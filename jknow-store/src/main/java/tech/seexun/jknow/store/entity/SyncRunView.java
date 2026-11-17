package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 同步运行记录视图（models.rs:313-324 SyncRun，sources.rs:492-495 list_runs 投影）：
 * 渠道的可审计历史，每来源仅保留最近 50 条（finish_run 时修剪）。
 */
public record SyncRunView(
        UUID id,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String status,
        int createdDocs,
        int updatedDocs,
        String error) {
}
