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

# 等待 broker 管理 API 就绪
until curl -sf -H "${AUTH}" "${API}/overview" >/dev/null 2>&1; do
  echo "[rabbitmq-init] waiting for management API..."
  sleep 2
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
