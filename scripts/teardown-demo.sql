-- ============================================================================
-- teardown-demo.sql — 拆除演示知识库「演示：智造产业链」（jknow 口径，挂 sys_user）
--
-- 策略：
--   · 演示工作区一条 DELETE 级联清空：knowledge_bases（及其全部 kb 归属行——
--     facts/entities/chunks/documents/sources/rules/队列/告警/会话/kb_members…）、
--     memberships、data_source_grants、kb_data_sources（业务表 FK 均 ON DELETE CASCADE）。
--   · 非 ws 归属的行按 seed-demo.sql 的固定 uuid 精确删除（不碰其他库/用户数据）。
--   · audit_events 是 append-only（触发器禁 UPDATE/DELETE）。演示库的 uuid 是固定
--     值，seed 重建后旧残行（含走查/演练写下的 fact.nod_confirmed 等）会重新出现在
--     裁决史里——所以本脚本以 superuser 先停行级触发器、按 kb_id 清演示库审计行、
--     再恢复触发器（V0007 的口径：特权运维动作显式动 DDL，全程可查）。只清本库。
--   · user 维度不动 sys_user（P3 任务 2 口径）：演示主角=admin（sys_user.user_id=1，
--     V0000 造，非本脚本资产）；演示访客（user_id=9999）ON CONFLICT 幂等保留，
--     seed 重跑 DO NOTHING 复用同一行，不累积。
--
-- 影响：p1-smoke/走查 等既有库与测试残留零影响（只按固定 uuid 删）；例外：挂演示
-- 工作区 f5 的压测库 a0f2（演示：档位压测，scripts/seed-nodebudget-demo.sql 建，
-- 2026-11-24 起）会被本脚本第 1 步的 ON DELETE CASCADE 连带清掉——跑毕按 README
-- 「可选演示」节复跑该命令复种。
--
-- 执行：docker compose -f docker-compose.dev.yml exec -T pg psql -U postgres -d jknow \
--         -v ON_ERROR_STOP=1 -f - < scripts/teardown-demo.sql
-- ============================================================================

BEGIN;

-- 1. 演示工作区（级联：演示库 + facts/entities/chunks/documents/sources/rules/
--    队列/告警/会话/kb_members + memberships + data_source_grants/kb_data_sources…）
DELETE FROM workspaces WHERE id = '0190d3e0-0000-7d5a-8000-0000000000f5';

-- 2. 演示组织（工作区已删，此处清账）
DELETE FROM organizations WHERE id = '0190d3e0-0000-7d5a-8000-0000000000f4';

-- 3. 工作区级数据源（data_source_grants / kb_data_sources 已随级联清）
DELETE FROM data_sources WHERE id = '0190d3e0-0000-7d5a-8000-0000000000e0';

-- 4. 演示主角的 CI 令牌（sys_user 不动，令牌行按固定 uuid 清）
DELETE FROM personal_tokens WHERE id = '0190d3e0-0000-7d5a-8000-000000000ff4';

-- 5. 告警已读位点（按告警 uuid 集合，幂等；alerts 本体已随 kb 级联清）
DELETE FROM alert_reads
 WHERE alert_id IN ('0190d3e0-0000-7d5a-8000-0000000000f2',
                    '0190d3e0-0000-7d5a-8000-0000000000f3');

-- 6. 演示库的审计行（append-only 触发器挡的是「绕过应用的顺手修改」；本脚本是
--    显式的演示环境重置动作，按 V0007 口径以 DDL 显式停/恢复行级触发器）。
--    不清则 seed 以同一 kb uuid 重建后，旧残行继续出现在裁决史（演示台账失真）。
--    事务内 ALTER TABLE：出错回滚连触发器状态一起还原。
ALTER TABLE audit_events DISABLE TRIGGER audit_events_no_update_delete;
DELETE FROM audit_events WHERE kb_id = '0190d3e0-0000-7d5a-8000-000000000001';
ALTER TABLE audit_events ENABLE TRIGGER audit_events_no_update_delete;

COMMIT;

-- 复核：应当全 0 行
-- SELECT count(*) FROM knowledge_bases WHERE id = '0190d3e0-0000-7d5a-8000-000000000001';
-- SELECT count(*) FROM workspaces WHERE id = '0190d3e0-0000-7d5a-8000-0000000000f5';
-- SELECT count(*) FROM facts WHERE kb_id = '0190d3e0-0000-7d5a-8000-000000000001';
-- SELECT count(*) FROM audit_events WHERE kb_id = '0190d3e0-0000-7d5a-8000-000000000001';
