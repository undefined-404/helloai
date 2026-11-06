# HelloAI 发版与升级操作手册（Windows / Git Bash）

> 适用：在自己的 Windows 机器上打 `0.2.x` 这类小版本，产物推到 ACR + Gitee，用户一条命令安装或升级。
> 平台：Windows 10/11 + Docker Desktop + Git for Windows。macOS 差异见文末第 14 节，后续另立分册。
> 依据：本文所有命令均取自仓库现役脚本（`deploy/quickstart/build-release.sh`、`deploy/install.sh`、`deploy/quickstart/start.sh`），版本 `0.2.1` 已实际跑通一遍。

---

## 速查：只想跑一遍

> 下面以发 `0.2.2` 举例；版本号换成你要发的号，路径换成你的仓库路径。

```bash
# ① 登录（密码是 ACR 控制台设的固定密码，不是阿里云登录密码）
docker login --username=<你的阿里云账号> crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com

# ② 构建 + 推 ACR + 打包（8~15 分钟；只改了 deploy/ 下的脚本就加 --bundle-only，10 秒）
cd /e/yhzx/1027/helloai/deploy/quickstart
./build-release.sh --version 0.2.2 --push crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai --skip-sandbox

# ③ 自检：三项 OK + 镜像 digest 本地等于远端
cd /e/yhzx/1027/helloai/dist/0.2.2 && sha256sum -c SHA256SUMS-0.2.2.txt

# ④ 提交 + 打 tag（代码有实体改动时按目录分组提交，见 §8.1）
cd /e/yhzx/1027/helloai
git commit -m "chore(release): 0.2.2" -- deploy README.md
git push origin master
git tag -a v0.2.2 -m "HelloAI v0.2.2" HEAD
git push origin v0.2.2
git ls-remote --tags origin | grep v0.2.2      # ^{} 那行必须是你的最新提交

# ⑤ 网页：Gitee 仓库 → 发行版 → 新建（或编辑）→ 上传 dist/0.2.2/helloai-quickstart-0.2.2.tar.gz
```

**别做的事**：不删旧的同名镜像 tag（会自动覆盖）、不加 `--no-cache`、不在构建期间改文件、不传 `helloai-offline-core`（556 MB 超附件上限）。

下面是要做对每一步时的细节。

---

## 0. 先把四种"东西"分清楚

发版混乱几乎都来自把下面四样当成一样。

| # | 名称 | 存在哪里 | 谁产生 | 用户怎么拿到 | 本次是否需要动 |
|---|---|---|---|---|---|
| ① | **镜像** `app` / `web` | ACR（阿里云镜像仓库） | `build-release.sh` 构建 + 你 `push` | `docker pull`（匿名可拉） | 代码有后端/前端改动时才动 |
| ② | **发行版附件** `helloai-quickstart-X.tar.gz` | Gitee 发行版附件区 | `build-release.sh` 打包 + 你网页上传 | `install.sh` 第一候选下载 | **每次都要传**（脚本改了就必须传） |
| ③ | **发行版源码包** `vX.tar.gz` / `.zip` | Gitee 服务器**现场生成** | Gitee 按 **tag** 打包，**不可上传** | 发行版页面底部"源码下载" | 只靠**移动 tag** |
| ④ | **离线镜像包** `helloai-offline-core-X.tar.gz` | 本地 `dist/X/`（可选上传） | `docker save` | 断网环境 `./start.sh --offline` | 默认不传（556 MB，超 Gitee 单文件限制） |

四条铁律：

1. **②和③是两条独立通道。** 附件改了不影响源码包，源码包只认 tag。
2. **③不能上传、不能提交。** 它是 Gitee 在你点下载那一刻按 tag 现场打的包。唯一能影响它的动作是 `git push -f origin vX`。
3. **①②必须版本号一致。** `docker-compose.yml` 里 `app` 和 `web` 是同版本的一对；nginx 按服务名反代 `app:6565`，错版会 502。
4. **tag 必须指向"含本次全部改动"的提交**，否则第 ③ 条拿到旧树。

---

## 1. 前置环境（只需配一次）

### 1.1 Git Bash 是唯一可用终端

Windows 上 `bash` 这个名字被 WSL 占用（`C:\Windows\system32\bash.exe`），`cmd` / PowerShell 里直接敲 `bash xxx.sh` 会进 WSL 而不是 Git Bash。本项目的脚本是 POSIX bash + 路径处理，**必须在 Git Bash 里跑**。

打开方式（任选）：

- 文件资源管理器 → 右键仓库目录 → **Git Bash Here**
- 开始菜单搜 **Git Bash** → `cd /e/yhzx/1027/helloai`

路径写法：Git Bash 里盘符是 `/e/` 而不是 `E:\`。

```bash
cd /e/yhzx/1027/helloai        # 等价于 E:\yhzx\1027\helloai
```

> 如果你的 GitHub 装在非默认位置，Git Bash 的可执行文件是 `E:\Program Files\Git\bin\bash.exe`。
> 非要在 PowerShell 里调，只能用这一种写法（**不能用 `\` 换行**，PowerShell 的续行符是反引号）：
> ```powershell
> & "E:\Program Files\Git\bin\bash.exe" -lc "cd /e/yhzx/1027/helloai/deploy/quickstart && ./build-release.sh --version 0.2.1 --push crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai --skip-sandbox"
> ```

### 1.2 Docker Desktop 必须处于运行状态

```bash
docker info | head -3          # 期望看到 Server Version: 29.x
docker buildx version           # 期望 v0.x（缺了会回落经典 builder，只影响速度）
```

失败最常见原因：Docker Desktop 没启动，或 WSL2 后端没起来。**先把 Desktop 打开，等鲸鱼图标变绿**。

### 1.3 磁盘余量

```bash
df -h /e | tail -1
```

`dist/` 产物约 600 MB（含 556 MB 离线包），Docker 构建缓存约 2 GB。**建议留 10 GB 以上**。

### 1.4 ACR 认证（只在需要推镜像时）

见第 3 节。

---

## 2. 全流程总览

```
登录 ACR → 改版本号(4 处) → 冻结工作区 → 一条命令(构建+推+打包)
      → 自检镜像与包 → 提交代码 → 打 tag + push
      → Gitee 上传附件 → 验收
```

| 步骤 | 本节 | 耗时（有缓存） | 耗时（无缓存） | 可跳过条件 |
|---|---|---|---|---|
| 登录 ACR | §3 | 1 分钟 | — | 只出本地包、不推镜像 |
| 改版本号 | §4 | 10 分钟 | — | 版本号没变 |
| 冻结工作区 | §5 | 1 分钟 | — | 不可跳过 |
| 一条命令构建+推+打包 | §6 | 8~15 分钟 | 25~40 分钟 | 只改了脚本 → 用 `--bundle-only`，**10 秒** |
| 自检 | §7 | 2 分钟 | — | 不可跳过 |
| 提交 + tag + push | §8 | 3 分钟 | — | 不可跳过 |
| Gitee 上传附件 | §9 | 2 分钟 | — | 不可跳过 |
| 验收 | §10 | 3 分钟 | — | 不可跳过 |

**只有 `deploy/` 下的脚本/文档变动时**（比如这次加自动升级），镜像**一个字节都不用重打**：

```bash
cd /e/yhzx/1027/helloai/deploy/quickstart
./build-release.sh --version 0.2.1 --bundle-only
```

因为 `Dockerfile` 只 `COPY` 了 `helloai-common` / `helloai-mq` / `helloai-job` / `helloai-core` / `helloai-api` / `helloai-start` / `helloai-ui` 七个模块，**从不 COPY `deploy/`**。`deploy/` 只进 quickstart 包。

---

## 3. 登录 ACR（推镜像前必须）

### 3.1 先搞清楚要推到哪

| 项 | 值 |
|---|---|
| Registry 域名 | `crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com` |
| 命名空间 | `helloai` |
| 镜像仓库名 | `app`、`web`（由脚本 `APP_REPO` / `WEB_REPO` 决定，默认就是这两个） |
| 完整镜像地址 | `crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai/app:0.2.1` |

**推送必须登录，拉取不用**（公开仓库，匿名可拉）。

### 3.2 拿登录密码

ACR **个人版**的密码不是阿里云账号登录密码，要单独设置：

1. 登录阿里云 → 控制台 → 搜「**容器镜像服务**」
2. 左侧「**个人实例**」→「**访问凭证**」
3. 设置「**固定密码**」（用于 `docker login`；也可用临时密码，但会过期）
4. 记下**用户名**（一般是阿里云账号全名，如 `xxx@example.com` 或注册手机号）

### 3.3 执行登录

```bash
docker login --username=你的用户名 crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com
# 提示 Password: 时粘贴 3.2 设置的固定密码（输入不回显，直接回车）
```

期望输出：

```
Login Succeeded
```

### 3.4 验证

```bash
# 已登录的 registry 会出现在这里（凭据在 C:\Users\<你>\.docker\config.json，base64 编码，不是加密）
cat ~/.docker/config.json | grep -A2 crpi
```

失败对照：

| 报错 | 原因 | 处理 |
|---|---|---|
| `unauthorized: authentication required` | 没登录 / 密码过期 | 重新 `docker login` |
| `Error response from daemon: Get "https://...": dial tcp: ... timeout` | 网络 / 代理 | 检查 Docker Desktop 的代理设置 |
| `denied: requested access to the resource is denied` | 用户名或命名空间不对 | 核对 3.1 的 `helloai` 是否是你的命名空间 |

---

## 4. 改版本号（4 个现役文件）

版本号在仓库里是**手写多份**的，漏改必然分叉。以下按 `0.2.1` → `0.2.2` 举例，用 `grep` 定位后逐处替换。

| 文件 | 处数 | 内容性质 |
|---|---|---|
| `deploy/quickstart/.env.example` | 2 | **最关键**：两行镜像地址，包内 `.env` 由它生成 |
| `deploy/quickstart/README.md` | ~25 | 面向用户的说明：示例命令、产物大小表、目录树 |
| `README.md`（仓库根） | ~6 | 一键安装段落里的 `HELLOAI_REF` 镜像地址 |
| `deploy/install.sh` | 3 | 注释与示例里的 `HELLOAI_REF=v0.2.1` |

`deploy/quickstart/rejoin.sh` 与 `build-release.sh` 里的版本号只是**示例注释**（真实值由 `--version` 参数传入），可以不改。

先看清楚有哪些：

```bash
cd /e/yhzx/1027/helloai
grep -n "0\.2\.1" deploy/quickstart/.env.example deploy/install.sh README.md | head -20
```

改完必须复核：

```bash
grep -rn "0\.2\.1" deploy/quickstart/.env.example deploy/install.sh README.md deploy/quickstart/README.md | wc -l
# 期望 0（如果保留历史沿革说明，允许剩少量，但要逐条确认）
```

> **改完再构建。** 构建期间不要动任何源文件——`docker build` 的 `COPY` 在**开始那一刻**就把源码拷进构建上下文了，中途改文件不会被带进镜像，产物自洽但与工作区不一致。这是 `0.2.1` 首版踩过的坑（12:01 的改动没进 12:27 构建的镜像）。

---

## 5. 冻结工作区

```bash
cd /e/yhzx/1027/helloai
git status --porcelain -uall
```

**看到输出就要想清楚**：每一行是否是你希望进本次镜像的改动？

- 想进 → 保持，构建后一起提交
- 不想进 → 现在就还原（`git checkout -- <file>`）

构建开始后到 `docker build` 结束，**别再存盘**。

---

## 6. 一条命令完成构建 + 推送 + 打包

### 6.1 命令

```bash
cd /e/yhzx/1027/helloai/deploy/quickstart

./build-release.sh --version 0.2.1 --push crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai --skip-sandbox
```

三个参数的含义：

| 参数 | 作用 | 是否必须 |
|---|---|---|
| `--version 0.2.1` | 版本号，同时决定本地 tag、远端 tag、`dist/0.2.1/` 目录名 | **必须** |
| `--push <reg>/<ns>` | 构建后 `docker tag` + `docker push` 到 `<reg>/<ns>/app:X` 与 `/web:X` | 要推 ACR 就加 |
| `--skip-sandbox` | 不产 `helloai-offline-sandbox-X.tar.gz`（`python:3.12` 单镜像就约 1 GB） | 建议加 |

如果提示 `Permission denied`（换机器 clone 后常见的执行位丢失）：

```bash
bash build-release.sh --version 0.2.1 --push crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai --skip-sandbox
```

### 6.2 它内部做了什么

按顺序，任一步失败即 `set -e` 退出：

1. `preflight`：检查 docker 守护进程、`Dockerfile`、8 个 bundle 文件、`pull-sandbox-images.sh`
2. `build_images`：
   - `docker build -f Dockerfile --target app -t helloai-app:0.2.1 .`
   - `docker build -f Dockerfile --target web -t helloai-web:0.2.1 .`
3. `ensure_core_images`：缺哪个中间件镜像就 `docker pull`（pgvector / redis / rabbitmq / minio）
4. `stage_bundle`：把 8 个 bundle 文件 + `deploy/pull-sandbox-images.sh` 拷到 `dist/0.2.1/helloai-quickstart-0.2.1/`，`sed` 改写 `.env.example` 两行镜像地址，写 `RELEASE` 元数据，`chmod 755 *.sh`，`tar -czf`
5. `save_core`：`docker save` app + web + 4 中间件 → `helloai-offline-core-0.2.1.tar.gz`（约 556 MB，几分钟）
6. `save_sandbox`：`--skip-sandbox` 时跳过
7. `write_manifest` → `IMAGES-0.2.1.txt`（镜像 ID + digest）
8. `write_checksums` → `SHA256SUMS-0.2.1.txt`
9. `push_images`：`docker tag` 后 `docker push` 两个镜像

**注意：脚本自己不做 `docker login`。** 没登录会在第 9 步报 `unauthorized`，前面 8 步白跑（但镜像和包已在本地，可 `--no-build` 重跑）。

### 6.3 参数能力矩阵（别用错）

| 命令 | 重构建镜像 | 推 ACR | 重打包 quickstart | 产离线包 | 适用场景 |
|---|---|---|---|---|---|
| `--version X` | ✅ | ❌ | ✅ | ✅ | 只本地出包 |
| `--version X --push R` | ✅ | ✅ | ✅ | ✅ | **标准发版** |
| `--version X --push R --skip-sandbox` | ✅ | ✅ | ✅ | core | **推荐**：省 1 GB |
| `--version X --no-build --push R` | ❌ 复用本地 tag | ✅ | ✅ | ✅ | 镜像已构建好，只补推送 |
| `--version X --bundle-only` | ❌ | ❌ | ✅ | ❌ | **只改了 `deploy/` 脚本/文档** |

两个陷阱：

- **`--no-build` 会用本地同名 tag**。上次构建有问题时千万别加，否则把坏镜像原样推上去。
- **不要加 `--no-cache`**。那会让 Maven 重新下载全部依赖，白等半小时；buildx 按文件 checksum 判断缓存，源码变了必然重编，不需要手动清。
- **`--bundle-only` 不推镜像**（代码里在 `push_images` 之前就 `exit 0`），`--push` 对它的唯一作用是决定包内 `.env.example` 写哪个地址。

### 6.4 期望输出（尾部）

```
[release] pushing crpi-.../helloai/app:0.2.1
[release] pushing crpi-.../helloai/web:0.2.1

================================================================
Release 0.2.1 is ready: /e/yhzx/1027/helloai/dist/0.2.1
...
================================================================
```

### 6.5 旧同名 tag 不用删

`docker build -t helloai-app:0.2.1` 会直接把 tag 换指向，旧镜像变 `<none>` 悬空，不冲突。
ACR 个人版默认允许同名 tag 覆盖 push。
**唯一例外**：控制台开了「镜像版本不可变」会 `DENIED`，那才需要去网页删。事后清悬空镜像：`docker image prune -f`（只清 `<none>`，不动有 tag 的）。

---

## 7. 自检（不可跳过）

### 7.1 镜像里到底有没有本次改动

`docker images` 的创建时间**不代表代码版本**——构建时刻早于文件修改，镜像里就是旧代码。必须进镜像查。

```bash
cd /e/yhzx/1027/helloai
mkdir -p .tmp

# 取出 jar（--name 复用会冲突，先清）
docker rm -f _chk _chkw >/dev/null 2>&1
docker create --name _chk helloai-app:0.2.1 >/dev/null
docker cp _chk:/app/helloai-start.jar .tmp/chk.jar
docker rm _chk >/dev/null

# ① 迁移脚本（改成你本次新增的版本号区间）
unzip -l .tmp/chk.jar | grep -E "db/migration/V14[3-9]"

# ② 后端类是否存在（示例：本次新增字段）
unzip -l .tmp/chk.jar | grep -i "SysPermission"

# ③ 前端产物标记（换成本次新增的 CSS 类 / 标识符，注意用前端里的驼峰写法）
docker create --name _chkw helloai-web:0.2.1 >/dev/null
docker cp _chkw:/usr/share/nginx/html .tmp/webchk >/dev/null 2>&1
docker rm _chkw >/dev/null
grep -rl "isDefaultHome" .tmp/webchk | head -2
```

判据：

- 迁移列表**最后一版必须是本次新增的那一版**（本次期望看到 `V143` `V144` `V145`）
- 关键符号命中，而不是"文件存在"
- **前端查驼峰、后端查 `javap` 真实符号**。拿 Java 侧的方法名去 grep 前端产物必然 0 命中（打包时已压缩改名），会误判成"没打进去"

> 需要注意 `.tmp/` 已在 gitignore 内，检查文件不要落在仓库根目录。

### 7.2 包内容与校验和

```bash
cd /e/yhzx/1027/helloai/dist/0.2.1
sha256sum -c SHA256SUMS-0.2.1.txt
```

期望三项 `OK`。

再确认包内 `.env.example` 指向的是**可拉取的地址**（不是本地 tag）：

```bash
tar xzOf helloai-quickstart-0.2.1.tar.gz --wildcards '*/.env.example' | grep IMAGE
# 期望：...aliyuncs.com/helloai/app:0.2.1
# 若显示 helloai-app:0.2.1 → 消费者拉不到，必须带 --push 重打
```

### 7.3 确认 ACR 上的对象就是本地这个

```bash
docker image inspect --format 'local : {{.Id}}' helloai-app:0.2.1
docker image inspect --format 'remote: {{index .RepoDigests 0}}' crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai/app:0.2.1
```

两行 digest 必须**逐字符一致**。不一致说明 push 没成功或推的是别的 tag。

---

## 8. 提交代码 + 打 tag

### 8.1 提交

`git commit -- <path>` **只提交点名的文件**，未跟踪的新文件必须先 `git add`。

```bash
cd /e/yhzx/1027/helloai
git status --porcelain -uall            # 复核一遍再提交

# 按目录分组提交，示例（路径按实际改动替换）
git commit -m "feat(rbac): 菜单一级分组 + 登录落点可配置（V143~V145）" -- \
  helloai-start/src/main/resources/db/migration helloai-core/src/main/java/com/helloai/core/system
git commit -m "feat(ui): 宽屏断点与 AI 免责声明" -- helloai-ui/src
git commit -m "chore(release): 版本号 0.2.2 与 quickstart 文档同步" -- .gitattributes README.md deploy
git commit -m "docs(log): 当日日志回填" -- doc

git push origin master
```

> 注意 `git commit -- <pathspec>` **不包含未跟踪的新文件**，新增文件必须先 `git add`。
> 共享工作树（多处同时改）时务必显式列路径，别用 `git commit -a`，会把别人或自己调试用的改动（如 `application.yml` 的 `profiles.active`）一起带进去。

提交信息按 `type(scope): 中文描述`，**分组提交**（后端 / 前端 / 部署 / 文档各一条）。

### 8.2 打 tag

```bash
git tag -a v0.2.1 -m "HelloAI v0.2.1" HEAD
git push origin v0.2.1
```

**如果这个 tag 之前已经推过**（改版重发），必须先删再建，然后 `-f`：

```bash
git tag -d v0.2.1
git tag -a v0.2.1 -m "HelloAI v0.2.1" HEAD
git push origin v0.2.1 -f
```

### 8.3 验证 tag（这一步决定源码包新旧）

```bash
git ls-remote --tags origin | grep v0.2.1
```

输出形如：

```
aa68c289...    refs/tags/v0.2.1          ← tag 对象
6858a724...    refs/tags/v0.2.1^{}       ← 实际指向的提交，必须是你的最新提交
```

**`^{}` 那行是真相**。如果还是旧哈希，说明 `-f` push 没生效（可能被 tag 保护拦住），源码包会一直是旧树。

### 8.4 验证源码包（实测，最硬的证据）

```bash
cd /e/yhzx/1027/helloai
curl -fsSL -o .tmp/arch.tar.gz "https://gitee.com/undefined_404/helloai/repository/archive/v0.2.1.tar.gz?t=$(date +%s)"
tar xzOf .tmp/arch.tar.gz --wildcards '*/deploy/install.sh' | grep -c "PREV_VER"
```

> URL 后面拼 `?t=时间戳` 是为了绕 CDN 缓存；实测改完 tag 后立刻生效，不需要等待。

判据：本次改了什么就去 grep 什么。数字从 `0` 变成非 `0` 即生效。

---

## 9. Gitee 发行版

**顺序不能错：先 push tag，再建/编辑发行版。** 否则发行版页面的标签下拉里没有 `v0.2.1`，Gitee 可能基于某个分支现场造一个 tag，那就和你的提交对不上了。

### 9.1 首次创建发行版

1. 打开 `https://gitee.com/undefined_404/helloai` → 右侧「**发行版**」
2. 「**创建发行版**」
3. **标签版本**：下拉选 `v0.2.1`（没有就先回去做 8.2）
4. **发行版标题**：`quickstart 0.2.1`
5. **发行版介绍**：写本次变更（示例见 9.4）
6. **附件**：上传 `E:\yhzx\1027\helloai\dist\0.2.1\helloai-quickstart-0.2.1.tar.gz`
7. 「**发行版**」保存

### 9.2 已存在同名发行版时（改版重发）

Gitee **不支持同名附件覆盖上传**，会提示附件已存在。流程：

1. 发行版页面 → 右上「**编辑**」
2. 附件区找到旧的 `helloai-quickstart-0.2.1.tar.gz` → **先删除**
3. 再「上传附件」传新包
4. 保存

### 9.3 附件清单：只传一个

| 文件 | 大小 | 传不传 | 原因 |
|---|---|---|---|
| `helloai-quickstart-0.2.1.tar.gz` | ~29 KB | **必须传** | `install.sh` 第一候选，硬拼这个文件名 |
| `helloai-offline-core-0.2.1.tar.gz` | ~556 MB | **默认不传** | 超过 Gitee 附件体积上限（免费账号单个约 100 MB）；包内 `.env` 指向 ACR，`./start.sh` 直接拉 |
| `helloai-offline-sandbox-0.2.1.tar.gz` | ~711 MB | 不传 | 同上，且本次 `--skip-sandbox` 没产 |
| `IMAGES-0.2.1.txt` | ~1.2 KB | 可选 | 想公开镜像清单就传 |
| `SHA256SUMS-0.2.1.txt` | ~281 B | 可选 | 想让人校验就传 |

> 真要给断网环境发核心包：用 `rejoin.sh` 的切片方案——`split -b 95M -d -a 3 helloai-offline-core-0.2.1.tar.gz helloai-offline-core-0.2.1-part-`，切成 6 个 `part-00x` 逐个上传，用户 `./rejoin.sh 0.2.1` 拼回后校验。

**文件名必须逐字一致**。`install.sh` 是这么拼的（第 60 行）：

```bash
"$REPO/releases/download/$REF/helloai-quickstart-${REF#v}.tar.gz"
```

传成 `.zip`、传成 `helloai-quickstart-0.2.1-v2.tar.gz`，这条 URL 就 404，一键安装直接断。

### 9.4 发行版说明模板

```
quickstart 0.2.1 —— 新增个人工作台、额度浮窗、菜单一级分组、登录落点可配置

安装（需 Docker）：
curl -fsSL https://gitee.com/undefined_404/helloai/raw/master/deploy/install.sh | HELLOAI_REF=v0.2.1 bash

已装旧版本的机器：同一条命令，会自动停旧栈（保留数据卷）并升级镜像标签。

镜像（公开，匿名可拉）：
crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai/app:0.2.1
crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai/web:0.2.1
```

### 9.5 源码包：什么都不用做

发行版页面底部自动出现 `v0.2.1.zip` / `v0.2.1.tar.gz`，由 Gitee 按 tag 现场打包。

- **不能上传，不能删除，不能提交**
- 它跟 tag 走：tag 挪了，下次下载自然就是新的
- 改 tag 后**不用重建发行版**

---

## 10. 验收（模拟真实用户）

### 10.1 验证附件可用（在任意能上网的机器）

```bash
cd /tmp
curl -fsSL -o qs.tar.gz "https://gitee.com/undefined_404/helloai/releases/download/v0.2.1/helloai-quickstart-0.2.1.tar.gz"
sha256sum qs.tar.gz
# 与本地 dist/0.2.1/SHA256SUMS-0.2.1.txt 里那一行比对，必须一致
tar tzf qs.tar.gz | head -3
```

### 10.2 验证镜像匿名可拉

```bash
docker logout crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com   # 先退登录，模拟普通用户
docker pull crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai/app:0.2.1
```

退登录后拉不动 = 仓库不是公开的，用户装到一半会 401。

### 10.3 服务器上升级（已装旧版本）

```bash
curl -fsSL https://gitee.com/undefined_404/helloai/raw/master/deploy/install.sh \
  | HELLOAI_REF=v0.2.1 HELLOAI_INSTALL_DIR=/opt/helloai bash
```

**不需要先 `./stop.sh`**：0.2.1 起 `start.sh` 的 `check_port` 会识别"占 8080 的是自己这一栈"，自动 `compose down --remove-orphans`（不带 `-v`，四个数据卷保留）后继续。只有 0.2.0 及更早版本才需要手动先停。

期望依次看到：

```
upgrade detected: 0.2.0 -> 0.2.1 (data volumes and .env secrets are kept)
upgrade: HELLOAI_APP_IMAGE .../app:0.2.0 -> .../app:0.2.1
upgrade: HELLOAI_WEB_IMAGE .../web:0.2.0 -> .../web:0.2.1
an existing HelloAI stack owns port 8080; stopping it to apply the upgrade
```

缺第一行 = `install.sh` 是旧的（检查 `raw/master` 是否含 `PREV_VER`）；缺中间两行 = `sync_image_tags` 没命中（检查 `.env` 里的仓库前缀是否被你改成了别的）。

### 10.4 数据影响（升级前心里有数）

| 内容 | 影响 |
|---|---|
| 任务 / 对话 / 知识库 / 附件 | **不动**（在 `postgres_data` / `minio_data` 卷里） |
| `HELLOAI_CREDENTIAL_AES_KEY_BASE64` 等 7 个 secret | **不动**（`sync_image_tags` 只改两行镜像地址） |
| 菜单结构 | 有新迁移时会按 `code` 重挂父级并重排 `sort`，手工调过的会被覆盖 |
| 管理员密码 | 不动（种子只在 `V1`，已 applied 不会重跑） |

大版本升级前备份：

```bash
docker ps --format '{{.Names}}' | grep postgres     # 先确认真实容器名
docker exec <上一步的容器名> pg_dump -U postgres helloai > /root/helloai-pre-upgrade.sql
```

库用户是 `postgres`、库名是 `helloai`（见 `docker-compose.yml` 的 `POSTGRES_USER` / `POSTGRES_DB`），**不是** `-U helloai`。

容器名的规律是 `<compose 项目名>-postgres-1`，而项目名取**安装目录名**（`docker-compose.yml` 顶层没有 `name:`）。所以装在 `/opt/helloai` 是 `helloai-postgres-1`，直接解压包里目录跑则是 `helloai-quickstart-0.2.1-postgres-1`。别硬记，用 `docker ps` 看。

---

## 11. 故障对照表

| 现象 | 根因 | 处理 |
|---|---|---|
| `bash: command not found` 或进了 WSL | `bash` 被 WSL 占用 | 用 Git Bash 终端，或 `& "E:\Program Files\Git\bin\bash.exe" -lc "..."` |
| `Permission denied: ./build-release.sh` | clone 后执行位丢失 | `bash build-release.sh ...` |
| `$'\r': command not found` / `bad interpreter` | CRLF 换行 | 仓库已用 `.gitattributes` 强制 `eol=lf`；本地残留则 `sed -i 's/\r$//'` 该文件 |
| `unauthenticated` / `unauthorized` | 没 `docker login` | 见第 3 节 |
| `denied: requested access to the resource is denied` | 推送仓库名与命名空间不符 | 核对 `--push` 前缀；必要时 `APP_REPO=` / `WEB_REPO=` 覆盖 |
| 镜像构建成功但功能没生效 | 构建时刻早于文件修改 | 第 7.1 节进镜像查；确认工作区已冻结后重打 |
| 包内 `.env.example` 是 `helloai-app:0.2.1` | `--bundle-only` 未带 `--push` 且无旧 `RELEASE` 可继承 | 补 `--push <reg>/<ns>` 重打（脚本已从旧 `RELEASE` 继承 `registry`，正常不会出现） |
| 用户装完还是旧版本 | ① `.env` 里镜像 tag 未更新（0.2.0 时代的老问题）；② **在安装目录内执行 install，包落进 `./helloai` 子目录，原目录一个字没动** | ② 先 `find <安装根> -maxdepth 3 -name RELEASE` 确认到底装到哪了；0.2.1 起 `install.sh` 已改为「当前目录有 `docker-compose.yml`+`start.sh` 就就地升级」，但旧版脚本仍会嵌套 |
| `docker ps` 里容器是新起的，`IMAGE` 却还是旧 tag | compose 写死 `name: helloai-quickstart`，宿主机上两个安装目录 = 同一个 stack、同一批卷，**旧目录的 `./start.sh` 把容器顶回去了** | 同一宿主只保留一个安装目录；判断版本用 `docker compose config \| grep -E "image:.*helloai/(app\|web)"`，它只反映**当前目录**的解析结果 |
| 端口 8080 被占直接退出 | 旧栈仍在跑 | 0.2.1 起自动 `down` 自家栈；非自家进程占用则改 `HELLOAI_HTTP_PORT` |
| 发行版附件下载 404 | 文件名不对（如传了 `.zip`） | 必须叫 `helloai-quickstart-<版本>.tar.gz` |
| 发行版源码包是旧的 | tag 没挪 | 见 8.2 / 8.3 |

---

## 12. 回滚

### 12.1 用户侧回滚到上一版

```bash
cd /opt/helloai
./stop.sh
sed -i -E 's#(HELLOAI_(APP|WEB)_IMAGE=.*):0\.2\.1#\1:0.2.0#' .env
./start.sh
```

**数据库不会自动降级**。若新版跑过 `INSERT`/`ALTER`，回滚后可能因缺列报错，需要用 10.4 的备份恢复。

### 12.2 发布侧回滚

- **镜像**：ACR 上 `0.2.0` 仍在，重新推 `0.2.0` tag 到 `0.2.1` 即可覆盖
- **附件**：重新上传旧包
- **tag**：`git tag -f -a v0.2.1 <旧提交>` 后 `push -f`
- **发行版**：Gitee 支持删除整条发行版

---

## 13. 常用命令速查

```bash
# 环境
docker info | head -3
df -h /e | tail -1

# 登录
docker login --username=<用户名> crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com

# 标准发版（构建 + 推送 + 打包）
cd /e/yhzx/1027/helloai/deploy/quickstart
./build-release.sh --version 0.2.1 --push crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai --skip-sandbox

# 只重打包（改了 deploy/ 下的脚本或文档）
./build-release.sh --version 0.2.1 --bundle-only

# 复用已构建镜像，只补推送
./build-release.sh --version 0.2.1 --no-build --push crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai --skip-sandbox

# 自检
cd /e/yhzx/1027/helloai/dist/0.2.1 && sha256sum -c SHA256SUMS-0.2.1.txt
docker image inspect --format 'local : {{.Id}}' helloai-app:0.2.1
docker image inspect --format 'remote: {{index .RepoDigests 0}}' crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai/app:0.2.1

# 提交 + tag
git status --porcelain -uall
git commit -m "type(scope): 描述" -- <路径>
git push origin master
git tag -d v0.2.1 && git tag -a v0.2.1 -m "HelloAI v0.2.1" HEAD
git push origin v0.2.1 -f

# 验证 tag 与源码包
git ls-remote --tags origin | grep v0.2.1
curl -fsSL -o /tmp/a.tgz "https://gitee.com/undefined_404/helloai/repository/archive/v0.2.1.tar.gz?t=$(date +%s)"
```

---

## 14. macOS 分册（后续另立）

本手册命令在 macOS 上 95% 通用，差异仅四点，届时另起一份 macOS 分册：

| 项 | Windows | macOS |
|---|---|---|
| 终端 | Git Bash（`bash` 被 WSL 占名） | 系统自带 `bash` / `zsh` |
| 路径 | `/e/yhzx/1027/helloai` | `~/code/helloai` |
| 哈希命令 | `sha256sum`（Git Bash 自带） | `shasum -a 256`（脚本已自动检测并回落） |
| Docker | Docker Desktop for Windows（WSL2 后端） | Docker Desktop for Mac（Apple Silicon 需注意 `linux/amd64` 中间件镜像） |

---

## 附：本次 0.2.1 的实测记录（可作参照基线）

| 项 | 值 |
|---|---|
| app 镜像 | `211557c7abd4` @ 13:38，含 `V143`/`V144`/`V145` |
| web 镜像 | `db4fab34e26a` @ 12:28 |
| quickstart 包 | 29,303 字节，`sha256 572701056eddb053f51d248e7089fd0fb3d48ce4b9f033ad81128cf3787a7a64` |
| 源码包（改 tag 前） | 33,689,365 字节，`install.sh` 内 `PREV_VER` 命中 0 |
| 源码包（改 tag 后） | 33,721,947 字节，`install.sh` 内 `PREV_VER` 命中 4 |
| ACR digest | 本地与远端 `211557c7…` / `db4fab34…` 逐字符一致 |
