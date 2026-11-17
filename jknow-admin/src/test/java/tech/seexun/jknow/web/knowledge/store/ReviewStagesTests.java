package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.entity.ReviewItem;

/**
 * 审核阶段是执行边界（review_stages.rs 断言不改写）：human 项必须展示给人，但绝不能
 * 被自动裁决器捞走。
 *
 * <p>pending pair 的唯一索引不含 stage：后出现的 namesake 必须把已有行升级为 human；
 * 反向的普通请求不得再把它降回自动裁决通道。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewStagesTests extends StoreTestBase {

    @Autowired
    ResolutionService resolution;

    @Autowired
    ReviewService reviewService;

    @Test
    void humanReviewIsVisibleButNeverPendingAdjudication() {
        UUID org = UUID.randomUUID();
        UUID ws = UUID.randomUUID();
        UUID kb = UUID.randomUUID();
        UUID ty = UUID.randomUUID();
        UUID left = UUID.randomUUID();
        UUID right = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID existing = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'review-stage-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'review-stage-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'review-stage-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'org', 'Org')",
                ty, kb);
        for (Object[] e : new Object[][]{{left, "Zhang Wei"}, {right, "Zhang Wei"},
                {third, "Zhang W."}, {existing, "Acme"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    e[0], kb, ty, e[1]);
        }
        try {
            ResolutionService.ResolutionResult ordinary = resolution.resolveMention(kb, ty, "Acme",
                    null, null, null, List.of());
            assertEquals(existing, ordinary.entityId());
            assertTrue(!ordinary.created(), "exclude=[] must retain ordinary recall");

            ResolutionService.ResolutionResult split = resolution.resolveMention(kb, ty, "Acme",
                    null, null, null, List.of(existing));
            assertNotEquals(existing, split.entityId());
            assertTrue(split.created(), "the excluded candidate cannot be attached");
            ResolutionService.ResolutionResult recalled = resolution.resolveMention(kb, ty, "Acme",
                    null, null, null, List.of(existing));
            assertEquals(split.entityId(), recalled.entityId());
            assertTrue(!recalled.created(), "re-extraction must reuse the non-excluded candidate");

            resolution.createReview(kb, left, right, 1.0f, "namesake",
                    ResolutionService.ReviewStage.HUMAN);
            resolution.createReview(kb, left, third, 0.42f, "ambiguous_name|0.42",
                    ResolutionService.ReviewStage.ADJUDICATING);

            // human 可见（list_reviews 不筛 stage），但绝不被自动裁决器捞走
            List<ReviewItem> all = visible(kb);
            assertEquals(2, all.size());
            assertTrue(all.stream().anyMatch(r -> "human".equals(r.stage())));
            List<ReviewItem> pending = resolution.pendingAdjudications(kb, 10);
            assertEquals(1, pending.size());
            assertEquals("adjudicating", pending.get(0).stage());

            // 后出现的 namesake 必须把已有行升级为 human；反向的普通请求不得再降回去
            resolution.createReview(kb, left, third, 1.0f, "namesake",
                    ResolutionService.ReviewStage.HUMAN);
            resolution.createReview(kb, left, third, 0.42f, "ambiguous_name|0.42",
                    ResolutionService.ReviewStage.ADJUDICATING);
            assertTrue(resolution.pendingAdjudications(kb, 10).isEmpty());
            ReviewItem upgraded = visible(kb).stream().filter(r ->
                    (r.left().id().equals(left) && r.right().id().equals(third))
                            || (r.left().id().equals(third) && r.right().id().equals(left)))
                    .findFirst().orElseThrow();
            assertEquals("human", upgraded.stage());
            assertEquals("namesake", upgraded.reason());
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    /** resolution.rs list_reviews 的对等入口：duplicates 档不筛 stage，全部等待对可见。 */
    private List<ReviewItem> visible(UUID kb) {
        return reviewService.items(kb, "duplicates", "any", 100, 0).stream()
                .map(o -> (ReviewItem) o).toList();
    }
}
