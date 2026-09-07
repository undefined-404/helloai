# HelloAI 实现差距表

> **状态：CURRENT GAP**
>
> 本文只记录当前 → 目标的真实差距。
>
> 最后更新：2026-09-07

# 1. 总体矩阵

| ID | 能力 | 当前状态 | 目标 | 优先级 | 处置 |
|---|---|---|---|---|---|
| G-001 | Agent Event Stream | 已有 Run/Turn/Step + Event 基础 | 统一事件契约和消费体系 | **P0** | 当前主线 |
| G-002 | Executor 迁移 | Runtime 已有，Legacy 通过 Adapter 接入 | Runtime 成为唯一执行契约，旧实现退出 | **P0** | 双轨迁移 |
| G-003 | AgentRuntime | 已具备基本 Context / Execute / Event / Environment | Context + Session + Skill + Tool + Loop + Event + Sandbox | **P0** | 增量提取 |
| G-004 | Skill Capability | 已有 Skill resolve / resolvedSpecs | Metadata / Version / Tools / Schema / Dependencies | **P1** | 兼容演进 |
| G-005 | Sandbox Provider | 已有 Environment / Provider | 真正 Provider 化执行环境与隔离策略 | **P1** | 先契约 |
| G-006 | Replay / Audit | 有执行轨迹基础 | 基于统一 Event 查询/回放 | **P1** | Event 后建设 |
| G-007 | Quality Gate | Reviewer 闭环已存在 | Rule + Test + LLM 统一决策 | **P2** | 现有链上增强 |
| G-008 | Agent Fleet Routing | 已有 Agent 选择机制 | Capability + Health + Load + Policy | **P2** | 渐进升级 |
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
