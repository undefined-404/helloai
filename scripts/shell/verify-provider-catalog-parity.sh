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
#   全部预置（含无种子项）另做三个结构性检查：协议枚举合法 / baseUrl 不带 /v1（D-b 类） /
#   catalogId 非空（M-2② 新增维度——models.dev 目录 id 是 defaultModel 的归属锚点）。
# M-3（V109）后：9 个内置预置全部有种子（NO_SEED 清空）；yml 段仅遗留 4 家保留，
#   故 yml 比对按「有段才比」进行，另加反向检查「yml 段必须 ∈ SEEDED」。
#
# 用法：
#   bash scripts/shell/verify-provider-catalog-parity.sh                   # EXIT=0 全部一致
#   bash scripts/shell/verify-provider-catalog-parity.sh --inject-deviation  # 反证①：篡改期望 ⇒ EXIT=1
#   bash scripts/shell/verify-provider-catalog-parity.sh --inject-catalog    # 反证②：篡改目录 ⇒ EXIT=1
#   bash scripts/shell/verify-provider-catalog-parity.sh --inject-missing-catalog-id  # 反证③：抹 catalogId ⇒ EXIT=1
#
# 【实现约定】**bash 3.2 兼容**（macOS 自带 bash；本仓门禁 3 的真实运行环境之一）：
#   禁用 `declare -A` 等 bash 4+ 语法 —— 该形态在 `set -u` 下会「中止脚本却 EXIT=0」，
#   把「未执行任何断言」伪装成通过（静默假绿）。详见下方真值锚点段注释与
#   `scripts/ci/check-shell-portability.sh`（机械守卫）。
#   **另**：变量插值后紧跟全角标点时一律写 `${VAR}`（`$VAR（` 在 bash 3.2 + UTF-8 locale
#   下会把多字节字符首字节并入变量名 ⇒ 同上「中止却 EXIT=0」），同一守卫覆盖。
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
#   - openai / anthropic / xai / mistral / groq : V109（M-3 目录种子，补 llm_provider 行 + 模型清单）
SEEDED=(deepseek moonshot minimax dashscope openai anthropic xai mistral groq)

# 「无迁移种子」白名单：**M-3（V109）落地后已清空** —— 9 个内置预置全部有种子。
# 保留空数组，使「目录枚举 ⊆ SEEDED ∪ NO_SEED」这条覆盖完整性写法不变（新增预置仍会被拦）。
NO_SEED=()

# ---------- bash 3 兼容：真值表与集合判定**不得用关联数组** ----------
# `declare -A` 是 **bash 4+** 语法；本仓门禁 3 的真实运行环境含 macOS 自带 bash 3.2.57。
# 实测（bash 3.2.57，`set -u` 生效）：
#   `declare -A X=( [k]=v )` ⇒ `-A` 被判非法选项（stderr，**非致命**）⇒ 该赋值退化为**索引**数组
#   ⇒ 下标 `[k]` 走**算术求值** ⇒ `k` 未绑定 ⇒ **中止脚本**，但退出码**实测为 0**
#   （对照：普通变量未绑定返回 1）⇒ 门禁 3 把它记成 `[ OK ]` —— 「静默假绿」。
# 故全部改用 `case` 查表函数 + 显式覆盖变量；机械守卫：scripts/ci/check-shell-portability.sh。
# ---- 反证①用的定点覆盖（替代原「原地改写关联数组元素」）----
SEED_DEFAULT_OVERRIDE_KEY=""
SEED_DEFAULT_OVERRIDE_VAL=""

# 「期望 defaultModel」的唯一取值入口（含反证覆盖，语义与原 `SEED_DEFAULT[$code]` 一致）
expected_default() {
  if [ -n "$SEED_DEFAULT_OVERRIDE_KEY" ] && [ "$1" = "$SEED_DEFAULT_OVERRIDE_KEY" ]; then
    printf '%s\n' "$SEED_DEFAULT_OVERRIDE_VAL"
    return 0
  fi
  case "$1" in
    deepseek)  printf '%s\n' 'deepseek-v4-flash' ;;
    moonshot)  printf '%s\n' 'kimi-k3' ;;
    minimax)   printf '%s\n' 'MiniMax-M3' ;;
    dashscope) printf '%s\n' 'qwen3.7-plus' ;;
    openai)    printf '%s\n' 'gpt-4o' ;;
    anthropic) printf '%s\n' 'claude-sonnet-4-5' ;;
    xai)       printf '%s\n' 'grok-4.7' ;;
    mistral)   printf '%s\n' 'mistral-large-latest' ;;
    groq)      printf '%s\n' 'llama-3.3-70b-versatile' ;;
    *) return 1 ;;
  esac
}

# 集合成员判定（替代原先两处 `declare -A` 集合）
in_list() {
  local needle="$1"
  shift
  local item
  for item in ${1+"$@"}; do
    [ "$item" = "$needle" ] && return 0
  done
  return 1
}
is_seeded()  { in_list "$1" ${SEEDED[@]+"${SEEDED[@]}"}; }
is_no_seed() { in_list "$1" ${NO_SEED[@]+"${NO_SEED[@]}"}; }
is_covered() { is_seeded "$1" || is_no_seed "$1"; }

# ---------- 反证开关 ----------
INJECT_MODE="${1:-}"
TMP_CATALOG=""
cleanup() { [[ -n "$TMP_CATALOG" && -f "$TMP_CATALOG" ]] && rm -f "$TMP_CATALOG"; return 0; }
trap cleanup EXIT
if [[ "$INJECT_MODE" == "--inject-deviation" ]]; then
  # 反证①：篡改「期望值」⇒ 三方比对链必须报失配
  SEED_DEFAULT_OVERRIDE_KEY="deepseek"
  SEED_DEFAULT_OVERRIDE_VAL="__injected_deviation__"
elif [[ "$INJECT_MODE" == "--inject-catalog" ]]; then
  # 反证②：篡改「目录真值」（临时副本）⇒ 目录解析 + 比对链必须报失配
  # 副本落 $ROOT/tmp（.gitignore:74 已忽略）。★不用 mktemp：本机 TMPDIR 是 Windows 绝对路径，
  # 沙箱 safe-delete 会对「盘符前缀」拒删 ⇒ 残留。仓内相对路径可正常清理。
  mkdir -p "$ROOT/tmp"
  TMP_CATALOG="$ROOT/tmp/.provider-catalog-inject.ts"
  sed "s#baseUrl: 'https://api.openai.com'#baseUrl: 'https://api.openai.com/v1'#" "$CATALOG" > "$TMP_CATALOG"
  CATALOG="$TMP_CATALOG"
elif [[ "$INJECT_MODE" == "--inject-missing-catalog-id" ]]; then
  # 反证③：抹掉某条预置的 catalogId ⇒ 检查 6（M-2②）必须报失配
  mkdir -p "$ROOT/tmp"
  TMP_CATALOG="$ROOT/tmp/.provider-catalog-inject-cid.ts"
  sed "/    catalogId: 'groq',/d" "$CATALOG" > "$TMP_CATALOG"
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
# 单次取值：既供「覆盖完整性」遍历，也供「反向空转检查」判据（见下方 ★）
CATALOG_CODES="$(catalog_codes)"

# yml：helloai.providers.<code>.default-model（${ENV:fallback} → fallback）
yml_default() {
  local code="$1"
  awk -v code="$code" '
    $1 == code ":" { inblock = 1; next }
    inblock && /default-model:/ { print; exit }
  ' "$YML" | sed -n 's/.*default-model:[[:space:]]*${[A-Z_]*:\([^}]*\)}.*/\1/p'
}

# yml 是否存在该 provider 段。yml 是**遗留载体**：V46 起 provider 已改 DB 驱动，
# M-3（V109）只为历史 4 家保留 yml 段，**新增 provider 一律不建段** ⇒ 比对仅对「有段者」生效。
yml_has_block() {
  local code="$1"
  awk -v code="$code" '
    /^  providers:/ { inb = 1; next }
    inb && /^  [a-z]/ { exit }
    inb && $0 ~ "^    " code ":" { found = 1; exit }
    END { exit !found }
  ' "$YML"
}

# yml providers 段内的全部 provider code（反向：防出现「非 SEEDED」野段）
yml_provider_codes() {
  awk '
    /^  providers:/ { inb = 1; next }
    inb && /^  [a-z]/ { exit }
    inb && /^    [a-z0-9-]+:/ { sub(/^    /, ""); sub(/:.*/, ""); print }
  ' "$YML"
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
covered=0
while read -r code; do
  [[ -z "$code" ]] && continue
  covered=$((covered + 1))
  if ! is_covered "$code"; then
    echo "[FAIL] 预置 '$code' 未纳入守卫：既无迁移种子，也不在 NO_SEED 白名单"
    echo "        ⇒ 其 defaultModel 漂移不会被拦。请归入 SEEDED 或显式登记到 NO_SEED。"
    FAILS=$((FAILS + 1))
  fi
done < <(catalog_codes)

# 反空转地板：目录解析为 0 条 ⇒ 上述「完整性」与下方结构性检查**恒真**（守卫存在 ≠ 守卫生效）
if [ "$covered" -eq 0 ]; then
  echo "[FAIL] 预置目录解析为 0 条 ⇒ 守卫空转（解析逻辑失配源码形态？）"
  FAILS=$((FAILS + 1))
fi

# 反向：有种子 provider 必须仍在目录中，否则本案空转（守卫存在 ≠ 守卫生效）
# ★ 判据不得写成 `catalog_codes | grep -qx "$code"`：`grep -q` 命中即退出 ⇒ 上游 `sed` 收
#   SIGPIPE（141），`set -o pipefail` 下整条管道判**非零** ⇒ **间歇性假 FAIL**
#   （2026-10-15 实测：该形态 270 次判里误判 187 次；`grep -c` 读全部输入、不早退 ⇒ 0 次）。
for code in "${SEEDED[@]}"; do
  if [ "$(printf '%s\n' "$CATALOG_CODES" | grep -cx "$code")" -eq 0 ]; then
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

# ---------- 检查 3：有种子 provider 的多方一致性 ----------
# 参与比对的载体按「该 provider 实际具备的」取：
#   ① 前端预置（9 家全部）② yml（仅遗留 4 家仍有段）③ Java 兜底常量（有条目才比）
for code in "${SEEDED[@]}"; do
  expected="$(expected_default "$code")"
  fe="$(front_field "$code" defaultModel)"
  ym="$(yml_default "$code")"
  jf="$(java_fallback "$code")"
  ok=1
  if [[ "$fe" != "$expected" ]]; then
    echo "[FAIL] $code 前端预置 defaultModel='$fe' ≠ 种子真值 '$expected'"
    ok=0
  fi
  if yml_has_block "$code" && [[ "$ym" != "$expected" ]]; then
    echo "[FAIL] $code application.yml default-model='$ym' ≠ 种子真值 '$expected'"
    ok=0
  fi
  if [[ -n "$jf" && "$jf" != "$expected" ]]; then
    echo "[FAIL] $code Java 兜底 FALLBACK_MODELS='$jf' ≠ 种子真值 '$expected'（D-d：验证会 404）"
    ok=0
  fi
  yml_flag="$( yml_has_block "$code" && echo '含 yml 段' || echo '无 yml 段' )"
  [[ "$ok" == 1 ]] && echo "[ OK ] $code 一致 defaultModel='$expected'（${yml_flag}；Java 兜底$( [[ -n "$jf" ]] && echo '' || echo '：无条目' )）"
  [[ "$ok" == 0 ]] && FAILS=$((FAILS + 1))
done

# ---------- 检查 3b：yml providers 段不得有「非 SEEDED」野段（反向）----------
# ★yml 是遗留载体（DB 驱动后不再新增段）⇒ 比对按「有段才比」。为避免该条件化
#   造成「yml 载体被整体删除却静默通过」，此处补一道整体缺失兜底（不硬编码条数）。
if [[ -z "$(yml_provider_codes)" ]]; then
  echo "[FAIL] application.yml 的 helloai.providers 段为空或解析失败 ⇒ yml 载体整体缺失，请核查"
  FAILS=$((FAILS + 1))
fi
while read -r code; do
  [[ -z "$code" ]] && continue
  if ! is_seeded "$code"; then
    echo "[FAIL] application.yml helloai.providers.$code 段不在 SEEDED ⇒ yml 与种子集脱节，请同步"
    FAILS=$((FAILS + 1))
  fi
done < <(yml_provider_codes)

# ---------- 检查 4：Java 兜底常量不得存在「非内置 provider」残留 ----------
while read -r code; do
  [[ -z "$code" ]] && continue
  if ! is_seeded "$code"; then
    echo "[FAIL] FALLBACK_MODELS 含非内置 provider '$code' ⇒ 兜底表与种子集脱节，请同步"
    FAILS=$((FAILS + 1))
  fi
done < <(grep -A 12 "FALLBACK_MODELS = Map.of(" "$JAVA_FALLBACK" \
            | grep -oE '"(deepseek|moonshot|minimax|dashscope|openai|anthropic|xai|mistral|groq)"' \
            | tr -d '"')

# ---------- 检查 5：DeepSeek 末位兜底（D-e）----------
jdm="$(java_deepseek_default)"
deepseek_expected="$(expected_default deepseek)"
if [[ "$jdm" != "$deepseek_expected" ]]; then
  echo "[FAIL] DeepSeek DEFAULT_MODEL='$jdm' ≠ 种子真值 '$deepseek_expected'（D-e）"
  FAILS=$((FAILS + 1))
else
  echo "[ OK ] deepseek Java 末位兜底 DEFAULT_MODEL='$jdm'"
fi

# ---------- 检查 6：全部预置的 catalogId 非空（M-2② 新增维度，防静默缺失）----------
missing_cid=0
while read -r code; do
  [[ -z "$code" ]] && continue
  cid="$(front_field "$code" catalogId)"
  if [[ -z "$cid" ]]; then
    echo "[FAIL] $code 缺 catalogId（models.dev 目录 id）⇒ M-3 种子无从锚定，请补齐"
    FAILS=$((FAILS + 1)); missing_cid=$((missing_cid + 1))
  fi
done < <(catalog_codes)
[[ "$missing_cid" == 0 ]] && echo "[ OK ] 全部预置 catalogId（models.dev 目录 id）非空"

echo "== verify-provider-catalog-parity: 预置 $covered 条（有种子 ${#SEEDED[@]} + 无种子白名单 ${#NO_SEED[@]}）；$([ $FAILS -eq 0 ] && echo 全部通过 || echo "${FAILS} 处失配")"
exit $FAILS
