-- V0105__role_menu_grant：普通角色知识菜单授权+演示访客绑定（收尾冲刺规格 §3——
-- docs/superpowers/plans/2026-11-19-final-sprint.md 任务 3）。
--
-- 背景：role 2（common）此前仅有 V0000 基线的 85 行系统菜单关联，知识菜单
-- （2100-2299，V0103 三分组后 3 M + 8 C + 14 F = 25 行）对非 admin 用户零授权——
-- 非 admin 登录侧栏看不到任何知识分组，知识平台等于 admin 专属。
--
-- 先补 2 行 perms 载体（V0103 缺口收口）：V0103 定谳删除工作空间页时一并退役了
-- F 行 2111/2112，但 @PreAuthorize 面仍引用 knowledge:workspace:list/edit
-- （WorkspaceKbController 类级 3+10 处等）——admin 持 *:*:* 无感，非 admin 则
-- GET /api/v1/workspaces 403、/graph 图谱入口卡死（2026-11-19 访客登录实测）。
-- 补回为 F 型按钮行挂 2200 工作台分组下：F 不进侧栏导航（RouterService 只处理 M/C），
-- 仅向权限集合贡献 perms。规格"区间=27 行"即此账（25 + 补回 2）。
--
-- 授权：区间 27 行整体授 role 2（含 3 个分组 M——若依菜单树按关联行逐级渲染，
-- 只授 C 不授 M 则分组目录不出现；F 按钮行一并授予，perms 鉴权面同步放行）。
-- 绑定：演示访客 9999 → role 2。用户本体由 scripts/seed-demo.sql §0.5 造
-- （user_name=demo，口令同 admin 的 BCrypt 串，明文 admin123）；sys_user_role
-- 无外键约束（V0000 DDL 仅复合主键），空库重放允许绑定先于用户存在，seed 后即生效。
--
-- 幂等：sys_menu 主键 menu_id、sys_role_menu 复合主键 (role_id,menu_id)、
-- sys_user_role 复合主键 (user_id,role_id)（V0000 DDL 核对），ON CONFLICT DO
-- NOTHING 重放不重复插；role 2 既有 85 行系统关联与区间无交集。

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
                      is_frame, is_cache, menu_type, visible, status, perms, icon,
                      create_by, create_time, update_by, update_time, remark)
VALUES (2111, '工作空间列表', 2200, 90, '#', '', '', '', 1, 0, 'F', '0', '0', 'knowledge:workspace:list', '#',
        'admin', now(), '', null, 'V0105 补回：工作空间页已退役，perms 载体行保留供 @PreAuthorize 鉴权'),
       (2112, '工作空间编辑', 2200, 91, '#', '', '', '', 1, 0, 'F', '0', '0', 'knowledge:workspace:edit', '#',
        'admin', now(), '', null, 'V0105 补回：工作空间页已退役，perms 载体行保留供 @PreAuthorize 鉴权')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 2, menu_id FROM sys_menu WHERE menu_id BETWEEN 2100 AND 2299
ON CONFLICT DO NOTHING;
INSERT INTO sys_user_role (user_id, role_id) VALUES (9999, 2) ON CONFLICT DO NOTHING;
