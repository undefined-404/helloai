# scripts/ 索引

本目录是 HelloAI 的验证与运维脚本库。共 **82 个 PowerShell（powershell/ 81 + 根目录 run-it-local.ps1）+ 28 个 Shell + 1 个 Java 工具 + 1 个 SQL**（2026-09-30 复核计数，09-28 盘点基线 77/24），另有 **4 个 CI 门禁脚本 + 1 个架构冻结基线**（2026-09-29 新增）。

- `ci/`：**跨平台 CI 门禁与架构守卫**（bash，Git Bash / Linux CI 通用；详细见下方第六节）
- `powershell/`：Windows 侧（pwsh / Windows PowerShell 5.1），含全部规范类与大部分 E2E 验收脚本
- `shell/`：macOS/Linux 侧（zsh/bash），E2E 验收为主 + 少量构建运维
- `powershell/tools/`：脚本共享工具（PgExec.java，JDBC 通道，供 verify-reviewer-dual 等在无 docker psql 时执行 SQL）
- `sql/`：一次性数据回填 SQL
- `shell/.tmp/`：E2E 运行期日志与临时产物，已被根 `.gitignore`（`.tmp/`）覆盖，不入库
- `powershell/logs/`：脚本运行期日志与 jar 解包残留，已由根 `.gitignore`（`scripts/powershell/logs/`）覆盖，不入库

> **脚本头注释里的「规则 N」**（如「规则 6：脚本 UTF-8 编码 + PS 5.1 单引号 + 拼接」）出自**本地 preflight 技能**
>（`.agents/` / `.qoder/` 下的 `helloai-preflight`，属本地产物、不入仓 2026-09-30）——仓库内没有对应文件，
> 按注释所述纪律执行即可，不必去找文件。2026-09-30 治理时已把脚本头里的死路径替换为「本地 preflight 技能」字样。

## 一、验收脚本（verify-*，57 ps1 + 19 sh）

命名即入口：`powershell -File .\scripts\powershell\<name>.ps1` / `zsh scripts/shell/<name>.sh`。
绝大多数前置为「后端 6565 已启动 + admin/admin123」，个别需 Docker 中间件（postgres:15432 / redis:26379 / rabbitmq:25672 / minio:29000）。

### 双平台对实现（ps1 与 sh 各有对应版本）

| 领域 | ps1 | sh |
|---|---|---|
| C3 灰度六件套（env/events/reconcile/rollback/route/seed） | verify-c3-*.ps1 | verify-c3-*.sh（共享库 c3-common.sh） |
| AgentHub 值班 P0/P1 | verify-agenthub-duty-e2e.ps1 | verify-agenthub-duty-e2e.sh |
| Dashboard 值班概览 | verify-dashboard-duty-leases.ps1 | verify-dashboard-duty-leases.sh |
| MCP 鉴权 / 业务闭环 | verify-mcp-auth.ps1 / verify-mcp-e2e.ps1 | 同名 .sh |
| MinIO 附件链路 | verify-minio-artifact.ps1 | verify-minio-artifact.sh |
| Planner 自动拆解 | verify-planner-decompose.ps1 | verify-planner-decompose.sh |
| C3 Step4 灰度观察 | watch-step4.ps1 | watch-step4.sh |

### 仅 ps1（Windows 侧）

- **规范红线**：verify-dependency-direction（依赖方向）、verify-code-style-p0-layer（Controller 分层）、verify-code-style-p1-paths（路径命名）、verify-code-style-p1-ui-sync（前后端路径同步）、verify-contract-first（契约先行）、verify-tool-matrix（工具面一致）
- **任务/拆解/澄清**：verify-a1-task-policy、verify-a2-skill-derive、verify-a3b-agent-edit-skills、verify-674-remove-specialization、verify-requirement-clarify-structured、verify-step9b-depends-on、verify-task-running-spec-phase-b、verify-inner-loop-e2e、verify-conversation-flow-e2e、verify-e2e-batch-a、verify-m5-scenarios、verify-g014
- **执行/调度**：verify-execution-dispatch-guard（启动期 fail-fast 守卫）、verify-poller-e2e（DB Poller）、verify-subtask-redispatch-auto-execution（重派自动执行）、verify-subtask-deadletter（死信兜底）、verify-agent-execution-preview、verify-agent-llm-connectivity（真 LLM 冒烟）、verify-single-track-e2e（内部单轨：计划/执行/评审/报告全绿）
- **审查/质量**：verify-reviewer-dual（双评审）、verify-quality-profile、verify-quality-dashboard、verify-artifact-content-review、verify-llm-conversation-stream
- **Agent/技能/配置**：verify-agent-skill-capability、verify-skill-packages、verify-platform-config、verify-api-key-verify、verify-llm-provider-models、verify-admin-authz、verify-attachment-version
- **MCP/门铃/外部 Agent 入职**：verify-mcp（最小连通）、verify-mcp-session-e2e、verify-doorbell-e2e、verify-onboarding（+ -doorbell / -heartbeat / -pull / -submit 五步链）、verify-external-agent-e2e（外部 CLI_CLIENT 多 Agent 实接单，含 -AssertOnly 断言模式）
- **MQ**：verify-outbox-relay-confirm-e2e（Outbox/Confirm 失败路径，配合 start-sb-e2e-mq.ps1）

### 仅 sh（macOS/Linux 侧）

verify-login-e2e、verify-requirement-clarify、verify-websearch-e2e、verify-planner-chat-dual-mode、verify-deps-context-e2e、verify-external-executor-e2e、verify-redispatch-in-progress（另有与 ps1 对实现的 12 个见上表）

## 二、运维启停

| 脚本 | 用途 |
|---|---|
| powershell/start-sb.ps1 | 启动后端（jar 新于 5min 跳过重建；日志 spring-boot-run.log，PID 写 .spring-boot-pid） |
| powershell/start-sb-e2e-mq.ps1 | 以 dispatch-mode=BOTH + 双开关启动，供 MQ E2E 使用 |
| powershell/restart-sb-mock.ps1 | 以 mock 执行模式重启（poller-e2e 等需要） |
| powershell/kill-old.ps1 | 释放 6565 端口（重启前调用） |
| powershell/clean-minio-bucket.ps1 | MinIO 桶清理（清库前置，P-1 批次 A4）：默认 dry-run 列举统计，`-Execute` 真实批量删除（DeleteObjects ≤1000/批，删后复核），`-Prefix` 缩小范围；纯 PowerShell SigV4 零依赖，`-Endpoint` 支持本地 29000 / dev 共享 39.106.204.43:29000 |
| powershell/run-redispatch-diagnose.ps1 | 一键编排「重启 → 等就绪 → 跑重派验收 → 打印排障 SQL」 |
| shell/build-all.sh | 全模块编译打包（JDK17 + IntelliJ 内置 Maven，跳过测试） |
| shell/run-core-tests.sh | 运行 helloai-core 指定单测 |
| shell/stop-helloai.sh | 停止后端（macOS/Linux） |

## 三、外部 Agent 值班/守护（powershell/）

- executor-onboard.ps1：外部 EXECUTOR 一键自助注册（生成 executor-config.json）
- executor-duty.ps1 / executor-api.ps1：值班动作轮询 / REST 业务端点调用
- outer-trae-daemon.ps1、qoder-ceshi-daemon.ps1：特定外部 Agent（trae / qoder）常驻值班守护
- qoder-ceshi-checkin.ps1 / -doorbell.ps1 / -poll.ps1：qoder-ceshi 的 checkIn / 门铃 SSE / REST 轮询
- mcp-sse-keepalive.ps1：MCP SSE 长连接保持

## 四、一次性/调试工具

| 脚本 | 用途 | 备注 |
|---|---|---|
| powershell/assemble-manual.ps1 | 从已审章节确定性装配 executor-duty 手册（字节级幂等） | 被 doc/manual/executor-duty/06 引用，保留 |
| powershell/backfill-task-iterations.ps1 | V42 历史任务迭代记录回填（调 POST /api/tasks/backfillTaskIterations） | 一次性 |
| powershell/count-testcases.ps1 | 统计 surefire XML testcase 数 | 小工具 |
| powershell/wait-verdict.ps1 | 等指定子任务审查结论 | **一次性调试**：含硬编码 subTaskId `2097935069198065670`，复用需改 ID |
| powershell/watch-step4.ps1 / shell/watch-step4.sh | C3 Step4 全量灰度持续观察 | 阶段性 |
| sql/backfill-timeout-reassign-timeline.sql | 超时重派 timeline 回填 | 一次性 |

## 五、已知问题与维护约定

1. **硬编码 Windows 路径**：`start-sb.ps1`、`verify-code-style-p0-layer.ps1` 等含 `e:\yhzx\1027\helloai` 绝对路径，换机需改（历史遗留，列为后续治理项）。
2. **平台不对称**：验收资产 ps1 远多于 sh，macOS/Linux 贡献者无法完整复现验收流程（评审 6.5）。**新增验收口径优先下沉为 JUnit / ArchUnit（随 `mvn test` 跨平台执行），ps1/sh 只做薄封装**；跑 Maven 测试必须显式 `-DskipTests=false`（根 pom 打包默认跳过）。
3. **2026-09-28 整理记录**：
   - 移除误入的外项目脚本 `powershell/下单支付E2E验收.ps1`（Trade Cloud 电商验收，gateway :8299，与本仓库零关联；如需可从 git 历史恢复）。
   - 修复 `verify-code-style-p0-layer.ps1` 对已删除的 `tmp\package-backend.ps1` / `tmp\kill-backend.ps1` / `tmp\wait-backend.ps1` 的三处坏引用（内联等价实现，未经 pwsh 实跑）。

## 六、CI 门禁（ci/，2026-09-29 新增）

背景：2026-09-29 架构与质量审计发现本仓库**无任何 CI**，且根 POM 默认 `skipTests=true`，导致「单测全绿」全靠人工执行、且极易得到「0 用例通过」的假绿结论。本节脚本把验证变成可自动执行、不可绕过的门禁。

| 脚本 | 用途 |
|---|---|
| `ci/ci-gate.sh` | **主门禁**：固定可用 JDK → 构建+真实单测（显式 `-DskipTests=false`）→ 断言「用例数 > 0」→ 架构漂移冻结 → 前端 type-check/build。**用例数计数口径（2026-10-01 修正）**：按 surefire XML 内的 `<testcase>` 元素计，**不可**读 `<testsuite tests="N">` 属性——JUnit5 `@Nested` 用例会被写进外层类 XML，但该文件 `tests` 属性仍为 0（实测 `AgentProviderResolverTest.xml`：`tests="0"` 而 `<testcase>` 12 个），属性口径会使本仓库漏计约 **639** 个用例（1144 vs 真实 1783） |
| `ci/lib-jdk.sh` | JDK 解析库：优先 `$HELLOAI_JAVA_HOME` → `$JAVA_HOME` → 探测 `~/.jdks/*`；**黑名单排除 ms-17.0.19**（本机必然 JVM 崩溃）；并**实测 `java -version` 大版本必须为 17**（防 Gitee 自有主机上 Agent 自带的 JDK 8 被误用） |
| `ci/check-arch-freeze.sh` | 跨域依赖守卫。**2026-10-02 起规则表改为「生成式」：20 条手写 → 70 条按依赖链生成**，并补齐三类盲区（A1 组 2 由 4 条硬编组合扩为 6 域全部 30 个有序域对；A2 扫描范围由 `helloai-core` 扩至 `helloai-api`/`job`/`mq`/`start`；A3 新增「前向跨域实体泄漏」计数）。**分两档**：组 1/2（反向依赖 + 跨域 Mapper）「只降不升」→ **硬拦截**；组 3（前向依赖 + 前向实体泄漏）→ **仅提示不拦截**。`--update-baseline` 刷新冻结基线，`--verbose` 打印明细。**性能**：每目录只全树抽一次 import 行 + 一次 awk 完成全部计数（约 11 个子进程），勿退回「每条规则一次 grep」（本机单次 grep ≈ 300ms，70 条规则会慢到 2 分钟以上） |
| `ci/arch-baseline.txt` | 冻结基线（**70 条**规则的计数快照，自动生成，勿手改数字；按组分段注释，组 3 段标注「仅提示」） |
| `ci/host-prepare.sh` | **自有主机侧准备与自检**（Gitee Go 主机组跑 CI 时用）：默认只检测；`--install` 装 JDK17/Maven/Node20；`--swap 2G` 给 4G 内存机器建 swap |

常用命令：

```bash
bash scripts/ci/ci-gate.sh                    # 全量，等价于 CI
bash scripts/ci/ci-gate.sh --quick --skip-ui  # 本地快速自检
bash scripts/ci/check-arch-freeze.sh          # 只跑架构冻结校验
bash scripts/ci/host-prepare.sh               # 自有主机自检（只读，不改系统）
```

平台接入：`.workflow/helloai-ci.yml`（Gitee Go，对应本仓库远程）与 `.github/workflows/ci.yml`（GitHub Actions 备份）。**平台配置只是薄封装**——门禁语义全在上表的脚本里，因此本地执行同一脚本即可复现 CI 结论（这正是 §5.2 约定「新增验收口径优先下沉为可跨平台执行的形式」的落地）。

**架构红线门禁的两层分工（2026-09-30 明确）**

| 层 | 脚本 | 语义 | 是否进 CI |
|---|---|---|---|
| 冻结层（增量） | `ci/check-arch-freeze.sh`（bash，跨平台） | **§6 全量 + §7.1 + 前向实体泄漏**，**70 条规则（生成式）分两档**：**组 1/2（反向依赖 + 跨域 Mapper）「只降不升」硬拦截**（组 1 现已全部归零，故 51 条 block 规则均为 0 值回归护栏）；**组 3（前向依赖 `planner->agent`/`task->agent` + 前向实体泄漏 19 条）仅提示**——前向是 §6 合法方向，也是「端口反转」的承载方向；实体泄漏不拦截但**记数暴露**，使其与反向依赖一样「看得见才可能被清偿」。**扫描范围**：core 六域 + `helloai-api`/`job`/`mq`/`start` | ✅ 门禁 3 |
| 断言层（存量/命名） | `powershell/verify-dependency-direction.ps1` | 严格断言「应为 0」的红线（2026-10-01 起 `system->task` 已端口反转清零，**亦为严格 0**，不再有 `[DEBT]` 豁免）+ **`@MapperScan` 注册守卫**（漏注册包 → 启动失败） | ❌ 本地/评审辅助 |

```bash
# 两层都跑（冻结层跨平台可进 CI；断言层需 Windows PowerShell）
bash scripts/ci/check-arch-freeze.sh
powershell -File scripts/powershell/verify-dependency-direction.ps1
```


**Gitee Go 的两种执行形态（2026-09-29 补充）**

| 形态 | 插件 | 消耗额度 | 环境 |
|---|---|---|---|
| 云端构建机（原方案） | `build@maven` / `build@nodejs` | **消耗核分**（= 运行分钟 × CPU 核数） | 开箱即用（CentOS 8.3 基础镜像 + 阿里源） |
| 自有主机组（现方案） | `shell@agent` + `hostGroupID` | **不消耗核分** | 需自备 JDK17/Maven/Node20（用 `host-prepare.sh` 一键自检/安装） |

依据：Gitee 官方计费规则「仅当您使用 Gitee 提供的云端构建资源，且流水线中的任务属于计费模型时，任务运行才会消耗核分」。回退到云端形态：`git show b02dce3:.workflow/helloai-ci.yml > .workflow/helloai-ci.yml`。

**尚未覆盖（后续项）**：本门禁只做「可编译 + 单测 + 依赖方向增量 + 前端」四类校验；**B 级集成测试已同日落地并与门禁 5 一起挂接**（详见下方第七节与 §0.3），已在本机 Docker 实跑**全绿**（2026-10-05：**7 个 IT 类 / 27 用例**；`run-it-local.ps1` 一键复跑）；E2E 仍依赖需 Docker 的 ps1/sh 脚本（审计建议 #6，待排期）。

## 七、本地 B 级集成测试一键跑（scripts/run-it-local.ps1，2026-09-29 新增）

本机 Docker 上实跑 Testcontainers B 级 IT（B1 Flyway 全量 apply / B2 MQ 幂等 / B3 Outbox 事务边界 / B4 状态机 CAS / TaskAgentMember Phase B / TaskRunningSpec Phase B / SubTaskBackoffClock Phase B，共 **7 类 27 用例**）的一条命令入口，封装三前置：会话级 PATH 清洗（剔除注册表坏项/残片，**不代改注册表**）、`DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`（新版 Docker Desktop 强制 Host 头，须配 testcontainers 2.0.5 / docker-java 4.x）、`JAVA_HOME=ms-17.0.20.1`（17.0.19 必崩，见 ci/lib-jdk.sh 黑名单），再执行 `mvn -s .tmp\settings-aliyun.xml -pl helloai-start -am test -DskipTests=false -Dtest=*IT`。

```powershell
powershell -File .\scripts\run-it-local.ps1
```

- **输出**：完整日志 `.tmp/it-run-local.log`（UTF-16，Select-String 可自动识别）；控制台汇总各 IT 类的 Tests run 行（当前 7 类）+ 聚合行；退出码 0 = 全绿。
- **退出码**：2 = mvn 缺失 / 3 = docker CLI 缺失 / 4 = Docker 引擎不可达 / 5 = JDK 缺失 / 1 = IT 失败。
- **坏项提示**：脚本开头检测系统注册表 PATH 中的「以空格结尾的坏条目」与「相对路径残片」——本机实测 `C:\Program `（坏条目）与 `iles\Docker\Docker\resources\bin`（残片）是 Docker CLI 完整路径被分号劈开的两半，脚本给出合并修复命令，但仅会话级绕过、不代改注册表。
- **编码注意**：PS5.1 下 mvn(java) 输出为 GBK 字节流，脚本临时按 GBK(936) 解码保证日志与汇总中文正确（含 PS5.1 两个经典坑的规避：Stop 模式 stderr 会变 NativeCommandError、Tee-Object 无 -Encoding）。
