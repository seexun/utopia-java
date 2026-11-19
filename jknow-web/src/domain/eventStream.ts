// 一条通知用的事件流：断线恢复时补刷，页面藏起来一阵后把连接让出来（#1028）。
//
// 浏览器给同一个源的 HTTP/1.1 连接只有六条，事件流一条占一条、占着不放。从前每个标签页
// 开两条通知流（当前库的事件、告警），正在生成的回答再占一条：两个标签页就把六条占满，
// Stop 这样的普通请求排在后面发不出去；三个空闲的标签页也一样。所以一页只开一条通知流
// （库事件那条顺带送告警，见 `useKbEvents`），藏起来超过 `HIDDEN_RELEASE_MS` 的页把它
// 让出来，回到前台再连上。
//
// 事件没有回放：断线期间、让出期间的变化都收不到，所以重新连上时调一次 `onRecover` 补刷。
// 首次正常连接不补刷，页面自己的读取已经是新的。
//
// jknow 收尾冲刺 T1：EventSource 不能带 Authorization 头，过不了若依鉴权——改 fetch
// 流式读（与 api.ts 两条对话流同一套读法：eventsource-parser 逐帧解析，后端线上是
// `event:<kind>`+`data:{...}`、15s 一条 `:keep-alive` 注释）。EventSource 自带的断线
// 自动重连以退避重连近似（默认 3s，规范 `retry:` 帧可改写）；「响应 200 且 body
// 就绪」即旧 open 事件的对等物——重连成功照旧补刷，流断/连不上即旧 error 事件的
// 对等物——漏事件打标。回调契约不变：事件名分发到 handlers 表，重连补刷调
// onRecover，消费方零动。

import { createParser } from "eventsource-parser";
import { getToken } from "@/utils/auth";

/** 页面藏起来多久之后让出连接。短暂切走再回来不断流，也就不用补刷 */
export const HIDDEN_RELEASE_MS = 30_000;

/** 断线后多久重连：EventSource 的浏览器默认量级（后端心跳 15s、不发 retry: 字段；
 *  规范的 `retry:` 帧可改写下一次退避——与 EventSource 同语义） */
export const RECONNECT_MS = 3_000;

export function subscribeEvents(
  url: string,
  handlers: Record<string, () => void>,
  onRecover: () => void,
): () => void {
  const page = typeof document === "undefined" ? null : document;
  let controller: AbortController | null = null;
  let retry: ReturnType<typeof setTimeout> | null = null;
  let retryMs = RECONNECT_MS;
  let missed = false;
  let disposed = false;
  let release: ReturnType<typeof setTimeout> | null = null;

  // 卸载之后迟到的帧一律不作数：旧连接的事件不能再去失效新页面的查询
  const dispatch = (type: string) => {
    if (!disposed) handlers[type]?.();
  };

  // 一条连接的完整一生：连上（=旧 open）→ 逐帧分发 → 断了择机重连。
  // controller 先占位再 await：open() 的重入闸靠它同步生效（对等旧 `if (source) return`）。
  const connect = async (): Promise<void> => {
    if (disposed || controller) return;
    const current = new AbortController();
    controller = current;
    try {
      const token = getToken();
      const res = await fetch(url, {
        credentials: "include",
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        signal: current.signal,
      });
      if (current.signal.aborted) return;
      if (!res.ok || !res.body) throw new Error(`SSE ${res.status}`);
      // 连上了：断线期间漏掉的变化此刻补刷（首次正常连接 missed=false，不补——
      // 页面自己的读取已经是新的）
      if (missed) {
        missed = false;
        onRecover();
      }
      const parser = createParser({
        // 事件不带业务数据（data 是占位 JSON），按事件名分发；注释心跳解析器自弃
        onEvent: ({ event }) => {
          if (event) dispatch(event);
        },
        onRetry: (ms) => {
          retryMs = ms;
        },
      });
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      for (;;) {
        const { done, value } = await reader.read();
        if (done || current.signal.aborted) break;
        parser.feed(decoder.decode(value, { stream: true }));
      }
    } catch {
      /* 断线/中止——走到下面统一排程重连 */
    } finally {
      if (controller === current) controller = null;
    }
    if (disposed || current.signal.aborted) return;
    // 服务端结束或网络错（=旧 error 事件）：漏事件打标，按最近一次 retry: 退避重连
    missed = true;
    retry = setTimeout(() => {
      retry = null;
      void connect();
    }, retryMs);
  };

  const open = () => void connect();
  const close = () => {
    if (retry !== null) {
      clearTimeout(retry);
      retry = null;
    }
    controller?.abort();
    controller = null;
  };
  const cancelRelease = () => {
    if (release === null) return;
    clearTimeout(release);
    release = null;
  };
  const onVisibility = () => {
    cancelRelease();
    if (page?.visibilityState !== "hidden") {
      open();
      return;
    }
    release = setTimeout(() => {
      release = null;
      if (!controller) return;
      close();
      missed = true;
    }, HIDDEN_RELEASE_MS);
  };

  page?.addEventListener("visibilitychange", onVisibility);
  if (page?.visibilityState === "hidden") missed = true;
  else open();

  return () => {
    disposed = true;
    page?.removeEventListener("visibilitychange", onVisibility);
    cancelRelease();
    close();
  };
}
