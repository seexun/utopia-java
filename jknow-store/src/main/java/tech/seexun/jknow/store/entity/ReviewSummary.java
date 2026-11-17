package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 审核台总览（models.rs:1528-1635 ReviewSummary 全族 serde 形状；四段聚合一次返回，
 * review_summary.rs:33-46）。waiting 九档与 counts 同一套 WHERE（各表自己的时钟取「最老」）；
 * decided 读审计台账（review./fact./conflict./merge. 四族）近 7/30 天两窗口 + 近 14 天逐日柱
 * （缺的天补 0——柱子要等距）；health 只数在世事实；agent 按现在的状态数。
 */
public record ReviewSummary(
        ReviewWaiting waiting,
        ReviewDecided decided,
        ReviewHealth health,
        ReviewAgent agent) {

    /** 一档队列：多少条、最老的一条从什么时候开始等。 */
    public record QueueWait(long count, OffsetDateTime oldestAt) {
    }

    /** 九档队列（与 counts 同口径 + oldest）。 */
    public record ReviewWaiting(
            QueueWait pending,
            QueueWait duplicates,
            QueueWait conflicts,
            QueueWait unconfirmed,
            QueueWait lowconf,
            QueueWait violations,
            QueueWait defects,
            QueueWait alignment,
            QueueWait errata) {
    }

    /** 一个窗口里的决定：总数、AI 自裁数、按动作、按人。 */
    public record DecidedWindow(
            long total,
            long automatic,
            List<ActionCount> byAction,
            List<ActorCount> byActor) {
    }

    public record ActionCount(String action, long count) {
    }

    /** actor_id null = 后台自裁。 */
    public record ActorCount(UUID actorId, String label, long count) {
    }

    public record DecidedDay(LocalDate day, long count) {
    }

    public record ReviewDecided(
            /** Rust 字段名 last_7d（数字开头的下划段不会被 SNAKE_CASE 策略生成，显式钉名）。 */
            @JsonProperty("last_7d") DecidedWindow last7d,
            @JsonProperty("last_30d") DecidedWindow last30d,
            List<DecidedDay> daily) {
    }

    public record ReviewHealth(
            long facts,
            long lowConfidence,
            long unconfirmed,
            long contested) {
    }

    public record AgentWindow(
            long applied,
            long proposed,
            long accepted,
            long overridden,
            long reverted) {
    }

    public record ReviewAgent(
            boolean running,
            long queue,
            long open,
            /** 同 ReviewDecided：数字下标段显式钉 Rust 键名。 */
            @JsonProperty("last_7d") AgentWindow last7d,
            @JsonProperty("last_30d") AgentWindow last30d) {
    }
}
