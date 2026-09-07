# HelloAI AI 开发协作规约

> **状态：ACTIVE / 项目级开发规约**
>
> 适用对象：Qoder、Trae、Codex、Claude Code 及其他参与 HelloAI 代码修改的 AI 编程 Agent。
>
> 最后更新：2026-09-07

---

# 1. 目的

本规约用于约束 AI 编程 Agent 对 HelloAI 的：

- 代码修改；
- 架构重构；
- 测试验证；
- PowerShell 验证；
- 文档回填；
- Git 变更边界。

核心目标不是限制 AI，而是避免多轮 AI 协作造成：

```text
架构漂移
+
重复抽象
+
重复实现
+
测试遗漏
+
文档失真
```

HelloAI 当前处于一次重要架构收敛阶段，因此**任何 AI 都不得自行重新定义项目方向**。

---

# 2. HelloAI 当前与目标定位

## 2.1 项目当前定位

HelloAI 当前是一个：

> **面向异构 AI Agent 的分布式任务编排与执行平台。**

当前已经具备：

```text
Planner
Task / SubTask
Workflow / DAG
Scheduler
ExecutionCommand
Executor / Runtime
Agent
Skill
Tool / MCP
Execution Environment
Reviewer
MQ / Outbox
Retry / Timeout
Heartbeat / Reconcile
DLQ
Execution Record / Event
```

当前代码已经具备较强的分布式执行和治理基础。

---

## 2.2 长期目标

HelloAI 的目标定位为：

> **面向跨终端、跨厂商异构 AI Agent 的分布式协作与治理平台。**

目标能力：

```text
跨厂商
跨终端
多 Agent
多任务并发
分布式调度
可恢复执行
统一执行轨迹
可插拔能力
质量验收
治理与审计
```

---

# 3. 当前架构方向

当前主链路：

```text
User
  ↓
Planner
  ↓
Task / SubTask
  ↓
Workflow / DAG
  ↓
Scheduler
  ↓
ExecutionCommand
  ↓
Execution Layer
  ↓
Agent / Agent Runtime
  ↓
Execution Result
  ↓
Reviewer
  ↓
PASS / REWORK
  ↓
State Convergence
```

横向基础设施：

```text
PostgreSQL
Redis
RabbitMQ
Outbox
Idempotency
Lock
Lease
Heartbeat
Reconcile
Retry
Timeout
DLQ
Event
```

---

# 4. 当前架构重构主线

当前阶段严格按照以下顺序推进：

```text
P0-A
Agent Event Stream
        ↓
P0-B
Dual Executor Migration
        ↓
P0-C
AgentRuntime
        ↓
P1
Skill Capability Package
        ↓
P1
Sandbox Provider
        ↓
P1
Event Consumer / Replay / Recovery
        ↓
P2
Quality Governance / Agent Fleet
        ↓
P3
Dynamic Workflow
```

**不得跳过 P0 直接扩展 P2/P3。**

---

# 5. 文档优先级

AI 修改代码前必须阅读：

```text
1. README.md
2. HelloAI 项目基线文档.md
3. HelloAI 目标架构.md
4. HelloAI 实现差距表.md
5. HelloAI 重构实施计划.md
6. HelloAI_CODE_STYLE.md
```

涉及专项任务时，再阅读：

```text
design/Agent_Event_Stream.md
design/Agent_Runtime.md
design/Skill_Capability.md
design/Sandbox_Provider.md
design/adr/*
```

## 5.1 历史文档

以下目录只允许作为历史参考：

```text
archive/**
```

严禁：

```text
从 archive 恢复旧架构
根据 archive 自动生成新 TODO
把历史 Phase 重新解释为当前 Roadmap
```

如果当前代码与 archive 冲突：

> **以当前代码和当前基线为准。**

---

# 6. 文档职责边界

## 6.1 项目基线

```text
HelloAI 项目基线文档.md
```

只回答：

> **现在真实有什么？**

允许：

```text
已实现
当前架构
当前表/对象
当前模块
当前执行链路
当前可靠性能力
当前已知限制
```

禁止：

```text
未来设计
TODO
长期 Roadmap
未经验证的能力
```

---

## 6.2 目标架构

```text
HelloAI 目标架构.md
```

只回答：

> **HelloAI 要演进成什么？**

---

## 6.3 实现差距表

```text
HelloAI 实现差距表.md
```

只回答：

> **Current → Target 差什么？**

状态：

```text
TODO
DESIGNING
IMPLEMENTING
VERIFYING
DONE
PARTIAL
BLOCKED
DEFERRED
WONTFIX
```

---

## 6.4 重构实施计划

```text
HelloAI 重构实施计划.md
```

只回答：

> **现在先做什么、后做什么？**

---

## 6.5 design

```text
design/**
```

只用于保留仍具有实施价值的专项设计。

---

## 6.6 log

```text
log/HelloAI 架构变更记录.md
```

只记录重大架构决策和变更原因。

不作为当前设计入口。

---

# 7. DeepSeek Harness 的借鉴边界

HelloAI 借鉴 DeepSeek Harness 的核心思想，但：

> **不以复制 DeepSeek Harness 为目标。**

当前明确吸收的三个重点：

## 7.1 Event / Trajectory 统一化

目标：

```text
AgentRun
  ↓
AgentEvent Stream
```

统一记录：

```text
Run
Turn
Step
Skill
Tool
Agent
Review
Retry
System
```

原则：

> **Event Stream 记录发生过什么；业务状态机决定现在是什么状态。**

不得把 Event Stream 设计为第二套业务状态机。

---

## 7.2 Skill → Capability Package

当前 Skill 仍允许使用 Markdown / instructions。

未来逐渐增加：

```text
Metadata
Version
Instructions
Required Tools
Dependencies
Input Schema
Output Schema
Validation Rules
```

目标：

```text
Discover
→ Resolve
→ Load
→ Execute
→ Validate
```

Skill 不得绑定具体 Planner / Executor / Reviewer 实现。

---

## 7.3 Sandbox Provider

目标：

```text
AgentRuntime
    ↓
SandboxProvider
    ↓
Execution Environment
```

SandboxProvider 可以支持：

```text
Local
Docker
Remote
K8s
```

注意：

> `ExecutionEnvironment` / `Provider` 抽象，不等于已经完成安全沙箱。

真正安全隔离还需要：

```text
Filesystem
Network
Process
Resource
Credential
```

边界。

---

# 8. Role 与能力必须解耦

核心原则：

```text
Role ≠ Execution Implementation
Role ≠ Agent Provider
Role ≠ Skill
Role ≠ Tool
```

### Planner

负责：

```text
Requirement
→ Plan
```

不负责具体 Agent 执行。

### Scheduler

负责：

```text
Task
→ Agent Assignment
→ Execution Dispatch
```

不负责 Agent 内部工具循环。

### AgentRuntime

负责：

```text
Context
Session
Skill
Tool
Environment
Agent Execution
Event
```

不负责：

```text
Global Scheduling
Task State Machine
Reviewer Decision
Agent Fleet Routing
```

### Reviewer / Quality Gate

负责：

```text
Result
+
Evidence
+
Trajectory
→
Quality Decision
```

---

# 9. AI 修改代码的标准生命周期

所有开发任务必须按以下顺序：

```text
Step 0
确认工作区
↓
Step 1
读取文档
↓
Step 2
阅读相关代码
↓
Step 3
输出实现计划
↓
Step 4
修改代码
↓
Step 5
单元 / 模块测试
↓
Step 6
执行相关 PowerShell 验证脚本
↓
Step 7
Git diff / status
↓
Step 8
文档回填
↓
Step 9
输出实施报告
```

未经分析和计划：

> **不得直接大范围修改代码。**

---

# 10. Step 0：工作区检查

AI 开始前必须检查：

```powershell
git status --short
git branch --show-current
```

要求：

- 不覆盖用户未提交修改；
- 不擅自 reset；
- 不擅自 checkout；
- 不删除未由当前任务创建的文件；
- 发现工作区已有修改时先分析影响。

---

# 11. Step 1：文档阅读

必须明确报告：

```text
已读取：
- 项目基线
- 目标架构
- 实现差距
- 实施计划
- Code Style
- 对应专项设计
```

同时明确：

```text
当前任务属于：
P0 / P1 / P2 / P3
```

---

# 12. Step 2：代码调查

修改前必须定位：

```text
入口类
接口 / Contract
核心 Service
Mapper / Repository
Entity
数据库 Migration
MQ Producer / Consumer
Event
相关 Test
```

不得：

```text
只看文件名猜实现
只看接口不看调用链
只看 Service 不看 MQ / DB
```

---

# 13. Step 3：先输出实现计划

必须输出：

```text
1. 当前实现
2. 当前问题
3. 目标状态
4. 修改范围
5. 修改文件
6. 新增文件
7. 删除文件
8. DB 影响
9. API / MQ 影响
10. 兼容性影响
11. 风险
12. 测试方案
13. PowerShell 验证方案
14. 回滚方案
```

如果发现需要修改计划之外的模块：

> 必须暂停扩展，说明原因。

---

# 14. Step 4：最小变更原则

优先：

```text
接口
+
Adapter
+
Provider
+
Composition
```

避免：

```text
大规模继承
God Class
巨型 Executor
if/else 厂商分支
复制已有 Runtime
复制已有 Scheduler
重复 State Machine
```

尤其禁止：

> 为了实现一个 P0 任务顺手重构整个项目。

---

# 15. P0-A：Agent Event Stream 开发规则

当前第一个重点。

目标：

```text
Execution Record
        ↓
AgentRun + AgentEvent
```

至少保证：

```text
runId
eventId
sequence
eventType
timestamp
actor
correlationId
payload
```

建议支持：

```text
causationId
```

## 15.1 必须遵守

```text
Event = execution fact
State = business truth
```

不得：

```text
Event → 第二状态机
```

不得一边保留旧 Event，一边创建第二套新的事实流。

## 15.2 兼容原则

旧执行入口：

```text
Legacy Executor
```

未来执行入口：

```text
AgentRuntime
```

均必须输出相同 Event Contract。

---

# 16. P0-A 验证脚本

当前已有正式脚本中，优先使用：

```text
verify-c3-events.ps1
verify-c3-reconcile.ps1
verify-c3-route.ps1
verify-execution-dispatch-guard.ps1
```

并根据实际改动补充对应执行链脚本。

如果任务涉及完整消息链：

```text
verify-poller-e2e.ps1
verify-outbox-relay-confirm-e2e.ps1
```

注意：

> 不是每次运行全部 70 个脚本。

必须根据任务范围选择：

```text
Required
+
Regression
+
Diagnosis
```

---

# 17. P0-B：Dual Executor

双轨只是迁移方案：

```text
ExecutionRouter
      │
 ┌────┴────┐
 ↓         ↓
Legacy     Runtime
```

必须：

```text
统一 Event
统一 Result
统一 State Convergence
统一 Review
统一 Idempotency
统一 Error Model
```

禁止：

```text
两套状态机
两套 Review
两套 Task Lifecycle
复制整个业务执行链
```

特别注意：

> **不能通过简单“fallback 再执行一次”来恢复带副作用的任务。**

涉及：

```text
Git write
Build
Deploy
External mutation
```

必须明确幂等 / Compensation / Execution ownership。

---

# 18. P0-B 验证脚本

优先：

```text
verify-c3-route.ps1
verify-execution-dispatch-guard.ps1
verify-c3-events.ps1
```

消息链需要时：

```text
verify-poller-e2e.ps1
verify-outbox-relay-confirm-e2e.ps1
```

---

# 19. P0-C：AgentRuntime

目标：

```text
AgentRuntime
├── AgentContext
├── Session
├── SkillResolver
├── ToolRegistry
├── ToolExecutor
├── AgentLoop
├── SandboxProvider
└── EventRecorder
```

但不得一次性全部重构。

推荐：

```text
Phase 1
Context + Event

Phase 2
Tool Registry / Executor

Phase 3
Agent Loop

Phase 4
Session / Sandbox
```

Runtime 不得直接依赖：

```text
Planner
Scheduler
Reviewer
```

---

# 20. AgentRuntime 验证脚本

当前优先使用：

```text
verify-agent-execution-preview.ps1
verify-agent-skill-capability.ps1
verify-tool-matrix.ps1
verify-contract-first.ps1
verify-code-style-p0-layer.ps1
```

如果新增 Runtime Contract，应补充专用 Contract Test。

---

# 21. Skill 开发规则

当前阶段：

> 不重写现有 Skill 系统。

只进行增量增强：

```text
name
version
description
instructions
requiredTools
```

后续再增加：

```text
dependencies
inputSchema
outputSchema
validationRules
```

已有：

```text
requiredSkills
```

保持兼容。

相关验证：

```text
verify-agent-skill-capability.ps1
verify-a2-skill-derive.ps1
verify-a3b-agent-edit-skills.ps1
verify-tool-matrix.ps1
```

---

# 22. Sandbox 开发规则

当前第一阶段重点是：

```text
SandboxProvider Contract
```

而不是立刻实现完整 Docker/K8s 安全体系。

已有：

```text
ExecutionEnvironment
ExecutionEnvironmentProvider
RemoteAgent
LocalProcess
```

不得在没有实际隔离能力的情况下宣称：

```text
“安全沙箱已经完成”
```

---

# 23. PowerShell 验证体系

当前项目已有约 70 个正式 PowerShell 脚本，主要位于：

```text
scripts/powershell/
```

因此：

> **禁止把“新增 test.ps1 / verify.ps1 / smoke.ps1”作为默认方案。**

应使用现有验证资产。

---

# 24. PowerShell 脚本分类原则

## 24.1 开发/启动脚本

例如：

```text
start-sb.ps1
start-sb-e2e-mq.ps1
restart-sb-mock.ps1
kill-old.ps1
```

## 24.2 架构 / Contract

```text
verify-contract-first.ps1
verify-dependency-direction.ps1
verify-code-style-p0-layer.ps1
verify-code-style-p1-paths.ps1
verify-code-style-p1-ui-sync.ps1
```

## 24.3 Execution / Dispatch

```text
verify-c3-route.ps1
verify-execution-dispatch-guard.ps1
verify-poller-e2e.ps1
verify-task-running-spec-phase-b.ps1
verify-subtask-redispatch-auto-execution.ps1
```

## 24.4 Event / Reconcile

```text
verify-c3-events.ps1
verify-c3-reconcile.ps1
verify-c3-rollback.ps1
```

## 24.5 Skill / Tool / Agent

```text
verify-agent-execution-preview.ps1
verify-agent-skill-capability.ps1
verify-tool-matrix.ps1
verify-agent-llm-connectivity.ps1
```

## 24.6 Review / Quality

```text
verify-reviewer-dual.ps1
verify-quality-profile.ps1
verify-quality-dashboard.ps1
verify-artifact-content-review.ps1
```

## 24.7 MCP

```text
verify-mcp.ps1
verify-mcp-auth.ps1
verify-mcp-e2e.ps1
verify-mcp-session-e2e.ps1
```

## 24.8 Agent Onboarding / Heartbeat

```text
verify-onboarding.ps1
verify-onboarding-doorbell.ps1
verify-onboarding-heartbeat.ps1
verify-onboarding-pull.ps1
verify-onboarding-submit.ps1
verify-dashboard-duty-leases.ps1
```

---

# 25. 每个任务必须形成验证集合

AI 必须在实施计划中明确：

```text
Required Tests
Regression Tests
Diagnosis Scripts
```

例如 P0-A Event Stream：

```text
Required:
verify-c3-events.ps1

Regression:
verify-c3-reconcile.ps1
verify-c3-route.ps1
verify-execution-dispatch-guard.ps1

Diagnosis:
.tmp/diag-agentevent.ps1
```

`.tmp` 里的脚本只能用于诊断，不得自动作为正式验证脚本。

---

# 26. `.tmp` 目录使用规则

当前 `.tmp/` 中存在大量：

```text
diag-*
dump-*
probe-*
compile-*
extract-*
*.log
*.sql
截图
临时 commit message
```

这些内容属于开发现场产物。

规则：

```text
.tmp = 临时诊断区
```

禁止：

```text
把 .tmp 当正式测试体系
把 .tmp 当产品源码
把 .tmp 作为架构文档来源
```

真正有长期价值的诊断脚本，经确认后再迁移到：

```text
scripts/powershell/
```

不得在 P0 重构期间顺手进行 `.tmp` 大清理，避免扩大任务范围。

---

# 27. Test / PS1 完成标准

AI 不得使用：

```text
“应该没问题”
“代码看起来正确”
“逻辑上已完成”
```

代替实际验证。

必须区分：

```text
PASS
FAIL
NOT RUN
BLOCKED
NOT APPLICABLE
```

例如：

```text
Unit Test: PASS
Integration Test: PASS
PS1: PASS
Smoke: NOT RUN
Reason: 本任务不涉及完整启动链
```

---

# 28. Git Diff 自检

完成代码后必须执行：

```powershell
git status --short
git diff --stat
git diff
```

检查：

```text
是否修改了计划外文件？
是否产生无关格式化？
是否删除已有逻辑？
是否出现重复类？
是否偷偷修改配置？
是否增加无关依赖？
是否修改 Migration？
是否修改 Prompt？
```

如果发现计划外修改：

> 必须回退或说明原因。

---

# 29. 数据库变更规则

如果修改：

```text
Entity
Mapper
Repository
Record
Event
```

必须检查是否涉及 DB Schema。

需要新增 Migration 时：

```text
Vxx__description.sql
```

必须：

```text
说明原因
检查兼容性
考虑已有数据
提供验证方式
```

禁止：

```text
直接修改历史 Migration
```

除非任务明确就是修复尚未发布的本地 Migration。

---

# 30. MQ / Outbox 变更规则

修改：

```text
Message
Producer
Consumer
Outbox
DLQ
```

必须检查：

```text
幂等
重复投递
顺序
Confirm
Retry
Dead Letter
State Convergence
```

不得只修改 Consumer 而不检查发送端。

---

# 31. 状态机修改规则

任何修改：

```text
Task Status
SubTask Status
Execution Status
Agent Status
Review Status
```

都必须说明：

```text
允许的 State Transition
并发条件
幂等策略
Version / CAS
Lease
Timeout
Retry
```

禁止通过字符串比较直接绕过状态机。

---

# 32. 文档回填规则

代码和测试完成后，根据实际结果更新文档。

## 仅实现功能

更新：

```text
HelloAI 实现差距表.md
```

## 当前真实架构发生变化

更新：

```text
HelloAI 项目基线文档.md
```

## 目标边界发生变化

更新：

```text
HelloAI 目标架构.md
```

## 实施顺序发生变化

更新：

```text
HelloAI 重构实施计划.md
```

## 出现重大架构决策

更新：

```text
log/HelloAI 架构变更记录.md
```

## 专项设计发生变化

更新对应：

```text
design/*.md
```

---

# 33. 严禁“设计完成 = 代码完成”

必须严格区分：

```text
DESIGNED
IMPLEMENTED
VERIFIED
```

例如：

```text
Skill Capability Package

DESIGNED      ✅
IMPLEMENTED   ⚠️ Partial
VERIFIED      ❌
```

不得写成：

```text
Skill Capability Package 已完成
```

---

# 34. 完成报告模板

每次代码任务结束必须输出：

```markdown
# Implementation Report

## 1. Task
P0-A / P0-B / P0-C / ...

## 2. Objective
本次解决什么问题。

## 3. Scope
本次允许修改什么。

## 4. Modified Files
- ...

## 5. Added Files
- ...

## 6. Removed Files
- ...

## 7. Architecture Impact
None / ...

## 8. Database Impact
None / Migration ...

## 9. MQ Impact
None / ...

## 10. Tests
- Unit: PASS
- Integration: PASS
- Contract: PASS

## 11. PowerShell Verification
- xxx.ps1: PASS
- xxx.ps1: PASS

## 12. Git Diff
Clean / ...

## 13. Documentation
- Base: Updated / Not Required
- Gap: Updated / Not Required
- Plan: Updated / Not Required
- ADR: Updated / Not Required

## 14. Remaining Gap
...

## 15. Risks
...

## 16. Rollback
...
```

---

# 35. AI 修改任务的推荐 Prompt 模板

每次任务不要只说：

> “帮我重构 XXX”。

推荐：

```text
你正在修改 HelloAI。

请严格遵循：

doc/README.md
doc/HelloAI 项目基线文档.md
doc/HelloAI 目标架构.md
doc/HelloAI 实现差距表.md
doc/HelloAI 重构实施计划.md
doc/HelloAI_CODE_STYLE.md

根据当前任务继续阅读对应 design 文档。

本次任务：

[填写具体任务]

当前阶段：

[P0-A / P0-B / P0-C / P1 ...]

要求：

1. 先分析，不立即修改。
2. 先输出当前实现、目标、差距、修改范围。
3. 明确修改文件和不修改文件。
4. 明确数据库 / MQ / API 影响。
5. 明确测试方案。
6. 明确需要运行的 PowerShell 脚本。
7. 只有分析完成后才实施。
8. 采用最小变更原则。
9. 不自行新增架构方向。
10. 不从 archive 恢复历史设计。
11. 完成后必须执行测试和 PowerShell 验证。
12. 必须执行 git diff/status。
13. 必须根据实际结果回填文档。
14. 最终输出 Implementation Report。

特别遵守：

Event Stream 是统一执行事实层；
AgentRuntime 是最终执行契约；
Skill 是 Capability Package 的演进方向；
Sandbox 使用 Provider 抽象；
Planner / Scheduler / Reviewer 不属于 Runtime 内部。

禁止：
- 创建第二套 Scheduler
- 创建第二套 Workflow Runtime
- 创建第二套 Review Runtime
- 为了“像 Harness”而复制 Harness
- 一次性重构多个未授权模块
```

---

# 36. P0-A 专用 Prompt

```text
执行 HelloAI P0-A：Agent Event Stream 统一化。

第一步只分析，不改代码。

重点检查：
- 当前 AgentEvent
- AgentRun
- Run / Turn / Step
- ExecutionRecord
- EventRecorder
- ExecutionResultHandler
- Timeline
- Audit
- 相关 DB Migration
- EventConsumer / Reconcile

目标：
建立统一 AgentRun + AgentEvent 执行事实模型。

要求：
1. 不做全量 Event Sourcing。
2. 不改变现有业务状态机语义。
3. 不建立第二套状态源。
4. Legacy Executor 和 AgentRuntime 使用同一 Event Contract。
5. Event 必须可幂等记录。
6. 保证 sequence 和 runId。
7. 不顺便实施 Skill / Sandbox / Workflow 重构。

验证优先：
- verify-c3-events.ps1
- verify-c3-reconcile.ps1
- verify-c3-route.ps1
- verify-execution-dispatch-guard.ps1

完成后：
更新实现差距表；
必要时更新项目基线；
如有架构决策，更新架构变更记录。
```

---

# 37. P0-B 专用 Prompt

```text
执行 HelloAI P0-B：Dual Executor Migration。

目标：

ExecutionRouter
      ↓
Runtime
      OR
Legacy Adapter

要求：
1. 双轨只是迁移策略。
2. 统一 Event。
3. 统一 Result。
4. 统一 State Convergence。
5. 统一 Review。
6. 统一幂等。
7. 支持灰度 / Feature Flag。
8. 明确 rollback。
9. 禁止副作用任务简单 fallback 重执行。
10. Legacy 不再继续扩张业务逻辑。

验证优先：
- verify-c3-route.ps1
- verify-execution-dispatch-guard.ps1
- verify-c3-events.ps1

根据消息链实际影响追加：
- verify-poller-e2e.ps1
- verify-outbox-relay-confirm-e2e.ps1
```

---

# 38. P0-C 专用 Prompt

```text
执行 HelloAI P0-C：AgentRuntime 抽象。

目标：
把属于“Agent Turn 执行”的能力从具体 Executor 业务代码中提取出来。

Runtime 负责：
- Context
- Session
- Agent Execution
- Skill
- Tool
- Environment
- Event

Runtime 不负责：
- Planner
- Global Scheduler
- Task State Machine
- Reviewer Decision
- Agent Fleet Routing

请分阶段实施：

第一阶段：
Context + EventRecorder

第二阶段：
ToolRegistry + ToolExecutor

第三阶段：
AgentLoop

第四阶段：
Session + Sandbox

禁止：
- 一次性重写 Executor
- 大范围修改 Scheduler
- 大范围修改 Reviewer
- 创建第二套 Runtime
```

---

# 39. Review 阶段必须回答的 10 个问题

在任何重大重构合入前，AI 必须自检：

```text
1. 当前状态机有没有被绕过？
2. 是否新增了重复抽象？
3. Event 是否仍然只有一个事实模型？
4. Legacy / Runtime 是否共享 Contract？
5. 是否可能重复执行副作用？
6. 是否引入新的 MQ 重试环？
7. Skill 是否绑定了具体角色？
8. Sandbox 是否只是名称变化而没有真实隔离？
9. 是否有计划外代码变更？
10. 文档是否与代码真实状态一致？
```

---

# 40. 架构红线

以下行为必须停止并重新分析：

```text
❌ 为一个新厂商写 if/else
❌ 把厂商逻辑塞进 Planner
❌ 把调度逻辑塞进 Runtime
❌ 把 Review 决策塞进 Runtime
❌ 创建第二套 State Machine
❌ 创建第二套 Event Stream
❌ 让 Agent 直接修改 HelloAI 业务状态
❌ 让 Agent 绕过平台状态机
❌ 把 Markdown Skill 直接全部改掉而无迁移策略
❌ 宣称已有 Environment 就等于安全 Sandbox
❌ 未执行 PS1 就宣布完成
❌ 用 `.tmp` 日志代替正式验证
❌ 用历史 archive 文档覆盖当前架构
```

---

# 41. 最终开发闭环

HelloAI 的 AI 协作必须形成：

```text
Documentation
      ↓
Analyze
      ↓
Plan
      ↓
Implement
      ↓
Test
      ↓
PowerShell Verify
      ↓
Git Diff
      ↓
Documentation Update
      ↓
Architecture Review
      ↓
Commit
```

与 HelloAI 本身的产品理念保持一致：

```text
Plan
→ Orchestrate
→ Execute
→ Review
→ Govern
```

---

# 42. 最终原则

> **AI 可以实现方案，但不能自行定义 HelloAI 的架构方向。**

> **代码是真实状态，基线文档是真实状态的解释；目标架构是方向，差距表是未完成项，实施计划是当前行动顺序，ADR 是重大决策依据，archive 是历史。**

> **任何一次重构都必须保证：可验证、可回滚、可解释、可追踪。**

> **HelloAI 的核心方向是跨终端、跨厂商、分布式异构 Agent 协作；DeepSeek Harness 是 Agent Runtime / Capability / Execution Trace 设计的重要参考，而不是 HelloAI 的复制目标。**
