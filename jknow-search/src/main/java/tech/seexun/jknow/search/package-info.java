/**
 * 检索层（对应旧 utopia-search crate：Tantivy+jieba 换 Elasticsearch+IK，语义等价集评审
 * 见 M1 任务 6 报告）——ES 客户端封装 {@link tech.seexun.jknow.search.EsChunkIndex}、
 * RRF 融合 {@link tech.seexun.jknow.common.Rrf}、全量索引器
 * {@link tech.seexun.jknow.search.ChunkIndexer}、检索编排
 * {@link tech.seexun.jknow.search.SearchService}。
 */
package tech.seexun.jknow.search;
