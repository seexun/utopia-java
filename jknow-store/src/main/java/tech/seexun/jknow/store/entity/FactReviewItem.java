package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 低置信/待确认事实审核行（models.rs:989-1001 FactReviewItem serde 形状；
 * SQL graph.rs:1976-1989（lowconf）/2013-2031（unconfirmed）同一主体两种过滤）。
 */
public record FactReviewItem(
        UUID id,
        String subjectName,
        String predicateLabel,
        String objectName,
        OffsetDateTime validFrom,
        OffsetDateTime validTo,
        float confidence,
        long evidenceCount,
        String quote) {
}
