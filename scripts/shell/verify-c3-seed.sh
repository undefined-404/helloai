#!/usr/bin/env zsh
# ============================================================
# helloai Phase0 C3 grayscale seed generator (verify-c3-seed.sh, macOS/Linux, HTTP-only)
# 移植自 scripts/powershell/verify-c3-seed.ps1。为 C3 灰度/全量观察造数：
#   每个 Run：POST /api/tasks（executorAgentIds 白名单）-> POST /api/sub-tasks（assignedAgent=内部执行者）
#             -> POST /api/sub-tasks/executeById/{id}（生成执行命令 -> 消费 -> 灰度路由）
#             -> GET /api/sub-tasks/getById/{id}（轮询直到离开 ASSIGNED/PENDING）
#   路由判定基于 taskId % 100 < gray-percent，与指派路径无关，故显式指派保证每 Run 确定性。
#
# macOS/本地库现实校准（Code > Plan）：
#   - 19 位雪花 ID 用 python3 解析（jq 用 double 会丢精度，日志已记录该坑）；
#   - EXECUTOR_AGENT_ID 默认空 -> 自动从 /api/agents/list 挑第一个「ACTIVE + 内部 LLM(modelType 非空)」EXECUTOR，
#     因本地 docker 库的 agent id 与旧 dev 服务器不同；也可显式指定 EXECUTOR_AGENT_ID 覆盖。
# Ref: doc/design/HelloAI_Phase0_C3_双轨切换预研.md（七章验收脚本表）
#      doc/log/2026-09.md（LOG-20260903-012 Step 4 全量档造数）
# 用法（项目根，后端已启动）：
#   ./scripts/shell/verify-c3-seed.sh
#   RUN_COUNT=5 ./scripts/shell/verify-c3-seed.sh
#   EXECUTOR_AGENT_ID=2093226386712219649 RUN_COUNT=5 ./scripts/shell/verify-c3-seed.sh
# 参数（环境变量）：RUN_COUNT(5) SUBS_PER_TASK(1) EXECUTOR_AGENT_ID(''=自动挑)
#                   POLL_TIMEOUT_SEC(180) POLL_INTERVAL_SEC(3) MAX_TOTAL_MINUTES(30) BASE_URL
# ============================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
source "$SCRIPT_DIR/c3-common.sh"

RUN_COUNT="${RUN_COUNT:-5}"
SUBS_PER_TASK="${SUBS_PER_TASK:-1}"
EXECUTOR_AGENT_ID="${EXECUTOR_AGENT_ID:-}"
POLL_TIMEOUT_SEC="${POLL_TIMEOUT_SEC:-180}"
POLL_INTERVAL_SEC="${POLL_INTERVAL_SEC:-3}"
MAX_TOTAL_MINUTES="${MAX_TOTAL_MINUTES:-30}"

need_cmd curl
need_cmd python3

# ------------------------------------------------------------
# JSON 解析（python3，big-int 精确）
# ------------------------------------------------------------
resp_code()        { print -r -- "$1" | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("code",""))
except Exception: print("")'; }
resp_msg()         { print -r -- "$1" | python3 -c 'import sys,json
try: print(json.load(sys.stdin).get("msg","") or "")
except Exception: print("")'; }
resp_data_id()     { print -r -- "$1" | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin).get("data") or {}; print(d.get("id","") if isinstance(d,dict) else "")
except Exception: print("")'; }
resp_data_status() { print -r -- "$1" | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin).get("data") or {}; print(d.get("status","") if isinstance(d,dict) else "")
except Exception: print("")'; }

# ------------------------------------------------------------
# HTTP 业务封装
# ------------------------------------------------------------
create_task() {
  # $1 title, $2 token -> 打印 taskId 或空
  local body
  body="$(jq -cn --arg t "$1" --argjson eid "$EXECUTOR_AGENT_ID" \
    '{title:$t,description:"C3 Step4 grayscale observation seed",agentPolicy:{executorAgentIds:[$eid]}}')"
  http_request POST "$BASE_URL/api/tasks" "$body" "X-Admin-Token: $2"
  if [[ "$HTTP_CODE" != "200" ]]; then print -r -- "[seed] FAIL create task HTTP=$HTTP_CODE body=$HTTP_BODY" >&2; print -r -- ""; return; fi
  if [[ "$(resp_code "$HTTP_BODY")" != "200" ]]; then print -r -- "[seed] FAIL create task biz: $HTTP_BODY" >&2; print -r -- ""; return; fi
  resp_data_id "$HTTP_BODY"
}

create_subtask() {
  # $1 taskId, $2 title, $3 token -> 打印 subTaskId 或空
  local body
  body="$(jq -cn --argjson tid "$1" --arg t "$2" --argjson eid "$EXECUTOR_AGENT_ID" \
    '{taskId:$tid,title:$t,description:"C3 Step4 seed sub-task",deliverable:"verification evidence note",acceptance:"evidence present",assignedAgent:$eid}')"
  http_request POST "$BASE_URL/api/sub-tasks" "$body" "X-Admin-Token: $3"
  if [[ "$HTTP_CODE" != "200" ]]; then print -r -- "[seed] FAIL create sub-task HTTP=$HTTP_CODE body=$HTTP_BODY" >&2; print -r -- ""; return; fi
  if [[ "$(resp_code "$HTTP_BODY")" != "200" ]]; then print -r -- "[seed] FAIL create sub-task biz: $HTTP_BODY" >&2; print -r -- ""; return; fi
  resp_data_id "$HTTP_BODY"
}

get_status() {
  # $1 subTaskId, $2 token
  http_request GET "$BASE_URL/api/sub-tasks/getById/$1" "" "X-Admin-Token: $2"
  [[ "$HTTP_CODE" == "200" ]] || { print -r -- ""; return; }
  [[ "$(resp_code "$HTTP_BODY")" == "200" ]] || { print -r -- ""; return; }
  resp_data_status "$HTTP_BODY"
}

# execute_by_id <subTaskId> <token>；设置全局 EXEC_OK(1/0) EXEC_CLI(1/0) EXEC_DETAIL
execute_by_id() {
  local sid="$1" tok="$2" code msg
  http_request POST "$BASE_URL/api/sub-tasks/executeById/$sid" "" "X-Admin-Token: $tok"
  code="$HTTP_CODE"; msg="$(resp_msg "$HTTP_BODY")"
  EXEC_OK=0; EXEC_CLI=0; EXEC_DETAIL="HTTP=$code msg=${msg:-$HTTP_BODY}"
  if [[ "$code" == "200" && "$(resp_code "$HTTP_BODY")" == "200" ]]; then
    EXEC_OK=1; return
  fi
  if [[ "$msg" == *"进行中"* ]]; then
    EXEC_OK=1; EXEC_DETAIL="$EXEC_DETAIL (already-in-flight)"; return
  fi
  if [[ "$msg" == *"状态不允许执行"* ]]; then
    EXEC_CLI=1; EXEC_DETAIL="$EXEC_DETAIL (CLI self-driven)"; return
  fi
}

wait_leaves_assigned() {
  # $1 subTaskId, $2 token -> 打印最终状态
  # 注意：zsh 中 status 是只读特殊变量（=$?），此处用 cur_st 避免 read-only 报错
  local sid="$1" tok="$2" deadline cur_st=""
  deadline=$(( $(date +%s) + POLL_TIMEOUT_SEC ))
  while :; do
    cur_st="$(get_status "$sid" "$tok")"
    if [[ -n "$cur_st" && "$cur_st" != "ASSIGNED" && "$cur_st" != "PENDING" ]]; then print -r -- "$cur_st"; return; fi
    [[ "$(date +%s)" -ge "$deadline" ]] && break
    sleep "$POLL_INTERVAL_SEC"
  done
  print -r -- "$cur_st"
}

# ============================================================
# S0: server health (retry 3)
# ============================================================
print -r -- "==== S0: server health ===="
health_code="000"
for try in 1 2 3; do
  health_code="$(curl -sS -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/api/health" 2>/dev/null)"
  [[ -n "$health_code" ]] || health_code="000"
  [[ "$health_code" == "200" ]] && break
  sleep 2
done
[[ "$health_code" == "200" ]] && assert_pass 1 "S0" "GET /api/health HTTP=200 (retry up to 3)" \
                             || assert_pass 0 "S0" "GET /api/health HTTP=$health_code (retry up to 3)"

# ============================================================
# S1: admin login + resolve executor
# ============================================================
print -r -- "==== S1: admin login ===="
login_body="$(jq -cn --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" '{type:"admin",username:$u,credential:$p}')"
http_request POST "$BASE_URL/api/auth/login" "$login_body"
admin_token="$(print -r -- "$HTTP_BODY" | python3 -c 'import sys,json
try: print((json.load(sys.stdin).get("data") or {}).get("token","") or "")
except Exception: print("")')"
if [[ -n "$admin_token" ]]; then assert_pass 1 "S1" "admin login token=ok"; else assert_pass 0 "S1" "admin login FAILED body=$HTTP_BODY"; fi
if [[ -z "$admin_token" ]]; then
  print -r -- "SUMMARY: PASS=$PASS_COUNT FAIL=$FAIL_COUNT"; exit 1
fi

if [[ -z "$EXECUTOR_AGENT_ID" ]]; then
  http_request GET "$BASE_URL/api/agents/list" "" "X-Admin-Token: $admin_token"
  EXECUTOR_AGENT_ID="$(print -r -- "$HTTP_BODY" | python3 -c 'import sys,json
try:
    d=json.load(sys.stdin).get("data") or []
    for a in d:
        if str(a.get("role","")).upper()=="EXECUTOR" and str(a.get("status","")).upper()=="ACTIVE" and str(a.get("modelType") or "")!="":
            print(a.get("id","")); break
except Exception:
    pass')"
  if [[ -n "$EXECUTOR_AGENT_ID" ]]; then
    print -r -- "[seed] auto-picked inner executor id=$EXECUTOR_AGENT_ID"
  else
    assert_pass 0 "S1" "no selectable inner EXECUTOR (ACTIVE + modelType 非空)；请人工注册/上线或显式指定 EXECUTOR_AGENT_ID"
    print -r -- "SUMMARY: PASS=$PASS_COUNT FAIL=$FAIL_COUNT"; exit 1
  fi
else
  print -r -- "[seed] using EXECUTOR_AGENT_ID=$EXECUTOR_AGENT_ID"
fi

# ============================================================
# S2: seed runs
# ============================================================
print -r -- "==== S2: seed runs ===="
task_count=$(( (RUN_COUNT + SUBS_PER_TASK - 1) / SUBS_PER_TASK ))
print -r -- "[seed] plan: tasks=$task_count runs=$RUN_COUNT subsPerTask=$SUBS_PER_TASK executor=$EXECUTOR_AGENT_ID maxTotalMinutes=$MAX_TOTAL_MINUTES"

typeset -a TASK_IDS SUBTASK_IDS MISSED
typeset -A DONE_MAP DIST
TASK_IDS=(); SUBTASK_IDS=(); MISSED=(); DONE_MAP=(); DIST=()
cli_driven=0
seed_index=0
seed_start="$(date +%s)"

for (( t=1; t<=task_count; t++ )); do
  elapsed_min=$(( ( $(date +%s) - seed_start ) / 60 ))
  if [[ "$elapsed_min" -ge "$MAX_TOTAL_MINUTES" ]]; then
    print -r -- "[seed] WARN: total runtime >= ${MAX_TOTAL_MINUTES}min, guard break (防止后台自循环)"
    break
  fi
  task_title="c3-gs-$(date +%Y%m%d-%H%M%S)-t$t"
  task_id="$(create_task "$task_title" "$admin_token")"
  if [[ -n "$task_id" ]]; then assert_pass 1 "S2-t$t" "create task $task_title -> id=$task_id"; else assert_pass 0 "S2-t$t" "create task $task_title -> N/A"; continue; fi
  TASK_IDS+=("$task_id")

  subs_this=$(( SUBS_PER_TASK < (RUN_COUNT - seed_index) ? SUBS_PER_TASK : (RUN_COUNT - seed_index) ))
  for (( s=1; s<=subs_this; s++ )); do
    seed_index=$(( seed_index + 1 ))
    sub_title="c3gs-r$seed_index"
    sub_id="$(create_subtask "$task_id" "$sub_title" "$admin_token")"
    if [[ -z "$sub_id" ]]; then
      assert_pass 0 "S2-r$seed_index" "create sub-task $sub_title failed"
      MISSED+=("$sub_title"); continue
    fi
    SUBTASK_IDS+=("$sub_id")

    execute_by_id "$sub_id" "$admin_token"
    if [[ "$EXEC_OK" != "1" ]]; then
      if [[ "$EXEC_CLI" == "1" ]]; then
        cli_driven=$(( cli_driven + 1 ))
        assert_pass 1 "S2-r$seed_index" "CLI self-driven (no command): $sub_id $EXEC_DETAIL"
        final="$(wait_leaves_assigned "$sub_id" "$admin_token")"
        if [[ -n "$final" ]]; then DONE_MAP[$sub_id]="${final}_CLI_DIRECT"; else MISSED+=("$sub_id"); fi
      else
        assert_pass 0 "S2-r$seed_index" "executeById $sub_id $EXEC_DETAIL"
        MISSED+=("$sub_id")
      fi
      [[ "$seed_index" -ge "$RUN_COUNT" ]] && break
      continue
    fi

    final="$(wait_leaves_assigned "$sub_id" "$admin_token")"
    if [[ -z "$final" || "$final" == "ASSIGNED" || "$final" == "PENDING" ]]; then
      assert_pass 0 "S2-r$seed_index" "sub-task $sub_id stuck at ${final:-no-status} (exec $EXEC_DETAIL)"
      MISSED+=("$sub_id")
    else
      assert_pass 1 "S2-r$seed_index" "sub-task $sub_id task=$task_id exec-ok -> $final"
      DONE_MAP[$sub_id]="$final"
    fi
    [[ "$seed_index" -ge "$RUN_COUNT" ]] && break
  done
  [[ "$seed_index" -ge "$RUN_COUNT" ]] && break
done

# ============================================================
# S3: summary
# ============================================================
print -r -- "==== S3: summary ===="
got="${#DONE_MAP}"
[[ "$got" -eq "$RUN_COUNT" ]] \
  && assert_pass 1 "S3" "runs executed=$got/$RUN_COUNT (command-chain=$(( got - cli_driven )) cli-self-driven=$cli_driven)" \
  || assert_pass 0 "S3" "runs executed=$got/$RUN_COUNT (command-chain=$(( got - cli_driven )) cli-self-driven=$cli_driven)"

if [[ "${#MISSED}" -gt 0 ]]; then
  print -r -- "[seed] missed/stuck: ${(j:,:)MISSED}"
fi
for k in "${(@k)DONE_MAP}"; do
  v="${DONE_MAP[$k]}"
  DIST[$v]=$(( ${DIST[$v]:-0} + 1 ))
done
for k in "${(@ko)DIST}"; do
  print -r -- "[seed] status distribution: $k=${DIST[$k]}"
done
print -r -- "[seed] taskIds (route key = taskId % 100 < gray-percent): ${(j:,:)TASK_IDS}"

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  print -r -- "SUMMARY: PASS=$PASS_COUNT FAIL=$FAIL_COUNT"
  print -r -- "RESULT: SEED PARTIAL - 检查上面 missed 列表（执行者在线？CLI daemon 存活？）"
  exit 1
fi
print -r -- "SUMMARY: PASS=$PASS_COUNT FAIL=$FAIL_COUNT"
print -r -- "RESULT: SEED COMPLETE - runs 进入观察窗口；路由占比经 verify-c3-route.sh / timeline route=agent_runtime 核对"
exit 0
