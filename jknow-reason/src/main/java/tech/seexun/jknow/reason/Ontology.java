package tech.seexun.jknow.reason;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 本体自己的自洽性（ontology.rs）：<b>不碰事实，只看定义</b>。
 *
 * <p>与 {@link AxiomChecks} 是两件事。那一层问「事实与定义抵触吗」，这一层问
 * 「定义自己站得住吗」。分开不是分类癖：一个自相矛盾的本体会让事实层的
 * 结论全部可疑——若某个谓词同时声明了 symmetric 与 asymmetric，那么据它
 * 报出来的每一条反对称违规都建立在一个本来就不成立的前提上。所以这一层
 * 的结论要<b>排在前面</b>给人看。便宜也是理由：输入只有几千行本体，不用扫账本。
 *
 * <p><b>同样是「没声明就不查」。</b>这里查的每一条都对应本体里写下来的东西，
 * 没有一条是我们替用户假设的。
 */
public final class Ontology {

    private Ontology() {
    }

    /**
     * 类层级上溯的深度上限（ontology.rs:18-20）。与 {@link AxiomChecks#MAX_DEPTH} 同一个理由：
     * 本体里可以有环（建表时只挡得住自环，{@code A → B → A} 拦不住），没有上限就不终止。
     */
    public static final int MAX_ANCESTRY = 32;

    /** 本体里的一处自相矛盾的种类（ontology.rs:22-58）。 */
    public enum Defect {
        /**
         * 同一个谓词既声明 symmetric 又声明 asymmetric。OWL 里这两者只能同时对<b>空</b>属性
         * 成立——一旦有一条边 {@code A p B}，对称说 {@code B p A} 必须成立，反对称说它必须不成立。
         */
        SYMMETRIC_AND_ASYMMETRIC("symmetric_and_asymmetric"),
        /**
         * 传递 + 函数性。OWL 2 DL 明文禁止（函数性属性不得声明为传递）。直觉上也讲得通：
         * 函数性说「主语侧只有一个值」，传递说「顺着链一直推」，而链上第二跳就给同一个
         * 主语推出了第二个值。
         */
        TRANSITIVE_AND_FUNCTIONAL("transitive_and_functional"),
        /** subClassOf 绕成了环。建表时的 CHECK 只挡得住 {@code A → A}。 */
        SUBCLASS_CYCLE("subclass_cycle"),
        /** 一个类跟自己的祖先声明了互斥 → 这个类<b>永远不可能有实例</b>。 */
        DISJOINT_WITH_ANCESTOR("disjoint_with_ancestor"),
        /** 一个类的两个祖先互相互斥 → 同上，不可满足。多父继承下这个形状不罕见。 */
        INHERITS_DISJOINT("inherits_disjoint"),
        /** 一个谓词声明自己是自己的逆。<b>等价于 symmetric</b>——推理照跑，只是读的人要多想一步。 */
        INVERSE_OF_ITSELF("inverse_of_itself"),
        /**
         * {@code p⁻¹ = q} 而 {@code q⁻¹ = r}，两边指得不一样。载入公理时只补空缺、不覆盖人写的
         * （<b>人写的优先于推出来的</b>），所以这个矛盾不会被悄悄抹平，留到这里报。
         */
        INVERSE_NOT_MUTUAL("inverse_not_mutual"),
        /** subPropertyOf 绕成了环。与 {@link #SUBCLASS_CYCLE} 同形。 */
        SUB_PROPERTY_CYCLE("sub_property_cycle");

        private final String asStr;

        Defect(String asStr) {
            this.asStr = asStr;
        }

        /** ontology.rs:61-73 as_str：直接进 SQL 也直接做落库行键的字面量。 */
        public String asString() {
            return asStr;
        }
    }

    /**
     * 本体里的一处自相矛盾（ontology.rs:76-85）。
     *
     * @param kind    种类
     * @param subject 出问题的那个对象：谓词（前两类）或类（后三类）
     * @param other   另一方：互斥的那个类。前两类与环没有第二方
     * @param path    环的路径（按类排列），或从类到那个祖先的路径。其余为空
     */
    public record OntologyDefect(Defect kind, UUID subject, UUID other, List<UUID> path) {
    }

    /** (子, 父) 对。 */
    public record ParentLink(UUID child, UUID parent) {
    }

    /** 互斥对——<b>导入侧已经把对称性展开成两行</b>，所以两个方向都会看到，去重靠有序键。 */
    public record DisjointPair(UUID a, UUID b) {
    }

    /**
     * 量一遍本体自己（ontology.rs:91-198）。
     */
    public static List<OntologyDefect> checkOntology(Map<UUID, Axioms> axioms,
                                                     List<ParentLink> parents,
                                                     List<DisjointPair> disjoint) {
        List<OntologyDefect> out = new ArrayList<>();

        // ---- 谓词上的自相矛盾。**排序后再报**：HashMap 的遍历顺序每次不同，
        // 而同一个本体两次检查出来的结果该是同一份
        List<UUID> preds = new ArrayList<>(axioms.keySet());
        preds.sort(UuidLexical::compare);
        for (UUID pred : preds) {
            Axioms ax = axioms.get(pred);
            if (ax.symmetric() && ax.asymmetric()) {
                out.add(new OntologyDefect(Defect.SYMMETRIC_AND_ASYMMETRIC, pred, null, List.of()));
            }
            if (ax.transitive() && (ax.functional() || ax.inverseFunctional())) {
                out.add(new OntologyDefect(Defect.TRANSITIVE_AND_FUNCTIONAL, pred, null, List.of()));
            }
            // 自己是自己的逆 = 对称。**不是错，是绕远路**——推理照跑，
            // 但读本体的人得自己想一步才明白。提示改用 symmetric 更直白
            if (pred.equals(ax.inverseOf())) {
                out.add(new OntologyDefect(Defect.INVERSE_OF_ITSELF, pred, null, List.of()));
            }
            // 逆 + 反对称：A p B 推出 B p A（自己的逆），而反对称说这不成立。
            // 与 symmetric+asymmetric 同一个矛盾，换了个写法进来
            if (pred.equals(ax.inverseOf()) && ax.asymmetric()) {
                out.add(new OntologyDefect(Defect.SYMMETRIC_AND_ASYMMETRIC, pred, null, List.of()));
            }
            // 两边各自声明了逆，却指向不同的谓词。**不在载入时悄悄改一致**
            // （store 侧 axioms 载入只补空缺，不覆盖人写的），所以在这里报
            if (ax.inverseOf() != null) {
                Axioms other = axioms.get(ax.inverseOf());
                UUID back = other == null ? null : other.inverseOf();
                if (back != null && !back.equals(pred)) {
                    out.add(new OntologyDefect(Defect.INVERSE_NOT_MUTUAL, pred, ax.inverseOf(),
                            List.of(pred, ax.inverseOf(), back)));
                }
            }
        }

        // ---- subPropertyOf 成环。与 subClassOf 的环是同一个形状：
        // 环上所有谓词互为子属性 = 它们其实是同一个谓词，而各自有标签、有事实
        Map<UUID, UUID> parentOf = new HashMap<>();
        for (Map.Entry<UUID, Axioms> e : axioms.entrySet()) {
            if (e.getValue().subPropertyOf() != null) {
                parentOf.put(e.getKey(), e.getValue().subPropertyOf());
            }
        }
        List<UUID> starts = new ArrayList<>(parentOf.keySet());
        starts.sort(UuidLexical::compare);
        Set<UUID> reported = new HashSet<>();
        for (UUID start : starts) {
            if (reported.contains(start)) {
                continue;
            }
            List<UUID> seen = new ArrayList<>();
            UUID cur = start;
            for (int i = 0; i < MAX_ANCESTRY; i++) {
                if (seen.contains(cur)) {
                    // 环上每个成员都标记过，整条环只报一次
                    reported.addAll(seen);
                    out.add(new OntologyDefect(Defect.SUB_PROPERTY_CYCLE, start, null,
                            List.copyOf(seen)));
                    break;
                }
                seen.add(cur);
                UUID p = parentOf.get(cur);
                if (p == null) {
                    break;
                }
                cur = p;
            }
        }

        Map<UUID, List<UUID>> up = adjacency(parents);
        out.addAll(subclassCycles(up));
        out.addAll(unsatisfiable(up, disjoint));
        return out;
    }

    /** 子 → 父的邻接表。同一对重复声明只留一次；排序保证同一份本体每次算出同一条路径。 */
    private static Map<UUID, List<UUID>> adjacency(List<ParentLink> parents) {
        Map<UUID, List<UUID>> up = new LinkedHashMap<>();
        for (ParentLink link : parents) {
            List<UUID> slot = up.computeIfAbsent(link.child(), k -> new ArrayList<>());
            if (!slot.contains(link.parent())) {
                slot.add(link.parent());
            }
        }
        for (List<UUID> v : up.values()) {
            v.sort(UuidLexical::compare);
        }
        return up;
    }

    /** subClassOf 的环（ontology.rs:217-281）。每个环只报一次，键取环上类的有序集合。 */
    private static List<OntologyDefect> subclassCycles(Map<UUID, List<UUID>> up) {
        Set<List<UUID>> reported = new HashSet<>();
        List<OntologyDefect> out = new ArrayList<>();
        List<UUID> starts = new ArrayList<>(up.keySet());
        starts.sort(UuidLexical::compare);
        for (UUID start : starts) {
            climb(start, start, up, new ArrayList<>(), new HashSet<>(), reported, out);
        }
        return out;
    }

    private static void climb(UUID start, UUID at, Map<UUID, List<UUID>> up,
                              List<UUID> path, Set<UUID> onPath,
                              Set<List<UUID>> reported, List<OntologyDefect> out) {
        if (path.size() >= MAX_ANCESTRY) {
            return;
        }
        List<UUID> ups = up.get(at);
        if (ups == null) {
            return;
        }
        for (UUID parent : ups) {
            if (parent.equals(start) && !path.isEmpty()) {
                // 回到起点：这是一个环。path 此刻是 start 之后的那几个类
                List<UUID> ring = new ArrayList<>();
                ring.add(start);
                ring.addAll(path);
                List<UUID> key = new ArrayList<>(new LinkedHashSet<>(ring));
                key.sort(UuidLexical::compare);
                if (reported.add(List.copyOf(key))) {
                    out.add(new OntologyDefect(Defect.SUBCLASS_CYCLE, start, null,
                            List.copyOf(ring)));
                }
                continue;
            }
            if (onPath.contains(parent) || parent.equals(start)) {
                // 别处的环，等它自己那一轮报；这里只是别走进去
                continue;
            }
            path.add(parent);
            onPath.add(parent);
            climb(start, parent, up, path, onPath, reported, out);
            onPath.remove(parent);
            path.remove(path.size() - 1);
        }
    }

    /**
     * 不可满足的类：它的祖先集合里出现了一对互斥（ontology.rs:287-325）。
     *
     * <p>两种形状分开报，因为<b>给人的话不一样</b>：跟自己的祖先互斥是「这条 disjoint
     * 声明写反了」，而两个祖先互斥是「这个类不该同时挂在这两支下」。
     */
    private static List<OntologyDefect> unsatisfiable(Map<UUID, List<UUID>> up,
                                                      List<DisjointPair> disjoint) {
        Set<String> pairs = new HashSet<>();
        for (DisjointPair p : disjoint) {
            pairs.add(pairKey(p.a(), p.b()));
        }
        if (pairs.isEmpty()) {
            return List.of();
        }
        List<OntologyDefect> out = new ArrayList<>();
        List<UUID> classes = new ArrayList<>(up.keySet());
        classes.sort(UuidLexical::compare);
        for (UUID clazz : classes) {
            Set<UUID> anc = ancestors(clazz, up);
            // 一、跟自己的祖先互斥
            for (UUID a : sorted(anc)) {
                if (pairs.contains(pairKey(clazz, a))) {
                    out.add(new OntologyDefect(Defect.DISJOINT_WITH_ANCESTOR, clazz, a,
                            List.of()));
                }
            }
            // 二、两个祖先互相互斥。有序对去重，否则展开成两行的 disjoint 会报两遍
            List<UUID> sorted = sorted(anc);
            for (int i = 0; i < sorted.size(); i++) {
                for (int j = i + 1; j < sorted.size(); j++) {
                    if (pairs.contains(pairKey(sorted.get(i), sorted.get(j)))) {
                        out.add(new OntologyDefect(Defect.INHERITS_DISJOINT, clazz,
                                sorted.get(j), List.of(sorted.get(i))));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Rust 的 {@code HashSet<(Uuid,Uuid)>} 语义：判「这一对是否声明了互斥」。声明侧
     * （导入）把对称性展开成两行，所以按无序对归一成一个键与按有序对直查等价；
     * 单侧手写的行也不漏。
     */
    private static String pairKey(UUID a, UUID b) {
        UUID lo = UuidLexical.compare(a, b) <= 0 ? a : b;
        UUID hi = UuidLexical.compare(a, b) <= 0 ? b : a;
        return lo + "|" + hi;
    }

    /** 一个类的全部祖先（不含自己）。有环也不会转不出来——seen 挡住（ontology.rs:328-345）。 */
    private static Set<UUID> ancestors(UUID clazz, Map<UUID, List<UUID>> up) {
        Set<UUID> seen = new HashSet<>();
        ArrayDeque<Map.Entry<UUID, Integer>> queue = new ArrayDeque<>();
        queue.push(Map.entry(clazz, 0));
        while (!queue.isEmpty()) {
            var e = queue.pop();
            if (e.getValue() >= MAX_ANCESTRY) {
                continue;
            }
            List<UUID> ups = up.get(e.getKey());
            if (ups == null) {
                continue;
            }
            for (UUID parent : ups) {
                if (!parent.equals(clazz) && seen.add(parent)) {
                    queue.push(Map.entry(parent, e.getValue() + 1));
                }
            }
        }
        return seen;
    }

    private static List<UUID> sorted(Set<UUID> ids) {
        List<UUID> out = new ArrayList<>(ids);
        out.sort(UuidLexical::compare);
        return out;
    }
}
