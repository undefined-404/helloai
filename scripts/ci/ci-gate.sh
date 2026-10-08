#!/usr/bin/env bash
# ============================================================================
# HelloAI CI 门禁 —— 「构建 + 真实单测 + 用例数>0 + 架构漂移冻结 + 前端 + B 级集成」
#
# 设计原则（对应 2026-09-29 审计建议 1/2/3/7/5）：
#   1. 门禁逻辑只写一遍，CI 与本地共用本脚本；平台上只放薄封装。
#   2. 必须显式 -DskipTests=false —— 根 POM 默认 <skipTests>true</skipTests>，
#      不加此参数时 `mvn test` 会跑 0 个用例并「假绿」通过。
#   3. 用例数必须 > 0，否则判失败（把「零用例假绿」变成显式错误）。
#   4. 跨域反向依赖计数只降不升（见 check-arch-freeze.sh）。
#   5. 固定可用 JDK，消除 ms-17.0.19 崩溃类「无法验证」。
#   6. B 级集成（Testcontainers PG/Redis/RabbitMQ）无 Docker 时输出 NOT RUN 而非 FAIL
#      （协作规约 §27 语义：不可用环境不制造假失败，门禁仍对真实回归负责）。
#   7. 时区固定为 Asia/Shanghai（业务口径，与 docker-compose.server.yml 的 TZ 一致）：
#      CI runner 默认 UTC、开发机默认 +08:00，会让「时间语义」用例云端失败、本地通过
#      （2026-10-08 实测）。可用 HELLOAI_CI_TZ 覆盖。
#   8. 失败项在 GitHub Actions 上输出 ::error:: 注解：否则云端只有一句
#      "Process completed with exit code 1"，看不到到底挂了哪一项。
#
# 用法：
#   bash scripts/ci/ci-gate.sh                 # 全量：所有模块 clean test + 前端
#   bash scripts/ci/ci-gate.sh --quick         # 快速：仅 helloai-core 单个测试类（本地自检）
#   bash scripts/ci/ci-gate.sh --skip-ui       # 跳过前端
#   bash scripts/ci/ci-gate.sh --offline       # 追加 mvn -o（无网/依赖已缓存时）
#   bash scripts/ci/ci-gate.sh --no-clean      # 应用在跑时用：跳过 clean，保留各模块 target/classes
#   bash scripts/ci/ci-gate.sh --quick --skip-ui
#
# 【串行化约定】同一工作树同一时刻只允许一个 mvn/门禁进程；并发会互相删
#   target/classes 致 ClassNotFoundException（2026-10-06 实测两次踩踏）。
#
# 退出码：0 全部门禁通过 / 非 0 表示具体门禁失败（见输出 [FAIL] 行）
# ============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$ROOT" || exit 1

# shellcheck source=lib-jdk.sh
source "$SCRIPT_DIR/lib-jdk.sh"

MODE_FULL=1
SKIP_UI=0
OFFLINE=0
NO_CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --quick)     MODE_FULL=0 ;;
    --skip-ui)   SKIP_UI=1 ;;
    --offline)   OFFLINE=1 ;;
    --no-clean)  NO_CLEAN=1 ;;
    -h|--help)   sed -n '2,26p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf '[ci-gate] 未知参数：%s\n' "$arg" >&2; exit 64 ;;
  esac
done

# --no-clean：应用在跑时用。跳过多模块 `clean`，保留各模块 target/classes。
# 应用的 classpath 指向 */target/classes（见启动脚本），clean 会删掉它们 →
# 正在运行的应用抛 ClassNotFoundException / NoClassDefFoundError。
#
# 【串行化约定】同一工作树同一时刻只允许一个 mvn/门禁进程；并发会互相删
#   target/classes 致 ClassNotFoundException（2026-10-06 实测两次踩踏）。
CLEAN_GOAL="clean"
if [ "$NO_CLEAN" = "1" ]; then
  CLEAN_GOAL=""
  printf '[ci-gate] 已启用 --no-clean：跳过 clean，保留 target/classes\n'
fi

MVN_ARGS=(-B --no-transfer-progress)
[ "$OFFLINE" = "1" ] && MVN_ARGS+=(-o)

# ---------------------------------------------------------------------------
# 时区固定（见头部设计原则 7）
#   业务口径 Asia/Shanghai，与 docker-compose.server.yml 的 TZ 保持一致。
#   这里的 export 覆盖本脚本派生的所有进程（mvn、node、docker CLI...）；
#   测试 JVM 的另一层保险在根 pom 的 surefire <user.timezone>（IDE 直接跑也生效）。
# ---------------------------------------------------------------------------
export TZ="${HELLOAI_CI_TZ:-Asia/Shanghai}"
printf '[ci-gate] 时区固定：TZ=%s（可用 HELLOAI_CI_TZ 覆盖）\n' "$TZ"

FAILURES=0
step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
ok()   { printf '  [ OK ] %s\n' "$1"; }
bad()  {
  FAILURES=$((FAILURES + 1))
  printf '  [FAIL] %s\n' "$1"
  # GitHub Actions：同时输出 ::error:: 注解，云端 Annotations 可直接看到失败项；
  # 本地/其它平台无副作用。% 需转义为 %25，否则注解正文会被 GitHub 误解析。
  if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    printf '::error title=CI 门禁失败::%s\n' "${1//%/%25}"
  fi
}

# ---------------------------------------------------------------------------
# 门禁 0：解析可用 JDK（消除 ms-17.0.19 崩溃）
# ---------------------------------------------------------------------------
step "门禁 0 / 5：解析可用 JDK"
RESOLVED_JDK="$(helloai_resolve_java_home)"
if [ -z "$RESOLVED_JDK" ]; then
  bad "未能解析出可用 JDK 17"
  helloai_print_jdk_help
  exit 1
fi
export JAVA_HOME="$RESOLVED_JDK"
export PATH="$JAVA_HOME/bin:$PATH"
printf '  JAVA_HOME = %s\n' "$JAVA_HOME"
java -version 2>&1 | sed 's/^/  /'
ok "JDK 已固定（跳过已知崩溃的 ms-17.0.19）"

# ---------------------------------------------------------------------------
# 门禁 1：构建 + 真实单测（显式 -DskipTests=false）
# ---------------------------------------------------------------------------
step "门禁 1 / 5：构建与单元测试（-DskipTests=false）"

# 先清掉历史 surefire 报告：否则「用例数 > 0」断言可能被上一次构建的残留报告
# 误满足（本地实测踩到：上游模块被 SKIPPED，却因 target 残留报告数出 12 个用例）。
STALE=0
while IFS= read -r d; do
  [ -n "$d" ] || continue
  rm -rf "$d" && STALE=$((STALE + 1))
done < <(find "$ROOT" -type d -path '*/target/surefire-reports' 2>/dev/null)
printf '  已清理历史 surefire 报告目录：%s 个\n' "$STALE"

if [ "$MODE_FULL" = "1" ]; then
  if [ "$NO_CLEAN" = "1" ]; then
    printf '  范围：全部模块 test（--no-clean：跳过 clean）\n'
  else
    printf '  范围：全部模块 clean test\n'
  fi
  # shellcheck disable=SC2086  # $CLEAN_GOAL 空串时按词拆分自动省略（实现 --no-clean）
  MVN_CMD=(mvn "${MVN_ARGS[@]}" -DskipTests=false $CLEAN_GOAL test)
else
  printf '  范围：--quick（仅 helloai-core 的 SubTaskStateMachineTest）\n'
  # shellcheck disable=SC2086
  MVN_CMD=(mvn "${MVN_ARGS[@]}" -DskipTests=false -pl helloai-core -am \
            -Dtest=SubTaskStateMachineTest \
            -DfailIfNoSpecifiedTests=false \
            -Dsurefire.failIfNoSpecifiedTests=false \
            $CLEAN_GOAL test)
fi
printf '  命令：%s\n' "${MVN_CMD[*]}"
if "${MVN_CMD[@]}"; then
  ok "构建与单测执行成功"
else
  rc=$?
  bad "构建或单测失败（exit=$rc）——上游已输出失败明细"
fi

# ---------------------------------------------------------------------------
# 门禁 2：用例数 > 0（杜绝「零用例假绿」）
# ---------------------------------------------------------------------------
step "门禁 2 / 5：用例数 > 0 断言（杜绝零用例假绿）"
# 计数口径：按 surefire XML 内的 <testcase> 元素个数，**不要**读 <testsuite tests="N"> 属性。
# 原因（2026-10-01 实测）：JUnit5 @Nested 用例会被写进外层类的 XML，但该文件的
#   tests 属性仍为 0（实测 AgentProviderResolverTest.xml：tests="0" 而 <testcase> 12 个）。
#   用属性口径汇总，本仓库会漏计约 639 个用例（1144 vs 真实 1783）；按 <testcase> 计
#   才与「实际执行了多少个测试」一致（含 skipped，skipped 同样产出 <testcase>）。
TOTAL_TESTS=0
SUITE_FILES=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  n="$(grep -o '<testcase' "$f" 2>/dev/null | wc -l | tr -d '[:space:]')"
  [ -n "$n" ] || n=0
  TOTAL_TESTS=$((TOTAL_TESTS + n))
  SUITE_FILES=$((SUITE_FILES + 1))
done < <(find "$ROOT" -path '*/target/surefire-reports/TEST-*.xml' -type f 2>/dev/null)

printf '  surefire 报告文件：%s 个\n' "$SUITE_FILES"
printf '  累计用例数（按 <testcase> 计）：%s\n' "$TOTAL_TESTS"
if [ "$TOTAL_TESTS" -gt 0 ]; then
  ok "用例数 $TOTAL_TESTS > 0"
else
  bad "用例数为 0 —— 极可能是漏传 -DskipTests=false（根 POM 默认跳过测试）"
fi

# ---------------------------------------------------------------------------
# 门禁 3：架构漂移冻结（跨域反向依赖只降不升）
# ---------------------------------------------------------------------------
step "门禁 3 / 5：架构漂移冻结校验"
if bash "$SCRIPT_DIR/check-arch-freeze.sh"; then
  ok "跨域反向依赖计数未超冻结基线"
else
  bad "跨域反向依赖计数超出冻结基线（新增反向依赖须显式评审并更新基线）"
fi

# ---------------------------------------------------------------------------
# 门禁 4：前端类型检查 + 构建
# ---------------------------------------------------------------------------
step "门禁 4 / 5：前端 type-check 与 build"
if [ "$SKIP_UI" = "1" ]; then
  printf '  已按 --skip-ui 跳过\n'
elif ! command -v npm >/dev/null 2>&1; then
  bad "未找到 npm，无法执行前端门禁（如需跳过请显式加 --skip-ui）"
else
  UI_DIR="$ROOT/helloai-ui"
  if [ ! -d "$UI_DIR" ]; then
    bad "前端目录不存在：$UI_DIR"
  else
    (
      cd "$UI_DIR" || exit 1
      if [ "${HELLOAI_CI:-0}" = "1" ]; then
        # CI：装 lock 文件锁定的依赖，保证可复现
        npm ci --no-audit --no-fund
      elif [ ! -d node_modules ]; then
        npm install --no-audit --no-fund
      fi
      npm run type-check
      npm run build
    )
    if [ $? -eq 0 ]; then
      ok "前端 type-check 与 build 通过"
    else
      bad "前端门禁失败"
    fi
  fi
fi

# ---------------------------------------------------------------------------
# 门禁 5：B 级集成测试（Testcontainers PG/Redis/RabbitMQ，无 Docker 则 NOT RUN）
# ---------------------------------------------------------------------------
step "门禁 5 / 5：B 级集成测试（Testcontainers，无 Docker 则 NOT RUN）"
if [ "$MODE_FULL" != "1" ]; then
  printf '  已按 --quick 跳过（B 级集成仅全量模式执行）\n'
elif ! command -v docker >/dev/null 2>&1; then
  printf '  [NOT RUN] 未找到 docker 命令 —— 无容器环境，B 级集成跳过（协作规约 §27）\n'
elif ! docker info >/dev/null 2>&1; then
  printf '  [NOT RUN] docker 守护进程不可用 —— B 级集成跳过（协作规约 §27）\n'
else
  # 只跑 *IT 类：surefire 默认 includes 为 *Test.java 系列（不含 *IT），
  # 单测门禁（1/2）与集成门禁（5）互不污染；-am 确保上游模块就位。
  # 裸 *IT 不带引号传递：bash 数组元素中 glob 无匹配文件时不展开，安全。
  IT_PATTERN='*IT'
  MVN_IT=(mvn "${MVN_ARGS[@]}" -DskipTests=false -pl helloai-start -am \
          "-Dtest=$IT_PATTERN" \
          -Dsurefire.failIfNoSpecifiedTests=false \
          -DfailIfNoSpecifiedTests=false \
          test)
  printf '  命令：%s\n' "${MVN_IT[*]}"
  if "${MVN_IT[@]}"; then
    ok "B 级集成测试全部通过（测试类：helloai-start/src/test/java/com/helloai/it/*IT）"
  else
    bad "B 级集成测试失败（需 Docker 可用环境复现；失败明细见 surefire-reports 与上方输出）"
  fi
fi

# ---------------------------------------------------------------------------
step "门禁汇总"
if [ "$FAILURES" -eq 0 ]; then
  printf '  \033[32m全部通过（0 项失败）\033[0m\n'
  exit 0
fi
printf '  \033[31m%s 项门禁失败\033[0m\n' "$FAILURES"
exit 1
