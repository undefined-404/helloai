# HelloAI 架构收口批次 —— 提交/上线前检查清单与回滚预案

> 作者：部署工程师 狄云　｜　生成方式：只读核查（未执行 mvn / 未做任何 git 写操作）
> 核查对象：`E:/yhzx/1027/helloai`，HEAD = `2bb78b8`（2026-09-30 23:28「文档更新」）
> 核查时间：工作区含 70 项已跟踪改动 + 20 项未跟踪文件（详见 §1.1）

---

## 0. 核查结论与事实订正（先看这里）

| 待核事实 | 核查结果 | 证据 |
|---|---|---|
| shared→task 反向依赖清零 | ✅ 属实 | `scripts/ci/arch-baseline.txt` 现 `shared->task=0`；`grep` 无 `shared.util.SubTaskDependencyOrder` 残留 |
| shared→agent 反向依赖清零 | ✅ 属实 | 现 `shared->agent=0`；`grep "core.shared.doorbell"` 与 `shared.event.ExecutionCommandCreatedEvent` 均**零残留** |
| doorbell 搬迁不丢 Bean 注册 | ✅ 属实 | 启动类 `@SpringBootApplication(scanBasePackages="com.helloai")`；新包 `core.agent.doorbell` 在其下；`@MapperScan` 不含 doorbell；无 `spring.factories` |
| 无 DB schema 变更 / 无 Flyway 迁移 | ✅ 属实 | `git status` 无 `db/`、无 `V*__*.sql` 改动；`db/migration` 无新增 |
| 无新增三方依赖 | ✅ 属实 | `git diff -- '*pom.xml'` 无 artifactId/groupId 增改 |
| **无配置文件改动** | ❌ **不符（重要）** | 工作区实际有 **4 个配置/示例文件**被改动，且属**安全加固 + fail-fast** 语义（见下） |
| 约 40 项未提交改动 | ⚠️ 低估 | 实测 **70 项已跟踪**（含 2 笔已暂存删除）+ **20 项未跟踪**（文件级） |
| 本批仅 4 项改动 | ⚠️ 不足 | 工作区还含**第 ⑤ 项（system↔task 端口反转）**与**安全配置批次**，均未提交 |

### 0.1 【必须上报】配置文件改动与部署语义（与「无配置文件改动」不符）

`git diff` 显示以下 4 个文件被改动，**它们从根上改变了应用的启动前置条件**：

1. `helloai-start/src/main/resources/application.yml`
   - `spring.profiles.active: local` → **`dev`**（默认 profile 变了）。
   - `spring.datasource.password`、`spring.rabbitmq.password`、`helloai.agent.registration-token`、
     `helloai.security.credential.aes-key-base64`、`helloai.storage.minio-access-key`、
     `helloai.storage.minio-secret-key` —— **一律去掉默认值，改为 `${ENV_VAR}`，缺失即启动失败（fail-fast）**。
2. `application-local.yml`：为 local profile 补回本地 Docker 默认口令（`postgres`/`guest`/`minioadmin`/本地 AES key 等），仅本 profile 生效。
3. `helloai-start/src/test/resources/application-it.yml`：为 IT 补 `rabbitmq.password=guest`、测试专用 AES key / minio 占位 / registration-token。
4. `deploy/app/.env.example`：新增 `MINIO_ACCESS_KEY`、`MINIO_SECRET_KEY`、`HELLOAI_AGENT_REGISTRATION_TOKEN` 三个 `change-me` 占位。

> **部署后果**：dev/prod 若未注入上述 6 个环境变量，**应用会直接启动失败**（这是有意设计，非缺陷）。
> 因此本批的「上线」若随附这些配置改动，**必须先与运维确认目标环境已具备全部环境变量**，否则将造成可用性事故。
> 若本批只提交 Java 结构改造，则**必须把这 4 个配置文件的改动排除出去**（见 §1.2 排除清单），二者不要混在同一笔提交。

---

## 1. 提交范围审查

### 1.1 该进（代码类）

```bash
# 代码类（Java + CI 脚本）——按路径分组提交，便于回溯
E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/agent/doorbell/          # 新增包（7 文件）
E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/agent/event/ExecutionCommandCreatedEvent.java
E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/util/SubTaskDependencyOrder.java
E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/shared/util/SubTaskOutputExtractor.java
E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/system/port/             # ⑤ 端口反转（2 文件）
E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/impl/ArtifactReferencePortAdapter.java
E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/system/storage/impl/ArtifactStorageReconcileServiceImpl.java
E:/yhzx/1027/helloai/helloai-api/src/main/java/com/helloai/api/controller/AgentDoorbellController.java
E:/yhzx/1027/helloai/helloai-job/src/main/java/com/helloai/job/task/                     # 6 任务去 Mapper
E:/yhzx/1027/helloai/helloai-*/src/test/java/...                                        # 测试搬迁/更新（doorbell/AttachmentServiceImplTest/job 6 测试）
E:/yhzx/1027/helloai/scripts/ci/arch-baseline.txt  scripts/ci/check-arch-freeze.sh  scripts/ci/ci-gate.sh
E:/yhzx/1027/helloai/scripts/README.md
```

### 1.2 该进（文档类）

```bash
E:/yhzx/1027/helloai/doc/HelloAI_CODE_STYLE.md
E:/yhzx/1027/helloai/doc/log/2026-10.md                       # 未跟踪，新增
E:/yhzx/1027/helloai/doc/review/HelloAI 代码规范与架构偏离专项审计报告（2026-09-30）.md   # 未跟踪，新增
E:/yhzx/1027/helloai/doc/review/HelloAI 优先级决策分析（V2架构调整 vs 代码质量）.md       # 未跟踪，新增
E:/yhzx/1027/helloai/doc/review/qa-verify-arch-collect-2026-10.md                        # 未跟踪，新增
```

### 1.3 必须排除 / 单独决策

| 路径 | 处置 | 原因 |
|---|---|---|
| `.workbuddy/` | **排除**（已 gitignore） | 本地工作区/记忆缓存 |
| `tools/` | **排除**（已 gitignore） | 本地一次性脚本 |
| `scripts/powershell/executor-config*.json` | **排除**（已 gitignore） | 含真实 apiKey |
| `**/target/`、`*.log`、`data/` | **排除**（已 gitignore） | 构建/运行产物 |
| `helloai-start/src/main/resources/application.yml` 等 4 个配置 | **单独决策** | 见 §0.1；带真实部署语义，勿与结构改造混提 |
| `deploy/app/.env.example`、`deploy/middleware/README.md` | **单独决策** | 同属安全配置批次 |

**验证「该排除的没被误带」**（逐条须返回空）：

```bash
cd E:/yhzx/1027/helloai
git status --short | grep -E '^\s*[AM?]+\s+(\.workbuddy/|tools/|.*/target/|scripts/powershell/executor-config)' && echo '!!! 误带本地文件' || echo 'OK: 无本地文件误带'
git ls-files --others --exclude-standard | grep -E '\.env$|\.env\.local$|apiKey|secret' && echo '!!! 疑似凭据文件未忽略' || echo 'OK: 无未忽略凭据文件'
# 已暂存内容中不得出现真实密钥字面量（对比 .env.example 的 change-me 占位）
git diff --cached | grep -iE 'password|secret|apiKey|token' | grep -v 'change-me' | grep -v '\${' || echo 'OK: 暂存区无明文凭据'
```

### 1.4 那 2 笔「已暂存的删除」如何处理

现状：`git status` 显示 `D  debug-blocked-redispatch-stuck.md`、`D  debug-redispatch-stuck-blocked.md`（已 `git rm`，内容已备份至被忽略的 `.workbuddy/notes/`）。

- **推荐**：随本批一起提交（它们本就是上一批次的有意清理，D 状态已在 index，无需额外动作）。
- **若不想在本批带出去**（取消暂存、文件保持删除态，**不恢复文件**）：
  ```bash
  git restore --staged -- debug-blocked-redispatch-stuck.md debug-redispatch-stuck-blocked.md
  ```
- **若要彻底还原这两个文件**（连同工作区）：
  ```bash
  git restore --staged --worktree -- debug-blocked-redispatch-stuck.md debug-redispatch-stuck-blocked.md
  ```
- ⚠️ 提交前务必确认这 2 个文件确实不是当前业务所需（内容已在 `.workbuddy/notes/` 备份，恢复路径存在）。

### 1.5 提交前快照（回滚要用，只读）

```bash
cd E:/yhzx/1027/helloai
git rev-parse HEAD > /tmp/helloai_head_before.txt          # 记录回滚锚点
git status --short > /tmp/helloai_status_before.txt        # 记录工作区全貌
git diff > /tmp/helloai_unstaged_before.patch              # 未暂存改动快照（含本批）
git diff --cached > /tmp/helloai_staged_before.patch       # 已暂存改动快照
```

---

## 2. 构建 / 测试 / 门禁最终确认

### 2.1 三个易错点（必须逐项满足）

1. **必须 `clean`** —— 否则 `target/surefire-reports` 残留会让「用例数>0」被上一次报告误满足（假绿）。
2. **必须显式 `-DskipTests=false`** —— 根 POM 默认 `<skipTests>true</skipTests>`，漏传会「跑 0 用例却通过」。
3. **用例数按 `<testcase>` 元素计，不读 `<testsuite tests="N">` 属性** —— JUnit5 `@Nested` 用例会漏计约 639 个。

### 2.2 一键门禁（与 CI 同源，推荐）

```bash
cd E:/yhzx/1027/helloai
bash scripts/ci/ci-gate.sh --skip-ui        # --skip-ui 本批前端未改，见 §2.4
# 通过判据：末尾输出「全部通过（0 项失败）」且 exit 0
```

等价的显式命令（便于人工核对）：

```bash
# 门禁1：构建 + 真实单测（7 模块）
mvn -B --no-transfer-progress -DskipTests=false clean test
# 判据：7/7 模块 BUILD SUCCESS；无 FAIL/ERROR

# 门禁2：用例数 > 0（按 <testcase> 计）
find E:/yhzx/1027/helloai -path '*/target/surefire-reports/TEST-*.xml' -type f -exec grep -o '<testcase' {} + | wc -l
# 判据：输出 == 1783（本批实测基线），且 > 0

# 门禁3：架构漂移冻结（团队在实施阶段已测 EXIT=0，提交前复核一遍）
bash scripts/ci/check-arch-freeze.sh
# 判据：全部规则「✅ 持平」或「✅ 改善」，exit 0；重点看 shared->task / shared->agent 均为 0

# 门禁4：前端（本批未改前端，可跳）
# 门禁5：B 级集成 IT（Testcontainers，需 Docker，无 Docker 输出 NOT RUN 不算失败）
```

> ⚠️ 本地同一工作区若有 QA 在跑全量构建，**不要并行执行 `mvn clean`**（会互删 `target/`）。
> 提交前门禁确认建议由**单人串行**执行，或直接以 CI（Gitee Go 自有主机）结果为准。

### 2.3 门禁与 CI 的对应

- `.workflow/helloai-ci.yml`（Gitee Go，trigger：push/PR → `master`）仅薄封装；
- 全部门禁语义在 `scripts/ci/ci-gate.sh`；备援 `.github/workflows/ci.yml`。

### 2.4 前端是否需要跑？

**不需要（本批）**，理由：

```bash
cd E:/yhzx/1027/helloai
git status --short | grep '^..\s*helloai-ui/' || echo 'OK: 本批无 helloai-ui 改动'
```

本批改动全部落在 `helloai-*/src/main/java`、`src/test/java`、`scripts/`、`doc/`，**无 `helloai-ui/` 变更**，故 `npm run type-check && build` 不会被本批影响。可用 `--skip-ui`。
（注意：CI 全量仍会跑前端门禁；本地为省时可跳过，但**不能因此声称前端已验证**。）

---

## 3. 影响面评估

### 3.1 是否需要重启应用？—— **需要**

依据：本批含**包路径变更 + Spring 组件位置迁移 + Service 方法契约新增**，属**编译期 + 运行期装配**变化：

- doorbell 7 主文件从 `core.shared.doorbell` → `core.agent.doorbell`（`@Component/@Service` 位置变了，Bean 名/类由 `scanBasePackages=com.helloai` 重新扫描）；
- `ExecutionCommandCreatedEvent` 换包；
- `SubTaskOutputExtractor.extractExecutionOutput` **方法签名变更**（`SubTask` → `Map<String,Object>`），8 处调用点改为传 `x.getContext()`；
- 6 个 job 定时任务**构造器依赖变更**（去 Mapper、改注入域 Service）；
- 新增 `ArtifactReferencePort` 端口 + Adapter（system 域构造器新增依赖）。

结论：**属进程级变更，必须停旧进程 + 起新进程（整包重启），不支持热替换。**

### 3.2 是否需要清缓存 / 重建？

- **构建期**：必须 `mvn clean` 全量重建（包搬迁会产生新旧 `.class` 混杂，增量编译可能残留旧包 class）。
- **运行期**：**无需**清理 Redis / MinIO / 本地磁盘缓存 —— 本批无缓存键结构变更。
- ⚠️ 仅当**同时**上线 §0.1 的 `credential.aes-key-base64` 轮换时，才有「旧密钥加密的 `credential_vault` 行无法解密」的**数据侧**影响（需重灌，见 §3.4）；纯 Java 结构改造**无此问题**。

### 3.3 DBA / 运维配合

- **纯本批（①②③④⑤ Java 结构改造）**：**无** DBA 动作、**无**运维改配置动作。无 schema 变更、无迁移、无新增端口/依赖。
- **若随附 §0.1 配置批次**：**需要运维配合** —— 目标 dev/prod 必须注入以下 6 个变量（**只列名称，不写值**）：

  | 变量名 | 用途 |
  |---|---|
  | `DATASOURCE_PASSWORD` | PostgreSQL 口令 |
  | `RABBITMQ_PASSWORD` | RabbitMQ 口令 |
  | `HELLOAI_AGENT_REGISTRATION_TOKEN` | Agent 自注册共享 token |
  | `HELLOAI_CREDENTIAL_AES_KEY_BASE64` | 凭据保险箱 AES 密钥 |
  | `MINIO_ACCESS_KEY` | MinIO access key |
  | `MINIO_SECRET_KEY` | MinIO secret key |

  且默认 profile 由 `local` 变 `dev`，需确认 `application-dev.yml` 与目标环境一致。

### 3.4 doorbell 搬迁后「已部署环境」是否有需手工处理的东西？—— **无（运行态）**

- 无残留旧包引用（已全仓 `grep` 验证为 0）；
- 无持久化依赖（doorbell 是内存态 SSE 连接注册表，`DoorbellRegistry` 不落库）；
- 无外部注册（`@MapperScan` 不含 doorbell；无 `spring.factories`、无 SPI 注册）；
- 无配置项改名（doorbell 相关配置键未动）。
- **唯一「手工处理」风险**：已部署实例重启后，**既有 SSE 长连接会断开**，接入方（外部 Agent）需**自动重连**；请确认客户端有重连逻辑（这是任何重启的固有影响，非本批引入的缺陷）。

---

## 4. 回滚预案

> 总原则：**禁 `git reset --hard`、禁 `git checkout .`、禁 `git stash drop`**（都会丢工作区其它未提交改动）。
> 所有回滚动作前，先保留 §1.5 的快照。

### 场景 A：本批改动**全部未提交**（当前状态）→ 最轻量回滚

回滚 = 放弃本批改动，且**不得动其它未提交改动**。

**A-1（推荐，可逆）—— 按路径 stash：**

```bash
cd E:/yhzx/1027/helloai
git stash push --include-untracked -m "rollback-arch-collect-2026-10" -- \
  helloai-core/src/main/java/com/helloai/core/agent/doorbell \
  helloai-core/src/main/java/com/helloai/core/agent/event/ExecutionCommandCreatedEvent.java \
  helloai-core/src/main/java/com/helloai/core/task/util/SubTaskDependencyOrder.java \
  helloai-core/src/main/java/com/helloai/core/shared/util/SubTaskOutputExtractor.java \
  helloai-core/src/main/java/com/helloai/core/system/port \
  helloai-core/src/main/java/com/helloai/core/task/service/impl \
  helloai-core/src/main/java/com/helloai/core/system/storage/impl/ArtifactStorageReconcileServiceImpl.java \
  helloai-api/src/main/java/com/helloai/api/controller/AgentDoorbellController.java \
  helloai-job/src/main/java/com/helloai/job/task \
  scripts/ci
# 注意：stash --include-untracked 只作用于 pathspec 内的路径；其它未提交改动留在工作区
```
回滚后如需恢复本批：`git stash pop`（或 `git stash apply stash@{0}`）。

**A-2（保守）—— 记录 patch 后逐文件还原：**

```bash
git diff > /tmp/rollback_before.patch        # 先备份
# 已跟踪文件恢复到 HEAD（仅这些路径），未跟踪新增文件手工删除
git restore --source=HEAD --worktree -- <§1.1 中的已跟踪路径...>
rm -rf helloai-core/src/main/java/com/helloai/core/agent/doorbell \
       helloai-core/src/main/java/com/helloai/core/agent/event/ExecutionCommandCreatedEvent.java \
       helloai-core/src/main/java/com/helloai/core/task/util/SubTaskDependencyOrder.java \
       helloai-core/src/main/java/com/helloai/core/system/port \
       helloai-core/src/main/java/com/helloai/core/task/service/impl/ArtifactReferencePortAdapter.java \
       helloai-core/src/test/java/com/helloai/core/agent/doorbell
```
⚠️ `rm -rf` 属**危险操作**：仅限上列**本批新增**目录，建议先 `ls` 确认命中再删，**不得扩大路径**。

**验证回滚成功：**
```bash
git status --short | grep 'core.agent.doorbell' || echo 'OK: 新包已回退'
git status --short | grep 'shared/doorbell' && echo 'OK: 旧包恢复' || echo '!! 旧包未恢复'
# 与快照比对差异（应只剩其它批次的改动）
diff <(git status --short) /tmp/helloai_status_before.txt
```

### 场景 B：本批**已提交、未推送** → 回滚该提交，保留工作区

**B-1（推荐）—— revert（生成反向提交，最安全）：**
```bash
git revert --no-commit <本批提交 sha>
git status --short          # 人工确认本次 revert 只动了本批文件
git diff --cached --stat
git commit -m "revert(core): 回滚架构收口批次 <sha>"
```

**B-2（次选）—— `reset --soft`（把提交退回工作区，不丢改动）：**
```bash
git reset --soft HEAD~1      # 仅本批是最后一个提交时；--soft 保留改动到暂存区
git restore --staged -- <不需要的路径>   # 再按需取消暂存
```
注意：`--soft` 只应作用于**本批是 HEAD** 的情形；若本批之前还有他人提交，**改用 revert**。

### 场景 C：本批**已推送** → 只能 revert + push

```bash
git revert --no-commit <本批提交 sha>
git commit -m "revert(core): 回滚架构收口批次 <sha>"
git push origin master
```
⚠️ **禁止** `git push --force` 到 `master`（会重写他人历史）；回滚一律用 revert 正向抵消。

### 各场景耗时与触发条件

| 场景 | 触发条件 | 预计耗时 | 验证方式 |
|---|---|---|---|
| A 未提交 | 尚未 commit，发现门禁/评审不过 | 1~2 min | `git status` 与快照比对 |
| B 已提交未推 | 推送前发现缺陷 | 2~3 min | revert 后重跑 §2.2 门禁 |
| C 已推送 | 线上回归异常 | revert 提交 + 重新部署 5~15 min | 健康检查 + 冒烟（§5） |

---

## 5. 上线后冒烟验证清单（可观测项）

> 前置：`HOST`/`PORT`/`AGENT_API_KEY` 用占位符替换；日志路径以实际部署为准。

### 5.1 启动无 Bean 缺失（doorbell 搬迁风险点）

```bash
# 启动后 60s 内，日志中不得出现装配异常
grep -Ec 'NoSuchBeanDefinitionException|UnsatisfiedDependencyException|BeanCreationException' /path/to/app.log   # 期望 0
grep -E 'Started HelloAIApplication in' /path/to/app.log                                                        # 期望命中 1 行
# 佐证 doorbell 4 组件 + Service 已注册（Spring 扫描日志）
grep -E 'Doorbell(Registry|Ringer|DutyListener|KeepaliveTask|ServiceImpl)' /path/to/app.log | head
```

### 5.2 doorbell 端点可达（路径与鉴权已核实）

- 实际路径：**`GET /api/agents/doorbell/sse`**，`produces=text/event-stream`。
- 鉴权：经 `AuthInterceptor`，需 **`Authorization: Bearer <Agent API Key>`**（从 `_authId` 注入 agentId），落在 `/api/**` 拦截范围。

```bash
# 带合法 Agent Key：期望 HTTP 200 + Content-Type: text/event-stream + 立即收到 connected 握手
curl -N -i -H "Authorization: Bearer {AGENT_API_KEY}" \
  "http://{HOST}:{PORT}/api/agents/doorbell/sse"
# 判据：状态行 200；响应头含 `Content-Type: text/event-stream`；数据流出现 event:connected（或 data 含 connected）

# 无 token 反证：期望 401/403（证明鉴权生效、端点确实存在而非 404）
curl -s -o /dev/null -w '%{http_code}\n' "http://{HOST}:{PORT}/api/agents/doorbell/sse"
```

### 5.3 6 个 job 定时任务正常触发且无 SQL 异常

| 任务 | 触发周期（已核实） | 观察窗口 |
|---|---|---|
| `AgentHealthCheckTask` | 60s | ≥2 周期 |
| `AssignedSubTaskTimeoutTask` | 30s | ≥2 周期 |
| `ExecutionCompensationTask` | 30s | ≥2 周期 |
| `PlanningTimeoutTask` | 30s | ≥2 周期 |
| `ExternalAgentFallbackTask` | 60s（可配 `helloai.dispatch.fallback.scan-interval-ms`，起始延迟 30s） | ≥2 周期 |
| `SubTaskPendingOrphanTask` | 60s（可配 `helloai.execution.pending-orphan-scan-interval-ms`） | ≥2 周期 |

```bash
# 每个任务名都应出现执行日志
for t in AgentHealthCheckTask AssignedSubTaskTimeoutTask ExecutionCompensationTask \
         ExternalAgentFallbackTask PlanningTimeoutTask SubTaskPendingOrphanTask; do
  echo -n "$t: "; grep -c "$t" /path/to/app.log
done
# SQL/持久层异常（去 Mapper 直连后重点看这里）——期望 0
grep -Ec 'BadSqlGrammarException|PSQLException|MyBatisSystemException|org.postgresql.util' /path/to/app.log
```

### 5.4 附件上传感知路径正常（`SubTaskOutputExtractor` 改签名影响面）

- 端点：`POST /api/artifacts/upload`（`multipart/form-data`）。
- 影响链：`SubTaskOutputExtractor.extractExecutionOutput(Map)` 现接收 `subTask.getContext()`，被
  `AgentRuntimeContextAssembler` / `McpToolServiceImpl` / `ReviewEvidenceAssembler` /
  `SubTaskCompletionListener` / `TaskDeliverableServiceImpl` / `TaskFinalReportServiceImpl` /
  `TaskIterationServiceImpl` 共 8 处消费。

```bash
# 1) 上传一个产物，记录返回的 artifact/attachment id
curl -s -H "Authorization: Bearer {AGENT_API_KEY}" \
  -F "file=@/tmp/smoke.txt" "http://{HOST}:{PORT}/api/artifacts/upload"
# 2) 触发/完成一个带产出的子任务后，检查产出读取非空：
#    - 去 DB 或调用交付物/最终报告接口，确认 context.lastExecution.output 能被读出（非空字符串）
#    - 判据：最终报告 / 交付物中能看到该产物内容片段；日志无 extract 相关 NPE
grep -Ec 'extractExecutionOutput' /path/to/app.log   # 仅佐证被调用；真正的判据是产出内容非空
```

### 5.5 版本确认

```bash
curl -s "http://{HOST}:{PORT}/api/health"                 # 期望 R.ok，body 含 "status":"ok"
curl -s "http://{HOST}:{PORT}/api/health/getExecutionMode" # 回显 enabled/mockMode/provider/model，确认与目标环境一致
```

### 5.6 观察窗口

- 连续观察日志 **≥5 分钟**：无异常堆栈（`ERROR`/`Exception`），6 个任务各触发 ≥2 次，doorbell 连接建立/断开无报错。

---

## 6. 风险登记（不影响本次上线，记录在案）

| # | 风险项 | 证据/来源 | 建议 |
|---|---|---|---|
| R1 | **配置文件改动与「无配置改动」陈述不符** | §0.1 | 立即向 team-lead 澄清：这 4 个文件是否属本批；若属，部署前必须落实 6 个环境变量 |
| R2 | **默认 profile `local→dev`** | `application.yml` diff | 确认目标环境 `application-dev.yml` 与预期一致，避免连错库 |
| R3 | 新增 Service 薄委托方法**未补专属单测** | `AgentService`/`SubTaskService`/`TaskService`/`AgentExecutionRecordService` 新增方法（`listStaleSince`/`markOfflineIfStale`/`physicalDeleteByTaskId`/`listPausedBefore`/`incrementExternalFallbackCount` 等） | 后续补齐方法级单测；当前靠 job 测试间接覆盖 |
| R4 | `ArtifactReferencePortAdapter`（⑤ 端口反转）**未在原 4 项清单中** | 未跟踪文件 `system/port/*`、`task/service/impl/ArtifactReferencePortAdapter.java` | 明确其是否纳入本批提交；补 `ArtifactReferencePortAdapterTest` 覆盖确认 |
| R5 | `check-arch-freeze.sh`/`ci-gate.sh` 注释中门禁编号不一致（一处写「门禁 4」，实际为门禁 3） | 脚本头部注释 | 文档性瑕疵，后续修正注释 |
| R6 | 归档文档可能残留过期包名 `com.helloai.core.shared.doorbell` | 未逐篇扫描 `doc/archive/**` | 在文档整理批次中全量 grep 订正 |
| R7 | 2 笔 `git rm` 删除的文档与 `qa-verify-arch-collect-2026-10.md` 等新增文档同批 | `git status` | 提交时按「代码类 / 文档类」拆分，便于回溯 |
| R8 | 本地与 CI 并行构建会争抢 `target/` | 团队已知 | 提交门禁由单人串行执行，或直接采信 CI 结果 |

---

## 附：一句话执行顺序

```
① 读 §0 订正事实 → 与 team-lead 确认配置改动去留
② 存快照（§1.5）→ 审查提交范围（§1.1~1.4）
③ 跑门禁（§2.2，注意 clean / -DskipTests=false / <testcase>）
④ 提交（代码类、文档类分批）→ 推送
⑤ 部署（整包重启，§3.1）→ 冒烟（§5）→ 观察 ≥5 分钟
⑥ 异常时按 §4 对应场景回滚（禁 reset --hard / 禁 force push）
```
