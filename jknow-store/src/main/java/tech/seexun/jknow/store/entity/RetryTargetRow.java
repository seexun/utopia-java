package tech.seexun.jknow.store.entity;

/**
 * 重答（#936）要找的那条消息（conversations.rs:129-133 RetryTarget）：它的角色、正文，
 * 以及它是不是这场对话的最后一条。回答与问题之间没有连线，靠的是顺序（created_at，
 * 同一刻再按 id）——「还没有回答」只能这样判断：这一问后面什么也没有。
 */
public record RetryTargetRow(String role, String content, boolean last) {
}
