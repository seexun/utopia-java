package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.VectorIndexService;
import tech.seexun.jknow.store.VectorIndexService.Target;

/**
 * 移植自 tests/store/the_nearest_chunk_is_found_however_it_is_reached.rs（1 例，
 * the_answers_agree_with_and_without_the_index）——向量召回的答案不随计划变（0035/#512），
 * 打在真库上：每一问没有索引问一遍、build 之后再问一遍，两遍必须一致。走不走索引由
 * 规划器定，这个测试钉的是<b>答案</b>；六问各自坏一种方式（表达式索引拼错悄悄不生效/
 * 小库 LIMIT 回不满/串库/另一维度并存/顶掉的不是命中/带时刻检索看到当时的）。
 *
 * <p>与 Rust 同一独占维度（DIMS=7，别的连库测试用 3/4 维——CROWD 用 LCG 可复现伪随机，
 * 失败能重放）。距离并列由 id 定（#652）：并列时精确路径与 HNSW 各排各的，不定死就会
 * 「索引改了答案」时红时绿——期望值按 uuid 的字节序取小（与 PG uuid 排序、Rust Uuid Ord
 * 同一套；Java UUID.compareTo 的有符号比较对 v7 等价，这里仍显式按无符号字节序写）。
 */
class TheNearestChunkIsFoundHoweverItIsReachedTests extends HeartStoreTestBase {

    /** 这个测试独占的维度：其它连库测试用 3 维，索引按维度分开，互不打扰。 */
    private static final int DIMS = 7;
    private static final int CROWD = 2000;

    @Autowired
    VectorIndexService vectorIndexes;

    private record Fixture(UUID org, UUID big, UUID small, UUID planted, UUID superseded,
                           UUID only, UUID otherDims) {
    }

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    /** 可复现的伪随机向量：同一粒种子同一批向量，失败了能重放。 */
    private static float lcg(long[] seed) {
        seed[0] = seed[0] * 6364136223846793005L + 1442695040888963407L;
        return ((seed[0] >>> 33) / (float) (1L << 31)) - 0.5f;
    }

    /** 查询向量（与夹具里 planted/superseded 两条重合）。 */
    private static float[] query() {
        return new float[] {0.11f, -0.42f, 0.37f, 0.05f, -0.28f, 0.19f, 0.33f};
    }

    /** uuid 字节序取小（Rust Uuid::min / PG uuid 排序同一套，无符号大端）。 */
    private static UUID uuidMin(UUID a, UUID b) {
        int hi = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        if (hi != 0) {
            return hi < 0 ? a : b;
        }
        return Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits()) < 0
                ? a : b;
    }

    private static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID big = Uuids.nowV7();
        UUID small = Uuids.nowV7();
        UUID docBig = Uuids.nowV7();
        UUID docSmall = Uuids.nowV7();
        UUID planted = Uuids.nowV7();
        UUID superseded = Uuids.nowV7();
        UUID only = Uuids.nowV7();
        UUID otherDims = Uuids.nowV7();
        String tag = Uuids.nowV7().toString();
        OffsetDateTime feb = t("2026-02-01T00:00:00Z");

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'plan-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'plan-test')",
                ws, org);
        for (Object[] kb : new Object[][] {
                {big, "big", docBig}, {small, "small", docSmall}}) {
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)",
                    kb[0], ws, kb[1]);
            jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256, status, created_at)"
                            + " VALUES (?, ?, ?, ?, 'ready', ?)",
                    kb[2], kb[0], kb[1] + ".md", "sha-" + tag + "-" + kb[1], feb);
        }

        List<Object[]> crowd = new ArrayList<>(CROWD + 3);
        long[] seed = {0x5eedL};
        for (int i = 0; i < CROWD; i++) {
            float[] v = new float[DIMS];
            for (int k = 0; k < DIMS; k++) {
                v[k] = lcg(seed);
            }
            crowd.add(new Object[] {Uuids.nowV7(), big, docBig, i, "crowd " + i,
                    vectorLiteral(v), feb});
        }
        // 与查询重合的一条：活着的、五月被顶掉的各一（superseded_at 走 UPDATE——批量插入
        // 保持同一组占位符）
        crowd.add(new Object[] {planted, big, docBig, CROWD, "the planted one",
                vectorLiteral(query()), feb});
        crowd.add(new Object[] {superseded, big, docBig, CROWD + 1,
                "the one a reparse displaced", vectorLiteral(query()), feb});
        // 换过模型留下的 5 维一条
        crowd.add(new Object[] {otherDims, big, docBig, CROWD + 2, "five dims",
                "[1.0,0.0,0.0,0.0,0.0]", feb});
        jdbc.batchUpdate("INSERT INTO chunks (id, kb_id, document_id, seq, text, embedding,"
                + " created_at) VALUES (?, ?, ?, ?, ?, ?::vector, ?)", crowd);
        jdbc.update("UPDATE chunks SET superseded_at = ? WHERE id = ?",
                t("2026-05-01T00:00:00Z"), superseded);
        // 小库：一条，离查询不近不远
        jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text, embedding, created_at)"
                        + " VALUES (?, ?, ?, 0, 'the only one', ?::vector, ?)",
                only, small, docSmall, "[0.3,-0.2,0.1,0.4,-0.1,0.2,0.0]", feb);

        return new Fixture(org, big, small, planted, superseded, only, otherDims);
    }

    /** 六个问题的答案，一次问完；两种计划下各取一份来比。 */
    private record Answers(UUID nearestInBig, List<UUID> smallLimit10, List<UUID> otherDimsQuery,
                           boolean bigTop10HasSuperseded, boolean bigTop10HasOtherDims,
                           UUID asOfMarchFirst) {
    }

    private Answers ask(Fixture f) {
        List<UUID> bigNow = vectorIndexes.chunkVectorSearch(f.big(), query(), 10, null);
        List<UUID> smallNow = vectorIndexes.chunkVectorSearch(f.small(), query(), 10, null);
        List<UUID> five = vectorIndexes.chunkVectorSearch(f.big(),
                new float[] {1f, 0f, 0f, 0f, 0f}, 10, null);
        List<UUID> bigThen = vectorIndexes.chunkVectorSearch(f.big(), query(), 10,
                t("2026-03-01T00:00:00Z"));
        return new Answers(bigNow.isEmpty() ? null : bigNow.get(0), smallNow, five,
                bigNow.contains(f.superseded()), bigNow.contains(f.otherDims()),
                bigThen.isEmpty() ? null : bigThen.get(0));
    }

    @Test
    void theAnswersAgreeWithAndWithoutTheIndex() throws Exception {
        Fixture f = seed();
        try {
            // ---- 一、没有索引：精确路径给出的答案
            vectorIndexes.drop(Target.CHUNKS, DIMS);
            assertEquals(null, vectorIndexes.status(Target.CHUNKS, DIMS));
            Answers exact = ask(f);
            assertEquals(f.planted(), exact.nearestInBig(), "重合的那一条最近");
            assertEquals(List.of(f.only()), exact.smallLimit10(),
                    "小库只有一条，LIMIT 10 就回这一条——不多不少，也不串大库");
            assertEquals(List.of(f.otherDims()), exact.otherDimsQuery(), "5 维的查询只找 5 维的");
            assertFalse(exact.bigTop10HasSuperseded(), "顶掉的不是命中");
            assertFalse(exact.bigTop10HasOtherDims(), "7 维的查询看不见 5 维的");
            // 三月一日：顶掉的那条还活着，它与查询重合——两条向量一样，距离并列，由 id 定。
            // 不定的话精确路径和 HNSW 各排各的，下面「索引改了答案」就会时红时绿（#652）
            assertEquals(uuidMin(f.planted(), f.superseded()), exact.asOfMarchFirst(),
                    "距离并列由 id 定");
            List<UUID> then = vectorIndexes.chunkVectorSearch(f.big(), query(), 2,
                    t("2026-03-01T00:00:00Z"));
            assertTrue(then.contains(f.superseded()), "三月一日它还活着，带时刻的检索该看见它");

            // ---- 二、建索引：任务本体，事务外，两次幂等
            VectorIndexService.Built built = vectorIndexes.build(Target.CHUNKS, DIMS);
            assertTrue(built.created(), "第一次建");
            assertEquals("chunks_embedding_hnsw_7", built.name());
            assertEquals(Boolean.TRUE, vectorIndexes.status(Target.CHUNKS, DIMS));
            VectorIndexService.Built again = vectorIndexes.build(Target.CHUNKS, DIMS);
            assertFalse(again.created(), "第二次是空操作");
            String def = jdbc.queryForObject(
                    "SELECT pg_get_indexdef(indexrelid) FROM pg_index"
                            + " WHERE indexrelid = 'chunks_embedding_hnsw_7'::regclass",
                    String.class);
            assertTrue(def.contains("USING hnsw"), def);
            assertTrue(def.contains("vector_cosine_ops"), def);
            assertTrue(def.contains("WHERE (vector_dims(embedding) = 7)"), def);

            // ---- 三、有索引：同样六问，答案必须一样
            Answers indexed = ask(f);
            assertEquals(exact, indexed, "索引改了答案");
        } finally {
            try {
                vectorIndexes.drop(Target.CHUNKS, DIMS);
            } catch (Exception ignored) {
                // 断言 panic 也要尽量拆——Rust 侧 teardown 同样不放大失败
            }
            jdbc.update("DELETE FROM knowledge_bases WHERE id IN (?, ?)", f.big(), f.small());
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
