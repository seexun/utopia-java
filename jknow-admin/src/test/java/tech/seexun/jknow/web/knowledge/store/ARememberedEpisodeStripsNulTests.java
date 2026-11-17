package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.MemoryService;

/**
 * 移植自 tests/store/a_remembered_episode_strips_nul.rs（1 例）——{@code remember} 工具把
 * 一句记忆落成 chunk 之前先剥 NUL 字节（#665；与 pipeline 的 process_document 共用
 * without_nul，记忆路径不经 ingest parse，独立测一遍）。断言逐条不改写。
 */
class ARememberedEpisodeStripsNulTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MemoryService memory;

    @Test
    void aNulInARememberedEpisodeIsStrippedBeforeChunksText() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'remember-nul-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'remember-nul-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'remember-nul-test')",
                kb, ws);
        try {
            // 三种 NUL：词里、词与词之间、连续 NUL。serde_json 把 JSON 的 \u0000 解回 \0，
            // 模型 / MCP 写回路径就是这一路。
            String raw = "Acme\0moved\0its\0\0HQ to Shenzhen on 2026-03-15.";
            MemoryService.Episode episode = memory.appendEpisode(kb, raw,
                    java.time.OffsetDateTime.now());

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT text, char_end FROM chunks WHERE id = ?", episode.chunkId());
            String text = (String) row.get("text");

            assertFalse(text.indexOf('\0') >= 0, () -> "入库后还剩 NUL：" + text);
            // [YYYY-MM-DD HH:MM] 前缀按当时的时间生成；断言只验它之后的内容：
            // 词被剥完后该照原样拼回去，NUL 整段消失、词与词之间的 NUL 也消失。
            int at = text.indexOf("] ");
            String stripped = at >= 0 ? text.substring(at + 2) : "";
            assertEquals("AcmemoveditsHQ to Shenzhen on 2026-03-15.", stripped,
                    "剥完 NUL 之后词该照原来拼回去");
            assertEquals(text.codePointCount(0, text.length()),
                    ((Number) row.get("char_end")).intValue(),
                    "char_end 用的是剥完的那份，不是入库前的那份");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
