# HelloAI 架构变更记录

> 本文件记录重大架构决策，不记录普通代码提交流水账。
>
> **最后更新：2026-09-28**（末条 `ARCH-20260928-003`）。本文件是 `ARCH-YYYYMMDD-NNN` 编号的唯一事实源，**请勿删除**；架构决策的**即时承载**为当月 Log（`### 决策` 段）+ 差距表，本文件**按开发周期迭代定期汇总回填**。

## ARCH-20260907-001 — Event Stream 作为统一执行事实层

### Decision
使用 AgentRun + AgentEvent Stream 描述一次 Agent 执行。

### Consequence
Timeline / Audit / Replay / Metrics 可以共享执行事实。

### Boundary
业务状态机仍然独立作为业务状态权威。

## ARCH-20260907-002 — Dual Executor 仅作为迁移策略

### Decision
LegacyExecutor 通过 Adapter 接入统一 Runtime Contract，Runtime 成为长期执行入口。

### Consequence
新旧实现可以灰度迁移，避免一次性重写。

## ARCH-20260907-003 — AgentRuntime 作为执行抽象

### Decision
把一次 Agent Turn 的执行能力从具体 Executor 中抽象出来。

### Boundary
Planner / Global Scheduler / Reviewer 不属于 Runtime。

## ARCH-20260907-004 — Skill 向 Capability Package 演进

### Decision
保留 Markdown Instructions 兼容性，逐步增加版本、Tool、Schema、依赖等结构化元数据。

## ARCH-20260907-005 — Sandbox Provider 解耦

### Decision
Runtime 只依赖 SandboxProvider Contract，具体 Local / Docker / Remote / K8s 实现由 Provider 提供。

## ARCH-20260928-001 — 最终报告审查异步化（REVIEWING 中间态 + 专用池 + 陈旧守卫）

### Decision
最终报告审查从「同步 @EventListener 同线程串行」改为「同步入口 + reportReviewExecutor 专用池异步执行」：
- 新增状态 `REVIEWING`（NONE → GENERATING → REVIEWING → DONE / FAILED），审查链全部出口（pass / unparseable / LLM failed / max_review / skipped / 自动审查开关关闭）收敛置 `DONE`，不收敛则 UI 永久轮询；
- 入口用**同步 Listener + 手动 `executor.execute` + try-catch `RejectedExecutionException`**，而非 `@Async`——`@Async` 的拒绝异常走 `AsyncUncaughtExceptionHandler` 不回发布线程，无法可靠落 `review_skipped(executor_saturated)`；
- 事件携带 `reportTime`（`truncatedTo(MICROS)` 对齐 TIMESTAMPTZ 精度）作三重陈旧守卫：审查前 / rework 前二次校验、收敛用条件写回 `.eq(finalReportTime)`（0 行影响 = 新链接管，放弃覆盖）；
- 专用池 `ReportReviewExecutorConfig`：core 1 / max 2 / queue 50 / **AbortPolicy**（异于子任务审查池的 CallerRunsPolicy——EventListener 入口已占发布线程，CallerRuns 会串死该线程）；
- 开关 `AgentDispatchProperties.autoFinalReportReviewEnabled`（默认 true）决定写回 REVIEWING 或 DONE。

### Rationale
一次点击原来最多串行 8 次 LLM 调用（出纲+正文≤3 档降档+审查+返工再出纲+正文+再审查），可超前端 240s 超时；异步化后请求时长回落到「出纲+正文」，审查在专用池收敛，UI 靠既有 5s 轮询收口。异步引入「审查在途与用户重新生成并发」的旧链覆盖新链风险，故陈旧守卫同批落地（非可选）。

### Consequence
最终报告状态机新增合法中间态并确定收敛出口；并发安全依赖「事件时间戳 == 库内 final_report_time」的 CAS 语义。

### Boundary
轮次去状态化（§12.3）：attempt 不落库、generate 恒为 1、返工轮次由监听器显式传参；版本单槽化（§12.1）：V95 三列 prev 槽 + rollback 整体互换。均不新建 Runtime / Scheduler / Workflow，不建第二套状态机。

### 修订（2026-09-28，见 ARCH-20260928-003）
本决策的「审查入口为普通 `@EventListener`、收敛出口全在进程内事件」已被 **ARCH-20260928-003** 取代：
- 入口改 `@TransactionalEventListener(phase = AFTER_COMMIT)`（原为普通 `@EventListener`，是潜伏相位 bug——一旦生成主链补上事务，会在提交前触发并被陈旧守卫丢弃）；
- 新增 L2 Outbox + MQ 消费、L3 巡检兜底，「不收敛则 UI 永久轮询」不再是既成事实（原文 Consequence 中的该表述已失效）；
- 收敛写口从 `lambdaUpdate` 直写收口到 `FinalReportStateMachine` + `TaskService` 状态机写口。
专用池 `reportReviewExecutor`（AbortPolicy）与 `reportTime` 三重陈旧守卫仍有效，本修订不改其口径。

## ARCH-20260928-002 — 产物存储抽象（ArtifactStorage）与对账巡检纪律

### Decision
把产物存取从「各自 new MinioClient + 拼 URL」收敛为 ArtifactStorage 契约（Local / Minio 双实现 + Composite 按 `helloai.storage.type` 与 URL 前缀路由）：
- 业务接口 `store/load/supports/exists/validateAddress` 按 URL 前缀分派；`listObjects/removeObject` 对账与测试面只面向主存储；
- 默认策略按面定：`exists` fail-open（校验不误伤）、`listObjects` fail-safe（看不到绝不导致删除）、`removeObject` fail-close（未实现的实现即抛错，删除能力必须显式实现）；
- `register` 前置校验：storageUrl 必填 → `validateAddress` → supports 且 `!exists` → 400 拒绝（把预览期 500 提前成登记期 400）；
- 对账巡检 `ArtifactStorageReconcileTask`：6h fixedRate + ShedLock（PT20M）+ `reconcile-enabled` 逃生口，只读比对（attachment 全量含逻辑删除 ↔ 桶内对象）出三态——悬空 / 孤儿 / 字节不符；孤儿清理与巡检分离双开关，默认关闭，三重保险（开关 + 24h 时间窗 + 单轮上限 200）。

### Rationale
排查发现 dev 库共享而对象存储历史上各环境一份：106/260（40.8%）附件悬空、16 个孤儿对象、9 组内容重复——业务侧只能看到零星「读取失败」，拼不出全貌（见 `doc/design/HelloAI_附件存储一致性排查报告.md`）。对账巡检是把「环境级漂移」从不可见变成可报告的最小闭环；清理是破坏性动作，故与巡检分离双开关、默认关闭。

### Consequence
register 的「先落库后校验」历史行为改变为「先校验后落库」（400 拒绝，@Transactional 回滚）；产物存储有了统一契约，未来换存（多桶 / 多云）只加实现不改造动业务。

### Boundary
历史悬空数据（106 条）不自动修复（不确定归属存储实例）；孤儿清理仅作用于「未被任何 attachment 行引用」的对象；R7 安全项（公网暴露 + 默认凭据）属部署侧，不在此代码变更范围内。

## ARCH-20260928-003 — 报告审查链三级容错 + 状态机写口收口（取代 ARCH-20260928-001 的进程内收敛口径）

### Decision
最终报告审查链由「单级进程内事件」补齐为与子任务核验链同构的**三级容错**，并把状态写口收口到显式状态机：

1. **L1 事务边界 + 正确相位**：新增 `FinalReportPersistService.persistAndRequestReview`（`@Transactional`），把「报告写回（含 prev 槽）」+「L2 Outbox 事件」+「L1 内存事件」三者放进**同一个事务**——修复原 `lambdaUpdate` 与 `publishEvent` 之间的双写窗口（无原子性：进程在中途挂掉即「报告已落库、审查永不触发」）。审查入口改 `@TransactionalEventListener(phase = AFTER_COMMIT)`。**独立 Bean 是必需的**（同类内自调用不走代理）。
2. **L2 持久化触发 + 幂等消费**：`AgentOutboxService.createReportReviewEvent` 写 `agent_outbox_event`；由既有 `AgentEventCompensationTask`（15s）补投到新增队列 `helloai.report-review.queue`（DLX + `x-max-length=50000` + `reject-publish`）；`MqFinalReportReviewConsumer extends AbstractIdempotentConsumer` 以 `eventId` 为幂等键消费（Redis + DB 双层幂等）。
3. **L3 巡检兜底**：新增 `FinalReportReviewOrphanTask`（`@Scheduled` 30s + ShedLock），扫「`final_report_status=REVIEWING` 且 `final_report_time` 早于阈值」的任务，**收敛到 DONE 而非重投审查**（报告已交付；重投会无界消耗 LLM），并落 timeline `task_final_report_review_orphan_converged`。
4. **状态机写口**：新增 `FinalReportStateMachine` 显式合法迁移表（`assertTransit`），状态写口收口为 `TaskService.transitFinalReportStatus` / `convergeFinalReportToDone`；收敛使用「状态 + `final_report_time`」双条件 CAS，替代散落的 `lambdaUpdate().set(...)`。

### Rationale
原链只有 L1 一级（且相位错误），存在三类失效面：双写丢失（日志实证 07-31 有一条 `task_final_report_generated` 无配对审查事件）、进程重启丢事件（纯内存监听器）、`isStale` 分支（`review_discarded_stale` / `rework_discarded_stale`）明确不收敛 → 报告可永久停在 `REVIEWING`，无自愈入口，注释自陈「需人工介入」。子任务核验链早已是三级容错，本批即口径对齐。**不把 LLM 调用放进事务**（长事务是反模式）——用 Outbox/MQ + 状态机达成最终一致，而非加长事务。

### Consequence
`REVIEWING` 的每个收敛出口（pass / unparseable / LLM failed / max_review / skipped / 开关关闭 / L3 超时）都会落到 `DONE`；审查链具备重放与自愈能力。写回被接管（CAS 未命中）时本轮生成结果被放弃并返回库内最新状态，不再覆盖新链。

### Boundary
不新建 Runtime / Scheduler / Workflow；不新增 Flyway 迁移（复用既有 `agent_outbox_event` 与新配置项）；L3 策略为「收敛不重投」，故不解决「审查未执行但用户期望补审」的场景（如需重投属新决策）。前端修复（轮询收敛广播 `status-change`）属表现层，同批落地但不属本决策范围。
