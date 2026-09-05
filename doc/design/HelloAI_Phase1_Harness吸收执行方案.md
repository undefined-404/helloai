# HelloAI Phase 1 Harness 吸收执行方案

> 主轴：把 `AgentRuntime` 从「唯一执行契约」（Phase 0 C3 已落地）扩展为「显式一等公民的 Harness 能力面」，让 SkillRegistry / ToolRegistry / Session / EventStream / SandboxProvider 成为 Runtime 的可消费维度，而不是旧链内部的隐式依赖。
>
> 文档约定：§0 全景路线（1.1/1.2/1.3/1.4 已落地）；§1 本期 Step 1 范围（Step 1 时点锚定，Step 2/3/4 已后续落地，见 §0/§6）；§2 任务分解；§3 决策记录；§4 与 ADR-001 关系；§5 依赖与提交；§6 后续 Step 2/3/4（Step 2/3/4 已全部落地）。

***

## §0 Phase 1 全景路线（5 阶段）

| 子阶段 | 主题 | 状态 | 关键产出 | 落点 |
|---|---|---|---|---|
| **1.0 Runtime 固化** | `AgentContext` + `AgentRuntime` + `LegacyExecutorAdapter` 唯一执行入口 | ✅ 已落地 | AgentContext 六字段契约 + C1/C2/C3 双轨切换（LOG-20260904-006） | Phase 0 C1-C3 |
| **1.1 Session Manager** | N-007 执行恢复载体（快照/恢复上下文/断点续接） | ✅ 已落地（Step 3） | `agent_session` 表 + AgentSessionService（Turn 级执行会话：快照/中断点/恢复上下文）+ 租约回收落 `sub_task_session_interrupted` + ABORT 幂等（LOG-20260905-002） | N-007 PARTIAL→收口中 |
| **1.2 Skill Registry** | 抽 `KNOWN_SPECS` 为 SkillRegistry 元数据消费面 | ✅ 模式已定型（不建平行类） | 与 ToolRegistry 同「resolve → matched 元数据」形态收拢；`AgentSkillSpecService` 即 skill 侧元数据面（§50.7 不另建 SkillRegistry 类） | Step 2（LOG-20260905） |
| **1.3 Tool Registry** | `AgentContext.tools` 供电 + ToolRegistry 描述层 | ✅ 已落地（Step 2） | `agent.tool` 包 ToolDefinition + ToolRegistry（从 ToolCallbackProvider 收集平台 12 工具元数据）+ TOOL_RESOLVED 埋点 + 对账同步（LOG-20260905） | Step 2 |
| **1.4 SandboxProvider** | 仅接口 + RemoteAgent + LocalProcess；不引 Docker/K8s | ✅ 已落地（Step 4） | `agent.runtime` 包 ExecutionEnvironment（name/supports）+ RemoteAgent/LocalProcess 实现 + ExecutionEnvironmentProvider 解析注入 `AgentContext.environment` + ENVIRONMENT_RESOLVED 埋点（step=7）+ 6 处对账契约同步（LOG-20260905-003） | Step 4 |

### 不做项（明文边界）

- **不引** DockerSandbox / K8sSandbox / Workflow / Team / Browser 等「第二控制面」能力（参照 [HelloAI_架构设计参考](HelloAI_架构设计参考.md)）。
- **不复制** AgentTeams 的 Workflow/Team/Browser 子系统，仅借鉴 Harness 抽象（Session / EventStream / SkillRegistry / ToolRegistry / SandboxProvider 五件套）。
- **不在本批做** P2 级重构（Planner 上下文、跨会话记忆、MQ 治理等，留 P2 路线）。

### 引用关系

- 本方案配套前置：[ADR-001 Run/Turn/Step 模型](adr/ADR-001-run-turn-step-model.md)（R1 step 槽位语义 + R2 as-is 对齐已落）。
- 调度解耦对齐：[HelloAI_调度解耦重构分析](HelloAI_调度解耦重构分析.md)。

***

## §1 本期 Step 1 范围（落地锚定）

**目标**：把 Skill 从旧链内部硬编码依赖（`PluginSkillSpecServiceImpl` 直接读 `task.requiredSkills` 渲染），提升为两件一等公民事实：

1. **契约输入**：`AgentContext.skills` 从默认空列表变为显式供电；
2. **事件可观测**：`SKILL_RESOLVED` 从「有定义零埋点」变为「恒发落库、对账无差」。

### Out of scope（Step 1 落地时点锚定；→ ✅ 项已由后续 Step 落地）

- Tool 供电（Step 2）→ ✅ 已落地（LOG-20260905-001，见 §0 1.3 / §6）；
- Session 收拢（Step 3）→ ✅ 已落地（LOG-20260905-002，见 §0 1.1 / §6）；
- SandboxProvider（Step 4）→ ✅ 已落地（LOG-20260905-003，见 §0 1.4 / §6）；
- **SkillRegistry 抽象（D2=A 暂缓）** → ✅ 模式已定型（§50.7 不建平行类，见 §0 1.2）。

### 前置已就绪

- ADR-001 R1（step =槽位 5）+ R2（as-is 对齐）已落文档。

***

## §2 任务分解

### T1 — `AgentContext.skills` 供电

- **文件**：[LocalExecutionCommandConsumer.java](../../helloai-core/src/main/java/com/helloai/core/agent/mqconsumer/LocalExecutionCommandConsumer.java#L149-L159)
- **现状**：`runViaRuntime` 构造 AgentContext 只填 6 个定位字段（L152-159），`skills` 落 `@Builder.Default` 空列表（[AgentContext.java L43-45](../../helloai-core/src/main/java/com/helloai/core/agent/runtime/AgentContext.java#L43-L45)）。
- **改动（2026-09-04 修订为命令装箱方案，LOG-20260904-009）**：
  1. `ExecutionCommand` 新增 `requiredSkills` 装箱字段（与 `Task.requiredSkills` 同名对应，D4）；
  2. 命令创建方在 task 域内查询后装箱：`SubTaskService.requiredSkillsOf(taskId)`（懒解析 `ObjectProvider<TaskService>`，破构造环）——Dispatcher / Review（人工打回）/ SubTaskReview（自动打回）/ 管理端执行 4 处 `createAssignedCommand(..., requiredSkillsOf(...))`；
  3. Consumer **不注入 TaskService**（§6 依赖方向红线：agent → task 禁止新增），`runViaRuntime` 改从 `command.getRequiredSkills()` 取 skills 注入 builder；`null → Collections.emptyList()` 不阻断执行。
- **验收（修订）**：[LocalExecutionCommandConsumerTest](../../helloai-core/src/test/java/com/helloai/core/agent/mqconsumer/LocalExecutionCommandConsumerTest.java) **不加** `@Mock TaskService`；skills 断言改为命令装箱值透传（L83-90 argThat 扩展 `getSkills()`）；3 条 RuntimeExecutionPath 用例断言 command 携带 requiredSkills。
- **注意**：纯契约完备性——`LegacyExecutorAdapter.execute`（L41-66）透传 `ctx.getSkills()`（consume 侧已由 Task 查询改为 command 装箱值），运行时行为**零变化**，价值在于为 SkillRegistry/ToolRegistry 消费定型模式。

### T2 — `SKILL_RESOLVED` 埋点（step=5）

- **文件**：[SubTaskExecutionServiceImpl.java L263-L283](../../helloai-core/src/main/java/com/helloai/core/agent/service/impl/SubTaskExecutionServiceImpl.java#L263-L283)
- **改动**：[L216 `agentSkillSpecService.resolve(requiredSkills)`（requiredSkills 由命令装箱传入，纯函数入参，不再按 taskId 反查）](../../helloai-core/src/main/java/com/helloai/core/agent/service/impl/SubTaskExecutionServiceImpl.java#L216) 之后、CONTEXT_BUILT（step=2）之前插入（复用 L241 `runTurn`、L233 `subTaskId`、L159 `safeMap`、L355 `recordEventSafely`）：

  ```java
  recordEventSafely(AgentEventContextResolver.resolveRunId(subTask.getTaskId()),
          subTask.getTaskId(), subTaskId, runTurn, 5,
          AgentEventType.SKILL_RESOLVED, agent.getId(),
          safeMap("requiredSkills", required, "resolvedSpecs", matched));
  ```

- **恒发纪律**：`requiredSkills` 为空也发（payload 两键恒在，值可为空数组）。
- **step 槽位 vs 时序说明**：SKILL_RESOLVED 的 create_time 实际早于 CONTEXT_BUILT（L263 vs L278），但 step=5 > step=2——这正是 R1「step 是类型槽位、不承担时序」的体现；对账按 `create_time DESC, id DESC` 取末条，无影响。
- **验收**：[SubTaskExecutionServiceTest](../../helloai-core/src/test/java/com/helloai/core/agent/execution/SubTaskExecutionServiceTest.java) **新增 `@Mock AgentEventRecorder`** + ArgumentCaptor 断言 `record(..., 5, SKILL_RESOLVED, ...)` 且 payload 含 requiredSkills/resolvedSpecs 两键；空技能用例断言空数组。

### T3 — 7 处对账契约同步（必须同一提交，防裂口）

| # | 文件 | 改点 |
|---|---|---|
| 1 | [EventReconciliationServiceImpl.java L52-65](../../helloai-core/src/main/java/com/helloai/core/agent/event/impl/EventReconciliationServiceImpl.java#L52-L65) | `IN_PROGRESS` 期望集（L54-58）加 `SKILL_RESOLVED.code()`；javadoc L28-29「step 1-4 递增」改「step 1-5 槽位」 |
| 2 | [verify-c3-events.ps1 L119](../../scripts/powershell/verify-c3-events.ps1#L119) | P2 `IN_PROGRESS AND last_event IN (...)` 加 `'skill_resolved'` |
| 3 | [verify-c3-events.sh L95](../../scripts/shell/verify-c3-events.sh#L95) | 同上（ps1 同构） |
| 4 | [verify-c3-reconcile.ps1 L151](../../scripts/powershell/verify-c3-reconcile.ps1#L151) | matched 分支加 `'skill_resolved'` |
| 5 | [verify-c3-reconcile.ps1 L157](../../scripts/powershell/verify-c3-reconcile.ps1#L157) | **mismatches 分支同步加**（漏改则反向误报） |
| 6 | [verify-c3-reconcile.sh L100](../../scripts/shell/verify-c3-reconcile.sh#L100) | matched 分支加 `'skill_resolved'` |
| 7 | [verify-c3-reconcile.sh L106](../../scripts/shell/verify-c3-reconcile.sh#L106) | **mismatches 分支同步加** |

- **验收**：重跑 `verify-c3-events`（P1/P2/P3）与 `verify-c3-reconcile`，P2 无 MISMATCH、mismatches=0。

### T4 — 验证收口

- 单测：T1（LocalExecutionCommandConsumerTest）+ T2（SubTaskExecutionServiceTest）全绿；
- **Maven 命令必须带 `-DskipTests=false`**：`mvn -pl helloai-core test "-DskipTests=false"`（pom 默认 `skipTests=true`，不带则静默空跑）；
- 探针：T3 的 4 份脚本跑通；
- 回归：全量 1052 例基线不破。

### T5 — 文档回填（docs 提交）

- 月度日志 `doc/log/2026-09.md` 新增 LOG 条目（Phase 1 Step 1 交付）；
- 差距表：预计**无 gap 关闭**（11 项均与 Skill 供电无直接对应），仅当实施发现某 gap 状态变化时更新；
- **ADR-001（R2 修订，本地未提交）随本批次 docs 提交一起走**（回应上一轮挂起）。

***

## §3 决策记录

| ID | 决策 | 选项 | 推荐 | 状态 |
|---|---|---|---|---|
| **D1** | `SKILL_RESOLVED` payload 内容 | A: 只记 requiredSkills / **B: requiredSkills + resolvedSpecs** | **B** | ✅ 已拍 B（用户确认） |
| **D2** | SkillRegistry 是否本期收拢 | A: 只做 T1-T3 / B: 同步抽象 | **A** | ✅ 已拍 A（用户确认） |
| **D3** | git 处置（6be38cf 违规实现） | A: reset 重写历史 / **B: 保留 6be38cf + 追加 fix commit** | **B** | ✅ 已拍 B（用户确认） |
| **D4** | 装箱字段命名 | skills / requiredSkills / taskRequiredSkills | **requiredSkills**（与 `Task.requiredSkills` 一致，装箱来源直接对应） | ✅ 已拍（用户确认） |
| **D5** | Skill 资源位置 | 迁模块 / 新增模块 / **原地不动** | **原地不动**（资源已在 helloai-core，仅 service 类迁包） | ✅ 已定（用户确认） |
| **D6** | CODE_STYLE §6 加注 | A: 本轮一起 / B: 后续单独 | **A**（技术债 + 禁新增 + 定回收，不是洗白先例） | ✅ 已拍 A（用户确认） |

### D1 决策说明（已拍 B，2026-09-04 实施方式修订见下）

- **payload 字段**：`requiredSkills`（原始声明）+ `resolvedSpecs`（命中的 `eng-*` 标签，两层过滤：标签命中 + 速览非空，与 [AgentSkillSpecServiceImpl L40-69](../../helloai-core/src/main/java/com/helloai/core/agent/skill/AgentSkillSpecServiceImpl.java#L40-L69) 注入事实一致）。
- **AgentSkillSpecService 改动（迁域 + 纯函数化）**：接口 + 实现由 task 域 `PluginSkillSpecServiceImpl` 迁至 agent 域 `agent.skill` 包；`ResolvedSpec resolve(List<String> requiredSkills)` 入参即装箱的 requiredSkills，不再接收 taskId，实现与 task 域零依赖（§6 依赖方向红线）。
- **executeOnce 改动**：调 `resolve(command.getRequiredSkills())` 一次得 record，从 record 取 `section` 用于 prompt 装配，取 `requiredSkills` + `matchedLabels` 写 SKILL_RESOLVED payload。
- **代价**：executeOnce 零 task 读取（读取发生在命令创建方的 `requiredSkillsOf`，随命令装箱）；record 是迁域时引入的内部 DTO，落在 `agent.skill` 包。

### D1 落地形态修订说明（2026-09-04，LOG-20260904-009）

- 6be38cf 的初版实现（Consumer 注入 `TaskService` + `PluginSkillSpecService.resolve(Long taskId)` 反查 task）违反 CODE_STYLE §6「agent → task 禁止反向依赖」——§7.1「走 Service」只约束依赖方式，不豁免依赖方向；
- 落地改为命令装箱：`ExecutionCommand.requiredSkills` 由 4 处命令创建方（Dispatcher / Review 人工打回 / SubTaskReview 自动打回 / 管理端执行）经 `SubTaskService.requiredSkillsOf(taskId)`（task 域内查询，`ObjectProvider<TaskService>` 懒解析破构造环）装箱，沿命令链路正向传入 agent 域；`PluginSkillSpecServiceImpl` 三件套删除；
- git 按 D3=B 处置：保留 6be38cf（审计记录），追加 fix commit。

### D2 决策说明（已拍 A）

- SkillRegistry 抽象与 1.3 Tool Registry 一起定型 Registry 模式，**单独抽象会撞 CODE_STYLE §50.7「不新增平行架构」**；
- 最小闭环优先，本期只把 Skill 能力提升为 Runtime 一等公民（契约 + 埋点 + 对账），Registry 元数据消费面留到 Step 2。

***

## §4 与 ADR-001 的关系

| 文档 | 关联 | 动作 |
|---|---|---|
| [ADR-001 R2 修订](adr/ADR-001-run-turn-step-model.md) | as-is 对齐（roundNum / DDL / 5 处契约同步点） | 本地未提交，**随本批次 docs 提交** |
| **ADR-001 R3 待补**（本期新增） | 记录 T2 实际埋点精确行号 + T3 5 文件 7 处改点的最终行号 | Step 1 实施完成后追加 R3 修订记录 |

***

## §5 依赖与提交顺序

```
T1 ──┐
     ├──→ T3（与 T2 同一提交：埋点落点 + 对账集合缺一不可）
T2（依赖 D1=B 已定 payload）──┘
        ↓
     T4（单测 + 探针）→ T5（docs）
```

- **提交拆两笔**（代码/文档分离，遵循项目惯例）：
  - `feat(core)`：T1-T3 + 单测（T4 单测部分）；
  - `docs`：T5（LOG）+ ADR-001 R2（as-is 修订）+ **本 P1 plan 文档（新增）**；
- **关键约束**：T2 与 T3 必须同一提交（R1 伴生约束硬性要求，漏改任一处探针即误报 MISMATCH）；
- **git push 由用户执行**（项目惯例：AI commit，用户 push）。

***

## §6 后续 Step 2/3/4（Step 2/3/4 已全部落地）

| 阶段 | 主题 | 衔接 |
|---|---|---|
| Step 2 | Tool 供电 + ToolRegistry | ✅ **已落地**（LOG-20260905）：`AgentContext.tools` 显式供电（消费侧 agent 域直读 `getEnabledTools`）+ `agent.tool` 包 ToolRegistry（从 spring-ai ToolCallbackProvider 收集平台 12 工具元数据）+ `TOOL_RESOLVED` 埋点（step=6）+ 6 处对账契约同步；Skill 侧以同「resolve → matched」形态收拢（§50.7 不建平行类）。契约供电 + 埋点 + 对账三件套全闭环 |
| Step 3 | Session Manager（N-007 收口） | ✅ **已落地**（LOG-20260905-002）：`agent_session` 表 + AgentSessionService（Turn 级执行会话，3 个写入点：start/advance/终态）+ 租约回收路径读 session 落 `sub_task_session_interrupted` 中断点 timeline + ABORT 幂等防重入。执行快照/中断点/恢复上下文载体闭环；「重派注入恢复上下文」「LLM 级断点续接」明确留待后续 |
| Step 4 | SandboxProvider（仅接口 + RemoteAgent + LocalProcess） | ✅ **已落地**（LOG-20260905-003）：`agent.runtime` 包 `ExecutionEnvironment` 接口（name/supports，路由契约与 AgentExecutorRouter 同构）+ `RemoteAgentEnvironment`（CLI_CLIENT/WEB_BROWSER）/`LocalProcessEnvironment`（API_KEY_LLM）+ `ExecutionEnvironmentProvider.resolve(accessType)` 消费侧解析注入 `AgentContext.environment`（agent 域数据直读，无 §6 跨域）+ `ENVIRONMENT_RESOLVED` 埋点（step=7，ADR-001 R4）+ session 快照补 environment + 6 处对账契约同步。DockerSandbox 推迟 P2、K8sSandbox 推迟 P3 |

> 每个 Step 落地时仍按「小而闭环」原则：契约 + 埋点 + 对账 + 文档，不平行架构。

***

## 修订记录

### R1（2026-09-04）：Phase 1 Harness 吸收执行方案定稿

- **背景**：Phase 0 P0（A1-A3 / B1-B3 / C1-C4）于 LOG-20260904-007 闭环；P1 主轴聚焦 Harness 能力吸收（SkillRegistry / ToolRegistry / Session / EventStream / SandboxProvider）。
- **范围**：本期 Step 1 = Skill 供电 + SKILL_RESOLVED 埋点 + 7 处对账契约同步；1.2 Skill Registry 暂缓，1.3 Tool Registry 与 1.4 SandboxProvider 留后续 Step。
- **决策**：D2 = A（SkillRegistry 暂不做），D1 = B（payload = requiredSkills + resolvedSpecs）。
- **与 Phase 0 衔接**：沿用「契约供电 + 埋点 + 对账同步」三件套模式，路径与 LOG-20260904-006（C3 Step 5/6）一致。