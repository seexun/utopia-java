/* 高亮切段（reskin-b4 归一）：把一段文本按命中词切成「普通/命中」两种片段，按原顺序。
 *
 * 检索结果卡、图谱浮搜、对话引用预览、docs 摘要高亮原本各有一份本地实现，
 * 语义取并集归到这里——
 * - 多词 regex：一个词里的空白是弹性的（引文里的一个空格对得上原文里的换行）
 * - 长词优先：多词按长度降序交替，免得「星辰智造」先被单字吃掉
 * - 正则转义：词里的元字符当字面
 * - 大小写不敏感，全部命中都标；没有词（或全是空白）时整段原样 */

export type HighlightSegment = { text: string; hit: boolean };

const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

export function highlightSegments(
  text: string,
  terms: string[]
): HighlightSegment[] {
  const alts = terms
    .map((t) => t.trim())
    .filter(Boolean)
    .sort((a, b) => b.length - a.length)
    .map((t) => t.split(/\s+/).map(escape).join("\\s+"));
  if (!alts.length) return [{ text, hit: false }];
  let re: RegExp;
  try {
    re = new RegExp(`(${alts.join("|")})`, "gi");
  } catch {
    return [{ text, hit: false }];
  }
  const segs: HighlightSegment[] = [];
  let last = 0;
  for (const m of text.matchAll(re)) {
    const i = m.index ?? 0;
    if (i > last) segs.push({ text: text.slice(last, i), hit: false });
    segs.push({ text: m[0], hit: true });
    last = i + m[0].length;
  }
  if (last < text.length) segs.push({ text: text.slice(last), hit: false });
  return segs;
}
