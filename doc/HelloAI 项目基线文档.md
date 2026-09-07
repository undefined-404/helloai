# HelloAI 项目基线文档

> **状态：CURRENT / FACT**
>
> 本文档只描述当前真实代码与已落地能力，不描述未来愿景。
>
> 最后更新：2026-09-07

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
- Agent 在线状态治理；
- 失败重派和结果收敛。

这些属于 HelloAI 的**分布式编排与可靠性基础设施**。

# 7. 当前 Runtime 基线

当前已经存在：

```text
AgentContext
AgentRuntime
AgentExecutionResult
ExecutionEnvironment
ExecutionEnvironmentProvider
LegacyExecutorAdapter
ToolExecutor
ToolExecutionResult
AgentLoop
AgentLoopInput
AgentLoopResult
SandboxProvider
SandboxContext
Sandbox
ExecutionPolicy
RuntimeTurnExecutor
RuntimeAgentRuntimeRouter
```

当前含义：

- `AgentRuntime` 已成为统一执行契约；
- 旧 Executor 已通过 Adapter 与新执行入口衔接；
- Runtime 已开始承接 Context / Event / Environment 等能力；
- `ToolExecutor` 已具备执行回路真身（懒加载 spring-ai ToolCallback 目录按名调用，与 ToolRegistry 元数据面同源同构；未知工具 / 空参 / 执行异常 best-effort 返回失败不抛）；
- `AgentLoop` 已具备手动工具循环真身（`runtime/loop`：ChatModel 契约 + ToolExecutor 执行 + TOOL_CALL 事件，`internalToolExecutionEnabled=false` 由循环接管工具执行，maxIterations 硬上限防死循环；真实 provider 行为待 Runtime 真身接线时联调）；
- `RuntimeTurnExecutor` 已具备（P0-B：AgentLoop/ToolExecutor/ToolRegistry/Skill/SandboxProvider 组装的 Turn 真身，事件骨架 + 沙箱观测）；`RuntimeAgentRuntimeRouter` 已具备（@Primary + @Order(1)，按 `runtime-enabled` 二进制切换，默认 false=Legacy）。

当前仍不能宣称已完成完整 Harness Runtime：

```text
ToolExecutor      → 契约+真身已具备（Phase 2），AgentLoop 已接线
AgentLoop         → 契约+真身已具备（Phase 3），待 Runtime 真身组装
Session 协调      → 已确认收敛口径（AgentSessionService 承载，Phase 1 Step 3）
SandboxProvider   → 契约已具备（Phase 4），隔离能力后置（Docker/K8s P2/P3）
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

读侧已具备 `AgentEventQueryService` 三消费面：`traceBySubTaskId`（按 subTaskId 以 `createTime + id` 有序投影，Timeline 消费面，A6 已并轨 `/timeline`）、`traceByRunId`（按 runId 重建 Run 级轨迹，Replay 读侧，A7）、`pageAuditByTaskId`（按 taskId 分页查执行事实，eventType 可选过滤，Audit 读侧，A7）——Timeline / Replay / Audit 已从 Event Stream 获取事实；Recovery / Fork 消费面后续建设。`task_timeline` 保持独立载体不迁移（ADR-001 §4）。

原则：

> `AgentEvent` 记录执行事实；业务状态机仍然是业务状态权威。

因此当前不是“Event Sourcing 全量替换业务状态机”。

# 9. 当前 Skill 基线

当前 Skill 已具备：

```text
requiredSkills
Skill resolve
resolvedSpecs
SKILL_RESOLVED
```

Skill 已开始从隐式 Prompt 拼接向显式 Runtime 输入迁移，但还没有完整形成 Capability Package。

当前缺口主要是：

```text
version
requiredTools
dependencies
inputSchema
outputSchema
validationRules
```

# 10. 当前 Environment / Sandbox 基线

当前已经具备：

```text
ExecutionEnvironment
ExecutionEnvironmentProvider
RemoteAgent
LocalProcess
ENVIRONMENT_RESOLVED
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

> **HelloAI 已经具备异步执行、分布式调度、异构 Agent 接入、可靠性治理和基础执行环境抽象；下一阶段的核心是统一 Agent Event Stream，并在双轨迁移基础上继续收敛 AgentRuntime、Skill Capability 和 Sandbox Provider。**
