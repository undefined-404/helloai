#!/usr/bin/env bash
# ============================================================
# verify-provider-catalog-parity.sh —— M-2③ 三方一致性守卫
# ------------------------------------------------------------
# 目标：防「供应商预置 default-model」在 前端预置 / 迁移种子 / application.yml 三方漂移
#       （D-a~D-e 陈旧值 404 的复发根因）。
# 校验对象（内置 provider 的默认模型）：
#   - 前端预置：helloai-ui/src/views/settings/providerCatalog.ts 的 defaultModel
#   - 迁移种子真值：V49(llm_provider_model is_default=1) + V101(deepseek/dashscope 订正)
#                  + V61(moonshot 下线订正)
#   - application.yml：helloai.providers.<code>.default-model
# 用法：
#   bash scripts/shell/verify-provider-catalog-parity.sh             # EXIT=0 三方一致
#   bash scripts/shell/verify-provider-catalog-parity.sh --inject-deviation  # 反证：注入偏离 ⇒ EXIT=1
# ============================================================
set -uo pipefail
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CATALOG="$ROOT/helloai-ui/src/views/settings/providerCatalog.ts"
YML="$ROOT/helloai-start/src/main/resources/application.yml"

# 真值锚点（唯一事实来源 = llm_provider_model.is_default=1）
#   - deepseek : V49:66-73 + V101（'deepseek-chat' 目录已无 → deepseek-v4-flash）
#   - moonshot : V49:79-90 + V61（moonshot-v1-*/kimi-k2.5 下线 → kimi-k3）
#   - minimax  : V49:108-116（MiniMax-M2.5 为默认，保持）
#   - dashscope: V49:94-104 + V101（'qwen-plus' 目录已无 → qwen3.7-plus）
declare -A SEED_DEFAULT=(
  [deepseek]=deepseek-v4-flash
  [moonshot]=kimi-k3
  [minimax]=MiniMax-M2.5
  [dashscope]=qwen3.7-plus
)

# 反证：人为注入一条偏离，验证守卫确实能拦（M-2③ 验收：改偏离 ⇒ EXIT=1）
[[ "${1:-}" == "--inject-deviation" ]] && SEED_DEFAULT[deepseek]="__injected_deviation__"

FAILS=0

# 从前端预置取某 provider 的 defaultModel：providerCode:'x' 块内 defaultModel:'val'
front_default() {
  local code="$1"
  awk -v code="$code" -F"'" '
    /providerCode:/ && $2 == code { found = 1 }
    found && /defaultModel:/ { print $2; exit }
  ' "$CATALOG"
}

# 从 application.yml 取某 provider 的 default-model（${ENV:fallback} → fallback）
yml_default() {
  local code="$1"
  awk -v code="$code" '
    $1 == code ":" { inblock = 1; next }
    inblock && /default-model:/ { print; exit }
  ' "$YML" | sed -n 's/.*default-model:[[:space:]]*${[A-Z_]*:\([^}]*\)}.*/\1/p'
}

for code in deepseek moonshot minimax dashscope; do
  expected="${SEED_DEFAULT[$code]}"
  fe="$(front_default "$code")"
  ym="$(yml_default "$code")"
  ok=1
  if [[ "$fe" != "$expected" ]]; then
    echo "[FAIL] $code 前端预置 defaultModel='$fe' ≠ 种子真值 '$expected'"
    ok=0
  fi
  if [[ "$ym" != "$expected" ]]; then
    echo "[FAIL] $code application.yml default-model='$ym' ≠ 种子真值 '$expected'"
    ok=0
  fi
  [[ "$ok" == 1 ]] && echo "[ OK ] $code 三方一致 defaultModel='$expected'"
  [[ "$ok" == 0 ]] && FAILS=$((FAILS+1))
done

echo "== verify-provider-catalog-parity: $([ $FAILS -eq 0 ] && echo 全部通过 || echo "${FAILS} 处失配")"
exit $FAILS