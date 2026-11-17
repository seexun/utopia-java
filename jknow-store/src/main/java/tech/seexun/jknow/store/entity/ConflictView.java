package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 时态冲突行（models.rs:1053-1071 ConflictView serde 形状；SQL temporal.rs:1325-1342）：
 * 双方完整三元组快照——主语侧冲突变的是宾语，宾语侧冲突变的是主语。
 */
public record ConflictView(
        UUID id,
        String reason,
        OffsetDateTime createdAt,
        String predicateLabel,
        UUID oldFactId,
        String oldSubject,
        String oldObject,
        OffsetDateTime oldValidFrom,
        UUID newFactId,
        String newSubject,
        String newObject,
        OffsetDateTime newValidFrom,
        float newConfidence) {
}
