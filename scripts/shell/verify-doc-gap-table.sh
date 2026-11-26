#!/usr/bin/env bash
# ============================================================
# helloai 《差距表》结构守卫（去过程化专项清理的长期守卫）
#
# 用途：把「差距表只记当前状态」这条治理口径变成**可机械校验**的断言，
#       防止清理成果被下一次迭代稀释（该表在 2026-10-08 已漂移过一次）。
#
# 七项断言（全部只读，不修改任何文件）：
#   ① 表格完整性 —— 每个表格块内每行 `|` 数 == 表头行 `|` 数
#      （历史坑：单元格内未转义的裸 `|` 会把一行切成 9 格）
#   ①b 矩阵连续性 —— §1 总体矩阵必须是**单一**表格块，不得被空行 / 非表格行切断
#      （历史坑：L196 / L200 两处空行把 G-024 之后切成三段，渲染成裸竖线；
#        当时 ① 是绿的——逐块校验看不见「块被切开」这件事）
#   ①c 号段登记行不累加 —— §0 单行 ≤ 1200 字符（只指路，不搬实现笔记）
#      （历史坑：`MA-x` 一行曾写到 10900 字符，`GT-x` 3005、`RV-x` 1544）
#   ①d 矩阵单元格不累加 —— §1「当前状态」「目标」「剩余动作」单格 ≤ 500 字符
#      （历史坑：`G-032` 单格 5464、`G-025` 剩余动作 6646、`G-038` 整行 9393；
#        膨胀源头是把「每次施工做了什么」逐批追加进单元格，同一事实在 log/plan
#        各写一遍必然分叉（判据 4）。单元格只写当前事实 + 目标口径 + 剩余动作）
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
# ①b 矩阵连续性（§1 总体矩阵内不得被空行 / 非表格行切断）
#
# 为什么单独立这一条：断言① 逐块校验「块内每行竖线数 == 该块表头竖线数」，
# 它有个盲区——空行会**把一张表切成两个块**，两块各自竖线数都自洽，
# 于是「表格被切断」这种更严重的破坏恰好能通过①。
# 实际发生过：L196 / L200 两处空行把 `G-024` 之后的矩阵切成三段，
# 渲染出来 `G-025` 起变成一堆裸竖线文本（无表头 ⇒ 不成表），而守卫当时是绿的。
# 判据取「矩阵块数」而非「行号连续」：块数恒为 1 才说明没被切断。
# ------------------------------------------------------------
matrix_blocks=$("$PY" -I - "$DOC" <<'PY'
import io, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ls = io.open(sys.argv[1], encoding="utf-8").read().split("\n")
# §1 矩阵的边界：从 `| 编号 | 项目 |` 表头起，到最后一个 `| G-0xx |` 行止
start = None
for i, l in enumerate(ls):
    if l.startswith("| 编号 |") and "项目" in l:
        start = i
        break
if start is None:
    print("NO_MATRIX"); raise SystemExit
end = max(i for i, l in enumerate(ls) if l.startswith("| G-0"))
blocks, prev_tbl = [], False
for i in range(start, end + 1):
    cur = ls[i].startswith("|")
    if cur and not prev_tbl:
        blocks.append(i + 1)
    prev_tbl = cur
print("%d:%s" % (len(blocks), ",".join("L%d" % b for b in blocks)))
PY
)
blk_n=${matrix_blocks%%:*}
blk_at=${matrix_blocks#*:}
if [ "$blk_n" = "1" ]; then
  ok "①b 矩阵连续性：§1 矩阵为单一表格块（未被空行切断）"
else
  bad "①b 矩阵连续性：§1 矩阵被切成 ${blk_n} 个表格块（新块起始 ${blk_at}），空行/非表格行会把后续行渲染成裸竖线"
fi

# ------------------------------------------------------------
# ①c 号段登记行不得累加（§0 编号口径表单行 ≤ 1200 字符）
#
# 为什么加这一条：§0 是「编号 → 含义」的登记表，它的职责只是**指路**。
# 但历史上它被当成实现笔记用——`MA-x` 一行曾写到 **10900 字符**（`MA-2`/`MA-3`
# 的落点、单测数、mapper 查询入口全塞在里面），`GT-x` 3005、`RV-x` 1544。
# 这正是用户反复提的「不要大段累加堆砌」：同一份事实在 §0、§1.1 索引表、
# 蓝图/计划文档里各写一遍，必然分叉（判据 4）。
# 判据取「单行字符数」而非关键词：膨胀形态多样，长度是唯一稳定的机械信号。
# 阈值 1200 ≈ 当前最长行（652）的两倍，用来拦**下一轮**膨胀，不卡现状。
# ------------------------------------------------------------
long_rows=$("$PY" -I - "$DOC" <<'PY'
import io, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ls = io.open(sys.argv[1], encoding="utf-8").read().split("\n")
# 只扫 §0（从 `# 0.` 到 `## 1.1`）
try:
    a = next(i for i, l in enumerate(ls) if l.startswith("# 0."))
    b = next(i for i, l in enumerate(ls) if l.startswith("## 1.1"))
except StopIteration:
    print(""); raise SystemExit
LIMIT = 1200
print(" ".join("L%d(%d)" % (i + 1, len(ls[i])) for i in range(a, b)
               if ls[i].startswith("| `") and len(ls[i]) > LIMIT))
PY
)
if [ -z "$long_rows" ]; then
  ok "①c 号段登记行不累加：§0 单行均 ≤ 1200 字符（只指路，不搬实现笔记）"
else
  bad "①c 号段登记行过长：$long_rows —— §0 只登记「编号 → 含义 → 落点」，实现明细写进对应 plan/design"
fi

# ------------------------------------------------------------
# ①d 矩阵单元格不得累加（§1「当前状态」「目标」「剩余动作」单格 ≤ 500 字符）
#
# 为什么加这一条：用户口径「再怎么复杂也不应该超过 500 字」。
# 历史上这三列被当成施工日志用——`G-032` 当前状态 5464 字符（把 `SL-1`~`SL-10`
# 每一步做了什么全塞进去）、`G-025` 剩余动作 6646（`TD-1`~`TD-15` 逐条追加）、
# `G-038` 整行 9393。膨胀机制是**只加不删**：每做完一批就往同一个格子里往后追加，
# 于是「当前状态」里混进了历次施工过程，「剩余动作」里混进了已完成事项。
# 判定口径：单元格只写「当前事实 / 目标口径 / 尚未完成的动作」；
# 「哪批做了什么、为什么这么定」归 `doc/log/`，实施细节归 `plan/` / `design/`。
# 判据取字符数而非关键词：膨胀形态多样，长度是唯一稳定的机械信号。
# 阈值 500 = 用户明确给出的上限，不做倍数放宽。
# ------------------------------------------------------------
wide_cells=$("$PY" -I - "$DOC" <<'PY'
import io, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ls = io.open(sys.argv[1], encoding="utf-8").read().split("\n")
LIMIT = 500
bad, n = [], 0
for i, l in enumerate(ls, 1):
    if not l.startswith("| G-0"):
        continue
    c = [x.strip() for x in l.strip().strip("|").split("|")]
    if len(c) != 7:
        continue
    n += 1
    for name, idx in (("当前状态", 2), ("目标", 3), ("剩余动作", 6)):
        if len(c[idx]) > LIMIT:
            bad.append("L%d·%s·%s(%d)" % (i, c[0], name, len(c[idx])))
print(" ".join(bad) if bad else "OK:%d 行" % n)
PY
)
case "$wide_cells" in
  OK:*) ok "①d 矩阵单元格不累加：三列单格均 ≤ 500 字符（${wide_cells#OK:}）" ;;
  *)    bad "①d 矩阵单元格过长：$wide_cells —— 单元格只写「当前事实 / 目标口径 / 剩余动作」，施工过程归 doc/log/、实施细节归 plan/design" ;;
esac

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
EXPECTED_ROWS = 55  # G-001 ~ G-055（G-055 于 2026-11-26 登记：视觉能力位只写不读（全仓 5 处命中全为定义/汇聚/javadoc/单测，执行链与评审链零消费）+ `InternalLlmExecutorTools` 三处 `new String(bytes, UTF_8)`（`:362` / `:385` / `:426`）对图片无差别解码；二者是「图片不得进文本通道」这条判据的第三、第四个未修消费方，与浏览器无关，故从 `VU-6` 整条里拆出为 `VU-6a`（G-054 于 2026-11-26 登记：附件轮次快照 `V157` 在人工驳回（`reworkFresh:588`）与死信重派（`SubTaskDispatchServiceImpl:411`）两条清零路径上倒退为 1，`round` 失去区分能力（真机实证：子任务 `2125050135475167234` 经 3 次死信重派产 5 份产物全为 round=1））（G-053 于 2026-11-26 登记：拆解并发监管——`executeDecompose(taskId)` 不接受 attemptId、只比对「任务是否仍 PLANNING」，租约回收后旧线程读到的是新 attemptId，会与新一轮并行跑完；代码走查发现、未构造复现）（G-052 于 2026-11-26 登记：评审覆盖引用按严格原文子串比对，反引号差异使 `coverage_ref_not_grounded`(WARN) 升级为 `acceptance_not_covered`(BLOCK) ⇒ RV-2 落 INCONCLUSIVE 转人工；与 G-051 同一次真机复跑中发现）（G-051 于 2026-11-26 登记：执行面「只能看图不能算图」——会话输入附件在 `chat_attachment`，而 `internal_stage_attachment_to_sandbox` 的归属校验只查 `attachment`（子任务产物表），注入必被拒；真机表现为拆解出的取色子任务 `BLOCKED`）（G-050 于 2026-11-25 登记：deepseek 图片输入静默失效——spring-ai-deepseek 1.1.8 无 media 分支，方案 A 改走 OpenAI 兼容通道，V156 恢复 image 声明，专用工厂停用不删（D-e 守卫依赖其 DEFAULT_MODEL））；G-041~G-049 于 2026-11-25 同批登记（均为会话附件链路：G-041 会话附件删除未彻底回收（口径定为硬删四件套：物理删行 + purge 对象 + 跨表护栏 + 清历史软删行）；G-042 执行面无附件作用域；G-043 工作台链未解析 attachment_targets；G-044 勾选信号在 CLARIFY/FINALIZE 被丢弃（修法 A：透传 selectedIds）；G-045 全排除语义反转（空集 ≡ 全量）；G-046 排序与截断方向相反（保留最老、丢弃最新）；G-047 对象回收护栏缺跨表一侧（同 objectKey 双行方案地基）；G-048 同步兜底链不接 attachmentIds；G-049 excludedAttachmentIds 切会话不重置）；G-039/G-040 于 2026-11-25 登记（G-039 = 任务全局上下文无差别注入已完成子任务产出（`VU-8` 执行面首轮对照实验被污染的直接原因）；G-040 = `POST /api/admin/agents` 不推导 `accessType`，建出 `CLI_CLIENT`+`modelType` 非法组合）；G-038 于 2026-11-24 登记（视觉验收 / 运行时 UI 证据能力全空白，`VU-1`~`VU-7` 建号锚点）；G-037 于 2026-11-23 登记（任务约束类型化试点，`CT-1`~`CT-8` 实施）；G-036 于 2026-11-16 登记（A2A 兼容边界，`ADR-009` 定边界、`AQ-1`~`AQ-13` 待验）；G-035 于 2026-11-15 登记（站内消息与置顶公告，`MSG-x` 建号锚点；首批 + 公告 + 平台开关，`MSG-7` 扩展列后续）;G-034 于 2026-11-13 登记（审核侧证据可见性与驳回依据可达性，`RV-8`~`RV-13`）；G-021 于 D-2026-10-12-1 登记；G-022 于 2026-10-13 登记；G-023 于 D-2026-10-16-4 登记；G-024 于 2026-10-23 登记；G-025 于 2026-10-24 登记，见 LOG-20261024-034；G-026 于 2026-10-26 登记，见 LOG-20261026-001；G-027 于 2026-11-04 登记，见 LOG-20261104-013；G-028 于 2026-11-05 补登「个人助理工作台」——`PA-x` 已落地但此前无 G 锚点，见 LOG-20261105；G-029/G-030 于 2026-11-10 登记（跨文档核验真机缺陷），见 LOG-20261110；G-031 于 2026-11-11 登记（Task-Team 成员表运行期无写入）；G-032 于 2026-11-11 登记（沙箱生命周期与台账证据，`SL-1`~`SL-6`）；G-033 于 2026-11-12 登记（执行环境执行侧与派发校验缺口，`LOG-20261112` A 段遗留；「仓库注入」形态经用户裁定先调查，结论回填差距表行））
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
