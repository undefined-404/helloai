# HelloAI 重构实施计划

> **状态：ACTIVE**
>
> 当前主线：**Event Stream → Dual Executor → AgentRuntime → Skill Capability → Sandbox Provider**。
>
> 最后更新：2026-09-07

# 1. 重构目标

本轮不是继续堆角色或功能，而是进行一次架构收敛：

```text
统一事实
→ 安全迁移
→ Runtime 收敛
→ Capability 演进
→ Sandbox 解耦
→ Governance
```

# 2. P0-A：统一 Agent Event Stream

## 目标

将现有执行记录逐步统一为：

```text
AgentRun
  ↓
AgentEvent Stream
```

第一版核心字段：

```text
eventId
runId
taskId
subTaskId
eventType
sequence
timestamp
actor
correlationId
causationId
payload
```

## 实施顺序

```text
A1 Event Contract
A2 EventType
A3 sequence / correlation / causation
A4 Legacy Adapter 埋点
A5 Runtime 埋点
A6 Timeline 消费迁移
A7 Replay / Audit 最小读取
```

## 现状基线（2026-09-07 代码核查）

A1~A5 已落地：`agent_event` 三层模型（Run / Turn / Step，append-only）+ EventType（含 SKILL_RESOLVED=5 / TOOL_RESOLVED=6 / ENVIRONMENT_RESOLVED=7 step 槽位）+ AgentEventRecorder（write-only）。验证：`verify-c3-events.ps1`。

A6 路线 B（2026-09-07 已落地）：新增 `AgentEventQueryService#traceBySubTaskId` 读侧投影（`agent_event` 按 subTaskId 以 `createTime ASC, id ASC` 有序重建轨迹），仅后端读侧，未接 UI；单测 3 用例 + dev 库连库验证 PASS。

**当前动作**：A6 后续（`/timeline` 读侧并轨 `agent_event`，作为 Timeline / Replay 共用读消费面）与 A7（Replay / Audit 最小读取）待续。Timeline 仍为独立载体（`task_timeline`），未从 Event Stream 消费。

# 3. P0-B：Executor 双轨迁移

```text
ExecutionRouter
      │
 ┌────┴────┐
 ↓         ↓
Legacy    Runtime
```

原则：

- 双轨只用于迁移；
- 所有执行共享同一 Result Handler；
- 所有执行进入 Event Stream；
- 不建立第二状态机；
- 不通过简单 fallback 造成副作用重复执行；
- Runtime 异常的补偿必须基于 Execution Idempotency / Compensation。

迁移节奏：

```text
100% Legacy
→ 95/5
→ 50/50
→ 5/95
→ Runtime 主路径
```

## 现状基线（2026-09-07 代码核查）

契约层已是单轨：`LocalExecutionCommandConsumer` 与 `MqExecutionCommandConsumer`（委托本地消费）统一经 `AgentRuntime#execute`——唯一执行契约，旧直连执行链已下线；唯一实现 `LegacyExecutorAdapter` 转发旧链（SubTaskExecutionService）。

即当前 100% 流量经 Runtime 契约、0% Runtime 真身。本阶段实际工作不是"建轨"，而是：① 让 Runtime 侧长出真身（能力提取见 P0-C）；② 真身可用后补 Legacy ↔ Runtime 灰度切换与回滚口径。

# 4. P0-C：AgentRuntime

第一阶段：

```text
AgentRuntime
├── AgentContext
├── EventRecorder
└── execute()
```

第二阶段：

```text
ToolRegistry
ToolExecutor
```

第三阶段：

```text
AgentLoop
```

第四阶段：

```text
Session
Sandbox
```

Runtime 禁止直接依赖：

```text
Planner
Global Scheduler
Reviewer Decision
Task Service
```

## 现状基线（2026-09-07 代码核查）

八件套现状：Context / EventRecorder / Environment 已落地；ToolRegistry 为元数据面（12 平台工具，仅注入 prompt 描述，无执行回路）；Session 为中断恢复检查点（AgentSessionService）；ToolExecutor / AgentLoop / SandboxProvider 未建。旧链编排仍在 `SubTaskExecutionServiceImpl`（约 790 行）。

**当前动作从第二阶段（ToolRegistry / ToolExecutor）起步**——第一阶段（Context + EventRecorder）已落地。

# 5. P1：Skill Capability Package

从：

```text
requiredSkills → Markdown instructions
```

演进为：

```text
Skill Package
├── Metadata
├── Version
├── Instructions
├── Required Tools
├── Dependencies
└── Schema
```

保持现有 Markdown 兼容，不建立第二套 Skill Runtime。

现状（2026-09-07 代码核查）：`KNOWN_SPECS` 为标签 → Markdown 文本（无 version / requiredTools / Schema）。本阶段即增加元数据层，保持 resolve 行为兼容。

# 6. P1：Sandbox Provider

第一阶段只建立：

```text
SandboxProvider
SandboxContext
ExecutionPolicy
```

第二阶段才接入：

```text
Docker
Remote
K8s
```

现状（2026-09-07 代码核查）：已有 ExecutionEnvironment / ExecutionEnvironmentProvider（remote-agent / local-process，场所标签，非安全沙箱）；SandboxProvider Contract 未建。

# 7. P1：Event Consumers

```text
Event Stream
 ↓
Timeline
 ↓
Replay
 ↓
Audit
```

后续再做：

```text
Recovery
Fork
```

现状（2026-09-07 代码核查）：Timeline 为独立载体（`task_timeline`），未从 Event Stream 消费；Replay / Audit 零实现。本阶段从 Timeline 迁移起步。

# 8. P2：Quality Gate / Agent Fleet

Quality Gate：

```text
Rule
Test
LLM Review
  ↓
Decision
```

Agent Fleet：

```text
Requirement
 ↓
Capability Match
 ↓
Health / Load
 ↓
Policy
 ↓
Provider Selection
```

# 9. P3：Dynamic Workflow

只有 Runtime / Event / Capability 稳定之后才开展：

```text
Planner
 ↓
Workflow DSL
 ↓
Workflow Engine
 ↓
Dynamic Branching
```

# 10. 当前禁止扩张

```text
❌ Agent Swarm
❌ 复杂 Memory
❌ 第二套 Scheduler
❌ 第二套 Workflow Runtime
❌ 全面改造 Kubernetes
❌ 为了 Harness 一一复制全部插件实现
```

# 11. 最终验收问题

本轮完成后必须能够清楚回答：

1. 一次 Agent Run 发生了什么？
2. Legacy Executor 如何迁移到 Runtime？
3. Runtime 与 Scheduler 的边界是什么？
4. Skill 如何成为 Capability Package？
5. Agent 如何与执行环境解耦？
6. 新增一个厂商 Agent 需要实现什么？
7. 失败执行如何恢复且避免重复副作用？
