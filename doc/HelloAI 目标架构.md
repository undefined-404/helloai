# HelloAI 目标架构

> **状态：TARGET ARCHITECTURE**
>
> 本文档定义未来稳定架构边界，不表示所有能力当前已经落地。
>
> 最后更新：2026-10-09（**① 新增 §13 备份与恢复、§14 知识库（RAG）两条目标边界**——用户裁定（`D-2026-10-09-4`）
> 将二者正式立项，此前在活文档零登记，差距锚点 `G-018` / `G-019`；**② §5 订正**：「Fork 已落地」与代码实测不符，
> 收窄为「Fork 仅快照复制、无入口、不驱动执行」，并进一步**将「入口 / 原 Run 冻结 / 驱动新 Run 执行」裁定为 WONTFIX**（`D-2026-10-09-5`）；
> **③ §7 Sandbox Provider 降级为「条件触发」（`D-2026-10-09-6③`）**——复核判据为「当前没有可隔离的执行对象」（外部 agent 在它自己终端；内部 agent 工具面全是平台 API；
> 平台无脚本引擎 / 表达式求值器），本项**不排期**，只登记三个触发条件与预案；**④ 新增 §15 外部 Agent 工作详情快照**（`D-2026-10-09-6⑤`，差距锚点 `G-020`）。
> 同日早先已完成 **§0 状态表与各层内联 `Status` 全量复核订正**：
> Quality Gate / Recovery·Fork / Cost·Latency 三处状态声明与代码实测不符，已更正；判定基线前移至 HEAD `d3c1b129`。
> 本文件只声明 **目标边界 + 状态**；进度百分比与逐条差距一律以《HelloAI 实现差距表》为**唯一事实源**）

# 0. 实现状态（Implementation Status）

> **状态四值**：`Implemented`（已达成）· `Partial`（部分达成）· `Planned`（未启动，已登记）· `Non-goal`（明确非目标）。
> **判定基线**：2026-10-09，HEAD `d3c1b129`（§0 各行逐项对代码实测；上一判定基线 2026-09-30 / HEAD `e2c7e8c`
> 见 `doc/review/HelloAI 架构V2进度与质量审计报告（2026-09-30）.md`、`HelloAI 代码规范与架构偏离专项审计报告（2026-09-30）.md`）。

| 章节 | 层 / 能力 | 状态 | 差距锚点（唯一事实源 = 差距表） | 判定依据 |
|---|---|---|---|---|
| §3 | **Role Layer**（Planner / Reviewer·Quality Gate / Governance） | **Partial** | G-007 · G-012 · G-013 · G-016 | Planner ✅；Review 链 ✅；Governance（RBAC）✅ 批次一~四；**Quality Gate 契约族已收口**（`QualityGate` 统一契约 + 2 真闸门 `RepeatedFailureGate` / `FinalReportFidelityGate`，RM12）；通用多闸门决策未全量泛化（差距表 G-007） |
| §3 | **Orchestration Layer**（Workflow / Scheduler / DAG / Dependency / Parallelism / Routing） | **Partial** | G-009 · G-010 · G-015 | Task·SubTask DAG / 依赖门禁 / 并行派发 / 路由 ✅；**Dynamic Workflow 语义未抽象**（P3 后置，且 §11 禁止新建第二套 Engine） |
| §3 | **Runtime Layer**（AgentRuntime / Context / Session / AgentLoop） | **Implemented** | G-002 · G-003 | 2026-09-30 单轨硬切，`RuntimeTurnExecutor` 为唯一 `AgentRuntime` 实现；八件套契约齐备；每轮 checkpoint 与 tokenUsage 已落库 |
| §3 | **Capability Layer**（Skill Package / Tool / MCP / Sandbox Provider） | **Partial** | G-004 · G-005 · G-008 | Skill 元数据层 ✅、Tool / MCP ✅；**Sandbox 仅契约、无真实隔离** |
| §3 | **Provider Layer**（Qoder / Trae / Codex …） | **Implemented** | G-014 · G-016 | 异构 Provider 契约清晰；外部 Agent 通道获 A 级端到端实证 |
| §5 | Event Stream（Run / Turn / Step + Replay · Audit） | **Partial** | G-001 · G-006 | 写侧 + Replay / Audit / UI 已上线；**Fork 仅快照服务**（`AgentEventForkService` 108 行，把 `run-{taskId}-1` 的 `agent_event` 复制到新 run_id，**无任何生产调用方**）——**其触发入口 / 原 Run 冻结 / 驱动新 Run 执行三项由用户裁定 WONTFIX**（`D-2026-10-09-5`，2026-10-09；理由：用法已被 Return + Replay 覆盖，且须改 ADR-001 Run 模型与 execution command 载荷）；**Recovery 未建**（留在 P1 剩余项按原次序推进） |
| §6 | Skill Capability Package | **Partial** | G-004 | 9 个元数据字段已落地（含 `inputSchema` / `outputSchema` / `validationRules`）；**Instructions 结构化未动**；Discover→Validate 生命周期部分达成 |
| §7 | Sandbox Provider | **Planned**（条件触发，不排期） | G-005 | 契约已落地；**五边界隔离未实现**（`EnvironmentSandboxProvider` 一律不标 ISOLATED）。**2026-10-09 复核：当前无可隔离的执行对象**——外部 agent 在它自己终端；内部 agent 工具面全是平台 API（`File`/`Path`/`ProcessBuilder` 0 命中）；平台无脚本引擎 / 表达式求值器 ⇒ 降级为条件触发（`D-2026-10-09-6③`），三个触发条件见 §7 |
| §8 | Agent Fleet | **Partial** | G-008 · G-014 | Capability Match / Health ✅，多外部执行者同台已实证；**Cost 维度已接入选人比较链**（`AgentSelector.resolveCostRanks`，近 5 次成功均值 min-max 反向归一，B5.3）；**Latency 维度未做** |
| §9 | 最终执行链 | **Implemented** | G-001 · G-002 · G-016 | 需求包 → Planner → Workflow → Scheduler → Runtime → Skill/Tool/Sandbox → 异构 Agent → Event Stream → Reviewer → PASS/REWORK/HUMAN_REVIEW/BLOCK 全链 A 级实证 |
| §10 | Harness | **Non-goal** | — | 明示为 Runtime 参考架构，非目标产品 |
| §11 | 非目标清单 | **Non-goal** | — | 显式列出并持续生效（含禁第二套 Scheduler / Workflow Runtime） |
| §12 | 基础架构（平台底座 RBAC） | **Implemented** | G-012 · G-013 | 批次一~四已实施（V77~V89）；BASE-4.5 建号 E2E 待重启复验 |
| §13 | 备份与恢复 | **Planned** | G-018 | 完全空白（全仓无 DB 备份/恢复能力）；可复用资产已就绪（MinIO 主存储 + `ArtifactStorage.listObjects` 枚举 + Redisson/ShedLock 单飞范式）。**用户裁定正式立项（`D-2026-10-09-4`，2026-10-09）** |
| §14 | 知识库（RAG） | **Planned** | G-019 | 完全空白（pgvector / embedding / 知识库零命中）。**用户裁定正式立项（`D-2026-10-09-4`，2026-10-09）**；前置：PG 镜像换 pgvector 版 + 嵌入模型选型落 ADR |
| §15 | 外部 Agent 工作详情快照 | **Planned**（可后置） | G-020 | 完全空白。**用户裁定新增立项（`D-2026-10-09-6⑤`，2026-10-09）**——平台只审计「订单层面」，子任务内部过程是刻意留的黑盒；本能力提供**事后、经审批、可选**的取证通道。动手前三项设计约束（闭合 schema 双面 / 审批方 / 时间线粒度）见 §15 与 `REF-7` |

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

> **Status: Partial** — Planner ✅ / Reviewer ✅（Review 链）/ Governance ✅（RBAC 底座）；**Quality Gate 契约族已收口**（`QualityGate` 统一契约 + 2 真闸门，RM12）；通用多闸门决策未全量泛化（差距表 G-007）。

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

> **Status: Partial** — Run/Turn/Step + Replay / Audit / UI 已上线；**Fork 仅快照服务**（`AgentEventForkService`，无消费方；**触发入口 / 原 Run 冻结 / 驱动新 Run 执行 = WONTFIX**，见 `D-2026-10-09-5`）；**Recovery 未建**（差距表 G-001 / G-006）。

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

> **Status: Planned（条件触发，不排期）** — 契约已落地；**五边界真实隔离未实现**（`EnvironmentSandboxProvider` 一律不标 ISOLATED，差距表 G-005）。
>
> **2026-10-09 降级（`D-2026-10-09-6③`）**：本节**不排期**。判据：**当前没有可隔离的执行对象**——外部 agent 跑在它自己的终端（本平台定位 = **派单方 ≠ 执行方**）；内部 `API_KEY_LLM` agent 的工具面全是平台 API（`McpMcpServer` 内 `File` / `Path` / `ProcessBuilder` 0 命中）；平台全库无脚本引擎 / 表达式求值器。
>
> **触发条件（任一成立 ⇒ 重新进入排期，届时启动专项出 ADR）**：
>
> ```text
> ① 平台增加「碰宿主」的工具（自持 shell / 文件写）
> ② 技能包要被执行（技能带脚本、平台跑脚本）
> ③ 平台自持浏览器（WEB_BROWSER 真实接入链路）
> ```
>
> **边界澄清**：「五边界」中对 helloai 当前唯一有真实意义的是**网络边界**，而它的正确实现是**出站 SSRF 守卫**（平台自己发起的外联，见 `plan/HelloAI 借鉴落地实施计划.md` `REF-5.4`），**不是容器网络隔离**。**预案**（spec 声明化 / 探针 / scope 生命周期 / 生产形态 = 独立沙箱服务）见同一计划 §5。

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

> **Status: Partial** — Capability Match / Health 已落地且多外部执行者同台已实证；**Cost 已纳入选人比较链**（`AgentSelector.resolveCostRanks`，B5.3），**Latency 未纳入**（差距表 G-008 / G-014）。

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
> 实施编排见 `doc/plan/HelloAI 基础架构调整实施计划.md`（任务编号 BASE-xxx，独立于业务主线编号）。

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
- **渐进演进**：按 `doc/plan/HelloAI 基础架构调整实施计划.md` 分批次实施（批次一~三已落地：
  动态路由 / 按钮权限 / 菜单管理 / 差异更新 / 部门 / 数据权限；批次四 BASE-4.x 已立项：
  认证收口 / 身份单事实源 / 角色分层 / 全量授权化 / 建号），完成后回填基线。

# 13. 备份与恢复

> **Status: Planned** — 当前**完全空白**（差距表 `G-018`）。**用户裁定正式立项（`D-2026-10-09-4`，2026-10-09）**；
> 此前该能力在《目标架构》《差距表》中零登记，本节为新增目标边界。
> 任务锚点见 `plan/HelloAI 借鉴落地实施计划.md`（`REF-2.3` / `REF-2.4`）。

目标形态：

```text
备份：pg_dump -Fc 全库  +  MinIO 对象清单  +  manifest 前置  +  分布式单飞锁
恢复：manifest peek 校验  →  恢复侧安全闸门  →  pg_restore + 对象回填  →  停机恢复流程
```

边界与不含项：

- **不含**应用层「报告回滚」语义——那是 `task.final_report` / `final_report_prev` 两槽互换（`G-016`），与本节无关，二者不得混用「回滚/恢复」措辞。
- **不含**跨引擎与在线热恢复：恢复只支持**同引擎 + 停机**路径；跨引擎、schema 版本高过运行时、在线恢复一律**拒绝**并给出可读原因。
- **诚实边界**：运行中备份**不保证**所有文件处于同一瞬间；该限制必须随流程文档一并交付，不得宣称「一致性快照」。

不变量：

```text
对象清单枚举为空或失败 ⇒ 备份显式失败（不得产出「完整备份」）
手动备份永不被自动保留策略淘汰（仅自动备份参与 prune）
```

# 14. 知识库（RAG）

> **Status: Planned** — 当前**完全空白**（差距表 `G-019`）。**用户裁定正式立项（`D-2026-10-09-4`，2026-10-09）**。
> 任务锚点见 `plan/HelloAI 借鉴落地实施计划.md`（`REF-4.x`）。

目标形态：

```text
文档摄入 → 切片 → 嵌入 → pgvector 索引 → 检索（预算内） → 引用 marker → 报告/核验可溯源
```

边界与不含项：

- **存储只走 PG**（pgvector 扩展）——不引入第二套数据库或侧库；这是既有 PG 单后端优势的延续。
- **能力不可用即摘除**：「无 KB ⇒ `search_knowledge` 从工具列表摘掉」，而不是在描述里写「不要调用」（与 §6 Skill / Tool 的条件可用语义位同源）。
- **不进上下文的东西先定死**：注入预算（`char_budget` 契约）先于检索实现，预算截断必须可断言。
- **接线口径**：`search_knowledge` 属**程序化能力**，必须经 `ToolCallbackContributor` 端口注册并登记技能包（`CODE_STYLE §35.1`），不得给 LLM 直插域内 Service。
- **前置未完成不进入实现**：① PG 镜像换 pgvector 版；② 嵌入模型供应商 / 维度 / 密钥管理落 `design/adr/`。

# 15. 外部 Agent 工作详情快照

> **Status: Planned**（可后置，差距锚点 `G-020`）——当前**完全空白**。
> **用户裁定新增立项（`D-2026-10-09-6⑤`，2026-10-09）**，任务锚点见 `plan/HelloAI 借鉴落地实施计划.md` §8（`REF-7`）。

目标形态：

```text
外部 Agent（事后、经审批、可选）
    └─► 提交全任务工作详情快照
            └─► 原文落 MinIO
                    └─► 平台解析 → 时间线插入结构化审计事件（闭合 schema + snapshotRef）
```

**定位边界（与平台定位一致）**：

- 平台**不改变**「不干涉 agent 怎么做」的原则（**派单方 ≠ 执行方**）——不要求外部 agent 实时上报内部步骤，快照是**事后、经审批、可选**的补充。
- **与 §5 Event Stream 的边界**：Event Stream 记录**平台与 agent 之间的交互事实**（订单层面：认领 / 开工 / 心跳 / 交单 / 产物）；本能力补的是**子任务内部过程**的取证。**不得**把快照内容直接倾入事件流——必须是「原文落对象存储 + 时间线只放结构化索引 + `ref`」的**双面**形态，否则与「审计事件闭合 schema、不允许自由文本字段」的判据（`REF-6.2`）冲突。
- **接线口径**：新增提交工具必须走既有 `McpMcpServer` 守卫（`assertAgentActive` / `assertToolEnabled` / `refreshDutyLease`），**不建平行通道**；且**默认关闭**（`REF-6.13`：新能力默认关闭、通过注入启用），不污染既有外部 agent 工具面。

**动手前三项约束（未定不进实现）**：① 闭合 schema 与快照自由文本的双面设计；② 审批方（倾向复用人工介入 `HUMAN_REVIEW` 通道，不建第二套审批）；③ 落时间线的粒度（倾向先做「一条汇总事件 + `ref`」）。
