# HelloAI 拆解并发 fencing 实施计划（2026-11-26）

> **状态：Active**（L1/L2/L3 已落地并实证；L4 未开工）
> 最后更新：2026-11-26
> 对应差距项：`G-053`（`PR2`）

---

# 0. 定位与边界

## 0.1 问题

首拆（`decompose`）异步段存在「同一任务两轮拆解并行」的窗口。范围止于草案阶段：`PENDING → PLANNING → Planner LLM → task_plan_draft → AWAITING_PLAN_CONFIRM`，涉及 `task`（`status` / `decompose_attempt_id` / `decompose_lease_until`）、`task_plan_attempt`、`task_plan_draft` 三张表。不涉及展开 / 重生成 / 确认 / 废除。

代码走查发现，**未构造复现**；本计划第 5 节把「复现用例」列为验收项，先证伪再改。

## 0.2 本计划做什么

1. 代次（fencing token）从**提交点**绑定并随调用传入，异步段不再读「当前值」；
2. `claim` 带代次，抢不到即放弃（删掉「抢不到仍继续」）；
3. 落库事务内做代次校验（行锁），作为**唯一授权点**；
4. 上述三层合起来才算「fencing 完成」，完成后才做续租。

## 0.3 本计划不做什么

| 不做 | 为什么 |
|---|---|
| 引入 MQ / 独立调度框架 | 现有派发器 + 租约已覆盖「有人去干」，本轮只补「谁能写」 |
| 行级状态机模型迁移 | 12 态内核不动（`ADR-009`）；本轮只加校验，不改状态语义 |
| `task` 表加 `decompose_attempt_no` 列 | 与 `task_plan_attempt.attempt_no` 是同一事实双写（判据 4），必分叉；代次唯一事实源留在 attempt 表 |
| 改 `task_plan_draft` 唯一索引 | 围栏生效后两代不会同时进写段，加 `attempt_no` 到唯一键等于允许两代并存数据，扩面且需确认侧选代 |
| 重排「推进 task 状态」与「attempt 收尾」的先后 | 重排会造出 `SUCCEEDED + 草案已回滚` 的不一致；授权点改在事务内校验，不动顺序 |

---

# 1. 缺陷机制（两条并发路径，只有一条被现有栅栏覆盖）

| 路径 | 触发 | `task.decompose_attempt_id` | batch_key | 现有栅栏是否有效 |
|---|---|---|---|---|
| **P1** 用户 / 接口重拆 | 巡检把任务回退 `PENDING` 后重新 `planById` | **换新**（`beginDecompose` 生成新 UUID） | 换新 | ✅ 有效：`casStatusForAttempt` 比 attemptId，老线程全落空 |
| **P2** 派发器重触发 | `reclaimHanging` 把同一 attempt 行改 `FAILED_RETRYABLE` 并 `attempt_no + 1`，`dispatchOne` 再 claim 成功后重调 `executeDecompose` | **不变**（派发器不写该列） | 不变 | ❌ 失效：两代共享同一 attemptId 与 batch_key |

P2 下失效的三处具体证据：

1. `PlannerDecomposeAsyncServiceImpl#executeDecompose:227` 进函数才 `currentDecomposeAttempt(taskId)`，`:235` 再 `findLatestByBatchKey(attemptId)` —— 拿到的是**新代**的 `attempt_no`，旧线程因此「自带新代身份」，后续所有按 `attempt_no` 的 CAS 全部命中。
2. `advanceAfterDecompose:857` 的 `PLANNING` 分支注释写「本轮拆解已被新一轮拆解取代（attemptId 变了）」。P2 下 attemptId 不变 ⇒ 该分支**永不触发**，两代都能 CAS 成功。
3. `PlanAttemptMapper#casClaim:40-46` 的 WHERE 只有 `id + status IN ('PENDING','FAILED_RETRYABLE')`，**不带 `attempt_no`** ⇒ 派发器扫描时读到的号与 claim 时的号不一致也照样抢成功；随后 `claimAttemptQuietly:301-306` 抢不到「仍继续」，注释称「收尾将被栅栏拦截」——在 P2 下这个假设不成立，因为收尾用的就是现读的新号。

结论：`TD-16b` 的栅栏设计前提是「一次执行 ⇔ 一个 attempt_no」，而 `attempt_no` 是**执行中现读**的，前提不成立。

---

# 2. 对四层方案的订正（A / B / C）

## 订正 A：fencing token 是 `(attemptId, attemptNo)` 二元组，且在提交点绑定

原方案第 3 层写「入口校验 `currentDecomposeAttempt == 传入 attemptId`」。P2 下两代同一 attemptId，该校验**恒成立**，是假校验；同理 `rollbackToPending:1859` 的 CAS 也恒成立，旧代能把新代打回 `PENDING`。

改为：`executeDecompose(taskId, attemptId, attemptNo)`，两个提交点各自绑定：

- 快路径 `PlannerAnalysisServiceImpl:203`：用 `begin()` **返回值**里的 `attemptNo`，不能用常量 `1`（`begin()` 撞键复用已有行时可能是 2/3；且 `begin()` 内部固定 `setAttemptNo(1)`，只在「新建行」时等值）。
- 派发器 `PlanAttemptDispatcherImpl:136`：传 `attempt.getId()` + `attempt.getAttemptNo()`，并要求 `claim` 带同一个号。

`attempt_no` 是唯一的代次事实源，不再往 `task` 表加列。

## 订正 B：续租必须两套一起续，否则「活着」仍被判死

- attempt 级 `attempt-lease-minutes` 默认 7 分钟（`PlanAttemptDispatcherImpl:54`）；
- task 级 `task.decompose_lease_until` 由 `beginDecompose` 写 10 分钟（`TaskServiceImpl:548`）；
- 两套都**没有续租调用点**（`renew` 在拆解链路零调用）。

只续 attempt 租约，第 10 分钟 `PlanningTimeoutTask` 照样回退 `PENDING`；此时 `advanceAfterDecompose` 走 `PENDING` 分支「把 AI 成果救回」——这是**掩盖**不是修复：救回过程中新代可能已经起来，两者又回到 P2。

顺序上先不碰续租：续租只降噪声、不提供正确性；先上会让「常态双跑」消失，从而掩盖 fencing 缺失。

## 订正 C：残缺批次作废的危险不在「删多了」，而在「谁有权删」

两代共享 `batch_key` ⇒ `discardIncompleteBatch(taskId, attemptId)` 调 `planDraftRoundService.discardBatch(attemptId)` 按 `batch_key` **全删**，会把另一代已落**完整**的批次一起删掉（`TD-16e-2` 症状换个方向重现）。

两种解法里选后者：

- ~~草案表加 `attempt_no`，按 `(batch_key, attempt_no)` 精确删~~ —— 围栏生效后本就只该有一代在写，加列是为「两代并存」付成本；
- ✅ **让非当前代根本进不了写段**：授权点放在落库事务内（行锁 + 代次比对），拿不到授权的执行**不落库、不推进、不作废、不回退**，只记时间线。于是作废只可能由当前代发起，按 `batch_key` 全删是安全的。

---

# 3. 落地顺序（与四层方案的差异）

| 阶段 | 内容 | 提供什么 | 状态 |
|---|---|---|---|
| **L1** | 代次绑定 + `claim` 带号 + 抢不到即放弃 | 阻止旧代**开始**跑 | ✅ 已落地 |
| **L2** | 落库事务内代次校验（行锁）+ 失败收尾路径同权限 | 阻止旧代**写** | ✅ 已落地 |
| **L3** | 旧代丢弃的 WARN 观测（**只记日志，不写时间线**，见 §4.3 订正） | 可观测 | ✅ 已落地 |
| **L4** | 执行期两套租约续期（attempt + task） | 减少「假死」 | ⏸ 未开工（必须最后做） |

L1+L2+L3 是一个语义整体（身份绑定 + 入口准入 + 写前校验），拆开上线会留下「看起来好了、底层没修」的中间态。L4 单列，因为它只改变噪声分布，且**必须最后做**。

> 本文件**未纳入** `verify-plan-status.sh` 白名单：条目用 `L1~L4` 分层号，不在该脚本 `ID`/`SEC` 正则的号段内，登记进去会因「解析出 0 个条目」整文件报红。若要纳入，须先在差距表 §0 登记新前缀再扩正则（判据 31）。

---

# 4. 改动清单

## 4.1 L1 代次绑定

| 文件 | 改动 |
|---|---|
| `helloai-core/.../planner/service/PlannerDecomposeAsyncService.java:32` | `executeDecompose(Long taskId, String attemptId, int attemptNo)` |
| `helloai-core/.../planner/service/impl/PlannerDecomposeAsyncServiceImpl.java:218` | 删掉入口 `currentDecomposeAttempt` / `findLatestByBatchKey` 现读；改收形参；入口断言 `taskStatus==PLANNING && task.decompose_attempt_id==attemptId`，否则 return + 时间线 |
| `.../PlanAttemptDispatcherImpl.java:130,136` | `claim(id, attemptNo, owner, lease)`；落空即 `SKIPPED`；`executeDecompose(taskId, attemptId, attemptNo)` |
| `.../planner/service/PlanAttemptService.java` + `impl/PlanAttemptServiceImpl.java:96` | `claim` 增 `Integer attemptNo` 形参 |
| `.../planner/mapper/PlanAttemptMapper.java:40` | `casClaim` WHERE 增 `AND attempt_no = #{attemptNo}` |
| `.../planner/service/impl/PlannerAnalysisServiceImpl.java:203` | 用 `begin()` 返回值取 `attemptNo` 一并传入 |
| `.../PlannerDecomposeAsyncServiceImpl#claimAttemptQuietly:290` | 改严格版：记录不存在 / status 非 PENDING 且非本人 RUNNING / claim 落空 ⇒ **放弃执行**（fail-close），不再「仍继续」 |

## 4.2 L2 写段授权

| 文件 | 改动 |
|---|---|
| `.../planner/mapper/PlanAttemptMapper.java` | 新增 `lockForUpdate(id)`：`SELECT * FROM task_plan_attempt WHERE id=#{id} AND deleted=0 FOR UPDATE` |
| `.../PlannerDecomposeAsyncServiceImpl.java:738` | `persistPlan` 事务内**首条语句** `authorizeWrite(...)`：`lockForWrite` 锁行 + 校验「行存在 / 代次相等 / status==RUNNING」；不符 ⇒ 抛 `StalePlanAttemptException`（`BizException` 子类，code=409），事务回滚 |
| `.../planner/support/StalePlanAttemptException.java` | 新建：携带 `attemptId` / `myAttemptNo` / `currentAttemptNo` / `stage`，供上层日志三元组与**类型判定**（不用 code 判，409 还能来自别处） |
| `.../PlannerDecomposeAsyncServiceImpl.java:240-275` | catch 分支区分 `StalePlanAttemptException`（置于 `DuplicateKeyException` 之前）：**不** `rollbackToPending`、**不** `discardIncompleteBatch`、**不** `markAttemptFailed`、**不**记 `task_plan_failed`，只 WARN + `logStaleAttempt` |
| `PlanDraftRoundService#discardBatch` 的调用点 | 仅在授权成功的同代内可达（由上一行保证），不改签名 |

行锁与 reclaim 的互斥关系：`casMarkHanging` 是单条 `UPDATE`，会取行锁；`persistPlan` 事务内先 `FOR UPDATE` 同一行 ⇒ 二者串行。reclaim 先行则本事务读到 `attempt_no+1` ⇒ 放弃；本事务先行则 reclaim 阻塞到提交 ⇒ 判悬挂的租约条件基于旧 `lease_until`，下一轮重扫即可。

## 4.3 L3 观测（**口径订正**：不新增时间线枚举）

原计划要加 `TimelineEventCode.TASK_PLAN_STALE_ATTEMPT_SKIPPED` 并写进时间线。落地时订正为**只记 WARN 日志**，理由：

1. **没有读取方**。用户看到的是一条「旧代被丢弃」只会困惑——它不是用户动作的结果；内部调度噪声不属于用户视角的时间线（判据 2：无读取方的列/位=反模式）。
2. **枚举扩容有守卫成本**：`TimelineEventCode` 增项牵动前端映射与事件口径校验，为一个无人消费的内部信号付这个代价不成比例。
3. 排障需要的是三元组而非事件流：`logStaleAttempt` 统一打印
   `taskId / attemptId / myAttemptNo / currentAttemptNo / stage`，`stage` 取值
   `entry-attempt-id`（入口挡 P1）、`attempt-no`（入口挡 P2）、`persist-lock` / `persist-attempt-no` / `persist-status`（写段三判据）。

仍然保留原计划的一条硬约束：**禁止**把「被围栏拒绝」记成 `TASK_PLAN_FAILED`——它不是失败，是正常的迟到丢弃；记成失败会让 `G-052` 式的失败率观测长期被噪声污染。该约束已由 catch 分支的类型分流保证。

## 4.4 L4 续租（最后做）

- `PlanAttemptService#renew` 在写段前后各调一次（LLM 段长时间无写，需要独立心跳线程或按 LLM 分段续期；仅「返回后再续」等于没续，因为判死发生在 LLM 段内）；
- 同批续 `task.decompose_lease_until`（新增 `TaskService#renewDecomposeLease(taskId, attemptId, attemptNo, minutes)`，CAS 带 attemptId，**不**带代次列的前提下以 attempt 行锁为准）；
- 心跳周期取 `min(attempt 租约, task 租约) / 3`；心跳失败只记 WARN，不中断主流程（判据 27：观测不得伤主链）。

---

# 5. 验收

| 项 | 判据 |
|---|---|
| 复现用例（改前必须红） | ✅ **已证**：在 HEAD 快照（`git archive HEAD`）上跑「同一 task 连调 `executeDecompose` 两次」⇒ `executeSync` 被调 **2 次**（`doDecompose:721` 各一次），`TooManyActualInvocations` 红；改后入口按代次拒绝，同一用例绿 |
| L1 单测 | `claim` 带错号返回 0；派发器 claim 落空不调 `executeDecompose` |
| L2 单测 | ✅ 三条：锁不到行 / 代次已 +1 / 状态非 RUNNING ⇒ 均断言 `writeDrafts` 0 次、`discardBatch` 0 次、`PLANNING→PENDING` 0 次、`task_plan_failed` 0 次 |
| L2 用例有效性（反证） | ✅ 把 `authorizeWrite` 临时改为空实现 ⇒ 上述 3 条 **3/3 转红**，证明断言真的挂在授权上，不是空转 |
| L2 事务性 | 校验失败抛异常后 `task_plan_draft` 无半批残留（沿用 `persistPlan` 既有事务断言） |
| 回归 | ✅ `PlannerDecomposeAsyncServiceImplTest` 68 例 + `PlanAttemptServiceImplTest` + `PlanAttemptDispatcherImplTest` + `PlannerAnalysisServiceTest` 35 例 = **118 例全绿**（独立 worktree，未污染运行中 `target/classes`） |
| 守卫 | `ci-gate.sh` 5 门禁 + `verify-doc-gap-table.sh` |
| 真机 | P2 复现：手动 `planById` 与派发器同任务并发触发，只能有一份草案；`task_plan_attempt` 只一行 `SUCCEEDED` |

---

# 6. 风险与回滚

| 风险 | 处置 |
|---|---|
| fail-close 导致「明明该跑却没跑」 | 入口放弃后任务停在 `PLANNING`，由 `PlanningTimeoutTask` 10 分钟内回退 `PENDING`，用户可重拆——有兜底，不是死结 |
| `FOR UPDATE` 与 reclaim 的锁等待 | 二者都是行级短事务；`persistPlan` 事务已存在（`txTemplate`），只是把首条语句从 INSERT 换成 SELECT 加锁，不延长 LLM 段 |
| **`lockForWrite` 被误加 `@Transactional`** | 那会新开短事务、方法返回即放锁，授权窗口退化成「只在方法内有效」——恰好把问题原样保留。已在实现方法注释里写死这条纪律：**锁必须由调用方事务持有** |
| **无事务调用时 `FOR UPDATE` 静默不加锁** | PostgreSQL 不报错，退化普通读取。当前唯一调用点在 `persistPlan`（`txTemplate().execute` 内）；若将来出现旁路调用必须自行保证事务边界 |
| L2 让「同代重复执行」也走丢弃 | 同代重复（快路径与派发器各提交一次、先到者已收尾 ⇒ 后到者读到 `status != RUNNING`）会抛「代次失效」而放弃写入。语义上略宽（不是代次问题），但处置正确（不写/不推进/不作废/不回退），后到者本该什么都不做 |
| 升级瞬间在途拆解「attempt 行不存在」 | 严格版会放弃；这是**一次性窗口**，代价是用户重拆一次，换掉「无栅栏执行」的长期风险 |
| UI / 前端 | 无改动 |

回滚：改动集中在 `planner` 域 6 个文件 + 1 个枚举，无 schema 变更（L1~L3），可整批 revert；L4 若上线需单独开关（`helloai.planner.lease-renew-enabled`，默认 `false`，观察一轮再开）。

---

# 7. 关联

- `G-053`（本项）、`TD-16b`（attempt 栅栏原设计）、`TD-16e-2`（残缺批次判据与作废）
- 判据 4（同一事实双写必分叉 → 不加 `task.decompose_attempt_no`）、判据 9（约束强弱看 fail-open/fail-close）、判据 26（真值分层）、判据 27（观测不得伤主链）、判据 30（构造器加字段排查）
- `ADR-009`（12 态内核不动）
