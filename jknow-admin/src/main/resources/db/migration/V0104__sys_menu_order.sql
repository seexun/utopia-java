-- V0104__sys_menu_order：系统组后移——侧栏交错根治（规格 docs/superpowers/specs/2026-11-18-menu-ia-design.md
-- 验收标准"系统组在后"收口）。
--
-- 根因（T2 全链走查发现，2026-11-18）：V0103 给三分组 M（2200 工作台/2210 知识管理/2220 配置）
-- order_num 1/2/3，与若依内置顶级目录 系统管理(1)/系统监控(2)/系统工具(3)（menu_id 1/2/3）同序；
-- getRouters 的菜单树 SQL 为 ORDER BY parent_id, order_num，PostgreSQL 稳定排序下同序按
-- menu_id 决胜 → 侧栏交错渲染：首页、系统管理、工作台、系统监控、知识管理、系统工具、配置。
-- 这是 V0103 计划草案自身的缺口（草案只设三组 order，未动系统组），非实现偏差。
--
-- 修复：系统组三行 order_num + 3 → 4/5/6。行数/perms/树形零变动（MENU_COUNT 109 不变）；
-- 三分组 order 1/2/3 断言（MigrationTests）不受影响。幂等性说明：Flyway 迁移单次执行，
-- +3 相对式与 V0103 的绝对式 UPDATE 同为一次性语句（secondMigrateIsIdempotent 靠
-- migrationsExecuted=0 而非语句可重入保证）。

UPDATE sys_menu SET order_num = order_num + 3 WHERE menu_id IN (1,2,3) AND parent_id = 0;
