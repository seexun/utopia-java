/* 来源设置弹窗（遗留 3 收口）：PATCH 请求体装配形状钉 + danger zone 删除闸
   （对照旧仓 Library.tsx 的 SourceEditModal）。编辑的契约面 = 每种类型送哪些
   字段：url/rss/custom/github_issues 各自成块、auth_header 留空不送（凭据
   只进不出）、日程随 syncing 类型直送既有值；删除要走打字级确认。 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { VueQueryPlugin } from "@tanstack/vue-query";
import { queryClient } from "@/store/query";
import ElementPlus from "element-plus";
import { SourceEditModal } from "@/views/utopia/library/source-edit-modal";
import { updateSource, deleteSource } from "@/api/knowledge/sources";
import { S } from "@/i18n";
import type { SourceView } from "@/api/knowledge/types";

const { updateMock, deleteMock } = vi.hoisted(() => ({
  updateMock: vi.fn(),
  deleteMock: vi.fn()
}));
vi.mock("@/api/knowledge/sources", () => ({
  updateSource: updateMock,
  deleteSource: deleteMock,
  sources: vi.fn(),
  sourceToken: vi.fn(),
  rotateSourceToken: vi.fn()
}));
vi.mock("@/api/knowledge/documents", () => ({ documents: vi.fn() }));

function source(over: Partial<SourceView> = {}): SourceView {
  return {
    id: "s1",
    kind: "url",
    name: "抓取",
    config: { urls: ["https://a.example.com"] },
    icon: null,
    sync_interval_minutes: 60,
    sync_cron: null,
    last_sync_at: null,
    last_sync_status: "never",
    last_sync_error: null,
    last_sync_added: 0,
    doc_count: 0,
    missing_count: 0,
    rss_full_content: null,
    ...over
  } as SourceView;
}

let mounted: ReturnType<typeof mount> | null = null;

function mountModal(src: SourceView) {
  const el = document.createElement("div");
  document.body.appendChild(el);
  mounted = mount(SourceEditModal, {
    props: { kbId: "k1", source: src, onDone: vi.fn(), onDelete: vi.fn() },
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
  updateMock.mockReset();
  updateMock.mockResolvedValue({ source: {} });
  deleteMock.mockReset();
  deleteMock.mockResolvedValue({ ok: true });
  document.body.innerHTML = "";
});

/** 第 n 个 EP 输入（对话框内按出现序）→ 直接 emit 模型更新 */
async function setInput(wrapper: ReturnType<typeof mount>, index: number, value: string) {
  const ins = wrapper.findAllComponents({ name: "ElInput" });
  await ins[index].vm.$emit("update:modelValue", value);
  await flushPromises();
}

async function submit(wrapper: ReturnType<typeof mount>) {
  const btns = document.body.querySelectorAll(".dialog-foot button");
  const saveBtn = Array.from(btns).find(b =>
    b.textContent?.includes(S.library.saveChanges)
  );
  expect(saveBtn).toBeTruthy();
  (saveBtn as HTMLButtonElement).click();
  await flushPromises();
  await flushPromises();
}

describe("SourceEditModal：PATCH 请求体按类型装配", () => {
  it("url：改名 + 改 urls；syncing 类型直送既有日程", async () => {
    const w = mountModal(source());
    await flushPromises();
    await setInput(w, 0, "抓取改");
    // 第 1 个输入是 textarea（urls）
    const ta = w
      .findAllComponents({ name: "ElInput" })
      .find(c => c.props("type") === "textarea");
    await ta?.vm.$emit(
      "update:modelValue",
      "https://a.example.com\nhttps://b.example.com"
    );
    await flushPromises();
    await submit(w);
    expect(updateMock).toHaveBeenCalledWith("k1", "s1", {
      name: "抓取改",
      config: { urls: ["https://a.example.com", "https://b.example.com"] },
      sync_interval_minutes: 60,
      sync_cron: null
    });
  });

  it("custom：图标点选随 payload 直送；auth_header 留空不送（凭据只进不出）", async () => {
    const w = mountModal(
      source({ kind: "custom", config: { endpoint: "https://old.example.com/feed" } })
    );
    await flushPromises();
    await setInput(w, 0, "我的接入口");
    // 图标格（UDialog Teleport 到 body——DOM 查询走 document）：点 globe
    const globe = Array.from(
      document.body.querySelectorAll<HTMLButtonElement>("button[title='globe']")
    )[0];
    expect(globe).toBeTruthy();
    globe.click();
    await flushPromises();
    await setInput(w, 1, "https://new.example.com/feed");
    // authHeader 输入留空
    await submit(w);
    const body = updateMock.mock.calls[0]?.[2] as Record<string, unknown>;
    expect(body.name).toBe("我的接入口");
    expect(body.icon).toBe("globe");
    expect(body.config).toEqual({ endpoint: "https://new.example.com/feed" });
    expect(body.config).not.toHaveProperty("auth_header");
  });

  it("github_issues：includePrs 开了才为 true（默认跟既有值走）", async () => {
    const w = mountModal(
      source({ kind: "github_issues", config: { repo: "org/old" } })
    );
    await flushPromises();
    await setInput(w, 1, "org/new");
    await submit(w);
    expect(updateMock).toHaveBeenCalledWith("k1", "s1", {
      name: "抓取",
      config: { repo: "org/new", include_pull_requests: false },
      sync_interval_minutes: 60,
      sync_cron: null
    });
  });

  it("rss：content_mode 换 full_new_items 时 config 成对直送", async () => {
    const w = mountModal(
      source({
        kind: "rss",
        config: { feed_url: "https://f.example.com/rss", content_mode: "feed" }
      })
    );
    await flushPromises();
    const sel = w.findComponent({ name: "ElSelect" });
    await sel.vm.$emit("update:modelValue", "full_new_items");
    await flushPromises();
    await submit(w);
    expect(updateMock).toHaveBeenCalledWith(
      "k1",
      "s1",
      expect.objectContaining({
        config: { feed_url: "https://f.example.com/rss", content_mode: "full_new_items" }
      })
    );
  });

  it("摄取配置改动时出现「旧文档去留」说明（ingestChanged）", async () => {
    const w = mountModal(source());
    await flushPromises();
    expect(document.body.textContent).not.toContain(S.library.editKeepNote);
    const ta = w
      .findAllComponents({ name: "ElInput" })
      .find(c => c.props("type") === "textarea");
    await ta?.vm.$emit("update:modelValue", "https://other.example.com");
    await flushPromises();
    expect(document.body.textContent).toContain(S.library.editKeepNote);
  });

  it("folder：无同步语义——无日程字段、payload 不带日程", async () => {
    const w = mountModal(
      source({ kind: "folder", config: {}, sync_interval_minutes: null, sync_cron: null })
    );
    await flushPromises();
    await submit(w);
    const body = updateMock.mock.calls[0]?.[2] as Record<string, unknown>;
    expect(body).toEqual({ name: "抓取" });
    expect(document.body.textContent).not.toContain(S.library.interval);
  });

  it("名字清空时保存钮禁用（valid 闸）", async () => {
    const w = mountModal(source());
    await flushPromises();
    // 编辑窗从既有名字起步——先清空再验证闸
    await setInput(w, 0, "");
    const btns = Array.from(document.body.querySelectorAll(".dialog-foot button"));
    const saveBtn = btns.find(b =>
      b.textContent?.includes(S.library.saveChanges)
    ) as HTMLButtonElement;
    expect(saveBtn.disabled).toBe(true);
  });
});

describe("SourceEditModal：danger zone 删除闸", () => {
  it("删除要走打字级确认（输错不解锁、逐字解锁后 onDelete）", async () => {
    const w = mountModal(source());
    await flushPromises();
    // 底部 danger zone 的删除按钮（不在 dialog-foot 里）
    const zoneBtn = Array.from(document.body.querySelectorAll("button")).find(b =>
      b.textContent?.includes(S.library.deleteSource)
    );
    expect(zoneBtn).toBeTruthy();
    (zoneBtn as HTMLButtonElement).click();
    await flushPromises();
    // 确认框（type=danger 的那只钮）出现，确认钮初始锁着
    const confirmBtn = () =>
      Array.from(document.body.querySelectorAll("button")).find(
        b =>
          b.textContent?.includes(S.library.deleteSource) &&
          b.className.includes("el-button--danger")
      ) as HTMLButtonElement | undefined;
    expect(confirmBtn()).toBeTruthy();
    expect(confirmBtn()!.disabled).toBe(true);
    // 解锁输入框 = 确认框里最后一个 ElInput（DangerConfirm 渲染在最末）
    const unlock = () =>
      w.findAllComponents({ name: "ElInput" }).at(-1)!;
    await unlock().vm.$emit("update:modelValue", "别的名字");
    await flushPromises();
    expect(confirmBtn()!.disabled).toBe(true);
    // 逐字输入来源名 → 解锁 → onDelete 上报（mutation 归父级）
    await unlock().vm.$emit("update:modelValue", "抓取");
    await flushPromises();
    expect(confirmBtn()!.disabled).toBe(false);
    confirmBtn()!.click();
    await flushPromises();
    expect((w.props() as { onDelete: () => void }).onDelete).toHaveBeenCalled();
    expect(deleteMock).not.toHaveBeenCalled();
  });
});
