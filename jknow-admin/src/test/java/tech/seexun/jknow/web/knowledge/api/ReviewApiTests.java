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
 * review 域 HTTP 面重建（P1 任务 8；源 ReviewApiTests 核心语义——原 TestRestTemplate+
 * utopia_token cookie 形态退役，MockMvc+LoginUser 重建，断言精简到主路径）：
 * 列表三键/counts 字段集与缺省队列、lowconf 升序与 limit 钳位、各队列名 200、
 * 未知队列 422 稳定 code、history 翻页钳位、未知库 404、无凭据 401。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReviewApiTests extends ApiTestBase {

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
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Rv Api Admin', TRUE)",
                adminId, orgId, "t8-rv-" + RUN + "@test.local");
        wsId = Uuids.nowV7();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)",
                wsId, orgId, "ws-t8-rv-" + RUN);
        kbId = Uuids.nowV7();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)",
                kbId, wsId, "kb-t8-rv-" + RUN);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'owner')",
                adminId, wsId);

        UUID rel = UUID.randomUUID();
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'employs', 'Employs')",
                rel, kbId);
        UUID a = insertEntity("Alpha");
        UUID b = insertEntity("Beta");
        UUID c = insertEntity("Gamma");
        insertFact(a, b, 0.1);
        insertFact(b, c, 0.2);
        insertFact(c, a, 0.3);
        jdbc.update("INSERT INTO resolution_reviews (id, kb_id, left_id, right_id, score, stage, created_at) "
                + "VALUES (?, ?, ?, ?, 0.9, 'human', now() - interval '7 minutes')",
                UUID.randomUUID(), kbId, a, b);
        jdbc.update("INSERT INTO audit_events (id, kb_id, actor_id, action, target_kind, detail) "
                + "VALUES (?, ?, ?, 'review.keep', 'review', '{}')", UUID.randomUUID(), kbId, adminId);
        jdbc.update("INSERT INTO audit_events (id, kb_id, actor_id, action, target_kind, detail) "
                + "VALUES (?, ?, ?, 'fact.confirm', 'fact', '{}')", UUID.randomUUID(), kbId, adminId);
    }

    private UUID insertEntity(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name, created_at) "
                + "VALUES (?, ?, ?, now() - interval '15 minutes')", id, kbId, name);
        return id;
    }

    private void insertFact(UUID subject, UUID object, double confidence) {
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence, recorded_at) "
                        + "VALUES (?, ?, ?, (SELECT id FROM relation_types WHERE kb_id = ? AND key = 'employs'), ?, ?, "
                        + "now() - interval '10 minutes')",
                UUID.randomUUID(), kbId, subject, kbId, object, confidence);
    }

    @AfterAll
    void cleanup() {
        // audit_events append-only：台账行留账（随 kb 级联不可见），与源同口径
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId);
        jdbc.update("CALL jknow_test_user_cleanup(?)", "t8-rv-" + RUN + "@test.local");
    }

    @Test
    void reviewListShapeCountsAndDefaultQueue() throws Exception {
        Resp resp = get("/api/v1/kbs/" + kbId + "/review", adminId);
        assertEquals(200, resp.status(), resp.raw());
        assertTrue(resp.body().has("counts") && resp.body().has("queue") && resp.body().has("items"), "三键");
        assertEquals("duplicates", resp.body().get("queue").asText(), "queue 缺省 duplicates");
        var counts = resp.body().get("counts");
        for (String f : new String[]{"pending", "duplicates", "duplicates_same_type", "duplicates_type_conflict",
                "conflicts", "unconfirmed", "lowconf", "mappings", "violations", "alignment", "errata",
                "defects", "merges", "agent", "agent_rows", "agent_running", "agent_queue"}) {
            assertTrue(counts.has(f), "counts 字段集含 " + f);
        }
        assertEquals(3, counts.get("lowconf").asLong(), "三条低置信");
        assertEquals(1, counts.get("duplicates").asLong(), "一对重复");
        assertEquals(1, resp.body().get("items").size());
        var item = resp.body().get("items").get(0);
        assertTrue(item.has("id") && item.has("score") && item.has("stage") && item.has("left")
                && item.has("right") && item.has("proposal"), "审核项字段集");
    }

    @Test
    void lowconfQueueOrderAndLimitClamp() throws Exception {
        Resp zero = get("/api/v1/kbs/" + kbId + "/review?queue=lowconf&limit=0", adminId);
        assertEquals(200, zero.status());
        assertEquals(1, zero.body().get("items").size(), "limit=0 钳到 1");
        assertEquals(0.1, zero.body().get("items").get(0).get("confidence").asDouble(), 1e-9,
                "置信度最低的在前（升序）");
        Resp all = get("/api/v1/kbs/" + kbId + "/review?queue=lowconf&limit=999999", adminId);
        assertEquals(3, all.body().get("items").size(), "上限 200 内全回");
        Resp page = get("/api/v1/kbs/" + kbId + "/review?queue=lowconf&limit=1&offset=1", adminId);
        assertEquals(0.2, page.body().get("items").get(0).get("confidence").asDouble(), 1e-9, "翻页");
    }

    @Test
    void everyQueueNameResponds200() throws Exception {
        for (String q : new String[]{"pending", "duplicates", "conflicts", "unconfirmed", "lowconf",
                "mappings", "violations", "defects", "alignment", "errata", "merges", "agent"}) {
            assertEquals(200, get("/api/v1/kbs/" + kbId + "/review?queue=" + q + "&limit=5&types=any",
                    adminId).status(), "queue=" + q);
        }
    }

    @Test
    void unknownQueueIs422WithCode() throws Exception {
        Resp resp = get("/api/v1/kbs/" + kbId + "/review?queue=no-such-queue", adminId);
        assertEquals(422, resp.status());
        assertEquals("unknown_queue", resp.body().get("code").asText(), "稳定 code");
    }

    @Test
    void historyPaginatesWithPerClamp() throws Exception {
        Resp resp = get("/api/v1/kbs/" + kbId + "/review/history?page=0&per=0", adminId);
        assertEquals(200, resp.status());
        assertEquals(1, resp.body().get("events").size(), "per=0 钳到 1");
        assertEquals(2, resp.body().get("total").asLong(), "两条台账");
        Resp page2 = get("/api/v1/kbs/" + kbId + "/review/history?page=1&per=1", adminId);
        assertEquals(1, page2.body().get("events").size(), "第二页一条");
        var e = page2.body().get("events").get(0);
        assertTrue(e.has("action") && e.has("actor_name") && e.has("created_at"), "台账字段集");
    }

    @Test
    void authAndUnknownKb() throws Exception {
        // 无凭据：若依 entry point（HTTP 200 + 体 code 401 的 RuoYi 原生形状）
        assertEquals(401, get("/api/v1/kbs/" + kbId + "/review", null).code(), "无凭据不进业务面");
        assertEquals(404, get("/api/v1/kbs/" + UUID.randomUUID() + "/review", adminId).status());
    }
}
