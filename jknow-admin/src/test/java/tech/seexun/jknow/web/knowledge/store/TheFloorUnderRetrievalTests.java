package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.OntologyService;

/**
 * 移植自 tests/store/the_floor_under_retrieval.rs（1 例，a_retrieved_leaf_brings_
 * its_ancestors）——检索候选要<b>带上祖先</b>，否则提示词里没有泛化基类（向量检索偏爱
 * 字面出现在正文里的叶子类：一个讲 Sutskever 的分块，976 个类里 researcher 排第 4、
 * person 排第 359）。ancestors_of 整个活在一段递归 SQL 里：多继承与菱形走不走得通、
 * 会不会把同一个祖先展开两次，编译期一个字都不说——必须连库。
 *
 * <p>造菱形：researcher → audience → thing、corporation → organization → thing，外加
 * 多继承的 agent（同时挂 thing 与 organization 下——两条路都通到 thing，没有去重的
 * 递归会把 thing 展开两次）。自建自拆。
 */
class TheFloorUnderRetrievalTests extends HeartStoreTestBase {

    private static final String ORG = "floor-test";

    @Autowired
    OntologyService ontology;

    private record Seeded(UUID kb, Map<String, UUID> ids) {
        UUID id(String key) {
            return ids().get(key);
        }
    }

    private Seeded seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, ORG);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org, ORG);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)",
                kb, ws, ORG);
        String[] keys = {"thing", "audience", "researcher", "organization", "corporation",
                "agent"};
        java.util.Map<String, UUID> ids = new java.util.LinkedHashMap<>();
        for (String k : keys) {
            UUID id = Uuids.nowV7();
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    id, kb, k, k);
            ids.put(k, id);
        }
        String[][] edges = {
                {"audience", "thing"},
                {"researcher", "audience"},
                {"organization", "thing"},
                {"corporation", "organization"},
                // 多继承 + 菱形：agent 两条路都通到 thing
                {"agent", "thing"},
                {"agent", "organization"},
        };
        for (String[] edge : edges) {
            jdbc.update("INSERT INTO entity_type_parents (child_id, parent_id)"
                    + " VALUES (?, ?) ON CONFLICT DO NOTHING", ids.get(edge[0]), ids.get(edge[1]));
        }
        return new Seeded(kb, ids);
    }

    @Test
    void aRetrievedLeafBringsItsAncestors() {
        // 开跑前先扫地：断言 panic 会跳过 teardown
        jdbc.update("DELETE FROM organizations WHERE name = ?", ORG);
        Seeded s = seed();
        try {
            // 检索只捞到了叶子——正文写的是 "a researcher at the corporation"
            List<UUID> anc = ontology.ancestorsOf(List.of(s.id("researcher"), s.id("corporation")));

            for (String k : new String[] {"audience", "thing", "organization"}) {
                assertTrue(anc.contains(s.id(k)), "祖先里少了 " + k + "——地板没补上");
            }
            // 自己不算祖先：调用方会把两者并起来，重复只是噪声
            for (String k : new String[] {"researcher", "corporation"}) {
                assertFalse(anc.contains(s.id(k)), k + " 是它自己，不该出现在祖先里");
            }

            // 菱形去重：agent 有两条路通到 thing，thing 只该出现一次
            List<UUID> anc2 = ontology.ancestorsOf(List.of(s.id("agent")));
            long things = anc2.stream().filter(x -> x.equals(s.id("thing"))).count();
            assertEquals(1L, things, "菱形继承把 thing 展开了 " + things + " 次");
            assertTrue(anc2.contains(s.id("organization")), "多继承的另一条腿丢了");

            // 空输入不该炸，也不该扫全表
            assertTrue(ontology.ancestorsOf(List.of()).isEmpty());
        } finally {
            jdbc.update("DELETE FROM organizations WHERE name = ?", ORG);
        }
    }
}
