package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.NameVectorsService;
import tech.seexun.jknow.store.NamesService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.ResolutionService.ReviewRequest;
import tech.seexun.jknow.store.ResolutionService.ReviewStage;

/**
 * 移植自 tests/store/a_similar_name_is_proposed_not_merged.rs（478 行）——名字向量召回
 * （0041 决定 3 通道 2，第 2 刀）：<b>相近的名字来报到，但只提议，不归并。</b>
 * 库里有 海洋探测器1号，一篇新文档只写 海探1。字面召回（通道 1）碰不上它——不相等、
 * 又短于 containment 的四字门槛——从前这里静默长出第二个实体（#709）。
 *
 * <p>向量都是手摆的三维——测的是召回和提议的规矩，不是某个嵌入模型的远近观。
 */
class ASimilarNameIsProposedNotMergedTests extends HeartStoreTestBase {

    @Autowired
    ResolutionService resolution;
    @Autowired
    NameVectorsService nameVectors;
    @Autowired
    NamesService names;

    record Fx(UUID org, UUID kb, UUID device, UUID person, UUID probe, UUID captain) {
    }

    private Fx seed(String tag) {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID device = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID probe = Uuids.nowV7();
        UUID captain = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, tag);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org, tag);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)", kb, ws, tag);
        // 两个大类：设备（type_family 认不出，None）与人（Person）
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'device', 'Device')",
                device, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                person, kb);
        for (Object[] e : new Object[][]{{probe, device, "海洋探测器1号"},
                {captain, person, "海洋探测队长"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    e[0], kb, e[1], e[2]);
            UUID fact = names.record(kb, (UUID) e[0], (String) e[2], null, null);
            assertTrue(fact != null, "a named entity gets a name fact");
            // 两个名字的向量故意一样：只有大类能把它们分开
            nameVectors.set(kb, List.of(new NameVectorsService.NameVectorWrite(fact, (UUID) e[0],
                    new float[]{1.0f, 0.0f, 0.0f})));
        }
        return new Fx(org, kb, device, person, probe, captain);
    }

    private void teardown(Fx f) {
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    private List<Map.Entry<UUID, Float>> vectorReviews(ResolutionService.ResolutionResult r) {
        List<Map.Entry<UUID, Float>> out = new ArrayList<>();
        for (ReviewRequest v : r.reviews()) {
            if (v.reason().startsWith("name_vector|")) {
                out.add(Map.entry(v.otherId(), v.score()));
            }
        }
        return out;
    }

    /** 相近的名字：新建实体，给裁决器排一对，绝不静默归并。 */
    @Test
    void a_near_name_creates_an_entity_and_proposes_a_pair() {
        Fx f = seed("name-vector-near");
        try {
            float[] near = {0.95f, 0.31f, 0.0f};
            var r = resolution.resolveMention(f.kb(), f.device(), "海探1", null, near, null,
                    List.of());
            assertTrue(r.created(), "字面碰不上，mention 该新建实体");
            assertNotEquals(f.probe(), r.entityId(), "向量召回只提议，不能直接归并到 海洋探测器1号");
            List<Map.Entry<UUID, Float>> proposed = vectorReviews(r);
            assertTrue(proposed.stream().anyMatch(p -> p.getKey().equals(f.probe())),
                    "海洋探测器1号 该被提议");
            // 夹具里两个名字的向量一样，而 Device 认不出大类、不拦 Person：海洋探测队长
            // 也进来。这不是漏，是规矩——分不出大类时宁可多问；大类分得出时的拦截见下一条
            assertEquals(2, proposed.size(), "Device 无大类，不拦同向量的 Person");
            assertTrue(proposed.stream().allMatch(p -> p.getValue() >= NameVectorsService.SIM_FLOOR),
                    "分数是余弦本身");
            assertTrue(r.reviews().stream().allMatch(v -> v.stage() == ReviewStage.ADJUDICATING),
                    "名字相近的对交给批量裁决器，不是人");
        } finally {
            teardown(f);
        }
    }

    /** 大类对不上的不提议：设备的名字再像，也不是那个人。 */
    @Test
    void a_near_name_of_another_family_is_not_proposed() {
        Fx f = seed("name-vector-family");
        try {
            // mention 是个组织；海洋探测器1号 的类 Device 认不出大类（None），照规矩不拦；
            // 海洋探测队长 是 Person，与 Organization 大类不同，拦下
            UUID organization = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'organization', 'Organization')
                    """, organization, f.kb());
            float[] near = {1.0f, 0.0f, 0.0f};
            var r = resolution.resolveMention(f.kb(), organization, "海洋探测公司", null, near,
                    null, List.of());
            List<UUID> proposed = vectorReviews(r).stream().map(Map.Entry::getKey).toList();
            assertFalse(proposed.contains(f.captain()),
                    "Person 与 Organization 大类不同，海洋探测队长 不该被提议");
            assertTrue(proposed.contains(f.probe()), "Device 认不出大类，不拦；海洋探测器1号 照常提议");
        } finally {
            teardown(f);
        }
    }

    /** 不够近的不提议；没给向量的照旧。 */
    @Test
    void a_far_name_or_no_vector_proposes_nothing() {
        Fx f = seed("name-vector-far");
        try {
            float[] far = {0.0f, 1.0f, 0.0f};
            var r = resolution.resolveMention(f.kb(), f.device(), "深海机器人", null, far, null,
                    List.of());
            assertTrue(vectorReviews(r).isEmpty(), "余弦 0 低于下限，不提议");
            var none = resolution.resolveMention(f.kb(), f.device(), "海探1", null, null, null,
                    List.of());
            assertTrue(vectorReviews(none).isEmpty(), "没给名字向量就不走通道 2");
        } finally {
            teardown(f);
        }
    }

    /** 字面命中的候选是通道 1 的事：同一个名字不再经通道 2 复议。 */
    @Test
    void the_same_literal_name_is_not_proposed_twice() {
        Fx f = seed("name-vector-literal");
        try {
            float[] same = {1.0f, 0.0f, 0.0f};
            // 没有上下文向量：v1 路径归并到唯一的字面候选
            var r = resolution.resolveMention(f.kb(), f.device(), "海洋探测器1号", null, same,
                    null, List.of());
            assertEquals(f.probe(), r.entityId(), "字面相等走通道 1");
            // 夹具里 海洋探测队长 的向量与之相同、大类又拦不住，它照常被提议；要看的只是
            // 已归并到的 海洋探测器1号 自己不会再被排一对
            assertTrue(vectorReviews(r).stream().noneMatch(p -> p.getKey().equals(f.probe())),
                    "已经归并到它，不再对它排 name_vector");
        } finally {
            teardown(f);
        }
    }

    /**
     * 补向量的往返：新记的名字事实在 pending 里，写了向量就不在了。端到端跑出来的窟窿
     * ——之前 pending 按一个不存在的列排序，每篇文档的补向量都静默失败，通道 2 从未触发。
     */
    @Test
    void a_new_name_is_pending_until_its_vector_is_set() {
        Fx f = seed("name-vector-pending");
        try {
            UUID fresh = Uuids.nowV7();
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, '远洋重工')",
                    fresh, f.kb(), f.device());
            UUID fact = names.record(f.kb(), fresh, "远洋重工", null, null);
            assertTrue(fact != null, "a name fact");
            var pending = nameVectors.pending(f.kb(), 100);
            assertTrue(pending.stream().anyMatch(p -> p.factId().equals(fact)
                    && p.entityId().equals(fresh) && "远洋重工".equals(p.name())),
                    "新记的名字该在待补清单里");
            // 夹具里两个已有向量的名字不在清单里
            assertTrue(pending.stream().allMatch(p -> !p.entityId().equals(f.probe())
                    && !p.entityId().equals(f.captain())));
            nameVectors.set(f.kb(), List.of(new NameVectorsService.NameVectorWrite(fact, fresh,
                    new float[]{0.0f, 0.0f, 1.0f})));
            var after = nameVectors.pending(f.kb(), 100);
            assertTrue(after.stream().noneMatch(p -> p.factId().equals(fact)),
                    "写了向量就不该再待补");
        } finally {
            teardown(f);
        }
    }

    /**
     * 判过「不是一个」的一对，实体再被提到时不再排（#1104）。名字向量通道每次提到都跑，
     * 而审核表只对 pending 的行去重：从前裁决器分开过的一对，下一篇文档一提到就又是一条
     * 新的 pending。还没判的那一对不受影响，照常提议。
     */
    @Test
    void a_pair_kept_apart_is_not_proposed_again() {
        Fx f = seed("name-vector-kept-apart");
        try {
            float[] near = {0.95f, 0.31f, 0.0f};
            var first = resolution.resolveMention(f.kb(), f.device(), "海探1", null, near, null,
                    List.of());
            assertTrue(first.created());
            for (ReviewRequest v : first.reviews()) {
                resolution.createReview(f.kb(), first.entityId(), v.otherId(), v.score(),
                        v.reason(), v.stage());
            }
            // 裁决器判 海探1 与 海洋探测器1号 不是一个；跟 海洋探测队长 的那一对还没判
            UUID review = jdbc.queryForObject("""
                    SELECT id FROM resolution_reviews WHERE kb_id = ? AND status = 'pending'
                      AND ? IN (left_id, right_id)
                    """, UUID.class, f.kb(), f.probe());
            resolution.closeReviewAuto(review, "kept", "kept_apart|test");

            var again = resolution.resolveMention(f.kb(), f.device(), "海探1", null, near, null,
                    List.of());
            assertFalse(again.created(), "同一个字面名字归到上一轮建的实体");
            assertEquals(first.entityId(), again.entityId());
            List<UUID> proposed = vectorReviews(again).stream().map(Map.Entry::getKey).toList();
            assertFalse(proposed.contains(f.probe()), "分开过的一对不该再提议");
            assertTrue(proposed.contains(f.captain()), "还没判的一对照常提议（入队时对 pending 幂等）");
        } finally {
            teardown(f);
        }
    }

    /**
     * 只差一个数的名字不配对（#1104）：向量上几乎重合、字面上互相包含，却不是同一个东西。
     * 两条通道各守一次；一边没有数字的简称照常提议。
     */
    @Test
    void names_that_carry_different_numbers_are_not_proposed() {
        Fx f = seed("name-vector-numbers");
        try {
            // 名字向量：2号 的向量跟 1号 一模一样，照样不提议；没有数字的 队长 不受影响
            float[] same = {1.0f, 0.0f, 0.0f};
            var second = resolution.resolveMention(f.kb(), f.device(), "海洋探测器2号", null,
                    same, null, List.of());
            List<UUID> proposed = vectorReviews(second).stream().map(Map.Entry::getKey).toList();
            assertFalse(proposed.contains(f.probe()), "1号 与 2号 不该配对");
            assertTrue(proposed.contains(f.captain()), "没有数字的名字照常提议");

            // 包含关系：migration 39 是 migration 395 的子串，但不是它的简称
            var longR = resolution.resolveMention(f.kb(), f.device(), "migration 395", null, null,
                    null, List.of());
            var briefName = resolution.resolveMention(f.kb(), f.device(), "migration 39", null,
                    null, null, List.of());
            assertTrue(briefName.created());
            List<UUID> contained = contained(briefName);
            assertFalse(contained.contains(longR.entityId()), "数字不同的包含不配对");
            // 一边没有数字：简称对全称，正是这条通道要接住的
            var full = resolution.resolveMention(f.kb(), f.device(), "星云推理平台 2.0", null,
                    null, null, List.of());
            var brief = resolution.resolveMention(f.kb(), f.device(), "星云推理平台", null, null,
                    null, List.of());
            assertTrue(contained(brief).contains(full.entityId()),
                    "简称照常配到带版本号的全称上");
        } finally {
            teardown(f);
        }
    }

    private static List<UUID> contained(ResolutionService.ResolutionResult r) {
        List<UUID> out = new ArrayList<>();
        for (ReviewRequest v : r.reviews()) {
            if (v.reason().startsWith("contains|")) {
                out.add(v.otherId());
            }
        }
        return out;
    }
}
