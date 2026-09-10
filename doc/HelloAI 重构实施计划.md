# HelloAI 重构实施计划

> **状态：ACTIVE**
>
> 当前主线：**Event Stream → Dual Executor → AgentRuntime → Skill Capability → Sandbox Provider**；P0 全链收官，P1 主体推进——G-004 / G-006 / G-010 / G-011 落地并完成 2026-09-10 外部执行者双轮全链闭环（Round2/Round3）。后续：P1 剩余项（Recovery/Fork 消费面 / Skill 回流规范 / Sandbox 第二阶段）+ 技术债清偿。
>
> 最后更新：2026-09-10

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

## 实施顺序

```text
A1 Event Contract
A2 EventType
A3 sequence / correlation / causation
A4 Legacy Adapter 埋点
A5 Runtime 埋点
A6 Timeline 消费迁移
A7 Replay / Audit 最小读取
```

## 现状基线（2026-09-07 代码核查）

A1~A5 已落地：`agent_event` 三层模型（Run / Turn / Step，append-only）+ EventType（含 SKILL_RESOLVED=5 / TOOL_RESOLVED=6 / ENVIRONMENT_RESOLVED=7 step 槽位）+ AgentEventRecorder（write-only）。验证：`verify-c3-events.ps1`。

A6 路线 B（2026-09-07 已落地）：新增 `AgentEventQueryService#traceBySubTaskId` 读侧投影（`agent_event` 按 subTaskId 以 `createTime ASC, id ASC` 有序重建轨迹），仅后端读侧，未接 UI；单测 3 用例 + dev 库连库验证 PASS。

A6 收口（2026-09-07 已落地）：`/timeline` 读侧并轨 `agent_event`——`TaskTimelineService.listBySubTaskId` 合并 task_timeline 粗事件 + agent_event 细轨迹（createTime ASC + id ASC 二级排序）；前端 SubTaskDetail 时间线/时序图补 agent_event 事件字典与泳道映射（COMPACT_HIDDEN 隐藏例行 Step 事件防刷屏）；后端单测 7 用例 + 前端 vue-tsc type-check PASS。`task_timeline` 保持不迁移（ADR-001 §4）。

A7（2026-09-07 已落地）：Replay / Audit 最小读取——`AgentEventQueryService` 新增 `traceByRunId`（按 runId 以 `createTime ASC, id ASC` 重建 Run 级轨迹，Replay 读侧，G-001 验收「一个 Run 可以按 sequence 重建轨迹」成立）与 `pageAuditByTaskId`（按 taskId 分页查执行事实，eventType 可选过滤，最新在前）；`AgentEventMapper` 对应新增 `selectByRunIdOrdered` / `selectPageAuditByTaskId`（`idx_agent_event_run` 索引支撑）；纯后端读侧，未接 API/UI（与 A6 路线 B 同形态）；单测 8 用例 + dev 库连库探针 PASS。

**当前动作**：P0-A 完整闭环（A1~A7 已落地）——G-001 Event Stream 验收全量成立；消费面已在 P1 补齐至全链暴露（G-006 增量 C1/C2/D，2026-09-08~10，见 §7）。剩余：Recovery / Fork 消费面。

# 3. P0-B：Executor 双轨迁移

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

迁移节奏：

```text
100% Legacy
→ 95/5
→ 50/50
→ 5/95
→ Runtime 主路径
```

## 现状基线（2026-09-07 代码核查）

契约层已是单轨：`LocalExecutionCommandConsumer` 与 `MqExecutionCommandConsumer`（委托本地消费）统一经 `AgentRuntime#execute`——唯一执行契约，旧直连执行链已下线；`LegacyExecutorAdapter` 转发旧链（SubTaskExecutionService）。

即当前 100% 流量经 Runtime 契约、0% Runtime 真身。本阶段实际工作不是"建轨"，而是：① 让 Runtime 侧长出真身（能力提取见 P0-C）；② 真身可用后补 Legacy ↔ Runtime 灰度切换与回滚口径。

P0-B 落地（2026-09-07）：**Runtime 真身已装配**——`RuntimeTurnExecutor`（AgentContext 输入 + AgentLoop / ToolExecutor / ToolRegistry / AgentSkillSpecService / SandboxProvider 组装；Turn 事件骨架 AGENT_STARTED→SKILL/TOOL/ENVIRONMENT_RESOLVED→CONTEXT_BUILT→[Loop TOOL_CALL]→AGENT_COMPLETED + 沙箱策略观测；prompt/chatModel 由调用方注入，不复制旧链业务编排）+ `RuntimeAgentRuntimeRouter`（`@Primary` + `@Order(1)`，`agentRuntimes.get(0)` 恒命中；按 `helloai.execution.runtime-enabled` 二进制切换，默认 `false`=Legacy 零变化，回滚=置回 false）。`AgentContext` 扩展 `accessType / systemPrompt / userPrompt / chatModel`；消费者注入 accessType。单测 6 用例 + 回归 38 用例 PASS（44 用例 0 失败）。

P0-B-2 落地（2026-09-07）：**主链接线注入完成，P0 主线收官**——LLM 工厂暴露 ChatModel（`ProviderChatClientFactory`/`ProtocolFactory` 三工厂 + `LlmProviderChatClientFactoryRegistry.createChatModel`，与 ChatClient 共享缓存实例）；`AgentChatClientService.buildChatModel`（mock → MockChatModel；真实 → 工厂出口）；新增 `TurnLlmCaller`（executeOnce LLM 调用点封装 Legacy 单次 / Runtime AgentLoop 切换，同一 `runtime-enabled` 开关；Runtime 路径 ChatModel + ToolExecutor + 循环内 TOOL_CALL 事件，Legacy 路径维持手动 TOOL_CALL 标记防双记）；`Router` 增加驱动性感知（runtimeEnabled 且 ctx 携带 chatModel 才走真身，dispatch 上下文回落 Legacy）。单测 TurnLlmCaller 4 + Router 3 + 工厂套回归，累计 **75 用例 0 失败**。

**当前动作**：**P0 主线（P0-A / P0-B / P0-C）完整收官**——Event Stream 统一、Runtime 真身 + 主链接线注入、八件套落地全部完成。P1 主体已推进（G-004 真实任务实测 / G-006 C1·C2·D 全链暴露 / G-010·G-011 落地），2026-09-10 完成外部执行者双轮全链闭环（Round2/Round3）。后续主线程：P1 剩余项（Recovery/Fork 消费面 / Skill 回流规范 / Sandbox 第二阶段）+ 技术债清偿。

---

**灰度第 0 步（2026-09-08 立执行口径，对应 §11 第 1/2/7 问验收）**

迁移节奏（100% Legacy → 95/5 → …）当前停在「100% 经契约、0% 真身」：开关机制已就位但从未在真实环境打开过。本步目标是把 §3 灰度验证走起来，不是新功能。执行口径：

- **点亮路径（联调排障关键）**：打开 `runtime-enabled` 后的真实接线 = `TurnLlmCaller#runRuntimeLoop`（buildChatModel → AgentLoop 手动循环 + 循环内 TOOL_CALL 事件）。消费侧 `LocalExecutionCommandConsumer` 构造 AgentContext **不注入 chatModel / prompt**，Router 驱动性判断（runtimeEnabled && chatModel != null）恒 false → **恒回落 LegacyExecutorAdapter** → executeOnce → TurnLlmCaller 内切换；Router → RuntimeTurnExecutor 八件套骨架需消费侧注入模型后才点亮，为后续项。开关 = Router + TurnLlmCaller 同一 `runtime-enabled`（默认 false），dev 全量打开，回滚 = 置回 false。
- **事件序列断言（parity 对账基线）**：Legacy / Runtime 双路径均为 `AGENT_STARTED(1) → SKILL_RESOLVED(5) → TOOL_RESOLVED(6) → ENVIRONMENT_RESOLVED(7) → CONTEXT_BUILT(2) → [TOOL_CALL(3/4)]×n → AGENT_COMPLETED(0)`（step 编号 = 槽位语义，非时间序）；对账用执行记录 executorName（Runtime = `RuntimeAgentLoop`）+ timeline route=agent_runtime 双点区分路径。真实 provider 的 tool-calling 兼容验证窗口为联调预留修复项（75 用例单测全 mock/stub ChatModel，真实报文未在链路跑过）。
- **幂等口径（拍板）**：Loop 内工具调用无幂等键（ToolCallbackToolExecutor.execute(toolName, argumentsJson)，循环无每轮 checkpoint）→ **Loop 级失败即整体失败**，由现有重派链兜底（TurnLlmCaller 异常 best-effort 转 failure → ExecutionResultHandler.handleFailure；结果级 idempotencyKey 去重不变）。12 个平台工具逐个标注幂等性：只读类（pullTasks / getDepsSummary / getAgentStatus / Echo 等）天然安全；写操作类要么自带幂等键（submitResult(resultId)）、要么天然幂等（claim / checkIn / heartbeat 等 CAS 状态守卫）、要么测试期禁用。
- **外部 Agent 定性（CLI_CLIENT）**：外部链路（pullTasks / checkIn / checkOut / heartbeat / submitResult）**代码路径零变更**——submitResult 直达 McpToolService → ExecutionResultHandler.handleReport，不经 AgentRuntime / 本次消费者；本步只做回归验证（既有 verify 脚本 + 真实外部 agent 拉取一轮）。预期事件流 = 命令下发 timeline 事件 + 回写层 `AGENT_COMPLETED(0)`，无 Turn / Step（外部自执行，平台只统一命令与回写契约）。
- **执行顺序**：① 幂等口径已立（本段）→ ②（可选）Replay / Audit 两端点（traceByRunId / pageAuditByTaskId 接 Controller，半天）→ ③ dev 全量开关 LLM 灰度联调 → ④ 外部 Agent 回归 + 真实拉取 → ⑤ 回填差距表 G-002 / G-006 状态与迭代日志。

**灰度第 0 步联调结果（2026-09-08 已执行并闭环，对应 §11 第 1/2/7 问验收）**

- **执行器实证**：基线（false）`agent_completed` payload.executor=`ApiKeyAgentExecutor`；真身（true）=`RuntimeAgentLoop`（TurnLlmCaller#runRuntimeLoop 点亮路径实证，非代码路径推断）。
- **事件序列断言命中**：基线 `1→5→6→7→2→3→4→0` 单轮；真身 `1→5→6→7→2→[3/4]×3→0`——真实 DeepSeek tool-calling 3 轮成对（getAgentStatus → pullTasks → heartbeat），LLM 每轮学习工具结果继续尝试，工具失败（agentId=0 校验失败）不中断 Loop；参数 JSON 解析与 assistant 回传格式真实报文跑通，**联调预留修复项（provider tool-calling 兼容窗口）未触发**；若全部成功为只读/幂等类无副作用（幂等口径实证）。
- **parity 对比**：结果质量（review score 双路径均 4）/ 事件序列 / 回写唯一入口（ExecutionResultHandler + idempotencyKey 去重）三方一致；差异仅 Runtime 不统计 tokens（payload.tokens=null），Legacy ≈10.3K~10.8K，属已知语义差异。
- **回滚演练**：`runtime-enabled` 置回 false 重启后 executor 回到 `ApiKeyAgentExecutor`、单轮序列、review score=4，与基线零差异（严格回滚：配置恢复 + 重启 + 同任务重跑对比）。
- **对账**：verify-c3-events 三探针全绿（P1 无孤儿 15/15 成对 / P2 无 MISMATCH / P3 RUNNING 0 滞留）。
- **外部 Agent 回归（CLI_CLIENT，真实 TeleAgent）**：造数→10min 认领窗口内拉取→claimSubTask→本地执行（4 个 API 测试，命令/stdout/exit code 证据）→submitResult→DONE；`sub_task_execute_submit` source=EXTERNAL + executor=cli_client + **idempotencyKey=r-{subTaskId}-v1**（submitResult 自带键实证）；回写层 `AGENT_COMPLETED(0)` 无 Turn/Step（外部自执行口径命中）；review approved score=4 一次通过。
- **执行口径修正 2 条**（首轮实跑踩坑）：① 外部 CLI_CLIENT 任务无人认领会触发 `assigned-timeout` 重派，5 次超限进死信（run 1 即被超时重派→死信→人工重派 inner 吃掉）——外部回归须在认领窗口内完成；② `runtime.v2-enabled`（application.yml，C3 Step 6 遗留）与 `helloai.execution.runtime-enabled` 是**两个开关**——前者代码零读取（仅文档/脚本语义），后者才是真身开关，勿混淆。
- **状态落账**：G-002 / G-003「名义收官 → 实际闭环」，差距表已同步；G-006（Replay / Audit API 暴露）当轮未做（服务层就绪），已由增量 C1/C2/D 补齐（2026-09-08~10，见 §7）。

# 4. P0-C：AgentRuntime

第一阶段：

```text
AgentRuntime
├── AgentContext
├── EventRecorder
└── execute()
```

第二阶段：

```text
ToolRegistry
ToolExecutor
```

第三阶段：

```text
AgentLoop
```

第四阶段：

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

## 现状基线（2026-09-10）

八件套现状：Context / EventRecorder / Environment 已落地；ToolRegistry 为元数据面（12 平台工具，仅注入 prompt 描述）；ToolExecutor 已落地（P0-C Phase 2，执行回路真身）；AgentLoop 已落地（P0-C Phase 3，`runtime/loop` 手动工具循环——ChatModel 契约 + ToolExecutor 执行 + TOOL_CALL 事件，maxIterations 硬上限防死循环）；Session 为中断恢复检查点（AgentSessionService）；SandboxProvider 契约已落地（Phase 4，五边界 + 诚实策略，见 §6）。旧链编排仍在 `SubTaskExecutionServiceImpl`（约 790 行）。

**第一阶段~第四阶段已全部落地**——Docker / K8s 隔离能力属 P1/P2 后置（见 §6）。

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

现状（2026-09-10）：元数据层已落地并保持 resolve 行为兼容——SkillPackage（name / version / description / requiredTools / dependencies / inputSchema / outputSchema / validationRules）3 个 eng-* 已结构化；requiredTools→tools 双链并集联动（增量 A）+ 拆解技能通路（增量 B）已接；任务级四段（创建 → 拆解 → 派发 → 执行）贯通，真实任务带 required_skills 实测完成（2026-09-10：Round2 硬门槛准入 / Round3 技能分布派单）。剩余：技能回流贡献规范（G-010 后置）。详见差距表 G-004。

# 6. P1：Sandbox Provider

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

现状（2026-09-07 代码核查）：已有 ExecutionEnvironment / ExecutionEnvironmentProvider（remote-agent / local-process，场所标签，非安全沙箱）；SandboxProvider Contract 已落地（P0-C Phase 4：SandboxProvider / SandboxContext / Sandbox / ExecutionPolicy 五边界，复用环境解析 + 诚实策略无 ISOLATED）；第二阶段（Docker / Remote / K8s）后置。

# 7. P1：Event Consumers

```text
Event Stream
 ↓
Timeline
 ↓
Replay
 ↓
Audit
```

后续再做：

```text
Recovery
Fork
```

现状（2026-09-10）：Timeline 已并轨 Event（A6）；Replay / Audit 全链暴露——读侧（A7，见 P0-A）→ API + UI 工作台（增量 C1）→ 外部认领埋点 + run 级汇总卡（增量 C2）→ 任务/子任务维度端点（免传 runId，service 内部推导）+ 工作台选择器/名称解析/payload 结构化展开/事件流深链（增量 D）；外部执行轨迹加厚为四事件（AGENT_STARTED → AGENT_COMPLETED → REVIEW_STARTED → REVIEW_APPROVED），agent_execution_record 仍 0 行（细线状态，登记 G-006 剩余缺口）。Recovery / Fork 消费面待建（当前下一动作）。

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

现状基线（2026-09-10）：**S1~S4 已落地（含实测）**——S1 数据层（V74 sub_task.required_skills JSONB + constraints TEXT）、S2 拆解侧（技能目录常驻注入 / 三档粒度 / 目录过滤 task_plan_skill_filtered 审计 / 超 20 项截断）、S3 传递链（mergeSkills 并集五装箱点同源 / inbox 技能要求行 / REST 下行 / 草案确认 UI 展示编辑 + updateDraftById fail-close 端点 / executor SKILL.md 增量）、S4 双场景实测 PASS：平台内链（2026-09-09，与 G-011 S5 合并）+ 外部执行链（2026-09-10 双轮全链闭环——Round2 eng-doc-standard 硬门槛准入 / Round3 技能分布派单与 135:20 分排序实证）。后置缺口：①技能回流贡献规范（D5-3）②verify-skill-packages.ps1（D5-2）；③④已由 G-011 清偿（见差距表 G-010）。设计：`doc/design/Planner_Capability_Awareness.md`。

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

现状基线（2026-09-10）：**S1~S5 已落地（含实测）**——S1 数据层（V75 sub_task.uncertainties JSONB + requirement_conversation.final_package JSONB + RequirementPackageParser）、S2 澄清侧（五字段结构化终稿 + final_package 定点写）、S3 拆解侧（uncertainties 继承落库 + 降级审计 + COARSE WARN + D5 兜底）、S4 传递链（执行注入 D6 三段 / 审查核验 D7 双占位符 + 轨道 A 第 8/9 条 / REST + inbox + 草案 UI）、S5 实测 PASS：平台内链（2026-09-09 与 G-010 S4 合并）+ 外部执行链（2026-09-10 双轮全链闭环——审查者引用 uncertainties 申报作驳回依据实证）。技术债：Agent 注册幂等顺序缺陷仍在（validateModelType 先于 registerOrGet）；配套 JSONB uncertainties CCE 死锁已修复（2026-09-10）。设计：`doc/design/Requirement_Package_Uncertainty.md`。

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
