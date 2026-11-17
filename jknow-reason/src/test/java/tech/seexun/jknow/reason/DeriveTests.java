package tech.seexun.jknow.reason;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.reason.Derive.Contradictions;
import tech.seexun.jknow.reason.Derive.Derived;
import tech.seexun.jknow.reason.Derive.Derivation;
import tech.seexun.jknow.reason.Derive.Rule;
import tech.seexun.jknow.reason.Derive.RuleSide;
import tech.seexun.jknow.reason.Derive.Span;
import tech.seexun.jknow.reason.Derive.TimedEdge;

/**
 * 移植自 utopia-reason/src/derive.rs 内联 mod tests（41 例）——frontier 半朴素不动点、
 * 断言优先、区间交集/覆盖、封顶上报、跨谓词两规则、证明完整性、矛盾四类。
 * 断言逐条对译，不改写。
 */
class DeriveTests {

    // ---- Rust 测试侧的 UUID 构造（from_bytes / from_u128） ----

    private static UUID bytes(byte b) {
        byte[] raw = new byte[16];
        Arrays.fill(raw, b);
        return bytes(raw);
    }

    private static UUID bytes(byte[] raw) {
        ByteBuffer buf = ByteBuffer.wrap(raw);
        return new UUID(buf.getLong(), buf.getLong());
    }

    private static UUID n(long i) {
        return bytes((byte) i);
    }

    private static UUID f(long i) {
        byte[] raw = new byte[16];
        Arrays.fill(raw, (byte) (i ^ 0xF0));
        return bytes(raw);
    }

    private static UUID u128(long hi, long lo) {
        return new UUID(hi, lo);
    }

    /** 一条无时间的边 */
    private static TimedEdge e(long fact, long s, long o) {
        return te(fact, s, o, null, null);
    }

    /** 一条带区间的边 */
    private static TimedEdge te(long fact, long s, long o, Long from, Long to) {
        return new TimedEdge(new Edge(f(fact), n(99), n(s), n(o)), from, to);
    }

    private static Map<UUID, Axioms> with(Axioms ax) {
        return Map.of(n(99), ax);
    }

    private static Map<UUID, Axioms> transitive() {
        return with(new Axioms(true, false, false, false, false, false, null, null));
    }

    /** (主, 宾) 对，排过序——Rust 测试的 pairs() 助手 */
    private static List<long[]> pairs(Derivation d) {
        List<long[]> p = new ArrayList<>();
        for (Derived x : d.facts) {
            p.add(new long[]{byteAt(x.subject()), byteAt(x.object())});
        }
        p.sort(Comparator.<long[]>comparingLong(a -> a[0]).thenComparingLong(a -> a[1]));
        return p;
    }

    private static long byteAt(UUID u) {
        return u.getMostSignificantBits() >>> 56 & 0xFF;
    }

    private static void assertPairs(Derivation d, long[]... expected) {
        List<long[]> got = pairs(d);
        List<long[]> want = new ArrayList<>(Arrays.asList(expected));
        want.sort(Comparator.<long[]>comparingLong(a -> a[0]).thenComparingLong(a -> a[1]));
        assertEquals(want.size(), got.size(), () -> "expected " + want + " got " + got);
        for (int i = 0; i < want.size(); i++) {
            assertArrayEquals(want.get(i), got.get(i));
        }
    }

    private static void assertArrayEquals(long[] a, long[] b) {
        assertTrue(Arrays.equals(a, b), () -> Arrays.toString(a) + " vs " + Arrays.toString(b));
    }

    private static Map<UUID, Span> spansOf(List<TimedEdge> edges) {
        Map<UUID, Span> out = new HashMap<>();
        for (TimedEdge x : edges) {
            out.put(x.edge().fact(), new Span(x.from(), x.to()));
        }
        return out;
    }

    @Test
    void nothingDeclaredDerivesNothing() {
        List<TimedEdge> edges = List.of(e(1, 1, 2), e(2, 2, 3));
        assertTrue(Derive.derive(edges, Map.of()).facts.isEmpty());
        // 声明了别的公理也一样——只有 transitive / symmetric / inverse / sub_property
        // 编得出规则
        Map<UUID, Axioms> irr = with(
                new Axioms(false, false, false, true, false, false, null, null));
        assertTrue(Derive.derive(edges, irr).facts.isEmpty());
    }

    @Test
    void aChainCloses() {
        // 1→2→3→4，传递应推出 1→3、1→4、2→4
        List<TimedEdge> edges = List.of(e(1, 1, 2), e(2, 2, 3), e(3, 3, 4));
        Derivation d = Derive.derive(edges, transitive());
        assertPairs(d, new long[]{1, 3}, new long[]{1, 4}, new long[]{2, 4});
        assertTrue(d.capped.isEmpty());
    }

    @Test
    void theProofIsThePremisesInOrder() {
        List<TimedEdge> edges = List.of(e(1, 1, 2), e(2, 2, 3), e(3, 3, 4));
        Derivation d = Derive.derive(edges, transitive());
        Derived long0 = d.facts.stream()
                .filter(x -> x.subject().equals(n(1)) && x.object().equals(n(4)))
                .findFirst().orElseThrow();
        assertEquals(List.of(f(1), f(2), f(3)), long0.premises(), "证明要按推导顺序带上三条前提");
        assertEquals(Rule.TRANSITIVE, long0.rule());
    }

    @Test
    void aChainedDerivationKeepsEveryPremise() {
        // 4→1→2 推出 4→2，而 4→2 已经是一条证明；传递再接 2→3 时，那条派生边
        // 自带的两条前提要一起进证明。少了任一条，validity 就只对留下的前提求
        // 交集，结论会比实际宽
        List<TimedEdge> edges = List.of(
                te(1, 1, 2, 0L, 100L),
                te(2, 2, 3, 40L, 50L),
                te(3, 4, 1, 0L, 100L));
        Derivation d = Derive.derive(edges, transitive());
        Derived to3 = d.facts.stream()
                .filter(x -> x.subject().equals(n(4)) && x.object().equals(n(3)))
                .findFirst().orElseThrow(() -> {
                    throw new AssertionError("4→3 应由 4→1→2→3 推出");
                });
        assertEquals(List.of(f(3), f(1), f(2)), to3.premises(),
                "证明要带上链上三条前提，中间那条派生边的两条都在内");
        Map<UUID, Span> byFact = new HashMap<>();
        for (TimedEdge x : edges) {
            byFact.put(x.edge().fact(), new Span(x.from(), x.to()));
        }
        assertEquals(new Span(40L, 50L), Derive.validity(to3.premises(), byFact),
                "有效期应是三条前提的交集，而不是漏掉中间那条的宽区间");
    }

    @Test
    void assertedBeatsDerived() {
        // 1→2、2→3 已经推得出 1→3，而 1→3 也被断言过 → 不重复派生
        List<TimedEdge> edges = List.of(e(1, 1, 2), e(2, 2, 3), e(3, 1, 3));
        Derivation d = Derive.derive(edges, transitive());
        assertTrue(d.facts.isEmpty(), "断言过的三元组不该再派生一份");
    }

    @Test
    void aRingDoesNotDeriveSelfLoopsAndDoesNotHang() {
        // 1→2→3→1：环。传递闭包里会推出 1→1，而那是矛盾不是知识
        List<TimedEdge> edges = List.of(e(1, 1, 2), e(2, 2, 3), e(3, 3, 1));
        Derivation d = assertDoesNotThrow(() -> Derive.derive(edges, transitive()));
        assertTrue(d.facts.stream().allMatch(x -> !x.subject().equals(x.object())),
                "自环不该被推出来——R0 会把这个环连路径一起报");
        // 但环上其余的推导是成立的：1→3、2→1、3→2
        assertPairs(d, new long[]{1, 3}, new long[]{2, 1}, new long[]{3, 2});
    }

    @Test
    void symmetricDerivesTheOtherDirectionOnce() {
        Map<UUID, Axioms> sym = with(
                new Axioms(false, true, false, false, false, false, null, null));
        List<TimedEdge> edges = List.of(e(1, 1, 2));
        Derivation d = Derive.derive(edges, sym);
        assertPairs(d, new long[]{2, 1});
        assertEquals(Rule.SYMMETRIC, d.facts.get(0).rule());
        assertEquals(List.of(f(1)), d.facts.get(0).premises());
        // 两个方向都断言过 → 无可派生
        List<TimedEdge> both = List.of(e(1, 1, 2), e(2, 2, 1));
        assertTrue(Derive.derive(both, sym).facts.isEmpty());
    }

    @Test
    void validityIsTheIntersection() {
        // 1→2 在 [10,30)，2→3 在 [20,∞) ⟹ 1→3 在 [20,30)
        List<TimedEdge> edges = List.of(te(1, 1, 2, 10L, 30L), te(2, 2, 3, 20L, null));
        Derivation d = Derive.derive(edges, transitive());
        assertPairs(d, new long[]{1, 3});
        Map<UUID, Span> byFact = Map.of(f(1), new Span(10L, 30L), f(2), new Span(20L, null));
        assertEquals(new Span(20L, 30L), Derive.validity(d.facts.get(0).premises(), byFact));
    }

    @Test
    void noOverlapDerivesNothing() {
        // 1→2 只在 [10,20)，2→3 只在 [30,40) —— 这条链在任何时刻都不成立
        List<TimedEdge> edges = List.of(
                te(1, 1, 2, 10L, 20L),
                te(2, 2, 3, 30L, 40L));
        Derivation d = Derive.derive(edges, transitive());
        assertTrue(d.facts.isEmpty(), "两段不重叠时推出来的是一条从不为真的事实");
    }

    @Test
    void aTouchingBoundaryIsNotAnOverlap() {
        // [10,20) 与 [20,30)：半开区间，端点相接不算重叠
        List<TimedEdge> edges = List.of(
                te(1, 1, 2, 10L, 20L),
                te(2, 2, 3, 20L, 30L));
        assertTrue(Derive.derive(edges, transitive()).facts.isEmpty());
    }

    @Test
    void depthIsBounded() {
        // 一条 40 跳的链，深度上限是 12
        List<TimedEdge> edges = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            edges.add(e(i, i, i + 1));
        }
        Derivation d = Derive.derive(edges, transitive());
        int longest = d.facts.stream().mapToInt(x -> x.premises().size()).max().orElse(0);
        assertTrue(longest <= AxiomChecks.MAX_DEPTH,
                "证明长度不该超过深度上限，实际 " + longest);
        assertFalse(d.facts.isEmpty(), "有上限不等于什么都不推");
    }

    private static List<TimedEdge> edgesWithShortcuts() {
        // 21→8→16 与 14→1→7→12 都有捷径；先碰到绕路的证明，不该让
        // 后到的短证明失去继续推导的机会。11→20 的最短路径恰好 12 条前提。
        long[][] pairs = {
                {12, 0}, {7, 12}, {15, 10}, {1, 7}, {2, 5}, {8, 16}, {13, 21}, {5, 13},
                {6, 14}, {14, 12}, {21, 8}, {0, 20}, {10, 2}, {21, 16}, {14, 1}, {11, 15},
                {16, 6}};
        List<TimedEdge> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i++) {
            out.add(e(i, pairs[i][0], pairs[i][1]));
        }
        return out;
    }

    @Test
    void aShorterProofKeepsAChainWithinTheDepthLimit() {
        List<TimedEdge> edges = edgesWithShortcuts();
        Derivation d = Derive.derive(edges, transitive());
        Derived chain = d.facts.stream()
                .filter(x -> x.subject().equals(n(11)) && x.object().equals(n(20)))
                .findFirst().orElseThrow(() -> {
                    throw new AssertionError("12 条前提能走完的链不能被先到的长证明挡住");
                });
        assertEquals(AxiomChecks.MAX_DEPTH, chain.premises().size());
        assertTrue(d.facts.stream().allMatch(x -> x.premises().size() <= AxiomChecks.MAX_DEPTH));
        Set<String> distinct = new HashSet<>();
        for (Derived x : d.facts) {
            distinct.add(x.subject() + ">" + x.object());
        }
        assertEquals(d.facts.size(), distinct.size(), "短证明不再物化同一段事实");
        assertTrue(d.capped.isEmpty());
    }

    @Test
    void shorterProofsDoNotSpendTheOutputCapTwice() {
        // 多个互不相连的副本共用一个谓词；每份都含需要重新展开的短证明。
        // 封顶数的是实际产出的事实，不能拿这些替代证明抵扣额度。
        List<TimedEdge> component = edgesWithShortcuts();
        List<TimedEdge> edges = new ArrayList<>();
        for (int copy = 0; copy < 300; copy++) {
            for (int i = 0; i < component.size(); i++) {
                TimedEdge ed = component.get(i);
                edges.add(new TimedEdge(
                        new Edge(u128(0, 100_000L + copy * 32L + i),
                                ed.edge().predicate(),
                                u128(0, copy * 32L + byteAt(ed.edge().subject())),
                                u128(0, copy * 32L + byteAt(ed.edge().object()))),
                        ed.from(), ed.to()));
            }
        }
        Derivation d = Derive.derive(edges, transitive());
        assertEquals(Derive.MAX_DERIVED_PER_PREDICATE, d.facts.size());
        assertEquals(List.of(n(99)), d.capped);
        Set<String> distinct = new HashSet<>();
        for (Derived x : d.facts) {
            distinct.add(x.subject() + ">" + x.object());
        }
        assertEquals(d.facts.size(), distinct.size());
        assertTrue(d.facts.stream().allMatch(x -> x.premises().size() <= AxiomChecks.MAX_DEPTH));
    }

    @Test
    void symmetricFeedsTheTransitiveChain() {
        // 同时声明对称与传递：1→2 与 3→2 断言过，对称推出 2→3，
        // 于是传递能接上 1→3
        Map<UUID, Axioms> both = with(
                new Axioms(true, true, false, false, false, false, null, null));
        List<TimedEdge> edges = List.of(e(1, 1, 2), e(2, 3, 2));
        Derivation d = Derive.derive(edges, both);
        List<long[]> got = pairs(d);
        boolean has21 = false;
        boolean has23 = false;
        boolean has13 = false;
        for (long[] p : got) {
            has21 |= p[0] == 2 && p[1] == 1;
            has23 |= p[0] == 2 && p[1] == 3;
            has13 |= p[0] == 1 && p[1] == 3;
        }
        assertTrue(has21 && has23, "两条对称边");
        assertTrue(has13, "对称推出来的边要能继续参与传递");
    }

    @Test
    void eachPredicateIsClosedOnItsOwn() {
        // 99 传递、98 不是：跨谓词不该接成链
        List<TimedEdge> edges = new ArrayList<>();
        edges.add(e(1, 1, 2));
        edges.add(new TimedEdge(new Edge(f(2), n(98), n(2), n(3)), null, null));
        Derivation d = Derive.derive(edges, transitive());
        assertTrue(d.facts.isEmpty(), "1 →(99) 2 →(98) 3 推不出任何东西");
    }

    // ---- 跨谓词的两条规则（inverseOf / subPropertyOf）----

    private static final UUID P = bytes((byte) 1);
    private static final UUID Q = bytes((byte) 2);
    private static final UUID R = bytes((byte) 3);

    /** 指定谓词的一条无时间边 */
    private static TimedEdge ep(UUID pred, long fact, long s, long o) {
        return tep(pred, fact, s, o, null, null);
    }

    /** 指定谓词、带区间 */
    private static TimedEdge tep(UUID pred, long fact, long s, long o, Long from, Long to) {
        return new TimedEdge(new Edge(f(fact), pred, n(s), n(o)), from, to);
    }

    /** `p⁻¹ = q` 且 `q⁻¹ = p`——互指，收敛性靠它测 */
    private static Map<UUID, Axioms> inversePair() {
        return Map.of(P, new Axioms(false, false, false, false, false, false, Q, null),
                Q, new Axioms(false, false, false, false, false, false, P, null));
    }

    /** `p ⊑ q` */
    private static Map<UUID, Axioms> subProperty() {
        return Map.of(P, new Axioms(false, false, false, false, false, false, null, Q));
    }

    /** A works_at B ⟹ B employs A：主宾对调且换谓词——两件都断言。 */
    @Test
    void theInverseSwapsTheEndsAndThePredicate() {
        Derivation d = Derive.derive(List.of(ep(P, 1, 1, 2)), inversePair());
        assertEquals(1, d.facts.size(), "一条边只推出一条逆");
        Derived got = d.facts.get(0);
        assertEquals(Q, got.predicate(), "**谓词换了**");
        assertEquals(n(2), got.subject());
        assertEquals(n(1), got.object(), "**主宾也对调了**");
        assertEquals(Rule.INVERSE, got.rule());
        assertEquals(P, got.via(), "声明写在 P 上，产出落在 Q 上");
        assertEquals(List.of(f(1)), got.premises(), "证明就是那一条原边");
    }

    /** `p⁻¹ = q` 且 `q⁻¹ = p` —— 互指。推回来的那条已经断言过，必须收敛而不是来回震荡。 */
    @Test
    void aMutualInverseSettlesInsteadOfBouncing() {
        Derivation d = Derive.derive(List.of(ep(P, 1, 1, 2), ep(Q, 2, 2, 1)), inversePair());
        assertTrue(d.facts.isEmpty(), "两个方向都已经断言过，一条都不该推——**断言优先**");
    }

    /** `p ⊑ q`：断言具体的，通用的也成立。主宾不动。 */
    @Test
    void aSubPropertyLiftsThePredicateAndKeepsTheEnds() {
        Derivation d = Derive.derive(List.of(ep(P, 1, 1, 2)), subProperty());
        assertEquals(1, d.facts.size());
        Derived got = d.facts.get(0);
        assertEquals(Q, got.predicate(), "升到父属性");
        assertEquals(n(1), got.subject());
        assertEquals(n(2), got.object(), "主宾不动");
        assertEquals(Rule.SUB_PROPERTY, got.rule());
        assertEquals(P, got.via(), "**via 是声明公理的那个谓词**，不是推出来的那个——落库按它找规则行");
    }

    /** 不换谓词的两条规则，via 必须等于 predicate——跨谓词的两条一加，键选错的 bug 就藏不住。 */
    @Test
    void forTheSamePredicateRulesViaIsThePredicate() {
        Derivation d = Derive.derive(List.of(e(1, 1, 2), e(2, 2, 3)), transitive());
        assertFalse(d.facts.isEmpty());
        for (Derived x : d.facts) {
            assertEquals(x.predicate(), x.via(), "传递不换谓词");
        }
    }

    /** 这一条是整次改造的理由：三条规则串起来（ceo_of ⊑ works_at；works_at⁻¹ = employs）。 */
    @Test
    void aSubPropertyFeedsTheInverse() {
        Map<UUID, Axioms> ax = Map.of(
                P, new Axioms(false, false, false, false, false, false, null, Q),
                Q, new Axioms(false, false, false, false, false, false, R, null));
        Derivation d = Derive.derive(List.of(ep(P, 1, 1, 2)), ax);
        record Row(UUID predicate, long subject, long object) {
        }
        List<Row> got = new ArrayList<>();
        for (Derived x : d.facts) {
            got.add(new Row(x.predicate(), byteAt(x.subject()), byteAt(x.object())));
        }
        got.sort(Comparator.comparing(Row::predicate).thenComparing(Row::subject)
                .thenComparing(Row::object));
        boolean hasWorksAt = got.stream().anyMatch(r -> r.predicate().equals(Q) && r.subject() == 1
                && r.object() == 2);
        boolean hasEmploys = got.stream().anyMatch(r -> r.predicate().equals(R) && r.subject() == 2
                && r.object() == 1);
        assertTrue(hasWorksAt, "先升成 works_at");
        assertTrue(hasEmploys, "**再转成 employs 的反方向**——跨了两个谓词，旧结构做不到");
        assertEquals(2, got.size());
        // 证明要跟着长：第二跳用掉两条前提里的第一条
        Derived employs = d.facts.stream().filter(x -> x.predicate().equals(R))
                .findFirst().orElseThrow();
        assertEquals(List.of(f(1)), employs.premises(), "根还是那条原始断言");
    }

    /** 逆推出来的边要能被传递接上（逆产出的边要进邻接表，否则传递接不上它）。 */
    @Test
    void whatTheInverseProducesCanStillBeChained() {
        Map<UUID, Axioms> ax = Map.of(
                P, new Axioms(false, false, false, false, false, false, Q, null),
                Q, new Axioms(true, false, false, false, false, false, null, null));
        // A p B、B p C  ⟹  B q A、C q B  ⟹（q 传递）⟹ C q A
        Derivation d = Derive.derive(List.of(ep(P, 1, 1, 2), ep(P, 2, 2, 3)), ax);
        record Row(UUID predicate, long subject, long object) {
        }
        Set<String> got = new HashSet<>();
        for (Derived x : d.facts) {
            got.add(x.predicate() + ":" + byteAt(x.subject()) + ">" + byteAt(x.object()));
        }
        assertTrue(got.contains(Q + ":2>1"));
        assertTrue(got.contains(Q + ":3>2"));
        assertTrue(got.contains(Q + ":3>1"), "**逆产出的边要进邻接表**，否则传递接不上它");
    }

    @Test
    void aLateRightHandEdgeCompletesAnExistingChain() {
        for (UUID[] pair : new UUID[][]{{Q, P}, {P, Q}}) {
            UUID source = pair[0];
            UUID target = pair[1];
            for (boolean inverse : new boolean[]{false, true}) {
                Map<UUID, Axioms> ax = Map.of(
                        source, new Axioms(false, false, false, false, false, false,
                                inverse ? target : null, inverse ? null : target),
                        target, new Axioms(true, false, false, false, false, false, null, null));
                TimedEdge left = ep(target, 1, 1, 2);
                TimedEdge right = inverse ? ep(source, 2, 3, 2) : ep(source, 2, 2, 3);
                for (List<TimedEdge> edges : List.of(List.of(left, right),
                        List.of(right, left))) {
                    Derivation d = Derive.derive(edges, ax);
                    Derived chain = d.facts.stream()
                            .filter(x -> x.predicate().equals(target)
                                    && x.subject().equals(n(1)) && x.object().equals(n(3)))
                            .findFirst().orElseThrow(() -> {
                                throw new AssertionError(
                                        "右边晚一步推出，不能让已展开的左边再也接不上它");
                            });
                    assertEquals(Rule.TRANSITIVE, chain.rule());
                    assertEquals(target, chain.via());
                    assertEquals(List.of(f(1), f(2)), chain.premises());
                    assertTrue(d.capped.isEmpty());
                }
            }
        }
    }

    @Test
    void aLateRightHandEdgeKeepsTheWholePrefixAndItsSpan() {
        Map<UUID, Axioms> ax = Map.of(
                P, new Axioms(true, false, false, false, false, false, null, null),
                Q, new Axioms(false, false, false, false, false, false, null, P),
                R, new Axioms(false, false, false, false, false, false, null, Q));
        List<TimedEdge> edges = List.of(
                tep(P, 1, 1, 2, 0L, 30L),
                tep(P, 2, 2, 3, 10L, 40L),
                tep(R, 3, 3, 4, 20L, 50L),
                tep(R, 4, 3, 5, 30L, 50L));
        Map<UUID, Span> spans = spansOf(edges);
        Derivation d = Derive.derive(edges, ax);
        Derived chain = d.facts.stream()
                .filter(x -> x.predicate().equals(P) && x.subject().equals(n(1))
                        && x.object().equals(n(4)))
                .findFirst().orElseThrow(() -> {
                    throw new AssertionError("右边跨两轮到达时，已派生的左边也要接得上");
                });
        assertEquals(List.of(f(1), f(2), f(3)), chain.premises());
        assertEquals(new Span(20L, 30L), Derive.validity(chain.premises(), spans));
        assertTrue(d.facts.stream().noneMatch(x -> x.predicate().equals(P)
                        && x.subject().equals(n(1)) && x.object().equals(n(5))),
                "前缀止于 30，右边始于 30，半开区间没有交集");
    }

    /** 同一个 triple 可以有几段时间都成立。第二段不能被第一段挡在传递之外。 */
    @Test
    void distinctIntervalsForOneTripleSurviveAxiomStepsInAnyOrder() {
        Map<UUID, Axioms> ax = Map.of(
                P, new Axioms(false, false, false, false, false, false, Q, null),
                Q, new Axioms(true, false, false, false, false, false, null, null));
        TimedEdge early = tep(P, 1, 1, 2, 0L, 10L);
        TimedEdge late = tep(P, 2, 1, 2, 20L, 30L);
        TimedEdge cb = tep(Q, 3, 3, 2, 20L, 30L);
        Map<UUID, Span> spans = Map.of(
                f(1), new Span(0L, 10L),
                f(2), new Span(20L, 30L),
                f(3), new Span(20L, 30L));
        record Row(UUID predicate, UUID subject, UUID object, Long from, Long to) {
        }
        Comparator<Row> cmp = Comparator.<Row, UUID>comparing(Row::predicate)
                .thenComparing(Row::subject)
                .thenComparing(Row::object)
                .thenComparing(Row::from, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Row::to, Comparator.nullsFirst(Comparator.naturalOrder()));
        java.util.function.Function<List<TimedEdge>, List<Row>> closure = edges -> {
            Derivation d = Derive.derive(edges, ax);
            List<Row> out = new ArrayList<>();
            for (Derived x : d.facts) {
                Span v = Derive.validity(x.premises(), spans);
                if (v == null) {
                    throw new AssertionError("派生区间应当可求");
                }
                out.add(new Row(x.predicate(), x.subject(), x.object(), v.from(), v.to()));
            }
            out.sort(cmp);
            return out;
        };
        List<Row> earlyFirst = closure.apply(List.of(early, late, cb));
        List<Row> lateFirst = closure.apply(List.of(late, early, cb));
        assertEquals(earlyFirst, lateFirst, "闭包不能随 fact 输入顺序改变");
        assertTrue(earlyFirst.contains(new Row(Q, n(2), n(1), 0L, 10L)),
                "第一段 B q A 也要保留");
        assertTrue(earlyFirst.contains(new Row(Q, n(2), n(1), 20L, 30L)),
                "第二段 B q A 要保留");
        assertTrue(earlyFirst.contains(new Row(Q, n(3), n(1), 20L, 30L)),
                "第二段 B q A 必须还能和 C q B 接成 C q A");
    }

    /** 断言的一段区间如果覆盖了派生出来的更窄区间，就不再留下第二条同 triple 的事实。 */
    @Test
    void anAssertedIntervalCoversANarrowerDerivation() {
        List<TimedEdge> edges = List.of(
                te(1, 1, 2, 10L, 20L),
                te(2, 2, 3, 10L, 20L),
                te(3, 1, 3, 0L, 100L));
        Derivation d = Derive.derive(edges, transitive());
        assertTrue(d.facts.isEmpty(), "断言 [0,100) 已覆盖推出来的 [10,20)，不该再派生一份");
    }

    /** 无时间断言表示一直成立，也覆盖有日期的同 triple 派生。 */
    @Test
    void anUndatedAssertionCoversADatedDerivation() {
        List<TimedEdge> edges = List.of(
                te(1, 1, 2, 10L, 20L),
                te(2, 2, 3, 10L, 20L),
                te(3, 1, 3, null, null));
        Derivation d = Derive.derive(edges, transitive());
        assertTrue(d.facts.isEmpty(), "无时间断言覆盖 [10,20)，不该推出一条说得更少的同 triple 事实");
    }

    /** 两条路径分别推出 [0,100) 与 [10,20)：宽的保留，窄的丢掉；输入反序也一样。 */
    @Test
    void aWiderRouteSuppressesANarrowerDerivation() {
        List<TimedEdge> edges = List.of(
                te(1, 1, 2, 0L, 100L),
                te(2, 2, 4, 0L, 100L),
                te(3, 1, 3, 10L, 20L),
                te(4, 3, 4, 10L, 20L));
        Map<UUID, Span> byFact = spansOf(edges);
        java.util.function.Function<List<TimedEdge>, List<Span>> spans = input -> {
            Derivation d = Derive.derive(input, transitive());
            List<Span> out = new ArrayList<>();
            for (Derived x : d.facts) {
                if (x.subject().equals(n(1)) && x.object().equals(n(4))) {
                    Span v = Derive.validity(x.premises(), byFact);
                    if (v == null) {
                        throw new AssertionError("派生区间应当可求");
                    }
                    out.add(v);
                }
            }
            out.sort(Comparator.comparing(Span::from, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Span::to, Comparator.nullsFirst(Comparator.naturalOrder())));
            return out;
        };
        Span broad = new Span(0L, 100L);
        assertEquals(List.of(broad), spans.apply(edges), "只留下覆盖更宽的 [0,100)");
        assertEquals(List.of(broad), spans.apply(List.of(edges.get(3), edges.get(2), edges.get(1),
                edges.get(0))), "输入反序不能把被覆盖的窄区间又放回来");
    }

    private static List<TimedEdge> competingRoutes(UUID p, UUID q, UUID[] nodes, Span narrow,
                                                  Span broad) {
        List<TimedEdge> edges = new ArrayList<>();
        edges.add(new TimedEdge(new Edge(u128(0, 101), p, nodes[0], nodes[3]),
                narrow.from(), narrow.to()));
        edges.add(new TimedEdge(new Edge(u128(0, 102), q, nodes[0], nodes[1]),
                broad.from(), broad.to()));
        edges.add(new TimedEdge(new Edge(u128(0, 103), q, nodes[1], nodes[2]),
                broad.from(), broad.to()));
        edges.add(new TimedEdge(new Edge(u128(0, 104), q, nodes[2], nodes[3]),
                broad.from(), broad.to()));
        return edges;
    }

    private static Map<UUID, Axioms> competingAxioms(UUID p, UUID q) {
        return Map.of(
                p, new Axioms(false, false, false, false, false, false, null, q),
                q, new Axioms(true, false, false, false, false, false, null, null));
    }

    /** #1034 / #1040 留下的 follow-up：先输出子属性的窄区间，再输出传递的宽区间。 */
    @Test
    void aLaterWiderDerivationReplacesTheNarrowOutputWithItsWholeProof() {
        UUID p = u128(0, 1);
        UUID q = u128(0, 2);
        UUID[] nodes = {u128(0, 10), u128(0, 20), u128(0, 30), u128(0, 40)};
        List<TimedEdge> edges = competingRoutes(p, q, nodes, new Span(10L, 20L),
                new Span(0L, 100L));
        Map<UUID, Axioms> axioms = competingAxioms(p, q);
        Derivation result = Derive.derive(edges, axioms);
        Map<UUID, Span> spans = spansOf(edges);
        List<Derived> outputs = result.facts.stream()
                .filter(fact -> fact.predicate().equals(q) && fact.subject().equals(nodes[0])
                        && fact.object().equals(nodes[3]))
                .toList();
        List<Span> outSpans = new ArrayList<>();
        for (Derived fact : outputs) {
            outSpans.add(Derive.validity(fact.premises(), spans));
        }
        assertEquals(List.of(new Span(0L, 100L)), outSpans, "后到的宽区间应移除先到的窄区间输出");
        assertEquals(List.of(u128(0, 102), u128(0, 103), u128(0, 104)),
                outputs.get(0).premises());
        assertEquals(Rule.TRANSITIVE, outputs.get(0).rule());
        assertEquals(q, outputs.get(0).via());
        assertTrue(result.capped.isEmpty());
    }

    @Test
    void finalIntervalsDoNotDependOnInputOrUuidOrder() {
        for (UUID[] pq : new UUID[][]{{u128(0, 1), u128(0, 2)}, {u128(0, 2), u128(0, 1)}}) {
            UUID p = pq[0];
            UUID q = pq[1];
            for (long[] nodeIds : new long[][]{{10, 20, 30, 40}, {40, 30, 20, 10},
                    {20, 40, 10, 30}}) {
                UUID[] nodes = new UUID[4];
                for (int i = 0; i < 4; i++) {
                    nodes[i] = u128(0, nodeIds[i]);
                }
                List<TimedEdge> edges = competingRoutes(p, q, nodes, new Span(10L, 20L),
                        new Span(0L, 100L));
                Map<UUID, Axioms> axioms = competingAxioms(p, q);
                Map<UUID, Span> spans = spansOf(edges);
                record Row(UUID predicate, UUID subject, UUID object, Long from, Long to) {
                }
                Comparator<Row> cmp = Comparator.<Row, UUID>comparing(Row::predicate)
                        .thenComparing(Row::subject).thenComparing(Row::object)
                        .thenComparing(r -> r.from(), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(r -> r.to(), Comparator.nullsFirst(Comparator.naturalOrder()));
                List<Row> expected = new ArrayList<>(List.of(
                        new Row(q, nodes[0], nodes[2], 0L, 100L),
                        new Row(q, nodes[0], nodes[3], 0L, 100L),
                        new Row(q, nodes[1], nodes[3], 0L, 100L)));
                expected.sort(cmp);
                // 四条事实的全部 24 种排列；交换谓词排序时宽区间可以先到
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        for (int k = 0; k < 4; k++) {
                            if (i == j || i == k || j == k) {
                                continue;
                            }
                            int last = -1;
                            for (int x = 0; x < 4; x++) {
                                if (x != i && x != j && x != k) {
                                    last = x;
                                }
                            }
                            Derivation result = Derive.derive(
                                    List.of(edges.get(i), edges.get(j), edges.get(k),
                                            edges.get(last)), axioms);
                            List<Row> actual = new ArrayList<>();
                            for (Derived fact : result.facts) {
                                Span v = Derive.validity(fact.premises(), spans);
                                if (v == null) {
                                    throw new AssertionError("派生区间应当可求");
                                }
                                actual.add(new Row(fact.predicate(), fact.subject(), fact.object(),
                                        v.from(), v.to()));
                            }
                            actual.sort(cmp);
                            assertEquals(expected, actual);
                            Derived wide = result.facts.stream()
                                    .filter(fact -> fact.object().equals(nodes[3])
                                            && fact.subject().equals(nodes[0]))
                                    .findFirst().orElseThrow();
                            assertEquals(List.of(u128(0, 102), u128(0, 103), u128(0, 104)),
                                    wide.premises());
                            assertEquals(new RuleSide(q, Rule.TRANSITIVE),
                                    new RuleSide(wide.via(), wide.rule()));
                        }
                    }
                }
            }
        }
    }

    @Test
    void finalOutputsKeepIncomparableSpansAndOneEqualSpan() {
        Span narrow = new Span(10L, 20L);
        record Case(Span narrow, Span broad, List<Span> expected) {
        }
        List<Case> cases = List.of(
                new Case(narrow, new Span(null, 100L), List.of(new Span(null, 100L))),
                new Case(narrow, new Span(0L, null), List.of(new Span(0L, null))),
                new Case(narrow, new Span(null, null), List.of(new Span(null, null))),
                new Case(new Span(null, 20L), new Span(null, null), List.of(new Span(null, null))),
                new Case(new Span(10L, null), new Span(null, null), List.of(new Span(null, null))),
                new Case(narrow, narrow, List.of(narrow)),
                new Case(new Span(null, null), new Span(null, null),
                        List.of(new Span(null, null))),
                new Case(narrow, new Span(15L, 30L),
                        List.of(narrow, new Span(15L, 30L))),
                new Case(narrow, new Span(20L, 30L),
                        List.of(narrow, new Span(20L, 30L))),
                new Case(narrow, new Span(30L, 40L),
                        List.of(narrow, new Span(30L, 40L))),
                new Case(new Span(null, 20L), new Span(10L, null),
                        List.of(new Span(null, 20L), new Span(10L, null))));
        Comparator<Span> cmp = Comparator.comparing(Span::from,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Span::to, Comparator.nullsFirst(Comparator.naturalOrder()));
        for (Case c : cases) {
            List<TimedEdge> edges = competingRoutes(P, Q,
                    new UUID[]{n(1), n(2), n(3), n(4)}, c.narrow(), c.broad());
            Derivation result = Derive.derive(edges, competingAxioms(P, Q));
            Map<UUID, Span> spans = spansOf(edges);
            List<Span> actual = new ArrayList<>();
            for (Derived fact : result.facts) {
                if (fact.predicate().equals(Q) && fact.subject().equals(n(1))
                        && fact.object().equals(n(4))) {
                    Span v = Derive.validity(fact.premises(), spans);
                    if (v == null) {
                        throw new AssertionError("派生区间应当可求");
                    }
                    actual.add(v);
                }
            }
            actual.sort(cmp);
            List<Span> expected = new ArrayList<>(c.expected());
            expected.sort(cmp);
            assertEquals(expected, actual, "区间 " + c.narrow() + " 与 " + c.broad());
        }
    }

    @Test
    void aUnionOfOutputsDoesNotCoverAnotherOutput() {
        UUID target = n(100);
        List<Span> intervals = List.of(
                new Span(0L, 60L), new Span(40L, 100L), new Span(20L, 80L));
        UUID[] sources = {P, Q, R};
        List<TimedEdge> edges = new ArrayList<>();
        Map<UUID, Axioms> axioms = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            UUID source = sources[i];
            edges.add(tep(source, i, 1, 2, intervals.get(i).from(), intervals.get(i).to()));
            axioms.put(source, new Axioms(false, false, false, false, false, false, null, target));
        }
        Derivation result = Derive.derive(edges, axioms);
        Map<UUID, Span> spans = spansOf(edges);
        List<Span> actual = new ArrayList<>();
        for (Derived fact : result.facts) {
            assertEquals(target, fact.predicate());
            assertEquals(n(1), fact.subject());
            assertEquals(n(2), fact.object());
            assertEquals(1, fact.premises().size());
            UUID source = edges.stream()
                    .filter(edge -> edge.edge().fact().equals(fact.premises().get(0)))
                    .findFirst().orElseThrow().edge().predicate();
            assertEquals(new RuleSide(source, Rule.SUB_PROPERTY),
                    new RuleSide(fact.via(), fact.rule()));
            actual.add(Derive.validity(fact.premises(), spans));
        }
        actual.sort(Comparator.comparing(Span::from, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Span::to, Comparator.nullsFirst(Comparator.naturalOrder())));
        List<Span> expected = new ArrayList<>(intervals);
        expected.sort(Comparator.comparing(Span::from,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        assertEquals(expected, actual, "只能由单个区间覆盖，不能拿两段的并集替代");
    }

    @Test
    void finalCleanupKeepsOneEqualOutputAndItsOriginalProof() {
        Derived first = new Derived(Q, P, n(1), n(2), Rule.SUB_PROPERTY, List.of(f(1)));
        Derived second = new Derived(Q, Q, n(1), n(2), Rule.TRANSITIVE,
                List.of(f(2), f(3)));
        Derived otherTriple = new Derived(R, P, n(1), n(2), Rule.SUB_PROPERTY, List.of(f(1)));
        Map<UUID, Span> spans = Map.of(
                f(1), new Span(10L, 20L),
                f(2), new Span(0L, 20L),
                f(3), new Span(10L, 30L));
        List<Derived> outputs = new ArrayList<>(List.of(first, otherTriple, second));
        Derive.retainWidestOutputs(outputs, spans);
        assertEquals(List.of(first, otherTriple), outputs);
    }

    @Test
    void finalCleanupDoesNotChangeOutputsWithAnEmptyValidity() {
        // 单跳规则原本允许此输入返回输出，由消费方用 validity 拒绝；清理不能改成抛错。
        for (long to : new long[]{10, 20}) {
            List<TimedEdge> edges = List.of(tep(P, 1, 1, 2, 20L, to));
            Derivation result = Derive.derive(edges, subProperty());
            assertEquals(1, result.facts.size());
            assertEquals(List.of(f(1)), result.facts.get(0).premises());
            assertNull(Derive.validity(result.facts.get(0).premises(), spansOf(edges)));
        }
    }

    @Test
    void aNarrowerShorterProofStillFinishesWithinTheDepthLimit() {
        // 捷径窄成 [10,20)：较窄的捷径证明也要在 12 条前提内走完
        List<TimedEdge> edges = new ArrayList<>(edgesWithShortcuts());
        TimedEdge shortcut = null;
        for (int i = 0; i < edges.size(); i++) {
            if (byteAt(edges.get(i).edge().subject()) == 21
                    && byteAt(edges.get(i).edge().object()) == 16) {
                shortcut = new TimedEdge(edges.get(i).edge(), 10L, 20L);
                edges.set(i, shortcut);
                break;
            }
        }
        UUID shortcutFact = shortcut.edge().fact();
        Derivation result = Derive.derive(edges, transitive());
        Map<UUID, Span> spans = spansOf(edges);
        Derived chain = result.facts.stream()
                .filter(fact -> fact.subject().equals(n(11)) && fact.object().equals(n(20)))
                .findFirst().orElseThrow();
        assertEquals(AxiomChecks.MAX_DEPTH, chain.premises().size());
        assertTrue(chain.premises().contains(shortcutFact));
        assertEquals(new Span(10L, 20L), Derive.validity(chain.premises(), spans));
        List<Derived> prefix = result.facts.stream()
                .filter(fact -> fact.subject().equals(n(11)) && fact.object().equals(n(16)))
                .toList();
        assertEquals(1, prefix.size(), "窄的中间证明参与传播，但最终只展示宽的前缀");
        assertEquals(new Span(null, null), Derive.validity(prefix.get(0).premises(), spans));
    }

    /** 区间照旧取交集，跨谓词也一样。 */
    @Test
    void theInverseCarriesTheSameSpan() {
        Derivation d = Derive.derive(List.of(tep(P, 1, 1, 2, 10L, 20L)), inversePair());
        assertEquals(1, d.facts.size());
        Span v = Derive.validity(d.facts.get(0).premises(),
                Map.of(f(1), new Span(10L, 20L)));
        assertEquals(new Span(10L, 20L), v, "逆不改变有效期");
    }

    /** 自己是自己的逆 = 对称，但不该推出自环。 */
    @Test
    void aPredicateThatIsItsOwnInverseStillRefusesSelfLoops() {
        Map<UUID, Axioms> ax = Map.of(P,
                new Axioms(false, false, false, false, false, false, P, null));
        Derivation d = Derive.derive(List.of(ep(P, 1, 1, 1)), ax);
        assertTrue(d.facts.isEmpty(), "`A p A` 的逆还是 `A p A`——自环不推");
    }

    // ---------- 矛盾（0017） ----------

    /** 指定谓词的一条带区间的边 */
    private static TimedEdge et(UUID pred, long fact, long s, long o, Long from, Long to) {
        return new TimedEdge(new Edge(f(fact), pred, n(s), n(o)), from, to);
    }

    /** ceo_of ⊑ works_at，works_at functional：派生撞上断言，指名道姓。 */
    @Test
    void aDerivationThatBreaksFunctionalNamesTheAssertionItHit() {
        Map<UUID, Axioms> ax = Map.of(
                P, new Axioms(false, false, false, false, false, false, null, Q),
                Q, new Axioms(false, false, false, false, true, false, null, null));
        List<TimedEdge> edges = List.of(ep(P, 1, 1, 2), ep(Q, 2, 1, 3));
        Derivation d = Derive.derive(edges, ax);
        assertEquals(1, d.facts.size());
        Contradictions c = Derive.contradictions(d, edges, ax, spansOf(edges));
        assertEquals(List.of(new Derive.Clash(0, Kind.FUNCTIONAL, f(2))), c.withAssertions);
        assertTrue(c.betweenDerivations.isEmpty());
        assertEquals(Set.of(0), c.blocked());
    }

    /** 区间不交就不是矛盾：前任与继任。 */
    @Test
    void disjointIntervalsAreSuccessionAndStaySilent() {
        Map<UUID, Axioms> ax = Map.of(
                P, new Axioms(false, false, false, false, false, false, null, Q),
                Q, new Axioms(false, false, false, false, true, false, null, null));
        List<TimedEdge> edges = List.of(
                et(P, 1, 1, 2, 10L, 20L),
                et(Q, 2, 1, 3, 30L, null));
        Derivation d = Derive.derive(edges, ax);
        Contradictions c = Derive.contradictions(d, edges, ax, spansOf(edges));
        assertTrue(c.withAssertions.isEmpty(), c::toString);
    }

    /** 对称与非对称：A p B 对称推出 B p A，而 p 又声明 asymmetric——每条断言都撞上自己的镜像。 */
    @Test
    void aSymmetricDerivationHitsTheAsymmetricAssertion() {
        Map<UUID, Axioms> ax = Map.of(P,
                new Axioms(false, true, true, false, false, false, null, null));
        List<TimedEdge> edges = List.of(ep(P, 1, 1, 2));
        Derivation d = Derive.derive(edges, ax);
        Contradictions c = Derive.contradictions(d, edges, ax, spansOf(edges));
        assertEquals(1, c.withAssertions.size());
        assertEquals(Kind.ASYMMETRY, c.withAssertions.get(0).axiom());
        assertEquals(f(1), c.withAssertions.get(0).against());
    }

    /** 两条派生互撞时按规则对聚合，而且都不落地。 */
    @Test
    void derivationsThatDisagreeAreGroupedByTheRulesThatMadeThem() {
        Map<UUID, Axioms> ax = Map.of(
                P, new Axioms(false, false, false, false, false, false, null, Q),
                Q, new Axioms(false, false, false, false, true, false, null, null));
        // 1 ceo_of 2 与 1 ceo_of 3：两条 works_at 由同一条规则推出，互相排斥
        List<TimedEdge> edges = List.of(ep(P, 1, 1, 2), ep(P, 2, 1, 3), ep(P, 3, 4, 5));
        Derivation d = Derive.derive(edges, ax);
        assertEquals(3, d.facts.size());
        Contradictions c = Derive.contradictions(d, edges, ax, spansOf(edges));
        assertTrue(c.withAssertions.isEmpty());
        assertEquals(1, c.betweenDerivations.size());
        Derive.RuleClash rc = c.betweenDerivations.get(0);
        assertEquals(new RuleSide(P, Rule.SUB_PROPERTY), rc.a());
        assertEquals(new RuleSide(P, Rule.SUB_PROPERTY), rc.b());
        assertEquals(Kind.FUNCTIONAL, rc.axiom());
        assertEquals(1, rc.pairs().size());
        // 第三条（4 works_at 5）没跟谁撞，照常落地
        assertEquals(2, c.blocked().size());
        assertFalse(c.blocked().contains(2));
    }

    /** 谓词上没有公理就没有矛盾可言。 */
    @Test
    void aPredicateWithoutAxiomsCannotContradict() {
        Map<UUID, Axioms> ax = Map.of(P,
                new Axioms(false, false, false, false, false, false, null, Q));
        List<TimedEdge> edges = List.of(ep(P, 1, 1, 2), ep(Q, 2, 1, 3));
        Derivation d = Derive.derive(edges, ax);
        Contradictions c = Derive.contradictions(d, edges, ax, spansOf(edges));
        assertTrue(c.withAssertions.isEmpty());
        assertTrue(c.betweenDerivations.isEmpty());
    }
}
