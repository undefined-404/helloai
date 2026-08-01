#!/usr/bin/env zsh
# ============================================================
# helloai 对话式需求澄清 — 联网搜索开关 V34 验证脚本（macOS/Linux）
# 用途：覆盖 REQUIREMENT_CLARIFY 的「会话级联网搜索开关」关键路径：
#   - 关闭路径（webSearchEnabled=false）：会话落库 web_search_enabled=false；首轮 LLM 正常返回
#   - 开启路径（webSearchEnabled=true）：会话落库 web_search_enabled=true；首轮 LLM 正常返回
#   - 不传字段（NULL）：落库为 NULL，读取侧按默认开启语义处理（兼容老数据）
#   - 关闭路径里的后续 sendMessage 不受影响（开关仅建会话生效）
# Ref:  doc/HelloAI_实现差距表.md（V34 对话式需求澄清联网搜索）
# Pre-conditions（fail-fast，本脚本不负责启动服务）：
#   - helloai-start 已在 6565 运行，且已包含 V34 迁移 + WebSearch 配置
#   - 默认 provider=bocha；服务器能联通 api.bochaai.com 才有"联网可用"的真实检索
#   - 任何网络/凭证问题不会让脚本失败：搜索服务失败一律降级为空，
#     下面以"功能层断言"为主（落库字段、首轮 LLM 返回），不强行断言联网文本
# Usage:
#   chmod +x ./scripts/shell/verify-websearch-e2e.sh
#   ./scripts/shell/verify-websearch-e2e.sh
# ============================================================

set -euo pipefail

# ------------------------------------------------------------
# UTF-8 编码强制头（规则 6）— 避免中文乱码
# ------------------------------------------------------------
export LANG="${LANG:-zh_CN.UTF-8}"
export LC_ALL="${LC_ALL:-zh_CN.UTF-8}"

BASE_URL="${BASE_URL:-http://localhost:6565}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"
PLANNER_MODEL_TYPE="${PLANNER_MODEL_TYPE:-deepseek:deepseek-chat}"
LLM_TIMEOUT_SEC="${LLM_TIMEOUT_SEC:-180}"
LLM_API_KEY="${DEEPSEEK_API_KEY:-sk-a36fdda1d4ad4e0386e78fc435be0d16}"
VAULT_PROVIDER="${VAULT_PROVIDER:-deepseek}"
# 验证用例措辞：留出"行业资料"关键词空间（同句含足够多可检索实体，便于人工质化对比）
PROBE_PROMPT="${PROBE_PROMPT:-我想做一个在线协作文档平台，类似 Notion/飞书文档那种风格，能写富文本、多人实时协作、有版本历史，给企业研发团队用。}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="${LOG_FILE:-$SCRIPT_DIR/.tmp/verify-websearch-e2e.log}"
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

# jq_value <resp> <jq-filter> — 安全取出标量字段（找不到时返回 null）
jq_value() {
  local resp="$1" filter="$2"
  print -r -- "$resp" | jq -r "$filter // \"null\""
}

need_cmd curl
need_cmd jq

: > "$LOG_FILE"

# ============================================================
# 准备工作：登录 + 注册 PLANNER + 绑凭证（与 verify-requirement-clarify.sh 一致）
# ============================================================

log "STEP1: admin login"
LOGIN_RESP="$(http_json POST "$BASE_URL/api/auth/login" \
  "{\"type\":\"admin\",\"username\":\"$ADMIN_USERNAME\",\"credential\":\"$ADMIN_PASSWORD\"}")"
assert_r200 "$LOGIN_RESP" "login"
ADMIN_TOKEN="$(jq_value "$LOGIN_RESP" '.data.token')"
[[ -n "$ADMIN_TOKEN" && "$ADMIN_TOKEN" != "null" ]] || fail "admin token is empty"

log "STEP2: register platform planner agent (idempotent)"
PLANNER_RESP="$(http_json POST "$BASE_URL/api/agents/register" \
  "{\"name\":\"planner-decompose\",\"role\":\"PLANNER\",\"description\":\"verify-websearch-e2e\",\"accessType\":\"API_KEY_LLM\",\"modelType\":\"$PLANNER_MODEL_TYPE\",\"idempotent\":true}")"
assert_r200 "$PLANNER_RESP" "register planner"
PLANNER_AGENT_ID="$(jq_value "$PLANNER_RESP" '.data.id')"
log "plannerAgentId=$PLANNER_AGENT_ID"

log "STEP2.1: bind agent api-key credential"
BIND_RESP="$(http_json POST "$BASE_URL/api/credentials/agents/$PLANNER_AGENT_ID/api-key" \
  "{\"provider\":\"$VAULT_PROVIDER\",\"apiKey\":\"$LLM_API_KEY\",\"remark\":\"verify-websearch-e2e\"}")"
assert_r200 "$BIND_RESP" "bind api-key"

# ============================================================
# 路径 A：webSearchEnabled=false（关闭）
# 期望：detail.conversation.webSearchEnabled == false；
#       首轮 LLM 仍正常返回（>=2 条消息）；后续 sendMessage 不受影响
# ============================================================

log "STEP3: create conversation with webSearchEnabled=false"
CREATE_OFF_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations" \
  "{\"message\":\"$PROBE_PROMPT\",\"plannerAgentId\":null,\"webSearchEnabled\":false}" \
  "$LLM_TIMEOUT_SEC")"
assert_r200 "$CREATE_OFF_RESP" "create (off)"
OFF_CONV_ID="$(jq_value "$CREATE_OFF_RESP" '.data.conversation.id')"
[[ -n "$OFF_CONV_ID" && "$OFF_CONV_ID" != "null" ]] || fail "off-path conv id is empty"

DETAIL_OFF_RESP="$(http_json GET "$BASE_URL/api/requirement-conversations/$OFF_CONV_ID" "-")"
assert_r200 "$DETAIL_OFF_RESP" "detail (off)"
OFF_FLAG="$(jq_value "$DETAIL_OFF_RESP" '.data.conversation.webSearchEnabled')"
assert_eq "$OFF_FLAG" "false" "webSearchEnabled落库为 false（关闭路径）"
OFF_ROUND="$(jq_value "$DETAIL_OFF_RESP" '.data.conversation.roundCount')"
assert_eq "$OFF_ROUND" "1" "off-path roundCount=1（首轮 LLM 已跑）"
OFF_MSG_COUNT="$(jq_value "$DETAIL_OFF_RESP" '.data.messages | length')"
(( OFF_MSG_COUNT >= 2 )) || fail "off-path 期望 >=2 条消息, actual=$OFF_MSG_COUNT"
log "off-path conversationId=$OFF_CONV_ID roundCount=$OFF_ROUND messages=$OFF_MSG_COUNT"

log "STEP3.1: off-path follow-up sendMessage 不受影响（开关仅建会话生效）"
SEND_OFF_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations/$OFF_CONV_ID/messages" \
  "{\"message\":\"补充：不需要评论和点赞功能，只要协作编辑和版本历史。\"}" "$LLM_TIMEOUT_SEC")"
assert_r200 "$SEND_OFF_RESP" "sendMessage (off-path)"
OFF_FLAG_2="$(jq_value "$SEND_OFF_RESP" '.data.conversation.webSearchEnabled')"
assert_eq "$OFF_FLAG_2" "false" "追加消息后 webSearchEnabled 仍为 false"

# ============================================================
# 路径 B：webSearchEnabled=true（开启）
# 期望：detail.conversation.webSearchEnabled == true；
#       首轮 LLM 仍正常返回；
#       服务端日志会输出"澄清联网搜索结束: provider=..., query=..., results=N"
#         （此行只能人工进日志查看；脚本受限于无日志访问，断言仅覆盖落库字段）
# ============================================================

log "STEP4: create conversation with webSearchEnabled=true"
CREATE_ON_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations" \
  "{\"message\":\"$PROBE_PROMPT\",\"plannerAgentId\":null,\"webSearchEnabled\":true}" \
  "$LLM_TIMEOUT_SEC")"
assert_r200 "$CREATE_ON_RESP" "create (on)"
ON_CONV_ID="$(jq_value "$CREATE_ON_RESP" '.data.conversation.id')"
[[ -n "$ON_CONV_ID" && "$ON_CONV_ID" != "null" ]] || fail "on-path conv id is empty"

DETAIL_ON_RESP="$(http_json GET "$BASE_URL/api/requirement-conversations/$ON_CONV_ID" "-")"
assert_r200 "$DETAIL_ON_RESP" "detail (on)"
ON_FLAG="$(jq_value "$DETAIL_ON_RESP" '.data.conversation.webSearchEnabled')"
assert_eq "$ON_FLAG" "true" "webSearchEnabled落库为 true（开启路径）"
ON_ROUND="$(jq_value "$DETAIL_ON_RESP" '.data.conversation.roundCount')"
assert_eq "$ON_ROUND" "1" "on-path roundCount=1（首轮 LLM 已跑）"
ON_MSG_COUNT="$(jq_value "$DETAIL_ON_RESP" '.data.messages | length')"
(( ON_MSG_COUNT >= 2 )) || fail "on-path 期望 >=2 条消息, actual=$ON_MSG_COUNT"
log "on-path conversationId=$ON_CONV_ID roundCount=$ON_ROUND messages=$ON_MSG_COUNT"
log "提示：在服务端日志中应能看到 \"澄清联网搜索结束: provider=<...>, query=<...>, results=N, costMs=...\""

# ============================================================
# 路径 C：不传 webSearchEnabled（NULL）
# 期望：落库为 NULL；读取侧按默认开启语义处理（不强制等于 true，只确认能落 NULL 且不报错）
# ============================================================

log "STEP5: create conversation without webSearchEnabled field (NULL fallback)"
CREATE_NULL_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations" \
  "{\"message\":\"$PROBE_PROMPT\",\"plannerAgentId\":null}" \
  "$LLM_TIMEOUT_SEC")"
assert_r200 "$CREATE_NULL_RESP" "create (null flag)"
NULL_CONV_ID="$(jq_value "$CREATE_NULL_RESP" '.data.conversation.id')"
DETAIL_NULL_RESP="$(http_json GET "$BASE_URL/api/requirement-conversations/$NULL_CONV_ID" "-")"
assert_r200 "$DETAIL_NULL_RESP" "detail (null flag)"
NULL_FLAG="$(jq_value "$DETAIL_NULL_RESP" '.data.conversation.webSearchEnabled')"
assert_eq "$NULL_FLAG" "null" "不传字段时 webSearchEnabled 落库为 null（读取侧按默认开启）"
log "null-flag conversationId=$NULL_CONV_ID webSearchEnabled=$NULL_FLAG"

# ============================================================
# 清理：abandon 全部新建会话
# ============================================================

log "STEP6: abandon all conversations (off/on/null)"
for cid in "$OFF_CONV_ID" "$ON_CONV_ID" "$NULL_CONV_ID"; do
  ABANDON_RESP="$(http_json POST "$BASE_URL/api/requirement-conversations/$cid/abandon" "{}")"
  assert_r200 "$ABANDON_RESP" "abandon $cid"
done

log "OK: websearch e2e passed"
log "off-pathConversationId=$OFF_CONV_ID"
log "on-pathConversationId=$ON_CONV_ID"
log "null-flagConversationId=$NULL_CONV_ID"
log "质化对比说明：开启/关闭两条路径用同一句 prompt；"
log "              可在任务管理侧对照终稿 finalDescription 是否更贴合"
log "              '在线协作文档平台/Notion/飞书文档/版本历史'等行业术语。"
exit 0
