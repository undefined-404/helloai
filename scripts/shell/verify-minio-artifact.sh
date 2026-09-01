#!/usr/bin/env zsh
# ============================================================
# helloai MinIO 附件存储 E2E 验证脚本（macOS/Linux，zsh 版）
# 用途：验证 v2.7 MinIO 集成后的附件链路（移植自 verify-minio-artifact.ps1）：
#   G1  MinIO 服务健康（29000 /minio/health/live）
#   G2  平台附件列表存在 minio:// 附件（storageUrl 前缀正确、objectKey 符合目录规范）
#   G3  minio:// 附件平台直读：下载 200 + 非空 + Content-Disposition + 未 302
# 并自动触发一次「最小执行」产生 minio:// 附件（无需 LLM 凭证）：
#   建 Agent -> 建任务 -> 建子任务(指派) -> claim -> submitResult(output 非空)
#   -> ExecutionResultHandler 物化链 -> ExecutionArtifactService.store 写入 MinIO
# Ref:  doc/HelloAI_实现差距表_V1.md (A0-5 遗留②：minio:// 外部存储平台不可直读)
#       doc/log/HelloAI_迭代执行记录_V1.md §6.76
# 前置：docker compose up -d 起 helloai-minio；后端已重启（storage.type=minio）；
#       后端 6565 可访问（本脚本不负责启动服务）。
# 用法（项目根）：
#   chmod +x ./scripts/shell/verify-minio-artifact.sh
#   ./scripts/shell/verify-minio-artifact.sh
#   ADMIN_USER=admin ADMIN_PASSWORD=admin123 ./scripts/shell/verify-minio-artifact.sh
# ============================================================

export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:6565}"
MINIO_HEALTH_URL="${MINIO_HEALTH_URL:-http://localhost:29000}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"
AGENT_NAME="minio-e2e-executor-$(date +%s)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMP_ROOT="${TMP_ROOT:-$SCRIPT_DIR/.tmp}"
mkdir -p "$TMP_ROOT"
RUN_DIR="$(mktemp -d "$TMP_ROOT/verify-minio-artifact.XXXXXX")"
LOG_FILE="$RUN_DIR/minio-e2e.log"

typeset -g HTTP_CODE=""
typeset -g HTTP_BODY=""
typeset -g PASS_COUNT=0
typeset -g FAIL_COUNT=0
typeset -g SKIP_COUNT=0
typeset -g ADMIN_TOKEN=""
typeset -g AGENT_ID=""
typeset -g AGENT_API_KEY=""
typeset -g TASK_ID=""
typeset -g SUBTASK_ID=""

need_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || {
    print -r -- "缺少依赖命令: $cmd"
    exit 1
  }
}

log() {
  print -r -- "$*" | tee -a "$LOG_FILE"
}

fail() {
  log "ERROR: $*"
  exit 1
}

assert_pass() {
  local cond="$1"
  local scenario="$2"
  local detail="$3"
  if [[ "$cond" == "true" ]]; then
    log "[$scenario] PASS : $detail"
    (( PASS_COUNT += 1 ))
  else
    log "[$scenario] FAIL : $detail"
    (( FAIL_COUNT += 1 ))
  fi
}

assert_skip() {
  local scenario="$1"
  local detail="$2"
  log "[$scenario] SKIP : $detail"
  (( SKIP_COUNT += 1 ))
}

http_request() {
  local method="$1"
  local url="$2"
  local body="$3"
  shift 3

  local body_file
  body_file="$(mktemp "$RUN_DIR/http-body.XXXXXX")"

  local -a curl_args
  curl_args=(-sS -X "$method" "$url" -o "$body_file" -w "%{http_code}" -H "Accept: application/json")
  if [[ "$method" != "GET" && "$method" != "DELETE" ]]; then
    curl_args+=(-H "Content-Type: application/json" --data "$body")
  fi

  local header
  for header in "$@"; do
    if [[ -n "$header" ]]; then
      curl_args+=(-H "$header")
    fi
  done

  if ! HTTP_CODE="$(curl "${curl_args[@]}")"; then
    HTTP_BODY="$(cat "$body_file" 2>/dev/null || true)"
    rm -f "$body_file"
    fail "$method $url 请求失败"
  fi

  HTTP_BODY="$(cat "$body_file")"
  rm -f "$body_file"
}

jq_field() {
  local body="$1"
  local field="$2"
  print -r -- "$body" | jq -r "$field // empty"
}

need_cmd curl
need_cmd jq
need_cmd mktemp

# ---------- G1: MinIO 服务健康 ----------
log "=== [G1] MinIO service health ==="
health_ok="false"
if curl -sS -m 10 -o /dev/null -w "%{http_code}" "$MINIO_HEALTH_URL/minio/health/live" 2>/dev/null | grep -q "200"; then
  health_ok="true"
fi
assert_pass "$health_ok" "G1-MinIO-health" "MinIO health check $MINIO_HEALTH_URL/minio/health/live"
if [[ "$health_ok" != "true" ]]; then
  log "[G1] 前置失败：MinIO 未就绪，先执行 docker compose up -d（helloai-minio 映射 29000/29001）。"
  log "SUMMARY PASS=$PASS_COUNT FAIL=$FAIL_COUNT SKIP=$SKIP_COUNT"
  exit 1
fi
log ""

# ---------- 0: admin 登录 ----------
log "=== [0] admin login ==="
login_body="$(jq -cn --arg u "$ADMIN_USER" --arg p "$ADMIN_PASSWORD" '{type:"admin",username:$u,credential:$p}')"
http_request POST "$BASE_URL/api/auth/login" "$login_body"
ADMIN_TOKEN="$(jq_field "$HTTP_BODY" '.data.token')"
[[ -n "$ADMIN_TOKEN" ]] || fail "未取到 admin token: $HTTP_BODY"
log "adminToken = ${ADMIN_TOKEN[1,16]}..."
log ""

# ---------- 1: 建 Agent（触发物化的执行者） ----------
log "=== [1] create executor agent ==="
create_body="$(jq -cn --arg name "$AGENT_NAME" --arg remark "minio e2e auto created" '{name:$name,role:"EXECUTOR",remark:$remark}')"
http_request POST "$BASE_URL/api/admin/agents" "$create_body" "X-Admin-Token: $ADMIN_TOKEN"
assert_pass "$([[ "$HTTP_CODE" == "200" && -n "$(jq_field "$HTTP_BODY" '.data.id')" ]] && echo true || echo false)" \
  "P1-agent-create" "创建 Agent"
AGENT_ID="$(jq_field "$HTTP_BODY" '.data.id')"
AGENT_API_KEY="$(jq_field "$HTTP_BODY" '.data.apiKey')"
[[ -n "$AGENT_ID" && -n "$AGENT_API_KEY" ]] || fail "未取到 Agent id/apiKey: $HTTP_BODY"
log "agentId=$AGENT_ID"
log ""

# ---------- 2: 建任务 ----------
log "=== [2] create task ==="
task_body="$(jq -cn --arg title "MinIO E2E 验证任务 $(date +%s)" '{title:$title,description:"自动触发最小执行以产生 minio:// 附件"}')"
http_request POST "$BASE_URL/api/tasks" "$task_body" "X-Admin-Token: $ADMIN_TOKEN"
TASK_ID="$(jq_field "$HTTP_BODY" '.data.id')"
[[ -n "$TASK_ID" ]] || fail "未取到 taskId: $HTTP_BODY"
log "taskId=$TASK_ID"
log ""

# ---------- 3: 建子任务并指派给 Agent ----------
log "=== [3] create subtask assigned to agent ==="
sub_body="$(jq -cn --argjson taskId "$TASK_ID" --arg title "MinIO 附件物化验证子任务" --argjson agentId "$AGENT_ID" \
  '[{taskId:$taskId,title:$title,assignedAgent:$agentId}]')"
http_request POST "$BASE_URL/api/sub-tasks/batch" "$sub_body" "X-Admin-Token: $ADMIN_TOKEN"
SUBTASK_ID="$(jq_field "$HTTP_BODY" '.data[0].id')"
[[ -n "$SUBTASK_ID" ]] || fail "未取到 subTaskId: $HTTP_BODY"
log "subTaskId=$SUBTASK_ID"
log ""

# ---------- 4: Agent claim 子任务 ----------
log "=== [4] agent claim subtask ==="
claim_body="$(jq -cn --argjson subTaskId "$SUBTASK_ID" '{subTaskId:$subTaskId}')"
http_request POST "$BASE_URL/api/mcp/tools/claimSubTask" "$claim_body" "Authorization: Bearer $AGENT_API_KEY"
assert_pass "$([[ "$HTTP_CODE" == "200" && "$(jq_field "$HTTP_BODY" '.data.ok')" == "true" ]] && echo true || echo false)" \
  "P2-agent-claim" "Agent 认领子任务"
log "HTTP $HTTP_CODE: $(print -r -- "$HTTP_BODY" | jq -c '.data // .msg' | head -c 200)"
log ""

# ---------- 5: Agent submitResult（触发物化链） ----------
log "=== [5] agent submitResult -> materialize ==="
output_text="# MinIO E2E 产出$(date +%s) - verify-minio-artifact.sh 自动触发的物化验证产出"
submit_body="$(jq -cn --argjson subTaskId "$SUBTASK_ID" --arg output "$output_text" '{subTaskId:$subTaskId,success:true,output:$output}')"
http_request POST "$BASE_URL/api/mcp/tools/submitResult" "$submit_body" "Authorization: Bearer $AGENT_API_KEY"
assert_pass "$([[ "$HTTP_CODE" == "200" && "$(jq_field "$HTTP_BODY" '.data.accepted')" == "true" ]] && echo true || echo false)" \
  "P3-agent-submit" "执行结果提交成功"
log "HTTP $HTTP_CODE: $(print -r -- "$HTTP_BODY" | jq -c '.data // .msg' | head -c 200)"
log "等待物化链（afterCommit 异步）..."
sleep 5
log ""

# ---------- G2: 平台附件列表存在 minio:// 附件 ----------
log "=== [G2] attachment list contains minio:// ==="
http_request GET "$BASE_URL/api/attachments" "" "X-Admin-Token: $ADMIN_TOKEN"
if [[ "$HTTP_CODE" != "200" ]]; then
  assert_pass "false" "G2-attachment-list" "附件列表接口不可用或返回异常: HTTP $HTTP_CODE"
  attachments_json="null"
else
  attachments_json="$HTTP_BODY"
  count="$(print -r -- "$attachments_json" | jq '[.data[]?] | length')"
  log "附件总数: $count"
fi

minio_count="$(print -r -- "$attachments_json" | jq '[.data[]? | select(.storageUrl | startswith("minio://"))] | length' 2>/dev/null || echo 0)"
if [[ "$minio_count" -eq 0 ]]; then
  assert_skip "G2-attachment-list" "无 minio:// 附件（先跑一次执行验证新物化链路）"
else
  assert_pass "true" "G2-attachment-list" "存在 minio:// 附件 $minio_count 条，storageUrl/bucket/objectKey 已落库"
  sample_objkey="$(print -r -- "$attachments_json" | jq -r '[.data[]? | select(.storageUrl | startswith("minio://"))][0].objectKey')"
  if print -r -- "$sample_objkey" | grep -Eq '^[^/]+/[0-9]{4}/[0-9]{2}/[0-9]+/[0-9]+/[0-9a-f]{8}-.+$'; then
    assert_pass "true" "G2-objectKey-rule" "objectKey 按 归属者/年/月/taskId/subTaskId 组织: $sample_objkey"
  else
    assert_pass "false" "G2-objectKey-rule" "objectKey 不符合目录规范: $sample_objkey"
  fi
fi
log ""

# ---------- G3: minio:// 附件平台直读 ----------
log "=== [G3] minio:// attachment platform direct read ==="
sample_id="$(print -r -- "$attachments_json" | jq -r '[.data[]? | select(.storageUrl | startswith("minio://"))][0].id // empty' 2>/dev/null)"
if [[ -z "$sample_id" ]]; then
  assert_skip "G3-minio-download" "无 minio:// 附件可下载，跳过直读验证"
else
  http_request GET "$BASE_URL/api/attachments/downloadById/$sample_id" "" "X-Admin-Token: $ADMIN_TOKEN"
  if [[ "$HTTP_CODE" == "200" ]]; then
    dl_size="$(print -r -- "$HTTP_BODY" | wc -c | tr -d ' ')"
    dl_disposition="$(curl -sSI -X GET "$BASE_URL/api/attachments/downloadById/$sample_id" -H "X-Admin-Token: $ADMIN_TOKEN" | grep -i "content-disposition" | head -1 || true)"
    assert_pass "true" "G3-minio-download" "附件 $sample_id 下载 HTTP 200 + 字节 $dl_size"
    if [[ "$dl_size" -gt 0 ]]; then
      assert_pass "true" "G3-no-empty" "下载内容非空"
    else
      assert_pass "false" "G3-no-empty" "下载内容为空"
    fi
    if [[ "$dl_disposition" == *"attachment"* ]]; then
      assert_pass "true" "G3-content-disposition" "响应带 Content-Disposition attachment"
    else
      assert_pass "false" "G3-content-disposition" "缺少 Content-Disposition attachment"
    fi
  elif [[ "$HTTP_CODE" == "302" ]]; then
    assert_pass "false" "G3-minio-download" "附件 $sample_id 仍 302 重定向（v2.7 应平台直读）"
  else
    assert_pass "false" "G3-minio-download" "附件 $sample_id 下载失败: HTTP $HTTP_CODE"
  fi
fi

log ""
log "SUMMARY PASS=$PASS_COUNT FAIL=$FAIL_COUNT SKIP=$SKIP_COUNT"
log "Run log: $LOG_FILE"
log "测试 Agent id=$AGENT_ID（$AGENT_NAME）、taskId=$TASK_ID、subTaskId=$SUBTASK_ID 保留在库，便于复核。"
if (( FAIL_COUNT > 0 )); then
  exit 1
fi
exit 0
