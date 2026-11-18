// API 层出口：按域拆分的请求函数（对照旧仓 web/src/api.ts 的 api 对象逐函数搬运，
// 仅把 fetch 收口换成 axios 基座）。页面按域 import，例如：
//   import { graphOverview } from "@/api/knowledge/graph";
// jknow T3 落字：auth.ts 以适配层回归——导出面（函数名/返回形状）与源仓一致，
// 实现映射若依端点（/getInfo、/login、/logout 等），见 auth.ts 头注。
export * from "./auth";
export * from "./client";
export * from "./me";
export * from "./workspaces";
export * from "./kbs";
export * from "./graph";
export * from "./ontology";
export * from "./review";
export * from "./documents";
export * from "./sources";
export * from "./mappings";
export * from "./chat";
export * from "./alerts";
export * from "./admin";
export * from "./types";
