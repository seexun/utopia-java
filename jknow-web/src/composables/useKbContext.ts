// 库上下文的 Vue 面（对照旧仓 kb.tsx 的 useKb）：服务端状态（工作区/库列表）走
// vue-query，选择状态走 pinia store（store/modules/kb.ts），切库动作在这里——
// 它同时需要 router（导航）与 queryClient（失效），单独放 store 里会把
// 三样东西搅在一起。
//
// 语义逐条对齐旧仓 useKb：
//   - **URL 里有就以 URL 为准**：地址栏说的是"这个链接指向什么"，localStorage
//     说的是"我上次在看什么"。别人分享的链接必须赢过自己的记忆；
//   - 空工作区自动创建 General（#580：空着起步，不带包；非管理员 403 就静默
//     等管理员来创建）；
//   - **换库是一次导航，不只是记一笔**：把导航并进 switchKb 本身，而不是要求
//     每个调用点记得配一次 navigate——漏掉的约定就是会被忘掉的约定；
//   - 停在当前这一页（samePageInKb：本体页换库看到另一个库的本体，而不是被
//     送回图谱；深一层的会话/文档 id 是旧库的东西，换库就该丢掉）。
import { computed, type ComputedRef } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useQuery } from "@tanstack/vue-query";
import { STREAM_KEYS } from "@/domain/queryDefaults";
import { samePageInKb } from "@/domain/kbContext";
import * as kbsApi from "@/api/knowledge/kbs";
import * as workspacesApi from "@/api/knowledge/workspaces";
import type { Kb, Workspace } from "@/api/knowledge/types";
// T3 接线：源仓 @/store/modules/kb、@/store/query 落位 src/store/knowledge/
//（与若依 store 并存，共用同一枚 pinia 实例）；源仓 @/router/utils 的
// initRouter（pure-admin 菜单管线）不迁——P3 清理：其 jknow 侧消费链
//（侧栏菜单源/菜单 store/菜单重组动作）全库无生产引用，已整链摘除。
import { useKbContextHook } from "@/store/knowledge/modules/kb";
import { queryClient } from "@/store/knowledge/query";

/** 空工作区自动创建 General 的取数函数（#580：空着起步，不带包；非管理员 403
 *  就静默等管理员来创建——现实中首个用户即管理员，General 总在）。 */
async function fetchKbsForWorkspace(workspaceId: string): Promise<Kb[]> {
  const existing = await kbsApi.kbs(workspaceId);
  if (existing.length > 0) return existing;
  try {
    const created = await kbsApi.createKb(workspaceId, {
      name: "General",
      ontology_packs: []
    });
    queryClient.invalidateQueries({ queryKey: ["kbs", workspaceId] });
    return [created];
  } catch {
    return [];
  }
}

/** 旧路径接管（KbRedirect 的守卫等价物，W1 审查收口）：`/graph` 这类不带库的
 *  地址，解析出该去哪个库，返回 `/kb/{id}/{page}`。
 *  **为什么在守卫里 await 而不是渲染中间组件**：解析用 queryClient.fetchQuery
 *  （与组件侧的 useQuery 同一缓存键，去重共享）；中间组件的「渲染 → replace」
 *  会让主区 Transition 经历一次「空跳板离场」，out-in 的 enter 在这条链上
 *  时序不稳（页面挂载间歇性丢失）。守卫直接返回重定向，页面组件只挂载一次。
 *  解析失败（无工作区/无库）返回 null——落到路由表上的 KbRedirect 兜底组件。 */
export async function resolveLegacyKbPath(path: string): Promise<string | null> {
  const seg = path.replace(/^\//, "").split("/")[0] ?? "";
  const page = seg === "kb-settings" ? "settings" : seg;
  const PAGES = [
    "graph",
    "search",
    "chat",
    "library",
    "ontology",
    "mappings",
    "review",
    "settings"
  ];
  if (!PAGES.includes(page)) return null;
  const store = useKbContextHook();
  try {
    const wss = await queryClient.fetchQuery({
      queryKey: ["workspaces"],
      queryFn: () => workspacesApi.workspaces()
    });
    // 记忆里的工作区还在列表里就用它，否则第一个（与 useKbContext 的 workspace 一致）
    const ws = wss.find(w => w.id === store.workspaceId) ?? wss[0] ?? null;
    if (!ws) return null;
    const list = await queryClient.fetchQuery({
      queryKey: ["kbs", ws.id],
      queryFn: () => fetchKbsForWorkspace(ws.id)
    });
    const kb = list.find(k => k.id === store.kbId) ?? list[0] ?? null;
    if (!kb) return null;
    return `/kb/${kb.id}/${page}`;
  } catch {
    return null;
  }
}

export function useKbContext(): {
  /** 当前库（URL 优先：路由参数 > 记忆 > 列表第一个）。**库列表说了算**——
   *  路由参数指向的 id 不在当前工作区列表里时，回落列表第一个（与旧仓一致；
   *  作用域页面的真实数据不走这里，走 KbScope 自己直问后端的详情） */
  kb: ComputedRef<Kb | null>;
  kbs: ComputedRef<Kb[]>;
  workspace: ComputedRef<Workspace | null>;
  workspaces: ComputedRef<Workspace[]>;
  /** 工作区和库的列表都取回来了：kb 为空是真的没有库，不是还在加载 */
  settled: ComputedRef<boolean>;
  /** 换库：记一笔 + 失效事件流覆盖的键（目标库）+ 停在当前页跳过去 */
  switchKb: (id: string) => void;
} {
  const route = useRoute();
  const router = useRouter();
  const store = useKbContextHook();

  const workspacesQuery = useQuery(
    {
      queryKey: ["workspaces"],
      queryFn: () => workspacesApi.workspaces()
    },
    queryClient
  );
  const workspaces = computed(() => workspacesQuery.data.value ?? []);

  // 记忆里的工作区还在列表里就用它，否则第一个（与旧仓 ws = find ?? list[0] 一致）
  const workspace = computed(
    () =>
      workspaces.value.find((w: Workspace) => w.id === store.workspaceId) ??
      workspaces.value[0] ??
      null
  );

  const kbsQuery = useQuery(
    {
      queryKey: computed(() => ["kbs", workspace.value?.id]),
      queryFn: () => fetchKbsForWorkspace(workspace.value!.id),
      enabled: computed(() => !!workspace.value)
    },
    queryClient
  );
  const kbs = computed(() => kbsQuery.data.value ?? []);

  const routeKbId = computed(
    () => (route.params as { kbId?: string } | undefined)?.kbId
  );
  const kb = computed(
    () =>
      kbs.value.find((k: Kb) => k.id === (routeKbId.value ?? store.kbId)) ??
      kbs.value[0] ??
      null
  );

  const settled = computed(
    () => workspacesQuery.isFetched.value && (!workspace.value || kbsQuery.isFetched.value)
  );

  /** **换库是一次导航，不只是记一笔。** 只写 store 的话，值变了、组件也重渲染了，
   *  算出来的还是同一个库（URL 优先），切换器整个是死的——所以把导航并进动作
   *  本身（旧仓 kb.tsx setKb 注释原文的教训）。事件流只订当前库，目标库在没人订
   *  的这段时间里的变化没人失效过；这些键又不再一挂载就重拉（queryDefaults），
   *  所以切过去时按 STREAM_KEYS 主动失效一次目标库。地址里没有 kbId 时
   *  （账户页等）只记一笔——那里本来就不该被拽走。 */
  function switchKb(id: string) {
    store.remember(id);
    store.reset();
    for (const head of STREAM_KEYS) {
      queryClient.invalidateQueries({ queryKey: [head, id] });
    }
    const currentKbId = routeKbId.value;
    if (currentKbId && currentKbId !== id) {
      router.push(samePageInKb(route.path, currentKbId, id));
    }
  }

  return {
    kb,
    kbs,
    workspace,
    workspaces,
    settled,
    switchKb
  };
}
