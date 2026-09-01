#!/usr/bin/env zsh
# ============================================================
# helloai Planner 对话双模式验证脚本（V39+V40，macOS/Linux）
# 用途：验证"CHAT 自由对话 + CLARIFY 方案澄清"双模式闭环：
#   POST /api/requirement-conversations                CHAT 建会（initialMode=CHAT），断言纯文本回复
#   POST /api/requirement-conversations/{id}/messages  再发一轮普通问题，断言仍为 CHAT 纯文本
#   POST /api/requirement-conversations/toClarifyById/{id} 切 CLARIFY（V40.2 带附加文本，断言 +2 消息）
#   POST /api/requirement-conversations/{id}/finalize  终稿建任务（或结构化 payload 合法）
#   POST /api/requirement-conversations/toChatById/{id}   反向切回 CHAT（仅置位，不加消息）
#   CHAT 结构化追问容错双模（V40.2）：发"需要你问我几个问题" → 宽松断言（payload 非空则必须合法 structured）
#   意图词路径（V40）：CHAT 会话发"整理方案"（V40.1 口语化意图词扩展）→ 置待确认 + 固定确认询问（不调 LLM）→
#   回复「确认」→ 转入 CLARIFY 跑澄清轮；回复其他内容则清标记继续自由对话
# Ref:  doc/HelloAI_实现差距表_V1.md（N17 Planner 对话双模式，V39+V40 意图词二次确认 + V40.2 /planner 命令）
# Pre-conditions（fail-fast，本脚本不负责启动服务）：
#   - helloai-start 已在 6565 运行且包含 V39/V40 迁移与新接口
#   - helloai.providers 已配置可用 LLM（deepseek）
#   脚本自动注册 role=PLANNER + accessType=API_KEY_LLM 的 Agent 并绑定托管凭证
# Usage:
#   chmod +x ./scripts/shell/verify-planner-chat-dual-mode.sh
#   ./scripts/shell/verify-planner-chat-dual-mode.sh
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
# spring.ai.deepseek.api-key 默认值保持一致（对齐 verify-requirement-clarify.sh 做法）
LLM_API_KEY="${DEEPSEEK_API_KEY:-sk-a36fdda1d4ad4e0386e78fc435be0d16}"
VAULT_PROVIDER="${VAULT_PROVIDER:-deepseek}"
# to-clarify 后 LLM 仍回追问时，追发"直接生成终稿"的最大轮数
MAX_PUSH_ROUNDS="${MAX_PUSH_ROUNDS:-3}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="${LOG_FILE:-$SCRIPT_DIR/.tmp/verify-planner-chat-dual-mode.log}"
mkdir -p "$(dirname "$LOG_FILE")"

# ============================================================
# helpers（照 verify-requirement-clarify.sh 模板）
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

# last_assistant_payload <resp> — 输出最后一条 assistant 消息的 payload（null 或 JSON 字符串）
last_assistant_payload() {
  local resp="$1"
  print -r -- "$resp" | jq -r '.data.messages | [.[] | select(.role == "assistant")][-1] | .payload'
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
  "{\"name\":\"planner-decompose\",\"role\":\"PLANNER\",\"description\":\"verify-planner-chat-dual-mode\",\"accessType\":\"API_KEY_LLM\",\"modelType\":\"$PLANNER_MODEL_TYPE\",\"idempotent\":true}")"
assert_r200 "$PLANNER_RESP" "register planner"
PLANNER_AGENT_ID="$(print -r -- "$PLANNER_RESP" | jq -r '.data.id')"
log "plannerAgentId=$PLANNER_AGENT_ID"

log "STEP2.1: bind agent api-key credential (provider=$VAULT_PROVIDER)"
BIND_RESP="$(http_json POST "$BASE_URL/api/credentials/agents/$PLANNER_AGENT_ID/api-key" \
  "{\"provider\":\"$VAULT_PROVIDER\",\"apiKey\":\"$LLM_API_KEY\",\"remark\":\"verify-planner-chat-dual-mode\"}")"
assert_r200 "$BIND_RESP" "bind api-key"

# ------------------------------------------------------------
# 主路径 A：CHAT 自由对话（纯文本回复，无 JSON 协议）
# ------------------------------------------------------------

log "STEP3: create conversation with initialMode=CHAT (free chat)"
CHAT_MSG="你好，帮我对比一下微服务与单体架构的优缺点，我做个技术选型。"
CREATE_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations" \
  "{\"message\":\"$CHAT_MSG\",\"initialMode\":\"CHAT\"}" "$LLM_TIMEOUT_SEC")"
assert_r200 "$CREATE_RESP" "create chat conversation"
CONV_ID="$(print -r -- "$CREATE_RESP" | jq -r '.data.conversation.id')"
[[ -n "$CONV_ID" && "$CONV_ID" != "null" ]] || fail "conversation id is empty"
assert_eq "$(print -r -- "$CREATE_RESP" | jq -r '.data.conversation.mode')" "CHAT" "mode after create"
assert_eq "$(print -r -- "$CREATE_RESP" | jq -r '.data.conversation.status')" "ACTIVE" "status after create"
MSG_COUNT="$(print -r -- "$CREATE_RESP" | jq -r '.data.messages | length')"
(( MSG_COUNT >= 2 )) || fail "expected >=2 messages (user+assistant), actual=$MSG_COUNT"
LAST_ROLE="$(print -r -- "$CREATE_RESP" | jq -r '.data.messages[-1].role')"
assert_eq "$LAST_ROLE" "assistant" "last message role after create"
LAST_CONTENT="$(print -r -- "$CREATE_RESP" | jq -r '.data.messages[-1].content // empty')"
[[ -n "$LAST_CONTENT" ]] || fail "chat assistant reply content is empty"
LAST_PAYLOAD="$(last_assistant_payload "$CREATE_RESP")"
assert_eq "$LAST_PAYLOAD" "null" "chat reply must be plain text (payload=NULL)"
log "conversationId=$CONV_ID messages=$MSG_COUNT mode=CHAT plainText=yes"

log "STEP4: send another ordinary question, mode must stay CHAT"
CHAT_MSG2="那在我们这种 10 人小团队场景下，单体是不是更合适？"
SEND_RESP="$(send_clarify_message "$CONV_ID" "$CHAT_MSG2")"
assert_eq "$(print -r -- "$SEND_RESP" | jq -r '.data.conversation.mode')" "CHAT" "mode after second chat round"
MSG_COUNT2="$(print -r -- "$SEND_RESP" | jq -r '.data.messages | length')"
(( MSG_COUNT2 == MSG_COUNT + 2 )) || fail "expected +2 messages after one round, actual $MSG_COUNT -> $MSG_COUNT2"
LAST_CONTENT2="$(print -r -- "$SEND_RESP" | jq -r '.data.messages[-1].content // empty')"
[[ -n "$LAST_CONTENT2" ]] || fail "second chat reply content is empty"
LAST_PAYLOAD2="$(last_assistant_payload "$SEND_RESP")"
assert_eq "$LAST_PAYLOAD2" "null" "second chat reply must stay plain text"
log "second chat round ok: messages=$MSG_COUNT2 mode=CHAT"

# ------------------------------------------------------------
# 主路径 B：to-clarify 切方案澄清 → 追问/终稿 → finalize
# ------------------------------------------------------------

log "STEP4.1: CHAT 轮结构化追问宽松断言（V40.2 容错双模，LLM 引导型不强制出现）"
CHAT_ASK_RESP="$(send_clarify_message "$CONV_ID" "需要你问我几个问题帮我做选型")"
assert_eq "$(print -r -- "$CHAT_ASK_RESP" | jq -r '.data.conversation.mode')" "CHAT" "mode after chat ask round"
MSG_COUNT4="$(print -r -- "$CHAT_ASK_RESP" | jq -r '.data.messages | length')"
(( MSG_COUNT4 == MSG_COUNT2 + 2 )) || fail "chat ask round must add +2 messages, actual $MSG_COUNT2 -> $MSG_COUNT4"
CHAT_ASK_ROLE="$(print -r -- "$CHAT_ASK_RESP" | jq -r '.data.messages[-1].role')"
assert_eq "$CHAT_ASK_ROLE" "assistant" "last role after chat ask round"
CHAT_ASK_PAYLOAD="$(last_assistant_payload "$CHAT_ASK_RESP")"
if [[ -n "$CHAT_ASK_PAYLOAD" && "$CHAT_ASK_PAYLOAD" != "null" ]]; then
  print -r -- "$CHAT_ASK_PAYLOAD" | jq -e 'fromjson | .type == "question" and .mode == "structured" and (.questions | type == "array")' >/dev/null \
    || fail "chat structured payload must be valid structured question JSON"
  log "chat ask round emitted structured question card (payload valid)"
else
  log "chat ask round fell back to plain text (payload NULL, LLM 未出卡片——观察记录，非失败)"
fi
MSG_COUNT2="$MSG_COUNT4"

log "STEP5: switch to clarify mode with extra text (POST /toClarifyById, V40.2 /planner 命令路径)"
EXTRA_TEXT="补充：团队10人，单体优先"
CLARIFY_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations/toClarifyById/$CONV_ID" \
  "{\"message\":\"$EXTRA_TEXT\"}" "$LLM_TIMEOUT_SEC")"
assert_r200 "$CLARIFY_RESP" "to-clarify"
assert_eq "$(print -r -- "$CLARIFY_RESP" | jq -r '.data.conversation.mode')" "CLARIFY" "mode after to-clarify"
MSG_COUNT3="$(print -r -- "$CLARIFY_RESP" | jq -r '.data.messages | length')"
(( MSG_COUNT3 == MSG_COUNT2 + 2 )) || fail "to-clarify with extra text must add user+assistant (+2), actual $MSG_COUNT2 -> $MSG_COUNT3"
LAST_ROLE3="$(print -r -- "$CLARIFY_RESP" | jq -r '.data.messages[-1].role')"
assert_eq "$LAST_ROLE3" "assistant" "last message role after to-clarify"
EXTRA_FOUND="$(print -r -- "$CLARIFY_RESP" | jq -r --arg t "$EXTRA_TEXT" \
  '[.data.messages[] | select(.role == "user" and .content == $t)] | length')"
assert_eq "$EXTRA_FOUND" "1" "extra text must be persisted as a user message"
log "switched to CLARIFY with extra text: messages=$MSG_COUNT3"

log "STEP6: push to final draft or accept structured question payload (max $MAX_PUSH_ROUNDS rounds)"
FINAL_TITLE="$(print -r -- "$CLARIFY_RESP" | jq -r '.data.conversation.finalTitle // empty')"
ROUND=0
while [[ -z "$FINAL_TITLE" ]] && (( ROUND < MAX_PUSH_ROUNDS )); do
  (( ROUND = ROUND + 1 ))
  log "  round $ROUND: LLM asked a question, pushing for final draft"
  PUSH_RESP="$(send_clarify_message "$CONV_ID" "没有其他要求了，请基于以上信息直接生成终稿。")"
  FINAL_TITLE="$(print -r -- "$PUSH_RESP" | jq -r '.data.conversation.finalTitle // empty')"
done
if [[ -n "$FINAL_TITLE" ]]; then
  log "finalTitle=$FINAL_TITLE"
  log "STEP6.1: finalize -> task created (PENDING)"
  FINALIZE_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations/$CONV_ID/finalize" "{}")"
  assert_r200 "$FINALIZE_RESP" "finalize"
  TASK_ID="$(print -r -- "$FINALIZE_RESP" | jq -r '.data.id')"
  [[ -n "$TASK_ID" && "$TASK_ID" != "null" ]] || fail "task id is empty"
  assert_eq "$(print -r -- "$FINALIZE_RESP" | jq -r '.data.status')" "PENDING" "task status after finalize"
  log "taskId=$TASK_ID"
  log "STEP6.2: assert conversation FINALIZED + taskId backfilled"
  DETAIL_RESP="$(http_json GET "$BASE_URL/api/requirement-conversations/$CONV_ID" "-")"
  assert_r200 "$DETAIL_RESP" "detail"
  assert_eq "$(print -r -- "$DETAIL_RESP" | jq -r '.data.conversation.status')" "FINALIZED" "status after finalize"
  BACK_TASK_ID="$(print -r -- "$DETAIL_RESP" | jq -r '.data.conversation.taskId')"
  assert_eq "$BACK_TASK_ID" "$TASK_ID" "conversation.taskId backfill"
else
  # 未产出终稿时，必须给出一轮合法的结构化追问（payload 为可解析 JSON）
  log "no final draft after $MAX_PUSH_ROUNDS push rounds, checking structured payload"
  CLARIFY_DETAIL="$(http_json GET "$BASE_URL/api/requirement-conversations/$CONV_ID" "-")"
  assert_r200 "$CLARIFY_DETAIL" "detail after push"
  LAST_PAYLOAD_FINAL="$(last_assistant_payload "$CLARIFY_DETAIL")"
  [[ -n "$LAST_PAYLOAD_FINAL" && "$LAST_PAYLOAD_FINAL" != "null" ]] \
    || fail "clarify round must carry structured payload when no final draft"
  print -r -- "$LAST_PAYLOAD_FINAL" | jq -e 'fromjson | type == "object"' >/dev/null \
    || fail "clarify payload is not valid JSON object"
  HAS_QUESTIONS="$(print -r -- "$LAST_PAYLOAD_FINAL" | jq -r 'fromjson | has("questions")')"
  assert_eq "$HAS_QUESTIONS" "true" "clarify payload must contain questions"
  log "structured question payload accepted (questions key present)"
fi

# ------------------------------------------------------------
# 意图词路径：CHAT 会话发"整理成方案"，服务端自动切 CLARIFY
# ------------------------------------------------------------

log "STEP7: intent phrase -> pending confirm -> reply confirm -> CLARIFY (V40)"
INTENT_MSG="帮我整理方案：做一个内部周报自动汇总工具，每周五自动把大家提交的周报汇总成 PDF 发到群里。"
INTENT_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations" \
  "{\"message\":\"$INTENT_MSG\"}" "$LLM_TIMEOUT_SEC")"
assert_r200 "$INTENT_RESP" "create intent conversation"
INTENT_CONV_ID="$(print -r -- "$INTENT_RESP" | jq -r '.data.conversation.id')"
[[ -n "$INTENT_CONV_ID" && "$INTENT_CONV_ID" != "null" ]] || fail "intent conversation id is empty"
# 意图词命中且无待确认 → 置 pendingClarifyConfirm + 回复固定确认询问（不调 LLM、不加轮数）
assert_eq "$(print -r -- "$INTENT_RESP" | jq -r '.data.conversation.mode')" "CHAT" "mode after intent phrase (still CHAT)"
assert_eq "$(print -r -- "$INTENT_RESP" | jq -r '.data.conversation.pendingClarifyConfirm')" "true" "pending confirm flag set"
assert_eq "$(print -r -- "$INTENT_RESP" | jq -r '.data.conversation.roundCount')" "0" "confirm ask must not consume rounds"
INTENT_MSG_COUNT="$(print -r -- "$INTENT_RESP" | jq -r '.data.messages | length')"
assert_eq "$INTENT_MSG_COUNT" "2" "intent create must have user + confirm-ask only"
INTENT_CONFIRM_ASK="$(print -r -- "$INTENT_RESP" | jq -r '.data.messages[-1].content // empty')"
[[ "$INTENT_CONFIRM_ASK" == *"回复「确认」"* ]] || fail "last assistant message must be the fixed confirm ask"
log "intentConversationId=$INTENT_CONV_ID pendingClarifyConfirm=true confirmAsk=yes"

log "STEP7.1: reply confirm -> mode flips to CLARIFY, pending flag cleared, one clarify round"
CONFIRM_RESP="$(send_clarify_message "$INTENT_CONV_ID" "确认")"
assert_eq "$(print -r -- "$CONFIRM_RESP" | jq -r '.data.conversation.mode')" "CLARIFY" "mode after confirm reply"
assert_eq "$(print -r -- "$CONFIRM_RESP" | jq -r '.data.conversation.pendingClarifyConfirm')" "false" "pending flag cleared after confirm"
assert_eq "$(print -r -- "$CONFIRM_RESP" | jq -r '.data.conversation.roundCount')" "1" "confirm round counts as clarify round 1"
INTENT_MSG_COUNT_AFTER="$(print -r -- "$CONFIRM_RESP" | jq -r '.data.messages | length')"
(( INTENT_MSG_COUNT_AFTER == INTENT_MSG_COUNT + 2 )) \
  || fail "confirm reply must add user+assistant, actual $INTENT_MSG_COUNT -> $INTENT_MSG_COUNT_AFTER"
INTENT_LAST_ROLE="$(print -r -- "$CONFIRM_RESP" | jq -r '.data.messages[-1].role')"
assert_eq "$INTENT_LAST_ROLE" "assistant" "intent conv last role after confirm"
INTENT_LAST_CONTENT="$(print -r -- "$CONFIRM_RESP" | jq -r '.data.messages[-1].content // empty')"
[[ -n "$INTENT_LAST_CONTENT" ]] || fail "intent conv assistant reply content is empty"
log "confirmed -> CLARIFY round ran: messages=$INTENT_MSG_COUNT_AFTER"

# ------------------------------------------------------------
# 反向切换：CLARIFY → CHAT（仅置位，不加消息）
# ------------------------------------------------------------

log "STEP8: switch back to chat (POST /toChatById), mode flip only"
MSG_COUNT_BEFORE_CHAT="$(print -r -- "$CONFIRM_RESP" | jq -r '.data.messages | length')"
TO_CHAT_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations/toChatById/$INTENT_CONV_ID" "{}")"
assert_r200 "$TO_CHAT_RESP" "to-chat"
assert_eq "$(print -r -- "$TO_CHAT_RESP" | jq -r '.data.conversation.mode')" "CHAT" "mode after to-chat"
MSG_COUNT_AFTER_CHAT="$(print -r -- "$TO_CHAT_RESP" | jq -r '.data.messages | length')"
assert_eq "$MSG_COUNT_AFTER_CHAT" "$MSG_COUNT_BEFORE_CHAT" "to-chat must not add messages"
log "switched back to CHAT: messages unchanged=$MSG_COUNT_AFTER_CHAT"

log "OK: planner chat dual-mode (CHAT + CLARIFY) e2e passed"
log "chatConversationId=$CONV_ID"
log "intentConversationId=$INTENT_CONV_ID"
[[ -n "${TASK_ID:-}" ]] && log "taskId=$TASK_ID"
exit 0
