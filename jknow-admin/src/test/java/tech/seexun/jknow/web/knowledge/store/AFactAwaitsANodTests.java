package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.MemoryService;
import tech.seexun.jknow.store.PendingService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_fact_awaits_a_nod.rs（210 行，0015/迁移 0018）——守的是那次实测的
 * 反面：对话里说「Acme 把总部搬到了深圳」，图上<b>不能</b>立刻多出一条活边。提议只进
 * pending_facts；人点头之后它才按抽取那条路进账本（事实 + 证据指回那句话 + 时态对账）；
 * 摇头之后同一个三元组不再被提。
 *
 * <p>等价点：append_episode 走 MemoryService（memory.rs 逐字）；confirm 的时态对账走
 * TemporalService.reconcileNewFact（temporal.rs 引擎真身，任务 2）。
 */
class AFactAwaitsANodTests extends HeartStoreTestBase {

    @Autowired
    PendingService pending;
    @Autowired
    MemoryService memory;

    record Fixture(UUID org, UUID kb, UUID acme, UUID shenzhen, UUID shanghai, UUID hq,
                   UUID chunk) {
    }

    private Fixture fixture() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'nod-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'nod-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'nod-test')",
                kb, ws);
        // 总部只有一个：functional 让时态对账有事可做
        UUID hq = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, functional)
                 VALUES (?, ?, 'headquartered_in', 'headquartered in', TRUE)
                """, hq, kb);
        UUID acme = Uuids.nowV7();
        UUID shenzhen = Uuids.nowV7();
        UUID shanghai = Uuids.nowV7();
        for (Object[] e : new Object[][]{{acme, "Acme"}, {shenzhen, "Shenzhen"},
                {shanghai, "Shanghai"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, ?)",
                    e[0], kb, e[1]);
        }
        // 那句记忆，走产品自己的路落成 chunk
        var episode = memory.appendEpisode(kb,
                "Acme moved its headquarters to Shenzhen on 2026-03-15.",
                OffsetDateTime.now());
        return new Fixture(org, kb, acme, shenzhen, shanghai, hq, episode.chunkId());
    }

    private static OffsetDateTime day(String s) {
        return OffsetDateTime.parse(s + "T00:00:00Z");
    }

    private long liveFacts(UUID kb) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM facts WHERE kb_id = ? AND invalidated_at IS NULL",
                Long.class, kb);
    }

    private PendingService.Outcome propose(Fixture f, UUID object, String from) {
        return pending.propose(new PendingService.Proposal(f.kb(), f.acme(), f.hq(), object, null,
                "moved headquarters to",
                new Validity(day(from), "day", null, null, null, null), 0.9f, f.chunk(), null,
                null, null, null, null, null));
    }

    @Test
    void a_remembered_fact_waits_for_a_nod() {
        Fixture f = fixture();
        try {
            // 1. 提议不上图
            var first = propose(f, f.shenzhen(), "2026-03-15");
            assertTrue(first.isProposed(), "第一次该进队列");
            assertEquals(0, liveFacts(f.kb()), "提议阶段图上不能有活边");
            var queued = pending.forChunk(f.kb(), f.chunk());
            assertEquals(1, queued.size());
            assertEquals("Acme", queued.get(0).subjectName());
            assertTrue(queued.get(0).quote().contains("Acme moved its headquarters"),
                    "原句要跟着提议一起给人看");

            // 2. 同一句重抽不重复提
            assertEquals(PendingService.Outcome.alreadyPending(),
                    propose(f, f.shenzhen(), "2026-03-15"));

            // 3. 点头：进账本，证据指回那句话，队列清空
            var done = pending.confirm(f.kb(), first.id());
            assertTrue(done.created(), "确认该落一条新事实");
            assertEquals(1, liveFacts(f.kb()));
            var ev = jdbc.queryForMap(
                    "SELECT chunk_id, proposed_predicate FROM fact_evidence WHERE fact_id = ?",
                    done.factId());
            assertEquals(f.chunk(), ev.get("chunk_id"), "证据要指回那句记忆");
            assertEquals("moved headquarters to", ev.get("proposed_predicate"));
            assertTrue(pending.forChunk(f.kb(), f.chunk()).isEmpty());

            // 4. 图上已有的不再问
            assertEquals(PendingService.Outcome.alreadyAsserted(),
                    propose(f, f.shenzhen(), "2026-03-15"));

            // 5. 摇头有记忆：拒过的三元组下一轮不再被提
            var bad = propose(f, f.shanghai(), "2020-01-01");
            assertTrue(bad.isProposed(), "换一个宾语该是新提议");
            pending.reject(f.kb(), bad.id(), null);
            assertEquals(PendingService.Outcome.rejected(), propose(f, f.shanghai(), "2020-01-01"));
            assertEquals(1, liveFacts(f.kb()), "拒绝不碰图");

            // 6. 点头走的是抽取那条路：functional 关系的新值闭合旧值。
            //    拒绝记录按 (主语, 谓词, 宾语) 查，同一个宾语换日期照样被挡——
            //    所以换一个宾语实体来演接任
            UUID beijing = Uuids.nowV7();
            jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'Beijing')",
                    beijing, f.kb());
            var next = propose(f, beijing, "2027-01-01");
            assertTrue(next.isProposed(), "接任该是新提议");
            var moved = pending.confirm(f.kb(), next.id());
            assertTrue(moved.created());
            assertEquals(0, moved.conflicts(), "先后清楚、置信够高，该自动闭合而不是进冲突");
            Long closed = jdbc.queryForObject("""
                    SELECT count(*) FROM facts
                      WHERE kb_id = ? AND object_id = ? AND invalidated_at IS NULL AND valid_to IS NOT NULL
                    """, Long.class, f.kb(), f.shenzhen());
            assertEquals(1L, closed, "深圳那条该被闭合成有终点的区间");
        } finally {
            // 删掉夹具自己建的 org，级联带走工作区、知识库与其余一切
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
