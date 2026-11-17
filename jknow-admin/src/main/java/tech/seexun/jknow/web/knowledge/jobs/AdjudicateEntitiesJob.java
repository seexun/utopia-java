package tech.seexun.jknow.web.knowledge.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.ReviewReasons;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.extract.ExtractLib;
import tech.seexun.jknow.extract.ExtractLib.AdjudicationPair;
import tech.seexun.jknow.extract.ExtractLib.AdjudicationSide;
import tech.seexun.jknow.extract.ExtractLib.AdjudicationVerdict;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.governance.SecondLookPort;
import tech.seexun.jknow.web.knowledge.governance.SecondLookPort.Look;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.ExecutionGate;
import tech.seexun.jknow.store.ExecutionGateService;
import tech.seexun.jknow.store.GovernanceService;
import tech.seexun.jknow.store.GovernanceService.NewDecision;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.Precedents;
import tech.seexun.jknow.store.entity.ReviewItem;
import tech.seexun.jknow.store.entity.ReviewItem.ReviewSide;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * 实体消解攒批裁决任务（adjudication.rs 566 行逐语义）：消费审核队列中
 * stage=adjudicating 的灰区对。先查裁决缓存，缓存未命中的攒成一批（一次 LLM 调用
 * 裁多对）；高置信 same → 自动合并（可回滚，过执行闸门），高置信 different →
 * 自动保持分开，其余转人工。未配模型时全部转人工——本任务失败或缺席都不影响
 * 抽取与查询。
 *
 * <p><b>与 Rust 的两处 Java 化取舍</b>：攒批调用走
 * {@link LlmSupport#chatRetryingRateLimits}（Rust adjudication.rs:249-254 是
 * acquire_chat 许可 + 单发 chat，调用/解析失败交给任务层退避重试；Java 侧同一把
 * 许可内联在退避重试里，会自愈的限流/闪断当场退避重试，耗尽后照旧失败任务——
 * 对外语义不变：行停留在队列里，人工仍可定夺）。且 chatRetryingRateLimits 隐含
 * <b>流式</b>读法（Rust 此处是非流式 chat）——内容等价、超时语义更好（任务 3 审查 M-1 补记）。
 */
@Component
public class AdjudicateEntitiesJob {

    private static final Logger log = LoggerFactory.getLogger(AdjudicateEntitiesJob.class);

    /** adjudication.rs:17 BATCH_SIZE：一批攒多少对问模型。 */
    static final long BATCH_SIZE = 12;

    /** adjudication.rs:18 AUTO_CONF：自动动手的线（低于治理的 0.85——只看这一对）。 */
    static final float AUTO_CONF = 0.8f;

    /** adjudication.rs:19 MAX_ROUNDS：一次任务最多跑几轮（防死循环——模型老答不上来就放给人）。 */
    static final int MAX_ROUNDS = 20;

    /**
     * adjudication.rs:86 SECOND_LOOK_UNAVAILABLE：第二眼没跑成时上交的理由
     * （相似提的 same 不能照攒批的看法合——#889）。
     */
    public static final String SECOND_LOOK_UNAVAILABLE = "escalate_unsure|second_look_unavailable";

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ====================================================================
    // PER_KB 进程内串行（adjudication.rs:137-165）

    /**
     * 同一个库的裁决一次只跑一个（与 ontology_index::PER_KB 同一套理由）。
     *
     * <p>入队去重只挡得住「还排着队的」；一批文档陆续抽完时，后一个入队时前一个
     * 已经是 running，于是七个任务同时跑同一个库。它们各自 pending_adjudications
     * 拿到<b>同一批</b>待裁项：模型调用翻七倍，而两个任务对同一对下判断时，第二个
     * 撞 agent_decisions_open_idx 唯一索引直接失败（2026-09-08 实测：七个任务里
     * 两个这样挂掉）。
     *
     * <p><b>等而不是跳过</b>：后到的等前一个跑完再进（不跳过、不失败），进来时
     * 重新查一遍待裁项，通常只剩前一个读完之后才新增的那几对。表项只增不删
     * （每库一把锁，量级 = 库数，Rust 同款不清理）。
     */
    private static final ConcurrentHashMap<UUID, ReentrantLock> PER_KB =
            new ConcurrentHashMap<>();

    /** adjudication.rs:158-165 lock_for：取这一库的锁（第一次用时建；测试借它占位验串行）。 */
    public static ReentrantLock lockFor(UUID kbId) {
        return PER_KB.computeIfAbsent(kbId, k -> new ReentrantLock());
    }

    // ====================================================================
    // 纯函数（adjudication.rs:23-93——内联测试 AdjudicationPureTests 断言不改写）

    /**
     * adjudication.rs:23-38 {@code pair_key}：缓存键 = 类型 + 双方名字 + 事实摘要
     * + 先例（与实体 id 无关——重传文档不重复付费）。<b>先例也进键</b>：答案随先例变
     * （0025 说的正是这个，治理那一路因此干脆不用缓存）；人又裁了一笔，键就变，
     * 旧答案自然作废，不必去清。
     */
    static String pairKey(ReviewItem item, List<String> precedents) {
        String[] sides = {sideKey(item.left()), sideKey(item.right())};
        java.util.Arrays.sort(sides);
        return sha256(String.join("##", sides) + "##" + String.join("\n", precedents));
    }

    private static String sideKey(ReviewSide s) {
        // 没判出类型的一侧照样要能缓存（0009）
        return s.name().toLowerCase(Locale.ROOT) + "|"
                + (s.typeLabel() != null ? s.typeLabel() : "untyped") + "|"
                + String.join(";", s.topFacts());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) { // SHA-256 恒可用
            throw new IllegalStateException(e);
        }
    }

    /**
     * adjudication.rs:42-57 {@code wants_another_look}：攒批没定的对要不要带工具再看
     * 一遍（0028）：没判决或把握不到线；硬规则拦得住的不看（大类不同、版本尾巴、
     * 含名字的一句话——再看也改不了规则）；有撤回的不看，那是人的事。
     */
    static boolean wantsAnotherLook(ReviewItem item, Precedents p, Boolean same, float conf) {
        boolean unsettled = same == null || conf < AUTO_CONF;
        GovernanceService.NameShape shape =
                GovernanceService.nameShape(item.left().name(), item.right().name());
        boolean ruled = GovernanceService.typesConflict(
                item.left().typeLabel(), item.right().typeLabel())
                || shape == GovernanceService.NameShape.VERSION
                || shape == GovernanceService.NameShape.PHRASE;
        return unsettled && !ruled && p.reverts().isEmpty();
    }

    /**
     * adjudication.rs:69-76 {@code needs_second_look}：攒批的看法之外还要不要第二眼
     * ——判不定的要看，相似提的 same 必看（0041 第 2 刀）。
     */
    static boolean needsSecondLook(ReviewItem item, Precedents p, Boolean same, float conf) {
        return wantsAnotherLook(item, p, same, conf) || !batchVerdictMayApply(item, same);
    }

    /**
     * adjudication.rs:81-83 {@code batch_verdict_may_apply}：攒批那一眼的看法能不能
     * 不经第二眼就落地。名字向量提的对说 same 不能：第二眼没跑成（预算用完、模型
     * 出错）就上交给人，不照攒批的看法合，也不把那个看法记进缓存——记了之后这一对
     * 每次再来都从缓存直接合，第二眼永远轮不到（#889 评审）。
     */
    public static boolean batchVerdictMayApply(ReviewItem item, Boolean same) {
        return !(ReviewReasons.similarityProposed(item.reason()) && Boolean.TRUE.equals(same));
    }

    /** adjudication.rs:151-156 {@code batch_name}：一批的名字（第一对的两个名字），日志认批用。 */
    public static String batchName(List<AdjudicationPair> pairs) {
        return pairs.isEmpty() ? "(empty)"
                : pairs.get(0).left().name() + " / " + pairs.get(0).right().name();
    }

    // ====================================================================
    // 主体

    private final ResolutionService resolution;
    private final GovernanceService governance;
    private final ExecutionGateService gate;
    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final LlmSupport llm;
    private final AuditService audit;
    private final AppEventBus events;
    private final ObjectProvider<SecondLookPort> secondLookProvider;
    private volatile SecondLookPort secondLookResolved;

    public AdjudicateEntitiesJob(ResolutionService resolution, GovernanceService governance,
                                 ExecutionGateService gate, KbMapper kbs,
                                 SettingsService settingsService, LlmSupport llm,
                                 AuditService audit, AppEventBus events,
                                 ObjectProvider<SecondLookPort> secondLookProvider) {
        this.resolution = resolution;
        this.governance = governance;
        this.gate = gate;
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.llm = llm;
        this.audit = audit;
        this.events = events;
        this.secondLookProvider = secondLookProvider;
    }

    /** 第二眼端口：任务 4 注册真身 bean 即自动接管，缺省「不可用」（#889 分支）。 */
    private SecondLookPort secondLook() {
        if (secondLookResolved == null) {
            secondLookResolved = secondLookProvider == null
                    ? SecondLookPort.UNAVAILABLE
                    : secondLookProvider.getIfAvailable(() -> SecondLookPort.UNAVAILABLE);
        }
        return secondLookResolved;
    }

    /** main.rs:616-623 dispatch 桩：payload 的 kb_id。 */
    public void handle(Job job) throws Exception {
        JsonNode kbNode = job.payload().get("kb_id");
        if (kbNode == null || !kbNode.isTextual()) {
            throw new IllegalStateException("payload 缺少 kb_id");
        }
        adjudicate(UUID.fromString(kbNode.asText()));
    }

    /** adjudication.rs:167-370 {@code adjudicate_entities}。 */
    public void adjudicate(UUID kbId) throws Exception {
        ReentrantLock serial = lockFor(kbId);
        // 等而不是跳过（见 PER_KB 注释）：等前一个跑完，进来时重查待裁项
        serial.lock();
        try {
            runAdjudication(kbId);
        } finally {
            serial.unlock();
        }
    }

    private void runAdjudication(UUID kbId) throws Exception {
        KbRow kb = kbs.selectById(kbId);
        if (kb == null) {
            throw AppException.NOT_FOUND;
        }
        LlmSettingsRow settings = settingsService.get(kb.workspaceId());
        ChatClient client = LlmSupport.chatClient(settings);
        String model = settings != null && settings.chatModel() != null
                ? settings.chatModel() : "";

        if (client == null) {
            // 无模型可用：全部转人工，任务本身成功结束（缺席不影响抽取与查询）
            List<ReviewItem> items = resolution.pendingAdjudications(kbId, 500);
            for (ReviewItem item : items) {
                resolution.escalateReview(item.id(), "escalate_no_model");
            }
            events.emitReview(kbId);
            return;
        }
        // 第二层的行都挂在这一次任务上
        UUID runId = Uuids.nowV7();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<ReviewItem> items = resolution.pendingAdjudications(kbId, BATCH_SIZE);
            if (items.isEmpty()) {
                break;
            }

            // 第一层：裁决缓存。先例（人在这个库里对这些名字做过什么，连同他们写的
            // 理由）先取出来：它既进提示词也进缓存键
            List<ToAsk> toAsk = new ArrayList<>();
            for (ReviewItem item : items) {
                Precedents p = governance.precedentsFor(kbId, item);
                List<String> precedents = GovernanceService.renderLines(p);
                String key = pairKey(item, precedents);
                ResolutionService.CachedVerdict cached = resolution.getVerdict(kbId, key);
                if (cached != null) {
                    applyVerdict(kbId, item, cached.same(), cached.confidence(), "cached", null);
                } else {
                    toAsk.add(new ToAsk(item, key, p, precedents));
                }
            }
            if (toAsk.isEmpty()) {
                continue;
            }

            // 第二层：攒批 LLM 裁决
            List<AdjudicationPair> pairs = new ArrayList<>(toAsk.size());
            for (ToAsk ask : toAsk) {
                ReviewItem item = ask.item();
                pairs.add(new AdjudicationPair(
                        sideOf(item.left()), sideOf(item.right()),
                        ask.lines(),
                        ExtractLib.proposedBecause(
                                ReviewReasons.nameVectorCosine(item.reason()))));
            }
            List<ChatClient.ChatMessage> messages = ExtractLib.buildAdjudicationMessages(pairs);
            // 调用/解析失败 → 任务按退避重试；重试耗尽后行停留在队列里，人工仍可定夺
            ChatClient.Reply reply =
                    llm.chatRetryingRateLimits(settings, client, messages, null);
            ExtractLib.AdjudicationReply parsed =
                    ExtractLib.parseAdjudicationRepairing(reply.text());
            List<AdjudicationVerdict> verdicts = parsed.verdicts();
            if (parsed.repaired()) {
                // 模型在引号里的理由中间直接换行：修补后照读，但记下是哪一批（#894/#895 同款）
                log.warn("kb={} pairs={} first_pair={} 裁决回复的字符串里有裸控制字符，转义后才解开",
                        kbId, pairs.size(), batchName(pairs));
            }
            Map<Long, AdjudicationVerdict> byIndex = new HashMap<>();
            for (AdjudicationVerdict v : verdicts) {
                byIndex.put(v.i(), v);
            }

            for (int idx = 0; idx < toAsk.size(); idx++) {
                ToAsk ask = toAsk.get(idx);
                ReviewItem item = ask.item();
                AdjudicationVerdict v = byIndex.get((long) idx);
                if (v == null) {
                    resolution.escalateReview(item.id(), "escalate_no_verdict");
                    continue;
                }
                Boolean same = switch (v.verdict()) {
                    case "same" -> Boolean.TRUE;
                    case "different" -> Boolean.FALSE;
                    default -> null;
                };
                float conf = v.confidence() == null ? 0.5f
                        : Math.max(0f, Math.min(1f, v.confidence()));
                // 第二层（0028）：攒批没定的，带工具再看一遍再落地。预算用完或
                // 循环没跑成就照攒批的看法办
                if (needsSecondLook(item, ask.precedents(), same, conf)) {
                    Look earlier = Look.fromBatch(same, conf, v.why());
                    Optional<Look> looked = secondLook().lookAgain(kbId, client, settings,
                            item, pairs.get(idx), earlier);
                    if (looked.isPresent()) {
                        Look look = looked.get();
                        Outcome outcome;
                        if (look.same() != null) {
                            resolution.putVerdict(kbId, ask.key(), look.same(), look.conf(),
                                    model);
                            outcome = applyVerdict(kbId, item, look.same(), look.conf(),
                                    "investigated", look.why());
                        } else {
                            // 问了一个问题，或看了没结论：留给人，卡片上带着建议与问题
                            resolution.escalateReview(item.id(), "proposed");
                            outcome = Outcome.ESCALATED;
                        }
                        recordLook(kbId, runId, item, ask.precedents(), look, outcome);
                        continue;
                    }
                    // 第二眼没跑成：相似提的 same 不能照攒批的看法合，也不进缓存
                    if (!batchVerdictMayApply(item, same)) {
                        resolution.escalateReview(item.id(), SECOND_LOOK_UNAVAILABLE);
                        continue;
                    }
                }
                resolution.putVerdict(kbId, ask.key(), same, conf, model);
                applyVerdict(kbId, item, same, conf, "adjudicated", v.why());
            }
            // 本轮裁决落库完毕，推给前端刷新审核队列
            events.emitReview(kbId);
        }
    }

    /** 攒批前的一项：待裁对 + 它的缓存键 + 先例（提示词与键共用）。 */
    private record ToAsk(ReviewItem item, String key, Precedents precedents,
                         List<String> lines) {
    }

    private static AdjudicationSide sideOf(ReviewSide s) {
        return new AdjudicationSide(s.name(),
                s.typeLabel() != null ? s.typeLabel() : "untyped", s.topFacts());
    }

    /** adjudication.rs:89-93 Outcome：一次裁决落地成了什么（第二层的行按它记 applied/proposed）。 */
    private record Outcome(Type type, UUID mergeId) {

        enum Type {MERGED, KEPT, ESCALATED}

        static final Outcome ESCALATED = new Outcome(Type.ESCALATED, null);
    }

    /**
     * adjudication.rs:97-135 {@code record_look}：第二层看过的一对，记一行
     * agent_decisions（0028）——轨迹、问题、花的调用都在里面。预算按这些行算，
     * Agent 队列也从这里读：治理开没开，机器去看过的都看得见。
     */
    private void recordLook(UUID kbId, UUID runId, ReviewItem item, Precedents p, Look look,
                            Outcome outcome) {
        String action = look.same() == null ? "unsure"
                : look.same() ? "merge" : "keep";
        String status;
        UUID mergeId;
        switch (outcome.type()) {
            case MERGED -> {
                status = "applied";
                mergeId = outcome.mergeId();
            }
            case KEPT -> {
                status = "applied";
                mergeId = null;
            }
            default -> {
                status = "proposed";
                mergeId = null;
            }
        }
        ArrayNode trace = JSON.createArrayNode();
        look.trace().forEach(trace::add);
        governance.record(kbId, new NewDecision(runId, item.id(), action, look.conf(),
                look.why(), GovernanceService.precedentsJson(p), status, mergeId,
                look.question(), trace, look.calls()));
    }

    /**
     * adjudication.rs:372-493 {@code apply_verdict}：一次判决落地。有把握就动手，
     * 不再抽一成给人（0026 修订）——队列里等人的，只剩机器拿不准、或闸门说合了会
     * 送出图外的那些。
     */
    private Outcome applyVerdict(UUID kbId, ReviewItem item, Boolean same, float conf,
                                 String via, String why) {
        if (Boolean.TRUE.equals(same) && conf >= AUTO_CONF) {
            // 执行闸门（0027）：合并会立刻送出图外的东西——违规、派生、答案——留给
            // 人，把握再高也不动手。人看到的是留下的原因，不是「裁决器没把握」
            ExecutionGate.Impact impact =
                    gate.impactOf(kbId, item.left().id(), item.right().id());
            ExecutionGate.Hold hold = ExecutionGate.hold(impact);
            if (hold != null) {
                resolution.escalateReview(item.id(), "escalate_impact|" + hold);
                return Outcome.ESCALATED;
            }
            ResolutionService.MergeDirection dir =
                    resolution.mergeDirection(item.left().id(), item.right().id());
            String reason = "auto_merged|" + via + " " + String.format(Locale.ROOT, "%.2f", conf);
            UUID mergeId;
            try {
                mergeId = resolution.mergeEntities(kbId, dir.source(), dir.target(), null,
                        reason);
            } catch (AppException e) {
                // 同批次连锁合并可能已吞掉其中一方：转人工而不是让任务失败
                if (e.kind() == AppException.Kind.CONFLICT
                        || e.kind() == AppException.Kind.NOT_FOUND) {
                    resolution.escalateReview(item.id(), "escalate_entity_changed");
                    return Outcome.ESCALATED;
                }
                throw e;
            }
            resolution.closeReviewAuto(item.id(), "merged", reason);
            // 决策台账：AI 自动合并（actor 为空 = 系统）
            audit.recordOpt(null, kbId, null, "review.merge", "review", item.id(),
                    auditDetail(item, conf, via, why).toString());
            return new Outcome(Outcome.Type.MERGED, mergeId);
        }
        if (Boolean.FALSE.equals(same) && conf >= AUTO_CONF) {
            String reason = "kept_apart|" + via + " " + String.format(Locale.ROOT, "%.2f", conf);
            resolution.closeReviewAuto(item.id(), "kept", reason);
            audit.recordOpt(null, kbId, null, "review.keep", "review", item.id(),
                    auditDetail(item, conf, via, why).toString());
            return new Outcome(Outcome.Type.KEPT, null);
        }
        resolution.escalateReview(item.id(), "escalate_unsure|" + via + " "
                + String.format(Locale.ROOT, "%.2f", conf));
        return Outcome.ESCALATED;
    }

    /** adjudication.rs:434-441/472-477 的台账 detail：why 为 null 也带键（serde json! 形状）。 */
    private static ObjectNode auditDetail(ReviewItem item, float conf, String via, String why) {
        ObjectNode detail = JSON.createObjectNode();
        detail.put("left", item.left().name());
        detail.put("right", item.right().name());
        detail.put("score", item.score());
        detail.put("confidence", conf);
        detail.put("via", via);
        // 模型的一句理由也留下：机器的行不是先例（0025 决定 1），但人看 Decisions
        // 时该看得见它凭什么
        if (why != null) {
            detail.put("why", why);
        } else {
            detail.putNull("why");
        }
        return detail;
    }
}
