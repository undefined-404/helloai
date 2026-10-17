#!/usr/bin/env bash
# ============================================================
# helloai OpenSandbox 集成契约守卫（S-6 验证集 · 静态模式 + S-8/S-9 执行链接线）
#
# 治什么：OpenSandbox 沙箱集成（S-1~S-5，doc/plan/HelloAI OpenSandbox 沙箱集成计划.md）
#   以「能力预研 + 契约落地」交付后，防止三类回归：
#   ① 契约文件缺失/改名（SandboxSpec / ExecutionPolicy.openSandbox / Provider / Probe）
#      —— 契约是后续业务执行链接线的接缝，缺一个就静默断链；
#   ② `ENVIRONMENT_RESOLVED` 埋点语义漂移（必须在 RuntimeTurnExecutor step=7 按事实记录
#      environment + accessType，不得删除/改步骤）——S-6 验收的「既有沙箱观测日志语义不变」；
#   ③ 诚实五边界被错误地标全 ISOLATED（network/process 必须保持 PARTIAL，
#      —— 防「安全沙箱已完成」误读；协作规约 §22 诚实标注）。
#   + S-7~S-9（沙箱执行链设计方案 2026-10-17）：执行通道（SandboxExecutionService）契约齐全、
#     internal_run_in_sandbox 工具接线（InternalLlmExecutorTools @Tool + INTERNAL_LLM_TOOLS 授权集 +
#     toolAvailability fail-closed 摘除）、S-9 机械判定（FinalReportFidelityChecker.checkScriptExecutability
#     存在 + FinalReportFidelityGate 沙箱注入点）——执行链任一环节缺失/改名即静默断链。
#
# 静态模式（默认；CI 用；零外部依赖）：grep 断言契约存在性 + 埋点形态 + 边界常量。
# 不依赖 Docker / 真实 server / 后端运行（探针真实验收见 doc/log/2026-10.md LOG-20261017-010）。
#
# 为什么用 bash 而非 PowerShell：见 verify-executor-doc-parity.sh 同源理由，三平台可跑。
#
# Ref: doc/plan/HelloAI OpenSandbox 沙箱集成计划.md §3（S-6 验收）
#      doc/plan/HelloAI 沙箱执行链设计方案（2026-10-17）.md §4（S-7/S-8/S-9 验收）
#      doc/log/2026-10.md LOG-20261017-008 / -009 / -010
# 用法：bash scripts/shell/verify-opensandbox-contract.sh
# 退出码：0 全部通过 / 1 存在失败项
# ============================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CORE="$REPO_ROOT/helloai-core/src/main/java/com/helloai/core"
COMMON="$REPO_ROOT/helloai-common/src/main/java/com/helloai/common"

SPEC="$CORE/agent/runtime/sandbox/SandboxSpec.java"
POLICY="$CORE/agent/runtime/sandbox/ExecutionPolicy.java"
PROVIDER="$CORE/agent/runtime/sandbox/impl/OpenSandboxSandboxProvider.java"
PROBE="$CORE/agent/runtime/sandbox/impl/OpenSandboxProbe.java"
ENV_CLS="$CORE/agent/runtime/OpenSandboxEnvironment.java"
PROPS="$COMMON/config/OpenSandboxProperties.java"
TURN_EXEC="$CORE/agent/runtime/RuntimeTurnExecutor.java"
# S-7~S-9 执行链落点
EXEC_SVC="$CORE/agent/runtime/sandbox/SandboxExecutionService.java"
EXEC_IMPL="$CORE/agent/runtime/sandbox/impl/OpenSandboxExecutionService.java"
EXEC_RESP="$CORE/agent/runtime/sandbox/ExecuteResponse.java"
EXEC_CONTRIB="$CORE/agent/runtime/sandbox/SandboxExecutionToolContributor.java"
INTERNAL_TOOLS="$CORE/agent/runtime/tool/InternalLlmExecutorTools.java"
AUTH_SVC="$CORE/agent/service/impl/AgentMcpServerServiceImpl.java"
FIDELITY_CHECKER="$CORE/review/support/FinalReportFidelityChecker.java"
FIDELITY_GATE="$CORE/review/quality/FinalReportFidelityGate.java"

PASS=0
FAIL=0
ok()  { printf '  [ OK ] %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  [FAIL] %s\n' "$1"; FAIL=$((FAIL + 1)); }

printf '== OpenSandbox 集成契约守卫（S-6 静态验证）==\n'

# ── 断言 ①：S-1~S-5 契约文件齐全 ──────────────────────────────────
printf '\n-- 断言 ①：契约文件齐全（S-1~S-5 落点）--\n'
missing=0
for f in "$SPEC" "$POLICY" "$PROVIDER" "$PROBE" "$ENV_CLS" "$PROPS"; do
  if [ ! -f "$f" ]; then
    bad "缺少契约文件：${f#"$REPO_ROOT"/}"
    missing=1
  fi
done
if [ "$missing" -eq 0 ]; then
  ok "S-1~S-5 契约文件齐全（SandboxSpec / ExecutionPolicy / Provider / Probe / Environment / Properties）"
fi

# ── 断言 ②：诚实五边界 openSandbox() 不标全 ISOLATED ───────────────
printf '\n-- 断言 ②：openSandbox() 诚实五边界（network/process 不得 ISOLATED）--\n'
if [ -f "$POLICY" ]; then
  # openSandbox() 工厂返回语句：跨两行 5 个 IsolationLevel 实参，须恰好 3×ISOLATED
  # （filesystem/resource/credential）；network/process 保持 PARTIAL
  factory_two="$(grep -A2 'public static ExecutionPolicy openSandbox()' "$POLICY" \
    | grep -A1 'return new ExecutionPolicy' | head -2 | tr '\n' ' ')"
  if [ -z "$factory_two" ]; then
    bad "openSandbox() 工厂返回语句未找到（形态与预期失配，断言不能静默失效）"
  else
    block_iso="$(printf '%s\n' "$factory_two" | grep -o 'IsolationLevel.ISOLATED' | wc -l | tr -d ' ')"
    if [ "$block_iso" -eq 3 ]; then
      ok "openSandbox() 工厂恰好 3×ISOLATED（filesystem/resource/credential；network/process 保持 PARTIAL）"
    else
      bad "openSandbox() 工厂 ISOLATED 数 = ${block_iso}（应为 3；全 5 即误标「安全沙箱已完成」）"
    fi
  fi
  # 五边界声明行（record 参数注释）须保留「不得标全 ISOLATED」的诚实口径文档
  if grep -q '不标全' "$POLICY"; then
    ok "工厂 javadoc 保留「不标全 ISOLATED」诚实口径"
  else
    bad "工厂 javadoc 丢失「不标全 ISOLATED」口径（诚实标注纪律）"
  fi
else
  bad "ExecutionPolicy.java 缺失，无法断言五边界"
fi

# ── 断言 ③：ENVIRONMENT_RESOLVED 埋点语义不变（S-6 回归锚点）──────
printf '\n-- 断言 ③：ENVIRONMENT_RESOLVED 埋点语义不变（既有沙箱观测日志）--\n'
if [ -f "$TURN_EXEC" ]; then
  # 埋点仍在 step=7 且按事实记录 environment + accessType（原语义：null 也按事实记录，不阻断链路）
  step7="$(grep -nE 'record\(ctx, 7, AgentEventType\.ENVIRONMENT_RESOLVED' "$TURN_EXEC" | head -1)"
  if [ -n "$step7" ]; then
    ok "ENVIRONMENT_RESOLVED 仍在 RuntimeTurnExecutor step=7 埋点（$step7）"
  else
    bad "ENVIRONMENT_RESOLVED 埋点缺失或步骤号漂移（应为 step=7）——既有沙箱观测日志语义被破坏"
  fi
  env_fact="$(grep -cE 'ctx\.getEnvironment\(\) != null \? ctx\.getEnvironment\(\)\.name\(\) : null' "$TURN_EXEC" || true)"
  if [ "$env_fact" -ge 1 ]; then
    ok "埋点按事实记录 environment（null 也记录，不阻断执行链）"
  else
    bad "environment 事实记录形态丢失——观测语义漂移"
  fi
  access_fact="$(grep -cE 'ctx\.getAccessType\(\) != null \? ctx\.getAccessType\(\)\.name\(\) : null' "$TURN_EXEC" || true)"
  if [ "$access_fact" -ge 1 ]; then
    ok "埋点按事实记录 accessType"
  else
    bad "accessType 事实记录形态丢失"
  fi
else
  bad "RuntimeTurnExecutor.java 缺失，无法断言 ENVIRONMENT_RESOLVED"
fi

# ── 断言 ④：Provider 默认关闭（enabled=false 契约，REF-6.13）──────
printf '\n-- 断言 ④：OpenSandbox Provider 默认关闭（REF-6.13）--\n'
if [ -f "$PROVIDER" ]; then
  default_off="$(grep -cE 'getProperties\(\).isEnabled\(\)|isEnabled\(\)' "$PROVIDER" || true)"
  if [ "$default_off" -ge 1 ]; then
    ok "Provider 经 enabled 开关显式启用（默认关闭，REF-6.13）"
  else
    bad "Provider 未显式检查 enabled 开关——默认开启会改变 app 零行为契约"
  fi
else
  bad "OpenSandboxSandboxProvider.java 缺失，无法断言 enabled 开关"
fi

# ── 断言 ⑤：S-7 执行通道契约齐全（沙箱执行链设计方案 §4）──────────
printf '\n-- 断言 ⑤：S-7 执行通道契约齐全（SandboxExecutionService / ExecuteResponse / impl）--\n'
missing_exec=0
for f in "$EXEC_SVC" "$EXEC_RESP" "$EXEC_IMPL"; do
  if [ ! -f "$f" ]; then
    bad "缺少 S-7 执行通道文件：${f#"$REPO_ROOT"/}"
    missing_exec=1
  fi
done
if [ "$missing_exec" -eq 0 ]; then
  ok "S-7 执行通道契约齐全（SandboxExecutionService / ExecuteResponse / OpenSandboxExecutionService）"
fi
if [ -f "$EXEC_IMPL" ]; then
  # 超时钳制 + 输出截断 + 失败结构化返回（不抛异常）——执行通道三要素
  if grep -q 'MAX_TIMEOUT_SECONDS' "$EXEC_IMPL" && grep -q 'MAX_OUTPUT_CHARS' "$EXEC_IMPL" \
    && grep -q 'EXIT_CODE_ERROR' "$EXEC_IMPL"; then
    ok "S-7 执行通道三要素齐备（超时钳制 / 输出截断 / 失败结构化返回）"
  else
    bad "S-7 执行通道三要素缺失（MAX_TIMEOUT_SECONDS / MAX_OUTPUT_CHARS / EXIT_CODE_ERROR）"
  fi
  # finally 无条件 kill：SDK close() 不删远端沙箱，必须显式 kill
  if grep -q 'sandbox.kill()' "$EXEC_IMPL"; then
    ok "S-7 执行通道 finally 无条件 kill（远端沙箱销毁语义）"
  else
    bad "S-7 执行通道缺失 sandbox.kill()——远端沙箱泄漏风险"
  fi
fi

# ── 断言 ⑥：S-8 工具接线齐全（internal_run_in_sandbox）────────────
printf '\n-- 断言 ⑥：S-8 internal_run_in_sandbox 工具接线 --\n'
if [ -f "$INTERNAL_TOOLS" ]; then
  if grep -q '@Tool(name = "internal_run_in_sandbox"' "$INTERNAL_TOOLS" && grep -q 'sandboxExecutionService.execute' "$INTERNAL_TOOLS"; then
    ok "InternalLlmExecutorTools 含 internal_run_in_sandbox 且委托 SandboxExecutionService"
  else
    bad "InternalLlmExecutorTools 缺失 internal_run_in_sandbox 或其委托调用——执行链断链"
  fi
  if grep -q 'requireInternalChain()' "$INTERNAL_TOOLS"; then
    ok "internal_run_in_sandbox 仍受 requireInternalChain() 强制闸门保护"
  else
    bad "requireInternalChain() 闸门缺失——internal 工具越权面回归"
  fi
else
  bad "InternalLlmExecutorTools.java 缺失，无法断言 S-8 接线"
fi
if [ -f "$AUTH_SVC" ]; then
  if grep -q '"internal_run_in_sandbox"' "$AUTH_SVC"; then
    ok "INTERNAL_LLM_TOOLS 授权集含 internal_run_in_sandbox（内部 LLM / Reviewer 可见）"
  else
    bad "INTERNAL_LLM_TOOLS 授权集缺失 internal_run_in_sandbox——工具装配了但模型不可见"
  fi
else
  bad "AgentMcpServerServiceImpl.java 缺失，无法断言授权集"
fi
if [ -f "$EXEC_CONTRIB" ]; then
  if grep -q 'toolAvailability' "$EXEC_CONTRIB" && grep -q 'properties.isEnabled()' "$EXEC_CONTRIB"; then
    ok "SandboxExecutionToolContributor 声明 fail-closed 摘除（enabled=false ⇒ 工具不可见）"
  else
    bad "SandboxExecutionToolContributor 缺失 toolAvailability()/isEnabled() 判定——fail-closed 失效"
  fi
else
  bad "SandboxExecutionToolContributor.java 缺失——条件可用（fail-closed）断链"
fi

# ── 断言 ⑦：S-9 机械判定接入（脚本类交付物可执行性）───────────────
printf '\n-- 断言 ⑦：S-9 机械判定接入（脚本可执行性）--\n'
if [ -f "$FIDELITY_CHECKER" ]; then
  if grep -q 'checkScriptExecutability' "$FIDELITY_CHECKER"; then
    ok "FinalReportFidelityChecker 含纯静态 checkScriptExecutability（S-9 确定性判定）"
  else
    bad "FinalReportFidelityChecker 缺失 checkScriptExecutability——S-9 机械判定断链"
  fi
else
  bad "FinalReportFidelityChecker.java 缺失，无法断言 S-9"
fi
if [ -f "$FIDELITY_GATE" ]; then
  if grep -q 'SandboxExecutionService' "$FIDELITY_GATE" && grep -q 'evaluateScriptExecutability' "$FIDELITY_GATE"; then
    ok "FinalReportFidelityGate 经沙箱执行通道注入脚本可执行性验证（Gate 扩展点）"
  else
    bad "FinalReportFidelityGate 缺失沙箱注入/脚本验证——S-9 接线点断链"
  fi
else
  bad "FinalReportFidelityGate.java 缺失，无法断言 S-9 接线点"
fi

printf '\n== 汇总 ==\n'
if [ "$FAIL" -eq 0 ]; then
  printf '[verify-opensandbox-contract] 全部通过（PASS=%s）\n' "$PASS"
  exit 0
else
  printf '[verify-opensandbox-contract] FAIL=%s / PASS=%s\n' "$FAIL" "$PASS"
  exit 1
fi
