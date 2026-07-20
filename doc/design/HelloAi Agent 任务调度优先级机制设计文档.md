#

# HelloAi Agent 任务调度优先级机制设计文档

## 1. 背景与问题

当前 HelloAi 的 Agent 任务调度缺乏优先级机制，所有消息按先来后到（FIFO）处理，导致以下问题：

* 紧急任务（如“立即停止生产环境操作”）被排在大量普通任务之后，无法优先执行。
* 空闲 Agent 不会主动接收新任务，资源利用率低。
* 工作中的 Agent 无法被高优先级任务打断，长任务执行期间紧急请求得不到响应。
* 被打断的任务无法自动恢复，影响用户体验。
* 多用户场景下缺乏公平调度，低价值任务可能占用高价值 Agent。

这些问题在以下场景中尤为突出：

* **紧急干预**：需要立即停止危险操作时，响应延迟。
* **资源分配**：高价值 Agent 空闲时，普通 Agent 仍在处理低优先级任务。
* **长任务阻塞**：耗时任务执行过程中，无法响应新请求。
* **多租户竞争**：用户之间缺乏优先级公平性。

## 2. 解决方案概述

引入基于优先级的消息队列和 Agent 状态管理机制，实现：

* **消息优先级分类**：支持 `CRITICAL`、`HIGH`、`NORMAL`、`LOW` 四个级别。
* **优先级队列**：高优先级消息优先出队，低优先级任务仅在空闲时处理。
* **Agent 状态管理**：定义 `IDLE`、`WORKING`、`INTERRUPTED`、`PAUSED` 状态。
* **任务打断与恢复**：紧急任务可打断当前任务，并在完成后恢复原任务上下文。
* **智能调度器**：优先分配空闲 Agent，CRITICAL 任务可抢占正在工作的 Agent。

## 3. 核心设计

### 3.1 消息优先级枚举

```python
from enum import Enum

class MessagePriority(Enum):
    CRITICAL = 0    # 紧急：立即打断当前任务
    HIGH = 1        # 高优先级：插队到队列前端
    NORMAL = 2      # 普通：正常排队
    LOW = 3         # 低优先级：后台任务
```

### 3.2 优先级消息队列

```python
import asyncio
from typing import Dict

class PriorityMessageQueue:
    """支持优先级的消息队列"""

    def __init__(self):
        self._queues: Dict[MessagePriority, asyncio.Queue] = {
            priority: asyncio.Queue()
            for priority in MessagePriority
        }
        self._lock = asyncio.Lock()

    async def put(self, message: AgentMessage, priority: MessagePriority = MessagePriority.NORMAL):
        """按优先级入队"""
        async with self._lock:
            await self._queues[priority].put(message)

    async def get(self) -> AgentMessage:
        """按优先级出队（高优先级优先）"""
        async with self._lock:
            for priority in MessagePriority:
                if not self._queues[priority].empty():
                    return await self._queues[priority].get()
        raise QueueEmpty
```

### 3.3 Agent 状态与调度器

#### 状态定义

```python
class AgentState(Enum):
    IDLE = "idle"           # 空闲：可立即接受任务
    WORKING = "working"     # 工作中：正在处理任务
    INTERRUPTED = "interrupted"  # 被打断：等待恢复
    PAUSED = "paused"       # 暂停：手动暂停
```

#### 调度器核心逻辑

```python
class AgentScheduler:
    """Agent 调度器"""

    async def dispatch(self, message: AgentMessage, priority: MessagePriority):
        # 1. 查找空闲 Agent
        idle_agent = self._find_idle_agent()
        if idle_agent:
            return await idle_agent.execute(message)

        # 2. 根据优先级决定策略
        if priority == MessagePriority.CRITICAL:
            # 找到正在工作的 Agent 并打断
            working_agent = self._find_working_agent()
            if working_agent:
                await working_agent.interrupt(message)
                return

        # 3. 入队等待
        await self._queue.put(message, priority)
```

### 3.4 任务打断与恢复

```python
class Agent:
    def __init__(self):
        self._current_task: Optional[Task] = None
        self._paused_task: Optional[PausedTask] = None
        self._state = AgentState.IDLE

    async def interrupt(self, new_message: AgentMessage):
        """打断当前任务"""
        if self._state != AgentState.WORKING:
            return

        # 保存当前任务状态（消息、上下文、进度等）
        self._paused_task = PausedTask(
            message=self._current_task.message,
            context=self._current_task.context,
            progress=self._current_task.progress
        )

        self._state = AgentState.INTERRUPTED
        await self._current_task.cancel()

        # 执行新任务
        await self.execute(new_message)

    async def resume(self):
        """恢复被打断的任务"""
        if not self._paused_task:
            return

        message = self._paused_task.message
        context = self._paused_task.context

        self._paused_task = None
        await self.execute(message, resume_context=context)
```

### 3.5 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                     Message Sources                          │
│  (Console / Channels / CLI / Scheduled Tasks)               │
└─────────────────────┬───────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────┐
│              Priority Message Queue                          │
│  ┌─────────┬─────────┬─────────┬─────────┐                 │
│  │CRITICAL │  HIGH   │ NORMAL  │   LOW   │                 │
│  └────┬────┴────┬────┴────┬────┴────┬────┘                 │
└───────┼─────────┼─────────┼─────────┼───────────────────────┘
        │         │         │         │
        ▼         ▼         ▼         ▼
┌─────────────────────────────────────────────────────────────┐
│                   Agent Scheduler                            │
│  • Find idle agent → dispatch                                │
│  • CRITICAL → interrupt working agent                        │
│  • Others → queue                                            │
└─────────────────────┬───────────────────────────────────────┘
                      │
        ┌─────────────┼─────────────┐
        ▼             ▼             ▼
   ┌─────────┐   ┌─────────┐   ┌─────────┐
   │ Agent 1 │   │ Agent 2 │   │ Agent 3 │
   │ (IDLE)  │   │(WORKING)│   │(INTRPT) │
   └─────────┘   └─────────┘   └─────────┘
```

## 4. 典型使用场景

### 场景 1：紧急任务打断

**用户操作**：发送“立即停止正在执行的所有文件删除操作”指令，标记为 `CRITICAL`。

**系统行为**：

* 调度器找到正在执行任务的 Agent，调用 `interrupt()` 暂停当前任务。
* 保存当前任务状态（上下文、进度）。
* 立即执行紧急停止指令。
* 完成后根据安全策略恢复原任务或通知用户。

### 场景 2：高优先级插队

**用户操作**：发送“马上给我生成这份报告”指令，标记为 `HIGH`。

**系统行为**：

* 调度器检查是否有空闲 Agent，若有则直接执行。
* 若无空闲，则将任务插入 `HIGH` 队列前端，优先于 `NORMAL` 和 `LOW` 任务出队。

### 场景 3：后台任务

**用户操作**：定时任务“每晚 2 点清理临时文件”，标记为 `LOW`。

**系统行为**：

* 任务进入 `LOW` 队列，仅在无 `CRITICAL`、`HIGH`、`NORMAL` 任务时由空闲 Agent 执行。

## 5. 实施计划

### Phase 1：基础队列

* 实现 `PriorityMessageQueue` 类。
* 为消息添加 `priority` 字段。
* 修改消息分发逻辑，支持按优先级入队和出队。

### Phase 2：Agent 状态管理

* 实现 `AgentState` 状态机。
* 为 Agent 添加状态切换逻辑（IDLE ↔ WORKING）。
* 实现工作排队机制，空闲 Agent 自动从队列拉取任务。

### Phase 3：打断与恢复

* 实现任务打断机制（`interrupt()` 方法）。
* 实现任务状态持久化（保存上下文、进度）。
* 实现任务恢复（`resume()` 方法），支持安全恢复。

### Phase 4：调度器集成

* 实现 `AgentScheduler`，整合队列、状态管理和打断逻辑。
* 支持多 Agent 协同调度。
* 添加负载均衡策略（如按能力分配）。

## 6. 备选方案评估

| **方案**                     | **优点**     | **缺点**          |
| -------------------------- | ---------- | --------------- |
| **简单 FIFO 队列**             | 实现简单       | 无法满足紧急任务需求      |
| **固定优先级调度**                | 实现相对简单     | 可能导致低优先级任务饥饿    |
| **外部消息队列（Redis/RabbitMQ）** | 功能强大，持久化   | 增加部署复杂度，不适合轻量场景 |
| **本方案（多级队列 + 抢占）**         | 灵活、支持抢占和恢复 | 实现复杂度适中         |

## 7. 参考与扩展

* **设计参考**：操作系统进程调度（优先级抢占）、Kubernetes 优先级调度。
* **未来扩展**：
  * 基于 Agent 能力的智能任务分配（如 GPU 加速、语言模型）。
  * 优先级动态调整（根据任务等待时间提升优先级，防止饥饿）。
  * 任务依赖关系管理（DAG 调度）。

***

_文档版本：v1.0_

_最后更新：2026-07-11_
