package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.SourceRow;
import tech.seexun.jknow.store.entity.SourceView;
import tech.seexun.jknow.store.entity.SyncRunView;
import tech.seexun.jknow.store.mapper.SourcesMapper;
import tech.seexun.jknow.store.secrets.SecretsBox;
import tech.seexun.jknow.store.secrets.SourceSecretKeys;

/**
 * 来源域服务（逐字对齐旧仓库 crates/utopia-store/src/sources.rs 的编排层，M2 任务 4）：
 * 15 kind 白名单 CRUD（keep_secrets 合并在路由层、封印在这里）/cron+interval 互斥/
 * 列表（凭据键 Java 侧剔除——{@code config - text[]} 等价）/sync_now 幂等 mark_queued/
 * 运行记录（每源修剪 50 条）/推送密钥（{@code utp_} 封印存 ingest_token）/
 * due_sources 60s tick 名单（cron 型服务层求下一次触发再过滤）。
 */
@Service
public class SourcesService {

    /** memory.rs:15 MEMORY_SOURCE_KIND：每库自带的记忆来源，不可建不可删（0015）。 */
    public static final String MEMORY_SOURCE_KIND = "memory";

    /**
     * SourceKind（models.rs:269-288）全集 15 种——创建的白名单、同步的分派、前端的
     * 下拉框有测试对表，一张表出全部（sources.rs:15-17：手写第二张表的教训 #247）。
     */
    private static final List<String> ALL_KINDS = List.of(
            "folder", "url", "rss", "github_issues", "jira_issues", "s3", "azure_blob", "gcs",
            "webdav", "notion", "api", "statements", "custom", MEMORY_SOURCE_KIND, "upload");

    /** models.rs:304-306 creatable_by_hand：人能从界面建的——memory 与 upload 之外的全部。 */
    private static final List<String> CREATABLE_KINDS = ALL_KINDS.stream()
            .filter(k -> !MEMORY_SOURCE_KIND.equals(k) && !"upload".equals(k)).toList();

    /** alerts.rs:26 kind::SOURCE_SYNC_FAILED。 */
    public static final String ALERT_SOURCE_SYNC_FAILED = "source.sync_failed";

    /** alerts.rs:402-415：内容类给 editor——配置这个源的人更需要知道你的东西没进来。 */
    private static final String ALERT_MIN_ROLE_EDITOR = "editor";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SourcesMapper sources;
    private final SecretsBox box;

    public SourcesService(SourcesMapper sources, SecretsBox box) {
        this.sources = sources;
        this.box = box;
    }

    // ---- kind / config 判定 ----

    /** 创建白名单（sources.rs:18-20 creatable_kinds，顺序=枚举序减 memory/upload）。 */
    public static List<String> creatableKinds() {
        return CREATABLE_KINDS;
    }

    /** SourceKind::all 的对等：15 种全集（创建白名单/同步分派/前端下拉对这一张表，#247）。 */
    public static List<String> allKinds() {
        return ALL_KINDS;
    }

    /** 有推送密钥的来源（sources_routes.rs:141-143）：api 推文档、statements 推陈述（0054）。 */
    public static boolean hasPushToken(SourceRow source) {
        return "api".equals(source.kind()) || "statements".equals(source.kind());
    }

    /**
     * RSS 的 content_mode（sources.rs:45-63 逐字语义）：缺省 feed；只认
     * feed/full_new_items；值不是串或不在枚举内 → 422 rss_content_mode_invalid。
     */
    public static String rssContentMode(JsonNode config) {
        JsonNode value = config == null ? null : config.get("content_mode");
        if (value == null) {
            return "feed";
        }
        if (!value.isTextual()) {
            throw AppException.invalid("rss_content_mode_invalid",
                    "RSS content_mode must be feed or full_new_items");
        }
        return switch (value.asText()) {
            case "feed" -> "feed";
            case "full_new_items" -> "full_new_items";
            default -> throw AppException.invalid("rss_content_mode_invalid",
                    "RSS content_mode must be feed or full_new_items");
        };
    }

    /** sources.rs:65-70：只有 rss 且 mode=full_new_items 才有台账基线。 */
    public static boolean rssFullContentEnabled(String kind, JsonNode config) {
        return "rss".equals(kind) && "full_new_items".equals(rssContentMode(config));
    }

    // ---- 读 ----

    /** sources.rs:147-151 opened 逐字语义：config 凭据键 + ingest_token 出库即开封。 */
    public SourceRow opened(SourceRow row) {
        if (row == null) {
            return null;
        }
        ObjectNode config = asObject(row.config());
        if (config != null) {
            box.openJsonKeys(config, SourceSecretKeys.array());
        }
        return new SourceRow(row.id(), row.kbId(), row.kind(), row.name(), config, row.icon(),
                row.syncIntervalMinutes(), row.syncCron(), row.lastSyncAt(), row.lastSyncStatus(),
                row.lastSyncError(), row.lastSyncAdded(), box.openOpt(row.ingestToken()),
                row.createdAt());
    }

    /** sources.rs:153-159 get。 */
    public SourceRow get(UUID id) {
        SourceRow row = sources.selectById(id);
        if (row == null) {
            throw AppException.NOT_FOUND;
        }
        return opened(row);
    }

    /**
     * sources.rs:83-143 list：凭据键剔除（Rust 在 SQL 里 {@code config - $2::text[]}；
     * Java 侧等价实现——整键删除，开封与否结果相同，见 SourcesMapper.xml 注）。
     */
    public List<SourceView> list(UUID kbId) {
        List<SourceView> views = sources.selectViewsForKb(kbId);
        List<SourceView> stripped = new ArrayList<>(views.size());
        for (SourceView view : views) {
            ObjectNode config = asObject(view.config());
            if (config != null) {
                for (String key : SourceSecretKeys.KEYS) {
                    config.remove(key);
                }
            }
            stripped.add(new SourceView(view.id(), view.kind(), view.name(), config, view.icon(),
                    view.syncIntervalMinutes(), view.syncCron(), view.lastSyncAt(),
                    view.lastSyncStatus(), view.lastSyncError(), view.lastSyncAdded(),
                    view.docCount(), view.missingCount(), view.rssFullContent()));
        }
        return stripped;
    }

    // ---- create（sources.rs:161-220） ----

    /**
     * 建源：kind 白名单/name trim/cron 5 段与 interval 互斥（cron 优先）/config 的
     * null 规范化为空对象再封印（serde 缺省的 Value::Null 落 jsonb null，前端读
     * config.x 直接炸）；rss 开全文即同事务开第一代。返回开封行。
     */
    @Transactional
    public SourceRow create(UUID kbId, String kind, String name, JsonNode config, String icon,
                            Integer syncIntervalMinutes, String syncCron) {
        if (!CREATABLE_KINDS.contains(kind)) {
            throw AppException.validation(
                    "kind must be one of: " + String.join(", ", CREATABLE_KINDS));
        }
        if (name == null || name.trim().isEmpty()) {
            throw AppException.invalid("source_name_required", "Source name is required");
        }
        // 互斥：cron 优先（UI 只会传其一）
        String cronNorm = syncCron == null ? null : CronSupport.validate(syncCron);
        Integer interval = cronNorm != null ? null : syncIntervalMinutes;
        ObjectNode next = asObject(config);
        if (next == null) {
            next = JSON.createObjectNode();
        }
        box.sealJsonKeys(next, SourceSecretKeys.array());
        boolean fullContent = rssFullContentEnabled(kind, next);
        SourceRow row = sources.insertSource(Uuids.nowV7(), kbId, kind, name.trim(), next, icon,
                interval, cronNorm);
        if (fullContent) {
            sources.initializeRssGeneration(row.id());
        }
        return opened(row);
    }

    // ---- update（sources.rs:244-308；keep_secrets 合并在路由层，与 Rust 同位） ----

    /**
     * 更新：schedule 字段出现即整体覆盖（cron 优先互斥同 create）；config 无论传没传都
     * 走「新值（已 keep_secrets 合并）或现值 → 封印幂等」；rss 全文模式从关到开或
     * feed_url 变了 → 代际自增重发现。现值行锁（FOR UPDATE）保证判定与写同一瞬间的库。
     */
    @Transactional
    public SourceRow update(UUID id, String name, JsonNode config, String icon,
                            Integer syncIntervalMinutes, String syncCron, boolean scheduleTouched) {
        // 互斥校验先做（Rust 在进事务之前 validate；坏 cron 422 不吃行锁）
        String cronNorm = syncCron == null ? null : CronSupport.validate(syncCron);
        Integer interval = cronNorm != null ? null : syncIntervalMinutes;

        SourceRow previous = sources.selectByIdForUpdate(id);
        if (previous == null) {
            throw AppException.NOT_FOUND;
        }
        ObjectNode nextConfig;
        if (config != null) {
            // 显式传了 config：null/非对象规范化为空对象（serde null → json!({})，sources.rs:268-276）
            nextConfig = asObject(config);
            if (nextConfig == null) {
                nextConfig = JSON.createObjectNode();
            }
        } else {
            // 没传：沿用现值
            nextConfig = asObject(previous.config());
            if (nextConfig == null) {
                nextConfig = JSON.createObjectNode();
            }
        }
        box.sealJsonKeys(nextConfig, SourceSecretKeys.array());
        boolean oldFull = rssFullContentEnabled(previous.kind(), previous.config());
        boolean newFull = rssFullContentEnabled(previous.kind(), nextConfig);
        boolean feedUrlChanged = "rss".equals(previous.kind()) && oldFull && newFull
                && !feedUrlOf(previous.config()).equals(feedUrlOf(nextConfig));
        SourceRow row = sources.updateSource(id, name, nextConfig, icon, scheduleTouched,
                interval, cronNorm);
        if ((!oldFull && newFull) || feedUrlChanged) {
            sources.advanceRssGeneration(id);
        }
        return opened(row);
    }

    /** sources.rs:236-242：feed_url 去空白，空串当没配。 */
    private static String feedUrlOf(JsonNode config) {
        JsonNode value = config == null ? null : config.get("feed_url");
        if (value == null || !value.isTextual()) {
            return "";
        }
        return value.asText().trim();
    }

    // ---- delete / token（sources.rs:222-233/310-320） ----

    /** sources.rs:311-320：文档保留（source_id 置 NULL）；0 行=NotFound。 */
    public void delete(UUID id) {
        if (sources.deleteSource(id) == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    /** sources.rs:223-233：设 api 来源的推送密钥（封印存库；明文只在响应里出一次）。 */
    public void setIngestToken(UUID id, String token) {
        if (sources.setIngestToken(id, box.seal(token)) == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    // ---- 调度与同步收尾（sources.rs:322-429） ----

    /** sources.rs:355-364 mark_queued（幂等）：已在队列/运行中返回 false。 */
    public boolean markQueued(UUID id) {
        return sources.markQueued(id) > 0;
    }

    /** sources.rs:366-372。 */
    public void markRunning(UUID id) {
        sources.markRunning(id);
    }

    /**
     * 一次同步收尾（sources.rs:378-419 逐字语义）：失败记一条告警（内容类给 editor——
     * 配这个源的人更需要知道你的东西没进来；名字存一份，源删了告警还留得住），
     * 成功什么都不做——「现在好了没有」不是告警中心该回答的问题。返回「记了没有」，
     * 调用方据此决定要不要推事件。
     */
    public boolean finishSync(UUID id, String error, int added) {
        SourcesMapper.SyncFinishRow row = sources.finishSync(id, error == null ? "ok" : "failed",
                error, added);
        if (row == null || error == null) {
            return false;
        }
        ObjectNode detail = JSON.createObjectNode();
        detail.put("name", row.name());
        detail.put("error", error);
        sources.insertAlert(Uuids.nowV7(), row.kbId(), "error", ALERT_SOURCE_SYNC_FAILED,
                ALERT_MIN_ROLE_EDITOR, "source", id, detail);
        return true;
    }

    /** sources.rs:422-429 touch_sync_time。 */
    public void touchSyncTime(UUID id, OffsetDateTime at) {
        sources.touchSyncTime(id, at);
    }

    /** sources.rs:437-447：上次成功 run 的开始时刻（增量游标）。 */
    public OffsetDateTime lastSuccessfulSyncStart(UUID sourceId) {
        return sources.selectLastSuccessfulSyncStart(sourceId);
    }

    /**
     * 到期待同步的来源（sources.rs:324-352 逐字语义）：interval 型 SQL 里已判定；
     * cron 型这里求下一次触发时刻（基准=上次同步时刻，没同步过取创建时刻——错过的
     * 触发点在下一轮扫描补上）。
     */
    public List<SourceRow> dueSources() {
        List<SourceRow> due = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (SourceRow raw : sources.selectDueSources()) {
            SourceRow row = opened(raw);
            if (row.syncCron() == null || cronDue(row, now)) {
                due.add(row);
            }
        }
        return due;
    }

    private static boolean cronDue(SourceRow row, OffsetDateTime now) {
        OffsetDateTime anchor = row.lastSyncAt() != null ? row.lastSyncAt() : row.createdAt();
        OffsetDateTime next = CronSupport.nextAfter(row.syncCron(), anchor);
        return next != null && !next.isAfter(now);
    }

    // ---- 运行记录（sources.rs:449-502；每源修剪 50 条） ----

    /** sources.rs:449-457 start_run。 */
    public UUID startRun(UUID sourceId) {
        UUID id = Uuids.nowV7();
        sources.startRun(id, sourceId);
        return id;
    }

    /** sources.rs:459-489 finish_run + 每来源只留最近 50 条。 */
    public void finishRun(UUID runId, UUID sourceId, String error, int createdDocs, int updatedDocs) {
        sources.finishRun(runId, error == null ? "ok" : "failed", error, createdDocs, updatedDocs);
        sources.trimRuns(sourceId);
    }

    /** sources.rs:491-502。 */
    public List<SyncRunView> listRuns(UUID sourceId, long limit) {
        return sources.selectRuns(sourceId, limit);
    }

    private static ObjectNode asObject(JsonNode node) {
        return node != null && node.isObject() ? (ObjectNode) node.deepCopy() : null;
    }
}
