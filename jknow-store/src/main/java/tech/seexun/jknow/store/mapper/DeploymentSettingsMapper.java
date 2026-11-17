package tech.seexun.jknow.store.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 部署设置 mapper（对齐旧仓库 utopia-store/src/access.rs 的两处，SQL 见 resources/mapper/）。 */
@Mapper
public interface DeploymentSettingsMapper {

    /**
     * 落库自动生成的 JWT 密钥，返回最终生效的那一个（access.rs:248-262 ensure_jwt_secret）。
     * COALESCE 让并发启动的多个实例收敛到同一个值；表无单例行时返回 null（调用方按内部错误处理）。
     */
    String ensureJwtSecret(@Param("generated") String generated);

    /** 注册开关（access.rs:183-188 open_registration）：无行时 null（调用方按 Rust 语义取缺省 true）。 */
    Boolean selectOpenRegistration();

    /**
     * 任务 worker 并发数（access.rs:223-231 worker_concurrency）：无行 null（调用方兜底 64
     * ——与迁移 0011 列缺省分开写，改一处不会带上另一处）。
     */
    Integer selectWorkerConcurrency();

    /**
     * 写 worker 并发数（access.rs:233-244 set_worker_concurrency，M5 任务 6 落位）：范围
     * 校验 1..=256 在 SQL 之外（AppError invalid {@code concurrency_range}）——列上的 CHECK
     * 与这里的放行档位由 {@code the_backstop_can_be_raised} 盯着不漂。
     *
     * @throws tech.seexun.jknow.common.AppException concurrency_range——越界值
     */
    default void updateWorkerConcurrency(int value) {
        if (value < 1 || value > 256) {
            throw tech.seexun.jknow.common.AppException.invalid("concurrency_range",
                    "worker_concurrency must be between 1 and 256");
        }
        doUpdateWorkerConcurrency(value);
    }

    /** updateWorkerConcurrency 的 SQL 落点（UPDATE 全表单例行）。 */
    void doUpdateWorkerConcurrency(@Param("value") int value);
}
