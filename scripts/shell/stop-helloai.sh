#!/usr/bin/env bash
# ============================================================
# helloai 后端服务停止脚本（macOS/Linux）
# 用途：停止本地运行的 helloai-start 应用进程（IDEA 或 mvn spring-boot:run 启动均可），
#       可选附带停止 docker compose 基础设施（postgres/redis/rabbitmq/minio）
# 停止策略：
#   - 按端口 6565 找 pid（lsof），SIGTERM 优雅停止（Spring Boot graceful shutdown），
#     等待最多 15s；超时进程 SIGKILL 兜底
#   - 同时清理 mvn spring-boot:run 父进程与 HelloAIApplication java 进程残留
#   - 幂等：无进程时提示并正常退出
# Usage:
#   chmod +x ./scripts/shell/stop-helloai.sh
#   ./scripts/shell/stop-helloai.sh          # 只停应用
#   ./scripts/shell/stop-helloai.sh --all    # 应用 + docker compose down
# ============================================================

# ------------------------------------------------------------
# UTF-8 编码强制头（规则 6）—— 避免中文乱码
# ------------------------------------------------------------
export LANG="${LANG:-zh_CN.UTF-8}"
export LC_ALL="${LC_ALL:-zh_CN.UTF-8}"

PORT="${PORT:-6565}"
STOP_TIMEOUT_SEC="${STOP_TIMEOUT_SEC:-15}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

log() { printf '%s\n' "$*"; }

# wait_gone <pid> — 等待进程退出，最多 $STOP_TIMEOUT_SEC 秒；超时返回 1
wait_gone() {
  local pid="$1" waited=0
  while kill -0 "$pid" 2>/dev/null; do
    if (( waited >= STOP_TIMEOUT_SEC )); then
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 0
}

# 1) 按端口找应用进程（java，Spring Boot）
app_pids="$(lsof -ti tcp:"$PORT" 2>/dev/null || true)"
# 2) mvn spring-boot:run 父进程（可能独立存活）
mvn_pids="$(pgrep -f 'spring-boot:run' 2>/dev/null || true)"
# 3) 兜底：HelloAIApplication 主类 java 进程（未监听端口时也能命中）
java_pids="$(pgrep -f 'com.helloai.HelloAIApplication' 2>/dev/null || true)"

all_pids="$(printf '%s\n%s\n%s\n' "$app_pids" "$mvn_pids" "$java_pids" \
  | sort -u | grep -E '^[0-9]+$' || true)"

if [[ -z "$all_pids" ]]; then
  log "no helloai process found (port $PORT) — nothing to stop"
else
  log "stopping helloai processes: $all_pids"
  # 第一轮：SIGTERM 优雅停止
  for pid in $all_pids; do
    kill "$pid" 2>/dev/null || true
  done
  # 等待退出，超时 SIGKILL 兜底
  for pid in $all_pids; do
    if wait_gone "$pid"; then
      log "pid $pid stopped gracefully"
    else
      log "pid $pid still alive after ${STOP_TIMEOUT_SEC}s, force kill"
      kill -9 "$pid" 2>/dev/null || true
      sleep 1
    fi
  done
fi

# 确认端口已释放
sleep 1
if lsof -ti tcp:"$PORT" >/dev/null 2>&1; then
  log "WARNING: port $PORT still in use"
  exit 1
fi
log "port $PORT released"

# 可选：docker compose down（基础设施容器）
if [[ "${1:-}" == "--all" || "${1:-}" == "--docker" ]]; then
  cd "$REPO_ROOT"
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    log "docker compose down (infrastructure containers)"
    docker compose down
  else
    log "docker not available/running, skip docker compose down"
  fi
fi

log "done"
exit 0
