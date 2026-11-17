package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.TypeBindingsService.Acceptance;
import tech.seexun.jknow.store.TypeBindingsService.ClassSnapshot;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.Binding;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.KindWordSignature;

/**
 * 移植自 tests/store/a_kind_word_binds_to_a_class.rs（877 行，4 例）——一个类别词绑到
 * 一个类（0044 决定 3–4 的第一片，账本侧）。「Company」与「company」是一个词；绑定判了
 * 之后写到该类别词下每个实体的 type_id（type_source='aligned'），人定过的不动；过期按
 * 类的 updated_at 与库里最新的类判；人的判定不被代理覆盖；解绑只动 aligned 的行。
 */
class AKindWordBindsToAClassTests extends HeartStoreTestBase {

    private static final String ORG = "kind-word-binding-test";
    private static final String PROPOSAL = "the stockholder proposal on declassifying the board";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TypeBindingsService typeBindings;
    @Autowired
    OntologyService ontology;
    @Autowired
    GraphWriteService writes;

    record Fixture(UUID org, UUID kb, UUID organization, UUID person, UUID acme, UUID beta,
                   UUID carol, UUID dave, UUID proposal) {
    }

    record Typed(UUID typeId, String typeSource, String proposedType) {
    }

    private Typed typed(UUID id) {
        return jdbc.queryForObject(
                "SELECT type_id, type_source, proposed_type FROM entities WHERE id = ?",
                (rs, i) -> new Typed(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
                id);
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID organization = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID beta = Uuids.nowV7();
        UUID carol = Uuids.nowV7();
        UUID dave = Uuids.nowV7();
        UUID proposal = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, ORG);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org, ORG);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)", kb, ws, ORG);
        for (Object[] row : new Object[][]{{organization, "organization", "Organization"},
                {person, "person", "Person"}}) {
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        // 有名字的：类别词照文档的写法，类空着
        for (Object[] row : new Object[][]{{acme, "Acme", "Company"}, {beta, "Beta", "company"},
                {carol, "Carol", "person"}}) {
            jdbc.update("""
                    INSERT INTO entities (id, kb_id, canonical_name, specific_type)
                     VALUES (?, ?, ?, ?)
                    """, row[0], kb, row[1], row[2]);
        }
        // 人看过、说没有合适的类：type_id 空、type_source = human
        jdbc.update("""
                INSERT INTO entities (id, kb_id, canonical_name, specific_type, type_source)
                 VALUES (?, ?, 'Dave', 'person', 'human')
                """, dave, kb);
        // 一个被描述、没有名字的东西
        jdbc.update("""
                INSERT INTO entities (id, kb_id, canonical_name, description, specific_type)
                 VALUES (?, ?, ?, ?, 'stockholder proposal')
                """, proposal, kb, PROPOSAL, PROPOSAL);
        // 几条开放陈述：主语是公司的
        writes.insertOpenStatement(kb, acme, "acquired", beta, null, null, 0.9f);
        writes.insertOpenStatement(kb, acme, "acquired", null,
                JsonNodeFactory.instance.objectNode().put("value", "a rival"), null, 0.9f);
        writes.insertOpenStatement(kb, beta, "is headquartered in", null,
                JsonNodeFactory.instance.objectNode().put("value", "Austin"), null, 0.9f);
        return new Fixture(org, kb, organization, person, acme, beta, carol, dave, proposal);
    }

    private JsonNodeFactory json() {
        return JsonNodeFactory.instance;
    }

    /** 两次 now() 要分得开：过期靠时间戳的先后。 */
    private static void tick() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aKindWordIsCountedOnceBoundOnceAndAppliedToItsEntities() {
        jdbc.update("DELETE FROM organizations WHERE name = ?", ORG);
        Fixture f = seed();
        try {
            // 1. 签名："Company" 与 "company" 是一个词
            List<KindWordSignature> sigs = typeBindings.signatures(f.kb());
            assertEquals(List.of("company", "person", "stockholder proposal"),
                    sigs.stream().map(KindWordSignature::kindWord).toList(),
                    "按实体数再按词序");
            KindWordSignature company = sigs.get(0);
            assertEquals(2L, company.count());
            assertEquals(Set.of("Company", "company"), new HashSet<>(company.words()),
                    "两种写法都留着");
            assertEquals(Set.of("Acme", "Beta"), new HashSet<>(company.examples()));
            assertEquals(List.of("acquired", "is headquartered in"), company.phrases(),
                    "以公司为主语的开放陈述的短语，按次数");
            KindWordSignature proposal = sigs.get(2);
            assertEquals(1L, proposal.count());
            assertEquals(List.of("stockholder proposal"), proposal.words());
            assertEquals(List.of(PROPOSAL), proposal.examples(), "没有名字的以描述为例");
            assertTrue(proposal.phrases().isEmpty());
            assertEquals(2L, sigs.get(1).count(), "Carol 与 Dave");

            // 2. 绑上并写到实体上：两个公司都成了 organization，人定过的不动
            assertTrue(typeBindings.decide(f.kb(), "company", company.words(), f.organization(),
                    "bound", json().objectNode().set("votes",
                            json().arrayNode().add("organization").add("organization")), "agent"));
            assertEquals(2, typeBindings.applyToEntities(f.kb(), "company", f.organization()));
            for (UUID id : new UUID[]{f.acme(), f.beta()}) {
                assertEquals(new Typed(f.organization(), "aligned", null), typed(id));
            }
            assertEquals(0, typeBindings.applyToEntities(f.kb(), "company", f.organization()),
                    "再写一次没有改动");
            assertTrue(typeBindings.decide(f.kb(), "Person", List.of(), f.person(), "bound",
                    json().objectNode(), "agent"), "写法归一后是同一个词");
            assertEquals(1, typeBindings.applyToEntities(f.kb(), "person", f.person()),
                    "只有 Carol；Dave 是人定的");
            assertEquals(f.person(), typed(f.carol()).typeId());
            assertEquals(new Typed(null, "human", null), typed(f.dave()), "人说没有类，就没有类");

            // 3. 抽取时用的表
            var bound = typeBindings.boundMap(f.kb());
            assertEquals(f.organization(), bound.get("company"));
            assertEquals(f.person(), bound.get("person"));
            assertEquals(2, bound.size());

            // 4. 过期：绑到的类改了
            assertTrue(typeBindings.stale(f.kb()).isEmpty());
            tick();
            ontology.updateEntityType(f.kb(), f.organization(), "Organisation", null, "square",
                    List.of(), "a company or any other body");
            assertEquals(List.of("company"), typeBindings.stale(f.kb()));
            tick();
            assertTrue(typeBindings.decide(f.kb(), "company", company.words(), f.organization(),
                    "bound", json().objectNode().set("votes",
                            json().arrayNode().add("organization").add("organization")), "agent"),
                    "代理可以改代理的");
            assertTrue(typeBindings.stale(f.kb()).isEmpty());

            // 5. 没有类对得上：记 none，按老流程提成建议加类
            assertTrue(typeBindings.decide(f.kb(), "stockholder proposal", proposal.words(), null,
                    "none", json().objectNode().set("votes",
                            json().arrayNode().addNull().addNull()), "agent"));
            assertEquals(1, typeBindings.propose(f.kb(), "stockholder proposal",
                    "stockholder proposal"));
            assertEquals("stockholder proposal", typed(f.proposal()).proposedType());
            assertEquals(0, typeBindings.propose(f.kb(), "stockholder proposal",
                    "Stockholder Proposal"), "只写第一次，与 set_proposed_type 同一条");
            // 绑定的形状：绑上了就得有类
            assertThrows(AppException.class, () -> typeBindings.decide(f.kb(), "thing", List.of(),
                    null, "bound", json().objectNode(), "agent"));

            // 6. 过期：判成 none 之后库里长出了新类
            assertTrue(typeBindings.stale(f.kb()).isEmpty());
            tick();
            jdbc.update(
                    "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'proposal', 'Proposal')",
                    Uuids.nowV7(), f.kb());
            assertEquals(List.of("stockholder proposal"), typeBindings.stale(f.kb()),
                    "绑上的不受新类影响，none 的要重判");
            tick();
            assertTrue(typeBindings.decide(f.kb(), "stockholder proposal", List.of(), null, "none",
                    json().objectNode().set("votes", json().arrayNode().addNull().addNull()),
                    "agent"));
            assertTrue(typeBindings.stale(f.kb()).isEmpty());

            // 7. 人的判定不被代理覆盖，反过来可以
            assertTrue(typeBindings.decide(f.kb(), "company", List.of(), f.organization(),
                    "bound", json().objectNode().put("by", "a person"), "person"));
            assertFalse(typeBindings.decide(f.kb(), "company", List.of(), null, "none",
                    json().objectNode(), "agent"), "代理改不了人的");
            List<Binding> b = typeBindings.bindings(f.kb());
            Binding companyB = b.stream().filter(x -> x.kindWord().equals("company")).findFirst().orElseThrow();
            assertEquals("bound", companyB.status());
            assertEquals(f.organization(), companyB.typeId());
            assertEquals("person", companyB.decidedBy());
            assertEquals(List.of("company", "person", "stockholder proposal"),
                    b.stream().map(Binding::kindWord).toList());
            assertTrue(typeBindings.decide(f.kb(), "company", List.of(), null, "none",
                    json().objectNode(), "person"), "人可以改人的");
            Binding after = typeBindings.bindings(f.kb()).stream()
                    .filter(x -> x.kindWord().equals("company")).findFirst().orElseThrow();
            assertEquals("none", after.status());
            assertNull(after.typeId());
            assertFalse(typeBindings.boundMap(f.kb()).containsKey("company"));

            // 8. 解绑只动 aligned 的行
            jdbc.update("UPDATE entities SET type_source = 'human' WHERE id = ?", f.beta());
            assertEquals(1, typeBindings.unapplyFromEntities(f.kb(), "company"),
                    "Beta 已被人认下，留着");
            assertEquals(new Typed(null, "extracted", null), typed(f.acme()));
            assertEquals(f.organization(), typed(f.beta()).typeId());
            assertEquals(f.person(), typed(f.carol()).typeId());
            assertEquals(0, typeBindings.unapplyFromEntities(f.kb(), "company"));
            // 绑上的类之后有了 → 对齐时把建议清掉
            assertEquals(1, typeBindings.applyToEntities(f.kb(), "stockholder proposal",
                    f.organization()));
            assertNull(typed(f.proposal()).proposedType());

            // 9. 类删了，绑定跟着走
            assertEquals(1, typeBindings.unapplyFromEntities(f.kb(), "person"));
            jdbc.update("DELETE FROM entity_types WHERE id = ?", f.person());
            assertTrue(typeBindings.bindings(f.kb()).stream()
                    .noneMatch(x -> x.kindWord().equals("person")));
            assertTrue(typeBindings.stale(f.kb()).isEmpty());
        } finally {
            jdbc.update("DELETE FROM organizations WHERE name = ?", ORG);
        }
    }

    /** 自己的组织名：上面那个测试开跑时按名字清场，共用名字会互相删掉对方的数据。 */
    private static final String BASIS_ORG = "kind-word-basis-test";

    /** 代理的判定只在它读到的输入还成立时才收（#795）。 */
    @Test
    void anAgentDecisionIsAcceptedOnlyForTheInputsItRead() {
        jdbc.update("DELETE FROM organizations WHERE name = ?", BASIS_ORG);
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID clazz = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, BASIS_ORG);
        try {
            jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org,
                    BASIS_ORG);
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)",
                    kb, ws, BASIS_ORG);
            jdbc.update("""
                    INSERT INTO entity_types (id, kb_id, key, label, description)
                     VALUES (?, ?, 'organization', 'Organization', 'OLD definition')
                    """, clazz, kb);
            jdbc.update("""
                    INSERT INTO entities (id, kb_id, canonical_name, specific_type)
                     VALUES (?, ?, 'Acme', 'company')
                    """, acme, kb);
            List<UUID> shown = List.of(clazz);
            String before = typeBindings.classSnapshot(kb).basis(shown);

            // 模型答题期间定义改了：这份回复答的是旧定义，不收，什么都不写
            jdbc.update("""
                    UPDATE entity_types SET description = 'NEW definition', updated_at = clock_timestamp()
                      WHERE id = ?
                    """, clazz);
            var votes = json().objectNode().put("first", "organization").put("second", "organization");
            assertEquals(Acceptance.MOVED, typeBindings.decideAndApplyIfCurrent(kb, "company",
                    List.of(), clazz, "bound", votes, before, shown));
            assertTrue(typeBindings.bindings(kb).isEmpty());
            assertNull(typed(acme).typeId());

            // 按新输入问出来的：收下，指纹一起存
            String current = typeBindings.classSnapshot(kb).basis(shown);
            assertFalse(before.equals(current));
            assertEquals(Acceptance.WRITTEN, typeBindings.decideAndApplyIfCurrent(kb, "company",
                    List.of(), clazz, "bound", votes, current, shown));
            Binding b = typeBindings.bindings(kb).get(0);
            assertEquals(current, b.basis());
            assertEquals(clazz, typed(acme).typeId());

            // 人判的：不带指纹；它的短语对齐任务与判定一起提交
            assertTrue(typeBindings.decideAndApplyHuman(kb, "company", null, votes));
            Binding human = typeBindings.bindings(kb).get(0);
            assertEquals("person", human.decidedBy());
            assertNull(human.basis());
            assertEquals(1L, jdbc.queryForObject("""
                    SELECT count(*) FROM jobs
                      WHERE kind = 'align_phrases' AND payload->>'kb_id' = ? AND status = 'queued'
                    """, Long.class, kb.toString()), "判定与 align_phrases 任务同事务");

            // 代理晚到的回复不覆盖人
            assertEquals(Acceptance.KEPT_PERSON, typeBindings.decideAndApplyIfCurrent(kb,
                    "company", List.of(), clazz, "bound", votes, current, shown));
            assertEquals("person", typeBindings.bindings(kb).get(0).decidedBy());
        } finally {
            jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", kb.toString());
            jdbc.update("DELETE FROM organizations WHERE name = ?", BASIS_ORG);
        }
    }

    /** 锁顺序的两测共用：一个类、一个带类别词的实体、一行绑在这个类上的代理判定。 */
    private UUID[] seedLockOrder(String name) {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID clazz = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, name);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org, name);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)", kb, ws, name);
        jdbc.update(
                "INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'organization', 'Organization')",
                clazz, kb);
        jdbc.update("""
                INSERT INTO entities (id, kb_id, canonical_name, specific_type)
                 VALUES (?, ?, 'Acme', 'company')
                """, Uuids.nowV7(), kb);
        typeBindings.decide(kb, "company", List.of(), clazz, "bound", JsonNodeFactory.instance.objectNode(),
                "agent");
        return new UUID[]{kb, clazz, org};
    }

    /** 等到有人排在 pid 后面等锁；chain 时等的是更长的一串。 */
    private void waitBehind(UUID kb, int pid, boolean chain) throws Exception {
        String sql = chain
                ? """
                  SELECT EXISTS (SELECT 1 FROM pg_stat_activity p
                                 WHERE EXISTS (SELECT 1 FROM unnest(pg_blocking_pids(p.pid)) b(pid)
                                                WHERE ? = ANY(pg_blocking_pids(b.pid))))"""
                : "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))";
        long deadline = System.currentTimeMillis() + 10_000;
        while (!Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, pid))) {
            assertTrue(System.currentTimeMillis() < deadline, "nobody waited behind " + pid);
            Thread.sleep(10);
        }
    }

    /**
     * 代理收判定时先锁候选类、再写判定行，与删类的顺序一致。反过来就是死锁。这里让代理
     * 停在两步之间，删类排到它后面，再放行：两边都得走完，谁也不报 40P01。
     */
    @Test
    void anAcceptanceAndAClassDeleteWaitForEachOtherInsteadOfDeadlocking() throws Exception {
        final String name = "kind-word-lock-order-test";
        jdbc.update("DELETE FROM organizations WHERE name = ?", name);
        UUID[] seed = seedLockOrder(name);
        UUID kb = seed[0];
        UUID clazz = seed[1];
        UUID org = seed[2];
        try {
            List<UUID> shown = List.of(clazz);
            String basis = typeBindings.classSnapshot(kb).basis(shown);
            // 闸门拿着判定行：代理锁完候选类、算完指纹，停在写判定这一步
            try (java.sql.Connection gate = java.sql.DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                gate.setAutoCommit(false);
                int gatePid;
                try (var rs = gate.createStatement().executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    gatePid = rs.getInt(1);
                }
                try (var ps = gate.prepareStatement(
                        "SELECT id FROM type_bindings WHERE kb_id = ? FOR SHARE")) {
                    ps.setObject(1, kb);
                    ps.executeQuery();
                }
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    AtomicReference<Acceptance> accepted = new AtomicReference<>();
                    Future<?> agent = pool.submit(() -> accepted.set(
                            typeBindings.decideAndApplyIfCurrent(kb, "company", List.of(), null,
                                    "undecided", JsonNodeFactory.instance.objectNode(), basis, shown)));
                    waitBehind(kb, gatePid, false);
                    Future<?> deleter = pool.submit(() ->
                            jdbc.update("DELETE FROM entity_types WHERE id = ?", clazz));
                    // 删类排在代理后面（代理拿着类的共享锁），代理排在闸门后面
                    waitBehind(kb, gatePid, true);
                    gate.rollback();
                    agent.get(10, TimeUnit.SECONDS);
                    deleter.get(10, TimeUnit.SECONDS);
                    assertEquals(Acceptance.WRITTEN, accepted.get());
                    List<Binding> b = typeBindings.bindings(kb);
                    assertEquals(1, b.size(), "the undecided row no longer points at the class");
                    assertEquals("undecided", b.get(0).status());
                    assertNull(b.get(0).typeId());
                    assertEquals(0L, jdbc.queryForObject(
                            "SELECT count(*) FROM entity_types WHERE kb_id = ?", Long.class, kb),
                            "the delete went through after the agent committed");
                } finally {
                    pool.shutdownNow();
                }
            }
        } finally {
            jdbc.update("DELETE FROM organizations WHERE name = ?", name);
        }
    }

    /** 候选类在收判定之前被删了：指纹对不上，这份回复不收；判定行随级联走了。 */
    @Test
    void aCandidateDeletedWhileTheReplyWaitsMovesIt() throws Exception {
        final String name = "kind-word-deleted-candidate-test";
        jdbc.update("DELETE FROM organizations WHERE name = ?", name);
        UUID[] seed = seedLockOrder(name);
        UUID kb = seed[0];
        UUID clazz = seed[1];
        try {
            List<UUID> shown = List.of(clazz);
            String basis = typeBindings.classSnapshot(kb).basis(shown);
            try (java.sql.Connection deleter = java.sql.DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                deleter.setAutoCommit(false);
                int deleterPid;
                try (var rs = deleter.createStatement().executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    deleterPid = rs.getInt(1);
                }
                try (var ps = deleter.prepareStatement(
                        "DELETE FROM entity_types WHERE id = ?")) {
                    ps.setObject(1, clazz);
                    ps.executeUpdate();
                }
                ExecutorService pool = Executors.newFixedThreadPool(1);
                try {
                    AtomicReference<Acceptance> accepted = new AtomicReference<>();
                    Future<?> agent = pool.submit(() -> accepted.set(
                            typeBindings.decideAndApplyIfCurrent(kb, "company", List.of(), null,
                                    "none", JsonNodeFactory.instance.objectNode(), basis, shown)));
                    waitBehind(kb, deleterPid, false);
                    deleter.commit();
                    agent.get(10, TimeUnit.SECONDS);
                    assertEquals(Acceptance.MOVED, accepted.get());
                    assertTrue(typeBindings.bindings(kb).isEmpty(),
                            "the cascade took the row and the moved reply did not write it back");
                } finally {
                    pool.shutdownNow();
                }
            }
        } finally {
            jdbc.update("DELETE FROM organizations WHERE name = ?", name);
        }
    }
}
