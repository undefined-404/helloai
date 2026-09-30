# Agent Runtime

> **Status: `Implemented`** · **Scope: 单次 Agent Turn 的执行契约（`AgentRuntime` 接口 + 八件套）；不含任务调度** · **差距锚点：`G-002 · G-003`**
>
> 最后更新：2026-09-30（**Document V2.1 治理**：补 Status / Scope 头。状态事实源 = [`../HelloAI 实现差距表.md`](../HelloAI%20实现差距表.md)，本文件只声明设计边界，不复述进度）

## 定义

> AgentRuntime 负责“一次 Agent Turn 如何执行”，而不是负责整个任务如何调度。

## 目标接口

```java
public interface AgentRuntime {
    AgentExecutionResult execute(AgentContext context);
}
```

## Runtime 内部组成

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

## 不属于 Runtime

```text
Planner
Global Scheduler
Workflow State Machine
Reviewer Decision
Agent Fleet Routing
```

## 迁移模型（已终结）

> **2026-09-30 G-002 单轨硬切**：迁移期结束——旧链与 Adapter 已删除，不存在双轨，也不存在灰度开关。

```text
Legacy Executor          已删除
LegacyExecutorAdapter    已删除（含 RuntimeAgentRuntimeRouter / TurnLlmCaller）
        ↓
AgentRuntime Contract    唯一执行契约
        ↑
RuntimeTurnExecutor      唯一实现（agentRuntimes.get(0) 直取，无路由无开关）
```

## Agent Loop

长期目标：

```text
Model
 ↓
Decision
 ↓
Tool
 ↓
Observation
 ↓
Model
 ↓
...
 ↓
Final Result
```

第一阶段允许 Runtime 继续支持单步/单次执行，不要求一次性完成完整 Tool Loop。
