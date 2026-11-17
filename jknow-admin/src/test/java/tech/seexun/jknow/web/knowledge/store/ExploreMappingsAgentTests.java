package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.extraction.ScriptedModelServer;
import tech.seexun.jknow.web.knowledge.jobs.ExploreMappingsJob;
import tech.seexun.jknow.store.DatasourcesService;
import tech.seexun.jknow.store.ExplorationRunsService;
import tech.seexun.jknow.store.MappingsService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.ExplorationRun;

/**
 * 探索 job 的一轮（脚本化模型——四 job 脚本化测试的 explore 面：drop 分类账/单源全收
 * #501/decided 不复活/空告警 MAPPING_EXPLORATION_EMPTY）。挂载的源就指共享测试库本体
 * （postgres 引擎 fetchSchema 读 information_schema——只读，表数由库决定，断言不依赖
 * 具体表名：covered 的分子是提议<b>声明</b>的表，不是库里真有的表）。
 *
 * <p>Rust 侧这两条性质没有整轮集成测试（mappings.rs 只有纯函数内联 3 例），分类账的
 * 断言从 job 代码逐句对出——与 ontology_agent/type_resolution 两套脚本化测试同口径。
 */
class ExploreMappingsAgentTests extends StoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ExploreMappingsJob explore;
    @Autowired
    ExplorationRunsService runs;
    @Autowired
    MappingsService mappings;
    @Autowired
    DatasourcesService datasources;
    @Autowired
    SettingsService settings;

    private static final String DESCRIBE =
            "{\"description\": \"dw.orders: one row per order.\", \"questions\": [\"Which "
                    + "ord_st values count?\"]}    ";

    private static String proposal(String name, String kind, String source, String definition) {
        return "[{\"name\": \"" + name + "\", \"kind\": \"" + kind + "\", \"source\": \"" + source
                + "\", \"definition\": " + definition + ", \"summary\": \"s\", "
                + "\"rationale\": \"r\"}]";
    }

    private record Fx(UUID org, UUID ws, UUID kb, UUID user, ScriptedModelServer model) {
    }

    /** 一座自拆的库 + 指共享 PG 的挂载源（engine_for 走真 postgres 引擎）。 */
    private Fx seed(List<String> script, List<String> sourceNames) throws java.io.IOException {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        ScriptedModelServer model = ScriptedModelServer.start(script);
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'explore-agent-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'explore-agent')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, "
                + "'explore-agent')", kb, ws);
        jdbc.update("CALL jknow_test_user(?, ?, ? || '@e.test', 'e')", user, org, user);
        settings.upsert(ws, model.endpoint(), null, "scripted", null, null, null, null);
        String conn = "postgres://" + DB_USER + ":" + DB_PASS + "@localhost:15432/jknow";
        for (String name : sourceNames) {
            UUID ds = datasources.create(name, "postgres", conn, user);
            datasources.grant(ds, ws, user);
            datasources.mount(kb, ds);
        }
        return new Fx(org, ws, kb, user, model);
    }

    private void cleanup(Fx f) {
        // 只删自建的：kb（连带挂载/实体/映射/告警）、data_sources、org。用户软删，
        // 不造产品里不存在的 DELETE FROM users
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        jdbc.update("DELETE FROM data_sources WHERE created_by = ?", f.user());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        f.model.stop();
    }

    /**
     * 分类账逐码落位：kind 不对丢 kind、源名对不上丢 source（多源才有歧义可言）、
     * definition 不是对象丢 definition；描述先落库且不依赖提议成不成。
     */
    @Test
    void aDropLedgerSaysWhyEachProposalDidNotLand() throws Exception {
        // 一轮的 propose 回四条：好一条 + 三种丢法（kind/source/definition）
        Fx f = seed(List.of(DESCRIBE,
                "[{\"name\": \"营收\", \"kind\": \"metric\", \"source\": \"warehouse\", "
                        + "\"definition\": {\"table\": \"dw.orders\", \"expr\": "
                        + "\"sum(amount)\"}, \"summary\": \"s\", \"rationale\": \"r\"},"
                        + "{\"name\": \"区域\", \"kind\": \"fact\", \"source\": "
                        + "\"warehouse\", \"definition\": {\"table\": \"dw.region\"}},"
                        + "{\"name\": \"在库天数\", \"kind\": \"dimension\", \"source\": "
                        + "\"tpch\", \"definition\": {\"table\": \"dw.days\", \"expr\": "
                        + "\"days\"}},"
                        + "{\"name\": \"退款额\", \"kind\": \"metric\", \"source\": "
                        + "\"warehouse\", \"definition\": \"not an object\"}]"),
                List.of("warehouse", "lakehouse"));
        try {
            explore.exploreMappings(f.kb());

            List<ExplorationRun> got = runs.recent(f.kb(), 5);
            assertEquals(1, got.size());
            ExplorationRun r = got.get(0);
            assertEquals(4, r.returned(), "模型回了四条");
            assertEquals(1, r.accepted(), "只有营收落进待看");
            JsonNode dropped = r.dropped();
            assertEquals(1, dropped.path("kind").path("n").asInt(), "kind 不对丢 kind");
            assertTrue(dropped.path("kind").path("example").asText().contains("fact"));
            assertEquals(1, dropped.path("source").path("n").asInt(), "源名对不上丢 source");
            assertTrue(dropped.path("source").path("example").asText().contains("tpch"),
                    "例子要说清模型说的是什么、挂的是什么");
            assertEquals(1, dropped.path("definition").path("n").asInt(),
                    "definition 不是对象丢 definition");
            assertEquals(1, r.tablesCovered().size(), "覆盖率的分子=声明了的表");
            assertEquals("dw.orders", r.tablesCovered().get(0));
            assertEquals(1, mappings.page(f.kb(), "proposed", null, 100, 0).items().size());
            // 描述先落库，且不依赖提议成不成
            String desc = jdbc.queryForObject(
                    "SELECT data_description FROM knowledge_bases WHERE id = ?", String.class,
                    f.kb());
            assertTrue(desc != null && desc.contains("dw.orders"), "数据描述已写");
        } finally {
            cleanup(f);
        }
    }

    /**
     * 单源时模型说什么都算它（#501）；表过态的下一轮不复活（decided），整轮空提
     * 走告警中心说一声——告警要带是怎么空的（dropped 明细）。
     */
    @Test
    void aSingleSourceTakesAnyNameAndADecidedMappingStaysDecided() throws Exception {
        // 第一轮：源挂的是 warehouse，模型照 schema 回了个短名——单源没有歧义，照收。
        // 两轮各两帧（describe + propose），第二轮同一条再算出来
        Fx f = seed(List.of(DESCRIBE,
                proposal("营收", "metric", "tpch-2026-09-08",
                        "{\"table\": \"dw.orders\", \"expr\": \"sum(amount)\"}"),
                DESCRIBE,
                proposal("营收", "metric", "tpch-2026-09-08",
                        "{\"table\": \"dw.orders\", \"expr\": \"sum(amount)\"}")),
                List.of("warehouse"));
        try {
            explore.exploreMappings(f.kb());
            List<ExplorationRun> first = runs.recent(f.kb(), 5);
            assertEquals(1, first.size());
            assertEquals(1, first.get(0).accepted(), "单源时说什么都算它（#501）");
            var page = mappings.page(f.kb(), "proposed", null, 100, 0);
            assertEquals(1, page.items().size());
            mappings.decide(f.kb(), page.items().get(0).id(), "rejected", f.user());

            // 第二轮：同一条再算出来——决定还在，不回待看；整轮空提要告警
            explore.exploreMappings(f.kb());
            List<ExplorationRun> second = runs.recent(f.kb(), 5);
            assertEquals(2, second.size());
            ExplorationRun r = second.get(0);
            assertEquals(1, r.returned());
            assertEquals(0, r.accepted(), "撞上决定的提议不算写入");
            assertEquals(1, r.dropped().path("decided").path("n").asInt());
            assertTrue(mappings.page(f.kb(), "proposed", null, 100, 0).items().isEmpty(),
                    "拒绝过的不该重新排队");
            Integer alerts = jdbc.queryForObject(
                    "SELECT count(*) FROM alerts WHERE kb_id = ? AND kind = "
                            + "'mapping.exploration_empty'", Integer.class, f.kb());
            assertTrue(alerts != null && alerts >= 1,
                    "accepted=0 走告警中心（MAPPING_EXPLORATION_EMPTY）");
        } finally {
            cleanup(f);
        }
    }
}
