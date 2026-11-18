<!-- 旧路径（`/graph` 这类不带库的地址）的接管兜底（对照旧仓 KbScope.tsx 的 KbRedirect）。
     **主路径在守卫**：router/index.ts 的 beforeEach 对这组路径 await
     resolveLegacyKbPath（fetchQuery 与组件侧同缓存键），直接返回重定向——
     不渲染中间组件（W1 审查收口：跳板组件的「渲染 → replace」会让主区
     Transition 的 out-in 离场时序不稳，页面挂载间歇性丢失）。
     **本组件只是解析失败（无工作区/无库）时的兜底**：等 useKbContext
     把库解析出来再走（旧仓「等 useKb 解析出来再走」的守卫版兜底）。
     **不能在路由守卫里同步重定向**——那时候库列表还没取回来，localStorage 里
     也可能什么都没有（新设备、清过缓存）。（旧仓注释原话。） -->
<script setup lang="ts">
import { computed, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { S } from "@/i18n";
import { useKbContext } from "@/composables/useKbContext";

const route = useRoute();
const router = useRouter();
const { kb, settled } = useKbContext();

const PAGES = [
  "graph",
  "search",
  "chat",
  "library",
  "ontology",
  "mappings",
  "review",
  "settings"
] as const;

/** 页名取自路径第一段；/kb-settings 是老名字，页名是 settings（旧仓路由表逐条） */
const page = computed<(typeof PAGES)[number] | null>(() => {
  const seg = route.path.replace(/^\//, "").split("/")[0] ?? "";
  if (seg === "kb-settings") return "settings";
  return (PAGES as readonly string[]).includes(seg)
    ? (seg as (typeof PAGES)[number])
    : null;
});

watch(
  [() => kb.value?.id, settled],
  ([id, ready]) => {
    if (!id || !ready || !page.value) return;
    router.replace({
      path: `/kb/${id}/${page.value}`,
      query: route.query
    });
  },
  { immediate: true }
);
</script>

<template>
  <!-- 兜底等待页：壳在、内容空（与旧仓 KbRedirect 同宿主同观感——一行「加载中」）。
       **根必须是带内容盒的真实元素**：主区 Transition(mode=out-in) 的离场
       要靠 transitionend/时长超时收尾，空 div 会把离场卡在半途。 -->
  <div class="grid h-full place-items-center px-6">
    <p class="text-sm" style="color: #94a3b8">{{ S.nav.loading }}</p>
  </div>
</template>
