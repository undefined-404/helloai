# HelloAI 项目基线文档

> **文档版本：V2**
>
> 本文档描述 HelloAI **当前真实状态**，不是历史路线图，也不是未来设计方案。
>
> **核心原则：代码与可复现实验结果优先于本文档。**
>
> 最后更新：2026-09-01

---

# 1. 文档定位

本文档只回答以下问题：

1. HelloAI 当前是什么系统？
2. 当前已经具备哪些能力？
3. 当前哪些能力可以视为稳定基线？
4. 当前有哪些明确的能力边界？
5. 当文档、设计与代码发生冲突时，以什么作为事实源？

本文档**不承担**以下职责：

- 不记录详细开发流水账
- 不记录每一次代码修改
- 不保存完整实现方案
- 不承担未来 Roadmap
- 不保存已经完成的历史 Gap
- 不描述某个版本具体修改了多少代码
- 不作为历史版本考古资料

相关内容分别进入：

- `HelloAI 实现差距表.md`
- `design/`
- `log/`
- `archive/`

---

# 2. 项目定位

HelloAI 是一个面向 AI Agent 协作场景的**多 Agent 协作与任务调度平台**。

核心目标是将：

```text
用户需求
   ↓
Planner
   ↓
任务拆解 / 需求澄清
   ↓
Task / SubTask
   ↓
调度
   ↓
Agent
   ↓
执行
   ↓
结果回写
   ↓
Review
   ↓
最终产出
```

形成可观测、可恢复、可扩展的完整执行闭环。

当前项目同时支持：

- 平台内 LLM Agent
- 外部 CLI Agent
- MCP Agent
- Reviewer
- Planner
- MQ / DB Poller 等异步执行基础设施

---

# 3. 当前技术基线

## 3.1 后端

| 技术 | 当前基线 |
|---|---|
| JDK | 17 |
| Spring Boot | 3.4.x |
| Spring AI | 1.1.x |
| 数据库 | PostgreSQL |
| 缓存 | Redis |
| 消息队列 | RabbitMQ |
| MCP | Spring AI MCP / SSE |
| 数据库迁移 | Flyway |

---

## 3.2 前端

当前项目具备基础管理后台及 Planner / Task 等主要交互页面。

前端具体实现以当前代码为准，不以历史设计文档中的页面描述作为事实依据。

---

# 4. 核心领域模型

当前系统核心概念包括：

```text
Planner
Task
SubTask
Agent
Execution
Review
Conversation
Artifact
MCP Tool
Execution Command
```

核心关系可以抽象为：

```text
Task
 ├── SubTask
 │     ├── Agent
 │     ├── Execution
 │     └── Review
 │
 ├── Conversation
 │
 └── Artifact
```

---

# 5. 当前核心执行链

当前主执行链可以抽象为：

```text
用户需求
   ↓
Planner Chat / Planner Plan
   ↓
Task
   ↓
SubTask
   ↓
Scheduler
   ↓
Agent Assignment
   ↓
ExecutionCommand
   ↓
Agent Execution
   ↓
Result
   ↓
Review
   ↓
SubTask 状态收敛
   ↓
Task 状态收敛
   ↓
最终产出
```

任何具体实现细节以当前代码为准。

---

# 6. 当前已形成闭环的能力

以下能力可以作为当前主线的现实基线。

## 6.1 Agent / MCP

- MCP SSE 主通道
- MCP 工具注册
- MCP 工具调用
- 外部 Agent 接入
- Agent API Key 鉴权
- 管理员 Token 鉴权
- Agent Skill 信息获取
- Agent 状态查询
- Agent 心跳
- Agent 主动拉取任务
- Agent ACK / Claim
- Agent 结果提交
- Agent 阻塞上报
- Artifact 上传

---

## 6.2 Agent 状态

当前已具备 Agent 在线状态基础能力：

```text
last_seen_time
last_active_time
online_status
```

外部 Agent 的可用性判断结合：

```text
checkIn / checkOut
+
heartbeat
+
任务完成情况
```

具体状态转换以当前代码和数据库结构为准。

---

## 6.3 调度与异常恢复

当前已具备：

- Agent 任务分配
- Agent 离线检测
- 同角色替补
- ASSIGNED 超时回收
- 执行超时补偿
- 重分配
- 重分配次数控制
- DEAD_LETTER 终态
- 人工重新分派
- Reconcile 健康检查
- Poller 兜底
- Session TTL 清理

---

## 6.4 Planner

当前 Planner 已具备两类核心能力：

### CHAT

用于用户与 Planner 进行自然语言对话。

特点：

- 多轮上下文
- 自由对话
- 意图识别
- 用户主导继续对话
- 必要时进入方案澄清

### CLARIFY

用于结构化需求澄清。

特点：

- 多轮追问
- 结构化选项
- 方案澄清
- 终稿形成
- 进入任务创建 / 自动拆解流程

同时支持：

```text
/planner
```

作为显式进入方案模式的快捷方式。

### 输入优化（PromptEnhancer）

Planner Chat 输入区支持一键优化当前输入。

特点：

- 独立 PromptEnhancer 服务，不侵入 Planner 主流程与状态机
- 复用 Planner 模型选型与执行链（同步调用，低 temperature）
- 语义保护：不改用户明确的字段名 / 接口名 / 数值 / 实体，不虚构业务事实，信息不足保留待确认
- 优化结果进入预览面板，由用户确认后回填输入框，不自动发送、不自动覆盖原始输入
- 当前只优化当前输入，不携带会话上下文

---

# 7. Planner 自动拆解

当前已支持：

```text
用户需求
   ↓
LLM 分析
   ↓
生成任务拆解草案
   ↓
PENDING_PLAN_REVIEW
   ↓
用户确认 / 拒绝
   ↓
正式 Task / SubTask
   ↓
既有调度链
```

草案阶段与正式执行阶段保持逻辑隔离。

---

# 8. Planner 联网搜索

当前 Planner 已具备联网搜索能力。

当前能力包括：

- 搜索开关
- 多供应商
- 搜索查询处理
- URL 提取
- 网页内容获取
- 搜索结果折叠展示
- 必要情况下的 LLM 查询改写

具体 Provider、配置项及降级策略以当前代码为准。

---

# 9. 执行产出物

当前已具备执行产出物的物化能力。

支持：

- Artifact
- 多文件产出
- 结构化 manifest
- 任务产出聚合
- ZIP 下载
- 最终报告

最终报告具备独立状态：

```text
NONE
GENERATING
DONE
FAILED
```

最终报告生成与 Task DONE 状态保持语义隔离。

---

# 10. Review

当前已具备 Reviewer 自动审查能力。

Review 主要用于：

- 结果质量判断
- LLM 评分
- 问题反馈
- 通过
- 驳回
- 返工

Reviewer 当前具备主路径与兜底路径。

具体 MQ Consumer、定时扫描及事件触发实现，以当前代码为准。

---

# 11. MQ / Execution Command

当前执行命令链支持 MQ 相关能力。

当前主要组成包括：

```text
ExecutionCommand
ExecutionCommandConsumer
LocalExecutionCommandConsumer
MqExecutionCommandConsumer
ExecutionCommandMqPublisher
AgentCommandOutbox
```

支持：

- Producer / Consumer 独立开关
- MQ / Local Consumer
- Publisher Confirm
- Outbox
- Retry
- Poller 兜底

MQ 并不是当前系统唯一执行路径。

具体运行模式由当前配置决定。

---

# 12. Outbox

当前已具备 Agent Command Outbox 基础能力。

核心目标：

```text
业务事务
+
消息可靠投递
```

保持一致。

当前支持：

- Pending
- Relay
- Sent
- Confirmed
- Failed
- Retry
- Final Failed

具体状态及重试策略以数据库结构与当前实现为准。

---

# 13. 鉴权

当前系统存在至少两类核心鉴权主体：

```text
管理员
Agent
```

主要方式：

```text
Admin Token
Agent API Key
```

外部 Agent 使用 API Key 访问受保护能力。

具体路径与白名单以当前代码配置为准。

---

# 14. Credential Vault

当前已具备 Agent API Key 的基础 Vault 能力。

当前支持最小生命周期：

```text
ACTIVE
EXPIRED
```

完整 Vault 迁移、双活、细粒度权限模型等不作为当前完整交付能力。

---

# 15. 监控

当前已具备基础监控能力：

```text
Prometheus
Grafana
Spring Boot Actuator
RabbitMQ metrics
Redis metrics
PostgreSQL metrics
```

当前监控主要用于：

- API RT
- 服务运行状态
- MQ
- Redis
- PostgreSQL
- 基础系统指标

监控指标与 Dashboard 以当前部署配置为准。

---

# 16. 当前明确不属于完整基线的能力

以下能力即使历史文档中存在详细设计，也不能默认视为已经完整交付：

| 能力 | 当前状态 |
|---|---|
| Workflow 模板 | 未完整交付 |
| Team 编排 | 未完整交付 |
| Browser Agent | 未完整交付 |
| Credential Vault 完整迁移 | 未完整交付 |
| 完整 Provider Factory | 部分完成 |
| 优先级调度 | 未完整交付 |
| 抢占式打断 / 恢复 | 未完整交付 |
| 执行进度快照 | 未完整交付 |
| 完整任务恢复上下文 | 未完整交付 |
| 跨会话长期记忆 | 未完整交付 |
| 完整统一 Message 超时转派 | 未完整交付 |

具体当前 Gap 以：

`doc/HelloAI 实现差距表.md`

为准。

---

# 17. 项目级架构约束

以下原则属于当前项目级约束。

## 17.1 不建立第二控制面

Planner / Scheduler / Agent / Execution 等能力应围绕当前控制面演进。

不得因为引入某个外部参考项目而复制第二套任务控制体系。

---

## 17.2 代码优先

设计文档不得覆盖当前代码事实。

如果：

```text
代码 ≠ 文档
```

优先确认代码真实行为，再修正文档。

---

## 17.3 外部项目只吸收思想

外部项目可以用于：

- 架构参考
- 模式参考
- 技术验证
- 设计启发

不得直接将其：

```text
K8s
Matrix
MinIO
Control Plane
大量治理组件
```

整体复制进入 HelloAI。

---

## 17.4 Agent 状态避免重复建模

当前 Agent 可用性相关状态已经存在：

```text
online_status
last_seen_time
last_active_time
agent_duty_lease
```

新增状态时必须先确认是否能够通过已有状态推导。

不得随意增加第二套互相重叠的状态体系。

---

# 18. 文档体系

当前文档采用以下职责划分：

```text
项目基线
    ↓
当前是什么

实现差距
    ↓
还缺什么

Design
    ↓
准备怎么实现

Log
    ↓
过去做了什么

Archive
    ↓
已经退出主线的历史资料
```

---

# 19. 文档优先级

推荐 AI / 开发者读取项目文档时遵循：

```text
当前代码
    ↓
数据库结构 / Flyway
    ↓
可复现实验 / E2E
    ↓
CODE_STYLE
    ↓
项目基线
    ↓
Design
    ↓
实现差距
    ↓
Log
    ↓
Archive
```

特别注意：

> **Log 和 Archive 不得作为判断当前代码状态的主要依据。**

---

# 20. 事实源优先级

当多个来源发生冲突时：

1. 当前运行代码
2. 数据库实际结构 / Flyway
3. 可复现实验与 E2E
4. 当前配置文件
5. `HelloAI_CODE_STYLE.md`
6. 本文档
7. `HelloAI 实现差距表.md`
8. Design 文档
9. README
10. Log / Archive

---

# 21. 当前工程红线

- JDK 固定为 17
- Controller 不直接访问 Mapper
- Controller 不承载业务逻辑
- Service 承担业务编排
- 数据库变更必须通过 Flyway
- 不通过文档伪造当前能力
- 不根据历史 Log 推断当前代码
- 不为了修复一个局部问题随意增加新的状态体系
- 不为了一个新功能复制第二套执行链
- 不随意引入新的基础设施
- 修改核心执行链必须考虑异常、重试、幂等与恢复
- 涉及 MCP / 鉴权 / Agent 链路的修改必须进行回归验证

---

# 22. AI 修改代码时的上下文规则

AI Agent 修改 HelloAI 代码时：

### 必须优先查看

```text
当前目标代码
+
HelloAI_CODE_STYLE.md
```

### 根据任务决定是否查看

```text
HelloAI 项目基线文档.md
design/
HelloAI 实现差距表.md
```

### 默认不要读取

```text
log/
archive/
```

除非任务明确要求：

- 查找历史决策
- 恢复历史实现
- 分析某次重构
- 追踪某个 Bug 的历史原因

---

# 23. 基线更新规则

只有以下情况才更新本文档：

- 新增稳定能力
- 删除现有能力
- 核心架构发生变化
- 技术栈发生变化
- 项目级工程约束发生变化
- 当前能力边界发生变化

以下内容不要写入本文档：

```text
某天修改了某个 Service
某次修复了一个 Bug
某次增加了一个字段
某次 E2E 失败
某次 Prompt 调整
某个版本修改了几个文件
```

这些内容进入：

```text
log/
```

---

# 24. 当前版本判断原则

本文档中的“已支持”意味着：

> 当前代码中存在对应实现，并且至少有基本验证依据。

“已支持”不等价于：

> 企业级完整、极端场景全部覆盖、生产环境已经验证。

“部分支持”意味着：

> 主路径已经存在，但仍有明确能力缺口。

“未完整交付”意味着：

> 当前不应让 AI 或开发者默认该能力已经存在。

---

# 25. 最终原则

HelloAI 的最终事实源永远是：

```text
代码
+
数据库
+
可复现实验
```

文档的职责不是取代代码，而是：

> **降低理解代码的成本。**

如果文档开始比代码更复杂，应优先简化文档，而不是继续增加文档。

---