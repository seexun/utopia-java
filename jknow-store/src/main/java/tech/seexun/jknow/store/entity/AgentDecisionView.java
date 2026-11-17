package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * agent 的一笔（models.rs:1493-1518 AgentDecisionView serde 形状；SQL governance.rs:863-883）。
 * left/right 为 SQL 引号别名（quoted identifier），合并之后仍按当时的实体读得出名字。
 */
public record AgentDecisionView(
        UUID id,
        UUID runId,
        String targetKind,
        UUID targetId,
        String action,
        float confidence,
        String reason,
        JsonNode precedents,
        String status,
        UUID mergeId,
        String question,
        JsonNode trace,
        int calls,
        OffsetDateTime createdAt,
        OffsetDateTime decidedAt,
        String decidedByName,
        String left,
        String right) {
}
