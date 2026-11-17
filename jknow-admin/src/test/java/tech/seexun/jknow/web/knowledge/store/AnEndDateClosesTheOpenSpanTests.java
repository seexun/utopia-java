package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/an_end_date_closes_the_open_span.rs（381 行）——一段状态先被观察到
 * 起点，后来的文档说它某天结束了：账本关上那一段，不另立一行。等价点：无（纯落库门 +
 * temporal 语义，直译）。
 */
class AnEndDateClosesTheOpenSpanTests extends HeartStoreTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    GraphWriteService writes;
    @Autowired
    OntologyService ontology;

    private static OffsetDateTime day(String s) {
        return OffsetDateTime.parse(s + "T00:00:00Z");
    }

    private static Validity span(String from, String to, String attested) {
        return new Validity(from == null ? null : day(from), from == null ? null : "day",
                to == null ? null : day(to), to == null ? null : "day", day(attested), null);
    }

    private static Validity ends(String to, String attested) {
        return new Validity(null, null, day(to), "day", day(attested), null);
    }

    record LiveRow(UUID id, OffsetDateTime from, OffsetDateTime to, UUID supersedes) {
    }

    private List<LiveRow> live(UUID kb, UUID pred, UUID object) {
        return jdbc.query("""
                SELECT id, valid_from, valid_to, supersedes FROM facts
                 WHERE kb_id = ? AND predicate_id = ? AND object_id = ? AND invalidated_at IS NULL
                """, (rs, i) -> new LiveRow(rs.getObject(1, UUID.class),
                rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class),
                rs.getObject(4, UUID.class)), kb, pred, object);
    }

    @Test
    void an_end_date_closes_the_open_span() {
        UUID tag = Uuids.nowV7();
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, "span-" + tag);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org,
                "span-" + tag);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)", kb, ws,
                "span-" + tag);
        UUID klass = Uuids.nowV7();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)", klass,
                kb, "org", "Org");
        UUID title = ontology.createRelationType(kb, "title", "title", "state",
                OntologyService.RelationAxioms.empty(), "", "attribute", List.of(klass), List.of(),
                "text", null);
        UUID worksAt = ontology.createRelationType(kb, "works_at", "works at", "state",
                OntologyService.RelationAxioms.empty(), "", "relation", List.of(), List.of(),
                null, null);
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                a, kb, klass, "李文博-" + tag);
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                b, kb, klass, "澜图科技-" + tag);

        try {
            // 第一份文档：从 2020-01-10 起任董事
            var first = writes.insertFact(kb, a, worksAt, b, new Validity(day("2020-01-10"), "day",
                    null, null, day("2025-08-01"), null), 0.9f);
            assertTrue(first.created());
            var value = JSON.createObjectNode().put("value", "董事");
            assertEquals(GraphWriteService.QualifierWrite.SET,
                    writes.upsertFactQualifier(first.id(), title, value));

            // 第二份文档：2024-04-30 辞去董事职务——没起点、只有终点
            var closed = writes.insertFact(kb, a, worksAt, b, ends("2024-04-30", "2024-04-18"),
                    0.9f);
            assertTrue(closed.created(), "关上一段是一次改写，不是复用");
            assertNotEquals(first.id(), closed.id());

            List<LiveRow> rows = jdbc.query("""
                    SELECT id, valid_from, valid_to, supersedes FROM facts
                     WHERE kb_id = ? AND predicate_id = ? AND invalidated_at IS NULL
                    """, (rs, i) -> new LiveRow(rs.getObject(1, UUID.class),
                    rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class),
                    rs.getObject(4, UUID.class)), kb, worksAt);
            assertEquals(1, rows.size(), "只剩一条活着的：那一段，关上了");
            assertEquals(closed.id(), rows.get(0).id());
            assertEquals(day("2020-01-10"), rows.get(0).from(), "起点照旧");
            assertEquals(day("2024-04-30"), rows.get(0).to(), "终点是离任那天");
            assertEquals(first.id(), rows.get(0).supersedes(), "修正行链回旧行");
            Boolean oldDead = jdbc.queryForObject(
                    "SELECT invalidated_at IS NOT NULL FROM facts WHERE id = ?", Boolean.class,
                    first.id());
            assertTrue(oldDead, "旧行在认知轴上作废，不删");
            var quals = writes.factQualifiersFor(List.of(closed.id()));
            assertEquals(1, quals.get(closed.id()).size(), "职务随修正行");
            assertEquals(value, quals.get(closed.id()).get(0).value());

            // 第三份文档又说了一遍同一天离任：同一件事，复用那一行
            var again = writes.insertFact(kb, a, worksAt, b, ends("2024-04-30", "2024-05-02"),
                    0.9f);
            assertEquals(closed.id(), again.id());
            assertTrue(!again.created());

            // 处罚决定书里提到「董事李文博」，没写日期，文档日期在任期之内：说的是这一段
            var mentioned = writes.insertFact(kb, a, worksAt, b,
                    span(null, null, "2024-04-17"), 0.9f);
            assertEquals(closed.id(), mentioned.id(), "任期内的无日期提及并进关上的那一段");
            assertTrue(!mentioned.created());
            // 任期之后的无日期提及：可能是再次任职，另立一条，等人裁
            var later = writes.insertFact(kb, a, worksAt, b,
                    span(null, null, "2025-06-01"), 0.9f);
            assertTrue(later.created());
            assertNotEquals(closed.id(), later.id());
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    /**
     * 同一段的三种来法，都只剩一条活着的行：起点先到、后来的文档把整段说全；
     * 终点先到（并行抽取时说结束的那份先落库）、起点后到。
     */
    @Test
    void a_span_told_in_two_halves_is_one_row() {
        UUID tag = Uuids.nowV7();
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, "halves-" + tag);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org,
                "halves-" + tag);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)", kb, ws,
                "halves-" + tag);
        UUID klass = Uuids.nowV7();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)", klass,
                kb, "org", "Org");
        UUID listed = ontology.createRelationType(kb, "listed", "listed", "state",
                OntologyService.RelationAxioms.empty(), "", "relation", List.of(), List.of(),
                null, null);
        UUID court = Uuids.nowV7();
        UUID first = Uuids.nowV7();
        UUID second = Uuids.nowV7();
        for (var e : List.of(new Object[]{"法院-" + tag, court}, new Object[]{"澜图新材料-" + tag, first},
                new Object[]{"第二家-" + tag, second})) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    e[1], kb, klass, (String) e[0]);
        }

        try {
            // 起点先到，后来的文档把整段说全（同起点 + 终点）→ 关上，不另立
            var started = writes.insertFact(kb, court, listed, first,
                    span("2024-11-05", null, "2024-11-07"), 0.9f);
            var closed = writes.insertFact(kb, court, listed, first,
                    span("2024-11-05", "2025-03-12", "2025-03-14"), 0.9f);
            assertTrue(closed.created());
            List<LiveRow> rows = live(kb, listed, first);
            assertEquals(1, rows.size(), "同起点带终点：关上原来那行");
            assertEquals(closed.id(), rows.get(0).id());
            assertEquals(day("2025-03-12"), rows.get(0).to());
            assertEquals(started.id(), rows.get(0).supersedes());

            // 终点先到（说结束的那份文档先落库），起点后到 → 一行，两头都有
            var endOnly = writes.insertFact(kb, court, listed, second,
                    ends("2025-03-12", "2025-03-14"), 0.9f);
            var merged = writes.insertFact(kb, court, listed, second,
                    span("2024-11-05", null, "2024-11-07"), 0.9f);
            assertTrue(merged.created());
            List<LiveRow> rows2 = live(kb, listed, second);
            assertEquals(1, rows2.size(), "起点后到：并进只知道终点的那行");
            assertEquals(merged.id(), rows2.get(0).id());
            assertEquals(day("2024-11-05"), rows2.get(0).from(), "起点是后到的");
            assertEquals(day("2025-03-12"), rows2.get(0).to(), "终点沿用先到的");
            assertEquals(endOnly.id(), rows2.get(0).supersedes());
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
