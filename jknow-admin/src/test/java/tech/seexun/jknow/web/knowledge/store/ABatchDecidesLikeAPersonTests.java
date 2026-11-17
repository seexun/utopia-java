package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.entity.ReviewCounts;
import tech.seexun.jknow.store.entity.ReviewItem;

/**
 * 批量裁决与按类型筛重复项（#428），打在真库上（a_batch_decides_like_a_person.rs 断言
 * 不改写）。要钉住的行为：
 *
 * <ul>
 *   <li><b>筛选按类型分三档</b>：全部 / 两边同一个类型 / 两边类型冲突。没类型的一侧
 *       哪档都不进——不知道的不能当成一样，也不能当成不一样</li>
 *   <li><b>计数与筛选同一套口径</b>：左栏的两个数就是这两档各多少条</li>
 *   <li><b>批量 = 逐条的人工裁决</b>：每一条都走 decide_review，合并方向、状态、
 *       decided_by 与单条一模一样；一条失败（不存在、已经裁过）不拖累其余的，结果里
 *       逐条说明；动作不认识整批拒绝</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ABatchDecidesLikeAPersonTests extends StoreTestBase {

    @Autowired
    ResolutionService resolution;

    @Autowired
    ReviewService reviews;

    private static final class Seed {
        final UUID kb;
        final UUID org;
        final UUID sameA;
        final UUID conflict;
        final UUID sameB;
        final UUID untyped;
        final UUID pairA0;
        final UUID pairA1;
        final UUID user;

        Seed(UUID kb, UUID org, UUID sameA, UUID conflict, UUID sameB, UUID untyped,
             UUID pairA0, UUID pairA1, UUID user) {
            this.kb = kb;
            this.org = org;
            this.sameA = sameA;
            this.conflict = conflict;
            this.sameB = sameB;
            this.untyped = untyped;
            this.pairA0 = pairA0;
            this.pairA1 = pairA1;
            this.user = user;
        }
    }

    private Seed seed() {
        UUID org = UUID.randomUUID();
        UUID ws = UUID.randomUUID();
        UUID kb = UUID.randomUUID();
        UUID person = UUID.randomUUID();
        UUID company = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'batch-decide-test')", org);
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Batch Tester')", user, org,
                "batch-" + user + "@test.local");
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'batch-decide-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'batch-decide-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                person, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'org', 'Organization')",
                company, kb);
        UUID a1 = entity(person, "Zhang Wei", kb);
        UUID a2 = entity(person, "Zhang Wei", kb);
        UUID b1 = entity(company, "Apple", kb);
        UUID b2 = entity(person, "Apple", kb);
        UUID c1 = entity(person, "Li Si", kb);
        UUID c2 = entity(person, "Li Si", kb);
        UUID d1 = entity(person, "Wang Wu", kb);
        UUID d2 = entity(null, "Wang Wu", kb);
        return new Seed(kb, org,
                review(kb, a1, a2), review(kb, b1, b2), review(kb, c1, c2), review(kb, d1, d2),
                a1, a2, user);
    }

    private UUID entity(UUID typeId, String name, UUID kb) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, kb, typeId, name);
        return id;
    }

    private UUID review(UUID kb, UUID l, UUID r) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO resolution_reviews (id, kb_id, left_id, right_id, score, reason, stage) "
                        + "VALUES (?, ?, ?, ?, 1.0, 'namesake', 'human')", id, kb, l, r);
        return id;
    }

    private List<UUID> queueIds(UUID kb, String types) {
        List<UUID> ids = new ArrayList<>();
        for (Object o : reviews.items(kb, "duplicates", types, 100, 0)) {
            ids.add(((ReviewItem) o).id());
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    @Test
    void aBatchDecidesLikeAPerson() {
        Seed s = seed();
        // 三档筛选
        List<UUID> all = queueIds(s.kb, "any");
        assertEquals(4, all.size(), "全部四对都在等");
        List<UUID> same = queueIds(s.kb, "same");
        List<UUID> wantSame = new ArrayList<>(List.of(s.sameA, s.sameB));
        wantSame.sort(Comparator.naturalOrder());
        assertEquals(wantSame, same, "同类：两对同名 Person");
        List<UUID> conflict = queueIds(s.kb, "conflict");
        assertEquals(List.of(s.conflict), conflict, "冲突：Organization 对 Person");
        assertFalse(same.contains(s.untyped) && conflict.contains(s.untyped),
                "一侧没类型的哪档都不进");

        // 计数与筛选同一套
        ReviewCounts counts = reviews.counts(s.kb);
        assertEquals(4, counts.duplicates());
        assertEquals(2, counts.duplicatesSameType());
        assertEquals(1, counts.duplicatesTypeConflict());

        // 动作不认识：整批拒绝，什么都没动
        assertThrows(RuntimeException.class, () -> resolution.decideReviews(s.kb,
                List.of(s.sameA), "smash", UUID.randomUUID(), null));

        // 批量合并：两条真的、一条不存在的
        UUID ghost = UUID.randomUUID();
        List<ResolutionService.ReviewBatchOutcome> outcomes = resolution.decideReviews(s.kb,
                List.of(s.sameA, ghost, s.sameB), "merge", s.user, null);
        assertEquals(3, outcomes.size(), "每个 id 一条结果，顺序照旧");
        assertEquals(null, outcomes.get(0).error(), "第一对合了");
        assertTrue(outcomes.get(1).error() != null, "不存在的那条报出来");
        assertEquals(null, outcomes.get(2).error(), "第三对不受第二条拖累");

        // 记的是这个人，与单条裁决一样；一对里恰好一个被并进另一个
        assertEquals("merged", jdbc.queryForObject(
                "SELECT status FROM resolution_reviews WHERE id = ?", String.class, s.sameA));
        assertEquals(s.user, jdbc.queryForObject(
                "SELECT decided_by FROM resolution_reviews WHERE id = ?", UUID.class, s.sameA));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM entities WHERE id = ANY(ARRAY[?,?]::uuid[]) "
                        + "AND merged_into IS NOT NULL", Long.class, s.pairA0, s.pairA1),
                "一对里恰好一个被并进另一个");

        // 已经裁过的再裁一次：报错、不重复合并
        List<ResolutionService.ReviewBatchOutcome> again = resolution.decideReviews(s.kb,
                List.of(s.sameA), "keep", s.user, null);
        assertTrue(again.get(0).error() != null);

        // 批量分开
        List<ResolutionService.ReviewBatchOutcome> kept = resolution.decideReviews(s.kb,
                List.of(s.conflict), "keep", s.user, null);
        assertEquals(null, kept.get(0).error());
        ReviewCounts after = reviews.counts(s.kb);
        assertEquals(1, after.duplicates(), "只剩没类型的那对");
        assertEquals(0, after.duplicatesSameType());
        assertEquals(0, after.duplicatesTypeConflict());

        // 自建自拆（audit_events 只增不删——本测试不经路由，无台账行）
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", s.kb);
        jdbc.update("DELETE FROM organizations WHERE id = ?", s.org);
    }
}
