# HelloAI 架构改造长期思路

> 来源：外部架构评估建议，2026-09-02 归档至 `doc/design/` 作为长期参考。
> 本文档不是当前 sprint 的执行计划，而是架构演进的方向性参考。

---

## 当前定位

HelloAI 当前处于 **V1：已具备生产级分布式 Agent 调度雏形，但 Agent Runtime 抽象还不够完整**。

核心问题不是"功能少"，而是：

```
功能已经不少
     ↓
但核心抽象还没有完全收敛
     ↓
Executor / Task / Agent / Skill / Workflow
之间边界还有进一步提升空间
```

## 核心原则：先收敛，再扩展

当前不应优先"横向堆 AI 能力"，而应先做一次"架构收敛 + Runtime 升级"：

```
❌ 更多 Agent
❌ 更多 LLM
❌ 更多 Skill
❌ 更多 MCP
❌ 更多 UI
```

收益不如：

```
✅ Agent Runtime
✅ Event/Trajectory
✅ 状态机一致性
✅ Workflow
✅ Sandbox
✅ 可恢复执行
```

---

## 四层边界定义

### Planner
回答：**What should be done?**
```
需求 → Plan → DAG
```

### Scheduler / Workflow Engine
回答：**When / In what order?**
```
Plan → Workflow → Task → 依赖/并发/调度
```

### Agent Runtime
回答：**How to execute?**
```
Task → Context → Skill → Tool → Agent → Result
```

### Reviewer
回答：**Is it correct?**
```
Result → Evidence → Review → PASS / REWORK
```

---

## P0：Agent Runtime Foundation

### P0-1：Executor → AgentRuntime 核心抽象

把 Executor 的职责重新划分：

```
AgentRuntime
├── AgentContext
├── SkillResolver
├── ToolRegistry
├── ToolExecutor
├── AgentLoop
├── SessionManager
├── SandboxProvider
└── EventRecorder
```

### P0-2：统一的 Agent Event Stream

建立 Run → Turn → Step 三层事件模型：

```
Run #1001
├── Turn #1
│    ├── Step #1
│    ├── Step #2
│    └── Step #3
├── Turn #2
│    ├── Step #1
│    └── Step #2
└── Turn #3
     └── Step #1
```

事件类型示例：

```
RUN_CREATED → PLAN_CREATED → TASK_CREATED → TASK_ASSIGNED →
AGENT_STARTED → SKILL_RESOLVED → CONTEXT_BUILT →
TOOL_CALL_STARTED → TOOL_CALL_COMPLETED → AGENT_STEP →
AGENT_COMPLETED → REVIEW_STARTED → REVIEW_REJECTED →
REWORK_STARTED → REVIEW_APPROVED → RUN_COMPLETED
```

Event Stream 的价值：

```
Event Stream
     ├── Audit
     ├── Metrics
     ├── Timeline
     ├── Debug
     ├── Replay
     ├── Resume
     ├── Fork
     └── Review
```

### P0-3：状态机 + CAS + Lease

并发事件下状态机一致性：

```
State Transition + Version/CAS + Event Ordering + Idempotency
```

状态更新必须基于版本号：

```sql
UPDATE task
SET status = ?, version = version + 1
WHERE id = ? AND version = expectedVersion
```

### P0-4：统一 RetryPolicy

当前多个层级各自重试，可能叠加放大。建议统一：

```
RetryPolicy { maxAttempts; backoff; retryableErrors; timeout; jitter; }
```

一个失败，只允许一个层级拥有 Retry Authority。

---

## P1：Capability System

### Skill Registry
Skill 从 Prompt 资源升级为 Capability：
- name, version, description, instructions
- requiredTools, dependencies
- inputSchema, outputSchema, validationRules

### Tool Registry
统一工具注册：
```
ToolRegistry → GitTool / ShellTool / FileTool / DockerTool / MCPTool / CustomTool
```

### SandboxProvider
统一沙箱抽象：
```
SandboxProvider → LocalSandbox / DockerSandbox / RemoteSandbox / K8sSandbox
```

### Workflow Engine
Plan 与 Workflow 拆开，Workflow Engine 负责：
- Dependency / Parallelism / Retry / Timeout / Compensation / Cancellation

### Reviewer → Quality Gate
```
Quality Gate
├── Rule Check
├── Test Check
└── LLM Review
     ↓
Quality Decision: PASS / REWORK / HUMAN_REVIEW / BLOCK
```

---

## P2：Agent Fleet

```
AgentRegistry → Qoder / Trae / Codex / Claude / DeepSeek
AgentDescriptor → capabilities / supportedSkills / supportedTools / cost / latency / health / concurrency / reliability
AgentSelector → score = capabilityMatch + skillMatch + health + latency + cost + historicalSuccessRate
```

---

## P3：AI-native Workflow

```
User Requirement → Planner → Workflow Generation → Workflow Engine → Agent Fleet
```

动态条件分支（后续再做）：

```
if securityRisk > threshold: securityReview()
if testFailure: developerRework()
if architectureChange: architectureReview()
```

---

## 目标架构 V2/V3

```
                         ┌─────────────────────┐
                         │      Client         │
                         │  Web / API / MCP    │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │       Planner       │
                         │ Requirement → Plan  │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │  Workflow Engine    │
                         │ DAG / Dependency    │
                         │ Parallel / Timeout  │
                         │ Retry / Compensation│
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │ Distributed         │
                         │ Scheduler           │
                         │ Agent Selection     │
                         │ Lease / CAS         │
                         │ Load Balance        │
                         └──────────┬──────────┘
                                    │
                                    ▼
                  ┌────────────────────────────────┐
                  │          Agent Runtime          │
                  │  Context / Session / Skills     │
                  │  Tool Registry / Agent Loop     │
                  │  Sandbox Provider               │
                  └───────────────┬────────────────┘
                                  │
                  ┌───────────────┼────────────────┐
                  │               │                │
                  ▼               ▼                ▼
              Qoder            Trae             Codex
                                  │
                                  ▼
                         ┌─────────────────────┐
                         │   Event Stream      │
                         │ Run / Turn / Step   │
                         │ Tool / Skill / Agent │
                         └──────────┬──────────┘
                                    │
                   ┌────────────────┼────────────────┐
                   ▼                ▼                ▼
                Audit           Metrics          Replay
                   │                │                │
                   └────────────────┼────────────────┘
                                    ▼
                         ┌─────────────────────┐
                         │    Quality Gate     │
                         │ Rule + Test + LLM   │
                         └──────────┬──────────┘
                                    │
                         ┌──────────┴──────────┐
                         ▼                     ▼
                      PASS                  REWORK
```

---

## 开发顺序建议（原始参考）

> 以下为原始架构思路中的开发顺序建议，保留作为方向参考。
> 具体的 Phase 0 执行方案见 [HelloAI_Phase0_架构改造执行方案](./HelloAI_Phase0_架构改造执行方案.md)。

### Phase 0 —— 架构收敛 ★★★★★
1. Executor → AgentRuntime 抽象
2. 明确 Planner / Workflow / Scheduler / Runtime / Reviewer 边界
3. 统一 Run / Task / Step 模型
4. 重新梳理状态机
5. 明确 Retry Authority
6. Scheduler owner + CAS/lease

### Phase 1 —— Harness 能力吸收 ★★★★★
AgentRuntime → SkillRegistry / ToolRegistry / Session / EventStream / SandboxProvider

### Phase 2 —— Agent Governance ★★★★☆
Quality Gate / Audit / Trajectory / Replay / Resume / Fork / Metrics

### Phase 3 —— Agent Fleet ★★★★☆
Agent Registry / Capability / Health / Routing / Historical Success / Cost & Latency

### Phase 4 —— AI-native Workflow ★★★☆☆
LLM → Dynamic Workflow → Workflow Engine → Agent Fleet

---

## 五个 Epic（原始参考）

```
EPIC-01  Agent Runtime Foundation
         ├── AgentRuntime
         ├── AgentContext
         ├── Session
         └── Execution Contract

EPIC-02  Agent Event & Trajectory
         ├── Run
         ├── Step
         ├── Event
         ├── Replay
         └── Audit

EPIC-03  Reliable Distributed Execution
         ├── State Machine
         ├── CAS
         ├── Lease
         ├── Retry Policy
         └── Timeout / Compensation

EPIC-04  Capability System
         ├── Skill Registry
         ├── Tool Registry
         ├── Sandbox Provider
         └── Agent Provider

EPIC-05  Agent Governance
         ├── Quality Gate
         ├── Agent Health
         ├── Agent Routing
         ├── Metrics
         └── Historical Success
```

---

## 暂时不要碰

- 多模型路由（不是模型平台）
- 复杂 Memory（Event Stream + Session 未做好前容易变成垃圾桶）
- Agent Swarm（没必要为了 Multi-Agent 而 Multi-Agent）
- 复杂 MCP 编排（MCP 现阶段只是 Tool 能力的一种 Provider）
- 复杂 Workflow DSL（先把 Workflow Engine 做干净）
- 大规模 Kubernetes 化（当前需要架构正确，不是部署规模）

---

## 最终定位演进

```
Multi-Agent Orchestration Platform
           ↓
Distributed Agent Orchestration & Governance Platform
           ↓
Agent Operating Platform
```

架构层级：

```
                  HelloAI
                     │
       ┌─────────────┴─────────────┐
       │                           │
 Agent Runtime              Agent Orchestration
       │                           │
   Harness-like               Scheduler
       │                       Workflow
   Skill / Tool                Agent Fleet
   Session                     Governance
   Sandbox                     Quality Gate
       │                           │
       └─────────────┬─────────────┘
                     ↓
             Enterprise Agent
               Infrastructure
```

