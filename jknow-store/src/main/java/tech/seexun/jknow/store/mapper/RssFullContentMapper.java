package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.RssEntries;

/**
 * RSS 全文台账 SQL 面（rss_full_content.rs 全量对等，M3 任务 9）——SQL 在
 * RssFullContentMapper.xml，全部逐字搬迁（含 ENTRY_SELECT 投影与 actived 锁判定）。
 */
public interface RssFullContentMapper {

    /** 激活面（rss_full_content.rs:96-103 Activation）。 */
    record Activation(UUID sourceId, int activationGeneration, String activationState,
                      OffsetDateTime activationAt, int baselineCount,
                      OffsetDateTime lastDiscoveryAt) {
    }

    /** get_activation（:196-201）。 */
    Activation selectActivation(@Param("sourceId") UUID sourceId);

    /** begin_feed_observation 的行锁读 config（:213-216）。 */
    String selectConfigForUpdate(@Param("sourceId") UUID sourceId);

    /** :228-230 SELECT clock_timestamp()——用库时间，不比较两台机器的钟。 */
    OffsetDateTime clockTimestamp();

    /** lock_activation（:269-279）：(activation_state, rss_generation) FOR UPDATE。 */
    LockState lockActivation(@Param("sourceId") UUID sourceId);

    /** lock_activation 的两列产物。 */
    record LockState(String activationState, int activationGeneration) {
    }

    /** record_baseline 的单条 INSERT（:295-298）——返回插入行数。 */
    int insertBaseline(@Param("id") UUID id, @Param("sourceId") UUID sourceId,
                       @Param("generation") int generation,
                       @Param("externalKey") String externalKey, @Param("title") String title);

    /** :300-303 UPDATE sources SET rss_baselined_at=clock_timestamp()。 */
    int markBaselined(@Param("sourceId") UUID sourceId);

    /** discover 的单条 INSERT（:340-345）——返回插入行数。 */
    int insertDiscovered(@Param("id") UUID id, @Param("sourceId") UUID sourceId,
                         @Param("generation") int generation,
                         @Param("externalKey") String externalKey,
                         @Param("title") String title, @Param("articleUrl") String articleUrl,
                         @Param("summary") String summary,
                         @Param("embeddedHtml") String embeddedHtml,
                         @Param("docTime") OffsetDateTime docTime,
                         @Param("kind") String kind,
                         @Param("observedAt") OffsetDateTime observedAt);

    /** discover 的清 job 归属 UPDATE（:356-363）。 */
    int clearJobIdIfDeletionInvalidated(@Param("sourceId") UUID sourceId,
                                        @Param("generation") int generation,
                                        @Param("externalKey") String externalKey,
                                        @Param("observedAt") OffsetDateTime observedAt,
                                        @Param("hasUsableSource") boolean hasUsableSource);

    /** discover 的观察刷新 UPDATE（:365-384）。 */
    int refreshObserved(@Param("sourceId") UUID sourceId,
                        @Param("generation") int generation,
                        @Param("externalKey") String externalKey,
                        @Param("title") String title, @Param("articleUrl") String articleUrl,
                        @Param("summary") String summary,
                        @Param("embeddedHtml") String embeddedHtml,
                        @Param("docTime") OffsetDateTime docTime,
                        @Param("observedAt") OffsetDateTime observedAt,
                        @Param("hasUsableSource") boolean hasUsableSource);

    /** claim 的在飞计数（:402-409）。 */
    long countLiveJobs(@Param("sourceId") UUID sourceId, @Param("generation") int generation);

    /** claim 的候选行（:411-417）——(id, observed_at) FOR UPDATE。 */
    List<RssEntries.ClaimRow> selectClaimable(@Param("sourceId") UUID sourceId,
                                              @Param("generation") int generation,
                                              @Param("limit") int limit);

    /** :426-427 领取即挂 job。 */
    int setCurrentJob(@Param("id") UUID id, @Param("jobId") long jobId);

    /** get_entry（:434-437）。 */
    RssEntries.Entry selectEntry(@Param("id") UUID id);

    /** current_attempt（:442-453）——claim check，不是第二套状态机。 */
    Integer selectCurrentAttempt(@Param("id") UUID id, @Param("jobId") long jobId);

    /** complete_hydration 的发布闸（:478-497）。 */
    UUID selectPublishable(@Param("id") UUID id, @Param("jobId") long jobId,
                           @Param("sourceId") UUID sourceId,
                           @Param("generation") int generation,
                           @Param("externalKey") String externalKey,
                           @Param("kbId") UUID kbId);

    /** :512-514 job 收 done。 */
    int finishJob(@Param("jobId") long jobId);

    /** :519-520 接受的内容归文档/blob，不留在观察行上。 */
    int clearEntryPayload(@Param("id") UUID id);

    /** list_current_entries（:525-531）。 */
    List<RssEntries.Entry> selectCurrentEntries(@Param("sourceId") UUID sourceId,
                                                @Param("limit") int limit);

    /** counts（:538-542）。 */
    RssEntries.CountsRow selectCounts(@Param("sourceId") UUID sourceId,
                                      @Param("generation") int generation);

    // ---- requeue_failed（:16-93，RSS 自己的准入重试） ----

    /** :23-35 失败 hydration 任务的来源名单。 */
    List<UUID> selectRequeueSources(@Param("kbId") UUID kbId,
                                    @Param("failedSince") OffsetDateTime failedSince);

    /** :38-40 锁来源行。 */
    UUID lockSource(@Param("sourceId") UUID sourceId);

    /** :42-49 源是否仍处于全文模式且已有基线（返回代际，null=不是）。 */
    Integer selectRequeueGeneration(@Param("sourceId") UUID sourceId);

    /** requeue 的在飞计数（:52-61）。 */
    long countLiveRequeueJobs(@Param("sourceId") UUID sourceId,
                              @Param("generation") int generation);

    /** :63-74 候选 job（FOR UPDATE OF e, j）。 */
    List<Long> selectRequeueJobs(@Param("sourceId") UUID sourceId,
                                 @Param("generation") int generation,
                                 @Param("failedSince") OffsetDateTime failedSince,
                                 @Param("limit") int limit);

    /** :77-85 放回队列（attempts 归零）。 */
    int requeueJobBatch(@Param("ids") List<Long> ids);
}
