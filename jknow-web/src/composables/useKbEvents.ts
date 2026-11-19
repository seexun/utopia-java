// KB 事件流订阅的 Vue 面（对照旧仓 useKbEvents.ts 的 React hook——该文件与
// useEventReconnect.test.ts 作为行为规格随迁保留，生产代码不再引用 react）。
// 收到事件只做失效重取（事件不带业务数据，天然幂等）；事件流断线自动重连
// （eventStream.ts 的 fetch 流式读，T1 起带若依 Bearer），重连成功后补刷漏掉的
// 变化。失效合并（一阵事件只换一次重取）的纯逻辑在
// domain/kbEventsCore.ts（与 React 随迁件逐字同源，仅 partialMatchKey 来自
// vue-query——两包同源 query-core，同一实现）。
import { watch, onScopeDispose } from "vue";
import { toValue, type MaybeRefOrGetter } from "vue";
import { useQueryClient } from "@tanstack/vue-query";
import { subscribeEvents } from "@/domain/eventStream";
import { SETTLE_MS, createInvalidationCoalescer } from "@/domain/kbEventsCore";
import { STREAM_KEYS } from "@/domain/queryDefaults";

export function useKbEvents(kbId: MaybeRefOrGetter<string | undefined>) {
  const queryClient = useQueryClient();
  let unsubscribe: (() => void) | null = null;
  // 卸载/换库时把攒着的刷掉而不是丢掉：换页回来看到的必须是新数据
  let flushPending = () => {};

  const stop = watch(
    () => toValue(kbId),
    (id: string | undefined) => {
      unsubscribe?.();
      flushPending();
      unsubscribe = null;
      flushPending = () => {};
      if (!id) return;
      const { push, flush } = createInvalidationCoalescer(
        key => queryClient.invalidateQueries({ queryKey: key }),
        SETTLE_MS
      );
      flushPending = flush;
      unsubscribe = subscribeEvents(
        `/api/v1/kbs/${id}/events`,
        {
          document: () => push(["documents", id], ["graph"]),
          graph: () => push(["graph"], ["ontologyRegressions", id]),
          // 映射探索跑完发的也是 review：Pending 那一栏得跟着刷新
          review: () =>
            push(["review", id], ["mappings", id], ["ontologyRegressions", id]),
          // 一句记忆抽出了等人点头的事实（0015）：对话里那张确认卡跟着长出来
          pending: () => push(["pending", id], ["review", id]),
          source: () => push(["sources", id], ["documents", id]),
          // 告警搭这条流过来（#1028）：打开着一个库的页不再另开告警流
          alert: () => push(["alerts"])
        },
        () => {
          // 事件没有回放，staleTime 到期也不会自己发请求。断线后恢复必须补刷；
          // 首次正常连接交给页面已有的读取，首次连接失败再恢复则同样可能漏事件。
          push(...STREAM_KEYS.map(head => [head, id]), ["alerts"]);
          flush();
        }
      );
    },
    { immediate: true }
  );

  onScopeDispose(() => {
    unsubscribe?.();
    flushPending();
    stop();
  });
}
