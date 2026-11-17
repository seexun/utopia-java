package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;


import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自顶层 tests/a_corrected_row_says_which_end_changed.rs（247 行）——迁移 0099：
 * 这一列之前人改过区间的行，从审计里算出改的是哪一端。照修正路由的来路造
 * （correct_interval 改区间 + 审计记 detail.from/to），再把这一列抹掉重跑迁移。
 * 等价点：迁移 SQL 从 classpath 读 V0090（Rust include_str! 同一份文件的对等移植；
 * Flyway 文件体逐字）；audit::record 经 M2 的 AuditService（同 SQL 同 detail 形状）。
 */
class ACorrectedRowSaysWhichEndChangedTests extends HeartStoreTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    GraphWriteService writes;
    @Autowired
    TemporalService temporal;
    @Autowired
    AuditService audit;

    private static OffsetDateTime day(String s) {
        return OffsetDateTime.parse(s + "T00:00:00Z");
    }

    private static Validity between(String from, String to) {
        return new Validity(day(from), "day", to == null ? null : day(to), to == null ? null : "day",
                null, null);
    }

    /** 修正路由记下的一端：与它一样用 RFC 3339 写日期。 */
    private static JsonNode side(Validity v) {
        ObjectNode n = JSON.createObjectNode();
        n.put("valid_from", v.from == null ? null : v.from.toString());
        n.put("valid_from_precision", v.fromPrecision);
        n.put("valid_to", v.to == null ? null : v.to.toString());
        n.put("valid_to_precision", v.toPrecision);
        return n;
    }

    /** 一行活着的那一版的标记。 */
    private String ends(UUID kb, UUID subject) {
        return jdbc.queryForObject("""
                SELECT corrected_ends FROM facts
                  WHERE kb_id = ? AND subject_id = ? AND invalidated_at IS NULL
                """, String.class, kb, subject);
    }

    @Test
    void a_row_corrected_before_the_column_gets_its_ends_back() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID project = Uuids.nowV7();
        UUID leads = Uuids.nowV7();
        UUID[] people = new UUID[6];
        for (int i = 0; i < people.length; i++) {
            people[i] = Uuids.nowV7();
        }
        UUID aurora = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'corrected-ends')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'corrected-ends')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'corrected-ends')",
                kb, ws);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'editor')
                """, user, org, user + "@ends.test");
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label, color, shape) VALUES (?, ?, 'person', 'person', '#7fd0ff', 'circle')",
                person, kb);
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label, color, shape) VALUES (?, ?, 'project', 'project', '#7fd0ff', 'circle')",
                project, kb);
        jdbc.update(
                "INSERT INTO relation_types (id, kb_id, key, label, temporal) VALUES (?, ?, 'leads', 'leads', 'state')",
                leads, kb);
        jdbc.update(
                "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, 'Aurora')",
                aurora, kb, project);
        String[] names = {"Zhang San", "Li Si", "Wang Wu", "Zhao Liu", "Qian Qi", "Sun Ba"};
        for (int i = 0; i < names.length; i++) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    people[i], kb, person, names[i]);
        }
        UUID zhang = people[0];
        UUID li = people[1];
        UUID wang = people[2];
        UUID zhao = people[3];
        UUID qian = people[4];
        UUID sun = people[5];

        try {
            java.util.function.BiFunction<UUID, Validity, UUID> row0 = (who, v) ->
                    writes.insertFact(kb, who, leads, aurora, v, 0.9f).id();
            // 人改一次区间：修正行，加上修正路由记在被改的那一行上的审计
            java.util.function.Function<UUID, UUID> correct0 = fact -> {
                UUID corrected = temporal.correctInterval(fact,
                        between("2023-02-01", "2024-07-05"));
                assertNotNull(corrected, "the row was live");
                ObjectNode detail = JSON.createObjectNode();
                detail.set("from", side(between("2023-01-10", "2024-07-05")));
                detail.set("to", side(between("2023-02-01", "2024-07-05")));
                audit.record(new ClientContext(null, null), kb, user, "fact.time_corrected", "fact", fact,
                        detail.toString());
                return corrected;
            };

            Validity first = between("2023-01-10", "2024-07-05");
            UUID z = row0.apply(zhang, first);
            z = correct0.apply(z);
            temporal.closeSuperseded(z, day("2024-06-30"), "day");

            UUID l = row0.apply(li, first);
            Validity moved = between("2023-02-01", "2024-07-05");
            l = correct0.apply(l);
            // 再改终点（第二次：改前=moved）
            UUID l2 = temporal.correctInterval(l, between("2023-02-01", "2024-08-01"));
            assertNotNull(l2);
            ObjectNode detail2 = JSON.createObjectNode();
            detail2.set("from", side(moved));
            detail2.set("to", side(between("2023-02-01", "2024-08-01")));
            audit.record(new ClientContext(null, null), kb, user, "fact.time_corrected", "fact", l,
                    detail2.toString());

            UUID w = row0.apply(wang, first);
            UUID w2 = temporal.correctInterval(w, between("2023-01-10", "2024-06-01"));
            assertNotNull(w2);
            ObjectNode detailW = JSON.createObjectNode();
            detailW.set("from", side(first));
            detailW.set("to", side(between("2023-01-10", "2024-06-01")));
            audit.record(new ClientContext(null, null), kb, user, "fact.time_corrected", "fact", w,
                    detailW.toString());

            Validity open = between("2023-01-10", null);
            UUID h = row0.apply(zhao, open);
            temporal.closeSuperseded(h, day("2024-01-01"), "day");

            UUID q = row0.apply(qian, first);
            UUID q2 = temporal.correctInterval(q, between("2023-03-01", "2024-07-05"));
            assertNotNull(q2);
            // 审计没记改前改后（detail 空）
            audit.record(new ClientContext(null, null), kb, user, "fact.time_corrected", "fact", q, "{}");

            // 时间线推出来的终点（这里直接标上），人只改起点、终点照抄
            UUID s = row0.apply(sun, first);
            jdbc.update("UPDATE facts SET end_derived = TRUE WHERE id = ?", s);
            UUID s2 = temporal.correctInterval(s, between("2023-02-01", "2024-07-05"));
            assertNotNull(s2);
            ObjectNode detailS = JSON.createObjectNode();
            detailS.set("from", side(first));
            detailS.set("to", side(between("2023-02-01", "2024-07-05")));
            audit.record(new ClientContext(null, null), kb, user, "fact.time_corrected", "fact", s,
                    detailS.toString());

            String[] now = new String[people.length];
            for (int i = 0; i < people.length; i++) {
                now[i] = ends(kb, people[i]);
            }
            assertEquals(java.util.Arrays.asList("start", "both", "end", null, "start", "both"),
                    java.util.Arrays.asList(now), "what correct_interval wrote");

            // 这一列出现之前的样子：没有标记。王五那一行已经有一个（与审计说的不一样）的
            // 标记，回填不动它
            jdbc.update("UPDATE facts SET corrected_ends = NULL WHERE kb_id = ? AND subject_id <> ?",
                    kb, wang);
            jdbc.update("""
                    UPDATE facts SET corrected_ends = 'both'
                      WHERE kb_id = ? AND subject_id = ? AND invalidated_at IS NULL
                    """, kb, wang);

            String migration;
            try (InputStream in = new ClassPathResource(
                    "db/migration/V0090__a_corrected_row_says_which_end_a_person_changed.sql")
                    .getInputStream()) {
                migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            jdbc.execute(migration);
            assertEquals("start", ends(kb, zhang));
            assertEquals("both", ends(kb, li));
            assertEquals("both", ends(kb, wang), "a row that has its mark keeps it");
            assertEquals(null, ends(kb, zhao), "closed by hand");
            assertEquals("both", ends(kb, qian),
                    "an audit without the two intervals cannot say which end");
            assertEquals("both", ends(kb, sun), "the derived end the person kept");

            // 再跑一遍，一样不动
            jdbc.execute(migration);
            assertEquals("start", ends(kb, zhang));
            assertEquals("both", ends(kb, li));
            assertEquals(null, ends(kb, zhao));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
