<!-- 部署管理（对照旧仓 pages/Settings.tsx 的 Settings 主组件）：五个页签
     （模型/成员/数据源/SSO/部署），页签落在地址上（?tab=，AccountShell 的
     子导航写它）——刷新、回退、把链接发给同事都落回同一节（旧仓注释原话）。
     各页签本体分文件同目录。
     reskin-b4 模板层重造（script 逻辑零动，仅 import 换装）：h1 升 PageHeader
     （admin 经 /admin 有自己的路由位，页头报当前节名——与旧 h1 同源）。 -->
<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { S } from "@/i18n";
import { usePageTitle } from "@/composables/usePageTitle";
import ModelsTab from "./models-tab.vue";
import MembersTab from "./members-tab.vue";
import DataSourcesTab from "./datasources-tab.vue";
import SsoTab from "./sso-tab.vue";
import DeploymentTab from "./deployment-tab.vue";
/* PageHeader（reskin-b1 自 gvdata 零改写迁入）：22/800 标题 + 副题 */
import PageHeader from "@/components/PageHeader/index.vue";

defineOptions({ name: "AdminSettings" });

const route = useRoute();
const router = useRouter();

const TABS = [
  { key: "models", label: () => S.settings.tabModels },
  { key: "members", label: () => S.settings.tabMembers },
  { key: "datasources", label: () => S.settings.datasources.tab },
  { key: "sso", label: () => S.settings.tabSso },
  { key: "deployment", label: () => S.settings.tabDeployment }
] as const;

const tab = computed(() => {
  const t = typeof route.query.tab === "string" ? route.query.tab : "models";
  return (TABS.some(x => x.key === t) ? t : "models") as (typeof TABS)[number]["key"];
});
usePageTitle(S.settings.title as string, S.account.titleTag as string);

function goTab(key: string) {
  router.push({ path: "/admin", query: { tab: key } });
}
</script>

<template>
  <div class="knowledge-page px-8 py-6">
    <div class="mx-auto w-full max-w-4xl">
      <!-- 页头（reskin-b4）：/admin 独立路由位——页头报当前节名（与旧 h1 同源，
           shell 子页签之上再放大一档） -->
      <PageHeader
        :title="TABS.find(t => t.key === tab)?.label() ?? S.settings.title"
      />
      <ModelsTab v-if="tab === 'models'" />
      <MembersTab v-else-if="tab === 'members'" />
      <DataSourcesTab v-else-if="tab === 'datasources'" />
      <SsoTab v-else-if="tab === 'sso'" />
      <DeploymentTab v-else />
    </div>
  </div>
</template>
