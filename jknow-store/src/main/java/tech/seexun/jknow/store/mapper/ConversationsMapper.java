package tech.seexun.jknow.store.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.ContextRow;
import tech.seexun.jknow.store.entity.ConversationMessage;
import tech.seexun.jknow.store.entity.ConversationView;
import tech.seexun.jknow.store.entity.RetryTargetRow;

/**
 * 会话读 mapper（逐字搬迁自旧仓库 utopia-store/src/conversations.rs；
 * 每条 SQL 的 file:line 见 ConversationsMapper.xml；M5 任务 4 补写路径——
 * create/append/rename/delete/retry_target/recent_context）。
 */
public interface ConversationsMapper {

    /** conversations.rs:32-56 list：我的会话（标题与消息正文两处可搜）。 */
    List<ConversationView> selectConversations(@Param("kbId") UUID kbId,
                                               @Param("userId") UUID userId,
                                               @Param("q") String q,
                                               @Param("limit") long limit,
                                               @Param("offset") long offset);

    /** conversations.rs:58-66 list 总数（同一 WHERE）。 */
    long countConversations(@Param("kbId") UUID kbId, @Param("userId") UUID userId,
                            @Param("q") String q);

    /** conversations.rs:88-100 require_owned：归属校验（会话必须属于本 KB 本人）。 */
    UUID selectOwned(@Param("kbId") UUID kbId, @Param("userId") UUID userId,
                     @Param("conversationId") UUID conversationId);

    /** conversations.rs:102-112 messages：历史回放（steps/sources 随行）。 */
    List<ConversationMessage> selectMessages(@Param("conversationId") UUID conversationId);

    // ---- 写路径（M5 任务 4） ----

    /** conversations.rs:9-19 create：标题=第一问截 80 字（字符，非字节）。 */
    int insertConversation(@Param("id") UUID id, @Param("kbId") UUID kbId,
                           @Param("userId") UUID userId, @Param("title") String title);

    /** conversations.rs:69-97 rename：标题 1-120 字，非本人/本库 0 行（404 由服务层判）。 */
    int renameConversation(@Param("kbId") UUID kbId, @Param("userId") UUID userId,
                           @Param("conversationId") UUID conversationId,
                           @Param("title") String title);

    /** conversations.rs:321-338 delete：级联清消息（外键 ON DELETE CASCADE）。 */
    int deleteConversation(@Param("kbId") UUID kbId, @Param("userId") UUID userId,
                           @Param("conversationId") UUID conversationId);

    /**
     * conversations.rs:288-319 append_message 的消息 INSERT（事务另一半 touchConversation
     * 更新 updated_at——同一事务，见 ConversationsService.appendMessage）。
     */
    int insertMessage(@Param("id") UUID id, @Param("conversationId") UUID conversationId,
                      @Param("role") String role, @Param("content") String content,
                      @Param("steps") JsonNode steps, @Param("sources") JsonNode sources,
                      @Param("resolved") JsonNode resolved,
                      @Param("toolExchange") JsonNode toolExchange,
                      @Param("stopped") boolean stopped);

    /** conversations.rs:313-317 updated_at 触碰（append_message 同事务）。 */
    int touchConversation(@Param("conversationId") UUID conversationId);

    /**
     * conversations.rs:140-162 retry_target：重答找的那条消息（角色、正文、是否最后一条）。
     * 「还没有回答」只能按顺序判：这一问后面什么也没有。
     */
    RetryTargetRow selectRetryTarget(@Param("conversationId") UUID conversationId,
                                     @Param("messageId") UUID messageId);

    /**
     * conversations.rs:206-254 recent_context 的行读：created_at 倒序 LIMIT 后正序拼
     * （最近 n 条），resolved/tool_exchange/sources/stopped 全带。
     */
    List<ContextRow> selectRecentContext(@Param("conversationId") UUID conversationId,
                                         @Param("limit") long limit);
}
