package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 认领到手的一条任务（对齐旧仓库 crates/utopia-store/src/jobs.rs:17-24 Job 的 FromRow 列集）。
 * attempts 是 <b>认领后</b>的值（claim_one 的 UPDATE 已 +1），mark_failed 的退避算术以它为入参。
 */
public record Job(
        long id,
        String kind,
        JsonNode payload,
        int attempts,
        int maxAttempts) {
}
