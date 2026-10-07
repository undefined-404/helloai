# HelloAI 中间件独立部署（摆法 A）

> ## ⚠️ 已废弃（ARCHIVED，2026-10-07）
>
> 本目录属于「**独立中间件服务器（摆法 A）**」方案 —— 一台 4C4G 服务器只跑中间件，
> 应用留在原处通过网络连接。**该方案已放弃**：现网改为**单机部署**，中间件 + app + web
> 同栈，统一由仓库根的 `docker-compose.server.yml` 承担。
>
> - 本目录的 `docker-compose.yml` / `scripts/` / `minio/` **仅作历史留档**，不再维护、不再使用。
> - 其中 §10 FAQ 与 §11 修复记录**仍有参考价值**（故障判据与踩坑记录对单机部署同样适用）。
> - **RabbitMQ 初始化脚本已迁出本目录**：`deploy/middleware/rabbitmq/init.sh`
>   → **`deploy/init-rabbitmq.sh`**（单机部署与之一并脱钩）。下文出现旧路径处均以新路径为准。

新服务器（4C4G）只部署中间件：PostgreSQL（HelloAI）+ MySQL（其他项目）+ Redis + RabbitMQ + MinIO。
HelloAI 应用与另一项目应用留在原处，通过网络连接新服务器（安全组按 IP 白名单放行）。

## 1. 部署包结构

```text
deploy/init-rabbitmq.sh     # RabbitMQ vhost/用户/权限初始化（幂等）—— 单机部署亦复用
                            # （原 deploy/middleware/rabbitmq/init.sh，2026-10-07 迁出本目录）

deploy/middleware/          # ⚠️ 以下均为已废弃方案的历史留档
├── docker-compose.yml      # 中间件 compose（含一次性初始化容器）
├── .env.example            # 配置模板（复制为 .env 后修改密码）
├── rabbitmq/rabbitmq.conf  # RabbitMQ 配置（init.sh 已迁出至 deploy/init-rabbitmq.sh）
├── minio/
│   ├── init.sh             # MinIO bucket/access key/policy 初始化（幂等）
│   └── policies/           # bucket 专属 policy（Resource 限定单桶）
└── scripts/
    ├── migrate.sh          # 数据迁移脚本（pg/mysql/minio）
    └── diagnose-redis.sh   # Redis 容器异常一键取证（只读，见 §10）
```

## 2. 新服务器前提

1. Docker Engine + Docker Compose v2
2. 数据盘挂载到 `/data`（`docker-compose.yml` 中所有卷都指向 `/data/*`；若路径不同请同步修改）
3. 安全组放行（建议只对应用服务器 IP 白名单）：`15432`(PG) / `13306`(MySQL) / `26379`(Redis) / `25672`+`25673`(RabbitMQ) / `29000`+`29001`(MinIO)

## 3. 快速开始

```bash
cd /home/admin/middleware
cp .env.example .env
vim .env                 # 修改所有 ChangeMe 密码（字母数字，勿含空格/引号）
docker compose up -d
docker compose ps        # 等待全部 healthy（init 容器显示 Exited 0 属正常）
docker compose logs rabbitmq-init minio-init   # 确认初始化成功
```

Redis / RabbitMQ / MinIO 的隔离配置由 init 容器自动完成，幂等可重跑。

## 4. 内存预算（4C4G）

| 服务 | mem_limit | 典型占用 |
|---|---|---|
| PostgreSQL 16.4 | 768m | ~400MB |
| MySQL 8.0 | 768m | ~450MB |
| Redis 7.2.5 | 384m | ~150MB（maxmemory 256MB） |
| RabbitMQ 3.12 | 512m | ~300MB |
| MinIO | 512m | ~250MB |
| **合计** | **2.9GB** | **~1.6~2.0GB** |

留出 ~2GB 给系统与页缓存。若日后要把应用也塞上来，需升 8G 或加 swap 并压 JVM（-Xmx 1g）。

## 5. 连接信息

| 中间件 | 地址 | 端口 | 账号 |
|---|---|---|---|
| PostgreSQL | 新IP:15432 | db=helloai | postgres / .env |
| MySQL | 新IP:13306 | db=other_project | root / .env |
| Redis | 新IP:26379 | 默认用户密码 / helloai 用户密码 | ACL |
| RabbitMQ | 新IP:25672 | vhost=/helloai | helloai / .env |
| MinIO | 新IP:29000 | bucket=helloai-artifacts | helloai-s3 / .env |

## 6. 数据迁移

在旧服务器（或装有 pg/mysql 客户端 + mc 的机器）上：

```bash
OLD_HOST=旧IP NEW_HOST=新IP \
PG_PASSWORD=旧库密码 NEW_PG_PASSWORD=新库密码 \
OLD_MYSQL_PASSWORD=.. NEW_MYSQL_PASSWORD=.. \
OLD_MINIO_ENDPOINT=http://旧IP:29000 OLD_MINIO_ACCESS=minioadmin OLD_MINIO_SECRET=.. \
NEW_MINIO_ENDPOINT=http://新IP:29000 NEW_MINIO_ACCESS=helloai-s3 NEW_MINIO_SECRET=.. \
./scripts/migrate.sh all
```

迁移完成后，切流量前先做应用验证（登录 / 附件直读 / 任务流转），再停旧中间件。

## 7. HelloAI 应用连接点改造

### 方式一（推荐）：修改 `docker-compose.server.yml` 的 app 环境变量

| 环境变量 | 原值 | 改后 |
|---|---|---|
| SPRING_DATASOURCE_URL | `jdbc:postgresql://postgres:5432/helloai?currentSchema=public&reWriteBatchedInserts=true` | `jdbc:postgresql://<新IP>:15432/helloai?currentSchema=public&reWriteBatchedInserts=true` |
| SPRING_DATA_REDIS_HOST | `redis` | `<新IP>` |
| SPRING_DATA_REDIS_PORT | `6379` | `26379` |
| SPRING_DATA_REDIS_USERNAME | （无） | `helloai`（新增） |
| SPRING_DATA_REDIS_PASSWORD | （无） | `.env` 的 HELLOAI_REDIS_PASSWORD（新增） |
| SPRING_RABBITMQ_HOST | `rabbitmq` | `<新IP>` |
| SPRING_RABBITMQ_PORT | `5672` | `25672` |
| SPRING_RABBITMQ_USERNAME | （无，默认 guest） | `helloai`（新增） |
| SPRING_RABBITMQ_PASSWORD | （无） | `.env` 的 HELLOAI_RABBIT_PASSWORD（新增） |
| MINIO_ENDPOINT | `http://minio:9000` | `http://<新IP>:29000` |
| MINIO_ACCESS_KEY | `minioadmin` | `helloai-s3` |
| MINIO_SECRET_KEY | `minioadmin123` | `.env` 的 HELLOAI_S3_SECRET_KEY |
| MINIO_BUCKET | `helloai-artifacts` | 不变 |

> Redis 相关（Sa-Token 会话 / RedisTemplate / 锁）统一走 `spring.data.redis`，无需逐处修改。
> 若实际代码里有独立构建的 RedissonClient / Jedis 配置，需同步补 username/password。

### 方式二：直接改 `helloai-start/src/main/resources/application-dev.yml`

`DATASOURCE_URL` / `REDIS_HOST` / `REDIS_PORT` / `RABBITMQ_HOST` / `RABBITMQ_PORT` 已有 env 占位；
`rabbitmq` 节点的 `username` / `password` / `virtual-host` 占位符已补齐（2026-10-01：默认
`helloai` + `/helloai`，密码默认值见文件内注释，生产仍可走 SPRING_* env 覆盖）；`redis` 节点若要启用
ACL 用户（对应上表 SPRING_DATA_REDIS_USERNAME/PASSWORD），仍需同步补 `username` / `password` 字段
（或同样走 SPRING_* env 覆盖）。

## 8. 其他项目连接与共用隔离

| 中间件 | 隔离机制 | 其他项目连接方式 |
|---|---|---|
| RabbitMQ | vhost 级完全隔离（`/helloai` 与 `/other` 互不可见） | 管理员在管理 UI（`新IP:25673`）新建 vhost+用户+权限，或仿 `deploy/init-rabbitmq.sh` 追加 |
| Redis | ACL 用户 + 密码隔离（默认 `~*`，凭据防串用） | 新用户 `user other on >密码 ~* &* +@all ...`；应用层 key 前缀约定 `other:` |
| MinIO | bucket + 独立 access key + bucket policy | 用 `mc mb` 建 bucket、`mc admin user add` 建用户、attach `other-project.json` policy（`minio/init.sh` 已留模板） |
| PostgreSQL / MySQL | 各自独立实例、独立端口、独立数据目录 | 两库完全物理隔离 |

**Redis 严格 key 隔离（可选）**：当前 ACL 为 `~*`（避免 HelloAI 现存 key 不带 `helloai:` 前缀导致 NOPERM）。
如需数据级隔离，把 compose 中 helloai 用户的 `~*` 改为 `~helloai:*`，并确保 HelloAI 所有 Redis key 统一 `helloai:` 前缀后再上。

> ⚠️ **ACL 写法约束（踩坑，勿"优化"）**：`compose` 中 `--user` 的每个 ACL token 都必须是**独立的列表元素**。
> Redis 启动时对每个非 `--` 开头的 argv 元素单独转义成一个配置 token（`src/server.c`：
> `options = sdscatrepr(options, argv[j], strlen(argv[j]))`），因此把整条 ACL 规则写成一个字符串
> （如 `- "default on >pw ~* &* +@all …"`）会让 `user` 指令只收到 **1 个参数**——规则全部丢失，
> `default` 用户拿不到密码。配合已发布的 `26379` 端口，等同于**无认证裸奔**。
> 官方测试用例同样印证该语义：`redis-server --port 6379 6380` 会把 `6379`、`6380` 都当作 `port` 的值。

## 9. 安全清单

> 🔴 **待处置（2026-09-29 生产实测确认）**：`deploy/middleware/.env.example` **已被 git 跟踪**，而本仓库
> **公网匿名可读**（`https://gitee.com/undefined_404/helloai/raw/master/deploy/middleware/.env.example`
> 返回 `HTTP 200`）。经与服务器 `/home/admin/middleware/.env` 逐键比对，**`.env` 中 13 个变量全部等于
> `.env.example` 的值**（即 `cp .env.example .env` 后**从未修改过密码**）。叠加 §2 第 3 条尚未限制
> 安全组、服务器 `ufw` 为 `inactive`、8 个端口经公网实测**全部可直接连通**——等同于把已公开的凭据
> 摆在了公网上。**必须轮换全部中间件密码**（注意 PG/MySQL/RabbitMQ/MinIO 的密码只在 initialize 时
> 生效，轮换需改容器内账号，不能只改 `.env`），并把 `.env.example` 的值改为占位符。

- [ ] **【最高优先】** 轮换全部中间件凭据；`.env.example` 只保留占位符（`change-me` 之类）
- [ ] 所有 `.env` 密码改为强密码，勿提交 git（`.env` 已被仓库 `.gitignore:44` 排除）
- [ ] 安全组仅对应用服务器 IP 白名单放行上述端口，不要 `0.0.0.0/0`
- [ ] RabbitMQ 默认 `guest` 已被管理员用户替代，管理 UI（25673）同样走白名单
- [ ] 数据盘独立挂载，容器数据不落系统盘
- [ ] 迁移前先 `docker compose exec postgres-helloai pg_dump ...` 做一次新库备份基线

## 10. FAQ

- **Redis 报 `NOPERM`**：ACL 限制了 key 前缀但应用 key 不带前缀。当前配置为 `~*` 不会触发；只有改成 `~helloai:*` 才会，需先统一 key 前缀。
- **RabbitMQ `ACCESS_REFUSED`**：检查 vhost 与用户名密码——应用连 `helloai` 用户 + `/helloai` vhost（Spring 默认 vhost=`/`，需设 `spring.rabbitmq.virtual-host=/helloai`）。
- **RabbitMQ `ACCESS_REFUSED - Login was refused using authentication mechanism PLAIN`（2026-10-07 生产复现，完整定位法）**：
  > 这是**部署侧状态问题，不是应用代码问题**。业务代码改动与 MQ 无关，不要为此回滚代码或凭直觉改密码。
  >
  > **先理解机制（否则会改错地方）**：`guest` 受 RabbitMQ 官方限制**仅允许回环登录**（AMQP 与 Management API 都受限）。
  > 本机开发时应用跑在**宿主机**、连 `localhost:25672` ⇒ 走回环 ⇒ `guest/guest@/` 可用；
  > 而 `docker-compose.server.yml` 里应用是**兄弟容器**（日志可见 `AMQP Connection 172.18.0.x:5672`，源 IP 是 compose 网段）
  > ⇒ **永远用不了 `guest`**，必须由 `rabbitmq-init` 在 broker 上创建 `helloai` 用户 + `/helloai` vhost。
  > `rabbitmq-init` 没跑成功 ⇒ 应用的 `helloai@/helloai` 认证必然被拒。
  >
  > **定位（在服务器部署目录执行，只读取证）**：
  >
  > ```bash
  > docker exec helloai-rabbitmq rabbitmqctl list_vhosts        # 期望看到 /helloai
  > docker exec helloai-rabbitmq rabbitmqctl list_users         # 期望看到 helloai
  > docker exec helloai-rabbitmq rabbitmqctl list_permissions   # 期望 helloai -> /helloai
  > grep -n "rabbitmq-init" docker-compose.yml                  # 无输出 = compose 是旧版（缺 init 服务）⇒ 根因
  > ls -l deploy/init-rabbitmq.sh                              # 必须存在（缺它 init 会 fail-open 假成功）
  > grep -c "HELLOAI_RABBIT_PASSWORD\|RABBITMQ_ADMIN_USER\|RABBITMQ_ADMIN_PASSWORD" .env   # 应为 3
  > docker compose logs rabbitmq-init                            # 看 init 是否非 0 退出、报什么码
  > ```
  >
  > **修复**：
  > 1. compose / `init.sh` / `.env` 任一缺失 ⇒ 从仓库同步（`docker-compose.server.yml` → 服务器 `docker-compose.yml`，
  >    并保持 `deploy/middleware/rabbitmq/` 相对路径），再 `docker compose up -d`。
  > 2. `rabbitmq-init` 非 0 退出且报 `HTTP 401` ⇒ `RABBITMQ_ADMIN_USER/PASSWORD` 在该 broker 上不存在或非 administrator。
  >    **注意 `RABBITMQ_DEFAULT_USER/PASS` 只在节点【首次启动】生效**，已有 volume 的 broker 改它无效，须一次性手工补：
  >    `docker exec helloai-rabbitmq rabbitmqctl add_user <管理员> <密码>` +
  >    `docker exec helloai-rabbitmq rabbitmqctl set_user_tags <管理员> administrator`，然后重跑 `docker compose up -d`。
  > 3. 密码分叉（app 与 init 用了不同值）⇒ `init.sh` 三步是**无条件 PUT（幂等）**，重跑会把 `helloai` 的密码
  >    **重置为 `.env` 的 `HELLOAI_RABBIT_PASSWORD`**，一次重跑即可对齐。
  >
  > ⚠️ **修完 broker 必须重启应用**：认证失败抛的是 `FatalListenerStartupException`
  > （日志 `Consumer received fatal exception on startup`），Spring AMQP 对**致命启动异常不自动重试**
  > （本项目未配自定义 `FatalExceptionStrategy`，也未开 `listener.simple.retry`，已核）⇒ 监听器容器就此停住，
  > 只修 broker 不重启应用，日志会持续报错，容易误判「修复无效」。
  > 执行 `docker compose restart app`（或 `docker compose up -d --force-recreate app`）。
- **init 容器非 0 退出**：`docker compose logs rabbitmq-init / minio-init` 查看原因；修复后重新 `docker compose up -d`（幂等）。
- **迁移后附件直读失败**：确认 `MINIO_ENDPOINT` 应用可达（公网需白名单），`MINIO_ACCESS_KEY/SECRET_KEY` 与 `.env` 一致。
- **Redis 容器 `Restarting` 崩溃循环** —— **已确认根因（2026-09-29，生产实测）**：§8 那条「ACL 写法约束」
  就是真凶。服务器日志逐秒循环输出
  `# Spaces not allowed in ACL usernames` → `# Critical error while loading ACLs. Exiting.`，
  容器 `ExitCode=1`、`RestartCount=11359`。`docker inspect` 可见 `Config.Cmd` 里那条 ACL 是**一个整串元素**。
  修复：把每个 ACL token 拆成独立列表元素（本仓库 `docker-compose.yml` 已修好）后
  `docker compose up -d --force-recreate redis` → 容器 `Up (healthy)`，ACL 校验通过。
  根因不一致时，仍可先跑一键取证 `bash scripts/diagnose-redis.sh`（只读，输出日志 + 退出码 + `.env` 体检 + 磁盘/权限），
  再按输出末尾的 A~E 分支定位：ACL 参数写法 / AOF 与实例不兼容 / 磁盘或权限 / 其它 directive 报错。
- **`vm.overcommit_memory` 警告**：Redis 启动日志常报需设为 `1`。已在服务器持久化
  （`/etc/sysctl.d/99-redis.conf` → `vm.overcommit_memory = 1`），新机部署照此补一条即可。
- **`.env` 变动后不生效**：`.env` 只在**容器重建**时注入，改完必须 `docker compose up -d --force-recreate <service>`；
  仅 `restart` 无效。`.env` 须为 UTF-8 **无 BOM**、**LF**（用 Windows 编辑后上传极易带入 CRLF，污染变量值）。

## 11. 修复记录

### 2026-09-29 · Redis 崩溃循环修复（服务器 `49.232.210.194`）

| 项 | 内容 |
|---|---|
| 现象 | `middleware-redis` 处于 `Restarting (1)`，**自创建起 8 天从未成功启动**（`RestartCount=11359`） |
| 根因 | ACL 规则整串传入 `--user`，`Config.Cmd` 里是**一个含空格的元素** → `# Spaces not allowed in ACL usernames` → ACL 加载致命错误退出 |
| 修复 | `docker-compose.yml` 的 ACL 改为逐 token 独立列表元素（37 个启动参数）；`docker compose up -d --force-recreate redis` |
| 数据影响 | **无**。`/data/redis` 为空（容器从未成功启动，无 AOF/RDB），重建不涉及数据丢失 |
| 备份 | 原文件留存为 `/home/admin/middleware/docker-compose.yml.bak-20260929-150856`（sha256 `6c949d59…`） |

**验收证据（全部实跑）**：容器 `Up (healthy)`、`RestartCount=0`（30 秒观察无增长）、启动日志
`Ready to accept connections tcp` 无 FATAL；`default` 与 `helloai` 用户 `PING → PONG`；
错误口令 `WRONGPASS`、匿名 `NOAUTH`、`FLUSHALL` `NOPERM` **均按预期被拒**；
`acl list` 两个用户的命令限制正确；`aof_enabled=1` 且写入状态 `ok`。

**同批完成**：`vm.overcommit_memory` 由 `0` 改为 `1` 并持久化到 `/etc/sysctl.d/99-redis.conf`。

**仍未处置（需人工拍板）**：§9 首条——凭据轮换；安全组白名单（当前 `ufw inactive`，
经公网实测 `15432 / 13306 / 26379 / 25672 / 25673 / 29000 / 29001` **全部可直连**）。
截至本次操作，**没有任何应用进程在连该中间件**（Redis 无外部客户端、PG 无外部会话），
因此轮换凭据的窗口期成本最低。
