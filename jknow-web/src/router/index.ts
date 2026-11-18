import { createWebHistory, createRouter } from 'vue-router'
/* Layout */
import Layout from '@/layout/index.vue'

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
  // hidden 不进菜单（菜单开启在 V0102/T4：若依 getRouters 的后端菜单树）；
  // 页面渲染为独立整页（utopia 壳的顶栏/侧栏职责由页面内组件承担——
  // sources-rail/kb-switcher），登录校验交若依 permission.ts 的 beforeEach。
  {
    path: "/kb/:kbId/graph",
    component: () => import("@/views/jknow/knowledge/graph/index.vue"),
    hidden: true
  },
  {
    path: "/kb/:kbId/search",
    component: () => import("@/views/jknow/knowledge/search/index.vue"),
    hidden: true
  },
  {
    path: "/kb/:kbId/library",
    component: () => import("@/views/jknow/knowledge/library/index.vue"),
    hidden: true
  },
  {
    path: "/kb/:kbId/doc/:docId",
    component: () => import("@/views/jknow/knowledge/doc-viewer/index.vue"),
    hidden: true
  },
  {
    path: "/kb/:kbId/ontology",
    component: () => import("@/views/jknow/knowledge/ontology/index.vue"),
    hidden: true
  },
  {
    path: "/kb/:kbId/review",
    component: () => import("@/views/jknow/knowledge/review/index.vue"),
    hidden: true
  },
  {
    path: "/kb/:kbId/mappings",
    component: () => import("@/views/jknow/knowledge/mappings/index.vue"),
    hidden: true
  },
  {
    path: "/kb/:kbId/settings",
    component: () => import("@/views/jknow/knowledge/kb-settings/index.vue"),
    hidden: true
  },
  {
    path: "/kb/:kbId/chat/:conversationId?",
    component: () => import("@/views/jknow/knowledge/chat/index.vue"),
    hidden: true
  },
  {
    path: "/kb/:kbId/docs",
    component: () => import("@/views/jknow/knowledge/docs/index.vue"),
    hidden: true
  },
  {
    path: "/kb",
    redirect: "/library",
    hidden: true
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
  // kb 页顶栏 user-menu（个人资料/我的库/部署管理）与 kb-switcher「新建知识库」
  // （/account/kbs?create=true）的链接目标——T3 只迁了 account/admin 页面族未
  // 注册路由，链接直落 404（T4 遗留交接项，走查实测确认后补）。独立整页
  // （account/shell.vue 自带左侧导航壳），hidden 不进若依菜单；子项写绝对路径
  // （源仓同构，vue-router 4 根路径子项仍挂壳渲染）。源仓 /admin 的
  // meta.roles:['admin'] 守卫 403 闸不随迁——入口可见性由 user-menu 仅对
  // is_admin 渲染兜底，后端面另有 @PreAuthorize 粗粒度权限闸（T4 挂载清单）。
  {
    path: "/account",
    component: () => import("@/views/jknow/knowledge/account/shell.vue"),
    hidden: true,
    children: [
      {
        path: "/account",
        name: "Account",
        component: () => import("@/views/jknow/knowledge/account/profile.vue"),
        hidden: true
      },
      {
        path: "/account/kbs",
        name: "AccountKbs",
        component: () => import("@/views/jknow/knowledge/account/my-kbs.vue"),
        hidden: true
      },
      {
        path: "/account/tokens",
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

export default router
