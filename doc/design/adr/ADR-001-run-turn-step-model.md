# ADR-001：Run / Turn / Step 三层执行模型定稿

| 项目       | 内容                                                                                                                                                            |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **编号**   | ADR-001                                                                                                                                                       |
| **状态**   | Accepted（Phase 0 Epic-A Task A1 定稿；R1 修订 2026-09-04：step 语义定为事件类型槽位）                                                                                          |
| **日期**   | 2026-09-02                                                                                                                                                    |
| **关联文档** | [HelloAI\_架构改造长期思路](../HelloAI_架构改造长期思路.md)、[HelloAI\_Phase0\_架构改造执行方案](../HelloAI_Phase0_架构改造执行方案.md)、[HelloAI\_CODE\_STYLE.md](../../HelloAI_CODE_STYLE.md) |
| **决策类型** | 模型定义（contract-first，先于 Event Stream 建表）                                                                                                                       |

***

## 1. 背景与问题

当前执行记录为**扁平生命周期**（`agent_execution_record` 一行 = 一次子任务执行，
PENDING→RUNNING→SUCCESS/FAILED/TIMEOUT，无事件溯源），且执行过程中的
Tool 调用、LLM 推理、Skill 加载等**原子动作不可见**。后续要建设
Trajectory / Replay / Resume / Fork 等治理能力（架构改造长期思路 P2-P3），
必须先统一"一次完整执行由什么组成"的三层模型。

**本 ADR 只定模型与标识契约，不建表、不改业务表。** 表结构落地见
Epic-B（`agent_event` 表）与 Epic-A2（CAS/Lease 字段），字段约束以本 ADR 为准。

***

## 2. 决策一：三层模型语义定义

| 概念       | 定义                                         | 生命周期                   | 产生方式          |
| -------- | ------------------------------------------ | ---------------------- | ------------- |
| **Run**  | 一次用户需求触发的完整执行 = 一个 Plan 的一次实例              | 对应一次 Reviewer 终态（任务终态） | 需求终稿确认 → 任务创建 |
| **Turn** | 一次 Agent 完整工作周期，含一次完整 Agent Loop           | Rework / 重试会产生新 Turn   | 子任务每次执行尝试     |
| **Step** | Turn 内的一个原子动作（Tool 调用 / LLM 推理 / Skill 加载） | 不可再分，append-only       | 执行过程中逐步产生     |

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
└── Run 终态事件    (RUN_COMPLETED；失败终态以业务表状态表达，不产事件，见 §5.3)
```

支撑能力（Phase 0 只定义契约，Phase 2 实现）：

- **Resume**：从失败 Step 所在 Turn 重放（不重跑已成功的 Step）

- **Replay**：按事件序列重放某个 Run 的完整过程

- **Fork**：复制 Run 的全部事件快照，生成新 RunId

- **Retry Step / Replay Step**：单 Step 粒度重试/重放

***

## 3. 决策二：标识规则（run\_id / turn / step）

### 3.1 run\_id

**格式**：`run-{taskId}-{roundNum}`（`VARCHAR(64)`）

- `taskId`：`task.id`（当前代码中"一次需求完整执行"的权威载体，
  `requirement_conversation` 终稿后创建 `task`）

- `roundNum`：`TaskIteration.roundNum`（同一 task 的 rework 迭代轮次，默认 1）

> **Phase 0 现实校准（R2 文档核查，与 `AgentEventContextResolver.resolveRunId` 注释同源）**：
> 本轮实现中 `roundNum` **运行时固定取 1**——`task_iteration.round_num` 是报告生成后的
> 回填快照（`TaskIterationServiceImpl.backfillForTask` 以子任务 `reworkCount+1` 派生），
> 运行时无数据、非主任务轮次权威字段，基于现有字段推导 true 轮次不可行。
> 故当前 `run_id` 实际以 `task.id` 为唯一维度；升级路径见 §8 待决策 2。

**理由**：

1. 当前无 `plan` 实体，`task`（`roundNum` 运行时固定 1）是"一次需求触发的完整执行实例"
   最贴近的现有锚点，避免 Phase 0 新建平行主表（CODE\_STYLE §50.7 禁止平行架构）；
2. 后续引入 Plan 实体（Phase 3 路线）时，`run_id` 生成规则升级为
   `run-{planInstanceId}`，存量事件可通过 `task_id` 列无损迁移；
3. 与现有主键体系（Long ASSIGN\_ID）隔离，事件表维度的字符串标识不做业务主键
   （CODE\_STYLE §12.1：业务主键必须 Long；`run_id` 仅是事件分组维度）。

### 3.2 turn / step

- `turn`：INT，从 1 递增。子任务每次"执行尝试"（assignment → 完成/失败）
  产生一个新 Turn；Rework（`reworkCount` +1）、超时回收重派、重试均递增。

  > **Phase 0 现实校准（与 `AgentEventContextResolver.resolveTurn` 注释同源）**：
  > 实现为 `1 + reworkCount + attemptTotal`（A3 后返工/重派/超时统一计入
  > `attempt_total` 共享预算）；`reworkFresh`（人工驳回）与死信重派清零计数器，
  > 使 Turn 序号回落——已知近似（事件仅 write-only，不影响 B3 对账）。

- `step`：INT，**事件类型槽位**（R1 修定，原文「从 1 递增」）：

  - `0`：Turn 端点 / 非 Step 级事件（`AGENT_COMPLETED`；Run/Task 级事件同用 0）；

  - `1-4`：现有执行链固定槽位（`AGENT_STARTED`=1 / `CONTEXT_BUILT`=2 /
    `TOOL_CALL_STARTED`=3 / `TOOL_CALL_COMPLETED`=4）；

  - `5`：`SKILL_RESOLVED`（Phase 1 Skill 供电新增槽位）；

  - `6`：`TOOL_RESOLVED`（Phase 1 Step 2 工具解析新增槽位，与 SKILL_RESOLVED 对称）；

  - `7`：`ENVIRONMENT_RESOLVED`（Phase 1 Step 4 执行环境解析新增槽位，与 SKILL/TOOL 对称）；

  - **step 不承担时序职责**：事件顺序以 `create_time + id` 为准（append-only 单调），
    事件类型逻辑序以 §5.2 为准，二者与 step 数值不等价；

  - Turn 结束（`AGENT_COMPLETED` / 失败终态）后下一 Turn 复用同一槽位映射；

  - Phase 2 AgentLoop 多步化时再决策 per-action 计数语义（衔接 §8.3 挂起项）。

### 3.3 传递链

`run_id / turn / step` 必须贯穿全链路（CODE\_STYLE §29 TraceId + 业务 ID 可追踪）：

```text
请求/MQ 消息 header
    ↓
MDC（run_id / task_id / step_id）
    ↓
AgentEventRecorder.record(runId, taskId, subTaskId, turn, step, eventType, agentId, payload)
    ↓
agent_event 表 + outbox
```

***

## 4. 决策三：与现有数据模型映射

| 三层概念     | 现有表 / 字段                                                                           | 说明                                                |
| -------- | ---------------------------------------------------------------------------------- | ------------------------------------------------- |
| **Run**  | `task`（`task.id`）+ `task_iteration.round_num` 派生 `run_id`                          | task 已含 `status`（TaskStatus）、`final_report*` 终态字段 |
| **Turn** | `sub_task` 的一次执行尝试；`sub_task.rework_count` / `attempt_total` 辅助判断 Turn 序号（实现 `1 + reworkCount + attemptTotal`；`reassign_attempt_count` 已随 Phase 0 A3 切换为 `attempt_total`，V64） | 每次执行尝试 = 1 个 Turn + 若干 Step                       |
| **Step** | 无独立表，append-only 事件（`agent_event.turn` + `step` 序号）                                | 原子动作只以事件形式存在，不建 Step 表（避免重复存储）                    |
| 执行记录     | `agent_execution_record`（扁平权威状态）                                                   | **保持为源 of truth 之一**，事件表不反向成为状态权威                 |
| 事件审计     | `task_timeline`（现有粗粒度事件）                                                           | 现有时间线保留；`agent_event` 覆盖更细粒度执行轨迹，二者互补不重复维护        |
| 产出快照     | `task_execution_record`（EXECUTION\_RECORD 协议 SUMMARY/KEY\_DECISIONS 等）             | 不迁移，保持现状                                          |

**明确不做**：

- 不把 `agent_execution_record` 改造成事件存储（不做 Event Sourcing）；

- 不为 Step 新建存储表（Step 粒度仅存在于事件流）；

- 不删除 / 不迁移 `task_timeline`（Phase 0 双轨期行为保持不变）。

***

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

| 事件              | 约定                                                                              |
| --------------- | ------------------------------------------------------------------------------- |
| `RUN_COMPLETED` | Run 成功终态，对应 task 终态（Reviewer 通过）                                                |
| 失败终态            | 以业务表状态为准（`agent_execution_record.status` FAILED/TIMEOUT），事件层不额外定义失败终态事件，避免双份状态源 |

> 对账规则（Epic-B3）：同一 `subTaskId` 下，事件流终态事件应与业务表状态一致；
> 事件仅是业务状态的投影（write-only），**不参与任何业务决策**（B2 埋点约束）。

***

## 6. 决策五：Phase 0 落地约束（本 ADR 对各 Task 的字段约束）

### 6.1 对 Event Stream（Epic-B1）的影响

`agent_event` 表核心维度必须为：

```sql
id BIGINT PK               -- ASSIGN_ID 雪花，业务主键（CODE_STYLE §12.1）
event_id VARCHAR(64) NOT NULL UNIQUE  -- 双写幂等 / B3 对账键（V65 实际）
run_id VARCHAR(64) NOT NULL
task_id BIGINT
sub_task_id BIGINT
turn INT NOT NULL DEFAULT 1
step INT NOT NULL DEFAULT 0
event_type VARCHAR(64) NOT NULL
agent_id BIGINT
payload JSONB NOT NULL DEFAULT '{}'::jsonb
create_time TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
-- V65 实际（R2 对齐）：通用审计列与 agent_outbox_event 同款（create_by/update_by/update_time/deleted/remark）
-- 索引（V65 实际）：idx_agent_event_run(run_id, turn, step)；idx_agent_event_sub_task(sub_task_id, create_time)
```

- `event_type` 枚举值以本 ADR §5 为准（`helloai-common/.../constant/AgentEventType.java`）；

- 时间字段 `timestamptz` + Java `OffsetDateTime`（CODE\_STYLE §16）；

- `payload` JSONB 映射 `JacksonTypeHandler` / 类型安全对象（CODE\_STYLE §16.3）。

### 6.2 对状态机加固（Epic-A2）的影响

- `sub_task` 增加 `owner`（`VARCHAR(128)`，与 `agent_execution_record.worker_node` 同取值体系）
  与 `lease_until`（`timestamptz`）——**Task 归属与租约在 sub\_task 层**（执行单元粒度）；

- `agent_execution_record` 增加 `version`（`INT NOT NULL DEFAULT 0`）+ `@Version`
  ——CAS 更新依赖（CODE\_STYLE §15 乐观锁机制）；

- Run 级不再单独建租约表（Run 的状态由 task.status 承载，多 worker 并发只发生在
  sub\_task 粒度）。

### 6.3 对 AgentRuntime（Epic-C1）的影响

`AgentContext` 必须携带 `runId / taskId / subTaskId / turn / step`，
`AgentResult` 可携带补发事件列表（如异步产出的事件）——契约即事件接口（C1）。

***

## 7. 备选方案与拒绝理由

| 备选                                   | 拒绝理由                                                                                                                                   |
| ------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------- |
| **纯 Event Sourcing**（事件即唯一状态源）       | 现有 `task` / `sub_task` / `agent_execution_record` 已是权威状态，改造为 ES 需重建全部查询与对账，违背"复用优先、最小改动"（CODE\_STYLE §2）；事件表只做轨迹/投递（Phase0 执行方案坑 1 结论） |
| **Step 建独立表**                        | 与事件表重复存储，双写一致性问题扩大；事件流本身可支撑 Replay/Resume，无需双份                                                                                         |
| **Run 新建独立主表**                       | Phase 0 无 plan 实体，新建平行主表违背 §50.7；用 `task + round_num` 派生即可满足事件分组与检索                                                                    |
| **复用** **`task_timeline`** **承担事件流** | 现有时间线 schema（eventType String / role / payload）粒度过粗，无法表达 turn/step 维度；并行保留，事件流独立建表                                                     |

***

## 8. 待 Phase 2 决策（本 ADR 明确不决策）

1. 快照机制（事件流长度阈值与快照表设计）；
2. Plan 实体引入后 `run_id` 生成规则升级；
3. Step 粒度的事件去重（同一 Step 重试的序列化约定）——Phase 2 实现 Retry Step 时定。

***

## 9. 参考文献

- 架构改造长期思路（P0-1 AgentRuntime / P0-2 Event Stream / P0-3 状态机）

- Phase 0 执行方案 §三（Run/Turn/Step 语义）、§四（ADR-003 Event Stream 选型）、附录坑 1（非 ES）

***

## 修订记录

### R4（2026-09-05）：ENVIRONMENT_RESOLVED 新增槽位（Phase 1 Step 4 执行环境供电 + SandboxProvider 落位）

**背景**：Phase 1 Step 4 把执行环境提升为 AgentRuntime 一等公民（`ExecutionEnvironment`
抽象 + RemoteAgent / LocalProcess 两实现 + `ExecutionEnvironmentProvider` 解析注入
`AgentContext.environment`，长期思路 P0-1 SandboxProvider 当期落位）。沿用「契约供电 +
埋点 + 对账同步」三件套模式（Step 1 Skill / Step 2 Tool 同款），执行链新增
`ENVIRONMENT_RESOLVED` 埋点。

**决策**：`ENVIRONMENT_RESOLVED` 分配新槽位 **step=7**（与 `SKILL_RESOLVED`=5 /
`TOOL_RESOLVED`=6 对称，同为「解析完成」语义；step 为事件类型槽位，不承担时序）。
事实依据与 R1 同源：step 无唯一约束、消费方按 `create_time DESC, id DESC` 排序，
无 step 数值断言。

**伴生约束**（实现 `ENVIRONMENT_RESOLVED` 埋点时同一闭环完成，IN_PROGRESS 合法末条
事件集均需加 `ENVIRONMENT_RESOLVED`，否则探针误报 MISMATCH）：

- 对账契约同步（**6 处同构映射，必须同一次提交全部修改**）：
  1. `EventReconciliationServiceImpl` 的 IN_PROGRESS 期望集合 + javadoc
     「step 1-6 槽位」→「step 1-7 槽位」；
  2. `verify-c3-events.ps1` P2 的 IN_PROGRESS 映射；
  3. `verify-c3-events.sh` P2 的 IN_PROGRESS 映射（ps1 同构移植）；
  4. `verify-c3-reconcile.ps1` 的 matched/mismatches 两处判定；
  5. `verify-c3-reconcile.sh` 的 matched/mismatches 两处判定（ps1 同构移植）；
- 埋点**恒发**（environment 未解析时 payload 两键按 null 事实记录），保持每个 Turn
  事件骨架完整；
- 埋点位置：`executeOnce`（`SubTaskExecutionServiceImpl`）内 tool 解析之后、
  `CONTEXT_BUILT`（step=2）之前；payload 含 `environment`（环境标识 remote-agent /
  local-process）+ `accessType`（接入类型，审计归因用）。

**不做**：`AgentContext.environment` 仅作为契约输入 + 埋点观测 + session 快照事实，不改动
Prompt 装配与路由行为（运行时行为零变化，与 Step 1/2 的「纯契约完备性」哲学一致）；
DockerSandbox 推迟 P2、K8sSandbox 推迟 P3（执行方案坑 4 结论）。

### R3（2026-09-05）：TOOL_RESOLVED 新增槽位（Phase 1 Step 2 Tool 供电 + ToolRegistry）

**背景**：Phase 1 Step 2 把 Tool 提升为 AgentRuntime 一等公民（`AgentContext.tools`
显式供电 + ToolRegistry 元数据注册面，长期思路 P0-1 成员落位）。按「契约供电 + 埋点 +
对账同步」三件套模式（Step 1 Skill 同款），执行链新增 `TOOL_RESOLVED` 埋点。

**决策**：`TOOL_RESOLVED` 分配新槽位 **step=6**（与 `SKILL_RESOLVED`=5 对称，
同为「解析完成」语义；step 为事件类型槽位，不承担时序）。事实依据与 R1 同源：
step 无唯一约束、消费方按 `create_time DESC, id DESC` 排序，无 step 数值断言。

**伴生约束**（实现 `TOOL_RESOLVED` 埋点时同一闭环完成，IN_PROGRESS 合法末条事件集
均需加 `TOOL_RESOLVED`，否则探针误报 MISMATCH）：

- 对账契约同步（**6 处同构映射，必须同一次提交全部修改**）：
  1. `EventReconciliationServiceImpl` 的 IN_PROGRESS 期望集合 + javadoc
     「step 1-5 槽位」→「step 1-6 槽位」；
  2. `verify-c3-events.ps1` P2 的 IN_PROGRESS 映射；
  3. `verify-c3-events.sh` P2 的 IN_PROGRESS 映射（ps1 同构移植）；
  4. `verify-c3-reconcile.ps1` 的 matched/mismatches 两处判定；
  5. `verify-c3-reconcile.sh` 的 matched/mismatches 两处判定（ps1 同构移植）；
- 埋点**恒发**（`tools` 为空时 payload 两键为空数组），保持每个 Turn 事件骨架完整；
- 埋点位置：`executeOnce`（`SubTaskExecutionServiceImpl`）内 skill 解析之后、
  `CONTEXT_BUILT`（step=2）之前；payload 含 `tools`（启用清单）+ `resolvedTools`
  （ToolRegistry 命中元数据 name/description，承载 Registry 元数据消费面）。

**不做**：AgentContext.tools 仅作为契约输入 + 埋点观测，不改动 Prompt 装配（运行时
行为零变化，与 Step 1 的「纯契约完备性」哲学一致）；ToolRegistry 仅覆盖平台当前
MCP 工具（12 个），GitTool/ShellTool 等未来工具类型（长期思路 P1）注册进同一目录。

### R2（2026-09-04）：as-is 事实对齐修正（文档核查，无方案变更）

**背景**：对照代码 / DDL（`AgentEventContextResolver`、`V64`、`V65`、`SubTaskExecutionServiceImpl`、4 份 C3 探针）核查，发现 4 处 as-is 表述与事实不符，纯文档修正。

**修正项**：

| 位置 | 修正前 | 修正后 |
|---|---|---|
| §3.1 roundNum | 声称取 `TaskIteration.roundNum`（运行时权威） | 补现实校准：运行时固定取 1（`round_num` 为报告后回填快照，非权威字段），升级路径见 §8 待决策 2 |
| §3.2 turn | 未提序号回落 | 补现实校准：实现 `1 + reworkCount + attemptTotal`；`reworkFresh`/死信重派清零致序号回落（已知近似，不影响对账） |
| §4 Turn 行 | `reassign_attempt_count` | `attempt_total`（Phase 0 A3 / V64 切换），并补 resolveTurn 公式 |
| §6.1 DDL | `created_at`；索引 `(task_id, sub_task_id, turn, step)` | 对齐 V65 实际：`create_time` + `event_id UNIQUE`；索引 `(run_id, turn, step)` + `(sub_task_id, create_time)` |
| R1 伴生约束 | 契约同步点列 2 处 | 扩为 5 处（后端 + events ps1/sh + reconcile ps1/sh），行号引用改内容化表述 |
| §2 关系图 | 「RUN_COMPLETED / 失败终态」 | 与 §5.3 对齐：失败终态以业务表状态表达，不产事件 |

**不做**：V65 为已应用迁移（Flyway checksum 校验），历史迁移文件不改；其 step / run_id 列注释若需修定应走新增迁移，当前无必要。

### R1（2026-09-04）：step 语义从「从 1 递增」修定为「事件类型槽位」

**背景**：Phase 1 Skill 供电（Harness 能力吸收 Step 1）需落地 `SKILL_RESOLVED`
埋点，其契约位置（§5.2）在 `AGENT_STARTED` 与 `CONTEXT_BUILT` 之间；若按稠密
序列重排插入，存量 `agent_event` 数据（C3 灰度 + 4 模型冒烟 + 回滚演练）将与
新数据同表永久错位（append-only 不迁移）。

**决策**：`SKILL_RESOLVED` 分配新槽位 **step=5**；§3.2 step 语义修定为
「事件类型槽位」。事实依据：

1. `AGENT_COMPLETED`=0 已是既有先例（Turn 端点事件，B2 实现
   `ExecutionResultHandler` 注释明示「Turn 端点事件 step=0」）；
2. `agent_event` 表 `(run_id, turn, step)` 仅为普通索引，无唯一约束
   （V65 DDL，唯一键只有 `event_id` 幂等键）；
3. 全部消费方（B3 对账 `selectLastEventTypeBySubTaskId`、探针 P1/P2/P3）
   按 `create_time DESC, id DESC` 排序，无任何 step 数值断言。

**伴生约束**（实现 `SKILL_RESOLVED` 埋点时必须同一闭环完成）：

- 对账契约同步（**5 处同构映射，必须同一次提交全部修改**，IN\_PROGRESS
  合法末条事件集均需加 `SKILL_RESOLVED`，否则探针在后端合法化后误报 MISMATCH）：
  1. `EventReconciliationServiceImpl` 的 IN\_PROGRESS 期望集合
     （skill 解析瞬态末条事件合法化），其上方 javadoc「step 1-4 递增」表述
     同步为「step 1-5 槽位」；
  2. `verify-c3-events.ps1` P2 的 IN\_PROGRESS 映射；
  3. `verify-c3-events.sh` P2 的 IN\_PROGRESS 映射（ps1 同构移植）；
  4. `verify-c3-reconcile.ps1` 的 matched/mismatches 判定；
  5. `verify-c3-reconcile.sh` 的 matched/mismatches 判定（ps1 同构移植）；

- 埋点**恒发**（`requiredSkills` 为空时 payload 为空数组），保持每个 Turn
  事件骨架完整，Replay 无需特判；

- 埋点位置：`executeOnce`（`SubTaskExecutionServiceImpl`）内 skill 解析完成后、
  `CONTEXT_BUILT`（step=2）之前。

**拒绝的备选**：

| 备选                           | 拒绝理由                                                      |
| ---------------------------- | --------------------------------------------------------- |
| 重排（SKILL\_RESOLVED=2，后续 +1）  | 存量事件同表永久错位；无消费方按 step 排序，收益为零，纯代价（CODE\_STYLE §2.2 无收益不改） |
| 推迟到 Phase 2 随 AgentLoop 统一定义 | Phase 1 Skill 供电等不到；存量越大将来重排代价越高；ADR §5.2 持续「有定义无实现」      |

