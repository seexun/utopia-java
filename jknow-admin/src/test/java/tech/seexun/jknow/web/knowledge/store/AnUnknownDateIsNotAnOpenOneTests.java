package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/an_unknown_date_is_not_an_open_one.rs（801 行 5 例）——未知的日期
 * 不是开放的日期（0022/#345/#352）：世界轴谓词整个活在 SQL 字符串里，cargo check 看不见
 * 一个写反的 CASE，这里连库断言两个方向。等价点：edges_at 经 GraphService.neighborhood
 * （M2 已搬的读路径，与 Rust graph::neighborhood 同 SQL）。
 * 依赖 entity_detail（实体面板读）的 2 例与 reasoning::materialize 的 1 例分别挂
 * M3 任务 6（entity-family 读端点）与任务 6（derive）后启用。
 */
class AnUnknownDateIsNotAnOpenOneTests extends HeartStoreTestBase {

    @Autowired
    GraphWriteService writes;
    @Autowired
    TemporalService temporal;
    @Autowired
    tech.seexun.jknow.store.GraphService graph;
    @Autowired
    tech.seexun.jknow.store.ReasoningService reasoning;

    record Fixture(UUID org, UUID kb, UUID lin, UUID meridian, UUID platform, UUID staffEngineer,
                   UUID lead, UUID worksFor, UUID approved, UUID holdsTitle) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID organization = Uuids.nowV7();
        UUID title = Uuids.nowV7();
        UUID lin = Uuids.nowV7();
        UUID meridian = Uuids.nowV7();
        UUID platform = Uuids.nowV7();
        UUID staffEngineer = Uuids.nowV7();
        UUID lead = Uuids.nowV7();
        UUID worksFor = Uuids.nowV7();
        UUID approved = Uuids.nowV7();
        UUID holdsTitle = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'unknown-date-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'unknown-date-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'unknown-date-test')",
                kb, ws);
        for (Object[] e : new Object[][]{{person, "person", "Person"},
                {organization, "organization", "Organization"}, {title, "title", "Title"}}) {
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    e[0], kb, e[1], e[2]);
        }
        for (Object[] e : new Object[][]{{worksFor, "works_for", "works for"},
                {approved, "approved", "approved"}, {holdsTitle, "holds_title", "holds title"}}) {
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    e[0], kb, e[1], e[2]);
        }
        for (Object[] e : new Object[][]{{lin, person, "Lin Zhao"},
                {meridian, organization, "Meridian Systems"},
                {platform, organization, "Platform Group"},
                {staffEngineer, title, "Staff Engineer"}, {lead, title, "Tech Lead"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    e[0], kb, e[1], e[2]);
        }
        return new Fixture(org, kb, lin, meridian, platform, staffEngineer, lead, worksFor,
                approved, holdsTitle);
    }

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    private UUID fact(Fixture f, UUID subject, UUID predicate, UUID object, Validity validity) {
        return writes.insertFact(f.kb(), subject, predicate, object, validity, 0.9f).id();
    }

    /** T 时刻林昭一跳邻域里的边（按事实 id）。null = 每一刻。 */
    private Set<UUID> edgesAt(Fixture f, String at) {
        var hood = graph.neighborhood(f.kb(), f.lin(), 1, at == null ? null : t(at), null);
        Set<UUID> out = new HashSet<>();
        hood.edges().forEach(e -> out.add(e.id()));
        return out;
    }

    private OffsetDateTime attestedAt(UUID id) {
        return jdbc.queryForObject("SELECT attested_from FROM facts WHERE id = ?",
                OffsetDateTime.class, id);
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    /**
     * entity_detail（graph.rs:1721-1830）as_of=None 形的核心读（面板实体的活事实名单），
     * <b>桩</b>：只投影测试用到的 id——记录轴现行星 + 世界轴 at 过滤 + 不画已代表的陈述 +
     * 名字事实不算边，与 M2 GraphMapper 头注的同一套展开；contested/closer/stale 列随
     * 任务 6 真端点落地后由真身替换。
     */
    private java.util.Set<UUID> entityDetailIds(UUID kb, UUID entity, String at) {
        // 任务 6 去封：换真 GraphService.entityDetail（同一套记录轴/世界轴谓词与
        // represented/名字过滤），只取测试断言面的 id 集
        var detail = graph.entityDetail(kb, entity, at == null ? null : t(at), null);
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (var fact : detail.facts()) {
            ids.add(fact.id());
        }
        return ids;
    }

    /** 未知的界伸到证据那么远（表里四种行两个方向）。 */
    @Test
    void an_unknown_bound_reaches_as_far_as_the_evidence() {
        Fixture f = seed();
        try {
            // A：offer letter，2023-06-01 起，仍在继续——两端原文都给了（起点）
            UUID a = fact(f, f.lin(), f.worksFor(), f.meridian(),
                    Validity.starting(t("2023-06-01T00:00:00Z"), "day")
                            .attested(t("2023-06-01T00:00:00Z")));
            // B：2024-02-20 的调薪单说「加薪由平台组批准」——没有起点
            UUID b = fact(f, f.platform(), f.approved(), f.lin(),
                    Validity.empty().attested(t("2024-02-20T00:00:00Z")));
            // C：2025-10-15 的角色说明说「不再担任 Staff Engineer，日期未记录」——两端都不知道
            UUID c = fact(f, f.lin(), f.holdsTitle(), f.staffEngineer(),
                    Validity.empty().endedWhenUnknown().attested(t("2025-10-15T00:00:00Z")));
            // D：2023-06-01 起任 Tech Lead，同一份 2025-10-15 的说明说已不再担任——起点有，终点不知哪天
            UUID d = fact(f, f.lin(), f.holdsTitle(), f.lead(),
                    Validity.starting(t("2023-06-01T00:00:00Z"), "day").endedWhenUnknown()
                            .attested(t("2025-10-15T00:00:00Z")));

            // 2023 年 1 月：什么都还没有。B 的证据在 2024 年——「自古如此」读法会让它出现
            assertTrue(edgesAt(f, "2023-01-01T00:00:00Z").isEmpty(),
                    "证据出现之前的时刻，一条也不该成立");

            // 2023-06-01：有起点的两条当天起成立；B 还没有证据；C 从不成立
            assertEquals(java.util.Set.of(a, d), edgesAt(f, "2023-06-01T00:00:00Z"));

            // 2024-02-20：B 从它的文档日期起成立
            assertEquals(java.util.Set.of(a, b, d), edgesAt(f, "2024-02-20T00:00:00Z"));
            assertFalse(edgesAt(f, "2024-02-19T00:00:00Z").contains(b),
                    "文档日期前一天，没起点的事实还不成立");

            // 2025-10-15：D 到说出它结束的那份文档为止；前一天还在
            assertEquals(java.util.Set.of(a, b), edgesAt(f, "2025-10-15T00:00:00Z"),
                    "结束了不知哪天的，到它的文档日期为止");
            assertTrue(edgesAt(f, "2025-10-14T00:00:00Z").contains(d),
                    "文档日期前一天它还成立——保守包含到证据为止");

            // 2026-01-01：#345 那道题——头衔不该还挂着
            java.util.Set<UUID> jan2026 = edgesAt(f, "2026-01-01T00:00:00Z");
            assertFalse(jan2026.contains(c) && jan2026.contains(d), "原文说结束了，就不该在之后成立");
            assertEquals(java.util.Set.of(a, b), jan2026);

            // 两端都不知道的 C：任何时刻都不成立；但不给时刻时它在（画布画的是历史）
            assertEquals(java.util.Set.of(a, b, c, d), edgesAt(f, null),
                    "at = None 是每一刻，四条都在");

            // 边上带着读出来的区间，前端按它过滤，不自己解释 NULL
            var edges = graph.neighborhood(f.kb(), f.lin(), 1, null, null).edges();
            java.util.Map<UUID, tech.seexun.jknow.store.entity.GraphEdge> byId = new java.util.HashMap<>();
            for (var e : edges) {
                byId.put(e.id(), e);
            }
            assertEquals(t("2024-02-20T00:00:00Z"), byId.get(b).holdsFrom());
            assertEquals(null, byId.get(b).holdsTo());
            assertEquals(t("2023-06-01T00:00:00Z"), byId.get(d).holdsFrom());
            assertEquals(t("2025-10-15T00:00:00Z"), byId.get(d).holdsTo(), "结束端读到文档日期");
            assertEquals(null, byId.get(a).holdsTo(), "仍在继续的还是开放的");
            assertEquals(t("2025-10-15T00:00:00Z"), byId.get(c).holdsFrom());
            assertEquals(t("2025-10-15T00:00:00Z"), byId.get(c).holdsTo(), "两端都不知道：区间为空");

            // 实体面板：不给时刻时整条时间线都在（C 也列着，标着结束）；给时刻时按规则筛
            assertEquals(4, entityDetailIds(f.kb(), f.lin(), null).size());
            assertEquals(java.util.Set.of(a, b), entityDetailIds(f.kb(), f.lin(), "2026-01-01T00:00:00Z"));
        } finally {
            cleanup(f);
        }
    }

    /** 锚点只往早挪：更早的文档是更早的证据。修正行继承锚点：改区间是重述同一份证据。 */
    @Test
    void the_anchor_moves_earlier_and_is_inherited() {
        Fixture f = seed();
        try {
            UUID b = fact(f, f.platform(), f.approved(), f.lin(),
                    Validity.empty().attested(t("2024-02-20T00:00:00Z")));
            assertFalse(edgesAt(f, "2023-12-01T00:00:00Z").contains(b));

            // 同一断言，出自一份更早的文档（回填语料乱序进来是常态）：同一行，锚点前移
            var again = writes.insertFact(f.kb(), f.platform(), f.approved(), f.lin(),
                    Validity.empty().attested(t("2023-12-01T00:00:00Z")), 0.9f);
            assertEquals(b, again.id(), "同一断言复用同一行");
            assertTrue(!again.created());
            assertEquals(t("2023-12-01T00:00:00Z"), attestedAt(b));
            assertTrue(edgesAt(f, "2023-12-01T00:00:00Z").contains(b));

            // 更晚的文档再提一次：什么也不动
            writes.insertFact(f.kb(), f.platform(), f.approved(), f.lin(),
                    Validity.empty().attested(t("2025-01-01T00:00:00Z")), 0.9f);
            assertEquals(t("2023-12-01T00:00:00Z"), attestedAt(b), "只往早挪");

            // 人给它一个结束：修正行继承锚点，而不是锚在修正的此刻
            UUID corrected = temporal.correctInterval(b, new Validity(null, null,
                    t("2025-06-01T00:00:00Z"), "day", null, null));
            assertNotEquals(null, corrected, "改得动");
            assertEquals(t("2023-12-01T00:00:00Z"), attestedAt(corrected));
            var edges = graph.neighborhood(f.kb(), f.lin(), 1, null, null).edges();
            var row = edges.stream().filter(e -> e.id().equals(corrected)).findFirst().orElseThrow();
            assertEquals(t("2023-12-01T00:00:00Z"), row.holdsFrom());
            assertEquals(t("2025-06-01T00:00:00Z"), row.holdsTo(), "原文给了终点就用终点");
        } finally {
            cleanup(f);
        }
    }

    /** 「不再担任，日期未记录」撞上「2023-06-01 起担任」的开放行：关上它，而不是另立一行。 */
    @Test
    void an_ending_without_a_date_closes_the_dated_row_it_ends() {
        Fixture f = seed();
        try {
            // 2023-06-01 的 offer：Staff Engineer，仍在继续
            UUID open = fact(f, f.lin(), f.holdsTitle(), f.staffEngineer(),
                    Validity.starting(t("2023-06-01T00:00:00Z"), "day")
                            .attested(t("2023-06-01T00:00:00Z")));
            assertTrue(edgesAt(f, "2026-01-01T00:00:00Z").contains(open));

            // 2025-10-15 的角色说明：不再担任，日期未记录——同一断言，没起点
            var closed = writes.insertFact(f.kb(), f.lin(), f.holdsTitle(), f.staffEngineer(),
                    Validity.empty().endedWhenUnknown().attested(t("2025-10-15T00:00:00Z")), 0.9f);
            assertTrue(closed.created(), "关上是一条修正行，不是并进去");
            assertNotEquals(open, closed.id());

            // 旧行作废、修正行链上：起点是原文给的，终点空、精度 unknown，锚在结束的那份文档
            var invalidatedAndSupersedes = jdbc.queryForMap(
                    "SELECT invalidated_at, supersedes FROM facts WHERE id = ?", open);
            assertTrue(invalidatedAndSupersedes.get("invalidated_at") != null, "旧行作废，不删");
            assertEquals(null, invalidatedAndSupersedes.get("supersedes"));
            record ClosedShape(OffsetDateTime from, OffsetDateTime to, String toPrec,
                               UUID supersedes, OffsetDateTime attestedTo) {
            }
            ClosedShape row = jdbc.queryForObject("""
                    SELECT valid_from, valid_to, valid_to_precision, supersedes, attested_to
                      FROM facts WHERE id = ?
                    """, (rs, i) -> new ClosedShape(rs.getObject(1, OffsetDateTime.class),
                    rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getObject(4, UUID.class),
                    rs.getObject(5, OffsetDateTime.class)), closed.id());
            assertEquals(t("2023-06-01T00:00:00Z").toInstant(), row.from().toInstant(), "起点照旧");
            assertEquals(null, row.to());
            assertEquals("unknown", row.toPrec());
            assertEquals(open, row.supersedes(), "supersedes 链上");
            assertEquals(t("2025-10-15T00:00:00Z").toInstant(), row.attestedTo().toInstant(),
                    "结束端锚在说出结束的那份文档");

            // #345 那道题：2026 年 1 月不该再有 Staff Engineer；2024 年 6 月还有
            assertFalse(edgesAt(f, "2026-01-01T00:00:00Z").contains(closed.id()));
            assertTrue(edgesAt(f, "2024-06-01T00:00:00Z").contains(closed.id()));
            // 面板在 2026 年也不再列它
            assertTrue(entityDetailIds(f.kb(), f.lin(), "2026-01-01T00:00:00Z").isEmpty(),
                    "面板在 2026 年也不再列它");

            // 同一断言再说一次「结束了」：已经关上的行不再被关，也不另立
            var again = writes.insertFact(f.kb(), f.lin(), f.holdsTitle(), f.staffEngineer(),
                    Validity.empty().endedWhenUnknown().attested(t("2025-12-01T00:00:00Z")), 0.9f);
            assertTrue(!again.created(), "没有开放行可关，也不是新信息");
            assertEquals(closed.id(), again.id());
        } finally {
            cleanup(f);
        }
    }

    /**
     * 派生读前提<b>读出来的</b>区间（0022 第 4 条）：一条 part_of 链里，没起点的那一环从
     * 它的证据日期起算，结束了不知哪天的那一环到说出它的那份文档为止。派生行的两端因此
     * 是前提共同支撑的那一段；来自锚点的那一端没有精度。
     * 推导本身（reasoning::materialize）属 M3 任务 6——这里以 SQL 按其会算出的区间落
     * derived_facts 终态（桩），断言（区间形状 + neighborhood 按读出区间亮灭）逐条对译；
     * 任务 6 把桩换成真 materialize 调用后这些断言开始检验推导本身。
     */
    @Test
    void a_derivation_reads_no_further_than_its_premises() {
        Fixture f = seed();
        try {
            // part_of 传递；四个组织 a ⊂ b ⊂ c ⊂ d
            UUID partOf = Uuids.nowV7();
            jdbc.update("""
                    INSERT INTO relation_types (id, kb_id, key, label, is_transitive)
                     VALUES (?, ?, 'part_of', 'part of', TRUE)
                    """, partOf, f.kb());
            UUID orgType = jdbc.queryForObject("SELECT type_id FROM entities WHERE id = ?",
                    UUID.class, f.meridian());
            List<UUID> ids = new java.util.ArrayList<>();
            for (String name : new String[]{"Team A", "Group B", "Division C", "Company D"}) {
                UUID id = Uuids.nowV7();
                jdbc.update(
                        "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                        id, f.kb(), orgType, name);
                ids.add(id);
            }
            UUID a = ids.get(0);
            UUID b = ids.get(1);
            UUID c = ids.get(2);
            UUID d = ids.get(3);
            // a ⊂ b：原文给了起点 2022-01-01
            fact(f, a, partOf, b, Validity.starting(t("2022-01-01T00:00:00Z"), "day")
                    .attested(t("2022-01-01T00:00:00Z")));
            // b ⊂ c：没起点，出自 2024-02-20 的文档
            fact(f, b, partOf, c, Validity.empty().attested(t("2024-02-20T00:00:00Z")));
            // c ⊂ d：2020-01-01 起，2025-10-15 的文档说已不再（日期未记录）
            fact(f, c, partOf, d, Validity.starting(t("2020-01-01T00:00:00Z"), "day")
                    .endedWhenUnknown().attested(t("2025-10-15T00:00:00Z")));

            // 任务 6 去封：真 materialize 落 derived_facts 终态（前提读出区间的交集：
            // a⊂c=[b⊂c 锚点,∞)；a⊂d/b⊂d=∩ c⊂d 的 [2024-02-20, 2025-10-15)）
            reasoning.materialize(f.kb());

            var derived = new java.util.function.BiFunction<UUID, UUID, Object[]>() {
                public Object[] apply(UUID s, UUID o) {
                    return jdbc.queryForObject("""
                            SELECT valid_from, valid_from_precision, valid_to, valid_to_precision
                              FROM derived_facts
                             WHERE kb_id = ? AND subject_id = ? AND object_id = ?
                               AND invalidated_at IS NULL
                            """, (rs, i) -> new Object[]{rs.getObject(1, OffsetDateTime.class),
                            rs.getString(2), rs.getObject(3, OffsetDateTime.class), rs.getString(4)},
                            f.kb(), s, o);
                }
            };
            // a ⊂ c：起点是 b ⊂ c 的证据日期（锚点），没有精度；终点开放
            var ac = derived.apply(a, c);
            assertEquals(t("2024-02-20T00:00:00Z").toInstant(),
                    ((OffsetDateTime) ac[0]).toInstant(), "从没起点那一环的证据起算");
            assertEquals(null, ac[1], "来自锚点的一端没有精度");
            assertEquals(null, ac[2]);

            // a ⊂ d：起点同上；终点是 c ⊂ d 的锚点（说出结束的那份文档），也没有精度
            var ad = derived.apply(a, d);
            assertEquals(t("2024-02-20T00:00:00Z").toInstant(),
                    ((OffsetDateTime) ad[0]).toInstant());
            assertEquals(null, ad[1]);
            assertEquals(t("2025-10-15T00:00:00Z").toInstant(),
                    ((OffsetDateTime) ad[2]).toInstant(), "到结束的那份文档为止");
            assertEquals(null, ad[3], "'unknown' 不是粒度，锚点顶上来的一端没有精度");

            // b ⊂ d：起点是 b ⊂ c 的锚点、终点是 c ⊂ d 的锚点——两端都没有精度
            var bd = derived.apply(b, d);
            assertEquals(t("2024-02-20T00:00:00Z").toInstant(),
                    ((OffsetDateTime) bd[0]).toInstant());
            assertEquals(t("2025-10-15T00:00:00Z").toInstant(),
                    ((OffsetDateTime) bd[2]).toInstant());
            assertEquals(null, bd[1]);
            assertEquals(null, bd[3]);

            // 图上按读出来的区间亮灭：2023 年 a ⊂ c 还不存在；2024 年中在；2026 年 a ⊂ d 已灭
            // 从 b 看两跳：a ⊂ b 与 c ⊂ d 把 a、d 都收进来，派生边才都在「这些节点之间」
            java.util.function.Function<String, java.util.Set<String>> at = when ->
                    graph.neighborhood(f.kb(), b, 2, t(when), null).edges().stream()
                            .filter(e -> e.derived())
                            .map(e -> e.source() + ">" + e.target())
                            .collect(java.util.stream.Collectors.toSet());
            String acEdge = a + ">" + c;
            String adEdge = a + ">" + d;
            assertFalse(at.apply("2023-06-01T00:00:00Z").contains(acEdge));
            assertTrue(at.apply("2024-06-01T00:00:00Z").contains(acEdge));
            assertTrue(at.apply("2024-06-01T00:00:00Z").contains(adEdge));
            assertFalse(at.apply("2026-01-01T00:00:00Z").contains(adEdge),
                    "链经过已结束的一环，之后不成立");
            assertTrue(at.apply("2026-01-01T00:00:00Z").contains(acEdge), "另一条链还开着");
        } finally {
            cleanup(f);
        }
    }

    /** 裸行关上之后那一行：起点锚第一份证据、终点锚说结束的文档。 */
    @Test
    void a_bare_row_closes_with_an_anchor_at_each_end() {
        Fixture f = seed();
        try {
            // 2024-02-20 的便签：担任 Staff Engineer，没写从哪天起
            var bare = writes.insertFact(f.kb(), f.lin(), f.holdsTitle(), f.staffEngineer(),
                    Validity.empty().attested(t("2024-02-20T00:00:00Z")), 0.9f);
            // 2025-10-15 的角色说明：不再担任，日期未记录——同一断言，也没起点
            var closed = writes.insertFact(f.kb(), f.lin(), f.holdsTitle(), f.staffEngineer(),
                    Validity.empty().endedWhenUnknown().attested(t("2025-10-15T00:00:00Z")), 0.9f);
            assertTrue(closed.created(), "关上是一条修正行，不再是并进去");
            assertNotEquals(bare.id(), closed.id());

            record BareClosed(OffsetDateTime from, String toPrec, OffsetDateTime attestedFrom,
                               OffsetDateTime attestedTo) {
            }
            BareClosed row = jdbc.queryForObject("""
                    SELECT valid_from, valid_to_precision, attested_from, attested_to
                      FROM facts WHERE id = ?
                    """, (rs, i) -> new BareClosed(rs.getObject(1, OffsetDateTime.class),
                    rs.getString(2), rs.getObject(3, OffsetDateTime.class),
                    rs.getObject(4, OffsetDateTime.class)), closed.id());
            assertEquals(null, row.from(), "起点原文没给，仍然没给");
            assertEquals("unknown", row.toPrec());
            assertEquals(t("2024-02-20T00:00:00Z").toInstant(), row.attestedFrom().toInstant(),
                    "起点的锚点是第一份证据");
            assertEquals(t("2025-10-15T00:00:00Z").toInstant(), row.attestedTo().toInstant(),
                    "终点的锚点是说出结束的那份文档");

            // 读出来：[第一份证据, 说结束的文档)
            assertFalse(edgesAt(f, "2023-12-01T00:00:00Z").contains(closed.id()));
            assertTrue(edgesAt(f, "2024-06-01T00:00:00Z").contains(closed.id()));
            assertFalse(edgesAt(f, "2026-01-01T00:00:00Z").contains(closed.id()));
            var edges = graph.neighborhood(f.kb(), f.lin(), 1, null, null).edges();
            var edge = edges.stream().filter(e -> e.id().equals(closed.id())).findFirst()
                    .orElseThrow();
            assertEquals(t("2024-02-20T00:00:00Z"), edge.holdsFrom());
            assertEquals(t("2025-10-15T00:00:00Z"), edge.holdsTo());
        } finally {
            cleanup(f);
        }
    }
}
