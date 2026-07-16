#!/usr/bin/env zsh
# ============================================================
# helloai AgentHub V1 P1 (Dashboard 值班概览 + 列表页) + R2 + R3 验证脚本 (macOS/Linux)
# Ref:
#   doc/HelloAI_迭代执行记录.md   (AgentHub V1 P1)
#   doc/HelloAI_实现差距表.md     (N12 P1 收尾)
#   .agents/skills/helloai-preflight/SKILL.md   (规则 6: 脚本 UTF-8 编码头)
#
# 覆盖四个真实环境场景：
#   S1  GET /api/admin/duty-leases/overview          -> 200,active/closed/expired/total 字段齐
#   S2  GET /api/admin/duty-leases                   -> 200,PageResult.list/total/pages/current 齐
#   S3  GET /api/admin/duty-leases?status=ACTIVE     -> 过滤生效(返回行 status 均为 ACTIVE)
#   S4  DB 抽查: agent_command_outbox 中 status IN (1,3) 行的 last_sent_at / confirmed_at 不全为 NULL
#        验证 V22 backfill 已生效 (R3 收尾证据)
#
# Pre-conditions:
#   - docker compose up -d (helloai-postgres:15432)
#   - helloai-start via IDEA @ :6565
#   - Flyway 已跑到 V22 (agent_command_outbox_backfill_timestamps)
#   - 至少有一个 Agent 执行过 dispatch-mode ∈ {MQ,BOTH} 的子任务(才能产生 SENT/CONFIRMED 行)
#
# Usage:
#   chmod +x ./scripts/shell/verify-dashboard-duty-leases.sh
#   ./scripts/shell/verify-dashboard-duty-leases.sh
# 或:
#   zsh ./scripts/shell/verify-dashboard-duty-leases.sh
# ============================================================

set -euo pipefail

# ------------------------------------------------------------
# UTF-8 编码强制头 (规则 6) — 避免中文乱码
# ------------------------------------------------------------
export LANG="${LANG:-zh_CN.UTF-8}"
export LC_ALL="${LC_ALL:-zh_CN.UTF-8}"

BASE_URL="${BASE_URL:-http://localhost:6565}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="${LOG_FILE:-$SCRIPT_DIR/.tmp/verify-dashboard-duty-leases.log}"
mkdir -p "$(dirname "$LOG_FILE")"

PG_CONTAINER="${PG_CONTAINER:-helloai-postgres}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-helloai}"

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
  body_file="$(mktemp -t vddl-body.XXXXXX)"

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

# docker exec psql: pipe-delim rows, first non-empty line is the result row
run_psql_one_row() {
  local sql="$1"
  local out_var="$2"

  local sql_file
  sql_file="$(mktemp -t vddl-sql.XXXXXX)"
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

# ============================================================
# pre-flight: docker postgres + server reachability
# ============================================================
need_cmd curl
need_cmd jq
need_cmd docker
need_cmd mktemp

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
# [A] admin login (duty-leases 接口要求 X-Admin-Token)
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
# [S1] GET /api/admin/duty-leases/overview
# ============================================================
log "=== [S1] GET /api/admin/duty-leases/overview ==="
http_request GET "$BASE_URL/api/admin/duty-leases/overview" "" "X-Admin-Token: $ADMIN_TOKEN"
log "HTTP $HTTP_CODE"
log "body: $HTTP_BODY"
assert_eq "200" "$HTTP_CODE" "S1 overview failed"

s1_data="$(print -r -- "$HTTP_BODY" | jq -r '.data // empty')"
[[ -n "$s1_data" ]] || fail "S1 FAIL: no data field"

for field in activeCount closedCount expiredCount totalCount; do
  val="$(print -r -- "$s1_data" | jq -r ".$field // empty")"
  [[ -n "$val" ]] || fail "S1 FAIL: missing field [$field]"
done

active="$(print -r -- "$s1_data" | jq -r '.activeCount')"
closed="$(print -r -- "$s1_data" | jq -r '.closedCount')"
expired="$(print -r -- "$s1_data" | jq -r '.expiredCount')"
total="$(print -r -- "$s1_data" | jq -r '.totalCount')"
log "S1 OK: active=$active closed=$closed expired=$expired total=$total"
log ""

# ============================================================
# [S2] GET /api/admin/duty-leases (page=1, size=20)
# ============================================================
log "=== [S2] GET /api/admin/duty-leases ==="
http_request GET "$BASE_URL/api/admin/duty-leases?page=1&size=20" "" "X-Admin-Token: $ADMIN_TOKEN"
log "HTTP $HTTP_CODE"
log "body length: ${#HTTP_BODY} chars"
assert_eq "200" "$HTTP_CODE" "S2 list failed"

s2_data="$(print -r -- "$HTTP_BODY" | jq -r '.data // empty')"
[[ -n "$s2_data" ]] || fail "S2 FAIL: no data field"

for field in list total pages current; do
  val="$(print -r -- "$s2_data" | jq -r ".$field // empty")"
  [[ -n "$val" || "$field" == "list" ]] || fail "S2 FAIL: missing field [$field]"
done

# list 字段是个数组,用 length > 0 即可,即使空数组也合法(测试环境无 duty)
list_count="$(print -r -- "$s2_data" | jq -r '.list | length')"
s2_total="$(print -r -- "$s2_data" | jq -r '.total')"
s2_pages="$(print -r -- "$s2_data" | jq -r '.pages')"
s2_current="$(print -r -- "$s2_data" | jq -r '.current')"

[[ "$s2_current" == "1" ]] || fail "S2 FAIL: current != 1 (got $s2_current)"
log "S2 OK: total=$s2_total pages=$s2_pages current=$s2_current rows=$list_count"
log ""

# ============================================================
# [S3] GET /api/admin/duty-leases?status=ACTIVE
# ============================================================
log "=== [S3] GET /api/admin/duty-leases?status=ACTIVE ==="
http_request GET "$BASE_URL/api/admin/duty-leases?status=ACTIVE&page=1&size=20" "" "X-Admin-Token: $ADMIN_TOKEN"
assert_eq "200" "$HTTP_CODE" "S3 filter failed"

s3_data="$(print -r -- "$HTTP_BODY" | jq -r '.data // empty')"
[[ -n "$s3_data" ]] || fail "S3 FAIL: no data field"

# 检查返回的每一条 status 都必须是 ACTIVE。空 list (count=0) 也合法。
s3_list="$(print -r -- "$s3_data" | jq -c '.list // []')"
bad_count="$(print -r -- "$s3_list" | jq '[ .[] | select(.status != "ACTIVE") ] | length')"
[[ "$bad_count" == "0" ]] || fail "S3 FAIL: $bad_count rows have status != ACTIVE (filter not effective)"

s3_rows="$(print -r -- "$s3_list" | jq 'length')"
log "S3 OK: $s3_rows rows, all status=ACTIVE"
log ""

# ============================================================
# [S4] V22 backfill audit
#   - agent_command_outbox status=1 (SENT) 行 last_sent_at IS NULL 数量
#   - agent_command_outbox status=3 (CONFIRMED) 行 confirmed_at IS NULL 数量
#   - 两者均应为 0 (V22 已 backfill); total=0 也算通过(空表)
# ============================================================
log "=== [S4] V22 backfill audit on agent_command_outbox ==="

s4_sql="SELECT
  (SELECT COUNT(*) FROM agent_command_outbox WHERE status = 1 AND deleted = 0) AS sent_total,
  (SELECT COUNT(*) FROM agent_command_outbox WHERE status = 1 AND deleted = 0 AND last_sent_at IS NULL) AS sent_null_last_sent,
  (SELECT COUNT(*) FROM agent_command_outbox WHERE status = 3 AND deleted = 0) AS confirmed_total,
  (SELECT COUNT(*) FROM agent_command_outbox WHERE status = 3 AND deleted = 0 AND confirmed_at IS NULL) AS confirmed_null_confirmed;"

s4_csv=""
run_psql_one_row "$s4_sql" "s4_csv"

# 把 s4_csv 按换行拆分成数组 (zsh 安全写法)
s4_lines=()
while IFS= read -r line; do
  trimmed="$(print -r -- "$line" | tr -d '[:space:]')"
  if [[ -n "$trimmed" ]]; then
    s4_lines+=("$trimmed")
  fi
done <<<"$s4_csv"

if [[ "${#s4_lines[@]}" -lt 4 ]]; then
  fail "S4 FAIL: expected at least 4 fields, got ${#s4_lines[@]} (raw=$s4_csv)"
fi

# zsh 数组默认从 1 开始
S4_SENT_TOTAL="${s4_lines[1]}"
S4_SENT_NULL_LAST="${s4_lines[2]}"
S4_CONFIRMED_TOTAL="${s4_lines[3]}"
S4_CONFIRMED_NULL="${s4_lines[4]}"

log "S4 fields: ${S4_SENT_TOTAL} | ${S4_SENT_NULL_LAST} | ${S4_CONFIRMED_TOTAL} | ${S4_CONFIRMED_NULL}"

# 仅当存在该状态行时才要求 backfill 已 100% 完成
if [[ "$S4_SENT_TOTAL" -gt 0 && "$S4_SENT_NULL_LAST" -ne 0 ]]; then
  fail "S4 FAIL: $S4_SENT_NULL_LAST / $S4_SENT_TOTAL SENT rows still have last_sent_at IS NULL (V22 backfill missing)"
fi
if [[ "$S4_CONFIRMED_TOTAL" -gt 0 && "$S4_CONFIRMED_NULL" -ne 0 ]]; then
  fail "S4 FAIL: $S4_CONFIRMED_NULL / $S4_CONFIRMED_TOTAL CONFIRMED rows still have confirmed_at IS NULL (V22 backfill missing)"
fi

log "S4 OK: sent_total=$S4_SENT_TOTAL (null_last_sent=$S4_SENT_NULL_LAST), confirmed_total=$S4_CONFIRMED_TOTAL (null_confirmed=$S4_CONFIRMED_NULL)"
log ""

# ============================================================
# done
# ============================================================
log "ALL PASSED: S1 overview / S2 list / S3 status filter / S4 V22 backfill audit"
log "Log file: $LOG_FILE"
