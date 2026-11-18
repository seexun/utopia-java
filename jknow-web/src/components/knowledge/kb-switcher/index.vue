<!-- 库切换器（对照旧仓 KbSwitcher.tsx）：侧栏左下角。
     弹层分三段：查找、库、新建。库上了十几个之后，切换库这件事是**先打字再挑**，
     不是滚一列名字；新建钉在最下面而不是混在列里——它不是一个库，是这张面板的出口。
     （旧仓注释原话。）切换动作 = useKbContext().switchKb（写 store + 失效 + 导航）。 -->
<script setup lang="ts">
import { ref, computed, watch, nextTick } from "vue";
import { useRouter } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import { workspaceRole as workspaceRoleApi } from "@/api/knowledge/workspaces";
import type { Kb } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { useUserStoreHook } from "@/store/knowledge/modules/user";
import { useKbContext } from "@/composables/useKbContext";
import LayersIcon from "~icons/lucide/layers";
import ChevronDownIcon from "~icons/lucide/chevron-down";
import CheckIcon from "~icons/lucide/check";
import PlusIcon from "~icons/lucide/plus";

const props = defineProps<{
  kb: Kb | null;
  kbs: Kb[];
}>();

const emit = defineEmits<{ change: [id: string] }>();

const { workspace } = useKbContext();
const router = useRouter();
const visible = ref(false);
const q = ref("");

const name = computed(() => props.kb?.name ?? "…");

// 建库要系统管理员或工作区 Admin+（见 api/kbs.rs create）：没这个权限的人
// 看不到入口，省得点进去吃一个 403（旧仓注释原话）
const userStore = useUserStoreHook();
const wsRole = useQuery({
  queryKey: computed(() => ["workspaceRole", props.kb?.workspace_id]),
  queryFn: () => workspaceRoleApi(props.kb!.workspace_id),
  enabled: computed(() => !!props.kb?.workspace_id)
});

const canCreate = computed(
  () =>
    userStore.isAdmin ||
    wsRole.data.value?.role === "admin" ||
    wsRole.data.value?.role === "owner"
);

// 关掉就把查找词丢掉：下次打开是从头挑，不是接着上次的筛选结果（旧仓注释原话）。
// 打开就聚焦（W1 M-8 收口：autofocus 不是 EP 的 API，得拿 ref 调 focus）——
// 一开口就能打字；Esc 与外点由 Popover 统一管（旧仓注释原话）
const findInput = ref<{ focus: () => void } | null>(null);
watch(visible, open => {
  if (open) {
    // 弹层内容首开才挂载（teleport），下一帧才拿得到输入框
    nextTick(() => findInput.value?.focus());
  } else {
    q.value = "";
  }
});

const needle = computed(() => q.value.trim().toLowerCase());
const shown = computed(() =>
  needle.value
    ? props.kbs.filter(k => k.name.toLowerCase().includes(needle.value))
    : props.kbs
);

function onPick(id: string) {
  visible.value = false;
  if (props.kb && id === props.kb.id) return;
  emit("change", id);
}

function goCreate() {
  visible.value = false;
  // 走 router 而不是改 hash（W1 M-5 收口）：hash 直改绕过路由器，
  // 没有守卫、没有历史记录语义，与「新建是导航」的口径不符
  router.push({ path: "/account/kbs", query: { create: "true" } });
}
</script>

<template>
  <el-popover
    v-model:visible="visible"
    placement="top-start"
    :width="280"
    :teleported="true"
    popper-class="shell-pop kb-switcher-pop"
    trigger="click"
  >
    <template #reference>
      <div class="kb-switcher" :title="S.nav.kbLabel as string">
        <LayersIcon width="15" height="15" style="color: #94a3b8" />
        <span class="kb-switcher-name">{{ name }}</span>
        <ChevronDownIcon width="12" height="12" style="color: #94a3b8" />
      </div>
    </template>

    <div class="border-b px-4 py-3" style="border-color: #e5eaf1">
      <!-- 查找：它是面板的一段，不是面板里摆的一个控件。打开就聚焦，
           一开口就能打字；Esc 与外点由 Popover 统一管（旧仓注释原话） -->
      <el-input
        ref="findInput"
        v-model="q"
        :placeholder="S.nav.findKb as string"
        :bordered="false"
        size="default"
      />
    </div>
    <el-scrollbar max-height="320px">
      <div class="py-1">
        <div
          v-for="k in shown"
          :key="k.id"
          role="button"
          tabindex="0"
          class="shell-row"
          @click="onPick(k.id)"
          @keydown.enter="onPick(k.id)"
        >
          <span class="min-w-0 flex-1 truncate">{{ k.name }}</span>
          <CheckIcon
            v-if="props.kb && k.id === props.kb.id"
            width="13"
            height="13"
            style="color: #94a3b8"
          />
        </div>
        <p v-if="shown.length === 0" class="px-4 py-3 text-sm" style="color: #475569">
          {{ S.nav.noKbMatch }}
        </p>
      </div>
    </el-scrollbar>
    <!-- 新建钉在最下面，不混在列里：它不是一个库。去的是「我的知识库」，
         带上 create——落地就是表单（旧仓注释原话） -->
    <div
      v-if="canCreate"
      class="shell-row border-t"
      style="border-color: #e5eaf1; padding: 10px 16px"
      role="button"
      tabindex="0"
      @click="goCreate"
      @keydown.enter="goCreate"
    >
      <PlusIcon width="14" height="14" />
      {{ S.settings.kbs.newKb }}
    </div>
  </el-popover>
</template>

<style scoped>
/* 与顶栏另外两个面板的行同一副（px-4 py-2，正文号，图标 gap 3）——旧仓 ITEM 同款 */
.shell-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 16px;
  font-size: 14px;
  color: var(--el-text-color-primary);
  cursor: pointer;
}

.shell-row:hover {
  background: var(--el-fill-color-light);
}
</style>
