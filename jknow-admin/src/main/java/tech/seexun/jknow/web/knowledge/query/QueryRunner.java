package tech.seexun.jknow.web.knowledge.query;

import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.store.DatasourcesService;

/**
 * 问数执行（api/tools.rs:998-1045 run_query/execute_query/query_text 的对位）：安全闸
 * （解析白名单）→ 引擎执行（只读会话/请求 + 强制 LIMIT + 超时）→ JSON 行。preview
 * （M5 任务 3）与 query_data 工具（M5 任务 4 的聊天域）走的都是这一条边界。
 */
@Service
public class QueryRunner {

    private final DatasourcesService datasources;

    public QueryRunner(DatasourcesService datasources) {
        this.datasources = datasources;
    }

    /**
     * tools.rs:1004-1013 execute_query：闸门按引擎选方言（Databricks 的反引号、
     * Snowflake 的 :: 转型都得先过得了解析），过闸后的语句交引擎。
     */
    public QueryResult executeQuery(UUID dataSourceId, String sql) throws Exception {
        DatasourcesService.EngineAndConn ec = datasources.engineAndConn(dataSourceId);
        String guarded = QueryEngines.guardSqlFor(ec.engine(), sql);
        return QueryEngines.engineFor(ec.engine(), ec.conn()).execute(guarded);
    }

    /** tools.rs:998-1000 run_query：结果给模型读的样子。 */
    public String runQuery(UUID dataSourceId, String sql) throws Exception {
        return queryText(executeQuery(dataSourceId, sql));
    }

    /**
     * tools.rs:1033-1045 query_text：每行一个 JSON 对象，末尾是行数与截断说明——
     * 「在 SQL 里聚合」是模型拿到截断结果时唯一正确的下一步。
     */
    public static String queryText(QueryResult result) {
        StringBuilder out = new StringBuilder();
        if (result.rows().isEmpty()) {
            out.append("(no rows)");
        } else {
            out.append(String.join("\n", result.rows()));
            out.append("\n(").append(result.rows().size()).append(" rows");
            if (result.truncated()) {
                out.append(", truncated at ").append(QueryEngines.ROW_CAP)
                        .append(" — aggregate in SQL for totals");
            }
            out.append(')');
        }
        return out.toString();
    }
}
