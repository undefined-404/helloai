# HelloAI 上线核对清单

> **Status: Active**
> 最后更新：2026-10-10
> 依据：《文档体系分类与治理规则》§3.2（执行清单与预案 → `plan/`）；各条目在自己的小节里给出事实源与验证出处

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

## 2. 后续小节（按需追加）

新的专项若产出「只在部署环境可验」的核对项，追加为独立小节，并在小节首行给出出处
（`LOG-…` / 差距表 `G-0xx`），与本节同构。**不要把本地可验项搬进来。**

---

## 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-10 | 建立本清单；首节「备份 / 恢复（`REF-2`）」三条来自 `LOG-20261010-008` —— 该轮 REF-2 回归与门禁均在**本地**完成，三条部署侧核对项此前无任何载体。 |
