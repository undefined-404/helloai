#!/usr/bin/env bash
# ============================================================
# check-shell-portability.sh —— Shell 脚本可移植性守卫（门禁 3）
# ------------------------------------------------------------
# 目标：把「**守卫自身会失真**」这一类缺陷（静默假绿 / 间歇假红）变成**可机械校验**的断言。
#       守卫存在 ≠ 守卫生效；守卫报错 ≠ 守卫报了真错。二者都要拦。
#
# 背景（2026-10-15 实测；macOS 自带 bash 3.2.57 = 本仓门禁 3 的真实运行环境之一）：
#   C1 **静默假绿**：`declare -A X=( [k]=v )` 在 bash 3.2 下 `-A` 被判非法选项（stderr、非致命）
#      ⇒ 该赋值退化为**索引**数组 ⇒ 下标 `[k]` 走**算术求值** ⇒ `k` 未绑定 ⇒ 在 `set -u` 下
#      **中止脚本**，但退出码**实测为 0**（对照：普通变量 `A=${NOPE}` 返回 1）
#      ⇒ 门禁 3 把它记成 `[ OK ]`。实例：`shell/verify-provider-catalog-parity.sh:47`
#      —— 其后的**全部断言从未执行**。
#   C2 **静默假绿（同族）**：`$VAR` 紧跟全角标点（如 `$S5_KEY（`）在 bash 3.2 + UTF-8 locale
#      （脚本自设 `LANG=zh_CN.UTF-8`）下会把多字节字符**首字节并入变量名** ⇒ `set -u` 下同上
#      「中止却 EXIT=0」。实例：`shell/verify-skill-capability-parity.sh:342` —— S5 两条断言未执行。
#   C3 **间歇假红**：管道右端 `grep -q`（命中即退出）+ `set -o pipefail` ⇒ 上游收 SIGPIPE(141)
#      ⇒ 整条管道判**非零** ⇒ 假 FAIL。实测同一判据 270 次判里误判 **187 次（69%）**。
#      实例：`shell/verify-provider-catalog-parity.sh` 的「反向空转检查」。
#
# 三项断言（全部只读，不修改任何文件）：
#   P1 bash 4+ 专属语法 —— 关联数组（`declare -A` / `local -A`）、`ma[p]file`、`readar[r]ay`、
#      `local -n`（nameref）、`wait -n`、`${V^^}` / `${V,,}`。
#      范围 = **全部在册 `.sh`**：bash 3.2 下不可用；zsh 下 `declare` 不存在，同样不可用。
#   P2 `$VAR` 后紧跟非 ASCII 字符 —— 须写 `${VAR}`（C2）。
#      范围 = **bash 实现**的 `.sh`（zsh 的多字节解析正确，实测 `zsh -c 'V=abc; print "$V（x"'`
#      输出完整 `abc（x` ⇒ 对 zsh 脚本不适用，避免无谓改动）。
#   P3 管道右端 `grep -q…`（C3）。范围 = **`scripts/` 下**的 `.sh`，**与当前是否启用 pipefail
#      无关**：该形态的正确性取决于「生产者恰好一次小写入」这一**隐性前提**，还受 **source 它的
#      调用方**的 `set -o pipefail` 影响（如 `c3-common.sh` 自身不启用、其调用方启用）
#      ⇒ 不按文件当前选项豁免，一律用 `grep -c` 计数形态（语义等价、无隐性前提）。
#      `deploy/` 侧同族 4 处（`docker-host-preflight.sh` / `init-rabbitmq-admin.sh` ×2 /
#      `middleware/scripts/migrate.sh`）**登记为已知例外**：均为一次性部署例程，改动风险
#      大于收益；若将来纳入，把下方 scope 判据从 `scripts/` 放宽到 `all` 即可。
#
# 反空转（守卫必须证明自己跑过）：
#   ① 扫到的 bash 实现数 < 20 ⇒ FAIL（防「枚举失效 ⇒ 三项断言恒真」）；
#   ② 本脚本必须产出结论行，否则判「未生效」（解释器异常时的静默通过）。
# 反证（**手动**跑，非每次门禁的固定项）：三个 `--inject-*` 各注入一个只含单一违规的临时文件
#   （落仓内 `tmp/`，已被根 .gitignore 忽略），期望 EXIT≠0 —— 见下方「用法」与 `case` 分支。
#
# 正则「自免疫」约定：本文件的**判据字面量与状态消息文字**都**不得命中自己的判据**（否则守卫永远红）。
#   手法：① `ma[p]file` / `readar[r]ay`（正则等价、字面量不自匹配）；② 要求「其后紧跟空白」的
#   分支（`decla[r]e -A` 等）天然免疫；③ **状态消息里只写类别名**（如「并发原语」），不枚举
#   `local -n` / `wait -n` 这类**带空白**的 token —— 首次实现即在此踩过（P1 命中自己 `:195`）。
#   注释行与 `#` 开头的行不参与判定（注释不构成隐患，可自由枚举）。
#
# 用法：
#   bash scripts/ci/check-shell-portability.sh                    # EXIT=0 全部通过
#   bash scripts/ci/check-shell-portability.sh --inject-bash4     # 反证①：bash4 语法 ⇒ EXIT≠0
#   bash scripts/ci/check-shell-portability.sh --inject-mb        # 反证②：多字节相邻 ⇒ EXIT≠0
#   bash scripts/ci/check-shell-portability.sh --inject-pipefail  # 反证③：管道右端 grep -q ⇒ EXIT≠0
#
# 退出码：0 全部通过 / 1 存在失败项 / 64 未知参数 / 69 缺少依赖（git 或 python3）
# Ref: LOG-20261015-008（本守卫建立背景与三类实测）
#      LOG-20261011-010（同族先例：差距表守卫在无 zsh 环境静默 EXIT=0）
#      plan/HelloAI 借鉴落地实施计划.md §10（REF-8 收敛判据：判据须可机械校验）
# ============================================================

# 【刻意不设 LANG/LC_ALL】本守卫读文件一律显式 `encoding="utf-8"`（python），输出侧
#   `reconfigure(encoding="utf-8")` ⇒ 无需依赖任何 locale —— 否则反而会被 C2（locale 相关）
#   与「目标机不一定有 zh_CN.UTF-8」（Git Bash / 精简容器）牵连。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT" || exit 1

if ! command -v git >/dev/null 2>&1; then
  printf '[FAIL] 未找到 git —— 本守卫的判据基于「仓库在册 .sh」（on-disk 会混入解包残留），无法枚举\n' >&2
  exit 69
fi

# 解析 python 解释器（三平台标准件；缺失即响亮失败，不静默跳过）
PY="$(command -v python3 2>/dev/null || command -v python 2>/dev/null || true)"
if [ -z "$PY" ]; then
  printf '[FAIL] 未找到 python3 / python —— 无法执行正则类断言（P1~P3）\n' >&2
  exit 69
fi

# ---------- 反证开关（只读源码；注入物落仓内 tmp/，已被根 .gitignore 忽略）----------
INJECT_MODE="${1:-}"
INJECT_FILE=""
cleanup() { [ -n "$INJECT_FILE" ] && rm -f "$INJECT_FILE"; return 0; }
trap cleanup EXIT
mkdir -p "$ROOT/tmp"
case "$INJECT_MODE" in
  "")
    : ;;
  --inject-bash4)
    # 拼装而非字面量：避免本文件源码命中自己的 P1 判据
    INJECT_FILE="$ROOT/tmp/.shellport-bash4.sh"
    printf '#!/usr/bin/env bash\nset -uo pipefail\n%s %s M=( [a]=1 )\n' \
      'declare' '-A' > "$INJECT_FILE" ;;
  --inject-mb)
    INJECT_FILE="$ROOT/tmp/.shellport-mb.sh"
    printf '#!/usr/bin/env bash\n%s=x\necho "$%s（尾"\n' 'V' 'V' > "$INJECT_FILE" ;;
  --inject-pipefail)
    INJECT_FILE="$ROOT/tmp/.shellport-pipefail.sh"
    printf '#!/usr/bin/env bash\nset -uo pipefail\nif ! printf "%%s\\n" a | %s -qx a; then :; fi\n' \
      'grep' > "$INJECT_FILE" ;;
  *)
    printf '[check-shell-portability] 未知参数：%s\n' "$INJECT_MODE" >&2
    exit 64 ;;
esac

# ---------- 清单：在册 .sh + 反证注入物（scope=scripts ⇒ P3 亦生效）----------
build_scan() {
  local f scope
  # shellcheck disable=SC2046  # 在册脚本路径无空格，安全按词拆分
  for f in $(git ls-files '*.sh'); do
    case "$f" in
      scripts/*) scope="scripts" ;;
      *)         scope="all" ;;
    esac
    printf '%s\t%s\n' "$f" "$scope"
  done
  [ -n "$INJECT_FILE" ] && printf '%s\tscripts\n' "$INJECT_FILE"
  return 0
}

SHELLPORT_SCAN="$(build_scan)"

OUT="$(
  SHELLPORT_SCAN="$SHELLPORT_SCAN" "$PY" - <<'PY'
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

# 判据字面量「自免疫」：ma[p]file / readar[r]ay 见文件头说明
P1 = re.compile(
    r"declare[ \t]+-A"
    r"|local[ \t]+-A"
    r"|ma[p]file"
    r"|readar[r]ay"
    r"|\blocal[ \t]+-n[ \t]"
    r"|\bwait[ \t]+-n\b"
    r"|\$\{[A-Za-z_][A-Za-z0-9_]*(,,|\^\^)"
)
P2 = re.compile(r"\$[A-Za-z_][A-Za-z0-9_]*[^\x00-\x7F]")
P3 = re.compile(r"\|[ \t]*grep[ \t]+(?:-[A-Za-z]*q[A-Za-z]*|--quiet)\b")

MIN_BASH_FILES = 20


def is_bash(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return "bash" in fh.readline()
    except OSError:
        return False


def read_lines(path):
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read().split("\n")


entries = [e for e in os.environ.get("SHELLPORT_SCAN", "").split("\n") if e.strip()]

hits1, hits2, hits3 = [], [], []
n_files = n_bash = 0
for entry in entries:
    path, _, scope = entry.partition("\t")
    if not os.path.isfile(path):
        continue
    n_files += 1
    bash_impl = is_bash(path)
    if bash_impl:
        n_bash += 1
    for lineno, line in enumerate(read_lines(path), 1):
        # 注释行不参与判定（注释里的示例字样不构成可执行隐患）
        if line.lstrip().startswith("#"):
            continue
        for m in P1.finditer(line):
            hits1.append((path, lineno, m.group(0)))
        if bash_impl:
            for m in P2.finditer(line):
                hits2.append((path, lineno, m.group(0)))
        if scope == "scripts":
            for m in P3.finditer(line):
                hits3.append((path, lineno, m.group(0)))


def report(tag, title, hits, ok_note):
    print(f"-- {tag} {title} --")
    if not hits:
        print(f"  [ OK ] {ok_note}")
        return 0
    for path, lineno, text in hits:
        print(f"  [FAIL] {tag} {path}:{lineno}: 命中 {text!r}")
    return len(hits)


n1 = report("P1", "bash 4+ 专属语法（bash 3.2 下不可用）", hits1,
            f"{n_files} 个在册 .sh 未使用 bash 4+ 专属语法（关联数组 / 文件读入 / nameref / 并发原语 / 大小写转换）—— 逐类清单见文件头 P1")
n2 = report("P2", "`$VAR` 紧跟非 ASCII 字符（须写 `${VAR}`）", hits2,
            f"{n_bash} 个 bash 实现的 .sh 变量插值边界均安全")
n3 = report("P3", "管道右端 `grep -q…`（早退 + pipefail ⇒ 假 FAIL）", hits3,
            "scripts/ 下的 .sh 均未把 `grep -q…` 放在管道右端")

if n_bash < MIN_BASH_FILES:
    print(f"  [FAIL] 反空转：仅识别出 {n_bash} 个 bash 实现（下限 {MIN_BASH_FILES}）"
          f" ⇒ 断言可能恒真，请核查枚举逻辑（git ls-files / shebang 判定）")

print(f"  扫描：在册 .sh {n_files} 个，其中 bash 实现 {n_bash} 个")
PY
)"

printf '%s\n' "$OUT"
FAILS="$(printf '%s\n' "$OUT" | grep -c '^[[:space:]]*\[FAIL\]' || true)"

# 自证：本守卫必须产出至少一条结论行，否则视为「未生效」（防解释器异常被记成通过）
OK_LINES="$(printf '%s\n' "$OUT" | grep -c '^[[:space:]]*\[ OK \]' || true)"
if [ "$OK_LINES" -eq 0 ]; then
  printf '[FAIL] 三项断言均未产出结论（python 解释器异常？）—— 视为未生效\n'
  FAILS=$((FAILS + 1))
fi

N_FILES="$(printf '%s\n' "$SHELLPORT_SCAN" | grep -c '[^[:space:]]' || true)"
printf '== check-shell-portability: P1 bash3 兼容 / P2 变量插值边界 / P3 管道判据 —— %s（在册 .sh %s）\n' \
  "$([ "$FAILS" -eq 0 ] && echo '全部通过' || echo "${FAILS} 处失配")" "$N_FILES"
exit "$FAILS"
