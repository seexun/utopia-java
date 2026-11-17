package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.MappingsService;

/**
 * 语义层映射的两条硬性质（tests/store/a_mapping_is_not_a_fact.rs 2 例逐字移植，断言不
 * 改写；docs/decisions/0011「mapped_to 搬出账本」换来的东西）：
 *
 * <ol>
 *   <li><b>同一个 (概念, 源) 只有一条。</b>从前这条唯一性藏在 object_value 这个 JSONB
 *       内部，数据库看不见，只能靠流程显式闭合；现在它是主键。</li>
 *   <li><b>表过态的不被下一轮探索刷回待看。</b>重跑必然再次算出被拒绝过的那条，
 *       {@code propose} 的 {@code WHERE status = 'proposed'} 不挡住就等于每跑一次都把
 *       人的否决抹掉一次。</li>
 * </ol>
 *
 * <p>自建自拆，绝不碰已有的库——只删 knowledge_bases，不删 org/user（用户是软删除的，
 * 测试不该造一个产品里不存在的动作，否则撞上的外键约束是夹具自己的问题）。
 */
class MappingIsNotAFactTests extends StoreTestBase {

    @Autowired
    MappingsService mappings;

    static UUID org;
    static UUID ws;

    @BeforeAll
    void seedOrg() {
        org = Uuids.nowV7();
        ws = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'map-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'map-test')", ws,
                org);
    }

    @AfterAll
    void cleanupOrg() {
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    /** Rust 每个测试各自 fixture——这里每例一座自拆的库与实体。 */
    private record Fx(UUID kb, UUID ent) {
    }

    private Fx fresh() {
        UUID kb = Uuids.nowV7();
        UUID ent = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'map-test')",
                kb, ws);
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, '营收')",
                ent, kb);
        return new Fx(kb, ent);
    }

    private void drop(Fx f) {
        // 只删知识库，不删 org/user（Rust 原注释：撞上的外键约束是夹具自己的问题）
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
    }

    @Test
    void oneConceptOneSourceOneMapping() {
        Fx f = fresh();
        try {
            var a = mappings.propose(f.kb(), f.ent(), "warehouse", "orders", null, null, null,
                    null, false);
            var b = mappings.propose(f.kb(), f.ent(), "warehouse", "orders_v2", null, null, null,
                    null, false);
            assertEquals(a.id(), b.id(), "同一个 (概念, 源) 该是同一行，不是两行");
            assertTrue(a.written() && b.written(), "第一次插入、第二次刷新，都算写入");

            var got = mappings.page(f.kb(), "proposed", null, 100, 0);
            assertEquals(1, got.items().size(), "只该有一条");
            assertEquals("orders_v2", got.items().get(0).tableName(), "重跑探索该刷新定义");

            // 换一个源就是另一条：同一概念在不同源上有不同定义是有意支持的
            mappings.propose(f.kb(), f.ent(), "lakehouse", "f_orders", null, null, null, null,
                    false);
            assertEquals(2, mappings.page(f.kb(), "proposed", null, 100, 0).items().size(),
                    "不同源该各有一条");
        } finally {
            drop(f);
        }
    }

    @Test
    void aRejectedMappingDoesNotComeBack() {
        Fx f = fresh();
        try {
            UUID user = Uuids.nowV7();
            jdbc.update("CALL jknow_test_user(?, ?, ? || '@m.test', 'm')", user, org, user);

            UUID id = mappings.propose(f.kb(), f.ent(), "warehouse", "orders", null, null, null,
                    null, false).id();
            mappings.decide(f.kb(), id, "rejected", user);

            // 下一轮探索会再次算出同一条——它不该被刷回待看，也不算写入
            boolean written = mappings.propose(f.kb(), f.ent(), "warehouse", "orders", null,
                    null, null, null, false).written();
            assertFalse(written, "撞上决定的提议不算写入，探索账把它记成 decided");
            assertTrue(mappings.page(f.kb(), "proposed", null, 100, 0).items().isEmpty(),
                    "拒绝过的不该重新排队");
            // 确认过的同理不该被探索覆盖
            assertTrue(mappings.page(f.kb(), "confirmed", null, 100, 0).items().isEmpty(),
                    "拒绝的也不该出现在确认列表里");
        } finally {
            drop(f);
        }
    }
}
