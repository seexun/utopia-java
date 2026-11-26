<template>
  <el-breadcrumb class="app-breadcrumb" separator="/">
    <transition-group name="breadcrumb">
      <el-breadcrumb-item v-for="(item, index) in levelList" :key="item.path">
        <span v-if="item.redirect === 'noRedirect' || index == levelList.length - 1" class="no-redirect">{{ item.meta.title }}</span>
        <a v-else @click.prevent="handleLink(item)">{{ item.meta.title }}</a>
      </el-breadcrumb-item>
    </transition-group>
  </el-breadcrumb>
</template>

<script setup lang="ts">
import usePermissionStore from '@/store/modules/permission'

const route = useRoute()
const router = useRouter()
const permissionStore = usePermissionStore()
const levelList = ref<any[]>([])

function getBreadcrumb(): void {
  // only show routes with meta.title
  let matched: any[] = []
  const pathNum = findPathNum(route.path)
  /* KB 工作页（主题色渗透批 2026-11-26）：/kb/:kbId/* 是带参动态路由，
     走下方多级菜单字符串匹配分支必失配（':kbId' 段对不上 UUID），层级被吞成
     单级"首页"——直接用 route.matched 取链，末级=当前页（蓝字锚） */
  if (route.path.startsWith("/kb/")) {
    matched = route.matched.filter((item: any) => item.meta && item.meta.title)
  } else if (pathNum > 2) {
    const reg = /\/\w+/gi
    const pathList = route.path.match(reg)?.map((item: string, index: number) => {
      if (index !== 0) item = item.slice(1)
      return item
    }) || []
    getMatched(pathList, permissionStore.defaultRoutes, matched)
  } else {
    matched = route.matched.filter((item: any) => item.meta && item.meta.title)
  }
  // 判断是否为首页（首页语义统一 menu-ia T2：合成屑指 '/'——直达当前库图谱，原 /index 落若依默认首页）
  if (!isDashboard(matched[0])) {
    matched = [{ path: "/", meta: { title: "首页" } }].concat(matched)
  }
  levelList.value = matched.filter(item => item.meta && item.meta.title && (item.meta.breadcrumb as boolean) !== false)
}
function findPathNum(str: string, char = "/"): number {
  let index = str.indexOf(char)
  let num = 0
  while (index !== -1) {
    num++
    index = str.indexOf(char, index + 1)
  }
  return num
}
function getMatched(pathList: string[], routeList: any[], matched: any[]): void {
  let data = routeList.find(item => item.path == pathList[0] || (item.name + '').toLowerCase() == pathList[0])
  if (data) {
    matched.push(data)
    if (data.children && pathList.length) {
      pathList.shift()
      getMatched(pathList, data.children, matched)
    }
  }
}
function isDashboard(route?: any): boolean {
  const name = route && route.name
  if (!name) {
    return false
  }
  return String(name).trim() === 'Index'
}
function handleLink(item: any): void {
  const { redirect, path } = item
  if (redirect) {
    router.push(redirect as string)
    return
  }
  router.push(path)
}

watchEffect(() => {
  // if you go to the redirect page, do not update the breadcrumbs
  if (route.path.startsWith('/redirect/')) {
    return
  }
  getBreadcrumb()
})
getBreadcrumb()
</script>

<style lang='scss' scoped>
.app-breadcrumb.el-breadcrumb {
  display: inline-block;
  font-size: 14px;
  line-height: 50px;

  .no-redirect {
    /* 主题色渗透②（治"白茫茫"走查）：面包屑当前级蓝字加粗——顶栏的层级锚点
       （灵云数智中台"数据标准 / 标准数据元"同款形态） */
    color: var(--current-color, #2563eb);
    font-weight: 600;
    cursor: text;
  }
}
</style>