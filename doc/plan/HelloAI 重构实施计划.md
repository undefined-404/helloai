# HelloAI 重构实施计划

> **状态：Active**（**2026-10-17 复核：§2 / §3 / §4 / §7 已退场；§5 / §8 / §9 为「主体已落地 + 尾项缺口」，无在办主线动作**——见下表）
>
> **★章节状态速查（2026-10-17 全量复核）**：下表是**唯一**「哪节还要不要读」的判据；已完成节的正文一律加删除线并给指针，**不再作为行动依据**。
>
> | 节 | 主题 | 状态 | 依据 |
> |---|---|---|---|
> | §1 | 重构目标 | 🟢 长期有效（设计意图，非进度） | — |
> | §2 | P0-A 统一 Agent Event Stream | ✅ **已完成**（2026-09-07 结项） | 差距表 `G-001` |
> | §3 | P0-B Executor 双轨迁移 | ✅ **已终结**（2026-09-30 `G-002` 单轨硬切） | 差距表 `G-002` |
> | §4 | P0-C AgentRuntime | ✅ **已完成**（八件套全落地） | 差距表 `G-002` / `G-003` |
> | §5 | P1 Skill Capability Package | 🟡 **主体已落地**（`REF-1.1`~`1.6` 已交付，`REF-1.4` 字段与打戳器已实现、**条件触发**：仅当平台出现「把技能包拷贝进 Agent 工作区」动作时接线） | `G-004` / `REF-1` |
> | §6 | P1 Sandbox Provider | ⏸ **条件触发、不排期**（正文为预案） | `G-005` / `REF-3` / `D-2026-10-09-6③` |
> | §7 | P1 Event Consumers | ✅ **已完成**（`Recovery` 2026-10-16 建成，序列收官） | 差距表 `G-006` |
> | §8 | P1 Planner 能力感知与粒度 | 🟡 **主体已落地**（2026-09-09 拍板并交付：技能目录注入拆解 Prompt / 子任务级 requiredSkills·constraints 指派 / 三档粒度 FINE·STANDARD·COARSE / 外部下行通道）；**缺口 2 项**：① 技能回流贡献规范（D5-3，与 §5 同一缺口）② 审查侧 constraints/requiredSkills 注入核验 | 差距表 `G-010` |
> | §9 | P1 需求包准入与不确定性 | 🟡 **主体已落地**（结构化需求包 / uncertainties 显式 JSONB 列 / 执行与审查侧注入 / missingEvidence 返工线索）；**缺口 1 项**：`gap_kind` 实现路径分类与任务后蒸馏闭环（后置） | 差距表 `G-011` |
> | §10 | P2 Quality Gate / Agent Fleet | ⬜ **未启动**（仅目标描述，无动作） | — |
> | §11 | P3 Dynamic Workflow | ⬜ **未启动**（仅目标描述，无动作） | — |
> | §12 | 当前禁止扩张 | 🟢 长期有效（约束清单） | — |
> | §13 | 最终验收问题 | 🟢 长期有效（验收标准） | — |
> | §14 | 运行时缺陷清偿记录 | 🟢 记录性段落（治理规则 §3.2 定则保留） | — |
>
> **读法**：✅ 四节（§2 / §3 / §4 / §7）已退场，正文划线保留追溯；🟡 三节（§5 / §8 / §9）**主体已落地，只列尾项缺口**；⏸ / ⬜ 两节条件触发 / 未启动；🟢 四节长期口径。
>
> **（历史主线陈述 · 截至 2026-09-13 时点，**真实状态以上方速查表为准**）** 当前主线：**Event Stream → Dual Executor → AgentRuntime → Skill Capability → Sandbox Provider**；P0 全链收官，P1 主体推进——G-004 / G-006 / G-010 / G-011 落地并完成 2026-09-10 外部执行者双轮全链闭环（Round2/Round3）。后续：P1 剩余项（技能回流规范〔`REF-1.4` 待接线，**条件触发**〕 / Sandbox 第二阶段〔**条件触发**，Fork 已 WONTFIX〕）+ 技术债清偿。**`Recovery` 消费面已于 2026-10-16 建成，自本清单移除**（见 §7；`D-2026-10-16-11` / `LOG-20261016-011`）。近况增量：2026-09-11 外部上下文供给打通（子任务详情 MCP 工具 V76 + REST/inbox 三通道，提交 8fa09e0）；2026-09-13 Sa-Token 异步上下文修复（SSE 流式接口 500：拦截器 ASYNC 放行 + 异常处理器 SSE 分支，提交 dd3bd18，见 §14）。
>
> **2026-10-09 次序与范围变更（用户裁定，`D-2026-10-09-4` / `D-2026-10-09-5` / `D-2026-10-09-6`）**：借鉴落地专项（`plan/HelloAI 借鉴落地实施计划.md`，编号 `REF-x.y`）并入本主线执行：
>
> ```text
> Skill Capability（技能可装配）──► 备份/恢复 ──► RAG 知识库（可后置，G-019）
> 条件触发（不排期）：Sandbox Provider（G-005，见下）
> Event 消费面：Timeline → Replay → Audit → Recovery（按 §7 原次序推进，次序未变）
> ```
>
> - **备份/恢复（`G-018`）提前到 Sandbox Provider 之前**；**RAG 知识库（`G-019`）为新增目标边界，排在最后**（见《目标架构》§13 / §14）。
> - **Fork 不再作为开发要求**（`D-2026-10-09-5`：触发入口 / 原 Run 冻结 / 驱动新 Run 执行三项 **WONTFIX**；`AgentEventForkService` 快照服务保留但无消费方）⇒ Event 消费面**回到 §7 原本以 `Recovery` 收尾的次序**。
> - **`Sandbox Provider`（§6）由 P1 主干改为「条件触发、不排期」**（`D-2026-10-09-6③`）：复核判据是**当前没有可隔离的执行对象**——外部 agent 跑在它自己终端（本平台定位 = 派单方 ≠ 执行方）；内部 `API_KEY_LLM` agent 的工具面全是平台 API（`McpMcpServer` 内 `File` / `Path` / `ProcessBuilder` 0 命中）；平台全库无脚本引擎 / 表达式求值器。**三个触发条件（任一成立 ⇒ 本项回到主干排期）**：① 平台增加碰宿主的工具；② 技能包要被执行；③ 平台自持浏览器。**连带修正**：原「技能的脚本要在沙箱里跑」硬约束在现形态下自动满足（平台本就不执行脚本）。§6 正文保留为**预案**。
> - **新增 `G-020` 外部 Agent 工作详情快照**（`D-2026-10-09-6⑤`，`REF-7`，可后置）——不改变本主线次序，登记见《目标架构》§15。
> - 本次调整不新增/删除其余 P1 能力项，不改变 P0 已收官口径。
>
> **2026-10-12 主线补充（用户裁定，`D-2026-10-12-1` / `D-2026-10-12-2`）**：**`REF-8`「内部 LLM 能力面」列为当前 T1 最高优先**（差距锚点 `G-021`）——这是「让内部 LLM 从单轮文本生成器进化为有工具、有手脚、能按需装配技能的 agent」的唯一直接产出层。次序 **`REF-8.1` 工具面解封 → `REF-8.2` 手脚+上下文 → `REF-8.3` 技能惰性加载 → `REF-8.5` 覆盖面收敛**（`REF-8.4` 拦截链可并行铺，~~是 `LOG-20261011-007` A+B 的唯一挂点~~ —— **⚠️ 2026-10-15 订正**：`REF-8.4` 已于 2026-10-13 交付（形态 = 循环自有 `LoopHook` 三档，**非** Advisors）；**B 收敛熔断挂拦截链、A 能力闸出列落 `ResilientDispatcher`**，见 `D-2026-10-15-6`）。细则见 `plan/HelloAI 借鉴落地实施计划.md` §10（动作明细 / 收敛完成判据 / 红线）；**本主线不内嵌其进度**（进度以《实现差距表》`G-021` 为准）。
>
> 最后更新：2026-10-17（**§5 / §8 / §9 状态订正：由「在办」改「主体已落地 + 尾项缺口」** —— 用户复核指正，实测差距表 G-004 / G-010 / G-011 与 REF-1 确认：三项主体早已交付，速查表此前「🔵 在办」措辞失真；同日另完成：新增「章节状态速查」+ §2 / §3 / §4 / §7 四节划线退场；2026-10-15 加 `REF-8.4` 交付 / A+B 挂点订正指针；2026-10-12 主线补充。**本主线次序未变**）

# 1. 重构目标

本轮不是继续堆角色或功能，而是进行一次架构收敛：

```text
统一事实
→ 安全迁移
→ Runtime 收敛
→ Capability 演进
→ Sandbox 解耦
→ Governance
```

# 2. P0-A：统一 Agent Event Stream

> **✅ 本节已完成并退场（2026-09-07 结项）** —— 当前状态见《差距表》`G-001`（Event Stream）/ `G-006`（消费面）。
> **仍有效**：`## 目标` 的三层模型与字段表 —— 它就是 `agent_event` 的**现行**结构，不是历史。
> **已划线退场**：`## 实施顺序` 与 `## 落地点` 的全部实施描述（A1~A7 已落地，仅作追溯）。

## 目标

将现有执行记录逐步统一为：

```text
AgentRun
  ↓
AgentEvent Stream
```

第一版核心字段：

```text
eventId
runId
taskId
subTaskId
eventType
sequence
timestamp
actor
correlationId
causationId
payload
```

## ~~实施顺序~~（✅ A1~A7 均已落地，2026-09-07 结项）

> ⛔ 下列原文为**当时的推进次序**，已全部完成、**不再作为行动依据**（代码块保留原样以便追溯）。

```text
A1 Event Contract
A2 EventType
A3 sequence / correlation / causation
A4 Legacy Adapter 埋点
A5 Runtime 埋点
A6 Timeline 消费迁移
A7 Replay / Audit 最小读取
```

## ~~落地点（P0-A 已结项，2026-09-07）~~

> **进度指针（不内嵌快照，`C1`）**：Event Stream 验收与消费面进度**以《差距表》`G-001` / `G-006` 为准**。以下四段为 P0-A 的**结项证据留痕**（A1~A7 均已落地），已划线，**不是**「当前基线」。

~~A1~A5 已落地：`agent_event` 三层模型（Run / Turn / Step，append-only）+ EventType（含 SKILL_RESOLVED=5 / TOOL_RESOLVED=6 / ENVIRONMENT_RESOLVED=7 step 槽位）+ AgentEventRecorder（write-only）。验证：`verify-c3-events.ps1`。~~

~~A6 路线 B（2026-09-07 已落地）：新增 `AgentEventQueryService#traceBySubTaskId` 读侧投影（`agent_event` 按 subTaskId 以 `createTime ASC, id ASC` 有序重建轨迹），仅后端读侧，未接 UI；单测 3 用例 + dev 库连库验证 PASS。~~

~~A6 收口（2026-09-07 已落地）：`/timeline` 读侧并轨 `agent_event`——`TaskTimelineService.listBySubTaskId` 合并 task_timeline 粗事件 + agent_event 细轨迹（createTime ASC + id ASC 二级排序）；前端 SubTaskDetail 时间线/时序图补 agent_event 事件字典与泳道映射（COMPACT_HIDDEN 隐藏例行 Step 事件防刷屏）；后端单测 7 用例 + 前端 vue-tsc type-check PASS。`task_timeline` 保持不迁移（ADR-001 §4）。~~

~~A7（2026-09-07 已落地）：Replay / Audit 最小读取——`AgentEventQueryService` 新增 `traceByRunId`（按 runId 以 `createTime ASC, id ASC` 重建 Run 级轨迹，Replay 读侧，G-001 验收「一个 Run 可以按 sequence 重建轨迹」成立）与 `pageAuditByTaskId`（按 taskId 分页查执行事实，eventType 可选过滤，按写入时序正序）；`AgentEventMapper` 对应新增 `selectByRunIdOrdered` / `selectPageAuditByTaskId`（`idx_agent_event_run` 索引支撑）；纯后端读侧，未接 API/UI（与 A6 路线 B 同形态）；单测 8 用例 + dev 库连库探针 PASS。~~

**当前结论（有效）**：~~P0-A 完整闭环（A1~A7 已落地）——G-001 Event Stream 验收全量成立；消费面已在 P1 补齐至全链暴露（G-006 增量 C1/C2/D，2026-09-08~10，见 §7）。~~ **本组四项消费面（Timeline / Replay / Audit / Recovery）已全部落地** —— 末项 **`Recovery` 消费面于 2026-10-16 建成**（只读恢复点投影 + 续跑兜底注入 + 只读端点；方向裁定与边界见 `D-2026-10-16-11`，记录 `LOG-20261016-011`）。Fork 已于 2026-10-09 裁定 WONTFIX（见 `D-2026-10-09-5`）⇒ **Event 消费面无剩余在办项**。

# 3. P0-B：Executor 双轨迁移

> **✅ 本节已终结并退场（2026-09-30 `G-002` 单轨硬切）** —— 当前只有单轨 `RuntimeTurnExecutor`，**不存在双轨、不存在灰度**。当前状态见《差距表》`G-002`。
> **仍有效**：下列「原则」六条（共享同一 Result Handler / 全部进 Event Stream / 不建第二状态机 / 不靠简单 fallback 重复副作用）—— 它们约束**现行单轨**，不是双轨期专属。
> **已划退场**：双轨图与迁移节奏（100% Legacy → 5/95 → Runtime 主路径）。

~~双轨结构（代码块保留原样以便追溯）：~~

```text
ExecutionRouter
      │
 ┌────┴────┐
 ↓         ↓
Legacy    Runtime
```

原则：

- 双轨只用于迁移；
- 所有执行共享同一 Result Handler；
- 所有执行进入 Event Stream；
- 不建立第二状态机；
- 不通过简单 fallback 造成副作用重复执行；
- Runtime 异常的补偿必须基于 Execution Idempotency / Compensation。

~~迁移节奏：~~

```text
100% Legacy
→ 95/5
→ 50/50
→ 5/95
→ Runtime 主路径
```

## 迁移期记录（已终结）

> **本段已终结**（2026-09-30 G-002 单轨硬切）：旧链入口与双轨开关（`runtime-enabled` / `v2-enabled` / `gray-percent`）**全部删除**——不存在双轨，也不存在灰度阶段；本段原载的迁移期口径（真身装配 → 主链接线注入 → 灰度联调 → 全量档）与「当前动作」均**已失效，不再作为行动依据**。
>
> 当前执行链基线见 `doc/HelloAI 项目基线文档.md` §5 / §7；`G-002` 状态见《HelloAI 实现差距表》。
>
> **历史现场**：该迁移期的执行口径、联调结果、parity 对账、回滚演练、外部 CLI_CLIENT Agent 回归与两条实跑修正，见 `LOG-20260908-006`（原文已于 2026-10-09 按去过程化清理移出本文件）。

# 4. P0-C：AgentRuntime

> **✅ 本节已完成并退场** —— 八件套 **Context / EventRecorder / Environment / ToolRegistry / ToolExecutor / AgentLoop / Session / SandboxProvider** 已全部落地（第一~第四阶段）。当前状态见《差距表》`G-002` / `G-003`。
> **仍有效**：「Runtime 禁止直接依赖」四条（Planner / Global Scheduler / Reviewer Decision / Task Service）—— 这是**现行架构约束**，由 `check-arch-freeze.sh` 组 1 机械拦截。
> **已划退场**：下列四阶段的搭建次序（代码块保留原样以便追溯）。

~~第一阶段：~~

```text
AgentRuntime
├── AgentContext
├── EventRecorder
└── execute()
```

~~第二阶段：~~

```text
ToolRegistry
ToolExecutor
```

~~第三阶段：~~

```text
AgentLoop
```

~~第四阶段：~~

```text
Session
Sandbox
```

Runtime 禁止直接依赖：

```text
Planner
Global Scheduler
Reviewer Decision
Task Service
```

## 现状与进度（指针，不内嵌快照）

> **本节原为 2026-09-10 的进度快照，已按 `plan/HelloAI 计划编排与排期总览.md` §3 `C1` 改为指针。**
> 快照与 `REF-1.x` / `REF-4.x` 的实际推进不一致（曾造成「技能 / RAG 尚未开工」的误判），且违反借鉴落地 §1「双方不得互相复述进度」。
> **当前状态以《差距表》为准**（`AgentRuntime` → `G-002` / `G-003`）；**技能与工具面进度见 `plan/HelloAI 借鉴落地实施计划.md` `REF-1` / `REF-4` 各节 `Status`**。
>
> 结构性结论（长期有效，**非**进度）：八件套 **Context / EventRecorder / Environment / ToolRegistry / ToolExecutor / AgentLoop / Session / SandboxProvider** 均已落地（第一阶段~第四阶段）；`SandboxProvider` 为契约 + 诚实策略（见 §6）；**旧链编排已随 `G-002` 单轨硬切整体退出，`SubTaskExecutionService` / `SubTaskExecutionServiceImpl` 两个类均已删除（全仓 0 文件），当前唯一执行入口为 `RuntimeTurnExecutor`**。Docker / K8s 隔离能力后置（见 §6）。

# 5. P1：Skill Capability Package

从：

```text
requiredSkills → Markdown instructions
```

演进为：

```text
Skill Package
├── Metadata
├── Version
├── Instructions
├── Required Tools
├── Dependencies
└── Schema
```

保持现有 Markdown 兼容，不建立第二套 Skill Runtime。

> **进度指针（不内嵌快照，`C1`）**：技能侧落地进度**以 `plan/HelloAI 借鉴落地实施计划.md` `REF-1` 各节 `Status` 为唯一来源**（`REF-1.1`~`1.3b` / `1.5` / `1.6` 已交付；`REF-1.4` 能力已落未接线），能力状态见《差距表》`G-004`。
> 本节的**长期有效口径**：保持 Markdown 兼容、**不建立第二套 Skill Runtime**；`requiredTools → tools` 双链并集联动；任务级四段（创建 → 拆解 → 派发 → 执行）贯通。~~原 2026-09-10 快照（「3 个 `eng-*`」）已作废~~——实测 classpath 已为 **4 个** `eng-*.md`。

# 6. P1：Sandbox Provider（**2026-10-09 起：条件触发，不排期**）

> **状态变更（`D-2026-10-09-6③`）**：本节由「P1 主干」改为**「条件触发、不排期」**，正文保留为**预案**。判据 = **当前没有可隔离的执行对象**（外部 agent 在它自己终端；内部 `API_KEY_LLM` agent 工具面全是平台 API，`McpMcpServer` 内 `File` / `Path` / `ProcessBuilder` 0 命中；平台全库无脚本引擎 / 表达式求值器）。
> **触发条件（任一成立 ⇒ 本项回到主干排期，届时启动专项出 ADR）**：① 平台增加「碰宿主」的工具（自持 shell / 文件写）；② 技能包要被执行；③ 平台自持浏览器（`WEB_BROWSER` 真实接入链路）。
> **形态已裁定** = **独立沙箱服务**（低权限面，不走「挂 `docker.sock` 进 app 容器」）；**spec 必须是纯声明**，不得内嵌 Docker API 参数。

第一阶段只建立：

```text
SandboxProvider
SandboxContext
ExecutionPolicy
```

第二阶段才接入：

```text
Docker
Remote
K8s
```

> **进度指针（不内嵌快照，`C1`）**：本项**整组降级为「条件触发」**（见本节头部状态变更），**不排期**；触发条件与形态裁定以节首为准。契约面长期有效：已有 `ExecutionEnvironment` / `ExecutionEnvironmentProvider`（场所标签，非安全沙箱）；`SandboxProvider` 契约（`SandboxProvider` / `SandboxContext` / `Sandbox` / `ExecutionPolicy` 五边界，复用环境解析 + 诚实策略**无** `ISOLATED`）已落地。实测状态见《差距表》`G-005`；借鉴侧预案见 `plan/HelloAI 借鉴落地实施计划.md` `REF-3`。

# 7. P1：Event Consumers

> **✅ 本节已完成并退场（2026-10-16 序列收官）** —— `Timeline → Replay → Audit → Recovery` 四项消费面**全部落地**，Fork 已 WONTFIX ⇒ **无剩余在办动作**。当前状态见《差距表》`G-006`。
> **已划退场**：下列「推进序列图」与「后续再做 Recovery」（代码块保留原样以便追溯）。
> **仍有效**：Fork 的 WONTFIX 裁定与「勿混」口径（见本节末两段）。

~~Event 消费面推进序列：~~

```text
Event Stream
 ↓
Timeline
 ↓
Replay
 ↓
Audit
```

~~后续再做：~~

```text
Recovery
```

> **Fork 移出本序列（2026-10-09，`D-2026-10-09-5`）**：Fork 的触发入口 / 原 Run 冻结 / 驱动新 Run 执行三项 **WONTFIX**；`AgentEventForkService`（快照复制）保留为未接线的内部能力。故 Event 消费面以 **Recovery 收尾**。

> **进度指针（不内嵌快照，`C1`）**：Event 消费面落地进度见《差距表》`G-006`。**长期有效口径**：`Timeline → Replay → Audit` 已全链暴露；**`Fork` 已于 2026-10-09 `D-2026-10-09-5` WONTFIX**，序列以 **`Recovery` 收尾**（`Recovery` 消费面**已于 2026-10-16 建成**，`D-2026-10-16-11` / `LOG-20261016-011` ⇒ 本项**无剩余在办动作**）；`agent_execution_record` 仍 0 行（细线状态）。
> **勿混**：MQ 级死信恢复（`DeadLetterRecoveryService` / `DlxAlertConsumer` / `V60 mq_dead_letter_archive`）为子任务重派兜底，属**命令 / 结果链路**，**非** Event Stream 消费面。

# 8. P1：Planner 能力感知与自适应粒度（G-010）

Planner 拆解不再"闭眼规划"，而是感知平台真实能力后再拆：

```text
技能目录（Skill Capability Package 元数据面）
        ↓
拆解 Prompt 注入（能力感知：目录 + 执行者画像 + 难度 + 粒度）
        ↓
子任务级技能指派 + 执行约束（V74 新列，与任务级并集装箱）
```

三档粒度（rule-based 决策矩阵：执行者画像 × 难度调制；LLM 自判粒度后置）：

- **FINE**：有序步骤 + 每步指定 Skill + 输入/输出契约 + DoD；
- **STANDARD**：默认档（白名单为空 / 常规场景）；
- **COARSE**：目标 + 约束 + DoD——"不许改的事"（constraints）必填。

> **进度指针（不内嵌快照，`C1`）**：G-010 落地进度**以《差距表》`G-010` 为准**；技能侧（装配 / 安装 / 闸门）见 `plan/HelloAI 借鉴落地实施计划.md` `REF-1` 各节 `Status`。以下为 S1~S4 的**结项证据留痕**（2026-09-10 快照），**不是**「当前基线」——其中「`verify-skill-packages.ps1` 已交付」一项已随 `REF-1.2`（元数据迁 md）**同批重写**。
> 长期有效口径：三档粒度（`FINE` / `STANDARD` / `COARSE`）为 **rule-based 决策矩阵**（执行者画像 × 难度调制），LLM 自判粒度后置；拆解侧「技能目录常驻注入 + 超 20 项截断」。设计：`doc/design/Planner_Capability_Awareness.md`。

**落地点（S1~S4，2026-09-10 结项证据）**——S1 数据层（V74 sub_task.required_skills JSONB + constraints TEXT）、S2 拆解侧（技能目录常驻注入 / 三档粒度 / 目录过滤 task_plan_skill_filtered 审计 / 超 20 项截断）、S3 传递链（mergeSkills 并集五装箱点同源 / inbox 技能要求行 / REST 下行 / 草案确认 UI 展示编辑 + updateDraftById fail-close 端点 / executor SKILL.md 增量）、S4 双场景实测 PASS：平台内链（2026-09-09，与 G-011 S5 合并）+ 外部执行链（2026-09-10 双轮全链闭环——Round2 eng-doc-standard 硬门槛准入 / Round3 技能分布派单与 135:20 分排序实证）。后置缺口：①技能回流贡献规范（D5-3）；②verify-skill-packages.ps1（D5-2）已交付（2026-09-10，18 PASS 双验证）；③④已由 G-011 清偿（见差距表 G-010）。设计：`doc/design/Planner_Capability_Awareness.md`。

# 9. P1：需求包准入与不确定性显式管理（G-011）

G-010 解决「Planner 不知道平台有什么能力」，G-011 解决「Planner 不知道需求边界在哪、哪些前提是猜的」：

```text
Raw Requirement
      ↓
Requirement Package（goal / scope / outOfScope / assumptions / openQuestions）
      ↓
能力感知拆解（拆解推断分级登记 → sub_task.uncertainties）
      ↓
执行侧注入（缺失边界不再静默补全：ASSUMPTION 自验证 / UNCONFIRMED 上报裁决）
      ↓
审查侧分级语义（假设不成立 ≠ 执行缺陷；并清偿 G-010 constraints 审查核验缺口）
```

定界原则：**不建自动闸门**——openQuestions 不阻断拆解/派发，裁决点在草案确认（人工逐条处理）与执行侧（fail-close 走既有 BLOCKED 链）；不建平行架构（需求包解析=静态工具类，uncertainties 消费并入既有审查轨道 A）；gap_kind 实现路径分类与任务后蒸馏闭环后置批次二/三。

> **进度指针（不内嵌快照，`C1`）**：G-011 落地进度**以《差距表》`G-011` 为准**。以下为 S1~S5 的**结项证据留痕**（2026-09-10 快照），**不是**「当前基线」。
> 长期有效口径（定界原则）：**不建自动闸门**——`openQuestions` 不阻断拆解 / 派发，裁决点在草案确认与执行侧（fail-close 走既有 `BLOCKED` 链）；不建平行架构（需求包解析 = 静态工具类，`uncertainties` 消费并入既有审查轨道 A）。设计：`doc/design/Requirement_Package_Uncertainty.md`。

**落地点（S1~S5，2026-09-10 结项证据）**——S1 数据层（V75 sub_task.uncertainties JSONB + requirement_conversation.final_package JSONB + RequirementPackageParser）、S2 澄清侧（五字段结构化终稿 + final_package 定点写）、S3 拆解侧（uncertainties 继承落库 + 降级审计 + COARSE WARN + D5 兜底）、S4 传递链（执行注入 D6 三段 / 审查核验 D7 双占位符 + 轨道 A 第 8/9 条 / REST + inbox + 草案 UI）、S5 实测 PASS：平台内链（2026-09-09 与 G-010 S4 合并）+ 外部执行链（2026-09-10 双轮全链闭环——审查者引用 uncertainties 申报作驳回依据实证）。技术债：Agent 注册幂等顺序缺陷仍在（validateModelType 先于 registerOrGet）；配套 JSONB uncertainties CCE 死锁已修复（2026-09-10）。设计：`doc/design/Requirement_Package_Uncertainty.md`。

# 10. P2：Quality Gate / Agent Fleet

Quality Gate：

```text
Rule
Test
LLM Review
  ↓
Decision
```

Agent Fleet：

```text
Requirement
 ↓
Capability Match
 ↓
Health / Load
 ↓
Policy
 ↓
Provider Selection
```

# 11. P3：Dynamic Workflow

只有 Runtime / Event / Capability 稳定之后才开展：

```text
Planner
 ↓
Workflow DSL
 ↓
Workflow Engine
 ↓
Dynamic Branching
```

# 12. 当前禁止扩张

```text
❌ Agent Swarm
❌ 复杂 Memory
❌ 第二套 Scheduler
❌ 第二套 Workflow Runtime
❌ 全面改造 Kubernetes
❌ 为了 Harness 一一复制全部插件实现
```

# 13. 最终验收问题

本轮完成后必须能够清楚回答：

1. 一次 Agent Run 发生了什么？
2. Legacy Executor 如何迁移到 Runtime？
3. Runtime 与 Scheduler 的边界是什么？
4. Skill 如何成为 Capability Package？
5. Agent 如何与执行环境解耦？
6. 新增一个厂商 Agent 需要实现什么？
7. 失败执行如何恢复且避免重复副作用？
8. Planner 如何不幻觉指派？（明确要平台技能目录过滤 + 子任务级技能/约束显式化 + 粒度自适应）
9. 需求边界与推断如何不靠猜？（明确要需求包五字段 + uncertainties 分级登记 + 人工裁决 + fail-close 上报）

# 14. 运行时缺陷清偿记录

> 定位：V2 改造主线下暴露的**运行时缺陷**（非规划能力项）的根因、修复方案与验证留档。
> 这些缺陷不改变 P0~P3 目标边界，但直接决定线上可用性，登记在此供后续排障与决策参考。

## 2026-09-13 Sa-Token 异步上下文修复（SSE 流式接口 500）

**现象**：`POST /api/requirement-conversations/streamSendById/{id}`（SseEmitter 流式）每次请求 500 刷屏，
`GlobalExceptionHandler` 连续报两个异常——`SaTokenContextException: SaTokenContext 上下文尚未初始化`
（拦截器链）+ `HttpMessageNotWritableException: No converter for [class R] with preset Content-Type
'text/event-stream'`（异常响应二次异常，吞掉真实错误）。

**根因（两层）**：

1. **Sa-Token 1.44 Context Filter 仅注册 `REQUEST`**（官方 v1.46 发布说明直证：新版本才把 Filter 注册为
   `REQUEST + ASYNC`）。`SseEmitter` 返回后容器触发 **ASYNC 二次分派**，Spring MVC 在异步线程重新执行
   拦截器链，而异步线程没有 Sa-Token 的 ThreadLocal 上下文 → 所有调 `StpUtil` 的拦截器
   （`AuthInterceptor` / `AdminOnlyInterceptor` / `SaInterceptor` 的 `@SaCheckPermission`）全部抛异常。
2. **SSE 接口异常响应无 converter**：`@PostMapping(produces=TEXT_EVENT_STREAM_VALUE)` 下异常处理器返回
   `R` 对象，内容协商选中 `text/event-stream` 却无对应 converter → 二次异常。

**修复方案（语义 = 官方 v1.46「Context Filter 覆盖 ASYNC」，提交 `dd3bd18`）**：

```text
REQUEST 阶段（主线程）                    ASYNC 阶段（异步线程）
  AuthInterceptor 鉴权 ✅                   拦截器链重跑 → 直接放行（不重复鉴权）
  AdminOnlyInterceptor ✅                 ├ AuthInterceptor / AdminOnlyInterceptor / SaInterceptor
  SaInterceptor 注解鉴权 ✅                    → DispatcherType.ASYNC 短路 return true
  Controller 返回 SseEmitter               └ RequestLogInterceptor → 不重复 put MDC / 覆盖 START_TIME
        ↓
  异常路径：GlobalExceptionHandler 判定 SSE（ASYNC 分派 / Accept / Content-Type 三通道）
        → 写 event:error\ndata:<msg>\n\n 帧（HTTP 200，对齐前端事件协议），不再返回 R 对象
```

- **拦截器 ASYNC 放行（3 处同模式）**：`AuthInterceptor` / `AdminOnlyInterceptor` 的 preHandle 开头
  `request.getDispatcherType() == DispatcherType.ASYNC` 直接 `return true`（同一请求 REQUEST 阶段已完成
  鉴权，异步分派不重复鉴权、不依赖异步线程上下文）；`WebMvcConfig` 的 `SaInterceptor` 改匿名子类覆盖
  preHandle（`@SaCheckPermission` 注解鉴权不再炸）。
- **RequestLogInterceptor ASYNC 放行**：不重复 put MDC / 覆盖 START_TIME——traceId 与计时沿用
  REQUEST 阶段值，afterCompletion 在 ASYNC 结束统一落一条日志。
- **GlobalExceptionHandler SSE 兼容**：新增 `sseRequest`（三通道：ASYNC 分派 / Accept 含
  `text/event-stream` / Content-Type 含 `text/event-stream`）与 `writeSseError`（写 `event:error` 帧，
  HTTP 保持 200；data 压平换行；响应已提交则放弃改写记 warn）。8 个 handler 全部加 SSE 分支——
  SSE 场景返回 null（Spring 对 null 返回体跳过写入），不再二次异常。
- **前端 chatStream.ts**：fetch 显式 `Accept: text/event-stream`（语义正确 + 服务端据 header 精准识别
  SSE 场景）。

**验证**：新增 `GlobalExceptionHandlerTest` 6 用例全绿（Accept 命中写 error 帧返回 null / ASYNC 分派命中
401 也写帧 / 兜底异常压平换行 / 普通 JSON 请求 401 保持 R+状态码零回归 / 兜底 R.fail 500）；core 全量
**1457 用例 0 失败**、api 全量 **66 用例 0 失败**（含既有 AdminOnlyInterceptorTest / RequestLogInterceptorTest
零回归）；`vue-tsc --noEmit` 0 错误。注：父 POM `skipTests=true` 默认跳过，跑测试须显式 `-DskipTests=false`。

**后续可选**：Sa-Token 升级 v1.46（Context Filter 原生覆盖 ASYNC）可替代拦截器放行方案；属可选优化，
不阻塞当前修复。
