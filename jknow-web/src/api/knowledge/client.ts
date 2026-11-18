// API 客户端：旧仓 web/src/api.ts 的 request 收口点换到 axios（pure-admin 基座机制）。
// 形状不变：错误收口成 ApiError（status / message / code）、拒绝措辞按 code 查 i18n
// err 表（旧仓 refusalMessage 同款）。
// jknow T2 认证改造（cookie → 若依 Bearer）：会话凭据从 utopia 同源 cookie 换成若依
// token（请求拦截器统一加 Authorization 头，@/utils/auth 取）；401 处置对齐若依惯例
// （ElMessage 会话过期 → useUserStore().logOut() → 跳 /login?redirect=）。裁定：若依
// entry point 保持 200+体 code:401 形状不改后端（T4 报告落字），前端成功分支按
// 体 code===401 一并截住。其余逻辑——错误归一/SSE——原样保留。
import Axios from "axios";
import type {
  AxiosError,
  AxiosInstance,
  AxiosRequestConfig,
  InternalAxiosRequestConfig,
  CustomParamsSerializer
} from "./types-http";
import { ElMessage } from "element-plus";
import type {
  PureHttpError,
  PureHttpResponse,
  PureHttpRequestConfig
} from "./types-http";
import { stringify } from "qs";
import { getToken } from "@/utils/auth"; // 若依 token 存取
import { message } from "@/utils/message";
import { S } from "@/i18n";

/** 服务端拒绝时回的正文（旧仓 Refusal 同形）：`{error, code, detail}` */
type Refusal = { error?: string; code?: string; detail?: string };

/** 若依 entry point 的会话过期正文（HTTP 200 + `{code: 401, msg}`——T4 裁定不改后端） */
type RuoYiRefusal = { code?: number; msg?: string };

/** 会话过期的提示语（若依惯例措辞；词典 S 无此键，此提示只在整壳层出现） */
const SESSION_EXPIRED_MSG = "会话已过期，请重新登录";

/** 服务端拒绝时抛的异常。status 是 HTTP 码；code 是稳定错误码（查 S.err 的键），
 *  没有则 message 已是服务端英文原句。页面拿到它自行决定内联展示或 toast。 */
export class ApiError extends Error {
  status: number;
  code?: string;
  constructor(status: number, message: string, code?: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

/** 一次拒绝给人看的那句话（旧仓 refusalMessage 原样搬运）。
 *
 *  有 code 就查 i18n，没有（或这一条还没进表）就退回服务端的英文原句——
 *  服务端永远说英文，因为界面语言在客户端。**普通请求与对话流共用这一处**：
 *  对话流从前只读 `error`，于是明明有译文的 `no_chat_model` 在中文界面上
 *  也是一句英文 */
export function refusalMessage(body: Refusal, fallback: string): string {
  let message = body.error || fallback;
  // code 来自网络，不是字面量——这一处 cast 换来 err 表本身的全量类型检查
  const worded = body.code
    ? (S.err as unknown as Record<string, string | undefined>)[body.code]
    : undefined;
  if (worded) message = worded;
  if (body.detail) message = S.errDetail(message, body.detail);
  return message;
}

/** 对话流里的 `error` 帧：与请求被拒是同一个信封 `{error, code}`，用同一个函数读。
 *  读不成 JSON 的是旧格式的一句英文，原样显示 */
export function streamFailure(data: string): string {
  try {
    const body: unknown = JSON.parse(data);
    if (body && typeof body === "object")
      return refusalMessage(body as Refusal, data);
  } catch {
    /* 旧格式：纯文本 */
  }
  return data;
}

// 相关配置请参考：www.axios-js.com/zh-cn/docs/#axios-request-config-1
const defaultConfig: AxiosRequestConfig = {
  // 同源代理基座：dev 经 vite proxy /api → 后端（jknow-admin 同应用托管 /api/v1/**），
  // 部署时同 jar 托管。域模块里的路径都写 /v1/...（旧仓写全 /api/v1/...，等价换算）
  baseURL: "/api",
  // jknow T2：源仓此处的同源 cookie 会话声明（凭据随 cookie 自动携带）已删——
  // 凭据改走请求拦截器的若依 Bearer 头（见 httpInterceptorsRequest）
  timeout: 60000,
  headers: {
    Accept: "application/json, text/plain, */*",
    "Content-Type": "application/json"
  },
  // 数组格式参数序列化（https://github.com/axios/axios/issues/5142）
  paramsSerializer: {
    serialize: stringify as unknown as CustomParamsSerializer
  }
};

/** 页面跳转的注入口（测试可换桩）：生产实现就是整页跳转 */
export const navigation = {
  to(url: string) {
    window.location.href = url;
  }
};

/** 复位 401 一次性闸门（I-2）：SPA 登录成功不整页刷新——不复位的话，
 *  同一页面里第二次会话过期的 401 会被闸门吞掉、静默失败。
 *  调用点：store 的 loginByUsername / registerAccount / logOut（会话边界）。 */
export function resetUnauthorizedGate() {
  PureHttp.redirecting401 = false;
}

export class PureHttp {
  constructor() {
    this.httpInterceptorsRequest();
    this.httpInterceptorsResponse();
  }

  /** 初始化配置对象 */
  private static initConfig: PureHttpRequestConfig = {};

  /** 保存当前`Axios`实例对象（公开给测试换 adapter 桩） */
  public static axiosInstance: AxiosInstance = Axios.create(defaultConfig);

  /** 401 全局拦截一次闸门——会话过期时一屏同时挂着四五个请求只跳一次；
   *  复位点：登录/注册/登出成功（resetUnauthorizedGate），SPA 不整页刷新 */
  public static redirecting401 = false;

  /** 请求拦截 */
  private httpInterceptorsRequest(): void {
    PureHttp.axiosInstance.interceptors.request.use(
      (config: InternalAxiosRequestConfig): InternalAxiosRequestConfig => {
        const $config = config as PureHttpRequestConfig;
        // 优先判断post/get等方法是否传入回调，否则执行初始化设置等回调
        if (typeof $config.beforeRequestCallback === "function") {
          $config.beforeRequestCallback($config);
          return config;
        }
        if (PureHttp.initConfig.beforeRequestCallback) {
          PureHttp.initConfig.beforeRequestCallback($config);
        }
        // jknow T2：若依 token 统一以 Bearer 头携带（未登录不带——登录页自身的
        // 请求没有 token 可言）
        const token = getToken();
        if (token) config.headers.Authorization = `Bearer ${token}`;
        // 上传/导入是 FormData：不能带 application/json 头（axios 会依内容自动定 multipart 边界）
        if (config.data instanceof FormData) {
          delete config.headers?.["Content-Type"];
        }
        return config;
      },
      error => {
        return Promise.reject(error);
      }
    );
  }

  /** 响应拦截 */
  private httpInterceptorsResponse(): void {
    const instance = PureHttp.axiosInstance;
    instance.interceptors.response.use(
      (response: PureHttpResponse) => {
        // jknow T2（T4 裁定：entry point 保持 200+体 code:401 形状）：若依安全链
        // 未认证时回 HTTP 200 + {code:401, msg}——成功分支里把会话过期截住，
        // 归一到与 HTTP 401 同一条处置线（ElMessage + logOut + 跳 /login）
        const body = response.data as RuoYiRefusal | undefined;
        if (body && typeof body === "object" && body.code === 401) {
          const skipGlobal401 =
            (response.config as PureHttpRequestConfig | undefined)
              ?.skipGlobal401 === true;
          if (!skipGlobal401)
            this.handleGlobalFailure(401, body.msg ?? SESSION_EXPIRED_MSG);
          return Promise.reject(
            new ApiError(401, body.msg ?? SESSION_EXPIRED_MSG)
          );
        }
        const $config = response.config;
        // 优先判断post/get等方法是否传入回调，否则执行初始化设置等回调
        if (typeof $config.beforeResponseCallback === "function") {
          $config.beforeResponseCallback(response);
          return response.data;
        }
        if (PureHttp.initConfig.beforeResponseCallback) {
          PureHttp.initConfig.beforeResponseCallback(response);
          return response.data;
        }
        return response.data;
      },
      (error: AxiosError) => {
        const status = error.response?.status ?? 0;
        let code: string | undefined;
        // 措辞在这一个收口点定：22 个文件里的 toast.error(e.message) 一处都不用改
        let text = error.response?.statusText || error.message || "Error";
        const body = error.response?.data as Refusal | string | undefined;
        if (body && typeof body === "object") {
          code = body.code;
          text = refusalMessage(body, text);
        } else if (typeof body === "string" && body.trim()) {
          try {
            const parsed = JSON.parse(body) as Refusal;
            code = parsed.code;
            text = refusalMessage(parsed, text);
          } catch {
            /* 非 JSON 响应体，保留 statusText */
          }
        }
        // 守卫的会话探测（ensureMe）自带处置：不触发全局 401 硬跳
        const skipGlobal401 =
          (error.config as PureHttpRequestConfig | undefined)?.skipGlobal401 ===
          true;
        if (!skipGlobal401) this.handleGlobalFailure(status, text);
        return Promise.reject(new ApiError(status, text, code));
      }
    );
  }

  /** 当前路由（jknow 若依壳：createWebHistory——真实路由在 pathname+search 里；
   *  源仓的 hash 读法随 history 模式改造，判"是否已在登录页"与取回落原处同源） */
  private static currentRoute(): string {
    const path = window.location.pathname + window.location.search;
    return path || "/";
  }

  /** 全局失败处置（W0 契约，jknow T2 对齐若依惯例）：
   *  - 401 会话失效：ElMessage 会话过期 → 若依 useUserStore().logOut()（清 token）→
   *    跳 /login?redirect=（redirect 参数与若依 permission.ts 守卫同款；已在登录页
   *    则不跳——密码错也是 401，登录页拿 reject 的 ApiError 自己内联显示）；
   *  - 403 无权限：toast 提示但不跳转；
   *  - 422 契约校验拒绝：后端 error 键文案直显（toast）；
   *  - 5xx：兜底文案 toast。
   *  请求仍以 reject 抛 ApiError，页面可再做内联处理（双份提示由页面自行取舍）。 */
  private handleGlobalFailure(status: number, text: string): void {
    if (status === 401) {
      if (PureHttp.redirecting401) return;
      const route = PureHttp.currentRoute();
      const routePath = route.split("?")[0];
      if (routePath.startsWith("/login")) return;
      PureHttp.redirecting401 = true;
      ElMessage({ message: SESSION_EXPIRED_MSG, type: "warning" });
      // 若依登出（懒加载 @/store/modules/user：静态引入会把若依 store 链上的
      // auto-import 依赖（defineStore 等）拖进测试环境——vitest 无 AutoImport
      // 插件直接 ReferenceError；懒加载让失败落在可兜底的 promise 里，真实
      // 应用不受影响）。清 token / 调若依 /logout；失败不拦跳转
      import("@/store/modules/user")
        .then(m => m.default().logOut())
        .catch(() => {
          /* 测试环境（store 链不可评估）或登出接口失败：不拦跳转 */
        });
      // 整页跳转（若依 history 路由）：redirect 参数带回落原处，登录后守卫送回
      navigation.to(`/login?redirect=${encodeURIComponent(route)}`);
      return;
    }
    if (status === 403 || status === 422) {
      // 文案在收口点已按 code 查过 err 表（refusalMessage）
      message(text, { type: "error" });
      return;
    }
    if (status >= 500) {
      message(S.nav.serverUnreachable, { type: "error" });
    }
  }

  /** 通用请求工具函数（URL 在前，方法/参数走配置——与旧仓 request(path, init) 同构）：
   *  http.request<T>("/v1/kbs/{id}/documents", { params, method: "post", data }) */
  public request<T>(
    url: string,
    config: PureHttpRequestConfig = {}
  ): Promise<T> {
    const merged = {
      url,
      ...config
    } as PureHttpRequestConfig;

    // 单独处理自定义请求/响应回调
    // （jknow T2：源仓 then 回调标 `(response: undefined)`——strict 模式下与真实
    // AxiosResponse 形状不合；响应拦截器实际已把值剥成 body，运行时不变，仅类型面收口）
    return new Promise((resolve, reject) => {
      PureHttp.axiosInstance
        .request(merged)
        .then((response: unknown) => {
          resolve(response as T);
        })
        .catch(error => {
          reject(error);
        });
    });
  }

  /** 单独抽离的`get`工具函数 */
  public get<T>(url: string, config?: PureHttpRequestConfig): Promise<T> {
    return this.request<T>(url, { ...config, method: "get" });
  }

  /** 单独抽离的`post`工具函数 */
  public post<T>(
    url: string,
    data?: unknown,
    config?: PureHttpRequestConfig
  ): Promise<T> {
    return this.request<T>(url, { ...config, method: "post", data });
  }
}

export const http = new PureHttp();

/** 把纯 http (PureHttpError) 之上的错误统一成 ApiError（页面 catch 里的类型收窄用） */
export function toApiError(e: unknown): ApiError {
  const err = e as PureHttpError;
  return err instanceof ApiError ? err : new ApiError(0, String(e));
}
