package tech.seexun.jknow.reason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.reason.Derive.Span;
import tech.seexun.jknow.reason.Expressions.Expr;
import tech.seexun.jknow.reason.Expressions.ExprError;
import tech.seexun.jknow.reason.Expressions.ExprErrorException;
import tech.seexun.jknow.reason.Expressions.Scalar;

/**
 * 移植自 utopia-reason/src/expressions.rs 内联 mod tests（5 例）——表达式树的编解码、
 * 求值不落默认值、UTC 日历截断、深度/臂数上限、文本结果各留各的区间与证明。
 *
 * <p>第 5 例（text_results_keep_each_readings_interval_and_proof）断言面全在 rules 引擎的
 * {@code evaluate}（rules.rs），任务 6 落地——测试体与断言全量移植（规则形状用本文件的
 * 测试侧镜像 record 承载），引擎调用是抛出的 seam，@Disabled 待任务 6 换真调用
 * （断言一字不动）。
 */
class ExpressionsTests {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    private static Expr parse(String raw) {
        try {
            return Expressions.fromJson(json(raw));
        } catch (ExprErrorException e) {
            throw new RuntimeException(e);
        }
    }

    private static Expr parseJson(JsonNode node) {
        try {
            return Expressions.fromJson(node);
        } catch (ExprErrorException e) {
            throw new RuntimeException(e);
        }
    }

    private static ExprError parseErr(String raw) {
        try {
            Expressions.fromJson(json(raw));
            throw new AssertionError("expected error: " + raw);
        } catch (ExprErrorException e) {
            return e.error;
        }
    }

    @Test
    void conversionsRoundTripAndKeepOnlyTheirInputReferences() {
        Expr expr = parse("""
                {"op":"div", "l":{"cast":"number","expr":{"attr":"%s"}},
                 "r":{"case":{"attr":"%s"},"when":[{"is":"cents","then":100}],"else":1}}
                """.formatted(id(1), id(2)));
        List<UUID> reads = new ArrayList<>();
        Expressions.predicates(expr, reads);
        assertEquals(List.of(id(1), id(2)), reads);
        Map<UUID, Scalar> values = Map.of(
                id(1), new Scalar.Text("12550"),
                id(2), new Scalar.Text("cents"));
        assertEquals(Optional.of(new Scalar.Number(125.5)),
                Optional.ofNullable(Expressions.evaluate(expr, values::get)));
        assertEquals(expr, parseJson(Expressions.toJson(expr)));
        assertEquals(new Expr.Const(2.5), parse("{\"const\":\"2.50\"}"));
        assertEquals(new Expr.TextE("2.50"), parse("{\"text\":\"2.50\"}"));
    }

    @Test
    void missingOrInvalidValuesNeverBecomeADefaultOrZero() {
        Expr cast = parse("{\"cast\":\"number\",\"expr\":{\"attr\":\"%s\"}}".formatted(id(1)));
        for (String value : new String[]{"null", "false", "\"\"", "\"NaN\"", "\"Infinity\"",
                "\"1e999\"", "\"12 CNY\""}) {
            JsonNode node = json(value);
            assertNull(Expressions.evaluate(cast, p -> Scalar.read(node)), value);
        }
        Expr choice = parse("""
                {"case":{"attr":"%s"},"when":[{"is":1,"then":"app"}],"else":"other"}
                """.formatted(id(1)));
        assertNull(Expressions.evaluate(choice, p -> null));
        assertEquals(new Scalar.Text("other"),
                Expressions.evaluate(choice, p -> new Scalar.Number(9.0)));
        Expr noElse = parse(
                "{\"case\":{\"attr\":\"%s\"},\"when\":[{\"is\":1,\"then\":\"app\"}]}"
                        .formatted(id(1)));
        assertNull(Expressions.evaluate(noElse, p -> new Scalar.Number(9.0)));
        // 文本代码不会被 CASE 静默折成数字代码
        assertNull(Expressions.evaluate(noElse, p -> new Scalar.Text("1")));
    }

    @Test
    void calendarTruncationUsesUtcAndRejectsAmbiguousOrInvalidDates() {
        String[][] good = {
            {"month", "2024-03-01T00:30:00+08:00", "2024-02-01"},
            {"day", "2024-02-29T23:30:00-02:00", "2024-03-01"},
            {"year", "2024-01-01T00:30:00+01:00", "2023-01-01"},
            {"day", "2024-02-29", "2024-02-29"},
            {"month", "2024-02-29", "2024-02-01"},
        };
        for (String[] c : good) {
            Expr expr = parse(
                    "{\"date_trunc\":\"%s\",\"expr\":{\"attr\":\"%s\"}}"
                            .formatted(c[0], id(1)));
            assertEquals(new Scalar.Text(c[2]),
                    Expressions.evaluate(expr, p -> new Scalar.Text(c[1])),
                    c[0] + " " + c[1]);
        }
        Expr expr = parse(
                "{\"date_trunc\":\"month\",\"expr\":{\"attr\":\"%s\"}}".formatted(id(1)));
        for (String input : new String[]{"2023-02-29", "2024-02-30", "2024-03-01T00:00:00",
                "yesterday", ""}) {
            assertNull(Expressions.evaluate(expr, p -> new Scalar.Text(input)), input);
        }
    }

    @Test
    void everyNewNodeObeysTheDepthAndCaseBounds() {
        JsonNode raw = json("{\"attr\":\"%s\"}".formatted(id(1)));
        for (int i = 0; i < Expressions.MAX_EXPR_DEPTH; i++) {
            ObjectNode wrap = JsonNodeFactory.instance.objectNode();
            wrap.put("cast", "number");
            wrap.set("expr", raw);
            raw = wrap;
        }
        assertEquals(raw.toString(), Expressions.toJson(parseJson(raw)).toString());
        assertEquals(ExprError.tooDeep().code(),
                parseErr("{\"date_trunc\":\"day\",\"expr\":" + raw + "}").code());
        List<String> bad = List.of(
                "{\"attr\":\"%1$s\",\"cast\":\"number\",\"expr\":{\"attr\":\"%2$s\"}}",
                "{\"const\":2,\"text\":\"app\"}",
                "{\"op\":\"add\",\"l\":{\"attr\":\"%1$s\"},\"r\":{\"const\":1},\"future\":true}",
                // 3 键但无 op（审查 I-3）：缺键报 bad_expression，不是 NPE
                "{\"l\":{\"attr\":\"%1$s\"},\"r\":{\"const\":1},\"x\":2}",
                "{\"cast\":\"integer\",\"expr\":{\"attr\":\"%1$s\"}}",
                "{\"date_trunc\":\"hour\",\"expr\":{\"attr\":\"%1$s\"}}",
                "{\"case\":{\"attr\":\"%1$s\"},\"when\":[]}",
                "{\"case\":{\"attr\":\"%1$s\"},\"when\":[{\"is\":1,\"then\":\"app\"}],\"else\":0}",
                "{\"case\":{\"attr\":\"%1$s\"},\"when\":[{\"is\":1,\"then\":{\"attr\":\"%2$s\"}}]}",
                "{\"case\":{\"attr\":\"%1$s\"},\"when\":[{\"is\":null,\"then\":\"app\"}]}");
        for (String shape : bad) {
            String filled = shape.formatted(id(1), id(2));
            ExprError err = parseErr(filled);
            assertEquals("bad_expression", err.code(), filled);
        }
        // 33 个臂（0..=MAX_CASE_ARMS）：一条「臂数」专属错误信息
        ArrayNode arms = JsonNodeFactory.instance.arrayNode();
        for (int n = 0; n <= Expressions.MAX_CASE_ARMS; n++) {
            arms.add(JsonNodeFactory.instance.objectNode().put("is", n).put("then", "app"));
        }
        ObjectNode caseTooMany = JsonNodeFactory.instance.objectNode();
        caseTooMany.set("case", json("{\"attr\":\"%s\"}".formatted(id(1))));
        caseTooMany.set("when", arms);
        assertEquals("A case needs between one and 32 literal arms.",
                parseErr(caseTooMany.toString()).message());
    }

    // ---- rules.rs 引擎的真调用（任务 6 落地 evaluate_with_pool 后从镜像 seam 换真） ----

    /** rules.rs AttrFact 形状（引擎真身 {@link tech.seexun.jknow.reason.Rules.AttrFact}）。 */
    record AttrFact(UUID id, UUID subject, UUID predicate, JsonNode value) {
    }

    /** evaluate 的单条产出（RuleHit 断言面子集：区间、值、前提）。 */
    record Hit(Long from, Long to, Optional<Scalar> value, List<UUID> premises) {
    }

    /** evaluate 的产出（命中列表 + report.hits）。 */
    record Hits(List<Hit> hits, int reportHits) {
    }

    /** rules.rs {@code evaluate(&[rule], &facts, &spans, &[])}——真引擎调用（任务 6 去封）。 */
    private static Hits evaluate(UUID ruleId, UUID conclusionPredicate, Expr expr,
                                 UUID conditionPredicate, List<AttrFact> facts,
                                 Map<UUID, Span> spans) {
        var rule = new tech.seexun.jknow.reason.Rules.BusinessRule(ruleId,
                new tech.seexun.jknow.reason.Rules.Conclusion.Computed(conclusionPredicate, expr),
                null,
                List.of(new tech.seexun.jknow.reason.Rules.Condition(0,
                        tech.seexun.jknow.reason.Rules.Side.X, conditionPredicate,
                        tech.seexun.jknow.reason.Rules.Op.PRESENT,
                        new tech.seexun.jknow.reason.Rules.Operand.None())));
        List<tech.seexun.jknow.reason.Rules.AttrFact> engineFacts = facts.stream()
                .map(f -> new tech.seexun.jknow.reason.Rules.AttrFact(f.id(), f.subject(),
                        f.predicate(), f.value()))
                .toList();
        var eval = tech.seexun.jknow.reason.Rules.evaluate(List.of(rule), engineFacts, spans,
                List.of());
        List<Hit> hits = eval.hits().stream()
                .map(h -> new Hit(h.from(), h.to(), Optional.ofNullable(h.value()), h.premises()))
                .toList();
        return new Hits(hits, eval.report().hits());
    }

    /**
     * 第 5 例（任务 6 去封）：读数各留各的区间与证明——CASE 的每个文本结果取自己
     * 命中的那一段（evaluate_with_pool 真身）。断言一字不动。
     */
    @Test
    void textResultsKeepEachReadingsIntervalAndProof() {
        List<AttrFact> facts = List.of(
                new AttrFact(id(1), id(99), id(10), json("5")),
                new AttrFact(id(2), id(99), id(11), json("1")),
                new AttrFact(id(3), id(99), id(11), json("2")));
        Map<UUID, Span> spans = new HashMap<>();
        spans.put(id(1), new Span(10L, 30L));
        spans.put(id(2), new Span(0L, 20L));
        spans.put(id(3), new Span(20L, 40L));
        Expr expr = parse(("{\"case\":{\"attr\":\"%s\"},\"when\":[{\"is\":1,\"then\":\"app\"},"
                + "{\"is\":2,\"then\":\"web\"}]}").formatted(id(11)));
        Hits result = evaluate(id(50), id(12), expr, id(10), facts, spans);
        assertEquals(2, result.reportHits());
        List<Hit> hits = new ArrayList<>(result.hits());
        hits.sort(Comparator.comparing(Hit::from));
        assertEquals(Optional.of(10L), Optional.of(hits.get(0).from()));
        assertEquals(Optional.of(20L), Optional.of(hits.get(0).to()));
        assertEquals(Optional.of(new Scalar.Text("app")), hits.get(0).value());
        assertEquals(List.of(id(1), id(2)), hits.get(0).premises());
        assertEquals(Optional.of(20L), Optional.of(hits.get(1).from()));
        assertEquals(Optional.of(30L), Optional.of(hits.get(1).to()));
        assertEquals(Optional.of(new Scalar.Text("web")), hits.get(1).value());
        assertEquals(List.of(id(1), id(3)), hits.get(1).premises());
    }
}
