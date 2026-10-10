# HelloAI 平台备份与停机恢复流程（Runbook）

> **状态：运维手册（随接口演进）**
> 最后更新：2026-10-10
> 适用能力：`REF-2.3`（备份编排）/ `REF-2.3b`（恢复三门）/ `REF-2.4`（停机恢复流程）—— 差距表 `G-018`
> 依据：`plan/HelloAI 借鉴落地实施计划.md` §4（`D-2026-10-10-2`）、`LOG-20261010-007` / `LOG-20261010-008`
> 验证载体：`scripts/powershell/verify-backup-restore.ps1`（端到端演练）、`RestoreGateTest` / `DbSchemaVersionReaderTest` / `PgDumpRunnerTest`（单测）

本手册回答「怎么用」：**怎么备份、怎么判断一份备份能不能恢复、停机恢复时按什么顺序动手**。
能力边界与代码落点的**唯一事实源**是《差距表》`G-018` 与代码本身，本手册不复述进度数字。

---

## 0. 先读这一节：能力边界（诚实边界）

备份恢复最容易出事的不是"命令敲错"，而是**对备份的想象超出它实际包含的东西**。下列四条在任何一次操作前都应确认无误：

```text
① 备份的「数据库」是单事务一致快照；但「对象清单」与 dump **不是同一瞬间**取的，
   且对象**本体根本不在备份内**（只有 key + size 的清单） ⇒ 不得宣称「多文件同一瞬间的一致快照」。
② 恢复的语义是「把备份灌进目标库」，验证口径是**空库**；对已有同名对象的库直接执行会大面积
   报错并以非 0 退出码结束（此时恢复已部分执行）⇒ 停机恢复走「新建空库 → 恢复 → 切库」。
③ 恢复**不回填对象本体**：attachment 行恢复后指向的仍是对象存储里的既有对象；
   桶在则对象还在，桶没了则只剩清单，**内容无法还原**。
④ 恢复是**同引擎 + 停机**路径：跨引擎、schema 高过运行时、在线恢复一律**拒绝**并给出可读原因。
```

### 0.1 备份包含什么 / 不包含什么

一次备份 = 独立桶下一个**前缀**，前缀内含三个对象：

| 对象 | 内容 | 说明 |
|---|---|---|
| `database.dump` | `pg_dump -Fc` 全库归档 | 单事务一致快照；`pg_restore -l` 可**不解档**读归档头 |
| `artifacts.json` | 对象存储**清单**（`count` + `objects[{key,size}]`） | **只有键与大小，没有对象内容** |
| `manifest.json` | 自描述元数据 | 恢复侧三门先读它（不解档）：`pgVersion` / `flywayMaxVersion` / `database` / `dumpKey` / `dumpSha256` / `artifactCount` / `artifactBucket` / `operator` |

**不包含**（明确非目标，不要在恢复计划里假设它们存在）：

- 对象**本体**（附件、产物文件）—— 备份的是「清单」，不是「内容」；
- 对象存储自身的配置（bucket policy / 用户 / 别名）；
- Redis / RabbitMQ / 中间件数据；
- 「报告回滚」语义 —— 那是 `task.final_report` / `final_report_prev` 两槽互换（差距表 `G-016`），与本手册的「恢复」**不是一回事**，措辞不得混用。

### 0.2 三条不变量（代码已保证，运维据此判断"是否异常"）

```text
① 运行中备份**不锁库** —— 备份期间数据库照常读写（演练 S3 断言）。
② 「listObjects 返回空」**必须显式失败** —— 桶为空但库里有产物引用时，备份中止并给出原因，
   绝不产出「看起来完整、其实没备」的备份。
③ 手动备份**永不**被自动保留策略淘汰 —— 只有 AUTO 备份参与按份数淘汰。
```

---

## 1. 前置条件

### 1.1 运行环境

| 项 | 要求 | 不满足时的表现 |
|---|---|---|
| `pg_dump` / `pg_restore` | 客户端**主版本 ≥ 服务端主版本**（本项目服务端 `postgres:16.4`） | 备份端点**显式报不可用**（不静默降级），其余功能不受影响 |
| 对象存储 | `helloai.storage.type=minio` | 显式拒绝：`备份功能要求对象存储`（本地磁盘不构成备份） |
| 备份桶 | 独立桶（默认 `helloai-backups`），**不得**等于 `helloai.storage.minio-bucket` | 桶不存在会自动创建；**共用产物桶会被孤儿清理删掉**（对账巡检枚举整桶） |

客户端来源（三环境已实测）：

```text
服务器 Docker 一键部署 ：app 镜像内置 postgresql-client-16（Dockerfile stage 3），
                        application.yml 的 pg-dump-path / pg-restore-path 保持留空即按 PATH 查找。
本地源码开发（IDEA 直跑）：PG 官方 zip 版二进制解压到仓库内 .tools/pgsql（已 gitignore，免安装不注册服务），
                        application-local.yml 已指向 .tools/pgsql/bin/pg_dump.exe。
PG 容器内             ：自带客户端，但平台**不走 docker exec**（那需要挂 docker.sock）。
```

### 1.2 配置项（`helloai.backup.*`，落点 `BackupProperties`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关；关闭时备份端点显式返回不可用 |
| `bucket` | `helloai-backups` | 备份产物桶（**必须独立**于产物桶） |
| `pg-dump-path` / `pg-restore-path` | 空 | 空 ⇒ 按 PATH 查找（服务器即此）；可配绝对/仓库相对路径 |
| `host` / `port` / `database` / `username` / `password` | 空 | **全留空 ⇒ 自动从 `spring.datasource.url` 派生**（防两处漂移）；`password` 只经进程 env `PGPASSWORD` 传递，**不进命令行** |
| `auto-enabled` | `false` | 定时自动备份开关；开启前须确认宿主/镜像侧具备 `pg_dump` |
| `auto-interval-ms` | `21600000`（6h） | 自动备份间隔 |
| `auto-retention-count` | `7` | **仅**自动备份按份数保留；手动备份永不淘汰 |
| `command-timeout-seconds` | `1800` | 单次 `pg_dump` / `pg_restore` 超时（超时杀进程并判失败） |
| `key-prefix` | `backups` | 桶下键前缀（一次备份一个前缀） |

### 1.3 权限码

| 权限码 | 覆盖接口 |
|---|---|
| `backup:view` | 列表 / 详情 |
| `backup:run` | 手动触发备份 / 恢复预检 / 执行恢复 |

---

## 2. 备份

### 2.1 触发

```text
手动：POST /api/backup                  → 提交即返回（建 RUNNING 台账行 + 异步执行），响应体含台账 id
      轮询 GET /api/backup/{id}         → 看 state（RUNNING / SUCCESS / FAILED）
自动：PlatformBackupTask（helloai-job，ShedLock 定时 + Redisson 单飞锁）
      默认关闭；开启方式：helloai.backup.auto-enabled=true（或 BACKUP_AUTO_ENABLED=true）
```

- 手动与自动**共用同一把分布式单飞锁**（`backup:lock:platform`，租期 2h）——同一时刻全平台只跑一个备份；抢不到锁的那次直接记 `FAILED`（"已有备份正在进行"），不会排队叠加。
- 手动触发的执行线程来自单线程池 `backupExecutor`（队列容量 4，溢出即拒绝）。
- 前置检查（`pg_dump` 可用 / 存储可用）**在建台账行之前**跑：能力不可用时不会留下一条注定失败的行。

### 2.2 成功判据

备份成功的**唯一判据是台账 `state='SUCCESS'`**。逐项核对建议（按重要性排序）：

| 台账字段 | 含义 | 说明 |
|---|---|---|
| `state` | `SUCCESS` / `FAILED` | `FAILED` 时必看 `failure_reason`（可读原因） |
| `object_prefix` | 本次备份在桶下的前缀 | 恢复时按它取对象；形如 `backups/manual/2026/10/10/<uuid8>/` |
| `pg_version` | 归档头的 `Dumped from database version` | 门①判据；须与运行时服务端主版本一致 |
| `flyway_max_version` | 备份时库内已应用的最高迁移号 | 门②判据；须 ≤ 当前代码内最高迁移号 |
| `dump_bytes` / `artifact_count` / `artifact_bytes` / `total_bytes` | 归档字节 / 纳入清单的对象数 / 对象总字节 / 合计 | 体积异常偏小应警觉 |
| `checksum_sha256` | **dump 归档**的 SHA-256 | 以代码为准确认（`V105` 的表注释写作「manifest 的 SHA-256」，与实现不符，属**已知的注释漂移**，不据此判断） |
| `started_at` / `finished_at` / `create_by` | 起止时刻 / 触发者（手动=登录名，自动=`system`） | RUNNING 长期不收敛应查日志 |

> 台账是**可查询的索引**，`manifest.json` 是**随备份走的自描述**。两者刻意部分冗余：台账行会随保留淘汰删除，manifest 不会。

### 2.3 保留策略

```text
AUTO  ：按份数保留最新 N 份（auto-retention-count，默认 7），更旧的连同桶内对象一并删除；
        淘汰失败不阻断备份本身，下一轮重试。
MANUAL：永不淘汰 —— 那是人的产物，不该被机器回收。
```

演进提示：GFS（keep-daily / weekly / monthly）留作后续精化，当前**不做**。

---

## 3. 恢复（停机操作）

### 3.1 恢复侧三门（先于任何 `pg_restore` 执行）

| 门 | 判据 | 拒绝时的可读原因 |
|---|---|---|
| ① 跨引擎 | 归档头 `Dumped from database version` 的**主版本** == 运行时 PG 主版本（取自 JDBC 元数据） | `恢复被拒[跨引擎]：备份由 PG x 导出，当前运行时为 PG y…` |
| ② schema 高过运行时 | `manifest.flywayMaxVersion`（**数值最大**）> 当前**代码内**已知最高迁移号（classpath `db/migration/V*.sql`） | `恢复被拒[schema]：备份的 schema 版本 Vn 高于当前运行时代码已知的最高迁移 Vm…` |
| ③ 在线恢复 | 库上**活动查询**数（`pg_stat_activity.state='active'`，排除自身连接）== 0 **且** 在飞子任务数（口径 `ASSIGNED`/`IN_PROGRESS`/`REWORK`）== 0 | `恢复被拒[在线]：…恢复要求停机（REF-2.4）` |

判据说明：

- 门①**不解档** —— 读的是 `pg_restore -l` 的归档头（实测可行：头部直接给出 `Dumped from database version`）。
- 门②比的是**代码认识多新**（classpath 迁移文件），不是库里的 `flyway_schema_history`。备份比代码旧是**允许**的（恢复后由 Flyway 在下次启动补齐）；比代码新才拒。
- 门③用「活动查询」而非「连接数」：应用自身 Hikari 池常有 idle 连接，按连接数判会把正常运行误判为"在线"、每次都拦。
- 三门判定**先于**任何恢复动作；判不出来时不放行（宁可拒绝）。

### 3.2 路径 A —— 预检（只读，任何时刻可跑）

```text
POST /api/backup/{id}/restore/preflight     权限 backup:run
→ { restorable: true|false, reason, pgVersion, flywayMaxVersion, database }
```

只读、可反复调用、**不产生任何副作用**。三门拒绝属**判定结论**（HTTP 200 + `restorable=false` + 可读原因），不是调用错误 —— 先把"能不能"问清楚，再决定选哪个停机窗口。

### 3.3 路径 B —— 平台在运行时的一键恢复（**仅限目标库无同名对象**）

```text
POST /api/backup/{id}/restore               权限 backup:run
body: {"confirm":"RESTORE"}                 ← 确认词大小写不敏感，必须显式写下
```

前置：备份 `state='SUCCESS'`、台账有 `manifest_key`/`dump_key`、三门全过。执行即 `pg_restore --no-owner --no-privileges -j 4`。

> ⚠️ **该端点只能打「配置库」**（无目标库参数），且**不带 `--clean`**（冻结的落地硬约束：复用既有 `migrate.sh` 的写法）。
> 实测：对**已含同名对象**的库执行，会因 `relation ... already exists` / `duplicate key` 等报错，
> `pg_restore` **继续执行但以非 0 退出码结束**（末尾汇总 `errors ignored on restore: N`），平台据此判**失败**
> —— 而数据可能已被部分改写。故本路径的实用场景是**目标库为新库/空库**（演练即此口径）。

### 3.4 路径 C —— 平台完全停机时的手工恢复（**生产推荐**）

平台已停、HTTP 端点不可达时，用与平台**同一套命令与判据**手工恢复：

```bash
# 0) 停机窗口：停业务（compose 里 app 服务名 = app，容器名 = helloai-app）
docker compose -f docker-compose.server.yml stop app

# 1) 取回制品（前缀取自台账 object_prefix；桶为独立桶 helloai-backups）
#    docker-compose 部署下 MinIO 容器名 = helloai-minio（演练脚本即用这一句配别名）
docker exec helloai-minio mc alias set bk http://localhost:9000 <access-key> <secret-key>
docker exec helloai-minio mc cp bk/helloai-backups/<object_prefix>manifest.json  ./restore/
docker exec helloai-minio mc cp bk/helloai-backups/<object_prefix>database.dump   ./restore/

# 2) 手工核对三门（与平台同一判据；PG 容器名 = helloai-postgres，客户端在 .tools/pgsql/bin 或镜像内）
pg_restore -l ./restore/database.dump | head -5      # ① Dumped from database version 主版本 == 服务端主版本
cat ./restore/manifest.json                           # ② flywayMaxVersion ≤ 代码内最高迁移号（classpath V*.sql）
psql -c "SELECT count(*) FROM pg_stat_activity WHERE datname='helloai' AND state='active' AND pid<>pg_backend_pid()"   # ③ 应为 0

# 3) 新建空库后恢复（**空库**是这个流程的关键前提）
psql -d postgres -c 'CREATE DATABASE helloai_restore'
PGPASSWORD=<pw> pg_restore -h <host> -p 5432 -U <user> -d helloai_restore \
    --no-owner --no-privileges -j 4 ./restore/database.dump
echo "pg_restore exit=$?"                            # 必须为 0；非 0 表示存在被忽略的错误（含对象已存在）

# 4) 对账（表数 + 关键表行数），口径与演练脚本一致
psql -d helloai_restore -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'"

# 5) 切库：把 spring.datasource.url 指向新库后启动应用；旧库保留一个观察期再清理
```

### 3.5 恢复后必做

```text
① 重启应用 —— 连接池、MyBatis 映射与会话状态都指向旧库快照，不重启会读到不一致状态；
② 确认 Flyway —— 备份的 schema 若比代码旧，启动时由 Flyway 补齐到当前基线（这是允许且预期的）；
③ 核对产物 —— attachment 记录会指向对象存储里的既有对象；**对象本体不在备份内**，
   若对象存储同时受损，只能拿 artifacts.json 的清单（key + size）去外部备份/快照还原；
④ 记录 —— 谁、何时、恢复哪个 backupId、目标库、结果，落运维记录。
```

### 3.6 失败与回退

```text
三门任一不过        ：不执行任何恢复动作，按 reason 处置（升级平台 / 换停机窗口 / 换同一主版本引擎）；
pg_restore 非 0 退出 ：恢复可能已部分执行 —— **不要**在同一个库上重跑（会叠加报错），
                      改用「新建另一个空库 → 重新恢复 → 校验通过再切库」；
查询后仍怀疑数据     ：旧库在切库前**不要删**，它是回退位。
```

---

## 4. 验收与演练

| 载体 | 覆盖 | 约定 |
|---|---|---|
| `scripts/powershell/verify-backup-restore.ps1` | 真实备份 → 台账字段核对（含 `flywayMaxVersion` 数值最大回归）→ preflight → **真恢复进一次性探针库** → 逐表对账 → 清理 | 真恢复**只对一次性探针库**（`helloai_restore_probe`，用完即删），**绝不**指向配置库；app 一键恢复端点按停机操作**不端到端跑**（脚本 S7 打印说明，不假装验过） |
| `RestoreGateTest` | 三类非法恢复各一个必拒绝用例（线上造不出伪造归档头 / manifest） | 单测覆盖三门 |
| `DbSchemaVersionReaderTest` | 字典序陷阱回归（`{99,105} ⇒ 105`） | 门②的判据来源必须取**数值最大** |
| `PgDumpRunnerTest` | 归档头解析 / 目标解析 / 就地失败 | — |

本手册路径 C 的「对已有同名对象直接恢复会非 0 退出」一节，为**实测结论**（本地 PG 16 上以 `pg_restore` 重复恢复同一归档复现：`errors ignored on restore: 9`，退出码 1），非推断。

---

## 5. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-10 | 建立本手册（`REF-2.4`）：能力边界（对象本体不在备份内 / 运行中备份不比同一瞬间 / 恢复口径为空库）、配置与权限、备份判据、恢复三路径与三门、停机恢复清单、演练载体。同批补上生产镜像的 `postgresql-client-16` 前置（`Dockerfile` stage 3）。 |
