<!-- 模型（对照旧仓 Settings.tsx 的 models 页签核心）：工作区模型设置四张卡
     （对话/嵌入/OCR/转写），一张卡一个保存——**PUT 是整体替换**，底子取服务端
     那一份、再把这张卡的字段盖上去；密钥留空 = 保留旧密钥（COALESCE 那两列）。
     Test 一次连的是四套模型（一个接口），结果各自回到各自那张卡。
     预设 Pill（遗留 2 收口）：DeepSeek/SiliconFlow/… 一键填满两张卡的四个字段
     （对照旧仓 Settings.tsx 的 PRESETS，数据逐值同源）。 -->
<script setup lang="ts">
import { ref, computed, watch } from "vue";
import { useQuery, useMutation, useQueryClient } from "@tanstack/vue-query";
import {
  settings as settingsApi,
  saveSettings,
  saveOcrSettings,
  saveTranscribeSettings,
  testSettings
} from "@/api/knowledge/workspaces";
import type { LlmSettingsView } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { toast } from "@/domain/toast";
import { UPill } from "@/components/knowledge/kit";
import { useKbContextHook } from "@/store/knowledge/modules/kb";

/** 预设（对照旧仓 Settings.tsx 的 PRESETS 逐值同源）：一键填满对话/嵌入两卡
 *  的 base_url 与 model 名。embed 为空 = 该服务商没有（或未定）嵌入档，
 *  只填对话卡的字段。 */
const PRESETS: Record<
  string,
  { chat: string; embed: string; chatModel: string; embedModel: string }
> = {
  DeepSeek: {
    chat: "https://api.deepseek.com/v1",
    chatModel: "deepseek-chat",
    embed: "",
    embedModel: ""
  },
  SiliconFlow: {
    chat: "https://api.siliconflow.cn/v1",
    chatModel: "deepseek-ai/DeepSeek-V3",
    embed: "https://api.siliconflow.cn/v1",
    embedModel: "BAAI/bge-m3"
  },
  "Qwen (DashScope)": {
    chat: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    chatModel: "qwen-plus",
    embed: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    embedModel: "text-embedding-v3"
  },
  Ollama: {
    chat: "http://localhost:11434/v1",
    chatModel: "qwen2.5:7b",
    embed: "http://localhost:11434/v1",
    embedModel: "bge-m3"
  },
  OpenAI: {
    chat: "https://api.openai.com/v1",
    chatModel: "gpt-4o-mini",
    embed: "https://api.openai.com/v1",
    embedModel: "text-embedding-3-small"
  }
};

const kbStore = useKbContextHook();
const workspaceId = computed(() => kbStore.workspaceId);
const queryClient = useQueryClient();

const settings = useQuery({
  queryKey: computed(() => ["settings", workspaceId.value]),
  queryFn: () => settingsApi(workspaceId.value!),
  enabled: computed(() => !!workspaceId.value)
});

type Form = {
  chat_base_url: string;
  chat_api_key: string;
  chat_model: string;
  chat_reasoning_effort: string;
  embed_base_url: string;
  embed_api_key: string;
  embed_model: string;
};
const form = ref<Form>({
  chat_base_url: "",
  chat_api_key: "",
  chat_model: "",
  chat_reasoning_effort: "",
  embed_base_url: "",
  embed_api_key: "",
  embed_model: ""
});
const ocrForm = ref({ provider: "mineru", base_url: "", api_key: "", backend: "", model: "" });
const trForm = ref({ base_url: "", api_key: "", model: "" });

watch(
  () => settings.data.value,
  d => {
    if (!d) return;
    form.value = {
      ...form.value,
      chat_base_url: d.chat_base_url ?? "",
      chat_model: d.chat_model ?? "",
      chat_reasoning_effort: d.chat_reasoning_effort ?? "",
      embed_base_url: d.embed_base_url ?? "",
      embed_model: d.embed_model ?? "",
    };
    ocrForm.value = {
      provider: d.ocr_provider ?? "mineru",
      base_url: d.ocr_base_url ?? "",
      api_key: "",
      backend: d.ocr_backend ?? "",
      model: d.ocr_model ?? ""
    };
    trForm.value = {
      base_url: d.transcribe_base_url ?? "",
      api_key: "",
      model: d.transcribe_model ?? ""
    };
  },
  { immediate: true }
);

/** 一张卡一个保存。PUT 整体替换：底子取服务端那份、再把这张卡的字段盖上——
    既不清空邻居，也不把邻居那张卡还没保存的编辑一起交上去。密钥不在底子里：
    留空 = 保留旧密钥 */
function withSaved(over: Partial<Form>): Record<string, unknown> {
  const d: LlmSettingsView = settings.data.value ?? {};
  return {
    chat_base_url: d.chat_base_url ?? "",
    chat_model: d.chat_model ?? "",
    chat_reasoning_effort: d.chat_reasoning_effort ?? "",
    embed_base_url: d.embed_base_url ?? "",
    embed_model: d.embed_model ?? "",
    ...over
  };
}

const dirty = ref({ chat: false, embed: false });
const savingChat = ref(false);
const savingEmbed = ref(false);
/** 预设一按填满两张卡的字段：它不是设置本身，所以在卡外面（旧仓注释原话）。
 *  预设只是批量填格，和手输一样让旧测试结论过期（testResult 清空），
 *  两张卡都标 dirty——填进来的东西还没存。 */
function applyPreset(p: { chat: string; embed: string; chatModel: string; embedModel: string }) {
  testResult.value = null;
  dirty.value = { chat: true, embed: true };
  form.value = {
    ...form.value,
    chat_base_url: p.chat,
    chat_model: p.chatModel,
    embed_base_url: p.embed,
    embed_model: p.embedModel
  };
}
async function saveChat() {
  savingChat.value = true;
  try {
    await saveSettings(workspaceId.value!, withSaved({
      chat_base_url: form.value.chat_base_url,
      chat_model: form.value.chat_model,
      chat_api_key: form.value.chat_api_key,
      chat_reasoning_effort: form.value.chat_reasoning_effort,
    }));
    dirty.value = { ...dirty.value, chat: false };
    toast.success(S.toast.saved);
    queryClient.invalidateQueries({ queryKey: ["settings", workspaceId.value] });
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
  savingChat.value = false;
}
async function saveEmbed() {
  savingEmbed.value = true;
  try {
    await saveSettings(workspaceId.value!, withSaved({
      embed_base_url: form.value.embed_base_url,
      embed_model: form.value.embed_model,
      embed_api_key: form.value.embed_api_key,
    }));
    dirty.value = { ...dirty.value, embed: false };
    toast.success(S.toast.saved);
    queryClient.invalidateQueries({ queryKey: ["settings", workspaceId.value] });
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
  savingEmbed.value = false;
}
/** 读扫描件的服务、转写模型各自一个保存：存它们不碰对话与嵌入那几列。
    `requeued`：因为缺它而等着的文件，这一存重新排进了处理队列几份 */
async function saveOcr() {
  try {
    const r = await saveOcrSettings(workspaceId.value!, { ...ocrForm.value });
    toast.success(S.settings.savedRequeued(r.requeued));
    queryClient.invalidateQueries({ queryKey: ["settings", workspaceId.value] });
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}
async function saveTr() {
  try {
    const r = await saveTranscribeSettings(workspaceId.value!, { ...trForm.value });
    toast.success(S.settings.savedRequeued(r.requeued));
    queryClient.invalidateQueries({ queryKey: ["settings", workspaceId.value] });
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
}

/* ---- 测试：一次连四套，结果各自回到各自那张卡 ---- */
const testing = ref<"chat" | "embed" | "ocr" | "transcribe" | null>(null);
const testResult = ref<{
  chat?: { ok: boolean; reply?: string; error?: string };
  embed?: { ok: boolean; dim?: number; error?: string };
  ocr?: { ok: boolean; version?: string | null; error?: string };
  transcribe?: { ok: boolean; error?: string };
} | null>(null);
async function startTest(card: "chat" | "embed" | "ocr" | "transcribe") {
  testing.value = card;
  try {
    testResult.value = await testSettings(workspaceId.value!);
  } catch (e) {
    toast.error(e instanceof Error ? e.message : String(e));
  }
  testing.value = null;
}
const cardNote = (r: { ok: boolean; message: string } | null) => r?.message ?? "";
function statusOf(card: "chat" | "embed" | "ocr" | "transcribe"): { ok: boolean; message: string } | null {
  const r = testResult.value;
  if (!r) return null;
  if (card === "chat" && r.chat)
    return r.chat.ok
      ? { ok: true, message: S.settings.ok(r.chat.reply ?? "") }
      : { ok: false, message: r.chat.error ?? S.settings.datasources.testFail };
  if (card === "embed" && r.embed)
    return r.embed.ok
      ? { ok: true, message: S.settings.okDim(r.embed.dim ?? 0) }
      : { ok: false, message: r.embed.error ?? S.settings.datasources.testFail };
  if (card === "ocr" && r.ocr)
    return r.ocr.ok
      ? { ok: true, message: S.settings.okVersion(r.ocr.version ?? "") }
      : { ok: false, message: r.ocr.error ?? S.settings.datasources.testFail };
  if (card === "transcribe" && r.transcribe)
    return r.transcribe.ok
      ? { ok: true, message: S.settings.okReachable }
      : { ok: false, message: r.transcribe.error ?? S.settings.datasources.testFail };
  return null;
}

const EFFORTS = ["", "none", "minimal", "low", "medium", "high"];
</script>

<template>
  <div class="space-y-4">
    <p class="text-body text-ink-2">{{ S.settings.modelsIntro }}</p>

    <!-- 预设 Pill（遗留 2 收口，对照旧仓 Settings.tsx models 页签同位）：一键填满
        两张卡的字段。不存盘——存的还是那两个保存钮 -->
    <div class="flex flex-wrap gap-2">
      <UPill v-for="(p, name) in PRESETS" :key="name" @click="applyPreset(p)">
        {{ name }}
      </UPill>
    </div>

    <!-- 对话模型卡 -->
    <div class="rounded-card border border-line bg-card">
      <div class="flex items-center justify-between gap-3 border-b border-line px-5 py-4">
        <div class="min-w-0">
          <div class="text-body font-semibold text-ink">{{ S.settings.chatModel }}</div>
          <div
            v-if="cardNote(statusOf('chat'))"
            class="mt-0.5 text-sub"
            :class="statusOf('chat')!.ok ? 'text-success' : 'text-danger'"
          >
            {{ cardNote(statusOf('chat')) }}
          </div>
        </div>
        <div class="flex shrink-0 items-center gap-2">
          <el-button
            size="small"
            :disabled="testing !== null || dirty.chat"
            @click="startTest('chat')"
          >
            {{ testing === "chat" ? S.settings.testing : S.settings.test }}
          </el-button>
          <el-button size="small" :disabled="savingChat" @click="saveChat">
            {{ savingChat ? S.settings.saving : S.settings.save }}
          </el-button>
        </div>
      </div>
      <div class="space-y-3 px-5 py-4">
        <div>
          <label class="mb-1 block text-sub font-medium text-ink-2">{{ S.settings.baseUrl }}</label>
          <el-input
            v-model="form.chat_base_url"
            placeholder="https://api.deepseek.com/v1"
            @input="dirty = { ...dirty, chat: true }"
          />
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="mb-1 block text-sub font-medium text-ink-2">{{ S.settings.model }}</label>
            <el-input
              v-model="form.chat_model"
              placeholder="deepseek-chat"
              @input="dirty = { ...dirty, chat: true }"
            />
          </div>
          <div>
            <label class="mb-1 block text-sub font-medium text-ink-2">
              {{ S.settings.reasoningEffort }}
            </label>
            <el-select
              v-model="form.chat_reasoning_effort"
              class="w-full"
              @change="dirty = { ...dirty, chat: true }"
            >
              <el-option
                v-for="e in EFFORTS"
                :key="e || '_default'"
                :value="e"
                :label="e === '' ? S.settings.reasoningDefault : e"
              />
            </el-select>
            <div class="mt-1 text-sub text-ink-2">{{ S.settings.reasoningHint }}</div>
          </div>
          <div>
            <label class="mb-1 block text-sub font-medium text-ink-2">
              {{ S.settings.apiKey }}
              <span v-if="settings.data.value?.has_chat_key" class="text-primary">
                {{ S.settings.keyConfigured }}
              </span>
            </label>
            <el-input v-model="form.chat_api_key" type="password" show-password placeholder="sk-…" />
          </div>
        </div>
      </div>
    </div>

    <!-- 嵌入模型卡 -->
    <div class="rounded-card border border-line bg-card">
      <div class="flex items-center justify-between gap-3 border-b border-line px-5 py-4">
        <div class="min-w-0">
          <div class="text-body font-semibold text-ink">{{ S.settings.embedModel }}</div>
          <div
            v-if="cardNote(statusOf('embed'))"
            class="mt-0.5 text-sub"
            :class="statusOf('embed')!.ok ? 'text-success' : 'text-danger'"
          >
            {{ cardNote(statusOf('embed')) }}
          </div>
        </div>
        <div class="flex shrink-0 items-center gap-2">
          <el-button
            size="small"
            :disabled="testing !== null || dirty.embed"
            @click="startTest('embed')"
          >
            {{ testing === "embed" ? S.settings.testing : S.settings.test }}
          </el-button>
          <el-button size="small" :disabled="savingEmbed" @click="saveEmbed">
            {{ savingEmbed ? S.settings.saving : S.settings.save }}
          </el-button>
        </div>
      </div>
      <div class="grid grid-cols-2 gap-3 px-5 py-4">
        <div>
          <label class="mb-1 block text-sub font-medium text-ink-2">{{ S.settings.baseUrl }}</label>
          <el-input
            v-model="form.embed_base_url"
            placeholder="https://api.siliconflow.cn/v1"
            @input="dirty = { ...dirty, embed: true }"
          />
        </div>
        <div>
          <label class="mb-1 block text-sub font-medium text-ink-2">{{ S.settings.model }}</label>
          <el-input
            v-model="form.embed_model"
            placeholder="BAAI/bge-m3"
            @input="dirty = { ...dirty, embed: true }"
          />
        </div>
        <div>
          <label class="mb-1 block text-sub font-medium text-ink-2">
            {{ S.settings.apiKey }}
            <span v-if="settings.data.value?.has_embed_key" class="text-primary">
              {{ S.settings.keyConfigured }}
            </span>
          </label>
          <el-input v-model="form.embed_api_key" type="password" show-password placeholder="sk-…" />
        </div>
      </div>
    </div>

    <!-- OCR 卡（读扫描件的服务） -->
    <div class="rounded-card border border-line bg-card">
      <div class="flex items-center justify-between gap-3 border-b border-line px-5 py-4">
        <div class="min-w-0">
          <div class="text-body font-semibold text-ink">{{ S.settings.ocrService }}</div>
          <div
            v-if="cardNote(statusOf('ocr'))"
            class="mt-0.5 text-sub"
            :class="statusOf('ocr')!.ok ? 'text-success' : 'text-danger'"
          >
            {{ cardNote(statusOf('ocr')) }}
          </div>
        </div>
        <div class="flex shrink-0 items-center gap-2">
          <el-button size="small" :disabled="testing !== null" @click="startTest('ocr')">
            {{ testing === "ocr" ? S.settings.testing : S.settings.test }}
          </el-button>
          <el-button size="small" @click="saveOcr">
            {{ S.settings.save }}
          </el-button>
        </div>
      </div>
      <div class="grid grid-cols-2 gap-3 px-5 py-4">
        <div>
          <label class="mb-1 block text-sub font-medium text-ink-2">{{ S.settings.readerProvider }}</label>
          <el-select v-model="ocrForm.provider" class="w-full">
            <el-option value="mineru" label="mineru" />
            <el-option value="ark" label="ark" />
          </el-select>
        </div>
        <div>
          <label class="mb-1 block text-sub font-medium text-ink-2">{{ S.settings.baseUrl }}</label>
          <el-input v-model="ocrForm.base_url" />
        </div>
        <div>
          <label class="mb-1 block text-sub font-medium text-ink-2">{{ S.settings.backend }}</label>
          <el-input v-model="ocrForm.backend" />
        </div>
        <div>
          <label class="mb-1 block text-sub font-medium text-ink-2">{{ S.settings.model }}</label>
          <el-input v-model="ocrForm.model" />
        </div>
        <div class="col-span-2">
          <label class="mb-1 block text-sub font-medium text-ink-2">
            {{ S.settings.apiKey }}
            <span v-if="settings.data.value?.has_ocr_key" class="text-primary">
              {{ S.settings.keyConfigured }}
            </span>
          </label>
          <el-input v-model="ocrForm.api_key" type="password" show-password placeholder="sk-…" />
        </div>
      </div>
    </div>

    <!-- 转写卡 -->
    <div class="rounded-card border border-line bg-card">
      <div class="flex items-center justify-between gap-3 border-b border-line px-5 py-4">
        <div class="min-w-0">
          <div class="text-body font-semibold text-ink">{{ S.settings.transcribeModel }}</div>
          <div
            v-if="cardNote(statusOf('transcribe'))"
            class="mt-0.5 text-sub"
            :class="statusOf('transcribe')!.ok ? 'text-success' : 'text-danger'"
          >
            {{ cardNote(statusOf('transcribe')) }}
          </div>
        </div>
        <div class="flex shrink-0 items-center gap-2">
          <el-button size="small" :disabled="testing !== null" @click="startTest('transcribe')">
            {{ testing === "transcribe" ? S.settings.testing : S.settings.test }}
          </el-button>
          <el-button size="small" @click="saveTr">
            {{ S.settings.save }}
          </el-button>
        </div>
      </div>
      <div class="grid grid-cols-2 gap-3 px-5 py-4">
        <div>
          <label class="mb-1 block text-sub font-medium text-ink-2">{{ S.settings.baseUrl }}</label>
          <el-input v-model="trForm.base_url" />
        </div>
        <div>
          <label class="mb-1 block text-sub font-medium text-ink-2">{{ S.settings.model }}</label>
          <el-input v-model="trForm.model" />
        </div>
        <div class="col-span-2">
          <label class="mb-1 block text-sub font-medium text-ink-2">
            {{ S.settings.apiKey }}
            <span v-if="settings.data.value?.has_transcribe_key" class="text-primary">
              {{ S.settings.keyConfigured }}
            </span>
          </label>
          <el-input v-model="trForm.api_key" type="password" show-password placeholder="sk-…" />
        </div>
      </div>
    </div>
  </div>
</template>
