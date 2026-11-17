package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.entity.AlignmentItem;
import tech.seexun.jknow.store.entity.AuditEventView;
import tech.seexun.jknow.store.entity.ReviewCounts;
import tech.seexun.jknow.store.entity.ReviewItem;
import tech.seexun.jknow.store.entity.ReviewSummary;

/**
 * review 读路径对真实库的断言（任务 4；StoreTestBase 口径：直连共享库 + INSERT fixture
 * + @AfterAll 级联清理）。示例库的审核族数据全零（抽取质量高），故以专建 fixture 库
 * 铺 12 队列的最小代表集：pending 六联（走查1 补）/duplicates 三档/lowconf 升序/
 * unconfirmed（证据全被取代）/conflicts 新旧快照（走查1 补）/alignment 三合一
 * （短语+类别词+规则）/violations 文本展开/defects 环标签（走查1 补）/agent 建议挂卡/
 * merges/errata/mappings/history——每条都对应一条逐字搬的 SQL 的真实执行。
 */
class ReviewStoreTests extends StoreTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String EMAIL = "task4-rv-" + RUN + "@test.local";

    private static UUID orgId;
    private static UUID userId;
    private static UUID wsId;
    private static UUID kbId;
    private static UUID typeCompany;
    private static UUID typePerson;
    private static UUID relEmploys;
    private static UUID e1; // Acme（Company）
    private static UUID e2; // Acme 消歧（Company）——与 e1 同类
    private static UUID e3; // Beta（Company）——e3×e4 类型冲突对
    private static UUID e4; // Gamma（Person）
    private static UUID factLow1; // confidence 0.3，活证据
    private static UUID factLow2; // confidence 0.5，证据全被取代 → unconfirmed
    private static UUID reviewSame; // e1×e2 pending（有 agent 建议）
    private static UUID reviewConflict; // e3×e4 pending（无建议 → agent_queue）
    private static UUID chunkLive; // F1 的活证据分块（走查1：pending 行也挂它）
    private static UUID pendingId; // 走查1：pending 队列一行（chunk/users 关联）
    private static UUID factOld; // 走查1：冲突旧行（Delta employs Epsilon，2023 起）
    private static UUID factNew; // 走查1：冲突新行（Delta employs Zeta，2026 起）
    private static UUID conflictId; // 走查1：fact_conflicts 一行 open
    private static UUID defectId; // 走查1：ontology_defects 一行 open（subclass_cycle）

    @Autowired
    ReviewService review;

    @BeforeAll
    void fixture() {
        orgId = jdbc.queryForObject("SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        userId = UUID.randomUUID();
        jdbc.update("CALL jknow_test_user(?, ?, ?, 'Task4 RV', true)", userId, orgId, EMAIL);
        wsId = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", wsId, orgId, "ws-task4-rv-" + RUN);
        kbId = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)", kbId, wsId, "kb-task4-rv-" + RUN);

        typeCompany = insertType("company", "Company", "#22c55e");
        typePerson = insertType("person", "Person", "#64748b");
        relEmploys = insertRelation("employs", "Employs");

        e1 = insertEntity("Acme", typeCompany, null);
        e2 = insertEntity("Acme", typeCompany, "the other one");
        e3 = insertEntity("Beta", typeCompany, null);
        e4 = insertEntity("Gamma", typePerson, null);
        UUID e5 = insertEntity("Merged Src", null, null);
        UUID e6 = insertEntity("Merged Dst", null, null);
        // 类别词签名的素材：specific_type=Company 的两个实体（签名按活实体数出例名）
        insertEntityWithSpecificType("Sig One", "Company");
        insertEntityWithSpecificType("Sig Two", "Company");

        // 文档/分块：F1 活证据（quote）、F2 旧版证据（superseded → unconfirmed）
        UUID doc = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256) VALUES (?, ?, 'task4-rv.md', '"
                + "0".repeat(64) + "')", doc, kbId);
        chunkLive = UUID.randomUUID();
        UUID chunkStale = UUID.randomUUID();
        jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text) VALUES (?, ?, ?, 1, 'Acme employs Beta since 2023')",
                chunkLive, kbId, doc);
        jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text, superseded_at) "
                + "VALUES (?, ?, ?, 2, 'old version', now() - interval '1 hour')", chunkStale, kbId, doc);

        factLow1 = UUID.randomUUID();
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence, recorded_at) "
                + "VALUES (?, ?, ?, ?, ?, 0.3, now() - interval '10 minutes')",
                factLow1, kbId, e1, relEmploys, e3);
        factLow2 = UUID.randomUUID();
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence, valid_to, valid_to_precision, recorded_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0.5, NULL, NULL, now() - interval '9 minutes')",
                factLow2, kbId, e2, relEmploys, e3);
        jdbc.update("INSERT INTO fact_evidence (fact_id, chunk_id, quote) VALUES (?, ?, 'Acme employs Beta')",
                factLow1, chunkLive);
        jdbc.update("INSERT INTO fact_evidence (fact_id, chunk_id, quote) VALUES (?, ?, 'stale quote')",
                factLow2, chunkStale);

        reviewSame = UUID.randomUUID();
        jdbc.update("INSERT INTO resolution_reviews (id, kb_id, left_id, right_id, score, reason, stage, created_at) "
                + "VALUES (?, ?, ?, ?, 0.9, NULL, 'human', now() - interval '8 minutes')",
                reviewSame, kbId, e1, e2);
        reviewConflict = UUID.randomUUID();
        jdbc.update("INSERT INTO resolution_reviews (id, kb_id, left_id, right_id, score, stage, created_at) "
                + "VALUES (?, ?, ?, ?, 0.8, 'human', now() - interval '7 minutes')",
                reviewConflict, kbId, e3, e4);
        // R1 上挂着开着的 agent 建议（0025）
        jdbc.update("INSERT INTO agent_decisions (id, kb_id, run_id, target_kind, target_id, action, confidence, reason, status, created_at) "
                + "VALUES (?, ?, ?, 'review', ?, 'merge', 0.9, 'same company', 'proposed', now() - interval '6 minutes')",
                UUID.randomUUID(), kbId, UUID.randomUUID(), reviewSame);

        jdbc.update("INSERT INTO entity_merges (id, kb_id, source_id, target_id, reason, created_at) "
                + "VALUES (?, ?, ?, ?, 'manual merge', now() - interval '5 minutes')", UUID.randomUUID(), kbId, e5, e6);

        jdbc.update("INSERT INTO phrase_bindings (id, kb_id, phrase, subject_type_id, status, statement_count, examples, votes, decided_at) "
                + "VALUES (?, ?, 'acquired', ?, 'undecided', 3, '{\"bought X\", \"took over Y\"}', "
                + "'{\"first\": null, \"second\": null}', now() - interval '4 minutes')", UUID.randomUUID(), kbId, typeCompany);
        jdbc.update("INSERT INTO type_bindings (id, kb_id, kind_word, words, status, votes, decided_at) "
                + "VALUES (?, ?, 'company', '{\"Company\"}', 'undecided', NULL, now() - interval '3 minutes')",
                UUID.randomUUID(), kbId);
        jdbc.update("INSERT INTO implication_rules (id, kb_id, trigger, phrase, conclude_property_id, status, decided_at) "
                + "VALUES (?, ?, 'phrase', 'acquired', ?, 'proposed', now() - interval '2 minutes')",
                UUID.randomUUID(), kbId, relEmploys);
        UUID errataRun = UUID.randomUUID();
        jdbc.update("INSERT INTO errata_runs (id, kb_id, document_id) VALUES (?, ?, ?)", errataRun, kbId, doc);
        jdbc.update("INSERT INTO errata_actions (id, kb_id, run_id, document_id, action, status, reason, quote, created_at) "
                + "VALUES (?, ?, ?, ?, 'retract', 'held', 'needs human', 'the quote', now() - interval '90 seconds')",
                UUID.randomUUID(), kbId, errataRun, doc);
        jdbc.update("INSERT INTO axiom_violations (id, kb_id, kind, left_fact, right_fact, path, detail, detected_at) "
                + "VALUES (?, ?, 'asymmetry', ?, ?, '{}', '{}', now() - interval '1 minute')",
                UUID.randomUUID(), kbId, factLow1, factLow2);
        jdbc.update("INSERT INTO concept_mappings (id, kb_id, concept_id, source, summary, status) "
                + "VALUES (?, ?, ?, 'orders', 'revenue', 'proposed')", UUID.randomUUID(), kbId, e1);
        jdbc.update("INSERT INTO audit_events (id, kb_id, actor_id, action, target_kind, target_id, detail) "
                + "VALUES (?, ?, ?, 'review.keep', 'review', ?, '{}')", UUID.randomUUID(), kbId, userId, reviewConflict);

        // 走查1 补：pending/conflicts/defects 三队列非空行（此前仅空库形状，构造器从未在真实行上跑过）
        // pending：六联 VIEW_SELECT 的素材——chunk 引文 + proposed_by 用户 + phrase/jsonb/span
        pendingId = UUID.randomUUID();
        jdbc.update("INSERT INTO pending_facts (id, kb_id, subject_id, predicate_id, object_id, confidence, "
                        + "valid_from, valid_from_precision, chunk_id, proposed_by, created_at, phrase, qualifiers, "
                        + "time_words, quote_start, quote_end) "
                        + "VALUES (?, ?, ?, ?, ?, 0.6, '2023-01-01T00:00:00Z', 'day', ?, ?, "
                        + "now() - interval '30 seconds', 'employs', '[{\"role\": \"to\", \"value\": \"Beta\"}]'::jsonb, "
                        + "'[{\"text\": \"since 2023\"}]'::jsonb, 0, 4)",
                pendingId, kbId, e1, relEmploys, e3, chunkLive, userId);
        // conflicts：新旧事实各一条（置信度 ≥0.75 且无证据——不进 lowconf/unconfirmed 口径），
        // 三个新实体不触碰 e1-e4 的 degree/top_facts 断言
        UUID e7 = insertEntity("Delta", null, null);
        UUID e8 = insertEntity("Epsilon", null, null);
        UUID e9 = insertEntity("Zeta", null, null);
        factOld = UUID.randomUUID();
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence, "
                        + "valid_from, valid_from_precision, recorded_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0.9, '2023-01-01T00:00:00Z', 'day', now() - interval '12 minutes')",
                factOld, kbId, e7, relEmploys, e8);
        factNew = UUID.randomUUID();
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id, confidence, "
                        + "valid_from, valid_from_precision, recorded_at) "
                        + "VALUES (?, ?, ?, ?, ?, 0.8, '2026-06-01T00:00:00Z', 'day', now() - interval '11 minutes')",
                factNew, kbId, e7, relEmploys, e9);
        conflictId = UUID.randomUUID();
        jdbc.update("INSERT INTO fact_conflicts (id, kb_id, old_fact_id, new_fact_id, reason, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'simultaneous', 'open', now() - interval '10 minutes')",
                conflictId, kbId, factOld, factNew);
        // defects：subject/other 是同列指两张表的裸 UUID——subclass_cycle 指 entity_types
        defectId = UUID.randomUUID();
        jdbc.update("INSERT INTO ontology_defects (id, kb_id, kind, subject, other, path, status, detail, detected_at) "
                        + "VALUES (?, ?, 'subclass_cycle', ?, ?, ARRAY[?, ?, ?]::uuid[], 'open', "
                        + "'{\"cycle_length\": 2}'::jsonb, now() - interval '45 seconds')",
                defectId, kbId, typeCompany, typePerson, typeCompany, typePerson, typeCompany);
    }

    private UUID insertType(String key, String label, String color) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label, color) VALUES (?, ?, ?, ?, ?)",
                id, kbId, key, label, color);
        return id;
    }

    private UUID insertRelation(String key, String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)", id, kbId, key, label);
        return id;
    }

    private UUID insertEntity(String name, UUID typeId, String disambiguator) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name, type_id, disambiguator, created_at) "
                + "VALUES (?, ?, ?, ?, ?, now() - interval '15 minutes')", id, kbId, name, typeId, disambiguator);
        return id;
    }

    private void insertEntityWithSpecificType(String name, String specificType) {
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name, specific_type, created_at) "
                + "VALUES (?, ?, ?, ?, now() - interval '15 minutes')", UUID.randomUUID(), kbId, name, specificType);
    }

    @AfterAll
    void cleanup() {
        if (!sharedStackUp()) {
            return;
        }
        // audit_events 是 append-only 台账（库上有触发器拒 DELETE）且无 kb FK：fixture 的
        // 台账行留账（与任务 1/2 双打 login/register 写入的审计行同性质）；其余随 kb 级联
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kbId);
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId);
        jdbc.update("CALL jknow_test_user_cleanup(?)", EMAIL);
    }

    // ---- counts：17 列对镜像（fixture 铺出全部非零档——走查1 补三队列行后无结构零档） ----

    @Test
    void countsMatchMirrorSql() {
        ReviewCounts c = review.counts(kbId);
        assertEquals(1, c.pending(), "一行待确认（走查1 补）");
        assertEquals(2, c.duplicates(), "duplicates=两对 pending");
        assertEquals(1, c.duplicatesSameType(), "same：e1×e2 都 Company");
        assertEquals(1, c.duplicatesTypeConflict(), "conflict：Beta(Company)×Gamma(Person)");
        assertEquals(1, c.conflicts(), "一条 open 冲突（走查1 补）");
        assertEquals(1, c.unconfirmed(), "F2 证据全被取代");
        assertEquals(2, c.lowconf(), "F1(0.3)+F2(0.5)——冲突对两条 ≥0.75 不进");
        assertEquals(1, c.mappings(), "一条 proposed");
        assertEquals(1, c.violations(), "一条 open");
        assertEquals(1, c.defects(), "一条 open 缺陷（走查1 补）");
        assertEquals(1, c.merges(), "一条合并日志");
        assertEquals(1, c.agent(), "一条 proposed 建议");
        assertEquals(1, c.agentRows(), "agent 行总数");
        assertFalse(c.agentRunning(), "无 govern 任务");
        assertEquals(1, c.agentQueue(), "R2 无建议（R1 有，不进队）");
        assertEquals(2, c.alignment(), "一条短语 + 一个类别词");
        assertEquals(1, c.errata(), "一笔 held");
    }

    // ---- pending：六联 VIEW_SELECT 在真实行上各列就位（走查1 补——25 参构造器首跑） ----

    @Test
    void pendingAssemblesViewSelectJoins() {
        List<?> raw = review.items(kbId, "pending", null, 10, 0);
        assertEquals(1, raw.size(), "一行待确认");
        var p = (tech.seexun.jknow.store.entity.PendingFactView) raw.get(0);
        assertEquals(pendingId, p.id());
        assertEquals(e1, p.subjectId());
        assertEquals("Acme", p.subjectName(), "主语名经 JOIN entities");
        assertEquals(relEmploys, p.predicateId());
        assertEquals("Employs", p.predicateLabel(), "谓词标签经 LEFT JOIN relation_types");
        assertEquals(e3, p.objectId());
        assertEquals("Beta", p.objectName());
        assertNull(p.objectValue(), "实体宾语 → object_value 空");
        assertEquals(java.time.Instant.parse("2023-01-01T00:00:00Z"), p.validFrom().toInstant(), "时点按 Instant 比");
        assertEquals("day", p.validFromPrecision());
        assertNull(p.validTo());
        assertEquals(0.6f, p.confidence());
        assertEquals(chunkLive, p.chunkId());
        assertEquals("Acme employs Beta since 2023", p.quote(), "quote=chunk 全文（JOIN chunks）");
        assertEquals(userId, p.proposedBy());
        assertEquals("Task4 RV", p.proposedByName(), "谁说的经 LEFT JOIN users");
        assertNull(p.proposedTokenName(), "无 token");
        assertEquals("employs", p.phrase(), "开放陈述（0044）");
        assertEquals("to", p.qualifiers().get(0).get("role").asText(), "qualifiers jsonb 透传");
        assertEquals("Beta", p.qualifiers().get(0).get("value").asText());
        assertEquals("since 2023", p.timeWords().get(0).get("text").asText(), "time_words jsonb 透传");
        assertEquals(0, p.quoteStart());
        assertEquals(4, p.quoteEnd());
        assertEquals(pendingId, review.pendingForChunk(kbId, chunkLive).get(0).id(),
                "for_chunk 同一 VIEW_SELECT 只换 WHERE");
    }

    // ---- duplicates：三档过滤 + sides 组装 + agent 建议挂卡 ----

    @Test
    void duplicatesAnyAssemblesSidesTopFactsAndProposal() {
        List<?> any = review.items(kbId, "duplicates", "any", 10, 0);
        assertEquals(2, any.size(), "any 不过滤");
        @SuppressWarnings("unchecked")
        List<ReviewItem> items = (List<ReviewItem>) any;
        // created_at DESC：R2（新）在前
        assertEquals(reviewConflict, items.get(0).id());
        assertEquals(reviewSame, items.get(1).id());
        ReviewItem same = items.get(1);
        assertEquals("Acme", same.left().name(), "左侧 e1");
        assertEquals("the other one", same.right().disambiguator(), "右侧 e2 消歧串");
        assertEquals("Company", same.left().typeLabel(), "类型标签来自 LEFT JOIN");
        assertEquals("#22c55e", same.left().color(), "侧摘要颜色直取类型色（coalesce 原样透传）");
        assertEquals(1, same.left().degree(), "e1 触达 F1 一条");
        assertTrue(same.left().topFacts().contains("Employs → Beta"), "top4 事实摘要（无区间 → 无括号）: "
                + same.left().topFacts());
        assertNotNull(same.proposal(), "R1 挂着 agent 建议");
        assertEquals("merge", same.proposal().action());
        assertEquals(0.9f, same.proposal().confidence());
        assertNull(items.get(0).proposal(), "R2 无建议 → null");
    }

    @Test
    void duplicatesTypeFilterNarrows() {
        assertEquals(1, review.items(kbId, "duplicates", "same", 10, 0).size());
        ReviewItem only = (ReviewItem) review.items(kbId, "duplicates", "same", 10, 0).get(0);
        assertEquals(reviewSame, only.id());
        assertEquals(reviewConflict,
                ((ReviewItem) review.items(kbId, "duplicates", "conflict", 10, 0).get(0)).id());
        // 认不出的 types 值当契约错误（resolution.rs:1644-1654）
        AppException e = assertThrowsInvalid("unknown_type_filter",
                () -> review.items(kbId, "duplicates", "bogus", 10, 0));
        assertTrue(e.getMessage().contains("types must be any, same or conflict, not bogus"));
    }

    // ---- conflicts：新旧事实双快照组装（走查1 补——13 参构造器首跑） ----

    @Test
    void conflictsAssembleOldNewFactSnapshots() {
        List<?> raw = review.items(kbId, "conflicts", null, 10, 0);
        assertEquals(1, raw.size(), "一条 open 冲突");
        var c = (tech.seexun.jknow.store.entity.ConflictView) raw.get(0);
        assertEquals(conflictId, c.id());
        assertEquals("simultaneous", c.reason());
        assertEquals("Employs", c.predicateLabel(), "谓词标签取旧事实的谓词（INNER JOIN）");
        assertEquals(factOld, c.oldFactId());
        assertEquals("Delta", c.oldSubject());
        assertEquals("Epsilon", c.oldObject());
        assertEquals(java.time.Instant.parse("2023-01-01T00:00:00Z"), c.oldValidFrom().toInstant());
        assertEquals(factNew, c.newFactId());
        assertEquals("Delta", c.newSubject(), "主语侧冲突变的是宾语");
        assertEquals("Zeta", c.newObject());
        assertEquals(java.time.Instant.parse("2026-06-01T00:00:00Z"), c.newValidFrom().toInstant());
        assertEquals(0.8f, c.newConfidence());
        assertNotNull(c.createdAt());
    }

    // ---- lowconf / unconfirmed ----

    @Test
    void lowconfOrdersByConfidenceAscending() {
        List<?> raw = review.items(kbId, "lowconf", null, 10, 0);
        @SuppressWarnings("unchecked")
        List<tech.seexun.jknow.store.entity.FactReviewItem> items = (List<tech.seexun.jknow.store.entity.FactReviewItem>) raw;
        assertEquals(2, items.size());
        assertEquals(0.3f, items.get(0).confidence(), "0.3 在前（升序）");
        assertEquals(0.5f, items.get(1).confidence());
        assertEquals("Acme", items.get(0).subjectName());
        assertEquals("Employs", items.get(0).predicateLabel(), "谓词标签");
        assertEquals("Beta", items.get(0).objectName(), "实体宾语名");
        assertEquals(1, items.get(0).evidenceCount());
        assertEquals("Acme employs Beta", items.get(0).quote(), "第一条证据引句");
    }

    @Test
    void unconfirmedPicksSupersededEvidenceFact() {
        @SuppressWarnings("unchecked")
        List<tech.seexun.jknow.store.entity.FactReviewItem> items =
                (List<tech.seexun.jknow.store.entity.FactReviewItem>) review.items(kbId, "unconfirmed", null, 10, 0);
        assertEquals(1, items.size());
        assertEquals(factLow2, items.get(0).id());
        assertEquals("stale quote", items.get(0).quote());
    }

    // ---- alignment：三合一混排 + 签名 + 内存分页 ----

    @Test
    void alignmentMergesThreeKindsSortedByDecidedAt() {
        List<?> raw = review.items(kbId, "alignment", null, 10, 0);
        assertEquals(3, raw.size(), "短语+类别词+规则");
        assertEquals(AlignmentItem.Phrase.class, raw.get(0).getClass(), "decided_at 升序：短语最老在前");
        assertEquals(AlignmentItem.KindWord.class, raw.get(1).getClass());
        assertEquals(AlignmentItem.Rule.class, raw.get(2).getClass());
        AlignmentItem.Phrase p = (AlignmentItem.Phrase) raw.get(0);
        assertEquals("phrase", p.kind(), "serde tag");
        assertEquals("acquired", p.phrase());
        assertEquals("company", p.subjectClass(), "两端类 key");
        assertEquals(3, p.statementCount());
        assertEquals(List.of("bought X", "took over Y"), p.examples());
        AlignmentItem.KindWord w = (AlignmentItem.KindWord) raw.get(1);
        assertEquals("kind_word", w.kind());
        assertEquals("company", w.kindWord());
        assertEquals(2, w.entityCount(), "签名按活实体数：Sig One/Sig Two");
        assertEquals(List.of("Sig One", "Sig Two"), w.examples(), "例名按创建先后前 3");
        assertEquals(List.of("Company"), w.words(), "写法来自 words 列");
        AlignmentItem.Rule r = (AlignmentItem.Rule) raw.get(2);
        assertEquals("rule", r.kind());
        assertEquals("employs", r.property());
        assertEquals("Employs", r.propertyLabel());
        // 内存分页（SQL 不带 LIMIT）：offset=1 → 类别词+规则
        assertEquals(2, review.items(kbId, "alignment", null, 10, 1).size());
        assertEquals(1, review.items(kbId, "alignment", null, 1, 0).size());
    }

    // ---- defects：双表标签 + 环标签序（走查1 补——7 参构造器首跑） ----

    @Test
    void defectsAssembleLabelsAndCyclePath() {
        List<?> raw = review.items(kbId, "defects", null, 10, 0);
        assertEquals(1, raw.size(), "一条 open 本体缺陷");
        var d = (tech.seexun.jknow.store.entity.OntologyDefect) raw.get(0);
        assertEquals(defectId, d.id());
        assertEquals("subclass_cycle", d.kind());
        assertEquals(2, d.detail().get("cycle_length").asInt(), "detail jsonb 透传");
        assertEquals("Company", d.subjectLabel(), "subject 同列指 entity_types：双 LEFT JOIN 取 label");
        assertEquals("Person", d.otherLabel());
        assertEquals(List.of("Company", "Person", "Company"), d.pathLabels(), "环上类标签按 path 序");
        assertNotNull(d.detectedAt());
    }

    // ---- errata / violations / merges / mappings / agent ----

    @Test
    void errataListsHeldWithDocumentName() {
        List<?> raw = review.items(kbId, "errata", null, 10, 0);
        assertEquals(1, raw.size());
        var item = (tech.seexun.jknow.store.entity.ErrataHeldItem) raw.get(0);
        assertEquals("retract", item.action());
        assertEquals("task4-rv.md", item.document(), "文档名来自 JOIN");
        assertEquals("the quote", item.quote());
    }

    @Test
    void violationsExpandTripleText() {
        List<?> raw = review.items(kbId, "violations", null, 10, 0);
        assertEquals(1, raw.size());
        var v = (tech.seexun.jknow.store.entity.AxiomViolation) raw.get(0);
        assertEquals("asymmetry", v.kind());
        assertEquals("Employs", v.predicate(), "判据来自 lf 的谓词");
        assertEquals("Acme · Employs · Beta", v.leftText(), "三元组文本在 SQL 里展开");
        assertTrue(v.rightText().startsWith("Acme · Employs · "), "右侧 e2 名相同");
        assertEquals(0, v.pathLen(), "非环违规 path 空");
        assertEquals(List.of(), v.path(), "jsonb_agg 空 → []");
        assertNull(v.hint(), "线索只对 derived_contradiction 给");
    }

    @Test
    void mergesAndMappingsAndAgentQueues() {
        var merges = (List<?>) review.items(kbId, "merges", null, 10, 0);
        assertEquals(1, merges.size());
        var m = (tech.seexun.jknow.store.entity.MergeLogView) merges.get(0);
        assertEquals("Merged Src", m.sourceName());
        assertEquals("Merged Dst", m.targetName());
        assertNull(m.mergedByName(), "LLM 自动合并 → NULL");

        var mappings = (List<?>) review.items(kbId, "mappings", null, 10, 0);
        assertEquals(1, mappings.size());
        var cm = (tech.seexun.jknow.store.entity.ConceptMapping) mappings.get(0);
        assertEquals("Acme", cm.conceptName());
        assertEquals("orders", cm.source());

        var agent = (List<?>) review.items(kbId, "agent", null, 10, 0);
        assertEquals(1, agent.size());
        var a = (tech.seexun.jknow.store.entity.AgentDecisionView) agent.get(0);
        assertEquals("merge", a.action());
        assertEquals("Acme", a.left(), "左右名经 resolution_reviews 联出");
        assertEquals("Acme", a.right());
    }

    // ---- history / summary ----

    @Test
    void historyReadsReviewDomainActions() {
        ReviewService.History h = review.history(kbId, 20, 0);
        assertEquals(1, h.total());
        assertEquals(1, h.events().size());
        AuditEventView e = h.events().get(0);
        assertEquals("review.keep", e.action());
        assertEquals("Task4 RV", e.actorName(), "操作人显示名");
    }

    @Test
    void summaryAggregatesWindowsWaitingAndHealth() {
        ReviewSummary s = review.summary(kbId);
        assertEquals(2, s.waiting().duplicates().count(), "waiting 与 counts 同口径");
        assertEquals(1, s.waiting().unconfirmed().count());
        assertNotNull(s.waiting().duplicates().oldestAt(), "最老在等（created_at）");
        assertEquals(1, s.decided().last30d().total(), "一笔 review.keep");
        assertEquals(0, s.decided().last30d().automatic(), "有人办的不算自动");
        assertEquals(1, s.decided().last30d().byAction().size());
        assertEquals("review.keep", s.decided().last30d().byAction().get(0).action());
        assertEquals(14, s.decided().daily().size(), "14 根柱");
        assertEquals(java.time.LocalDate.now(java.time.ZoneOffset.UTC),
                s.decided().daily().get(13).day(), "最后一根是今天");
        assertEquals(1, s.decided().daily().get(13).count(), "今天这一笔");
        assertEquals(4, s.health().facts(), "在世事实 F1+F2+冲突对两条");
        assertEquals(2, s.health().lowConfidence());
        assertEquals(1, s.health().unconfirmed());
        assertEquals(2, s.health().contested(), "被 open 冲突指到的在世事实两条（contested 数事实不数冲突行）");
        assertEquals(1, s.agent().open(), "开着的建议");
        assertEquals(1, s.agent().queue(), "等 agent 的对（R2）");
        assertFalse(s.agent().running());
        assertEquals(1, s.agent().last30d().proposed(), "状态行按现在的状态数");
    }

    // ---- 未知队列 ----

    @Test
    void unknownQueueIs422WithCode() {
        AppException e = assertThrowsInvalid("unknown_queue",
                () -> review.items(kbId, "no-such-queue", null, 10, 0));
        assertEquals("no review queue named no-such-queue", e.getMessage());
    }

    private static AppException assertThrowsInvalid(String code, Runnable run) {
        try {
            run.run();
        } catch (AppException e) {
            assertEquals(code, e.code().orElse(null), "code");
            return e;
        }
        throw new AssertionError("期望 AppException(" + code + ") 未抛出");
    }
}
