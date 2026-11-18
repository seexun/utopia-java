-- ============================================================================
-- teardown-demo.sql — 拆除演示知识库「演示：智造产业链」（jknow 口径，挂 sys_user）
--
-- 策略：
--   · 演示工作区一条 DELETE 级联清空：knowledge_bases（及其全部 kb 归属行——
--     facts/entities/chunks/documents/sources/rules/队列/告警/会话/kb_members…）、
--     memberships、data_source_grants、kb_data_sources（业务表 FK 均 ON DELETE CASCADE）。
--   · 非 ws 归属的行按 seed-demo.sql 的固定 uuid 精确删除（不碰其他库/用户数据）。
--   · audit_events 是 append-only（触发器禁 UPDATE/DELETE），无法删除；其中
--     kb_id 指向本库的残行没有外键，库删后永不可见，留作审计痕迹（幂等可重跑）。
--   · user 维度不动 sys_user（P3 任务 2 口径）：演示主角=admin（sys_user.user_id=1，
--     V0000 造，非本脚本资产）；演示访客（user_id=9999）ON CONFLICT 幂等保留，
--     seed 重跑 DO NOTHING 复用同一行，不累积。
--
-- 影响：p1-smoke/走查 等既有库与测试残留零影响（只按固定 uuid 删）。
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

COMMIT;

-- 复核：应当全 0 行
-- SELECT count(*) FROM knowledge_bases WHERE id = '0190d3e0-0000-7d5a-8000-000000000001';
-- SELECT count(*) FROM workspaces WHERE id = '0190d3e0-0000-7d5a-8000-0000000000f5';
-- SELECT count(*) FROM facts WHERE kb_id = '0190d3e0-0000-7d5a-8000-000000000001';
