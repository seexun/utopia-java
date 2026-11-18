<!-- KB 作用域层（对照旧仓 KbScope.tsx）：`/kb/:kbId` 之下的一切都属于这个知识库。
     **为什么把库放进路径而不是查询参数**：库是容器不是筛选条件——图谱、搜索、
     文库、本体、复核全都在它下面。路径能表达这种包含关系，而且路由器会替你兜底：
     `/kb/$kbId/search` 没有 id 根本构造不出来。（旧仓注释原话。）
     W1：真正的 KB 页面在后续任务逐个迁入，这里先渲染「库上下文已就位」的占位页；
     本组件的职责（载详情/记上下文/无权限落地页）是壳的一部分，迁页面时保留。
     **模板必须是单元素根**：pure-admin 的主区是 Transition(mode=out-in) 包裹，
     注释根（v-if 全 false 且无 else）会让 enter 永远落不了地——外层 div 常驻。 -->
<script setup lang="ts">
import { computed, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import { ApiError } from "@/api/knowledge/client";
import { kbDetail } from "@/api/knowledge/kbs";
import type { Kb } from "@/api/knowledge/types";
import { S } from "@/i18n";
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { useKbContext } from "@/composables/useKbContext";

const route = useRoute();
const router = useRouter();
const kbStore = useKbContextHook();
const { rebuildMenus } = useKbContext();

const kbId = computed(
  () => (route.params as { kbId?: string } | undefined)?.kbId ?? ""
);

// **直接问后端，而不是在当前工作区的列表里找**：链接可能指向另一个工作区
// 里的库，那时列表里没有它，但用户其实有权限——照列表判断会错杀（旧仓注释原话）
const kbQuery = useQuery<Kb, ApiError>({
  queryKey: computed(() => ["kbOne", kbId.value]),
  queryFn: () => kbDetail(kbId.value),
  retry: false,
  enabled: computed(() => !!kbId.value)
});

// 打开哪个库，"上次看的"就跟到哪个库；工作区也一并对齐（旧仓 effect 同语义）。
// 详情到手后重组侧栏菜单——写菜单随角色显隐，要等 my_role 才知道
watch(
  () => kbQuery.data.value,
  kb => {
    if (!kb) return;
    if (kbStore.kbId !== kb.id || kbStore.kbDetail?.my_role !== kb.my_role) {
      kbStore.load(kb.id).then(() => rebuildMenus());
    }
  },
  { immediate: true }
);

// 库不存在或没权限时的落地页。**不能只给一张空图**——分享链接最常见的
// 失败就是对方没权限，而空白的图谱看起来像"这个库是空的"，是另一回事
// （旧仓注释原话）
const noAccess = computed(() => {
  const err = kbQuery.error.value;
  if (!kbQuery.isError.value || !err) return null;
  return { status: err.status || 500 };
});

function goMyKbs() {
  router.push("/account/kbs");
}
</script>

<template>
  <div class="knowledge-page utopia-page-scope h-full">
    <!-- 加载中什么都不画：这一层只是个作用域，闪一个 spinner 反而像页面在跳
         （旧仓注释原话）——但根元素常驻（Transition 的 enter 要有落点） -->
    <div v-if="noAccess" class="grid h-full place-items-center px-6">
      <div class="max-w-sm text-center">
        <h2 class="text-xl" style="color: #0f172a">
          {{
            noAccess.status === 404
              ? S.kbScope.missingTitle
              : S.kbScope.deniedTitle
          }}
        </h2>
        <p class="mt-2 text-sm leading-relaxed" style="color: #475569">
          {{
            noAccess.status === 404
              ? S.kbScope.missingBody
              : S.kbScope.deniedBody
          }}
        </p>
        <el-button class="mt-4" size="default" @click="goMyKbs">
          {{ S.kbScope.myKbs }}
        </el-button>
      </div>
    </div>
    <div
      v-else-if="kbQuery.data.value"
      class="grid h-full place-items-center px-6 text-center"
      style="color: #94a3b8"
    >
      <div class="max-w-md">
        <p class="text-base font-medium" style="color: #475569">
          {{ route.meta.title }} ·
          {{ kbQuery.data.value.name }}
        </p>
        <p class="mt-2 text-sm">
          <!-- 页面本体随后续任务迁入；壳/库上下文（W1）已就位 -->
          {{ S.nav.loading }}
        </p>
      </div>
    </div>
    <div v-else class="grid h-full place-items-center px-6" />
  </div>
</template>
