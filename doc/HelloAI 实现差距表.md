# HelloAI 实现差距表

> **状态：CURRENT GAP**
>
> 本文只记录当前 → 目标的真实差距。
>
> 最后更新：2026-09-09

# 1. 总体矩阵

| ID | 能力 | 当前状态 | 目标 | 优先级 | 处置 |
|---|---|---|---|---|---|
| G-001 | Agent Event Stream | 已有 Run/Turn/Step + Event 基础 + Timeline 并轨（A6）+ Replay/Audit 读侧（A7） | 统一事件契约和消费体系 | **P0** | P0-A 完整闭环（A1~A7 已落地，验收全量成立） |
| G-002 | Executor 迁移 | Runtime 契约单轨 + 真身 + 主链接线注入（runtime-enabled 开关，默认 Legacy 零变化） | Runtime 成为唯一执行契约，旧实现退出 | **P0** | P0 主线收官 + 真灰度闭环：2026-09-08 dev 真身联调（RuntimeAgentLoop 点亮）/ 对账全绿 / 回滚零差异 / 外部 Agent 回归通过 / 真实任务全链闭环（外部端到端 14 分钟 5 子任务零故障，见 log 2026-09-08） |
| G-003 | AgentRuntime | 八件套已全部落地（Context / Session / Skill / Tool / Loop / Event / Environment / SandboxProvider 契约） | Context + Session + Skill + Tool + Loop + Event + Sandbox | **P0** | P0 完整闭环（P0-A/B/C 收官）；真实 provider tool-calling 循环 2026-09-08 联调通过，无边界问题 |
| G-004 | Skill Capability | SkillPackage 元数据层已落地（name/version/description/requiredTools/dependencies/inputSchema/outputSchema/validationRules，3 个 eng-* 已结构化）+ **requiredTools→tools 联动已接**（Legacy/Runtime 双链 mergeTools 并集去重保序，TOOL_RESOLVED 前合并）+ **SKILL_RESOLVED 携带 resolvedVersions**（Replay/前端按字段投影兼容）+ **拆解技能通路已接**（task.required_skills → 拆解 Prompt 注入，规划/验收与技能规范对齐；增量 B） | Metadata / Version / Tools / Schema / Dependencies 全量 + **requiredTools→tools 联动** + SKILL_RESOLVED 携带版本 + 真实任务行使（任务级创建/拆解/派发/执行四段已贯通；带 required_skills 真实任务实测待 dev 环境验收） | **P1** | 元数据层（ed14e40 / 234bed4）+ 联动接线（增量 A）+ 拆解技能通路（增量 B，全量 1295 单测 0 失败）落地；真实任务带 required_skills 端到端实测 BLOCKED（本机无 dev 环境，口径与历轮一致） |
| G-005 | Sandbox Provider | 已有 Environment / Provider + SandboxProvider 契约（诚实策略，无 ISOLATED） | 真正 Provider 化执行环境与隔离策略 | **P1** | 契约已落地；Docker/K8s 隔离能力后置 |
| G-006 | Replay / Audit | 写侧+对账闭环；Timeline 已暴露（API+UI）；Replay/Audit 读侧 service 就绪；**Replay/Audit API 已暴露**（增量 C1：helloai-api AgentEventController——GET /api/agent-events/traceByRunId/{runId} + GET /api/agent-events/pageAuditByTaskId/{taskId}，API 层 DTO 投影 + ControllerTest 5 用例）+ **UI 工作台已上线**（/event-stream 事件流：Replay 轨迹时间线 + Audit 分页表格 + eventType 过滤 + payload 原文折叠；事件字典抽离 utils/eventMeta 与 SubTaskDetail 时间线同源共享） | 基于统一 Event 查询/回放；**外部执行轨迹对齐**（外部路径 agent_execution_record 0 行、事件仅完成态，Replay 时外部任务仅「派发→完成」细线） | **P1** | 增量 C1 落地（2026-09-08）：Timeline ✅ / Replay ✅ / Audit ✅（API+UI，api 56 单测 + type-check/build 全绿）；增量 C2 落地（2026-09-08）：外部认领埋点 AGENT_STARTED + Replay run 级汇总卡（core 799 单测 + type-check/build 全绿），外部轨迹加厚为「AGENT_STARTED → AGENT_COMPLETED → REVIEW_STARTED → REVIEW_APPROVED」四事件 |
| G-007 | Quality Gate | Reviewer 闭环已存在 | Rule + Test + LLM 统一决策 | **P2** | 现有链上增强 |
| G-008 | Agent Fleet Routing | 已有 Agent 选择机制；单外部执行者场景下 preferred 指定（真实任务 5 子任务同一 agent）；外部执行 tokens=null（成本观测盲区，submitResult 未回传） | Capability + Health + Load + Policy | **P2** | 渐进升级；多外部执行者对照与 token 回传为前置验证场景（见 log 2026-09-08 观察点 3/4） |
| G-009 | Dynamic Workflow | 已有模板/实例化/DAG | 动态分支、复杂运行期编排 | **P3** | 后置 |
| G-010 | Planner 能力感知与自适应粒度 | **S1~S3 已落地（2026-09-09）**：S1 数据层（V74 sub_task.required_skills JSONB + constraints TEXT）+ S2 拆解侧（技能目录常驻注入 / 子任务级 requiredSkills+constraints 指派 / 目录过滤 task_plan_skill_filtered 审计 / rule-based 粒度三档 FINE/STANDARD/COARSE + 目录超 20 项截断）+ S3 传递链（mergeSkills 并集装箱 5 装箱点同源 / inbox 技能要求行 / REST 下行 / 草案确认 UI 展示编辑 + updateDraftById 端点 / SKILL.md 增量） | 技能目录注入拆解 Prompt + 子任务级 requiredSkills/constraints 指派（V74 新列，并集装箱）+ 粒度三档 FINE/STANDARD/COARSE 自适应（**rule-based 决策矩阵**：执行者画像 × difficulty 调制，2026-09-09 拍板）+ 外部感知下行通道（可选字段向后兼容）+ 技能回流贡献规范 | **P1** | S1~S3 PASS（2026-09-09，见 log）；S4 双场景实测 BLOCKED（本机无 dev 环境 + LLM Key + 外部 agent）；**后置缺口**：①技能回流贡献规范（D5-3 DB 化）②verify-skill-packages.ps1 校验脚本（D5-2 未交付）③审查侧 constraints/requiredSkills 注入核验（D4 验收口径，ReviewExecutionEngine 未消费两字段）④COARSE 档 constraints 缺失的 timeline WARN 级事件（设计 §2.3 承诺未实现） |

# 2. P0 主线

## G-001 Event Stream

验收：

- Legacy 与 Runtime 产生同一 Event Model；
- Timeline / Audit 逐步统一从 Event 获取事实；
- 一个 Run 可以按 sequence 重建轨迹；
- Event 不成为第二业务状态源；
- 写入具备幂等和可对账能力。

## G-002 Dual Executor

原则：

> Dual Executor 只是迁移策略，不是长期架构。

```text
ExecutionRouter
      ↓
 ┌────┴─────┐
 ↓          ↓
Runtime    LegacyAdapter
```

禁止：

```text
复制完整业务链
复制第二套状态机
复制第二套 Review
无幂等地再次执行副作用
```

## G-003 AgentRuntime

推荐提取顺序：

```text
1. Context
2. EventRecorder
3. ToolRegistry / ToolExecutor
4. AgentLoop
5. Session
6. Sandbox
```

# 3. P1

### Skill Capability

保持 Markdown 兼容，同时增加：

```text
version
requiredTools
dependencies
inputSchema
outputSchema
validationRules
```

### Sandbox Provider

第一阶段只完成 Provider Contract，不把 Docker/K8s 当作已完成安全隔离。

### Event Consumers

顺序：

```text
Timeline / Replay
→ Audit
→ Recovery
→ Fork
```

### Planner 能力感知（G-010）

设计：`doc/design/Planner_Capability_Awareness.md`（2026-09-09 落稿，§7 决策 1~7 全部拍板）。

已拍板：

```text
登记口径：新 G-010（不并入 G-004）
粒度决策：rule-based 矩阵（执行者画像 × difficulty；LLM 自判后置）
装箱语义：并集（子任务级 ∪ 任务级，去重保序，子任务级在前）
目录注入：常驻（每技能一行摘要，超 20 项截断）
constraints：显式列（仅 COARSE 必填）
回流：classpath 声明态（DB 化/热加载后置）
混合粒度：FINE（白名单为空 = STANDARD）
```

实施状态（2026-09-09）：

- S1 数据层：PASS（V74 迁移 + SubTask 实体/DTO + 单测）；
- S2 拆解侧：PASS（提示词三段 + PlanDraftItem 扩展 + PlannerGranularityResolver 矩阵单测 + buildDrafts 目录过滤落库）；
- S3 传递链：PASS（mergeSkills 并集装箱：SubTaskAutoExecutionDispatcher / ReviewServiceImpl / SubTaskReviewServiceImpl / SubTaskController.execute / SubTaskDispatchServiceImpl 选人约束五点同源；inbox summary 技能要求行；SubTaskResponse REST 下行；草案确认 UI 展示/编辑 + updateDraftById fail-close 端点；executor SKILL.md 子任务级指派说明）；
- S4 双场景实测：BLOCKED（本机无 dev 环境 + LLM Key + 外部 agent，口径与历轮一致）。

验证基线：core 全量单测 0 失败；api 模块编译通过；UI type-check 通过。

# 4. P2

```text
Quality Gate
Capability-based Agent Routing
Historical Success
Cost / Latency
```

# 5. P3

```text
Dynamic Workflow
LLM-generated branching
Advanced Sandbox
Cross-session optimization
```

# 6. 明确不再作为开发要求的口径

```text
❌ 为了凑 Harness 功能而复制 Harness
❌ SkillRegistry 作为独立“大框架”重新建设
❌ Sandbox = Local/Remote Environment 的同义词
❌ Dual Executor 永久双轨
❌ Planner / Executor / Reviewer 各自拥有完整执行能力
❌ 新建第二套 Workflow Runtime
```

# 7. Gap 登记与回流规则

## 7.1 孤儿项回流

设计文档（专项设计 / 执行方案 / ADR）中作出「推迟到 X 期」「划远期」「降级交付」的决定时，必须在本表同步登记对应条目或显式记 WONTFIX，不得只留存在设计文档内——否则该承诺会随批次闭环从索引中消失。

## 7.2 历史编号映射（2026-09-07 文档重构）

| 旧编号（已归档） | 新编号 | 能力 |
|---|---|---|
| N-013 | G-003 | Harness 执行循环（AgentLoop / ToolExecutor） |
| N-014 | G-004 | Skill 元数据结构化 |
| N-015 | G-005 | Sandbox Provider 化 |
| N-016 | G-006 | Event Stream 消费侧（Replay / Audit） |
| N-017 | G-008 | Agent Fleet 能力化选人 |
| N-018 | G-007 | Quality Gate |
| N-019 | G-009 | Workflow Engine 增强（远期） |

旧编号明细与登记背景见 `archive/legacy/V1_HelloAI 实现差距表.md` 与 `archive/logs/2026-09.md`（LOG-20260907-001）。
