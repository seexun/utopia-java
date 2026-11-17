package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 会话消息（models.rs:429-439 ConversationMessage；含落库 steps/sources）。 */
public record ConversationMessage(
        UUID id,
        String role,
        String content,
        JsonNode steps,
        JsonNode sources,
        boolean stopped,
        OffsetDateTime createdAt) {
}
