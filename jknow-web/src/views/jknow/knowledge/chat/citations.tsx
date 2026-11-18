/* 对话里的引用：正文角标、落款行、预览浮窗的接线。（对照旧仓 chatCitations.tsx
   + ui/citation.tsx；浮层用 EP 的 el-popover——弹层样式统一走 shell-pop 一族，
   替旧仓的 RadixPopover。）点一条引用先给一段原文，右上角才是去原文的路——
   核对一句话多半只要看那一段，而跳转会把人从答案里带走，回来时答案已经滚上去了。 */
import {
  type Ref,
  type VNode,
  computed,
  defineComponent,
  inject,
  provide,
  ref,
  type PropType
} from "vue";
import { useRoute, RouterLink } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import { documentDetail } from "@/api/knowledge/documents";
import type { Source } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { originLabel } from "@/domain/origin";
import { quoteParam, quotePieces, sourceLead } from "@/domain/quotes";
import BookOpenIcon from "~icons/lucide/book-open";
import ArrowUpRightIcon from "~icons/lucide/arrow-up-right";
import { ElPopover } from "element-plus";

/* ---------- 来源的供给面 ---------- */

const SOURCES_KEY = Symbol("utopia:chatSources");

/** 这一轮回答的来源，由 Turn 供给（页面对每条助手消息 provide 一次）。
 *
 *  **走 provide/inject 而不是 prop**：正文分段按文本记忆化（一条长答案每来一个
 *  词元都要重渲染，收了尾的段落不该再解析一遍），而来源是一条条到的。把它当
 *  prop 传下去，每来一条来源所有段落就全部失效；注入只让角标自己重画。 */
export function provideChatSources(sources: Ref<Source[]>): void {
  provide(SOURCES_KEY, sources);
}

export function useChatSources(): Ref<Source[]> {
  return inject<Ref<Source[]>>(
    SOURCES_KEY,
    computed(() => [] as Source[])
  );
}

/** 引言节的 heading 就是文章名，避免 "X › X" */
export function charterTitle(s: Source): string {
  return s.heading && s.heading !== s.filename
    ? `${s.filename} › ${s.heading}`
    : s.filename;
}

/* ---------- 引用预览浮窗 ---------- */

const CITE_POPPER = "shell-pop chat-cite-pop";

/** 浮窗的样子：标题一行、原文一段、右上角一条出路 */
const PreviewCard = defineComponent({
  name: "UCitePreviewCard",
  props: {
    title: { type: String, required: true },
    /* 标题下面的一行小字（章节名、这段文字的来历） */
    meta: { type: String, default: undefined },
    /** 原文正文：纯字符串或已组好的 VNode（引文命中高亮走 QuotedText，I-3 接回） */
    body: {
      type: [String, Object] as PropType<string | VNode>,
      required: true
    },
    /** 去原文的路由目标；没有就只有原文（charter 无文档详情时兜底） */
    actionTo: { type: Object as PropType<unknown>, default: undefined }
  },
  setup(props) {
    return () => (
      <div class="w-80 max-w-[min(24rem,90vw)]">
        <div class="flex items-start gap-2 border-b border-line px-3 py-2">
          <div class="min-w-0 flex-1">
            <div class="sle text-sub text-ink">{props.title}</div>
            {props.meta && (
              <div class="sle text-aux text-ink-2">{props.meta}</div>
            )}
          </div>
          {props.actionTo && (
            <div class="shrink-0">
              <RouterLink
                to={props.actionTo as never}
                class="link-inline flex items-center gap-1 text-aux text-ink-2"
              >
                {S.ask.openOriginal}
                <ArrowUpRightIcon width={11} height={11} />
              </RouterLink>
            </div>
          )}
        </div>
        {/* 原文按原样排（换行是它的一部分），长了自己滚，别把浮窗撑穿屏幕 */}
        <div class="max-h-64 overflow-auto whitespace-pre-wrap px-3 py-2 text-sub/relaxed  text-ink-2">
          {props.body}
        </div>
      </div>
    );
  }
});

/** 一段原文，被引到的那几句用检索命中的样子标出来（#968 的后续）。对不上的引文不标 */
const QuotedText = defineComponent({
  name: "UQuotedText",
  props: {
    text: { type: String, required: true },
    quotes: {
      type: Array as PropType<string[] | undefined>,
      default: undefined
    }
  },
  setup(props) {
    return () =>
      quotePieces(props.text, props.quotes).map((p, i) =>
        p.quoted ? (
          <mark key={i} class="mark">
            {p.text}
          </mark>
        ) : (
          <span key={i}>{p.text}</span>
        )
      );
  }
});

/** 预览：先给检索回来的那段摘录（160 字，立刻有东西看），整块原文随文档详情
 *  到了再换上——同一把 react-query 钥匙，所以点完「打开原文」那一页是现成的。
 *  **查询点开才发**（opened 闸）：Radix 的浮层内容是点开才挂载，这里把同一件
 *  事在数据面上说破——没开过就不发请求。 */
const SourcePreview = defineComponent({
  name: "USourcePreview",
  props: {
    s: { type: Object as PropType<Source>, required: true },
    opened: { type: Boolean, default: false }
  },
  setup(props) {
    const route = useRoute();
    const kbId = computed(
      () => (route.params as { kbId?: string } | undefined)?.kbId ?? ""
    );
    const doc = useQuery({
      queryKey: computed(() => ["docDetail", props.s.document_id]),
      queryFn: () => documentDetail(props.s.document_id!),
      enabled: computed(() => props.opened && !!props.s.document_id)
    });
    const chunk = computed(() =>
      doc.data.value?.chunks.find(c => c.id === props.s.chunk_id)
    );

    if (props.s.kind === "charter") {
      return () => (
        <PreviewCard
          title={charterTitle(props.s)}
          body={props.s.excerpt}
          actionTo={{
            path: props.s.slug ? `/docs/${props.s.slug}` : "/docs",
            hash: props.s.anchor ? `#${props.s.anchor}` : undefined
          }}
        />
      );
    }

    return () => (
      <PreviewCard
        title={props.s.filename}
        meta={
          originLabel(chunk.value?.origin, chunk.value?.anchor) ?? undefined
        }
        /* 引文命中高亮（#968 的后续）：被引到的那几句用检索命中的样子标出来，
           对不上的引文不标（quotePieces 的判定）。I-3 接回——body 走 VNode */
        body={
          <QuotedText
            text={chunk.value?.text ?? props.s.excerpt}
            quotes={props.s.quotes}
          />
        }
        actionTo={{
          path: `/kb/${kbId.value}/doc/${props.s.document_id}`,
          query: {
            chunk: props.s.chunk_id,
            quote: quoteParam(props.s.quotes?.[0])
          }
        }}
      />
    );
  }
});

/** 还点不动的号（来源尚未到达）。占同样的位置，只是不发光。
 *  InlineCite 在来源未到时也落到这里画（M-4：一个形态只有一份实现） */
export const CiteMark = defineComponent({
  name: "UCiteMark",
  props: { n: { type: Number, required: true } },
  setup(props) {
    return () => <span class="tabular-nums px-1 text-ink-2">[{props.n}]</span>;
  }
});

/** 正文里的一个 `[n]`。流式中来源还没到的号用静的样子画（CiteMark）。
 *  浮窗内容只有点开才挂载，所以里面的请求也是点开才发——opened 闸挂在触发器
 *  自己的 onClick 上（Radix 的 Portal 内容点开才挂载，这里把同一件事在数据面上
 *  说破：没点过就不发请求） */
export const InlineCite = defineComponent({
  name: "UInlineCite",
  props: { n: { type: Number, required: true } },
  setup(props) {
    const sources = useChatSources();
    const s = computed(() => sources.value.find(x => x.n === props.n));
    const opened = ref(false);
    return () => {
      const source = s.value;
      // 流式中来源比正文晚到：先画成静的，那条到了自己就变成可点的
      if (!source) return <CiteMark n={props.n} />;
      return (
        <ElPopover
          trigger="click"
          placement="top"
          width={320}
          popperClass={CITE_POPPER}
        >
          {{
            reference: () => (
              <button
                type="button"
                class="tabular-nums rounded-ctl px-1 text-primary transition-colors hover:bg-fill"
                onClick={() => (opened.value = true)}
              >
                [{props.n}]
              </button>
            ),
            default: () => <SourcePreview s={source} opened={opened.value} />
          }}
        </ElPopover>
      );
    };
  }
});

/** 落款：只列正文引到的那几条（见 liveAnswer.citedSources），一个面板装一组。
 *  整行是触发器，点开同一个浮窗。
 *  **查询按行闸**（I-2，审查收口）：openedN 记「点开的是哪一条」，只有那一条的
 *  SourcePreview 拿到 enabled——从前一块共享 opened，点一条全组都发 docDetail
 *  （整篇文档 + chunks 一起下来）。persistent 默认 true 只保「开过的」不再重发，
 *  「没开过的」本来就不该发。 */
export const USourceList = defineComponent({
  name: "USourceList",
  props: {
    sources: { type: Array as PropType<Source[]>, required: true }
  },
  setup(props) {
    const openedN = ref<number | null>(null);
    return () => (
      <div class="mt-2 divide-y divide-line rounded-card border border-line bg-card">
        {props.sources.map(s => (
          <ElPopover
            key={s.n}
            trigger="click"
            placement="top"
            width={320}
            popperClass={CITE_POPPER}
          >
            {{
              reference: () => (
                <button
                  type="button"
                  class="block w-full px-3 py-2 text-left text-sub text-ink-2 transition-colors hover:bg-fill"
                  onClick={() => (openedN.value = s.n)}
                >
                  {s.kind === "charter" ? (
                    <span class="flex items-center gap-2">
                      <span class="tabular-nums text-primary">[{s.n}]</span>
                      <BookOpenIcon
                        width={11}
                        height={11}
                        class="shrink-0 text-ink-2"
                      />
                      <span class="sle">{charterTitle(s)}</span>
                    </span>
                  ) : (
                    <span class="sle block">
                      <span class="tabular-nums text-primary">[{s.n}]</span>{" "}
                      {s.filename} · {sourceLead(s).slice(0, 60)}…
                    </span>
                  )}
                </button>
              ),
              default: () => (
                <SourcePreview s={s} opened={openedN.value === s.n} />
              )
            }}
          </ElPopover>
        ))}
      </div>
    );
  }
});

/** 正文角标的渲染走 markdown.tsx（hast 树里遇到的 `a` 分发到 InlineCite）。 */
