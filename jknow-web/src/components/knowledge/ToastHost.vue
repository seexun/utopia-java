<!-- 全局消息渲染面（对照旧仓 ui/toast.tsx 的 ToastHost）：挂在应用根部一次。
     总线（domain/toast.ts）是逐字随迁的纯单例，这里只补 Vue 的渲染面——
     右下角堆叠，success/info 3.8s、error 6s 自动消失，可带一个动作（如「撤销」）。 -->
<script setup lang="ts">
import { ref, onScopeDispose } from "vue";
import { subscribe, type ToastItem } from "@/domain/toast";
import CircleCheckIcon from "~icons/lucide/circle-check";
import CircleAlertIcon from "~icons/lucide/circle-alert";
import InfoIcon from "~icons/lucide/info";
import XIcon from "~icons/lucide/x";

const items = ref<ToastItem[]>([]);
const unsubscribe = subscribe(list => (items.value = list));
onScopeDispose(unsubscribe);

function run(t: ToastItem) {
  t.action?.onClick();
  dismiss(t.id);
}
function dismiss(id: number) {
  items.value = items.value.filter(t => t.id !== id);
}
</script>

<template>
  <div
    v-if="items.length"
    class="pointer-events-none fixed bottom-5 right-5 z-[100] flex flex-col items-end gap-2"
  >
    <div
      v-for="t in items"
      :key="t.id"
      class="pop toast-in pointer-events-auto flex items-center gap-2.5 rounded-card py-2.5 pl-3.5 pr-2.5 text-body text-ink shadow-pop"
    >
      <CircleCheckIcon
        v-if="t.kind === 'success'"
        width="15"
        height="15"
        class="shrink-0 text-success"
      />
      <CircleAlertIcon
        v-else-if="t.kind === 'error'"
        width="15"
        height="15"
        class="shrink-0 text-danger"
      />
      <InfoIcon v-else width="15" height="15" class="shrink-0 text-ink-2" />
      <span class="max-w-xs">{{ t.text }}</span>
      <button
        v-if="t.action"
        type="button"
        class="link-inline shrink-0 text-sub"
        @click="run(t)"
      >
        {{ t.action.label }}
      </button>
      <button
        type="button"
        class="text-ink-2 hover:text-ink"
        @click="dismiss(t.id)"
      >
        <XIcon width="13" height="13" />
      </button>
    </div>
  </div>
</template>
