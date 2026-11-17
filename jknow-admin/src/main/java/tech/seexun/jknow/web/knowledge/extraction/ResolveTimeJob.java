package tech.seexun.jknow.web.knowledge.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.extract.ExtractLib;
import tech.seexun.jknow.extract.ExtractLib.ParsedTime;
import tech.seexun.jknow.extract.TimeExtract;
import tech.seexun.jknow.extract.TimeExtract.Anchor;
import tech.seexun.jknow.extract.TimeExtract.DateParts;
import tech.seexun.jknow.extract.TimeExtract.DocumentDating;
import tech.seexun.jknow.extract.TimeExtract.Interpretation;
import tech.seexun.jknow.extract.TimeExtract.MentionInput;
import tech.seexun.jknow.extract.TimeExtract.Offset;
import tech.seexun.jknow.extract.TimeExtract.RefAbsolute;
import tech.seexun.jknow.extract.TimeExtract.RefAnchored;
import tech.seexun.jknow.extract.TimeExtract.RefNone;
import tech.seexun.jknow.extract.TimeExtract.RefPeriod;
import tech.seexun.jknow.extract.TimeExtract.Shape;
import tech.seexun.jknow.extract.TimeExtract.TimeEntry;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.DocumentsService;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.mapper.KbMapper;
import tech.seexun.jknow.store.mapper.TimeMentionsMapper.MentionToResolveRow;
import tech.seexun.jknow.store.mapper.TimeMentionsMapper;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.WorldTime;

/**
 * 时间提及按文档解析（time_resolution.rs 逐语义，0045 决定 2–5）：<b>模型读，代码算</b>。
 * 抽取只记时间<b>词</b>（time_mentions）；这里是它后面的任务：
 * <ol>
 *   <li>文档时间上下文（0064 起由抽取时各块报上来，这里不再问模型）；</li>
 *   <li>解释——每个时间词连同它所在的那句话交给模型，模型只回形状/引用/粒度，
 *       <b>从不算日期</b>；</li>
 *   <li>算——日历算术、期间到区间、按粒度截断、形状蕴含的区间，全在代码里（决定 8：
 *       代码里没有时间词）。写明的日期是 A 级，按文档自己的锚点算出来的是 B 级，锚不到
 *       的是 C 级——C 级什么也不写，等锚点（决定 4、6）；</li>
 *   <li>落到开放陈述的 valid_from/valid_to。</li>
 * </ol>
 * 例外只长在推送的陈述上（0054）：when/ended 写的是完整日期或带区时刻时，载荷本身就
 * 是结构化的绝对值——代码直接读成解释，不送模型（#1089）；没有对话模型时代码读得动的
 * 照解、读不动的留 C，任务照常收工。
 */
@Component
public class ResolveTimeJob {

    private static final Logger log = LoggerFactory.getLogger(ResolveTimeJob.class);

    /** time_resolution.rs:35 BATCH：一批解释问多少个提及（40）。 */
    private static final int BATCH = 40;

    /** graph.rs:346 ENDED_UNKNOWN：结束了，不知哪天。 */
    public static final String ENDED_UNKNOWN = "unknown";

    /** graph.rs:351 WORLD_PRECISIONS：与 facts 同一张精度梯子（0024）。 */
    public static final List<String> WORLD_PRECISIONS =
            List.of("year", "month", "day", "hour", "minute", "second");

    private final DocumentsService documents;
    private final DocumentsWriteMapper writes;
    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final TimeMentionsMapper mentions;
    private final GraphWriteService graph;
    private final TypedMaterializeService materialize;
    private final TemporalService temporal;
    private final LlmSupport llm;
    private final AppEventBus events;
    private final JdbcTemplate jdbc;

    public ResolveTimeJob(DocumentsService documents, DocumentsWriteMapper writes, KbMapper kbs,
                          SettingsService settingsService, TimeMentionsMapper mentions,
                          GraphWriteService graph, TypedMaterializeService materialize,
                          TemporalService temporal, LlmSupport llm, AppEventBus events,
                          javax.sql.DataSource dataSource) {
        this.documents = documents;
        this.writes = writes;
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.mentions = mentions;
        this.graph = graph;
        this.materialize = materialize;
        this.temporal = temporal;
        this.llm = llm;
        this.events = events;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void handle(Job job) throws Exception {
        UUID documentId = UUID.fromString(job.payload().get("document_id").asText());
        resolveDocument(documentId);
    }

    // ====================================================================
    // 解算纯函数（time_resolution.rs:38-452）
    // ====================================================================

    /** time_resolution.rs:39-46 Resolved：世界轴上的位置与等级（A 写明/B 按锚点/C 锚不到）。 */
    record Resolved(OffsetDateTime from, String fromPrecision, OffsetDateTime to,
                    String toPrecision, String grade) {

        static final Resolved UNRESOLVED = new Resolved(null, null, null, null, "C");

        boolean unresolved() {
            return from == null && to == null && toPrecision == null;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Resolved r)) {
                return false;
            }
            return java.util.Objects.equals(from, r.from)
                    && java.util.Objects.equals(fromPrecision, r.fromPrecision)
                    && java.util.Objects.equals(to, r.to)
                    && java.util.Objects.equals(toPrecision, r.toPrecision)
                    && java.util.Objects.equals(grade, r.grade);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(from, fromPrecision, to, toPrecision, grade);
        }
    }

    /** time_resolution.rs:57-81 parts_to_time：数字部件 → 时刻与精度（精度是字写到的那一档）。 */
    static ParsedTime partsToTime(DateParts p) {
        // 写明的季度从它的头一个月起（日历季度；财年的季度走命名的期间）
        Integer month = p.month() != null ? p.month()
                : (p.quarter() != null ? (p.quarter() - 1) * 3 + 1 : null);
        LocalDate date;
        try {
            date = LocalDate.of(p.year(), month != null ? month : 1, p.day() != null ? p.day() : 1);
        } catch (Exception e) {
            return null;
        }
        OffsetDateTime t = date.atTime(p.hour() != null ? p.hour() : 0,
                p.minute() != null ? p.minute() : 0, p.second() != null ? p.second() : 0)
                .atOffset(ZoneOffset.UTC);
        String precision;
        if (month == null) {
            precision = "year";
        } else if (p.day() == null) {
            precision = "month";
        } else if (p.hour() == null) {
            precision = "day";
        } else if (p.minute() == null) {
            precision = "hour";
        } else if (p.second() == null) {
            precision = "minute";
        } else {
            precision = "second";
        }
        return new ParsedTime(t, precision);
    }

    /** 两档精度取粗的那一档：字只写到年，模型说是「天」也只能是年（time_resolution.rs:95-102）。 */
    static String coarser(String a, String b) {
        return rank(a) <= rank(b) ? a : b;
    }

    private static int rank(String p) {
        int i = WORLD_PRECISIONS.indexOf(p);
        return i < 0 ? 0 : i;
    }

    /**
     * time_resolution.rs:105-133 shift：锚点加偏移（决定 2 的算术）。月、季、年按月份加减
     * （月末自动收口）；周和天以下按时长。方向由 count 的符号与 direction 共同决定。
     */
    static OffsetDateTime shift(OffsetDateTime t, Offset offset) {
        long count = Math.abs(offset.count());
        boolean forward = (offset.direction() == TimeExtract.Direction.AFTER)
                == (offset.count() >= 0);
        try {
            return switch (offset.unit()) {
                case YEAR -> shiftMonths(t, count * 12, forward);
                case QUARTER -> shiftMonths(t, count * 3, forward);
                case MONTH -> shiftMonths(t, count, forward);
                case WEEK -> shiftDuration(t, java.time.Duration.ofDays(7 * count), forward);
                case DAY -> shiftDuration(t, java.time.Duration.ofDays(count), forward);
                case HOUR -> shiftDuration(t, java.time.Duration.ofHours(count), forward);
                case MINUTE -> shiftDuration(t, java.time.Duration.ofMinutes(count), forward);
                case SECOND -> shiftDuration(t, java.time.Duration.ofSeconds(count), forward);
            };
        } catch (Exception e) {
            return null;
        }
    }

    /** 月加减（chrono checked_add_months 语义：月末收口，2024-03-31 − 1 月 = 2024-02-29）。 */
    private static OffsetDateTime shiftMonths(OffsetDateTime t, long months, boolean forward) {
        LocalDate d = t.toLocalDate();
        long cur = (long) d.getYear() * 12 + (d.getMonthValue() - 1) + (forward ? months : -months);
        int year = (int) Math.floorDiv(cur, 12);
        int month = (int) (Math.floorMod(cur, 12) + 1);
        int day = Math.min(d.getDayOfMonth(), LocalDate.of(year, month, 1).lengthOfMonth());
        LocalDate moved = LocalDate.of(year, month, day);
        return moved.atTime(t.toLocalTime()).atOffset(t.getOffset());
    }

    private static OffsetDateTime shiftDuration(OffsetDateTime t, java.time.Duration d,
                                                boolean forward) {
        return forward ? t.plus(d) : t.minus(d);
    }

    /** 一个时刻和它的精度（世界轴的一端）。 */
    record Bound(OffsetDateTime time, String precision) {
    }

    /**
     * time_resolution.rs:140-150 quarter_around：一个季度——包含 t 的那三个月（有财年结束
     * 日就按财年的季，否则按日历季），起是季首月、止是季末月（终点精度到月）。
     */
    static Bound[] quarterAround(OffsetDateTime t, int[] fiscalYearEnd) {
        // 财年从结束日的下一个月算起：结束于 1 月 25 日的财年，季首月是 2、5、8、11 月
        int firstMonth = fiscalYearEnd != null ? fiscalYearEnd[0] % 12 + 1 : 1;
        int month0 = Math.floorMod(t.getMonthValue() - firstMonth, 12);
        int startMonth0 = month0 - month0 % 3;
        OffsetDateTime start = shiftMonths(t, startMonth0, false)
                .withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        start = WorldTime.truncateTo(start, "month");
        OffsetDateTime end = shiftMonths(start, 2, true);
        return new Bound[]{new Bound(start, "month"), new Bound(end, "month")};
    }

    /** time_resolution.rs:153-155 period_bounds。 */
    static Bound[] periodBounds(DateParts from, DateParts to) {
        ParsedTime f = partsToTime(from);
        ParsedTime t = partsToTime(to);
        if (f == null || t == null) {
            return null;
        }
        return new Bound[]{new Bound(f.time(), f.precision()), new Bound(t.time(), t.precision())};
    }

    /**
     * time_resolution.rs:160-213 place：形状决定字在世界轴上占哪一段（决定 7）。点：一刻，
     * 两端同值；自：起点；至：终点；区间：两端；截至：观察到的起点；时长：没有位置；
     * 结束但不知何时：终点精度 unknown。
     */
    static Resolved place(Shape shape, Bound at, Bound until, String grade) {
        if (at != null) {
            at = new Bound(WorldTime.truncateTo(at.time(), at.precision()), at.precision());
        }
        if (until != null) {
            until = new Bound(WorldTime.truncateTo(until.time(), until.precision()),
                    until.precision());
        }
        Resolved r = new Resolved(null, null, null, null, grade);
        switch (shape) {
            case POINT -> {
                if (at != null) {
                    r = new Resolved(at.time(), at.precision(), at.time(), at.precision(), grade);
                }
            }
            case SINCE, AS_OF -> {
                if (at != null) {
                    r = new Resolved(at.time(), at.precision(), null, null, grade);
                }
            }
            case UNTIL -> {
                Bound end = until != null ? until : at;
                if (end != null) {
                    r = new Resolved(null, null, end.time(), end.precision(), grade);
                }
            }
            case INTERVAL -> {
                r = new Resolved(at != null ? at.time() : null,
                        at != null ? at.precision() : null,
                        until != null ? until.time() : null,
                        until != null ? until.precision() : null, grade);
            }
            case DURATION -> {
                return Resolved.UNRESOLVED;
            }
            case ENDED_UNKNOWN -> {
                r = new Resolved(null, null, null, ENDED_UNKNOWN, grade);
            }
        }
        if (r.from() == null && r.to() == null && r.toPrecision() == null) {
            return Resolved.UNRESOLVED;
        }
        return r;
    }

    /** time_resolution.rs:216-237 anchor_time：一个锚点在世界轴上的位置（文档日期/另一提及/命名的期间）。 */
    static Bound[] anchorTime(Anchor anchor, DocumentDating ctx, Map<Long, Resolved> earlier) {
        if (anchor instanceof TimeExtract.AnchorDocument) {
            if (ctx.date == null) {
                return null;
            }
            ParsedTime t = partsToTime(ctx.date);
            return t == null ? null : new Bound[]{new Bound(t.time(), t.precision()), null};
        }
        if (anchor instanceof TimeExtract.AnchorMention m) {
            Resolved r = earlier.get(m.id());
            if (r == null || r.from() == null) {
                return null;
            }
            return new Bound[]{new Bound(r.from(), r.fromPrecision()),
                    r.to() != null ? new Bound(r.to(), r.toPrecision()) : null};
        }
        if (anchor instanceof TimeExtract.AnchorPeriod p) {
            for (TimeExtract.NamedPeriod np : ctx.periods) {
                if (np.name().equalsIgnoreCase(p.name())) {
                    Bound[] b = periodBounds(np.from(), np.to());
                    return b == null ? null : new Bound[]{b[0], b[1]};
                }
            }
        }
        return null;
    }

    /** time_resolution.rs:240-352 resolve_one：一条解释 → 世界轴位置。 */
    static Resolved resolveOne(Interpretation interp, DocumentDating ctx,
                               Map<Long, Resolved> earlier) {
        String g = interp.granularity().asString();
        // 「结束了，不知哪天」是字明说的（#393）：没有日期可算，但结束本身是 A 级
        if (interp.shape() == Shape.ENDED_UNKNOWN) {
            return place(Shape.ENDED_UNKNOWN, null, null, "A");
        }
        if (interp.reference() instanceof RefAbsolute abs) {
            ParsedTime t = partsToTime(abs.from());
            if (t == null) {
                return Resolved.UNRESOLVED;
            }
            ParsedTime until = abs.to() != null ? partsToTime(abs.to()) : null;
            // 一个季度是三个月的区间：到它最后一个月为止
            if (abs.from().quarter() != null && until == null) {
                OffsetDateTime last = shiftMonths(t.time(), 2, true);
                Shape shape = interp.shape() == Shape.POINT || interp.shape() == Shape.DURATION
                        ? Shape.INTERVAL : interp.shape();
                return place(shape, new Bound(t.time(), "month"),
                        new Bound(last, "month"), "A");
            }
            return place(interp.shape(), new Bound(t.time(), coarser(t.precision(), g)),
                    until != null ? new Bound(until.time(), until.precision()) : null, "A");
        }
        if (interp.reference() instanceof RefAnchored anc) {
            Bound[] anchorB = anchorTime(anc.anchor(), ctx, earlier);
            if (anchorB == null) {
                return Resolved.UNRESOLVED;
            }
            Bound at = anchorB[0];
            Bound until = anchorB[1];
            // 对着一个期间说「截至」「至」（「年末」对着报告年）：说的是期间的末，不是首。
            // 这是形状的语义，不是认词
            if (anc.offset() == null && anc.anchor() instanceof TimeExtract.AnchorPeriod
                    && (interp.shape() == Shape.AS_OF || interp.shape() == Shape.UNTIL)
                    && until != null) {
                return place(interp.shape(), until, null, "B");
            }
            if (anc.offset() != null) {
                Offset o = anc.offset();
                OffsetDateTime moved = shift(at.time(), o);
                if (moved == null) {
                    return Resolved.UNRESOLVED;
                }
                // 以「年」「月」为单位的偏移（本年、去年、上个月）说的是那一整段：
                // 起止同一个桶，精度就是那个单位
                if ((o.unit() == TimeExtract.Unit.YEAR || o.unit() == TimeExtract.Unit.MONTH)
                        && (interp.shape() == Shape.POINT
                                || interp.shape() == Shape.INTERVAL)) {
                    String unitP = o.unit() == TimeExtract.Unit.YEAR ? "year" : "month";
                    OffsetDateTime start = WorldTime.truncateTo(moved, unitP);
                    return place(Shape.POINT, new Bound(start, unitP), null, "B");
                }
                // 以「季」为单位的偏移（本季度、上季度）说的是那一整个季度，不是某一天
                if (o.unit() == TimeExtract.Unit.QUARTER) {
                    Bound[] q = quarterAround(moved, ctx.fiscalYearEnd);
                    if (q == null) {
                        return Resolved.UNRESOLVED;
                    }
                    Shape shape = switch (interp.shape()) {
                        case POINT, SINCE, AS_OF, DURATION -> Shape.INTERVAL;
                        default -> interp.shape();
                    };
                    return place(shape, q[0], q[1], "B");
                }
                at = new Bound(moved, at.precision());
                if (until != null) {
                    OffsetDateTime movedUntil = shift(until.time(), o);
                    until = movedUntil == null ? null
                            : new Bound(movedUntil, g);
                }
            }
            // 锚点算出来的时刻，精度由字说的粒度定，但不能比锚点自己更细
            return place(interp.shape(), new Bound(at.time(), coarser(at.precision(), g)),
                    until, "B");
        }
        if (interp.reference() instanceof RefPeriod per) {
            // 只写了一端的期间（「三个月截至 6 月 30 日」）：写明的那一端照写，另一端不猜
            if (per.from() == null && per.to() != null) {
                ParsedTime t = partsToTime(per.to());
                return t == null ? Resolved.UNRESOLVED
                        : place(Shape.UNTIL, null, new Bound(t.time(), t.precision()), "A");
            }
            if (per.from() != null && per.to() == null) {
                ParsedTime t = partsToTime(per.from());
                return t == null ? Resolved.UNRESOLVED
                        : place(Shape.SINCE, new Bound(t.time(), t.precision()), null, "A");
            }
            Bound[] bounds = null;
            String grade;
            if (per.from() != null && per.to() != null) {
                bounds = periodBounds(per.from(), per.to());
                grade = "A";
            } else {
                for (TimeExtract.NamedPeriod np : ctx.periods) {
                    if (np.name().equalsIgnoreCase(per.name())) {
                        bounds = periodBounds(np.from(), np.to());
                        break;
                    }
                }
                grade = "B";
            }
            if (bounds == null) {
                return Resolved.UNRESOLVED;
            }
            // 一个期间本身是区间；「截至期末」这类形状照形状算
            Shape shape = switch (interp.shape()) {
                case POINT, SINCE, DURATION -> Shape.INTERVAL;
                default -> interp.shape();
            };
            return place(shape, bounds[0], bounds[1], grade);
        }
        return Resolved.UNRESOLVED;
    }

    /**
     * time_resolution.rs:355-381 resolve_interpretations：每轮至少解开一条才继续——循环、
     * 自引用、缺失或不可用的锚点都在无进展时留下 C。不能固定遍数：模型回复可以把任意长
     * 的依赖链倒着写。
     */
    static Map<Long, Resolved> resolveInterpretations(List<Interpretation> interpretations,
                                                      java.util.function.LongFunction<DocumentDating> contextFor) {
        Map<Long, Resolved> resolved = new LinkedHashMap<>();
        List<Interpretation> pendingList = new ArrayList<>(interpretations);
        while (!pendingList.isEmpty()) {
            int before = pendingList.size();
            List<Interpretation> still = new ArrayList<>();
            for (Interpretation interp : pendingList) {
                Resolved r = resolveOne(interp, contextFor.apply(interp.id()), resolved);
                if (r.unresolved()) {
                    still.add(interp);
                } else {
                    resolved.put(interp.id(), r);
                }
            }
            pendingList = still;
            if (pendingList.size() == before) {
                break;
            }
        }
        for (Interpretation interp : pendingList) {
            resolved.put(interp.id(), Resolved.UNRESOLVED);
        }
        return resolved;
    }

    /** time_resolution.rs:384-391 context_at：算的时候文档的日期换成提及所在那一节的起算点。 */
    static DocumentDating contextAt(DocumentDating context, Integer nowIndex) {
        DocumentDating scoped = copy(context);
        if (nowIndex != null && nowIndex < context.entries.size()) {
            scoped.date = context.entries.get(nowIndex).from();
            scoped.dateWords = context.entries.get(nowIndex).words();
        }
        return scoped;
    }

    private static DocumentDating copy(DocumentDating d) {
        DocumentDating out = new DocumentDating();
        out.entries = new ArrayList<>(d.entries);
        out.date = d.date;
        out.dateWords = d.dateWords;
        out.periods = new ArrayList<>(d.periods);
        out.fiscalYearEnd = d.fiscalYearEnd;
        return out;
    }

    /**
     * time_resolution.rs:402-435 stated_reading：推送载荷里写明的值（#1089）——完整日期与
     * 带区时刻读成点状绝对解释，精度随字；光杆的年、年月、读不动的字都回 null（退回模型
     * 那条路）。from 存换算成 UTC 后的部件。
     */
    static Interpretation statedReading(String text, long id) {
        ParsedTime t = ExtractLib.parseTime(text);
        if (t == null) {
            return null;
        }
        Integer hour = null;
        Integer minute = null;
        Integer second = null;
        switch (t.precision()) {
            case "day" -> {
            }
            case "hour" -> hour = t.time().getHour();
            case "minute" -> {
                hour = t.time().getHour();
                minute = t.time().getMinute();
            }
            case "second" -> {
                hour = t.time().getHour();
                minute = t.time().getMinute();
                second = t.time().getSecond();
            }
            default -> {
                return null; // 光杆的年、年月不在这刀里（#1089 的界）
            }
        }
        OffsetDateTime utc = t.time().withOffsetSameInstant(ZoneOffset.UTC);
        DateParts from = new DateParts(utc.getYear(), null, utc.getMonthValue(),
                utc.getDayOfMonth(), hour, minute, second);
        return new Interpretation(id, Shape.POINT, new RefAbsolute(from, null),
                TimeExtract.Granularity.parse(t.precision()));
    }

    /** time_resolution.rs:438-452 combine：一条陈述的起与止——when 给起（点与区间也给止），ended 给止。 */
    static Resolved combine(Resolved when, Resolved ended) {
        Resolved r = when != null ? when : Resolved.UNRESOLVED;
        if (ended != null) {
            OffsetDateTime to = ended.to() != null ? ended.to() : ended.from();
            String toP = ended.to() != null ? ended.toPrecision() : ended.fromPrecision();
            if (to != null || toP != null) {
                r = new Resolved(r.from(), r.fromPrecision(), to, toP, r.grade());
            }
            if ("C".equals(r.grade())) {
                r = new Resolved(r.from(), r.fromPrecision(), r.to(), r.toPrecision(),
                        ended.grade());
            }
        }
        return r;
    }

    // ====================================================================
    // 主体（time_resolution.rs:456-750 resolve_document）
    // ====================================================================

    /** 幂等：再跑一次覆盖上一次的结果，锚点晚到时重排一次即可（决定 4 的第一步）。 */
    void resolveDocument(UUID documentId) throws Exception {
        DocumentRow doc = documents.get(documentId);
        if (doc == null) {
            throw new IllegalStateException("document not found: " + documentId);
        }
        if (doc.deletedAt() != null) {
            return;
        }
        KbRow kb = kbs.selectById(doc.kbId());
        LlmSettingsRow settings = settingsService.get(kb.workspaceId());
        ChatClient client = LlmSupport.chatClient(settings);
        boolean pushed = "statements".equals(sourceKind(doc.sourceId()));
        if (!pushed && client == null) {
            throw new IllegalStateException("Chat model not configured; cannot resolve time");
        }

        // 1. 文档时间上下文：抽取时各块报上来的（0064）。这里不问模型
        DocumentDating context = readContext(doc, documentId);
        attestStatements(doc, context);

        // 抽取报上来的期间（0064）并进文档命名的期间里：解释时按名字指它
        for (TimeEntry e : new ArrayList<>(context.entries)) {
            if (!"period".equals(e.kind())) {
                continue;
            }
            String name = e.name().isEmpty() ? e.words() : e.name();
            boolean exists = context.periods.stream().anyMatch(p -> p.name().equalsIgnoreCase(name));
            if (e.to() != null && !exists) {
                context.periods.add(new TimeExtract.NamedPeriod(name, e.from(), e.to()));
            }
        }

        // 2. 提及：同一篇里同样的字在同样的句子里只解释一次
        List<MentionToResolveRow> mentionRows = mentions.selectForDocument(documentId);
        if (mentionRows.isEmpty()) {
            return;
        }
        Map<UUID, String> chunkText = new HashMap<>();
        jdbc.query("SELECT id, text FROM chunks WHERE document_id = ?",
                rs -> {
                    chunkText.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                }, documentId);
        // 每条提及所在的那一节里管事的起算点（0064 决定 4）
        java.util.function.Function<MentionToResolveRow, Integer> nowOf = m -> {
            String text = chunkText.get(m.chunkId());
            if (text == null) {
                return null;
            }
            List<String> path = TimeExtract.headingsAt(text, m.charStart());
            TimeEntry found = TimeExtract.nowInForce(context.entries, path);
            if (found == null) {
                return null;
            }
            return context.entries.indexOf(found);
        };
        List<Key> distinct = new ArrayList<>();
        Map<Key, Integer> index = new HashMap<>();
        Map<UUID, Key> keyOf = new HashMap<>();
        Map<Integer, Interpretation> presolved = new HashMap<>();
        for (MentionToResolveRow m : mentionRows) {
            Key key = new Key(m.text(), m.sentence(), nowOf.apply(m));
            keyOf.put(m.id(), key);
            if (index.containsKey(key)) {
                continue;
            }
            index.put(key, distinct.size());
            distinct.add(key);
            if (pushed) {
                Interpretation interp = statedReading(m.text(), distinct.size() - 1);
                if (interp != null) {
                    presolved.put(distinct.size() - 1, interp);
                }
            }
        }
        TimeExtract.TimeContext ctx = new TimeExtract.TimeContext(context.date, context.dateWords,
                context.periods, context.fiscalYearEnd);
        List<Interpretation> interpretations = new ArrayList<>(presolved.values());
        Set<Long> received = new HashSet<>();
        int[] malformed = {0};
        // 问两轮：第一轮全部，第二轮只问第一轮没答到的（代码读过的不问——重问也只能是
        // 这个答案，代码读过的不许模型盖掉）
        List<Integer> todo = new ArrayList<>();
        for (int i = 0; i < distinct.size(); i++) {
            if (!presolved.containsKey(i)) {
                todo.add(i);
            }
        }
        if (settings != null && client != null) {
            for (int round = 0; round < 2; round++) {
                if (round == 1) {
                    todo.removeIf(i -> received.contains(i.longValue()));
                    if (todo.isEmpty()) {
                        break;
                    }
                    log.info("document={} 时间解释有 {} 条没答到，再问一次", documentId, todo.size());
                }
                for (int from = 0; from < todo.size(); from += BATCH) {
                    List<Integer> batch = todo.subList(from, Math.min(from + BATCH, todo.size()));
                    List<MentionInput> inputs = new ArrayList<>();
                    List<Long> ids = new ArrayList<>();
                    for (int i : batch) {
                        inputs.add(new MentionInput(i, distinct.get(i).text(),
                                distinct.get(i).sentence()));
                        ids.add((long) i);
                    }
                    List<tech.seexun.jknow.llm.ChatClient.ChatMessage> messages =
                            TimeExtract.buildInterpretationMessages(ctx, inputs);
                    tech.seexun.jknow.llm.ChatClient.Reply reply;
                    try {
                        reply = llm.chatRetryingRateLimits(settings, client, messages, 0.0d);
                    } catch (Exception e) {
                        log.warn("document={} 时间解释调用失败，这一批留作未解析: {}",
                                documentId, e.getMessage());
                        continue;
                    }
                    TimeExtract.InterpretationReply parsed;
                    try {
                        parsed = TimeExtract.parseInterpretationResponse(reply.text(), ids);
                    } catch (Exception e) {
                        log.warn("document={} 时间解释回复解析失败，这一批留作未解析: {}",
                                documentId, e.getMessage());
                        continue;
                    }
                    malformed[0] += parsed.skipped();
                    if (parsed.skipped() > 0) {
                        log.warn("document={} 时间解释里有 {} 条读不了的条目", documentId,
                                parsed.skipped());
                    }
                    for (Interpretation interp : parsed.interpretations()) {
                        received.add(interp.id());
                    }
                    interpretations.addAll(parsed.interpretations());
                }
            }
        }
        Map<Long, Resolved> computed = resolveInterpretations(interpretations, id -> {
            int i = (int) (long) id;
            Integer now = i >= 0 && i < distinct.size() ? distinct.get(i).now() : null;
            return contextAt(context, now);
        });
        Map<Integer, Map.Entry<Interpretation, Resolved>> resolved = new HashMap<>();
        for (Interpretation interp : interpretations) {
            int i = (int) interp.id();
            Resolved r = computed.get(interp.id());
            if (r != null) {
                resolved.put(i, Map.entry(interp, r));
            }
        }

        // 3. 写回提及，再按陈述合起止
        Map<UUID, WhenEnded> perFact = new HashMap<>();
        int[] a = {0};
        int[] b = {0};
        int[] c = {0};
        for (MentionToResolveRow m : mentionRows) {
            Key key = keyOf.get(m.id());
            Integer i = key != null ? index.get(key) : null;
            if (i == null) {
                continue;
            }
            Map.Entry<Interpretation, Resolved> entry = resolved.get(i);
            if (entry == null) {
                // 推送的文档没有对话模型可问（#1089）：代码读不动的提及按 C 记——「有提及、
                // 没能放上轴」和「没有时间词」要分得出；任务照常收工。解释列留空：没有人
                // 读过它，不编一条出来
                if (pushed && client == null) {
                    mentions.setResolution(m.id(), "C", null, null, null, null);
                    c[0]++;
                    slot(perFact, m.factId(), m.role(), Resolved.UNRESOLVED);
                }
                // 有模型而没答到的：留着字，不写等级（下次重跑再问）
                continue;
            }
            Interpretation interp = entry.getKey();
            Resolved r = entry.getValue();
            mentions.setInterpretation(m.id(), interp.shape().asString(),
                    referenceJson(interp), interp.granularity().asString());
            mentions.setResolution(m.id(), r.grade(), truncate(r.from(), r.fromPrecision()),
                    r.fromPrecision(), truncate(r.to(), r.toPrecision()), r.toPrecision());
            switch (r.grade() == null ? "C" : r.grade()) {
                case "A" -> a[0]++;
                case "B" -> b[0]++;
                default -> c[0]++;
            }
            slot(perFact, m.factId(), m.role(), r);
        }
        int dated = 0;
        for (Map.Entry<UUID, WhenEnded> e : perFact.entrySet()) {
            Resolved when = e.getValue().when;
            Resolved ended = e.getValue().ended;
            Resolved r = combine(when, ended);
            // 起点的等级只看起点那条提及（combine 在起点锚不到时借结束端的等级来报数，
            // 这里不借：引擎问的是「这一行的起点是怎么来的」，0045 第 3 刀）
            String grade = when != null ? when.grade() : null;
            boolean placed = r.from() != null || r.to() != null || r.toPrecision() != null;
            // 什么都没算出来也要写等级 C：只有这一列说得出「这句话有时间词、我们没能把它
            // 放到轴上」，否则它和「整句没有时间词」在库里长得一模一样
            if (!placed && !"C".equals(grade)) {
                continue;
            }
            graph.setOpenValidity(e.getKey(), r.from(), r.fromPrecision(), r.to(),
                    r.toPrecision(), grade);
            if (placed) {
                dated++;
            }
        }
        log.info("document={} 时间提及解析完成：mentions={} A={} B={} C={} malformed={} dated={}",
                documentId, mentionRows.size(), a[0], b[0], c[0], malformed[0], dated);
        if (dated > 0) {
            events.emitGraph(doc.kbId());
        }
    }

    private static final class WhenEnded {
        Resolved when;
        Resolved ended;
    }

    private static void slot(Map<UUID, WhenEnded> perFact, UUID factId, String role, Resolved r) {
        WhenEnded slot = perFact.computeIfAbsent(factId, k -> new WhenEnded());
        if ("ended".equals(role)) {
            slot.ended = r;
        } else {
            slot.when = r;
        }
    }

    private static OffsetDateTime truncate(OffsetDateTime t, String precision) {
        return t == null || precision == null ? t : WorldTime.truncateTo(t, precision);
    }

    /**
     * 把解释参照存回提及列（Rust serde_json::to_value(&interp.reference)）。
     * serde 形状逐字：enum 带 tag="kind"；Absolute.to/Anchored.offset/Period.from/to 都是
     * <b>没有 skip_serializing_if 的 Option</b>——None 序列化成 {@code null} 而不是缺键
     * （time_mentions.reference 是 JSONB，缺键与 null 键在库内形状不同；任务 11 快照比对
     * 抓出的对齐缺口——与 DateParts 的 month/day null 常驻同一条规矩）。
     */
    private static JsonNode referenceJson(Interpretation interp) {
        com.fasterxml.jackson.databind.ObjectMapper om =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.JsonNodeFactory f = om.getNodeFactory();
        ObjectNode n = om.createObjectNode();
        if (interp.reference() instanceof RefAbsolute abs) {
            n.put("kind", "absolute");
            n.set("from", partsJson(abs.from()));
            n.set("to", abs.to() == null ? f.nullNode() : partsJson(abs.to()));
            return n;
        }
        if (interp.reference() instanceof RefAnchored anc) {
            n.put("kind", "anchored");
            ObjectNode anchor = n.putObject("anchor");
            if (anc.anchor() instanceof TimeExtract.AnchorDocument) {
                anchor.put("kind", "document");
            } else if (anc.anchor() instanceof TimeExtract.AnchorMention m) {
                anchor.put("kind", "mention");
                anchor.put("id", m.id());
            } else if (anc.anchor() instanceof TimeExtract.AnchorPeriod p) {
                anchor.put("kind", "period");
                anchor.put("name", p.name());
            }
            if (anc.offset() == null) {
                n.set("offset", f.nullNode());
            } else {
                ObjectNode offset = n.putObject("offset");
                offset.put("count", anc.offset().count());
                offset.put("unit", anc.offset().unit().asString());
                offset.put("direction", anc.offset().direction().asString());
            }
            return n;
        }
        if (interp.reference() instanceof RefPeriod per) {
            n.put("kind", "period");
            n.put("name", per.name());
            n.set("from", per.from() == null ? f.nullNode() : partsJson(per.from()));
            n.set("to", per.to() == null ? f.nullNode() : partsJson(per.to()));
            return n;
        }
        n.put("kind", "none");
        return n;
    }

    private static ObjectNode partsJson(DateParts p) {
        // Rust serde 的 DateParts「存成 JSONB 时用全名键」：year 常驻；quarter 带
        // skip_serializing_if（None 不写）；month/day/hour/minute/second 的 Option 没有
        // skip——None 写 null。短键（y/m/d）是<b>回复</b>的读法（TimeExtract.parts），
        // 不是这一处（写进 time_mentions.reference）的写法
        com.fasterxml.jackson.databind.ObjectMapper om =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.JsonNodeFactory f = om.getNodeFactory();
        ObjectNode n = om.createObjectNode();
        n.put("year", p.year());
        if (p.quarter() != null) {
            n.put("quarter", p.quarter());
        }
        n.put("month", p.month() == null ? f.nullNode() : f.numberNode(p.month()));
        n.put("day", p.day() == null ? f.nullNode() : f.numberNode(p.day()));
        n.put("hour", p.hour() == null ? f.nullNode() : f.numberNode(p.hour()));
        n.put("minute", p.minute() == null ? f.nullNode() : f.numberNode(p.minute()));
        n.put("second", p.second() == null ? f.nullNode() : f.numberNode(p.second()));
        return n;
    }

    private String sourceKind(UUID sourceId) {
        if (sourceId == null) {
            return null;
        }
        DocumentsWriteMapper.SourceRef ref = writes.selectSourceRef(sourceId);
        return ref == null ? null : ref.kind();
    }

    /** time_resolution.rs:476-509：读文档时间上下文；没报日期时不再另问一次开头（0064 决定 1）。 */
    private DocumentDating readContext(DocumentRow doc, UUID documentId) {
        DocumentDating context = fromContextJson(doc.timeContext());
        if (context == null) {
            return new DocumentDating();
        }
        // 整篇的起算点照旧记成文档的日期（文库排序、引擎给没起点的行排次序都读它）
        if (ExtractionJob.datedAt(doc) == null && context.date != null) {
            ParsedTime t = partsToTime(context.date);
            if (t != null) {
                writes.setContentDate(documentId, t.time());
            }
        }
        // 这一刀之前读开头定出来、存在文档上的日期（只有日期、没有条目）：它就是整篇的
        // 起算点，和抽取报上来的一样进条目里
        if (context.entries.isEmpty() && context.date != null && context.dateWords != null) {
            context.entries.add(new TimeEntry("now", "", context.dateWords, context.date, null,
                    List.of(), null, null));
        }
        return context;
    }

    /** documents.time_context 的 JSONB → DocumentDating（serde default 语义：缺省字段容错）。 */
    static DocumentDating fromContextJson(JsonNode json) {
        if (json == null || !json.isObject()) {
            return null;
        }
        DocumentDating out = new DocumentDating();
        JsonNode entries = json.get("entries");
        if (entries != null && entries.isArray()) {
            for (JsonNode e : entries) {
                try {
                    out.entries.add(new TimeEntry(e.path("kind").asText(),
                            e.path("name").asText(""), e.path("words").asText(),
                            partsFromJson(e.path("from")),
                            e.has("to") && !e.get("to").isNull() ? partsFromJson(e.get("to"))
                                    : null,
                            stringsOf(e.path("scope")),
                            e.path("chunk").isTextual() ? e.get("chunk").asText() : null,
                            e.path("char_start").canConvertToInt() && !e.path("char_start").isNull()
                                    ? e.get("char_start").asInt() : null));
                } catch (Exception ignore) {
                    // 坏条目不毁整份
                }
            }
        }
        out.date = json.has("date") && !json.get("date").isNull()
                ? partsFromJson(json.get("date")) : null;
        out.dateWords = json.path("date_words").isTextual() ? json.get("date_words").asText() : null;
        JsonNode periods = json.get("periods");
        if (periods != null && periods.isArray()) {
            for (JsonNode p : periods) {
                out.periods.add(new TimeExtract.NamedPeriod(p.path("name").asText(),
                        partsFromJson(p.path("from")), partsFromJson(p.path("to"))));
            }
        }
        JsonNode fye = json.get("fiscal_year_end");
        if (fye != null && fye.isArray() && fye.size() == 2) {
            out.fiscalYearEnd = new int[]{fye.get(0).asInt(), fye.get(1).asInt()};
        }
        return out;
    }

    private static DateParts partsFromJson(JsonNode n) {
        return new DateParts(n.path("year").asInt(n.path("y").asInt()),
                n.hasNonNull("quarter") ? n.get("quarter").asInt() : null,
                n.hasNonNull("month") ? n.get("month").asInt() : null,
                n.hasNonNull("day") ? n.get("day").asInt() : null,
                n.hasNonNull("hour") ? n.get("hour").asInt() : null,
                n.hasNonNull("minute") ? n.get("minute").asInt() : null,
                n.hasNonNull("second") ? n.get("second").asInt() : null);
    }

    private static List<String> stringsOf(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(x -> out.add(x.asText()));
        }
        return out;
    }

    // ====================================================================
    // 见证（time_resolution.rs:752-833 attest_statements）
    // ====================================================================

    /** time_resolution.rs:754-761 witness_name：日期的名字加它的字（「提报日期 2026年9月4日」）。 */
    static String witnessName(String name, String words) {
        String n = name == null ? "" : name.strip();
        String w = words == null ? "" : words.strip();
        if (n.isEmpty() || n.equals(w)) {
            return w;
        }
        return n + " " + w;
    }

    /**
     * 这篇文档的陈述各由它所在那一节里文本说话的那一刻作证（0064 决定 3）。一条陈述在哪
     * 一节，看它的引文在它那一块里的位置；管着它的是范围最深的那个 now。没有的不写——
     * 见证留空，这条陈述在任何时点都成立（决定 5）。这是「截至」，不是起点。
     */
    void attestStatements(DocumentRow doc, DocumentDating context) {
        UUID documentId = doc.id();
        boolean anyNow = context.entries.stream().anyMatch(e -> "now".equals(e.kind()));
        if (!anyNow) {
            // 这一遍文档一条日期都没说：上一遍记在证据上的也不算数了
            jdbc.update("""
                    UPDATE fact_evidence SET attested_at = NULL, attested_by = NULL
                     WHERE document_id = ? AND attested_at IS NOT NULL
                    """, documentId);
            return;
        }
        List<Object[]> rows = new ArrayList<>();
        jdbc.query("""
                SELECT fe.fact_id, fe.chunk_id, c.text, fe.quote_start
                  FROM fact_evidence fe
                  JOIN facts f ON f.id = fe.fact_id
                  JOIN chunks c ON c.id = fe.chunk_id
                 WHERE fe.document_id = ? AND c.superseded_at IS NULL
                   AND f.layer = 'open' AND f.invalidated_at IS NULL
                """, rs -> {
            rows.add(new Object[]{UUID.fromString(rs.getString(1)),
                    UUID.fromString(rs.getString(2)), rs.getString(3),
                    (Integer) rs.getObject(4)});
        }, documentId);
        int moved = 0;
        for (Object[] row : rows) {
            UUID factId = (UUID) row[0];
            UUID chunkId = (UUID) row[1];
            String text = (String) row[2];
            Integer start = (Integer) row[3];
            List<String> path = TimeExtract.headingsAt(text, start != null ? start : 0);
            TimeEntry nowEntry = TimeExtract.nowInForce(context.entries, path);
            OffsetDateTime at = null;
            String by = null;
            if (nowEntry != null) {
                ParsedTime t = partsToTime(nowEntry.from());
                if (t != null) {
                    at = t.time();
                    by = witnessName(nowEntry.name(), nowEntry.words());
                }
            }
            // 每条证据记下自己这一节的日期（没说的记成空）：事实上只留最早的那一个，
            // 证据所在的文档删了之后要照还在的证据重算，得知道每一条各是哪天
            graph.witnessEvidence(factId, chunkId, at, by);
            if (at == null) {
                continue;
            }
            if (graph.attestStatement(factId, at, by)) {
                moved++;
            }
        }
        // 已经物化出来的、规则算出来的类型化行跟着它们的陈述走
        List<UUID> typed = materialize.syncTypedAttestation(doc.kbId(), null);
        // 见证动了的行换了它在唯一性时间线上的位置：只把这些行所在的时间线重算一遍
        // ——先提交同步，再拿时间线锁，不在持有 UPDATE 行锁时等咨询锁
        TemporalService.ReconcileReport reconciled = temporal.reconcileMovedFacts(doc.kbId(), typed);
        if (moved > 0 || !typed.isEmpty()) {
            log.info("document={} 陈述按它所在那一节的日期作了证：statements={} typed={}",
                    documentId, moved, typed.size());
            events.emitGraph(doc.kbId());
        }
    }

    /** 提及的去重键：(字, 句子, 起算点下标)。 */
    record Key(String text, String sentence, Integer now) {
    }
}
