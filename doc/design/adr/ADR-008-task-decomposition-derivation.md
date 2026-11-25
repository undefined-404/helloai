# ADR-008 任务拆解改为「派生式子任务」

- **状态**：**已接受（2026-11-10 定稿）**（2026-11-09 首稿；同日增补执行期重构；21:20 增补一致性契约 §18；2026-11-10 按四项用户裁定收敛并并入草案暂存区/编号设计；**2026-11-24 增补 §13.5 派生链的 git 拓扑**——派生关系映射为提交父子而非分支嵌套，含 ref D/F 冲突实测；**同日二次增补 §13.5⑥ 分支写者归属**——每个 ref 单一写者、平台侧只做单向「发布」禁「同步」，含双向写丢失提交实测）
- **唯一设计事实源**：本 ADR。`doc/design/HelloAI 草案暂存区与稳定编号设计.md` 的有效内容已并入 §19，该文件已删除；实施批次与状态只在 `doc/plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md` 的 `TD-16` 节维护
- **取代**：草案暂存区设计 §4.3（S3 展开）、§7（深度按 code 段数）；`TD-16e-3-1b`（`G3_ORPHAN`）、`TD-16e-4`（确认时扁平化）、`TD-16f`（废除下沉 + 栅栏）均作废
- **结构**：第一部分（§1~§10）= **草案期主动细化**；第二部分（§11~§17）= **执行期重构**；§18 = **一致性契约**；§19 = **并入的暂存区/编号模型**。前两部分共用同一个原语（派生独立 Task），触发时机与去向不同

> **一句话**：同一个「派生」动作，两个触发点——草案确认前想拆细（第一部分）、执行中反复失败需要重来（第二部分）。§13.4 收敛了第一部分遗留的「失败传播未决」。

> **定稿裁定（2026-11-10，四项，覆盖下文旧表述）**
> 1. **完成态传播 = 同步传播 + 第 23 个定时对账兜底**；**不新建生命周期 outbox**（§18.6 旧第 2/4/5 条作废）。
> 2. **派生会话**：草案期派生**不建会话**，直接建 `Task Y` 并触发拆解；执行期重构**建 ACTIVE 需求会话**（`source_sub_task_id` 记来源）。
> 3. **archetype**：`Task Y` **继承父任务**的 `task_archetype`，仅允许更严（深度分档取 `min(父档, 本档)`），不重新判定。
> 4. **文档结构**：ADR 为唯一设计源，计划只保留批次/状态/验收；暂存区设计的有效内容并入 §19 后删除原文件。

## 1. 背景：当前「同计划内树展开」的三个成本

当前设计把「细化」实现为**同一 Task 内的子级草案**：`A2` 展开 ⇒ 建 `A2a/A2b/A2c`（`code` 段数 +1）+ 软删 `A2` + 继承上游依赖 + 回门禁，全在一个事务里。

三个成本都是结构性的，不是实现质量问题：

| # | 成本 | 证据 |
|---|---|---|
| 1 | **展开是改造类操作**：LLM 在事务内等待，失败整笔回滚 | `PlannerDecomposeAsyncServiceImpl#expandById` 整方法 `@Transactional` |
| 2 | **父行软删 ⇒ 下游 `depends_on` 悬空**，只能靠 `referencingDrafts` 硬拒展开；而设计 §9.2/§9.3 明确要求删掉它 | `PlannerDecomposeAsyncServiceImpl:512-516` 仍在；设计 §9.3 #4「删除该限制」未执行 |
| 3 | **确认时要做 code→id 扁平化**（`PlanConfirmFlattener`），为「多层树」付的成本 | 该类从未创建，因为扁平化本身没实现 |

成本 2 的根因是双写期两套身份并存：`code` 想当位置身份，`depends_on` 仍是真实 id，删行必悬空。**只要「细化 = 删父行」，这条矛盾就无解。**

## 2. 决策

把「细化某个子任务」实现为**派生一个独立 Task**，而不是同一 Task 内的子级草案。

```
Task X (depth 0)          A1 / A2 / A3
   └── A2 派生 → Task Y (depth 1, parent_sub_task_id = A2)
                    └── A2a / A2b / A2c（Y 自己的草案区）
```

规则：

1. **`A2` 从不删除**，只置 `AWAITING_DERIVED`，`Y` 整体完成时置 `DONE`
2. **每层 Task 完全独立**：自己的草案暂存区、自己的确认动作、自己的执行链、自己的整合报告
3. **细化动作 = 纯新增**：建 Task + 建会话 + 触发拆解，与「用户手工创建任务」同构
4. **层级由 `task.depth` 表达**（根 = 0，派生 +1），不再从 `code` 段数推导；上限**全局 ≤2**（= 最多 3 级：根 + 2 层派生，对应用户「层级不超过 3 级」）。`MaxExpandDepth` 的 `BUILD` 档随之由 `3` 降为 `2`（`DOCUMENT` / `RESEARCH` / `DATA` / `OPERATE` 已是 `2`，`DECIDE` 保持 `1` 的更严档）；`application.yml` 的 `build: 2` 已于 2026-11-10（`AD-6`）生效。

## 3. 数据模型（纯增量，不动现有列）

| 列 | 类型 | 说明 |
|---|---|---|
| `task.parent_sub_task_id` | `BIGINT NULL` | 执行期派生的父 SubTask（软引用，无 FK，与 `requirement_conversation.task_id` 同风格） |
| `task.parent_task_id` | `BIGINT NULL` | 草案期派生的父 Task：`Y` 在 `X` 确认前就要落库，此时 `A2` 还不存在 |
| `task.parent_draft_code` | `VARCHAR(64) NULL` | 草案期派生的父草案 `code`。`X` 确认（`A2` 物化）时同一事务回填 `parent_sub_task_id` 并置本列为 `NULL` |
| `task.root_task_id` | `BIGINT NOT NULL` | 根链 id。**根任务 = 自身 `id`**（迁移回填 `SET root_task_id = id`），派生 = 父的 `root_task_id`。不用 `NULL` 表示根，避免全库查询都要 `COALESCE` |
| `task.depth` | `INT NOT NULL DEFAULT 0` | 根 = 0，派生 +1；`CHECK (depth BETWEEN 0 AND 2)`（全局 ≤2 层） |
| `task.derivation_reason` | `VARCHAR(16) NULL` | `PLAN`（草案期）/ `RESTRUCTURE`（执行期） |
| `task.derivation_round` | `INT NULL` | 同一父的第几次派生，从 1 起；用于区分「合法重复派生」与「重复派生 = bug」 |

索引与约束（**本节是唯一版本**；原 §13.3 另写了一条无条件索引，已按本节收敛）：

```sql
-- 活跃派生唯一：废除/取消过的允许再次派生
CREATE UNIQUE INDEX uk_task_parent_sub_task_active
  ON task (parent_sub_task_id)
  WHERE parent_sub_task_id IS NOT NULL AND status <> 'CANCELLED' AND deleted = 0;

-- 草案期派生（X 尚未确认，A2 还没有 sub_task id）的活跃唯一
CREATE UNIQUE INDEX uk_task_parent_draft_active
  ON task (parent_task_id, parent_draft_code)
  WHERE parent_sub_task_id IS NULL AND parent_draft_code IS NOT NULL
    AND status <> 'CANCELLED' AND deleted = 0;

CREATE INDEX idx_task_root_task ON task (root_task_id) WHERE deleted = 0;

-- 父锚二选一：执行期用 sub_task id，草案期用 (task id, draft code)
ALTER TABLE task ADD CONSTRAINT chk_task_parent_anchor CHECK (
  NOT (parent_sub_task_id IS NOT NULL AND parent_draft_code IS NOT NULL)
  AND (parent_draft_code IS NULL OR parent_task_id IS NOT NULL)
);
```

**唯一索引的作用是防重复派生**（同一个子任务同时派生两个 Task 会让完成态传播分叉）。用户取消派生后软删 `Y`，条件失效，可再次派生。

**不加 `sub_task.derived_task_id`**：同一事实两处存必分叉（判据 4），反查 `parent_sub_task_id` 即可。

## 4. 等待态：新增 `AWAITING_DERIVED`

### 为什么不能复用既有状态（已核）

| 候选 | 冲突 | 证据 |
|---|---|---|
| `BLOCKED` | 有「停滞超阈值自动重派」机制，会把等待派生误判成 agent 卡住 | `SubTaskMapper:260-283` |
| `PAUSED` | 有「离线 Agent 名下在飞宽限后改派」机制，同上 | `SubTaskDispatchService:203-220`（`AgentHealthCheckTask` 消费） |
| `IN_PROGRESS` | 被心跳/超时回收盯上，且会让用户在「执行中」看到一条不会动的任务 | 超时回收按时长判定 |

### 状态机（全表在 §11.5，本节只列派生相关边）

```
PENDING_PLAN_REVIEW             --草案期派生-->  AWAITING_DERIVED   ← 旧稿漏这条，草案期唯一入口
BLOCKED/DEAD_LETTER/REWORK/PAUSED --人工接管-->  RESTRUCTURING --派生--> AWAITING_DERIVED（执行期）
AWAITING_DERIVED                --Y 完成------>  DONE
AWAITING_DERIVED                --Y 失败/废除-->  RESTRUCTURING      ← §13.4 收敛
AWAITING_DERIVED                --人工取消---->  CANCELLED
```

改动面（六处，缺一即回归）：

1. `SubTaskStatus` 枚举加 `AWAITING_DERIVED` / `RESTRUCTURING`
2. `chk_sub_task_status` CHECK 约束（新迁移；该约束在 `V26` 建，必须新迁移重建）
3. `SubTaskStateMachine` 转换表（含 §11.5 全部增补边）
4. 前端状态映射与文案（「等待子任务完成」/「重构中」）
5. 所有 `switch (status)` 穷举点：`SubTaskStateMachineServiceImpl#externalExitStatus` / `SubTaskNotificationServiceImpl#sendInboxNotification` / `AgentOutboxServiceImpl#resolveRoutingKey` / `EventReconciliationServiceImpl` 的状态表
6. 统计与看板口径：`SubTaskMapper.xml` 的 `pending_manual` 口径要把 `RESTRUCTURING` 计入（它是等人处理的）

**`tryCloseTask` 的收尾判据不变**：它只把 `DONE`/`CANCELLED` 视为收尾，`AWAITING_DERIVED` / `RESTRUCTURING` 天然挡住父任务提前完成——这正是想要的。**但要在 CAS 成功后新增一处传播动作**（`Y` 完成时置父 SubTask `DONE`），实现见 §5；旧稿「`tryCloseTask` 一行不改」的说法作废。

## 5. 完成态传播（**同步传播 + 定时对账兜底，不建生命周期 outbox**）

复用既有链路，新增一个同步动作 + 一个对账任务：

```
Y 的最后一条子任务 DONE
  → SubTaskCompletionListener.tryCloseTask(Y)
      CAS Y → DONE                                  [已存在]
      ├─ 同步传播（同一方法内，紧跟 CAS 成功分支）：
      │    parent = Y.parent_sub_task_id            [草案期派生的在 X 确认时已回填]
      │    若非空：changeStatus(parent, DONE)
      │      → 走状态机 → 触发既有 listener → tryCloseTask(X)   [复用]
      └─ 写 timeline + publishEvent(TaskAutoCompletedEvent(Y))  [已存在]
```

兜底：**第 23 个定时任务 `TaskLifecycleReconcileTask`**（§18.6），每轮三项对账，全部幂等：

| # | 检查 | 修正 |
|---|---|---|
| 1 | 任务非终态，但其全部子任务已终态 | 重放 `tryCloseTask(taskId)` |
| 2 | `Y.status ∈ {DONE, CANCELLED}` 且 `parent_sub_task_id` 非空，但父 SubTask 仍为 `AWAITING_DERIVED` | 重放传播：`DONE` 且有 `DONE` 子任务 ⇒ 父 `DONE`；`DONE` 但零产出或 `CANCELLED` ⇒ 父 `RESTRUCTURING`（§13.4 失败收敛） |
| 3 | `task.status = DONE` 且 `final_report` 为空 | 触发 `TaskFinalReportService.generate`（取代「只能人工重生成」） |

关键性质：

- **传播是同步的**：CAS 成功后立刻改父，少一次进程崩溃窗口；定时任务只负责补历史悬挂，不承担主路径
- **幂等**：`changeStatus` 走状态机 + CAS（`AWAITING_DERIVED → DONE` 只能成功一次），重复传播无副作用
- **不新增事件表**：`TaskAutoCompletedEvent` 仍是唯一广播点；报告生成仍是 `@Async` 监听，失败由对账任务兜底
- **草案期派生在 `X` 确认前 `A2` 不存在**：`Y` 先完成后，`X` 确认物化 `A2` 时直接按 `Y.status` 落 `DONE`（不回 `AWAITING_DERIVED`），避免「等待一个已经完成的任务」

## 6. 失败传播（**已由 §13.4 收敛**——`A2 → RESTRUCTURING` 人工接管，不做 `FAILED` 冒泡）

`Y` 失败 / 被整体废除时，`A2` 停在 `AWAITING_DERIVED` ⇒ `X` 永久卡死。这与 `TD-16f` 的栅栏问题同型，但跨了 Task。

| 选项 | 语义 | 代价 |
|---|---|---|
| A. `A2 → FAILED` 并向上冒泡 | 最直接；一个子任务失败拖垮整链 | 需要 `FAILED` 语义与重试边界（当前 `SubTaskStatus` 无 `FAILED`，只有 `DEAD_LETTER`） |
| B. `A2 → BLOCKED`，允许重新派生 | 保留可恢复性 | 与 `BLOCKED` 的自动重派机制冲突，需要先给该机制加排除条件 |
| C. `A2` 回退 `PENDING`，允许再拆 | 用户可改子任务后重新派生 | 需处理「已产生的 Y 部分产物」 |

**原为「暂不定」，已由 §13.4 收敛**：失败不冒泡成 `FAILED`，而是把 `A2` 打回 `RESTRUCTURING`（人工接管），由人决定「再拆一次 / 手改后重跑 / 放弃」。理由见 §13.4。

## 7. 禁止项

1. **跨层依赖禁止**：`A3` 只能依赖到 `A2` 这一级，不能直接依赖 `Y` 内的 `A2a`。一旦允许，就退回多层图，本 ADR 的全部收益消失。
2. **`A2` 派生后其四要素冻结**：`Y` 执行期间不允许改 `A2` 的 title/content/acceptance，否则 `Y` 的目标与父不一致。
3. **`Y` 不能再派生回 `X` 的兄弟**：派生链是树，不允许交叉引用。

## 8. 对现有实现的影响

### 保留（不动）

`task_plan_draft` 暂存区 + 镜像双写（每层各有一份）、`draft_code` 的**单段**身份、规则门 + 语义门（作用域从「整棵树」变为「单层」，判据不变）、撞键完整性判定、确认原子化 `confirmDrafts`、`task_plan_attempt` 五态栅栏、深度上限 `MaxExpandDepth`（判据输入从 `code` 段数改为 `task.depth`，在派生时判）

### 废弃

| 对象 | 原因 |
|---|---|
| `draft_code` 多段语义、`DraftCode.child()`、`resolveParentDraftCode` | 每层独立编号，不存在子层 code |
| `expandById` / `expandDraftByCode` / `planner-expand.md` 展开路径 | 改为「派生 Task」 |
| `referencingDrafts` | 父行不删，引用永不悬空 |
| `task_plan_draft.parent_code` | 无跨层父子，恒 `NULL`（列保留到减法批） |
| `task_plan_draft.depth` | 恒 `0`，深度改为 `task.depth`；旧稿 §8 漏列此项 |
| `G3_DEPTH_LIMIT` 的 draft 口径 / `G3_ORPHAN` | 判据搬到派生入口（按 `task.depth`），无跨层 parent_code 可校验 |
| `sub_task_abandon_fence`（`TD-16f`） | 执行期局部整改/派生已提供入口，「整批废除」不再是唯一出路；`TD-16f` 整片作废 |

### 不必做

`PlanConfirmFlattener`（确认时扁平化）、`G3_ORPHAN`（`e-3-1b`）、`TD-16f` 废除栅栏——三者都是为多层树或「无局部入口」付出的成本，本模型下不需要。

## 9. 后果

**正面**

- 消灭三个结构性问题：展开长事务、依赖悬空、确认扁平化
- 深度控制从 code 段数改 `task.depth`，可靠且可索引
- 「细化」变成纯新增，失败不需要回滚，LLM 不必进事务
- 每层复用同一套 Task 流水线，概念数量减少

**负面（如实登记）**

- 新增三个问题：完成态传播（有解，见 §5）、失败传播（未决，见 §6）、可见性碎片化（一个任务变 N 个 Task，需「已派生」入口与进度展示）
- 任务列表会膨胀，看板需要按 `root_task_id` 聚合
- 新增一个状态值，改动面覆盖枚举/约束/状态机/前端/统计五处

## 10. 已决与剩余未决（2026-11-10 收敛）

| # | 问题 | 结论 | 落点 |
|---|---|---|---|
| 1 | 失败传播 | 已由 §13.4 收敛：`AWAITING_DERIVED → RESTRUCTURING`，不做 `FAILED` 冒泡 | §13.4 |
| 2 | 派生时是否建 `RequirementConversation` | **草案期不建**（直接建 `Y` 并触发拆解）；**执行期建 ACTIVE 会话**，记 `source_sub_task_id` | §13.1、§19.6 |
| 3 | 能否对已派生的 `A2` 追加需求 | **不允许直接改**（§7.2 冻结四要素）；要改只能走执行期重构或废除 `Y` 后重新派生 | §7、§11 |
| 4 | `Y` 的 archetype | **继承 `X`**；只允许更严（`maxDepth = min(父档, 本档)`），不重新判定 | §19.7 |
| 5 | 报告回挂 | 采用 §15.2 方案 C（物化附件），但**必须先开平台写入入口** | §15.4 |

**仍未决**（有明确默认，不阻塞开工）：

1. `DONE` 是否允许重构：维持 §11.2 保守口径（不允许）。
2. `RESTRUCTURING` 不设自动超时，但必须进「待我处理」列表（§17.3）。
3. ~~`G5` 模型调用预算（§17.6）必须在 `G5` 实现前定。~~ **已定（2026-11-10）：确定性 + LLM 语义，语义只 `warn`、30s 有界、超时降级留痕。**
4. 派生入口的权限点：草案页复用 `task:plan`，执行页新增 `subtask:restructure`（§19.8）。

---

# 第二部分：执行期重构（同一原语的第二个触发点）

> §2~§10 讲的是**草案期主动细化**。本部分讲**执行期出问题后的补救**。两者共用同一个原语（派生一个独立 `Task`），区别只在触发时机与去向。
> 合并进同一份 ADR 而不是另开 ADR-009：它们是同一个模型的两面，分开会让「子任务派生」这件事散成两处，读者必须同时读两份才能判断某条结论是否仍成立。

## 11. 执行期重构：触发条件与入口状态

### 11.1 问题

草案确认后，子任务进入自动派发链。一旦 agent 反复报阻塞、被自动核验多次驳回、或被重分配熔断打入死信，**当前只有三条出路**：

| 现状出路 | 局限 |
|---|---|
| `redispatchDeadLetter` 人工指定 agent 重派 | 只能换人，任务要求本身有问题时换谁都会再失败 |
| `blockById` / `pauseById` | 只是停住，不解决 |
| 整批重拆（`rejectPlan` 回 `PENDING` 再拆整个任务） | 颗粒度太粗，已完成的兄弟子任务全部作废 |

**缺的是「只改这一个」的粒度。** 而这正是 §2 那个原语能提供的。

### 11.2 触发闸门（两条都满足才允许）

| # | 条件 | 依据 |
|---|---|---|
| 1 | 当前状态 ∈ `{BLOCKED, DEAD_LETTER, REWORK, PAUSED}` | 这四个状态都代表「自动链路已停 / 已交人工」，见 §11.3 的排除表 |
| 2 | 该子任务的产出**尚未被下游消费** | 一旦下游已开始执行并读取过它的产出，改要求会让下游基于旧产出去做新要求，必然返工 |

**条件 2 的判定口径需要你拍板**（原始诉求里两句话范围不同）：

- 「不能是审核通过、下游任务使用这个任务产出结果了」⇒ 允许「`DONE` 但下游尚未开始」
- 「只要这个子任务没有完成之前」⇒ `DONE` 一律不可

**本文按后一句（保守）设计**，三条理由：

1. `DONE` 在状态机里是**终态**（`SubTaskStateMachine:32` — `TRANSITIONS.put(DONE, Set.of())`），允许它出去要开终态可变的口子，后续所有「终态不可变」的推断全部失效
2. 「下游是否已消费」当前**无法判定** —— 没有「谁读过我的产出」的记录。`buildDependencySection` 每次装配上下文都会读，但没有落痕。要做就得先加消费埋点，成本与本片不成比例
3. 用户能感知到的场景（反复阻塞 / 多次驳回 / 死信 / 暂停）全部落在 `DONE` 之外，保守口径不损失实际能力

若你要求「`DONE` 但下游未开始」也允许，则必须同时解决上面第 1、2 条，建议单独立项。

### 11.3 `RESTRUCTURING` 必须新增——四个候选逐一排除

| 候选状态 | 排除原因 | 证据 |
|---|---|---|
| `BLOCKED` | 有「停滞超阈值自动重派」，会把重构决策期误判成 agent 卡住并强行拉回执行链 | `SubTaskMapper:260-285` `selectStaleBlockedSubTaskIds` + `SubTaskBlockedAutoRedispatchTask` |
| `PAUSED` | 有「离线 Agent 名下在飞宽限后改派」，同样会抢跑 | `SubTaskDispatchServiceImpl:203-220` + `AgentHealthCheckTask` 消费 |
| `REWORK` | 计入在飞并发占用，且 `claimSubTask`/`startIfNeeded` 会把它推进 `IN_PROGRESS`，与「等人改要求」直接冲突 | `ConcurrencyQuotaService:10` 占用口径；`McpTaskClaimServiceImpl:326,366` |
| `DEAD_LETTER` | 出边只有 `ASSIGNED / CANCELLED / DONE / REWORK`，**没有回 `PENDING` 的路**；整改完无法回到执行链 | `SubTaskStateMachine:37` |

结论：**新增 `RESTRUCTURING`**，语义「人工接管中，自动链路已停」。它与 §4 的 `AWAITING_DERIVED` 是两个状态，不合并：

- `RESTRUCTURING` = 人在改 / 人在决定，出边由**人的动作**驱动
- `AWAITING_DERIVED` = 人已经决定派生，系统在等派生任务整体完成，出边由**派生任务的完成事件**驱动

合并会让「派生完成后置 DONE」不得不靠 `derived_task_id != null` 反推语义，查询与展示都会别扭，且两者的超时策略不同（前者可无限等，后者需要失败收敛，见 §13.4）。

### 11.4 「不被定时任务轮询」自动成立

现有轮询候选只有三类（全部实测）：
- `PENDING` 孤儿（`selectStalePendingWithoutExecutionRecord` / `selectPendingUnassignedWithoutActiveExecutionRecord`）
- `ASSIGNED` 超时（`selectTimedOutAssigned`）
- `BLOCKED` 停滞（`selectStaleBlockedSubTaskIds`）

`RESTRUCTURING` 不在任何一类里 ⇒ **不需要为它写排除条件**。这是「新状态」比「复用旧状态」省的唯一一处，但省的正是最容易漏的一处（复用时每加一个定时任务就要记得排除一次）。

下游全部等待也**不需要新机制**：派发前已有依赖就绪检查，未就绪保持 `PENDING`：

```
SubTaskDispatchServiceImpl:244-249  「离线子任务依赖未就绪，保持 PENDING 等待依赖完成」
SubTaskDispatchServiceImpl:300-306  「子任务依赖未就绪，跳过分发（保持 PENDING）」
```

只要 `A2` 不在 `DONE`，`A3` 的 `dependsOn` 就不满足 ⇒ 自动停在 `PENDING`。这一条是白捡的。

### 11.5 状态机增补（全表）

```
新增入边：
  PENDING_PLAN_REVIEW → AWAITING_DERIVED   草案期派生（§13.2）
  BLOCKED       → RESTRUCTURING   人工接管
  DEAD_LETTER   → RESTRUCTURING   人工接管
  REWORK        → RESTRUCTURING   人工接管
  PAUSED        → RESTRUCTURING   人工接管
  AWAITING_DERIVED → RESTRUCTURING  派生失败收敛（§13.4）

新增出边：
  RESTRUCTURING → PENDING            去向 A：整改完成，回执行链
  RESTRUCTURING → AWAITING_DERIVED   去向 B：派生主任务
  RESTRUCTURING → CANCELLED          放弃
```

`DONE` / `CANCELLED` 仍是终态，不加任何出边。

## 12. 去向 A：原地整改与双向校验门

### 12.1 一个现状缺口：执行期没有编辑入口

现有唯一的子任务内容编辑入口是 `updateDraftById`（`SubTaskController:268-273`），文档注释明确 **「仅 PENDING_PLAN_REVIEW 状态可编辑」**。

也就是说：**执行期的子任务，现在根本无法改要求**。去向 A 不是「改一下校验」，而是**新建一条编辑通道**。

### 12.2 双向校验门 `G5_RUNTIME_CONTRACT_BREAK`

你的原话是「人工修改的子任务，需要审核，就是前置任务和后置依赖的校验审核」。这条判据现有代码里**完全没有**，因为原来不存在「运行期改需求」这个动作——草案门只在确认前跑一次。

| 方向 | 判什么 | 可复用性 |
|---|---|---|
| **上游向** | 新 `acceptance` 要求的事实，是否被上游**已产出的内容**或上游 `constraints` 排除 | 判据可复用 `G4_UNSATISFIABLE_CONTRACT` 的语义，但**作用域变了**：从「批内草案互检」变成「本条 vs 上游已完成产出」。需要把已完成产出也喂进模型 |
| **下游向** | 下游 `A3` 的 `content` / `acceptance` 是否引用了本条的某个具体产出（字段名 / 章节 / 文件 / 格式） | **全新**。确定性部分可做（文本包含匹配），语义部分必须过模型 |

处置（**2026-11-10 口径已定**）：

| 层 | 判据 | 处置 |
|---|---|---|
| 确定性 | 下游显式引用了整改前的产出标识，而新要求不再包含它（反引号/引号片段、带大写/数字/点的 ASCII 标识） | `block`，消息指明「哪条下游、哪个标识」 |
| 语义 | 一次有界 LLM 判断（30s）：删除了下游依赖的产出、改变交付形态、与上游已产出/约束矛盾 | 只 `warn`（决策 17「LLM 结论永无 block 权」）；超时/失败降级为「语义门未执行」warn 并留痕 |

去向：`RESTRUCTURING → CANCELLED`（放弃本次整改）或改回去继续在 `RESTRUCTURING` 里改。

**为什么必须做这一道门**（你的原话已经点到）：「不能你写的要求和限制，会使得后面的任务执行逻辑相悖，无法执行可不行」。这是同一个失效模式的第二个实例——**草案期的 `G4` 只管批内，管不到运行期**。

### 12.3 通过后

`RESTRUCTURING → PENDING`，走原派发链（定时轮询 / 手动认领均可，无需改派发逻辑）。

两个必须写死的口径：

1. **`reworkCount` 不清零**。它是「被驳回次数」的事实记录，改需求不等于驳回被撤销。另记 `context.restructureRound = n`（第几次重构）供归因
2. **改动必须留快照**。`context.restructureHistory[]` 存每次改动前的 `{title, content, deliverable, acceptance, constraints, editedAt, editorId}`，否则「为什么这次跑出来和上次不一样」永远查不清

## 13. 去向 B：派生主任务

### 13.1 流程

```
A2 (RESTRUCTURING)
  → 用户点「拆出去当主任务」
  → 新建 RequirementConversation（`task_id = X`，`source_sub_task_id = A2.id`，见 §19.6）
  → 把 A2 的要求 + 上游已完成产出 + 下游依赖情况 装配进上下文（§16）
  → 用户与 Planner 讨论 → 产出草案
  → 草案确认（走既有 confirmDrafts + 门禁）
  → 建 Y = Task{parent_sub_task_id: A2.id, root_task_id: X.root_task_id, depth: X.depth+1}
  → A2 → AWAITING_DERIVED（复用 §4 的状态，不新增）
  → Y 整体完成 → A2 → DONE（§5 完成态传播）
```

关键：**`A2` 全程不删、不取消、四要素不改**。这是 §2 相对旧方案的核心收益——引用永不悬空，`referencingDrafts` 可以真删。

### 13.2 与草案期派生的唯一差别

| 维度 | 草案期派生（§2） | 执行期派生（本部分） |
|---|---|---|
| 入口 | 草案审核页对某条 `ACTIVE` 草案点「拆出去」 | 执行页对 `RESTRUCTURING` 子任务点「拆出去」 |
| 父 SubTask 状态 | `PENDING_PLAN_REVIEW`（`X` 确认前 `A2` 还不存在） | `RESTRUCTURING`（`A2` 已存在） |
| **`Y` 何时落库** | **点「拆出去」立即建 `Y`**，父锚 = `(parent_task_id=X.id, parent_draft_code=A2.code)`，随后触发 `Y` 拆解 | 讨论定稿并确认草案后建 `Y`，父锚 = `parent_sub_task_id=A2.id` |
| 会话 | **不建**：草案四要素已经确定，直接拆解 | **建 ACTIVE 需求会话**（§16 四段上下文） |
| 子任务要求从哪来 | 草案四要素（尚未执行过） | 原要求 + **失败/驳回的 review 评语 + 阻塞原因**（§16 的关键增量） |
| 上下文装配 | 不存在的「上游产出」 | 上游产出已物化，必须注入 |
| 父 `A2` 物化时机 | `X` 确认时创建 `A2`（`AWAITING_DERIVED`，若 `Y` 已 `DONE` 则直接 `DONE`），并回填 `Y.parent_sub_task_id` | 已在 |

第五行是本质差别：**执行期派生时，上游已经有真实产出**，这是「二次拆分不跑偏」最重要的材料。

### 13.3 允许重复重构

`A2` 可以重构多次（第一次派生的 `Y` 失败了，再派一个 `Z`）。唯一索引因此必须是**条件索引**——无条件索引会挡住第二次合法派生。条件索引的唯一定义在 §3（`uk_task_parent_sub_task_active` / `uk_task_parent_draft_active`），旧稿此处另写的版本已删除。

重复派生时：

1. `derivation_round = 该父已有派生行数的最大值 + 1`
2. `derivation_reason` 区分 `PLAN` / `RESTRUCTURE`
3. 旧 `Y` 必须先 `CANCELLED`（或软删），否则条件索引仍会挡住
4. 新 `Y.root_task_id` / `depth` 与旧 `Y` 同源（父不变，层级不变）

### 13.4 失败传播收敛（原 §6 的答案）

`Y` 失败 / 被整体废除时，`A2` 不做 `FAILED` 冒泡，而是 **`AWAITING_DERIVED → RESTRUCTURING`**：

| 方案 | 你的场景下是否合适 |
|---|---|
| `FAILED` 冒泡 | ✗ 一个子任务失败拖垮整条链，且 `SubTaskStatus` 无 `FAILED`，要新增 |
| `BLOCKED` | ✗ 有停滞自动重派会抢跑（§11.3） |
| 回退 `PENDING` | ✗ 直接回执行链等于「什么都没改就重跑」，必然再失败 |
| **`RESTRUCTURING`** | ✓ 精准表达「需要人再看一眼」，且人在这个状态下可以再拆 / 手改 / 放弃 |

**这个收敛是自然推论，不是新设计**：你说了「这几个状态都可以对这个任务重构」，那么派生失败就该回到那个可以做重构的状态。`A2` 永远不会「卡死在等待」——它总能回到一个人能介入的地方。

### 13.5 派生链的 git 拓扑（2026-11-24 增补）

> **本节回答**：派生 Task 在 git 上如何表达。**结论先行：派生关系不映射为「分支的嵌套」，而映射为「提交的父子」**——分支名平级，层级只在 merge DAG 里。

**① 为什么不能「在父分支下建子分支」**（[实测] 原生 git 2.55.0）

| # | 操作 | 结果 |
|---|---|---|
| 1 | `git branch helloai/task-1` → `git branch helloai/task-1/sub` | **RC=128** `cannot lock ref '.../task-1/sub': 'refs/heads/helloai/task-1' exists` |
| 2 | `git branch helloai/task-1` → `git branch helloai/task-1.d1-22` | **RC=0**，共存 |
| 3 | 先建 `helloai/t9/main` + `helloai/t9/d1-22` → 再建 `helloai/t9/d1-22/s1` / 试建 `helloai/t9` | 均 **RC=128** |

git ref 存储是一层目录树：任一中间段一旦被用作分支名，其下**永不能再建子段**（D/F 冲突），反向同理。⇒ 用 `/` 做层级既不可行，也会让分支名长度随派生深度增长。

**② 定案：扁平命名，每个 Task 一条分支**

```text
helloai/t{taskId}
```

`task.id` 全局唯一 ⇒ 派生 `Task Y` 天然拿到 `helloai/t{Y}`，与 `helloai/t{X}` **平级共存**，**不需要把 `depth` 编进分支名**。3 层派生（`depth=2` 上限，`V148__task_derivation.sql:55`）与 1 层派生的分支名形态完全一致。

**③ 层级只体现在 merge DAG，合并方向唯一**

```text
master
  └─ helloai/t{X} ──●──●──────●(merge Y)────────●
                       \      /                  \
                        ●────● (A1 子任务 commit)  \
                                                   \
     helloai/t{Y} ──●────●──────●(merge Z)
                        \     /
                         ●───●  (B1 子任务 commit)
```

- **仅 `depth=0` 根 Task 分支可直连 `master`**，派生分支永不直连 ⇒ 不存在「派生分支与 master 平级、先后不明确」的歧义。
- **禁 squash**：`MergeCommand` 必须真实合并，squash 压掉 merge-base ⇒ 层级不可回溯，回退从「revert 一次 merge」退化成「手工挑文件」。
- **冲突即中止**：`MergeResult` 非 `MERGED`/`FAST_FORWARD` ⇒ 该子任务回 `BLOCKED` 带原因。不做自动 rerere——会让「哪次合入引入了错误」不可归因。
- **切点 = 父分支当前 HEAD**；父分支尚未存在时（草案期派生，`X` 未 clone）先按远端基线建出父分支再切，与派生顺序无关、幂等收敛（建分支时机见 ④——由 `confirmPlan` 后异步建，`lazy ensure` 仅兜底）。

**④ 分支创建不得进派生事务，且必须早于任何执行者**

`§18.2` 定「派生 = 同库单事务」（`INSERT task` + `UPDATE sub_task` + 建会话）。git 是外部 IO，混入会让那个单事务失去原子性。

⇒ 分支创建**不得进派生事务**，也**不得内联在 `confirmPlan` 的同步路径上**（clone/push 是网络 IO，会让确认耗时不可控）。正确落点是 **`confirmPlan` 落「待建分支」意图 → 由 job 异步建分支与发布**；`WorkspaceRef` 解析到宿主管时的 lazy ensure **降为兜底**（覆盖草案期派生的 Task 等未走 `confirmPlan` 的路径）。

**为什么不能只靠 lazy ensure**：派生 Task 也可能被派给**外部 agent**，而外部 agent 在客户机器上执行、不从平台工作区取代码——它只按派发载荷的 `repoUrl`/`branchName` 自行 clone。分支在远端尚未存在时它只能自建 ⇒ 分叉。详见 `research` §7.9.4。**派生链上任一 `t{Y}` 分支都必须在其执行者动手之前存在于远端**，这条对派生 Task 与普通 Task 同等适用。

**⑤ 与 §13.3「重复派生」的关系**

`A2` 可派生多次（`Y` 失败再派 `Z`）：每次是**新 Task、新分支**（`helloai/t{Y}` / `helloai/t{Z}`），旧分支按遗弃处理，**绝不在同一分支上继续叠 commit**。

**⑥ 分支写者归属（2026-11-24 晚增补：混合协作下必须单向）**

派生链上的分支**每个 ref 只有一个写者**，这是「同一事实双写必分叉」（§14）在 git 上的直接体现：

| ref | 唯一写者 |
|---|---|
| `master` | 人（IDEA 合并） |
| `helloai/t{taskId}` | **平台**（含本 ADR 的全部派生分支；`t{Y}`/`t{Z}` 亦由平台建与发布） |
| `helloai/t{taskId}-s{subId}-a{n}` | 执行该尝试的 agent 本人 |

**平台侧本地↔远端动作只做「发布」（单向、只推自有 ref、写前必 fetch、禁 force），禁止「同步」（双向写同名 ref）**——实测双向写会静默覆盖提交且 `push` 退出码仍为 0（`research` §7.9.1）。⇒ **派生 Task 分支的创建与发布不得由外部 agent 触发，也不得与远端形成双向写关系**；派生时若父分支尚不存在，由平台在 `confirmPlan` 后异步建出（`GT-7`/`GT-12`），不塞进派生事务（见 ④）。

**⑦ 本节的边界**

只覆盖 **Task 级**拓扑。**子任务级**（同一 `subTask` 被不同 agent 接力、或多 agent 并行）的尝试分支形态另见 `plan/HelloAI git 能力集成实施计划（2026-11-24）.md` 的 `GT-11` 与 `research/HelloAI git 能力集成与外部代码任务调研（2026-11-24）.md` §7.6——**派生（新建 Task）与接力（同 subTask 换 agent）是两个正交维度，不得混为一谈**。发布与工作区前进的完整规则见 `GT-12` 与 `research` §7.9。

## 14. 双向关联：为什么只存单向

你的表述是「父级表存 child_id，后面子表存 parent_id」。**建议只保留子表侧 `task.parent_sub_task_id`**，父表不存 `derived_task_id`。

依据是判据 4「同一事实双写必分叉」：

| 冲突场景 | 单向（子表存父） | 双向 |
|---|---|---|
| 派生任务被废除后重新派生 | 只需改新 `Y` 的 `parent_sub_task_id` | 要同时改 `A2.derived_task_id`，任一处漏改就分叉 |
| 并发创建（重试 / 双写） | 唯一索引直接挡住 | 唯一索引挡的是 `task` 侧，`sub_task` 侧不受约束，会留脏值 |
| 多次重构（§13.3） | 天然支持（多行指向同一父） | `derived_task_id` 是单列，只能存最新一次，历史丢失 |

**反向查询（从父找子）不需要冗余列**：`parent_sub_task_id` 建索引后 `WHERE parent_sub_task_id = ? ORDER BY create_time DESC` 即可，量级是「一个子任务的派生次数」（个位数）。

若你担心「查最新派生」在热点路径上开销大，正确做法是加**派生缓存**或把 `current_derived_task_id` 作为**纯派生投影**（有唯一写入口、有校验表能重建），而不是把它当第二事实源。

## 15. 报告回挂：闭环的最后一环

你的要求：「拆出去的子任务完成后，整体完成后，生成的报告的信息，挂到前面的子任务上，供后面的依赖子任务读取」。

### 15.1 现状机制（已实测）

下游 `A3` 读 `A2` 的产出，走两条路（`AgentRuntimeContextAssembler:856-925`）：

| 路径 | 实现 | 键 |
|---|---|---|
| 摘要 | `findExecutionSummary(taskId, subTaskId)` → `task_execution_record` | **(taskId, subTaskId) 双键** |
| 正文 | `loadUpstreamContent(dep)` → **优先读物化附件**，无附件回落原始产出 | `attachment(subTaskId)` |

而最终报告是 `task.final_report`（`V32__task_final_report.sql`），**按 taskId 存**。

所以断点很具体：`Y.final_report` 在 `Y.id` 下，而 `A3` 查的是 `(X.id, A2.id)` —— **查不到**。

### 15.2 三个方案

| 方案 | 做法 | 改动面 | 问题 |
|---|---|---|---|
| **A. 写一条伪造归属的记录** | 派生完成时写 `(X.id, A2.id)` 的 execution_record，summary = Y 报告摘录 | 一个写入点，读侧零改 | `selectByTaskIdAndSubTaskId` 返回单条 ⇒ **覆盖 `A2` 自己的失败记录**，审计链断（分不清哪条是 A2 的、哪条是 Y 的） |
| **B. 快照加字段 + 装配器分流** | `SubTaskSnapshot` 加 `derivedTaskId`；装配器发现非空则改读 `Y.final_report` | agent 域投影 + 装配器 + 测试 | 需要把 `final_report` 从 task 域投影出来（**注意方向：`agent → task` 是反向依赖**，必须走端口） |
| **C. 物化附件（推荐）** | 派生完成时把 `Y.final_report` 渲染成附件挂到 `A2` 上 | 一个写入点，读侧**零改**（`loadUpstreamContent` 天然附件优先） | 需确认 `AttachmentPort` 是否对外开放写入口；附件有总量/截断上限 |

**推荐 C**，因为它是唯一「复用既有物化机制、且不破坏审计」的方案：`A2` 的原失败记录保留原样，`Y` 的报告作为独立附件存在，`A3` 的读取路径一行不改。

若 C 因写入口受限不可行，退到 B——但必须守住依赖方向：`final_report` 的读取通过 task 域端口暴露，不能让 `agent` 直接读 `task` 的 entity 或 mapper。

### 15.3 一个必须写进契约的细节

`loadUpstreamContent` 是**附件优先**：只要 `A2` 上挂了任何附件，正文路径就读附件、**不再读原始产出**。所以 C 方案挂附件后，`A2` 原有的产出正文会被附件顶掉。

要么挂附件时把「原有产出 + 派生报告」一起渲染进去，要么在附件里显式声明「本任务已派生，产出以下游任务报告为准」。**不写清就会出现「衍生后上下游看到的内容凭空换了一套」**。

### 15.4 方案 C 的两个前置（已核实，必须先补）

1. **平台写入入口当前不存在**：`AttachmentServiceImpl#register` 强制 `agentId.equals(subTask.getAssignedAgentId())`（`:80-89`），平台侧无法以「系统」身份挂附件。必须先新增平台写入方法（系统主体，跳过 agent 归属校验但保留审计字段），或把 `register` 拆成「归属校验」与「写入」两段复用。
2. **附件优先读取会顶掉 `A2` 原产出**（§15.3）。因此挂载内容必须是「`A2` 原有产出 + `Y.final_report`」的合并渲染，附件正文首行声明来源与优先级；**不得只挂报告**。
3. 渲染受附件总量/截断上限约束：超限时保留报告摘要 + 指向 `Y` 任务的链接，不得静默截掉结论段。

## 16. 派生会话的上下文装配契约

你强调「这个上下文很重要，能确保这个子任务二次拆分不会跑偏」。这条不能靠 prompt 自由发挥，必须写成**装配契约**（否则换个模型就散）。

新会话必须注入四段，缺一不可：

| 段 | 内容 | 来源 |
|---|---|---|
| ① 本子任务原要求 | title / content / deliverable / acceptance / constraints | `sub_task` |
| ② **失败/阻塞轨迹** | review 评语逐条、`reworkCount`、`attemptTotal`、阻塞原因、死信原因 | `review_record` + `agent_event` + `context.blockReason` |
| ③ 上游已完成产出 | 直接前置的产出摘要 + 正文（复用 `buildDependencySection`） | 既有装配器 |
| ④ **下游依赖期待** | 谁依赖它、下游的 `content`/`acceptance` 原文 | `sub_task WHERE depends_on @> [A2.id]` |

**②和④是这次派生的独有价值**：

- 没有 ②，新拆出来的方案会**重复踩同一个坑**（旧要求为什么失败，是这次拆分唯一要解决的问题）
- 没有 ④，拆出来的东西虽然自己跑通了，却**满足不了下游**——正是你在去向 A 里担心的「后面的任务执行逻辑相悖」

一段都不许省。这四段加起来才构成「为什么必须重拆」。

## 17. 增补后的未决项

原 §10 四项保留，新增：

1. **`DONE` 是否允许重构**（§11.2 条件 2 的两种口径，本文取保守）
2. **报告回挂走 A / B / C 哪个**（§15.2，推荐 C，需先验 `AttachmentPort` 写入口）
3. **`RESTRUCTURING` 是否需要超时**：人可能忘了处理，任务永久停在该状态。建议**不设自动超时**（自动转走会违背「人工接管」本意），但要进「待我处理」列表 + 看板告警
4. **`G5` 的确定性部分做到什么程度**：文本包含匹配（下游 `acceptance` 里出现本任务产出的字段名）误报率未知，需实测样本
5. **派生会话由谁发起**：用户在草案页点，还是在执行页点？（影响前端入口与权限点）
6. **`G5` 的模型调用预算**：`LOG-20261109` 实测语义门 30s 有界等待**余量为零**（5 个样本里 2 个触顶降级）。`G5` 是运行期门，触发频率比确认口高得多，且用户正在点「重构」时等 30s 比确认页等 30s 更难接受。方向有两个：`G5` 只做确定性部分（文本匹配），语义部分异步补判；或改「分批判」把单次调用压小。**实现前必须定，否则 `G5` 会把已知的 30s 问题带进更高频的路径。**
   **2026-11-10 定稿**：选「确定性 + LLM 语义」——确定性层先跑，已阻断则不调模型；语义层 30s 有界、超时降级 `warn` 留痕、结论只 `warn`（决策 17）。实测：确定性阻断在真机 400ms 内返回且证据可操作；语义层尚未在真机触发（样本无材料时直接跳过）。

## 18. 一致性契约：outbox 用在哪、TCC 与 Saga 用不用

本节回答「派生模型下既有的一致性设施是否还成立」。结论先行：**outbox 是硬前提，TCC 不需要，Saga 只需要动作表不需要编排器**。

### 18.1 先纠正一个事实前提

生产代码检索（`helloai-core` / `helloai-common` / `helloai-api`）：

| 关键词 | 命中文件数 | 结论 |
|---|---|---|
| `outbox` / `Outbox` | 28 / 37 | 真实存在，三套 |
| `TCC` | **0** | **从未实现** |
| `Saga` / `saga` | **0** | **从未实现** |

`scripts/**/verify-tcc-chain.ps1` 亦不存在（`data/artifacts/` 下两份同名 markdown 是 AI 生成的**任务说明书**，不是产物）。所以本节的题目不是「旧的还用不用得上」，而是「**要不要把缺的补齐**」。

实际存在的一致性设施：

| 设施 | 位置 | 职责 |
|---|---|---|
| `agent_command_outbox` | `V19` / `V20` | 命令投递：`PENDING(0) → SENT(1) → CONFIRMED(3)` / `FAILED(2)`，带 publisher confirm、`confirm-timeout`、退避重试、CAS 迁移 |
| `agent_outbox_event` | `V19` | 领域事件投递：`AgentEventCompensationTask`（15s）兜底重发 |
| `task_plan_attempt` | `V146` | 拆解操作栅栏：`batch_key + attempt_no` 唯一索引防悬挂 / 防空回滚，五态结论 |
| `ExecutionCompensationTask` | `helloai-job` | 执行记录 PENDING / RUNNING 超时 → 判超时改派（**超时协调，非业务反向补偿**） |

**注意 `agent_command_outbox` 的 `SENT → CONFIRMED` 看着像 TCC，但它是消息投递的两阶段确认（publisher confirm），不是业务事务的 Try/Confirm/Cancel。名字像，语义不同——不要据此认为「TCC 已存在」。**

### 18.2 四类动作的事务边界（不要一律上 outbox）

| 动作 | 边界 | 需要什么 |
|---|---|---|
| 派生（`INSERT task` + `UPDATE sub_task(A2)` + 建会话） | **同库单事务** | 本地事务足够。任何一步失败整笔回滚，无需补偿 |
| 原地整改回 `PENDING`（§12） | 单表 CAS | 本地事务 |
| **`Y` 完成 → `A2 → DONE` → `X` 收尾**（§5） | **跨 Task、跨进程、跨异步** | **必须 outbox** |
| **报告回挂**（§15） | 跨 Task + 对象存储 | **必须 outbox**（或对账兜底） |

判据：**状态变更是事实，事件是它的投影；「改一次」用 CAS，「一定送出去」只能靠 outbox。二者不可互相替代。**

### 18.3 完成态传播：同步 + 对账，**不上 outbox**（2026-11-10 定稿裁定）

现状缺口（证据保留）：`SubTaskCompletionListener#tryCloseTask` 收尾三步**没有事务包裹**（注释自认「此处已无事务上下文」，`:258`）：

```
CAS 改 task → DONE            [250-253]
写 TASK_AUTO_COMPLETED timeline [255-256]
publishEvent(...)             [260]
```

第 1 步成功、第 3 步失败（进程崩溃 / 异步线程池拒绝）即得到「task 已 DONE 但报告永不生成」，且：

- `TaskFinalReportService#onTaskAutoCompleted` 是 `@Async("appEventExecutor") + @EventListener`，`catch (Exception) { log.warn }`——**无重试**，注释写明「可手动重新生成兜底」（`:96`、`:122-123`）
- 22 个定时任务里**没有**「所有子任务均终态但 task 非终态」的对账；`EventReconciliationTask` 只比「子任务事件流末条 vs 子任务状态」，且明确 `write-only 仅告警不修正`（`EventReconciliationServiceImpl:74-93`）

**本 ADR 的处置（不新增 outbox）**：

| 层 | 做法 |
|---|---|
| 主路径 | `Y` 的 CAS 成功后**同步**把父 SubTask 置 `DONE`（§5），窗口从「异步投递」缩到「同进程两次调用」 |
| 兜底 | 新增第 23 个定时任务 `TaskLifecycleReconcileTask`，三项对账（§5 表），全部幂等 |
| 不建 outbox 的理由 | 现有两张 outbox 表都不承载任务生命周期：`agent_command_outbox` 绑 `ExecutionCommandMqMessage`，`agent_outbox_event` 绑 agent 路由键；为单一事件新建一张表 = 为一类事件付整套 relay 成本，而本项目是**单库单服务**，同步 CAS + 对账足以收敛 |

判据不变：状态变更是事实、事件是投影；「改一次」用 CAS，「一定送出去」用对账或 outbox。生命周期传播选**对账**，因为它的失败形态是「少一次幂等调用」（可重建），不是「事件丢失且不可重建」。

### 18.4 不引入 TCC

TCC 解决的是「跨服务资源预留，Try 阶段先占住，Confirm 才真扣，Cancel 释放」。本项目是单体 + 同库：

- Try 阶段要「预留」的东西（`INSERT task` 行、`UPDATE sub_task` 状态）**都能在一个本地事务里直接回滚**，没有「预留后必须等别人确认」的资源
- 强行引入只会得到三套空接口 + 一个没人调 `confirm` 的流程，且要为「悬挂 / 空回滚」再写一遍 `task_plan_attempt` 已经解决的问题

**唯一需要「预留」语义的地方是对象存储（报告回挂的附件）**——那是 `AttachmentPort` 的职责，用「先写临时态、成功后置 ACTIVE」比 TCC 更贴合（现有附件版本机制已具备）。

### 18.5 Saga：要动作表，不要编排器

本 ADR §13.4 的 `A2 → RESTRUCTURING` 已经是补偿动作，但缺三样才能算闭环：

| 缺什么 | 现在的状态 | 补法 |
|---|---|---|
| **谁触发** | `Y` 失败目前没有任何投递方 | **同步**：`Y` 置 `CANCELLED`/整体失败的同一条路径里打回父 `A2 → RESTRUCTURING`；`TaskLifecycleReconcileTask` 兜底（§5 第 2 项） |
| **补偿幂等** | `AWAITING_DERIVED → RESTRUCTURING` 可能被重复触发 | `task_plan_attempt` 的 `batch_key + attempt_no` 就是现成的栅栏，`action` 加 `RESTRUCTURE` |
| **补偿失败重试** | 无 | 失败时写 `FAILED_RETRYABLE`，由既有的 `PlanAttemptDispatchTask` 重跑 |

`task_plan_attempt` 事实上已经是「受限 Saga 的动作表」：一行 = 一个动作的一次尝试，五态结论区分「可重试」与「重试同样失败」。**不需要引入通用 Saga 框架，也不需要生命周期 outbox**，把 `DERIVE` / `RESTRUCTURE` 两个 action 接进去即可。

### 18.6 落地清单（实现时必须一起做，不能分片欠账）

1. `chk_plan_attempt_action` 约束 + `action` 枚举包含 `DERIVE`、`RESTRUCTURE`（最终值：`DECOMPOSE/REGENERATE/CONFIRM/ABANDON/DERIVE/RESTRUCTURE`；`EXPAND` 随同 Task 内展开下线一并移除，见 `V146`）
2. **新增第 23 个定时任务 `TaskLifecycleReconcileTask`**：三项对账（§5 表），每项都带 `@SchedulerLock`
3. **不新增生命周期 outbox**：`OutboxAggregateType` 不加 `TASK_LIFECYCLE`；`TaskAutoCompletedEvent` 保持 `@Async @EventListener`；`tryCloseTask` 改为「同步传播 + 事件」两段
4. 报告回挂由同步传播与对账任务触发/补生成；附件写入需先开平台入口（§15.4）
5. `task` 派生列 + 两条条件唯一索引 + `root_task_id` 回填（§3）；`requirement_conversation` 加 `source_sub_task_id`（§19.6）

**这五条与 §8 的「保留不动」不冲突**：它们是在既有设施上扩展，不推翻任何已交付的 12 项能力。

### 18.7 上 k3s 要不要把代码分成「单机 / 分布式」两套

**结论：不分。用「默认按分布式正确写 + 就绪度清单 + CI 守卫」代替双路径。**

#### 为什么双路径是最坏的形态

| 问题 | 说明 |
|---|---|
| 两条路径的差异**只在故障时显现** | 测试（3687 例）跑的是单机路径，分布式路径**永远处于未验证状态**，等真上 k3s 才第一次运行——等于没测 |
| 配置选错 = **静默降级到错误路径** | 「约束强弱看 fail-open / fail-close」的反面：这是 fail-silent，比 fail-open 更坏，因为没有任何痕迹 |
| 成本不是翻倍 | 是「每次改动都要在两种模式下各验一遍」，而其中一条永远验不实，等于长期背着一份不可信资产 |

#### 现状盘点：一半本来就对

| 类 | 判据 | 实测 |
|---|---|---|
| **A. 已经分布式安全** | 用 DB / Redis 做互斥或 CAS | `shedlock` 用 **`RedisLockProvider`**（`ShedLockConfig:28`，非内存锁）⇒ 22 个 `@Scheduled` 在多实例下天然互斥；`task_plan_attempt` 的 `batch_key + attempt_no` 栅栏；outbox 状态迁移全是 CAS；`confirmRound` / `discardRound` 是 `REQUIRES_NEW` + CAS |
| **B. 补一行判断** | 有 CAS 能力但没接返回值 / 没加 `WHERE` | **仅 1 处**：`ExecutionCommandPoller#processRecord:150` 丢弃了 `markPolled` 的 `boolean` 返回值，且 `markPolled` 本身不是 CAS（`AgentExecutionRecordServiceImpl:260-265` 无条件 `eq(id)`）。多实例下同一孤儿会被多个实例同时拉到并各执行一次 |
| **C. 本来就该各管各** | 管的是进程内资源 | 沙箱池 `ReentrantLock`、`SandboxCallLedger`、工具/技能/Provider 注册表、门铃保活——多实例各自一份才是对的，加分布式锁反而是 bug |
| **D. 需要重新设计** | 单实例假设渗进数据模型 | **没有**（这是好消息：说明 V2 的「单应用→分布式」改造方向没有留下结构性债） |

`scanReviewOrphans`（`SubTaskReviewServiceImpl:209`，无 `@SchedulerLock`）看似属 B，实则安全——它调的 `reviewSubTask` 内部有防双审互斥锁。**这恰好证明正确做法是「锁放在业务动作上，不是调度层」**：调度层加锁只能防「同一定时任务多实例跑」，业务动作互斥能防「任何来源的重复触发」（MQ 重投 / 手工调用 / 定时扫描）。

#### 那 B 类的修法（不是一套实现，是两行）

```java
// markPolled 加 WHERE 条件，把「标记过」变成「抢到了」
lambdaUpdate().eq(AgentExecutionRecord::getId, id)
    .and(w -> w.isNull(AgentExecutionRecord::getLastAttemptTime)
               .or().lt(AgentExecutionRecord::getLastAttemptTime, cutoff))
    .set(AgentExecutionRecord::getLastAttemptTime, OffsetDateTime.now())
    .update();

// 调用方接返回值：抢不到就跳过
if (!agentExecutionRecordService.markPolled(record.getId(), cutoff)) {
    continue;
}
```

现状实测：`poller-enabled: true`、`poller-orphan-threshold-seconds: 60`、轮询间隔 `1000ms` ⇒ 默认开启、每实例每秒扫一次、60s 窗口内不重扫。**并发窗口真实存在**（两个实例同相位拉到同一条时，`markPolled` 两边都返回 true）。影响是孤儿恢复时双跑一次（双份模型额度、执行记录双写），不是数据损坏级——但它是缺陷，且修法只有两行。

#### 用 CI 守卫代替双路径

新增一条不变量检查，让「多实例就绪」变成机械可判的：

> **`helloai-job` / `helloai-core` 新增 `@Scheduled` 必须带 `@SchedulerLock`，白名单除外**（白名单 = 每实例固有的任务，如 `DoorbellKeepaliveTask`、`ExecutionCommandPoller`——后者本应由 `markPolled` 的 CAS 保证，不是调度锁）。

这样「忘记加锁」在 CI 就红，不需要一批人记住「上 k3s 前要检查」。与 `check-arch-freeze.sh` 同型：**把架构约束变成脚本，而不是变成第二份代码。**

#### 对 TCC / Saga / outbox 的具体影响

| 设施 | k3s 下的判断 |
|---|---|
| **outbox** | **更加必需**（Pod 随时被驱逐，进程崩溃是常态而非异常），且现有实现**天然多实例安全**：纯 DB 表 + `RedisLockProvider` 保证同一时刻只有一个 relay 在跑 + 状态迁移 CAS ⇒ **不需要分叉** |
| **TCC** | **仍不需要**。除非将来拆库 / 拆服务，否则「单体 + 同库」下 Try 阶段没有需要跨越确认的资源；k3s 只是换了部署形态，不改变事务边界 |
| **Saga 动作表** | `task_plan_attempt` 已是 DB 表 + 栅栏 + `PlanAttemptDispatchTask` 重试，**多实例安全** |

#### 唯一值得预先设计的：进程身份与租约

- `task_plan_attempt.owner_node`（`V146` 已有该列）应记录 **Pod 名 + 重启计数**，而不是主机名——k3s 下 Pod 名会变，这个字段是故障归因的唯一线索
- `lease_until` 心跳式租约机制**天然适配 k3s**（这正是它比内存锁强的地方：Pod 被驱逐后租约自然过期，其他副本接管）
- 这两条都是「改一处赋值」，不是分叉

#### 一句话

**上 k3s 需要的是「一张就绪清单 + 一条 CI 守卫」，不是「两套代码」。** 本项目 22 个定时任务里已有 20 个天然安全（`RedisLockProvider`），第 21 个安全（业务动作互斥），只有 1 个需要两行修复——这个比例说明**架构方向是对的，缺的是把「对了」变成「可验证」。**

---

# 附录：并入的草案暂存区与编号模型

> 原 `doc/design/HelloAI 草案暂存区与稳定编号设计.md` 的有效内容并入本节后，原文件删除。已被本 ADR 取代的章节（展开 S3、code 多段、扁平化、废除栅栏）不再保留。实现细节只保留仍在用的部分；批次与状态见 plan 的 `TD-16` 节。

## 19.1 `task_plan_draft` 表（已在 `V147` / `V147` 落地）

```sql
CREATE TABLE task_plan_draft (
    id                BIGINT       NOT NULL PRIMARY KEY,  -- 雪花，仅内部用
    task_id           BIGINT       NOT NULL REFERENCES task(id),
    code              VARCHAR(64)  NOT NULL,              -- 逻辑位置身份
    parent_code       VARCHAR(64),                        -- 本模型恒 NULL（保留列至清理批）
    depth             SMALLINT     NOT NULL DEFAULT 0,    -- 恒 0；层级改由 task.depth 表达
    round             SMALLINT     NOT NULL DEFAULT 1,    -- 草案轮次
    draft_seq         INT,                                -- 展示号来源（V147；不可从 code 推导）
    batch_key         VARCHAR(64)  NOT NULL,              -- 幂等键
    node_state        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    title             VARCHAR(255) NOT NULL,
    content           TEXT,
    deliverable       TEXT,
    acceptance        TEXT,
    acceptance_kind   VARCHAR(16)  NOT NULL DEFAULT 'AUTOMATED',
    priority          VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',
    contract          SMALLINT     NOT NULL DEFAULT 0,
    required_skills   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    constraints       TEXT,
    uncertainties     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    depends_on_codes  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    context           JSONB,
    create_time       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT chk_plan_draft_state CHECK (node_state IN ('ACTIVE','SUPERSEDED','DISCARDED','CONFIRMED'))
);
-- uk_plan_draft_code (task_id, code) WHERE deleted=0
-- uk_plan_draft_batch (batch_key, code) WHERE deleted=0
-- idx_plan_draft_task (task_id, node_state, deleted)
-- idx_plan_draft_round (task_id, round, node_state, deleted)
```

**`node_state` 四态**：`ACTIVE`（参与确认）/ `SUPERSEDED`（被派生取代的旧语义，本模型不再产生）/ `DISCARDED`（用户废弃或整批拒绝）/ `CONFIRMED`（已转正；**必须与 `deleted=1` 双写**，否则漏过滤 `deleted` 的读侧会把已转正草案再确认一次）。

## 19.2 `code` 与 `round`

- **`code` = 任务内位置身份**，单段：`<task.create_time 的 yyyyMMdd><seq:04d>`（定宽 ⇒ `ORDER BY code` 即前序）。**不存在子段**；跨任务不共享编号。
- 日期前缀取 `task.create_time`，不取 `now()`：跨天重拆时编号属于任务、不属于某一天。
- 展示号由 `draft_seq` 派生（`2` / `2.1` 的旧格式在无展开模型下退化为 `2`），存储/引用/接口一律用完整 `code`。
- **`round`**：草案期第几次拆解尝试，同 task 内递增，从 1 起。轮内可任意改；确认即终止；确认后唯一的新轮入口是「整批废除 + 重拆」。**`code` 跨轮不重置**，延续 `max(seq)+1`（旧轮整轮作废，无人引用旧号）。
- 不用 `uk (task_id, round, code)`：`code` 带轮次维度会迫使 `depends_on_codes` 知道「引用第几轮」，跨轮引用本身非法。
- **根层 `seq` 是全局计数器**：新增草案必须 `SELECT max(...) FOR UPDATE` 锁 `task` 行（或撞 `uk_plan_draft_code` 重试）。「不抢号」只对派生任务的草案区成立（每个 `Y` 自己的 `task_id` 独立）。

## 19.3 草案期生命周期（无展开）

| 阶段 | 动作 |
|---|---|
| S1 首拆 | `task.status = PLANNING` + 租约；LLM 产出 N 条 → `code = <date><seq:04d>`、`depth=0`、`parent_code=NULL`；模型给的局部号翻译成 `code`；全部落库 → `AWAITING_PLAN_CONFIRM` |
| S2 编辑 | 只改 `ACTIVE` 行字段，**`code` 不变**；白名单 = `content/deliverable/acceptance/acceptance_kind/priority/required_skills/constraints/depends_on_codes`；改依赖后立刻重跑 `G1_CYCLE`（叶/批内）+ `G1_DEP_OUT_OF_RANGE` |
| S3 派生 | **替代原「展开」**：对某条 `ACTIVE` 草案点「拆出去」→ 建 `Task Y`（`parent_task_id/parent_draft_code` 锚定、`depth+1`、继承 archetype）+ 触发 `Y` 拆解。**不改变本条草案的 `node_state`**（仍 `ACTIVE`，确认时物化为 `AWAITING_DERIVED` 的子任务） |
| S4 废弃/整批拒绝 | 单条：`DISCARDED + deleted=1`（子树同处理，本模型无子树）；整批：全部 `DISCARDED + deleted=1`，`task → PENDING`，记 `TASK_PLAN_REJECTED`，清空 findings |
| S5 确认 | 事务外跑规则门/语义门 → 事务内 T2：按 `code` 序物化 `sub_task`、写 `depends_on`（`code`→真实 id）、已派生行落 `AWAITING_DERIVED`（`Y` 已完成则落 `DONE`）、回填 `Y.parent_sub_task_id`、暂存区 `CONFIRMED + deleted=1`、`task → IN_PROGRESS`、清 findings |
| S6 确认后 | **不允许局部下钻**；要改走执行期重构（§11~§13）或整批废除重拆（新 `round`） |

**确认的并发边界**：T2 开头 `AWAITING_PLAN_CONFIRM → CONFIRMING` CAS；T2 内按 `update_time` 复核草案集合与事务外取数一致，不一致则中止并提示「草案已被修改，请重新确认」。

> **`G1` 的锚定前提**：草案期派生**不删、不软删、不改 `node_state`** 本条草案，所以 `DraftGateChecker` 的 `known` 集合（`checkDependencyRules` 只从入参草案构建）**天然包含被派生的草案**，兄弟依赖不会误报 `G1_DEP_OUT_OF_RANGE`。这是旧稿「派生后兄弟依赖悬空」缺口的解法——**不是补锚点，而是不改状态**。

## 19.4 幂等与栅栏（`task_plan_attempt`，已在 `V146` 落地）

| 事件 | `batch_key` | 说明 |
|---|---|---|
| 首拆 | `task.decompose_attempt_id` | 沿用现有生成器 |
| 派生（草案期/执行期） | `derive:<parentKey>:<uuid>` | 新增；`action=DERIVE` |
| 重构收敛 | `restructure:<subTaskId>:<uuid>` | 新增；`action=RESTRUCTURE` |
| 确认 | `confirm:<taskId>:<draftCount>:<contentHash>` | `action=CONFIRM` |
| 整批废除 | `abandon:<taskId>:<round>:<uuid>` | `action=ABANDON` |

- 五态：`PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED_TERMINAL` / `FAILED_RETRYABLE`（重试上限 3 次）。
- 防悬挂：写结果前 CAS `WHERE batch_key AND attempt_no AND status='RUNNING'`，影响 0 行即放弃。
- 完整性：撞键恢复判「内容完整」而非「存在」——`countByBatchKeyIncludeDeleted == expected_count`；`expected_count` 在解析阶段写入；`null` / 镜像 0 行两处 fail-open 回落存在性判定。

## 19.5 门禁

- **规则门**（`helloai-common` `DraftGateChecker`，纯函数，`ruleVersion=s5-3`）：`G1_DEP_OUT_OF_RANGE` / `G1_SELF_DEP` / `G1_CYCLE`（改为**批内 code 图**即可，无跨层叶子）/ `G2_BLANK` / `G2_LEN_MIN` / `G2_KIND_FORM_CONFLICT` / `G3_DEPTH_LIMIT`（入参改 `task.depth`，派生入口判）。
- **语义门**（`planner/policy` `SemanticContractGate`，经 `SemanticJudgePort` 调模型）：`G4_UNSATISFIABLE_CONTRACT`，只在确认前跑，30s 有界等待，fail-open + `warn` 留痕。不接「结构变更后」——改造类路径上 LLM 不能进事务。
- **运行期门**（执行期重构）：`G5_RUNTIME_CONTRACT_BREAK`，双向（上游已产出/约束 vs 新验收；下游引用 vs 本条新产出），确定性文本匹配 + 模型；预算见 §17.6。
- 三层处置不变：生成类只诊断、改造类判不过即回滚、确认类判不过即拒。
- `Finding.draftRef` 为 `String`（存 code），`PlanGateRunner` / `SubTaskGateFindingsStore` / 前端 `gateFindings` 同步。

## 19.6 执行期重构的会话血缘

`requirement_conversation` 现有 `task_id` / `parent_conversation_id` / `forked_from_message_id`，**没有「由哪个子任务派生」的列**。新增：

```sql
ALTER TABLE requirement_conversation ADD COLUMN source_sub_task_id BIGINT NULL;
CREATE INDEX idx_conversation_source_sub_task ON requirement_conversation (source_sub_task_id)
  WHERE source_sub_task_id IS NOT NULL AND deleted = 0;
```

写入时机：执行期重构创建 ACTIVE 会话时。草案期派生**不建会话**，故不写。反查「这个子任务产生过哪些派生会话」走该列；不冗余到 `sub_task`（判据 4）。

## 19.7 深度与 archetype

- 全局 `depth ≤ 2`（根 + 2 层派生 = 3 级）。派生入口校验 `task.depth < maxDepth`；超限拒绝并提示「继续细拆说明需求边界不清」。
- `MaxExpandDepth` 分档：`BUILD=2` / `DOCUMENT=2` / `RESEARCH=2` / `DATA·OPERATE=default 2` / `DECIDE=1`；`application.yml` 的 `build: 2` 已于 2026-11-10（`AD-6`）生效。
- `Y` 继承 `X.task_archetype`，只允许更严：`maxDepth(Y) = min(分档(父 archetype), 分档(Y 继承值))`；**不重新判定**，避免派生任务与父任务拆分维度不一致。

## 19.8 入口与权限

| 入口 | 位置 | 权限码 |
|---|---|---|
| 草案期派生 | 草案审阅弹窗行操作 | 复用 `task:plan` |
| 执行期重构（进入 `RESTRUCTURING`） | 子任务详情页 | 新增 `subtask:restructure` |
| 原地整改（`RESTRUCTURING → PENDING`） | 子任务编辑通道（**新建**，现状 `updateDraftById` 仅限草案期） | `subtask:restructure` |
| 派生执行 | 重构页「拆出去」 | `subtask:derive` |

种子与落地断言在 `V148`（与派生模型同文件；`subtask:restructure` / `subtask:derive` + ADMIN/NORMAL_USER 授予，末尾按 code 校验两码与 4 条绑定）。未并入 `V88`：该迁移早已 apply；2026-11-11 收敛后也不再单独占号。

## 19.9 迁移与存量口径

- 已合并为 4 个文件（2026-11-10）：`V146`（租约/状态 + `draft_seq` 回填 + attempt 栅栏）、`V147`（暂存区 + `sub_task.draft_code` + 镜像 `draft_seq` + 删除级联）、`V148`（派生列/索引/触发器 + 重构会话血缘）、`V149`（全局规则同步）。原 V146~V155 的分步文件已并入，映射见各文件末尾。
- 新增（按施工顺序占号，以 `ls db/migration | sort -V | tail -1` 为准）：派生列 + 索引 + `root_task_id` 回填；`source_sub_task_id`；`chk_sub_task_status` 增两态；`chk_plan_attempt_action` 增 `DERIVE`/`RESTRUCTURE`。
- 存量收口：`PENDING_PLAN_REVIEW` 行迁入暂存区（未镜像的旧行），再执行**不可逆减法**（删 `sub_task.draft_seq` / `decompose_attempt_id` / `uk_sub_task_decompose_attempt`，CHECK 去 `PENDING_PLAN_REVIEW`）——迁移脚本内 `RAISE EXCEPTION` 断言 `count(PENDING_PLAN_REVIEW)=0`。
  **2026-11-10 评审定稿**：拆成 **`5a`（可逆）** 与 **`5b`（不可逆）**。`5a` 把写/读/编辑/重生成/确认全部切到 `task_plan_draft`（`sub_task` 只在确认时物化），存量 **83 条**（10 个任务；65 条无 `draft_code`）迁入后把旧行置 **`CANCELLED`** 并记 `context.draftMigratedToStaging`（保留审计，不硬删）；`5b` 才做减法。`5a` 必须先于 `5b` 烘焙一个周期。
- 暂存区死列 `parent_code` / `depth` 在合并版 `V147` 中**不再建列**（原为只写不读）；`node_state` 收为三态（去掉 `SUPERSEDED`），`chk_plan_attempt_action` 去掉 `EXPAND`。已运行过旧 V146~V155 的环境必须 drop schema 重建，不能只删 history（`IF NOT EXISTS` 会静默保留旧结构）。
