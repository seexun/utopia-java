package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.import_.ImportPlanner;
import tech.seexun.jknow.web.knowledge.import_.ImportPlanner.Applied;
import tech.seexun.jknow.web.knowledge.import_.ImportPlanner.Disposition;
import tech.seexun.jknow.web.knowledge.import_.ImportPlanner.Planned;
import tech.seexun.jknow.web.knowledge.import_.ImportPlanner.PlannedItem;
import tech.seexun.jknow.web.knowledge.ingest.BlobStore;
import tech.seexun.jknow.store.OntologyService;

/**
 * OWL 导入的落库路径（owl_import.rs 的 plan/apply/relink 打真库 + tests/store/
 * adopting_an_iri_adopts_the_shape.rs 逐字）：预览与落库同一计划、Disposition 状态机、
 * 批量落库顺序、blob 先存、重导入认得出自己。自建自拆。
 */
class OwlImportDbTests extends StoreTestBase {

    @Autowired
    ImportPlanner importer;
    @Autowired
    OntologyService ontology;
    @Autowired
    BlobStore blobs;
    @Autowired
    JdbcTemplate jdbc;

    static UUID org;
    static UUID ws;
    static UUID actor;

    @BeforeAll
    void seed() {
        org = Uuids.nowV7();
        ws = Uuids.nowV7();
        actor = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'owl-import-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'owl-import-test')",
                ws, org);
        jdbc.update("""
                CALL jknow_test_user(?, ?, 'owl-import@test', 'Importer')
                """, actor, org);
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    /** 每例一座自拆的库（Rust 各测试各自 fixture；类内互不污染计数断言）。 */
    private UUID freshKb() {
        UUID kb = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'owl-import-test')",
                kb, ws);
        return kb;
    }

    private void dropKb(UUID kb) {
        jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", kb.toString());
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
    }

    private static final String TTL = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix ex: <http://acme.example/hr#> .

            ex:Employee a owl:Class ;
                rdfs:label "Employee" ;
                rdfs:comment "A person on the payroll." ;
                rdfs:subClassOf ex:Person .
            ex:Person a owl:Class ; rdfs:label "Person" .
            ex:Contractor a owl:Class .
            ex:Employee owl:disjointWith ex:Contractor .
            ex:hasManager a owl:ObjectProperty, owl:FunctionalProperty ;
                rdfs:label "has manager" ;
                rdfs:domain ex:Employee ; rdfs:range ex:Person .
            ex:employs a owl:ObjectProperty ; owl:inverseOf ex:hasManager .
            ex:salary a owl:DatatypeProperty ;
                rdfs:domain ex:Employee ; rdfs:range <http://www.w3.org/2001/XMLSchema#integer> .
            """;

    private static byte[] ttl() {
        return TTL.getBytes(StandardCharsets.UTF_8);
    }

    /** plan 与 apply 同源：apply 的结果与 plan 的处置逐项对得上，重导入认得出自己。 */
    @Test
    void planAndApplyAgreeAndReimportUpdatesInPlace() throws java.io.IOException {
        UUID kb = freshKb();
        try {
        Planned planned = importer.plan(kb, "hr.ttl", ttl());
        assertEquals("turtle", planned.plan().format());
        assertEquals(3, planned.plan().classes().size());
        // 批量序的承诺：新建的类/关系/属性各就各位（apply 后逐项核对）
        Applied first = importer.apply(kb, actor, "hr.ttl", ttl());
        assertEquals(first.plan().classes(), planned.plan().classes());

        var eTypes = ontology.entityTypesWithIri(kb);
        assertEquals(3, eTypes.size());
        for (var t : eTypes) {
            assertNotNull(t.iri(), t.key() + " 带 IRI 落库");
        }
        // blob 先存（内容寻址），原文拿得回来
        byte[] stored = blobs.get(ImportPlanner.sha256Hex(ttl()));
        assertEquals(TTL, new String(stored, StandardCharsets.UTF_8));
        // 记账：ontology_imports 一行 + 审计 + embed_ontology 已排队
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM ontology_imports WHERE kb_id = ?", Long.class, kb));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE kb_id = ? AND action = 'ontology.imported'",
                Long.class, kb));
        assertTrue(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM jobs WHERE kind = 'embed_ontology' "
                        + "AND payload->>'kb_id' = ?)", Boolean.class, kb.toString()));

        // 结构核对：父类边、互斥双向、domain/range、逆属性、属性的 domain
        UUID employee = eTypes.stream().filter(t -> t.key().equals("employee")).findFirst()
                .orElseThrow().id();
        UUID person = eTypes.stream().filter(t -> t.key().equals("person")).findFirst()
                .orElseThrow().id();
        UUID contractor = eTypes.stream().filter(t -> t.key().equals("contractor")).findFirst()
                .orElseThrow().id();
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM entity_type_parents WHERE child_id = ? AND parent_id = ?",
                Long.class, employee, person));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM entity_type_disjoint WHERE kb_id = ? AND a_id = ? "
                        + "AND b_id = ?", Long.class, kb, employee, contractor));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM entity_type_disjoint WHERE kb_id = ? AND a_id = ? "
                        + "AND b_id = ?", Long.class, kb, contractor, employee),
                "互斥两个方向都在");
        var rTypes = ontology.relationTypesWithIri(kb);
        assertEquals(3, rTypes.size());
        UUID hasManager = rTypes.stream().filter(t -> t.key().equals("has_manager"))
                .findFirst().orElseThrow().id();
        UUID employs = rTypes.stream().filter(t -> t.key().equals("employs"))
                .findFirst().orElseThrow().id();
        UUID salary = rTypes.stream().filter(t -> t.key().equals("salary"))
                .findFirst().orElseThrow().id();
        assertEquals(List.of(employee), jdbc.queryForList(
                "SELECT entity_type_id FROM relation_type_domains WHERE relation_type_id = ? "
                        + "ORDER BY 1", UUID.class, hasManager));
        assertEquals(List.of(person), jdbc.queryForList(
                "SELECT entity_type_id FROM relation_type_ranges WHERE relation_type_id = ? "
                        + "ORDER BY 1", UUID.class, hasManager));
        // 逆属性第二遍连上了（employs → hasManager）
        assertEquals(hasManager, jdbc.queryForObject(
                "SELECT inverse_of FROM relation_types WHERE id = ?", UUID.class, employs));
        // 属性带 datatype=number（xsd:integer）且挂在 domain 上
        assertEquals("attribute", jdbc.queryForObject(
                "SELECT kind FROM relation_types WHERE id = ?", String.class, salary));
        assertEquals("number", jdbc.queryForObject(
                "SELECT datatype FROM relation_types WHERE id = ?", String.class, salary));
        assertEquals(List.of(employee), jdbc.queryForList(
                "SELECT entity_type_id FROM relation_type_domains WHERE relation_type_id = ?",
                UUID.class, salary));
        // functional 照词表写下去（part_of 那个坑的反面）
        assertTrue(jdbc.queryForObject(
                "SELECT functional FROM relation_types WHERE id = ?", Boolean.class, hasManager));

        // 重导入：同一份文件——全部 Update（IRI 是身份），一条都不再新建
        Planned second = importer.plan(kb, "hr.ttl", ttl());
        assertTrue(second.plan().classes().stream()
                .allMatch(c -> c.disposition() == Disposition.UPDATE), () -> second.plan()
                        .classes().stream().map(PlannedItem::disposition).toList().toString());
        assertTrue(second.plan().relations().stream()
                .allMatch(r -> r.disposition() == Disposition.UPDATE));
        assertTrue(second.plan().attributes().stream()
                .allMatch(a -> a.disposition() == Disposition.UPDATE));
        importer.apply(kb, actor, "hr.ttl", ttl());
        assertEquals(3, ontology.entityTypesWithIri(kb).size(), "重导入不新建");
        assertEquals(3, ontology.relationTypesWithIri(kb).size());
        } finally {
            dropKb(kb);
        }
    }

    /** KeyTaken 不自动加后缀；对齐表声明的 Rename 例外。 */
    @Test
    void keyTakenIsReportedNotSuffixed() {
        UUID kb = freshKb();
        try {
        // 本地起的一个类占了 person 这个 key，没有 IRI——schema.org 的 Person 认领它
        UUID grown = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO entity_types (id, kb_id, key, label, color, shape)
                VALUES (?, ?, 'person', 'Person', '#7fd0ff', 'circle')
                """, grown, kb);
        String ttl = """
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix schema: <https://schema.org/> .
                schema:Person a rdfs:Class ; rdfs:label "Person" .
                schema:Organization a rdfs:Class ; rdfs:label "Organization" .
                """;
        Planned planned = importer.plan(kb, "schema-mini.ttl",
                ttl.getBytes(StandardCharsets.UTF_8));
        PlannedItem person = planned.plan().classes().stream()
                .filter(c -> c.key().equals("person")).findFirst().orElseThrow();
        assertEquals(Disposition.UPDATE, person.disposition(), "无 IRI 的同名本地类被认领");
        importer.apply(kb, actor, "schema-mini.ttl", ttl.getBytes(StandardCharsets.UTF_8));
        assertEquals("https://schema.org/Person",
                jdbc.queryForObject("SELECT iri FROM entity_types WHERE id = ?", String.class,
                        grown));
        assertEquals("square", jdbc.queryForObject(
                "SELECT shape FROM entity_types WHERE id = ?", String.class, grown),
                "认领之后是词表声明的类了，形状要跟着说实话");
        assertEquals("#7fd0ff", jdbc.queryForObject(
                "SELECT color FROM entity_types WHERE id = ?", String.class, grown),
                "认领不该动用户看惯的颜色");
        // 认领幂等：再认一次不改动任何东西
        assertNull(ontology.adoptIriOntoKey(kb, "person", "https://example.org/OtherPerson"),
                "已被认领的类不该被另一个词汇表抢走");
        } finally {
            dropKb(kb);
        }
    }

    /** relink 第二遍：单次导入落空的跨包 domain/range 只补不删、重复跑无害。 */
    @Test
    void relinkAddsMissingCrossPackSignatures() {
        UUID kb = freshKb();
        try {
        // 第一遍只装 w3c-org 风格的头衔包（headOf 的 domain foaf:Agent 还不在库里）
        byte[] first = """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix org: <http://www.w3.org/ns/org#> .
                org:headOf a owl:ObjectProperty ;
                    rdfs:label "head of" ;
                    rdfs:domain <http://xmlns.com/foaf/0.1/Agent> ;
                    rdfs:range org:Organization .
                org:Organization a owl:Class ; rdfs:label "Organization" .
                """.getBytes(StandardCharsets.UTF_8);
        importer.apply(kb, actor, "w3c-org-mini.ttl", first);
        var headOf = ontology.relationTypesWithIri(kb).stream()
                .filter(t -> t.key().equals("head_of")).findFirst().orElseThrow();
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM relation_type_domains WHERE relation_type_id = ?",
                Long.class, headOf.id()), "foaf:Agent 还没来，domain 落空（#222）");
        // 第二个包装好 foaf:Agent
        byte[] second = """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix foaf: <http://xmlns.com/foaf/0.1/> .
                foaf:Agent a owl:Class ; rdfs:label "Agent" .
                """.getBytes(StandardCharsets.UTF_8);
        importer.apply(kb, actor, "foaf-mini.ttl", second);
        // relink 第一遍装的包：head_of 的 domain 现在连得上
        int[] linked = importer.relinkDomainsRanges(kb, "w3c-org-mini.ttl", first);
        assertEquals(1, linked[0]);
        var agent = ontology.entityTypesWithIri(kb).stream()
                .filter(t -> t.key().equals("agent")).findFirst().orElseThrow();
        assertEquals(List.of(agent.id()), jdbc.queryForList(
                "SELECT entity_type_id FROM relation_type_domains WHERE relation_type_id = ?",
                UUID.class, headOf.id()));
        // 只补不删 + 幂等
        int[] again = importer.relinkDomainsRanges(kb, "w3c-org-mini.ttl", first);
        assertEquals(1, again[0]);
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM relation_type_domains WHERE relation_type_id = ?",
                Long.class, headOf.id()));
        } finally {
            dropKb(kb);
        }
    }

    /** 一个类被词汇表认领之后，形状要跟着说实话（adopting_an_iri_adopts_the_shape 逐字）。 */
    @Test
    void adoptingAnIriTurnsTheClassSquare() {
        // 开跑前先扫地：断言 panic 会跳过 teardown，一次失败会给下一次留垃圾
        jdbc.update("DELETE FROM organizations WHERE name = 'adopt-shape-test'");
        UUID aOrg = Uuids.nowV7();
        UUID aWs = Uuids.nowV7();
        UUID aKb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'adopt-shape-test')", aOrg);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'adopt-shape-test')",
                aWs, aOrg);
        jdbc.update(
                "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'adopt-shape-test')",
                aKb, aWs);
        try {
            // 一个「语料里长出来的」类：没有 IRI，所以是圆的
            UUID grown = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO entity_types (id, kb_id, key, label, color, shape)
                    VALUES (?, ?, 'person', 'Person', '#7fd0ff', 'circle')
                    """, grown, aKb);
            assertEquals("circle", jdbc.queryForObject(
                    "SELECT shape FROM entity_types WHERE id = ?", String.class, grown),
                    "还没被认领时该是圆的");

            // 导入一份词汇表，把 schema.org 的 Person 认到这个 key 上
            UUID adopted = ontology.adoptIriOntoKey(aKb, "person", "https://schema.org/Person");
            assertEquals(grown, adopted, "该认到那个已有的类上");

            assertEquals("https://schema.org/Person", jdbc.queryForObject(
                    "SELECT iri FROM entity_types WHERE id = ?", String.class, grown));
            assertEquals("square", jdbc.queryForObject(
                    "SELECT shape FROM entity_types WHERE id = ?", String.class, grown),
                    "认领之后是词表声明的类了，形状要跟着说实话");
            // 颜色不该动：颜色是身份，认领改变的是来历不是身份
            assertEquals("#7fd0ff", jdbc.queryForObject(
                    "SELECT color FROM entity_types WHERE id = ?", String.class, grown),
                    "认领不该动用户看惯的颜色");

            // 幂等：再认一次不该改动任何东西（iri 已非空，UPDATE 匹配不上）
            assertNull(ontology.adoptIriOntoKey(aKb, "person", "https://example.org/OtherPerson"),
                    "已被认领的类不该被另一个词汇表抢走");
            assertEquals("https://schema.org/Person", jdbc.queryForObject(
                    "SELECT iri FROM entity_types WHERE id = ?", String.class, grown));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE name = 'adopt-shape-test'");
        }
    }

    /** 预览不写任何东西：plan 之后库里没有行、没有账。 */
    @Test
    void previewWritesNothing() {
        UUID kb = freshKb();
        try {
        String ttl = """
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                <http://preview-test/x> a rdfs:Class ; rdfs:label "Preview Only" .
                """;
        long classesBefore = jdbc.queryForObject(
                "SELECT count(*) FROM entity_types WHERE kb_id = ?", Long.class, kb);
        long importsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM ontology_imports WHERE kb_id = ?", Long.class, kb);
        Planned planned = importer.plan(kb, "preview.ttl", ttl.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, planned.plan().classes().size());
        assertEquals(Disposition.CREATE, planned.plan().classes().get(0).disposition());
        assertEquals(classesBefore, jdbc.queryForObject(
                "SELECT count(*) FROM entity_types WHERE kb_id = ?", Long.class, kb));
        assertEquals(importsBefore, jdbc.queryForObject(
                "SELECT count(*) FROM ontology_imports WHERE kb_id = ?", Long.class, kb));
        assertFalse(blobs.exists(ImportPlanner.sha256Hex(ttl.getBytes(StandardCharsets.UTF_8))),
                "plan 不写 blob");
        } finally {
            dropKb(kb);
        }
    }
}
