#!/usr/bin/env bash
# P0 冒烟：登录闭环 + 核心读接口 + 全日期面筛选断言。
# 前置：应用已启动(默认 19080，JKNOW_APP_BASE 可覆盖)、compose(pg+redis) 在跑。
# 自足化：脚本自动关验证码（DB sys_config 置 false + flush redis 缓存），
#         新环境按 README 重放后可直接执行本脚本。
set -euo pipefail
cd "$(dirname "$0")/.."
BASE=${JKNOW_APP_BASE:-http://localhost:19080}

# 关验证码（若依 CaptchaController 读 redis 缓存的 sys_config，故 DB+redis 双清）
docker compose -f docker-compose.dev.yml exec -T pg psql -U postgres -d jknow -c \
  "update sys_config set config_value='false' where config_key='sys.account.captchaEnabled';" >/dev/null
docker compose -f docker-compose.dev.yml exec -T redis redis-cli flushdb >/dev/null

TOKEN=$(curl -s --max-time 10 -X POST "$BASE/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "FAIL: 登录未取得 token"; exit 1; }
echo "PASS: login"
AUTH="Authorization: Bearer $TOKEN"
for ep in getInfo getRouters system/user/list system/role/list system/menu/list \
          system/dict/type/list system/config/list system/notice/list \
          monitor/operlog/list monitor/logininfor/list monitor/job/list; do
  CODE=$(curl -s --max-time 10 -H "$AUTH" "$BASE/$ep?pageNum=1&pageSize=10" | sed -n 's/.*"code":\([0-9]*\).*/\1/p')
  [ "$CODE" = "200" ] && echo "PASS: $ep" || { echo "FAIL: $ep code=$CODE"; exit 1; }
done
# 日期范围筛选 9 面（params.beginTime/endTime 经 mapper 参数侧 ::timestamp 转型，
# 回归 to_char(varchar)/裸比较 必炸问题——user/role/dict.type/config/jobLog/logininfor/operlog/gen）
for ep in system/user/list system/role/list system/dict/type/list system/config/list \
          monitor/jobLog/list monitor/logininfor/list monitor/operlog/list tool/gen/list; do
  CODE=$(curl -s --max-time 10 -H "$AUTH" \
    "$BASE/$ep?pageNum=1&pageSize=10&params%5BbeginTime%5D=2026-01-01&params%5BendTime%5D=2026-12-31" \
    | sed -n 's/.*"code":\([0-9]*\).*/\1/p')
  [ "$CODE" = "200" ] && echo "PASS: $ep date-range" || { echo "FAIL: $ep date-range code=$CODE"; exit 1; }
done
echo "ALL PASS"
