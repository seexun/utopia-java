package tech.seexun.jknow.web.knowledge.query;

/**
 * 问数查询引擎的接缝（query_engine/mod.rs:83-89 trait QueryEngine——BlobStore 同手法）。
 * 引擎按协议族扩，不按产品名扩：postgres 线协议、MySQL 线协议（一条协议顺带覆盖
 * TiDB/OceanBase/Doris/StarRocks/MariaDB）→ HTTP 族——Trino 一个顶起 Iceberg/Delta/Hive
 * 整个湖仓生态，Databricks/Snowflake 各走自家的 SQL REST API，ClickHouse 走其 HTTP 接口。
 *
 * <p>四层纵深防御（不信任模型，各层威胁模型见 {@link QueryEngines} 头注）：
 * 安全闸第 1 层在 {@link QueryEngines#guardSqlFor}；{@link #execute} 收到的已是过闸 SQL，
 * 但实现自身仍需强制只读会话与超时（纵深防御——parser 万一漏网也写不进去）。
 */
public interface QueryEngine {

    /** 连得上吗（POST /admin/data-sources/test 与 /{id}/test 用）。 */
    void test() throws Exception;

    /** 目标库的列清单（挂载摄取 schema 文档用）。 */
    java.util.List<SchemaColumn> fetchSchema() throws Exception;

    /** 执行已过闸的 SELECT（只读会话 + 强制 LIMIT + 超时由实现保证）。 */
    QueryResult execute(String sql) throws Exception;
}
