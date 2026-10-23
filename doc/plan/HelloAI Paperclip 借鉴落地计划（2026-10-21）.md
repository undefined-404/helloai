# HelloAI Paperclip 借鉴落地计划（2026-10-21）

> **状态：Active（阶段一 1~5 已落地，6 框架就绪待 G-008；阶段二按安全前提挂起）**
> 最后更新：2026-10-22
>
> **阶段一进度（2026-10-23 复核）**：**1~5 已实施并提交**（`env 黑名单` / `唤醒原因` / `409 语义固化` / `三配额分离` / `双入口一致性契约`，见 `LOG-20261022-004`）；**6（成本闸门）框架设计已就绪（§1.6），前置已重新定级** —— 原判「`agent_execution_record.token` 全 null」经实测**不成立**：37 条执行记录全为内部 LLM，SUCCESS 且带 token **22 条**（8.4 万~17 万 tok），null 的 12 条均在 `V97` 迁移前；但按 agent 拆开仅 1 个达 21 条、第 2 个仅 1 条，**候选间比较尚未生效**。故前置由「等数据积累」改为「**可接线**（机制可验证、单 agent 成本画像成立），拦截效果待第 2 个 LLM agent 攒够 ≥5 次成功」。§7.2 预算来源口径仍待定，不写悬空代码。见差距表 `G-008` 与 `LOG-20261023-005`。
>
> **依据**：`research/Paperclip 外部Agent接入与CLI适配器借鉴分析（2026-10-21）.md`（`状态：Analysis`，全部结论带 `[实测]` 路径:行号）。
> **定位**：本文件只回答「先做哪个、多大代价、什么前提」；「借不借、为什么」的证据在调研文档里，此处不重复论证。**阶段一 6 项已展开为实施设计（§1.1 ~ §1.6），每项含现状代码定位 / 改动方案 / 验收标准，可直接按 §5 执行序开工。**
> **编号口径**：**不引入新编号前缀**。条目以「阶段 + 序号」寻址（如「阶段二·1」），避免与现有 `G-` / `REF-` / `M-` / `W-` / `T-` 号段冲突（《差距表》§0）。
> **上游对象**：`E:/workspace/paperclip-master`（MIT），本地副本。

---

## 0. 总纲

**Paperclip 是「推模型」平台（自己 spawn CLI 进程）；helloai 是「拉模型」平台（agent 心跳 + 领活）。两者不是替代关系，是两个市场：**

| | 解决的问题 | 前提 |
|---|---|---|
| Paperclip | 我有**一台机器**，上面跑 20 个 Agent，怎么管 | 平台拥有执行环境 |
| HelloAI | Agent 散在 **20 个人的电脑上**，怎么调度 | 平台**不**拥有执行环境 |

⇒ **现有拉模型继续做深，同时补一条并行的推通道（CLI 接入）。绝不推翻现有架构。**

---

## 1. 阶段一：现有机制补硬（不需要新架构、不需要新容器）

> **2026-10-21 实施设计展开**：下表按**推荐执行序**排列（编号 = 执行序，非原始序号）。每项的完整实施设计（现状代码定位 / 改动方案 / 验收标准）见 §1.1 ~ §1.6。
> **本次现状核验的两处重要修正**：
> - 原「409 抢占」项（现 §1.3）：`claimSubTask` 已有 DB 条件更新原子占位（`claimAtomic`，`McpTaskClaimServiceImpl:274`），成本由「中」降为「低」——剩下的是**协议语义固化 + 并发测试**，不是新建原子机制。
> - 原「成本闸门」项（现 §1.6）：`agent_execution_record.token` 已落库（`ApiKeyAgentExecutor:59-62`），缺的是**任务级累计 + 派发前阈值查询**；**前置 = `G-008` 的「候选间比较」**（实测单 agent 已有 21 条成本画像，多候选比较待第 2 个 agent 攒够 ≥5 次成功）。

| 执行序 | 借什么 | 解决 helloai 什么问题 | 落点 | 成本 | 前置 |
|---|---|---|---|---|---|
| 1（原4） | **配置 env 不可覆盖运行时 key**（黑名单 + 运行时优先） | 一旦执行 env 支持配置注入（阶段二 CLI 通道必然引入），配置页填一个 env 就能拿到敏感 key，是典型越权路径。**当前 `ExecutionEnvironment` 只是纯标签（无 env 注入），是「即将出现的缺口」，先立黑名单契约** | `ExecutionEnvironment` / `ExecutionEnvironmentProvider` / 装配侧 | 低 | 无 |
| 2（原1） | **唤醒原因写进响应体**（`issue_assigned` / `comment_mentioned` / `retry` / `continuation`） | 现在 Agent 领活后自己判断该干什么，判断错就白跑一轮 | 领活接口响应 + 心跳返回 | 低 | 无 |
| 3（原2） | **checkout / 409 抢占语义**（原子占位已有，固化协议：拿不到就换活，**永不重试 409**） | 「心跳在线 + 打分」挡不住并发双派，网络抖动下会同一任务派两人。原子占位已存在，缺**标准化 reason + 并发测试** | `claimAtomic` / 认领协议文档 / 并发 IT | 低（原评估中） | 无 |
| 4（原3） | **retry / continuation / recovery 三配额分离** | 现在「超时 / 无输出 / 报错 / 空回复」混在一个失败计数里，空回复吃掉真失败的配额 | `ExternalAgentFailureTracker` | 中 | 无 |
| 5（原5） | **MCP 工具里不写业务逻辑**（薄封装复核 + 双入口一致性契约） | MCP 与 REST 两个入口两套规则，必然漂移。现状已基本是薄封装，需复核残留 + 加契约测试 | `McpToolServiceImpl` 复核 | 中（主要是复核） | 无 |
| 6（原6） | **成本累加 + 80% / 100% 双阈值闸门**（软告警 / 硬停派发） | 完全空白。失控 subtask 可以无限重试烧钱。token 已落 `agent_execution_record`，缺**任务级累计 + 派发前查阈值** | `agent_execution_record` 聚合 + 预算字段 + 派发前闸门 | 中 | **`G-008` 候选间比较**（单 agent 画像已成立） |

> 阶段一全部可在现有拉模型内完成，**不引入任何执行通道**，是最安全的第一批。
> 推荐执行序逻辑：**安全缺口（1）与成功率（2）先行**——成本低、直接见效；**3（协议固化）紧随**——原子基础已存在，补测试即闭环；**4/5 中成本重构**随后；**6 最后**——依赖 G-008 数据积累，先做框架等数据。

---

### 1.1 实施设计 · 配置 env 不可覆盖运行时 key（黑名单 + 运行时优先）

**Paperclip 对照**：`execute.ts:296-317` env 三层合并（base → 配置文件 → 运行时），运行时优先。

**现状代码定位（helloai 侧）**：
- `helloai-core/.../agent/runtime/ExecutionEnvironmentProvider.java:32-37` —— `resolve(accessType)` 只按 accessType 从 `environments` 列表过滤首个命中，返回接口对象。
- `helloai-core/.../agent/runtime/ExecutionEnvironment.java:13-29` —— 接口只有 `name()` / `supports()`，**无 env 注入能力**（`AgentContext.environment` 是 `ExecutionEnvironment` 类型，`AgentContext.java:64`）。
- 实现类：`RemoteAgentEnvironment` / `LocalProcessEnvironment` / `OpenSandboxEnvironment`（同目录）。
- 消费装配：`LocalExecutionCommandConsumer.java:216-217` → `AgentRuntimeContextAssembler.assemble(..., environment)` → `:217` `.environment(environment)` 注入 `AgentContext`。
- `Agent.java:50-60`：`apiKey` 是 consumerToken 工牌，真实 LLM 凭证在 `credential_vault`，**不落 env**。

**改动方案**：
1. `ExecutionEnvironment` 接口新增默认方法 `Map<String, String> environmentOverrides()`（默认空 Map），三个现有实现返回空 = 行为不变。
2. 新增纯函数 `EnvironmentBlacklist`（`runtime/` 下）：黑名单前缀 `HELLOAI_`、精确键 `HELLOAI_CREDENTIAL_AES_KEY_BASE64` / `CREDENTIAL_AES_KEY*`、注入类键 `JAVA_TOOL_OPTIONS` / `LD_PRELOAD`。
3. 合并顺序固定：`base env < 配置 env（过黑名单） < 运行时 key（最后写入，不可覆盖）`。
4. 在 `LocalExecutionCommandConsumer` 装配处（`:216-217`）过滤：`environmentOverrides()` 命中黑名单 → 丢键 + 告警日志（**只丢键，不拒绝整个执行**）。
5. 单测：黑名单纯函数（前缀 / 精确 / 大小写）+ 合并优先级（运行时 key 恒赢）+ 装配侧集成（override 带 `HELLOAI_CREDENTIAL_AES_KEY_BASE64` 被丢）。

**验收标准**：
- 配置 env 命中黑名单键 → 不进入 `AgentContext.environment`，日志留痕。
- 运行时 key（provider 凭证）恒不被配置 env 覆盖。
- 现有三个 `ExecutionEnvironment` 实现行为不变。
- 单测覆盖黑名单 / 优先级 / 装配，全绿。

---

### 1.2 实施设计 · 唤醒原因写进响应体

**Paperclip 对照**：`heartbeat.ts:689-693` wakeReason 枚举（`issue_assigned` / `comment_mentioned` / `retry` / `continuation`）。

**现状代码定位（helloai 侧）**：
- `helloai-api/.../controller/McpController.java:111-115` —— `POST /tools/heartbeat` → `mcpToolService.heartbeat(agentId)` → `R<HeartbeatResult>`。
- `McpController.java:62-71` —— `POST /tools/pullTasks` → `R<PullTasksResult>`。
- 委托链：`McpToolServiceImpl`（`helloai-core/.../service/impl/`）→ `McpTaskClaimServiceImpl.pullTasks`（`:81`，返回消息带 `summary/read`，**无结构化唤醒原因**）。
- JSON-RPC 入口：`McpController.java:373-398`（`tools/call` dispatch）。

**改动方案**：
1. `HeartbeatResult` / `PullTasksResult` 增加 `wakeReason` 字段（字符串枚举）：`issue_assigned`（有新任务派给本 agent）/ `comment_mentioned`（被评论 @）/ `retry`（失败重试）/ `continuation`（续跑）/ `none`。
2. `heartbeat` 服务端：从待办队列读最近一次唤醒原因（有待领 PENDING 任务 → `issue_assigned`；有失败重试 → `retry`；其余 → `none`）。
3. `pullTasks` 每条消息附带 `wakeReason`，与 `summary/read` 并列。
4. 兼容：新增字段向后兼容，老客户端忽略。
5. 单测：heartbeat 在有待办 / 无待办时 reason 正确，四种枚举映射覆盖。

**验收标准**：
- `heartbeat` 响应含 `wakeReason`，与「当前是否有待领任务」一致。
- `pullTasks` 每条消息带 `wakeReason`。
- 老协议客户端不受影响。
- 单测全绿。

---

### 1.3 实施设计 · checkout / 409 抢占语义（协议固化 + 并发测试）

**Paperclip 对照**：`heartbeat-protocol.md:56 / 119-127` 409 抢占 + Run Liveness：「拿不到就换活，**永不重试 409**」。

**现状代码定位（helloai 侧）**：
- `helloai-core/.../service/impl/McpTaskClaimServiceImpl.java:167-303` —— `claimSubTask` 全流程：
  - `:178-214` 幂等分支（已归属自己 → 成功，认领即开工 `start` + `openExternalAttempt`）；
  - `:217-223` 已归属他人 → `claimed=false` + `already_claimed_by_other`；
  - `:226-232` 非 PENDING → `invalid_status:*`；
  - `:242-259` 准入闸门（白名单 + 必需技能）、`:265-271` 依赖闸门（`dependency_not_ready`）；
  - `:274` **原子条件更新 `claimAtomic`**（DB `WHERE status='PENDING' AND (assigned_agent IS NULL OR = agentId)`）——并发安全核心，与 Paperclip 409 语义等价；
  - `:275-279` 失败 → `claimed=false` + `race_condition_or_invalid_status`。
- 内部执行链同款：`LocalExecutionCommandConsumer.java:152` `markRunning` CAS（PENDING→RUNNING）。

**结论**：原子占位**已存在**。真正缺口是：① reason 语义混（`race_condition_or_invalid_status` 混合「抢不过」与「状态非法」）；② **无并发测试**；③ 客户端「永不重试 409」的协议说明未文档化。

**改动方案**：
1. 拆分 reason：`claimed=false` 时区分 `already_claimed_by_other`（= Paperclip 409，客户端**永不重试、直接换活**）/ `invalid_status`（状态非法，客户端上报）/ `dependency_not_ready`（前置未就绪，稍后重试或换活）。
2. 认领协议文档化：MCP 工具 `claimSubTask` 的 description（`McpController.java:292`）写明 409 语义与「勿重试，换下一个活」。
3. 新增并发 IT：`McpTaskClaimConcurrencyIT` —— 两 agent 并发 claim 同一 PENDING，断言恰一个 `claimed=true`、另一个 `claimed=false` 且 reason = `already_claimed_by_other`；**ID 段 9701**（Testcontainers 共享库约定，避开 9101~9601 已占用段）+ `@AfterEach` 清场。
4. （可选）`pullTasks` 附带 `claimable` 预判，减少无效认领。

**验收标准**：
- 双 agent 并发认领同一 PENDING：恰一赢一输，输家 reason 可被客户端识别为「应换活」。
- `claimed=false` 的 reason 枚举与 Paperclip 409 语义一一对应。
- 并发 IT 通过（ID 段 9701 无冲突）。
- 协议说明写入 MCP 工具 description。

---

### 1.4 实施设计 · retry / continuation / recovery 三配额分离

**Paperclip 对照**：三配额分离（retry / continuation / recovery 各自独立配额，互不挤占）。

**现状代码定位（helloai 侧）**：
- `helloai-core/.../agent/observability/ExternalAgentFailureTracker.java` —— `recordFailure` / `recordSuccess` / `markFallbackTriggered` 用 `agentMapper.incrementConsecutiveFailure` **单一 `consecutive_failure_count`** 计数；仅 CLI_CLIENT 生效；`REQUIRES_NEW` 事务。
- 消费侧：`helloai-core/.../agent/command/ExecutionResultHandler.java:591` `applyFailureTracking` → `:518-522`。
- 判定侧：`shouldFallback` / `findFallbackCandidates`（`AgentSelector`）基于同一计数。

**改动方案**：
1. 拆分计数：`retry_count`（显式 retry 指令）/ `continuation_count`（续跑）/ `recovery_count`（恢复路径）三列独立配额。
2. `recordFailure` 增加 `FailureCategory` 入参：`TIMEOUT` / `NO_OUTPUT` → `recovery`（严格）；`ERROR` → `recovery`；`EMPTY_REPLY` → `continuation`（可容忍）。映射表集中定义，供单测断言。
3. 存量 `consecutive_failure_count` 保留作总览字段（消费方查全再定是否退役，**先改语义后动列**）。
4. 单测：各类失败归入正确配额；配额耗尽触发 fallback 时机正确；空回复不再挤占真失败配额。

**验收标准**：
- 三类失败各自计数、各自配额，互不挤占。
- 空回复归 `continuation` 配额，不触发真失败（`recovery`）的 fallback。
- `shouldFallback` 判定基于新配额语义，行为与文档一致。
- 单测全绿。

---

### 1.5 实施设计 · MCP 工具薄封装复核（双入口一致性契约）

**Paperclip 对照**：`mcp-server/README.md:1-6` MCP server 是薄封装 + 一个受限 escape hatch。

**现状代码定位（helloai 侧）**：
- `helloai-core/.../service/impl/McpToolServiceImpl.java` —— 工具方法全部委托领域服务（`taskClaimService.pullTasks/ack/claimSubTask/startSubTask`、`agentReportService.reportBlocked`、`heartbeatService.seen` 等），**已基本是薄封装**。
- `helloai-api/.../controller/McpController.java` —— REST 入口（`/tools/*`）+ JSON-RPC 入口（`tools/call` dispatch `case "pullTasks" -> ...`，`:373-398`），两入口共享同一 `McpToolService`。
- 既有测试：`helloai-core/src/test/.../agent/mcp/McpToolServiceTest.java`。

**改动方案**：
1. 复核 `McpToolServiceImpl` 每个方法：业务规则是否全部下沉领域服务；残留的直接业务逻辑（如有）下沉。重点检查 `assertToolEnabled`（横切校验，可留）与 `heartbeatService.active` 触发点是否与领域语义一致。
2. 补「双入口一致性」契约测试：断言 REST 与 JSON-RPC 入口对同一入参产出同一结果（共享同一实现，防未来漂移）。
3. 无新业务逻辑写入 `McpToolServiceImpl`（回归守卫：新增工具方法必须只做「参数解析 + 委托」）。

**验收标准**：
- `McpToolServiceImpl` 无新增业务规则，全部委托领域服务。
- REST 与 JSON-RPC 两入口行为一致（同一实现）。
- 既有 `McpToolServiceTest` + 新增契约测试全绿。

---

### 1.6 实施设计 · 成本累加 + 80% / 100% 双阈值闸门

**Paperclip 对照**：`costs-and-budgets.md:42-49` 成本累加 + 双阈值（80% 软告警 / 100% 硬停派发）。

**现状代码定位（helloai 侧）**：
- `helloai-core/.../agent/entity/AgentExecutionRecord.java:28` —— 已有 `token` 字段（「loop 全部轮次 totalTokens 累加」）。
- `helloai-core/.../agent/executor/ApiKeyAgentExecutor.java:59-62` —— `totalTokens` → `recordUsage(...)` 落库。
- `helloai-core/.../agent/service/AgentExecutionRecordService.java:34` —— `markSuccess` CAS 落 Token 用量。

**缺口**：只有「单次执行」的 token 记录，**无「任务级累计」+ 派发前阈值查询**。`agent_execution_record` 目前只有内部 LLM 执行在写；外部 agent 执行（`openExternalAttempt`）的 token 来自客户端上报，需先有数据（= G-008 前置）。

**改动方案**：
1. 预算字段落库：`sub_task`（或 `task`）级新增 `token_budget` + `token_spent`（累计）。
2. 聚合消费：`agent_execution_record.token` 按 `sub_task_id` 聚合 → 更新 `token_spent`（幂等：同一 record 只计一次）。
3. 闸门：派发 / 认领前查阈值 —— `token_spent / token_budget ≥ 80%` → 软告警（日志 + 通知）；`≥ 100%` → 硬停派发（拒绝新 subtask / 拒绝认领，reason = `budget_exhausted`）。
4. 预算来源：任务创建时设定（或按 provider 历史均值推导，默认不拦截 = 向后兼容）。
5. 单测：聚合正确（幂等）、阈值判定、硬停路径。

**验收标准**：
- `agent_execution_record.token` 能幂等聚合到 sub_task 级 `token_spent`。
- 80% 触发软告警、100% 硬停派发，行为可测。
- 预算未配置时默认不拦截（向后兼容）。
- 单测覆盖聚合 / 阈值 / 硬停，全绿。
- **前置验收**：`G-008` 的「候选间比较」成立后才具备**拦截效果**；**机制接线不再被阻塞**（单 agent 成本画像已成立，可先接线并以单 agent 验证阈值行为）。

---

## 2. 阶段二：CLI 推通道（安全前提必须先定）

| 序 | 借什么 | 备注 | 成本 |
|---|---|---|---|
| 1 | **CLI 适配器执行链**：`ProcessBuilder` spawn → 逐行读 stdout → 回调流式落库 | `core/agent/**` 首次引入进程执行；**触发 `G-005` 条件②** | 高 |
| 2 | **headless 契约**：命令模板 + 参数白名单 + stdin 传 prompt + timeout / grace kill | prompt 必须走 stdin（会超命令行长度） | 中 |
| 3 | **输出解析器**（纯函数，可单测）：`system:init` / `assistant` / `result` 三类事件 | **usage 必须按 `message.id` 取 max 去重**，否则成本虚高数倍 | 中 |
| 4 | **session 续接 + 自愈**：四重 AND 判据（uuid / promptBundle / cwd / target）+ 失效原因日志 + 坏句柄 `clearSession` | 外部句柄「只写不校」= 静默永久故障 | 中 |
| 5 | **run 记录归一**：CLI run 与现有外部 Agent run / 内部 LLM run 写同一套状态机 + 日志 + 成本 | 不做这个，永远没有统一的成本 / 审计 / 复盘视图 | 中高（先定统一 run 表） |

**阶段二·1 开工前置（缺一不可）**：

1. 独立容器执行，**不与 app 容器共享 env / 网络**；
2. 只挂载目标 worktree，不带 `HELLOAI_CREDENTIAL_AES_KEY_BASE64`；
3. 参数白名单化，不接受任意命令；
4. 超时 + grace kill + 孤儿进程回收。

> 若这四条定不下来，阶段二·1 无限期挂起；阶段二·2 ~ 2·4 可先行开发（纯逻辑，不外跑）。

---

## 3. 阶段三：有条件再做（依赖前置能力）

| 序 | 借什么 | 前置 |
|---|---|---|
| 1 | **run-scoped 短票**替代长期 key（每次 run 一个限期 token，含 agentId + runId + 工具集，结束作废） | 需先有「run」实体（阶段二·5） |
| 2 | **技能分层**：`markdown_only`（仅提示词注入，零风险）/ `assets` / `scripts_executables`（需沙箱）；trustLevel **由文件构成推导，不可自报** | 接 `REF-8.3`，先只接 `markdown_only` |
| 3 | **可调用性纯函数**：10 种 block reason 枚举（自身 5 种 + 组织链 5 种：上级缺失 / 跨公司 / 上级终止 / 上报环 / 层级过深） | 需先有 team 组织链 |
| 4 | **结构化交互卡片**：`suggest_tasks` / `ask_user_questions` / `request_confirmation` + `wake_assignee` 续跑 | 需前端配合 |
| 5 | **员工四件套** `SOUL.md` / `AGENTS.md` / `HEARTBEAT.md` / `TOOLS.md` 替代单体 prompt_template | 需 prompt 模板体系改造 |
| 6 | **目标实体 + goalId 全程携带**（任何 subtask 可回溯「为什么存在」） | 需新增 goal 表 |
| 7 | **持久执行工作区**（直到人手动关闭，不自动回收） | 需确认现有 workspace 语义 |
| 8 | **liveness 判定**：`plan_only` / `empty_response` 不算实质进展（进展必须是工具事件 / 评论 / commit / 测试） | 依赖阶段一·3 |

---

## 4. 明确不借（避免后续反复讨论）

| 不借 | 理由 |
|---|---|
| **spawn 模式套 GUI Agent** | Trae / Qoder 在别人桌面里，平台既 spawn 不了也 ssh 不进去。权限边界问题，非技术选择 |
| **Node 进程内 `require` 第三方 adapter 包** | 等价于「往 classpath 塞任意 jar」，正面撞 `G-005` 条件②。要做只做**声明式清单 + 内置执行器** |
| **「AI 公司」产品隐喻**（CEO / 招聘 / 战略审批） | 机制可借（组织链检查、goalId、审批门），产品包装不借 |
| **`no-remote-git` 契约的具体实现** | 它假设「平台与代码同机」。借思路（跨 run 状态必须有单一边界），不借实现 |
| **ACP 引擎**（`@agentclientprotocol/*`） | 需 CLI 产品实现该协议，只有少数几家有。属体验层（能看 Agent 打字），非能力层 |
| **多组织隔离 / evals / 移动端** | 单租户阶段不需要，成熟期再谈 |

---

## 5. 执行次序

```text
阶段一（拉模型做深，无风险）      1 2 3 4 5 6          ← 推荐执行序：安全(1) → 成功率(2) → 协议固化(3) → 重构(4/5) → 成本闸门(6，等 G-008)
      ↓
阶段二（CLI 通道，先定安全前提）   2 3 4  →  1（安全前提齐备后）→  5
      ↓
阶段三（依赖前两批）             1 ~ 8 按需
```

**判断依据**：阶段一全是「现有机制补硬」，投入产出比最高且不引入新风险面；阶段二补齐形态空缺，但受 `G-005` 约束；阶段三每一条都依赖前面的产物，提前做会返工。
**阶段一执行序说明（2026-10-21 定）**：
1. **1（env 黑名单）先行**——`ExecutionEnvironment` 即将获得 env 注入能力（阶段二 CLI 通道必然引入），黑名单契约必须先于能力落地，否则越权路径开口。成本低、无前置。
2. **2（唤醒原因）次之**——纯协议增强，直接提成功率，成本低。
3. **3（409 语义固化）**——原子占位已存在（`claimAtomic`），补 reason 拆分 + 并发测试即闭环，成本低。
4. **4（三配额分离）与 5（MCP 薄封装复核）**——中成本重构，可在 1~3 落地后并行。
5. **6（成本闸门）最后**——前置 = `G-008` 候选间比较；框架设计可先行，**机制接线已不被阻塞**（单 agent 可验证阈值行为），拦截效果待第 2 个 LLM agent 的成本数据。

---

## 6. 三个「一句话就能省很多钱」的细节（来自 Paperclip 实测踩坑）

1. **系统提示词只在首次会话注入**，resume 时再注入每心跳白烧 5-10K token。
2. **流式输出的 token 用量必须按消息 id 去重取 max**，直接累加会虚高数倍，而你会以为是模型涨价。
3. **外部句柄（sessionId / workerId / jobId）必须有「坏了怎么自愈」的路径**，否则单条坏数据 = 任务永久卡死。

---

## 7. 前置待决

1. **阶段二·1 的 `G-005` 条件②安全方案**（独立容器 / 网络策略 / 凭据隔离）由谁出——它决定阶段二能否启动。
2. **阶段一·6（成本闸门）的预算来源口径**：`token_budget` 由「任务创建时设定」还是「按 provider 历史均值推导」——实施设计默认「未配置不拦截 = 向后兼容」，落地前须定口径。
3. **阶段一·6 落库走 Flyway**：`sub_task` 新增 `token_budget` / `token_spent` 两列属 schema 变更，**必须按迁移铁律走新版本迁移**（先登记版本号、`ON CONFLICT` 带 `WHERE deleted = 0`、JSON 列 `::jsonb` 等），严禁改已 apply 迁移。
4. 若后续确需为本专项建号，须先登记进《差距表》§0「编号口径」表，**再**在文档内使用；当前一律用「阶段 + 序号」。
