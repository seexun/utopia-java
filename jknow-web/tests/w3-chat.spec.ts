/* W3 聊天页（任务 8）：脚本 SSE 注入（FakeEventSource 的前端版——脚本帧通过
   stub fetch 的 ReadableStream 喂进 api/chat 的 SSE 解析器），断言帧处理 UI 接线：
   conversation 换 URL / step 轨迹出现 / delta 渲染（markdown+引用角标）/
   sources 落款 / done 收口（停止键收回）；加无模型 422 直显、停止 API、
   会话改名删除、刷新接回 idle→重答（retry_message_id）。
   帧解析本体已由 domain/chatStream.test.ts + liveAnswer.stream.test.ts 覆盖，
   这里只管「帧到了页面上长得出来」。 */
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount, flushPromises, type VueWrapper } from "@vue/test-utils";
import { createRouter, createMemoryHistory, type Router } from "vue-router";
import { ref } from "vue";
import { VueQueryPlugin } from "@tanstack/vue-query";
import ElementPlus from "element-plus";
import { queryClient } from "@/store/knowledge/query";
import { store as globalPinia } from "@/store/knowledge";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { S } from "@/i18n";
import Chat from "@/views/jknow/knowledge/chat/index.vue";
import type { ConversationMessage, ConversationRow } from "@/api/knowledge/types";

const m = vi.hoisted(() => ({
  conversations: vi.fn(),
  renameConversation: vi.fn(),
  conversationDetail: vi.fn(),
  removeConversation: vi.fn(),
  me: vi.fn(),
  kbDetail: vi.fn(),
  readiness: vi.fn()
}));

vi.mock("@/api/knowledge/chat", async importOriginal => {
  const actual = await importOriginal<typeof import("@/api/knowledge/chat")>();
  /* streamChat / reattachChat 走真身——SSE 从 stub fetch 的脚本流进来 */
  return {
    ...actual,
    conversations: m.conversations,
    renameConversation: m.renameConversation,
    conversationDetail: m.conversationDetail,
    removeConversation: m.removeConversation
  };
});
vi.mock("@/api/knowledge/auth", () => ({ me: m.me }));
vi.mock("@/api/knowledge/kbs", () => ({ kbDetail: m.kbDetail, readiness: m.readiness }));
/* 作用域弹层/切库不在本任务断言面里；rebuildMenus 需要壳级路由器，整面桩掉 */
vi.mock("@/composables/useKbContext", () => ({
  useKbContext: () => ({
    kb: ref(null),
    kbs: ref([]),
    workspace: ref(null),
    workspaces: ref([]),
    settled: ref(true),
    switchKb: vi.fn(),
    rebuildMenus: vi.fn()
  }),
  resolveLegacyKbPath: vi.fn()
}));

/* ---------- 脚本 SSE 注入 ---------- */

const calls: { url: string; method?: string; body?: string }[] = [];
/** 开着不关的 SSE 流的控制器（停止测试用：先流着，人按停止后由测试收口） */
let liveController: ReadableStreamDefaultController<Uint8Array> | null = null;
/** 最近一次 POST /chat 的请求体（重答断言用） */
let lastChatBody: Record<string, unknown> | null = null;
/** 下一次打开 /chat 流时喂的脚本帧 */
let nextScript: string[] = [];
/** 本流喂完初始帧后保持不关（停止测试：由测试收口） */
let keepNextOpen = false;
let streamOpenCount = 0;

function frame(event: string, data: unknown): string {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

function makeStream(
  frames: string[],
  keepOpen: boolean
): ReadableStream<Uint8Array> {
  const enc = new TextEncoder();
  return new ReadableStream<Uint8Array>({
    start(controller) {
      for (const f of frames) controller.enqueue(enc.encode(f));
      if (keepOpen) liveController = controller;
      else controller.close();
    }
  });
}

function sseRes(stream: ReadableStream<Uint8Array>): Response {
  return new Response(stream, {
    status: 200,
    headers: { "content-type": "text/event-stream" }
  });
}

const fetchStub = vi.fn(
  async (input: unknown, init?: { method?: string; body?: string }) => {
    const url = String(input);
    calls.push({ url, method: init?.method, body: init?.body });
    if (url.endsWith("/chat") && init?.method === "POST") {
      lastChatBody = JSON.parse(init.body ?? "{}");
      const script = nextScript;
      nextScript = [];
      const keepOpen = keepNextOpen || script.length === 0; // 空脚本 = 开着不关（等待测试收口）
      keepNextOpen = false;
      streamOpenCount++;
      return sseRes(makeStream(script, keepOpen));
    }
    if (url.endsWith("/stop")) {
      return new Response(JSON.stringify({ ok: true }), { status: 200 });
    }
    if (url.endsWith("/stream")) {
      return sseRes(makeStream([frame("idle", "idle")], false));
    }
    return new Response(JSON.stringify({ error: "unexpected" }), {
      status: 404
    });
  }
);

/** 轮询直到条件成立（帧的到达与 liveAnswer 的 33ms 通知都是异步的） */
async function until(fn: () => boolean, what: string, timeout = 3000) {
  const start = Date.now();
  for (;;) {
    if (fn()) return;
    if (Date.now() - start > timeout)
      throw new Error(`condition not met: ${what}`);
    await new Promise(r => setTimeout(r, 20));
    await flushPromises();
  }
}

function bodyText(w: VueWrapper): string {
  return w.element.textContent ?? "";
}

/* ---------- 挂载 ---------- */

let mounted: VueWrapper | null = null;
let el: HTMLElement | null = null;
let router: Router | null = null;

async function mountChat(path = "/kb/kb1/chat") {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      /* 生产形态（M-2）：可选参数单记录，与 src/router/modules/utopia.ts 同形 */
      {
        path: "/kb/:kbId/chat/:conversationId?",
        name: "KbChat",
        component: Chat
      },
      { path: "/:pathMatch(.*)*", component: { template: "<div/>" } }
    ]
  });
  el = document.createElement("div");
  document.body.appendChild(el);
  await router.push(path);
  await router.isReady();
  mounted = mount(Chat, {
    global: {
      plugins: [
        ElementPlus,
        globalPinia,
        router,
        [VueQueryPlugin, { queryClient }]
      ]
    },
    attachTo: el!
  });
  await flushPromises();
  return mounted!;
}

async function send(w: VueWrapper, text: string) {
  await w.find("textarea").setValue(text);
  const sendBtn = w
    .findAll("button")
    .find(b => b.attributes("aria-label") === S.ask.send);
  expect(sendBtn, "发送键不存在或被禁用").toBeTruthy();
  await sendBtn!.trigger("click");
  await flushPromises();
}

const convRow = (over: Partial<ConversationRow> = {}): ConversationRow => ({
  id: "c1",
  title: "Chat A",
  created_at: "2026-11-15T00:00:00Z",
  updated_at: "2026-11-15T00:00:00Z",
  message_count: 2,
  ...over
});

const msg = (over: Partial<ConversationMessage>): ConversationMessage => ({
  id: "x",
  role: "user",
  content: "",
  steps: [],
  sources: [],
  stopped: false,
  created_at: "2026-11-15T00:00:00Z",
  ...over
});

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem("utopia.lang", "en");
  sessionStorage.clear();
  // pinia 全局单例跨用例残留上一角色的 kbDetail——清掉
  useKbContextHook().$reset();
  queryClient.clear();
  for (const f of Object.values(m)) f.mockReset();
  calls.length = 0;
  liveController = null;
  lastChatBody = null;
  nextScript = [];
  keepNextOpen = false;
  streamOpenCount = 0;
  vi.stubGlobal("fetch", fetchStub);
  // jsdom 没有 scrollIntoView（跟随底部的直落）
  Element.prototype.scrollIntoView = vi.fn();
  m.me.mockResolvedValue({ is_admin: false, display_name: "U" });
  m.kbDetail.mockResolvedValue({
    id: "kb1",
    name: "General",
    my_role: "editor",
    workspace_id: "ws1"
  });
  m.readiness.mockResolvedValue({
    has_chat_model: true,
    documents: 3,
    processing: 0,
    failed: 0,
    entities: 5
  });
  m.conversations.mockResolvedValue({ conversations: [], total: 0 });
  m.conversationDetail.mockResolvedValue({ messages: [] });
  m.renameConversation.mockResolvedValue({ ok: true });
  m.removeConversation.mockResolvedValue({ ok: true });
});

afterEach(() => {
  // 开着的流先关掉，reader 循环才能退
  try {
    liveController?.close();
  } catch {
    /* 已关 */
  }
  vi.unstubAllGlobals();
  mounted?.unmount();
  el?.remove();
  mounted = null;
  el = null;
  router = null;
});

describe("W3 聊天页：脚本 SSE 注入", () => {
  it("delta 渲染 / steps 轨迹 / sources 落款 / done 收口：全帧路径", async () => {
    /* 第一批帧：conversation + step + 首 delta。流开着，先断言流中形态 */
    keepNextOpen = true;
    nextScript = [
      frame("conversation", {
        id: "conv1",
        message_id: "m1",
        generation_id: "g1"
      }),
      frame("step", {
        kind: "search",
        label: "Search documents",
        detail: "",
        status: "ok",
        count: 2,
        at: 0
      }),
      frame("step", {
        kind: "query",
        label: "How many documents",
        detail: "count docs",
        status: "ok",
        count: 3,
        more: false,
        sql: "SELECT count(*) FROM documents;",
        at: 0
      }),
      frame("delta", { text: "A **tree** walk cites [1].\n" })
    ];

    const w = await mountChat();
    await send(w, "问题一句话");

    /* conversation 帧：会话即路由——URL 换到这场 */
    await until(
      () => router!.currentRoute.value.path === "/kb/kb1/chat/conv1",
      "conversation 帧换 URL"
    );
    expect(sessionStorage.getItem("chat:last:kb1")).toBe("conv1");
    /* 列表失效（新会话要冒出来） */
    expect(m.conversations.mock.calls.length).toBeGreaterThan(1);

    /* 流中：停止键在位，delta 已渲染（经 remend/markdown 管线），轨迹已出现 */
    await until(
      () =>
        w
          .findAll("button")
          .some(b => b.attributes("aria-label") === S.ask.stop),
      "流中停止键出现"
    );
    await until(
      () => bodyText(w).includes("tree walk cites"),
      "delta 文字渲染"
    );
    expect(bodyText(w)).toContain("Search documents");

    /* 第二批帧：补一段围栏代码 + 来源，done 收口 */
    const enc = new TextEncoder();
    liveController!.enqueue(
      enc.encode(
        frame("delta", { text: "\n```sql\nSELECT 1;\n```" }) +
          frame("sources", [
            {
              n: 1,
              filename: "doc-a.pdf",
              excerpt: "the excerpt text",
              chunk_id: "ck1",
              document_id: "d1"
            }
          ]) +
          frame("done", { stopped: false })
      )
    );
    liveController!.close();

    /* done 收口：停止键收回、发送键回来 */
    await until(
      () =>
        w
          .findAll("button")
          .some(b => b.attributes("aria-label") === S.ask.send),
      "done 后发送键回来"
    );

    /* steps 轨迹：话按读者的语言说（stepLabel/stepDetail） */
    expect(bodyText(w)).toContain(S.ask.step.sources(2));

    /* markdown 管线（PoC）：粗体与围栏代码高亮，且挂在 .chat-prose 壳内
       （C-1，审查收口：皮肤全族挂在 .chat-prose 祖先上，壳丢了=样式全灭） */
    expect(w.element.querySelector("strong")).toBeTruthy();
    const code = w.element.querySelector("pre code");
    expect(code?.className).toContain("language-sql");
    expect(code?.className).toContain("hljs");
    const prose = code?.closest(".chat-prose");
    expect(prose, "UMarkdown 没有带 .chat-prose 壳").toBeTruthy();
    expect(prose?.contains(w.element.querySelector("strong")!)).toBe(true);

    /* 引用角标：正文里的 [1] 成了可点的 chip；落款只列引到的那条 */
    const chip = w
      .findAll("button")
      .find(
        b => b.text().trim() === "[1]" && b.classes().includes("tabular-nums")
      );
    expect(chip, "正文角标 chip 没画出来").toBeTruthy();
    await until(() => bodyText(w).includes("doc-a.pdf"), "引用落款渲染");
  });

  it("问数那一步展开 SQL（#936）：点 SQL 展开原文，再点收回（I-1 内部状态）", async () => {
    nextScript = [
      frame("conversation", {
        id: "conv1",
        message_id: "m1",
        generation_id: "g1"
      }),
      frame("step", {
        kind: "query",
        label: "How many documents",
        detail: "count docs",
        status: "ok",
        count: 3,
        more: false,
        sql: "SELECT count(*) FROM documents;",
        at: 0
      }),
      frame("delta", { text: "共 3 篇。" }),
      frame("done", { stopped: false })
    ];

    const w = await mountChat();
    await send(w, "有多少文档");
    await until(
      () =>
        w
          .findAll("button")
          .some(b => b.attributes("aria-label") === S.ask.send),
      "done 收口"
    );

    /* 初始收着：SQL 原文不在屏上 */
    expect(bodyText(w)).not.toContain("SELECT count(*) FROM documents;");
    const sqlBtn = w
      .findAll("button")
      .find(b => (b.text() ?? "").trim() === S.ask.step.sql);
    expect(sqlBtn, "SQL 展开键不存在").toBeTruthy();
    expect(sqlBtn!.attributes("aria-expanded")).toBe("false");

    /* 点开：原文出现 */
    await sqlBtn!.trigger("click");
    await flushPromises();
    expect(bodyText(w)).toContain("SELECT count(*) FROM documents;");
    expect(sqlBtn!.attributes("aria-expanded")).toBe("true");

    /* 点回：原文收回 */
    await sqlBtn!.trigger("click");
    await flushPromises();
    expect(bodyText(w)).not.toContain("SELECT count(*) FROM documents;");
    expect(sqlBtn!.attributes("aria-expanded")).toBe("false");
  });

  it("停止：流中按停止 → stop API 带上 generation_id → done(stopped) 落「已停止」", async () => {
    keepNextOpen = true;
    nextScript = [
      frame("conversation", {
        id: "conv1",
        message_id: "m1",
        generation_id: "g1"
      }),
      frame("delta", { text: "still writing" })
    ];
    const w = await mountChat();
    await send(w, "问一句");

    const stopBtn = () =>
      w.findAll("button").find(b => b.attributes("aria-label") === S.ask.stop);
    await until(() => !!stopBtn(), "停止键出现");
    await stopBtn()!.trigger("click");
    await flushPromises();

    /* stop API：generation_id 来自 conversation 帧 */
    const stopCall = calls.find(c => c.url.endsWith("/stop"));
    expect(stopCall, "stop API 没被调").toBeTruthy();
    expect(JSON.parse(stopCall!.body ?? "{}")).toEqual({ generation_id: "g1" });

    /* 服务端收尾：done(stopped) → 「已停止」落款，键收回 */
    liveController!.enqueue(
      new TextEncoder().encode(frame("done", { stopped: true }))
    );
    liveController!.close();
    await until(() => bodyText(w).includes(S.ask.stopped), "已停止落款");
    await until(() => !stopBtn(), "停止键收回");
  });

  it("无模型（422 no_chat_model）：问候屏给下一步；发送被拒后拒绝话术直显在那一轮", async () => {
    m.readiness.mockResolvedValue({
      has_chat_model: false,
      documents: 0,
      processing: 0,
      failed: 0,
      entities: 0
    });
    m.me.mockResolvedValue({ is_admin: true, display_name: "U" });
    const w = await mountChat();

    /* 问候屏的 NextStep（#313：别等他敲完第一句才撞墙） */
    expect(bodyText(w)).toContain(S.steps.noModel);
    expect(bodyText(w)).toContain(S.steps.configureModel);

    /* 发送：服务端 422 拒绝，措辞按 code 查 err 表（refusalMessage） */
    fetchStub.mockImplementationOnce(
      async () =>
        new Response(JSON.stringify({ code: "no_chat_model" }), {
          status: 422,
          statusText: "Unprocessable Entity"
        })
    );
    await send(w, "你好");
    await until(
      () => bodyText(w).includes(S.err.no_chat_model),
      "no_chat_model 话术直显"
    );
    /* 出错的那一轮：问题在屏上，红字跟着它 */
    expect(bodyText(w)).toContain("你好");
  });

  it("会话改名与删除（CRUD）：就地改名提交、DangerConfirm 确认后删除", async () => {
    m.conversations.mockResolvedValue({ conversations: [convRow()], total: 1 });
    const w = await mountChat();

    /* 打开三点菜单（EP 弹层 teleport 到 body） */
    const moreBtn = w
      .findAll("button")
      .find(b => b.attributes("aria-label") === S.ask.moreActions);
    expect(moreBtn, "三点菜单入口不存在").toBeTruthy();
    await moreBtn!.trigger("click");
    await flushPromises();
    await new Promise(r => setTimeout(r, 30));

    const items = () =>
      Array.from(document.body.querySelectorAll(".el-dropdown-menu__item"));
    const findItem = (label: string) =>
      items().find(i => (i.textContent ?? "").trim() === label);

    /* 就地改名：Enter 保存（改名框在会话行里，不是左栏的搜索框） */
    const renameItem = findItem(S.ask.rename);
    expect(renameItem, "重命名菜单项不存在").toBeTruthy();
    (renameItem as HTMLElement).click();
    await flushPromises();
    const renameInput = w.find(".group.relative input");
    expect(renameInput.exists(), "就地改名输入框没出现").toBe(true);
    await renameInput.setValue("New name");
    await renameInput.trigger("keydown", { key: "Enter" });
    await flushPromises();
    expect(m.renameConversation).toHaveBeenCalledWith("kb1", "c1", "New name");

    /* 删除：确认框把守（不直接删） */
    await moreBtn!.trigger("click");
    await flushPromises();
    await new Promise(r => setTimeout(r, 30));
    const deleteItem = findItem(S.ask.deleteConversation);
    expect(deleteItem, "删除菜单项不存在").toBeTruthy();
    (deleteItem as HTMLElement).click();
    await flushPromises();
    const confirmBtn = Array.from(
      document.body.querySelectorAll("button")
    ).find(b => (b.textContent ?? "").trim() === S.ask.deleteBtn);
    expect(confirmBtn, "确认框删除键不存在").toBeTruthy();
    (confirmBtn as HTMLElement).click();
    await flushPromises();
    expect(m.removeConversation).toHaveBeenCalledWith("kb1", "c1");
  });

  it("刷新接回：idle → 「最后一个问题没有回答」+ 重答带 retry_message_id", async () => {
    m.conversationDetail.mockResolvedValue({
      messages: [msg({ id: "m1", role: "user", content: "第一问" })]
    });
    const w = await mountChat("/kb/kb1/chat/conv1");

    /* reattach GET /stream 回 idle（没在跑）→ onIdle 补读一次历史 → 横条出现 */
    await until(
      () => bodyText(w).includes(S.ask.noActiveAnswer),
      "无回答横条出现"
    );
    const retryTop = w
      .findAll("button")
      .find(b => (b.text() ?? "").trim() === S.ask.retryQuestion);
    expect(retryTop, "重答键不存在").toBeTruthy();
    await retryTop!.trigger("click");
    await flushPromises();

    /* 重答不再存一遍：带着它存下的 id 重新开流（#936） */
    await until(() => !!lastChatBody, "重答开流");
    expect(lastChatBody).toEqual({
      conversation_id: "conv1",
      message: "第一问",
      retry_message_id: "m1"
    });
    expect(streamOpenCount).toBe(1);
  });
});
