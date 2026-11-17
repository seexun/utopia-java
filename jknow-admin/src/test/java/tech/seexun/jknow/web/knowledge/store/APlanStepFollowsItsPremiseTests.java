package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.BusinessRulesService;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.TypedMaterializeService;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.BusinessRulesService.ConditionInput;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.OntologyService.RelationAxioms;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.temporal.Side;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_plan_step_follows_its_premise.rs（830 行，5 例）——步骤结论跟着前提
 * 走（#875）：初始→重复→遮挡→移动→晚到旧观察→明确结束→撤回，一条时间线走完；两条独立
 * 证明（组间或）撤一条仍成立。后三例（对齐/同身份更新）断言面在类型化物化（materialize.rs），
 * 随任务 7 落地——测试体与断言全量移植，@Disabled 待任务 7。
 */
class APlanStepFollowsItsPremiseTests extends HeartStoreTestBase {

    // Rust 测试用的时间常量
    private static final String T0 = "2026-09-23T06:50:30Z";
    private static final String T1 = "2026-09-23T07:00:00Z";
    private static final String T2 = "2026-09-23T08:00:00Z";
    private static final String T_OCC = "2026-09-23T07:40:00Z";
    private static final String T_MID = "2026-09-23T07:30:00Z";
    private static final String T_END = "2026-09-23T09:10:00Z";
    private static final String NOW = "2026-09-23T12:00:00Z";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReasoningService reasoning;
    @Autowired
    BusinessRulesService rules;
    @Autowired
    OntologyService ontology;
    @Autowired
    GraphWriteService writes;
    @Autowired
    TemporalService temporal;
    @Autowired
    TypedMaterializeService typedMaterialize;
    @Autowired
    PhraseBindingsService phraseBindings;
    @Autowired
    DocumentsWriteService documents;
    @Autowired
    DocumentsWriteMapper documentsMapper;

    private static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s);
    }

    record Fixture(UUID org, UUID kb, UUID cup, UUID boxed, UUID saReady, UUID sbReady,
                   UUID location, UUID rfidZone, UUID visibility, UUID a, UUID b) {
    }

    private Fixture seed(String name) {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, name);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org, name);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)",
                kb, ws, name);
        UUID cup = ontology.createEntityType(kb, "cup", "Cup", "#7fd0ff", "circle", List.of(), "");
        UUID boxed = ontology.createEntityType(kb, "box", "Box", "#7fd0ff", "circle", List.of(), "");
        // 步骤可执行写成归类结论：规则只会推类型或属性，不为这个实验加语法（0021）
        UUID saReady = ontology.createEntityType(kb, "step_sa_ready", "S_A precondition holds",
                "#7fd0ff", "circle", List.of(), "");
        UUID sbReady = ontology.createEntityType(kb, "step_sb_ready", "S_B precondition holds",
                "#7fd0ff", "circle", List.of(), "");
        // 一个东西同一时刻只在一处：声明 functional，时间线才会让后一个位置关上前一个
        UUID location = ontology.createRelationType(kb, "location", "location", "state",
                new RelationAxioms(true, false, false, false, false, false, null, null), "",
                "attribute", List.of(cup, boxed), List.of(), "text", null);
        UUID rfidZone = ontology.createRelationType(kb, "rfid_zone", "rfid_zone", "state",
                new RelationAxioms(false, false, false, false, false, false, null, null), "",
                "attribute", List.of(cup, boxed), List.of(), "text", null);
        UUID visibility = ontology.createRelationType(kb, "visibility", "visibility", "state",
                new RelationAxioms(false, false, false, false, false, false, null, null), "",
                "attribute", List.of(cup, boxed), List.of(), "text", null);
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        for (Object[] row : new Object[][]{{a, cup, "cup-7"}, {b, boxed, "box-3"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        return new Fixture(org, kb, cup, boxed, saReady, sbReady, location, rfidZone, visibility,
                a, b);
    }

    /** 一条步骤规则：主类 class 的属性落在 places 里 → 归到 ready。 */
    private UUID stepRule(Fixture f, String name, UUID class_, UUID ready, UUID predicate,
                          String place) {
        return rules.create(f.kb(), name,
                "the modelled precondition of a plan step; not a safety or success guarantee",
                class_, "typing", ready, null, null, null, null,
                List.of(new ConditionInput(0, predicate, "in",
                        JsonNodeFactory.instance.arrayNode().add(place), "x")));
    }

    /** 一次观察：属性事实走类型化图谱的门，带唯一性的状态关系随后对账。 */
    private UUID observe(Fixture f, UUID subject, UUID predicate, String value, String at) {
        Validity validity = new Validity(t(at), "second", null, null, t(at), null);
        var valueNode = JsonNodeFactory.instance.objectNode().put("value", value);
        var wrote = writes.insertValueFact(f.kb(), subject, predicate, valueNode, validity, 1.0f);
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT functional FROM relation_types WHERE id = ?", Boolean.class, predicate))
                && "state".equals(jdbc.queryForObject(
                "SELECT temporal FROM relation_types WHERE id = ?", String.class, predicate))) {
            temporal.reconcileNewFact(f.kb(), wrote.id(), subject, predicate, null,
                    Side.SUBJECT_SIDE);
        }
        return wrote.id();
    }

    record Row(UUID id, OffsetDateTime validFrom, OffsetDateTime validTo, String validToPrecision) {
    }

    private List<Row> liveRows(Fixture f, UUID subject, UUID predicate, String value) {
        return jdbc.query("""
                SELECT id, valid_from, valid_to, valid_to_precision FROM facts
                  WHERE kb_id = ? AND subject_id = ? AND predicate_id = ?
                    AND object_value = ?::jsonb AND invalidated_at IS NULL
                  ORDER BY valid_from NULLS FIRST, recorded_at
                """, (rs, i) -> new Row(rs.getObject(1, UUID.class),
                rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class),
                rs.getString(4)), f.kb(), subject, predicate,
                "{\"value\": \"" + value + "\"}");
    }

    private List<Row> conclusions(Fixture f, UUID entity, UUID rule) {
        return jdbc.query("""
                SELECT id, valid_from, valid_to, valid_to_precision FROM derived_facts
                  WHERE kb_id = ? AND subject_id = ? AND attribute_rule_id = ?
                    AND invalidated_at IS NULL
                  ORDER BY valid_from, id
                """, (rs, i) -> new Row(rs.getObject(1, UUID.class),
                rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class),
                rs.getString(4)), f.kb(), entity, rule);
    }

    private boolean holdsAt(Fixture f, UUID entity, UUID rule, String at, OffsetDateTime asOf) {
        return reasoning.derivedForEntity(f.kb(), entity, t(at), asOf).stream().anyMatch(
                d -> rule.equals(d.attributeRuleId()) && entity.equals(d.subjectId()));
    }

    private List<UUID> premises(UUID derived) {
        return jdbc.queryForList("""
                SELECT premise_fact_id FROM fact_derivations
                  WHERE derived_fact_id = ? AND premise_fact_id IS NOT NULL ORDER BY seq
                """, UUID.class, derived);
    }

    private OffsetDateTime dbNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    @Test
    void aStepLeavesWithItsPremiseAndTheOtherStepStays() {
        Fixture f = seed("issue875-step-premise");
        try {
            UUID ruleA = stepRule(f, "S_A pick cup from desk", f.cup(), f.saReady(), f.location(),
                    "desk");
            UUID ruleB = stepRule(f, "S_B pick box from shelf", f.boxed(), f.sbReady(),
                    f.location(), "shelf");

            // ---- 初始观察：A 在桌上、B 在架上
            UUID aDesk = observe(f, f.a(), f.location(), "desk", T1);
            UUID bShelf = observe(f, f.b(), f.location(), "shelf", T1);
            var r = reasoning.materialize(f.kb());
            assertEquals(2, r.attributeRules());
            assertEquals(2, r.ruleHits());
            assertEquals(2, r.inserted());
            assertEquals(0, r.invalidated());
            List<Row> sa = conclusions(f, f.a(), ruleA);
            List<Row> sb = conclusions(f, f.b(), ruleB);
            assertEquals(1, sa.size());
            assertEquals(1, sb.size());
            assertEquals(t(T1), sa.get(0).validFrom());
            assertEquals(null, sa.get(0).validTo(), "前提还开着，结论也开着");
            // 前提定位得到：派生指着那条读数，证明读得出它
            assertEquals(List.of(aDesk), premises(sa.get(0).id()));
            assertEquals(List.of(bShelf), premises(sb.get(0).id()));
            var proof = reasoning.proof(f.kb(), sa.get(0).id());
            assertNotNull(proof, "a live conclusion has a proof");
            assertEquals(1, proof.steps().size());
            assertEquals(aDesk, proof.steps().get(0).factId);
            assertEquals(false, proof.steps().get(0).retracted);
            var matches = rules.matches(f.kb(), ruleA, 20, 0);
            assertEquals(1, matches.total());
            assertEquals(f.a().toString(), matches.rows().get(0).path("entity_id").asText());
            assertTrue(holdsAt(f, f.a(), ruleA, NOW, null));
            assertTrue(holdsAt(f, f.b(), ruleB, NOW, null));

            // ---- 同一份观察再来一遍：同断言同起点复用那一行，推导无事可做
            assertEquals(aDesk, observe(f, f.a(), f.location(), "desk", T1));
            var again = reasoning.materialize(f.kb());
            assertEquals(0, again.inserted());
            assertEquals(0, again.invalidated());
            assertEquals(0, again.reproved());

            // ---- 遮挡：没人写 location，另一个属性记下「看不见」。没看见 ≠ 不在桌上
            observe(f, f.a(), f.visibility(), "occluded", T_OCC);
            var occluded = reasoning.materialize(f.kb());
            assertEquals(0, occluded.inserted());
            assertEquals(0, occluded.invalidated());
            assertEquals(conclusions(f, f.a(), ruleA).get(0).id(), sa.get(0).id());
            assertTrue(holdsAt(f, f.a(), ruleA, NOW, null));

            // ---- 移动：A 被正面看到在架上。时间线把桌面那一段关在 t2（作废 + 改写）
            OffsetDateTime beforeMove = dbNow();
            observe(f, f.a(), f.location(), "shelf", T2);
            List<Row> desk = liveRows(f, f.a(), f.location(), "desk");
            assertEquals(1, desk.size());
            assertNotEquals(aDesk, desk.get(0).id(), "关上的是改写出来的新行，旧行作废");
            assertEquals(t(T2), desk.get(0).validTo());
            var moved = reasoning.materialize(f.kb());
            assertEquals(1, moved.inserted());
            assertEquals(1, moved.invalidated());
            assertEquals(false, holdsAt(f, f.a(), ruleA, NOW, null), "S_A 在现在不再成立");
            assertEquals(true, holdsAt(f, f.a(), ruleA, T_MID, null),
                    "t1..t2 之间它成立过：世界轴上的历史");
            List<Row> saNow = conclusions(f, f.a(), ruleA);
            assertEquals(1, saNow.size());
            assertEquals(t(T1), saNow.get(0).validFrom());
            assertEquals(t(T2), saNow.get(0).validTo());
            assertEquals(List.of(desk.get(0).id()), premises(saNow.get(0).id()));
            // 旧结论作废而不删，记录轴回到移动之前它还在，前提链还指着当时那条读数
            OffsetDateTime oldInvalidated = jdbc.queryForObject(
                    "SELECT invalidated_at FROM derived_facts WHERE id = ?",
                    OffsetDateTime.class, sa.get(0).id());
            assertNotNull(oldInvalidated);
            assertEquals(List.of(aDesk), premises(sa.get(0).id()));
            assertEquals(true, holdsAt(f, f.a(), ruleA, NOW, beforeMove),
                    "当时所知：移动之前的库认为 S_A 至今成立");
            // 无关步骤原样：同一行、没作废
            List<Row> sbAfter = conclusions(f, f.b(), ruleB);
            assertEquals(1, sbAfter.size());
            assertEquals(sb.get(0).id(), sbAfter.get(0).id());
            assertTrue(holdsAt(f, f.b(), ruleB, NOW, null));

            // ---- 晚到的旧观察：t0 时 A 在桌上，移动之后才送到
            observe(f, f.a(), f.location(), "desk", T0);
            reasoning.materialize(f.kb());
            assertEquals(false, holdsAt(f, f.a(), ruleA, NOW, null));
            assertEquals(true, holdsAt(f, f.a(), ruleA, "2026-09-23T07:55:00Z", null));
            List<UUID> aRowsBeforeEnd = conclusions(f, f.a(), ruleA).stream().map(Row::id)
                    .toList();

            // ---- 明确结束：B 在架上这一段止于 t_end（人在 Review 里关上它的那一步）
            List<Row> bRows = liveRows(f, f.b(), f.location(), "shelf");
            assertEquals(1, bRows.size());
            temporal.closeSuperseded(bRows.get(0).id(), t(T_END), "second");
            var ended = reasoning.materialize(f.kb());
            assertEquals(1, ended.inserted());
            assertEquals(1, ended.invalidated());
            assertEquals(false, holdsAt(f, f.b(), ruleB, NOW, null));
            assertEquals(true, holdsAt(f, f.b(), ruleB, T_MID, null));
            List<UUID> aRowsAfterEnd = conclusions(f, f.a(), ruleA).stream().map(Row::id)
                    .toList();
            assertEquals(aRowsBeforeEnd, aRowsAfterEnd, "关 B 不碰 A 的结论");

            // ---- 撤回：B 那条读数被判为错读。结论整条作废，证明还读得出当时靠的是什么
            List<Row> bClosed = liveRows(f, f.b(), f.location(), "shelf");
            List<Row> sbClosed = conclusions(f, f.b(), ruleB);
            assertEquals(1, bClosed.size());
            assertEquals(1, sbClosed.size());
            writes.rejectFact(f.kb(), bClosed.get(0).id());
            var retracted = reasoning.materialize(f.kb());
            assertEquals(0, retracted.inserted());
            assertEquals(1, retracted.invalidated());
            assertTrue(conclusions(f, f.b(), ruleB).isEmpty());
            assertEquals(false, holdsAt(f, f.b(), ruleB, T_MID, null));
            var history = reasoning.proof(f.kb(), sbClosed.get(0).id());
            assertNotNull(history, "an invalidated conclusion keeps its proof");
            assertNotNull(history.derived().invalidatedAt());
            assertEquals(bClosed.get(0).id(), history.steps().get(0).factId);
            assertEquals(true, history.steps().get(0).retracted, "撤掉的前提照样列出并打上标记");
        } finally {
            jdbc.update("DELETE FROM axiom_violations WHERE kb_id = ?", f.kb());
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 一个步骤两条独立的证明（组间「或」）：撤掉一条仍成立、证明换成另一条。 */
    @Test
    void aStepWithTwoProofsStaysUntilItsLastSupportGoes() {
        Fixture f = seed("issue875-two-proofs");
        try {
            UUID rule = rules.create(f.kb(), "S_A pick cup from desk, either sensor", "",
                    f.cup(), "typing", f.saReady(), null, null, null, null, List.of(
                    new ConditionInput(0, f.location(), "in",
                            JsonNodeFactory.instance.arrayNode().add("desk"), "x"),
                    new ConditionInput(1, f.rfidZone(), "in",
                            JsonNodeFactory.instance.arrayNode().add("desk"), "x")));
            UUID seen = observe(f, f.a(), f.location(), "desk", T1);
            UUID tagged = observe(f, f.a(), f.rfidZone(), "desk", T1);
            var r = reasoning.materialize(f.kb());
            // 两组推出同一段区间，落成一行；留下的证明是组序在前的那条（0029）
            assertEquals(1, r.ruleHits());
            assertEquals(1, r.inserted());
            List<Row> step = conclusions(f, f.a(), rule);
            assertEquals(1, step.size());
            assertEquals(List.of(seen), premises(step.get(0).id()));

            writes.rejectFact(f.kb(), seen);
            var reproved = reasoning.materialize(f.kb());
            assertEquals(0, reproved.inserted());
            assertEquals(0, reproved.invalidated());
            assertEquals(1, reproved.reproved(), "结论没变、理由变了：同一行重写证明（0030）");
            List<Row> still = conclusions(f, f.a(), rule);
            assertEquals(1, still.size());
            assertEquals(step.get(0).id(), still.get(0).id());
            assertEquals(List.of(tagged), premises(still.get(0).id()));
            assertTrue(holdsAt(f, f.a(), rule, NOW, null));

            writes.rejectFact(f.kb(), tagged);
            var gone = reasoning.materialize(f.kb());
            assertEquals(0, gone.inserted());
            assertEquals(1, gone.invalidated());
            assertTrue(conclusions(f, f.a(), rule).isEmpty());
            assertEquals(false, holdsAt(f, f.a(), rule, NOW, null));
        } finally {
            jdbc.update("DELETE FROM axiom_violations WHERE kb_id = ?", f.kb());
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    // ---- 以下三例断言面在类型化物化（materialize.rs / phrase_bindings）——任务 7 去封 ----

    // ---- 从开放陈述起步：对齐那一段（Rust :544-587 document/statement/bind_is_on/typed_desk） ----

    /** 一份带日期的文档和它的一块（推送来的陈述就是这样一份文档，doc_time_source='source'）。 */
    private UUID[] document(Fixture f, String name, String docTime, String text) {
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        jdbc.update(
                "INSERT INTO documents (id, kb_id, filename, sha256, doc_time, doc_time_source)"
                        + " VALUES (?, ?, ?, ?, ?, 'source')",
                doc, f.kb(), name, name + "-" + doc, t(docTime));
        jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text) VALUES (?, ?, ?, 0, ?)",
                chunk, f.kb(), doc, text);
        return new UUID[]{doc, chunk};
    }

    /** 一条开放陈述「cup-7 —is on→ <place>」，证据是那一块、没有引文（0054 决定 4）。 */
    private UUID statement(Fixture f, UUID chunk, String place, String attested) {
        var value = JsonNodeFactory.instance.objectNode().put("value", place);
        var wrote = writes.insertOpenStatement(f.kb(), f.a(), "is on", null, value, t(attested),
                1.0f);
        writes.addEvidenceLocated(wrote.id(), chunk, null, "is on", null);
        return wrote.id();
    }

    /** 人把签名（is on × cup × 值）绑到 location，与 Review 里点下去的那一次同一个函数。 */
    private void bindIsOn(Fixture f) {
        var sig = new tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature(
                "is on", f.cup(), "cup", null, null, true, 1, List.of(), List.of(), List.of());
        boolean written = phraseBindings.decide(f.kb(), sig, new PhraseBindingsService.Decision(
                f.location(), "forward", "bound",
                JsonNodeFactory.instance.objectNode().put("person",
                        JsonNodeFactory.instance.objectNode()
                                .put("property", "location").put("direction", "forward")),
                "person", null, null, false));
        assertTrue(written);
    }

    /** 类型化行里 A 的 location = desk 那一行（活着的）。 */
    private List<Row> typedDesk(Fixture f) {
        return jdbc.query(
                "SELECT id, valid_from, valid_to, valid_to_precision FROM facts"
                        + " WHERE kb_id = ? AND layer = 'typed' AND subject_id = ? AND predicate_id = ?"
                        + " AND object_value = '{\"value\": \"desk\"}'::jsonb AND invalidated_at IS NULL",
                (rs, i) -> new Row(rs.getObject(1, UUID.class),
                        rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class),
                        rs.getString(4)), f.kb(), f.a(), f.location());
    }

    /**
     * 两份观察各是一份文档（一次观察一个身份）。物化随手对账，桌面那一段关在第二份的日期上，
     * 步骤跟着退场——显式对账端点（POST .../relation-types/{type_id}/reconcile）就是这一步。
     */
    @Test
    void anExplicitReconcileClosesTheEarlierPlaceAndTheStepLeaves() {
        Fixture f = seed("issue875-reconcile");
        try {
            UUID rule = stepRule(f, "S_A pick cup from desk", f.cup(), f.saReady(), f.location(),
                    "desk");
            UUID[] c1 = document(f, "obs-1.json", T1, "cup-7 is on desk");
            UUID[] c2 = document(f, "obs-2.json", T2, "cup-7 is on shelf");
            statement(f, c1[1], "desk", T1);
            statement(f, c2[1], "shelf", T2);
            bindIsOn(f);
            var typed = typedMaterialize.materialize(f.kb());
            // 物化自己就对账了（#899）：桌面那一段在这里关上。显式对账仍然可用，只是没剩下要改的
            assertEquals(2L, typed.added());
            assertEquals(1L, typed.corrected(), typed.toString());

            var report = temporal.reconcilePredicate(f.kb(), f.location());
            assertEquals(0, report.corrected.size());
            assertEquals(0, report.conflicts, "nothing left for the explicit reconcile: " + report);
            List<Row> desk = typedDesk(f);
            assertEquals(1, desk.size());
            assertEquals(null, desk.get(0).validFrom(), "没有模型读时间词：起点留空");
            assertEquals("unknown", desk.get(0).validToPrecision(),
                    "没起点的后任：前任写成「结束了，不知哪天」，锚在后任那份文档的日期上");
            reasoning.materialize(f.kb());
            assertFalse(holdsAt(f, f.a(), rule, NOW, null));
            assertTrue(holdsAt(f, f.a(), rule, T_MID, null));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** #899：两份观察各是一份文档，绑定之后物化自己就把桌面那一段关上，不用再显式对账。 */
    @Test
    void aLaterBoundStatementClosesTheEarlierPlace() {
        Fixture f = seed("issue899-materialize-timeline");
        try {
            UUID rule = stepRule(f, "S_A pick cup from desk", f.cup(), f.saReady(), f.location(),
                    "desk");
            UUID[] c1 = document(f, "obs-1.json", T1, "cup-7 is on desk");
            UUID[] c2 = document(f, "obs-2.json", T2, "cup-7 is on shelf");
            statement(f, c1[1], "desk", T1);
            statement(f, c2[1], "shelf", T2);
            bindIsOn(f);
            var typed = typedMaterialize.materialize(f.kb());
            assertEquals(2L, typed.added());
            assertEquals(1L, typed.corrected());
            assertEquals(0, typed.conflicts(), typed.toString());
            List<Row> desk = typedDesk(f);
            assertEquals(1, desk.size());
            assertEquals("unknown", desk.get(0).validToPrecision(),
                    "location is functional and a later place was materialized: the desk row is closed"
                            + " without an explicit reconcile");
            // 再跑一遍是空转：没有新行，也不再对账
            var again = typedMaterialize.materialize(f.kb());
            assertEquals(0L, again.added());
            assertEquals(0L, again.merged());
            assertEquals(0L, again.corrected());
            assertEquals(0, again.conflicts(), again.toString());
            reasoning.materialize(f.kb());
            assertFalse(holdsAt(f, f.a(), rule, NOW, null));
            assertTrue(holdsAt(f, f.a(), rule, T_MID, null));
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /**
     * #900：同一身份再推一份新内容（原地替换、记版本、doc_time 换成新的，新块顶替旧块）。
     * 停在旧版上的证据按它那一版（版本行）的日期算，所以后一次观察关得上前一段，而不是
     * 记成「同时」的冲突。
     */
    @Test
    void aSameIdentityUpdateClosesTheEarlierPlace() {
        Fixture f = seed("issue900-same-identity");
        try {
            UUID[] c1 = document(f, "cup-7.json", T1, "cup-7 is on desk");
            UUID doc = c1[0];
            // 直接写进库的文档没有版本行：第一版按现在的日期补上，和摄入路径写下的一样
            documentsMapper.recordVersion(Uuids.nowV7(), doc, "cup-7-v1", 17);
            statement(f, c1[1], "desk", T1);
            bindIsOn(f);
            assertEquals(1L, typedMaterialize.materialize(f.kb()).added());

            // 第二次推送：原地替换、记版本、doc_time 换成新的（ingest_item 的那一步），新块顶替旧块
            documents.replaceContentAndEnqueue(doc, "cup-7.json", "application/json", 17,
                    "cup-7-v2", t(T2));
            // 那一步排下的处理任务这里不跑：抽取的结果由下面几行写出来
            jdbc.update("DELETE FROM jobs WHERE payload->>'document_id' = ?", doc.toString());
            // Rust replace_chunks（无条件顶替）的等价入口：认领式分块入口 sha 对上即全量替换
            List<tech.seexun.jknow.ingest.ChunkPiece> pieces = tech.seexun.jknow.ingest.Chunker
                    .chunkWithBudget("cup-7 is on shelf", tech.seexun.jknow.ingest.Chunker.BUDGET_TOKENS);
            assertNotNull(documents.replaceChunksIfCurrent(f.kb(), doc, pieces, "cup-7-v2"));
            UUID c2 = jdbc.queryForObject(
                    "SELECT id FROM chunks WHERE document_id = ? AND superseded_at IS NULL",
                    UUID.class, doc);
            statement(f, c2, "shelf", T2);
            var second = typedMaterialize.materialize(f.kb());
            assertEquals(1L, second.added());
            assertEquals(1L, second.corrected());
            assertEquals(0, second.conflicts(),
                    "the desk row dates at version 1's time, the shelf row at version 2's: " + second);
            List<Row> desk = typedDesk(f);
            assertEquals(1, desk.size());
            assertEquals("unknown", desk.get(0).validToPrecision(),
                    "after a same-identity update the desk row is closed");
            // 版本表记着各自的日期
            List<Object[]> dates = jdbc.query(
                    "SELECT version, doc_time FROM document_versions WHERE document_id = ?"
                            + " ORDER BY version",
                    (rs, i) -> new Object[]{rs.getInt(1), rs.getObject(2, OffsetDateTime.class)},
                    doc);
            assertEquals(2, dates.size());
            assertEquals(1, dates.get(0)[0]);
            assertEquals(t(T1), dates.get(0)[1]);
            assertEquals(2, dates.get(1)[0]);
            assertEquals(t(T2), dates.get(1)[1]);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
