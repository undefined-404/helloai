# HelloAI 外部项目借鉴技术细节

> 文档定位
>
> - 本文档按**借鉴项目维度**整理，列出每个参考项目中可具体借鉴的技术细节、代码模式与文件路径。
> - 本文档是 `doc/design/HelloAI_架构设计参考.md` 的**技术落地补充**——架构参考回答"吸收什么思想"，本文档回答"具体看哪些文件、借鉴哪些模式、落到 HelloAI 哪些类"。
> - 阅读顺序：先看《架构设计参考》第 1 节了解吸收边界，再看本文档了解具体落点。

**版本**：2026-07-11
**适用范围**：HelloAI 多 Agent 协作调度平台

---

## 1. AgentTeams-main

> 本地路径：`E:\workspace\AgentTeams-main`
> 吸收定位：**调度内核 + 执行边界 + 状态收敛模型**（详见架构设计参考 §1.2）

### 1.1 调度分离：Manager 只发命令，Worker 独立消费

**参考文件**：
- `manager/agent/skills/task-management/references/finite-tasks.md` — 任务创建、分配、完成的标准流程
- `manager/agent/skills/task-management/references/worker-selection.md` — Worker 选择策略（skills 匹配 + availability 过滤）

**核心模式**：

```
Manager 接收任务 → 选 Worker → 创建 task 目录 + meta/spec → 推共享存储
→ 登记 state.json → 通知 Worker → Worker 自己拉取并执行 → 结果异步回传
```

**HelloAI 落点**：

| AgentTeams 概念 | HelloAI 对应 | 当前状态 | 后续动作 |
|---|---|---|---|
| Manager 选 Worker | `ResilientDispatcher` + `AgentSelector` | 已有 | 保持 |
| 任务描述 (meta/spec) | `ExecutionCommand` | 已有 | 继续收口为唯一调度→执行边界 |
| state.json 登记 | `sub_task.status` + `task_timeline` | 已有 | 保持 |
| Worker 独立拉取执行 | `ExecutionCommandConsumer` (目标态) | 部分落地 | 从本地 Spring 事件切到独立 MQ/DB poller |
| 结果异步回传 | `ExecutionResultHandler` | 部分落地 | 固化为唯一执行结果入口 |

**关键代码参考**（AgentTeams `finite-tasks.md`）：
- 任务命令结构：`{ task_id, type, spec: { title, description, deliverables, acceptance_criteria } }`
- 分配后不等待，只记录 `assigned_to` + `assigned_at`
- Worker 完成后的回传格式：`{ task_id, status: "completed"|"failed", result: {...}, artifacts: [...] }`

### 1.2 状态管理：state.json 作为单一事实源

**参考文件**：
- `manager/agent/skills/task-management/references/state-management.md` — 状态收敛模型
- `manager/agent/skills/task-management/scripts/manage-state.sh` — 状态操作脚本

**核心模式**：

```
state.json 是每个 task 目录下的权威状态文件，包含：
{
  "task_id": "...",
  "status": "pending|assigned|in_progress|review|done|blocked",
  "assigned_to": "worker-1",
  "created_at": "...",
  "updated_at": "...",
  "transitions": [ { "from": "pending", "to": "assigned", "at": "...", "by": "manager" } ]
}
```

**HelloAI 落点**：
- `sub_task` 表已承担类似职责（status + assigned_agent + update_time）
- `task_timeline` 表已承担 transitions 记录职责
- 差距：缺少类似 `state.json` 的**显式任务运行对象目录**（task 目录含 meta.json / spec.md / result.md / progress/ ）
- 后续可在 `agent_execution_record` 中扩展 `context` JSONB 字段承载类似信息

### 1.3 Heartbeat 七步主动巡检

**参考文件**：
- `manager/agent/HEARTBEAT.md` — 192 行，7 步巡检清单

**核心流程**（7 步）：

```
1. 检查所有登记任务的状态文件是否一致
2. 对每个 assigned/in_progress 任务，主动 ping 对应 Worker
3. Worker 无响应超过阈值 → 标记为 stale，触发 reassign
4. 检查是否有孤儿任务（Worker 已退出但任务未释放）
5. 更新全局任务看板状态
6. 对超时任务执行 escalation（通知上级/自动 block）
7. 记录心跳日志，写入 audit trail
```

**HelloAI 落点**：

| Heartbeat 步骤 | HelloAI 对应 | 当前状态 |
|---|---|---|
| 步骤 1-2：逐任务询问 Worker | `AgentHealthCheckTask` | 已有骨架，但缺少对 IN_PROGRESS 任务的**逐项主动询问** |
| 步骤 3：stale → reassign | `AgentHealthCheckTask.reassignStaleTasks()` | 已有 |
| 步骤 4：孤儿任务检测 | 无 | 待补 |
| 步骤 5：全局看板 | 前端 Dashboard | 已有 |
| 步骤 6：escalation | `SubTaskTimeoutTask` | 已有（2小时阈值，偏长） |
| 步骤 7：audit trail | `task_timeline` | 已有 |

**关键借鉴**：AgentTeams 的心跳是**主动逐个询问 Worker 状态**，而非被动等待超时。HelloAI 当前只检查 Agent 是否在线（last_seen_at），不主动询问"你手上的 IN_PROGRESS 任务还在跑吗"。这是后续需要增强的点。

### 1.4 .processing 工作区协调锁

**参考文件**：
- `manager/agent/skills/task-coordination/SKILL.md` — 含 `.processing` 标记机制

**核心模式**：

```
当 Worker 开始处理某个 task 时，在 task 目录下创建 .processing 文件：
{
  "worker_id": "worker-1",
  "started_at": "2026-07-11T10:00:00Z",
  "expires_at": "2026-07-11T10:05:00Z",   // 带过期时间
  "heartbeat": "2026-07-11T10:02:00Z"      // 定期更新
}

其他 Worker 发现 .processing 存在且未过期 → 跳过
Manager 发现 .processing 已过期 → 可强制接管
```

**HelloAI 落点**：
- 当前没有显式的"执行中锁"机制
- 可通过 `agent_execution_record` 的 `status = RUNNING` + `started_at` 实现类似效果
- 配合 Redis `SETNX` 分布式锁可增强并发安全
- 锁 Key 设计建议：`execution:lock:{subTaskId}`，TTL = 超时阈值

### 1.5 任务恢复流（task-history.json + progress/）

**参考文件**：
- `manager/agent/worker-agent/skills/task-progress/SKILL.md` — 进度记录格式与恢复流程

**核心模式**：

```
task 目录结构：
├── meta.json           # 任务元信息（创建时写入）
├── spec.md             # 任务规格说明
├── state.json          # 当前状态
├── .processing         # 执行锁（存在 = 正在执行）
├── progress/
│   ├── 001-checkout.md # 步骤 1：拉取代码
│   ├── 002-analyze.md  # 步骤 2：分析
│   └── 003-implement.md# 步骤 3：实现
├── task-history.json   # 完整操作历史
└── result.md           # 最终产出

恢复流程：
1. Worker 启动后检查自己名下的 task 目录
2. 发现 .processing 存在 → 检查是否是自己 + 是否过期
3. 是自己且未过期 → 从 progress/ 最后一步继续
4. 不是自己或已过期 → 跳过（由 Manager 重新分配）
```

**HelloAI 落点**：
- 当前没有显式的"进度快照"和"恢复上下文"
- `sub_task.context` (JSONB) 可扩展为进度存储
- `agent_execution_record` 的 `result` 字段可扩展为步骤级进度
- 这是第二阶段"补任务运行时能力"的核心工作之一

### 1.6 Team 委托模型

**参考文件**：
- `manager/agent/skills/team-management/references/team-task-delegation.md` — Team 委托流程

**核心模式**：

```
Manager 不直接管理 Worker，而是：
Manager → 选 Leader → Leader 拆解 subtask → Leader 分配 → Worker 执行
                                ↓
                          Leader 汇总结果 → 上报 Manager
```

**HelloAI 落点**：
- 当前只有单层调度（Manager → Agent），没有 Team/Leader 层级
- `task` → `sub_task` 已在 DB 建模，但缺少 Leader 角色和委托逻辑
- 这是第三阶段"补协作编排能力"的工作

### 1.7 Worker 生命周期管理

**参考文件**：
- `manager/agent/skills/worker-management/references/lifecycle.md` — Worker 生命周期

**核心模式**：

```
Worker 状态：idle → busy → idle（循环）
- ensure-ready：Manager 在分配前确认 Worker 可用
- idle auto-stop：空闲超过阈值自动休眠，释放资源
- wake-on-task：有新任务时自动唤醒
```

**HelloAI 落点**：
- 当前 `online_status`（ONLINE/OFFLINE/SLEEPING）已部分覆盖
- `SLEEPING` 状态的自动唤醒与休眠逻辑尚未完整实现
- `AgentHealthCheckTask` 可扩展 idle 检测与自动回收

---

## 2. Vibe-Skills-main

> 本地路径：`E:\workspace\Vibe-Skills-main`
> 吸收定位：**工作流运行时设计参考**（详见架构设计参考 §1.3）

### 2.1 6 阶段状态机（VCO Runtime）

**参考文件**：
- `protocols/runtime.md` — 324 行，完整的状态机契约

**核心模式**：

```
skeleton_check → deep_interview → requirement_doc → xl_plan → plan_execute → phase_cleanup
```

每个阶段有明确的：
- 进入条件（gate）
- 产出物（artifact）
- 退出标准（exit criteria）
- 失败回退路径（rollback）

**HelloAI 落点**：
- HelloAI 当前没有任务级阶段状态机（只有 `SubTaskStatus` 9 状态）
- 这个 6 阶段模型可作为后续"工作流模板"中各阶段的参考
- 尤其 `phase_cleanup` 阶段的设计思路（验证产出 + 关闭资源 + 写回证据）值得引入

### 2.2 Late Skill Binding（晚绑定）

**参考文件**：
- `SKILL.md` — 246 行，governed runtime entry contract

**核心模式**：

```
先定义 Work Unit（做什么），再绑定 Skill（怎么做的能力）。
绑定是运行时的，不是注册时的。

Work Unit = { goal, scope, constraints, DoD, verification }
Skill = { capabilities, tools, prompts, memory_context }
Binding = runtime match(Work Unit, available Skills)
```

**HelloAI 落点**：
- 当前 Prompt 是在 `ApiKeyAgentExecutor.execute()` 时拼装的
- 缺少显式的 Work Unit 模型和 Skill 绑定记录
- `PromptTemplateService.getSkillForAgent()` 是最接近 Skill 绑定的入口
- 后续可在 `agent_execution_record` 中记录"本次执行使用了哪个 Skill/Prompt 版本"

### 2.3 Task Contract 结构

**参考文件**：
- `protocols/team.md` — 542 行，XL 多 Agent 编排协议

**Task Contract 核心字段**：

```
{
  "goal": "明确的一句話目标",
  "scope": { "in": [...], "out": [...] },
  "definition_of_done": ["可验证的条件1", "条件2"],
  "verification": { "method": "test|review|manual", "evidence": [...] },
  "dependencies": [...],
  "estimated_effort": "M|L|XL"
}
```

**HelloAI 落点**：
- `sub_task` 表已有 `deliverable`、`acceptance` 字段可对标 DoD/verification
- 缺少 `scope`（明确边界）和 `estimated_effort`（工作量估算）
- 可在 `sub_task.context` JSONB 中扩展这些字段

### 2.4 Root/Child Authority 层级治理

**参考文件**：
- `protocols/team.md` — Root 与 Child 的权限边界定义

**核心模式**：

```
Root Agent 拥有：
- 任务最终审批权
- 资源分配权
- Child Agent 的注册/撤销权

Child Agent 拥有：
- 任务执行权
- 子任务建议权
- 异常上报权

边界：Child 不能修改 Root 的决策；Root 不能直接操作 Child 的执行上下文
```

**HelloAI 落点**：
- 当前只有角色（PLANNER/EXECUTOR/REVIEWER），没有层级
- 这是第三阶段"Team 编排"中需要引入的权限模型

### 2.5 3-Tier Memory（记忆分层）

**参考文件**：
- `README.md` — 项目概述中的记忆架构描述

**核心模式**：

```
Tier 1 — 会话记忆（Session Memory）：单次任务上下文，任务结束即释放
Tier 2 — 工作记忆（Working Memory）：跨任务但同项目，项目周期内有效
Tier 3 — 长期记忆（Long-term Memory）：跨项目持久化，知识积累
```

**HelloAI 落点**：
- 当前只有 Tier 1（`sub_task.context` + Redis 对话上下文）
- `conversation_message` 表可扩展为 Tier 2
- Tier 3 暂无对应实现

### 2.6 M/L/XL 工作分档 + Degraded Mode

**参考文件**：
- `protocols/runtime.md` — 工作分级与降级策略

**核心模式**：

```
M (≤30 min)：直通执行，不启动计划阶段
L (30 min - 3 hr)：标准 6 阶段流程
XL (>3 hr)：Team 模式，多 Agent 协作

Degraded Mode：当资源不足时，自动降级——
XL → L（单人串行），L → M（跳过部分验证）
```

**HelloAI 落点**：
- 当前没有工作分级概念
- 可在 `task` / `sub_task` 中增加 `effort_size` 字段
- Degraded Mode 可作为熔断降级的下一层扩展

---

## 3. OpenMOSS

> 本地路径：`E:\workspace\openMoss\OpenMOSS-main`
> 吸收定位：**Agent 接入层 + 角色建模层 + Prompt/Skill 资产层**（详见架构设计参考 §1.1）

### 3.1 Agent 自注册与 Onboarding

**参考模块**（OpenMOSS 源码目录结构可能已变化，以下为职责级描述）：

- Agent 注册端点（`routers/agents` 模块）：处理 Agent 注册的 HTTP 端点，含角色白名单校验
- Agent 注册服务（`services/agent_service` 模块）：注册核心逻辑——名称去重、UUID 生成、API Key（`"ak_" + secrets.token_hex(16)`）生成、DB 写入
- Onboarding 指引：`prompts/tool/` 目录下的 agent-onboarding 类文件，定义首次注册时的引导内容

**核心模式**（Python → Java 映射）：

```python
# OpenMOSS: agent_service 模块注册逻辑（伪代码）
def register_agent(db, name, role, description):
    # 1. 角色白名单校验: planner/executor/reviewer/patrol
    # 2. 名称重复检查 (DB 唯一约束兜底)
    # 3. 生成 UUID 作为 Agent ID
    # 4. 生成 API Key: "ak_" + secrets.token_hex(16)
    # 5. 创建 Agent: id, name, role, description, status="active", api_key, score=0
    # 6. db.commit()
```

**HelloAI 已实现** (`AgentService.register()`)：完全对应，无需改动。

**Onboarding 差异**：
- OpenMOSS：返回纯文本引导 + API Key
- HelloAI：`AgentOnboardingDialog` 弹窗 + `GET /api/admin/agents/{id}/onboarding-content`（已实现）

### 3.2 四角色模型

**参考文件**：
- `prompts/templates/task-planner.md` (140 行)
- `prompts/templates/executor.md` (107 行)
- `prompts/templates/task-reviewer.md` (75 行)
- `prompts/templates/task-patrol.md` (83 行)

**核心设计**：

```
PLANNER：接收顶层任务 → 拆解为子任务 → 分配给 EXECUTOR → 跟踪进度
EXECUTOR：认领子任务 → 执行 → 提交结果 → 等待审查
REVIEWER：审查结果 → 通过(DONE) / 驳回(REWORK)
PATROL：定期扫描 → 发现异常 → 上报 BLOCKED → 通知 PLANNER
```

**HelloAI 落点**：
- HelloAI 已收敛为三角色（PLANNER/EXECUTOR/REVIEWER，通过 `AgentRole` 枚举和 SKILL.md 文件建模）；PATROL 已移除，其兜底目标由重分配熔断、死信池与定时补偿任务覆盖
- EXECUTOR 自动执行链路已成型
- PLANNER 自动拆解 + REVIEWER 自动审查尚未完整闭环

**Prompt 模板借鉴**：OpenMOSS 的角色 Prompt 模板（上述 4 个 .md 文件）可作为 HelloAI 当前 `resources/skills/{role}/SKILL.md` 的内容参考，尤其是：
- PLANNER 的拆解策略描述
- REVIEWER 的审查标准分级

### 3.3 CLI 工具模式（task-cli.py）

**参考文件**：
- `skills/task-cli.py` — 847 行，完整的 CLI 工具

**CLI 命令模式**（可借鉴的命令结构）：

```
register --name xx --role xx --token xx
rules
task create|list|get|edit|status|cancel
st create|list|get|mine|available|latest|claim|start|submit|edit|cancel|block|session|reassign
score me|logs|agent-logs|leaderboard|adjust
log create|mine|list
agents [--role xx]
notification
update
```

**HelloAI 已有**：`helloai-core/src/main/resources/scripts/task-cli.py`（对外下载入口：`/api/tools/cli`，由 ToolsController 提供）
**差距**：HelloAI 的 CLI 命令集不如 OpenMOSS 完整，后续可逐步补齐

### 3.4 规则合并（Rules Merging）

**参考模块**（OpenMOSS 源码目录结构可能已变化，以下为职责级描述）：

- 规则合并端点（`routers/rules` 模块）：对外暴露规则合并 API，含 CLI 版本检查（`cli_version < latest → update_available=true`）
- 规则合并服务（`services/rule_service` 模块）：实现全局规则 + 任务规则 + 子任务规则 + 模块规则的合并优先级逻辑

**核心模式**：

```
合并优先级：全局规则 + 任务规则 + 子任务规则 + 模块规则
CLI 版本检查：cli_version < latest → 返回 update_available=true
```

**HelloAI 已有**：`RulesController.getMergedRules()` + `RuleService`
**差距**：缺少 CLI 版本检查字段（`update_available` / `latest_version`）

---

## 4. HelloAi Agent 任务调度优先级机制设计文档

> 本地路径：`doc/design/HelloAi Agent 任务调度优先级机制设计文档.md`
> 吸收定位：**控制命令层 + 抢占式调度机制的理论起点**（详见架构设计参考 §1.4）

### 4.1 PriorityMessageQueue 多级队列

**参考章节**：§3.2

**核心模式**：

```python
class PriorityMessageQueue:
    _queues: Dict[MessagePriority, asyncio.Queue]  # 每个优先级一个独立队列
    put(message, priority)  # 按优先级入队
    get()                    # 按优先级出队（CRITICAL → HIGH → NORMAL → LOW）
```

**HelloAI 落点**：
- 当前 RabbitMQ 是按角色分队列（executor/reviewer/planner），不是按优先级
- 要实现优先级，可在 `agent_execution_record` 或 `ExecutionCommand` 中增加 `priority` 字段
- 消费端按优先级排序拉取（而非 FIFO）
- 注意：不要把所有优先级消息都塞进一个队列（那会退化成 FIFO），每个优先级独立队列或消费端排序

### 4.2 Agent 执行状态机（IDLE/WORKING/INTERRUPTED/PAUSED）

**参考章节**：§3.3

**核心模式**：

```
IDLE → WORKING（接收任务）
WORKING → INTERRUPTED（被 CRITICAL 打断）
INTERRUPTED → WORKING（恢复原任务）
WORKING → PAUSED（手动暂停）
PAUSED → WORKING（手动恢复）
WORKING → IDLE（任务完成）
```

**HelloAI 落点**：
- 当前 `SubTaskStatus` 已有 PAUSED，但这是**任务状态**，不是**Agent 运行时状态**
- 文档建议引入独立的 Agent 执行状态（IDLE/WORKING/INTERRUPTED），但基线文档 §5 已明确约束：优先通过查询推导而非新增 DB 枚举，避免与 `online_status` 形成双套状态体系
- 建议：通过 `agent_execution_record` 的聚合查询推导 Agent 当前执行状态（如：有 RUNNING 记录 → WORKING，无 → IDLE）

### 4.3 打断与恢复机制

**参考章节**：§3.4

**核心模式**：

```python
class Agent:
    _current_task: Optional[Task]
    _paused_task: Optional[PausedTask]  # 保存被打断任务的完整上下文

    async def interrupt(new_message):
        # 1. 保存当前任务状态（消息、上下文、进度）
        self._paused_task = PausedTask(message=..., context=..., progress=...)
        # 2. 取消当前任务
        await self._current_task.cancel()
        # 3. 立即执行新任务
        await self.execute(new_message)

    async def resume():
        # 从 _paused_task 恢复上下文继续执行
        await self.execute(message, resume_context=context)
```

**HelloAI 落点**：
- 当前 `SubTaskStatus.PAUSED` 支持暂停/恢复，但缺少**保存 + 恢复执行上下文**的机制
- `PausedTask` 概念可通过 `sub_task.context` JSONB 存储（保存打断前的 LLM 对话上下文、工具调用栈等）
- **注意**：底层 LLM HTTP 调用无法真正"中断"（已发出的 HTTP 请求只能等超时），所以 `interrupt` 更多是"标记打断 + 忽略旧结果 + 发起新调用"
- 详见架构设计参考 §1.4 的"不直接照搬的部分"

### 4.4 控制命令体系

**参考章节**：§3（整体设计思路的扩展）

**核心命令**（从优先级模型抽象而来）：

```
STOP    — 停止当前任务，不回写结果
PAUSE   — 暂停当前任务，保存上下文
REPLAN  — 重新规划（PLANNER 重新拆解子任务）
RESUME  — 恢复暂停的任务
APPROVE — 审批通过当前阶段
```

**HelloAI 落点**：
- 当前只有 `SubTaskStatus.PAUSED` 支持暂停（通过 `/api/sub-tasks/{id}/pause`）
- STOP / REPLAN / RESUME / APPROVE 尚未作为独立控制命令实现
- 建议在 `ExecutionCommand` 中增加 `commandType` 字段区分"执行命令"和"控制命令"

---

## 5. trade-cloud

> 本地路径：`E:\yhzx\1027\trade-cloud`
> 吸收定位：**可靠性与最终一致性底座**（详见架构设计参考 §1.5）

### 5.1 Outbox 事务性消息模式

**核心模式**：

```
业务操作与事件写入同一本地事务：
BEGIN TX
  UPDATE business_table SET status = 'DONE' WHERE id = ?
  INSERT INTO outbox (event_id, event_type, payload, status) VALUES (?, ?, ?, 'PENDING')
COMMIT

异步发送：
Scheduler 定时扫描 outbox WHERE status = 'PENDING'
→ 发送 MQ → UPDATE outbox SET status = 'SENT'
→ 发送失败 → 重试（指数退避，最多 N 次） → status = 'FAILED' → 人工介入
```

**HelloAI 对应**：
- `AgentOutboxService.createEvent()` — 已在事务内写入 outbox 事件
- `AgentEventCompensationTask` — 定时扫描 PENDING 补偿发送
- 消费者幂等通过 `MessageDeduplicationService`（Redis + DB 双重去重）

**已对齐程度**：高。核心 Outbox 模式已落地。

### 5.2 TCC（Try-Confirm-Cancel）思想借鉴

**核心模式**：

```
Try：预留资源（如：标记 sub_task 为 ASSIGNED，锁定 Agent）
Confirm：确认执行（如：Agent 执行成功 → REVIEW）
Cancel：释放资源（如：Agent 超时 → 释放锁定 → 重新分配）
```

**HelloAI 对应**：
- `ASSIGNED → IN_PROGRESS` = Try
- `IN_PROGRESS → REVIEW` = Confirm
- `IN_PROGRESS → BLOCKED` + `Reconcile` = Cancel/补偿

**差距**：
- 当前 Cancel 路径不够完善（如中间状态没有显式"释放"操作，依赖超时巡检兜底）
- 建议：在状态机增加显式的"资源释放"步骤，尤其在 `BLOCKED → PENDING`（重分配）路径

### 5.3 补偿与对账

**核心模式**：

```
定时对账任务：
1. 扫描所有"中间状态"记录（PENDING/RUNNING/IN_PROGRESS）
2. 对每条记录执行"期望状态 vs 实际状态"比对
3. 不一致 → 执行补偿操作（重试/标记失败/通知）
4. 补偿操作必须幂等（可重复执行不产生副作用）
```

**HelloAI 对应**：
- `ExecutionCompensationTask`：扫描 PENDING/RUNNING 超时执行记录
- `SubTaskTimeoutTask`：扫描 IN_PROGRESS 超时子任务
- `AgentHealthCheckTask`：扫描离线 Agent 并重分配其任务
- `Reconcile`：任务状态收敛

**差距**：
- 缺少统一的"执行状态对账"视角（执行记录状态 vs 子任务状态 vs Agent 状态的交叉校验）
- `AgentHealthCheckTask` 缺少对 IN_PROGRESS 任务的主动询问（见 §1.3）

### 5.4 防晚到结果覆盖

**核心模式**：

```
执行结果回写时：
1. 检查 sub_task.version（乐观锁）或 execution_record.id + status
2. 如果当前状态已不是 IN_PROGRESS（如已被 block/timeout 补偿推进）→ 丢弃晚到结果
3. 记录"late arrival"日志但不回写状态
```

**HelloAI 对应**：
- `sub_task.version`（`@Version` 乐观锁）已提供基础保护
- `ExecutionResultHandler.handleSuccess/handleFailure` 应加强"状态前置校验"
- 建议：在 `handleSuccess` 中先检查 `sub_task.status == IN_PROGRESS`，不是则记录日志后返回

---

## 6. 借鉴优先级速查表

| 借鉴来源 | 借鉴项 | HelloAI 落点 | 优先级 | 状态 |
|---|---|---|---|---|
| AgentTeams §1.1 | 调度分离（命令边界） | `ExecutionCommand` + `ExecutionCommandConsumer` | P0 | 进行中 |
| AgentTeams §1.3 | Heartbeat 主动巡检 | `AgentHealthCheckTask` 增强 | P0 | 待补 |
| trade-cloud §5.1 | Outbox 事务性消息 | `AgentOutboxService` | P0 | 已落地 |
| trade-cloud §5.4 | 防晚到结果覆盖 | `ExecutionResultHandler` 增强 | P0 | 待补 |
| AgentTeams §1.4 | .processing 工作区锁 | `agent_execution_record` + Redis 锁 | P1 | 待补 |
| AgentTeams §1.5 | 任务恢复流 | `sub_task.context` + progress 快照 | P1 | 待补 |
| 优先级文档 §4.2 | Agent 执行状态 | 查询推导（不新增 DB 枚举） | P1 | 待设计 |
| Vibe-Skills §2.2 | Late Skill Binding | `agent_execution_record` 记录 Skill 版本 | P2 | 待设计 |
| Vibe-Skills §2.3 | Task Contract | `sub_task.context` 扩展 scope/effort | P2 | 待设计 |
| 优先级文档 §4.3 | 打断与恢复 | `sub_task.context` 存 PausedTask | P2 | 待设计 |
| OpenMOSS §3.2 | 四角色 Prompt 模板 | `resources/skills/{role}/SKILL.md` 内容参考 | P2 | 可增强 |
| AgentTeams §1.6 | Team 委托模型 | `task` → `sub_task` 层级扩展 | P3 | 远期 |
| Vibe-Skills §2.4 | Root/Child Authority | Team 权限模型 | P3 | 远期 |
| Vibe-Skills §2.1 | 6 阶段状态机 | 工作流模板的阶段参考 | P3 | 远期 |

---

## 7. 使用说明

- 本文档配合 `doc/design/HelloAI_架构设计参考.md` 使用：架构参考给方向，本文档给具体文件路径和代码模式
- 每次从外部项目借鉴新内容时，先更新本文档的对应章节，再更新架构参考的吸收边界
- 本文档中的"HelloAI 落点"描述的是**目标态落点**，不代表当前已实现；当前实现状态以《实现差距表》为准
- 本文档不替代《调度解耦重构分析》——后者的重点是 HelloAI 内部类的职责拆解与迁移路径，本文档的重点是外部参考的具体技术细节
