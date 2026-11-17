package tech.seexun.jknow.reason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.reason.Derive.Span;
import tech.seexun.jknow.reason.Derive.TimedEdge;

/**
 * 移植自 utopia-reason/src/lib.rs 内联 mod tests（24 例，含两个随机图对拍大测试）——
 * 「拿公理量边」的纯逻辑全部：规范旋转（#618）、每环一次、深度/环数/步数三上限（#642）、
 * 区间同时成立（#634/#636）、连通块一组报一处（#624）、顺序无关。
 *
 * <p><b>RNG 等价点说明</b>：Rust 侧对拍用自实现 xorshift64*（种子 0x0636_C0FF_EE00_0001 /
 * 0x0634_BEEF_0000_0002 / 0x0642），不为这个引依赖；Java 侧同种子策略改用
 * {@link java.util.Random}（LCG）——<b>生成的图序列不同，但对拍的性质完全相同</b>：
 * 自实现（带剪枝/扫描优化）与暴力枚举在同一张图上逐个比对，随机图参数（节点数、
 * 边数上限、区间端点 0..=60 十格与 1/4 无界率）与轮数（6000/4000）逐字照搬。
 * 洗牌用 Fisher–Yates 同构写法（Rust {@code (1..len).rev()} 双向同界）。
 */
class AxiomChecksTests {

    // ---- 造数与断言的简写（lib.rs:550-619） ----

    /** 节点用小整数编出稳定的 uuid，读断言时看得出谁是谁（Uuid::from_bytes）。 */
    private static UUID n(int i) {
        return fromByte((byte) i);
    }

    /**
     * 与节点 id 分段：节点用低位，事实用高位。{@code u8} 装得下 0..=255，
     * 而测试里造过 30 条边的链——用 {@code 200 + i} 会溢出（Rust 注释原话）。
     */
    private static UUID f(int i) {
        return fromByte((byte) (i ^ 0xF0));
    }

    private static UUID fromByte(byte b) {
        byte[] arr = new byte[16];
        Arrays.fill(arr, b);
        ByteBuffer buf = ByteBuffer.wrap(arr);
        return new UUID(buf.getLong(), buf.getLong());
    }

    private static Edge e(int fact, int s, int o) {
        return new Edge(f(fact), n(99), n(s), n(o));
    }

    private static Map<UUID, Axioms> with(Axioms ax) {
        return Map.of(n(99), ax);
    }

    /** 不带时间地查：两端都无界，任意两条都同时成立，检查只剩形状（lib.rs:571-581）。 */
    private static List<Violation> check(List<Edge> edges, Map<UUID, Axioms> axioms) {
        List<TimedEdge> timed = edges.stream()
                .map(edge -> new TimedEdge(edge, null, null)).toList();
        return AxiomChecks.check(timed, axioms);
    }

    /** 带区间的边，[from, to)（lib.rs:583-589）。 */
    private static TimedEdge at(int fact, int s, int o, Long from, Long to) {
        return new TimedEdge(e(fact, s, o), from, to);
    }

    /** 输入的全排列。顺序是这一类 bug 的全部来源，所以每种顺序都要跑到（lib.rs:591-605）。 */
    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.size() <= 1) {
            return List.of(new ArrayList<>(items));
        }
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                tail.add(0, head);
                out.add(tail);
            }
        }
        return out;
    }

    /** 一次检查的结果，排成与顺序无关的形状，好拿来比（lib.rs:607-614）。 */
    record Shape(String kind, UUID left, UUID right, List<UUID> path) {

        static final Comparator<Shape> ORDER = Comparator.comparing(Shape::kind)
                .thenComparing(s -> s.left(), UuidLexical::compare)
                .thenComparing(s -> s.path(), AxiomChecksTests::pathsCompare);

        static List<Shape> of(List<Violation> v) {
            List<Shape> out = v.stream()
                    .map(x -> new Shape(x.kind().asString(), x.left(), x.right(), x.path()))
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            out.sort(Shape.ORDER);
            return out;
        }
    }

    /** Vec&lt;Uuid&gt; 的字典序（Rust Vec Ord：逐元素比，短的前）。 */
    private static int pathsCompare(List<UUID> a, List<UUID> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int d = UuidLexical.compare(a.get(i), b.get(i));
            if (d != 0) {
                return d;
            }
        }
        return Integer.compare(a.size(), b.size());
    }

    private static List<Kind> kinds(List<Violation> v) {
        List<Kind> k = v.stream().map(Violation::kind).collect(ArrayList::new, ArrayList::add,
                ArrayList::addAll);
        k.sort(Comparator.comparing(Kind::asString));
        return k;
    }

    // ---- 24 例 ----

    /**
     * <b>同一个环，每次都是同一行</b>（#618）。环的 left/right 从前取这一次遍历的首尾，
     * 而起点来自 HashMap 的迭代顺序——同一份输入跑两次可以给出不同的旋转，而
     * axiom_violations 按 (kind, left_fact, right_fact) 唯一，换个旋转就是换一行，
     * 人裁过的那一行于是被绕开。跑一百次而不是一次：顺序是随机的，一次跑不出问题来。
     */
    @Test
    void aCycleIsReportedTheSameWayEveryTime() {
        List<Edge> edges = List.of(e(1, 1, 2), e(2, 2, 3), e(3, 3, 1));
        Map<UUID, Axioms> ax = with(new Axioms(true, false, false, false, false, false, null,
                null));
        Set<String> shapes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            List<Violation> v = check(edges, ax).stream()
                    .filter(x -> x.kind() == Kind.CYCLE).toList();
            assertEquals(1, v.size(), "一个环只报一次");
            shapes.add(v.get(0).left() + "|" + v.get(0).right() + "|" + v.get(0).path());
        }
        assertEquals(1, shapes.size(), "同一个环给出了不止一种形状: " + shapes);
        // 规范形状是从最小的事实起头，路径仍然是一圈
        Violation cyc = check(edges, ax).stream().filter(x -> x.kind() == Kind.CYCLE).findFirst()
                .orElseThrow();
        List<UUID> path = cyc.path();
        assertEquals(path.stream().min(UuidLexical::compare).orElseThrow(), cyc.left());
        assertEquals(path.get(0), cyc.left());
        assertEquals(path.getLast(), cyc.right());
        assertEquals(3, path.size());
    }

    /**
     * <b>共用首尾两条边的两个环是两个环</b>（#641）。A→B 起头、X→A 收尾，中间一条走 C、
     * 一条走 D：两个环的 left 都是 A→B、right 都是 X→A。从前库里按这两列定键，一个
     * 覆盖另一个；这里钉的是检查器给出的就是两个、路径不同——落库那边按整条 path 定键。
     */
    @Test
    void twoCyclesSharingTheirEndsAreTwoCycles() {
        List<Edge> edges = List.of(e(1, 1, 2), e(2, 2, 3), e(3, 2, 4), e(4, 3, 5), e(5, 4, 5),
                e(6, 5, 1));
        List<Violation> v = check(edges, with(new Axioms(true, false, false, false, false,
                false, null, null))).stream().filter(x -> x.kind() == Kind.CYCLE).toList();
        Set<List<UUID>> paths = new HashSet<>(v.stream().map(Violation::path).toList());
        assertEquals(Set.of(List.of(f(1), f(2), f(4), f(6)), List.of(f(1), f(3), f(5), f(6))),
                paths);
        for (Violation c : v) {
            assertEquals(f(1), c.left());
            assertEquals(f(6), c.right());
        }
    }

    /**
     * <b>层级里的一条回边，不让找环走遍整个层级</b>（#642）。宽 4、14 层的层级 DAG，
     * 第 5 层一条边回到第 0 层：环只有 256 个（第 0 层那个点经四层每层四选一到第 5 层
     * 那个点），全在前六层；而从前从每个节点出发把深度 12 以内的路径都走一遍，光一个
     * 起点就有 4^12 条。只在强连通分量里找，那几万条路一步都不走——这里断言
     * 的是结果完整、没有撞上步数上限：若分量剪枝失效，这张图必然撞上。
     */
    @Test
    void aBackEdgeInAHierarchyDoesNotWalkTheHierarchy() {
        int width = 4;
        int layers = 14;
        List<TimedEdge> edges = new ArrayList<>();
        long fact = 0;
        for (int l = 0; l < layers - 1; l++) {
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < width; y++) {
                    fact++;
                    edges.add(new TimedEdge(
                            new Edge(new UUID(1, fact), n(99),
                                    new UUID(0, l * width + x), new UUID(0, (l + 1) * width + y)),
                            null, null));
                }
            }
        }
        fact++;
        edges.add(new TimedEdge(
                new Edge(new UUID(1, fact), n(99), new UUID(0, 5 * width), new UUID(0, 0)),
                null, null));
        Checked checked = AxiomChecks.checkAll(edges,
                with(new Axioms(true, false, false, false, false, false, null, null)));
        assertTrue(checked.cyclesCapped().isEmpty(), "不该撞上上限");
        long cycles = checked.violations().stream().filter(v -> v.kind() == Kind.CYCLE).count();
        assertEquals((int) Math.pow(4, 4), (int) cycles,
                "第 0 层那个点经四层、每层四选一到第 5 层那个点");
    }

    /**
     * <b>稠密的分量里撞上上限就停，并且说出来</b>（#642）；停下时报出的那一批环只取决于
     * 数据，与读进来的顺序无关——否则同一份数据每跑一遍 Review 里就换一批环。
     */
    @Test
    void aDenseLoopStopsAtTheCapAndSaysSo() {
        // 12 个节点两两互指：深度 12 以内的简单环是天文数字
        List<TimedEdge> edges = new ArrayList<>();
        int fact = 0;
        for (int s = 1; s <= 12; s++) {
            for (int o = 1; o <= 12; o++) {
                if (s != o) {
                    fact++;
                    edges.add(at(fact, s, o, null, null));
                }
            }
        }
        Map<UUID, Axioms> ax = with(new Axioms(true, false, false, false, false, false, null,
                null));
        Checked first = AxiomChecks.checkAll(edges, ax);
        assertEquals(List.of(n(99)), first.cyclesCapped());
        long cycles = first.violations().stream().filter(v -> v.kind() == Kind.CYCLE).count();
        assertTrue(cycles <= AxiomChecks.MAX_CYCLES_PER_PREDICATE, "报出 " + cycles + " 个");
        assertTrue(cycles > 0, "撞上上限之前找到的照报");

        Random rng = new Random(0x0642L);
        for (int i = 0; i < 5; i++) {
            Checked again = AxiomChecks.checkAll(shuffled(rng, edges), ax);
            assertEquals(first.cyclesCapped(), again.cyclesCapped());
            assertEquals(Shape.of(first.violations()), Shape.of(again.violations()),
                    "撞上上限时报出的那一批环随输入顺序变了");
        }
    }

    /**
     * <b>没声明公理的谓词一条都不查</b>。这是整套检查的地基：没有依据就不报矛盾。
     * 反过来说也成立——一个没装本体包的库跑出来是零，那是实情不是故障。
     */
    @Test
    void aPredicateThatDeclaresNothingIsNeverChecked() {
        List<Edge> edges = List.of(e(1, 1, 1), e(2, 1, 2), e(3, 2, 1));
        assertTrue(check(edges, with(Axioms.empty())).isEmpty());
        // 连 axioms 里都没有这个谓词时同样不查（本体里没有这一行）
        assertTrue(check(edges, Map.of()).isEmpty());
    }

    @Test
    void aSelfLoopNeedsIrreflexiveToBeAProblem() {
        List<Edge> edges = List.of(e(1, 1, 1));
        assertTrue(check(edges, with(new Axioms(true, false, false, false, false, false, null,
                null))).isEmpty());
        List<Violation> v = check(edges, with(new Axioms(false, false, false, true, false, false,
                null, null)));
        assertEquals(List.of(Kind.SELF_LOOP), kinds(v));
        // 只有一条事实：两列填同一个 id
        assertEquals(v.get(0).left(), v.get(0).right());
    }

    /**
     * 反对称那一档<b>不重复报自环</b>。A p A 同时满足「有反向边」的字面意思，
     * 不挡掉的话一条自环会在两档里各报一次，人看到两条要处理的东西而其实是一件。
     */
    @Test
    void aSelfLoopIsReportedOnceNotTwice() {
        List<Edge> edges = List.of(e(1, 1, 1));
        List<Violation> v = check(edges, with(new Axioms(false, false, true, true, false, false,
                null, null)));
        assertEquals(List.of(Kind.SELF_LOOP), kinds(v));
    }

    @Test
    void aPairPointingBothWaysNeedsAsymmetric() {
        List<Edge> edges = List.of(e(1, 1, 2), e(2, 2, 1));
        assertTrue(check(edges, with(new Axioms(true, false, false, false, false, false, null,
                null))).stream().allMatch(v -> v.kind() != Kind.ASYMMETRY));
        List<Violation> v = check(edges, with(new Axioms(false, false, true, false, false, false,
                null, null)));
        assertEquals(List.of(Kind.ASYMMETRY), kinds(v));
        assertEquals(f(1), v.get(0).left());
        assertEquals(f(2), v.get(0).right());
    }

    /** 长环要报出<b>路径</b>，而不只是「首尾矛盾」——人要顺着看一遍才知道撤哪一条。 */
    @Test
    void aLongCycleReportsTheWholePath() {
        List<Edge> edges = List.of(e(1, 1, 2), e(2, 2, 3), e(3, 3, 4), e(4, 4, 1));
        List<Violation> v = check(edges, with(new Axioms(true, false, false, false, false,
                false, null, null)));
        assertEquals(1, v.size(), "一个环只报一次");
        assertEquals(4, v.get(0).path().size(), "四条边都该在路径里");
    }

    /**
     * <b>同一个环从不同节点出发会被走到 n 次</b>。去重是这个函数存在的一半理由：
     * 报四遍就是让人把同一件事看四遍。
     */
    @Test
    void oneCycleIsOneFindingHoweverManyWaysIn() {
        List<Edge> edges = List.of(e(1, 1, 2), e(2, 2, 3), e(3, 3, 1));
        assertEquals(1, check(edges, with(new Axioms(true, false, false, false, false, false,
                null, null))).size());
    }

    /** 两个互不相干的环各报一次。 */
    @Test
    void separateCyclesStaySeparate() {
        List<Edge> edges = List.of(e(1, 1, 2), e(2, 2, 1), e(3, 5, 6), e(4, 6, 5));
        assertEquals(2, check(edges, with(new Axioms(true, false, false, false, false, false,
                null, null))).size());
    }

    /**
     * <b>深度上限挡得住不收敛</b>。0002 在真实语料上量到 part_of 闭包深度到 10
     * 仍在振荡；没有上限，一条长链加一个环就能让求值不终止。这里造一条比上限更长的
     * 链再闭合——它不该让检查挂住，报不报得出那个环是次要的，<b>不挂住是首要的</b>。
     */
    @Test
    void aChainLongerThanTheLimitStillTerminates() {
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            edges.add(e(i, i, i + 1));
        }
        edges.add(e(60, 30, 0));
        List<Violation> v = check(edges, with(new Axioms(true, false, false, false, false,
                false, null, null)));
        // 断言的是"跑完了"，长度不作要求
        assertTrue(v.size() <= 1);
    }

    @Test
    void functionalAndItsInverseAreTwoDirectionsOfOneCheck() {
        // 同一个主语指了两个宾语
        List<Edge> out = List.of(e(1, 1, 2), e(2, 1, 3));
        assertEquals(List.of(Kind.FUNCTIONAL),
                kinds(check(out, with(new Axioms(false, false, false, false, true, false, null,
                        null)))));
        assertTrue(check(out, with(new Axioms(false, false, false, false, false, true, null,
                null))).isEmpty());

        // 同一个宾语被两个主语指：记成它自己的种类，不冒充 functional（#634）
        List<Edge> inn = List.of(e(1, 2, 1), e(2, 3, 1));
        assertEquals(List.of(Kind.INVERSE_FUNCTIONAL),
                kinds(check(inn, with(new Axioms(false, false, false, false, false, true, null,
                        null)))));
        assertTrue(check(inn, with(new Axioms(false, false, false, false, true, false, null,
                null))).isEmpty());
    }

    /**
     * 同一个主语指五个宾语只报一处。报十处（两两组合）是把同一件事说十遍；
     * 那一处带着全部五条，人撤哪条都有按钮。
     */
    @Test
    void oneFindingPerConflictingKeyNotOnePerPair() {
        List<Edge> edges = List.of(e(1, 1, 2), e(2, 1, 3), e(3, 1, 4), e(4, 1, 5), e(5, 1, 6));
        List<Violation> v = check(edges, with(new Axioms(false, false, false, false, true,
                false, null, null)));
        assertEquals(1, v.size());
        assertEquals(List.of(f(1), f(2), f(3), f(4), f(5)), v.get(0).path());
        assertEquals(f(1), v.get(0).left());
        assertEquals(f(5), v.get(0).right());
    }

    /**
     * <b>一处冲突，不管以什么顺序读进来，都是同一行</b>（#624）。从前 left/right 取
     * 遇到的头两条：三个值的六种顺序给出六种键，反对称两种顺序给出两种键——而
     * axiom_violations 按键唯一，换个顺序，人裁过的那行就被绕开了。
     * 跑全排列而不是跑一百次：顺序是这里唯一的变量，每一种都要到。
     */
    @Test
    void theSameClashIsTheSameRowWhateverTheOrder() {
        Map<UUID, Axioms> functional = with(new Axioms(false, false, false, false, true, false,
                null, null));
        Map<UUID, Axioms> inverse = with(new Axioms(false, false, false, false, false, true,
                null, null));
        Map<UUID, Axioms> asymmetric = with(new Axioms(false, false, true, false, false, false,
                null, null));
        List<Map.Entry<List<Edge>, Map<UUID, Axioms>>> cases = List.of(
                Map.entry(List.of(e(1, 1, 2), e(2, 1, 3), e(3, 1, 4)), functional),
                Map.entry(List.of(e(1, 2, 1), e(2, 3, 1), e(3, 4, 1)), inverse),
                Map.entry(List.of(e(1, 1, 2), e(2, 2, 1), e(3, 1, 2)), asymmetric));
        for (Map.Entry<List<Edge>, Map<UUID, Axioms>> c : cases) {
            Set<List<Shape>> shapes = new HashSet<>();
            for (List<Edge> p : permutations(c.getKey())) {
                shapes.add(Shape.of(check(p, c.getValue())));
            }
            assertEquals(1, shapes.size(), "顺序换出了不同的行: " + shapes);
            List<Shape> only = shapes.iterator().next();
            assertEquals(1, only.size());
            assertEquals(List.of(f(1), f(2), f(3)), only.get(0).path(), "整组，按 id 排序");
            assertEquals(f(1), only.get(0).left());
            assertEquals(f(3), only.get(0).right(), "首尾是最小与最大");
        }
    }

    /**
     * <b>环要整条路径同一时刻成立</b>（#636）。A 在 2019–2021 年属于 B，2022 年起 B
     * 属于 A：图在任何一刻都没有这个环。三元环更容易看走眼——三条边可以两两重叠，
     * 三者却没有共同的一刻，这时逐对比较会误报，只有沿路径求交才对。
     */
    @Test
    void aCycleMustHoldAtOneMoment() {
        Map<UUID, Axioms> transitive = with(new Axioms(true, false, false, false, false, false,
                null, null));

        // 两条边一前一后
        List<TimedEdge> swapped = List.of(at(1, 1, 2, 0L, 100L), at(2, 2, 1, 100L, null));
        assertTrue(cyclesOf(swapped, transitive).isEmpty(), "前后相接的两段凑不成环");

        // 三条边两两重叠，三者无交：[0,100) ∩ [50,150) ∩ [100,200) = ∅
        List<TimedEdge> pairwise = List.of(at(1, 1, 2, 0L, 100L), at(2, 2, 3, 50L, 150L),
                at(3, 3, 1, 100L, 200L));
        assertTrue(cyclesOf(pairwise, transitive).isEmpty(), "两两重叠不等于同时成立");

        // 三者共有 [90,100) 这一段：是环，而且照旧只报一次、规范形状
        List<TimedEdge> together = List.of(at(1, 1, 2, 0L, 100L), at(2, 2, 3, 50L, 150L),
                at(3, 3, 1, 90L, 200L));
        List<Violation> v = cyclesOf(together, transitive);
        assertEquals(1, v.size());
        assertEquals(List.of(f(1), f(2), f(3)), v.get(0).path());

        // 没日期的事件哪一刻都不成立，不进任何环
        List<TimedEdge> undated = List.of(at(1, 1, 2, 50L, 50L), at(2, 2, 1, null, null));
        assertTrue(cyclesOf(undated, transitive).isEmpty());
    }

    private static List<Violation> cyclesOf(List<TimedEdge> edges, Map<UUID, Axioms> ax) {
        return AxiomChecks.check(edges, ax).stream().filter(v -> v.kind() == Kind.CYCLE).toList();
    }

    // ---------- 对拍：随机小图上，与暴力做法逐个比（lib.rs:1064-1345） ----------

    /** 随机图生成（lib.rs:1086-1102）：节点少、边多，平行边、自环、反向边都常见。 */
    private static List<TimedEdge> randomGraph(Random rng, int nodes, int maxEdges) {
        int count = rng.nextInt(maxEdges + 1);
        List<TimedEdge> out = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            int s = rng.nextInt(nodes) + 1;
            int o = rng.nextInt(nodes) + 1;
            Long from = bound(rng);
            Long to = bound(rng);
            out.add(at(i, s, o, from, to));
        }
        return out;
    }

    /** 端点落在 0..=60 的十格上（1/4 概率无界）——首尾相接、完全重合、空区间、无界都常见。 */
    private static Long bound(Random rng) {
        if (rng.nextInt(4) == 0) {
            return null;
        }
        return (long) rng.nextInt(7) * 10;
    }

    /** Fisher–Yates 洗牌（Rust shuffled：i 从 len-1 降到 1）。 */
    private static List<TimedEdge> shuffled(Random rng, List<TimedEdge> edges) {
        List<TimedEdge> v = new ArrayList<>(edges);
        for (int i = v.size() - 1; i >= 1; i--) {
            Collections.swap(v, i, rng.nextInt(i + 1));
        }
        return v;
    }

    /**
     * 暴力找环（lib.rs:1114-1167）：从每个节点起，枚举所有节点不重复、至少两条边的回路，
     * 整条路径的区间交集非空才算。不剪枝，不看遍历顺序，结果按事实集合收。
     */
    private static Set<List<UUID>> cyclesByBruteForce(List<TimedEdge> edges) {
        Set<List<UUID>> out = new HashSet<>();
        Set<UUID> starts = new HashSet<>();
        for (TimedEdge t : edges) {
            starts.add(t.edge().subject());
        }
        for (UUID start : starts) {
            extend(edges, start, start, new ArrayList<>(), new ArrayList<>(List.of(start)), out);
        }
        return out;
    }

    private static void extend(List<TimedEdge> edges, UUID start, UUID at, List<Integer> path,
                               List<UUID> seen, Set<List<UUID>> out) {
        for (int i = 0; i < edges.size(); i++) {
            TimedEdge t = edges.get(i);
            if (!t.edge().subject().equals(at)) {
                continue;
            }
            if (t.edge().object().equals(start)) {
                if (path.isEmpty()) {
                    continue;
                }
                List<Integer> all = new ArrayList<>(path);
                all.add(i);
                Span acc = new Span(null, null);
                boolean holds = true;
                for (int j : all) {
                    acc = Derive.overlap(acc, edges.get(j).span());
                    if (acc == null) {
                        holds = false;
                        break;
                    }
                }
                if (holds) {
                    List<UUID> facts = all.stream().map(j -> edges.get(j).edge().fact())
                            .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
                    facts.sort(UuidLexical::compare);
                    out.add(facts);
                }
                continue;
            }
            if (seen.contains(t.edge().object())) {
                continue;
            }
            seen.add(t.edge().object());
            path.add(i);
            extend(edges, start, t.edge().object(), path, seen, out);
            path.remove(path.size() - 1);
            seen.remove(seen.size() - 1);
        }
    }

    /** 暴力分组（lib.rs:1170-1204）：两两比，同键、「不同」、区间有交就连一条，取连通块。 */
    private static Set<List<UUID>> groupsByBruteForce(List<TimedEdge> edges,
                                                      java.util.function.BiPredicate<Edge, Edge> conflict) {
        int n = edges.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        boolean[] clashed = new boolean[n];
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                TimedEdge a = edges.get(i);
                TimedEdge b = edges.get(j);
                if (conflict.test(a.edge(), b.edge())
                        && Derive.overlap(a.span(), b.span()) != null) {
                    clashed[i] = true;
                    clashed[j] = true;
                    parent[AxiomChecks.root(parent, i)] = AxiomChecks.root(parent, j);
                }
            }
        }
        Map<Integer, List<UUID>> blocks = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            if (clashed[i]) {
                blocks.computeIfAbsent(AxiomChecks.root(parent, i), k -> new ArrayList<>())
                        .add(edges.get(i).edge().fact());
            }
        }
        Set<List<UUID>> out = new HashSet<>();
        for (List<UUID> v : blocks.values()) {
            List<UUID> sorted = new ArrayList<>(v);
            sorted.sort(UuidLexical::compare);
            out.add(sorted);
        }
        return out;
    }

    private static List<List<UUID>> found(List<Violation> v, Kind kind) {
        return v.stream().filter(x -> x.kind() == kind).map(x -> {
            List<UUID> facts = new ArrayList<>(x.path());
            facts.sort(UuidLexical::compare);
            return facts;
        }).toList();
    }

    /**
     * <b>环：几千张随机小图上与暴力枚举逐个一致</b>（#636）。剪枝是个"证明了才敢做"的
     * 优化——交集只会越求越窄，所以剪掉的分支凑不出真环。这里不信证明，信对拍：
     * 带剪枝的深搜报出的环集合必须与不剪枝、整条求交的暴力枚举完全相同，一个不多
     * 一个不少；每个环只报一次，报出来的形状是规范的（从最小事实起头、首尾对得上
     * 路径、路径真的首尾相连）；把输入打乱，输出逐字节不变。
     */
    @Test
    void cyclesAgreeWithBruteForceOnRandomGraphs() {
        Map<UUID, Axioms> transitive = with(new Axioms(true, false, false, false, false, false,
                null, null));
        Random rng = new Random(0x0636_C0FF_EE00_0001L);
        int withCycles = 0;
        int filteredByTime = 0;
        for (int round = 0; round < 6000; round++) {
            int nodes = round % 2 == 0 ? 3 : 4;
            int maxEdges = round % 2 == 0 ? 9 : 12;
            List<TimedEdge> edges = randomGraph(rng, nodes, maxEdges);
            List<Violation> v = AxiomChecks.check(edges, transitive);
            List<List<UUID>> got = found(v, Kind.CYCLE);
            Set<List<UUID>> unique = new HashSet<>(got);
            assertEquals(got.size(), unique.size(), "同一个环报了两次: " + edges);
            assertEquals(cyclesByBruteForce(edges), unique, "与暴力枚举不一致: " + edges);
            if (!unique.isEmpty()) {
                withCycles++;
            }
            // 同一张图抹掉时间再暴力找一遍：形状上的环比带时间的多，说明这张图上
            // 时间真的起了作用——剪枝与求交走到了
            List<TimedEdge> timeless = edges.stream()
                    .map(t -> new TimedEdge(t.edge(), null, null)).toList();
            if (cyclesByBruteForce(timeless).size() > unique.size()) {
                filteredByTime++;
            }

            Map<UUID, TimedEdge> byFact = new LinkedHashMap<>();
            for (TimedEdge t : edges) {
                byFact.put(t.edge().fact(), t);
            }
            for (Violation c : v) {
                if (c.kind() != Kind.CYCLE) {
                    continue;
                }
                assertEquals(c.path().stream().min(UuidLexical::compare).orElseThrow(), c.left(),
                        "从最小的事实起头");
                assertEquals(c.path().get(0), c.left());
                assertEquals(c.path().getLast(), c.right());
                for (int w = 0; w < c.path().size(); w++) {
                    Edge here = byFact.get(c.path().get(w)).edge();
                    Edge next = byFact.get(c.path().get((w + 1) % c.path().size())).edge();
                    assertEquals(here.object(), next.subject(), "路径要真的首尾相连: " + c);
                }
            }

            assertEquals(Shape.of(v), Shape.of(AxiomChecks.check(shuffled(rng, edges),
                    transitive)), "打乱输入换了输出: " + edges);
        }
        // 对拍要真的碰到环才算数：随机图若几乎不成环，这个测试什么也没证明
        assertTrue(withCycles > 1000, "只有 " + withCycles + " 张图有环，样本太稀");
        assertTrue(filteredByTime > 1000,
                "只有 " + filteredByTime + " 张图上时间筛掉过环，剪枝没怎么走到");
    }

    /**
     * <b>互斥三类：几千张随机小图上与两两比较的暴力分组逐个一致</b>（#634、#624）。
     * 按起点扫一遍、手里只留还没结束的——这个扫描省掉了两两比较，也最容易在边界上
     * 错：首尾相接、无界的起点、空区间、同一时刻开始的几条。暴力做法把每一对都比
     * 一遍再取连通块，两边给出的组必须完全相同；打乱输入，输出逐字节不变。
     */
    @Test
    void clashesAgreeWithBruteForceOnRandomGraphs() {
        Map<UUID, Axioms> all = with(new Axioms(false, false, true, false, true, true, null,
                null));
        Random rng = new Random(0x0634_BEEF_0000_0002L);
        int[] seen = new int[3];
        for (int round = 0; round < 4000; round++) {
            int nodes = round % 2 == 0 ? 2 : 4;
            int maxEdges = round % 2 == 0 ? 8 : 12;
            List<TimedEdge> edges = randomGraph(rng, nodes, maxEdges);
            List<Violation> v = AxiomChecks.check(edges, all);
            List<Map.Entry<Kind, java.util.function.BiPredicate<Edge, Edge>>> cases = List.of(
                    Map.entry(Kind.FUNCTIONAL, (a, b) -> a.subject().equals(b.subject())
                            && !a.object().equals(b.object())),
                    Map.entry(Kind.INVERSE_FUNCTIONAL, (a, b) -> a.object().equals(b.object())
                            && !a.subject().equals(b.subject())),
                    Map.entry(Kind.ASYMMETRY, (a, b) -> !a.subject().equals(a.object())
                            && a.subject().equals(b.object()) && a.object().equals(b.subject())));
            for (int k = 0; k < cases.size(); k++) {
                Kind kind = cases.get(k).getKey();
                java.util.function.BiPredicate<Edge, Edge> conflict = cases.get(k).getValue();
                List<List<UUID>> got = found(v, kind);
                Set<List<UUID>> unique = new HashSet<>(got);
                assertEquals(got.size(), unique.size(), kind + " 同一组报了两次: " + edges);
                assertEquals(groupsByBruteForce(edges, conflict), unique,
                        kind + " 与暴力分组不一致: " + edges);
                seen[k] += unique.size();
            }
            for (Violation x : v) {
                if (x.kind() == Kind.CYCLE) {
                    continue;
                }
                List<UUID> sorted = new ArrayList<>(x.path());
                sorted.sort(UuidLexical::compare);
                assertEquals(sorted, x.path(), "组按 id 排序");
                assertEquals(x.path().get(0), x.left());
                assertEquals(x.path().getLast(), x.right());
            }
            assertEquals(Shape.of(v), Shape.of(AxiomChecks.check(shuffled(rng, edges), all)),
                    "打乱输入换了输出: " + edges);
        }
        assertTrue(Arrays.stream(seen).allMatch(n -> n > 500),
                "有一类几乎没碰到: " + Arrays.toString(seen));
    }

    /**
     * <b>剪枝丢不了真环</b>。同一对节点之间有两条边，一条的区间让路径无交、另一条
     * 不会：前一条被剪掉之后，后一条照样把环走出来。输入的每一种顺序都要到——
     * 剪枝发生在遍历里，顺序决定先碰到哪一条。
     */
    @Test
    void pruningADeadBranchKeepsTheLiveOne() {
        Map<UUID, Axioms> transitive = with(new Axioms(true, false, false, false, false, false,
                null, null));
        List<TimedEdge> edges = List.of(
                at(1, 1, 2, 0L, 100L),
                // 与 1 无交：经过它的路径被剪掉
                at(2, 2, 3, 200L, 300L),
                // 与 1 有交：环走这一条
                at(3, 2, 3, 50L, 150L),
                at(4, 3, 1, 60L, null));
        for (List<TimedEdge> order : permutations(edges)) {
            List<Violation> v = cyclesOf(order, transitive);
            assertEquals(1, v.size(), order.toString());
            assertEquals(List.of(f(1), f(3), f(4)), v.get(0).path());
        }
    }

    /**
     * <b>前后相接是接任，不是矛盾</b>（#634）。Lin Zhao 的薪水 [2023-06, 2024-02)
     * 是 28000、[2024-02, 现在) 是 32000：一个人任何一刻都只有一份薪水。从前
     * 检查不看时间，每次调薪都进 Review。重叠哪怕一秒，才是同时有两个值。
     */
    @Test
    void aSuccessionIsNotAContradiction() {
        Map<UUID, Axioms> functional = with(new Axioms(false, false, false, false, true, false,
                null, null));
        long jun23 = 1_685_577_600L;
        long feb24 = 1_708_387_200L;

        List<TimedEdge> raise = List.of(at(1, 1, 2, jun23, feb24), at(2, 1, 3, feb24, null));
        assertTrue(AxiomChecks.check(raise, functional).isEmpty(), "首尾相接");

        List<TimedEdge> overlap = List.of(at(1, 1, 2, jun23, feb24 + 1), at(2, 1, 3, feb24,
                null));
        assertEquals(List.of(Kind.FUNCTIONAL), kinds(AxiomChecks.check(overlap, functional)));

        // 宾语侧同理：一个项目换了负责人，中间还空了一年
        List<TimedEdge> handover = List.of(at(1, 2, 9, jun23, feb24),
                at(2, 3, 9, feb24 + 31_536_000L, null));
        Map<UUID, Axioms> inverse = with(new Axioms(false, false, false, false, false, true,
                null, null));
        assertTrue(AxiomChecks.check(handover, inverse).isEmpty());

        // 反对称同理：先是 A 管 B，后来 B 管 A
        List<TimedEdge> reversal = List.of(at(1, 1, 2, jun23, feb24), at(2, 2, 1, feb24, null));
        Map<UUID, Axioms> asymmetric = with(new Axioms(false, false, true, false, false, false,
                null, null));
        assertTrue(AxiomChecks.check(reversal, asymmetric).isEmpty());
    }

    /**
     * 无界的那一侧跟谁都重叠：一条不知道从何时起、一直成立的事实，与任何一段
     * 都同时成立。没有日期的事件区间为空（0031），哪一刻都不成立，跟谁都不冲突。
     */
    @Test
    void anUnboundedSpanMeetsEverythingAndAnEmptyOneMeetsNothing() {
        Map<UUID, Axioms> functional = with(new Axioms(false, false, false, false, true, false,
                null, null));
        List<TimedEdge> always = List.of(at(1, 1, 2, null, null), at(2, 1, 3, 100L, 200L));
        assertEquals(List.of(Kind.FUNCTIONAL), kinds(AxiomChecks.check(always, functional)));

        List<TimedEdge> undatedEvent = List.of(at(1, 1, 2, 150L, 150L), at(2, 1, 3, 100L, 200L));
        assertTrue(AxiomChecks.check(undatedEvent, functional).isEmpty());
    }

    /**
     * <b>组是冲突的连通块</b>。a 与 b 重叠、b 与 c 重叠，a 与 c 首尾相接：三条是一组
     * ——撤掉 b 两场冲突一起消失，分开报会让人以为是两件事。两场彼此隔开的冲突是两组。
     */
    @Test
    void aClashIsAConnectedGroupAndSeparateClashesStaySeparate() {
        Map<UUID, Axioms> functional = with(new Axioms(false, false, false, false, true, false,
                null, null));
        List<TimedEdge> chained = List.of(at(1, 1, 2, 0L, 100L), at(2, 1, 3, 50L, 150L),
                at(3, 1, 4, 100L, 200L));
        List<Violation> v = AxiomChecks.check(chained, functional);
        assertEquals(1, v.size());
        assertEquals(List.of(f(1), f(2), f(3)), v.get(0).path());

        List<TimedEdge> apart = List.of(at(1, 1, 2, 0L, 100L), at(2, 1, 3, 50L, 100L),
                at(3, 1, 4, 500L, 600L), at(4, 1, 5, 550L, 600L),
                // 与谁都不重叠的一条不进任何一组
                at(5, 1, 6, 300L, 400L));
        List<Shape> s = Shape.of(AxiomChecks.check(apart, functional));
        assertEquals(2, s.size());
        assertEquals(List.of(f(1), f(2)), s.get(0).path());
        assertEquals(List.of(f(3), f(4)), s.get(1).path());
    }

    /** 同一个主语两次指向<b>同一个</b>宾语不是冲突——重复断言而已。 */
    @Test
    void sayingTheSameThingTwiceIsNotAContradiction() {
        List<Edge> edges = List.of(e(1, 1, 2), e(2, 1, 2));
        assertTrue(check(edges, with(new Axioms(false, false, false, false, true, false, null,
                null))).isEmpty());
    }

    /**
     * <b>公理挂在谓词上，跨谓词的边之间没有可比性</b>。
     * A p B 与 B q A 同时成立不是矛盾，哪怕 p 声明了反对称。
     */
    @Test
    void axiomsDoNotLeakAcrossPredicates() {
        Edge a = new Edge(f(1), n(90), n(1), n(2));
        Edge b = new Edge(f(2), n(91), n(2), n(1));
        Map<UUID, Axioms> ax = Map.of(
                n(90), new Axioms(false, false, true, false, false, false, null, null),
                n(91), new Axioms(false, false, true, false, false, false, null, null));
        assertTrue(check(List.of(a, b), ax).isEmpty());
    }
}
