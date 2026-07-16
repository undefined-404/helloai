# HelloAI 实现差距表

## 1. 文档定位

本文档用于回答：

- 文档原先承诺了什么
- 当前代码实际做到了什么
- 差距属于“应改文档”还是“应补功能”

本文档是当前版本对表、验收与排期的主参照文档。

---

## 2. 当前总体结论

- 已交付闭环：MCP SSE 接入、鉴权、核心工具链路、在线状态三件套、熔断降级、Reconcile 健康检查
- 文档失真：历史路线图、技术方案与部分说明文档曾混入过时事实或实施日志
- 部分落地：执行命令主链、Provider 配置复用、工牌模式与 `credential_vault`
- 未落地：工作流模板、浏览器型 Agent 真实接入链路

---

## 3. 文档失真项（D）

| 编号 | 主题 | 当前状态 | 结论 |
|---|---|---|---|
| D1 | MCP 工具数量口径 | ✅ 已关闭 | README 已明确：工具数量不写死，以 `tools/list` 实际输出为准（2026-07-13） |
| D2 | 兼容通道定位 | ✅ 已关闭 | README 已明确：MCP SSE 是唯一主通道，REST `tools/list` / `tools/call` 属兼容保留（2026-07-13） |
| D3 | 路线图正文混入实施日志 | ✅ 已关闭 | 实施记录已迁移到《迭代执行记录》，路线图已退出事实源 |
| D4 | Spring AI 版本口径 | ✅ 已关闭 | 现行版本以代码、基线文档与回归结果为准 |
| D5 | `/api/tools/cli` 鉴权口径 | ✅ 已关闭 | 代码验证：`WebMvcConfig` 中 `/api/tools/cli` 已通过 `excludePathPatterns` 排除鉴权（公开下载入口，设计如此）。历史技术方案不再作为事实源（2026-07-13） |
| D6 | 心跳刷新规则口径 | ✅ 已关闭 | README 已明确：`last_seen_at`/在线态刷新以 `heartbeat` 为主；仅将 pull/ack 也计入活跃需代码配合再升级（2026-07-13） |
| D7 | README 文档边界 | ✅ 已关闭 | README 已收口为项目介绍与使用说明，不再承载阶段性执行结论 |
| D8 | PS 5.1 脚本 UTF-8 编码规范未沉淀到 skills | ✅ 已关闭 | 5 份 `helloai-preflight` SKILL.md（.agents/.qoder/.trae/.cursor/.claude）+ `AGENTS.md` 同步落地“脚本必须显式声明 UTF-8 编码”规则，覆盖运行时输出编码（[Console]::OutputEncoding / $OutputEncoding）、源文件 BOM（UTF-8 with BOM + `Parser.ParseFile` 自检）、管道原始字节传输（`cmd /c type` / `[Diagnostics.Process]`）、here-string 串入 U+FEFF 隐限（入口 `TrimStart([char]0xFEFF)`）四个子项。以后任何新增 verify-*.ps1 / start-*.ps1 / test-*.ps1 / hook / CI 脚本都应遵循（2026-07-16） |

---

## 4. 路线图项差距（N）

| 编号 | 主题 | 当前状态 | 差距定义 | 处理建议 |
|---|---|---|---|---|
| N1 | Outbox / 命令可靠投递底座 | ②a + ②b 已落地 | `AgentOutboxService`（SubTask 状态变更事件）已具备事务性 Outbox 能力；**Phase 2H ②a** 新增 `agent_command_outbox` 表 + `AgentCommandOutboxService`（5 个最小方法：createPending / listReadyForRelay / markSent / markFailed / markFinalFailed）+ `OutboxRelayTask`（helloai-job，Redis 锁 + 应用侧指数退避）+ `AgentCommandOutboxRelayProperties`（`enabled/interval-ms/batch-limit/max-retry/base-backoff-seconds`）。`ExecutionCommandService` 在 `dispatch-mode ∈ {MQ,BOTH}` 时改为同事务写 outbox，OutboxRelay 异步取行调 `ExecutionCommandMqPublisher` 真正投递；`aggregate_type` 固定 `EXECUTION_COMMAND` 防止后续统一 outbox 语义发散。**Phase 2H ②b（2026-07-15）扩展为可 CONFIRMED + 可对 NACK/超时回退重试：** Flyway V20 将 `status` 由 VARCHAR(32) 收敛为 SMALLINT（兼容 `PENDING/SENT/FAILED/CONFIRMED` ↔ `0/1/2/3`），新增 `last_sent_at` / `confirmed_at`，CHECK 约束扩展到 `0/1/2/3`，重建 PENDING 部分索引并新增 `idx_agent_command_outbox_sent_scan` SENT 部分索引；`AgentCommandOutboxStatus` 新增 `CONFIRMED(3)`，`AgentCommandOutboxService` 新增 `listExpiredSentForRetry / markSent(id,sentAt) / markConfirmed(id,confirmedAt) / markFailedFromSent / markFinalFailedFromSent`；`ExecutionCommandMqPublisher` 新增 `publishWithCorrelation(command, correlationKey)` 返回 `CorrelationData`，`OutboxRelayTask` 在 `publishWithCorrelation` 后异步注册 confirm 回调，区分 ACK/NACK/return/超时四种路径，命中 ACK 且无 return 时 `markConfirmed`，其它路径走 SENT→PENDING 指数退避回退或 SENT→FAILED 终态；`application.yml` 开 `publisher-confirm-type: correlated` + `publisher-returns: true`，`RabbitMQConfig` 注册 `ConfirmCallback`/`ReturnsCallback` 并 `setMandatory(true)`；`OutboxRelayTaskTest` 用例从 5 扩到 7（新增 Confirm NACK、SENT 超时）。本轮明确不做：Poller 降级、`OutboxCompensationTask` 新增调度（沿用 `OutboxRelayTask`）、DLQ、per-event 业务级熔断、旧 `ExecutionCommandMqPublisher.publish()` 静默丢失 confirm future 的清理（单独立项 R2） | 继续推 T4 RabbitMQ E2E 失败可恢复 → T5 Poller 降级；T6 §5.2 后置 |
| N2 | 可配置工作流模板 | 未落地 | 缺模板表、模板管理、模板化调度入口与 Team 编排 | 后续独立迭代 |
| N3 | MCP Server 工具集 | 已交付 | MCP SSE 主通道工具链已可用，已具备外部 Agent 最小执行闭环能力：`pullTasks/ack/claimSubTask/heartbeat/uploadArtifact/submitResult/reportBlocked/getAgentStatus`（其中 `submitResult` 对接统一回写入口，`reportBlocked` 记录阻塞原因证据链） | 保持现状 |
| N4 | 心跳与在线判定 | 已交付 | 在线态三件套、在线计算态与巡检收敛已具备 | 保持现状 |
| N5 | 熔断降级 | 已交付 | per-agent 熔断与同角色替补策略已具备 | 保持现状 |
| N6 | 执行命令消费与结果回写 | 已交付（含 T5 Poller 兜底 + Validator 启动期 fail-fast 闭环） | MQ 主链已连通：Phase 2D MQ Consumer 骨架 / Phase 2E 生产端接入（`ExecutionCommandMqPublisher` + `dispatch-mode` 四挡 + 双开关 + DispatchValidator producer-fail-fast）/ Phase 2F 两个阻断性修复（事务时机 `afterCommit` + 显式 JSON 序列化）/ Phase 2G 双路消费 E2E（DB CAS + Redis + `event_consumption_log` 三层幂等抵消）/ Phase 2H ②a Outbox 最小闭环（`agent_command_outbox` 同事务写入 + `OutboxRelayTask` 1000ms 扫描 + Redis 锁）/ Phase 2H ②b Confirm/Retry（`PENDING/SENT/CONFIRMED/FAILED` 四态 + `CorrelationData` + publisher confirms + Confirm-aware Retry + SENT 超时回退）/ T4 Outbox ②b E2E（S1 broker NACK / S2 mandatory return / S3 confirm timeout / S4 control happy path 四场景实测，N1 可标已交付证据）。**T5（2026-07-15）Poller 兜底重塑 + Validator 闭环：** `AgentExecutionProperties.ConsumerMode` 三种模式语义全部重塑为 "Poller 仅作孤儿/超时/补偿兜底"，主消费路径由 `MqExecutionCommandConsumer` 或本地事务事件承担；`ExecutionCommandPoller` 删除 `listAllPending` 分支，所有 consumer-mode 统一走 `listOrphanPending`，`trigger` 前缀恒为 `poll-recovery:`、timeline 事件恒为 `sub_task_execution_command_poll_recovery`；`ExecutionDispatchValidator` 新增 `consumer-mode ∈ {POLLER, BOTH}` + `consumer-enabled=false` 启动期 `IllegalStateException` fail-fast（阻止 "主消费路径全关但 Poller 仅兜底，PENDING 永远不被消费" 的事故形态），保留 `dispatch-mode ∈ {MQ, BOTH}` + producer 缺失 fail-fast 与 consumer=false WARN；`application.yml` `helloai.mq.execution-command.consumer-enabled` 默认值 `false → true` + 灰度节奏注释（MQ 环境就绪 → 主链稳定 → 可选退回 EVENT）；`AgentExecutionRecordService.listAllPending` 加 `@Deprecated` 兼容保留；`ExecutionCompensationTask` 保持独立（不并入 Poller，职责边界清晰）；`ExecutionCommandPollerTest` 删除 PollerMain 嵌套类（5 个 listAllPending 主路径用例）+ 新增 DowngradeConsistency 5 用例；`ExecutionDispatchValidatorTest` 新建 14 用例覆盖 DispatchModeFailFastOnProducer / DispatchModeFailFastOnRelay / ConsumerModeFailFast / DispatchWarnOnConsumerDisabled / ValidCombinationsAndPriority 五类场景；`verify-poller-e2e.ps1` 已升级到 v3.1，并新增 **S5** 等价验证：直接注入孤儿 `PENDING` 执行记录，验证 `last_attempt_at`、`sub_task_execution_command_poll_recovery` 和 `poll-recovery:` trigger 三项证据链均由 Poller 独自留下。结果回写入口收口为 `ExecutionResultHandler.handleReport(ExecutionResultReport)`，平台内执行链与外部 MCP `submitResult` 统一走该入口。`AsyncExecutionResultConsumer` 改造后置仍待后续独立轮次 | 保持现状；S5 最小等价验证已具备，后续仅保留 **S6（手动 MQ-isolation）** 作为补充对照实验 |
| N7 | 健康检查改写 | 已交付 | Reconcile、离线重分配、兜底收敛已具备 | 保持现状 |
| N8 | 网页版 AI 浏览器接入 | 未落地 | 只有枚举与预留，没有真实接入模块 | 后续独立迭代 |
| N9 | Provider 配置与 ChatClient 复用 | 已交付 | Provider 配置入口已统一（`helloai.providers`）+ provider/model 解析已收口（`AgentProviderResolver`）+ `ProviderChatModelCache` 按 (provider, baseUrl, apiKey 指纹) 缓存 ChatModel 实例（避免每次 new DeepSeekChatModel）；`DeepSeekProviderChatClientFactory` 已接入缓存，SHA-256 指纹保证明文 API Key 不入 cache key | 保持现状 |
| N10 | 工牌模式 + `credential_vault` | 部分落地 | 最小模型、绑定与托管语义已具备；Agent API Key 的最小轮换语义已落地（`ACTIVE / EXPIRED` + 审计备注），但迁移、过渡期双活策略与权限颗粒度仍未收口 | 继续补功能 |
| N11 | 调度策略：外部优先 + 空闲优先 + LLM 保底 | 已交付 | 候选选择策略收口为可配置项（`preferExternal` / `requireIdle` / `forceAccessType` / `autoAssignOnCreate`），并已落地“外部 Agent 连续失败阈值后自动回退到平台内 API_KEY_LLM”闭环：V17 补 agent.consecutive_failure_count/last_failure_at/last_fallback_at + sub_task.external_fallback_count；`ExternalAgentFailureTracker` 在 `ExecutionResultHandler.handleReport` / `ExecutionCompensationTask` / `AgentHealthCheckTask` 三处统一累加与重置；`SubTaskDispatchService.redispatchForFallback` 绕过 `AgentSelector`（不被 preferExternal 影响）直接选同角色 API_KEY_LLM Agent；`ExternalAgentFallbackTask`（helloai-job，60s 周期 + Redis 锁）扫描超阈值 CLI_CLIENT Agent 触发重新分发；阈值与冷却期可由 `helloai.dispatch.fallback.{failure-threshold,cooldown-minutes}` 调节 | 保持现状 |
| N12 | AgentHub V1 P0：值班租约闭环 + 值班优先调度 | 已交付 P0 | 2026-07-16 落地 AgentHub V1 P0 三件（checkIn / checkOut / DutyLeaseExpirationTask）真实环境 E2E：`agent_duty_lease` 表（V18）承载 Agent 值班租约，状态机 `ACTIVE / CLOSED / EXPIRED`（`uk_duty_lease_agent_active` partial unique index 防止同一 Agent 多条 ACTIVE 行）；`AgentDutyLeaseService.checkIn` 赋予 Agent 值班权（workMode=NORMAL/STRICT/maxConcurrent/ttlMinutes）+ heartbeat 联动；`checkOut` 主动闭锁并记录 closeReason（服务端参数名 `closeReason` 为准，旧字段名 `reason` 兼容）；`DutyLeaseExpirationTask`（helloai-job，`@Scheduled fixedRate=30_000` + Redis Lua 锁）扫描过期 ACTIVE 租约翻为 `EXPIRED`，close_reason=`lease_expired`；`AgentSelector.pickAlternative` 增加 `dutyRank` 排序（值班中的 Agent 优先于未值班），多候选用例排序时调用 `agentDutyLeaseService.isOnDuty(agentId)`，`lenient()` mock 避免单候选用例 UnnecessaryStubbing。`MyBatisPlusMetaObjectHandler.insertFill` 补 `consecutiveFailureCount=0` 默认填充（v2.4 N11 字段遗漏修复）。`McpMcpServer` 补 `checkIn` / `checkOut` 工具（`@Tool` + `@ToolParam`），认证上下文 `requireAuthId(sessionId,_sessionId)` 覆盖客户端传的 agentId。`agent_mcp_server` 表 V21 seed 新建 Agent 自动启用 `checkIn/checkOut`（partial unique index `idx_ams_agent_tool WHERE deleted=0`，ON CONFLICT 子句必须显式带 partial 条件）。E2E 脚本：`verify-agenthub-duty-e2e.ps1`（S1 checkIn / S2 checkOut / S3 Lease 过期扫描 3 场景实测通过 ALL PASSED）。skill 规则 6 + D8 同步落地 PS 5.1 脚本编码防护四原则。本轮明确不做：dashboard、调度重新计入值班权重以外的二次排序、`workMode=STRICT` 下的独占报锁语义、动态 TTL 自适应 | 保持现状；后续 P1 可考虑值班报表 + 多 Agent 同时值班的 concurrency 预扣语义 |

---

## 5. 当前建议优先级

1. ~~先继续关闭文档失真项：D1、D2、D5、D6~~ ✅ 全部已关闭（2026-07-13）
2. ~~D7、D8 已关闭（2026-07-13 / 2026-07-16）~~
3. 再继续夯实执行链：N1 ②a + ②b 已落地（最小闭环 + Confirm/Retry）→ T4 RabbitMQ 失败可恢复 E2E ✅ → T5 Poller 降级 + Validator 启动期 fail-fast 闭环 ✅；N6 标已交付（含 T5 + S5 最小等价验证）；后续仅保留 **S6**（手动 MQ-isolation）作为补充验证
4. AgentHub V1 P0 已交付（2026-07-16）：N12 值班租约闭环 + 值班优先调度；E2E 脚本 `verify-agenthub-duty-e2e.ps1` 可重复回归
5. N9 / N10 / N11 维持现状
6. 最后推进产品化编排能力：N2、N8
7. §5.2 后置（WorkUnit / STOP/PAUSE/REPLAN / 用户输入可重入）—— 等可靠投递与兜底职责收紧之后再开

---

## 6. 本轮文档治理结论

- `v3.0` 已不再作为路线图继续维护，而是降级为《架构设计参考》
- `v2.4` 已归档为历史资产，不再作为事实源
- 后续若出现“设计理念、目标态、实现状态、执行记录”混写，优先拆回文档矩阵，而不是继续往单个文件里堆内容
