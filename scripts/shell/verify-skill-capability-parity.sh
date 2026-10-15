#!/usr/bin/env bash
# ============================================================
# verify-skill-capability-parity.sh —— M-9 技能/能力词表三源一致性 + V52 两列退役守卫
# ------------------------------------------------------------
# 目标（防 `M-9` 后的两类漂移）：
#   S1 三源同集合：后端 STANDARD_SKILLS ⇔ 前端 AGENT_SKILL_OPTIONS ⇔ REQUIRED 键集
#      —— 任一处新增/删除技能而另两处未同步 ⇒ 前端可选而后端判定不了（或反之）。
#   S2 两列退役无回写：`capability_skills` / `available_optional_skills` 只允许出现在
#      迁移目录的 {V52,V108,V109,V110,V112} 内（含 V112 归档表定义）；
#      出现在其他任何源文件 ⇒ FAIL（防「V52 列的代码幽灵」复发）。
#   S3 新迁移必须写谓词：`db/migration/**` 中凡含 `INSERT INTO llm_provider_model`
#      的文件必须在「语义位置」写 `capabilities`（剥掉 SQL 注释后按词边界匹配，
#      防注释绕过）；（白名单 = 既有冻结迁移文件，只对新文件生效）
#      —— `V112` 摘掉 capabilities 列默认值后，不写谓词的新插入行会 NOT NULL 违约。
#   S4 技能包引用边词表闭合（G-022，2026-10-15）：内置技能包 frontmatter 的
#      `requiredBaseCapabilities` 元素必须 ∈ `predicateVocabulary()`
#      （`SkillCapabilityRequirements` 登记的 reasonING/webSearch/toolCalling）
#      ——拼错的谓词会**放宽**而非收紧匹配（空提供集的包含判定恒真），
#      属放宽型失效方向，必须 fail-closed。
#   S5 技能包白名单同步（G-022）：`SkillPackageParser.KNOWN_KEYS` 必须含
#      `requiredBaseCapabilities`，且 `SkillPackage` record 的分量数与之匹配
#      ——防「frontmatter 加了键但解析器不认」（= 契约半改，最难发现）。
#
# 地面真值：
#   · 后端词表      helloai-core/src/main/java/com/helloai/core/agent/AgentSkillDeriver.java
#   · 前端词表      helloai-ui/src/constants/agentSkills.ts
#   · 谓词映射      helloai-core/src/main/java/com/helloai/core/agent/SkillCapabilityRequirements.java
#   · 技能包解析    helloai-core/src/main/java/com/helloai/core/agent/skill/SkillPackageParser.java
#   · 技能包 record helloai-core/src/main/java/com/helloai/core/agent/skill/SkillPackage.java
#   · 内置技能包    helloai-core/src/main/resources/skills/plugins/*.md
#   · 迁移目录      helloai-start/src/main/resources/db/migration/
#
# 用法：
#   bash scripts/shell/verify-skill-capability-parity.sh                        # EXIT=0 全部一致
#   bash scripts/shell/verify-skill-capability-parity.sh --inject-missing-skill # 反证①：前端词表少一项 ⇒ EXIT≠0
#   bash scripts/shell/verify-skill-capability-parity.sh --inject-ghost-column  # 反证②：某源文件写退役列 ⇒ EXIT≠0
#   bash scripts/shell/verify-skill-capability-parity.sh --inject-legacy-insert # 反证③：新增只写旧列的迁移 ⇒ EXIT≠0
#   bash scripts/shell/verify-skill-capability-parity.sh --inject-comment-bypass # 反证④：旧列 + 注释含 capabilities ⇒ EXIT≠0（QA-M9-02 回归锁）
#   bash scripts/shell/verify-skill-capability-parity.sh --inject-bad-predicate  # 反证⑤：技能包声明未登记谓词 ⇒ EXIT≠0
#   bash scripts/shell/verify-skill-capability-parity.sh --inject-unknown-key    # 反证⑥：解析器白名单缺键 ⇒ EXIT≠0
#
# 零外部依赖（bash + grep/awk/sed），CI 可跑。
# 【实现约定】刻意不使用 `while read … done < <(…)` 形态：本仓 Windows/Git Bash 下该形态
#   与循环体内命令替换组合会偶发挂起（实测），故一律用「先取列表 → 纯管道/for over array」。
# ============================================================
set -uo pipefail
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

JAVA_DERIVER="$ROOT/helloai-core/src/main/java/com/helloai/core/agent/AgentSkillDeriver.java"
JAVA_REQ="$ROOT/helloai-core/src/main/java/com/helloai/core/agent/SkillCapabilityRequirements.java"
JAVA_PARSER="$ROOT/helloai-core/src/main/java/com/helloai/core/agent/skill/SkillPackageParser.java"
JAVA_PKG="$ROOT/helloai-core/src/main/java/com/helloai/core/agent/skill/SkillPackage.java"
SKILL_PKG_DIR="$ROOT/helloai-core/src/main/resources/skills/plugins"
TS="$ROOT/helloai-ui/src/constants/agentSkills.ts"
MIG_DIR="$ROOT/helloai-start/src/main/resources/db/migration"
SELF="$ROOT/scripts/shell/verify-skill-capability-parity.sh"

# S2：允许出现退役列名的迁移版本（其余位置一律 FAIL）
ALLOWED_MIG_RE="^${MIG_DIR}/V(52|108|109|110|112)__"
# S3：既有「冻结」迁移（含 INSERT INTO llm_provider_model 但不含 capabilities）——只对新文件生效
FROZEN_INSERT_MIG_RE='/(V49|V61|V108|V109)__'
# S2 扫描的源文件根（天然排除 doc/ · .qoder/ · .tmp/ · tmp/ · logs/ · data/ 等非源目录）
SCAN_ROOTS=(
  "$ROOT/helloai-api"
  "$ROOT/helloai-common"
  "$ROOT/helloai-core"
  "$ROOT/helloai-job"
  "$ROOT/helloai-mq"
  "$ROOT/helloai-start"
  "$ROOT/helloai-ui/src"
  "$ROOT/scripts"
)

# ---------- 反证开关（只读源码、不落库；注入物落仓内 tmp/，已 .gitignore） ----------
INJECT_MODE="${1:-}"
TMP_FILES=()
cleanup() {
  local f
  for f in ${TMP_FILES[@]+"${TMP_FILES[@]}"}; do
    if [[ -n "$f" && -f "$f" ]]; then rm -f "$f"; fi
  done
  return 0
}
trap cleanup EXIT

EXTRA_S2_FILES=()
EXTRA_MIG_FILES=()
EXTRA_PKG_FILES=()
if [[ "$INJECT_MODE" == "--inject-missing-skill" ]]; then
  # 反证①：前端词表删掉 thinking ⇒ 三源应失配
  mkdir -p "$ROOT/tmp"
  TMP_TS="$ROOT/tmp/.agentSkills-inject.ts"
  grep -v "value: 'thinking'" "$TS" > "$TMP_TS"
  TMP_FILES+=("$TMP_TS")
  TS="$TMP_TS"
elif [[ "$INJECT_MODE" == "--inject-ghost-column" ]]; then
  # 反证②：在源侧注入一条对退役列的引用 ⇒ S2 应 FAIL
  mkdir -p "$ROOT/tmp"
  TMP_GHOST="$ROOT/tmp/.ghost-column-inject.java"
  printf '// ghost reference: available_optional_skills\n' > "$TMP_GHOST"
  TMP_FILES+=("$TMP_GHOST")
  EXTRA_S2_FILES+=("$TMP_GHOST")
elif [[ "$INJECT_MODE" == "--inject-legacy-insert" ]]; then
  # 反证③：注入一个只写旧列、不写 capabilities 的「新迁移」 ⇒ S3 应 FAIL
  mkdir -p "$ROOT/tmp"
  TMP_MIG="$ROOT/tmp/.legacy-insert-inject.sql"
  printf "INSERT INTO llm_provider_model (id, model_name) VALUES (1, 'x');\n" > "$TMP_MIG"
  TMP_FILES+=("$TMP_MIG")
  EXTRA_MIG_FILES+=("$TMP_MIG")
elif [[ "$INJECT_MODE" == "--inject-comment-bypass" ]]; then
  # 反证④（QA-M9-02 回归锁）：注入「写旧列 + 注释里出现 capabilities 字样」的新迁移
  #   ⇒ S3 必须仍 FAIL（证明判定落在语义位置，不被注释绕过；修复前该场景会被漏放）
  mkdir -p "$ROOT/tmp"
  TMP_MIG_C="$ROOT/tmp/.comment-bypass-inject.sql"
  {
    printf -- "-- capabilities 仅出现在行注释里，并未写入列清单\n"
    printf "INSERT INTO llm_provider_model (id, model_name) VALUES (1, 'x');\n"
    printf "/* block comment also mentioning capabilities, still not a column */\n"
  } > "$TMP_MIG_C"
  TMP_FILES+=("$TMP_MIG_C")
  EXTRA_MIG_FILES+=("$TMP_MIG_C")
elif [[ "$INJECT_MODE" == "--inject-bad-predicate" ]]; then
  # 反证⑤（G-022）：注入一个声明**未登记谓词**的技能包 ⇒ S4 应 FAIL
  #   （必须真会拦：空提供集的 containsAll 恒真 ⇒ 拼错的谓词会**放宽**匹配，
  #     是放宽型失效方向，漏放即等于没有引用边约束）
  mkdir -p "$ROOT/tmp"
  TMP_PKG_MD="$ROOT/tmp/.bad-predicate-inject.md"
  {
    printf -- '---\n'
    printf 'name: eng-bad-predicate\n'
    printf 'version: "1.0.0"\n'
    printf 'description: "反证用：声明未登记谓词"\n'
    printf 'requiredBaseCapabilities:\n'
    printf '  - totallyBogusPredicate\n'
    printf -- '---\n\n# 正文\n'
  } > "$TMP_PKG_MD"
  TMP_FILES+=("$TMP_PKG_MD")
  EXTRA_PKG_FILES+=("$TMP_PKG_MD")
elif [[ "$INJECT_MODE" == "--inject-unknown-key" ]]; then
  # 反证⑥（G-022）：把解析器白名单里的 requiredBaseCapabilities 抹掉 ⇒ S5 应 FAIL
  #   （模拟「frontmatter 加了键但解析器不认」= 契约半改，最难发现的一类漂移）
  mkdir -p "$ROOT/tmp"
  TMP_PARSER="$ROOT/tmp/.parser-no-key-inject.java"
  grep -v '"requiredBaseCapabilities"' "$JAVA_PARSER" \
    | sed 's|"requiredTools", "requiredBaseCapabilities",|"requiredTools",|' \
    | sed 's|"requiredTools", "requiredBaseCapabilities"|"requiredTools"|' > "$TMP_PARSER"
  TMP_FILES+=("$TMP_PARSER")
  JAVA_PARSER="$TMP_PARSER"
elif [[ -n "$INJECT_MODE" ]]; then
  printf '[verify-skill-capability-parity] 未知反证开关：%s\n' "$INJECT_MODE" >&2
  exit 64
fi

FAILS=0
fail() { printf '[FAIL] %s\n' "$1"; FAILS=$((FAILS + 1)); }
ok()   { printf '[ OK ] %s\n' "$1"; }
line() { printf '     %s\n' "$1"; }

# ============================================================
# S1 三源同集合
# ============================================================

# 后端 STANDARD_SKILLS（Set.of( ... ) 块内的引号 token）
backend_skills() {
  awk '/STANDARD_SKILLS = Set\.of\(/ { f = 1 } f { print } f && /\);/ { exit }' "$JAVA_DERIVER" \
    | grep -oE '"[a-z0-9-]+"' | tr -d '"' | sort -u
}

# 前端 AGENT_SKILL_OPTIONS 的 value
frontend_skills() {
  grep -oE "value: *'[a-z0-9-]+'" "$TS" | sed -E "s/.*'([a-z0-9-]+)'.*/\1/" | sort -u
}

# REQUIRED 映射键集（Map.of( ... ) 块内的首个引号 token）
required_skills() {
  awk '/REQUIRED = Map\.of\(/ { f = 1; next } f && /^[[:space:]]*\);/ { exit } f { print }' "$JAVA_REQ" \
    | grep -oE '"[a-z0-9-]+"' | tr -d '"' | sort -u
}

BE_SKILLS="$(backend_skills)"
FE_SKILLS="$(frontend_skills)"
RQ_SKILLS="$(required_skills)"

[[ -z "$BE_SKILLS" ]] && fail "S1 后端 STANDARD_SKILLS 解析为空（解析逻辑失配源码形态？）"
[[ -z "$FE_SKILLS" ]] && fail "S1 前端 AGENT_SKILL_OPTIONS 解析为空（解析逻辑失配源码形态？）"
[[ -z "$RQ_SKILLS" ]] && fail "S1 REQUIRED 键集解析为空（解析逻辑失配源码形态？）"

if [[ -n "$BE_SKILLS" && "$BE_SKILLS" == "$FE_SKILLS" && "$BE_SKILLS" == "$RQ_SKILLS" ]]; then
  ok "S1 三源同集合（共 $(printf '%s\n' "$BE_SKILLS" | grep -c .) 项）：$(printf '%s' "$BE_SKILLS" | tr '\n' ' ')"
else
  fail "S1 三源技能集合不一致："
  line "后端 STANDARD_SKILLS   : $(printf '%s' "$BE_SKILLS" | tr '\n' ' ')"
  line "前端 AGENT_SKILL_OPTIONS: $(printf '%s' "$FE_SKILLS" | tr '\n' ' ')"
  line "REQUIRED 键集          : $(printf '%s' "$RQ_SKILLS" | tr '\n' ' ')"
fi

# ============================================================
# S2 两列退役无回写
# ============================================================
S2_HITS="$(
  grep -rlE 'capability_skills|available_optional_skills' \
    --include='*.java' --include='*.kt' --include='*.ts' --include='*.vue' --include='*.js' \
    --include='*.sh' --include='*.ps1' --include='*.py' --include='*.sql' --include='*.xml' \
    --include='*.yml' --include='*.yaml' --include='*.properties' --include='*.json' \
    --exclude-dir=target --exclude-dir=node_modules --exclude-dir=dist \
    ${SCAN_ROOTS[@]+"${SCAN_ROOTS[@]}"} 2>/dev/null
)"
# 注入的反证文件（不在扫描根内的临时物）
for f in ${EXTRA_S2_FILES[@]+"${EXTRA_S2_FILES[@]}"}; do
  S2_HITS="$(printf '%s\n%s\n' "$S2_HITS" "$f")"
done

S2_GHOSTS="$(printf '%s\n' "$S2_HITS" \
  | grep -v '^$' \
  | grep -vxF "$SELF" \
  | grep -vE "$ALLOWED_MIG_RE" || true)"

S2_GHOST_COUNT="$(printf '%s\n' "$S2_GHOSTS" | grep -c . || true)"
if [[ "$S2_GHOST_COUNT" == 0 ]]; then
  ok "S2 V52 两列无回写（仅存在于白名单迁移 V52/V108/V109/V110/V112 与守卫自身）"
else
  printf '%s\n' "$S2_GHOSTS" | sed "s#^${ROOT}/##" | sed 's/^/[FAIL] S2 引用了退役列 capability_skills \/ available_optional_skills：/'
  FAILS=$((FAILS + S2_GHOST_COUNT))
fi

# ============================================================
# S3 新迁移必须写谓词（capabilities）
# ============================================================
# 【QA-M9-02 修复】判定必须落在「语义位置」，不能是整文件子串匹配——
#   否则「写旧列 + 注释里出现 capabilities 字样」的新迁移会被误判为已写谓词。
#   故：先剥掉 SQL 注释（`/* … */` 跨行块注释 + `--` 行注释），再要求 `capabilities`
#   以词边界出现（`capability_skills` / `capabilities_x` 这类标识符子串不算）。
#   与 S2 的职责边界：S2 管「退役列不得回写」；S3 只管「新 INSERT 是否真的写了 capabilities 列」。
strip_sql_comments() {
  awk '
    { buf = buf $0 "\n" }
    END {
      while (match(buf, /\/\*([^*]|\*[^\/])*\*\//)) {
        buf = substr(buf, 1, RSTART - 1) substr(buf, RSTART + RLENGTH)
      }
      n = split(buf, lines, "\n")
      for (i = 1; i <= n; i++) { sub(/--.*/, "", lines[i]); print lines[i] }
    }
  ' "$1"
}
# 该文件在「语义位置」是否真的写了 capabilities 列（注释内出现不算）
has_capabilities_column() {
  strip_sql_comments "$1" | grep -qE '(^|[^A-Za-z0-9_])capabilities([^A-Za-z0-9_]|$)'
}
INSERT_RE='INSERT[[:space:]]+INTO[[:space:]]+llm_provider_model([^_A-Za-z0-9]|$)'
# 含「精确表名 INSERT」的迁移，去掉冻结白名单后，取其中「未写 capabilities」者
S3_CANDIDATES="$(grep -lE "$INSERT_RE" "$MIG_DIR"/V*.sql 2>/dev/null | grep -vE "$FROZEN_INSERT_MIG_RE" || true)"
S3_OFFENDERS=""
# shellcheck disable=SC2086  # 迁移路径无空格，安全按词拆分
for f in $S3_CANDIDATES; do
  if ! has_capabilities_column "$f"; then
    S3_OFFENDERS="$(printf '%s\n%s\n' "$S3_OFFENDERS" "$f")"
  fi
done
for f in ${EXTRA_MIG_FILES[@]+"${EXTRA_MIG_FILES[@]}"}; do
  if grep -qE "$INSERT_RE" "$f" && ! has_capabilities_column "$f"; then
    S3_OFFENDERS="$(printf '%s\n%s\n' "$S3_OFFENDERS" "$f")"
  fi
done

S3_OFFENDERS="$(printf '%s\n' "$S3_OFFENDERS" | grep -v '^$' || true)"
S3_COUNT="$(printf '%s\n' "$S3_OFFENDERS" | grep -c . || true)"
if [[ "$S3_COUNT" == 0 ]]; then
  ok "S3 含 INSERT INTO llm_provider_model 的迁移均显式写 capabilities（冻结白名单：V49/V61/V108/V109）"
else
  printf '%s\n' "$S3_OFFENDERS" | sed "s#^${ROOT}/##" | sed 's/^/[FAIL] S3 迁移写 llm_provider_model 却未含 capabilities：/'
  FAILS=$((FAILS + S3_COUNT))
fi

# ============================================================
# S4 技能包引用边词表闭合（G-022）
# ============================================================
# 为什么必须 fail-closed：判定式是 `requires ⊆ provides`，
# 若 requires 里的谓词名拼错，则该元素在 provides 中**必然查不到**——
#   但我们判定的是「**全部** requires 都被 provides 覆盖」，
#   未登记谓词会让覆盖失败（安全方向）。
#   真正的放宽风险在另一处：`unsatisfiedPackages` / 批量判定若把未登记谓词
#   **静默丢弃**，则技能变成「无条件可派」＝约束失效。
#   故此处要求：frontmatter 声明的谓词必须 ∈ 已登记词表，非法即 FAIL。
predicate_vocabulary() {
  # 真值源 =谓词常量**声明**（`private static final String PREDICATE_X = "x";`），
  # 不用 `predicateVocabulary()` 的方法体——后者是 `return Set.of(...)` 单行形态，
  # 解析它会随代码排版漂移；常量声明是「词 ⇒ 值」的唯一登记处，且换行安全。
  grep -oE 'PREDICATE_[A-Z_]+[[:space:]]*=[[:space:]]*"[A-Za-z0-9]+"' "$JAVA_REQ" \
    | sed -E 's/.*=[[:space:]]*"([A-Za-z0-9]+)"/\1/' | sort -u
}

# 提取某技能包 frontmatter 里 requiredBaseCapabilities 的元素
pkg_required_caps() {
  awk '
    BEGIN { in_fm = 0; in_key = 0 }
    /^---[[:space:]]*$/ { if (in_fm) { exit } else { in_fm = 1 } }
    in_fm && /^requiredBaseCapabilities[[:space:]]*:/ { in_key = 1; rest = $0; sub(/^requiredBaseCapabilities[[:space:]]*:[[:space:]]*/, "", rest); if (rest != "") print rest; next }
    in_fm && in_key {
      if ($0 ~ /^[[:space:]]*-[[:space:]]*/) { sub(/^[[:space:]]*-[[:space:]]*/, ""); print }
      else if ($0 ~ /^[[:space:]]*$/ || $0 ~ /^[[:space:]]*#/) { next }
      else { in_key = 0 }
    }
  ' "$1" | sed -E 's/^["'"'"']//; s/["'"'"']$//' | grep -v '^$' | grep -v '^\[' | sort -u
}

PRED_VOCAB="$(predicate_vocabulary)"
[[ -z "$PRED_VOCAB" ]] && fail "S4 predicateVocabulary 解析为空（解析逻辑失配源码形态？）"

PKG_FILES="$(ls -1 "$SKILL_PKG_DIR"/*.md 2>/dev/null || true)"
for f in ${EXTRA_PKG_FILES[@]+"${EXTRA_PKG_FILES[@]}"}; do
  PKG_FILES="$(printf '%s\n%s\n' "$PKG_FILES" "$f")"
done

S4_OFFENDERS=""
for f in $PKG_FILES; do
  [[ -f "$f" ]] || continue
  for p in $(pkg_required_caps "$f"); do
    if ! printf '%s\n' "$PRED_VOCAB" | grep -qxF "$p"; then
      S4_OFFENDERS="$(printf '%s\t%s\n%s' "$S4_OFFENDERS" "${f#"$ROOT"/}" "$p")"
    fi
  done
done
S4_OFFENDERS="$(printf '%s' "$S4_OFFENDERS" | sed '/^$/d' | sort -u || true)"
S4_COUNT="$(printf '%s\n' "$S4_OFFENDERS" | grep -c . || true)"
if [[ "$S4_COUNT" == 0 ]]; then
  ok "S4 技能包 requiredBaseCapabilities 谓词均已登记（词表：$(printf '%s' "$PRED_VOCAB" | tr '\n' ' ')）"
else
  printf '%s\n' "$S4_OFFENDERS" | sed 's/^/[FAIL] S4 技能包声明未登记谓词：/'
  FAILS=$((FAILS + S4_COUNT))
fi

# ============================================================
# S5 技能包解析器白名单同步（G-022）
# ============================================================
S5_KEY='requiredBaseCapabilities'
# 5a 解析器 KNOWN_KEYS 必须含该键
if grep -qE "\"$S5_KEY\"" "$JAVA_PARSER"; then
  ok "S5a SkillPackageParser.KNOWN_KEYS 含 $S5_KEY（契约 8→9 键已同步）"
else
  fail "S5a SkillPackageParser.KNOWN_KEYS 缺 $S5_KEY —— frontmatter 加了键但解析器不认 ⇒ 技能包被判 corrupt（G-022 契约半改）"
fi
# 5b SkillPackage record 必须有该分量（分量名含该键）
if grep -qE "List<String> $S5_KEY" "$JAVA_PKG"; then
  ok "S5b SkillPackage record 含 $S5_KEY 分量"
else
  fail "S5b SkillPackage record 缺 $S5_KEY 分量（解析器认了但 record 接不住 ⇒ 反序列化丢字段）"
fi

# ============================================================
echo "== verify-skill-capability-parity: S1 三源同集合 / S2 两列退役无回写 / S3 新迁移写谓词 / S4 技能包谓词词表闭合 / S5 解析器键同步 —— $([ "$FAILS" -eq 0 ] && echo 全部通过 || echo "${FAILS} 处失配")"
exit $FAILS
