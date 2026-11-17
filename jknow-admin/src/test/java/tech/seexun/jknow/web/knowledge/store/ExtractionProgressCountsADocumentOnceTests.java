package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.DocumentsService;

/**
 * 移植自 tests/store/extraction_progress_counts_a_document_once.rs（1 例）——文库的抽取
 * 进度每篇文档只数一次（#715）：分子分母都只看 graph_status（done 与 extracting），
 * 删掉的不算、抽取失败的单独数。断言逐条不改写。
 */
class ExtractionProgressCountsADocumentOnceTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DocumentsService documents;

    private void document(UUID kb, String name, String graphStatus, boolean deleted) {
        jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256, status, graph_status,"
                        + " deleted_at) VALUES (?, ?, ?, ?, 'ready', ?, CASE WHEN ? THEN now() END)",
                Uuids.nowV7(), kb, name, name, graphStatus, deleted);
    }

    @Test
    void aDocumentStillExtractingIsNotCountedAsDone() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'progress-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'progress-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'progress-test')",
                kb, ws);
        try {
            document(kb, "done.md", "done", false);
            document(kb, "extracting.md", "extracting", false);
            document(kb, "queued.md", "queued", false);
            document(kb, "failed.md", "failed", false);
            // 删掉的文档哪一格都不算，包括它删之前正在抽的
            document(kb, "deleted.md", "extracting", true);
            DocumentsService.DocumentPage page = documents.page(kb, null, null, null, false, 50, 0);
            // 摄入完成的四篇都是 ready——重抽的作用范围不变
            assertEquals(4, page.ready());
            assertEquals(1, page.done());
            assertEquals(2, page.extracting(), "queued 与 extracting 都算在抽");
            assertEquals(1, page.failed());
            // 进度条因此是 done / (done + extracting) = 1 / 3。旧的写法 ready / (ready +
            // extracting) 会是 4 / 6：两篇还没抽完的、一篇抽失败的都被算成了完成
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
