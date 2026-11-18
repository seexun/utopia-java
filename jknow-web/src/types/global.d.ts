/** 模块类型声明 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

/** Vite 环境变量类型 */
interface ImportMetaEnv {
  readonly VITE_APP_TITLE: string
  readonly VITE_APP_BASE_API: string
  readonly VITE_APP_ENV: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

// element-plus
declare module 'element-plus'

// vue
declare module 'vue' {
  interface ComponentInternalInstance { proxy: any }
  // jknow T2：支撑层随迁代码（src/composables、src/config、src/utils/message.ts）
  // 按源仓原样走 ESM 具名导入 vue 的工具与类型。本文件是无 import/export 的脚本
  // 文件，这里的 declare module 是**替换式**声明——真包具名导出（computed/Ref/
  // App…）在替换面内不可见（T1 报告预告的 TS2305 病根，P2 支撑层代码成规模踩中）。
  // 整体改为 augmentation 会让若依自有 .vue 面约 300 条潜在严格性错误浮出（上游
  // RuoYi-Vue3 即以这份替换声明钝化 typecheck，376 处 `ref<T>()`/`computed<T>()`
  // 泛型调用在「无类型」声明下本就宽松），故按消费面补具名导出。签名取
  // 「泛型 + 入参/返回 any」：泛型让显式类型实参（ref<T>()）合法，any 返回让
  // 若依面的既有松散写法不浮错——只做编译闭合，类型面现代化留后续专项。
  export function computed<T>(...args: any[]): any
  export function ref<T>(...args: any[]): any
  export function watch(...args: any[]): any
  export function watchEffect(...args: any[]): any
  export function onScopeDispose(...args: any[]): any
  export function toValue(...args: any[]): any
  export type Ref<T = any> = any
  export type ComputedRef<T = any> = any
  export type MaybeRefOrGetter<T = any> = any
  export type App = any
  export type VNode = any
}

// nprogress
declare module 'nprogress' {
  export interface NProgressOptions {
    minimum?: number
    template?: string
    easing?: string
    speed?: number
    trickle?: boolean
    trickleSpeed?: number
    showSpinner?: boolean
    parent?: string
    barSelector?: string
  }

  export interface NProgress {
    start(): NProgress
    set(n: number): NProgress
    inc(amount?: number): NProgress
    done(force?: boolean): NProgress
    remove(): void
    configure(options: NProgressOptions): NProgress
    status: number | null
  }

  const nprogress: NProgress
  export default nprogress
}

// js-cookie
declare module 'js-cookie' {
  export default Cookies
}

// file-saver
declare module 'file-saver' {
  export function saveAs(data: Blob | string, filename?: string, options?: any): void
  export function saveAs(data: Blob | string, filename?: string, disableAutoBOM?: boolean): void
  export default saveAs
}

// jsencrypt
declare module 'jsencrypt/bin/jsencrypt.min' {
  import JSEncrypt from 'jsencrypt'
  export default JSEncrypt
}

// axios
declare module 'axios'
// （jknow T2 试过删除此简写让真包类型生效——若依自有 .vue 面的潜在严格性错误
// 成规模浮出；src/api/knowledge 只需类型面而非真包，改走 types-http.d.ts 的
// 结构性本地类型。详见 vue 块注释）

// vue-quill
declare module '@vueup/vue-quill'

// sortablejs
declare module 'sortablejs' {
  export interface SortableEvent {
    oldIndex: number;
    newIndex: number;
  }
  
  export interface SortableOptions {
    ghostClass?: string;
    onEnd?: (evt: SortableEvent) => void;
  }
  
  export default class Sortable {
    static create(el: HTMLElement, options: SortableOptions): Sortable;
  }
}

// fuse
declare module 'fuse.js' {
  export interface FuseOptions<T> {
    keys: string[];
    threshold?: number;
    includeScore?: boolean;
    includeMatches?: boolean;
    minMatchCharLength?: number;
    shouldSort?: boolean;
    // 添加其他你需要的选项
  }
  
  export default class Fuse<T> {
    constructor(list: T[], options?: FuseOptions<T>)
    search(pattern: string): T[]
  }
}

// vuedraggable
declare module 'vuedraggable/dist/vuedraggable.common' {
  import { DefineComponent } from 'vue'
  const draggable: DefineComponent
  export default draggable
}

// vue-cropper
declare module 'vue-cropper' {
  import { DefineComponent } from 'vue'
  const VueCropper: DefineComponent
  export { VueCropper }
}

// splitpanes
declare module 'splitpanes' {
  import { DefineComponent } from 'vue'
  
  export const Splitpanes: DefineComponent
  export const Pane: DefineComponent
}
