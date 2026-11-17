package tech.seexun.jknow.store.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.JobStatus;

/**
 * 任务队列 mapper（对齐旧仓库 crates/utopia-store/src/jobs.rs，SQL 见 resources/mapper/）。
 * 单语句层：多语句编排（入队+notify 同事务、退避判定）在 JobsService；
 * 事务边界语义（_tx 变体加入调用方事务）由 Service 的传播属性承担。
 */
@Mapper
public interface JobsMapper {

    /** jobs.rs:39-64 enqueue_unless_pending 的 INSERT（挡 queued+running；after 去抖）。null=已有一条。 */
    Long insertUnlessPending(@Param("kind") String kind, @Param("payload") JsonNode payload,
                             @Param("afterSecs") double afterSecs);

    /** jobs.rs:80-99 enqueue_unless_queued_tx 的 INSERT（只挡 queued）。null=已有一条。 */
    Long insertUnlessQueuedTx(@Param("kind") String kind, @Param("payload") JsonNode payload);

    /** jobs.rs:103-126 enqueue_unless_queued_after 的 INSERT（挡 queued、晚跑）。null=已有一条。 */
    Long insertUnlessQueuedAfter(@Param("kind") String kind, @Param("payload") JsonNode payload,
                                 @Param("afterSecs") double afterSecs);

    /** jobs.rs:130-140 pending_for_kb（按 payload 里的 kb_id 看，不比整份 payload）。 */
    Boolean selectPendingForKb(@Param("kind") String kind, @Param("kbId") String kbId);

    /** jobs.rs:177-184 enqueue_with_max_attempts_tx 的 INSERT。 */
    long insertWithMaxAttemptsTx(@Param("kind") String kind, @Param("payload") JsonNode payload,
                                 @Param("maxAttempts") int maxAttempts);

    /** jobs.rs:192-198 notify_worker_tx：SELECT pg_notify(channel,'')——提交时投递。 */
    int notifyWorker(@Param("channel") String channel);

    /** jobs.rs:344-360 claim_one：SKIP LOCKED 认领（attempts 已 +1）。null=无到期任务。 */
    Job claimOne();

    /** jobs.rs:362-374 mark_done：成功要把上一次的错清掉。 */
    int markDone(@Param("id") long id);

    /** jobs.rs:424-431 Terminal 落 failed（一次就到此为止）。 */
    int markFailedTerminal(@Param("id") long id, @Param("error") String error);

    /**
     * jobs.rs:446-462 Deferred 挂回 queued（attempts 退回、不烧预算、窗口守卫）。
     * 返回受影响行数：0=窗口已过，调用方落到普通退避。
     */
    int markFailedDeferred(@Param("id") long id, @Param("error") String error,
                           @Param("secs") double secs, @Param("windowSecs") double windowSecs);

    /** jobs.rs:477-487 普通失败退避挂回 queued。 */
    int markFailedBackoff(@Param("id") long id, @Param("error") String error,
                          @Param("backoffSecs") double backoffSecs);

    /** jobs.rs:468-475 预算耗尽落 failed。 */
    int markFailedExhausted(@Param("id") long id, @Param("error") String error);

    /** jobs.rs:280-303 requeue_failed（排 hydrate_rss_entry 之外的全部 failed；attempts 归零）。 */
    int requeueFailed(@Param("kind") String kind, @Param("failedSince") OffsetDateTime failedSince,
                      @Param("kbId") UUID kbId);

    /** jobs.rs:306-313 failed_count（KB_SCOPE 圈定；null=不限库）。 */
    long failedCount(@Param("kbId") UUID kbId);

    /** jobs.rs:330-341 status_in_kb：属于这个库才回行（授权跟库走）。 */
    JobStatus statusInKb(@Param("id") long id, @Param("kbId") UUID kbId);

    /** jobs.rs:503-506 孤儿回收：启动时仍 running 的必是上次进程退出遗留，一律重排队。 */
    int recoverOrphans();
}
