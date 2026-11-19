// 对话域：会话列表/改名/详情/删除 + RAG 流式回答（SSE）（对照旧仓 api.ts 同域函数）。
// SSE 走 fetch 不走 axios：axios 在浏览器侧不支持流式读响应体。
// SSE 解析是内建的一份最小实现（只认 utopia 的帧），不引 eventsource-parser 依赖。
import { http, ApiError, refusalMessage, streamFailure } from "./client";
import { bearerHeaders } from "@/domain/api";
import { S } from "@/i18n";
import type {
  ConversationRow,
  ConversationMessage,
  ChatStep,
  Source
} from "./types";

/** 会话列表：**可搜可翻页**：标题会重（同一个问题问两次就重了），而固定一百条
 *  之后的会话界面上根本不存在。搜的是标题与消息正文两处——人记得住的往往是
 *  问过的那句话，不是标题 */
export const conversations = (kbId: string, q = "", limit = 30, offset = 0) =>
  http.request<{ conversations: ConversationRow[]; total: number }>(
    `/v1/kbs/${kbId}/conversations`,
    { params: { limit, offset, ...(q.trim() ? { q: q.trim() } : {}) } }
  );

/** 改标题。标题本来是从第一句话自动取的，而一段对话跑偏是常态 */
export const renameConversation = (kbId: string, id: string, title: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/conversations/${id}`, {
    method: "patch",
    data: { title }
  });

export const conversationDetail = (kbId: string, id: string) =>
  http.request<{ messages: ConversationMessage[] }>(
    `/v1/kbs/${kbId}/conversations/${id}`
  );

export const stopGeneration = (
  kbId: string,
  id: string,
  generationId: string
) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/chat/${id}/stop`, {
    method: "post",
    data: { generation_id: generationId }
  });

export const removeConversation = (kbId: string, id: string) =>
  http.request<{ ok: boolean }>(`/v1/kbs/${kbId}/conversations/${id}`, {
    method: "delete"
  });

export interface ChatHandlers {
  /** `messageId`：这一问存下的 id。答到一半失败了，凭它重答（#936）；老的服务端不发 */
  onConversation: (
    id: string,
    messageId?: string,
    generationId?: string
  ) => void;
  onSources: (s: Source[]) => void;
  onStep: (s: ChatStep) => void;
  onDelta: (text: string) => void;
  onDone: (stopped: boolean) => void;
  onError: (message: string, error?: ApiError) => void;
  /** 接上一个已经在跑的回答：这是它此刻的样子，**覆盖，不是追加** */
  onSnapshot?: (s: {
    generation_id: string;
    content: string;
    steps: ChatStep[];
    sources: Source[];
  }) => void;
  /** 这个会话没有在跑的生成——最常见的答案，不是错误 */
  onIdle?: () => void;
}

/** 接上一个正在生成的回答（刷新页面之后走这里）。
 *  与 `streamChat` 读的是同一条事件流，只是多了开头那份快照。 */
export function reattachChat(
  kbId: string,
  conversationId: string,
  handlers: ChatHandlers
): () => void {
  return consumeChatStream(
    signal =>
      fetch(`/api/v1/kbs/${kbId}/conversations/${conversationId}/stream`, {
        credentials: "include",
        headers: bearerHeaders(),
        signal
      }),
    handlers,
    true
  );
}

export function streamChat(
  kbId: string,
  /** `retry_message_id`：重答这场对话最后那个没有回答的问题（#936），不再存一遍 */
  body: {
    conversation_id?: string;
    message: string;
    retry_message_id?: string;
  },
  handlers: ChatHandlers
): () => void {
  return consumeChatStream(
    signal =>
      fetch(`/api/v1/kbs/${kbId}/chat`, {
        method: "POST",
        credentials: "include",
        headers: { "Content-Type": "application/json", ...bearerHeaders() },
        body: JSON.stringify(body),
        signal
      }),
    handlers
  );
}

/** SSE 的读法只有一份：发起请求的方式不同，收下来之后的处理完全一样。
 *  逐块喂进一个最小行解析器：`event:`/`data:` 两段一行一帧，空行分发。
 *  utopia 的帧都在一个 data 行里，不需要 eventsource-parser 的多 data 行拼接——
 *  即便出现，也按多 data 行以 \n 拼接处理（规范行为）。 */
function consumeChatStream(
  open: (signal: AbortSignal) => Promise<Response>,
  handlers: ChatHandlers,
  allowIdle = false
): () => void {
  const controller = new AbortController();
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
  let terminal = false;
  const fail = (text: string, error?: ApiError) => {
    if (terminal || controller.signal.aborted) return;
    terminal = true;
    handlers.onError(text, error);
  };
  (async () => {
    try {
      const res = await open(controller.signal);
      if (controller.signal.aborted) {
        await res.body?.cancel();
        return;
      }
      if (!res.ok || !res.body) {
        let text = res.statusText;
        let code: string | undefined;
        try {
          const body = (await res.json()) as { code?: string };
          code = body.code;
          text = refusalMessage(body, text);
        } catch {
          /* keep the HTTP status */
        }
        fail(text, new ApiError(res.status, text, code));
        return;
      }
      reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      let eventName = "";
      let dataLines: string[] = [];
      const dispatch = (event: string, data: string) => {
        if (terminal || controller.signal.aborted) return;
        if (event === "done") {
          const stopped = JSON.parse(data).stopped === true;
          terminal = true;
          handlers.onDone(stopped);
        } else if (event === "error") {
          fail(streamFailure(data));
        } else if (event === "idle") {
          if (allowIdle) {
            terminal = true;
            handlers.onIdle?.();
          } else {
            fail(S.ask.streamInterrupted);
          }
        } else if (event === "conversation") {
          const frame = JSON.parse(data);
          handlers.onConversation(
            frame.id,
            frame.message_id,
            frame.generation_id
          );
        } else if (event === "sources") {
          handlers.onSources(JSON.parse(data));
        } else if (event === "step") {
          handlers.onStep(JSON.parse(data));
        } else if (event === "delta") {
          handlers.onDelta(JSON.parse(data).text);
        } else if (event === "snapshot") {
          handlers.onSnapshot?.(JSON.parse(data));
        }
      };
      const feed = (chunk: string) => {
        buffer += chunk;
        // 规范按 \n\n 分帧；容忍 \r\n（先归一 \r\n → \n，尾部悬挂 \r 由下次块续上）
        buffer = buffer.replace(/\r\n/g, "\n");
        let idx: number;
        while ((idx = buffer.indexOf("\n\n")) !== -1) {
          const rawFrame = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          let ev = eventName;
          const lines = dataLines;
          for (const line of rawFrame.split("\n")) {
            if (line.startsWith("event:")) ev = line.slice(6).trim();
            else if (line.startsWith("data:"))
              lines.push(line.slice(5).trimStart());
          }
          eventName = "";
          dataLines = [];
          if (ev) dispatch(ev, lines.join("\n"));
        }
      };
      while (!terminal && !controller.signal.aborted) {
        const { done, value } = await reader.read();
        if (done) break;
        if (controller.signal.aborted) break;
        const chunk = decoder.decode(value, { stream: true });
        if (chunk) feed(chunk);
      }
      // EOF never dispatches an incomplete frame and is not an application done.
      fail(S.ask.streamInterrupted);
    } catch (e) {
      fail(e instanceof SyntaxError ? S.ask.streamInterrupted : String(e));
    } finally {
      if (reader) {
        try {
          await reader.cancel();
        } catch {
          /* terminal/abort already decided */
        }
        reader.releaseLock();
      }
    }
  })();
  return () => {
    controller.abort();
    void reader?.cancel().catch(() => {});
  };
}
