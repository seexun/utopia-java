import { createApp } from 'vue'

// 服务端状态层（源仓 main.ts 的 QueryClientProvider 对等物）：vue-query 承担
// react-query 的职责——切库与 KB 事件流的失效面都打在它的 queryClient 上。
// 客户端是模块单例（store/knowledge/query.ts）：会话边界的清缓存拿同一实例。
import { VueQueryPlugin } from '@tanstack/vue-query'
import { queryClient } from '@/store/knowledge/query'
// 知识页面级离线图标注册表（@iconify/vue/dist/offline 的 addIcon 面；源仓在
// layout/index.vue 加载——若依壳下收口到 main.ts，页面 Icon 走 dist/offline 同一注册表）
import '@/components/knowledge/ReIcon/offlineIcon'
import ToastHost from '@/components/knowledge/ToastHost.vue'

import Cookies from 'js-cookie'

import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import zhCnLocale from 'element-plus/es/locale/lang/zh-cn'
import enLocale from 'element-plus/es/locale/lang/en'

// 知识库词条 i18n（对齐源仓 utopia-web：词典在 src/i18n/dicts，en 定结构 zh 补齐；
// locale 从 localStorage 读（src/i18n 的 detect()，键 utopia.lang，默认 zh），fallback zh）。
// 若依原生页面文本不经过 t()，此实例只服务 knowledge 页面。
import { createI18n } from 'vue-i18n'
import { lang, stringsProjection } from '@/i18n'
import { en as dictEn } from '@/i18n/dicts/en'
import { zh as dictZh } from '@/i18n/dicts/zh'

// vue-i18n 消息树叶子（对齐 @intlify 的 LocaleMessage 约定：叶子只能是 string，
// 嵌套只能是词典；stringsProjection 返回的 Record<string, unknown> 过宽，收窄后
// createI18n 的泛型推断才能成立。number 叶子运行时可用，类型面按 string 记）
type MessageTree = { [key: string]: string | MessageTree }

const i18n = createI18n({
  legacy: false,
  locale: lang,
  fallbackLocale: 'zh',
  // 函数值词条 vue-i18n 表达不了，走 S（见 src/i18n/index.ts）；这里只装字符串子集
  messages: {
    en: stringsProjection(dictEn) as MessageTree,
    zh: stringsProjection(dictZh) as MessageTree
  }
})

// 设计令牌单源（gvdata tokens.scss 原样迁入）：先于 index.scss/tailwind/业务样式，
// 对齐 gvdata main.js 的引入序（tokens 先入、主题层殿后）——静态覆盖 element 的
// --el-color-primary 色阶（#2563eb），运行时主题色阶见 store/modules/settings.ts。
import '@/style/tokens.scss'
import '@/assets/styles/index.scss' // global css（gvdata 装配序：variables→mixin→transition→element-ui→sidebar→btn→gvdata + ruoyi 殿后）
// anivia 组件层（gvdesign 5 件之一，1162 行原样迁入）：等位 gvdata main.js 的
// 引入位（index.scss 之后、tokens 之后；jknow 侧 tokens 按 T1 约束保持最先）。
// global.scss/table-style-optimized.scss 不在此装配：gvdata 中它们由
// utils/opBase.js 与 dpp 任务视图 scoped 引入（非全局），jknow 无等位消费点。
import '@/assets/styles/gvdesign/anivia.scss'
// tailwind4 入口（必须在 main.ts 引入，避免 vite 每次 hmr 重新请求整个 scss 入口）
import '@/style/tailwind.css'
// 主题层+业务样式（T2 合并后 theme.scss = gvdata 全局主题层 378 行原文 + jknow
// data-theme 段与壳对齐垫片追加；tailwind 之后，业务三件套殿后不变）
import '@/style/theme.scss'
import '@/style/utopia.scss'
import '@/style/utopia-pages.css'

import App from './App.vue'
import store from './store'
import router from './router'
import directive from './directive' // directive

// 注册指令
import plugins from './plugins' // plugins
import { download } from '@/utils/request'

// svg图标
import 'virtual:svg-icons-register'
import SvgIcon from '@/components/SvgIcon/index.vue'
import elementIcons from '@/components/SvgIcon/svgicon'

import './permission' // permission control

import { useDict } from '@/utils/dict'
import { getConfigKey } from "@/api/system/config"
import { parseTime, resetForm, addDateRange, handleTree, selectDictLabel, selectDictLabels } from '@/utils/ruoyi'

// 分页组件
import Pagination from '@/components/Pagination/index.vue'
// 自定义表格工具组件
import RightToolbar from '@/components/RightToolbar/index.vue'
// 富文本组件
import Editor from "@/components/Editor/index.vue"
// 文件上传组件
import FileUpload from "@/components/FileUpload/index.vue"
// 图片上传组件
import ImageUpload from "@/components/ImageUpload/index.vue"
// 图片预览组件
import ImagePreview from "@/components/ImagePreview/index.vue"
// 字典标签组件
import DictTag from '@/components/DictTag/index.vue'

const app = createApp(App)

// 全局方法挂载
app.config.globalProperties.useDict = useDict
app.config.globalProperties.download = download
app.config.globalProperties.parseTime = parseTime
app.config.globalProperties.resetForm = resetForm
app.config.globalProperties.handleTree = handleTree
app.config.globalProperties.addDateRange = addDateRange
app.config.globalProperties.getConfigKey = getConfigKey
app.config.globalProperties.selectDictLabel = selectDictLabel
app.config.globalProperties.selectDictLabels = selectDictLabels

// 全局组件挂载
app.component('DictTag', DictTag)
app.component('Pagination', Pagination)
app.component('FileUpload', FileUpload)
app.component('ImageUpload', ImageUpload)
app.component('ImagePreview', ImagePreview)
app.component('RightToolbar', RightToolbar)
app.component('Editor', Editor)

app.use(router)
app.use(store)
app.use(i18n)
// 知识页面族的服务端状态层（views 里不带显式 client 的 useQuery 走它）
app.use(VueQueryPlugin, { queryClient })
app.use(plugins)
app.use(elementIcons)
app.component('svg-icon', SvgIcon)

directive(app)

// 使用element-plus 并且设置全局的大小
// EP 内建文案（分页/空态/日期面板等）随界面语言：启动时按 i18n 当前 locale
// 取值——user-menu 切语言是整页 reload（源语义），reload 后这里取到新值即闭环，
// 无需 watch。词典语言默认 zh（src/i18n 的 detect），非 en 一律回落 zh-cn 包。
// @ts-ignore
app.use(ElementPlus, {
  locale: i18n.global.locale.value === 'en' ? enLocale : zhCnLocale,
  // 支持 large、default、small
  size: Cookies.get('size') || 'default'
})

app.mount('#app')

// 知识域 toast 宿主（源仓 App.vue 同位）：独立微应用挂到 body 上的专属容器。
// 不并进 App.vue——router-view 与兄弟节点并存（fragment 根）会让 RouterView
// 的锚点更新静默失效（T3 冒烟实证：kb 页渲染成空 #app）；toast 总线
// （domain/toast）是模块单例，第二实例只做渲染面，两侧同源。
const toastHostEl = document.createElement('div')
document.body.appendChild(toastHostEl)
createApp(ToastHost).mount(toastHostEl)
