package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.MemoryService;
import tech.seexun.jknow.store.PendingService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_pending_statement_keeps_the_documents_words.rs（283 行）——等人
 * 点头的一条陈述也留着文档自己的字（0044 第一刀的记忆侧，迁移 0062）。提议不上图；
 * 队列里的那一条带着短语/角色词属性/时间词/引文位置；点头走 insert_open_statement；
 * 摇头挡的是<b>这句话</b>（短语进了键），换一句话照样能提。
 *
 * <p>等价点：append_episode 走 MemoryService（memory.rs 逐字）；tokio 单测试函数 →
 * JUnit 单方法顺序执行。
 */
class APendingStatementKeepsTheDocumentsWordsTests extends HeartStoreTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SENTENCE = "Acme moved its headquarters to Shenzhen on 2026-03-15.";
    private static final String PHRASE = "moved its headquarters to";
    private static final String TIME_WORDS = "on 2026-03-15";

    @Autowired
    PendingService pending;
    @Autowired
    MemoryService memory;

    record Fixture(UUID org, UUID kb, UUID acme, UUID shenzhen, UUID chunk, String text) {
    }

    private Fixture fixture() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'pending-words-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'pending-words-test')",
                ws, org);
        jdbc.update(
                "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'pending-words-test')",
                kb, ws);
        UUID acme = Uuids.nowV7();
        UUID shenzhen = Uuids.nowV7();
        for (Object[] e : new Object[][]{{acme, "Acme"}, {shenzhen, "Shenzhen"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, ?)",
                    e[0], kb, e[1]);
        }
        // 那句记忆，走产品自己的路落成 chunk
        var episode = memory.appendEpisode(kb, SENTENCE, java.time.OffsetDateTime.now());
        UUID chunk = episode.chunkId();
        String text = jdbc.queryForObject("SELECT text FROM chunks WHERE id = ?", String.class,
                chunk);
        return new Fixture(org, kb, acme, shenzhen, chunk, text);
    }

    /** 引文在块里的字符偏移：从入库后的文本算，不从字面量算。 */
    private static int[] spanOf(String text, String quote) {
        int start = text.indexOf(quote);
        int charsBefore = text.codePointCount(0, start);
        return new int[]{charsBefore, charsBefore + quote.codePointCount(0, quote.length())};
    }

    private long liveFacts(UUID kb) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM facts WHERE kb_id = ? AND invalidated_at IS NULL",
                Long.class, kb);
    }

    @Test
    void a_pending_statement_keeps_the_documents_words() throws Exception {
        Fixture f = fixture();
        try {
            int[] quoteSpan = spanOf(f.text(), SENTENCE);
            int timeStart = spanOf(f.text(), TIME_WORDS)[0];
            assertTrue(quoteSpan[0] > 0, "入库的文本带时间戳前缀，偏移不能从 0 起");
            var qualifiers = JSON.readTree("[{ \"role\": \"on\", \"value\": \"2026-03-15\" }]");
            var timeWords = JSON.readTree(
                    "[{ \"text\": \"" + TIME_WORDS + "\", \"char_start\": " + timeStart + " }]");
            var first = pending.propose(proposal(f, PHRASE, qualifiers, timeWords, quoteSpan));

            // 1. 提议不上图；队列里的那一条带着文档自己的字
            assertTrue(first.isProposed(), "第一次该进队列");
            assertEquals(0, liveFacts(f.kb()), "提议阶段图上不能有活边");
            var queued = pending.forChunk(f.kb(), f.chunk());
            assertEquals(1, queued.size());
            var q = queued.get(0);
            assertEquals("Acme", q.subjectName());
            assertEquals("Shenzhen", q.objectName());
            assertEquals(PHRASE, q.phrase());
            assertTrue(q.predicateId() == null, "开放陈述没有谓词");
            assertEquals(qualifiers, q.qualifiers());
            assertEquals(timeWords, q.timeWords());
            assertEquals(quoteSpan[0], q.quoteStart());
            assertEquals(quoteSpan[1], q.quoteEnd());
            assertEquals(f.text(), q.quote(), "原句要跟着提议一起给人看");

            // 2. 同一句重抽不重复提
            assertEquals(PendingService.Outcome.alreadyPending(),
                    pending.propose(proposal(f, PHRASE, qualifiers, timeWords, quoteSpan)));

            // 3. 点头：按开放陈述进账本
            var done = pending.confirm(f.kb(), first.id());
            assertTrue(done.created(), "确认该落一条新事实");
            assertEquals(0, done.conflicts(), "开放陈述不做时态对账");
            assertEquals(PHRASE, done.snapshot().get("phrase").asText());
            assertEquals(qualifiers, done.snapshot().get("qualifiers"));
            assertEquals(1, liveFacts(f.kb()));

            var row = jdbc.queryForMap("""
                    SELECT layer, phrase, predicate_id, object_id, valid_from, valid_to, attested_from
                      FROM facts WHERE id = ?
                    """, done.factId());
            assertEquals("open", row.get("layer"));
            assertEquals(PHRASE, row.get("phrase"));
            assertTrue(row.get("predicate_id") == null, "开放陈述没有谓词");
            assertEquals(f.shenzhen(), row.get("object_id"));
            assertTrue(row.get("valid_from") == null && row.get("valid_to") == null,
                    "开放陈述在世界轴上还没有位置");
            assertTrue(row.get("attested_from") != null,
                    "记录轴总是有：记忆文档没有文档日期，落成此刻");

            // 证据指回那句记忆，带着引文在块里的位置
            var ev = jdbc.queryForMap(
                    "SELECT chunk_id, proposed_predicate, quote_start, quote_end"
                            + " FROM fact_evidence WHERE fact_id = ?", done.factId());
            assertEquals(f.chunk(), ev.get("chunk_id"), "证据要指回那句记忆");
            assertEquals(PHRASE, ev.get("proposed_predicate"), "表层谓词就是短语");
            assertEquals(quoteSpan[0], ev.get("quote_start"));
            assertEquals(quoteSpan[1], ev.get("quote_end"));
            // 引文就是偏移截出的那句话，不是整块（整块带时间戳前缀，还会被截到 120 字）
            var evQuote = jdbc.queryForMap("""
                    SELECT fe.quote, substr(c.text, fe.quote_start + 1, fe.quote_end - fe.quote_start) AS at_span
                      FROM fact_evidence fe JOIN chunks c ON c.id = fe.chunk_id WHERE fe.fact_id = ?
                    """, done.factId());
            assertEquals(evQuote.get("at_span"), evQuote.get("quote"),
                    "证据引文要和偏移截出的字一样");
            assertEquals(SENTENCE, evQuote.get("quote"));

            // 角色词属性按文档的角色词落
            var qualifierRows = jdbc.queryForList(
                    "SELECT role, value, entity_id FROM statement_qualifiers WHERE fact_id = ?",
                    done.factId());
            assertEquals(1, qualifierRows.size());
            assertEquals("on", qualifierRows.get(0).get("role"));
            assertEquals("\"2026-03-15\"", qualifierRows.get(0).get("value").toString());
            assertTrue(qualifierRows.get(0).get("entity_id") == null);

            // 时间词照抄，偏移在块里能复原出那几个字
            var mentions = jdbc.queryForList("""
                    SELECT m.text, m.char_start, substr(c.text, m.char_start + 1, length(m.text)) AS back
                      FROM time_mentions m JOIN chunks c ON c.id = m.chunk_id
                     WHERE m.fact_id = ?
                    """, done.factId());
            assertEquals(1, mentions.size());
            assertEquals(TIME_WORDS, mentions.get(0).get("text"));
            assertEquals(timeStart, mentions.get(0).get("char_start"));
            assertEquals(TIME_WORDS, mentions.get(0).get("back"), "偏移指回的就是块里的那几个字");

            assertTrue(pending.forChunk(f.kb(), f.chunk()).isEmpty(), "点头后队列清空");

            // 4. 图上已有的不再问
            assertEquals(PendingService.Outcome.alreadyAsserted(),
                    pending.propose(proposal(f, PHRASE, qualifiers, timeWords, quoteSpan)));

            // 5. 摇头挡的是这句话：短语进了拒绝记录；换一句话照样能提
            var bad = pending.propose(proposal(f, "closed its office in", qualifiers, timeWords,
                    quoteSpan));
            assertTrue(bad.isProposed(), "换一个短语该是新提议");
            pending.reject(f.kb(), bad.id(), null);
            List<String> rejected = jdbc.queryForList(
                    "SELECT phrase FROM rejected_facts WHERE kb_id = ?", String.class, f.kb());
            assertEquals(List.of("closed its office in"), rejected);
            assertEquals(PendingService.Outcome.rejected(),
                    pending.propose(proposal(f, "closed its office in", qualifiers, timeWords,
                            quoteSpan)));
            assertTrue(pending.propose(proposal(f, "opened a lab in", qualifiers, timeWords,
                    quoteSpan)).isProposed(), "拒绝只挡那一句话，不挡这一对实体之间的一切");
            assertEquals(1, liveFacts(f.kb()), "拒绝不碰图");
        } finally {
            // 删掉夹具自己建的 org，级联带走工作区、知识库与其余一切
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    private PendingService.Proposal proposal(Fixture f, String phrase,
                                             com.fasterxml.jackson.databind.JsonNode qualifiers,
                                             com.fasterxml.jackson.databind.JsonNode timeWords,
                                             int[] quoteSpan) {
        return new PendingService.Proposal(f.kb(), f.acme(), null, f.shenzhen(), null, phrase,
                Validity.empty(), 0.9f, f.chunk(), null, null, phrase, qualifiers, timeWords,
                quoteSpan);
    }
}
