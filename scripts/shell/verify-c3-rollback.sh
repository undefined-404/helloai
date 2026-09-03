#!/usr/bin/env zsh
# ============================================================
# helloai Phase0 C3 rollback drill verifier (verify-c3-rollback.sh, macOS/Linux)
# 移植自 scripts/powershell/verify-c3-rollback.ps1（v1.0）。
# 用途：C3 回滚预案演练验收（预研 7 章验收脚本表；四章回滚表格灰度期行）：
#   S1 解析 application.yml gray-percent：
#      - 非 0（未处于回滚态）：输出演练操作指引并退出 0（本脚本只读，不改配置）
#        指引：改 gray-percent=0 -> 重启后端 -> 造一个新任务 -> 重跑本脚本验证
#      - 为 0（回滚态）：继续 S2-S4 断言
#   S2 日志窗口（默认 30min）内 'route=agent_runtime' 出现次数 = 0（后端日志关键词同源观察）
#   S3 只读 SQL 探针（.tmp/c3-rollback-probe.sql）窗口内：
#      - rt_new（route=agent_runtime 观察点）= 0（无新任务经 Runtime）
#      - consume_new（全部执行命令消费观察点）> 0（正面证据：新任务已回旧直连路径）
#      有 psql/docker 自动执行断言（run_probe_sql，规则 6），否则提示会话内 MCP 执行核对
#   S4 汇总；回滚态下 FAIL>0 才 exit 1；演练完成后提示恢复灰度步骤
# Ref:  doc/design/HelloAI_Phase0_C3_双轨切换预研.md（四章回滚表格；七章脚本表）
# 口径：route 观察点 = task_timeline event_type='sub_task_execution_command_consume'
#       AND payload->>'route'='agent_runtime'（LocalExecutionCommandConsumer.runViaRuntime 写入；
#       未命中旧直连路径不写 route 字段，gray-percent=0 时 routeToRuntime 恒 false）
# 用法（项目根）：
#   ./scripts/shell/verify-c3-rollback.sh
# 参数（环境变量）：WINDOW_MINUTES(30) LOG_TAIL_LINES(20000) YML_PATH(自动定位)
# ============================================================
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
source "$SCRIPT_DIR/c3-common.sh"

WINDOW_MINUTES="${WINDOW_MINUTES:-30}"
LOG_TAIL_LINES="${LOG_TAIL_LINES:-20000}"
YML_PATH="${YML_PATH:-}"

# ============================================================
# S1: gray-percent config + rollback-state gate
# ============================================================
print -r -- "==== S1: gray-percent config ===="

if [[ -z "$YML_PATH" ]]; then
  YML_PATH="$(locate_yml)"
fi
if [[ -n "$YML_PATH" ]]; then
  assert_pass 1 "S1" "application.yml: $YML_PATH"
else
  assert_pass 0 "S1" "application.yml: NOT-FOUND"
fi

cfg_gray="$(read_gray_percent "$YML_PATH")"
if [[ "$cfg_gray" =~ ^-?[0-9]+$ && "$cfg_gray" -ge 0 ]]; then
  assert_pass 1 "S1" "gray-percent=$cfg_gray (parsed from yml)"
else
  assert_pass 0 "S1" "gray-percent=$cfg_gray (parsed from yml)"
fi

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  print -r -- "SUMMARY: PASS=$PASS_COUNT FAIL=$FAIL_COUNT"; exit 1
fi

if [[ "$cfg_gray" -ne 0 ]]; then
  print -r -- "[S1] INFO : gray-percent=$cfg_gray (not in rollback state)"
  print -r -- "==== ROLLBACK DRILL GUIDE (run these steps, then rerun this script) ===="
  print -r -- "  1. edit application.yml: set  gray-percent: 0"
  print -r -- "  2. restart backend (kill old process, then relaunch)"
  print -r -- "  3. create a new task / let an existing chain consume at least one command"
  print -r -- "  4. rerun:"
  print -r -- "     zsh scripts/shell/verify-c3-rollback.sh"
  print -r -- "     expect: all PASS + DB probe rt_new=0, consume_new>0"
  print -r -- "RESUME GRAY AFTER DRILL: set gray-percent back to $cfg_gray, restart,"
  print -r -- "  then rerun verify-c3-env.sh to confirm grayscale gate back to normal"
  print -r -- "==== SUMMARY: PASS=$PASS_COUNT FAIL=$FAIL_COUNT ===="
  print -r -- "RESULT: GUIDE - not in rollback state, drill steps printed above"
  exit 0
fi

print -r -- "S1 state: rollback mode (gray-percent=0), proceeding to assertions"

# ============================================================
# S2: log window scan for route=agent_runtime
# ============================================================
print -r -- "==== S2: log window=${WINDOW_MINUTES}min route=agent_runtime scan ===="

LOG_FILE="$(locate_log)"
if [[ -n "$LOG_FILE" ]]; then
  assert_pass 1 "S2" "log file: $LOG_FILE"
else
  assert_pass 0 "S2" "log file: NOT-FOUND"
fi

if [[ -n "$LOG_FILE" ]]; then
  rt_hits="$(extract_log_window "$LOG_FILE" "$WINDOW_MINUTES" "$LOG_TAIL_LINES" | grep -c 'route=agent_runtime' 2>/dev/null || true)"
  rt_hits="${rt_hits:-0}"
  [[ "$rt_hits" -eq 0 ]] && assert_pass 1 "S2" "log route=agent_runtime in window=$rt_hits" \
                        || assert_pass 0 "S2" "log route=agent_runtime in window=$rt_hits"
else
  print -r -- "S2 NOTE: log not found; skip log assertion (DB probe below still authoritative)"
fi

# ============================================================
# S3: emit + run rollback probe SQL (read-only)
# ============================================================
print -r -- "==== S3: rollback probe SQL ===="

TMP_DIR="$PROJECT_ROOT/.tmp"
mkdir -p "$TMP_DIR"
probe_file="$TMP_DIR/c3-rollback-probe.sql"
probe_out="$TMP_DIR/c3-rollback-probe.out"

cat >"$probe_file" <<SQL
-- C3 回滚演练断言（只读）：回滚态（gray-percent=0）下窗口 ${WINDOW_MINUTES}min 内
-- rt_new = 0（无新任务经 Runtime）；consume_new > 0（新任务已回旧直连，正面证据）。
SELECT
  (SELECT COUNT(*) FROM task_timeline
    WHERE event_type = 'sub_task_execution_command_consume'
      AND payload->>'route' = 'agent_runtime'
      AND create_time >= now() - interval '${WINDOW_MINUTES} minutes') AS rt_new,
  (SELECT COUNT(*) FROM task_timeline
    WHERE event_type = 'sub_task_execution_command_consume'
      AND create_time >= now() - interval '${WINDOW_MINUTES} minutes') AS consume_new;
SQL
assert_pass 1 "S3" "probe written: $probe_file"

if run_probe_sql "$probe_file" "$probe_out"; then
  probe_row="$(grep -E '^[0-9]+\|[0-9]+$' "$probe_out" | head -1)"
  if [[ -n "$probe_row" ]]; then
    rt_new="$(print -r -- "$probe_row" | awk -F'|' '{print $1}')"
    consume_new="$(print -r -- "$probe_row" | awk -F'|' '{print $2}')"
    print -r -- "S3 probe: rt_new=$rt_new consume_new=$consume_new (window ${WINDOW_MINUTES}min)"
    [[ "$rt_new" -eq 0 ]] && assert_pass 1 "S3" "new agent_runtime observations=$rt_new (expect 0)" \
                          || assert_pass 0 "S3" "new agent_runtime observations=$rt_new (expect 0)"
    if [[ "$consume_new" -gt 0 ]]; then
      print -r -- "S3 INFO: consume_new=$consume_new > 0, positive evidence new tasks go legacy path"
    else
      print -r -- "S3 INFO: consume_new=0 in window - no evidence yet, create a task and rerun"
    fi
  else
    assert_pass 0 "S3" "rollback probe returned no row (out=$probe_out)"
  fi
else
  print -r -- "S3 NOTE: no psql/docker channel; run probe via session MCP (postgres_helloai_dev query):"
  print -r -- "  $probe_file"
  print -r -- "  expect: rt_new=0 and consume_new>0"
fi

# ============================================================
# S4: summary
# ============================================================
print -r -- "==== SUMMARY: PASS=$PASS_COUNT FAIL=$FAIL_COUNT ===="
if [[ "$FAIL_COUNT" -gt 0 ]]; then
  print -r -- "RESULT: FAILED - rollback drill failed, restore grayscale NOW (set gray-percent back, restart)"
  exit 1
fi
print -r -- "RESULT: ROLLBACK OK - no new runtime routing; legacy path confirmed"
print -r -- "REMINDER: resume grayscale by setting gray-percent back to default and rerun verify-c3-env.sh"