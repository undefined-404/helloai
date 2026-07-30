# HelloAI 架构设计参考

> 文档定位
>
> - 本文档只描述设计理念、参考来源、核心概念与目标态方向。
> - 本文档**不是**当前实现状态说明，也不作为验收或对表依据。
> - 当前项目是什么：`doc/HelloAI_项目基线文档.md`
> - 当前差距在哪里：`doc/HelloAI_实现差距表.md`
> - 本轮实际做了什么：`doc/log/HelloAI_迭代执行记录.md`

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
- 角色模型（OpenMOSS 原生为 planner / executor / reviewer / patrol 四角色；HelloAI 已收敛为 planner / executor / reviewer 三角色，patrol 的兜底目标由重分配熔断、死信池与定时补偿任务覆盖）
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

本地参考路径：`doc/design/HelloAi Agent 任务调度优先级机制设计文档.md`

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

### 3.8 外部 Agent 三层可用性模型

外部 Agent（`CLI_CLIENT` / `WEB_BROWSER`）与平台内 LLM（`API_KEY_LLM`）采用**不同的可用性判定模型**，不能用同一套规则衡量。

外部 Agent 三层（自下而上，缺一不可）：

- **上班打卡（duty lease）— 业务准入**：决定 Agent 是否进入调度候选池，是"愿不愿意接单"的显式表达。未打卡不参与分配。这是外部 Agent 可被调度的第一步。
- **长连接（TCP / WebSocket / SSE 门铃）— 通信通道**：让平台能在任务发布时**实时唤醒** Agent，而非依赖 Agent 端定时轮询。
- **双心跳（`last_seen_at` / `last_active_at`）— 运行时健康监控**：验证 Agent 是否在正常干活、能否及时完成任务；异常时触发熔断与重分配。它是"确保任务及时完成"的监控工具，不是准入条件。

`API_KEY_LLM` **不适用**上述三层：

- 无需打卡：平台主动调用，不存在"接单意愿"
- 无需长连接：平台侧同步 / 异步直接触发
- 无需双心跳：无常驻连接；其可用性由"任务是否按时完成 + 定期 API Key 可用性探测"保证

一句话：**打卡 = 入场券，长连接 = 电话线，双心跳 = 体检仪。外部 Agent 三者都要，`API_KEY_LLM` 三者都不要。**

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

### 4.7 目标态七：外部 Agent 协作协议（MCP）

外部 Agent（Qoder / Trae / Codex / Claude Code 等）应被视为平台的第一公民执行器，但它们不应直接绑定平台内部基础设施（RabbitMQ、表结构、内部 DTO）。

统一边界：

- 平台对外只暴露“任务协议层”，默认采用 MCP-over-SSE（并可保留 REST/JSON-RPC 兼容通道作为调试入口）
- 外部 Agent 只消费平台协议，不直接消费 RabbitMQ，也不直接写 DB
- 平台保持 DB 为状态中心：状态机推进、审计时间线、补偿收敛仍以 DB 为权威

外部 Agent 的最小闭环工具语义（以能力而非具体实现为准）：

- pull：拉取待处理任务消息（不自动标记已读，避免拉取后崩溃造成消息丢失）
- ack：确认消息已处理（幂等）
- claim：原子认领任务（幂等 / 并发安全）
- heartbeat：心跳与存活声明（用于在线态与租约续期）
- progress：上报执行进度（可选，但推荐；用于可观测与协作）
- result：上交最终结果（必需；必须幂等，避免重复提交污染状态）
- blocked：上报阻塞与原因（必需；用于触发协作排障）
- artifact：登记或上传交付物元数据（可选；用于证据化交付与审计）

核心约束：

- 外部 Agent 只能“上报事实”（progress/result/blocked/artifact），不能直接推进平台状态机；状态推进必须由平台结果回写层统一处理
- 任务认领必须是显式的（claim），并在 DB 侧可审计；平台可基于租约超时回收并重分配
- 结果上交必须具备幂等键（例如 eventId / resultId / recordId 之一），避免晚到结果覆盖新状态
- 外部 Agent 若参与执行链，必须满足“在线且当前无执行中任务”这类最小可调度条件；否则不应进入优先分配集合

实时性边界补充：

- `MCP-over-SSE` 在 HelloAI 中首先是**协议传输层**，用于建立会话、完成鉴权并承载 `tools/call`；它本身不应被等同为“平台已经具备服务端主动任务推送语义”
- 当前外部 Agent 最小闭环仍以 `pull -> ack -> claim -> result/blocked` 为核心；这条链路能保证消息不易因客户端崩溃而丢失，但不能单独保证“任务一到达，第三方 Agent 就一定能第一时间响应”
- 双心跳（`last_seen_at` / `last_active_at`）与在线态收敛，只能证明外部 Agent 处于在线或活跃窗口，不能证明它当前正处于“可被平台立即唤醒并立刻接活”的状态
- 因此，外部 Agent 接入层需要显式承认一个事实：**在线 ≠ 可及时响应 ≠ 可被即时唤醒**
- 对支持长期保持 `MCP-over-SSE` 连接、并能在收到平台事件后自主进入工具调用的客户端，额外通知通道可以不必引入
- 对仅支持 stdio、依赖用户交互触发、或缺乏后台常驻形态的第三方 Agent，后续大概率仍需要补一层“本地 Bridge / Daemon + 通知通道”能力；其职责是接收平台通知，再在本机触发 MCP Client / CLI / 脚本入口，而不是让第三方 Agent 直接接入 RabbitMQ 或直接写平台 DB
- 该通知通道的实现可以是 TCP、WebSocket、SSE 长连接或其他可维持会话的轻量协议；**真正的设计重点不是 TCP 本身，而是 Bridge 作为接入层扩展的必要性**
- 无论未来 Bridge 最终采用哪种通知协议，MCP 仍然负责“能力接口与结果上报”，通知通道只负责“门铃 / 唤醒”，二者不互相替代

### 4.8 目标态八：双执行面分工（平台内执行 vs 平台外执行）

平台需要同时支持两类执行面，并保持“调度只发命令、执行独立消费、结果异步回写”的统一哲学不变：

- 平台内执行（API_KEY_LLM）：平台可直接触发执行（ExecutionCommand + Consumer），适合作为兜底执行器与快速闭环能力
- 平台外执行（CLI_CLIENT）：外部 Agent 通过 MCP 协议拉取并执行，适合作为强执行器与团队型协作执行面

统一收敛点：

- 不论执行面来源如何变化，执行结果都应回到同一结果回写层，以保证状态机与审计口径一致
- MQ 作为平台内部解耦手段可逐步主线化；外部执行面始终只依赖协议层而非 MQ

调度分配策略：

- 后续即使引入流程图 / workflow 节点，节点最终仍应落到“角色”而不是绑定某个具体 Agent 实例
- 在同一角色下，优先分配外部 Agent（Qoder / Trae / Codex 等 CLI_CLIENT），前提是：
  - Agent 在线
  - Agent 当前无执行中任务
  - Agent 能力满足当前子任务要求
- 这里的“可分配”默认只代表满足平台侧最小调度条件；若某类外部 Agent 无法被平台及时唤醒，则仍需接入层补 Bridge / 通知通道，不能把“已在线”误判为“已具备实时消费能力”
- 平台内 `API_KEY_LLM` 作为同角色保底执行器存在，不与外部 Agent 抢首选位；它主要承担兜底、补位与快速收敛职责
- 工程落地建议：通过 `helloai.dispatch.*` 提供策略开关（如外部优先、空闲优先、强制接入类型用于纯 LLM 回归、创建时是否自动分配），避免调度策略散落在业务代码中

降级与保底策略：

- 子任务一旦已经分配给外部 Agent，就应先给予其独立执行与回传结果的机会，不应在调度链内部同步等待
- 只有当外部 Agent 在执行阶段出现超时、掉线、租约失效、连续失败等情况，并达到预设阈值后，系统才自动重分配给平台内 `API_KEY_LLM`
- 这种“外部强执行器优先，平台内 LLM 保底接管”的模式，是 HelloAI 的默认目标态，而不是例外分支
- 浏览器型 Agent 不进入该保底链主路径；它主要承担 plan / research / 辅助类职责
- 若后续为 stdio / 用户驱动型外部 Agent 增加 Bridge / Daemon，该能力也应被视为“平台外执行面的接入层增强”，而不是调度内核或 MQ 主链的一部分

### 4.9 目标态九：浏览器型 Agent 的定位

浏览器型 Agent（WEB_BROWSER）应优先承接“规划与轻执行”类工作，而非默认进入强执行链路：

- 更适合：plan 拆解、信息整理、轻量 research、辅助审核与补全证据
- 不适合：强依赖登录态、反爬验证、稳定上传与长时间后台执行的核心执行任务

---

## 5. 后续开发思路

> 具体外部文件路径与代码模式见 `doc/design/HelloAI_外部项目借鉴技术细节.md`。
> 本节只保留阶段划分与重点工作方向，不再逐条列出外部文件名。

### 5.0 开发优先级校准（战略拍板）

> 本节记录一次显式的优先级调整，用于校准 §5.1~§5.4 的推进顺序。若与后续阶段文字冲突，以本节为准。

平台的两条价值主线：

- **LLM 内部自循环**（Planner 解析 → 拆分子任务 → 发布 → Executor 执行 → 完成）：这是成熟红海，业界（含各类 Agent Team 框架）已有大量方案。
- **外部 AI Agent 与平台的实时协作**（用户下发任务 → MQ 发布 → 长连接门铃通知外部 Agent → 外部 Agent 按注册时下发的 skills，经 MCP 拉取 / 消费 / 执行 / 反馈）：这是蓝海，几乎没有"一个 AI 产品调用另一个 AI 产品"的现成平台或源码，是 HelloAI 最独特的价值点。

**优先级结论：外部 AI Agent 实时协作闭环 = 最高优先级；Planner 用 LLM 自动拆解子任务 = 暂缓。**

理由与就绪度：

- 两者**正交**：外部 Agent 执行闭环不依赖 Planner 自动拆解——过渡期可用人工 / 简单规则创建 `Task` / `SubTask`，直接喂给外部 Agent 执行。
- 闭环的两端已就绪：**入口侧**上班打卡（AgentHub V1 `agent_duty_lease` + `checkIn/checkOut`，见差距表 N12）已交付；**出口侧**超时回退 `API_KEY_LLM`（差距表 N11 阈值回退）已交付。
- 唯一缺失的硬骨头是**中间的长连接门铃通知**（AgentHub V3 门铃层 + V2 轻量 Bridge）：把"MQ 发布任务 → 实时唤醒外部 Agent → Agent 走 MCP 主线消费执行"的响应时延从轮询级（0~30s）降到秒级，替代业界常见的 30 秒定时轮询。

最小快速实现路径（按此顺序推进）：

1. 外部 Agent 长连接门铃通知通道（V3 门铃层，只负责唤醒，不替代 MCP 能力接口）——最小技术方案见 `doc/archive/HelloAI_门铃通知通道设计.md`
2. 打通「打卡上班（V1 已交付）→ MQ 发任务 → 门铃唤醒 → 外部 Agent 经 MCP 消费并执行 → 结果进统一回写链」
3. 超时 / 掉线 / 租约失效达阈值 → 回退 `API_KEY_LLM` 兜底（N11 已交付，仅需与门铃链对接）
4. **暂缓**：Planner 用 LLM 自动解析并拆分子任务（保持人工 / 简单规则过渡，待外部 Agent 闭环稳定后再回补，对应 §5.2 / §5.3）

不变的边界：门铃通知通道只负责"唤醒"，MCP 仍是唯一任务协议层；外部 Agent 不直接消费 RabbitMQ、不直接写 DB、不直接推进状态机（与 §4.7 一致）。

### 5.1 第一阶段：继续收口调度解耦主链

优先参考：AgentTeams-main（调度分离 + 主动心跳）+ trade-cloud（Outbox/TCC 可靠投递）

重点工作：

- 完成 DB Poller 主消费载体后，推进“MQ 主链路 + DB 状态中心 + Poller 兜底恢复”的演进形态
- 继续削薄 `SubTaskExecutionService` 的编排职责
- 将 `ExecutionResultHandler` 固化为唯一执行结果入口
- 强化 ExecutionCommand 幂等、补偿、晚到结果防覆盖
- **Phase 2D / 2E / 2F 已完成项**：MQ 主链路的“生产→消费”椅骨已全部接上，默认零行为变化：`MqExecutionCommandConsumer` + `ExecutionCommandMqPublisher` 共用 `ExecutionCommandConsumer` 接口；topology（`EXECUTION_COMMAND_QUEUE` / `EXECUTION_COMMAND_EXCHANGE` / binding 与 DLX 复用）由 `RabbitMQConfig` 统一声明；由 `AgentExecutionProperties.dispatch-mode`（`NONE / EVENT / MQ / BOTH`，默认 `NONE`）控制分发，`MqExecutionCommandProperties.{producer-enabled, consumer-enabled}` 分别控制 Publisher / Consumer 注册，支持独立灰度；`ExecutionDispatchValidator` 启动期 fail-fast。**Phase 2F 修正两个阻断性问题：**（a）Publisher 将投递挂到 `TransactionSynchronization.afterCommit()`，与本地事件 `@TransactionalEventListener(AFTER_COMMIT)` 语义对齐，避免“事务未提交先发消息”与“回滚后消息已发”；（b）Publisher 改为显式 `ObjectMapper.writeValueAsBytes` + `rabbitTemplate.send(Message)`，与消费端 `readValue(byte[])` 完全对称，不依赖默认 SimpleMessageConverter，也不侵入全局 `RabbitTemplate` converter。下一轮路线拍板见下方独立段落。

**MQ 主链路线拍板（按差距表 N6 与迭代记录 Phase 2G 状态更新；①②③ 严格依赖，不并列）**：

- ✅ **① E2E 冒烟**（已通过）：`dispatch-mode=BOTH` + `producer-enabled=true` + `consumer-enabled=true` 跑通，Redis + DB 双层幂等对 "本地事件 × MQ" 双消费的抵消能力得到验证；详细证据见差距表 N6 与迭代记录 Phase 2G
- ⏳ **② 生产端可靠投递**（前提：① 已通过 ✅）—— **拆为两小步**：
  - **②a 最小闭环** —— `ExecutionCommand` 与 Outbox 同事务写入，`OutboxRelay` 负责 MQ 投递（替代业务服务直接调用），`ExecutionCommandMqPublisher` 退化为 `OutboxRelay` 使用的底层发送器；outbox 表维护 `PENDING / SENT / FAILED` 三态
  - **②b Confirm / Retry** —— 接入 `CorrelationData` + publisher-confirms 回执；outbox 状态机扩为 `PENDING / SENT / CONFIRMED / FAILED / retry_count / next_retry_at`；新增 `OutboxCompensationTask` 扫描重发；跑一轮 RabbitMQ E2E，从 "能发" 验证到 "失败可恢复"
- 🔒 **③ Poller 职责重定位 + 消费侧回写链路改造**（前提：②a ②b 已稳定）：Poller **从 "主消费载体" 降级为孤儿 / 超时 / 补偿兜底**（不切除，作为 MQ 主链异常时的恢复机制保留）；`AsyncExecutionResultConsumer` 回写链路改造后置

> ❗ 不得跳阶推进；尤其不得在 ②a 未稳定前提前到 ②b，或在 ②b 未稳定前推进 Poller 职责重定位，或在可靠性收尾窗口未关闭前提前启动 §5.2 阶段二。MQ 投递生命周期（**outbox** 表）与执行生命周期（**agent_execution_record**）严格分层，不混字段；技术噪声（broker nack / 重发节奏）只动 outbox 表，超阈值或最终失败才落一条业务级 timeline。

### 5.2 第二阶段：补任务运行时能力

优先参考：Vibe-Skills-main（work model / bounded work）+ 优先级设计文档（控制命令模型）

重点工作：

- 引入工作单元、执行绑定、验证证据、交付证据
- 增加执行进度快照与恢复快照
- 设计控制命令流与优先级调度
- 将"用户新输入"纳入可重入调度体系

### 5.3 第三阶段：补协作编排能力

优先参考：AgentTeams-main（Team 委托 + 工作区协调）+ OpenMOSS（角色模型）

重点工作：

- 工作流模板化（2~4 角色、可嵌套 Team）
- Team / coordinator / workflow 节点建模
- Planner / Executor / Reviewer 自动协作闭环

### 5.4 第四阶段：补接入与执行器扩展

优先参考：OpenMOSS（Agent 接入与 onboarding）+ 现有 HelloAI 接入主线

重点工作：

- 浏览器型 Agent 接入
- CLI / Browser / API Key 执行器统一抽象
- PromptTemplate / Skill 内容资产进一步标准化

---

## 6. 使用边界

- 如果要看当前已实现到哪里，请不要用本文档判断，直接看《实现差距表》。
- 如果要看调度架构的最准参考，请优先看 `doc/design/HelloAI_调度解耦重构分析.md`。
- 如果要看 blocked / 执行链问题的诊断上下文，请看 `doc/archive/HelloAI_执行链路架构分析.md`。
- 如果要看本轮外部项目综合吸收结论，请优先看本文第 1 节与第 5 节，而不是回到历史路线图中寻找旧方案。
- 如果要看外部项目具体借鉴的技术细节、代码模式与文件路径，请优先看 `doc/design/HelloAI_外部项目借鉴技术细节.md`（本文档的**技术落地补充**）。
