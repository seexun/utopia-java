package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.TimeMentionsService;

/**
 * 移植自 tests/store/a_time_mention_is_words_not_a_date.rs（202 行）——一条时间提及是
 * 字，不是日期（0044 第一刀，#729；0045）：落库的是那几个字和它在 chunks.text 里的
 * <b>字符</b>偏移（不是字节）。等价点：无（直译；char 偏移 Java String 与 Rust chars
 * 同为 Unicode 码点计数）。
 */
class ATimeMentionIsWordsNotADateTests extends HeartStoreTestBase {

    private static final String ORG = "time-mention-test";
    private static final String TEXT = "星云科技去年冬天收购了北斗软件，作价十二亿元。";

    @Autowired
    GraphWriteService writes;
    @Autowired
    TimeMentionsService timeMentions;

    record Fixture(UUID org, UUID kb, UUID nebula, UUID beidou, UUID chunk) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID nebula = Uuids.nowV7();
        UUID beidou = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, ORG);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org, ORG);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)", kb,
                ws, ORG);
        for (Object[] e : new Object[][]{{nebula, "星云科技"}, {beidou, "北斗软件"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, ?)",
                    e[0], kb, e[1]);
        }
        jdbc.update("""
                INSERT INTO documents (id, kb_id, filename, sha256, status)
                 VALUES (?, ?, 'deal.txt', 'timemention', 'ready')
                """, doc, kb);
        jdbc.update(
                "INSERT INTO chunks (id, kb_id, document_id, seq, text) VALUES (?, ?, ?, 0, ?)",
                chunk, kb, doc, TEXT);
        return new Fixture(org, kb, nebula, beidou, chunk);
    }

    @Test
    void a_time_mention_resolves_inside_the_chunk_and_sets_no_date() {
        // Rust 原文开头清一次同名 org（历史残留），Java 侧同样自建自拆即可
        jdbc.update("DELETE FROM organizations WHERE name = ?", ORG);
        Fixture f = seed();
        try {
            var fact = writes.insertOpenStatement(f.kb(), f.nebula(), "收购了", f.beidou(), null,
                    null, 0.9f);
            writes.addEvidence(fact.id(), f.chunk(), TEXT, "收购了");

            // 服务端在块里搜出来的字符偏移
            String words = "去年冬天";
            // Rust str::find 给的是 UTF-8 字节偏移——Java 对齐用前缀的 UTF-8 字节数
            int charStart = TEXT.indexOf(words);
            int byteStart = TEXT.substring(0, charStart).getBytes(StandardCharsets.UTF_8).length;
            assertEquals(4, charStart, "「星野科技」四个字之后");
            assertTrue(byteStart != charStart, "中文里字节偏移与字符偏移不同");
            UUID id = timeMentions.record(f.kb(), fact.id(), f.chunk(), words, charStart, "when");

            // 字与偏移在块里对得上：按字符数到那里，取同样多的字，得到的就是记下的字
            var stored = jdbc.queryForMap(
                    "SELECT text, char_start FROM time_mentions WHERE id = ?", id);
            String storedText = (String) stored.get("text");
            int storedStart = (Integer) stored.get("char_start");
            String chunkText = jdbc.queryForObject("SELECT text FROM chunks WHERE id = ?",
                    String.class, f.chunk());
            String at = chunkText.substring(storedStart, storedStart + storedText.length());
            assertEquals(words, at);
            assertEquals(words, storedText);
            // Postgres 的 substr 也按字符数：界面用它高亮时指的是同一处
            String bySql = jdbc.queryForObject("""
                    SELECT substr(c.text, m.char_start + 1, char_length(m.text))
                     FROM time_mentions m JOIN chunks c ON c.id = m.chunk_id WHERE m.id = ?
                    """, String.class, id);
            assertEquals(words, bySql);

            // 字不是日期：陈述在世界轴上没有位置
            var span = jdbc.queryForMap("""
                    SELECT valid_from, valid_to, valid_from_precision, valid_to_precision
                     FROM facts WHERE id = ?
                    """, fact.id());
            assertTrue(span.get("valid_from") == null && span.get("valid_from_precision") == null);
            assertTrue(span.get("valid_to") == null && span.get("valid_to_precision") == null);

            var mentions = timeMentions.forFacts(java.util.List.of(fact.id()));
            assertEquals(1, mentions.get(fact.id()).size());
            var m = mentions.get(fact.id()).get(0);
            assertEquals(f.chunk(), m.chunkId());
            assertEquals("when", m.role());
            // 刚记下的字还没有读法：解释与解算都空着
            assertTrue(m.shape() == null && m.reference() == null && m.granularity() == null);
            assertTrue(m.grade() == null && m.resolvedFrom() == null && m.resolvedTo() == null);

            // 起与止各是一条提及：同一处字换一个槽是另一行，同一个槽再记一次是同一行
            UUID ended = timeMentions.record(f.kb(), fact.id(), f.chunk(), words, charStart,
                    "ended");
            assertNotEquals(id, ended);
            assertEquals(id, timeMentions.record(f.kb(), fact.id(), f.chunk(), words, charStart,
                    "when"));
            var again = timeMentions.forFacts(java.util.List.of(fact.id()));
            assertEquals(java.util.List.of("ended", "when"),
                    again.get(fact.id()).stream().map(x -> x.role()).toList());
            // 槽只有这两个（库上的 CHECK 把关）
            assertThrows(Exception.class, () -> timeMentions.record(f.kb(), fact.id(), f.chunk(),
                    words, charStart, "as_of"), "the ledger takes only when / ended");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
