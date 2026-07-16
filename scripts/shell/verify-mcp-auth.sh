#!/bin/zsh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:6565}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMP_ROOT="${TMP_ROOT:-$SCRIPT_DIR/.tmp}"
mkdir -p "$TMP_ROOT"
RUN_DIR="$(mktemp -d "$TMP_ROOT/verify-mcp-auth.XXXXXX")"
SSE_FILE="$RUN_DIR/sse-auth.txt"
SSE_ERR_FILE="$RUN_DIR/sse-auth.err"
LOG_FILE="$RUN_DIR/m4-auth-test.log"
AGENT_NAME="${AGENT_NAME:-M4-test-executor}"

typeset -g HTTP_CODE=""
typeset -g HTTP_BODY=""
typeset -g LAST_MCP_CODE=""
typeset -g LAST_MCP_BODY=""
typeset -g LAST_SSE_NEW_CONTENT=""
typeset -g ADMIN_TOKEN=""
typeset -g AGENT_ID=""
typeset -g AGENT_API_KEY=""
typeset -g SID=""
typeset -g SSE_PID=""

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

cleanup() {
  if [[ -n "${SSE_PID:-}" ]]; then
    kill "$SSE_PID" >/dev/null 2>&1 || true
    wait "$SSE_PID" >/dev/null 2>&1 || true
  fi
}

fail() {
  log "ERROR: $*"
  exit 1
}

trap cleanup EXIT

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

assert_matches() {
  local content="$1"
  local pattern="$2"
  local message="$3"
  print -r -- "$content" | grep -Eq "$pattern" || fail "$message，未匹配: $pattern"
}

extract_sse_data_json() {
  local raw="$1"
  local data_line
  data_line="$(print -r -- "$raw" | awk '/^data:/{sub(/^data:/, ""); print; exit}')"
  [[ -n "$data_line" ]] || return 1
  print -r -- "$data_line"
}

extract_result_text_json() {
  local raw="$1"
  local data_json
  data_json="$(extract_sse_data_json "$raw")" || return 1
  print -r -- "$data_json" | jq -cer '.result.content[0].text // empty | fromjson'
}

start_sse() {
  : > "$SSE_FILE"
  : > "$SSE_ERR_FILE"

  curl -sS -N "$BASE_URL/mcp/sse" >"$SSE_FILE" 2>"$SSE_ERR_FILE" &
  SSE_PID="$!"

  local attempt=0
  while (( attempt < 20 )); do
    if [[ -s "$SSE_FILE" ]]; then
      SID="$(LC_ALL=C grep -aoE 'sessionId=[A-Za-z0-9-]+' "$SSE_FILE" | head -n 1 | cut -d= -f2)"
      if [[ -n "$SID" ]]; then
        break
      fi
    fi
    sleep 1
    (( attempt += 1 ))
  done

  [[ -n "$SID" ]] || fail "未能从 /mcp/sse 提取 sessionId。stderr: $(cat "$SSE_ERR_FILE" 2>/dev/null || true)"
}

send_mcp() {
  local label="$1"
  local body="$2"
  shift 2

  local before=0
  if [[ -f "$SSE_FILE" ]]; then
    before="$(wc -c <"$SSE_FILE" | tr -d ' ')"
  fi

  log "=== $label ==="
  log "Body: $body"

  http_request POST "$BASE_URL/mcp/messages?sessionId=$SID" "$body" "$@"
  LAST_MCP_CODE="$HTTP_CODE"
  LAST_MCP_BODY="$HTTP_BODY"

  log "POST Status: $LAST_MCP_CODE"
  log "POST Body: $LAST_MCP_BODY"

  sleep 2

  local after=0
  if [[ -f "$SSE_FILE" ]]; then
    after="$(wc -c <"$SSE_FILE" | tr -d ' ')"
  fi

  if (( after > before )); then
    LAST_SSE_NEW_CONTENT="$(tail -c +$((before + 1)) "$SSE_FILE")"
  else
    LAST_SSE_NEW_CONTENT=""
  fi

  log "--- SSE new content ---"
  if [[ -n "$LAST_SSE_NEW_CONTENT" ]]; then
    print -r -- "$LAST_SSE_NEW_CONTENT" | tee -a "$LOG_FILE"
  else
    log "(no new content)"
  fi
  log ""
}

need_cmd curl
need_cmd jq
need_cmd grep
need_cmd mktemp

log "=== [0] server reachability check ==="
http_request GET "$BASE_URL/api/health" ""
assert_eq "200" "$HTTP_CODE" "健康检查失败"
log "HTTP $HTTP_CODE - server is up"
log ""

log "=== [A] admin login ==="
login_body="$(jq -cn '{type:"admin",username:"admin",credential:"admin123"}')"
http_request POST "$BASE_URL/api/auth/login" "$login_body"
assert_eq "200" "$HTTP_CODE" "admin 登录失败"
ADMIN_TOKEN="$(print -r -- "$HTTP_BODY" | jq -r '.data.token // empty')"
[[ -n "$ADMIN_TOKEN" ]] || fail "未取到 admin token: $HTTP_BODY"
log "adminToken = ${ADMIN_TOKEN[1,16]}..."
log ""

log "=== [B] create or reuse test agent ==="
http_request GET "$BASE_URL/api/admin/agents?pageSize=50" "" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$HTTP_CODE" "查询 admin agents 失败"

existing_agent="$(print -r -- "$HTTP_BODY" | jq -c --arg name "$AGENT_NAME" '.data.list[]? | select(.name == $name)' | head -n 1 || true)"
if [[ -n "$existing_agent" ]]; then
  AGENT_ID="$(print -r -- "$existing_agent" | jq -r '.id')"
  AGENT_API_KEY="$(print -r -- "$existing_agent" | jq -r '.apiKey')"
  log "reuse existing: id=$AGENT_ID"
else
  create_body="$(jq -cn --arg name "$AGENT_NAME" --arg remark "M4 auth verify auto created (macOS)" '{name:$name,role:"EXECUTOR",remark:$remark}')"
  http_request POST "$BASE_URL/api/admin/agents" "$create_body" "X-Admin-Token: $ADMIN_TOKEN"
  assert_eq "200" "$HTTP_CODE" "创建测试 Agent 失败"
  AGENT_ID="$(print -r -- "$HTTP_BODY" | jq -r '.data.id // empty')"
  AGENT_API_KEY="$(print -r -- "$HTTP_BODY" | jq -r '.data.apiKey // empty')"
  log "create new: id=$AGENT_ID"
fi

[[ -n "$AGENT_ID" ]] || fail "未取到 agentId"
[[ -n "$AGENT_API_KEY" ]] || fail "未取到 agentApiKey"
log "agentId = $AGENT_ID"
log ""

log "=== [C] start SSE long connection ==="
start_sse
log "sessionId = $SID"
log ""

init_body="$(jq -cn '{jsonrpc:"2.0",id:0,method:"initialize",params:{protocolVersion:"2024-11-05",capabilities:{},clientInfo:{name:"zsh-m4",version:"0.0.1"}}}')"
send_mcp "[D1] initialize with admin token" "$init_body" "X-Admin-Token: $ADMIN_TOKEN"

notify_body="$(jq -cn '{jsonrpc:"2.0",method:"notifications/initialized"}')"
send_mcp "[D2] notifications/initialized (admin token)" "$notify_body" "X-Admin-Token: $ADMIN_TOKEN"

d3_body="$(jq -cn '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"getAgentStatus",arguments:{agentId:999}}}')"
send_mcp "[D3] tools/call NO TOKEN (expect 401)" "$d3_body"
assert_eq "401" "$LAST_MCP_CODE" "D3 应返回 401"

d4_body="$(jq -cn '{jsonrpc:"2.0",id:2,method:"tools/call",params:{name:"getAgentStatus",arguments:{agentId:999}}}')"
send_mcp "[D4] tools/call WRONG TOKEN (expect 401)" "$d4_body" "Authorization: Bearer wrong-api-key-xxxxx"
assert_eq "401" "$LAST_MCP_CODE" "D4 应返回 401"

d5_body="$(jq -cn --arg sid "$SID" '{jsonrpc:"2.0",id:3,method:"tools/call",params:{name:"getAgentStatus",arguments:{agentId:999,sessionId:$sid}}}')"
send_mcp "[D5] tools/call agent apiKey + wrong agentId (expect 200 + override)" "$d5_body" "Authorization: Bearer $AGENT_API_KEY"
assert_eq "200" "$LAST_MCP_CODE" "D5 应返回 200"
assert_contains "$LAST_SSE_NEW_CONTENT" '"id":3' "D5 SSE 应包含 jsonrpc id=3"
assert_contains "$LAST_SSE_NEW_CONTENT" '"isError":false' "D5 SSE 应包含 isError=false"
d5_json="$(extract_result_text_json "$LAST_SSE_NEW_CONTENT")"
d5_agent_id="$(print -r -- "$d5_json" | jq -r '.agentId // empty')"
assert_eq "$AGENT_ID" "$d5_agent_id" "D5 应将 agentId 覆盖为真实 Agent"

d6_body="$(jq -cn --arg sid "$SID" '{jsonrpc:"2.0",id:4,method:"tools/call",params:{name:"getAgentStatus",arguments:{agentId:999,sessionId:$sid}}}')"
send_mcp "[D6] tools/call admin token + wrong agentId (expect 200 + isError=true)" "$d6_body" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$LAST_MCP_CODE" "D6 应返回 200"
assert_contains "$LAST_SSE_NEW_CONTENT" '"id":4' "D6 SSE 应包含 jsonrpc id=4"
assert_contains "$LAST_SSE_NEW_CONTENT" '"isError":true' "D6 SSE 应包含 isError=true"

log "=== Cleanup ==="
log "SSE log: $SSE_FILE"
log "Test agent id=$AGENT_ID still in DB (需要时可在管理后台删除)"
log "Done."
