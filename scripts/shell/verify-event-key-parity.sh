#!/usr/bin/env bash
# ============================================================
# helloai 事件码 parity 守卫（REF-6.8 收口：事件码服务端单一来源）
#
# 治什么：事件码（task_timeline.event_type 的展示码）经后端 `TimelineEventCode` 枚举
#   作为**唯一事实源**，前端经 `/api/events/catalog` 拉取代 EVENT_META / sequenceFlow.LABEL
#   硬编码副本。本守卫保证：
#   ① 后端**显示通道写点**不得残留裸字面量（写点替换后统一为 `TimelineEventCode.<CONST>.code()`），
#      且引用的每个常量必须真实存在于枚举 —— 防「写回字面量绕过单源」与「枚举改名留悬空引用」；
#   ② 前端**不再残留** `EVENT_META` / `sequenceFlow.LABEL` 硬编码字典（单一来源不可被
#      一个人偷偷把它复制回前端而漂移）。
# 背景：`LOG-20261010-013`（旧守卫，对账 EVENT_META/LABEL 两份副本）；REF-6.8 收口后前端副本删除；
#       `LOG-20261011-006` 收口「服务端单一来源」；写点替换（`LOG-20261011-008`）后守卫由
#       「写点字面量 ⊆ enum」转为「写点无字面量残留 + 枚举引用存在 + 库内 ⊆ enum」。
#
# 两种模式：
#   A. 静态（默认；CI 用；零外部依赖）
#      断言①写点无字面量残留 + 断言②写点枚举引用常量存在（防形态失配静默失效）
#   B. `--db`（本地/运维用；地面真值）
#      断言③码集 = 库内 `SELECT DISTINCT event_type FROM task_timeline` ⊆ enum
#      （覆盖常量传参 / 历史遗留码等静态盲区）。
#
# 为什么用 bash 而非 PowerShell：见 `verify-executor-doc-parity.sh` 同源理由。
# 三平台可跑：Git Bash / Linux / macOS。
#
# Ref: doc/plan/HelloAI 借鉴落地实施计划.md §9 `REF-6.8`；doc/log/2026-10.md `LOG-20261011-006`
# 用法：bash scripts/shell/verify-event-key-parity.sh [--db] [--container helloai-postgres] [--dbname helloai]
# 退出码：0 全部通过（含 SKIP）/ 1 存在失败项
# ============================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TIMELINE_ENUM="$REPO_ROOT/helloai-common/src/main/java/com/helloai/common/constant/TimelineEventCode.java"
EVENT_META="$REPO_ROOT/helloai-ui/src/utils/eventMeta.ts"
SEQ_FLOW="$REPO_ROOT/helloai-ui/src/utils/sequenceFlow.ts"
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

printf '== 事件码 parity 守卫（服务端单一来源：TimelineEventCode 枚举）==\n'

# 文件必须存在
for f in "$TIMELINE_ENUM"; do
  if [ ! -f "$f" ]; then
    bad "缺少事件码单一来源：${f#"$REPO_ROOT"/}——本守卫的断言前提是 TimelineEventCode 枚举已就位"
    printf '\n[verify-event-key-parity] FAIL=%s PASS=%s\n' "$FAIL" "$PASS"
    exit 1
  fi
done

WORK="$(mktemp -d 2>/dev/null || printf '%s' "${TMPDIR:-/tmp}/eve-parity.$$")"
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT
CODES_FILE="$WORK/all_codes"
ENUM_FILE="$WORK/enum_codes"

# ── 单一来源：TimelineEventCode 枚举码集 ─────────────────────────
grep -oE '^    [A-Z_]+\(("[a-z][a-z0-9_]+")' "$TIMELINE_ENUM" \
  | grep -oE '"[a-z][a-z0-9_]+"' | tr -d '"' | sort -u > "$ENUM_FILE"
enum_count="$(grep -c . "$ENUM_FILE" || true)"
if [ "$enum_count" -gt 0 ]; then
  ok "单一来源码集解析成功（TimelineEventCode = ${enum_count} 码）"
else
  bad "TimelineEventCode 枚举码集为空 —— 提取形态可能与枚举结构失配，断言不能静默失效"
fi

# ── 前端不得残留硬编码字典（单一来源防回流）───────────────────────
# EVENT_META 与 sequenceFlow.LABEL 已被 REF-6.8 移除，改 /api/events/catalog 拉取。
# 若有人重新把字典 hardcode 回前端，本断言立即红（漂移防护）。
leftover_eventmeta="$(grep -cE 'export const EVENT_META' "$EVENT_META" 2>/dev/null || true)"
leftover_label="$(grep -cE '^const LABEL: Record' "$SEQ_FLOW" 2>/dev/null || true)"
if [ "$leftover_eventmeta" -eq 0 ] && [ "$leftover_label" -eq 0 ]; then
  ok "前端无 EVENT_META / sequenceFlow.LABEL 硬编码残留（目录已改 /api/events/catalog 拉取）"
else
  bad "前端检测到硬编码事件字典残留（EVENT_META=${leftover_eventmeta} / LABEL=${leftover_label}）——单一来源被破坏，请删除前端副本"
fi

# ── 显示通道写点的两种形态 ────────────────────────────────────────
# 写点替换（REF-6.8 收口遗留项）后，展示码写点统一为 `TimelineEventCode.<CONST>.code()`：
#   ① 裸字面量写点（旧邻接形态 `"<code>", AgentRole.X`）应**恒为 0** —— 若有人把字面量
#      写回，枚举单一来源即被绕过（枚举外的新码照样落库 ⇒ 前端查目录缺 label 回退裸英文）；
#   ② 引用的每个 `<CONST>` 必须真实存在于枚举 —— 防枚举改名后留下悬空引用。
LITERAL_FILE="$WORK/literal_write_points"
REF_FILE="$WORK/ref_consts"
ENUM_NAMES="$WORK/enum_names"

# 枚举常量名集合（与码集分开：码是小写 snake，常量名是大写下划线）
grep -oE '^    [A-Z][A-Z0-9_]*\(' "$TIMELINE_ENUM" | tr -d ' (' | sort -u > "$ENUM_NAMES"

{
  grep -RzohE '"[a-z][a-z0-9_]+",[[:space:]]*AgentRole\.[A-Z_]+' \
      --include=*.java "${SRC_DIRS[@]}" 2>/dev/null \
    | tr '\n' ' ' | tr '\0' '\n' \
    | sed -E 's/^"([a-z][a-z0-9_]+)".*/\1/' | grep -E '^[a-z][a-z0-9_]+$'
} | sort -u > "$LITERAL_FILE"
literal_count="$(grep -c . "$LITERAL_FILE" || true)"

grep -RohE 'TimelineEventCode\.[A-Z][A-Z0-9_]*\.code\(\)' \
    --include=*.java "${SRC_DIRS[@]}" 2>/dev/null \
  | sed -E 's/^TimelineEventCode\.([A-Z][A-Z0-9_]*)\.code\(\)$/\1/' | sort -u > "$REF_FILE"
ref_count="$(grep -c . "$REF_FILE" || true)"

# ── 采集库内真值（--db）到 CODES_FILE ─────────────────────────────
: > "$CODES_FILE"
if [ "$USE_DB" = "1" ]; then
  printf '\n-- 地面真值模式（--db：库内 distinct event_type）--\n'
  if ! command -v docker >/dev/null 2>&1; then
    info "SKIP 未找到 docker 命令（不制造假失败）"
  elif [ "$(docker ps --format '{{.Names}}' 2>/dev/null | grep -cx "$PG_CONTAINER")" -eq 0 ]; then
    info "SKIP 容器 ${PG_CONTAINER} 未运行（不制造假失败）"
  else
    db_codes="$(docker exec "$PG_CONTAINER" psql -U postgres -d "$PG_DB" -tAc \
        "SELECT DISTINCT event_type FROM task_timeline WHERE deleted = 0" 2>/dev/null \
      | tr -d '\r' | grep -E '^[a-z][a-z0-9_]+$' | sort -u || true)"
    db_count="$(printf '%s\n' "$db_codes" | grep -c . || true)"
    if [ "$db_count" -gt 0 ]; then
      ok "库内事件码读取成功（${db_count} 码）"
      printf '%s\n' "$db_codes" > "$CODES_FILE"
      sort -u "$CODES_FILE" -o "$CODES_FILE"
    else
      info "SKIP 库内未读到任何事件码（空库？）"
    fi
  fi
fi

# ── 断言 ①：显示通道写点无字面量残留 ───────────────────────────────
printf '\n-- 断言 ①：显示通道写点无字面量残留 --\n'
if [ "$literal_count" -eq 0 ]; then
  ok "写点无裸字面量（均走 TimelineEventCode.<CONST>.code()；枚举引用 ${ref_count} 个常量）"
else
  bad "写点仍有 ${literal_count} 个裸字面量码未枚举化（应为 0，须改 TimelineEventCode.<X>.code()）：$(tr '\n' ' ' < "$LITERAL_FILE")"
fi

# ── 断言 ②：写点枚举引用常量 ⊆ 枚举常量集 ─────────────────────────
printf '\n-- 断言 ②：写点枚举引用 ⊆ TimelineEventCode 常量集 --\n'
if [ "$ref_count" -eq 0 ]; then
  bad "未提取到任何 TimelineEventCode.<CONST>.code() 写点 —— 提取形态与代码失配，断言不能静默失效"
else
  dangling="$(comm -23 "$REF_FILE" "$ENUM_NAMES" || true)"
  if [ -z "$dangling" ]; then
    ok "写点引用的 ${ref_count} 个枚举常量全部存在（无改名悬空）"
  else
    bad "写点引用了不存在的枚举常量（改名残留 / 悬空引用）：$(printf '%s ' $dangling)"
  fi
fi

# ── 断言 ③：库内真值码 ⊆ 单一来源枚举 ─────────────────────────────
printf '\n-- 断言 ③：库内真值码 ⊆ TimelineEventCode 枚举 --\n'
db_total="$(grep -c . "$CODES_FILE" || true)"
missing="$(comm -23 "$CODES_FILE" "$ENUM_FILE" 2>/dev/null || true)"
if [ -z "$missing" ]; then
  ok "库内码全部有枚举条目（库内 ${db_total} 码 ⊆ ${enum_count}，缺则前端无 label 回退裸英文）"
else
  bad "以下库内码无 TimelineEventCode 条目（前端查目录会缺 label 回退裸英文）：$(printf '%s ' $missing)"
fi

# ── 提示（不拦截）：枚举里有、本次码集未见（历史/废弃/非字面量形态）──
extra="$(comm -13 "$CODES_FILE" "$ENUM_FILE" | grep -c . || true)"
[ "$extra" -gt 0 ] && info "枚举中另有 ${extra} 个码不在本次码集内（历史/废弃/非字面量形态；--db 可缩小差集）"

printf '\n== 汇总 ==\n'
if [ "$FAIL" -eq 0 ]; then
  printf '[verify-event-key-parity] 全部通过（PASS=%s）\n' "$PASS"
  exit 0
else
  printf '[verify-event-key-parity] FAIL=%s / PASS=%s\n' "$FAIL" "$PASS"
  exit 1
fi