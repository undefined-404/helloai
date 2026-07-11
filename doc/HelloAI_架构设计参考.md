# HelloAI 架构设计参考

> 文档定位
>
> - 本文档只描述设计理念、参考来源、核心概念与目标态方向。
> - 本文档**不是**当前实现状态说明，也不作为验收或对表依据。
> - 当前项目是什么：`doc/HelloAI_项目基线文档.md`
> - 当前差距在哪里：`doc/HelloAI_实现差距表.md`
> - 本轮实际做了什么：`doc/HelloAI_迭代执行记录.md`

**版本**：2026-07-11 整理版  
**适用范围**：HelloAI 多 Agent 协作调度平台

---

## 1. 参考来源与吸收边界

HelloAI 的设计不是单一来源，而是吸收多个项目中最适合当前阶段的部分，再收口到“DB 为事实中心 + 调度与执行解耦 + 最终一致收敛”的主线上。

### 1.1 OpenMOSS

本地参考路径：`E:\workspace\openMoss\OpenMOSS-main`

重点借鉴：

- 多 Agent 平台的整体产品形态与后台结构
- Agent 自动注册、onboarding、角色接入体验
- 四角色模型（planner / executor / reviewer / patrol）
- Prompt 模板、Skills 组织与角色能力边界

适合落到 HelloAI 的部分：

- Agent 接入与自动注册体验
- PromptTemplate / Skill 内容组织
- 后台中的 Agent 管理、角色视图、接入内容生成

不直接照搬的部分：

- 基于 cron 的主循环调度模型
- “Agent 自主轮询 + 自主认领”作为唯一主链
- OpenMOSS 中与当前 Java / Spring 主线不一致的存储与调度实现

结论：

OpenMOSS 更适合作为 **Agent 接入层、角色建模层、Prompt/Skill 资产层** 的参考，而不是 HelloAI 调度内核的唯一蓝本。

### 1.2 AgentTeams-main

本地参考路径：`E:\workspace\AgentTeams-main`

重点借鉴：

- Manager / Worker 分层
- 调度只做选人、发命令、登记状态
- Worker 独立消费执行、回传结果
- `state.json / progress / result` 这类显式任务运行对象
- heartbeat / reconcile / idle timeout 驱动的最终一致收敛

适合落到 HelloAI 的部分：

- `ExecutionCommand` 作为调度与执行的边界对象
- 执行进度、执行结果、恢复快照的显式化
- Team / workflow / coordinator 方向的未来编排模型
- "调度成功"和"执行完成"解耦
- Heartbeat 七步主动巡检模式（逐任务询问 Worker 状态，而非被动等超时）
- `.processing` 标记机制：带过期时间的工作区协调锁
- `task-history.json` + `progress/` 任务恢复流

不直接照搬的部分：

- Matrix 房间、MinIO 文件同步、K8s CRD、Helm 控制面
- Manager / Worker 的容器编排与 IM 驱动交互形态

结论：

AgentTeams-main 是 HelloAI **调度内核、执行边界、状态收敛模型** 的第一参考来源。

### 1.3 Vibe-Skills-main

本地参考路径：`E:\workspace\Vibe-Skills-main`

重点借鉴：

- work model / bounded work 的任务组织方式
- late skill binding：先定义工作单元，再绑定技能
- `work_binding` 与 proof trail 的证据化交付思路
- memory plane / control plane 分层
- fallback / rollback / conflict freeze 的治理方式

适合落到 HelloAI 的部分：

- 在 `task / sub_task` 之上继续抽象工作单元与执行绑定
- 将 Prompt、Skill、执行器、记忆上下文做成显式绑定记录
- 补"验证证据 / 交付证据 / 人工复核状态"
- 后续做跨会话记忆与多轮自动协作时，坚持单一控制面
- Root/Child Authority 层级化治理模型：为后续 Team / workflow 编排提供权威边界

不直接照搬的部分：

- 大量运行时治理壳、证明文件、阶段化公共入口
- 第二套工作流运行时或第二控制面

结论：

Vibe-Skills-main 更适合作为 HelloAI 的 **工作流运行时设计参考**，尤其用于后续的工作单元建模、Skill 绑定、证据交付与记忆分层。

### 1.4 `HelloAi Agent 任务调度优先级机制设计文档`

本地参考路径：`doc/HelloAi Agent 任务调度优先级机制设计文档.md`

重点借鉴：

- 用户新输入触发的抢占式调度思想
- `CRITICAL / HIGH / NORMAL / LOW` 优先级模型
- `IDLE / WORKING / INTERRUPTED / PAUSED` 状态机雏形
- 任务暂停、恢复、插队的基本策略

适合落到 HelloAI 的部分：

- 将"用户输入"区分为普通任务与控制命令
- 引入 `STOP / PAUSE / REPLAN / RESUME / APPROVE` 等控制流
  （注：控制命令体系是在原文档 `CRITICAL / HIGH / NORMAL / LOW` 优先级模型
   与 `interrupt / resume` 机制之上的进一步抽象与扩展）
- 在执行命令层增加优先级、抢占策略、恢复快照语义

不直接照搬的部分：

- 假设所有执行器都支持立即打断且无损恢复
- 把优先级完全交给内存队列而不落 DB 状态

结论：

这份文档适合作为 HelloAI **控制命令层与抢占式调度机制** 的理论起点，但必须建立在 CAS、补偿、异步回写已经稳定的前提下推进。

### 1.5 trade-cloud

本地参考路径：`E:\yhzx\1027\trade-cloud`

重点借鉴：

- TCC / Outbox 等最终一致性模式
- 补偿、回滚、对账与兜底任务
- 状态收敛优先于“单次同步成功”的工程思路

适合落到 HelloAI 的部分：

- ExecutionCommand / Outbox 事务一体化
- 执行结果晚到、防覆盖、幂等更新
- 超时补偿、离线重分配、回执补偿、死信处理

结论：

trade-cloud 是 HelloAI **可靠性与最终一致性底座** 的主要参考来源。

---

## 2. 技术栈版本表

> 这里记录的是当前项目采用的技术栈基线，不展开实现细节。

| 组件 | 版本 / 口径 |
|---|---|
| JDK | 17 |
| Spring Boot | 3.4.x |
| Spring AI | 1.1.x |
| MyBatis-Plus | 3.5.x |
| Flyway | 10.x |
| PostgreSQL | 16 |
| Redis | 7.x |
| RabbitMQ | 3.12+ |
| 前端 | Vue 3 + TypeScript + Element Plus |

---

## 3. 核心概念定义

### 3.1 调度分离

调度层只负责：

- 选人
- 分配
- 生成执行命令

执行层只负责：

- 消费执行命令
- 执行任务
- 回传结果

结果回写层只负责：

- 推进状态机
- 记录执行结果与审计事件

### 3.2 双心跳

双心跳指：

- `last_seen_at`：判断 Agent 是否在线
- `last_active_at`：判断 Agent 是否处于活跃工作状态

它们的计算与收敛策略由在线状态模型和巡检任务共同完成。

### 3.3 熔断

当某个 Agent 持续失败、超时或不可用时，对其进行临时隔离，避免故障放大，并按“同角色 + 在线 + 分数最高优先”的规则选择替补。

### 3.4 Outbox

业务状态变更与待发送消息在同一事务中落库，再由后续消费者/补偿链路异步投递，避免“业务成功但消息丢失”的不一致。

### 3.5 TCC

TCC 在本项目中更多作为最终一致与兜底思路参考，而不是逐字照搬实现。核心启发是：

- 关键链路必须可补偿
- 失败后要能收敛回可解释状态
- 不依赖单次长链调用保证“看起来同步成功”

### 3.6 工作单元与晚绑定

工作单元指一段带明确目标、约束、验收条件和输出的 bounded work。  
晚绑定指先确定“做什么”，再绑定“用哪个 Prompt / Skill / 执行器 / 记忆上下文去做”。

### 3.7 控制命令

控制命令是高于普通任务的运行时指令，例如：

- `STOP`
- `PAUSE`
- `REPLAN`
- `RESUME`
- `APPROVE`

它们用于处理用户新输入、人工介入、抢占与恢复，不应与普通业务任务混为一类。

---

## 4. 目标态方向

### 4.1 目标态一：调度只发命令

平台调度器不再同步等待执行结果，而是只负责生成执行命令与记录状态起点。

### 4.2 目标态二：执行独立消费

外部 Agent 或平台内执行器都应作为独立执行方消费命令，而不是继续把执行逻辑绑在调度链里。

### 4.3 目标态三：结果异步回写

执行成功、失败、超时、补偿都应通过统一处理链回写到状态机与审计记录中。

### 4.4 目标态四：最终一致收敛

通过补偿任务、离线重分发、审计时间线与健康检查，保证系统能从中间失败状态回到可解释、可继续推进的状态。

### 4.5 目标态五：工作单元显式化

将“任务描述、技能绑定、执行进度、执行结果、恢复快照、证据产物”从隐式调用链中抽出，形成显式对象。

### 4.6 目标态六：用户输入可重入

当用户在任务运行中追加新输入时，系统应优先抢占控制面，再决定是：

- 直接重规划
- 发出停止或暂停命令
- 保留当前执行并等待合适时机切换

---

## 5. 后续开发思路

> 具体外部文件路径与代码模式见 `doc/HelloAI_外部项目借鉴技术细节.md`。
> 本节只保留阶段划分与重点工作方向，不再逐条列出外部文件名。

### 5.1 第一阶段：继续收口调度解耦主链

优先参考：AgentTeams-main（调度分离 + 主动心跳）+ trade-cloud（Outbox/TCC 可靠投递）

重点工作：

- 将本地 Spring 事件消费者继续收口到独立 MQ / DB poller 消费模型
- 继续削薄 `SubTaskExecutionService` 的编排职责
- 将 `ExecutionResultHandler` 固化为唯一执行结果入口
- 强化 ExecutionCommand 幂等、补偿、晚到结果防覆盖

### 5.2 第二阶段：补任务运行时能力

优先参考：Vibe-Skills-main（work model / bounded work）+ 优先级设计文档（控制命令模型）

重点工作：

- 引入工作单元、执行绑定、验证证据、交付证据
- 增加执行进度快照与恢复快照
- 设计控制命令流与优先级调度
- 将"用户新输入"纳入可重入调度体系

### 5.3 第三阶段：补协作编排能力

优先参考：AgentTeams-main（Team 委托 + 工作区协调）+ OpenMOSS（四角色模型）

重点工作：

- 工作流模板化（2~4 角色、可嵌套 Team）
- Team / coordinator / workflow 节点建模
- Planner / Executor / Reviewer / Patrol 自动协作闭环

### 5.4 第四阶段：补接入与执行器扩展

优先参考：OpenMOSS（Agent 接入与 onboarding）+ 现有 HelloAI 接入主线

重点工作：

- 浏览器型 Agent 接入
- CLI / Browser / API Key 执行器统一抽象
- PromptTemplate / Skill 内容资产进一步标准化

---

## 6. 使用边界

- 如果要看当前已实现到哪里，请不要用本文档判断，直接看《实现差距表》。
- 如果要看调度架构的最准参考，请优先看 `doc/HelloAI_调度解耦重构分析.md`。
- 如果要看 blocked / 执行链问题的诊断上下文，请看 `doc/HelloAI_执行链路架构分析.md`。
- 如果要看本轮外部项目综合吸收结论，请优先看本文第 1 节与第 5 节，而不是回到历史路线图中寻找旧方案。
- 如果要看外部项目具体借鉴的技术细节、代码模式与文件路径，请优先看 `doc/HelloAI_外部项目借鉴技术细节.md`（本文档的**技术落地补充**）。
