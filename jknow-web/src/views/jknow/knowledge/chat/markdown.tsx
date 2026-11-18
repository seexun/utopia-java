/* 对话正文的 markdown 渲染器（任务 8 PoC 定选）。
 *
 * **选型：unified 系（remark-parse + remark-gfm + remark-rehype），
 * 不选 markdown-it。** 理由：
 * 1. 「角标与落款必须是同一套判定」的既有约束在 domain/citations.ts 里已经
 *    铁板钉钉——`citedNumbers`（落款判定）用的就是 remark 解析器，头注写明
 *    「与渲染同一套 remark 解析」。rehypeCitations（Ⅰ类，hast 树上动刀）直接
 *    插进管线；换 markdown-it 等于在渲染面上换一套解析器，`[1]` 在两套解析里
 *    「算不算引用」会分叉（链接锚文本、代码块里的方括号正是两套解析最容易
 *    各说各话的地方），落款与角标从此对不上。
 * 2. 流式增量性能与 react-markdown 同一剖面：每次都是整段全量重解析，真正
 *    的增量策略在高处，且全部已就位——liveAnswer 33ms 帧合并（每秒至多 30 次
 *    通知）、segments 按 step.at 切段（只有还在长的那段会重渲染）、段落组件
 *    按 text prop 记忆化（收尾段不重解析）、citedNumbers 按文本记忆。
 *    markdown-it 全量重解析剖面相同，换它不省任何一件事，还丢掉第 1 条。
 * 3. react-markdown 的对应面从这里缺席：hast → Vue vnode 走下面这个小分派器
 *    （`a` → 引用角标/普通链接、`pre` → 带复制的代码块、其余按标签直绘），
 *    rehype-highlight 管代码高亮（hljs 类名，八色在 utopia-pages.css），
 *    remend 管流式中未闭合语法——成熟件组装，观感自持（旧仓注释原话）。 */
import { Fragment, type VNode, type PropType, defineComponent, h } from "vue";
import { unified } from "unified";
import remarkParse from "remark-parse";
import remarkGfm from "remark-gfm";
import remarkRehype from "remark-rehype";
import rehypeHighlight from "rehype-highlight";
import { rehypeCitations, citeHref } from "@/domain/citations";
import { InlineCite } from "./citations";
import { UCodeBlock } from "./chatCopy";

/** 与旧仓 react-markdown 插件面同一序列：remark-gfm →（remark-rehype）→
 *  rehype-highlight → rehypeCitations。处理器模块级建一次——每段每次渲染
 *  都要 parse，实例化不能跟着渲染走。全同步管线：runSync 直接出 hast。 */
const processor = unified()
  .use(remarkParse)
  .use(remarkGfm)
  .use(remarkRehype)
  .use(rehypeHighlight)
  .use(rehypeCitations);

/** hast 节点里我们读的几个字段（结构类型，不多一个依赖——domain/citations 同款） */
export type HastNode = {
  type: string;
  tagName?: string;
  value?: string;
  properties?: Record<string, unknown>;
  children?: HastNode[];
};

/** hast properties → vnode props。className 数组并成 class 串；raw `html`
 *  属性不落 DOM（remark-rehype 的 allowDangerousHtml 我们没开，防御性跳过） */
function toProps(properties: Record<string, unknown> | undefined) {
  const out: Record<string, unknown> = {};
  if (!properties) return out;
  for (const [k, v] of Object.entries(properties)) {
    if (v === undefined || v === null || k === "html") continue;
    if (k === "className")
      out.class = Array.isArray(v) ? v.join(" ") : String(v);
    else out[k] = v;
  }
  return out;
}

/** 标题子树 → 纯文本（锚点 id 的取材面：h2/h3 里可能嵌 code/strong） */
function textOfHast(node: HastNode): string {
  if (node.type === "text") return node.value ?? "";
  return (node.children ?? []).map(textOfHast).join("");
}

/** hast → vnode 的分派器（react-markdown `components` 覆盖面的 Vue 对等物）：
 *  `a`：remend 把流式中还没收尾的 `[2` 补成一个链接（`streamdown:incomplete-link`），
 *       于是一个正在打出来的引用会先闪成一个蓝色的「2」再变成角标。它本来就不是
 *       链接，按原样的文字画（旧仓注释原话）。`#cite-n` 是 rehypeCitations 落下的
 *       锚，`[1, 2]` 是两条来源——画成两个各自可点的角标，别让人点一个号跳出
 *       两段原文（旧仓注释原话）。
 *  `pre`：代码块带一个复制（#936）：人从回答里抄走的，最常见的就是 SQL
 *  `h1-h3`：anchorId 回调在场时给标题落 id（目录滚动跟随与深链要锚）——
 *           对话面用不到（不传即零行为），内置文档页传 slugify */
function renderHast(
  node: HastNode,
  key: string,
  anchorId?: (text: string) => string
): VNode | string | (VNode | string)[] {
  if (node.type === "text") return node.value ?? "";
  const kids = () => {
    const children = node.children ?? [];
    return children.map((c, i) => renderHast(c, `${key}/${i}`, anchorId));
  };
  if (node.type === "element") {
    const props = toProps(node.properties);
    if (node.tagName === "a") {
      const href = typeof props.href === "string" ? props.href : undefined;
      if (href?.startsWith("streamdown:")) {
        return <Fragment key={key}>{kids()}</Fragment>;
      }
      const ns = citeHref(href);
      if (ns) {
        return h(
          Fragment,
          { key },
          ns.map((n, i) => h(InlineCite, { n, key: `${key}#${i}` }))
        );
      }
      return h("a", { ...props, href, key }, kids());
    }
    if (node.tagName === "pre") {
      return h(UCodeBlock, { key }, { default: kids });
    }
    if (
      anchorId &&
      (node.tagName === "h1" || node.tagName === "h2" || node.tagName === "h3")
    ) {
      const t = textOfHast(node).trim();
      if (t) props.id = anchorId(t);
    }
    return h(node.tagName!, { ...props, key }, kids());
  }
  /* root / 其它容器：直接摊开（外层的分段容器自己管住） */
  return <Fragment key={key}>{kids()}</Fragment>;
}

/** 一段 markdown。**按文本记忆化**：一次生成里每来一个词元，整条消息都要重渲染，
 *  而 react-markdown 每次都把那一段从头解析一遍——答案越长每个词元越贵，读起来
 *  就是越写越顿。收了尾的段落文本不再变，Vue 的 props 浅比较让它们一次也不重解析
 *  （子组件 props 没变就不进渲染函数）；还在长的那一段照旧，它本来就得重解析。
 *  渲染自带 `<div class="chat-prose">` 壳（C-1，审查收口）：皮肤全归 chat-prose
 *  设计系统（代码八色/代码块凹底/段落间距全挂在 .chat-prose 祖先上），旧仓每段
 *  包 div.chat-prose，这里由组件自己带，调用点不再记得这件事。 */
export const UMarkdown = defineComponent({
  name: "UMarkdown",
  props: {
    text: { type: String, required: true },
    /** 标题锚点回调（可选）：传了就给 h1-h3 落 id。对话面不传（零行为），
     *  内置文档页传 slugify——目录与正文用同一函数保证对得上（旧仓注释原话） */
    anchorId: {
      type: Function as PropType<(text: string) => string>,
      required: false
    }
  },
  setup(props) {
    return () => {
      const tree = processor.runSync(processor.parse(props.text)) as HastNode;
      const rendered = (tree.children ?? []).map((c, i) =>
        renderHast(c, `md/${i}`, props.anchorId)
      );
      return <div class="chat-prose">{rendered}</div>;
    };
  }
});

/** 一段正文，或一组同时发生的调用。（旧仓 Chat.tsx 的 Segment 联合类型） */
export type Segment =
  | { kind: "text"; text: string; last: boolean }
  | { kind: "steps"; steps: import("@/api/knowledge/types").ChatStep[] };

/** 把一轮回复拆成按发生顺序排列的段。
 *
 *  切分点是 `step.at`——那一步发生时正文已经有多长。**这条迁移之前落库的
 *  消息没有 `at`**，那时的顺序信息是真的没有存下来，编不出来也不该编：
 *  它们退回旧样子，整段轨迹在最前面。（旧仓注释原话） */
export function segments(turn: {
  content?: string;
  steps?: import("@/api/knowledge/types").ChatStep[] | undefined;
}): Segment[] {
  const steps = turn.steps ?? [];
  const text = turn.content ?? "";
  if (steps.length === 0) {
    return text ? [{ kind: "text", text, last: true }] : [];
  }
  if (steps.some(s => s.at === undefined)) {
    return [
      { kind: "steps", steps },
      ...(text ? [{ kind: "text" as const, text, last: true }] : [])
    ];
  }
  const out: Segment[] = [];
  let cursor = 0;
  for (let i = 0; i < steps.length;) {
    const at = steps[i].at!;
    // 同一位置的连成一组：一轮里的多次调用之间没有正文，它们本来就是一次扇出
    let j = i;
    while (j < steps.length && steps[j].at === at) j++;
    const before = text.slice(cursor, at);
    if (before) out.push({ kind: "text", text: before, last: false });
    out.push({ kind: "steps", steps: steps.slice(i, j) });
    cursor = at;
    i = j;
  }
  const tail = text.slice(cursor);
  if (tail) out.push({ kind: "text", text: tail, last: true });
  return out;
}
