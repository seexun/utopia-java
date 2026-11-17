package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.SourcesService;
import tech.seexun.jknow.store.entity.SourceRow;

/**
 * 移植自 tests/store/a_schema_document_is_searched_not_extracted.rs（1 例）——来源说了不
 * 抽取的文档，queue_extraction 不排（0035 决定 7，#553：schema 文档「只检索、不学习」，
 * 否则宽表语料上列名全成实体）。过滤在库里，全库重建/按来源重抽/以后任何新入口都走同一条。
 * 断言逐条不改写。
 */
class ASchemaDocumentIsSearchedNotExtractedTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SourcesService sources;
    @Autowired
    DocumentsWriteService documents;

    private UUID readyDocument(UUID kb, UUID source, String name) {
        UUID id = Uuids.nowV7();
        jdbc.update("INSERT INTO documents (id, kb_id, source_id, filename, sha256, status)"
                + " VALUES (?, ?, ?, ?, ?, 'ready')", id, kb, source, name, "sha-" + id);
        return id;
    }

    @Test
    void aSourceThatDoesNotExtractKeepsItsDocumentsOutOfTheQueue() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'skip-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'skip-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'skip-test')",
                kb, ws);
        try {
            // 与 sync_schema_doc 建的那个一模一样：folder，config 里明说不抽取
            SourceRow schemas = sources.create(kb, "folder", "Data schemas",
                    object("extract", false), "database", null, null);
            // 老来源：config 是 {}，照旧抽取
            SourceRow uploads = sources.create(kb, "folder", "Uploads",
                    JsonNodeFactory.instance.objectNode(), null, null, null);
            assertFalse(schemas.extracts());
            assertTrue(uploads.extracts());

            UUID schemaDoc = readyDocument(kb, schemas.id(), "wide-schema.md");
            UUID proseDoc = readyDocument(kb, uploads.id(), "report.md");

            // 全库重建走的就是这一条（sourceId = null）
            List<UUID> queued = documents.queueExtraction(kb, null);
            assertEquals(List.of(proseDoc), queued, "只有普通来源下的文档该被排上");
            assertFalse(queued.contains(schemaDoc));

            // 按来源重抽：来源本身说了不抽取，排出来的是空
            List<UUID> bySource = documents.queueExtraction(kb, schemas.id());
            assertTrue(bySource.isEmpty(), "不抽取的来源按来源重抽也该是空");
        } finally {
            // 只删知识库，不删 org——用户是软删除的，测试也不该造一个产品里不存在的动作
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode object(String key, boolean value) {
        com.fasterxml.jackson.databind.node.ObjectNode n =
                JsonNodeFactory.instance.objectNode();
        n.put(key, value);
        return n;
    }
}
