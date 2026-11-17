package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 类型化物化 SQL（materialize.rs 逐字搬迁；147-435 materialize_in_tx 的步骤 0/1/1b/2、
 * due 查询、imply_in_tx 两条取数、sync_typed_attestation、count）。每条 XML 内注明
 * file:line。MATCH/MOMENT_UNREAD/RULE_MATCH 片段随 SQL 展开在 XML 里（与 Rust
 * {@code format!} 拼接的产物同一文本）。步骤编排在 {@code TypedMaterializeService}。
 */
public interface MaterializeMapper {

    // ---- 锁（materialize.rs:95/131） ----

    /** materialize.rs:95 等锁：worker 的完整重算串行（pg_advisory_xact_lock）。 */
    void lockMaterialize(@Param("kb") String kb);

    /** materialize.rs:130-131 试锁不等（job 用，0051）：拿不到回 false。 */
    Boolean tryLockMaterialize(@Param("kb") String kb);

    // ---- materialize_in_tx 步骤 0/1/1b/2（materialize.rs:161-243） ----

    /**
     * materialize.rs:161-175 步骤 0：作废 #966 之前照抄的一刻状态行（只认
     * from_statement_id IS NOT NULL OR implied 且来源陈述两端正是这一刻）。返回作废数。
     */
    int retireMomentStateRows(@Param("kbId") UUID kbId);

    /**
     * materialize.rs:182-205 步骤 1：DELETE typed_fact_sources 不再成立的来源
     * （陈述死/行死/签名不绑/方向变/有 mood/MOMENT_UNREAD——MATCH 片段逐字）。
     */
    int deleteBrokenTypedSources(@Param("kbId") UUID kbId);

    /**
     * materialize.rs:209-229 步骤 1b：DELETE implied_fact_sources（规则不再 approved /
     * 触发陈述死（RULE_MATCH）/ 实体没了或换了类别词（kind_word_sql））。
     */
    int deleteBrokenImpliedSources(@Param("kbId") UUID kbId);

    /**
     * materialize.rs:232-239 步骤 2：作废来源全空的<b>算出来的</b>类型化行
     * （人写不碰——from_statement_id IS NOT NULL OR implied 守卫）。返回作废数。
     */
    int retireOrphanTypedRows(@Param("kbId") UUID kbId);

    // ---- 步骤 3：due 查询（materialize.rs:248-277） ----

    /**
     * materialize.rs:248-273 该有而没有的（陈述, 绑定）对：陈述活着、签名绑着、没 mood、
     * 没被勘误撤过（0044 决定 7）、还没有活着的行以它为来源且谓词相同。
     * reverse 只对两样东西之间的关系有意义（object_id 非空）。
     */
    List<DueRow> selectDue(@Param("kbId") UUID kbId);

    /** materialize.rs:65-84 Due（一条该物化的（陈述, 绑定）对，连陈述上要抄的东西）。 */
    record DueRow(UUID statement, UUID property, String direction, UUID subjectId,
                  UUID objectId, JsonNode objectValue,
                  OffsetDateTime validFrom, String validFromPrecision, String validFromGrade,
                  OffsetDateTime validTo, String validToPrecision,
                  OffsetDateTime attestedFrom, float confidence,
                  String marks, String temporal) {
    }

    /**
     * materialize.rs:357 新行 from_statement_id（只在为空时填）。<b>必须逐条执行</b>
     * （M4 任务 7 审查 C-1）：门的闭合分支改写携带旧行的 from_statement_id
     * （insertRewriteEnd 照抄该列），同轮双陈述时延迟落库会让修正行带着 NULL 出门、
     * 再被攒批的 setFrom 覆写——与 Rust 逐条序的「第一条陈述」终态分歧。
     */
    int setFromStatement(@Param("factId") UUID factId, @Param("statementId") UUID statementId);

    /**
     * materialize.rs:393-401 证据抄一份：证据是同一段原文的同一处引文
     * （ON CONFLICT DO NOTHING）。<b>必须逐条执行</b>（M4 任务 7 审查 C-1）：门的
     * refine 分支读 fact_evidence（copyEvidenceFrom 把被取代行上的证据搬来）——同轮
     * 更早 due 的证据若攒批未落库，refine 的新行搬不到它，先到陈述的证据丢失。
     */
    int copyEvidenceFromStatement(@Param("factId") UUID factId,
                                  @Param("statementId") UUID statementId);

    /**
     * materialize.rs:406-413 限定照角色词原样带过去（ON CONFLICT DO NOTHING）。
     * <b>M4 任务 7 因路径攒批执行</b>（JDBC batch，TypedMaterializeService）：
     * statement_qualifiers 在本轮循环内确无读者——due 查询（mood 检查）循环前已取完，
     * 门搬限定走的是另一张表 fact_qualifiers（graph.rs copy_qualifiers_from），
     * temporal 改写也不碰它；攒批仅打包往返（reWriteBatchedInserts=false），
     * 服务端逐条按 due 序执行，与 Rust 逐条同终态。
     */
    String COPY_QUALIFIERS_FROM_STATEMENT_SQL = """
            INSERT INTO statement_qualifiers (fact_id, role, value, entity_id)
             SELECT ?, role, value, entity_id FROM statement_qualifiers WHERE fact_id = ?
             ON CONFLICT DO NOTHING
            """;

    /**
     * materialize.rs:364-370 新行取代了一条裸行（supersedes 链上）：被取代那行的来源
     * 跟着搬过来，这一轮就收敛。
     */
    int adoptSupersededSources(@Param("factId") UUID factId);

    /** materialize.rs:374-378 搬完删旧行上的来源（收敛的同一步）。 */
    int dropSupersededSources(@Param("factId") UUID factId);

    /** materialize.rs:385-388 （陈述, 行）来源链接（ON CONFLICT DO NOTHING）。 */
    int insertTypedSource(@Param("factId") UUID factId, @Param("statementId") UUID statementId);

    // ---- 步骤 3a：imply_in_tx 取数（materialize.rs:514-568） ----

    /**
     * materialize.rs:514-545 短语规则：签名下活着的、没 mood 的、没被勘误撤过的陈述；
     * 宾语是读数的答案（缓存里的实体或值），没有读数时就是陈述的宾语。已经有活着的
     * 隐含行以这条陈述为来源的不再算。规则没有 marks（#966）：一刻的陈述蕴含状态属性
     * 时读法未知，不算隐含行。
     */
    List<ImpliedRow> selectImpliedByStatement(@Param("kbId") UUID kbId);

    /**
     * materialize.rs:549-568 类别词规则：带这个类别词的活着的实体；宾语必须来自读数
     * （类别词自己没有宾语）；置信度 0.9、无时间（与陈述无关）。
     */
    List<ImpliedRow> selectImpliedByEntity(@Param("kbId") UUID kbId);

    /** materialize.rs:489-505 Implied（一条该算的隐含行）。 */
    record ImpliedRow(UUID rule, UUID statement, UUID entity, UUID subjectId, UUID property,
                      UUID objectId, JsonNode objectValue,
                      OffsetDateTime validFrom, String validFromPrecision, String validFromGrade,
                      OffsetDateTime validTo, String validToPrecision,
                      OffsetDateTime attestedFrom, float confidence) {
    }

    /** materialize.rs:603 新隐含行标记 implied=TRUE。 */
    int markImplied(@Param("factId") UUID factId);

    /**
     * materialize.rs:608-612 隐含行的来源：规则 + 触发陈述/实体
     * （ON CONFLICT DO NOTHING）。
     */
    int insertImpliedSource(@Param("factId") UUID factId, @Param("ruleId") UUID ruleId,
                            @Param("statementId") UUID statementId, @Param("entityId") UUID entityId);

    // ---- 步骤 3b：sync_typed_attestation（materialize.rs:452-487） ----

    /**
     * materialize.rs:457-484 类型化行的见证 = 它的来源陈述里最早的那个见证，连同那条
     * 日期的名字（UNION typed+implied 来源；作废了的来源陈述不作证；只动算出来的行）。
     * 返回见证动了的行。
     */
    List<UUID> syncTypedAttestation(@Param("kbId") UUID kbId,
                                    @Param("only") List<UUID> only);

    // ---- count（materialize.rs:643-651） ----

    /** materialize.rs:644-648 库里活着的、从陈述算出来的类型化行数。 */
    long countTyped(@Param("kbId") UUID kbId);
}
