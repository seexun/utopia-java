<!-- Composer 卡（对照旧仓 Chat.tsx 的 composerCard，同一块两处复用）：
     新对话首屏居中出场，进入对话后停靠底部。
     输入 el-input textarea autosize（旧仓手写的高度撑开归 EP）；Enter 发送、
     Shift+Enter 换行、输入法组合中不发送（isComposing）。
     作用域 chip：提问点位可见「在问哪个库」，切库沿用现有语义（开新会话）。 -->
<script setup lang="ts">
import { ref, computed } from "vue";
import { S } from "@/i18n";
import type { Kb } from "@/api/knowledge/types";
import { useKbContext } from "@/composables/useKbContext";
import { UIconButton, URow, cn } from "@/components/knowledge/kit";
import LayersIcon from "~icons/lucide/layers";
import ChevronDownIcon from "~icons/lucide/chevron-down";
import CheckIcon from "~icons/lucide/check";
import ArrowUpIcon from "~icons/lucide/arrow-up";

const props = defineProps<{
  modelValue: string;
  streaming: boolean;
  stopping?: boolean;
  stopError?: string;
  /** 发送键不可用的口径（流中之外还有：无输入/装历史中/历史错误），页面算好 */
  sendDisabled: boolean;
  kb: Kb | null;
}>();

const emit = defineEmits<{
  "update:modelValue": [v: string];
  send: [];
  stop: [];
  /** 选了别的库：页面先丢掉当前视图的认领，再换库（旧仓 claimView(null); setKb） */
  rescope: [id: string];
}>();

const { kbs, switchKb } = useKbContext();
const scopeOpen = ref(false);

/* 新对话入口要能直接开打（旧仓 newChat 后 inputRef.focus() 同款）：
   el-input 暴露 focus()，这里再向页面转一手 */
const inputEl = ref<{ focus: () => void } | null>(null);
function focus() {
  inputEl.value?.focus();
}
defineExpose({ focus });

const draft = computed({
  get: () => props.modelValue,
  set: (v: string) => emit("update:modelValue", v)
});

function onKeyDown(e: KeyboardEvent) {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    emit("send");
  }
}

/* **换库是一次导航**（useKbContext.switchKb）。页面先收到 rescope 丢掉当前视图
   的认领（旧仓 claimView(null); setKb(k.id) 同一顺序），然后导航——深一层的
   会话 id 属于旧库，samePageInKb 会把它丢掉 */
function pickKb(k: Kb) {
  scopeOpen.value = false;
  if (props.kb && k.id !== props.kb.id) {
    emit("rescope", k.id);
    switchKb(k.id);
  }
}

function scopeButtonClass() {
  return cn(
    "max-w-52 flex min-w-0 shrink-0 items-center gap-2 border-0 bg-transparent px-2 py-1.5 -ml-2 text-body text-ink-2 rounded-ctl transition-colors hover:bg-fill hover:text-ink"
  );
}
</script>

<template>
  <div class="composer px-4 pt-3 pb-2">
    <el-input
      ref="inputEl"
      v-model="draft"
      type="textarea"
      :autosize="{ minRows: 1, maxRows: 8 }"
      :placeholder="S.ask.placeholder"
      resize="none"
      class="composer-input"
      @keydown="onKeyDown"
    />
    <div v-if="stopError" role="alert" class="text-sub text-danger">
      {{ stopError }}
    </div>
    <div class="flex-bc gap-3 pt-1">
      <div class="flex items-center gap-3 min-w-0">
        <el-popover
          v-model:visible="scopeOpen"
          trigger="click"
          placement="top-start"
          :width="224"
          :teleported="true"
          popper-class="shell-pop pop-up"
        >
          <template #reference>
            <button
              type="button"
              :title="S.ask.scopeLabel"
              :class="scopeButtonClass()"
            >
              <LayersIcon width="12" height="12" class="shrink-0 text-ink-2" />
              <span class="sle">{{ kb?.name ?? "…" }}</span>
              <ChevronDownIcon
                width="11"
                height="11"
                :class="
                  cn('turn shrink-0 text-ink-2', scopeOpen && 'rotate-180')
                "
              />
            </button>
          </template>
          <div
            class="border-b border-line px-4 py-3 text-body font-medium text-ink"
          >
            {{ S.ask.scopeLabel }}
          </div>
          <div class="max-h-60 overflow-y-auto overscroll-contain py-1">
            <URow
              v-for="k in kbs"
              :key="k.id"
              density="menu"
              :active="k.id === kb?.id"
              @click="pickKb(k)"
            >
              <span class="sle">{{ k.name }}</span>
              <template #trailing>
                <CheckIcon
                  v-if="k.id === kb?.id"
                  width="12"
                  height="12"
                  class="text-ink-2"
                />
              </template>
            </URow>
          </div>
        </el-popover>
        <span class="text-aux text-ink-2 sle">{{ S.ask.composerHint }}</span>
      </div>
      <!-- **只停正在看的这一场**——切页面、换会话、换库都不打断（liveAnswer.ts），
           别场照常写它们自己的条目（旧仓注释原话）。实心方块按 10 画（旧仓
           size-2.5 口径）：32 的按钮里塞半格白，比旁边那个细线箭头轻一截 -->
      <UIconButton
        v-if="streaming"
        :label="stopping ? S.ask.stopping : S.ask.stop"
        :disabled="stopping"
        class="shrink-0"
        @click="emit('stop')"
      >
        <svg
          width="10"
          height="10"
          viewBox="0 0 24 24"
          fill="currentColor"
          stroke-width="0"
        >
          <rect x="6" y="6" width="12" height="12" rx="2" />
        </svg>
      </UIconButton>
      <!-- 发送键：reskin-b3 升主行动形态（批次模板语言）——有输入变实底主色键
           （.chat-send-live，scoped 层覆盖 UIconButton 的中性幽灵档；旧仓
           primary/secondary 两档皮的最终到位），无输入保持幽灵档 -->
      <UIconButton
        v-else
        :label="S.ask.send"
        :disabled="sendDisabled"
        :class="cn('shrink-0', modelValue.trim() ? 'chat-send-live' : '')"
        @click="emit('send')"
      >
        <ArrowUpIcon width="15" height="15" style="stroke-width: 2.4" />
      </UIconButton>
    </div>
  </div>
</template>

<style scoped>
/* 重造层（reskin-b3）：只引令牌（--color-*），禁硬编码色值 */

/* el-input 的 textarea 去掉 EP 的默认皮：composer 卡自己就是框 */
.composer-input :deep(.el-textarea__inner) {
  box-shadow: none;
  background: transparent;
  padding: 0 2px;
  color: var(--color-ink);
  font-size: var(--text-body);
  line-height: 22px;
}
.composer-input :deep(.el-textarea__inner:focus) {
  box-shadow: none;
}
.composer-input :deep(.el-textarea__inner::placeholder) {
  color: var(--color-ink-3);
}

/* 发送键主行动形态（有输入时实底主色；scoped 无层规则压过 UIconButton 的
   @layer utilities 幽灵档）。不可用时退 soft 底淡色（EP 主键 disabled 同语言） */
.chat-send-live {
  background: var(--color-primary);
  color: var(--color-on-accent);
}
.chat-send-live:hover {
  background: var(--color-primary-hover);
  color: var(--color-on-accent);
}
.chat-send-live:disabled {
  background: var(--color-primary-soft);
  color: var(--color-ink-3);
}
</style>
