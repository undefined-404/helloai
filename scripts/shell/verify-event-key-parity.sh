#!/usr/bin/env bash
# ============================================================
# helloai 事件码 parity 守卫（REF-6.8 的守卫半：前后端 key 匹配）
#
# 治什么：后端把事件写进 `task_timeline.event_type`，前端用两张字典把它「人话化」——
#   `helloai-ui/src/utils/eventMeta.ts` 的 `EVENT_META`（时间线卡片的 label/desc）
#   与 `helloai-ui/src/utils/sequenceFlow.ts` 的 `LABEL`（时序图短标签）
# 两边**任一**缺登记时，界面回退**原始码**（eventMeta 回退事件名、sequenceFlow 回退
# 「去 sub_task_ 前缀 + 下划线转空格」的伪英文）⇒ 用户看到裸英文。本守卫把它们钉在一起。
# 背景：`LOG-20261010-009` / `LOG-20261010-011` 两次靠人工记忆补前端登记；
#       `LOG-20261010-013` 一次性清偿存量 46 个未登记码并立本守卫（后端 98 码 ↔ 前端 130 键对齐）。
#
# 两种模式：
#   A. 静态（**默认**，CI 用；零外部依赖）
#      码集 = 「写入点字面量 ∪ `AgentEventType` 枚举」
#      —— 字面量取**形态特征**：`"<snake_case>",` 紧邻 `AgentRole.`（这是事件写入调用的第 3+4 参数对）。
#      实测三种口径（任意方法名宽匹配 / `recordEvent(` 锚定 / 纯邻接）在本仓给出**同一码集**（82），
#      故取最简的邻接形态（宽匹配会把 payload key 如 `"arguments"` 误当事件码，曾实测踩到）。
#   B. `--db`（本地/运维用；地面真值）
#      码集 = 库内 `SELECT DISTINCT event_type FROM task_timeline`。
#      **覆盖静态模式的盲区**：常量传参（`ExecutorDoneIssuesBackfiller.TIMELINE_EVENT`）、
#      辅助方法参数（`finalizeAfterDraft(..., "task_created_from_clarify")`）、历史遗留码（`agent_offline`）。
#      Docker 或 psql 不可用时 **SKIP**（不制造假失败 —— 协作规约 §27）。
#
# 为什么用 bash 而非 PowerShell：见 `scripts/shell/verify-executor-doc-parity.sh` 同源理由
# （既有的 `verify-doc-gap-table.sh` 依赖 zsh + python3，Windows/Git Bash 下跑不了）。
# 三平台可跑：Git Bash / Linux / macOS。
#
# Ref: doc/plan/HelloAI 借鉴落地实施计划.md §9 `REF-6.8`；doc/log/2026-10.md `LOG-20261010-013`
# 用法：bash scripts/shell/verify-event-key-parity.sh [--db] [--container helloai-postgres] [--dbname helloai]
# 退出码：0 全部通过（含 SKIP）/ 1 存在失败项
# ============================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EVENT_META="$REPO_ROOT/helloai-ui/src/utils/eventMeta.ts"
SEQ_LABEL="$REPO_ROOT/helloai-ui/src/utils/sequenceFlow.ts"
ENUM="$REPO_ROOT/helloai-common/src/main/java/com/helloai/common/constant/AgentEventType.java"
SRC_DIRS=(
  "$REPO_ROOT/helloai-core/src/main"
  "$REPO_ROOT/helloai-api/src/main"
  "$REPO_ROOT/helloai-job/src/main"
)

USE_DB=0
PG_CONTAINER="helloai-postgres"
PG_DB="helloai"
while [ $# -gt 0 ]; do
  case "$1" in
    --db)        USE_DB=1; shift ;;
    --container) PG_CONTAINER="$2"; shift 2 ;;
    --dbname)    PG_DB="$2"; shift 2 ;;
    *) printf '未知参数：%s\n' "$1" >&2; exit 64 ;;
  esac
done

PASS=0
FAIL=0
ok()  { printf '  [ OK ] %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  [FAIL] %s\n' "$1"; FAIL=$((FAIL + 1)); }
info(){ printf '  [INFO] %s\n' "$1"; }

printf '== 事件码 parity 守卫（后端写入码 ↔ EVENT_META ↔ sequenceFlow.LABEL）==\n'

for f in "$EVENT_META" "$SEQ_LABEL" "$ENUM"; do
  if [ ! -f "$f" ]; then
    bad "缺少被检查文件：${f#"$REPO_ROOT"/}"
    printf '\n[verify-event-key-parity] FAIL=%s PASS=%s\n' "$FAIL" "$PASS"
    exit 1
  fi
done

# ── 提取：前端两张字典的键 ────────────────────────────────────────
meta_keys() {
  sed -n '/^export const EVENT_META/,/^}/p' "$EVENT_META" \
    | grep -oE '^  [a-z][a-z0-9_]+:' | tr -d ' :' | sort -u
}
label_keys() {
  sed -n '/^const LABEL/,/^}/p' "$SEQ_LABEL" \
    | grep -oE '^  [a-z][a-z0-9_]+:' | tr -d ' :' | sort -u
}

WORK="$(mktemp -d 2>/dev/null || printf '%s' "${TMPDIR:-/tmp}/eve-parity.$$")"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT
META_FILE="$WORK/meta_keys"
LABEL_FILE="$WORK/label_keys"
CODES_FILE="$WORK/backend_codes"
meta_keys  > "$META_FILE"
label_keys > "$LABEL_FILE"

meta_count="$(grep -c . "$META_FILE" || true)"
label_count="$(grep -c . "$LABEL_FILE" || true)"
if [ "$meta_count" -gt 0 ] && [ "$label_count" -gt 0 ]; then
  ok "前端字典解析成功（EVENT_META ${meta_count} 键 / LABEL ${label_count} 键）"
else
  bad "前端字典解析为空（EVENT_META=${meta_count} / LABEL=${label_count}）—— 断言不能静默失效，请检查字典结构"
fi

# ── 提取：后端事件码 ─────────────────────────────────────────────
{
  # A. 写入点字面量（形态：`"code",` 紧邻 `AgentRole.`）
  grep -RzohE '"[a-z][a-z0-9_]+",[[:space:]]*AgentRole\.[A-Z_]+' \
      --include=*.java "${SRC_DIRS[@]}" 2>/dev/null \
    | tr '\n' ' ' | tr '\0' '\n' \
    | sed -E 's/^"([a-z][a-z0-9_]+)".*/\1/' | grep -E '^[a-z][a-z0-9_]+$'
  # B. AgentEventType 枚举（本身即单一来源）
  grep -oE '"[a-z][a-z0-9_]+"' "$ENUM" | tr -d '"'
} | sort -u > "$CODES_FILE"
static_count="$(grep -c . "$CODES_FILE" || true)"
if [ "$static_count" -gt 0 ]; then
  ok "静态码集提取成功（字面量 ∪ 枚举 = ${static_count} 码）"
else
  bad "静态码集为空 —— 提取模式可能已与代码形态失配"
fi

if [ "$USE_DB" = "1" ]; then
  printf '\n-- 地面真值模式（--db：库内 distinct event_type）--\n'
  if ! command -v docker >/dev/null 2>&1; then
    info "SKIP 未找到 docker 命令（不制造假失败）"
  elif ! docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$PG_CONTAINER"; then
    info "SKIP 容器 ${PG_CONTAINER} 未运行（不制造假失败）"
  else
    db_codes="$(docker exec "$PG_CONTAINER" psql -U postgres -d "$PG_DB" -tAc \
        "SELECT DISTINCT event_type FROM task_timeline WHERE deleted = 0" 2>/dev/null \
      | tr -d '\r' | grep -E '^[a-z][a-z0-9_]+$' | sort -u || true)"
    db_count="$(printf '%s\n' "$db_codes" | grep -c . || true)"
    if [ "$db_count" -gt 0 ]; then
      ok "库内事件码读取成功（${db_count} 码）"
      # **并集**：地面真值补上静态模式扫不到的形态，但不得缩窄静态码集
      printf '%s\n' "$db_codes" >> "$CODES_FILE"
      sort -u "$CODES_FILE" -o "$CODES_FILE"
      ok "静态 ∪ 库内 = $(grep -c . "$CODES_FILE") 码（并集后断言）"
    else
      info "SKIP 库内未读到任何事件码（空库？）"
    fi
  fi
fi

# ── 断言：每个后端码必须在前端两张字典里都有登记 ────────────────
printf '\n-- 断言：后端码 ∈ EVENT_META 且 ∈ LABEL --\n'
missing_meta="$(comm -23 "$CODES_FILE" "$META_FILE" 2>/dev/null || true)"
missing_label="$(comm -23 "$CODES_FILE" "$LABEL_FILE" 2>/dev/null || true)"

if [ -z "$missing_meta" ]; then
  ok "EVENT_META 覆盖全部后端事件码（缺登记 ⇒ 时间线回退裸事件名）"
else
  bad "EVENT_META 缺登记 $(printf '%s\n' "$missing_meta" | grep -c .) 个：$(printf '%s ' $missing_meta)"
fi

if [ -z "$missing_label" ]; then
  ok "sequenceFlow.LABEL 覆盖全部后端事件码（缺登记 ⇒ 时序图回退伪英文）"
else
  bad "sequenceFlow.LABEL 缺登记 $(printf '%s\n' "$missing_label" | grep -c .) 个：$(printf '%s ' $missing_label)"
fi

# ── 提示（不拦截）：字典里前端有、本次码集未见（非字面量形态 / 历史码 / 已废弃） ──
extra="$(comm -13 "$CODES_FILE" "$META_FILE" | grep -c . || true)"
[ "$extra" -gt 0 ] && info "EVENT_META 中另有 ${extra} 个键不在本次码集内（非字面量形态、历史码或已废弃；--db 模式可缩小该差集）"

printf '\n== 汇总 ==\n'
if [ "$FAIL" -eq 0 ]; then
  printf '[verify-event-key-parity] 全部通过（PASS=%s）\n' "$PASS"
  exit 0
else
  printf '[verify-event-key-parity] FAIL=%s / PASS=%s\n' "$FAIL" "$PASS"
  exit 1
fi
