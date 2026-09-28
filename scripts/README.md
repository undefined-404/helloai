# scripts/ 索引

本目录是 HelloAI 的验证与运维脚本库。共 **76 个 PowerShell + 24 个 Shell + 1 个 Java 工具 + 1 个 SQL**（2026-09-28 盘点）。

- `powershell/`：Windows 侧（pwsh / Windows PowerShell 5.1），含全部规范类与大部分 E2E 验收脚本
- `shell/`：macOS/Linux 侧（zsh/bash），E2E 验收为主 + 少量构建运维
- `powershell/tools/`：脚本共享工具（PgExec.java，JDBC 通道，供 verify-reviewer-dual 等在无 docker psql 时执行 SQL）
- `sql/`：一次性数据回填 SQL
- `shell/.tmp/`：E2E 运行期日志与临时产物，已被根 `.gitignore`（`.tmp/`）覆盖，不入库

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
- **执行/调度**：verify-execution-dispatch-guard（启动期 fail-fast 守卫）、verify-poller-e2e（DB Poller）、verify-subtask-redispatch-auto-execution（重派自动执行）、verify-subtask-deadletter（死信兜底）、verify-agent-execution-preview、verify-agent-llm-connectivity（真 LLM 冒烟）
- **审查/质量**：verify-reviewer-dual（双评审）、verify-quality-profile、verify-quality-dashboard、verify-artifact-content-review、verify-llm-conversation-stream
- **Agent/技能/配置**：verify-agent-skill-capability、verify-skill-packages、verify-platform-config、verify-api-key-verify、verify-llm-provider-models、verify-admin-authz、verify-attachment-version
- **MCP/门铃/外部 Agent 入职**：verify-mcp（最小连通）、verify-mcp-session-e2e、verify-doorbell-e2e、verify-onboarding（+ -doorbell / -heartbeat / -pull / -submit 五步链）
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
