/* 管理员模型页签预设 Pill（遗留 2 收口）：一键填满对话/嵌入两卡的四个字段
   （对照旧仓 Settings.tsx 的 PRESETS 逐值同源）。钉三件事：五个预设全渲染、
   点击后四字段到位、旧测试结论清空（结果随配置过期）。 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { VueQueryPlugin } from "@tanstack/vue-query";
import { queryClient } from "@/store/query";
import { store as globalPinia } from "@/store";
import ElementPlus from "element-plus";
import ModelsTab from "@/views/utopia/admin/models-tab.vue";
import { S } from "@/i18n";

const m = vi.hoisted(() => ({
  settings: vi.fn(),
  saveSettings: vi.fn(),
  saveOcrSettings: vi.fn(),
  saveTranscribeSettings: vi.fn(),
  testSettings: vi.fn()
}));
vi.mock("@/api/knowledge/workspaces", () => ({
  settings: m.settings,
  saveSettings: m.saveSettings,
  saveOcrSettings: m.saveOcrSettings,
  saveTranscribeSettings: m.saveTranscribeSettings,
  testSettings: m.testSettings
}));

let mounted: ReturnType<typeof mount> | null = null;

async function mountTab() {
  const el = document.createElement("div");
  document.body.appendChild(el);
  mounted = mount(ModelsTab, {
    attachTo: el,
    global: {
      plugins: [ElementPlus, globalPinia, [VueQueryPlugin, { queryClient }]]
    }
  });
  await flushPromises();
  return mounted;
}

beforeEach(() => {
  queryClient.clear();
  localStorage.clear();
  // kb store 的 workspaceId 从 localStorage 起步——先种再挂
  localStorage.setItem("utopia.ws", "ws-1");
  m.settings.mockReset();
  m.settings.mockResolvedValue({
    chat_base_url: "https://old.example.com/v1",
    chat_model: "old-model",
    chat_reasoning_effort: "",
    embed_base_url: "https://old.example.com/v1",
    embed_model: "old-embed",
    has_chat_key: false,
    has_embed_key: false
  });
  m.saveSettings.mockReset();
  m.saveOcrSettings.mockReset();
  m.saveTranscribeSettings.mockReset();
  m.testSettings.mockReset();
  document.body.innerHTML = "";
});

function pillByName(wrapper: ReturnType<typeof mount>, name: string) {
  const pill = wrapper.findAll("button").find(b => b.text() === name);
  expect(pill, `preset pill ${name} 应渲染`).toBeTruthy();
  return pill!;
}

function inputValues(wrapper: ReturnType<typeof mount>): string[] {
  return wrapper.findAllComponents({ name: "ElInput" }).map(c =>
    String(c.props("modelValue") ?? "")
  );
}

describe("admin models 页签：预设 Pill", () => {
  it("五个预设（DeepSeek/SiliconFlow/Qwen/Ollama/OpenAI）全渲染", async () => {
    const w = await mountTab();
    for (const name of ["DeepSeek", "SiliconFlow", "Qwen (DashScope)", "Ollama", "OpenAI"])
      pillByName(w, name);
  });

  it("SiliconFlow 一键填满两卡四字段（对话与嵌入）", async () => {
    const w = await mountTab();
    await flushPromises();
    await pillByName(w, "SiliconFlow").trigger("click");
    await flushPromises();
    const vals = inputValues(w);
    expect(vals).toContain("https://api.siliconflow.cn/v1");
    expect(vals).toContain("deepseek-ai/DeepSeek-V3");
    expect(vals).toContain("BAAI/bge-m3");
    // 旧的 base_url/model 被换掉
    expect(vals).not.toContain("https://old.example.com/v1");
    expect(vals).not.toContain("old-model");
  });

  it("DeepSeek 只填对话卡（该预设没有嵌入档：嵌入两格置空）", async () => {
    const w = await mountTab();
    await flushPromises();
    await pillByName(w, "DeepSeek").trigger("click");
    await flushPromises();
    const vals = inputValues(w);
    expect(vals).toContain("https://api.deepseek.com/v1");
    expect(vals).toContain("deepseek-chat");
    expect(vals).toContain("");
    // 嵌入卡被清成空串（旧仓 setForm 同款：空值也照填）
    expect(vals).not.toContain("old-embed");
  });

  it("Ollama 本机地址直填（私有部署档）", async () => {
    const w = await mountTab();
    await flushPromises();
    await pillByName(w, "Ollama").trigger("click");
    await flushPromises();
    const vals = inputValues(w);
    expect(vals).toContain("http://localhost:11434/v1");
    expect(vals).toContain("qwen2.5:7b");
    expect(vals).toContain("bge-m3");
  });
});

describe("admin models 页签：预设与保存/测试的相容", () => {
  it("填预设不落盘（saveSettings 不被调），两卡的 Test 随 dirty 关掉（旧结论过期）", async () => {
    const w = await mountTab();
    await flushPromises();
    // 填预设前 Test 可点（settings 已载入、不 dirty）
    const testBtns = () =>
      w.findAll("button").filter(b => b.text().includes(S.settings.test));
    expect(testBtns().length).toBeGreaterThanOrEqual(2);
    expect(testBtns()[0].attributes("disabled")).toBeUndefined();
    await pillByName(w, "OpenAI").trigger("click");
    await flushPromises();
    expect(m.saveSettings).not.toHaveBeenCalled();
    // 预设只是批量填格：对话/嵌入两卡标脏 → Test 按钮禁用（点存盘前不能再测）
    expect(testBtns()[0].attributes("disabled")).toBeDefined();
    expect(testBtns()[1].attributes("disabled")).toBeDefined();
  });
});
