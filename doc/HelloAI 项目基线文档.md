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
```

当前含义：

- `AgentRuntime` 已成为统一执行契约；
- 旧 Executor 已通过 Adapter 与新执行入口衔接；
- Runtime 已开始承接 Context / Event / Environment 等能力。

当前仍不能宣称已完成完整 Harness Runtime：

```text
ToolExecutor      → 尚需完善
AgentLoop         → 尚需完善
Session 协调      → 尚需继续收敛
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
