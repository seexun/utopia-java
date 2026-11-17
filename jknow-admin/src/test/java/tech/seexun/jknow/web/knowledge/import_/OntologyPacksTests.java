package tech.seexun.jknow.web.knowledge.import_;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 预置本体包（ontology_packs.rs 内联 tests——断言逐字，资源载体换 classpath）。 */
class OntologyPacksTests {

    /** 每个包都能解压，且解出来的不是空文件（gzip 有效）。 */
    @Test
    void every_pack_decompresses() {
        for (OntologyPacks.Pack p : OntologyPacks.PACKS) {
            byte[] b = OntologyPacks.bytes(p);
            assertTrue(b.length > 10_000, p.id() + " 解出来只有 " + b.length + " 字节");
        }
    }

    /** 文件名后缀决定格式判定，写错了整个包会被当成另一种语法送进解析器。 */
    @Test
    void filenames_carry_a_format_suffix() {
        for (OntologyPacks.Pack p : OntologyPacks.PACKS) {
            assertTrue(p.filename().endsWith(".ttl") || p.filename().endsWith(".rdf"),
                    p.id() + " 的文件名没有可判定的后缀：" + p.filename());
        }
    }

    @Test
    void ids_are_unique() {
        List<String> ids = new ArrayList<>();
        OntologyPacks.PACKS.forEach(p -> ids.add(p.id()));
        long distinct = ids.stream().distinct().count();
        assertTrue(ids.size() == (int) distinct, "包 id 有重复");
    }
}
