// pure-admin 基座 http 封装的类型（原 src/utils/http/types.d.ts 原样迁入 api/）。
// jknow T2：源仓从 axios 具名导入 Method/AxiosError/AxiosResponse/AxiosRequestConfig
// 四个类型；jknow 沿用上游 RuoYi-Vue3 的 `declare module 'axios'` 简写声明（见
// src/types/global.d.ts——真包类型在声明面打成 any，具名类型导入会全体报错），
// 故此处改为**结构性等价**的本地类型：字段按 axios 1.x 公开形状收敛到本 api
// 目录的实际消费面，运行时零影响（运行时仍用真 axios 实例）。
export type resultType = {
  accessToken?: string;
};

export type RequestMethods =
  | "get"
  | "post"
  | "put"
  | "delete"
  | "patch"
  | "option"
  | "head";

/** 数组格式参数序列化器（axios paramsSerializer.serialize 的形状） */
export type CustomParamsSerializer = (params: unknown) => string;

export interface AxiosRequestConfig {
  url?: string;
  method?: string;
  baseURL?: string;
  headers?: Record<string, unknown>;
  params?: unknown;
  paramsSerializer?: { serialize?: CustomParamsSerializer };
  data?: unknown;
  timeout?: number;
  withCredentials?: boolean;
  responseType?: string;
  signal?: unknown;
  onUploadProgress?: (progressEvent: unknown) => void;
  onDownloadProgress?: (progressEvent: unknown) => void;
  validateStatus?: ((status: number) => boolean) | null;
}

/** 拦截器里的请求配置：headers 经 axios 归一为必填 */
export interface InternalAxiosRequestConfig extends AxiosRequestConfig {
  headers: Record<string, unknown>;
}

export interface AxiosResponse<T = unknown, D = unknown> {
  data: T;
  status: number;
  statusText: string;
  headers?: unknown;
  config: D;
  request?: unknown;
}

export interface AxiosError<T = unknown, D = unknown> extends Error {
  config?: D;
  code?: string;
  request?: unknown;
  response?: AxiosResponse<T, D>;
  isAxiosError?: boolean;
}

export interface AxiosInstance {
  defaults: {
    adapter?: unknown;
    headers?: Record<string, unknown>;
  };
  interceptors: {
    request: {
      use(
        onFulfilled?: (
          config: InternalAxiosRequestConfig
        ) => InternalAxiosRequestConfig,
        onRejected?: (error: unknown) => unknown
      ): unknown;
    };
    response: {
      // any 泛参对齐真 axios 的拦截器签名（回调参数按需收窄成 PureHttpResponse）
      use(
        onFulfilled?: (response: AxiosResponse<any, any>) => unknown,
        onRejected?: (error: AxiosError<any, any>) => unknown
      ): unknown;
    };
  };
  request<T = any>(config: AxiosRequestConfig): Promise<T>;
}

export interface PureHttpError extends AxiosError {
  isCancelRequest?: boolean;
}

export interface PureHttpResponse extends AxiosResponse {
  config: PureHttpRequestConfig;
}

export interface PureHttpRequestConfig extends AxiosRequestConfig {
  beforeRequestCallback?: (request: PureHttpRequestConfig) => void;
  beforeResponseCallback?: (response: PureHttpResponse) => void;
  /** 该请求的 401 不触发全局跳转（守卫的会话探测用自己的失败处置） */
  skipGlobal401?: boolean;
}
