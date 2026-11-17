package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RSS 观察台账契约（rss_ledger_contract.rs 全量移植——未发布契约的必需库验收）：
 * 一张观察表；sources 两列带类型激活；执行与文档状态不重复持久化（从前的八列里
 * 三列 NULL 五列 0，一个文件夹来源报 queued_count: 0 就是在谈它没有的队列）。
 */
class RssLedgerContractTests extends HeartStoreTestBase {

    @Test
    void rssHasOneObservationTableAndTypedSourceActivation() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name LIKE 'rss_full_content_%' "
                        + "ORDER BY table_name", String.class);
        assertEquals(List.of("rss_full_content_entries"), tables);
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'sources' "
                        + "AND column_name IN ('rss_generation', 'rss_baselined_at') "
                        + "ORDER BY column_name", String.class);
        assertEquals(List.of("rss_baselined_at", "rss_generation"), columns);
        Long redundant = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'rss_full_content_entries' AND column_name IN "
                        + "('state','attempt_count','error_code','error_detail',"
                        + "'content_source','content_sha256','completed_at','document_id')",
                Long.class);
        assertEquals(0L, redundant, "execution and document state must not be persisted twice");
        String kind = jdbc.queryForObject(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'rss_full_content_entries' AND column_name = "
                        + "'entry_kind'", String.class);
        assertEquals("entry_kind", kind);
    }
}
