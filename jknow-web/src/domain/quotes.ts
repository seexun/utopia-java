// 原文里被引到的那几句（#968 的后续）。
//
// 图谱工具给一条事实印的号打开的是它第一条有效证据的那一块；来源条目的 `quotes` 是块里
// 说出这条（或这几条）事实的那几句。预览浮窗据此把它们标出来（切段已 reskin-b4 归一到
// utils/highlightSegments：空白弹性/长词优先/转义），号打开的就是那句话。

/** 落款行上那一条的摘要：有被引的句子就是第一句，否则是这一块的开头 */
export function sourceLead(source: { excerpt: string; quotes?: string[] }): string {
  return source.quotes?.[0] ?? source.excerpt;
}

/** 进地址栏的那一句最长多少字。地址会留在浏览器历史和代理的访问日志里，刷新时整条重发：
 *  一个汉字编码后约九个字节，几百字就顶到常见的 8k 请求头上限。超长的一句不带——
 *  截断了在原文里找不到，标不出来，不如不标 */
export const QUOTE_PARAM_MAX = 300;

/** 能进地址栏的一句：空白的、超长的都等于没给 */
export function quoteParam(quote: unknown): string | undefined {
  return typeof quote === "string" && quote.trim() && quote.length <= QUOTE_PARAM_MAX
    ? quote
    : undefined;
}

/** 文档页的查询参数：引用跳转到哪一块，块里标出哪一句 */
export function docSearch(search: Record<string, unknown>): { chunk?: string; quote?: string } {
  return {
    chunk: typeof search.chunk === "string" ? search.chunk : undefined,
    quote: quoteParam(search.quote),
  };
}
