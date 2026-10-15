# docs-site —— HelloAI 单页站点（Cloudflare Pages 部署源）

> 由根目录 `README.md` 生成的单页交互版（原 `README.html` 的副本）+ 其依赖资源。
> 用途：部署到 Cloudflare Pages 作为在线站点（`*.pages.dev`）。
> **根目录 `README.html` 已删除**（Gitee 首页回归 `README.md`），本目录的 `index.html` 为站点唯一事实源。

## 构成

| 内容 | 说明 |
|---|---|
| `index.html` | 单页交互版（原 `README.html` 的副本，站点唯一事实源） |
| `doc/diagrams/html/*.html` | 6 张交互式架构图（自包含 iframe，无外部依赖） |
| `doc/diagrams/*.svg` | 9 张 README 直接引用的架构图 |
| `doc/images/*.png` | 4 张 UI 截图 |

> 站点外链接（预期行为）：`index.html` 中指向仓库文件的链接
> （`LICENSE` / `Dockerfile` / `deploy/...` / `doc/log/` 等）在静态站点内无法打开。

## 如何更新

改完根目录 `README.md` 后，执行一键同步脚本（会重新生成 HTML 并刷新全部资源）：

```bash
bash scripts/sync-docs-site.sh
```

## 如何部署

### 方式一：GitHub 仓库 + Cloudflare Pages Git 集成（推荐）

1. 新建 GitHub 仓库（如 `helloai-docs`），把 `docs-site/` 内容提交并推送。
2. Cloudflare Dashboard → Workers & Pages → **Create application** → **Pages** → **Connect to Git**。
3. 选择该 GitHub 仓库 → 生产分支 `main`（或 `master`）。
4. 构建配置：
   - **Build command**：留空（纯静态，无需构建）
   - **Build output directory**：`/`（仓库根即站点内容）
5. **Save and Deploy** → 得到 `https://<project>.pages.dev`。

> 若仓库根就是站点内容，输出目录填 `/` 即可（内容直接上传）。

### 方式二：wrangler CLI 直推（不依赖 Git）

```bash
npm i -g wrangler        # 或 npx wrangler
npx wrangler login       # 浏览器授权 Cloudflare 账号
npx wrangler pages deploy docs-site --project-name helloai-docs
```

## 注意

- Cloudflare Pages 的 **Git 集成只支持 GitHub / GitLab**，不支持 Gitee。
- 免费版：每月 500 次构建、无限静态请求与带宽，足够本项目。
