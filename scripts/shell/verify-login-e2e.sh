#!/usr/bin/env zsh
# ============================================================
# helloai 账号密码登录 E2E 验证脚本（macOS/Linux）
# 用途：模拟登录页「注册+账号密码登录」主流程，覆盖服务端登录链路：
#   POST /api/auth/login     账号密码登录（type=admin）
#   GET  /api/auth/me        登录态校验（X-Admin-Token）
#   POST /api/auth/logout    登出，使 token 失效
# 负面用例：空用户名/空密码、用户不存在、密码错误、
#           非法登录类型（旧版 api/agent 登录入口已被登录页移除）
# Ref:  doc/HelloAI_实现差距表_V1.md（登录页去除 API 登录）
# Pre-conditions（fail-fast，本脚本不负责启动服务）：
#   - helloai-start 已在 6565 运行，且包含最新登录改造
#   - 默认管理员账号 admin/admin123 可用（可用环境变量覆盖）
# Usage:
#   chmod +x ./scripts/shell/verify-login-e2e.sh
#   ADMIN_USER=admin ADMIN_PASSWORD=admin123 ./scripts/shell/verify-login-e2e.sh
# ============================================================

export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:6565}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMP_ROOT="${TMP_ROOT:-$SCRIPT_DIR/.tmp}"
mkdir -p "$TMP_ROOT"
RUN_DIR="$(mktemp -d "$TMP_ROOT/verify-login-e2e.XXXXXX")"
LOG_FILE="$RUN_DIR/login-e2e.log"

typeset -g HTTP_CODE=""
typeset -g HTTP_BODY=""

need_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || {
    print -r -- "缺少依赖命令: $cmd"
    exit 1
  }
}

log() {
  print -r -- "$*" | tee -a "$LOG_FILE"
}

fail() {
  log "ERROR: $*"
  exit 1
}

http_request() {
  local method="$1"
  local url="$2"
  local body="$3"
  shift 3

  local body_file
  body_file="$(mktemp "$RUN_DIR/http-body.XXXXXX")"

  local -a curl_args
  curl_args=(-sS -X "$method" "$url" -o "$body_file" -w "%{http_code}" -H "Accept: application/json")
  if [[ "$method" != "GET" && "$method" != "DELETE" ]]; then
    curl_args+=(-H "Content-Type: application/json" --data "$body")
  fi

  local header
  for header in "$@"; do
    if [[ -n "$header" ]]; then
      curl_args+=(-H "$header")
    fi
  done

  if ! HTTP_CODE="$(curl "${curl_args[@]}")"; then
    HTTP_BODY="$(cat "$body_file" 2>/dev/null || true)"
    rm -f "$body_file"
    fail "$method $url 请求失败"
  fi

  HTTP_BODY="$(cat "$body_file")"
  rm -f "$body_file"
}

assert_eq() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  [[ "$expected" == "$actual" ]] || fail "$message，期望=$expected，实际=$actual"
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  local message="$3"
  [[ "$haystack" == *"$needle"* ]] || fail "$message，缺少片段: $needle"
}

jq_field() {
  local body="$1"
  local field="$2"
  print -r -- "$body" | jq -r "$field // empty"
}

need_cmd curl
need_cmd jq
need_cmd mktemp

log "=== [0] server reachability check ==="
http_request GET "$BASE_URL/api/health" ""
assert_eq "200" "$HTTP_CODE" "健康检查失败"
log "HTTP $HTTP_CODE - server is up"
log ""

log "=== [1] empty username rejected ==="
# username 字段无校验注解（仅 type/credential 必填），空用户名会走到业务层按用户不存在处理
http_request POST "$BASE_URL/api/auth/login" '{"type":"admin","username":"","credential":"x"}'
assert_eq "500" "$HTTP_CODE" "空用户名应返回 HTTP 500（业务层用户不存在）"
assert_contains "$HTTP_BODY" "用户不存在或已禁用" "空用户名应提示用户不存在"
log "HTTP $HTTP_CODE, msg=用户不存在或已禁用（username 无校验注解，由业务层拒绝）"
log ""

log "=== [2] empty password rejected ==="
http_request POST "$BASE_URL/api/auth/login" '{"type":"admin","username":"admin","credential":""}'
assert_eq "400" "$HTTP_CODE" "空密码应返回 HTTP 400"
code="$(jq_field "$HTTP_BODY" '.code')"
assert_eq "400" "$code" "空密码 body.code 应为 400"
assert_contains "$HTTP_BODY" "凭证" "空密码应提示字段校验消息"
log "HTTP $HTTP_CODE, code=$code, 参数校验失败已优雅返回 400"
log ""

log "=== [3] unknown user rejected ==="
unknown_body="$(jq -cn --arg u 'no_such_user_xyz' --arg p "$ADMIN_PASSWORD" '{type:"admin",username:$u,credential:$p}')"
http_request POST "$BASE_URL/api/auth/login" "$unknown_body"
# 业务异常码 500 由全局处理器映射为 HTTP 500（见 GlobalExceptionHandler）
assert_eq "500" "$HTTP_CODE" "未知用户登录应返回 HTTP 500"
assert_contains "$HTTP_BODY" "用户不存在或已禁用" "未知用户应提示用户不存在"
log "HTTP $HTTP_CODE, msg=用户不存在或已禁用"
log ""

log "=== [4] wrong password rejected ==="
wrong_body="$(jq -cn --arg u "$ADMIN_USER" '{type:"admin",username:$u,credential:"wrong-password"}')"
http_request POST "$BASE_URL/api/auth/login" "$wrong_body"
assert_eq "500" "$HTTP_CODE" "错误密码登录应返回 HTTP 500"
assert_contains "$HTTP_BODY" "密码错误" "错误密码应提示密码错误"
log "HTTP $HTTP_CODE, msg=密码错误"
log ""

log "=== [5] legacy api/agent login type rejected ==="
api_body="$(jq -cn --arg u "$ADMIN_USER" --arg p "$ADMIN_PASSWORD" '{type:"apikey",username:$u,credential:$p}')"
http_request POST "$BASE_URL/api/auth/login" "$api_body"
assert_eq "200" "$HTTP_CODE" "非法登录类型应返回 200 业务响应"
assert_contains "$HTTP_BODY" "登录类型无效" "非法登录类型应被拒绝"
log "HTTP $HTTP_CODE, msg=登录类型无效，仅支持 admin/agent"
log ""

log "=== [6] admin account+password login ==="
login_body="$(jq -cn --arg u "$ADMIN_USER" --arg p "$ADMIN_PASSWORD" '{type:"admin",username:$u,credential:$p}')"
http_request POST "$BASE_URL/api/auth/login" "$login_body"
assert_eq "200" "$HTTP_CODE" "账号密码登录失败"
code="$(jq_field "$HTTP_BODY" '.code')"
assert_eq "200" "$code" "登录响应 code 应为 200"
ADMIN_TOKEN="$(jq_field "$HTTP_BODY" '.data.token')"
[[ -n "$ADMIN_TOKEN" ]] || fail "未取到 admin token: $HTTP_BODY"
login_type="$(jq_field "$HTTP_BODY" '.data.type')"
login_role="$(jq_field "$HTTP_BODY" '.data.role')"
[[ -n "$login_role" ]] || fail "未取到角色: $HTTP_BODY"
assert_eq "admin" "$login_type" "登录类型应为 admin"
log "token = ${ADMIN_TOKEN[1,16]}... type=$login_type role=$login_role"
log ""

log "=== [7] /me with valid token ==="
http_request GET "$BASE_URL/api/auth/me" "" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$HTTP_CODE" "/me 带有效 token 应返回 200"
me_type="$(jq_field "$HTTP_BODY" '.data.type')"
me_name="$(jq_field "$HTTP_BODY" '.data.displayName')"
[[ -n "$me_name" ]] || fail "未取到 /me 身份信息: $HTTP_BODY"
assert_eq "admin" "$me_type" "/me 返回的类型应为 admin"
log "HTTP $HTTP_CODE, type=$me_type, displayName=$me_name"
log ""

log "=== [8] /me without token ==="
http_request GET "$BASE_URL/api/auth/me" ""
assert_eq "200" "$HTTP_CODE" "/me 无 token 应返回 200 业务响应"
assert_contains "$HTTP_BODY" "未登录" "/me 无 token 应提示未登录"
log "HTTP $HTTP_CODE, code=$(jq_field "$HTTP_BODY" '.code'), msg=未登录"
log ""

log "=== [9] logout ==="
http_request POST "$BASE_URL/api/auth/logout" '{}' "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$HTTP_CODE" "登出失败"
log "HTTP $HTTP_CODE - logout ok"
log ""

log "=== [10] stale token rejected after logout ==="
http_request GET "$BASE_URL/api/auth/me" "" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "401" "$HTTP_CODE" "登出后旧 token 应被拒绝(401)"
log "HTTP $HTTP_CODE - stale token rejected"
log ""

log "=== Cleanup ==="
log "Run log: $LOG_FILE"
log "Done. 账号密码登录链路全部通过。"
