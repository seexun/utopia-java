package tech.seexun.jknow.web.knowledge.query;

import java.util.List;

/**
 * 查询结果（query_engine/mod.rs:54-59 QueryResult）：每行一个 JSON 对象文本，
 * <b>键序 = 查询列序</b>——模型读表靠列序，这是第 4 层（结果统一）的承诺。
 *
 * @param rows      每行一段 JSON 文本（手工拼接、保列序）
 * @param truncated 第 201 行只用来判断截断，不交给模型（mod.rs:174-178 truncate_rows）
 */
public record QueryResult(List<String> rows, boolean truncated) {
}
