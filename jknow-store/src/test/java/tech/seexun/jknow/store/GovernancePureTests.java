package tech.seexun.jknow.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.store.GovernanceService.Gate;
import tech.seexun.jknow.store.GovernanceService.NameShape;
import tech.seexun.jknow.store.entity.Precedent;
import tech.seexun.jknow.store.entity.Precedents;
import tech.seexun.jknow.store.entity.TypePairStats;

/**
 * 移植自 crates/utopia-store/src/governance.rs 内联 mod tests（:1153-1505，8 例）——
 * 闸门四律（未定/低把握只建议；分开只看把握；历史动线（同意降线/反对拦住/类型对习惯）；
 * 类型冲突/撤回/人反判先于把握）、settled_by_people 四档、name_shape 全形态（含 5 对
 * 中文多字节——断言的是「有个答案」，防按字节前进的 panic 复发）。纯函数直译断言，
 * <b>不改写</b>（Rust 断言的消息也照搬为 JUnit message）。
 */
class GovernancePureTests {

    private static Precedent p(String action) {
        return new Precedent(UUID.randomUUID(), action, "a", "b",
                OffsetDateTime.parse("2026-11-12T00:00:00Z"), null);
    }

    private static Precedents withStats(long merged, long kept, long reverted) {
        return new Precedents(List.of(), List.of(),
                new TypePairStats(merged, kept, reverted), List.of());
    }

    /** governance.rs:1153 unsure_and_low_confidence_are_proposed。 */
    @Test
    void unsureAndLowConfidenceAreProposed() {
        Precedents none = Precedents.none();
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(null, 0.99f, false, NameShape.UNRELATED, none));
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(false, 0.6f, false, NameShape.UNRELATED, none));
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(true, 0.84f, false, NameShape.UNRELATED, none));
    }

    /** governance.rs:1170 keeping_apart_needs_confidence_only。 */
    @Test
    void keepingApartNeedsConfidenceOnly() {
        Precedents none = Precedents.none();
        assertEquals(Gate.APPLY,
                GovernanceService.gate(false, 0.9f, false, NameShape.UNRELATED, none));
        // 类型冲突的对也能自己分开：分开不改图
        assertEquals(Gate.APPLY,
                GovernanceService.gate(false, 0.9f, true, NameShape.UNRELATED, none));
    }

    /** governance.rs:1184 the_agent_judges_and_history_moves_the_bar。 */
    @Test
    void theAgentJudgesAndHistoryMovesTheBar() {
        // 没有任何历史：agent 自己的把握够就合，不够就问
        Precedents none = Precedents.none();
        assertEquals(Gate.APPLY,
                GovernanceService.gate(true, 0.9f, false, NameShape.UNRELATED, none));
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(true, 0.8f, false, NameShape.UNRELATED, none));
        // 历史同意：线降到 SUPPORTED_CONF
        Precedents pair = new Precedents(List.of(p("review.merge")), List.of(), null, List.of());
        assertEquals(Gate.APPLY,
                GovernanceService.gate(true, 0.8f, false, NameShape.UNRELATED, pair));
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(true, 0.7f, false, NameShape.UNRELATED, pair));
        Precedents name = new Precedents(List.of(),
                List.of(p("merge.manual"), p("review.merge")), null, List.of());
        assertEquals(Gate.APPLY,
                GovernanceService.gate(true, 0.8f, false, NameShape.UNRELATED, name));
        // 同名的决定有合有分：不算同意，回到 agent 自己的线
        Precedents mixed = new Precedents(List.of(),
                List.of(p("review.merge"), p("review.keep")), null, List.of());
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(true, 0.8f, false, NameShape.UNRELATED, mixed));
        assertEquals(Gate.APPLY,
                GovernanceService.gate(true, 0.9f, false, NameShape.UNRELATED, mixed));
        // 类型对的习惯
        assertEquals(Gate.APPLY,
                GovernanceService.gate(true, 0.8f, false, NameShape.UNRELATED,
                        withStats(6, 2, 0)));
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(true, 0.8f, false, NameShape.UNRELATED,
                        withStats(3, 1, 0)),
                "习惯要够 " + GovernanceService.TYPE_PAIR_MIN + " 笔才算");
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(true, 0.8f, false, NameShape.UNRELATED,
                        withStats(6, 2, 1)),
                "撤回过的类型对不算合并的习惯");
        // 分开那一边同样：人一直分开的名字，分开的线也低
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(false, 0.8f, false, NameShape.UNRELATED, none));
        Precedents kept = new Precedents(List.of(p("review.keep")), List.of(), null, List.of());
        assertEquals(Gate.APPLY,
                GovernanceService.gate(false, 0.8f, false, NameShape.UNRELATED, kept));
        assertEquals(Gate.APPLY,
                GovernanceService.gate(false, 0.8f, false, NameShape.UNRELATED,
                        withStats(1, 8, 0)));
    }

    /** governance.rs:1288 a_pair_people_decided_skips_the_agent。 */
    @Test
    void aPairPeopleDecidedSkipsTheAgent() {
        Precedents none = Precedents.none();
        assertEquals(Boolean.TRUE,
                GovernanceService.settledByPeople("Apple", "Apple Inc.", none, true));
        assertEquals(Boolean.FALSE,
                GovernanceService.settledByPeople("Zhang Wei", "Zhang Wei", none, false));
        assertNull(GovernanceService.settledByPeople("Apple", "Apple Inc.", none, null));
        Precedents merged = new Precedents(
                List.of(p("review.merge"), p("merge.manual")), List.of(), null, List.of());
        assertEquals(Boolean.TRUE,
                GovernanceService.settledByPeople("Apple", "Apple Inc.", merged, null));
        assertNull(GovernanceService.settledByPeople("Zhang Wei", "Zhang Wei", merged, null),
                "同名的一对：名字上的先例说不了眼前这两个是谁");
        Precedents mixed = new Precedents(
                List.of(p("review.merge"), p("review.keep")), List.of(), null, List.of());
        assertNull(GovernanceService.settledByPeople("Apple", "Apple Inc.", mixed, null));
        Precedents reverted = new Precedents(List.of(p("review.merge")), List.of(), null,
                List.of(p("merge.revert")));
        assertNull(GovernanceService.settledByPeople("Apple", "Apple Inc.", reverted, true));
    }

    /** governance.rs:1329 a_name_has_a_shape（中文样例逐字——防按字节前进的 panic 复发）。 */
    @Test
    void aNameHasAShape() {
        assertEquals(NameShape.IDENTICAL, GovernanceService.nameShape("Google", "google"));
        assertEquals(NameShape.IDENTICAL,
                GovernanceService.nameShape("The New York Times", "New York Times"));
        assertEquals(NameShape.ABBREVIATION,
                GovernanceService.nameShape("Google DeepMind", "DeepMind"));
        assertEquals(NameShape.ABBREVIATION,
                GovernanceService.nameShape("Adam D'Angelo", "D'Angelo"));
        assertEquals(NameShape.SUFFIX, GovernanceService.nameShape("Apple Inc.", "Apple"));
        assertEquals(NameShape.SUFFIX, GovernanceService.nameShape("Thrive Capital", "Thrive"));
        assertEquals(NameShape.SUFFIX, GovernanceService.nameShape("Meta Platforms", "Meta"));
        assertEquals(NameShape.VERSION, GovernanceService.nameShape("Claude 4 Opus", "Claude"));
        assertEquals(NameShape.VERSION, GovernanceService.nameShape("AlphaFold2", "AlphaFold"));
        assertEquals(NameShape.VERSION, GovernanceService.nameShape("GPT-4.5", "GPT-4"));
        assertEquals(NameShape.VERSION, GovernanceService.nameShape("Lyria 3", "Lyria"));
        assertEquals(NameShape.VERSION, GovernanceService.nameShape(
                "2024 International Mathematical Olympiad",
                "International Mathematical Olympiad"));
        assertEquals(NameShape.EXTENSION,
                GovernanceService.nameShape("DeepMind Health", "DeepMind"));
        // 中文名字不能把它炸掉。下面每一对都要走进「整词判定失败、换个位置再找」
        // 那条路，而那条路从前按字节前进，落在多字节字符中间就 panic——
        // 测试断言的是「有个答案」，不是答案是什么
        for (String[] pair : new String[][]{
                {"繪圖處理器", "繪圖"},
                {"英伟达繪圖繪圖", "繪圖"},
                {"北京中关村科技园", "中关村"},
                {"東京都渋谷区", "渋谷"},
                {"Nvidia 繪圖處理器", "繪圖"}}) {
            GovernanceService.nameShape(pair[0], pair[1]);
            GovernanceService.nameShape(pair[1], pair[0]);
        }
        assertEquals(NameShape.EXTENSION,
                GovernanceService.nameShape("Gemini Robotics-ER", "Gemini Robotics"));
        assertEquals(NameShape.PHRASE,
                GovernanceService.nameShape("OpenAI Ireland Ltd", "OpenAI"));
        assertEquals(NameShape.PHRASE,
                GovernanceService.nameShape("DeepMind Health data sharing", "DeepMind Health"));
        assertEquals(NameShape.IDENTICAL, GovernanceService.nameShape(
                "reinforcement learning (RL)", "reinforcement learning"));
        assertEquals(NameShape.ABBREVIATION, GovernanceService.nameShape(
                "US Securities and Exchange Commission (SEC)",
                "Securities and Exchange Commission"));
        assertEquals(NameShape.ABBREVIATION, GovernanceService.nameShape(
                "United States Department of Defense (DoD)", "Department of Defense"));
        assertEquals(NameShape.IDENTICAL, GovernanceService.nameShape(
                "Amazon Web Services (AWS)", "Amazon Web Services"));
        assertEquals(NameShape.PHRASE,
                GovernanceService.nameShape("Sam Altman's efforts", "Sam Altman"));
        assertEquals(NameShape.PHRASE, GovernanceService.nameShape(
                "share sale led by Thrive Capital", "Thrive Capital"));
        assertEquals(NameShape.PHRASE, GovernanceService.nameShape(
                "psychological abuse from Sam Altman", "Sam Altman"));
        assertEquals(NameShape.PHRASE, GovernanceService.nameShape(
                "MuZero, AlphaStar, AlphaGeometry", "AlphaStar"));
        assertEquals(NameShape.PHRASE, GovernanceService.nameShape(
                "MuZero, AlphaStar, AlphaGeometry", "MuZero"));
        assertEquals(NameShape.ABBREVIATION,
                GovernanceService.nameShape("Time", "Financial Times"));
        assertEquals(NameShape.PHRASE, GovernanceService.nameShape(
                "C++ Standard Library sorting algorithms", "sorting algorithm"));
        assertEquals(NameShape.UNRELATED, GovernanceService.nameShape("Timeline", "Time"));
        assertEquals(NameShape.SUFFIX,
                GovernanceService.nameShape("Altimeter", "Altimeter Capital"));
    }

    /** governance.rs:1427 types_conflict_only_across_families。 */
    @Test
    void typesConflictOnlyAcrossFamilies() {
        assertFalse(GovernanceService.typesConflict("Organization", "Corporation"));
        assertFalse(GovernanceService.typesConflict("SoftwareApplication", "CreativeWork"));
        assertFalse(GovernanceService.typesConflict("Organization", null));
        assertTrue(GovernanceService.typesConflict("Person", "Organization"));
        assertFalse(GovernanceService.typesConflict("City", "Product"));
        assertTrue(GovernanceService.typesConflict("City", "Corporation"));
        assertTrue(GovernanceService.typesConflict("ConferenceEvent", "Person"));
    }

    /** governance.rs:1441 a_version_or_a_phrase_never_merges_on_its_own。 */
    @Test
    void aVersionOrAPhraseNeverMergesOnItsOwn() {
        Precedents none = Precedents.none();
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(true, 0.99f, false, NameShape.VERSION, none));
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(true, 0.99f, false, NameShape.PHRASE, none));
        assertEquals(Gate.APPLY,
                GovernanceService.gate(true, 0.99f, false, NameShape.EXTENSION, none));
        assertEquals(Gate.APPLY,
                GovernanceService.gate(false, 0.9f, false, NameShape.VERSION, none));
    }

    /** governance.rs:1462 hard_rules_come_first。 */
    @Test
    void hardRulesComeFirst() {
        Precedents pair = new Precedents(List.of(p("review.merge")), List.of(), null, List.of());
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(true, 0.99f, true, NameShape.UNRELATED, pair),
                "类型冲突不合");
        Precedents reverted = new Precedents(List.of(p("review.merge")), List.of(), null,
                List.of(p("merge.revert")));
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(true, 0.99f, false, NameShape.UNRELATED, reverted));
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(false, 0.99f, false, NameShape.UNRELATED, reverted));
        Precedents kept = new Precedents(List.of(p("review.keep")), List.of(), null, List.of());
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(true, 0.99f, false, NameShape.UNRELATED, kept),
                "人分开过这一对，模型说合，只建议");
        Precedents merged = new Precedents(List.of(p("review.merge")), List.of(), null,
                List.of());
        assertEquals(Gate.PROPOSE,
                GovernanceService.gate(false, 0.99f, false, NameShape.UNRELATED, merged),
                "人合过这一对，模型说分，只建议");
    }

    private static void assertNull(Object o) {
        assertTrue(o == null, () -> "expected null but was: " + o);
    }

    private static void assertNull(Object o, String message) {
        assertTrue(o == null, message);
    }
}
