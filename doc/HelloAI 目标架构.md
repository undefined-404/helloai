# HelloAI 目标架构

> **状态：TARGET ARCHITECTURE**
>
> 本文档定义未来稳定架构边界，不表示所有能力当前已经落地。
>
> 最后更新：2026-09-30（**Document V2.1 治理**：新增 §0「实现状态（Implementation Status）」逐层状态标记，
> 各层补内联 `Status` 行。本文件自此只声明**目标边界 + 状态**；进度百分比与逐条差距一律以
> 《HelloAI 实现差距表》为**唯一事实源**，不在本文件复述）

# 0. 实现状态（Implementation Status）

> **状态四值**：`Implemented`（已达成）· `Partial`（部分达成）· `Planned`（未启动，已登记）· `Non-goal`（明确非目标）。
> **判定基线**：2026-09-30，HEAD `e2c7e8c`（代码实测 + 两份 09-30 审计，逐层实测见
> `doc/review/HelloAI 架构V2进度与质量审计报告（2026-09-30）.md`、`HelloAI 代码规范与架构偏离专项审计报告（2026-09-30）.md`）。

| 章节 | 层 / 能力 | 状态 | 差距锚点（唯一事实源 = 差距表） | 判定依据 |
|---|---|---|---|---|
| §3 | **Role Layer**（Planner / Reviewer·Quality Gate / Governance） | **Partial** | G-007 · G-012 · G-013 · G-016 | Planner ✅；Review 链 ✅；Governance（RBAC）✅ 批次一~四；**Quality Gate 泛化未做**（全仓无 `QualityGate` 类） |
| §3 | **Orchestration Layer**（Workflow / Scheduler / DAG / Dependency / Parallelism / Routing） | **Partial** | G-009 · G-010 · G-015 | Task·SubTask DAG / 依赖门禁 / 并行派发 / 路由 ✅；**Dynamic Workflow 语义未抽象**（P3 后置，且 §11 禁止新建第二套 Engine） |
| §3 | **Runtime Layer**（AgentRuntime / Context / Session / AgentLoop） | **Implemented** | G-002 · G-003 | 2026-09-30 单轨硬切，`RuntimeTurnExecutor` 为唯一 `AgentRuntime` 实现；八件套契约齐备；每轮 checkpoint 与 tokenUsage 已落库 |
| §3 | **Capability Layer**（Skill Package / Tool / MCP / Sandbox Provider） | **Partial** | G-004 · G-005 · G-008 | Skill 元数据层 ✅、Tool / MCP ✅；**Sandbox 仅契约、无真实隔离** |
| §3 | **Provider Layer**（Qoder / Trae / Codex …） | **Implemented** | G-014 · G-016 | 异构 Provider 契约清晰；外部 Agent 通道获 A 级端到端实证 |
| §5 | Event Stream（Run / Turn / Step + Replay · Audit） | **Partial** | G-001 · G-006 | 写侧 + Replay / Audit / UI 已上线；**Recovery / Fork 未建** |
| §6 | Skill Capability Package | **Partial** | G-004 | 9 个元数据字段已落地（含 `inputSchema` / `outputSchema` / `validationRules`）；**Instructions 结构化未动**；Discover→Validate 生命周期部分达成 |
| §7 | Sandbox Provider | **Planned** | G-005 | 契约已落地；**文件 / 网络 / 进程 / 资源 / 凭证五边界隔离未实现**（`EnvironmentSandboxProvider` 一律不标 ISOLATED） |
| §8 | Agent Fleet | **Partial** | G-008 · G-014 | Capability Match / Health ✅，多外部执行者同台已实证；**Cost / Latency 维度的 Fleet 化未做**（`tokenUsage` 已落库但未纳入选人策略） |
| §9 | 最终执行链 | **Implemented** | G-001 · G-002 · G-016 | 需求包 → Planner → Workflow → Scheduler → Runtime → Skill/Tool/Sandbox → 异构 Agent → Event Stream → Reviewer → PASS/REWORK/HUMAN_REVIEW/BLOCK 全链 A 级实证 |
| §10 | Harness | **Non-goal** | — | 明示为 Runtime 参考架构，非目标产品 |
| §11 | 非目标清单 | **Non-goal** | — | 显式列出并持续生效（含禁第二套 Scheduler / Workflow Runtime） |
| §12 | 基础架构（平台底座 RBAC） | **Implemented** | G-012 · G-013 | 批次一~四已实施（V77~V89）；BASE-4.5 建号 E2E 待重启复验 |

**维护规则（2026-09-30 新增）**

1. 本文件只声明**目标边界**与**状态标记**；**进度百分比、逐条差距、完成度一律以 `HelloAI 实现差距表.md` 为唯一事实源**，本文件不复述数字，避免双源漂移。
2. 状态变更须同步更新「判定依据」列（日期 + 代码/DB 证据出处）；只改状态不改依据视为未完成。
3. 历史迁移方案与已完成阶段性设计**不写入本文件**，一律进 `archive/`。
4. 权威优先级与 `README.md` 一致：**代码 / 可验证事实 > 项目基线 > 目标架构 > 实现差距 > 实施计划 > 历史归档**。

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

> **Status: Partial** — Planner ✅ / Reviewer ✅（Review 链）/ Governance ✅（RBAC 底座）；**Quality Gate 泛化未做**（无 `QualityGate` 类，差距表 G-007）。

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

> **Status: Partial** — DAG / Dependency / Parallelism / Routing 均有实际实现；**Workflow Engine 语义未抽象**（差距表 G-009，P3 后置；§11 明确禁止新建第二套 Workflow Runtime）。

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

> **Status: Implemented** — 2026-09-30 单轨硬切，`RuntimeTurnExecutor` 为唯一实现（差距表 G-002）。

```text
AgentRuntime
Context
Session
AgentLoop
```

负责一次 Agent Turn 如何实际执行。

## Capability Layer

> **Status: Partial** — Skill Package 与 Tool / MCP 已落地；**Sandbox Provider 仅契约**（差距表 G-004 / G-005 / G-008）。

```text
Skill Package
Tool
MCP
Sandbox Provider
```

负责 Runtime 可以获得哪些能力，以及在哪个环境中执行。

## Provider Layer

> **Status: Implemented** — 异构 Provider 契约清晰，外部 Agent 通道 A 级实证（差距表 G-014）。

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

> **Status: Implemented** — 边界已由代码强制：`agent/runtime/` 包零 Planner / Scheduler / Reviewer / Task Service 依赖。

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

> **Status: Partial** — Run/Turn/Step + Replay / Audit / UI 已上线；**Recovery / Fork 未建**（差距表 G-001 / G-006）。

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

> **Status: Partial** — 9 个元数据字段已落地；**Instructions 结构化未动**；Discover→Validate 生命周期部分达成（差距表 G-004）。

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

> **Status: Planned** — 契约已落地；**五边界真实隔离未实现**（`EnvironmentSandboxProvider` 一律不标 ISOLATED，差距表 G-005）。

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

> **Status: Partial** — Capability Match / Health 已落地且多外部执行者同台已实证；**Cost / Latency 未纳入选人策略**（差距表 G-008 / G-014）。

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

> **Status: Implemented** — 全链获 A 级端到端实证（内部单轨 + 外部 CLI_CLIENT 多 Agent）。
> 口径说明：本链按本文件原文终止于 `PASS / REWORK / HUMAN_REVIEW / BLOCK`；**Synthesis（报告整合）不是本链的独立环节**——它是 Role Layer 的待决策项（差距表 N2），不要把它读成本链既有阶段。

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

> **Status: Non-goal** — Harness 仅为 Runtime 参考架构，明确不是目标产品。

DeepSeek Harness 是**重要的 Agent Runtime 参考架构**，但不是 HelloAI 的目标产品。

吸收重点：

```text
1. Event / Trajectory 统一化
2. Skill → Capability Package
3. Sandbox Provider 抽象
```

不以复制 Harness 全部内部实现为目标。

# 11. 非目标

> **Status: Non-goal**（本清单本身即非目标声明，持续生效）。

```text
❌ DeepSeek Harness Clone
❌ 单一 Coding Agent Runtime
❌ 单一模型平台
❌ 第二套 Scheduler
❌ 第二套 Workflow Runtime
❌ 外部 Agent 绕过平台状态机
```

# 12. 基础架构（平台底座）

> **Status: Implemented** — 批次一~四已实施（V77~V89）；BASE-4.5 建号 E2E 待重启复验（差距表 G-012 / G-013）。

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
  ├── 身份单事实源：用户 ↔ 角色只经 sys_user_role（多对多）
  │   （sys_user.role 单字段已退场 —— 批次四 BASE-4.2 / V87）
  ├── 权限码动作级（资源:动作，如 task:add / subtask:execute / role:assign-perm）
  │   SUPER_ADMIN 由 StpInterface 返回 "*" 通配，自动覆盖全部权限码
  └── 菜单树字段：path / icon / component / sort / hidden / keepAlive / externalLink

会话与鉴权
  登录会话：Sa-Token 标准链路（X-Admin-Token 头，StpUtil.checkLogin，
             active-timeout 滑动续期真实生效，Redis 存储）
  存量会话无缝迁移：旧自建 Redis 会话以原 token 重建，前端零感知
  外部 Agent 通道：API Key / MCP 显式旁路，不进入 Sa-Token 会话（契约不变）
  接口鉴权：@SaCheckPermission（动作级权限码）→ 401 / 403 语义
            认证与授权分离：/api/admin/** 必须经 Admin 授权，不以「已登录」放行
  授权覆盖：适用接口 100% 动作级权限码化
            （管理面 109 接口 + 业务面适用写接口；Agent / 公开白名单通道除外）
  前端权限：动态路由（权限 = 路由可达性，无权限 URL 404）+ v-auth 按钮级

角色分层
  SUPER_ADMIN  通配 "*"：平台全能力
  ADMIN        管理面运维：系统设置（用户/角色/权限/菜单/部门）+ 业务读写
  NORMAL_USER  业务操作者：业务读写，无系统设置与平台配置
  GUEST        只读演示：仅 *:view 菜单 + 只读接口，全部写接口 403

管理面
  用户管理（分页 / 建号 / 分配角色 / 重置密码）
  角色管理（CRUD + 权限绑定，差异更新）
  菜单管理（可视化菜单树 CRUD，DB 化可运维）
  权限查询（权限码列表 / 菜单树按用户过滤）
  自助注册：受 sys_config 开关（auth.register.enabled，默认关闭）门控
```

## 12.3 边界原则

- **单事实源**：权限判定只走 sys_user_role → sys_role_permission → sys_permission +
  Sa-Token StpInterface，不建第二套权限体系、不做权限双写；身份不设 legacy 冗余字段。
- **认证收口**：登录态判定一律经 Sa-Token（`StpUtil.checkLogin` / `isLogin` / `StpInterface`），
  不自建并行的守门判定；Agent 通道作为显式旁路，不进会话体系。
- **与业务正交**：本底座不触碰 Agent Event Stream / 业务状态机 / Scheduler / Workflow /
  Review Runtime；业务模块按需声明权限码即可接入。
- **授权完整性**：新增业务接口默认须声明动作级权限码；确属 Agent / 系统 / 公开通道的，
  须在文档中显式登记为例外，禁止「静默无授权」。
- **外部 Agent 不迁移**：CLI_CLIENT 契约（API Key / MCP）保持不变，不进 Sa-Token 会话体系。
- **渐进演进**：按 `doc/HelloAI 基础架构调整实施计划.md` 分批次实施（批次一~三已落地：
  动态路由 / 按钮权限 / 菜单管理 / 差异更新 / 部门 / 数据权限；批次四 BASE-4.x 已立项：
  认证收口 / 身份单事实源 / 角色分层 / 全量授权化 / 建号），完成后回填基线。
