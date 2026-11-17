package tech.seexun.jknow.store.entity;

/** 单模型并发限额行（model_limits.rs list；models.rs:627 ModelLimit 的两列读面）。 */
public record ModelLimitRow(
        String baseUrl,
        String model,
        Integer maxConcurrent) {
}
