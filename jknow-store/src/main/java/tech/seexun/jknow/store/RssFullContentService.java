package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.entity.RssEntries;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.mapper.RssFullContentMapper;

/**
 * RSS 全文台账（rss_full_content.rs 全量对等，M3 任务 9）：opt-in 全文来源的持久发现
 * 与补全状态。台账与 documents <b>刻意分开</b>：一条 feed 条目可以在拿到可接受内容
 * 之前被发现，也必须在离开出版方滑动窗口之后保持可补全。
 *
 * <p>执行与文档状态<b>不重复持久化</b>：attempts 与执行迁移归通用任务队列，文档归
 * documents——这里只有观察（observation）与准入（admission）。发布事务
 * {@link #completeHydration} 在同一把源锁（{@code FOR UPDATE} of sources）下串行，
 * 按「源 / 代际 / 身份 / 库 / 运行中的 job / 不可变的删除前置」六重匹配才落文档。
 */
@Service
public class RssFullContentService {

    /** rss_full_content.rs:12 HYDRATION_JOB_KIND。 */
    public static final String HYDRATION_JOB_KIND = "hydrate_rss_entry";

    /** ingest_sources.rs:21 RSS_MAX_INFLIGHT。 */
    public static final int RSS_MAX_INFLIGHT = 25;

    /** ingest_sources.rs:22 / rss_full_content.rs:115 HYDRATION_ATTEMPTS。 */
    public static final int RSS_HYDRATION_ATTEMPTS = 5;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RssFullContentMapper rss;
    private final DocumentsWriteMapper documents;
    private final DocumentsWriteService writes;
    private final JobsService jobs;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public RssFullContentService(RssFullContentMapper rss, DocumentsWriteMapper documents,
                                 DocumentsWriteService writes, JobsService jobs,
                                 org.springframework.transaction.PlatformTransactionManager tm) {
        this.rss = rss;
        this.documents = documents;
        this.writes = writes;
        this.jobs = jobs;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(tm);
    }

    /** counts 的完整产出（rss_full_content.rs:147-159 Counts）。 */
    public record Counts(String activationState, int activationGeneration, int baselineCount,
                         java.time.OffsetDateTime lastDiscoveryAt, long pendingCount,
                         long queuedCount, long hydratingCount, long retryingCount,
                         long completeCount, long terminalCount) {
    }

    /** rss_full_content.rs:196-201 get_activation。 */
    public RssFullContentMapper.Activation getActivation(UUID sourceId) {
        return rss.selectActivation(sourceId);
    }

    /**
     * 抓取前的快照（rss_full_content.rs:206-233 begin_feed_observation）：把网络证据绑在
     * 配置/代际上再开始 I/O。用库时间——不比较两台机器的钟；取在 fetch 之前是保守的：
     * fetch 期间的删除要求一次新的 fetch。
     */
    @Transactional
    public FeedObservation beginFeedObservation(UUID sourceId, JsonNode expectedConfig) {
        String raw = rss.selectConfigForUpdate(sourceId);
        JsonNode config;
        try {
            config = raw == null ? null : JSON.readTree(raw);
        } catch (Exception e) {
            throw AppException.conflict("RSS configuration changed before fetch");
        }
        if (config == null || !jsonEq(config.get("feed_url"), expectedConfig.get("feed_url"))
                || !jsonEq(config.get("content_mode"), expectedConfig.get("content_mode"))) {
            throw AppException.conflict("RSS configuration changed before fetch");
        }
        RssFullContentMapper.Activation activation = rss.selectActivation(sourceId);
        if (activation == null) {
            throw AppException.NOT_FOUND;
        }
        java.time.OffsetDateTime observedAt = rss.clockTimestamp();
        return new FeedObservation(activation, observedAt);
    }

    /** begin_feed_observation 的产出。 */
    public record FeedObservation(RssFullContentMapper.Activation activation,
                                  java.time.OffsetDateTime observedAt) {
    }

    private static boolean jsonEq(JsonNode a, JsonNode b) {
        return a == null ? b == null || b.isNull() : a.equals(b);
    }

    /**
     * 基线（rss_full_content.rs:281-306 record_baseline）：pending 态的第一次观察只记
     * 「这些已经存在」，不摄入——防的是把历史存量灌进库。
     */
    @Transactional
    public int recordBaseline(UUID sourceId, int generation, List<RssEntries.NewEntry> entries) {
        RssFullContentMapper.LockState locked = rss.lockActivation(sourceId);
        if (locked == null || !"pending".equals(locked.activationState())
                || locked.activationGeneration() != generation) {
            throw AppException.conflict("RSS activation changed before baseline");
        }
        int inserted = 0;
        for (RssEntries.NewEntry e : entries) {
            inserted += rss.insertBaseline(Uuids.nowV7(), sourceId, generation,
                    e.externalKey(), e.title());
        }
        rss.markBaselined(sourceId);
        return inserted;
    }

    /**
     * 发现（rss_full_content.rs:320-389 discover_observed 逐字控制流）：新键插入，
     * 旧键只在更晚的观察下刷新；纯摘要的刷新不算留存正文的更晚证据。
     */
    @Transactional
    public RssEntries.DiscoveryStats discoverObserved(UUID sourceId, int generation,
                                                      List<RssEntries.NewEntry> entries,
                                                      java.time.OffsetDateTime observedAt) {
        RssFullContentMapper.LockState locked = rss.lockActivation(sourceId);
        if (locked == null || !"active".equals(locked.activationState())
                || locked.activationGeneration() != generation) {
            throw AppException.conflict("RSS activation changed before discovery");
        }
        int discovered = 0;
        int terminal = 0;
        for (RssEntries.NewEntry e : entries) {
            String kind = e.hasUsableSource() ? "candidate" : "no_source";
            int n = rss.insertDiscovered(Uuids.nowV7(), sourceId, generation, e.externalKey(),
                    e.title(), e.articleUrl(), e.summary(), e.embeddedHtml(), e.docTime(), kind,
                    observedAt);
            if (n > 0) {
                discovered++;
                if (!e.hasUsableSource()) {
                    terminal++;
                }
                continue;
            }
            rss.clearJobIdIfDeletionInvalidated(sourceId, generation, e.externalKey(), observedAt,
                    e.hasUsableSource());
            rss.refreshObserved(sourceId, generation, e.externalKey(), e.title(), e.articleUrl(),
                    e.summary(), e.embeddedHtml(), e.docTime(), observedAt, e.hasUsableSource());
        }
        return new RssEntries.DiscoveryStats(discovered, terminal);
    }

    /** rss_full_content.rs:308-318 discover：库时间由服务端取。 */
    @Transactional
    public RssEntries.DiscoveryStats discover(UUID sourceId, int generation,
                                              List<RssEntries.NewEntry> entries) {
        return discoverObserved(sourceId, generation, entries, rss.clockTimestamp());
    }

    /**
     * 领取并入队（rss_full_content.rs:391-431 claim_pending_and_enqueue）：在飞 ≤25、
     * 尝试 ≤5；激活变了（编辑/换 feed_url）静默返回 0——旧代际的观察不再准入。
     */
    @Transactional
    public int claimPendingAndEnqueue(UUID sourceId, int generation, int maxInflight,
                                      int maxAttempts) {
        RssFullContentMapper.LockState locked = rss.lockActivation(sourceId);
        if (locked == null || !"active".equals(locked.activationState())
                || locked.activationGeneration() != generation) {
            return 0;
        }
        long live = rss.countLiveJobs(sourceId, generation);
        long capacity = Math.max(0, Math.max(0, Math.min(maxInflight, 25)) - live);
        List<RssEntries.ClaimRow> entries = rss.selectClaimable(sourceId, generation,
                (int) capacity);
        for (RssEntries.ClaimRow entry : entries) {
            var payload = JSON.createObjectNode();
            payload.put("source_id", sourceId.toString());
            payload.put("rss_entry_id", entry.id().toString());
            payload.put("observed_at", entry.observedAt().toString());
            long job = jobs.enqueueWithMaxAttemptsTx(HYDRATION_JOB_KIND, payload, maxAttempts);
            rss.setCurrentJob(entry.id(), job);
        }
        return entries.size();
    }

    /** rss_full_content.rs:433-438 get_entry。 */
    public RssEntries.Entry getEntry(UUID entryId) {
        return rss.selectEntry(entryId);
    }

    /** rss_full_content.rs:440-453 current_attempt。 */
    public Integer currentAttempt(UUID entryId, long jobId) {
        return rss.selectCurrentAttempt(entryId, jobId);
    }

    /**
     * 发布（rss_full_content.rs:456-523 complete_hydration 逐字控制流）：闸内匹配才
     * 落文档；接受的内容归文档，job 收 done；闸外（源编辑/迟到的 worker/删除栅栏）
     * 返回 empty 不写。文档落库复用普通三路判定的事务形状（upsert_source_document_tx：
     * 墓碑走普通恢复事务，不是第二套 RSS 专用的墓碑更新——调用方持有源围栏）。
     */
    @Transactional
    public Optional<UUID> completeHydration(UUID entryId, long jobId, UUID sourceId,
                                            int generation, UUID kbId, String externalKey,
                                            String filename, String mime, long sizeBytes,
                                            String contentSha256,
                                            java.time.OffsetDateTime docTime) {
        RssFullContentMapper.LockState locked = rss.lockActivation(sourceId);
        if (locked == null || !"active".equals(locked.activationState())
                || locked.activationGeneration() != generation) {
            return Optional.empty();
        }
        if (rss.selectPublishable(entryId, jobId, sourceId, generation, externalKey, kbId)
                == null) {
            return Optional.empty();
        }
        UUID documentId = upsertSourceDocumentTx(kbId, sourceId, externalKey, filename, mime,
                sizeBytes, contentSha256, docTime);
        rss.finishJob(jobId);
        rss.clearEntryPayload(entryId);
        return Optional.of(documentId);
    }

    /** rss_full_content.rs:500-511 → documents.rs:236-410 upsert_source_document_tx 的事务形状。 */
    private UUID upsertSourceDocumentTx(UUID kbId, UUID sourceId, String externalKey,
                                        String filename, String mime, long sizeBytes,
                                        String sha256, java.time.OffsetDateTime docTime) {
        UUID deleted = documents.selectDeletedIdByKey(sourceId, externalKey);
        if (deleted != null) {
            writes.restore(kbId, deleted);
        }
        DocumentRow existing = documents.findByExternalKeyForUpdate(sourceId, externalKey);
        if (existing != null) {
            if (sha256.equals(existing.sha256())) {
                return existing.id();
            }
            writes.replaceContentAndEnqueue(existing.id(), filename, mime, sizeBytes, sha256,
                    docTime);
            return existing.id();
        }
        DocumentRow doc = writes.createWithVersion(kbId, filename, mime, sizeBytes, sha256,
                sourceId, docTime, "source", externalKey, false);
        return doc.id();
    }

    /** rss_full_content.rs:525-532 list_current_entries。 */
    public List<RssEntries.Entry> listCurrentEntries(UUID sourceId, long limit) {
        return rss.selectCurrentEntries(sourceId, (int) Math.max(1, Math.min(100, limit)));
    }

    /** rss_full_content.rs:534-555 counts。 */
    public Counts counts(UUID sourceId) {
        RssFullContentMapper.Activation a = getActivation(sourceId);
        if (a == null) {
            return null;
        }
        RssEntries.CountsRow row = rss.selectCounts(sourceId, a.activationGeneration());
        return new Counts(a.activationState(), a.activationGeneration(), a.baselineCount(),
                a.lastDiscoveryAt(), row.pendingCount(), row.queuedCount(), row.hydratingCount(),
                row.retryingCount(), row.completeCount(), row.terminalCount());
    }

    /**
     * RSS 自己的失败重试（rss_full_content.rs:16-93 requeue_failed）：失败的补全任务经
     * 与首准入<b>同一把源锁、代际、purge 栅栏与容量上限</b>重新收进来——通用 requeue
     * 排除 hydrate_rss_entry，就是在等这里。<b>每个来源一个小事务</b>（Rust 同形）：
     * 锁不放一个源等另一个源。
     */
    public int requeueFailed(JobsService.RequeueScope scope) {
        if (scope.kind() != null && !HYDRATION_JOB_KIND.equals(scope.kind())) {
            return 0;
        }
        int count = 0;
        for (UUID sourceId : rss.selectRequeueSources(scope.kbId(), scope.failedSince())) {
            count += tx.execute(status -> requeueSource(sourceId, scope));
        }
        return count;
    }

    private int requeueSource(UUID sourceId, JobsService.RequeueScope scope) {
        rss.lockSource(sourceId);
        Integer generation = rss.selectRequeueGeneration(sourceId);
        if (generation == null) {
            return 0;
        }
        long live = rss.countLiveRequeueJobs(sourceId, generation);
        int capacity = (int) Math.max(0, 25 - live);
        List<Long> jobIds = rss.selectRequeueJobs(sourceId, generation, scope.failedSince(),
                capacity);
        if (jobIds.isEmpty()) {
            return 0;
        }
        int requeued = rss.requeueJobBatch(jobIds);
        if (requeued > 0) {
            jobs.notifyWorkerTx();
        }
        return requeued;
    }
}
