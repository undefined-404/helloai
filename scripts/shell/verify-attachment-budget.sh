#!/usr/bin/env bash
# ============================================================
# 附件预算单一来源守卫
#
# 防的问题：同一事实两处计算。
#   附件「能注入多少字」有两个判定点——① 要不要 ingest 到 kb（门槛）、
#   ② 实际注入多少（预算）。两处若各算一套，会出现方向相反的错位：
#     - 门槛宽、预算严：文件进了 kb 但注入被截断（能力白做且用户问不到后半段）
#     - 门槛严、预算宽：文件进了 kb 但仍按全文注入（嵌入成本白花 + 无用数据）
#   两种都不报错，只表现为「问不到」或「账单变高」，极难排查。
#
# 判据：会话附件链路（planner/）只能经 resolveChatAttachmentTotalLimit() 取预算，
#   不得直接读 ATTACHMENT_CONTENT_TOTAL_LIMIT（那是报告链的口径，窗口假设不同）。
#
#   报告链（review/ task/）继续用常量是合法的——它们的上下文假设是「核验走
#   64K+ 官方模型」，与会话附件的窗口配置无关，统一反而会改坏它们。
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

PLANNER_SRC="helloai-core/src/main/java/com/helloai/core/planner"

printf '== 附件预算单一来源守卫 ==\n'

# 1) 会话附件链不得直接读总预算常量
violations=$(grep -rn "ATTACHMENT_CONTENT_TOTAL_LIMIT" --include=*.java "$PLANNER_SRC" 2>/dev/null || true)
if [ -n "$violations" ]; then
    printf '  [FAIL] planner/ 直接读取 ATTACHMENT_CONTENT_TOTAL_LIMIT（应改调 resolveChatAttachmentTotalLimit）:\n'
    echo "$violations" | sed 's/^/         /'
    FAILED=1
else
    printf '  [ OK ] planner/ 无直接读取总预算常量（统一走 resolveChatAttachmentTotalLimit）\n'
fi

# 2) ingest 门槛与注入预算必须同一个配置键
gate=$(grep -rn "context-window-tokens" --include=*.java "$PLANNER_SRC" 2>/dev/null | wc -l | tr -d ' ')
if [ "$gate" -ge 2 ]; then
    printf '  [ OK ] 窗口配置被 %s 处引用（ingest 门槛与注入预算同源）\n' "$gate"
else
    printf '  [FAIL] 窗口配置仅 %s 处引用——门槛与预算可能用了不同的值\n' "$gate"
    FAILED=1
fi

printf '\n'
if [ "${FAILED:-0}" = "0" ]; then
    printf '[verify-attachment-budget] ✅ 全部通过\n'
    exit 0
else
    printf '[verify-attachment-budget] ❌ 存在预算分叉风险\n'
    exit 1
fi
