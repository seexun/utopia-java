package tech.seexun.jknow.web.knowledge.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.store.entity.LlmSettingsRow;

/**
 * 对话客户端按工作区缓存（state.chat_clients 的对位）：端点报过的上下文窗口记在客户端
 * 上（#964），换轮也记得——不缓存的话每轮都忘，同一个超长历史会再挨一次 400。
 * settings 变了（换模型/换地址）立刻重建：模型换了不能继承上次的窗口。
 */
@Component
public class ChatClients {

    private static final class Cached {

        final ChatClient client;
        final String fingerprint;

        Cached(ChatClient client, String fingerprint) {
            this.client = client;
            this.fingerprint = fingerprint;
        }
    }

    private final Map<UUID, Cached> byWorkspace = new ConcurrentHashMap<>();

    /** settings→客户端（chat_ready 才建；null = 没配好）。 */
    public ChatClient get(UUID workspaceId, LlmSettingsRow settings) {
        if (settings == null || !settings.chatReady()) {
            return null;
        }
        String fingerprint = settings.chatBaseUrl() + "|" + settings.chatModel() + "|"
                + (settings.chatApiKey() == null ? "" : settings.chatApiKey()) + "|"
                + (settings.chatReasoningEffort() == null ? "" : settings.chatReasoningEffort());
        Cached cached = byWorkspace.get(workspaceId);
        if (cached != null && cached.fingerprint.equals(fingerprint)) {
            return cached.client;
        }
        ChatClient client = new ChatClient(settings.chatBaseUrl(), settings.chatApiKey(),
                settings.chatModel(), settings.chatReasoningEffort());
        byWorkspace.put(workspaceId, new Cached(client, fingerprint));
        return client;
    }
}
