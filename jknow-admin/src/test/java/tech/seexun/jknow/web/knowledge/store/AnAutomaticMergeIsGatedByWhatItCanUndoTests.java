package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ExecutionGate;
import tech.seexun.jknow.store.ExecutionGate.Hold;
import tech.seexun.jknow.store.ExecutionGate.Impact;
import tech.seexun.jknow.store.ExecutionGateService;

/**
 * 移植自 tests/store/an_automatic_merge_is_gated_by_what_it_can_undo.rs（342 行，0027）
 * ——执行闸门：一次自动合并能不能落地，看它撤得回什么。三种会离开图的东西，各造一个
 * 最小的例子，再各撤一次，证明闸门看的是「现在」：矛盾（functional 谓词两边各挂一个
 * 不同实体）/ 派生（还成立的派生事实）/ 回答（任一边在对话里被认过）。
 * 什么都不牵动的一对，闸门不拦：把握够就照旧自动。
 */
class AnAutomaticMergeIsGatedByWhatItCanUndoTests extends HeartStoreTestBase {

    @Autowired
    ExecutionGateService gate;

    record Fixture(UUID org, UUID kb, UUID user, UUID ceoOf, UUID partOf, UUID a, UUID b,
                   UUID c1, UUID c2) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        UUID company = Uuids.nowV7();
        UUID ceoOf = Uuids.nowV7();
        UUID partOf = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        UUID c1 = Uuids.nowV7();
        UUID c2 = Uuids.nowV7();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'gate-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'gate-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'gate-test')",
                kb, ws);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'Asker')
                """, user, org, "asker-" + user + "@test.local");
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'company', 'Company')",
                company, kb);
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, functional)
                 VALUES (?, ?, 'ceo_of', 'CEO of', TRUE)
                """, ceoOf, kb);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'part_of', 'part of')",
                partOf, kb);
        for (Object[] e : new Object[][]{{a, "Acme"}, {b, "Acme Corp"}, {c1, "Alice"}, {c2, "Bob"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    e[0], kb, company, e[1]);
        }
        return new Fixture(org, kb, user, ceoOf, partOf, a, b, c1, c2);
    }

    private UUID fact(Fixture f, UUID s, UUID p, UUID o) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, attested_from)
                 VALUES (?, ?, ?, ?, ?, NULL)
                """, id, f.kb(), s, p, o);
        return id;
    }

    private void teardown(Fixture f) {
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        jdbc.update("CALL jknow_test_user_cleanup_uuid(?)", f.user());
        int gone = jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        assertEquals(1, gone, "一次性 org 没删掉");
    }

    @Test
    void a_merge_that_would_leave_the_graph_is_held() {
        Fixture f = seed();
        try {
            // 什么都没有：不拦
            Impact none = gate.impactOf(f.kb(), f.a(), f.b());
            assertNull(ExecutionGate.hold(none), "两边空空的一对，闸门不该拦");

            // 矛盾：a 的 CEO 是 Alice，b 的 CEO 是 Bob。合了就是一个实体两个 CEO
            fact(f, f.a(), f.ceoOf(), f.c1());
            UUID clash = fact(f, f.b(), f.ceoOf(), f.c2());
            Impact i = gate.impactOf(f.kb(), f.a(), f.b());
            assertEquals(java.util.List.of("CEO of"), i.contradictions);
            assertEquals(Hold.contradiction("CEO of"), ExecutionGate.hold(i));
            // 反过来问也一样
            Impact reversed = gate.impactOf(f.kb(), f.b(), f.a());
            assertEquals(Hold.contradiction("CEO of"), ExecutionGate.hold(reversed));

            // 撤掉 b 的那条：矛盾没了。再给 b 一条指向同一个 Alice 的：同一个值不是矛盾
            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", clash);
            fact(f, f.b(), f.ceoOf(), f.c1());
            i = gate.impactOf(f.kb(), f.a(), f.b());
            assertTrue(i.contradictions.isEmpty(), "作废的与同值的都不算");
            assertNull(ExecutionGate.hold(i));

            // 派生：一条靠着 a 推出来的还成立的派生
            UUID rule = Uuids.nowV7();
            jdbc.update(
                    "INSERT INTO rules (id, kb_id, predicate_id, kind) VALUES (?, ?, ?, 'transitive')",
                    rule, f.kb(), f.partOf());
            UUID derived = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_id, rule_id)
                     VALUES (?, ?, ?, ?, ?, ?)
                    """, derived, f.kb(), f.a(), f.partOf(), f.c2(), rule);
            i = gate.impactOf(f.kb(), f.a(), f.b());
            assertEquals(1L, i.derived);
            assertEquals(Hold.derived(1), ExecutionGate.hold(i));
            jdbc.update("UPDATE derived_facts SET invalidated_at = now() WHERE id = ?", derived);
            assertNull(ExecutionGate.hold(gate.impactOf(f.kb(), f.a(), f.b())),
                    "推翻了的派生不算");

            // 回答：b 在一轮回答里被认过。人的那一轮与认了别人的那一轮都不算
            UUID conv = Uuids.nowV7();
            jdbc.update("INSERT INTO conversations (id, kb_id, user_id) VALUES (?, ?, ?)",
                    conv, f.kb(), f.user());
            for (Object[] m : new Object[][]{{"user", f.b()}, {"assistant", f.c2()},
                    {"assistant", f.b()}}) {
                jdbc.update("""
                        INSERT INTO conversation_messages (id, conversation_id, role, content, resolved)
                         VALUES (?, ?, ?, 'hi', ?::jsonb)
                        """, Uuids.nowV7(), conv, m[0],
                        "[{\"id\": \"" + m[1] + "\", \"name\": \"x\", \"type\": null}]");
            }
            i = gate.impactOf(f.kb(), f.a(), f.b());
            assertEquals(1L, i.answered, "只数认了 a 或 b 的那些回答");
            assertEquals(Hold.answered(1), ExecutionGate.hold(i));
            assertEquals("answered 1", ExecutionGate.hold(i).toString());
        } finally {
            teardown(f);
        }
    }

    /**
     * 一个值接替了另一个：两个名字各挂一个、合起来是先后接替的时间线，不是矛盾。
     * 同一天开始的两个值才排不开（Blackbaud 总部租约：房东先 HPBB1、后 BBHQ1，
     * 「Lease Agreement」与「Lease Agreement dated May 16, 2016」各挂一个，从前闸门把
     * 这一对当矛盾留给人，同一份租约就一直是两个实体）。
     */
    @Test
    void a_value_that_took_over_from_another_is_not_a_contradiction() {
        Fixture f = seed();
        Fixture g = seed();
        try {
            dated(f, f.a(), f.c1(), "2019-01-01");
            dated(f, f.b(), f.c2(), "2021-01-01");
            Impact succession = gate.impactOf(f.kb(), f.a(), f.b());

            sameDay(g, g.a(), g.c1());
            sameDay(g, g.b(), g.c2());
            Impact clash = gate.impactOf(g.kb(), g.a(), g.b());

            assertNull(ExecutionGate.hold(succession), "先后接替不是矛盾");
            assertEquals(Hold.contradiction("CEO of"), ExecutionGate.hold(clash));
        } finally {
            teardown(f);
            teardown(g);
        }
    }

    private void dated(Fixture f, UUID s, UUID o, String from) {
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                    valid_from, valid_from_precision)
                 VALUES (?, ?, ?, ?, ?, ?::timestamptz, 'day')
                """, Uuids.nowV7(), f.kb(), s, f.ceoOf(), o, from + "T00:00:00Z");
    }

    private void sameDay(Fixture f, UUID s, UUID o) {
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                    valid_from, valid_from_precision)
                 VALUES (?, ?, ?, ?, ?, '2020-01-01T00:00:00Z', 'day')
                """, Uuids.nowV7(), f.kb(), s, f.ceoOf(), o);
    }

    /** 一边在合并前就有的冲突不算合并带进来的（子集跳过）。 */
    @Test
    void a_clash_one_side_already_had_is_not_the_merges() {
        Fixture f = seed();
        try {
            // b 自己已经挂着两个没日期的值；a 只挂着其中一个
            fact(f, f.b(), f.ceoOf(), f.c1());
            fact(f, f.b(), f.ceoOf(), f.c2());
            fact(f, f.a(), f.ceoOf(), f.c1());
            Impact impact = gate.impactOf(f.kb(), f.a(), f.b());
            assertTrue(impact.contradictions.isEmpty(),
                    "the clash was on b before the merge");
        } finally {
            teardown(f);
        }
    }
}
