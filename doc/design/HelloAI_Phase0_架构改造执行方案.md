# HelloAI Phase 0 架构改造执行方案

> 基于 [HelloAI_架构改造长期思路](./HelloAI_架构改造长期思路.md) 中 P0 各项，经多轮交叉验证后的具体执行方案。
> 本文档对应改造思路中的以下项：P0-1（AgentRuntime）、P0-2（Event Stream）、P0-3（状态机+CAS+Lease）、P0-4（RetryPolicy）。

---

## 核心调整：Event Stream 先行 + 状态机加固并进

Event Stream 作为第一件代码工作先行（contract-first），但状态机加固（CAS+Lease+Watchdog）提到同一 Epic 并行推进，不得排在 Event Stream 之后。

### 为什么调整顺序

原文档把"状态机加固"放在 Event Stream 之后、Runtime 重构之前。校准后逻辑：

- **Event = State Transition** —— 事件本质是状态迁移的记录
- **Replay/Resume 的正确性取决于状态迁移是否正确** —— 若 Event Stream 前半程记录的是加固前的、可能有并发缺陷的转换，后期 Replay 会重放出错误历史

因此：**状态机加固 ≠ Event Stream 串行依赖，而是同一层地基，必须并进同一 Epic。**

校准原则：Event Stream 是第一件"代码工作"，ADR 必须先于它；状态机加固与它并行，且在 AgentRuntime 切换前完成。

---

## 一、三条必须守住的条件

### 条件 1：真正的起点是 ADR-001（Run/Task/Step 模型）

事件需要聚合根。未定义 Run/Task/Step 就先建 `agent_event` 表，会导致事件主键、父子关系反复变更。

```
ADR-001（模型定稿）→ Event Stream 建表 → 双轨埋点
```

### 条件 2：状态机加固并进 Event Stream 的 Epic

CAS + Lease + Watchdog + Reconciler + 崩溃测试 与 Event Stream 互不阻塞，必须同一 Epic 并行。

> ⚠️ **Watchdog 是关键缺失**：Worker 跑长任务（完整构建等）时 lease 会自然过期，被 Reconciler 误判崩溃而抢走任务。AgentRuntime 必须后台续期 `lease_until`（Watchdog）。这是原文档缺失的一环。

### 条件 3："双轨"必须有明确下线开关

双轨 Executor 最容易变成永久状态（旧路径不敢删 = 双倍维护）。开工时定死：

- **Feature Toggle**：`agent.runtime.v2.enabled`，按 `run_id` 灰度
- **下线里程碑**：双轨跑通 N 个生产 Run + 事件对账 100% 一致 → 关旧路径
- **事件对账**：双轨期对比「旧 Executor 产物 vs 新 Runtime 产物」，一致才放量

---

## 二、执行顺序

```
P0-Epic-A「契约 + 正确性」（并行推进，不分先后）
├── A1. ADR-001 Run/Task/Step 模型定稿        ← 真正的起点
├── A2. 状态机加固：CAS + Lease + Watchdog + Reconciler + 崩溃测试
└── A3. RetryPolicy 共享计数器（attempt_total）

P0-Epic-B「Event Stream 先行」
├── B1. agent_event 表 + Outbox 双写（坚持双轨、非 ES）
├── B2. 旧 Executor 埋点发事件（双轨开始，旧路径仍为主）
└── B3. 事件对账：事件 ↔ 业务表 一致性校验

P0-Epic-C「双轨 → Runtime」
├── C1. 定义 AgentRuntime 接口（事件契约即接口）
├── C2. 旧 Executor 包成 Adapter，Feature Toggle 灰度
├── C3. 新 Runtime 接管事件生产 → 下线旧 Executor    ← 双轨结束
└── C4. MDC 全链路 run_id/task_id/step_id
```

**关键依赖**：A 组（契约+正确性）必须在 C3 切换前全部完成；B 组（Event Stream）是贯穿全程的脉络。

---

## 三、Run / Turn / Step 模型（ADR-001 要点）

```
Run #1001
├── Turn #1
│    ├── Step #1
│    ├── Step #2
│    └── Step #3
├── Turn #2
│    ├── Step #1
│    └── Step #2
└── Turn #3
     └── Step #1
```

支撑能力：Resume / Replay / Fork / Retry Step / Replay Step

### 语义定义

| 概念 | 定义 | 生命周期 |
|---|---|---|
| **Run** | 一次用户需求触发的完整执行 = 一个 Plan 的一次实例 | 对应一次 Reviewer 终态 |
| **Turn** | 一次 Agent 完整工作周期，含一次完整 Agent Loop | Rework 会产生新 Turn |
| **Step** | Turn 内的一个原子动作（Tool 调用/LLM 推理/Skill 加载） | 不可再分，append-only |

Resume 从失败 Step 所在 Turn 重放；Fork 复制 Run 的全部事件快照、生成新 RunId。

---

## 四、Event Stream 选型（ADR-003 要点）

| 维度 | 决策 |
|---|---|
| 存储 | `agent_event` 独立事件表 + Outbox 双写 |
| 业务表 | source of truth，**不做 Event Sourcing** |
| 事件表 | 仅作轨迹记录 / 投递，不反向成为状态权威 |
| 投递 | 复用现有 Outbox 四态机（PENDING/SENT/CONFIRMED/FAILED） |

事件类型：

```
RUN_CREATED → TASK_CREATED → TASK_ASSIGNED →
AGENT_STARTED → SKILL_RESOLVED → CONTEXT_BUILT →
TOOL_CALL_STARTED → TOOL_CALL_COMPLETED →
AGENT_COMPLETED → REVIEW_STARTED → REVIEW_REJECTED →
REWORK_STARTED → REVIEW_APPROVED → RUN_COMPLETED
```

---

## 五、状态机加固（A2）要点

### 四件套（缺一不可）

| 机制 | 职责 | 当前代码状态 |
|---|---|---|
| **CAS** | `UPDATE ... SET status=?, version=version+1 WHERE id=? AND version=?` 防并发写冲突 | `SubTask` 有 `@Version` ✅；`AgentExecutionRecord` 无 ❌ |
| **Lease** | `owner + lease_until`，防止多 Worker 重复执行 | `AgentDutyLease` 有 ✅ |
| **Watchdog** | 长任务后台续期 `lease_until`，避免自然过期被误回收 | ❌ **缺失** |
| **Reconciler** | 扫描过期 lease 并回收，覆盖 Worker 崩溃场景 | ❌ **缺失** |

任务字段：`status` / `version` / `owner` / `lease_until` / `updated_at`

状态流转做成显式枚举 + 允许矩阵（`PENDING→RUNNING→SUCCESS/TIMEOUT/RETRY`），不靠散落 `if/else`。

---

## 六、Retry 分层共享计数器（A3）

```
attempt_total（全局上限，如 5）
├── Executor 重试（同错误定责）
└── MQ 重试（DLX + TTL，覆盖进程崩溃）
```

- **两层共享** `attempt_total` 计数器，任一层达上限即停
- 关键：杜绝 "Executor 3 × MQ 3 × Agent 3 = 27 次" 的实际执行

> ✅ **状态（LOG-20260904-007）：本地化落地。** 共享计数器 = `sub_task.attempt_total`（原子 SQL 累加/重置）+ `RetryPolicy.exceedsMax` 纯判定（helloai-common）。覆盖：调度重派 4 入口（dispatchBlocked / redispatchOffline / fallback / assigned-timeout）与 review 自动驳回返工（rework 打回前消耗预算，耗尽转 DEAD_LETTER 人工兜底）；人工驳回（reworkFresh）与死信重派清零预算。未纳入本轮：Executor 内层 retry 与 MQ DLX 共读同一计数器的完整形态（MQ DLX 仍由 outbox retryCount + 死信台账兜底，进程崩溃场景职责独立）。

---

## 七、与整体路线图的衔接

```
Phase 0（本文件）
  └── Event Stream + 状态机 + 双轨 Runtime   ← 当前
Phase 1 Runtime 升级（Skill/Tool/Sandbox 仅接口）
Phase 1.5 Quality Gate（并行，不阻塞 Runtime）
Phase 2 治理深化（Trajectory/Replay/Agent Registry）
Phase 3 AI 动态 Workflow（仅特定节点分支）
```

**Phase 0 期间纪律：不接任何新 Agent、不做新 UI。**

---

## 八、Phase 1-4 路线图（Phase 0 完成后）

### Phase 1 —— Runtime 正确性 + Harness 能力吸收 ★★★★★
1. Skill Registry（先做，在现有 `required_skills` 机制上演进）
2. Tool Registry（依赖 Runtime 抽象稳定后）
3. Session Manager
4. SandboxProvider（仅接口 + RemoteAgent + LocalProcess 两种实现）

### Phase 1.5 —— Quality Gate（并行推进）
- Rule Check + Test Check + LLM Review → PASS / REWORK / HUMAN_REVIEW / BLOCK
- 复用现有双 Reviewer/抽检/放水检测

### Phase 2 —— Agent Governance ★★★★☆
- Audit / Trajectory / Replay / Resume / Fork / Metrics
- 快照机制（事件流长了要快照）

### Phase 3 —— Agent Fleet ★★★★☆
- Agent Registry / Capability / Health / Routing / Historical Success

### Phase 4 —— AI-native Workflow ★★★☆☆
- 仅允许 LLM 在 Workflow 特定节点做动态分支，不全量 LLM 生成编排脚本

---

# 附录：落地审查 —— 4 个坑点 + 代码现实对照

> 审查依据：对照 HelloAI 当前代码实现，逐条验证架构改造方案的可行性。

---

## 坑 1：Event Stream 技术选型 —— 别做成纯 Event Sourcing

### 代码现实

| 现有机制 | 状态 | 说明 |
|---|---|---|
| `AgentCommandOutboxEvent`（`agent_command_outbox` 表） | ✅ 成熟 | 已有 PENDING→SENT→CONFIRMED/FAILED 四态机，`retryCount` + `nextRetryTime` 指数退避 |
| `OutboxRelayTask`（`helloai-job`） | ✅ 成熟 | 周期扫描 Outbox 行 → 投递 MQ，已跑通 publisher-confirm + 幂等 |
| `DlxAlertConsumer` + `mq_dead_letter_archive` | ✅ 成熟 | 死信台账 + 告警，`requeue=false` 防循环 |
| `AgentExecutionRecord`（扁平生命周期） | ⚠️ 待升级 | 单次执行一行，PENDING→RUNNING→SUCCESS/FAILED/TIMEOUT，无事件溯源 |
| `TaskExecutionRecordEntity`（产出快照） | ⚠️ 待升级 | 只存 EXECUTION_RECORD 协议的 SUMMARY/KEY_DECISIONS/DOWNSTREAM_NOTES/DELIVERABLES |
| `ActivityLog`（独立日志） | ⚠️ 待收敛 | agentId/action/level/source/subTaskId/detail，与执行记录不联动 |

### 结论

**方案 C（Outbox 表 + 轮询/Notify 投递）是最佳路径**，理由：
1. 你已有成熟的 Outbox 四态机 + publisher-confirm + 三层幂等，Event Stream 可以直接复用同一套机制
2. 不要上纯 Event Sourcing（"事件即唯一状态源"），应采用 **"业务状态表 + 独立 append-only 事件表"** 的双轨模型
3. 新建 `agent_event` 表（append-only，以 `run_id + turn + step + event_type` 为主键维度），业务状态仍留在 `task`/`sub_task`/`agent_execution_record` 表中
4. 后续如需跨服务实时订阅，可在 Outbox 表上挂 Debezium CDC 升级

---

## 坑 2：CAS 单点不够 —— 必须 CAS + Lease + Reconciler 四件套

### 代码现实

| 机制 | 状态 | 代码位置 |
|---|---|---|
| `@Version` 乐观锁 | ✅ 已有 | `SubTask.version`（`@Version`），`TaskRunningSpecEntity.version`（`@Version`） |
| `AgentDutyLease` 租约 | ✅ 已有 | `AgentDutyLeaseServiceImpl.startLease/renewLease/closeLease`，含 `expireTime` |
| `RedissonClient` + `RLock` 分布式锁 | ✅ 已有 | `SubTaskReviewServiceImpl` 使用 `RLock`，显式 `leaseTime` 禁看门狗 |
| **过期回收扫描器（Reconciler）** | ❌ **缺失** | 无 `scanExpiredLeases` 或僵尸任务回收机制 |
| `AgentExecutionRecord` 的 CAS | ❌ **缺失** | 执行记录表无 `version` 字段，状态更新靠 `lambdaUpdate().eq(status, oldStatus)` |

### 结论

**CAS 只防并发写冲突，不防持有者崩溃。** 当前代码里：
- `SubTask` 有 `@Version` 乐观锁 ✅
- `AgentDutyLease` 有 `expireTime` + `renewLease` ✅
- 但**没有 Reconciler**：Worker 崩溃后，`RUNNING` 状态的任务无人回收，会永久卡死
- `AgentExecutionRecord` 没有 `version` 字段，状态 CAS 更新靠 `eq(status, oldStatus)` 做条件更新，不够严谨

**必须补的：**
1. `AgentExecutionRecord` 加 `version` 字段 + CAS 更新
2. 新增 **Reconciler 定时扫描器**：定期捞 `lease_until < now` 的僵尸任务，CAS 抢回 PENDING
3. 状态流转做成显式枚举 + 允许矩阵（`PENDING→RUNNING→SUCCESS/TIMEOUT/RETRY`），不靠散落 `if/else`

---

## 坑 3：Retry 的"单一权威"原则 —— 必须共享计数器

### 代码现实

| 重试层级 | 当前计数器 | 上限配置 |
|---|---|---|
| Outbox 投递重试 | `AgentCommandOutboxEvent.retryCount` | `AgentCommandOutboxRelayProperties.maxRetry` |
| 子任务重分配 | `SubTask.reassignAttemptCount` | `helloai.dispatch.max-reassign-attempts`（默认 5） |
| 子任务返工 | `SubTask.reworkCount` | 业务逻辑控制 |
| 子任务超时 | `SubTask.timeoutCount` | 业务逻辑控制 |
| MQ 死信（DLX） | `DlxAlertConsumer` 处理 | `mq_dead_letter_archive` 台账 |

**关键发现：这 5 个计数器互不共享，各自独立计数。** 文档担心的"27 次实际执行"在当前代码中理论上可能发生——Outbox 重试 3 次 × MQ requeue 3 次 × 重分配 5 次 = 理论最大 45 次。

### 结论

**"单一权威"的准确表述应该是：对同一错误原因，定义唯一责任层级；但允许不同层级分别重试不同错误类型。** 修正方案：

1. 把 `retry_count` 放进消息 header + 共享表，**两层共享同一个计数器**
2. 每一层在重试前检查 `attempt >= max` 就放弃，而不是每层都"无条件重试 N 次"
3. 保留 MQ DLX 层（消费者进程崩溃时 Executor 层覆盖不了），但 DLX 也读同一计数器

```
Executor retry: max 3, backoff → 超限
MQ DLX (TTL 递增): 共享计数器已达 3 → 直接 DLQ/人工
```

---

## 坑 4：Sandbox 是"伪 P0" —— 只做接口，不做完整实现

### 代码现实

| 机制 | 状态 | 说明 |
|---|---|---|
| `ExecutionEnvironment` 接口 | ❌ 不存在 | 无任何执行环境抽象 |
| `SandboxProvider` 接口 | ❌ 不存在 | 无沙箱抽象 |
| 远程 Agent 执行 | ✅ 已有 | Qoder/Trae 在自己环境执行，通过 MCP/REST 调用 |
| 本地进程执行 | ❌ 不存在 | 无 subprocess 封装 |

### 结论

**当前真正缺的不是 Sandbox 抽象，而是"谁在生产执行代码"的明确定义。** 当前远程 Agent 在自己环境跑，本机/本集群的隔离压力并不迫切。建议：

- **P0/P1：只做 `ExecutionEnvironment` 接口 + 两个实现：**
  - `RemoteAgentEnvironment`（适配现有 Qoder/Trae 的 MCP/REST 调用）
  - `LocalProcessEnvironment`（简单 subprocess，够开发自测）
- **P2 再做 DockerSandbox**（且只在确定"要在本集群执行代码"之后）
- **K8sSandbox 放到 P3**，不写进近期计划

---

## 术语精确化

### 四层 → 五职责三部署

- **Planner**：生成 Plan/DAG（What）
- **Workflow Engine**：解释 DAG、算依赖/并发/超时/补偿（When/Order）——**必须与 Scheduler 分开**
- **Scheduler**：按 Lease/CAS 把 Task 派给具体 Worker（Who/Where）
- **Agent Runtime**：单个 Task 的执行（How）
- **Quality Gate**：验收（Is correct）

### Run / Turn / Step 语义定义

| 概念 | 定义 | 生命周期 |
|---|---|---|
| **Run** | 一次用户需求触发的完整执行 = 一个 Plan 的一次实例 | 对应一次 Reviewer 终态 |
| **Turn** | 一次 Agent 完整工作周期，含一次完整 Agent Loop | Rework 会产生新 Turn |
| **Step** | Turn 内的一个原子动作（Tool 调用/LLM 推理/Skill 加载） | 不可再分，append-only |

Resume 从失败 Step 所在 Turn 重放；Fork 复制 Run 的全部事件快照、生成新 RunId。

### 明确不用 Event Sourcing

**HelloAI 采用"业务状态表 + 独立 append-only 事件表"的双轨模型，不采用"事件即唯一状态源"的纯 Event Sourcing。** 事件表用于轨迹、审计、重放；业务表仍是 task/execution 的权威状态。