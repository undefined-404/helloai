#!/usr/bin/env zsh
# watch-step4.sh — C3 Step4 100% 全量灰度持续稳定观察（macOS zsh 版）
# Usage: zsh watch-step4.sh [interval_minutes] [duration_hours] [gray_percent]
#   interval_minutes: 每轮抽查间隔（默认 30 分钟）
#   duration_hours  : 观察总时长（默认 24 小时，0 = 无限直到手工 Ctrl-C）
#   gray_percent    : 期望灰度档（默认 100，Step 4 全量档；透传给 env/route 探针）
# 每轮执行：verify-c3-reconcile.sh + verify-c3-events.sh（分钟级轻量）
#          每天执行一次：verify-c3-env.sh + verify-c3-route.sh（全量）
# 结果写入 logs/step4-watch-YYYYMMDD-HHMM.log；任一轮 FAIL>0 追加 !!!ANOMALY!!! 标记
# 依赖 c3-common.sh（env 校验、admin 登录、http_request、断言记录）
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8

set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTERVAL_MIN="${1:-30}"
DURATION_HOURS="${2:-24}"
WATCH_GRAY="${3:-100}"
LOG_DIR="$SCRIPT_DIR/../../logs"
mkdir -p "$LOG_DIR"

INTERVAL_SEC=$(( INTERVAL_MIN * 60 ))
END_TS=$(( DURATION_HOURS > 0 ? $(date +%s) + DURATION_HOURS * 3600 : 0 ))
ROUND=0

log() { print -r -- "$(date '+%Y-%m-%d %H:%M:%S') $*"; }

# 上一轮 reconcile 快照的 WARN 数（用于区分存量/新增噪声）
PREV_WARN=""

while :; do
  ROUND=$(( ROUND + 1 ))
  TS="$(date '+%Y%m%d-%H%M%S')"
  LOG_FILE="$LOG_DIR/step4-watch-$TS.log"
  log "===== round $ROUND start (interval=${INTERVAL_MIN}min) =====" | tee -a "$LOG_FILE"

  # 1) 轻量对账 + 事件探针（每轮）
  reconcile_out="$(zsh "$SCRIPT_DIR/verify-c3-reconcile.sh" 2>&1)"
  r_pass="$(print -r -- "$reconcile_out" | grep -oE 'SUMMARY: PASS=[0-9]+' | grep -oE '[0-9]+' | head -1)"
  r_fail="$(print -r -- "$reconcile_out" | grep -oE 'FAIL=[0-9]+' | grep -oE '[0-9]+' | head -1)"
  warn_now="$(print -r -- "$reconcile_out" | grep -oE 'WARN=[0-9]+' | grep -oE '[0-9]+' | head -1)"
  log "reconcile: PASS=${r_pass:-?} FAIL=${r_fail:-?} WARN=${warn_now:-0}${PREV_WARN:+ (prev=${PREV_WARN})}" | tee -a "$LOG_FILE"
  PREV_WARN="${warn_now:-0}"

  events_out="$(zsh "$SCRIPT_DIR/verify-c3-events.sh" 2>&1)"
  e_pass="$(print -r -- "$events_out" | grep -oE 'SUMMARY: PASS=[0-9]+' | grep -oE '[0-9]+' | head -1)"
  e_fail="$(print -r -- "$events_out" | grep -oE 'FAIL=[0-9]+' | grep -oE '[0-9]+' | head -1)"
  log "events  : PASS=${e_pass:-?} FAIL=${e_fail:-?}" | tee -a "$LOG_FILE"

  # 2) 全量核查（每天一次；首轮无条件跑）
  if (( ROUND == 1 )) || (( $(date +%H) == 0 && $(date +%M) < INTERVAL_MIN )); then
    log "---- daily full check ----" | tee -a "$LOG_FILE"
    env_out="$(EXPECTED_GRAY_PERCENT=$WATCH_GRAY zsh "$SCRIPT_DIR/verify-c3-env.sh" 2>&1 | tail -1)"
    route_out="$(GRAY_PERCENT=$WATCH_GRAY zsh "$SCRIPT_DIR/verify-c3-route.sh" 2>&1 | tail -3 | tr '\n' ' ')"
    log "env   : $env_out" | tee -a "$LOG_FILE"
    log "route : $route_out" | tee -a "$LOG_FILE"
  fi

  # 3) 异常标记
  if (( ${r_fail:-1} > 0 )) || (( ${e_fail:-1} > 0 )); then
    log "!!!ANOMALY!!! round=$ROUND reconcile_FAIL=${r_fail:-?} events_FAIL=${e_fail:-?} -- check $LOG_FILE" | tee -a "$LOG_FILE"
  else
    log "round $ROUND clean (PASS r=${r_pass:-?}/e=${e_pass:-?})" | tee -a "$LOG_FILE"
  fi

  if (( DURATION_HOURS > 0 && $(date +%s) >= END_TS )); then
    log "===== watch complete after $DURATION_HOURS hours ($ROUND rounds), last log: $LOG_FILE =====" | tee -a "$LOG_FILE"
    break
  fi
  sleep "$INTERVAL_SEC"
done