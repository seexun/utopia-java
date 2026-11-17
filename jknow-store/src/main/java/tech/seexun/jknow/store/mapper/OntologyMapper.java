package tech.seexun.jknow.store.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.EntityInstance;
import tech.seexun.jknow.store.entity.EntityTypeView;
import tech.seexun.jknow.store.entity.RelationTypeView;

/**
 * ontology 读路径 mapper（逐字搬迁自旧仓库 utopia-0.1.0-rc9/crates/utopia-store/src/ontology.rs；
 * 每条 SQL 的 file:line 见 OntologyMapper.xml 注释）。M1 任务 4 只搬三个读面：
 * 两视图 + 实例列表；CRUD/uniqueness/proposals 等写与扩展面归后续任务。
 */
public interface OntologyMapper {

    /** 类视图（ontology.rs:13-35 entity_type_views）：parents/primary_parent/disjoint 数组子查询 + usage。 */
    List<EntityTypeView> selectEntityTypeViews(@Param("kbId") UUID kbId);

    /** 关系视图（ontology.rs:82-108 relation_type_views）：domains/ranges/qualifiers + usage（活事实计数）。 */
    List<RelationTypeView> selectRelationTypeViews(@Param("kbId") UUID kbId);

    /** 某类下实例一页（ontology.rs:45-55：名称序，含 fact_count 相关子查询）。 */
    List<EntityInstance> selectEntityInstances(@Param("kbId") UUID kbId, @Param("typeId") UUID typeId,
                                               @Param("limit") long limit, @Param("offset") long offset);

    /** 某类下实例总数（ontology.rs:62-65，同一 WHERE 无 ORDER/LIMIT）。 */
    long countEntityInstances(@Param("kbId") UUID kbId, @Param("typeId") UUID typeId);

    /** 抽取未匹配（活的）：参照实例二进制的 GET /ontology 契约（见 XML 头注的形态考古）。 */
    List<OntologyMiss> selectOntologyMisses(@Param("kbId") UUID kbId);

    /** 抽取未匹配（已忽略的 dismissed_at 非空）：同上契约。 */
    List<OntologyMiss> selectDismissedOntologyMisses(@Param("kbId") UUID kbId);

    /** 未匹配行形状（kind=entity_type|relation_type|attribute_type，key/example/count）。 */
    record OntologyMiss(String kind, String key, String example, int count) {
    }

    // ---- create_relation_type 写族（ontology.rs:458-558；任务 2 为 temporal 测试造数搬） ----

    /** ontology.rs:469-485 INSERT（17 列；属性不带 inverse_of/sub_property_of——兜底在服务层）。 */
    int insertRelationType(@Param("id") UUID id, @Param("kbId") UUID kbId, @Param("key") String key,
                           @Param("label") String label, @Param("temporal") String temporal,
                           @Param("functional") boolean functional,
                           @Param("inverseFunctional") boolean inverseFunctional,
                           @Param("description") String description, @Param("kind") String kind,
                           @Param("datatype") String datatype, @Param("unit") String unit,
                           @Param("transitive") boolean transitive,
                           @Param("symmetric") boolean symmetric,
                           @Param("asymmetric") boolean asymmetric,
                           @Param("irreflexive") boolean irreflexive,
                           @Param("inverseOf") UUID inverseOf,
                           @Param("subPropertyOf") UUID subPropertyOf);

    /** ontology.rs:541 覆盖式写 domain（先删后插）。 */
    void deleteDomains(@Param("id") UUID id);

    /** ontology.rs:550-554 unnest 一次插完。 */
    void insertDomains(@Param("id") UUID id,
                       @Param("typeIds") List<UUID> typeIds);

    /** ontology.rs:541 覆盖式写 range。 */
    void deleteRanges(@Param("id") UUID id);

    /** ontology.rs:550-554。 */
    void insertRanges(@Param("id") UUID id,
                      @Param("typeIds") List<UUID> typeIds);

    // ---- validate_property_links / update_relation_type（ontology.rs:406-455/658-822） ----

    /** ontology.rs:429-434 目标关系的 kind（不在本库返回 null——不区分「不存在」与「在别的库」）。 */
    String selectRelationKind(@Param("id") UUID id, @Param("kbId") UUID kbId);

    /** ontology.rs:676-682 行是不是内建的名字属性。 */
    Boolean selectBuiltinNameAttribute(@Param("id") UUID id, @Param("kbId") UUID kbId);

    /** ontology.rs:706-714 旧判据快照（行不在返回 null → NotFound）。 */
    OldCriteria selectOldCriteria(@Param("id") UUID id, @Param("kbId") UUID kbId);

    /** ontology.rs:826-836 旧判据的十列（跟「哪些违规成立」有关的列）。 */
    record OldCriteria(String kind, String temporal, boolean functional,
                       boolean inverseFunctional, boolean isTransitive, boolean isSymmetric,
                       boolean isAsymmetric, boolean isIrreflexive, UUID inverseOf,
                       UUID subPropertyOf) {
    }

    /** ontology.rs:779-800 UPDATE（两条链覆盖式写——缺省 = 清空）。返回行数（0 = NotFound）。 */
    int updateRelationType(@Param("kbId") UUID kbId, @Param("id") UUID id,
                           @Param("label") String label, @Param("temporal") String temporal,
                           @Param("functional") boolean functional,
                           @Param("inverseFunctional") boolean inverseFunctional,
                           @Param("description") String description,
                           @Param("datatype") String datatype, @Param("unit") String unit,
                           @Param("transitive") boolean transitive,
                           @Param("symmetric") boolean symmetric,
                           @Param("asymmetric") boolean asymmetric,
                           @Param("irreflexive") boolean irreflexive,
                           @Param("inverseOf") UUID inverseOf,
                           @Param("subPropertyOf") UUID subPropertyOf);

    /** ontology.rs:735-741 现行 domain 集（签名改没改）。 */
    List<UUID> selectOldDomains(@Param("id") UUID id);

    /** ontology.rs:742-748 现行 range 集。 */
    List<UUID> selectOldRanges(@Param("id") UUID id);

    // ---- 边上的属性声明（ontology.rs:569-652） ----

    /** ontology.rs:575-585 全部 ids 是不是本库的属性。 */
    long countAttributeQualifiers(@Param("kbId") UUID kbId, @Param("ids") List<UUID> ids);

    /** ontology.rs:626-634 单个 id 是不是本库的属性。 */
    long countAttributeQualifier(@Param("kbId") UUID kbId, @Param("id") UUID id);

    /** ontology.rs:587-589 覆盖式先删。 */
    void deleteRelationQualifiers(@Param("id") UUID id);

    /** ontology.rs:590-597 覆盖式后插。 */
    void insertRelationQualifiers(@Param("id") UUID id, @Param("ids") List<UUID> ids);

    /** ontology.rs:643-649 追加式（只加不删，撞上已有的什么都不做）。 */
    void insertRelationQualifier(@Param("id") UUID id,
                                 @Param("qualifierTypeId") UUID qualifierTypeId);

    // ---- create_entity_type + set_parents（ontology.rs:156-192/252-295） ----

    /** ontology.rs:164-174 INSERT。 */
    int insertEntityType(@Param("id") UUID id, @Param("kbId") UUID kbId, @Param("key") String key,
                         @Param("label") String label, @Param("color") String color,
                         @Param("shape") String shape, @Param("description") String description);

    /** ontology.rs:263-271 候选父类的祖先里出现 child 的条数（>0 会成环）。 */
    long countParentCycle(@Param("childId") UUID childId, @Param("parents") List<UUID> parents);

    /** ontology.rs:273-275 覆盖式先删。 */
    void deleteParents(@Param("id") UUID id);

    /** ontology.rs:276-290 后插（第一个当主父）。 */
    void insertParent(@Param("childId") UUID childId, @Param("parentId") UUID parentId,
                      @Param("primary") boolean primary);

    // ---- update_entity_type / delete_entity_type / delete_relation_type
    //      （ontology.rs:195-363 / 823-875，M3 任务 5：本体 CRUD 判据对账钩子） ----

    /** ontology.rs:212-214 旧父集（父链是签名判据，0062：违规按祖先链算）。 */
    List<UUID> selectParentIds(@Param("childId") UUID childId);

    /** ontology.rs:229-235 更新行（color=None 保持原色：COALESCE）。 */
    int updateEntityType(@Param("kbId") UUID kbId, @Param("id") UUID id,
                         @Param("label") String label, @Param("color") String color,
                         @Param("shape") String shape, @Param("description") String description);

    /** ontology.rs:306-308 还有人用这个类就不能删。 */
    long countEntitiesOfType(@Param("typeId") UUID typeId);

    /** ontology.rs:317-321 这个类是否牵着签名判据（当父类 / 在 domain / range 上）。 */
    boolean selectTypeAffectsCriteria(@Param("typeId") UUID typeId);

    /** ontology.rs:327-344 只剩这一个 domain 的属性随类一起走（属性必须挂在类上）。 */
    void deleteAttributesOnlyOnType(@Param("kbId") UUID kbId, @Param("typeId") UUID typeId);

    /** ontology.rs:346-349 删类（builtin 拦）。 */
    int deleteEntityType(@Param("kbId") UUID kbId, @Param("id") UUID id);

    /** ontology.rs:826-828 还有事实用这条关系就不能删。 */
    long countFactsOfPredicate(@Param("predicateId") UUID predicateId);

    /** ontology.rs:840-841 0070 外键是 DEFERRABLE SET NULL——提交才落地，先显式断链。 */
    void unlinkInverseOf(@Param("id") UUID id);

    /** ontology.rs:844-846 同上（sub_property_of）。 */
    void unlinkSubPropertyOf(@Param("id") UUID id);

    /** ontology.rs:848-851 删关系（builtin 拦）。 */
    int deleteRelationTypeRow(@Param("kbId") UUID kbId, @Param("id") UUID id);

    // ---- create_relation_types_bulk / set_disjoint_for
    //      （ontology.rs:1559-1625 / 1715-1738，M3 任务 5 随 axioms_reach_the_database
    //       与 a_declared_disjointness 的断言面落地） ----

    /** bulk INSERT 的 RETURNING 行（key → id）。 */
    record IdKey(UUID id, String key) {
    }

    /**
     * ontology.rs:1587-1604 一次插完一批关系（UNNEST 平行数组，ON CONFLICT DO NOTHING）。
     * 十二路平行参数与服务层 BulkRelation 的列一一对应。
     */
    List<IdKey> insertRelationTypesBulk(@Param("kbId") UUID kbId, @Param("keys") List<String> keys,
                                        @Param("labels") List<String> labels,
                                        @Param("descs") List<String> descs,
                                        @Param("iris") List<String> iris,
                                        @Param("kinds") List<String> kinds,
                                        @Param("datatypes") List<String> datatypes,
                                        @Param("functional") List<Boolean> functional,
                                        @Param("inverseFunctional") List<Boolean> inverseFunctional,
                                        @Param("transitive") List<Boolean> transitive,
                                        @Param("symmetric") List<Boolean> symmetric,
                                        @Param("asymmetric") List<Boolean> asymmetric,
                                        @Param("irreflexive") List<Boolean> irreflexive);

    /** ontology.rs:1720-1725 先清掉这个类参与的全部互斥边（两个方向都可能出现）。 */
    void deleteDisjointFor(@Param("kbId") UUID kbId, @Param("classId") UUID classId);

    /** ontology.rs:1727-1734 两个方向各写一行（与导入侧一致）。 */
    void insertDisjointPair(@Param("kbId") UUID kbId, @Param("a") UUID a, @Param("b") UUID b);

    /** ontology.rs:1432-1449 nearest_relation_type_ids 逐字（短语对齐短名单的近邻路）。 */
    List<UUID> nearestRelationTypeIds(@Param("kbId") UUID kbId,
                                      @Param("vector") String vector,
                                      @Param("limit") long limit,
                                      @Param("onlyKind") String onlyKind);

    /** ontology.rs:1461-1471 has_relation_type_vectors 逐字（谓词与上面一字不差）。 */
    boolean hasRelationTypeVectors(@Param("kbId") UUID kbId, @Param("onlyKind") String onlyKind);

    // ---- 本体向量（ontology.rs:1078-1297 types_needing_embedding / set_type_embeddings；
    //      M3 任务 10 随 embed_ontology job 落地） ----

    /** ontology.rs:1238-1249 StoredEmbedding：类型那一行上两份向量的现状，判陈用。 */
    record StoredEmbedding(UUID id, String label, String description, boolean embedded,
                           String embeddedModel, String embeddedText, boolean labelEmbedded,
                           String labelEmbeddedModel, String labelEmbeddedText) {
    }

    /** ontology.rs:1139-1148 逐字：类的两份向量现状（整段 + 只有 label 那份）。 */
    List<StoredEmbedding> selectEntityTypeEmbeddingRows(@Param("kbId") UUID kbId);

    /**
     * ontology.rs:1204-1214 逐字：关系/属性的现状（只有整段那一份，label 三列补空）。
     * 名字属性不嵌（0041）：没有向量就不会被最近邻检索出来——名字只走抽取回复的
     * {@code names} 那一条路。
     */
    List<StoredEmbedding> selectRelationTypeEmbeddingRows(@Param("kbId") UUID kbId);

    /** ontology.rs:1284-1293（EmbedField::Full，entity_types）。 */
    int updateEntityTypeEmbeddingFull(@Param("id") UUID id, @Param("vector") String vector,
                                      @Param("text") String text, @Param("model") String model);

    /** ontology.rs:1284-1293（EmbedField::Label，entity_types——列名前缀不同，其余一模一样）。 */
    int updateEntityTypeEmbeddingLabel(@Param("id") UUID id, @Param("vector") String vector,
                                       @Param("text") String text, @Param("model") String model);

    /** ontology.rs:1284-1293（relation_types，只有整段一份）。 */
    int updateRelationTypeEmbeddingFull(@Param("id") UUID id, @Param("vector") String vector,
                                        @Param("text") String text, @Param("model") String model);

    // ---- 检索候选地板与未覆盖计数（ontology.rs:1908-1925 ancestors_of / :2036-2051 uncovered） ----

    /** ontology.rs:1913-1920 逐字：递归上溯（UNION 去重——菱形不会把同一祖先展开两次）。 */
    List<UUID> selectAncestorsOf(@Param("ids") List<UUID> ids);

    /** ontology.rs:2036-2039 Uncovered：没绑到类的类别词、没绑到属性的短语各有多少。 */
    record Uncovered(long kindWords, long phrases) {
    }

    // ---- 本体扩展读族（M4 任务 6）：提案存取 / 导入历史 / 代理提案报告 ----

    /**
     * ontology.rs:1757-1765 StoredProposal 行（serves 是 uuid[]——它服务哪些能力问题；
     * signatures 是 jsonb——它会绑上的形状）。consumers: proposals 读面（只摆 agent 提的）。
     */
    record StoredProposalRow(String section, String key,
                             com.fasterxml.jackson.databind.JsonNode payload,
                             String proposedBy, List<UUID> serves,
                             com.fasterxml.jackson.databind.JsonNode signatures) {
    }

    /** ontology.rs:1863-1870 open_proposals：还等着人看的提案（新的排前面）。 */
    List<StoredProposalRow> selectOpenProposals(@Param("kbId") UUID kbId);

    /** models.rs:614-622 OntologyImportView 七列（imported_by_name 删号后为 null）。 */
    record ImportViewRow(UUID id, String filename, String format, long byteSize,
                         com.fasterxml.jackson.databind.JsonNode summary,
                         java.time.OffsetDateTime importedAt, String importedByName) {
    }

    /** ontology.rs:1063-1073 list_imports：一个库的导入历史（近 50 条）。 */
    List<ImportViewRow> selectOntologyImports(@Param("kbId") UUID kbId);

    /** ontology.rs:2053-2062 agent_proposal_report 的四格计数。 */
    record AgentProposalReportRow(long open, long adopted, long adoptedEdited, long rejected) {
    }

    /** ontology.rs:2053-2062：代理提案的报告（只数 proposed_by='agent' 的）。 */
    AgentProposalReportRow selectAgentProposalReport(@Param("kbId") UUID kbId);

    /** ontology.rs:2043-2047 逐字。 */
    Uncovered selectUncovered(@Param("kbId") UUID kbId);

    // ====================================================================
    // M5 任务 5：OWL 导入的写路径（owl_import.rs 的 store 面——批量建/更新/认领/
    // 第二遍连边/记账）与本体代理的提案写面（ontology.rs:1778-1931 + agent_reviews.rs）。
    // ====================================================================

    /** 现有类/关系的 (id, key, iri)——iri 可空（本地起的名字没有 IRI）。 */
    record IdKeyIri(UUID id, String key, String iri) {
    }

    /** owl_import.rs:188-199 现有本体索引（entity_types，plan 只取这三列）。 */
    List<IdKeyIri> selectEntityTypesWithIri(@Param("kbId") UUID kbId);

    /** owl_import.rs:188-199 现有本体索引（relation_types，plan 只取这三列）。 */
    List<IdKeyIri> selectRelationTypesWithIri(@Param("kbId") UUID kbId);

    /** ontology.rs:1487-1558 create_entity_types_bulk：UNNEST 一次插完，回 id+key。 */
    List<IdKey> insertEntityTypesBulk(@Param("kbId") UUID kbId, @Param("keys") List<String> keys,
                                      @Param("labels") List<String> labels,
                                      @Param("descs") List<String> descs,
                                      @Param("iris") List<String> iris,
                                      @Param("colors") List<String> colors,
                                      @Param("shapes") List<String> shapes);

    /** ontology.rs:925-950 update_type_from_import：按 IRI 更新标签与描述（key 不动），回 id。 */
    UUID updateTypeFromImport(@Param("kbId") UUID kbId, @Param("iri") String iri,
                              @Param("label") String label,
                              @Param("description") String description);

    /** ontology.rs:1413-1426 adopt_iri_onto_key：认领本地同名类（只在 iri IS NULL 时写）。 */
    UUID adoptIriOntoKey(@Param("kbId") UUID kbId, @Param("key") String key,
                         @Param("iri") String iri);

    /** ontology.rs:1657-1681 set_parents_bulk：一次设完一批父类（不查环——环是上游的事）。 */
    int insertParentsBulk(@Param("children") List<UUID> children,
                          @Param("parents") List<UUID> parents);

    /** ontology.rs:1684-1713 set_disjoint_bulk：类互斥只增不减（导入专用）。 */
    int insertDisjointBulk(@Param("kbId") UUID kbId, @Param("a") List<UUID> a,
                           @Param("b") List<UUID> b);

    /** ontology.rs:1002-1031 update_relation_from_import：按 IRI 更新（key 不动、空描述不覆盖）。 */
    UUID updateRelationFromImport(@Param("kbId") UUID kbId, @Param("iri") String iri,
                                  @Param("label") String label,
                                  @Param("description") String description);

    /** owl_import 逐条 Update 的 domain/range 整体重写：先清两张关联表（domain 侧）。 */
    int deleteDomainsOf(@Param("id") UUID id);

    /** 同上（range 侧）。 */
    int deleteRangesOf(@Param("id") UUID id);

    /** ontology.rs:1624-1655 link_domains_ranges_bulk：摊平两条 UNNEST（不 DELETE）。 */
    int insertDomainsBulk(@Param("rels") List<UUID> rels,
                          @Param("types") List<UUID> types);

    /** 同上（range 表）。 */
    int insertRangesBulk(@Param("rels") List<UUID> rels,
                         @Param("types") List<UUID> types);

    /** ontology.rs:1934-2034 link_property_axioms_bulk：把逆属性从 IRI 解析成 id（第二遍）。 */
    long linkInverseOfBulk(@Param("kbId") UUID kbId, @Param("src") List<String> src,
                           @Param("dst") List<String> dst);

    /** 同上（sub_property_of 一支）。 */
    long linkSubPropertyOfBulk(@Param("kbId") UUID kbId, @Param("src") List<String> src,
                               @Param("dst") List<String> dst);

    /** ontology.rs:1033-1061 record_import：原文已进 blob，这里只记账。 */
    int insertImport(@Param("id") UUID id, @Param("kbId") UUID kbId,
                     @Param("sha256") String sha256, @Param("filename") String filename,
                     @Param("format") String format, @Param("byteSize") long byteSize,
                     @Param("summary") com.fasterxml.jackson.databind.JsonNode summary,
                     @Param("actor") UUID actor);

    /** ontology.rs:1778-1805 save_agent_proposals：只刷 open 的行（决定的还在）。 */
    int upsertAgentProposal(@Param("id") UUID id, @Param("kbId") UUID kbId,
                            @Param("section") String section, @Param("key") String key,
                            @Param("payload") com.fasterxml.jackson.databind.JsonNode payload,
                            @Param("serves") List<UUID> serves,
                            @Param("signatures") com.fasterxml.jackson.databind.JsonNode signatures);

    /** ontology.rs:1808-1816 signatures_already_proposed：agent 提案的全部签名 JSON。 */
    List<com.fasterxml.jackson.databind.JsonNode> selectAgentProposalSignatures(
            @Param("kbId") UUID kbId);

    /** ontology.rs:1829-1843 open_proposal：按 (section,key) 取一条 open 的（采纳时读 payload）。 */
    StoredProposalRow selectOpenProposal(@Param("kbId") UUID kbId,
                                         @Param("section") String section,
                                         @Param("key") String key);

    /** ontology.rs:1879-1898 decide_proposal：改状态不删行（采纳/拒绝都发生过）。 */
    int decideProposal(@Param("kbId") UUID kbId, @Param("section") String section,
                       @Param("key") String key, @Param("status") String status,
                       @Param("actor") UUID actor);

    /** ontology.rs:1821-1843 reject_proposal 连理由（比 decideProposal 多写 reason 一列）。 */
    int rejectProposal(@Param("kbId") UUID kbId, @Param("section") String section,
                       @Param("key") String key, @Param("reason") String reason,
                       @Param("actor") UUID actor);

    /** adopt 后的 edited 位（报告 changed_share 的分子要它）。 */
    int markProposalEdited(@Param("kbId") UUID kbId, @Param("section") String section,
                           @Param("key") String key);

    /** agent_reviews.rs:7-16 Review 行。 */
    record AgentReviewRow(String kind,
                          com.fasterxml.jackson.databind.JsonNode shape,
                          String outcome, String target, String basis) {
    }

    /** agent_reviews.rs:22-28 list 逐字。 */
    List<AgentReviewRow> selectAgentReviews(@Param("kbId") UUID kbId);

    /** agent_reviews.rs:31-45 record：同一形状再看是刷新。 */
    int upsertAgentReview(@Param("kbId") UUID kbId, @Param("kind") String kind,
                          @Param("shape") com.fasterxml.jackson.databind.JsonNode shape,
                          @Param("outcome") String outcome, @Param("target") String target,
                          @Param("basis") String basis);

    /** 近邻候选整行（utopia_core::models::TypeCandidate 的 store 形）。 */
    record TypeCandidateRow(UUID id, String key, String label, String description,
                            String kind, double distance) {
    }

    /**
     * ontology.rs:1314-1356 nearest_entity_types 逐字（列名由服务层传
     * embedding/label_embedding——短说法对短标签，两份索引见 entity_types 列）。
     */
    List<TypeCandidateRow> selectNearestEntityTypes(@Param("kbId") UUID kbId,
                                                    @Param("vector") String vector,
                                                    @Param("limit") long limit,
                                                    @Param("column") String column);

    /** ontology.rs:1360-1390 nearest_relation_types 的整行版（词表近邻要 label/描述）。 */
    List<TypeCandidateRow> selectNearestRelationTypes(@Param("kbId") UUID kbId,
                                                      @Param("vector") String vector,
                                                      @Param("limit") long limit,
                                                      @Param("onlyKind") String onlyKind);
}
