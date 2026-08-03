#!/usr/bin/env zsh
# ============================================================
# helloai 依赖产出双轨上下文注入 E2E 验证（Task Running Spec 依赖感知改造）
# 用途：验证"子任务存在多个前置时，下游 prompt 同时注入全部直接前置的
#       结构化摘要 + 完成内容本体"，且不出现"只记录最后一次前置信息"的覆盖缺陷：
#   1. 建任务 + 3 个子任务（sub1 无依赖 / sub2 无依赖 / sub3 依赖 sub1+sub2 双前置）
#   2. SQL 直写 sub3.depends_on=[sub1, sub2]（当前无 API 入口，同 V27 就绪守卫口径）
#   3. 并行 claim sub1/sub2（API_KEY_LLM agent claim 即自动执行，两前置并发完成，
#      EXECUTION_RECORD 并发回填不互覆）
#   4. 双前置 DONE 后 claim sub3（自动执行）→ 从 conversation_message 取 sub_task_execute_user_prompt
#   5. 断言 prompt 同时包含 sub1 与 sub2 的产出内容（双前置不覆盖）
# Ref:  doc/log/HelloAI_迭代执行记录.md §6.43（依赖感知双轨上下文注入）
# Pre-conditions:
#   - helloai-start 已在 6565 运行（本脚本自行启动或复用现有实例）
#   - docker helloai-postgres 在 15432（psql 经 docker exec 访问）
#   - 已配置可用 LLM（deepseek API key 默认值与 verify-planner-decompose.sh 一致）
# Usage:
#   bash ./scripts/shell/verify-deps-context-e2e.sh
# ============================================================

set -euo pipefail

# ------------------------------------------------------------
# UTF-8 编码强制头 (AGENTS.md 规则 6) — 避免中文乱码
# ------------------------------------------------------------
export LANG="${LANG:-zh_CN.UTF-8}"
export LC_ALL="${LC_ALL:-zh_CN.UTF-8}"

BASE_URL="${BASE_URL:-http://localhost:6565}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"
EXEC_MODEL_TYPE="${EXEC_MODEL_TYPE:-deepseek:deepseek-chat}"
LLM_API_KEY="${DEEPSEEK_API_KEY:-sk-a36fdda1d4ad4e0386e78fc435be0d16}"
VAULT_PROVIDER="${VAULT_PROVIDER:-deepseek}"
PG_CONTAINER="${PG_CONTAINER:-helloai-postgres}"
PG_DB="${PG_DB:-helloai}"
EXEC_TIMEOUT_SEC="${EXEC_TIMEOUT_SEC:-180}"   # 单个子任务执行等待上限
PROMPT_TIMEOUT_SEC="${PROMPT_TIMEOUT_SEC:-120}"  # sub3 prompt 落库等待上限

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="${LOG_FILE:-$SCRIPT_DIR/.tmp/verify-deps-context-e2e.log}"
mkdir -p "$(dirname "$LOG_FILE")"
: > "$LOG_FILE"

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

assert_r200() {
  local resp="$1" ctx="$2"
  local code msg
  code="$(print -r -- "$resp" | jq -r '.code // empty')"
  [[ "$code" == "200" ]] || {
    msg="$(print -r -- "$resp" | jq -r '.msg // empty')"
    fail "$ctx code=$code msg=$msg"
  }
}

# pg_sql <sql> — 在 PG 容器内执行 SQL，输出结果
pg_sql() {
  docker exec -i "$PG_CONTAINER" psql -U postgres -d "$PG_DB" -t -A -c "$1" || fail "psql failed: $1"
}

# wait_exec_done <subTaskId> <timeoutSec> — 轮询子任务状态直到离开 PENDING/ASSIGNED/IN_PROGRESS
wait_exec_done() {
  local sub_id="$1" timeout_sec="$2" waited=0 st
  while (( waited < timeout_sec )); do
    st="$(http_json GET "$BASE_URL/api/sub-tasks/$sub_id" "-" | jq -r '.data.status')"
    case "$st" in
      PENDING|ASSIGNED|IN_PROGRESS) ;;
      *) log "subTask[$sub_id] done: status=$st (waited=${waited}s)"; return 0 ;;
    esac
    sleep 5
    waited=$((waited + 5))
  done
  fail "wait_exec_done timeout: subTaskId=$sub_id still executing after ${timeout_sec}s"
}

# wait_prompt <subTaskId> <timeoutSec> — 轮询 conversation 直到出现 sub_task_execute_user_prompt（toolName 字段）
wait_user_prompt() {
  local sub_id="$1" timeout_sec="$2" waited=0 resp prompt
  while (( waited < timeout_sec )); do
    resp="$(http_json GET "$BASE_URL/api/sub-tasks/$sub_id/conversation" "-")"
    prompt="$(print -r -- "$resp" | jq -r '[.data[] | select(.toolName == "sub_task_execute_user_prompt")][0].content // empty')"
    if [[ -n "$prompt" ]]; then
      print -r -- "$prompt"
      return 0
    fi
    sleep 5
    waited=$((waited + 5))
  done
  fail "wait_user_prompt timeout: no sub_task_execute_user_prompt for subTaskId=$sub_id after ${timeout_sec}s"
}

need_cmd curl
need_cmd jq
need_cmd docker

log "STEP0: health check"
HEALTH="$(http_json GET "$BASE_URL/api/health" "-")"
assert_r200 "$HEALTH" "health"

log "STEP1: admin login"
LOGIN_RESP="$(http_json POST "$BASE_URL/api/auth/login" \
  "{\"type\":\"admin\",\"username\":\"$ADMIN_USERNAME\",\"credential\":\"$ADMIN_PASSWORD\"}")"
assert_r200 "$LOGIN_RESP" "login"
ADMIN_TOKEN="$(print -r -- "$LOGIN_RESP" | jq -r '.data.token // empty')"
[[ -n "$ADMIN_TOKEN" ]] || fail "admin token is empty"

TS="$(date -u +%Y%m%d%H%M%S)"

log "STEP2: register platform executor agent (API_KEY_LLM, idempotent fixed name)"
EXEC_RESP="$(http_json POST "$BASE_URL/api/agents/register" \
  "{\"name\":\"executor-deps-ctx\",\"role\":\"EXECUTOR\",\"description\":\"verify-deps-context-e2e\",\"accessType\":\"API_KEY_LLM\",\"modelType\":\"$EXEC_MODEL_TYPE\",\"idempotent\":true}")"
assert_r200 "$EXEC_RESP" "register executor"
EXEC_AGENT_ID="$(print -r -- "$EXEC_RESP" | jq -r '.data.id')"
log "executorAgentId=$EXEC_AGENT_ID"

log "STEP2.1: bind agent api-key credential (provider=$VAULT_PROVIDER)"
BIND_RESP="$(http_json POST "$BASE_URL/api/credentials/agents/$EXEC_AGENT_ID/api-key" \
  "{\"provider\":\"$VAULT_PROVIDER\",\"apiKey\":\"$LLM_API_KEY\",\"remark\":\"verify-deps-context-e2e\"}")"
assert_r200 "$BIND_RESP" "bind api-key"

log "STEP3: create task"
TASK_RESP="$(http_json POST "$BASE_URL/api/tasks" \
  "{\"title\":\"deps-ctx-e2e-$TS\",\"description\":\"E2E: multi-predecessor dependency context injection.\"}")"
assert_r200 "$TASK_RESP" "create task"
TASK_ID="$(print -r -- "$TASK_RESP" | jq -r '.data.id')"
log "taskId=$TASK_ID"

log "STEP4: create 3 subTasks (no assignedAgent -> stay PENDING, full manual control)"
SUB1_RESP="$(http_json POST "$BASE_URL/api/sub-tasks" \
  "{\"taskId\":$TASK_ID,\"title\":\"前置一：收集竞品资料\",\"description\":\"列出至少3个竞品及其核心差异\"}")"
assert_r200 "$SUB1_RESP" "create sub1"
SUB1_ID="$(print -r -- "$SUB1_RESP" | jq -r '.data.id')"
SUB2_RESP="$(http_json POST "$BASE_URL/api/sub-tasks" \
  "{\"taskId\":$TASK_ID,\"title\":\"前置二：收集用户反馈\",\"description\":\"汇总至少3条典型用户诉求\"}")"
assert_r200 "$SUB2_RESP" "create sub2"
SUB2_ID="$(print -r -- "$SUB2_RESP" | jq -r '.data.id')"
SUB3_RESP="$(http_json POST "$BASE_URL/api/sub-tasks" \
  "{\"taskId\":$TASK_ID,\"title\":\"下游：综合产出竞品对比结论\",\"description\":\"结合竞品资料与用户反馈给出产品建议\"}")"
assert_r200 "$SUB3_RESP" "create sub3"
SUB3_ID="$(print -r -- "$SUB3_RESP" | jq -r '.data.id')"
log "sub1Id=$SUB1_ID sub2Id=$SUB2_ID sub3Id=$SUB3_ID"

log "STEP5: set sub3 depends_on=[sub1, sub2] via SQL (no API entry yet)"
pg_sql "UPDATE sub_task SET depends_on='[$SUB1_ID,$SUB2_ID]'::jsonb WHERE id=$SUB3_ID;"
DEP_CHECK="$(pg_sql "SELECT depends_on::text FROM sub_task WHERE id=$SUB3_ID;")"
assert_eq "$DEP_CHECK" "[$SUB1_ID, $SUB2_ID]" "depends_on not persisted"

# 注：本环境 SubTaskAutoExecutionDispatcher 对 API_KEY_LLM agent 在 claim(ASSIGNED) 后
# 自动派发执行命令（trigger=assigned），claim 即执行；此时再调 execute 会重复触发报 500。
log "STEP6: claim sub1/sub2 in parallel (each claim auto-triggers execution, concurrent)"
for sid in "$SUB1_ID" "$SUB2_ID"; do
  CLAIM_RESP="$(http_json POST "$BASE_URL/api/sub-tasks/claim/$sid?agentId=$EXEC_AGENT_ID" "-")"
  assert_r200 "$CLAIM_RESP" "claim subTask $sid"
  log "subTask[$sid] claimed"
done

log "STEP7: wait both predecessors (sub1/sub2) to finish (concurrent execution)"
wait_exec_done "$SUB1_ID" "$EXEC_TIMEOUT_SEC"
wait_exec_done "$SUB2_ID" "$EXEC_TIMEOUT_SEC"

log "STEP9: verify both predecessors have execution output persisted"
OUT1="$(pg_sql "SELECT left(coalesce(context->'lastExecution'->>'output',''),200) FROM sub_task WHERE id=$SUB1_ID;")"
OUT2="$(pg_sql "SELECT left(coalesce(context->'lastExecution'->>'output',''),200) FROM sub_task WHERE id=$SUB2_ID;")"
[[ -n "$OUT1" ]] || fail "sub1 has no lastExecution.output"
[[ -n "$OUT2" ]] || fail "sub2 has no lastExecution.output"
log "sub1.output head: ${OUT1:0:60}"
log "sub2.output head: ${OUT2:0:60}"

log "STEP10: claim sub3 (auto-executes now that both predecessors are DONE)"
CLAIM3_RESP="$(http_json POST "$BASE_URL/api/sub-tasks/claim/$SUB3_ID?agentId=$EXEC_AGENT_ID" "-")"
assert_r200 "$CLAIM3_RESP" "claim sub3"
wait_exec_done "$SUB3_ID" "$EXEC_TIMEOUT_SEC"

log "STEP11: capture sub3 user prompt from conversation stream"
PROMPT="$(wait_user_prompt "$SUB3_ID" "$PROMPT_TIMEOUT_SEC")"
log "--- sub3 user prompt (first 800 chars) ---"
print -r -- "${PROMPT:0:800}" | tee -a "$LOG_FILE"
log "--- end ---"

# ============================================================
# 断言：双前置同时注入，不覆盖
# ============================================================
log "STEP12: assert dual-predecessor content co-exists in prompt"
print -r -- "$PROMPT" | grep -q "## 依赖产出参考（直接前置）" || fail "missing dependency section header"
print -r -- "$PROMPT" | grep -q "### 前置 1：前置一：收集竞品资料" || fail "missing predecessor 1 (sub1) block"
print -r -- "$PROMPT" | grep -q "### 前置 2：前置二：收集用户反馈" || fail "missing predecessor 2 (sub2) block"
# 内容本体：两条前置的产出都必须出现（任一被覆盖即失败）
# 注意：head -c 按字节截断会切坏 UTF-8 中文产生非法字节序列（BSD grep 报 illegal byte
# sequence），故先取首行再用 zsh 字符切片（多字节安全）
KEY1="$(print -r -- "$OUT1" | head -n 1)"
KEY1="${KEY1:0:40}"
KEY2="$(print -r -- "$OUT2" | head -n 1)"
KEY2="${KEY2:0:40}"
print -r -- "$PROMPT" | grep -qF -- "$KEY1" || fail "sub1 content NOT in prompt (overwritten?): $KEY1"
print -r -- "$PROMPT" | grep -qF -- "$KEY2" || fail "sub2 content NOT in prompt (overwritten?): $KEY2"
# 不应出现第三个前置块
print -r -- "$PROMPT" | grep -q "### 前置 3" && fail "unexpected predecessor 3 block" || true

log "STEP13: assert sub_task_spec_context_loaded timeline with dep stats"
TIMELINE="$(http_json GET "$BASE_URL/api/sub-tasks/$SUB3_ID/timeline" "-")"
SPEC_EVT="$(print -r -- "$TIMELINE" | jq -r '[.data[] | select(.eventType == "sub_task_spec_context_loaded")][0].payload // empty')"
[[ -n "$SPEC_EVT" ]] || fail "no sub_task_spec_context_loaded timeline event for sub3"
log "spec context payload: $SPEC_EVT"
print -r -- "$SPEC_EVT" | jq -e '.depCount == 2' >/dev/null 2>&1 || fail "expected depCount=2, got: $SPEC_EVT"

log "OK: dual-predecessor dependency context injection e2e passed"
log "taskId=$TASK_ID"
log "sub1Id=$SUB1_ID sub2Id=$SUB2_ID sub3Id=$SUB3_ID"
log "executorAgentId=$EXEC_AGENT_ID"
exit 0
