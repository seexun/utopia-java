package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 告警一组（alerts.rs:154-171 AlertGroup；相邻同类折叠 gaps-and-islands）。
 * lines = 组内明细 jsonb 前 {@code GROUP_LINES}=5 条，新的在前。
 */
public record AlertGroup(
        UUID kbId,
        String kbName,
        String kind,
        String severity,
        long count,
        long unread,
        OffsetDateTime latestAt,
        OffsetDateTime earliestAt,
        List<JsonNode> lines) {
}
