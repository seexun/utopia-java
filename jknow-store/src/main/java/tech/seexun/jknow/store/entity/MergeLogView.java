package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 合并日志行（models.rs:976-987 MergeLogView serde 形状；SQL resolution.rs:2364-2372）。
 * merged_by_name NULL = LLM 自动合并。
 */
public record MergeLogView(
        UUID id,
        String sourceName,
        String targetName,
        String mergedByName,
        String reason,
        OffsetDateTime createdAt,
        OffsetDateTime revertedAt) {
}
