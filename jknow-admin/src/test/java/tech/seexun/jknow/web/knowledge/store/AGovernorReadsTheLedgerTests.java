package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GovernanceService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.entity.AgentDecisionView;
import tech.seexun.jknow.store.entity.Precedent;
import tech.seexun.jknow.store.entity.Precedents;
import tech.seexun.jknow.store.entity.ReviewItem;
import tech.seexun.jknow.store.entity.TypePairStats;

/**
 * 移植自 tests/store/a_governor_reads_the_ledger.rs（604 行）——治理先读台账（0025），
 * 打在真库上。要钉住的行为：
 *
 * <ul>
 * <li><b>先例只认人的</b>：同一对被人分开过两次算两条，机器自己合过的那条不算；
 *     这一对合过、这个名字对别的名字、这种类型对的习惯、涉及这个名字的撤回，四族各归各</li>
 * <li><b>闸门</b>：人分开过的对模型说合只建议；合过的对模型说合就动手；有撤回只建议；
 *     类型冲突不合；没有任何先例不合，但能分</li>
 * <li><b>队列先进先出</b>，队头带着同名或同实体的簇；写了建议的对不再进队列；
 *     人裁了一对，同簇的建议作废、那些对回到队列</li>
 * <li><b>回答</b>：一条建议只能答一次；开关关了的库不在定时扫描里</li>
 * </ul>
 *
 * <p>自建自拆，绝不碰已有的库。台账只增不删，种下的几行留在库里（kb 已删，kb_id
 * 悬空，本就允许）——与 Rust 原测试同口径。全部断言走 store 面（govern 任务本体属
 * 任务 4；'govern' 任务行由测试自己种）。
 */
class AGovernorReadsTheLedgerTests extends HeartStoreTestBase {

    @Autowired
    GovernanceService governance;
    @Autowired
    ResolutionService resolution;
    @Autowired
    ReviewService review;

    /** 种下的五对（同 Rust Seed）。 */
    private record Seed(UUID kb, UUID org, UUID user,
                        UUID zw12, UUID apple, UUID orion, UUID mercury, UUID zw23) {
    }

    private Seed s;

    @BeforeEach
    void seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID company = Uuids.nowV7();
        UUID user = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'governor-test')", org);
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'Governor Tester')
                """, user, org, "governor-" + user + "@test.local");
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'governor-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name, governance)"
                        + " VALUES (?, ?, 'governor-test', TRUE)", kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                person, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'org', 'Organization')",
                company, kb);

        UUID zw1 = entity(person, "Zhang Wei", kb);
        UUID zw2 = entity(person, "Zhang Wei", kb);
        UUID zw3 = entity(person, "Zhang Wei", kb);
        UUID apple1 = entity(company, "Apple", kb);
        UUID apple2 = entity(company, "Apple Inc.", kb);
        UUID orion1 = entity(company, "Orion", kb);
        UUID orion2 = entity(company, "Orion Labs", kb);
        UUID mercury1 = entity(company, "Mercury", kb);
        UUID mercury2 = entity(person, "Mercury", kb);

        // 等人的对，created_at 隔开一分钟：先进先出要看得出顺序
        OffsetDateTime t0 = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        long[] n = {0};
        UUID zw12 = review(kb, zw1, zw2, t0, n);
        UUID apple = review(kb, apple1, apple2, t0, n);
        UUID orion = review(kb, orion1, orion2, t0, n);
        UUID mercury = review(kb, mercury1, mercury2, t0, n);
        UUID zw23 = review(kb, zw2, zw3, t0, n);

        // 台账：人的决定。actor 为空的那条是机器裁的，不算先例
        ledger(kb, user, "review.keep", pair("Zhang Wei", "Zhang Wei"));
        ledger(kb, user, "review.keep", pair("Zhang Wei", "Zhang Wei"));
        ledger(kb, null, "review.merge", pair("Zhang Wei", "Zhang Wei"));
        ledger(kb, user, "review.merge", pair("Apple Inc.", "Apple"));
        ledger(kb, user, "review.keep", pair("Apple", "Apple Records"));
        ledger(kb, user, "merge.revert",
                "{\"source\": \"Orion\", \"target\": \"Orion Labs\"}");

        // 这种类型对的习惯：人裁过的 Person×Person 对，一合三分
        for (String status : new String[]{"merged", "kept", "kept", "kept"}) {
            UUID a = entity(person, "Someone", kb);
            UUID b = entity(person, "Someone", kb);
            jdbc.update("""
                    INSERT INTO resolution_reviews
                        (id, kb_id, left_id, right_id, score, stage, status, decided_at, decided_by)
                     VALUES (?, ?, ?, ?, 1.0, 'human', ?, now(), ?)
                    """, Uuids.nowV7(), kb, a, b, status, user);
        }

        s = new Seed(kb, org, user, zw12, apple, orion, mercury, zw23);
    }

    @AfterEach
    void teardown() {
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", s.kb());
        jdbc.update("DELETE FROM organizations WHERE id = ?", s.org());
    }

    private UUID entity(UUID typeId, String name, UUID kb) {
        UUID id = Uuids.nowV7();
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                id, kb, typeId, name);
        return id;
    }

    private UUID review(UUID kb, UUID l, UUID r, OffsetDateTime t0, long[] n) {
        UUID id = Uuids.nowV7();
        OffsetDateTime at = t0.plusMinutes(n[0]);
        n[0]++;
        jdbc.update("""
                INSERT INTO resolution_reviews
                    (id, kb_id, left_id, right_id, score, reason, stage, created_at)
                 VALUES (?, ?, ?, ?, 1.0, 'namesake', 'human', ?)
                """, id, kb, l, r, at);
        return id;
    }

    private void ledger(UUID kb, UUID actor, String action, String detail) {
        jdbc.update("""
                INSERT INTO audit_events (id, kb_id, actor_id, action, target_kind, detail)
                 VALUES (?, ?, ?, ?, 'review', ?::jsonb)
                """, Uuids.nowV7(), kb, actor, action, detail);
    }

    private static String pair(String l, String r) {
        return "{\"left\": \"" + l + "\", \"right\": \"" + r + "\", \"score\": 1.0}";
    }

    /** 随便一个张伟的 id：原文工具要一个实体。 */
    private UUID alikeId() {
        return jdbc.queryForObject(
                "SELECT id FROM entities WHERE kb_id = ? AND canonical_name = 'Zhang Wei' LIMIT 1",
                UUID.class, s.kb());
    }

    private ReviewItem byId(List<ReviewItem> items, UUID id) {
        return items.stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aGovernorReadsTheLedger() {
        // 先进先出
        List<ReviewItem> q = governance.queue(s.kb(), 10);
        assertEquals(List.of(s.zw12(), s.apple(), s.orion(), s.mercury(), s.zw23()),
                q.stream().map(ReviewItem::id).toList());
        List<ReviewItem> queueSnapshot = List.copyOf(q);

        // 队头的簇：同名同实体的张伟 2 对 3
        List<ReviewItem> cluster = governance.clusterOf(s.kb(), byId(queueSnapshot, s.zw12()), 11);
        assertEquals(List.of(s.zw23()), cluster.stream().map(ReviewItem::id).toList());

        // 四族先例
        Precedents p = governance.precedentsFor(s.kb(), byId(queueSnapshot, s.zw12()));
        assertEquals(2, p.samePair().size(), "人分开过两次；机器合过的那条不算");
        assertTrue(p.samePair().stream().noneMatch(Precedent::merged));
        assertTrue(p.sameName().isEmpty());
        assertTrue(p.reverts().isEmpty());
        assertNotNull(p.typePair(), "两边都有类型");
        assertEquals(new TypePairStats(1, 3, 0), p.typePair());
        assertEquals(GovernanceService.Gate.APPLY,
                GovernanceService.gate(false, 0.9f, false,
                        GovernanceService.NameShape.UNRELATED, p));
        assertEquals(GovernanceService.Gate.PROPOSE,
                GovernanceService.gate(true, 0.99f, false,
                        GovernanceService.NameShape.UNRELATED, p),
                "人分开过，模型说合，只建议");

        p = governance.precedentsFor(s.kb(), byId(queueSnapshot, s.apple()));
        assertEquals(1, p.samePair().size(), "左右对调也算这一对");
        assertTrue(p.samePair().get(0).merged());
        assertEquals(1, p.sameName().size(), "Apple 对 Apple Records 是这个名字对别的名字");
        assertEquals(GovernanceService.Gate.APPLY,
                GovernanceService.gate(true, 0.9f, false,
                        GovernanceService.NameShape.UNRELATED, p));
        assertEquals(GovernanceService.Gate.PROPOSE,
                GovernanceService.gate(true, 0.7f, false,
                        GovernanceService.NameShape.UNRELATED, p));
        List<String> lines = GovernanceService.renderLines(p);
        assertTrue(lines.get(0).startsWith("this same pair was merged by a person on "));
        assertTrue(lines.stream().anyMatch(
                l -> l.contains("\"Apple\" against \"Apple Records\" was kept apart")));

        p = governance.precedentsFor(s.kb(), byId(queueSnapshot, s.orion()));
        assertEquals(1, p.reverts().size());
        assertEquals(GovernanceService.Gate.PROPOSE,
                GovernanceService.gate(true, 0.99f, false,
                        GovernanceService.NameShape.UNRELATED, p));
        assertEquals(GovernanceService.Gate.PROPOSE,
                GovernanceService.gate(false, 0.99f, false,
                        GovernanceService.NameShape.UNRELATED, p));

        ReviewItem m = byId(queueSnapshot, s.mercury());
        p = governance.precedentsFor(s.kb(), m);
        assertNotNull(p.typePair());
        assertEquals(GovernanceService.Gate.PROPOSE,
                GovernanceService.gate(true, 0.99f, true,
                        GovernanceService.NameShape.UNRELATED, p));
        assertEquals(GovernanceService.Gate.APPLY,
                GovernanceService.gate(false, 0.9f, true,
                        GovernanceService.NameShape.UNRELATED, p));

        // 写了建议的对不再进队列
        UUID runId = Uuids.nowV7();
        UUID dApple = governance.record(s.kb(), new GovernanceService.NewDecision(
                runId, s.apple(), "merge", 0.7f, "the same pair was merged before",
                GovernanceService.precedentsJson(p), "proposed", null, null,
                json("[]"), 0));
        UUID dZw23 = governance.record(s.kb(), new GovernanceService.NewDecision(
                runId, s.zw23(), "keep", 0.6f, null, json("[]"), "proposed", null, null,
                json("[]"), 0));
        assertEquals(List.of(s.zw12(), s.orion(), s.mercury()),
                governance.queue(s.kb(), 10).stream().map(ReviewItem::id).toList());
        assertEquals(2, governance.countProposed(s.kb()));
        assertEquals(2, review.counts(s.kb()).agent());
        List<AgentDecisionView> listed = governance.list(s.kb(), 10, 0);
        assertEquals(2, listed.size(), "最新的在前");
        assertEquals(dZw23, listed.get(0).id());
        assertEquals("Apple", listed.get(1).left());
        assertEquals("Apple Inc.", listed.get(1).right());
        UUID finalApple = s.apple();
        UUID finalRunId = runId;
        assertThrows(DataIntegrityViolationException.class, () -> governance.record(s.kb(),
                new GovernanceService.NewDecision(finalRunId, finalApple, "keep", 0.5f, null,
                        json("[]"), "proposed", null, null, json("[]"), 0)),
                "一个目标同时只有一条开着的建议");

        // 人裁了张伟 1 对 2：同簇的张伟 2 对 3 的建议作废、回到队列；Apple 的不受影响
        resolution.decideReview(s.kb(), s.zw12(), "keep", s.user(), null);
        // 同一对实体人裁过：不分左右；别的对没有
        ReviewItem zw = byId(queueSnapshot, s.zw12());
        assertEquals(Boolean.FALSE, governance.decidedBefore(s.kb(), zw.left().id(), zw.right().id()));
        assertEquals(Boolean.FALSE, governance.decidedBefore(s.kb(), zw.right().id(), zw.left().id()));
        ReviewItem ap = byId(queueSnapshot, s.apple());
        assertNull(governance.decidedBefore(s.kb(), ap.left().id(), ap.right().id()));
        assertEquals(1, governance.supersedeSiblings(s.kb(), s.zw12()));
        assertEquals("superseded", governance.get(s.kb(), dZw23).status());
        assertEquals("proposed", governance.get(s.kb(), dApple).status());
        assertEquals(List.of(s.orion(), s.mercury(), s.zw23()),
                governance.queue(s.kb(), 10).stream().map(ReviewItem::id).toList());
        // 张伟 2 对 3 的同对先例还是种下的两条：decide_review 本身不写台账，台账由路由层记
        p = governance.precedentsFor(s.kb(), byId(queueSnapshot, s.zw23()));
        assertEquals(2, p.samePair().size());

        // 回答一条建议：答过就不能再答
        governance.settle(s.kb(), dApple, "accepted", s.user());
        AgentDecisionView d = governance.get(s.kb(), dApple);
        assertEquals("accepted", d.status());
        assertEquals("Governor Tester", d.decidedByName());
        assertThrows(AppException.class,
                () -> governance.settle(s.kb(), dApple, "overridden", s.user()));

        // 第二刀的工具：翻台账、找同名、数今天花的调用
        List<Precedent> hits = governance.ledgerSearch(s.kb(), "apple", 10);
        assertEquals(2, hits.size(), "Apple Inc. 的合并与 Apple Records 的分开都含 apple");
        assertTrue(governance.ledgerSearch(s.kb(), "zhang", 10).stream()
                .allMatch(x -> "review.keep".equals(x.action())), "机器合的那条不算");
        List<tech.seexun.jknow.store.entity.Namesake> alike =
                governance.namesakes(s.kb(), "zhang wei", 10);
        assertEquals(3, alike.size(), "三个张伟");
        assertTrue(alike.stream().allMatch(
                x -> "Person".equals(x.typeLabel()) && !x.merged()));
        assertTrue(governance.quotesOf(s.kb(), alikeId(), 6).isEmpty(), "没有事实就没有原文");
        UUID looked = governance.record(s.kb(), new GovernanceService.NewDecision(
                runId, s.mercury(), "unsure", 0.0f, null, json("[]"), "proposed", null,
                "Is the Mercury that filed the 2024 report the company or the person?",
                json("[{ \"tool\": \"facts\", \"args\": { \"side\": \"A\" }, \"note\": \"0 facts of A\" }]"),
                3));
        d = governance.get(s.kb(), looked);
        assertEquals(3, d.calls());
        assertTrue(d.question().startsWith("Is the Mercury"));
        assertEquals("facts", d.trace().get(0).get("tool").asText());
        assertEquals(3, governance.loopCallsToday(s.kb()));

        // 保险丝（第四刀）：撤回的次数从某一刻数起；跳闸只在开着时发生、只发生一次；
        // 合并历史里撤回 agent 的合并，那一行也记成 reverted
        UUID a = alikeId();
        UUID b = jdbc.queryForObject(
                "SELECT id FROM entities WHERE kb_id = ? AND canonical_name = 'Someone' LIMIT 1",
                UUID.class, s.kb());
        UUID mergeId = resolution.mergeEntities(s.kb(), a, b, null, "governed|0.90");
        UUID applied = governance.record(s.kb(), new GovernanceService.NewDecision(
                runId, s.orion(), "merge", 0.9f, null, json("[]"), "applied", mergeId, null,
                json("[]"), 0));
        OffsetDateTime anHourAgo = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        assertEquals(0, governance.revertsSince(s.kb(), anHourAgo));
        assertNull(governance.settleByMerge(s.kb(), Uuids.nowV7(), s.user()),
                "不是 agent 的合并：没有行可记");
        assertEquals(applied, governance.settleByMerge(s.kb(), mergeId, s.user()));
        assertEquals("reverted", governance.get(s.kb(), applied).status());
        assertEquals(1, governance.revertsSince(s.kb(), anHourAgo));
        assertEquals(0, governance.revertsSince(s.kb(),
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)), "只数那一刻之后的");
        assertTrue(governance.trip(s.kb()), "开着：关了");
        assertFalse(governance.trip(s.kb()), "已经关了：不再算跳闸");
        jdbc.update("UPDATE knowledge_bases SET governance = TRUE WHERE id = ?", s.kb());

        // agent 正在裁的一簇锁住（0025 第五刀）：任务在跑 + 标着 adjudicating + 开关开着，
        // 三样齐了人才裁不了；任务一停，标记只是标记
        jdbc.update(
                "INSERT INTO jobs (kind, payload, status) VALUES ('govern', ?::jsonb, 'running')",
                "{\"kb_id\": \"" + s.kb() + "\"}");
        assertTrue(governance.agentRunning(s.kb()));
        ReviewItem mercury = byId(queueSnapshot, s.mercury());
        governance.lock(s.kb(), List.of(mercury.id()));
        assertTrue(governance.lockedByAgent(s.kb(), mercury.id()));
        UUID mercuryId = mercury.id();
        UUID user = s.user();
        assertThrows(AppException.class,
                () -> resolution.decideReview(s.kb(), mercuryId, "keep", user, null),
                "agent 正在裁的对，人裁不了");
        assertEquals(1, governance.releaseLocks(s.kb()));
        assertFalse(governance.lockedByAgent(s.kb(), mercury.id()));
        jdbc.update("DELETE FROM jobs WHERE kind = 'govern' AND payload->>'kb_id' = ?",
                s.kb().toString());
        assertFalse(governance.agentRunning(s.kb()));
        assertEquals(3, governance.queueLen(s.kb()), "orion、mercury、zw23 等 agent 看");

        // 定时扫描：开关开着且有没看过的对 → 在；关了 → 不在
        assertTrue(governance.due().contains(s.kb()));
        jdbc.update("UPDATE knowledge_bases SET governance = FALSE WHERE id = ?", s.kb());
        assertFalse(governance.due().contains(s.kb()));
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
