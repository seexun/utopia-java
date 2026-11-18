package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.query.QueryEngines;
import tech.seexun.jknow.web.knowledge.query.SchemaSync;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.DatasourcesService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 知识库层数据源挂载面（datasource_routes.rs:150-285 KB 分支 + 394-459 授权分支；
 * admin 注册面在 {@link AdminUsersController}）。挂载/手动刷新时把目标库的 schema 生成
 * markdown 摄入 KB（同 key 原地更新），Chat 写 SQL 前可检索到表结构——见
 * {@link SchemaSync}。查询执行的安全闸在刀 2（query_data 工具/preview——
 * {@link tech.seexun.jknow.web.knowledge.query.QueryRunner}）。
 */
@RestController
@PreAuthorize("@ss.hasPermi('knowledge:settings:list')")
public class DatasourcesController {

    private final DatasourcesService datasources;
    private final SchemaSync schemaSync;
    private final JobsService jobs;
    private final KbAccess rbac;
    private final ObjectMapper mapper;

    public DatasourcesController(DatasourcesService datasources, SchemaSync schemaSync,
                                 JobsService jobs, KbAccess rbac, ObjectMapper mapper) {
        this.datasources = datasources;
        this.schemaSync = schemaSync;
        this.jobs = jobs;
        this.rbac = rbac;
        this.mapper = mapper;
    }

    /**
     * datasource_routes.rs:179-213 mount：挂载即摄取 schema——Chat 写 SQL 前能检索到表
     * 结构。
     *
     * <p><b>这一步失败不能回报成挂载失败。</b>上面那行已经写进 kb_data_sources 了，源是
     * 真挂着的；从前这里 `?` 出去回 500，人以为没挂上，实际挂上了——而问数看不见它有哪
     * 些表。改成：照实说挂载成了，schema 没成，并且报进告警中心，因为那之后就是一个
     * 静默的缺失状态（0009）。
     *
     * <p><b>列表过滤不是守卫。</b>那只挡「看得见」，而这个端点是照着 id 调的——谁都能
     * 自己拼一个 uuid 打过来。授权在这里再查一次。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @PutMapping("/api/v1/kbs/{kbId}/data-sources/{dsId}")
    public JsonNode mount(@PathVariable UUID kbId, @PathVariable UUID dsId,
                          HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.ADMIN);
        if (!datasources.isGranted(kbId, dsId)) {
            throw AppException.invalid("source_not_granted",
                    "This data source is not available to this workspace");
        }
        datasources.mount(kbId, dsId);
        try {
            int synced = schemaSync.syncSchemaDoc(kbId, dsId);
            ObjectNode out = mapper.createObjectNode();
            out.put("ok", true);
            out.put("schema_tables", synced);
            return out;
        } catch (Exception e) {
            String name = schemaSync.sourceName(dsId);
            schemaSync.observeSchemaSyncFailure(kbId, dsId, name, e);
            ObjectNode out = mapper.createObjectNode();
            out.put("ok", true);
            out.put("schema_tables", 0);
            out.put("schema_error", String.valueOf(e.getMessage()));
            return out;
        }
    }

    /** datasource_routes.rs:225-233 unmount（幂等——没挂也是 ok）。 */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @DeleteMapping("/api/v1/kbs/{kbId}/data-sources/{dsId}")
    public JsonNode unmount(@PathVariable UUID kbId, @PathVariable UUID dsId,
                            HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.ADMIN);
        datasources.unmount(kbId, dsId);
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        return out;
    }

    /**
     * datasource_routes.rs:240-268 sync_schema：手动刷新结构。
     *
     * <p><b>这里照旧把错误回给调用方</b>——点按钮的人正看着，而且什么都没半途发生。但
     * 同样报一条告警：留下的后果与挂载失败时一模一样（源挂着、表结构是旧的或空的），而
     * 点按钮的人未必是需要知道这件事的人。
     *
     * <p><b>读不出结构回 422 带引擎原话</b>（截 600 字符）；我们自己这边出的错（库、写
     * 文档）照旧 500。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/data-sources/{dsId}/sync-schema")
    public ResponseEntity<JsonNode> syncSchema(@PathVariable UUID kbId, @PathVariable UUID dsId,
                                               HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.ADMIN);
        try {
            int synced = schemaSync.syncSchemaDoc(kbId, dsId);
            ObjectNode out = mapper.createObjectNode();
            out.put("ok", true);
            out.put("schema_tables", synced);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out);
        } catch (SchemaSync.SchemaUnreadableException unreadable) {
            String name = schemaSync.sourceName(dsId);
            schemaSync.observeSchemaSyncFailure(kbId, dsId, name, unreadable);
            org.slf4j.LoggerFactory.getLogger(DatasourcesController.class)
                    .warn("kb={} ds={} 数据源结构读取失败: {}", kbId, dsId, unreadable.getMessage());
            throw AppException.invalidDetail("schema_sync_failed",
                    "The data source's schema could not be read",
                    SchemaSync.bounded(unreadable.getMessage(), 600));
        } catch (Exception e) {
            String name = schemaSync.sourceName(dsId);
            schemaSync.observeSchemaSyncFailure(kbId, dsId, name, e);
            throw AppException.other(String.valueOf(e.getMessage()), e);
        }
    }

    /**
     * datasource_routes.rs:271-285 explore：Agentic 探索——后台任务读挂载源 schema，提议
     * 指标/维度→字段 映射（低置信入 Review）。无挂载源 422 no_data_sources。
     */
    @PreAuthorize("@ss.hasPermi('knowledge:settings:edit')")
    @PostMapping("/api/v1/kbs/{kbId}/data-sources/explore")
    public JsonNode explore(@PathVariable UUID kbId, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.ADMIN);
        if (datasources.mounted(kbId).isEmpty()) {
            throw AppException.invalid("no_data_sources", "No data sources mounted");
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("kb_id", kbId.toString());
        jobs.enqueue("explore_mappings", payload);
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        return out;
    }

    /** 共用：引擎按 scheme 分派 + 连接串形状校验（datasource_routes.rs:57-70/114-126）。 */
    static String engineOf(String connString) {
        String engine = QueryEngines.engineFromConn(connString);
        if (engine == null) {
            throw AppException.invalid("unsupported_conn_scheme",
                    "Connection string must start with one of: postgres://, mysql://,"
                            + " trino://, databricks://, snowflake://, clickhouse://"
                            + " (engines: " + String.join(", ", QueryEngines.ENGINES) + ")");
        }
        return engine;
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }
}
