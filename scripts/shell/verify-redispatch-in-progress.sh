#!/usr/bin/env zsh
# ============================================================
# helloai 执行中卡死改派（换人）e2e 验证脚本（V1，macOS/Linux）
# 用途：验证前端「换人」按钮对应的后端收口接口闭环：
#   POST /api/sub-tasks/redispatchInProgressById/{id}
#       IN_PROGRESS / PAUSED 子任务先标 BLOCKED（sub_task_report_blocked 事件落时间线），
#       再复用既有 BLOCKED 重调度链改派给指定 Agent（PAUSED 先自动恢复）
#   断言链：ASSIGNED(A) -> start -> IN_PROGRESS(A) -> redispatch -> ASSIGNED(B)
#   正向用例 2：PAUSED 换人（先自动恢复再 block 再重派，同样到 ASSIGNED(B)）
#   负面用例：PENDING 状态调用必须被拒绝（只有 IN_PROGRESS/PAUSED 才能改派）
# Ref:  doc/log/HelloAI_迭代执行记录.md（2026-08-26 子任务「执行中卡死改派」）
# Pre-conditions（fail-fast，本脚本不负责启动服务）：
#   - helloai-start 已在 6565 运行（IDEA 或 mvn spring-boot:run）
#   - docker compose 已起 postgres/redis（agent 打卡租约与心跳依赖）
#   脚本自动注册 2 个 CLI_CLIENT EXECUTOR Agent（幂等固定名），
#   目标 Agent 通过 REST 直通端点 checkIn + heartbeat 保持可分配
#   （CLI_CLIENT 不触发自动执行链，改派后状态稳定停在 ASSIGNED）
# Usage:
#   chmod +x ./scripts/shell/verify-redispatch-in-progress.sh
#   ./scripts/shell/verify-redispatch-in-progress.sh
# 或:
#   zsh ./scripts/shell/verify-redispatch-in-progress.sh
# ============================================================

set -euo pipefail

# ------------------------------------------------------------
# UTF-8 编码强制头 (规则 6) — 避免中文乱码
# ------------------------------------------------------------
export LANG="${LANG:-zh_CN.UTF-8}"
export LC_ALL="${LC_ALL:-zh_CN.UTF-8}"

BASE_URL="${BASE_URL:-http://localhost:6565}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"
AGENT_A_NAME="redispatch-e2e-a-v1"
AGENT_B_NAME="redispatch-e2e-b-v1"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="${LOG_FILE:-$SCRIPT_DIR/.tmp/verify-redispatch-in-progress.log}"
mkdir -p "$(dirname "$LOG_FILE")"

# ============================================================
# helpers
# ============================================================
need_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || {
    print -r -- "MISSING DEPENDENCY: $cmd"
    exit 1
  }
}

log() {
  print -r -- "$*" | tee -a "$LOG_FILE"
}

fail() {
  print -r -- "ASSERT_FAIL: $*" | tee -a "$LOG_FILE"
  exit 1
}

assert_eq() {
  local actual="$1" expected="$2" msg="$3"
  [[ "$actual" == "$expected" ]] || fail "$msg (expected=$expected actual=$actual)"
}

# http_json <method> <url> <json-body|-> <timeout-sec>
# 带管理员头请求，输出响应 body；curl 层错误直接 fail
http_json() {
  local method="$1" url="$2" body="$3" timeout="${4:-30}"
  local -a args
  args=(-sS -X "$method" "$url" -H "Content-Type: application/json" --max-time "$timeout")
  [[ -n "${ADMIN_TOKEN:-}" ]] && args+=(-H "X-Admin-Token: $ADMIN_TOKEN")
  [[ "$body" != "-" ]] && args+=(-d "$body")
  curl "${args[@]}" || fail "curl $method $url failed (server down or timeout)"
}

# http_agent_json <method> <url> <json-body|-> <api-key> <timeout-sec>
# 带 Agent API Key Bearer 头请求（agent 工具通道，_authId 取自该 Key）
http_agent_json() {
  local method="$1" url="$2" body="$3" api_key="$4" timeout="${5:-30}"
  local -a args
  args=(-sS -X "$method" "$url" -H "Content-Type: application/json" -H "Authorization: Bearer $api_key" --max-time "$timeout")
  [[ "$body" != "-" ]] && args+=(-d "$body")
  curl "${args[@]}" || fail "curl $method $url failed (server down or timeout)"
}

# assert_r200 <resp> <ctx> — 断言平台统一 R 响应 code==200
assert_r200() {
  local resp="$1" ctx="$2"
  local code msg
  code="$(print -r -- "$resp" | jq -r '.code // empty')"
  [[ "$code" == "200" ]] || {
    msg="$(print -r -- "$resp" | jq -r '.msg // empty')"
    fail "$ctx code=$code msg=$msg"
  }
}

# register_agent <name> — 幂等注册 CLI_CLIENT EXECUTOR，输出 "id apiKey"
register_agent() {
  local name="$1" resp id api_key
  resp="$(http_json POST "$BASE_URL/api/agents/register" \
    "{\"name\":\"$name\",\"role\":\"EXECUTOR\",\"description\":\"verify-redispatch-in-progress\",\"accessType\":\"CLI_CLIENT\",\"idempotent\":true}")"
  assert_r200 "$resp" "register agent $name"
  id="$(print -r -- "$resp" | jq -r '.data.id')"
  api_key="$(print -r -- "$resp" | jq -r '.data.apiKey')"
  [[ -n "$id" && -n "$api_key" ]] || fail "register agent $name: empty id/apiKey"
  print -r -- "$id $api_key"
}

# get_subtask <id> — 输出 "status assignedAgent"
get_subtask() {
  local id="$1" resp
  resp="$(http_json GET "$BASE_URL/api/sub-tasks/getById/$id" "-")"
  assert_r200 "$resp" "get subtask $id"
  print -r -- "$(print -r -- "$resp" | jq -r '.data.status') $(print -r -- "$resp" | jq -r '.data.assignedAgent // "" | tostring')"
}

need_cmd curl
need_cmd jq

: > "$LOG_FILE"

log "STEP1: admin login"
LOGIN_RESP="$(http_json POST "$BASE_URL/api/auth/login" \
  "{\"type\":\"admin\",\"username\":\"$ADMIN_USERNAME\",\"credential\":\"$ADMIN_PASSWORD\"}")"
assert_r200 "$LOGIN_RESP" "login"
ADMIN_TOKEN="$(print -r -- "$LOGIN_RESP" | jq -r '.data.token // empty')"
[[ -n "$ADMIN_TOKEN" ]] || fail "admin token is empty"

TS="$(date -u +%Y%m%d%H%M%S)"

log "STEP2: register executor agents (idempotent fixed names)"
read -r AGENT_A_ID AGENT_A_KEY <<<"$(register_agent "$AGENT_A_NAME")"
log "agentA=$AGENT_A_ID"
read -r AGENT_B_ID AGENT_B_KEY <<<"$(register_agent "$AGENT_B_NAME")"
log "agentB=$AGENT_B_ID"

log "STEP3: agentB checkIn + heartbeat (REST direct channel, Bearer auth)"
CHECKIN_RESP="$(http_agent_json POST "$BASE_URL/api/mcp/tools/checkIn" \
  '{"workMode":"AUTO","maxConcurrent":2,"ttlMinutes":60}' "$AGENT_B_KEY")"
assert_r200 "$CHECKIN_RESP" "agentB checkIn"
HEARTBEAT_RESP="$(http_agent_json POST "$BASE_URL/api/mcp/tools/heartbeat" '{}' "$AGENT_B_KEY")"
assert_r200 "$HEARTBEAT_RESP" "agentB heartbeat"

# ------------------------------------------------------------
# 主路径：IN_PROGRESS(A) -> 换人 -> ASSIGNED(B)
# ------------------------------------------------------------

log "STEP4: create parent task"
TASK_ID="$(http_json POST "$BASE_URL/api/tasks" \
  "{\"title\":\"redispatch-e2e-$TS\",\"description\":\"verify redispatchInProgress e2e\"}" | jq -r '.data.id')"
[[ -n "$TASK_ID" && "$TASK_ID" != "null" ]] || fail "create task: empty id"
log "taskId=$TASK_ID"

log "STEP5: create subtask assigned to agentA"
SUB_RESP="$(http_json POST "$BASE_URL/api/sub-tasks" \
  "{\"taskId\":$TASK_ID,\"title\":\"redispatch-e2e-st-$TS\",\"description\":\"stuck subtask for manual redispatch\",\"assignedAgent\":$AGENT_A_ID}")"
assert_r200 "$SUB_RESP" "create subtask"
SUB_ID="$(print -r -- "$SUB_RESP" | jq -r '.data.id')"
[[ -n "$SUB_ID" && "$SUB_ID" != "null" ]] || fail "create subtask: empty id"
read -r ST_STATUS ST_AGENT <<<"$(get_subtask "$SUB_ID")"
assert_eq "$ST_STATUS" "ASSIGNED" "subtask status after create"
assert_eq "$ST_AGENT" "$AGENT_A_ID" "subtask assigned to agentA after create"
log "subTaskId=$SUB_ID status=$ST_STATUS assignedAgent=$ST_AGENT"

log "STEP6: start subtask -> IN_PROGRESS"
START_RESP="$(http_json POST "$BASE_URL/api/sub-tasks/startById/$SUB_ID" "-")"
assert_r200 "$START_RESP" "start subtask"
read -r ST_STATUS ST_AGENT <<<"$(get_subtask "$SUB_ID")"
assert_eq "$ST_STATUS" "IN_PROGRESS" "subtask status after start"
assert_eq "$ST_AGENT" "$AGENT_A_ID" "subtask still assigned to agentA"
log "status=$ST_STATUS assignedAgent=$ST_AGENT"

log "STEP7: redispatchInProgress -> agentB (core assertion)"
REDISP_RESP="$(http_json POST "$BASE_URL/api/sub-tasks/redispatchInProgressById/$SUB_ID" \
  "{\"agentId\":$AGENT_B_ID}")"
assert_r200 "$REDISP_RESP" "redispatchInProgress"
read -r ST_STATUS ST_AGENT <<<"$(get_subtask "$SUB_ID")"
assert_eq "$ST_STATUS" "ASSIGNED" "subtask back to ASSIGNED after redispatch"
assert_eq "$ST_AGENT" "$AGENT_B_ID" "subtask reassigned to agentB"
log "status=$ST_STATUS assignedAgent=$ST_AGENT (handover OK)"

log "STEP8: timeline must contain sub_task_report_blocked (manual stuck block)"
TL_RESP="$(http_json GET "$BASE_URL/api/sub-tasks/listTimelineBySubTaskId/$SUB_ID" "-")"
assert_r200 "$TL_RESP" "list timeline"
BLOCKED_EVENTS="$(print -r -- "$TL_RESP" | jq -r '[.data[] | select(.eventType == "sub_task_report_blocked")] | length')"
(( BLOCKED_EVENTS >= 1 )) || fail "expected >=1 sub_task_report_blocked events, actual=$BLOCKED_EVENTS"
DISPATCH_EVENTS="$(print -r -- "$TL_RESP" | jq -r '[.data[] | select(.eventType == "sub_task_dispatch_prepare" or .eventType == "sub_task_assigned")] | length')"
(( DISPATCH_EVENTS >= 1 )) || fail "expected >=1 dispatch/assigned events, actual=$DISPATCH_EVENTS"
log "blockedEvents=$BLOCKED_EVENTS dispatchEvents=$DISPATCH_EVENTS"

# ------------------------------------------------------------
# 负面用例：PENDING 状态必须拒绝换人
# ------------------------------------------------------------

log "STEP9: negative - PENDING subtask must be rejected"
NEG_RESP="$(http_json POST "$BASE_URL/api/sub-tasks" \
  "{\"taskId\":$TASK_ID,\"title\":\"redispatch-e2e-neg-$TS\",\"description\":\"negative case\",\"assignedAgent\":$AGENT_A_ID}")"
assert_r200 "$NEG_RESP" "create negative subtask"
NEG_ID="$(print -r -- "$NEG_RESP" | jq -r '.data.id')"
NEG_REDISP="$(http_json POST "$BASE_URL/api/sub-tasks/redispatchInProgressById/$NEG_ID" \
  "{\"agentId\":$AGENT_B_ID}")"
NEG_CODE="$(print -r -- "$NEG_REDISP" | jq -r '.code // empty')"
[[ "$NEG_CODE" != "200" ]] || fail "redispatchInProgress on PENDING unexpectedly succeeded"
log "PENDING rejected as expected (code=$NEG_CODE)"

# ------------------------------------------------------------
# 正向用例：PAUSED 状态换人（先自动恢复再 block 再重派）
# ------------------------------------------------------------

log "STEP9b: positive - PAUSED subtask redispatch (resume -> block -> reassign)"
PAU_RESP="$(http_json POST "$BASE_URL/api/sub-tasks" \
  "{\"taskId\":$TASK_ID,\"title\":\"redispatch-e2e-paused-$TS\",\"description\":\"paused redispatch\",\"assignedAgent\":$AGENT_A_ID}")"
assert_r200 "$PAU_RESP" "create paused subtask"
PAU_ID="$(print -r -- "$PAU_RESP" | jq -r '.data.id')"
PAU_START="$(http_json POST "$BASE_URL/api/sub-tasks/startById/$PAU_ID" "-")"
assert_r200 "$PAU_START" "start paused subtask"
PAU_PAUSE="$(http_json POST "$BASE_URL/api/sub-tasks/pauseById/$PAU_ID" "-")"
assert_r200 "$PAU_PAUSE" "pause subtask"
read -r PAU_STATUS _ <<<"$(get_subtask "$PAU_ID")"
assert_eq "$PAU_STATUS" "PAUSED" "subtask should be PAUSED before redispatch"
PAU_REDISP="$(http_json POST "$BASE_URL/api/sub-tasks/redispatchInProgressById/$PAU_ID" \
  "{\"agentId\":$AGENT_B_ID}")"
assert_r200 "$PAU_REDISP" "redispatch PAUSED subtask"
read -r PAU_STATUS2 PAU_AGENT2 <<<"$(get_subtask "$PAU_ID")"
assert_eq "$PAU_STATUS2" "ASSIGNED" "PAUSED redispatch should reach ASSIGNED"
assert_eq "$PAU_AGENT2" "$AGENT_B_ID" "PAUSED redispatch should assign to agentB"
log "PAUSED redispatch ok (status=$PAU_STATUS2 agent=$PAU_AGENT2)"

log "STEP10: cleanup - agentB checkOut (idempotent, best-effort)"
CHECKOUT_RESP="$(http_agent_json POST "$BASE_URL/api/mcp/tools/checkOut" \
  '{"closeReason":"e2e_verify_done"}' "$AGENT_B_KEY")" || true
log "checkOut done"

log "OK: redispatchInProgress e2e passed"
log "agentA=$AGENT_A_ID agentB=$AGENT_B_ID"
log "taskId=$TASK_ID subTaskId=$SUB_ID"
log "negativeSubTaskId=$NEG_ID pausedSubTaskId=$PAU_ID"
exit 0
