package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.OntologyService.BulkRelation;

/**
 * 移植自 tests/store/axioms_reach_the_database.rs（109 行，1 例）——本体声明的公理要
 * 真的落到库里，打在真库上。
 *
 * <p>这条防线的由来是一个既存缺陷：create_relation_types_bulk 的文档写着
 * 「functional / inverse_functional 必须照词汇表写下去，不能默认 false」，而它的
 * SQL 里是硬编码的 FALSE, FALSE——两个数组绑定了却没进 UNNEST。实测装 FOAF（声明了
 * 17 条 FunctionalProperty）之后，库里 functional 为真的<b>一条都没有</b>。
 *
 * <p>那两位是时态引擎自动闭合事实的依据。写错的方向不同，后果也不同：标错成真
 * 会成批造假冲突（part_of 那次 59 条），而这次是反方向——该为真的全成了假，于是
 * <b>该检测出的时态冲突一条都检测不到</b>，静悄悄地。cargo check 看不见这种错
 * （类型全对），单元测试也看不见（它在 SQL 字符串里）。只有把一行真的写进去再读回来
 * 才行。
 */
class AxiomsReachTheDatabaseTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OntologyService ontology;

    /** 造一个最小的库：公理落库跟本体大小无关，一条就够。 */
    private UUID[] kb() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'ax-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'ax-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'ax-test')",
                kb, ws);
        return new UUID[]{kb, org};
    }

    @Test
    void everyAxiomSurvivesTheBulkInsert() {
        UUID[] kbOrg = kb();
        try {
            // 一条全真、一条全假：全假那条守的是"没声明的不该被写成真"
            ontology.createRelationTypesBulk(kbOrg[0], List.of(
                    new BulkRelation("all_true", "all_true", "", "http://ax.test/#all_true",
                            "relation", null, true, true, true, true, true, true),
                    new BulkRelation("all_false", "all_false", "", "http://ax.test/#all_false",
                            "relation", null, false, false, false, false, false, false)));

            List<boolean[]> got = jdbc.query("""
                    SELECT functional, inverse_functional,
                           is_transitive, is_symmetric, is_asymmetric, is_irreflexive
                     FROM relation_types WHERE kb_id = ? ORDER BY key
                    """, (rs, i) -> new boolean[]{rs.getBoolean(1), rs.getBoolean(2),
                    rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6)},
                    kbOrg[0]);
            // key 序：all_false 在前，all_true 在后
            boolean[] allFalse = got.get(0);
            boolean[] allTrue = got.get(1);
            assertTrue(allTrue[0] && allTrue[1] && allTrue[2] && allTrue[3] && allTrue[4]
                    && allTrue[5], "六个公理位都该照写下去");
            assertFalse(allFalse[0] || allFalse[1] || allFalse[2] || allFalse[3] || allFalse[4]
                    || allFalse[5], "没声明的公理不该凭空为真");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", kbOrg[1]);
        }
    }
}
