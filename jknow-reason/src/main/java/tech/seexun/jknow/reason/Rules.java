package tech.seexun.jknow.reason;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import tech.seexun.jknow.reason.Derive.Span;
import tech.seexun.jknow.reason.Expressions.Expr;
import tech.seexun.jknow.reason.Expressions.Scalar;

/**
 * 业务规则引擎（rules.rs 全量对等，0021/0029/0032/0047）：读一个实体自己的属性事实，
 * 得出一个结论。
 *
 * <p><b>与 {@link Derive} 分开的一趟</b>：那一趟走实体—实体的边，在公理下做闭包；
 * 这一趟做的是「拿字面值跟阈值比」。两件事的输入、判据、终止条件都不一样。共享的是
 * 区间语义（{@link Derive#validity}）和「结论是派生的」这条身份：命中产出的东西照样进
 * {@code derived_facts}、照样挂前提、照样在前提消失时失效。这一层不碰数据库。
 */
public final class Rules {

    private Rules() {
    }

    /** 四则运算（rules.rs:122-127）。 */
    public enum Arith {
        ADD, SUB, MUL, DIV;

        /** rules.rs:130-137 as_str。 */
        public String asString() {
            return switch (this) {
                case ADD -> "add";
                case SUB -> "sub";
                case MUL -> "mul";
                case DIV -> "div";
            };
        }

        /** rules.rs:138-146 parse。 */
        public static Arith parse(String s) {
            return switch (s == null ? "" : s) {
                case "add" -> ADD;
                case "sub" -> SUB;
                case "mul" -> MUL;
                case "div" -> DIV;
                default -> null;
            };
        }
    }

    /** 一个实体、一条属性、一个时段上的字面值（rules.rs:19-26）。求值器的全部输入。 */
    public record AttrFact(UUID id, UUID subject, UUID predicate, JsonNode value) {
    }

    /** 一条参与规则连接的实体—实体边（rules.rs:29-35）。 */
    public record RuleEdge(UUID id, UUID predicate, UUID subject, UUID object) {
    }

    /** 连接边的两侧（rules.rs:38-59）：X 是规则的主语；Y 是这条边连到的另一个实体。 */
    public enum Side {
        X("x"), Y("y");

        private final String asStr;

        Side(String asStr) {
            this.asStr = asStr;
        }

        public String asString() {
            return asStr;
        }

        public static Side parse(String s) {
            return switch (s == null ? "" : s) {
                case "x", "X" -> X;
                case "y", "Y" -> Y;
                default -> null;
            };
        }
    }

    /** 条件的比较方式（rules.rs:62-103）。与 {@code attribute_rule_conditions.op} 的 CHECK 一一对应。 */
    public enum Op {
        GT("gt"), GTE("gte"), LT("lt"), LTE("lte"), BETWEEN("between"), IN("in"),
        /** 反过来的 In——判的仍然是一条<b>存在</b>的事实（0029 末节）。 */
        NOT_IN("not_in"), PRESENT("present");

        private final String asStr;

        Op(String asStr) {
            this.asStr = asStr;
        }

        public String asString() {
            return asStr;
        }

        public static Op parse(String s) {
            return switch (s == null ? "" : s) {
                case "gt" -> GT;
                case "gte" -> GTE;
                case "lt" -> LT;
                case "lte" -> LTE;
                case "between" -> BETWEEN;
                case "in" -> IN;
                case "not_in" -> NOT_IN;
                case "present" -> PRESENT;
                default -> null;
            };
        }
    }

    /** 操作数（rules.rs:106-117）。形状由 op 决定，读的时候一次解析好。 */
    public sealed interface Operand {

        /** 一个数。 */
        record Num(double n) implements Operand {
        }

        /** 闭区间 [lo, hi]。 */
        record Range(double lo, double hi) implements Operand {
        }

        /**
         * 类别集合。<b>逐字匹配</b>——同一类别换个语言就不算命中（0021 记下的未决问题，
         * 不在这里偷偷做模糊）。
         */
        record Set_(List<String> set) implements Operand {
        }

        /** 算出来的数（0032）：拿这个实体别的读数算一个门槛，而不是写死一个。 */
        record Calc(Expr expr) implements Operand {
        }

        /** Present 用的空操作数。 */
        record None() implements Operand {
        }
    }

    /** 便捷构造：Operand::Set。 */
    static Operand set(List<String> values) {
        return new Operand.Set_(values);
    }

    /** 便捷构造：Operand::None。 */
    static Operand none() {
        return new Operand.None();
    }

    /** 一个条件（rules.rs:167-176）：同组合取、组间或；side 说这条读连接的哪一侧。 */
    public record Condition(int group, Side side, UUID predicate, Op op, Operand operand) {
    }

    /** 结论（rules.rs:179-193）四种形状，对应 {@code attribute_rules.conclusion} 的四支。 */
    public sealed interface Conclusion {

        /** 派生归类：结论是一个类，落成 {@code is_a} 上的字面值。 */
        record Typing(String class_) implements Conclusion {
        }

        /** 派生属性：算出来的数（0032）。算式读的那几条读数一并进前提。 */
        record Computed(UUID predicate, Expr expr) implements Conclusion {
        }

        /** 派生属性：某个属性谓词上的一个字面值。 */
        record Attribute(UUID predicate, JsonNode value) implements Conclusion {
        }

        /** 派生关系：从 X 经规则声明的连接指向 Y（0047）。 */
        record Relation(UUID predicate) implements Conclusion {
        }
    }

    /**
     * 一条业务规则（rules.rs:195-205）。空条件集永不命中——一条没有判据的规则应当
     * 什么都不推，而不是把整个类都归进去。
     */
    public record BusinessRule(UUID id, Conclusion conclusion, UUID joinPredicate,
                               List<Condition> conditions) {
    }

    /**
     * 一次命中（rules.rs:208-220）。前提是<b>真正让它成立的那几条事实</b>，
     * 不是这个实体的全部属性。value 是算出来的标量（Computed 才有）。
     */
    public record RuleHit(UUID rule, UUID subject, UUID object, List<UUID> premises, Long from,
                          Long to, Scalar value) {
    }

    /**
     * 一次求值的报告（rules.rs:222-231）。capped 是因组合数超上限而没有展开完的
     * (规则, 实体) 对数——<b>必须报出来</b>：少推几条与「这个实体不满足」长得一模一样。
     */
    public record RuleReport(int rules, int hits, int capped) {
    }

    /**
     * 一个 (规则, 实体) 对最多展开多少种前提组合（rules.rs:238）。组合数是各条件命中集
     * 大小的乘积；真实数据里每项通常只有一两条，这个上限只在「同一属性被反复覆盖几十次」
     * 时才够得着。
     */
    public static final int MAX_COMBOS = 64;

    // ===================== Expr 的求值适配（rules.rs:149-165） =====================

    /**
     * Comparisons still need a number, including the legacy numeric-string readings
     * （rules.rs:150-153 {@code Expr::eval}）。
     */
    static Double eval(Expr expr, Map<UUID, AttrFact> bound) {
        Scalar s = compute(expr, bound);
        if (s == null) {
            return null;
        }
        OptionalDouble n = s.number();
        return n.isPresent() ? n.getAsDouble() : null;
    }

    /**
     * rules.rs:155-164 {@code Expr::compute}：裸属性表达式按数值读数解（旧语义保留）；
     * CASE 与日期截断显式产出文本，不改变它。
     */
    static Scalar compute(Expr expr, Map<UUID, AttrFact> bound) {
        Scalar value = Expressions.evaluate(expr, id -> {
            AttrFact f = bound.get(id);
            return f == null ? null : Scalar.read(f.value());
        });
        if (value == null) {
            return null;
        }
        if (expr instanceof Expr.Attr) {
            OptionalDouble n = value.number();
            return n.isPresent() ? new Scalar.Number(n.getAsDouble()) : null;
        }
        return value;
    }

    // ===================== 求值（rules.rs:254-432） =====================

    /** evaluate（rules.rs:254-261）：{@code evaluate_with_pool} 的 facts == pool_facts 形态。 */
    public static RuleEval evaluate(List<BusinessRule> rules, List<AttrFact> facts,
                                    Map<UUID, Span> spans, List<RuleEdge> edges) {
        return evaluateWithPool(rules, facts, facts, spans, edges);
    }

    /** 求值产出：命中列表 + 报告。 */
    public record RuleEval(List<RuleHit> hits, RuleReport report) {
    }

    /**
     * Evaluate rules with the facts available to a joined {@code Y} supplied separately
     * （rules.rs:267-432）。{@code facts} 只装结论主类之内的实体；一跳连接够得着另一类
     * 实体，它的条件从 {@code poolFacts} 读。
     *
     * <p>语义：每个条件各自挑出满足它的事实集合，再在「每个条件取一条」的组合上求区间
     * 交集，交集非空即一次命中——同一规则可在同一实体上产出<b>多段区间</b>（0021 决策 4）。
     * 组内与、组间或（0029）；封顶按组算——一组展不开不该拖累另一组；两组推出同一区间时
     * 按区间去重，留先到的那组当证明。
     */
    public static RuleEval evaluateWithPool(List<BusinessRule> rules, List<AttrFact> facts,
                                            List<AttrFact> poolFacts, Map<UUID, Span> spans,
                                            List<RuleEdge> edges) {
        int capped = 0;
        List<RuleHit> hits = new ArrayList<>();
        for (BusinessRule rule : rules) {
            if (rule.conditions().isEmpty()) {
                continue;
            }
            if (rule.joinPredicate() != null) {
                capped += joinedEvaluate(rule, rule.joinPredicate(), facts, poolFacts, spans,
                        edges, hits);
                continue;
            }
            // 按实体分组：这条规则谈的还是一个实体自己的属性
            Map<UUID, List<AttrFact>> bySubject = new LinkedHashMap<>();
            for (AttrFact f : facts) {
                bySubject.computeIfAbsent(f.subject(), k -> new ArrayList<>()).add(f);
            }
            List<List<Condition>> groups = groupConditions(rule.conditions());
            for (Map.Entry<UUID, List<AttrFact>> entry : bySubject.entrySet()) {
                UUID subject = entry.getKey();
                List<AttrFact> own = entry.getValue();
                // 同一实体上多个组可能推出同一段区间，去重跨组（键带上算出来的值，0032）
                List<Object[]> seen = new ArrayList<>();
                boolean cappedHere = false;
                for (List<Condition> group : groups) {
                    // 槽位：一个条件一个槽（同一属性的两个条件各挑各的读数）；
                    // 算式读到而条件没覆盖的谓词补进后面的槽——算式引用一个谓词时绑到
                    // 第一个同谓词的槽（thc > thc*0.5 两边说的是同一条读数）
                    List<UUID> slots = new ArrayList<>();
                    for (Condition c : group) {
                        slots.add(c.predicate());
                    }
                    List<UUID> extra = new ArrayList<>();
                    for (Condition c : group) {
                        if (c.operand() instanceof Operand.Calc e) {
                            Expressions.predicates(e.expr(), extra);
                        }
                    }
                    if (rule.conclusion() instanceof Conclusion.Computed computed) {
                        Expressions.predicates(computed.expr(), extra);
                    }
                    for (UUID p : extra) {
                        if (!slots.contains(p)) {
                            slots.add(p);
                        }
                    }
                    List<List<UUID>> perSlot = new ArrayList<>();
                    boolean satisfiable = true;
                    for (UUID p : slots) {
                        List<UUID> matched = new ArrayList<>();
                        for (AttrFact f : own) {
                            if (f.predicate().equals(p)) {
                                matched.add(f.id());
                            }
                        }
                        if (matched.isEmpty()) {
                            satisfiable = false;
                            break;
                        }
                        perSlot.add(matched);
                    }
                    if (!satisfiable) {
                        continue;
                    }
                    // 封顶按组算：一组展不开，不该把另一组也拦下
                    long combos = 1;
                    for (List<UUID> v : perSlot) {
                        combos *= v.size();
                    }
                    if (combos > MAX_COMBOS) {
                        cappedHere = true;
                        continue;
                    }
                    Map<UUID, AttrFact> byId = new HashMap<>();
                    for (AttrFact f : own) {
                        byId.put(f.id(), f);
                    }
                    // 笛卡尔积。条件要等组合定下来才判得了（0032）：算出来的门槛读的是
                    // 这一轮选中的那几条读数。同一区间可能由多个组合得出，按区间去重
                    for (List<UUID> combo : cartesian(perSlot)) {
                        Map<UUID, AttrFact> bound = new HashMap<>();
                        for (int i = 0; i < combo.size(); i++) {
                            AttrFact f = byId.get(combo.get(i));
                            if (f != null) {
                                bound.putIfAbsent(slots.get(i), f);
                            }
                        }
                        boolean holds = true;
                        for (int i = 0; i < group.size(); i++) {
                            AttrFact f = i < combo.size() ? byId.get(combo.get(i)) : null;
                            if (f == null || !satisfies(group.get(i), f.value(), bound)) {
                                holds = false;
                                break;
                            }
                        }
                        if (!holds) {
                            continue;
                        }
                        // Missing readings and failed conversions do not manufacture a value.
                        Scalar value = null;
                        if (rule.conclusion() instanceof Conclusion.Computed computed) {
                            value = compute(computed.expr(), bound);
                            if (value == null) {
                                continue;
                            }
                        }
                        Span span = Derive.validity(combo, spans);
                        if (span == null) {
                            continue;
                        }
                        // 去重按 (区间, 值)：算出来的结论同一区间可以有不同的值
                        if (seenKey(seen, span.from(), span.to(), value)) {
                            continue;
                        }
                        seen.add(new Object[]{span.from(), span.to(), value});
                        hits.add(new RuleHit(rule.id(), subject, null, combo, span.from(),
                                span.to(), value));
                    }
                }
                if (cappedHere) {
                    capped++;
                }
            }
        }
        return new RuleEval(hits, new RuleReport(rules.size(), hits.size(), capped));
    }

    /** 去重键匹配：数值按位比较（Rust 的 to_bits），文本按相等。 */
    private static boolean seenKey(List<Object[]> seen, Long from, Long to, Scalar value) {
        for (Object[] key : seen) {
            if (!java.util.Objects.equals(key[0], from) || !java.util.Objects.equals(key[1], to)) {
                continue;
            }
            Scalar old = (Scalar) key[2];
            if (old == null && value == null) {
                return true;
            }
            if (old instanceof Scalar.Number a && value instanceof Scalar.Number b) {
                if (Double.doubleToRawLongBits(a.value()) == Double.doubleToRawLongBits(b.value())) {
                    return true;
                }
            } else if (java.util.Objects.equals(old, value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 求值一条跨两个实体的规则（rules.rs:439-596）。
     *
     * <p><b>一条连接边是一对 (X, Y)，不是一次笛卡尔连接。</b>一跳是上限，每条边自己
     * 成为前提；封顶按 (rule, X, Y) 报，与单实体规则的 (rule, X) 同一种含义。
     * 返回这一条规则上封顶的 (X, Y) 对数。
     */
    private static int joinedEvaluate(BusinessRule rule, UUID joinPredicate,
                                      List<AttrFact> facts, List<AttrFact> poolFacts,
                                      Map<UUID, Span> spans, List<RuleEdge> edges,
                                      List<RuleHit> hits) {
        // X is already scoped to the rule's subject type. The join reaches a
        // differently typed Y.
        Map<UUID, List<AttrFact>> xBySubject = new LinkedHashMap<>();
        for (AttrFact f : facts) {
            xBySubject.computeIfAbsent(f.subject(), k -> new ArrayList<>()).add(f);
        }
        Map<UUID, List<AttrFact>> poolBySubject = new LinkedHashMap<>();
        for (AttrFact f : poolFacts) {
            poolBySubject.computeIfAbsent(f.subject(), k -> new ArrayList<>()).add(f);
        }
        // 连接边按 X 分桶：对每个 X 再去全部边里找它是 X 数乘边数，分桶之后随对数线性
        Map<UUID, List<RuleEdge>> edgesByX = new LinkedHashMap<>();
        for (RuleEdge e : edges) {
            if (e.predicate().equals(joinPredicate)) {
                edgesByX.computeIfAbsent(e.subject(), k -> new ArrayList<>()).add(e);
            }
        }
        List<UUID> xSubjects = new ArrayList<>(edgesByX.keySet());
        xSubjects.sort(Comparator.naturalOrder());
        List<List<Condition>> groups = groupConditions(rule.conditions());
        int capped = 0;
        for (UUID x : xSubjects) {
            List<AttrFact> xFacts = xBySubject.getOrDefault(x, List.of());
            for (RuleEdge edge : edgesByX.get(x)) {
                UUID y = edge.object();
                List<AttrFact> yFacts = poolBySubject.getOrDefault(y, List.of());
                // 同一对上的多个组可能推出同一结论，留先到的组作证明
                List<Object[]> seen = new ArrayList<>();
                boolean cappedHere = false;
                for (List<Condition> group : groups) {
                    List<Map.Entry<Side, UUID>> slots = new ArrayList<>();
                    for (Condition c : group) {
                        slots.add(Map.entry(c.side(), c.predicate()));
                    }
                    List<Map.Entry<Side, UUID>> extra = new ArrayList<>();
                    for (Condition c : group) {
                        if (c.operand() instanceof Operand.Calc e) {
                            exprPredicates(e.expr(), c.side(), extra);
                        }
                    }
                    if (rule.conclusion() instanceof Conclusion.Computed computed) {
                        exprPredicates(computed.expr(), Side.X, extra);
                    }
                    for (Map.Entry<Side, UUID> slot : extra) {
                        boolean present = false;
                        for (Map.Entry<Side, UUID> s : slots) {
                            if (s.equals(slot)) {
                                present = true;
                                break;
                            }
                        }
                        if (!present) {
                            slots.add(slot);
                        }
                    }
                    List<List<UUID>> perSlot = new ArrayList<>();
                    boolean satisfiable = true;
                    for (Map.Entry<Side, UUID> slot : slots) {
                        List<AttrFact> sideFacts = slot.getKey() == Side.X ? xFacts : yFacts;
                        List<UUID> matched = new ArrayList<>();
                        for (AttrFact f : sideFacts) {
                            if (f.predicate().equals(slot.getValue())) {
                                matched.add(f.id());
                            }
                        }
                        if (matched.isEmpty()) {
                            satisfiable = false;
                            break;
                        }
                        perSlot.add(matched);
                    }
                    if (!satisfiable) {
                        continue;
                    }
                    long combos = 1;
                    for (List<UUID> v : perSlot) {
                        combos *= v.size();
                    }
                    if (combos > MAX_COMBOS) {
                        cappedHere = true;
                        continue;
                    }
                    Map<UUID, AttrFact> xById = new HashMap<>();
                    for (AttrFact f : xFacts) {
                        xById.put(f.id(), f);
                    }
                    Map<UUID, AttrFact> yById = new HashMap<>();
                    for (AttrFact f : yFacts) {
                        yById.put(f.id(), f);
                    }
                    for (List<UUID> combo : cartesian(perSlot)) {
                        Map<UUID, AttrFact> xBound = new HashMap<>();
                        Map<UUID, AttrFact> yBound = new HashMap<>();
                        for (int i = 0; i < slots.size(); i++) {
                            Map.Entry<Side, UUID> slot = slots.get(i);
                            Map<UUID, AttrFact> bound = slot.getKey() == Side.X ? xBound : yBound;
                            Map<UUID, AttrFact> byId = slot.getKey() == Side.X ? xById : yById;
                            AttrFact f = byId.get(combo.get(i));
                            if (f != null) {
                                bound.putIfAbsent(slot.getValue(), f);
                            }
                        }
                        boolean holds = true;
                        for (int i = 0; i < group.size(); i++) {
                            Condition c = group.get(i);
                            Map<UUID, AttrFact> bound = c.side() == Side.X ? xBound : yBound;
                            Map<UUID, AttrFact> byId = c.side() == Side.X ? xById : yById;
                            AttrFact f = i < combo.size() ? byId.get(combo.get(i)) : null;
                            if (f == null || !satisfies(c, f.value(), bound)) {
                                holds = false;
                                break;
                            }
                        }
                        if (!holds) {
                            continue;
                        }
                        // Relation is the only joined conclusion（store 侧校验挡住别的组合）
                        if (!(rule.conclusion() instanceof Conclusion.Relation)) {
                            continue;
                        }
                        List<UUID> premises = new ArrayList<>(combo);
                        premises.add(edge.id());
                        Span span = Derive.validity(premises, spans);
                        if (span == null) {
                            continue;
                        }
                        if (seenKey(seen, span.from(), span.to(), null)) {
                            continue;
                        }
                        seen.add(new Object[]{span.from(), span.to(), null});
                        hits.add(new RuleHit(rule.id(), x, y, premises, span.from(), span.to(),
                                null));
                    }
                }
                if (cappedHere) {
                    capped++;
                }
            }
        }
        return capped;
    }

    private static void exprPredicates(Expr expr, Side side, List<Map.Entry<Side, UUID>> out) {
        List<UUID> predicates = new ArrayList<>();
        Expressions.predicates(expr, predicates);
        for (UUID predicate : predicates) {
            Map.Entry<Side, UUID> slot = Map.entry(side, predicate);
            boolean present = false;
            for (Map.Entry<Side, UUID> s : out) {
                if (s.equals(slot)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                out.add(slot);
            }
        }
    }

    /**
     * 按 {@code group} 切成几组（rules.rs:609-618），<b>组序按 group_seq 升序</b>——
     * 两组推出同一区间时，留下的证明得是稳定的那一条。
     */
    static List<List<Condition>> groupConditions(List<Condition> conditions) {
        List<Integer> keys = new ArrayList<>();
        for (Condition c : conditions) {
            keys.add(c.group());
        }
        keys.sort(Comparator.naturalOrder());
        List<Integer> unique = new ArrayList<>();
        for (int k : keys) {
            if (unique.isEmpty() || unique.get(unique.size() - 1) != k) {
                unique.add(k);
            }
        }
        List<List<Condition>> out = new ArrayList<>();
        for (int g : unique) {
            List<Condition> group = new ArrayList<>();
            for (Condition c : conditions) {
                if (c.group() == g) {
                    group.add(c);
                }
            }
            out.add(group);
        }
        return out;
    }

    /** 每个条件取一条，穷举组合（rules.rs:620-635）。调用方已把上限挡在外面。 */
    static List<List<UUID>> cartesian(List<List<UUID>> sets) {
        List<List<UUID>> out = new ArrayList<>();
        out.add(new ArrayList<>());
        for (List<UUID> set : sets) {
            List<List<UUID>> next = new ArrayList<>(out.size() * set.size());
            for (List<UUID> prefix : out) {
                for (UUID id : set) {
                    List<UUID> row = new ArrayList<>(prefix);
                    row.add(id);
                    next.add(row);
                }
            }
            out = next;
        }
        return out;
    }

    /**
     * 一条属性值满不满足一个条件（rules.rs:641-670）。
     * <b>类型不对就是不满足，不是报错</b>——一个本该是数字的属性被抽成了「十二点三」，
     * 这条规则在这个实体上不成立，而不是让整轮物化失败。
     */
    static boolean satisfies(Condition c, JsonNode value, Map<UUID, AttrFact> bound) {
        // 算出来的门槛：先按这一轮选中的读数算个数，算不出来就是不满足（0032）
        if (c.operand() instanceof Operand.Calc e) {
            Double n = eval(e.expr(), bound);
            if (n == null) {
                return false;
            }
            Double v = num(value);
            if (v == null) {
                return false;
            }
            return switch (c.op()) {
                case GT -> v > n;
                case GTE -> v >= n;
                case LT -> v < n;
                case LTE -> v <= n;
                // 算式给的是一个数：区间、集合、有记录都不是它能填的位置
                default -> false;
            };
        }
        if (c.op() == Op.PRESENT) {
            return value != null && !value.isNull();
        }
        if (c.op() == Op.NOT_IN && c.operand() instanceof Operand.Set_ set) {
            // 值不在集合里就算满足；没有值不算——那是「没记」，不是「不是它」
            if (value == null || value.isNull()) {
                return false;
            }
            String v = text(value);
            return v != null && set.set().stream().noneMatch(v::equals);
        }
        if (c.op() == Op.GT && c.operand() instanceof Operand.Num n) {
            Double v = num(value);
            return v != null && v > n.n();
        }
        if (c.op() == Op.GTE && c.operand() instanceof Operand.Num n) {
            Double v = num(value);
            return v != null && v >= n.n();
        }
        if (c.op() == Op.LT && c.operand() instanceof Operand.Num n) {
            Double v = num(value);
            return v != null && v < n.n();
        }
        if (c.op() == Op.LTE && c.operand() instanceof Operand.Num n) {
            Double v = num(value);
            return v != null && v <= n.n();
        }
        if (c.op() == Op.BETWEEN && c.operand() instanceof Operand.Range r) {
            Double v = num(value);
            return v != null && v >= r.lo() && v <= r.hi();
        }
        if (c.op() == Op.IN && c.operand() instanceof Operand.Set_ set) {
            String s = text(value);
            return s != null && set.set().stream().anyMatch(s::equals);
        }
        // op 与操作数形状对不上：库里的 CHECK 挡了大部分，剩下的当不满足
        return false;
    }

    /**
     * 数字（rules.rs:674-681）。<b>字符串里的数字也认</b>——抽取把「12.3」存成字符串
     * 是常有的事，一个阈值规则因为引号而静默失效是最难被发现的那种失效。
     */
    public static Double num(JsonNode v) {
        if (v == null) {
            return null;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isTextual()) {
            try {
                String s = v.asText().trim();
                return s.isEmpty() ? null : Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? 1.0 : 0.0;
        }
        return null;
    }

    /** 类别（rules.rs:684-691）。数字与布尔也转成字面形态参与集合比较，理由同上。 */
    public static String text(JsonNode v) {
        if (v == null) {
            return null;
        }
        if (v.isTextual()) {
            return v.asText().trim();
        }
        if (v.isNumber()) {
            return v.asText();
        }
        if (v.isBoolean()) {
            return Boolean.toString(v.asBoolean());
        }
        return null;
    }
}
