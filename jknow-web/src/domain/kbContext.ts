/* 【任务4搬运】自旧仓 web/src/kb.tsx 的 samePageInKb：纯函数逐字原样（含注释）。
   旧仓的 React hook 面不搬——Vue 面在 composables/useKbContext.ts 重组。 */

/**
 * 换库落在同一个页面上，但**不带走页面里的东西**。
 *
 *  从前是把路径里的 kbId 整个替换掉，于是 `/kb/A/chat/某会话` 变成
 *  `/kb/B/chat/某会话`——会话属于 A，页面拿着它去问 B，得到一个 404 再退回
 *  新对话。单槽的 liveAnswer 曾经把这一步掩住（它不问会话属于谁就认领），
 *  按库键控之后（#259）认领正确地失败，404 就露了出来（#261）。
 *
 *  所以只保留 kbId 后面的第一段：chat、graph、library……深一层的会话 id、
 *  文档 id 都是那个库里的东西，换库就该丢掉。文档页本身就是某一篇文档，
 *  换库后落到新库的 Library。不在 /kb 作用域下时保持原行为。
 */
export function samePageInKb(
  pathname: string,
  fromKbId: string,
  toKbId: string
): string {
  const prefix = `/kb/${fromKbId}`;
  if (!pathname.startsWith(prefix))
    return pathname.replace(fromKbId, toKbId);
  const section =
    pathname.slice(prefix.length).split("/").filter(Boolean)[0] ?? "graph";
  return `/kb/${toKbId}/${section === "doc" ? "library" : section}`;
}
