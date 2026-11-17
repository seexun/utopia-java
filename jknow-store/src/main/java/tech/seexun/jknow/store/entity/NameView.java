package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 一个名字的事实行（models.rs:764-777 NameView serde 形状）：名字是关于实体的一条
 * 事实（0041 决定 1）——有出处、两个时钟、可以有有效期（更名）、能被撤回。世界轴上
 * 曾用名在 valid_to 有一个结束。
 */
public record NameView(
        UUID factId,
        String name,
        /** 界面上显示的那个名字（entities.canonical_name）。 */
        boolean canonical,
        OffsetDateTime recordedAt,
        OffsetDateTime validFrom,
        String validFromPrecision,
        OffsetDateTime validTo,
        String validToPrecision,
        List<UUID> documentIds,
        long evidenceCount) {
}
