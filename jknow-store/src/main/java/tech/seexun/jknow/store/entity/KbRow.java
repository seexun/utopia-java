package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * knowledge_bases 表行（对齐 Rust utopia-core models::KnowledgeBase 的 FromRow 列集，
 * SELECT * 全 20 列；rbac 判定链只读 id/workspace_id/visibility，其余列任务 5 的
 * GET/PATCH /kbs/{id} 读面复用）。字段顺序=DB 列序（0001/0011 等迁移定型）。
 */
public record KbRow(
        UUID id,
        UUID workspaceId,
        String name,
        String kind,
        String description,
        boolean isDefault,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String visibility,
        boolean autoExtendOntology,
        String ontologyLang,
        boolean materializeInferences,
        int inferenceIntervalMinutes,
        OffsetDateTime lastInferenceAt,
        boolean autoTypeResolution,
        boolean governance,
        OffsetDateTime governanceSince,
        String dataDescription,
        JsonNode dataQuestions,
        String dataConventions) {
}
