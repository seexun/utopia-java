package tech.seexun.jknow.store.mapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * review 总览 mapper（逐字搬迁自旧仓库 review_summary.rs + governance.rs 的
 * agent_running/queue_len；file:line 见 ReviewSummaryMapper.xml）。窗口合成
 * （7 天/30 天两窗口的合并排序、14 天柱补零）在 ReviewService——对应 Rust 侧
 * decided() 的 window 闭包。
 */
public interface ReviewSummaryMapper {

    /** waiting 九档 count+oldest 一条查询（review_summary.rs:74-117）。 */
    WaitingRow selectWaiting(@Param("kbId") UUID kbId, @Param("below") float below);

    /** 办过的按动作（review_summary.rs:156-168；GROUP BY action+automatic，30 天窗内）。 */
    List<ActionRow> selectDecidedByAction(@Param("kbId") UUID kbId);

    /** 办过的按人（review_summary.rs:169-183；LIMIT $2=TOP_ACTORS 5）。 */
    List<ActorRow> selectDecidedByActor(@Param("kbId") UUID kbId, @Param("topActors") long topActors);

    /** 近 14 天逐日（review_summary.rs:184-194；$2=DAILY_DAYS-1=13）。 */
    List<DayRow> selectDecidedDays(@Param("kbId") UUID kbId, @Param("days") int days);

    /** agent 开着的建议数（review_summary.rs:277-282）。 */
    long selectAgentOpen(@Param("kbId") UUID kbId);

    /** agent 近期每状态两窗口（review_summary.rs:283-293）。 */
    List<AgentStatusRow> selectAgentStatusRows(@Param("kbId") UUID kbId);

    /** govern 任务此刻在跑（governance.rs:902-910；$1 绑 kb_id.to_string()）。 */
    boolean selectAgentRunning(@Param("kbId") String kbId);

    /** 等 agent 看的对（governance.rs:914-923 queue_len；OPEN_PROPOSAL 含 stage 过滤）。 */
    long selectAgentQueueLen(@Param("kbId") UUID kbId);

    /** 库的成色（review_summary.rs:318-342：contested=挂着 open 冲突的任一端，UNION 去重）。 */
    HealthRow selectHealth(@Param("kbId") UUID kbId, @Param("below") float below);

    // ---- 行记录 ----

    /** review_summary.rs:48-68 WaitingRow（九对 count/oldest）。 */
    record WaitingRow(
            long pending, OffsetDateTime pendingOldest,
            long duplicates, OffsetDateTime duplicatesOldest,
            long conflicts, OffsetDateTime conflictsOldest,
            long unconfirmed, OffsetDateTime unconfirmedOldest,
            long lowconf, OffsetDateTime lowconfOldest,
            long violations, OffsetDateTime violationsOldest,
            long defects, OffsetDateTime defectsOldest,
            long alignment, OffsetDateTime alignmentOldest,
            long errata, OffsetDateTime errataOldest) {
    }

    /** review_summary.rs:132-138 ActionRow。 */
    record ActionRow(String action, boolean automatic, long last7d, long last30d) {
    }

    /** review_summary.rs:140-146 ActorRow。 */
    record ActorRow(UUID actorId, String actorLabel, long last7d, long last30d) {
    }

    /** review_summary.rs:148-152 DayRow。 */
    record DayRow(LocalDate day, long count) {
    }

    /** review_summary.rs:267-272 AgentRow（按状态）。 */
    record AgentStatusRow(String status, long last7d, long last30d) {
    }

    /** review_summary.rs:259-265 HealthRow。 */
    record HealthRow(long facts, long lowConfidence, long unconfirmed, long contested) {
    }
}
