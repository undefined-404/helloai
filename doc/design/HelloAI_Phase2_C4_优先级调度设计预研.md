# HelloAI Phase 2 C4：优先级调度设计预研（N-006）

> 主轴：为差距表 N-006（优先级调度，TODO P2）产出**可实施级设计草案**（P2-C 设计预研，与 C1 Workflow / C2 Team / C3 Browser 同批）。核心结论：**当前调度是"即时选人"（PENDING+ready 立即派发，无排队层），`sub_task.priority` 自 V1 存在但调度零消费，task 表无 priority 字段**——N-006 最小闭环 = **让优先级进入派发排序（Task 继承 + 子任务按优先级出队近似）+ starvation 防护（等待时长 aging）**，不引入显式队列/运行时抢占/第二控制面。
>
> 文档性质：设计预研（草案），表结构与接口为实施前方向；§7 决策点待用户审阅拍板。
>
> **状态**：已实施（2026-09-06，S1~S4 代码完成 + S5 文档收口；task.priority 字段 + 优先级继承三入口 + 候选取数排序 + aging，见 §5 路线 + R2 实施记录）。

---

## §0 设计输入与约束

### 0.1 差距表 N-006 要求

```text
现状：调度基础能力已存在，但缺少：优先级队列 / starvation 防护 / 抢占 / 恢复 / 优先级继承 / 调度公平性策略。
目标：Task Priority ↓ Queue ↓ Scheduler ↓ Agent。
优先级 P2 / 状态 TODO
```

### 0.2 S0 事实基线（2026-09-06 代码盘点）

1. **即时选人，无排队**：`dispatchPendingSubTaskAuto`（PENDING + ready 守卫）→ `AgentSelector.pickPreferred` → `assignNext`，中间无队列缓冲/按优先级出队/抢占。
2. **`sub_task.priority` 存在但不消费**：V1 L205 `priority VARCHAR(10) DEFAULT 'MEDIUM'` + `idx_sub_task_priority` 索引；值域 HIGH/MEDIUM/LOW（`PlannerDecomposeAsyncServiceImpl.VALID_PRIORITIES` + `normalizePriority` 非法降级 MEDIUM）；写入方仅 LLM 拆解 + `SubTaskController` 手工建子任务；**调度链/取数 SQL 零读取**（仅 dashboard 展示）。
3. **task 表无 priority**：仅 `sla_minutes`（V48）；`sub_task.deadline`（V1 + `idx_sub_task_deadline`）由 confirmPlan 按 SLA 下发，但**不驱动调度排序、不驱动超时巡检**（巡检用 update_time）。
4. **调度取数全先来先得**：`selectTimedOutAssigned`（ORDER BY update_time ASC）、`selectStalePending*`（ORDER BY id ASC）——无任何按 priority 排序的 SQL。
5. **`AgentSelector.resolveComparator` 是多级比较器（天然扩展点）**：dutyRank（值班）→ accessTypeRank（preferExternal）→ qualityRank（质量档位×weight）→ score（降序）；过滤链已含白名单/心跳/并发额度/熔断。
6. **抢占/恢复为零**：无"高优先级打断执行中任务"机制；`sub_task` 状态机无 INTERRUPTED（历史设计未落地）；恢复仅 `PAUSED → resume` + 故障型重派链（redispatchBlocked/Offline/AssignedTimeout/InProgress/DeadLetter + 统一熔断 + DEAD_LETTER 死信池）。
7. **`agent_inbox.priority`（URGENT/HIGH/NORMAL/LOW）仅通知展示**，不驱动调度。
8. **历史资产**：`HelloAi Agent 任务调度优先级机制设计文档.md`（CRITICAL/HIGH/NORMAL/LOW + PriorityMessageQueue + INTERRUPTED/PAUSED 状态机，Python 伪代码）+ 架构参考 §1.4/§3.7（控制命令 STOP/PAUSE/REPLAN/RESUME/APPROVE）；**约束：落 DB 而非纯内存队列**。

### 0.3 外部借鉴与红线

- 架构参考 §3.7 控制命令层（STOP/PAUSE/REPLAN/RESUME/APPROVE）为抢占/恢复的远期形态；本项目红线 = **落 DB、单一控制面、不建第二调度系统**。
- 差距表 N-006 六项（优先级队列/starvation/抢占/恢复/优先级继承/公平性）需**分首版与远期**：首版做"派发优先级 + aging"，抢占/恢复显式划远期（需 INTERRUPTED 状态 + 恢复快照，超出小闭环）。

---

## §1 任务定位

**preflight 四问**：改什么——让优先级进入派发排序（task 继承 + sub_task 按优先级出队近似）+ starvation 防护（等待时长 aging）；为什么——差距表 N-006 P2 TODO，`sub_task.priority` 已有数据但零消费，task 级无 priority；类型——补功能（小闭环）；不做什么——**不引入显式优先级队列表/纯内存队列、不做运行时抢占（高优先级打断执行中任务）、不新增控制命令层**（皆远期，需 INTERRUPTED 状态机 + 恢复快照）、不改执行链/收敛链语义、不建第二控制面。

---

## §2 范围边界

**做**：

1. `task` 表新增 `priority` 字段（HIGH/MEDIUM/LOW，默认 MEDIUM；V72 迁移）
2. **优先级继承**：sub_task 创建时若未显式指定 priority，继承 task.priority（Planner 拆解/Workflow 实例化/手工建子任务统一）
3. **派发排序**：`AgentSelector` 候选过滤后按"任务优先级档"插入比较器（仿 qualityRank 低权重模式）；调度取数 SQL（PENDING 候选查询）按 priority + 等待时长排序
4. **starvation 防护（aging）**：等待时长提升（PENDING 等待越久档位越高），同档 FIFO 兜底
5. 定向单测 + 全量回归 + 差距表 N-006 处置 + LOG

**不做**（明文边界）：

- 不做显式优先级队列表 / 纯内存队列（落 DB 约束；优先级排序的即时选人即队列语义近似）
- 不做运行时抢占（高优先级打断低优先级执行中任务；需 INTERRUPTED 状态 + 恢复快照，远期）
- 不做控制命令层（STOP/PAUSE/REPLAN/RESUME/APPROVE，架构参考 §3.7 远期）
- 不改 `sub_task` 状态机 / 执行链 / 收敛链 / 重派熔断语义

---

## §3 设计（决策草案，待拍板）

### 3.1 Task Priority 落点（决策 1）

```sql
-- V72
ALTER TABLE task ADD COLUMN priority VARCHAR(10) NOT NULL DEFAULT 'MEDIUM';
CREATE INDEX IF NOT EXISTS idx_task_priority ON task(priority, status) WHERE deleted = 0;
```

- 值域对齐 `sub_task.priority`（HIGH/MEDIUM/LOW）；`TaskAgentPolicy` 同风格的静态解析工具或复用 `TaskPriority` 枚举（common/constant）。
- **优先级继承**：sub_task 创建（LLM 拆解 draft / Workflow 实例化节点 / 手工建子任务）时 `priority` 未显式指定 → 继承 task.priority。task 新建默认 MEDIUM。

### 3.2 Queue 语义（决策 2，方案 A 倾向）

- **不建显式队列**。现有"即时选人"保留，但派发候选**按优先级出队近似**：
  - `AgentSelector.resolveComparator` 插入"任务优先级档"（如 HIGH→权重最高，MEDIUM→0，LOW→最低），作为 dutyRank/qualityRank 之外的档位（低权重防抖动，仿 qualityRank 模式）
  - 调度取数 SQL（PENDING 候选）`ORDER BY st.priority 档位 DESC, st.create_time ASC`（同档 FIFO）
- 目标链 `Task Priority → Queue → Scheduler → Agent` 中 Queue = **排序后的候选集**（不落新表，满足"落 DB"红线——排序在 DB 完成）。

### 3.3 Starvation 防护 / 公平性（决策 3）

- **aging（等待时长提升）**：候选排序加入"等待时长"因子——PENDING 等待超过阈值（如 30min）档位 +1、再超 +2（cap HIGH），保证低优先级不被饿死；同档 `create_time ASC`（FIFO）。
- 公平性策略 = 优先级档 + aging + FIFO 三因子排序（全部在 DB/SQL 与比较器内完成，零运行期状态）。

### 3.4 抢占 / 恢复（决策 4）

- **首版不做运行时抢占**（记录口径：WONTFIX 本期，划远期）——高优先级任务进入时只"插队派发未开始任务"，不打断执行中任务（避免 INTERRUPTED 状态机 + 恢复快照的复杂度）。
- 恢复沿用现有故障型重派链（BLOCKED/超时/死信 + 人工介入），与优先级无耦合。

### 3.5 优先级继承实现点

| 创建入口 | 现状 | 改法 |
|---|---|---|
| LLM 拆解（PlannerDecomposeAsyncService） | draft.priority = LLM 输出或 MEDIUM | 未显式/非法 → 继承 task.priority |
| Workflow 实例化（C1 物化 sub_task） | 无 priority 设置 | 继承 task.priority |
| 手工建子任务（SubTaskController） | req.priority 或 MEDIUM | 未指定 → 继承 task.priority |

---

## §4 验证方案

| # | 项 | 落点 |
|---|---|---|
| 1 | task.priority 落库 + 索引 | V72 + Task 实体 + TaskPriority 枚举解析单测 |
| 2 | 优先级继承 | 三入口（拆解/实例化/手工）未指定时继承 task.priority 断言 |
| 3 | 派发排序 | AgentSelector 比较器含 priority 档 + 候选 SQL 按 priority+create_time 排序断言 |
| 4 | aging | 等待超阈值档位提升、同档 FIFO、cap HIGH 断言 |
| 5 | 全量回归 | core + api 既有回归全绿（调度/重派/Workflow/Team 不受影响） |

---

## §5 实施路线建议（骨架先行）

```text
N-006-S1 V72 task.priority + 枚举/实体/TaskPriority 解析 + 优先级继承三入口
N-006-S2 派发排序：AgentSelector 比较器插入 + 候选取数 SQL 排序
N-006-S3 aging 因子 + 同档 FIFO
N-006-S4 验证：§4 五项单测 + 全量回归
N-006-S5 文档回填（差距表 N-006 处置 + LOG）
```

---

## §6 风险与边界

| 风险 | 缓解 |
|---|---|
| 排序复杂度影响现有调度 | 低权重插入比较器（仿 qualityRank），不改选人过滤链/熔断 |
| 优先级被绕过 | 三入口统一继承 + 取数 SQL 排序，单点收口 |
| aging 导致高优先级插队过度 | 阈值 + cap HIGH + 同档 FIFO，可配置常量起步 |
| 与抢占期望不符 | 决策 4 显式记录"运行时抢占 = 远期"，首版只做派发优先级 |
| 退化成第二控制面 | 零运行期状态/零新队列表，全部在 DB 排序 + 现有比较器 |

---

## §7 决策拍板记录（待用户审阅）

| # | 决策点 | 草案倾向 | 待拍板 |
|---|---|---|---|
| 1 | Task Priority 落点 | V72 task 新增 priority（HIGH/MEDIUM/LOW，默认 MEDIUM）+ 优先级继承三入口 | ☐ |
| 2 | Queue 语义 | **方案 A**：不建显式队列，派发候选按 priority + create_time 排序（DB 排序 = 队列近似） | ☐ |
| 3 | Starvation/公平性 | aging 等待时长提升（阈值 30min 起步，cap HIGH）+ 同档 FIFO | ☐ |
| 4 | 抢占/恢复 | 首版不做运行时抢占（WONTFIX 本期，远期需 INTERRUPTED 状态机）；恢复沿用现有重派链 | ☐ |

---

## 修订记录

### R1（2026-09-06）：设计预研草案产出

- **背景**：P2-C 设计预研（与 C1/C2/C3 同批），N-006 优先级调度。
- **核心**：D=优先级进派发排序（Task 继承 + DB 排序出队近似）/ aging 防饿死 / 抢占显式划远期；零新队列表、零运行期状态。
- **边界**：首版只做派发优先级 + aging；运行时抢占/控制命令层远期；不建第二控制面。

### R2（2026-09-06）：实施完成（S1~S4）

- **S1**：V72 `task.priority`（HIGH/MEDIUM/LOW 默认 MEDIUM + 索引）+ `TaskPriority` 枚举（parse/normalize/rank 纯函数，非法回落 MEDIUM）+ Task 实体字段 + createTask 默认 + `TaskService.updatePriority`；**优先级继承三入口**：Planner 拆解（LLM 显式合法值优先，未给/非法继承 task.priority）、Workflow 实例化物化节点、SubTaskController 手工建子任务。
- **S2**：调度取数 SQL（`selectStalePendingWithoutExecutionRecord` / `selectPendingUnassignedWithoutActiveExecutionRecord`）ORDER BY 改为「priority 档位 + aging + create_time」——跨任务批量补派时高优先级先出队（DB 排序 = 队列近似）。AgentSelector 比较器未插档（逐个选人模型下同一 task 候选共享优先级，对 agent 间相对顺序无影响，出队顺序由候选 SQL 承担）。
- **S3**：aging 防饿死——等待 ≥30min 档 +1、≥60min +2（cap +2），同档 FIFO（create_time ASC）。
- **S4**：**全量回归：core Tests run: 1230（基线 1225 + 5）/ api 全绿**；新增 TaskPriorityTest 3 + Workflow 继承 1 + Planner 继承 1；既有调度/重派/Workflow/Team/Browser 回归全绿无回退。
- **边界兑现**：零新队列表/零运行期状态；运行时抢占（INTERRUPTED 状态机）显式划远期；不建第二控制面。
