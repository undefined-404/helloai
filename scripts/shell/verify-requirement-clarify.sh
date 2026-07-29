#!/usr/bin/env zsh
# ============================================================
# helloai 对话式需求澄清验证脚本（V29，macOS/Linux）
# 用途：验证"模糊需求 → 多轮澄清 → 终稿 → 建任务 → 顺路 AI 拆解"闭环：
#   POST /api/requirement-conversations                创建会话（首条消息即走一轮 LLM）
#   POST /api/requirement-conversations/{id}/messages  追加消息再走一轮 LLM
#   GET  /api/requirement-conversations/{id}           会话详情（含消息）
#   POST /api/requirement-conversations/{id}/finalize  终稿建任务（PENDING）
#   POST /api/tasks/{id}/plan                          顺路断言拆解草案生成
# Ref:  doc/HelloAI_实现差距表.md（V29 对话式需求澄清）
# Pre-conditions（fail-fast，本脚本不负责启动服务）：
#   - helloai-start 已在 6565 运行且包含 V29 迁移与新接口
#   - helloai.providers 已配置可用 LLM（deepseek）
#   脚本自动注册 role=PLANNER + accessType=API_KEY_LLM 的 Agent 并绑定托管凭证
# Usage:
#   chmod +x ./scripts/shell/verify-requirement-clarify.sh
#   ./scripts/shell/verify-requirement-clarify.sh
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
LLM_TIMEOUT_SEC="${LLM_TIMEOUT_SEC:-180}"
# require-vault=true 时 LLM 调用必须有托管凭证；与 helloai-start application.yml
# spring.ai.deepseek.api-key 默认值保持一致（对齐 verify-planner-decompose.sh 做法）
LLM_API_KEY="${DEEPSEEK_API_KEY:-sk-a36fdda1d4ad4e0386e78fc435be0d16}"
VAULT_PROVIDER="${VAULT_PROVIDER:-deepseek}"
# LLM 回 question 时追发"直接生成终稿"的最大轮数
MAX_PUSH_ROUNDS="${MAX_PUSH_ROUNDS:-3}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="${LOG_FILE:-$SCRIPT_DIR/.tmp/verify-requirement-clarify.log}"
mkdir -p "$(dirname "$LOG_FILE")"

# ============================================================
# helpers（照 verify-planner-decompose.sh 模板）
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

# send_clarify_message <convId> <message> — 输出会话响应 body
send_clarify_message() {
  local conv_id="$1" message="$2" resp
  resp="$(http_json POST "$BASE_URL/api/requirement-conversations/$conv_id/messages" \
    "{\"message\":\"$message\"}" "$LLM_TIMEOUT_SEC")"
  assert_r200 "$resp" "send message"
  print -r -- "$resp"
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

log "STEP2: register platform planner agent (API_KEY_LLM, idempotent fixed name)"
PLANNER_RESP="$(http_json POST "$BASE_URL/api/agents/register" \
  "{\"name\":\"planner-decompose\",\"role\":\"PLANNER\",\"description\":\"verify-requirement-clarify\",\"accessType\":\"API_KEY_LLM\",\"modelType\":\"$PLANNER_MODEL_TYPE\",\"idempotent\":true}")"
assert_r200 "$PLANNER_RESP" "register planner"
PLANNER_AGENT_ID="$(print -r -- "$PLANNER_RESP" | jq -r '.data.id')"
log "plannerAgentId=$PLANNER_AGENT_ID"

log "STEP2.1: bind agent api-key credential (provider=$VAULT_PROVIDER)"
BIND_RESP="$(http_json POST "$BASE_URL/api/credentials/agents/$PLANNER_AGENT_ID/api-key" \
  "{\"provider\":\"$VAULT_PROVIDER\",\"apiKey\":\"$LLM_API_KEY\",\"remark\":\"verify-requirement-clarify\"}")"
assert_r200 "$BIND_RESP" "bind api-key"

# ------------------------------------------------------------
# 主路径：创建会话（详尽需求）→ 若追问则推进出终稿 → finalize → plan
# ------------------------------------------------------------

log "STEP3: create clarify conversation (detailed requirement, LLM timeout=${LLM_TIMEOUT_SEC}s)"
FIRST_MSG="搭建一个内部日报统计模块：后端提供每日/每周统计 REST API（按项目与人员两个维度聚合），前端做一个图表页展示趋势，交付物包括数据库表结构、接口文档、单元测试与部署说明；验收标准是接口全部通过单测且图表页能正确渲染近 30 天数据。"
CREATE_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations" \
  "{\"message\":\"$FIRST_MSG\"}" "$LLM_TIMEOUT_SEC")"
assert_r200 "$CREATE_RESP" "create conversation"
CONV_ID="$(print -r -- "$CREATE_RESP" | jq -r '.data.conversation.id')"
[[ -n "$CONV_ID" && "$CONV_ID" != "null" ]] || fail "conversation id is empty"
CONV_STATUS="$(print -r -- "$CREATE_RESP" | jq -r '.data.conversation.status')"
assert_eq "$CONV_STATUS" "ACTIVE" "conversation status after create"
MSG_COUNT="$(print -r -- "$CREATE_RESP" | jq -r '.data.messages | length')"
(( MSG_COUNT >= 2 )) || fail "expected >=2 messages (user+assistant), actual=$MSG_COUNT"
log "conversationId=$CONV_ID messages=$MSG_COUNT"

log "STEP4: push to final draft (max $MAX_PUSH_ROUNDS extra rounds)"
FINAL_TITLE="$(print -r -- "$CREATE_RESP" | jq -r '.data.conversation.finalTitle // empty')"
ROUND=0
while [[ -z "$FINAL_TITLE" ]] && (( ROUND < MAX_PUSH_ROUNDS )); do
  (( ROUND = ROUND + 1 ))
  log "  round $ROUND: LLM asked a question, pushing for final draft"
  PUSH_RESP="$(send_clarify_message "$CONV_ID" "没有其他要求了，请基于以上信息直接生成终稿。")"
  FINAL_TITLE="$(print -r -- "$PUSH_RESP" | jq -r '.data.conversation.finalTitle // empty')"
done
[[ -n "$FINAL_TITLE" ]] || fail "no final draft after $MAX_PUSH_ROUNDS push rounds"
log "finalTitle=$FINAL_TITLE"

log "STEP5: finalize -> task created (PENDING)"
FINALIZE_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations/$CONV_ID/finalize" "{}")"
assert_r200 "$FINALIZE_RESP" "finalize"
TASK_ID="$(print -r -- "$FINALIZE_RESP" | jq -r '.data.id')"
[[ -n "$TASK_ID" && "$TASK_ID" != "null" ]] || fail "task id is empty"
TASK_STATUS="$(print -r -- "$FINALIZE_RESP" | jq -r '.data.status')"
assert_eq "$TASK_STATUS" "PENDING" "task status after finalize"
log "taskId=$TASK_ID"

log "STEP6: assert conversation FINALIZED + taskId backfilled"
DETAIL_RESP="$(http_json GET "$BASE_URL/api/requirement-conversations/$CONV_ID" "-")"
assert_r200 "$DETAIL_RESP" "detail"
CONV_STATUS="$(print -r -- "$DETAIL_RESP" | jq -r '.data.conversation.status')"
assert_eq "$CONV_STATUS" "FINALIZED" "conversation status after finalize"
BACK_TASK_ID="$(print -r -- "$DETAIL_RESP" | jq -r '.data.conversation.taskId')"
assert_eq "$BACK_TASK_ID" "$TASK_ID" "conversation.taskId backfill"

log "STEP7: send to FINALIZED conversation must fail"
# 平台统一 R 响应以 code!=200 表达业务失败（BizException），HTTP 层仍 200
DUP_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations/$CONV_ID/messages" \
  "{\"message\":\"再补充一点\"}")"
DUP_CODE="$(print -r -- "$DUP_RESP" | jq -r '.code // empty')"
[[ "$DUP_CODE" != "200" ]] || fail "send to FINALIZED conversation unexpectedly succeeded"
log "send to FINALIZED rejected as expected (code=$DUP_CODE)"

log "STEP8: trigger decompose on created task (LLM call)"
PLAN_RESP="$(http_json POST "$BASE_URL/api/tasks/$TASK_ID/plan" "{}" "$LLM_TIMEOUT_SEC")"
assert_r200 "$PLAN_RESP" "plan"
DRAFT_COUNT="$(print -r -- "$PLAN_RESP" | jq -r '.data | length')"
(( DRAFT_COUNT >= 1 )) || fail "expected >=1 drafts, actual=$DRAFT_COUNT"
log "draftCount=$DRAFT_COUNT"

# ------------------------------------------------------------
# 回归路径：abandon
# ------------------------------------------------------------

log "STEP9: create second conversation and abandon it"
CREATE_RESP2="$(http_json POST "$BASE_URL/api/requirement-conversations" \
  "{\"message\":\"想做点跟数据可视化有关的东西，还没想清楚。\"}" "$LLM_TIMEOUT_SEC")"
assert_r200 "$CREATE_RESP2" "create conversation 2"
CONV_ID2="$(print -r -- "$CREATE_RESP2" | jq -r '.data.conversation.id')"
ABANDON_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations/$CONV_ID2/abandon" "{}")"
assert_r200 "$ABANDON_RESP" "abandon"
DETAIL_RESP2="$(http_json GET "$BASE_URL/api/requirement-conversations/$CONV_ID2" "-")"
CONV_STATUS2="$(print -r -- "$DETAIL_RESP2" | jq -r '.data.conversation.status')"
assert_eq "$CONV_STATUS2" "ABANDONED" "conversation status after abandon"

log "STEP10: list contains both conversations"
LIST_RESP="$(http_json GET "$BASE_URL/api/requirement-conversations" "-")"
assert_r200 "$LIST_RESP" "list"
FOUND="$(print -r -- "$LIST_RESP" | jq -r --arg a "$CONV_ID" --arg b "$CONV_ID2" \
  '[.data[] | select((.id|tostring) == $a or (.id|tostring) == $b)] | length')"
assert_eq "$FOUND" "2" "list should contain both conversations"

log "OK: requirement clarify e2e passed"
log "conversationId=$CONV_ID"
log "taskId=$TASK_ID"
log "abandonedConversationId=$CONV_ID2"
exit 0
