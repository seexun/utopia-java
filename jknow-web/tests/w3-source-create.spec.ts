/* 新建来源弹窗（W3 补迁）：请求体装配形状钉（对照旧仓 SourceModal 的 config
   组装面）。 kinds×配置字段是创建校验的契约面（#247 那次两边对账的教训）——
   选得到就必须建得出来。 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { VueQueryPlugin } from "@tanstack/vue-query";
import { queryClient } from "@/store/knowledge/query";
import ElementPlus from "element-plus";
import { SourceModal } from "@/views/jknow/knowledge/library/source-modal";
import { createSource } from "@/api/knowledge/sources";
import { S } from "@/i18n";

const { createMock } = vi.hoisted(() => ({ createMock: vi.fn() }));
vi.mock("@/api/knowledge/sources", () => ({
  createSource: createMock,
  sources: vi.fn(),
  sourceToken: vi.fn(),
  rotateSourceToken: vi.fn()
}));
vi.mock("@/api/knowledge/documents", () => ({ documents: vi.fn() }));

let mounted: ReturnType<typeof mount> | null = null;

function mountModal() {
  const el = document.createElement("div");
  document.body.appendChild(el);
  mounted = mount(SourceModal, {
    props: { kbId: "k1", onDone: vi.fn() },
    attachTo: el,
    global: {
      // UDialog Teleport 到 body——挂在当前文档即可
      plugins: [ElementPlus, [VueQueryPlugin, { queryClient }]]
    }
  });
  return mounted;
}

beforeEach(() => {
  queryClient.clear();
  createMock.mockReset();
  createMock.mockResolvedValue({ source: { id: "s1" } });
  document.body.innerHTML = "";
});

async function pickKind(wrapper: ReturnType<typeof mount>, kindValue: string) {
  // el-select 触发器展开后再点选项——headless 下直接调组件事件更快：
  // 通过模型更新模拟（EP select 的 update:modelValue）
  const select = wrapper.findComponent({ name: "ElSelect" });
  await select.vm.$emit("update:modelValue", kindValue);
  await flushPromises();
}

/** 第 n 个 EP 输入（跳过搜索框之类；对话框内按出现序）→ 直接 emit 模型更新 */
async function setInput(wrapper: ReturnType<typeof mount>, index: number, value: string) {
  const ins = wrapper.findAllComponents({ name: "ElInput" });
  await ins[index].vm.$emit("update:modelValue", value);
  await flushPromises();
}

async function setTextarea(wrapper: ReturnType<typeof mount>, value: string) {
  const ta = wrapper
    .findAllComponents({ name: "ElInput" })
    .find(c => c.props("type") === "textarea");
  await ta?.vm.$emit("update:modelValue", value);
  await flushPromises();
}

async function submit(wrapper: ReturnType<typeof mount>) {
  const btns = document.body.querySelectorAll(".dialog-foot button");
  const createBtn = Array.from(btns).find(b => b.textContent?.includes(S.library.createSource));
  expect(createBtn).toBeTruthy();
  (createBtn as HTMLButtonElement).click();
  await flushPromises();
  await flushPromises();
}

describe("SourceModal：创建请求体按类型装配", () => {
  it("folder：无同步语义，日程强制留空", async () => {
    const w = mountModal();
    await flushPromises();
    await setInput(w, 0, "文献夹");
    await submit(w);
    expect(createMock).toHaveBeenCalledWith("k1", {
      kind: "folder",
      name: "文献夹",
      config: {},
      icon: null,
      sync_interval_minutes: null,
      sync_cron: null
    });
  });

  it("url：urls 按行拆、去空行", async () => {
    const w = mountModal();
    await pickKind(w, "url");
    await setInput(w, 0, "抓取");
    await setTextarea(w, "https://a.example.com\n\nhttps://b.example.com");
    await submit(w);
    expect(createMock).toHaveBeenCalledWith("k1", expect.objectContaining({
      kind: "url",
      config: { urls: ["https://a.example.com", "https://b.example.com"] }
    }));
  });

  it("rss：feed_url + content_mode，且带可视化日程缺省（manual=null）", async () => {
    const w = mountModal();
    await pickKind(w, "rss");
    await setInput(w, 0, "订阅");
    await setInput(w, 1, "https://feed.example.com/rss.xml");
    await submit(w);
    expect(createMock).toHaveBeenCalledWith("k1", expect.objectContaining({
      kind: "rss",
      config: { feed_url: "https://feed.example.com/rss.xml", content_mode: "feed" },
      sync_interval_minutes: null,
      sync_cron: null
    }));
  });

  it("github_issues：includePrs 开了才送（默认不收 PR）", async () => {
    const w = mountModal();
    await pickKind(w, "github_issues");
    await setInput(w, 0, "收工单");
    await setInput(w, 1, "org/repo");
    await submit(w);
    const body = createMock.mock.calls[0]?.[1] as Record<string, unknown>;
    expect(body.config).toEqual({ repo: "org/repo" });
    expect(body.config).not.toHaveProperty("include_pull_requests");
  });

  it("名字为空时创建钮禁用（valid 闸）", async () => {
    mountModal();
    await flushPromises();
    const btns = Array.from(document.body.querySelectorAll(".dialog-foot button"));
    const createBtn = btns.find(b => b.textContent?.includes(S.library.createSource)) as HTMLButtonElement;
    expect(createBtn.disabled).toBe(true);
  });
});
