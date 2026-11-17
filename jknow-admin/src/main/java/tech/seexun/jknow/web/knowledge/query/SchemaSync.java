package tech.seexun.jknow.web.knowledge.query;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.ingest.SourceIngestService;
import tech.seexun.jknow.store.DatasourcesService;
import tech.seexun.jknow.store.SourcesService;
import tech.seexun.jknow.store.entity.DataSourceView;
import tech.seexun.jknow.store.entity.SourceRow;
import tech.seexun.jknow.store.mapper.AlertsMapper;

/**
 * 拉目标库的 information_schema 生成 markdown，走三路判定摄入（同 key 原地更新）——
 * datasource_routes.rs:287-392（sync_schema_doc）的全量对位。文档挂在 per-KB 的
 * "Data schemas" folder 来源下：Chat 写 SQL 前可检索到表结构（挂载/手动刷新两个入口）。
 *
 * <p>引擎读不出库表结构（连接、权限、catalog 不存在……）与我们自己这边出错分开：
 * 前者是 {@link SchemaUnreadableException}（连接串或权限的事，回给点按钮的人 422 带引擎
 * 原话）；后者照旧 500。
 */
@Service
public class SchemaSync {

    /** datasource_routes.rs:311 MAX_TABLES：文档最多写这么多张表（宽库砍尾不砍首）。 */
    static final int MAX_TABLES = 200;

    private static final Logger log = LoggerFactory.getLogger(SchemaSync.class);

    private final DatasourcesService datasources;
    private final SourcesService sources;
    private final SourceIngestService ingest;
    private final AlertsMapper alerts;
    private final AppEventBus events;
    private final JdbcTemplate jdbc;

    public SchemaSync(DatasourcesService datasources, SourcesService sources,
                      SourceIngestService ingest, AlertsMapper alerts, AppEventBus events,
                      JdbcTemplate jdbc) {
        this.datasources = datasources;
        this.sources = sources;
        this.ingest = ingest;
        this.alerts = alerts;
        this.events = events;
        this.jdbc = jdbc;
    }

    /** 引擎读不出结构（datasource_routes.rs:291-300 SchemaUnreadable）：连接串/权限的事，不是我们的错。 */
    public static final class SchemaUnreadableException extends RuntimeException {
        SchemaUnreadableException(Throwable cause) {
            super(String.valueOf(cause.getMessage()) + (cause.getMessage() == null
                    ? " (" + cause.getClass().getSimpleName() + ")" : ""), cause);
        }
    }

    /** datasource_routes.rs:310-392 sync_schema_doc：返回摄入的表数。 */
    public int syncSchemaDoc(UUID kbId, UUID dsId) {
        String name = datasources.listAll().stream()
                .filter(d -> d.id().equals(dsId))
                .map(DataSourceView::name)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Data source not found"));
        DatasourcesService.EngineAndConn ec = datasources.engineAndConn(dsId);
        List<SchemaColumn> cols;
        try {
            cols = QueryEngines.engineFor(ec.engine(), ec.conn()).fetchSchema();
        } catch (Exception e) {
            throw new SchemaUnreadableException(e);
        }

        StringBuilder md = new StringBuilder(
                "# Data source: " + name + "\n\nEngine: " + ec.engine()
                        + ". Tables and columns available for SQL queries against this source;"
                        + " write SQL in this engine's dialect.\n");
        String current = "";
        int tables = 0;
        for (SchemaColumn c : cols) {
            String key = c.schema() + "." + c.table();
            if (!key.equals(current)) {
                if (tables >= MAX_TABLES) {
                    md.append("\n(further tables omitted)\n");
                    break;
                }
                current = key;
                tables++;
                md.append("\n## ").append(key).append('\n');
            }
            md.append("- ").append(c.column()).append(" (").append(c.dataType()).append(')');
            if (c.comment() != null) {
                md.append(" — ").append(c.comment());
            }
            md.append('\n');
        }

        // per-KB "Data schemas" 容器来源（folder：纯容器语义）。**这份文档只做检索语料，
        // 不进抽取**（0035 决定 7）。它跟别的文档一样进抽取的时候，抽取器把每个列名都
        // 当成了实体——宽表语料上四十个概念实体里二十八个是列名（#553）。「不抽取」记在
        // 来源的 config 上，流水线读它
        UUID folder = findSchemasFolder(kbId);
        if (folder == null) {
            SourceRow created = sources.create(kbId, "folder", "Data schemas",
                    extractFalse(), "database", null, null);
            folder = created.id();
        }
        ingest.ingestItem(kbId, folder, "datasource:" + dsId + ":schema",
                name + "-schema.md", "text/markdown",
                md.toString().getBytes(StandardCharsets.UTF_8), null);
        events.emitSource(kbId);
        return tables;
    }

    private static com.fasterxml.jackson.databind.JsonNode extractFalse() {
        ObjectNode n = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        n.put("extract", false);
        return n;
    }

    /** datasource_routes.rs:356-360：per-KB 的 "Data schemas" folder 来源（没有则建）。 */
    private UUID findSchemasFolder(UUID kbId) {
        List<UUID> found = jdbc.query(
                "SELECT id FROM sources WHERE kb_id = ? AND kind = 'folder'"
                        + " AND name = 'Data schemas'",
                (rs, i) -> UUID.fromString(rs.getString(1)), kbId);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * 告警（datasource_routes.rs:204-212 / alerting.rs:202-232 observe_schema_sync_failure）：
     * 挂载成功而 schema 摄取失败是一个静默的缺失状态（0009），报进告警中心。配置类：
     * 要修的是连接串或网络，不是内容——找 admin。名字在 detail 里存一份——源被删掉之后
     * subject_id 解析不出名字，而告警该留得住。**报警路径上的失败不该淹掉它要报的那件事**。
     */
    public void observeSchemaSyncFailure(UUID kbId, UUID dsId, String sourceName, Exception err) {
        try {
            ObjectNode detail = new com.fasterxml.jackson.databind.ObjectMapper()
                    .createObjectNode();
            detail.put("source", sourceName);
            detail.put("error", String.valueOf(err.getMessage()));
            alerts.insertAlert(Uuids.nowV7(), kbId, "warning", "data_source.schema_sync_failed",
                    "admin", "data_source", dsId, detail);
            events.emitAlert();
        } catch (Exception e) {
            log.warn("上报 data_source.schema_sync_failed 失败: {}", e.getMessage());
        }
    }

    /** 告警要留住名字（datasource_routes.rs:217-223 source_name）：查不到给 id 占位。 */
    public String sourceName(UUID dsId) {
        try {
            return datasources.listAll().stream()
                    .filter(d -> d.id().equals(dsId))
                    .map(DataSourceView::name)
                    .findFirst()
                    .orElse(dsId.toString());
        } catch (Exception e) {
            return dsId.toString();
        }
    }

    /**
     * 截到 max 个字符以内（按字符边界，datasource_routes.rs:303-308 bounded）——引擎的
     * 报错可能带整段堆栈。
     */
    public static String bounded(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        // 按码点截断（Rust char_indices——审查 M-2：UTF-16 截点落在增补面字符中间
        // 会产生孤立代理项）；max 以字符计，截点对齐码点边界
        int end = text.offsetByCodePoints(0, Math.min(max, text.codePointCount(0, text.length())));
        return text.substring(0, end) + "…";
    }
}
