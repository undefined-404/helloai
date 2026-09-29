#!/usr/bin/env bash
# ============================================================================
# HelloAI 架构漂移冻结守卫 —— 跨域反向依赖「只降不升」
#
# 为什么要有这个脚本（2026-09-29 架构审计 Q4）：
#   core 域间存在成对的反向依赖：agent→task 68 处、planner→agent 34 处、task→agent 45 处。
#   前者与第三者构成 agent ⇄ task 双向环。项目当前**无 ArchUnit 等编译期约束**，
#   仅靠《HelloAI_CODE_STYLE》§6.1 的**文档豁免**与 scripts/powershell/verify-dependency-direction.ps1
#   把关，而该 ps1：① 本机无 pwsh 无法运行；② 其白名单**不禁止 agent→task**。
#   即：架构红线当前实际上是「靠自觉」，回归无自动化拦截。
#
# 本脚本的定位：
#   以**零新增依赖**的方式提供可执行的「冻结基线」守卫 —— 计数超出基线即失败，
#   使任何新增反向依赖必须显式走评审并更新基线，而不是静默漂移。
#   它与 verify-dependency-direction.ps1 互补（后者管命名/路径红线，本脚本管域间耦合增量）。
#
# 用法：
#   bash scripts/ci/check-arch-freeze.sh                 # 校验（超出基线 -> 退出码 1）
#   bash scripts/ci/check-arch-freeze.sh --update-baseline   # 用当前计数刷新基线
#   bash scripts/ci/check-arch-freeze.sh --verbose       # 打印每条规则的文件明细 Top5
# ============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASELINE="$SCRIPT_DIR/arch-baseline.txt"
cd "$ROOT" || exit 1

UPDATE=0
VERBOSE=0
for arg in "$@"; do
  case "$arg" in
    --update-baseline) UPDATE=1 ;;
    --verbose|-v)      VERBOSE=1 ;;
    -h|--help)         sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf '[arch-freeze] 未知参数：%s\n' "$arg" >&2; exit 64 ;;
  esac
done

# 规则表：规则名|被扫描目录|被禁止的 import 前缀
# 说明：这三条是 2026-09-29 审计实测确认的反向/成环依赖对。
RULES=(
  "agent->task|helloai-core/src/main/java/com/helloai/core/agent|com.helloai.core.task"
  "planner->agent|helloai-core/src/main/java/com/helloai/core/planner|com.helloai.core.agent"
  "task->agent|helloai-core/src/main/java/com/helloai/core/task|com.helloai.core.agent"
)

count_rule() {
  local dir="$1" prefix="$2"
  [ -d "$dir" ] || { printf '0'; return; }
  grep -rE "^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?${prefix//./\\.}([.;]|$)" "$dir" \
       --include=*.java 2>/dev/null | wc -l | tr -d ' '
}

baseline_get() {
  local key="$1"
  [ -f "$BASELINE" ] || { printf '%s' "-1"; return; }
  local v
  v="$(grep -E "^${key}=" "$BASELINE" 2>/dev/null | tail -1 | cut -d= -f2 | tr -d '[:space:]')"
  [ -n "$v" ] || v="-1"
  printf '%s' "$v"
}

printf '[arch-freeze] 仓库根：%s\n' "$ROOT"
printf '[arch-freeze] 冻结基线：%s\n\n' "$BASELINE"
printf '%-16s %10s %10s   %s\n' '规则' '冻结基线' '当前计数' '判定'
printf '%s\n' '-------------------------------------------------------------------'

EXCEEDED=0
IMPROVED=0
NEW_LINES=()

for rule in "${RULES[@]}"; do
  IFS='|' read -r name dir prefix <<< "$rule"
  cur="$(count_rule "$dir" "$prefix")"
  base="$(baseline_get "$name")"

  if [ "$base" = "-1" ]; then
    verdict="NEW (基线缺失)"
  elif [ "$cur" -gt "$base" ]; then
    verdict="❌ 超出 +$((cur - base))"
    EXCEEDED=$((EXCEEDED + 1))
  elif [ "$cur" -lt "$base" ]; then
    verdict="✅ 改善 -$((base - cur))"
    IMPROVED=$((IMPROVED + 1))
  else
    verdict="✅ 持平"
  fi

  printf '%-16s %10s %10s   %s\n' "$name" "$base" "$cur" "$verdict"
  NEW_LINES+=("${name}=${cur}")

  if [ "$VERBOSE" = "1" ] && [ "$cur" -gt 0 ]; then
    grep -rE "^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?${prefix//./\\.}([.;]|$)" "$dir" \
         --include=*.java 2>/dev/null \
      | sed "s|^|      |" | head -5
  fi
done

printf '\n'

if [ "$UPDATE" = "1" ]; then
  {
    printf '# ============================================================================\n'
    printf '# HelloAI 架构漂移冻结基线 —— 跨域反向依赖计数\n'
    printf '#\n'
    printf '# 语义：计数「只降不升」。\n'
    printf '#   当前计数 > 基线 -> check-arch-freeze.sh 失败，CI 拦截；\n'
    printf '#   当前计数 < 基线 -> 视为改善，提示刷新基线；\n'
    printf '#   需要新增反向依赖时，必须在评审中说明理由，再执行\n'
    printf '#       bash scripts/ci/check-arch-freeze.sh --update-baseline\n'
    printf '#   更新本文件（不要直接手改数字）。\n'
    printf '#\n'
    printf '# 背景：见 doc/review/HelloAI 架构V2进度与质量审计报告（2026-09-29）.md §5.6\n'
    printf '#       与 scripts/README.md §5.2（新增验收口径优先下沉为可跨平台执行的形式）。\n'
    printf '#\n'
    printf '# 最后更新：%s\n' "$(date +%Y-%m-%d)"
    printf '# ============================================================================\n'
    for l in "${NEW_LINES[@]}"; do printf '%s\n' "$l"; done
  } > "$BASELINE"
  printf '[arch-freeze] 基线已刷新：%s\n' "$BASELINE"
  printf '[arch-freeze] 新基线：%s\n' "$(tr '\n' ' ' < <(grep -v '^#' "$BASELINE" | grep . ))"
  exit 0
fi

if [ "$EXCEEDED" -gt 0 ]; then
  printf '[arch-freeze] ❌ 有 %s 条规则超出冻结基线。\n' "$EXCEEDED"
  printf '  跨域反向依赖属于《目标架构》红线（域间依赖方向），新增必须显式评审。\n'
  printf '  若本次新增确属必要且已获认可，执行：\n'
  printf '      bash scripts/ci/check-arch-freeze.sh --update-baseline\n'
  exit 1
fi

if [ "$IMPROVED" -gt 0 ]; then
  printf '[arch-freeze] ✅ 未超出基线，且有 %s 条规则改善；建议刷新基线以锁定成果：\n' "$IMPROVED"
  printf '      bash scripts/ci/check-arch-freeze.sh --update-baseline\n'
else
  printf '[arch-freeze] ✅ 全部规则未超冻结基线。\n'
fi
exit 0
