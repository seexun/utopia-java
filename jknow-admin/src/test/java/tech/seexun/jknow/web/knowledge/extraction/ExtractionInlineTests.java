package tech.seexun.jknow.web.knowledge.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.store.HeartStoreTestBase;

import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 crates/utopia-server/src/extraction.rs 内联 mod origin_ceiling_tests（1 例）+
 * mod tests（span_in_quote 1 例 + incomplete_reason 3 例）+ 两个真库 tokio 测试
 * （namesake 句柄运行时 A-B-C / #270 画像并列）——断言逐条不改写。
 *
 * <p>纯函数部分直接调 {@link ExtractionJob} 的静态；真库部分自建自拆，绝不碰已有库。
 */
class ExtractionInlineTests extends HeartStoreTestBase {

    // ---- origin_ceiling_tests ----

    @Test
    void aDescribedFactCannotCloseAValueByItself() {
        float ceiling = ExtractionJob.originCeiling("described", 0.95f);
        assertTrue(ceiling < tech.seexun.jknow.store.ReviewService.LOW_CONFIDENCE_BELOW);
        // it still enters the graph
        assertEquals(ExtractionJob.DESCRIBED_CEILING, ceiling);
        assertEquals(0.62f, ExtractionJob.originCeiling("described", 0.62f));
        for (String origin : new String[]{"stated", "ocr", "transcribed"}) {
            assertEquals(0.95f, ExtractionJob.originCeiling(origin, 0.95f));
        }
    }

    // ---- mod tests ----

    @Test
    void aSpanIsFoundInItsQuoteRegardlessOfCaseAndSpacing() {
        assertTrue(ExtractionJob.spanInQuote("former openai   personnel",
                "Former OpenAI personnel have founded"));
        assertTrue(!ExtractionJob.spanInQuote("OpenAI staff",
                "Former OpenAI personnel have founded"));
        assertTrue(!ExtractionJob.spanInQuote("", "anything"));
    }

    /** 这条判据存在的理由：一次网络抖动让六篇文档 60 块里只抽成 12 块，六篇全部显示「抽取完成」。 */
    @Test
    void aDocumentWithASkippedChunkIsNotComplete() {
        assertNull(ExtractionJob.incompleteReason(List.of(), 23), "全抽完才算完成");
        List<Map.Entry<Integer, String>> one =
                List.of(new AbstractMap.SimpleEntry<>(7, "request failed: timeout"));
        String msg = ExtractionJob.incompleteReason(one, 23);
        assertTrue(msg != null, "有块没抽成就不该算完成");
        assertTrue(msg.contains("23"), "分母要说出来：" + msg);
        assertTrue(msg.contains("#7"), "得指得出是哪一块：" + msg);
    }

    /** 原因往往是同一个，举三个够了，但剩下多少必须说。 */
    @Test
    void manyFailuresAreSummarisedWithoutHidingTheCount() {
        List<Map.Entry<Integer, String>> many = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            many.add(new AbstractMap.SimpleEntry<>(i, "request failed"));
        }
        String msg = ExtractionJob.incompleteReason(many, 60);
        assertTrue(msg.contains("20"), "总数要在：" + msg);
        assertTrue(msg.contains("; 17 more"), "省略掉的数量要说出来：" + msg);
        assertEquals(3, count(msg, "request failed"), "只举三个");
    }

    /** 重试时只取还没抽的块，分母是本轮的数。 */
    @Test
    void theDenominatorIsThisRoundsChunksNotTheDocument() {
        String msg = ExtractionJob.incompleteReason(
                List.of(new AbstractMap.SimpleEntry<>(2, "x")), 3);
        assertTrue(msg.startsWith("1 of 3 chunks could not be extracted in this attempt:"), msg);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int at = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }

    // ---- 审查收口（M-1/M-2/M-3）----

    /** M-2：locate 的偏移按码点算（emoji 占两个 UTF-16 unit，PG 的 substr 按字符数）。 */
    @Test
    void locateOffsetsAreCodePointNotUtf16() {
        String chunk = "前😀后 Acme is based in London.";
        int[] at = ExtractionJob.locate(chunk, "Acme is based");
        assertEquals(4, at[0], "前=0 😀=1 后=2 空格=3，Acme 从码点 4 起（UTF-16 会算成 5）");
        assertEquals(17, at[1]);
        int[] one = ExtractionJob.locate("😀😀", "😀");
        assertEquals(0, one[0]);
        assertEquals(1, one[1], "一个 emoji 是一个码点宽");
    }

    /** M-2：空白 = Unicode White_Space（含 NBSP——isWhitespace/(?U)\s 都不含）。 */
    @Test
    void nbspFoldsLikeAnyOtherWhitespace() {
        int[] at = ExtractionJob.locate("a\u00A0\u00A0b", "a b");
        assertEquals(0, at[0]);
        assertEquals(4, at[1]);
        assertTrue(ExtractionJob.spanInQuote("former\u00A0openai personnel",
                "Former OpenAI personnel have founded"), "NBSP 也是一段空白");
    }

    /** M-1：标题行先整行去空白再数 #——行首带空白的标题时间照样认。 */
    @Test
    void aHeadingWithLeadingWhitespaceStillGovernsItsTimeWords() {
        String chunk = "# Report\n\n  ## 2025年第三季度\n\n销售额创新高。";
        ExtractionJob.Quote quote = new ExtractionJob.Quote("销售额创新高。",
                ExtractionJob.locate(chunk, "销售额创新高。"));
        Integer start = ExtractionJob.locateTime(chunk, quote, "2025年第三季度");
        assertEquals(Integer.valueOf(
                chunk.codePointCount(0, chunk.indexOf("2025年第三季度"))), start);
    }

    /** M-3：eq_ignore_ascii_case 只折 ASCII——Kelvin 记号 K 不是 k。 */
    @Test
    void caseFoldingForPhraseComparisonsIsAsciiOnly() {
        assertTrue(ExtractionJob.sameWords("Revenue", "revenue"));
        assertTrue(ExtractionJob.sameWords("REVENUE", "revenue"));
        assertTrue(!ExtractionJob.sameWords("K", "k"),
                "KELVIN SIGN 的 Unicode 小写恰是 k，String.equalsIgnoreCase 会判等");
        assertTrue(!ExtractionJob.sameWords("Straße", "STRASSE"));
    }

    // ---- 真库：句柄运行时（namesake_handles_keep_fact_attribution_and_a_fresh_handle_gets_c）----

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ExtractionJob extraction;
    @Autowired
    ResolutionService resolution;
    @Autowired
    GraphWriteService graph;
    @Autowired
    ReviewService reviews;

    @Test
    void namesakeHandlesKeepFactAttributionAndAFreshHandleGetsC() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'handle-runtime-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'handle-runtime-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'handle-runtime-test')",
                kb, ws);
        UUID person = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                person, kb);
        try {
            ExtractionJob.RunState st = new ExtractionJob.RunState();
            Map<String, List<UUID>> responseClaims = new HashMap<>();
            UUID a = extraction.resolveHandle(kb, person, "Zhang Wei", null, null, null,
                    responseClaims, st);
            UUID b = extraction.resolveHandle(kb, person, "Zhang Wei", null, null, null,
                    responseClaims, st);
            assertNotEquals(a, b);
            assertTrue(st.humanReviewsFound);
            assertTrue(!st.needsAdjudication, "namesakes must not wake the LLM worker");

            // 同一回复里两个句柄各绑各的实体：事实跟着句柄走，不跟着名字走
            Map<String, UUID> refs = Map.of("e1", a, "e2", b);
            UUID finance = Uuids.nowV7();
            UUID platform = Uuids.nowV7();
            UUID worksAt = Uuids.nowV7();
            jdbc.update(
                    "INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, 'works_at', 'works at')",
                    worksAt, kb);
            for (Object[] row : new Object[][]{{finance, "Finance"},
                    {platform, "Platform Engineering"}}) {
                jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, ?)",
                        row[0], kb, row[1]);
            }
            for (Object[] row : new Object[][]{{a, finance}, {b, platform}}) {
                graph.insertFact(kb, (UUID) row[0], worksAt, (UUID) row[1], Validity.empty(),
                        0.9f);
            }
            List<Object[]> pairs = jdbc.query(
                    // 只看边：实体身上还有名字事实（0041），那些没有宾语实体
                    "SELECT subject_id, object_id FROM facts WHERE kb_id = ? AND object_id IS NOT NULL"
                            + " ORDER BY subject_id",
                    (rs, i) -> new Object[]{rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class)}, kb);
            assertTrue(containsPair(pairs, a, finance));
            assertTrue(containsPair(pairs, b, platform));
            assertTrue(!containsPair(pairs, a, platform));
            assertTrue(!containsPair(pairs, b, finance));
            resolution.refreshDisambiguators(kb, "Zhang Wei");
            List<Object[]> labels = jdbc.query(
                    "SELECT id, disambiguator FROM entities WHERE id = ANY(?) ORDER BY id",
                    (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)},
                    (Object) new UUID[]{a, b});
            assertTrue(labels.stream().anyMatch(
                    x -> a.equals(x[0]) && "Finance".equals(x[1])));
            assertTrue(labels.stream().anyMatch(
                    x -> b.equals(x[0]) && "Platform Engineering".equals(x[1])));

            // 后一次回复给同一个名字开了一个新句柄，没挑 A 或 B：文本里没有挑的依据，
            // 不许猜。事实落到一个文档级的 C 上，对 A、对 B 各一条人工审核对
            Map<String, List<UUID>> laterResponseClaims = new HashMap<>();
            UUID c = extraction.resolveHandle(kb, person, "Zhang Wei", null, null, null,
                    laterResponseClaims, st);
            assertNotEquals(c, a);
            assertNotEquals(c, b);
            graph.insertFact(kb, c, worksAt, finance, Validity.empty(), 0.9f);
            // 再往后的回复再开一个新句柄，仍是那个 C：一个新拼写不是猜 A 或 B 的许可
            Map<String, List<UUID>> anotherResponseClaims = new HashMap<>();
            UUID cAgain = extraction.resolveHandle(kb, null, "Zhang Wei", null, null, null,
                    anotherResponseClaims, st);
            assertEquals(c, cAgain,
                    "a later response cannot evade A/B ambiguity by inventing a new e-handle");
            graph.insertFact(kb, cAgain, worksAt, platform, Validity.empty(), 0.9f);
            List<UUID> cObjects = jdbc.queryForList(
                    "SELECT object_id FROM facts WHERE kb_id = ? AND subject_id = ?"
                            + " AND object_id IS NOT NULL ORDER BY object_id",
                    UUID.class, kb, c);
            assertEquals(2, cObjects.size());
            assertTrue(cObjects.contains(finance));
            assertTrue(cObjects.contains(platform));

            List<tech.seexun.jknow.store.entity.ReviewItem> found =
                    (List<tech.seexun.jknow.store.entity.ReviewItem>) reviews.items(kb,
                            "duplicates", null, 10, 0);
            assertEquals(3, found.size(), "A/B, C/A and C/B");
            assertTrue(found.stream().allMatch(r -> "human".equals(r.stage())));
            assertTrue(resolution.pendingAdjudications(kb, 10).isEmpty());
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    private static boolean containsPair(List<Object[]> pairs, UUID subject, UUID object) {
        for (Object[] p : pairs) {
            if (subject.equals(p[0]) && object.equals(p[1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * #270：跨文档的同名并列——库里已有两个「张伟」，画像质心一模一样（同一 chunk 播的
     * 种）。新提及对两人打出同一个分。旧路径在最高分 ≥ SIM_ATTACH 时静默 attach 到先遇
     * 到的那个；修好之后要：新建第三个实体、两条<b>人工</b>审核对、且绝不唤醒 LLM 裁决器。
     */
    @Test
    void aProfileTieAcrossDocumentsFilesHumanReviewsNotAnAttach() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'profile-tie-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'profile-tie-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'profile-tie-test')",
                kb, ws);
        UUID person = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                person, kb);
        try {
            // 两个同名的人，画像向量完全相同
            UUID a = Uuids.nowV7();
            UUID b = Uuids.nowV7();
            for (UUID id : new UUID[]{a, b}) {
                jdbc.update(
                        "INSERT INTO entities (id, kb_id, type_id, canonical_name, profile_embedding,"
                                + " profile_n) VALUES (?, ?, ?, 'Zhang Wei', '[1,0,0]', 1)",
                        id, kb, person);
            }

            // 与两人画像都一致的上下文：打出的余弦相同 → 分不开
            float[] ctx = {1.0f, 0.0f, 0.0f};
            ExtractionJob.RunState st = new ExtractionJob.RunState();
            Map<String, List<UUID>> responseClaims = new HashMap<>();
            UUID c = extraction.resolveHandle(kb, person, "Zhang Wei", ctx, null, null,
                    responseClaims, st);

            assertNotEquals(c, a, "画像并列不该 attach 到 A——那是候选顺序掷出的硬币");
            assertNotEquals(c, b, "画像并列不该 attach 到 B——那是候选顺序掷出的硬币");
            assertTrue(!st.needsAdjudication, "同名并列只能等人裁，绝不该唤醒 LLM 裁决器");

            List<tech.seexun.jknow.store.entity.ReviewItem> found =
                    (List<tech.seexun.jknow.store.entity.ReviewItem>) reviews.items(kb,
                            "duplicates", null, 10, 0);
            assertEquals(2, found.size(), "对 A、对 B 各一条审核对");
            assertTrue(found.stream().allMatch(r -> "human".equals(r.stage())),
                    "同名并列的审核对必须是人工阶段");
            assertTrue(resolution.pendingAdjudications(kb, 10).isEmpty(),
                    "人工审核对绝不能落进批量裁决队列，否则又会被自动并掉");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
