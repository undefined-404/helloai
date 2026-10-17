# HelloAI 上线核对清单

> **状态：Active**
> 最后更新：2026-10-17
> 依据：《文档体系分类与治理规则》§3.2（执行清单与预案 → `plan/`）；各条目在自己的小节里给出事实源与验证出处
>
> **执行状态（2026-10-16 更新）**：本清单条目**均属「只在部署环境可验」**，**当前尚未在服务器执行** —— 阻塞点 = 缺**部署环境 / 服务器时间窗**。**★触发条件（2026-10-16 用户裁定）**：**不设时间表** —— 只有**所有 `plan/` 计划完成、且 `review/` 中遗留的问题全部解决之后**才考虑启动本清单（原「待环境确认」的表述作废）。见 `plan/HelloAI 计划编排与排期总览.md` §5.1 项 8。

**收录口径（决定一条该不该进本清单）**：

```text
只收「本地跑不出来、只在部署环境（镜像 / 服务器 / 真数据）才有意义」的核对项。
本地可验的项归各专项的验证集（plan 的「验证集」段 / scripts/README 索引 / 单测），不进本清单 ——
否则同一件事两处维护，必然漂移。
```

**用法**：每次上线前按小节逐条过；跑完在复选框打勾并注明执行人 + 日期（不勾 = 没验过，不得当已验）。
条目一旦被**永久机制**取代（如写进 CI / 镜像自检），就从清单移除并说明去向。

---

## 1. 备份 / 恢复（`REF-2` / 差距表 `G-018`）

> 出处：`LOG-20261010-008`。本节三条**全部**是「本地通过 ≠ 部署环境通过」的项：
> 本地走 `.tools/pgsql` 本地二进制 + 本地 MinIO 容器，与服务器「镜像内客户端 + 自建桶」是两条路径。

### 1.1 镜像内 `pg_dump` / `pg_restore` 可用（`Dockerfile` stage 3）

- [ ] 构建：`docker compose -f docker-compose.server.yml build app`
- [ ] 验版本：`docker compose -f docker-compose.server.yml run --rm --entrypoint pg_dump app --version`
      （或 `docker run --rm --entrypoint pg_dump <app-image> --version`）
- [ ] 验还原端：同上把 `pg_dump` 换成 `pg_restore`

**判据**：两者都能执行且**主版本 = 服务端 PG 主版本**（当前 `postgres:16.4` ⇒ 期望 `16.x`）。
跨引擎门（`RestoreGate` 门①）按**服务端**主版本比对，客户端主版本低于服务端会产出恢复侧拒收的归档。

**背景**：`D-2026-10-10-2①` 要求「生产由 app 镜像提供客户端」，而该前置**长期未落地**（`application.yml`
注释因此失真），`LOG-20261010-008` 才补上：基础镜像钉 `eclipse-temurin:17-jre-noble`
+ `apt-get install -y --no-install-recommends postgresql-client-16`。
本地那次只是「按 stage 3 配方单独起容器」的**代理验证**，不等于完整镜像构建通过。

**不通过的表现**：备份端点**显式**报「`pg_dump` 无法启动」（不静默降级）；`pg-dump-path` 在服务器侧应保持留空。

### 1.2 服务器侧首次真备份

- [ ] 前置：1.1 通过；`helloai.storage.type=minio`；`helloai.backup.bucket`（默认 `helloai-backups`）
      **独立于** `helloai.storage.minio-bucket`（共用会被孤儿清理删掉）
- [ ] 触发：管理端 `POST /api/backup`（权限码 `backup:run`）→ 轮询 `GET /api/backup/{id}` 直到 `state=SUCCESS`
      （自动备份需显式开启：`BACKUP_AUTO_ENABLED=true`，默认关）
- [ ] 核对台账：`state=SUCCESS` / `pg_version` 为 16.x / `artifact_count` / `dump_bytes` 与库规模相称
- [ ] 核对桶内三对象：`<object_prefix>` 下有 `database.dump`、`artifacts.json`、`manifest.json`
- [ ] 记录该次 `id` 与 `object_prefix`（1.3 要用）

**判据**：`state=SUCCESS` 且三对象齐。**桶为空**时 `artifact_count=0` 属正常；
但「桶为空而库里有产物引用」必须**显式失败**（那更像存储实现不支持枚举，静默通过会产出缺对象的「完整备份」）。

**不通过的表现**：台账 `state=FAILED` + `failure_reason` 给可读原因（客户端缺失 / 桶不可用 / 超时）。

### 1.3 停机恢复（旧称「回滚」，手册路径 C）在真环境演练一次

> 流程正文：`doc/manual/platform-backup-restore/runbook.md` §3.4 / §3.5。此处只列核对点。

- [ ] 前置：1.2 有一次成功备份；选定停机窗口（无在飞子任务）
- [ ] 预检：`POST /api/backup/{id}/restore/preflight` → `restorable=true`（只读，可反复跑）
- [ ] 取制品 + 手工核对三门（跨引擎 / schema 高过运行时 / 离线）
- [ ] **新建空库**后 `pg_restore --no-owner --no-privileges -j 4`，确认 **exit=0**
- [ ] 对账：表数一致 + 关键表行数抽查一致
- [ ] 切库 → 重启应用 → 登录 + 读写正常
- [ ] 旧库保留观察期后再清理

**判据**：`pg_restore` **exit=0**（非 0 表示存在被忽略的错误，此时可能已部分改写）；
对账一致；切库后应用可用。

**红线**：目标必须是**空库**。恢复端点**不带 `--clean`**（`G-018` 冻结的落地硬约束），
对已有同名对象的库直接执行会大面积报错并以非 0 退出码结束 —— 且**不要在同库上重跑**。
**对象本体不在备份内**（只有 `key` + `size` 清单），桶没了就只剩清单。

---

## 2. 生产环境 Docker 引擎 / Compose 升级前置

> 出处：2026-10-11 用户指令（回单位升级生产 Docker 引擎 / Compose）；`G-017` 的「部署侧安全动作」同窗口核对（§2.6）。
> 脚本：`deploy/docker-host-preflight.sh` —— **只读**探测（除 `--record` 外不写任何东西），在**服务器宿主机**执行。
> 本节全部条目满足收录口径：只在真服务器（真 Docker / 真安全组 / 真数据盘）才有意义。

### 2.0 先决认知：为什么必须是 Compose v2

`docker-compose.server.yml` 用了 `depends_on: condition: service_completed_successfully`，且**无顶层 `version:` 键** —— 它面向 **Compose Spec**，只被 `docker compose`（v2 插件）支持。EOL 的 Compose v1（Python 版 `docker-compose`）**不支持该条件**，用它根本起不来栈。

**判据**：`docker compose version` 有输出（v2 插件在）。`docker-compose --version` **不能**作为判据 —— Docker Desktop / 新版打包把它做成指向 v2 二进制的别名，其 banner 是 `Docker Compose version vX`（只有 `docker-compose version 1.x` 才是真 v1）。

### 2.1 升级前：环境探测（一次性，只读）

- [ ] 在**宿主机**（不是容器内）执行：`bash deploy/docker-host-preflight.sh --record /root/helloai-docker-upgrade-record-$(date +%F).txt`
- [ ] 记下三样：引擎 `ServerVersion`、`docker compose version`、回退记录文件路径
- [ ] 确认 `docker system df` 与「docker root 剩余空间」

**判据**：脚本 `VERDICT` 段为 `FAIL blockers: 0`。有 blocker 就先清（脚本会点明是哪一条），**不要带着 blocker 动 Docker 包**。

**脚本会给出的 WARN（都需人工判断，不是失败）**：

| WARN | 含义 | 处置 |
|---|---|---|
| `live-restore is DISABLED and N container(s) are running` | 升级 dockerd 会**停掉全部容器** | 见 §2.2 |
| `less than 5 GiB free on the docker root` | 升级拉包 + 构建缓存可能撑爆盘 | 先 `docker system prune`（谨慎）或扩盘 |
| `floating ':latest' tag in the stack` | 栈内有浮动 tag | 升级后固定（`image@sha256`） |
| `docker root is on X but /data exists` | 数据卷落在系统盘而非数据盘 | 确认是否有意；容量与盘位风险 |

> `--record` 产物是**回退依据**：引擎/Compose 包版本 + 每个栈镜像的 digest。请把它留在服务器上（**不要**提交进仓库）。

### 2.2 关键前置抉择：把停机窗口压到最小（`live-restore`）

直接升级 Docker 引擎 = `systemctl restart docker`，会停掉**所有**运行中容器（`restart: unless-stopped` 只会让它们随后自动起来，但中断是真实的，且要叠加应用启动时间）。

- [ ] **可接受中断**（推荐首次升级走这条，最省事）⇒ 跳过本节，直接 §2.3。
- [ ] **要求最小中断** ⇒ 升级**前**在 `/etc/docker/daemon.json` 置 `"live-restore": true`，然后 `systemctl reload docker`（reload **不会**重启容器），确认生效后再升级引擎 —— 升级期间容器**不中断**。

**判据**：`docker info --format '{{.LiveRestoreEnabled}}'` 返回 `true`，且 reload 后 `docker ps` 的 `Up` 时长**未重置**。

**红线**：`live-restore` 与 Swarm 模式不兼容（本项目不用 Swarm，不影响）；它只保证「容器不被打断」，**不降低升级本身的风险**，也不豁免 §2.3。

### 2.3 升级前：数据与回退准备

- [ ] **云控制台做实例/磁盘快照** —— 最强回退，优先于任何脚本
- [ ] 记下当前引擎 + Compose 包版本（回退要按版本装回去）；§2.1 的 `--record` 已含
- [ ] 库已在生产用 ⇒ 应用侧备份一次：管理端 `POST /api/backup` → 轮询至 `state=SUCCESS`（判据见 §1.2）
- [ ] 停止写入：`cd <部署目录> && docker compose -f docker-compose.server.yml stop`
- [ ] 确认无容器在跑：`docker compose -f docker-compose.server.yml ps`（应无 `Up`）

**判据**：快照 ID 已记录 + 回退版本已记录 + 备份 `state=SUCCESS` + `ps` 无 `Up`。

### 2.4 执行升级（按发行版）

```bash
# Debian / Ubuntu
apt-get update
apt-get install -y --only-upgrade docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# RHEL / CentOS / Alibaba Cloud Linux
dnf -y upgrade docker-ce docker-ce-cli containerd.io docker-compose-plugin

# 两者都要复核
docker --version && docker compose version && docker info >/dev/null && echo ENGINE_OK
```

**判据**：两条版本命令都有输出且为预期版本；`containerd.io` **一并升级**（引擎与 containerd 版本需配套，只升 `docker-ce` 会留下不匹配）。

**注意（国内网络）**：`download.docker.com` 常极慢或超时 ⇒ 改用阿里云镜像源；仓库文件位置由 §2.1 探测（`/etc/yum.repos.d/*docker*` 或 `/etc/apt/sources.list.d/docker.list`）。

**红线**：**不要** `apt-get remove docker*`、**不要** 删 `/var/lib/docker`。引擎升级**不动数据卷**，容器/镜像/卷全部保留。

### 2.5 升级后：拉起与自检

- [ ] `systemctl status docker` = active，`docker info` 正常
- [ ] `docker compose -f docker-compose.server.yml up -d` —— **不带 `--build`**（本次只升引擎，镜像未变；带 `--build` 会把「升级」与「重建镜像」两件事混在一起，出问题难归因）
- [ ] `docker compose -f docker-compose.server.yml ps` 全部 `Up` / `exited(0)`
- [ ] `logs rabbitmq-init` 显示 DONE
- [ ] `logs app` 出现 `Started HelloAIApplication`，且无 `ACCESS_REFUSED` / `ERROR`
- [ ] 冒烟：管理端登录 → 附件直读 → 任务流转

**判据**：`ps` 无 `Restarting`；应用日志无 ERROR；管理端可登录。

### 2.6 同窗口安全动作（`G-017`，与升级无依赖）

现状：MinIO 凭据在 `docker-compose.server.yml` 内**硬编码** `minioadmin/minioadmin123`；`15432/26379/25672/25673/29000/29001` 对公网开放。

- [ ] 云安全组把上列端口收为**仅本机/办公 IP 白名单**（公网只留 `80`）
- [ ] 轮换 MinIO 凭据 —— **注意三处必须同步**：`minio` 服务的 `MINIO_ROOT_USER/PASSWORD`、`app` 服务的 `MINIO_ACCESS_KEY/SECRET_KEY`、以及 `.env`（若已抽出）。改完需**重建/重启**两个服务，并**复验** `helloai-artifacts` 桶内历史对象的直读链路。

**判据**：非白名单 IP `telnet <ip> 15432` 不通；旧 MinIO 凭据登录失败、新凭据可用。

### 2.7 回退（升级后异常）

- [ ] **首选**：云控制台用 §2.3 的快照回滚
- [ ] 或用 §2.1 记录把旧版引擎/Compose 包装回去，`systemctl restart docker`
- [ ] 数据卷 / 镜像均未被升级触碰 ⇒ 回退只涉及引擎二进制，**业务数据不受影响**

**判据**：回退后 `docker --version` 与 `--record` 记录一致；`docker compose ps` 恢复全部 `Up`。

---

## 3. 启动配置注入（部署前置）

> 出处：`doc/review/HelloAI 优先级决策分析（V2架构调整 vs 代码质量）.md` **§13.4** item 1 / item 2（2026-10-01 暴露；其中 RabbitMQ 密码于同日**部分清偿**，registration-token 仍未处置）。复核与闭环登记见 `LOG-20261017-004`。
>
> 本节全部条目满足收录口径：`HELLOAI_AGENT_REGISTRATION_TOKEN` 的注入点在生产 `compose` 的 `app` 服务（本机不跑该栈）；「随包生效」只能在服务器重打包后验证。

**现状（2026-10-17 实测）**：生产 app 走 `dev` profile。

| 配置 | 文件 | 现状 |
|---|---|---|
| `spring.rabbitmq.{username,password,virtual-host}` | `application-dev.yml`（`git ls-files -v` = **`S`**，`skip-worktree` 本机专属） | ✅ 2026-10-01 已补齐（可被 `RABBITMQ_PASSWORD` 覆盖）；**服务器需重打包 jar 才生效** |
| `helloai.agent.registration-token` | 同上 | ⬜ **仍缺占位符**（base 已是无默认占位符） |
| `HELLOAI_AGENT_REGISTRATION_TOKEN` | `docker-compose.server.yml`（`git ls-files -v` = **`H`**，已在库）的 `app` 服务 | ⬜ **未注入** |
| `MINIO_ACCESS_KEY / SECRET_KEY` | `docker-compose.server.yml` 内**硬编码** `minioadmin/minioadmin123` | ⬜ 建议改由 `deploy/app/.env` 注入 —— **与 §2.6 同一条，见该节**（`deploy/app/` 当前只有 `.env.example`） |

- [ ] 服务器侧重打包 jar（使 `application-dev.yml` 的 RabbitMQ 改动随包生效）—— 判据：app 启动后 RabbitMQ 连接成功，无 `ACCESS_REFUSED`
- [ ] 在 `docker-compose.server.yml` 的 `app` 服务补 `HELLOAI_AGENT_REGISTRATION_TOKEN`（**纯 ASCII** —— 该文件是功能性字符串，非注释）
- [ ] `application-dev.yml` 补 `${HELLOAI_AGENT_REGISTRATION_TOKEN:…}` 占位符（与既有「本地私有默认值」口径一致；该文件 `skip-worktree`，改索引需按 `§13.4` item 2 的手法，勿泄露真实 IP）
- [ ] MinIO 凭据轮换与三处同步 —— **按 §2.6 执行**（不在本节重复）

**判据**：app 容器 `logs app` 出现 `Started HelloAIApplication`，且无 `registration-token` 缺失 / `ACCESS_REFUSED` 类错误；Agent 注册链路可用。

---

## 4. 后续小节（按需追加）

新的专项若产出「只在部署环境可验」的核对项，追加为独立小节，并在小节首行给出出处
（`LOG-…` / 差距表 `G-0xx`），与本节同构。**不要把本地可验项搬进来。**

---

## 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-10 | 建立本清单；首节「备份 / 恢复（`REF-2`）」三条来自 `LOG-20261010-008` —— 该轮 REF-2 回归与门禁均在**本地**完成，三条部署侧核对项此前无任何载体。 |
| 2026-10-11 | 新增 §2「生产环境 Docker 引擎 / Compose 升级前置」（§2.0~§2.7）：来源 = 2026-10-11 用户指令（回单位升级生产 Docker）+ `G-017` 部署侧安全动作同窗口核对；配套只读探测脚本 `deploy/docker-host-preflight.sh`（`--record` 落回退记录）。原「后续小节」占位顺延为 §3。见 `LOG-20261011-011`。 |
| 2026-10-17 | 新增 §3「启动配置注入（部署前置）」：来源 = `review/HelloAI 优先级决策分析（V2架构调整 vs 代码质量）.md` §13.4 item 1 / item 2（2026-10-01 暴露的部署遗留）。原「后续小节」占位顺延为 §4。**同批判定 §13.4 item 3（本机 `scripts/powershell/logs/` 含真实凭据）不列入本清单** —— 它是**本机动作**，不符收录口径；改在 `LOG-20261017-004` 登记为待停机处置项。见 `LOG-20261017-004`。 |
