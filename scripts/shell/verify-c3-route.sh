#!/usr/bin/env zsh
# ============================================================
# helloai Phase0 C3 gray route verifier (verify-c3-route.sh, macOS/Linux)
# 移植自 scripts/powershell/verify-c3-route.ps1（含 v1.1 的 -WindowMinutes）。
#   S1 解析 application.yml gray-percent，断言与期望值一致
#   S2 生成只读 SQL 探针（.tmp/c3-route-violation.sql / c3-route-stats.sql）：
#      - 反侧违例：route=agent_runtime 观察点的 taskId % 100 必须 < gray（路由确定性，期望 0 行）
#      - 正侧统计：Runtime 命中 task 占比 vs gray% 偏差 <= +-10%（验收标准 1，样本 >= 10 才判定）
#        all_tasks 仅统计新协议消费（consume 事件带 route 键）：旧协议执行（切换前存量，
#        无 route 键）永远不产生 runtime 消费，不应稀释分母；WINDOW_MINUTES>0 可再缩窗
#   S3 有 psql 或 docker(helloai-postgres) 时自动执行断言；否则提示走会话内 MCP
#   S4 汇总；配置/探针侧 FAIL>0 才 exit 1
# Ref: doc/design/HelloAI_Phase0_C3_双轨切换预研.md（六章验收标准 1/3；七章脚本表）
# 口径：route 观察点 = task_timeline event_type='sub_task_execution_command_consume'
#       AND payload->>'route'='agent_runtime'（LocalExecutionCommandConsumer.runViaRuntime 写入）
# 用法（项目根）：
#   ./scripts/shell/verify-c3-route.sh                       # 默认期望 gray=5
#   GRAY_PERCENT=100 ./scripts/shell/verify-c3-route.sh      # Step 4 全量档
#   GRAY_PERCENT=100 WINDOW_MINUTES=120 ./scripts/shell/verify-c3-route.sh
# 参数（环境变量）：GRAY_PERCENT(5) WINDOW_MINUTES(0=全量) YML_PATH('') DB_HOST PG_CONTAINER
# ============================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
source "$SCRIPT_DIR/c3-common.sh"

GRAY_PERCENT="${GRAY_PERCENT:-5}"
WINDOW_MINUTES="${WINDOW_MINUTES:-0}"
YML_PATH="${YML_PATH:-}"

need_cmd awk

# ============================================================
# S1: gray-percent config
# ============================================================
print -r -- "==== S1: gray-percent config ===="
[[ -n "$YML_PATH" ]] || YML_PATH="$(locate_yml)"
if [[ -n "$YML_PATH" && -f "$YML_PATH" ]]; then
  assert_pass 1 "S1" "application.yml: $YML_PATH"
else
  assert_pass 0 "S1" "application.yml: NOT-FOUND"
fi
cfg_gray="$(read_gray_percent "$YML_PATH")"
if [[ "$cfg_gray" == "$GRAY_PERCENT" ]]; then
  assert_pass 1 "S1" "gray-percent=$cfg_gray (expect $GRAY_PERCENT)"
else
  assert_pass 0 "S1" "gray-percent=$cfg_gray (expect $GRAY_PERCENT)"
fi
if [[ "$FAIL_COUNT" -gt 0 ]]; then
  print -r -- "SUMMARY: PASS=$PASS_COUNT FAIL=$FAIL_COUNT"; exit 1
fi

# ============================================================
# S2: emit route probe SQL (read-only)
# ============================================================
print -r -- "==== S2: route probe SQL ===="
viol_file="$TMP_DIR/c3-route-violation.sql"
stat_file="$TMP_DIR/c3-route-stats.sql"
win_note=""
[[ "$WINDOW_MINUTES" -gt 0 ]] && win_note="窗口 ${WINDOW_MINUTES}min："

{
  print -r -- "-- C3 灰度路由反侧违例（只读）：期望 0 行。"
  print -r -- "-- route=agent_runtime 观察点的 taskId % 100 必须 < gray-percent（路由确定性）。"
  print -r -- "SELECT DISTINCT task_id, sub_task_id, (task_id % 100)::int AS mod100"
  print -r -- "FROM task_timeline"
  print -r -- "WHERE event_type = 'sub_task_execution_command_consume'"
  print -r -- "  AND payload->>'route' = 'agent_runtime'"
  print -r -- "  AND (task_id % 100) >= ${GRAY_PERCENT};"
} >"$viol_file"

{
  print -r -- "-- C3 灰度路由统计（只读，验收标准 1）：Runtime 命中 task 占比 vs gray 偏差 <= +-10%。"
  print -r -- "-- ${win_note}样本过少（< 10 task）时不判定，先积累。"
  print -r -- "-- 口径：仅新协议消费（consume 事件带 route 键）；旧协议执行（双轨切换前存量，payload 无"
  print -r -- "--       route 键）不产生 runtime 消费、永远进不了分子，故从分母排除，避免稀释占比。"
  print -r -- "SELECT"
  print -r -- "  (SELECT COUNT(DISTINCT task_id) FROM task_timeline"
  print -r -- "    WHERE event_type = 'sub_task_execution_command_consume'"
  print -r -- "      AND payload->>'route' = 'agent_runtime'"
  [[ "$WINDOW_MINUTES" -gt 0 ]] && print -r -- "      AND create_time >= now() - interval '${WINDOW_MINUTES} minutes'"
  print -r -- "  ) AS rt_tasks,"
  print -r -- "  (SELECT COUNT(DISTINCT task_id) FROM task_timeline"
  print -r -- "    WHERE event_type = 'sub_task_execution_command_consume'"
  print -r -- "      AND payload->>'route' IS NOT NULL"
  [[ "$WINDOW_MINUTES" -gt 0 ]] && print -r -- "      AND create_time >= now() - interval '${WINDOW_MINUTES} minutes'"
  print -r -- "  ) AS all_tasks,"
  print -r -- "  ${GRAY_PERCENT} AS gray;"
} >"$stat_file"

if [[ -f "$viol_file" && -f "$stat_file" ]]; then
  assert_pass 1 "S2" "probes written: ${viol_file:t} / ${stat_file:t}"
else
  assert_pass 0 "S2" "probe write failed"
fi

# ============================================================
# S3: execute probes (psql / docker exec) or NOTE (MCP)
# ============================================================
print -r -- "==== S3: route probe execution ===="
viol_out="$TMP_DIR/c3-route-violation.out"
stat_out="$TMP_DIR/c3-route-stats.out"
rm -f "$viol_out" "$stat_out"

if run_probe_sql "$viol_file" "$viol_out"; then
  viol_rows="$(awk 'NF' "$viol_out" 2>/dev/null | wc -l | tr -d '[:space:]')"; viol_rows="${viol_rows:-0}"
  [[ "$viol_rows" == "0" ]] && assert_pass 1 "S3" "route violation rows=0" \
                           || assert_pass 0 "S3" "route violation rows=$viol_rows"
  awk 'NF' "$viol_out" | while IFS= read -r r; do print -r -- "S3 VIOLATION: $r"; done

  if run_probe_sql "$stat_file" "$stat_out"; then
    stat_row="$(awk 'NF{print; exit}' "$stat_out" 2>/dev/null)"
    if [[ -n "$stat_row" ]]; then
      rt_t="$(print -r -- "$stat_row" | awk -F'|' '{print $1}')"
      all_t="$(print -r -- "$stat_row" | awk -F'|' '{print $2}')"
      gray_v="$(print -r -- "$stat_row" | awk -F'|' '{print $3}')"
      ratio="$(awk -v rt="$rt_t" -v all="$all_t" 'BEGIN{ if(all>0) printf "%.1f", 100*rt/all; else printf "0.0" }')"
      wtxt="all"; [[ "$WINDOW_MINUTES" -gt 0 ]] && wtxt="${WINDOW_MINUTES}min"
      print -r -- "S3 stats: rt_tasks=$rt_t all_tasks=$all_t ratio=${ratio}% gray=${gray_v}% window=$wtxt"
      if [[ "$all_t" -ge 10 ]]; then
        dev="$(awk -v r="$ratio" -v g="$gray_v" 'BEGIN{d=r-g; if(d<0)d=-d; printf "%.1f", d}')"
        ok_dev="$(awk -v d="$dev" 'BEGIN{print (d<=10.0)?1:0}')"
        assert_pass "$ok_dev" "S3" "route ratio deviation=${dev}% (<= 10%)"
      else
        print -r -- "S3 INFO: sample < 10 tasks，占比偏差暂不判定（继续积累 Run）"
      fi
    else
      assert_pass 0 "S3" "stats probe returned no row"
    fi
  else
    assert_pass 0 "S3" "stats probe execution failed (see $stat_out)"
  fi
else
  print -r -- "S3 NOTE: 本机无 psql 且未见 docker 容器 [$PG_CONTAINER]；请经会话内 MCP(postgres_helloai_dev) 执行："
  print -r -- "  1) 期望 0 行 : $viol_file"
  print -r -- "  2) 核对占比 : $stat_file"
fi

# ============================================================
# S4: summary
# ============================================================
summary_exit "ROUTE OK - gray 配置已校验；DB 侧探针结论待 MCP 核对确认"
