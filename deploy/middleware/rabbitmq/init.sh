#!/usr/bin/env bash
# ============================================================
# RabbitMQ 隔离初始化（rabbitmq-init 容器内执行，幂等）
# 通过 Management HTTP API 创建：
#   - vhost  /helloai
#   - user   helloai（仅 /helloai 的 configure/write/read 权限）
# 密码从环境变量注入（compose 传入），不落盘
# ============================================================
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8
set -euo pipefail

API="http://rabbitmq:15672/api"
AUTH_B64=$(printf '%s:%s' "${RABBITMQ_ADMIN_USER}" "${RABBITMQ_ADMIN_PASSWORD}" | base64 | tr -d '\n')
AUTH="Authorization: Basic ${AUTH_B64}"

# 等待 broker 管理 API 就绪
until curl -sf -H "${AUTH}" "${API}/overview" >/dev/null 2>&1; do
  echo "[rabbitmq-init] waiting for management API..."
  sleep 2
done

echo "[rabbitmq-init] creating vhost /helloai"
curl -sf -X PUT -H "${AUTH}" -H 'content-type: application/json' -d '{}' \
  "${API}/vhosts/helloai"

echo "[rabbitmq-init] creating user helloai"
curl -sf -X PUT -H "${AUTH}" -H 'content-type: application/json' \
  -d "{\"password\":\"${HELLOAI_RABBIT_PASSWORD}\",\"tags\":\"management\"}" \
  "${API}/users/helloai"

echo "[rabbitmq-init] setting permissions (helloai -> /helloai)"
curl -sf -X PUT -H "${AUTH}" -H 'content-type: application/json' \
  -d '{"configure":".*","write":".*","read":".*"}' \
  "${API}/permissions/helloai/helloai"

echo "[rabbitmq-init] DONE"
