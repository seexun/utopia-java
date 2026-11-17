package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.TokensService;

/**
 * 个人访问令牌（a_token_is_the_person_but_not_all_of_them.rs 原值移植，0014 / 迁移
 * 0017——M5 任务 6 收口：PAT 域随切流演练在 TokensService 落位，白盒随之销账）。
 * 断言逐字：明文只出现一次/撤销立刻生效（SQL 里判）打戳不删行/过期立刻失效/
 * kb_ids 只收窄/last_used_at 会被写上/别人的令牌撤不动。
 */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class ATokenIsThePersonButNotAllOfThemTests extends StoreTestBase {

    @Autowired
    TokensService tokens;

    private UUID org;
    private UUID user;
    private UUID kbA;
    private UUID kbB;

    @BeforeAll
    void seed() {
        org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        user = Uuids.nowV7();
        kbA = Uuids.nowV7();
        kbB = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'token-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'token-test')", ws,
                org);
        for (Object[] kb : new Object[][]{{kbA, "kb-a"}, {kbB, "kb-b"}}) {
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name)"
                    + " VALUES (?, ?, ?)", kb[0], ws, kb[1]);
        }
        jdbc.update("""
                CALL jknow_test_user(?, ?, ?, 'Token Test')
                """, user, org, user + "@token.test");
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM organizations WHERE id = ?", org);
    }

    @Test
    void aTokenIsThePersonButNotAllOfThem() {
        // ---- 一、发一枚，明文只这一次拿得到
        TokensService.Issued first = tokens.issue(user, "我的笔记本", "read", null, null);
        String plain = first.plain();
        assertTrue(plain.startsWith("utp_pat_"), "前缀要认得出是哪一种令牌");
        assertEquals("read", first.view().scope(), "**缺省只读**：要写得显式勾");
        assertEquals(null, first.view().revokedAt());
        String stored = jdbc.queryForObject(
                "SELECT token_hash FROM personal_tokens WHERE id = ?",
                String.class, first.view().id());
        assertNotEquals(plain, stored, "库里存的必须是哈希");
        assertTrue(!stored.contains(plain.substring(8, 24)),
                "**明文的任何一段都不该出现在库里**——拿到整张表也复原不出来");

        // ---- 二、认得回来，而且顺手写了 last_used_at
        TokensService.Authenticated auth = tokens.authenticate(plain);
        assertEquals(user, auth.userId(), "令牌以发它的人的身份行事");
        assertTrue(!auth.canWrite(), "read 的令牌不能写");
        OffsetDateTime used = jdbc.queryForObject(
                "SELECT last_used_at FROM personal_tokens WHERE id = ?",
                OffsetDateTime.class, first.view().id());
        assertNotNull(used, "**撤之前人要答得出「这把还在用吗」**");

        // ---- 三、伪造的、前缀不对的，一律不认
        assertThrows(AppException.class, () -> tokens.authenticate("utp_pat_deadbeef"),
                "编一串出来不该认");
        assertThrows(AppException.class,
                () -> tokens.authenticate(plain.replace("utp_pat_", "utp_")),
                "摄入令牌的前缀不该走这条路");

        // ---- 四、kb_ids 只收窄
        TokensService.Issued scoped = tokens.issue(user, "只给 A 库", "write",
                List.of(kbA), null);
        TokensService.Authenticated scopedAuth = tokens.authenticate(scoped.plain());
        assertTrue(scopedAuth.covers(kbA), "授权过的库够得着");
        assertTrue(!scopedAuth.covers(kbB),
                "**限定到一个库的令牌够不着另一个**——哪怕这个人两个库都能进");
        assertTrue(scopedAuth.canWrite(), "write 的令牌能写");
        assertTrue(tokens.authenticate(plain).covers(kbB), "不限定的那一枚照样覆盖全部");

        // ---- 五、撤销立刻生效，而且行还在
        tokens.revoke(user, first.view().id());
        assertThrows(AppException.class, () -> tokens.authenticate(plain),
                "**撤了就立刻不认**——判断在 SQL 里，不在取回来之后");
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM personal_tokens WHERE id = ?", Long.class,
                first.view().id()), "打戳不删行：这把钥匙存在过要查得到");
        assertThrows(AppException.class, () -> tokens.revoke(user, first.view().id()),
                "撤两次的第二次该说没有这一行可撤");

        // ---- 六、过期
        TokensService.Issued expired = tokens.issue(user, "早就过期的", "read", null,
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
        assertThrows(AppException.class, () -> tokens.authenticate(expired.plain()),
                "过了期就不认");

        // ---- 七、列表把撤过的、过期的都列出来
        List<tech.seexun.jknow.store.entity.TokenView> all = tokens.list(user);
        assertEquals(3, all.size(), "撤销过的也要列——撤过这件事本身要看得见");
        assertTrue(all.stream().anyMatch(
                t -> t.id().equals(first.view().id()) && t.revokedAt() != null),
                "列表要标出哪一把被撤了");
        assertTrue(all.stream().allMatch(t -> t.tokenPrefix().startsWith("utp_pat_")),
                "列表只给前缀，不给明文");
        assertTrue(all.stream().anyMatch(t -> t.id().equals(expired.view().id())));

        // 别人的令牌撤不动
        UUID other = Uuids.nowV7();
        assertThrows(AppException.class, () -> tokens.revoke(other, expired.view().id()),
                "**令牌是谁的谁才撤得动**");
    }
}
