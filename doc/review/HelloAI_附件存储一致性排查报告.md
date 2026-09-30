# HelloAI 附件存储一致性排查报告

- 排查时间：2026-09-28
- 排查范围：`attachment` 表（云 dev 库 `39.106.204.43:15432/helloai`）↔ MinIO 桶 `helloai-artifacts`（`http://39.106.204.43:29000`）
- 触发问题：外部 AI Agent 上传的附件在附件管理处无法预览（HTTP 500）；怀疑子任务「调研报告整合、来源追溯与验收自检」的 16 个附件未落到 MinIO
- 取证方式：JDBC 直连读库 + MinIO S3 API 逐条 `statObject` 对账（脚本 `.tmp/MinioReconcile.java`）

---

## 0. 结论摘要（先看这里）

| 编号 | 命题 | 判定 | 依据 |
|---|---|---|---|
| **①** | 「16 个附件在 MinIO 桶中查不到」 | **不成立** | 16 个对象全部在桶内，`objectKey` 与 DB 完全一致、字节数逐一相等 |
| **②** | 「点预览报 500」 | **成立** | 本地实例 MinIO 端点指向 `http://localhost:29000`，本地无 MinIO 进程 → 连接被拒 → `BizException` 默认 code=500 |
| **③** | 「不同子任务上传同一个文件夹」 | **部分成立，但不是 Bug** | 整合子任务把上游 9 份产出**逐字节复制**后重新上传到自己名下（ETag 完全相同），属 Agent 行为，非路径冲突 |
| **④** | 真实问题（用户未察觉，**比 ① 严重得多**） | **106 / 260 条附件记录在 MinIO 中无对应对象（40.8%）**，按「整天整块」分布，呈环境切换指纹 |

> 一句话：**你看到的 16 个附件没问题；真正坏掉的是另外 106 个历史附件，占了全库四成。**

---

## 1. DB ↔ MinIO 全量对账结果

```
DB attachment 总行数        = 262   （含逻辑删除 2 条）
有效 minio:// 记录          = 260
  ✔ 对象存在且字节数一致     = 154   （59.2%）
  ✘ 对象在 MinIO 中缺失     = 106   （40.8%）
  ⚠ 存在但字节数不符         =   0

MinIO 桶 helloai-artifacts  = 170 个对象
  ├─ 154 有 DB 记录对应
  └─  16 孤儿对象（MinIO 有、DB 无）
170 = 154 + 16   ← 数字完全闭合，对账可信
```

其他：
- 桶 `trae-executor`、`helloai-local` **均不存在**（有 2 条已逻辑删除的记录把 `trae-executor` 当成了 bucket，见 §3.3）。
- `helloai.storage.type = minio`，`minio-bucket = helloai-artifacts`（`helloai-start/src/main/resources/application.yml:298-307`）。

---

## 2. 命题①：16 个附件**确实在桶里**

子任务 `2103027074955993090`（标题「调研报告整合、来源追溯与验收自检」，主任务 `2103026929396867074`，执行者 `workBuddy-executor`）：

- DB：16 条记录，全部 `status=ACTIVE`、`deleted=0`、`create_time = 2026-09-24 16:27:40~42`
- MinIO：前缀 `workBuddy-executor/2026/09/2103026929396867074/2103027074955993090/` 下**正好 16 个对象**，`lastModified = 2026-09-24T08:27:40~42Z`（UTC，与 DB 本地时间 +08:00 同一瞬间），**字节数逐条相等**（11042 / 8806 / 6659 / 7306 / 7014 / 10945 / 7455 / 7950 / 12630 / 9525 / 10337 / 3765 / 2828 / 6906 / 3668 / 8971）。

采样佐证：

```
EXISTS 11042B  .../87b4d5f6-00_最终调研分析报告_总览与结论汇总.md
EXISTS  6659B  .../2a382a3d-02_第2章_候选清单与证据规范.md
EXISTS  8971B  .../d7e254a3-调研报告整合、来源追溯与验收自检.md
（16/16 全部 EXISTS，0 MISSING）
```

**为什么你会以为"查不到"？两种可能，都需要你确认：**

1. **凭据不对。** `deploy/middleware/.env` 里的 `MINIO_ROOT_USER/PASSWORD` 与 `HELLOAI_S3_ACCESS_KEY/SECRET_KEY` 在 `39.106.204.43:29000` 上**认证失败**（`SignatureDoesNotMatch`）；实际可用的是 `application.yml` 的默认值 `minioadmin / minioadmin123`。
2. **看错了实例/路径。** 本地没有 MinIO（`localhost:29000` 无监听），若打开的是本机 `localhost:29001` console 自然什么也没有；即便在服务端 console，也需逐级进入 `workBuddy-executor / 2026 / 09 / <taskId> / <subTaskId> /` 才看得到，桶根目录不会平铺文件名。

> ⚠️ 顺带一个**安全发现**：`39.106.204.43` 的 MinIO（29000/29001）对公网开放，且可直接用默认凭据 `minioadmin/minioadmin123` 登录（本次即从本机跨公网直连成功）。同主机 PG(15432)、Redis(26379)、RabbitMQ(25672/25673) 同样公网可达。建议立即改密钥 + 收敛安全组。

---

## 3. 命题②：预览 500 —— 根因是**运行实例的 MinIO 端点配置**

### 3.1 代码路径（能 500 的只有一处）

```
GET /api/attachments/previewById/{id}
  → AttachmentServiceImpl.isPreviewable()   // minio:// 且 mime=text/* → true，不拦
  → AttachmentServiceImpl.loadContent()
  → MinioArtifactStorage.load()
      catch (Exception e) → throw new BizException("产物读取失败: " + msg)
  → GlobalExceptionHandler: BizException(String) 默认 code=500  →  HTTP 500
```

`BizException(String)` 的 `code` 硬编码为 **500**（`helloai-common/.../base/BizException.java:12`），所以只要读取抛异常就是 500，不会退化为 413 或 302。

### 3.2 现场日志（决定性证据）

`logs/helloai.log` 今日 12:31 报告中整合链路连续刷屏：

```
附件内容读取失败，报告链跳过该附件: attachmentId=2103038645719523330,
  err=产物读取失败: Failed to connect to localhost/[0:0:0:0:0:0:0:1]:29000
... （16 条正好对应上述 16 个附件，attachmentId 一一匹配）
```

- `application.yml` 默认 `minio-endpoint: ${MINIO_ENDPOINT:http://localhost:29000}`
- `application-dev.yml` **只覆盖了 datasource / redis / rabbitmq，没有覆盖 `helloai.storage.minio-endpoint`** → 本地实例用的是 `localhost:29000`
- 本机 `netstat` 确认 **29000 无监听**，`docker` 命令不存在 → 本地 MinIO 根本不在跑

**结论：本地 dev 实例读任何 `minio://` 附件都必然失败**（预览 500 / 下载 500），与对象是否存在无关。真实对象在 `39.106.204.43:29000`。

### 3.3 旁证：一个连带影响

同一现象解释了上一轮报告质量退化——整合报告生成时 16 个附件**全部读取失败被跳过**，所以正文才只能用"见分册第 X 章"这类空壳引用代替真实内容。这不是 Prompt 问题，是**存储端点配置问题**。

另：2 条已逻辑删除的记录 `storage_url = minio://trae-executor/2026/08/...`，把 **Agent 注册名当成了 bucket**。这正是 MCP 工具描述 `storageUrl 建议按 {自身注册名}/{yyyy}/{MM}/{taskId}/{subTaskId}/{文件名} 组织` 被字面执行的产物——该描述讲的是 **objectKey 布局**，没有说清「URL 里第一段必须是 bucket」，属于**误导性文档**。

---

## 4. 命题③：不同子任务"上传同一个文件夹"——不是 Bug

按 `ETag + size` 指纹在**同一主任务内**做跨子任务比对，命中 9 组、18 条记录：

| 组 | 上游子任务产出 | 整合子任务（…3090）中的同名副本 |
|---|---|---|
| 1 | `…874 调研框架与证据规范_v1_A.md` | `01_第1章_调研框架与证据规范.md` |
| 2 | `…874 候选清单与抽查验证_v1_B.md` | `02_第2章_候选清单与证据规范.md` |
| 3 | `…483 开源项目对比矩阵_v1.md` | `04_第4章_开源项目对比矩阵.md` |
| 4 | `…482 商业成熟产品对比矩阵_v1.md` | `03_第3章_商业成熟产品对比矩阵.md` |
| 5 | `…484 认证授权…_v2.md` | `05_第5章_认证授权与统一标识专题.md` |
| 6 | `…785 内网外网部署…_v1.md` | `06_第6章_内网外网部署与跨网同步.md` |
| 7 | `…786 技术栈与数据库兼容性评估矩阵_v1.md` | `07_第7章_技术栈与数据库兼容性.md` |
| 8 | `…089 选型建议与参考方案_v1_A.md` | `08_第8章_选型建议与参考方案.md` |
| 9 | `…089 选型建议与参考方案_v1_B.md` | `09_第8章_参考架构与组件集成.md` |

**判定**：整合子任务的执行 Agent 为了让自己的交付物**自包含**，把上游产出逐字节复制并以 `01_/02_/…`（章节号，不是子任务号）重命名后重新上传。因此——

- **没有跨子任务的文件系统冲突**：`objectKey = {owner}/{yyyy}/{MM}/{taskId}/{subTaskId}/{uuid8}-{safeName}`，`subTaskId` 段天然隔离，路径不会互相覆盖。
- 你在附件管理页看到某些子任务里出现 `02_…`/`03_…` 文件名，那是**章节编号**，不是"子任务 2、3 的附件跑错地方"。
- 代价是**存储冗余**（9 份文件双份存储），且下游读取/报告链会把这 9 份内容**当成两份不同材料**重复摄入。

---

## 5. 命题④：真正的问题 —— 106 条记录在 MinIO 中无对象

### 5.1 按创建日期分布（**核心证据**）

| 日期 | 存在 | 缺失 | | 日期 | 存在 | 缺失 |
|---|---:|---:|---|---|---:|---:|
| 08-18 | 23 | 0 | | 09-04 | 0 | **1** |
| 08-25 | 6 | 0 | | 09-07 | 0 | **4** |
| 08-26 | 23 | 0 | | 09-08 | 0 | **26** |
| 09-01 | 7 | 0 | | 09-10 | 0 | **46** |
| 09-02 | 0 | **24** | | 09-11 | 26 | 0 |
| 09-03 | 5 | **5** | | 09-24 | 64 | 0 |

**读法**：整块存在（08-18～09-01，09-11～09-24）与整块缺失（09-02～09-10）交替，**同一天内几乎不混杂**。这不是"某些 Agent 少传了文件"的随机缺失，而是**运行实例切换**的指纹——某段时间内平台进程写入了**另一个 MinIO 实例**。

结合 §3.2 的事实（本地实例指向 `localhost:29000`，指向的是**本机/本地容器里的 MinIO**）：

- 缺失那几天（09-02～09-10），任务大概率由**本地实例**执行 → 对象写进了本地 MinIO（现已不存在：本机 29000 无监听、`docker` 命令缺失、`D:\minioData` 仅为 2023 年的旧实例且只有 `otatest` 桶）。
- 存在那几天，任务由**服务端实例**执行 → 对象落在 `39.106.204.43:29000`。

**这是最严重的结构性问题：DB 是共享的（云 dev 库），对象存储是每个环境各一份，而 `attachment` 表不记录"这份对象属于哪个 MinIO"。** 只要换个运行环境，历史附件就集体变成悬空指针。

### 5.2 附带说明

- 所有 106 条缺失记录的 `objectKey` 都带 `uuid8-` 前缀（如 `3641fb6a-c3gs-r1-verification-evidence-note.md`），说明它们**确实走过 `MinioArtifactStorage.store()`**（`buildObjectKey` 生成），不是 MCP 只登记元数据的 `uploadArtifact` 路径——所以不是"没上传"，是"上传到了别处"。
- **可恢复性**：若本地 MinIO 的数据卷（Docker volume / 本地目录）还在，106 条中属于本地实例的那部分**有找回可能**；否则内容不可恢复，只能标记失效。**需要你确认**：那段时间的 MinIO 是用 Docker 起的吗？数据卷是否还在？

### 5.3 孤儿对象 16 个

MinIO 有对象、DB 无记录，例如：

```
trae-executor/2026/08/2089595660903194626/2089595974993649666/
  6e9444ec-《部署工具推荐与安装说明》小节；本机已完成主推工具安装…   （同名 4 份）
inner-deepseek-flash-executor/2026/08/2092598583915954177/2092598584591237121/
  redispatch.py / EVIDENCE.md / ACCEPTANCE_CHECK.md / state.example.json …
```

来源是 `store()` 成功但 `register()` 失败的分支——`ArtifactUploadServiceImpl` 类注释里明确写了这是"可接受"的残留（DB 回滚、对象留存）。**目前无清理机制**。

---

## 6. 根因归因

| # | 根因 | 影响 | 级别 |
|---|---|---|---|
| R1 | `application-dev.yml` 未覆盖 `helloai.storage.minio-endpoint`，本地实例退化为 `localhost:29000`，而本地无 MinIO | 预览/下载 500；报告链读不到附件；附件管理页"打不开" | **P0** |
| R2 | DB 共享而对象存储分环境，`attachment` 不记录对象所属 MinIO 实例 | 106/260（40.8%）附件悬空且大概率不可恢复 | **P0** |
| R3 | `register()` 不校验对象存在性；MCP `uploadArtifact` 允许登记任意 URL | 可产生"有记录无对象"的僵尸附件，预览时才暴雷 | **P1** |
| R4 | MCP 工具描述 `storageUrl 建议按 {自身注册名}/...` 语义不清 | Agent 把注册名当 bucket（`minio://trae-executor/...`） | **P1** |
| R5 | `store` 成功 / `register` 失败的孤儿对象无清理 | 桶内 16 个垃圾对象 | **P2** |
| R6 | 整合子任务整份复制上游产出 | 9 组内容重复存储、重复摄入 | **P2** |
| R7 | MinIO 公网暴露 + 默认凭据可登录 | 数据泄露风险 | **P0（安全）** |

---

## 7. 建议的处置

**立即（P0）**
1. `application-dev.yml` 增加一行，把本地 dev 实例指向服务端 MinIO：
   ```yaml
   helloai:
     storage:
       minio-endpoint: ${MINIO_ENDPOINT:http://39.106.204.43:29000}
   ```
   （或本机起一个 MinIO 容器，但那样会复现 R2 的分裂）
2. 改掉 MinIO 默认凭据，并把 29000/29001/15432/26379/25672 收到安全组白名单内。

**短期（P1）**
3. `AttachmentServiceImpl.register()` 在写入前对 `minio://`/`local://` 做一次 `statObject` / 文件存在性校验，不存在直接拒绝（把 500 提前成 400），并在日志里带上 agent 与 storageUrl。
4. 修正 MCP `uploadArtifact` 的工具描述：明确「`storageUrl` 形如 `minio://helloai-artifacts/{注册名}/{yyyy}/{MM}/{taskId}/{subTaskId}/{文件名}`，第一段必须是 bucket，**不要用 agent 名当 bucket**」；并在 `register()` 里对 bucket 段做白名单校验。

**中期（P2）**
5. 加一个对账巡检：定期比对 `attachment` 与 MinIO，输出悬空记录 + 孤儿对象清单（本次的 `.tmp/MinioReconcile.java` 可直接改造为运维脚本）。
6. 清理孤儿对象；给整合链路的"复制上游产出"改为**引用上游 attachment** 而非重传副本。

---

## 7.1 处置落地跟踪（2026-09-28）

本次排查后代码已按 §7 落地处置，跟踪如下：

| # | 处置 | 状态 | 落地内容 |
|---|---|---|---|
| R1 | dev 端点配置 | **已落地 ✅** | `application-dev.yml` 已覆盖 `helloai.storage.minio-endpoint` 指向服务端 MinIO（`http://39.106.204.43:29000`），本地实例不再退化 `localhost:29000` |
| R2 | 悬空不可恢复 | **缓解 ⚠️** | 存储抽象 + 对账巡检（6h 轮询，ShedLock 互斥）使 106 条悬空记录**可见可报告**（悬空/孤儿/字节不符三态）；历史数据不自动修复（不确定归属存储实例，不臆改） |
| R3 | register 前置校验 | **已落地 ✅** | `AttachmentServiceImpl.register` 写入前 `validateAddress` + supports 时 `exists` 存在性校验，不存在即 400 拒绝（@Transactional 连同名去活一并回滚） |
| R4 | MCP 描述重写 | **已落地 ✅** | `uploadArtifact` 描述固定 storageUrl 格式 `minio://helloai-artifacts/{ownerName}/{yyyy}/{MM}/{taskId}/{subTaskId}/{文件名}`：bucket 段白名单（必须等于平台桶，否则 400 带错误示例）、登记前平台校验对象存在、整合类子任务建议复用上游 storageUrl |
| R5 | 对账巡检 + 孤儿清理 | **已落地 ✅** | `ArtifactStorageReconcileTask`（6h + ShedLock PT20M + `reconcile-enabled` 开关，只读巡检默认开启）；孤儿清理独立开关默认**关闭**，开启后仍需三重保险（开关 + 24h 时间窗 + 单轮上限 200），被任何行引用永不删除 |
| R6 | 复制冗余 | **文档引导 ⚠️** | 与 R4 合并落地（MCP 描述建议整合子任务复用上游附件引用）；存量 9 组重复不主动清理，留人工决策 |
| R7 | 安全（公网 + 默认凭据） | **未做 ❌** | 属部署侧动作待用户执行：改掉 MinIO 默认凭据 + 29000/29001/15432/26379/25672 端口收安全组白名单 |

验证：storage 相关定向测试 **72 例 0 失败**（CompositeArtifactStorage 13 / LocalArtifactStorage 8 / MinioArtifactStorage 20 / ArtifactStorageReconcileServiceImpl 13 / AttachmentServiceImpl 18，见 `.tmp/test-storage-r12.log`）；迭代记录见 `doc/log/2026-09.md`「2026-09-28 附件存储一致性：存储抽象 + 对账巡检（排查报告处置落地）」条目；差距表 G-017。

---

## 8. 附：取证脚本与原始输出

| 文件 | 用途 |
|---|---|
| `.tmp/MinioReconcile.java` | DB↔MinIO 全量对账（存在性 / 字节数 / 孤儿 / 跨子任务重复） |
| `.tmp/minio-reconcile2.out.txt` | 对账原始输出（含 106 条缺失清单、日期分布、16 条孤儿、9 组重复） |
| `.tmp/MinioAudit.java` / `.tmp/minio-audit.out.txt` | 目标子任务前缀对象清单 + 16 条逐条 `statObject` |
| `.tmp/diag-attachment3.out.txt` | 16 条附件明细与主任务/子任务归属 |
