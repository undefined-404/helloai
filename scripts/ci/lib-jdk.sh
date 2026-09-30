#!/usr/bin/env bash
# ============================================================================
# HelloAI CI —— 可用 JDK 解析库（被 ci-gate.sh / check-arch-freeze.sh 复用）
#
# 背景（2026-09-29 架构与质量审计实测）：
#   本机默认 JAVA_HOME 指向 ms-17.0.19，该 JDK 在本机必然触发
#   JVM EXCEPTION_ACCESS_VIOLATION，导致 mvn 编译/测试整体失败。
#   这是项目文档中大量「NOT RUN」的直接技术根因，且属**可一键修复的环境问题**。
#   ms-17.0.20.1 实测可用（编译 BUILD SUCCESS / 单测 0 失败）。
#
# 解析优先级：
#   1. $HELLOAI_JAVA_HOME   （显式指定，最高优先级，供 CI 与换机场景使用）
#   2. $JAVA_HOME           （须不在崩溃黑名单内，且**实测大版本为 17**）
#   3. 探测 ~/.jdks/* 中可用的 JDK 17（优先 17.0.20 系，跳过黑名单）
#   4. 探测常见系统安装位置（/usr/lib/jvm 等）
# 解析失败 -> 返回码 1，并打印可执行的修复指引。
#
# 2026-09-29 补强（自有主机 CI 场景）：
#   原实现只做「路径黑名单」判断，不校验 java 实际版本。这在 Gitee Go 自有主机上会出事 ——
#   Gitee 的 Agent 会自带一份 JDK 8（…/gitee_go_agent/jdk4agent/jdk1.8.0_251）并可能占用
#   JAVA_HOME；旧逻辑会把它当作可用 JDK 返回，Maven 随即因 `<release>17</release>` 失败。
#   现改为**运行 java -version 实测大版本必须为 17**，黑名单仅作为快速短路。
#
# 用法：
#   source "$(dirname "${BASH_SOURCE[0]}")/lib-jdk.sh"
#   JAVA_HOME="$(helloai_resolve_java_home)" || exit 1
# ============================================================================

# 已知在本机必然 JVM 崩溃的 JDK（按路径子串匹配；不做版本号推断，避免误伤）
HELLOAI_JDK_DENYLIST=(
  "ms-17.0.19"
)

helloai_jdk_is_denied() {
  local p="$1" d
  [ -n "$p" ] || return 1
  for d in "${HELLOAI_JDK_DENYLIST[@]}"; do
    case "$p" in *"$d"*) return 0 ;; esac
  done
  return 1
}

# 取 JDK 大版本号（实测 java -version，非按路径推断）
#   17.0.20.1 -> 17   /   1.8.0_251 -> 8   /   24.0.1 -> 24
#   无法解析 -> 返回非 0
helloai_jdk_major() {
  local p="$1" raw first second
  [ -x "$p/bin/java" ] || return 1
  raw="$("$p/bin/java" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -1)"
  [ -n "$raw" ] || return 1
  first="${raw%%.*}"
  if [ "$first" = "1" ]; then
    second="$(printf '%s' "$raw" | cut -d. -f2)"
    [ -n "$second" ] || return 1
    printf '%s\n' "$second"
  else
    printf '%s\n' "$first"
  fi
}

# 本项目要求 JDK 17（根 pom.xml: <java.version>17</java.version>）
HELLOAI_JDK_REQUIRED_MAJOR=17

helloai_jdk_is_usable() {
  local p="$1"
  [ -n "$p" ] || return 1
  [ -x "$p/bin/java" ] || return 1
  helloai_jdk_is_denied "$p" && return 1
  [ "$(helloai_jdk_major "$p")" = "$HELLOAI_JDK_REQUIRED_MAJOR" ] || return 1
  return 0
}

helloai_resolve_java_home() {
  local cands=()
  local p c

  if helloai_jdk_is_usable "${HELLOAI_JAVA_HOME:-}"; then
    printf '%s\n' "$HELLOAI_JAVA_HOME"; return 0
  fi
  if helloai_jdk_is_usable "${JAVA_HOME:-}"; then
    printf '%s\n' "$JAVA_HOME"; return 0
  fi

  # 探测常见 JDK 安装位置。
  # 注意：不再用「路径里是否含 17」当判据（会漏掉 /opt/java/latest 这类命名），
  # 统一交给 helloai_jdk_is_usable 实测 java -version 大版本。
  for p in "$HOME"/.jdks/* /usr/lib/jvm/* /opt/java/* /opt/jdk*; do
    [ -e "$p" ] || continue
    if helloai_jdk_is_usable "$p"; then cands+=("$p"); fi
  done

  # 优先实测可用的 17.0.20 系
  for c in ${cands[@]+"${cands[@]}"}; do
    case "$c" in *17.0.20*) printf '%s\n' "$c"; return 0 ;; esac
  done
  for c in ${cands[@]+"${cands[@]}"}; do
    printf '%s\n' "$c"; return 0
  done

  return 1
}

helloai_print_jdk_help() {
  cat >&2 <<'EOF'
[ci-gate] 无法解析出可用的 JDK 17（本项目要求大版本 = 17）。

当前探测到的问题：
  - JAVA_HOME="${JAVA_HOME:-<未设置>}"（若非 17，会被直接跳过）
  - 已知 ms-17.0.19 在本机必然 JVM 崩溃（EXCEPTION_ACCESS_VIOLATION），已列入黑名单

常见原因（按命中概率）：
  a) 落在 Gitee Go 自有主机上：Agent 自带 JDK 8（jdk4agent/jdk1.8.0_251）并可能占用 JAVA_HOME，
     而主机上**没装 JDK 17**。请在该主机执行：bash scripts/ci/host-prepare.sh --install
  b) 本机默认 JAVA_HOME 指向崩溃版本 ms-17.0.19。

请任选一种修复方式：
  1) 临时指定（本机实测可用）：
       export HELLOAI_JAVA_HOME="$HOME/.jdks/ms-17.0.20.1"
  2) 修正默认 JAVA_HOME：
       export JAVA_HOME="$HOME/.jdks/ms-17.0.20.1"
  3) 若已安装其它 JDK 17，指向它即可（不得使用 ms-17.0.19）
  4) Linux 主机（root）：
       apt-get install -y openjdk-17-jdk
EOF
}
