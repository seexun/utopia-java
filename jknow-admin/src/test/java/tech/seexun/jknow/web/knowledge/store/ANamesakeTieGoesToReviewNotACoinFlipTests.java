package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.ResolutionService.ReviewStage;

/**
 * 移植自 tests/store/a_namesake_tie_goes_to_review_not_a_coin_flip.rs（206 行，#270，续
 * #221/#296）——同名并列的灰区不该靠候选顺序掷硬币。一个库里已有两个「张伟」，画像
 * 向量一模一样（都从同一个 chunk 里长出来）。后来一条没有任何 handle 的「张伟」mention
 * 进来，对两个候选打出同一个分数：分不开就别硬分——新建实体，对并列的两个候选各入一条
 * <b>人工</b>审核对，绝不静默归并。
 */
class ANamesakeTieGoesToReviewNotACoinFlipTests extends HeartStoreTestBase {

    @Autowired
    ResolutionService resolution;

    record Fx(UUID org, UUID kb, UUID person, UUID zhangA, UUID zhangB) {
    }

    /** 两个同名的「张伟」，画像向量完全相同；雇主不同，但那条线索此刻没人用得上。 */
    private Fx seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID organization = Uuids.nowV7();
        UUID worksFor = Uuids.nowV7();
        UUID platform = Uuids.nowV7();
        UUID finance = Uuids.nowV7();
        UUID zhangA = Uuids.nowV7();
        UUID zhangB = Uuids.nowV7();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'namesake-tie-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'namesake-tie-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'namesake-tie-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                person, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'organization', 'Organization')",
                organization, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, temporal)
                 VALUES (?, ?, 'works_for', 'works for', 'state')
                """, worksFor, kb);

        // 两个雇主，两个同名的人。故意一个大写一个小写：召回用 SQL lower()，
        // 「Zhang Wei」和「zhang wei」本就是一对同名候选，并列判定也必须忽略大小写
        for (Object[] e : new Object[][]{{platform, organization, "Platform Engineering"},
                {finance, organization, "Finance"}, {zhangA, person, "Zhang Wei"},
                {zhangB, person, "zhang wei"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    e[0], kb, e[1], e[2]);
        }
        // 画像向量一模一样：两人都是同一个 chunk 里播下的种子，质心相同
        for (UUID id : new UUID[]{zhangA, zhangB}) {
            jdbc.update(
                    "UPDATE entities SET profile_embedding = '[1,0,0]'::vector, profile_n = 1 WHERE id = ?",
                    id);
        }
        // 区分他们的事实就摆在这里——department/雇主——只是画像比对看不见它
        for (UUID[] p : new UUID[][]{{zhangA, platform}, {zhangB, finance}}) {
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence)
                     VALUES (?, ?, ?, ?, ?, 0.9)
                    """, Uuids.nowV7(), kb, p[0], worksFor, p[1]);
        }
        return new Fx(org, kb, person, zhangA, zhangB);
    }

    @Test
    void a_namesake_tie_creates_an_entity_and_two_reviews() {
        Fx f = seed();
        try {
            // 与两个候选画像都一致的上下文：对 A、对 B 打出的余弦相同
            float[] ctx = {1.0f, 0.0f, 0.0f};
            var r = resolution.resolveMention(f.kb(), f.person(), "Zhang Wei", ctx, null, null,
                    List.of());

            // 分不开就不归并：新建了第三个实体，没有 attach 到 A 或 B
            assertTrue(r.created(), "同名并列不该静默归并到先遇到的那个——该新建实体（#270）");
            assertNotEquals(f.zhangA(), r.entityId(), "attach 到了 A：候选顺序掷出的硬币");
            assertNotEquals(f.zhangB(), r.entityId(), "attach 到了 B：候选顺序掷出的硬币");

            // 对并列的两个候选各入一条审核对
            List<UUID> reviewed = new ArrayList<>();
            for (var rv : r.reviews()) {
                reviewed.add(rv.otherId());
            }
            Collections.sort(reviewed);
            List<UUID> want = new ArrayList<>(List.of(f.zhangA(), f.zhangB()));
            Collections.sort(want);
            assertEquals(want, reviewed, "两个同名候选都该进审核，一个都不能少");

            // 同名并列只有人分得开，绝不能让批量裁决器把两条几乎相同的画像自动并掉
            for (var rv : r.reviews()) {
                assertEquals(ReviewStage.HUMAN, rv.stage(), "同名并列的审核对必须是人工阶段");
            }

            // 落库后确实是两条待裁的人工审核对，都挂在新建的实体上
            for (var rv : r.reviews()) {
                resolution.createReview(f.kb(), r.entityId(), rv.otherId(), rv.score(),
                        rv.reason(), rv.stage());
            }
            var pending = jdbc.query("""
                    SELECT CASE WHEN left_id = ? THEN right_id ELSE left_id END AS other, stage
                     FROM resolution_reviews
                     WHERE kb_id = ? AND status = 'pending' AND (left_id = ? OR right_id = ?)
                    """, (rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)),
                    r.entityId(), f.kb(), r.entityId(), r.entityId());
            assertEquals(2, pending.size(), "库里该有两条待裁的审核对");
            assertTrue(pending.stream().allMatch(p -> "human".equals(p.getValue())),
                    "落库的审核对都该是 human 阶段");
            List<UUID> others = new ArrayList<>(pending.stream().map(Map.Entry::getKey).toList());
            Collections.sort(others);
            assertEquals(want, others, "两条审核对分别指向 A 和 B");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
