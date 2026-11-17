package tech.seexun.jknow.reason;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * R1：物化推导（derive.rs 全量对等）。<b>这一层会往图里加东西</b>，所以它的每一条约束
 * 都是必要的（ADR 0002 三条硬性规矩）：
 * <ol>
 *   <li>规则只从本体公理编译——「没声明就不推」的同一条</li>
 *   <li>断言优先于派生，硬性：已经断言过的三元组不再派生一遍（判断的是区间覆盖，
 *       不只是等值——无时间/更宽的一段也能挡住窄的派生）</li>
 *   <li>深度上限 + 不推自环：`A → A` 是矛盾不是知识，交给 R0 报</li>
 * </ol>
 * 有效时间取交集：前提 A `[2020,2023)`、前提 B `[2022,∞)` → 派生 `[2022,2023)`；
 * 交集为空就不推（derive.rs:19-21）。
 */
public final class Derive {

    private Derive() {
    }

    /** 半开区间 {@code [from, to)}，两端可空（= 不知道/一直）。derive.rs:138-139。 */
    public record Span(Long from, Long to) {

        public static final Span OPEN = new Span(null, null);
    }

    /**
     * 一条参与推导/检查的边，比 {@link Edge} 多带有效期（derive.rs:95-104）。
     *
     * <p>单独一个类型而不是给 Edge 加字段：环与自环用不上时间，R0 的互斥三类要看
     * 两条是否同时成立（#634），R1 每一步都要算交集。
     */
    public record TimedEdge(Edge edge, Long from, Long to) {

        public Span span() {
            return new Span(from, to);
        }
    }

    /**
     * 交集（derive.rs:107-129）。{@code null} 表示无界那一侧；返回 {@code null} 表示空交集。
     *
     * <p>空交集不推（R1）/不参与（R0 的互斥与环）：两段没有重叠时，这条链在任何时刻都
     * 不成立——推出来的是一条从不为真的事实，比不推更糟。
     */
    public static Span overlap(Span a, Span b) {
        // 分支展开而不是三元套三元：Java 的数值提升会把装箱 Long 在判空前拆箱，
        // 两端都无界（最常见形状）就 NPE——Rust 的 match 没有这个坑
        Long from;
        if (a.from() != null && b.from() != null) {
            from = Math.max(a.from(), b.from());
        } else {
            from = a.from() != null ? a.from() : b.from();
        }
        Long to;
        if (a.to() != null && b.to() != null) {
            to = Math.min(a.to(), b.to());
        } else {
            to = a.to() != null ? a.to() : b.to();
        }
        if (from != null && to != null && from >= to) {
            return null;
        }
        return new Span(from, to);
    }

    /** 元组形态的便捷重载（Rust 的 {@code (Option<i64>, Option<i64>)}）。 */
    public static Span overlap(Long aFrom, Long aTo, Long bFrom, Long bTo) {
        return overlap(new Span(aFrom, aTo), new Span(bFrom, bTo));
    }

    // ===================== 规则与派生（derive.rs:34-92） =====================

    /** 单个谓词上派生的条数上限（derive.rs:32）。数出来要报，不能悄悄截断。 */
    public static final int MAX_DERIVED_PER_PREDICATE = 20_000;

    /** 公理编译出的规则种类 + 业务规则在不动点里的身份（derive.rs:35-61）。 */
    public enum Rule {
        /** `A p B` ∧ `B p C` ⟹ `A p C` */
        TRANSITIVE("transitive"),
        /** `A p B` ⟹ `B p A` */
        SYMMETRIC("symmetric"),
        /** `A p B` ∧ `p⁻¹ = q` ⟹ `B q A`——主宾对调<b>且</b>换谓词，两件事一起发生 */
        INVERSE("inverse"),
        /** `A p B` ∧ `p ⊑ q` ⟹ `A q B`——主宾不动，只升谓词 */
        SUB_PROPERTY("sub_property"),
        /**
         * 一条业务规则推出的关系边（0047）。不是公理：{@code derive()} 从不产出它，
         * 它只作为候选进矛盾检查，让撞上断言或别的派生时能像公理派生一样被报出来。
         */
        BUSINESS("business_rule");

        private final String asStr;

        Rule(String asStr) {
            this.asStr = asStr;
        }

        /** derive.rs:53-60 as_str——直接进 SQL 与 detail 的字面量。 */
        public String asString() {
            return asStr;
        }
    }

    /** 规则身份（声明所在的谓词 + 种类）——规则对聚合与排序都用它。 */
    public record RuleSide(UUID predicate, Rule rule) implements Comparable<RuleSide> {

        @Override
        public int compareTo(RuleSide o) {
            // Rust 的 (Uuid, as_str) 比较：字面量字典序（business_rule < inverse < …），
            // 不是声明顺序——a ≤ b 的「谁前谁后」由它定
            int c = predicate.compareTo(o.predicate);
            return c != 0 ? c : rule.asString().compareTo(o.rule.asString());
        }
    }

    /**
     * 一条要落地的派生事实，连同它的证明（derive.rs:64-83）。
     *
     * @param via 哪个谓词的声明触发了它——传递与对称不换谓词（== predicate），
     *            而 inverseOf / subPropertyOf 换。落库按 via 找规则行（踩过一次坑：
     *            原先按 predicate 找，跨谓词的两条规则加了之后查不到规则、被静默丢弃）
     */
    public record Derived(UUID predicate, UUID via, UUID subject, UUID object, Rule rule,
                          List<UUID> premises) {
    }

    /**
     * 一次推导的产出（derive.rs:86-92）。capped 是撞上单谓词上限、没推完的谓词
     * <b>必须回给调用方</b>：「推完了」和「推了一部分」在结果里长得一样。
     */
    public static final class Derivation {
        public final List<Derived> facts = new ArrayList<>();
        public final List<UUID> capped = new ArrayList<>();
    }

    // ===================== 不动点求值（derive.rs:131-357） =====================

    /** 邻接表里的一条边：(另一端, 起, 止, 支撑它的事实 id 列表)。证明是列表不是单个 id。 */
    private record Hop(UUID end, Long from, Long to, List<UUID> premises) {
    }

    /** 派生的中间态：一个 (主语, 宾语) 对是怎么来的（derive.rs:142-147）。 */
    private record Reached(Long from, Long to, List<UUID> premises) {
    }

    /** 一条派生已经推出来的样子：区间 + 证明（reached 表的条目）。 */
    private record Entry(Span span, Reached proof) {
    }

    /** 一条三元组的身份：(谓词, 主语, 宾语)。谓词进 key——跨谓词规则天生要它（derive.rs:155）。 */
    private record Triple(UUID predicate, UUID subject, UUID object) implements Comparable<Triple> {

        @Override
        public int compareTo(Triple o) {
            int c = predicate.compareTo(o.predicate);
            if (c != 0) {
                return c;
            }
            c = subject.compareTo(o.subject);
            return c != 0 ? c : object.compareTo(o.object);
        }
    }

    /**
     * 拿公理推一遍这批边（derive.rs:172-357）。
     *
     * <p><b>全局不动点，不按谓词分组</b>：三条规则会串起来
     * {@code A ceo_of B --(⊑)--> A works_at B --(⁻¹)--> B employs A}。半朴素求值扫全集：
     * 每一轮拿上一轮的新边（frontier）再推一遍，没有新增就停。三条一跳规则（对称/逆/
     * 子属性）与传递放在同一轮里互喂。轮数上限是兜底，真正的界在 premises ≥ MAX_DEPTH。
     */
    public static Derivation derive(List<TimedEdge> edges, Map<UUID, Axioms> axioms) {
        Derivation out = new Derivation();

        // 断言过的时序三元组——派生撞上它就让路（asserted > derived 硬性）
        Map<Triple, List<Span>> asserted = new HashMap<>();
        for (TimedEdge e : edges) {
            asserted.computeIfAbsent(new Triple(e.edge().predicate(), e.edge().subject(),
                    e.edge().object()), k -> new ArrayList<>()).add(new Span(e.from(), e.to()));
        }

        // 已经推出来的 → 怎么来的。同 triple 可有多段互不包含的区间；宽覆盖窄时
        // 短证明仍可继续展开（深度上限前多走几步）
        Map<Triple, List<Entry>> reached = new HashMap<>();
        // 封顶仍按谓词计：常量的含义是「一个谓词最多推两万条」，界面上答得出哪个谓词太密
        Map<UUID, Integer> perPred = new HashMap<>();
        Set<UUID> capped = new HashSet<>();

        // 出边 / 入边索引：新边也能当链的右半段（后到的 B→C 接到已见过的 A→B 上）
        Map<UUID, Map<UUID, List<Hop>>> adj = new HashMap<>();
        Map<UUID, Map<UUID, List<Hop>>> incoming = new HashMap<>();
        for (TimedEdge e : edges) {
            adj.computeIfAbsent(e.edge().predicate(), k -> new HashMap<>())
                    .computeIfAbsent(e.edge().subject(), k -> new ArrayList<>())
                    .add(new Hop(e.edge().object(), e.from(), e.to(), List.of(e.edge().fact())));
            incoming.computeIfAbsent(e.edge().predicate(), k -> new HashMap<>())
                    .computeIfAbsent(e.edge().object(), k -> new ArrayList<>())
                    .add(new Hop(e.edge().subject(), e.from(), e.to(), List.of(e.edge().fact())));
        }

        List<Map.Entry<Triple, Reached>> frontier = new ArrayList<>();
        for (TimedEdge e : edges) {
            frontier.add(Map.entry(
                    new Triple(e.edge().predicate(), e.edge().subject(), e.edge().object()),
                    new Reached(e.from(), e.to(), new ArrayList<>(List.of(e.edge().fact())))));
        }
        // 起始 frontier 按完整事实身份排一次：同一个库两次推导给得出同一份结果
        frontier.sort(Comparator.<Map.Entry<Triple, Reached>, Triple>comparing(Map.Entry::getKey)
                .thenComparing(e -> e.getValue().from(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(e -> e.getValue().to(), Comparator.nullsFirst(Comparator.naturalOrder())));

        for (int round = 0; round < AxiomChecks.MAX_DEPTH; round++) {
            if (frontier.isEmpty()) {
                break;
            }
            List<Map.Entry<Triple, Reached>> next = new ArrayList<>();
            for (Map.Entry<Triple, Reached> item : frontier) {
                Triple triple = item.getKey();
                Reached acc = item.getValue();
                Axioms ax = axioms.get(triple.predicate());
                if (ax == null) {
                    continue;
                }
                // 再接一条就超了：这一条不再往下延，但它自己已经产出过
                if (acc.premises().size() >= AxiomChecks.MAX_DEPTH) {
                    continue;
                }
                UUID pred = triple.predicate();
                UUID subj = triple.subject();
                UUID obj = triple.object();

                // ---- 一跳的三条：换主宾（对称）、换谓词（逆/子属性）
                List<Map.Entry<Triple, Rule>> hops = new ArrayList<>();
                if (ax.symmetric()) {
                    hops.add(Map.entry(new Triple(pred, obj, subj), Rule.SYMMETRIC));
                }
                if (ax.inverseOf() != null) {
                    hops.add(Map.entry(new Triple(ax.inverseOf(), obj, subj), Rule.INVERSE));
                }
                if (ax.subPropertyOf() != null) {
                    hops.add(Map.entry(new Triple(ax.subPropertyOf(), subj, obj),
                            Rule.SUB_PROPERTY));
                }
                for (Map.Entry<Triple, Rule> hop : hops) {
                    emit(hop.getKey(), pred, hop.getValue(), acc, acc.from(), acc.to(), null,
                            asserted, reached, perPred, capped, out, next, adj, incoming);
                }

                // ---- 传递：新边既能作左半段，也能作右半段
                if (ax.transitive()) {
                    List<Hop> outs = adj.getOrDefault(pred, Map.of())
                            .getOrDefault(obj, List.of());
                    for (Hop hop : outs) {
                        // 不推自环：A p A 在传递+非自反谓词上是矛盾，R0 连路径一起报
                        if (subj.equals(hop.end())) {
                            continue;
                        }
                        Span span = overlap(new Span(acc.from(), acc.to()),
                                new Span(hop.from(), hop.to()));
                        if (span == null) {
                            continue;
                        }
                        emit(new Triple(pred, subj, hop.end()), pred, Rule.TRANSITIVE, acc,
                                span.from(), span.to(), hop.premises(), asserted, reached,
                                perPred, capped, out, next, adj, incoming);
                    }
                    List<Hop> ins = incoming.getOrDefault(pred, Map.of())
                            .getOrDefault(subj, List.of());
                    for (Hop hop : ins) {
                        if (hop.end().equals(obj)) {
                            continue;
                        }
                        Span span = overlap(new Span(hop.from(), hop.to()),
                                new Span(acc.from(), acc.to()));
                        if (span == null) {
                            continue;
                        }
                        // emit 按左、右顺序拼证明；后到的是右边，不能把整条路径倒过来
                        Reached prefix = new Reached(hop.from(), hop.to(), hop.premises());
                        emit(new Triple(pred, hop.end(), obj), pred, Rule.TRANSITIVE, prefix,
                                span.from(), span.to(), acc.premises(), asserted, reached,
                                perPred, capped, out, next, adj, incoming);
                    }
                }
            }
            next.sort(Comparator.<Map.Entry<Triple, Reached>, Triple>comparing(Map.Entry::getKey)
                    .thenComparing(e -> e.getValue().from(), Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(e -> e.getValue().to(), Comparator.nullsFirst(Comparator.naturalOrder())));
            frontier = next;
        }

        // 窄区间可能先输出、宽区间后到。等传播结束再清理输出：较窄但更短的证明
        // 仍可能是 MAX_DEPTH 内走完后续链的必要中间态，不能在传播时仅按区间删掉
        Map<UUID, Span> spans = new HashMap<>();
        for (TimedEdge edge : edges) {
            spans.put(edge.edge().fact(), new Span(edge.from(), edge.to()));
        }
        retainWidestOutputs(out.facts, spans);

        List<UUID> sorted = new ArrayList<>(capped);
        sorted.sort(Comparator.naturalOrder());
        out.capped.addAll(sorted);
        return out;
    }

    /** {@code outer} 是否覆盖 {@code inner}（derive.rs:360-367）。两端 null 表示无界。 */
    static boolean spanContains(Span outer, Span inner) {
        boolean fromOk = outer.from() == null
                || (inner.from() != null && outer.from() <= inner.from());
        boolean toOk = outer.to() == null
                || (inner.to() != null && outer.to() >= inner.to());
        return fromOk && toOk;
    }

    /**
     * 输出侧清理（derive.rs:369-398）：同一个 triple 只留不被更宽区间覆盖的输出；
     * 相同区间只留先输出的一份（避免互相覆盖导致全部被删）。保留原顺序与整份证明。
     */
    static void retainWidestOutputs(List<Derived> facts, Map<UUID, Span> spans) {
        Map<Triple, List<int[]>> grouped = new LinkedHashMap<>();
        List<Span> factSpans = new ArrayList<>(facts.size());
        for (int i = 0; i < facts.size(); i++) {
            Derived fact = facts.get(i);
            Span span = validity(fact.premises(), spans);
            factSpans.add(span);
            if (span != null) {
                grouped.computeIfAbsent(new Triple(fact.predicate(), fact.subject(),
                        fact.object()), k -> new ArrayList<>()).add(new int[]{i});
            }
        }
        boolean[] keep = new boolean[facts.size()];
        java.util.Arrays.fill(keep, true);
        for (List<int[]> outputs : grouped.values()) {
            for (int[] index : outputs) {
                int i = index[0];
                Span span = factSpans.get(i);
                boolean covered = false;
                for (int[] other : outputs) {
                    int j = other[0];
                    if (j == i) {
                        continue;
                    }
                    Span outer = factSpans.get(j);
                    // 相同区间只留先输出的一份，避免互相覆盖导致全部被删
                    if (spanContains(outer, span) && (!outer.equals(span) || j < i)) {
                        covered = true;
                        break;
                    }
                }
                keep[i] = !covered;
            }
        }
        List<Derived> out = new ArrayList<>(facts.size());
        for (int i = 0; i < facts.size(); i++) {
            if (keep[i]) {
                out.add(facts.get(i));
            }
        }
        facts.clear();
        facts.addAll(out);
    }

    /** 求值上下文——四条规则共用（参数多但抽出来是必要的：上一版两处的跳过条件长得不一样了）。 */
    private static boolean emit(Triple t, UUID via, Rule rule, Reached acc, Long from, Long to,
                                List<UUID> extraPremises, Map<Triple, List<Span>> asserted,
                                Map<Triple, List<Entry>> reached, Map<UUID, Integer> perPred,
                                Set<UUID> capped, Derivation out,
                                List<Map.Entry<Triple, Reached>> next,
                                Map<UUID, Map<UUID, List<Hop>>> adj,
                                Map<UUID, Map<UUID, List<Hop>>> incoming) {
        UUID pred = t.predicate();
        UUID subj = t.subject();
        UUID obj = t.object();
        // 自环不推，任何规则都一样
        if (subj.equals(obj)) {
            return false;
        }
        Span span = new Span(from, to);
        // 断言优先；无时间断言也覆盖日期的派生
        for (Span outer : asserted.getOrDefault(t, List.of())) {
            if (spanContains(outer, span)) {
                return false;
            }
        }
        List<UUID> premises = new ArrayList<>(acc.premises());
        if (extraPremises != null) {
            premises.addAll(extraPremises);
        }
        // 深度上限拼完之后才判得上：传递接上来的那条边可能自带好几条前提
        if (premises.size() > AxiomChecks.MAX_DEPTH) {
            return false;
        }

        List<Entry> previous = reached.computeIfAbsent(t, k -> new ArrayList<>());
        boolean covered = previous.stream().anyMatch(e -> spanContains(e.span(), span));
        // 区间与长度都被覆盖才丢掉：先走绕路不该让后来的短证明失去走完的机会
        boolean coveredByShorter = false;
        for (Entry e : previous) {
            if (spanContains(e.span(), span)
                    && e.proof().premises().size() <= premises.size()) {
                coveredByShorter = true;
                break;
            }
        }
        if (coveredByShorter) {
            return false;
        }
        if (!covered) {
            int n = perPred.getOrDefault(pred, 0);
            if (n >= MAX_DERIVED_PER_PREDICATE) {
                capped.add(pred);
                return true;
            }
            perPred.put(pred, n + 1);
        }

        Reached r = new Reached(from, to, premises);
        previous.removeIf(e -> spanContains(span, e.span())
                && premises.size() <= e.proof().premises().size());
        previous.add(new Entry(span, r));
        // 派生出来的边也能被后续传递接上——整份证明都要带上，只留首条会让证明越拼越短。
        // adj 按 (谓词, 主语) 索引指向宾语；incoming 按 (谓词, 宾语) 索引指向主语
        if (!premises.isEmpty()) {
            Hop forward = new Hop(obj, from, to, premises);
            List<Hop> outs = adj.computeIfAbsent(pred, k -> new HashMap<>())
                    .computeIfAbsent(subj, k -> new ArrayList<>());
            outs.removeIf(h -> h.end().equals(obj) && spanContains(span,
                    new Span(h.from(), h.to())) && premises.size() <= h.premises().size());
            outs.add(forward);
            Hop backward = new Hop(subj, from, to, premises);
            List<Hop> ins = incoming.computeIfAbsent(pred, k -> new HashMap<>())
                    .computeIfAbsent(obj, k -> new ArrayList<>());
            ins.removeIf(h -> h.end().equals(subj) && spanContains(span,
                    new Span(h.from(), h.to())) && premises.size() <= h.premises().size());
            ins.add(backward);
        }
        if (!covered) {
            out.facts.add(new Derived(pred, via, subj, obj, rule, premises));
        }
        next.add(Map.entry(t, r));
        return false;
    }

    /**
     * 派生事实的有效期（derive.rs:513-523）：前提区间的交集。与 derive 分开——交集已经在
     * 推导过程里算过了，前提就是那几条事实，交集是它们的函数。前提区间不交（算不出）得 null。
     */
    public static Span validity(List<UUID> premises, Map<UUID, Span> byFact) {
        Span acc = Span.OPEN;
        for (UUID p : premises) {
            Span span = byFact.get(p);
            if (span == null) {
                return null;
            }
            acc = overlap(acc, span);
            if (acc == null) {
                return null;
            }
        }
        return acc;
    }

    // ===================== 矛盾：派生撞上了什么（0017，derive.rs:525-763） =====================

    /** 一条派生撞上了一条断言（derive.rs:528-536）。 */
    public record Clash(int derived, Kind axiom, UUID against) {
    }

    /**
     * 两条规则加在一起产出了互相矛盾的派生（derive.rs:538-550）。
     * <b>按规则对聚合，不逐对报</b>：根子是那两条声明，逐对进队列只会淹掉 Review。
     */
    public record RuleClash(RuleSide a, RuleSide b, Kind axiom, List<int[]> pairs) {
    }

    /** (谓词, 一端) → 另一端的边：(另一端, 事实, 区间)。 */
    private record ByEndItem(UUID end, UUID fact, Span span) {
    }

    /** 一次矛盾检查的产出（derive.rs:559-563）。 */
    public static final class Contradictions {
        public final List<Clash> withAssertions = new ArrayList<>();
        public final List<RuleClash> betweenDerivations = new ArrayList<>();

        /** 不该落地的派生下标：撞过断言的，和撞过别的派生的。写图宁少勿错（0002）。 */
        public Set<Integer> blocked() {
            Set<Integer> out = new HashSet<>();
            for (Clash c : withAssertions) {
                out.add(c.derived());
            }
            for (RuleClash rc : betweenDerivations) {
                for (int[] pair : rc.pairs()) {
                    out.add(pair[0]);
                    out.add(pair[1]);
                }
            }
            return out;
        }
    }

    /**
     * 拿公理量一遍派生（derive.rs:588-763）：与断言撞的逐条列出，派生之间撞的按规则对聚合。
     *
     * <p>只查四类——functional（含 inverse）、asymmetric、irreflexive——只有它们能由
     * <b>两条边</b>判出矛盾；都要<b>区间重叠</b>（Mira 走了 Devin 接任，区间不交是接任）。
     * 撞上断言的派生一律不落地（asserted &gt; derived 硬性）；这一步把「让路」变成可见。
     */
    public static Contradictions contradictions(Derivation derivation, List<TimedEdge> edges,
                                                Map<UUID, Axioms> axioms,
                                                Map<UUID, Span> spans) {
        // 断言的三份索引：(谓词, 主) → 宾；(谓词, 宾) → 主；(谓词, 主, 宾) → 边
        Map<UUID, Map<UUID, List<ByEndItem>>> byPs = new HashMap<>();
        Map<UUID, Map<UUID, List<ByEndItem>>> byPo = new HashMap<>();
        Map<Triple, List<Map.Entry<UUID, Span>>> bySpo = new HashMap<>();
        for (TimedEdge e : edges) {
            Span span = new Span(e.from(), e.to());
            byPs.computeIfAbsent(e.edge().predicate(), k -> new HashMap<>())
                    .computeIfAbsent(e.edge().subject(), k -> new ArrayList<>())
                    .add(new ByEndItem(e.edge().object(), e.edge().fact(), span));
            byPo.computeIfAbsent(e.edge().predicate(), k -> new HashMap<>())
                    .computeIfAbsent(e.edge().object(), k -> new ArrayList<>())
                    .add(new ByEndItem(e.edge().subject(), e.edge().fact(), span));
            bySpo.computeIfAbsent(new Triple(e.edge().predicate(), e.edge().subject(),
                    e.edge().object()), k -> new ArrayList<>())
                    .add(Map.entry(e.edge().fact(), span));
        }

        Contradictions out = new Contradictions();
        // 派生的区间：与落库那一侧同一个函数算，算不出的（前提区间不交）本来就不会落
        List<Span> derivedSpans = new ArrayList<>(derivation.facts.size());
        for (Derived d : derivation.facts) {
            derivedSpans.add(validity(d.premises(), spans));
        }

        for (int i = 0; i < derivation.facts.size(); i++) {
            Derived d = derivation.facts.get(i);
            Span span = derivedSpans.get(i);
            if (span == null) {
                continue;
            }
            Axioms ax = axioms.get(d.predicate());
            if (ax == null) {
                continue;
            }
            if (d.premises().isEmpty()) {
                continue;
            }
            UUID last = d.premises().get(d.premises().size() - 1);
            if (ax.irreflexive() && d.subject().equals(d.object())) {
                out.withAssertions.add(new Clash(i, Kind.SELF_LOOP, last));
            }
            if (ax.asymmetric()) {
                for (Map.Entry<UUID, Span> v : bySpo.getOrDefault(
                        new Triple(d.predicate(), d.object(), d.subject()), List.of())) {
                    if (overlap(span, v.getValue()) != null) {
                        out.withAssertions.add(new Clash(i, Kind.ASYMMETRY, v.getKey()));
                    }
                }
            }
            if (ax.functional()) {
                for (ByEndItem v : byPs.getOrDefault(d.predicate(), Map.of())
                        .getOrDefault(d.subject(), List.of())) {
                    if (!v.end().equals(d.object()) && overlap(span, v.span()) != null) {
                        out.withAssertions.add(new Clash(i, Kind.FUNCTIONAL, v.fact()));
                    }
                }
            }
            if (ax.inverseFunctional()) {
                for (ByEndItem v : byPo.getOrDefault(d.predicate(), Map.of())
                        .getOrDefault(d.object(), List.of())) {
                    if (!v.end().equals(d.subject()) && overlap(span, v.span()) != null) {
                        out.withAssertions.add(new Clash(i, Kind.INVERSE_FUNCTIONAL, v.fact()));
                    }
                }
            }
        }

        // 派生之间：同样三份索引，只不过键的是下标
        Map<UUID, Map<UUID, List<Integer>>> dPs = new HashMap<>();
        Map<UUID, Map<UUID, List<Integer>>> dPo = new HashMap<>();
        Map<Triple, List<Integer>> dSpo = new HashMap<>();
        for (int i = 0; i < derivation.facts.size(); i++) {
            if (derivedSpans.get(i) == null) {
                continue;
            }
            Derived d = derivation.facts.get(i);
            dPs.computeIfAbsent(d.predicate(), k -> new HashMap<>())
                    .computeIfAbsent(d.subject(), k -> new ArrayList<>()).add(i);
            dPo.computeIfAbsent(d.predicate(), k -> new HashMap<>())
                    .computeIfAbsent(d.object(), k -> new ArrayList<>()).add(i);
            dSpo.computeIfAbsent(new Triple(d.predicate(), d.subject(), d.object()),
                    k -> new ArrayList<>()).add(i);
        }
        // 分组键 (规则 a, 规则 b, 撞在哪条公理上)；TreeMap 保持键序稳定 → 输出可复现
        Map<List<Object>, List<int[]>> grouped = new LinkedHashMap<>();
        Set<List<Object>> groupOrder = new java.util.LinkedHashSet<>();
        for (int i = 0; i < derivation.facts.size(); i++) {
            Derived d = derivation.facts.get(i);
            Span span = derivedSpans.get(i);
            if (span == null) {
                continue;
            }
            Axioms ax = axioms.get(d.predicate());
            if (ax == null) {
                continue;
            }
            if (ax.asymmetric()) {
                for (int j : dSpo.getOrDefault(
                        new Triple(d.predicate(), d.object(), d.subject()), List.of())) {
                    if (j > i && overlaps(derivedSpans, j, span)) {
                        note(grouped, groupOrder, derivation, i, j, Kind.ASYMMETRY);
                    }
                }
            }
            if (ax.functional()) {
                for (int j : dPs.getOrDefault(d.predicate(), Map.of())
                        .getOrDefault(d.subject(), List.of())) {
                    if (j > i && !derivation.facts.get(j).object().equals(d.object())
                            && overlaps(derivedSpans, j, span)) {
                        note(grouped, groupOrder, derivation, i, j, Kind.FUNCTIONAL);
                    }
                }
            }
            if (ax.inverseFunctional()) {
                for (int j : dPo.getOrDefault(d.predicate(), Map.of())
                        .getOrDefault(d.object(), List.of())) {
                    if (j > i && !derivation.facts.get(j).subject().equals(d.subject())
                            && overlaps(derivedSpans, j, span)) {
                        note(grouped, groupOrder, derivation, i, j, Kind.INVERSE_FUNCTIONAL);
                    }
                }
            }
        }
        for (List<Object> key : groupOrder) {
            List<int[]> pairs = grouped.get(key);
            pairs.sort(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[1]));
            // Rust sort_unstable + dedup：排序去重（同一对只会记一次，这里 dedup 保守等价）
            List<int[]> unique = new ArrayList<>();
            for (int[] p : pairs) {
                if (unique.isEmpty() || !java.util.Arrays.equals(unique.get(unique.size() - 1), p)) {
                    unique.add(p);
                }
            }
            @SuppressWarnings("unchecked")
            RuleSide a = (RuleSide) key.get(0);
            @SuppressWarnings("unchecked")
            RuleSide b = (RuleSide) key.get(1);
            out.betweenDerivations.add(new RuleClash(a, b, (Kind) key.get(2), unique));
        }
        // 输出排过序——这条路的价值有一半在确定性（Rust 的排序键只有规则对，公理不入键：
        // 同对谓词几种撞法之间的相对次序按聚合遇到的顺序保持稳定）
        out.betweenDerivations.sort(Comparator
                .comparing((RuleClash rc) -> rc.a())
                .thenComparing(RuleClash::b));
        return out;
    }

    private static boolean overlaps(List<Span> derivedSpans, int j, Span span) {
        Span other = derivedSpans.get(j);
        return other != null && overlap(span, other) != null;
    }

    @SuppressWarnings("unchecked")
    private static void note(Map<List<Object>, List<int[]>> grouped, Set<List<Object>> order,
                             Derivation derivation, int i, int j, Kind axiom) {
        int lo = Math.min(i, j);
        int hi = Math.max(i, j);
        Derived di = derivation.facts.get(lo);
        Derived dj = derivation.facts.get(hi);
        RuleSide ri = new RuleSide(di.via(), di.rule());
        RuleSide rj = new RuleSide(dj.via(), dj.rule());
        RuleSide a;
        RuleSide b;
        if (ri.compareTo(rj) <= 0) {
            a = ri;
            b = rj;
        } else {
            a = rj;
            b = ri;
        }
        List<Object> key = List.of(a, b, axiom);
        order.add(key);
        grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(new int[]{lo, hi});
    }
}
