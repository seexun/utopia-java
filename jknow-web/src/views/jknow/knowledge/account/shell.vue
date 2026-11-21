<!-- 账户层壳（对照旧仓 pages/AccountShell.tsx）：Profile / Administration 的宿主。
     kb/account 若依壳内联（规格 2026-11-18-kb-inline-layout）——去壳化：原自带
     的全屏侧栏/顶栏（w-64 aside + h-14 header）退役，侧栏/顶栏/页签职责交若依
     Layout，本组件只保留页内 tab 导航 + router-view；管理页子页签（?tab=）
     仍住在这里。根上的 knowledge-page 保留：profile/my-kbs/tokens 页根本身
     不带该类，EP 主题令牌（utopia.scss）靠这层祖先作用域。 -->
<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter, RouterLink } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import { Icon } from "@iconify/vue/dist/offline";
import { me as meApi } from "@/api/knowledge/auth";
import { S } from "@/i18n";
import { usePageTitle } from "@/composables/usePageTitle";

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

// 标题跟随当前页：`Utopia | 账户`（品牌在前，与主壳同口径）
usePageTitle(S.app.name, S.account.titleTag as string);

function goAdminTab(key: string) {
  router.push({ path: "/admin", query: { tab: key } });
}
</script>

<template>
  <div class="knowledge-page knowledge-account flex h-full min-h-0 flex-col">
    <!-- 页内 tab 导航（去壳化：原左侧菜单组改横向页签；若依壳供侧栏/顶栏） -->
    <nav class="account-tabs" :aria-label="S.account.groupPersonal as string">
      <RouterLink
        to="/account/profile"
        class="account-tab"
        :class="route.path === '/account/profile' ? 'is-active' : ''"
      >
        <Icon icon="lucide:user-round" width="15" height="15" />
        {{ S.account.profile }}
      </RouterLink>
      <RouterLink
        to="/account/kbs"
        class="account-tab"
        :class="route.path === '/account/kbs' ? 'is-active' : ''"
      >
        <Icon icon="lucide:layers" width="15" height="15" />
        {{ S.account.kbsNav }}
      </RouterLink>
      <RouterLink
        to="/account/tokens"
        class="account-tab"
        :class="route.path === '/account/tokens' ? 'is-active' : ''"
      >
        <Icon icon="lucide:key-round" width="15" height="15" />
        {{ S.account.tokensNav }}
      </RouterLink>
      <RouterLink
        v-if="me.data.value?.is_admin"
        to="/admin"
        class="account-tab"
        :class="onAdmin ? 'is-active' : ''"
      >
        <Icon icon="lucide:shield-check" width="15" height="15" />
        {{ S.account.administration }}
      </RouterLink>
    </nav>

    <!-- 管理页子页签（?tab= 落地址：刷新/回退/分享链接都落回同一节） -->
    <nav v-if="onAdmin" class="admin-subtabs" aria-label="Administration">
      <button
        v-for="t in ADMIN_TABS"
        :key="t.key"
        type="button"
        class="subtab"
        :class="adminTab === t.key ? 'is-active' : ''"
        @click="goAdminTab(t.key)"
      >
        {{ t.label() }}
      </button>
    </nav>

    <main class="account-main min-h-0 flex-1 overflow-y-auto">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
/* 页签条（antd Tabs 底线式）：高 44px、下分割线；选中 = 主色 + 2px 底线 */
.account-tabs {
  display: flex;
  gap: 4px;
  padding: 0 16px;
  border-bottom: 1px solid var(--el-border-color-light, #e5eaf0);
  background: var(--el-bg-color, #fff);
}

.account-tab {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  height: 44px;
  padding: 0 14px;
  font-size: 14px;
  font-weight: 500;
  color: var(--el-text-color-regular, #6b7280);
  border-bottom: 2px solid transparent;
  transition:
    color 120ms,
    border-color 120ms;
}

.account-tab:hover {
  color: var(--el-text-color-primary, #1f2937);
}

.account-tab.is-active {
  font-weight: 600;
  color: var(--el-color-primary, #1677ff);
  border-bottom-color: var(--el-color-primary, #1677ff);
}

/* 管理页子页签行：贴主页签之下、内容区之上（缩进子项的横排化） */
.admin-subtabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 20px;
  border-bottom: 1px solid var(--el-border-color-lighter, #eef2f6);
  background: var(--el-bg-color, #fff);
}

.subtab {
  height: 30px;
  padding: 0 14px;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-regular, #6b7280);
  background: transparent;
  border: 0;
  border-radius: 8px;
  cursor: pointer;
  transition:
    background-color 120ms,
    color 120ms;
}

.subtab:hover {
  color: var(--el-text-color-primary, #1f2937);
  background: var(--el-fill-color-light, #f1f5f9);
}

.subtab.is-active {
  color: var(--el-color-primary, #1677ff);
  background: var(--el-color-primary-light-9, #e6f4ff);
}
</style>
