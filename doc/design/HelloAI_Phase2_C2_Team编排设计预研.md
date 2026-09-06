# HelloAI Phase 2 C2：Team 编排设计预研（N-002）

> 主轴：为差距表 N-002（Team 编排，TODO P1）产出**可实施级设计草案**（P2-C 设计预研，与 C1 Workflow 同批）。核心结论：**Team = "命名可复用的 Agent 组合 + 槽位填充参数源"，不是第二调度器/第二控制面**——Team 只做组合声明与白名单展开，派发/执行/收敛完全复用现有调度链（`AgentSelector`/`ResilientDispatcher`）。
>
> 文档性质：设计预研（草案），表结构与接口为实施前方向；§9 决策点待用户审阅拍板。
>
> **状态**：已实施（2026-09-06，S1~S4 代码完成 + S5 文档收口；Team/成员 CRUD + 生命周期、agent_policy.teamId 展开快照、节点级差异化派发、验证全部落地，见 §5 路线 + R2 实施记录）。

---

## §0 设计输入与约束

### 0.1 差距表 N-002 要求

```text
现状：Agent 已支持角色（PLANNER/EXECUTOR/REVIEWER）、状态、调度；但 Agent 与 Team 之间尚未形成稳定模型。
缺少：Team / Team Member / Team Role / Team Policy / Team 生命周期。
原则：Team 不应复制 Scheduler；Team 是「Agent 的组织 / 编排抽象」。
优先级 P1 / 状态 TODO
```

### 0.2 S0 事实基线（2026-09-06 代码盘点）

1. **Agent 模型扁平三角色**：`agent` 表（V1 L89-140）——`role` 是 VARCHAR CHECK 约束（V30 收敛为 PLANNER/EXECUTOR/REVIEWER，SYSTEM 仅系统级事件），无 `agent_role`/`agent_capability` 独立表；能力= `capabilities` JSONB、技能声明= `skills` JSONB[]（V47）、标签= `labels` JSONB；含心跳三件套与 N11 回退字段。
2. **`task.agent_policy` 是任务级单值 JSONB（V47）**：`TaskAgentPolicy` 解析键 `plannerAgentId` / `executorAgentIds`（白名单 `List<Long>`）/ `reviewerAgentId` / `fallbackPolicy`（AUTO/RESTRICTED/NONE）/ `difficulty`（LOW/MEDIUM/HIGH）。**表达不了节点级（sub_task 级）agent 绑定与差异化组合**——这是 N-002 的核心缺口（C1 预研已确认）。
3. **`sub_task` 无 role 列**：只有 `assigned_agent_id`（V23 重命名）；角色由被指派 Agent 的 role 推导（`SubTaskDispatchServiceImpl` L237-241 注释明示）。写入方集中在 `SubTaskServiceImpl.changeStatus/assignNext/claim/rework/resetToPendingForDispatch`。
4. **调度链已有完整匹配骨架**：`dispatchPendingSubTaskAuto(subTaskId, role)`（ready 守卫 → 重分配熔断 → `AgentSelector.pickPreferred(role, constraints)` → `assignNext`）；`ResilientDispatcher`（`@CircuitBreaker("agentDispatch")` + per-agent 熔断 + fast-fail + fallback `pickAlternative`）；`AgentSelector` 过滤链（白名单 + 技能 AND / 非 SLEEPING / 心跳新鲜度 / 并发额度 / ACTIVE / 凭证 / 熔断）+ 排序（dutyRank → qualityRank → score）；重分配超限（V64 预算 5）→ DEAD_LETTER；手动改派 API + `QuickDispatchDialog`（agentIds[] 批量 create）。
5. **代码层完全无 Team 概念**：全库无 `team`/`agent_group` 类/表/配置；最接近的 `module` 表是任务内子任务分组，**不是 Agent 组合**。
6. **Planner 选择已支持"钉住"**：`PlannerAgentPicker.pickForTask` 优先级 = `agent_policy.plannerAgentId` → 澄清会话 `planner_agent_id` → autoPick（PLANNER + API_KEY_LLM + ACTIVE + 空闲最少）。

### 0.3 外部借鉴（架构参考 L70/103/447-453）

- **AgentTeams-main（Team 委托模型）**：Manager→Leader→Worker 分层、team/workflow/coordinator 为未来编排方向；适合借鉴"命名组合 + 委托职责"的抽象，但**不照搬其容器编排与 IM 驱动交互**。
- **Vibe-Skills-main（Root/Child Authority）**：层级化权威边界，为 Team 成员变更/槽位填充提供治理模型参考。
- **现状调度链复用点**：`DispatchConstraints`（allowedAgentIds + requiredSkills）/ `AgentSelectionConstraints` 可作为"槽位填充"的天然扩展点——Team 展开结果直接喂现有约束，零改调度内核。

---

## §1 任务定位

**preflight 四问**：改什么——新增 Team 组合模型（表 + CRUD + 生命周期）+ `agent_policy.teamId` 绑定与展开 + （可选）节点级槽位；为什么——差距表 N-002 P1 TODO，且 C1 决策 4 已把"节点级差异化"显式划给 N-002（现状 `task.agent_policy` 任务级单值表达不了）；类型——补功能（新抽象，小闭环）；不做什么——**不做第二调度器/第二控制面**（Team 不派发、不持有运行时对象）、不做嵌套 Team 委托层级（首版平面组合）、不改执行链 6 步语义、不复制 Scheduler。

---

## §2 范围边界

**做**：

1. `team` 表（命名组合 + 生命周期）+ `team_member` 表（agent 引用 + 槽位角色声明）
2. Team / 成员 CRUD（成员校验：ACTIVE agent、角色白名单；变更 CAS）
3. `task.agent_policy` 新增 `teamId` 键：任务创建 / Workflow 实例化时按 Team **展开快照**为 `executorAgentIds`/`reviewerAgentId`/`plannerAgentId`
4. （决策 3 拍板后）节点级槽位：sub_task 级"要求某角色/某 Team 成员"的派发约束扩展
5. 口径登记（Team 与 Scheduler 边界 / 成员变更语义 / 与 Workflow 单向依赖）+ 定向单测 + 全量回归 + 差距表 N-002 处置 + LOG

**不做**（明文边界）：

- 不建第二调度器 / Team 不参与派发决策（复用 `AgentSelector` 现链）
- 不做嵌套 Team / Manager-Leader-Worker 委托层级（架构参考第三阶段远期项）
- 不改 `sub_task` 既有状态机与执行链语义
- 不做 Team 级 SLA/配额/审计聚合（超出"槽位填充"范围，YAGNI）
- 不预读 N-003 Browser / 不感知 N-006 调度权重

---

## §3 设计（决策草案，待拍板）

### 3.1 表结构草案

```sql
team
  id BIGSERIAL PK
  name VARCHAR UNIQUE NOT NULL           -- 命名组合（如 "前端专项组"）
  description VARCHAR
  status VARCHAR NOT NULL                -- DRAFT / ACTIVE / ARCHIVED
  created_by BIGINT
  -- 审计列继承 BaseEntity

team_member
  id BIGSERIAL PK
  team_id BIGINT NOT NULL REFERENCES team(id)
  agent_id BIGINT NOT NULL REFERENCES agent(id)
  slot_role VARCHAR NOT NULL             -- PLANNER / EXECUTOR / REVIEWER（槽位角色声明）
  weight INT DEFAULT 100                 -- 组内优先级（可选，默认 100）
  -- 唯一约束 (team_id, agent_id)；一个 agent 可属多 team、可在 team 内多角色？
  -- 决策 1 待拍板：agent 是否可在一 team 内多槽位
```

- **零侵入 agent 表**：组合关系全在 `team_member`，agent 表不动。
- **复用现状选人**：Team 展开结果 = `DispatchConstraints.allowedAgentIds`（+ 槽位 role），直接喂 `AgentSelector`，**不改调度内核**。

### 3.2 `agent_policy.teamId` 绑定

- `TaskAgentPolicy` 新增键 `teamId`（Long，可选）。
- **创建/实例化时展开快照**（倾向，决策 2 待拍板）：任务创建或 Workflow 实例化时，按 Team 的 ACTIVE 成员展开写入 `executorAgentIds`/`reviewerAgentId`/`plannerAgentId`——已建任务的派发不受成员后续变更影响（与 C1 "一次性物化"同构，防运行期漂移）。
- **单向依赖**（对齐 C1 决策 4）：Workflow 不引用 Team；Workflow 实例化 `params.teamId` 可作参数源填充 `task.agent_policy`（Team 可喂 Workflow，Workflow 不依赖 Team）。

### 3.3 节点级槽位（决策 3 待拍板）

现状：`sub_task` 无 role 列，调度 `dispatchPendingSubTaskAuto(subTaskId, role)` 的 role 由调用方显式传入；Workflow 实例化时节点已声明 role（在 `context.workflow.spec.role`）。

候选（二选一或组合）：

- **方案 A（零 DDL，推荐）**：不做 `sub_task.role` 列；"节点级差异化"通过**创建即指定**表达——实例化/建子任务时按节点 role 从 Team 展开候选集，直接 `assignNext` 或写入 `assigned_agent_id`；派发仍走现状 role 参数链。Team 只需提供"某 role 的成员集"查询。
- **方案 B（加列）**：`sub_task` 新增 `role`/`team_member_id` 槽位列，调度按槽位约束过滤。成本高、侵入 `changeStatus`/派发全链，首版不建议（YAGNI）。

### 3.4 Team 生命周期（状态机）

```text
DRAFT --发布(成员快照校验通过)--> ACTIVE --停用--> ARCHIVED
  │                              │
  └--编辑成员/描述----------------┘（DRAFT 可自由改；ACTIVE 改成员走 CAS + 版本号？
```

- **决策 4 待拍板**：ACTIVE 后成员变更语义——（a）允许变更，已建任务不受影响（快照隔离，倾向）；（b）发布后不可变（对齐 Workflow 版本不可变哲学）。
- **成员校验**：agent 必须 ACTIVE（管理态）；角色必须白名单；Team 至少 1 名 EXECUTOR。

### 3.5 Team 与 Scheduler 边界（口径登记）

```text
Scheduler：选人、发命令、登记状态（Who/Where）——现状不变
Team：      命名组合 + 槽位声明（What 的组合抽象）——只做数据与展开，零运行期触发
边界：      Team 不持有运行时对象、不参与派发、不聚合 SLA；Agent 在线状态由现有过滤链处理
```

---

## §4 验证方案

| # | 项 | 落点 |
|---|---|---|
| 1 | Team/成员 CRUD + 校验 | TeamService 单测（成员必须 ACTIVE、角色白名单、至少 1 EXECUTOR） |
| 2 | 生命周期状态机 | Team 发布/停用/归档 + 变更 CAS 冲突单测 |
| 3 | teamId 展开快照 | 任务创建/实例化后 agent_policy 落白名单断言（成员后变不影响已建任务） |
| 4 | 槽位填充复用调度 | 集成：Team 绑定任务 → 派发落 Team 白名单内 agent（复用 AgentSelector） |
| 5 | 节点级（方案 A） | 按节点 role 从 Team 选人断言 |
| 6 | 全量回归 | core + api 既有回归全绿（调度/重派/重试/收敛链不受影响） |

---

## §5 实施路线建议（骨架先行）

```text
N-002-S1 表 + 实体 + Team/成员 CRUD + 生命周期
N-002-S2 agent_policy.teamId 键 + 展开快照（创建/实例化）
N-002-S3 节点级槽位（决策 3 拍板后）
N-002-S4 验证：§4 六项单测 + 全量回归
N-002-S5 文档回填（差距表 N-002 处置 + LOG）
```

---

## §6 风险与边界

| 风险 | 缓解 |
|---|---|
| 退化成第二控制面 | D 定位"组合参数源"，零运行期触发；复用 AgentSelector 现链 |
| 与 Workflow 重叠 | C1 决策 4 切分：Workflow 管流程形状、Team 管槽位填充；单向依赖 |
| 成员变更影响已建任务 | 倾向展开快照隔离（决策 2）；如需实时引用再评估 |
| 嵌套 Team 过度设计 | 首版平面组合；层级化列入架构参考第三阶段远期 |
| 调度链回归风险 | 复用 DispatchConstraints，零改调度内核；S4 全量回归 |

---

## §7 决策拍板记录（2026-09-06 已按草案倾向拍板）

| # | 决策点 | 草案倾向 | 待拍板 |
|---|---|---|---|
| 1 | Team 模型 | 新 `team` + `team_member` 两表；agent 表零侵入；agent 是否可在 team 内多槽位 | ☐ |
| 2 | 绑定方式 | `agent_policy.teamId` + **创建/实例化展开快照**（隔离成员变更） | ☐ |
| 3 | 节点级槽位 | **方案 A（零 DDL）**：节点 role 从 Team 展开候选集直接指定，不加 sub_task 列 | ☐ |
| 4 | ACTIVE 成员变更 | 允许变更 + 快照隔离已建任务（不锁已建任务） | ☐ |
| 5 | 首版边界 | 平面组合、不做嵌套 Team / 委托层级 / Team 级 SLA | ☐ |

---

## 修订记录

### R1（2026-09-06）：设计预研草案产出

- **背景**：P2-C 设计预研（与 C1 同批），N-002 Team 编排。
- **核心**：D=组合参数源（非第二控制面）/ teamId 展开快照 / 槽位填充复用 AgentSelector 现链 / 与 Workflow 单向依赖（对齐 C1 决策 4）。
- **边界**：平面组合首版；嵌套 Team、Team 级 SLA 列为远期；零改调度内核。
