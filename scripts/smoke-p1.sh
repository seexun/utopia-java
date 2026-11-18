#!/usr/bin/env bash
# P1 业务冒烟：若依登录→业务造数(workspace/kb/实体类/关系类)→各域 GET 走查→pt- 双通→ES/LLM 条件断言。
# 前置：应用已启动(默认 19080，JKNOW_APP_BASE 可覆盖)、compose(pg+redis) 在跑。
# 自足化：自动关验证码（DB sys_config 置 false + flush redis 缓存），新环境按 README 重放后可直接执行。
# 幂等：固定前缀 p1-smoke 造数，跑前清理上一轮残留 workspace（DELETE 级联 kb/本体），重复跑不撞名不留残。
# 条件断言：ES(JKNOW_ES_URL 可覆盖，与应用 application.yml 同名同缺省)不可达→检索面 SKIP；
#           JKNOW_LLM_API_KEY 未配→对话面 SKIP（chat 无模型配置回 422 no_chat_model，属外部依赖非回归）。
set -euo pipefail
cd "$(dirname "$0")/.."
BASE=${JKNOW_APP_BASE:-http://localhost:19080}
ES=${JKNOW_ES_URL:-http://localhost:9200}
PASS=0
SKIP=0
J='Content-Type: application/json'

# 关验证码（若依 CaptchaController 读 redis 缓存的 sys_config，故 DB+redis 双清）
docker compose -f docker-compose.dev.yml exec -T pg psql -U postgres -d jknow -c \
  "update sys_config set config_value='false' where config_key='sys.account.captchaEnabled';" >/dev/null
docker compose -f docker-compose.dev.yml exec -T redis redis-cli flushdb >/dev/null

# 若依登录（会话 token 打全程写面/清理面）
TOKEN=$(curl -s --max-time 10 -X POST "$BASE/login" -H "$J" \
  -d '{"username":"admin","password":"admin123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "FAIL: login"; exit 1; }
echo "PASS: login"; PASS=$((PASS+1))
AUTH="Authorization: Bearer $TOKEN"

# 幂等清理：删上一轮 p1-smoke 前缀 workspace（上轮异常退出留下的残档也在此清掉）
OLD_WS=$(curl -s --max-time 10 -H "$AUTH" "$BASE/api/v1/workspaces" \
  | LC_ALL=C grep -ao '"id":"[^"]*","org_id":"[^"]*","name":"p1-smoke[^"]*"' \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' || true)
for id in $OLD_WS; do
  curl -s --max-time 10 -X DELETE -H "$AUTH" "$BASE/api/v1/workspaces/$id" >/dev/null
done

# 从 JSON 响应取首个 "id" 字段（业务面 Jackson SNAKE_CASE，id 恒为首键）
json_id() { sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1; }

# 造数流（POST 写面各域一个代表）：workspace → kb → 实体类 → 关系类。
# 实体/关系实例无直建端点——由抽取管线产出（LLM 依赖），不入无 key 冒烟。
WS=$(curl -s --max-time 10 -X POST -H "$AUTH" -H "$J" \
  -d '{"name":"p1-smoke"}' "$BASE/api/v1/workspaces" | json_id)
[ -n "$WS" ] || { echo "FAIL: POST workspaces（响应无 id）"; exit 1; }
echo "PASS: POST api/v1/workspaces (ws=$WS)"; PASS=$((PASS+1))

KB=$(curl -s --max-time 10 -X POST -H "$AUTH" -H "$J" \
  -d '{"name":"p1-smoke-kb"}' "$BASE/api/v1/workspaces/$WS/kbs" | json_id)
[ -n "$KB" ] || { echo "FAIL: POST workspaces/{id}/kbs（响应无 id）"; exit 1; }
echo "PASS: POST api/v1/workspaces/{id}/kbs (kb=$KB)"; PASS=$((PASS+1))

ET=$(curl -s --max-time 10 -X POST -H "$AUTH" -H "$J" \
  -d '{"key":"smoke_person","label":"Smoke Person"}' \
  "$BASE/api/v1/kbs/$KB/ontology/entity-types" | json_id)
[ -n "$ET" ] || { echo "FAIL: POST ontology/entity-types（响应无 id）"; exit 1; }
echo "PASS: POST api/v1/kbs/{id}/ontology/entity-types (et=$ET)"; PASS=$((PASS+1))

RT=$(curl -s --max-time 10 -X POST -H "$AUTH" -H "$J" \
  -d '{"key":"smoke_knows","label":"smoke knows"}' \
  "$BASE/api/v1/kbs/$KB/ontology/relation-types" | json_id)
[ -n "$RT" ] || { echo "FAIL: POST ontology/relation-types（响应无 id）"; exit 1; }
echo "PASS: POST api/v1/kbs/{id}/ontology/relation-types (rt=$RT)"; PASS=$((PASS+1))

# 各域只读走查（GET 200）。三面带必填 query 参数（controller 实况）：
#   entities 要 q、review/pending 要 chunk_id、graph/neighborhood 要 entity——
#   缺参自 P1 终审 I3 起回源契约 400（下方专段断言），走查按契约补参打。
ZERO="00000000-0000-0000-0000-000000000000"
GET_EPS=(
  "api/v1/workspaces"
  "api/v1/workspaces/$WS"
  "api/v1/workspaces/$WS/kbs"
  "api/v1/workspaces/$WS/members"
  "api/v1/workspaces/$WS/my-kbs"
  "api/v1/workspaces/$WS/settings"
  "api/v1/kbs/$KB"
  "api/v1/kbs/$KB/audit"
  "api/v1/kbs/$KB/export"
  "api/v1/kbs/$KB/members"
  "api/v1/kbs/$KB/readiness"
  "api/v1/kbs/$KB/conversations"
  "api/v1/kbs/$KB/data-sources"
  "api/v1/kbs/$KB/data-sources/available"
  "api/v1/kbs/$KB/documents"
  "api/v1/kbs/$KB/entities?q=smoke"
  "api/v1/kbs/$KB/extraction-drops"
  "api/v1/kbs/$KB/graph/neighborhood?entity=$ZERO"
  "api/v1/kbs/$KB/graph/overview"
  "api/v1/kbs/$KB/jobs/failed"
  "api/v1/kbs/$KB/mappings"
  "api/v1/kbs/$KB/mappings/relevant?q=smoke"
  "api/v1/kbs/$KB/ontology"
  "api/v1/kbs/$KB/ontology/entity-types/$ET/entities"
  "api/v1/kbs/$KB/ontology/imports"
  "api/v1/kbs/$KB/ontology/proposals"
  "api/v1/kbs/$KB/ontology/uniqueness"
  "api/v1/kbs/$KB/questions"
  "api/v1/kbs/$KB/questions/report"
  "api/v1/kbs/$KB/review"
  "api/v1/kbs/$KB/review/history"
  "api/v1/kbs/$KB/review/pending?chunk_id=$ZERO"
  "api/v1/kbs/$KB/review/summary"
  "api/v1/kbs/$KB/rules"
  "api/v1/kbs/$KB/sources"
  "api/v1/me/tokens"
  "api/v1/ontology-packs"
  "api/v1/users"
  "api/v1/users/deactivated"
  "api/v1/alerts"
  "api/v1/alerts/unread"
  "api/v1/admin/data-sources"
  "api/v1/admin/deployment"
  "api/v1/health"
  "health"
)
for ep in "${GET_EPS[@]}"; do
  CODE=$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' -H "$AUTH" "$BASE/$ep")
  [ "$CODE" = 200 ] && { echo "PASS: GET $ep"; PASS=$((PASS+1)); } \
    || { echo "FAIL: GET $ep code=$CODE"; exit 1; }
done

# 公开路由面与参数契约（P1 终审 C1/I3 断言）：
#   匿名 health 200 且体含 version（不再 200+code401 的若依鉴权体）；
#   推送端点 permitAll 后自带推送密钥门——api 源造好后无密钥推 401 源体形状
#   （判定序同源：源不存在 404 在前，故先造真源再断 401）；
#   缺必填 query 参数 400 源文案（q/chunk_id 两面）。
HEALTH_BODY=$(curl -s --max-time 10 "$BASE/api/v1/health")
HEALTH_CODE=$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' "$BASE/api/v1/health")
if [ "$HEALTH_CODE" = 200 ] && printf '%s' "$HEALTH_BODY" | LC_ALL=C grep -aq '"version"'; then
  echo "PASS: 匿名 GET api/v1/health（200+version）"; PASS=$((PASS+1))
else
  echo "FAIL: 匿名 GET api/v1/health code=$HEALTH_CODE body=$HEALTH_BODY"; exit 1
fi
PUSH_SRC=$(curl -s --max-time 10 -X POST -H "$AUTH" -H "$J" \
  -d '{"kind":"api","name":"p1-smoke-push"}' "$BASE/api/v1/kbs/$KB/sources")
PUSH_SID=$(printf '%s' "$PUSH_SRC" | sed -n 's/.*"source":{"id":"\([^"]*\)".*/\1/p')
[ -n "$PUSH_SID" ] || { echo "FAIL: 造 api 源（推送 401 断言前置）resp=$(printf '%s' "$PUSH_SRC" | head -c 120)"; exit 1; }
PUSH_CODE=$(curl -s --max-time 10 -o /tmp/smoke-p1-push.txt -w '%{http_code}' -X POST -H "$J" \
  -d '{"filename":"x.txt","content":"x"}' "$BASE/api/v1/sources/$PUSH_SID/ingest")
if [ "$PUSH_CODE" = 401 ] && LC_ALL=C grep -aq 'Not signed in or invalid credentials' /tmp/smoke-p1-push.txt; then
  echo "PASS: POST api/v1/sources/{id}/ingest 无密钥 401（源体形状）"; PASS=$((PASS+1))
else
  echo "FAIL: 推送端点无密钥 code=$PUSH_CODE body=$(head -c 120 /tmp/smoke-p1-push.txt)"; exit 1
fi
Q_CODE=$(curl -s --max-time 10 -o /tmp/smoke-p1-q.txt -w '%{http_code}' -H "$AUTH" \
  "$BASE/api/v1/kbs/$KB/mappings/relevant")
if [ "$Q_CODE" = 400 ] && LC_ALL=C grep -aq 'Failed to deserialize query string: missing field `q`' /tmp/smoke-p1-q.txt; then
  echo "PASS: GET api/v1/kbs/{id}/mappings/relevant 缺 q 400（源文案）"; PASS=$((PASS+1))
else
  echo "FAIL: 缺 q code=$Q_CODE body=$(head -c 120 /tmp/smoke-p1-q.txt)"; exit 1
fi
CH_CODE=$(curl -s --max-time 10 -o /tmp/smoke-p1-ch.txt -w '%{http_code}' -H "$AUTH" \
  "$BASE/api/v1/kbs/$KB/review/pending")
if [ "$CH_CODE" = 400 ] && LC_ALL=C grep -aq 'Failed to deserialize query string: missing field `chunk_id`' /tmp/smoke-p1-ch.txt; then
  echo "PASS: GET api/v1/kbs/{id}/review/pending 缺 chunk_id 400（源文案）"; PASS=$((PASS+1))
else
  echo "FAIL: 缺 chunk_id code=$CH_CODE body=$(head -c 120 /tmp/smoke-p1-ch.txt)"; exit 1
fi

# pt- 个人令牌双通：签发（明文只此一次）→pt- 打业务读面 200→撤销→pt- 即时 401
PT_RESP=$(curl -s --max-time 10 -X POST -H "$AUTH" -H "$J" \
  -d '{"name":"p1-smoke","scope":"read","expires_in_days":1}' "$BASE/api/v1/me/tokens")
PT=$(printf '%s' "$PT_RESP" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
PTID=$(printf '%s' "$PT_RESP" | sed -n 's/.*"info":{"id":"\([^"]*\)".*/\1/p')
case "$PT" in
  pt-*) echo "PASS: POST api/v1/me/tokens（pt- 前缀签发）"; PASS=$((PASS+1)) ;;
  *) echo "FAIL: POST api/v1/me/tokens（无 pt- 前缀 token，resp=$(printf '%s' "$PT_RESP" | head -c 120)）"; exit 1 ;;
esac
CODE=$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $PT" "$BASE/api/v1/workspaces")
[ "$CODE" = 200 ] && { echo "PASS: pt- GET api/v1/workspaces"; PASS=$((PASS+1)); } \
  || { echo "FAIL: pt- GET api/v1/workspaces code=$CODE"; exit 1; }
CODE=$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $PT" "$BASE/api/v1/kbs/$KB")
[ "$CODE" = 200 ] && { echo "PASS: pt- GET api/v1/kbs/{id}"; PASS=$((PASS+1)); } \
  || { echo "FAIL: pt- GET api/v1/kbs/{id} code=$CODE"; exit 1; }
# pt- scope 的 REST 面强制（P1 终审 I1）：read 令牌打写方法 → 401 源体形状
WCODE=$(curl -s --max-time 10 -o /tmp/smoke-p1-ptw.txt -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $PT" -H "$J" -d '{"name":"p1-smoke-pt-read"}' "$BASE/api/v1/workspaces")
if [ "$WCODE" = 401 ] && LC_ALL=C grep -aq 'Not signed in or invalid credentials' /tmp/smoke-p1-ptw.txt; then
  echo "PASS: pt- read 打写面 401（scope 强制）"; PASS=$((PASS+1))
else
  echo "FAIL: pt- read 打写面 code=$WCODE body=$(head -c 120 /tmp/smoke-p1-ptw.txt)"; exit 1
fi
CODE=$(curl -s --max-time 10 -X DELETE -H "$AUTH" -o /dev/null -w '%{http_code}' \
  "$BASE/api/v1/me/tokens/$PTID")
[ "$CODE" = 200 ] && { echo "PASS: DELETE api/v1/me/tokens/{id}（撤销）"; PASS=$((PASS+1)); } \
  || { echo "FAIL: DELETE api/v1/me/tokens/{id} code=$CODE"; exit 1; }
CODE=$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $PT" "$BASE/api/v1/workspaces")
[ "$CODE" = 401 ] && { echo "PASS: pt- 撤销后即时 401"; PASS=$((PASS+1)); } \
  || { echo "FAIL: pt- 撤销后复用 code=$CODE（期望 401）"; exit 1; }

# ES 条件断言：ES 通→检索面（BM25 路 ES+IK）必须 200；不通→SKIP
if curl -s --max-time 5 -o /dev/null "$ES"; then
  CODE=$(curl -s --max-time 15 -o /dev/null -w '%{http_code}' -X POST -H "$AUTH" -H "$J" \
    -d '{"q":"smoke"}' "$BASE/api/v1/kbs/$KB/search")
  [ "$CODE" = 200 ] && { echo "PASS: POST api/v1/kbs/{id}/search (es=$ES)"; PASS=$((PASS+1)); } \
    || { echo "FAIL: POST api/v1/kbs/{id}/search code=$CODE（ES 可达却失败）"; exit 1; }
else
  echo "SKIP: POST api/v1/kbs/{id}/search（es=$ES 不可达）"; SKIP=$((SKIP+1))
fi

# LLM 条件断言：JKNOW_LLM_API_KEY 配了才打对话面（应用侧还需 Settings→Models 配模型）；未配→SKIP
if [ -n "${JKNOW_LLM_API_KEY:-}" ]; then
  CODE=$(curl -s --max-time 60 -o /dev/null -w '%{http_code}' -X POST -H "$AUTH" -H "$J" \
    -d '{"message":"smoke"}' "$BASE/api/v1/kbs/$KB/chat")
  [ "$CODE" = 200 ] && { echo "PASS: POST api/v1/kbs/{id}/chat (llm)"; PASS=$((PASS+1)); } \
    || { echo "FAIL: POST api/v1/kbs/{id}/chat code=$CODE（JKNOW_LLM_API_KEY 已设）"; exit 1; }
else
  echo "SKIP: POST api/v1/kbs/{id}/chat（JKNOW_LLM_API_KEY 未配）"; SKIP=$((SKIP+1))
fi

# seed 演示态明细面（P3 任务 3）：seed-demo.sql 灌库（幂等，固定 uuid）→ 明细 GET
# （200 + 体形状断言——空库造不出的面：文档详情/实体详情/图谱邻域/会话族/未读角标）
# → teardown-demo.sql 拆除。自足段：seed 路径按仓库相对定位，psql 经 compose exec，
# 跑完库里不留演示档（teardown 策略见其文件头注）。
SEED_KB='0190d3e0-0000-7d5a-8000-000000000001'
SEED_DOC='0190d3e0-0000-7d5a-8000-000000000040'
SEED_ENT='0190d3e0-0000-7d5a-8000-000000000020'
SEED_CONV='0190d3e0-0000-7d5a-8000-000000000ff0'
docker compose -f docker-compose.dev.yml exec -T pg psql -U postgres -d jknow \
  -v ON_ERROR_STOP=1 -q -f - < scripts/seed-demo.sql

# 明细断言助手：GET 200 且体含全部给定形状串（grep -a 字节级，UTF-8 中文直配）
seed_get() { # $1=面名（echo 用） $2=端点 $3..=体形状断言串
  local name=$1 ep=$2; shift 2
  local code ok=1 pat
  code=$(curl -s --max-time 10 -o /tmp/smoke-p1-seed.txt -w '%{http_code}' \
    -H "$AUTH" "$BASE/$ep")
  [ "$code" = 200 ] || ok=0
  for pat in "$@"; do
    LC_ALL=C grep -aq -- "$pat" /tmp/smoke-p1-seed.txt || ok=0
  done
  if [ "$ok" = 1 ]; then
    echo "PASS: GET $ep（$name，seed 明细体形状）"; PASS=$((PASS+1))
  else
    echo "FAIL: GET $ep（$name）code=$code body=$(head -c 160 /tmp/smoke-p1-seed.txt)"; exit 1
  fi
}
seed_get "文档详情" "api/v1/documents/$SEED_DOC" \
  '"document":{' '"id":"'$SEED_DOC'"' '"chunks":\[' '供应链尽调报告.md'
seed_get "实体详情" "api/v1/kbs/$SEED_KB/entities/$SEED_ENT" \
  '"entity":{' '"facts":\[' '"same_name":\[' '星辰智造'
seed_get "图谱邻域" "api/v1/kbs/$SEED_KB/graph/neighborhood?entity=$SEED_ENT" \
  '"nodes":\[{'
seed_get "会话列表" "api/v1/kbs/$SEED_KB/conversations" \
  '"conversations":\[{' '供应链产能问题'
seed_get "会话详情" "api/v1/kbs/$SEED_KB/conversations/$SEED_CONV" \
  '"messages":\[{'
seed_get "未读角标" "api/v1/alerts/unread" \
  '"unread":[1-9][0-9]*'

docker compose -f docker-compose.dev.yml exec -T pg psql -U postgres -d jknow \
  -v ON_ERROR_STOP=1 -q -f - < scripts/teardown-demo.sql

# 收尾清理：本轮 workspace（级联 kb/本体/实体类），库里不留 p1-smoke 档
curl -s --max-time 10 -X DELETE -H "$AUTH" "$BASE/api/v1/workspaces/$WS" >/dev/null

echo "ALL PASS (PASS=$PASS SKIP=$SKIP)"
