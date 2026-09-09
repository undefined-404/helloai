#!/usr/bin/env zsh
# ============================================================
# helloai 外部 EXECUTOR agent 半程状态流转 E2E 验证脚本（macOS/Linux）
# 用途：验证「外部 CLI_CLIENT Agent 值班打卡 → 平台拆解 → 派单/认领 →
#       inbox 通知 → 执行推进 → 提交 → 审查入口」闭环。
#       不跑真实 LLM 执行（状态可控半程测试）：外部 agent 用 REST/MCP 主动流转
#       状态机，平台侧拆解（LLM）与自动核验（平台内 REVIEWER + LLM）作为
#       既有能力被本脚本复用；外部真实「执行内容产出」不在本轮断言范围。
# 链路：
#   POST /api/agents/register                   注册 CLI_CLIENT EXECUTOR（无 modelType）
#   MCP  tools/call checkIn / checkOut          值班打卡（SSE 通道）
#   POST /api/tasks/planById/{id}               平台内 PLANNER 拆解（异步，轮询草案）
#   GET  /api/tasks/findPlanByTaskId/{id}       轮询草案（每 3s，窗口默认 360s）
#   POST /api/tasks/confirmPlanByTaskId/{id}    草案转正（白名单绑定本 agent）
#   POST /api/sub-tasks/claimById/{id}          PENDING → ASSIGNED（外部 agent 认领）
#   GET  /api/agent/inbox                       外部 agent 收件箱（sub_task.assigned）
#   POST /api/sub-tasks/startById/{id}          ASSIGNED → IN_PROGRESS
#   POST /api/sub-tasks/submitById/{id}         IN_PROGRESS → REVIEW（审查入口）
# Ref:  doc/HelloAI_实现差距表.md（G-010/G-011 S5 实测：外部执行链缺口登记）
# Pre-conditions（fail-fast，本脚本不负责启动服务）：
#   - docker compose up -d（helloai-postgres:15432）
#   - helloai-start 已在 6565 运行；LLM 可用（拆解 + 自动核验）
#   - 平台内 PLANNER（API_KEY_LLM）：脚本查名复用 planner-decompose，
#     不存在才注册 + 绑定托管凭证（需 DEESEEK_API_KEY / LLM_API_KEY）
# Usage:
#   zsh ./scripts/shell/verify-external-executor-e2e.sh
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
PLANNER_MODEL_TYPE="${PLANNER_MODEL_TYPE:-deepseek:deepseek-v4-pro}"
PLAN_TIMEOUT_SEC="${PLAN_TIMEOUT_SEC:-360}"
EXT_EXECUTOR_NAME="${EXT_EXECUTOR_NAME:-ext-executor-e2e}"
# require-vault=true 时拆解必须有托管凭证；对齐 verify-planner-decompose.sh 默认
LLM_API_KEY="${DEEPSEEK_API_KEY:-sk-a36fdda1d4ad4e0386e78fc435be0d16}"
VAULT_PROVIDER="${VAULT_PROVIDER:-deepseek}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="${LOG_FILE:-$SCRIPT_DIR/.tmp/verify-external-executor-e2e.log}"
mkdir -p "$(dirname "$LOG_FILE")"

PG_CONTAINER="${PG_CONTAINER:-helloai-postgres}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-helloai}"

SSE_FILE="${SSE_FILE:-$SCRIPT_DIR/.tmp/verify-external-executor-e2e-sse.txt}"
SSE_PID=""

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
  print -r -- "ASSERT_FAIL: $*" >&2
  exit 1
}

assert_eq() {
  local actual="$1" expected="$2" msg="$3"
  [[ "$actual" == "$expected" ]] || fail "$msg (expected=$expected actual=$actual)"
}

# http_json <method> <url> <json-body|-> <timeout-sec> <extra-header|-> 
# 输出响应 body；curl 层错误直接 fail
http_json() {
  local method="$1" url="$2" body="$3" timeout="${4:-30}" extra="${5:--}"
  local -a args
  args=(-sS -X "$method" "$url" -H "Content-Type: application/json" --max-time "$timeout")
  [[ -n "${ADMIN_TOKEN:-}" ]] && args+=(-H "X-Admin-Token: $ADMIN_TOKEN")
  [[ "$extra" != "-" ]] && args+=(-H "$extra")
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

# psql_field <n> <sql> — docker exec psql 取首行第 n 个字段（1 起）
run_psql_one_row() {
  local sql="$1" out_var="$2"
  local sql_file raw
  sql_file="$(mktemp -t veee-sql.XXXXXX)"
  print -r -- "$sql" >"$sql_file"
  raw="$(docker exec -i "$PG_CONTAINER" psql \
      -v ON_ERROR_STOP=1 -X -t -A -F '|' \
      -U "$PG_USER" -d "$PG_DB" <"$sql_file" 2>&1)" || {
    rm -f "$sql_file"
    fail "psql exec failed. raw=$raw"
  }
  rm -f "$sql_file"
  local parsed
  parsed="$(print -r -- "$raw" | awk 'NF && $0 !~ /^\(/ {print; exit}')"
  [[ -n "$parsed" ]] || fail "psql returned empty result. raw=$raw"
  eval "${out_var}=\"\$(print -r -- \"\$parsed\" | tr '|' '\n')\""
}

psql_field() {
  local n="$1" sql="$2" rows
  run_psql_one_row "$sql" "rows"
  local -a fields=()
  local line
  while IFS= read -r line; do
    [[ -n "$line" ]] && fields+=("$line")
  done <<<"$rows"
  [[ "${#fields[@]}" -ge "$n" ]] || fail "psql field $n missing. raw=$(print -r -- "$rows")"
  print -r -- "${fields[$n]}"
}

# create_task <title> <description> — 白名单任务，输出 taskId
create_task() {
  local title="$1" desc="$2" resp st
  resp="$(http_json POST "$BASE_URL/api/tasks" \
    "{\"title\":\"$title\",\"description\":\"$desc\",\"agentPolicy\":{\"executorAgentIds\":[$EXT_EXECUTOR_ID]}}")"
  assert_r200 "$resp" "create task"
  st="$(print -r -- "$resp" | jq -r '.data.status')"
  assert_eq "$st" "PENDING" "unexpected task status after create"
  print -r -- "$resp" | jq -r '.data.id'
}

# wait_for_drafts <taskId> <maxSeconds> — 拆解异步化后草案经 findPlanByTaskId 轮询
wait_for_drafts() {
  local task_id="$1" max_secs="$2" waited=0 count=0 resp
  while (( waited < max_secs )); do
    resp="$(http_json GET "$BASE_URL/api/tasks/findPlanByTaskId/$task_id" "-")"
    count="$(print -r -- "$resp" | jq -r '.data | length')"
    (( count >= 1 )) && { print -r -- "$count"; return 0; }
    sleep 3
    (( waited = waited + 3 ))
  done
  print -r -- "$count"
  return 1
}

# ---- MCP-over-SSE（值班打卡通道，参考 verify-agenthub-duty-e2e.sh） ----
start_mcp_sse() {
  rm -f "$SSE_FILE"
  curl -s -i -N "$BASE_URL/mcp/sse" >"$SSE_FILE" 2>&1 &
  SSE_PID=$!
  sleep 3
  SID="$(grep -o 'sessionId=[A-Za-z0-9-]*' "$SSE_FILE" 2>/dev/null | head -1 | cut -d= -f2 || true)"
  [[ -n "$SID" ]] || fail "sessionId extraction failed; see $SSE_FILE"
}

send_mcp() {
  local body="$1" label="$2" auth_header="$3"
  log "=== $label ==="
  local pos_before pos_after
  pos_before="$(wc -c <"$SSE_FILE" | tr -d ' ')"
  HTTP_BODY="$(curl -sS -X POST "$BASE_URL/mcp/messages?sessionId=$SID" \
    -H "Content-Type: application/json" -H "$auth_header" --data "$body" --max-time 30)" || {
    kill "$SSE_PID" 2>/dev/null || true
    fail "MCP $label POST failed (curl error)"
  }
  sleep 2
  pos_after="$(wc -c <"$SSE_FILE" | tr -d ' ')"
  if (( pos_after > pos_before )); then
    dd if="$SSE_FILE" bs=1 skip="$pos_before" count=$((pos_after - pos_before)) 2>/dev/null | tee -a "$LOG_FILE"
  fi
  log ""
}

stop_mcp_sse() {
  if [[ -n "$SSE_PID" ]]; then
    kill "$SSE_PID" 2>/dev/null || true
    wait "$SSE_PID" 2>/dev/null || true
    SSE_PID=""
  fi
}

cleanup() {
  stop_mcp_sse
}
trap cleanup EXIT

# ============================================================
# pre-flight
# ============================================================
need_cmd curl
need_cmd jq
need_cmd docker
need_cmd mktemp
need_cmd dd

: > "$LOG_FILE"

log "STEP1: admin login"
LOGIN_RESP="$(http_json POST "$BASE_URL/api/auth/login" \
  "{\"type\":\"admin\",\"username\":\"$ADMIN_USERNAME\",\"credential\":\"$ADMIN_PASSWORD\"}")"
assert_r200 "$LOGIN_RESP" "login"
ADMIN_TOKEN="$(print -r -- "$LOGIN_RESP" | jq -r '.data.token // empty')"
[[ -n "$ADMIN_TOKEN" ]] || fail "admin token is empty"
log "adminToken=${ADMIN_TOKEN[1,16]}..."

# ------------------------------------------------------------
# STEP2: 外部 CLI_CLIENT EXECUTOR（查名复用，缺则注册；避免带 modelType
#        注册撞 validateModelUniqueInRole——CLI_CLIENT 不携带 modelType）
# ------------------------------------------------------------
log "STEP2: prepare external CLI_CLIENT EXECUTOR ($EXT_EXECUTOR_NAME)"
AGENT_LIST="$(http_json GET "$BASE_URL/api/admin/agents/list?page=1&pageSize=200" "-")"
assert_r200 "$AGENT_LIST" "admin agents list"
EXT_EXECUTOR_ID="$(print -r -- "$AGENT_LIST" | jq -r --arg n "$EXT_EXECUTOR_NAME" \
  '.data.list[]? | select(.name == $n and .role == "EXECUTOR") | .id' | head -1)"
if [[ -z "$EXT_EXECUTOR_ID" ]]; then
  REG_RESP="$(http_json POST "$BASE_URL/api/agents/register" \
    "{\"name\":\"$EXT_EXECUTOR_NAME\",\"role\":\"EXECUTOR\",\"description\":\"verify-external-executor-e2e\",\"accessType\":\"CLI_CLIENT\",\"idempotent\":true}")"
  assert_r200 "$REG_RESP" "register external executor"
  EXT_EXECUTOR_ID="$(print -r -- "$REG_RESP" | jq -r '.data.id // empty')"
  EXT_EXECUTOR_KEY="$(print -r -- "$REG_RESP" | jq -r '.data.apiKey // empty')"
  [[ -n "$EXT_EXECUTOR_ID" ]] || fail "external executor register returned no id"
  [[ -n "$EXT_EXECUTOR_KEY" ]] || fail "external executor register returned no apiKey"
  log "registered executorId=$EXT_EXECUTOR_ID"
else
  EXT_EXECUTOR_KEY="$(print -r -- "$AGENT_LIST" | jq -r --arg n "$EXT_EXECUTOR_NAME" \
    '.data.list[]? | select(.name == $n and .role == "EXECUTOR") | .apiKey' | head -1)"
  [[ -n "$EXT_EXECUTOR_KEY" ]] || fail "external executor apiKey missing (check AgentListItemVO.apiKey mapping)"
  log "reuse existing executorId=$EXT_EXECUTOR_ID"
fi
log "executorApiKey=${EXT_EXECUTOR_KEY[1,16]}..."

# 确保 checkIn/checkOut MCP 工具已挂载（幂等 seed）
seed_sql="INSERT INTO agent_mcp_server (agent_id, tool_name, is_enabled, rate_limit, create_by, update_by)
SELECT $EXT_EXECUTOR_ID, tool.name, 1, 0, 'e2e', 'e2e'
FROM (VALUES ('checkIn'), ('checkOut')) AS tool(name)
ON CONFLICT (agent_id, tool_name) WHERE deleted = 0 DO NOTHING;"
print -r -- "$seed_sql" | docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -X -t -A -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1 || true

# ------------------------------------------------------------
# STEP2.1: 平台内 PLANNER（查名复用 planner-decompose；缺则注册 + 绑定凭证。
#          带 modelType 的重复注册会撞 validateModelUniqueInRole 500，
#          因此查名命中后绝不重新 register）
# ------------------------------------------------------------
log "STEP2.1: prepare platform PLANNER (planner-decompose)"
PLANNER_ID="$(print -r -- "$AGENT_LIST" | jq -r \
  '.data.list[]? | select(.name == "planner-decompose" and .role == "PLANNER") | .id' | head -1)"
if [[ -z "$PLANNER_ID" ]]; then
  PLANNER_RESP="$(http_json POST "$BASE_URL/api/agents/register" \
    "{\"name\":\"planner-decompose\",\"role\":\"PLANNER\",\"description\":\"verify-external-executor-e2e\",\"accessType\":\"API_KEY_LLM\",\"modelType\":\"$PLANNER_MODEL_TYPE\",\"idempotent\":true}")"
  assert_r200 "$PLANNER_RESP" "register planner"
  PLANNER_ID="$(print -r -- "$PLANNER_RESP" | jq -r '.data.id // empty')"
  BIND_RESP="$(http_json POST "$BASE_URL/api/credentials/bindApiKeyByAgentId/$PLANNER_ID" \
    "{\"provider\":\"$VAULT_PROVIDER\",\"apiKey\":\"$LLM_API_KEY\",\"remark\":\"verify-external-executor-e2e\"}")"
  assert_r200 "$BIND_RESP" "bind planner api-key"
  log "registered+bound plannerId=$PLANNER_ID"
else
  log "reuse existing plannerId=$PLANNER_ID"
fi

# ------------------------------------------------------------
# STEP3: MCP SSE 值班打卡 checkIn → lease ACTIVE
# ------------------------------------------------------------
log "STEP3: MCP checkIn (workMode=AUTO, maxConcurrent=1)"
start_mcp_sse
log "sessionId=$SID"
send_mcp '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"ext-executor-e2e","version":"1.0"}}}' \
  "initialize" "X-Admin-Token: $ADMIN_TOKEN"
send_mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}' "initialized" "X-Admin-Token: $ADMIN_TOKEN"
CHECKIN_BODY="$(jq -cn --argjson aid "$EXT_EXECUTOR_ID" --arg sid "$SID" \
  '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"checkIn",arguments:{agentId:$aid,workMode:"AUTO",maxConcurrent:1,ttlMinutes:10,sessionId:$sid}}}')"
send_mcp "$CHECKIN_BODY" "checkIn" "Authorization: Bearer $EXT_EXECUTOR_KEY"
LEASE_STATUS="$(psql_field 1 "SELECT status FROM agent_duty_lease WHERE agent_id = $EXT_EXECUTOR_ID AND deleted = 0 ORDER BY id DESC LIMIT 1;")"
assert_eq "$LEASE_STATUS" "ACTIVE" "checkIn -> lease ACTIVE"
log "STEP3 OK: checkIn -> lease ACTIVE"

# ------------------------------------------------------------
# STEP4: 白名单任务（executorAgentIds=[外部 agent]）
# ------------------------------------------------------------
TS="$(date -u +%Y%m%d%H%M%S)"
log "STEP4: create whitelist task"
TASK_ID="$(create_task "ext-executor-e2e-$TS" \
  "Build a daily report module: DB schema, statistics REST API, frontend chart page, unit tests and deployment doc.")"
log "taskId=$TASK_ID"

# ------------------------------------------------------------
# STEP5: 平台内拆解（异步，轮询草案）
# ------------------------------------------------------------
log "STEP5: trigger decompose (async, poll drafts; timeout=${PLAN_TIMEOUT_SEC}s)"
PLAN_RESP="$(http_json POST "$BASE_URL/api/tasks/planById/$TASK_ID" "{}" "30")"
assert_r200 "$PLAN_RESP" "plan"
DRAFT_COUNT="$(wait_for_drafts "$TASK_ID" "$PLAN_TIMEOUT_SEC")" || true
(( DRAFT_COUNT >= 1 )) || fail "expected >=1 drafts, actual=$DRAFT_COUNT"
log "draftCount=$DRAFT_COUNT"

# ------------------------------------------------------------
# STEP6: 确认草案 → 子任务转正（PENDING 或 ASSIGNED 双态兼容）
# ------------------------------------------------------------
log "STEP6: confirm plan"
CONFIRM_RESP="$(http_json POST "$BASE_URL/api/tasks/confirmPlanByTaskId/$TASK_ID" "{}")"
assert_r200 "$CONFIRM_RESP" "confirm"
SUB_COUNT="$(print -r -- "$CONFIRM_RESP" | jq -r '.data | length')"
assert_eq "$SUB_COUNT" "$DRAFT_COUNT" "confirmed count mismatch"
SUB_TASK_ID="$(print -r -- "$CONFIRM_RESP" | jq -r '.data[0].id')"
SUB_STATUS="$(print -r -- "$CONFIRM_RESP" | jq -r '.data[0].status')"
[[ "$SUB_STATUS" == "PENDING" || "$SUB_STATUS" == "ASSIGNED" ]] || fail "unexpected subtask status after confirm: $SUB_STATUS"
log "subTaskId=$SUB_TASK_ID status=$SUB_STATUS (count=$SUB_COUNT)"

# ------------------------------------------------------------
# STEP6.1: 若仍 PENDING → 外部 agent 主动认领（claimById，Bearer 身份）
# ------------------------------------------------------------
if [[ "$SUB_STATUS" == "PENDING" ]]; then
  log "STEP6.1: claim by external agent"
  CLAIM_RESP="$(http_json POST "$BASE_URL/api/sub-tasks/claimById/$SUB_TASK_ID?agentId=$EXT_EXECUTOR_ID" "{}" "30" \
    "Authorization: Bearer $EXT_EXECUTOR_KEY")"
  assert_r200 "$CLAIM_RESP" "claim"
  SUB_STATUS="ASSIGNED"
  log "claimed -> ASSIGNED"
fi
ASSIGNED_ID="$(psql_field 1 "SELECT COALESCE(assigned_agent_id::text, 'NULL') FROM sub_task WHERE id = $SUB_TASK_ID AND deleted = 0;")"
assert_eq "$ASSIGNED_ID" "$EXT_EXECUTOR_ID" "assigned_agent mismatch"
log "STEP6 OK: subtask owned by external executor"

# ------------------------------------------------------------
# STEP7: inbox 校验（外部 agent 身份查收，断言有 sub_task 通知）
# ------------------------------------------------------------
log "STEP7: assert inbox notification (sub_task)"
INBOX_RESP="$(http_json GET "$BASE_URL/api/agent/inbox?limit=20" "-" "30" "Authorization: Bearer $EXT_EXECUTOR_KEY")"
assert_r200 "$INBOX_RESP" "inbox"
INBOX_HITS="$(print -r -- "$INBOX_RESP" | jq '[.data[]? | select(.refType == "sub_task" or (.eventType // "" | contains("assigned")))] | length')"
(( INBOX_HITS >= 1 )) || fail "expected >=1 sub_task inbox notification, actual=$INBOX_HITS"
log "inbox sub_task notifications=$INBOX_HITS"

# ------------------------------------------------------------
# STEP8: 执行推进 startById → IN_PROGRESS
# ------------------------------------------------------------
log "STEP8: start execution"
START_RESP="$(http_json POST "$BASE_URL/api/sub-tasks/startById/$SUB_TASK_ID" "{}" "30" \
  "Authorization: Bearer $EXT_EXECUTOR_KEY")"
assert_r200 "$START_RESP" "start"
SUB_STATUS="$(psql_field 1 "SELECT status FROM sub_task WHERE id = $SUB_TASK_ID AND deleted = 0;")"
assert_eq "$SUB_STATUS" "IN_PROGRESS" "subtask status after start"
log "STEP8 OK: IN_PROGRESS"

# ------------------------------------------------------------
# STEP9: 提交结果 submitById → REVIEW（审查入口）
# ------------------------------------------------------------
log "STEP9: submit result"
SUBMIT_RESP="$(http_json POST "$BASE_URL/api/sub-tasks/submitById/$SUB_TASK_ID" "{}" "30" \
  "Authorization: Bearer $EXT_EXECUTOR_KEY")"
assert_r200 "$SUBMIT_RESP" "submit"
SUB_STATUS="$(psql_field 1 "SELECT status FROM sub_task WHERE id = $SUB_TASK_ID AND deleted = 0;")"
assert_eq "$SUB_STATUS" "REVIEW" "subtask status right after submit"
log "STEP9 OK: REVIEW (auto-review-enabled=true 时平台内 REVIEWER 会随后自动核验推进)"

# 软观察：自动核验链可能把 REVIEW 推进到 DONE（APPROVED）或返工（ASSIGNED/IN_PROGRESS）
sleep 8
SUB_STATUS_AFTER="$(psql_field 1 "SELECT status FROM sub_task WHERE id = $SUB_TASK_ID AND deleted = 0;")"
case "$SUB_STATUS_AFTER" in
  REVIEW|DONE|ASSIGNED|IN_PROGRESS) log "observe: status after 8s=$SUB_STATUS_AFTER (auto-review chain may be in flight)" ;;
  *) fail "unexpected status after submit: $SUB_STATUS_AFTER" ;;
esac

# ------------------------------------------------------------
# STEP10: 值班签退 checkOut → lease CLOSED
# ------------------------------------------------------------
log "STEP10: checkOut"
CHECKOUT_BODY="$(jq -cn --argjson aid "$EXT_EXECUTOR_ID" --arg sid "$SID" \
  '{jsonrpc:"2.0",id:2,method:"tools/call",params:{name:"checkOut",arguments:{agentId:$aid,closeReason:"e2e_complete",sessionId:$sid}}}')"
send_mcp "$CHECKOUT_BODY" "checkOut" "Authorization: Bearer $EXT_EXECUTOR_KEY"
LEASE_STATUS="$(psql_field 1 "SELECT status FROM agent_duty_lease WHERE agent_id = $EXT_EXECUTOR_ID AND deleted = 0 ORDER BY id DESC LIMIT 1;")"
assert_eq "$LEASE_STATUS" "CLOSED" "checkOut -> lease CLOSED"
stop_mcp_sse

log ""
log "ALL PASSED: register(CLI_CLIENT) / checkIn / decompose(async) / confirm / claim / inbox / start / submit(REVIEW) / checkOut"
log "residual test data: taskId=$TASK_ID subTaskId=$SUB_TASK_ID executorAgentId=$EXT_EXECUTOR_ID (cleanup-test-data.sql 可清理)"