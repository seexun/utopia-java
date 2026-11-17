package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.temporal.TemporalService;

/**
 * 移植自 tests/store/closing_an_unconfirmed_fact_clears_the_queue.rs（248 行）——「闭合」
 * 这个出路要真的能清掉一条「已不再被陈述」的事实：修正行带着同一批证据、但已经有终点，
 * 不再是「现行」（0022：「结束不知哪天」不是开放）；counts 与列表同一套 WHERE 一起归零。
 *
 * <p>等价点：stale_facts 走 ReviewService unconfirmed 队列（graph.rs:2007-2031 同一套
 * WHERE，M1 已落）；counts 走 ReviewService.counts；close_superseded 走
 * TemporalService（任务 2 真身）。
 */
class ClosingAnUnconfirmedFactClearsTheQueueTests extends HeartStoreTestBase {

    @Autowired
    ReviewService review;
    @Autowired
    TemporalService temporal;

    record Fx(UUID org, UUID kb, UUID fact, UUID chunk) {
    }

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    private Fx seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID rel = Uuids.nowV7();
        UUID subject = Uuids.nowV7();
        UUID object = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        UUID fact = Uuids.nowV7();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'stale-close-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'stale-close-test')",
                ws, org);
        jdbc.update(
                "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'stale-close-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        jdbc.update(
                "INSERT INTO relation_types (id, kb_id, key, label, temporal) VALUES (?, ?, 'leads', 'leads', 'state')",
                rel, kb);
        for (Object[] e : new Object[][]{{subject, "Akkaraju"}, {object, "Weta"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    e[0], kb, etype, e[1]);
        }
        jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256) VALUES (?, ?, 'a.md', 'sha-a')",
                doc, kb);
        // 第一版的分块：证据就落在这里，文档出新版后它会被取代
        jdbc.update("""
                INSERT INTO chunks (id, kb_id, document_id, seq, text, doc_version)
                 VALUES (?, ?, ?, 0, 'Akkaraju leads Weta', 1)
                """, chunk, kb, doc);
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence)
                 VALUES (?, ?, ?, ?, ?, 0.9)
                """, fact, kb, subject, rel, object);
        jdbc.update(
                "INSERT INTO fact_evidence (fact_id, chunk_id, quote) VALUES (?, ?, 'Akkaraju leads Weta')",
                fact, chunk);
        return new Fx(org, kb, fact, chunk);
    }

    private List<UUID> staleIds(UUID kb) {
        return review.items(kb, "unconfirmed", null, 50, 0).stream()
                .map(x -> ((tech.seexun.jknow.store.entity.FactReviewItem) x).id()).toList();
    }

    @Test
    void a_fact_closed_from_that_queue_leaves_it() {
        Fx f = seed();
        try {
            // 文档出了新版，旧分块被取代——证据只剩下停在旧版的那一条，这一档该列它
            jdbc.update("UPDATE chunks SET superseded_at = now() WHERE id = ?", f.chunk());
            assertEquals(List.of(f.fact()), staleIds(f.kb()));
            assertEquals(1, review.counts(f.kb()).unconfirmed());

            // 人裁决「它在 2021 年 1 月结束了」——月精度照写，服务端截到那个月的 1 日
            UUID corrected = temporal.closeSuperseded(f.fact(), t("2021-01-01T00:00:00Z"), "month");
            assertTrue(corrected != null, "闭合要改写出修正行");

            // **修正行带着同一批证据，但它已经有终点了，不再是「现行」。**
            // 少了这一条，它立刻回到列表里——用户看到的正是「点了闭合，条目还在」
            assertTrue(staleIds(f.kb()).isEmpty(), "闭合过的行要离开这一档");
            assertEquals(0, review.counts(f.kb()).unconfirmed(), "左栏的计数与列表同一套 WHERE");

            // 修正行自己：证据复制过来了，终点是写明的——判据靠的是上界那一句，不是证据没了
            Long copied = jdbc.queryForObject(
                    "SELECT count(*) FROM fact_evidence WHERE fact_id = ?", Long.class, corrected);
            assertEquals(1L, copied, "证据随修正行复制");
            OffsetDateTime end = jdbc.queryForObject("SELECT valid_to FROM facts WHERE id = ?",
                    OffsetDateTime.class, corrected);
            assertTrue(end != null, "修正行的终点是写明的");
        } finally {
            // 删组织不级联到库：先删库，再删组织与工作区
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /**
     * 反面：「结束了，不知哪天」的行也不算现行（0022）。只看 valid_to IS NULL 会把这条
     * 留在队列里——它已经不成立了，问它没有意义。
     */
    @Test
    void an_ended_unknown_row_is_not_current_either() {
        Fx f = seed();
        try {
            jdbc.update("UPDATE chunks SET superseded_at = now() WHERE id = ?", f.chunk());
            assertEquals(1, staleIds(f.kb()).size());

            // 锚点必须一起给：0022 的 CHECK 把「结束不知哪天」和说出结束的那份文档绑在一起
            jdbc.update(
                    "UPDATE facts SET valid_to_precision = 'unknown', attested_to = now() WHERE id = ?",
                    f.fact());
            assertTrue(staleIds(f.kb()).isEmpty(), "结束不知哪天的行不是现行");
            assertEquals(0, review.counts(f.kb()).unconfirmed());
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
