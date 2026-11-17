package tech.seexun.jknow.reason;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.Function;

/**
 * 规则与列换算共用的表达式树（expressions.rs，0032/0036）。
 *
 * <p>CASE 是「代码到值」的映射，不是又一种谓词语言。它的臂是字面量，所以树读到的每个
 * 属性都是每个结果的前提。
 */
public final class Expressions {

    private Expressions() {
    }

    /** 从根数起的已绑节点数上限（expressions.rs:12）。 */
    public static final int MAX_EXPR_DEPTH = 4;

    /** CASE 的臂数上限（expressions.rs:13）。 */
    public static final int MAX_CASE_ARMS = 32;

    /** 一个读出来的值：数或文本（expressions.rs:16-49）。 */
    public sealed interface Scalar {

        /** 数（有限值——构造处与读出处都过滤非有限）。 */
        record Number(double value) implements Scalar {
        }

        /** 文本。 */
        record Text(String value) implements Scalar {
        }

        /** Scalar::read：JSON 里的数或字符串；其余（null/bool/对象/非有限）读不出来。 */
        static Scalar read(JsonNode value) {
            if (value != null && value.isNumber()) {
                double d = value.asDouble();
                if (Double.isFinite(d)) {
                    return new Number(d);
                }
                return null;
            }
            if (value != null && value.isTextual()) {
                return new Text(value.asText());
            }
            return null;
        }

        /** Scalar::number：数值字符串仍是合法读数——有换算之前的旧语义保留。非有限得空。 */
        default OptionalDouble number() {
            if (this instanceof Number n) {
                return Double.isFinite(n.value) ? OptionalDouble.of(n.value)
                        : OptionalDouble.empty();
            }
            Text t = (Text) this;
            try {
                double d = Double.parseDouble(t.value.trim());
                return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
            } catch (NumberFormatException e) {
                return OptionalDouble.empty();
            }
        }

        /** to_json。 */
        default JsonNode toJson() {
            return this instanceof Number n
                    ? JsonNodeFactory.instance.numberNode(n.value)
                    : JsonNodeFactory.instance.textNode(((Text) this).value);
        }

        /** same_type：同一档（数/文本）——CASE 的全部结果必须同档。 */
        default boolean sameType(Scalar other) {
            return (this instanceof Number) == (other instanceof Number);
        }
    }

    /** 日历的哪一段（expressions.rs:52-96）。 */
    public enum DatePart {
        YEAR, MONTH, DAY;

        /** DatePart::parse。 */
        public static DatePart parse(String s) {
            return switch (s == null ? "" : s) {
                case "year" -> YEAR;
                case "month" -> MONTH;
                case "day" -> DAY;
                default -> null;
            };
        }

        /** as_str。 */
        public String asString() {
            return switch (this) {
                case YEAR -> "year";
                case MONTH -> "month";
                case DAY -> "day";
            };
        }

        /**
         * truncate：把一个日期读数截到这一段。长度 10 的按纯日期解；其余要求带
         * 时区偏移（RFC 3339）——<b>宿主时区不许改一条事实</b>，再折到 UTC 取日历日。
         * 解不动得空：宁可没有读数，不猜一个。
         */
        Scalar truncate(Scalar value) {
            if (!(value instanceof Scalar.Text t)) {
                return null;
            }
            String text = t.value();
            LocalDate date;
            try {
                if (text.length() == 10) {
                    date = LocalDate.parse(text);
                } else {
                    // 带偏移是硬要求：宿主时区不许改一条事实
                    date = OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC)
                            .toLocalDate();
                }
            } catch (RuntimeException e) {
                return null;
            }
            LocalDate truncated = switch (this) {
                case YEAR -> LocalDate.of(date.getYear(), 1, 1);
                case MONTH -> LocalDate.of(date.getYear(), date.getMonthValue(), 1);
                case DAY -> date;
            };
            return new Scalar.Text(truncated.toString());
        }
    }

    /** 表达式树（expressions.rs:98-118）。 */
    public sealed interface Expr {

        /** 读一个属性（前提）。 */
        record Attr(UUID id) implements Expr {
        }

        /** 数值常量。 */
        record Const(double n) implements Expr {
        }

        /** 文本常量（与数值常量分开：旧数值字符串保义，文本标签另有节点）。 */
        record TextE(String s) implements Expr {
        }

        /** 四则。 */
        record ArithE(Rules.Arith op, Expr l, Expr r) implements Expr {
        }

        /** 读数换算：文本数值转数（cast: number）。 */
        record NumberCast(Expr expr) implements Expr {
        }

        /** CASE 的一个臂：is 匹配则取 then。 */
        record Arm(Scalar is, Scalar then) {
        }

        /** 代码到值的映射；else 可缺省。 */
        record CaseE(Expr expr, List<Arm> arms, Scalar otherwise) implements Expr {
        }

        /** 日历截断。 */
        record DateTruncE(DatePart part, Expr expr) implements Expr {
        }
    }

    /** 解不动的理由（expressions.rs:120-140）。 */
    public record ExprError(String code, String message) {

        public static ExprError tooDeep() {
            return new ExprError("expression_too_deep",
                    "That expression nests too deeply; a rule computes with a few operations,"
                            + " not a program.");
        }

        public static ExprError invalid() {
            return new ExprError("bad_expression",
                    "That expression contains an invalid or unsupported node.");
        }

        public static ExprError invalid(String message) {
            return new ExprError("bad_expression", message);
        }
    }

    /** 一个解码器同时服务写校验与物化（Expr::from_json）。 */
    public static Expr fromJson(JsonNode raw) throws ExprErrorException {
        return decode(raw, 0);
    }

    /** {@link #fromJson} 的受检异常形态：调用方要么处理要么上抛，不经手 null。 */
    public static final class ExprErrorException extends Exception {
        public final ExprError error;

        ExprErrorException(ExprError error) {
            super(error.code());
            this.error = error;
        }

        /** 错误明细（code + message）——store 侧的写校验把它转成 AppError::invalid。 */
        public ExprError error() {
            return error;
        }
    }

    private static Expr decode(JsonNode raw, int depth) throws ExprErrorException {
        if (depth > MAX_EXPR_DEPTH) {
            throw new ExprErrorException(ExprError.tooDeep());
        }
        ExprError bad = ExprError.invalid();
        if (raw == null || !raw.isObject()) {
            throw new ExprErrorException(bad);
        }
        ObjectNode obj = (ObjectNode) raw;
        // 混合形状不许从这些检查的先后次序里捡到含义；未知字段同样当未知报，不当看不见
        if (obj.has("attr")) {
            if (obj.size() != 1) {
                throw new ExprErrorException(bad);
            }
            UUID id;
            // 值必须是字符串且解得出 UUID（Rust as_str 只认字符串，数字节点直接 bad）
            if (!obj.get("attr").isTextual()) {
                throw new ExprErrorException(bad);
            }
            try {
                id = UUID.fromString(obj.get("attr").asText());
            } catch (IllegalArgumentException e) {
                throw new ExprErrorException(bad);
            }
            return new Expr.Attr(id);
        }
        if (obj.has("const")) {
            if (obj.size() != 1) {
                throw new ExprErrorException(bad);
            }
            Scalar s = Scalar.read(obj.get("const"));
            OptionalDouble n = s == null ? OptionalDouble.empty() : s.number();
            if (n.isEmpty()) {
                throw new ExprErrorException(bad);
            }
            return new Expr.Const(n.getAsDouble());
        }
        if (obj.size() == 1 && obj.has("text")) {
            JsonNode text = obj.get("text");
            if (!text.isTextual()) {
                throw new ExprErrorException(bad);
            }
            return new Expr.TextE(text.asText());
        }
        if (obj.size() == 2 && obj.has("cast") && obj.get("cast").isTextual()
                && "number".equals(obj.get("cast").asText())) {
            JsonNode inner = obj.get("expr");
            if (inner == null) {
                throw new ExprErrorException(bad);
            }
            return new Expr.NumberCast(decode(inner, depth + 1));
        }
        if (obj.size() == 2 && obj.has("date_trunc")) {
            DatePart part = obj.get("date_trunc").isTextual()
                    ? DatePart.parse(obj.get("date_trunc").asText()) : null;
            if (part == null) {
                throw new ExprErrorException(ExprError.invalid());
            }
            JsonNode inner = obj.get("expr");
            if (inner == null) {
                throw new ExprErrorException(bad);
            }
            return new Expr.DateTruncE(part, decode(inner, depth + 1));
        }
        if (obj.has("case")) {
            if (obj.size() != 2 + (obj.has("else") ? 1 : 0)) {
                throw new ExprErrorException(bad);
            }
            JsonNode rawArms = obj.get("when");
            if (rawArms == null || !rawArms.isArray()) {
                throw new ExprErrorException(ExprError.invalid());
            }
            if (rawArms.isEmpty() || rawArms.size() > MAX_CASE_ARMS) {
                throw new ExprErrorException(ExprError.invalid(
                        "A case needs between one and 32 literal arms."));
            }
            List<Expr.Arm> arms = new ArrayList<>(rawArms.size());
            for (JsonNode arm : rawArms) {
                if (!arm.isObject() || arm.size() != 2 || !arm.has("is") || !arm.has("then")) {
                    throw new ExprErrorException(ExprError.invalid());
                }
                Scalar key = Scalar.read(arm.get("is"));
                Scalar value = Scalar.read(arm.get("then"));
                if (key == null || value == null) {
                    throw new ExprErrorException(ExprError.invalid());
                }
                arms.add(new Expr.Arm(key, value));
            }
            Scalar otherwise = null;
            if (obj.has("else")) {
                otherwise = Scalar.read(obj.get("else"));
                if (otherwise == null) {
                    throw new ExprErrorException(bad);
                }
            }
            Scalar first = arms.getFirst().then();
            boolean same = arms.stream().allMatch(a -> first.sameType(a.then()))
                    && (otherwise == null || first.sameType(otherwise));
            if (!same) {
                throw new ExprErrorException(ExprError.invalid(
                        "A case's results must all be numbers or all be text."));
            }
            return new Expr.CaseE(decode(obj.get("case"), depth + 1), List.copyOf(arms),
                    otherwise);
        }
        if (obj.size() != 3) {
            throw new ExprErrorException(bad);
        }
        // op 键缺省时按 bad_expression 报（expressions.rs:240-244 的 .ok_or(bad)）——
        // 直接 .isTextual() 会在 3 键无 op 的形状上 NPE（审查 I-3）
        JsonNode opNode = obj.get("op");
        Rules.Arith op = opNode != null && opNode.isTextual()
                ? Rules.Arith.parse(opNode.asText()) : null;
        if (op == null) {
            throw new ExprErrorException(ExprError.invalid());
        }
        if (!obj.has("l") || !obj.has("r")) {
            throw new ExprErrorException(bad);
        }
        return new Expr.ArithE(op, decode(obj.get("l"), depth + 1), decode(obj.get("r"), depth + 1));
    }

    /** to_json：与 from_json 往返（测试钉住）。 */
    static JsonNode toJson(Expr expr) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        switch (expr) {
            case Expr.Attr a -> out.put("attr", a.id().toString());
            case Expr.Const c -> out.put("const", c.n());
            case Expr.TextE t -> out.put("text", t.s());
            case Expr.ArithE a -> {
                out.put("op", a.op().asString());
                out.set("l", toJson(a.l()));
                out.set("r", toJson(a.r()));
            }
            case Expr.NumberCast n -> {
                out.put("cast", "number");
                out.set("expr", toJson(n.expr()));
            }
            case Expr.DateTruncE d -> {
                out.put("date_trunc", d.part().asString());
                out.set("expr", toJson(d.expr()));
            }
            case Expr.CaseE c -> {
                out.set("case", toJson(c.expr()));
                ArrayNode arms = out.putArray("when");
                for (Expr.Arm arm : c.arms()) {
                    ObjectNode o = arms.addObject();
                    o.set("is", arm.is().toJson());
                    o.set("then", arm.then().toJson());
                }
                if (c.otherwise() != null) {
                    out.set("else", c.otherwise().toJson());
                }
            }
        }
        return out;
    }

    /** 属性 id，按遇到的顺序去重（Expr::predicates 的 "without duplicates"——审查 M-4 勘误）。 */
    public static void predicates(Expr expr, List<UUID> out) {
        switch (expr) {
            case Expr.Attr a -> {
                if (!out.contains(a.id())) {
                    out.add(a.id());
                }
            }
            case Expr.Const c -> {
            }
            case Expr.TextE t -> {
            }
            case Expr.ArithE a -> {
                predicates(a.l(), out);
                predicates(a.r(), out);
            }
            case Expr.NumberCast n -> predicates(n.expr(), out);
            case Expr.CaseE c -> predicates(c.expr(), out);
            case Expr.DateTruncE d -> predicates(d.expr(), out);
        }
    }

    /** 从根数起的深度（Expr::depth）。 */
    static int depth(Expr expr) {
        return switch (expr) {
            case Expr.Attr a -> 1;
            case Expr.Const c -> 1;
            case Expr.TextE t -> 1;
            case Expr.ArithE a -> 1 + Math.max(depth(a.l()), depth(a.r()));
            case Expr.NumberCast n -> 1 + depth(n.expr());
            case Expr.CaseE c -> 1 + depth(c.expr());
            case Expr.DateTruncE d -> 1 + depth(d.expr());
        };
    }

    /**
     * 求值（Expr::evaluate）。换算失败就是没有读数——与缺一次读数、除零一样：
     * <b>调用方不许把它变成零或一条成功的事实</b>。
     */
    static Scalar evaluate(Expr expr, Function<UUID, Scalar> read) {
        return switch (expr) {
            case Expr.Attr a -> read.apply(a.id());
            case Expr.Const c -> Double.isFinite(c.n()) ? new Scalar.Number(c.n()) : null;
            case Expr.TextE t -> new Scalar.Text(t.s());
            case Expr.NumberCast n -> {
                Scalar inner = evaluate(n.expr(), read);
                if (inner == null) {
                    yield null;
                }
                OptionalDouble d = inner.number();
                yield d.isPresent() ? new Scalar.Number(d.getAsDouble()) : null;
            }
            case Expr.ArithE a -> {
                Scalar ls = evaluate(a.l(), read);
                Scalar rs = evaluate(a.r(), read);
                if (ls == null || rs == null) {
                    yield null;
                }
                OptionalDouble x = ls.number();
                OptionalDouble y = rs.number();
                if (x.isEmpty() || y.isEmpty()) {
                    yield null;
                }
                double value = switch (a.op()) {
                    case ADD -> x.getAsDouble() + y.getAsDouble();
                    case SUB -> x.getAsDouble() - y.getAsDouble();
                    case MUL -> x.getAsDouble() * y.getAsDouble();
                    case DIV -> y.getAsDouble() != 0.0 ? x.getAsDouble() / y.getAsDouble()
                            : Double.NaN;
                };
                yield Double.isFinite(value) ? new Scalar.Number(value) : null;
            }
            case Expr.CaseE c -> {
                Scalar key = evaluate(c.expr(), read);
                if (key == null) {
                    yield null;
                }
                Scalar hit = null;
                for (Expr.Arm arm : c.arms()) {
                    if (arm.is().equals(key)) {
                        hit = arm.then();
                        break;
                    }
                }
                yield hit != null ? hit : c.otherwise();
            }
            case Expr.DateTruncE d -> {
                Scalar inner = evaluate(d.expr(), read);
                yield inner == null ? null : d.part().truncate(inner);
            }
        };
    }
}
