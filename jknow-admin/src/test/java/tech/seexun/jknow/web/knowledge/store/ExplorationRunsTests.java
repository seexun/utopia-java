package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ExplorationRunsService;
import tech.seexun.jknow.store.entity.ExplorationRun;

/**
 * 一轮映射探索留下的账（tests/store/an_exploration_says_what_it_covered.rs 2 例逐字
 * 移植，断言不改写）：覆盖率答得出来；跑挂了的那一轮也有账可查。自建自拆，绝不碰已有
 * 的库——只删 knowledge_base，不删 org（用户是软删除的，测试也不该造一个产品里不存
 * 在的动作）。
 */
class ExplorationRunsTests extends StoreTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    ExplorationRunsService runs;

    static UUID org;
    static UUID ws;

    @BeforeAll
    void seedOrg() {
        org = Uuids.nowV7();
        ws = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'run-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'run-test')", ws,
                org);
    }

    @AfterAll
    void cleanupOrg() {
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    /** Rust 每个测试各自 fixture（两轮互不可见）——这里每例一座自拆的库。 */
    private UUID freshKb() {
        UUID kb = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'run-test')",
                kb, ws);
        return kb;
    }

    private void dropKb(UUID kb) {
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
    }

    @Test
    void aRunSaysWhatItScannedAndWhatItDropped() throws Exception {
        UUID kb = freshKb();
        try {
        UUID id = runs.start(kb);
        runs.scanned(id, List.of("warehouse"), 8, 61, false, 24);
        runs.finish(id, 12, 9,
                JSON.readTree("{\"source\": {\"n\": 3, \"example\": "
                        + "\"model said \\\"tpch\\\", mounted: tpch-2026\"}}"),
                List.of("tpch.lineitem", "tpch.orders"));

        List<ExplorationRun> got = runs.recent(kb, 10);
        assertEquals(1, got.size());
        ExplorationRun r = got.get(0);
        assertEquals(8, r.tablesScanned());
        assertEquals(61, r.columnsScanned());
        assertEquals(24, r.cap());
        // 回了 12 条、落库 9 条：两者之差就是被丢掉的，明细在 dropped。从前这三个数
        // 一个都没有，页面上只看得见落库的那 9 条
        assertEquals(12, r.returned());
        assertEquals(9, r.accepted());
        assertEquals(3, r.dropped().path("source").path("n").asInt());
        assertTrue(r.dropped().path("source").path("example").asText().contains("mounted"),
                "光有计数诊断不动，例子要留住");
        assertEquals(2, r.tablesCovered().size(), "覆盖率的分子");
        assertNotNull(r.finishedAt());
        assertNull(r.error());
        } finally {
            dropKb(kb);
        }
    }

    @Test
    void aFailedRunIsNotAnEmptyOne() {
        UUID kb = freshKb();
        try {
        UUID id = runs.start(kb);
        runs.fail(id, "Chat model not configured");

        List<ExplorationRun> got = runs.recent(kb, 10);
        assertEquals(1, got.size(), "跑挂了的那一轮也该留下一行");
        // 这一行与「跑完了但 accepted=0」的区别就在这里。两者在页面上从前都是
        // 「没有新提议」，而一个要去配模型，一个要去给列加注释
        assertEquals(0, got.get(0).accepted());
        assertTrue(got.get(0).error() != null && got.get(0).error().contains("Chat model"));
        } finally {
            dropKb(kb);
        }
    }
}
