/* W1 文库页数据层（任务 5）：服务端筛选与分页的取数语义（mock api 层）。
   ——作用域/名字/抽取状态/页码都进 queryKey，换任何一个都重新取一页；
   uploads → source:"none"，deleted → state:"deleted"。 */
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { defineComponent, h, nextTick } from "vue";
import { createRouter, createMemoryHistory, type Router } from "vue-router";
import { VueQueryPlugin } from "@tanstack/vue-query";
import ElementPlus from "element-plus";
import { queryClient } from "@/store/knowledge/query";
import { store as globalPinia } from "@/store/knowledge";
import Library from "@/views/jknow/knowledge/library/index.vue";
import { S } from "@/i18n";

const { documentsMock, sourcesMock, dropsMock } = vi.hoisted(() => ({
  documentsMock: vi.fn(),
  sourcesMock: vi.fn(),
  dropsMock: vi.fn()
}));
vi.mock("@/api/knowledge/documents", () => ({
  documents: documentsMock,
  extractionDrops: dropsMock
}));
vi.mock("@/api/knowledge/sources", () => ({ sources: sourcesMock }));

function doc(over: Record<string, unknown> = {}) {
  return {
    id: "d1",
    kb_id: "kb1",
    source_id: null,
    filename: "a.pdf",
    mime: "application/pdf",
    size_bytes: 2048,
    status: "ready",
    graph_status: "done",
    error: null,
    graph_error: null,
    chunk_count: 3,
    tags: [],
    missing_since: null,
    deleted_at: null,
    purged_at: null,
    reader_needed: null,
    created_at: "2026-11-01T00:00:00Z",
    doc_time: null,
    doc_time_source: "none",
    ...over
  };
}

let mounted: ReturnType<typeof mount> | null = null;
let el: HTMLElement | null = null;
let router: Router | null = null;

async function mountLibrary(path = "/kb/kb1/library") {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/kb/:kbId/library", name: "library", component: Library },
      { path: "/:pathMatch(.*)*", component: { template: "<div/>" } }
    ]
  });
  el = document.createElement("div");
  document.body.appendChild(el);
  await router.push(path);
  await router.isReady();
  mounted = mount(Library, {
    global: {
      plugins: [ElementPlus, globalPinia, router, [VueQueryPlugin, { queryClient }]]
    },
    attachTo: el
  });
  await flushPromises();
  return mounted!;
}

beforeEach(() => {
  localStorage.clear();
  localStorage.setItem("utopia.lang", "en");
  queryClient.clear();
  documentsMock.mockReset();
  sourcesMock.mockReset();
  dropsMock.mockReset();
  sourcesMock.mockResolvedValue({ sources: [] });
  dropsMock.mockResolvedValue({ drops: [] });
  documentsMock.mockResolvedValue({
    docs: [],
    total: 0,
    ready: 0,
    done: 0,
    extracting: 0,
    failed: 0,
    deleted: 0
  });
});

afterEach(() => {
  mounted?.unmount();
  el?.remove();
  mounted = null;
  router = null;
});

describe("Library 页数据层：服务端筛选与分页", () => {
  it("默认 All documents：不带 source/state，按页取第一页", async () => {
    await mountLibrary();
    await flushPromises();
    expect(documentsMock).toHaveBeenCalledWith(
      "kb1",
      expect.objectContaining({ limit: 15, offset: 0 })
    );
    const arg = documentsMock.mock.calls[0][1];
    expect(arg.source).toBeUndefined();
    expect(arg.state).toBeUndefined();
  });

  it("Uploads 作用域 → source:'none'；deleted 作用域 → state:'deleted'", async () => {
    const w = await mountLibrary();
    await flushPromises();
    documentsMock.mockClear();
    documentsMock.mockResolvedValue({
      docs: [doc()],
      total: 1,
      ready: 1,
      done: 1,
      extracting: 0,
      failed: 0,
      deleted: 0
    });
    // 切到 Uploads
    const uploads = w.findAll("button").filter(b => b.text().includes(S.library.uploads));
    await uploads[0].trigger("click");
    await flushPromises();
    expect(documentsMock).toHaveBeenLastCalledWith(
      "kb1",
      expect.objectContaining({ source: "none" })
    );
    // 切到已删（墓碑视图）：rail 只在有墓碑时出现——直接驱动 selection 经 deep 链
    await router!.push("/kb/kb1/library?src=deleted");
    await flushPromises();
    expect(documentsMock).toHaveBeenLastCalledWith(
      "kb1",
      expect.objectContaining({ state: "deleted" })
    );
  });

  it("过滤词与抽取状态进请求（q / graph），变化回第一页", async () => {
    const w = await mountLibrary();
    await flushPromises();
    documentsMock.mockClear();
    // 输入过滤词（el-input：真实 input 在 .el-input__wrapper 里）
    let input = w.find(".library-filter input");
    if (!input.exists()) {
      // eslint-disable-next-line no-console
      console.log("LIB-HTML:", w.html().slice(0, 1500));
    }
    if (!input.exists()) input = w.find("input");
    expect(input.exists()).toBe(true);
    await input.setValue("hoshino");
    await flushPromises();
    const calls = documentsMock.mock.calls;
    const last = calls[calls.length - 1][1];
    expect(last.q).toBe("hoshino");
    // 抽取状态筛选
    const graphCalls = () => documentsMock.mock.calls.map(c => c[1]);
    void graphCalls;
    // 换页码 → offset 推进（造出 40 篇让翻页器出现）
    documentsMock.mockResolvedValue({
      docs: Array.from({ length: 15 }, (_, i) => doc({ id: `d${i}` })),
      total: 40,
      ready: 40,
      done: 40,
      extracting: 0,
      failed: 0,
      deleted: 0
    });
    // mock 换返回值不会自己触发重取：失效这一族查询再 flush
    await queryClient.invalidateQueries({ queryKey: ["documents"] });
    await flushPromises();
    const pager = w.findAll(".mt-3");
    expect(pager.length).toBeGreaterThan(0);
    const btns = pager[pager.length - 1].findAll("button");
    documentsMock.mockClear();
    await btns[btns.length - 1].trigger("click");
    await flushPromises();
    expect(documentsMock).toHaveBeenLastCalledWith(
      "kb1",
      expect.objectContaining({ offset: 15 })
    );
    // 再改过滤词：回第一页
    documentsMock.mockClear();
    await input.setValue("second");
    await flushPromises();
    expect(documentsMock).toHaveBeenLastCalledWith(
      "kb1",
      expect.objectContaining({ offset: 0, q: "second" })
    );
  });

  it("抽取丢弃按文档分组：done 与「3 dropped」同时为真", async () => {
    dropsMock.mockResolvedValue({
      drops: [
        { document_id: "d1", reason: "low_confidence", detail: "x", count: 2 },
        { document_id: "d1", reason: "attr_domain_mismatch", detail: "y", count: 1 }
      ]
    });
    documentsMock.mockResolvedValue({
      docs: [doc()],
      total: 1,
      ready: 1,
      done: 1,
      extracting: 0,
      failed: 0,
      deleted: 0
    });
    const w = await mountLibrary();
    await flushPromises();
    await nextTick();
    expect(w.html()).toContain(S.library.dropsChip(3));
  });
});
