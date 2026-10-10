# HelloAI 项目基线文档

> **状态：CURRENT / FACT**
>
> 本文档只描述当前真实代码与已落地能力，不描述未来愿景。
>
> 最后更新：2026-10-09（**§8 Event 基线订正**：Fork「消费面已落地」与代码实测不符 → 改为「仅快照服务、**无消费方**；入口 / 原 Run 冻结 / 驱动执行已于 2026-10-09 裁定 **WONTFIX**」，依据 `D-2026-10-09-5`；**§7 Runtime 基线补 REF-1.3 工具注册两个语义位 + 不可关闭清单**；**§9 Skill 基线补元数据事实源迁移**（REF-1.1/1.2，md frontmatter + 目录扫描））

# 1. 当前项目定位

HelloAI 当前是一个面向**异构 AI Agent 的分布式任务编排与执行平台**。

当前主要解决：

```text
用户需求
  ↓
Planner / Requirement Clarify
  ↓
Task / SubTask
  ↓
Workflow / DAG
  ↓
Scheduler
  ↓
Agent Assignment
  ↓
Execution Command
  ↓
Agent 执行
  ↓
Result Handler
  ↓
Review / Rework
  ↓
状态收敛
```

当前平台已经具备多 Agent、异步执行、可靠性治理和执行环境抽象的基础，但**不要将当前系统描述成已经完整实现 DeepSeek Harness**。

# 2. 当前技术基线

| 组件 | 基线 |
|---|---|
| JDK | 17 |
| Spring Boot | 3.4.x |
| Spring AI | 1.1.x |
| PostgreSQL | 16 |
| Redis | 7.x |
| RabbitMQ | 3.12+ |
| Flyway | 10.x |
| MyBatis-Plus | 3.5.x |
| Frontend | Vue 3 + TypeScript + Element Plus |

# 3. 当前核心模块

```text
helloai-common
helloai-core
helloai-job
helloai-mq
helloai-api
helloai-start
```

# 4. 当前核心领域对象

```text
Task
SubTask
Agent
AgentTask（skills：契约层技能注入载体，空列表不注入，行为零变化）
ExecutionCommand
AgentExecutionRecord
Review
Conversation
Artifact
AgentEvent
AgentSession
WorkflowTemplate
Team
Credential
```

# 5. 当前执行链路

```text
                     Planner
                        │
                        ▼
                 Task / SubTask
                        │
                        ▼
                    Scheduler
                        │
                        ▼
                ExecutionCommand
                        │
                 ┌──────┴──────┐
                 ▼             ▼
              RabbitMQ       DB Poller
                 │             │
                 └──────┬──────┘
                        ▼
                 Execution Layer
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
       Platform LLM   External CLI   MCP/Agent
          │             │             │
          └─────────────┼─────────────┘
                        ▼
                  Result Handler
                        │
                        ▼
                  State Convergence
                        │
                        ▼
                     Reviewer
                        │
                  ┌─────┴─────┐
                  ▼           ▼
                PASS        REWORK
```

最终报告链路：

```text
生成：出纲（Outline Prompt）→ 成文（Report Prompt）→ 审查(Review Prompt)，至多 3 次 LLM 调用
降级：出纲失败 → 单次调用（现状兜底）；审查可配置开关关闭（autoFinalReportReviewEnabled=false 时写回直接 DONE）
读取口径：物化附件优先（UTF-8 正文）→ ExecutionRecord SUMMARY/DELIVERABLES 注入 → output 兜底
限额单源：AttachmentContentPolicy（shared/util，附件限额 + 文本/媒体族判定）
状态机：NONE → GENERATING → REVIEWING → DONE（/ FAILED）；REVIEWING 为异步审查在途态，
        审查链全部出口（pass / unparseable / LLM failed / max_review / skipped / 开关关闭 / L3 超时兜底）收敛 DONE；
        迁移合法性由 FinalReportStateMachine 显式迁移表校验，写口收口 TaskService.transitFinalReportStatus / convergeFinalReportToDone
审查异步：FinalReportReviewServiceImpl（@TransactionalEventListener(AFTER_COMMIT) 入口）+ reportReviewExecutor
        专用池（core1/max2/queue50/AbortPolicy）执行；事件携带 reportTime（微秒截断）作三重陈旧守卫
        （审查前 / rework 前 / 收敛条件写回）；入口 Redisson 防双审锁 + status != REVIEWING 幂等跳过
三级容错：L1 内存事件（AFTER_COMMIT）+ L2 Outbox（FinalReportPersistService 写回与 agent_outbox_event 同事务 →
        AgentEventCompensationTask 补投 → helloai.report-review.queue → MqFinalReportReviewConsumer 幂等消费）+
        L3 FinalReportReviewOrphanTask 巡检（REVIEWING 超阈值收敛 DONE，刻意不重投审查避免无界烧 LLM）
轮次：无状态——generate 恒为 1，同轮返工由审查服务显式传 attempt+1（不落库）
版本：V95 单槽列 final_report_prev(_agent_id/_time)——覆盖前现值落槽；
        POST /api/tasks/rollbackFinalReportByTaskId/{id}：current↔prev 整体互换可反复切换，无 prev 409；
        事件 task_final_report_rolled_back
```

# 6. 当前可靠性能力

当前已经形成的基础能力包括：

- DB 状态中心；
- ExecutionCommand；
- Outbox；
- RabbitMQ 主链 + DB Poller 兜底；
- 幂等保护；
- 分布式锁；
- Lease / Heartbeat / Reconcile；
- Retry / Timeout / Compensation；
- DLQ / 死信台账；
- Agent 在线状态治理（ACTIVE 值班租约作为一等存活证据——心跳过期不直接判 OFFLINE，避免在岗 Agent 被误判离线触发在飞任务重派；`renewLease` 到期时刻同租约内单调不减）；
- 外部 Agent 通道与内部分发链约束同口径（`claimSubTask` 复用 `isReady` 依赖门禁、`listAvailable` 就绪过滤；新增 MCP `startSubTask` 打通 REWORK 返工出口；`SubTaskDetail` 内联产出附件与贡献者，产物可发现）；
- 失败重派和结果收敛；
- 报告审查链三级容错（L1 `@TransactionalEventListener(AFTER_COMMIT)` 内存事件 + L2 Outbox（报告写回与 `agent_outbox_event` 同事务 → `AgentEventCompensationTask` 补投 → `helloai.report-review.queue` → `MqFinalReportReviewConsumer` 幂等消费）+ L3 `FinalReportReviewOrphanTask` 巡检（`REVIEWING` 超阈值收敛 `DONE`，刻意不重投审查）；写口收口 `FinalReportStateMachine` 显式迁移表 + `TaskService.transitFinalReportStatus` / `convergeFinalReportToDone`；入口 Redisson 防双审锁）；
- 产物存储一致性（ArtifactStorage 抽象 + Composite 路由（local/minio 双实现、按 type/URL 前缀分派）+ `AttachmentServiceImpl.register` 前置校验（validateAddress + 存在性，不存在 400 拒绝，把预览期 500 提前成登记期 400）；`ArtifactStorageReconcileTask` 6h ShedLock 只读对账（attachment 全量含逻辑删除 ↔ 桶内对象双向比对，悬空/孤儿/字节不符三态）；孤儿清理默认关闭，三重保险——开关 + 24h 时间窗 + 单轮上限 200）。

- 平台备份 / 恢复（备份编排：`pg_dump -Fc` 全库 + MinIO 对象清单递归枚举 + manifest 前置 peek（不解档即可判内容）+ Redisson 单飞锁 + 仅淘汰自动备份，落**独立桶** `helloai-backups`（不参与产物对账）+ `platform_backup` 台账（`V105`）；恢复侧三门（跨引擎拒 / schema 高过运行时拒 / 在线拒）+ 管理端 preflight（只读）与 restore 端点；生产镜像内置 `pg_dump` / `pg_restore` 客户端。**诚实边界**：对象**本体**不在备份内（只有 `key` + `size` 清单）；运行中备份不比多文件同一瞬间。停机恢复流程见 `doc/manual/platform-backup-restore/runbook.md`）。

- 附件与任务域**读权限单一判据**（`AttachmentVisibilityPolicy`：`TASK` 可见范围 × **根任务 Task-Team 成员关系**，含 derive-on-miss 兜底；上传者恒可读自传 ⇒ 被改派换下者仍能读自己旧产出）；读侧通道**同源**——附件端点（`/api/attachments/**`）与子任务视图端点（时间线 / 对话流 / 详情）统一经该判据（视图侧收口 `SubTaskViewGuard`），其中详情端点 **此前无校验**的缺口已关闭；被注入截断的前置附件在标注行给出可寻址 `id=<attachmentId>`，配合既有 `downloadById` 端点可按需回取全文。

这些属于 HelloAI 的**分布式编排与可靠性基础设施**。

# 7. 当前 Runtime 基线

当前已经存在：

```text
AgentContext
AgentRuntime
AgentExecutionResult
ExecutionEnvironment
ExecutionEnvironmentProvider
ToolExecutor
ToolExecutionResult
AgentLoop
AgentLoopInput
AgentLoopResult
SandboxProvider
SandboxContext
Sandbox
ExecutionPolicy
RuntimeTurnExecutor          ← 唯一 AgentRuntime 实现
```

当前含义：

- `AgentRuntime` 已成为统一执行契约，且**实现层已唯一化**；
- **单轨硬切（G-002）**：`LegacyExecutorAdapter` / `RuntimeAgentRuntimeRouter` / `TurnLlmCaller` / `TurnLlmCallContext` 与 `runtime-enabled` / `v2-enabled` / `gray-percent` 开关**已全部删除**——不再存在双轨，也不再存在配置级回退开关（回退手段为 `git revert`）。消费链统一由 `LocalExecutionCommandConsumer` 分层编排（startIfNeeded → markRunning CAS → `AgentRuntimeContextAssembler` 装配 → `AgentRuntime#execute` → afterTurn → 终态 CAS → `ExecutionResultHandler` 回写）；
- `RuntimeTurnExecutor` 是**唯一** `AgentRuntime` 实现（`agentRuntimes.get(0)` 直取，无路由、无排序、无开关）；
- `ToolExecutor` 已具备执行回路真身（懒加载 spring-ai ToolCallback 目录按名调用，与 ToolRegistry 元数据面同源同构；未知工具 / 空参 / 执行异常 best-effort 返回失败不抛）；
- `AgentLoop` 已具备手动工具循环真身（`runtime/loop`：ChatModel 契约 + ToolExecutor 执行 + TOOL_CALL 事件，`internalToolExecutionEnabled=false` 由循环接管工具执行，maxIterations 硬上限防死循环）；**每轮 iteration 边界经 `LoopCheckpointListener` 落 `agent_session.snapshot.loop`**（同层恢复 checkpoint，零 DDL），`tokenUsage` 逐轮累加并落 `agent_execution_record.token_usage`（V97）；
- `RuntimeTurnExecutor` 组装 `AgentLoop` / `ToolExecutor` / `ToolRegistry` / `AgentSkillSpecService` / `SandboxProvider`；prompt / chatModel / 会话 / 对话流由 `AgentRuntimeContextAssembler` 装配后注入。
- **工具注册两个语义位（2026-10-09 起）**：`ToolRegistry.resolve(names, ToolContext)` 返回「**生效形态**」，其结果是**模型可见工具的唯一判据**（`resolveVisibleCallbacks` → `AgentLoopInput`）——①「按条件可用」（`ToolCallbackContributor.toolAvailability()`，`false` ⇒ 摘除）②「按上下文动态描述」（`toolDescription()` + `DescribedToolCallback` 包装）。**生效粒度 = Turn**；工具目录加载失败时 **fail-open**（`catalogDegraded`，未知名字保留）；**不作用**于 MCP `tools/list` 暴露面。**不可关闭清单 `CRITICAL_TOOLS = {pullTasks, submitResult, heartbeat}`** 生效于授权面（`isToolEnabled` 短路 + `getEnabledTools` 并集），**刻意不抵销** `API_KEY_LLM` 的 MCP 会话工具过滤。当前真实声明者 = `WebSearchToolCallback`（无搜索凭据 ⇒ 摘 `web_search`）。边界正文见 `design/Agent_Runtime.md`。

当前仍不能宣称已完成完整 Harness Runtime：

```text
ToolExecutor      → 契约+真身已具备（执行回路真身），AgentLoop 已接线
AgentLoop         → 契约+真身已具备（手动工具循环真身），已组装进 RuntimeTurnExecutor
Session 协调      → 已确认收敛口径（AgentSessionService 承载）
SandboxProvider   → 契约已具备，隔离能力后置（Docker/K8s P2/P3）
Capability 体系   → 尚需完善
```

# 8. 当前 Event 基线

当前已经存在 `agent_event` 体系，以及：

```text
Run
Turn
Step
Event
```

的统一模型。

当前 Event 的主要职责：

```text
执行轨迹
对账
Timeline / Review 的事实输入
```

读侧已具备 `AgentEventQueryService` 多消费面：`traceBySubTaskId`（按 subTaskId 以 `createTime + id` 有序投影，Timeline 消费面，A6 已并轨 `/timeline`；增量 D 起亦经 REST 暴露）、`traceByRunId`（按 runId 重建 Run 级轨迹，Replay 读侧，A7）、`traceByTaskId`（任务维度轨迹，免传 runId 由 service 内部推导，增量 D）、`pageAuditByTaskId`（按 taskId 分页查执行事实，eventType 可选过滤，Audit 读侧，A7）——Timeline / Replay / Audit 已从 Event Stream 获取事实，并经 REST API（`/api/agent-events` 四端点）+ UI 事件流工作台（`/event-stream`，增量 C1/C2/D）全链暴露；**Fork 仅快照服务**（`AgentEventForkService`，可复制 Run 事件到新 run_id，但**零生产调用方**；触发入口 / 原 Run 冻结 / 驱动新 Run 执行已于 2026-10-09 裁定 **WONTFIX**，见 `D-2026-10-09-5`），**Recovery 消费面后续建设**。`task_timeline` 保持独立载体不迁移（ADR-001 §4）。

原则：

> `AgentEvent` 记录执行事实；业务状态机仍然是业务状态权威。

因此当前不是“Event Sourcing 全量替换业务状态机”。

# 9. 当前 Skill 基线

当前 Skill 已具备：

```text
requiredSkills（任务级 + 子任务级 V74 新列，装箱并集）
Skill resolve
resolvedSpecs
SKILL_RESOLVED（携带 resolvedVersions）
SkillPackage 元数据层（version / requiredTools / dependencies / inputSchema / outputSchema / validationRules）
SkillPackageCatalog 目录扫描（元数据事实源 = md frontmatter，懒加载，按 name 升序）
SKILL_CATALOG 目录注入（拆解侧能力感知，G-010 S2）
AgentTask.skills 契约层统一注入（execute/executeStream + 拆解/审查收口/报告 5 类同步 LLM 调用挂点，空不注入行为零变化）
技能目录查询 API（GET /api/skills/catalog，skill:view，V103）
技能包安装入口（POST /api/skills/packages，skill:install，V104）：受控存储（PG 元数据 + 正文 / MinIO 原始 zip）+ 摄入闸门（REF-1.5）+ 安装-激活-卸载审计
```

Skill 已从隐式 Prompt 拼接迁移为显式 Runtime 输入 + 元数据面，required_skills 创建 → 拆解 → 派发 → 执行四段贯通，并经真实任务实测闭环（外部执行者双轮，见 log）。

**元数据事实源（2026-10-09 起）**：技能的 9 个元数据字段由 classpath `skills/plugins/*.md` 的 **YAML frontmatter** 承载（8 键白名单，`fileName` 由文件名推导），**原先的 Java `KNOWN_SPECS` 编译期硬编码已删除**；目录扫描按 name 升序，坏文件显式报 corrupt（不静默跳过）。⇒ **新增技能 = 丢一个 md 文件，零改 Java 代码**（「零发版」未达成：需外部技能目录）。

已结构化技能包 4 个（eng-*）：eng-code-review / eng-doc-standard / eng-verification / eng-web-research（requiredTools=[web_search]）。

**技能包安装面（2026-10-10 起，REF-1.5 / 1.6）**：技能包可由安装入口装入并持久化到**受控存储**——元数据与原始正文落 PG、原始上传 zip 落 MinIO（`minio://…/skill-packages/…`），**不写宿主文件系统**（故不构成 `G-005` 沙箱触发条件①）。技能目录改为**双源**（classpath 内置 + 已安装 ACTIVE 行）：同 name 至多一行 ACTIVE、多版本共存供回滚，与内置 `eng-*` 同名一律拒绝。**摄入安全闸门**（文件数 / 解压总量 / 压缩比 / 路径穿越 / symlink / 非普通文件 / 加密位 / 清单 UTF-8）是安装的前置。原计划的「外部技能目录」**已裁 WONTFIX**（差距表 §7.1.3 R4）。

当前尚未全量形成的 Capability Package 剩余缺口主要是：

```text
Instructions 结构化
技能回流贡献规范（D5-3 未交付）
```

# 10. 当前 Environment / Sandbox 基线

当前已经具备：

```text
ExecutionEnvironment
ExecutionEnvironmentProvider
RemoteAgent
LocalProcess
ENVIRONMENT_RESOLVED
SandboxProvider / SandboxContext / Sandbox / ExecutionPolicy（契约层；诚实策略无 ISOLATED）
```

当前准确定位是：

> **执行环境抽象已经存在；完整安全沙箱尚未完成。**

完整 Sandbox 还需要考虑：

```text
Filesystem Boundary
Network Boundary
Process Boundary
Resource Limit
Credential Boundary
```

# 11. 当前 Workflow / Team

当前已具备：

- Workflow Template / Version；
- Workflow 实例化/物化；
- Team / Member；
- 节点级派发和 Agent 绑定。

当前边界：

```text
Workflow = 编排定义
Scheduler = 运行期调度
```

不要因为未来演进再建立第二套运行时。

# 12. 当前 Reviewer

当前 Reviewer 已形成：

```text
执行结果
  ↓
Review
  ↓
PASS / REJECT
  ↓
REWORK
```

后续目标是把它收敛为 Quality Gate，但现阶段不建立第二套 Review Runtime。

**核验证据边界**：附件正文注入进核验 Prompt 时按限额截断（**每份 64000 字符 / 总量 200000 字符**，以匹配官方 DeepSeek 64K 上下文；正常产出不会触发截断），截断处输出结构化标注行 `[TRUNCATED] file=… shown=… total=… reason=…`；核验 Prompt 明确要求「不可见部分一律视为未提供证据，禁止以提交方自报数值/自检清单补全，依赖不可见内容的验收项不得判 pass」。

# 13. 当前明确边界

```text
不绑定单一模型
不绑定单一厂商
不要求 Agent 永远长连接
不允许外部 Agent 直接写 HelloAI 数据库
不允许外部 Agent 绕过平台状态机
不因 Harness 借鉴而复制完整 Harness
```

# 14. 当前基线一句话

> **HelloAI 已经具备异步执行、分布式调度、异构 Agent 接入、可靠性治理和基础执行环境抽象；Agent Event Stream 与 AgentRuntime 已统一收敛（2026-09 收官，经外部执行者双轮全链实测），下一阶段的核心是继续收敛 Skill Capability 剩余缺口与 Sandbox Provider 隔离能力，并补齐 Event 消费面的 Recovery。**
