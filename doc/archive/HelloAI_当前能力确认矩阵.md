# HelloAI 当前能力确认矩阵

## 1. 目的

本文档用于回答当前阶段最关键的 4 个确认问题：

1. 现在的代码，是否支持 Qoder、CLI 这类 AI Agent 自动注册
2. 现在是否能够实现多角色 Agent 自动分发、消费任务、全链路执行
3. 当前是否有兜底策略；是否具备类似死信队列、超时重分发、同角色替补能力
4. 当 4 类角色都注册后，整体任务执行流程是固定的，还是随机的

本文结论基于当前主干代码现状，不代表远期路线图目标。

参考来源与设计理念见：`doc/HelloAI_架构设计参考.md`。当前事实边界与对表结论仍以《项目基线文档》《实现差距表》为准。

## 2. 总结结论

### 2.1 一句话结论

- `Qoder / CLI` 类 Agent：**支持程序化注册与接入，但不是“平台自动发现”式接入**
- `EXECUTOR` 主链：**已基本打通**
- `PLANNER / REVIEWER / PATROL` 四角色全自动闭环：**尚未完全打通**
- 兜底策略：**已有执行超时补偿、离线重分发、同角色替补；但“消息长时间未消费后自动转派”尚未形成统一机制**
- 流程编排：**状态流固定，候选 Agent 选择不是随机，而是按规则筛选；但完整四角色自动编排尚未落地**

### 2.2 能力矩阵

| 能力项 | 当前状态 | 说明 |
| --- | --- | --- |
| CLI Agent 自注册 | 已支持 | 支持通过 `/api/agents/register-with-token` 注册，默认 `accessType=CLI_CLIENT` |
| CLI Agent 鉴权接入 | 已支持 | 注册后可持 `Bearer apiKey` 调用 `/api/agents/me/skill`、MCP/SSE/HTTP 工具 |
| API_KEY_LLM 自动执行 | 已支持 | 子任务 `ASSIGNED` 后可自动创建 `ExecutionCommand` 并由本地 consumer 执行 |
| CLI Agent 主动拉任务消费 | 已支持 | 通过 `pullTasks / ack / claimSubTask / heartbeat / reportBlocked` 等工具实现 |
| EXECUTOR 自动执行主链 | 已支持 | 调度、命令消费、执行、结果回写、超时补偿链路均已接通 |
| PLANNER 自动拆解任务 | 部分支持 | 创建任务后会通知 PLANNER，但未看到完整自动拆解编排主链 |
| REVIEWER 自动审查 | 部分支持 | 子任务进入 `REVIEW` 后有通知，但当前主流程仍依赖显式 review 提交 |
| PATROL 自动巡检链路 | 未完整支持 | 有角色、表和统计，但未看到完整 patrol 编排主链 |
| Agent 离线后同角色重分发 | 已支持 | 会重置子任务并交回弹性调度器，按同角色替补 |
| 执行超时补偿 | 已支持 | `PENDING/RUNNING` 超时会补偿为 `TIMEOUT`，必要时推进 `BLOCKED` |
| 消息未消费后的统一超时转派 | 未完整支持 | 当前没有一套对所有角色 inbox/message 的统一“超时未消费 -> 自动转派”机制 |
| MQ DLQ 基础设施 | 已有基础设施 | RabbitMQ 已配置 DLX/DLQ，但业务主链尚未全面建立在该机制上 |

## 3. 问题 1：是否支持 Qoder、CLI 这类 AI Agent 自动注册

### 3.1 结论

**支持程序化注册与接入。**

但这里的“自动注册”更准确的定义是：

- Agent 自己调用平台注册接口完成注册
- 平台给它下发 consumer token
- Agent 再用这个 token 走技能拉取、MCP 工具调用、心跳、拉任务等流程

当前代码**没有**做成“平台主动发现本地 Qoder / CLI 进程并自动纳管”的形态。

### 3.2 代码依据

1. `AgentController`
   - `POST /api/agents/register`
   - `POST /api/agents/register-with-token`
   - 注册扩展字段支持 `accessType / specializationSlug / capabilities / labels`
   - 默认 `accessType=CLI_CLIENT`

2. `AgentService.register(...)`
   - 注册时会生成 `apiKey`
   - 默认 `accessType=CLI_CLIENT`
   - 默认 `onlineStatus=OFFLINE`

3. `AgentController.getSkill(...)`
   - 注册后，Agent 可以通过 `Bearer apiKey` 拉取对应角色的 `SKILL.md`

4. `McpMcpServer` / `McpToolService`
   - 提供 `pullTasks / ack / claimSubTask / heartbeat / uploadArtifact / reportBlocked / getAgentStatus`
   - 说明 CLI Agent 可以按“自注册 -> 带 token 接入 -> 主动拉任务消费”的模式工作

### 3.3 当前边界

- 当前是**通用 CLI Agent 接入能力**
- 不是 Qoder 专属协议适配
- 只要 Qoder、Trae、Codex CLI、Claude Code 这类客户端能按平台约定调注册 API 和 MCP/HTTP 工具，就能接入

## 4. 问题 2：是否已实现多角色 Agent 自动分发、消费任务、全链路执行

### 4.1 结论

**部分实现。**

更准确地说：

- `EXECUTOR` 角色的自动执行链路已经基本成型
- 但“PLANNER -> EXECUTOR -> REVIEWER -> PATROL”四角色全自动闭环，当前代码还没有完全打通

### 4.2 已经打通的部分

#### A. API_KEY_LLM 类型 EXECUTOR 自动执行

当前 `SubTaskAutoExecutionDispatcher` 的逻辑是：

- 子任务进入 `ASSIGNED`
- 如果 `accessType=API_KEY_LLM`
- 就创建 `ExecutionCommand`
- 然后由 `LocalExecutionCommandConsumer` 异步消费
- 再进入 `SubTaskExecutionService.executeCommand(...)`
- 最终通过 `ExecutionResultHandler` 回写成功/失败结果

这条链路说明：

- 调度侧与执行侧已经拆开
- 执行结果也已经异步回写状态机
- 对 API 托管型 EXECUTOR，平台可以自动驱动执行

#### B. CLI 类型 EXECUTOR 主动拉任务执行

当前 `CLI_CLIENT` 不走平台内自动执行命令链，而是：

- 通过 MCP `pullTasks` 拉收件箱
- `ack` 确认消息
- `claimSubTask` 抢占任务
- `heartbeat` 上报存活
- `reportBlocked` 上报阻塞

也就是说：

- CLI Agent 是“外部 worker 主动拉任务”模型
- 这与 API_KEY_LLM 的“平台内触发执行”模型不同

### 4.3 尚未完全打通的部分

#### A. PLANNER

当前创建任务后，会通知所有 `PLANNER`：

- `TaskController.create(...)` 中会向所有 PLANNER 发 `task.created`

但目前看到的是：

- 有通知
- 没看到完整“PLANNER 自动拆任务 -> 自动创建 subtask -> 自动分派”的统一主链

#### B. REVIEWER

当前子任务进入 `REVIEW` 后，会发送审查通知；
但 `ReviewService` 的主入口仍然是显式 `createReview(...)`，对应 `/api/reviews`。

这说明：

- REVIEW 阶段有状态与通知
- 但不是一个完全由 REVIEWER Agent 自动消费并自动提交 review 的闭环

另外，当前 `SubTaskService` 在 `REVIEW` 通知里实际发给的是 `PLANNER`，而不是一个清晰独立的 `REVIEWER` 分发主链，这也说明 REVIEWER 自动链路尚未真正收口。

#### C. PATROL

当前仓库里有：

- `PATROL` 角色
- `patrol_record` 表
- patrol 相关统计

但未看到：

- 完整 patrol 调度入口
- patrol 自动消费主链
- patrol 完成后的状态收敛主链

因此当前不能认为 `PATROL` 已经形成完整自动执行链路。

### 4.4 当前真实判断

如果问题是：

“现在能不能实现多个 EXECUTOR 自动接活并执行？”

答案是：**可以，尤其是 EXECUTOR 主链已经可用。**

如果问题是：

“现在 4 类角色都注册后，能不能自动形成完整团队式协作闭环？”

答案是：**还不行，当前仍是部分角色能力已具备、完整多角色编排尚未最终落地。**

## 5. 问题 3：是否有兜底策略？是否类似死信队列？是否支持某角色未消费后超时重发给同角色其他 Agent

### 5.1 结论

**有一部分明确的兜底能力，但不是完整意义上的“统一死信 + 未消费超时转派框架”。**

### 5.2 当前已有的兜底能力

#### A. 执行超时补偿

`ExecutionCompensationTask` 当前会周期性扫描执行记录：

- `PENDING` 超时
- `RUNNING` 超时

处理策略：

- 将 `agent_execution_record` 标成 `TIMEOUT`
- 如果子任务仍在 `IN_PROGRESS`，则通过 `ExecutionResultHandler.handleFailure(...)` 推进到 `BLOCKED`

这说明当前有明确的**执行链超时兜底**。

#### B. Agent 离线重分发

`AgentHealthCheckTask` 会每 60 秒巡检一次：

- 超过 5 分钟无心跳，且 Redis TTL 也失效
- 则 CAS 标记 Agent 为 `OFFLINE`
- 然后把该 Agent 名下 `ASSIGNED / IN_PROGRESS` 的子任务重新交回 `SubTaskDispatchService.redispatchOfflineSubTask(...)`

这说明当前有明确的**离线 agent 重分发兜底**。

#### C. 同角色替补

`ResilientDispatcher` + `AgentSelector` 已实现同角色替补：

- 原 agent 不可用、熔断、OFFLINE、SLEEPING 时
- 在同角色 agent 中选替代者
- 过滤离线、睡眠、禁用、熔断中的 agent
- 最终按 `score` 最高优先

所以这里不是随机挑选，而是**规则化替补**。

#### D. 重复执行保护

当前执行记录具备：

- `hasPendingOrRunning(...)`
- `markRunning / markSuccess / markFailed / markTimeout` 的 CAS 状态推进

这可以避免：

- 重复发命令
- late consumer 覆盖补偿结果

### 5.3 当前 DLQ / 死信能力的真实状态

RabbitMQ 层面已经配置了：

- `executorQueue`
- `reviewerQueue`
- `plannerQueue`
- `patrolQueue`
- `DLX_EXCHANGE`
- `DLX_QUEUE`

因此从基础设施角度看：

- **DLQ 能力的底座是存在的**

但从当前业务落地来看：

- 我只看到 `NotificationConsumer` 这个真正的 `@RabbitListener`
- 没看到 executor/reviewer/planner/patrol 各自完整的 MQ 业务消费者链都已经接好

所以当前更准确的结论是：

- **MQ DLQ 基础设施已存在**
- **但业务主链还不是完全建立在“角色队列 + 失败死信 + 自动转派”之上**

### 5.4 “某个角色消息没有消费，超时后会重新下发同角色其他 AI Agent 吗？”

当前要拆成两种情况：

#### 情况 A：Agent 已离线

**会。**

如果某个角色 Agent 离线，它名下的子任务会被重新丢回调度器，并在同角色中选择替补 Agent。

#### 情况 B：Agent 没离线，但消息长时间没人消费

**当前没有看到统一、明确、通用的“消息未消费超时 -> 自动重新下发同角色其他 Agent”机制。**

当前更接近的是：

- inbox 消息可被 CLI agent 主动拉取
- outbox 事件有重试补偿
- execution command 有超时补偿
- offline agent 有重分发

但“所有角色统一 inbox/message 超时无人处理后自动转派”的那层框架，目前还没有彻底落地。

## 6. 问题 4：4 类角色都注册时，任务流程图是固定的，还是随机的

### 6.1 结论

**状态机是固定的；Agent 选择也不是随机的；但完整四角色编排当前并未完全实现。**

### 6.2 固定的部分

#### A. 子任务状态流固定

`SubTaskStateMachine` 已定义固定状态迁移：

- `PENDING -> ASSIGNED`
- `ASSIGNED -> IN_PROGRESS / BLOCKED / PENDING / CANCELLED`
- `IN_PROGRESS -> REVIEW / BLOCKED / PAUSED / CANCELLED`
- `REVIEW -> DONE / REWORK / CANCELLED`
- `REWORK -> IN_PROGRESS / CANCELLED`

也就是说，**状态流不是随机的**，而是固定状态机。

#### B. 替补 Agent 选择也不是随机

当前替补 Agent 的选择规则是：

- 同角色
- 跳过 `SLEEPING`
- 跳过 `OFFLINE`
- 跳过 `status != ACTIVE`
- 跳过熔断器已打开的 agent
- 按 `score` 最高优先

所以这也不是随机分发，而是**规则驱动分发**。

### 6.3 还不固定的部分

如果你问的是“4 个角色已经注册后，系统是否已有一张完整固定的自动流程图，例如：

`PLANNER 自动拆解 -> EXECUTOR 自动执行 -> REVIEWER 自动审查 -> PATROL 自动巡检`

”

那么答案是：

**当前还不能这么说。**

原因是：

- PLANNER 有通知，但自动拆解主链未完整收口
- REVIEWER 有 review 领域，但自动 reviewer pipeline 不完整
- PATROL 角色主链未落地

所以现在系统里固定的是：

- 状态机
- 调度/替补规则
- EXECUTOR 执行主链

而不是一个完整的“四角色固定编排流程图”。

## 7. 当前能力的准确定位

### 7.1 现在已经具备的能力

当前系统更适合这样描述：

> HelloAI 已具备面向多类型 Agent 的接入底座、EXECUTOR 主链、执行补偿与离线重分发能力；  
> 但四角色自治协作编排仍处于“部分角色能力已落地、完整团队工作流尚未收口”的阶段。

### 7.2 当前最接近的真实形态

更贴近现实的描述不是：

> “4 类角色都能全自动协作闭环”

而是：

> “系统已经能接多类型 Agent，也已经能让 EXECUTOR 自动或半自动执行任务，并在失败/超时/离线时进行一定程度的补偿与重分发。”

## 8. 仍然存在的主要差距

### 8.1 多角色编排差距

- PLANNER 未形成完整自动拆任务主链
- REVIEWER 未形成完整自动审查主链
- PATROL 未形成完整自动巡检主链

### 8.2 可靠性差距

- 尚未形成统一的“角色消息未消费超时 -> 自动转派”框架
- MQ 虽有 DLQ 底座，但角色业务消费者未全面成型

### 8.3 编排清晰度差距

- 当前状态机固定，但完整角色协作流程图尚未固化为一条唯一主路径

## 9. 建议的口径

对外或对项目当前状态，建议使用下面这套口径：

### 9.1 可以说已经支持的

- 支持 CLI / API_KEY_LLM / WEB_BROWSER 三类 Agent 接入
- 支持 CLI Agent 自注册、自带 token 接入、心跳、拉任务、确认消息
- 支持 EXECUTOR 自动执行主链
- 支持执行超时补偿
- 支持 Agent 离线后的同角色重分发

### 9.2 不建议说已经完全支持的

- 不建议说“4 类角色已形成完整自动协作闭环”
- 不建议说“所有角色消息都具备统一死信重派机制”
- 不建议说“任务流程已经完全固定为 Planner -> Executor -> Reviewer -> Patrol 自动编排”

## 10. 对这 4 个问题的最终答复

### Q1：现在的代码，是否支持 Qoder、CLI 这种 AI Agent 自动注册？

**支持程序化自注册与接入。**  
只要客户端能调用平台注册接口，并按平台要求持 token 调 MCP/HTTP 工具，就可以接入。  
但当前不是平台主动发现客户端的那种自动纳管。

### Q2：现在是否能够实现多角色 Agent 自动分发、消费任务、全链路执行？

**部分支持。**  
EXECUTOR 主链已基本打通；四角色全自动闭环还没有完全实现。

### Q3：是否有兜底策略？是否类似死信队列？是否有某个角色消息没有消费，超时后会重新下发同角色其他 AI Agent？

**有部分兜底，但不是完整统一死信重派框架。**  
已有执行超时补偿、离线重分发、同角色替补；但“任意角色消息长期未消费后统一超时转派”当前还没完全落地。

### Q4：4 类角色都有注册的话，整个任务执行流程图是固定的，还是随机的？

**状态机固定，分配规则也不是随机。**  
但完整的四角色自动编排流程目前尚未完整实现，因此不能说当前已经存在一张完全固定的四角色自动流程图。
