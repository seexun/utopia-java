package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.KindWordSignature;

/**
 * 移植自 tests/store/tied_signatures_come_in_a_fixed_order.rs（208 行）——签名与类别词的
 * 扫描一趟聚完（#1096）：多于三条的组只取最早的三条例句，引文跟着自己的例句走；计数与
 * 短语都相同、两端的类不同的签名按类的 id 定序，每次一样。
 */
class TiedSignaturesComeInAFixedOrderTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PhraseBindingsService phraseBindings;
    @Autowired
    TypeBindingsService typeBindings;
    @Autowired
    GraphWriteService writes;

    private UUID entity(UUID kb, String name, UUID typeId, String kindWord) {
        UUID id = Uuids.nowV7();
        jdbc.update("""
                INSERT INTO entities (id, kb_id, canonical_name, type_id, specific_type)
                 VALUES (?, ?, ?, ?, ?)
                """, id, kb, name, typeId, kindWord);
        return id;
    }

    private UUID say(UUID kb, UUID subject, String phrase, UUID object) {
        return writes.insertOpenStatement(kb, subject, phrase, object, null, null, 1.0f).id();
    }

    private UUID sayValue(UUID kb, UUID subject, String phrase, String value) {
        return writes.insertOpenStatement(kb, subject, phrase, null,
                JsonNodeFactory.instance.objectNode().put("value", value), null, 1.0f).id();
    }

    private record Shape(String phrase, UUID subjectTypeId, UUID objectTypeId, boolean objectIsValue,
                         long count) {
    }

    @Test
    void tiedSignaturesComeInAFixedOrder() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        // Rust 注释「v7 按生成先后递增」：同毫秒内随机位不定，这里显式保证 a < b
        // （PG uuid 按字节序排——与排序键 subject_type_id/object_type_id 的语义一致）
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        if (a.compareTo(b) > 0) {
            UUID swap = a;
            a = b;
            b = swap;
        }
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'tied-signatures')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'tied-signatures')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'tied-signatures')",
                kb, ws);
        jdbc.update("""
                INSERT INTO entity_types(id,kb_id,key,label,color,shape) VALUES
                    (?, ?, 'a', 'A', '#000', 'circle'), (?, ?, 'b', 'B', '#000', 'circle')
                """, a, kb, b, kb);
        try {
            // 类别词 company 下四种写法，各 4、3、2、1 个实体：写法只留前三
            java.util.List<UUID> subjects = new java.util.ArrayList<>();
            for (Object[] row : new Object[][]{{"Company", 4}, {"company", 3}, {"COMPANY", 2},
                    {"CoMpany", 1}}) {
                for (int i = 0; i < (Integer) row[1]; i++) {
                    subjects.add(entity(kb, row[0] + "-" + i, a, (String) row[0]));
                }
            }
            UUID target = entity(kb, "target", b, "place");

            // 一组五条：例句取最早的三条，引文跟着各自的陈述（第一条没有证据，引文为空）
            java.util.List<UUID> supplies = new java.util.ArrayList<>();
            for (UUID s : subjects.subList(0, 5)) {
                supplies.add(say(kb, s, "supplies", target));
            }
            UUID doc = Uuids.nowV7();
            jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256) VALUES (?,?,?,?)",
                    doc, kb, doc.toString(), doc.toString());
            for (int seq = 1; seq < supplies.size(); seq++) {
                UUID chunk = Uuids.nowV7();
                jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES (?,?,?,?,?)",
                        chunk, kb, doc, seq, "q" + seq);
                writes.addEvidenceLocated(supplies.get(seq), chunk, "q" + seq, null,
                        new int[]{0, 2});
            }

            // 三组「owns」各两条，计数与短语都相同，只差两端的类与宾语是不是字面值
            for (UUID s : subjects.subList(0, 2)) {
                say(kb, target, "owns", s); // b → a
                say(kb, s, "owns", target); // a → b
            }
            sayValue(kb, subjects.get(0), "owns", "x"); // a → 字面值
            sayValue(kb, subjects.get(1), "owns", "y");

            List<PhraseSignature> sigs = phraseBindings.signatures(kb);
            List<Shape> shape = sigs.stream()
                    .map(s -> new Shape(s.phrase(), s.subjectTypeId(), s.objectTypeId(),
                            s.objectIsValue(), s.count()))
                    .toList();
            assertEquals(List.of(
                    new Shape("supplies", a, b, false, 5),
                    // 平手按主语的类、宾语的类（空的在后）、宾语是不是字面值
                    new Shape("owns", a, b, false, 2),
                    new Shape("owns", a, null, true, 2),
                    new Shape("owns", b, a, false, 2)), shape);
            PhraseSignature supplied = sigs.get(0);
            assertEquals(supplies.subList(0, 3), supplied.exampleStatementIds());
            assertEquals(List.of("Company-0 —supplies→ target", "Company-1 —supplies→ target",
                    "Company-2 —supplies→ target"), supplied.examples(),
                    "the first three by when they were recorded");
            assertEquals(List.of("", "q1", "q2"), supplied.quotes(),
                    "each quote follows its own example");
            assertEquals(List.of("Company-0 —owns→ x", "Company-1 —owns→ y"), sigs.get(2).examples());
            assertEquals(List.of("", ""), sigs.get(2).quotes());
            for (int i = 0; i < 3; i++) {
                List<String> again = phraseBindings.signatures(kb).stream()
                        .map(s -> s.phrase() + "|" + s.subjectTypeId() + "|" + s.objectTypeId())
                        .toList();
                List<String> first = sigs.stream()
                        .map(s -> s.phrase() + "|" + s.subjectTypeId() + "|" + s.objectTypeId())
                        .toList();
                assertEquals(first, again, "the order holds from call to call");
            }

            List<KindWordSignature> kinds = typeBindings.signatures(kb);
            KindWordSignature company = kinds.stream()
                    .filter(k -> k.kindWord().equals("company")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("no company"));
            assertEquals(10L, company.count());
            assertEquals(List.of("Company", "company", "COMPANY"), company.words(),
                    "the three most frequent spellings");
            assertEquals(List.of("Company-0", "Company-1", "Company-2"), company.examples());
            assertEquals(List.of("supplies", "owns"), company.phrases(),
                    "phrases by how often a company says them");
            assertEquals(List.of("company", "place"),
                    kinds.stream().map(KindWordSignature::kindWord).toList());
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
