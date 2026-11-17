package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;

/** 任务状态行（jobs.rs:330-339 status_in_kb 的 SELECT 列集）。 */
public record JobStatus(
        long id,
        String kind,
        String status,
        int attempts,
        int maxAttempts,
        String lastError,
        OffsetDateTime runAt,
        OffsetDateTime updatedAt) {
}
