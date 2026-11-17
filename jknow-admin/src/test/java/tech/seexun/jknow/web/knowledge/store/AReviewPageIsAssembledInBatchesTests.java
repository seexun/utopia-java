package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.NamesService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.entity.ReviewItem;

/**
 * 共享实体在按批组装的审核页里保得住自己的别名、度数与 top-4 限额
 * （a_review_page_is_assembled_in_batches.rs 原值移植——M4 任务 8 台账挂账的队列读族
 * 白盒，M5 任务 6 收口）：批面 SQL 在（M3 任务 4 落位），共享双侧摘要与整页定序的
 * 专白盒由此补上，断言逐字。
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class AReviewPageIsAssembledInBatchesTests extends StoreTestBase {

    @Autowired
    ReviewService reviews;
    @Autowired
    ResolutionService resolution;
    @Autowired
    NamesService names;

    private UUID org;
    private UUID ws;
    private UUID kb;
    private UUID otherKb;

    @BeforeAll
    void seed() {
        org = Uuids.nowV7();
        ws = Uuids.nowV7();
        kb = Uuids.nowV7();
        otherKb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'review-page-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name)"
                + " VALUES (?, ?, 'review-page-test')", ws, org);
        for (UUID base : List.of(kb, otherKb)) {
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name)"
                    + " VALUES (?, ?, 'review-page-test')", base, ws);
        }
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    @SuppressWarnings("unchecked")
    private List<ReviewItem> duplicates(String types, long limit, long offset) {
        return (List<ReviewItem>) reviews.items(kb, "duplicates", types, limit, offset);
    }

    private static List<UUID> ids(List<ReviewItem> items) {
        return items.stream().map(ReviewItem::id).toList();
    }

    @Test
    void sharedSidesKeepTheirSummariesAndThePageKeepsItsOrder() {
        UUID person = Uuids.nowV7();
        UUID company = Uuids.nowV7();
        UUID relation = Uuids.nowV7();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label, color)"
                + " VALUES (?, ?, 'person', 'Person', '#123456')", person, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label, color)"
                + " VALUES (?, ?, 'company', 'Company', '#123456')", company, kb);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label)"
                + " VALUES (?, ?, 'knows', 'Knows')", relation, kb);
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        UUID c = Uuids.nowV7();
        UUID d = Uuids.nowV7();
        UUID neighbor = Uuids.nowV7();
        UUID otherA = Uuids.nowV7();
        UUID otherB = Uuids.nowV7();
        for (Object[] row : new Object[][]{
                {a, kb, person, "Alpha", "North"},
                {b, kb, person, "Beta", null},
                {c, kb, company, "Gamma", null},
                {d, kb, null, "Untyped", null},
                {neighbor, kb, null, "Neighbor", null},
                {otherA, otherKb, null, "Alpha", null},
                {otherB, otherKb, null, "Beta", null}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name,"
                    + " disambiguator) VALUES (?, ?, ?, ?, ?)", row[0], row[1], row[2], row[3],
                    row[4]);
        }

        OffsetDateTime at = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        UUID knownAs = names.ensureKnownAs(kb);
        String[] aliases = {"Common", "Alternate", "common", null, "Retired"};
        for (UUID entity : List.of(a, b)) {
            String canonical = entity.equals(a) ? "alpha" : "BETA";
            // 两个实体共享同一批别名：去重必须按实体各管各的
            for (int i = 0; i < aliases.length; i++) {
                String name = aliases[i] == null ? canonical : aliases[i];
                jdbc.update("""
                        INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_value,
                                           recorded_at, invalidated_at)
                         VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                        """, Uuids.nowV7(), kb, entity, knownAs,
                        "{\"value\": \"" + name + "\"}",
                        at.plusSeconds(i),
                        "Retired".equals(name) ? at.plusDays(1) : null);
            }

            // 每实体六条活事实，含一条自环；第七条更高置信度的已作废；同置信度按 recorded_at
            float[] confidences = {0.9f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 1.0f};
            for (int i = 0; i < confidences.length; i++) {
                UUID subject = i == 0 ? neighbor : entity;
                UUID object = (i == 0 || i == 5) ? entity : (i == 3 ? null : neighbor);
                jdbc.update("""
                        INSERT INTO facts (id, kb_id, subject_id, predicate_id, phrase, object_id,
                                           object_value, confidence, recorded_at, invalidated_at,
                                           valid_from, valid_from_precision,
                                           valid_to, valid_to_precision)
                         VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?,
                                 CASE WHEN ? < 2 THEN TIMESTAMPTZ '2020-01-01 00:00:00+00' END,
                                 CASE WHEN ? < 2 THEN 'month' END,
                                 CASE WHEN ? = 0 THEN TIMESTAMPTZ '2021-02-01 00:00:00+00' END,
                                 CASE WHEN ? = 0 THEN 'month' END)
                        """, Uuids.nowV7(), kb, subject, i != 2 ? relation : null,
                        i == 2 ? "works alongside" : null, object,
                        i == 3 ? "{\"value\": \"text\"}" : null, confidences[i],
                        at.plusSeconds(10 - i), i == 6 ? at.plusDays(1) : null, i, i, i, i);
            }
        }

        UUID[] reviewsIds = new UUID[6];
        for (int i = 0; i < reviewsIds.length; i++) {
            reviewsIds[i] = Uuids.nowV7();
        }
        Arrays.sort(reviewsIds);
        // 最新两条共享时间戳：id 平手裁决要起作用
        Object[][] rows = {
                {kb, a, b, "human", "pending", 3},
                {kb, c, a, "adjudicating", "pending", 3},
                {kb, b, c, "human", "pending", 2},
                {kb, a, d, "adjudicating", "pending", 1},
                {kb, b, d, "human", "kept", 4},
                {otherKb, otherA, otherB, "human", "pending", 5}};
        for (int i = 0; i < rows.length; i++) {
            jdbc.update("""
                    INSERT INTO resolution_reviews (id, kb_id, left_id, right_id, stage, status,
                                                    score, reason, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, 0.8, 'namesake', ?)
                    """, reviewsIds[i], rows[i][0], rows[i][1], rows[i][2], rows[i][3],
                    rows[i][4], at.plusSeconds((int) rows[i][5]));
        }
        UUID mergeProposal = Uuids.nowV7();
        UUID keepProposal = Uuids.nowV7();
        Object[][] decisions = {
                {mergeProposal, reviewsIds[0], "merge", "proposed"},
                {keepProposal, reviewsIds[2], "keep", "proposed"},
                {Uuids.nowV7(), reviewsIds[0], "keep", "superseded"},
                {Uuids.nowV7(), reviewsIds[1], "keep", "accepted"}};
        for (Object[] row : decisions) {
            jdbc.update("""
                    INSERT INTO agent_decisions (id, kb_id, run_id, target_kind, target_id,
                                                 action, confidence, reason, status)
                     VALUES (?, ?, ?, 'review', ?, ?, 0.75, 'evidence', ?)
                    """, row[0], kb, Uuids.nowV7(), row[1], row[2], row[3]);
        }

        List<ReviewItem> all = duplicates("any", 10, 0);
        assertEquals(List.of(reviewsIds[1], reviewsIds[0], reviewsIds[2], reviewsIds[3]),
                ids(all));
        List<String> expected = List.of(
                "also known as: Common, Alternate",
                "Knows ← Neighbor (2020-01 → 2021-02)",
                "Knows → Neighbor (2020-01 → now)",
                "works alongside → Neighbor",
                "Knows → ?");
        for (ReviewItem item : all) {
            assertEquals(0.8f, item.score());
            assertEquals("namesake", item.reason());
            for (ReviewItem.ReviewSide side : List.of(item.left(), item.right())) {
                if (side.id().equals(a) || side.id().equals(b)) {
                    assertEquals(expected, side.topFacts(), "four facts per entity, plus aliases");
                    assertEquals(6, side.degree(),
                            "names and invalidated facts do not count; a self-loop counts once");
                    assertEquals("Person", side.typeLabel());
                    assertEquals("#123456", side.color());
                } else {
                    assertTrue(side.topFacts().isEmpty());
                    assertEquals(0, side.degree());
                }
            }
        }
        assertEquals(c, all.get(0).left().id());
        assertEquals("Company", all.get(0).left().typeLabel());
        assertEquals("Alpha", all.get(0).right().name());
        assertEquals("North", all.get(0).right().disambiguator());
        assertEquals("Beta", all.get(1).right().name());
        assertNull(all.get(1).right().disambiguator());
        assertEquals(d, all.get(3).right().id());
        assertNull(all.get(3).right().typeLabel());
        assertEquals("#94a3b8", all.get(3).right().color());
        for (Object[] check : new Object[][]{{all.get(1), mergeProposal, "merge"},
                {all.get(2), keepProposal, "keep"}}) {
            ReviewItem item = (ReviewItem) check[0];
            ReviewItem.ReviewProposal proposal = item.proposal();
            assertEquals(check[1], proposal.id());
            assertEquals(check[2], proposal.action());
            assertEquals(0.75f, proposal.confidence());
            assertEquals("evidence", proposal.reason());
        }
        assertNull(all.get(0).proposal());
        assertNull(all.get(3).proposal());
        for (UUID entity : List.of(a, b)) {
            Map<UUID, List<String>> lines = reviews.entityFactLines(kb, List.of(entity), 4);
            assertEquals(expected, lines.get(entity));
            assertEquals(expected.subList(0, 2),
                    reviews.entityFactLines(kb, List.of(entity), 1).get(entity),
                    "the alias heading does not consume the fact limit");
        }

        assertEquals(List.of(reviewsIds[0], reviewsIds[2]), ids(duplicates("any", 2, 1)));
        assertEquals(List.of(reviewsIds[0]), ids(duplicates("same", 10, 0)));
        assertEquals(List.of(reviewsIds[1], reviewsIds[2]), ids(duplicates("conflict", 10, 0)));
        List<ReviewItem> pending = resolution.pendingAdjudications(kb, 10);
        assertEquals(List.of(reviewsIds[3], reviewsIds[1]), ids(pending));
        assertTrue(pending.stream().allMatch(item -> "adjudicating".equals(item.stage())));
        assertEquals(expected, pending.get(1).right().topFacts());
        assertTrue(duplicates("any", 10, 4).isEmpty());
        assertTrue(duplicates("any", 0, 0).isEmpty());
    }
}
