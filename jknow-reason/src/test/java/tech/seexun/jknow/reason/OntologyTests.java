package tech.seexun.jknow.reason;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.reason.Ontology.Defect;
import tech.seexun.jknow.reason.Ontology.DisjointPair;
import tech.seexun.jknow.reason.Ontology.OntologyDefect;
import tech.seexun.jknow.reason.Ontology.ParentLink;

/**
 * 移植自 utopia-reason/src/ontology.rs 内联 mod tests（10 例）+ mod
 * inverse_and_sub_property_tests（6 例），共 16 例——本体自己的自洽性：
 * 谓词公理互斥、subClassOf/subPropertyOf 环、不可满足的类、逆的相互性。
 */
class OntologyTests {

    private static UUID c(int i) {
        byte[] arr = new byte[16];
        Arrays.fill(arr, (byte) i);
        ByteBuffer buf = ByteBuffer.wrap(arr);
        return new UUID(buf.getLong(), buf.getLong());
    }

    private static List<Defect> kinds(List<OntologyDefect> v) {
        List<Defect> k = new ArrayList<>(v.stream().map(OntologyDefect::kind).toList());
        k.sort(Comparator.comparing(Defect::asString));
        return k;
    }

    /** 测试造数：单谓词 c(99) 的公理（Rust ax() 闭包）。 */
    private static Map<UUID, Axioms> ax(java.util.function.Consumer<AxiomsBuilder> f) {
        AxiomsBuilder b = new AxiomsBuilder();
        f.accept(b);
        return Map.of(c(99), b.build());
    }

    /** Rust {@code Axioms{..Default::default()}} 的可变构建形态（仅测试用）。 */
    static final class AxiomsBuilder {
        boolean transitive;
        boolean symmetric;
        boolean asymmetric;
        boolean irreflexive;
        boolean functional;
        boolean inverseFunctional;
        UUID inverseOf;
        UUID subPropertyOf;

        Axioms build() {
            return new Axioms(transitive, symmetric, asymmetric, irreflexive, functional,
                    inverseFunctional, inverseOf, subPropertyOf);
        }
    }

    private static List<ParentLink> parents(UUID... pairs) {
        List<ParentLink> out = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.add(new ParentLink(pairs[i], pairs[i + 1]));
        }
        return out;
    }

    // ---- mod tests（10 例） ----

    @Test
    void aPropertyCannotBeBothSymmetricAndAsymmetric() {
        Map<UUID, Axioms> a = ax(x -> {
            x.symmetric = true;
            x.asymmetric = true;
        });
        List<OntologyDefect> d = Ontology.checkOntology(a, List.of(), List.of());
        assertEquals(List.of(Defect.SYMMETRIC_AND_ASYMMETRIC), kinds(d));
        assertEquals(c(99), d.get(0).subject());
    }

    @Test
    void eitherOneAloneIsFine() {
        assertTrue(Ontology.checkOntology(ax(x -> x.symmetric = true), List.of(), List.of())
                .isEmpty());
        assertTrue(Ontology.checkOntology(ax(x -> x.asymmetric = true), List.of(), List.of())
                .isEmpty());
        // 传递 + 反对称是**正常的**——它正是环检测有意义的前提
        Map<UUID, Axioms> both = ax(x -> {
            x.transitive = true;
            x.asymmetric = true;
        });
        assertTrue(Ontology.checkOntology(both, List.of(), List.of()).isEmpty());
    }

    @Test
    void transitiveAndFunctionalIsForbiddenByOwl2() {
        Map<UUID, Axioms> a = ax(x -> {
            x.transitive = true;
            x.functional = true;
        });
        assertEquals(List.of(Defect.TRANSITIVE_AND_FUNCTIONAL),
                kinds(Ontology.checkOntology(a, List.of(), List.of())));
        // 反函数性同理
        Map<UUID, Axioms> b = ax(x -> {
            x.transitive = true;
            x.inverseFunctional = true;
        });
        assertEquals(List.of(Defect.TRANSITIVE_AND_FUNCTIONAL),
                kinds(Ontology.checkOntology(b, List.of(), List.of())));
    }

    @Test
    void subclassOfCanFormARing() {
        Map<UUID, Axioms> none = new HashMap<>();
        // 1 → 2 → 3 → 1
        List<ParentLink> ring = parents(c(1), c(2), c(2), c(3), c(3), c(1));
        List<OntologyDefect> d = Ontology.checkOntology(none, ring, List.of());
        assertEquals(List.of(Defect.SUBCLASS_CYCLE), kinds(d), "三个类绕成一圈");
        assertEquals(1, d.size(), "同一个环只报一次，不是每个类各报一次");
        assertEquals(3, d.get(0).path().size(), "路径要带上环上全部三个类");
    }

    @Test
    void aTreeIsNotARing() {
        Map<UUID, Axioms> none = new HashMap<>();
        // 多父也不是环：4 同时挂在 2 与 3 下
        List<ParentLink> tree = parents(c(1), c(2), c(1), c(3), c(4), c(2), c(4), c(3));
        assertTrue(Ontology.checkOntology(none, tree, List.of()).isEmpty());
    }

    @Test
    void aClassDisjointWithItsOwnAncestorCanNeverExist() {
        Map<UUID, Axioms> none = new HashMap<>();
        List<ParentLink> ps = parents(c(1), c(2), c(2), c(3));
        // 导入侧把 disjoint 的对称性展开成两行，这里照样给两行
        List<DisjointPair> dis = List.of(new DisjointPair(c(1), c(3)),
                new DisjointPair(c(3), c(1)));
        List<OntologyDefect> d = Ontology.checkOntology(none, ps, dis);
        assertEquals(List.of(Defect.DISJOINT_WITH_ANCESTOR), kinds(d));
        assertEquals(c(1), d.get(0).subject());
        assertEquals(c(3), d.get(0).other(), "指出跟哪个祖先互斥——人要去改那一条");
    }

    @Test
    void twoDisjointAncestorsMakeAClassUnsatisfiable() {
        Map<UUID, Axioms> none = new HashMap<>();
        // 1 同时是 2 与 3 的子类，而 2 与 3 互斥
        List<ParentLink> ps = parents(c(1), c(2), c(1), c(3));
        List<DisjointPair> dis = List.of(new DisjointPair(c(2), c(3)),
                new DisjointPair(c(3), c(2)));
        List<OntologyDefect> d = Ontology.checkOntology(none, ps, dis);
        assertEquals(List.of(Defect.INHERITS_DISJOINT), kinds(d));
        assertEquals(1, d.size(), "展开成两行的 disjoint 不该报两遍");
        assertEquals(c(1), d.get(0).subject());
    }

    @Test
    void disjointBetweenUnrelatedBranchesIsThePointOfDisjoint() {
        Map<UUID, Axioms> none = new HashMap<>();
        // 2 与 3 互斥，而 1 只挂在 2 下、4 只挂在 3 下——这正是 disjoint 的正常用法
        List<ParentLink> ps = parents(c(1), c(2), c(4), c(3));
        List<DisjointPair> dis = List.of(new DisjointPair(c(2), c(3)),
                new DisjointPair(c(3), c(2)));
        assertTrue(Ontology.checkOntology(none, ps, dis).isEmpty());
    }

    @Test
    void aRingDoesNotHangTheAncestorWalk() {
        Map<UUID, Axioms> none = new HashMap<>();
        List<ParentLink> ring = parents(c(1), c(2), c(2), c(1));
        List<DisjointPair> dis = List.of(new DisjointPair(c(1), c(2)),
                new DisjointPair(c(2), c(1)));
        // 环 + 互斥同时存在：既要报环，也不能在上溯时转不出来
        List<OntologyDefect> d = Ontology.checkOntology(none, ring, dis);
        assertTrue(d.stream().anyMatch(x -> x.kind() == Defect.SUBCLASS_CYCLE));
        assertTrue(d.stream().anyMatch(x -> x.kind() == Defect.DISJOINT_WITH_ANCESTOR));
    }

    @Test
    void nothingDeclaredMeansNothingReported() {
        assertTrue(Ontology.checkOntology(new HashMap<>(), List.of(), List.of()).isEmpty());
        // 类层级齐全但一条 disjoint 都没有 → 没有判据
        List<ParentLink> ps = parents(c(1), c(2), c(2), c(3));
        assertTrue(Ontology.checkOntology(new HashMap<>(), ps, List.of()).isEmpty());
    }

    // ---- mod inverse_and_sub_property_tests（6 例） ----

    /** 自己是自己的逆 —— 合法但绕远路，提示改用 symmetric。 */
    @Test
    void aPredicateThatIsItsOwnInverseShouldJustSaySymmetric() {
        Map<UUID, Axioms> a = Map.of(c(1),
                new Axioms(false, false, false, false, false, false, c(1), null));
        List<OntologyDefect> d = Ontology.checkOntology(a, List.of(), List.of());
        assertEquals(List.of(Defect.INVERSE_OF_ITSELF), kinds(d));
    }

    /** 自己的逆 + 反对称 = 与 symmetric+asymmetric 同一个矛盾，换了个写法。 */
    @Test
    void itsOwnInverseAndAsymmetricIsTheSameContradictionInDisguise() {
        Map<UUID, Axioms> a = Map.of(c(1),
                new Axioms(false, false, true, false, false, false, c(1), null));
        List<OntologyDefect> d = Ontology.checkOntology(a, List.of(), List.of());
        assertTrue(kinds(d).contains(Defect.SYMMETRIC_AND_ASYMMETRIC),
                "**要报成同一类**：读的人不该因为写法不同就以为是两回事");
    }

    /** 互指一致 —— 干净，什么都不该报。 */
    @Test
    void aMutualPairIsClean() {
        Map<UUID, Axioms> ax = Map.of(
                c(1), new Axioms(false, false, false, false, false, false, c(2), null),
                c(2), new Axioms(false, false, false, false, false, false, c(1), null));
        assertTrue(Ontology.checkOntology(ax, List.of(), List.of()).isEmpty());
    }

    /**
     * p⁻¹ = q 而 q⁻¹ = r —— 两边指得不一样。<b>载入公理时只补空缺不覆盖</b>，
     * 所以这个矛盾不会被悄悄抹平；它必须在这里被报出来，否则没有任何地方会提。
     */
    @Test
    void anInverseThatDoesNotPointBackIsReported() {
        Map<UUID, Axioms> ax = Map.of(
                c(1), new Axioms(false, false, false, false, false, false, c(2), null),
                c(2), new Axioms(false, false, false, false, false, false, c(3), null));
        List<OntologyDefect> d = Ontology.checkOntology(ax, List.of(), List.of());
        OntologyDefect one = d.stream().filter(x -> x.kind() == Defect.INVERSE_NOT_MUTUAL)
                .findFirst().orElseThrow(() -> new AssertionError("该报 InverseNotMutual"));
        assertEquals(c(1), one.subject());
        assertEquals(c(2), one.other());
        assertIterableEquals(List.of(c(1), c(2), c(3)), one.path(), "路径要说清指到哪去了");
    }

    /** subPropertyOf 成环。 */
    @Test
    void aSubPropertyRingIsASinglePredicateWearingThreeHats() {
        Map<UUID, Axioms> ax = Map.of(
                c(1), new Axioms(false, false, false, false, false, false, null, c(2)),
                c(2), new Axioms(false, false, false, false, false, false, null, c(3)),
                c(3), new Axioms(false, false, false, false, false, false, null, c(1)));
        List<OntologyDefect> d = Ontology.checkOntology(ax, List.of(), List.of());
        List<OntologyDefect> ring = d.stream()
                .filter(x -> x.kind() == Defect.SUB_PROPERTY_CYCLE).toList();
        assertEquals(1, ring.size(), "**整条环只报一次**，不是每个成员报一遍");
        assertEquals(3, ring.get(0).path().size());
    }

    /** 一条不成环的链不该被误报。 */
    @Test
    void aChainThatEndsIsNotARing() {
        Map<UUID, Axioms> ax = Map.of(
                c(1), new Axioms(false, false, false, false, false, false, null, c(2)),
                c(2), new Axioms(false, false, false, false, false, false, null, c(3)));
        assertTrue(Ontology.checkOntology(ax, List.of(), List.of()).isEmpty());
    }
}
