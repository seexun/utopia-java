package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.mapper.FactsWriteMapper;
import tech.seexun.jknow.store.mapper.FactsWriteMapper.FactSpanRow;
import tech.seexun.jknow.store.mapper.FactsWriteMapper.QualifierRow;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;
import tech.seexun.jknow.store.temporal.WorldTime;
import tech.seexun.jknow.store.temporal.WorldTime.Temporal;

/**
 * 事实落库门（graph.rs:549-909 insert_fact_on 全门逐分支）——同断言查询→闭合分支族。
 * 抽取、点头、人写的事实都经过这一个门：按谓词的时间语义归一（0031），同断言的
 * 闭合/复用/并进/精化在这里判定，时间线的重算由调用方在写完证据后交给
 * {@link TemporalService#reconcileNewFact}。
 *
 * <p><b>为什么作废+改写而不是原地 UPDATE</b>：修正行链回旧行（supersedes），记录轴
 * 才能回放「当时我们怎么读的」（0019）；证据与边上的属性随行复制，改时间不丢话。
 *
 * <p><b>事务口径</b>：Rust 的门在一条连接上顺序执行（闭合走自己的小事务）；Java 侧
 * 用 {@link TransactionTemplate}（REQUIRES_NEW）包住整个门——落库原子化是 Java 侧的
 * 等价改写（tokio→Spring），门内各分支的可见语义与 Rust 逐字一致。
 */
@Service
public class GraphWriteService {

    /**
     * graph.rs:288 一次落库的产出：(事实 id, 是否新建, 新行 supersedes 列的指向)。
     * 第三字段是 M4 任务 7 为物化热路径加的：TypedMaterializeService 的来源搬家
     * （adopt/drop）读的正是这一列——由门在写入时带回，免去「执行一条注定 0 行的
     * JOIN/DELETE 语句」的往返。<b>等价性</b>：supersedeId 为 null ⟺ 该行 supersedes
     * 列为 NULL（主插入不 refine 时不写该列、复用/开放陈述同理）⟺ adopt 的
     * {@code JOIN ... ON src.fact_id = n.supersedes} 与 drop 的 {@code src.fact_id =
     * n.supersedes} 都匹配 0 行——跳过与执行同效（0 行、无锁）。修正行（闭合分支、
     * 引擎改写）的 supersedes 恒指向被改写的旧行（insertRewriteEnd 等写入），此处
     * 如实带回，物化的搬家语义与 Rust 逐条执行一致。
     */
    public record FactWrite(UUID id, boolean created, UUID supersedeId) {

        /** 无 supersedes 信息处的兼容形状（复用分支/开放陈述——该列必为 NULL）。 */
        public FactWrite(UUID id, boolean created) {
            this(id, created, null);
        }
    }

    /** graph.rs:99-104 QualifierWrite：Set 新写 / Same 已有同值 / Conflict 已有异值（不覆盖）。 */
    public enum QualifierWrite { SET, SAME, CONFLICT }

    /** graph.rs:191-199 FactQualifier（连定义与实体名之后的一行）。 */
    public record FactQualifier(UUID qualifierTypeId, String key, String label, JsonNode value,
                                UUID entityId, String entityName) {
    }

    private final FactsWriteMapper facts;
    private final TemporalService temporal;
    private final TransactionTemplate tx;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public GraphWriteService(FactsWriteMapper facts, TemporalService temporal,
                             PlatformTransactionManager txManager,
                             javax.sql.DataSource dataSource) {
        this.facts = facts;
        this.temporal = temporal;
        this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * graph.rs:1888-1905 update_entity（M5 任务 6 切流演练落位）：改了类型才标 human——
     * 这个端点也用来改名字，只改名不该顺手把类型的来源盖成人工。typeId null = 本次没
     * 提供类型（不是清空）。
     */
    public void updateEntity(UUID kbId, UUID entityId, UUID typeId, String newName) {
        jdbc.update("""
                UPDATE entities
                 SET type_id = COALESCE(?, type_id),
                     canonical_name = COALESCE(?, canonical_name),
                     type_source = CASE WHEN ?::uuid IS NULL THEN type_source ELSE 'human' END,
                     updated_at = now()
                 WHERE id = ? AND kb_id = ? AND merged_into IS NULL
                """, typeId, newName, typeId, entityId, kbId);
    }

    /** graph.rs:1875-1886：类型得是本库的。null = 不在。 */
    public Integer entityTypeExists(UUID kbId, UUID typeId) {
        return jdbc.queryForObject("SELECT count(*) FROM entity_types WHERE id = ? AND kb_id = ?",
                Integer.class, typeId, kbId) > 0 ? 1 : null;
    }

    /**
     * graph.rs:2189-2221 purge_graph（M5 任务 6 重建面落位）：清空整个图层。决策台账与
     * 裁决缓存刻意保留——重建放弃的是既有抽取，不是人的决定。返回 (实体数, 事实数)。
     */
    public long[] purgeGraph(UUID kbId) {
        return tx.execute(status -> {
            long entities = facts.countEntities(kbId);
            long factsRemoved = facts.countFacts(kbId);
            // FK 多为 CASCADE，但两处自引用是 NO ACTION：先解引用再删，顺序显式写出
            facts.deleteFactConflicts(kbId);
            facts.deleteResolutionReviews(kbId);
            facts.deleteEntityMerges(kbId);
            facts.clearSupersedes(kbId);
            facts.deleteFactEvidenceInKb(kbId);
            facts.deleteFactsInKb(kbId);
            facts.clearMergedInto(kbId);
            facts.deleteEntitiesInKb(kbId);
            facts.deleteOntologyMisses(kbId);
            return new long[]{entities, factsRemoved};
        });
    }

    /** graph.rs:288-306 insert_fact（实体宾语）。 */
    public FactWrite insertFact(UUID kbId, UUID subjectId, UUID predicateId, UUID objectId,
                                Validity validity, float confidence) {
        return insert(kbId, subjectId, predicateId, objectId, null, validity, confidence);
    }

    /** graph.rs:999-1016 insert_value_fact（值宾语——属性事实）。 */
    public FactWrite insertValueFact(UUID kbId, UUID subjectId, UUID predicateId,
                                     JsonNode objectValue, Validity validity, float confidence) {
        return insert(kbId, subjectId, predicateId, null, objectValue, validity, confidence);
    }

    /** graph.rs:596-909 insert_fact_on 门（objectId/objectValue 二选一）。 */
    private FactWrite insert(UUID kbId, UUID subjectId, UUID predicateId, UUID objectId,
                             JsonNode objectValue, Validity validity, float confidence) {
        return tx.execute(t -> insertOn(kbId, subjectId, predicateId, objectId, objectValue,
                validity, confidence));
    }

    /**
     * graph.rs:575-909 {@code insert_fact_on} 的「加入调用方事务」入口：Rust 的门收一个
     * tx、全程同一条连接（materialize.rs:317-352 的物化就这么调它）。Java 侧类型化物化
     * 一轮重算是一个事务（pg_advisory_xact_lock 在这条事务里），门内的闭合分支也必须
     * 留在同一事务里——这里走 TemporalService 的 InTx 变体，不再各开 REQUIRES_NEW。
     * 调用方必须已开着事务（TypedMaterializeService.materializeInTx）。
     */
    public FactWrite insertOnTx(UUID kbId, UUID subjectId, UUID predicateId, UUID objectId,
                                JsonNode objectValue, Validity validity, float confidence) {
        return insertOn(kbId, subjectId, predicateId, objectId, objectValue, validity,
                confidence, true);
    }

    private FactWrite insertOn(UUID kbId, UUID subjectId, UUID predicateId, UUID objectId,
                               JsonNode objectValue, Validity validity, float confidence) {
        return insertOn(kbId, subjectId, predicateId, objectId, objectValue, validity,
                confidence, false);
    }

    /** {@code inTx}：门内的闭合分支走 TemporalService 的 InTx 变体（加入当前事务）。 */
    private FactWrite insertOn(UUID kbId, UUID subjectId, UUID predicateId, UUID objectId,
                               JsonNode objectValue, Validity validity, float confidence,
                               boolean inTx) {
        boolean isValue = objectValue != null;
        // 按谓词的时间语义归一（0031）：事件两端同一刻，恒常无日期——写在门里，
        // 各个写入者就不用各自记一遍。声明成状态的属性才守「两端不相等」（#966）
        Temporal temporalKind = temporal.predicateTemporal(predicateId);
        boolean declaredState = predicateId != null && temporalKind == Temporal.STATE;
        Validity v = predicateId != null ? validity.truncated().under(temporalKind)
                : validity.truncated();
        List<FactSpanRow> same = isValue
                ? facts.selectSameValue(kbId, subjectId, predicateId, objectValue)
                : facts.selectSameEntity(kbId, subjectId, predicateId, objectId);
        // 状态属性下起止同值的行（#966 之前写下的）任何时刻都不成立：不关它、也不并进它
        if (declaredState) {
            same.removeIf(r -> r.validFrom() != null && r.validFrom().equals(r.validTo()));
        }
        // 「结束了，不知哪天」的观察撞上同断言的开放行（0022/#393）：关上它——并进去等于
        // 把「它结束了」这唯一带来的信息丢掉；也不另立一行让两条各说各话。已经关上的
        // （结束了不知哪天）再听到一次「结束了」：同一件事，复用那一行，锚点只往早挪；
        // 那一行的终点若是引擎推的，现在原文说出来了——改成写明的（#679 第三轮评审）
        if (v.to == null && WorldTime.ENDED_UNKNOWN.equals(v.toPrecision)) {
            FactSpanRow open = pickOpen(same, v);
            if (open != null) {
                UUID closed = inTx ? temporal.closeWithUnknownEndInTx(open.id(), v.attestedAt)
                        : temporal.closeWithUnknownEnd(open.id(), v.attestedAt);
                if (closed != null) {
                    return new FactWrite(closed, true, open.id());
                }
            }
            for (FactSpanRow r : same) {
                if (r.validTo() == null && WorldTime.ENDED_UNKNOWN.equals(r.validToPrecision())
                        && fromMatches(v, r)) {
                    UUID stated = inTx ? temporal.stateDerivedEndInTx(r.id(), null, null, v.attestedAt)
                            : temporal.stateDerivedEnd(r.id(), null, null, v.attestedAt);
                    if (stated != null) {
                        return new FactWrite(stated, true, r.id());
                    }
                    attestEarlier(r.id(), v.attestedAt, true);
                    return new FactWrite(r.id(), false);
                }
            }
        }
        // 「某天结束了」的观察（没起点、有终点）撞上同断言的开放行：关上它，不另立一行。
        // 终点说的是一个时段，比的却是时刻：开放行若从这个时段里开始，终点落在时段的
        // 尽头；在时段之前开始的照旧关在时段的开头（0053 修订）。同一天开始又结束的也是
        // 这样：关在那一对的尽头，不关在它自己的起点——那是一段不成立的状态（#966）
        if (temporalKind == Temporal.STATE && v.from == null && v.to != null) {
            String precision = v.toPrecision == null ? "day" : v.toPrecision;
            OffsetDateTime periodEnd = declaredState ? WorldTime.bucketEnd(v.to, precision) : null;
            for (FactSpanRow r : same) {
                if (equalsNullable(closesAt(r.validFrom(), v.to, periodEnd), r.validTo())) {
                    UUID stated = inTx
                            ? temporal.stateDerivedEndInTx(r.id(),
                                    closesAt(r.validFrom(), v.to, periodEnd), precision, v.attestedAt)
                            : temporal.stateDerivedEnd(r.id(),
                                    closesAt(r.validFrom(), v.to, periodEnd), precision, v.attestedAt);
                    if (stated != null) {
                        return new FactWrite(stated, true, r.id());
                    }
                    attestEarlier(r.id(), v.attestedAt, true);
                    return new FactWrite(r.id(), false);
                }
            }
            FactSpanRow open = null;
            for (FactSpanRow r : same) {
                if (r.validTo() != null || r.validToPrecision() != null) {
                    continue;
                }
                if (r.validFrom() == null || (periodEnd != null
                        ? r.validFrom().isBefore(periodEnd) : !r.validFrom().isAfter(v.to))) {
                    if (open == null || maxKey(r.validFrom(), open.validFrom()) > 0) {
                        open = r;
                    }
                }
            }
            if (open != null) {
                UUID closed = inTx
                        ? temporal.closeSupersededInTx(open.id(),
                                closesAt(open.validFrom(), v.to, periodEnd), precision)
                        : temporal.closeSuperseded(open.id(),
                                closesAt(open.validFrom(), v.to, periodEnd), precision);
                if (closed != null) {
                    return new FactWrite(closed, true, open.id());
                }
            }
        }
        // 精确重复：同 valid_from → 复用。同起点、这次带了终点、那行还开着 → 关上它
        // （「自 2020-01-10 起任董事」之后读到「2020-01-10 至 2024-04-30 任董事」）。
        // 那行已经关上，这次观察也说了终点：终点若是引擎推的，改成原文说的
        for (FactSpanRow r : same) {
            if (equalsNullable(r.validFrom(), v.from)) {
                if (temporalKind == Temporal.STATE && r.validTo() == null
                        && r.validToPrecision() == null && v.to != null) {
                    UUID closed = inTx
                            ? temporal.closeSupersededInTx(r.id(), v.to,
                                    v.toPrecision == null ? "day" : v.toPrecision)
                            : temporal.closeSuperseded(r.id(), v.to,
                                    v.toPrecision == null ? "day" : v.toPrecision);
                    if (closed != null) {
                        return new FactWrite(closed, true, r.id());
                    }
                }
                if (temporalKind == Temporal.STATE && (r.validTo() != null || r.validToPrecision() != null)
                        && v.hasEnded()) {
                    UUID stated = inTx
                            ? temporal.stateDerivedEndInTx(r.id(), v.to,
                                    v.toPrecision == null ? "day" : v.toPrecision, v.attestedAt)
                            : temporal.stateDerivedEnd(r.id(), v.to,
                                    v.toPrecision == null ? "day" : v.toPrecision, v.attestedAt);
                    if (stated != null) {
                        return new FactWrite(stated, true, r.id());
                    }
                }
                attestEarlier(r.id(), v.attestedAt, v.hasEnded());
                return new FactWrite(r.id(), false);
            }
        }
        // 弱化陈述：新观察无时间，同断言已有开放行 → 并入（取起点最新的开放行）。事件没有
        // 「开放」一说——没日期的再观察并进已有的那一刻（0031）
        if (v.from == null && !v.hasEnded()) {
            FactSpanRow pick = null;
            for (FactSpanRow r : same) {
                if (r.validTo() == null || temporalKind == Temporal.EVENT) {
                    if (pick == null || maxKey(r.validFrom(), pick.validFrom()) > 0) {
                        pick = r;
                    }
                }
            }
            if (pick != null) {
                attestEarlier(pick.id(), v.attestedAt, false);
                return new FactWrite(pick.id(), false);
            }
            // 没有开放行，但这次观察的文档日期落在某条已关上的行里：说的是那一段，不是新的
            // 一段。文档日期在段之后的照旧另立：那可能真是新的一段，拿不准时宁分勿合
            if (v.attestedAt != null) {
                for (FactSpanRow r : same) {
                    if (r.validTo() != null && !v.attestedAt.isAfter(r.validTo())
                            && (r.validFrom() == null || !r.validFrom().isAfter(v.attestedAt))) {
                        attestEarlier(r.id(), v.attestedAt, false);
                        return new FactWrite(r.id(), false);
                    }
                }
            }
        }
        // 时间精化候选：已有无起点的行（裸行，或只知道终点的行——并行抽取时说结束的那份
        // 文档可能先到），本次观察带了起点 → 落库后作废那行并链上。只知道终点的行，
        // 终点跟着走：这次没说终点就沿用它的，说了就得是同一个
        UUID refineTarget = null;
        OffsetDateTime refineEnd = null;
        String refineEndPrecision = null;
        if (v.from != null) {
            for (FactSpanRow r : same) {
                if (r.validFrom() != null) {
                    continue;
                }
                OffsetDateTime end;
                if (v.to == null && r.validTo() != null && declaredState
                        && !r.validTo().isAfter(v.from)) {
                    OffsetDateTime bucketEnd = WorldTime.bucketEnd(r.validTo(),
                            r.validToPrecision() == null ? "day" : r.validToPrecision());
                    if (!v.from.isBefore(bucketEnd)) {
                        continue;
                    }
                    end = bucketEnd;
                } else if (v.to != null && r.validTo() != null && !v.to.isEqual(r.validTo())) {
                    continue;
                } else {
                    end = r.validTo();
                }
                refineTarget = r.id();
                refineEnd = end;
                refineEndPrecision = r.validToPrecision();
                break;
            }
        }
        Validity out = v;
        if (refineTarget != null && refineEnd != null && out.to == null) {
            // graph.rs:820-829 vtp.as_deref().map(...)：旧行没写精度就落 NULL——
            // 'unknown' 不是粒度，只在旧行确实带着 unknown 精度时沿用
            String p = refineEndPrecision == null ? null
                    : switch (refineEndPrecision) {
                case "year", "month", "day", "hour", "minute", "second" -> refineEndPrecision;
                default -> WorldTime.ENDED_UNKNOWN;
            };
            out = new Validity(out.from, out.fromPrecision, refineEnd, p, out.attestedAt,
                    out.fromGrade);
        }
        UUID id = Uuids.nowV7();
        if (isValue) {
            facts.insertFactValue(id, kbId, subjectId, predicateId, objectValue, out.from,
                    out.fromPrecision, out.to, out.toPrecision, confidence, out.attestedAt,
                    out.fromGrade);
        } else {
            facts.insertFactEntity(id, kbId, subjectId, predicateId, objectId, out.from,
                    out.fromPrecision, out.to, out.toPrecision, confidence, out.attestedAt,
                    out.fromGrade);
        }
        // 时间精化：裸行（无时无终的同断言）被本次带时间的观察取代——作废+链上，
        // 证据（表层谓词随证据一起搬：精化的是时间，不是原文说了什么）与属性随行
        if (refineTarget != null) {
            if (inTx) {
                facts.invalidateFact(refineTarget);
            } else {
                // Rust 的 insert_fact 走池的自动提交：作废与插入各是一个事务、now() 不同
                // （µs 级）——0096 迁移正是靠这一差区分「引擎的改写」（同事务，链有效）
                // 与「另一次观察的时间精化」（不同事务，不 adopt）。Java 侧独立写入口
                // 的整体原子化（任务 3 等价改写）会把两个 now() 抹成同一个，这里把作废
                // 放进它自己的小事务，恢复 Rust 的可区分性；物化（inTx）在调用方事务里，
                // 与 Rust 的 insert_fact_on 同一事务语义
                final UUID retired = refineTarget;
                tx.executeWithoutResult(t -> facts.invalidateFact(retired));
            }
            facts.setSupersedes(id, refineTarget);
            facts.copyEvidenceFrom(id, refineTarget);
            facts.copyQualifiersFrom(id, refineTarget);
        }
        return new FactWrite(id, true, refineTarget);
    }

    /**
     * graph.rs:899-918 {@code attest_earlier}：同一断言又被观察到一次，锚点只往早挪——
     * 更早的文档是更早的证据；更晚的什么也不改。ended：这次说的是「它结束了」，
     * 只有它是结束得更早的证据，终点锚才跟着挪（#875）。
     */
    public void attestEarlier(UUID factId, OffsetDateTime at, boolean ended) {
        if (at != null) {
            facts.attestEarlier(factId, at, ended);
        }
    }

    /**
     * graph.rs:1030-1043 attested_at 匹配（Rust {@code validity.from.is_none_or(|f| Some(f) == *vf)}）：
     * 观察没给起点就匹配；给了就与行的起点相等才匹配。
     */
    private static boolean fromMatches(Validity v, FactSpanRow r) {
        return v.from == null || v.from.equals(r.validFrom());
    }

    /** graph.rs:655-658 pick_open：开着、且（观察没给起点或起点与行一致的）里起点最新的。 */
    private FactSpanRow pickOpen(List<FactSpanRow> same, Validity v) {
        FactSpanRow open = null;
        for (FactSpanRow r : same) {
            if (r.validTo() != null || r.validToPrecision() != null || !fromMatches(v, r)) {
                continue;
            }
            if (open == null || maxKey(r.validFrom(), open.validFrom()) > 0) {
                open = r;
            }
        }
        return open;
    }

    /** Rust max_by_key(Option&lt;DateTime&gt;)：None &lt; Some；返回 &gt;0 表示 a 更大。 */
    private static int maxKey(OffsetDateTime a, OffsetDateTime b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        return a.isAfter(b) ? 1 : a.isEqual(b) ? 0 : -1;
    }

    private static boolean equalsNullable(OffsetDateTime a, OffsetDateTime b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.isEqual(b);
    }

    /**
     * graph.rs:660-663 closes_at：开放行若从时段里开始（to &lt;= from &lt; period_end），
     * 终点落在时段的尽头；否则关在 to。
     */
    private static OffsetDateTime closesAt(OffsetDateTime from, OffsetDateTime to,
                                           OffsetDateTime periodEnd) {
        if (from != null && periodEnd != null && !to.isAfter(from) && from.isBefore(periodEnd)) {
            return periodEnd;
        }
        return to;
    }

    // ==== add_evidence（graph.rs:1167-1230） ====

    /**
     * graph.rs:1167-1172 add_evidence：不带定位。证据落在活着的那一行上——改写期间
     * 顺着 supersedes 走到修正行（#679 第三轮评审）。
     */
    public void addEvidence(UUID factId, UUID chunkId, String quote, String proposed) {
        addEvidenceLocated(factId, chunkId, quote, proposed, null);
    }

    /** graph.rs:1182-1230 add_evidence_located：span 为 (start, end) 字符偏移，服务端算的。 */
    public void addEvidenceLocated(UUID factId, UUID chunkId, String quote, String proposed,
                                   int[] span) {
        tx.executeWithoutResult(t -> {
            UUID target = factId;
            while (true) {
                Boolean live = facts.selectLiveForShare(target);
                if (!Boolean.FALSE.equals(live)) {
                    break;
                }
                UUID next = facts.selectSuccessor(target);
                if (next == null) {
                    break;
                }
                target = next;
            }
            facts.insertEvidence(target, chunkId, quote, proposed,
                    span != null ? span[0] : null, span != null ? span[1] : null);
        });
    }

    // ==== 边上的属性（graph.rs:125-154/181-213） ====

    /**
     * graph.rs:125-154 {@code upsert_fact_qualifier}：往一条边上写一个字面值属性——
     * 属性不进事实的去重键。两个属性值是不是同一个：数按数比（65 与 65.0 同一个数），
     * 其余按结构比。已经有了、值不一样 → CONFLICT，不覆盖（先写者留着）。
     */
    public QualifierWrite upsertFactQualifier(UUID factId, UUID qualifierTypeId, JsonNode value) {
        JsonNode existing = facts.selectQualifierValue(factId, qualifierTypeId);
        if (existing != null) {
            return qualifierValuesAgree(existing, value) ? QualifierWrite.SAME : QualifierWrite.CONFLICT;
        }
        facts.insertQualifier(factId, qualifierTypeId, value);
        return QualifierWrite.SET;
    }

    /**
     * graph.rs:107-116 qualifier_values_agree。数按数比（as_f64——65 与 65.0 同一个数），
     * 其余按结构比。
     */
    static boolean qualifierValuesAgree(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.asDouble() == b.asDouble();
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (var it = a.fields(); it.hasNext(); ) {
                var e = it.next();
                JsonNode w = b.get(e.getKey());
                if (w == null || !qualifierValuesAgree(e.getValue(), w)) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!qualifierValuesAgree(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    /** graph.rs:181-213 {@code fact_qualifiers_for}：一批事实各自带的属性，按事实 id 取回。 */
    public Map<UUID, List<FactQualifier>> factQualifiersFor(List<UUID> factIds) {
        Map<UUID, List<FactQualifier>> out = new HashMap<>();
        if (factIds.isEmpty()) {
            return out;
        }
        for (QualifierRow r : facts.selectQualifiersFor(factIds)) {
            out.computeIfAbsent(r.factId(), k -> new ArrayList<>())
                    .add(new FactQualifier(r.qualifierTypeId(), r.key(), r.label(), r.value(),
                            r.entityId(), r.entityName()));
        }
        return out;
    }

    // ==== 开放陈述（graph.rs:1022-1095） ====

    /**
     * graph.rs:1022-1095 {@code insert_open_statement}：一条开放陈述（0044 第一刀）——
     * 文档说了什么，用它自己的话。不走 insert_fact_on 那道门：不问时间语义、不写任何
     * valid_*、不碰时间线；一条开放陈述在世界轴上还没有位置。去重按
     * (库, 主语, 短语, 宾语)；撞上了只把锚点往早挪（两个锚点一起挪——落库时还不知道
     * 这次提及说的是成立还是结束，0045 的解算在那之后）。
     */
    public FactWrite insertOpenStatement(UUID kbId, UUID subjectId, String phrase, UUID objectId,
                                         JsonNode objectValue, OffsetDateTime attestedAt,
                                         float confidence) {
        String trimmed = phrase == null ? "" : phrase.trim();
        if (trimmed.isEmpty()) {
            throw AppException.invalid("phrase_missing",
                    "an open statement keeps the document's relation phrase");
        }
        boolean isValue = objectValue != null;
        UUID same = isValue
                ? facts.selectOpenStatementValue(kbId, subjectId, trimmed, objectValue)
                : facts.selectOpenStatementEntity(kbId, subjectId, trimmed, objectId);
        if (same != null) {
            attestEarlier(same, attestedAt, true);
            return new FactWrite(same, false);
        }
        UUID id = Uuids.nowV7();
        if (isValue) {
            facts.insertOpenStatementValue(id, kbId, subjectId, trimmed, objectValue, confidence,
                    attestedAt);
        } else {
            facts.insertOpenStatementEntity(id, kbId, subjectId, trimmed, objectId, confidence,
                    attestedAt);
        }
        return new FactWrite(id, true);
    }

    // ==== 开放陈述的时间词落轴（graph.rs:1125-1162） ====

    /**
     * graph.rs:1125-1162 {@code set_open_validity}：时间解析（0045）把一句话里的时间词
     * 落到轴上——只动活着的那条开放陈述。锚不到（grade {@code C}）也要写：那时两端
     * 都是空，只有这一列说得出「这句话有时间词、我们没能把它放到轴上」，物化把它带给
     * 类型化的行，时态引擎才不会让一个猜出来的位置改写前任的历史。没命中活陈述 →
     * not_an_open_statement。
     */
    public void setOpenValidity(UUID factId, OffsetDateTime from, String fromPrecision,
                                OffsetDateTime to, String toPrecision, String grade) {
        OffsetDateTime fromTrunc = from == null ? null
                : WorldTime.truncateTo(from, fromPrecision);
        OffsetDateTime toTrunc = to == null ? null : WorldTime.truncateTo(to, toPrecision);
        int done = facts.setOpenValidity(factId, fromTrunc, fromPrecision, toTrunc, toPrecision,
                grade);
        if (done == 0) {
            throw AppException.invalid("not_an_open_statement",
                    "only a live open statement takes its valid time from resolution");
        }
    }

    // ==== 陈述的见证（graph.rs:949-965） ====

    /**
     * graph.rs:949-965 {@code attest_statement}：一条陈述的见证——它所在那一节里
     * 文本说话的那一刻，连同那条日期的名字（0064 决定 3）。只往早挪（同
     * {@link #attestEarlier}）：同一句话在更早的文档里说过，见证就是更早的那份；
     * 名字跟着日期走——日期没动，名字也不动。物化出来的、规则算出来的类型化行
     * 自己没有出处，跟着陈述走（{@code sync_typed_attestation}，任务 7 物化落地）。
     *
     * @return 这次是否挪动（Rust {@code moved.rows_affected() > 0}）
     */

    /** graph.rs:975-992 witness_evidence（服务层壳；无见证传 null/null）。 */
    public void witnessEvidence(UUID factId, UUID chunkId, OffsetDateTime at, String by) {
        facts.witnessEvidence(factId, chunkId, at, by);
    }

    public boolean attestStatement(UUID factId, OffsetDateTime at, String by) {
        return facts.attestStatement(factId, at, by) > 0;
    }

    // ==== 开放陈述的角色词属性（graph.rs:216-283） ====

    /**
     * graph.rs:216-243 {@code add_statement_qualifier}：往一条开放陈述上按文档的
     * 角色词记一个属性（"amount"→"$1.2 billion"、"to"→某实体）。值与实体必须恰好
     * 一个——两个都没有是没记到东西，两个都有是说两件事。先写者留着：同一角色
     * 再听到一次不同值，不覆盖（ON CONFLICT DO NOTHING）。
     */
    public void addStatementQualifier(UUID factId, String role, JsonNode value, UUID entityId) {
        if ((value != null) == (entityId != null)) {
            throw AppException.invalid("qualifier_shape",
                    "a statement qualifier carries exactly one of a value or an entity");
        }
        String trimmed = role == null ? "" : role.trim();
        if (trimmed.isEmpty()) {
            throw AppException.invalid("qualifier_role",
                    "a statement qualifier needs the document's role word");
        }
        facts.insertStatementQualifier(factId, trimmed, value, entityId);
    }

    /**
     * graph.rs:250-283 {@code statement_qualifiers_for}：一批开放陈述各自的角色词
     * 属性，按事实 id 取回（实体那一格连上显示名），按 (fact_id, role) 排序。
     */
    public Map<UUID, List<FactsWriteMapper.StatementQualifierRow>> statementQualifiersFor(
            List<UUID> factIds) {
        Map<UUID, List<FactsWriteMapper.StatementQualifierRow>> out = new HashMap<>();
        if (factIds.isEmpty()) {
            return out;
        }
        for (FactsWriteMapper.StatementQualifierRow r : facts.selectStatementQualifiersFor(factIds)) {
            out.computeIfAbsent(r.factId(), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    // ==== confirm / reject（graph.rs:2041-2064） ====

    /** graph.rs:2059-2064 reject_fact：人判它是抽取错误——撤掉，时间线按剩下的行重算。 */
    public void rejectFact(UUID kbId, UUID factId) {
        if (!temporal.retract(kbId, factId)) {
            throw AppException.NOT_FOUND;
        }
    }

    /**
     * graph.rs:2042-2056 {@code confirm_fact}：人工确认低置信事实——置信度提到 1.0。
     * 0 行（不存在/别的库/已作废）→ NotFound。
     */
    public void confirmFact(UUID kbId, UUID factId) {
        if (facts.confirmFact(kbId, factId) == 0) {
            throw AppException.NOT_FOUND;
        }
    }
}
