package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.SourceRow;
import tech.seexun.jknow.store.entity.SourceView;
import tech.seexun.jknow.store.entity.SyncRunView;

/**
 * 来源域 mapper（M2 任务 4）：CRUD/列表（doc_count+missing_count 子查询+RSS LATERAL）/
 * 调度（mark_queued 幂等/mark_running/finish_sync/due_sources 60s tick）/运行记录
 * （start_run/finish_run+每源修剪 50 条/list_runs）/推送密钥（set_ingest_token）。
 * SQL 见 resources/mapper/SourcesMapper.xml——逐字搬迁自旧仓库
 * crates/utopia-store/src/sources.rs（+rss_full_content.rs/alerts.rs 引文），每条带 file:line。
 */
@Mapper
public interface SourcesMapper {

    /** sources.rs:154 SELECT * WHERE id；出库的封印由 SourcesService.opened 统一开封。 */
    SourceRow selectById(@Param("id") UUID id);

    /** sources.rs:263 更新前读现值（FOR UPDATE：keep_secrets 合并与 rss 开关切换要原子）。 */
    SourceRow selectByIdForUpdate(@Param("id") UUID id);

    /** sources.rs:202-204 INSERT ... RETURNING *。 */
    SourceRow insertSource(@Param("id") UUID id, @Param("kbId") UUID kbId,
                           @Param("kind") String kind, @Param("name") String name,
                           @Param("config") com.fasterxml.jackson.databind.JsonNode config,
                           @Param("icon") String icon,
                           @Param("syncIntervalMinutes") Integer syncIntervalMinutes,
                           @Param("syncCron") String syncCron);

    /**
     * sources.rs:285-291 UPDATE：name/config/icon COALESCE，调度两列 CASE WHEN $5
     * （schedule 字段出现即整体覆盖——两列同开关，避免半套调度）。
     */
    SourceRow updateSource(@Param("id") UUID id, @Param("name") String name,
                           @Param("config") com.fasterxml.jackson.databind.JsonNode config,
                           @Param("icon") String icon,
                           @Param("scheduleTouched") boolean scheduleTouched,
                           @Param("syncIntervalMinutes") Integer syncIntervalMinutes,
                           @Param("syncCron") String syncCron);

    /** sources.rs:312 DELETE；0 行=NotFound（documents.source_id FK SET NULL 落回 Uploads）。 */
    int deleteSource(@Param("id") UUID id);

    /** sources.rs:89-135 列表（凭据键剔除在 Java 侧等价 config - text[]，见 XML 注）。 */
    List<SourceView> selectViewsForKb(@Param("kbId") UUID kbId);

    /** sources.rs:224 set_ingest_token（封印后的密文传入；0 行=NotFound 由调用方判）。 */
    int setIngestToken(@Param("id") UUID id, @Param("token") String token);

    /** sources.rs:326-331 到期名单（interval 型 SQL 内判；cron 型取回后由服务层过滤）。 */
    List<SourceRow> selectDueSources();

    /** sources.rs:357-359 mark_queued（幂等：已在队列/运行中 0 行）。 */
    int markQueued(@Param("id") UUID id);

    /** sources.rs:367 mark_running。 */
    int markRunning(@Param("id") UUID id);

    /** sources.rs:385-388 finish_sync 收尾 RETURNING kb_id, name（0 行=来源已不在）。 */
    SyncFinishRow finishSync(@Param("id") UUID id, @Param("status") String status,
                             @Param("error") String error, @Param("added") int added);

    /** sources.rs:386 RETURNING 两列。 */
    record SyncFinishRow(UUID kbId, String name) {
    }

    /** sources.rs:423 touch_sync_time（长同步防调度器重复触发）。 */
    int touchSyncTime(@Param("id") UUID id, @Param("at") OffsetDateTime at);

    /** sources.rs:441-443 上次成功 run 的开始时刻（github/jira/custom 的增量游标）。 */
    OffsetDateTime selectLastSuccessfulSyncStart(@Param("sourceId") UUID sourceId);

    /** sources.rs:451 start_run。 */
    void startRun(@Param("id") UUID id, @Param("sourceId") UUID sourceId);

    /** sources.rs:467-473 finish_run。 */
    int finishRun(@Param("id") UUID id, @Param("status") String status,
                  @Param("error") String error, @Param("createdDocs") int createdDocs,
                  @Param("updatedDocs") int updatedDocs);

    /** sources.rs:480-484 每来源只留最近 50 条。 */
    int trimRuns(@Param("sourceId") UUID sourceId);

    /** sources.rs:492-495 运行历史。 */
    List<SyncRunView> selectRuns(@Param("sourceId") UUID sourceId, @Param("limit") long limit);

    /**
     * alerts.rs:89-108 raise 的 INSERT（SOURCE_SYNC_FAILED 一族）：
     * finish_sync 失败路径的告警入账（一次故障一条，写完不再变）。
     */
    void insertAlert(@Param("id") UUID id, @Param("kbId") UUID kbId,
                     @Param("severity") String severity, @Param("kind") String kind,
                     @Param("minRole") String minRole, @Param("subjectType") String subjectType,
                     @Param("subjectId") UUID subjectId,
                     @Param("detail") com.fasterxml.jackson.databind.JsonNode detail);

    /** rss_full_content.rs:179 initialize_source（建源即开第一代）。 */
    int initializeRssGeneration(@Param("id") UUID id);

    /** rss_full_content.rs:187 enable_source（代际自增、基线清空——feed_url 换了重新发现）。 */
    int advanceRssGeneration(@Param("id") UUID id);
}
