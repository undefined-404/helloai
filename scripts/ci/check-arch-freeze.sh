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
#   走棘轮评审。组 1（反向依赖）与组 2（跨域 Mapper）仍是**硬拦截**。
#
#   2026-10-02（A1/A2/A3：生成化 + 扩面 + 前向实体计数）：规则表由**手写 20 条**改为
#   **按依赖链生成 70 条**，并补齐此前的三类盲区（见 doc/review 2026-10-02 报告 F-1/F-2）：
#     A1 组 2（跨域直捅 Mapper）由 4 条硬编组合 → 遍历 6 域的**全部 30 个有序域对**生成；
#     A2 扫描范围由 helloai-core 扩至 **helloai-api / job / mq / start**——此前这些模块
#        完全不在视野内（脚本内 4 个模块名引用数均为 0），F-1（api 直捅
#        core.system.mapper.RequestLogMapper）正因此长期存活；
#     A3 新增「**前向跨域实体泄漏**」计数（组 3，warn 起步）——§6 只禁反向，前向实体泄漏
#        （如 agent→system.entity 61 行）此前无任何计数，看不见就不会被清偿。
#   规则表改为生成式后，「漏配一种域对组合」这类**规则级盲区**在结构上不再可能。
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

# ============================================================================
# 扫描源定义
# ----------------------------------------------------------------------------
# 依赖方向链（CODE_STYLE §6），下标即层级：0 最高，5 最低（shared 为叶子域）
#     planner(0) > review(1) > task(2) > agent(3) > system(4) > shared(5)
#   「顺向」= 上层依赖下层（srcIdx < dstIdx）；「反向」= 下层依赖上层（srcIdx > dstIdx）
# ============================================================================
DOMAINS=(planner review task agent system shared)
CORE_ROOT="helloai-core/src/main/java/com/helloai/core"

# A2：core 之外持有 com.helloai.core 依赖的模块（此前完全不在扫描范围内）
NON_CORE_MODULES=(api job mq start)

# 组 3 中需要保留计数的**域级前向依赖**组合（2026-09-29 基线既有项，保持键名与语义连续）
FORWARD_PKG_PAIRS=(planner:agent task:agent)

G1="组 1：§6 反向依赖（严格拦截；目标恒为 0）"
G2A="组 2a：core 跨域直捅 Mapper，30 个有序域对全量（严格拦截；目标恒为 0）"
G2B="组 2b：非 core 模块直捅 com.helloai.core.*.mapper（严格拦截；目标恒为 0）"
G3A="组 3a：域级前向依赖（§6 合法；仅提示）"
G3B="组 3b：前向跨域实体泄漏（A3；仅提示，用于暴露清偿目标）"
G3C="组 3c：非 core 模块的 core 实体泄漏（A3；仅提示）"

# 域在依赖链中的下标（0=planner … 5=shared）；未登记返回 -1
# 不用 assoc array：保持 macOS 自带 bash 3.2 亦可运行
domain_idx() {
  local target="$1" i
  for i in "${!DOMAINS[@]}"; do
    if [ "${DOMAINS[$i]}" = "$target" ]; then printf '%s' "$i"; return; fi
  done
  printf '%s' "-1"
}

# ============================================================================
# 规则表（**生成式**，勿手写单条）
#   字段：规则名 | 被扫描目录（':' 分隔，支持多根） | 匹配式 | 严重级 | 分组标签
#   匹配式两种写法：
#     pkg:<包前缀>  —— 字面包前缀，'.' 自动转义，其后须接 '.' / ';' / 行尾
#                      （因此域级规则天然覆盖子包：task 覆盖 task.mapper / task.entity …）
#     re:<ERE>      —— 原始扩展正则，直接交 grep -E（用于「任意域」这类通配场景）
#   严重级：block（严格拦截，只降不升）| warn（仅打印计数，不参与退出码）
# ============================================================================
RULES=()

# ---- 组 1：§6 反向依赖（下层 → 上层）全部 15 对 ----
for s in "${DOMAINS[@]}"; do
  for d in "${DOMAINS[@]}"; do
    si="$(domain_idx "$s")"; di="$(domain_idx "$d")"
    [ "$si" -gt "$di" ] || continue
    RULES+=("${s}->${d}|${CORE_ROOT}/${s}|pkg:com.helloai.core.${d}|block|${G1}")
  done
done

# ---- 组 2a：core 跨域直捅 Mapper，30 个有序域对全量（A1） ----
for s in "${DOMAINS[@]}"; do
  for d in "${DOMAINS[@]}"; do
    [ "$s" = "$d" ] && continue
    RULES+=("${s}->${d}.mapper|${CORE_ROOT}/${s}|pkg:com.helloai.core.${d}.mapper|block|${G2A}")
  done
done

# ---- 组 2b：非 core 模块直捅 core 任意域的 Mapper（A2） ----
for m in "${NON_CORE_MODULES[@]}"; do
  RULES+=("${m}->core.mapper|helloai-${m}/src/main/java|re:^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?com\\.helloai\\.core\\.[a-z]+\\.mapper\\.|block|${G2B}")
done

# ---- 组 3a：域级前向依赖（沿用 2026-09-29 基线键名与口径） ----
for pair in "${FORWARD_PKG_PAIRS[@]}"; do
  s="${pair%%:*}"; d="${pair##*:}"
  RULES+=("${s}->${d}|${CORE_ROOT}/${s}|pkg:com.helloai.core.${d}|warn|${G3A}")
done

# ---- 组 3b：前向跨域实体泄漏，15 对（A3） ----
for s in "${DOMAINS[@]}"; do
  for d in "${DOMAINS[@]}"; do
    si="$(domain_idx "$s")"; di="$(domain_idx "$d")"
    [ "$si" -lt "$di" ] || continue
    RULES+=("${s}->${d}.entity|${CORE_ROOT}/${s}|pkg:com.helloai.core.${d}.entity|warn|${G3B}")
  done
done

# ---- 组 3c：非 core 模块的 core 实体泄漏（A3） ----
for m in "${NON_CORE_MODULES[@]}"; do
  RULES+=("${m}->core.entity|helloai-${m}/src/main/java|re:^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?com\\.helloai\\.core\\.[a-z]+\\.entity\\.|warn|${G3C}")
done

# 匹配式 -> ERE。结果写入全局 RP（**不起子进程**：70 条规则各 fork 一次，
# 在本机的进程创建开销下就足以让守卫慢到不可用）
RP=""
rule_pattern_set() {
  local spec="$1" p
  case "$spec" in
    re:*)  RP="${spec#re:}" ;;
    pkg:*) p="${spec#pkg:}"; RP="^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?${p//./\\.}([.;]|$)" ;;
    *)     p="$spec"; RP="^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?${p//./\\.}([.;]|$)" ;;
  esac
}

# ----------------------------------------------------------------------------
# 性能设计（重要，勿退回「每条规则一次 grep」）
#   70 条规则 × 10 个扫描目录，若每条规则各跑一次 grep + 一次基线查询，实测需 2 分钟
#   以上——瓶颈是**进程创建**（本机单次 grep ≈ 300ms），而非匹配本身。因此：
#     ① 每个扫描目录只全树扫一次，抽出 import 行落缓存文件（10 次 grep）；
#     ② 全部规则的匹配交给**一次 awk**（动态 ERE，纯内存匹配）；
#     ③ 基线文件**一次读入内存**，用 bash 数组线性查表（不再每条规则 grep+cut+tr）。
#   合计子进程 ≈ 11 个。
#   另有两坑：缓存目录放 target/（已 gitignore）而**不放 /tmp**——CI 沙箱常禁仓外
#   写入；且**不能用 trap 清理**——命令替换子 shell 会继承 EXIT trap，会把缓存提前删掉。
# ----------------------------------------------------------------------------
# 缓存目录固定放 target/（已 gitignore，随 `mvn clean` 一起清；**不做 rm -rf**——
# 大目录递归删除容易被 CI 的安全删除策略拦截，一旦删不掉而代码又依赖「目录不存在」
# 来保证新鲜度，就会静默沿用上一次的旧快照。新鲜度改用「每次运行逐个截断重建」保证）。
CACHE_DIR="${ROOT}/target/arch-freeze-cache"
IMPORT_DIR="${CACHE_DIR}/imports"
mkdir -p "$IMPORT_DIR" 2>/dev/null

# 按 ':' 切分目录列表到全局数组 SPLIT_DIRS
SPLIT_DIRS=()
split_dirs() {
  local IFS=':'
  # shellcheck disable=SC2162
  read -r -a SPLIT_DIRS <<< "$1"
}

# 规则 -> 缓存文件名（纯 bash 参数展开做 sanitize，避免 sed 子进程）
RULE_KEYS=()
for _rule in "${RULES[@]}"; do
  IFS='|' read -r _n _dirs _s _sev _g <<< "$_rule"
  RULE_KEYS+=("${_dirs//[^A-Za-z0-9]/_}")
done

# ① 抽取：每个唯一扫描目录一次全树 grep
_i=0
BUILT_KEYS=""
for _rule in "${RULES[@]}"; do
  IFS='|' read -r _n _dirs _s _sev _g <<< "$_rule"
  _key="${RULE_KEYS[$_i]}"
  _i=$((_i + 1))
  # 同目录只抽一次；用「本次已建集合」判断，**不用 `[ -f ]`**——后者会让上一次运行的
  # 残留文件被当成有效缓存，源码改了却读到旧计数（静默失真）。
  case " ${BUILT_KEYS} " in *" ${_key} "*) continue ;; esac
  BUILT_KEYS="${BUILT_KEYS} ${_key}"
  _f="${IMPORT_DIR}/${_key}"
  : > "$_f"
  split_dirs "$_dirs"
  for _d in "${SPLIT_DIRS[@]}"; do
    [ -d "$_d" ] || continue
    grep -rhE "^[[:space:]]*import[[:space:]]+" "$_d" --include=*.java 2>/dev/null >> "$_f"
  done
done

# ② 规格表（name / 缓存文件 / ERE）
{
  _i=0
  for _rule in "${RULES[@]}"; do
    IFS='|' read -r _n _dirs _s _sev _g <<< "$_rule"
    rule_pattern_set "$_s"
    printf '%s\t%s\t%s\n' "$_n" "${RULE_KEYS[$_i]}" "$RP"
    _i=$((_i + 1))
  done
} > "${CACHE_DIR}/rules.tsv"

# ③ 一次 awk 完成 70 条规则的计数（按输出顺序与 RULES 对齐）
awk -F'\t' '
  NR == FNR { nm[++n] = $1; fl[n] = $2; re[n] = $3; c[n] = 0; next }
  FNR == 1  { f = FILENAME; sub(/^.*\//, "", f) }
  { for (i = 1; i <= n; i++) if (f == fl[i] && $0 ~ re[i]) c[i]++ }
  END { for (i = 1; i <= n; i++) printf "%s\t%d\n", nm[i], c[i] }
' "${CACHE_DIR}/rules.tsv" "${IMPORT_DIR}"/* > "${CACHE_DIR}/counts.tsv" 2>/dev/null

COUNTS=()
while IFS=$'\t' read -r _cn _cc; do COUNTS+=("${_cc:-0}"); done < "${CACHE_DIR}/counts.tsv"

# ④ 基线一次读入内存，线性查表（bash 3.2 无关联数组，故用两列数组）
BASE_NAMES=()
BASE_VALS=()
if [ -f "$BASELINE" ]; then
  while IFS='=' read -r _bn _bv; do
    case "$_bn" in \#*|"") continue ;; esac
    BASE_NAMES+=("$_bn")
    BASE_VALS+=("${_bv:-0}")
  done < "$BASELINE"
fi
BASE_HIT="-1"
baseline_lookup() {
  local key="$1" i
  BASE_HIT="-1"
  for i in "${!BASE_NAMES[@]}"; do
    if [ "${BASE_NAMES[$i]}" = "$key" ]; then BASE_HIT="${BASE_VALS[$i]}"; return 0; fi
  done
  return 0
}

verbose_hits() {
  local dirs="$1" pat="$2" d
  split_dirs "$dirs"
  for d in "${SPLIT_DIRS[@]}"; do
    [ -d "$d" ] || continue
    grep -rE "$pat" "$d" --include=*.java 2>/dev/null | sed "s|^|      |" | head -5
  done
}

printf '[arch-freeze] 仓库根：%s\n' "$ROOT"
printf '[arch-freeze] 冻结基线：%s\n' "$BASELINE"
printf '[arch-freeze] 规则总数：%s 条（生成式，非手写）\n\n' "${#RULES[@]}"
printf '%-24s %10s %10s   %s\n' '规则' '冻结基线' '当前计数' '判定'
printf '%s\n' '-----------------------------------------------------------------------'

EXCEEDED=0
IMPROVED=0
WARNED=0
NEW_RULES=0
NEW_LINES=()
PREV_GROUP=""

IDX=0
for rule in "${RULES[@]}"; do
  IFS='|' read -r name dirs spec severity group <<< "$rule"
  severity="${severity:-block}"
  cur="${COUNTS[$IDX]:-0}"
  baseline_lookup "$name"; base="$BASE_HIT"
  IDX=$((IDX + 1))

  if [ "$base" = "-1" ]; then
    verdict="🆕 新规则（待锁基线）"
    NEW_RULES=$((NEW_RULES + 1))
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

  printf '%-24s %10s %10s   %s\n' "$name" "$base" "$cur" "$verdict"

  if [ "$VERBOSE" = "1" ] && [ "$cur" -gt 0 ]; then
    rule_pattern_set "$spec"
    verbose_hits "$dirs" "$RP"
  fi

  if [ "$group" != "$PREV_GROUP" ]; then
    [ -n "$PREV_GROUP" ] && NEW_LINES+=("")
    NEW_LINES+=("# ---- ${group} ----")
    PREV_GROUP="$group"
  fi
  NEW_LINES+=("${name}=${cur}")
done

printf '\n'

if [ "$UPDATE" = "1" ]; then
  {
    printf '# ============================================================================\n'
    printf '# HelloAI 架构漂移冻结基线 —— 跨域依赖计数（§6 反向 + §7.1 Mapper + 前向实体泄漏）\n'
    printf '#\n'
    printf '# 由 `bash scripts/ci/check-arch-freeze.sh --update-baseline` 生成，**勿手改数字**。\n'
    printf '#\n'
    printf '# 语义：分两档。\n'
    printf '#   [严格拦截] 组 1 反向依赖 + 组 2 跨域直捅 Mapper：计数「只降不升」。\n'
    printf '#     当前计数 > 基线 -> check-arch-freeze.sh 失败，CI 拦截；\n'
    printf '#     当前计数 < 基线 -> 视为改善，提示刷新基线；\n'
    printf '#     需要新增时，必须在评审中说明理由，再执行 --update-baseline 更新本文件。\n'
    printf '#   [仅提示]   组 3（前向依赖 / 前向实体泄漏）：§6 合法方向，只打印计数、\n'
    printf '#     不参与退出码。前向实体泄漏（组 3b/3c）用于**暴露清偿目标**，\n'
    printf '#     使其与反向依赖一样「看得见才可能被清偿」。\n'
    printf '#\n'
    printf '# 规则集（生成式）：\n'
    printf '#   组 1  反向依赖          15 条（6 域全部逆序对）\n'
    printf '#   组 2a core 跨域 Mapper  30 条（6 域全部有序对）\n'
    printf '#   组 2b 非 core ->core.mapper  4 条（api / job / mq / start）\n'
    printf '#   组 3a 域级前向依赖       2 条（planner->agent / task->agent）\n'
    printf '#   组 3b 前向实体泄漏      15 条（6 域全部顺序对）\n'
    printf '#   组 3c 非 core ->core.entity  4 条\n'
    printf '#   合计 70 条。\n'
    printf '#\n'
    printf '# 变更史：2026-09-30 由 3 条扩至 20 条（§6 反向依赖全量 + §7.1 Mapper 红线）；\n'
    printf '#   2026-10-02 由「手写 20 条」改为「生成式 70 条」（A1 组 2 全量域对 /\n'
    printf '#   A2 扫描扩至 api·job·mq·start / A3 新增前向实体泄漏计数）。\n'
    printf '# 背景：见 doc/review/HelloAI 代码规范与架构偏离专项审计报告（2026-09-30）.md §7\n'
    printf '#       与 doc/review/HelloAI 架构V2进度与质量审计报告（2026-10-02）.md §6.3。\n'
    printf '#\n'
    printf '# 最后更新：%s\n' "$(date +%Y-%m-%d)"
    printf '# ============================================================================\n'
    for l in "${NEW_LINES[@]}"; do printf '%s\n' "$l"; done
  } > "$BASELINE"
  printf '[arch-freeze] 基线已刷新：%s\n' "$BASELINE"
  exit 0
fi

if [ "$EXCEEDED" -gt 0 ]; then
  printf '[arch-freeze] ❌ 有 %s 条规则超出冻结基线。\n' "$EXCEEDED"
  printf '  跨域反向依赖属于《目标架构》红线（域间依赖方向），新增必须显式评审。\n'
  printf '  若本次新增确属必要且已获认可，执行：\n'
  printf '      bash scripts/ci/check-arch-freeze.sh --update-baseline\n'
  exit 1
fi

if [ "$NEW_RULES" -gt 0 ]; then
  printf '[arch-freeze] 🆕 有 %s 条新规则尚无基线（不拦截）；执行 --update-baseline 锁定。\n' "$NEW_RULES"
fi

if [ "$WARNED" -gt 0 ]; then
  printf '[arch-freeze] ⚠️  有 %s 条「组 3 前向依赖/实体泄漏」计数上升（仅提示，不拦截）。\n' "$WARNED"
  printf '  它们是 §6 合法前向方向（上层 → 下层），也是「端口反转」的承载方向；\n'
  printf '  红线仍是组 1（反向依赖）与组 2（跨域 Mapper），二者严格拦截。\n'
fi

if [ "$IMPROVED" -gt 0 ]; then
  printf '[arch-freeze] ✅ 未超出基线，且有 %s 条规则改善；建议刷新基线以锁定成果：\n' "$IMPROVED"
  printf '      bash scripts/ci/check-arch-freeze.sh --update-baseline\n'
else
  printf '[arch-freeze] ✅ 全部规则未超冻结基线。\n'
fi
exit 0
