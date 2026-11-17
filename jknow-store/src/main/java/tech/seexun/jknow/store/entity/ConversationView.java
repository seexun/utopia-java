package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 会话列表行（models.rs:419-427 ConversationView；conversations.rs:32-66）。 */
public record ConversationView(
        UUID id,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long messageCount) {
}
