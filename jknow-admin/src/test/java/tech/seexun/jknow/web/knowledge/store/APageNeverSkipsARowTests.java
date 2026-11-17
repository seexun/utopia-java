package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.mapper.ReviewMapper;

/**
 * 分页翻完，每一行恰好出现一次（a_page_never_skips_a_row.rs 原值移植，#646——M5 任务 6
 * 台账收口）。
 *
 * <p>排序键若不唯一，并列的行每次查询的先后可以不同——翻到第二页时第一页见过的又出来
 * 一次，另一行一页也没出现。时间戳作排序键时逐条写入的行很少并列；<b>一个事务里写下的
 * 行全都并列</b>（now() 在事务里是同一个值），而 Review 的队列正是成批写的。三份成批写
 * 下的数据各翻一遍：公理违规（一次 reasoning::run 插下 300 个环）/本体缺陷（一个语句
 * 250 行）/审核台账（一个语句 250 条）。翻出来的就是全部行，一行一次，并列时按 id 倒序
 * ——排序是全序。
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class APageNeverSkipsARowTests extends StoreTestBase {

    private static final long PAGE = 37;

    @Autowired
    ReviewMapper review;
    @Autowired
    ReasoningService reasoning;

    private UUID org;
    private UUID ws;
    private UUID kb;

    @BeforeAll
    void seed() {
        org = Uuids.nowV7();
        ws = Uuids.nowV7();
        kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'page-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'page-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name)"
                + " VALUES (?, ?, 'page-test')", kb, ws);
    }

    @AfterAll
    void cleanup() {
        // 审计事件只许追加（删不掉），留在测试库里；它们挂在这个自建的库名下，不串到别处
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    private List<UUID> pageThrough(java.util.function.LongFunction<List<UUID>> page) {
        List<UUID> seen = new ArrayList<>();
        long offset = 0;
        while (true) {
            List<UUID> ids = page.apply(offset);
            seen.addAll(ids);
            if (ids.size() < PAGE) {
                return seen;
            }
            offset += PAGE;
        }
    }

    private void assertTotalOrder(String what, List<UUID> seen, String allIds, int expected) {
        List<UUID> want = jdbc.queryForList(allIds, UUID.class);
        assertEquals(expected, want.size(), what + ": 写下的行数");
        // PG 的 uuid 是无符号字节序，而 java.util.UUID.compareTo 按有符号长整型比——
        // 高位字节的最高位为 1 时两者分歧（v4 随机 id 常见）。按十六进制串排=无符号同序
        want.sort(Comparator.comparing(UUID::toString).reversed());
        List<UUID> distinct = new ArrayList<>(seen);
        distinct.sort(null);
        assertEquals(distinct.stream().distinct().count(), seen.size(),
                what + ": 有行翻到了不止一次");
        assertEquals(expected, seen.size(), what + ": 翻完只见到 " + seen.size() + " 行");
        assertTrue(seen.equals(want), what + ": 并列的行没有按 id 定下先后，翻页之间会漂");
    }

    @Test
    void pagingThroughABatchSeesEveryRowExactlyOnce() {
        // ---- 一、公理违规：一个中心节点与 300 个节点两两互指，恒常谓词上就是 300 个环
        final int cycles = 300;
        UUID etype = Uuids.nowV7();
        UUID partOf = Uuids.nowV7();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label)"
                + " VALUES (?, ?, 't', 'T')", etype, kb);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label, temporal, is_transitive)"
                + " VALUES (?, ?, 'part_of', 'part of', 'eternal', TRUE)", partOf, kb);
        jdbc.update("""
                WITH n AS (
                     INSERT INTO entities (id, kb_id, type_id, canonical_name)
                     SELECT gen_random_uuid(), ?, ?, 'N' || i FROM generate_series(0, ?) AS i
                     RETURNING id, canonical_name
                 ), hub AS (SELECT id FROM n WHERE canonical_name = 'N0'),
                    spokes AS (SELECT id FROM n WHERE canonical_name <> 'N0')
                 INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id)
                 SELECT gen_random_uuid(), ?, hub.id, ?, spokes.id FROM hub, spokes
                 UNION ALL
                 SELECT gen_random_uuid(), ?, spokes.id, ?, hub.id FROM hub, spokes
                """, kb, etype, cycles, kb, partOf, kb, partOf);
        ReasoningService.Report r = reasoning.run(kb);
        assertEquals(cycles, r.inserted(), "一次检查插下这些环");
        List<UUID> violations = pageThrough(offset ->
                review.selectOpenViolations(kb, PAGE, offset)
                        .stream().map(ReviewMapper.ViolationRow::id).toList());
        assertTotalOrder("公理违规", violations,
                "SELECT id FROM axiom_violations WHERE kb_id = '" + kb + "'", cycles);

        // ---- 二、本体缺陷：一个语句插下 250 行，detected_at 全相同
        final int defects = 250;
        jdbc.update("""
                INSERT INTO ontology_defects (id, kb_id, kind, subject, other)
                 SELECT gen_random_uuid(), ?, 'rules_disagree', gen_random_uuid(), gen_random_uuid()
                   FROM generate_series(1, ?)
                """, kb, defects);
        List<UUID> defectIds = pageThrough(offset ->
                review.selectOpenDefects(kb, PAGE, offset)
                        .stream().map(tech.seexun.jknow.store.entity.OntologyDefect::id)
                        .toList());
        assertTotalOrder("本体缺陷", defectIds,
                "SELECT id FROM ontology_defects WHERE kb_id = '" + kb + "'", defects);

        // ---- 三、审核台账：一个语句写下 250 条，created_at 全相同
        final int events = 250;
        jdbc.update("""
                INSERT INTO audit_events (id, kb_id, action, target_kind)
                 SELECT gen_random_uuid(), ?, 'review.decided', 'violation'
                   FROM generate_series(1, ?)
                """, kb, events);
        List<UUID> history = pageThrough(offset ->
                review.selectReviewHistory(kb, PAGE, offset)
                        .stream().map(tech.seexun.jknow.store.entity.AuditEventView::id)
                        .toList());
        assertTotalOrder("审核台账", history,
                "SELECT id FROM audit_events WHERE kb_id = '" + kb + "'", events);
    }
}
