# HelloAI 实现差距表

> **状态：CURRENT GAP**
>
> 本文只记录当前 → 目标的真实差距。
>
> 最后更新：2026-09-08

# 1. 总体矩阵

| ID | 能力 | 当前状态 | 目标 | 优先级 | 处置 |
|---|---|---|---|---|---|
| G-001 | Agent Event Stream | 已有 Run/Turn/Step + Event 基础 + Timeline 并轨（A6）+ Replay/Audit 读侧（A7） | 统一事件契约和消费体系 | **P0** | P0-A 完整闭环（A1~A7 已落地，验收全量成立） |
| G-002 | Executor 迁移 | Runtime 契约单轨 + 真身 + 主链接线注入（runtime-enabled 开关，默认 Legacy 零变化） | Runtime 成为唯一执行契约，旧实现退出 | **P0** | P0 主线收官 + 真灰度闭环：2026-09-08 dev 真身联调（RuntimeAgentLoop 点亮）/ 对账全绿 / 回滚零差异 / 外部 Agent 回归通过 / 真实任务全链闭环（外部端到端 14 分钟 5 子任务零故障，见 log 2026-09-08） |
| G-003 | AgentRuntime | 八件套已全部落地（Context / Session / Skill / Tool / Loop / Event / Environment / SandboxProvider 契约） | Context + Session + Skill + Tool + Loop + Event + Sandbox | **P0** | P0 完整闭环（P0-A/B/C 收官）；真实 provider tool-calling 循环 2026-09-08 联调通过，无边界问题 |
| G-004 | Skill Capability | SkillPackage 元数据层已落地（name/version/description/requiredTools/dependencies/inputSchema/outputSchema/validationRules，3 个 eng-* 已结构化）；resolve 渲染兼容不变 | Metadata / Version / Tools / Schema / Dependencies 全量 + **requiredTools→tools 联动** + SKILL_RESOLVED 携带版本 + 真实任务行使（当前 required_skills 流量为零） | **P1** | 元数据层完成（ed14e40 / 234bed4）；联动接线与真实验收待做 |
| G-005 | Sandbox Provider | 已有 Environment / Provider + SandboxProvider 契约（诚实策略，无 ISOLATED） | 真正 Provider 化执行环境与隔离策略 | **P1** | 契约已落地；Docker/K8s 隔离能力后置 |
| G-006 | Replay / Audit | 写侧+对账闭环；Timeline 已暴露（API+UI）；Replay/Audit 读侧 service 就绪 | 基于统一 Event 查询/回放；**外部执行轨迹对齐**（外部路径 agent_execution_record 0 行、事件仅完成态，Replay 时外部任务仅「派发→完成」细线） | **P1** | Timeline ✅ / Replay / Audit API+UI 待暴露；外部可观测深化待设计（见 log 2026-09-08 观察点 1） |
| G-007 | Quality Gate | Reviewer 闭环已存在 | Rule + Test + LLM 统一决策 | **P2** | 现有链上增强 |
| G-008 | Agent Fleet Routing | 已有 Agent 选择机制；单外部执行者场景下 preferred 指定（真实任务 5 子任务同一 agent）；外部执行 tokens=null（成本观测盲区，submitResult 未回传） | Capability + Health + Load + Policy | **P2** | 渐进升级；多外部执行者对照与 token 回传为前置验证场景（见 log 2026-09-08 观察点 3/4） |
| G-009 | Dynamic Workflow | 已有模板/实例化/DAG | 动态分支、复杂运行期编排 | **P3** | 后置 |

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
