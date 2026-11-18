<!-- 菜单入口（P2 任务 4，V0102 起 8 个 C 菜单的统一 component）：kb 页不能直连——
     页面 kbId 严格取自 /kb/:kbId 路由参数（useKbScope），菜单路由 /knowledge/<页>
     上没有该参数，直连渲染＝全部查询 disabled 的死页；工作空间页（my-kbs）的取数
     门槛 kbStore.workspaceId 是 localStorage 记忆，冷登录为空同样死页。本组件两模式：
     - kb 页（graph/search/chat/ontology/review/settings/ingest）：复用 useKbContext
       的解析（workspaces→kbs，含空工作区自动建 General），落定后 replace 到
       /kb/{id}/{page}——与旧路径接管组件 KbRedirect 同构（那边页名取路径首段，
       这里取末段：/knowledge/graph 的首段是 knowledge）。ingest 域的页面载体是
       library 文库页（sources 列表/文档上传），名字对不上在这里换。
     - workspace（工作空间/库列表）：工作区落定后引导 kbStore.workspaceId（记忆为
       空时），原地渲染 my-kbs——库列表是工作区级页面，没有可跳的 kb 路由。
     解析不出（无工作区/无库）先停在等待页（与 KbRedirect 同观感）；8s 仍未落定
     转引导卡（P3）——最常见的卡因是「账号还没被拉进任何工作区且无权自建」
     （fetchKbsForWorkspace 对建库 403 静默回落空表），需要的是找管理员，不是
     永远转圈的「加载中」。 -->
<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount } from "vue";
import { useRoute, useRouter } from "vue-router";
import { S } from "@/i18n";
import { useKbContext } from "@/composables/useKbContext";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import MyKbs from "@/views/jknow/knowledge/account/my-kbs.vue";

defineOptions({ name: "KnowledgeKbEntry" });

const route = useRoute();
const router = useRouter();
const kbStore = useKbContextHook();
const { kb, workspace, settled } = useKbContext();

// 等待分支的超时转引导：8s 内解析落定（replace 走人/原地渲染 MyKbs）就用不上；
// 慢网下误转也无妨——解析成功时本组件随之卸载，引导卡自然让位
const guided = ref(false);
let guideTimer: ReturnType<typeof setTimeout> | null = null;
onMounted(() => {
  guideTimer = setTimeout(() => {
    guided.value = true;
  }, 8000);
});
onBeforeUnmount(() => {
  if (guideTimer) clearTimeout(guideTimer);
});

function reload() {
  window.location.reload();
}

/** 菜单 path（=URL 末段）→ kb 页名。workspace 不在此列（原地渲染模式）。 */
const KB_PAGES: Record<string, string> = {
  graph: "graph",
  search: "search",
  chat: "chat",
  ontology: "ontology",
  review: "review",
  settings: "settings",
  ingest: "library"
};

/** 目标取路径末段：菜单路由形如 /knowledge/<path>（若依 M 目录 + C 子页拼接）。 */
const segment = computed(
  () => route.path.replace(/\/+$/, "").split("/").pop() ?? ""
);
const page = computed<string | null>(() => KB_PAGES[segment.value] ?? null);
const isWorkspace = computed(() => segment.value === "workspace");

// kb 页模式：解析落定即 replace（query 保留——深链参数不丢语义）
watch(
  [() => kb.value?.id, settled],
  ([id, ready]) => {
    if (isWorkspace.value || !id || !ready || !page.value) return;
    router.replace({ path: `/kb/${id}/${page.value}`, query: route.query });
  },
  { immediate: true }
);

// workspace 模式：工作区落定后引导记忆（my-kbs 的取数门槛），然后原地渲染
watch(
  [() => workspace.value?.id, settled],
  ([wsId, ready]) => {
    if (!isWorkspace.value || !ready || !wsId) return;
    if (!kbStore.workspaceId) kbStore.workspaceId = wsId;
  },
  { immediate: true }
);
const workspaceReady = computed(
  () => isWorkspace.value && settled.value && !!workspace.value
);
</script>

<template>
  <MyKbs v-if="workspaceReady" />
  <!-- 引导卡：8s 未解析出工作区/库——状态一句话，动作在按钮上（与 kbScope
       的 noAccess 落地页同一副骨架） -->
  <div v-else-if="guided" class="grid h-full place-items-center px-6">
    <div class="max-w-sm text-center">
      <h2 class="text-xl" style="color: #0f172a">
        {{ S.kbEntry.guideTitle }}
      </h2>
      <p class="mt-2 text-sm leading-relaxed" style="color: #475569">
        {{ S.kbEntry.guideBody }}
      </p>
      <el-button class="mt-4" size="default" @click="reload">
        {{ S.kbEntry.retry }}
      </el-button>
    </div>
  </div>
  <!-- 等待页：壳在、内容空（与 KbRedirect 同观感——一行「加载中」） -->
  <div v-else class="grid h-full place-items-center px-6">
    <p class="text-sm" style="color: #94a3b8">{{ S.nav.loading }}</p>
  </div>
</template>
