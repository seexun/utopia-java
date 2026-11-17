package tech.seexun.jknow.reason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.reason.Derive.Span;
import tech.seexun.jknow.reason.Expressions.Expr;
import tech.seexun.jknow.reason.Expressions.Scalar;
import tech.seexun.jknow.reason.Rules.Arith;
import tech.seexun.jknow.reason.Rules.AttrFact;
import tech.seexun.jknow.reason.Rules.BusinessRule;
import tech.seexun.jknow.reason.Rules.Conclusion;
import tech.seexun.jknow.reason.Rules.Condition;
import tech.seexun.jknow.reason.Rules.Operand;
import tech.seexun.jknow.reason.Rules.Op;
import tech.seexun.jknow.reason.Rules.RuleEdge;
import tech.seexun.jknow.reason.Rules.RuleEval;
import tech.seexun.jknow.reason.Rules.RuleHit;
import tech.seexun.jknow.reason.Rules.Side;

/**
 * 移植自 utopia-reason/src/rules.rs 内联 mod tests（19 例）+ mod joined_tests（2 例）
 * = 21 例——组内合取/组间析取、区间交集、前提链、封顶上报、算出来的结论与门槛、
 * 连接规则两侧读数与边同为前提。断言逐条对译，不改写。
 */
class RulesTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static UUID id(long n) {
        return new UUID(0, n);
    }

    private static AttrFact fact(long fid, long subject, long pred, JsonNode value) {
        return new AttrFact(id(fid), id(subject), id(pred), value);
    }

    private static AttrFact fact(long fid, long subject, long pred, String value) {
        return fact(fid, subject, pred, json(value));
    }

    private static Map<UUID, Span> spans(long fid, Long from, Long to) {
        return Map.of(id(fid), new Span(from, to));
    }

    // ===================== joined_tests（rules.rs:694-779） =====================

    /** A joined rule sees a second entity across exactly one declared edge. */
    @Test
    void aRuleJoinsOneEntityAndConcludesARelation() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Relation(id(2)), id(1), List.of(
                new Condition(0, Side.X, id(10), Op.GT, new Operand.Num(50.0)),
                new Condition(0, Side.Y, id(11), Op.GT, new Operand.Num(50.0))));
        List<AttrFact> facts = List.of(fact(1, 50, 10, "60.0"), fact(2, 51, 11, "70.0"));
        List<RuleEdge> edges = List.of(new RuleEdge(id(9), id(1), id(50), id(51)));
        Map<UUID, Span> spans = Map.of(
                id(1), new Span(100L, 200L),
                id(2), new Span(150L, 300L),
                id(9), new Span(120L, null));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, edges);
        List<RuleHit> hits = eval.hits();
        assertEquals(1, hits.size(), "one edge yields one joined conclusion");
        assertEquals(id(50), hits.get(0).subject());
        assertEquals(id(51), hits.get(0).object());
        assertEquals(150L, hits.get(0).from());
        assertEquals(200L, hits.get(0).to());
        assertTrue(hits.get(0).premises().contains(id(1)));
        assertTrue(hits.get(0).premises().contains(id(2)));
        assertTrue(hits.get(0).premises().contains(id(9)));
    }

    /** A condition on Y cannot manufacture the edge that binds it to X. */
    @Test
    void aJoinedConditionWithoutTheEdgeFiresNothing() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Relation(id(2)), id(1), List.of(
                new Condition(0, Side.Y, id(11), Op.GT, new Operand.Num(50.0))));
        List<AttrFact> facts = List.of(fact(2, 51, 11, "70.0"));
        Map<UUID, Span> spans = Map.of(id(2), new Span(100L, 200L));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        assertTrue(eval.hits().isEmpty());
    }

    // ===================== tests（rules.rs:781-1404） =====================

    /** 全烃 12.3 且解释为气测异常 → 命中，前提正是那两条读数。 */
    @Test
    void aConjunctionFiresAndNamesTheTwoReadings() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Typing("GasBearingWell"), null, List.of(
                new Condition(0, Side.X, id(10), Op.GT, new Operand.Num(8.0)),
                new Condition(0, Side.X, id(11), Op.IN,
                        Rules.set(List.of("气测异常", "气测异常后效")))));
        List<AttrFact> facts = List.of(
                fact(1, 50, 10, "12.3"),
                fact(2, 50, 11, "\"气测异常\""));
        Map<UUID, Span> spans = new HashMap<>();
        spans.put(id(1), new Span(100L, null));
        spans.put(id(2), new Span(100L, null));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        List<RuleHit> hits = eval.hits();
        assertEquals(1, hits.size());
        assertEquals(id(50), hits.get(0).subject());
        assertEquals(List.of(id(1), id(2)), hits.get(0).premises());
        assertEquals(100L, hits.get(0).from());
        assertEquals(1, eval.report().hits());
    }

    /** 合取里少一条就不成立——任一条件没有命中的事实，整条规则在这个实体上就不成立。 */
    @Test
    void aMissingConditionFiresNothing() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Typing("GasBearingWell"), null, List.of(
                new Condition(0, Side.X, id(10), Op.GT, new Operand.Num(8.0)),
                new Condition(0, Side.X, id(11), Op.PRESENT, Rules.none())));
        List<AttrFact> facts = List.of(fact(1, 50, 10, "12.3"));
        Map<UUID, Span> spans = spans(1, 100L, null);
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        assertTrue(eval.hits().isEmpty(), "第二个条件没有任何事实，不该命中");
    }

    /** 同一属性的两次读数各自成立 → 两段区间，而不是一行翻转（0021 决策 4）。 */
    @Test
    void twoReadingsGiveTwoIntervals() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Typing("GasBearingWell"), null, List.of(
                new Condition(0, Side.X, id(10), Op.GT, new Operand.Num(8.0))));
        List<AttrFact> facts = List.of(fact(1, 50, 10, "12.3"), fact(2, 50, 10, "9.9"));
        Map<UUID, Span> spans = Map.of(
                id(1), new Span(100L, 200L),
                id(2), new Span(300L, 400L));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        List<RuleHit> hits = eval.hits();
        assertEquals(2, hits.size(), "两次读数各自成立");
        List<List<Long>> out = new ArrayList<>();
        for (RuleHit h : hits) {
            out.add(List.of(h.from(), h.to()));
        }
        out.sort(java.util.Comparator.<List<Long>>comparingLong(a -> a.get(0)));
        assertEquals(List.of(List.of(100L, 200L), List.of(300L, 400L)), out);
    }

    /** 区间不相交的两条前提凑不成一次命中：全烃是 2023 的，解释是 2025 的。 */
    @Test
    void premisesThatNeverOverlappedFireNothing() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Typing("GasBearingWell"), null, List.of(
                new Condition(0, Side.X, id(10), Op.GT, new Operand.Num(8.0)),
                new Condition(0, Side.X, id(11), Op.IN, Rules.set(List.of("气测异常")))));
        List<AttrFact> facts = List.of(
                fact(1, 50, 10, "12.3"),
                fact(2, 50, 11, "\"气测异常\""));
        Map<UUID, Span> spans = Map.of(
                id(1), new Span(100L, 200L),
                id(2), new Span(300L, 400L));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        assertTrue(eval.hits().isEmpty(), "两条前提没有同时成立的时段");
    }

    /** 两组「或」：任一组成立就命中，前提只挂自己那一组的读数。 */
    @Test
    void eitherGroupCanFireAndCarriesOnlyItsOwnPremises() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Typing("GasBearingWell"), null, List.of(
                new Condition(0, Side.X, id(10), Op.GT, new Operand.Num(8.0)),
                new Condition(0, Side.X, id(11), Op.IN, Rules.set(List.of("气测异常"))),
                new Condition(1, Side.X, id(12), Op.IN, Rules.set(List.of("气层")))));
        List<AttrFact> facts = List.of(fact(3, 50, 12, "\"气层\""));
        Map<UUID, Span> spans = spans(3, 100L, 200L);
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        List<RuleHit> hits = eval.hits();
        assertEquals(1, hits.size(), "第二组独自成立");
        assertEquals(List.of(id(3)), hits.get(0).premises());
        assertEquals(100L, hits.get(0).from());
        assertEquals(200L, hits.get(0).to());
    }

    /** 两组同时成立、且推出同一段区间：一条命中，留下的证明是组序在前的那一组。 */
    @Test
    void twoGroupsOnTheSameIntervalAreOneHit() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Typing("GasBearingWell"), null, List.of(
                new Condition(0, Side.X, id(10), Op.GT, new Operand.Num(8.0)),
                new Condition(1, Side.X, id(11), Op.IN, Rules.set(List.of("气层")))));
        List<AttrFact> facts = List.of(
                fact(1, 50, 10, "12.3"),
                fact(2, 50, 11, "\"气层\""));
        Map<UUID, Span> spans = Map.of(
                id(1), new Span(100L, 200L),
                id(2), new Span(100L, 200L));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        List<RuleHit> hits = eval.hits();
        assertEquals(1, hits.size());
        assertEquals(List.of(id(1)), hits.get(0).premises(), "留下的是组序在前那一组的证明");
        assertEquals(1, eval.report().hits());
    }

    /** 一组展不开不该拖累另一组：封顶按组算，且 (规则, 实体) 对只报一次「没展完」。 */
    @Test
    void aCappedGroupDoesNotStopTheOther() {
        List<AttrFact> facts = new ArrayList<>();
        Map<UUID, Span> spans = new HashMap<>();
        // 第 0 组：同一个属性上 100 条读数，组合数 100 > 64
        for (int i = 1; i <= 100; i++) {
            facts.add(fact(i, 50, 10, "12.3"));
            spans.put(id(i), new Span(100L, 200L));
        }
        // 第 1 组：一条就够
        facts.add(fact(200, 50, 11, "\"气层\""));
        spans.put(id(200), new Span(100L, 200L));
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Typing("GasBearingWell"), null, List.of(
                new Condition(0, Side.X, id(10), Op.GT, new Operand.Num(8.0)),
                new Condition(1, Side.X, id(11), Op.IN, Rules.set(List.of("气层")))));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        List<RuleHit> hits = eval.hits();
        assertEquals(1, hits.size(), "第二组照样出结论");
        assertEquals(List.of(id(200)), hits.get(0).premises());
        assertEquals(1, eval.report().capped(), "(规则, 实体) 只报一次");
    }

    /** is not one of：值在集合外算满足；没有这条读数不算——那是「没记」，不是「不是它」。 */
    @Test
    void notOneOfNeedsAReadingToBeTrue() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Typing("NonGas"), null, List.of(
                new Condition(0, Side.X, id(11), Op.NOT_IN, Rules.set(List.of("气层")))));
        Map<UUID, Span> spans = spans(1, null, null);
        RuleEval hit = Rules.evaluate(List.of(rule),
                List.of(fact(1, 50, 11, "\"水层\"")), spans, List.of());
        assertEquals(1, hit.hits().size(), "读数在集合外");
        RuleEval miss = Rules.evaluate(List.of(rule),
                List.of(fact(1, 50, 11, "\"气层\"")), spans, List.of());
        assertTrue(miss.hits().isEmpty(), "读数在集合里");
        RuleEval none = Rules.evaluate(List.of(rule), List.of(), Map.of(), List.of());
        assertTrue(none.hits().isEmpty(), "没有这条读数：不成立，而不是「不是它」");
    }

    /** 数字被抽成字符串照样比得动——这一条挡的是最难发现的失效：规则从不报错，只是永远不命中。 */
    @Test
    void aNumberInQuotesStillCompares() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Attribute(id(20), json("\"good\"")), null, List.of(
                new Condition(0, Side.X, id(10), Op.GTE, new Operand.Num(12.0))));
        List<AttrFact> facts = List.of(fact(1, 50, 10, "\" 12.3 \""));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans(1, null, null), List.of());
        assertEquals(1, eval.hits().size());
    }

    /** 阈值抬高到读数之上，同一份数据就不再命中——降/升阈值重跑是 0021 的验收项。 */
    @Test
    void raisingTheThresholdClearsTheHit() {
        List<AttrFact> facts = List.of(fact(1, 50, 10, "12.3"));
        Map<UUID, Span> spans = spans(1, null, null);
        java.util.function.DoubleFunction<BusinessRule> mk = threshold -> new BusinessRule(
                id(90), new Conclusion.Typing("GasBearingWell"), null,
                List.of(new Condition(0, Side.X, id(10), Op.GT, new Operand.Num(threshold))));
        assertEquals(1, Rules.evaluate(List.of(mk.apply(8.0)), facts, spans, List.of())
                .hits().size());
        assertTrue(Rules.evaluate(List.of(mk.apply(20.0)), facts, spans, List.of())
                .hits().isEmpty());
    }

    /** 没有条件的规则什么都不推：空合取在逻辑上恒真，会把整个类归进去。 */
    @Test
    void aRuleWithoutConditionsConcludesNothing() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Typing("X"), null, List.of());
        List<AttrFact> facts = List.of(fact(1, 50, 10, "12.3"));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans(1, null, null), List.of());
        assertTrue(eval.hits().isEmpty());
    }

    /** 组合数超上限时报出来，不是静默少推。 */
    @Test
    void tooManyCombinationsAreReported() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Typing("X"), null, List.of(
                new Condition(0, Side.X, id(10), Op.PRESENT, Rules.none()),
                new Condition(0, Side.X, id(11), Op.PRESENT, Rules.none())));
        List<AttrFact> facts = new ArrayList<>();
        Map<UUID, Span> spans = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            facts.add(fact(i, 50, 10, "1"));
            facts.add(fact(i + 100, 50, 11, "1"));
            spans.put(id(i), new Span(null, null));
            spans.put(id(i + 100), new Span(null, null));
        }
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        assertEquals(1, eval.report().capped(), "100 种组合超过上限，要计数");
        assertTrue(eval.hits().isEmpty());
    }

    private static Expr attr(long p) {
        return new Expr.Attr(id(p));
    }

    private static Expr arith(Arith op, Expr l, Expr r) {
        return new Expr.ArithE(op, l, r);
    }

    /** 结论是算出来的：margin = revenue − cost。算式读的两条读数都进前提（0032）。 */
    @Test
    void aComputedConclusionCarriesTheReadingsItRead() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Computed(id(12), arith(Arith.SUB, attr(10), attr(11))), null,
                List.of(new Condition(0, Side.X, id(10), Op.PRESENT, Rules.none())));
        List<AttrFact> facts = List.of(
                fact(1, 50, 10, "300.0"),
                fact(2, 50, 11, "120.0"));
        Map<UUID, Span> spans = new HashMap<>();
        spans.put(id(1), new Span(100L, null));
        spans.put(id(2), new Span(100L, null));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        List<RuleHit> hits = eval.hits();
        assertEquals(1, hits.size());
        assertEquals(new Scalar.Number(180.0), hits.get(0).value());
        // 条件只提到 revenue，可 cost 也读了——它照样是前提
        assertEquals(2, hits.get(0).premises().size());
        assertTrue(hits.get(0).premises().contains(id(1)) && hits.get(0).premises().contains(id(2)));
    }

    /** 每个组合各算各的：两条 revenue 一条 cost，就是两个数、两段区间、两行结论（0032）。 */
    @Test
    void eachCombinationOfReadingsComputesItsOwnValue() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Computed(id(12), arith(Arith.SUB, attr(10), attr(11))), null,
                List.of(new Condition(0, Side.X, id(10), Op.PRESENT, Rules.none())));
        List<AttrFact> facts = List.of(
                fact(1, 50, 10, "300.0"),
                fact(2, 50, 10, "400.0"),
                fact(3, 50, 11, "120.0"));
        Map<UUID, Span> spans = Map.of(
                id(1), new Span(100L, 200L),
                id(2), new Span(200L, 300L),
                id(3), new Span(100L, 300L));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        List<RuleHit> hits = eval.hits();
        assertEquals(2, hits.size(), "两条 revenue 各算一个 margin");
        List<Double> values = new ArrayList<>();
        for (RuleHit h : hits) {
            values.add(h.value().number().getAsDouble());
        }
        values.sort(java.util.Comparator.naturalOrder());
        assertEquals(List.of(180.0, 280.0), values);
    }

    /** 缺一条读数就不出结论——不是当零。没记不等于零。 */
    @Test
    void aMissingReadingComputesNothingRatherThanZero() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Computed(id(12), arith(Arith.SUB, attr(10), attr(11))), null,
                List.of(new Condition(0, Side.X, id(10), Op.PRESENT, Rules.none())));
        List<AttrFact> facts = List.of(fact(1, 50, 10, "300.0"));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans(1, 100L, null), List.of());
        assertTrue(eval.hits().isEmpty(), "cost 没记，margin 就不该有");
    }

    /** 除零同样什么都不出：一条算不出来的结论与一条不成立的结论一样。 */
    @Test
    void dividingByZeroComputesNothing() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Computed(id(12), arith(Arith.DIV, attr(10), attr(11))), null,
                List.of(new Condition(0, Side.X, id(10), Op.PRESENT, Rules.none())));
        List<AttrFact> facts = List.of(
                fact(1, 50, 10, "300.0"),
                fact(2, 50, 11, "0.0"));
        Map<UUID, Span> spans = new HashMap<>();
        spans.put(id(1), new Span(100L, null));
        spans.put(id(2), new Span(100L, null));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        assertTrue(eval.hits().isEmpty());
    }

    /** 门槛也能是算出来的：revenue > cost × 1.5——判断要等组合定下来才做得了。 */
    @Test
    void aThresholdCanBeComputedFromAnotherReading() {
        java.util.function.DoubleFunction<BusinessRule> rule = factor -> new BusinessRule(
                id(90), new Conclusion.Typing("Healthy"), null,
                List.of(new Condition(0, Side.X, id(10), Op.GT,
                        new Operand.Calc(arith(Arith.MUL, attr(11),
                                new Expr.Const(factor))))));
        List<AttrFact> facts = List.of(
                fact(1, 50, 10, "300.0"),
                fact(2, 50, 11, "120.0"));
        Map<UUID, Span> spans = new HashMap<>();
        spans.put(id(1), new Span(100L, null));
        spans.put(id(2), new Span(100L, null));
        RuleEval hits = Rules.evaluate(List.of(rule.apply(1.5)), facts, spans, List.of());
        assertEquals(1, hits.hits().size(), "300 > 120 × 1.5");
        assertEquals(2, hits.hits().get(0).premises().size(), "门槛读的那条也是前提");
        RuleEval none = Rules.evaluate(List.of(rule.apply(3.0)), facts, spans, List.of());
        assertTrue(none.hits().isEmpty(), "300 不大于 120 × 3");
    }

    /** 同一区间上两个不同的值是两行，不是一行——去重的键带上值。 */
    @Test
    void twoValuesOnOneIntervalAreTwoHits() {
        BusinessRule rule = new BusinessRule(id(90),
                new Conclusion.Computed(id(12), attr(10)), null,
                List.of(new Condition(0, Side.X, id(10), Op.PRESENT, Rules.none())));
        List<AttrFact> facts = List.of(
                fact(1, 50, 10, "300.0"),
                fact(2, 50, 10, "400.0"));
        Map<UUID, Span> spans = new HashMap<>();
        spans.put(id(1), new Span(100L, null));
        spans.put(id(2), new Span(100L, null));
        RuleEval eval = Rules.evaluate(List.of(rule), facts, spans, List.of());
        assertEquals(2, eval.hits().size(), "同一段区间，两个值");
    }

    /** 算式的深度算得对——编译那一侧靠它拦住「有人在这里写程序」。 */
    @Test
    void depthCountsTheDeepestBranch() {
        assertEquals(1, Expressions.depth(attr(10)));
        assertEquals(2, Expressions.depth(arith(Arith.ADD, attr(10), attr(11))));
        assertEquals(3, Expressions.depth(
                arith(Arith.DIV, arith(Arith.SUB, attr(10), attr(11)), attr(10))));
    }
}
