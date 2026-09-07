# HelloAI 架构变更记录

> 本文件记录重大架构决策，不记录普通代码提交流水账。

## ARCH-20260907-001 — Event Stream 作为统一执行事实层

### Decision
使用 AgentRun + AgentEvent Stream 描述一次 Agent 执行。

### Consequence
Timeline / Audit / Replay / Metrics 可以共享执行事实。

### Boundary
业务状态机仍然独立作为业务状态权威。

## ARCH-20260907-002 — Dual Executor 仅作为迁移策略

### Decision
LegacyExecutor 通过 Adapter 接入统一 Runtime Contract，Runtime 成为长期执行入口。

### Consequence
新旧实现可以灰度迁移，避免一次性重写。

## ARCH-20260907-003 — AgentRuntime 作为执行抽象

### Decision
把一次 Agent Turn 的执行能力从具体 Executor 中抽象出来。

### Boundary
Planner / Global Scheduler / Reviewer 不属于 Runtime。

## ARCH-20260907-004 — Skill 向 Capability Package 演进

### Decision
保留 Markdown Instructions 兼容性，逐步增加版本、Tool、Schema、依赖等结构化元数据。

## ARCH-20260907-005 — Sandbox Provider 解耦

### Decision
Runtime 只依赖 SandboxProvider Contract，具体 Local / Docker / Remote / K8s 实现由 Provider 提供。
