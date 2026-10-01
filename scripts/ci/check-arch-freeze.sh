#!/usr/bin/env bash
# ============================================================================
# HelloAI 架构漂移冻结守卫 —— 跨域反向依赖「只降不升」
#
# 为什么要有这个脚本（2026-09-29 架构审计 Q4）：
#   项目当前**无 ArchUnit 等编译期约束**，架构红线（《HelloAI_CODE_STYLE》§6 依赖方向、
#   §7.1 跨域直捅 Mapper）此前仅靠**文档豁免**与 scripts/powershell/verify-dependency-direction.ps1
#   把关，而该 ps1：① 本机无 pwsh 无法运行；② 规则集只覆盖 10 条、不含 planner→task.mapper
#   与 shared→业务域；③ 硬编码绝对路径（违反 §39）。即：架构红线实际上是「靠自觉」，
#   回归无自动化拦截。
#
#   2026-09-30（Q1-② 元修复）：本脚本规则集由 3 条扩至 **§6 全量 + §7.1 Mapper 红线**（20 条），
#   作为**跨平台（bash）可进 CI** 的规范实现（接入点：scripts/ci/ci-gate.sh 门禁 3）；
#   verify-dependency-direction.ps1 退居「命名/@MapperScan 注册」补充。
#
#   2026-10-01（组 3 降级）：分组 3（planner->agent / task->agent，均为 §6 合法**前向**依赖）
#   由「只降不升」降级为**仅提示**。理由：端口反转（消费方定义端口 + 提供方实现适配器）必然
#   在提供方侧新增一条「提供方 -> 消费方」的顺向 import，属有意为之的机制性代价，不应逐轮
#   走棘轮评审。组 1（反向依赖）与组 2（跨域直捅 Mapper）仍是**硬拦截**。
#
# 本脚本的定位：
#   以**零新增依赖**的方式提供可执行的「冻结基线」守卫 —— 计数超出基线即失败，
#   使任何新增反向依赖必须显式走评审并更新基线，而不是静默漂移。
#
# 用法：
#   bash scripts/ci/check-arch-freeze.sh                       # 校验（超出基线 -> 退出码 1）
#   bash scripts/ci/check-arch-freeze.sh --update-baseline     # 用当前计数刷新基线
#   bash scripts/ci/check-arch-freeze.sh --verbose             # 打印每条规则的文件明细 Top5
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
    -h|--help)         sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed '$d'; exit 0 ;;
    *) printf '[arch-freeze] 未知参数：%s\n' "$arg" >&2; exit 64 ;;
  esac
done

# 规则表：规则名|被扫描目录|被禁止的 import 前缀|严重级（block|warn，缺省 block）
# ----------------------------------------------------------------------------
# 依赖方向链（CODE_STYLE §6）：planner > review > task > agent > system > shared
#   允许（下层向更下层，即「顺向」）：
#       planner→task、review→task、task→agent、agent→system、*→shared
#   禁止（组 1）：任何「下层 → 上层」的反向 import（§6「禁止反向依赖」全量）
#   禁止（组 2）：跨域直捅 Mapper（§7.1），含顺向方向的越级（如 planner→task.mapper）
#   组 3（warn）：2026-09-29 基线既有项，**非 §6 反向**（planner→agent / task→agent 都是
#       「上层 → 下层」的顺向前向依赖），2026-10-01 起由「只降不升」降级为**仅提示**：
#       端口反转（消费方定义端口 + 提供方实现适配器）必然在提供方侧新增 1 条
#       `提供方 → 消费方` 的顺向前向 import，这是**有意为之的机制性代价**，不应逐轮走棘轮评审。
#       真正红线是组 1（反向依赖）与组 2（跨域 Mapper），二者仍严格拦截。
#
# 计数口径：匹配「行首 import（含 static）+ 前缀」，前缀后的下一字符须为 `.` / `;` / 行尾，
#   因此域级规则（如 agent->task）**同时覆盖**其子包（task.mapper / task.entity …）。
#   非 0 计数的规则均为**存量标债**，只降不升；0 计数的规则是回归护栏（任何新增即刻失败）。
# ----------------------------------------------------------------------------
RULES=(
  # ---- 组 1：§6 反向依赖（严格禁止；目标恒为 0）----
  "task->planner|helloai-core/src/main/java/com/helloai/core/task|com.helloai.core.planner"
  "task->review|helloai-core/src/main/java/com/helloai/core/task|com.helloai.core.review"
  "agent->task|helloai-core/src/main/java/com/helloai/core/agent|com.helloai.core.task"                     # 存量标债 §6.1（68）
  "agent->planner|helloai-core/src/main/java/com/helloai/core/agent|com.helloai.core.planner"
  "agent->review|helloai-core/src/main/java/com/helloai/core/agent|com.helloai.core.review"
  "system->planner|helloai-core/src/main/java/com/helloai/core/system|com.helloai.core.planner"
  "system->review|helloai-core/src/main/java/com/helloai/core/system|com.helloai.core.review"
  "system->task|helloai-core/src/main/java/com/helloai/core/system|com.helloai.core.task"
  "system->agent|helloai-core/src/main/java/com/helloai/core/system|com.helloai.core.agent"
  "shared->planner|helloai-core/src/main/java/com/helloai/core/shared|com.helloai.core.planner"
  "shared->review|helloai-core/src/main/java/com/helloai/core/shared|com.helloai.core.review"
  "shared->task|helloai-core/src/main/java/com/helloai/core/shared|com.helloai.core.task"
  "shared->agent|helloai-core/src/main/java/com/helloai/core/shared|com.helloai.core.agent"
  "shared->system|helloai-core/src/main/java/com/helloai/core/shared|com.helloai.core.system"
  # ---- 组 2：§7.1 跨域直捅 Mapper（目标恒为 0）----
  "planner->task.mapper|helloai-core/src/main/java/com/helloai/core/planner|com.helloai.core.task.mapper"
  "review->task.mapper|helloai-core/src/main/java/com/helloai/core/review|com.helloai.core.task.mapper"
  "agent->task.mapper|helloai-core/src/main/java/com/helloai/core/agent|com.helloai.core.task.mapper"
  "task->agent.mapper|helloai-core/src/main/java/com/helloai/core/task|com.helloai.core.agent.mapper"
  # ---- 组 3：2026-09-29 基线既有项（顺向前向；2026-10-01 起仅提示）----
  "planner->agent|helloai-core/src/main/java/com/helloai/core/planner|com.helloai.core.agent|warn"
  "task->agent|helloai-core/src/main/java/com/helloai/core/task|com.helloai.core.agent|warn"
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
printf '%-22s %10s %10s   %s\n' '规则' '冻结基线' '当前计数' '判定'
printf '%s\n' '-----------------------------------------------------------------'

EXCEEDED=0
IMPROVED=0
WARNED=0
NEW_LINES=()
PREV_SEV=""

for rule in "${RULES[@]}"; do
  IFS='|' read -r name dir prefix severity <<< "$rule"
  severity="${severity:-block}"
  cur="$(count_rule "$dir" "$prefix")"
  base="$(baseline_get "$name")"

  if [ "$base" = "-1" ]; then
    verdict="NEW (基线缺失)"
  elif [ "$cur" -gt "$base" ]; then
    if [ "$severity" = "warn" ]; then
      verdict="⚠️  前向 +$((cur - base))（仅提示）"
      WARNED=$((WARNED + 1))
    else
      verdict="❌ 超出 +$((cur - base))"
      EXCEEDED=$((EXCEEDED + 1))
    fi
  elif [ "$cur" -lt "$base" ]; then
    verdict="✅ 改善 -$((base - cur))"
    IMPROVED=$((IMPROVED + 1))
  else
    verdict="✅ 持平"
  fi

  printf '%-22s %10s %10s   %s\n' "$name" "$base" "$cur" "$verdict"

  # 基线文件按严重级分段（warn 段加注释说明「仅提示」语义）
  if [ "$severity" != "$PREV_SEV" ]; then
    case "$severity" in
      warn)  NEW_LINES+=("# ---- 组 3：顺向前向依赖（§6 合法；**仅提示不拦截**，端口反转的承载方向）----") ;;
      *)     NEW_LINES+=("# ---- 组 1/2：反向依赖与跨域直捅 Mapper（严格拦截，只降不升）----") ;;
    esac
    PREV_SEV="$severity"
  fi
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
    printf '# HelloAI 架构漂移冻结基线 —— 跨域反向依赖计数（CODE_STYLE §6 全量 + §7.1）\n'
    printf '#\n'
    printf '# 语义：分两档。\n'
    printf '#   [严格拦截] 组 1 反向依赖 + 组 2 跨域直捅 Mapper：计数「只降不升」。\n'
    printf '#     当前计数 > 基线 -> check-arch-freeze.sh 失败，CI 拦截；\n'
    printf '#     当前计数 < 基线 -> 视为改善，提示刷新基线；\n'
    printf '#     需要新增反向依赖时，必须在评审中说明理由，再执行\n'
    printf '#        bash scripts/ci/check-arch-freeze.sh --update-baseline\n'
    printf '#     更新本文件（不要直接手改数字）。\n'
    printf '#   [仅提示]   组 3 前向依赖（planner->agent / task->agent）：§6 合法前向，\n'
    printf '#     2026-10-01 起由「只降不升」降级为仅打印计数、不参与拦截。\n'
    printf '#     理由：端口反转（消费方定义端口 + 提供方实现适配器）必然在提供方侧\n'
    printf '#     新增 1 条「提供方 -> 消费方」的顺向 import，属有意为之的机制性代价。\n'
    printf '#     仍照常记录，便于在 git 历史中观察前向耦合总量。\n'
    printf '#\n'
    printf '# 规则集：2026-09-30 由 3 条扩至 20 条（§6 反向依赖全量 + §7.1 Mapper 红线）。\n'
    printf '# 背景：见 doc/review/HelloAI 代码规范与架构偏离专项审计报告（2026-09-30）.md §7\n'
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

if [ "$WARNED" -gt 0 ]; then
  printf '[arch-freeze] ⚠️  有 %s 条「组 3 前向依赖」计数上升（仅提示，不拦截）。\n' "$WARNED"
  printf '  它们是 §6 合法前向依赖（上层 → 下层），也是「端口反转」的承载方向；\n'
  printf '  红线仍是组 1（反向依赖）与组 2（跨域 Mapper），二者严格拦截。\n\n'
fi

if [ "$IMPROVED" -gt 0 ]; then
  printf '[arch-freeze] ✅ 未超出基线，且有 %s 条规则改善；建议刷新基线以锁定成果：\n' "$IMPROVED"
  printf '      bash scripts/ci/check-arch-freeze.sh --update-baseline\n'
else
  printf '[arch-freeze] ✅ 全部规则未超冻结基线。\n'
fi
exit 0
