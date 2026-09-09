# HelloAI 目标架构

> **状态：TARGET ARCHITECTURE**
>
> 本文档定义未来稳定架构边界，不表示所有能力当前已经落地。
>
> 最后更新：2026-09-09（Planner 增强定界：G-010 能力感知与自适应粒度 / G-011 需求包准入与不确定性显式管理）

# 1. 目标定位

> **HelloAI 是面向跨终端、跨厂商异构 AI Agent 的分布式协作与治理平台。**

HelloAI 不重新实现底层 Coding Agent，而是让不同 Agent 在统一的平台上完成：

```text
Planning
→ Orchestration
→ Distributed Execution
→ Review
→ Governance
```

# 2. 目标架构

```text
                         HelloAI Platform
                                │
                ┌───────────────┴───────────────┐
                │                               │
             Planning                     Governance
                │                               │
        Requirement Package                Quality Gate
                │                               │
             Planner                            │
                │                               │
                └───────────────┬───────────────┘
                                ↓
                        Workflow Engine
                                │
                                ↓
                     Distributed Scheduler
                                │
                                ↓
                         Agent Runtime
                                │
        ┌───────────────────────┼───────────────────────┐
        ↓                       ↓                       ↓
     Context                  Skill                    Tool
        │                       │                       │
        │                Capability Package        MCP / API
        │                       │                       │
        └───────────────────────┼───────────────────────┘
                                ↓
                         Sandbox Provider
                                │
                                ↓
                          Agent Provider
                                │
              ┌─────────────────┼─────────────────┐
              ↓                 ↓                 ↓
            Qoder              Trae             Codex
              │                 │                 │
              └─────────────────┼─────────────────┘
                                ↓
                       Agent Event Stream
                                │
                ┌───────────────┼───────────────┐
                ↓               ↓               ↓
              Audit          Metrics          Replay
                                │
                                ↓
                            Recovery
```

# 3. 五层职责

## Role Layer

```text
Planner
Reviewer / Quality Gate
Governance
```

只表达平台角色职责，不包含具体 Agent Provider 实现。

### Planner 目标形态（G-010 / G-011 定界，2026-09-09）

V2 目标态的 Planner 拆解链路（两次增强后）：

```text
Requirement Package（需求包：goal / scope / outOfScope / assumptions / openQuestions）
        ↓
能力感知拆解（技能目录注入 + 执行者画像 + 难度感知）
        ↓
粒度自适应（FINE / STANDARD / COARSE，rule-based 矩阵；LLM 自判后置）
        ↓
子任务契约（requiredSkills / constraints / uncertainties[ASSUMPTION|UNCONFIRMED]）
```

定界原则：

- **拆解不虚构能力**：技能指派必须命中平台技能目录，未命中丢弃并审计（幻觉标签零容忍）；
- **推断不伪装成事实**：不确定性显式分级登记——ASSUMPTION 执行者自验证、UNCONFIRMED 上报人工裁决；
- **粒度不是越细越好**：细=步骤 + 每步 Skill + 输入/输出契约 + DoD；粗=目标 + 约束 + DoD；
- **不建自动闸门**：openQuestions 不阻断拆解/派发，裁决点在草案确认（人工）与执行侧（fail-close 走既有 BLOCKED 链）；不建"需求管理中心"平行架构；
- **契约向后兼容**：REST / inbox 只增可选字段，外部 Agent 未升级无感知。

设计文档：`doc/design/Planner_Capability_Awareness.md`（G-010，S1~S3 已落地）、`doc/design/Requirement_Package_Uncertainty.md`（G-011，设计落稿待实施）。

## Orchestration Layer

```text
Workflow
Scheduler
DAG
Dependency
Parallelism
Routing
```

负责一个团队/任务整体如何运行。

## Runtime Layer

```text
AgentRuntime
Context
Session
AgentLoop
```

负责一次 Agent Turn 如何实际执行。

## Capability Layer

```text
Skill Package
Tool
MCP
Sandbox Provider
```

负责 Runtime 可以获得哪些能力，以及在哪个环境中执行。

## Provider Layer

```text
Qoder
Trae
Codex
Claude Code
DeepSeek
...
```

通过统一 Provider Contract 接入。

# 4. AgentRuntime 边界

目标：

```text
AgentRuntime
├── AgentContext
├── Session
├── SkillResolver
├── ToolRegistry
├── ToolExecutor
├── AgentLoop
├── SandboxProvider
└── EventRecorder
```

Runtime 不负责：

```text
Global Scheduler
Workflow 全局状态
Planner Decision
Reviewer Decision
Agent Fleet Routing
```

# 5. Event Stream

目标统一模型：

```text
Run
 ├── Turn
 │    ├── Event
 │    └── Event
 └── Turn
      └── Event
```

原则：

> **Event 记录发生过什么；业务状态机记录当前是什么状态。**

不要求通过 Event Stream 重写所有业务状态。

# 6. Skill Capability Package

目标：

```text
Skill Package
├── Name
├── Version
├── Description
├── Instructions
├── RequiredTools
├── Dependencies
├── InputSchema
├── OutputSchema
└── ValidationRules
```

Markdown 可以继续作为 Instructions 的存储载体。

Skill 生命周期：

```text
Discover
→ Resolve
→ Load
→ Execute
→ Validate
```

# 7. Sandbox Provider

目标：

```text
AgentRuntime
      ↓
SandboxProvider
      ↓
Execution Environment
```

可能实现：

```text
Local
Docker
Remote
K8s
```

但真正安全隔离还必须覆盖文件、网络、进程、资源和凭证边界。

# 8. Agent Fleet

每个 Agent 应拥有：

```text
Provider
Terminal
Capabilities
Skills
Health
Concurrency
Cost
Latency
Historical Success
```

未来选择逻辑：

```text
Task Requirements
      ↓
Capability Match
      ↓
Health / Load
      ↓
Policy
      ↓
Agent Selection
```

# 9. 最终执行链

```text
Requirement
   ↓
Requirement Package（goal / scope / outOfScope / assumptions / openQuestions）
   ↓
Planner（能力感知 + 粒度自适应；子任务级技能指派 / 约束 / 不确定性登记）
   ↓
Workflow
   ↓
Distributed Scheduler
   ↓
AgentRuntime
   ↓
Skill + Tool + Sandbox
   ↓
Heterogeneous Agent
   ↓
Agent Event Stream
   ↓
Reviewer / Quality Gate
   ↓
PASS / REWORK / HUMAN_REVIEW / BLOCK
```

# 10. Harness 的角色

DeepSeek Harness 是**重要的 Agent Runtime 参考架构**，但不是 HelloAI 的目标产品。

吸收重点：

```text
1. Event / Trajectory 统一化
2. Skill → Capability Package
3. Sandbox Provider 抽象
```

不以复制 Harness 全部内部实现为目标。

# 11. 非目标

```text
❌ DeepSeek Harness Clone
❌ 单一 Coding Agent Runtime
❌ 单一模型平台
❌ 第二套 Scheduler
❌ 第二套 Workflow Runtime
❌ 外部 Agent 绕过平台状态机
```
