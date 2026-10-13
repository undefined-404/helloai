#!/usr/bin/env bash
# ============================================================
# verify-provider-catalog-parity.sh —— M-2③ 内置供应商注册信息一致性守卫
# ------------------------------------------------------------
# 目标：防「内置供应商的默认模型 / 端点 / 协议」在多处载体之间漂移
#       （D-a~D-e 陈旧值 ⇒ 密钥验证 404 / Agent 注册被拒 的复发根因）。
#
# 校验源（四方，均可能各自漂移；唯一真值是 llm_provider_model.is_default=1）：
#   ① 前端预置  helloai-ui/src/views/settings/providerCatalog.ts  的 defaultModel
#   ② 迁移种子  V49(is_default=1) + V61(moonshot 下线) + V101(deepseek/dashscope 订正)
#   ③ yml      helloai-start/src/main/resources/application.yml
#              helloai.providers.<code>.default-model
#   ④ Java 常量 LlmProviderKeyVerifyServiceImpl.FALLBACK_MODELS（D-d，404 命门）
#              DeepSeekProviderChatClientFactory.DEFAULT_MODEL（D-e）
#
# 覆盖完整性（★防「新增预置静默不受保护」）：
#   从 providerCatalog.ts 反解全部 providerCode，凡既不在「有种子集」、也不在
#   「显式无种子白名单」⇒ 判 FAIL。守卫白名单必须与目录枚举同源核对，不得靠人工记忆。
#   全部预置（含无种子项）另做「协议枚举合法 + baseUrl 不带 /v1」两个结构性检查（D-b 类）。
#
# 用法：
#   bash scripts/shell/verify-provider-catalog-parity.sh                   # EXIT=0 全部一致
#   bash scripts/shell/verify-provider-catalog-parity.sh --inject-deviation  # 反证①：篡改期望 ⇒ EXIT=1
#   bash scripts/shell/verify-provider-catalog-parity.sh --inject-catalog    # 反证②：篡改目录 ⇒ EXIT=1
# ============================================================
set -uo pipefail
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CATALOG="$ROOT/helloai-ui/src/views/settings/providerCatalog.ts"
YML="$ROOT/helloai-start/src/main/resources/application.yml"
JAVA_FALLBACK="$ROOT/helloai-core/src/main/java/com/helloai/core/agent/service/impl/LlmProviderKeyVerifyServiceImpl.java"
JAVA_DEEPSEEK="$ROOT/helloai-core/src/main/java/com/helloai/core/agent/chat/provider/DeepSeekProviderChatClientFactory.java"

# ---------- 真值锚点（唯一事实来源 = llm_provider_model.is_default=1）----------
#   - deepseek : V49:66-73 + V101（'deepseek-chat' 目录已无 → deepseek-v4-flash）
#   - moonshot : V49:79-90 + V61（moonshot-v1-*/kimi-k2.5 下线 → kimi-k3）
#   - minimax  : V49:108-116 + V108（M2.5/M2.7 全系列即将下线 → MiniMax-M3）
#   - dashscope: V49:94-104 + V101（'qwen-plus' 目录已无 → qwen3.7-plus）
SEEDED=(deepseek moonshot minimax dashscope)
declare -A SEED_DEFAULT=(
  [deepseek]=deepseek-v4-flash
  [moonshot]=kimi-k3
  [minimax]=MiniMax-M3
  [dashscope]=qwen3.7-plus
)

# 显式「无迁移种子」白名单：运行时经 createLlmProvider 建档（无 V46 行、无 yml 段、无模型行），
# 故三方一致性不适用。★M-3（模型目录种子）落地后这些 provider 应转入 SEEDED 并收回本白名单。
NO_SEED=(openai anthropic xai mistral groq)

# ---------- 反证开关 ----------
INJECT_MODE="${1:-}"
TMP_CATALOG=""
cleanup() { [[ -n "$TMP_CATALOG" && -f "$TMP_CATALOG" ]] && rm -f "$TMP_CATALOG"; return 0; }
trap cleanup EXIT
if [[ "$INJECT_MODE" == "--inject-deviation" ]]; then
  # 反证①：篡改「期望值」⇒ 三方比对链必须报失配
  SEED_DEFAULT[deepseek]="__injected_deviation__"
elif [[ "$INJECT_MODE" == "--inject-catalog" ]]; then
  # 反证②：篡改「目录真值」（临时副本）⇒ 目录解析 + 比对链必须报失配
  # 副本落 $ROOT/tmp（.gitignore:74 已忽略）。★不用 mktemp：本机 TMPDIR 是 Windows 绝对路径，
  # 沙箱 safe-delete 会对「盘符前缀」拒删 ⇒ 残留。仓内相对路径可正常清理。
  mkdir -p "$ROOT/tmp"
  TMP_CATALOG="$ROOT/tmp/.provider-catalog-inject.ts"
  sed "s#baseUrl: 'https://api.openai.com'#baseUrl: 'https://api.openai.com/v1'#" "$CATALOG" > "$TMP_CATALOG"
  CATALOG="$TMP_CATALOG"
fi

FAILS=0

# ---------- 取值器 ----------
# 前端预置：providerCode:'x' 块内 <field>:'val'
front_field() {
  local code="$1" field="$2"
  awk -v code="$code" -v field="$field" -F"'" '
    /providerCode:/ && $2 == code { found = 1 }
    found && $0 ~ "^[[:space:]]*" field ":" { print $2; exit }
  ' "$CATALOG"
}

# 目录全部 providerCode（断言与「覆盖集」同源，不靠人工记忆）
catalog_codes() {
  grep -oE "providerCode: *'[a-z0-9-]+'" "$CATALOG" | sed -E "s/.*'([a-z0-9-]+)'.*/\1/"
}

# yml：helloai.providers.<code>.default-model（${ENV:fallback} → fallback）
yml_default() {
  local code="$1"
  awk -v code="$code" '
    $1 == code ":" { inblock = 1; next }
    inblock && /default-model:/ { print; exit }
  ' "$YML" | sed -n 's/.*default-model:[[:space:]]*${[A-Z_]*:\([^}]*\)}.*/\1/p'
}

# Java 常量：FALLBACK_MODELS 的 "code", "model"
java_fallback() {
  local code="$1"
  grep -A 12 "FALLBACK_MODELS = Map.of(" "$JAVA_FALLBACK" \
    | grep -oE "\"$code\", *\"[^\"]+\"" | head -1 \
    | sed -E "s/.*\"$code\", *\"([^\"]+)\".*/\1/"
}

# Java 常量：DeepSeek 末位兜底 DEFAULT_MODEL
java_deepseek_default() {
  grep -oE 'DEFAULT_MODEL = "[^"]+"' "$JAVA_DEEPSEEK" | head -1 | sed -E 's/.*"([^"]+)".*/\1/'
}

# ---------- 检查 1：覆盖完整性（目录枚举 ↔ 守卫白名单）----------
declare -A KNOWN=()
for c in "${SEEDED[@]}" "${NO_SEED[@]}"; do KNOWN[$c]=1; done
covered=0
while read -r code; do
  [[ -z "$code" ]] && continue
  covered=$((covered + 1))
  if [[ -z "${KNOWN[$code]:-}" ]]; then
    echo "[FAIL] 预置 '$code' 未纳入守卫：既无迁移种子，也不在 NO_SEED 白名单"
    echo "        ⇒ 其 defaultModel 漂移不会被拦。请归入 SEEDED 或显式登记到 NO_SEED。"
    FAILS=$((FAILS + 1))
  fi
done < <(catalog_codes)

# 反向：有种子 provider 必须仍在目录中，否则本案空转（守卫存在 ≠ 守卫生效）
for code in "${SEEDED[@]}"; do
  if ! catalog_codes | grep -qx "$code"; then
    echo "[FAIL] 有种子 provider '$code' 已不在预置目录 ⇒ 本案空转，请同步"
    FAILS=$((FAILS + 1))
  fi
done

# ---------- 检查 2：全部预置的结构性约束（覆盖 D-b 类，含无种子项）----------
while read -r code; do
  [[ -z "$code" ]] && continue
  proto="$(front_field "$code" protocolType)"
  base="$(front_field "$code" baseUrl)"
  if [[ "$proto" != "OPENAI_COMPATIBLE" && "$proto" != "ANTHROPIC_COMPATIBLE" ]]; then
    echo "[FAIL] $code protocolType='$proto' 不在工厂支持枚举（OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE）"
    FAILS=$((FAILS + 1))
  fi
  # OpenAI/Anthropic 兼容工厂会自动拼 /v1（application.yml:305 注「实际端点 {base-url}/v1/chat/completions」）
  if [[ "$base" == */v1 || "$base" == */v1/ ]]; then
    echo "[FAIL] $code baseUrl='$base' 自带 /v1 ⇒ 实际请求会拼成 /v1/v1/...（D-b）"
    FAILS=$((FAILS + 1))
  fi
  if [[ -z "$base" ]]; then
    echo "[FAIL] $code baseUrl 取值失败（目录解析异常？）"
    FAILS=$((FAILS + 1))
  fi
done < <(catalog_codes)

# ---------- 检查 3：有种子 provider 的四方一致性 ----------
for code in "${SEEDED[@]}"; do
  expected="${SEED_DEFAULT[$code]}"
  fe="$(front_field "$code" defaultModel)"
  ym="$(yml_default "$code")"
  jf="$(java_fallback "$code")"
  ok=1
  if [[ "$fe" != "$expected" ]]; then
    echo "[FAIL] $code 前端预置 defaultModel='$fe' ≠ 种子真值 '$expected'"
    ok=0
  fi
  if [[ "$ym" != "$expected" ]]; then
    echo "[FAIL] $code application.yml default-model='$ym' ≠ 种子真值 '$expected'"
    ok=0
  fi
  if [[ -n "$jf" && "$jf" != "$expected" ]]; then
    echo "[FAIL] $code Java 兜底 FALLBACK_MODELS='$jf' ≠ 种子真值 '$expected'（D-d：验证会 404）"
    ok=0
  fi
  [[ "$ok" == 1 ]] && echo "[ OK ] $code 一致 defaultModel='$expected'（含 Java 兜底$( [[ -n "$jf" ]] && echo '' || echo '：无条目' )）"
  [[ "$ok" == 0 ]] && FAILS=$((FAILS + 1))
done

# ---------- 检查 4：Java 兜底常量不得存在「非内置 provider」残留 ----------
declare -A SEEDED_SET=()
for c in "${SEEDED[@]}"; do SEEDED_SET[$c]=1; done
while read -r code; do
  [[ -z "$code" ]] && continue
  if [[ -z "${SEEDED_SET[$code]:-}" ]]; then
    echo "[FAIL] FALLBACK_MODELS 含非内置 provider '$code' ⇒ 兜底表与种子集脱节，请同步"
    FAILS=$((FAILS + 1))
  fi
done < <(grep -A 12 "FALLBACK_MODELS = Map.of(" "$JAVA_FALLBACK" \
            | grep -oE '"(deepseek|moonshot|minimax|dashscope|openai|anthropic|xai|mistral|groq)"' \
            | tr -d '"')

# ---------- 检查 5：DeepSeek 末位兜底（D-e）----------
jdm="$(java_deepseek_default)"
if [[ "$jdm" != "${SEED_DEFAULT[deepseek]}" ]]; then
  echo "[FAIL] DeepSeek DEFAULT_MODEL='$jdm' ≠ 种子真值 '${SEED_DEFAULT[deepseek]}'（D-e）"
  FAILS=$((FAILS + 1))
else
  echo "[ OK ] deepseek Java 末位兜底 DEFAULT_MODEL='$jdm'"
fi

echo "== verify-provider-catalog-parity: 预置 $covered 条（有种子 ${#SEEDED[@]} + 无种子白名单 ${#NO_SEED[@]}）；$([ $FAILS -eq 0 ] && echo 全部通过 || echo "${FAILS} 处失配")"
exit $FAILS
