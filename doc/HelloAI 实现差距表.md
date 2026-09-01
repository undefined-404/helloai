# HelloAI 实现差距表

> **文档版本：V2**
>
> 本文档只记录 HelloAI **当前仍然存在的实现差距、风险、待决策事项和未完整交付能力**。
>
> 已完成事项不在本文档长期保留。
>
> 历史实现过程请查看 `doc/log/`。
>
> 具体设计请查看 `doc/design/`。
>
> 最终事实以当前代码、数据库及可复现实验为准。
>
> 最后更新：2026-09-01

---

# 1. 文档定位

本文档用于回答：

```text
现在还缺什么？
为什么说它还缺？
优先级是什么？
下一步应该做什么？
如何验证完成？
```

本文档不负责：

- 记录历史版本
- 保存已经完成的需求
- 记录完整代码修改
- 保存详细设计方案
- 保存开发流水账
- 替代项目基线
- 替代 Issue / Git

---

# 2. 状态定义

| 状态 | 含义 |
|---|---|
| `TODO` | 尚未开始 |
| `DESIGNING` | 正在设计 |
| `DOING` | 开发中 |
| `VERIFYING` | 已开发，等待验证 |
| `BLOCKED` | 存在明确阻塞 |
| `PARTIAL` | 已有主路径，但能力不完整 |
| `DONE` | 已完成并验证 |
| `WONTFIX` | 当前明确不做 |

其中：

> `DONE` 项原则上应从当前差距表移除，仅在 Log 中保留历史记录。

---

# 3. 优先级定义

| 优先级 | 含义 |
|---|---|
| P0 | 阻断主链路 / 严重数据风险 |
| P1 | 核心能力缺失 / 明显可靠性问题 |
| P2 | 重要能力增强 |
| P3 | 优化项 / 体验项 |

---

# 4. 当前总体结论

当前 HelloAI 已经形成：

```text
Planner
→ Task
→ SubTask
→ Agent
→ Execution
→ Review
→ Artifact
```

的核心闭环。

当前主要差距集中在：

1. Workflow / Team 编排
2. Browser Agent
3. Credential Vault 完整化
4. Provider Factory 完整化
5. 高级调度能力
6. 执行恢复能力
7. 跨会话记忆
8. 统一消息超时与转派
9. MQ / 异步链路进一步治理

---

# 5. 当前 Gap 总览

| ID | 能力 | 状态 | 优先级 | Gap |
|---|---|---|---|---|
| N-001 | Workflow 模板 | TODO | P1 | 当前缺少完整 Workflow 模型与模板化编排能力 |
| N-002 | Team 编排 | TODO | P1 | 当前缺少稳定的 Team / Agent 组合编排模型 |
| N-003 | Browser Agent | TODO | P2 | 尚无完整真实 Browser Agent 执行链路 |
| N-004 | Credential Vault | PARTIAL | P1 | 已有基础轮换语义，但完整迁移 / 权限模型不足 |
| N-005 | Provider Factory | PARTIAL | P1 | Provider Catalog 已存在，但部分 Provider Factory 尚不完整 |
| N-006 | 优先级调度 | TODO | P2 | 当前缺少完整优先级队列与抢占机制 |
| N-007 | 执行恢复 | PARTIAL | P1 | 已有超时 / 补偿，但缺少完整执行快照与恢复上下文 |
| N-008 | Message 超时转派 | PARTIAL | P1 | 尚无统一 inbox/message 超时消费转派机制 |
| N-009 | 跨会话记忆 | TODO | P2 | 尚无独立长期记忆平面 |
| N-010 | MQ 业务治理 | PARTIAL | P2 | 基础 Outbox / Confirm 已有，但 DLQ / 业务级治理尚未完整 |
| N-012 | Planner Context 管理 | PARTIAL | P1 | 当前上下文能力存在，但尚需建立更明确的 Context 分层策略 |

---

# 6. N-001 Workflow 模板

**状态：** `TODO`

**优先级：** P1

## 当前状态

当前已经具备：

```text
Task
SubTask
depends_on
Scheduler
Agent
Execution
```

但还没有形成独立、稳定、可复用的 Workflow 模板模型。

## 差距

缺少：

- Workflow 定义
- Workflow Template
- Workflow Version
- 节点模型
- 节点依赖
- 参数化
- 模板实例化
- Workflow 执行状态

## 目标

支持：

```text
Workflow Template
      ↓
Workflow Instance
      ↓
Task / SubTask
      ↓
Agent Execution
```

## 建议

独立设计，不直接修改现有 Task 模型硬塞 Workflow 语义。

设计文档：

```text
doc/design/
```

## 验证

至少覆盖：

- 模板创建
- 模板实例化
- 节点依赖
- 节点失败
- 重试
- Workflow 完成
- Workflow 恢复

---

# 7. N-002 Team 编排

**状态：** `TODO`

**优先级：** P1

## 当前状态

当前 Agent 已支持角色、状态和调度。

但：

```text
Agent
```

与：

```text
Team
```

之间尚未形成稳定模型。

## 差距

缺少：

- Team
- Team Member
- Team Role
- Team Policy
- Team 生命周期

## 原则

Team 不应复制 Scheduler。

Team 应该是：

> Agent 的组织 / 编排抽象。

---

# 8. N-003 Browser Agent

**状态：** `TODO`

**优先级：** P2

## 当前状态

平台已经具备外部 Agent / MCP 执行基础设施。

## 差距

缺少完整：

```text
Browser Agent
↓
Browser Session
↓
Tool
↓
Artifact
↓
Result
```

执行链路。

## 目标

Browser Agent 应作为一种 Agent 类型接入已有执行体系。

不得新增第二套任务调度系统。

---

# 9. N-004 Credential Vault

**状态：** `PARTIAL`

**优先级：** P1

## 已有

- API Key 基础存储
- ACTIVE
- EXPIRED
- 基础轮换语义

## 差距

仍缺少：

- 完整迁移
- 旧字段下线
- 双活过渡策略
- 更细粒度权限
- 审计
- Secret 生命周期治理

## 完成标准

必须能够明确回答：

```text
谁可以读取？
谁可以轮换？
谁可以失效？
旧 Key 什么时候删除？
异常情况下如何恢复？
```

---

# 10. N-005 Provider Factory

**状态：** `PARTIAL`

**优先级：** P1

## 当前状态

当前已经具备 Provider Catalog / Model 管理基础。

## 差距

部分 Provider 尚未形成完整统一 Factory。

## 目标

统一：

```text
Provider
+
Model
+
ChatClient
+
Config
```

创建方式。

## 原则

不得在业务 Service 中大量出现：

```text
if provider == xxx
if provider == yyy
```

---

# 11. N-006 优先级调度

**状态：** `TODO`

**优先级：** P2

## 差距

当前调度基础能力已经存在，但缺少：

- 优先级队列
- starvation 防护
- 抢占
- 恢复
- 优先级继承
- 调度公平性策略

## 目标

形成：

```text
Task Priority
      ↓
Queue
      ↓
Scheduler
      ↓
Agent
```

---

# 12. N-007 执行恢复

**状态：** `PARTIAL`

**优先级：** P1

## 已有

- 超时补偿
- Poller
- Outbox
- Reconcile
- DEAD_LETTER

## 差距

尚缺：

- 执行快照
- 恢复上下文
- 中断点
- 可恢复执行
- 恢复幂等策略

## 目标

系统重启或 Agent 异常后能够：

```text
识别未完成执行
      ↓
恢复执行上下文
      ↓
继续执行 / 重新调度
```

---

# 13. N-008 Message 超时转派

**状态：** `PARTIAL`

**优先级：** P1

## 当前问题

当前已经存在多种：

```text
timeout
reconcile
reassign
poll
```

机制。

但缺少统一：

```text
Message / Inbox
       ↓
超时未消费
       ↓
重新分派
```

机制。

## 目标

统一定义：

- 消息生命周期
- ACK
- Claim
- Timeout
- Retry
- Reassign
- Dead Letter

避免不同业务各自实现一套。

---

# 14. N-009 跨会话记忆

**状态：** `TODO`

**优先级：** P2

## 当前状态

Planner 已具备会话上下文。

## 差距

尚缺独立长期记忆平面：

```text
Session Memory
Long Term Memory
User Memory
Task Memory
Agent Memory
```

之间的明确边界。

## 原则

不要把所有历史 Conversation 直接当 Memory。

---

# 15. N-010 MQ 业务治理

**状态：** `PARTIAL`

**优先级：** P2

## 已有

- RabbitMQ
- Publisher Confirm
- Outbox
- Retry
- Poller 兜底
- DLX / DLQ 基础设施

## 差距

仍需进一步明确：

- DLQ 业务语义
- 消费失败分类
- poison message
- 告警
- 人工恢复
- 消息幂等
- 业务级熔断

## 原则

MQ 是执行链基础设施，不应成为第二业务控制面。

---

# 16. N-012 Planner Context 管理

**状态：** `PARTIAL`

**优先级：** P1

## 当前问题

Planner 当前同时涉及：

```text
Chat
Clarify
Search
Plan
Task
Memory
```

如果所有信息全部进入一个 Prompt，Context 会持续膨胀。

## 目标

建立分层 Context：

```text
System Context
    ↓
Project Context
    ↓
Conversation Context
    ↓
Current User Input
    ↓
Optional Search Context
    ↓
Optional MCP Context
```

不同任务只注入需要的信息。

## 原则

Context 越多不代表 Agent 越聪明。

目标是：

> 在正确任务中提供正确上下文。

---

# 17. Gap 新增规则

新增 Gap 必须至少包含：

```text
ID
能力
状态
优先级
当前状态
差距
目标
下一步
验证方式
```

禁止新增：

- 纯想法
- 模糊愿望
- 已完成事项
- 历史记录
- 完整代码
- 大段技术方案

如果还没有形成明确 Gap：

> 不要进入本文档。

---

# 18. Gap 完成规则

当一个 Gap 满足：

```text
代码完成
+
测试完成
+
关键路径验证完成
```

则：

```text
Gap → DONE
```

随后从当前表移除。

历史信息写入：

```text
doc/log/
```

如果产生长期有效的架构知识：

```text
doc/design/
```

---

# 19. Gap 与 Design 的关系

推荐：

```text
N-001
  ↓
design/workflow.md
  ↓
代码实现
  ↓
E2E
  ↓
log/2026-xx.md
```

而不是：

```text
N-001
  ↓
不断向表格追加 500 行实现细节
```

---

# 20. Gap 与 Log 的关系

Gap：

> 现在还有什么问题？

Log：

> 这个问题过去怎么处理过？

因此：

```text
Gap = 当前状态
Log = 历史状态
```

两者不能混用。

---

# 21. 当前 Gap 使用原则

AI Agent 读取本文件时：

1. 可以用于判断当前缺失能力。
2. 可以用于判断项目正在解决什么问题。
3. 不得据此推断具体代码结构。
4. 不得把 Gap 描述当成已经存在的代码。
5. 修改代码前必须检查实际代码。

---

# 22. 最终原则

实现差距表不是：

> “HelloAI 做过什么的百科全书”。

而是：

> **HelloAI 当前待解决问题的实时索引。**

因此：

```text
完成 → 移除
失效 → 移除
改为设计 → 链接 design/
进入开发 → 更新状态
```

保持表格小、准、当前。

---