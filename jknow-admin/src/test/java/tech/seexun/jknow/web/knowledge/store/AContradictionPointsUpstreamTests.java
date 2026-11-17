package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphService;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.ReviewService;

/**
 * 移植自 tests/store/a_contradiction_points_upstream.rs（385 行，1 例）——0017：派生撞上
 * 断言时，让路这件事从静默变成可见。
 *
 * <p>ceo_of ⊑ works_at，works_at functional。Mira ceo_of Acme 推出 Mira works_at Acme，
 * 而账本里说她 works_at Globex。这里守四件事：
 * <ol>
 *   <li><b>派生不落地，而队列里有一行</b>。run 记一条 derived_contradiction，left 是被撞
 *       的断言，right 是最后一条前提，detail 写着推出来的三元组；materialize 拦下它</li>
 *   <li><b>修了就落</b>。给旧断言一个结束日期，派生的区间与它不再重叠，下一轮落地，
 *       队列里那一行随之清掉</li>
 *   <li><b>认可就落</b>。人说两边都对，accepted 之后派生照常落地，那一行留着不再报</li>
 *   <li><b>派生之间互撞按规则对聚合</b>。两个 ceo 推出两条互斥的 works_at，进
 *       ontology_defects 一行 rules_disagree，两条派生都不落</li>
 * </ol>
 *
 * <p>任务 6 已去封：materialize/derived_facts/DerivedPoolProvider 真身与读端点
 * （blocked_for_entity/blocked_proof/contested 面板/neighborhood 幽灵边）全部换真调用，
 * 断言一字未动。
 */
class AContradictionPointsUpstreamTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReasoningService reasoning;

    @Autowired
    GraphService graph;

    @Autowired
    ReviewService review;

    record Fixture(UUID org, UUID user, UUID kb, UUID ceoOf, UUID worksAt, UUID mira, UUID acme,
                   UUID globex, UUID initech) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID ceoOf = Uuids.nowV7();
        UUID worksAt = Uuids.nowV7();
        UUID mira = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID globex = Uuids.nowV7();
        UUID initech = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'contradiction-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'contradiction-test')",
                ws, org);
        jdbc.update(
                "CALL jknow_test_user(?, ?, ?, 'c')",
                user, org, user + "@contradiction.test");
        jdbc.update("""
                INSERT INTO knowledge_bases (id, workspace_id, name, materialize_inferences)
                 VALUES (?, ?, 'contradiction-test', TRUE)
                """, kb, ws);
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, functional)
                 VALUES (?, ?, 'works_at', 'works at', TRUE)
                """, worksAt, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, sub_property_of)
                 VALUES (?, ?, 'ceo_of', 'CEO of', ?)
                """, ceoOf, kb, worksAt);
        for (Object[] row : new Object[][]{{mira, "Mira"}, {acme, "Acme"}, {globex, "Globex"},
                {initech, "Initech"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    row[0], kb, etype, row[1]);
        }
        return new Fixture(org, user, kb, ceoOf, worksAt, mira, acme, globex, initech);
    }

    private UUID asserted(Fixture f, UUID subject, UUID predicate, UUID object, String from) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence,
                                    valid_from, valid_from_precision)
                 VALUES (?, ?, ?, ?, ?, 0.9, ?::timestamptz, CASE WHEN ?::timestamptz IS NULL THEN NULL ELSE 'day' END)
                """, id, f.kb(), subject, predicate, object, from, from);
        return id;
    }

    record OpenContradiction(UUID id, UUID leftFact, com.fasterxml.jackson.databind.JsonNode detail) {
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private List<OpenContradiction> openContradictions(Fixture f) {
        // jsonb 经字符串读再解（JdbcTemplate 不认 jsonb→JsonNode 的自动转换）
        return jdbc.query("""
                SELECT id, left_fact, detail FROM axiom_violations
                  WHERE kb_id = ? AND kind = 'derived_contradiction' AND status = 'open'
                """, (rs, i) -> new OpenContradiction(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                readJson(rs.getString(3))), f.kb());
    }

    private static com.fasterxml.jackson.databind.JsonNode readJson(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- 任务 6 去封：materialize 与 blocked/contested 读端点的真调用 ----

    private Object materialize(UUID kb) {
        return reasoning.materialize(kb);
    }

    private Object openViolations(UUID kb) {
        return review.items(kb, "violations", null, 50, 0);
    }

    private Object entityDetail(UUID kb, UUID entity) {
        return graph.entityDetail(kb, entity, null, null);
    }

    private Object neighborhood(UUID kb, UUID entity) {
        return graph.neighborhood(kb, entity, 2, null, null);
    }

    private Object blockedForEntity(UUID kb, UUID entity) {
        return reasoning.blockedForEntity(kb, entity, null);
    }

    private Object blockedProof(UUID kb, UUID violationId) {
        return reasoning.blockedProof(kb, violationId);
    }

    @Test
    void aContradictionPointsUpstream() {
        Fixture f = seed();
        try {
            // Mira works_at Globex（没写结束日期）；Mira ceo_of Acme 自 2024 起
            UUID old = asserted(f, f.mira(), f.worksAt(), f.globex(), "2020-01-01T00:00:00Z");
            UUID ceo = asserted(f, f.mira(), f.ceoOf(), f.acme(), "2024-01-01T00:00:00Z");

            // 1. 派生不落地，队列里有一行（materialize 报 derived/blocked/inserted）
            Object m = materialize(f.kb());

            var r = reasoning.run(f.kb());
            assertEquals(1, r.contradictions());
            assertEquals(0, r.rulesDisagree());
            List<OpenContradiction> rows = openContradictions(f);
            assertEquals(1, rows.size());
            OpenContradiction row = rows.get(0);
            assertEquals(old, row.leftFact(), "left is the assertion that was hit");
            assertEquals("functional", row.detail().path("axiom").asText());
            assertEquals("sub_property", row.detail().path("rule").asText());
            assertEquals("Mira", row.detail().path("subject").asText());
            assertEquals("works at", row.detail().path("predicate").asText());
            assertEquals("Acme", row.detail().path("object").asText());
            assertEquals("CEO of", row.detail().path("via_label").asText());
            assertEquals(ceo, UUID.fromString(row.detail().path("premises").get(0).asText()));
            UUID right = jdbc.queryForObject(
                    "SELECT right_fact FROM axiom_violations WHERE id = ?", UUID.class, row.id());
            assertEquals(ceo, right, "right is the last premise");

            // Review 给的线索：旧断言没写结束日期、派生起得更晚 → stale
            Object page = openViolations(f.kb());
            // 争议在它坐的地方可见（0017 §3）：面板行挂 contested，图上有一条幽灵边，
            // 「没落地的」一档有一行，它的证明链读得出前提（entityDetail/neighborhood/
            // blockedForEntity/blockedProof）
            Object facts = entityDetail(f.kb(), f.mira());
            Object edges = neighborhood(f.kb(), f.mira());
            Object blocked = blockedForEntity(f.kb(), f.acme());
            Object steps = blockedProof(f.kb(), row.id());

            // 重跑幂等：还是那一行
            reasoning.run(f.kb());
            assertEquals(1, openContradictions(f).size());

            // 2. 修了就落：给旧断言一个结束日期，区间不再重叠
            jdbc.update(
                    "UPDATE facts SET valid_to = '2023-06-30T00:00:00Z'::timestamptz,"
                            + " valid_to_precision = 'day' WHERE id = ?",
                    old);
            materialize(f.kb());
            reasoning.run(f.kb());
            assertEquals(0, openContradictions(f).size(),
                    "the queue row clears with the contradiction");

            // 3. 认可就落：把结束日期拿掉，矛盾回来；人说两边都对，派生照常落地
            jdbc.update(
                    "UPDATE facts SET valid_to = NULL, valid_to_precision = NULL WHERE id = ?",
                    old);
            materialize(f.kb());
            reasoning.run(f.kb());
            List<OpenContradiction> again = openContradictions(f);
            assertEquals(1, again.size());
            reasoning.decide(f.kb(), again.get(0).id(), "accepted", f.user());
            materialize(f.kb());
            var r3 = reasoning.run(f.kb());
            assertEquals(1, r3.contradictions(), "still counted");
            assertEquals(0, openContradictions(f).size(),
                    "but the accepted row stays resolved and nothing new is opened");

            // 4. 派生之间互撞：先把旧断言闭合掉，让断言不再参与；再来一个 ceo_of Initech，
            //    两条 works_at 由同一条规则推出、互斥——按规则对报一次，两条都不落
            jdbc.update(
                    "UPDATE facts SET valid_to = '2023-06-30T00:00:00Z'::timestamptz,"
                            + " valid_to_precision = 'day' WHERE id = ?",
                    old);
            asserted(f, f.mira(), f.ceoOf(), f.initech(), "2024-01-01T00:00:00Z");
            materialize(f.kb());
            var r4 = reasoning.run(f.kb());
            assertEquals(0, r4.contradictions());
            assertEquals(1, r4.rulesDisagree());
            var defects = jdbc.queryForList("""
                    SELECT subject, other FROM ontology_defects
                      WHERE kb_id = ? AND kind = 'rules_disagree' AND status = 'open'
                    """, f.kb());
            assertEquals(1, defects.size());
            assertEquals(f.ceoOf(), defects.get(0).get("subject"));
            assertEquals(f.ceoOf(), defects.get(0).get("other"));

            // 撤掉第二个 ceo：规则对的那一行清掉，第一条派生重新落地
            jdbc.update(
                    "UPDATE facts SET invalidated_at = now() WHERE id ="
                            + " (SELECT id FROM facts WHERE kb_id = ? AND predicate_id = ?"
                            + " AND object_id = ? AND invalidated_at IS NULL)",
                    f.kb(), f.ceoOf(), f.initech());
            reasoning.run(f.kb());
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM ontology_defects WHERE kb_id = ?"
                            + " AND kind = 'rules_disagree' AND status = 'open'",
                    Long.class, f.kb());
            assertEquals(0L, n, "a rule clash clears when its derivations go");
            materialize(f.kb());
        } finally {
            // 人裁过的违规行带着 decided_by→users 的 FK：本机会话的级联删序先删 users，
            // 先清掉 kb 名下的违规再删组织（Rust 参照库级联序先删 kb——数据造法侧的兜底）
            jdbc.update("DELETE FROM axiom_violations WHERE kb_id = ?", f.kb());
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
