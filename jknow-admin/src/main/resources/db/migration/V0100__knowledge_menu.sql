-- V0100__knowledge_menu：图谱业务菜单与权限标识（页面 P2 落地后再开，全部 visible='1' 隐藏）
-- 列序模板 = V0000 的 sys_menu insert 行（menu_id, menu_name, parent_id, order_num, path,
-- component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon,
-- create_by, create_time, update_by, update_time, remark）。
-- 顶级 2100 为目录（M，component null，同 V0000 menu_id=1 形状）；8 个子页 2110-2180（C）；
-- 每页 list/edit 两个 F 级按钮（2111/2112 … 2181/2182）。perms 即接口鉴权标识。

insert into sys_menu values('2100', '知识图谱', '0', '6', 'knowledge', null, '', '', 1, 0, 'M', '1', '0', '', 'graph', 'admin', now(), '', null, '知识图谱平台');

insert into sys_menu values('2110', '工作空间', '2100', '1', 'workspace', 'jknow/knowledge/workspace/index', '', '', 1, 0, 'C', '1', '0', 'knowledge:workspace:list', '#', 'admin', now(), '', null, '工作空间菜单');
insert into sys_menu values('2120', '图谱',     '2100', '2', 'graph',     'jknow/knowledge/graph/index',     '', '', 1, 0, 'C', '1', '0', 'knowledge:graph:list',     '#', 'admin', now(), '', null, '图谱菜单');
insert into sys_menu values('2130', '本体',     '2100', '3', 'ontology',  'jknow/knowledge/ontology/index',  '', '', 1, 0, 'C', '1', '0', 'knowledge:ontology:list',  '#', 'admin', now(), '', null, '本体菜单');
insert into sys_menu values('2140', '检索',     '2100', '4', 'search',    'jknow/knowledge/search/index',    '', '', 1, 0, 'C', '1', '0', 'knowledge:search:list',    '#', 'admin', now(), '', null, '检索菜单');
insert into sys_menu values('2150', '对话',     '2100', '5', 'chat',      'jknow/knowledge/chat/index',      '', '', 1, 0, 'C', '1', '0', 'knowledge:chat:list',      '#', 'admin', now(), '', null, '对话菜单');
insert into sys_menu values('2160', '摄取',     '2100', '6', 'ingest',    'jknow/knowledge/ingest/index',    '', '', 1, 0, 'C', '1', '0', 'knowledge:ingest:list',    '#', 'admin', now(), '', null, '摄取菜单');
insert into sys_menu values('2170', '审查',     '2100', '7', 'review',    'jknow/knowledge/review/index',    '', '', 1, 0, 'C', '1', '0', 'knowledge:review:list',    '#', 'admin', now(), '', null, '审查菜单');
insert into sys_menu values('2180', '设置',     '2100', '8', 'settings',  'jknow/knowledge/settings/index',  '', '', 1, 0, 'C', '1', '0', 'knowledge:settings:list',  '#', 'admin', now(), '', null, '设置菜单');

insert into sys_menu values('2111', '工作空间列表', '2110', '1', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:workspace:list', '#', 'admin', now(), '', null, '');
insert into sys_menu values('2112', '工作空间编辑', '2110', '2', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:workspace:edit', '#', 'admin', now(), '', null, '');
insert into sys_menu values('2121', '图谱列表',     '2120', '1', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:graph:list',     '#', 'admin', now(), '', null, '');
insert into sys_menu values('2122', '图谱编辑',     '2120', '2', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:graph:edit',     '#', 'admin', now(), '', null, '');
insert into sys_menu values('2131', '本体列表',     '2130', '1', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:ontology:list',  '#', 'admin', now(), '', null, '');
insert into sys_menu values('2132', '本体编辑',     '2130', '2', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:ontology:edit',  '#', 'admin', now(), '', null, '');
insert into sys_menu values('2141', '检索列表',     '2140', '1', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:search:list',    '#', 'admin', now(), '', null, '');
insert into sys_menu values('2142', '检索编辑',     '2140', '2', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:search:edit',    '#', 'admin', now(), '', null, '');
insert into sys_menu values('2151', '对话列表',     '2150', '1', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:chat:list',      '#', 'admin', now(), '', null, '');
insert into sys_menu values('2152', '对话编辑',     '2150', '2', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:chat:edit',      '#', 'admin', now(), '', null, '');
insert into sys_menu values('2161', '摄取列表',     '2160', '1', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:ingest:list',    '#', 'admin', now(), '', null, '');
insert into sys_menu values('2162', '摄取编辑',     '2160', '2', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:ingest:edit',    '#', 'admin', now(), '', null, '');
insert into sys_menu values('2171', '审查列表',     '2170', '1', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:review:list',    '#', 'admin', now(), '', null, '');
insert into sys_menu values('2172', '审查编辑',     '2170', '2', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:review:edit',    '#', 'admin', now(), '', null, '');
insert into sys_menu values('2181', '设置列表',     '2180', '1', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:settings:list',  '#', 'admin', now(), '', null, '');
insert into sys_menu values('2182', '设置编辑',     '2180', '2', '#', '', '', '', 1, 0, 'F', '1', '0', 'knowledge:settings:edit',  '#', 'admin', now(), '', null, '');
