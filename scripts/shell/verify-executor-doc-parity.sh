#!/usr/bin/env bash
# ============================================================
# helloai 交付文档一致性守卫（外部 Agent 手册 ↔ 内部手册 ↔ 代码常量）
#
# 用途：把「代码改了、交付给外部 Agent 的说明书没跟」这类漂移变成**可机械校验**的断言。
#       背景（2026-10-10）：`REF-5.2b` 落地时发现交付手册 `onboarding/executor/guide.md`
#       有两处**已存在**的错误（限额口径写成「每前置 64000」，实为整块总预算；以及
#       指引用 `getSubTaskDetail` 取“前置”附件清单 —— 该工具必拒）；同源未达成项
#       见差距表 G-015（`attachmentId` vs `id` 命名口径不一致）。
#
# 五组断言（全部只读，不修改任何文件）：
#   S1 工具面**三源静态对齐** —— guide.md §0.1 表格 / `McpController.TOOL_NAMES` /
#      `McpMcpServer` 的 `@Tool(name=…)` 名字集合三者相等，且 §0.1 标题声明的数量与之一致。
#      （运行时的 `verify-tool-matrix.ps1` 需后端在跑 + 管理员口令；本组是它的**离线版**，CI 可跑）
#   S2 已知错误口径**黑名单** —— 交付手册里不得再出现这两类表述：
#      ① 「每前置 <数字> 字符」（限额被误述为每前置；实为整块总预算，多前置共享）
#      ② 同一行里 `getSubTaskDetail` 与「等价」（曾指引它取前置附件清单，实际必拒）
#   S3 **关键口径必备词** —— 交付手册与内部手册都必须写明取回链与判据（防倒退）。
#   S4 内部手册 ↔ **拼装产物**一致 —— `manual-assembled.md` 必须含 `00-manual-contract.md` 的关键词
#      （它是 `assemble-manual.ps1` 的确定性产物；改源不重装即红）。
#   S5 文档里的限额数字 ↔ **代码常量** —— 防止「代码调了限额、文档没跟」。
#   S6 scripts/README.md 的脚本计数 ↔ **仓库在册实数**（git ls-files）—— 索引里的数字不许悄悄过期
#   S7 `RV-12` 有界续读工具名**三源对齐** —— `InternalLlmExecutorTools` @Tool / `ToolDisplayName`
#      枚举 / reviewer 指南同时出现，缺一源即失败（模型按旧指南不会用新工具）。
#   S8 `RV-8`/`RV-10`/`RV-12` 的标记与常量**同源** —— reviewer 指南必须写明
#      `[MECHANICAL_CHECK]` / `[EVIDENCE_LEDGER]` / `8,192` / `1,200`，且代码常量存在且数值一致。
#   （S7/S8 于 2026-11-14 随 `G-034` 批次 3 增加；口径只做存在性与数值一致，不做语义判断）
#
# 为什么用 bash（不用 zsh / python）：本守卫刻意只用 bash + grep/sed，**三平台都能跑**、零依赖。
# （对照：`verify-doc-gap-table.sh` 原为 zsh 实现、Windows/Git Bash 下跑不了且会静默 EXIT=0；
#   2026-10-11 已改 bash 实现并接入 `ci-gate.sh` 门禁 3，见 LOG-20261011-010。）
#
# Ref: doc/log/2026-10.md LOG-20261010-011（本轮落地）/ LOG-20261010-009（前序）
#      doc/HelloAI 实现差距表.md G-015（`attachmentId` ↔ `id` 命名口径）
#      scripts/README.md（脚本索引）
# 退出码：0 全部通过 / 1 存在失败项
# 用法：bash scripts/shell/verify-executor-doc-parity.sh
# ============================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

GUIDE="$REPO_ROOT/helloai-core/src/main/resources/onboarding/executor/guide.md"
REVIEWER_GUIDE="$REPO_ROOT/helloai-core/src/main/resources/onboarding/reviewer/guide.md"
MANUAL="$REPO_ROOT/doc/manual/executor-duty/00-manual-contract.md"
ASSEMBLED="$REPO_ROOT/doc/manual/executor-duty/manual-assembled.md"
MCP_CONTROLLER="$REPO_ROOT/helloai-api/src/main/java/com/helloai/api/controller/McpController.java"
MCP_SERVER="$REPO_ROOT/helloai-core/src/main/java/com/helloai/core/agent/mcp/McpMcpServer.java"
RENDERER="$REPO_ROOT/helloai-core/src/main/java/com/helloai/core/shared/util/UpstreamAttachmentRenderer.java"
POLICY="$REPO_ROOT/helloai-core/src/main/java/com/helloai/core/shared/util/AttachmentContentPolicy.java"
TOOL_IMPL="$REPO_ROOT/helloai-core/src/main/java/com/helloai/core/agent/runtime/tool/InternalLlmExecutorTools.java"
TOOL_DISPLAY="$REPO_ROOT/helloai-common/src/main/java/com/helloai/common/constant/ToolDisplayName.java"
MECH_CHECK="$REPO_ROOT/helloai-core/src/main/java/com/helloai/core/review/support/ReviewMechanicalCheck.java"
EVIDENCE_LEDGER="$REPO_ROOT/helloai-core/src/main/java/com/helloai/core/review/support/ReviewEvidenceLedger.java"

PASS=0
FAIL=0
ok()  { printf '  [ OK ] %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  [FAIL] %s\n' "$1"; FAIL=$((FAIL + 1)); }

printf '== 交付文档一致性守卫（外部 Agent 手册 ↔ 内部手册 ↔ 代码常量）==\n'

for f in "$GUIDE" "$REVIEWER_GUIDE" "$MANUAL" "$ASSEMBLED" "$MCP_CONTROLLER" "$MCP_SERVER" "$RENDERER" "$POLICY" \
         "$TOOL_IMPL" "$TOOL_DISPLAY" "$MECH_CHECK" "$EVIDENCE_LEDGER"; do
  if [ ! -f "$f" ]; then
    bad "缺少被检查文件：${f#$REPO_ROOT/}"
    printf '\n[verify-executor-doc-parity] FAIL=%s PASS=%s\n' "$FAIL" "$PASS"
    exit 1
  fi
done

# ------------------------------------------------------------
# S1 工具面三源静态对齐
# ------------------------------------------------------------
printf '\n-- S1 工具面三源对齐（guide §0.1 / McpController / McpMcpServer）--\n'

guide_tools="$(sed -n '/^### 0\.1/,/^### 0\.2/p' "$GUIDE" \
  | grep -oE '^\| `[a-zA-Z]+`' | grep -oE '[a-zA-Z]+' | sort -u)"
controller_tools="$(sed -n '/TOOL_NAMES = List\.of(/,/);/p' "$MCP_CONTROLLER" \
  | grep -oE '"[a-zA-Z]+"' | tr -d '"' | sort -u)"
server_tools="$(grep -oE '@Tool\(name = "[a-zA-Z]+"' "$MCP_SERVER" \
  | grep -oE '"[a-zA-Z]+"' | tr -d '"' | sort -u)"

g_count="$(printf '%s\n' "$guide_tools" | grep -c . || true)"
c_count="$(printf '%s\n' "$controller_tools" | grep -c . || true)"
s_count="$(printf '%s\n' "$server_tools" | grep -c . || true)"

if [ "$g_count" -eq 0 ]; then
  bad "S1 guide §0.1 工具表解析为空 —— 表格格式可能已被改坏（断言不能静默失效）"
elif [ "$g_count" -eq "$c_count" ] && [ "$c_count" -eq "$s_count" ] \
     && [ "$(printf '%s\n' "$guide_tools")" = "$(printf '%s\n' "$controller_tools")" ] \
     && [ "$(printf '%s\n' "$controller_tools")" = "$(printf '%s\n' "$server_tools")" ]; then
  ok "S1 三源工具名集合一致（$g_count 个：$(printf '%s ' $guide_tools))"
else
  bad "S1 工具面漂移：guide=$g_count / McpController=$c_count / McpMcpServer=$s_count"
  printf '       仅 guide 有 : %s\n' "$(comm -23 <(printf '%s\n' "$guide_tools") <(printf '%s\n' "$controller_tools") | tr '\n' ' ')"
  printf '       仅代码有   : %s\n' "$(comm -13 <(printf '%s\n' "$guide_tools") <(printf '%s\n' "$controller_tools") | tr '\n' ' ')"
fi

# §0.1 标题声明的数量必须等于实际行数（防止"表加了行、标题没改"）
# 注：`\+` / `\|` 是 GNU sed 扩展，BSD sed（macOS）不认 ⇒ 用 `[0-9][0-9]*` 保持三平台可跑
declared="$(sed -n '/^### 0\.1/s/.*（\([0-9][0-9]*\) 个.*/\1/p' "$GUIDE" | head -1)"
if [ -n "$declared" ] && [ "$declared" = "$g_count" ]; then
  ok "S1 §0.1 标题声明数量与实际一致（$declared 个）"
else
  bad "S1 §0.1 标题声明数量（'${declared:-未解析到}'）与实际行数（${g_count}）不一致"
fi

# ------------------------------------------------------------
# S2 已知错误口径黑名单（交付手册）
# ------------------------------------------------------------
printf '\n-- S2 已知错误口径黑名单 --\n'

hits="$(grep -nE '每前置[[:space:]]*[0-9]' "$GUIDE" || true)"
if [ -z "$hits" ]; then
  ok "S2 无「每前置 N 字符」误述（限额是整块总预算，多前置共享）"
else
  bad "S2 出现「每前置 N 字符」误述：$(printf '%s' "$hits" | head -2 | tr '\n' ' ')"
fi

hits="$(grep -n 'getSubTaskDetail' "$GUIDE" | grep '等价' || true)"
if [ -z "$hits" ]; then
  ok "S2 无「getSubTaskDetail … 等价」错述（该工具门槛=已分配给我 ∨ 未分配且 PENDING，取不到前置）"
else
  bad "S2 出现「getSubTaskDetail … 等价」错述：$(printf '%s' "$hits" | head -2 | tr '\n' ' ')"
fi

# ------------------------------------------------------------
# S3 关键口径必备词（防倒退）
# ------------------------------------------------------------
printf '\n-- S3 关键口径必备词 --\n'

require_in() { # $1=文件 $2=必备串 $3=说明
  if grep -qF -- "$2" "$1"; then
    ok "S3 ${1##*/} 含「$3」"
  else
    bad "S3 ${1##*/} 缺「$3」（检索串：$2）"
  fi
}

require_in "$GUIDE" 'id=<attachmentId>'        '截断标注行带可寻址 ref'
require_in "$GUIDE" 'downloadById'             '附件取回通道'
require_in "$GUIDE" '整块总预算'                '注入限额口径（总预算，非每前置）'
require_in "$GUIDE" 'dep_content_limit'        '截断原因码'
require_in "$GUIDE" 'Task-Team'                '附件可见性判据'
require_in "$MANUAL" 'id=<attachmentId>'       '截断标注行带可寻址 ref'
require_in "$MANUAL" 'downloadById'            '附件取回通道'
require_in "$MANUAL" '不可用于取'               'getSubTaskDetail 不适用于前置'

# ------------------------------------------------------------
# S4 内部手册 ↔ 拼装产物一致（确定性产物，改源必须重装）
# ------------------------------------------------------------
printf '\n-- S4 内部手册 ↔ 拼装产物 --\n'

for token in 'id=<attachmentId>' 'downloadById' '不可用于取'; do
  if grep -qF -- "$token" "$ASSEMBLED"; then
    ok "S4 manual-assembled.md 含「${token}」（与源章节同步）"
  else
    bad "S4 manual-assembled.md 缺「${token}」—— 源章节改了但未重跑 assemble-manual.ps1"
  fi
done

# ------------------------------------------------------------
# S5 文档限额数字 ↔ 代码常量
# ------------------------------------------------------------
printf '\n-- S5 文档限额 ↔ 代码常量 --\n'

deps_limit="$(grep -oE 'DEP_CONTENT_MAX_CHARS[[:space:]]*=[[:space:]]*[0-9_]+' "$RENDERER" | sed -E 's/.*=[[:space:]]*//' | tr -d '_' | grep -E '^[0-9]+$' | head -1)"
deps_line="$(grep -n '整块总预算' "$GUIDE" | head -1)"
# ★ 判据不写 `printf … | grep -qF …`：`grep -q` 命中即退出 ⇒ 上游收 SIGPIPE(141)，本脚本
#   启用了 `set -o pipefail` ⇒ 整条管道判非零 ⇒ **间歇性假 FAIL**。改「计数」形态（见
#   scripts/ci/check-shell-portability.sh P3；实测同族判据 270 次误判 187 次）。
if [ -n "$deps_limit" ] && [ "$(printf '%s' "$deps_line" | grep -cF "$deps_limit")" -gt 0 ]; then
  ok "S5 执行侧注入限额与代码一致（DEP_CONTENT_MAX_CHARS=${deps_limit}）"
else
  bad "S5 执行侧注入限额不一致：代码 DEP_CONTENT_MAX_CHARS=${deps_limit:-未解析到}，手册含「整块总预算」的行未出现该数字"
fi

per_file="$(grep -oE 'ATTACHMENT_CONTENT_PER_FILE_LIMIT[[:space:]]*=[[:space:]]*[0-9_]+' "$POLICY" | sed -E 's/.*=[[:space:]]*//' | tr -d '_' | grep -E '^[0-9]+$' | head -1)"
total_lim="$(grep -oE 'ATTACHMENT_CONTENT_TOTAL_LIMIT[[:space:]]*=[[:space:]]*[0-9_]+' "$POLICY" | sed -E 's/.*=[[:space:]]*//' | tr -d '_' | grep -E '^[0-9]+$' | head -1)"
reviewer_line="$(grep -n '每附件' "$REVIEWER_GUIDE" | head -1)"
if [ -n "$per_file" ] && [ -n "$total_lim" ] \
   && [ "$(printf '%s' "$reviewer_line" | grep -cF "$per_file")" -gt 0 ] \
   && [ "$(printf '%s' "$reviewer_line" | grep -cF "$total_lim")" -gt 0 ]; then
  ok "S5 核验侧限额与代码一致（每附件 $per_file / 总计 ${total_lim}）"
else
  bad "S5 核验侧限额不一致：代码 每附件=${per_file:-?} 总计=${total_lim:-?}，reviewer 手册含「每附件」的行未同时出现两个数字"
fi

# ------------------------------------------------------------
# S6 scripts/README.md 的脚本计数 ↔ 仓库在册实数（git ls-files）
#
#   口径契约：改动 README 开头那一行时，**须保持这些标注词**，否则守卫判「无法解析」：
#     共 **N 个 PowerShell（…）+ N 个 Shell + N 个 Java 工具 + N 个 SQL**
#     另有 **N 个 CI 门禁脚本 + N 个架构冻结基线**
#
#   为什么用「仓库在册」而不是 on-disk：on-disk 会混入 logs/ 下解包 jar 的残留
#   （实测 sql 在册 1 / on-disk 13 —— 那 12 个是 scripts/powershell/logs/mcp-jar/… 里的迁移脚本）。
#   代价：**刚新增脚本、尚未 `git add` 时会红** —— 这是有意的，正确顺序是
#   「加文件 + 同步 README + git add」三者一起成立。
# ------------------------------------------------------------
printf '\n-- S6 脚本索引计数 ↔ 仓库在册实数 --\n'

S_README="$REPO_ROOT/scripts/README.md"
readme_line="$(grep -n '个 PowerShell' "$S_README" | head -1)"
num() { printf '%s' "$readme_line" | sed -nE "$1" | head -1; }

claim_ps="$(num 's/.*共 \*\*([0-9]+) 个 PowerShell.*/\1/p')"
claim_sh="$(num 's/.*\+ ([0-9]+) 个 Shell.*/\1/p')"
claim_java="$(num 's/.*\+ ([0-9]+) 个 Java 工具.*/\1/p')"
claim_sql="$(num 's/.*\+ ([0-9]+) 个 SQL.*/\1/p')"
claim_ci="$(num 's/.*([0-9]+) 个 CI 门禁脚本.*/\1/p')"
claim_base="$(num 's/.*([0-9]+) 个架构冻结基线.*/\1/p')"

real_ps=$(( $(git ls-files scripts/powershell | grep -c '\.ps1$') + $(git ls-files scripts | grep -cE '^scripts/[^/]+\.ps1$') ))
real_sh="$(git ls-files scripts/shell | grep -c '\.sh$')"
real_java="$(git ls-files scripts | grep -c '\.java$')"
real_sql="$(git ls-files scripts | grep -c '\.sql$')"
real_ci="$(git ls-files scripts/ci | grep -c '\.sh$')"
real_base="$(git ls-files scripts/ci | grep -c '\.txt$')"

check_count() { # $1=名称 $2=README 声明值 $3=仓库在册实数
  if [ -z "$2" ]; then
    bad "S6 $1 计数无法从 scripts/README.md 解析（口径契约见守卫注释，勿改动该行标注词）"
  elif [ "$2" = "$3" ]; then
    ok "S6 $1 计数一致（$2）"
  else
    bad "S6 $1 计数不一致：README 记 $2 / 仓库在册 $3（刚新增脚本？先 git add 并同步 README）"
  fi
}

check_count "PowerShell" "$claim_ps" "$real_ps"
check_count "Shell" "$claim_sh" "$real_sh"
check_count "Java 工具" "$claim_java" "$real_java"
check_count "SQL" "$claim_sql" "$real_sql"
check_count "CI 门禁脚本" "$claim_ci" "$real_ci"
check_count "架构冻结基线" "$claim_base" "$real_base"

# ------------------------------------------------------------
# S7 RV-12 有界续读工具名三源对齐（@Tool / ToolDisplayName / reviewer 指南）
# ------------------------------------------------------------
printf '\n-- S7 RV-12 工具名三源对齐 --\n'

for tool in internal_attachment_digest internal_read_attachment_range; do
  impl_hits="$(grep -c "@Tool(name = \"$tool\"" "$TOOL_IMPL")"
  display_hits="$(grep -c "\"$tool\"" "$TOOL_DISPLAY")"
  guide_hits="$(grep -c "$tool" "$REVIEWER_GUIDE")"
  if [ "$impl_hits" -gt 0 ] && [ "$display_hits" -gt 0 ] && [ "$guide_hits" -gt 0 ]; then
    ok "S7 $tool 三源一致（@Tool / ToolDisplayName / reviewer-guide）"
  else
    bad "S7 $tool 漂移：@Tool=$impl_hits / ToolDisplayName=$display_hits / reviewer-guide=${guide_hits}（缺哪源补哪源）"
  fi
done

# ------------------------------------------------------------
# S8 RV-8 / RV-10 / RV-12 标记与常量同源
#   口径：reviewer 指南必须写明四个机读标记/数值；代码侧常量存在且与指南数字一致。
# ------------------------------------------------------------
printf '\n-- S8 机械预检 / 台账标记与常量同源 --\n'

for marker in '[MECHANICAL_CHECK]' '[EVIDENCE_LEDGER]'; do
  if [ "$(grep -cF -- "$marker" "$REVIEWER_GUIDE")" -gt 0 ]; then
    ok "S8 reviewer 指南含标记 $marker"
  else
    bad "S8 reviewer 指南缺标记 ${marker}（模型不知道平台已在 Prompt 里给出该段）"
  fi
done

# 4 位整数加千分位（8192 → 8,192；1200 → 1,200）
thousands() { printf '%s' "$1" | sed -E 's/([0-9])([0-9]{3})$/\1,\2/'; }

range_max="$(grep -oE 'RANGE_MAX_CHARS[[:space:]]*=[[:space:]]*[0-9_]+' "$TOOL_IMPL" | sed -E 's/.*=[[:space:]]*//' | tr -d '_' | grep -E '^[0-9]+$' | head -1)"
mech_max="$(grep -oE 'MAX_OUTPUT_CHARS[[:space:]]*=[[:space:]]*[0-9_]+' "$MECH_CHECK" | sed -E 's/.*=[[:space:]]*//' | tr -d '_' | grep -E '^[0-9]+$' | head -1)"
ledger_max="$(grep -oE 'MAX_ENTRIES[[:space:]]*=[[:space:]]*[0-9_]+' "$EVIDENCE_LEDGER" | sed -E 's/.*=[[:space:]]*//' | tr -d '_' | grep -E '^[0-9]+$' | head -1)"

check_number_in_guide() { # $1=常量名 $2=代码值 $3=指南文件
  if [ -z "$2" ]; then
    bad "S8 代码常量 $1 未解析到（守卫不能静默失效）"
    return
  fi
  formatted="$(thousands "$2")"
  if [ "$(grep -cF -- "$formatted" "$3")" -gt 0 ]; then
    ok "S8 reviewer 指南的 $1 数值与代码一致（${formatted}）"
  else
    bad "S8 reviewer 指南缺 $1 数值 ${formatted}（代码=${2}）"
  fi
}

check_number_in_guide "RANGE_MAX_CHARS" "$range_max" "$REVIEWER_GUIDE"
check_number_in_guide "MAX_OUTPUT_CHARS" "$mech_max" "$REVIEWER_GUIDE"
if [ -n "$ledger_max" ]; then
  ok "S8 台账条目上限常量存在（ReviewEvidenceLedger.MAX_ENTRIES=${ledger_max}）"
else
  bad "S8 台账条目上限常量缺失（ReviewEvidenceLedger.MAX_ENTRIES）"
fi

# ------------------------------------------------------------
printf '\n== 汇总 ==\n'
if [ "$FAIL" -eq 0 ]; then
  printf '[verify-executor-doc-parity] 全部通过（PASS=%s）\n' "$PASS"
  exit 0
else
  printf '[verify-executor-doc-parity] FAIL=%s / PASS=%s\n' "$FAIL" "$PASS"
  exit 1
fi
