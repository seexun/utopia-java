// KB 作用域装载（对照旧仓 KbScope.tsx 的宿主职责，任务 5 抽为 composable）：
// 直问后端 kbDetail（跨工作区深链不错杀）→ 记上下文（kbStore.load + wsStore
// 对齐）→ rebuildMenus。旧仓由 KbScope 组件包住每个页面；Vue 侧路由被
// pure-admin 拍平成平铺叶子（禁父子包装，见 router/modules/utopia.ts 头注），
// 所以「每个页面 setup 里调一次 useKbScope()」就是 KbScope 的对等物——
// 壳面包屑（库名）、角色门控（my_role）、SSE 失效面都从这里进料。
import { computed, watch, type ComputedRef, type Ref } from "vue";
import { useRoute } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import { ApiError } from "@/api/knowledge/client";
import { kbDetail } from "@/api/knowledge/kbs";
import type { Kb } from "@/api/knowledge/types";
// —— jknow T2 类型桩（源仓 @/store/modules/kb 属 pure-admin 基建，未随 T2 迁移；
// T3 随 store 基建接线后还原为真实 import。桩面与 useKbContext.ts 保持一致）——
interface KbContextStore {
  kbId: string;
  workspaceId: string;
  kbDetail: Kb | null;
  loading: boolean;
  load(kbId: string): Promise<Kb | null>;
  remember(kbId: string): void;
  reset(): void;
}
const useKbContextHook: () => KbContextStore = () => {
  throw new Error("T3 TODO：utopia pinia store（@/store/modules/kb）未随 T2 迁移");
};
import { useKbContext } from "@/composables/useKbContext";

export function useKbScope(): {
  kbId: ComputedRef<string>;
  kb: Ref<Kb | null>;
  noAccess: ComputedRef<{ status: number } | null>;
} {
  const route = useRoute();
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
    (kb: Kb | undefined) => {
      if (!kb) return;
      if (kbStore.kbId !== kb.id || kbStore.kbDetail?.my_role !== kb.my_role) {
        kbStore.load(kb.id).then(() => rebuildMenus());
      }
    },
    { immediate: true }
  );

  const noAccess = computed(() => {
    const err = kbQuery.error.value;
    if (!kbQuery.isError.value || !err) return null;
    return { status: err.status || 500 };
  });

  return {
    kbId,
    kb: computed(() => kbQuery.data.value ?? null),
    noAccess
  };
}
