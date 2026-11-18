// vue-query 客户端单例（对照旧仓 main.tsx 的 QueryClientProvider + UserMenu/Login
// 的会话边界清缓存）：main.ts 装进 VueQueryPlugin；登录/登出（user store 的
// 会话边界）从这里拿同一个实例 clear()。
//
// staleTime 按键分档，表在 domain/queryDefaults.ts（#518 的对等物）；这里只给
// 全局默认（对照旧仓 main.tsx：retry 关、不随窗口聚焦重拉）。分档表按键头装进
// 客户端：me/health/deployment 会话内不变（Infinity）、事件流覆盖的键 30 秒、
// 其余 15 秒——与旧仓 applyQueryDefaults 同一张表。
//
// **换人不靠过期，靠登录与登出清空**（domain/queryDefaults.ts 头注的旧仓决定）：
// 会话过期后同一标签页登另一个账号是唯一会露馅的路，所以登录那一清不能省——
// A 登出、B 登录后，A 的库列表/告警未读等缓存键不允许残留（审查 I-2）。
import { QueryClient } from "@tanstack/vue-query";
import { DEFAULT_STALE_MS, STREAM_KEYS, staleTimeFor } from "@/domain/queryDefaults";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
      staleTime: DEFAULT_STALE_MS
    }
  }
});

for (const head of [...STREAM_KEYS, "me", "health", "deployment"] as const) {
  queryClient.setQueryDefaults([head], { staleTime: staleTimeFor([head]) });
}
