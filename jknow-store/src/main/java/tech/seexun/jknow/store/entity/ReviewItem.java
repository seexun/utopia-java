package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 消解审核项（models.rs:937-974 ReviewSide/ReviewProposal/ReviewItem serde 形状）。
 * 组装流程对齐 resolution.rs:1576-1628 assemble_reviews：一页审核行 → 两侧实体摘要
 * （review_sides + top4 事实摘要 + 别名行）→ agent 开着的建议按 target_id 挂上。
 */
public record ReviewItem(
        UUID id,
        float score,
        String reason,
        String stage,
        OffsetDateTime createdAt,
        ReviewSide left,
        ReviewSide right,
        ReviewProposal proposal) {

    /** 一侧实体摘要（resolution.rs:1434-1482 review_sides；degree 排除 known_as）。 */
    public record ReviewSide(
            UUID id,
            String name,
            String typeLabel,
            String color,
            String disambiguator,
            long degree,
            List<String> topFacts) {
    }

    /** agent 在一对上留下的、还开着的建议（0025）。 */
    public record ReviewProposal(
            UUID id,
            String action,
            float confidence,
            String reason) {
    }
}
