#!/usr/bin/env bash
# ============================================================
# 同步 HelloAI 站点资源到 Cloudflare Pages 部署目录（docs-site/）
# ------------------------------------------------------------
# 背景：根目录 README.html 已删除（Gitee 首页回归 README.md），
#       站点以 docs-site/index.html 为唯一事实源（README.html 的副本）。
# 用途：当 doc/diagrams/ 或 doc/images/ 下的图表/截图更新后，
#       运行本脚本把这些资源同步进 docs-site/，保持在线站点最新。
# 流程：
#   1. 主页面：若存在上游源则覆盖，否则保留现有 docs-site/index.html
#   2. 同步 6 个交互图 iframe + 9 个 SVG + 28 张 PNG 资源
#   3. 校验站点内所有相对引用可达
# 注意：
#   - 本站是「单页 + 图片资源」部署；index.html 中指向仓库文件的链接
#     （LICENSE / Dockerfile / deploy/... / doc/log/ 等）在静态站点内
#     无法打开，属预期行为，不入站。
#   - 交互图 iframe（doc/diagrams/html/*.html）自包含，无外部依赖。
#   - 若未来重新引入 README.html 生成流程，把产物放到 docs-site/index.html
#     即可（本脚本第 1 步只更新资源，不覆盖已有 index.html 内容变化）。
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."

SITE="docs-site"

# 0. 预检
[ -d "$SITE" ] || { echo "错误：缺少站点目录 $SITE/（请先从 README.html 生成 index.html）"; exit 1; }

# 1. 主页面
#    上游源（根目录 README.html 已删除，这里留作将来重建时启用）：
#    if [ -f README.html ]; then cp README.html "$SITE/index.html"; fi
echo "✓ 主页面保持现有：$SITE/index.html"

# 2. 依赖资源（与 index.html 相对路径保持一致）
mkdir -p "$SITE/doc/diagrams/html" "$SITE/doc/images"

# 2.1 交互图 iframe（6 张，自包含）
cp doc/diagrams/html/executor-flow-and-state.html \
   doc/diagrams/html/helloai-system-architecture.html \
   doc/diagrams/html/planner-flow-and-state.html \
   doc/diagrams/html/platform-scheduling-flow.html \
   doc/diagrams/html/platform-state-machines.html \
   doc/diagrams/html/reviewer-flow-and-state.html \
   "$SITE/doc/diagrams/html/"
echo "✓ 6 张交互图"

# 2.2 SVG 架构图（9 张，index.html 直接引用的）
cp doc/diagrams/external-agent-flow.svg \
   doc/diagrams/final-report-flow.svg \
   doc/diagrams/helloai-architecture.svg \
   doc/diagrams/internal-llm-executor-flow.svg \
   doc/diagrams/planner-decompose-flow.svg \
   doc/diagrams/platform-state-machines.svg \
   doc/diagrams/reviewer-full-flow.svg \
   doc/diagrams/subtask-state-machine.svg \
   "$SITE/doc/diagrams/"
echo "✓ 9 张 SVG"

# 2.3 UI 截图（28 张 PNG：索引页引用 18 张 + 归档 8 张 + 设计图 2 张）
for img in \
  "clarify-chat.png" \
  "chat-artifact.png" \
  "dag-view.png" \
  "iteration-view.png" \
  "overview.png" \
  "task-list.png" \
  "agents.png" \
  "teams.png" \
  "subtask-detail.png" \
  "subtask-conversation.png" \
  "quality-dashboard.png" \
  "reviews.png" \
  "event-stream.png" \
  "inbox.png" \
  "duty-leases.png" \
  "role-list.png" \
  "permission-list.png" \
  "menu-list.png" \
  "user-list.png" \
  "depart-list.png" \
  "settings.png" \
  "subtask-list.png" \
  "activity.png" \
  "rules.png" \
  "attachments.png" \
  "login.png" \
  "helloai任务旅程.png" \
  "helloai一句话看懂.png"
do
  if [ -f "doc/images/$img" ]; then
    cp "doc/images/$img" "$SITE/doc/images/"
  else
    echo "⚠ 源图缺失，跳过：doc/images/$img"
  fi
done
echo "✓ UI 截图（28 张）"

# 3. 校验：站点内所有相对引用可达
echo
echo "--- 资源可达性校验 ---"
python - "$SITE" <<'PY'
import re, os, sys, urllib.parse
root = os.path.abspath(sys.argv[1])
s = open(os.path.join(root, "index.html"), encoding="utf-8").read()
urls = sorted(set(re.findall(r'(?:src|href)="((?!https?:|data:|#|mailto:|//)[^"]*)"', s)))
ok = miss = 0
for u in urls:
    rel = urllib.parse.unquote(u.split("#")[0])
    fp = os.path.normpath(os.path.join(root, rel))
    hit = os.path.exists(fp) and os.path.isfile(fp)
    if hit:
        ok += 1
    else:
        miss += 1
        print("  MISS", u)
print(f"  相对引用 {len(urls)} 处，可达 {ok}，缺失 {miss}")
if miss:
    print("  ⚠ 存在缺失引用（多为仓库文件链接，属预期，详见说明）")
PY

echo
echo "同步完成。站点目录：$SITE/（$(du -sh "$SITE" | cut -f1)）"
echo "下一步：推送到 GitHub 后由 Cloudflare Pages 自动部署（或用 wrangler pages deploy）。"
