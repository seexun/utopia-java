package tech.seexun.jknow.web.knowledge.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.store.StoreTestBase;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * KbAccess 四路径（任务 3 步骤 2 核心，m1-recon §2 判定链逐条）：
 * <ol>
 *   <li><b>admin 通配</b>：is_admin → Owner 全通（无矩阵记录也过，含 restricted 库 Owner 级）；</li>
 *   <li><b>矩阵角色</b>：kb_members 记录 editor → requireKb(Editor) 过 / requireKb(Admin) 403；</li>
 *   <li><b>角色不足（low→403）</b>：viewer 矩阵要求 Editor → 403 FORBIDDEN 文案对齐；</li>
 *   <li><b>restricted 无记录→404</b>：不可见优先于权限（不泄露存在性）；外加
 *       open+workspace membership→Viewer、脏角色=不可见、库不存在→404 三条边界。</li>
 * </ol>
 * fixture 全部 SQL 直插共享库（同库同表生产形状），@AfterAll 物理清理。
 */
class KbAccessTests extends StoreTestBase {

    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String ADMIN_EMAIL = "task3-rbac-admin-" + RUN + "@test.local";
    private static final String USER_EMAIL = "task3-rbac-user-" + RUN + "@test.local";

    private static UUID orgId;
    private static UUID adminId;
    private static UUID userId;
    private static UUID wsId;
    private static UUID kbOpenId;      // 矩阵 editor
    private static UUID kbOpen2Id;     // open 无矩阵（workspace membership → Viewer）
    private static UUID kbRestrictedId;// restricted 无记录 → 404

    @Autowired
    KbAccess rbac;

    @BeforeAll
    void fixture() {
        orgId = jdbc.queryForObject("SELECT id FROM organizations ORDER BY created_at LIMIT 1", UUID.class);
        adminId = insertUser(ADMIN_EMAIL, true);
        userId = insertUser(USER_EMAIL, false);
        wsId = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)",
                wsId, orgId, "ws-task3-rbac-" + RUN);
        kbOpenId = insertKb("kb-task3-open", "open");
        kbOpen2Id = insertKb("kb-task3-open2", "open");
        kbRestrictedId = insertKb("kb-task3-restricted", "restricted");
        // 矩阵：open 库 editor（写权限来自矩阵）。脏角色路径不可经 DB 造出
        // （kb_members_role_check CHECK 约束挡写）——Role.parse 的 null 分支由 roleOrder 单测覆盖
        jdbc.update("INSERT INTO kb_members (kb_id, user_id, role) VALUES (?, ?, 'editor')",
                kbOpenId, userId);
        // 部署侧：user 是该工作区成员（open 库的部署角色可见性）
        jdbc.update("INSERT INTO memberships (user_id, workspace_id, role) VALUES (?, ?, 'viewer')",
                userId, wsId);
    }

    private UUID insertUser(String email, boolean isAdmin) {
        UUID id = UUID.randomUUID();
        jdbc.update("CALL jknow_test_user(?, ?, ?, ?, ?)",
                id, orgId, email, "Task3 Rbac " + RUN, isAdmin);
        return id;
    }

    private UUID insertKb(String name, String visibility) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name, visibility) VALUES (?, ?, ?, ?)",
                id, wsId, name + "-" + RUN, visibility);
        return id;
    }

    @AfterAll
    void cleanup() {
        if (!sharedStackUp()) {
            return;
        }
        // kb_members/memberships 随 users/kbs 级联；显式按本轮 id 清三张主表
        jdbc.update("DELETE FROM knowledge_bases WHERE workspace_id = ?", wsId);
        jdbc.update("DELETE FROM workspaces WHERE id = ?", wsId);
        jdbc.update("CALL jknow_test_user_cleanup(?, ?)", ADMIN_EMAIL, USER_EMAIL);
    }

    // ---- 路径 1：admin 通配 ----

    @Test
    void adminWildcardIsOwnerEverywhere() {
        UserRow admin = user(adminId, true);
        assertEquals(Role.OWNER, rbac.kbRole(admin, kb(kbRestrictedId)), "is_admin 链首即 Owner");
        assertEquals(Role.OWNER, rbac.kbRole(admin, kb(kbOpenId)));
        // restricted 库无任何记录，admin 仍以 Owner 过（access.rs:13-15）
        KbRow granted = rbac.requireKb(admin, kbRestrictedId, Role.OWNER);
        assertEquals(kbRestrictedId, granted.id());
    }

    // ---- 路径 2：矩阵角色 ----

    @Test
    void matrixRoleGrantsExactlyItsRank() {
        UserRow user = user(userId, false);
        assertEquals(Role.EDITOR, rbac.kbRole(user, kb(kbOpenId)), "矩阵优先于 open");
        assertEquals(kbOpenId, rbac.requireKb(user, kbOpenId, Role.EDITOR).id(),
                "矩阵 editor 要求 Editor 通过");
        assertEquals(kbOpenId, rbac.requireKb(user, kbOpenId, Role.VIEWER).id());
    }

    // ---- 路径 3：角色不足 → 403（文案对齐 error.rs Forbidden） ----

    @Test
    void lowRoleIsForbiddenWithCanonicalMessage() {
        UserRow user = user(userId, false);
        AppException e = assertThrows(AppException.class,
                () -> rbac.requireKb(user, kbOpenId, Role.ADMIN));
        assertEquals(AppException.Kind.FORBIDDEN, e.kind());
        assertEquals("You don't have permission to do that", e.getMessage());
    }

    // ---- 路径 4：restricted 无记录 → 404（不泄露存在性） ----

    @Test
    void restrictedWithoutRecordIsNotFoundNotForbidden() {
        UserRow user = user(userId, false);
        assertNull(rbac.kbRole(user, kb(kbRestrictedId)), "restricted 无矩阵且非 open → 不可见");
        AppException e = assertThrows(AppException.class,
                () -> rbac.requireKb(user, kbRestrictedId, Role.VIEWER));
        assertEquals(AppException.Kind.NOT_FOUND, e.kind(), "404 而非 403——存在性不泄露");
        assertEquals("Not found", e.getMessage());
    }

    // ---- 边界：open+workspace membership → Viewer；库不存在 → 404 ----

    @Test
    void openKbGrantsViewerViaWorkspaceMembershipOnly() {
        UserRow user = user(userId, false);
        assertEquals(Role.VIEWER, rbac.kbRole(user, kb(kbOpen2Id)),
                "open 库 + workspace membership → Viewer（无矩阵记录）");
        assertEquals(kbOpen2Id, rbac.requireKb(user, kbOpen2Id, Role.VIEWER).id());
        AppException e = assertThrows(AppException.class,
                () -> rbac.requireKb(user, kbOpen2Id, Role.EDITOR));
        assertEquals(AppException.Kind.FORBIDDEN, e.kind(),
                "部署角色不向库内映射写权（Viewer 要求 Editor → 403）");
    }

    @Test
    void unknownKbIsNotFound() {
        UserRow user = user(userId, false);
        assertEquals(AppException.Kind.NOT_FOUND,
                assertThrows(AppException.class,
                        () -> rbac.requireKb(user, UUID.randomUUID(), Role.VIEWER)).kind());
    }

    @Test
    void roleOrderMatchesDeclaration() {
        assertTrue(Role.VIEWER.rank() < Role.EDITOR.rank()
                && Role.EDITOR.rank() < Role.ADMIN.rank()
                && Role.ADMIN.rank() < Role.OWNER.rank(), "viewer<editor<admin<owner");
        assertEquals(Role.ADMIN, Role.parse("admin"));
        assertNull(Role.parse("Admin"), "字面量区分大小写（models.rs:72-80）");
        assertNull(Role.parse("nope"));
    }

    // ---- fixture 小工具：从共享库回读真实行（SELECT * 形状，走 KbMapper 的同一条 SQL） ----

    private UserRow user(UUID id, boolean admin) {
        return jdbc.queryForObject(
                "SELECT utopia_uuid AS id, NULL::uuid AS org_id, user_name AS email, NULL::text AS password_hash, nick_name AS display_name, (user_id = 1 OR EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = sys_user.user_id AND ur.role_id = 1)) AS is_admin, create_time AT TIME ZONE 'UTC' AS created_at, NULL::timestamptz AS deactivated_at FROM sys_user WHERE utopia_uuid = ?",
                (rs, i) -> new UserRow(rs.getObject("id", UUID.class),
                        rs.getObject("org_id", UUID.class), rs.getString("email"),
                        rs.getString("password_hash"), rs.getString("display_name"),
                        rs.getBoolean("is_admin"), rs.getObject("created_at", java.time.OffsetDateTime.class),
                        rs.getObject("deactivated_at", java.time.OffsetDateTime.class)), id);
    }

    private KbRow kb(UUID id) {
        return jdbc.queryForObject(
                "SELECT id, workspace_id, visibility FROM knowledge_bases WHERE id = ?",
                (rs, i) -> new KbRow(rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class), null, null, null, false, null, null,
                        rs.getString("visibility"), false, null, false, 0, null, false, false, null,
                        null, null, null), id);
    }
}
