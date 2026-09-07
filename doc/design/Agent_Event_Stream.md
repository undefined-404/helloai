# Agent Event Stream

> 状态：ACTIVE / P0

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
 └── Recovery（后续）
```
