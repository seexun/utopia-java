package tech.seexun.jknow.web.knowledge.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.extract.JsonFuns;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.OntologyService.TypeCandidate;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.ResolutionService.DescendantsMemo;
import tech.seexun.jknow.store.ResolutionService.EntityRetype;
import tech.seexun.jknow.store.ResolutionService.Neighbour;
import tech.seexun.jknow.store.ResolutionService.RetypeOutcome;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.mapper.KbMapper;
import tech.seexun.jknow.store.mapper.ResolutionMapper.TypeCandidateSubject;

/**
 * 类型消解 job（type_resolution.rs:434-757 逐语义；preview/resolve 两段同搬——人点的那
 * 轮 HTTP 面属收口任务，此处先落 job 本体 0016 C2 的自动一轮）：把实体的类型改对——
 * <b>往细里走，也把走错的掰回来</b>（纠正天然判为跨轴，一定进人工）。
 *
 * <p>与生俱来的三条输入：proposed_type（抽取时模型自己报的类型名）、实体画像、证据引
 * 文。候选两路来取并集不合分数：画像搜类的描述、名字单独再发一次（短查询对短标签），
 * 外加已定类近邻投票。
 *
 * <p><b>分档看跨没跨分类轴，不看模型自报的置信度</b>：自报置信度是文风不是概率（实测
 * 双峰），真正分档的是「目标在不在粗类子树里」。
 */
@Component
public class ResolveTypesJob {

    private static final Logger log = LoggerFactory.getLogger(ResolveTypesJob.class);

    /** 一轮看多少个实体（BATCH）。 */
    static final long BATCH = 60;
    /** 每个实体检索几个候选（CANDIDATES）——检索的活是端候选，说不是裁决的活。 */
    static final long CANDIDATES = 10;
    /** 看几个已定类的近邻（NEIGHBOURS）。少了凑不出票数，多了尾巴上全是噪音。 */
    static final long NEIGHBOURS = 10;
    /** 自动档的轮数上限（MAX_ROUNDS）：更多的等下一篇文档抽完再排。 */
    static final int MAX_ROUNDS = 10;
    /** 低于这条线的一律进人工（AUTO_THRESHOLD）——兜住偶尔出现的低分，不是灰度主判据。 */
    static final float AUTO_THRESHOLD = 0.85f;

    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final ResolutionService resolution;
    private final EmbedOntologyJob index;
    private final LlmSupport llm;
    private final AuditService audit;

    public ResolveTypesJob(KbMapper kbs, SettingsService settingsService,
                           ResolutionService resolution, EmbedOntologyJob index, LlmSupport llm,
                           AuditService audit) {
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.resolution = resolution;
        this.index = index;
        this.llm = llm;
        this.audit = audit;
    }

    /** main.rs:607-616：载荷取 kb_id，自动一轮即成功。 */
    public void handle(Job job) {
        JsonNode kbNode = job.payload().get("kb_id");
        if (kbNode == null || !kbNode.isTextual()) {
            throw new IllegalStateException("payload 缺少 kb_id");
        }
        resolveTypesJob(UUID.fromString(kbNode.asText()));
    }

    /**
     * 抽取结束后的自动一轮（0016 C2）。与人点的那一轮是同一套判定——在原类子树里精化的
     * 自动改，跨轴的留给人——差别只有两处：只看引擎没看过的实体（type_resolved_at 打
     * 标，<b>连检索没给候选的也算看过</b>防同批回流），且看过的打上标记，下一轮不回头。
     * 待人工的那一档这里不落库：它们留在候选里，人在本体页点一次运行就会再见到。
     * 开关关着的库什么都不做。
     */
    public void resolveTypesJob(UUID kbId) {
        KbRow kb = kbs.selectById(kbId);
        if (kb == null) {
            throw new IllegalStateException("no such knowledge base: " + kbId);
        }
        if (!kb.autoTypeResolution()) {
            return;
        }
        long retyped = 0;
        int forReview = 0;
        int leftAlone = 0;
        int rounds = 0;
        for (int i = 0; i < MAX_ROUNDS; i++) {
            ResolutionOutcome outcome = resolveWith(kbId, true);
            int seen = (int) outcome.retyped() + outcome.forReview().size()
                    + outcome.leftAlone().size();
            if (seen == 0) {
                break;
            }
            rounds++;
            retyped += outcome.retyped();
            forReview += outcome.forReview().size();
            leftAlone += outcome.leftAlone().size();
        }
        if (rounds > 0) {
            com.fasterxml.jackson.databind.node.ObjectNode detail =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            detail.put("via", "job");
            detail.put("rounds", rounds);
            detail.put("retyped", retyped);
            detail.put("for_review", forReview);
            detail.put("left_alone", leftAlone);
            audit.recordOpt(null, kbId, null, "ontology.types_resolved", "knowledge_base",
                    kbId, detail.toString());
            log.info("kb={} rounds={} retyped={} for_review={} left_alone={} 类型消解自动跑完成",
                    kbId, rounds, retyped, forReview, leftAlone);
        }
    }

    /** 一个实体的消解建议（TypeSuggestion）。 */
    public record TypeSuggestion(UUID entityId, String name, String coarse, String coarseDescription,
                                 UUID coarseId, String proposedType, String specificType,
                                 long factCount, String profile, List<TypeCandidate> candidates,
                                 List<NeighbourVote> neighbours, Set<UUID> descendants) {
    }

    /** 近邻投出来的一个类（NeighbourVote）。 */
    public record NeighbourVote(String key, int votes, double bestDistance, List<String> examples,
                                boolean sameDocumentOnly) {
    }

    /** 只算不写：每个待消解实体的画像与候选（preview_with）。 */
    public List<TypeSuggestion> preview(UUID kbId) {
        try {
            return previewWith(kbId, false);
        } catch (Exception e) {
            throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
        }
    }

    List<TypeSuggestion> previewWith(UUID kbId, boolean unattended) {
        // 只补类那一半：这一步用不到关系，而一份大本体的关系有一千多条
        index.refreshScoped(kbId, tech.seexun.jknow.store.OntologyService.TypeKind.ENTITY);
        List<TypeCandidateSubject> subjects = resolution.entitiesForTypeResolution(kbId, BATCH,
                unattended);
        if (subjects.isEmpty()) {
            return List.of();
        }
        // **每个实体两个查询，不是一个**：画像里语境占大头（「杭州拱墅区」的画像讲的是
        // 药房），名字被稀释进了段落——名字单独发一次（短对短），两次结果取并集
        List<String> profiles = subjects.stream().map(ResolveTypesJob::profileOf).toList();
        List<String> names = new ArrayList<>();
        for (TypeCandidateSubject s : subjects) {
            String q = nameQueryOf(s);
            if (q != null) {
                names.add(q);
            }
        }
        // 两路各查各的索引（整段 vs 只有 label）——短说法那一路被同义反复的类接管过。
        // 检索失败降级为空清单继续跑（type_resolution.rs:151-169 的 unwrap_or_default——
        // 嵌入或查库任何错误都吞成空，本轮只是无候选；审查 M-1 对齐降级方向）
        List<List<TypeCandidate>> profileHits;
        List<List<TypeCandidate>> nameHits;
        try {
            profileHits = index.nearestForEach(kbId, profiles,
                    CANDIDATES * 4, EmbedOntologyJob.Target.CLASS);
            nameHits = names.isEmpty() ? List.of()
                    : index.nearestForEach(kbId, names, CANDIDATES * 4,
                            EmbedOntologyJob.Target.CLASS_LABEL);
        } catch (RuntimeException e) {
            log.warn("kb={} 类型候选检索失败，本轮按无候选处理：{}", kbId, e.getMessage());
            profileHits = new ArrayList<>(Collections.nCopies(profiles.size(), List.<TypeCandidate>of()));
            nameHits = new ArrayList<>(Collections.nCopies(names.size(), List.<TypeCandidate>of()));
        }
        // 名字那一路只有给得出说法的实体才发了查询，下标单独数
        List<Map.Entry<Integer, Integer>> slots = new ArrayList<>();
        int ni = 0;
        for (int pi = 0; pi < subjects.size(); pi++) {
            String q = nameQueryOf(subjects.get(pi));
            Integer slot = null;
            if (q != null) {
                slot = ni++;
            }
            slots.add(Map.entry(pi, slot));
        }
        // 邻居先一起取（#514）：六十个查询彼此无关；后代集合按粗类记一批
        List<UUID> ids = subjects.stream().map(TypeCandidateSubject::id).toList();
        List<List<Neighbour>> neighbourLists = resolution.nearestTypedForEach(kbId, ids,
                NEIGHBOURS);
        DescendantsMemo descendantsMemo = new DescendantsMemo();

        List<TypeSuggestion> out = new ArrayList<>(subjects.size());
        for (int i = 0; i < subjects.size(); i++) {
            TypeCandidateSubject s = subjects.get(i);
            // 粗类的后代排前面但不是唯一能选的（签名是导向不是闸门）；还没有类的实体
            // 没有这个轴可用，整张类表都是候选
            Set<UUID> descendants = descendantsMemo.get(resolution, kbId, s.coarseId());
            // 两路交替取，不按距离合并——距离在两路之间不可比，按距离排等于让名字那
            // 一路独占前几名；交替之后两路各占一半席位
            List<List<TypeCandidate>> lists = new ArrayList<>();
            lists.add(i < profileHits.size() ? profileHits.get(i) : List.of());
            Integer nameSlot = slots.get(i).getValue();
            if (nameSlot != null && nameSlot < nameHits.size()) {
                lists.add(nameHits.get(nameSlot));
            }
            Set<UUID> seenIds = new HashSet<>();
            List<TypeCandidate> ranked = new ArrayList<>();
            int longest = lists.stream().mapToInt(List::size).max().orElse(0);
            for (int rank = 0; rank < longest; rank++) {
                for (List<TypeCandidate> list : lists) {
                    if (rank >= list.size()) {
                        continue;
                    }
                    TypeCandidate c = list.get(rank);
                    if (c.id().equals(s.coarseId()) || !seenIds.add(c.id())) {
                        continue;
                    }
                    ranked.add(c);
                }
            }
            List<TypeCandidate> candidates = ranked.size() > (int) CANDIDATES
                    ? ranked.subList(0, (int) CANDIDATES) : ranked;
            // 第二路：语境相似的已定类实体，按类投票
            List<Neighbour> raw = neighbourLists.get(i);
            Map<String, Vote> votes = new TreeMap<>();
            for (Neighbour n : raw) {
                Vote slot = votes.computeIfAbsent(n.key(),
                        k -> new Vote(0, n.distance(), new ArrayList<>(), true));
                slot.count++;
                slot.bestDistance = Math.min(slot.bestDistance, n.distance());
                if (slot.examples.size() < 3) {
                    slot.examples.add(n.name());
                }
                slot.sameDocumentOnly &= n.sameDocument();
            }
            List<NeighbourVote> neighbours = new ArrayList<>();
            for (Map.Entry<String, Vote> e : votes.entrySet()) {
                if (e.getKey().equals(s.coarseKey())) {
                    continue;
                }
                neighbours.add(new NeighbourVote(e.getKey(), e.getValue().count,
                        e.getValue().bestDistance, e.getValue().examples,
                        e.getValue().sameDocumentOnly));
            }
            neighbours.sort(Comparator.comparingInt(NeighbourVote::votes).reversed()
                    .thenComparingDouble(NeighbourVote::bestDistance));
            out.add(new TypeSuggestion(s.id(), s.canonicalName(), s.coarseKey(),
                    s.coarseDescription(), s.coarseId(), s.proposedType(), s.specificType(),
                    s.factCount(), profiles.get(i), candidates, neighbours, descendants));
        }
        return out;
    }

    private static final class Vote {
        int count;
        double bestDistance;
        List<String> examples;
        boolean sameDocumentOnly;

        Vote(int count, double bestDistance, List<String> examples, boolean sameDocumentOnly) {
            this.count = count;
            this.bestDistance = bestDistance;
            this.examples = examples;
            this.sameDocumentOnly = sameDocumentOnly;
        }
    }

    /**
     * 实体画像（profile_of）：模型自己报的类型名放最前（名字对名字，正是这个索引擅长
     * 的形状）；名字、别名其次；引文垫后当语境且只有两句。
     */
    static String profileOf(TypeCandidateSubject s) {
        List<String> parts = new ArrayList<>();
        for (String named : new String[] {s.specificType(), s.proposedType()}) {
            if (named != null && !named.trim().isEmpty()) {
                parts.add(named.trim());
            }
        }
        parts.add(s.canonicalName());
        if (!s.otherNames().isEmpty()) {
            parts.add(String.join(", ", s.otherNames()));
        }
        if (!s.roles().isEmpty()) {
            parts.add(String.join(" ", s.roles()));
        }
        for (String q : s.quotes().subList(0, Math.min(2, s.quotes().size()))) {
            parts.add(codePoints(q, 120));
        }
        return String.join(". ", parts);
    }

    /** 只有名字的那个查询（name_query_of）：两个说法都没有就返回 null——再发是白花嵌入。 */
    static String nameQueryOf(TypeCandidateSubject s) {
        List<String> parts = new ArrayList<>();
        for (String v : new String[] {s.specificType(), s.proposedType()}) {
            if (v != null && !v.trim().isEmpty()) {
                parts.add(v.trim());
            }
        }
        return parts.isEmpty() ? null : String.join(". ", parts);
    }

    private static String codePoints(String s, int limit) {
        return s.codePoints().limit(limit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /** 一次消解的结果，给调用方交代清楚三档各去了哪里（ResolutionOutcome）。 */
    public record ResolutionOutcome(UUID batch, long retyped, List<ReviewItem> forReview,
                                    List<DeclineNote> leftAlone) {
    }

    /** 裁决说「都不是」的一条，连同它给的理由（DeclineNote）。 */
    public record DeclineNote(String name, String coarse, String specificType, String reason,
                              String topCandidate) {
    }

    /** 留给人的一条（ReviewItem）。 */
    public record ReviewItem(UUID entityId, String name, String coarse, UUID fromTypeId,
                             UUID toTypeId, String choice, float confidence, String reason,
                             boolean crossesAxis) {
    }

    /** 跑一轮类型消解并落库（resolve_with）。 */
    public ResolutionOutcome resolveWith(UUID kbId, boolean unattended) {
        List<TypeSuggestion> items = previewWith(kbId, unattended);
        // 自动跑：这一批不管裁成哪一档都算看过（连检索都没给候选的也算——它们下一轮
        // 照样没有），否则同一批实体每轮都回来
        if (unattended && !items.isEmpty()) {
            List<UUID> ids = items.stream().map(TypeSuggestion::entityId).toList();
            resolution.markTypeJudged(kbId, ids);
        }
        items = items.stream()
                .filter(i -> !i.candidates().isEmpty() || !i.neighbours().isEmpty())
                .toList();
        if (items.isEmpty()) {
            return new ResolutionOutcome(null, 0, List.of(), List.of());
        }

        KbRow kb = kbs.selectById(kbId);
        LlmSettingsRow settings = kb == null ? null : settingsService.get(kb.workspaceId());
        ChatClient client = LlmSupport.chatClient(settings);
        if (client == null) {
            throw AppException.invalid("no_chat_model", "Chat model not configured");
        }
        // Rust 此处是单发 client.chat（无退避）；Java 沿 M4 任务 3 记档的同一取舍走
        // chatRetryingRateLimits——同一把许可 + 自愈类内联退避，耗尽照旧失败任务
        ChatClient.Reply reply;
        try {
            reply = llm.chatRetryingRateLimits(settings, client,
                    List.of(new ChatClient.ChatMessage("user", adjudicationPrompt(items))), null);
        } catch (Exception e) {
            throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
        }
        JsonNode parsed;
        try {
            parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(JsonFuns.jsonBlock(reply.text()));
        } catch (Exception e) {
            throw new IllegalStateException("verdict reply did not parse: " + e.getMessage(), e);
        }

        // 名字 → 下标列表，不是单条。同名的未分类实体可以并存（0009），塌成一条会让
        // 其中几个永远拿不到裁决。裁决优先按 id 对回来，名字只是模型漏给 id 时的退路
        Map<String, List<Integer>> byName = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            byName.computeIfAbsent(items.get(i).name(), k -> new ArrayList<>()).add(i);
        }
        // 人认可过的配对：同一对不再进人工。跨轴是类与类之间的事，实体只是碰巧撞上它
        Set<Map.Entry<UUID, UUID>> approved = resolution.approvedRefinements(kbId);
        List<EntityRetype> picks = new ArrayList<>();
        List<ReviewItem> forReview = new ArrayList<>();
        List<DeclineNote> leftAlone = new ArrayList<>();
        Set<Integer> decided = new HashSet<>();
        java.util.function.BiFunction<TypeSuggestion, String, DeclineNote> decline =
                (item, reason) -> new DeclineNote(item.name(), item.coarse(),
                        item.specificType(), reason,
                        item.candidates().isEmpty() ? null : item.candidates().get(0).key());
        for (JsonNode v : parsed.path("verdicts")) {
            // id 优先；漏给时退回名字，取该名字下还没裁决过的第一条。越界的 id 当没给
            Integer idx = null;
            if (v.path("id").canConvertToInt()) {
                int id = v.get("id").asInt();
                if (id >= 0 && id < items.size()) {
                    idx = id;
                }
            }
            if (idx == null) {
                for (int cand : byName.getOrDefault(v.path("name").asText(""), List.of())) {
                    if (!decided.contains(cand)) {
                        idx = cand;
                        break;
                    }
                }
            }
            if (idx == null) {
                continue;
            }
            // 同一条只认第一份裁决——重复作答会把同一个 entity_id 再推进 picks 撞主键
            if (!decided.add(idx)) {
                continue;
            }
            TypeSuggestion item = items.get(idx);
            // **字符串 "null" 也是 null**：当成 key 去查候选必然查不到，一条编造的拒绝
            // 理由会盖掉模型真正给的那条——拒绝的理由是这一步最要紧的输出
            String rawChoice = v.path("choice").isTextual() ? v.path("choice").asText().trim()
                    : null;
            String choice = rawChoice != null && !rawChoice.isEmpty()
                    && !"null".equalsIgnoreCase(rawChoice) && !"none".equalsIgnoreCase(rawChoice)
                    ? rawChoice : null;
            if (choice == null) {
                leftAlone.add(decline.apply(item,
                        v.path("reason").isTextual() ? v.path("reason").asText() : null));
                continue;
            }
            // 只认候选清单里的 key——清单之外的答案是模型在凭记忆写一个 schema.org
            // 里的名字，本体里未必有
            TypeCandidate target = null;
            for (TypeCandidate c : item.candidates()) {
                if (c.key().equals(choice)) {
                    target = c;
                    break;
                }
            }
            if (target == null) {
                leftAlone.add(decline.apply(item,
                        "chose " + choice + ", which is not among the candidates"));
                continue;
            }
            float confidence = v.path("confidence").isNumber()
                    ? (float) v.path("confidence").asDouble() : 0f;
            // 分档看跨没跨分类轴：在粗类子树里=往下走一格（自动改）；不在=换了分类轴
            // （重新分类，值得一个人看一眼）。纠正天然跨轴——正确答案是错类的兄弟不是
            // 后代。还没有类的实体不算跨轴（0009）：第一次定类是补齐不是重新分类
            boolean crossesAxis;
            if (item.coarseId() != null) {
                crossesAxis = !item.descendants().contains(target.id())
                        && !approved.contains(Map.entry(item.coarseId(), target.id()));
            } else {
                crossesAxis = false;
            }
            if (confidence >= AUTO_THRESHOLD && !crossesAxis) {
                picks.add(new EntityRetype(item.entityId(), target.id()));
            } else {
                forReview.add(new ReviewItem(item.entityId(), item.name(), item.coarse(),
                        item.coarseId(), target.id(), choice, confidence,
                        v.path("reason").isTextual() ? v.path("reason").asText() : null,
                        crossesAxis));
            }
        }
        // 裁决压根没提到的实体也算「没动」，否则三档加起来对不上总数
        for (int i = 0; i < items.size(); i++) {
            if (!decided.contains(i)) {
                leftAlone.add(decline.apply(items.get(i), null));
            }
        }

        UUID batch = null;
        long retyped = 0;
        if (!picks.isEmpty()) {
            RetypeOutcome outcome = resolution.retypeEntities(kbId, picks, null);
            batch = outcome.batchId();
            retyped = outcome.moved();
        }
        return new ResolutionOutcome(batch, retyped, forReview, leftAlone);
    }

    /**
     * 裁决提示词（adjudication_prompt）逐字。<b>「都不是」必须是个体面的答案</b>：保持
     * 粗类什么也不损失，硬逼模型从候选里挑一个换来的是一批自信的错误。编号是钥匙
     * 名字不是（0009 同名可并存）；现类连描述一起给（导入本体的 key 常常自解释不了）。
     */
    static String adjudicationPrompt(List<TypeSuggestion> items) {
        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            TypeSuggestion it = items.get(i);
            String current;
            if (it.coarse() != null) {
                String d = it.coarseDescription() == null ? "" : it.coarseDescription().trim();
                current = !d.isEmpty() ? it.coarse() + " (" + d + ")" : it.coarse();
            } else {
                current = "not yet typed";
            }
            StringBuilder lines = new StringBuilder();
            lines.append("### [").append(i).append("] ").append(it.name())
                    .append("\ncurrently: ").append(current)
                    .append("\nthe extractor called it: ")
                    .append(it.specificType() == null ? "-" : it.specificType())
                    .append("\nseen as: ").append(codePoints(it.profile(), 200));
            lines.append("\ncandidates:");
            for (TypeCandidate c : it.candidates()) {
                String d = c.description() == null ? "" : c.description().trim();
                lines.append(d.isEmpty() ? "\n- " + c.key() + " (" + c.label() + ")"
                        : "\n- " + c.key() + ": " + d);
            }
            if (!it.neighbours().isEmpty()) {
                List<String> n = new ArrayList<>();
                for (int k = 0; k < Math.min(3, it.neighbours().size()); k++) {
                    NeighbourVote vote = it.neighbours().get(k);
                    n.add(vote.key() + " (like " + String.join(", ", vote.examples()) + ")");
                }
                lines.append("\nsimilar entities are typed: ").append(String.join("; ", n));
            }
            blocks.add(lines.toString());
        }
        return "You are fixing entity types in a knowledge graph. Each entity below has a type it was "
                + "given during extraction, and a list of candidate types retrieved from the ontology.\n"
                + "\n"
                + "For each entity choose ONE candidate key, or null. There are two reasons to choose "
                + "a candidate, and they are different:\n"
                + "\n"
                + "**Narrowing** — the current type is right but broad, and a candidate says the same "
                + "thing more precisely (organization → hospital).\n"
                + "\n"
                + "**Correcting** — the current type is simply wrong, and a candidate is right. This "
                + "happens because extraction picks from a retrieved shortlist and can pick badly: a city "
                + "typed as an address, an app typed as an entry point. A correcting candidate is "
                + "usually a sibling of the current type rather than a narrower version of it, so do not "
                + "withhold it on the grounds that it is not more specific. Say plainly in the reason "
                + "that the current type is wrong; a person will see this one before it is applied.\n"
                + "\n"
                + "Choose null whenever any of these hold, and expect null to be a common answer:\n"
                + "- no candidate actually means the thing (the list is retrieved by similarity, so it "
                + "usually contains near-misses and sometimes contains nothing right at all);\n"
                + "- the current type is already right and no candidate is more precise;\n"
                + "- the entity is not a thing of that kind at all — a quantity, a capability, a phrase.\n"
                + "Keeping the current type loses nothing: the entity and its facts stay exactly as they "
                + "are. Picking a wrong type is worse than picking none, because it reads as a decided "
                + "fact.\n"
                + "\n"
                + "confidence is your own 0~1: use above 0.85 only when the candidate's definition "
                + "plainly describes this entity, not when it is merely the closest of a weak list.\n"
                + "\n"
                + "reason is required on every verdict, including the nulls — especially the nulls. "
                + "When you choose null, say which candidate came closest and what it got wrong "
                + "(\"nearest was publication_issue, but that is one issue of a journal, not the "
                + "journal\"). A refusal without a reason cannot be acted on: nobody can tell whether "
                + "the ontology is missing the class, or the search failed to surface it, or you read "
                + "the entity differently.\n"
                + "\n"
                + String.join("\n\n", blocks) + "\n"
                + "\n"
                + "id is the number in the heading, and it is what identifies the verdict — not the "
                + "name. Two entries can carry the same name and still be different things (two people "
                + "called Zhang Wei, each with their own facts); judge each one on its own block and "
                + "give one verdict per id you answer. Never merge two ids into one verdict.\n"
                + "\n"
                + "Output exactly one JSON object:\n"
                + "{\"verdicts\":[{\"id\":0,\"name\":\"entity name exactly as given\","
                + "\"choice\":\"candidate key or null\",\"confidence\":0.0,"
                + "\"reason\":\"one short clause\"}]}";
    }
}
