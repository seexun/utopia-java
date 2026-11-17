package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.temporal.Validity;
import tech.seexun.jknow.store.temporal.WorldTime;

/**
 * 移植自 tests/store/an_earlier_mention_keeps_the_stated_end.rs（147 行）——一条
 * 「结束了，不知哪天」的行（0022/#393）：更早的「它成立」并进这一行时起点锚往早挪，
 * 终点锚不动（#875 的回放）。等价点：无（直译）。
 */
class AnEarlierMentionKeepsTheStatedEndTests extends HeartStoreTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    GraphWriteService writes;

    @Test
    void an_earlier_mention_that_it_held_keeps_the_stated_end() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID klass = Uuids.nowV7();
        UUID location = Uuids.nowV7();
        UUID cup = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'stated-end-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'stated-end-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'stated-end-test')",
                kb, ws);
        try {
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'cup', 'Cup')",
                    klass, kb);
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, kind, datatype, temporal)
                     VALUES (?, ?, 'location', 'location', 'attribute', 'text', 'state')
                    """, location, kb);
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, 'cup-7')",
                    cup, kb, klass);
            OffsetDateTime t0 = OffsetDateTime.parse("2026-09-23T07:50:00Z");
            OffsetDateTime t1 = OffsetDateTime.parse("2026-09-23T08:00:00Z");
            OffsetDateTime t2 = OffsetDateTime.parse("2026-09-23T08:10:00Z");
            var desk = JSON.createObjectNode().put("value", "desk");
            // t1：在桌上。没有起点，从这份证据起成立
            writes.insertValueFact(kb, cup, location, desk, Validity.empty().attested(t1), 1.0f);
            // t2：它结束了，不知哪天——终点锚在这份文档上
            var closed = writes.insertValueFact(kb, cup, location, desk,
                    Validity.empty().attested(t2).endedWhenUnknown(), 1.0f);
            // t0 < t1：一次更早的「在桌上」晚到。同一断言并进已经关上的那一行
            var merged = writes.insertValueFact(kb, cup, location, desk,
                    Validity.empty().attested(t0), 1.0f);
            assertEquals(closed.id(), merged.id());
            assertTrue2(!merged.created());
            record Anchors(OffsetDateTime from, OffsetDateTime to, String prec) {
            }
            Anchors row = jdbc.queryForObject(
                    "SELECT attested_from, attested_to, valid_to_precision FROM facts WHERE id = ?",
                    (rs, i) -> new Anchors(rs.getObject(1, OffsetDateTime.class),
                            rs.getObject(2, OffsetDateTime.class), rs.getString(3)), closed.id());
            assertEquals(WorldTime.ENDED_UNKNOWN, row.prec());
            assertEquals(t0.toInstant(), row.from().toInstant(),
                    "the earlier mention is earlier evidence that it held");
            assertEquals(t2.toInstant(), row.to().toInstant(),
                    "a mention that it held is no evidence that it ended earlier");
            // 另一条路径仍要前移：这份更早的证据确实说它已结束，而不是只说它成立
            var ended = writes.insertValueFact(kb, cup, location, desk,
                    Validity.empty().attested(t1).endedWhenUnknown(), 1.0f);
            assertEquals(closed.id(), ended.id());
            assertTrue2(!ended.created());
            Anchors anchors = jdbc.queryForObject(
                    "SELECT attested_from, attested_to FROM facts WHERE id = ?",
                    (rs, i) -> new Anchors(rs.getObject(1, OffsetDateTime.class),
                            rs.getObject(2, OffsetDateTime.class), null), closed.id());
            assertEquals(t0.toInstant(), anchors.from().toInstant());
            assertEquals(t1.toInstant(), anchors.to().toInstant(),
                    "earlier ended evidence moves only the end here");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    private static void assertTrue2(boolean b) {
        org.junit.jupiter.api.Assertions.assertTrue(b);
    }
}
