import { createWebHistory, createRouter } from 'vue-router'
/* Layout */
import Layout from '@/layout/index.vue'
import { resolveKnowledgeMenuPath } from '@/composables/useKbContext'

/**
 * Note: 路由配置项
 *
 * hidden: true                     // 当设置 true 的时候该路由不会再侧边栏出现 如401，login等页面，或者如一些编辑页面/edit/1
 * alwaysShow: true                 // 当你一个路由下面的 children 声明的路由大于1个时，自动会变成嵌套的模式--如组件页面
 *                                  // 只有一个时，会将那个子路由当做根路由显示在侧边栏--如引导页面
 *                                  // 若你想不管路由下面的 children 声明的个数都显示你的根路由
 *                                  // 你可以设置 alwaysShow: true，这样它就会忽略之前定义的规则，一直显示根路由
 * redirect: noRedirect             // 当设置 noRedirect 的时候该路由在面包屑导航中不可被点击
 * name:'router-name'               // 设定路由的名字，一定要填写不然使用<keep-alive>时会出现各种问题
 * query: '{"id": 1, "name": "ry"}' // 访问路由的默认传递参数
 * roles: ['admin', 'common']       // 访问路由的角色权限
 * permissions: ['a:a:a', 'b:b:b']  // 访问路由的菜单权限
 * meta : {
    noCache: true                   // 如果设置为true，则不会被 <keep-alive> 缓存(默认 false)
    title: 'title'                  // 设置该路由在侧边栏和面包屑中展示的名字
    icon: 'svg-name'                // 设置该路由的图标，对应路径src/assets/icons/svg
    breadcrumb: false               // 如果设置为false，则不会在breadcrumb面包屑中显示
    activeMenu: '/system/user'      // 当路由设置了该属性，则会高亮相对应的侧边栏。
  }
 */

// 公共路由
export const constantRoutes = [
  {
    path: '/redirect',
    component: Layout,
    hidden: true,
    children: [
      {
        path: '/redirect/:path(.*)',
        component: () => import('@/views/redirect/index.vue')
      }
    ]
  },
  {
    path: '/login',
    component: () => import('@/views/login.vue'),
    hidden: true
  },
  {
    path: '/register',
    component: () => import('@/views/register.vue'),
    hidden: true
  },
  {
    path: "/:pathMatch(.*)*",
    component: () => import('@/views/error/404.vue'),
    hidden: true
  },
  // ---- 知识图谱 kb 动态路由（P2 任务 3，V0100 component 前缀 jknow/knowledge/<域>/index）----
  // kb/account 若依壳内联（规格 2026-11-18-kb-inline-layout）：/kb/:kbId/* 原为
  // constantRoutes 顶级独立整页——菜单点入后 kb-entry 解析默认库 replace 到
  // /kb/:id/:page，跳出若依壳全屏渲染。现包为 Layout children（path 相对化，
  // 直达 URL 不变，渲染链 Layout → 页面）；hidden 不进菜单（菜单开启在
  // V0102/T4：8 个 C 页统一指 kb-entry 入口组件），登录校验交若依
  // permission.ts 的 beforeEach。/kb 自身的 → /library 重定向收进 Layout 记录
  // （redirect 仅在命中 /kb 本身时触发，children 照常深链匹配；避免第二条同名
  // 顶级 /kb 记录按注册序遮蔽）。
  {
    path: "/kb",
    component: Layout,
    redirect: "/library",
    hidden: true,
    children: [
      {
        path: ":kbId/graph",
        component: () => import("@/views/jknow/knowledge/graph/index.vue"),
        hidden: true
      },
      {
        path: ":kbId/search",
        component: () => import("@/views/jknow/knowledge/search/index.vue"),
        hidden: true
      },
      {
        path: ":kbId/library",
        component: () => import("@/views/jknow/knowledge/library/index.vue"),
        hidden: true
      },
      {
        path: ":kbId/doc/:docId",
        component: () => import("@/views/jknow/knowledge/doc-viewer/index.vue"),
        hidden: true
      },
      {
        path: ":kbId/ontology",
        component: () => import("@/views/jknow/knowledge/ontology/index.vue"),
        hidden: true
      },
      {
        path: ":kbId/review",
        component: () => import("@/views/jknow/knowledge/review/index.vue"),
        hidden: true
      },
      {
        path: ":kbId/mappings",
        component: () => import("@/views/jknow/knowledge/mappings/index.vue"),
        hidden: true
      },
      {
        path: ":kbId/settings",
        component: () => import("@/views/jknow/knowledge/kb-settings/index.vue"),
        hidden: true
      },
      {
        path: ":kbId/chat/:conversationId?",
        component: () => import("@/views/jknow/knowledge/chat/index.vue"),
        hidden: true
      },
      {
        path: ":kbId/docs",
        component: () => import("@/views/jknow/knowledge/docs/index.vue"),
        hidden: true
      }
    ]
  },
  // 旧路径接管（源仓 router/modules/utopia.ts 的 LegacyX 族）：/graph /search 这类
  // 不带库的地址由 KbRedirect 兜底——等 useKbContext 解析出库再 replace 到
  // /kb/:id/:page；/kb 的 redirect 目标 /library 也落在这组上。
  {
    path: "/graph",
    component: () => import("@/components/knowledge/kb-redirect/index.vue"),
    hidden: true
  },
  {
    path: "/search",
    component: () => import("@/components/knowledge/kb-redirect/index.vue"),
    hidden: true
  },
  {
    path: "/chat",
    component: () => import("@/components/knowledge/kb-redirect/index.vue"),
    hidden: true
  },
  {
    path: "/library",
    component: () => import("@/components/knowledge/kb-redirect/index.vue"),
    hidden: true
  },
  {
    path: "/ontology",
    component: () => import("@/components/knowledge/kb-redirect/index.vue"),
    hidden: true
  },
  {
    path: "/mappings",
    component: () => import("@/components/knowledge/kb-redirect/index.vue"),
    hidden: true
  },
  {
    path: "/review",
    component: () => import("@/components/knowledge/kb-redirect/index.vue"),
    hidden: true
  },
  {
    path: "/kb-settings",
    component: () => import("@/components/knowledge/kb-redirect/index.vue"),
    hidden: true
  },
  // 内置文档页（源仓 remaining.ts：/docs → /docs/ingest 重定向 + slug 页；
  // docs 页内导航目标在 /docs/:slug，/kb/:kbId/docs 入口渲染缺省首页）
  {
    path: "/docs",
    redirect: "/docs/ingest",
    hidden: true
  },
  {
    path: "/docs/:slug",
    component: () => import("@/views/jknow/knowledge/docs/index.vue"),
    hidden: true
  },
  // 账户层（源仓 router/modules/remaining.ts 的 AccountShell 族，T5 收口）：
  // kb 页顶栏 user-menu（个人资料/我的库/部署管理）与「新建知识库」深链
  // （/account/kbs?create=true）的链接目标。kb/account 若依壳内联
  // （规格 2026-11-18-kb-inline-layout）：整段挂进 Layout children，shell.vue
  // 去壳化（自带的全屏侧栏/顶栏退役，只留页内 tab 导航 + router-view），高度链
  // 由 AppMain 承载。/admin 择「并入 account 容器」而非 Layout 直挂（规格允许
  // 二选一、实施记录）：管理页子页签（?tab=）导航一直住在 shell 上，直挂会丢
  // 这行导航，且 /account 裸址需要 redirect 子记录补 /account/profile 落点。
  // 源仓 /admin 的 meta.roles:['admin'] 守卫 403 闸不随迁——入口可见性由 tab
  // 仅对 me.is_admin 渲染兜底，后端面另有 @PreAuthorize 粗粒度权限闸。
  {
    path: "/account",
    component: Layout,
    hidden: true,
    children: [
      {
        path: "",
        redirect: "/account/profile"
      },
      {
        path: "/account",
        name: "Account",
        component: () => import("@/views/jknow/knowledge/account/shell.vue"),
        hidden: true,
        children: [
          {
            path: "profile",
            name: "AccountProfile",
            component: () => import("@/views/jknow/knowledge/account/profile.vue"),
            hidden: true
          },
          {
            path: "kbs",
            name: "AccountKbs",
            component: () => import("@/views/jknow/knowledge/account/my-kbs.vue"),
            hidden: true
          },
          {
            path: "tokens",
            name: "AccountTokens",
            component: () => import("@/views/jknow/knowledge/account/tokens.vue"),
            hidden: true
          },
          {
            path: "/admin",
            name: "Admin",
            component: () => import("@/views/jknow/knowledge/admin/index.vue"),
            hidden: true
          }
        ]
      }
    ]
  },
  {
    path: '/401',
    component: () => import('@/views/error/401.vue'),
    hidden: true
  },
  {
    path: '',
    component: Layout,
    redirect: '/index',
    children: [
      {
        path: '/index',
        component: () => import('@/views/index.vue'),
        name: 'Index',
        meta: { title: '首页', icon: 'dashboard', affix: true }
      }
    ]
  },
  {
    path: '/lock',
    component: () => import('@/views/lock.vue'),
    hidden: true,
    meta: { title: '锁定屏幕' }
  },
  {
    path: '/user',
    component: Layout,
    hidden: true,
    redirect: 'noredirect',
    children: [
      {
        path: 'profile/:activeTab?',
        component: () => import('@/views/system/user/profile/index.vue'),
        name: 'Profile',
        meta: { title: '个人中心', icon: 'user' }
      }
    ]
  }
]

// 动态路由，基于用户权限动态去加载
export const dynamicRoutes = [
  {
    path: '/system/user-auth',
    component: Layout,
    hidden: true,
    permissions: ['system:user:edit'],
    children: [
      {
        path: 'role/:userId(\\d+)',
        component: () => import('@/views/system/user/authRole.vue'),
        name: 'AuthRole',
        meta: { title: '分配角色', activeMenu: '/system/user' }
      }
    ]
  },
  {
    path: '/system/role-auth',
    component: Layout,
    hidden: true,
    permissions: ['system:role:edit'],
    children: [
      {
        path: 'user/:roleId(\\d+)',
        component: () => import('@/views/system/role/authUser.vue'),
        name: 'AuthUser',
        meta: { title: '分配用户', activeMenu: '/system/role' }
      }
    ]
  },
  {
    path: '/system/dict-data',
    component: Layout,
    hidden: true,
    permissions: ['system:dict:list'],
    children: [
      {
        path: 'index/:dictId(\\d+)',
        component: () => import('@/views/system/dict/data.vue'),
        name: 'Data',
        meta: { title: '字典数据', activeMenu: '/system/dict' }
      }
    ]
  },
  {
    path: '/monitor/job-log',
    component: Layout,
    hidden: true,
    permissions: ['monitor:job:list'],
    children: [
      {
        path: 'index/:jobId(\\d+)',
        component: () => import('@/views/monitor/job/log.vue'),
        name: 'JobLog',
        meta: { title: '调度日志', activeMenu: '/monitor/job' }
      }
    ]
  },
  {
    path: '/tool/gen-edit',
    component: Layout,
    hidden: true,
    permissions: ['tool:gen:edit'],
    children: [
      {
        path: 'index/:tableId(\\d+)',
        component: () => import('@/views/tool/gen/editTable.vue'),
        name: 'GenEdit',
        meta: { title: '修改生成配置', activeMenu: '/tool/gen' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes: constantRoutes,
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    return { top: 0 }
  },
})

// kb/account 若依壳内联（规格 2026-11-18-kb-inline-layout）的菜单链守卫：/kb/:kbId/*
// 挂进 Layout children 后，主区 AppMain 的 <Transition mode="out-in"> 会把
// 「kb-entry 挂载 → replace /kb/:id/:page」的跳板式二次换子变成空主区（进场
// 丢失，实测复现；resolveLegacyKbPath 的 W1 结论在菜单链路同样成立）。这里在
// 守卫层 await 解析（queryClient.fetchQuery 与组件侧同缓存键）直接重定向——
// kb 页组件只挂载一次，过渡只走一轮；解析不出回落 kb-entry 组件原逻辑
// （等待页/8s 引导卡），workspace 菜单（原地渲染）不经此守卫。
router.beforeEach(async to => {
  if (to.path.startsWith('/knowledge/')) {
    const target = await resolveKnowledgeMenuPath(to.path)
    if (target) {
      return { path: target, query: to.query, replace: true }
    }
  }
  return true
})

export default router
