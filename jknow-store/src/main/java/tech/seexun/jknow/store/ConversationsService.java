package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.ContextRow;
import tech.seexun.jknow.store.entity.ConversationMessage;
import tech.seexun.jknow.store.entity.ConversationView;
import tech.seexun.jknow.store.entity.RetryTargetRow;
import tech.seexun.jknow.store.mapper.ConversationsMapper;

/**
 * 会话读写面（conversations.rs 全量编排；q 空串当没搜，limit/offset 已由控制器钳位）。
 * M5 任务 4 补写路径：create/append/rename/delete/retry_target/recent_context。
 */
@Service
public class ConversationsService {

    private final ConversationsMapper conversations;
    private final TransactionTemplate tx;

    public ConversationsService(ConversationsMapper conversations,
                                org.springframework.transaction.PlatformTransactionManager txManager) {
        this.conversations = conversations;
        this.tx = new TransactionTemplate(txManager);
    }

    public ConversationPage list(UUID kbId, UUID userId, String q, long limit, long offset) {
        String query = q == null || q.trim().isEmpty() ? null : q.trim();
        List<ConversationView> rows = conversations.selectConversations(kbId, userId, query, limit, offset);
        long total = conversations.countConversations(kbId, userId, query);
        return new ConversationPage(rows, total);
    }

    /** conversations.rs:88-100 require_owned：会话必须属于本 KB 本人，否则 404。 */
    public void requireOwned(UUID kbId, UUID userId, UUID conversationId) {
        if (conversations.selectOwned(kbId, userId, conversationId) == null) {
            throw AppException.NOT_FOUND;
        }
    }

    public List<ConversationMessage> messages(UUID conversationId) {
        return conversations.selectMessages(conversationId);
    }

    /** conversations.rs:9-19 create：标题=第一问截 80 字（字符，非字节——中文问句不截半）。 */
    public UUID create(UUID kbId, UUID userId, String title) {
        UUID id = Uuids.nowV7();
        conversations.insertConversation(id, kbId, userId, truncateChars(title, 80));
        return id;
    }

    /** conversations.rs:69-97 rename：标题 1-120 字（trim 后），0 行 → 404。 */
    public void rename(UUID kbId, UUID userId, UUID conversationId, String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || t.codePoints().count() > 120) {
            throw AppException.invalid("bad_title", "Title must be 1-120 characters");
        }
        if (conversations.renameConversation(kbId, userId, conversationId, t) == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    public void delete(UUID kbId, UUID userId, UUID conversationId) {
        if (conversations.deleteConversation(kbId, userId, conversationId) == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    /**
     * conversations.rs:140-162 retry_question 的资格检查：只接受本会话<b>最后一条未回答</b>
     * 的 user 消息，就地重答不重存（#936）。拒绝时不改历史，也不调用模型。
     */
    public String retryQuestion(UUID conversationId, UUID questionId) {
        RetryTargetRow target = conversations.selectRetryTarget(conversationId, questionId);
        if (target == null) {
            throw AppException.NOT_FOUND;
        }
        if (!"user".equals(target.role())) {
            throw AppException.invalid("retry_not_question",
                    "Only a question can be answered again");
        }
        if (!target.last()) {
            throw AppException.codedConflict("retry_answered",
                    "That question already has an answer, or the conversation went on after it");
        }
        return target.content();
    }

    /**
     * 一轮除了正文之外留下的东西（conversations.rs TurnRecord 五元组）。四个都是 JsonNode，
     * 散着传编译器帮不上忙——传错顺序会得到一条能落库、也能读回来、只是内容张冠李戴的记录。
     */
    public record TurnRecord(boolean stopped, JsonNode steps, JsonNode sources, JsonNode resolved,
                             JsonNode toolExchange) {

        /** 用户消息：四样都空。 */
        public static TurnRecord empty(com.fasterxml.jackson.databind.ObjectMapper mapper) {
            return new TurnRecord(false, mapper.createArrayNode(), mapper.createArrayNode(),
                    mapper.createArrayNode(), mapper.createArrayNode());
        }
    }

    /**
     * conversations.rs:288-319 append_message：消息 INSERT 与 updated_at 触碰同一事务——
     * 半途失败时不留「答了没浮上来」的会话。
     */
    public UUID appendMessage(UUID conversationId, String role, String content,
                              TurnRecord record) {
        UUID id = Uuids.nowV7();
        tx.executeWithoutResult(txState -> {
            conversations.insertMessage(id, conversationId, role, content, record.steps(),
                    record.sources(), record.resolved(), record.toolExchange(), record.stopped());
            conversations.touchConversation(conversationId);
        });
        return id;
    }

    /** 一轮回放的历史（conversations.rs:164-254 History）。 */
    public static final class History {

        /** 与 turns 平行的身份，调用方按落库 id 剔除自己刚存的那个问题。 */
        public final List<UUID> turnIds;
        /** (role, content)，按时间序；stopped 的助手消息加未完成前缀。 */
        public final List<String[]> turns;
        /** 这场对话里已经认下的实体（按 id 去重、首次出现序）。 */
        public final List<JsonNode> entities;
        /** 最近一轮助手做过什么：带 tool_calls 的助手消息与配套 tool 结果——只回放最近一轮。 */
        public final List<JsonNode> lastToolExchange;
        /** 同一条助手消息的来源（#943）：不查东西的改写沿用那条回答的 [n]。 */
        public final List<JsonNode> lastSources;

        History(List<UUID> turnIds, List<String[]> turns, List<JsonNode> entities,
                List<JsonNode> lastToolExchange, List<JsonNode> lastSources) {
            this.turnIds = turnIds;
            this.turns = turns;
            this.entities = entities;
            this.lastToolExchange = lastToolExchange;
            this.lastSources = lastSources;
        }
    }

    /**
     * conversations.rs:206-254 recent_context：最近 n 条消息的正文/实体/最近一轮工具往返。
     * stopped 的助手消息回放加前缀 {@code [This response was stopped by the user before
     * completion.]}——模型上下文要另说一句「回答未完成」（#934）。
     */
    public History recentContext(UUID conversationId, int n) {
        List<ContextRow> rows = new ArrayList<>(conversations.selectRecentContext(conversationId, n));
        java.util.Collections.reverse(rows);
        List<UUID> ids = new ArrayList<>();
        List<String[]> turns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<JsonNode> entities = new ArrayList<>();
        ContextRow lastAssistant = null;
        for (ContextRow row : rows) {
            ids.add(row.id());
            String content = row.content();
            if (row.stopped()) {
                content = "[This response was stopped by the user before completion.]\n\n"
                        + content;
            }
            turns.add(new String[]{row.role(), content});
            for (JsonNode e : toList(row.resolved())) {
                String id = e.path("id").asText(null);
                if (id != null && seen.add(id)) {
                    entities.add(e);
                }
            }
            if ("assistant".equals(row.role())) {
                lastAssistant = row;
            }
        }
        List<JsonNode> exchange = toList(lastAssistant == null ? null
                : lastAssistant.toolExchange());
        List<JsonNode> sources = toList(lastAssistant == null ? null : lastAssistant.sources());
        return new History(ids, turns, entities, exchange, sources);
    }

    private static List<JsonNode> toList(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(out::add);
        }
        return out;
    }

    private static String truncateChars(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.codePoints().count() <= max) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        s.codePoints().limit(max).forEach(out::appendCodePoint);
        return out.toString();
    }

    public record ConversationPage(List<ConversationView> conversations, long total) {
    }
}
