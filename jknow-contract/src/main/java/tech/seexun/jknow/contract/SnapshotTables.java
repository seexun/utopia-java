package tech.seexun.jknow.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快照表登记簿（M3 任务 1，规格 §3.2/§5 锁 4）：kb 作用域账本表 → 取数 SQL + 稳定复合键。
 *
 * <p><b>登记集即默认集</b>（核心表 + 扩展表全部默认 dump——比对面宁全勿缺，空表不产
 * 差异；扩展表无须 {@code tables:} 另行启用）。剧本快照步可用 {@code tables: [...]} 把
 * 默认集<b>收窄</b>为指定子集。jobs 表<b>永不进快照</b>（瞬态队列，静默等待只轮询它——
 * 见 {@link Snapshot#awaitQuiet}）。
 *
 * <p>kb 作用域的三种形状（从 {@code utopia-server/src/main/resources/db/migration/} 迁移
 * SQL 逐表梳理）：
 * <ol>
 * <li>自带 kb_id 列——直接过滤；</li>
 * <li>无 kb_id 但挂在 facts/derived_facts 下（fact_evidence、statement_qualifiers、
 * typed_fact_sources、implied_fact_sources、fact_derivations）——JOIN 主表按其 kb_id 过滤；</li>
 * <li>投影表（chunks/documents）——只取对终态比对有意义的列：chunks 的 text/embedding
 * 是载荷不是账本，documents 的全文/哈希同理（投影列集是任务规格定死的，见 m3-plan 任务 1）。</li>
 * </ol>
 *
 * <p><b>稳定复合键</b>（businessKeys）供 {@link SnapshotNormalizer} 行排序：优先非 id
 * 业务键（canonical_name/phrase/kind_word/key/filename/valid_from/seq/created_at……），
 * 业务键全同才落到 id 占位符——两侧 UUID 各自随机，首层排序若吃原始 UUID，两次运行
 * 的行序必然漂移，制造假差异。键列缺省不存在的列按最小值排序（防御未来 schema 增删列）。
 *
 * <p><b>pkColumn</b>：单 uuid 主键列名，供 UUID 规范标签索引（多列复合主键/无主键表
 * 填 null——这些表的行不作为标签锚点，只被引用）。
 *
 * <p>「implication_rules（+当前版本）」的规格注记核实结果：schema 中 implication_rules
 * <b>无版本表</b>（attribute_rule_versions 属 attribute_rules，见 V0076）；故本登记照字面
 * 收录 implication_rules，attribute_rules 族（rules/attribute_rules/attribute_rule_versions/
 * attribute_rule_conditions）作为扩展表同进默认集——审查 I-1 裁定「登记集即默认集」
 * （golden 已按全集自洽，比对面宁全勿缺），derived_facts.rule_id 的外部引用也因此
 * 默认就能解析成规范标签。
 */
final class SnapshotTables {

    /** 一张登记表：取数 SQL（唯一 ? 绑 kb uuid）、稳定复合键（排序优先序）、uuid 主键列。 */
    record TableSpec(String select, List<String> businessKeys, String pkColumn) {
    }

    static final Map<String, TableSpec> REGISTRY = buildRegistry();

    /** 默认集 = 登记全集（LinkedHashMap 保序，输出时表名再排序；审查 I-1：宁全勿缺）。 */
    static List<String> defaultTables() {
        return List.copyOf(REGISTRY.keySet());
    }

    /** 表名合法性校验（tables 覆盖只能从登记词汇里选），返回解析后的表集（null → 默认集）。 */
    static List<String> resolve(List<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return defaultTables();
        }
        for (String t : tables) {
            if (!REGISTRY.containsKey(t)) {
                throw new IllegalArgumentException(
                        "快照表未登记: " + t + "（可选用: " + REGISTRY.keySet() + "；jobs 永不进快照）");
            }
        }
        return List.copyOf(tables);
    }

    private static Map<String, TableSpec> buildRegistry() {
        Map<String, TableSpec> m = new LinkedHashMap<>();
        // ---- 默认心脏集（登记序即默认集）----
        m.put("facts", new TableSpec(
                "SELECT * FROM facts WHERE kb_id = ?",
                List.of("subject_id", "predicate_id", "object_id", "object_value",
                        "valid_from", "valid_from_precision", "valid_to", "valid_to_precision", "id"),
                "id"));
        m.put("derived_facts", new TableSpec(
                "SELECT * FROM derived_facts WHERE kb_id = ?",
                List.of("subject_id", "predicate_id", "object_id", "valid_from", "valid_to", "id"),
                "id"));
        m.put("fact_derivations", new TableSpec(
                "SELECT d.* FROM fact_derivations d JOIN derived_facts f ON f.id = d.derived_fact_id WHERE f.kb_id = ?",
                List.of("derived_fact_id", "seq"), null));
        m.put("fact_evidence", new TableSpec(
                "SELECT e.* FROM fact_evidence e JOIN facts f ON f.id = e.fact_id WHERE f.kb_id = ?",
                List.of("fact_id", "chunk_id"), null));
        m.put("fact_conflicts", new TableSpec(
                "SELECT * FROM fact_conflicts WHERE kb_id = ?",
                List.of("old_fact_id", "new_fact_id", "reason", "id"), "id"));
        m.put("statement_qualifiers", new TableSpec(
                "SELECT q.* FROM statement_qualifiers q JOIN facts f ON f.id = q.fact_id WHERE f.kb_id = ?",
                List.of("fact_id", "role"), null));
        m.put("time_mentions", new TableSpec(
                "SELECT * FROM time_mentions WHERE kb_id = ?",
                List.of("fact_id", "chunk_id", "char_start", "id"), "id"));
        m.put("typed_fact_sources", new TableSpec(
                "SELECT t.* FROM typed_fact_sources t JOIN facts f ON f.id = t.fact_id WHERE f.kb_id = ?",
                List.of("fact_id", "statement_id"), null));
        m.put("implied_fact_sources", new TableSpec(
                "SELECT i.* FROM implied_fact_sources i JOIN facts f ON f.id = i.fact_id WHERE f.kb_id = ?",
                List.of("fact_id", "rule_id"), null));
        m.put("pending_facts", new TableSpec(
                "SELECT * FROM pending_facts WHERE kb_id = ?",
                List.of("subject_id", "predicate_id", "object_id", "object_value", "chunk_id", "id"), "id"));
        m.put("rejected_facts", new TableSpec(
                "SELECT * FROM rejected_facts WHERE kb_id = ?",
                List.of("subject_id", "predicate_id", "object_id", "rejected_at"), null));
        m.put("entities", new TableSpec(
                "SELECT * FROM entities WHERE kb_id = ?",
                // canonical_name 打头：实体的业务身份是名字，不是 UUID
                List.of("canonical_name", "type_id", "disambiguator", "id"), "id"));
        m.put("entity_merges", new TableSpec(
                "SELECT * FROM entity_merges WHERE kb_id = ?",
                List.of("source_id", "target_id", "created_at", "id"), "id"));
        m.put("resolution_reviews", new TableSpec(
                "SELECT * FROM resolution_reviews WHERE kb_id = ?",
                List.of("left_id", "right_id", "stage", "status", "id"), "id"));
        m.put("axiom_violations", new TableSpec(
                "SELECT * FROM axiom_violations WHERE kb_id = ?",
                List.of("kind", "left_fact", "right_fact", "id"), "id"));
        m.put("ontology_defects", new TableSpec(
                "SELECT * FROM ontology_defects WHERE kb_id = ?",
                List.of("kind", "subject", "other", "id"), "id"));
        m.put("phrase_bindings", new TableSpec(
                "SELECT * FROM phrase_bindings WHERE kb_id = ?",
                List.of("phrase", "direction", "subject_type_id", "object_type_id", "id"), "id"));
        m.put("type_bindings", new TableSpec(
                "SELECT * FROM type_bindings WHERE kb_id = ?",
                List.of("kind_word", "id"), "id"));
        m.put("implication_rules", new TableSpec(
                "SELECT * FROM implication_rules WHERE kb_id = ?",
                List.of("trigger", "phrase", "conclude_property_id", "reading", "status", "id"), "id"));
        m.put("relation_types", new TableSpec(
                "SELECT * FROM relation_types WHERE kb_id = ?",
                List.of("key", "kind", "id"), "id"));
        m.put("entity_types", new TableSpec(
                "SELECT * FROM entity_types WHERE kb_id = ?",
                List.of("key", "id"), "id"));
        m.put("entity_type_disjoint", new TableSpec(
                "SELECT * FROM entity_type_disjoint WHERE kb_id = ?",
                List.of("a_id", "b_id"), null));
        m.put("chunks", new TableSpec(
                // 投影（任务规格定死）：text/embedding 是载荷不是账本，不进终态比对
                "SELECT id, kb_id, seq, extracted_at, superseded_at FROM chunks WHERE kb_id = ?",
                List.of("seq", "id"), "id"));
        m.put("documents", new TableSpec(
                // 投影（任务规格定死）：终态比对只看图谱抽取相关的生命周期列
                "SELECT id, kb_id, filename, status, graph_status, graph_error, extract_epoch,"
                        + " deleted_at, doc_time, doc_time_source FROM documents WHERE kb_id = ?",
                List.of("filename", "id"), "id"));
        // ---- 扩展表（同进默认集，审查 I-1 裁定：宁全勿缺；tables: 只用于收窄）----
        m.put("rules", new TableSpec(
                "SELECT * FROM rules WHERE kb_id = ?",
                List.of("predicate_id", "kind", "id"), "id"));
        m.put("attribute_rules", new TableSpec(
                "SELECT * FROM attribute_rules WHERE kb_id = ?",
                List.of("name", "id"), "id"));
        m.put("attribute_rule_versions", new TableSpec(
                "SELECT * FROM attribute_rule_versions WHERE kb_id = ?",
                List.of("rule_id", "seq", "id"), "id"));
        m.put("attribute_rule_conditions", new TableSpec(
                "SELECT c.* FROM attribute_rule_conditions c JOIN attribute_rules r ON r.id = c.rule_id"
                        + " WHERE r.kb_id = ?",
                List.of("rule_id", "group_seq", "seq"), null));
        m.put("phrase_readings", new TableSpec(
                "SELECT * FROM phrase_readings WHERE kb_id = ?",
                List.of("reading", "phrase"), null));
        m.put("extraction_drops", new TableSpec(
                "SELECT * FROM extraction_drops WHERE kb_id = ?",
                List.of("document_id", "reason", "detail"), null));
        // 勘误两表（M3 任务 11）：errata_actions 是勘误撤回跳过语义的锚——物化 due 查询按
        // （statement_id, predicate_id, action retract/revise, status applied）跳过（0044 决定 7），
        // 快照要能看见这一行；errata_runs 是其外键前提（结构上不含可变时序之外的信息，一并收）
        m.put("errata_runs", new TableSpec(
                "SELECT * FROM errata_runs WHERE kb_id = ?",
                List.of("document_id", "started_at", "id"), "id"));
        m.put("errata_actions", new TableSpec(
                "SELECT * FROM errata_actions WHERE kb_id = ?",
                List.of("action", "status", "statement_id", "predicate_id", "created_at", "id"), "id"));
        return java.util.Collections.unmodifiableMap(m);
    }

    private SnapshotTables() {
    }
}
