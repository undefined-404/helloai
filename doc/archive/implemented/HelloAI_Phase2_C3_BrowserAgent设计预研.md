> **状态：HISTORICAL / REFERENCE**。当前开发请以根目录 Current/Target/Gap/Plan 为准。

# HelloAI Phase 2 C3：Browser Agent 设计预研（N-003）

> 主轴：为差距表 N-003（Browser Agent，TODO P2）产出**可实施级设计草案**（P2-C 设计预研，与 C1 Workflow / C2 Team 同批）。核心结论：**Browser Agent = 一种新的 Agent 接入类型（WEB_BROWSER），复用现有 task/sub_task/调度/执行/产物链路接入，不新增第二套任务调度系统**——调度/选人/心跳豁免/执行记录/产物物化五环已铺好，核心缺口是「Browser 执行桥接（Session + Tool）与 AgentExecutor 实现」。
>
> 文档性质：设计预研（草案），表结构与接口为实施前方向；§7 决策点待用户审阅拍板。
>
> **状态**：已实施（2026-09-06，S1~S4 代码完成 + S5 文档收口；browser_session 登记、BrowserAgentExecutor 路由接入、HTTP 推送桥接契约、验证全部落地，见 §5 路线 + R2 实施记录）。

---

## §0 设计输入与约束

### 0.1 差距表 N-003 要求

```text
现状：平台已具备外部 Agent / MCP 执行基础设施。
缺少完整：Browser Agent ↓ Browser Session ↓ Tool ↓ Artifact ↓ Result 执行链路。
目标：Browser Agent 应作为一种 Agent 类型接入已有执行体系；不得新增第二套任务调度系统。
优先级 P2 / 状态 TODO
```

### 0.2 S0 事实基线（2026-09-06 代码盘点）

1. **接入类型已预留，执行实现为零**：`agent.access_type` CHECK 已含 `WEB_BROWSER`（V1 L113）；`AgentAccessType.WEB_BROWSER` 语义 = `requiresRuntimeLiveness()=false`（不需心跳）、`usesConsumerTokenAuth()=false`、`usesCredentialVault()=false`、**`supportsArtifactUpload=true`**、`maxConcurrentTasks=1`、`isSlow=true`；无预置种子 Agent。
2. **执行环境路由已识别**：`RemoteAgentEnvironment`（runtime 包）`supports()` 已含 `CLI_CLIENT || WEB_BROWSER`，注释明确 WEB_BROWSER = 「Playwright 桥接、执行主体为外部 AI 服务」；`LocalProcessEnvironment` 仅 `API_KEY_LLM`。
3. **执行器缺失**：`AgentExecutorRouter` 按 `supports` 路由，但**无任何 CLI_CLIENT/WEB_BROWSER 的 AgentExecutor 实现**——现有 `ApiKeyAgentExecutor` 仅 API_KEY_LLM；对 WEB_BROWSER 调 `executeSync` 会抛「未找到匹配的 AgentExecutor」。
4. **调度侧已就绪**：`AgentSelector` L188 不要求心跳（恒 fresh）、L301 并发权重 WEB_BROWSER→1；`ResilientDispatcher` L148 心跳豁免；`ExternalAgentFailureTracker`（N11 SQL 仅 CLI_CLIENT，WEB_BROWSER 自动跳过）；`SubTaskDispatchService` 对非 API_KEY_LLM `hasLocalExecutionCapability` 恒 true。
5. **执行命令推送链完整**：`ExecutionCommandServiceImpl.createAssignedCommand` 三表同事务（ExecutionCommand + agent_execution_record + agent_command_outbox），dispatch-mode=EVENT 本地消费 → `AgentRuntime.execute` / MQ / NONE（DB Poller）；`AgentExecutionRecord` 已含 `accessType` 冗余字段（对 WEB_BROWSER 天然兼容）。**外部 CLI_CLIENT 走 MCP pullTasks 拉取；WEB_BROWSER 应走平台内推送（defaultCapabilities 已设 supportsPull=false）**。
6. **产物/Artifact 链路完整且与 accessType 解耦**：`ExecutionOutputParser`（manifest JSON 多文件协议 → `ArtifactFile`/`Manifest`，降级单 md）+ `ExecutionResultHandler.handleReport` → `executionArtifactService.materialize()`（afterCommit 物化附件）→ `subTaskService.submit()` → REVIEW。**浏览器产物（截图/HTML/抓取文本）可直接以 manifest 协议进入物化链，无需改造**。
7. **Browser Session/Tool 完全缺失**：后端无 selenium/playwright/puppeteer 实现与依赖；无浏览器 Session/Tool 概念；MCP 工具面（`McpMcpServer` 11 工具 + `McpController`）全部为任务/收件箱/打卡域，无网页能力。
8. **技能铺底**：`AgentSkillDeriver` WEB_BROWSER → 默认推导 `web-search` 技能（白名单含 web-search）。

### 0.3 外部借鉴（架构参考第三阶段 Browser 接入）

- **C1 决策 4 前瞻**：模板约束将来可带 `access_type` 提示字段（N-003 Browser 预留），实例化落入任务级约束；模板预留字段不预读。
- **现有 API_KEY_LLM 平台内执行范式**（推送 + 平台内执行器 + 产物回写）是 WEB_BROWSER 的接入模板；**现有 CLI_CLIENT MCP 拉取范式**是外部服务侧工具面的参考（`McpMcpServer` 工具注册模式）。
- **不照搬**：不引入第二套任务调度；不把浏览器执行堆进 Controller/MCP 面。

---

## §1 任务定位

**preflight 四问**：改什么——补 WEB_BROWSER 的 Browser 执行桥接（Session 登记 + Tool 契约 + `BrowserAgentExecutor`），复用现有执行/产物/调度链；为什么——差距表 N-003 P2 TODO，WEB_BROWSER 接入类型已预留但执行实现为零；类型——补功能（新接入类型，小闭环）；不做什么——**不新增第二套任务调度系统**、不重写 sub_task 状态机/执行链、不把浏览器能力做成平台内 LLM 编排（形态 A 边界）、不动执行命令/Outbox 链路语义。

---

## §2 范围边界

**做**：

1. `BrowserAgentExecutor`（AgentExecutor 实现，接入 `AgentExecutorRouter`）——按 WEB_BROWSER 推送执行命令，桥接外部 Browser Agent
2. `browser_session` 表（浏览器会话登记：agent/task/状态/最后 url/最后截图引用，供展示与恢复）+ 生命周期（BEGIN/ACTIVE/CLOSED）
3. Browser Tool 契约（外部 Browser Agent 自持浏览器，平台侧登记工具清单；产物走 manifest 协议进入现有物化链）
4. 定向单测 + 全量回归 + 差距表 N-003 处置 + LOG

**不做**（明文边界）：

- 不新增第二套任务调度（复用 ExecutionCommand/Outbox/本地消费推送链）
- 形态 A 下不做**平台内 Playwright + LLM 编排**（浏览器与 AI 都在外部服务侧闭环，平台只推送/收产物）
- 不改 sub_task 状态机/执行链/收敛链语义
- 不做浏览器集群/多实例调度（首版 1 Agent = 1 会话）

---

## §3 设计（决策草案，待拍板）

### 3.1 Browser 执行形态（决策 1）

两种形态二选一：

- **形态 A（外部 AI 服务 + Playwright 桥接，倾向）**：WEB_BROWSER Agent = 外部 AI 服务（自带 Playwright 浏览器）。平台按现有推送链下发 sub_task 执行命令 → 外部服务执行网页任务（内部闭环：AI 决策 + 浏览器操作）→ 回传结果 + 产物（截图/HTML/文本）。平台侧只补 `BrowserAgentExecutor`（推送 + 等待 + 收产物），复用 `ExecutionOutputParser → materialize` 链。**缺口最小，对齐 RemoteAgentEnvironment 注释方向**。
- **形态 B（平台内 Playwright + LLM）**：平台内跑 Playwright，复用 `ApiKeyAgentExecutor` 的 ChatClient 调 LLM 驱动浏览器 ToolRegistry 工具（navigate/click/type/screenshot/extract/scroll）。需要 BrowserSession 实例管理 + LLM 工具编排，复杂度高（近一套新的执行器内核），首版不建议。

### 3.2 Browser Session 模型（决策 2）

```sql
browser_session
  id BIGSERIAL PK
  agent_id BIGINT NOT NULL              -- WEB_BROWSER Agent
  task_id BIGINT                        -- 关联任务（可空：跨任务会话）
  status VARCHAR(16) NOT NULL           -- BEGIN / ACTIVE / CLOSED / FAILED
  current_url VARCHAR(512)
  last_screenshot_ref VARCHAR(255)      -- 最近截图 artifact 引用（展示/审计）
  begin_time TIMESTAMPTZ
  close_time TIMESTAMPTZ
  -- 审计列继承 BaseEntity
```

- **首版为"登记 + 展示"语义**：会话状态由执行结果回写更新（BEGIN→ACTIVE→CLOSED），**不做运行期反锁 task/sub_task**（对齐 C1 反锁禁令）。
- **形态 A 下浏览器实例生命周期在外部服务侧**：平台 `browser_session` 只做会话登记/审计，不持有浏览器对象。

### 3.3 Browser Tool 契约（决策 3）

- **形态 A**：浏览器工具（navigate/click/type/screenshot/extract/scroll 等）为**外部 Browser Agent 内部工具**，平台不暴露也不编排；平台只登记 Agent 声明（`capabilities` 扩展 `browserTools` 清单）+ 定义**产物协议**（manifest JSON：`files[{fileName,mimeType,content}]`，截图/HTML 直接物化）。
- **形态 B**：工具进平台 `ToolRegistry`（`agent/tool` 包），LLM 通过 ChatClient function-calling 调用。首版（形态 A）不建平台浏览器工具面。

### 3.4 执行接入（决策 4）

```text
现有推送链：ExecutionCommandServiceImpl → (EVENT) LocalExecutionCommandConsumer → AgentRuntime.execute
Browser 接入：AgentRuntime 按 accessType 路由 → AgentExecutorRouter → BrowserAgentExecutor（新增）
BrowserAgentExecutor：解析命令 → 推送外部 Browser Agent（HTTP/Webhook 或 MQ 扩展）→ 等待结果
                     → 解析 manifest 产物 → ExecutionResultHandler.handleReport 回写（复用现有链）
```

- **浏览器产物协议**：外部服务回传 manifest JSON（截图 base64/HTML/文本），平台 `ExecutionOutputParser` 物化 → 附件 → `subTaskService.submit()` → REVIEW（全复用，零改造）。
- **超时/失败**：走现有 agent_execution_record 状态机（PENDING→RUNNING→SUCCESS/FAILED/TIMEOUT）+ 现有重派/死信链。

### 3.5 与 C1 Workflow 的预留

C1 决策 4：模板约束将来可带 `access_type` 提示字段，实例化落入任务级约束；本轮不预读、不感知 N-003（Workflow 与 Browser 解耦，仅预留）。

---

## §4 验证方案

| # | 项 | 落点 |
|---|---|---|
| 1 | BrowserAgentExecutor 路由接入 | AgentExecutorRouter 对 WEB_BROWSER 命中 BrowserAgentExecutor（单测） |
| 2 | 执行命令推送 + 结果回写 | 模拟外部回传 manifest → ExecutionOutputParser 物化 → submit 断言 |
| 3 | browser_session 生命周期 | 登记/更新/关闭 + 无反向写 task/sub_task 断言 |
| 4 | 产物协议 | 截图/HTML/文本 manifest 解析 → ArtifactFile 物化断言 |
| 5 | 全量回归 | core + api 既有回归全绿（调度/执行/产物/收敛链不受影响） |

---

## §5 实施路线建议（骨架先行）

```text
N-003-S1 browser_session 表 + 实体/Mapper + Session 服务（登记/更新/关闭）
N-003-S2 BrowserAgentExecutor + 路由接入 + 产物协议解析（manifest → 物化复用）
N-003-S3 执行推送桥接（外部 Browser Agent 对接契约）
N-003-S4 验证：§4 五项单测 + 全量回归
N-003-S5 文档回填（差距表 N-003 处置 + LOG）
```

---

## §6 风险与边界

| 风险 | 缓解 |
|---|---|
| 退化成第二套调度 | D：复用 ExecutionCommand/Outbox/本地消费推送链，不新增调度对象 |
| 平台内浏览器编排膨胀 | 形态 A 把浏览器/AI 放外部服务闭环，平台只推送/收产物 |
| 产物协议歧义 | 复用现有 manifest 多文件协议（ExecutionOutputParser 已支持），不另立协议 |
| 与 Workflow/Team 重叠 | C1 预留 access_type 提示字段不预读；Browser 是接入类型而非流程/组合抽象 |
| 外部服务不可达/超时 | 复用 agent_execution_record 状态机 + 现有超时/重派/死信链 |

---

## §7 决策拍板记录（待用户审阅）

| # | 决策点 | 草案倾向 | 待拍板 |
|---|---|---|---|
| 1 | Browser 执行形态 | **形态 A**：外部 AI 服务 + Playwright 桥接（平台推送 + 收产物；对齐 RemoteAgentEnvironment 注释） | ☐ |
| 2 | Browser Session | `browser_session` 登记表（BEGIN/ACTIVE/CLOSED，展示/审计；不反锁 task/sub_task） | ☐ |
| 3 | Tool 面 | 浏览器工具为外部服务内部能力，平台只登记声明 + 定义 manifest 产物协议（首版不建平台浏览器工具面） | ☐ |
| 4 | 执行接入 | `BrowserAgentExecutor` + 复用 ExecutionCommand 推送链 + ExecutionOutputParser/materialize/submit 回写链 | ☐ |

---

## 修订记录

### R1（2026-09-06）：设计预研草案产出

- **背景**：P2-C 设计预研（与 C1/C2 同批），N-003 Browser Agent。
- **核心**：D=新接入类型（WEB_BROWSER）复用现有调度/执行/产物链 / 形态 A 外部桥接（不建第二调度、不做平台内 LLM 编排）/ browser_session 登记 / manifest 产物协议复用。
- **边界**：S1~S5 骨架先行；browser_session 不反锁任务；Workflow 预留 access_type 不预读。

### R2（2026-09-06）：实施完成（S1~S4）

- **S1**：V71 `browser_session` 表（状态 BEGIN/ACTIVE/CLOSED/FAILED，登记/展示语义，不反锁 task/sub_task）+ `BrowserSessionStatus` 枚举 + BrowserSession 实体/Mapper + `BrowserSessionService`（begin/markActive/close/markFailed + 分页，CLOSED 幂等）。
- **S2**：`BrowserAgentExecutor`（AgentExecutor 实现，`supports`=WEB_BROWSER，接入 `AgentExecutorRouter`；push 成功回传 output 文本可为 manifest 产物协议，失败抛异常同 ApiKeyAgentExecutor 契约）。
- **S3**：`BrowserAgentGateway` 接口 + `HttpBrowserAgentGateway`（推送目标 `agent.modelConfig.webhookUrl`，HTTP 同步请求-响应，超时 120s，4xx/5xx fail）。
- **S4**：api 不新增（browser_session 为登记表，展示留待前端）；**全量回归：core Tests run: 1225（基线 1211 + 14）/ api 49 全绿**；新增测试 BrowserSessionServiceImpl 6 / BrowserAgentExecutor 4 / HttpBrowserAgentGateway 3（含 JDK HttpServer 本地契约验证）；既有调度/执行/产物/收敛链回归全绿无回退。
- **边界兑现**：零改调度内核/执行命令/Outbox 链路；浏览器与 AI 在外部服务侧闭环（形态 A）；平台只推送 + 收产物（manifest → ExecutionOutputParser/materialize 复用）。
