# HelloAI 迭代执行记录

> **文档版本：V2**
>
> 本文档用于记录 HelloAI 的**历史开发过程、重要技术决策、重大重构和验证结果**。
>
> 本文档不是当前项目状态的事实源。
>
> 当前项目状态请查看：
>
> `doc/HelloAI 项目基线文档.md`
>
> 当前未完成事项请查看：
>
> `doc/HelloAI 实现差距表.md`
>
> 最后更新：2026-09-04

---

# 1. 文档定位

Log 的核心问题只有一个：

> **HelloAI 过去发生了什么？**

主要记录：

- 重大功能实现
- 架构重构
- 数据库变更
- 重要技术决策
- 关键 Bug 修复
- E2E 验证
- 失败方案
- 设计取舍
- 重大性能 / 稳定性问题
- 与外部项目借鉴相关的重要决策

---

# 2. Log 不负责什么

以下内容不要写入 Log：

- 当前系统完整架构
- 当前所有 API
- 当前所有数据库表
- 当前所有能力
- 当前 Gap
- 完整代码
- 大段代码片段
- 完整技术方案
- 用户操作手册
- 长期 Roadmap

对应文档：

```text
当前架构
→ HelloAI 项目基线文档.md

当前 Gap
→ HelloAI 实现差距表.md

详细设计
→ design/

代码规范
→ HelloAI_CODE_STYLE.md
```

---

# 3. 新 Log 存储方式

不再无限增长单文件。

推荐：

```text
doc/
└── log/
    ├── README.md
    ├── 2026-06.md
    ├── 2026-07.md
    ├── 2026-08.md
    ├── 2026-09.md
    └── archive/
        └── HelloAI_迭代执行记录_V1.md（V2 文档体系重组前的全部历史记录）
```

原则：

> **一个月一个 Log 文件。**

如果某个月内容特别多，可以拆成：

```text
2026-09/
├── 01-planner.md
├── 02-agent.md
├── 03-mq.md
└── 04-infrastructure.md
```

---

# 4. Log 编号规则

不再使用大量跨文档共享的：

```text
V1
V2
V29
V40
V40.2
Phase 2H
A0-1
A0-2
```

作为当前状态的主要标识。

历史版本号可以继续保留，但只作为：

> 历史上下文。

推荐新的 Log ID：

```text
LOG-20260901-001
LOG-20260901-002
```

或者简单使用：

```text
## 2026-09-01 Planner 输入优化
```

---

# 5. 单条 Log 标准格式

推荐统一使用：

```markdown
## LOG-20260901-001 Planner 输入优化

### 背景

为什么要做。

### 问题

原来有什么问题。

### 决策

最终选择什么方案。

### 实现

修改了哪些模块。

### 验证

如何验证。

### 结果

最终结果。

### 影响

是否影响现有链路。

### 关联

- Gap：
- Design：
- Commit：
```

---

# 6. 推荐记录粒度

一条 Log 应该回答：

```text
为什么改？
改了什么？
为什么这么改？
有没有验证？
结果怎么样？
```

而不是记录：

```text
今天 10:01 改了 A.java
10:15 改了 B.java
10:32 改了 C.java
```

---

# 7. 禁止保存完整代码

Log 中不建议保存：

```java
public void xxx() {
    ...
}
```

代码本身应该进入 Git。

Log 只记录：

```text
修改 ExecutionCommandService
增加 Outbox 写入
增加 Confirm 回调
增加失败重试
```

---

# 8. 重大架构变更

如果一次修改改变了架构，应同时：

```text
代码
+
Design
+
Log
```

其中：

### Design

描述：

> 为什么这么设计，以及应该怎么设计。

### Log

描述：

> 什么时候做了，以及最终结果。

### 基线

只有当新架构成为当前稳定事实时才更新。

---

# 9. 当前历史阶段摘要

以下只作为历史索引，不代表当前系统全部能力。

---

## 9.1 Agent / MCP 阶段

早期主要完成：

- MCP SSE 接入
- Agent 鉴权
- Tool 注册
- Tool 调用
- Agent 注册
- Agent 心跳
- Agent 状态
- 外部 Agent 执行闭环

这一阶段形成了 HelloAI 的基础 Agent Control 能力。

---

## 9.2 调度与 Reconcile 阶段

主要完成：

- Agent 调度
- ASSIGNED 超时回收
- Agent 离线检测
- 同角色替补
- Reconcile
- 重分配
- DEAD_LETTER
- 人工重派

这一阶段解决了：

```text
Agent 掉线
任务丢失
无限重分配
```

等基础可靠性问题。

---

## 9.3 AgentHub 阶段

主要完成：

- checkIn
- checkOut
- Duty Lease
- 值班优先调度
- 门铃通知
- SSE 唤醒
- 双心跳

后续验证过程中发现：

> 外部 Agent 能力与平台主动推送能力之间存在边界。

因此部分能力保持在基础设施层，而不是强制要求所有外部 Agent 支持完整推送。

---

## 9.4 Planner 自动拆解阶段

形成：

```text
用户需求
↓
LLM
↓
Plan Draft
↓
PENDING_PLAN_REVIEW
↓
用户确认
↓
Task
↓
SubTask
```

重点解决：

> LLM 自动拆解不能直接进入执行链。

因此建立：

```text
Draft
```

与：

```text
Execution
```

之间的隔离。

---

## 9.5 Planner Chat / Clarify 阶段

Planner 从单一方案生成逐渐发展为：

```text
CHAT
CLARIFY
```

双模式。

主要演进方向：

- 多轮对话
- 需求澄清
- 结构化选项
- 意图识别
- `/planner`
- 搜索
- 推荐卡片
- 终稿
- 自动立项

这一阶段形成了当前 Planner Chat 的基础。

---

## 9.6 搜索阶段

逐步增加：

- 搜索 Provider
- URL 提取
- 网页获取
- 搜索查询规划
- 多供应商
- 搜索结果折叠

核心经验：

> 搜索能力应该作为 Planner 的辅助上下文，而不是成为独立控制面。

---

## 9.7 Execution / Outbox / MQ 阶段

逐步建立：

```text
ExecutionCommand
↓
Outbox
↓
Publisher
↓
RabbitMQ
↓
Consumer
↓
Agent
```

同时保留：

```text
DB Poller
```

作为兜底。

主要解决：

- 消息可靠投递
- Confirm
- Retry
- Poller Recovery
- MQ 故障
- 执行命令可靠性

---

## 9.8 Artifact / Final Report 阶段

执行结果从单纯：

```text
String Output
```

逐步演进为：

```text
Artifact
↓
Manifest
↓
多文件
↓
ZIP
↓
Final Report
```

同时增加：

```text
NONE
GENERATING
DONE
FAILED
```

独立报告状态。

---

## 9.9 Reviewer 阶段

逐步形成：

```text
Execution
↓
Reviewer
↓
评分
↓
问题
↓
Pass / Reject / Rework
```

同时增加：

- Review History
- Review Message
- Reviewer fallback
- 自动审查

---

## 9.10 Model / Provider 管理阶段

逐步形成：

```text
Provider
↓
Model
↓
Default Model
↓
Agent Role Model
```

并支持：

- Provider 管理
- Model 管理
- 默认模型
- Agent 注册时模型约束
- Model 启停
- Model 删除保护

---

## 9.11 Monitoring 阶段

逐步加入：

```text
Prometheus
Grafana
Actuator
RabbitMQ Metrics
Redis Metrics
PostgreSQL Metrics
```

建立基础运行监控能力。

---

# 10. 重要历史决策

## 10.1 不复制第二套控制面

外部项目可以提供：

```text
架构思想
设计模式
实现参考
```

但不能直接复制其：

```text
Control Plane
Workflow Engine
Infrastructure
```

进入 HelloAI。

---

## 10.2 Poller 与 MQ 共存

当前设计不是：

```text
MQ 替代 DB
```

而是：

```text
DB / Outbox
+
MQ
+
Poller Recovery
```

共同构成可靠执行链。

---

## 10.3 Draft 与 Execution 隔离

Planner 自动拆解产生：

```text
PENDING_PLAN_REVIEW
```

不得直接进入：

```text
EXECUTION
```

必须经过用户确认。

---

## 10.4 Controller 不承载业务逻辑

历史上曾存在 Controller 直接访问 Mapper 的情况。

后续逐步收口为：

```text
Controller
↓
Service
↓
Repository / Mapper
```

当前 Controller 层应遵守：

> 参数接收 + DTO 转换 + 返回封装。

---

## 10.5 Agent 状态不重复建模

历史讨论中曾考虑增加：

```text
IDLE
WORKING
INTERRUPTED
```

等执行状态。

最终原则：

> 如果能够从任务 / 执行数据推导，则优先查询推导，不重复增加数据库状态字段。

---

# 11. 历史版本迁移说明

旧版本日志中存在大量：

```text
Vxx
Phase x
Nxx
A0-x
PR-x
```

这些编号继续保留在历史归档中。

但从 V2 开始：

> 新文档不再依赖这些编号理解当前系统。

例如：

```text
V40
V41
V42
```

只能说明：

> 某个历史阶段曾经发生过变化。

不能说明：

> 当前代码一定仍然采用该实现。

---

# 12. 历史 Log 的使用原则

AI 如果需要回答：

> “为什么这里这么设计？”

可以读取 Log。

如果需要回答：

> “现在这里是什么？”

不要首先读取 Log。

应该：

```text
当前代码
↓
数据库
↓
基线
```

---

# 13. Log 与 Git 的关系

Git 是：

> **代码历史事实源。**

Log 是：

> **工程决策历史事实源。**

两者互补。

Git 负责：

```text
谁改了什么
什么时候改
具体 diff
```

Log 负责：

```text
为什么改
为什么选择这个方案
遇到了什么问题
验证结果是什么
```

---

# 14. Log 与 Design 的关系

Design：

> 应该怎么设计。

Log：

> 最终实际上怎么做了。

因此一次重要功能可能形成：

```text
Gap
 ↓
Design
 ↓
Code
 ↓
E2E
 ↓
Log
 ↓
Baseline
```

---

# 15. 新增 Log 的最低要求

至少必须有：

```text
背景
问题
决策
结果
```

涉及代码时建议增加：

```text
修改范围
验证
风险
关联文档
```

---

# 16. 不要为了“完整”而记录

Log 的目标不是：

> 什么都记录。

而是：

> 让未来的人知道重要事情为什么发生。

因此：

```text
普通 Bug
小型字段修改
简单重命名
格式调整
```

不一定需要单独记录。

---

# 17. 当前 Log 文件治理规则

单个 Log 文件：

> 建议不超过 500～1000 行。

达到上限时：

```text
拆月
```

或者：

```text
按主题拆分
```

不要继续无限增长。

---

# 18. 归档规则

历史文件只读。

例如：

```text
doc/log/archive/
```

中的文件：

- 不再修改
- 不作为当前状态依据
- 不作为新功能设计依据
- 只用于历史追溯

---

# 19. 最终文档关系

HelloAI 文档体系最终保持：

```text
                    当前代码
                       │
                       ▼
                ┌─────────────┐
                │ 项目基线     │
                │ 当前是什么   │
                └─────────────┘
                       │
                       ▼
                ┌─────────────┐
                │ 实现差距     │
                │ 还缺什么     │
                └─────────────┘
                       │
                       ▼
                ┌─────────────┐
                │ Design      │
                │ 怎么实现     │
                └─────────────┘
                       │
                       ▼
                ┌─────────────┐
                │ Code        │
                │ 实际实现     │
                └─────────────┘
                       │
                       ▼
                ┌─────────────┐
                │ Log         │
                │ 做过什么     │
                └─────────────┘
                       │
                       ▼
                ┌─────────────┐
                │ Archive     │
                │ 历史资料     │
                └─────────────┘
```

---

# 20. 最终原则

Log 的价值不是记录：

> HelloAI 写过多少代码。

而是记录：

> **HelloAI 为什么变成今天这个样子。**

如果某条历史记录已经无法帮助未来开发者理解：

```text
为什么这么设计？
```

则没有必要继续维护。

---