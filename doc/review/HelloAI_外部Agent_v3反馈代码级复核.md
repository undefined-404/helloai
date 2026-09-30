# HelloAI 外部 Agent v3 反馈 · 代码级复核

> 输入：`平台问题重测核对表_2026-09-24_v3`（全任务实测）＋`HelloAI平台问题与漏洞分析报告_2026-09-24` v3 第二编（§6~§9）
> 被测 Agent：`workBuddy-executor`（`2102935207752503297`）｜任务 `2103026929396867074`（8/8 子任务 DONE）
> 复核基线：commit `5f48a64`（G-014 T01~T08）｜复核时间 2026-09-24
> 本文只做**代码事实核实与优先级判断**，不含实施方案。

## 0. 结论速览

| 类别 | 项 | 代码核实 |
|---|---|---|
| ✅ 已修并经真实验证 | P0-1 依赖门禁、P0-2 返工出口、P1-3 依赖读取、P1-4-b 附件读端与越权拦截、P2-8 schema | 属实 |
| ⚠ 部分修 | P1-7（`contributors` 可查仍不拦冲突）、P2-10（TTL 口径出新矛盾） | 属实，真因见 §2.2 |
| ❌ 未修 | P1-4-c 渲染 8,000 上限（**且 `output` 同限**）、§6.115 `mergedSkills` 恒 null | 属实，真因见 §2.1 / §2.3 |
| 🆕 新增 P0 | P0-A 重派打满直入死信、P0-B 5 分钟无心跳即判离线 | 属实，且根因比报告描述更具体，见 §1 |

**排序建议（与报告 §9 一致）**：P0-B → P0-A → P1-4-c。P0-B 是 P0-A 的上游触发源，先修可把事故率降一个数量级。

---

## 1. 两项新 P0 的代码事实

### 1.1 P0-B：在线判定（上游根因）

**判定实现**：`HeartbeatServiceImpl.java:197-217`

```java
OffsetDateTime cutoff = now.minusMinutes(5);        // :199 字面量，不可配
if (lastSeen == null || lastSeen.isBefore(cutoff)) {
    if (hasActiveDutyLease(agent.getId())) return IDLE;   // :207-208 租约兜底
    return OFFLINE;                                        // :210
}
```

**报告未指出的两处加重事实**：

1. **阈值双源并存**。`helloai.agent.health.offline-minutes=5`（`AgentHealthProperties.java:41`）只被 `AgentHealthCheckTask` 消费；`checkOnlineStatus` 不读它，用的是硬编码 5。当前两者恰好都是 5，运维一旦调整 `offline-minutes`，即时写回与巡检 cutoff 立即分叉。
2. **"干活被判离线"的范围比报告更广**。13 个 MCP 工具中只有 3 个刷 `last_seen_time`：

| 刷新 `last_seen_time` | 只续租约、不刷 |
|---|---|
| `heartbeat`(`:411`)、`checkIn`(`:636`)、`claimSubTask`/`submitResult`(`:233/:283/:543`，走 `active()`) | `pullTasks`、`ack`、`startSubTask`、`uploadArtifact`、`reportBlocked`、`getAgentStatus`、`getDepsSummary`、`getSubTaskDetail` |

   且 `active()` 的 30 秒节流判断发生在 `seen()` **之前**（`HeartbeatServiceImpl.java:155-166`），节流窗口内连 `seen()` 都不执行——即便调了 `claimSubTask`/`submitResult` 也可能不刷新。

**无确认轮次**：`AgentHealthCheckTask.java:105-108`（一次 Redis `hasKey`）→ `:111-116`（一次 SQL CAS 置 OFFLINE，原因 `heartbeat_lost`）→ `:127` 立即 `reassignStaleTasks`。无探测轮次、无宽限期、无连续丢失计数结构。

### 1.2 P0-A：重派 → 死信

**计数与熔断**：`SubTaskDispatchServiceImpl.java:554`（`incrementAttemptTotal`）／`SubTaskMapper.xml:226-232`；上限 `max-reassign-attempts=5`（`AgentDispatchProperties.java:70`、`application.yml:165`）；达到上限转死信在 `:529-551`，写入 `dead_letter_reason=reassign_attempt_exceeded`。

**确认报告"候选集为空仍计数"**——5 个入口全是"先 +1、后选人"：

| 入口 | 计数行 | 选人行 | 无候选时 |
|---|---|---|---|
| `dispatchBlockedSubTask` | `:54` | `:68` | fast-fail |
| `redispatchOfflineSubTask` | `:75` | `:118` | `AgentUnavailableException` |
| `dispatchPendingSubTaskAuto` | `:135` | `:148` | 抛 `无可用候选 Agent` |
| `redispatchForFallback` | `:227` | `:266` | 抛异常；策略跳过分支同样已 +1 |
| `redispatchAssignedTimeout` | `:361` | `:392` | `log.warn` 后 return |

唯一不计数的是 `dispatchPendingSubTaskAuto:126-132` 的"依赖未就绪"守卫。

**PAUSED 中间态已存在但未被离线链路使用**：状态机 `SubTaskStateMachine.java:22-23` 只有 `IN_PROGRESS→PAUSED` 一条入边、`PAUSED→IN_PROGRESS/CANCELLED` 出边；`SubTaskServiceImpl.java:469/475` 已有 `pause()`/`resume()`，离线处置走的是重派而非 `pause()`。

**恢复通道缺失（确认）**：MCP 13 工具无 `pause/resume/reset/recover`；`SubTaskController` 的 `reassignById`(`:326`)、`redispatchInProgressById`(`:341`)、`redispatchDeadLetterById`(`:356`)、`pauseById`(`:364`)、`resumeById`(`:371`) 全带 `@SaCheckPermission` → API Key 通道不进 Sa-Token 会话（`AuthInterceptor.java:44-56`），由 `GlobalExceptionHandler.java:62-71` 转成 **HTTP 401**（非 403）。不存在 `recoverById`/`resetById`。

**`startSubTask` 自相矛盾回显（一行可修）**：`McpToolServiceImpl.java:324` 在读库前就把入参 `agentId` 塞进 `assignedAgent`；`not_task_owner` 分支（`:334-340`）只写 `ok/started/reason/status`，既不重置 `assignedAgent` 也不回填 `subTask.getAssignedAgentId()`。对照 `claimSubTask`（`:230-232` 校验后才 `setAssignedAgent`）语义正确。

### 1.3 死信残留字段

`dead_letter_reason` 仅 2 处写入（`SubTaskServiceImpl.java:842`、`SubTaskDispatchServiceImpl.java:534`），`changeStatus` 对 context 是**增量合并不重置**（`SubTaskServiceImpl.java:340-344`）。清理路径**不存在**——`redispatchDeadLetter`（`SubTaskDispatchServiceImpl.java:171-222`）只 `ctx.remove("manualIntervention")`（`:200-204`），不清理 `dead_letter_reason` 及其同批写入的 `attempt_total`/`max_reassign_attempts` 快照。

---

## 2. 未修三项的真因（报告未说透的部分）

### 2.1 §6.115 `mergedSkills` 恒 null

**不是"没实现"，是 REST 两条通道根本不读 `skills` 入参**。合并逻辑存在（`McpToolServiceImpl.mergeReportedSkills:662-686`，落 `agent.skills` 列），但：

| 通道 | 是否传 skills | 结果 |
|---|---|---|
| MCP SSE（`McpMcpServer.java:425` + `parseCsvSkills:436-448`） | 是（逗号分隔串） | 唯一可能非 null |
| REST jsonrpc（`McpController.java:403-408`） | **否**，调 4 参重载 | 恒 null |
| REST 直通 `POST /api/mcp/tools/checkIn`（`McpController.java:199-209`） | **否**，调 4 参重载 | 恒 null |
| 4 参重载 `McpToolServiceImpl.java:613-616` | 硬编码 `null` | 恒 null |

**附带类型漂移**：`tools/list` 声明 `checkIn.skills` 为 `array<string>`（`McpController.java:292-297`），而唯一消费它的 SSE 侧按逗号分隔字符串解析。按 schema 发数组调用任一 REST 通道，技能既不生效、回显也必为 null。

**另注**：`getAgentStatus` 结果对象（`McpToolService.java:292` 起）**没有任何技能字段**，故也无法从该工具反查。

### 2.2 P2-10 `remainingTtlSeconds` = 14399

不是"两套数据源"，而是**同一行被 `adaptiveRenew` 二次改写**：

- `checkIn` → `resolveTtlMinutes(agentId, 60)` → `startLease` → `expire_time = now + 60min`（准确）；
- 其后任意一次工具调用 → `refreshDutyLease` → `adaptiveRenew`（`AgentDutyLeaseServiceImpl.java:302-311`）：只要 `hasInFlightSubTask` 为真就**无条件取 `maxTtlMinutes=240`**（`:307-309`），叠加单调钳制（`:222-224`）后不再回落 → 14399 = 240×60 − 1。

根因：`agent_duty_lease` 表**未持久化 `ttl_minutes`**，调用方显式请求的 60 分钟无处保存，只能重新推断。

### 2.3 P1-4-c 渲染 8,000 上限（含 `output` 同限）

常量：`ReviewEvidenceAssembler.java:37/40/42` → `OUTPUT_SUMMARY_LIMIT=4000`、`ATTACHMENT_CONTENT_PER_FILE_LIMIT=8000`、`ATTACHMENT_CONTENT_TOTAL_LIMIT=24000`。

**报告新增的"`output` 同受此限"经代码证实**：
`submitResult` → `ExecutionResultHandler.java:272-281` → `ExecutionArtifactServiceImpl.materialize`；`ExecutionOutputParser.java:34-44` 未命中 manifest JSON 时降级为单文件，文件名 `<子任务标题>.md`（`:47-57`），content 即 output 全文 → `ExecutionArtifactServiceImpl.java:114-116` `attachmentService.register(...)` 写成一条 attachment → 核验侧按普通附件走 8000 截断。

**`deps[].content` 上限 4000**：`McpToolServiceImpl.java:88` `DEP_CONTENT_MAX_CHARS`；同源另一处 `SubTaskExecutionServiceImpl.java:89`。

---

## 3. 其他新增发现（代码事实）

| 项 | 事实 |
|---|---|
| 字段名 | MCP `AttachmentItem.attachmentId`（`McpToolService.java:211`）；REST `/api/attachments` 返回实体用 `id`（`BaseEntity.java:15`）→ **两通道命名不一致** |
| 误导性 500 | `ArtifactUploadServiceImpl.java:51-53` 抛单参 `BizException`（默认 code=500，`BizException.java:10-13`），`GlobalExceptionHandler.java:44-47` 把 code 直接当 HTTP 状态；把「归属他人 / `assignedAgentId` 为 null / `subTaskId` 取错」三种原因压成同一句话 |
| `downloadById/None` | `@PathVariable("id") Long` 类型转换失败 → `MethodArgumentTypeMismatchException` 无专门 handler → 兜底 500 +「服务内部错误」 |
| P1-7 冲突不拦 | `contributors` 已下发，但分派/认领链路无 `conflict_of_interest` 判定 |

---

## 4. 待决策（含需重议的历史决策）

1. **G6「`status` 不改名 `leaseStatus`」是否维持**：v3 报告 §8.4 再次提出改名（破坏性 API），与既有决策冲突，需确认。
2. **是否新增 `resumeSubTask` MCP 工具 + 死信管理端点**：报告 §7.5 要求；涉及 MCP 工具面 13→14 与 §10 红线（不得加 `@SaCheckPermission`）。
3. **G3「限额保持现状 + Prompt 硬化」是否重议**：当时**未发现 `output` 亦受 8,000 限制**，信息已变，需重新决策（选项：限额上调 / 改为按子任务总预算 / 仅写入文档并说明 output 同限）。
4. **P1-6 未复现**：报告判定「本次 8 条核验意见均有实证基础」，但机制未变（`[TRUNCATED]` 标注与规则 11 已上线）。建议保留观察，暂不追加改动。

---

## 5. 已确认无误的信息

- 我此前记录的「规约禁止新增 `verify*.ps1`」**不成立**：规约 §26 仅规定「`.tmp` 中的脚本不得作为正式验证脚本」，`scripts/powershell/verify-g014.ps1` 的新增不违规。（本条为更正记录）
- 上轮 P0（`_authId == null` 误伤平台账号）已修复为按 `_authType == "agent"` 判定；复现用例 `AttachmentControllerAuthScopeTest` 已随 `5f48a64` 提交并转绿。
- 回归实测：`helloai-core` 888 用例 / 143 类 + `helloai-api` 69 用例 / 13 类 = **957 全绿，0 失败**。
