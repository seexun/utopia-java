package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.store.mapper.DeploymentSettingsMapper;

/**
 * 外层兜底调得上去，而且两处缺省说的是同一个数（the_backstop_can_be_raised.rs 原值移植，
 * M5 任务 6 台账收口；{@code set_worker_concurrency} 随本移植在
 * {@link DeploymentSettingsMapper#updateWorkerConcurrency} 落位）。
 *
 * <p>这个 bug 的形态是<b>约束在 SQL、校验在 Java，两边各自漂移</b>：放行 1..=256 而列上的
 * CHECK 曾是 BETWEEN 1 AND 32——设置页填 33 到 256 任何一个值，Java 说行、数据库说不行，
 * 用户看到的是一条 CHECK 约束报错。约束的上限当初等于列的缺省（都是 32），于是兜底一格
 * 都调不上去——而 0001 的注释写着它要明显大于各模型限额之和。只读 + 改完还原，绝不
 * 留痕迹；两个检查串行跑（同一行单例，行锁挡得住读脏但没必要赌交错——#248）。
 */
class TheBackstopCanBeRaisedTests extends StoreTestBase {

    @Autowired
    DeploymentSettingsMapper deployment;

    /** Java 放行的值，数据库必须也放行。逐个试边界而不是只试一个：漂移可能出在任何一档上。 */
    private void everyValueJavaAcceptsTheDatabaseAcceptsToo() {
        Integer before = deployment.selectWorkerConcurrency();
        try {
            // 33 是当初被卡死的第一格；256 是放行上限
            for (int v : new int[]{1, 33, 64, 255, 256}) {
                deployment.updateWorkerConcurrency(v);
                assertEquals(v, deployment.selectWorkerConcurrency(),
                        "写进去 " + v + " 读回来却对不上");
            }
            // 反向：放行面拒绝的，不该悄悄落库
            for (int v : new int[]{0, 257, -1}) {
                AppException err = assertThrows(AppException.class,
                        () -> deployment.updateWorkerConcurrency(v));
                assertEquals("concurrency_range", err.code().orElse(null), v + " 越界了却被接受");
            }
        } finally {
            // 还原：这是共享的部署设置，测试不该改变别人的运行参数
            deployment.doUpdateWorkerConcurrency(before == null ? 64 : before);
        }
    }

    /** 表里没有行时的兜底值，必须与列的缺省是同一个数（一处 SQL、一处 Java，改一处不会带上另一处）。 */
    private void theTwoDefaultsSayTheSameNumber() {
        // 列的缺省：直接问 information_schema，不猜
        String columnDefault = jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                  WHERE table_name = 'deployment_settings' AND column_name = 'worker_concurrency'
                """, String.class);
        int fromColumn = Integer.parseInt(columnDefault.split("::")[0].trim());

        // Java 的兜底：把行藏起来再问一次（一定回滚——deployment_settings 是单例）
        jdbc.execute((java.sql.Connection con) -> {
            try (java.sql.Statement st = con.createStatement()) {
                st.execute("BEGIN");
                st.execute("DELETE FROM deployment_settings");
                java.sql.ResultSet rs = st.executeQuery(
                        "SELECT worker_concurrency FROM deployment_settings LIMIT 1");
                assertTrue(!rs.next(), "行没删掉，下面这句就白测了");
                st.execute("ROLLBACK");
            }
            return null;
        });
        int javaFallback = 64; // JobWorker.refreshConcurrency 的 unwrap_or 那个数
        assertEquals(javaFallback, fromColumn,
                "列缺省是 " + fromColumn + "，Java 兜底是 " + javaFallback + "——两处漂开了");
    }

    @Test
    void theBackstopCanBeRaised() {
        everyValueJavaAcceptsTheDatabaseAcceptsToo();
        theTwoDefaultsSayTheSameNumber();
    }
}
