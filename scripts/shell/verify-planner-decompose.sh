#!/usr/bin/env zsh
# ============================================================
# helloai Planner 平台内自动拆解验证脚本（V26，macOS/Linux）
# 等价迁移自 scripts/powershell/verify-planner-decompose.ps1
# 用途：验证"需求 → 自动拆解 → 用户确认/拒绝 → 进入既有分发链"闭环：
#   POST /api/tasks/{id}/plan          触发 LLM 拆解（Task PENDING → PLANNING，
#                                      草案落库 PENDING_PLAN_REVIEW）
#   GET  /api/tasks/{id}/plan          查看草案列表
#   POST /api/tasks/{id}/plan/confirm  草案转正 PENDING（Task → IN_PROGRESS，
#                                      按 autoAssignOnCreate 触发分发链）
#   POST /api/tasks/{id}/plan/reject   草案翻 CANCELLED（Task 回退 PENDING）
# Ref:  doc/HelloAI_实现差距表.md（V26 Planner 平台内拆解）
# Pre-conditions（fail-fast，本脚本不负责启动服务）：
#   - helloai-start 已在 6565 运行（IDEA 或 mvn spring-boot:run）
#   - helloai.providers 已配置可用 LLM（deepseek）
#   脚本自动注册 role=PLANNER + accessType=API_KEY_LLM 的 Agent 供拆解使用
# Usage:
#   chmod +x ./scripts/shell/verify-planner-decompose.sh
#   ./scripts/shell/verify-planner-decompose.sh
# 或:
#   zsh ./scripts/shell/verify-planner-decompose.sh
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
PLANNER_MODEL_TYPE="${PLANNER_MODEL_TYPE:-deepseek:deepseek-chat}"
PLAN_TIMEOUT_SEC="${PLAN_TIMEOUT_SEC:-180}"
# require-vault=true 时拆解必须有托管凭证；与 helloai-start application.yml
# spring.ai.deepseek.api-key 默认值保持一致（对齐 verify-inner-loop-e2e.ps1 做法）
LLM_API_KEY="${DEEPSEEK_API_KEY:-sk-a36fdda1d4ad4e0386e78fc435be0d16}"
VAULT_PROVIDER="${VAULT_PROVIDER:-deepseek}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="${LOG_FILE:-$SCRIPT_DIR/.tmp/verify-planner-decompose.log}"
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

# create_task <title> <description> — 输出 taskId
create_task() {
  local title="$1" desc="$2" resp st
  resp="$(http_json POST "$BASE_URL/api/tasks" "{\"title\":\"$title\",\"description\":\"$desc\"}")"
  assert_r200 "$resp" "create task"
  st="$(print -r -- "$resp" | jq -r '.data.status')"
  assert_eq "$st" "PENDING" "unexpected task status after create"
  print -r -- "$resp" | jq -r '.data.id'
}

# get_task_status <taskId> — 输出 status
get_task_status() {
  local task_id="$1" resp
  resp="$(http_json GET "$BASE_URL/api/tasks/$task_id" "-")"
  assert_r200 "$resp" "get task"
  print -r -- "$resp" | jq -r '.data.status'
}

# draft_count <taskId> — 输出 GET /plan 草案数
draft_count() {
  local task_id="$1" resp
  resp="$(http_json GET "$BASE_URL/api/tasks/$task_id/plan" "-")"
  assert_r200 "$resp" "list drafts"
  print -r -- "$resp" | jq -r '.data | length'
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

log "STEP2: register platform planner agent (API_KEY_LLM, idempotent fixed name)"
PLANNER_RESP="$(http_json POST "$BASE_URL/api/agents/register" \
  "{\"name\":\"planner-decompose\",\"role\":\"PLANNER\",\"description\":\"verify-planner-decompose\",\"accessType\":\"API_KEY_LLM\",\"modelType\":\"$PLANNER_MODEL_TYPE\",\"idempotent\":true}")"
assert_r200 "$PLANNER_RESP" "register planner"
PLANNER_AGENT_ID="$(print -r -- "$PLANNER_RESP" | jq -r '.data.id')"
log "plannerAgentId=$PLANNER_AGENT_ID"

log "STEP2.1: bind agent api-key credential (provider=$VAULT_PROVIDER)"
BIND_RESP="$(http_json POST "$BASE_URL/api/credentials/agents/$PLANNER_AGENT_ID/api-key" \
  "{\"provider\":\"$VAULT_PROVIDER\",\"apiKey\":\"$LLM_API_KEY\",\"remark\":\"verify-planner-decompose\"}")"
assert_r200 "$BIND_RESP" "bind api-key"

# ------------------------------------------------------------
# 主路径：拆解 → 断言草案 → 确认 → 断言转正 + Task IN_PROGRESS
# ------------------------------------------------------------

log "STEP3: create task for confirm path"
TASK_ID="$(create_task "planner-e2e-confirm-$TS" \
  "Build a daily report module: DB schema, statistics REST API, frontend chart page, unit tests and deployment doc.")"
log "taskId=$TASK_ID"

log "STEP4: trigger decompose (LLM call, timeout=${PLAN_TIMEOUT_SEC}s)"
PLAN_RESP="$(http_json POST "$BASE_URL/api/tasks/$TASK_ID/plan" "{}" "$PLAN_TIMEOUT_SEC")"
assert_r200 "$PLAN_RESP" "plan"
DRAFT_COUNT="$(print -r -- "$PLAN_RESP" | jq -r '.data | length')"
(( DRAFT_COUNT >= 1 )) || fail "expected >=1 drafts, actual=$DRAFT_COUNT"
(( DRAFT_COUNT <= 10 )) || fail "expected <=10 drafts, actual=$DRAFT_COUNT"
BAD_DRAFTS="$(print -r -- "$PLAN_RESP" | jq -r '[.data[] | select(.status != "PENDING_PLAN_REVIEW")] | length')"
assert_eq "$BAD_DRAFTS" "0" "some drafts not in PENDING_PLAN_REVIEW"
log "draftCount=$DRAFT_COUNT all PENDING_PLAN_REVIEW"

log "STEP5: assert task PLANNING + GET drafts consistent"
TASK_STATUS="$(get_task_status "$TASK_ID")"
assert_eq "$TASK_STATUS" "PLANNING" "task status after decompose"
LIST_COUNT="$(draft_count "$TASK_ID")"
assert_eq "$LIST_COUNT" "$DRAFT_COUNT" "draft list mismatch"

log "STEP6: confirm plan"
CONFIRM_RESP="$(http_json POST "$BASE_URL/api/tasks/$TASK_ID/plan/confirm" "{}")"
assert_r200 "$CONFIRM_RESP" "confirm"
CONFIRMED_COUNT="$(print -r -- "$CONFIRM_RESP" | jq -r '.data | length')"
assert_eq "$CONFIRMED_COUNT" "$DRAFT_COUNT" "confirmed count mismatch"
# autoAssignOnCreate 开启时可能已被分发链推进到 ASSIGNED，两者均为合法转正结果
BAD_CONFIRMED="$(print -r -- "$CONFIRM_RESP" | jq -r '[.data[] | select(.status != "PENDING" and .status != "ASSIGNED")] | length')"
assert_eq "$BAD_CONFIRMED" "0" "some subTasks not PENDING/ASSIGNED after confirm"
log "confirmed $CONFIRMED_COUNT subTasks (PENDING/ASSIGNED)"

log "STEP7: assert task IN_PROGRESS + no drafts left"
TASK_STATUS="$(get_task_status "$TASK_ID")"
assert_eq "$TASK_STATUS" "IN_PROGRESS" "task status after confirm"
LEFT_COUNT="$(draft_count "$TASK_ID")"
assert_eq "$LEFT_COUNT" "0" "expected 0 drafts left after confirm"

# ------------------------------------------------------------
# 回归路径：拆解 → 拒绝 → 断言 CANCELLED + Task 回退 PENDING
# ------------------------------------------------------------

log "STEP8: create task for reject path"
TASK_ID2="$(create_task "planner-e2e-reject-$TS" \
  "Prototype an internal FAQ chatbot: knowledge ingestion, retrieval API and a simple web UI.")"
log "taskId2=$TASK_ID2"

log "STEP9: trigger decompose again"
PLAN_RESP2="$(http_json POST "$BASE_URL/api/tasks/$TASK_ID2/plan" "{}" "$PLAN_TIMEOUT_SEC")"
assert_r200 "$PLAN_RESP2" "plan2"
DRAFT_COUNT2="$(print -r -- "$PLAN_RESP2" | jq -r '.data | length')"
(( DRAFT_COUNT2 >= 1 )) || fail "expected >=1 drafts, actual=$DRAFT_COUNT2"

log "STEP10: reject plan"
REJECT_RESP="$(http_json POST "$BASE_URL/api/tasks/$TASK_ID2/plan/reject" "{}")"
assert_r200 "$REJECT_RESP" "reject"
CANCELLED_COUNT="$(print -r -- "$REJECT_RESP" | jq -r '.data.cancelledCount')"
assert_eq "$CANCELLED_COUNT" "$DRAFT_COUNT2" "cancelledCount mismatch"

log "STEP11: assert task back to PENDING + drafts cancelled"
TASK_STATUS2="$(get_task_status "$TASK_ID2")"
assert_eq "$TASK_STATUS2" "PENDING" "task status after reject"
LEFT_COUNT2="$(draft_count "$TASK_ID2")"
assert_eq "$LEFT_COUNT2" "0" "expected 0 drafts left after reject"

log "STEP12: duplicate decompose on IN_PROGRESS task must fail"
# 平台统一 R 响应以 code!=200 表达业务失败（BizException），HTTP 层仍 200
DUP_RESP="$(http_json POST "$BASE_URL/api/tasks/$TASK_ID/plan" "{}" "$PLAN_TIMEOUT_SEC")"
DUP_CODE="$(print -r -- "$DUP_RESP" | jq -r '.code // empty')"
[[ "$DUP_CODE" != "200" ]] || fail "duplicate decompose unexpectedly succeeded"
log "duplicate decompose rejected as expected (code=$DUP_CODE)"

log "OK: planner decompose / confirm / reject e2e passed"
log "plannerAgentId=$PLANNER_AGENT_ID"
log "confirmTaskId=$TASK_ID"
log "rejectTaskId=$TASK_ID2"
exit 0
