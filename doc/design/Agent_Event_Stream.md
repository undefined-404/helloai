# Agent Event Stream

> **Status: `Partial`** · **Scope: Run / Turn / Step 事件契约与消费体系（写侧 + Timeline / Replay / Audit / Recovery 读侧）** · **差距锚点：`G-001`**
>
> 最后更新：2026-10-16（**Recovery 消费面落地**：新增只读恢复点投影 `AgentEventQueryService#projectRecoveryPoint`
> 与续跑注入的事件流兜底，见下方「建议消费面」；设计边界见 §Recovery 消费面。
> 上一版 2026-09-30：**Document V2.1 治理** 补 Status / Scope 头。状态事实源 = [`../HelloAI 实现差距表.md`](../HelloAI%20实现差距表.md)，本文件只声明设计边界，不复述进度）

## 目标

把一次 Agent Run 的执行事实统一成可排序、可关联、可审计的事件流。

```text
AgentRun
  └── Event Stream
```

## 事件生命周期

```text
RUN_CREATED
TASK_CREATED
TASK_ASSIGNED
AGENT_STARTED
SKILL_RESOLVED
SESSION_STARTED
TOOL_STARTED
TOOL_COMPLETED
TOOL_FAILED
AGENT_COMPLETED
AGENT_FAILED
REVIEW_STARTED
REVIEW_PASSED
REVIEW_REJECTED
REWORK_STARTED
RETRY_STARTED
RUN_COMPLETED
RUN_FAILED
```

## Event 字段

```text
eventId
runId
taskId
subTaskId
turn
step
eventType
sequence
timestamp
actor
correlationId
causationId
payload
```

## 核心原则

1. Event append-only；
2. Event 是 execution fact，不是业务状态；
3. sequence 保证同一 Run 内的顺序语义；
4. 新旧执行入口必须产生同一事件模型；
5. Event 写入必须可幂等；
6. Replay 是读取历史，不代表再次产生副作用。

## 建议消费面

```text
Event Stream
 ├── Timeline
 ├── Audit
 ├── Replay
 ├── Metrics
 └── Recovery
```

## Recovery 消费面（2026-10-16）

**职责**：回答「上一次执行尝试停在哪、从哪里继续」。与另三个消费面的分工是**问的问题不同**：
Timeline / Replay / Audit 回答「发生了什么」，Recovery 回答「**从哪继续**」。

**只读投影**（`AgentEventQueryService#projectRecoveryPoint`，产出 `RecoveryPoint`）：

```text
unfinished          # 按事件序「AGENT_STARTED 置开、AGENT_COMPLETED 置闭」，结束仍开 ⇒ 未闭合轮次
unfinishedTurn
danglingTool        # 未闭合轮次内「有 TOOL_CALL_STARTED、无 TOOL_CALL_COMPLETED」的工具（= 中断点）
completedTools      # 未闭合轮次内已完成的工具（保序去重 ⇒ 接续者无需重复）
lastEventType / lastEventSequence / lastEventAt
terminalEventSeen
```

**纪律（本消费面的核心约束）**

1. **纯读**：不落库、不写业务状态、**不反向修正**任何东西 —— 与 `EventReconciliationService`
   的「只报告不修正」同源（原则 2「Event 是 execution fact，不是业务状态」）。
2. **不替代业务状态**：恢复点只用于**注入续跑上下文**（`AgentRuntimeContextAssembler`）与
   **只读暴露**（`GET /api/agent-events/recoveryPointBySubTaskId/{subTaskId}`），
   消费方不得据其直接改写业务表。
3. **零阈值判定**：全部由结构量推出（「无 `AGENT_COMPLETED`」是唯一可用的未终结信号 ——
   事件层不定义失败终态事件，ADR-001 §5.3）。

**与会话表的分工（为什么不合并）**：续跑上下文原本只取自 `agent_session` 表
（`AgentSessionServiceImpl#findLatestInterrupted`）。该表是**可变表**且口径为「只看最新一行，
ACTIVE / COMPLETED 一律零注入」；进程执行中途被中断时该行可能停在 ACTIVE 或不存在
⇒ 静默零注入。而事件流是 **append-only 事实**，悬挂的 `TOOL_CALL_STARTED` 永不被覆盖。
⇒ 二者**互补**：会话表优先（带 snapshot，信息更全），事件流兜底（覆盖面更全）。
