package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;

/**
 * 移植自 tests/store/phrase_signature_evidence.rs（152 行）——签名的陈述数和例句名额不随
 * 证据条数增长（#1096 的另一半）：同一陈述有三条证据也只能占一个例句名额；最早的证据
 * 没有引用位置时，后来的完整位置必须优先。
 */
class PhraseSignatureEvidenceTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PhraseBindingsService phraseBindings;
    @Autowired
    GraphWriteService writes;
    @Autowired
    ResolutionService resolution;

    @Test
    void multipleEvidenceDoesNotMultiplyStatementsOrExamples() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'signature-evidence')", org);
        try {
            jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'signature-evidence')",
                    ws, org);
            jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'signature-evidence')",
                    kb, ws);
            java.util.List<UUID> statements = new java.util.ArrayList<>();
            for (String name : new String[]{"甲", "乙", "丙", "丁"}) {
                UUID subject = resolution.resolveMention(kb, null, name, null, null, null,
                        List.of()).entityId();
                UUID object = resolution.resolveMention(kb, null, "买方", null, null, null,
                        List.of()).entityId();
                statements.add(writes.insertOpenStatement(kb, subject, "supplies", object, null,
                        null, 1.0f).id());
            }
            // 同一陈述有三条证据，也只能占一个例句名额。最早的证据没有引用位置，
            // 后来的完整位置必须优先；只按 chunk_id 排序会选错。
            for (int i = 0; i < 3; i++) {
                UUID doc = Uuids.nowV7();
                UUID chunk = Uuids.nowV7();
                String text = i == 2 ? "前言。甲向买方供货。替代引文" : "前言。甲向买方供货。后记";
                jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256) VALUES (?, ?, ?, ?)",
                        doc, kb, doc.toString(), doc.toString());
                jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES (?, ?, ?, 0, ?)",
                        chunk, kb, doc, text);
                writes.addEvidenceLocated(statements.get(0), chunk,
                        i == 2 ? "替代引文" : "甲向买方供货。", null,
                        switch (i) {
                            case 0 -> null;
                            case 1 -> new int[]{3, 10};
                            default -> new int[]{10, 14};
                        });
                List<PhraseSignature> signatures = phraseBindings.signatures(kb);
                assertEquals(1, signatures.size());
                PhraseSignature s = signatures.get(0);
                assertEquals(4L, s.count(),
                        "after " + (i + 1) + " evidence rows, four statements counted as " + s.count());
                assertEquals(3, s.examples().size());
                assertEquals(3, new HashSet<>(s.examples()).size(),
                        "one statement filled multiple representative slots: " + s.examples());
                assertEquals(i == 0 ? "" : "甲向买方供货。", s.quotes().get(0),
                        "Unicode quote offsets must stay character-based: " + s.quotes());
                assertTrue(s.quotes().subList(1, s.quotes().size()).stream().allMatch(String::isEmpty),
                        "statements without evidence remain eligible");
            }
            jdbc.update("UPDATE facts SET invalidated_at=now() WHERE id = ?", statements.get(0));
            PhraseSignature s = phraseBindings.signatures(kb).get(0);
            assertEquals(3L, s.count());
            assertEquals(3, s.examples().size());
            assertTrue(s.quotes().stream().allMatch(String::isEmpty));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
