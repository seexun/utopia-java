// 侧栏菜单源（对照旧仓 Shell.tsx 的 MENU——规格 §4.1：8 个 KB 页面按
// 「工作台 / 知识管理 / 配置」分组，顺序沿用旧 TABS 的信息架构：图谱是门面，
// 本体与数据映射相邻）。
//
// **菜单数据源是这里，不是路由表**：路由记录是参数化的 `/kb/:kbId/*`（vue-router
// 要拿参数匹配），菜单条目必须在运行时换成实 id 才点得动。pure-admin 的菜单
// 管线（handleWholeMenus → filterTree → filterNoPermissionTree → el-menu）保持
// 原样，本函数在 initRouter 时把 KB 作用域菜单喂进去；库路由的路由模块本身
// showLink:false（不进 pure-admin 默认菜单）。
//
// **写菜单随角色显隐**（W1 验收口径）：配置组（库设置）只在可写库出现——
// contract0 对只读库（General viewer）没有写菜单，对可写库有。my_role 在
// kb 详情接口里，详情没回来时缺省放行（与 useKbRole 的口径一致）。
// 分组节点是纯展示节点（el-sub-menu 常开渲染），path 是伪路径，永不匹配路由。
import { S } from "@/i18n";

export type UtopiaMenuItem = {
  path: string;
  meta: {
    title: string;
    icon?: string;
    showLink?: boolean;
    /** 单子分组不拍平（组头保留）——pure-admin SidebarItem 读同名 meta */
    showParent?: boolean;
  };
  children?: UtopiaMenuItem[];
  /** pure-admin 菜单树的层级字段（utils/tree.ts buildHierarchyTree 的形状）。
   *  本项目的 initRouter 把 fresh 对象直接喂 handleWholeMenus（filterTree 即
   *  cloneDeep），不会经过 buildHierarchyTree——缺这些字段时 SidebarItem 折叠
   *  分支的 `item.parentId === null` 判不定，折叠态组头文字收不起来（只截成
   *  「工…」），所以数据侧自己补齐。 */
  id?: number;
  parentId?: number | null;
  pathList?: number[];
};

export function utopiaMenus(
  kbId: string,
  canWrite: boolean
): UtopiaMenuItem[] {
  // kbId 还没解析出来（冷启动极早期）时不渲染菜单，等 KbScope 载入后重组
  if (!kbId) return [];
  const groups: { key: string; items: UtopiaMenuItem[] }[] = [
    {
      key: "workbench",
      items: [
        {
          path: `/kb/${kbId}/graph`,
          meta: { title: S.nav.graph as string, icon: "utopia/graph" }
        },
        {
          path: `/kb/${kbId}/search`,
          meta: { title: S.nav.search as string, icon: "utopia/search" }
        },
        {
          path: `/kb/${kbId}/chat`,
          meta: { title: S.nav.ask as string, icon: "utopia/chat" }
        }
      ]
    },
    {
      key: "knowledge",
      items: [
        {
          path: `/kb/${kbId}/library`,
          meta: { title: S.nav.library as string, icon: "utopia/library" }
        },
        {
          path: `/kb/${kbId}/review`,
          meta: { title: S.review.title as string, icon: "utopia/review" }
        },
        {
          path: `/kb/${kbId}/ontology`,
          meta: { title: S.ontology.title as string, icon: "utopia/ontology" }
        },
        {
          path: `/kb/${kbId}/mappings`,
          meta: { title: S.mapping.title as string, icon: "utopia/mappings" }
        }
      ]
    }
  ];
  // 「配置」组（库设置）：旧壳的静态 MENU 对所有角色可见（含 viewer）——菜单对齐
  // 旧仓 Shell.tsx（D1 裁定：菜单级不设门槛；settings 页内部本就有只读呈现，
  // 写操作另有 useKbRole 闸 + 后端 403 兜底双保险）。
  // showParent: true——pure-admin 对单子分组默认拍平（子项顶替组名直出，
  // 组头与子项图标都不渲染），旧壳是「配置」组头 + 设置行，钉回组形。
  // （canWrite 参数保留：失效面/未来按角色的其它分组仍可用。）
  void canWrite;
  groups.push({
    key: "config",
    items: [
      {
        path: `/kb/${kbId}/settings`,
        meta: {
          title: S.nav.settings as string,
          icon: "utopia/settings",
          showParent: true
        }
      }
    ]
  });
  // 组头图标只在折叠态出现（展开态组头是旧壳的纯文字标签，图标由 utopia.scss
  // 藏起）——折叠态 SidebarItem 靠 meta.icon + parentId===null 把组头文字收起、
  // 只留图标并弹出子项 popper。
  const groupIcons: Record<string, string> = {
    workbench: "utopia/group-workbench",
    knowledge: "utopia/group-knowledge",
    config: "utopia/group-config"
  };
  return groups.map((g, gi) => ({
    path: `/kb-group-${g.key}`,
    meta: { title: groupTitle(g.key), icon: groupIcons[g.key] },
    id: gi,
    parentId: null,
    pathList: [gi],
    children: g.items.map((it, ci) => ({
      ...it,
      id: ci,
      parentId: gi,
      pathList: [gi, ci]
    }))
  }));
}

function groupTitle(key: string): string {
  if (key === "workbench") return S.nav.groupWorkbench as string;
  if (key === "knowledge") return S.nav.groupKnowledge as string;
  return S.nav.groupConfig as string;
}
