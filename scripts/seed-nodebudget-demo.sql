-- nodeBudget 档位演示库（独立脚本，不入默认 seed/smoke 链）：>150 实体使
-- 图谱页节点档位 ± 启用（档位 [150,300,600,1000]）。幂等：固定 uuid ON CONFLICT。
-- 用法：docker exec -i jknow-pg-1 psql -U postgres -d jknow < scripts/seed-nodebudget-demo.sql
-- 库挂既有「演示工作区」(…0000000000f5，seed-demo.sql 建)：kb-switcher 下拉只列
-- 当前工作区的库（useKbContext 的 kbsQuery 以 workspace.id 为缓存键），独立工作区
-- 里的库在切换器不可见——故不建新 org/ws（简报骨架的 org/ws 段按实测链路裁撤）。
-- 注意：重跑 seed-demo.sql / teardown-demo.sql 或 smoke-p1.sh 会经工作区 f5 的
-- ON DELETE CASCADE 连带清掉本库（演示：档位压测）——跑毕复跑本脚本即可复种。
-- visibility CHECK 只收 open/restricted（\d 实测，无 'public' 取值）。
-- uuid 段式 0190d3e0-0000-7d5a-8000-00000000a0f2（kb）/ -0000000a1ggg（实体，
-- 尾段 12 hex 全 hex 合法），与 seed-demo.sql 的 0000000000xx 段不撞（实测两前缀
-- 库内 0 行）。
BEGIN;

INSERT INTO knowledge_bases (id, workspace_id, name, visibility, created_at) VALUES
    ('0190d3e0-0000-7d5a-8000-00000000a0f2',
     '0190d3e0-0000-7d5a-8000-0000000000f5',
     '演示：档位压测', 'open', '2026-11-24T00:00:01+08:00')
ON CONFLICT (id) DO NOTHING;

-- admin 入会（kb-switcher 可见）；kb_members.user_id 为 uuid → sys_user(utopia_uuid)，
-- 沿用 seed-demo.sql 的 (SELECT utopia_uuid … WHERE user_id = 1) 写法
-- （admin 实测 user_id=1，utopia_uuid=c626b0dc-dbaf-4318-be21-20253d686df4）
INSERT INTO kb_members (kb_id, user_id, role, created_at)
SELECT '0190d3e0-0000-7d5a-8000-00000000a0f2',
       utopia_uuid, 'admin', '2026-11-24T00:00:03+08:00'
FROM sys_user WHERE user_id = 1
ON CONFLICT DO NOTHING;

-- 170 实体（type_id NULL=未分类，graph 正常渲染灰点；实体 id 尾段
-- 0000000a1||lpad(g,3)：0000000a1001 ~ 0000000a1170，lpad 3 位 170→'170' 合法）
INSERT INTO entities (id, kb_id, canonical_name)
SELECT ('0190d3e0-0000-7d5a-8000-0000000a1' || lpad(g::text, 3, '0'))::uuid,
       '0190d3e0-0000-7d5a-8000-00000000a0f2',
       '压测实体-' || g
FROM generate_series(1, 170) g
ON CONFLICT (id) DO NOTHING;

COMMIT;

-- 清理段（需要时手动启用）：
-- BEGIN;
-- DELETE FROM entities WHERE kb_id='0190d3e0-0000-7d5a-8000-00000000a0f2';
-- DELETE FROM kb_members WHERE kb_id='0190d3e0-0000-7d5a-8000-00000000a0f2';
-- DELETE FROM knowledge_bases WHERE id='0190d3e0-0000-7d5a-8000-00000000a0f2';
-- COMMIT;
