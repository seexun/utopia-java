package tech.seexun.jknow.web.knowledge.query;

import javax.annotation.Nullable;

/**
 * fetch_schema 的一列（query_engine/mod.rs:61-81 SchemaColumn）。
 *
 * <p>{@code isPrimaryKey}：该列本身是这张表的<b>单列</b>主键（组合主键里这一位仍是
 * false）。PG（#671）与 MySQL/MariaDB（#678）从 catalog 读；Trino/Snowflake/Databricks/
 * ClickHouse 恒 false——意思是「不知道」，不是「不是键」（各自原因见
 * {@link TrinoEngine#schemaRow} 上的注释）。探索提示词靠它把 ID 与量分开，宽表上
 * 一个八十列的 schema 没有它几乎认不出哪一列是键（#502）。
 *
 * <p>{@code referencesTable}：该列本身是某张表的单列外键时它指向的表（schema.table）；
 * 组合外键的成员与读不出外键的引擎都是 null。可空没放进来：两个探索提示词都不用
 * 它，宽表上每列多一个 NOT NULL 只是挤掉列（#502 真用到时再加）。
 */
public record SchemaColumn(String schema, String table, String column, String dataType,
                           @Nullable String comment, boolean isPrimaryKey,
                           @Nullable String referencesTable) {
}
