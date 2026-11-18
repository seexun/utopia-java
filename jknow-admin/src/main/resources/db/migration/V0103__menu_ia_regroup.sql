-- V0103__menu_ia_regroup：菜单对齐原程序三分组制（规格 docs/superpowers/specs/2026-11-18-menu-ia-design.md）
-- 三分组目录（M）+ 业务页（C）+ 按钮权限（F）；2120 图谱保留改造（menu_id 不变，重挂工作台下）。
-- 行数账目：V0000 种子 85 + V0100 的 25 = 110；
--   V0103 变动 = -1（menu_id=4 若依官网外链退役）
--              -8（旧目录与旧 C：2100 目录 / 2110 工作空间 / 2130 本体 / 2140 检索 / 2150 对话
--                  / 2160 摄取 / 2170 审查 / 2180 设置——C 行职责由新 id 承接）
--              -2（工作空间 F 2111/2112 定谳删除：新树无工作空间 C 页，按钮行无义；
--                  perms knowledge:workspace:* 保留在 @PreAuthorize 面，仅菜单行退役）
--              +10（新 M×3：2200/2210/2220 + 新 C×7：2202/2203/2211/2212/2213/2214/2221；
--                  2120 图谱为保留改造 UPDATE，不计新增）
--   = 110 - 11 + 10 = 109（MigrationTests MENU_COUNT 实测校准值）。
-- 区间账目：2100-2299 = 25 行 = 3M + 8C（2120 保留 + 新 7）+ 14F（原 16 减工作空间 2）。
-- F 行 perms 一字不动（仅 UPDATE parent_id 重挂）；图标均取自若依自带 src/assets/icons/svg/ 现有集。
-- 列序模板 = V0000 的 sys_menu insert 行（20 列：menu_id, menu_name, parent_id, order_num, path,
-- component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon,
-- create_by, create_time, update_by, update_time, remark）。

-- 1) 退役行
DELETE FROM sys_menu WHERE menu_id = 4;                          -- 若依官网外链
DELETE FROM sys_menu WHERE menu_id IN (2100,2110,2130,2140,2150,2160,2170,2180); -- 旧目录+旧 C（2120 图谱保留改造）
DELETE FROM sys_menu WHERE menu_id IN (2111,2112);               -- 工作空间 F（定谳删除，见头注）

-- 2) 2120 图谱保留改造：parent 2100→2200、order 2→1、icon #→chart
--    （menu_name/path/perms/component(kb-entry)/visible('0') 已是目标值——V0100/V0102 既定）
UPDATE sys_menu SET parent_id = 2200, order_num = 1, icon = 'chart' WHERE menu_id = 2120;

-- 3) 新 M×3 分组目录（工作台/知识管理/配置，order 1/2/3，perms 空）
insert into sys_menu values('2200', '工作台',   '0', '1', 'workbench',      null, '', '', 1, 0, 'M', '0', '0', '', 'dashboard',    'admin', now(), '', null, '知识工作台');
insert into sys_menu values('2210', '知识管理', '0', '2', 'knowledge-mgmt', null, '', '', 1, 0, 'M', '0', '0', '', 'education', 'admin', now(), '', null, '');
insert into sys_menu values('2220', '配置',     '0', '3', 'config',         null, '', '', 1, 0, 'M', '0', '0', '', 'build',       'admin', now(), '', null, '');

-- 4) 新 C×7（component 一律 kb-entry 入口组件；perms 按规格表；数据映射 2214 复用 ontology 域）
insert into sys_menu values('2202', '检索',     '2200', '2', 'search',   'jknow/knowledge/kb-entry/index', '', '', 1, 0, 'C', '0', '0', 'knowledge:search:list',   'search',        'admin', now(), '', null, '');
insert into sys_menu values('2203', '问答',     '2200', '3', 'chat',     'jknow/knowledge/kb-entry/index', '', '', 1, 0, 'C', '0', '0', 'knowledge:chat:list',     'message',       'admin', now(), '', null, '');
insert into sys_menu values('2211', '文库',     '2210', '1', 'library',  'jknow/knowledge/kb-entry/index', '', '', 1, 0, 'C', '0', '0', 'knowledge:ingest:list',   'documentation', 'admin', now(), '', null, '');
insert into sys_menu values('2212', '审查',     '2210', '2', 'review',   'jknow/knowledge/kb-entry/index', '', '', 1, 0, 'C', '0', '0', 'knowledge:review:list',   'log',           'admin', now(), '', null, '');
insert into sys_menu values('2213', '本体',     '2210', '3', 'ontology', 'jknow/knowledge/kb-entry/index', '', '', 1, 0, 'C', '0', '0', 'knowledge:ontology:list', 'tree',          'admin', now(), '', null, '');
insert into sys_menu values('2214', '数据映射', '2210', '4', 'mappings', 'jknow/knowledge/kb-entry/index', '', '', 1, 0, 'C', '0', '0', 'knowledge:ontology:list', 'form',          'admin', now(), '', null, '');
insert into sys_menu values('2221', '库设置',   '2220', '1', 'settings', 'jknow/knowledge/kb-entry/index', '', '', 1, 0, 'C', '0', '0', 'knowledge:settings:list', 'edit',          'admin', now(), '', null, '');

-- 5) F 14 行重挂（perms 一字不动；图谱 F 原挂 2120 不变，列出以自证账目）
UPDATE sys_menu SET parent_id = 2120 WHERE menu_id IN (2121,2122);  -- 图谱 F（no-op，自证）
UPDATE sys_menu SET parent_id = 2213 WHERE menu_id IN (2131,2132);  -- 本体 F（原 2130 删→新 2213）
UPDATE sys_menu SET parent_id = 2202 WHERE menu_id IN (2141,2142);  -- 检索 F（原 2140 删→新 2202）
UPDATE sys_menu SET parent_id = 2203 WHERE menu_id IN (2151,2152);  -- 问答 F（原 2150 删→新 2203）
UPDATE sys_menu SET parent_id = 2211 WHERE menu_id IN (2161,2162);  -- 文库 F（原 2160 摄取删→新 2211）
UPDATE sys_menu SET parent_id = 2212 WHERE menu_id IN (2171,2172);  -- 审查 F（原 2170 删→新 2212）
UPDATE sys_menu SET parent_id = 2221 WHERE menu_id IN (2181,2182);  -- 库设置 F（原 2180 删→新 2221）
