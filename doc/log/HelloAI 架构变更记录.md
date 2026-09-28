# HelloAI 架构变更记录

> 本文件记录重大架构决策，不记录普通代码提交流水账。

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
