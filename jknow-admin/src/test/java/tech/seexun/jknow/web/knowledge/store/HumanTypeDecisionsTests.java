package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 人拍过板的类型，引擎不许改——打在真库上（human_type_decisions.rs 四例断言不改写）。
 *
 * <p>这一整条防线活在 SQL 的 WHERE 子句里：三处读侧各加一句 {@code type_source <> 'human'}，
 * 漏一处就是静默失效。0009 刚在同一片区域踩过 NULL &lt;&gt; uuid 的坑——类型系统数得清
 * Rust，数不到 SQL 里去。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HumanTypeDecisionsTests extends StoreTestBase {

    @Autowired
    ResolutionService resolution;

    private static final class Fx {
        final UUID org;
        final UUID kb;
        final UUID orgType;
        final UUID subType;

        Fx(UUID org, UUID kb, UUID orgType, UUID subType) {
            this.org = org;
            this.kb = kb;
            this.orgType = orgType;
            this.subType = subType;
        }
    }

    /** 造一个刚好能触发「第三种取材条件」的本体：organization 有子类 startup。 */
    private Fx seed() {
        UUID org = UUID.randomUUID();
        UUID ws = UUID.randomUUID();
        UUID kb = UUID.randomUUID();
        UUID orgType = UUID.randomUUID();
        UUID subType = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'p4a-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'p4a-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'p4a-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'organization', 'organization')",
                orgType, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'startup', 'startup')",
                subType, kb);
        jdbc.update("INSERT INTO entity_type_parents (child_id, parent_id) VALUES (?, ?)", subType,
                orgType);
        return new Fx(org, kb, orgType, subType);
    }

    private UUID entity(Fx f, String name, UUID typeId, String source) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name, type_source) "
                        + "VALUES (?, ?, ?, ?, ?)", id, f.kb, typeId, name, source);
        return id;
    }

    private String sourceOf(UUID id) {
        return jdbc.queryForObject("SELECT type_source FROM entities WHERE id = ?", String.class, id);
    }

    private Set<UUID> picked(Fx f) {
        return new HashSet<>(resolution.entitiesForTypeResolution(f.kb, 100, false)
                .stream().map(tech.seexun.jknow.store.mapper.ResolutionMapper.TypeCandidateSubject::id)
                .toList());
    }

    /** 主战场：取材条件里的「现类还有子类就纳入」会把人拍过板的实体一并捞回来。 */
    @Test
    void typeResolutionLeavesHumanDecisionsAlone() {
        Fx f = seed();
        UUID byHuman = entity(f, "Acme", f.orgType, "human");
        UUID byEngine = entity(f, "Globex", f.orgType, "extracted");
        try {
            Set<UUID> picked = picked(f);
            assertTrue(picked.contains(byEngine),
                    "抽取定的类型该被重判——organization 有子类 startup，这正是消解的用武之地");
            assertFalse(picked.contains(byHuman), "人拍过板的不该被拿去重判");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb);
        }
    }

    /**
     * 0009 × P4 的交叉，最容易漏的一条：0009 之后「没有类型」可能是人的决定——他看过
     * 这个实体，认为本体里没有合适的类。抽取升格的守卫只看 type_key.is_none() 分不出
     * 「还没判」和「人判了，就是没有」，于是下一次抽取会给它安一个类型。
     */
    @Test
    void extractionDoesNotFillInATypeAHumanLeftEmpty() {
        Fx f = seed();
        UUID decided = entity(f, "Ambiguous Thing", null, "human");
        UUID pending = entity(f, "Other Thing", null, "extracted");
        try {
            // 升格分支要画像相似度才走得到：ctx 为空时整段被跳过（Rust 头注的教训）
            for (UUID id : List.of(decided, pending)) {
                jdbc.update("UPDATE entities SET profile_embedding = ?::vector, profile_n = 1 "
                        + "WHERE id = ?", "[1,0,0]", id);
            }
            // 抽取再次遇到同名 mention，判出 organization。余弦 = 1.0，远高于 SIM_ATTACH
            for (UUID id : List.of(decided, pending)) {
                String name = jdbc.queryForObject(
                        "SELECT canonical_name FROM entities WHERE id = ?", String.class, id);
                resolution.resolveMention(f.kb, f.orgType, name, new float[]{1f, 0f, 0f}, null,
                        null, List.of());
            }
            // 对照：没有人拍过板的那个应该被升格，否则这个测试证明不了守卫在起作用
            assertEquals(f.orgType, jdbc.queryForObject(
                    "SELECT type_id FROM entities WHERE id = ?", UUID.class, pending),
                    "还没判过的该被抽取升格——不然下面那条断言是空的");
            assertEquals(null, jdbc.queryForObject("SELECT type_id FROM entities WHERE id = ?",
                    UUID.class, decided), "人说过「就是没有类型」，抽取不该替他填一个");
            assertEquals("human", sourceOf(decided));
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb);
        }
    }

    /** retype_entities 用现成的 actor 参数区分来源：人点的批准是背书，受保护；引擎的不是。 */
    @Test
    void whoApprovedARetypeDecidesWhetherItIsProtected() {
        Fx f = seed();
        UUID actor = UUID.randomUUID();
        jdbc.update("CALL jknow_test_user(?, ?, ? || '@p4a.test', 'p4a')", actor, f.org, actor);
        try {
            UUID a = entity(f, "Approved", f.orgType, "extracted");
            UUID b = entity(f, "Auto", f.orgType, "extracted");
            resolution.retypeEntities(f.kb, List.of(new ResolutionService.EntityRetype(a, f.subType)),
                    actor);
            resolution.retypeEntities(f.kb, List.of(new ResolutionService.EntityRetype(b, f.subType)),
                    null);
            assertEquals("human", sourceOf(a), "人点的批准是背书");
            assertEquals("inferred", sourceOf(b), "引擎自动裁决的不是");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb);
            jdbc.update("CALL jknow_test_user_cleanup_uuid(?)", actor);
        }
    }

    /**
     * 引擎改过的实体，下一轮还要捞得回来：类型消解落库时把「点运行的那个人」当成了
     * retype 的 actor，有 actor 就写 type_source='human'——跑过一次消解的实体从此永远
     * 不再被消解。谁点的运行与谁判定这个实体是什么是两件事。
     */
    @Test
    void anEngineRetypeDoesNotLockTheEntityOutOfTheNextRound() {
        Fx f = seed();
        UUID e = entity(f, "Initech", f.orgType, "extracted");
        try {
            // 留一个 specific_type：让它在取材条件上始终够格，落选与否只取决于 type_source
            jdbc.update("UPDATE entities SET specific_type = 'startup company' WHERE id = ?", e);
            resolution.retypeEntities(f.kb, List.of(new ResolutionService.EntityRetype(e, f.subType)),
                    null);
            assertEquals("inferred", sourceOf(e), "引擎裁决不是人的背书");
            assertTrue(picked(f).contains(e),
                    "引擎改过一次不该把实体锁死——本体还会长大，它还得能被重判");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb);
        }
    }
}
