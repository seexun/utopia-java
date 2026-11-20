-- V0106__demo_readonly_role：演示访客专用只读角色（只授知识菜单，不授系统菜单）+孤儿行通用清
-- （打磨+加固批规格 2026-11-20-polish-harden §4——docs/superpowers/plans/ 对应任务 3）。
--
-- 背景：V0105 把演示访客 9999 绑到 role 2（common），而 role 2 持 V0000 基线 85 行系统菜单
-- 关联——demo 登录侧栏三分组之外还见系统管理/监控/工具，只读演示定位失守。本迁移另立
-- role 3（demo，只读演示角色）：知识区间 2100-2299 全量 27 行整体授予（与 V0105 的
-- role 2 同口径——含 3 分组 M 与 16 F perms 载体行，图谱/检索/对话全链鉴权面放行），
-- 系统菜单（menu_id < 2100）零授予；9999 解绑 2 改绑 3。
--
-- 列序模板 = V0000 的 sys_role insert 行（14 列 DDL 实况：role_id, role_name, role_key,
-- role_sort, data_scope, menu_check_strictly(smallint), dept_check_strictly(smallint),
-- status, del_flag, create_by, create_time, update_by, update_time, remark）——
-- 显式列名插入（同 V0105 对 sys_menu 的做法），strictly 列取整数 1（V0000 两行实况，
-- 非 boolean——smallint 列收 true 会类型报错）。
--
-- 孤儿行通用清：V0103 定谳删除 menu_id=4（若依官网外链）等行后，sys_role_menu 残留
-- (2,4) 关联（V0000 基线 85 行之一，V0103 只清菜单未清关联）——按 NOT IN 子查询通用清，
-- 覆盖全表任何角色的历史残留，role 2 关联总数 112 → 111。
--
-- 幂等：sys_role 主键 role_id、sys_role_menu 复合主键 (role_id,menu_id)、sys_user_role
-- 复合主键 (user_id,role_id)（V0000 DDL 核对），ON CONFLICT DO NOTHING 重放不重复插；
-- 9999 改绑用 DELETE 全清 + INSERT 单绑（重放时 DELETE 0 行、INSERT 冲突跳过）；
-- 孤儿清 DELETE 天然幂等。sys_role_menu/sys_user_role 无外键约束（V0000 DDL 仅复合主键），
-- 空库重放允许绑定先于用户存在（用户本体由 scripts/seed-demo.sql §0.5 造，seed 后即生效）。

INSERT INTO sys_role (role_id, role_name, role_key, role_sort, data_scope,
                      menu_check_strictly, dept_check_strictly, status, del_flag,
                      create_by, create_time, remark)
VALUES (3, '演示访客', 'demo', 3, '1', 1, 1, '0', '0', 'admin', now(),
        '只读演示角色——仅知识菜单（规格 2026-11-20-polish-harden §4）')
ON CONFLICT (role_id) DO NOTHING;

-- role 3 授权：知识区间 27 行整体授予（V0105 的 role 2 同口径；SELECT 现存行故必非孤儿）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 3, menu_id FROM sys_menu WHERE menu_id BETWEEN 2100 AND 2299
ON CONFLICT DO NOTHING;

-- 9999 解绑 role 2 改绑 role 3（DELETE 全清 + INSERT 单绑，幂等）
DELETE FROM sys_user_role WHERE user_id = 9999;
INSERT INTO sys_user_role (user_id, role_id) VALUES (9999, 3) ON CONFLICT DO NOTHING;

-- 孤儿行通用清（menu_id 指向已删菜单的关联行，含 V0103 遗留 (2,4)；menu_id 主键非空，NOT IN 安全）
DELETE FROM sys_role_menu WHERE menu_id NOT IN (SELECT menu_id FROM sys_menu);
