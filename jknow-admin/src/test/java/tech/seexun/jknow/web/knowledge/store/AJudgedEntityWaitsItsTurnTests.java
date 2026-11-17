package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/a_judged_entity_waits_its_turn.rs（156 行）——0016 C2：类型消解
 * 自动跑，看过的不再回头，同库的任务不重复排。守三件事：
 *
 * <ol>
 * <li><b>自动跑跳过看过的，人点的不跳。</b>打了 type_resolved_at 的实体在
 *     unattended=true 时不出现，false 时照常出现。</li>
 * <li><b>人拍过板的两条路都不碰。</b>type_source='human' 的实体谁也不重判。</li>
 * <li><b>同种任务同载荷排着就不再排。</b>enqueue_unless_queued 第二次返回 null；
 *     那条跑完（不再 queued）之后才能再排。</li>
 * </ol>
 *
 * <p>自建自拆，绝不碰已有的库。<b>共享库适配</b>（Rust 原测试独占库）：共享栈的
 * 常驻 worker 在毫秒级认领 queued 行——排下的行把 run_at 钉到一小时后保持
 * status='queued'（enqueue_unless_queued 的去重语义只看 kind+payload+queued，钉
 * run_at 不改任何断言口径），测试自己置 done、自己删行。
 */
class AJudgedEntityWaitsItsTurnTests extends HeartStoreTestBase {

    @Autowired
    ResolutionService resolution;

    private record Fixture(UUID org, UUID kb, UUID thing) {
    }

    private Fixture f;
    private final List<Long> jobIds = new java.util.ArrayList<>();

    @BeforeEach
    void seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID thing = Uuids.nowV7();
        UUID sub = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'judged-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'judged-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'judged-test')",
                kb, ws);
        // 一个有子类的类：现类还有子类，是候选查询的第三种条件
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                thing, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'gadget', 'Gadget')",
                sub, kb);
        jdbc.update("INSERT INTO entity_type_parents (child_id, parent_id) VALUES (?, ?)", sub, thing);
        f = new Fixture(org, kb, thing);
    }

    @AfterEach
    void teardown() {
        for (Long id : jobIds) {
            jdbc.update("DELETE FROM jobs WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    private UUID entity(String name, String source) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO entities (id, kb_id, type_id, canonical_name, type_source)
                 VALUES (?, ?, ?, ?, ?)
                """, id, f.kb(), f.thing(), name, source);
        return id;
    }

    private List<UUID> candidates(boolean unattended) {
        return resolution.entitiesForTypeResolution(f.kb(), 50, unattended)
                .stream().map(s -> s.id()).toList();
    }

    /**
     * enqueue_unless_queued 的去重 INSERT（JobsMapper insertUnlessQueuedTx 逐字）+
     * run_at 钉到一小时后，<b>同一条语句</b>落地：共享库的常驻 worker 收到 NOTIFY 或
     * 2s 轮询会毫秒级认领 queued 行（Rust 原测试独占库无此问题）；钉在 INSERT 里、
     * 不再走 notifyWorker，行落地时就是不可认领的。去重语义（kind+payload+
     * status='queued' 的 NOT EXISTS）与断言口径一字不改；JobsService 通知面由
     * JobsFrameworkTests（隔离容器）覆盖。
     */
    private Long pinnedEnqueue(String payload) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO jobs (kind, payload, run_at)
                 SELECT 'resolve_types', ?::jsonb, now() + interval '1 hour'
                  WHERE NOT EXISTS (SELECT 1 FROM jobs
                                     WHERE kind = 'resolve_types' AND payload = ?::jsonb
                                       AND status = 'queued')
                 RETURNING id
                """, Long.class, payload, payload);
        if (!ids.isEmpty()) {
            jobIds.add(ids.get(0));
            return ids.get(0);
        }
        return null;
    }

    @Test
    void aJudgedEntityWaitsItsTurn() {
        UUID fresh = entity("Fresh", "inferred");
        UUID seen = entity("Seen", "inferred");
        UUID human = entity("Decided", "human");
        resolution.markTypeJudged(f.kb(), List.of(seen));

        // 1. 自动跑跳过看过的；人点的不跳
        List<UUID> auto = candidates(true);
        assertTrue(auto.contains(fresh));
        assertFalse(auto.contains(seen), "the engine does not look twice on its own");
        List<UUID> manual = candidates(false);
        assertTrue(manual.contains(fresh) && manual.contains(seen));

        // 2. 人拍过板的两条路都不碰
        assertFalse(auto.contains(human));
        assertFalse(manual.contains(human));

        // 3. 同库的任务不重复排
        String payload = "{\"kb_id\": \"" + f.kb() + "\"}";
        Long first = pinnedEnqueue(payload);
        assertNotNull(first);
        assertNull(pinnedEnqueue(payload), "one queued job per base is enough");
        jdbc.update("UPDATE jobs SET status = 'done' WHERE id = ?", first);
        Long third = pinnedEnqueue(payload);
        assertTrue(third != null && !third.equals(first), "once it ran, the next one may queue");
    }
}
