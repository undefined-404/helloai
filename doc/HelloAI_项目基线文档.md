# HelloAI 项目基线文档

## 1. 文档定位

本文档只回答三件事：

- 当前项目是什么
- 当前哪些能力可以视为现实基线
- 文档冲突时以什么作为事实源

本文档不承担实施流水账、历史路线图或逐条差距对表的职责。

---

## 2. 当前基线结论

- 当前项目是一套基于 Spring Boot + Spring AI MCP 的多 Agent 协作调度平台。
- 当前主线已具备 MCP SSE 接入、双通道鉴权、工具调用、在线状态三件套、熔断降级、Reconcile 健康检查、管理后台与基础前端能力。
- 当前工程运行基线保持在 `JDK 17 + Spring Boot 3.4.x + Spring AI 1.1.x`。
- 涉及调度、执行链、异步回写、MQ 解耦的后续设计与实现，统一优先参考 `doc/design/HelloAI_调度解耦重构分析.md` 与 `E:\workspace\AgentTeams-main` 的分层思想。

---

## 3. 当前已形成闭环的能力

- MCP SSE 接入与消息链路
- 管理员 Token / Agent API Key 双通道鉴权
- MCP 工具注册与业务工具调用
- 外部 Agent 执行闭环最小集：`submitResult` 上交结果进入统一回写入口；`reportBlocked` 上报阻塞原因进入证据链
- Agent 在线状态三件套：`last_seen_at` / `last_active_at` / `online_status`
- `ASSIGNED` 超时未 `claim` 的巡检回收与重新分配
- 熔断降级与同角色替补
- Reconcile 健康检查与离线重分配
- Poller 兜底路径的最小等价验证：孤儿 `PENDING` 记录可被 `POLLER` 扫描并写入 `sub_task_execution_command_poll_recovery`
- Session TTL 清理
- MQ 维度的执行命令主链路（producer/consumer 双开关）：`MqExecutionCommandConsumer` 与 `LocalExecutionCommandConsumer` 共用 `ExecutionCommandConsumer` 接口，`@RabbitListener` MANUAL ACK；`ExecutionCommandMqPublisher` 完成生产端接入，由 `AgentExecutionProperties.dispatch-mode`（`NONE / EVENT / MQ / BOTH`，默认 `NONE`）控制分发方式，`MqExecutionCommandProperties.{producer-enabled, consumer-enabled}` 分别控制 Publisher / Consumer 注册，支持独立灰度；MQ 段 Publisher Bean 不可用则 `ExecutionDispatchValidator` 启动期 fail-fast，不隐式回退。默认零行为变化（POLLER 仍为默认主消费载体）；具备 RabbitMQ 环境可开 `dispatch-mode=BOTH` + 双开关做 E2E。详见 §6 文档矩阵中“迭代执行记录 Phase 2D / Phase 2E”条目
- `credential_vault` 已具备 Agent API Key 的最小轮换语义（`ACTIVE / EXPIRED`）
- 基础管理后台与前端主流程
- 值班租约闭环（AgentHub V1）：`checkIn / checkOut` + 值班优先调度（N12）与门铃通知通道（AgentHub V3：`SseEmitter` 秒级唤醒 + 保活帧 + 双心跳，N13）
- 重分配熔断与死信兜底（V24/V25）：`reassign_attempt_count` 熔断阈值 + `DEAD_LETTER` 终态 + 人工重派接口，打破"Agent 全掉线 → 无限重分配"死循环
- Controller 分层红线收口（N15）：6 个历史违规 Controller 全部收口至 Service，Controller 层 0 Mapper
- Planner 平台内自动拆解（N16，V26）：需求 → LLM 拆解 → 草案 `PENDING_PLAN_REVIEW` → 确认/拒绝 → 进入既有分发链，草案态与执行链硬隔离
- 对话式需求澄清（N17，V29/V31/V33/V34）：多轮追问 → 终稿 → 建任务顺路拆解；结构化选项式追问 + 进度条；联网搜索开关（默认博查）
- 执行产出物化（方案2）+ 交付物实时聚合 zip 下载 + 任务最终整合报告（V32）：执行产出物化为真实附件，主任务收口后 Planner 整合全部子任务产出为一份连贯报告
- 执行链依赖上下文注入（V35 原始产出 + 2026-08-03 双轨升级）：执行 Agent 按 `depends_on` 声明顺序参考直接前置产出——先经 V35 `## 上游产出参考` 注入原始产出，2026-08-03 升级为 Task Running Spec 双轨：直接前置的**结构化摘要**（`findRecord` 精确取单条 EXECUTION_RECORD）+ **完成内容本体**（物化附件优先、`context.lastExecution.output` 回退）同现于 `## 依赖产出参考（直接前置）` 章节（多前置按声明顺序全量收集防覆盖）；Phase A JSONB 回填加 taskId 粒度分段锁防并发互覆（Phase B 独立表行级天然安全）；`sub_task_spec_context_loaded` 可观测（depCount/loadedCount/truncatedCount/degraded）。E2E 双前置场景 PASS（§6.43）
- 执行对话流可观测（V38 + 2026-08-02）：user prompt 落库 `conversation_message`（`sub_task_execute_user_prompt`）、reviewHistory 多轮累积、审核结论消息 `subtask_review_result`、Snowflake 长 ID 全链路字符串化（`BaseEntity.id` + DTO 注解 + 前端 `String()` 防御）

---

## 4. 当前不默认视为“已完整交付”的能力

以下内容即使在历史文档中被展开描述，也默认属于目标态、部分落地或待补能力，不能直接按“已交付”理解：

- 工作流模板与 Team 编排
- 独立 MQ 版执行命令消费载体（已交付：DB Poller 主消费 + MQ Consumer + Outbox 一体化 + Publisher Confirm + Poller 兜底重塑，详见差距表 N6；`dispatch-mode=BOTH` 下 E2E 已验证）
- `credential_vault` 的完整迁移、过渡期双活与权限模型
- 浏览器型 Agent 的真实接入链路
- moonshot / minimax / dashscope 等 Provider 的 ChatClient Factory 实现（`LlmProviderCatalogService` 目录已就绪，Factory 缺实现前标记不可用）
- 优先级调度队列与抢占式打断/恢复机制
- 执行进度快照与任务恢复上下文
- 工作单元显式建模与跨会话记忆平面

---

## 5. 设计参考与架构方向

参考吸收原则与后续开发方向由以下文档承担，本文档不重复展开：

- 参考来源与吸收边界：`doc/design/HelloAI_架构设计参考.md` §1
- 后续开发思路与阶段划分：`doc/design/HelloAI_架构设计参考.md` §5
- 具体外部文件路径与代码模式：`doc/design/HelloAI_外部项目借鉴技术细节.md`

### 5.1 已确认的统一边界

以下约束为项目级决策，不受架构方向迭代影响：

- 不引入第二控制面
- 不让设计参考覆盖代码事实
- 不把外部项目的基础设施形态（K8s / Matrix / MinIO / 大量治理壳）原样搬进当前主线
- 引入 Agent 执行状态（IDLE / WORKING / INTERRUPTED）优先通过查询推导而非新增 DB 枚举，
  避免与 `online_status`（ONLINE / OFFLINE / SLEEPING）形成双套状态体系
- 双心跳（`last_seen_at` / `last_active_at`）与上班打卡（`agent_duty_lease`）仅用于外部 Agent
  （`CLI_CLIENT` / `WEB_BROWSER`）的可用性判定：打卡是被调度选中的准入第一步，双心跳是验证其是否在
  正常干活的运行时监控；`API_KEY_LLM` 豁免这两类判定，其可用性以"任务是否按时完成 + 定期 API Key
  可用性探测"衡量（三层可用性模型详见 `doc/design/HelloAI_架构设计参考.md` §3.8）

---

## 6. 文档矩阵

### 6.1 核心三层

- `doc/HelloAI_项目基线文档.md`：项目是什么
- `doc/HelloAI_实现差距表.md`：差在哪里
- `doc/log/HelloAI_迭代执行记录.md`：做了什么

### 6.2 专项分析

- `doc/design/HelloAI_调度解耦重构分析.md`
- `doc/archive/HelloAI_执行链路架构分析.md`

### 6.3 设计参考

- `doc/design/HelloAI_架构设计参考.md`：设计理念、参考来源、核心概念与目标态方向
- `doc/design/HelloAI_外部项目借鉴技术细节.md`：按借鉴项目维度整理的具体技术细节、代码模式与文件路径
- `doc/design/HelloAI_执行产出物化与结构化多文件产出方案.md`：执行产出物化（方案2，已实现）与 LLM manifest 多文件协议（方案3，设计遗留）

### 6.4 能力确认

- `doc/archive/HelloAI_当前能力确认矩阵.md`

### 6.5 工程规范

- `doc/HelloAI_CODE_STYLE.md`

### 6.6 其他参考

- `doc/design/HelloAi Agent 任务调度优先级机制设计文档.md`
- `doc/HelloAI_登录页原创AI虚拟人物生成提示词.md`：登录页原创视觉资产（AI 虚拟人物 + 机器人骨架）生成提示词

---

## 7. 事实源优先级

文档冲突时，按以下优先级判定：

1. 代码与运行结果
2. Flyway 初始化脚本与数据库结构
3. 验收脚本与可复现实验结果
4. `doc/HelloAI_实现差距表.md`
5. 本文档
6. README
7. 历史路线图 / 技术方案 / 对比文档

---

## 8. 工程红线

- JDK 固定为 `17`
- Spring AI 保持当前项目运行基线，任何升级或回退都必须重新做 MCP 鉴权与端到端回归
- 后端数据库初始化以 `helloai-start/src/main/resources/db/migration/V1__init_all.sql` 为单一初始化入口
- Controller 只做参数接收、DTO 转换与返回封装
- 代码事实与文档不一致时，优先修正文档误导，而不是用文档掩盖现状

---

## 9. 能力边界

| 能力项 | 当前状态 | 说明 |
| --- | --- | --- |
| CLI Agent 自注册 | 已支持 | 支持通过 `/api/agents/register-with-token` 注册，默认 `accessType=CLI_CLIENT` |
| CLI Agent 鉴权接入 | 已支持 | 注册后可持 `Bearer apiKey` 调用 `/api/agents/me/skill`、MCP/SSE/HTTP 工具 |
| API_KEY_LLM 自动执行 | 已支持 | 子任务 `ASSIGNED` 后可自动创建 `ExecutionCommand` 并由本地 consumer 执行 |
| CLI Agent 主动拉任务消费 | 已支持 | 通过 `pullTasks / ack / claimSubTask / heartbeat / reportBlocked` 等工具实现 |
| EXECUTOR 自动执行主链 | 已支持 | 调度、命令消费、执行、结果回写、超时补偿链路均已接通 |
| PLANNER 自动拆解任务 | 已支持 | 需求经 LLM 自动拆解为草案（`PENDING_PLAN_REVIEW`），用户确认后进入既有分发链（V26，2026-07-28 交付，详见差距表 N16） |
| REVIEWER 自动审查 | 部分支持 | 子任务进入 `REVIEW` 后有通知，但当前主流程仍依赖显式 review 提交 |
| Agent 离线后同角色重分发 | 已支持 | 会重置子任务并交回弹性调度器，按同角色替补 |
| 执行超时补偿 | 已支持 | `PENDING/RUNNING` 超时会补偿为 `TIMEOUT`，必要时推进 `BLOCKED` |
| 消息未消费后的统一超时转派 | 未完整支持 | 当前没有一套对所有角色 inbox/message 的统一“超时未消费 -> 自动转派”机制 |
| MQ DLQ 基础设施 | 已有基础设施 | RabbitMQ 已配置 DLX/DLQ，但业务主链尚未全面建立在该机制上 |
