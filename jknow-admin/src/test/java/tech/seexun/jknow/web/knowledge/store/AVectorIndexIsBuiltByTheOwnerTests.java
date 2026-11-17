package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.store.VectorIndexService;
import tech.seexun.jknow.store.VectorIndexService.Target;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;

/**
 * 移植自顶层 tests/a_vector_index_is_built_by_the_owner.rs（1 例）——建索引要表的所有权
 * （#1120）：运行身份是受限角色（只读写、不拥有表——部署里 {@code utopia_app} 的处境）时
 * 它自己建不出来，得用迁移那个身份的连接去建。连库才测得到：建一个只有读写授权、不拥有
 * 任何表的登录角色。角色和索引自建自拆。
 *
 * <p>ownerUrl 传 JDBC 形态并带身份参数（Rust 原生串由 {@code build_as_owner} 自行换形态；
 * Java 的 {@code buildAsOwner} 两种都收——{@code jdbc:} 开头直用）。
 */
class AVectorIndexIsBuiltByTheOwnerTests extends StoreTestBase {

    private static final String ROLE = "utopia_index_probe";
    private static final String PASSWORD = "index-probe-only";
    /** 别的测试不用的维度，索引名不会撞。 */
    private static final int DIMS = 19;

    @Autowired
    VectorIndexService ownerSide;
    @Autowired
    DocumentsWriteMapper documents;
    @Autowired
    tech.seexun.jknow.store.JobsService jobs;

    @Test
    void aRestrictedRoleCannotBuildAndTheOwnerCan() throws Exception {
        jdbc.update("""
                DO $$ BEGIN
                   IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                     CREATE ROLE %s LOGIN PASSWORD '%s';
                   END IF;
                 END $$;
                 GRANT USAGE ON SCHEMA public TO %s;
                 GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %s;
                """.formatted(ROLE, ROLE, PASSWORD, ROLE, ROLE));
        ownerSide.drop(Target.NAME_VECTORS, DIMS);

        HikariDataSource app = new HikariDataSource();
        app.setJdbcUrl(DB_URL);
        app.setUsername(ROLE);
        app.setPassword(PASSWORD);
        app.setMaximumPoolSize(2);
        try {
            VectorIndexService restricted = new VectorIndexService(app, documents, jobs);
            // 身份没分开时的老路：受限角色自己建，库拒绝
            SQLException refused = org.junit.jupiter.api.Assertions.assertThrows(
                    SQLException.class, () -> restricted.build(Target.NAME_VECTORS, DIMS),
                    "a role that does not own the table cannot index it");
            assertTrue(refused.getMessage() != null && refused.getMessage().contains("must be owner"),
                    "the refusal is about ownership: " + refused.getMessage());
            assertEquals(null, ownerSide.status(Target.NAME_VECTORS, DIMS), "nothing was built");

            // 给了 owner 的连接串：同一个任务建得出来，应用的池子仍是受限角色
            String ownerUrl = DB_URL + "?user=" + DB_USER + "&password=" + DB_PASS;
            VectorIndexService.Built built = restricted.buildAsOwner(ownerUrl,
                    Target.NAME_VECTORS, DIMS);
            assertTrue(built.created());
            assertEquals(Boolean.TRUE, restricted.status(Target.NAME_VECTORS, DIMS),
                    "the restricted role sees a valid index it can use");
        } finally {
            app.close();
            try {
                ownerSide.drop(Target.NAME_VECTORS, DIMS);
            } catch (Exception ignored) {
                // teardown 不放大失败
            }
            jdbc.update("DROP OWNED BY " + ROLE + "; DROP ROLE " + ROLE + ";");
        }
    }
}
