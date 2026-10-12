#!/usr/bin/env bash
# ============================================================
# helloai 对外 MCP 工具面隔离验证（REF-8.1 B / D-2026-10-12-5）—— macOS/Linux 版
#
# 用途：证明 internal_* 工具**没有泄漏进对外 MCP tools/list**。
#
# 为什么必须走 SSE 通道（而不是 REST 别名）：
#   GET  /api/mcp/tools            → 返回**手写静态清单** McpController.TOOL_NAMES
#   POST /api/mcp/jsonrpc tools/list → 同样是**手写静态清单**
#   两者都与 spring-ai 自动配置聚合出的真实对外面**不同源**，断言它们等于没断言
#   （甚至本身就可能漂移）。唯一真实对外面 = spring-ai
#   ToolCallbackConverterAutoConfiguration 聚合全部 ToolCallbackProvider/ToolCallback Bean
#   后经 MCP SSE 暴露的集合 ⇒ 本脚本走 GET /mcp/sse + POST /mcp/messages。
#
# 断言（HARD = 失败即 exit 1）：
#   H1 tools/list 能拿到非空工具集
#   H2 没有任何工具名以 internal_ 开头（泄漏 = 红）
#   H3 工具集**恰好等于** 16 个对外工具（多一个 = 泄漏，少一个 = 误删）
#
# 对实现：scripts/powershell/verify-mcp-external-surface.ps1（Windows 侧 2026-10-12 实测通过）。
#   相对 ps1 的唯一增强：SSE 回包由「固定等待 N 秒」改为「轮询等待目标 id 出现」（默认 15s 上限），
#   只减少假失败、不改变断言口径。
#
# 前置：helloai 已在 6565 运行（项目根执行）。
#
# 凭据获取（管理员口令，优先级从高到低）：
#   1) --admin-password 传非空值
#   2) 环境变量 HELLOAI_ADMIN_PASSWORD 非空
#   3) 交互式提示输入（不回显）；非交互宿主（CI/无人值守）直接报错
#
# 用法（项目根）：
#   HELLOAI_ADMIN_PASSWORD=xx bash scripts/shell/verify-mcp-external-surface.sh
#   bash scripts/shell/verify-mcp-external-surface.sh --admin-password 'xx' --base-url http://localhost:6565
#
# Ref: LOG-20261012-006 · doc/HelloAI 实现差距表.md D-2026-10-12-5
# ============================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_ROOT="${TMP_ROOT:-$SCRIPT_DIR/.tmp}"
mkdir -p "$TMP_ROOT"
RUN_DIR="$(mktemp -d "$TMP_ROOT/verify-mcp-external-surface.XXXXXX")"

BASE_URL="${BASE_URL:-http://localhost:6565}"
ADMIN_USERNAME="${HELLOAI_ADMIN_USERNAME:-admin}"
ADMIN_PASSWORD="${HELLOAI_ADMIN_PASSWORD:-}"
# SSE 回包轮询上限（秒）：ps1 为固定 sleep，此处改为「等到目标 id 出现即走」
SSE_REPLY_TIMEOUT_SEC="${SSE_REPLY_TIMEOUT_SEC:-15}"

usage() {
  cat <<'EOF'
用法: bash scripts/shell/verify-mcp-external-surface.sh [选项]
  --base-url <url>         默认 http://localhost:6565（或环境变量 BASE_URL）
  --admin-username <name>  默认 admin
  --admin-password <pw>    默认取环境变量 HELLOAI_ADMIN_PASSWORD，缺失则交互输入
  -h, --help               显示本帮助
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url)       BASE_URL="${2:?--base-url 需要参数}"; shift 2 ;;
    --admin-username) ADMIN_USERNAME="${2:?--admin-username 需要参数}"; shift 2 ;;
    --admin-password) ADMIN_PASSWORD="${2:?--admin-password 需要参数}"; shift 2 ;;
    -h|--help)        usage; exit 0 ;;
    *) echo "未知参数: $1" >&2; usage >&2; exit 2 ;;
  esac
done

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "缺少依赖命令: $1" >&2; exit 1; }
}
need_cmd curl
need_cmd jq
need_cmd mktemp

log() { printf '%s\n' "$*"; }
fail() { printf 'ASSERT_FAIL: %s\n' "$*" >&2; exit 1; }

# 凭据获取三级优先：1) 显式参数非空 2) 环境变量非空 3) 交互输入（仅交互宿主；空输入最多重试 3 次）
resolve_admin_password() {
  [[ -n "$ADMIN_PASSWORD" ]] && return 0
  if [[ ! -t 0 ]]; then
    fail "缺少管理员口令：请设置环境变量 HELLOAI_ADMIN_PASSWORD 或传 --admin-password（非交互宿主不提示输入）"
  fi
  local i pw
  for i in 1 2 3; do
    read -r -s -p "Admin password (hidden input): " pw || true
    printf '\n' >&2
    if [[ -n "$pw" ]]; then ADMIN_PASSWORD="$pw"; return 0; fi
    log "  empty input - attempt $i of 3"
  done
  fail "缺少管理员口令：请设置环境变量 HELLOAI_ADMIN_PASSWORD 或传 --admin-password"
}
resolve_admin_password

# ------------------------------------------------------------
# HTTP 辅助：HTTP_CODE / HTTP_BODY 为全局出参
# ------------------------------------------------------------
HTTP_CODE=""
HTTP_BODY=""
HTTP_BODY_FILE="$RUN_DIR/http-body.txt"

http_request() { # method url body [header...]
  local method="$1" url="$2" body="$3"; shift 3
  local -a args=(-sS -X "$method" "$url" -o "$HTTP_BODY_FILE" -w "%{http_code}"
                 --max-time 20 -H "Accept: application/json")
  if [[ "$method" != "GET" ]]; then
    args+=(-H "Content-Type: application/json; charset=utf-8" --data "$body")
  fi
  local h
  for h in "$@"; do args+=(-H "$h"); done
  if ! HTTP_CODE="$(curl "${args[@]}")"; then
    fail "$method $url 请求失败（服务未启动？）"
  fi
  HTTP_BODY="$(cat "$HTTP_BODY_FILE" 2>/dev/null || true)"
}

# ------------------------------------------------------------
# SSE 长连接：curl -N 直写字节文件（不经管道解码，避免中文工具名/描述被破坏）
# ------------------------------------------------------------
SSE_FILE="$RUN_DIR/mcp-external-surface.sse"
SSE_ERR_FILE="$RUN_DIR/mcp-external-surface.err"
SSE_PID=""

cleanup() {
  if [[ -n "$SSE_PID" ]]; then
    kill "$SSE_PID" >/dev/null 2>&1 || true
    wait "$SSE_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

sse_size() {
  if [[ -f "$SSE_FILE" ]]; then wc -c <"$SSE_FILE" | tr -d ' '; else printf '0'; fi
}

# 从 SSE 文本里抽出所有 data: 行的 JSON 对象（逐行解析，单行坏 JSON 不影响其余行）
sse_json_objects() { # from_offset
  local from="$1" line payload
  [[ -f "$SSE_FILE" ]] || return 0
  while IFS= read -r line; do
    case "$line" in
      data:*) payload="${line#data:}"; payload="${payload# }" ;;
      *) continue ;;
    esac
    [[ -n "$payload" ]] || continue
    if printf '%s' "$payload" | jq -e . >/dev/null 2>&1; then
      printf '%s' "$payload" | jq -c .
    fi
  done < <(tail -c +$((from + 1)) "$SSE_FILE")
}

sse_wait_for_id() { # from_offset  id  (轮询到目标 JSON-RPC 回包出现为止)
  local from="$1" want="$2" i=0
  local max=$((SSE_REPLY_TIMEOUT_SEC * 3))
  while (( i < max )); do
    if sse_json_objects "$from" | jq -e --argjson want "$want" 'select(.id == $want)' >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.3
    (( i += 1 ))
  done
  return 1
}

# 发一条 JSON-RPC 消息；SSE_WAIT_ID 非空时轮询等待该 id 的回包（通知类消息置空）
send_mcp() { # body  label  [header...]
  local body="$1" label="$2"; shift 2
  local before after
  before="$(sse_size)"
  log "=== $label ==="
  http_request POST "$BASE_URL/mcp/messages?sessionId=$SID" "$body" "$@"
  log "  POST HTTP $HTTP_CODE"
  if [[ "$HTTP_CODE" != "200" ]]; then log "  POST body: $HTTP_BODY"; fi
  if [[ -n "$SSE_WAIT_ID" ]]; then
    if ! sse_wait_for_id "$before" "$SSE_WAIT_ID"; then
      log "  WARN: SSE 未在 ${SSE_REPLY_TIMEOUT_SEC}s 内出现 id=$SSE_WAIT_ID 的回包"
    fi
  else
    sleep 1
  fi
  after="$(sse_size)"
  log "  SSE new bytes $before -> $after"
}

# ------------------------------------------------------------
# 对外 MCP 面唯一权威清单 = spring-ai 聚合的全部 ToolCallback（用户裁定 2026-10-12）：
#   13 个业务 MCP 生命周期工具 + echo（平台连通性诊断，EchoMcpTool，设计对外）
#   + search_knowledge / web_search（平台内置 contributor 工具，经 McpToolConfig 并入对外 provider）。
#   注意：此清单 ≠ McpController.TOOL_NAMES（后者是 REST 直通通道的 13 个 Agent 生命周期工具，
#   不含诊断 / contributor 工具）。改动须同步 McpToolConfig（provider 全量）。
# ------------------------------------------------------------
EXPECTED_TOOLS=(
  "pullTasks" "ack" "claimSubTask" "startSubTask" "heartbeat" "uploadArtifact"
  "submitResult" "reportBlocked" "getAgentStatus" "getDepsSummary"
  "getSubTaskDetail" "checkIn" "checkOut"
  "echo" "search_knowledge" "web_search"
)

# ------------------------------------------------------------
# STEP 0: reachability
# ------------------------------------------------------------
log "STEP0: server reachability ($BASE_URL)"
http_request GET "$BASE_URL/api/health" ""
[[ "$HTTP_CODE" == "200" ]] || fail "server NOT reachable at $BASE_URL - start HelloAIApplication first (HTTP $HTTP_CODE)"
log "  HTTP $HTTP_CODE - server is up"

# ------------------------------------------------------------
# STEP 1: admin login
# ------------------------------------------------------------
log "STEP1: admin login"
login_body="$(jq -cn --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" \
  '{type:"admin",username:$u,credential:$p}')"
http_request POST "$BASE_URL/api/auth/login" "$login_body"
[[ "$HTTP_CODE" == "200" ]] || fail "login HTTP $HTTP_CODE body=$HTTP_BODY"
ADMIN_TOKEN="$(printf '%s' "$HTTP_BODY" | jq -r '.data.token // empty')"
[[ -n "$ADMIN_TOKEN" ]] || fail "admin token is empty: $HTTP_BODY"
log "  admin token acquired"

# ------------------------------------------------------------
# STEP 2: open the real external MCP surface (SSE)
# ------------------------------------------------------------
log "STEP2: open MCP SSE long connection"
: >"$SSE_FILE"
: >"$SSE_ERR_FILE"
curl -sS -N "$BASE_URL/mcp/sse" >"$SSE_FILE" 2>"$SSE_ERR_FILE" &
SSE_PID=$!

SID=""
i=0
while (( i < 40 )); do
  if [[ -s "$SSE_FILE" ]]; then
    SID="$(LC_ALL=C grep -aoE 'sessionId=[A-Za-z0-9-]+' "$SSE_FILE" | head -n 1 | cut -d= -f2)"
    [[ -n "$SID" ]] && break
  fi
  sleep 0.5
  (( i += 1 ))
done
[[ -n "$SID" ]] || fail "sessionId extraction failed (sse file: $SSE_FILE, stderr: $(cat "$SSE_ERR_FILE" 2>/dev/null || true))"
log "  sessionId=$SID"

SSE_WAIT_ID=0

# ------------------------------------------------------------
# STEP 3: MCP handshake (required before tools/list)
# ------------------------------------------------------------
log "STEP3: MCP handshake"
init_body='{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"sh-mcp-external-surface","version":"1.0.0"}}}'
send_mcp "$init_body" "initialize" "X-Admin-Token: $ADMIN_TOKEN"
SSE_WAIT_ID=""   # 通知无回包，无需等待
send_mcp '{"jsonrpc":"2.0","method":"notifications/initialized"}' "notifications/initialized" "X-Admin-Token: $ADMIN_TOKEN"

# ------------------------------------------------------------
# STEP 4: tools/list on the REAL external surface
# ------------------------------------------------------------
log "STEP4: tools/list (real external surface)"
list_offset="$(sse_size)"
http_request POST "$BASE_URL/mcp/messages?sessionId=$SID" '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' "X-Admin-Token: $ADMIN_TOKEN"
[[ "$HTTP_CODE" == "200" ]] || fail "tools/list POST HTTP $HTTP_CODE body=$HTTP_BODY"
sse_wait_for_id "$list_offset" 1 || fail "SSE 未在 ${SSE_REPLY_TIMEOUT_SEC}s 内返回 tools/list（id=1）回包"

names="$(sse_json_objects "$list_offset" | jq -r 'select(.id == 1) | .result.tools[]?.name' | sort -u)"
tool_count="$(printf '%s\n' "$names" | grep -c . || true)"
log "  tools/list returned $tool_count tools:"
printf '%s\n' "$names" | sed 's/^/    /'

# ------------------------------------------------------------
# STEP 5: assertions
# ------------------------------------------------------------
log "STEP5: assertions"
(( tool_count >= 1 )) || fail "tools/list returned an empty tool set - external MCP surface is broken"
log "  H1 OK: tools/list non-empty"

leaked="$(printf '%s\n' "$names" | grep '^internal_' || true)"
[[ -z "$leaked" ]] || fail "internal_* tools leaked into external MCP tools/list: $(printf '%s' "$leaked" | tr '\n' ',')"
log "  H2 OK: no internal_* tool in external tools/list"

extra="$(comm -13 <(printf '%s\n' "${EXPECTED_TOOLS[@]}" | sort -u) <(printf '%s\n' "$names"))"
missing="$(comm -23 <(printf '%s\n' "${EXPECTED_TOOLS[@]}" | sort -u) <(printf '%s\n' "$names"))"
if [[ -n "$extra" || -n "$missing" ]]; then
  fail "external tool set mismatch: extra=[$(printf '%s' "$extra" | tr '\n' ',')] missing=[$(printf '%s' "$missing" | tr '\n' ',')]"
fi
log "  H3 OK: external tool set == expected ${#EXPECTED_TOOLS[@]} tools"

log ""
log "OK: external MCP surface contains no internal_* tool"
log "  toolCount=$tool_count sessionId=$SID"
log "  sse log: $SSE_FILE"
exit 0
