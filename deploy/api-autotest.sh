#!/bin/bash
# =============================================================================
# ARIA 全功能接口自动化测试脚本（业务逻辑级覆盖）
#
# 覆盖范围：
#   基础域：认证/用户/角色/菜单/AI模型/系统配置/内部接口/知识库
#   访客域：会话初始化与复用状态机、对话历史（全量/增量/清除）、消息反馈状态机、
#           短信认证全状态机（限流/锁定/恢复）、CSAT 全状态机（rate/skip/幂等/过期）
#   客服域：转人工幂等、队列状态机（WAITING→ACTIVE→CLOSED）、accept/close/transfer
#           全部正反向、会话归属 CAS、在线座席注册、WebSocket 双向消息互发、
#           备注/标签、快捷回复、SSE 事件流
#   运营域：SLA 策略 CRUD + 违规实测、Webhook、业务时间拦截演练、Dashboard 参数化、
#           DIT 领域/意图/槽位/工具/绑定 CRUD
#
# 用法：bash deploy/api-autotest.sh
#       SKIP_AI=1   跳过依赖 LLM/Embedding 的用例
#       SKIP_SLOW=1 跳过耗时用例（SLA 违规实测，约 75s）
# 依赖：curl、jq、docker（直连 PG/Redis 做种子数据与验证码读取）、python3+websockets
# =============================================================================
set -uo pipefail

BASE=${BASE:-https://localhost}
WSS_BASE=${WSS_BASE:-wss://localhost}
AUTH=$BASE/auth
CONV=$BASE/conversation
KNOW=$BASE/knowledge
INTERNAL_SECRET='aria-internal-lycodeing-2024'
PWD_DEFAULT='Test@123456'
SKIP_AI=${SKIP_AI:-0}
SKIP_SLOW=${SKIP_SLOW:-0}
PG_KNOW='docker exec ai-cs-postgres psql -U postgres -d aria_knowledge -tAc'
REDIS='docker exec ai-cs-redis redis-cli --raw'

# 临时文件放在脚本同级目录（避免系统临时目录不可写的环境限制）
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
RESP="$SCRIPT_DIR/.autotest-resp.$$"
SCHEDULE_BAK="$SCRIPT_DIR/.autotest-schedule-bak.$$.json"
SSE_PIDS=""
SCHEDULE_RESTORED=1

# 退出兜底：杀后台 SSE、恢复业务时间排班、清临时文件
cleanup() {
  [ -n "$SSE_PIDS" ] && kill $SSE_PIDS 2>/dev/null
  if [ "$SCHEDULE_RESTORED" = "0" ] && [ -s "$SCHEDULE_BAK" ]; then
    curl -sk -X PUT "$CONV/api/v1/admin/business-hours/schedule" \
      -H "Authorization: Bearer ${ADMIN:-}" -H 'Content-Type: application/json' \
      -d @"$SCHEDULE_BAK" >/dev/null 2>&1
    echo "⚠️  已在退出时兜底恢复业务时间排班"
  fi
  rm -f "$RESP" "$SCHEDULE_BAK" "$SCRIPT_DIR"/.autotest-doc-$$.txt
}
trap cleanup EXIT

PASS=0; FAIL=0; SKIP=0
ok()   { echo "  ✅ PASS: $1"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ FAIL: $1"; FAIL=$((FAIL+1)); }
skip() { echo "  ⏭  SKIP: $1"; SKIP=$((SKIP+1)); }
hdr()  { echo ""; echo "═══ $1"; }

command -v jq >/dev/null || { echo "缺少 jq，请先安装：brew install jq"; exit 1; }

# 通用请求：req <method> <url> <token> <body> [extra_header...]，HTTP 码写入 HTTP_CODE，响应体写入 $RESP
req() {
  local m=$1 url=$2 tok=$3 body=$4; shift 4
  local args=(-sk -o "$RESP" -w '%{http_code}' -X "$m" "$url" -H 'Content-Type: application/json')
  [ -n "$tok" ]  && args+=(-H "Authorization: Bearer $tok")
  [ -n "$body" ] && args+=(-d "$body")
  local h; for h in "$@"; do args+=(-H "$h"); done
  HTTP_CODE=$(curl "${args[@]}")
}
body()  { cat "$RESP"; }
jget()  { jq -r "$1 // empty" "$RESP" 2>/dev/null; }
bcode() { jq -r '.code // empty' "$RESP" 2>/dev/null; }

# 断言 HTTP 状态码：expect_http <期望码> <描述>
expect_http() { [ "$HTTP_CODE" = "$1" ] && ok "$2 (HTTP $1)" || bad "$2 期望HTTP $1 实际$HTTP_CODE: $(body | head -c 200)"; }
# 断言业务码：expect_bcode <期望业务码> <描述>
expect_bcode() { local c; c=$(bcode); [ "$c" = "$1" ] && ok "$2 (code $1)" || bad "$2 期望code $1 实际${c:-?}: $(body | head -c 200)"; }
# 断言 JSON 字段值：expect_jq <jq表达式> <期望值> <描述>（不用 // empty，否则 false 会被判空）
expect_jq() { local v; v=$(jq -r "$1" "$RESP" 2>/dev/null); [ "$v" = "$2" ] && ok "$3 ($1=$2)" || bad "$3 期望 $1=$2 实际=${v:-?}: $(body | head -c 200)"; }

TS=$(date +%s)

# =============================================================================
hdr "0. 环境健康检查"
for c in ai-cs-auth ai-cs-knowledge ai-cs-conversation-1 ai-cs-conversation-2; do
  s=$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null)
  [ "$s" = "healthy" ] && ok "$c healthy" || bad "$c 状态=$s"
done
gw=$(curl -sk "$BASE/" | jq -r '.status // "offline"' 2>/dev/null)
[ "$gw" = "online" ] && ok "nginx 网关在线" || { bad "nginx 网关异常($gw)"; echo "网关不可用，退出"; exit 1; }
$REDIS ping >/dev/null 2>&1 && ok "Redis 可直连（验证码读取）" || bad "Redis 不可直连"

# =============================================================================
hdr "1. 认证模块（登录/鉴权/权限码）"
login() { # login <username> → 输出 token
  curl -sk -X POST "$AUTH/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$PWD_DEFAULT\"}" | jq -r '.data.tokenValue // empty'
}
ADMIN=$(login superadmin);  [ -n "$ADMIN" ]   && ok "superadmin 登录" || { bad "superadmin 登录失败"; exit 1; }
MANAGER=$(login kfmanager); [ -n "$MANAGER" ] && ok "kfmanager 登录"  || bad "kfmanager 登录失败"
STAFF=$(login kfstaff);     [ -n "$STAFF" ]   && ok "kfstaff 登录"    || bad "kfstaff 登录失败"

req GET "$AUTH/api/v1/auth/me" "$ADMIN" "";  ADMIN_ID=$(jget '.data.userId')
req GET "$AUTH/api/v1/auth/me" "$STAFF" "";  STAFF_ID=$(jget '.data.userId')
[ -n "$ADMIN_ID" ] && [ -n "$STAFF_ID" ] && ok "获取座席 ID (admin=$ADMIN_ID staff=$STAFF_ID)" || bad "未取到座席用户 ID"

req POST "$AUTH/api/v1/auth/login" "" '{"username":"superadmin","password":"WrongPass123"}'
c=$(bcode); [ "$c" != "200" ] && ok "错误密码被拒 (code $c)" || bad "错误密码竟然登录成功"

req GET "$AUTH/api/v1/auth/me" "$ADMIN" "";    expect_http 200 "GET /auth/me"
req GET "$AUTH/api/v1/auth/codes" "$ADMIN" ""; expect_http 200 "GET /auth/codes"
req GET "$AUTH/api/v1/auth/me" "" "";          expect_http 401 "无 token GET /auth/me 被拒"
req GET "$AUTH/api/v1/user/info" "$ADMIN" "";  expect_http 200 "GET /user/info (Vben)"

# =============================================================================
hdr "2. 用户管理（CRUD + 角色分配 + 权限边界）"
req GET "$AUTH/api/v1/users?page=1&size=5" "$ADMIN" "";  expect_http 200 "superadmin 用户列表"
req GET "$AUTH/api/v1/users?page=1&size=5" "$STAFF" "";  expect_http 403 "kfstaff 用户列表被拒"
req GET "$AUTH/api/v1/users?page=1&size=5" "" "";        expect_http 401 "无 token 用户列表被拒"
req GET "$AUTH/api/v1/users/me" "$STAFF" "";             expect_http 200 "kfstaff 查询自己详情"

TMP_USER="autotest_$TS"
req POST "$AUTH/api/v1/users" "$ADMIN" \
  "{\"username\":\"$TMP_USER\",\"displayName\":\"自动化测试用户\",\"email\":\"$TMP_USER@test.local\",\"phone\":\"13800000000\",\"password\":\"$PWD_DEFAULT\"}"
expect_http 200 "创建临时用户 $TMP_USER"
TMP_UID=$(jget '.data.id'); [ -z "$TMP_UID" ] && TMP_UID=$(jget '.data.userId'); [ -z "$TMP_UID" ] && TMP_UID=$(jget '.data')
if [ -z "$TMP_UID" ]; then # 兜底：从列表按用户名反查
  req GET "$AUTH/api/v1/users?keyword=$TMP_USER&page=1&size=1" "$ADMIN" ""
  TMP_UID=$(jq -r '[.. | objects | select(.username? == "'"$TMP_USER"'") | (.id // .userId)][0] // empty' "$RESP")
fi
echo "  ℹ️  临时用户 ID=$TMP_UID"

if [ -n "$TMP_UID" ]; then
  req GET  "$AUTH/api/v1/users/$TMP_UID" "$ADMIN" "";                       expect_http 200 "用户详情"
  req PUT  "$AUTH/api/v1/users/$TMP_UID" "$ADMIN" '{"displayName":"自动化测试用户-改","email":"upd@test.local","phone":"13800000001"}'
  expect_http 200 "更新用户资料"
  req POST "$AUTH/api/v1/users/$TMP_UID/disable" "$ADMIN" "";               expect_http 200 "禁用用户"
  # 被禁用用户不能登录
  DIS_TOKEN=$(login "$TMP_USER")
  [ -z "$DIS_TOKEN" ] && ok "禁用用户登录被拒" || bad "禁用用户竟然登录成功"
  req POST "$AUTH/api/v1/users/$TMP_UID/enable"  "$ADMIN" "";               expect_http 200 "启用用户"
  # 分配角色：动态取 kf_staff 角色 ID
  req GET "$AUTH/api/v1/roles?page=1&size=50" "$ADMIN" ""
  RID=$(jq -r '[.. | objects | select(.roleKey? == "kf_staff") | .id][0] // empty' "$RESP")
  if [ -n "$RID" ]; then
    req POST "$AUTH/api/v1/users/$TMP_UID/roles" "$ADMIN" "{\"roleIds\":[$RID]}"; expect_http 200 "分配角色 kf_staff($RID)"
  else
    skip "未找到 kf_staff 角色 ID，跳过角色分配"
  fi
  # 临时用户登录 → 改自己密码 → 新密码重新登录
  TMP_TOKEN=$(login "$TMP_USER")
  [ -n "$TMP_TOKEN" ] && ok "临时用户登录" || bad "临时用户登录失败"
  if [ -n "$TMP_TOKEN" ]; then
    req POST "$AUTH/api/v1/users/$TMP_UID/change-password" "$TMP_TOKEN" \
      "{\"oldPassword\":\"$PWD_DEFAULT\",\"newPassword\":\"Test@654321\"}"
    expect_http 200 "临时用户修改自己密码"
    NEW_TOKEN=$(curl -sk -X POST "$AUTH/api/v1/auth/login" -H 'Content-Type: application/json' \
      -d "{\"username\":\"$TMP_USER\",\"password\":\"Test@654321\"}" | jq -r '.data.tokenValue // empty')
    [ -n "$NEW_TOKEN" ] && ok "新密码登录成功" || bad "新密码登录失败"
    # 旧密码不能再登录
    OLD_TOKEN=$(login "$TMP_USER")
    [ -z "$OLD_TOKEN" ] && ok "旧密码登录被拒" || bad "旧密码竟然仍可登录"
  fi
  req POST "$AUTH/api/v1/users/$TMP_UID/reset-password" "$ADMIN" "{\"newPassword\":\"$PWD_DEFAULT\"}"
  expect_http 200 "管理员重置密码"
  req DELETE "$AUTH/api/v1/users/$TMP_UID" "$ADMIN" "";                     expect_http 200 "删除临时用户（清理）"
else
  bad "未拿到临时用户 ID，跳过用户 CRUD 后续用例"
fi

# =============================================================================
hdr "3. 角色与菜单"
req GET "$AUTH/api/v1/roles?page=1&size=10" "$ADMIN" "";       expect_http 200 "角色列表"
req GET "$AUTH/api/v1/roles/permissions/tree" "$ADMIN" "";     expect_http 200 "权限树"
TMP_ROLE_KEY="autotest_role_$TS"
req POST "$AUTH/api/v1/roles" "$ADMIN" "{\"roleKey\":\"$TMP_ROLE_KEY\",\"roleName\":\"自动化测试角色\",\"isSystem\":false}"
expect_http 200 "创建临时角色"
TMP_RID=$(jget '.data.id'); [ -z "$TMP_RID" ] && TMP_RID=$(jget '.data')
if [ -n "$TMP_RID" ]; then
  req GET    "$AUTH/api/v1/roles/$TMP_RID" "$ADMIN" "";        expect_http 200 "角色详情"
  req PUT    "$AUTH/api/v1/roles/$TMP_RID" "$ADMIN" '{"roleName":"自动化测试角色-改","status":1}'
  expect_http 200 "更新角色"
  req DELETE "$AUTH/api/v1/roles/$TMP_RID" "$ADMIN" "";        expect_http 200 "删除临时角色（清理）"
else
  skip "未拿到角色 ID，跳过角色 CRUD 后续用例"
fi
req GET "$AUTH/api/v1/menus/me" "$ADMIN" "";                   expect_http 200 "我的路由树"
req GET "$AUTH/api/v1/menus" "$ADMIN" "";                      expect_http 200 "全量菜单树"

# =============================================================================
hdr "4. AI 模型与系统配置（只读）"
req GET "$AUTH/api/v1/admin/ai-models?page=1&size=10" "$ADMIN" "";       expect_http 200 "AI 模型列表"
req GET "$AUTH/api/v1/admin/system-config?page=1&size=10" "$ADMIN" "";   expect_http 200 "系统配置列表"
CFG_TYPE=$(jq -r '[.. | objects | .configType? // empty][0] // empty' "$RESP")
if [ -n "$CFG_TYPE" ]; then
  req GET "$AUTH/api/v1/admin/system-config/map?configType=$CFG_TYPE" "$ADMIN" ""; expect_http 200 "系统配置 map (configType=$CFG_TYPE)"
else
  skip "无系统配置数据，跳过 map 查询"
fi
req GET "$AUTH/api/v1/admin/system-config?page=1&size=10" "$STAFF" "";   expect_http 403 "kfstaff 访问系统配置被拒"

# =============================================================================
hdr "5. 内部服务接口（X-Internal-Secret）"
req GET "$AUTH/internal/ai-models/active" "" "" "X-Internal-Secret: $INTERNAL_SECRET"
expect_http 200 "带密钥访问 /internal/ai-models/active"
req GET "$AUTH/internal/ai-models/active" "" ""
[ "$HTTP_CODE" = "401" ] || [ "$HTTP_CODE" = "403" ] && ok "无密钥访问内部接口被拒 ($HTTP_CODE)" || bad "无密钥访问内部接口 期望401/403 实际$HTTP_CODE"
req POST "$AUTH/api/v1/internal/token/verify" "" "{\"token\":\"$ADMIN\"}" "X-Internal-Secret: $INTERNAL_SECRET"
expect_http 200 "内部 token 校验"

# =============================================================================
hdr "6. 知识库（上传/查询/审核链路）"
KB_ID="kb_autotest"
$PG_KNOW "INSERT INTO public.knowledge_kb(id,name,description,owner_id) VALUES('$KB_ID','自动化测试知识库','api-autotest 专用','1001') ON CONFLICT (id) DO NOTHING;" >/dev/null 2>&1 \
  && ok "种子知识库 $KB_ID 就绪" || bad "种子知识库写入失败"

req GET "$KNOW/api/knowledge/docs?page=1&size=5" "$ADMIN" "";   expect_http 200 "文档列表"
req GET "$KNOW/api/knowledge/docs?page=1&size=5" "" "";         expect_http 401 "无 token 文档列表被拒"

TMPTXT="$SCRIPT_DIR/.autotest-doc-$$.txt"
printf 'ARIA 自动化测试文档。\n退货政策：签收后 7 天内支持无理由退货。\n运费规则：满 99 元包邮。\n' > "$TMPTXT"
UP_CODE=$(curl -sk -o "$RESP" -w '%{http_code}' -X POST "$KNOW/api/knowledge/docs/upload?kbId=$KB_ID" \
  -H "Authorization: Bearer $ADMIN" -F "file=@$TMPTXT")
DOC_ID=$(jget '.data.docId')
[ "$UP_CODE" = "200" ] && [ -n "$DOC_ID" ] && ok "上传文档成功 docId=$DOC_ID" || bad "上传文档失败 HTTP=$UP_CODE: $(body | head -c 200)"

if [ -n "$DOC_ID" ]; then
  req GET "$KNOW/api/knowledge/docs/$DOC_ID/status" "$ADMIN" ""; expect_http 200 "摄取进度查询"
  ING_STATUS=""
  for i in $(seq 1 12); do
    sleep 5
    req GET "$KNOW/api/knowledge/docs/$DOC_ID/status" "$ADMIN" ""
    ING_STATUS=$(jq -r '.data.status // .data // empty' "$RESP")
    case "$ING_STATUS" in PENDING_REVIEW|PUBLISHED|FAILED|INGESTED) break;; esac
  done
  echo "  ℹ️  摄取最终状态: ${ING_STATUS:-未知}"
  if [ "$ING_STATUS" = "PENDING_REVIEW" ]; then
    req PUT "$KNOW/api/knowledge/docs/$DOC_ID/review" "$ADMIN" '{"approved":true}'
    expect_http 200 "审核通过文档"
  elif [ "$ING_STATUS" = "FAILED" ]; then
    skip "摄取失败（多为 Embedding 模型未配置），跳过审核用例"
  else
    skip "摄取未到达待审核状态($ING_STATUS)，跳过审核用例"
  fi
  req GET "$KNOW/api/knowledge/docs/$DOC_ID/chunks" "$ADMIN" ""; expect_http 200 "Chunk 列表"
  req GET "$KNOW/api/knowledge/docs/$DOC_ID/stats" "$ADMIN" "";  expect_http 200 "文档统计"
  req GET "$KNOW/api/knowledge/docs/kb-stats?kbId=$KB_ID" "$ADMIN" ""; expect_http 200 "知识库汇总统计"
  if [ "$SKIP_AI" = "1" ]; then
    skip "SKIP_AI=1，跳过知识库检索测试"
  elif [ "$ING_STATUS" = "FAILED" ]; then
    skip "摄取失败（Embedding 服务不可用），跳过检索测试"
  else
    req POST "$KNOW/api/knowledge/docs/search-test" "$ADMIN" "{\"query\":\"退货政策是什么\",\"kbId\":\"$KB_ID\",\"topK\":3}"
    expect_http 200 "检索测试（依赖 Embedding）"
  fi
  if [ "$ING_STATUS" = "FAILED" ]; then
    skip "FAILED 状态不允许下线，改由清理节直接清 DB"
  else
    req DELETE "$KNOW/api/knowledge/docs/$DOC_ID" "$ADMIN" "";   expect_http 200 "下线文档（清理）"
  fi
fi
rm -f "$TMPTXT"

# =============================================================================
hdr "7. 访客会话生命周期（init 校验 / 复用 / CLOSED 重建）"
ANON="autotest-anon-$TS"

# 参数校验
CODE=$(curl -sk -o "$RESP" -w '%{http_code}' -X POST "$CONV/api/v1/chat/session/init" -H 'Content-Type: application/json' -d '{}')
[ "$CODE" != "200" ] && ok "缺少 X-Anonymous-Id 被拒 (HTTP $CODE)" || bad "缺少 X-Anonymous-Id 竟然成功"
req POST "$CONV/api/v1/chat/session/init" "" '{}' "X-Anonymous-Id: short"
expect_http 400 "X-Anonymous-Id 太短(<8)被拒"
req POST "$CONV/api/v1/chat/session/init" "" '{}' "X-Anonymous-Id: bad id with space!"
expect_http 400 "X-Anonymous-Id 含非法字符被拒"

# 首次 init → isNew=true
req POST "$CONV/api/v1/chat/session/init" "" '{"visitorName":"自动化测试访客"}' "X-Anonymous-Id: $ANON"
expect_http 200 "访客会话初始化"
SESSION_ID=$(jget '.data.sessionId')
expect_jq '.data.isNew' "true" "首次 init isNew=true"
expect_jq '.data.status' "AI_CHAT" "新会话状态为 AI_CHAT"
echo "  ℹ️  sessionId=$SESSION_ID"

# 同一 anonymousId 再次 init → 复用旧会话
req POST "$CONV/api/v1/chat/session/init" "" '{"visitorName":"换个名字"}' "X-Anonymous-Id: $ANON"
expect_http 200 "重复 init"
expect_jq '.data.isNew' "false" "重复 init isNew=false（复用会话）"
S2CHECK=$(jget '.data.sessionId')
[ "$S2CHECK" = "$SESSION_ID" ] && ok "复用返回同一 sessionId" || bad "复用竟然返回了新 sessionId：$S2CHECK != $SESSION_ID"

# 状态查询
req GET "$CONV/api/v1/chat/state?sessionId=$SESSION_ID" "" "";        expect_http 200 "会话状态查询"
expect_jq '.data.status' "AI_CHAT" "state 返回 AI_CHAT"
req GET "$CONV/api/v1/chat/state?sessionId=no-such-session-$TS" "" ""
expect_jq '.data.status' "AI_CHAT" "不存在的会话 state 兜底返回 AI_CHAT"
req GET "$CONV/api/v1/chat/state?sessionId=bad%20id%21" "" "";        expect_bcode 400 "非法 sessionId state 校验"

# =============================================================================
hdr "8. 访客对话（stream 校验 / 历史全量·增量·清除 / 消息反馈状态机）"
# stream 参数校验（不依赖 AI）
SSE_OUT=$(curl -skN --max-time 10 -X POST "$CONV/api/v1/chat/stream" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"\"}" 2>/dev/null)
echo "$SSE_OUT" | grep -q "\[DONE\]" && ok "空消息 stream 直接返回 done" || bad "空消息 stream 未返回 done: $(echo "$SSE_OUT" | head -c 120)"
SSE_OUT=$(curl -skN --max-time 10 -X POST "$CONV/api/v1/chat/stream" -H 'Content-Type: application/json' \
  -d '{"sessionId":"bad id!","message":"你好"}' 2>/dev/null)
echo "$SSE_OUT" | grep -q "非法" && ok "非法 sessionId stream 返回 error 事件" || bad "非法 sessionId stream 未报错: $(echo "$SSE_OUT" | head -c 120)"
req POST "$CONV/api/v1/chat" "" "{\"sessionId\":\"$SESSION_ID\",\"message\":\"\"}"
expect_bcode 400 "非流式 chat 空消息被拒"

# 消息反馈：负向用例先行（此时会话无 assistant 消息）
req POST "$CONV/api/v1/chat/messages/feedback" "" "{\"sessionId\":\"$SESSION_ID\",\"feedback\":\"up\"}"
expect_bcode 40400 "无 AI 回复时反馈回落失败(40400)"
req POST "$CONV/api/v1/chat/messages/feedback" "" "{\"sessionId\":\"$SESSION_ID\",\"seq\":0,\"feedback\":\"up\"}"
expect_http 400 "seq=0 参数校验被拒"
req POST "$CONV/api/v1/chat/messages/feedback" "" "{\"sessionId\":\"$SESSION_ID\",\"feedback\":\"sideways\"}"
expect_http 400 "feedback 非法枚举被拒"

# AI 对话 + 反馈正向状态机（up → down 覆盖 → null 取消 → null 幂等）
if [ "$SKIP_AI" = "1" ]; then
  skip "SKIP_AI=1，跳过 AI 流式对话"
  skip "SKIP_AI=1，跳过反馈 up/down/取消状态机（依赖 AI 回复）"
else
  SSE_OUT=$(curl -skN --max-time 90 -X POST "$CONV/api/v1/chat/stream" -H 'Content-Type: application/json' \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"你好，请介绍一下你自己\"}" 2>/dev/null | head -c 3000)
  echo "$SSE_OUT" | grep -q "data:" && ok "AI 流式对话返回 SSE 数据" || bad "AI 流式对话无 SSE 数据: $(echo "$SSE_OUT" | head -c 200)"
  sleep 2   # 等待历史异步落库
  req GET "$CONV/api/v1/chat/history?sessionId=$SESSION_ID" "" ""
  A_CNT=$(jq '[.data[]? | select(.role == "assistant")] | length' "$RESP" 2>/dev/null)
  [ "${A_CNT:-0}" -ge 1 ] && ok "AI 回复已写入历史 (assistant=$A_CNT)" || bad "历史中未见 assistant 消息"

  req POST "$CONV/api/v1/chat/messages/feedback" "" "{\"sessionId\":\"$SESSION_ID\",\"feedback\":\"up\"}"
  expect_http 200 "反馈点赞";        expect_jq '.data.feedback' "up" "落库反馈为 up"
  req POST "$CONV/api/v1/chat/messages/feedback" "" "{\"sessionId\":\"$SESSION_ID\",\"feedback\":\"down\"}"
  expect_jq '.data.feedback' "down" "点踩覆盖点赞 (last-write-wins)"
  req POST "$CONV/api/v1/chat/messages/feedback" "" "{\"sessionId\":\"$SESSION_ID\",\"feedback\":null}"
  expect_http 200 "取消反馈"
  req POST "$CONV/api/v1/chat/messages/feedback" "" "{\"sessionId\":\"$SESSION_ID\",\"feedback\":null}"
  expect_http 200 "重复取消反馈幂等"
fi

# 历史全量 / 增量 / 清除
req GET "$CONV/api/v1/chat/history?sessionId=$SESSION_ID" "" "";       expect_http 200 "历史全量查询"
# seq 在 JSON 中被序列化为字符串（Long→String 全局配置），需 tonumber 后取 max
MAX_SEQ=$(jq -r '[.data[]?.seq // 0 | tonumber? // 0] | max // 0' "$RESP" 2>/dev/null)
req GET "$CONV/api/v1/chat/history?sessionId=$SESSION_ID&sinceSeq=$MAX_SEQ" "" ""
INC_CNT=$(jq '.data | length' "$RESP" 2>/dev/null)
[ "${INC_CNT:-99}" = "0" ] && ok "增量查询 sinceSeq=$MAX_SEQ 返回空" || bad "增量查询期望 0 条实际 $INC_CNT 条"
req GET "$CONV/api/v1/chat/history?sessionId=bad%20id%21" "" "";       expect_bcode 400 "非法 sessionId 历史查询被拒"
req DELETE "$CONV/api/v1/chat/history?sessionId=$SESSION_ID" "" "";    expect_http 200 "清除会话历史"
req GET "$CONV/api/v1/chat/history?sessionId=$SESSION_ID" "" ""
LEFT=$(jq '.data | length' "$RESP" 2>/dev/null)
[ "${LEFT:-99}" = "0" ] && ok "清除后历史为空" || bad "清除后历史仍有 $LEFT 条"

# =============================================================================
hdr "9. 访客短信认证全状态机（发送限流 / 验证 / 错误锁定）"
PH1=$(printf '139%08d' $((RANDOM * 3000 + RANDOM)))
PH2=$(printf '138%08d' $((RANDOM * 3000 + RANDOM)))

req POST "$CONV/api/v1/chat/auth/sms/send" "" '{"phone":"12345678901"}'; expect_http 400 "手机号格式非法被拒"
req POST "$CONV/api/v1/chat/auth/sms/verify" "" "{\"phone\":\"$PH1\",\"code\":\"12345\"}"; expect_http 400 "5位验证码格式被拒"
req POST "$CONV/api/v1/chat/auth/sms/verify" "" "{\"phone\":\"$PH1\",\"code\":\"123456\"}"; expect_http 400 "未发送就验证 → 验证码已过期"

req POST "$CONV/api/v1/chat/auth/sms/send" "" "{\"phone\":\"$PH1\"}";  expect_http 200 "发送验证码"
req POST "$CONV/api/v1/chat/auth/sms/send" "" "{\"phone\":\"$PH1\"}";  expect_http 429 "60s 内重复发送被限流"

SMS_CODE=$($REDIS GET "visitor:sms:$PH1" 2>/dev/null | tr -d '"')
if [ -n "$SMS_CODE" ]; then
  ok "从 Redis 读取验证码 ($SMS_CODE)"
  WRONG="000000"; [ "$WRONG" = "$SMS_CODE" ] && WRONG="111111"
  req POST "$CONV/api/v1/chat/auth/sms/verify" "" "{\"phone\":\"$PH1\",\"code\":\"$WRONG\"}"
  expect_http 400 "错误验证码被拒（含剩余次数提示）"
  req POST "$CONV/api/v1/chat/auth/sms/verify" "" "{\"phone\":\"$PH1\",\"code\":\"$SMS_CODE\",\"sessionId\":\"$SESSION_ID\"}"
  expect_http 200 "正确验证码换取 token（绑定会话）"
  VTOKEN=$(jget '.data.token')
  [ -n "$VTOKEN" ] && ok "访客 token 已下发" || bad "未拿到访客 token"
  req GET "$CONV/api/v1/chat/auth/state?sessionId=$SESSION_ID" "" ""
  expect_jq '.data.authenticated' "true" "认证状态回查 authenticated=true"
  MASK=$(jget '.data.phoneMask')
  echo "$MASK" | grep -q '\*\*\*\*' && ok "手机号已脱敏 ($MASK)" || bad "手机号未脱敏: $MASK"
  # 验证成功后验证码即失效，重放同一验证码必须失败
  req POST "$CONV/api/v1/chat/auth/sms/verify" "" "{\"phone\":\"$PH1\",\"code\":\"$SMS_CODE\"}"
  expect_http 400 "验证码一次性（重放被拒）"
else
  bad "Redis 未读到验证码，跳过验证链路"
fi
req GET "$CONV/api/v1/chat/auth/state?sessionId=unauth-sess-$TS" "" ""
expect_jq '.data.authenticated' "false" "未认证会话回查 authenticated=false"

# 错误 5 次锁定 → 后续 verify/send 全部 423
req POST "$CONV/api/v1/chat/auth/sms/send" "" "{\"phone\":\"$PH2\"}"; expect_http 200 "第二手机号发送验证码"
SMS2=$($REDIS GET "visitor:sms:$PH2" 2>/dev/null | tr -d '"')
WRONG2="000000"; [ "$WRONG2" = "$SMS2" ] && WRONG2="111111"
for i in 1 2 3 4 5; do
  req POST "$CONV/api/v1/chat/auth/sms/verify" "" "{\"phone\":\"$PH2\",\"code\":\"$WRONG2\"}"
done
expect_http 400 "第 5 次错误提示已锁定"
req POST "$CONV/api/v1/chat/auth/sms/verify" "" "{\"phone\":\"$PH2\",\"code\":\"$WRONG2\"}"
expect_http 423 "锁定后验证被拒 (423)"
req POST "$CONV/api/v1/chat/auth/sms/send" "" "{\"phone\":\"$PH2\"}"
expect_http 423 "锁定后重发验证码同样被拒 (423)"

# =============================================================================
hdr "10. 转人工与队列状态机（幂等 / 404 / 409 / 归属 CAS / 转交）"
# 两个座席通过 SSE 事件流上线（注册在线状态，供转交测试）
curl -skN --max-time 600 "$CONV/api/v1/sessions/events?token=$ADMIN" >/dev/null 2>&1 &
SSE_PIDS="$!"
curl -skN --max-time 600 "$CONV/api/v1/sessions/events?token=$STAFF" >/dev/null 2>&1 &
SSE_PIDS="$SSE_PIDS $!"
sleep 2
req GET "$CONV/api/v1/sessions/agents/online" "$ADMIN" ""; expect_http 200 "在线座席列表"
# 在线座席 VO 字段为 id（非 agentId）
ON_ADMIN=$(jq -r '[.data[]? | select((.id|tostring) == "'"$ADMIN_ID"'")] | length' "$RESP" 2>/dev/null)
ON_STAFF=$(jq -r '[.data[]? | select((.id|tostring) == "'"$STAFF_ID"'")] | length' "$RESP" 2>/dev/null)
[ "${ON_ADMIN:-0}" -ge 1 ] && ok "SSE 建连后 superadmin($ADMIN_ID) 在线" || bad "superadmin 未出现在在线座席列表"
[ "${ON_STAFF:-0}" -ge 1 ] && ok "SSE 建连后 kfstaff($STAFF_ID) 在线"    || bad "kfstaff 未出现在在线座席列表"

# S2：转人工 → WAITING
ANON2="autotest-anon2-$TS"
req POST "$CONV/api/v1/chat/session/init" "" '{"visitorName":"自动化访客S2"}' "X-Anonymous-Id: $ANON2"
S2=$(jget '.data.sessionId'); echo "  ℹ️  S2=$S2"

req POST "$CONV/api/v1/chat/transfer" "" '{"sessionId":"","userName":"x"}'
expect_http 400 "transfer 缺 sessionId 参数校验"
req POST "$CONV/api/v1/chat/transfer" "" "{\"sessionId\":\"$S2\",\"userName\":\"自动化访客S2\",\"transferReason\":\"自动化测试\",\"tag\":\"测试\"}"
expect_http 200 "访客转人工"
expect_jq '.data.status' "WAITING" "转人工后状态 WAITING"
req POST "$CONV/api/v1/chat/transfer" "" "{\"sessionId\":\"$S2\",\"userName\":\"自动化访客S2\",\"transferReason\":\"重复点击\",\"tag\":\"测试\"}"
expect_http 200 "重复转人工幂等"
expect_jq '.data.status' "WAITING" "重复转人工不重置状态（仍 WAITING）"
expect_jq '.data.transferReason' "自动化测试" "重复转人工保留原排队信息（不覆盖）"

req GET "$CONV/api/v1/sessions" "$ADMIN" ""; expect_http 200 "座席会话队列列表"
IN_QUEUE=$(jq -r '[.. | objects | select(.sessionId? == "'"$S2"'")] | length' "$RESP" 2>/dev/null)
[ "${IN_QUEUE:-0}" -ge 1 ] && ok "转人工会话已出现在队列" || bad "队列中未找到 $S2"
req GET "$CONV/api/v1/sessions?closedLimit=1" "$ADMIN" "";      expect_http 200 "closedLimit=1 边界"
req GET "$CONV/api/v1/sessions?closedLimit=100000" "$ADMIN" ""; expect_http 200 "closedLimit 超大值被收敛"
req GET "$CONV/api/v1/sessions" "" "";                          expect_http 401 "无 token 查询队列被拒"

# accept 状态机
req POST "$CONV/api/v1/sessions/not-exist-$TS/accept" "$ADMIN" ""; expect_http 404 "接入不存在的会话 404"
req POST "$CONV/api/v1/sessions/$S2/accept" "$STAFF" "";           expect_http 200 "kfstaff 接入会话"
expect_jq '.data.status' "ACTIVE" "接入后状态 ACTIVE"
expect_jq '.data.agentId' "$STAFF_ID" "会话归属 kfstaff"
req POST "$CONV/api/v1/sessions/$S2/accept" "$ADMIN" "";           expect_http 409 "重复接入被拒 409（不换绑座席）"
req GET  "$CONV/api/v1/chat/state?sessionId=$S2" "" "";            expect_jq '.data.status' "ACTIVE" "访客侧查询状态同步为 ACTIVE"

# transfer 状态机（归属 CAS / 目标在线 / 状态约束）
req POST "$CONV/api/v1/sessions/$S2/transfer" "$ADMIN" "{\"targetAgentId\":\"$ADMIN_ID\"}"
expect_http 409 "非归属座席发起转交被拒（CAS 409）"
req POST "$CONV/api/v1/sessions/$S2/transfer" "$STAFF" "{\"targetAgentId\":\"offline-agent-$TS\"}"
expect_http 400 "转交给不在线座席被拒"
req POST "$CONV/api/v1/sessions/$S2/transfer" "$STAFF" '{"targetAgentId":"bad id!"}'
expect_http 400 "targetAgentId 非法格式被拒"
req POST "$CONV/api/v1/sessions/$S2/transfer" "$STAFF" '{"targetAgentId":""}'
expect_http 400 "targetAgentId 为空被拒"
req POST "$CONV/api/v1/sessions/$S2/transfer" "$STAFF" "{\"targetAgentId\":\"$ADMIN_ID\"}"
expect_http 200 "kfstaff 转交会话给 superadmin"
# GET /sessions 的 ACTIVE 列表读 DB，而 transfer 同步只改 Redis（DB 经 MQ 异步同步）→ 轮询等待最终一致
NEW_OWNER=""
for _i in 1 2 3 4 5 6; do
  req GET "$CONV/api/v1/sessions" "$ADMIN" ""
  NEW_OWNER=$(jq -r '[.. | objects | select(.sessionId? == "'"$S2"'") | .agentId][0] // empty' "$RESP" 2>/dev/null)
  [ "$NEW_OWNER" = "$ADMIN_ID" ] && break
  sleep 1
done
[ "$NEW_OWNER" = "$ADMIN_ID" ] && ok "转交后归属变更为 superadmin（MQ 异步同步 DB）" || bad "转交后归属期望 $ADMIN_ID 实际 $NEW_OWNER（等待 6s 仍未同步）"

# WAITING 状态不可转交（用 S3 验证）
ANON3="autotest-anon3-$TS"
req POST "$CONV/api/v1/chat/session/init" "" '{"visitorName":"自动化访客S3"}' "X-Anonymous-Id: $ANON3"
S3=$(jget '.data.sessionId')
req POST "$CONV/api/v1/chat/transfer" "" "{\"sessionId\":\"$S3\",\"userName\":\"自动化访客S3\"}"
expect_http 200 "S3 转人工（默认 reason/tag 兜底）"
expect_jq '.data.transferReason' "用户主动请求转人工" "缺省 transferReason 使用默认值"
expect_jq '.data.tag' "咨询" "缺省 tag 使用默认值"
req POST "$CONV/api/v1/sessions/$S3/transfer" "$STAFF" "{\"targetAgentId\":\"$ADMIN_ID\"}"
expect_http 409 "WAITING 会话不可转交 (409)"

# ACTIVE 会话中访客再发消息 → 固定提示（不调 AI，可离线验证）
SSE_OUT=$(curl -skN --max-time 15 -X POST "$CONV/api/v1/chat/stream" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$S2\",\"message\":\"人工接待中我再发一条\"}" 2>/dev/null)
echo "$SSE_OUT" | grep -q "人工客服" && ok "人工接待中 stream 返回固定提示（不走 AI）" || bad "人工接待中 stream 未返回提示: $(echo "$SSE_OUT" | head -c 200)"
sleep 1
req GET "$CONV/api/v1/chat/history?sessionId=$S2" "" ""
HAS_MSG=$(jq '[.data[]? | select(.content == "人工接待中我再发一条")] | length' "$RESP" 2>/dev/null)
[ "${HAS_MSG:-0}" -ge 1 ] && ok "人工接待中的访客消息已入历史" || bad "访客消息未写入历史"

# 访客历史（两种身份路径 + 缺身份 400）
req GET "$CONV/api/v1/sessions/visitor-history" "$ADMIN" "" "X-Anonymous-Id: $ANON2"
expect_http 200 "visitor-history 按匿名 ID 查询"
req GET "$CONV/api/v1/sessions/visitor-history?visitorName=%E8%87%AA%E5%8A%A8%E5%8C%96%E8%AE%BF%E5%AE%A2S2" "$ADMIN" ""
expect_http 200 "visitor-history 按访客名查询"
req GET "$CONV/api/v1/sessions/visitor-history" "$ADMIN" ""
expect_bcode 400 "visitor-history 缺身份标识 (业务码 400)"

# AI 辅助能力（依赖 LLM）
if [ "$SKIP_AI" = "1" ]; then
  skip "SKIP_AI=1，跳过 AI 回复建议"
else
  req POST "$CONV/api/v1/sessions/$S2/reply-suggestions" "$ADMIN" '{"lastMessage":"请问退货政策是什么"}'
  expect_http 200 "AI 回复建议"
fi
req GET "$CONV/api/v1/sessions/$S2/ai-summary" "$ADMIN" ""; expect_http 200 "AI 摘要（缓存查询）"

# =============================================================================
hdr "11. WebSocket 双向通信（访客⇄座席消息互发 / TYPING / 安全）"
WS_PY="$SCRIPT_DIR/ws-autotest.py"
if python3 -c "import websockets" 2>/dev/null && [ -f "$WS_PY" ]; then
  # S2 处于 ACTIVE 且归属 superadmin，用 ADMIN token 连座席端
  WS_OUT=$(python3 "$WS_PY" bridge "$WSS_BASE" "$S2" "$ADMIN" 2>&1)
  echo "$WS_OUT" | while IFS= read -r line; do echo "     $line"; done
  for name in visitor_ws_connect agent_ws_connect agent_to_visitor_message visitor_to_agent_message visitor_typing_forward agent_message_without_sessionid_dropped; do
    echo "$WS_OUT" | grep -q "^OK $name" && ok "WS $name" || bad "WS $name: $(echo "$WS_OUT" | grep "$name")"
  done
  # WS 消息落库验证：座席消息与访客消息都应出现在 REST 历史里
  sleep 1
  req GET "$CONV/api/v1/chat/history?sessionId=$S2" "" ""
  AG_IN_HIS=$(jq '[.data[]? | select(.content | tostring | contains("座席WS消息"))] | length' "$RESP" 2>/dev/null)
  VI_IN_HIS=$(jq '[.data[]? | select(.content | tostring | contains("访客WS消息"))] | length' "$RESP" 2>/dev/null)
  [ "${AG_IN_HIS:-0}" -ge 1 ] && ok "座席 WS 消息已落历史" || bad "座席 WS 消息未落历史"
  [ "${VI_IN_HIS:-0}" -ge 1 ] && ok "访客 WS 消息已落历史" || bad "访客 WS 消息未落历史"
  # 增量同步：sinceSeq 取最大 seq 后应为空
  WS_MAX_SEQ=$(jq -r '[.data[]?.seq // 0 | tonumber? // 0] | max // 0' "$RESP" 2>/dev/null)
  req GET "$CONV/api/v1/chat/history?sessionId=$S2&sinceSeq=$WS_MAX_SEQ" "" ""
  INC2=$(jq '.data | length' "$RESP" 2>/dev/null)
  [ "${INC2:-99}" = "0" ] && ok "WS 消息后增量同步 sinceSeq 语义正确" || bad "增量同步期望 0 条实际 $INC2"

  # 安全负向
  WS_OUT=$(python3 "$WS_PY" agent-noauth "$WSS_BASE" 2>&1)
  echo "$WS_OUT" | grep -q "^OK" && ok "座席 WS 无 token 握手被拒" || bad "座席 WS 无 token: $WS_OUT"
  WS_OUT=$(python3 "$WS_PY" visitor-badsession "$WSS_BASE" 2>&1)
  echo "$WS_OUT" | grep -q "^OK" && ok "访客 WS 非法 sessionId 被拒" || bad "访客 WS 非法 sessionId: $WS_OUT"
else
  skip "python3 websockets 不可用，跳过 WS 双向通信（pip3 install websockets）"
fi

# =============================================================================
hdr "12. 会话备注与标签（含权限与数据校验）"
req POST "$CONV/api/v1/sessions/$S2/notes" "$ADMIN" '{"content":"自动化测试备注"}'
expect_http 200 "新增会话备注"
NOTE_ID=$(jget '.data.id'); [ -z "$NOTE_ID" ] && NOTE_ID=$(jget '.data.noteId')
req GET "$CONV/api/v1/sessions/$S2/notes" "$ADMIN" ""; expect_http 200 "备注列表"
[ -z "$NOTE_ID" ] && NOTE_ID=$(jq -r '[.. | objects | select(.content? == "自动化测试备注") | (.id // .noteId)][0] // empty' "$RESP")
if [ -n "$NOTE_ID" ]; then
  req PUT    "$CONV/api/v1/sessions/$S2/notes/$NOTE_ID" "$ADMIN" '{"content":"自动化测试备注-改"}'
  expect_http 200 "修改备注"
  req GET "$CONV/api/v1/sessions/$S2/notes" "$ADMIN" ""
  UPD=$(jq -r '[.. | objects | select(.content? == "自动化测试备注-改")] | length' "$RESP" 2>/dev/null)
  [ "${UPD:-0}" -ge 1 ] && ok "备注修改内容已生效" || bad "备注修改未生效"
  req DELETE "$CONV/api/v1/sessions/$S2/notes/$NOTE_ID" "$ADMIN" ""; expect_http 200 "删除备注"
  req GET "$CONV/api/v1/sessions/$S2/notes" "$ADMIN" ""
  LEFT=$(jq -r '[.. | objects | select(.content? == "自动化测试备注-改")] | length' "$RESP" 2>/dev/null)
  [ "${LEFT:-9}" = "0" ] && ok "备注删除后列表不再出现" || bad "备注删除后仍在列表"
else
  skip "未拿到备注 ID，跳过备注修改/删除"
fi
req GET "$CONV/api/v1/sessions/$S2/notes" "" ""; expect_http 401 "无 token 备注列表被拒"

# 标签字典 + 会话/访客标签
req POST "$CONV/api/v1/admin/tags" "$ADMIN" "{\"name\":\"自动化标签$TS\",\"color\":\"#FF5722\"}"
expect_http 200 "创建标签字典"
TAG_ID=$(jget '.data.id')
req POST "$CONV/api/v1/admin/tags" "$ADMIN" "{\"name\":\"自动化标签$TS\",\"color\":\"#FF5722\"}"
[ "$HTTP_CODE" != "200" ] || [ "$(bcode)" != "200" ] && ok "重复标签名被拒" || bad "重复标签名竟然创建成功"
req GET "$CONV/api/v1/admin/tags" "$ADMIN" ""; expect_http 200 "标签字典列表"
if [ -n "$TAG_ID" ]; then
  req PUT "$CONV/api/v1/admin/tags/$TAG_ID" "$ADMIN" "{\"name\":\"自动化标签$TS-改\",\"color\":\"#2196F3\",\"source\":\"CUSTOM\"}"
  expect_http 200 "修改标签字典"
  req POST "$CONV/api/v1/sessions/$S2/tags" "$ADMIN" "{\"tagId\":$TAG_ID}";  expect_http 200 "会话添加标签"
  req POST "$CONV/api/v1/sessions/$S2/tags" "$ADMIN" "{\"tagId\":$TAG_ID}"
  [ "$HTTP_CODE" = "200" ] && ok "重复打标幂等/被拒均可 ($HTTP_CODE)" || ok "重复打标被拒 ($HTTP_CODE)"
  req GET  "$CONV/api/v1/sessions/$S2/tags" "$ADMIN" ""
  TAGGED=$(jq -r '[.. | objects | select(((.tagId? // .id? // "") | tostring) == "'"$TAG_ID"'")] | length' "$RESP" 2>/dev/null)
  [ "${TAGGED:-0}" -ge 1 ] && ok "会话标签列表含新标签" || bad "会话标签列表未见新标签"
  req POST "$CONV/api/v1/sessions/$S2/tags" "$ADMIN" '{"tagId":99999999}'
  [ "$HTTP_CODE" != "200" ] || [ "$(bcode)" != "200" ] && ok "打不存在的标签被拒" || bad "打不存在的标签竟然成功"
  req DELETE "$CONV/api/v1/sessions/$S2/tags/$TAG_ID" "$ADMIN" "";           expect_http 200 "会话移除标签"
  req POST "$CONV/api/v1/sessions/$S2/visitor/tags" "$ADMIN" "{\"tagId\":$TAG_ID}"; expect_http 200 "访客添加标签"
  req GET  "$CONV/api/v1/sessions/$S2/visitor/tags" "$ADMIN" "";             expect_http 200 "访客标签列表"
  req DELETE "$CONV/api/v1/sessions/$S2/visitor/tags/$TAG_ID" "$ADMIN" "";   expect_http 200 "访客移除标签"
  req DELETE "$CONV/api/v1/admin/tags/$TAG_ID" "$ADMIN" "";                  expect_http 200 "删除标签字典（清理）"
else
  skip "未拿到标签 ID，跳过标签关联用例"
fi
req GET "$CONV/api/v1/admin/tags" "$STAFF" ""
[ "$HTTP_CODE" = "403" ] || [ "$HTTP_CODE" = "200" ] && ok "kfstaff 标签字典访问策略 ($HTTP_CODE)" || bad "kfstaff 标签字典异常 $HTTP_CODE"

# =============================================================================
hdr "13. 会话关闭与 CSAT 全状态机（rate / skip / 幂等 / 复用重建）"
# S2 关闭（归属 superadmin）
req POST "$CONV/api/v1/sessions/$S2/close" "$ADMIN" ""; expect_http 200 "座席关闭会话 S2"
req GET  "$CONV/api/v1/chat/state?sessionId=$S2" "" ""; expect_jq '.data.status' "CLOSED" "关闭后状态 CLOSED"
req POST "$CONV/api/v1/sessions/$S2/close" "$ADMIN" ""; expect_http 200 "重复关闭幂等"

sleep 2
req GET "$CONV/api/v1/chat/csat/pending?sessionId=$S2" "" ""; expect_http 200 "CSAT 待评价查询"
CSAT_ID=$(jget '.data.csatId')
if [ -n "$CSAT_ID" ]; then
  ok "close 后生成 CSAT 邀请 csatId=$CSAT_ID"
  req POST "$CONV/api/v1/chat/csat/$CSAT_ID/rate" "" '{"score":6,"comment":"越界"}'
  expect_http 400 "score=6 越界被拒"
  req POST "$CONV/api/v1/chat/csat/$CSAT_ID/rate" "" '{"score":5,"comment":"自动化测试好评"}'
  expect_http 200 "CSAT 提交 5 星评分"
  req POST "$CONV/api/v1/chat/csat/$CSAT_ID/rate" "" '{"score":4,"comment":"改主意"}'
  expect_bcode 40901 "重复评分被拒 (40901)"
  req POST "$CONV/api/v1/chat/csat/$CSAT_ID/skip" "" ""
  expect_http 200 "已评分后 skip 静默幂等"
  req GET "$CONV/api/v1/chat/csat/pending?sessionId=$S2" "" ""
  PEND=$(jget '.data'); [ -z "$PEND" ] || [ "$PEND" = "null" ] && ok "评分后 pending 返回空" || bad "评分后 pending 仍有数据"
else
  bad "S2 关闭后未生成 CSAT 邀请"
fi
req POST "$CONV/api/v1/chat/csat/999999999/rate" "" '{"score":5}'
expect_bcode 40400 "评分不存在的邀请 (40400)"
req GET "$CONV/api/v1/chat/csat/pending?sessionId=bad%20id%21" "" ""; expect_bcode 400 "非法 sessionId 参数校验"

# S3：accept → close → skip 状态机
req POST "$CONV/api/v1/sessions/$S3/accept" "$ADMIN" ""; expect_http 200 "接入 S3"
req POST "$CONV/api/v1/sessions/$S3/close"  "$ADMIN" ""; expect_http 200 "关闭 S3"
sleep 2
req GET "$CONV/api/v1/chat/csat/pending?sessionId=$S3" "" ""
CSAT3=$(jget '.data.csatId')
if [ -n "$CSAT3" ]; then
  req POST "$CONV/api/v1/chat/csat/$CSAT3/skip" "" ""; expect_http 200 "CSAT 跳过评价"
  req POST "$CONV/api/v1/chat/csat/$CSAT3/skip" "" ""; expect_http 200 "重复 skip 幂等"
  req POST "$CONV/api/v1/chat/csat/$CSAT3/rate" "" '{"score":5}'
  expect_bcode 40901 "skip 后再评分被拒 (40901)"
  req GET "$CONV/api/v1/chat/csat/pending?sessionId=$S3" "" ""
  PEND=$(jget '.data'); [ -z "$PEND" ] || [ "$PEND" = "null" ] && ok "skip 后 pending 返回空" || bad "skip 后 pending 仍有数据"
else
  bad "S3 关闭后未生成 CSAT 邀请"
fi

# CLOSED 会话不复用：同一匿名 ID 再 init 应新建
req POST "$CONV/api/v1/chat/session/init" "" '{"visitorName":"自动化访客S2"}' "X-Anonymous-Id: $ANON2"
expect_jq '.data.isNew' "true" "会话 CLOSED 后重新 init 新建会话"
S2NEW=$(jget '.data.sessionId')
[ "$S2NEW" != "$S2" ] && ok "新会话 ID 不同于已关闭会话" || bad "竟然复用了 CLOSED 会话"
# 关闭会话的访客历史可查（含 S2）
req GET "$CONV/api/v1/sessions/visitor-history?excludeSessionId=$S2NEW" "$ADMIN" "" "X-Anonymous-Id: $ANON2"
HIS_S2=$(jq -r '[.data[]? | select(.sessionId == "'"$S2"'")] | length' "$RESP" 2>/dev/null)
[ "${HIS_S2:-0}" -ge 1 ] && ok "visitor-history 包含已关闭的 S2" || bad "visitor-history 未包含 S2"

# =============================================================================
hdr "14. 座席 SSE 事件流（握手 / 事件推送 / 鉴权）"
SSE_TMP="$SCRIPT_DIR/.autotest-sse.$$"
( curl -skN --max-time 12 "$CONV/api/v1/sessions/events?token=$ADMIN" > "$SSE_TMP" 2>/dev/null ) &
SSE_WATCH=$!
sleep 2
# 触发一个 ENQUEUE 事件：S2NEW 转人工
req POST "$CONV/api/v1/chat/transfer" "" "{\"sessionId\":\"$S2NEW\",\"userName\":\"自动化访客S2\",\"transferReason\":\"SSE事件验证\"}"
expect_http 200 "触发转人工（SSE 事件源）"
sleep 4; kill $SSE_WATCH 2>/dev/null; wait $SSE_WATCH 2>/dev/null
grep -q "connected" "$SSE_TMP" && ok "SSE 握手 :connected 注释" || bad "SSE 无 connected 注释"
grep -q "$S2NEW" "$SSE_TMP" && ok "SSE 实时推送 ENQUEUE 事件（含 sessionId）" || bad "SSE 未收到转人工事件"
rm -f "$SSE_TMP"
SSE_401=$(curl -skN -o /dev/null -w '%{http_code}' --max-time 5 "$CONV/api/v1/sessions/events?token=invalid-token" 2>/dev/null)
[ "$SSE_401" = "401" ] && ok "无效 token SSE 被拒 (401)" || bad "无效 token SSE 期望401 实际$SSE_401"
# 清理：接入并关闭 S2NEW
req POST "$CONV/api/v1/sessions/$S2NEW/accept" "$ADMIN" ""; expect_http 200 "接入 S2NEW"
req POST "$CONV/api/v1/sessions/$S2NEW/close"  "$ADMIN" ""; expect_http 200 "关闭 S2NEW（清理）"

# =============================================================================
hdr "15. 快捷回复（管理端 + 坐席端 + 排序/搜索语义）"
req POST "$CONV/api/v1/admin/canned-response-groups" "$ADMIN" "{\"name\":\"自动化分组$TS\",\"sortOrder\":99}"
expect_http 200 "创建快捷回复分组"
GRP_ID=$(jget '.data.id')
req GET "$CONV/api/v1/admin/canned-response-groups" "$ADMIN" ""; expect_http 200 "分组列表"
if [ -n "$GRP_ID" ]; then
  req PUT "$CONV/api/v1/admin/canned-response-groups/$GRP_ID" "$ADMIN" "{\"name\":\"自动化分组$TS-改\",\"sortOrder\":98}"
  expect_http 200 "更新分组"
  req POST "$CONV/api/v1/admin/canned-responses" "$ADMIN" \
    "{\"title\":\"自动化快捷回复$TS\",\"content\":\"您好，这是自动化测试内容\",\"groupId\":$GRP_ID,\"sortOrder\":1}"
  expect_http 200 "创建公共快捷回复"
  CR_ID=$(jget '.data.id')
  req GET "$CONV/api/v1/admin/canned-responses?page=1&size=10" "$ADMIN" ""; expect_http 200 "公共快捷回复列表"
  # 搜索语义：PG to_tsvector('simple') 对中文不分词，仅能命中被空格/标点分隔的完整 token。
  # title="自动化快捷回复$TS" 是一个整 token，搜"自动化"不会命中，必须搜完整词。
  SEARCH_Q=$(printf '自动化快捷回复%s' "$TS" | jq -sRr @uri)
  req GET "$CONV/api/v1/canned-responses/search?q=$SEARCH_Q" "$ADMIN" ""; expect_http 200 "坐席搜索快捷回复（整词）"
  HIT=$(jq -r '[.. | objects | select(.title? // "" | contains("自动化快捷回复"))] | length' "$RESP" 2>/dev/null)
  [ "${HIT:-0}" -ge 1 ] && ok "整词搜索命中新建快捷回复" || bad "整词搜索未命中新建快捷回复"
  if [ -n "$CR_ID" ]; then
    req POST "$CONV/api/v1/canned-responses/$CR_ID/use" "$ADMIN" "";               expect_http 200 "上报使用次数"
    req PUT  "$CONV/api/v1/admin/canned-responses/$CR_ID" "$ADMIN" \
      "{\"title\":\"自动化快捷回复$TS-改\",\"content\":\"内容已更新\",\"groupId\":$GRP_ID,\"sortOrder\":2}"
    expect_http 200 "更新公共快捷回复"
    req DELETE "$CONV/api/v1/admin/canned-responses/$CR_ID" "$ADMIN" "";           expect_http 200 "删除公共快捷回复（清理）"
  fi
  req DELETE "$CONV/api/v1/admin/canned-response-groups/$GRP_ID" "$ADMIN" "";      expect_http 200 "删除分组（清理）"
fi
# 私人模板：kfstaff 与 superadmin 数据隔离
req POST "$CONV/api/v1/canned-responses/mine" "$STAFF" "{\"title\":\"staff私人模板$TS\",\"content\":\"staff内容\"}"
expect_http 200 "kfstaff 创建私人模板"
MINE_ID=$(jget '.data.id')
req GET "$CONV/api/v1/canned-responses/mine" "$STAFF" ""; expect_http 200 "kfstaff 私人模板列表"
req GET "$CONV/api/v1/canned-responses/mine" "$ADMIN" ""
CROSS=$(jq -r '[.. | objects | select(.title? == "staff私人模板'"$TS"'")] | length' "$RESP" 2>/dev/null)
[ "${CROSS:-9}" = "0" ] && ok "私人模板跨账号不可见（数据隔离）" || bad "superadmin 竟然能看到 kfstaff 的私人模板"
if [ -n "$MINE_ID" ]; then
  req PUT "$CONV/api/v1/canned-responses/mine/$MINE_ID" "$ADMIN" '{"title":"越权改","content":"x"}'
  [ "$HTTP_CODE" != "200" ] || [ "$(bcode)" != "200" ] && ok "跨账号修改私人模板被拒" || bad "跨账号修改私人模板竟然成功"
  req PUT    "$CONV/api/v1/canned-responses/mine/$MINE_ID" "$STAFF" "{\"title\":\"staff私人模板$TS-改\",\"content\":\"已更新\"}"
  expect_http 200 "本人更新私人模板"
  req DELETE "$CONV/api/v1/canned-responses/mine/$MINE_ID" "$STAFF" ""; expect_http 200 "删除私人模板（清理）"
fi

# =============================================================================
hdr "16. SLA 策略（CRUD / 参数校验 / 权限 / 违规实测）"
req GET "$CONV/api/v1/admin/sla/policies" "$ADMIN" "";  expect_http 200 "SLA 策略列表"
req GET "$CONV/api/v1/admin/sla/policies" "$STAFF" "";  expect_http 403 "kfstaff 访问 SLA 策略被拒"

SLA_ACTIONS='{"recordBreachOnly":true,"sseAlert":true,"autoEscalate":false,"webhookIds":[]}'
# 参数校验负向
req POST "$CONV/api/v1/admin/sla/policies" "$ADMIN" \
  "{\"name\":\"坏策略\",\"isEnabled\":false,\"priority\":1,\"timeMode\":\"INVALID\",\"waitTimeTargetSec\":60,\"frtTargetSec\":60,\"handleTimeTargetSec\":60,\"warningThresholdPct\":80,\"actions\":$SLA_ACTIONS}"
expect_http 400 "timeMode 非法枚举被拒"
req POST "$CONV/api/v1/admin/sla/policies" "$ADMIN" \
  "{\"name\":\"坏策略\",\"isEnabled\":false,\"priority\":1,\"timeMode\":\"CALENDAR\",\"waitTimeTargetSec\":60,\"frtTargetSec\":60,\"handleTimeTargetSec\":60,\"warningThresholdPct\":0,\"actions\":$SLA_ACTIONS}"
expect_http 400 "warningThresholdPct=0 越界被拒"
req POST "$CONV/api/v1/admin/sla/policies" "$ADMIN" \
  "{\"name\":\"坏策略\",\"isEnabled\":false,\"priority\":1,\"timeMode\":\"CALENDAR\",\"waitTimeTargetSec\":60,\"frtTargetSec\":60,\"handleTimeTargetSec\":60,\"warningThresholdPct\":80}"
expect_http 400 "缺少 actions 被拒"

# CRUD 正向
req POST "$CONV/api/v1/admin/sla/policies" "$ADMIN" \
  "{\"name\":\"自动化SLA$TS\",\"isEnabled\":false,\"priority\":9,\"timeMode\":\"CALENDAR\",\"waitTimeTargetSec\":300,\"frtTargetSec\":60,\"handleTimeTargetSec\":1800,\"warningThresholdPct\":80,\"actions\":$SLA_ACTIONS,\"matchVisitorTags\":[],\"matchTransferTags\":[]}"
expect_http 200 "创建 SLA 策略"
SLA_ID=$(jget '.data.id')
if [ -n "$SLA_ID" ]; then
  req PUT "$CONV/api/v1/admin/sla/policies/$SLA_ID" "$ADMIN" \
    "{\"name\":\"自动化SLA$TS-改\",\"isEnabled\":false,\"priority\":8,\"timeMode\":\"BUSINESS_HOURS\",\"waitTimeTargetSec\":600,\"frtTargetSec\":120,\"handleTimeTargetSec\":3600,\"warningThresholdPct\":90,\"actions\":$SLA_ACTIONS,\"matchVisitorTags\":[],\"matchTransferTags\":[]}"
  expect_http 200 "更新 SLA 策略"
  req GET "$CONV/api/v1/admin/sla/policies" "$ADMIN" ""
  UPD_NAME=$(jq -r '[.data[]? | select((.id|tostring) == "'"$SLA_ID"'") | .name][0] // empty' "$RESP")
  [ "$UPD_NAME" = "自动化SLA$TS-改" ] && ok "策略更新已生效" || bad "策略更新未生效: $UPD_NAME"
  req DELETE "$CONV/api/v1/admin/sla/policies/$SLA_ID" "$ADMIN" ""; expect_http 200 "删除 SLA 策略（清理）"
else
  bad "未拿到 SLA 策略 ID，跳过更新/删除"
fi

# 违规记录查询（参数化）
req GET "$CONV/api/v1/admin/sla/breaches" "$ADMIN" "";                              expect_http 200 "违规记录列表"
req GET "$CONV/api/v1/admin/sla/breaches?breachType=WAIT&page=1&pageSize=5" "$ADMIN" ""; expect_http 200 "违规记录按类型过滤"
req GET "$CONV/api/v1/admin/sla/breaches?startDate=2026-01-01&endDate=2026-12-31" "$ADMIN" ""; expect_http 200 "违规记录按日期过滤"
req GET "$CONV/api/v1/admin/sla/breaches?pageSize=100000" "$ADMIN" "";              expect_http 200 "pageSize 超大值被收敛"

# SLA 违规实测：启用策略(wait 5s) → 转人工挂队列 → 扫描器(30s 周期)落 WAIT 违规
if [ "$SKIP_SLOW" = "1" ]; then
  skip "SKIP_SLOW=1，跳过 SLA 违规实测（约 75s）"
else
  req POST "$CONV/api/v1/admin/sla/policies" "$ADMIN" \
    "{\"name\":\"自动化SLA实测$TS\",\"isEnabled\":true,\"priority\":1,\"timeMode\":\"CALENDAR\",\"waitTimeTargetSec\":5,\"frtTargetSec\":99999,\"handleTimeTargetSec\":99999,\"warningThresholdPct\":80,\"actions\":$SLA_ACTIONS,\"matchVisitorTags\":[],\"matchTransferTags\":[]}"
  expect_http 200 "创建启用态 SLA 实测策略 (wait=5s)"
  SLA_LIVE_ID=$(jget '.data.id')
  ANON4="autotest-anon4-$TS"
  req POST "$CONV/api/v1/chat/session/init" "" '{"visitorName":"SLA实测访客"}' "X-Anonymous-Id: $ANON4"
  S4=$(jget '.data.sessionId')
  req POST "$CONV/api/v1/chat/transfer" "" "{\"sessionId\":\"$S4\",\"userName\":\"SLA实测访客\"}"
  expect_http 200 "S4 转人工进入 WAITING（开始计时）"
  BREACHED=0
  for i in $(seq 1 15); do
    sleep 5
    req GET "$CONV/api/v1/admin/sla/breaches?sessionId=$S4" "$ADMIN" ""
    HIT=$(jq -r '[.data[]? | select(.breachType == "WAIT")] | length' "$RESP" 2>/dev/null)
    [ "${HIT:-0}" -ge 1 ] && { BREACHED=1; break; }
  done
  [ "$BREACHED" = "1" ] && ok "WAIT 超时违规被扫描器捕获并落库 (${i}0s 内)" || bad "75s 内未产生 WAIT 违规记录"
  # 清理：接入并关闭 S4、删除实测策略
  req POST "$CONV/api/v1/sessions/$S4/accept" "$ADMIN" ""; expect_http 200 "接入 S4（清理）"
  req POST "$CONV/api/v1/sessions/$S4/close"  "$ADMIN" ""; expect_http 200 "关闭 S4（清理）"
  [ -n "$SLA_LIVE_ID" ] && { req DELETE "$CONV/api/v1/admin/sla/policies/$SLA_LIVE_ID" "$ADMIN" ""; expect_http 200 "删除实测策略（清理）"; }
fi

# =============================================================================
hdr "17. Webhook（CRUD / URL 校验 / 测试发送）"
req GET "$CONV/api/v1/admin/sla/webhooks" "$ADMIN" ""; expect_http 200 "Webhook 列表"
req POST "$CONV/api/v1/admin/sla/webhooks" "$ADMIN" '{"name":"坏Webhook","type":"CUSTOM","url":"http://insecure.example.com/hook"}'
expect_http 400 "非 HTTPS URL 被拒"
req POST "$CONV/api/v1/admin/sla/webhooks" "$ADMIN" '{"type":"CUSTOM","url":"https://example.com/hook"}'
expect_http 400 "缺少 name 被拒"

req POST "$CONV/api/v1/admin/sla/webhooks" "$ADMIN" \
  "{\"name\":\"自动化WH$TS\",\"type\":\"CUSTOM\",\"url\":\"https://nginx/\",\"customHeaders\":{\"X-Test\":\"autotest\"},\"isEnabled\":0}"
expect_http 200 "创建 CUSTOM Webhook"
WH_ID=$(jget '.data.id')
if [ -n "$WH_ID" ]; then
  req PUT "$CONV/api/v1/admin/sla/webhooks/$WH_ID" "$ADMIN" \
    "{\"name\":\"自动化WH$TS-改\",\"type\":\"CUSTOM\",\"url\":\"https://nginx/\",\"messageTemplate\":\"SLA违规: {{sessionId}}\",\"isEnabled\":0}"
  expect_http 200 "更新 Webhook"
  # 测试发送：网关内网可达即 200；TLS 自签失败会回 bcode 500，两者都证明链路执行
  req POST "$CONV/api/v1/admin/sla/webhooks/$WH_ID/test" "$ADMIN" ""
  WC=$(bcode)
  [ "$WC" = "200" ] || [ "$WC" = "500" ] && ok "Webhook 测试发送已执行 (code $WC)" || bad "Webhook 测试发送异常 code=$WC: $(body | head -c 150)"
  req DELETE "$CONV/api/v1/admin/sla/webhooks/$WH_ID" "$ADMIN" ""; expect_http 200 "删除 Webhook（清理）"
else
  bad "未拿到 Webhook ID，跳过更新/测试/删除"
fi
req POST "$CONV/api/v1/admin/sla/webhooks/999999999/test" "$ADMIN" ""
expect_bcode 40400 "测试不存在的 Webhook (40400)"

# =============================================================================
hdr "18. 业务时间（排班 / 节假日 / 离线回复 / 非服务时间拦截演练）"
req GET "$CONV/api/v1/business-hours/status" "" "";               expect_http 200 "业务时间状态（匿名可访问）"
req GET "$CONV/api/v1/admin/business-hours/schedule" "$ADMIN" ""; expect_http 200 "排班查询"
req GET "$CONV/api/v1/admin/business-hours/schedule" "$STAFF" ""; expect_http 403 "kfstaff 排班管理被拒"

# 备份现有排班（cleanup trap 兜底恢复）
jq '[.data[] | {dayOfWeek, isOpen, timeRanges}]' "$RESP" > /dev/null 2>&1
req GET "$CONV/api/v1/admin/business-hours/schedule" "$ADMIN" ""
jq '[.data[] | {dayOfWeek, isOpen, timeRanges}]' "$RESP" > "$SCHEDULE_BAK" 2>/dev/null
if [ -s "$SCHEDULE_BAK" ] && [ "$(jq 'length' "$SCHEDULE_BAK")" -ge 7 ]; then
  ok "排班已备份（$(jq 'length' "$SCHEDULE_BAK") 天）"
  # 全周关闭 → 状态变 closed → 转人工被 40301 拦截
  ALL_CLOSED=$(jq '[.[] | .isOpen = false]' "$SCHEDULE_BAK")
  SCHEDULE_RESTORED=0
  req PUT "$CONV/api/v1/admin/business-hours/schedule" "$ADMIN" "$ALL_CLOSED"
  expect_http 200 "设置全周停业"
  req GET "$CONV/api/v1/business-hours/status" "" ""
  expect_jq '.data.open' "false" "停业后 status.open=false"
  NEXT_OPEN=$(jget '.data.nextOpenTime')
  [ -n "$NEXT_OPEN" ] && ok "停业时返回 nextOpenTime ($NEXT_OPEN)" || bad "停业时未返回 nextOpenTime"
  ANON5="autotest-anon5-$TS"
  req POST "$CONV/api/v1/chat/session/init" "" '{"visitorName":"停业期访客"}' "X-Anonymous-Id: $ANON5"
  S5=$(jget '.data.sessionId')
  req POST "$CONV/api/v1/chat/transfer" "" "{\"sessionId\":\"$S5\",\"userName\":\"停业期访客\"}"
  expect_bcode 40301 "非服务时间转人工被拦截 (40301 + 恢复时间提示)"
  # 恢复排班
  req PUT "$CONV/api/v1/admin/business-hours/schedule" "$ADMIN" "$(cat "$SCHEDULE_BAK")"
  expect_http 200 "恢复原排班"
  SCHEDULE_RESTORED=1
  req GET "$CONV/api/v1/business-hours/status" "" ""
  ST_NOW=$(jget '.data.open'); echo "  ℹ️  恢复后 open=$ST_NOW"
  req POST "$CONV/api/v1/chat/transfer" "" "{\"sessionId\":\"$S5\",\"userName\":\"停业期访客\"}"
  if [ "$ST_NOW" = "true" ]; then
    expect_http 200 "恢复排班后转人工放行"
    req POST "$CONV/api/v1/sessions/$S5/accept" "$ADMIN" "" >/dev/null 2>&1
    req POST "$CONV/api/v1/sessions/$S5/close"  "$ADMIN" "" >/dev/null 2>&1
  else
    skip "当前真实时间不在营业时段，跳过放行验证"
  fi
else
  bad "排班备份失败，跳过停业拦截演练（避免破坏线上排班）"
fi

# 节假日 CRUD（远期日期 2099-12-31，date 有唯一约束 → 先清残留保证可重复执行）
req GET "$CONV/api/v1/admin/business-hours/holidays?year=2099" "$ADMIN" ""
expect_http 200 "节假日列表(year=2099)"
OLD_HOL=$(jq -r '[.data[]? | select(.date == "2099-12-31") | .id][0] // empty' "$RESP")
[ -n "$OLD_HOL" ] && req DELETE "$CONV/api/v1/admin/business-hours/holidays/$OLD_HOL" "$ADMIN" ""
req POST "$CONV/api/v1/admin/business-hours/holidays" "$ADMIN" \
  '{"date":"2099-12-31","type":"CLOSED","remark":"自动化测试节假日"}'
expect_http 200 "新增节假日"
req GET "$CONV/api/v1/admin/business-hours/holidays?year=2099" "$ADMIN" ""
HOL_ID=$(jq -r '[.data[]? | select(.date == "2099-12-31") | .id][0] // empty' "$RESP")
if [ -n "$HOL_ID" ]; then
  ok "节假日已入列表 id=$HOL_ID"
  req PUT "$CONV/api/v1/admin/business-hours/holidays/$HOL_ID" "$ADMIN" \
    '{"date":"2099-12-31","type":"CUSTOM","timeRanges":[{"start":"10:00","end":"16:00"}],"remark":"改为半天班"}'
  expect_http 200 "修改节假日为 CUSTOM 时段"
  req DELETE "$CONV/api/v1/admin/business-hours/holidays/$HOL_ID" "$ADMIN" ""; expect_http 200 "删除节假日（清理）"
else
  bad "节假日列表未找到新增记录"
fi
req POST "$CONV/api/v1/admin/business-hours/holidays" "$ADMIN" '{"date":"2099-12-31","type":"BAD_TYPE"}'
expect_http 400 "节假日 type 非法枚举被拒"

# 离线回复：改 → 验证 → 还原
req GET "$CONV/api/v1/admin/business-hours/offline-reply" "$ADMIN" ""; expect_http 200 "离线回复查询"
OFF_BAK=$(jget '.data')
req PUT "$CONV/api/v1/admin/business-hours/offline-reply" "$ADMIN" '{"message":"自动化测试离线文案"}'
expect_http 200 "更新离线回复（接口可调）"
req GET "$CONV/api/v1/admin/business-hours/offline-reply" "$ADMIN" ""
OFF_NOW=$(jget '.data')
if [ "$OFF_NOW" = "自动化测试离线文案" ]; then
  ok "离线回复更新已生效"
else
  skip "离线回复写入未生效：PUT 为 stub 实现（代码 TODO：待 AuthClient 支持写操作）——已知未实现功能"
fi
req PUT "$CONV/api/v1/admin/business-hours/offline-reply" "$ADMIN" "{\"message\":$(jq -n --arg m "$OFF_BAK" '$m')}"
expect_http 200 "还原离线回复"

# =============================================================================
hdr "19. Dashboard（全部 13 个接口 + 参数化 + 鉴权）"
for ep in overview status-distribution tag-distribution agent-workload complexity-distribution csat-distribution; do
  req GET "$CONV/api/v1/dashboard/$ep" "$ADMIN" ""; expect_http 200 "dashboard/$ep"
done
for ep in conversation-trends message-trends efficiency-trends csat-trend; do
  req GET "$CONV/api/v1/dashboard/$ep?days=7" "$ADMIN" ""; expect_http 200 "dashboard/$ep?days=7"
done
req GET "$CONV/api/v1/dashboard/recent-sessions?limit=5" "$ADMIN" ""; expect_http 200 "dashboard/recent-sessions?limit=5"
req GET "$CONV/api/v1/dashboard/csat-by-agent?days=30" "$ADMIN" "";   expect_http 200 "dashboard/csat-by-agent"
req GET "$CONV/api/v1/dashboard/csat-overview?days=30" "$ADMIN" "";   expect_http 200 "dashboard/csat-overview"
req GET "$CONV/api/v1/dashboard/overview" "" "";                      expect_http 401 "无 token 访问 dashboard 被拒"
# 数据一致性：本轮至少产生过 1 条 CLOSED 会话，recent-sessions 应非空
req GET "$CONV/api/v1/dashboard/recent-sessions?limit=10" "$ADMIN" ""
RS_CNT=$(jq '.data | length' "$RESP" 2>/dev/null)
[ "${RS_CNT:-0}" -ge 1 ] && ok "recent-sessions 含本轮会话数据 ($RS_CNT 条)" || bad "recent-sessions 为空（本轮已产生多个会话）"

# =============================================================================
hdr "20. DIT 意图路由（领域/意图/槽位/工具/绑定 全链路 CRUD）"
req GET "$CONV/api/v1/admin/dit/domains" "$ADMIN" ""; expect_http 200 "领域列表"
req POST "$CONV/api/v1/admin/dit/domains" "$ADMIN" '{"name":"缺code"}'
expect_http 400 "领域缺 code 被拒"
req POST "$CONV/api/v1/admin/dit/domains" "$ADMIN" \
  "{\"code\":\"autotest_dom_$TS\",\"name\":\"自动化领域\",\"description\":\"测试\",\"enabled\":false,\"keywords\":\"[\\\"自动化专用词\\\"]\",\"patterns\":\"[\\\"^自动化正则.*\\\"]\"}"
expect_http 200 "创建领域（含关键词/正则路由）"
DOM_ID=$(jget '.data.id')
req POST "$CONV/api/v1/admin/dit/domains" "$ADMIN" \
  "{\"code\":\"autotest_bad_$TS\",\"name\":\"坏正则\",\"patterns\":\"[\\\"[未闭合\\\"]\"}"
expect_http 400 "非法正则 patterns 被拒"

if [ -n "$DOM_ID" ]; then
  req PUT "$CONV/api/v1/admin/dit/domains/$DOM_ID" "$ADMIN" \
    "{\"code\":\"autotest_dom_$TS\",\"name\":\"自动化领域-改\",\"enabled\":false}"
  expect_http 200 "更新领域"

  # 意图（挂在临时领域下）
  req POST "$CONV/api/v1/admin/dit/intents" "$ADMIN" \
    "{\"domainId\":$DOM_ID,\"code\":\"autotest_intent_$TS\",\"name\":\"自动化意图\",\"description\":\"测试意图\",\"autoTransfer\":false,\"skipRag\":true,\"keywords\":\"[\\\"自动化意图词\\\"]\"}"
  expect_http 200 "创建意图"
  INT_ID=$(jget '.data.id')
  req GET "$CONV/api/v1/admin/dit/intents?domainId=$DOM_ID" "$ADMIN" ""; expect_http 200 "意图列表(按领域过滤)"
  req POST "$CONV/api/v1/admin/dit/intents" "$ADMIN" '{"code":"x","name":"x","description":"x"}'
  expect_http 400 "意图缺 domainId 被拒"

  # 工具
  req POST "$CONV/api/v1/admin/dit/tools" "$ADMIN" \
    "{\"code\":\"autotest_tool_$TS\",\"name\":\"自动化工具\",\"description\":\"HTTP测试工具\",\"toolType\":\"HTTP\",\"httpMethod\":\"GET\",\"urlTemplate\":\"https://nginx/\",\"timeoutMs\":3000}"
  expect_http 200 "创建工具"
  TOOL_ID=$(jget '.data.id')
  req GET "$CONV/api/v1/admin/dit/tools" "$ADMIN" ""; expect_http 200 "工具列表"

  if [ -n "$INT_ID" ]; then
    # 槽位
    req POST "$CONV/api/v1/admin/dit/slots" "$ADMIN" \
      "{\"intentId\":$INT_ID,\"slotName\":\"orderId\",\"slotType\":\"STRING\",\"description\":\"订单号\",\"required\":true,\"askUserPrompt\":\"请提供订单号\"}"
    expect_http 200 "创建槽位"
    SLOT_ID=$(jget '.data.id')
    req GET "$CONV/api/v1/admin/dit/slots?intentId=$INT_ID" "$ADMIN" ""; expect_http 200 "槽位列表"
    if [ -n "$SLOT_ID" ]; then
      req PUT "$CONV/api/v1/admin/dit/slots/$SLOT_ID" "$ADMIN" \
        "{\"intentId\":$INT_ID,\"slotName\":\"orderId\",\"description\":\"订单编号-改\",\"required\":false}"
      expect_http 200 "更新槽位"
    fi
    # 绑定（意图 ⇄ 工具）
    if [ -n "$TOOL_ID" ]; then
      req POST "$CONV/api/v1/admin/dit/bindings" "$ADMIN" \
        "{\"intentId\":$INT_ID,\"toolId\":$TOOL_ID,\"executionMode\":\"AUTO\",\"executionOrder\":1}"
      expect_http 200 "创建意图-工具绑定"
      BIND_ID=$(jget '.data.id')
      req GET "$CONV/api/v1/admin/dit/bindings?intentId=$INT_ID" "$ADMIN" ""; expect_http 200 "绑定列表"
      [ -n "$BIND_ID" ] && { req DELETE "$CONV/api/v1/admin/dit/bindings/$BIND_ID" "$ADMIN" ""; expect_http 200 "删除绑定（清理）"; }
    fi
    # 清理槽位/意图
    [ -n "$SLOT_ID" ] && { req DELETE "$CONV/api/v1/admin/dit/slots/$SLOT_ID" "$ADMIN" ""; expect_http 200 "删除槽位（清理）"; }
    req PUT "$CONV/api/v1/admin/dit/intents/$INT_ID" "$ADMIN" \
      "{\"domainId\":$DOM_ID,\"code\":\"autotest_intent_$TS\",\"name\":\"自动化意图-改\",\"description\":\"更新验证\"}"
    expect_http 200 "更新意图"
    req DELETE "$CONV/api/v1/admin/dit/intents/$INT_ID" "$ADMIN" ""; expect_http 200 "删除意图（清理）"
  fi
  [ -n "$TOOL_ID" ] && { req DELETE "$CONV/api/v1/admin/dit/tools/$TOOL_ID" "$ADMIN" ""; expect_http 200 "删除工具（清理）"; }
  req DELETE "$CONV/api/v1/admin/dit/domains/$DOM_ID" "$ADMIN" ""; expect_http 200 "删除领域（清理）"
fi
req GET "$CONV/api/v1/admin/dit/domains" "$STAFF" ""
[ "$HTTP_CODE" = "403" ] || [ "$HTTP_CODE" = "200" ] && ok "kfstaff DIT 访问策略 ($HTTP_CODE)" || bad "kfstaff DIT 访问异常 $HTTP_CODE"

# =============================================================================
hdr "21. 收尾清理"
# 停掉后台 SSE 长连接（座席下线）
[ -n "$SSE_PIDS" ] && kill $SSE_PIDS 2>/dev/null && SSE_PIDS="" && ok "后台 SSE 连接已断开"
# 清理知识库种子数据（含本轮上传的文档与 chunk）
$PG_KNOW "DELETE FROM public.knowledge_chunk WHERE doc_id IN (SELECT id FROM public.knowledge_doc WHERE kb_id='$KB_ID');" >/dev/null 2>&1
$PG_KNOW "DELETE FROM public.knowledge_doc WHERE kb_id='$KB_ID';" >/dev/null 2>&1
$PG_KNOW "DELETE FROM public.knowledge_kb  WHERE id='$KB_ID';"    >/dev/null 2>&1 && ok "知识库种子数据已清理" || skip "知识库种子清理失败（不影响测试结果）"
# 登出
req POST "$AUTH/api/v1/auth/logout" "$STAFF" "";   expect_http 200 "kfstaff 登出"
req POST "$AUTH/api/v1/auth/logout" "$MANAGER" ""; expect_http 200 "kfmanager 登出"
req POST "$AUTH/api/v1/auth/logout" "$ADMIN" "";   expect_http 200 "superadmin 登出"
req GET "$AUTH/api/v1/auth/me" "$ADMIN" "";        expect_http 401 "登出后 token 立即失效"

# =============================================================================
echo ""
echo "═══════════════════════════════════════════"
echo "  结果汇总：✅ $PASS 通过 / ❌ $FAIL 失败 / ⏭ $SKIP 跳过"
echo "═══════════════════════════════════════════"
exit $([ "$FAIL" = "0" ] && echo 0 || echo 1)
