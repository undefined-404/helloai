# HelloAI Task-Team 与附件可见性设计

> 状态：**待拍板**（schema 定稿后再动 Flyway —— 迁移一旦执行不可回改）
> 日期：2026-10-02
> 触发：真机混合执行测试（task `2106009817950142465`）暴露「外部 agent 无法读取同任务其他子任务产出」

---

## 0. 一句话结论

把现在**混在一起的两件事**拆开：`team` 只管**选人**（跨任务复用），新增 `task-team` 管**授权**（任务级、持久化）；附件读权限从「子任务归属」改为「**可见性枚举 × 任务成员关系**」，判据不写死层级。

---

## 1. 现状取证（代码 + DB 实证）

| 事实 | 证据 |
|---|---|
| `team` / `team_member` 表存在，但**均 0 行**（建了从未启用） | `select count(*)` = 0 |
| Team 唯一用途 = **创建期选人模板** | `task.agent_policy.teamId` → `TeamPolicyExpander.expand()` → `executorAgentIds` / `plannerAgentId` / `reviewerAgentId` |
| 展开后 Team 即退场，**运行时无团队概念** | 源码注释：「teamId 保留为来源元信息，调度只读展开后的白名单/单值键」 |
| **读权限是 sub_task 级硬隔离** | `AttachmentController.assertSubTaskReadable()`：agent 通道必须 `agentId.equals(subTask.assignedAgentId())`，否则 403 |
| 该硬隔离是**事后安全修复** | 注释标明 G-014 T04b，原缺陷＝「任意有效 Agent API Key 可凭 attachmentId 读取任意子任务附件正文」 |
| `attachment` **无 `task_id`** | 仅可空 `sub_task_id`（现有 93 个附件 100% 有归属）；`file_type` 仅格式（markdown/log/other） |
| 外部 agent **无读取附件的 MCP 工具** | 13 工具中仅 `uploadArtifact`（只上传） |
| 掉线判定与改派**已有基础** | `AgentSelector.isHeartbeatFresh()`（CLI_CLIENT 超 `offlineMinutes` 默认 5 分钟判离线；API_KEY_LLM 恒新鲜）；`SubTaskDispatchService.dispatchBlockedSubTask` / `redispatchInProgress` / `redispatchDeadLetter` |

**根因**：当前把「选人」与「授权」压在一个概念里 —— team 展开完就丢，导致运行时**没有授权依据**，只能退化成「按子任务归属硬隔离」。

---

## 2. 职责分层（正交，不得混用）

| | 管什么 | 作用域 | 现状 |
|---|---|---|---|
| **team** | 「派谁」（选人模板 / 能力组合） | **跨任务**（`uk_team_name` 全局唯一，无 task_id） | 有，仅创建期展开 |
| **task-team** | 「能看什么」（授权边界） | **任务级**（每任务独立） | **缺失** |

> **⚠️ team 绝不能当授权边界。** 它是跨任务复用的：若用 team 作授权判据，等于「进了这个模板就能读**所有用过该 team 的任务**的附件」＝ 跨任务过度授权。
> **这正是 task-team 必须单独存在、且必须绑定到具体任务的根本原因。**

---

## 3. `task-team` —— 持久化成员表

### 3.1 为什么选持久化（而非推导）

用户明确：需应对**分布式场景**（网络中断、系统崩溃）。持久化表的收益：
- 崩溃/重启后成员身份**不依赖内存或实时推导**，直接可查
- **可审计**：谁、何时、以什么方式入队
- 支持**提前授权待命成员**（推导式做不到）

### 3.2 表结构（草案，迁移号 V98）

```sql
CREATE TABLE IF NOT EXISTS task_agent_member (
    id           BIGINT      NOT NULL PRIMARY KEY,
    task_id      BIGINT      NOT NULL REFERENCES task(id),
    agent_id     BIGINT      NOT NULL REFERENCES agent(id),
    join_source  VARCHAR(32) NOT NULL,   -- ASSIGNED / CLAIMED / REASSIGNED / MANUAL / REBUILT
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE / LEFT
    join_time    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    leave_time   TIMESTAMPTZ,
    create_by    VARCHAR(64) NOT NULL DEFAULT '',
    update_by    VARCHAR(64) NOT NULL DEFAULT '',
    create_time  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT    NOT NULL DEFAULT 0,
    remark       VARCHAR(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_agent_member ON task_agent_member(task_id, agent_id);
CREATE INDEX IF NOT EXISTS idx_task_agent_member_agent ON task_agent_member(agent_id, status);
```

### 3.3 写入策略 —— 事件驱动派生，**不做双写**

避免「成员集 vs `assigned_agent_id` 双写漂移」。原则：**唯一权威源是子任务归属变更，成员表是其派生快照**。

- 在子任务归属变更的四类入口登记（幂等 upsert `ON CONFLICT (task_id, agent_id) DO UPDATE`）：
  1. 任务拆解分配（`ASSIGNED`）
  2. 外部 agent 认领（`claimSubTask` → `CLAIMED`）
  3. 改派（`redispatchInProgress` / `dispatchBlockedSubTask` → `REASSIGNED`）
  4. 死信人工指派（`redispatchDeadLetter` → `ASSIGNED`）
- **改派不写 `leave_time`**：按需求「改派进来的能看、原来干过的也能看」，**加入过即成员**（`status` 保持 ACTIVE）。
- **自愈**：提供重建/对账入口，从 `sub_task.assigned_agent_id` ∪ `agent_execution_record.agent_id`（追加型历史）重建，覆盖「事件丢失 / 崩溃中断写入」。成员表可查（快），权威源可重建（不怕漂移）。

---

## 4. 附件可见性

### 4.1 不加 `task_id`，加**可见性枚举**（迁移号 V99）

```sql
ALTER TABLE attachment ADD COLUMN IF NOT EXISTS visibility VARCHAR(16) NOT NULL DEFAULT 'TASK';
-- PERSONAL / TASK / PUBLIC
```

| 取值 | 谁可读 | 语义 |
|---|---|---|
| `PERSONAL` | 仅上传者 | 上传者**离开团队后仍可读自己上传的** |
| `TASK` | 上传者 + 附件所属**根任务**的 task-team 全体成员 | **默认**；本次改造的目的 |
| `PUBLIC` | 所有 ACTIVE agent | 慎用，仅显式设置 |

> 说明：`PERSONAL` 与 `TASK` 都有意义 —— 上传者通常情况下属于 task-team，但**被改派换下后**两者语义分叉（TASK 会失权，PERSONAL 不会）。

### 4.2 判定入口**单一化**（不写死层级）

```java
// 唯一判定入口：所有读附件路径都走它
AttachmentAccessPolicy.canRead(Long agentId, Attachment att)
```

内部两步：

1. `ownerTaskId = rootTaskIdOf(att)` —— **解析"根任务"而非"直接父级"**
   - 现在：`att.sub_task_id` → `sub_task.task_id`
   - **未来层级加深（子任务的子任务）只需改这一个函数**，权限入口零改动
2. 按 `att.visibility` 判定：
   - `PERSONAL` → `agentId == uploaderOf(att)`
   - `TASK` → `TaskMemberPort.isMember(ownerTaskId, agentId)`（或 `agentId == uploaderOf(att)`）
   - `PUBLIC` → agent 存在且 ACTIVE

### 4.3 ⚠️ 纠正：可见性**不要绑"角色"**

原始设想提到「绑到角色，或者可见性身上」。**绑可见性枚举 ✅，绑角色 ❌**：

- 角色（PLANNER / EXECUTOR / REVIEWER）是 agent 的**全局属性**，同一 agent 可同时是多个任务的 REVIEWER
- 用角色作授权判据 ⇒ 「只要是 REVIEWER 就能读」＝ **跨任务过度授权**
- **与"team 不能当授权边界"是同一个坑**：判据必须落在**任务级成员关系**上，不能落在全局属性上

---

## 5. 面向未来的"不写死"设计（回应层级/跨任务演进）

| 未来演进 | 本设计的应对 |
|---|---|
| **二次分解**：附件下沉到「子任务的子任务」，层级不固定（类机构/菜单树） | 判据只依赖 `rootTaskIdOf(att)` 抽象 + 成员关系，**与层级深度解耦**；层级加深仅需扩展该解析函数 |
| **大需求层层分解**：一个需求 → 多个主任务 | `rootTaskIdOf` 预留可扩展为「需求域 / 任务组」；`canRead` 入口不变 |
| 可见性策略扩展 | 走**枚举 + 策略**分支，不写 if-else 硬编码名单 |

> 设计原则：**判据引用抽象（根任务 / 成员关系 / 可见性枚举），绝不引用具体 `task_id` / `sub_task_id` 常量。**

---

## 6. 安全边界（必须守住）

放宽读权限**不得**回退到 G-014 T04b 之前的状态。三重限定同时满足：

1. 请求者**是通过判据的可见对象**（task-team 成员 / 上传者本人 / PUBLIC 下 ACTIVE）
2. 目标附件**属于同一声明范围内的任务**
3. 该 agent **已启用**对应工具（沿用 `assertToolEnabled`）

并新增**跨子任务附件读取审计**（谁读了非自己名下子任务的附件），纳入可观测。

---

## 7. 分期落地

| 期 | 内容 | 依赖 |
|---|---|---|
| **P1** | V98 `task_agent_member` + 成员派生写入（四类入口）+ 重建对账；V99 `attachment.visibility`；`AttachmentAccessPolicy.canRead` 单一入口；`assertSubTaskReadable` → 走新入口 | 本设计定稿 |
| **P2** | 新增 MCP 工具 `listTaskArtifacts(taskId)` / `getUpstreamArtifact(subTaskId, fileName)` —— 解开 4000 字符天花板的「按需拉全文」通道 | P1 |
| **P3** | Team 运行时化（`team` → `task-team` 显式绑定，支持"待命成员提前授权"） | P2 |

---

## 8. 已确认决策（2026-10-02 拍板）

1. **历史附件（93 个）的 `visibility`** = **`TASK`**。
   - **实现方式：无需显式回填、无需清库。** PostgreSQL 11+ 对「加列 + 常量 DEFAULT」是**纯元数据操作**（不重写表），存量行自动读作默认值。本机为 **PG 16.4**，故 `ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'TASK'` 一步即完成回填。
2. **`PUBLIC` 作用**：字段**预留**（枚举含该值、CHECK 允许），但**不提供任何设置入口**，避免误用。
3. **重建对账触发方式** = **启动一次性**（`ApplicationRunner`，幂等）。
   - 理由：与设计意图「自愈」一致、零人工步骤、多次启动安全；管理端点作为 P3 可选项，不在 P1 范围。
4. **不做「离队即失权」**：保持「**加入过即成员**」，改派**不写 `leave_time`**（符合"改派的也能看"的诉求）。

### 8.1 数据策略结论：**不需要清库**

- `attachment.visibility` 由 PG 版本特性自动回填（见上）。
- `task_agent_member` 对**存量任务**由第 3 条的**启动一次性重建**补齐 —— 这是唯一需要"补历史"的地方，且可由权威源重建，**无需人工清库或重建任务**。
- 结论：**保留现有库与 MinIO 数据不动**。清库会丢失 `task_running_spec` 9 条迁移成果与本次分析所用的 task `2106009817950142465` 取证样本，收益为负。
- 若后续确需重来：开发阶段可随时 `TRUNCATE` 业务表 + 清 MinIO bucket 后重建任务，成本很低 —— 但**不是本方案的必需步骤**。
