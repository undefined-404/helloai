# Agent Runtime

> 状态：ACTIVE / P0

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

## 迁移模型

```text
Legacy Executor
      ↓
LegacyExecutorAdapter
      ↓
AgentRuntime Contract
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
