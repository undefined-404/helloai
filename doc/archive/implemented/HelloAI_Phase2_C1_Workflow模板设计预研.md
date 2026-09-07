> **状态：HISTORICAL / REFERENCE**。当前开发请以根目录 Current/Target/Gap/Plan 为准。

# HelloAI Phase 2 C1：Workflow 模板设计预研（N-001）

> 主轴：为差距表 N-001（Workflow 模板，TODO P1）产出**可实施级设计草案**（P2-C 设计预研，D3=乙 并行）。核心结论：**Workflow 模板 = 现有 Task/SubTask/调度链的"可复用蓝图 + 一次性实例化生成器"，不建第二控制面/第二运行时**——实例化后直接物化为现有 `task`/`sub_task`，后续执行完全复用现有调度/执行/回写/收敛链。
>
> 文档性质：设计预研（草案），表结构与接口为实施前方向；§10 四决策已拍板，实施细节如有新分歧再确认。
>
> **状态**：已实施（2026-09-06，S1~S4 代码完成 + S5 文档回填收口；模板/版本 CRUD、实例化物化、实例状态聚合、验证全部落地，见 §8 路线 + R3 实施记录）；用户审阅拍板 §10 四决策已全部落稿。

---

## §0 设计输入与约束

### 0.1 差距表 N-001 要求（8 项差距 + 验证）

```text
Workflow 定义 / Template / Version / 节点模型 / 节点依赖 / 参数化 / 模板实例化 / 执行状态
验证：模板创建、模板实例化、节点依赖、节点失败、重试、Workflow 完成、Workflow 恢复
建议：独立设计，不直接修改现有 Task 模型硬塞 Workflow 语义
```

### 0.2 现有事实基线（代码核查）

| 能力 | 现状 |
|---|---|
| 层级 | `task` → `sub_task`（moduleId 分组） |
| 依赖 | `sub_task.depends_on`（JSONB 数组，ready 语义：全部前置 DONE 才可分派） |
| 调度 | `SubTaskService.assignNext` / `claimSubTask`（DB 原子认领 + 乐观锁） |
| 执行 | `ExecutionCommand` → `LocalExecutionCommandConsumer`/`MqExecutionCommandConsumer` → `ExecutionResultHandler`（唯一回写入口） |
| 收敛 | `LeaseReconcilerTask` / `ExecutionCompensationTask` / `SubTaskTimeoutTask` / `AssignedSubTaskTimeoutTask` / `SubTaskPendingOrphanTask`（超时/孤儿兜底） |
| 观测 | `agent_event`（Run/Turn/Step 三层，append-only）+ `task_timeline` |
| 任务级策略 | `task.agent_policy`（plannerAgentId / executorAgentIds / reviewerAgentId / fallbackPolicy / difficulty）+ `task.required_skills` + `task.sla_minutes` |
| 契约先行 | `sub_task.is_contract` + `task_running_spec.contract` 全局注入 |
| 终稿 | `task.final_report` + `final_report_status`（NONE/GENERATING/DONE/FAILED） |

### 0.3 架构约束（红线，不可逾越）

1. **单一控制面**：不引第二套 Workflow 运行时 / 第二控制面（架构参考 §1.3/§5、Phase1 Harness 方案 §0 均明文）。
2. **节点落到角色**：Workflow 节点 = 角色槽位（planner / executor / reviewer），不绑定具体 Agent 实例（架构参考 §4.8 目标态八）。
3. **主链不可破坏**：调度只发命令 / 执行独立消费 / 结果异步回写 / 最终一致收敛（架构参考 §4 目标态一~四）。
4. **职责分层**：Planner 生成 Plan/DAG（What）→ Workflow Engine 解释 DAG 算依赖/并发/超时/补偿（When/Order）→ Scheduler 按 Lease/CAS 派发（Who/Where）→ Agent Runtime 执行（How）→ Quality Gate 验收（Phase0 执行方案"术语精确化"；调度解耦重构分析 §5.2 四层）。
5. **执行边界对象**：节点触发落到 `ExecutionCommand`，结果统一走 `ExecutionResultHandler`，失败靠收敛层兜底。
6. **可观测底座**：复用 Run/Turn/Step 事件模型（`agent_event`），不 Event Sourcing（ADR-001）。
7. **不硬塞 Task**：Workflow 是独立抽象，Task 模型不因 Workflow 而改结构（差距表 N-001 建议）。

### 0.4 外部借鉴映射（设计取舍参考）

| 来源 | 可借鉴 | 落点 |
|---|---|---|
| AgentTeams-main（调度内核） | Team 委托模型（Manager→Leader→Worker）；state.json 单一事实源 + transitions；任务恢复流（meta/spec/state/history） | Workflow 实例状态聚合、节点依赖推进、失败恢复 |
| Vibe-Skills-main（工作流运行时） | 6 阶段状态机 + gate/artifact/exit criteria/rollback；Task Contract（goal/scope/DoD/verification/dependencies/effort）；Late Skill Binding | 节点规格字段设计、节点级规范注入 |
| DeepSeek Harness（能力插件） | eng-* 规范库（eng-code-review / eng-doc-standard / eng-verification）、五件套抽象（能力维度） | 模板节点可选绑定的执行规范段（非引擎骨架） |

---

## §1 核心设计决策

### D1：Workflow 的定位 = "模板化实例生成器"，不是第二运行时

```text
Workflow Template（定义态：节点图 + 依赖 + 参数 schema）
      ↓ 实例化（Workflow Engine 一次性解释）
Workflow Instance（实例态：绑定版本快照 + 参数）
      ↓ 物化
现有 task + sub_task 集（节点 → sub_task，节点依赖 → sub_task.depends_on，参数 → 字段填充）
      ↓ 之后完全走现有调度/执行/回写/收敛链（零第二运行时）
```

**理由**：现有 `sub_task.depends_on` 已具备 DAG ready 语义，调度/执行/收敛链已闭环。Workflow 只需在"实例化"这一步把模板蓝图物化为现有结构，**DAG 解释一次性完成**，运行期不逐节点解释。这样引擎职责最薄、复用最大化、彻底规避第二控制面风险。

### D2：节点模型 = 角色槽位 + 节点规格 + 执行约束

```text
WorkflowNode（模板节点）
  ├─ id / node_key（图内唯一，如 "contract" / "design" / "implement"）
  ├─ role：planner | executor | reviewer        ← 角色槽位（模板粒度只到 role，不绑 Agent 实例）
  ├─ spec：Vibe-Skills Task Contract 字段
  │    goal / scope{in,out} / definition_of_done / verification{method,evidence} / dependencies / estimated_effort
  ├─ constraints：skills[] / provider / access_type（预留提示，首版不做节点级执行者绑定）
  └─ depends_on：node_key[]（模板态依赖，实例化 → sub_task.depends_on）
```

**落点（V1 事实校准）**：实例化时 `spec` + `constraints` 只渲染到 **V1 实有列**（`sub_task.title/deliverable/acceptance/content`）+ `context` 扩展键（见 D6 单顶级键）。**`sub_task` 无 `required_skills` 列**（该列是 task 级 V47 加的），**节点级能力约束首版不表达**。`task.agent_policy` 是**任务级单值**（JSONB 一组 executorAgentIds 等，一个 task 的所有 sub_task 共享）——实例化以**模板级默认 agentPolicy（任务级单值）**填充或留空交调度/人工，**不存在"节点级覆盖"**（C1 初版该表述已删除；节点级差异化 = N-002 Team 真实缺口，见决策 4 切分线）。

### D3：Workflow 引擎职责边界（When/Order，不与 Scheduler 抢 Who/Where）

```text
Workflow Engine（实例化 + 状态聚合）
  ├─ 实例化：模板版本快照 + 参数 → 物化 task/sub_task（含依赖 DAG）
  ├─ 状态聚合：实例状态从 sub_task 状态推导（节点完成度 → 实例进度）
  ├─ 补偿：模板级超时（实例级 SLA）/ 失败策略（节点重试上限 → 实例 FAILED 或人工介入）
  └─ 恢复：基于现有收敛层（重派/回收/孤儿兜底）+ agent_event 观测
Scheduler / Agent Runtime / Quality Gate：完全复用现有
```

**关键**：Workflow Engine **不直接触发执行**——实例化后节点由现有调度链接管（PENDING → ready → assignNext → ExecutionCommand）。引擎只做"模板解释 + 状态聚合 + 模板级补偿"，不碰 Who/Where/How。

### D4：版本管理

```text
workflow_template（当前激活版本） + workflow_template_version（不可变版本快照）
实例绑定 version_id → 实例化的 sub_task 固化，模板后续改版不影响历史实例
```

### D5：参数化

```text
模板声明 params schema（如 {{business_area}} / {{platform}} / {{goal}}）
实例化入参 params → 节点 spec/constraints 字段渲染（String.format / Map 占位符替换）
```

### D6：实例化上下文与实例状态（审阅拍板 2026-09-05）

1. **context 单顶级键**：模板来源标记统一装入 `sub_task.context.workflow = {nodeKey, spec:{estimatedEffort, scope, ...}, templateVersionId}`——**不平铺散键**（context 键空间无 registry，各写入方自有键，单顶级键避免撞名，已核实既有写入方均为增量 putAll patch 语义、追加键零冲突）。
2. **实例状态纯查询聚合（决策 2 方案 A）**：`workflow_instance` **不落 status 列**，实例完成度/状态每次现算（节点 DONE 数 / 总数）——与 D3"状态聚合从 sub_task 推导"完全同构，天然无一致性问题；若需列表展示用快照可另存 `status_snapshot`（无状态机、无约束力）。
3. **反锁禁令（硬约束）**：实例状态是**派生态（投影）**，**绝不反向约束已物化 task/sub_task 的操作**（FAILED 后不得禁止改派/rework/回收）——D1"实例化后脱离 Workflow 层"的硬性延伸。

---

## §2 概念模型

```text
Workflow Template（模板：name/description/当前版本引用）
  └─ Workflow Version（不可变版本：definition JSONB = 节点集 + 依赖边 + 参数 schema）
        └─ Workflow Instance（实例：绑定版本 + params + 物化出的 task_id）
              └─ 物化：task + sub_task 集（节点 → sub_task）
                    └─ 执行：复用现有调度/执行/收敛链
```

---

## §3 数据模型草案（实施前需确认）

### 3.1 `workflow_template`

| 列 | 说明 |
|---|---|
| id / name / description / status | 模板元信息（status: DRAFT / ACTIVE / ARCHIVED） |
| current_version_id | 当前激活版本（软引用） |
| category | 分类（可选，如 dev-release / data-etl） |

### 3.2 `workflow_template_version`

| 列 | 说明 |
|---|---|
| id / template_id / version_no | 版本号（1, 2, 3...） |
| definition JSONB | 节点集（D2 结构）+ 依赖边 + params schema |
| status / created_by | 版本状态（DRAFT / PUBLISHED），发布后不可变 |

### 3.3 `workflow_instance`

| 列 | 说明 |
|---|---|
| id / template_id / version_id | 实例绑定版本快照 |
| params JSONB | 实例化参数 |
| task_id | 物化出的主任务（软引用，实例 ↔ Task 1:1） |
| start_time / end_time | 生命周期 |
| status_snapshot | [决策 2 方案 A] 可选展示快照（无状态机、无约束力）；权威状态每次从 sub_task 纯查询聚合（D6-2），**不落权威 status 列** |

### 3.4 实例化产物

**复用现有 `task` / `sub_task` 表，不新增节点实例表**——节点就是 sub_task，节点依赖就是 `sub_task.depends_on`。来源标记统一装入 `sub_task.context.workflow` **单顶级键**：`{nodeKey, spec:{estimatedEffort, scope, ...}, templateVersionId}`（D6-1，不平铺散键）。实例状态由 sub_task 状态**纯查询聚合**（D6-2），不落列。

---

## §4 执行状态机

### 4.1 节点（sub_task）状态——复用现有 `SubTaskStatus`

```text
PENDING → READY（依赖全 DONE）→ ASSIGNED → IN_PROGRESS → REVIEW → DONE
                              ↘ BLOCKED（fail-close，人工介入）→ DONE / CANCELLED
```

### 4.2 实例状态——由节点状态聚合（纯查询投影，不落权威列）

```text
RUNNING（有节点未终态）→ DONE（全部节点 DONE + final_report DONE）
                        → FAILED（节点重试达上限 / 实例级 SLA 超时）
                        → CANCELLED（人工中止）
```

**实现（决策 2 方案 A）**：实例状态每次现算（节点 DONE 数 / 总数），不落权威列——与 D3"状态聚合从 sub_task 推导"完全同构，无一致性问题（D6-2）。

**反锁禁令（硬约束）**：实例状态是派生态（投影），**绝不反向约束已物化 task/sub_task 的操作**——FAILED 后不得禁止改派/rework/回收（D6-3）。

**两级恢复语义（拆清）**：

| 级 | 形态 | 现状 | 判定 |
|---|---|---|---|
| 节点级恢复 | BLOCKED/FAILED 子任务 → 人工改派 / rework | 已存在且成熟（重派链 + B1 `appendRecoveryContext` 续接注入） | 与 Workflow 无关，天然可用 |
| 实例级续跑 | 新实例保留已完成节点、只重跑失败节点 | 需 Workflow 层感知节点完成度并运行期干预，违背 D1"一次性物化、零运行期干预" | **首版砍** |

**人工重建**（实例级恢复的唯一形态）= 新建 `workflow_instance` 实例化到新 task，旧 task 保留为失败现场（审计/复盘），不做旧 task 复活。

---

## §5 实例化流程（骨架）

```text
createWorkflowInstance(templateId, params)
  ├─ 1) 取 current_version → 校验 params 满足 schema
  ├─ 2) 创建 task（title/description 由模板 + params 渲染，agentPolicy/requiredSkills/slaMinutes 取模板默认）
  ├─ 3) 遍历节点 → 创建 sub_task（spec 渲染字段；context 记 workflow 来源 + node_key；
  │       depends_on 由模板依赖边映射到子任务 id）
  ├─ 4) 创建 workflow_instance（绑定 task_id，status=RUNNING）
  └─ 5) 后续：现有调度链自动接管（PENDING → ready → 分派）
```

**引擎不触发执行**：实例化完成后即脱离 Workflow 层，全部由现有链推进。

---

## §6 与差距表 8 项对照

| 差距项 | 落点 |
|---|---|
| Workflow 定义 | `workflow_template`（模板元信息） |
| Workflow Template | 模板态节点图 + 版本引用（§3.1） |
| Workflow Version | `workflow_template_version` 不可变快照（§3.2） |
| 节点模型 | 角色槽位 + 节点规格 + 执行约束（D2） |
| 节点依赖 | 模板依赖边 → 实例化 sub_task.depends_on（D1） |
| 参数化 | params schema + 实例化渲染（D5） |
| 模板实例化 | Workflow Engine 一次性物化 task/sub_task（§5） |
| 执行状态 | 节点复用 SubTaskStatus；实例纯查询聚合不落权威列（§4.2） |

---

## §7 验证汇总（C1-S4 已实施，2026-09-05）

| 验证项 | 落点（测试类 / 方式） | 状态 |
|---|---|---|
| 模板创建 | `WorkflowTemplateServiceImplTest`（模板创建/更新/归档/发布版本不可变/version_no 递增）+ `WorkflowDefinitionValidatorTest`（definition JSONB 校验） | 通过（S1） |
| 模板实例化 | `WorkflowInstanceServiceImplTest.shouldMaterializeTemplate`（task 渲染创建/节点→sub_task 数量/依赖映射/参数渲染/context 单顶级键） | 通过（S2） |
| 节点依赖 | 同实例化用例断言 `updateDependsOn(implement→[contract])` + `WorkflowDefinitionValidatorTest`（依赖引用存在/DAG 无环）；ready 语义由现有调度链测试（既有回归）覆盖 | 通过（S2） |
| 节点失败 | `WorkflowInstanceStatusAggregatorTest.shouldStayRunningOnBlocked` + `WorkflowInstanceServiceImplTest.shouldStayRunningOnBlockedNode`（节点 BLOCKED fail-close → 实例 RUNNING 不终态，且聚合不反锁 task/sub_task）；现有 BLOCKED/重派链测试（既有回归） | 通过（S3/S4） |
| 重试 | `WorkflowInstanceStatusAggregatorTest.shouldAggregateDeadLetter`（DEAD_LETTER 熔断 → FAILED）；现有重派/重试测试（既有回归） | 通过（S3） |
| Workflow 完成 | `WorkflowInstanceStatusAggregatorTest.shouldAggregateDone` + `WorkflowInstanceServiceImplTest.shouldAggregateDone`（全节点 DONE + task DONE → 实例 DONE） | 通过（S3） |
| Workflow 恢复 | 现有收敛链测试（Lease 回收/孤儿兜底，既有回归）+ 人工重建语义（C1 §4.2：新实例新 task，旧 task 留失败现场）；实例状态纯查询聚合天然支持重建 | 通过（复用 + 语义） |

**全量回归**：core Tests run: 1178（基线 1158 + C1 新增 20，Failures/Errors=0）/ api Tests run: 41。既有回归（BLOCKED 链/重派/重试/收敛链/Planner）全部保持绿色，无行为回退。

---

## §8 实施路线建议（骨架先行，最小验证闭环）

```text
C1-S1 表 + 实体 + 模板/版本 CRUD（含 definition JSONB 校验）
C1-S2 实例化核心：模板+参数 → task/sub_task 物化（依赖映射 + 参数渲染）
C1-S3 实例状态聚合（从 sub_task 推导）+ 模板级补偿（实例 SLA/失败上限）
C1-S4 验证：§7 七项单测 + 全量回归
C1-S5 文档回填（差距表 N-001 收口口径 + LOG）
```

**不做（首版边界，决策 4 切分）**：
- **节点不绑定 agent**：模板粒度只到 role，实例化以模板默认 agentPolicy（任务级单值）填充或留空交调度/人工——节点级差异化 = N-002 Team 缺口（需节点级 agent 绑定 + 调度按节点元数据匹配，现状 `task.agent_policy` 任务级单值表达不了）
- **不做嵌套 Team 节点**（N-002 范畴）、条件分支/循环（YAGNI——depends_on 是静态 DAG、实例化一次性展开；循环可用"参数数组展开 N 组"在静态框架内近似但首版不做）、模板运行期热改
- **不做"从失败节点续跑"**（决策 2，人工重建实例）
- **不新增 sub_task 列**（spec 字段入 context 单顶级键 workflow，零 DDL）
- **依赖方向单向**：Workflow 不引用 Team；N-002 Team 未来以 `params.teamId` 作为实例化可选参数源填充 `task.agent_policy`（Team 可喂 Workflow，Workflow 不依赖 Team）

---

## §9 风险与边界

| 风险 | 缓解 |
|---|---|
| 退化成第二控制面 | D1 一次性物化 + 复用现有链，引擎只做模板解释/状态聚合，零运行期触发 |
| 与现有 Planner 拆解重叠 | 定位差异：Planner 拆解是"LLM 生成草案 → 人工确认 → 物化 sub_task"，Workflow 是"预定义模板 DAG 实例化"；两者不重叠。未来"模板+params 作 planner 输入生成变体"的正确接点在**实例化前**（走既有草案-确认链路，模板约束下的草案生成），非实例化后改已物化 DAG（决策 3） |
| sub_task.context 承载 spec 字段膨胀 | 只放节点规格结构化字段，运行期数据仍走现有 timeline/event |
| 版本/模板管理复杂度 | 首版只做基础 CRUD + 发布不可变，不做模板间继承/嵌套 |

---

## §10 决策拍板记录（2026-09-05 用户审阅定稿）

| # | 决策点 | 拍板 |
|---|---|---|
| 1 | spec 字段落点 | 落 `sub_task.context`（零 DDL）；**单顶级键 `workflow`** 整体装入 `{nodeKey, spec:{estimatedEffort, scope, ...}, templateVersionId}`，不平铺散键（context 键空间无 registry，各写入方自有键，避免撞名）；渲染目标 = V1 实有列（title/deliverable/acceptance/content）+ context 扩展键；**`sub_task` 无 `required_skills` 列，节点级能力约束首版不表达** |
| 2 | 失败续跑 | 首版不支持（违背 D1"一次性物化、零运行期干预"）；人工重建实例（新 task，旧 task 留失败现场）；实例状态**纯查询聚合（方案 A，不落权威列）**，**永不反锁 task/sub_task 操作**（D6-3） |
| 3 | LLM 微调 | 首版不接；根因 = 现有链路无"已物化 DAG 的 LLM 修订通道"（Planner 只写草案，物化后改子任务集不是其能力）。未来正确接点 = **实例化前**（模板+params 作 planner 输入，走既有草案-确认链路生成变体）；D4 版本管理即"微调"的确定性替代 |
| 4 | 首版边界 + N-002 切分 | **Workflow 管"流程形状"（节点=role+spec+约束，不绑 agent）、Team 管"槽位填充"（命名 agent 组合）**；节点级差异化显式划 N-002/后续；条件分支/循环不做（YAGNI）；N-003 Browser 预留 access_type 提示字段但不预读 |

**审阅修正清单（已全部落稿）**：

1. D2 落点段：删除"渲染为 sub_task.required_skills / task.agent_policy 节点级覆盖"不现实表述 → 校准为 V1 实有列 + context 单顶级键 + 模板级默认 agentPolicy（任务级单值）。
2. §3.4：来源标记明确单顶级键 `context.workflow = {nodeKey, spec, templateVersionId}`。
3. §4.2：实例状态语义拍板（纯查询聚合，不落权威列）+ 反锁禁令声明。
4. §8/§9：决策 4 结果回填（节点不绑 agent、模板默认单值、实例化前微调接点）。

---

## 修订记录

### R1（2026-09-05）：设计预研产出

- **背景**：P2-C 设计预研（D3=乙 并行），N-001 Workflow 模板。
- **核心**：D1=一次性实例化生成器（不建第二运行时）/ D2=节点=角色槽位+规格+约束 / D3=引擎只做 When/Order / D4=版本不可变快照 / D5=参数化渲染。
- **边界**：复用现有 task/sub_task/调度/执行/收敛链；不做嵌套 Team / 条件分支 / 运行期热改 / 失败续跑。

### R2（2026-09-05）：用户审阅拍板定稿

- **审阅修正（V1 事实校准）**：`sub_task` 无 `required_skills` 列（task 级 V47）；`task.agent_policy` 为任务级单值，无"节点级覆盖"——节点级差异化是 N-002 Team 真实缺口。D2 落点段已删除不现实表述。
- **决策拍板**：D1=spec 落 context 单顶级键 `workflow`（不平铺散键）/ D2=失败续跑首版砍，实例状态纯查询聚合（方案 A，不落权威列）+ 反锁禁令 / D3=LLM 微调首版不接（正确接点在实例化前）/ D4=Workflow 管流程形状、Team 管槽位填充（节点不绑 agent，单向依赖）。
- **新增 D6**：context 单顶级键 + 实例状态纯查询聚合 + 反锁禁令（§4.2 两级恢复语义拆清，§10 决策记录落稿）。

### R3（2026-09-05）：实施完成 + 验证汇总（S1~S4）

- **S1**：V68 两表 + 枚举/实体/Mapper + `WorkflowDefinitionValidator`（nodeKey 唯一/角色白名单/依赖引用/DAG 无环）+ 模板/版本 CRUD（发布 CAS 不可变 + current_version 回填）。core 1158 / api 39 全绿。
- **S2**：V69 `workflow_instance`（无权威 status 列，D6-2）+ `WorkflowInstanceService.createWorkflowInstance`（params 必填校验 → taskDefaults 渲染 → createTask → 逐节点 sub_task 物化（spec 渲染 + context 单顶级键 workflow{nodeKey,spec,templateVersionId}）→ depends_on node_key 映射回填 → 创建实例 → dispatchPendingSubTaskAuto（ready 守卫）→ task IN_PROGRESS）+ `WorkflowParamRenderer`。core 1165 / api 40 全绿。
- **S3**：`WorkflowInstanceStatusAggregator` 纯函数（ALL_DONE / CANCELED / DEAD_LETTER→FAILED / SLA_TIMEOUT→FAILED / IN_PROGRESS）+ `aggregateStatus` 纯查询聚合（不落权威列、无反锁）+ api `GET /workflow-instances/{id}/status`。core 1177 / api 41 全绿。
- **S4**：补服务层"节点失败 → RUNNING 不终态"集成用例（含反锁禁令断言）；§7 七项验证全部落点核对（见 §7 汇总表）；全量回归 core 1178 / api 41 全绿，既有 BLOCKED/重派/重试/收敛链回归无回退。
