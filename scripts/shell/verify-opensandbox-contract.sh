#!/usr/bin/env bash
# ============================================================
# helloai OpenSandbox 集成契约守卫（S-6 验证集 · 静态模式）
#
# 治什么：OpenSandbox 沙箱集成（S-1~S-5，doc/plan/HelloAI OpenSandbox 沙箱集成计划.md）
#   以「能力预研 + 契约落地」交付后，防止三类回归：
#   ① 契约文件缺失/改名（SandboxSpec / ExecutionPolicy.openSandbox / Provider / Probe）
#      —— 契约是后续业务执行链接线的接缝，缺一个就静默断链；
#   ② `ENVIRONMENT_RESOLVED` 埋点语义漂移（必须在 RuntimeTurnExecutor step=7 按事实记录
#      environment + accessType，不得删除/改步骤）——S-6 验收的「既有沙箱观测日志语义不变」；
#   ③ 诚实五边界被错误地标全 ISOLATED（network/process 必须保持 PARTIAL，
#      —— 防「安全沙箱已完成」误读；协作规约 §22 诚实标注）。
#
# 静态模式（默认；CI 用；零外部依赖）：grep 断言契约存在性 + 埋点形态 + 边界常量。
# 不依赖 Docker / 真实 server / 后端运行（探针真实验收见 doc/log/2026-10.md LOG-20261017-010）。
#
# 为什么用 bash 而非 PowerShell：见 verify-executor-doc-parity.sh 同源理由，三平台可跑。
#
# Ref: doc/plan/HelloAI OpenSandbox 沙箱集成计划.md §3（S-6 验收）
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

printf '\n== 汇总 ==\n'
if [ "$FAIL" -eq 0 ]; then
  printf '[verify-opensandbox-contract] 全部通过（PASS=%s）\n' "$PASS"
  exit 0
else
  printf '[verify-opensandbox-contract] FAIL=%s / PASS=%s\n' "$FAIL" "$PASS"
  exit 1
fi
