package tech.seexun.jknow.reason;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * 拿公理量一遍这批边（lib.rs:167-534）——一致性检查的纯逻辑部分：<b>不写 facts 表，
 * 也不碰数据库</b>。输入是边与公理，输出是「哪几条事实互相矛盾」。取数与落库在 store 侧。
 *
 * <p>每个谓词各查各的：公理是挂在谓词上的，跨谓词的边之间没有可比性
 * （{@code A part_of B} 与 {@code B produces A} 同时成立不是矛盾）。
 *
 * <p><b>互斥的三类要求两条事实同时成立</b>（#634）。functional、inverse_functional、
 * asymmetric 说的都是「同一时刻」：Lin Zhao 的薪水在 2024-02-20 从 28000 变成
 * 32000，两段首尾相接，那是一次调薪。
 *
 * <p><b>环要整条路径在同一时刻成立</b>（#636）。A 在 2019–2021 年并入 B、2022 年起 B
 * 又并入 A，图在任何一刻都没有这个环；两两重叠也不够，三条边可以两两相交而三者
 * 无交。所以沿路径求交集。
 *
 * <p>区间照 {@link Derive.TimedEdge} 的读法：半开 {@code [from, to)}，{@code null} 是那一侧无界
 * （恒常谓词两端都是 {@code null}，于是退回到只看形状）。自环不看时间：{@code A p A}
 * 哪一刻成立都是错的。
 */
public final class AxiomChecks {

    /**
     * 环检测的深度上限（lib.rs:118-124）。
     *
     * <p><b>必须有，不是防御性编程。</b>0002 在真实语料上量过：part_of 的传递闭包
     * 从 185 条膨胀到 828 条且<b>不收敛</b>——深度分布 1:185 2:181 3:141 4:45
     * 5:52 6:40 7:52 8:40 9:52 10:40，第 5 层起振荡而不是衰减，那是有环的形状。
     * 没有上限，一个环就能让求值不终止。
     */
    public static final int MAX_DEPTH = 12;

    /**
     * 一个谓词上最多报多少个环（#642，lib.rs:126-131）。
     *
     * <p>上千个环不是上千件事：它们挤在同一团互相可达的节点里，根子多半是这个谓词不该
     * 声明 transitive，或者有一两条边方向写反了。逐个端进 Review 只会淹掉队列，与
     * {@code MAX_CLASHES_PER_PREDICATE} 同一个道理。
     */
    public static final int MAX_CYCLES_PER_PREDICATE = 1000;

    /**
     * 一个谓词上找环最多走多少步（每看一条边算一步，#642，lib.rs:133-138）。
     *
     * <p><b>环数上限管不住工作量。</b>一团稠密的强连通分量里，深度 12 以内的简单路径是
     * 指数级的，大多数根本不回到起点——步数没有上限，一次检查就能把请求挂住、把内存
     * 撑满。量过：500 个节点、2000 条边、两成随机回边，从前 60 秒没跑完。
     */
    public static final int MAX_CYCLE_STEPS = 1_000_000;

    private AxiomChecks() {
    }

    /** lib.rs:167-169 check：只要违规，不要「哪些谓词没搜完」。 */
    public static List<Violation> check(List<Derive.TimedEdge> edges, Map<UUID, Axioms> axioms) {
        return checkAll(edges, axioms).violations();
    }

    /**
     * 同 {@link #check}，另外说出哪些谓词的环没搜完（#642，lib.rs:172-223）。
     */
    public static Checked checkAll(List<Derive.TimedEdge> edges, Map<UUID, Axioms> axioms) {
        List<Violation> out = new ArrayList<>();
        List<UUID> cyclesCapped = new ArrayList<>();
        // 谓词分组用 LinkedHashMap + 出口按谓词 id 排序：Rust 侧 HashMap 迭代序任意、
        // 结果的「行身份」与顺序无关（键是 canonical 的），Java 侧排一次序让同一份数据
        // 每次跑出的 violations 顺序也一致——测试对拍与排查都省事
        Map<UUID, List<Derive.TimedEdge>> byPred = new LinkedHashMap<>();
        for (Derive.TimedEdge t : edges) {
            Axioms ax = axioms.get(t.edge().predicate());
            if (ax == null || ax.saysNothing()) {
                continue;
            }
            byPred.computeIfAbsent(t.edge().predicate(), k -> new ArrayList<>()).add(t);
        }
        for (UUID pred : byPred.keySet().stream()
                .sorted(UuidLexical::compare).toList()) {
            List<Derive.TimedEdge> group = byPred.get(pred);
            Axioms ax = axioms.get(pred);
            if (ax.irreflexive()) {
                out.addAll(selfLoops(group));
            }
            if (ax.asymmetric()) {
                out.addAll(asymmetries(group));
            }
            if (ax.transitive()) {
                CyclesResult found = cycles(group);
                out.addAll(found.violations());
                if (found.capped()) {
                    cyclesCapped.add(pred);
                }
            }
            if (ax.functional()) {
                out.addAll(clashes(group, Kind.FUNCTIONAL,
                        e -> new ClashKey(e.subject(), UuidLexical.NIL),
                        (a, b) -> !a.object().equals(b.object())));
            }
            if (ax.inverseFunctional()) {
                out.addAll(clashes(group, Kind.INVERSE_FUNCTIONAL,
                        e -> new ClashKey(e.object(), UuidLexical.NIL),
                        (a, b) -> !a.subject().equals(b.subject())));
            }
        }
        cyclesCapped.sort(UuidLexical::compare);
        return new Checked(out, cyclesCapped);
    }

    /** lib.rs:225-237：subject==object 的边，两列填同一条——它跟自己矛盾。 */
    static List<Violation> selfLoops(List<Derive.TimedEdge> edges) {
        List<Violation> out = new ArrayList<>();
        for (Derive.TimedEdge t : edges) {
            if (t.edge().subject().equals(t.edge().object())) {
                out.add(new Violation(Kind.SELF_LOOP, t.edge().fact(), t.edge().fact(),
                        List.of()));
            }
        }
        return out;
    }

    /**
     * 反对称：同一对节点在同一段时间里两个方向都断言了（lib.rs:243-256）。
     *
     * <p>键是无序的那一对节点，「不同」是方向相反——与 functional 同一个形状，所以走
     * 同一个函数。自环由 irreflexive 那一档负责；反对称在这里报一遍是重复。
     */
    static List<Violation> asymmetries(List<Derive.TimedEdge> edges) {
        List<Derive.TimedEdge> twoWay = new ArrayList<>();
        for (Derive.TimedEdge t : edges) {
            if (!t.edge().subject().equals(t.edge().object())) {
                twoWay.add(t);
            }
        }
        return clashes(twoWay, Kind.ASYMMETRY,
                e -> new ClashKey(UuidLexical.min(e.subject(), e.object()),
                        UuidLexical.max(e.subject(), e.object())),
                (a, b) -> !a.subject().equals(b.subject()));
    }

    /**
     * 找环（lib.rs:279-319）。<b>每个环只报一次，而且只以一种形状报</b>：报出来的路径转到
     * 最小的那条事实起头（见 {@code Search} 里的注释）。
     *
     * <p>用深度优先而不是半朴素闭包求值：两者都能发现环，但闭包只告诉你「A 推出了
     * A」，而人要的是<b>路径</b>——顺着 {@code A→B→C→A} 看一遍才知道该撤哪一条。
     *
     * <p><b>只在强连通分量里找，每个环只从它最小的节点找一次</b>（#642）。从前从每个节点出发
     * 把深度 12 以内的路径全走一遍，再按事实集合去重。环只可能落在一个强连通分量里，
     * 而真实的 part_of 大体是一棵层级、只有几条回边：分量很小，绝大多数路径一路往下
     * 走进 DAG、永远回不来。先算分量、只走分量内的边，这些路径一步都不走；再规定环只从
     * 它编号最小的节点出发、途中只经过比起点大的节点，每个环恰好被走到一次。
     *
     * <p>真正稠密的大分量里环本身就是指数级的——那时撞上 {@code MAX_CYCLES_PER_PREDICATE} /
     * {@code MAX_CYCLE_STEPS} 就停，第二个返回值为真。起点与每个节点的出边都排过序，
     * 所以停在哪儿只取决于数据：同一份数据跑多少遍、以什么顺序读进来，报出来的都是
     * 同一批环。
     */
    static CyclesResult cycles(List<Derive.TimedEdge> edges) {
        // 自环不是这里的事（irreflexive 那一档管），也不可能在两个以上节点的环上
        Map<UUID, List<UUID>> next = new HashMap<>();
        for (Derive.TimedEdge t : edges) {
            if (!t.edge().subject().equals(t.edge().object())) {
                next.computeIfAbsent(t.edge().subject(), k -> new ArrayList<>())
                        .add(t.edge().object());
            }
        }
        List<UUID> nodes = new ArrayList<>(next.keySet());
        nodes.sort(UuidLexical::compare);
        Map<UUID, Integer> component = components(nodes, next);

        // 分量内的边才可能在环上；出边按 (宾语, 事实) 排，遍历顺序只取决于数据
        Map<UUID, List<Derive.TimedEdge>> adj = new HashMap<>();
        for (Derive.TimedEdge t : edges) {
            UUID s = t.edge().subject();
            UUID o = t.edge().object();
            boolean same = component.containsKey(s) && component.containsKey(o)
                    && component.get(s).equals(component.get(o));
            if (!s.equals(o) && same) {
                adj.computeIfAbsent(s, k -> new ArrayList<>()).add(t);
            }
        }
        for (List<Derive.TimedEdge> out : adj.values()) {
            out.sort(Comparator.comparing((Derive.TimedEdge t) -> t.edge().object(),
                    UuidLexical::compare).thenComparing(t -> t.edge().fact(), UuidLexical::compare));
        }
        List<UUID> starts = new ArrayList<>(adj.keySet());
        starts.sort(UuidLexical::compare);

        Search search = new Search(adj);
        for (UUID start : starts) {
            search.walk(start, start, new Derive.Span(null, null));
            if (search.capped) {
                break;
            }
        }
        return new CyclesResult(search.out, search.capped);
    }

    /** cycles 的两件产出（Rust 的二元组）。 */
    record CyclesResult(List<Violation> violations, boolean capped) {
    }

    /**
     * 强连通分量：每个节点所在分量的编号（lib.rs:325-383）。
     *
     * <p>Tarjan，<b>迭代版</b>：递归版的调用深度等于最长的那条链，几万个节点的层级在它面前
     * 就是几万层递归，栈会炸。这里自己维护调用栈（节点, 下一个要看的后继）。
     */
    static Map<UUID, Integer> components(List<UUID> nodes, Map<UUID, List<UUID>> next) {
        Map<UUID, Integer> index = new HashMap<>();
        Map<UUID, Integer> low = new HashMap<>();
        Set<UUID> onStack = new HashSet<>();
        Deque<UUID> stack = new ArrayDeque<>();
        Map<UUID, Integer> component = new HashMap<>();
        int counter = 0;
        int count = 0;
        for (UUID root : nodes) {
            if (index.containsKey(root)) {
                continue;
            }
            index.put(root, counter);
            low.put(root, counter);
            counter++;
            stack.push(root);
            onStack.add(root);
            Deque<long[]> calls = new ArrayDeque<>();
            Deque<UUID> callNodes = new ArrayDeque<>();
            calls.push(new long[]{0});
            callNodes.push(root);
            while (!calls.isEmpty()) {
                UUID v = callNodes.peek();
                List<UUID> succ = next.getOrDefault(v, List.of());
                int i = (int) calls.peek()[0];
                if (i < succ.size()) {
                    calls.peek()[0] = i + 1;
                    UUID w = succ.get(i);
                    if (!index.containsKey(w)) {
                        index.put(w, counter);
                        low.put(w, counter);
                        counter++;
                        stack.push(w);
                        onStack.add(w);
                        calls.push(new long[]{0});
                        callNodes.push(w);
                    } else if (onStack.contains(w)) {
                        int lw = index.get(w);
                        low.merge(v, lw, Math::min);
                    }
                    continue;
                }
                calls.pop();
                callNodes.pop();
                int lv = low.get(v);
                if (!callNodes.isEmpty()) {
                    UUID parent = callNodes.peek();
                    low.merge(parent, lv, Math::min);
                }
                if (lv == index.get(v)) {
                    while (true) {
                        UUID w = stack.pop();
                        onStack.remove(w);
                        component.put(w, count);
                        if (w.equals(v)) {
                            break;
                        }
                    }
                    count++;
                }
            }
        }
        return component;
    }

    /** 互斥组的键：functional 的（主语, nil）、inverse 的（宾语, nil）、反对称的无序对。 */
    record ClashKey(UUID a, UUID b) {
    }

    /**
     * 一次找环的状态（lib.rs:386-460）：当前路径、找到的环、走了多少步、是否撞上了上限。
     */
    private static final class Search {
        final Map<UUID, List<Derive.TimedEdge>> adj;
        final List<Derive.TimedEdge> path = new ArrayList<>();
        final Set<UUID> onPath = new HashSet<>();
        final List<Violation> out = new ArrayList<>();
        int steps;
        boolean capped;

        Search(Map<UUID, List<Derive.TimedEdge>> adj) {
            this.adj = adj;
        }

        /** 从 {@code at} 往下走。{@code span} 是路径上已走过的边的区间交集；起点是全时间。 */
        void walk(UUID start, UUID at, Derive.Span span) {
            if (path.size() >= MAX_DEPTH || capped) {
                return;
            }
            List<Derive.TimedEdge> next = adj.get(at);
            if (next == null) {
                return;
            }
            for (Derive.TimedEdge t : next) {
                steps++;
                if (steps > MAX_CYCLE_STEPS || out.size() >= MAX_CYCLES_PER_PREDICATE) {
                    capped = true;
                    return;
                }
                Edge e = t.edge();
                // 只经过比起点大的节点：每个环只在从它最小的节点出发时被走到，恰好一次。
                // 出边按宾语排过序，这里本可以二分跳过，但出边通常只有几条
                if (UuidLexical.compare(e.object(), start) < 0) {
                    continue;
                }
                // 接上这条边，路径就没有哪一刻是整条成立的：不管是成环还是往下走，都不必了。
                // 没日期的事件区间为空（0031），在这里自然被剪掉
                Derive.Span joined = Derive.overlap(span, t.span());
                if (joined == null) {
                    continue;
                }
                if (e.object().equals(start)) {
                    if (path.isEmpty()) {
                        continue;
                    }
                    List<UUID> facts = new ArrayList<>();
                    for (Derive.TimedEdge x : path) {
                        facts.add(x.edge().fact());
                    }
                    facts.add(e.fact());
                    // **报之前把环转到规范位置。**环是一个圈，从哪条边开始读都是同一个环；
                    // 而 axiom_violations 里环是按整条 path 唯一的（0054）：换一种旋转
                    // 就是换一行。后果不是"多一行"，是**人的裁决会悄悄失效**：重算删掉的是
                    // open 的行，裁过的那行留着，同一个环以新键插成 open，重开那一支匹配
                    // 不上就不会触发（#618）。起点是最小的节点，却未必是最小的事实——转到
                    // 最小的事实 id 起头，键就成了环自己的函数
                    int atMin = 0;
                    for (int i = 1; i < facts.size(); i++) {
                        if (UuidLexical.compare(facts.get(i), facts.get(atMin)) < 0) {
                            atMin = i;
                        }
                    }
                    Collections.rotate(facts, -atMin);
                    out.add(new Violation(Kind.CYCLE, facts.getFirst(), facts.getLast(),
                            List.copyOf(facts)));
                    continue;
                }
                if (onPath.contains(e.object())) {
                    continue;
                }
                onPath.add(e.object());
                path.add(t);
                walk(start, e.object(), joined);
                path.remove(path.size() - 1);
                onPath.remove(e.object());
                if (capped) {
                    return;
                }
            }
        }
    }

    /**
     * 互斥：同一个键下，两条「不同」的事实同时成立（lib.rs:476-534）。
     *
     * <p>functional（键是主语，不同是宾语不同）、inverse_functional（键是宾语，不同是
     * 主语不同）、asymmetric（键是无序的一对节点，不同是方向相反）都是这个形状，
     * 调用方给键和「不同」。
     *
     * <p><b>一组报一处。</b>同一个主语同一段时间里指了五个宾语，是一件事；两两组合报十处
     * 只是把它说十遍。组是冲突关系的连通块：a 与 b 同时成立、b 与 c 同时成立，三条
     * 就是一组，哪怕 a 与 c 首尾相接。先后出现、互不重叠的两场冲突是两组。
     *
     * <p><b>身份只由组里有哪些事实决定</b>（#624）。axiom_violations 按
     * {@code (kind, left_fact, right_fact)} 唯一；从前 left/right 取遇到的头两条，而遇到的
     * 顺序来自 HashMap 与取数顺序——同一处冲突换个顺序就是另一行，人裁过的那行被绕开，
     * 与 #618 的环是同一个病。现在 path 是排过序的整组，left/right 是它的首尾。
     */
    static List<Violation> clashes(List<Derive.TimedEdge> edges, Kind kind,
                                   Function<Edge, ClashKey> key, BiPredicate<Edge, Edge> differ) {
        Map<ClashKey, List<Derive.TimedEdge>> byKey = new LinkedHashMap<>();
        for (Derive.TimedEdge t : edges) {
            // 空区间（没有日期的事件，0031）哪一刻都不成立，跟谁都不同时
            if (t.from() != null && t.to() != null && t.from() >= t.to()) {
                continue;
            }
            byKey.computeIfAbsent(key.apply(t.edge()), k -> new ArrayList<>()).add(t);
        }
        List<Violation> out = new ArrayList<>();
        for (List<Derive.TimedEdge> group : byKey.values()) {
            if (group.size() < 2) {
                continue;
            }
            // 按起点扫一遍，手里留着还没结束的：它们正是与这一条同时成立的。一长串前后
            // 相接的历史值（逐月的薪水）手里始终只有一两条，不会两两比一遍
            group.sort(Comparator.comparingLong((Derive.TimedEdge t) ->
                    t.from() == null ? Long.MIN_VALUE : t.from())
                    .thenComparing(t -> t.edge().fact(), UuidLexical::compare));
            int[] parent = new int[group.size()];
            for (int i = 0; i < parent.length; i++) {
                parent[i] = i;
            }
            boolean[] clashed = new boolean[group.size()];
            List<Integer> open = new ArrayList<>();
            for (int i = 0; i < group.size(); i++) {
                long start = group.get(i).from() == null ? Long.MIN_VALUE : group.get(i).from();
                // 半开区间：终点正好是这一条起点的，已经结束了——首尾相接是接任
                final long s = start;
                open.removeIf(j -> group.get(j).to() != null && group.get(j).to() <= s);
                for (int j : open) {
                    if (differ.test(group.get(i).edge(), group.get(j).edge())) {
                        clashed[i] = true;
                        clashed[j] = true;
                        int a = root(parent, i);
                        int b = root(parent, j);
                        parent[a] = b;
                    }
                }
                open.add(i);
            }
            Map<Integer, List<UUID>> blocks = new LinkedHashMap<>();
            for (int i = 0; i < group.size(); i++) {
                if (clashed[i]) {
                    blocks.computeIfAbsent(root(parent, i), k -> new ArrayList<>())
                            .add(group.get(i).edge().fact());
                }
            }
            for (List<UUID> facts : blocks.values()) {
                facts.sort(UuidLexical::compare);
                List<UUID> deduped = new ArrayList<>(new LinkedHashSet<>(facts));
                out.add(new Violation(kind, deduped.getFirst(), deduped.getLast(),
                        List.copyOf(deduped)));
            }
        }
        return out;
    }

    /** 并查集找根，顺手压缩路径（lib.rs:537-543）。 */
    static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }
}
