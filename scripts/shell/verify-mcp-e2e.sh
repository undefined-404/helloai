#!/bin/zsh

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:6565}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMP_ROOT="${TMP_ROOT:-$SCRIPT_DIR/.tmp}"
mkdir -p "$TMP_ROOT"
RUN_DIR="$(mktemp -d "$TMP_ROOT/verify-mcp-e2e.XXXXXX")"
SSE_FILE="$RUN_DIR/sse-mcp-e2e.txt"
SSE_ERR_FILE="$RUN_DIR/sse-mcp-e2e.err"
LOG_FILE="$RUN_DIR/m5-e2e-test.log"
PSQL_SNAPSHOT_FILE="$RUN_DIR/m5-e2e-psql.sql"
AGENT_NAME="${AGENT_NAME:-M5-test-executor-v10}"

typeset -g HTTP_CODE=""
typeset -g HTTP_BODY=""
typeset -g LAST_MCP_CODE=""
typeset -g LAST_MCP_BODY=""
typeset -g LAST_SSE_NEW_CONTENT=""
typeset -g ADMIN_TOKEN=""
typeset -g AGENT_ID=""
typeset -g AGENT_API_KEY=""
typeset -g TASK_ID=""
typeset -g SUB_TASK_ID=""
typeset -g SID=""
typeset -g SSE_PID=""
typeset -g PULL_TASKS_JSON=""

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
  create_body="$(jq -cn --arg name "$AGENT_NAME" --arg remark "M5 e2e test auto created (macOS)" '{name:$name,role:"EXECUTOR",remark:$remark}')"
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

log "=== [C] admin create task ==="
task_title="M5-e2e-task-$(date +%Y%m%d-%H%M%S)"
task_body="$(jq -cn --arg title "$task_title" --arg description "M5 e2e business loop verification auto created (macOS)" '{title:$title,description:$description}')"
http_request POST "$BASE_URL/api/tasks" "$task_body" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$HTTP_CODE" "创建任务失败"
TASK_ID="$(print -r -- "$HTTP_BODY" | jq -r '.data.id // empty')"
[[ -n "$TASK_ID" ]] || fail "未取到 taskId"
log "taskId = $TASK_ID"
log ""

log "=== [D] admin create subTask (assignedAgent=$AGENT_ID) ==="
sub_task_title="M5-subtask-$(date +%Y%m%d-%H%M%S)"
sub_task_body="$(jq -cn \
  --argjson taskId "$TASK_ID" \
  --arg title "$sub_task_title" \
  --arg description "M5 subtask assigned to $AGENT_ID" \
  --arg deliverable "README + attachment registration" \
  --arg acceptance "sub_task.status=DONE" \
  --arg priority "HIGH" \
  --argjson assignedAgent "$AGENT_ID" \
  '{taskId:$taskId,title:$title,description:$description,deliverable:$deliverable,acceptance:$acceptance,priority:$priority,assignedAgent:$assignedAgent}')"
http_request POST "$BASE_URL/api/sub-tasks" "$sub_task_body" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$HTTP_CODE" "创建子任务失败"
SUB_TASK_ID="$(print -r -- "$HTTP_BODY" | jq -r '.data.id // empty')"
[[ -n "$SUB_TASK_ID" ]] || fail "未取到 subTaskId"
log "subTaskId = $SUB_TASK_ID"
log ""

log "=== [E] start SSE long connection ==="
start_sse
log "sessionId = $SID"
log ""

init_body="$(jq -cn '{jsonrpc:"2.0",id:0,method:"initialize",params:{protocolVersion:"2024-11-05",capabilities:{},clientInfo:{name:"zsh-m5",version:"1.0.0"}}}')"
send_mcp "[F1] initialize with admin token" "$init_body" "X-Admin-Token: $ADMIN_TOKEN"

notify_body="$(jq -cn '{jsonrpc:"2.0",method:"notifications/initialized"}')"
send_mcp "[F2] notifications/initialized (admin token)" "$notify_body" "X-Admin-Token: $ADMIN_TOKEN"

g_body="$(jq -cn --argjson agentId "$AGENT_ID" --arg sid "$SID" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:"heartbeat",arguments:{agentId:$agentId,sessionId:$sid}}}')"
send_mcp "[G] tools/call heartbeat (agent apiKey)" "$g_body" "Authorization: Bearer $AGENT_API_KEY"
assert_eq "200" "$LAST_MCP_CODE" "G 心跳调用失败"
assert_contains "$LAST_SSE_NEW_CONTENT" '"id":1' "G SSE 应包含 id=1"
assert_contains "$LAST_SSE_NEW_CONTENT" '"isError":false' "G SSE 应包含 isError=false"

h_body="$(jq -cn --argjson agentId "$AGENT_ID" --arg sid "$SID" '{jsonrpc:"2.0",id:2,method:"tools/call",params:{name:"getAgentStatus",arguments:{agentId:$agentId,sessionId:$sid}}}')"
send_mcp "[H] tools/call getAgentStatus (agent apiKey)" "$h_body" "Authorization: Bearer $AGENT_API_KEY"
assert_eq "200" "$LAST_MCP_CODE" "H getAgentStatus 失败"
assert_contains "$LAST_SSE_NEW_CONTENT" '"id":2' "H SSE 应包含 id=2"
assert_contains "$LAST_SSE_NEW_CONTENT" '"isError":false' "H SSE 应包含 isError=false"

i_body="$(jq -cn --argjson agentId "$AGENT_ID" --arg sid "$SID" '{jsonrpc:"2.0",id:3,method:"tools/call",params:{name:"pullTasks",arguments:{agentId:$agentId,role:"EXECUTOR",max:20,sessionId:$sid}}}')"
send_mcp "[I] tools/call pullTasks (agent apiKey)" "$i_body" "Authorization: Bearer $AGENT_API_KEY"
assert_eq "200" "$LAST_MCP_CODE" "I pullTasks 失败"
PULL_TASKS_JSON="$(extract_result_text_json "$LAST_SSE_NEW_CONTENT")"
pull_subtask_id="$(print -r -- "$PULL_TASKS_JSON" | jq -r '.messages[0].subTaskId // empty')"
assert_eq "$SUB_TASK_ID" "$pull_subtask_id" "I pullTasks 结果里应包含目标 subTaskId"

j_body="$(jq -cn --argjson agentId "$AGENT_ID" --argjson subTaskId "$SUB_TASK_ID" --arg sid "$SID" '{jsonrpc:"2.0",id:4,method:"tools/call",params:{name:"claimSubTask",arguments:{agentId:$agentId,subTaskId:$subTaskId,sessionId:$sid}}}')"
send_mcp "[J] tools/call claimSubTask (agent apiKey)" "$j_body" "Authorization: Bearer $AGENT_API_KEY"
assert_eq "200" "$LAST_MCP_CODE" "J claimSubTask 失败"
assert_contains "$LAST_SSE_NEW_CONTENT" '"id":4' "J SSE 应包含 id=4"
assert_contains "$LAST_SSE_NEW_CONTENT" '"isError":false' "J SSE 应包含 isError=false"

log "=== [K] REST start subTask -> IN_PROGRESS ==="
http_request POST "$BASE_URL/api/sub-tasks/start/$SUB_TASK_ID" "" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$HTTP_CODE" "K start 子任务失败"
log "start Body: $HTTP_BODY"
log ""

l_body="$(jq -cn --argjson agentId "$AGENT_ID" --arg sid "$SID" '{jsonrpc:"2.0",id:5,method:"tools/call",params:{name:"heartbeat",arguments:{agentId:$agentId,sessionId:$sid}}}')"
send_mcp "[L] tools/call heartbeat 2nd (agent apiKey)" "$l_body" "Authorization: Bearer $AGENT_API_KEY"
assert_eq "200" "$LAST_MCP_CODE" "L 第二次 heartbeat 失败"
assert_contains "$LAST_SSE_NEW_CONTENT" '"isError":false' "L SSE 应包含 isError=false"

m_body="$(jq -cn --argjson agentId "$AGENT_ID" --argjson subTaskId "$SUB_TASK_ID" --arg sid "$SID" --arg storageUrl "minio://helloai-test/M5-test/$SUB_TASK_ID/result.txt" '{jsonrpc:"2.0",id:6,method:"tools/call",params:{name:"uploadArtifact",arguments:{agentId:$agentId,subTaskId:$subTaskId,fileName:"M5-result.txt",mimeType:"text/plain",fileSize:1024,storageUrl:$storageUrl,sessionId:$sid}}}')"
send_mcp "[M] tools/call uploadArtifact (agent apiKey)" "$m_body" "Authorization: Bearer $AGENT_API_KEY"
assert_eq "200" "$LAST_MCP_CODE" "M uploadArtifact 失败"
upload_artifact_json="$(extract_result_text_json "$LAST_SSE_NEW_CONTENT")"
attachment_id="$(print -r -- "$upload_artifact_json" | jq -r '.attachmentId // empty')"
[[ -n "$attachment_id" ]] || fail "M 结果里应包含 attachmentId"

log "=== [N] tools/call ack (mark inbox as read) ==="
inbox_id="$(print -r -- "$PULL_TASKS_JSON" | jq -r '.messages[0].messageId // empty' | sed 's/^inbox-//')"
[[ -n "$inbox_id" ]] || fail "未能从 SSE 日志中解析 inboxId"
n_body="$(jq -cn --argjson agentId "$AGENT_ID" --arg messageId "inbox-$inbox_id" --arg sid "$SID" '{jsonrpc:"2.0",id:7,method:"tools/call",params:{name:"ack",arguments:{agentId:$agentId,messageId:$messageId,sessionId:$sid}}}')"
send_mcp "[N] tools/call ack (agent apiKey)" "$n_body" "Authorization: Bearer $AGENT_API_KEY"
assert_eq "200" "$LAST_MCP_CODE" "N ack 失败"
assert_contains "$LAST_SSE_NEW_CONTENT" '"isError":false' "N SSE 应包含 isError=false"

log "=== [O] REST submit subTask -> REVIEW ==="
http_request POST "$BASE_URL/api/sub-tasks/submit/$SUB_TASK_ID" "" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$HTTP_CODE" "O submit 子任务失败"
log "submit Body: $HTTP_BODY"
log ""

log "=== [P] REST complete subTask -> DONE ==="
http_request POST "$BASE_URL/api/sub-tasks/complete/$SUB_TASK_ID" "" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$HTTP_CODE" "P complete 子任务失败"
log "complete Body: $HTTP_BODY"
log ""

log "=== [Q] admin GET agent detail ==="
http_request GET "$BASE_URL/api/admin/agents/$AGENT_ID" "" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$HTTP_CODE" "Q 查询 agent 详情失败"
last_activity="$(print -r -- "$HTTP_BODY" | jq -r '.data.lastActivityAt // .data.lastActiveAt // empty')"
[[ -n "$last_activity" ]] || fail "Q 未看到 lastActivityAt/lastActiveAt 被刷新"
log "lastActivityAt = $last_activity"
log ""

log "=== [R] admin GET subTask status ==="
http_request GET "$BASE_URL/api/sub-tasks/$SUB_TASK_ID" "" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$HTTP_CODE" "R 查询 subTask 详情失败"
subtask_status="$(print -r -- "$HTTP_BODY" | jq -r '.data.status // empty')"
assert_eq "DONE" "$subtask_status" "R subTask 最终状态应为 DONE"
log "subTask status = $subtask_status"
log ""

log "=== [S] HTTP GET /api/agent/inbox/count ==="
http_request GET "$BASE_URL/api/agent/inbox/count" "" "Authorization: Bearer $AGENT_API_KEY"
assert_eq "200" "$HTTP_CODE" "S inbox/count 查询失败"
log "inbox/count Body: $HTTP_BODY"
log ""

cat >"$PSQL_SNAPSHOT_FILE" <<EOF
-- T1. inbox read
SELECT id, agent_id, event_type, ref_type, ref_id, is_read, read_time
FROM agent_inbox
WHERE agent_id = $AGENT_ID AND deleted = 0
ORDER BY id DESC LIMIT 5;

-- T2. attachment registered
SELECT id, sub_task_id, file_name, mime_type, file_size, storage_url, status
FROM attachment
WHERE sub_task_id = $SUB_TASK_ID AND deleted = 0
ORDER BY id DESC LIMIT 5;

-- T3. sub_task final state
SELECT id, status, assigned_agent_id, complete_time, composite_score, score_grade
FROM sub_task
WHERE id = $SUB_TASK_ID AND deleted = 0;

-- T4. agent heartbeat fields
SELECT id, name, last_seen_time, last_active_time, online_status
FROM agent
WHERE id = $AGENT_ID;
EOF

log "=== Cleanup ==="
log "SSE log:   $SSE_FILE"
log "DB script: $PSQL_SNAPSHOT_FILE"
log "Test agent: id=$AGENT_ID name=$AGENT_NAME (需要时可在管理后台删除)"
log "Test task/subTask: taskId=$TASK_ID subTaskId=$SUB_TASK_ID"
log "Done."
