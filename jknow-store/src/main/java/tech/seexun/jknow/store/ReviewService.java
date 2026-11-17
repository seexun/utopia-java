package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.store.entity.AlignmentItem;
import tech.seexun.jknow.store.entity.AuditEventView;
import tech.seexun.jknow.store.entity.AxiomViolation;
import tech.seexun.jknow.store.entity.PendingFactView;
import tech.seexun.jknow.store.entity.ReviewCounts;
import tech.seexun.jknow.store.entity.ReviewItem;
import tech.seexun.jknow.store.entity.ReviewSummary;
import tech.seexun.jknow.store.mapper.ReviewMapper;
import tech.seexun.jknow.store.mapper.ReviewSummaryMapper;

/**
 * review 读路径装配（对齐旧仓库 review_routes.rs:75-155 list 的队列分发 + 各 store 函数的
 * 组装逻辑：duplicates 的 assemble_reviews（resolution.rs:1576-1628）、alignment 的三查询
 * 合一内存分页（alignment_queue.rs:116-225）、violations 的 hint/path 展开
 * （reasoning.rs:856-878）、summary 的窗口合成（review_summary.rs:154-257/276-316）。
 * SQL 全在 {@link ReviewMapper}/{@link ReviewSummaryMapper}（逐字），本类只做流程控制。
 */
@Service
public class ReviewService {

    /** review.rs:20：低置信阈值——counts 与 lowconf 队列共用一个常量，两处各写迟早分叉。 */
    public static final float LOW_CONFIDENCE_BELOW = 0.75f;

    /** review_summary.rs:25：「办过的」按天看多少天。 */
    private static final long DAILY_DAYS = 14;

    /** review_summary.rs:27：「谁办的」最多列几个人。 */
    private static final long TOP_ACTORS = 5;

    /** names.rs KNOWN_AS：known_as 谓词 key（别名查询的参数绑定）。 */
    private static final String KNOWN_AS = "known_as";

    /** 事实摘要行的月份格式（resolution.rs:1566-1568 "%Y-%m"）。 */
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final ReviewMapper review;
    private final ReviewSummaryMapper summaryMapper;

    public ReviewService(ReviewMapper review, ReviewSummaryMapper summaryMapper) {
        this.review = review;
        this.summaryMapper = summaryMapper;
    }

    // ---- counts（review.rs:35-84） ----

    public ReviewCounts counts(UUID kbId) {
        return review.selectCounts(kbId, LOW_CONFIDENCE_BELOW);
    }

    // ---- 12 队列分发（review_routes.rs:88-148） ----

    /** 未知 queue → 422 unknown_queue（认不出的档名当契约错误报，不悄悄回空）。 */
    public List<?> items(UUID kbId, String queue, String types, long limit, long offset) {
        return switch (queue) {
            case "pending" -> review.selectPendingFacts(kbId, limit, offset);
            case "duplicates" -> assembleDuplicates(kbId, TypeFilter.parse(types), limit, offset);
            case "conflicts" -> review.selectConflicts(kbId, limit, offset);
            case "unconfirmed" -> review.selectUnconfirmedFacts(kbId, limit, offset);
            case "lowconf" -> review.selectLowConfidenceFacts(kbId, LOW_CONFIDENCE_BELOW, limit, offset);
            case "mappings" -> review.selectProposedMappings(kbId, limit, offset);
            case "alignment" -> alignment(kbId, limit, offset);
            case "errata" -> review.selectHeldErrata(kbId, limit, offset);
            case "violations" -> violations(kbId, limit, offset);
            case "defects" -> review.selectOpenDefects(kbId, limit, offset);
            case "merges" -> review.selectMerges(kbId, limit, offset);
            case "agent" -> review.selectAgentDecisions(kbId, limit, offset);
            default -> throw AppException.invalid("unknown_queue", "no review queue named " + queue);
        };
    }

    /** pending_for_chunk（review_routes.rs:1087-1096 → pending.rs:236-248）。 */
    public List<PendingFactView> pendingForChunk(UUID kbId, UUID chunkId) {
        return review.selectPendingForChunk(kbId, chunkId);
    }

    // ---- duplicates 组装（resolution.rs:1668-1691 + 1576-1628） ----

    /** 重复项按两边类型关系分三档（#428）；认不出的 types 值当契约错误报出来。 */
    enum TypeFilter {
        ANY, SAME, CONFLICT;

        static TypeFilter parse(String raw) {
            String s = raw == null ? "any" : raw;
            return switch (s) {
                case "any" -> ANY;
                case "same" -> SAME;
                case "conflict" -> CONFLICT;
                default -> throw AppException.invalid("unknown_type_filter",
                        "types must be any, same or conflict, not " + s);
            };
        }
    }

    private List<ReviewItem> assembleDuplicates(UUID kbId, TypeFilter types, long limit, long offset) {
        List<ReviewMapper.ReviewRow> rows = switch (types) {
            case ANY -> review.selectDuplicateReviewsAny(kbId, limit, offset);
            case SAME -> review.selectDuplicateReviewsSame(kbId, limit, offset);
            case CONFLICT -> review.selectDuplicateReviewsConflict(kbId, limit, offset);
        };
        return assembleReviews(kbId, rows);
    }

    /**
     * resolution.rs:1576-1628 {@code assemble_reviews}（M3 任务 4 起公开）：审核行 →
     * 审阅卡——两侧摘要一趟读、这一页上开着的 agent 建议按行挂上。duplicates 队列与
     * pending_adjudications（后台裁决任务，M4）共用这一段，审核顺序不改。
     */
    public List<ReviewItem> assembleReviews(UUID kbId, List<ReviewMapper.ReviewRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        // 一个实体可以出现在好几对的任意一侧：这一页读一次，按审核顺序复用
        List<UUID> entityIds = new ArrayList<>();
        for (ReviewMapper.ReviewRow r : rows) {
            entityIds.add(r.leftId());
            entityIds.add(r.rightId());
        }
        entityIds.sort(null); // sort_unstable + dedup（resolution.rs:1586-1588）
        entityIds = new ArrayList<>(new java.util.LinkedHashSet<>(entityIds));
        Map<UUID, ReviewItem.ReviewSide> sides = reviewSides(kbId, entityIds);
        // 这一页上开着的建议（0025）：一趟查完，按审核行挂上去（后写覆盖=同 Rust collect 语义）
        List<UUID> ids = rows.stream().map(ReviewMapper.ReviewRow::id).toList();
        Map<UUID, ReviewItem.ReviewProposal> proposals = new LinkedHashMap<>();
        for (ReviewMapper.ProposalRow p : review.selectOpenProposals(ids)) {
            proposals.put(p.targetId(), new ReviewItem.ReviewProposal(p.id(), p.action(), p.confidence(), p.reason()));
        }
        List<ReviewItem> items = new ArrayList<>(rows.size());
        for (ReviewMapper.ReviewRow r : rows) {
            ReviewItem.ReviewSide left = sides.get(r.leftId());
            ReviewItem.ReviewSide right = sides.get(r.rightId());
            if (left == null || right == null) {
                // Rust ok_or(NotFound)：实体在审核行上却查不到摘要（被物理删除等）
                throw AppException.NOT_FOUND;
            }
            items.add(new ReviewItem(r.id(), r.score(), r.reason(), r.stage(), r.createdAt(),
                    left, right, proposals.get(r.id())));
        }
        return items;
    }

    /** 两侧实体摘要（resolution.rs:1434-1482 review_sides + top4 事实摘要行）。 */
    private Map<UUID, ReviewItem.ReviewSide> reviewSides(UUID kbId, List<UUID> entityIds) {
        List<ReviewMapper.SideRow> rows = review.selectReviewSides(kbId, entityIds);
        Map<UUID, List<String>> lines = entityFactLines(kbId, entityIds, 4);
        Map<UUID, ReviewItem.ReviewSide> out = new LinkedHashMap<>();
        for (ReviewMapper.SideRow row : rows) {
            out.put(row.id(), new ReviewItem.ReviewSide(row.id(), row.name(), row.typeLabel(),
                    row.color(), row.disambiguator(), row.degree(),
                    lines.getOrDefault(row.id(), List.of())));
        }
        return out;
    }

    /**
     * 每实体事实摘要行（resolution.rs:1499-1573 {@code entity_fact_lines}——Rust 侧
     * pub，M3 任务 3 起测试与裁决器单实体直取也走它）：本名以外的名字单独打头一行
     * （0041），再跟置信度 top4 的事实行——"works at → 星云科技 (2023-01 → now)"。
     */
    public Map<UUID, List<String>> entityFactLines(UUID kbId, List<UUID> entityIds, long limit) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }
        List<ReviewMapper.FactLineRow> rows = review.selectEntityFactLines(kbId, entityIds, limit);
        // 别名按首次记下的先后聚到各自实体（names.rs:289-312 的行序保证）
        Map<UUID, List<String>> byName = new LinkedHashMap<>();
        for (ReviewMapper.NameRow n : review.selectOtherNames(entityIds, KNOWN_AS)) {
            byName.computeIfAbsent(n.entityId(), k -> new ArrayList<>()).add(n.name());
        }
        Map<UUID, List<String>> lines = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<String>> e : byName.entrySet()) {
            lines.put(e.getKey(), new ArrayList<>(List.of("also known as: " + String.join(", ", e.getValue()))));
        }
        for (ReviewMapper.FactLineRow l : rows) {
            String other = l.otherName() == null ? "?" : l.otherName();
            String core = "out".equals(l.direction())
                    ? l.predicateLabel() + " → " + other
                    : l.predicateLabel() + " ← " + other;
            String text;
            if (l.validFrom() != null && l.validTo() != null) {
                text = core + " (" + YEAR_MONTH.format(l.validFrom()) + " → " + YEAR_MONTH.format(l.validTo()) + ")";
            } else if (l.validFrom() != null) {
                text = core + " (" + YEAR_MONTH.format(l.validFrom()) + " → now)";
            } else {
                text = core;
            }
            lines.computeIfAbsent(l.entityId(), k -> new ArrayList<>()).add(text);
        }
        return lines;
    }

    // ---- alignment 组装（alignment_queue.rs:116-225：三查询全量 → decided_at 稳定排序 → 内存分页） ----

    private record Timed(OffsetDateTime at, AlignmentItem item) {
    }

    private List<AlignmentItem> alignment(UUID kbId, long limit, long offset) {
        List<ReviewMapper.PhraseRow> phrases = review.selectWaitingPhrases(kbId);
        List<ReviewMapper.RuleRow> rules = review.selectProposedRules(kbId);
        List<ReviewMapper.KindWordRow> words = review.selectUndecidedKindWords(kbId);
        // 类别词的例名和短语来自签名（活实体数出来的），按词对上；没有词就不查签名
        List<ReviewMapper.SignatureRow> sigs =
                words.isEmpty() ? List.of() : review.selectKindWordSignatures(kbId);
        List<Timed> items = new ArrayList<>();
        for (ReviewMapper.PhraseRow r : phrases) {
            items.add(new Timed(r.decidedAt(), new AlignmentItem.Phrase("phrase", r.id(), r.phrase(),
                    r.subjectClass(), r.objectClass(), r.objectIsValue(), r.statementCount(),
                    r.examples(), r.votes(), r.decidedAt(), r.boundTo(), r.direction())));
        }
        for (ReviewMapper.KindWordRow w : words) {
            ReviewMapper.SignatureRow sig = sigs.stream()
                    .filter(s -> s.kindWord().equals(w.kindWord())).findFirst().orElse(null);
            items.add(new Timed(w.decidedAt(), new AlignmentItem.KindWord("kind_word", w.kindWord(),
                    w.words(),
                    sig == null ? List.of() : sig.examples(),
                    sig == null ? List.of() : sig.phrases(),
                    sig == null ? 0 : sig.count(),
                    w.votes(), w.decidedAt())));
        }
        for (ReviewMapper.RuleRow r : rules) {
            items.add(new Timed(r.decidedAt(), new AlignmentItem.Rule("rule", r.id(), r.trigger(),
                    r.phrase(), r.subjectClass(), r.objectClass(), r.objectIsValue(), r.property(),
                    r.propertyLabel(), r.reading(), r.statementCount(), r.examples(), r.votes(),
                    r.decidedAt())));
        }
        // 先来的在前（两种混排，稳定排序——同刻多行的相对序保持入序）
        items.sort(Comparator.comparing(Timed::at));
        return items.stream().skip(offset).limit(limit).map(Timed::item).toList();
    }

    // ---- violations 展开（reasoning.rs:856-878：hint 一次只给一条 + path 文本化） ----

    private List<AxiomViolation> violations(UUID kbId, long limit, long offset) {
        List<AxiomViolation> out = new ArrayList<>();
        for (ReviewMapper.ViolationRow r : review.selectOpenViolations(kbId, limit, offset)) {
            // 线索只对派生矛盾给（0017 §2：stale/duplicate/unsure）
            String hint = "derived_contradiction".equals(r.kind()) ? hintFor(r) : null;
            List<AxiomViolation.ViolationFact> path = new ArrayList<>();
            if (r.path() != null && r.path().isArray()) {
                for (JsonNode n : r.path()) {
                    path.add(new AxiomViolation.ViolationFact(
                            UUID.fromString(n.get("id").asText()), n.get("text").asText()));
                }
            }
            out.add(new AxiomViolation(r.id(), r.kind(), r.predicate(), r.leftFact(), r.leftText(),
                    r.rightFact(), r.rightText(), r.pathLen(), r.detectedAt(), r.detail(), hint, path));
        }
        return out;
    }

    /** reasoning.rs:901-911 hint_for：按最常见的错法排，三条并列等于没给。 */
    private static String hintFor(ReviewMapper.ViolationRow r) {
        if (r.leftOpen() && r.detail() != null && r.detail().hasNonNull("valid_from")) {
            return "stale";
        }
        if (r.sameNamePeers()) {
            return "duplicate";
        }
        if (r.leftConfidence() < LOW_CONFIDENCE_BELOW) {
            return "unsure";
        }
        return null;
    }

    // ---- history（audit.rs:94-119） ----

    public record History(List<AuditEventView> events, long total) {
    }

    public History history(UUID kbId, long limit, long offset) {
        return new History(review.selectReviewHistory(kbId, limit, offset),
                review.countReviewHistory(kbId));
    }

    // ---- summary（review_summary.rs:33-46：四段一次返回） ----

    public ReviewSummary summary(UUID kbId) {
        ReviewSummaryMapper.WaitingRow w = summaryMapper.selectWaiting(kbId, LOW_CONFIDENCE_BELOW);
        ReviewSummary.ReviewWaiting waiting = new ReviewSummary.ReviewWaiting(
                wait(w.pending(), w.pendingOldest()),
                wait(w.duplicates(), w.duplicatesOldest()),
                wait(w.conflicts(), w.conflictsOldest()),
                wait(w.unconfirmed(), w.unconfirmedOldest()),
                wait(w.lowconf(), w.lowconfOldest()),
                wait(w.violations(), w.violationsOldest()),
                wait(w.defects(), w.defectsOldest()),
                wait(w.alignment(), w.alignmentOldest()),
                wait(w.errata(), w.errataOldest()));
        ReviewSummary.ReviewHealth health;
        ReviewSummaryMapper.HealthRow h = summaryMapper.selectHealth(kbId, LOW_CONFIDENCE_BELOW);
        health = new ReviewSummary.ReviewHealth(h.facts(), h.lowConfidence(), h.unconfirmed(), h.contested());
        return new ReviewSummary(waiting, decided(kbId), health, agent(kbId));
    }

    private static ReviewSummary.QueueWait wait(long count, OffsetDateTime oldestAt) {
        return new ReviewSummary.QueueWait(count, oldestAt);
    }

    /** decided（review_summary.rs:154-257：两窗口 + 14 天柱，缺的天补 0——柱子要等距）。 */
    private ReviewSummary.ReviewDecided decided(UUID kbId) {
        List<ReviewSummaryMapper.ActionRow> byAction = summaryMapper.selectDecidedByAction(kbId);
        List<ReviewSummaryMapper.ActorRow> byActor = summaryMapper.selectDecidedByActor(kbId, TOP_ACTORS);
        List<ReviewSummaryMapper.DayRow> days = summaryMapper.selectDecidedDays(kbId, (int) (DAILY_DAYS - 1));
        Map<LocalDate, Long> byDay = new HashMap<>();
        for (ReviewSummaryMapper.DayRow d : days) {
            byDay.put(d.day(), d.count());
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<ReviewSummary.DecidedDay> daily = new ArrayList<>();
        for (long back = DAILY_DAYS - 1; back >= 0; back--) {
            LocalDate day = today.minusDays(back);
            daily.add(new ReviewSummary.DecidedDay(day, byDay.getOrDefault(day, 0L)));
        }
        return new ReviewSummary.ReviewDecided(window(byAction, byActor, true),
                window(byAction, byActor, false), daily);
    }

    /** 一个窗口（review_summary.rs:196-236 window 闭包）：count=0 的动作不进 total。 */
    private static ReviewSummary.DecidedWindow window(List<ReviewSummaryMapper.ActionRow> byAction,
                                                      List<ReviewSummaryMapper.ActorRow> byActor,
                                                      boolean seven) {
        Map<String, Long> merged = new TreeMap<>();
        long total = 0;
        long automatic = 0;
        for (ReviewSummaryMapper.ActionRow a : byAction) {
            long n = seven ? a.last7d() : a.last30d();
            if (n == 0) {
                continue;
            }
            total += n;
            if (a.automatic()) {
                automatic += n;
            }
            merged.merge(a.action(), n, Long::sum);
        }
        List<ReviewSummary.ActionCount> actions = merged.entrySet().stream()
                .map(e -> new ReviewSummary.ActionCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(ReviewSummary.ActionCount::count).reversed()
                        .thenComparing(ReviewSummary.ActionCount::action))
                .toList();
        List<ReviewSummary.ActorCount> actors = byActor.stream()
                .map(r -> new ReviewSummary.ActorCount(r.actorId(), r.actorLabel(),
                        seven ? r.last7d() : r.last30d()))
                .filter(c -> c.count() > 0)
                .sorted(Comparator.comparingLong(ReviewSummary.ActorCount::count).reversed())
                .toList();
        return new ReviewSummary.DecidedWindow(total, automatic, actions, actors);
    }

    /** agent（review_summary.rs:276-316：开着的建议不分时间；窗口按**现在的**状态数）。 */
    private ReviewSummary.ReviewAgent agent(UUID kbId) {
        long open = summaryMapper.selectAgentOpen(kbId);
        List<ReviewSummaryMapper.AgentStatusRow> rows = summaryMapper.selectAgentStatusRows(kbId);
        return new ReviewSummary.ReviewAgent(
                summaryMapper.selectAgentRunning(kbId.toString()),
                summaryMapper.selectAgentQueueLen(kbId),
                open,
                agentWindow(rows, true),
                agentWindow(rows, false));
    }

    private static ReviewSummary.AgentWindow agentWindow(List<ReviewSummaryMapper.AgentStatusRow> rows,
                                                         boolean seven) {
        long applied = 0;
        long proposed = 0;
        long accepted = 0;
        long overridden = 0;
        long reverted = 0;
        for (ReviewSummaryMapper.AgentStatusRow r : rows) {
            long n = seven ? r.last7d() : r.last30d();
            switch (r.status()) {
                case "applied" -> applied = n;
                case "proposed" -> proposed = n;
                case "accepted" -> accepted = n;
                case "overridden" -> overridden = n;
                case "reverted" -> reverted = n;
                default -> {
                }
            }
        }
        return new ReviewSummary.AgentWindow(applied, proposed, accepted, overridden, reverted);
    }
}
