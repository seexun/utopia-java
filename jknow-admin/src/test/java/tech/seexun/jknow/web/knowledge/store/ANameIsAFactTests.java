package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.NamesService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.ReviewService;

/**
 * 移植自 tests/store/a_name_is_a_fact.rs（437 行，7 例）——名字是关于实体的一条事实
 * （0041 决定 1）：建实体时本名就是一条名字事实；记下的别名让后来的提及找得到它；
 * 名字不进事实列表、不算度数；合并把名字当普通事实搬走，撤回合并再搬回来。
 *
 * <p>等价点（M3 任务 4 起）：resolve_mention/existing_by_name/merge_entities/revert_merge
 * 走 ResolutionService 真身；entity_detail 核心读（任务 6 起）走 GraphService 真身；
 * quotes_of（治理域 M4）仍走 DetailStubs。断言一字不改。
 */
class ANameIsAFactTests extends HeartStoreTestBase {

    @Autowired
    NamesService names;
    @Autowired
    OntologyService ontology;
    @Autowired
    ReviewService review;
    @Autowired
    DocumentsWriteService documents;
    @Autowired
    ResolutionService resolution;
    @Autowired
    tech.seexun.jknow.store.GovernanceService governance;
    @Autowired
    tech.seexun.jknow.store.GraphService graph;

    record Fixture(UUID org, UUID kb, UUID equipment) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID equipment = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'names-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'names-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'names-test')",
                kb, ws);
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'equipment', 'Equipment')",
                equipment, kb);
        return new Fixture(org, kb, equipment);
    }

    private void teardown(Fixture f) {
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    /** 不给向量：召回到候选就走「并到事实最多的那个」，量的正是召回找不找得到。 */
    private ResolutionService.ResolutionResult mention(Fixture f, String name) {
        return resolution.resolveMention(f.kb(), f.equipment(), name, null, null, null, List.of());
    }

    private List<String> values(List<tech.seexun.jknow.store.entity.NameView> v) {
        List<String> out = new ArrayList<>(v.stream().map(n -> n.name()).toList());
        java.util.Collections.sort(out);
        return out;
    }

    @Test
    void a_new_entity_carries_its_name_and_an_alias_brings_a_later_mention_home() {
        Fixture f = seed();
        try {
            var probe = mention(f, "海洋探测器1号");
            assertTrue(probe.created());
            var n = names.forEntity(f.kb(), probe.entityId(), null);
            assertEquals(List.of("海洋探测器1号"), values(n), "本名是一条名字事实");
            assertTrue(n.get(0).canonical());

            // 没有桥的简称：另起一个实体（这正是 0041 要修的，这一刀靠记下名字修）
            var before = mention(f, "海探2");
            assertTrue(before.created() && !before.entityId().equals(probe.entityId()));

            assertNotNull(names.record(f.kb(), probe.entityId(), "海探1", null, null));
            var later = mention(f, "海探1");
            assertFalse(later.created(), "记下的别名让后来的提及找得到它");
            assertEquals(probe.entityId(), later.entityId());
            assertEquals(probe.entityId(), resolution.existingByName(f.kb(), "海探1"));

            // 同一个名字再记一次只有一行
            assertNotNull(names.record(f.kb(), probe.entityId(), " 海探1 ", null, null));
            var again = names.forEntity(f.kb(), probe.entityId(), null);
            assertEquals(List.of("海探1", "海洋探测器1号"), values(again));
            assertNull(names.record(f.kb(), probe.entityId(), "  ", null, null));
        } finally {
            teardown(f);
        }
    }

    @Test
    void a_name_is_neither_a_listed_fact_nor_a_degree() {
        Fixture f = seed();
        try {
            var probe = mention(f, "海洋探测器1号");
            assertNotNull(names.record(f.kb(), probe.entityId(), "海探1", null, null));
            // 任务 6：entity_detail 真读（GraphService）——名字不进事实列表、不算度数
            var detail = graph.entityDetail(f.kb(), probe.entityId(), null, null);
            assertTrue(detail.facts().isEmpty(), "名字不进事实列表");
            assertEquals(0, detail.node().degree(), "名字不算度数");
        } finally {
            teardown(f);
        }
    }

    @Test
    void merging_moves_names_and_reverting_brings_them_back() {
        Fixture f = seed();
        try {
            UUID full = mention(f, "海洋探测器1号").entityId();
            UUID shortId = mention(f, "海探1").entityId();
            assertNotEquals(full, shortId);

            var merge = resolution.mergeEntities(f.kb(), shortId, full, null, "test");
            assertEquals(List.of("海探1", "海洋探测器1号"),
                    values(names.forEntity(f.kb(), full, null)),
                    "被并方的名字随它的事实搬过来");
            assertEquals(full, resolution.existingByName(f.kb(), "海探1"),
                    "合并之后按简称找到的是存活者");

            resolution.revertMerge(f.kb(), merge);
            assertEquals(List.of("海探1"), values(names.forEntity(f.kb(), shortId, null)));
            assertEquals(List.of("海洋探测器1号"), values(names.forEntity(f.kb(), full, null)),
                    "撤回合并，名字搬回去");
        } finally {
            teardown(f);
        }
    }

    @Test
    void a_name_another_entity_already_has_queues_the_pair_without_merging() {
        Fixture f = seed();
        try {
            // 倒序到达：只写简称的那篇先建出「海探1」，写着全称的那篇后到
            UUID shortId = mention(f, "海探1").entityId();
            UUID full = mention(f, "海洋探测器1号").entityId();
            assertNotNull(names.record(f.kb(), full, "海探1", null, null));
            assertEquals(1, names.pairSharedName(f.kb(), full, "海探1"));
            var row = jdbc.queryForMap("""
                    SELECT reason, stage, status FROM resolution_reviews
                      WHERE kb_id = ? AND least(left_id, right_id) = least(?, ?)
                        AND greatest(left_id, right_id) = greatest(?, ?)
                    """, f.kb(), shortId, full, shortId, full);
            assertEquals("shared_name|海探1", row.get("reason"));
            assertEquals("adjudicating", row.get("stage"));
            assertEquals("pending", row.get("status"));
            UUID merged = jdbc.queryForObject("SELECT merged_into FROM entities WHERE id = ?",
                    UUID.class, shortId);
            assertNull(merged, "只排队，不合并");
            // 再报一次同一个名字不重复排
            assertEquals(1, names.pairSharedName(f.kb(), full, "海探1"));
            Long n = jdbc.queryForObject("SELECT count(*) FROM resolution_reviews WHERE kb_id = ?",
                    Long.class, f.kb());
            assertEquals(1L, n);
        } finally {
            teardown(f);
        }
    }

    @Test
    void the_adjudicator_sees_the_other_names_and_never_the_shared_one() {
        Fixture f = seed();
        try {
            UUID full = mention(f, "海洋探测器1号").entityId();
            assertNotNull(names.record(f.kb(), full, "海探1", null, null));
            List<String> lines = review.entityFactLines(f.kb(), List.of(full), 4)
                    .getOrDefault(full, List.of());
            assertEquals(List.of("also known as: 海探1"), lines);
            UUID bare = mention(f, "海探2").entityId();
            assertTrue(review.entityFactLines(f.kb(), List.of(bare), 4)
                    .getOrDefault(bare, List.of()).isEmpty(),
                    "只有本名的实体没有「又名」这一行");
        } finally {
            teardown(f);
        }
    }

    /** 分开过的一对，同一个简称再被读到也不再排队：分开这个决定要记得住。 */
    @Test
    void a_pair_kept_apart_is_not_queued_again() {
        Fixture f = seed();
        try {
            mention(f, "海探1");
            UUID full = mention(f, "海洋探测器1号").entityId();
            assertNotNull(names.record(f.kb(), full, "海探1", null, null));
            assertEquals(1, names.pairSharedName(f.kb(), full, "海探1"));
            jdbc.update("UPDATE resolution_reviews SET status = 'kept', decided_at = now() WHERE kb_id = ?",
                    f.kb());
            assertEquals(0, names.pairSharedName(f.kb(), full, "海探1"), "判过不是一个的，不再配");
            Long pending = jdbc.queryForObject(
                    "SELECT count(*) FROM resolution_reviews WHERE kb_id = ? AND status = 'pending'",
                    Long.class, f.kb());
            assertEquals(0L, pending);
        } finally {
            teardown(f);
        }
    }

    /**
     * 名字属性不是一条普通属性：按 key 找不到，改不了，也不进本体向量索引。
     * 否则本体提议能把「简称」一类的值归并到它上面、唯一性面板能把它标成 functional，
     * 名字的核对与配对就都绕过去了。
     */
    @Test
    void the_name_attribute_is_not_an_ordinary_attribute() {
        Fixture f = seed();
        try {
            UUID knownAs = names.ensureKnownAs(f.kb());
            assertThrows(tech.seexun.jknow.common.AppException.class, () -> ontology
                    .updateRelationType(f.kb(), knownAs, "known as", "state",
                            new OntologyService.RelationAxioms(true, false, false, false, false,
                                    false, null, null),
                            "", null, null, null, null),
                    "内建的名字属性不能改成 functional");
            Boolean functional = jdbc.queryForObject(
                    "SELECT functional FROM relation_types WHERE id = ?", Boolean.class, knownAs);
            assertFalse(functional);
            assertTrue(DetailStubs.relationsNeedingEmbedding(jdbc, f.kb(), "test-model")
                    .stream().allMatch(t -> !t.equals(knownAs)), "名字属性不嵌");
        } finally {
            teardown(f);
        }
    }

    /**
     * 一段只写了名字的引文不是原文：给治理 agent 的片段里没有它，带整句的别名照样有。
     * 删掉文档，实体的本名还在，只凭这篇文档读到的别名跟着作废。
     */
    @Test
    void a_bare_name_is_not_a_quote_and_a_deleted_document_keeps_the_name() {
        Fixture f = seed();
        try {
            UUID full = mention(f, "海洋探测器1号").entityId();
            UUID doc = Uuids.nowV7();
            UUID chunk = Uuids.nowV7();
            jdbc.update(
                    "INSERT INTO documents (id, kb_id, filename, sha256) VALUES (?, ?, 'probe.txt', ?)",
                    doc, f.kb(), doc.toString());
            jdbc.update("""
                    INSERT INTO chunks (id, kb_id, document_id, seq, text)
                     VALUES (?, ?, ?, 0, '海洋探测器1号（简称海探1）于2026年下水。')
                    """, chunk, f.kb(), doc);
            UUID canonical = names.record(f.kb(), full, "海洋探测器1号",
                    new NamesService.NameSource(chunk, "海洋探测器1号"), null);
            UUID alias = names.record(f.kb(), full, "海探1",
                    new NamesService.NameSource(chunk, "简称海探1"), null);
            assertNotNull(canonical, "canonical name fact");
            assertNotNull(alias, "alias fact");
            UUID launched = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, kind, datatype, temporal)
                     VALUES (?, ?, 'launch_year', 'launch year', 'attribute', 'text', 'event')
                    """, launched, f.kb());
            // graph.rs:insert_value_fact + add_evidence 的直取路径（值为 jsonb，证据带说法）
            UUID fact = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_value, confidence)
                     VALUES (?, ?, ?, ?, jsonb_build_object('value', '2026'), 0.9)
                    """, fact, f.kb(), full, launched);
            jdbc.update(
                    "INSERT INTO fact_evidence (fact_id, chunk_id, quote, proposed_predicate) VALUES (?, ?, ?, ?)",
                    fact, chunk, "于2026年下水", "launch_year");

            // M4 任务 1 起 quotes_of 走治理 store 真身（原 DetailStubs 桩退役）
            List<String> quotes = governance.quotesOf(f.kb(), full, 10).stream()
                    .map(q -> q.quote()).toList();
            for (String q : quotes) {
                assertFalse(q.equals("海洋探测器1号"), () -> quotes.toString());
            }
            assertEquals(1, quotes.size(), "一块一段");
            assertEquals("于2026年下水", quotes.get(0), "同一块里真正的句子优先");

            documents.delete(f.kb(), doc, null);
            assertFalse(invalidated(canonical), "本名不随文档走");
            assertTrue(invalidated(alias), "只凭这篇读到的别名作废");
            assertTrue(invalidated(fact));
        } finally {
            teardown(f);
        }
    }

    private boolean invalidated(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT invalidated_at IS NOT NULL FROM facts WHERE id = ?", Boolean.class, id));
    }
}
