package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.DocumentsService;
import tech.seexun.jknow.store.GraphService;

/**
 * 移植自 tests/store/no_predicate_still_shows.rs（304 行，0010）——没有谓词的事实
 * <b>不能从读路径上消失</b>：facts.predicate_id 改可空之后，二十条读查询里的
 * {@code JOIN relation_types} 全都变成静默过滤器（三值逻辑下「没有值」被当成「不匹配」）。
 * 造一条没有谓词的事实，要求每条读路径都还能看见它；顺带守住显示口径：
 * fact_surface_predicate 取出现最多的那个说法。
 *
 * <p><b>任务 11 去封</b>：entity_detail（任务 6）/entity_history（任务 4/6）/
 * graph_changes（任务 11 随 the_second_clock 落 GraphService.graphChanges）三个读点
 * 全部换真身，断言一字不动。
 */
class NoPredicateStillShowsTests extends HeartStoreTestBase {

    private static final String ORG = "no-predicate-test";

    @Autowired
    GraphService graph;
    @Autowired
    DocumentsService documents;
    @Autowired
    JdbcTemplate jdbc;

    record Fixture(UUID kb, UUID subject, UUID doc, UUID surfaced, UUID mute) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID subject = Uuids.nowV7();
        UUID object = Uuids.nowV7();
        UUID src = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunkA = Uuids.nowV7();
        UUID chunkB = Uuids.nowV7();
        UUID surfaced = Uuids.nowV7();
        UUID mute = Uuids.nowV7();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'no-predicate-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'no-predicate-test')",
                ws, org);
        jdbc.update(
                "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'no-predicate-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'org', '组织')",
                etype, kb);
        for (UUID[] pair : new UUID[][]{{subject, null}, {object, null}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    pair[0], kb, etype, pair[0].equals(subject) ? "Acme" : "Beta");
        }
        jdbc.update("INSERT INTO sources (id, kb_id, name) VALUES (?, ?, '临时来源')", src, kb);
        jdbc.update("""
                INSERT INTO documents (id, kb_id, source_id, filename, sha256, status)
                 VALUES (?, ?, ?, 'note.md', 'nopredicate', 'ready')
                """, doc, kb, src);
        for (Object[] chunk : new Object[][]{{chunkA, 0, "Acme acquired Beta."},
                {chunkB, 1, "Acme acquired Beta again."}}) {
            jdbc.update(
                    "INSERT INTO chunks (id, kb_id, document_id, seq, text) VALUES (?, ?, ?, ?, ?)",
                    chunk[0], kb, doc, chunk[1], chunk[2]);
        }

        // 两条事实都没有谓词——本体里没有对应的关系，这正是可空谓词要表达的状态
        for (Object[] fact : new Object[][]{{surfaced, "2020-01-01T00:00:00Z"},
                {mute, "2021-01-01T00:00:00Z"}}) {
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                        valid_from, valid_from_precision, confidence)
                     VALUES (?, ?, ?, NULL, ?, ?, 'day', 0.9)
                    """, fact[0], kb, subject, object,
                    java.time.OffsetDateTime.parse((String) fact[1]));
        }

        // surfaced 有三条证据：acquired 两次、bought 一次。众数是 acquired——同时验了
        // fact_surface_predicate 的「取出现最多的那个」。mute 只有一条不带原文说法的证据，
        // 模拟 add_evidence 无条件记录之前的老数据
        for (Object[] ev : new Object[][]{{surfaced, chunkA, "acquired"},
                {surfaced, chunkB, "acquired"},
                {surfaced, chunkA, "bought"},
                {mute, chunkA, null}}) {
            jdbc.update("""
                    INSERT INTO fact_evidence (fact_id, chunk_id, document_id, proposed_predicate, quote)
                     VALUES (?, ?, ?, ?, 'Acme acquired Beta.') ON CONFLICT DO NOTHING
                    """, ev[0], ev[1], doc, ev[2]);
        }
        return new Fixture(kb, subject, doc, surfaced, mute);
    }

    /** graph.rs entity_history（任务 6 真身）：x[0]=fact id、x[1]=谓词显示口径。 */
    private List<Object[]> entityHistory(UUID kb, UUID entity, long limit, long offset) {
        return graph.entityHistory(kb, entity, limit, offset).events().stream()
                .map(e -> new Object[]{e.factId(), e.predicateLabel()})
                .toList();
    }

    /** graph.rs graph_changes（任务 11 落地）：x[0]=fact id、x[1]=谓词显示口径。 */
    private List<Object[]> graphChanges(UUID kb, java.time.OffsetDateTime from,
                                        java.time.OffsetDateTime to, UUID source, String kind,
                                        long limit) {
        List<String> kinds = kind == null ? null : List.of(kind);
        return graph.graphChanges(kb, from, to, source, kinds, limit).stream()
                .map(c -> new Object[]{c.factId(), c.predicateLabel()})
                .toList();
    }

    @Test
    void a_fact_without_a_predicate_is_still_visible_everywhere() {
        // 开跑前先扫地：一次失败的跑会给下一次留下垃圾
        jdbc.update("DELETE FROM organizations WHERE name = 'no-predicate-test'");
        Fixture f = seed();
        try {
            // 1. 显示口径：众数胜出，且不是随机的那一个
            String word = jdbc.queryForObject("SELECT fact_surface_predicate(?)", String.class,
                    f.surfaced());
            assertEquals("acquired", word, "该取出现最多的说法");
            String none = jdbc.queryForObject("SELECT fact_surface_predicate(?)", String.class,
                    f.mute());
            assertNull(none, "连原文说法都没有时该是空，而不是编一个词");

            // 2. 图的边：两条都要在，且都标成 inferred
            var edges = graph.neighborhood(f.kb(), f.subject(), 1, null, null).edges();
            var ours = edges.stream()
                    .filter(e -> e.id().equals(f.surfaced()) || e.id().equals(f.mute())).toList();
            assertEquals(2, ours.size(), "内连接会把没有谓词的边整条吞掉");
            var e = ours.stream().filter(x -> x.id().equals(f.surfaced())).findFirst().orElseThrow();
            assertEquals("acquired", e.label());
            assertTrue(e.inferred(), "本体没认下，界面要看得出这词来自原文");
            var m = ours.stream().filter(x -> x.id().equals(f.mute())).findFirst().orElseThrow();
            assertNull(m.label(), "说不出就是说不出，不该回落成「有关联」");

            // 3. 实体面板（任务 6 真身 entityDetail）
            var panel = graph.entityDetail(f.kb(), f.subject(), null, null).facts().stream()
                    .filter(x -> x.id().equals(f.surfaced()) || x.id().equals(f.mute())).toList();
            assertEquals(2, panel.size(), "实体面板漏掉了没有谓词的事实");
            var s = panel.stream().filter(x -> x.id().equals(f.surfaced())).findFirst()
                    .orElseThrow();
            assertEquals("acquired", s.predicateLabel());
            assertTrue(s.inferred());
            assertNull(s.temporal(), "没有谓词就谈不上时态类别");

            // 4. 文档产出（DocViewer 逐块回看）
            var chunkFacts = documents.extractions(f.doc());
            assertTrue(chunkFacts.stream().anyMatch(c -> c.factId().equals(f.surfaced())),
                    "文档页看不见没有谓词的事实");
            assertTrue(chunkFacts.stream().anyMatch(c -> c.factId().equals(f.mute())),
                    "文档页看不见没有说法的事实");
            assertTrue(chunkFacts.stream().noneMatch(
                    tech.seexun.jknow.store.entity.ChunkFactView::isOpenStatement),
                    "历史投影即便有原文说法，也不能用投影 ID 添加陈述案例");

            // 宾语是字面值的那条：阅读页右栏只认 canonical_name 的时候，它在界面上是
            // 主语加短语、后面空着一片（「Hugging Face, Inc. known as」）
            UUID valued = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, layer, phrase, object_value, confidence)
                     VALUES (?, ?, ?, 'open', 'known as', jsonb_build_object('value', 'Acme Inc.'), 0.9)
                    """, valued, f.kb(), f.subject());
            jdbc.update("""
                    INSERT INTO fact_evidence (fact_id, chunk_id, document_id, quote)
                     SELECT ?, chunk_id, document_id, 'Acme Inc.' FROM fact_evidence WHERE fact_id = ? LIMIT 1
                    """, valued, f.surfaced());
            var valuedFacts = documents.extractions(f.doc());
            var v = valuedFacts.stream().filter(c -> c.factId().equals(valued)).findFirst()
                    .orElseThrow();
            assertEquals("Acme Inc.", v.object(), "值宾语在文档页是空的");
            assertNull(v.objectId(), "字面值没有实体可跳");
            assertTrue(v.isOpenStatement(), "有短语的原始陈述可添加案例");
            assertEquals("known as", v.predicate(), "确认案例要显示原文短语");

            // 5. 实体历史（外层 FROM 是 CTE，别名写错会直接报 missing FROM-clause）
            var hist = entityHistory(f.kb(), f.subject(), 50, 0);
            var h = hist.stream().filter(x -> f.surfaced().equals(x[0])).findFirst().orElseThrow();
            assertEquals("acquired", h[1]);

            // 6. 账本变更流
            var changes = graphChanges(f.kb(), java.time.OffsetDateTime.parse("2000-01-01T00:00:00Z"),
                    java.time.OffsetDateTime.parse("2100-01-01T00:00:00Z"), null, null, 50);
            var c = changes.stream().filter(x -> f.surfaced().equals(x[0])).findFirst()
                    .orElseThrow();
            assertEquals("acquired", c[1]);

            // 7. 不再有任何机制能把它长回来：本体里不该有代码硬塞进去的关系
            List<String> seeded = jdbc.queryForList(
                    "SELECT key FROM relation_types WHERE kb_id = ? AND builtin", String.class,
                    f.kb());
            assertTrue(seeded.isEmpty(), "本体里不该有代码硬塞进去的关系，实得 " + seeded);
        } finally {
            // 无论断言是否炸，都把临时数据拆干净（下层全是 ON DELETE CASCADE）
            jdbc.update("DELETE FROM organizations WHERE name = 'no-predicate-test'");
        }
    }
}
