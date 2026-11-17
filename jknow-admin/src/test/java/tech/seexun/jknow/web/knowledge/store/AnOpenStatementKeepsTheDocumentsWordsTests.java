package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ExportStore;
import tech.seexun.jknow.store.GraphService;
import tech.seexun.jknow.store.PathsService;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.TimeMentionsService;

/**
 * 移植自 tests/store/an_open_statement_keeps_the_documents_words.rs（389 行，0044 第一刀，
 * #729）——一条陈述留着文档自己的字：facts 里 layer='open' 的一行，短语照写、没有谓词。
 * 所有已经容得下空谓词的读路径（图、实体面板、邻域、路径、导出）按短语显示它；同一句话
 * 再听到一次是同一行、证据累积；角色词属性与时间词各自取得回来；证据带着引文在块里的
 * 偏移；库拒绝没有短语的开放行。
 *
 * <p><b>已启用（M5 任务 1 去封）</b>——三个 seam 全部换真身：entity_detail（任务 6）、
 * paths_between（任务 11）、export facts_page（M5 任务 1，ExportStore.factsPage）。
 * 写路径（insert_open_statement / add_evidence_located / add_statement_qualifier /
 * time_mentions）与 overview/neighborhood 均为真身；
 * 断言一字不动。
 */
class AnOpenStatementKeepsTheDocumentsWordsTests extends HeartStoreTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ORG = "open-statement-test";
    private static final String TEXT_A = "Acme acquired Beta for $1.2 billion last winter.";
    private static final String TEXT_B = "Regulators later cleared the deal: Acme acquired Beta.";

    @Autowired
    GraphService graph;
    @Autowired
    PathsService paths;
    @Autowired
    GraphWriteService writes;
    @Autowired
    TimeMentionsService timeMentions;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    javax.sql.DataSource dataSource;

    record Fixture(UUID kb, UUID acme, UUID beta, UUID chunkA, UUID chunkB) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID beta = Uuids.nowV7();
        UUID src = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunkA = Uuids.nowV7();
        UUID chunkB = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, ORG);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org, ORG);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)", kb,
                ws, ORG);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'org', '组织')",
                etype, kb);
        for (UUID[] pair : new UUID[][]{{acme, null}, {beta, null}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    pair[0], kb, etype, pair[0].equals(acme) ? "Acme" : "Beta");
        }
        jdbc.update("INSERT INTO sources (id, kb_id, name) VALUES (?, ?, '临时来源')", src, kb);
        jdbc.update("""
                INSERT INTO documents (id, kb_id, source_id, filename, sha256, status)
                 VALUES (?, ?, ?, 'deal.md', 'openstatement', 'ready')
                """, doc, kb, src);
        for (Object[] chunk : new Object[][]{{chunkA, 0, TEXT_A}, {chunkB, 1, TEXT_B}}) {
            jdbc.update(
                    "INSERT INTO chunks (id, kb_id, document_id, seq, text) VALUES (?, ?, ?, ?, ?)",
                    chunk[0], kb, doc, chunk[1], chunk[2]);
        }
        return new Fixture(kb, acme, beta, chunkA, chunkB);
    }

    /** 引文在块里的字符偏移：服务端搜文本算出来的那个数（ASCII 文本，字节即字符）。 */
    private static int[] spanOf(String text, String quote) {
        int start = text.indexOf(quote);
        int charsBefore = text.codePointCount(0, start);
        return new int[]{charsBefore, charsBefore + quote.codePointCount(0, quote.length())};
    }

    /**
     * paths.rs:115 paths_between（任务 6 真身 PathsService，任务 11 换接）：
     * 每条路径的边序列化为 [factId, 谓词显示口径] 数组——与断言面同形。
     */
    private List<List<Object[]>> pathsBetween(UUID kb, UUID from, UUID to,
                                              java.time.OffsetDateTime at,
                                              java.time.OffsetDateTime asOf) {
        return paths.pathsBetween(kb, from, to, at, asOf, PathsService.Limits.DEFAULT).stream()
                .map(path -> path.edges().stream()
                        .map(e -> new Object[]{e.factId(), e.predicate()})
                        .toList())
                .toList();
    }

    /**
     * export.rs facts_page 的真身（M5 任务 1 落地——ExportStore.factsPage：SQL 逐字、
     * 不过滤 invalidated、按页原子校验）。asOf 形参保留（Rust 调用点形状）——导出读的是
     * 整本账，不按记录轴回退。
     */
    private List<Object[]> factsPage(UUID kb, java.time.OffsetDateTime asOf) {
        try (java.sql.Connection tx = dataSource.getConnection()) {
            tx.setAutoCommit(false);
            var facts = ExportStore.factsPage(tx, kb, null);
            tx.rollback();
            return facts.stream()
                    .map(f -> new Object[]{f.id(), f.surfacePredicate(), f.predicateId()})
                    .toList();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void an_open_statement_shows_under_its_phrase_and_reuses_its_row() throws Exception {
        jdbc.update("DELETE FROM organizations WHERE name = ?", ORG);
        Fixture f = seed();
        try {
            // 1. 落一条开放陈述：Acme —acquired→ Beta，短语照写、谓词为空、没有 valid_*
            var statement = writes.insertOpenStatement(f.kb(), f.acme(), "acquired", f.beta(),
                    null, null, 0.9f);
            assertTrue(statement.created());
            UUID fact = statement.id();
            writes.addEvidenceLocated(fact, f.chunkA(), TEXT_A, "acquired", spanOf(TEXT_A, TEXT_A));
            writes.addStatementQualifier(fact, "amount",
                    JSON.readTree("{ \"value\": \"$1.2 billion\" }"), null);
            int winterStart = spanOf(TEXT_A, "last winter")[0];
            UUID mention = timeMentions.record(f.kb(), fact, f.chunkA(), "last winter",
                    winterStart, "when");

            var row = jdbc.queryForMap("""
                    SELECT layer, phrase, predicate_id, valid_from, valid_to FROM facts WHERE id = ?
                    """, fact);
            assertEquals("open", row.get("layer"));
            assertEquals("acquired", row.get("phrase"));
            assertTrue(row.get("predicate_id") == null, "开放陈述没有谓词");
            assertTrue(row.get("valid_from") == null && row.get("valid_to") == null,
                    "开放陈述在世界轴上还没有位置");

            // 2. 每条读路径都按短语显示它
            var edges = graph.overview(f.kb(), 50, null, null).edges();
            var e = edges.stream().filter(x -> x.id().equals(fact)).findFirst()
                    .orElseThrow(() -> new AssertionError("overview shows it"));
            assertEquals("acquired", e.label());
            assertTrue(e.inferred(), "名字来自原文，不是本体认下的关系");

            var facts = DetailStubs.entityDetail(jdbc, f.kb(), f.acme(), null, null);
            var d = facts.stream().filter(x -> x.id().equals(fact)).findFirst()
                    .orElseThrow(() -> new AssertionError("entity detail shows it"));
            assertEquals("acquired", d.predicateLabel());
            assertEquals(f.beta(), d.otherId());

            var nEdges = graph.neighborhood(f.kb(), f.acme(), 1, null, null).edges();
            var n = nEdges.stream().filter(x -> x.id().equals(fact)).findFirst()
                    .orElseThrow(() -> new AssertionError("neighborhood shows it"));
            assertEquals("acquired", n.label());

            var paths = pathsBetween(f.kb(), f.acme(), f.beta(), null, null);
            var direct = paths.stream().filter(p -> p.size() == 1
                    && p.get(0)[0].equals(fact)).findFirst()
                    .orElseThrow(() -> new AssertionError("a path walks the open statement"));
            assertEquals("acquired", direct.get(0)[1]);

            var exported = factsPage(f.kb(), null);
            var x = exported.stream().filter(rowX -> rowX[0].equals(fact)).findFirst()
                    .orElseThrow(() -> new AssertionError("export carries it"));
            assertEquals("acquired", x[1]);
            assertNull(x[2]);

            // 3. 同一句话再听到一次：同一行，证据累积
            var again = writes.insertOpenStatement(f.kb(), f.acme(), "acquired", f.beta(), null,
                    null, 0.8f);
            assertEquals(fact, again.id(), "同 (主语, 短语, 宾语) 复用那一行");
            assertTrue(!again.created());
            String quoteB = "Acme acquired Beta.";
            writes.addEvidenceLocated(fact, f.chunkB(), quoteB, "acquired", spanOf(TEXT_B, quoteB));
            var evidence = jdbc.queryForList("""
                    SELECT chunk_id, quote_start, quote_end FROM fact_evidence WHERE fact_id = ?
                     ORDER BY quote_start
                    """, fact);
            assertEquals(2, evidence.size(), "第二块的证据累积到同一行上");
            assertEquals(f.chunkA(), evidence.get(0).get("chunk_id"));
            assertEquals(0, evidence.get(0).get("quote_start"));
            assertEquals(TEXT_A.length(), evidence.get(0).get("quote_end"));
            int[] bSpan = spanOf(TEXT_B, quoteB);
            assertEquals(f.chunkB(), evidence.get(1).get("chunk_id"));
            assertEquals(bSpan[0], evidence.get(1).get("quote_start"));
            assertEquals(bSpan[1], evidence.get(1).get("quote_end"));
            Long live = jdbc.queryForObject("""
                    SELECT count(*) FROM facts
                     WHERE kb_id = ? AND layer = 'open' AND invalidated_at IS NULL
                    """, Long.class, f.kb());
            assertEquals(1L, live, "两次观察一行");

            // 另一个宾语是另一行：去重键里有宾语
            var other = writes.insertOpenStatement(f.kb(), f.acme(), "acquired", null,
                    JSON.readTree("{ \"value\": \"a rival\" }"), null, 0.5f);
            assertTrue(other.created() && !other.id().equals(fact));

            // 4. 角色词属性与时间词各自取得回来
            var qualifiers = writes.statementQualifiersFor(List.of(fact));
            var q = qualifiers.get(fact);
            assertEquals(1, q.size());
            assertEquals("amount", q.get(0).role());
            assertEquals(JSON.readTree("{ \"value\": \"$1.2 billion\" }"), q.get(0).value());
            assertTrue(q.get(0).entityId() == null);
            // 先写者留着
            writes.addStatementQualifier(fact, "amount",
                    JSON.readTree("{ \"value\": \"$2 billion\" }"), null);
            var q2 = writes.statementQualifiersFor(List.of(fact)).get(fact);
            assertEquals(JSON.readTree("{ \"value\": \"$1.2 billion\" }"), q2.get(0).value());
            // 值与实体必须恰好一个
            boolean shapeError = false;
            try {
                writes.addStatementQualifier(fact, "buyer", null, null);
            } catch (tech.seexun.jknow.common.AppException ex) {
                shapeError = true;
            }
            assertTrue(shapeError, "值与实体必须恰好一个");
            writes.addStatementQualifier(fact, "buyer", null, f.acme());
            var q3 = writes.statementQualifiersFor(List.of(fact)).get(fact);
            var buyer = q3.stream().filter(rowQ -> "buyer".equals(rowQ.role())).findFirst()
                    .orElseThrow();
            assertEquals(f.acme(), buyer.entityId());
            assertEquals("Acme", buyer.entityName());

            var mentions = timeMentions.forFacts(List.of(fact)).get(fact);
            assertEquals(1, mentions.size());
            assertEquals(mention, mentions.get(0).id());
            assertEquals("last winter", mentions.get(0).text());
            assertEquals(winterStart, mentions.get(0).charStart());
            assertEquals(mention, timeMentions.record(f.kb(), fact, f.chunkA(), "last winter",
                    winterStart, "when"), "同一位置再记一次回的是同一行");

            // 6. 库拒绝没有短语的开放行
            boolean bad = false;
            try {
                jdbc.update(
                        "INSERT INTO facts (id, kb_id, subject_id, layer, object_id) VALUES (?, ?, ?, 'open', ?)",
                        Uuids.nowV7(), f.kb(), f.acme(), f.beta());
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                bad = true;
            }
            assertTrue(bad, "开放行必须带短语");
            // 库里没有关系类型时这句什么也不插；有的话必须被约束挡下
            int hit = jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, layer, phrase, predicate_id, object_id)
                     SELECT ?, ?, ?, 'open', 'acquired', r.id, ?
                     FROM relation_types r WHERE r.kb_id = ? LIMIT 1
                    """, Uuids.nowV7(), f.kb(), f.acme(), f.beta(), f.kb());
            assertTrue(hit == 0, "有的话必须被约束挡下");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE name = ?", ORG);
        }
    }
}
