package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/a_name_created_twice_at_once_is_one_entity.rs（80 行）——两份文档
 * 并行抽取，同一个名字同时到达消解：只建一个实体。实测「澜图数据」在同一秒里建了两个，
 * 之后每次提到它都撞上两个候选、再各建一个，一篇语料跑完裂成四个。新建按（库，名字）
 * 拿咨询锁串行化，锁里再查一次。
 *
 * <p>等价点（tokio::join!→固定线程池 + Future.get 全收集）；自建自拆。
 */
class ANameCreatedTwiceAtOnceIsOneEntityTests extends HeartStoreTestBase {

    @Autowired
    ResolutionService resolution;

    @Test
    void a_name_created_twice_at_once_is_one_entity() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'race-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'race-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'race-test')",
                kb, ws);
        UUID company = Uuids.nowV7();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'company', '公司')",
                company, kb);
        try {
            String name = "澜图数据-" + Uuids.nowV7();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Callable<UUID>> jobs = List.of(
                        () -> resolution.resolveMention(kb, company, name, null, null, null,
                                List.of()).entityId(),
                        () -> resolution.resolveMention(kb, company, name, null, null, null,
                                List.of()).entityId(),
                        () -> resolution.resolveMention(kb, company, name, null, null, null,
                                List.of()).entityId(),
                        () -> resolution.resolveMention(kb, company, name, null, null, null,
                                List.of()).entityId());
                UUID first = null;
                for (Future<UUID> fu : pool.invokeAll(jobs)) {
                    UUID id = fu.get();
                    if (first == null) {
                        first = id;
                    }
                    assertTrue(id.equals(first), "四次并行消解落到同一个实体");
                }
                Long rows = jdbc.queryForObject(
                        "SELECT count(*) FROM entities WHERE kb_id = ? AND canonical_name = ?"
                                + " AND merged_into IS NULL",
                        Long.class, kb, name);
                assertEquals(1L, rows, "库里只有一个这个名字");
            } finally {
                pool.shutdownNow();
            }
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
