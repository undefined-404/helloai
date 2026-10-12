#!/usr/bin/env bash
# ============================================================
# helloai 内部 LLM 工具能力 E2E 验证（REF-8.1 / REF-8.4 收敛判据③）—— macOS/Linux 版
#
# 用途：验证内部 LLM（API_KEY_LLM）**真的会自己调 internal_* 工具**完成任务，
#       而不是「工具面已注入但模型只聊天」。这是 REF-8.1 验收「可调通」的
#       唯一硬证据（此前只有单测直连方法，未走模型循环）。
#
# 流程（全程真实链路，无 mock）：
#   POST /api/auth/login                                管理员登录
#   POST /api/agents/register                           注册 PLANNER/EXECUTOR/REVIEWER（每次运行唯一名，确保拿得到 apiKey）
#   POST /api/credentials/bindApiKeyByAgentId/{id}      绑定托管 LLM 凭证
#   POST /api/tasks                                     建任务（描述强制要求「先读附件再作答」）
#   POST /api/tasks/planById/{id}                       触发 LLM 拆解（异步，轮询草案）
#   POST /api/tasks/confirmPlanByTaskId/{id}            转正 + 自动分发
#   POST /api/artifacts/upload                          子任务一被指派，立刻以**该执行者身份**上传探针附件
#   GET  /api/sub-tasks?taskId=                         轮询直到全部 DONE
#   GET  /api/sub-tasks/listTimelineBySubTaskId/{id}    取 tool_call_started/completed 事件
#   GET  /api/sub-tasks/listConversationBySubTaskId/{id} 取执行产出全文
#
# 断言（HARD = 失败即 exit 1）：
#   H1 至少一次 tool_call_started 且 tool 以 internal_ 开头      → L1 工具面注入 + 模型真的调了
#   H2 至少一次 internal_get_sub_task_detail                     → 模型走了「先看任务详情」的发现步
#   H3 至少一次 internal_read_attachment                         → 模型真的读了附件正文
#   H4 至少一次 tool_call_completed(tool=internal_read_attachment) 的 output 含 marker
#                                                                 → 附件内容确实流进了模型上下文
#   H5 marker 出现在执行产出全文（conversation）                 → 模型把读到的内容用进了交付物
#   S1 internal_register_output_attachment（SOFT，仅告警）       → 交付登记工具（模型按需，不强制）
#
# 对实现：scripts/powershell/verify-internal-llm-tools-e2e.ps1（Windows 侧同源；本 .sh 首次 macOS 落地）。
#   相对 ps1 的增强（不改变断言口径）：
#     ① STEP8 额外打印「探针附件上传时刻 ↔ 工具调用时刻」对照，用于判 H3/H4 失败是
#        「附件没赶在执行前落地」还是「模型调了工具但没用内容」；两因结论完全不同。
#     ② 指派到本次注册之外的 Agent 时打印可操作 HINT；加 --fail-on-foreign-assignee 可在发现即退出。
#     ③ **STEP2.8 打卡上班（默认开）**：`AgentSelector.pickPreferred` 排序首位是 dutyRank，
#        而 dev 库通常已存在 score 更高的存量 API_KEY_LLM 候选 ⇒ 不打卡则初始分配落到存量 Agent，
#        探针附件无法以「该执行者身份」上传（403），H3/H4 恒红。
#        打卡后本次 Agent dutyRank=1 胜出；离场 trap 自动 checkOut。`--no-check-in` 回到 ps1 行为。
#     ④ bash 3.2 兼容（macOS 自带 bash 无关联数组），仅依赖 curl/jq/awk/sort/openssl。
#     ⑤ **两层结论 + 证据先出**：流程未收敛（DEAD_LETTER / BLOCKED / 超时）时**不再当场退出**，
#        仍跑 STEP8 采集工具调用证据 —— 平台 `auto-review-max-rework`（默认 3）会让严格评审把
#        「格式/证据不足」的子任务打到死信，而这与「模型会不会调 internal_*」是两回事。
#        退出码：0=两层皆过 / 1=判据层红（H1-H5 失败）/ 2=判据层绿但流程层未收敛。
#     ⑥ Agent 来源：`--reuse-agents`（或注册被「同角色同模型唯一」占用时自动）复用平台存量
#        API_KEY_LLM Agent（管理端列表可见其 apiKey）—— dev 库通常已被 inner-<model>-<role>
#        占满，注册路径必 409；复用不改库、不绑凭证，用其自带托管凭证。
#
# 前置：helloai 已在 6565 运行；helloai.providers 已配置可用 LLM。
#       选人收敛不靠 force-access-type（该开关只按 accessType 过滤，过滤不掉同为
#       API_KEY_LLM 的存量候选），靠 STEP2.8 的**值班租约**（dutyRank 优先）——见下。
#
# 凭据获取（管理员口令 / LLM API Key，优先级从高到低）：
#   1) --admin-password / --llm-api-key 参数传非空值
#   2) 环境变量 HELLOAI_ADMIN_PASSWORD / DEEPSEEK_API_KEY 非空
#   3) 两者都没有 → 交互式提示输入（不回显）；非交互宿主（CI/无人值守）直接报错
#
# 已知残量竞态（诚实边界）：附件上传要求子任务**已被指派**，而指派与执行几乎同时发生。
#       脚本以 1s 粒度轮询 assignedAgent 并立即上传，通常能赶在模型第一次工具调用之前；
#       带依赖的子任务执行得晚，必定看得到附件。若 H3/H4 失败，先看 STEP8 打印的对照表。
#
# 用法（项目根）：
#   HELLOAI_ADMIN_PASSWORD=xx DEEPSEEK_API_KEY=sk-xx bash scripts/shell/verify-internal-llm-tools-e2e.sh
#   bash scripts/shell/verify-internal-llm-tools-e2e.sh --llm-model-type deepseek:deepseek-v4-flash \
#        --admin-password 'xx' --llm-api-key 'sk-xx'
#
# Ref: doc/HelloAI 实现差距表.md G-021（REF-8.1）· D-2026-10-12-5
#      doc/plan/HelloAI 借鉴落地实施计划.md §10.2 / §10.4（收敛判据③）
#      LOG-20261012-006
# ============================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_ROOT="${TMP_ROOT:-$SCRIPT_DIR/.tmp}"
mkdir -p "$TMP_ROOT"
RUN_DIR="$(mktemp -d "$TMP_ROOT/verify-internal-llm-tools-e2e.XXXXXX")"

BASE_URL="${BASE_URL:-http://localhost:6565}"
ADMIN_USERNAME="${HELLOAI_ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${HELLOAI_ADMIN_PASSWORD:-}"
LLM_MODEL_TYPE="${LLM_MODEL_TYPE:-deepseek:deepseek-v4-flash}"
LLM_API_KEY="${DEEPSEEK_API_KEY:-}"
LLM_KEY_ENV_NAME="${LLM_KEY_ENV_NAME:-DEEPSEEK_API_KEY}"
ATTACHMENT_FILE_NAME="${ATTACHMENT_FILE_NAME:-input-brief.txt}"
PLAN_TIMEOUT_SEC="${PLAN_TIMEOUT_SEC:-360}"
LOOP_TIMEOUT_SEC="${LOOP_TIMEOUT_SEC:-900}"
POLL_INTERVAL_SEC="${POLL_INTERVAL_SEC:-10}"
# 指派后上传附件的轮询粒度（赶在执行前落地）
ASSIGN_POLL_SEC="${ASSIGN_POLL_SEC:-1}"
# 等所有子任务都被指派的窗口（超时只告警，不失败——后续轮询仍会补传）
ASSIGN_WAIT_SEC="${ASSIGN_WAIT_SEC:-180}"
FAIL_ON_FOREIGN_ASSIGNEE="false"
# Agent 来源：auto=能注册就注册，被「同角色同模型唯一」占用则改用平台存量 Agent；on=直接复用；off=只注册
REUSE_MODE="${REUSE_MODE:-auto}"
# 打卡上班（值班租约）——见 check_in_agent 上方注释：不打卡则初始分配大概率落到存量 Agent
CHECK_IN_AGENTS="true"
DUTY_TTL_MINUTES="${DUTY_TTL_MINUTES:-60}"
DUTY_MAX_CONCURRENT="${DUTY_MAX_CONCURRENT:-5}"

usage() {
  cat <<'EOF'
用法: bash scripts/shell/verify-internal-llm-tools-e2e.sh [选项]
  --base-url <url>              默认 http://localhost:6565（或环境变量 BASE_URL）
  --admin-username <name>       默认 admin
  --admin-password <pw>         默认环境变量 HELLOAI_ADMIN_PASSWORD，缺失则交互输入
  --llm-model-type <p:m>        默认 deepseek:deepseek-v4-flash（provider 取冒号前半段）
  --llm-api-key <key>           默认环境变量 DEEPSEEK_API_KEY，缺失则交互输入
  --llm-key-env-name <name>     仅用于提示文案（默认 DEEPSEEK_API_KEY）
  --attachment-file-name <name> 探针附件文件名，默认 input-brief.txt
  --plan-timeout-sec <n>        等草案超时，默认 360
  --loop-timeout-sec <n>        等全部子任务 DONE 超时，默认 900
  --poll-interval-sec <n>       内循环轮询粒度，默认 10
  --assign-wait-sec <n>         等全部子任务被指派的窗口，默认 180
  --fail-on-foreign-assignee    子任务被派给非本次注册 Agent 时立即退出（默认仅告警）
  --reuse-agents                直接复用平台存量 API_KEY_LLM Agent（跳过注册/绑定凭证）
  --no-reuse-agents             禁用自动复用（注册被 409 占用则直接失败）
  --no-check-in                 不给本次 Agent 打卡上班（默认打卡：否则存量 API_KEY_LLM
                                Agent 会因 score 更高被优先选中，探针附件传不进去）
  --duty-ttl-minutes <n>        值班租约 TTL，默认 60（离场自动 checkOut）
  -h, --help                    显示本帮助
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url)                BASE_URL="${2:?--base-url 需要参数}"; shift 2 ;;
    --admin-username)          ADMIN_USERNAME="${2:?--admin-username 需要参数}"; shift 2 ;;
    --admin-password)          ADMIN_PASSWORD="${2:?--admin-password 需要参数}"; shift 2 ;;
    --llm-model-type)          LLM_MODEL_TYPE="${2:?--llm-model-type 需要参数}"; shift 2 ;;
    --llm-api-key)             LLM_API_KEY="${2:?--llm-api-key 需要参数}"; shift 2 ;;
    --llm-key-env-name)        LLM_KEY_ENV_NAME="${2:?--llm-key-env-name 需要参数}"; shift 2 ;;
    --attachment-file-name)    ATTACHMENT_FILE_NAME="${2:?--attachment-file-name 需要参数}"; shift 2 ;;
    --plan-timeout-sec)        PLAN_TIMEOUT_SEC="${2:?--plan-timeout-sec 需要参数}"; shift 2 ;;
    --loop-timeout-sec)        LOOP_TIMEOUT_SEC="${2:?--loop-timeout-sec 需要参数}"; shift 2 ;;
    --poll-interval-sec)       POLL_INTERVAL_SEC="${2:?--poll-interval-sec 需要参数}"; shift 2 ;;
    --assign-wait-sec)         ASSIGN_WAIT_SEC="${2:?--assign-wait-sec 需要参数}"; shift 2 ;;
    --fail-on-foreign-assignee) FAIL_ON_FOREIGN_ASSIGNEE="true"; shift ;;
    --reuse-agents)            REUSE_MODE="on"; shift ;;
    --no-reuse-agents)         REUSE_MODE="off"; shift ;;
    --no-check-in)             CHECK_IN_AGENTS="false"; shift ;;
    --duty-ttl-minutes)        DUTY_TTL_MINUTES="${2:?--duty-ttl-minutes 需要参数}"; shift 2 ;;
    -h|--help)                 usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
  esac
done

need_cmd() { command -v "$1" >/dev/null 2>&1 || { echo "缺少依赖命令: $1" >&2; exit 1; }; }
log()  { printf '%s\n' "$*"; }
fail() { printf 'ASSERT_FAIL: %s\n' "$*" >&2; exit 1; }

# 凭据获取三级优先：1) 显式参数非空 2) 环境变量非空 3) 交互输入（仅交互宿主；空输入最多重试 3 次）
# 注：经全局 RESOLVED 回传而非 print——若用 $( ) 捕获，函数内 fail 只退出子 shell，主脚本会带空值继续。
RESOLVED=""
resolve_credential() { # 当前值 提示文案 缺失文案
  local val="$1" prompt="$2" missing="$3" i pw
  if [[ -n "$val" ]]; then RESOLVED="$val"; return 0; fi
  [[ -t 0 ]] || fail "${missing}（非交互宿主不提示输入）"
  for i in 1 2 3; do
    read -r -s -p "$prompt" pw || true
    printf '\n' >&2
    if [[ -n "$pw" ]]; then RESOLVED="$pw"; return 0; fi
    log "  empty input - attempt $i of 3"
  done
  fail "$missing"
}

need_cmd curl
need_cmd jq
need_cmd mktemp
need_cmd awk
need_cmd sort
need_cmd openssl

resolve_credential "$ADMIN_PASSWORD" "Admin password (hidden input): " \
  "缺少管理员口令：请设置环境变量 HELLOAI_ADMIN_PASSWORD 或传 --admin-password"
ADMIN_PASSWORD="$RESOLVED"
resolve_credential "$LLM_API_KEY" "LLM api key (hidden input): " \
  "缺少 LLM API Key：请设置环境变量 $LLM_KEY_ENV_NAME 或传 --llm-api-key"
LLM_API_KEY="$RESOLVED"

# 本次注册的三个 Agent（提前初始化为空串：cleanup trap 在变量未赋值时也要安全）
PLANNER_ID="";  PLANNER_KEY=""
EXECUTOR_ID=""; EXECUTOR_KEY=""
REVIEWER_ID=""; REVIEWER_KEY=""
REUSED_AGENTS="false"

# ------------------------------------------------------------
# HTTP 辅助：HTTP_CODE / HTTP_BODY 为全局出参
# ------------------------------------------------------------
HTTP_CODE=""
HTTP_BODY=""
HTTP_BODY_FILE="$RUN_DIR/http-body.txt"

http_request() { # method url body [header...]
  local method="$1" url="$2" body="$3"; shift 3
  local -a args=(-sS -X "$method" "$url" -o "$HTTP_BODY_FILE" -w "%{http_code}"
                 --max-time 30 -H "Accept: application/json")
  if [[ "$method" != "GET" ]]; then
    args+=(-H "Content-Type: application/json; charset=utf-8" --data "$body")
  fi
  local h
  for h in "$@"; do args+=(-H "$h"); done
  if ! HTTP_CODE="$(curl "${args[@]}")"; then
    fail "$method $url 请求失败（服务未启动？）"
  fi
  HTTP_BODY="$(cat "$HTTP_BODY_FILE" 2>/dev/null || true)"
}

admin_get() { http_request GET "$1" "" "X-Admin-Token: $ADMIN_TOKEN"; }
admin_post() { http_request POST "$1" "$2" "X-Admin-Token: $ADMIN_TOKEN"; }
agent_post() { http_request POST "$1" "$2" "Authorization: Bearer $3"; }

# ------------------------------------------------------------
# 值班租约（选人唯一可用确定性手段）
#   AgentSelector.pickPreferred 的排序首位是 dutyRank（isOnDuty ? 1 : 0），而 dev 库
#   通常已存在历史 API_KEY_LLM 候选（它们 score 更高 ⇒ 不带班必输）。本脚本注册的
#   Agent 打卡上班即得 dutyRank=1，初始分配（子任务）/评审选人（REVIEWER）才会落到本次
#   Agent 上——否则探针附件无法以「该执行者身份」上传（403），H3/H4 恒红且与能力无关。
#   --no-check-in 可关掉，回到与 ps1 完全一致的行为。
# ------------------------------------------------------------
CHECK_IN_ERR=""
check_in_agent() { # agent_id  api_key → 0 成功 / 1 失败（详情写入 CHECK_IN_ERR）
  local body
  body="$(jq -cn --arg mode "AUTO" --argjson mc "$DUTY_MAX_CONCURRENT" --argjson ttl "$DUTY_TTL_MINUTES" \
    '{workMode:$mode,maxConcurrent:$mc,ttlMinutes:$ttl}')"
  agent_post "$BASE_URL/api/mcp/tools/checkIn" "$body" "$2"
  if [[ "$HTTP_CODE" != "200" ]]; then
    CHECK_IN_ERR="HTTP $HTTP_CODE body=$HTTP_BODY"
    return 1
  fi
  return 0
}

check_out_agent() { # agent_id  api_key（best-effort，离场清租约，失败不报错）
  [[ -n "$1" ]] || return 0
  curl -sS -o /dev/null --max-time 10 -X POST "$BASE_URL/api/mcp/tools/checkOut" \
    -H "Authorization: Bearer $2" -H "Content-Type: application/json" \
    -d '{"closeReason":"verify-internal-llm-tools-e2e finished"}' >/dev/null 2>&1 || true
}

cleanup() {
  if [[ "$CHECK_IN_AGENTS" == "true" ]]; then
    check_out_agent "$PLANNER_ID" "$PLANNER_KEY"
    check_out_agent "$EXECUTOR_ID" "$EXECUTOR_KEY"
    check_out_agent "$REVIEWER_ID" "$REVIEWER_KEY"
  fi
}
trap cleanup EXIT

# ------------------------------------------------------------
# 领域辅助
# ------------------------------------------------------------
SUBS_JSON="[]"
list_sub_tasks() {
  # 端点已随「描述性路径」整改改名：旧路径 `GET /api/sub-tasks?taskId=` 实测 **405**，现为 /list
  admin_get "$BASE_URL/api/sub-tasks/list?taskId=$TASK_ID"
  [[ "$HTTP_CODE" == "200" ]] || fail "list sub-tasks HTTP $HTTP_CODE body=$HTTP_BODY"
  # page 缺省 ⇒ data 为纯数组；带 page 时才是 data.list 分页壳
  SUBS_JSON="$(printf '%s' "$HTTP_BODY" | jq -c '(.data // []) | if type == "array" then . else (.list // []) end')"
}
sub_task_ids()      { printf '%s' "$SUBS_JSON" | jq -r '.[].id'; }
sub_task_statuses() { printf '%s' "$SUBS_JSON" | jq -r '.[] | "\(.id):\(.status)"'; }

is_uploaded() { # sub_task_id
  case " $UPLOADED_IDS " in *" $1 "*) return 0 ;; *) return 1 ;; esac
}
mark_uploaded() { UPLOADED_IDS="$UPLOADED_IDS $1"; }

# 本次注册的 Agent 才认（否则 403 归属他人 / 409 无归属）
key_for_agent() { # agent_id → print apiKey；非本次注册返回 1
  case "$1" in
    "$PLANNER_ID")  printf '%s' "$PLANNER_KEY" ;;
    "$EXECUTOR_ID") printf '%s' "$EXECUTOR_KEY" ;;
    "$REVIEWER_ID") printf '%s' "$REVIEWER_KEY" ;;
    *) return 1 ;;
  esac
}

# 注册即返回 apiKey（唯一名 ⇒ 不会是「已存在、不回显 key」的分支）；REG_ID / REG_KEY 为出参
REG_ID=""
REG_KEY=""
register_llm_agent() { # name role
  local body
  body="$(jq -cn --arg name "$1" --arg role "$2" --arg model "$LLM_MODEL_TYPE" \
    '{name:$name,role:$role,description:"verify-internal-llm-tools-e2e",accessType:"API_KEY_LLM",modelType:$model}')"
  http_request POST "$BASE_URL/api/agents/register" "$body"
  [[ "$HTTP_CODE" == "200" ]] || fail "register $2 HTTP $HTTP_CODE body=$HTTP_BODY"
  REG_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.data.id // empty')"
  REG_KEY="$(printf '%s' "$HTTP_BODY" | jq -r '.data.apiKey // empty')"
  [[ -n "$REG_ID" ]] || fail "register $2 returned empty id: $HTTP_BODY"
  [[ -n "$REG_KEY" ]] || fail "register $2 returned empty apiKey: $HTTP_BODY"
}

bind_agent_api_key() { # agent_id
  local body
  body="$(jq -cn --arg provider "$LLM_PROVIDER" --arg key "$LLM_API_KEY" \
    '{provider:$provider,apiKey:$key,remark:"verify-internal-llm-tools-e2e"}')"
  admin_post "$BASE_URL/api/credentials/bindApiKeyByAgentId/$1" "$body"
  [[ "$HTTP_CODE" == "200" ]] || fail "bind api-key for agent $1 HTTP $HTTP_CODE body=$HTTP_BODY"
}

# multipart 上传探针附件（以该子任务执行者身份）；UPLOAD_CODE/UPLOAD_BODY 为出参
UPLOAD_CODE=""
UPLOAD_BODY=""
upload_probe_attachment() { # sub_task_id  agent_api_key
  local out="$RUN_DIR/upload-body.json"
  if ! UPLOAD_CODE="$(curl -sS -o "$out" -w "%{http_code}" --max-time 30 \
      -X POST "$BASE_URL/api/artifacts/upload" \
      -H "Authorization: Bearer $2" \
      -F "file=@$BRIEF_FILE;filename=$ATTACHMENT_FILE_NAME;type=text/plain" \
      -F "subTaskId=$1")"; then
    UPLOAD_CODE="000"
    UPLOAD_BODY="(curl 失败)"
    return 0
  fi
  UPLOAD_BODY="$(cat "$out" 2>/dev/null || true)"
}

TIMELINE_DATA="[]"
CONVO_DATA="[]"
timeline_json() { # sub_task_id → 出参 TIMELINE_DATA（compact 数组）
  admin_get "$BASE_URL/api/sub-tasks/listTimelineBySubTaskId/$1"
  [[ "$HTTP_CODE" == "200" ]] || fail "timeline HTTP $HTTP_CODE body=$HTTP_BODY"
  TIMELINE_DATA="$(printf '%s' "$HTTP_BODY" | jq -c '(.data // [])')"
}

conversation_json() { # sub_task_id → 出参 CONVO_DATA（compact 数组）
  admin_get "$BASE_URL/api/sub-tasks/listConversationBySubTaskId/$1"
  [[ "$HTTP_CODE" == "200" ]] || fail "conversation HTTP $HTTP_CODE body=$HTTP_BODY"
  CONVO_DATA="$(printf '%s' "$HTTP_BODY" | jq -c '(.data // [])')"
}

# ------------------------------------------------------------
# STEP 0: server reachability
# ------------------------------------------------------------
log "STEP0: server reachability ($BASE_URL)"
http_request GET "$BASE_URL/api/health" ""
[[ "$HTTP_CODE" == "200" ]] || fail "server NOT reachable at $BASE_URL - start HelloAIApplication first (HTTP $HTTP_CODE)"
log "  HTTP $HTTP_CODE - server is up"

# ------------------------------------------------------------
# STEP 1: admin login
# ------------------------------------------------------------
log "STEP1: admin login"
login_body="$(jq -cn --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" \
  '{type:"admin",username:$u,credential:$p}')"
http_request POST "$BASE_URL/api/auth/login" "$login_body"
[[ "$HTTP_CODE" == "200" ]] || fail "login HTTP $HTTP_CODE body=$HTTP_BODY"
ADMIN_TOKEN="$(printf '%s' "$HTTP_BODY" | jq -r '.data.token // empty')"
[[ -n "$ADMIN_TOKEN" ]] || fail "admin token is empty: $HTTP_BODY"

TS="$(date -u +%Y%m%d%H%M%S)"
LLM_PROVIDER="${LLM_MODEL_TYPE%%:*}"
UPLOADED_IDS=""

# 探针 marker：每次运行随机，避免与历史数据撞车
MARKER="HLMRK-$(openssl rand -hex 4 | tr '[:lower:]' '[:upper:]')"
log "  probe marker = $MARKER"

BRIEF_FILE="$RUN_DIR/$ATTACHMENT_FILE_NAME"
cat >"$BRIEF_FILE" <<EOF
INPUT BRIEF - internal LLM tool capability probe
=================================================
marker_token: $MARKER
product: internal note-taking app 'NoteDeck'
positioning: a single-window workspace where engineers capture decisions next to code
features: (1) code-block aware capture, (2) decision timeline with reversible edits, (3) offline-first sync
rule: any executor MUST quote marker_token verbatim in its final answer
EOF

# ------------------------------------------------------------
# STEP 2: register platform LLM agents (unique names -> apiKey always returned)
# ------------------------------------------------------------
log "STEP2: provision platform LLM agents (PLANNER/EXECUTOR/REVIEWER, API_KEY_LLM)"
# 平台限「同一模型在同一角色下唯一（API_KEY_LLM + 未删除）」。dev 库通常已被常驻
# inner-<model>-<role> Agent 占满 ⇒ 先探测占用，占用则走复用（auto/on），否则注册。
# 复用不改库、不绑凭证：直接用存量 Agent 自己的托管凭证 + 它的 apiKey（管理端列表可见）。
AGENTS_JSON="[]"
list_admin_agents() {
  admin_get "$BASE_URL/api/admin/agents/list?page=1&pageSize=200"
  [[ "$HTTP_CODE" == "200" ]] || fail "admin agents list HTTP $HTTP_CODE body=$HTTP_BODY"
  AGENTS_JSON="$(printf '%s' "$HTTP_BODY" | jq -c '(.data.list // [])')"
}

# 精确模型优先，其次同角色任意可用 API_KEY_LLM；出参 REUSE_ID/REUSE_KEY/REUSE_NAME/REUSE_MODEL
REUSE_ID=""; REUSE_KEY=""; REUSE_NAME=""; REUSE_MODEL=""
pick_reuse_agent() { # role
  local row
  row="$(printf '%s' "$AGENTS_JSON" | jq -r --arg role "$1" --arg model "$LLM_MODEL_TYPE" '
    [ .[] | select(.role == $role and .accessType == "API_KEY_LLM" and .status == "ACTIVE"
                   and ((.apiKey // "") | length) > 0) ]
    | (map(select(.modelType == $model)) + .)
    | .[0] // empty
    | "\(.id)\t\(.apiKey)\t\(.name)\t\(.modelType)"')"
  [[ -n "$row" ]] || return 1
  REUSE_ID="${row%%$'\t'*}"; row="${row#*$'\t'}"
  REUSE_KEY="${row%%$'\t'*}"; row="${row#*$'\t'}"
  REUSE_NAME="${row%%$'\t'*}"; REUSE_MODEL="${row#*$'\t'}"
}

list_admin_agents
# 三个角色任一被同模型占用 ⇒ 注册路径必 409（避免半注册：PLANNER 成功、EXECUTOR 报错留残留）
occupied="$(printf '%s' "$AGENTS_JSON" | jq -r --arg model "$LLM_MODEL_TYPE" \
  '[ .[] | select(.accessType == "API_KEY_LLM" and .modelType == $model
                  and (.role == "PLANNER" or .role == "EXECUTOR" or .role == "REVIEWER"))
     | "\(.role):\(.name)" ] | join(",")')"

if [[ "$REUSE_MODE" == "off" ]]; then
  log "  register mode (--no-reuse-agents)"
elif [[ "$REUSE_MODE" == "on" || -n "$occupied" ]]; then
  if [[ "$REUSE_MODE" == "on" ]]; then
    log "  reuse mode (--reuse-agents)"
  else
    log "  reuse mode (auto)：$LLM_MODEL_TYPE 已被占用（${occupied}）—— 平台限「同角色同模型唯一」，改用存量 Agent"
  fi
  pick_reuse_agent "PLANNER"  || fail "复用失败：找不到 ACTIVE 且带 apiKey 的 API_KEY_LLM PLANNER（--reuse-agents 需存量 Agent）"
  PLANNER_ID="$REUSE_ID";  PLANNER_KEY="$REUSE_KEY";  log "  planner  = $REUSE_NAME ($REUSE_MODEL)"
  pick_reuse_agent "EXECUTOR" || fail "复用失败：找不到 ACTIVE 且带 apiKey 的 API_KEY_LLM EXECUTOR"
  EXECUTOR_ID="$REUSE_ID"; EXECUTOR_KEY="$REUSE_KEY"; log "  executor = $REUSE_NAME ($REUSE_MODEL)"
  pick_reuse_agent "REVIEWER" || fail "复用失败：找不到 ACTIVE 且带 apiKey 的 API_KEY_LLM REVIEWER"
  REVIEWER_ID="$REUSE_ID"; REVIEWER_KEY="$REUSE_KEY"; log "  reviewer = $REUSE_NAME ($REUSE_MODEL)"
  REUSED_AGENTS="true"
else
  register_llm_agent "illt-planner-$TS"  "PLANNER";  PLANNER_ID="$REG_ID";  PLANNER_KEY="$REG_KEY"
  register_llm_agent "illt-executor-$TS" "EXECUTOR"; EXECUTOR_ID="$REG_ID"; EXECUTOR_KEY="$REG_KEY"
  register_llm_agent "illt-reviewer-$TS" "REVIEWER"; REVIEWER_ID="$REG_ID"; REVIEWER_KEY="$REG_KEY"
  log "  planner=$PLANNER_ID executor=$EXECUTOR_ID reviewer=$REVIEWER_ID"
fi

if [[ "$REUSED_AGENTS" == "true" ]]; then
  log "STEP2.5: skip vault bind (复用存量 Agent 用其自己的托管凭证，不改库)"
else
  log "STEP2.5: bind vault api-key (provider=$LLM_PROVIDER)"
  bind_agent_api_key "$PLANNER_ID"
  bind_agent_api_key "$EXECUTOR_ID"
  bind_agent_api_key "$REVIEWER_ID"
  log "  vault credentials bound"
fi

if [[ "$CHECK_IN_AGENTS" == "true" ]]; then
  log "STEP2.8: check in (duty lease ttl=${DUTY_TTL_MINUTES}min) —— 让初始分配选中本次 Agent"
  # EXECUTOR 是硬前置：探针附件必须由「该子任务的执行者」上传，落到存量 Agent 则 H3/H4 无解。
  # PLANNER/REVIEWER 只告警：存量 API_KEY_LLM Agent 同样会被平台自动执行，不阻断本轮验证。
  check_in_agent "$EXECUTOR_ID" "$EXECUTOR_KEY" \
    || fail "EXECUTOR checkIn 失败：${CHECK_IN_ERR}（探针附件必须由该子任务的执行者上传，执行者不是本次 Agent 则 H3/H4 无解）"
  log "  on duty: executor=$EXECUTOR_ID"
  if check_in_agent "$PLANNER_ID" "$PLANNER_KEY"; then
    log "  on duty: planner=$PLANNER_ID"
  else
    log "  WARN: PLANNER checkIn 失败（${CHECK_IN_ERR}）—— 不阻断：存量 API_KEY_LLM 规划者同样会被平台自动执行"
  fi
  if check_in_agent "$REVIEWER_ID" "$REVIEWER_KEY"; then
    log "  on duty: reviewer=$REVIEWER_ID"
  else
    log "  WARN: REVIEWER checkIn 失败（${CHECK_IN_ERR}）—— 不阻断（同上）"
  fi
else
  log "STEP2.8: skipped check-in (--no-check-in)：分配可能落到存量 Agent 上"
fi

# ------------------------------------------------------------
# STEP 3: create task whose description FORCES reading the attachment
# ------------------------------------------------------------
log "STEP3: create task (description mandates reading the attached input file)"
TASK_DESC="Two-part internal note, strictly in order:
1) A required input file named '$ATTACHMENT_FILE_NAME' is attached to your sub-task. Before writing anything you MUST use the platform tools to read that attachment's full text. Do not guess, summarise from memory, or invent its content.
2) Then write a 3-sentence summary of the brief, and on its own line quote the marker_token value found in that file verbatim."
task_body="$(jq -cn --arg title "internal-llm-tools-e2e-$TS" --arg description "$TASK_DESC" \
  '{title:$title,description:$description}')"
admin_post "$BASE_URL/api/tasks" "$task_body"
[[ "$HTTP_CODE" == "200" ]] || fail "create task HTTP $HTTP_CODE body=$HTTP_BODY"
TASK_ID="$(printf '%s' "$HTTP_BODY" | jq -r '.data.id // empty')"
[[ -n "$TASK_ID" ]] || fail "create task returned empty id: $HTTP_BODY"
log "  taskId=$TASK_ID"

# ------------------------------------------------------------
# STEP 4: decompose (async) + confirm
# ------------------------------------------------------------
log "STEP4: trigger decompose (async; timeout=${PLAN_TIMEOUT_SEC}s)"
admin_post "$BASE_URL/api/tasks/planById/$TASK_ID" '{}'
[[ "$HTTP_CODE" == "200" ]] || fail "plan HTTP $HTTP_CODE body=$HTTP_BODY"

DRAFTS_JSON="[]"
plan_deadline=$((SECONDS + PLAN_TIMEOUT_SEC))
while (( SECONDS < plan_deadline )); do
  admin_get "$BASE_URL/api/tasks/findPlanByTaskId/$TASK_ID"
  [[ "$HTTP_CODE" == "200" ]] || fail "findPlan HTTP $HTTP_CODE body=$HTTP_BODY"
  DRAFTS_JSON="$(printf '%s' "$HTTP_BODY" | jq -c '(.data // [])')"
  (( $(printf '%s' "$DRAFTS_JSON" | jq 'length') >= 1 )) && break
  sleep 3
done
draft_count="$(printf '%s' "$DRAFTS_JSON" | jq 'length')"
(( draft_count >= 1 )) || fail "expected >=1 drafts, actual=${draft_count}（$PLAN_TIMEOUT_SEC s 内未产出；PLANNER 是否被派给本次注册的 API_KEY_LLM Agent？）"
printf '%s' "$DRAFTS_JSON" | jq -r '.[] | "  draft id=\(.id) title=\(.title) dependsOn=\((.dependsOn // []) | join(","))"'

log "STEP5: confirm plan"
admin_post "$BASE_URL/api/tasks/confirmPlanByTaskId/$TASK_ID" '{}'
[[ "$HTTP_CODE" == "200" ]] || fail "confirm HTTP $HTTP_CODE body=$HTTP_BODY"

# ------------------------------------------------------------
# STEP 6: upload the probe attachment the moment each sub-task gets an assignee
#   (upload requires assignedAgent != null AND caller == assignee; see ArtifactUploadServiceImpl)
# ------------------------------------------------------------
log "STEP6: upload probe attachment as soon as each sub-task is assigned"
WARNED_FOREIGN="$RUN_DIR/warned-foreign.txt"
: >"$WARNED_FOREIGN"
UPLOAD_LOG="$RUN_DIR/upload-log.txt"
: >"$UPLOAD_LOG"

warn_foreign_once() { # sub_task_id  assignee
  if ! grep -qx "$1" "$WARNED_FOREIGN" 2>/dev/null; then
    printf '%s\n' "$1" >>"$WARNED_FOREIGN"
    log "  WARN: subTask $1 assigned to agent $2 which is NOT one of this run's API_KEY_LLM agents"
    log "        HINT: 本脚本默认会给本次 Agent 打卡（dutyRank 优先）；仍被派给他人说明有并发抢占，"
    log "              可加 --fail-on-foreign-assignee 快速失败，或改用独占的候选池（清掉存量候选 / 换库）"
    if [[ "$FAIL_ON_FOREIGN_ASSIGNEE" == "true" ]]; then
      fail "subTask $1 被派给非本次注册的 Agent $2 ⇒ 本次断言无意义（--fail-on-foreign-assignee 生效）"
    fi
  fi
}

assign_deadline=$((SECONDS + ASSIGN_WAIT_SEC))
while :; do
  list_sub_tasks
  while IFS=$'\t' read -r sid assignee; do
    [[ -n "$sid" ]] || continue
    is_uploaded "$sid" && continue
    [[ -n "$assignee" ]] || continue
    if ! akey="$(key_for_agent "$assignee")"; then
      warn_foreign_once "$sid" "$assignee"
      continue
    fi
    upload_probe_attachment "$sid" "$akey"
    if [[ "$UPLOAD_CODE" =~ ^2 ]]; then
      mark_uploaded "$sid"
      printf '%s|%s|%s\n' "$sid" "$(date +%H:%M:%S)" "$UPLOAD_BODY" >>"$UPLOAD_LOG"
      log "  uploaded probe attachment for subTask $sid (assignee=$assignee)"
    else
      log "  WARN: upload failed for subTask $sid HTTP $UPLOAD_CODE body=$UPLOAD_BODY"
    fi
  done < <(printf '%s' "$SUBS_JSON" | jq -r '.[] | select((.assignedAgent // "") != "") | "\(.id)\t\(.assignedAgent)"')

  remaining=0
  for sid in $(sub_task_ids); do is_uploaded "$sid" || remaining=$((remaining + 1)); done
  (( remaining == 0 )) && break
  if (( SECONDS > assign_deadline )); then
    log "  WARN: not every sub-task got an upload within ${ASSIGN_WAIT_SEC}s; continuing (STEP7 keeps retrying)"
    break
  fi
  sleep "$ASSIGN_POLL_SEC"
done
uploaded_count="$(printf '%s' "$UPLOADED_IDS" | wc -w | tr -d ' ')"
log "  uploaded=$uploaded_count subTasks"

# ------------------------------------------------------------
# STEP 7: watch inner loop until all DONE
# ------------------------------------------------------------
log "STEP7: watch loop until all subTasks DONE (timeout=${LOOP_TIMEOUT_SEC}s)"
loop_deadline=$((SECONDS + LOOP_TIMEOUT_SEC))
all_done="false"
LOOP_ISSUE=""
while (( SECONDS < loop_deadline )); do
  sleep "$POLL_INTERVAL_SEC"
  list_sub_tasks
  log "  [$(date +%H:%M:%S)] $(sub_task_statuses | tr '\n' ' ')"

  # 边跑边补传（带依赖的子任务此时才被指派）
  while IFS=$'\t' read -r sid assignee; do
    [[ -n "$sid" ]] || continue
    is_uploaded "$sid" && continue
    [[ -n "$assignee" ]] || continue
    if ! akey="$(key_for_agent "$assignee")"; then
      warn_foreign_once "$sid" "$assignee"
      continue
    fi
    upload_probe_attachment "$sid" "$akey"
    if [[ "$UPLOAD_CODE" =~ ^2 ]]; then
      mark_uploaded "$sid"
      printf '%s|%s|%s\n' "$sid" "$(date +%H:%M:%S)" "$UPLOAD_BODY" >>"$UPLOAD_LOG"
      log "  [late] uploaded probe attachment for subTask $sid (assignee=$assignee)"
    fi
  done < <(printf '%s' "$SUBS_JSON" | jq -r '.[] | select((.assignedAgent // "") != "") | "\(.id)\t\(.assignedAgent)"')

  # 死信/阻塞只记录并跳出——**不在此处 fail**：H1-H5 是「内部 LLM 工具能力」判据，
  # 与之无关的流程未收敛（评审返工超限、超时）不该把已产生的证据一起丢掉。
  dead="$(printf '%s' "$SUBS_JSON" | jq -r '.[] | select(.status == "DEAD_LETTER" or .status == "BLOCKED") | .id' | tr '\n' ',')"
  if [[ -n "$dead" ]]; then LOOP_ISSUE="subTasks entered DEAD_LETTER/BLOCKED: $dead"; break; fi

  not_done="$(printf '%s' "$SUBS_JSON" | jq -r '.[] | select(.status != "DONE" and .status != "CANCELLED") | .id' | tr '\n' ',')"
  if [[ -z "$not_done" ]]; then all_done="true"; break; fi
done
if [[ "$all_done" == "true" ]]; then
  log "  all subTasks DONE/CANCELLED ($(printf '%s' "$SUBS_JSON" | jq 'length') total)"
elif [[ -z "$LOOP_ISSUE" ]]; then
  LOOP_ISSUE="loop not finished in ${LOOP_TIMEOUT_SEC}s"
  log "  WARN: $LOOP_ISSUE —— 仍继续采集工具调用证据（STEP8）"
fi

# ------------------------------------------------------------
# STEP 8: collect tool-call evidence from every sub-task timeline
# ------------------------------------------------------------
log "STEP8: collect tool_call_* evidence from timelines"
TOOLS_STARTED="$RUN_DIR/tools-started.txt"
READ_OUTPUTS="$RUN_DIR/read-attachment-outputs.txt"
CALL_DIAG="$RUN_DIR/tool-call-diag.txt"
CONVO_TEXT="$RUN_DIR/conversation.txt"
: >"$TOOLS_STARTED"; : >"$READ_OUTPUTS"; : >"$CALL_DIAG"; : >"$CONVO_TEXT"

for sid in $(sub_task_ids); do
  timeline_json "$sid"
  printf '%s' "$TIMELINE_DATA" | jq -r '.[] | select(.eventType == "tool_call_started") | .payload.tool // empty' >>"$TOOLS_STARTED"
  printf '%s' "$TIMELINE_DATA" | jq -r '.[] | select(.eventType == "tool_call_completed" and .payload.tool == "internal_read_attachment") | .payload.output // empty' >>"$READ_OUTPUTS"
  { printf 'subTask %s | probeUploadedAt %s\n' "$sid" \
      "$(grep "^$sid|" "$UPLOAD_LOG" 2>/dev/null | head -n 1 | cut -d'|' -f2 || true)";
    printf '%s' "$TIMELINE_DATA" | jq -r '.[] | select((.eventType // "") | startswith("tool_call_")) | "    \(.createTime)  \(.eventType)  \(.payload.tool // "?")  success=\(.payload.success // "-")"'; } >>"$CALL_DIAG"
  conversation_json "$sid"
  printf '%s' "$CONVO_DATA" | jq -r '.[] | .content // empty' >>"$CONVO_TEXT"
done

log "  tool_call_started by tool:"
if [[ ! -s "$TOOLS_STARTED" ]]; then
  log "    (none - NO tool call happened at all)"
else
  sort "$TOOLS_STARTED" | uniq -c | sort -rn | awk '{ printf "    %s x%s\n", $2, $1 }'
fi

log ""
log "  附件上传时刻 ↔ 工具调用时刻（判 H3/H4 失败是「没赶在执行前」还是「读了没用」）："
cat "$CALL_DIAG"
log ""

# ------------------------------------------------------------
# STEP 9: assertions
# ------------------------------------------------------------
log "STEP9: assertions"
internal_started="$(grep '^internal_' "$TOOLS_STARTED" 2>/dev/null | sort -u | tr '\n' ',')"
[[ -n "$internal_started" ]] || fail "no internal_* tool call observed (started tools: $(sort -u "$TOOLS_STARTED" 2>/dev/null | tr '\n' ',')) - L1 tool surface not exercised"
log "  H1 OK: internal_* tool called (${internal_started%,})"

grep -qx 'internal_get_sub_task_detail' "$TOOLS_STARTED" \
  || fail "internal_get_sub_task_detail never called - model skipped task-detail discovery"
log "  H2 OK: internal_get_sub_task_detail called"

grep -qx 'internal_read_attachment' "$TOOLS_STARTED" \
  || fail "internal_read_attachment never called - model did not read the attached probe file"
log "  H3 OK: internal_read_attachment called"

grep -q -- "$MARKER" "$READ_OUTPUTS" \
  || fail "internal_read_attachment returned no output containing marker $MARKER - attachment content did not reach the model"
log "  H4 OK: marker flowed through internal_read_attachment output"

grep -q -- "$MARKER" "$CONVO_TEXT" \
  || fail "marker not found in any sub-task execution output - model read the file but did not use it in the deliverable"
log "  H5 OK: marker appears in execution output"

if grep -qx 'internal_register_output_attachment' "$TOOLS_STARTED"; then
  log "  S1 OK: internal_register_output_attachment also called"
else
  log "  S1 WARN: internal_register_output_attachment not called (soft: deliverable is plain text, platform materialises it)"
fi

# ------------------------------------------------------------
# summary
# ------------------------------------------------------------
admin_get "$BASE_URL/api/tasks/getById/$TASK_ID"
task_status="$(printf '%s' "$HTTP_BODY" | jq -r '.data.status // empty')"
sub_task_count="$(printf '%s' "$SUBS_JSON" | jq 'length')"

log ""
log "taskId=$TASK_ID taskStatus=$task_status"
log "  subTaskCount=$sub_task_count uploadedProbe=$uploaded_count"
log "  internal tools called: $(grep '^internal_' "$TOOLS_STARTED" | sort -u | tr '\n' ',' | sed 's/,$//')"
log "  marker=$MARKER"
log "  run dir: $RUN_DIR"
log ""
log "=== 结论（两层，互不替代）==="
log "判据层 H1-H5: PASS —— 内部 LLM 工具面已注入、模型真调 internal_*、附件内容回流进交付物"
if [[ -n "$LOOP_ISSUE" ]]; then
  log "流程层: 未收敛 —— $LOOP_ISSUE"
  log "  与内部 LLM 工具能力无关（属评审/返工/超时链）：看上面 STEP8 证据与本结论分层判读"
  exit 2
fi
log "流程层: PASS —— 全部子任务 DONE/CANCELLED"
log ""
log "OK: internal LLM tool capability e2e passed"
exit 0
