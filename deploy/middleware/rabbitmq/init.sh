#!/usr/bin/env bash
# ============================================================
# RabbitMQ 隔离初始化（rabbitmq-init 容器内执行，幂等）
# 通过 Management HTTP API 创建：
#   - vhost  /helloai
#   - user   helloai（仅 /helloai 的 configure/write/read 权限）
# 密码从环境变量注入（compose 传入），不落盘
#
# ⚠️ vhost 名 URL 编码约束 —— 请勿改回字面量（已实测）
#   Management API 里 vhost 是 URL 路径段，vhost 名中的 "/" 必须转义成 %2F：
#     PUT /api/vhosts/%2Fhelloai  → 建出名为 "/helloai" 的 vhost   ✅ 应用要的就是这个
#     PUT /api/vhosts/helloai     → 建出名为 "helloai" 的 vhost    ❌ 另一个独立实体
#   实测三个 vhost："/"、"/helloai"、"helloai" 彼此独立。
#   若写成字面 helloai，应用（spring.rabbitmq.virtual-host=/helloai）永远连不上，
#   且故障是静默的（MQ 认证失败不阻塞 Spring 启动，只刷红日志）。
#   同理 /api/permissions/{vhost}/{user} 的【第一个路径段是 vhost】，也须写 %2Fhelloai。
#
# 注：本脚本由 compose 以 `entrypoint: /bin/sh` 执行（busybox ash），
#     故只使用 POSIX sh 语法，勿引入 bash 专有特性（如 ${!var}）。
# ============================================================
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8
set -euo pipefail

# --- 失败速报守卫：参数缺失立即报错退出，绝不静默继续 ---
if [ -z "${RABBITMQ_ADMIN_USER:-}" ]; then
  echo "[rabbitmq-init] 错误：环境变量 RABBITMQ_ADMIN_USER 未设置或为空，拒绝继续（避免静默失败）。" >&2
  echo "[rabbitmq-init] 注意：该账号必须是非 guest 的专用管理员——guest 仅限回环登录，兄弟容器使用必被拒。" >&2
  exit 1
fi
if [ -z "${RABBITMQ_ADMIN_PASSWORD:-}" ]; then
  echo "[rabbitmq-init] 错误：环境变量 RABBITMQ_ADMIN_PASSWORD 未设置或为空，拒绝继续（避免静默失败）。" >&2
  exit 1
fi
if [ -z "${HELLOAI_RABBIT_PASSWORD:-}" ]; then
  echo "[rabbitmq-init] 错误：环境变量 HELLOAI_RABBIT_PASSWORD 未设置或为空，拒绝继续（避免静默失败）。" >&2
  exit 1
fi

# 业务账号名（compose 已传 HELLOAI_RABBIT_USER；缺省保持历史行为）
RABBIT_USER="${HELLOAI_RABBIT_USER:-helloai}"
# 目标 vhost 的 URL 编码形式（路径段中的 "/" 必须转义）
RABBIT_VHOST_ENC="%2Fhelloai"

API="http://rabbitmq:15672/api"
AUTH_B64=$(printf '%s:%s' "${RABBITMQ_ADMIN_USER}" "${RABBITMQ_ADMIN_PASSWORD}" | base64 | tr -d '\n')
AUTH="Authorization: Basic ${AUTH_B64}"

# 等待 broker 管理 API 就绪。
# ⚠️ 必须设最大重试上限：管理员【密码错误】时管理 API 返回 401，
#    若用不带上限的 until 循环会永远重试、容器永不退出（静默挂死）。
#    因此这里区分两种情形并显式失败：
#      - 401/403          → 凭据被拒，立即退出（重试无意义）
#      - 连接失败/超时     → 计入重试上限，超限退出
MAX_ATTEMPTS="${RABBITMQ_INIT_MAX_ATTEMPTS:-30}"   # 30 次 × 2s ≈ 60s
INTERVAL=2
attempt=0
PROBE_CODE=""
while :; do
  if PROBE_CODE=$(curl -s -o /dev/null -w '%{http_code}' -H "${AUTH}" "${API}/overview" 2>/dev/null); then
    if [ "${PROBE_CODE}" = "200" ]; then
      echo "[rabbitmq-init] management API ready (HTTP 200)"
      break
    fi
  fi
  case "${PROBE_CODE}" in
    401|403)
      echo "[rabbitmq-init] 错误：管理 API 拒绝认证（HTTP ${PROBE_CODE}）——管理员账号或密码不正确，重试无意义，直接退出。" >&2
      echo "[rabbitmq-init] 请检查 .env 的 RABBITMQ_ADMIN_USER / RABBITMQ_ADMIN_PASSWORD 是否为该 broker 上真实存在且启用（且非 guest）的管理员。" >&2
      exit 1
      ;;
  esac
  attempt=$((attempt + 1))
  if [ "${attempt}" -ge "${MAX_ATTEMPTS}" ]; then
    echo "[rabbitmq-init] 错误：等待管理 API 就绪超时（已尝试 ${MAX_ATTEMPTS} 次 / 约 $((MAX_ATTEMPTS * INTERVAL))s），最后 HTTP 码='${PROBE_CODE}'，放弃。" >&2
    case "${PROBE_CODE}" in
      ""|000)
        echo "[rabbitmq-init] 提示：无 HTTP 响应，通常为 API 尚未监听 / 网络不通 / 或本容器内缺少 curl 可执行文件。" >&2
        ;;
    esac
    exit 1
  fi
  echo "[rabbitmq-init] waiting for management API... (${attempt}/${MAX_ATTEMPTS}, last HTTP='${PROBE_CODE}')"
  sleep "${INTERVAL}"
done

echo "[rabbitmq-init] creating vhost /helloai (URL-encoded as ${RABBIT_VHOST_ENC})"
curl -sf -X PUT -H "${AUTH}" -H 'content-type: application/json' -d '{}' \
  "${API}/vhosts/${RABBIT_VHOST_ENC}"

echo "[rabbitmq-init] creating user ${RABBIT_USER}"
curl -sf -X PUT -H "${AUTH}" -H 'content-type: application/json' \
  -d "{\"password\":\"${HELLOAI_RABBIT_PASSWORD}\",\"tags\":\"management\"}" \
  "${API}/users/${RABBIT_USER}"

echo "[rabbitmq-init] setting permissions (${RABBIT_USER} -> /helloai)"
curl -sf -X PUT -H "${AUTH}" -H 'content-type: application/json' \
  -d '{"configure":".*","write":".*","read":".*"}' \
  "${API}/permissions/${RABBIT_VHOST_ENC}/${RABBIT_USER}"

echo "[rabbitmq-init] DONE"
