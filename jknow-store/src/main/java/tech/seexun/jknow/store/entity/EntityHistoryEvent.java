package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 一个实体身上的认识轴事件（models.rs:866-897 EntityHistoryEvent serde 形状；
 * SQL graph.rs:2232-2404 entity_history）。
 *
 * <p>kind：asserted（首次断言）| corrected（区间被修正）| rejected（认知被推翻）|
 * merged（并进了另一条断言）| retyped（改了类）| retype_reverted（改类被撤销）|
 * merged_in（别人并进了它）| merged_away（它并进了别人）| merge_reverted（合并被撤销）。
 */
public record EntityHistoryEvent(
        UUID factId,
        OffsetDateTime at,
        String kind,
        String direction,
        String predicateLabel,
        String otherName,
        JsonNode objectValue,
        OffsetDateTime validFrom,
        OffsetDateTime validTo,
        String validFromPrecision,
        String validToPrecision,
        Float confidence,
        String actorName,
        String action,
        UUID documentId,
        String filename,
        String quote,
        String fromTypeLabel,
        String toTypeLabel) {

    /** 同查询带回的总数（分页的那句把改类、合并两支也算上，否则翻到尾页会少几条）。 */
    public record Page(java.util.List<EntityHistoryEvent> events, long total) {
    }
}
