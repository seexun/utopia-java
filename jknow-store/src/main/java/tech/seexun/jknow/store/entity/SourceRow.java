package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * sources 表行（models.rs:142-165 Source 的列集，SELECT * 全列按名映射；rss_generation/
 * rss_baselined_at 两列同步域自用，这里不投影）。config 里的凭据键与 ingest_token
 * 在库里是封印的（{@code enc:v1:}）——出库统一 opened（sources.rs:147-151），调用方
 * 决定回不回显；ingest_token 序列化时剔除（Rust {@code #[serde(skip_serializing)]}，
 * Java 由响应 DTO 不带该字段等价实现）。
 */
public record SourceRow(
        UUID id,
        UUID kbId,
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
        String ingestToken,
        OffsetDateTime createdAt) {

    /** models.rs:178-183 extracts()：缺省要抽，只有 config 明说 {@code "extract": false} 才不抽。 */
    public boolean extracts() {
        return config == null || !config.path("extract").isBoolean()
                || config.path("extract").asBoolean(true);
    }
}
