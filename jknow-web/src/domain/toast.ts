/* 【任务3抽离】自旧仓 web/src/ui/toast.tsx 取纯单例部分（toast 消息总线，clipboard.ts 消费）；
   React 组件面 <ToastHost/> 留在旧仓（Ⅲ类-重写，见本目录 ASSET-LEDGER.md）。
   除本头注外逐字原样。 */
/* 全局消息（toast）：模块级单例 + <ToastHost/>（挂在应用根部一次）。
   任何模块 `import { toast } from "./toast"` 即可弹消息，无需 context 接线。
   右下角堆叠，success/info 3.8s、error 6s 自动消失，可手动关闭。
   可带一个动作（如「撤销」）：带动作的留 8s，点了动作即关闭。 */

type Kind = "success" | "error" | "info";
type Action = { label: string; onClick: () => void };
type Item = { id: number; kind: Kind; text: string; action?: Action };

let nextId = 1;
let items: Item[] = [];
let listener: ((items: Item[]) => void) | null = null;

function dismiss(id: number) {
  items = items.filter((t) => t.id !== id);
  listener?.(items);
}

function push(kind: Kind, text: string, action?: Action) {
  const id = nextId++;
  items = [...items, { id, kind, text, action }];
  listener?.(items);
  // 带动作的多留一会儿：「撤销」要给人看清楚再点的时间
  const ttl = kind === "error" ? 6000 : action ? 8000 : 3800;
  window.setTimeout(() => dismiss(id), ttl);
}

export const toast = {
  success: (text: string, action?: Action) => push("success", text, action),
  error: (text: string) => push("error", text),
  info: (text: string, action?: Action) => push("info", text, action),
};

/** 渲染面订阅（W2：ToastHost.vue 消费）。返回退订函数；组件卸载时必须调，
 *  否则旧组件还挂着 listener，新组件收不到推送。 */
export function subscribe(fn: (items: Item[]) => void): () => void {
  listener = fn;
  fn(items);
  return () => {
    if (listener === fn) listener = null;
  };
}

export type ToastItem = Item;
