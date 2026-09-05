#!/usr/bin/env zsh
# ============================================================
# helloai Phase0 C3 Step2 reconciliation verifier (verify-c3-reconcile.sh, macOS/Linux)
# 移植自 scripts/powershell/verify-c3-reconcile.ps1。
#   S1 定位后端日志（logs/helloai.log 或 helloai-start/logs/helloai.log）
#   S2 对账窗口（默认 20min >= B3 10min + 60s 调度余量）内：
#      - 逐条「事件对账不一致: subTaskId=...」WARN = 0
#      - 汇总「事件对账发现不一致: 数量=N」WARN = 0
#      - EventReconciliationTask 执行异常 ERROR = 0
#   S3 生成 B3 对账口径复刻 SQL 探针（只读，五态投影，窗口 10min）-> .tmp/c3-reconcile-probe.sql
#      本机有 psql 或 docker(helloai-postgres) 时自动执行断言；否则提示走会话内 MCP
#   S4 汇总：日志侧 FAIL>0 才 exit 1；DB 侧无通道时由 MCP 核对后回填判定
# Ref: doc/design/HelloAI_Phase0_C3_双轨切换预研.md（六章验收标准 2/4；七章脚本表）
# 用法（项目根）：
#   ./scripts/shell/verify-c3-reconcile.sh
#   WINDOW_MINUTES=20 LOG_FILE=... ./scripts/shell/verify-c3-reconcile.sh
# 参数（环境变量）：WINDOW_MINUTES(20) LOG_TAIL_LINES(20000) LOG_FILE('') DB_HOST PG_CONTAINER
# ============================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
source "$SCRIPT_DIR/c3-common.sh"

WINDOW_MINUTES="${WINDOW_MINUTES:-20}"
LOG_TAIL_LINES="${LOG_TAIL_LINES:-20000}"
LOG_FILE="${LOG_FILE:-}"

need_cmd awk

# ============================================================
# S1: locate backend log
# ============================================================
print -r -- "==== S1: locate backend log ===="
LOG_FILE="$(locate_log "$LOG_FILE")"
if [[ -n "$LOG_FILE" ]]; then
  assert_pass 1 "S1" "log file: $LOG_FILE"
else
  assert_pass 0 "S1" "log file: NOT-FOUND，请设置 LOG_FILE"
  print -r -- "SUMMARY: PASS=$PASS_COUNT FAIL=$FAIL_COUNT"
  exit 1
fi

# ============================================================
# S2: reconcile WARN window
# ============================================================
print -r -- "==== S2: reconcile WARN window=${WINDOW_MINUTES}min ===="

now_e="$(date +%s)"; mtime_e="$(file_mtime_epoch "$LOG_FILE")"; age_min=$(( (now_e - mtime_e) / 60 ))
[[ "$age_min" -le 3 ]] && assert_pass 1 "S2" "log last write ${age_min}min ago (backend alive)" \
                      || assert_pass 0 "S2" "log last write ${age_min}min ago (>3min，后端可能未运行)"

win_file="$TMP_DIR/c3-reconcile-window.log"
extract_log_window "$LOG_FILE" "$WINDOW_MINUTES" "$LOG_TAIL_LINES" >"$win_file"
win_lines="$(wc -l <"$win_file" | tr -d '[:space:]')"

item_n="$(grep -F "事件对账" "$win_file" 2>/dev/null | grep -Fc "不一致" 2>/dev/null)"; item_n="${item_n:-0}"
sum_n="$(grep -F "事件对账" "$win_file" 2>/dev/null | grep -F "发现" 2>/dev/null | grep -Fc "不一致" 2>/dev/null)"; sum_n="${sum_n:-0}"
err_n="$(grep -F "EventReconciliationTask" "$win_file" 2>/dev/null | grep -Fc "执行异常" 2>/dev/null)"; err_n="${err_n:-0}"

[[ "$item_n" == "0" ]] && assert_pass 1 "S2" "per-item reconcile WARN in window=0" \
                      || assert_pass 0 "S2" "per-item reconcile WARN in window=$item_n"
[[ "$sum_n" == "0" ]] && assert_pass 1 "S2" "summary reconcile WARN in window=0" \
                     || assert_pass 0 "S2" "summary reconcile WARN in window=$sum_n"
[[ "$err_n" == "0" ]] && assert_pass 1 "S2" "EventReconciliationTask ERROR in window=0" \
                     || assert_pass 0 "S2" "EventReconciliationTask ERROR in window=$err_n"

print -r -- "S2 info: window lines=$win_lines"
if [[ "$item_n" != "0" ]]; then
  grep -F "事件对账" "$win_file" | grep -F "不一致" | head -5 | while IFS= read -r l; do print -r -- "S2 WARN sample: $l"; done
fi

# ============================================================
# S3: B3 replica SQL probe (read-only, 10min window, 5-state projection)
# ============================================================
print -r -- "==== S3: B3 replica SQL probe ===="
probe_file="$TMP_DIR/c3-reconcile-probe.sql"
cat >"$probe_file" <<'SQL'
-- B3 对账口径复刻（只读；窗口 10min，与 EventReconciliationServiceImpl 一致）。
-- 期望：mismatches 全为 0。>0 时逐条核对人工验收埋点（已补 REVIEW_APPROVED/REJECTED）。
WITH recent AS (
    SELECT s.id, s.status
    FROM sub_task s
    WHERE s.deleted = 0
      AND s.update_time >= now() - interval '10 minutes'
    ORDER BY s.update_time DESC
    LIMIT 500
), last_ev AS (
    SELECT r.id, r.status,
           (SELECT e.event_type FROM agent_event e
             WHERE e.sub_task_id = r.id AND e.deleted = 0
             ORDER BY e.create_time DESC, e.id DESC LIMIT 1) AS last_event
    FROM recent r
)
SELECT status,
       COUNT(*)                                   AS total,
       COUNT(*) FILTER (WHERE
           (status='ASSIGNED' AND last_event='task_assigned') OR
           (status='IN_PROGRESS' AND last_event IN ('agent_started','context_built','tool_call_started','tool_call_completed','skill_resolved','tool_resolved')) OR
           (status='REVIEW' AND last_event IN ('agent_completed','review_started')) OR
           (status='REWORK' AND last_event IN ('review_rejected','rework_started')) OR
           (status='DONE' AND last_event='review_approved')) AS matched,
       COUNT(*) FILTER (WHERE NOT (
           (status='ASSIGNED' AND last_event='task_assigned') OR
           (status='IN_PROGRESS' AND last_event IN ('agent_started','context_built','tool_call_started','tool_call_completed','skill_resolved','tool_resolved')) OR
           (status='REVIEW' AND last_event IN ('agent_completed','review_started')) OR
           (status='REWORK' AND last_event IN ('review_rejected','rework_started')) OR
           (status='DONE' AND last_event='review_approved'))) AS mismatches
FROM last_ev
WHERE status IN ('ASSIGNED','IN_PROGRESS','REVIEW','REWORK','DONE')
GROUP BY status
ORDER BY status;
SQL
[[ -f "$probe_file" ]] && assert_pass 1 "S3" "probe written: $probe_file" || assert_pass 0 "S3" "probe write failed"

probe_out="$TMP_DIR/c3-reconcile-probe.out"
rm -f "$probe_out"
if run_probe_sql "$probe_file" "$probe_out"; then
  # mismatches = 第 4 列；任何非零 mismatch 行即 FAIL
  bad_n="$(awk -F'|' 'NF>=4 && $4 ~ /^[1-9]/' "$probe_out" 2>/dev/null | wc -l | tr -d '[:space:]')"
  bad_n="${bad_n:-0}"
  [[ "$bad_n" == "0" ]] && assert_pass 1 "S3" "probe mismatches rows=0" \
                       || assert_pass 0 "S3" "probe mismatches rows=$bad_n"
  while IFS= read -r row; do [[ -n "$row" ]] && print -r -- "S3 probe row: $row"; done <"$probe_out"
else
  print -r -- "S3 NOTE: 本机无 psql 且未见 docker 容器 [$PG_CONTAINER]；请经会话内 MCP(postgres_helloai_dev) 执行 $probe_file，期望 mismatches=0"
fi

# ============================================================
# S4: summary
# ============================================================
summary_exit "RECONCILE OK - 窗口 WARN=0；DB 侧探针结论待 MCP 核对确认"
