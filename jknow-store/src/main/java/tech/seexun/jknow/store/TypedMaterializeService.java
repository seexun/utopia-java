package tech.seexun.jknow.store;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.store.mapper.MaterializeMapper;
import tech.seexun.jknow.store.mapper.MaterializeMapper.DueRow;
import tech.seexun.jknow.store.mapper.MaterializeMapper.ImpliedRow;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 类型化物化（materialize.rs 656 行对等）：绑上的签名下的开放陈述算成类型化事实
 * （0044 决定 3 的第三、四片）。类型化图谱是<b>视图</b>——一轮重算是集合运算，跑多少遍
 * 结果一样：先删「不再成立」的来源，再作废来源全空的类型化行（<b>人写的不碰</b>——
 * 只认 from_statement_id IS NOT NULL OR implied 的算出来的行），最后给「该有而没有」的
 * （陈述, 绑定）对补上。没有模型调用。
 *
 * <p><b>为什么写下的行随后要对账（#899）</b>：写路径上抽取和点头写完一条 state 事实
 * 都会沿它的唯一性方向重算时间线，物化出来的行是同一种观察，不该少这一步——否则一个
 * 函数型属性的两个值各开着一段，后一次观察关不上前一次。对账在<b>提交之后</b>：按
 * 时间线各自开事务、拿自己的锁，不在物化的事务里做。
 *
 * <p><b>事务口径</b>：Rust 的 materialize/try_materialize 各开一个 tx、锁
 * {@code pg_advisory_xact_lock(hashtext('typed_materialize'), hashtext(kb))} 在这条 tx 里，
 * materialize_in_tx 全程同一条连接（含经 insert_fact_on 的闭合分支）。Java 侧用
 * {@link TransactionTemplate}（REQUIRES_NEW）包住整个重算，落库门走
 * {@link GraphWriteService#insertOnTx}（加入当前事务），语义与 Rust 逐字一致。
 *
 * <p><b>热路径形态（M4 任务 7，审查 C-1 收窄后）</b>：每条 due 约 6-9 条逐条 SQL
 * （与 Rust materialize.rs:280-415 同构），瓶颈是每条 SQL 一次网络往返。收敛项只有
 * statement_qualifiers 的抄写攒批一次往返（循环内确无读者，见 {@link #materializeInTx}
 * 步骤 3 注释的等价性论证）与 adopt/drop 的零行跳过（{@code GraphWriteService.FactWrite}
 * 带回 supersedes 列，null 时这两条必 0 行）。setFromStatement 与证据抄写曾随本任务
 * 攒批，审查用回滚事务实证终态分歧后恢复逐条——门的 refine 分支读 fact_evidence 搬
 * 被取代行的证据，闭合分支的改写行携带旧行 from_statement_id，两者都要求同轮更早
 * due 的写入即时可见。曾评估并放弃：predicateTemporal 跨 due 缓存（Rust 每条 due 重读
 * 最新已提交，缓存改变事务内可见性窗口）、六条全量攒批到循环末（裸行 refine 链上后续
 * due 的 adopt 要搬前面 due 刚挂上的来源，延迟落库改变可见序）。
 */
@Service
public class TypedMaterializeService {

    /** materialize.rs:47-62 Outcome：一轮重算写了什么（corrected/conflicts 由对账回填）。 */
    public record Outcome(long retired, long added, long merged, long implied, long corrected,
                          int conflicts) {

        public static final Outcome EMPTY = new Outcome(0, 0, 0, 0, 0, 0);

        @Override
        public String toString() {
            return "Outcome{retired=" + retired + ", added=" + added + ", merged=" + merged
                    + ", implied=" + implied + ", corrected=" + corrected + ", conflicts="
                    + conflicts + "}";
        }
    }

    private final MaterializeMapper mapper;
    private final GraphWriteService writes;
    private final TemporalService temporal;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    public TypedMaterializeService(MaterializeMapper mapper, GraphWriteService writes,
                                   TemporalService temporal, PlatformTransactionManager txManager,
                                   JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.writes = writes;
        this.temporal = temporal;
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * materialize.rs:90-102 {@code materialize}：对一个库重算一遍（等锁）。Worker 走这一条：
     * 等多久都行，因为它没人在屏幕前面等。锁拿到后整轮重算用这条事务做完再提交，
     * 提交后对账（reconcile_written → temporal.reconcileFacts，#899）。
     */
    public Outcome materialize(UUID kbId) {
        InTx out = tx.execute(t -> {
            mapper.lockMaterialize(kbId.toString());
            return materializeInTx(kbId);
        });
        return reconcileWritten(kbId, out.outcome(), out.written());
    }

    /**
     * materialize.rs:128-145 {@code try_materialize}（任务里的入口，0051）：<b>试锁，不等</b>。
     * 拿到 {@code typed_materialize} 就在这条事务上跑完整的重算并提交；拿不到就结束、
     * 返回 null，由调用方挂成 {@code Deferred} 稍后再来。为什么不像 {@link #materialize}
     * 那样等锁：等锁的是一条池里的连接，几个决定连着点下来，每个 job 都抱着一条连接排队，
     * 池就空了（0051 §Alternatives）。
     */
    public Outcome tryMaterialize(UUID kbId) {
        InTx out = tx.execute(t -> {
            Boolean acquired = mapper.tryLockMaterialize(kbId.toString());
            if (!Boolean.TRUE.equals(acquired)) {
                return null;
            }
            return materializeInTx(kbId);
        });
        if (out == null) {
            return null;
        }
        return reconcileWritten(kbId, out.outcome(), out.written());
    }

    /**
     * materialize.rs:104-118 {@code reconcile_written}：这一轮写下（新建或并入）的类型化行
     * 沿各自的唯一性时间线对账（#899）。只有 state 且声明了唯一性的谓词有时间线，
     * {@code timelines_of} 自己筛；其余的行这里是空转。
     */
    private Outcome reconcileWritten(UUID kbId, Outcome outcome, List<UUID> written) {
        if (!written.isEmpty()) {
            TemporalService.ReconcileReport report = temporal.reconcileFacts(kbId, written);
            return new Outcome(outcome.retired(), outcome.added(), outcome.merged(),
                    outcome.implied(), report.corrected.size(), report.conflicts);
        }
        return outcome;
    }

    /** materialize_in_tx 的产出：(Outcome, 这一轮写下的行——提交后对账用)。 */
    private record InTx(Outcome outcome, List<UUID> written) {
    }

    /**
     * materialize.rs:147-435 {@code materialize_in_tx} 步骤 0-3b 全序（调用方已持锁开事务）。
     */
    private InTx materializeInTx(UUID kbId) {
        List<UUID> written = new ArrayList<>();
        // 0. 起止同值的物化状态行（#966 之前照抄一刻写下的）任何时刻都不成立：作废，它的
        //    陈述在第 3 步按绑定说的读法重算。只认照抄来的（from_statement_id 或 implied，
        //    且来源陈述两端正是这一刻）；人改成起止相等的行带着同样的链接，但它的来源陈述
        //    不是这一刻，不碰——不变量从此挡住新的，旧的由人自己改。排在第 1 步之前：
        //    那一刻的陈述在绑定说出读法之前不算来源，第 1 步会先删掉这条链接
        int emptied = mapper.retireMomentStateRows(kbId);

        // 1. 删不再成立的来源：陈述死了、行死了、签名没绑着、属性或方向变了、陈述带了 mood
        mapper.deleteBrokenTypedSources(kbId);
        // 1b. 隐含行的来源：规则不再批准、触发它的陈述死了或换了签名、实体没了或换了类别词
        mapper.deleteBrokenImpliedSources(kbId);

        // 2. 作废来源全空的类型化行：只动算出来的行（带 from_statement_id 的、或规则算的），
        //    人写的不碰
        long retired = mapper.retireOrphanTypedRows(kbId) + emptied;

        // 3. 该有而没有的（陈述, 绑定）对逐条经 insert_fact_on 落库（同一事务）
        // 【M4 任务 7 热路径收敛（审查 C-1 后的收窄形态）】仅 statement_qualifiers 的
        // 抄写在循环里攒批、循环后一次 flush（JDBC batch，pgjdbc 默认
        // reWriteBatchedInserts=false——只是把多条 Bind/Execute 打包成一次网络往返，
        // 服务端仍逐条顺序执行）。它写的列在本轮循环内确无读者：due 查询（mood 检查）
        // 在循环前已取完，门搬限定走另一张表 fact_qualifiers（copyQualifiersFrom），
        // temporal 改写也不碰 statement_qualifiers；批内按 due 序执行（ON CONFLICT
        // DO NOTHING 的跳过次序与逐条一致），步骤 3a 前全部落库——与 Rust 逐条同终态。
        // setFromStatement 与 copyEvidenceFromStatement 曾随本任务攒批、审查 C-1 用回滚
        // 事务实证终态分歧后<b>恢复逐条</b>：门的 refine 分支读 fact_evidence
        // （copyEvidenceFrom 搬被取代行的证据）——同轮更早 due 的证据攒着未落库时，
        // refine 的新行搬不到它，先到陈述的证据丢失；闭合分支的改写行又携带旧行的
        // from_statement_id（insertRewriteEnd 照抄该列）——同轮双陈述时旧行的 setFrom
        // 未落会让修正行带着 NULL 出门、再被攒批覆写，与 Rust 逐条序的「第一条陈述」
        // 终态分歧。adopt/drop/insertTypedSource 三条始终逐条：typed_fact_sources 有
        // 跨 due 的链式可见性（后一条 due 的 adopt 要搬前一条刚挂上的来源，裸行 refine 链）。
        List<Object[]> qualifiersBatch = new ArrayList<>();
        long added = 0;
        long merged = 0;
        for (DueRow d : mapper.selectDue(kbId)) {
            boolean reverse = "reverse".equals(d.direction());
            // 一刻的陈述在状态属性下按绑定说的读（#966）：开始写成从那一刻起，结束写成到
            // 那一刻为止（走 insert_fact_on「某天结束了」那条路，关上开放的那段）。什么都
            // 不算的在查询里已经排掉了（MOMENT_UNREAD）
            boolean moment = "state".equals(d.temporal()) && d.validFrom() != null
                    && d.validTo() != null && d.validFrom().isEqual(d.validTo());
            Validity validity;
            if (!moment) {
                validity = new Validity(d.validFrom(), d.validFromPrecision(), d.validTo(),
                        d.validToPrecision(), d.attestedFrom(), d.validFromGrade());
            } else if ("start".equals(d.marks())) {
                validity = new Validity(d.validFrom(), d.validFromPrecision(), null, null,
                        d.attestedFrom(), d.validFromGrade());
            } else if ("end".equals(d.marks())) {
                validity = new Validity(null, null, d.validFrom(), d.validFromPrecision(),
                        d.attestedFrom(), null);
            } else {
                continue;
            }
            // reverse 只对两样东西之间的关系有意义（字面值当不了主语）
            GraphWriteService.FactWrite write;
            if (reverse && d.objectId() != null) {
                write = writes.insertOnTx(kbId, d.objectId(), d.property(), d.subjectId(),
                        null, validity, d.confidence());
            } else if (!reverse && d.objectId() != null) {
                write = writes.insertOnTx(kbId, d.subjectId(), d.property(), d.objectId(),
                        null, validity, d.confidence());
            } else if (!reverse && d.objectId() == null && d.objectValue() != null) {
                write = writes.insertOnTx(kbId, d.subjectId(), d.property(), null,
                        d.objectValue(), validity, d.confidence());
            } else {
                continue;
            }
            written.add(write.id());
            if (write.created()) {
                added++;
                mapper.setFromStatement(write.id(), d.statement());
                // 新行取代了一条裸行（时间精化，supersedes 链上）：被取代那行的来源跟着
                // 搬过来，这一轮就收敛，不等下一轮把旧来源当「不成立」删掉再补。
                // 【M4 任务 7】supersedeId 为 null（新行 supersedes 列为空）时这两条
                // 语句匹配 0 行——门写入时带回这一列（GraphWriteService.FactWrite 注释
                // 的等价论证），跳过与执行同效，省一次注定空转的往返
                if (write.supersedeId() != null) {
                    mapper.adoptSupersededSources(write.id());
                    mapper.dropSupersededSources(write.id());
                }
            } else {
                merged++;
            }
            mapper.insertTypedSource(write.id(), d.statement());
            // 证据与限定各抄一份：证据是同一段原文的同一处引文；限定照角色词原样带过去。
            // 证据逐条（审查 C-1：门的 refine 分支读它），限定攒批（循环内确无读者）
            mapper.copyEvidenceFromStatement(write.id(), d.statement());
            qualifiersBatch.add(new Object[]{write.id(), d.statement()});
        }
        // 攒批的限定抄写在此一次往返落库（空批零语句，纯 merged 轮次不产生开销）
        if (!qualifiersBatch.isEmpty()) {
            jdbc.batchUpdate(MaterializeMapper.COPY_QUALIFIERS_FROM_STATEMENT_SQL, qualifiersBatch);
        }
        // 3a. 已批准的规则算隐含行（0044 决定 3 第五片）。读数只查缓存：缓存里没有的
        //     这一轮不算，read_phrases 填上之后再来
        long implied = implyInTx(kbId, written);
        // 3b. 见证跟着来源陈述走（0064 决定 3、5）：类型化的行自己没有出处。物化与算隐含
        //     行用的写入口在陈述没有见证时填的是此刻（那是给人写的事实用的缺省），这里改回
        //     陈述的。排在 3a 之后：规则算出的行也是这样写下的。见证动了的行提交后对账
        written.addAll(mapper.syncTypedAttestation(kbId, null));
        return new InTx(new Outcome(retired, added, merged, implied, 0, 0), written);
    }

    /**
     * materialize.rs:507-635 {@code imply_in_tx}：短语规则按陈述触发（读数缓存
     * phrase_readings），类别词规则按实体 specific_type 触发；一个东西不蕴含自己（读数把
     * 「Virginia」读成「United States」是对的，把「France」读成「France」就是一条自环）；
     * 证据抄触发它的那条陈述的引文——读的人从这句得出的结论，证据就是这句。
     */
    private long implyInTx(UUID kbId, List<UUID> written) {
        long implied = 0;
        List<ImpliedRow> rows = new ArrayList<>(mapper.selectImpliedByStatement(kbId));
        rows.addAll(mapper.selectImpliedByEntity(kbId));
        for (ImpliedRow d : rows) {
            Validity validity = new Validity(d.validFrom(), d.validFromPrecision(), d.validTo(),
                    d.validToPrecision(), d.attestedFrom(), d.validFromGrade());
            UUID objectId = d.objectId();
            if (objectId == null && d.objectValue() == null) {
                continue;
            }
            if (objectId != null && objectId.equals(d.subjectId())) {
                continue;
            }
            GraphWriteService.FactWrite write = writes.insertOnTx(kbId, d.subjectId(),
                    d.property(), objectId, d.objectValue(), validity, d.confidence());
            written.add(write.id());
            if (write.created()) {
                implied++;
                mapper.markImplied(write.id());
            }
            mapper.insertImpliedSource(write.id(), d.rule(), d.statement(), d.entity());
            if (d.statement() != null) {
                mapper.copyEvidenceFromStatement(write.id(), d.statement());
            }
        }
        return implied;
    }

    /**
     * materialize.rs:452-487 {@code sync_typed_attestation}（删文档那条路复用：只动引用了
     * 那篇文档的行）。返回见证动了的行——调用方提交之后拿这些行去对账。加入当前事务。
     */
    public List<UUID> syncTypedAttestation(UUID kbId, List<UUID> only) {
        return mapper.syncTypedAttestation(kbId, only);
    }

    /** materialize.rs:643-651 {@code count}：库里活着的、从陈述算出来的类型化行数。 */
    public long count(UUID kbId) {
        return mapper.countTyped(kbId);
    }
}
