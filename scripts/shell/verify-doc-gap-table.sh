#!/usr/bin/env zsh
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
#
# 退出码：0 全部通过 / 1 存在失败项
# 用法：zsh scripts/shell/verify-doc-gap-table.sh [-RepoRoot <path>]
# ============================================================

emulate -L zsh
set -u

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
while [[ $# -gt 0 ]]; do
  case "$1" in
    -RepoRoot) REPO_ROOT="$2"; shift 2 ;;
    *) print -u2 "未知参数：$1"; exit 64 ;;
  esac
done

DOC="$REPO_ROOT/doc/HelloAI 实现差距表.md"
if [[ ! -f "$DOC" ]]; then
  print -u2 "[FAIL] 找不到 $DOC"
  exit 1
fi

PASS=0; FAIL=0
ok()   { print "  [ OK ] $1"; PASS=$((PASS+1)); }
bad()  { print "  [FAIL] $1"; FAIL=$((FAIL+1)); }

print "== 《差距表》结构守卫 =="
print "  目标文件：${DOC#$REPO_ROOT/}"

# ------------------------------------------------------------
# ① 表格完整性
# ------------------------------------------------------------
table_bad=$(python3 -I - "$DOC" <<'PY'
import io, sys
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
if [[ -z "$table_bad" ]]; then
  ok "① 表格完整性：所有表格块每行竖线数与表头一致"
else
  bad "① 表格完整性：$table_bad"
fi

# ------------------------------------------------------------
# ② 无转义竖线
# ------------------------------------------------------------
esc=$(grep -c '\\|' "$DOC" || true)
if [[ "$esc" == "0" ]]; then
  ok "② 无转义竖线（新增文本一律换符号）"
else
  bad "② 发现 $esc 行含转义竖线 \\|，应改为换符号"
fi

# ------------------------------------------------------------
# ③ 处置列首词 ∈ 九态 + 一行一态
# ------------------------------------------------------------
row_check=$(python3 -I - "$DOC" <<'PY'
import io, sys, re
S = ["TODO","DESIGNING","IMPLEMENTING","VERIFYING","DONE","PARTIAL","BLOCKED","DEFERRED","WONTFIX"]
ls = io.open(sys.argv[1], encoding="utf-8").read().split("\n")
bad, n = [], 0
for i, l in enumerate(ls, 1):
    if not l.startswith("| G-0"):
        continue
    c = l.split("|")
    if len(c) != 8:
        bad.append("L%d 段数%d" % (i, len(c))); continue
    n += 1
    disp = c[6].strip()
    first = re.split(r"[ ·:：]", disp, maxsplit=1)[0].strip()
    if first not in S:
        bad.append("L%d(%s 首词=%r)" % (i, c[1].strip(), first))
        continue
    hits = [w for w in S if re.search(r"(?<![A-Z])" + w + r"(?![A-Z])", disp)]
    if len(hits) > 1:
        bad.append("L%d(%s 多状态词=%s)" % (i, c[1].strip(), hits))
if n != 20:
    bad.append("矩阵行数=%d(应 20)" % n)
print(" ".join(bad))
PY
)
if [[ -z "$row_check" ]]; then
  ok "③ 处置列首词全部 ∈ 九态集合，一行一态，矩阵 20 行齐备"
else
  bad "③ 状态词校验：$row_check"
fi

# ------------------------------------------------------------
# ④ 矩阵行内过程叙述信号（先剔除 durable 锚点，再扫禁止模式）
# ------------------------------------------------------------
SIG='20[0-9]{2}-[0-9]{2}-[0-9]{2}|[0-9]+ 用例|0 失败|全绿|BUILD SUCCESS|PASS（|增量 [A-D]|批次[一二三四五]|\.java:[0-9]+|已落地|已修复|新增 [0-9]'
sig_hits=$(python3 -I - "$DOC" "$SIG" <<'PY'
import io, sys, re
doc, sig = sys.argv[1], sys.argv[2]
ls = io.open(doc, encoding="utf-8").read().split("\n")
pat = re.compile(sig)
# durable 锚点（`LOG-…` / `D-2026-…` / `REF-…` / `V102` / `G-0xx` 等）先剔除，避免误报
anchor = re.compile(r"`(?:LOG-[0-9]{8}-[0-9]{3}|D-20[0-9]{2}-[0-9]{2}-[0-9]{2}-[0-9]+[^`]*|REF-[0-9.]+|BASE-[0-9.x]+|G-0[0-9]{2}|V[0-9]{2,3}|ADR-[0-9]+|RM[0-9]+)`")
bad = []
for i, l in enumerate(ls, 1):
    if not l.startswith("| G-0"):
        continue
    c = l.split("|")
    if len(c) != 8:
        continue
    for col in (3, 6):
        txt = anchor.sub("", c[col])
        for m in pat.finditer(txt):
            bad.append("L%d·列%d·%s" % (i, col, m.group(0)))
print(" ".join(bad[:20]))
PY
)
if [[ -z "$sig_hits" ]]; then
  ok "④ 矩阵两列无过程叙述信号（日期 / 用例数 / 施工动词 / 分期标签 / 代码行号）"
else
  bad "④ 残留过程信号：$sig_hits"
fi

# ------------------------------------------------------------
print ""
if [[ "$FAIL" == "0" ]]; then
  print "[verify-doc-gap-table] ✅ 全部通过（$PASS 项）"
  exit 0
else
  print "[verify-doc-gap-table] ❌ $FAIL 项失败 / $PASS 项通过"
  exit 1
fi
