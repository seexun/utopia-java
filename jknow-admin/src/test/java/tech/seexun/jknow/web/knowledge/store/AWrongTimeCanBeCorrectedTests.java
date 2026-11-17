package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_wrong_time_can_be_corrected.rs（413 行 4 例）——人工修正一条
 * 事实的有效区间（302）：旧行作废不删、修正行链回、证据随行复制、改完起点重新对账、
 * 作废行改不动。等价点：无（直译；correct_interval/reconcile_moved_facts 即本任务真身）。
 */
class AWrongTimeCanBeCorrectedTests extends HeartStoreTestBase {

    @Autowired
    GraphWriteService writes;
    @Autowired
    TemporalService temporal;

    record Fixture(UUID kb, UUID zhang, UUID li, UUID project, UUID leads, UUID chunk, UUID doc) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID etype = Uuids.nowV7();
        UUID leads = Uuids.nowV7();
        UUID zhang = Uuids.nowV7();
        UUID li = Uuids.nowV7();
        UUID project = Uuids.nowV7();
        UUID src = Uuids.nowV7();
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'time-edit-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'time-edit-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'time-edit-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'thing', 'Thing')",
                etype, kb);
        // inverse_functional：一个项目同时只有一个人 leads 它——宾语侧的唯一性，
        // 起点挪动要撞的正是这条不变量
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, temporal, inverse_functional)
                 VALUES (?, ?, 'leads', 'leads', 'state', TRUE)
                """, leads, kb);
        for (Object[] e : new Object[][]{{zhang, "Zhang San"}, {li, "Li Si"},
                {project, "Phoenix"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    e[0], kb, etype, e[1]);
        }
        jdbc.update("INSERT INTO sources (id, kb_id, name) VALUES (?, ?, 'time-edit-test')", src, kb);
        jdbc.update("""
                INSERT INTO documents (id, kb_id, source_id, filename, sha256, status)
                 VALUES (?, ?, ?, 'memo.md', 'timeedit', 'ready')
                """, doc, kb, src);
        jdbc.update("""
                INSERT INTO chunks (id, kb_id, document_id, seq, text)
                 VALUES (?, ?, ?, 0, 'Zhang San led Phoenix in the first half of 2023.')
                """, chunk, kb, doc);
        return new Fixture(kb, zhang, li, project, leads, chunk, doc);
    }

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    /** 一条带证据的开放事实。 */
    private UUID assertFact(Fixture f, UUID subject, String from, String precision) {
        UUID id = writes.insertFact(f.kb(), subject, f.leads(), f.project(),
                Validity.starting(t(from), precision), 0.9f).id();
        jdbc.update("""
                INSERT INTO fact_evidence (fact_id, chunk_id, quote, document_id, doc_version)
                 VALUES (?, ?, 'led Phoenix', ?, 1)
                """, id, f.chunk(), f.doc());
        return id;
    }

    record Row(OffsetDateTime validFrom, String validFromPrecision, OffsetDateTime validTo,
               String validToPrecision, OffsetDateTime invalidatedAt, UUID supersedes) {
    }

    private Row row(UUID id) {
        return jdbc.queryForObject("""
                SELECT valid_from, valid_from_precision, valid_to, valid_to_precision,
                        invalidated_at, supersedes
                 FROM facts WHERE id = ?
                """, (rs, i) -> new Row(rs.getObject(1, OffsetDateTime.class), rs.getString(2),
                rs.getObject(3, OffsetDateTime.class), rs.getString(4),
                rs.getObject(5, OffsetDateTime.class), rs.getObject(6, UUID.class)), id);
    }

    private long evidenceCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM fact_evidence WHERE fact_id = ?",
                Long.class, id);
    }

    /** 改一个日期，账本要留下改过的痕迹：旧行作废但不删，修正行链回它，证据跟着走。 */
    @Test
    void correcting_a_date_leaves_the_old_reading_in_the_ledger() {
        Fixture f = seed();
        try {
            // 抽取读成了 1 月 1 日，精确到日——原文其实只说了「上半年」
            UUID wrong = assertFact(f, f.zhang(), "2023-01-01T00:00:00Z", "day");

            UUID corrected = temporal.correctInterval(wrong,
                    Validity.starting(t("2023-06-01T00:00:00Z"), "month"));
            assertNotNull(corrected, "这条还活着，应当改得动");

            Row old = row(wrong);
            assertNotNull(old.invalidatedAt(), "旧行要记下何时被推翻——原地 UPDATE 会让这次修改自己消失");
            assertEquals(t("2023-01-01T00:00:00Z").toInstant(), old.validFrom().toInstant(),
                    "旧行的世界区间一个字都不该动：它记录的是我们当时读成了什么");

            Row fresh = row(corrected);
            assertEquals(wrong, fresh.supersedes(), "修正行要链回被它取代的那条");
            assertEquals(null, fresh.invalidatedAt(), "修正行是现行的");
            assertEquals(t("2023-06-01T00:00:00Z").toInstant(), fresh.validFrom().toInstant());
            assertEquals("month", fresh.validFromPrecision(),
                    "精度跟着值一起改——写到月就是月，不该继承旧行那个「日」");

            assertEquals(1L, evidenceCount(corrected),
                    "证据要随修正行复制。少了它，这条事实立刻变成「无人陈述」被扫成灰的");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        }
    }

    /** 结束端的三态都要改得进去，尤其是把闭区间重新打开。 */
    @Test
    void an_end_that_was_never_there_can_be_taken_back() {
        Fixture f = seed();
        try {
            UUID fact = assertFact(f, f.zhang(), "2023-01-01T00:00:00Z", "day");
            // 先闭合到 2024
            UUID closed = temporal.correctInterval(fact, new Validity(
                    t("2023-01-01T00:00:00Z"), "day", t("2024-01-01T00:00:00Z"), "year",
                    null, null));
            assertNotNull(closed, "改得动");
            assertEquals(t("2024-01-01T00:00:00Z").toInstant(),
                    row(closed).validTo().toInstant());

            // 判错了：它其实没结束。把结束端整个收回
            UUID reopened = temporal.correctInterval(closed,
                    Validity.starting(t("2023-01-01T00:00:00Z"), "day"));
            assertNotNull(reopened, "改得动");
            Row r = row(reopened);
            assertEquals(null, r.validTo(), "结束端收回了");
            assertEquals(null, r.validToPrecision(),
                    "精度也要一起收回：留着 'year' 而日期为空，CHECK 会拦，而三值逻辑下这种组合曾经静默放行");
            assertEquals(closed, r.supersedes(), "两次修正串成链，不是各挂各的");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        }
    }

    /** 改完起点要重新对账——这一刀最容易漏掉的一半：区间改对了，与继任者的关系没人再算。 */
    @Test
    void moving_a_start_earlier_makes_it_the_predecessor() {
        Fixture f = seed();
        try {
            // 两条都开放：李四 2024-07 接任，张三被抽取读成 2025-01（错的，他其实 2023 年就在任）
            UUID liFact = assertFact(f, f.li(), "2024-07-01T00:00:00Z", "month");
            UUID zhangFact = assertFact(f, f.zhang(), "2025-01-01T00:00:00Z", "month");

            // 把张三改回真实的 2023
            UUID fixed = temporal.correctInterval(zhangFact,
                    Validity.starting(t("2023-01-01T00:00:00Z"), "month"));
            assertNotNull(fixed, "改得动");
            var report = temporal.reconcileMovedFacts(f.kb(), java.util.List.of(fixed));

            assertTrue(!report.corrected.isEmpty(),
                    "对账要动手：张三 2023 起、李四 2024-07 接任，两条不能都挂着开放区间");
            // 新事实开始得更早 = 它是前任，闭合在旧事实的开始（引擎的判据）
            assertNotNull(row(fixed).invalidatedAt(),
                    "被闭合的是张三自己那条，不是李四——早的那个是前任");
            Row current = jdbc.queryForObject("""
                    SELECT valid_from, valid_from_precision, valid_to, valid_to_precision,
                            invalidated_at, supersedes
                     FROM facts WHERE supersedes = ?
                    """, (rs, i) -> new Row(rs.getObject(1, OffsetDateTime.class), rs.getString(2),
                    rs.getObject(3, OffsetDateTime.class), rs.getString(4),
                    rs.getObject(5, OffsetDateTime.class), rs.getObject(6, UUID.class)), fixed);
            assertEquals(t("2024-07-01T00:00:00Z").toInstant(), current.validTo().toInstant(),
                    "张三的区间闭合在李四的上任日");
            assertEquals(t("2023-01-01T00:00:00Z").toInstant(), current.validFrom().toInstant(),
                    "起点保持修正后的值");
            assertEquals(null, row(liFact).invalidatedAt(),
                    "李四那条一动不动：他是现任，本来就该开放");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        }
    }

    /** 已被作废的行改不动：两个人同时改不会串出两条各自挂在死行后面的修正。 */
    @Test
    void a_row_that_is_already_gone_cannot_be_corrected() {
        Fixture f = seed();
        try {
            UUID fact = assertFact(f, f.zhang(), "2023-01-01T00:00:00Z", "day");
            UUID first = temporal.correctInterval(fact,
                    Validity.starting(t("2023-06-01T00:00:00Z"), "month"));
            assertNotNull(first);

            // 第二次拿着同一个（已作废的）id 再改
            UUID second = temporal.correctInterval(fact,
                    Validity.starting(t("2022-01-01T00:00:00Z"), "year"));
            assertEquals(null, second,
                    "作废行改不动，要让调用方知道没动手——而不是插一条挂在死行后面的修正");
            Long n = jdbc.queryForObject("SELECT count(*) FROM facts WHERE supersedes = ?",
                    Long.class, fact);
            assertEquals(1L, n, "一条死行只该有一个后继");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        }
    }
}
