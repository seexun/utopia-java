package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** recent_context 读的一行消息（conversations.rs:195-204 ContextRow）。 */
public record ContextRow(UUID id, String role, String content, JsonNode resolved,
                         JsonNode toolExchange, JsonNode sources, boolean stopped) {
}
