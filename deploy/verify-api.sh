#!/bin/bash
# =============================================================================
# 接口自动化验证脚本 — 验证本次代码评审修复点部署后是否生效
# 用法：bash deploy/verify-api.sh
# 依赖：curl、jq、docker（用于直连 PG 校验 N1 落库）
# =============================================================================
set -uo pipefail

# 业务服务未对宿主发布端口，统一走 nginx（自签证书用 -k）
AUTH=https://localhost/auth
CONV=https://localhost/conversation
KNOW=https://localhost/knowledge
ADMIN_USER=superadmin
STAFF_USER=kfstaff
PWD_DEFAULT='Test@123456'
PG='docker exec ai-cs-postgres psql -U postgres -d aria_cs -tAc'

PASS=0; FAIL=0
ok()   { echo "  ✅ PASS: $1"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ FAIL: $1"; FAIL=$((FAIL+1)); }
hdr()  { echo ""; echo "── $1"; }

# 通用请求：$1=method $2=url $3=token(可空) $4=body(可空)，输出 "HTTP_CODE\n<body>"
req() {
  local m=$1 url=$2 tok=${3:-} body=${4:-}
  local args=(-sk -o /tmp/resp.$$ -w '%{http_code}' -X "$m" "$url" -H 'Content-Type: application/json')
  [ -n "$tok" ] && args+=(-H "Authorization: Bearer $tok")
  [ -n "$body" ] && args+=(-d "$body")
  local code; code=$(curl "${args[@]}"); echo "$code"; cat /tmp/resp.$$
}

# =============================================================================
hdr "0. 健康检查（容器 docker health + nginx 网关）"
for c in ai-cs-auth ai-cs-knowledge ai-cs-conversation-1 ai-cs-conversation-2; do
  status=$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null)
  [ "$status" = "healthy" ] && ok "$c 容器健康 (healthy)" || bad "$c 不健康 (status=$status)"
done
gw=$(curl -sk https://localhost/ | jq -r '.status // "offline"' 2>/dev/null)
[ "$gw" = "online" ] && ok "nginx 网关在线 (online)" || bad "nginx 网关异常 (status=$gw)"

# nginx 剥掉一级前缀：$AUTH/api/... → auth-service /api/...；健康端点未经网关暴露，故走容器 health

# =============================================================================
hdr "1. 登录（superadmin）取 token"
LOGIN=$(curl -sk -X POST "$AUTH/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$PWD_DEFAULT\"}")
ADMIN_TOKEN=$(echo "$LOGIN" | jq -r '.data.tokenValue // empty')
ADMIN_ROLES=$(echo "$LOGIN" | jq -c '.data.roles // []')
if [ -n "$ADMIN_TOKEN" ]; then ok "superadmin 登录成功，roles=$ADMIN_ROLES"; else bad "superadmin 登录失败: $LOGIN"; echo "无法继续，退出"; exit 1; fi

hdr "1b. 登录（kfstaff 非管理员）取 token"
LOGIN2=$(curl -sk -X POST "$AUTH/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$STAFF_USER\",\"password\":\"$PWD_DEFAULT\"}")
STAFF_TOKEN=$(echo "$LOGIN2" | jq -r '.data.tokenValue // empty')
STAFF_ROLES=$(echo "$LOGIN2" | jq -c '.data.roles // []')
[ -n "$STAFF_TOKEN" ] && ok "kfstaff 登录成功，roles=$STAFF_ROLES" || bad "kfstaff 登录失败: $LOGIN2"

# =============================================================================
hdr "2. H1 — 用户管理接口权限校验 @SaCheckRole(admin)"
# 2a 无 token 访问受保护列表 → 401
r=$(req GET "$AUTH/api/v1/users" ""); code=$(echo "$r" | head -1)
[ "$code" = "401" ] && ok "无 token GET /users 被拒 (401)" || bad "无 token GET /users 期望401 实际$code"
# 2b superadmin → 200
r=$(req GET "$AUTH/api/v1/users?page=1&size=1" "$ADMIN_TOKEN"); code=$(echo "$r" | head -1)
[ "$code" = "200" ] && ok "superadmin GET /users 放行 (200)" || bad "superadmin GET /users 期望200 实际$code"
# 2c kfstaff 非 admin → 403
if [ -n "$STAFF_TOKEN" ]; then
  r=$(req GET "$AUTH/api/v1/users?page=1&size=1" "$STAFF_TOKEN"); code=$(echo "$r" | head -1)
  [ "$code" = "403" ] && ok "kfstaff GET /users 被拒 (403 角色不足)" || bad "kfstaff GET /users 期望403 实际$code"
fi

# =============================================================================
hdr "3. H1 — 自操作归属校验（改他人密码需 admin）"
# kfstaff 尝试改 superadmin(1001) 密码 → 角色校验先行拦截 403（不会真正改密码）
if [ -n "$STAFF_TOKEN" ]; then
  r=$(req POST "$AUTH/api/v1/users/1001/change-password" "$STAFF_TOKEN" \
      '{"oldPassword":"whatever12","newPassword":"whatever12"}'); code=$(echo "$r" | head -1)
  [ "$code" = "403" ] && ok "kfstaff 改他人密码被拒 (403)" || bad "kfstaff 改他人密码 期望403 实际$code"
fi

# =============================================================================
hdr "4. N1 — assignRoles 角色真正落库（核心修复点）"
BEFORE=$($PG "SELECT string_agg(role_id::text,',' ORDER BY role_id) FROM cs_auth.sys_user_role WHERE user_id=1003;")
echo "  DB 初始 user 1003 角色: [${BEFORE}]"
# 4a 分配 {11,12}
r=$(req POST "$AUTH/api/v1/users/1003/roles" "$ADMIN_TOKEN" '{"roleIds":[11,12]}'); code=$(echo "$r" | head -1)
[ "$code" = "200" ] && ok "assignRoles 调用成功 (200)" || bad "assignRoles 期望200 实际$code : $(echo "$r"|tail -1)"
AFTER=$($PG "SELECT string_agg(role_id::text,',' ORDER BY role_id) FROM cs_auth.sys_user_role WHERE user_id=1003;")
echo "  DB 变更后 user 1003 角色: [${AFTER}]"
[ "$AFTER" = "11,12" ] && ok "N1 已落库：sys_user_role 实际变为 {11,12}" || bad "N1 未落库：期望{11,12} 实际{$AFTER}"
# 4b GET 回读 roleIds
r=$(req GET "$AUTH/api/v1/users/1003" "$ADMIN_TOKEN"); rids=$(echo "$r" | tail -1 | jq -c '.data.roleIds // .data.roles // "n/a"' 2>/dev/null)
echo "  GET /users/1003 返回 roleIds=$rids"
# 4c 还原为初始 {12}
r=$(req POST "$AUTH/api/v1/users/1003/roles" "$ADMIN_TOKEN" '{"roleIds":[12]}'); code=$(echo "$r" | head -1)
RESTORE=$($PG "SELECT string_agg(role_id::text,',' ORDER BY role_id) FROM cs_auth.sys_user_role WHERE user_id=1003;")
[ "$RESTORE" = "12" ] && ok "已还原 user 1003 角色为 {12}" || bad "还原失败，当前{$RESTORE}（请手工核对）"

# =============================================================================
hdr "5. L6 — SessionQueue closedLimit 收敛（超大/非法值不报错）"
for lim in 99999 0 -5; do
  r=$(req GET "$CONV/api/v1/sessions?closedLimit=$lim" "$ADMIN_TOKEN"); code=$(echo "$r" | head -1)
  [ "$code" = "200" ] && ok "closedLimit=$lim 正常收敛 (200)" || bad "closedLimit=$lim 期望200 实际$code"
done

# =============================================================================
hdr "6. CSAT — /pending sessionId 格式校验"
# 6a 非法 sessionId → code 400
r=$(req GET "$CONV/api/v1/chat/csat/pending?sessionId=bad%20id%21" ""); c=$(echo "$r"|tail -1|jq -r '.code // "?"' 2>/dev/null)
[ "$c" = "400" ] && ok "非法 sessionId 被拒 (业务码400)" || bad "非法 sessionId 期望code400 实际$c"
# 6b 合法 sessionId → code 200 data null
r=$(req GET "$CONV/api/v1/chat/csat/pending?sessionId=test_session_001" ""); c=$(echo "$r"|tail -1|jq -r '.code // "?"' 2>/dev/null)
[ "$c" = "200" ] && ok "合法 sessionId 放行 (业务码200)" || bad "合法 sessionId 期望code200 实际$c"

# =============================================================================
echo ""
echo "═══════════════════════════════════════════"
echo "  结果汇总：通过 $PASS 项 / 失败 $FAIL 项"
echo "═══════════════════════════════════════════"
rm -f /tmp/resp.$$
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
