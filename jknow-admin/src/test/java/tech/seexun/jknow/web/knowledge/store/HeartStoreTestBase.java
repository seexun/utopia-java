package tech.seexun.jknow.web.knowledge.store;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * 心脏域真库测试基类（M3 任务 2 起）：StoreTestBase 的共享库口径
 * （P1 任务 7 起直连 jknow dev 15432 + SharedDbGuard 跳过语义），但连接池放宽到 16——
 * Rust 参照测试用 max_connections(8/12) 的池跑 tokio::join! 并发对账
 * （a_timeline_is_recomputed values_that_arrive_together 5 路并发），Java 侧
 * REQUIRES_NEW 嵌套会瞬时占两条连接（源版 Hikari 10 会饿死；若依栈换 Druid maxActive）。
 *
 * <p>与 M2 StoreTestBase 的取舍不同：这些测试<b>自建自拆</b>（自己的 org/workspace/kb，
 * 跑完 DELETE 回收），绝不 UPDATE/DELETE 既有行——Rust 原样（「自建自拆，绝不碰
 * 已有的库」）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.druid.master.url=${jknow.test.jdbc-url:jdbc:postgresql://localhost:15432/jknow}",
        "spring.datasource.druid.master.username=${jknow.test.db-username:postgres}",
        "spring.datasource.druid.master.password=${jknow.test.db-password:postgres}",
        "spring.datasource.druid.maxActive=16",
        "spring.flyway.enabled=false",
        "jknow.data-dir=${java.io.tmpdir}/jknow-test-secret",
})
public abstract class HeartStoreTestBase extends StoreTestBase {

    /**
     * world_axis.rs:55-65 facts_holds_from("f") 展开（读出来的下界）——测试侧拼接与
     * 引擎同一套表达式（M2 GraphMapper 头注的展开对照）。
     */
    protected static final String HF_F =
            "CASE WHEN (SELECT r.temporal FROM relation_types r WHERE r.id = f.predicate_id)"
                    + " = 'eternal' THEN NULL ELSE COALESCE(f.valid_from, f.attested_from) END";

    /** world_axis.rs:67-77 facts_holds_to("f") 展开（读出来的上界）。 */
    protected static final String HT_F =
            "CASE WHEN (SELECT r.temporal FROM relation_types r WHERE r.id = f.predicate_id)"
                    + " = 'eternal' THEN NULL"
                    + " WHEN (SELECT r.temporal FROM relation_types r WHERE r.id = f.predicate_id)"
                    + " = 'event' THEN"
                    + " CASE WHEN f.valid_from IS NULL THEN f.attested_from"
                    + " ELSE COALESCE(f.valid_to, f.valid_from)"
                    + " + ('1 ' || COALESCE(NULLIF(f.valid_to_precision, 'unknown'),"
                    + " f.valid_from_precision))::interval END"
                    + " WHEN f.valid_to IS NOT NULL THEN f.valid_to"
                    + " WHEN f.valid_to_precision = 'unknown' THEN f.attested_to END";
}
