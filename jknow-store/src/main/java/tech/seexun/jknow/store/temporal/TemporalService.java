package tech.seexun.jknow.store.temporal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.mapper.TemporalMapper;
import tech.seexun.jknow.store.mapper.TemporalMapper.CandidateRow;
import tech.seexun.jknow.store.mapper.TemporalMapper.ConflictRow;
import tech.seexun.jknow.store.mapper.TemporalMapper.PlacedFact;
import tech.seexun.jknow.store.mapper.TemporalMapper.SharedPredicate;
import tech.seexun.jknow.store.temporal.TemporalMath.EndPair;
import tech.seexun.jknow.store.temporal.WorldTime.Temporal;

/**
 * 时态引擎（temporal.rs 1963 行的对等）——时间轴、有效期闭合、第二时钟（改写链）。
 *
 * <p><b>原则</b>（模块头逐字语义）：
 * <ul>
 * <li>纯规则判定，零 LLM——模糊性已在上游（消解归并实体、本体标 functional）消化。
 * <li><b>闭合走「作废 + 改写」而非原地 UPDATE</b>：旧断言 invalidated_at 记下何时被
 *     修正，修正行闭合区间并以 supersedes 链回旧行——「以当时的认知回放当时」得以
 *     成立（0019/0057）。证据、限定、物化链接随行复制。
 * <li><b>先锁时间线再改行</b>（谓词级共享→时间线级排他，两级各自排序）：一次要动
 *     几条时间线的事务（撤回合并、删除/恢复文档）先按固定顺序把锁全拿到再改任何一行
 *     ——谁也不会拿着行锁去等咨询锁（#679 第四轮）。
 * <li><b>described 不许关旧行</b>：看图描述出来的事实能进图、能被引用，却不许单独
 *     关掉一段正确的旧值——那一对交给人（0040 决定 4），冲突原因 described_evidence。
 * <li>拿不准（缺时间/同时开始/低置信）绝不硬闭合，进 fact_conflicts 由人裁决。
 * </ul>
 *
 * <p><b>事务口径</b>：Rust 里拿 pool 的函数自己 begin/commit（一个时间线一个事务），
 * 这里一律用显式 {@link TransactionTemplate}（REQUIRES_NEW）对等——不用 {@code @Transactional}
 * 注解，避免同类自调用（resolveConflict→closeSuperseded/retract）绕过代理的坑；拿 tx 的
 * 函数（tidyTimelinesTx/reattestTx/tidy/arrive/rewriteEndTx/recordConflictTx）加入
 * 调用方当前事务（删除/恢复文档在自己的事务里改完行之后调用）。
 */
@Service
public class TemporalService {

    /** temporal.rs:237 BULK_TIMELINES：超过即谓词级排他锁（不再逐条时间线锁）。 */
    public static final int BULK_TIMELINES = 256;

    private final TemporalMapper mapper;
    private final TransactionTemplate newTx;

    public TemporalService(TemporalMapper mapper, PlatformTransactionManager txManager) {
        this.mapper = mapper;
        // REQUIRES_NEW：一条时间线一个事务（temporal.rs:788-799 逐线 begin/commit 的对等）
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** temporal.rs:91-96 对账结果：自动闭合产生的修正行 id 与这次新进人审的冲突数。 */
    public static final class ReconcileReport {
        public final List<UUID> corrected = new ArrayList<>();
        public int conflicts;

        @Override
        public String toString() {
            return "ReconcileReport{corrected=" + corrected.size() + ", conflicts=" + conflicts + "}";
        }
    }

    // ==== 咨询锁（temporal.rs:228-287） ====

    /**
     * temporal.rs:248-287 {@code lock_timelines} 逐字语义：谓词键（排序去重）先取——
     * 非批量走共享锁，&gt;{@link #BULK_TIMELINES} 走排他并到此为止；否则时间线键
     * （排序去重）逐个排他。全部按键的固定顺序取锁——两个按序拿同样锁的事务不会
     * 互相等死。加入调用方事务（xact 级咨询锁随事务提交释放）。
     */
    public void lockTimelines(UUID kbId, List<Timeline> timelines) {
        if (timelines.isEmpty()) {
            return;
        }
        Set<String> predicates = new TreeSet<>();
        for (Timeline t : timelines) {
            predicates.add("predicate:" + kbId + ":" + t.predicateId());
        }
        boolean bulk = timelines.size() > BULK_TIMELINES;
        for (String key : predicates) {
            if (bulk) {
                mapper.lockExclusive(key);
            } else {
                mapper.lockPredicateShared(key);
            }
        }
        if (bulk) {
            return;
        }
        Set<String> keys = new TreeSet<>();
        for (Timeline t : timelines) {
            keys.add(t.lockKey(kbId));
        }
        for (String key : keys) {
            mapper.lockExclusive(key);
        }
    }

    /** temporal.rs:228-234 lock_timeline：一条。 */
    void lockTimeline(UUID kbId, Timeline timeline) {
        lockTimelines(kbId, List.of(timeline));
    }

    // ==== load_timeline（temporal.rs:289-315） ====

    /** temporal.rs:289-315：读档即锁行（FOR UPDATE OF f——改写与它互斥）。 */
    private List<TRow> loadTimeline(UUID kbId, Timeline timeline) {
        return timeline.side() == Side.SUBJECT_SIDE
                ? mapper.selectTimelineSubject(kbId, timeline.holder(), timeline.predicateId())
                : mapper.selectTimelineObject(kbId, timeline.holder(), timeline.predicateId());
    }

    // ==== timelines_of（temporal.rs:321-383） ====

    /** temporal.rs:321-383 无 swap 版：这些事实所在的全部唯一性时间线。 */
    public List<Timeline> timelinesOf(UUID kbId, List<UUID> factIds) {
        return timelinesOf(kbId, factIds, null, null);
    }

    /**
     * temporal.rs:321-383 {@code timelines_of}：放置查询→侧别推导→去重。
     * {@code swapFrom/swapTo}：主语或宾语是 swapFrom 里一员的事实，换成 swapTo 之后
     * 落在的那条也算上——撤回合并要在搬动任何一行之前，把搬走前、搬回后两边的锁都拿到。
     */
    public List<Timeline> timelinesOf(UUID kbId, List<UUID> factIds, List<UUID> swapFrom, UUID swapTo) {
        if (factIds == null || factIds.isEmpty()) {
            return List.of();
        }
        List<PlacedFact> placed = mapper.selectTimelinesOf(kbId, factIds);
        Set<Timeline> seen = new LinkedHashSet<>();
        for (PlacedFact p : placed) {
            boolean bothSides = p.functional() && p.inverseFunctional();
            if (p.functional()) {
                seen.add(new Timeline(p.subjectId(), p.predicateId(), Side.SUBJECT_SIDE, bothSides));
                if (swapTo != null && swapFrom != null && swapFrom.contains(p.subjectId())) {
                    seen.add(new Timeline(swapTo, p.predicateId(), Side.SUBJECT_SIDE, bothSides));
                }
            }
            // 宾语侧唯一性只对实体宾语有意义（字面值不「被占用」）
            if (p.inverseFunctional() && p.objectId() != null) {
                seen.add(new Timeline(p.objectId(), p.predicateId(), Side.OBJECT_SIDE, bothSides));
                if (swapTo != null && swapFrom != null && swapFrom.contains(p.objectId())) {
                    seen.add(new Timeline(swapTo, p.predicateId(), Side.OBJECT_SIDE, bothSides));
                }
            }
        }
        return new ArrayList<>(seen);
    }

    // ==== 对账入口（temporal.rs:395-434/755-765/781-850） ====

    /**
     * temporal.rs:395-434 {@code reconcile_new_fact}：一条 state 事实<b>被观察到</b>之后、
     * 沿指定唯一性方向的对账。新落库的要调，又被观察到的也要调（同一断言多了一份证据，
     * 那份证据的日期可能更早，时间线的形状跟着变），写完证据再调。时间、置信度从库里读，
     * validity/confidence 参数只用来决定对哪条时间线（保持 Rust 签名）。
     */
    public ReconcileReport reconcileNewFact(UUID kbId, UUID newFactId, UUID subjectId,
                                            UUID predicateId, UUID objectId,
                                            Side direction) {
        UUID holder;
        if (direction == Side.SUBJECT_SIDE) {
            holder = subjectId;
        } else if (objectId != null) {
            holder = objectId;
        } else {
            return new ReconcileReport();
        }
        Boolean both = mapper.selectBothSides(predicateId);
        Timeline timeline = new Timeline(holder, predicateId, direction, Boolean.TRUE.equals(both));
        ReconcileReport report = new ReconcileReport();
        // 一个事务里、持着这条时间线的咨询锁做完（temporal.rs:428-432 begin/commit 对等）
        newTx.executeWithoutResult(tx -> {
            lockTimeline(kbId, timeline);
            arrive(kbId, timeline, newFactId, List.of(), report);
            tidy(kbId, timeline, report);
        });
        return report;
    }

    /**
     * temporal.rs:443-472 {@code arrive}：一条事实来到它的时间线上，记下重算裁不了的，
     * 交给人（调用方已持锁）——与别的值同一时刻开始、两边那一刻都还成立（simultaneous）；
     * 两边都还开着、有一边说不出时间（no_time）。{@code settled}：同批已来过的事实不再重复成对。
     */
    void arrive(UUID kbId, Timeline timeline, UUID factId, List<UUID> settled,
                ReconcileReport report) {
        List<TRow> rows = loadTimeline(kbId, timeline);
        TRow now = null;
        for (TRow r : rows) {
            if (r.id.equals(factId)) {
                now = r;
                break;
            }
        }
        if (now == null) {
            return;
        }
        for (TRow other : rows) {
            if (other.id.equals(now.id) || TemporalMath.sameValue(timeline.side(), other, now)) {
                continue;
            }
            if (settled.contains(other.id)) {
                continue;
            }
            String reason;
            if (now.key() != null && other.key() != null && now.key().isEqual(other.key())
                    && now.holdsAt(now.key()) && other.holdsAt(other.key())) {
                reason = "simultaneous";
            } else if ((now.key() == null || other.key() == null) && now.isOpen() && other.isOpen()) {
                reason = "no_time";
            } else {
                continue;
            }
            if (recordConflictTx(kbId, other.id, now.id, reason)) {
                report.conflicts++;
            }
        }
    }

    /**
     * temporal.rs:558-606 {@code tidy}：把一条时间线重算到 desired_ends 说的样子
     * （调用方已持锁）。两侧都唯一的关系，每一行再按它另一侧的时间线推一次，
     * 取早的那个终点。重新打开的行只是开着——终点不是谁推出来的（derived = end != Open）。
     */
    void tidy(UUID kbId, Timeline timeline, ReconcileReport report) {
        List<TRow> rows = loadTimeline(kbId, timeline);
        var computed = TemporalMath.desiredEnds(timeline.side(), rows);
        Map<UUID, TEnd> ends = computed.getKey();
        List<EndPair> held = new ArrayList<>(computed.getValue());
        if (timeline.bothSides()) {
            Side other = timeline.side().other();
            // BTreeSet<Uuid>＝字节序迭代；重算一侧要读另一侧（双唯一一条谓词一把锁）
            TreeSet<UUID> holders = new TreeSet<>(Timeline.UUID_BYTES);
            for (TRow r : rows) {
                UUID h = r.holder(other);
                if (h != null) {
                    holders.add(h);
                }
            }
            for (UUID holder : holders) {
                Timeline across = new Timeline(holder, timeline.predicateId(), other, true);
                List<TRow> acrossRows = loadTimeline(kbId, across);
                var acrossComputed = TemporalMath.desiredEnds(other, acrossRows);
                for (Map.Entry<UUID, TEnd> e : acrossComputed.getKey().entrySet()) {
                    TEnd mine = ends.remove(e.getKey());
                    if (mine != null) {
                        ends.put(e.getKey(), mine.earlier(e.getValue()));
                    }
                }
                for (EndPair pair : acrossComputed.getValue()) {
                    if (ends.containsKey(pair.row())) {
                        held.add(pair);
                    }
                }
            }
        }
        Map<UUID, UUID> rewritten = new HashMap<>();
        for (Map.Entry<UUID, TEnd> e : TemporalMath.diff(rows, ends)) {
            // 重新打开的行只是开着，终点不是谁推出来的
            boolean derived = !e.getValue().equals(TEnd.open());
            UUID corrected = rewriteEndTx(e.getKey(), e.getValue(), derived, false);
            if (corrected != null) {
                rewritten.put(e.getKey(), corrected);
                report.corrected.add(corrected);
            }
        }
        for (EndPair pair : held) {
            UUID row = rewritten.getOrDefault(pair.row(), pair.row());
            UUID later = rewritten.getOrDefault(pair.later(), pair.later());
            if (recordConflictTx(kbId, row, later, "described_evidence")) {
                report.conflicts++;
            }
        }
    }

    /**
     * temporal.rs:755-765 {@code tidy_timelines_tx}（M2 挂账项，真身）：把几条时间线各自
     * 重算一遍（调用方已按 {@link #lockTimelines} 拿到锁）。撤回合并、删除或恢复文档在
     * 自己的事务里改完行之后调——加入当前事务，不另起炉灶。
     */
    public ReconcileReport tidyTimelinesTx(UUID kbId, List<Timeline> timelines) {
        ReconcileReport report = new ReconcileReport();
        for (Timeline timeline : timelines) {
            tidy(kbId, timeline, report);
        }
        return report;
    }

    /**
     * temporal.rs:770-776 {@code reconcile_moved_facts}：实体合并搬移事实后的对账——
     * 换了主/宾的事实等价于「新落库的观察」。人改过区间之后也走这里。修正行 id 由
     * 调用方记入合并账本，供审计。
     */
    public ReconcileReport reconcileMovedFacts(UUID kbId, List<UUID> factIds) {
        return reconcileFacts(kbId, factIds);
    }

    /**
     * temporal.rs:781-802 {@code reconcile_facts}：一批事实所在的每条时间线——批里的
     * 事实逐条「来到」时间线上（记下该交给人的），整条重算一遍。<b>一条时间线一个事务</b>
     * （与 Rust 逐线 begin/commit 对等，经事务模板实现）。物化把陈述算成类型化行之后
     * 也走这里（#899）。
     */
    public ReconcileReport reconcileFacts(UUID kbId, List<UUID> factIds) {
        ReconcileReport report = new ReconcileReport();
        Set<UUID> batch = new HashSet<>(factIds);
        for (Timeline timeline : timelinesOf(kbId, factIds)) {
            tidyAndArriveInOwnTx(kbId, timeline, batch, report);
        }
        return report;
    }

    /**
     * 一条时间线一个事务（temporal.rs:788-799 的 begin/commit 对等）：REQUIRES_NEW
     * 挂起外层、独立提交——与 Rust「pool.begin」同一形状（外层此时不该持有这条时间线
     * 的行锁，撤回合并/落库对账两边顺序一致）。
     */
    private void tidyAndArriveInOwnTx(UUID kbId, Timeline timeline, Set<UUID> batch,
                                      ReconcileReport report) {
        newTx.executeWithoutResult(tx -> {
            lockTimeline(kbId, timeline);
            List<TRow> rows = loadTimeline(kbId, timeline);
            List<TRow> arriving = new ArrayList<>();
            for (TRow r : rows) {
                if (batch.contains(r.id)) {
                    arriving.add(r);
                }
            }
            // 无起点的排后面；同一档位按 id（#679：结果与到达顺序无关）
            arriving.sort(java.util.Comparator
                    .comparing((TRow r) -> r.key() == null)
                    .thenComparing(TRow::key, java.util.Comparator.nullsLast(
                            java.util.Comparator.naturalOrder()))
                    .thenComparing(r -> r.id, Timeline.UUID_BYTES));
            for (int i = 0; i < arriving.size(); i++) {
                arrive(kbId, timeline, arriving.get(i).id,
                        arriving.subList(0, i).stream().map(r -> r.id).toList(), report);
            }
            tidy(kbId, timeline, report);
        });
    }

    /**
     * temporal.rs:812-850 {@code reconcile_predicate}：声明来晚了（#341）——一条谓词上
     * 所有现存事实所在的时间线重算一遍。走的是与落库时同一条路（作废+改写，supersedes
     * 链回旧行），所以记录轴倒回声明之前仍看得见三条开放的行；拿不准的照旧进人审，
     * 不硬闭合。没有声明的谓词拒绝：引擎不替人推断。非 state / 未声明唯一性 → INVALID。
     */
    public ReconcileReport reconcilePredicate(UUID kbId, UUID predicateId) {
        var declared = mapper.selectDeclared(kbId, predicateId);
        if (declared == null) {
            throw AppException.NOT_FOUND;
        }
        if (!"state".equals(declared.temporal())) {
            throw AppException.invalid("not_a_state",
                    "only a state relation has intervals to close");
        }
        if (!declared.functional() && !declared.inverseFunctional()) {
            throw AppException.invalid("not_unique",
                    "declare the relation functional or inverse-functional first; "
                            + "the engine does not infer it");
        }
        List<UUID> ids = mapper.selectPredicateFactIds(kbId, predicateId);
        return reconcileFacts(kbId, ids);
    }

    /**
     * temporal.rs:854-873 {@code retract}：撤掉一条事实（人判它是抽取错误）——它从来
     * 不在，时间线按剩下的行重算（关在它开始时的前任重新接上）。<b>先锁时间线再作废</b>
     * （见类注释）。返回有没有撤掉；已经作废的不算。
     */
    public boolean retract(UUID kbId, UUID factId) {
        return Boolean.TRUE.equals(newTx.execute(tx -> {
            List<Timeline> timelines = timelinesOf(kbId, List.of(factId));
            lockTimelines(kbId, timelines);
            boolean retracted = mapper.retractFact(factId, kbId) > 0;
            if (retracted) {
                tidyTimelinesTx(kbId, timelines);
            }
            return retracted;
        }));
    }

    // ==== 闭合三兄弟（temporal.rs:878-941） ====

    /**
     * temporal.rs:878-893 {@code close_superseded}：作废+改写成<b>写明的</b>终点
     * （闭合点截到它的精度，0024）——原文说它在哪天结束、人裁决把它关上，都走这里；
     * 这个终点此后不再由引擎重算。返回修正行 id；null = 这条已被作废，没动。
     */
    public UUID closeSuperseded(UUID factId, OffsetDateTime validTo, String validToPrecision) {
        TEnd end = TEnd.at(WorldTime.truncateTo(validTo, validToPrecision), validToPrecision);
        return newTx.execute(tx -> rewriteEndTx(factId, end, false, false));
    }

    /**
     * temporal.rs:900-910 {@code close_with_unknown_end}：作废+改写成「结束了，不知哪天」
     * （0022/#393）——修正行终点仍空、精度 unknown，attested_to 锚在说出结束的那份文档；
     * attested_from 从旧行继承（裸行靠它记着第一份证据）。这是原文写明的结束，引擎不重算。
     */
    public UUID closeWithUnknownEnd(UUID factId, OffsetDateTime attestedAt) {
        return newTx.execute(tx -> rewriteEndTx(factId, TEnd.unknown(attestedAt), false, true));
    }

    /**
     * temporal.rs:915-941 {@code state_derived_end}：原文说出了一条<b>引擎关上</b>的行的
     * 终点——改写成原文说的，此后不再重算（#679 第三轮评审）。validTo 为 null 是
     * 「结束了不知哪天」，锚在 attestedAt。行已作废，或它的终点本来就是写明的，返回 null。
     */
    public UUID stateDerivedEnd(UUID factId, OffsetDateTime validTo, String validToPrecision,
                                OffsetDateTime attestedAt) {
        TEnd end = validTo != null
                ? TEnd.at(WorldTime.truncateTo(validTo, validToPrecision), validToPrecision)
                : TEnd.unknown(attestedAt);
        return newTx.execute(tx -> {
            Boolean derived = mapper.selectEndDerivedForUpdate(factId);
            if (Boolean.TRUE.equals(derived)) {
                return rewriteEndTx(factId, end, false, false);
            }
            return null;
        });
    }

    // ---- 闭合三兄弟的「加入调用方事务」变体：类型化物化一轮重算是一个事务
    //      （materialize.rs:315/328/334 经 insert_fact_on 在同一 tx 里走到这里），
    //      闭合不能另起炉灶——否则外层回滚会留下已提交的半截改写 ----

    /** {@link #closeSuperseded} 的同事务版（供物化的落库门在调用方事务里调）。 */
    public UUID closeSupersededInTx(UUID factId, OffsetDateTime validTo, String validToPrecision) {
        TEnd end = TEnd.at(WorldTime.truncateTo(validTo, validToPrecision), validToPrecision);
        return rewriteEndTx(factId, end, false, false);
    }

    /** {@link #closeWithUnknownEnd} 的同事务版。 */
    public UUID closeWithUnknownEndInTx(UUID factId, OffsetDateTime attestedAt) {
        return rewriteEndTx(factId, TEnd.unknown(attestedAt), false, true);
    }

    /** {@link #stateDerivedEnd} 的同事务版。 */
    public UUID stateDerivedEndInTx(UUID factId, OffsetDateTime validTo, String validToPrecision,
                                    OffsetDateTime attestedAt) {
        TEnd end = validTo != null
                ? TEnd.at(WorldTime.truncateTo(validTo, validToPrecision), validToPrecision)
                : TEnd.unknown(attestedAt);
        Boolean derived = mapper.selectEndDerivedForUpdate(factId);
        if (Boolean.TRUE.equals(derived)) {
            return rewriteEndTx(factId, end, false, false);
        }
        return null;
    }

    /**
     * temporal.rs:949-1021 {@code rewrite_end_tx}：作废+改写一行的终点。
     * {@code derived}：这个终点是引擎按时间线推出来的（0057）；{@code requireOpen}：
     * 只改还开着的行。先 FOR UPDATE 锁旧行——并行的两次改写只有一次看得见它还活着，
     * 另一次拿到 null，不会各插一条修正行；往这一行上添证据的也要等它。还没人裁的
     * 冲突跟着换到修正行上（问题还在，只是这一行换了终点）。加入调用方事务。
     */
    UUID rewriteEndTx(UUID factId, TEnd end, boolean derived, boolean requireOpen) {
        var alive = mapper.selectForRewrite(factId, requireOpen);
        if (alive == null) {
            return null;
        }
        // 声明成状态的行关在它自己的起点，就是一段任何时刻都不成立的状态（#966，
        // 与 Validity::under 同一条不变量）。人关一条事实走这里；引擎只把一行关在
        // 严格更晚的起点上，写入路径也不拿开放行自己的起点来关它
        if (end.kind == TEnd.Kind.AT && alive.predicateId() != null
                && alive.validFrom() != null && alive.validFrom().isEqual(end.at)
                && predicateTemporal(alive.predicateId()) == Temporal.STATE) {
            throw WorldTime.emptyStateSpan();
        }
        OffsetDateTime validTo = null;
        String precision = null;
        OffsetDateTime anchor = null;
        switch (end.kind) {
            case OPEN -> {
            }
            case AT -> {
                validTo = end.at;
                precision = end.precision;
            }
            case UNKNOWN -> {
                precision = WorldTime.ENDED_UNKNOWN;
                anchor = end.anchor;
            }
        }
        UUID corrected = Uuids.nowV7();
        mapper.insertRewriteEnd(corrected, factId, validTo, precision, anchor, derived);
        carryOpenConflicts(factId, corrected);
        mapper.invalidateFact(factId);
        mapper.copyEvidence(corrected, factId);
        mapper.copyQualifiers(corrected, factId);
        mapper.copyTypedSources(corrected, factId);
        mapper.copyImpliedSources(corrected, factId);
        return corrected;
    }

    /** temporal.rs:1104-1130 {@code carry_open_conflicts}：还开着的冲突换到修正行上。 */
    private void carryOpenConflicts(UUID from, UUID to) {
        mapper.carryConflictsOld(from, to);
        mapper.carryConflictsNew(from, to);
    }

    /** graph.rs:434-445 {@code predicate_temporal}：没有谓词（0010）按状态。 */
    public Temporal predicateTemporal(UUID predicateId) {
        if (predicateId == null) {
            return Temporal.STATE;
        }
        String t = mapper.selectPredicateTemporal(predicateId);
        return t == null ? Temporal.STATE : Temporal.parse(t);
    }

    // ==== correct_interval（temporal.rs:1194-1267） ====

    /**
     * temporal.rs:1194-1267 {@code correct_interval}：人工修正一条事实的有效区间。
     * 与自动闭合同一机制（作废+改写），区别只在两端都来自参数。<b>不原地 UPDATE</b>——
     * 原地改会把修正本身抹掉，而那正是记录轴要回放的东西（0019）。人交来的是整段区间，
     * 改完两端都是人的（end_derived 清掉，时间线不再重画它）。corrected_ends 记哪端
     * 被改（#970）。返回修正行 id；null = 已被并发改写或作废，本次没有动手。
     */
    public UUID correctInterval(UUID factId, Validity validity) {
        // 人改区间也按谓词的时间语义归一（0031）：给一个事件填了一段，落下的仍是它的那一刻。
        // 两端相等的状态只对声明成状态的属性拒（#966）：开放陈述与空谓词的行按状态读只是读法
        UUID predicate = mapper.selectFactPredicateId(factId);
        Validity v = predicate != null
                ? validity.truncated().under(predicateTemporal(predicate))
                : validity.truncated();
        UUID corrected = Uuids.nowV7();
        return newTx.execute(tx -> {
            UUID inserted = mapper.insertCorrectInterval(corrected, factId, v.from, v.fromPrecision,
                    v.to, v.toPrecision);
            // 已被并发修正过：不重复动手（与 close_superseded 同一防线）——INSERT 未落行，
            // 事务里没有别的改动，返回 null 即可
            if (inserted == null) {
                return null;
            }
            mapper.invalidateFact(factId);
            mapper.copyEvidence(corrected, factId);
            mapper.copyQualifiers(corrected, factId);
            mapper.copyTypedSources(corrected, factId);
            mapper.copyImpliedSources(corrected, factId);
            return corrected;
        });
    }

    // ==== 冲突（temporal.rs:1271-1310/1357-1429） ====

    /**
     * temporal.rs:1271-1310 {@code record_conflict_tx}：记一对冲突。返回这一对是不是
     * 第一次记——时间线每次重算都会再看见同一对，已经在队列里（或人已裁过）的不再算
     * 新冲突。加入调用方事务。
     */
    boolean recordConflictTx(UUID kbId, UUID oldFactId, UUID newFactId, String reason) {
        return mapper.insertRecordConflict(Uuids.nowV7(), kbId, oldFactId, newFactId, reason) > 0;
    }

    /**
     * temporal.rs:1357-1429 {@code resolve_conflict}：人工裁决三出路——close（旧事实
     * 闭合于 closeAt 或新事实起点，精度跟着走）/ keep（kept_both）/ reject_new（新事实
     * 是抽取错误，retract 作废；同一新事实撞出的其他 open 冲突一并出队）。各步独立
     * 提交（与 Rust 一致：close/retract 各自的事务，裁决 UPDATE 自身一条）。
     */
    public void resolveConflict(UUID kbId, UUID conflictId, String resolution,
                                OffsetDateTime closeAt, String closeAtPrecision) {
        ConflictRow row = mapper.selectConflictRow(conflictId, kbId);
        if (row == null) {
            throw AppException.NOT_FOUND;
        }
        String stored;
        switch (resolution) {
            case "close" -> {
                // 闭合点带着它的精度走：人给了日期就用人给的精度，没给就闭合在新事实的
                // 起点——那个起点是几月还是几号，闭合点就是几月还是几号
                OffsetDateTime at;
                String precision;
                if (closeAt != null) {
                    at = closeAt;
                    precision = closeAtPrecision;
                } else if (row.newValidFrom() != null) {
                    at = row.newValidFrom();
                    precision = row.newValidFromPrecision() == null ? "day" : row.newValidFromPrecision();
                } else {
                    throw AppException.invalid("close_at_required",
                            "close_at is required when the new fact has no start time");
                }
                closeSuperseded(row.oldFactId(), at, precision);
                stored = "closed";
            }
            case "keep" -> stored = "kept_both";
            case "reject_new" -> {
                retract(kbId, row.newFactId());
                // 波及：同一新事实撞出的其他 open 冲突一并出队（新事实已死，无从裁起）
                mapper.resolveOtherOpenConflicts(row.newFactId(), conflictId);
                stored = "rejected_new";
            }
            default -> throw AppException.validation("Unknown resolution: " + resolution);
        }
        mapper.markConflictResolved(conflictId, kbId, stored);
    }

    // ==== uniqueness_candidates（temporal.rs:1435-1609） ====

    /** temporal.rs:1435-1471 候选形状（给人看的提议依据，不是判决）。 */
    public record UniquenessCandidate(UUID predicateId, String key, String label, String kind,
                                      String side, boolean declared, int holders, int openFacts,
                                      int wouldClose, int wouldReview,
                                      List<HolderExample> examples) {
    }

    public record HolderExample(String holder, List<TemporalMath.OpenValue> values) {
    }

    /** temporal.rs:1474 每个候选带几个例子。 */
    public static final int EXAMPLE_HOLDERS = 3;

    /**
     * temporal.rs:1476-1609 {@code uniqueness_candidates}：两端各查一遍；行已按
     * （谓词, 持有者, 年表）排好，顺着切段就是分组。would_close/would_review 由
     * {@link TemporalMath#planClosures} 干跑估算——真跑一遍的结果才作数。
     */
    public List<UniquenessCandidate> uniquenessCandidates(UUID kbId) {
        List<UniquenessCandidate> out = new ArrayList<>();
        for (String side : new String[]{"subject", "object"}) {
            List<CandidateRow> rows = side.equals("subject")
                    ? mapper.selectUniquenessSubjectSide(kbId)
                    : mapper.selectUniquenessObjectSide(kbId);
            int i = 0;
            while (i < rows.size()) {
                UUID pred = rows.get(i).predicateId();
                String key = rows.get(i).key();
                String label = rows.get(i).label();
                String kind = rows.get(i).kind();
                boolean declared = rows.get(i).declared();
                int holders = 0;
                int openFacts = 0;
                int wouldClose = 0;
                int wouldReview = 0;
                List<HolderExample> examples = new ArrayList<>();
                while (i < rows.size() && rows.get(i).predicateId().equals(pred)) {
                    String holder = rows.get(i).holderName();
                    List<TemporalMath.OpenValue> values = new ArrayList<>();
                    while (i < rows.size() && rows.get(i).predicateId().equals(pred)
                            && rows.get(i).holderName().equals(holder)) {
                        CandidateRow r = rows.get(i);
                        String name = r.otherName() != null ? r.otherName()
                                : r.objectValue() != null ? TemporalMath.literalName(r.objectValue())
                                : "?";
                        values.add(new TemporalMath.OpenValue(r.id(), name, r.validFrom(),
                                r.validFromGrade(), r.confidence()));
                        i++;
                    }
                    int[] plan = TemporalMath.planClosures(values);
                    holders++;
                    openFacts += values.size();
                    wouldClose += plan[0];
                    wouldReview += plan[1];
                    if (examples.size() < EXAMPLE_HOLDERS) {
                        examples.add(new HolderExample(holder, values));
                    }
                }
                out.add(new UniquenessCandidate(pred, key, label, kind, side, declared,
                        holders, openFacts, wouldClose, wouldReview, examples));
            }
        }
        return out;
    }

    // ==== merge_would_overlap（temporal.rs:616-712） ====

    /**
     * temporal.rs:616-712 {@code merge_would_overlap}：两个实体合成一个之后，哪些唯一性
     * 关系上<b>还会</b>有两个值同时成立（执行闸门，0027）。两边各有一个不同的值不等于
     * 矛盾：按引擎会怎么排（desired_ends）推出每一行的终点，再看不同的值有没有哪一刻
     * 叠在一起。只算合并带进来的冲突（一边是另一边的子集就跳过）。返回谓词标签，按标签排。
     */
    public List<String> mergeWouldOverlap(UUID kbId, UUID a, UUID b) {
        List<SharedPredicate> shared = mapper.selectSharedPredicates(kbId, a, b);
        List<String> labels = new ArrayList<>();
        for (SharedPredicate s : shared) {
            List<Side> sides = new ArrayList<>();
            if (s.functional()) {
                sides.add(Side.SUBJECT_SIDE);
            }
            if (s.inverseFunctional()) {
                sides.add(Side.OBJECT_SIDE);
            }
            for (Side side : sides) {
                List<TRow> rows = side == Side.SUBJECT_SIDE
                        ? mapper.selectMergeRowsSubject(kbId, List.of(a, b), s.predicateId())
                        : mapper.selectMergeRowsObject(kbId, List.of(a, b), s.predicateId());
                // 只算合并带进来的冲突：一边持有的值另一边全都已经持有，合起来还是那几个值
                Set<UUID> heldByA = new HashSet<>();
                Set<UUID> heldByB = new HashSet<>();
                for (TRow r : rows) {
                    if (side == Side.SUBJECT_SIDE && r.subjectId.equals(a) && r.objectId != null) {
                        heldByA.add(r.objectId);
                    }
                    if (side == Side.SUBJECT_SIDE && r.subjectId.equals(b) && r.objectId != null) {
                        heldByB.add(r.objectId);
                    }
                    if (side == Side.OBJECT_SIDE && a.equals(r.objectId)) {
                        heldByA.add(r.subjectId);
                    }
                    if (side == Side.OBJECT_SIDE && b.equals(r.objectId)) {
                        heldByB.add(r.subjectId);
                    }
                }
                if (heldByA.containsAll(heldByB) || heldByB.containsAll(heldByA)) {
                    continue;
                }
                // 合成一个之后的样子：持有者那一侧都是同一个实体
                for (TRow row : rows) {
                    if (side == Side.SUBJECT_SIDE) {
                        row.subjectId = a;
                    } else {
                        row.objectId = a;
                    }
                }
                if (TemporalMath.stillOverlaps(side, rows, "state".equals(s.temporal()))) {
                    labels.add(s.label());
                    break;
                }
            }
        }
        // Rust String Ord = UTF-8 字节序；Java String::compareTo 是 UTF-16 码元序，
        // 对齐用字节比较（标签多为 ASCII 时两者一致，非 ASCII 时只有字节序与 Rust 相同）
        labels.sort(java.util.Comparator.comparing(s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.util.Arrays::compare));
        labels = new ArrayList<>(new LinkedHashSet<>(labels));
        return labels;
    }

    // ==== rehome_tx（temporal.rs:1056-1100，撤回合并的活改写行搬回源实体） ====

    /**
     * temporal.rs:1056-1100 {@code rehome_tx}：把一条<b>活着的</b>改写行换到另一个持有者
     * 身上——作废 + 在新持有者上另起一行接着它（supersedes 链回旧行），证据/限定/
     * 物化链接随行复制。撤回合并用它把「合并之后才改写出来的行」搬回源实体：作废了的
     * 留在目标实体上——它们在那段窗口里确实挂在那里（0027 记录轴）。加入调用方事务。
     *
     * @return 修正行 id；null = 这行已经不活着，没动
     */
    public UUID rehomeTx(UUID factId, UUID subjectId, UUID objectId) {
        // 先 FOR UPDATE 锁行：并行的 rehome 只有一次看得见它活着
        UUID alive = mapper.selectAliveForUpdate(factId);
        if (alive == null) {
            return null;
        }
        UUID moved = Uuids.nowV7();
        mapper.insertRehome(moved, factId, subjectId, objectId);
        // 开着的冲突先换到新行上：旧行一作废，0051 的触发器就把它们撤下，之后重算也
        // 不会再记同一天开始的那一对——撤回完两行叠在一起，审核队列却是空的
        // （#679 第四轮评审）
        carryOpenConflicts(factId, moved);
        mapper.invalidateFact(factId);
        mapper.copyEvidence(moved, factId);
        mapper.copyQualifiers(moved, factId);
        mapper.copyTypedSources(moved, factId);
        mapper.copyImpliedSources(moved, factId);
        return moved;
    }

    // ==== reattest_tx（documents.rs:1020-1066） ====

    /**
     * documents.rs:1020-1066 {@code reattest_tx}：证据文档变了，把这些事实的见证按还在
     * 的证据重算（#679 第四轮评审）。三条 UPDATE 逐字（M2 自 DocumentsWriteMapper 迁入，
     * SQL 未动）：开放陈述重锚→类型化行抄陈述见证→其余行按活文档最早文档日期。
     * 加入调用方事务（删除/恢复文档路径）。
     */
    public void reattestTx(UUID kbId, List<UUID> factIds) {
        if (factIds.isEmpty()) {
            return;
        }
        mapper.reattestOpen(factIds);
        mapper.reattestTyped(kbId, factIds);
        mapper.reattestNonOpen(factIds);
    }
}
