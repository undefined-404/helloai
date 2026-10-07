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
#
# ⚠️ 本脚本【必须保持幂等】（三步均为无条件 PUT，重复执行结果相同）
#   原因：docker-compose.server.yml 里 app 依赖
#         rabbitmq-init: {condition: service_completed_successfully}
#         而一次性容器在每次 `docker compose up -d` 时都会被重建并重跑。
#   若把这里改成非幂等写法（例如先查后建、或含删除/覆盖语义的操作），
#   上述编排依赖就会变成【每次部署都执行危险操作】。
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
      echo "[rabbitmq-init] 错误：管理 API 拒绝认证（HTTP ${PROBE_CODE}）——管理员账号或密码不正确（或该账号标签/权限不足），重试无意义，直接退出。" >&2
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

echo "[rabbitmq-init] management API ready, 开始初始化隔离资源"

# ------------------------------------------------------------
# PUT 调用 + 失败诊断
#   背景：原来三步写成 `curl -sf`——`-s` 会把错误信息一并静默，且三步都没有
#         失败处理。一旦失败，日志只会停在上一行的 creating xxx 然后 Exited(1)，
#         运维无法判断是哪一步失败、为什么失败（403 权限不足？名称非法？broker 拒绝？）。
#   做法：用 -w '%{http_code}' 取实际状态码（不能带 -f，否则拿不到码），
#         并逐码分类给出可执行的中文提示。
#   另：-sS 的 -S 必须与 -s 同时给 —— 让 curl 在静默模式下仍把传输层错误打到 stderr。
#
#   ⚠️ 期望码用【空格分隔列表】逐个字面比较，不要写成 "201|204" 再展开进 case：
#      已实测 POSIX sh / busybox ash 下 `case "$c" in ${expect})` 的 ${expect}
#      不会被当作「或」模式（引号与否都不匹配），会导致成功码被判为失败。
# ------------------------------------------------------------
http_put() {
  _step="$1"      # 步骤名（用于错误定位）
  _path="$2"      # API 路径（含 %2F 编码）
  _payload="$3"   # JSON 载荷（可能为空串，如 vhost 创建的 {}）
  shift 3
  _expect="$*"    # 期望 HTTP 码，空格分隔，如 "200 201 204"

  if [ -n "${_payload}" ]; then
    if _code=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
                 -H "${AUTH}" -H 'content-type: application/json' \
                 -d "${_payload}" "${API}${_path}"); then
      _rc=0
    else
      _rc=$?
    fi
  else
    if _code=$(curl -sS -o /dev/null -w '%{http_code}' -X PUT \
                 -H "${AUTH}" "${API}${_path}"); then
      _rc=0
    else
      _rc=$?
    fi
  fi

  if [ "${_rc}" -ne 0 ]; then
    echo "[rabbitmq-init] 错误：${_step} 失败（HTTP 请求未完成，curl 退出码 ${_rc}，HTTP 码='${_code}'）。" >&2
    echo "[rabbitmq-init]   可能原因：管理 API 不可达 / 网络中断 / DNS 解析失败 / 本容器内缺少 curl。" >&2
    exit 1
  fi

  _ok=0
  for _e in ${_expect}; do
    if [ "${_code}" = "${_e}" ]; then
      _ok=1
      break
    fi
  done
  if [ "${_ok}" -eq 1 ]; then
    echo "[rabbitmq-init] ${_step} 成功（HTTP ${_code}）"
    return 0
  fi

  echo "[rabbitmq-init] 错误：${_step} 失败（HTTP ${_code}，期望 200/201/204 之一）。" >&2
  case "${_code}" in
    401)
      echo "[rabbitmq-init]   可能原因：管理员凭据被拒或权限不足（HTTP 401）——账号不存在 / 密码错误 / 该账号缺少 administrator 标签。" >&2
      echo "[rabbitmq-init]   已实测：Management API 对「认证失败」与「已认证但非 administrator」都返回 401（不返回 403），两类原因需一并排查。" >&2
      ;;
    403)
      echo "[rabbitmq-init]   可能原因：被拒绝（HTTP 403）——通常来自反向代理或安全组拦截；Management API 本身极少返回此码。" >&2
      ;;
    404)
      echo "[rabbitmq-init]   可能原因：路径不存在（HTTP 404）——目标 vhost / 用户尚未创建，或 %2F 编码被改坏。" >&2
      ;;
    400)
      echo "[rabbitmq-init]   可能原因：名称非法或载荷不合法（HTTP 400）——检查 vhost 名与 JSON 载荷。" >&2
      ;;
    000|"")
      echo "[rabbitmq-init]   可能原因：无 HTTP 响应——连接被拒 / TLS 或代理问题。" >&2
      ;;
    *)
      echo "[rabbitmq-init]   可能原因：broker 拒绝（HTTP ${_code}）——请对照 Management API 文档排查。" >&2
      ;;
  esac
  exit 1
}

echo "[rabbitmq-init] creating vhost /helloai (URL-encoded as ${RABBIT_VHOST_ENC})"
http_put "创建 vhost /helloai" "/vhosts/${RABBIT_VHOST_ENC}" '{}' 200 201 204

echo "[rabbitmq-init] creating user ${RABBIT_USER}"
http_put "创建业务用户 ${RABBIT_USER}" "/users/${RABBIT_USER}" \
  "{\"password\":\"${HELLOAI_RABBIT_PASSWORD}\",\"tags\":\"management\"}" 200 201 204

echo "[rabbitmq-init] setting permissions (${RABBIT_USER} -> /helloai)"
http_put "设置权限 ${RABBIT_USER} -> /helloai" "/permissions/${RABBIT_VHOST_ENC}/${RABBIT_USER}" \
  '{"configure":".*","write":".*","read":".*"}' 200 201 204

echo "[rabbitmq-init] 已就绪资源：vhost=/helloai  user=${RABBIT_USER}  permissions=${RABBIT_USER}->/helloai (configure/write/read=.*)"
echo "[rabbitmq-init] DONE"
