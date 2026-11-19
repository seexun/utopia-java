<!-- Chat：agentic 对话（检索/图谱工具 + remember 记忆）。（对照旧仓 pages/Chat.tsx
     逐交互搬运）会话持久化：左栏会话列表；上下文由服务端拼，前端只发
     conversation_id + 新消息；行动轨迹(steps)与引用(sources)随消息落库，历史回放
     与实时流共用渲染（旧仓注释原话）。
     会话即路由：/kb/:kbId/chat/:conversationId——URL 是当前会话的唯一事实来源
     （刷新/回退天然可用）。markdown 渲染与引用角标在 markdown.tsx / citations.tsx，
     轨迹行在 steps.tsx / turn.tsx。
     **根元素纪律（W3 走查的血泪教训，kb-page 头注同一句话的实证版）**：本页
     模板曾经是「注释 + v-if 块 + v-else 块 + 尾挂 DangerConfirm」的多根——壳的
     主区 Transition(mode=out-in) 拿到的是 fragment，out-in 的「旧叶离场后插入
     新叶」在这棵树上静默失败：旧叶淡出完、新叶永不挂载、router-view 落
     注释占位节点，且从此整壳空白（连别的页面都进不去）。症状毫无报错、与脚本
无关（同脚本配单元素根即愈），二分到只剩模板才现形。所以：**根必须是
外层这一个常驻 div**，一切条件渲染都在它里面。 -->
<script setup lang="ts">
import {
  computed,
  nextTick,
  onScopeDispose,
  ref,
  shallowRef,
  watch
} from "vue";
import { useRoute, useRouter, RouterLink } from "vue-router";
import { useInfiniteQuery, useQuery, useMutation } from "@tanstack/vue-query";
import SearchIcon from "~icons/lucide/search";
import SquarePenIcon from "~icons/lucide/square-pen";
import ChevronRightIcon from "~icons/lucide/chevron-right";
import MoreHorizontalIcon from "~icons/lucide/more-horizontal";
import {
  conversations,
  renameConversation,
  conversationDetail,
  removeConversation,
  reattachChat,
  streamChat
} from "@/api/knowledge/chat";
import { ApiError } from "@/api/knowledge/client";
import { me as meApi } from "@/api/knowledge/auth";
import { readiness as readinessApi } from "@/api/knowledge/kbs";
import type { ConversationRow, ConversationMessage } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { nextStep } from "@/domain/nextStep";
import { liveAnswer, type LiveHandle, type Turn } from "@/domain/liveAnswer";
import { convMarks } from "@/domain/unread";
import { followsBottom } from "@/domain/chatScroll";
import { copyAndSay } from "@/domain/clipboard";
import { toast } from "@/domain/toast";
import { queryClient } from "@/store/knowledge/query";
import { useKbScope } from "@/composables/useKbScope";
import { useKbContext } from "@/composables/useKbContext";
import { useSyncExternal } from "@/composables/useSyncExternal";
import { DangerConfirm } from "@/components/knowledge/dialog";
import { UIconButton, URow, cn, REVEAL } from "@/components/knowledge/kit";
import PageHeader from "@/components/PageHeader/index.vue";
import { UTurn } from "./turn";
import { UConvMark } from "./convMark";
import ChatComposer from "./ChatComposer.vue";

/* 同标签页记忆：上次会话（按库）与未发送草稿——切页回来还原，新标签页从头开始 */
const lastKey = (kbId: string) => `chat:last:${kbId}`;
const DRAFT_KEY = "chat:draft";

const viewKey = (kbId: string, id: string | null) => `${kbId}/${id ?? ""}`;

/** 能重答的那一问在屏上的位置（#936），没有就是 -1。只看最后一问：它后面要么
 *  什么也没有（重开会话时的样子），要么只有一条出错的回答（刚答到一半失败）。
 *  服务端同样只答会话的最后一条，答过的、后面又有人接着问的都不答 */
function retryableQuestion(shown: Turn[]): number {
  const last = shown.at(-1);
  const at =
    last?.role === "assistant" && last.error
      ? shown.length - 2
      : last?.role === "user"
        ? shown.length - 1
        : -1;
  return at >= 0 && shown[at].role === "user" && shown[at].id ? at : -1;
}

const historyTurns = (messages: ConversationMessage[]): Turn[] =>
  messages.map(m => ({
    role: m.role,
    id: m.id,
    content: m.content,
    steps: m.steps.length ? m.steps : undefined,
    sources: m.sources.length ? m.sources : undefined,
    stopped: m.stopped
  }));

const route = useRoute();
const router = useRouter();

/* ---- KB 作用域（useKbScope：KbScope 宿主职责的 composable 对等物）----
   + 库切换面（useKbContext：旧仓 useKb 的 Vue 面） */
const scope = useKbScope();
const kbId = scope.kbId;
const kb = computed(() => scope.kb.value ?? null);
const { kbs, switchKb } = useKbContext();

/* **只拦模型这一档。** 空的知识库照样能聊——挂载的数据库查得了，记忆也读得到；
   没有对话模型才是真的一句都问不出来，而问候语从前照常显示，用户敲完第一句
   才撞墙（#313） */
const me = useQuery({ queryKey: ["me"], queryFn: meApi });
const readiness = useQuery({
  queryKey: computed(() => ["readiness", kbId.value]),
  queryFn: () => readinessApi(kbId.value),
  enabled: computed(() => !!kbId.value),
  staleTime: 10_000
});
const modelStep = computed(() =>
  readiness.data.value?.has_chat_model
    ? null
    : nextStep(readiness.data.value, {
        kbId: kbId.value,
        isAdmin: !!me.data.value?.is_admin,
        canUpload: kb.value?.my_role !== "viewer"
      })
);

// 会话即路由：URL 是当前会话的唯一事实来源
const conversationId = computed(
  () =>
    // jknow 落位（vue-router 4 ↔ 5 语义差）：源仓 vue-router 5 的可缺省参数
    // （:conversationId?）缺省时是 undefined；若依基线的 vue-router 4 给的是
    // 空串——归一回 undefined，下面的 ?? 判据与 liveAnswer 的 null 键才对得上
    (route.params as { conversationId?: string } | undefined)?.conversationId ||
    undefined
);
const activeId = ref<string | null>(null);
// 路由同步 watcher 的判据：state 的提交时序晚于 navigate 触发的重渲染，
// 用 ref 同步写入才能让"流式新建后仅换 URL"的守卫可靠命中
const activeIdRef = ref<string | null>(null);
// 读者是不是停在对话底部（见 chatScroll.ts）。换会话、发新消息都回到「跟着」：
// 那一刻人要看的就是底部
const following = ref(true);
// 已经结束的那些轮次，从库里读来。**进行中的那一次不在这里**——见 liveAnswer
const turns = ref<Turn[]>([]);
const loadedKey = ref<string | null>(null);
const idleHistoryKey = ref<string | null>(null);
const historyError = ref<string | null>(null);
const loadingHistory = ref(false);

// Object identity is the viewing epoch: A → B → A creates three owners.
// Generation handles live separately and continue after this view leaves.
// **必须是 shallowRef**：深 ref 会把存进去的对象包成 reactive 代理，
// .value 读回来的是代理不是原对象——`ownsView` 的引用相等就此永远落空。
type ViewRequest = { kbId: string; id: string | null };
const viewRequest = shallowRef<ViewRequest>({
  kbId: kbId.value,
  id: conversationId.value ?? null
});
const claimView = (id: string | null): ViewRequest => {
  const request = { kbId: kbId.value, id };
  viewRequest.value = request;
  return request;
};
const ownsView = (request: ViewRequest) => viewRequest.value === request;
const previousRoute = ref(viewKey(kbId.value, conversationId.value ?? null));
/* 路由变化：旧 view 失效、视图状态清零（先于装载 watcher 执行——顺序见下） */
watch([kbId, conversationId], () => {
  const key = viewKey(kbId.value, conversationId.value ?? null);
  if (previousRoute.value === key) return;
  previousRoute.value = key;
  // 新建会话的 URL 同步也会走这里：旧 send owner 失效、activeIdRef 清空，
  // 因而 route-sync 会调用 loadConversation。onConversation 已先 identify 生成句柄，
  // loadConversation 必须先检查 liveAnswer.entry，直接认领，避免流中途读库覆盖。
  claimView(conversationId.value ?? null);
  activeIdRef.value = null;
  following.value = true;
  activeId.value = conversationId.value ?? null;
  turns.value = [];
  loadedKey.value = null;
  historyError.value = null;
  loadingHistory.value = false;
});
/* 卸载即离场：在途回调从此不再拥有视图（旧仓 unmount effect 的同一件事） */
onScopeDispose(() => {
  viewRequest.value = { ...viewRequest.value };
  activeIdRef.value = null;
});

const input = ref(sessionStorage.getItem(DRAFT_KEY) ?? "");
/* **按 URL 认领，不按 state。** 这个文件开头就写着「URL 是当前会话的唯一
   事实来源」，而这里一度用了 `activeId`——它是 state，切走再回来时更新得
   比第一次渲染晚，于是那一帧认不出自己，屏幕空着。用地址栏里的那个 id
   就没有时序可言。新会话还没拿到 id 时两者都是空，也对得上 */
const currentId = computed(() => conversationId.value ?? activeId.value);
// 进行中的那些回答都活在组件之外（见 liveAnswer.ts），这里只认领「正在看的
// 这一场」。别场的任何变更都不动这一场的快照引用
const liveAll = useSyncExternal(liveAnswer.subscribe, liveAnswer.get);
const liveHere = computed(
  () =>
    liveAll.value.find(
      e =>
        e.kbId === (kbId.value || null) && e.conversationId === currentId.value
    ) ?? null
);
/* **是「这一场」在流，不是「有一场」在流。** 写成全局的话，另一场在生成时
   这一场的输入框也会变成停止按钮、发不出消息，而且最后一轮会被当成还在流——
   引用于是被藏起来（旧仓注释原话） */
const streaming = computed(() => liveHere.value?.streaming ?? false);
const shown = computed(() =>
  liveHere.value
    ? liveHere.value.turns
    : loadedKey.value === viewKey(kbId.value, currentId.value)
      ? turns.value
      : []
);
const retryAt = computed(() => retryableQuestion(shown.value));
const pendingDelete = ref<ConversationRow | null>(null);
// 会话搜索。**搜标题也搜正文**——人记得住的往往是问过的那句话
const convSearch = ref("");
// 「最近」这一组收起来没有。默认展开：左栏本来就是为了看见这些会话
const recentOpen = ref(true);
/* 左栏每一条会话的记号（正在写 / 写完了还没看）。两张表都活在组件外面——
   "写完了"常常发生在这个组件已经卸载的时候（见 unread.ts 开头） */
const liveIds = useSyncExternal(convMarks.subscribe, convMarks.live);
const unreadIds = useSyncExternal(convMarks.subscribe, convMarks.unread);
const bottomRef = ref<HTMLElement | null>(null);
const composerEl = ref<{ focus: () => void } | null>(null);

/* 打开哪一场就等于看过哪一场：记号消掉，并且告诉 unread「此刻看的是它」——
   正看着的那一场写完时不该变成未读。传 null（新对话、删掉了当前这场）
   同样有意义：那之后写完的任何一场都算未读 */
watch(activeId, id => convMarks.view(id), { immediate: true });

/* ---- 会话列表（可搜可翻页） ---- */
const convs = useInfiniteQuery({
  queryKey: computed(
    () => ["conversations", kbId.value, convSearch.value] as const
  ),
  queryFn: ({ pageParam }) =>
    conversations(kbId.value!, convSearch.value, 30, pageParam as number),
  initialPageParam: 0,
  getNextPageParam: (last, pages) => {
    const loaded = pages.reduce(
      (count, page) => count + page.conversations.length,
      0
    );
    return last.conversations.length > 0 && loaded < last.total
      ? loaded
      : undefined;
  },
  enabled: computed(() => !!kbId.value && kb.value?.id === kbId.value),
  // 换搜索词时留着上一屏，别每敲一个字就清空再冒出来（Graph、Library 同理）。
  // 换了库不留：另一个库的会话一刻也不该出现在这里
  placeholderData: (prev, prevQuery) =>
    (prevQuery?.queryKey as readonly unknown[] | undefined)?.[1] === kbId.value
      ? prev
      : undefined
});
// Updated conversations can move between offset pages. Deduplicate by identity;
// invalidation refetches the loaded page range rather than appending stale offsets.
const conversationRows = computed<ConversationRow[]>(() => [
  ...new Map(
    (convs.data.value?.pages.flatMap(page => page.conversations) ?? []).map(
      c => [c.id, c]
    )
  ).values()
]);
// 改标题：**就地编辑**，不弹对话框——改一个名字不值得打断整页
const renamingId = ref<string | null>(null);
const renameDraft = ref("");
// 开口就能改（同 W1 M-8 口径：autofocus 不是 EP 的 API，得拿 ref 调 focus）
const renameInputEl = ref<{ focus: () => void } | null>(null);
function setRenameRef(el: unknown) {
  renameInputEl.value = (el as { focus: () => void } | null) ?? null;
}
const rename = useMutation({
  mutationFn: (v: { id: string; title: string }) =>
    renameConversation(kb.value!.id, v.id, v.title),
  onSuccess: () => {
    renamingId.value = null;
    queryClient.invalidateQueries({
      queryKey: ["conversations", kb.value?.id]
    });
  },
  onError: (e: Error) => toast.error(e.message)
});
watch(renamingId, v => {
  if (v) nextTick(() => renameInputEl.value?.focus());
});

// 直落底部（instant）：平滑滚动在流式追加下会一路慢爬。**只在人停在底部时落**：
// 生成期间 `shown` 每 33ms 变一次，翻上去读前文的人不该被每一批新字拽回来
watch(shown, () => {
  if (following.value) bottomRef.value?.scrollIntoView({ behavior: "instant" });
});

/* ---- 路由 → 会话装载；裸 /chat 还原本库上次会话（切页回来仍在原对话） ---- */
watch(
  [() => kb.value?.id, kbId, conversationId] as const,
  async () => {
    if (!kb.value || kb.value.id !== kbId.value) return;
    if (!conversationId.value) {
      const last = sessionStorage.getItem(lastKey(kb.value.id));
      if (last) {
        // 记忆的会话可能已被删/属别的库——装挂失败会安静回新对话（loadConversation）
        await router.replace({ path: `/kb/${kbId.value}/chat/${last}` });
      }
      return;
    }
    if (conversationId.value === activeIdRef.value) return; // 流式新建会话后仅 URL 同步，勿重载
    await loadConversation(conversationId.value);
  },
  { immediate: true }
);

const invalidateList = () =>
  queryClient.invalidateQueries({ queryKey: ["conversations", kb.value?.id] });

/** 列表点击只改 URL，装载由路由同步 watcher 负责 */
function openConversation(id: string) {
  if (id === currentId.value) return;
  claimView(id);
  router.push({ path: `/kb/${kbId.value}/chat/${id}` });
}

/** 接回一个正在生成的回答。没有在跑的话服务端回 `idle`，补读一次已保存历史。 */
function attachIfRunning(id: string, history: Turn[], owner: ViewRequest) {
  let abort = () => {};
  let handle: LiveHandle | null = null;
  let checkedIdle = false;
  const stop = reattachChat(owner.kbId, id, {
    onConversation: () => {},
    /* **快照到了才建这一轮。** 先摆一个空位再等回答的话，没有在跑的会话
       上会闪一下空的助手气泡——而那是绝大多数情况。
       快照是覆盖：它是那个回答此刻的全貌，不是增量（旧仓注释原话） */
    onSnapshot: s => {
      if (!ownsView(owner)) {
        abort();
        return;
      }
      if (handle) return;
      handle = liveAnswer.begin(
        owner.kbId,
        id,
        [
          ...history,
          {
            role: "assistant",
            content: s.content,
            steps: s.steps.length ? s.steps : undefined,
            sources: s.sources.length ? s.sources : undefined
          }
        ],
        abort
      );
      handle.identify(id, undefined, s.generation_id);
    },
    onSources: sources => handle?.patchLast(t => ({ ...t, sources })),
    onStep: step =>
      handle?.patchLast(t => ({ ...t, steps: [...(t.steps ?? []), step] })),
    onDelta: text =>
      handle?.patchLast(t => ({ ...t, content: t.content + text })),
    onDone: stopped => {
      handle?.finish(stopped);
      invalidateList();
    },
    onError: message => {
      if (!handle && ownsView(owner)) historyError.value = message;
      handle?.patchLast(t => ({ ...t, error: message }));
      handle?.finish();
    },
    onIdle: async () => {
      if (checkedIdle || handle || !ownsView(owner)) return;
      checkedIdle = true;
      try {
        // The answer may have committed between the history read and attach.
        // One read closes that handoff; never re-POST or recursively attach.
        const { messages } = await conversationDetail(owner.kbId, id);
        if (!ownsView(owner)) return;
        const refreshed = historyTurns(messages);
        turns.value = refreshed;
        loadedKey.value = viewKey(owner.kbId, id);
        idleHistoryKey.value =
          refreshed.at(-1)?.role === "user" ? viewKey(owner.kbId, id) : null;
      } catch (error) {
        if (ownsView(owner))
          historyError.value =
            error instanceof Error ? error.message : String(error);
      }
    }
  });
  abort = stop;
}

async function loadConversation(id: string) {
  const owner = claimView(id);
  idleHistoryKey.value = null;
  historyError.value = null;
  loadingHistory.value = false;
  // 回到正在写的那一场：直接认领，别去库里读——库里要等它写完才有那一行
  if (liveAnswer.entry(kb.value!.id, id)) {
    activeIdRef.value = id;
    activeId.value = id;
    return;
  }
  activeIdRef.value = id;
  activeId.value = id;
  turns.value = [];
  loadedKey.value = null;
  loadingHistory.value = true;
  try {
    const { messages } = await conversationDetail(owner.kbId, id);
    if (!ownsView(owner)) return;
    sessionStorage.setItem(lastKey(owner.kbId), id);
    const history = historyTurns(messages);
    turns.value = history;
    loadedKey.value = viewKey(owner.kbId, id);
    /* **刷新之后接回去。** 上面那个 store 只活在这一个页面里；刷新、
       新标签页、换台机器都拿不到它，而服务端那边生成还在跑。问一句
       「这个会话有没有在跑的」——没有是最常见的答案，代价是一次会
       立刻回 `idle` 的请求。最后一条是用户说的话时才问：那正好是
       「问了但还没答上」的形状（旧仓注释原话） */
    if (history[history.length - 1]?.role === "user") {
      attachIfRunning(id, history, owner);
    }
  } catch (error) {
    if (!ownsView(owner)) return;
    if (!(
      error instanceof ApiError && [401, 403, 404].includes(error.status)
    )) {
      historyError.value =
        error instanceof Error ? error.message : String(error);
      return;
    }
    // 失效链接（会话已删 / 属于别的库）：安静回到新对话
    sessionStorage.removeItem(lastKey(kb.value!.id));
    activeIdRef.value = null;
    activeId.value = null;
    turns.value = [];
    router.replace({ path: `/kb/${owner.kbId}/chat` });
  } finally {
    if (ownsView(owner)) loadingHistory.value = false;
  }
}

function newChat() {
  claimView(null);
  historyError.value = null;
  loadingHistory.value = false;
  loadedKey.value = null;
  // 同样不 abort：开一场新的不等于放弃上一场
  if (kb.value) sessionStorage.removeItem(lastKey(kb.value.id));
  activeIdRef.value = null;
  following.value = true;
  activeId.value = null;
  turns.value = [];
  router.push({ path: `/kb/${kbId.value}/chat` });
  composerEl.value?.focus();
}

async function removeConversationRow(id: string) {
  const owner = viewRequest.value;
  try {
    await removeConversation(kb.value!.id, id);
  } catch (e) {
    // 确认框已经关了：删不掉不说出来的话，这一行只是安静地留在列表里
    toast.error(e instanceof Error ? e.message : String(e));
    return;
  }
  // 记号跟着会话走，否则这个 id 会一直留在浏览器的那张表里
  convMarks.forget(id);
  if (sessionStorage.getItem(lastKey(kb.value!.id)) === id) {
    sessionStorage.removeItem(lastKey(kb.value!.id));
  }
  invalidateList();
  if (ownsView(owner) && id === activeIdRef.value) newChat();
}

function send() {
  const q = input.value.trim();
  if (
    !q ||
    !kb.value ||
    kb.value.id !== kbId.value ||
    loadingHistory.value ||
    historyError.value
  )
    return;
  if (liveAnswer.entry(kb.value.id, activeId.value)?.streaming) return;
  const owner = claimView(activeId.value);
  following.value = true;
  idleHistoryKey.value = null;
  input.value = "";
  sessionStorage.removeItem(DRAFT_KEY);

  answer(
    owner,
    kb.value.id,
    // 从屏上正在显示的那些轮续接，而不是组件 state——流结束后内容只落在
    // store 里，state 还是上次装 conversation 时的库内历史，用它会让
    // 上一条回答从画面里消失（旧仓注释原话）
    [
      ...(liveHere.value?.turns ?? turns.value),
      { role: "user", content: q },
      { role: "assistant", content: "" }
    ],
    { conversation_id: activeId.value ?? undefined, message: q }
  );
}

/** 重答最后那个没有回答的问题（#936）。问题已经在库里，不再存一遍：屏上它后面
 *  若挂着一条出错的回答，换成一条空的，带着它存下的 id 重新开流 */
function retry() {
  const at = retryableQuestion(shown.value);
  if (
    at < 0 ||
    !kb.value ||
    kb.value.id !== kbId.value ||
    !activeId.value ||
    loadingHistory.value ||
    historyError.value
  )
    return;
  if (liveAnswer.entry(kb.value.id, activeId.value)?.streaming) return;
  const question = shown.value[at];
  const owner = claimView(activeId.value);
  following.value = true;
  idleHistoryKey.value = null;
  answer(
    owner,
    kb.value.id,
    [...shown.value.slice(0, at + 1), { role: "assistant", content: "" }],
    {
      conversation_id: activeId.value,
      message: question.content,
      retry_message_id: question.id
    }
  );
}

/* **结果留在 store 里，不交回组件状态。** 交回去要经过一个 `setTurns`，而流结束
   时这个组件可能早就卸载了——那一下是空操作，内容就此消失（切回来一片空白，
   问题气泡都没有）。留在 store 里，谁挂载谁认领。这一场从开场起就有名有姓：
   先建条目、后开流，回调顺着句柄只写自己这一场。新问题与重答走同一段 */
function answer(
  owner: ViewRequest,
  kbNow: string,
  start: Turn[],
  body: Parameters<typeof streamChat>[1]
) {
  const handle = liveAnswer.begin(kbNow, activeId.value, start, () => {});
  const abort = streamChat(kbNow, body, {
    onConversation: (id, questionId, generationId) => {
      handle.identify(id, questionId, generationId);
      invalidateList();
      if (!ownsView(owner)) return;
      // 先 identify 生成句柄再换 URL；路由 watcher 重置视图后，loadConversation 会认领该句柄。
      activeIdRef.value = id;
      activeId.value = id;
      sessionStorage.setItem(lastKey(kbNow), id);
      router.replace({ path: `/kb/${kbId.value}/chat/${id}` });
    },
    onSources: sources => handle.patchLast(t => ({ ...t, sources })),
    onStep: step =>
      handle.patchLast(t => ({ ...t, steps: [...(t.steps ?? []), step] })),
    onDelta: text =>
      handle.patchLast(t => ({ ...t, content: t.content + text })),
    onDone: stopped => {
      handle.finish(stopped);
      invalidateList();
    },
    onError: (message, error) => {
      if (
        error?.status === 409 &&
        error.code === "answer_running" &&
        body.conversation_id
      ) {
        handle.discard();
        if (!ownsView(owner)) return;
        if (!body.retry_message_id) {
          const draft = [
            body.message,
            input.value ?? sessionStorage.getItem(DRAFT_KEY)
          ]
            .filter(Boolean)
            .join("\n");
          sessionStorage.setItem(DRAFT_KEY, draft);
          input.value = draft;
        }
        void loadConversation(body.conversation_id);
        return;
      }
      handle.patchLast(t => ({ ...t, error: message }));
      handle.finish();
    }
  });
  // streamChat 的 abort 要等它返回才有；真 abort 到手前，句柄上先占着空操作
  handle.setAbort(abort);
}

function onRowCommand(cmd: string, c: ConversationRow) {
  if (cmd === "rename") {
    renameDraft.value = c.title || "";
    renamingId.value = c.id;
  } else if (cmd === "copy") {
    void copyAndSay(c.title || "", S.ask.copied);
  } else if (cmd === "delete") {
    pendingDelete.value = c;
  }
}

function submitRename(c: ConversationRow) {
  if (renameDraft.value.trim())
    rename.mutate({ id: c.id, title: renameDraft.value });
  else renamingId.value = null;
}

function onComposerRescope(id: string) {
  claimView(null);
  switchKb(id);
}

const composerSendDisabled = computed(
  () => !input.value.trim() || loadingHistory.value || !!historyError.value
);
</script>

<template>
  <div class="knowledge-page utopia-page-scope h-full">
    <div
      v-if="scope.noAccess.value"
      class="utopia-page-scope grid h-full place-items-center px-6"
    >
      <div class="max-w-sm text-center">
        <h2 class="text-xl text-ink">
          {{
            scope.noAccess.value.status === 404
              ? S.kbScope.missingTitle
              : S.kbScope.deniedTitle
          }}
        </h2>
        <p class="mt-2 text-body/relaxed text-ink-2">
          {{
            scope.noAccess.value.status === 404
              ? S.kbScope.missingBody
              : S.kbScope.deniedBody
          }}
        </p>
        <el-button
          class="mt-4"
          size="default"
          @click="router.push('/account/kbs')"
        >
          {{ S.kbScope.myKbs }}
        </el-button>
      </div>
    </div>
    <div v-else class="utopia-page-scope flex h-full flex-col p-6">
      <!-- 页头（reskin-b3 六要素之一）：标题=对话、副题=库名；本页唯一主行动是
           左栏的「新对话」，页头不放 extra（检索/图谱页同口径） -->
      <PageHeader :title="S.nav.ask" :subtitle="kb?.name ?? ''" />

      <div class="flex min-h-0 flex-1 gap-4">
        <!-- 会话列表卡（reskin-b3 三卡之一）：anivia 卡容器（theme.scss .card），
           选中态由 URow 的 bg-primary-soft 提供（零动） -->
        <aside class="card chat-rail flex w-64 shrink-0 flex-col">
          <!-- 搜索在最上面，与图谱页的搜索框、本体页的过滤框同一副身材、同一个
             角落。**标题重是常态**（同一个问题问两次就重了），而正文里那句话才是人
             记得住的——所以服务端两处都搜 -->
          <div class="pb-2">
            <el-input
              v-model="convSearch"
              :placeholder="S.ask.searchConversations"
              clearable
              @keydown.escape="convSearch = ''"
            >
              <template #prefix>
                <SearchIcon width="12" height="12" />
              </template>
            </el-input>
          </div>
          <!-- 新建会话：本页唯一主行动（批次模板语言：el-button primary 实底） -->
          <div class="pb-2">
            <el-button type="primary" class="w-full" @click="newChat">
              <SquarePenIcon width="14" height="14" class="mr-1" />
              {{ S.ask.newChat }}
            </el-button>
          </div>
          <!-- 「最近」是这一组的名字，不是一条会话：同一副行的身材、同一档字色，
              右端的三角说明这一组收得起来（朝右=收着，朝下=开着） -->
          <div>
            <URow density="nav" @click="recentOpen = !recentOpen">
              {{ S.ask.recent }}
              <template #trailing>
                <ChevronRightIcon
                  width="12"
                  height="12"
                  :class="cn('turn', recentOpen && 'rotate-90')"
                />
              </template>
            </URow>
          </div>
          <div
            v-if="recentOpen"
            class="flex flex-col gap-0.5 flex-1 overflow-y-auto overscroll-contain pb-3"
          >
            <div v-for="c in conversationRows" :key="c.id" class="group relative">
              <!-- 单行标题；删除键悬停浮现（弹确认，不直接删） -->
              <el-input
                v-if="renamingId === c.id"
                :ref="setRenameRef"
                v-model="renameDraft"
                size="small"
                class="w-full"
                @keydown.enter.prevent="submitRename(c)"
                @keydown.escape="renamingId = null"
                @blur="renamingId = null"
              />
              <URow
                v-else
                density="nav"
                class="pr-8"
                :active="c.id === activeId"
                @click="openConversation(c.id)"
              >
                <template #icon>
                  <UConvMark
                    :state="
                      liveIds.has(c.id)
                        ? 'live'
                        : unreadIds.has(c.id)
                          ? 'unread'
                          : 'rest'
                    "
                  />
                </template>
                <span
                  :class="[
                    'block truncate pr-6 text-body',
                    c.id === activeId ? 'text-ink' : 'text-ink-2'
                  ]"
                >
                  {{ c.title || S.ask.untitled }}
                </span>
              </URow>
              <!-- 三点菜单：**一个入口装下所有动作**（从前右边直接是删除，而删除是
                 这里最不该一步到位的那个）。走 el-dropdown：弹层样式与全站统一 -->
              <el-dropdown
                v-if="renamingId !== c.id"
                trigger="click"
                class="absolute right-1 top-1/2 -translate-y-1/2"
                :class="REVEAL"
                @command="(cmd: string) => onRowCommand(cmd, c)"
              >
                <UIconButton :label="S.ask.moreActions" @click.stop />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="rename">{{
                      S.ask.rename
                    }}</el-dropdown-item>
                    <el-dropdown-item command="copy">{{
                      S.ask.copyTitle
                    }}</el-dropdown-item>
                    <el-dropdown-item command="delete" class="text-danger!">
                      {{ S.ask.deleteConversation }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
            <div
              v-if="convs.isError.value"
              role="alert"
              class="px-3 py-2 text-sub text-ink-2"
            >
              <p>{{ S.ask.conversationsLoadFailed }}</p>
              <el-button
                size="small"
                link
                @click="
                  convs.isFetchNextPageError.value
                    ? convs.fetchNextPage()
                    : convs.refetch()
                "
              >
                {{ S.ask.retryConversations }}
              </el-button>
            </div>
            <!-- 加载更早：有下一页且整条查询没挂时才给（旧仓 !isFetchNextPageError——
               那是「取下一页失败才收」，isError 是「任意页失败都收」；首屏就挂时
               翻页无从谈起，重试走上面的 retryConversations） -->
            <div
              v-if="convs.hasNextPage.value && !convs.isError.value"
              class="px-2 pt-1"
            >
              <el-button
                size="small"
                link
                :disabled="convs.isFetching.value"
                @click="convs.fetchNextPage()"
              >
                {{ S.ask.loadEarlierConversations }}
              </el-button>
            </div>
            <!-- 这句话与上面每一条会话的标题同一条竖线（38 = 卡内距 12 + 行内距
               12 + 图标格 14），而不是自己另起一列 -->
            <p
              v-if="convs.isSuccess.value && conversationRows.length === 0"
              class="py-2 pl-[38px] pr-3 text-sub text-ink-2"
            >
              {{ S.ask.noConversations }}
            </p>
          </div>
        </aside>

        <!-- 对话区（reskin-b3 三卡之二/之三：消息流的消息卡 + Composer 输入卡）：
           新对话首屏 = 问候（+下一步提示），composer 随问候落位；
           有消息后上区变滚动区、composer 停靠底部。
           **composer 只挂一份、常驻不卸载**：EP 的 textarea autosize 在 nextTick 里
           回读 DOM，卸载撞上那个回调会读 null 抛未处理异常——旧仓 React 无此约束，
           所以旧仓可以同一块 JSX 挂两处随分支换，这里必须一实体两态（新对话/
           装载中的 focus 也因此拿得到同一实例） -->
        <div class="min-w-0 flex flex-1 flex-col">
          <div
            v-if="
              idleHistoryKey === loadedKey &&
              idleHistoryKey === viewKey(kbId, currentId) &&
              !streaming
            "
            role="status"
            class="px-4 pt-3 flex flex-wrap items-center gap-2 text-body text-ink-2"
          >
            <span>{{ S.ask.noActiveAnswer }}</span>
            <el-button
              v-if="retryAt === shown.length - 1"
              size="small"
              @click="retry"
            >
              {{ S.ask.retryQuestion }}
            </el-button>
          </div>
          <!-- 优先级与旧仓一致：历史读挂了 > 正在装 > 问候首屏 > 对话滚动区 -->
          <div
            v-if="historyError"
            role="alert"
            class="card chat-error-card flex-1"
          >
            <p class="text-danger">{{ S.ask.historyLoadFailed }}</p>
            <p class="text-ink-2">{{ historyError }}</p>
            <el-button
              class="mt-2"
              @click="currentId && loadConversation(currentId)"
            >
              {{ S.ask.retryHistory }}
            </el-button>
          </div>
          <div
            v-else-if="loadingHistory && !liveHere"
            role="status"
            class="flex-1 p-6 text-body text-ink-2"
          >
            {{ S.ask.loadingHistory }}
          </div>
          <!-- 锚定上三分之一而非垂直居中：居中在高窗口下会显得下坠。
             22vh + 顶部 chrome(~100px) ≈ 问候落在 37% 高度（旧仓注释原话） -->
          <div v-else-if="shown.length === 0" class="px-4 pt-[22vh]">
            <div class="w-full max-w-3xl mx-auto">
              <h1 class="chat-greeting text-center text-stat text-ink mb-8">
                {{ S.ask.greeting }}
              </h1>
              <div v-if="modelStep" class="mb-6 grid place-items-center">
                <div class="max-w-xs text-center text-body text-ink-2">
                  {{ modelStep.line }}
                  <RouterLink
                    v-if="modelStep.action"
                    class="link-inline mt-3 block"
                    :to="{
                      path: modelStep.action.to,
                      query: modelStep.action.search as never
                    }"
                  >
                    {{ modelStep.action.label }}
                  </RouterLink>
                </div>
              </div>
            </div>
          </div>
          <div
            v-else
            class="flex-1 overflow-y-auto overscroll-contain chat-fade px-4 pt-6 pb-12"
            @scroll="
              e => {
                following = followsBottom(e.currentTarget as HTMLElement);
              }
            "
          >
            <div class="max-w-3xl mx-auto space-y-4">
              <UTurn
                v-for="(t, i) in shown"
                :key="i"
                :turn="t"
                :live="streaming && i === shown.length - 1"
                :kb-id="kbId"
                :show-retry="
                  !streaming &&
                  i === shown.length - 1 &&
                  t.role === 'assistant' &&
                  retryAt >= 0
                "
                @retry="retry"
              />
              <div ref="bottomRef" />
            </div>
          </div>
          <div class="px-4 pt-3 pb-1">
            <div class="max-w-3xl mx-auto">
              <ChatComposer
                ref="composerEl"
                v-model="input"
                :streaming="streaming"
                :stopping="liveHere?.stopping"
                :stop-error="liveHere?.stopError"
                :send-disabled="composerSendDisabled"
                :kb="kb"
                @send="send"
                @stop="liveAnswer.stop(kbId, currentId)"
                @rescope="onComposerRescope"
              />
            </div>
          </div>
        </div>
      </div>
      <DangerConfirm
        v-if="pendingDelete"
        :title="S.ask.deleteTitle"
        :hint="S.ask.deleteHint(pendingDelete.title || S.ask.untitled)"
        :confirm-label="S.ask.deleteBtn"
        :cancel-label="S.ask.cancel"
        @confirm="
          () => {
            removeConversationRow(pendingDelete!.id);
            pendingDelete = null;
          }
        "
        @cancel="pendingDelete = null"
      />
    </div>
  </div>
</template>

<style scoped>
/* 重造层（reskin-b3）：只引令牌（--color-* / --radius-* / --shadow-*），
   禁硬编码色值；卡容器形态来自全局 .card（theme.scss），徽章形态来自全局
   .badge-soft。script 逻辑零动——本块只管三卡的容器身材 */

/* 会话列表卡：256px 窄栏不吃 .card 默认 20px 内距，收到 12px；
   列表滚动条随之落在卡内缘 */
.chat-rail {
  padding: 12px;
}

/* 历史读挂错误态卡（对齐检索页 state-card is-error 的口径：卡面 + 首行危险色） */
.chat-error-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  justify-content: center;
  max-width: 48rem;
  margin: 0 auto;
  width: 100%;
}
</style>
