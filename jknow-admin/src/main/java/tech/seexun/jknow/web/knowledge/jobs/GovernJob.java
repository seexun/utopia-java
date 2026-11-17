package tech.seexun.jknow.web.knowledge.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.ReviewReasons;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.extract.ExtractLib;
import tech.seexun.jknow.extract.ExtractLib.AdjudicationPair;
import tech.seexun.jknow.extract.ExtractLib.AdjudicationSide;
import tech.seexun.jknow.extract.ExtractLib.AdjudicationVerdict;
import tech.seexun.jknow.extract.GovernorPrompts;
import tech.seexun.jknow.extract.GovernorPrompts.Step;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.governance.SecondLookPort;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.ExecutionGate;
import tech.seexun.jknow.store.ExecutionGate.Hold;
import tech.seexun.jknow.store.ExecutionGate.Impact;
import tech.seexun.jknow.store.ExecutionGateService;
import tech.seexun.jknow.store.GovernanceService;
import tech.seexun.jknow.store.GovernanceService.BaseLock;
import tech.seexun.jknow.store.GovernanceService.Gate;
import tech.seexun.jknow.store.GovernanceService.NameShape;
import tech.seexun.jknow.store.GovernanceService.NewDecision;
import tech.seexun.jknow.store.GovernanceService.TypeFamily;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.ResolutionService.MergeDirection;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.Namesake;
import tech.seexun.jknow.store.entity.Precedent;
import tech.seexun.jknow.store.entity.Precedents;
import tech.seexun.jknow.store.entity.ReviewItem;
import tech.seexun.jknow.store.entity.ReviewItem.ReviewSide;
import tech.seexun.jknow.store.mapper.GovernanceMapper.QuoteRow;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * 治理任务（0025，governance.rs 1042 行逐语义）：开关开着就把等人的重复对按先进先出
 * 过一遍，先读台账再裁。
 *
 * <p>人裁过的一对（同一对实体，或名字不同的同一对名字）照人的定，不问模型。其余两层：
 * 第一层是攒批——队头带着它的簇进一次模型调用，先例已经在提示词里；过闸的自己动手
 * （合并可撤、分开可再合）。第二层（第二刀）只接第一层<b>判不定</b>的对：逐条带工具
 * 再看一遍——一侧的全部事实、原文片段、台账里人对这个名字的决定、同名的其他实体、
 * 合并会牵动什么——看完要么 decide，要么 defer 留一个具体的问题给人。硬规则拦下的对
 * （类型冲突、有撤回、与人对这一对的决定相反）不进第二层：再看也不会改规则。
 *
 * <p>两簇之间看一眼开关，关掉就停在这里——「关闭后队列自动终止」就是那一行。模型
 * 缺席时任务成功结束、什么都不动：没有依据的裁决不如不裁，队列原地等。
 *
 * <p>与攒批裁决器（{@link AdjudicateEntitiesJob}）的分工：开关关着，那一档照旧独自判
 * 灰区对；开着，抽取结束排的是这里，灰区对与等人的对都从这条队列走。这里不读也不写
 * 裁决缓存——缓存键里没有先例，而这里的答案随先例变。两个 agent 共用同一个第二层
 * （{@link SecondLookPort}——本类即其真身，注册为 bean 后裁决器的懒取自动接管）。
 */
@Component
public class GovernJob implements SecondLookPort {

    private static final Logger log = LoggerFactory.getLogger(GovernJob.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** governance.rs:33 BATCH_SIZE：一次模型调用带几对——队头加它的簇。 */
    static final long BATCH_SIZE = 12;

    /** governance.rs:35 MAX_ROUNDS：一个任务最多走几轮，之后再排一个接着走——让别的库的任务也轮得上。 */
    static final int MAX_ROUNDS = 20;

    /**
     * governance.rs:38 LOOP_DAILY_CALLS：每库每天第二层最多花几次模型调用。用完了，
     * 判不定的对照第一刀写成建议。300 在一次 229 块的导入上第一轮就用光了
     * （132 对花了 301 次），所以是 2000。
     */
    static final long LOOP_DAILY_CALLS = 2000;

    /** governance.rs:40 TOOL_OUTPUT_CHARS：一次工具结果最多给模型多少字——原文片段与事实列表都可能很长。 */
    static final int TOOL_OUTPUT_CHARS = 3000;

    /**
     * governance.rs:155 CLUSTERS_PER_ROUND：一轮里并行裁几簇。只有互不共享实体、也不
     * 共享名字的簇才能同一轮：一簇里的合并会改另一簇看到的东西。模型那边的并发由
     * LlmSupport 的闸门限，这里只决定一次挂几个。
     */
    static final int CLUSTERS_PER_ROUND = 3;

    /** governance.rs:157 HEADS_TO_SCAN：挑簇时从队头往后看多少对。 */
    static final long HEADS_TO_SCAN = 60;

    /** ledger 行的日期形态（governance.rs:835 %Y-%m-%d）。 */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 一轮里到处要带的东西（governance.rs:42-49 Ctx）。 */
    private record Ctx(UUID kbId, UUID runId, ChatClient client, LlmSettingsRow settings) {
    }

    /** 一簇：等人的对、各自的先例、给模型看的样子、已经有的看法（人定过的快路）。 */
    private static final class Cluster {

        final List<ReviewItem> items;
        final List<Precedents> precedents;
        final List<AdjudicationPair> pairs;
        /** null = 还没有看法（人没裁过、模型没答） */
        final List<Look> looks;

        Cluster(List<ReviewItem> items, List<Precedents> precedents,
                List<AdjudicationPair> pairs, List<Look> looks) {
            this.items = items;
            this.precedents = precedents;
            this.pairs = pairs;
            this.looks = looks;
        }
    }

    private final GovernanceService governance;
    private final ResolutionService resolution;
    private final ExecutionGateService gateService;
    private final ReviewService reviews;
    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final LlmSupport llm;
    private final AuditService audit;
    private final AppEventBus events;
    private final JobsService jobs;

    /** 攒批与第二层的并行（Rust join_all 的有界等价：至多 CLUSTERS_PER_ROUND 路同时）。 */
    private final ExecutorService parallel;

    public GovernJob(GovernanceService governance, ResolutionService resolution,
                     ExecutionGateService gateService, ReviewService reviews, KbMapper kbs,
                     SettingsService settingsService, LlmSupport llm, AuditService audit,
                     AppEventBus events, JobsService jobs) {
        this.governance = governance;
        this.resolution = resolution;
        this.gateService = gateService;
        this.reviews = reviews;
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.llm = llm;
        this.audit = audit;
        this.events = events;
        this.jobs = jobs;
        this.parallel = Executors.newFixedThreadPool(CLUSTERS_PER_ROUND, r -> {
            Thread t = new Thread(r, "govern-look");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        parallel.shutdownNow();
    }

    // ====================================================================
    // 入口（governance.rs:103-151 govern）

    /** dispatch 桩位：payload 的 kb_id。 */
    public void handle(Job job) throws Exception {
        JsonNode kbNode = job.payload().get("kb_id");
        if (kbNode == null || !kbNode.isTextual()) {
            throw new IllegalStateException("payload 缺少 kb_id");
        }
        govern(UUID.fromString(kbNode.asText()));
    }

    /**
     * governance.rs:103-151 {@code govern}：开关关着直接成功返回；没配聊天模型成功返回
     * （队列原地等——没有依据的裁决不如不裁）。
     *
     * <p>一个库一次只跑一个治理任务。每篇文档抽完都排一个，而排队的去重只挡排着的、
     * 不挡在跑的：抢不到锁就说明有人在治理这个库，它会把队列走完，走完还有积压会再排
     * 一个。它读完队头之后才进来的对它看不见，所以这里隔一分钟再排一个——<b>等而不跳</b>
     * ——排着的至多一个，跑着的也只有它。
     */
    public void govern(UUID kbId) throws Exception {
        KbRow kb = kbs.selectById(kbId);
        if (kb == null) {
            throw AppException.NOT_FOUND;
        }
        if (!kb.governance()) {
            return;
        }
        LlmSettingsRow settings = settingsService.get(kb.workspaceId());
        ChatClient client = LlmSupport.chatClient(settings);
        if (client == null) {
            log.info("治理：没有配聊天模型，队列原地等 kb={}", kbId);
            return;
        }
        BaseLock baseLock = governance.tryLockBase(kbId);
        if (baseLock == null) {
            log.info("治理：这个库已有任务在跑，一分钟后再看一眼 kb={}", kbId);
            jobs.enqueueUnlessQueuedAfter("govern", payload(kbId), java.time.Duration.ofSeconds(60));
            return;
        }
        Ctx ctx = new Ctx(kbId, Uuids.nowV7(), client, settings);

        // 上一次任务半路留下的锁先放掉；跑完（不管怎么结束的）再放一次
        governance.releaseLocks(kbId);
        boolean more = false;
        Exception failure = null;
        try {
            more = rounds(ctx);
        } catch (Exception e) {
            failure = e;
        }
        try {
            governance.releaseLocks(kbId);
        } catch (RuntimeException e) {
            log.warn("治理：放锁失败 kb={} error={}", kbId, e.getMessage());
        }
        baseLock.release();
        events.emitReview(kbId);
        if (failure != null) {
            throw failure;
        }
        // 轮数用完还有积压：再排一个，下一轮从队头接着走
        if (more) {
            jobs.enqueueUnlessQueued("govern", payload(kbId));
        }
    }

    private static JsonNode payload(UUID kbId) {
        return JSON.createObjectNode().put("kb_id", kbId.toString());
    }

    // ====================================================================
    // 主循环（governance.rs:168-304 rounds）

    /**
     * 一轮轮走到队列空、开关关或轮数用完。
     *
     * @return true = 轮数用完还有积压（该再排一个接着走）
     */
    private boolean rounds(Ctx ctx) throws Exception {
        UUID kbId = ctx.kbId();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            // 关闭后队列自动终止：每一轮之前看一眼
            KbRow kb = kbs.selectById(kbId);
            if (kb == null) {
                throw AppException.NOT_FOUND;
            }
            if (!kb.governance()) {
                log.info("治理：开关已关，停在这里 kb={}", kbId);
                return false;
            }

            // 从队头起挑互不重叠的簇，最多 K 簇；和已取的簇有交集的头留到下一轮
            List<ReviewItem> heads = governance.queue(kbId, HEADS_TO_SCAN);
            if (heads.isEmpty()) {
                return false;
            }
            List<List<ReviewItem>> clusters = new ArrayList<>();
            Set<UUID> takenIds = new HashSet<>();
            Set<UUID> takenEntities = new HashSet<>();
            Set<String> takenNames = new HashSet<>();
            for (ReviewItem head : heads) {
                if (clusters.size() >= CLUSTERS_PER_ROUND) {
                    break;
                }
                if (takenIds.contains(head.id())) {
                    continue;
                }
                List<ReviewItem> items = new ArrayList<>();
                items.add(head);
                items.addAll(governance.clusterOf(kbId, head, BATCH_SIZE - 1));
                boolean overlaps = items.stream().anyMatch(i ->
                        takenIds.contains(i.id())
                                || takenEntities.contains(i.left().id())
                                || takenEntities.contains(i.right().id())
                                || takenNames.contains(i.left().name().toLowerCase(Locale.ROOT))
                                || takenNames.contains(i.right().name().toLowerCase(Locale.ROOT)));
                if (overlaps) {
                    continue;
                }
                for (ReviewItem i : items) {
                    takenIds.add(i.id());
                    takenEntities.add(i.left().id());
                    takenEntities.add(i.right().id());
                    takenNames.add(i.left().name().toLowerCase(Locale.ROOT));
                    takenNames.add(i.right().name().toLowerCase(Locale.ROOT));
                }
                clusters.add(items);
            }
            // 这几簇归 agent 了：人在这几分钟里不能裁它们，界面上看得见
            governance.lock(kbId, new ArrayList<>(takenIds));
            events.emitReview(kbId);

            // 先例与快路（库操作，顺序做）——人裁过这一对的照人的定，不问模型
            List<Cluster> roundClusters = new ArrayList<>(clusters.size());
            for (List<ReviewItem> items : clusters) {
                List<Precedents> precedents = new ArrayList<>(items.size());
                for (ReviewItem item : items) {
                    precedents.add(governance.precedentsFor(kbId, item));
                }
                List<AdjudicationPair> pairs = new ArrayList<>(items.size());
                for (int i = 0; i < items.size(); i++) {
                    pairs.add(pairOf(items.get(i), precedents.get(i)));
                }
                List<Look> looks = new ArrayList<>();
                for (int idx = 0; idx < items.size(); idx++) {
                    ReviewItem item = items.get(idx);
                    Boolean prior = governance.decidedBefore(kbId, item.left().id(),
                            item.right().id());
                    Boolean settled = GovernanceService.settledByPeople(item.left().name(),
                            item.right().name(), precedents.get(idx), prior);
                    looks.add(settled == null ? null : fromPeople(settled));
                }
                roundClusters.add(new Cluster(items, precedents, pairs, looks));
            }

            // 攒批：每簇一次调用，几簇同时挂。任何一次失败整轮失败，任务按退避重试；
            // 重试耗尽后这些对留在队列里，人照样能裁
            List<Callable<Void>> batches = new ArrayList<>();
            for (Cluster c : roundClusters) {
                batches.add(() -> {
                    batchLooks(ctx, c);
                    return null;
                });
            }
            runAll(batches);

            // 第二层：判不定的、同名却说不同的，带工具再看一遍——几对同时看，
            // 模型那边由闸门限并发
            List<Integer> secondCi = new ArrayList<>();
            List<Integer> secondIdx = new ArrayList<>();
            for (int ci = 0; ci < roundClusters.size(); ci++) {
                Cluster c = roundClusters.get(ci);
                for (int idx = 0; idx < c.items.size(); idx++) {
                    Look look = c.looks.get(idx);
                    if (look != null && wantsSecondLook(c.items.get(idx), c.precedents.get(idx),
                            look)) {
                        secondCi.add(ci);
                        secondIdx.add(idx);
                    }
                }
            }
            List<Callable<Optional<Look>>> lookTasks = new ArrayList<>();
            for (int i = 0; i < secondCi.size(); i++) {
                Cluster c = roundClusters.get(secondCi.get(i));
                ReviewItem item = c.items.get(secondIdx.get(i));
                AdjudicationPair pair = c.pairs.get(secondIdx.get(i));
                Look earlier = c.looks.get(secondIdx.get(i));
                lookTasks.add(() -> secondLook(ctx, item, pair, earlier));
            }
            List<Optional<Look>> seen = runAll(lookTasks);
            for (int i = 0; i < seen.size(); i++) {
                Optional<Look> looked = seen.get(i);
                if (looked.isPresent()) {
                    roundClusters.get(secondCi.get(i)).looks.set(secondIdx.get(i), looked.get());
                }
            }

            // 落地：按簇、按对顺序写库——连锁合并要按顺序
            for (Cluster c : roundClusters) {
                for (int idx = 0; idx < c.items.size(); idx++) {
                    Look look = c.looks.get(idx);
                    if (look == null) {
                        look = Look.fromBatch(null, 0f, null);
                    }
                    apply(ctx, c.items.get(idx), c.precedents.get(idx), look);
                }
            }
            events.emitReview(kbId);
        }
        return !governance.queue(kbId, 1).isEmpty();
    }

    /**
     * join_all 的有界等价（3 线程 = CLUSTERS_PER_ROUND）：全部提交、按序收账。收账
     * 时机与 Rust 有一差：join_all 全部跑完后 zip 循环才撞第一个 Err，这里 f.get()
     * 按提交序撞到第一个失败即抛（其余任务照跑完但不收账）——安全前提是这些任务
     * <b>只写各自的内存 Cluster、不碰库</b>（batchLooks/secondLook 均如此），早抛
     * 不产生半写，对外仍是「整轮失败、任务按退避重试」。
     */
    private <T> List<T> runAll(List<Callable<T>> tasks) throws Exception {
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(parallel.submit(task));
        }
        List<T> out = new ArrayList<>(tasks.size());
        for (Future<T> f : futures) {
            try {
                out.add(f.get());
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception ex) {
                    throw ex;
                }
                if (e.getCause() instanceof Error err) {
                    throw err;
                }
                throw e;
            }
        }
        return out;
    }

    // ====================================================================
    // 第一层：攒批（governance.rs:306-398）

    /**
     * 一簇的攒批调用（governance.rs:307-353）：人定过的不问，其余一次问完；看法写回
     * 本簇。攒批调用走 {@link LlmSupport#chatRetryingRateLimits}（Rust 是许可+单发，
     * Java 同一把许可内联退避——任务 3 已记的取舍：会自愈的限流/闪断当场重试，耗尽照旧
     * 失败任务，对外语义不变）。
     */
    private void batchLooks(Ctx ctx, Cluster c) throws Exception {
        List<Integer> asked = new ArrayList<>();
        for (int i = 0; i < c.items.size(); i++) {
            if (c.looks.get(i) == null) {
                asked.add(i);
            }
        }
        if (asked.isEmpty()) {
            return;
        }
        List<AdjudicationPair> batch = new ArrayList<>(asked.size());
        for (int i : asked) {
            batch.add(c.pairs.get(i));
        }
        List<ChatClient.ChatMessage> messages = ExtractLib.buildAdjudicationMessages(batch);
        ChatClient.Reply reply =
                llm.chatRetryingRateLimits(ctx.settings(), ctx.client(), messages, null);
        ExtractLib.AdjudicationReply parsed = ExtractLib.parseAdjudicationRepairing(reply.text());
        if (parsed.repaired()) {
            // 模型在引号里的理由中间直接换行：修补后照读，但记下是哪一簇（#894/#895 同款）
            log.warn("kb={} run={} pairs={} first_pair={} 裁决回复的字符串里有裸控制字符，转义后才解开",
                    ctx.kbId(), ctx.runId(), batch.size(), AdjudicateEntitiesJob.batchName(batch));
        }
        Map<Long, AdjudicationVerdict> byIndex = new HashMap<>();
        for (AdjudicationVerdict v : parsed.verdicts()) {
            byIndex.put(v.i(), v);
        }
        for (int n = 0; n < asked.size(); n++) {
            int idx = asked.get(n);
            AdjudicationVerdict v = byIndex.get((long) n);
            Look look;
            if (v == null) {
                look = Look.fromBatch(null, 0f, null);
            } else {
                Boolean same = switch (v.verdict()) {
                    case "same" -> Boolean.TRUE;
                    case "different" -> Boolean.FALSE;
                    default -> null;
                };
                float conf = v.confidence() == null ? 0.5f
                        : Math.max(0f, Math.min(1f, v.confidence()));
                look = Look.fromBatch(same, conf, v.why());
            }
            c.looks.set(idx, look);
        }
    }

    /**
     * governance.rs:362-398 pair_of：审核行变成给模型看的一对。
     * 同名且大类不冲突的对两侧写同一个类型标签：抽取器给同一家公司的两条记录 Store 与
     * Organization，模型就拿这个当「不同」的理由——把拐杖拿掉，让它看事实。只在两侧
     * 都归得到同一个大类（人、组织、地点、事件）时才共用：Periodical 对 Service、
     * VideoGame 对没类型，那些标签是有信息的，留着。
     */
    private static AdjudicationPair pairOf(ReviewItem item, Precedents p) {
        boolean sameKind =
                GovernanceService.nameShape(item.left().name(), item.right().name())
                        == NameShape.IDENTICAL
                        && typeFamilyOf(item.left()) != null
                        && typeFamilyOf(item.left()) == typeFamilyOf(item.right());
        String shared = item.left().typeLabel() != null ? item.left().typeLabel()
                : item.right().typeLabel() != null ? item.right().typeLabel() : "untyped";
        return new AdjudicationPair(
                sideOf(item.left(), sameKind, shared),
                sideOf(item.right(), sameKind, shared),
                GovernanceService.renderLines(p),
                ExtractLib.proposedBecause(
                        ReviewReasons.nameVectorCosine(item.reason())));
    }

    private static TypeFamily typeFamilyOf(ReviewSide s) {
        return s.typeLabel() == null ? null : GovernanceService.typeFamily(s.typeLabel());
    }

    private static AdjudicationSide sideOf(ReviewSide s, boolean sameKind, String shared) {
        String label = sameKind ? shared
                : s.typeLabel() != null ? s.typeLabel() : "untyped";
        return new AdjudicationSide(s.name(), label, s.topFacts());
    }

    // ====================================================================
    // 第二层的入排（governance.rs:400-501）

    /**
     * governance.rs:405-427 wants_second_look：第一层没定（没判决、置信度不到线），或者
     * 同名、大类不冲突、模型却说不同——那是它最爱错的一种：都让第二层带着全部事实与
     * 原文再看一遍。名字的形状让人起疑的合并（版本尾巴、含着名字的一长串）也再看：
     * 名字只能让人起疑，定不了是不是一个东西——「Lease Agreement dated May 16, 2016,
     * as amended」与「Lease Agreement」形状上是一句话，事实上是同一份租约。类型冲突
     * 这条硬规则拦下的不进：再看也不会改规则。名字向量提的对说 same 不论把握多高都
     * 先带工具看一遍（与不带治理的裁决同一条规矩）；只看第一层的，第二层看过的不再看。
     */
    static boolean wantsSecondLook(ReviewItem item, Precedents p, Look look) {
        boolean typesConflict = GovernanceService.typesConflict(item.left().typeLabel(),
                item.right().typeLabel());
        NameShape shape = GovernanceService.nameShape(item.left().name(), item.right().name());
        boolean doubtedSplit = shape == NameShape.IDENTICAL && !typesConflict
                && Boolean.FALSE.equals(look.same()) && look.calls() == 0;
        boolean doubtedMerge = nameDoubts(shape) && !typesConflict
                && Boolean.TRUE.equals(look.same()) && look.calls() == 0;
        boolean similaritySame = !AdjudicateEntitiesJob.batchVerdictMayApply(item, look.same())
                && look.calls() == 0;
        return ((GovernanceService.gate(look.same(), look.conf(), typesConflict, shape, p)
                == Gate.PROPOSE && uncertain(look))
                || doubtedSplit || doubtedMerge || similaritySame)
                && p.reverts().isEmpty();
    }

    /** 名字形状让人起疑的合并：版本尾巴，或含着另一个名字的一长串（governance.rs:429-432）。 */
    static boolean nameDoubts(NameShape shape) {
        return shape == NameShape.VERSION || shape == NameShape.PHRASE;
    }

    /** 闸门看的名字形状（governance.rs:436-441）。 */
    static NameShape shapeForGate(ReviewItem item, Look look) {
        return settledShape(
                GovernanceService.nameShape(item.left().name(), item.right().name()),
                look.calls() > 0);
    }

    /**
     * 形状的疑点在第二层读过证据之后就答完了（governance.rs:443-450）：第二层带着两
     * 边的事实与原文看过、仍说是同一个的，形状的疑点已经由证据答过，不再按形状拦——
     * 拦的只剩没看过证据的第一层。
     */
    static NameShape settledShape(NameShape shape, boolean evidenceRead) {
        return evidenceRead && nameDoubts(shape) ? NameShape.UNRELATED : shape;
    }

    /** 人裁过这一对：照人的定，不问模型（governance.rs:77-87 Look::from_people）。 */
    private static Look fromPeople(boolean merged) {
        return new Look(merged, 1.0f, "a person decided this same pair before", null, List.of(),
                0);
    }

    /** 第一层没定：没判决，或置信度不到线（governance.rs:89-92）。 */
    private static boolean uncertain(Look look) {
        return look.same() == null || look.conf() < GovernanceService.AUTO_CONF;
    }

    /** merge | keep | unsure（governance.rs:94-100）。 */
    private static String actionOf(Look look) {
        return look.same() == null ? "unsure" : look.same() ? "merge" : "keep";
    }

    /**
     * 裁决器的入口（0028，governance.rs:454-471 look_again）：治理关着，攒批判不定的对
     * 也带工具再看一遍——同一个循环、同一份预算。每次调用自造 run_id：一次裁决任务里
     * 的每一对各是一条 run。
     */
    @Override
    public Optional<Look> lookAgain(UUID kbId, ChatClient client, LlmSettingsRow settings,
                                    ReviewItem item, AdjudicationPair pair, Look earlier) {
        return secondLook(new Ctx(kbId, Uuids.nowV7(), client, settings), item, pair, earlier);
    }

    /**
     * 第二层看一对（governance.rs:473-501）：预算够就看，看完的看法替掉第一层的；
     * 看不成（预算用完、模型出错）回 empty，照第一层的看法办。
     */
    private Optional<Look> secondLook(Ctx ctx, ReviewItem item, AdjudicationPair pair,
                                      Look look) {
        long spent;
        try {
            spent = governance.loopCallsToday(ctx.kbId());
        } catch (RuntimeException e) {
            log.warn("治理：数循环预算失败 kb={} error={}", ctx.kbId(), e.getMessage());
            return Optional.empty();
        }
        if (spent >= LOOP_DAILY_CALLS) {
            log.info("治理：今天的循环预算用完，判不定的只写建议 kb={} spent={}",
                    ctx.kbId(), spent);
            return Optional.empty();
        }
        try {
            return Optional.of(investigate(ctx, item, pair, look));
        } catch (Exception e) {
            // 第二层失败不拖累第一层：这一对照第一层的看法写成建议
            log.warn("治理：第二层没跑完 kb={} review={} error={}", ctx.kbId(), item.id(),
                    e.getMessage());
            return Optional.empty();
        }
    }

    // ====================================================================
    // 工具循环（governance.rs:651-756 investigate）

    /**
     * 第二层：带工具逐条再看。模型每回合要么查一样东西（查完把结果喂回去），要么
     * decide / defer 收尾。回合数封顶；模型不用工具收尾就提醒一次（NUDGE），再不收尾就
     * 当没定。
     *
     * <p>回合上限 = 查询次数 + 撞两次上限 + 一次提醒 + 收尾那一次。模型多半一回合只查
     * 一件事，查够 MAX_STEPS 次常常还想再查：撞上限的那一回合得算在预算外，不然它连
     * 收尾的机会都没有（identity bench 上，第二眼「看了没收尾」九次里有五次是这么来的）。
     */
    private Look investigate(Ctx ctx, ReviewItem item, AdjudicationPair pair, Look earlier)
            throws Exception {
        JsonNode tools = GovernorPrompts.tools();
        List<JsonNode> messages = new ArrayList<>(GovernorPrompts.messages(pair,
                new GovernorPrompts.EarlierLook(
                        earlier.same() == null ? "unsure" : earlier.same() ? "same" : "different",
                        earlier.conf(), earlier.why())));
        List<JsonNode> trace = new ArrayList<>();
        int calls = 0;
        boolean nudged = false;
        int walls = 0;
        int lookups = 0;

        for (int turn = 0; turn < GovernorPrompts.MAX_STEPS + 4; turn++) {
            ChatClient.AssistantTurn reply;
            Semaphore permit = llm.acquireChat(ctx.settings());
            try {
                reply = ctx.client().chatTools(messages, tools);
            } finally {
                if (permit != null) {
                    permit.release();
                }
            }
            calls++;
            messages.add(reply.toMessage());
            if (reply.toolCalls.isEmpty()) {
                // 没调工具就说话：记下它说了什么，下次看轨迹能知道它卡在哪
                ObjectNode said = JSON.createObjectNode();
                said.put("said", takeChars(reply.content == null ? "" : reply.content, 200));
                trace.add(said);
                if (nudged) {
                    break;
                }
                nudged = true;
                ObjectNode nudge = JSON.createObjectNode();
                nudge.put("role", "user");
                nudge.put("content", GovernorPrompts.NUDGE);
                messages.add(nudge);
                continue;
            }
            for (ChatClient.ToolCall call : reply.toolCalls) {
                Step step =
                        GovernorPrompts.readStep(call.name(), call.arguments());
                if (step instanceof Step.Decide d) {
                    return new Look(d.same(), d.confidence(),
                            d.why().isEmpty() ? null : d.why(), null, trace, calls);
                }
                if (step instanceof Step.Defer defer) {
                    return new Look(null, 0f, null, defer.question(), trace, calls);
                }
                if (step instanceof Step.Lookup lu) {
                    String out;
                    if (lookups >= GovernorPrompts.MAX_STEPS) {
                        walls++;
                        ObjectNode t = JSON.createObjectNode();
                        t.put("tool", lu.tool());
                        t.set("args", lu.args());
                        t.put("note", "refused: lookup limit");
                        trace.add(t);
                        out = GovernorPrompts.LIMIT_REACHED;
                    } else {
                        lookups++;
                        ToolOut result = lookup(ctx, item, lu.tool(), lu.args());
                        ObjectNode t = JSON.createObjectNode();
                        t.put("tool", lu.tool());
                        t.set("args", lu.args());
                        t.put("note", result.note());
                        trace.add(t);
                        out = result.out();
                    }
                    messages.add(ChatClient.toolResultMessage(call.id(), out));
                    continue;
                }
                // 认不出的工具或缺参数：把问题原话喂回去，别猜
                messages.add(ChatClient.toolResultMessage(call.id(),
                        ((Step.Unknown) step).problem()));
            }
            // 撞了两次上限还在查：不会收尾了，别再花回合
            if (walls >= 2) {
                break;
            }
        }
        // 看了，没收尾：当没定，轨迹留下
        return new Look(null, 0f, "the agent looked but did not conclude", null, trace, calls);
    }

    /** 跑一个工具的产出：给模型的文本 + 写进轨迹的一句话（governance.rs:758-764）。 */
    private record ToolOut(String out, String note) {
    }

    /**
     * 五工具（governance.rs:759-893）：facts / quotes / ledger / consequences /
     * namesakes——输出统一 3000 字截断（clip），note 进 trace。
     */
    private ToolOut lookup(Ctx ctx, ReviewItem item, String tool, JsonNode args) {
        UUID kbId = ctx.kbId();
        switch (tool) {
            case "facts" -> {
                String s = args.path("side").asText("A");
                ReviewSide e = side(s, item);
                List<String> lines = reviews
                        .entityFactLines(kbId, List.of(e.id()), 30)
                        .getOrDefault(e.id(), List.of());
                String out = lines.isEmpty() ? "(no recorded facts)"
                        : String.join("\n", lines);
                return new ToolOut(clip(out), lines.size() + " facts of " + s + " \"" + e.name()
                        + "\"");
            }
            case "quotes" -> {
                String s = args.path("side").asText("A");
                ReviewSide e = side(s, item);
                List<QuoteRow> rows = governance.quotesOf(kbId, e.id(), 6);
                String out = rows.isEmpty() ? "(no source passages)"
                        : rows.stream()
                                .map(r -> "[" + r.filename() + "] " + r.quote())
                                .collect(java.util.stream.Collectors.joining("\n"));
                return new ToolOut(clip(out), rows.size() + " passages about " + s + " \""
                        + e.name() + "\"");
            }
            case "ledger" -> {
                String q = args.path("query").asText("");
                List<Precedent> rows = governance.ledgerSearch(kbId, q, 10);
                String out = rows.isEmpty() ? "(no decisions by people about this name)"
                        : rows.stream().map(x -> {
                            String verb = switch (x.action()) {
                                case "merge.revert" -> "merge reverted";
                                case "review.keep" -> "kept apart";
                                default -> "merged";
                            };
                            // 人写的理由跟在后面：第二层去查台账，查到的该是「凭什么」
                            String because = x.why() != null
                                    ? "; they wrote: \"" + x.why() + "\"" : "";
                            return "\"" + x.left() + "\" ≟ \"" + x.right() + "\": " + verb
                                    + " by a person on " + x.at().format(DAY) + because;
                        }).collect(java.util.stream.Collectors.joining("\n"));
                return new ToolOut(clip(out), rows.size() + " decisions about \"" + q + "\"");
            }
            // 合并会牵动什么（0028）：模型先看到闸门（0027）会看到的东西，再决定是裁还是问
            case "consequences" -> {
                Impact impact = gateService.impactOf(kbId, item.left().id(), item.right().id());
                String families = GovernanceService.typesConflict(item.left().typeLabel(),
                        item.right().typeLabel())
                        ? "\n- the two types belong to different families; the rules never"
                                + " merge across families"
                        : "";
                Hold hold = ExecutionGate.hold(impact);
                String held = hold == null ? "nothing held" : hold.toString();
                return new ToolOut(clip(ExecutionGate.describe(impact) + families),
                        "what a merge would touch: " + held);
            }
            case "namesakes" -> {
                String q = args.path("query").asText("");
                List<Namesake> rows = governance.namesakes(kbId, q, 10);
                String out = rows.isEmpty() ? "(no other entity with such a name)"
                        : rows.stream()
                                .map(x -> "\"" + x.name() + "\" ("
                                        + (x.typeLabel() != null ? x.typeLabel() : "untyped")
                                        + ") · " + x.facts() + " facts"
                                        + (x.merged() ? " · merged into another" : ""))
                                .collect(java.util.stream.Collectors.joining("\n"));
                return new ToolOut(clip(out), rows.size() + " entities named like \"" + q
                        + "\"");
            }
            default -> {
                return new ToolOut("no tool named " + tool, "unknown tool " + tool);
            }
        }
    }

    private static ReviewSide side(String s, ReviewItem item) {
        return "A".equals(s) ? item.left() : item.right();
    }

    /** governance.rs:774-782 clip：超长的工具结果截到 3000 字符，加省略号（按字符不按字节）。 */
    private static String clip(String s) {
        return s.codePointCount(0, s.length()) <= TOOL_OUTPUT_CHARS ? s
                : s.substring(0, s.offsetByCodePoints(0, TOOL_OUTPUT_CHARS)) + "\n…";
    }

    private static String takeChars(String s, int max) {
        return s.codePointCount(0, s.length()) <= max ? s
                : s.substring(0, s.offsetByCodePoints(0, max));
    }

    // ====================================================================
    // 落地（governance.rs:503-647 apply）

    /**
     * 一对的判决落地：过闸就动手并记 applied，否则转人工并记 proposed（带着问题与轨迹）。
     */
    private void apply(Ctx ctx, ReviewItem item, Precedents p, Look look) {
        UUID kbId = ctx.kbId();
        boolean typesConflict = GovernanceService.typesConflict(item.left().typeLabel(),
                item.right().typeLabel());
        NameShape shape = shapeForGate(item, look);
        String action = actionOf(look);
        JsonNode precedents = GovernanceService.precedentsJson(p);
        float conf = look.conf();

        // 第二眼没跑成（预算用完、模型出错）的相似提的 same：过闸也不合，上交给人，
        // 看法照记成建议——名字相近不是同一个东西的证据，事实才是
        if (Boolean.TRUE.equals(look.same()) && look.calls() == 0
                && !AdjudicateEntitiesJob.batchVerdictMayApply(item, look.same())) {
            resolution.escalateReview(item.id(), AdjudicateEntitiesJob.SECOND_LOOK_UNAVAILABLE);
            governance.record(kbId, decisionOf(ctx, item, look, precedents, action, "proposed",
                    null, "held for a person: the names are similar, not the same string, and"
                            + " the second look did not run", null));
            return;
        }
        Gate gate = GovernanceService.gate(look.same(), conf, typesConflict, shape, p);
        if (gate == Gate.APPLY && Boolean.TRUE.equals(look.same())) {
            String reason = "governed|" + String.format(Locale.ROOT, "%.2f", conf);
            // 同簇连锁：前一对合完，这一对的一侧可能已经并进了别人——合活着的那个
            UUID l = resolution.survivor(kbId, item.left().id());
            UUID r = resolution.survivor(kbId, item.right().id());
            if (l.equals(r)) {
                // 两边已经是同一个实体：只剩把审核行关上。关不上是已经有人关了，不再记一条
                long closed = resolution.closeReviewAuto(item.id(), "merged", reason);
                if (closed > 0) {
                    UUID id = governance.record(kbId,
                            decisionOf(ctx, item, look, precedents, action, "applied", null,
                                    null, null));
                    audit(ctx, "review.merge", item, conf, id);
                }
                return;
            }
            // 执行闸门（0027）：合并会立刻送出图外的东西——违规、派生、答案——留给人，
            // 把握再高也不动手。agent 的看法照记成建议，理由前面写明是闸门留下的
            Impact impact = gateService.impactOf(kbId, l, r);
            Hold hold = ExecutionGate.hold(impact);
            if (hold != null) {
                resolution.escalateReview(item.id(), "escalate_impact|" + hold);
                String held = look.why() != null
                        ? "held for a person: " + hold.explain() + "; " + look.why()
                        : "held for a person: " + hold.explain();
                governance.record(kbId, decisionOf(ctx, item, look, precedents, action,
                        "proposed", null, held, null));
                return;
            }
            MergeDirection dir = resolution.mergeDirection(l, r);
            try {
                UUID mergeId = resolution.mergeEntities(kbId, dir.source(), dir.target(), null,
                        reason);
                resolution.closeReviewAuto(item.id(), "merged", reason);
                UUID id = governance.record(kbId,
                        decisionOf(ctx, item, look, precedents, action, "applied", mergeId,
                                null, null));
                audit(ctx, "review.merge", item, conf, id);
            } catch (AppException e) {
                if (e.kind() == AppException.Kind.CONFLICT
                        || e.kind() == AppException.Kind.NOT_FOUND) {
                    // 同簇连锁合并已吞掉一方：留给人，并记一条 unsure 免得下一轮又撞上
                    resolution.escalateReview(item.id(), "escalate_entity_changed");
                    governance.record(kbId, decisionOf(ctx, item, look, precedents, action,
                            "proposed", null,
                            "one side changed while this cluster was being decided", "unsure"));
                } else {
                    throw e;
                }
            }
        } else if (gate == Gate.APPLY) {
            String reason = "governed|" + String.format(Locale.ROOT, "%.2f", conf);
            // 关不上是这一对已经不是 pending（人裁了，或另一条路先到）：不再记一条一样的裁决
            long closed = resolution.closeReviewAuto(item.id(), "kept", reason);
            if (closed > 0) {
                UUID id = governance.record(kbId,
                        decisionOf(ctx, item, look, precedents, action, "applied", null,
                                null, null));
                audit(ctx, "review.keep", item, conf, id);
            }
        } else {
            resolution.escalateReview(item.id(), "proposed");
            governance.record(kbId, decisionOf(ctx, item, look, precedents, action, "proposed",
                    null, null, null));
        }
    }

    /**
     * governance.rs:516-528 的 decision 闭包：NewDecision 的公共字段一次成型；
     * reason / action 可覆写（闸门留下的前缀、连锁吞掉一侧时改记 unsure）。
     */
    private static NewDecision decisionOf(Ctx ctx, ReviewItem item, Look look,
                                          JsonNode precedents, String action, String status,
                                          UUID mergeId, String reasonOverride,
                                          String actionOverride) {
        ArrayNode trace = JSON.createArrayNode();
        look.trace().forEach(trace::add);
        return new NewDecision(ctx.runId(), item.id(),
                actionOverride != null ? actionOverride : action,
                look.conf(), reasonOverride != null ? reasonOverride : look.why(),
                precedents, status, mergeId, look.question(), trace, look.calls());
    }

    /**
     * 决策台账（governance.rs:895-910 audit）：actor 为空（机器），detail 里说是治理、
     * 置信度多少、对应哪条 agent 决定。
     */
    private void audit(Ctx ctx, String action, ReviewItem item, float conf, UUID decisionId) {
        ObjectNode detail = JSON.createObjectNode();
        detail.put("left", item.left().name());
        detail.put("right", item.right().name());
        detail.put("score", item.score());
        detail.put("confidence", conf);
        detail.put("via", "governor");
        detail.put("decision", decisionId.toString());
        audit.recordOpt(null, ctx.kbId(), null, action, "review", item.id(), detail.toString());
    }
}
