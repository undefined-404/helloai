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
- 涉及调度、执行链、异步回写、MQ 解耦的后续设计与实现，统一优先参考 `doc/HelloAI_调度解耦重构分析.md` 与 `E:\workspace\AgentTeams-main` 的分层思想。

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

---

## 4. 当前不默认视为“已完整交付”的能力

以下内容即使在历史文档中被展开描述，也默认属于目标态、部分落地或待补能力，不能直接按“已交付”理解：

- 工作流模板与 Team 编排
- 独立 MQ 版执行命令消费载体（DB Poller 主消费已交付；MQ Consumer 骨架已交付；`ExecutionCommandMqPublisher` 与 `MqExecutionCommandConsumer` 在 `dispatch-mode=BOTH + producer/consumer=true` 下 E2E 已验证；详见差距表 N6 与迭代记录 Phase 2G；当前**处于可靠性收尾窗口**：下一阶段是 Outbox 一体化与 Publisher Confirm，Poller 仍保留）
- `credential_vault` 的完整迁移、过渡期双活与权限模型
- `agent_duty_lease` 仅完成最小表结构与服务骨架，尚未接入 `checkIn/checkOut`、调度优先级与看板
- 浏览器型 Agent 的真实接入链路
- 多 Provider 的完整配置复用与平台内执行统一抽象
- 优先级调度队列与抢占式打断/恢复机制
- 执行进度快照与任务恢复上下文
- 工作单元显式建模与跨会话记忆平面

---

## 5. 设计参考与架构方向

参考吸收原则与后续开发方向由以下文档承担，本文档不重复展开：

- 参考来源与吸收边界：`doc/HelloAI_架构设计参考.md` §1
- 后续开发思路与阶段划分：`doc/HelloAI_架构设计参考.md` §5
- 具体外部文件路径与代码模式：`doc/HelloAI_外部项目借鉴技术细节.md`

### 5.1 已确认的统一边界

以下约束为项目级决策，不受架构方向迭代影响：

- 不引入第二控制面
- 不让设计参考覆盖代码事实
- 不把外部项目的基础设施形态（K8s / Matrix / MinIO / 大量治理壳）原样搬进当前主线
- 引入 Agent 执行状态（IDLE / WORKING / INTERRUPTED）优先通过查询推导而非新增 DB 枚举，
  避免与 `online_status`（ONLINE / OFFLINE / SLEEPING）形成双套状态体系

---

## 6. 文档矩阵

### 6.1 核心三层

- `doc/HelloAI_项目基线文档.md`：项目是什么
- `doc/HelloAI_实现差距表.md`：差在哪里
- `doc/HelloAI_迭代执行记录.md`：做了什么

### 6.2 专项分析

- `doc/HelloAI_调度解耦重构分析.md`
- `doc/HelloAI_执行链路架构分析.md`

### 6.3 设计参考

- `doc/HelloAI_架构设计参考.md`：设计理念、参考来源、核心概念与目标态方向
- `doc/HelloAI_外部项目借鉴技术细节.md`：按借鉴项目维度整理的具体技术细节、代码模式与文件路径

### 6.4 能力确认

- `doc/HelloAI_当前能力确认矩阵.md`

### 6.5 工程规范

- `doc/HelloAI_CODE_STYLE.md`

### 6.6 其他参考

- `doc/HelloAi Agent 任务调度优先级机制设计文档.md`

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
