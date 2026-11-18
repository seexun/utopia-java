// 壳级探活（任务 7，收 W1 遗留②——后端中途挂掉的壳级探测）：
// 旧壳的兜底是 me 查询的 refetch（各页 12 处同名查询，query gc 后重挂载时补探，
// 约 5 分钟量级、且依赖用户到处导航），错误时整壳翻 ServerDown。新壳的 me 走
// 守卫（冷加载/导航才探测），壳开着时后端挂掉没人翻面——这里补一条显式的
// 周期探针：health 是部署级无认证端点（不牵扯会话），60 秒一次。
//
// 语义与 ensureMe 同一套 shell store：
//   - 非 401 的失败 = 后端不可达 ≠ 没登录 → markServerDown，整壳翻 ServerDown 页；
//   - 探针成功 → markAlive（后端回来，壳自愈——ServerDown 页不用手点刷新）。
// 探针在 layout 的 setup 里跑：ServerDown v-if 替换的是模板里的壳内容，
// layout 组件本身还活着，轮询不停，所以自愈路径成立。
import { watch } from "vue";
import { useQuery } from "@tanstack/vue-query";
import { health as healthApi } from "@/api/knowledge/admin";
import { ApiError } from "@/api/knowledge/client";
// T3 接线：源仓 @/store/modules/shell 落位 src/store/knowledge/modules/shell
import { useShellStoreHook } from "@/store/knowledge/modules/shell";

/** 探活周期。旧壳的隐式兜底约 5 分钟量级（query gc 后重挂载），这里取更明确的 60 秒 */
export const SERVER_PROBE_MS = 60_000;

export function useServerProbe() {
  const shell = useShellStoreHook();
  // ["health"] 与顶栏版本胶囊同键（SESSION_KEYS Infinity 档）：探针的周期刷新
  // 顺带让胶囊的版本号保持新鲜，一份数据两处用。
  const probe = useQuery({
    queryKey: ["health"],
    queryFn: healthApi,
    refetchInterval: SERVER_PROBE_MS,
    retry: false
  });

  watch(probe.error, (err: unknown) => {
    // 成功后 error 复位成 null，那一次不置位——只有真错误才翻面
    if (!err) return;
    // 401 是会话问题（守卫送登录页），不是后端不可达
    if (err instanceof ApiError && err.status === 401) return;
    shell.markServerDown();
  });
  watch(probe.isSuccess, (ok: boolean) => {
    if (ok) shell.markAlive();
  });
}
