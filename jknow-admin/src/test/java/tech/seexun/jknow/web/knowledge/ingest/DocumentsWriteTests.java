package tech.seexun.jknow.web.knowledge.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.api.ApiTestBase;

/**
 * documents 写面 HTTP 重建（P1 任务 8；源 DocumentsWriteTests 核心语义——MockMvc+
 * LoginUser 重建，multipart 走 MockMultipartFile）：上传即台账版本 1 + 同事务入队 +
 * blob 共享布局、skip 双语义（空文件/重复内容）与 no_files 422、内容时间从文件名识别。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentsWriteTests extends ApiTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String NOTE = "Alice works at Acme Corp.";

    @Value("${jknow.data-dir}")
    String dataDir;

    private UUID orgId;
    private UUID userId;
    private UUID wsId;
    private UUID kbId;

    @BeforeAll
    void bootstrap() {
        orgId = jdbc.queryForObject(
                "SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        userId = Uuids.nowV7();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Doc Writer')", userId, orgId,
                "t8-docw-" + RUN + "@test.local");
        wsId = Uuids.nowV7();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 't8-docw-ws')",
                wsId, orgId);
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'admin')",
                userId, wsId);
        kbId = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 't8-docw-kb')",
                kbId, wsId);
        jdbc.update("INSERT INTO kb_members (kb_id, user_id, role) VALUES (?, ?, 'admin')", kbId, userId);
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId);
        jdbc.update("CALL jknow_test_user_cleanup(?)", "t8-docw-" + RUN + "@test.local");
    }

    private Resp upload(UUID kb, String filename, String content, boolean withAuth) throws Exception {
        TestSecurityContextHolder.clearContext();
        MockMultipartHttpServletRequestBuilder req =
                (MockMultipartHttpServletRequestBuilder) org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/v1/kbs/" + kb + "/documents")
                        .file(new MockMultipartFile("files", filename, "text/plain",
                                content.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .accept(org.springframework.http.MediaType.APPLICATION_JSON);
        if (withAuth) {
            req = (MockMultipartHttpServletRequestBuilder) req.with(ruoyi(userId));
        }
        var result = mockMvc.perform(req).andReturn();
        String raw = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return new Resp(result.getResponse().getStatus(),
                raw.isEmpty() ? null : mapper.readTree(raw), raw);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void uploadWritesVersionLedgerAndEnqueuesInSameTransaction() throws Exception {
        Resp uploaded = upload(kbId, "t8-note.txt", NOTE + " upload-t8", true);
        assertEquals(200, uploaded.status(), uploaded.raw());
        UUID doc = UUID.fromString(uploaded.body().path("created").get(0).path("id").asText());
        // D9 消除的核心断言：multipart 上传也写 document_versions 版本 1
        assertEquals(1, count("SELECT count(*) FROM document_versions WHERE document_id = ?", doc),
                "上传即台账版本 1");
        assertEquals("pending", text("SELECT status FROM documents WHERE id = ?", doc));
        assertEquals("none", text("SELECT doc_time_source FROM documents WHERE id = ?", doc),
                "无日期就写 none——上传的时刻是记录时间");
        // 同事务入队：process_document 预算 3
        assertEquals(1, count("SELECT count(*) FROM jobs WHERE kind = 'process_document' "
                        + "AND payload->>'document_id' = ? AND status = 'queued' AND max_attempts = 3",
                doc.toString()));
        // blob 落 {data_dir}/files/{sha}（共享布局）
        String sha = text("SELECT sha256 FROM documents WHERE id = ?", doc);
        assertTrue(Files.exists(Path.of(dataDir).resolve("files").resolve(sha)),
                "原文落共享布局 {data_dir}/files/{sha256}");
        // 读侧：versions=[v1]、content 200（按当前 sha 查台账能查到）
        Resp versions = get("/api/v1/documents/" + doc + "/versions", userId);
        assertEquals(200, versions.status());
        assertEquals(1, versions.body().path("versions").size());
        Resp content = get("/api/v1/documents/" + doc + "/content", userId);
        assertEquals(200, content.status(), "content 不再 500：" + content.raw());
        assertTrue(content.raw().startsWith("Alice works at Acme"));
    }

    @Test
    void uploadRecognizesContentTimeFromFilename() throws Exception {
        Resp byName = upload(kbId, "2024-02-29-minutes.txt", "meeting notes", true);
        assertEquals(200, byName.status(), byName.raw());
        UUID doc = UUID.fromString(byName.body().path("created").get(0).path("id").asText());
        assertEquals("content", text("SELECT doc_time_source FROM documents WHERE id = ?", doc),
                "文件名带日期 → content 时间源");
    }

    @Test
    void uploadSkipsEmptyFilesAndDuplicateContent() throws Exception {
        String content = NOTE + " skip-semantics";
        Resp first = upload(kbId, "a.txt", content, true);
        assertEquals(200, first.status(), first.raw());
        assertEquals(1, first.body().path("created").size());

        Resp duplicate = upload(kbId, "b.txt", content, true);
        assertEquals(200, duplicate.status());
        assertEquals(0, duplicate.body().path("created").size());
        assertEquals("duplicate content",
                duplicate.body().path("skipped").get(0).path("reason").asText());
        assertEquals("b.txt", duplicate.body().path("skipped").get(0).path("filename").asText());

        Resp empty = upload(kbId, "empty.txt", "", true);
        assertEquals(200, empty.status());
        assertEquals("empty file", empty.body().path("skipped").get(0).path("reason").asText());
        assertEquals(0, count("SELECT count(*) FROM documents WHERE filename = 'empty.txt' AND kb_id = ?",
                kbId), "空文件不落库");
    }

    @Test
    void uploadWithoutCredentialsIsRejected() throws Exception {
        assertEquals(401, upload(kbId, "noauth.txt", "x", false).code(),
                "无凭据：若依链拒（体 code 401）");
    }
}
