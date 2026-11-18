<!-- 账户层壳（对照旧仓 pages/AccountShell.tsx）：Profile / Administration 的宿主。
     与 KB 无关：没有库切换器，菜单是「个人中心」一组（规格 §4.1）；管理页子页签
     作为缩进子项。独立整页路由（remaining.ts），不经 pure-admin 的三栏壳拍平。 -->
<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter, RouterLink } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import { Icon } from "@iconify/vue/dist/offline";
import { me as meApi } from "@/api/knowledge/auth";
import { health } from "@/api/knowledge/admin";
import { S } from "@/i18n";
import { usePageTitle } from "@/composables/usePageTitle";
import { useAlertEvents } from "@/composables/useAlertEvents";
import AlertBell from "@/components/knowledge/alert-bell/index.vue";
import UserMenu from "@/components/knowledge/user-menu/index.vue";

/** 管理页子页签（对照旧仓 Settings.tsx 的 ADMIN_TABS）：
    W1 只落导航；部署管理页本体（模型/成员/数据源/SSO 管理面）随任务 6-7 迁 */
const ADMIN_TABS = [
  { key: "models", label: () => S.settings.tabModels },
  { key: "members", label: () => S.settings.tabMembers },
  { key: "datasources", label: () => S.settings.datasources.tab },
  { key: "sso", label: () => S.settings.tabSso },
  { key: "deployment", label: () => S.settings.tabDeployment }
];

defineOptions({ name: "AccountShell" });

const route = useRoute();
const router = useRouter();
const onAdmin = computed(() => route.path === "/admin");
const adminTab = computed(() =>
  typeof route.query.tab === "string" ? route.query.tab : "models"
);

const me = useQuery({ queryKey: ["me"], queryFn: meApi });
// 版本胶囊（HeaderActions 的 GitHub·version badge）：会话内不变的键
const healthQ = useQuery({
  queryKey: ["health"],
  queryFn: health,
  staleTime: Infinity
});

// 标题跟随当前页：`Utopia | 账户`（品牌在前，与主壳同口径）
usePageTitle(S.app.name, S.account.titleTag as string);
// 账户层没有库上下文：单订全局告警流（对照旧仓 useAlertEvents(settled && !kb)）
useAlertEvents(true);

function goAdminTab(key: string) {
  router.push({ path: "/admin", query: { tab: key } });
}
</script>

<template>
  <div class="knowledge-page flex h-screen overflow-hidden">
    <aside class="flex w-64 shrink-0 flex-col border-r border-line bg-card">
      <!-- 品牌字标（旧仓 layout-logo + Wordmark）：渐变方块 + Utopia -->
      <RouterLink
        to="/graph"
        class="flex items-center gap-2.5 px-5 py-4"
        title="Utopia"
      >
        <span
          class="grid size-7 place-items-center rounded-ctl text-white"
          style="background: linear-gradient(135deg, #1677ff, #06b6d4)"
        >
          <Icon icon="lucide:lock" width="14" height="14" />
        </span>
        <span class="text-title font-bold tracking-tight text-ink">Utopia</span>
      </RouterLink>
      <nav class="flex-1 overflow-y-auto px-3 py-2">
        <div
          class="px-3 pb-2 pt-3 text-aux font-semibold uppercase tracking-wide text-ink-3"
        >
          {{ S.account.groupPersonal }}
        </div>
        <RouterLink
          to="/account"
          class="menu-item"
          :class="route.path === '/account' ? 'is-active' : ''"
        >
          <Icon icon="lucide:user-round" width="16" height="16" />
          {{ S.account.profile }}
        </RouterLink>
        <RouterLink
          to="/account/kbs"
          class="menu-item"
          :class="route.path === '/account/kbs' ? 'is-active' : ''"
        >
          <Icon icon="lucide:layers" width="16" height="16" />
          {{ S.account.kbsNav }}
        </RouterLink>
        <RouterLink
          to="/account/tokens"
          class="menu-item"
          :class="route.path === '/account/tokens' ? 'is-active' : ''"
        >
          <Icon icon="lucide:key-round" width="16" height="16" />
          {{ S.account.tokensNav }}
        </RouterLink>
        <template v-if="me.data.value?.is_admin">
          <RouterLink
            to="/admin"
            class="menu-item"
            :class="onAdmin ? 'is-active' : ''"
          >
            <Icon icon="lucide:shield-check" width="16" height="16" />
            {{ S.account.administration }}
          </RouterLink>
          <template v-if="onAdmin">
            <button
              v-for="t in ADMIN_TABS"
              :key="t.key"
              type="button"
              class="menu-item pl-9 text-sub"
              :class="adminTab === t.key ? 'is-active' : ''"
              @click="goAdminTab(t.key)"
            >
              {{ t.label() }}
            </button>
          </template>
        </template>
      </nav>
      <!-- 账户层没有库切换器：留空（旧仓注释原话） -->
    </aside>

    <div class="flex min-w-0 flex-1 flex-col">
      <header
        class="relative z-40 flex-bc h-14 border-b border-line bg-card px-6"
      >
        <nav
          class="flex items-center gap-3 text-sub text-ink-2"
          aria-label="Breadcrumb"
        >
          <!-- 旧仓 AccountShell 顶栏第一链接（backToApp → /）——迁移时漏过，对齐补回 -->
          <RouterLink
            to="/graph"
            class="rounded-ctl px-2 py-1 font-semibold text-ink hover:bg-fill hover:text-primary"
            :title="S.account.backToApp as string"
          >
            {{ S.account.backToApp }}
          </RouterLink>
          <span class="font-semibold text-ink">{{ S.account.titleTag }}</span>
        </nav>
        <div class="flex items-center gap-2">
          <!-- 文档 · 版本 · 铃 · 用户（HeaderActions 对等） -->
          <RouterLink
            to="/docs/ingest"
            class="rounded-ctl p-2 text-ink-2 hover:bg-fill hover:text-ink"
            :title="S.nav.docs as string"
          >
            <Icon icon="lucide:book-open" width="16" height="16" />
          </RouterLink>
          <span
            class="hidden rounded-full border border-line bg-card px-2.5 py-1 text-aux text-ink-2 md:inline-flex"
          >
            <Icon icon="simple-icons:github" width="11" height="11" />
            v{{ healthQ.data.value?.version ?? "…" }}
          </span>
          <AlertBell />
          <UserMenu v-if="me.data.value" :user="me.data.value" />
        </div>
      </header>
      <main class="min-h-0 flex-1 overflow-y-auto">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
/* 账户层菜单条目（旧仓 .menu-item 同一副：38px、圆角、active 主色 soft 底） */
.menu-item {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  height: 38px;
  padding: 0 12px;
  font-size: 14px;
  font-weight: 500;
  color: #475569;
  border-radius: 8px;
  transition:
    background-color 120ms,
    color 120ms;
}

.menu-item:hover {
  color: #0f172a;
  background: #f1f5f9;
}

.menu-item.is-active {
  color: #1677ff;
  background: #e6f4ff;
}
</style>
