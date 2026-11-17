package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tech.seexun.jknow.store.Names;
import tech.seexun.jknow.store.Resolution;
import tech.seexun.jknow.store.Resolution.TypeDrift;

/**
 * 移植自 resolution.rs:2478-2571 内联纯函数测试（normalize/stem/recall/type_drift/cosine）
 * + :3020-3049 same_type_tests——断言逐字对译。normalize/numbers_differ 的原内联测试已随
 * 任务 3 落在 {@link NamesNumbersDifferTests}，此处不重复。
 */
class ResolutionPureTests {

    @Test
    void stem_generic_suffixes() {
        assertEquals("星尘", Resolution.nameStem("星尘项目"));
        assertEquals("星辰科技", Resolution.nameStem("星辰科技公司"));
        assertEquals("phoenix", Resolution.nameStem("Phoenix Project"));
        assertEquals("phoenix", Resolution.nameStem("Project Phoenix"));
        assertEquals("acme", Resolution.nameStem("Acme Inc."));
        assertNull(Resolution.nameStem("星尘"));
        assertNull(Resolution.nameStem("项目"), "剥空不算词干");
        assertNull(Resolution.nameStem("项目团队"), "词干本身是泛用词");
        assertNull(Resolution.nameStem("Team Project"));
    }

    @Test
    void recall_keys_bidirectional() {
        // 无后缀 mention 能召回带后缀实体（增广），反之靠词干
        assertTrue(Resolution.recallKeys("星尘").contains("星尘"));
        assertTrue(Resolution.recallKeys("星尘").contains("星尘项目"));
        assertTrue(Resolution.recallKeys("星尘项目").contains("星尘项目"));
        assertTrue(Resolution.recallKeys("星尘项目").contains("星尘"));
        assertTrue(Resolution.recallKeys("Phoenix").contains("phoenix project"));
        assertTrue(Resolution.recallKeys("Phoenix").contains("project phoenix"));
        assertTrue(Resolution.recallKeys("Project Phoenix").contains("phoenix"));
        assertTrue(Resolution.recallKeys("张三").size() <= 10);
    }

    @Test
    void type_drift_classes() {
        // 还没判出类型的那一侧 → 召回候选。0009 之前这一档比的是 concept 这个 key，
        // 现在比的是"有没有类"本身
        assertEquals(TypeDrift.RECALL, Resolution.classifyTypeDrift(null, "organization"));
        assertEquals(TypeDrift.RECALL, Resolution.classifyTypeDrift("project", null));
        // 两边都还没判出来：也归召回，由画像相似度说话
        assertEquals(TypeDrift.RECALL, Resolution.classifyTypeDrift(null, null));
        // 易混具体类型两两 → 审核对
        assertEquals(TypeDrift.REVIEW, Resolution.classifyTypeDrift("organization", "project"));
        assertEquals(TypeDrift.REVIEW, Resolution.classifyTypeDrift("project", "product"));
        assertEquals(TypeDrift.REVIEW, Resolution.classifyTypeDrift("product", "organization"));
        // 硬互斥与未知自定义类型 → 完全分开
        assertEquals(TypeDrift.DISJOINT, Resolution.classifyTypeDrift("person", "organization"));
        assertEquals(TypeDrift.DISJOINT, Resolution.classifyTypeDrift("person", "project"));
        assertEquals(TypeDrift.DISJOINT, Resolution.classifyTypeDrift("event", "location"));
        assertEquals(TypeDrift.DISJOINT, Resolution.classifyTypeDrift("team", "organization"));
    }

    @Test
    void cosine_basics() {
        assertTrue(Math.abs(Resolution.cosine(new float[]{1f, 0f}, new float[]{1f, 0f}) - 1.0)
                < 1e-6);
        assertTrue(Math.abs(Resolution.cosine(new float[]{1f, 0f}, new float[]{0f, 1f})) < 1e-6);
        assertNull(Resolution.cosine(new float[]{1f}, new float[]{1f, 2f}));
        assertNull(Resolution.cosine(new float[]{0f, 0f}, new float[]{1f, 1f}));
    }

    /**
     * 移植自 resolution.rs:3020-3049 same_type_tests：<b>相同类型是最常见的情形，而它
     * 曾经落在 Disjoint 上。</b>这个函数生来服务"类型漂移"（同名被抽成两种类型），那里
     * 两边相同不会发生；后来被 containment_reviews 借去当相容性判据，那里两边相同是
     * 常态。结果是 Sherlock Holmes 与 Holmes 一对都进不了审阅队列。
     */
    @Test
    void the_same_type_is_a_recall_candidate() {
        for (String k : new String[]{"person", "location", "organization", "product", "event"}) {
            assertEquals(TypeDrift.RECALL, Resolution.classifyTypeDrift(k, k),
                    k + " 对 " + k + " 必须可召回");
        }
        // 自定义类型同样——这里判的是"两边是不是同一种东西"，不是"它在不在白名单里"
        assertEquals(TypeDrift.RECALL, Resolution.classifyTypeDrift("drug", "drug"));
        // 跨类型的老结论一条都不变
        assertEquals(TypeDrift.DISJOINT,
                Resolution.classifyTypeDrift("person", "organization"));
    }
}
