# ADR-001：Run / Turn / Step 三层执行模型定稿

| 项目 | 内容 |
|---|---|
| **编号** | ADR-001 |
| **状态** | Accepted（Phase 0 Epic-A Task A1 定稿；实现中发现问题可发起修订） |
| **日期** | 2026-09-02 |
| **关联文档** | [HelloAI_架构改造长期思路](../HelloAI_架构改造长期思路.md)、[HelloAI_Phase0_架构改造执行方案](../HelloAI_Phase0_架构改造执行方案.md)、[HelloAI_CODE_STYLE.md](../../HelloAI_CODE_STYLE.md) |
| **决策类型** | 模型定义（contract-first，先于 Event Stream 建表） |

---

## 1. 背景与问题

当前执行记录为**扁平生命周期**（`agent_execution_record` 一行 = 一次子任务执行，
PENDING→RUNNING→SUCCESS/FAILED/TIMEOUT，无事件溯源），且执行过程中的
Tool 调用、LLM 推理、Skill 加载等**原子动作不可见**。后续要建设
Trajectory / Replay / Resume / Fork 等治理能力（架构改造长期思路 P2-P3），
必须先统一"一次完整执行由什么组成"的三层模型。

**本 ADR 只定模型与标识契约，不建表、不改业务表。** 表结构落地见
Epic-B（`agent_event` 表）与 Epic-A2（CAS/Lease 字段），字段约束以本 ADR 为准。

---

## 2. 决策一：三层模型语义定义

| 概念 | 定义 | 生命周期 | 产生方式 |
|---|---|---|---|
| **Run** | 一次用户需求触发的完整执行 = 一个 Plan 的一次实例 | 对应一次 Reviewer 终态（任务终态） | 需求终稿确认 → 任务创建 |
| **Turn** | 一次 Agent 完整工作周期，含一次完整 Agent Loop | Rework / 重试会产生新 Turn | 子任务每次执行尝试 |
| **Step** | Turn 内的一个原子动作（Tool 调用 / LLM 推理 / Skill 加载） | 不可再分，append-only | 执行过程中逐步产生 |

关系约束：

```text
Run #1
├── Turn #1
│    ├── Step #1   (TOOL_CALL_STARTED: postgres_oa)
│    ├── Step #2   (TOOL_CALL_COMPLETED)
│    └── Step #3   (LLM 推理)
├── Turn #2        ← Rework 产生新 Turn
│    ├── Step #1
│    └── Step #2
└── Run 终态事件    (RUN_COMPLETED / 失败终态)
```

支撑能力（Phase 0 只定义契约，Phase 2 实现）：

- **Resume**：从失败 Step 所在 Turn 重放（不重跑已成功的 Step）
- **Replay**：按事件序列重放某个 Run 的完整过程
- **Fork**：复制 Run 的全部事件快照，生成新 RunId
- **Retry Step / Replay Step**：单 Step 粒度重试/重放

---

## 3. 决策二：标识规则（run_id / turn / step）

### 3.1 run_id

**格式**：`run-{taskId}-{roundNum}`（`VARCHAR(64)`）

- `taskId`：`task.id`（当前代码中"一次需求完整执行"的权威载体，
  `requirement_conversation` 终稿后创建 `task`）
- `roundNum`：`TaskIteration.roundNum`（同一 task 的 rework 迭代轮次，默认 1）

**理由**：

1. 当前无 `plan` 实体，`task` + `roundNum` 是"一次需求触发的完整执行实例"
   最贴近的现有锚点，避免 Phase 0 新建平行主表（CODE_STYLE §50.7 禁止平行架构）；
2. 后续引入 Plan 实体（Phase 3 路线）时，`run_id` 生成规则升级为
   `run-{planInstanceId}`，存量事件可通过 `task_id` 列无损迁移；
3. 与现有主键体系（Long ASSIGN_ID）隔离，事件表维度的字符串标识不做业务主键
   （CODE_STYLE §12.1：业务主键必须 Long；`run_id` 仅是事件分组维度）。

### 3.2 turn / step

- `turn`：INT，从 1 递增。子任务每次"执行尝试"（assignment → 完成/失败）
  产生一个新 Turn；Rework（`reworkCount` +1）、超时回收重派、重试均递增。
- `step`：INT，从 1 递增（`TOOL_CALL_STARTED` 等原子事件逐条递增），
  Turn 结束（`AGENT_COMPLETED` / 失败终态）后重置，下一 Turn 从 1 重新开始。

### 3.3 传递链

`run_id / turn / step` 必须贯穿全链路（CODE_STYLE §29 TraceId + 业务 ID 可追踪）：

```text
请求/MQ 消息 header
    ↓
MDC（run_id / task_id / step_id）
    ↓
AgentEventRecorder.record(runId, taskId, subTaskId, turn, step, eventType, agentId, payload)
    ↓
agent_event 表 + outbox
```

---

## 4. 决策三：与现有数据模型映射

| 三层概念 | 现有表 / 字段 | 说明 |
|---|---|---|
| **Run** | `task`（`task.id`）+ `task_iteration.round_num` 派生 `run_id` | task 已含 `status`（TaskStatus）、`final_report*` 终态字段 |
| **Turn** | `sub_task` 的一次执行尝试；`sub_task.rework_count` / `reassign_attempt_count` 辅助判断 Turn 序号 | 每次执行尝试 = 1 个 Turn + 若干 Step |
| **Step** | 无独立表，append-only 事件（`agent_event.turn` + `step` 序号） | 原子动作只以事件形式存在，不建 Step 表（避免重复存储） |
| 执行记录 | `agent_execution_record`（扁平权威状态） | **保持为源 of truth 之一**，事件表不反向成为状态权威 |
| 事件审计 | `task_timeline`（现有粗粒度事件） | 现有时间线保留；`agent_event` 覆盖更细粒度执行轨迹，二者互补不重复维护 |
| 产出快照 | `task_execution_record`（EXECUTION_RECORD 协议 SUMMARY/KEY_DECISIONS 等） | 不迁移，保持现状 |

**明确不做**：

- 不把 `agent_execution_record` 改造成事件存储（不做 Event Sourcing）；
- 不为 Step 新建存储表（Step 粒度仅存在于事件流）；
- 不删除 / 不迁移 `task_timeline`（Phase 0 双轨期行为保持不变）。

---

## 5. 决策四：事件类型分类

事件分两层，对应 Epic-B 建表时的 `event_type` 枚举（与 Run 生命周期对齐）：

### 5.1 Run / Task 级（turn=0，step=0）

```text
RUN_CREATED → TASK_CREATED → TASK_ASSIGNED →
REVIEW_STARTED → REVIEW_REJECTED → REWORK_STARTED →
REVIEW_APPROVED → RUN_COMPLETED
```

### 5.2 Turn / Step 级（turn≥1，step≥1）

```text
AGENT_STARTED → SKILL_RESOLVED → CONTEXT_BUILT →
TOOL_CALL_STARTED → TOOL_CALL_COMPLETED → AGENT_COMPLETED
```

### 5.3 端点事件约定（对账依赖，重要）

| 事件 | 约定 |
|---|---|
| `RUN_COMPLETED` | Run 成功终态，对应 task 终态（Reviewer 通过） |
| 失败终态 | 以业务表状态为准（`agent_execution_record.status` FAILED/TIMEOUT），事件层不额外定义失败终态事件，避免双份状态源 |

> 对账规则（Epic-B3）：同一 `subTaskId` 下，事件流终态事件应与业务表状态一致；
> 事件仅是业务状态的投影（write-only），**不参与任何业务决策**（B2 埋点约束）。

---

## 6. 决策五：Phase 0 落地约束（本 ADR 对各 Task 的字段约束）

### 6.1 对 Event Stream（Epic-B1）的影响

`agent_event` 表核心维度必须为：

```sql
id BIGINT PK               -- ASSIGN_ID 雪花，业务主键（CODE_STYLE §12.1）
run_id VARCHAR(64) NOT NULL
task_id BIGINT
sub_task_id BIGINT
turn INT NOT NULL DEFAULT 1
step INT NOT NULL DEFAULT 0
event_type VARCHAR(64) NOT NULL
agent_id BIGINT
payload JSONB
created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
-- 索引：(task_id, sub_task_id, turn, step)；(run_id, turn, step)
```

- `event_type` 枚举值以本 ADR §5 为准（`helloai-common/.../constant/AgentEventType.java`）；
- 时间字段 `timestamptz` + Java `OffsetDateTime`（CODE_STYLE §16）；
- `payload` JSONB 映射 `JacksonTypeHandler` / 类型安全对象（CODE_STYLE §16.3）。

### 6.2 对状态机加固（Epic-A2）的影响

- `sub_task` 增加 `owner`（`VARCHAR(128)`，与 `agent_execution_record.worker_node` 同取值体系）
  与 `lease_until`（`timestamptz`）——**Task 归属与租约在 sub_task 层**（执行单元粒度）；
- `agent_execution_record` 增加 `version`（`INT NOT NULL DEFAULT 0`）+ `@Version`
  ——CAS 更新依赖（CODE_STYLE §15 乐观锁机制）；
- Run 级不再单独建租约表（Run 的状态由 task.status 承载，多 worker 并发只发生在
  sub_task 粒度）。

### 6.3 对 AgentRuntime（Epic-C1）的影响

`AgentContext` 必须携带 `runId / taskId / subTaskId / turn / step`，
`AgentResult` 可携带补发事件列表（如异步产出的事件）——契约即事件接口（C1）。

---

## 7. 备选方案与拒绝理由

| 备选 | 拒绝理由 |
|---|---|
| **纯 Event Sourcing**（事件即唯一状态源） | 现有 `task` / `sub_task` / `agent_execution_record` 已是权威状态，改造为 ES 需重建全部查询与对账，违背"复用优先、最小改动"（CODE_STYLE §2）；事件表只做轨迹/投递（Phase0 执行方案坑 1 结论） |
| **Step 建独立表** | 与事件表重复存储，双写一致性问题扩大；事件流本身可支撑 Replay/Resume，无需双份 |
| **Run 新建独立主表** | Phase 0 无 plan 实体，新建平行主表违背 §50.7；用 `task + round_num` 派生即可满足事件分组与检索 |
| **复用 `task_timeline` 承担事件流** | 现有时间线 schema（eventType String / role / payload）粒度过粗，无法表达 turn/step 维度；并行保留，事件流独立建表 |

---

## 8. 待 Phase 2 决策（本 ADR 明确不决策）

1. 快照机制（事件流长度阈值与快照表设计）；
2. Plan 实体引入后 `run_id` 生成规则升级；
3. Step 粒度的事件去重（同一 Step 重试的序列化约定）——Phase 2 实现 Retry Step 时定。

---

## 9. 参考文献

- 架构改造长期思路（P0-1 AgentRuntime / P0-2 Event Stream / P0-3 状态机）
- Phase 0 执行方案 §三（Run/Turn/Step 语义）、§四（ADR-003 Event Stream 选型）、附录坑 1（非 ES）