#!/usr/bin/env bash
# ============================================================================
# HelloAI CI —— 自有主机（Gitee Go 主机组）一次性准备与自检
#
# 背景（2026-09-29）：
#   仓库 CI 原设计跑在 Gitee **云端构建机**（build@maven / build@nodejs），
#   按官方计费规则「消耗核分 = 运行分钟 × CPU 核数」，走云端会吃每月 1000 核分。
#   改用 `shell@agent` 插件把门禁整体搬到**自有主机组**执行 —— 依据官方计费规则
#   「仅当您使用 Gitee 提供的云端构建资源，且任务属于计费模型时，运行才消耗核分」，
#   自有主机执行**不消耗核分**。
#
#   代价是：云端插件那层「CentOS 8.3 基础镜像 + 阿里源」没有了，环境要自备。
#   本脚本把「自备」变成一条命令，并给出可核查的自检结论。
#
# 用法（在**目标主机**上执行）：
#   bash scripts/ci/host-prepare.sh                  # 只检测，不改动任何东西（默认，安全）
#   sudo bash scripts/ci/host-prepare.sh --install   # 安装缺失项（JDK17 / Maven / Node20）
#   sudo bash scripts/ci/host-prepare.sh --swap 2G   # 建 2G swap（4G 内存机器强烈建议）
#   可组合：sudo bash scripts/ci/host-prepare.sh --install --swap 2G
#
# 退出码：0 = 自检通过，可跑 CI；非 0 = 有阻断项未满足
# ============================================================================

set -uo pipefail

DO_INSTALL=0
SWAP_SIZE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --install) DO_INSTALL=1 ;;
    --swap)
      shift
      SWAP_SIZE="${1:-2G}"
      ;;
    -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf '[host-prepare] 未知参数：%s\n' "$1" >&2; exit 64 ;;
  esac
  shift
done

BLOCKERS=0
WARNINGS=0
pass() { printf '  [ OK ] %s\n' "$1"; }
fail() { printf '  [FAIL] %s\n' "$1"; BLOCKERS=$((BLOCKERS + 1)); }
warn() { printf '  [WARN] %s\n' "$1"; WARNINGS=$((WARNINGS + 1)); }
info() { printf '  [info] %s\n' "$1"; }
step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

is_root() { [ "$(id -u 2>/dev/null || echo 0)" = "0" ]; }

# --- 安装辅助（仅在 --install / --swap 时被调用）------------------------------
pkg_install() {
  local pkgs="$*"
  if ! is_root; then
    fail "安装 [$pkgs] 需要 root —— 请用 sudo 重跑本脚本"
    return 1
  fi
  printf '  >>> 安装：%s\n' "$pkgs"
  if have apt-get; then
    apt-get update -y && apt-get install -y $pkgs
  elif have dnf; then
    dnf install -y $pkgs
  elif have yum; then
    yum install -y $pkgs
  else
    fail "未识别包管理器（apt/dnf/yum 都没有），请手动安装：$pkgs"
    return 1
  fi
}

install_node20() {
  if ! is_root; then
    fail "安装 Node 20 需要 root —— 请用 sudo 重跑本脚本"
    return 1
  fi
  if ! have apt-get; then
    fail "请手动安装 Node 20（推荐 nvm 或 NodeSource）"
    return 1
  fi
  printf '  >>> 通过 NodeSource 官方脚本安装 Node 20\n'
  printf '      （来源：https://github.com/nodesource/distributions —— 官方推荐的 apt 方式）\n'
  if have curl; then
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  elif have wget; then
    wget -qO- https://deb.nodesource.com/setup_20.x | bash -
  else
    fail "无 curl/wget，无法安装 Node 20"
    return 1
  fi
  apt-get install -y nodejs
}

make_swap() {
  local size="${1:-2G}" file="/swapfile" mb
  if ! is_root; then
    fail "创建 swap 需要 root —— 请用 sudo 重跑本脚本"
    return 1
  fi
  case "$size" in
    *[Gg]) mb=$(( ${size%[Gg]} * 1024 )) ;;
    *[Mm]) mb=$(( ${size%[Mm]} )) ;;
    *)     printf '[host-prepare] swap 尺寸格式无法识别：%s（示例 2G / 2048M）\n' "$size" >&2; return 1 ;;
  esac
  if [ -e "$file" ]; then
    warn "$file 已存在，跳过创建（如需调整请手工处理）"
    return 0
  fi
  printf '  >>> 创建 %s swap 于 %s（dd/fallocate）\n' "$size" "$file"
  fallocate -l "$size" "$file" 2>/dev/null \
    || dd if=/dev/zero of="$file" bs=1M count="$mb" status=none \
    || { fail "创建 swapfile 失败"; return 1; }
  chmod 600 "$file"
  mkswap "$file" >/dev/null && swapon "$file" || { fail "mkswap/swapon 失败"; return 1; }
  if ! grep -qE '^\s*[^#].*\s+/swapfile\s' /etc/fstab 2>/dev/null; then
    printf '/swapfile none swap sw 0 0\n' >> /etc/fstab
    info "已写入 /etc/fstab（开机自动挂载）"
  fi
  pass "swap 已启用：$(free -h 2>/dev/null | awk '/^Swap:/{print $2}')"
}

echo "============================================================"
echo " HelloAI CI · 自有主机准备与自检"
echo " 时间：$(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================"

# ---------------------------------------------------------------------------
step "0 / 6：系统概况"
if [ -r /etc/os-release ]; then
  # shellcheck disable=SC1091
  info "OS: $(. /etc/os-release && printf '%s %s' "${NAME:-?}" "${VERSION_ID:-?}")"
else
  info "OS: $(uname -s)"
fi
info "内核: $(uname -r)   架构: $(uname -m)   主机名: $(hostname)"

if have nproc; then CPU=$(nproc); else CPU=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0); fi
info "CPU 核心: $CPU"

MEM_MB=0
SWAP_MB=0
if have free; then
  MEM_MB=$(free -m 2>/dev/null | awk '/^Mem:/{print $2}')
  SWAP_MB=$(free -m 2>/dev/null | awk '/^Swap:/{print $2}')
  info "内存: ${MEM_MB:-?} MB    Swap: ${SWAP_MB:-0} MB"
else
  warn "未找到 free，无法判断内存"
fi

if have df; then info "当前目录可用磁盘: $(df -h . 2>/dev/null | awk 'NR==2{print $4}')"; fi

if [ "${MEM_MB:-0}" -gt 0 ] && [ "${MEM_MB}" -lt 5120 ]; then
  if [ "${SWAP_MB:-0}" -eq 0 ]; then
    warn "内存 ${MEM_MB}MB 且无 Swap —— Maven（分叉 JVM）+ Node 构建峰值可能 OOM。"
    if [ -n "$SWAP_SIZE" ]; then
      make_swap "$SWAP_SIZE"
    else
      info "建议：sudo bash scripts/ci/host-prepare.sh --swap 2G"
    fi
  else
    info "内存偏小但有 Swap(${SWAP_MB}MB)，属可接受配置"
  fi
elif [ -n "$SWAP_SIZE" ]; then
  make_swap "$SWAP_SIZE"
fi

# ---------------------------------------------------------------------------
step "1 / 6：基础命令"
for c in bash git find grep sed awk; do
  if have "$c"; then pass "$c -> $(command -v "$c")"; else fail "$c 缺失（CI 门禁脚本依赖）"; fi
done
if have curl || have wget; then pass "curl/wget 可用"; else warn "curl 与 wget 均缺失（安装 Node 时需要）"; fi

# ---------------------------------------------------------------------------
step "2 / 6：JDK 17（本项目要求大版本 = 17）"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JDK_OK=0
if [ -f "$SCRIPT_DIR/lib-jdk.sh" ]; then
  # shellcheck source=lib-jdk.sh
  source "$SCRIPT_DIR/lib-jdk.sh"
  JDK="$(helloai_resolve_java_home)"
  if [ -n "${JDK:-}" ]; then
    JDK_OK=1
    pass "解析到可用 JDK 17：$JDK"
    info "版本：$("$JDK/bin/java" -version 2>&1 | head -1)"
    export JAVA_HOME="$JDK"
    export PATH="$JAVA_HOME/bin:$PATH"
  else
    fail "未找到 JDK 17（当前 JAVA_HOME=${JAVA_HOME:-<未设置>}）"
    info "注意：Gitee Agent 自带的 JDK 8（…/gitee_go_agent/jdk4agent）不能用于本项目编译"
    if [ "$DO_INSTALL" = "1" ]; then pkg_install openjdk-17-jdk; else info "修复：sudo bash scripts/ci/host-prepare.sh --install"; fi
  fi
else
  fail "未找到 $SCRIPT_DIR/lib-jdk.sh（请确认仓库已完整克隆）"
fi

# ---------------------------------------------------------------------------
step "3 / 6：Maven"
if have mvn; then
  pass "mvn -> $(command -v mvn)"
  info "$(mvn -v 2>&1 | head -1)"
else
  fail "未找到 mvn"
  if [ "$DO_INSTALL" = "1" ]; then pkg_install maven; else info "修复：sudo bash scripts/ci/host-prepare.sh --install"; fi
fi
if [ -f "$HOME/.m2/settings.xml" ]; then
  if grep -qi 'aliyun\|mirror' "$HOME/.m2/settings.xml" 2>/dev/null; then
    pass "~/.m2/settings.xml 已配置镜像"
  else
    warn "~/.m2/settings.xml 存在但未见 mirror 配置，首次构建可能很慢"
  fi
else
  warn "无 ~/.m2/settings.xml —— 首次构建从 Maven Central 拉依赖（国内较慢）。
       建议加阿里云镜像：https://maven.aliyun.com/repository/public"
fi

# ---------------------------------------------------------------------------
step "4 / 6：Node.js"
if have node; then
  NODE_MAJOR="$(node -v 2>/dev/null | sed 's/^v//' | cut -d. -f1)"
  info "node -> $(command -v node)  $(node -v 2>/dev/null)"
  if [ -n "$NODE_MAJOR" ] && [ "$NODE_MAJOR" -ge 18 ] 2>/dev/null; then
    pass "Node 大版本 $NODE_MAJOR ≥ 18（满足 Vite / vue-tsc）"
  else
    fail "Node 大版本 ${NODE_MAJOR:-?} < 18，前端门禁会失败"
    if [ "$DO_INSTALL" = "1" ]; then install_node20; fi
  fi
else
  fail "未找到 node"
  if [ "$DO_INSTALL" = "1" ]; then install_node20; else info "修复：sudo bash scripts/ci/host-prepare.sh --install"; fi
fi
if have npm; then pass "npm -> $(command -v npm)"; else fail "未找到 npm"; fi

# ---------------------------------------------------------------------------
step "5 / 6：仓库副本与克隆前提"
GATE=""
if [ -f "$PWD/scripts/ci/ci-gate.sh" ]; then
  GATE="$PWD/scripts/ci/ci-gate.sh"
else
  GATE="$(find "$HOME" /home /root -maxdepth 6 -type f -path '*/scripts/ci/ci-gate.sh' 2>/dev/null | head -1)"
fi
if [ -n "$GATE" ]; then
  pass "找到仓库：$(cd "$(dirname "$GATE")/../.." && pwd)"
else
  warn "当前未找到已克隆的仓库副本"
  info "若此处为流水线执行环境：请在任务的可视化编辑中打开「是否克隆代码」开关。
       前置条件：本机 SSH 公钥已添加到 Gitee（仓库部署公钥或个人公钥），否则克隆会因权限不足失败。"
fi
if [ -f "$HOME/.ssh/id_rsa.pub" ] || [ -f "$HOME/.ssh/id_ed25519.pub" ]; then
  pass "存在 SSH 公钥（供「是否克隆代码」使用）"
else
  warn "未发现 ~/.ssh/id_*.pub —— 若仓库为私有且需自动克隆，请先生成并添加到 Gitee"
fi
if have git; then
  if git ls-remote https://gitee.com/undefined_404/helloai.git >/dev/null 2>&1; then
    pass "远程仓库 HTTPS 匿名可读（公开仓库）"
  else
    info "HTTPS 匿名不可读（私有仓库或网络受限）—— 自动克隆需走 SSH Key"
  fi
fi

# ---------------------------------------------------------------------------
step "6 / 6：结论"
if [ "$BLOCKERS" -eq 0 ]; then
  printf '  \033[32m自检通过（0 项阻断，%s 项提醒）—— 可执行 CI 门禁。\033[0m\n' "$WARNINGS"
  printf '  试跑（在仓库根目录）：\n'
  printf '      bash scripts/ci/ci-gate.sh --quick     # 快速自检（单测试类 + 前端）\n'
  printf '      bash scripts/ci/ci-gate.sh             # 全量（所有模块 clean test + 前端）\n'
  exit 0
fi
printf '  \033[31m自检未通过：%s 项阻断，%s 项提醒。\033[0m\n' "$BLOCKERS" "$WARNINGS"
printf '  安装类修复：sudo bash scripts/ci/host-prepare.sh --install\n'
exit 1
