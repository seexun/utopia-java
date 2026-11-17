package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.TypedMaterializeService.Outcome;

/**
 * M4 任务 7 审查 C-1 的回归测试：同一轮物化里，后一条 due 必须看见前一条 due 写下的
 * 证据与 from_statement_id（Rust materialize.rs 逐条执行的可见序）。
 *
 * <p>审查者用回滚事务实证过攒批形态的终态分歧：裸陈述先成行、带时间的后来精化它时，
 * 门的 refine 分支（graph.rs copy_evidence_from）要把裸行上的证据搬给新行——前一条
 * due 的证据若攒批未落库，新行搬不到它，先到陈述的证据丢失；「某天结束了」走闭合分支
 * 时，改写行携带旧行的 from_statement_id（insertRewriteEnd 照抄该列）——旧行的
 * setFromStatement 未落库会让修正行带着 NULL 出门、再被攒批覆写成后一条陈述，
 * 与 Rust 逐条序的「第一条陈述」终态分歧。两条链各一组陈述（不同宾语、互不干扰），
 * 按 Rust 序断言：证据两条、来源两条、from 继承第一条。
 */
class ALaterDueSeesWhatAnEarlierDueWroteTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TypedMaterializeService materialize;

    private record Row(UUID id, UUID fromStatement, UUID supersedes) {
    }

    private Row live(UUID kb, UUID object) {
        return jdbc.queryForObject("""
                SELECT id, from_statement_id, supersedes FROM facts
                 WHERE kb_id = ? AND layer = 'typed' AND invalidated_at IS NULL AND object_id = ?
                """, (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class)), kb, object);
    }

    private long count(String table, String column, UUID value) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, value);
    }

    @Test
    void aLaterDueSeesWhatAnEarlierDueWrote() {
        jdbc.update("DELETE FROM organizations WHERE name = 'm4t7-c1-regression'");
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'm4t7-c1-regression')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'm4t7-c1-regression')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'm4t7-c1-regression')",
                kb, ws);
        try {
            UUID organization = Uuids.nowV7();
            UUID place = Uuids.nowV7();
            UUID hq = Uuids.nowV7();
            for (Object[] row : new Object[][]{{organization, "organization"}, {place, "place"}}) {
                jdbc.update("""
                        INSERT INTO entity_types (id, kb_id, key, label, color, shape)
                         VALUES (?, ?, ?, ?, '#7fd0ff', 'circle')
                        """, row[0], kb, row[1], row[1]);
            }
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, temporal)
                     VALUES (?, ?, 'headquartered_in', 'headquartered in', 'state')
                    """, hq, kb);
            UUID subj = Uuids.nowV7();
            UUID objA = Uuids.nowV7();
            UUID objB = Uuids.nowV7();
            for (Object[] row : new Object[][]{{subj, organization, "Subj"},
                    {objA, place, "Port A"}, {objB, place, "Port B"}}) {
                jdbc.update(
                        "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                        row[0], kb, row[1], row[2]);
            }

            // 四个陈述 id 预生成排序：每组内前一条（裸/开放段）必须先于后一条被物化
            // （due 按 s.id 排，同 ABoundStatement 的同毫秒防乱序手法）
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ids.add(Uuids.nowV7());
            }
            ids.sort(null);
            UUID bare = ids.get(0);
            UUID stated = ids.get(1);
            UUID started = ids.get(2);
            UUID ended = ids.get(3);

            OffsetDateTime from = OffsetDateTime.parse("2019-01-01T00:00:00Z");
            OffsetDateTime to = OffsetDateTime.parse("2024-01-01T00:00:00Z");
            // 链 A（refine）：裸陈述（无时间，带证据 E0）+ 带完整区间的同断言（证据 E1）
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, object_id, layer, phrase, confidence)
                     VALUES (?, ?, ?, ?, 'open', 'is based in', 0.9)
                    """, bare, kb, subj, objA);
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, object_id, layer, phrase, confidence,
                                       valid_from, valid_from_precision, valid_to, valid_to_precision)
                     VALUES (?, ?, ?, ?, 'open', 'is based in', 0.9, ?, 'day', ?, 'day')
                    """, stated, kb, subj, objA, from, to);
            // 链 B（闭合）：marks=start 的一刻（2019，带证据 Ea）成开放段；
            // 无起点、有终点（2024）的观察（证据 Eb）走门「某天结束了」闭合分支
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, object_id, layer, phrase, confidence,
                                       valid_from, valid_from_precision, valid_to, valid_to_precision)
                     VALUES (?, ?, ?, ?, 'open', 'is based in', 0.9, ?, 'day', ?, 'day')
                    """, started, kb, subj, objB, from, from);
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, object_id, layer, phrase, confidence,
                                       valid_to, valid_to_precision)
                     VALUES (?, ?, ?, ?, 'open', 'is based in', 0.9, ?, 'day')
                    """, ended, kb, subj, objB, to);

            // 每条陈述各带一份证据（不同文档/引文），绑定 marks=start
            for (Object[] row : new Object[][]{{bare, "doc-bare.txt", "bare says so"},
                    {stated, "doc-stated.txt", "stated says so"},
                    {started, "doc-started.txt", "started says so"},
                    {ended, "doc-ended.txt", "ended says so"}}) {
                UUID doc = Uuids.nowV7();
                UUID chunk = Uuids.nowV7();
                jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256) VALUES (?, ?, ?, ?)",
                        doc, kb, row[1], row[1]);
                jdbc.update("""
                        INSERT INTO chunks (id, kb_id, document_id, seq, text)
                         VALUES (?, ?, ?, 0, ?)
                        """, chunk, kb, doc, row[2]);
                jdbc.update("""
                        INSERT INTO fact_evidence (fact_id, chunk_id, quote, document_id, quote_start, quote_end)
                         VALUES (?, ?, ?, ?, 0, 16)
                        """, row[0], chunk, row[2], doc);
            }
            jdbc.update("""
                    INSERT INTO phrase_bindings
                         (id, kb_id, phrase, subject_type_id, object_type_id, object_is_value,
                          relation_type_id, direction, marks, status)
                     VALUES (?, ?, 'is based in', ?, ?, false, ?, 'forward', 'start', 'bound')
                    """, Uuids.nowV7(), kb, organization, place, hq);

            Outcome o = materialize.materialize(kb);
            // 每条链两次新建：refine 链（裸行 + 取代它的带区间行）、闭合链（开放段 + 改写行）
            assertEquals(new Outcome(0, 4, 0, 0, 0, 0), o);

            // 链 A（refine）：带区间的观察取代裸行——新行的证据是两份（裸行搬来的 E0
            // + 自己的 E1），来源两条（adopt 搬来的 + 自己挂的），from 是带区间的那条
            // （门 INSERT 的新行 from 为空，setFromStatement 落上）
            Row a = live(kb, objA);
            assertEquals(stated, a.fromStatement(), "refine 链：from = 带区间的那条陈述");
            assertEquals(2L, count("fact_evidence", "fact_id", a.id()),
                    "refine 链：先到陈述的证据被门搬来，共两份");
            assertEquals(2L, count("typed_fact_sources", "fact_id", a.id()),
                    "refine 链：来源两条（裸行的 + 自己的）");
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM facts WHERE kb_id = ? AND object_id = ? AND layer = 'typed'"
                            + " AND invalidated_at IS NOT NULL", Long.class, kb, objA),
                    "裸行作废留在账上");

            // 链 B（闭合）：「某天结束了」的修正行携带开放段的 from_statement_id
            // （insertRewriteEnd 照抄该列）——Rust 逐条序下开放段的 setFromStatement 已落库，
            // 修正行继承的是第一条陈述；后来这条的 setFromStatement 被 IS NULL 挡住不覆写。
            // 证据同样是两份（rewrite 搬来的 Ea + 自己的 Eb）
            Row b = live(kb, objB);
            assertEquals(started, b.fromStatement(), "闭合链：from 继承第一条陈述（不被后来者覆写）");
            assertEquals(2L, count("fact_evidence", "fact_id", b.id()),
                    "闭合链：rewrite 搬来开放段的证据，共两份");
            assertEquals(2L, count("typed_fact_sources", "fact_id", b.id()),
                    "闭合链：来源两条（adopt 搬来的 + 自己的）");

            // 再跑一遍：什么都不动（终态稳定）
            assertEquals(Outcome.EMPTY, materialize.materialize(kb));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
