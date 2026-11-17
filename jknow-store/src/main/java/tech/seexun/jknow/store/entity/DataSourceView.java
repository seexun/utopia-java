package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 问数数据源视图（models.rs:1146-1155 DataSourceView；连接串不下发，只露 host:port/db 摘要）。 */
public record DataSourceView(
        UUID id,
        String name,
        String engine,
        String summary,
        OffsetDateTime createdAt,
        OffsetDateTime lastTestAt,
        Boolean lastTestOk) {
}
