package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.store.GovernanceService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.entity.Precedents;
import tech.seexun.jknow.store.entity.ReviewItem;

/**
 * 一次裁决记下的是「为什么」（0026），打在真库上（a_decision_records_why.rs 断言不改写）。
 *
 * <p>三件事各自会以不同的方式坏掉：
 * <ul>
 *   <li><b>落库</b>：理由写进这一行，空白与只有空格算没写（不存一串空格）</li>
 *   <li><b>先例带理由</b>：治理与裁决器读先例走的是审计台账，不是这张表——所以理由
 *       也得进 detail.why，precedents_for 才读得到，render_lines 才写得出</li>
 *   <li><b>老行不炸</b>：0026 之前的决定没有 why，Precedent.why 是 null，渲染照旧</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ADecisionRecordsWhyTests extends StoreTestBase {

    @Autowired
    ResolutionService resolution;

    @Autowired
    GovernanceService governance;

    @Autowired
    AuditService audit;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final class Fixture {
        final UUID org;
        final UUID kb;
        final UUID user;
        final UUID a;
        final UUID b;
        final UUID review;

        Fixture(UUID org, UUID kb, UUID user, UUID a, UUID b, UUID review) {
            this.org = org;
            this.kb = kb;
            this.user = user;
            this.a = a;
            this.b = b;
            this.review = review;
        }
    }

    private Fixture seed() {
        UUID org = UUID.randomUUID();
        UUID ws = UUID.randomUUID();
        UUID kb = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID person = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID review = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'why-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'why-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'why-test')",
                kb, ws);
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Decider')", user, org,
                "decider-" + user + "@test.local");
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'company', 'Company')",
                person, kb);
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, 'Acme')",
                a, kb, person);
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, 'Acme Corp')",
                b, kb, person);
        jdbc.update("INSERT INTO resolution_reviews (id, kb_id, left_id, right_id, score, reason, stage) "
                        + "VALUES (?, ?, ?, ?, 0.6, 'contains', 'human')", review, kb, a, b);
        return new Fixture(org, kb, user, a, b, review);
    }

    /** 先例取数用的审核项（precedents_for 只读两侧 id/name）。 */
    private static ReviewItem item(Fixture f) {
        return new ReviewItem(f.review, 0.6f, "contains", "human", OffsetDateTime.now(),
                new ReviewItem.ReviewSide(f.a, "Acme", "Company", "#888888", null, 0, List.of()),
                new ReviewItem.ReviewSide(f.b, "Acme Corp", "Company", "#888888", null, 0, List.of()),
                null);
    }

    @Test
    void aDecisionKeepsItsReasonAndThePrecedentCarriesIt() throws Exception {
        Fixture f = seed();
        // 人把它们分开，并说了为什么（两头带空格）
        resolution.decideReview(f.kb, f.review, "keep", f.user,
                "  Acme Corp is the parent company; Acme is the product line  ");
        assertEquals("kept", jdbc.queryForObject(
                "SELECT status FROM resolution_reviews WHERE id = ?", String.class, f.review));
        assertEquals("Acme Corp is the parent company; Acme is the product line",
                jdbc.queryForObject("SELECT rationale FROM resolution_reviews WHERE id = ?",
                        String.class, f.review),
                "理由落在这一行上，两头的空格去掉");

        // 先例走审计：路由把 why 写进 detail，这里照路由的写法记一笔
        audit.recordOpt(null, f.kb, f.user, "review.keep", "review", f.review, JSON.createObjectNode()
                .put("left", "Acme")
                .put("right", "Acme Corp")
                .put("score", 0.6)
                .put("why", "Acme Corp is the parent company; Acme is the product line")
                .toString());
        Precedents p = governance.precedentsFor(f.kb, item(f));
        assertEquals(1, p.samePair().size(), "同对先例一条");
        assertEquals("Acme Corp is the parent company; Acme is the product line",
                p.samePair().get(0).why(), "先例带着人写的理由");
        List<String> lines = GovernanceService.renderLines(p);
        assertTrue(lines.stream().anyMatch(l -> l.contains("kept apart")
                        && l.contains("they wrote: \"Acme Corp is the parent")),
                "提示词里那一行要引用理由: " + lines);

        // 老行：没有 why 的决定照样是先例，只是没有理由
        audit.recordOpt(null, f.kb, f.user, "review.keep", "review", f.review, JSON.createObjectNode()
                .put("left", "Acme")
                .put("right", "Acme Corp")
                .put("score", 0.6)
                .toString());
        Precedents p2 = governance.precedentsFor(f.kb, item(f));
        assertEquals(2, p2.samePair().size());
        assertTrue(p2.samePair().stream().anyMatch(x -> x.why() == null),
                "0026 之前的行没有理由，读出来是 null 而不是报错");
        List<String> lines2 = GovernanceService.renderLines(p2);
        assertTrue(lines2.stream().anyMatch(l -> l.contains("kept apart") && !l.contains("they wrote")),
                "没理由的那条不该凭空长出一句引文: " + lines2);

        // 空理由不落库
        UUID review2 = UUID.randomUUID();
        jdbc.update("INSERT INTO resolution_reviews (id, kb_id, left_id, right_id, score, reason, stage) "
                        + "VALUES (?, ?, ?, ?, 0.6, 'contains', 'human')", review2, f.kb, f.a, f.b);
        resolution.decideReview(f.kb, review2, "keep", f.user, "   ");
        assertNull(jdbc.queryForObject("SELECT rationale FROM resolution_reviews WHERE id = ?",
                String.class, review2), "只有空格等于没写");

        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb);
        jdbc.update("CALL jknow_test_user_cleanup_uuid(?)", f.user);
        assertEquals(1, jdbc.update("DELETE FROM organizations WHERE id = ?", f.org),
                "一次性 org 没删掉");
    }
}
