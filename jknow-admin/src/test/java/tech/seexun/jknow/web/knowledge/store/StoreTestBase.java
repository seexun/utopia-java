package tech.seexun.jknow.web.knowledge.store;

import java.net.InetSocketAddress;
import java.net.Socket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * store 读层测试基类（源 utopia-server M1 任务 3；P1 任务 7 随迁改造）：
 * Spring 全栈上下文（现 boot 的是 jknow 若依全栈）<b>直连 jknow dev 共享库</b>
 * （127.0.0.1:15432，docker-compose.dev.yml 的 pg；凭据环境变量注入，
 * 缺省与 compose 一致）。与源版的差异：
 * <ul>
 * <li>数据源走若依 Druid 动态源——override 键从 spring.datasource.* 换成
 *     spring.datasource.druid.master.*；</li>
 * <li>dev 库 schema 归 Flyway 全链所有（V0000-V0101 已入库），测试一律关迁移
 *     （spring.flyway.enabled=false——测试绝不对共享库跑迁移）；</li>
 * <li>上下文还需要 Redis（若依配置缓存/登录态）——守卫一并检查 36379，
 *     不可达整类跳过（干净 clone 的 mvn verify 仍绿）；</li>
 * <li>jknow.data-dir 指 JVM 临时目录（封印钥匙与 dev 数据目录隔离，
 *     测试不写封印行）。</li>
 * </ul>
 * 造数自律保持源版承诺：只做 INSERT fixture + @AfterAll 物理清理，不 UPDATE/DELETE
 * 既有行。身份主档已切 sys_user（V0101）：用户 fixture 走 sys_user（utopia_uuid 桥接列）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.druid.master.url=${jknow.test.jdbc-url:jdbc:postgresql://localhost:15432/jknow}",
        "spring.datasource.druid.master.username=${jknow.test.db-username:postgres}",
        "spring.datasource.druid.master.password=${jknow.test.db-password:postgres}",
        "spring.flyway.enabled=false",
        "jknow.data-dir=${java.io.tmpdir}/jknow-test-secret",
})
// PER_CLASS：fixture 的 @BeforeAll/@AfterAll 为非静态方法，才能拿到注入的 JdbcTemplate
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
// 守卫用 @ExtendWith 而不是 @EnabledIf：@EnabledIf 无 @Inherited（源版任务 5 接力教训：
// 声明在基类上子类拿到的是 EnabledIfCondition 却找不到注解——条件变 no-op）。
// 扩展注册沿类层次继承，SharedDbGuard 因此对全部子类生效。
@org.junit.jupiter.api.extension.ExtendWith(StoreTestBase.SharedDbGuard.class)
public abstract class StoreTestBase {

    /** 共享栈可达性守卫（评估先于 Spring context 加载——条件阶段在扩展回调之前）。 */
    static class SharedDbGuard implements org.junit.jupiter.api.extension.ExecutionCondition {
        @Override
        public org.junit.jupiter.api.extension.ConditionEvaluationResult evaluateExecutionCondition(
                org.junit.jupiter.api.extension.ExtensionContext context) {
            return sharedStackUp()
                    ? org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled(
                            "jknow 共享栈（PG 15432 + Redis 36379）可达")
                    : org.junit.jupiter.api.extension.ConditionEvaluationResult.disabled(
                            "jknow 共享栈（PG 15432 + Redis 36379）不可达，跳过直连测试");
        }
    }

    protected static final String DB_URL =
            System.getenv().getOrDefault("JKNOW_TEST_JDBC_URL", "jdbc:postgresql://localhost:15432/jknow");
    protected static final String DB_USER =
            System.getenv().getOrDefault("JKNOW_TEST_DB_USERNAME", "postgres");
    protected static final String DB_PASS =
            System.getenv().getOrDefault("JKNOW_TEST_DB_PASSWORD", "postgres");

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * 直调 controller 的登录态供给（P2 任务 4 起 controller 挂 @PreAuthorize）：
     * MockMvc 面的登录态由 postprocessor 逐请求挂（ApiTestBase.ruoyi）；直接调
     * bean 方法的测试（ChatFx 直调 chatAttached、PhraseAlignmentTests 直调
     * createRelationType 等）要自备 SecurityContext，否则 @ss.hasPermi 取不到
     * LoginUser 直接抛「获取用户信息异常」。与 ApiTestBase.ruoyi 同形状，
     * permissions 取 admin 全通 {@code *:*:*}——权限语义不是这些测试的考察点。
     * 用完 {@link #logoutDirectCall()} 清线程，防串味到其它测试面。
     */
    protected static void loginAsAdminDirectCall() {
        tech.seexun.jknow.common.core.domain.entity.SysUser sysUser =
                new tech.seexun.jknow.common.core.domain.entity.SysUser();
        sysUser.setUserId(1L);
        tech.seexun.jknow.common.core.domain.model.LoginUser loginUser =
                new tech.seexun.jknow.common.core.domain.model.LoginUser(
                        1L, null, sysUser, java.util.Set.of("*:*:*"));
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken(
                        loginUser, null, loginUser.getAuthorities()));
        // hasPermi 里的 PermissionContextHolder.setContext 要线程绑定的 request 属性——
        // 直调没有真请求，挂一个 mock 的（与 MockMvc 面的 ServletRequestAttributes 同构）
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));
    }

    /** {@link #loginAsAdminDirectCall()} 的收尾：清线程 SecurityContext 与请求属性。 */
    protected static void logoutDirectCall() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    /**
     * sys_user 造数/清理助手（P1 任务 7 身份主档切 sys_user 后的 fixture 桥）：
     * 旧 users 表 fixture（INSERT INTO users (id, org_id, email, ...) / DELETE WHERE email）
     * 在测试 SQL 里统一改写为对这几个函数的调用——Java 侧实参表零改动（org 参数被吸收
     * 忽略、密码列丢弃、is_admin 折成超级管理员角色 sys_user_role(role_id=1)）。
     * user_name/nick_name 是 varchar(30)，函数内 LEFT 截断；幂等（CREATE OR REPLACE）。
     */
    @org.junit.jupiter.api.BeforeAll
    void ensureSysUserFixtureHelpers() {
        jdbc.execute("""
                CREATE OR REPLACE PROCEDURE jknow_test_user(p_id uuid, p_org uuid, p_email text,
                                                            p_name text, p_admin boolean DEFAULT false)
                LANGUAGE plpgsql AS $fn$
                BEGIN
                    INSERT INTO sys_user (utopia_uuid, user_name, nick_name, password)
                    VALUES (p_id, LEFT(p_email, 30), LEFT(p_name, 30), 'test-fixture');
                    IF p_admin THEN
                        INSERT INTO sys_user_role (user_id, role_id)
                        SELECT user_id, 1 FROM sys_user WHERE utopia_uuid = p_id;
                    END IF;
                END
                $fn$""");
        jdbc.execute("""
                CREATE OR REPLACE PROCEDURE jknow_test_user_cleanup(VARIADIC p_names text[])
                LANGUAGE plpgsql AS $fn$
                BEGIN
                    DELETE FROM sys_user_role WHERE user_id IN
                        (SELECT user_id FROM sys_user WHERE user_name = ANY(p_names));
                    DELETE FROM sys_user WHERE user_name = ANY(p_names);
                END
                $fn$""");
        jdbc.execute("""
                CREATE OR REPLACE PROCEDURE jknow_test_user_cleanup_like(p_pattern text)
                LANGUAGE plpgsql AS $fn$
                BEGIN
                    DELETE FROM sys_user_role WHERE user_id IN
                        (SELECT user_id FROM sys_user WHERE user_name LIKE p_pattern);
                    DELETE FROM sys_user WHERE user_name LIKE p_pattern;
                END
                $fn$""");
        jdbc.execute("""
                CREATE OR REPLACE PROCEDURE jknow_test_user_cleanup_uuid(p_id uuid)
                LANGUAGE plpgsql AS $fn$
                BEGIN
                    DELETE FROM sys_user_role WHERE user_id IN
                        (SELECT user_id FROM sys_user WHERE utopia_uuid = p_id);
                    DELETE FROM sys_user WHERE utopia_uuid = p_id;
                END
                $fn$""");
    }

    /** 共享栈可达性（dev compose：pg 15432 + redis 36379——若依上下文两样都要）。 */
    public static boolean sharedStackUp() {
        int pgPort = portOf(DB_URL, 15432);
        String redisPort = System.getenv().getOrDefault("JKNOW_REDIS_PORT", "36379");
        return reachable("127.0.0.1", pgPort) && reachable("127.0.0.1", Integer.parseInt(redisPort));
    }

    /** 从 JDBC URL 提取端口，失败取缺省。 */
    static int portOf(String url, int dflt) {
        try {
            int idx = url.lastIndexOf(':');
            return Integer.parseInt(url.substring(idx + 1).replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return dflt;
        }
    }

    static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
