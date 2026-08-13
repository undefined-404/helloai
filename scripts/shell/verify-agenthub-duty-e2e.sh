#!/usr/bin/env zsh
# ============================================================
# helloai AgentHub V1 P0/P1 + N12 A1/A2 全场景验证脚本 (macOS/Linux)
# Ref:
#   doc/HelloAI_迭代执行记录.md   (AgentHub V1 P0 / N12 A1/A2)
#   doc/HelloAI_实现差距表.md     (N12)
#   .agents/skills/helloai-preflight/SKILL.md   (规则 6: 脚本 UTF-8 编码头)
#
# 覆盖八个真实环境场景（IDEA 启动后端 + docker compose 起 postgres / redis / rabbitmq）：
#   S1  MCP-over-SSE tools/call checkIn      -> agent_duty_lease 出现 status=ACTIVE 行
#   S2  MCP-over-SSE tools/call checkOut     -> 同一行翻为 CLOSED，close_reason 匹配
#   S3  手工 INSERT 一条 expire_time 已过期的 ACTIVE 租约（独立 test agent）
#        -> 等 35s，DutyLeaseExpirationTask (@Scheduled fixedRate=30s) 巡检
#        -> DB 校验 status='EXPIRED', close_reason='lease_expired'
#   S6  N12 A2 第 1 段 STRICT 独占报锁：STRICT / 小写 strict / BOGUS_VALUE 拒绝
#   S7  E1 动态 TTL 自适应（N12 A2 第 2 段）：score=0 -> ~5min / score=100 -> ~240min
#   S8  E2 并发额度预扣（N12 A2 第 3 段）：maxConcurrent=1 派发即占用
#        S8.1 checkIn(maxConcurrent=1) -> 租约 quota=1
#        S8.2 建 t1（白名单）自动派发选中 -> S8.3 建 t2 满额被跳过（保持 PENDING）
#        S8.4 submitResult 释放 -> 建 t3 重派成功 -> S8.5 并发建 t4/t5 在飞数 <=1
#        S8.6 cleanup checkOut + 任务级联删除
#
# Pre-conditions:
#   - docker compose up -d (helloai-postgres:15432)
#   - helloai-start via IDEA @ :6565 with:
#       helloai.job.enabled = true (DutyLeaseExpirationTask 需要 @Scheduled 启用)
#       helloai.dispatch.auto-assign-on-create = true (S8 需要；脚本有行为自检)
#       redis 可达（DutyLeaseExpirationTask 用 Redis Lua 锁）
#   - Flyway 已跑到 V21（agent_mcp_server 已 seed checkIn/checkOut）
#
# Usage:
#   chmod +x ./scripts/shell/verify-agenthub-duty-e2e.sh
#   ./scripts/shell/verify-agenthub-duty-e2e.sh
# 或:
#   zsh ./scripts/shell/verify-agenthub-duty-e2e.sh
# ============================================================

set -euo pipefail

# ------------------------------------------------------------
# UTF-8 编码强制头 (规则 6) — 避免中文乱码
# ------------------------------------------------------------
export LANG="${LANG:-zh_CN.UTF-8}"
export LC_ALL="${LC_ALL:-zh_CN.UTF-8}"

BASE_URL="${BASE_URL:-http://localhost:6565}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="${LOG_FILE:-$SCRIPT_DIR/.tmp/verify-agenthub-duty-e2e.log}"
mkdir -p "$(dirname "$LOG_FILE")"

PG_CONTAINER="${PG_CONTAINER:-helloai-postgres}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-helloai}"

SSE_FILE="${SSE_FILE:-$SCRIPT_DIR/.tmp/verify-agenthub-duty-e2e-sse.txt}"
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
  log "ERROR: $*"
  exit 1
}

http_request() {
  local method="$1"
  local url="$2"
  local body="$3"
  shift 3

  local body_file
  body_file="$(mktemp -t vade-body.XXXXXX)"

  local -a curl_args
  curl_args=(-sS -X "$method" "$url" -o "$body_file" -w "%{http_code}" -H "Accept: application/json")
  if [[ "$method" != "GET" ]]; then
    curl_args+=(-H "Content-Type: application/json" --data "$body")
  fi

  local header
  for header in "$@"; do
    if [[ -n "$header" ]]; then
      curl_args+=(-H "$header")
    fi
  done

  local code
  if ! code="$(curl "${curl_args[@]}")"; then
    HTTP_BODY="$(cat "$body_file" 2>/dev/null || true)"
    rm -f "$body_file"
    fail "$method $url failed (curl error)"
  fi

  HTTP_CODE="$code"
  HTTP_BODY="$(cat "$body_file")"
  rm -f "$body_file"
}

assert_eq() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  [[ "$expected" == "$actual" ]] || fail "$message, expected=$expected actual=$actual"
}

# docker exec psql: pipe-delim rows, first non-empty line is the result row;
# 结果通过 eval 导出为按换行拆分的字段数组（zsh 数组 1 起）
run_psql_one_row() {
  local sql="$1"
  local out_var="$2"

  local sql_file
  sql_file="$(mktemp -t vade-sql.XXXXXX)"
  print -r -- "$sql" >"$sql_file"

  local raw
  raw="$(docker exec -i "$PG_CONTAINER" psql \
      -v ON_ERROR_STOP=1 \
      -X -t -A -F '|' \
      -U "$PG_USER" -d "$PG_DB" <"$sql_file" 2>&1)" || {
    rm -f "$sql_file"
    fail "psql exec failed. raw=$raw"
  }
  rm -f "$sql_file"

  local parsed
  parsed="$(print -r -- "$raw" | awk 'NF && $0 !~ /^\(/ {print; exit}')"

  if [[ -z "$parsed" ]]; then
    fail "psql returned empty result. raw=$raw"
  fi

  # shellcheck disable=SC2034 # exported via eval into caller's namespace
  eval "${out_var}=\"\$(print -r -- \"\$parsed\" | tr '|' '\n')\""
}

# 执行 SQL 并取首行第 n 个字段（1 起）；SQL 必须保证至少返回一行
psql_field() {
  local n="$1"
  local sql="$2"
  local rows
  run_psql_one_row "$sql" "rows"
  local -a fields=()
  while IFS= read -r line; do
    [[ -n "$line" ]] && fields+=("$line")
  done <<<"$rows"
  [[ "${#fields[@]}" -ge "$n" ]] || fail "psql field $n missing. raw=$(print -r -- "$rows")"
  print -r -- "${fields[$n]}"
}

# MCP SSE: 后台 curl 长连接 + 提取 sessionId
start_mcp_sse() {
  rm -f "$SSE_FILE"
  curl -s -i -N "$BASE_URL/mcp/sse" >"$SSE_FILE" 2>&1 &
  SSE_PID=$!
  sleep 3
  SID="$(grep -o 'sessionId=[A-Za-z0-9-]*' "$SSE_FILE" 2>/dev/null | head -1 | cut -d= -f2 || true)"
  [[ -n "$SID" ]] || fail "sessionId extraction failed; see $SSE_FILE"
}

# MCP tools/call: POST /mcp/messages + 输出 SSE 增量
send_mcp() {
  local body="$1"
  local label="$2"
  local auth_header="$3"
  log "=== $label ==="
  log "Body: $body"
  local pos_before
  pos_before="$(wc -c <"$SSE_FILE" | tr -d ' ')"
  http_request POST "$BASE_URL/mcp/messages?sessionId=$SID" "$body" "$auth_header"
  log "POST HTTP: $HTTP_CODE"
  log "POST Body: $HTTP_BODY"
  sleep 2
  local pos_after
  pos_after="$(wc -c <"$SSE_FILE" | tr -d ' ')"
  if [[ "$pos_after" -gt "$pos_before" ]]; then
    log "--- SSE new content ($pos_before -> $pos_after) ---"
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
# pre-flight: docker postgres + server reachability
# ============================================================
need_cmd curl
need_cmd jq
need_cmd docker
need_cmd mktemp
need_cmd dd

log "=== [0] pre-flight ==="

docker_check="$(docker ps --format '{{.Names}}|{{.Status}}' --filter "name=$PG_CONTAINER" 2>&1 || true)"
if [[ "$docker_check" != *"${PG_CONTAINER}|Up"* ]]; then
  fail "container [$PG_CONTAINER] is NOT up. Run: docker compose up -d"
fi
log "postgres container up"

if ! http_code="$(curl -sS -o /dev/null -w "%{http_code}" --max-time 3 "$BASE_URL/api/health")"; then
  fail "server NOT reachable at $BASE_URL - start HelloAIApplication via IDEA first"
fi
log "server $BASE_URL HTTP $http_code"
log ""

# ============================================================
# [A] admin login
# ============================================================
log "=== [A] admin login ==="
login_body="$(jq -cn '{type:"admin",username:"admin",credential:"admin123"}')"
http_request POST "$BASE_URL/api/auth/login" "$login_body"
assert_eq "200" "$HTTP_CODE" "admin login failed"
ADMIN_TOKEN="$(print -r -- "$HTTP_BODY" | jq -r '.data.token // empty')"
[[ -n "$ADMIN_TOKEN" ]] || fail "no admin token: $HTTP_BODY"
log "adminToken = ${ADMIN_TOKEN[1,16]}..."
log ""

# ============================================================
# [B] create or reuse test agent
# ============================================================
AGENT_NAME='duty-e2e-agent-v1'
log "=== [B] create or reuse $AGENT_NAME ==="
http_request GET "$BASE_URL/api/admin/agents/list?pageSize=50" "" "X-Admin-Token: $ADMIN_TOKEN"
[[ "$HTTP_CODE" == "200" ]] || fail "admin agents list failed: $HTTP_BODY"
AGENT_ID="$(print -r -- "$HTTP_BODY" | jq -r --arg n "$AGENT_NAME" '.data.list[]? | select(.name == $n) | .id' | head -1)"
AGENT_API_KEY="$(print -r -- "$HTTP_BODY" | jq -r --arg n "$AGENT_NAME" '.data.list[]? | select(.name == $n) | .apiKey' | head -1)"
if [[ -z "$AGENT_ID" ]]; then
  create_body="$(jq -cn --arg n "$AGENT_NAME" '{name:$n,role:"EXECUTOR",remark:"AgentHub V1 duty e2e auto-created"}')"
  http_request POST "$BASE_URL/api/admin/agents" "$create_body" "X-Admin-Token: $ADMIN_TOKEN"
  assert_eq "200" "$HTTP_CODE" "admin create agent failed"
  AGENT_ID="$(print -r -- "$HTTP_BODY" | jq -r '.data.id // empty')"
  AGENT_API_KEY="$(print -r -- "$HTTP_BODY" | jq -r '.data.apiKey // empty')"
  [[ -n "$AGENT_ID" ]] || fail "agent create returned no id: $HTTP_BODY"
  log "created agentId=$AGENT_ID"
else
  log "reuse existing agentId=$AGENT_ID"
fi
[[ -n "$AGENT_API_KEY" ]] || fail "agent apiKey empty (check AgentRegistrationResponse mapping)"
log "agentApiKey = ${AGENT_API_KEY[1,16]}..."

# 确保 V21 seed 已生效；若手工建的 test agent 早于 V21 也补一次（幂等）
seed_sql="INSERT INTO agent_mcp_server (agent_id, tool_name, is_enabled, rate_limit, create_by, update_by)
SELECT $AGENT_ID, tool.name, 1, 0, 'e2e', 'e2e'
FROM (VALUES ('checkIn'), ('checkOut')) AS tool(name)
ON CONFLICT (agent_id, tool_name) WHERE deleted = 0 DO NOTHING;"
print -r -- "$seed_sql" | docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -X -t -A -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1 || true

# ============================================================
# STEP C/D: start SSE + initialize
# ============================================================
log "=== [C] start MCP SSE long connection ==="
start_mcp_sse
log "sessionId = $SID"

log "=== [D] MCP initialize ==="
send_mcp '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"duty-e2e","version":"1.0"}}}' "initialize" "X-Admin-Token: $ADMIN_TOKEN"
send_mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}' "notifications/initialized" "X-Admin-Token: $ADMIN_TOKEN"

# ============================================================
# STEP S1: checkIn -> lease ACTIVE
# ============================================================
log "=== [S1] tools/call checkIn (workMode=AUTO, maxConcurrent=3, ttlMinutes=5) ==="
s1_body='{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":'"$AGENT_ID"',"workMode":"AUTO","maxConcurrent":3,"ttlMinutes":5,"sessionId":"'"$SID"'"}}}'
send_mcp "$s1_body" "S1 checkIn" "Authorization: Bearer $AGENT_API_KEY"

s1_row=""
run_psql_one_row "SELECT status, work_mode, max_concurrent, (expire_time > now()) AS not_yet_expired, COALESCE(close_reason, '') FROM agent_duty_lease WHERE agent_id = $AGENT_ID AND status = 'ACTIVE' AND deleted = 0 ORDER BY id DESC LIMIT 1;" "s1_row"
s1_fields=()
while IFS= read -r line; do
  [[ -n "$line" ]] && s1_fields+=("$line")
done <<<"$s1_row"
log "S1 fields: $s1_fields"
[[ "${s1_fields[1]}" == "ACTIVE" ]] || fail "S1 FAIL: status != ACTIVE"
[[ "${s1_fields[2]}" == "AUTO" ]] || fail "S1 FAIL: work_mode != AUTO"
[[ "${s1_fields[3]}" == "3" ]] || fail "S1 FAIL: max_concurrent != 3"
[[ "${s1_fields[4]}" == "t" ]] || fail "S1 FAIL: lease already expired"
log "S1 OK: checkIn -> lease ACTIVE, work_mode=AUTO, max_concurrent=3, not-yet-expired"
log ""

# ============================================================
# STEP S2: checkOut -> lease CLOSED
# ============================================================
log "=== [S2] tools/call checkOut (reason=e2e_test_close) ==="
s2_body='{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":'"$AGENT_ID"',"closeReason":"e2e_test_close","sessionId":"'"$SID"'"}}}'
send_mcp "$s2_body" "S2 checkOut" "Authorization: Bearer $AGENT_API_KEY"

s2_row=""
run_psql_one_row "SELECT status, COALESCE(close_reason, '') FROM agent_duty_lease WHERE agent_id = $AGENT_ID AND deleted = 0 ORDER BY id DESC LIMIT 1;" "s2_row"
s2_fields=()
while IFS= read -r line; do
  [[ -n "$line" ]] && s2_fields+=("$line")
done <<<"$s2_row"
log "S2 fields: $s2_fields"
[[ "${s2_fields[1]}" == "CLOSED" ]] || fail "S2 FAIL: status != CLOSED"
[[ "${s2_fields[2]}" == "e2e_test_close" ]] || fail "S2 FAIL: close_reason mismatch (${s2_fields[2]})"
log "S2 OK: checkOut -> lease CLOSED, close_reason=e2e_test_close"
log ""

# ============================================================
# STEP S3: Lease Expiration
#   - 直接 INSERT 一条 expire_time 已过期的 ACTIVE 租约
#   - 等 35s，让 DutyLeaseExpirationTask @Scheduled(fixedRate=30s) 至少跑 1 次
#   - 校验 status='EXPIRED', close_reason='lease_expired'
# ============================================================
log "=== [S3] simulate expired ACTIVE lease and wait for DutyLeaseExpirationTask ==="
lease_id="$(( $(date +%s) * 1000 + RANDOM + 1 ))"
s3_insert_sql="INSERT INTO agent_duty_lease
  (id, agent_id, session_id, work_mode, max_concurrent, status,
   start_time, last_renew_time, expire_time,
   create_by, update_by, create_time, update_time, deleted, remark)
VALUES
  ($lease_id, $AGENT_ID, 'e2e-expired-lease', 'AUTO', 3, 'ACTIVE',
   now() - interval '3 minutes',
   now() - interval '3 minutes',
   now() - interval '1 minute',
   'e2e', 'e2e', now(), now(), 0, 'DutyLeaseExpirationTask e2e input');"
print -r -- "$s3_insert_sql" | docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -X -t -A -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1 || fail "S3 insert lease failed"
log "inserted expired ACTIVE lease id=$lease_id (expire_time=now-1min)"

log "waiting 35s for DutyLeaseExpirationTask fixedRate=30s (may hit 1-2 ticks)..."
sleep 35

s3_row=""
run_psql_one_row "SELECT status, COALESCE(close_reason, '') FROM agent_duty_lease WHERE id = $lease_id AND deleted = 0;" "s3_row"
s3_fields=()
while IFS= read -r line; do
  [[ -n "$line" ]] && s3_fields+=("$line")
done <<<"$s3_row"
log "S3 fields: $s3_fields"
[[ "${s3_fields[1]}" == "EXPIRED" ]] || fail "S3 FAIL: status != EXPIRED (${s3_fields[1]})"
[[ "${s3_fields[2]}" == "lease_expired" ]] || fail "S3 FAIL: close_reason != lease_expired (${s3_fields[2]})"
log "S3 OK: DutyLeaseExpirationTask flipped lease ACTIVE->EXPIRED with reason=lease_expired"
log ""

# ============================================================
# STEP S6: N12 P1 STRICT 独占报锁（AgentSelector 退出替补池）
#   - 创建独立 test agent（不复用主 agent，主 agent 已被 S2 签退）
#   - S6.1 workMode=STRICT checkIn -> DB 断言 work_mode=STRICT
#   - S6.2 workMode=strict（小写）checkIn -> DB 断言 work_mode=STRICT（大小写不敏感）
#   - S6.3 workMode=BOGUS_VALUE checkIn -> 断言 BizException 拒绝（不默默降级 AUTO）
#   - S6.4 cleanup checkOut
# ============================================================
log "=== [S6] N12 P1 STRICT 独占报锁 (AgentSelector pickAlternative 过滤验证) ==="

STRICT_AGENT_NAME='duty-e2e-strict-agent-v1'
http_request GET "$BASE_URL/api/admin/agents/list?page=1&pageSize=200" "" "X-Admin-Token: $ADMIN_TOKEN"
STRICT_AGENT_ID="$(print -r -- "$HTTP_BODY" | jq -r --arg n "$STRICT_AGENT_NAME" '.data.list[]? | select(.name == $n) | .id' | head -1)"
STRICT_AGENT_API_KEY="$(print -r -- "$HTTP_BODY" | jq -r --arg n "$STRICT_AGENT_NAME" '.data.list[]? | select(.name == $n) | .apiKey' | head -1)"
if [[ -z "$STRICT_AGENT_ID" ]]; then
  strict_create_body="$(jq -cn --arg n "$STRICT_AGENT_NAME" '{name:$n,role:"EXECUTOR",remark:"AgentHub N12 P1 STRICT e2e auto-created"}')"
  http_request POST "$BASE_URL/api/admin/agents" "$strict_create_body" "X-Admin-Token: $ADMIN_TOKEN"
  assert_eq "200" "$HTTP_CODE" "S6 create agent failed"
  STRICT_AGENT_ID="$(print -r -- "$HTTP_BODY" | jq -r '.data.id // empty')"
  STRICT_AGENT_API_KEY="$(print -r -- "$HTTP_BODY" | jq -r '.data.apiKey // empty')"
  log "S6 created strict agentId=$STRICT_AGENT_ID"
else
  log "S6 reuse existing strict agentId=$STRICT_AGENT_ID"
fi

# seed checkIn / checkOut tool（幂等）
strict_seed_sql="INSERT INTO agent_mcp_server (agent_id, tool_name, is_enabled, rate_limit, create_by, update_by)
SELECT $STRICT_AGENT_ID, tool.name, 1, 0, 'e2e', 'e2e'
FROM (VALUES ('checkIn'), ('checkOut')) AS tool(name)
ON CONFLICT (agent_id, tool_name) WHERE deleted = 0 DO NOTHING;"
print -r -- "$strict_seed_sql" | docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -X -t -A -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1 || true

# 清理旧 lease（避免 uk_duty_lease_agent_active 冲突）
print -r -- "DELETE FROM agent_duty_lease WHERE agent_id = $STRICT_AGENT_ID;" | docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -X -t -A -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1 || true

# ---------- S6.1 workMode=STRICT checkIn ----------
s61_body='{"jsonrpc":"2.0","id":61,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":'"$STRICT_AGENT_ID"',"workMode":"STRICT","maxConcurrent":1,"ttlMinutes":5,"sessionId":"'"$SID"'"}}}'
send_mcp "$s61_body" "S6.1 checkIn STRICT" "Authorization: Bearer $STRICT_AGENT_API_KEY"
[[ "$HTTP_CODE" == "200" ]] || fail "S6.1 FAIL: HTTP=$HTTP_CODE body=$HTTP_BODY"

s61_row=""
run_psql_one_row "SELECT status, work_mode FROM agent_duty_lease WHERE agent_id = $STRICT_AGENT_ID AND deleted = 0 ORDER BY id DESC LIMIT 1;" "s61_row"
s61_fields=()
while IFS= read -r line; do
  [[ -n "$line" ]] && s61_fields+=("$line")
done <<<"$s61_row"
[[ "${s61_fields[1]}" == "ACTIVE" ]] || fail "S6.1 FAIL: status != ACTIVE (${s61_fields[1]})"
[[ "${s61_fields[2]}" == "STRICT" ]] || fail "S6.1 FAIL: work_mode != STRICT (${s61_fields[2]})"
log "S6.1 OK: checkIn(workMode=STRICT) -> DB status=ACTIVE, work_mode=STRICT"

# 签退为 S6.2 清理 ACTIVE（uk_duty_lease_agent_active 同一 agent 只 1 条）
s61_out_body='{"jsonrpc":"2.0","id":62,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":'"$STRICT_AGENT_ID"',"closeReason":"s6_1_cleanup","sessionId":"'"$SID"'"}}}'
send_mcp "$s61_out_body" "S6.1 checkOut cleanup" "Authorization: Bearer $STRICT_AGENT_API_KEY"

# ---------- S6.2 workMode=strict (lower-case) checkIn ----------
s62_body='{"jsonrpc":"2.0","id":63,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":'"$STRICT_AGENT_ID"',"workMode":"strict","maxConcurrent":1,"ttlMinutes":5,"sessionId":"'"$SID"'"}}}'
send_mcp "$s62_body" "S6.2 checkIn strict lower" "Authorization: Bearer $STRICT_AGENT_API_KEY"
[[ "$HTTP_CODE" == "200" ]] || fail "S6.2 FAIL: HTTP=$HTTP_CODE body=$HTTP_BODY"

s62_mode="$(psql_field 1 "SELECT work_mode FROM agent_duty_lease WHERE agent_id = $STRICT_AGENT_ID AND deleted = 0 ORDER BY id DESC LIMIT 1;")"
[[ "$s62_mode" == "STRICT" ]] || fail "S6.2 FAIL: work_mode != STRICT (got $s62_mode), case-insensitive check failed"
log "S6.2 OK: checkIn(workMode=strict lower) -> DB work_mode=STRICT (case-insensitive)"

s62_out_body='{"jsonrpc":"2.0","id":64,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":'"$STRICT_AGENT_ID"',"closeReason":"s6_2_cleanup","sessionId":"'"$SID"'"}}}'
send_mcp "$s62_out_body" "S6.2 checkOut cleanup" "Authorization: Bearer $STRICT_AGENT_API_KEY"

# ---------- S6.3 workMode=BOGUS_VALUE checkIn -> 拒绝 ----------
# 验证 strictParse 拒绝非法值：HTTP 仍 200（MCP tools/call 不会传 HTTP 非 200），
# 但 DB 中 lease 不增加（被 BizException 拒绝后不落库）
s63_before="$(psql_field 1 "SELECT COUNT(*) FROM agent_duty_lease WHERE agent_id = $STRICT_AGENT_ID AND deleted = 0;")"
s63_body='{"jsonrpc":"2.0","id":65,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":'"$STRICT_AGENT_ID"',"workMode":"BOGUS_VALUE","maxConcurrent":1,"ttlMinutes":5,"sessionId":"'"$SID"'"}}}'
send_mcp "$s63_body" "S6.3 checkIn BOGUS_VALUE" "Authorization: Bearer $STRICT_AGENT_API_KEY"
[[ "$HTTP_CODE" == "200" ]] || fail "S6.3 FAIL: HTTP=$HTTP_CODE body=$HTTP_BODY"
s63_after="$(psql_field 1 "SELECT COUNT(*) FROM agent_duty_lease WHERE agent_id = $STRICT_AGENT_ID AND deleted = 0;")"
[[ "$s63_after" == "$s63_before" ]] || fail "S6.3 FAIL: BOGUS_VALUE was accepted and persisted (lease count from $s63_before to $s63_after)"
log "S6.3 OK: workMode=BOGUS_VALUE was rejected by BizException, lease count unchanged (still $s63_after)"

# ---------- S6.4 清理（如果 S6.3 之前还有 ACTIVE 也兜底清一次） ----------
s64_clean_body='{"jsonrpc":"2.0","id":66,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":'"$STRICT_AGENT_ID"',"closeReason":"s6_final_cleanup","sessionId":"'"$SID"'"}}}'
send_mcp "$s64_clean_body" "S6.4 final checkOut" "Authorization: Bearer $STRICT_AGENT_API_KEY"
log "S6 OK: STRICT persisted / case-insensitive / invalid-rejected three cases all green"
log ""

# ============================================================
# STEP S7: E1 动态 TTL 自适应（N12 A2 第 2 段）
#   - S7.0 score 复位 0（幂等起点）
#   - S7.1 score=0（低表现）checkIn 不带 ttlMinutes -> 约 min(5min) 短窗口
#   - S7.2 score=100（高表现）checkIn 不带 ttlMinutes -> 约 max(240min) 长窗口
#   - S7.3 cleanup: checkOut + score 复位 0
# 复用主 test agent（$AGENT_ID），与 S1-S3/S6 互不干扰（每步前清理 ACTIVE）
# ============================================================
log "=== [S7] E1 dynamic TTL (adaptive window by agent score) ==="

# ---------- S7.0 复位 score=0（幂等起点） ----------
print -r -- "UPDATE agent SET score = 0 WHERE id = $AGENT_ID AND deleted = 0;" | docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -X -t -A -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1 || true

# ---------- S7.1 低分 Agent：checkIn 不带 ttl -> 短窗口 ----------
s71_body='{"jsonrpc":"2.0","id":71,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":'"$AGENT_ID"',"workMode":"AUTO","maxConcurrent":1,"sessionId":"'"$SID"'"}}}'
send_mcp "$s71_body" "S7.1 checkIn no-ttl low-score" "Authorization: Bearer $AGENT_API_KEY"
[[ "$HTTP_CODE" == "200" ]] || fail "S7.1 FAIL: HTTP=$HTTP_CODE body=$HTTP_BODY"
s71_ttl="$(psql_field 1 "SELECT ROUND(EXTRACT(EPOCH FROM (expire_time - now())) / 60)::int FROM agent_duty_lease WHERE agent_id = $AGENT_ID AND status = 'ACTIVE' AND deleted = 0 ORDER BY id DESC LIMIT 1;")"
log "S7.1 ttl-minutes=$s71_ttl"
if [[ "$s71_ttl" -lt 3 || "$s71_ttl" -gt 8 ]]; then
  fail "S7.1 FAIL: expected ~5min window, got $s71_ttl"
fi
log "S7.1 OK: score=0 checkIn -> short window (~5min)"

# ---------- S7.2 高分 Agent：score=100 -> checkIn 不带 ttl -> 长窗口 ----------
print -r -- "UPDATE agent SET score = 100 WHERE id = $AGENT_ID AND deleted = 0;" | docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -X -t -A -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1 || true
s72_out_body='{"jsonrpc":"2.0","id":72,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":'"$AGENT_ID"',"closeReason":"s7_before_recheckin","sessionId":"'"$SID"'"}}}'
send_mcp "$s72_out_body" "S7.2 pre checkOut" "Authorization: Bearer $AGENT_API_KEY"
s72_body='{"jsonrpc":"2.0","id":73,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":'"$AGENT_ID"',"workMode":"AUTO","maxConcurrent":1,"sessionId":"'"$SID"'"}}}'
send_mcp "$s72_body" "S7.2 checkIn no-ttl high-score" "Authorization: Bearer $AGENT_API_KEY"
[[ "$HTTP_CODE" == "200" ]] || fail "S7.2 FAIL: HTTP=$HTTP_CODE body=$HTTP_BODY"
s72_ttl="$(psql_field 1 "SELECT ROUND(EXTRACT(EPOCH FROM (expire_time - now())) / 60)::int FROM agent_duty_lease WHERE agent_id = $AGENT_ID AND status = 'ACTIVE' AND deleted = 0 ORDER BY id DESC LIMIT 1;")"
log "S7.2 ttl-minutes=$s72_ttl"
if [[ "$s72_ttl" -lt 236 || "$s72_ttl" -gt 244 ]]; then
  fail "S7.2 FAIL: expected ~240min window, got $s72_ttl"
fi
log "S7.2 OK: score=100 checkIn -> long window (~240min)"

# ---------- S7.3 cleanup: checkOut + score 复位 0 ----------
s73_out_body='{"jsonrpc":"2.0","id":74,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":'"$AGENT_ID"',"closeReason":"s7_final_cleanup","sessionId":"'"$SID"'"}}}'
send_mcp "$s73_out_body" "S7.3 final checkOut" "Authorization: Bearer $AGENT_API_KEY"
print -r -- "UPDATE agent SET score = 0 WHERE id = $AGENT_ID AND deleted = 0;" | docker exec -i "$PG_CONTAINER" psql -v ON_ERROR_STOP=1 -X -t -A -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1 || true
log "S7 OK: dynamic TTL by score (low ~5min / high ~240min) both green"
log ""

# ============================================================
# STEP S8: E2 并发额度预扣（N12 A2 第 3 段，§6.86）
#   - S8.0 残留清理：同名 task（e2e-quota-verify-task）残留先级联删除（幂等起点）
#   - S8.1 checkIn(maxConcurrent=1) -> DB 断言 ACTIVE + max_concurrent=1
#   - S8.2 建 task（agentPolicy.executorAgentIds=[本 agent] 白名单）+ t1
#         -> 自动派发 -> 断言 t1.assigned_agent_id=本 agent（额度内选中）
#   - S8.3 建 t2 -> 自动派发 -> 断言 t2 保持 PENDING（满额被选人链跳过，不超发）
#   - S8.4 submitResult(t1) 释放额度 -> 建 t3 -> 断言 t3.assigned_agent_id=本 agent（释放重派）
#   - S8.5 并发窗口：submitResult(t3) 释放 -> 并发建 t4/t5
#         -> 断言本 agent 在飞数 <= 1（FOR UPDATE 原子防线防并发超发）
#   - S8.6 cleanup: checkOut + DELETE task 级联删除
#
# 前置条件：helloai.dispatch.auto-assign-on-create=true（application.yml dispatch 段；
#           脚本行为自检：t1 创建后 2s 未派发 -> 报错提示改配置）
# 隔离策略：任务级 agentPolicy 白名单（V47）把选人限定在本 agent——环境里其他 ACTIVE
#           Agent 不参与，断言环境无关、可重复回归
# ============================================================
QUOTA_TASK_TITLE='e2e-quota-verify-task'
log "=== [S8] E2 concurrency quota (maxConcurrent=1 dispatch-occupies) ==="

# ---------- S8.0 残留清理（幂等起点） ----------
s80_find="$(psql_field 1 "SELECT COALESCE((SELECT id::text FROM task WHERE title = '$QUOTA_TASK_TITLE' AND deleted = 0 LIMIT 1), '');")"
if [[ -n "$s80_find" ]]; then
  log "S8.0 cleanup residual task id=$s80_find"
  s80_del_body="$(jq -cn --arg t "$QUOTA_TASK_TITLE" '{confirmTitle:$t}')"
  http_request DELETE "$BASE_URL/api/tasks/deleteById/$s80_find" "$s80_del_body" "X-Admin-Token: $ADMIN_TOKEN"
  log "S8.0 delete residual HTTP $HTTP_CODE"
fi

# ---------- S8.1 checkIn(maxConcurrent=1) ----------
s81_body='{"jsonrpc":"2.0","id":81,"method":"tools/call","params":{"name":"checkIn","arguments":{"agentId":'"$AGENT_ID"',"workMode":"AUTO","maxConcurrent":1,"ttlMinutes":10,"sessionId":"'"$SID"'"}}}'
send_mcp "$s81_body" "S8.1 checkIn maxConcurrent=1" "Authorization: Bearer $AGENT_API_KEY"
[[ "$HTTP_CODE" == "200" ]] || fail "S8.1 FAIL: HTTP=$HTTP_CODE body=$HTTP_BODY"

s81_row=""
run_psql_one_row "SELECT status, max_concurrent FROM agent_duty_lease WHERE agent_id = $AGENT_ID AND status = 'ACTIVE' AND deleted = 0 ORDER BY id DESC LIMIT 1;" "s81_row"
s81_fields=()
while IFS= read -r line; do
  [[ -n "$line" ]] && s81_fields+=("$line")
done <<<"$s81_row"
[[ "${s81_fields[1]}" == "ACTIVE" ]] || fail "S8.1 FAIL: status != ACTIVE (${s81_fields[1]})"
[[ "${s81_fields[2]}" == "1" ]] || fail "S8.1 FAIL: max_concurrent != 1 (${s81_fields[2]})"
log "S8.1 OK: checkIn(maxConcurrent=1) -> lease ACTIVE, quota=1"

# ---------- S8.2 建 task（白名单）+ t1 -> 自动派发选中 ----------
s82_task_body="$(jq -cn --arg t "$QUOTA_TASK_TITLE" --argjson aid "$AGENT_ID" '{title:$t,description:"E2 concurrency quota verify task",agentPolicy:{executorAgentIds:[$aid]}}')"
http_request POST "$BASE_URL/api/tasks" "$s82_task_body" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$HTTP_CODE" "S8.2 create task failed"
S82_TASK_ID="$(print -r -- "$HTTP_BODY" | jq -r '.data.id // empty')"
[[ -n "$S82_TASK_ID" ]] || fail "S8.2 task id missing: $HTTP_BODY"
log "S8.2 taskId=$S82_TASK_ID"

s82_st_body='{"taskId":'"$S82_TASK_ID"',"title":"e2e-quota-t1","description":"first task within quota","deliverable":"E2 quota proof"}'
http_request POST "$BASE_URL/api/sub-tasks" "$s82_st_body" "X-Admin-Token: $ADMIN_TOKEN"
log "S8.2 create t1 HTTP $HTTP_CODE body: $HTTP_BODY"
S82_T1_ID="$(print -r -- "$HTTP_BODY" | jq -r '.data.id // empty')"
[[ -n "$S82_T1_ID" ]] || fail "S8.2 t1 id missing from create response: $HTTP_BODY"
sleep 2
s82_assigned="$(psql_field 2 "SELECT status, COALESCE(assigned_agent_id::text, '0') FROM sub_task WHERE id = $S82_T1_ID AND deleted = 0;")"
if [[ "$s82_assigned" != "$AGENT_ID" ]]; then
  if [[ "$s82_assigned" == "0" ]]; then
    fail "S8.2 FAIL: t1 not dispatched (still PENDING). Check application.yml helloai.dispatch.auto-assign-on-create=true"
  fi
  fail "S8.2 FAIL: t1 assigned to agent $s82_assigned (whitelist broken?)"
fi
log "S8.2 OK: t1 auto-dispatched to whitelist agent (within quota)"

# ---------- S8.3 建 t2 -> 满额被选人链跳过（保持 PENDING，不超发） ----------
s83_st_body='{"taskId":'"$S82_TASK_ID"',"title":"e2e-quota-t2","description":"second task over quota","deliverable":"E2 quota skip proof"}'
http_request POST "$BASE_URL/api/sub-tasks" "$s83_st_body" "X-Admin-Token: $ADMIN_TOKEN"
# 满额时 pickPreferred 白名单内无候选 -> BizException（HTTP 500/业务错误）；不依赖响应码，DB 断言为准
log "S8.3 create t2 HTTP $HTTP_CODE body: $HTTP_BODY"
sleep 2
s83_assigned="$(psql_field 2 "SELECT status, COALESCE(assigned_agent_id::text, 'NULL') FROM sub_task WHERE title = 'e2e-quota-t2' AND task_id = $S82_TASK_ID AND deleted = 0 LIMIT 1;")"
[[ "$s83_assigned" == "NULL" ]] || fail "S8.3 FAIL: t2 was assigned to agent $s83_assigned while quota full (selector soft-filter broken)"
log "S8.3 OK: t2 skipped by selector while quota full (kept PENDING, no over-dispatch)"

# ---------- S8.4 submitResult(t1) 释放额度 -> 建 t3 -> 重派成功 ----------
s84_result_id="e2e-quota-submit-$(date +%Y%m%d%H%M%S)"
s84_sr_body="$(jq -cn --argjson st "$S82_T1_ID" --arg rid "$s84_result_id" '{subTaskId:$st,success:true,output:"e2e quota release proof",finishReason:"completed",resultId:$rid}')"
http_request POST "$BASE_URL/api/mcp/tools/submitResult" "$s84_sr_body" "Authorization: Bearer $AGENT_API_KEY"
log "S8.4 submitResult HTTP $HTTP_CODE body: $HTTP_BODY"
assert_eq "200" "$HTTP_CODE" "S8.4 submitResult HTTP != 200"
s84_ok="$(print -r -- "$HTTP_BODY" | jq -r '.data.ok // empty')"
[[ "$s84_ok" == "true" ]] || fail "S8.4 FAIL: submitResult not accepted: $HTTP_BODY"
sleep 1
# t1 已流转 REVIEW（不在占用口径 ASSIGNED/IN_PROGRESS/REWORK -> 额度释放）
s84_t1_status="$(psql_field 1 "SELECT status FROM sub_task WHERE id = $S82_T1_ID AND deleted = 0;")"
log "S8.4 t1 status=$s84_t1_status"
[[ "$s84_t1_status" == "REVIEW" || "$s84_t1_status" == "DONE" ]] || fail "S8.4 FAIL: t1 not released after submit (status=$s84_t1_status)"

s84_st_body='{"taskId":'"$S82_TASK_ID"',"title":"e2e-quota-t3","description":"third task after release","deliverable":"E2 quota release proof"}'
http_request POST "$BASE_URL/api/sub-tasks" "$s84_st_body" "X-Admin-Token: $ADMIN_TOKEN"
log "S8.4 create t3 HTTP $HTTP_CODE"
sleep 2
s84_assigned="$(psql_field 1 "SELECT COALESCE(assigned_agent_id::text, 'NULL') FROM sub_task WHERE title = 'e2e-quota-t3' AND task_id = $S82_TASK_ID AND deleted = 0 LIMIT 1;")"
[[ "$s84_assigned" == "$AGENT_ID" ]] || fail "S8.4 FAIL: t3 not re-dispatched after release (assigned=$s84_assigned)"
log "S8.4 OK: submit released quota -> t3 dispatched to same agent"

# ---------- S8.5 并发窗口：释放后并发建 t4/t5 -> 原子防线防超发 ----------
s85_t3_id="$(psql_field 1 "SELECT id FROM sub_task WHERE title = 'e2e-quota-t3' AND task_id = $S82_TASK_ID AND deleted = 0 LIMIT 1;")"
s85_result_id="e2e-quota-submit-$(date +%H%M%S)"
s85_sr_body="$(jq -cn --argjson st "$s85_t3_id" --arg rid "$s85_result_id" '{subTaskId:$st,success:true,output:"e2e quota concurrency release",finishReason:"completed",resultId:$rid}')"
http_request POST "$BASE_URL/api/mcp/tools/submitResult" "$s85_sr_body" "Authorization: Bearer $AGENT_API_KEY"
sleep 1

s85_body4='{"taskId":'"$S82_TASK_ID"',"title":"e2e-quota-t4","description":"concurrent a","deliverable":"E2 quota atomic proof"}'
s85_body5='{"taskId":'"$S82_TASK_ID"',"title":"e2e-quota-t5","description":"concurrent b","deliverable":"E2 quota atomic proof"}'
# 两个后台 curl 并发 POST：选人通过 vs 落库满额的冲突窗口由 agent 行锁串行化，
# 后到者在锁内 canAccept=false -> AgentUnavailableException -> 白名单内无替代 -> 冒泡 PENDING
s85_tmp="$SCRIPT_DIR/.tmp"
curl -sS -X POST "$BASE_URL/api/sub-tasks" -o "$s85_tmp/s8-5-t4.json" -w '%{http_code}' -H 'Content-Type: application/json' -H "X-Admin-Token: $ADMIN_TOKEN" --data "$s85_body4" >"$s85_tmp/s8-5-t4.code" &
pid4=$!
curl -sS -X POST "$BASE_URL/api/sub-tasks" -o "$s85_tmp/s8-5-t5.json" -w '%{http_code}' -H 'Content-Type: application/json' -H "X-Admin-Token: $ADMIN_TOKEN" --data "$s85_body5" >"$s85_tmp/s8-5-t5.code" &
pid5=$!
wait "$pid4" "$pid5"
log "S8.5 concurrent create t4/t5: $(cat "$s85_tmp/s8-5-t4.code") / $(cat "$s85_tmp/s8-5-t5.code")"
rm -f "$s85_tmp/s8-5-t4.json" "$s85_tmp/s8-5-t5.json" "$s85_tmp/s8-5-t4.code" "$s85_tmp/s8-5-t5.code"
sleep 3
s85_inflight="$(psql_field 1 "SELECT COUNT(*) FROM sub_task WHERE assigned_agent_id = $AGENT_ID AND status IN ('ASSIGNED','IN_PROGRESS','REWORK') AND deleted = 0;")"
if [[ "$s85_inflight" -gt 1 ]]; then
  fail "S8.5 FAIL: in-flight count $s85_inflight > quota 1 (atomic guard broken)"
fi
log "S8.5 OK: concurrent dispatch kept in-flight count at $s85_inflight (<= quota 1)"

# ---------- S8.6 cleanup: checkOut + 任务级联删除 ----------
s86_out_body='{"jsonrpc":"2.0","id":86,"method":"tools/call","params":{"name":"checkOut","arguments":{"agentId":'"$AGENT_ID"',"closeReason":"s8_final_cleanup","sessionId":"'"$SID"'"}}}'
send_mcp "$s86_out_body" "S8.6 final checkOut" "Authorization: Bearer $AGENT_API_KEY"
s86_del_body="$(jq -cn --arg t "$QUOTA_TASK_TITLE" '{confirmTitle:$t}')"
http_request DELETE "$BASE_URL/api/tasks/deleteById/$S82_TASK_ID" "$s86_del_body" "X-Admin-Token: $ADMIN_TOKEN"
log "S8.6 delete task HTTP $HTTP_CODE body: $HTTP_BODY"
s86_left="$(psql_field 1 "SELECT COUNT(*) FROM sub_task WHERE task_id = $S82_TASK_ID AND deleted = 0;")"
[[ "$s86_left" == "0" ]] || fail "S8.6 FAIL: residual sub_tasks $s86_left after cascade delete"
log "S8.6 OK: checkOut + task cascade delete (zero residual sub_tasks)"
log "S8 OK: E2 concurrency quota all green (dispatch-occupies / soft-skip / release / atomic-guard)"
log ""

# ============================================================
# done
# ============================================================
log "ALL PASSED: S1 checkIn / S2 checkOut / S3 DutyLeaseExpirationTask / S6 N12-P1 STRICT / S7 E1 dynamic TTL / S8 E2 concurrency quota"
log "SSE log:    $SSE_FILE"
log "Log file:   $LOG_FILE"
log "如需反复回归：残留 task（e2e-quota-verify-task）脚本会自动清理；agent 保留复用"
