package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.OntologyRegressionsService;
import tech.seexun.jknow.store.OntologyRegressionsService.NewCase;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.mapper.OntologyRegressionsMapper.Case;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;

/**
 * 移植自 tests/store/ontology_regressions.rs（committed_decisions_report_matches_and_human_bindings）
 * ——人的例子比较现有对齐账本：提交了的判定按它说的报（passed），人绑定的行报
 * human_bound（护住≠机器独立答对）。
 */
class OntologyRegressionsTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OntologyRegressionsService regressions;
    @Autowired
    PhraseBindingsService phraseBindings;
    @Autowired
    GraphWriteService writes;

    /**
     * Rust 测试开跑都先 {@code db::migrate()}（把共享库补到迁移头）——共享栈常驻的 Rust
     * 实例比迁移集旧时（当前 0106，本例要 0109 的 {@code ontology_regression_cases}），
     * 这里做同一件事：按 sqlx 的簿记（版本/描述/SHA-384 校验和，文件按 LF 字节）把缺的
     * 迁移补上。Java classpath 的 V 文件是 Rust 迁移的逐字拷贝（换行除外），校验和按
     * LF 归一计算——与 Rust sqlx 对同一文件算出的值一致，日后 Rust 实例升级再跑
     * migrate 会按版本跳过、不报校验和冲突。
     */
    @org.junit.jupiter.api.BeforeAll
    void migrateSharedDbToHead() throws Exception {
        if (jdbc.queryForObject("SELECT to_regclass('ontology_regression_cases') IS NOT NULL",
                Boolean.class)) {
            return;
        }
        long applied = jdbc.queryForObject(
                "SELECT coalesce(max(version), 0) FROM _sqlx_migrations", Long.class);
        for (String name : new String[]{
                "V0109__a_human_example_records_the_existing_alignment_outcome.sql",
                "V0110__a_signature_is_embedded_once.sql"}) {
            long version = Long.parseLong(name.substring(1, 5));
            if (version <= applied) {
                continue;
            }
            String description = name.substring(7, name.length() - 4);
            byte[] raw;
            try (var in = getClass().getResourceAsStream("/db/migration/" + name)) {
                raw = in.readAllBytes();
            }
            // LF 归一：sqlx 的校验和按它自己迁移文件的字节（LF）算
            byte[] lf = new String(raw, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-384");
            byte[] checksum = sha.digest(lf);
            long started = System.nanoTime();
            try (var conn = jdbc.getDataSource().getConnection()) {
                conn.setAutoCommit(false);
                try (var stmt = conn.createStatement()) {
                    stmt.execute(new String(lf, java.nio.charset.StandardCharsets.UTF_8));
                }
                try (var ps = conn.prepareStatement(
                        "INSERT INTO _sqlx_migrations (version, description, checksum, execution_time, success)"
                                + " VALUES (?, ?, ?, ?, true)")) {
                    ps.setLong(1, version);
                    ps.setString(2, description);
                    ps.setBytes(3, checksum);
                    ps.setLong(4, (System.nanoTime() - started) / 1_000_000);
                    ps.executeUpdate();
                }
                conn.commit();
            }
        }
    }

    @Test
    void committedDecisionsReportMatchesAndHumanBindings() {
        UUID org = Uuids.nowV7();
        UUID workspace = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID actor = Uuids.nowV7();
        UUID subject = Uuids.nowV7();
        UUID object = Uuids.nowV7();
        UUID property = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?, 'regression-cases')", org);
        try {
            jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, 'regression-cases')",
                    workspace, org);
            jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'regression-cases')",
                    kb, workspace);
            jdbc.update("""
                    CALL jknow_test_user(?, ?, ?, 'Reviewer')
                    """, actor, org, actor + "@example.test");
            for (Object[] row : new Object[][]{{subject, "A"}, {object, "B"}}) {
                jdbc.update("INSERT INTO entities(id,kb_id,canonical_name) VALUES (?, ?, ?)",
                        row[0], kb, row[1]);
            }
            jdbc.update("INSERT INTO relation_types(id,kb_id,key,label) VALUES (?, ?, 'acquired', 'acquired')",
                    property, kb);
            UUID statementId = writes.insertOpenStatement(kb, subject, "acquired", object, null,
                    null, 1.0f).id();
            UUID caseId = regressions.add(kb, new NewCase(statementId, property, "forward", actor,
                    "person"));
            PhraseSignature signature = phraseBindings.signatures(kb).get(0);
            for (Object[] row : new Object[][]{{"forward", "agent", true, false},
                    {"reverse", "agent", false, false}, {"reverse", "person", true, true}}) {
                phraseBindings.decide(kb, signature, new PhraseBindingsService.Decision(
                        property, (String) row[0], "bound",
                        JsonNodeFactory.instance.nullNode(), (String) row[1], null, null, false));
                Case c = regressions.get(kb, caseId);
                assertNotNull(c);
                var report = c.lastResult();
                assertEquals(row[2], report.get("passed").asBoolean());
                assertEquals(row[3], report.get("human_bound").asBoolean());
                assertEquals(row[0], report.get("actual_direction").asText());
                assertNotNull(c.lastCheckedAt());
            }
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
