package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 来源列表视图（models.rs:347-368 SourceView，sources.rs:89-135 list 的投影）：
 * 带 doc_count/missing_count 子查询计数与 RSS 全文补全整块 rss_full_content
 * （<b>不是 RSS 的来源这一格是 NULL</b>，0033 决定 2/#417）。无 kb_id/created_at/
 * ingest_token——列表给 Viewer 看，哪一种连接器的密钥都不下发。
 */
public record SourceView(
        UUID id,
        String kind,
        String name,
        JsonNode config,
        String icon,
        Integer syncIntervalMinutes,
        String syncCron,
        OffsetDateTime lastSyncAt,
        String lastSyncStatus,
        String lastSyncError,
        int lastSyncAdded,
        long docCount,
        long missingCount,
        JsonNode rssFullContent) {
}
