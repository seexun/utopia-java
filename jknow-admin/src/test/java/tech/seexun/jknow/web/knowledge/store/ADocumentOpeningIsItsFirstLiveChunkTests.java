package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper.ChunkForExtract;

/**
 * 移植自 tests/store/a_document_opening_is_its_first_live_chunk.rs（1 例）——提示词里附的
 * 「文件开头」是序号最小的<b>现存</b>分块，不是还没抽的第一块（改过的文件重抽第 3、7 块时，
 * 第 7 块不该把第 3 块当成开头）。断言逐条不改写。
 */
class ADocumentOpeningIsItsFirstLiveChunkTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentsWriteMapper writes;

    @Test
    void theOpeningIsTheFirstLiveChunkWhetherOrNotItWasExtracted() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'opening-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'opening-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'opening-test')",
                kb, ws);
        try {
            jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256)"
                    + " VALUES (?, ?, 'a.html', 'a')", doc, kb);
            UUID superseded = Uuids.nowV7();
            UUID first = Uuids.nowV7();
            // 第 0 块是上一版留下、已被取代的；第 1 块抽过了；第 3、7 块等着抽
            for (Object[] row : new Object[][]{
                    {superseded, 0, true, true},
                    {first, 1, true, false},
                    {Uuids.nowV7(), 3, false, false},
                    {Uuids.nowV7(), 7, false, false}}) {
                jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text, extracted_at,"
                                + " superseded_at) VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN now() END,"
                                + " CASE WHEN ? THEN now() END)",
                        row[0], kb, doc, row[1], "chunk " + row[1], row[2], row[3]);
            }
            List<ChunkForExtract> pending = writes.selectChunksForExtraction(doc);
            assertEquals(3, pending.get(0).seq(), "等着抽的第一块是第 3 块");
            DocumentsWriteMapper.OpeningChunk opening = writes.selectOpeningChunk(doc);
            assertEquals(first, opening.id(), "开头是现存的第 1 块，抽没抽过都一样");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
            jdbc.update("DELETE FROM workspaces WHERE id = ?", ws);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
