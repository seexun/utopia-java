package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.TypedMaterializeService.Outcome;

/**
 * 移植自 tests/store/a_bound_statement_becomes_a_typed_fact.rs（272 行）——类型化图谱是
 * 视图（0044 决定 3，0067、0068）：绑上的签名下的开放陈述算成类型化行，几条陈述说同一
 * 件事就是一行，绑定变了行跟着变，跑多少遍结果一样。
 *
 * <p>等价点（tokio→JUnit）：Rust 的 {@code materialize(&pool, kb)} →
 * {@link TypedMaterializeService#materialize}；Outcome 相等断言按 record 值相等直译。
 */
class ABoundStatementBecomesATypedFactTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TypedMaterializeService materialize;

    private record Row(UUID id, UUID subject, UUID object, UUID predicate, UUID fromStatement,
                      OffsetDateTime validFrom, String validFromGrade) {
    }

    private List<Row> live(UUID kb) {
        return jdbc.query("""
                SELECT id, subject_id, object_id, predicate_id, from_statement_id, valid_from,
                        valid_from_grade
                   FROM facts WHERE kb_id = ? AND layer = 'typed' AND invalidated_at IS NULL
                """, (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class), rs.getObject(6, OffsetDateTime.class),
                rs.getString(7)), kb);
    }

    @Test
    void aBoundStatementBecomesATypedFact() {
        jdbc.update("DELETE FROM organizations WHERE name = 'materialize-test'");
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'materialize-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'materialize-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'materialize-test')",
                kb, ws);
        try {
            // 两个类、一条属性
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

            // 两样东西；三条陈述：一条平常的，一条带 mood 的，一条另一份文档说的同一件事
            UUID bakery = Uuids.nowV7();
            UUID port = Uuids.nowV7();
            for (Object[] row : new Object[][]{{bakery, organization, "Harbor Bakery"},
                    {port, place, "Port Ellen"}}) {
                jdbc.update(
                        "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                        row[0], kb, row[1], row[2]);
            }
            // 先来一条没时间的裸陈述，再来两条带时间的：裸行会被带时间的行取代（supersedes），
            // 来源要跟着搬到新行上
            UUID bare = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, object_id, layer, phrase, confidence)
                     VALUES (?, ?, ?, ?, 'open', 'operates from', 0.9)
                    """, bare, kb, bakery, port);
            // v7 时间戳只到毫秒：同毫秒内的随机位不保序（Rust 注释假设同毫秒递增）。
            // due 按 s.id 排——这里排序后按序指派，裸行必先于带时间的行被处理
            java.util.List<UUID> ids = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                ids.add(Uuids.nowV7());
            }
            ids.sort(null);
            UUID stated = ids.get(0);
            UUID planned = ids.get(1);
            UUID again = ids.get(2);
            for (Object[] row : new Object[][]{{stated, "is based in"}, {planned, "will move to"},
                    {again, "is based in"}}) {
                jdbc.update("""
                        INSERT INTO facts (id, kb_id, subject_id, object_id, layer, phrase, confidence,
                                            valid_from, valid_from_precision, valid_from_grade)
                         VALUES (?, ?, ?, ?, 'open', ?, 0.9, ?, 'year', 'B')
                        """, row[0], kb, bakery, port, row[1],
                        OffsetDateTime.parse("2019-01-01T00:00:00Z"));
            }
            for (Object[] row : new Object[][]{{stated, "bakery.txt"}, {again, "bakery-2.txt"}}) {
                UUID doc = Uuids.nowV7();
                UUID chunk = Uuids.nowV7();
                jdbc.update(
                        "INSERT INTO documents (id, kb_id, filename, sha256) VALUES (?, ?, ?, ?)",
                        doc, kb, row[1], row[1]);
                jdbc.update("""
                        INSERT INTO chunks (id, kb_id, document_id, seq, text)
                         VALUES (?, ?, ?, 0, 'Harbor Bakery is based in Port Ellen.')
                        """, chunk, kb, doc);
                jdbc.update("""
                        INSERT INTO fact_evidence (fact_id, chunk_id, quote, document_id, quote_start, quote_end)
                         VALUES (?, ?, 'Harbor Bakery is based in Port Ellen.', ?, 0, 37)
                        """, row[0], chunk, doc);
            }
            jdbc.update(
                    "INSERT INTO statement_qualifiers (fact_id, role, value) VALUES (?, 'since', '\"2019\"'::jsonb)",
                    stated);
            jdbc.update(
                    "INSERT INTO statement_qualifiers (fact_id, role, value) VALUES (?, 'mood', '\"will\"'::jsonb)",
                    planned);

            // 两条签名都绑到 headquartered_in
            for (String phrase : new String[]{"is based in", "will move to", "operates from"}) {
                jdbc.update("""
                        INSERT INTO phrase_bindings
                             (id, kb_id, phrase, subject_type_id, object_type_id, object_is_value,
                              relation_type_id, direction, status)
                         VALUES (?, ?, ?, ?, ?, false, ?, 'forward', 'bound')
                        """, Uuids.nowV7(), kb, phrase, organization, place, hq);
            }

            // 三条平常的陈述说的是同一件事：裸的那条先成一行，带时间的取代它（added 2，旧行作废、
            // 来源搬过来），第三条并进去（merged 1）——最后一行，三条来源，两条证据；带 mood 的
            // 那条不算
            Outcome first = materialize.materialize(kb);
            assertEquals(new Outcome(0, 2, 1, 0, 0, 0), first);
            List<Row> rows = live(kb);
            assertEquals(1, rows.size(), "同一个三元组只有一行");
            Row typed = rows.get(0);
            assertEquals(bakery, typed.subject());
            assertEquals(port, typed.object());
            assertEquals(hq, typed.predicate());
            assertEquals(stated, typed.fromStatement());
            assertNotNull(typed.validFrom(), "世界轴时间抄过来");
            // 起点是怎么来的也抄过来：时态引擎在类型化的行上判，读不到等级就只能回去猜（0045 第 3 刀）
            assertEquals("B", typed.validFromGrade(), "起点的来历随日期一起抄过来");
            UUID typedId = typed.id();
            assertEquals(3L, jdbc.queryForObject(
                    "SELECT count(*) FROM typed_fact_sources WHERE fact_id = ?", Long.class, typedId));
            assertEquals(2L, jdbc.queryForObject(
                    "SELECT count(*) FROM fact_evidence WHERE fact_id = ?", Long.class, typedId));
            assertEquals(List.of("since"), jdbc.queryForList(
                    "SELECT role FROM statement_qualifiers WHERE fact_id = ? ORDER BY role",
                    String.class, typedId));

            // 再跑一遍：什么都不动
            assertEquals(Outcome.EMPTY, materialize.materialize(kb));

            // 一份文档撤了它那句：行还在，来源少一条
            jdbc.update("UPDATE facts SET invalidated_at = now() WHERE id = ?", again);
            assertEquals(Outcome.EMPTY, materialize.materialize(kb));
            assertEquals(2L, jdbc.queryForObject(
                    "SELECT count(*) FROM typed_fact_sources WHERE fact_id = ?", Long.class, typedId));
            assertEquals(1, live(kb).size(), "还有来源，行留着");

            // 绑定翻成 reverse：旧行作废，新行主宾对调
            jdbc.update("""
                    UPDATE phrase_bindings SET direction = 'reverse'
                      WHERE kb_id = ? AND phrase IN ('is based in', 'operates from')
                    """, kb);
            // 旧行的来源全不成立了：作废 1；反向重算时裸的那条先成行、带时间的再取代它：新建 2
            assertEquals(new Outcome(1, 2, 0, 0, 0, 0), materialize.materialize(kb));
            rows = live(kb);
            assertEquals(1, rows.size());
            assertEquals(port, rows.get(0).subject(), "方向反了主宾对调");
            assertEquals(bakery, rows.get(0).object());
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM facts WHERE from_statement_id = ? AND invalidated_at IS NOT NULL",
                    Long.class, stated), "旧行留着，只是作废了");

            // 绑定改成 none：行作废，不再补
            jdbc.update("""
                    UPDATE phrase_bindings SET status = 'none', relation_type_id = NULL, direction = NULL
                      WHERE kb_id = ? AND phrase IN ('is based in', 'operates from')
                    """, kb);
            assertEquals(new Outcome(1, 0, 0, 0, 0, 0), materialize.materialize(kb));
            assertEquals(0L, materialize.count(kb));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
