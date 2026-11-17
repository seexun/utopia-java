package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tech.seexun.jknow.store.Names;

/**
 * 移植自 crates/utopia-store/src/names.rs 内联 mod tests（:320-351，2 例）——两个名字
 * 都带数字而数字不一样时，召回不把它们配成一对（#1104）。等价点：无（纯函数直译，
 * digit_runs 按码点遍历对 Rust chars()）。
 */
class NamesNumbersDifferTests {

    @Test
    void names_with_different_numbers_are_not_a_pair() {
        for (String[] pair : new String[][]{
                {"migration 394", "migration 395"},
                {"#440", "#441"},
                {"海洋探测器1号", "海洋探测器2号"},
                {"api/main.py:84-142", "api/main.py:145-169"},
                {"migration 39", "migration 395"},
                {"v1.2", "v1.3"}}) {
            assertTrue(Names.numbersDiffer(pair[0], pair[1]),
                    () -> pair[0] + " / " + pair[1]);
        }
    }

    @Test
    void the_same_numbers_or_none_on_one_side_still_pair() {
        for (String[] pair : new String[][]{
                {"海洋探测器1号", "海探1"},
                {"沧海", "沧海分布式推理平台 2.0"},
                {"migration 0106", "migration 106"},
                {"Ｑ３ ２０２４", "2024 Q3"},
                {"sección 01010101", "Sección 01010101 (Distrito Nacional)"},
                {"Sherlock Holmes", "Mr. Holmes"}}) {
            assertFalse(Names.numbersDiffer(pair[0], pair[1]),
                    () -> pair[0] + " / " + pair[1]);
        }
    }
}
