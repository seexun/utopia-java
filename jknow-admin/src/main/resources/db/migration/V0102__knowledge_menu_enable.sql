-- V0102__knowledge_menu_enable：P2 前端落地——菜单开启 + component 对齐 T3 实搬页面
-- 开启：V0100 的 25 行（2100 目录 + 8 个 C 页 + 16 个 F 按钮）visible '1'(隐藏) → '0'(显示)。
-- component 校正（T3 实况，全部 8 个 C 页统一改指菜单入口组件 kb-entry）：
--   V0100 写的 jknow/knowledge/{workspace|ingest|settings}/index 与 T3 实搬页面族
--   （account/my-kbs、library、kb-settings）三处不符；其余 5 页文件虽对齐，但 kb 页
--   kbId 严格取自 /kb/:kbId 路由参数（useKbScope），菜单路由 /knowledge/<页> 上无参
--   ＝全部查询 disabled 的死页，my-kbs 又依赖 localStorage 记忆的 workspaceId（冷登录
--   为空）。故 8 页统一指 kb-entry：kb 页模式解析默认库后 replace /kb/{id}/{page}
--   （ingest→library 文库页），workspace 模式引导工作区后原地渲染库列表页。
-- perms 与 V0100 一致（F 按钮 16 项 perms 即后端 @PreAuthorize 挂载面），零改动。

UPDATE sys_menu SET visible = '0' WHERE menu_id BETWEEN 2100 AND 2199;

UPDATE sys_menu SET component = 'jknow/knowledge/kb-entry/index'
 WHERE menu_id BETWEEN 2110 AND 2180 AND menu_type = 'C';
