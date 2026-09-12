# HelloAI 目标架构

> **状态：TARGET ARCHITECTURE**
>
> 本文档定义未来稳定架构边界，不表示所有能力当前已经落地。
>
> 最后更新：2026-09-12（新增 §12 基础架构（平台底座）：RBAC 用户/角色/权限/菜单底座纳入目标架构，与业务五层正交；实施见《HelloAI 基础架构调整实施计划》BASE-xxx 批次）

# 1. 目标定位

> **HelloAI 是面向跨终端、跨厂商异构 AI Agent 的分布式协作与治理平台。**

HelloAI 不重新实现底层 Coding Agent，而是让不同 Agent 在统一的平台上完成：

```text
Planning
→ Orchestration
→ Distributed Execution
→ Review
→ Governance
```

# 2. 目标架构

```text
                         HelloAI Platform
                                │
                ┌───────────────┴───────────────┐
                │                               │
             Planning                     Governance
                │                               │
        Requirement Package                Quality Gate
                │                               │
             Planner                            │
                │                               │
                └───────────────┬───────────────┘
                                ↓
                        Workflow Engine
                                │
                                ↓
                     Distributed Scheduler
                                │
                                ↓
                         Agent Runtime
                                │
        ┌───────────────────────┼───────────────────────┐
        ↓                       ↓                       ↓
     Context                  Skill                    Tool
        │                       │                       │
        │                Capability Package        MCP / API
        │                       │                       │
        └───────────────────────┼───────────────────────┘
                                ↓
                         Sandbox Provider
                                │
                                ↓
                          Agent Provider
                                │
              ┌─────────────────┼─────────────────┐
              ↓                 ↓                 ↓
            Qoder              Trae             Codex
              │                 │                 │
              └─────────────────┼─────────────────┘
                                ↓
                       Agent Event Stream
                                │
                ┌───────────────┼───────────────┐
                ↓               ↓               ↓
              Audit          Metrics          Replay
                                │
                                ↓
                            Recovery
```

# 3. 五层职责

## Role Layer

```text
Planner
Reviewer / Quality Gate
Governance
```

只表达平台角色职责，不包含具体 Agent Provider 实现。

### Planner 目标形态（G-010 / G-011 定界，2026-09-09）

V2 目标态的 Planner 拆解链路（两次增强后）：

```text
Requirement Package（需求包：goal / scope / outOfScope / assumptions / openQuestions）
        ↓
能力感知拆解（技能目录注入 + 执行者画像 + 难度感知）
        ↓
粒度自适应（FINE / STANDARD / COARSE，rule-based 矩阵；LLM 自判后置）
        ↓
子任务契约（requiredSkills / constraints / uncertainties[ASSUMPTION|UNCONFIRMED]）
```

定界原则：

- **拆解不虚构能力**：技能指派必须命中平台技能目录，未命中丢弃并审计（幻觉标签零容忍）；
- **推断不伪装成事实**：不确定性显式分级登记——ASSUMPTION 执行者自验证、UNCONFIRMED 上报人工裁决；
- **粒度不是越细越好**：细=步骤 + 每步 Skill + 输入/输出契约 + DoD；粗=目标 + 约束 + DoD；
- **不建自动闸门**：openQuestions 不阻断拆解/派发，裁决点在草案确认（人工）与执行侧（fail-close 走既有 BLOCKED 链）；不建"需求管理中心"平行架构；
- **契约向后兼容**：REST / inbox 只增可选字段，外部 Agent 未升级无感知。

设计文档：`doc/design/Planner_Capability_Awareness.md`（G-010，S1~S4 已落地含双场景实测，2026-09-09~10）、`doc/design/Requirement_Package_Uncertainty.md`（G-011，S1~S5 已落地含双场景实测，2026-09-09~10）。

## Orchestration Layer

```text
Workflow
Scheduler
DAG
Dependency
Parallelism
Routing
```

负责一个团队/任务整体如何运行。

## Runtime Layer

```text
AgentRuntime
Context
Session
AgentLoop
```

负责一次 Agent Turn 如何实际执行。

## Capability Layer

```text
Skill Package
Tool
MCP
Sandbox Provider
```

负责 Runtime 可以获得哪些能力，以及在哪个环境中执行。

## Provider Layer

```text
Qoder
Trae
Codex
Claude Code
DeepSeek
...
```

通过统一 Provider Contract 接入。

# 4. AgentRuntime 边界

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

Runtime 不负责：

```text
Global Scheduler
Workflow 全局状态
Planner Decision
Reviewer Decision
Agent Fleet Routing
```

# 5. Event Stream

目标统一模型：

```text
Run
 ├── Turn
 │    ├── Event
 │    └── Event
 └── Turn
      └── Event
```

原则：

> **Event 记录发生过什么；业务状态机记录当前是什么状态。**

不要求通过 Event Stream 重写所有业务状态。

# 6. Skill Capability Package

目标：

```text
Skill Package
├── Name
├── Version
├── Description
├── Instructions
├── RequiredTools
├── Dependencies
├── InputSchema
├── OutputSchema
└── ValidationRules
```

Markdown 可以继续作为 Instructions 的存储载体。

Skill 生命周期：

```text
Discover
→ Resolve
→ Load
→ Execute
→ Validate
```

# 7. Sandbox Provider

目标：

```text
AgentRuntime
      ↓
SandboxProvider
      ↓
Execution Environment
```

可能实现：

```text
Local
Docker
Remote
K8s
```

但真正安全隔离还必须覆盖文件、网络、进程、资源和凭证边界。

# 8. Agent Fleet

每个 Agent 应拥有：

```text
Provider
Terminal
Capabilities
Skills
Health
Concurrency
Cost
Latency
Historical Success
```

未来选择逻辑：

```text
Task Requirements
      ↓
Capability Match
      ↓
Health / Load
      ↓
Policy
      ↓
Agent Selection
```

# 9. 最终执行链

```text
Requirement
   ↓
Requirement Package（goal / scope / outOfScope / assumptions / openQuestions）
   ↓
Planner（能力感知 + 粒度自适应；子任务级技能指派 / 约束 / 不确定性登记）
   ↓
Workflow
   ↓
Distributed Scheduler
   ↓
AgentRuntime
   ↓
Skill + Tool + Sandbox
   ↓
Heterogeneous Agent
   ↓
Agent Event Stream
   ↓
Reviewer / Quality Gate
   ↓
PASS / REWORK / HUMAN_REVIEW / BLOCK
```

# 10. Harness 的角色

DeepSeek Harness 是**重要的 Agent Runtime 参考架构**，但不是 HelloAI 的目标产品。

吸收重点：

```text
1. Event / Trajectory 统一化
2. Skill → Capability Package
3. Sandbox Provider 抽象
```

不以复制 Harness 全部内部实现为目标。

# 11. 非目标

```text
❌ DeepSeek Harness Clone
❌ 单一 Coding Agent Runtime
❌ 单一模型平台
❌ 第二套 Scheduler
❌ 第二套 Workflow Runtime
❌ 外部 Agent 绕过平台状态机
```

# 12. 基础架构（平台底座）

> 定界（2026-09-12）：平台基础架构专项（用户 / 角色 / 权限 / 菜单底座）纳入目标架构，
> 与业务五层（Planning / Orchestration / Runtime / Capability / Provider）**正交不冲突**。
> 实施编排见 `doc/HelloAI 基础架构调整实施计划.md`（任务编号 BASE-xxx，独立于业务主线编号）。

## 12.1 定位

RBAC 是平台治理（Governance）之下、所有业务模块共用的支撑底座：

```text
业务模块（任务 / 子任务 / Agent / 质量 / 事件流 / 积分 / 团队 …）
        │
        ↓
基础架构（平台底座）
  用户 ↔ 角色 ↔ 权限（菜单 / 接口 / 按钮）
        │
  会话：Sa-Token（admin 浏览器端；agent 走 API Key / MCP，不入本会话体系）
```

## 12.2 目标边界

```text
数据模型（唯一事实源）
  sys_user / sys_role / sys_permission（type=MENU|API，parent_id 承载菜单树）
  sys_user_role / sys_role_permission
  ├── 权限码动作级（:view / :add / :edit / :delete），SUPER_ADMIN 通配 "*"
  └── 菜单树字段：path / icon / component / sort（可扩展 hidden / keepAlive / 外链）

会话与鉴权
  登录会话：Sa-Token（X-Admin-Token 头，active-timeout 滑动续期，Redis 存储）
  存量会话无缝迁移：旧自建 Redis 会话以原 token 重建，前端零感知
  接口鉴权：@SaCheckPermission（动作级权限码）→ 401 / 403 语义
  前端权限：动态路由（权限 = 路由可达性，无权限 URL 404）+ v-auth 按钮级

管理面
  用户管理（分页 / 分配角色 / 重置密码）
  角色管理（CRUD + 权限绑定，差异更新）
  菜单管理（可视化菜单树 CRUD，DB 化可运维）
  权限查询（权限码列表 / 菜单树按用户过滤）
```

## 12.3 边界原则

- **单事实源**：权限判定只走 sys_role_permission → sys_permission + Sa-Token StpInterface，
  不建第二套权限体系、不做权限双写；
- **与业务正交**：本底座不触碰 Agent Event Stream / 业务状态机 / Scheduler / Workflow /
  Review Runtime；业务模块按需声明权限码即可接入；
- **外部 Agent 不迁移**：CLI_CLIENT 契约（API Key / MCP）保持不变，不进 Sa-Token 会话体系；
- **渐进演进**：按 `doc/HelloAI 基础架构调整实施计划.md` 分批次实施（动态路由 / 按钮权限 →
  菜单管理 / 差异更新 → 扩展能力），完成后回填基线。
