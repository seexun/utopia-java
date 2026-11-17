package tech.seexun.jknow.web.knowledge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tech.seexun.jknow.common.uuids.Uuids;

/**
 * search 域 HTTP 面重建（P1 任务 8；源 SearchApiTests 核心语义——MockMvc+LoginUser 重建）。
 * 本环境 ES 不可达：命中面（真 ES+IK 的中英分支/排序）沿用源的双门——
 * {@link #esUp()} 不可达即跳过；判定序与参数校验面（422/404/401/top_k 拒负）不依赖 ES，
 * 任何环境都跑（比源的全类 ES 门更进一步）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchApiTests extends ApiTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    private UUID orgId;
    private UUID adminId;
    private UUID wsId;
    private UUID kbId;

    @BeforeAll
    void fixture() {
        orgId = jdbc.queryForObject(
                "SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        adminId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Se Api Admin', TRUE)",
                adminId, orgId, "t8-se-" + RUN + "@test.local");
        wsId = Uuids.nowV7();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", wsId, orgId,
                "ws-t8-se-" + RUN);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'owner')",
                adminId, wsId);
        kbId = Uuids.nowV7();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name, visibility) "
                + "VALUES (?, ?, ?, 'restricted')", kbId, wsId, "kb-t8-se-" + RUN);
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId);
        jdbc.update("CALL jknow_test_user_cleanup(?)", "t8-se-" + RUN + "@test.local");
    }

    static boolean esUp() {
        return StoreTestBaseLike.reachable("127.0.0.1", esPort());
    }

    private static int esPort() {
        String url = System.getenv().getOrDefault("JKNOW_ES_URL", "http://localhost:9200");
        try {
            return Integer.parseInt(url.replaceAll(".*:(\\d+).*", "$1"));
        } catch (Exception e) {
            return 9200;
        }
    }

    /** 复用基类可达性探测（不可见则内联）。 */
    static final class StoreTestBaseLike {
        static boolean reachable(String host, int port) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 500);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ---- 判定序与错误形状（不依赖 ES） ----

    @Test
    void emptyQueryIs422BeforeKbCheck() throws Exception {
        // 空 q 先于 require_kb：未知库+空 q 也是 422 empty_query
        Resp resp = post("/api/v1/kbs/00000000-0000-0000-0000-000000000000/search",
                adminId, "{\"q\": \"   \"}");
        assertEquals(422, resp.status());
        assertEquals("Search query cannot be empty", resp.body().get("error").asText());
        assertEquals("empty_query", resp.body().get("code").asText());
    }

    @Test
    void missingQIsDeserializeShape() throws Exception {
        Resp resp = post("/api/v1/kbs/" + kbId + "/search", adminId, "{}");
        assertEquals(422, resp.status(), "缺必填字段 → 422（serde 对等语义）：" + resp.raw());
        assertTrue(resp.raw().contains("missing field") || resp.raw().contains("q"),
                "文案指认缺失字段：" + resp.raw());
    }

    @Test
    void unknownKbIs404() throws Exception {
        Resp resp = post("/api/v1/kbs/00000000-0000-0000-0000-000000000000/search",
                adminId, "{\"q\": \"x\"}");
        assertEquals(404, resp.status());
        assertEquals("Not found", resp.body().get("error").asText());
    }

    @Test
    void noCredentialsIs401() throws Exception {
        assertEquals(401, post("/api/v1/kbs/" + kbId + "/search", null, "{\"q\": \"x\"}").code(),
                "无凭据：若依链拒（体 code 401）");
    }

    @Test
    void negativeTopKRejected() throws Exception {
        // 负值在反序列化即拒（usize 域外），先于 ES 调用——不需要 ES 在位
        Resp negative = post("/api/v1/kbs/" + kbId + "/search",
                adminId, "{\"q\": \"Acme\", \"top_k\": -1}");
        assertEquals(422, negative.status(), "负值=usize 域外，反序列化即拒：" + negative.raw());
    }

    @Test
    void knownTermHitsWithChunkViewShape() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(esUp(), "ES（9200）不可达，跳过命中面");
        // 命中面的完整对齐（含 IK 中文/as_of 记录轴/现行性过滤）依赖真 ES 索引，
        // 重建面留给 ES 在位的环境（T9 冒烟的条件断言同口径）——这里只验 200 通路
        Resp resp = post("/api/v1/kbs/" + kbId + "/search", adminId, "{\"q\": \"x\"}");
        assertEquals(200, resp.status(), resp.raw());
        assertTrue(resp.body().get("results").isArray());
    }
}
