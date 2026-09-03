#!/usr/bin/env zsh
# ============================================================
# helloai Phase0 C3 验收脚本共享库 (macOS/Linux, zsh)
# 用途：为 verify-c3-{env,seed,reconcile,route,events}.sh 提供公共能力，
#       等价于原 PowerShell 版各脚本内联的 Assert-Pass / Invoke-Json /
#       Get-Content 窗口扫描 / psql 探针执行。
#
# 与 PowerShell 版的两处 macOS 现实校准（Code > Plan）：
#   1. DB 主机默认 localhost（application.yml 已指向 jdbc:postgresql://localhost:15432，
#      不再是 dev 服务器 39.106.204.43）；
#   2. 本机无 psql 客户端 → 探针执行走 `docker exec helloai-postgres psql`
#      （docker-compose 提供的 PG 容器），本地 psql 存在时优先直连。
#
# 约定（对齐既有 scripts/shell/verify-dashboard-duty-leases.sh）：
#   - 由入口脚本先设置 SCRIPT_DIR / PROJECT_ROOT 再 source 本文件；
#   - 不用 `set -e`：验收脚本需在断言失败后继续跑并累计 FAIL（对齐 ps1 Continue 语义）。
# Ref: .agents/skills/helloai-preflight/SKILL.md (规则 6：脚本 UTF-8 编码头)
#      doc/design/HelloAI_Phase0_C3_双轨切换预研.md (七章验收脚本表)
# ============================================================

# ------------------------------------------------------------
# UTF-8 编码强制头 (规则 6) — 避免中文乱码
# ------------------------------------------------------------
export LANG="${LANG:-zh_CN.UTF-8}"
export LC_ALL="${LC_ALL:-zh_CN.UTF-8}"

# ------------------------------------------------------------
# 配置默认值（全部可被环境变量覆盖）
# ------------------------------------------------------------
BASE_URL="${BASE_URL:-http://localhost:6565}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-15432}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-helloai}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"
PG_CONTAINER="${PG_CONTAINER:-helloai-postgres}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"

# 入口脚本未设置时的兜底（本库位于 scripts/shell/）
if [[ -z "${PROJECT_ROOT:-}" ]]; then
  PROJECT_ROOT="$(cd "$(dirname "${(%):-%N}")/../.." 2>/dev/null && pwd)"
fi
TMP_DIR="${TMP_DIR:-$PROJECT_ROOT/.tmp}"
mkdir -p "$TMP_DIR" 2>/dev/null

export PGPASSWORD="$DB_PASSWORD"

PASS_COUNT=0
FAIL_COUNT=0

# ============================================================
# 基础 helper
# ============================================================
need_cmd() {
  command -v "$1" >/dev/null 2>&1 || { print -r -- "MISSING DEPENDENCY: $1"; exit 1; }
}

# assert_pass <1|0> <scenario> <detail>
assert_pass() {
  local cond="$1" scen="$2" detail="$3"
  if [[ "$cond" == "1" ]]; then
    print -r -- "[$scen] PASS : $detail"
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    print -r -- "[$scen] FAIL : $detail"
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

summary_exit() {
  # $1 = 全 PASS 时打印的结论文案
  print -r -- "==== SUMMARY: PASS=$PASS_COUNT FAIL=$FAIL_COUNT ===="
  if [[ "$FAIL_COUNT" -gt 0 ]]; then
    print -r -- "RESULT: FAILED - 请先修复上面 FAIL 项再重跑"
    exit 1
  fi
  print -r -- "RESULT: $1"
  exit 0
}

# BSD/GNU 兼容：N 分钟前的 "YYYY-MM-DD HH:MM:SS"（零填充，可字典序比较）
date_ago_str() {
  local mins="$1"
  if date -v-1M "+%Y-%m-%d %H:%M:%S" >/dev/null 2>&1; then
    date -v-"${mins}"M "+%Y-%m-%d %H:%M:%S"        # BSD/macOS: M=minute
  else
    date -d "-${mins} min" "+%Y-%m-%d %H:%M:%S"    # GNU
  fi
}

# BSD/GNU 兼容：文件 mtime epoch 秒
file_mtime_epoch() {
  local f="$1"
  if stat -f %m "$f" >/dev/null 2>&1; then stat -f %m "$f"; else stat -c %Y "$f"; fi
}

# ============================================================
# HTTP (curl)：设置全局 HTTP_CODE / HTTP_BODY
# http_request <METHOD> <URL> <BODY> [header ...]
# ============================================================
http_request() {
  local method="$1" url="$2" body="$3"
  shift 3
  local bf code h
  bf="$(mktemp -t c3body.XXXXXX)"
  local -a ca
  ca=(-sS -X "$method" "$url" -o "$bf" -w "%{http_code}" --max-time 120 -H "Accept: application/json")
  if [[ "$method" != "GET" && "$method" != "DELETE" ]]; then
    ca+=(-H "Content-Type: application/json" --data "$body")
  fi
  for h in "$@"; do
    [[ -n "$h" ]] && ca+=(-H "$h")
  done
  if code="$(curl "${ca[@]}" 2>/dev/null)"; then :; else code="000"; fi
  HTTP_CODE="$code"
  HTTP_BODY="$(cat "$bf" 2>/dev/null)"
  rm -f "$bf"
}

# TCP 端口探测（nc，3s 超时）：test_tcp_port <host> <port> -> 1|0
test_tcp_port() {
  local host="$1" port="$2"
  nc -z -w 3 "$host" "$port" >/dev/null 2>&1 && print -r -- "1" || print -r -- "0"
}

# ============================================================
# 日志定位与窗口扫描
# ============================================================
# locate_log [显式路径] -> 打印日志路径（找不到打印空）
locate_log() {
  local explicit="${1:-}"
  if [[ -n "$explicit" && -f "$explicit" ]]; then print -r -- "$explicit"; return; fi
  local c
  for c in "$PROJECT_ROOT/logs/helloai.log" "$PROJECT_ROOT/helloai-start/logs/helloai.log"; do
    [[ -f "$c" ]] && { print -r -- "$c"; return; }
  done
  print -r -- ""
}

# extract_log_window <logfile> <window_minutes> <tail_lines>
#   仅保留带 "YYYY-MM-DD HH:MM:SS" 前缀且 >= cutoff 的行（字典序比较，跨 BSD/GNU 一致）
extract_log_window() {
  local lf="$1" mins="$2" tailn="$3" cutoff
  cutoff="$(date_ago_str "$mins")"
  tail -n "$tailn" "$lf" 2>/dev/null | awk -v cutoff="$cutoff" '
    /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9]/ {
      ts = substr($0, 1, 19)
      if (ts >= cutoff) print
    }'
}

# ============================================================
# SQL 探针执行：本地 psql 优先，其次 docker exec，均无则返回 1（走 MCP）
# run_probe_sql <sql_file> <out_file> -> 0 已执行 / 1 无通道
# ============================================================
run_probe_sql() {
  local sqlf="$1" outf="$2"
  if command -v psql >/dev/null 2>&1; then
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
      -v ON_ERROR_STOP=1 -X -t -A -F '|' -f "$sqlf" >"$outf" 2>&1
    return $?
  fi
  if command -v docker >/dev/null 2>&1 && \
     docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$PG_CONTAINER"; then
    docker exec -i "$PG_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" \
      -v ON_ERROR_STOP=1 -X -t -A -F '|' <"$sqlf" >"$outf" 2>&1
    return $?
  fi
  return 1
}

# 解析 application.yml 的 gray-percent（找不到打印 -1）
read_gray_percent() {
  local yml="$1" v
  [[ -f "$yml" ]] || { print -r -- "-1"; return; }
  v="$(awk '/^[[:space:]]*gray-percent:[[:space:]]*[0-9]+/ {gsub(/[^0-9]/,"",$2); print $2; exit}' "$yml")"
  print -r -- "${v:--1}"
}

# 定位 application.yml
locate_yml() {
  local c
  for c in "$PROJECT_ROOT/helloai-start/src/main/resources/application.yml" \
           "$PROJECT_ROOT/src/main/resources/application.yml"; do
    [[ -f "$c" ]] && { print -r -- "$c"; return; }
  done
  print -r -- ""
}
