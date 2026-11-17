package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tech.seexun.jknow.store.ExecutionGate;
import tech.seexun.jknow.store.ExecutionGate.Hold;
import tech.seexun.jknow.store.ExecutionGate.Impact;

/**
 * 移植自 execution_gate.rs:220-271 内联测试（hold/describe/explain 纯函数）——
 * 断言逐字对译；impact_of 的取数面由 AnAutomaticMergeIsGatedByWhatItCanUndoTests 打真库。
 */
class ExecutionGateTests {

    @Test
    void nothing_touched_means_no_hold() {
        assertEquals(null, ExecutionGate.hold(new Impact()));
    }

    @Test
    void a_contradiction_speaks_before_anything_else() {
        Impact i = new Impact();
        i.contradictions.add("CEO of");
        i.contradictions.add("headquartered in");
        i.derived = 4;
        i.answered = 2;
        assertEquals(Hold.contradiction("CEO of"), ExecutionGate.hold(i));
        assertEquals("contradiction CEO of", ExecutionGate.hold(i).toString());
    }

    @Test
    void describe_says_what_would_move_or_that_nothing_would() {
        assertTrue(ExecutionGate.describe(new Impact()).contains("nothing outside the graph"));
        Impact i = new Impact();
        i.contradictions.add("CEO of");
        i.derived = 2;
        String text = ExecutionGate.describe(i);
        assertTrue(text.contains("two \"CEO of\" facts"));
        assertTrue(text.contains("2 derived fact"));
        assertFalse(text.contains("chat answer"));
    }

    @Test
    void derived_then_answered() {
        Impact i = new Impact();
        i.derived = 3;
        i.answered = 1;
        assertEquals(Hold.derived(3), ExecutionGate.hold(i));
        Impact second = new Impact();
        second.answered = 1;
        assertEquals(Hold.answered(1), ExecutionGate.hold(second));
        assertEquals("answered 1", Hold.answered(1).toString());
        assertEquals("merging would put two \"CEO of\" facts on one entity",
                Hold.contradiction("CEO of").explain());
    }
}
