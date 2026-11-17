package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * RSS 全文台账的实体形状（rss_full_content.rs:96-159 的 FromRow 对等）。
 * Entry 是投影（ENTRY_SELECT），不是表行——执行与文档状态不重复持久化。
 */
public final class RssEntries {

    private RssEntries() {
    }

    /** 台账投影行（rss_full_content.rs:105-125 Entry + ENTRY_SELECT 的 state/error 列）。 */
    public record Entry(UUID id, UUID sourceId, int activationGeneration, String externalKey,
                        String title, String articleUrl, String summary, String embeddedHtml,
                        OffsetDateTime docTime, String state, Long hydrationJobId,
                        int attemptCount, UUID documentId, String errorCode, String errorDetail,
                        OffsetDateTime firstSeenAt, OffsetDateTime updatedAt,
                        OffsetDateTime completedAt) {
    }

    /** claim 的候选行（rss_full_content.rs:411-417）。 */
    public record ClaimRow(UUID id, OffsetDateTime observedAt) {
    }

    /** counts 聚合（rss_full_content.rs:147-159 Counts 的投影段）。 */
    public record CountsRow(long pendingCount, long queuedCount, long hydratingCount,
                            long retryingCount, long completeCount, long terminalCount) {
    }

    /** 发现统计（rss_full_content.rs:141-145 DiscoveryStats）。 */
    public record DiscoveryStats(int discovered, int terminal) {
    }

    /** 一次观察得到的台账输入（rss_full_content.rs:131-139 NewEntry）。 */
    public record NewEntry(String externalKey, String title, String articleUrl, String summary,
                           String embeddedHtml, OffsetDateTime docTime,
                           boolean hasUsableSource) {
    }
}
