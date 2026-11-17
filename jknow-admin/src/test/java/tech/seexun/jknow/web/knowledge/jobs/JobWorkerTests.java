package tech.seexun.jknow.web.knowledge.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.api.ApiTestBase;

/**
 * jobs/worker HTTP 面重建（P1 任务 8；源 JobWorkerTests 核心语义——MockMvc+LoginUser
 * 重建，Testcontainers 全新库改为共享 dev 栈 + worker 显式开）：noop 端点经活 worker
 * 全链路往返（queued→running→succeeded）、未知 kind 退 failed。
 * worker 打开是测试专属上下文（inlined properties 盖过缺省 false）；
 * {@link DirtiesContext} 类后即关——活 worker 不随缓存上下文常驻轮询共享库
 * （否则后续测试类的 queued 断言会被它抢跑）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "jknow.jobs.worker-enabled=true")
class JobWorkerTests extends ApiTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    tech.seexun.jknow.web.knowledge.jobs.JobWorker worker;

    private UUID orgId;
    private UUID userId;
    private UUID wsId;

    @BeforeAll
    void bootstrap() {
        orgId = jdbc.queryForObject(
                "SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        userId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Job Worker')", userId, orgId,
                "t8-job-" + RUN + "@test.local");
        wsId = Uuids.nowV7();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 't8-job-ws')", wsId, orgId);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'admin')",
                userId, wsId);
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId);
        jdbc.update("CALL jknow_test_user_cleanup(?)", "t8-job-" + RUN + "@test.local");
    }

    private void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("超时未满足：" + what);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM jobs WHERE id = " + id, String.class);
    }

    @Test
    void noopEndpointRoundTripsThroughTheLiveWorker() throws Exception {
        var resp = post("/api/v1/jobs/noop", userId, "{}");
        assertEquals(200, resp.status(), "noop 端点入队：" + resp.raw());
        long id = resp.body().path("job_id").asLong();
        await("job " + id + " → done", () -> "done".equals(status(id)));
    }

    @Test
    void anUnknownKindBailsToFailed() throws Exception {
        // 未知 kind 直插一行（无端点面），worker 认领即退 failed——HTTP 面只验活 worker 在场
        jdbc.update("""
                INSERT INTO jobs (kind, payload, status, max_attempts)
                VALUES ('bogus_kind', '{}', 'queued', 1)
                """);
        Long id = jdbc.queryForObject(
                "SELECT id FROM jobs WHERE kind = 'bogus_kind' ORDER BY id DESC LIMIT 1", Long.class);
        await("bogus job " + id + " → failed", () -> "failed".equals(status(id)));
    }
}
