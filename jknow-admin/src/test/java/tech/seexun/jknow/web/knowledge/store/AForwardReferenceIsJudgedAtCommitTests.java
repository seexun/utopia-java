package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import tech.seexun.jknow.common.uuids.Uuids;

/**
 * 移植自 tests/store/a_forward_reference_is_judged_at_commit.rs（373 行，0070 §1b）——
 * 同表自指的前向引用（facts.supersedes / from_statement_id / relation_types 的两条链）
 * 在<b>提交边界</b>上判：复合外键 (kb_id, ref) 把「存在且同库」并成一条约束，
 * DEFERRABLE INITIALLY DEFERRED 让它在提交时重估，那时整批都在。
 *
 * <p>等价点：Rust {@code db::migrate} 在共享库上等价于「schema 已由迁移就位」——本测试
 * 连的共享库（15432）由 Rust 实例持续迁移，约束已在；Rust copy_in_raw → JDBC 的
 * CopyManager（同一 COPY 协议路径，copyIn 满批后才 conn.commit()）。
 */
class AForwardReferenceIsJudgedAtCommitTests extends HeartStoreTestBase {

    record Fixture(UUID org, UUID a, UUID b, UUID entA, UUID entB) {
    }

    /** 两个库各一件最小零件：实体——supersedes 的合法写法也要它们。 */
    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'fwdref-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'fwdref-test')", ws,
                org);
        for (UUID kb : List.of(a, b)) {
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)",
                    kb, ws, "fwdref-test");
        }
        UUID entA = Uuids.nowV7();
        UUID entB = Uuids.nowV7();
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'e')", entA, a);
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'e')", entB, b);
        return new Fixture(org, a, b, entA, entB);
    }

    private void cleanup(Fixture f) {
        for (UUID kb : List.of(f.a(), f.b())) {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
        }
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    /**
     * 多行 INSERT：引用行在前、目标行在后——FK 在语句末放行，BEFORE 逐行看时目标还不在。
     * <b>提交边界上的递延约束</b>是抓住它的地方：跨库的过不了，同库的照样落。
     */
    @Test
    void a_multi_row_insert_is_judged_at_commit() {
        Fixture f = seed();
        try {
            // 引用行在前、别库目标行在后：BEFORE 看不见目标，递延约束看得见整批
            UUID newer = Uuids.nowV7();
            UUID older = Uuids.nowV7();
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.update("""
                            INSERT INTO facts (id, kb_id, subject_id, object_id, confidence, supersedes)
                             VALUES (?, ?, ?, ?, 0.9, ?), (?, ?, ?, ?, 0.9, NULL)
                            """, newer, f.a(), f.entA(), f.entA(), older, older, f.b(), f.entB(),
                            f.entB()),
                    "多行 INSERT 里的跨库 supersedes 必须被拒");

            // 同库的同样写法：两行同库，一条语句——合法
            UUID newer2 = Uuids.nowV7();
            UUID older2 = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, object_id, confidence, supersedes)
                     VALUES (?, ?, ?, ?, 0.9, ?), (?, ?, ?, ?, 0.9, NULL)
                    """, newer2, f.a(), f.entA(), f.entA(), older2, older2, f.a(), f.entA(),
                    f.entA());
        } finally {
            cleanup(f);
        }
    }

    /**
     * COPY 是恢复灌库的形状：先放行后校验只在语句提交边界做一次。别库目标排在本批
     * 后面也过不了那道闸；同库的前向链照样进得来。
     */
    @Test
    void a_copy_batch_is_judged_at_commit() throws Exception {
        Fixture f = seed();
        try {
            // 跨库：newer 在前、older 在后，older 属 B 库——提交时被拦
            UUID newer = Uuids.nowV7();
            UUID older = Uuids.nowV7();
            byte[] bad = (newer + "\t" + f.a() + "\t" + f.entA() + "\t" + f.entA() + "\t0.9\t"
                    + older + "\n" + older + "\t" + f.b() + "\t" + f.entB() + "\t" + f.entB()
                    + "\t0.9\t\\N\n").getBytes(StandardCharsets.UTF_8);
            copyBatch(f, bad, true, "COPY 批里的跨库 supersedes 必须在提交时被拦下");

            // 同库：同样的前向顺序，整条链合法
            UUID newer2 = Uuids.nowV7();
            UUID older2 = Uuids.nowV7();
            byte[] good = (newer2 + "\t" + f.a() + "\t" + f.entA() + "\t" + f.entA() + "\t0.9\t"
                    + older2 + "\n" + older2 + "\t" + f.a() + "\t" + f.entA() + "\t" + f.entA()
                    + "\t0.9\t\\N\n").getBytes(StandardCharsets.UTF_8);
            copyBatch(f, good, false, null);
        } finally {
            cleanup(f);
        }
    }

    /** COPY 一批进 facts，然后提交；expectFailure=true 时提交必须被递延外键拦下。 */
    private void copyBatch(Fixture f, byte[] payload, boolean expectFailure, String message)
            throws Exception {
        try (Connection conn = jdbc.getDataSource().getConnection()) {
            conn.setAutoCommit(false);
            CopyManager copy = new CopyManager(conn.unwrap(BaseConnection.class));
            copy.copyIn("COPY public.facts (id, kb_id, subject_id, object_id, confidence, supersedes) FROM stdin",
                    new ByteArrayInputStream(payload));
            if (expectFailure) {
                assertThrows(SQLException.class, conn::commit, message);
                conn.rollback();
            } else {
                conn.commit();
            }
        }
    }

    /**
     * 顺序写的前向引用同样归到提交边界：递延外键让「先插引用、后插目标」在事务里合法，
     * 目标始终不到的写法在 COMMIT 被拦下。
     */
    @Test
    void a_sequential_forward_reference_is_judged_at_commit() throws Exception {
        Fixture f = seed();
        try {
            // 目标始终不到：语句放行、提交时被拦
            UUID newer = Uuids.nowV7();
            UUID older = Uuids.nowV7();
            try (Connection conn = jdbc.getDataSource().getConnection()) {
                conn.setAutoCommit(false);
                conn.createStatement().execute("""
                        INSERT INTO facts (id, kb_id, subject_id, object_id, confidence, supersedes)
                         VALUES ('%s', '%s', '%s', '%s', 0.9, '%s')
                        """.formatted(newer, f.a(), f.entA(), f.entA(), older));
                assertThrows(SQLException.class, conn::commit,
                        "目标始终不到的 supersedes 顺序写，提交时必须报");
                conn.rollback();
            }

            // 同事务里目标后到：整条链在提交时齐了——合法
            UUID newer2 = Uuids.nowV7();
            UUID older2 = Uuids.nowV7();
            try (Connection conn = jdbc.getDataSource().getConnection()) {
                conn.setAutoCommit(false);
                conn.createStatement().execute("""
                        INSERT INTO facts (id, kb_id, subject_id, object_id, confidence, supersedes)
                         VALUES ('%s', '%s', '%s', '%s', 0.9, '%s')
                        """.formatted(newer2, f.a(), f.entA(), f.entA(), older2));
                conn.createStatement().execute("""
                        INSERT INTO facts (id, kb_id, subject_id, object_id, confidence)
                         VALUES ('%s', '%s', '%s', '%s', 0.9)
                        """.formatted(older2, f.a(), f.entA(), f.entA()));
                conn.commit();
            }
        } finally {
            cleanup(f);
        }
    }

    /**
     * UPDATE 装上的跨库 supersedes：目标已存在，BEFORE 逐行检查看得见它——当场就报；
     * 真漏过去的那一层，递延约束在提交时兜底。
     */
    @Test
    void an_update_to_a_foreign_supersedes_fails() throws Exception {
        Fixture f = seed();
        try {
            UUID fa = Uuids.nowV7();
            UUID fb = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, object_id, confidence)
                     VALUES (?, ?, ?, ?, 0.9), (?, ?, ?, ?, 0.9)
                    """, fa, f.a(), f.entA(), f.entA(), fb, f.b(), f.entB(), f.entB());

            try (Connection conn = jdbc.getDataSource().getConnection()) {
                conn.setAutoCommit(false);
                boolean updated;
                try {
                    conn.createStatement().execute(
                            "UPDATE facts SET supersedes = '" + fb + "' WHERE id = '" + fa + "'");
                    updated = true;
                } catch (SQLException e) {
                    updated = false;
                }
                if (updated) {
                    assertThrows(SQLException.class, conn::commit,
                            "UPDATE 装上的跨库 supersedes 最迟在提交时要报");
                }
                conn.rollback();
            }
        } finally {
            cleanup(f);
        }
    }

    /**
     * from_statement_id 是同一张表上的第二条自指边：陈述行在批尾、类型化事实在批头的
     * 跨库写法，提交时一样被拦。
     */
    @Test
    void a_from_statement_is_judged_at_commit() {
        Fixture f = seed();
        try {
            // 类型化事实在前、别库陈述在后：BEFORE 看不见目标，递延约束看得见整批
            UUID typed = Uuids.nowV7();
            UUID stmt = Uuids.nowV7();
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.update("""
                            INSERT INTO facts (id, kb_id, subject_id, object_id, confidence,
                                                layer, phrase, from_statement_id)
                             VALUES (?, ?, ?, ?, 0.9, 'typed', NULL, ?),
                                    (?, ?, ?, ?, 0.9, 'open', 'joined', NULL)
                            """, typed, f.a(), f.entA(), f.entA(), stmt, stmt, f.b(), f.entB(),
                            f.entB()),
                    "多行 INSERT 里的跨库 from_statement_id 必须被拒");

            // 同库的同样写法：类型化事实在前、同库陈述在后——合法
            UUID typed2 = Uuids.nowV7();
            UUID stmt2 = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, object_id, confidence,
                                        layer, phrase, from_statement_id)
                     VALUES (?, ?, ?, ?, 0.9, 'typed', NULL, ?),
                            (?, ?, ?, ?, 0.9, 'open', 'joined', NULL)
                    """, typed2, f.a(), f.entA(), f.entA(), stmt2, stmt2, f.a(), f.entA(),
                    f.entA());
        } finally {
            cleanup(f);
        }
    }

    /**
     * 关系的同表自指同一条边界：inverse_of / sub_property_of 引用行在前、目标行在后——
     * 别库的在提交时被拦，同库的放行。
     */
    @Test
    void relation_self_links_are_judged_at_commit() {
        Fixture f = seed();
        try {
            // inverse_of：一条语句里 A 库的谓词在前、B 库的目标在后——提交时报
            UUID inv = Uuids.nowV7();
            UUID tgt = Uuids.nowV7();
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> jdbc.update("""
                            INSERT INTO relation_types (id, kb_id, key, label, inverse_of)
                             VALUES (?, ?, 'inv', 'inv', ?), (?, ?, 'tgt', 'tgt', NULL)
                            """, inv, f.a(), tgt, tgt, f.b()),
                    "多行 INSERT 里的跨库 inverse_of 必须被拒");

            // sub_property_of 同库、目标行在后：合法
            UUID child = Uuids.nowV7();
            UUID parent = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, sub_property_of)
                     VALUES (?, ?, 'child', 'child', ?), (?, ?, 'parent', 'parent', NULL)
                    """, child, f.a(), parent, parent, f.a());
            assertTrue(true, "同库前向链进得来");
        } finally {
            cleanup(f);
        }
    }
}
