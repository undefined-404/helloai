#!/usr/bin/env zsh
# ============================================================
# helloai Phase0 C3 dev-env verifier (verify-c3-env.sh, macOS/Linux)
# 移植自 scripts/powershell/verify-c3-env.ps1（灰度前置 dev 环境就绪检查）。
#   S1 服务端口存活：后端 6565（TCP + /api/health）+ 中间件 PG 15432 / Redis 26379 / RabbitMQ 25672
#   S2 对账任务窗口：扫描日志最近 N 分钟（默认 12 >= B3 10min 窗口），
#      断言 0 条「事件对账不一致」WARN 与 EventReconciliationTask 执行异常 ERROR；日志新鲜
#   S3 executor Agent 在线数：admin 登录 -> /api/agents/list，
#      统计 role=EXECUTOR 且 ACTIVE 且（内部 LLM 豁免在线/心跳 或 外部在线+心跳新鲜）>= 1
#   S4 灰度配置：application.yml gray-percent == 期望值（Step 4 用 100）
# Ref: doc/design/HelloAI_Phase0_C3_双轨切换预研.md（七章验收脚本表）
#      doc/log/2026-09.md（LOG-20260902-011 预检落地 / LOG-20260903-012 Step 4 全量档）
# 用法（项目根）：
#   ./scripts/shell/verify-c3-env.sh                 # 默认期望 gray=5
#   EXPECTED_GRAY_PERCENT=100 ./scripts/shell/verify-c3-env.sh   # Step 4 全量档
#   DB_HOST=localhost LOG_FILE=... ./scripts/shell/verify-c3-env.sh
# 参数（环境变量）：EXPECTED_GRAY_PERCENT(5) RECONCILE_WINDOW_MINUTES(12) FRESH_MINUTES(5)
#                   LOG_TAIL_LINES(10000) LOG_FILE('') BASE_URL DB_HOST
# ============================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
source "$SCRIPT_DIR/c3-common.sh"

EXPECTED_GRAY_PERCENT="${EXPECTED_GRAY_PERCENT:-5}"
RECONCILE_WINDOW_MINUTES="${RECONCILE_WINDOW_MINUTES:-12}"
FRESH_MINUTES="${FRESH_MINUTES:-5}"
LOG_TAIL_LINES="${LOG_TAIL_LINES:-10000}"
LOG_FILE="${LOG_FILE:-}"

need_cmd curl
need_cmd jq
need_cmd nc
need_cmd awk

# N 分钟前的 UTC epoch（BSD/GNU 兼容）
epoch_ago() {
  local m="$1"
  if date -u -v-1M +%s >/dev/null 2>&1; then date -u -v-"${m}"M +%s; else date -u -d "-${m} min" +%s; fi
}

# ============================================================
# S1: 服务端口
# ============================================================
print -r -- "==== S1: service ports ===="

health_code="$(curl -sS -o /dev/null -w "%{http_code}" --max-time 5 "$BASE_URL/api/health" 2>/dev/null)"
[[ -n "$health_code" ]] || health_code="000"
assert_pass "$(test_tcp_port 127.0.0.1 6565)" "S1" "backend :6565 TCP listen (base=$BASE_URL)"
[[ "$health_code" == "200" ]] && assert_pass 1 "S1" "backend /api/health HTTP 200" || assert_pass 0 "S1" "backend /api/health HTTP $health_code (期望 200)"

assert_pass "$(test_tcp_port "$DB_HOST" 15432)" "S1" "middleware $DB_HOST:15432 PostgreSQL TCP"
assert_pass "$(test_tcp_port "$DB_HOST" 26379)" "S1" "middleware $DB_HOST:26379 Redis TCP"
assert_pass "$(test_tcp_port "$DB_HOST" 25672)" "S1" "middleware $DB_HOST:25672 RabbitMQ TCP"

# ============================================================
# S2: 对账窗口（日志扫描）
# ============================================================
print -r -- "==== S2: reconciliation window (no mismatch in last ${RECONCILE_WINDOW_MINUTES}min) ===="

LOG_FILE="$(locate_log "$LOG_FILE")"
if [[ -n "$LOG_FILE" ]]; then
  assert_pass 1 "S2" "log file found: $LOG_FILE"

  now_e="$(date +%s)"
  mtime_e="$(file_mtime_epoch "$LOG_FILE")"
  age_min=$(( (now_e - mtime_e) / 60 ))
  [[ "$age_min" -le 3 ]] && assert_pass 1 "S2" "log last write ${age_min}min ago (backend alive)" \
                        || assert_pass 0 "S2" "log last write ${age_min}min ago (>3min，后端可能未运行)"

  win_file="$TMP_DIR/c3-env-window.log"
  extract_log_window "$LOG_FILE" "$RECONCILE_WINDOW_MINUTES" "$LOG_TAIL_LINES" >"$win_file"
  win_lines="$(wc -l <"$win_file" | tr -d '[:space:]')"

  mismatch_n="$(grep -F "事件对账" "$win_file" 2>/dev/null | grep -Fc "不一致" 2>/dev/null)"
  taskerr_n="$(grep -F "EventReconciliationTask" "$win_file" 2>/dev/null | grep -Fc "执行异常" 2>/dev/null)"
  mismatch_n="${mismatch_n:-0}"; taskerr_n="${taskerr_n:-0}"

  [[ "$mismatch_n" == "0" ]] && assert_pass 1 "S2" "reconcile mismatch WARN in window=0" \
                            || assert_pass 0 "S2" "reconcile mismatch WARN in window=$mismatch_n"
  [[ "$taskerr_n" == "0" ]] && assert_pass 1 "S2" "EventReconciliationTask ERROR in window=0" \
                           || assert_pass 0 "S2" "EventReconciliationTask ERROR in window=$taskerr_n"
  print -r -- "S2 info: window lines=$win_lines"
  if [[ "$mismatch_n" != "0" ]]; then
    grep -F "事件对账" "$win_file" | grep -F "不一致" | head -5 | while IFS= read -r l; do print -r -- "S2 WARN sample: $l"; done
  fi
else
  assert_pass 0 "S2" "log file NOT-FOUND (设置 LOG_FILE 指定路径)"
fi

# ============================================================
# S3: executor agent 在线
# ============================================================
print -r -- "==== S3: executor agent online ===="

login_body="$(jq -cn --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" '{type:"admin",username:$u,credential:$p}')"
http_request POST "$BASE_URL/api/auth/login" "$login_body"
admin_token="$(print -r -- "$HTTP_BODY" | jq -r '.data.token // empty' 2>/dev/null)"
if [[ "$HTTP_CODE" == "200" && -n "$admin_token" ]]; then
  assert_pass 1 "S3" "admin login token=ok"
else
  assert_pass 0 "S3" "admin login FAILED (HTTP=$HTTP_CODE body=$HTTP_BODY)"
fi

if [[ -n "${admin_token:-}" ]]; then
  http_request GET "$BASE_URL/api/agents/list" "" "X-Admin-Token: $admin_token"
  list_code="$(print -r -- "$HTTP_BODY" | jq -r '.code // empty' 2>/dev/null)"
  if [[ "$HTTP_CODE" == "200" && "$list_code" == "200" ]]; then
    assert_pass 1 "S3" "agent list code=200"

    cutoff_e="$(epoch_ago "$FRESH_MINUTES")"
    # 逐 executor 明细
    print -r -- "$HTTP_BODY" | jq -r --argjson cutoff "$cutoff_e" '
      (.data // [])[] | select((.role|ascii_upcase)=="EXECUTOR")
      | "S3 agent: id=\(.id) name=\(.name) status=\(.status) online=\(.onlineStatus // "-") seen=\(.lastSeenAt // "null") innerLlm=\(((.modelType // "")|tostring|length)>0) (modelType=\(.modelType // "-"))"
    ' 2>/dev/null

    # 对齐 AgentSelector 候选口径：内部 LLM（modelType 非空）豁免在线/心跳；
    # 外部 Agent 要求 onlineStatus 非 OFFLINE/SLEEPING 且心跳新鲜（lastSeenAt 可解析为 ISO8601 UTC）
    selectable="$(print -r -- "$HTTP_BODY" | jq --argjson cutoff "$cutoff_e" '
      [ (.data // [])[]
        | select((.role|ascii_upcase)=="EXECUTOR")
        | select((.status|ascii_upcase)=="ACTIVE")
        | select(
            (((.modelType // "")|tostring|length) > 0)
            or (
              ((((.onlineStatus // "")|ascii_upcase)) as $os | ($os != "OFFLINE" and $os != "SLEEPING"))
              and ((.lastSeenAt // "") | tostring | (try (fromdateiso8601 >= $cutoff) catch false))
            )
          )
      ] | length' 2>/dev/null)"
    selectable="${selectable:-0}"
    [[ "$selectable" -ge 1 ]] \
      && assert_pass 1 "S3" "selectable executor count=$selectable (ACTIVE; LLM-exempt 或 not-OFFLINE/SLEEPING+心跳新鲜)" \
      || assert_pass 0 "S3" "selectable executor count=0（灰度门禁要求 >= 1；注册/上线一律人工）"
  else
    assert_pass 0 "S3" "agent list FAILED (HTTP=$HTTP_CODE code=${list_code:-N/A})"
  fi
fi

# ============================================================
# S4: 灰度配置 gray-percent
# ============================================================
print -r -- "==== S4: grayscale config (gray-percent) ===="

yml="$(locate_yml)"
gray_now="$(read_gray_percent "$yml")"
[[ -n "$yml" ]] && assert_pass 1 "S4" "application.yml: $yml" || assert_pass 0 "S4" "application.yml NOT-FOUND"
if [[ "$gray_now" == "$EXPECTED_GRAY_PERCENT" ]]; then
  assert_pass 1 "S4" "gray-percent=$gray_now (expect $EXPECTED_GRAY_PERCENT)"
else
  assert_pass 0 "S4" "gray-percent=$gray_now (expect $EXPECTED_GRAY_PERCENT)"
fi

# ============================================================
# S5: 汇总
# ============================================================
summary_exit "ALL PASSED - dev env ready，灰度门禁（executor 在线 >= 1 + gray=$EXPECTED_GRAY_PERCENT）满足"
