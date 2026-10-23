#!/usr/bin/env bash
# ============================================================
# 计划文档「逐项完成状态」守卫（增量式）
#
# 目的：让 plan/ 里的任务条目能被人与 AI agent 直接扫描出「还差哪些」，
#       与《差距表》§1 矩阵的状态列同一套九态口径。
#
# 两种承载形态都支持：
#   表格式   | PC-1 | 借什么 | ... | 状态 | 剩余动作 |
#   章节式   ### ATT-1 ... 紧跟一行  > **状态**：已完成 ｜ **剩余动作**：—
#
# 增量策略：只校验「已改造白名单」内的文件——存量计划未补状态列时不报红，
#           改造一份纳入一份，避免一次性全量阻断。
#
# 判据：
#   ① 状态值 ∈ 九态（待开始/设计中/实现中/验证中/已完成/部分完成/阻塞/暂缓/明确不做）
#   ② 状态 = 已完成 ⇒ 剩余动作必须是「—」
#   ③ 状态 ≠ 已完成 ⇒ 剩余动作不得为空、不得为「—」
# ============================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PLAN_DIR="$ROOT/doc/plan"

# 已改造白名单（新增一份改造完成就追加一行）
FILES=(
  "HelloAI Paperclip 借鉴落地计划（2026-10-21）.md"
  "HelloAI 需求对话附件实施计划（2026-10-22）.md"
)

PY=""
for c in python3 python py; do
  if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
done
if [ -z "$PY" ]; then
  echo "[verify-plan-status] 跳过：未找到 python"
  exit 0
fi

printf '== 计划文档逐项状态守卫 ==\n'
for f in "${FILES[@]}"; do
  if [ ! -f "$PLAN_DIR/$f" ]; then
    printf '  [SKIP] %s（白名单文件不存在）\n' "$f"
    continue
  fi
  out=$("$PY" -I - "$PLAN_DIR/$f" <<'PY'
import io, re, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
S = ["待开始","设计中","实现中","验证中","已完成","部分完成","阻塞","暂缓","明确不做"]
ID = re.compile(r"^\| \*{0,2}((?:PC|S|W|ATT|REF|M)-\d+)[^|]*\|")
SEC = re.compile(r"^#{2,4} \*{0,2}((?:PC|S|W|ATT)-\d+)")
ls = io.open(sys.argv[1], encoding="utf-8").read().split("\n")
bad, checked = [], 0
for i, l in enumerate(ls):
    m = ID.match(l)
    if m:
        c = [x.strip() for x in l.split("|")]
        if c and c[-1] == "":
            c = c[:-1]          # 行尾 "|" 会切出一个空段，去掉后再取末两列
        if len(c) < 4:
            continue
        st, act = c[-2], c[-1]
        checked += 1
        if st not in S:
            bad.append("L%d(%s 状态=%r)" % (i+1, m.group(1), st)); continue
        if st == "已完成" and act != "—":
            bad.append("L%d(%s 已完成但剩余动作=%r)" % (i+1, m.group(1), act))
        if st != "已完成" and (not act or act == "—"):
            bad.append("L%d(%s %s 但剩余动作为空)" % (i+1, m.group(1), st))
        continue
    m = SEC.match(l)
    if m:
        checked += 1
        win = "\n".join(ls[i+1:i+6])
        if "**状态**" not in win:
            bad.append("L%d(%s 缺 `> **状态**：` 行)" % (i+1, m.group(1))); continue
        line = [x for x in ls[i+1:i+6] if "**状态**" in x][0]
        # 只解析「状态」段（到 ｜ 为止）与「剩余动作」段，避免剩余动作里的描述文字
        # （如「已完成 V122 迁移」）被误当成状态值——这是解析缺陷，不是文档缺陷。
        state_seg = line.split("**状态**：", 1)[-1].split("｜")[0] if "**状态**：" in line else line
        act_seg = line.split("**剩余动作**：", 1)[-1] if "**剩余动作**：" in line else ""
        hits = [w for w in S if w in state_seg]
        if not hits:
            bad.append("L%d(%s 状态值不在九态内)" % (i+1, m.group(1))); continue
        act_blank = act_seg.strip() in ("", "—")
        if "已完成" in hits and act_seg.strip() != "—":
            bad.append("L%d(%s 已完成但剩余动作非「—」)" % (i+1, m.group(1)))
        if "已完成" not in hits and act_blank:
            bad.append("L%d(%s 非已完成但剩余动作为「—」或空)" % (i+1, m.group(1)))
print("%d|%s" % (checked, " ".join(bad[:10])))
PY
)
  n=${out%%|*}
  msg=${out#*|}
  if [ -z "$msg" ]; then
    printf '  [ OK ] %s：%s 个条目状态齐备\n' "${f:0:34}" "$n"
  else
    printf '  [FAIL] %s：%s\n' "${f:0:34}" "$msg"
    FAILED=1
  fi
done

printf '\n'
if [ "${FAILED:-0}" = "0" ]; then
  printf '[verify-plan-status] ✅ 全部通过（在册 %s 份）\n' "${#FILES[@]}"
  exit 0
else
  printf '[verify-plan-status] ❌ 存在未达标文件\n'
  exit 1
fi
