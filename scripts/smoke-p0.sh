#!/usr/bin/env bash
# P0 冒烟：登录闭环 + 若依核心读接口。前置：应用已启动(默认 18080，JKNOW_APP_BASE 可覆盖)、验证码已关。
set -euo pipefail
BASE=${JKNOW_APP_BASE:-http://localhost:18080}
TOKEN=$(curl -s -X POST "$BASE/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "FAIL: 登录未取得 token"; exit 1; }
echo "PASS: login"
AUTH="Authorization: Bearer $TOKEN"
for ep in getInfo getRouters system/user/list system/role/list system/menu/list \
          system/dict/type/list system/config/list system/notice/list \
          monitor/operlog/list monitor/logininfor/list monitor/job/list; do
  CODE=$(curl -s -H "$AUTH" "$BASE/$ep?pageNum=1&pageSize=10" | sed -n 's/.*"code":\([0-9]*\).*/\1/p')
  [ "$CODE" = "200" ] && echo "PASS: $ep" || { echo "FAIL: $ep code=$CODE"; exit 1; }
done
echo "ALL PASS"
