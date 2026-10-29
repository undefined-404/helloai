# HelloAI Quickstart

**一条命令体验 HelloAI：不需要源码、不需要 JDK / Maven / Node，也不需要在本机构建镜像。**

## 0. 先拿到发布包

两种方式，**优先方式一**（一条命令，不用下大包）。

### 方式一：公开镜像仓库（推荐）

`app` / `web` 两个预构建镜像放在公开 ACR 上，**匿名可拉、不需要登录**：

```
crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai/app:0.2.0
crpi-y0c6x9ls3zo4d4je.cn-beijing.personal.cr.aliyuncs.com/helloai/web:0.2.0
```

仓库地址已写进 `.env.example`，所以只需：

```bash
tar xzf helloai-quickstart-0.2.0.tar.gz
cd helloai-quickstart-0.2.0
./start.sh
```

> 中间件（postgres / redis / rabbitmq / minio）仍来自 Docker Hub。国内建议先配好
> Docker 镜像加速（`/etc/docker/daemon.json` 的 `registry-mirrors`），否则这一步会慢。
>
> 升级到新版本：改 `.env` 里的 `HELLOAI_APP_IMAGE` / `HELLOAI_WEB_IMAGE` 标签，
> 再 `./start.sh` 即可（Docker 层缓存，只拉变化的层）。

### 方式二：离线包（断网 / 镜像仓库不可达时用）

> 离线包（core + sandbox + 清单，共 5 个文件）由**发布说明**提供下载链接。
> 断网 / 镜像仓库不可达时才需要它。

| 文件 | 大小 | 要不要 |
|---|---|---|
| `helloai-quickstart-0.2.0.tar.gz` | 28 KB | **必须**（脚本 + compose + 本说明） |
| `helloai-offline-core-0.2.0.tar.gz` | 556 MB | **必须**（app + web + 4 个中间件镜像） |
| `helloai-offline-sandbox-0.2.0.tar.gz` | 711 MB | 可选，仅 `--with-sandbox` 需要 |
| `IMAGES-0.2.0.txt` | 2.4 KB | 建议（镜像 ID / digest 清单） |
| `SHA256SUMS-0.2.0.txt` | 384 B | 建议（校验下载完整性） |

```bash
# 1) 校验（建议）
sha256sum -c SHA256SUMS-0.2.0.txt

# 2) 解压并启动
tar xzf helloai-quickstart-0.2.0.tar.gz
cd helloai-quickstart-0.2.0
cp ../helloai-offline-core-0.2.0.tar.gz .    # 必须放脚本同目录
./start.sh --offline
```

```powershell
# Windows (PowerShell)，解压后进入目录
.\start.ps1 --offline
```

启动后打开 <http://localhost:8080>，默认管理员 **admin / admin123**。

> ⚠️ **离线包必须和 `start.sh` 同目录**：`--offline` 只在这个目录里找
> `helloai-offline-*.tar.gz`。放到上一层不会被发现——脚本只 warn 一句就继续，
> 直到 `up` 阶段才失败，看起来像"跑通了其实必挂"。

> 🔒 **默认管理员密码是固定的**（`admin / admin123`），设计意图是"下来就能进"。
> 这只适合**本机试用**。若部署到别人能访问的机器：**登录后第一件事就是改密码**，
> 并且不要把 8080 直接暴露到公网。

> 包内 `RELEASE` 文件记录版本、镜像 tag 与构建时间。与本仓库根目录
> `docker-compose.server.yml` 的区别：那份从源码构建（服务器上有代码树），
> 这份只用预构建镜像，目标机器不构建任何东西。
>
> **不要**在源码目录里直接 `./start.sh` 当作用户路径——那需要你先自己 `docker build`
> 出镜像并改 `.env`，属于维护者自测（见 §6「维护者」）。

---

## 1. 前置条件

| 项 | 要求 |
|---|---|
| Docker | Docker Desktop（Windows/macOS）或 Docker Engine 20.10+ |
| Docker Compose | v2（`docker compose`，不是 `docker-compose`） |
| 内存 | 核心模式 ≥ 3.5 GB 给 Docker；沙箱模式 ≥ 5.5 GB |
| 磁盘 | 核心模式约 3 GB；沙箱模式再加约 3 GB |
| 平台 | 见文末「支持矩阵」 |

脚本会自己检查这些，不满足时**先报错、再动手**，不会让你等一次几 GB 的拉取之后才失败。

---

## 2. 两种模式

### 核心模式（默认）

```bash
./start.sh
```

启动 PostgreSQL、Redis、RabbitMQ、MinIO、后端、前端。可以完整体验：

- 登录与管理端
- 模型配置（在「系统设置 → 模型配置」里填 LLM API Key）
- 需求澄清 → 任务拆解 → Agent 调度 → 执行时间线 → Reviewer → 报告与交付物

**沙箱能力默认关闭**，`internal_run_in_sandbox` 工具会从模型可见列表中摘除（fail-closed）。
这样不会出现「功能看着在、一调用就超时」的假可用状态。

### 沙箱模式

```bash
./start.sh --with-sandbox
```

在核心模式基础上增加 OpenSandbox（画图、技能执行等需要真实运行时的能力）。
首次会额外拉取约 2–3 GB：`opensandbox/server`、`execd`、`egress`、`python:3.12`、`node:20-bookworm-slim`。

> ⚠️ **沙箱服务会挂载 `/var/run/docker.sock`**——它必须在宿主机上创建容器。
> 容器内的 root 等价于宿主 root。不认识这个风险就不要开沙箱模式。
> 8090 端口只绑定回环地址，不对外暴露；本机已有服务占用 8090 时，在 `.env` 里改
> `HELLOAI_SANDBOX_PORT=8091`（只换宿主端口，容器内仍是 8090）。
>
> ⚠️ **改端口只能解决「与不带沙箱的栈共存」**：两个 `opensandbox-server` 同时运行会
> 争夺 40000-60000 的沙箱端口段（它们共用同一个 docker.sock），必须只跑一个。

---

## 3. 目录与端口

```text
deploy/quickstart/
├── docker-compose.yml        # 只有 image:，没有 build:
├── .env.example              # 模板；首次运行时复制为 .env 并自动填密钥
├── start.sh / start.ps1      # 启动（幂等，可重复执行）
├── stop.sh  / stop.ps1       # 停止（默认保留数据；--purge 才删）
├── pull-sandbox-images.sh    # 沙箱镜像校验/拉取（被 start 脚本调用）
├── build-release.sh          # 维护者用：打发布包（用户不需要）
└── README.md                 # 本文件
```

**只有前端端口对宿主机开放**（默认 8080，可用 `HELLOAI_HTTP_PORT` 改）。
中间件（PostgreSQL / Redis / RabbitMQ / MinIO）**一个端口都不发布**：

- 本机 5432 / 6379 经常被占用，发布会直接导致启动失败；
- 发布到 `0.0.0.0` 是不必要的暴露面。

需要连数据库调试时用 `docker compose exec`，不要改 compose 加端口。

```bash
docker compose -f docker-compose.yml exec postgres psql -U postgres -d helloai
docker compose -f docker-compose.yml exec rabbitmq rabbitmqctl list_vhosts
docker compose -f docker-compose.yml exec minio sh -c 'ls /data'
```

---

## 4. 数据与密钥（重要）

| 内容 | 位置 | 说明 |
|---|---|---|
| 业务数据 | Docker 命名卷 `helloai-quickstart_*` | 任务、执行记录、附件元数据 |
| 附件对象 | MinIO 卷 `minio_data` | 桶 `helloai-artifacts` 自动创建 |
| 全部密钥 | `deploy/quickstart/.env`（权限 600） | **必须备份** |

`.env` 在首次运行时自动生成 6 个随机值：PostgreSQL 口令、RabbitMQ 业务口令、
AES 加密密钥、Agent 注册令牌、MinIO 账号、沙箱 API Key。

> 🔴 **`HELLOAI_CREDENTIAL_AES_KEY_BASE64` 一旦生成就不能换。**
> 它加密的是 `credential_vault` 里所有 LLM API Key。换 key（或删掉 `.env`）之后，
> 即使数据库完整恢复，历史密文也**再也解不开**，所有模型凭证需要重新录入。
> 脚本只在值为空时生成，重复运行不会覆盖——但 `stop.sh --purge` 会连 `.env` 一起删。

---

## 5. 常用操作

```bash
./start.sh                 # 启动 / 重启（幂等）
./start.sh --no-pull       # 用本机已有镜像（例如自己 build 的 helloai-app:local）
./start.sh --offline       # 从 ./helloai-offline-*.tar(.gz) 导入镜像，不联网
./start.sh --with-sandbox  # 核心 + 沙箱（会先校验 execd/egress/python/node 四个镜像）
./stop.sh                  # 停止，保留数据
./stop.sh --purge          # 停止并删除卷 + .env（不可逆，需输入 yes）
```

```powershell
.\start.ps1
.\stop.ps1
.\stop.ps1 --purge
```

查看状态与日志：

```bash
docker compose -f docker-compose.yml ps
docker compose -f docker-compose.yml logs -f app
docker compose -f docker-compose.yml logs rabbitmq | tail
```

---

## 6. 离线安装

适用于内网或镜像仓库不可达的环境。离线包由维护者用 `build-release.sh` 产出，
用户侧只需要下载 + 解压 + `--offline`。

```text
helloai-quickstart-0.2.0/                 # 脚本包解开后的目录
├── start.sh / docker-compose.yml / ...
├── helloai-offline-core-0.2.0.tar.gz     # 必选：app + web + 4 个中间件
└── helloai-offline-sandbox-0.2.0.tar.gz  # 可选：只有 --with-sandbox 才需要
```

> 两个 `helloai-offline-*.tar.gz` **必须放在脚本同一目录**——`--offline` 是在
> `start.sh` 所在目录里找它们的。放到上一层目录不会被发现。

```bash
cd helloai-quickstart-0.2.0
./start.sh --offline                    # 核心模式
./start.sh --offline --with-sandbox     # 需要沙箱时
```

- `--offline` 会自动 `docker load` 目录下所有 `helloai-offline-*.tar` / `*.tar.gz`，
  不需要手动执行十几次 `docker load`（`docker load` 原生支持 gzip，无需先解压）；
- **核心模式会跳过 `*sandbox*` 包**，不会强迫你为没开的功能多等几个 GB；
- 加载后仍会跑完整前置检查（内存/端口/架构）与健康等待，行为与在线模式一致。

### 维护者：产出发布包

```bash
# 在发版机执行（有 Docker + bash；Linux / WSL / Git Bash 均可）
cd deploy/quickstart
./build-release.sh --version 0.2.0
```

产物在 `dist/0.2.0/`：脚本包、`helloai-offline-core-<ver>.tar.gz`、
`helloai-offline-sandbox-<ver>.tar.gz`、`IMAGES-<ver>.txt`（镜像 ID 与 digest）、
`SHA256SUMS-<ver>.txt`。把这 5 个文件上传到网盘（或 Gitee Release 附件）即可分发。

可选——如果你确实有镜像仓库，加 `--push` 会额外推送（不推也不影响离线包可用）：

```bash
./build-release.sh --version 0.2.0 --push registry.cn-hangzhou.aliyuncs.com/<namespace>
```

> **为什么默认不建镜像仓库。** 仓库托管在 Gitee，没有现成的 ghcr.io / ACR 地址；
> 而镜像仓库的长期成本（存储、带宽、认证、多架构构建、镜像 GC）对一个个人项目
> 换不来离线 tar 包已经给出的东西——可复现、可校验、零基础设施。
>
> **中间件也必须一起打包**，否则 `--offline` 仍然要访问 Docker Hub。
> `build-release.sh` 已经把 4 个中间件放进 core 包。
>
> `minio/minio:latest` 是浮动 tag（全仓 5 份 compose 都这么用，非本目录引入），
> 所以 `IMAGES-<ver>.txt` 额外记录它的 repo digest——那才是让一个离线包可复现的依据。

---

## 7. 升级

```bash
./stop.sh
# 修改 .env 里的 HELLOAI_APP_IMAGE / HELLOAI_WEB_IMAGE 版本号
./start.sh
```

- 数据卷保留，Flyway 在应用启动时自动执行迁移；
- `app` 与 `web` 必须使用**同一个版本号**（同一个发布包内天然一致）；
- 大版本升级前建议先备份：`docker compose -f docker-compose.yml exec postgres pg_dump -U postgres helloai > backup.sql`
  （后端镜像内自带 `pg_dump` 16，与运行时主版本一致）。

---

## 8. 常见问题

| 现象 | 原因与处理 |
|---|---|
| `port 8080 is already in use` | 在 `.env` 里改 `HELLOAI_HTTP_PORT=8081` 后重跑 |
| `cannot reach the Docker daemon` | Docker Desktop / dockerd 没启动 |
| `could not pull app/web images` | 网络问题，或镜像是本地构建的 → 加 `--no-pull` |
| 启动很慢（> 3 分钟） | 首次启动要跑 Flyway 全量迁移，属正常；日志看 `logs app` |
| `app` 反复重启 | `docker compose -f docker-compose.yml logs app | tail -50` 找 `resolve placeholder` 一类报错，通常是 `.env` 被手工改坏 |
| 技能报 `sandbox unavailable` | 核心模式本来就没开沙箱；用 `--with-sandbox` 重启 |
| 沙箱创建超时 | 宿主缺 `node:20-bookworm-slim` 或 `python:3.12`（沙箱经 docker.sock 只用宿主已有镜像）。`--with-sandbox` 现在会**提前校验**这四个镜像并直接报错，不会再拖到 35 秒后才裸超时 |
| `sandbox images are missing` | 沙箱镜像没就位。联网：去掉 `--no-pull` 让它拉；内网：先 `docker load` 离线沙箱包，或用 `SANDBOX_IMAGE_MIRROR=<镜像站>/library/` 走镜像 |
| 忘记管理员密码 | `docker compose -f docker-compose.yml exec postgres psql -U postgres -d helloai` 后按需重置 `sys_user` |

---

## 9. 支持矩阵

| 环境 | 核心模式 | 沙箱模式 |
|---|---|---|
| Linux x86_64 | 正式支持 | 正式支持 |
| Linux ARM64 | 支持 | 未验证（OpenSandbox 镜像多架构待确认） |
| Windows + Docker Desktop（Linux 容器） | 支持 | **单独验证**（docker.sock 转发与路径语义需实测） |
| macOS + Docker Desktop | 支持 | **单独验证**，Apple 芯片尤其 |

> 不承诺「全平台一键沙箱」。核心模式全平台可用；沙箱能力优先保障 Linux x86_64。

---

## 10. 这个目录与本仓库其它部署方式的对应关系

| 方式 | 目标用户 | 需要源码 | 需要工具链 | 入口 |
|---|---|---|---|---|
| **Quickstart（本文）** | 想快速体验的人 | ❌ | ❌ | `./start.sh` |
| 服务器源码部署 | 自建 / 内网 | ✅ | ❌（容器内构建） | `docker compose -f docker-compose.server.yml up -d --build` |
| 高级部署包（jar + dist） | 二次开发 / 特殊环境 | ✅ 构建后 | 构建时需要 | `deploy/app/docker-compose.yml` |
| 本地源码开发 | 改代码 | ✅ | ✅ | 根目录 `README.md` 的「方式 A」 |
