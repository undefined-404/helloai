# HelloAI 项目基线文档

> **状态：CURRENT / FACT**
>
> 本文档只描述当前真实代码与已落地能力，不描述未来愿景。
>
> 最后更新：2026-11-05（**本次覆盖 2026-10-18 → 2026-11-05 的交付**：① 新增 **§13 个人助理工作台**（`PA-1`~`PA-7` / `PA-11`~`PA-17`：对话与需求对话按 `kind` 分流、右栏三段、产物三源归一、会话级模型钉住、上下文窗口压缩、模型偏好记忆 / 先思考后检索 / 工作概览注入 / 重新生成），原 §13/§14 顺延为 §14/§15；② **§12 补核验双域**（他审 `RV-1`~`RV-7` + 自审 `SR-1`~`SR-5`：三态结论 / 覆盖批评器 / 抽检独立性 / 判定快照 / 驳回侧冻结复判 / 编码损坏检测 / 自审证据围栏与闸门）；③ **§9 补技能产物导出与对话内技能直出**（`SKX-11` / `SKX-12`）与交付 ZIP 规范正文补包；④ **§10 补技能执行面已验清单**（`SKX-1`~`SKX-15`，2026-11-01 收口）；⑤ §15 一句话与附录 A 同步（补 `G-028` / `G-024` 两行）。**2026-10-17 原文**：① 头部日期失真修正 —— §6 备份/恢复（2026-10-10 交付）与 §8 `Recovery` 消费面（2026-10-16 交付）**早已写入正文但日期未同步**；② §14「当前基线一句话」删去已建成的 `Recovery`；③ 新增**附录 A**「近期已交付能力指针」补 `G-019` RAG / `G-021` 内部 LLM 能力面 / `G-023` 运行时合同三项 —— 它们在《差距表》已 `DONE`，而本文此前零覆盖。**2026-10-09 原文**：**§8 Event 基线订正**：Fork「消费面已落地」与代码实测不符 → 改为「仅快照服务、**无消费方**；入口 / 原 Run 冻结 / 驱动执行已于 2026-10-09 裁定 **WONTFIX**」，依据 `D-2026-10-09-5`；**§7 Runtime 基线补 REF-1.3 工具注册两个语义位 + 不可关闭清单**；**§9 Skill 基线补元数据事实源迁移**（REF-1.1/1.2，md frontmatter + 目录扫描））

# 1. 当前项目定位

HelloAI 当前是一个面向**异构 AI Agent 的分布式任务编排与执行平台**。

当前主要解决：

```text
用户需求
  ↓
Planner / Requirement Clarify
  ↓
Task / SubTask
  ↓
Workflow / DAG
  ↓
Scheduler
  ↓
Agent Assignment
  ↓
Execution Command
  ↓
Agent 执行
  ↓
Result Handler
  ↓
Review / Rework
  ↓
状态收敛
```

当前平台已经具备多 Agent、异步执行、可靠性治理和执行环境抽象的基础，但**不要将当前系统描述成已经完整实现 DeepSeek Harness**。

# 2. 当前技术基线

| 组件 | 基线 |
|---|---|
| JDK | 17 |
| Spring Boot | 3.4.x |
| Spring AI | 1.1.x |
| PostgreSQL | 16 |
| Redis | 7.x |
| RabbitMQ | 3.12+ |
| Flyway | 10.x |
| MyBatis-Plus | 3.5.x |
| Frontend | Vue 3 + TypeScript + Element Plus |

# 3. 当前核心模块

```text
helloai-common
helloai-core
helloai-job
helloai-mq
helloai-api
helloai-start
```

# 4. 当前核心领域对象

```text
Task
SubTask
Agent
AgentTask（skills：契约层技能注入载体，空列表不注入，行为零变化）
ExecutionCommand
AgentExecutionRecord
Review
Conversation
Artifact
AgentEvent
AgentSession
WorkflowTemplate
Team
Credential
```

# 5. 当前执行链路

```text
                     Planner
                        │
                        ▼
                 Task / SubTask
                        │
                        ▼
                    Scheduler
                        │
                        ▼
                ExecutionCommand
                        │
                 ┌──────┴──────┐
                 ▼             ▼
              RabbitMQ       DB Poller
                 │             │
                 └──────┬──────┘
                        ▼
                 Execution Layer
                        │
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
       Platform LLM   External CLI   MCP/Agent
          │             │             │
          └─────────────┼─────────────┘
                        ▼
                  Result Handler
                        │
                        ▼
                  State Convergence
                        │
                        ▼
                     Reviewer
                        │
                  ┌─────┴─────┐
                  ▼           ▼
                PASS        REWORK
```

最终报告链路：

```text
生成：出纲（Outline Prompt）→ 成文（Report Prompt）→ 审查(Review Prompt)，至多 3 次 LLM 调用
降级：出纲失败 → 单次调用（现状兜底）；审查可配置开关关闭（autoFinalReportReviewEnabled=false 时写回直接 DONE）
读取口径：物化附件优先（UTF-8 正文）→ ExecutionRecord SUMMARY/DELIVERABLES 注入 → output 兜底
限额单源：AttachmentContentPolicy（shared/util，附件限额 + 文本/媒体族判定）
状态机：NONE → GENERATING → REVIEWING → DONE（/ FAILED）；REVIEWING 为异步审查在途态，
        审查链全部出口（pass / unparseable / LLM failed / max_review / skipped / 开关关闭 / L3 超时兜底）收敛 DONE；
        迁移合法性由 FinalReportStateMachine 显式迁移表校验，写口收口 TaskService.transitFinalReportStatus / convergeFinalReportToDone
审查异步：FinalReportReviewServiceImpl（@TransactionalEventListener(AFTER_COMMIT) 入口）+ reportReviewExecutor
        专用池（core1/max2/queue50/AbortPolicy）执行；事件携带 reportTime（微秒截断）作三重陈旧守卫
        （审查前 / rework 前 / 收敛条件写回）；入口 Redisson 防双审锁 + status != REVIEWING 幂等跳过
三级容错：L1 内存事件（AFTER_COMMIT）+ L2 Outbox（FinalReportPersistService 写回与 agent_outbox_event 同事务 →
        AgentEventCompensationTask 补投 → helloai.report-review.queue → MqFinalReportReviewConsumer 幂等消费）+
        L3 FinalReportReviewOrphanTask 巡检（REVIEWING 超阈值收敛 DONE，刻意不重投审查避免无界烧 LLM）
轮次：无状态——generate 恒为 1，同轮返工由审查服务显式传 attempt+1（不落库）
版本：V95 单槽列 final_report_prev(_agent_id/_time)——覆盖前现值落槽；
        POST /api/tasks/rollbackFinalReportByTaskId/{id}：current↔prev 整体互换可反复切换，无 prev 409；
        事件 task_final_report_rolled_back
```

# 6. 当前可靠性能力

当前已经形成的基础能力包括：

- DB 状态中心；
- ExecutionCommand；
- Outbox；
- RabbitMQ 主链 + DB Poller 兜底；
- 幂等保护；
- 分布式锁；
- Lease / Heartbeat / Reconcile；
- Retry / Timeout / Compensation；
- DLQ / 死信台账；
- Agent 在线状态治理（ACTIVE 值班租约作为一等存活证据——心跳过期不直接判 OFFLINE，避免在岗 Agent 被误判离线触发在飞任务重派；`renewLease` 到期时刻同租约内单调不减）；
- 外部 Agent 通道与内部分发链约束同口径（`claimSubTask` 复用 `isReady` 依赖门禁、`listAvailable` 就绪过滤；新增 MCP `startSubTask` 打通 REWORK 返工出口；`SubTaskDetail` 内联产出附件与贡献者，产物可发现）；
- 失败重派和结果收敛；
- 报告审查链三级容错（L1 `@TransactionalEventListener(AFTER_COMMIT)` 内存事件 + L2 Outbox（报告写回与 `agent_outbox_event` 同事务 → `AgentEventCompensationTask` 补投 → `helloai.report-review.queue` → `MqFinalReportReviewConsumer` 幂等消费）+ L3 `FinalReportReviewOrphanTask` 巡检（`REVIEWING` 超阈值收敛 `DONE`，刻意不重投审查）；写口收口 `FinalReportStateMachine` 显式迁移表 + `TaskService.transitFinalReportStatus` / `convergeFinalReportToDone`；入口 Redisson 防双审锁）；
- 产物存储一致性（ArtifactStorage 抽象 + Composite 路由（local/minio 双实现、按 type/URL 前缀分派）+ `AttachmentServiceImpl.register` 前置校验（validateAddress + 存在性，不存在 400 拒绝，把预览期 500 提前成登记期 400）；`ArtifactStorageReconcileTask` 6h ShedLock 只读对账（attachment 全量含逻辑删除 ↔ 桶内对象双向比对，悬空/孤儿/字节不符三态）；孤儿清理默认关闭，三重保险——开关 + 24h 时间窗 + 单轮上限 200）。

- 平台备份 / 恢复（备份编排：`pg_dump -Fc` 全库 + MinIO 对象清单递归枚举 + manifest 前置 peek（不解档即可判内容）+ Redisson 单飞锁 + 仅淘汰自动备份，落**独立桶** `helloai-backups`（不参与产物对账）+ `platform_backup` 台账（`V105`）；恢复侧三门（跨引擎拒 / schema 高过运行时拒 / 在线拒）+ 管理端 preflight（只读）与 restore 端点；生产镜像内置 `pg_dump` / `pg_restore` 客户端。**诚实边界**：对象**本体**不在备份内（只有 `key` + `size` 清单）；运行中备份不比多文件同一瞬间。停机恢复流程见 `doc/manual/platform-backup-restore/runbook.md`）。

- 附件与任务域**读权限单一判据**（`AttachmentVisibilityPolicy`：`TASK` 可见范围 × **根任务 Task-Team 成员关系**，含 derive-on-miss 兜底；上传者恒可读自传 ⇒ 被改派换下者仍能读自己旧产出）；读侧通道**同源**——附件端点（`/api/attachments/**`）与子任务视图端点（时间线 / 对话流 / 详情）统一经该判据（视图侧收口 `SubTaskViewGuard`），其中详情端点 **此前无校验**的缺口已关闭；被注入截断的前置附件在标注行给出可寻址 `id=<attachmentId>`，配合既有 `downloadById` 端点可按需回取全文。

- 事件码**服务端单一来源**（`REF-6.8`，2026-10-11）：展示码字典以 `TimelineEventCode` 枚举（141 码）为**唯一事实源**，经 `GET /api/events/catalog` 下发；前端**不再保留** `EVENT_META` / `sequenceFlow.LABEL` 硬编码副本（改为运行时拉目录，未命中回退原始码）。后端**展示通道写点**（119 处）统一写作 `TimelineEventCode.<CONST>.code()`，无裸字面量残留。守卫 `scripts/shell/verify-event-key-parity.sh`（静态模式，入驻 ci-gate 门禁 3）三断言：写点无裸字面量 / 写点枚举引用常量存在 / 库内真值 ⊆ 枚举。

这些属于 HelloAI 的**分布式编排与可靠性基础设施**。

# 7. 当前 Runtime 基线

当前已经存在：

```text
AgentContext
AgentRuntime
AgentExecutionResult
ExecutionEnvironment
ExecutionEnvironmentProvider
ToolExecutor
ToolExecutionResult
AgentLoop
AgentLoopInput
AgentLoopResult
SandboxProvider
SandboxContext
Sandbox
ExecutionPolicy
RuntimeTurnExecutor          ← 唯一 AgentRuntime 实现
```

当前含义：

- `AgentRuntime` 已成为统一执行契约，且**实现层已唯一化**；
- **单轨硬切（G-002）**：`LegacyExecutorAdapter` / `RuntimeAgentRuntimeRouter` / `TurnLlmCaller` / `TurnLlmCallContext` 与 `runtime-enabled` / `v2-enabled` / `gray-percent` 开关**已全部删除**——不再存在双轨，也不再存在配置级回退开关（回退手段为 `git revert`）。消费链统一由 `LocalExecutionCommandConsumer` 分层编排（startIfNeeded → markRunning CAS → `AgentRuntimeContextAssembler` 装配 → `AgentRuntime#execute` → afterTurn → 终态 CAS → `ExecutionResultHandler` 回写）；
- `RuntimeTurnExecutor` 是**唯一** `AgentRuntime` 实现（`agentRuntimes.get(0)` 直取，无路由、无排序、无开关）；
- `ToolExecutor` 已具备执行回路真身（懒加载 spring-ai ToolCallback 目录按名调用，与 ToolRegistry 元数据面同源同构；未知工具 / 空参 / 执行异常 best-effort 返回失败不抛）；
- `AgentLoop` 已具备手动工具循环真身（`runtime/loop`：ChatModel 契约 + ToolExecutor 执行 + TOOL_CALL 事件，`internalToolExecutionEnabled=false` 由循环接管工具执行，maxIterations 硬上限防死循环）；**每轮 iteration 边界经 `LoopCheckpointListener` 落 `agent_session.snapshot.loop`**（同层恢复 checkpoint，零 DDL），`tokenUsage` 逐轮累加并落 `agent_execution_record.token_usage`（V97）；
- `RuntimeTurnExecutor` 组装 `AgentLoop` / `ToolExecutor` / `ToolRegistry` / `AgentSkillSpecService` / `SandboxProvider`；prompt / chatModel / 会话 / 对话流由 `AgentRuntimeContextAssembler` 装配后注入。
- **工具注册两个语义位（2026-10-09 起）**：`ToolRegistry.resolve(names, ToolContext)` 返回「**生效形态**」，其结果是**模型可见工具的唯一判据**（`resolveVisibleCallbacks` → `AgentLoopInput`）——①「按条件可用」（`ToolCallbackContributor.toolAvailability()`，`false` ⇒ 摘除）②「按上下文动态描述」（`toolDescription()` + `DescribedToolCallback` 包装）。**生效粒度 = Turn**；工具目录加载失败时 **fail-open**（`catalogDegraded`，未知名字保留）；**不作用**于 MCP `tools/list` 暴露面。**不可关闭清单 `CRITICAL_TOOLS = {pullTasks, submitResult, heartbeat}`** 生效于授权面（`isToolEnabled` 短路 + `getEnabledTools` 并集），**刻意不抵销** `API_KEY_LLM` 的 MCP 会话工具过滤。当前真实声明者 = `WebSearchToolCallback`（无搜索凭据 ⇒ 摘 `web_search`）。边界正文见 `design/Agent_Runtime.md`。

当前仍不能宣称已完成完整 Harness Runtime：

```text
ToolExecutor      → 契约+真身已具备（执行回路真身），AgentLoop 已接线
AgentLoop         → 契约+真身已具备（手动工具循环真身），已组装进 RuntimeTurnExecutor
Session 协调      → 已确认收敛口径（AgentSessionService 承载）
SandboxProvider   → 契约已具备；首个真实提供方 OpenSandbox 已按「能力预研 + 契约落地」接入（S-1~S-5，2026-10-17），隔离能力已具 ISOLATED 真身（filesystem/resource/credential），业务执行链接线后置（触发条件见 ADR-003 §6）
Capability 体系   → 尚需完善
```

# 8. 当前 Event 基线

当前已经存在 `agent_event` 体系，以及：

```text
Run
Turn
Step
Event
```

的统一模型。

当前 Event 的主要职责：

```text
执行轨迹
对账
Timeline / Review 的事实输入
```

读侧已具备 `AgentEventQueryService` 多消费面：`traceBySubTaskId`（按 subTaskId 以 `createTime + id` 有序投影，Timeline 消费面，A6 已并轨 `/timeline`；增量 D 起亦经 REST 暴露）、`traceByRunId`（按 runId 重建 Run 级轨迹，Replay 读侧，A7）、`traceByTaskId`（任务维度轨迹，免传 runId 由 service 内部推导，增量 D）、`pageAuditByTaskId`（按 taskId 分页查执行事实，eventType 可选过滤，Audit 读侧，A7）——Timeline / Replay / Audit 已从 Event Stream 获取事实，并经 REST API（`/api/agent-events` 四端点）+ UI 事件流工作台（`/event-stream`，增量 C1/C2/D）全链暴露；**Fork 仅快照服务**（`AgentEventForkService`，可复制 Run 事件到新 run_id，但**零生产调用方**；触发入口 / 原 Run 冻结 / 驱动新 Run 执行已于 2026-10-09 裁定 **WONTFIX**，见 `D-2026-10-09-5`），**Recovery 消费面已于 2026-10-16 建成**——只读恢复点投影 `AgentEventQueryService#projectRecoveryPoint`（未闭合轮次 / 中断点工具 / 该轮已完成工具，零阈值结构量判定）+ 只读端点 `GET /api/agent-events/recoveryPointBySubTaskId/{subTaskId}`；同时 `AgentRuntimeContextAssembler` 的续跑上下文改为「`agent_session` 优先、**事件流恢复点兜底**」（原会话表无记录即静默零注入 ⇒ 现可兜底）。`task_timeline` 保持独立载体不迁移（ADR-001 §4）。

原则：

> `AgentEvent` 记录执行事实；业务状态机仍然是业务状态权威。

因此当前不是“Event Sourcing 全量替换业务状态机”。

# 9. 当前 Skill 基线

当前 Skill 已具备：

```text
requiredSkills（任务级 + 子任务级 V74 新列，装箱并集）
Skill resolve
resolvedSpecs
SKILL_RESOLVED（携带 resolvedVersions）
SkillPackage 元数据层（version / requiredTools / dependencies / inputSchema / outputSchema / validationRules）
SkillPackageCatalog 目录扫描（元数据事实源 = md frontmatter，懒加载，按 name 升序）
SKILL_CATALOG 目录注入（拆解侧能力感知，G-010 S2）
AgentTask.skills 契约层统一注入（execute/executeStream + 拆解/审查收口/报告 5 类同步 LLM 调用挂点，空不注入行为零变化）
技能目录查询 API（GET /api/skills/catalog，skill:view，V103）
技能包安装入口（POST /api/skills/packages，skill:install，V104）：受控存储（PG 元数据 + 正文 / MinIO 原始 zip）+ 摄入闸门（REF-1.5）+ 安装-激活-卸载审计
技能执行面（SKX-1~SKX-15，2026-11-01 收口）：包入库 / 文件注入 / 沙箱规格分层 / internal_run_skill / 产物回传 / 异步化 / spec 预校验 / 部署面
技能产物导出（SKX-11）：产物按路径回传后登记为可下载附件（ChatAttachment），执行现场不落地宿主
对话内技能直出（SKX-12）：需求对话选技能即当场出图，不建任务——runSkillRound 走 LLM 产 spec → validate → deliver → check --require-provenance → saveArtifact 回读 六段
技能包分发规格（2026-11-05）：交付 ZIP 补入 eng-* 规范正文，getMySkill 路径与编码约定同步订正
```

Skill 已从隐式 Prompt 拼接迁移为显式 Runtime 输入 + 元数据面，required_skills 创建 → 拆解 → 派发 → 执行四段贯通，并经真实任务实测闭环（外部执行者双轮，见 log）。

**元数据事实源（2026-10-09 起）**：技能的 9 个元数据字段由 classpath `skills/plugins/*.md` 的 **YAML frontmatter** 承载（8 键白名单，`fileName` 由文件名推导），**原先的 Java `KNOWN_SPECS` 编译期硬编码已删除**；目录扫描按 name 升序，坏文件显式报 corrupt（不静默跳过）。⇒ **新增技能 = 丢一个 md 文件，零改 Java 代码**（「零发版」未达成：需外部技能目录）。

已结构化技能包 4 个（eng-*）：eng-code-review / eng-doc-standard / eng-verification / eng-web-research（requiredTools=[web_search]）。

**技能包安装面（2026-10-10 起，REF-1.5 / 1.6）**：技能包可由安装入口装入并持久化到**受控存储**——元数据与原始正文落 PG、原始上传 zip 落 MinIO（`minio://…/skill-packages/…`），**不写宿主文件系统**（故不构成 `G-005` 沙箱触发条件①）。技能目录改为**双源**（classpath 内置 + 已安装 ACTIVE 行）：同 name 至多一行 ACTIVE、多版本共存供回滚，与内置 `eng-*` 同名一律拒绝。**摄入安全闸门**（文件数 / 解压总量 / 压缩比 / 路径穿越 / symlink / 非普通文件 / 加密位 / 清单 UTF-8）是安装的前置。原计划的「外部技能目录」**已裁 WONTFIX**（差距表 §7.1.3 R4）。

当前尚未全量形成的 Capability Package 剩余缺口主要是：

```text
Instructions 结构化
技能回流贡献规范（D5-3 未交付）
```

# 10. 当前 Environment / Sandbox 基线

当前已经具备：

```text
ExecutionEnvironment
ExecutionEnvironmentProvider
RemoteAgent
LocalProcess
ENVIRONMENT_RESOLVED
SandboxProvider / SandboxContext / Sandbox / ExecutionPolicy（契约层）
SandboxSpec / OpenSandboxProperties / OpenSandboxSandboxProvider / OpenSandboxEnvironment / OpenSandboxProbe（OpenSandbox 集成，S-1~S-5）
```

当前准确定位是：

> **执行环境抽象已经存在；首个真实沙箱提供方（OpenSandbox）已按「能力预研 + 契约落地」接入，
> 平台首次具备可诚实标注 `ISOLATED` 的执行环境（filesystem/resource/credential = `ISOLATED`，
> network/process = `PARTIAL`——刻意不标全 ISOLATED，防「安全沙箱已完成」误读）。**

已落地（`S-1`~`S-5`，2026-10-17，见 `archive/implemented/HelloAI OpenSandbox 沙箱集成计划.md`）：

```text
SandboxSpec                      声明式 spec（type/image/entrypoint/resourceLimits/networkPolicy/scope/timeoutSeconds，纯声明）
ExecutionPolicy.openSandbox()    诚实五边界工厂（3×ISOLATED + 2×PARTIAL）
OpenSandboxSandboxProvider       SandboxProvider 实现（enabled=false 默认关闭，REF-6.13；scope=agent 复用 + 显式回收）
OpenSandboxProbe                 写→读→删回环 + 失败路径也销毁（finally 无条件 kill()）
docker-compose(.server).yml      独立网络 opensandbox-net，app 不加入；api_key 走 config.toml；ACR 镜像源 + python:3.12 预热
```

**执行链已接线（`S-7`~`S-9`，2026-10-17，见 `archive/implemented/HelloAI 沙箱执行链设计方案（2026-10-17）.md`）**：

```text
SandboxExecutionService           执行通道接口（ExecuteResponse 统一三字段）
OpenSandboxExecutionService       执行通道实现（enabled 守卫 / 超时钳制 / 输出截断 / finally 无条件 kill）
internal_run_in_sandbox           内部 LLM 自检 + Reviewer 验证脚本工具（requireInternalChain 闸门 + fail-closed 摘除）
SandboxExecutionToolContributor   条件可用声明（enabled=false ⇒ 工具从模型可见列表摘除）
FinalReportFidelityGate           S-9 机械判定接入点（脚本类交付物可执行性 HARD 规则，checker 保持纯静态）
```

**后续仍需考虑**（未接业务执行链；沙箱内执行 agent 工具链的触发条件见 `ADR-003` §6）：

```text
Filesystem Boundary   → 已落地（ISOLATED，OpenSandbox 沙箱文件系统）
Network Boundary      → 部分落地（PARTIAL：默认禁出 + egress 白名单，容器侧已隔离）
Process Boundary      → PARTIAL（沙箱内进程受限，但共享宿主内核）
Resource Limit        → 已落地（ISOLATED，spec.resourceLimits K8s 风格字符串）
Credential Boundary   → 已落地（ISOLATED，沙箱不注入 app 凭据；Vault 双层隔离属后置增强）
```

**技能执行面已验证能力（SKX-1~SKX-15，2026-11-01 收口）**——只写已验事实，未验标「待核」：

```text
技能包入库           已落地：受控存储（PG 元数据 + MinIO zip），版本锚 + 清单 + 许可（SKX-4）
技能包文件注入       已落地：逐文件 writeFile / tar 注入通道实测（SKX-3 / SKX-6）
沙箱规格按调用分层   已落地：槽 key 带规格指纹（node:20-bookworm-slim，SKX-5）
internal_run_skill   已落地：工具面生效开关（SKX-8）；模型在工具循环自主触发已真机验真（SKX-9）
产物按路径回传       已落地：readFile 三件套（files().readFile，未经命令输出通路，SKX-7 / SKX-11）
技能执行异步化       已落地：脱离 HTTP 线程（SKX-13）；spec 预校验 + $ref 内联自动纠偏（SKX-14）
部署面收口           已落地：SANDBOX_* 贯通 + preflight fail-close + 侧车端口收敛（SKX-15，用户自测通过）
平台级 sha256 三方对账 待核：SKX-9 执行侧死信（7059 DEAD_LETTER / 7060 PENDING），技能按通过回填但不宣称自动化通过
```

# 11. 当前 Workflow / Team

当前已具备：

- Workflow Template / Version；
- Workflow 实例化/物化；
- Team / Member；
- 节点级派发和 Agent 绑定。

当前边界：

```text
Workflow = 编排定义
Scheduler = 运行期调度
```

不要因为未来演进再建立第二套运行时。

# 12. 当前 Reviewer

当前 Reviewer 已形成：

```text
执行结果
  ↓
Review
  ↓
PASS / REJECT
  ↓
REWORK
```

后续目标是把它收敛为 Quality Gate，但现阶段不建立第二套 Review Runtime。

**核验双域（2026-11-04 / 11-05，锚 `G-007`）**——他审与自审分成两域，**均只增强链上判定、不改状态机、不新建 Review Runtime**：

```text
他审（reviewer 侧，RV-x）
  RV-1  纯静态判定校验器（analysis / acceptanceRef / pass↔score / 宽松解析），首批 Warn 档
  RV-2  三态结论 INCONCLUSIVE——判定不成立时不冒充 PASS / REJECT
  RV-3  coverage[] 结构化 + 静态覆盖批评器（判据 = criterionRef 子串归属，不用条数相等）
  RV-4  抽检独立性 + LLM 失败可观测（判定快照漂移则跳过）
  RV-5  判定快照（附件集合 + 产出摘要，落库供后续比对）
  RV-6  驳回侧抽检：驳回即冻结正文原文，复判复用冻结对象（rejected 桶出数口径）
  RV-7  交付正文编码损坏检测（写侧清洗 / 核验侧检测 / 平台失败信号注入 reviewer 可见）
自审（执行收尾侧，SR-x）
  SR-1  收尾轮仅放开 internal_run_in_sandbox（单轮上限按基线差值）
  SR-2  自审证据围栏 EVIDENCE 段 + 解析器
  SR-3  SelfReviewGate + 同 turn 有界重跑
  SR-4  降级与观测（关沙箱档的降级标签）
  SR-5  沙箱调用台账（SR-2 / SR-3 的事实源）
```

**诚实边界**：`G-007` 的目标「Rule + Test + LLM 统一决策」**未启动**，状态仍为「待开始」——上述两域是链上增强，不是统一决策；`RV-1` 的 Block 档与 `RV-3` 的两个 BLOCK 分支经实测**不可自然采样**，目前没有真机证据。依据 `plan/HelloAI 核验双域改造实施计划（2026-11-04）.md`。

**核验证据边界**：附件正文注入进核验 Prompt 时按限额截断（**每份 64000 字符 / 总量 200000 字符**，以匹配官方 DeepSeek 64K 上下文；正常产出不会触发截断），截断处输出结构化标注行 `[TRUNCATED] file=… shown=… total=… reason=…`；核验 Prompt 明确要求「不可见部分一律视为未提供证据，禁止以提交方自报数值/自检清单补全，依赖不可见内容的验收项不得判 pass」。

# 13. 当前个人助理工作台基线

平台的**第二个对话入口**（助理），与需求对话并列——`PA-1`~`PA-7` / `PA-11`~`PA-17` 已落地（2026-11-02 ~ 11-05），差距锚点 `G-028`。

```text
入口          /workbench（views/workbench/Workbench.vue；菜单 V134，曾设 hidden=1）
对话分流      requirement_conversation.kind（V135）：assistant / requirement，读写隔离
助理链路      core.planner.assistant —— AssistantPromptBuilder / AssistantSearchQueryPlanner /
              AssistantSkillDirective / AssistantWorkBriefFormatter / AssistantChatService
身份与记忆    assistant_profile（PA-3）+ long_term_memory 的 USER 类型（PA-2）+ owner 归属（V133）
右栏三段      任务概况轮播 / 最近任务 / 最近产物（WorkbenchQueryService 聚合）
产物三源      执行产物 + 会话附件 + 技能产物（PA-5 归一读取）
模型选择      /listLlmModelOptions（候选源 llm_provider_model）+ 会话 model_type（V142）
              + AgentModelPin 在执行入口覆盖入参 Agent 的 modelType
上下文窗口    token 分项占用 + 手动 / 自动压缩（PA-12）
```

**已落地事实**：

- 助理对话与需求对话**同协议**（SSE 流式 / 思考过程 / 停止生成），但走**独立 pipeline**，不共用需求澄清的编排；
- 右栏收敛为三段；主内容列宽令牌化并支持大屏三档；
- 模型下拉选**无角色纯 LLM 模型**，与需求对话页选 Planner **彻底分离**；所选模型钉住会话，执行入口覆盖入参 Agent 而不改 `AgentTask`、不新增执行链；
- **失效模型不下发**——无 API Key / 供应商已下架 / 模型停用一律从候选集滤掉，排除原因记 debug 日志；
- 活跃 Agent 可见性分两类：内部 LLM 常驻在线**恒可见**、外部 Agent 须心跳 `ONLINE`/`IDLE`，离线者隐藏且条数经 `hiddenOfflineAgents` 显式回传；
- `PA-13`~`PA-17`：模型偏好记忆、先思考后检索（平台内数据类问题跳过联网）、工作概览注入（任务 + 子任务 + 最近事件）、重新生成端点、消息操作条。

**边界**：

```text
PA-8   自动化定时任务         暂缓（登记不启动）
PA-9   插件 / 模板市场        暂缓（无需求来源）
PA-10  IM 渠道 / 移动端       暂缓（条件触发）
执行链                        复用既有 Task / SubTask / Runtime，助理不拥有独立执行器
```

依据 `plan/HelloAI 个人助理工作台实施计划（2026-11-02）.md`、差距表 `G-028`、`LOG-20261103` / `LOG-20261105`。

# 14. 当前明确边界

```text
不绑定单一模型
不绑定单一厂商
不要求 Agent 永远长连接
不允许外部 Agent 直接写 HelloAI 数据库
不允许外部 Agent 绕过平台状态机
不因 Harness 借鉴而复制完整 Harness
```

# 15. 当前基线一句话

> **HelloAI 已经具备异步执行、分布式调度、异构 Agent 接入、可靠性治理、基础执行环境抽象与知识库检索；Agent Event Stream 与 AgentRuntime 已统一收敛（2026-09 收官，经外部执行者双轮全链实测），Event 四项消费面（Timeline / Replay / Audit / Recovery）已于 2026-10-16 全部落地；内部 LLM 已从单轮文本生成器进化为有工具、有手脚、能按需装配技能的 agent（`REF-8.1`~`8.8`），并已能在沙箱容器内自主跑技能包产出可下载产物（`SKX-1`~`SKX-15`，2026-11-01）；核验侧由「单点判 pass」扩为**他审 + 自审双域**（`RV-1`~`RV-7` / `SR-1`~`SR-5`，判定快照、驳回侧冻结复判、编码损坏检测），但**质量门统一决策（`G-007`）仍未启动**；平台侧已建成个人助理工作台（`G-028`）与 LLM 额度监控（`G-026`）两个用户面能力。下一阶段的核心是收敛 Skill Capability 剩余缺口（技能回流贡献规范）、Sandbox Provider 重排期（触发条件②已命中）与项目级交付编排（`G-027`，设计中）。**

# 附录 A. 近期已交付能力（指针，不在本文复述细节）

> 下列能力在《差距表》§1 已判 `DONE`，或**主体已落地但仍有登记中的剩余动作**（后者在表内明确标注「部分完成」并只写已验部分）；本文正文覆盖不到，此处**只给指针与一句事实**，能力细节 / 验收证据一律以《差距表》与对应 `design/` 为准（避免在权威文档里造第二事实源）。

| 能力 | 差距锚点 | 一句话事实 | 细节载体 |
|---|---|---|---|
| 内部 LLM 能力面 | `G-021` | `API_KEY_LLM` 工具面已解封（`internal_*` **进程内通道**，与 MCP 会话解耦：读详情 / 读依赖 / 读状态 / 读附件 / 交付登记 / 技能目录 / 技能惰性加载 / 自报阻塞）；技能装配为「全量目录 + 惰性加载」；`systemPrompt` 非空（角色 + persona 槽位 + 工具指引 + 输出契约 + 验证证据围栏）；循环预算按接入类型分档；上下文压缩两道（剪枝 + 摘要）。**循环面 1 → 5 处**（基线 1 + 任务拆解 / 子任务核验 / 终报告整合 / 终报告审查），其余 11 面按 `plan §10.6` 明确留单次，插点为单咽喉 + `scene` 白名单 + 异常回落单次 | `plan/HelloAI 借鉴落地实施计划.md` §10；`design/Agent_Runtime.md` |
| RAG 知识库 | `G-019` | `kb_chunk`（`V107`，`vector(1024)` + HNSW-cosine）**PG 单后端** + `JdbcTemplate` 余弦检索 + `search_knowledge` 工具（**无 KB 即摘**）+ `char_budget` 注入预算 + 引用溯源 marker（`[kb:source#idx]`，喂模型前剥离、前端渲染可点开卡片）+ 管理端 `/api/kb/*`；生产启用（内容入口与开关）属运维决策（新能力默认关闭） | `design/adr/ADR-002-embedding-provider.md`；差距表 `G-019` |
| 内部 LLM 运行时合同 | `G-023` | 取消 / 超时树（装配点收敛为 `AgentLoopInput` 紧凑构造器「唯一咽喉」，单工具超时降级为失败 observation 交模型决策）；`max-tokens` 截断独立终态 `MAX_TOKENS` + **sticky**（全部出口在 `beforeTerminate` 挂点前定性）；观测面 `AgentUsage` 互斥五分量 + latency 落 `AGENT_COMPLETED` payload；**独立预算闸 `REF-8.9` 登记不启动**（参照实现同样不做，非对齐项） | `plan/HelloAI 借鉴落地实施计划.md` §10.10~§10.12；`review/内部 LLM 执行链运行时合同核对·以 DSH 为基准的重判（2026-10-16）.md` |
| 技能执行面 | `SKX-1`~`SKX-15` | 技能包入库（受控存储 + 版本锚 + 清单 + 许可）、注入通道（writeFile / tar）、沙箱规格按调用分层（node:20-bookworm-slim）、`internal_run_skill` 工具面 + **模型在工具循环自主触发已真机验真**、产物按路径回传（`files().readFile`）、技能执行异步化、spec 预校验 + `$ref` 内联自动纠偏、部署面收口（`SANDBOX_*` 贯通 / preflight fail-close / 侧车端口收敛）。**平台级 sha256 三方对账待核**（技能按通过回填但非自动化通过） | `plan/HelloAI 技能执行面接入实施计划（2026-10-26）.md`；`review/HelloAI SKX-9 端到端验收记录（2026-11-01）.md` |
| LLM 额度与用量监控 | `G-026` | 4 家外部供应商余额在平台内一处可查（DeepSeek / Moonshot 金额型、MiniMax 百分比额度型、博查），只读浮球带抓取时间与失败标记；刷新间隔可配（`sys_config["quota.refresh.interval-seconds"]`，`0`=仅手动 / `60~3600` 秒）；失败不回填 0，通义无公开接口不显示。**诚实边界**：只做「看」，不做额度耗尽联动与阈值告警 | `archive/implemented/HelloAI 额度与用量监控实施计划（2026-11-02）.md`；`design/HelloAI LLM 额度与用量监控设计.md` |
| 个人助理工作台 | `G-028` | 助理成为平台第二个对话入口：`kind` 分流、右栏三段、产物三源归一、会话级模型钉住、上下文压缩、模型偏好记忆 / 先思考后检索 / 工作概览注入 / 重新生成；`PA-8`~`PA-10` 暂缓。详见本文 §13 | `plan/HelloAI 个人助理工作台实施计划（2026-11-02）.md`；`design/HelloAI 助理对话与上下文窗口设计.md` |
| 需求对话附件与联网编排 | `G-024`（部分完成） | 决策视图注入 + 模板「附件优先」硬规则 + 元词过滤 + `ATT-5` 附件意图三态（`answerable` 不联网 / `none` 不注入正文 / `needs_external` 带锚点检索）+ `ATT-6` 补搜多变体；**剩余**：进度文案取真实候选词、检索词由附件外部锚点构成的约束补写 | `plan/HelloAI 需求对话附件实施计划（2026-10-22）.md`；`design/HelloAI 需求对话附件设计.md` |
