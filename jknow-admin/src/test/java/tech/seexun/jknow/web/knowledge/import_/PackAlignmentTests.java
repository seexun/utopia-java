package tech.seexun.jknow.web.knowledge.import_;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.import_.OwlProjector.OwlProjection;
import tech.seexun.jknow.web.knowledge.import_.OwlProjector.RdfFormat;
import tech.seexun.jknow.web.knowledge.import_.PackAlignment.Alignment;

/** 包间同名词处置（pack_alignment.rs 内联 tests + against_real_packs——断言逐字）。 */
class PackAlignmentTests {

    // 命名空间前缀只在测试里拼 IRI 用；表里写的是完整 IRI，因为那是要跟投影结果逐字符
    // 比对的东西，拼接会掩盖笔误
    private static final String SCHEMA = "https://schema.org/";
    private static final String ORG = "http://www.w3.org/ns/org#";
    private static final String PROV = "http://www.w3.org/ns/prov#";
    private static final String FOAF = "http://xmlns.com/foaf/0.1/";

    @Test
    void same_as_and_rename_are_both_reachable() {
        assertEquals(Alignment.SAME_AS,
                PackAlignment.lookup(ORG + "Organization", SCHEMA + "Organization").alignment());
        PackAlignment.Result rename = PackAlignment.lookup(ORG + "Role", SCHEMA + "Role");
        assertEquals(Alignment.RENAME, rename.alignment());
        assertEquals("org_role", rename.renameKey());
    }

    /** 不在表里的组合必须落回 KeyTaken——默认是报告冲突，不是猜。 */
    @Test
    void unknown_pairs_fall_through() {
        assertNull(PackAlignment.lookup("http://example.com/a#Foo", SCHEMA + "Foo"));
        assertNull(PackAlignment.lookup(PROV + "Entity", "http://example.com/b#Entity"));
    }

    /** 方向敏感：表是 (进来的, 已占的)，反过来查不到。装包顺序不同时命中的是不同的
     * 条目，不该靠对称性蒙混。 */
    @Test
    void lookup_is_directional() {
        assertNull(PackAlignment.lookup(SCHEMA + "Organization", ORG + "Organization"));
    }

    /** Rename 出来的 key 必须过 validate_key 那套约束：小写字母数字下划线、不超 40。 */
    @Test
    void renamed_keys_are_valid() {
        for (var e : PackAlignment.tableEntries()) {
            if (e.alignment() == Alignment.RENAME) {
                String k = e.renameKey();
                assertTrue(k.length() <= 40, k + " 超过 40 字符");
                assertTrue(k.chars().allMatch(c -> Character.isLowerCase(c)
                        || Character.isDigit(c) || c == '_'), k + " 含非法字符");
            }
        }
    }

    @Test
    void no_duplicate_pairs() {
        Set<String> seen = new HashSet<>();
        for (var e : PackAlignment.tableEntries()) {
            assertTrue(seen.add(e.incoming() + "→" + e.existing()), "对齐表里有重复的组合");
        }
    }

    /** FOAF 常量在测试外没被引用，这里确认它拼对了。 */
    @Test
    void foaf_namespace_is_used() {
        assertNotNull(PackAlignment.lookup(FOAF + "Person", SCHEMA + "Person"));
    }

    /**
     * 对齐表里的 IRI 必须真的出现在包里（against_real_packs）。表是手写的字符串，
     * 写错一个字符它就静默失效——撞名照样走 KeyTaken，只是再也不会命中。
     */
    @Test
    void every_alignment_iri_exists_in_some_pack() {
        Set<String> iris = new HashSet<>();
        for (OntologyPacks.Pack p : OntologyPacks.PACKS) {
            byte[] bytes = OntologyPacks.bytes(p);
            OwlProjection proj = OwlProjector.project(bytes,
                    OwlProjector.detect(p.filename(), bytes));
            proj.classes().forEach(c -> iris.add(c.iri()));
            proj.properties().forEach(x -> iris.add(x.iri()));
        }
        assertFalse(iris.isEmpty());
        for (var e : PackAlignment.tableEntries()) {
            assertTrue(iris.contains(e.incoming()), "对齐表的进来的 IRI 没有任何包里有: "
                    + e.incoming());
            assertTrue(iris.contains(e.existing()), "对齐表的已占 IRI 没有任何包里有: "
                    + e.existing());
        }
    }

    /** 真包四例的格式判定也顺手钉住（.rdf/.ttl 走扩展名强信号）。 */
    @Test
    void pack_filenames_pick_the_right_format() {
        assertEquals(RdfFormat.TURTLE,
                OwlProjector.detect("schema-org.ttl", new byte[] {'@'}));
        assertEquals(RdfFormat.RDFXML, OwlProjector.detect("foaf.rdf", new byte[] {'<'}));
    }
}
