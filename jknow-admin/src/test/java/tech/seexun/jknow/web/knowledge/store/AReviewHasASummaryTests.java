package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.entity.ReviewCounts;
import tech.seexun.jknow.store.entity.ReviewSummary;

/**
 * 审核台的总览（#377），打在真库上（a_review_has_a_summary.rs 两例断言不改写）。
 *
 * <p>三段都是 SQL 聚合，编译期一个字看不见；要钉住的是口径：
 * <ul>
 *   <li><b>等着办的</b>与左栏的计数同一套 WHERE，多出来的「最老」取的是各表自己的
 *       时钟（冲突是 created_at，事实队列是 recorded_at）</li>
 *   <li><b>办过的</b>只认 review 域的四族动作，7 天窗口是 30 天窗口的子集，没有
 *       actor 的算自裁，按天补齐到 14 条</li>
 *   <li><b>成色</b>只数在世的事实；「有争议」是挂在开着的冲突任一端</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AReviewHasASummaryTests extends StoreTestBase {

    /** review_summary.rs:33 DAILY_DAYS=14。 */
    private static final int DAILY_DAYS = 14;

    @Autowired
    ReviewService reviews;

    private static final class Seed {
        final UUID kb;
        final UUID org;
        final UUID ada;
        final UUID[] facts;
        final OffsetDateTime conflictAt;
        final OffsetDateTime reviewAt;

        Seed(UUID kb, UUID org, UUID ada, UUID[] facts, OffsetDateTime conflictAt,
             OffsetDateTime reviewAt) {
            this.kb = kb;
            this.org = org;
            this.ada = ada;
            this.facts = facts;
            this.conflictAt = conflictAt;
            this.reviewAt = reviewAt;
        }
    }

    private Seed seed() {
        UUID org = UUID.randomUUID();
        UUID ws = UUID.randomUUID();
        UUID kb = UUID.randomUUID();
        UUID etype = UUID.randomUUID();
        UUID rel = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID f1 = UUID.randomUUID();
        UUID f2 = UUID.randomUUID();
        UUID f3 = UUID.randomUUID();
        UUID ada = UUID.randomUUID();
        UUID bo = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'review-summary-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'review-summary-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'review-summary-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                rel, kb, "knows_" + UUID.randomUUID(), "knows");
        for (Object[] e : new Object[][]{{a, "Ada"}, {b, "Bo"}, {c, "Cy"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    e[0], kb, etype, e[1]);
        }
        // 三条在世的事实：f2 低置信（0.5 < 0.75），f1 与 f3 之间挂一条开着的冲突
        for (Object[] e : new Object[][]{{f1, a, b, 0.9}, {f2, a, c, 0.5}, {f3, b, c, 0.9}}) {
            jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", e[0], kb, e[1], rel, e[2], e[3]);
        }
        OffsetDateTime conflictAt = now.minusDays(3);
        jdbc.update("INSERT INTO fact_conflicts (id, kb_id, old_fact_id, new_fact_id, reason, created_at) "
                        + "VALUES (?, ?, ?, ?, 'simultaneous', ?)", UUID.randomUUID(), kb, f1, f3,
                conflictAt);
        OffsetDateTime reviewAt = now.minusDays(5);
        jdbc.update("INSERT INTO resolution_reviews (id, kb_id, left_id, right_id, score, created_at) "
                        + "VALUES (?, ?, ?, ?, 0.8, ?)", UUID.randomUUID(), kb, a, b, reviewAt);

        // 台账：Ada 两条在 7 天内，一条自裁在 7 天内，Bo 一条在 20 天前（30 天内），
        // Ada 还有一条 40 天前（窗口外）；再夹一条不是 review 域的动作，不该被数
        Object[][] events = {
                {ada, "Ada", "review.merge", 1},
                {ada, "Ada", "fact.confirm", 2},
                {null, null, "review.keep", 3},
                {bo, "Bo", "conflict.close_old", 20},
                {ada, "Ada", "fact.reject", 40},
                {ada, "Ada", "document.upload", 1},
        };
        for (Object[] e : events) {
            jdbc.update("INSERT INTO audit_events (id, kb_id, actor_id, actor_label, action, target_kind, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'test', ?)", UUID.randomUUID(), kb, e[0],
                    e[1], e[2], now.minusDays((int) e[3]));
        }
        return new Seed(kb, org, ada, new UUID[]{f1, f2, f3}, conflictAt, reviewAt);
    }

    private void teardown(Seed s) {
        // 台账只增不删（触发器挡着 DELETE），这几行留在库里，挂着一个已经不存在
        // 的 kb_id，谁也读不到；组织不级联到库，两个都要删
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", s.kb);
        jdbc.update("DELETE FROM organizations WHERE id = ?", s.org);
    }

    @Test
    void sharedConflictEndpointsDoNotMultiplyHealthCounts() {
        Seed s = seed();
        Seed other = seed();
        UUID f1 = s.facts[0];
        UUID f2 = s.facts[1];
        UUID resolvedOnly = UUID.randomUUID();
        UUID invalidated = UUID.randomUUID();
        for (Object[] e : new Object[][]{{resolvedOnly, 0.9, false}, {invalidated, 0.5, true}}) {
            jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, "
                            + "confidence, invalidated_at) "
                            + "SELECT ?, kb_id, subject_id, predicate_id, object_id, ?, "
                            + "CASE WHEN ? THEN now() END FROM facts WHERE id = ?",
                    e[0], e[1], e[2], f2);
        }
        // f1 occurs on both ends and in several open conflicts. Every health
        // aggregate must still count the fact once, including low/unconfirmed.
        for (Object[] e : new Object[][]{{s.facts[2], f1, "open"}, {f2, f1, "open"},
                {resolvedOnly, f1, "resolved"}}) {
            jdbc.update("INSERT INTO fact_conflicts (id, kb_id, old_fact_id, new_fact_id, reason, status) "
                            + "VALUES (?, ?, ?, ?, 'simultaneous', ?)", UUID.randomUUID(), s.kb,
                    e[0], e[1], e[2]);
        }
        UUID doc = UUID.randomUUID();
        UUID chunk = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256) VALUES (?, ?, 'old.txt', ?)",
                doc, s.kb, doc.toString());
        jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text, superseded_at) "
                        + "VALUES (?, ?, ?, 0, 'old evidence', now())", chunk, s.kb, doc);
        jdbc.update("INSERT INTO fact_evidence (fact_id, chunk_id, document_id) VALUES (?, ?, ?)",
                f1, chunk, doc);
        ReviewSummary sum = reviews.summary(s.kb);
        teardown(s);
        teardown(other);
        assertEquals(4, sum.health().facts(), "only this KB's live facts");
        assertEquals(3, sum.health().contested(), "distinct live endpoints of open conflicts");
        assertEquals(1, sum.health().lowConfidence());
        assertEquals(1, sum.health().unconfirmed());
        assertEquals(3, sum.waiting().conflicts().count());
        assertEquals(1, sum.waiting().lowconf().count());
        assertEquals(1, sum.waiting().unconfirmed().count());
    }

    private static boolean close(OffsetDateTime a, OffsetDateTime b) {
        return a != null && Math.abs(a.toEpochSecond() - b.toEpochSecond()) < 2;
    }

    @Test
    void theSummaryReadsTheThreeLedgersWithOneYardstick() {
        Seed s = seed();
        ReviewSummary sum = reviews.summary(s.kb);
        ReviewCounts counts = reviews.counts(s.kb);
        teardown(s);

        // 等着办的：与左栏同一套数，多了最老的一条从何时起等
        ReviewSummary.ReviewWaiting w = sum.waiting();
        assertEquals(1, w.conflicts().count());
        assertTrue(close(w.conflicts().oldestAt(), s.conflictAt), "冲突按 created_at");
        assertEquals(1, w.duplicates().count());
        assertTrue(close(w.duplicates().oldestAt(), s.reviewAt), "重复按 created_at");
        assertEquals(1, w.lowconf().count());
        assertTrue(w.lowconf().oldestAt() != null, "低置信按事实的 recorded_at");
        assertEquals(0, w.unconfirmed().count());
        assertEquals(0, w.pending().count());
        assertNull(w.pending().oldestAt(), "空队列没有「最老」");
        assertEquals(0, w.violations().count());
        assertEquals(0, w.defects().count());
        assertEquals(counts.conflicts(), w.conflicts().count(), "总览与左栏计数是同一套口径");
        assertEquals(counts.duplicates(), w.duplicates().count());
        assertEquals(counts.lowconf(), w.lowconf().count());
        assertEquals(counts.unconfirmed(), w.unconfirmed().count());

        // 成色：三条在世的事实，一条低置信，两条挂在开着的冲突上
        assertEquals(3, sum.health().facts());
        assertEquals(1, sum.health().lowConfidence());
        assertEquals(2, sum.health().contested());
        assertEquals(0, sum.health().unconfirmed());

        // 办过的：7 天窗口三条（两条 Ada、一条自裁），30 天窗口再加 Bo 的一条；
        // 40 天前的与非 review 域的动作都不算
        ReviewSummary.DecidedWindow d7 = sum.decided().last7d();
        assertEquals(3, d7.total());
        assertEquals(1, d7.automatic());
        List<String> byAction = d7.byAction().stream()
                .map(x -> x.action() + ":" + x.count()).toList();
        assertEquals(List.of("fact.confirm:1", "review.keep:1", "review.merge:1"), byAction,
                "同数按动作名排序");
        assertEquals(s.ada, d7.byActor().get(0).actorId());
        assertEquals("Ada", d7.byActor().get(0).label());
        assertEquals(2, d7.byActor().get(0).count());
        assertTrue(d7.byActor().stream().anyMatch(x -> x.actorId() == null && x.count() == 1),
                "自裁的那条按「没有人」单列");
        ReviewSummary.DecidedWindow d30 = sum.decided().last30d();
        assertEquals(4, d30.total());
        assertEquals(1, d30.automatic());
        assertTrue(d30.byActor().stream().anyMatch(x -> "Bo".equals(x.label()) && x.count() == 1),
                "20 天前的那条进 30 天窗口");

        // 按天：固定 14 条、今天在最后、加起来就是近 14 天的三条
        List<ReviewSummary.DecidedDay> daily = sum.decided().daily();
        assertEquals(DAILY_DAYS, daily.size());
        // 「今天」按 UTC 取（Rust 原断言 Utc::now().date_naive()，序列按 (now() AT TIME
        // ZONE 'UTC')::date 生成）——本机时区在前半夜已过明天（UTC）时，LocalDate.now()
        // 会差一天（M4 任务 5 全量跑在 00:40 撞出的既有移植缺口，非本任务行为面）
        assertEquals(LocalDate.now(java.time.ZoneOffset.UTC),
                daily.get(daily.size() - 1).day());
        assertEquals(3, daily.stream().mapToLong(ReviewSummary.DecidedDay::count).sum());
        for (int i = 1; i < daily.size(); i++) {
            assertEquals(1, daily.get(i).day().toEpochDay() - daily.get(i - 1).day().toEpochDay(),
                    "一天一条，不跳");
        }
    }
}
