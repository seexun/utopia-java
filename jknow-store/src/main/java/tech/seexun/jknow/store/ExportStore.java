package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.store.GraphWriteService.FactQualifier;

/**
 * 导出用的读取面（0020，逐字对齐旧仓库 utopia-store/src/export.rs 1104 行）。
 *
 * <p>与界面那几个视图分开写，因为问的东西不一样：界面要「现在是什么样」，导出要<b>全部</b>——
 * 闭合的区间、撤回的行、修正链，一条都不能少，否则导出的是一张干净自信的图，而那正是
 * 审计要看的东西被抹掉的样子。所以 facts 一族<b>不过滤 invalidated_at</b>。
 *
 * <p>除本体外一律<b>按 id 分页</b>：一个库的事实可以有几十万条，全读进内存再序列化会在最需要
 * 它的那种部署上炸掉。id 是 uuid v7，按它排序即按写入顺序排序。第一页没有下界
 * （{@code $2 IS NULL OR id > $2}）：uuid 没有更小的哨兵可垫——{@code id > NIL} 会把主键恰为
 * NIL 的合法行永远挡在导出外，而指向它的 (document_id, version) 定位器照样解析，留下一条
 * 没有本体的边。
 *
 * <p><b>同一份快照</b>：所有取数口吃调用方的连接（{@link Connection}），路由侧（ExportController）
 * 把它定成只读 REPEATABLE READ——体检、词汇表与每一页读的是同一个时刻的库。若每页各自向
 * 连接池要一条连接，词汇表发完之后才提交的规则会在派生页里留下一条指向它的 wasGeneratedBy
 * ——图里就出现没有本体的引用。
 *
 * <p><b>逐页校验用的是留下的那几行自己</b>：每个 page 查询把被引行的 kb_id 与行本体<b>原子地
 * 一并选出</b>，校验在内存里跑。不能「先取一页、再去库里问一次」——第二次问的是另一个时刻
 * 的状态，留下的行早已不是它。
 *
 * <p>占位符约定：SQL 逐字搬迁，仅 {@code $1/$2/$3} 机械改写为 JDBC 的 {@code ?}（与
 * AuditMapper.xml 的 {@code #{} } 转写同一纪律），列集/值序/文本不动。
 */
public final class ExportStore {

    private ExportStore() {
    }

    /** 一次取多少行（export.rs:34 PAGE）。够大以免把往返次数拉满，够小以免一页撑爆内存。 */
    public static final int PAGE = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 出处体检的唯一一份 SQL：运行时就跑它，migration_0070 守卫（catalog 完备性）读的也是它。 */
    private static final String PREFLIGHT_SQL = loadPreflight();

    private static String loadPreflight() {
        try (InputStream in = ExportStore.class.getClassLoader()
                .getResourceAsStream("export_provenance_integrity.sql")) {
            if (in == null) {
                throw new IllegalStateException("export_provenance_integrity.sql 不在 classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读 export_provenance_integrity.sql 失败", e);
        }
    }

    // =====================================================================
    // 行形状（export.rs:149-299 的 FromRow 结构逐字）
    // =====================================================================

    /** export.rs:149-160 ExportClass。导入来的类留着它原来的 IRI（schema.org 的 Organization
     *  导出去还是 schema:Organization，读的人手里的词汇表对得上）。 */
    public record ExportClass(UUID id, String key, String label, String description, String iri,
                              List<UUID> parents, List<UUID> disjoint) {
    }

    /** export.rs:162-186 ExportRelation。 */
    public record ExportRelation(UUID id, String key, String label, String description, String iri,
                                 String kind, String datatype, String unit, String temporal,
                                 boolean functional, boolean inverseFunctional, boolean isTransitive,
                                 boolean isSymmetric, boolean isAsymmetric, boolean isIrreflexive,
                                 UUID inverseOf, UUID subPropertyOf,
                                 List<UUID> domains, List<UUID> ranges) {
    }

    /** export.rs:188-196 ExportEntity（type_kb 由 page 查询 LEFT JOIN 原子选出：越库/悬空 → 拒导）。 */
    public record ExportEntity(UUID id, String canonicalName, UUID typeId, UUID typeKb) {
    }

    /** export.rs:198-242 ExportFact（qualifiers 加载后按事实 id 补）。 */
    public record ExportFact(UUID id, UUID subjectId, UUID predicateId, String surfacePredicate,
                             UUID objectId, JsonNode objectValue, List<FactQualifier> qualifiers,
                             OffsetDateTime validFrom, String validFromPrecision,
                             OffsetDateTime validTo, String validToPrecision,
                             OffsetDateTime holdsFrom, OffsetDateTime holdsTo,
                             OffsetDateTime recordedAt, OffsetDateTime invalidatedAt,
                             float confidence, boolean implied, UUID supersedes,
                             List<UUID> documents, List<String> quotes, List<String> quoteOrigins,
                             UUID subjectKb, UUID objectKb, UUID predicateKb, UUID supersedesKb,
                             boolean foreignDocument, boolean foreignChunk,
                             boolean subjectMerged, boolean objectMerged) {
    }

    /** export.rs:244-288 ExportDerived。 */
    public record ExportDerived(UUID id, UUID subjectId, UUID predicateId, UUID objectId,
                                JsonNode objectValue, UUID ruleId, UUID attributeRuleId,
                                OffsetDateTime validFrom, String validFromPrecision,
                                OffsetDateTime validTo, String validToPrecision,
                                OffsetDateTime derivedAt, OffsetDateTime invalidatedAt,
                                float confidence, String rule, UUID rulePredicate, String ruleName,
                                List<UUID> premises, List<UUID> premisesDerived,
                                UUID subjectKb, UUID objectKb, UUID predicateKb,
                                UUID ruleKb, UUID attributeRuleKb,
                                boolean foreignFactPremise, boolean foreignDerivedPremise,
                                boolean subjectMerged, boolean objectMerged) {
    }

    /** export.rs:290-299 ExportDocument（删过的文档留墓碑照导——prov:invalidatedAtTime）。 */
    public record ExportDocument(UUID id, String filename, String externalKey,
                                 OffsetDateTime docTime, OffsetDateTime createdAt,
                                 OffsetDateTime deletedAt) {
    }

    /** export.rs:769-787 ExportFactConflict（0062 认知状态之一）。 */
    public record ExportFactConflict(UUID id, UUID oldFactId, UUID newFactId, String reason,
                                     OffsetDateTime createdAt, String status, String resolution,
                                     OffsetDateTime resolvedAt,
                                     UUID oldFactKb, UUID newFactKb) {
    }

    /** export.rs:834-861 ExportAxiomViolation（0062；criterion 两列只在 open 行上解析）。 */
    public record ExportAxiomViolation(UUID id, String kind, UUID leftFact, UUID rightFact,
                                       List<UUID> path, String status, String resolution,
                                       OffsetDateTime detectedAt, OffsetDateTime decidedAt,
                                       UUID criterionPredicate, String criterionAxiom,
                                       UUID leftKb, UUID rightKb, UUID criterionPredicateKb,
                                       boolean foreignPath) {

        /**
         * 判据的 RDF 词项（utopia:criterion 的宾语，可能不止一个）。open 行必然取得到——
         * 取不到说明存储值进了合同不认识的东西，拒导而不是瞎编（export.rs:899-905）。
         */
        public String[] criterionTerms() {
            return criterionTermsOf(criterionAxiom);
        }
    }

    /** export.rs:48-53 CrossKbViolation：哪条边坏了、坏了几行。 */
    public record CrossKbViolation(String edge, long rows) {
    }

    // =====================================================================
    // 拒导错误（export.rs:55-91 + 979-984：四拒导码全集）
    // =====================================================================

    private static String detail(List<CrossKbViolation> violations) {
        StringBuilder sb = new StringBuilder();
        for (CrossKbViolation v : violations) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(v.edge()).append(": ").append(v.rows()).append(" row(s)");
        }
        return sb.toString();
    }

    /**
     * 出处链越界（别库/悬空）：把别库对象的 id 铸进本库 IRI 等于伪造身份——那份文件看着完整，
     * 实则悬空。处置一律是<b>整份拒导</b>；少导一行、换个 IRI 都不在选项里。
     */
    static AppException crossKbError(List<CrossKbViolation> violations) {
        return AppException.invalidDetail("cross_kb_provenance",
                "export refused: KB-scoped provenance points outside the knowledge base",
                detail(violations));
    }

    /** 同库但不在导出集（合并掉的实体是唯一会缺席的实体）：与越库同一处置，整份拒导。 */
    static AppException unexportedError(List<CrossKbViolation> violations) {
        return AppException.invalidDetail("unexported_target",
                "export refused: a reference points at a row that is not in this KB's exported set",
                detail(violations));
    }

    private static void tally(List<CrossKbViolation> violations, String edge, long rows) {
        if (rows > 0) {
            violations.add(new CrossKbViolation(edge, rows));
        }
    }

    /** 引用列的判定：留下的是谁就查谁。ref_kb 随行原子选出——别库、悬空（NULL）都不算本库。 */
    private static boolean foreign(UUID refKb, UUID kbId) {
        return !kbId.equals(refKb);
    }

    // =====================================================================
    // 世界轴投影（world_axis.rs facts_holds_from/facts_holds_to 逐字——GraphService 同款）
    // =====================================================================

    private static final String TEMPORAL_OF_F =
            "(SELECT r.temporal FROM relation_types r WHERE r.id = f.predicate_id)";

    /** world_axis.rs:35-42 facts_holds_from("f")：读出来的下界——原文给了起点用起点，否则从最早证据起。 */
    static String factsHoldsFrom() {
        return "CASE WHEN " + TEMPORAL_OF_F + " = 'eternal' THEN NULL "
                + "ELSE COALESCE(f.valid_from, f.attested_from) END";
    }

    /** world_axis.rs:50-67 facts_holds_to("f")：读出来的上界（事件桶/闭合/unknown 到说出结束的文档）。 */
    static String factsHoldsTo() {
        return "CASE WHEN " + TEMPORAL_OF_F + " = 'eternal' THEN NULL "
                + "WHEN " + TEMPORAL_OF_F + " = 'event' THEN "
                + "CASE WHEN f.valid_from IS NULL THEN f.attested_from "
                + "ELSE COALESCE(f.valid_to, f.valid_from) "
                + "+ ('1 ' || COALESCE(NULLIF(f.valid_to_precision, 'unknown'), "
                + "f.valid_from_precision))::interval END "
                + "WHEN f.valid_to IS NOT NULL THEN f.valid_to "
                + "WHEN f.valid_to_precision = 'unknown' THEN f.attested_to END";
    }

    // =====================================================================
    // 两道流前预检
    // =====================================================================

    private record ScanViolation(String edge, String kind, long rows) {
    }

    /**
     * 导出前的出处体检（export.rs:110-147）。逐类数一遍越界引用，有一行就整份拒导。
     * 扫的边 = 0070/0091 保护的全部结构引用边（export_provenance_integrity.sql，每条分支带
     * @edge/@filter 标记给 catalog 守卫核对）——连导出尚未序列化的边也算。必须在导出用的
     * 那条连接里跑（REPEATABLE READ）：体检与每一页查询看的是同一个快照。
     */
    public static void provenanceIntegrity(Connection conn, UUID kbId) {
        // $1 → ?：占位符方言转写，SQL 文本逐字（资源文件本身保持原样供守卫解析）。
        // 参数个数按资源文件里 $1 的出现次数计（审查 M-7）——按转写后 ? 计数会在将来
        // 出现 jsonb ? 操作符时静默错绑；另断言资源无字面 ?（有则转写不守恒，双保险）
        if (PREFLIGHT_SQL.contains("?")) {
            throw new IllegalStateException("export_provenance_integrity.sql 含字面 '?'——转写不再机械");
        }
        String sql = PREFLIGHT_SQL.replace("$1", "?");
        // 出现次数 = 长度差 / 模式宽度（"$1" 是 2 字符——首版忘了除，66 越界被测试当场抓）
        int params = (PREFLIGHT_SQL.length() - PREFLIGHT_SQL.replace("$1", "").length()) / 2;
        List<ScanViolation> violations = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= params; i++) {
                ps.setObject(i, kbId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    violations.add(new ScanViolation(rs.getString("edge"), rs.getString("kind"),
                            rs.getLong("rows")));
                }
            }
        } catch (SQLException e) {
            throw AppException.db("provenance_integrity: " + e.getMessage(), e);
        }
        List<CrossKbViolation> crossKb = violations.stream()
                .filter(v -> "cross_kb".equals(v.kind()))
                .map(v -> new CrossKbViolation(v.edge(), v.rows())).toList();
        List<CrossKbViolation> unexported = violations.stream()
                .filter(v -> "unexported".equals(v.kind()))
                .map(v -> new CrossKbViolation(v.edge(), v.rows())).toList();
        if (!crossKb.isEmpty()) {
            throw crossKbError(crossKb);
        }
        if (!unexported.isEmpty()) {
            throw unexportedError(unexported);
        }
    }

    /** detail.predicate_id 存成 JSON 文本，落到 uuid 前先验形（export.rs:865-866）。 */
    private static final String UUID_TEXT_RE =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    /**
     * open 行的判据谓词解析式（export.rs:871-878）：derived_contradiction 的判据挂在 detail
     * 记下的派生谓词上；其余种类就是 left_fact 的谓词。分页与预检共用同一串——两处各写一份
     * 迟早漂移。
     */
    static String criterionPredicateSql() {
        return "CASE WHEN v.kind = 'derived_contradiction' "
                + "THEN CASE WHEN v.detail->>'predicate_id' ~ '" + UUID_TEXT_RE + "' "
                + "THEN (v.detail->>'predicate_id')::uuid END "
                + "ELSE lf.predicate_id END";
    }

    /** open 行的判据词解析式（export.rs:881-882）。 */
    static final String CRITERION_AXIOM_SQL =
            "CASE WHEN v.kind = 'derived_contradiction' THEN v.detail->>'axiom' ELSE v.kind END";

    /**
     * 认知状态的流前预检（0062，export.rs:1001-1104）：越库/悬空、词表外的存储值、
     * status↔resolution 不自洽、open 违规判据不可解析——都在发第一个字节之前拦下。
     * 8 个 SELECT 分支（7 个 UNION ALL）聚合，GROUP BY edge, kind（审查 M-6 勘误计数）。
     */
    public static void contestIntegrity(Connection conn, UUID kbId) {
        String sql = """
                SELECT edge, kind, count(*) AS rows FROM (
                     SELECT 'conflict.old_fact' AS edge, 'cross_kb' AS kind
                       FROM fact_conflicts c LEFT JOIN facts f ON f.id = c.old_fact_id
                      WHERE c.kb_id = ? AND f.kb_id IS DISTINCT FROM c.kb_id
                     UNION ALL
                     SELECT 'conflict.new_fact', 'cross_kb'
                       FROM fact_conflicts c LEFT JOIN facts f ON f.id = c.new_fact_id
                      WHERE c.kb_id = ? AND f.kb_id IS DISTINCT FROM c.kb_id
                     UNION ALL
                     SELECT 'conflict.vocabulary', 'contract'
                       FROM fact_conflicts c
                      WHERE c.kb_id = ? AND (
                            COALESCE(c.status, '') NOT IN ('open', 'resolved', 'withdrawn')
                            OR COALESCE(c.reason, '') NOT IN
                               ('no_time', 'simultaneous', 'low_confidence', 'described_evidence')
                            OR c.resolution IS NOT NULL
                               AND c.resolution NOT IN ('closed', 'kept_both', 'rejected_new')
                            -- 0051：resolved=人裁了（resolution 必有值），其余状态不该有
                            OR c.status = 'resolved' AND c.resolution IS NULL
                            OR c.status IN ('open', 'withdrawn') AND c.resolution IS NOT NULL)
                     UNION ALL
                     SELECT 'violation.left_fact', 'cross_kb'
                       FROM axiom_violations v LEFT JOIN facts f ON f.id = v.left_fact
                      WHERE v.kb_id = ? AND f.kb_id IS DISTINCT FROM v.kb_id
                     UNION ALL
                     SELECT 'violation.right_fact', 'cross_kb'
                       FROM axiom_violations v LEFT JOIN facts f ON f.id = v.right_fact
                      WHERE v.kb_id = ? AND f.kb_id IS DISTINCT FROM v.kb_id
                     UNION ALL
                     SELECT 'violation.path', 'cross_kb'
                       FROM axiom_violations v
                      WHERE v.kb_id = ? AND EXISTS(
                            SELECT 1 FROM unnest(v.path) AS member(fact_id)
                             WHERE NOT EXISTS(SELECT 1 FROM facts pf
                                               WHERE pf.id = member.fact_id
                                                 AND pf.kb_id = v.kb_id))
                     UNION ALL
                     SELECT 'violation.vocabulary', 'contract'
                       FROM axiom_violations v
                      WHERE v.kb_id = ? AND (
                            COALESCE(v.kind, '') NOT IN
                               ('self_loop', 'asymmetry', 'cycle', 'functional',
                                'inverse_functional', 'signature', 'derived_contradiction')
                            OR COALESCE(v.status, '') NOT IN ('open', 'resolved')
                            OR v.resolution IS NOT NULL AND v.resolution NOT IN
                               ('fact_retracted', 'fact_closed', 'axiom_relaxed', 'accepted',
                                'criterion_changed')
                            -- 写路从来都是成对落（决定+时间戳）；open 带着 resolution
                            -- 或 resolved 丢了 resolution 都是自相矛盾的脏行
                            OR v.status = 'resolved' AND v.resolution IS NULL
                            OR v.status = 'open' AND v.resolution IS NOT NULL)
                     UNION ALL
                     SELECT 'violation.criterion', 'unresolvable'
                       FROM axiom_violations v
                       LEFT JOIN facts lf ON lf.id = v.left_fact
                      WHERE v.kb_id = ? AND v.status = 'open' AND (
                            COALESCE(%s, '') NOT IN
                               ('self_loop', 'asymmetry', 'cycle', 'functional',
                                'inverse_functional', 'signature')
                            OR NOT EXISTS(
                                SELECT 1 FROM relation_types rt
                                 WHERE rt.kb_id = v.kb_id
                                   AND rt.id = %s))
                ) t GROUP BY edge, kind
                """.formatted(CRITERION_AXIOM_SQL, criterionPredicateSql());
        List<ScanViolation> violations = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 1; i <= 8; i++) {
                ps.setObject(i, kbId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    violations.add(new ScanViolation(rs.getString("edge"), rs.getString("kind"),
                            rs.getLong("rows")));
                }
            }
        } catch (SQLException e) {
            throw AppException.db("contest_integrity: " + e.getMessage(), e);
        }
        List<CrossKbViolation> crossKb = violations.stream()
                .filter(v -> "cross_kb".equals(v.kind()))
                .map(v -> new CrossKbViolation(v.edge(), v.rows())).toList();
        if (!crossKb.isEmpty()) {
            throw crossKbError(crossKb);
        }
        List<CrossKbViolation> rest = violations.stream()
                .filter(v -> !"cross_kb".equals(v.kind()))
                .map(v -> new CrossKbViolation(v.edge(), v.rows())).toList();
        if (!rest.isEmpty()) {
            throw AppException.invalidDetail("contested_state_contract",
                    "export refused: contested-state rows fall outside the contract vocabulary"
                            + " or an open violation has no resolvable criterion",
                    detail(rest));
        }
    }

    /**
     * kind / detail.axiom 的存储值 → 判据该用的词（0062 §4，export.rs:887-897）。
     * signature 的判据是一对签名（行不记哪一侧破的，合同也不装知道）。
     * null = 不认识的存储值——open 行撞上它就要拒导，不能静默丢判据。
     */
    static String[] criterionTermsOf(String axiom) {
        if (axiom == null) {
            return null;
        }
        return switch (axiom) {
            case "self_loop" -> new String[]{"owl:IrreflexiveProperty"};
            case "asymmetry" -> new String[]{"owl:AsymmetricProperty"};
            case "cycle" -> new String[]{"owl:TransitiveProperty"};
            case "functional" -> new String[]{"owl:FunctionalProperty"};
            case "inverse_functional" -> new String[]{"owl:InverseFunctionalProperty"};
            case "signature" -> new String[]{"rdfs:domain", "rdfs:range"};
            default -> null;
        };
    }

    // =====================================================================
    // 八族分页取数
    // =====================================================================

    /** ① classes（全量，export.rs:301-332）。parents/disjoint 不在本库词汇表 → 拒导。 */
    public static List<ExportClass> classes(Connection conn, UUID kbId) {
        List<ExportClass> classes = new ArrayList<>();
        String sql = """
                SELECT t.id, t.key, t.label, t.description, t.iri,
                       COALESCE(ARRAY(SELECT p.parent_id FROM entity_type_parents p
                                       WHERE p.child_id = t.id ORDER BY p.parent_id), '{}') AS parents,
                       COALESCE(ARRAY(SELECT CASE WHEN d.a_id = t.id THEN d.b_id ELSE d.a_id END
                                        FROM entity_type_disjoint d
                                        WHERE d.kb_id = ? AND (d.a_id = t.id OR d.b_id = t.id)
                                        ORDER BY 1), '{}') AS disjoint
                  FROM entity_types t WHERE t.kb_id = ? ORDER BY t.key
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, kbId);
            ps.setObject(2, kbId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    classes.add(new ExportClass(getUuid(rs, "id"), rs.getString("key"),
                            rs.getString("label"), rs.getString("description"),
                            rs.getString("iri"), uuidArray(rs, "parents"),
                            uuidArray(rs, "disjoint")));
                }
            }
        } catch (SQLException e) {
            throw AppException.db("export classes: " + e.getMessage(), e);
        }
        // 父类与互斥类稍后要进本库词汇表按 id 查——查不着就是被静默丢掉。
        // 能解析的就地解析：词汇表全集就在手里，不在集合里的引用就是越库/悬空
        Set<UUID> own = new HashSet<>();
        for (ExportClass c : classes) {
            own.add(c.id());
        }
        List<CrossKbViolation> violations = new ArrayList<>();
        for (ExportClass c : classes) {
            long badParents = c.parents().stream().filter(p -> !own.contains(p)).count();
            long badDisjoint = c.disjoint().stream().filter(p -> !own.contains(p)).count();
            tally(violations, "class.parent", badParents);
            tally(violations, "class.disjoint", badDisjoint);
        }
        if (!violations.isEmpty()) {
            throw crossKbError(violations);
        }
        return classes;
    }

    /** ② relations（全量，export.rs:334-388）。校验 relation.domain/range/inverse/sub_property。 */
    public static List<ExportRelation> relations(Connection conn, UUID kbId) {
        List<ExportRelation> relations = new ArrayList<>();
        String sql = """
                SELECT r.id, r.key, r.label, r.description, r.iri, r.kind, r.datatype, r.unit,
                       r.temporal, r.functional, r.inverse_functional,
                       r.is_transitive, r.is_symmetric, r.is_asymmetric, r.is_irreflexive,
                       r.inverse_of, r.sub_property_of,
                       COALESCE(ARRAY(SELECT d.entity_type_id FROM relation_type_domains d
                                       WHERE d.relation_type_id = r.id ORDER BY 1), '{}') AS domains,
                       COALESCE(ARRAY(SELECT g.entity_type_id FROM relation_type_ranges g
                                       WHERE g.relation_type_id = r.id ORDER BY 1), '{}') AS ranges
                  FROM relation_types r WHERE r.kb_id = ? ORDER BY r.key
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, kbId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    relations.add(new ExportRelation(getUuid(rs, "id"), rs.getString("key"),
                            rs.getString("label"), rs.getString("description"), rs.getString("iri"),
                            rs.getString("kind"), rs.getString("datatype"), rs.getString("unit"),
                            rs.getString("temporal"), rs.getBoolean("functional"),
                            rs.getBoolean("inverse_functional"), rs.getBoolean("is_transitive"),
                            rs.getBoolean("is_symmetric"), rs.getBoolean("is_asymmetric"),
                            rs.getBoolean("is_irreflexive"), getUuid(rs, "inverse_of"),
                            getUuid(rs, "sub_property_of"), uuidArray(rs, "domains"),
                            uuidArray(rs, "ranges")));
                }
            }
        } catch (SQLException e) {
            throw AppException.db("export relations: " + e.getMessage(), e);
        }
        // domain/range 的类 id 与 inverse/sub_property 的关系 id 都要进本库词汇表查——
        // 查不着就静默丢公理。两行全集都在手里，不在集合里的引用就是越库/悬空
        Set<UUID> own;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(ARRAY(SELECT id FROM entity_types WHERE kb_id = ?), '{}')")) {
            ps.setObject(1, kbId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                own = new HashSet<>(uuidArray(rs, 1));
            }
        } catch (SQLException e) {
            throw AppException.db("export relations own types: " + e.getMessage(), e);
        }
        Set<UUID> ownRel = new HashSet<>();
        for (ExportRelation r : relations) {
            ownRel.add(r.id());
        }
        List<CrossKbViolation> violations = new ArrayList<>();
        for (ExportRelation r : relations) {
            tally(violations, "relation.domain",
                    r.domains().stream().filter(t -> !own.contains(t)).count());
            tally(violations, "relation.range",
                    r.ranges().stream().filter(t -> !own.contains(t)).count());
            if (r.inverseOf() != null) {
                tally(violations, "relation.inverse", !ownRel.contains(r.inverseOf()) ? 1 : 0);
            }
            if (r.subPropertyOf() != null) {
                tally(violations, "relation.sub_property", !ownRel.contains(r.subPropertyOf()) ? 1 : 0);
            }
        }
        if (!violations.isEmpty()) {
            throw crossKbError(violations);
        }
        return relations;
    }

    /** ③ entities_page（export.rs:391-418）：合并掉的实体不导出；entity.type 越库拒导。 */
    public static List<ExportEntity> entitiesPage(Connection conn, UUID kbId, UUID after) {
        List<ExportEntity> page = new ArrayList<>();
        String sql = """
                SELECT e.id, e.canonical_name, e.type_id, t.kb_id AS type_kb
                   FROM entities e LEFT JOIN entity_types t ON t.id = e.type_id
                  WHERE e.kb_id = ? AND e.merged_into IS NULL
                    AND (?::uuid IS NULL OR e.id > ?::uuid)
                  ORDER BY e.id LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, kbId);
            ps.setObject(2, after);
            ps.setObject(3, after);
            ps.setInt(4, PAGE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    page.add(new ExportEntity(getUuid(rs, "id"), rs.getString("canonical_name"),
                            getUuid(rs, "type_id"), getUuid(rs, "type_kb")));
                }
            }
        } catch (SQLException e) {
            throw AppException.db("export entities_page: " + e.getMessage(), e);
        }
        List<CrossKbViolation> violations = new ArrayList<>();
        for (ExportEntity e : page) {
            if (e.typeId() != null && foreign(e.typeKb(), kbId)) {
                tally(violations, "entity.type", 1);
            }
        }
        if (!violations.isEmpty()) {
            throw crossKbError(violations);
        }
        return page;
    }

    /**
     * ④ facts_page（export.rs:422-538，最重的一族）。<b>不过滤 invalidated_at</b>——撤回的、
     * 被修正顶掉的、区间早已闭合的全在里面：它们各自带着两根轴上的时刻，读的人自己判断
     * 当时成立不成立。随后按页补 0037 边上属性（qualifiers_for_export 的 LEFT JOIN 与界面版
     * 刻意不同——导出不能静默吞行）。
     */
    public static List<ExportFact> factsPage(Connection conn, UUID kbId, UUID after) {
        String sql = """
                SELECT f.id, f.subject_id, f.predicate_id,
                       fact_surface_predicate(f.id) AS surface_predicate,
                       f.object_id, f.object_value,
                       f.valid_from, f.valid_from_precision, f.valid_to, f.valid_to_precision,
                       %s AS holds_from, %s AS holds_to,
                       f.recorded_at, f.invalidated_at, f.confidence, f.implied, f.supersedes,
                       COALESCE(ARRAY(SELECT DISTINCT e.document_id FROM fact_evidence e
                                       WHERE e.fact_id = f.id AND e.document_id IS NOT NULL), '{}')
                         AS documents,
                       COALESCE(ARRAY(SELECT e.quote FROM fact_evidence e
                                       WHERE e.fact_id = f.id AND e.quote IS NOT NULL
                                       ORDER BY e.chunk_id), '{}') AS quotes,
                       COALESCE(ARRAY(SELECT DISTINCT c.origin FROM fact_evidence e
                                       JOIN chunks c ON c.id = e.chunk_id
                                       WHERE e.fact_id = f.id ORDER BY c.origin), '{}')
                         AS quote_origins,
                       s.kb_id AS subject_kb, o.kb_id AS object_kb,
                       p.kb_id AS predicate_kb, sp.kb_id AS supersedes_kb,
                       EXISTS(SELECT 1 FROM fact_evidence e
                               LEFT JOIN documents ed ON ed.id = e.document_id
                               WHERE e.fact_id = f.id AND e.document_id IS NOT NULL
                                 AND ed.kb_id IS DISTINCT FROM f.kb_id) AS foreign_document,
                       EXISTS(SELECT 1 FROM fact_evidence e
                               LEFT JOIN chunks ec ON ec.id = e.chunk_id
                               WHERE e.fact_id = f.id
                                 AND ec.kb_id IS DISTINCT FROM f.kb_id) AS foreign_chunk,
                       (s.merged_into IS NOT NULL) AS subject_merged,
                       (o.merged_into IS NOT NULL) AS object_merged
                  FROM facts f
                  LEFT JOIN entities s ON s.id = f.subject_id
                  LEFT JOIN entities o ON o.id = f.object_id
                  LEFT JOIN relation_types p ON p.id = f.predicate_id
                  LEFT JOIN facts sp ON sp.id = f.supersedes
                 WHERE f.kb_id = ? AND (?::uuid IS NULL OR f.id > ?::uuid)
                 ORDER BY f.id LIMIT ?
                """.formatted(factsHoldsFrom(), factsHoldsTo());
        List<ExportFact> facts = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, kbId);
            ps.setObject(2, after);
            ps.setObject(3, after);
            ps.setInt(4, PAGE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    facts.add(new ExportFact(getUuid(rs, "id"), getUuid(rs, "subject_id"),
                            getUuid(rs, "predicate_id"), rs.getString("surface_predicate"),
                            getUuid(rs, "object_id"), jsonOrNull(rs, "object_value"),
                            List.of(), at(rs, "valid_from"), rs.getString("valid_from_precision"),
                            at(rs, "valid_to"), rs.getString("valid_to_precision"),
                            at(rs, "holds_from"), at(rs, "holds_to"),
                            at(rs, "recorded_at"), at(rs, "invalidated_at"),
                            rs.getFloat("confidence"), rs.getBoolean("implied"),
                            getUuid(rs, "supersedes"), uuidArray(rs, "documents"),
                            strArray(rs, "quotes"), strArray(rs, "quote_origins"),
                            getUuid(rs, "subject_kb"), getUuid(rs, "object_kb"),
                            getUuid(rs, "predicate_kb"), getUuid(rs, "supersedes_kb"),
                            rs.getBoolean("foreign_document"), rs.getBoolean("foreign_chunk"),
                            rs.getBoolean("subject_merged"), rs.getBoolean("object_merged")));
                }
            }
        } catch (SQLException e) {
            throw AppException.db("export facts_page: " + e.getMessage(), e);
        }
        // 留下的行逐个过：被铸成本库 IRI 的引用不许越库，进词汇表的引用不许查空。
        // 检查用的是随行原子选出的 ref_kb——不是再去库里问一次的另一个时刻
        List<CrossKbViolation> violations = new ArrayList<>();
        List<CrossKbViolation> unexported = new ArrayList<>();
        for (ExportFact f : facts) {
            tally(violations, "fact.subject", foreign(f.subjectKb(), kbId) ? 1 : 0);
            tally(unexported, "fact.subject(merged)", f.subjectMerged() ? 1 : 0);
            if (f.objectId() != null) {
                tally(violations, "fact.object", foreign(f.objectKb(), kbId) ? 1 : 0);
                tally(unexported, "fact.object(merged)", f.objectMerged() ? 1 : 0);
            }
            if (f.predicateId() != null) {
                tally(violations, "fact.predicate", foreign(f.predicateKb(), kbId) ? 1 : 0);
            }
            if (f.supersedes() != null) {
                tally(violations, "fact.supersedes", foreign(f.supersedesKb(), kbId) ? 1 : 0);
            }
            tally(violations, "evidence.document", f.foreignDocument() ? 1 : 0);
            tally(violations, "evidence.chunk", f.foreignChunk() ? 1 : 0);
        }
        if (!violations.isEmpty()) {
            throw crossKbError(violations);
        }
        if (!unexported.isEmpty()) {
            throw unexportedError(unexported);
        }
        // 边上的属性另一张表（0037），按事实 id 一次取回补上——把属性类型的 kb 与实体值的
        // kb 一并选出：别库类型会被词汇表静默跳过，别库实体会被铸进本库 IRI，
        // 两种行都得在序列化之前拦下来
        qualifiersForExport(conn, facts.stream().map(ExportFact::id).toList(), kbId)
                .forEach((factId, qs) -> {
                    for (int i = 0; i < facts.size(); i++) {
                        if (facts.get(i).id().equals(factId)) {
                            ExportFact f = facts.get(i);
                            facts.set(i, new ExportFact(f.id(), f.subjectId(), f.predicateId(),
                                    f.surfacePredicate(), f.objectId(), f.objectValue(), qs,
                                    f.validFrom(), f.validFromPrecision(), f.validTo(),
                                    f.validToPrecision(), f.holdsFrom(), f.holdsTo(),
                                    f.recordedAt(), f.invalidatedAt(), f.confidence(), f.implied(),
                                    f.supersedes(), f.documents(), f.quotes(), f.quoteOrigins(),
                                    f.subjectKb(), f.objectKb(), f.predicateKb(), f.supersedesKb(),
                                    f.foreignDocument(), f.foreignChunk(), f.subjectMerged(),
                                    f.objectMerged()));
                            break;
                        }
                    }
                });
        return facts;
    }

    /** qualifiers_for_export（export.rs:558-623）：LEFT JOIN 与界面版刻意不同——导出不能静默吞行。 */
    private static Map<UUID, List<FactQualifier>> qualifiersForExport(Connection conn,
                                                                      List<UUID> factIds,
                                                                      UUID kbId) {
        Map<UUID, List<FactQualifier>> out = new LinkedHashMap<>();
        if (factIds.isEmpty()) {
            return out;
        }
        record Row(UUID factId, UUID qualifierTypeId, String key, String label, JsonNode value,
                   UUID entityId, String entityName, UUID typeKb, UUID entityKb,
                   boolean entityMerged) {
        }
        String sql = """
                SELECT q.fact_id, q.qualifier_type_id, r.key, r.label, q.value, q.entity_id,
                       e.canonical_name AS entity_name,
                       r.kb_id AS type_kb, e.kb_id AS entity_kb,
                       (e.merged_into IS NOT NULL) AS entity_merged
                  FROM fact_qualifiers q
                  LEFT JOIN relation_types r ON r.id = q.qualifier_type_id
                  LEFT JOIN entities e ON e.id = q.entity_id
                  WHERE q.fact_id = ANY(?)
                  ORDER BY q.fact_id, r.key
                """;
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setArray(1, conn.createArrayOf("uuid", factIds.toArray(new UUID[0])));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(getUuid(rs, "fact_id"), getUuid(rs, "qualifier_type_id"),
                            rs.getString("key"), rs.getString("label"), jsonOrNull(rs, "value"),
                            getUuid(rs, "entity_id"), rs.getString("entity_name"),
                            getUuid(rs, "type_kb"), getUuid(rs, "entity_kb"),
                            rs.getBoolean("entity_merged")));
                }
            }
        } catch (SQLException e) {
            throw AppException.db("export qualifiers: " + e.getMessage(), e);
        }
        List<CrossKbViolation> violations = new ArrayList<>();
        List<CrossKbViolation> unexported = new ArrayList<>();
        for (Row r : rows) {
            tally(violations, "qualifier.type", foreign(r.typeKb(), kbId) ? 1 : 0);
            if (r.entityId() != null) {
                tally(violations, "qualifier.entity", foreign(r.entityKb(), kbId) ? 1 : 0);
                tally(unexported, "qualifier.entity(merged)", r.entityMerged() ? 1 : 0);
            }
        }
        if (!violations.isEmpty()) {
            throw crossKbError(violations);
        }
        if (!unexported.isEmpty()) {
            throw unexportedError(unexported);
        }
        for (Row r : rows) {
            out.computeIfAbsent(r.factId(), k -> new ArrayList<>())
                    .add(new FactQualifier(r.qualifierTypeId(),
                            // 过了校验 r 必然在：key/label 不会取不到
                            r.key() == null ? "" : r.key(), r.label() == null ? "" : r.label(),
                            r.value(), r.entityId(), r.entityName()));
        }
        return out;
    }

    /**
     * ⑤ derived_page（export.rs:625-737）。<b>两个 LEFT JOIN</b>：表拓宽之后（0021）派生可能
     * 没有实体宾语、也可能来自业务规则而不是公理——内连接会把这类结论整条挡在导出之外，
     * 而 0020 承诺的正是「审计员不靠我们也能读全」。
     */
    public static List<ExportDerived> derivedPage(Connection conn, UUID kbId, UUID after) {
        String sql = """
                SELECT d.id, d.subject_id, d.predicate_id, d.object_id, d.object_value,
                       d.rule_id, d.attribute_rule_id,
                       d.valid_from, d.valid_from_precision, d.valid_to, d.valid_to_precision,
                       d.derived_at, d.invalidated_at, d.confidence,
                       COALESCE(ru.kind, 'business') AS rule, ru.predicate_id AS rule_predicate,
                       ar.name AS rule_name,
                       COALESCE(ARRAY(SELECT fd.premise_fact_id FROM fact_derivations fd
                                       WHERE fd.derived_fact_id = d.id
                                         AND fd.premise_fact_id IS NOT NULL
                                       ORDER BY fd.seq), '{}') AS premises,
                       COALESCE(ARRAY(SELECT fd.premise_derived_id FROM fact_derivations fd
                                       WHERE fd.derived_fact_id = d.id
                                         AND fd.premise_derived_id IS NOT NULL
                                       ORDER BY fd.seq), '{}') AS premises_derived,
                       s.kb_id AS subject_kb, o.kb_id AS object_kb, p.kb_id AS predicate_kb,
                       ru.kb_id AS rule_kb, ar.kb_id AS attribute_rule_kb,
                       EXISTS(SELECT 1 FROM fact_derivations fd
                               LEFT JOIN facts pf ON pf.id = fd.premise_fact_id
                               WHERE fd.derived_fact_id = d.id AND fd.premise_fact_id IS NOT NULL
                                 AND pf.kb_id IS DISTINCT FROM d.kb_id) AS foreign_fact_premise,
                       EXISTS(SELECT 1 FROM fact_derivations fd
                               LEFT JOIN derived_facts pd ON pd.id = fd.premise_derived_id
                               WHERE fd.derived_fact_id = d.id AND fd.premise_derived_id IS NOT NULL
                                 AND pd.kb_id IS DISTINCT FROM d.kb_id) AS foreign_derived_premise,
                       (s.merged_into IS NOT NULL) AS subject_merged,
                       (o.merged_into IS NOT NULL) AS object_merged
                  FROM derived_facts d
                  LEFT JOIN rules ru ON ru.id = d.rule_id
                  LEFT JOIN attribute_rules ar ON ar.id = d.attribute_rule_id
                  LEFT JOIN entities s ON s.id = d.subject_id
                  LEFT JOIN entities o ON o.id = d.object_id
                  LEFT JOIN relation_types p ON p.id = d.predicate_id
                 WHERE d.kb_id = ? AND (?::uuid IS NULL OR d.id > ?::uuid)
                 ORDER BY d.id LIMIT ?
                """;
        List<ExportDerived> page = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, kbId);
            ps.setObject(2, after);
            ps.setObject(3, after);
            ps.setInt(4, PAGE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    page.add(new ExportDerived(getUuid(rs, "id"), getUuid(rs, "subject_id"),
                            getUuid(rs, "predicate_id"), getUuid(rs, "object_id"),
                            jsonOrNull(rs, "object_value"), getUuid(rs, "rule_id"),
                            getUuid(rs, "attribute_rule_id"), at(rs, "valid_from"),
                            rs.getString("valid_from_precision"), at(rs, "valid_to"),
                            rs.getString("valid_to_precision"), at(rs, "derived_at"),
                            at(rs, "invalidated_at"), rs.getFloat("confidence"),
                            rs.getString("rule"), getUuid(rs, "rule_predicate"),
                            rs.getString("rule_name"), uuidArray(rs, "premises"),
                            uuidArray(rs, "premises_derived"), getUuid(rs, "subject_kb"),
                            getUuid(rs, "object_kb"), getUuid(rs, "predicate_kb"),
                            getUuid(rs, "rule_kb"), getUuid(rs, "attribute_rule_kb"),
                            rs.getBoolean("foreign_fact_premise"),
                            rs.getBoolean("foreign_derived_premise"), rs.getBoolean("subject_merged"),
                            rs.getBoolean("object_merged")));
                }
            }
        } catch (SQLException e) {
            throw AppException.db("export derived_page: " + e.getMessage(), e);
        }
        List<CrossKbViolation> violations = new ArrayList<>();
        List<CrossKbViolation> unexported = new ArrayList<>();
        for (ExportDerived d : page) {
            tally(violations, "derived.subject", foreign(d.subjectKb(), kbId) ? 1 : 0);
            tally(unexported, "derived.subject(merged)", d.subjectMerged() ? 1 : 0);
            if (d.objectId() != null) {
                tally(violations, "derived.object", foreign(d.objectKb(), kbId) ? 1 : 0);
                tally(unexported, "derived.object(merged)", d.objectMerged() ? 1 : 0);
            }
            // 派生的谓词非空（CHECK 保证），NULL 的 ref_kb 一样是越界/悬空
            tally(violations, "derived.predicate", foreign(d.predicateKb(), kbId) ? 1 : 0);
            if (d.ruleId() != null) {
                tally(violations, "derived.rule", foreign(d.ruleKb(), kbId) ? 1 : 0);
            }
            if (d.attributeRuleId() != null) {
                tally(violations, "derived.attribute_rule", foreign(d.attributeRuleKb(), kbId) ? 1 : 0);
            }
            tally(violations, "derivation.premise_fact", d.foreignFactPremise() ? 1 : 0);
            tally(violations, "derivation.premise_derived", d.foreignDerivedPremise() ? 1 : 0);
        }
        if (!violations.isEmpty()) {
            throw crossKbError(violations);
        }
        if (!unexported.isEmpty()) {
            throw unexportedError(unexported);
        }
        return page;
    }

    /** ⑥ documents_page（export.rs:739-755）：删掉的文档留墓碑照导。 */
    public static List<ExportDocument> documentsPage(Connection conn, UUID kbId, UUID after) {
        String sql = """
                SELECT id, filename, external_key, doc_time, created_at, deleted_at
                   FROM documents
                  WHERE kb_id = ? AND (?::uuid IS NULL OR id > ?::uuid)
                  ORDER BY id LIMIT ?
                """;
        List<ExportDocument> page = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, kbId);
            ps.setObject(2, after);
            ps.setObject(3, after);
            ps.setInt(4, PAGE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    page.add(new ExportDocument(getUuid(rs, "id"), rs.getString("filename"),
                            rs.getString("external_key"), at(rs, "doc_time"), at(rs, "created_at"),
                            at(rs, "deleted_at")));
                }
            }
        } catch (SQLException e) {
            throw AppException.db("export documents_page: " + e.getMessage(), e);
        }
        return page;
    }

    /** ⑦ fact_conflicts_page（export.rs:789-826，0062 认知状态：只读，不走惰性清理读面）。 */
    public static List<ExportFactConflict> factConflictsPage(Connection conn, UUID kbId, UUID after) {
        String sql = """
                SELECT c.id, c.old_fact_id, c.new_fact_id, c.reason, c.created_at,
                       c.status, c.resolution, c.resolved_at,
                       of_.kb_id AS old_fact_kb, nf.kb_id AS new_fact_kb
                   FROM fact_conflicts c
                   LEFT JOIN facts of_ ON of_.id = c.old_fact_id
                   LEFT JOIN facts nf ON nf.id = c.new_fact_id
                  WHERE c.kb_id = ? AND (?::uuid IS NULL OR c.id > ?::uuid)
                  ORDER BY c.id LIMIT ?
                """;
        List<ExportFactConflict> page = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, kbId);
            ps.setObject(2, after);
            ps.setObject(3, after);
            ps.setInt(4, PAGE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    page.add(new ExportFactConflict(getUuid(rs, "id"), getUuid(rs, "old_fact_id"),
                            getUuid(rs, "new_fact_id"), rs.getString("reason"), at(rs, "created_at"),
                            rs.getString("status"), rs.getString("resolution"), at(rs, "resolved_at"),
                            getUuid(rs, "old_fact_kb"), getUuid(rs, "new_fact_kb")));
                }
            }
        } catch (SQLException e) {
            throw AppException.db("export fact_conflicts_page: " + e.getMessage(), e);
        }
        List<CrossKbViolation> violations = new ArrayList<>();
        for (ExportFactConflict c : page) {
            tally(violations, "conflict.old_fact", foreign(c.oldFactKb(), kbId) ? 1 : 0);
            tally(violations, "conflict.new_fact", foreign(c.newFactKb(), kbId) ? 1 : 0);
        }
        if (!violations.isEmpty()) {
            throw crossKbError(violations);
        }
        return page;
    }

    /** ⑧ axiom_violations_page（export.rs:907-986）：criterion 两段 SQL 片段与 contest_integrity 共用。 */
    public static List<ExportAxiomViolation> axiomViolationsPage(Connection conn, UUID kbId,
                                                                 UUID after) {
        String sql = """
                SELECT v.id, v.kind, v.left_fact, v.right_fact, v.path, v.status, v.resolution,
                       v.detected_at, v.decided_at,
                       CASE WHEN v.status = 'open' THEN %s
                       END AS criterion_predicate,
                       CASE WHEN v.status = 'open' THEN %s
                       END AS criterion_axiom,
                       lf.kb_id AS left_kb, rf.kb_id AS right_kb, cp.kb_id AS criterion_predicate_kb,
                       EXISTS(SELECT 1 FROM unnest(v.path) AS member(fact_id)
                               WHERE NOT EXISTS(SELECT 1 FROM facts pf
                                                 WHERE pf.id = member.fact_id
                                                   AND pf.kb_id = v.kb_id)) AS foreign_path
                  FROM axiom_violations v
                  LEFT JOIN facts lf ON lf.id = v.left_fact
                  LEFT JOIN facts rf ON rf.id = v.right_fact
                  LEFT JOIN relation_types cp ON cp.id =
                       CASE WHEN v.status = 'open' THEN %s END
                 WHERE v.kb_id = ? AND (?::uuid IS NULL OR v.id > ?::uuid)
                 ORDER BY v.id LIMIT ?
                """.formatted(criterionPredicateSql(), CRITERION_AXIOM_SQL, criterionPredicateSql());
        List<ExportAxiomViolation> page = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, kbId);
            ps.setObject(2, after);
            ps.setObject(3, after);
            ps.setInt(4, PAGE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    page.add(new ExportAxiomViolation(getUuid(rs, "id"), rs.getString("kind"),
                            getUuid(rs, "left_fact"), getUuid(rs, "right_fact"),
                            uuidArray(rs, "path"), rs.getString("status"), rs.getString("resolution"),
                            at(rs, "detected_at"), at(rs, "decided_at"),
                            getUuid(rs, "criterion_predicate"), rs.getString("criterion_axiom"),
                            getUuid(rs, "left_kb"), getUuid(rs, "right_kb"),
                            getUuid(rs, "criterion_predicate_kb"), rs.getBoolean("foreign_path")));
                }
            }
        } catch (SQLException e) {
            throw AppException.db("export axiom_violations_page: " + e.getMessage(), e);
        }
        List<CrossKbViolation> violations = new ArrayList<>();
        List<CrossKbViolation> unresolvable = new ArrayList<>();
        for (ExportAxiomViolation v : page) {
            tally(violations, "violation.left_fact", foreign(v.leftKb(), kbId) ? 1 : 0);
            tally(violations, "violation.right_fact", foreign(v.rightKb(), kbId) ? 1 : 0);
            tally(violations, "violation.path", v.foreignPath() ? 1 : 0);
            if ("open".equals(v.status())) {
                // open 的承诺是「已按当前本体对账」：判据指不回去，这份导出就在替一个坏掉的
                // 不变式背书——拒导与越库同一条纪律
                if (v.criterionPredicate() == null || v.criterionTerms() == null) {
                    unresolvable.add(new CrossKbViolation("violation.criterion", 1));
                } else {
                    tally(violations, "violation.criterion",
                            foreign(v.criterionPredicateKb(), kbId) ? 1 : 0);
                }
            }
        }
        if (!violations.isEmpty()) {
            throw crossKbError(violations);
        }
        if (!unresolvable.isEmpty()) {
            throw AppException.invalidDetail("unresolvable_criterion",
                    "export refused: an open axiom violation has no governing criterion to link",
                    detail(unresolvable));
        }
        return page;
    }

    // =====================================================================
    // JDBC 小工具
    // =====================================================================

    private static UUID getUuid(ResultSet rs, String column) throws SQLException {
        Object v = rs.getObject(column);
        return v == null ? null : (UUID) v;
    }

    /** timestamptz 统一折 UTC（sqlx 解码 DateTime&lt;Utc&gt; 的对等——字面量格式化假定 UTC 时钟）。 */
    private static OffsetDateTime at(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static List<UUID> uuidArray(ResultSet rs, String column) throws SQLException {
        return uuidArray(rs, rs.findColumn(column));
    }

    private static List<UUID> uuidArray(ResultSet rs, int index) throws SQLException {
        Array arr = rs.getArray(index);
        if (arr == null) {
            return List.of();
        }
        Object raw = arr.getArray();
        arr.free();
        if (raw instanceof UUID[] uuids) {
            return List.of(uuids);
        }
        if (raw instanceof Object[] objs) {
            List<UUID> out = new ArrayList<>(objs.length);
            for (Object o : objs) {
                out.add((UUID) o);
            }
            return out;
        }
        return List.of();
    }

    private static List<String> strArray(ResultSet rs, String column) throws SQLException {
        Array arr = rs.getArray(column);
        if (arr == null) {
            return List.of();
        }
        Object raw = arr.getArray();
        arr.free();
        if (raw instanceof String[] strs) {
            return List.of(strs);
        }
        if (raw instanceof Object[] objs) {
            List<String> out = new ArrayList<>(objs.length);
            for (Object o : objs) {
                out.add(o == null ? null : o.toString());
            }
            return out;
        }
        return List.of();
    }

    private static JsonNode jsonOrNull(ResultSet rs, String column) throws SQLException {
        Object v = rs.getObject(column);
        if (v == null) {
            return null;
        }
        try {
            return JSON.readTree(v.toString());
        } catch (IOException e) {
            throw AppException.db("jsonb 列 " + column + " 解析失败", e);
        }
    }
}
