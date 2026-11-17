package tech.seexun.jknow.store.entity;

/** 库就绪度（models.rs:1039-1052 Readiness；kbs.rs:173-199 单查询五列）。 */
public record Readiness(
        boolean hasChatModel,
        long documents,
        long processing,
        long failed,
        long entities) {
}
