package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.temporal.Side;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;
import tech.seexun.jknow.store.temporal.WorldTime;

/**
 * 移植自 tests/store/ended_when_unknown.rs（229 行）——「结束了，但不知道哪天」这个
 * 状态打在真库上：三种结束状态都存得进去；同一断言再来一次「结束了但不知哪天」的
 * 观察不会被并成「什么都没说」；时态引擎不把它当开放行去闭合。等价点：无（直译）。
 */
class EndedWhenUnknownTests extends HeartStoreTestBase {

    @Autowired
    GraphWriteService writes;
    @Autowired
    TemporalService temporal;

    record Fixture(UUID kb, UUID subject, UUID predicate, UUID objectA, UUID objectB) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID predicate = Uuids.nowV7();
        UUID subject = Uuids.nowV7();
        UUID objectA = Uuids.nowV7();
        UUID objectB = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'ended-unknown-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'ended-unknown-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'ended-unknown-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        // functional：主语侧唯一，时态引擎才会对它做闭合对账
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, temporal, functional)
                 VALUES (?, ?, 'leads', 'leads', 'state', TRUE)
                """, predicate, kb);
        for (Object[] e : new Object[][]{{subject, "Akkaraju"}, {objectA, "Weta"},
                {objectB, "Stability"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    e[0], kb, etype, (String) e[1]);
        }
        return new Fixture(kb, subject, predicate, objectA, objectB);
    }

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    /** (valid_to IS NULL, valid_to_precision)。 */
    private Object[] shape(UUID id) {
        return jdbc.queryForObject(
                "SELECT valid_to IS NULL, valid_to_precision FROM facts WHERE id = ?",
                (rs, i) -> new Object[]{rs.getBoolean(1), rs.getString(2)}, id);
    }

    @Test
    void a_relation_the_text_says_is_over_is_not_stored_as_ongoing() {
        Fixture f = seed();
        try {
            // "Akkaraju, former CEO of Weta" —— 结束是原文说的，日期是原文没给的
            var ended = writes.insertFact(f.kb(), f.subject(), f.predicate(), f.objectA(),
                    Validity.starting(t("2020-01-01T00:00:00Z"), "day").endedWhenUnknown(), 0.9f);
            Object[] shape = shape(ended.id());
            assertEquals(Boolean.TRUE, shape[0], "没有日期可写，valid_to 仍然是 NULL");
            assertEquals(WorldTime.ENDED_UNKNOWN, shape[1],
                    "但精度那一位要说出「它结束了」——少了它就跟「仍在持续」分不开");

            // 同一断言再来一次「结束了但不知哪天」的观察，不该被并成"什么都没说"
            var second = writes.insertFact(f.kb(), f.subject(), f.predicate(), f.objectB(),
                    Validity.empty().endedWhenUnknown(), 0.9f);
            assertTrue(second.created(), "它说了事情，不该被当成弱化陈述并掉");
            assertEquals(WorldTime.ENDED_UNKNOWN, shape(second.id())[1]);
            return;
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        }
    }

    /** 时态引擎不把「已结束-不知何时」当成开放行去闭合。 */
    @Test
    void an_already_ended_fact_is_not_treated_as_an_open_claim() {
        Fixture f = seed();
        try {
            // 旧行：结束了，不知哪天
            var old = writes.insertFact(f.kb(), f.subject(), f.predicate(), f.objectA(),
                    Validity.starting(t("2020-01-01T00:00:00Z"), "day").endedWhenUnknown(), 0.9f);
            // 新行：另一个宾语，2024 年开始。functional 关系，引擎会去找"开放行"
            var now = writes.insertFact(f.kb(), f.subject(), f.predicate(), f.objectB(),
                    Validity.starting(t("2024-01-01T00:00:00Z"), "day"), 0.9f);
            var report = temporal.reconcileNewFact(f.kb(), now.id(), f.subject(), f.predicate(),
                    f.objectB(), Side.SUBJECT_SIDE);
            assertEquals(0, report.corrected.size(), "旧行已经结束，不该再被闭合一次");
            assertEquals(0, report.conflicts, "它也不构成矛盾——两段本来就不重叠");
            // 旧行原样未动
            OffsetDateTime still = jdbc.queryForObject(
                    "SELECT valid_to FROM facts WHERE id = ?", OffsetDateTime.class, old.id());
            assertEquals(null, still, "引擎不该凭空给它安一个结束日期");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        }
    }
}
