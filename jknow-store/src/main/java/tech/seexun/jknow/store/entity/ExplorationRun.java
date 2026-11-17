package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 一轮映射探索的覆盖账（models.rs:1194-1215 ExplorationRun；exploration_runs.rs:124-135 recent）。 */
public record ExplorationRun(
        UUID id,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        List<String> sources,
        int tablesScanned,
        int columnsScanned,
        boolean schemaTruncated,
        int cap,
        int returned,
        int accepted,
        JsonNode dropped,
        List<String> tablesCovered,
        String error) {
}
