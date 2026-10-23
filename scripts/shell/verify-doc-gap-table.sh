#!/usr/bin/env bash
# ============================================================
# helloai 《差距表》结构守卫（去过程化专项清理的长期守卫）
#
# 用途：把「差距表只记当前状态」这条治理口径变成**可机械校验**的断言，
#       防止清理成果被下一次迭代稀释（该表在 2026-10-08 已漂移过一次）。
#
# 四项断言（全部只读，不修改任何文件）：
#   ① 表格完整性 —— 每个表格块内每行 `|` 数 == 表头行 `|` 数
#      （历史坑：单元格内未转义的裸 `|` 会把一行切成 9 格）
#   ② 无转义竖线 —— 新增文本一律「换符号」而非 `\|`
#   ③ 处置列首词 ∈ 九态词集（协作规约 §6.3；落位见差距表 §0「状态词表」）
#      且每行只允许一个状态词
#   ④ 矩阵行内无过程叙述信号（日期 / 用例数 / 施工动词 / 分期标签 / 代码行号）
#      —— 只扫 §1 矩阵的「当前状态」「处置」两列，且先剔除 durable 锚点
#
# Ref:  doc/文档体系分类与治理规则.md §3.1（只记当前状态、不累加批次过程）
#       doc/HelloAI_AI开发协作规约.md §6.3（九态状态词 + 落位句）
#       doc/HelloAI 实现差距表.md §0「状态词表」
#       LOG-20261009-014（本守卫的建立背景与前后对比）
#       LOG-20261011-010（zsh→bash 改造 + 接入门禁 3）
#
# 实现说明：**bash 实现**（原为 zsh，用 `emulate` 内建 + `print`，
#   Windows/Git Bash 无 zsh 且 `print` 会被 print.exe 顶替 ⇒ 静默 EXIT=0 假绿）。
#   正则类断言（③ 状态词边界、④ 过程信号黑名单）用 `python3`（三平台标准件）。
#
# 退出码：0 全部通过 / 1 存在失败项
# 用法：bash scripts/shell/verify-doc-gap-table.sh [-RepoRoot <path>]
# ============================================================

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
while [ $# -gt 0 ]; do
  case "$1" in
    -RepoRoot) REPO_ROOT="$2"; shift 2 ;;
    *) printf '未知参数：%s\n' "$1" >&2; exit 64 ;;
  esac
done

DOC="$REPO_ROOT/doc/HelloAI 实现差距表.md"
if [ ! -f "$DOC" ]; then
  printf '[FAIL] 找不到 %s\n' "$DOC" >&2
  exit 1
fi

# 解析 python 解释器（三平台标准件：linux/mac 为 python3，Windows 视安装而定）
PY="$(command -v python3 2>/dev/null || command -v python 2>/dev/null || true)"
if [ -z "$PY" ]; then
  printf '[FAIL] 未找到 python3 / python，无法执行正则类断言（③④）\n' >&2
  exit 1
fi

PASS=0; FAIL=0
ok()  { printf '  [ OK ] %s\n' "$1"; PASS=$((PASS+1)); }
bad() { printf '  [FAIL] %s\n' "$1"; FAIL=$((FAIL+1)); }

printf '== 《差距表》结构守卫 ==\n'
printf '  目标文件：%s\n' "${DOC#$REPO_ROOT/}"

# ------------------------------------------------------------
# ① 表格完整性
# ------------------------------------------------------------
table_bad=$("$PY" -I - "$DOC" <<'PY'
import io, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # 三平台输出一致（-I 会忽略 PYTHONUTF8）
ls = io.open(sys.argv[1], encoding="utf-8").read().split("\n")
tbl, bad = [], []
for i, l in enumerate(ls, 1):
    if l.startswith("|"):
        tbl.append((i, l.count("|")))
    else:
        if tbl:
            h = tbl[0][1]
            bad += ["L%d(%d!=%d)" % (ln, c, h) for ln, c in tbl if c != h]
            tbl = []
if tbl:
    h = tbl[0][1]
    bad += ["L%d(%d!=%d)" % (ln, c, h) for ln, c in tbl if c != h]
print(" ".join(bad))
PY
)
if [ -z "$table_bad" ]; then
  ok "① 表格完整性：所有表格块每行竖线数与表头一致"
else
  bad "① 表格完整性：$table_bad"
fi

# ------------------------------------------------------------
# ② 无转义竖线
# ------------------------------------------------------------
esc=$(grep -c '\\|' "$DOC" || true)
if [ "$esc" = "0" ]; then
  ok "② 无转义竖线（新增文本一律换符号）"
else
  bad "② 发现 $esc 行含转义竖线 \\|，应改为换符号"
fi

# ------------------------------------------------------------
# ③ 状态列严格等于九态中文值之一 + 优先级 ∈ PR0~PR3 + 已完成 ⇔ 剩余动作为「—」
# ------------------------------------------------------------
row_check=$("$PY" -I - "$DOC" <<'PY'
import io, sys, re
sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # 三平台输出一致（-I 会忽略 PYTHONUTF8）
S = ["待开始","设计中","实现中","验证中","已完成","部分完成","阻塞","暂缓","明确不做"]
ls = io.open(sys.argv[1], encoding="utf-8").read().split("\n")
bad, n = [], 0
for i, l in enumerate(ls, 1):
    if not l.startswith("| G-0"):
        continue
    c = l.split("|")
    if len(c) != 9:
        bad.append("L%d 段数%d(应 9)" % (i, len(c))); continue
    n += 1
    st = c[6].strip()
    if st not in S:
        bad.append("L%d(%s 状态=%r)" % (i, c[1].strip(), st))
        continue
    pr = c[5].strip()
    if pr not in ("PR0", "PR1", "PR2", "PR3"):
        bad.append("L%d(%s 优先级=%r)" % (i, c[1].strip(), pr))
    act = c[7].strip()
    if st == "已完成" and act != "—":
        bad.append("L%d(%s 已完成但剩余动作=%r)" % (i, c[1].strip(), act))
    if st != "已完成" and (not act or act == "—"):
        bad.append("L%d(%s %s 但剩余动作为空)" % (i, c[1].strip(), st))
# tripwire：矩阵行数是「新增 / 删除 G-0xx 行」的手动确认闸门。
# 每次登记新差距行（+1）或关闭删除（-1）时必须同步本数值，否则本守卫会红——
# 这正是它拦住「静默增删矩阵行」的手段，**不要**改成不等式或自动推导。
EXPECTED_ROWS = 24  # G-001 ~ G-024（G-021 于 D-2026-10-12-1 登记；G-022 于 2026-10-13 登记；G-023 于 D-2026-10-16-4 登记；G-024 于 2026-10-23 登记）
if n != EXPECTED_ROWS:
    bad.append("矩阵行数=%d(应 %d)" % (n, EXPECTED_ROWS))
print(" ".join(bad))
PY
)
if [ -z "$row_check" ]; then
  ok "③ 状态列严格 ∈ 九态中文值、优先级 ∈ PR0~PR3、已完成 ⇔ 剩余动作「—」、矩阵行数齐备（tripwire）"
else
  bad "③ 状态词校验：$row_check"
fi

# ------------------------------------------------------------
# ④ 矩阵行内过程叙述信号（先剔除 durable 锚点，再扫禁止模式）
# ------------------------------------------------------------
SIG='20[0-9]{2}-[0-9]{2}-[0-9]{2}|[0-9]+ 用例|0 失败|全绿|BUILD SUCCESS|PASS（|增量 [A-D]|批次[一二三四五]|\.java:[0-9]+|已落地|已修复|新增 [0-9]'
sig_hits=$("$PY" -I - "$DOC" "$SIG" <<'PY'
import io, sys, re
sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # 三平台输出一致（-I 会忽略 PYTHONUTF8）
doc, sig = sys.argv[1], sys.argv[2]
ls = io.open(doc, encoding="utf-8").read().split("\n")
pat = re.compile(sig)
# durable 锚点（`LOG-…` / `D-2026-…` / `REF-…` / `V102` / `G-0xx` 等）先剔除，避免误报
anchor = re.compile(r"`(?:LOG-[0-9]{8}-[0-9]{3}|D-20[0-9]{2}-[0-9]{2}-[0-9]{2}-[0-9]+[^\x60]*|REF-[0-9.]+|BASE-[0-9.x]+|G-0[0-9]{2}|V[0-9]{2,3}|ADR-[0-9]+|RM[0-9]+)`")
bad = []
for i, l in enumerate(ls, 1):
    if not l.startswith("| G-0"):
        continue
    c = l.split("|")
    if len(c) != 9:
        continue
    for col in (3, 4):
        txt = anchor.sub("", c[col])
        for m in pat.finditer(txt):
            bad.append("L%d·列%d·%s" % (i, col, m.group(0)))
print(" ".join(bad[:20]))
PY
)
if [ -z "$sig_hits" ]; then
  ok "④ 矩阵两列无过程叙述信号（日期 / 用例数 / 施工动词 / 分期标签 / 代码行号）"
else
  bad "④ 残留过程信号：$sig_hits"
fi

# ------------------------------------------------------------
printf '\n'
if [ "$FAIL" = "0" ]; then
  printf '[verify-doc-gap-table] ✅ 全部通过（%s 项）\n' "$PASS"
  exit 0
else
  printf '[verify-doc-gap-table] ❌ %s 项失败 / %s 项通过\n' "$FAIL" "$PASS"
  exit 1
fi
