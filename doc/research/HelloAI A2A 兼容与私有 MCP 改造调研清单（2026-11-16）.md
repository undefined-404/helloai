# HelloAI A2A 兼容与私有 MCP 改造调研清单（2026-11-16）

> **状态：Analysis**
>
> 最后更新：2026-11-17（AQ-7 / AQ-4 已拍板，结论回填 ADR-009 §8.6 / §8.7；AQ-4 增补 contextId 第三义。首稿 2026-11-16：结合本仓代码实测，把「要不要兼容 A2A」拆成 13 个可独立开工的调研问题）
>
> **本文是什么**：一份**调研清单**，不是调研报告、不是方案、不是排期。已核实的写进 §2（不要重查），未核实的写成 `AQ-x` 问题（每条给「为什么重要 / 查哪里 / 查完什么算完」）。结论性的方向判断集中在 §1 与 §5，供用户裁定。
>
> **调研问题**：本仓对外执行通道是一套 13 个工具的自建 MCP 语义，与 A2A（Agent2Agent，2026-03-12 发布 v1.0.0 生产可用，现稳定版 v1.0.1）在概念上高度相邻但在协议上不对齐。用户要求判断：**是否要立项改造、改到什么程度、先查清什么**。
>
> **勘察口径**（治理规则 §3.6 二值标记）：`[实测]` = 本仓源码实测，附 `path:line`；`[推断]` = 外部公开资料，**未在本仓验证**，接入前须单独实测。不虚构本仓不存在的能力。
>
> **编号口径**：本文 `AQ-x` 为**本文件局部锚点**（`AQ-` 全库实测零命中），只用于节内互指，**不是项目号段**、不登记差距表 §0，**不得**在 `plan/` / `design/` / 代码注释中当任务编号引用。立项时另建号段（建议 `G-036` 锚定，见 §6）。
>
> **进度口径**：本文**不跟踪进度**。文中状态值一律引用自《HelloAI 实现差距表》，以该文件为唯一事实源。
>
> **生命周期位置**：治理规则 §4 ①调研。`design/`（ADR）应在 `AQ-1` / `AQ-3` / `AQ-5` / `AQ-6` 有结论后再出——这四条决定「改造到什么程度」这件事本身。
>
> **2026-11-16 订正（用户裁定后）**：上一句已被推翻，勿再据它推迟。`ADR-009` 当日即 `Accepted`——边界可以拆成「现在就能定死的」与「待 AQ 回填的」两半：前者是内核不改 / 双平面 / 投影三原则 / **A2A task 身份 ≠ subTask 身份** / 出站优先，后者进 ADR §8 增补区。`AQ-5` 因此从「定形态」级阻塞降为「逐条文案」。
>
> **本文与 ADR-009 的分工**：结论以 `doc/design/adr/ADR-009-a2a-compatibility-boundary.md` 为唯一事实源；本文只保留「还没查清什么」与已实测的代码事实。

---

## 0. 一句话结论

**A2A 兼容的收益不在「不被孤立」，在「把执行器接入门槛从『实现 13 个工具』降到『实现一个 A2A server』」**——你的外部 executor 至今只有 2 个，这个门槛本身就是运营负担。但**不能按 A2A 改造内核**：`REWORK` / `AWAITING_DERIVED` / `RESTRUCTURING` 三个状态在 A2A 里没有位置，而 A2A 明文规定「terminal task never restarts」，返工与派生恰是你的核心能力。所以正确形态是**双平面**：A2A 只做外贸口岸，内部 12 态状态机原样保留。

---

## 1. 已被本清单排除的三个方向（不再讨论）

| # | 曾出现的提法 | 排除理由 | 依据 |
|---|---|---|---|
| 1 | 「按 A2A 标准改造状态机」 | 状态机从 12 态压到 8 态，`REWORK`/`AWAITING_DERIVED`/`RESTRUCTURING` 无落点；A2A 终态不可重开 | §2.2 `[实测]` + A2A v1.0 规范 |
| 2 | 「第一步就建完整 A2A server 作为主通道」 | 入站价值小于出站（你的强项是编排管控，不是单 agent 能力）；且入站依赖 `AQ-6` 的通道缺口 | §5 顺序 |
| 3 | 「`davidtaikocha/taiko-bridge-agent` 可参考」 | 全网检索无任何结果，未能证实存在，不可作为依据 | 2026-11-16 检索 |

**仍然成立、且被本清单采纳的**：① 双平面（口岸 / 内核）定位；② `completed` 只等于「提交待审」不等于 `DONE`，这是真实差异点也是卖点；③ 出站（调度外部 A2A agent）优先于入站。

---

## 2. 已核实事实（`[实测]`，勿重复调研）

### 2.1 私有工具面：13 个，无 cancel

`[实测]` `helloai-core/src/main/java/com/helloai/core/agent/mcp/McpMcpServer.java`（`@Tool` 自 :70 起）+ `helloai-api/src/main/java/com/helloai/api/controller/McpController.java:52`：

```
pullTasks / ack / claimSubTask / startSubTask / heartbeat / uploadArtifact /
submitResult / reportBlocked / getAgentStatus / getDepsSummary /
getSubTaskDetail / checkIn / checkOut
```

- **无任何 cancel 工具**（全文件 grep `cancel` 零命中）。这是独立于 A2A 的缺陷：executor 卡死后平台只能改库或等租约过期。
- 无 `contextId` 对应物；无「任务列表查询」（`pullTasks` 是拉收件箱，不是查任务）。
- 每个工具都带 `sessionId` + `_sessionId` 双参数兼容位（:88-89 等 12 处）。

### 2.2 状态机：12 态 vs A2A 8 态 vs MCP Tasks 5 态

`[实测]` `helloai-common/src/main/java/com/helloai/common/constant/SubTaskStatus.java:3-34`

| 本项目 12 态 | A2A 8 态（v1.0） | 可映射 |
|---|---|---|
| `PENDING` / `ASSIGNED` | `TASK_STATE_SUBMITTED` | 是 |
| `IN_PROGRESS` | `TASK_STATE_WORKING` | 是 |
| `BLOCKED` | `TASK_STATE_INPUT_REQUIRED` | **否**（语义朝向相反，见下） |
| `REVIEW` | `TASK_STATE_WORKING` | 弱（A2A 无审核态） |
| `DONE` | `TASK_STATE_COMPLETED` | 是 |
| `CANCELLED` | `TASK_STATE_CANCELED` | 是 |
| `DEAD_LETTER` | `TASK_STATE_FAILED` / `REJECTED` | 弱（DEAD_LETTER 非终态） |
| `REWORK` / `PAUSED` / `AWAITING_DERIVED` / `RESTRUCTURING` | **无对应** | **否** |

> **`BLOCKED` ≠ `INPUT_REQUIRED`**：A2A 的 `input-required` 是「**调用方**补信息即可继续」，控制权在 client；本仓 `BLOCKED` 是「**执行器**搞不定，通知 PLANNER 排障」，后手是 `RESTRUCTURING` / 重派 / 死信。照 A2A 语义实现会让外部 client 误以为「补个参数就好」，实际任务被重拆。
>
> 另一条外部事实（`[推断]`，2026-11-16 检索）：MCP 的 Tasks 原语已于 2026-07-28 从核心协议移出、成为官方扩展 `io.modelcontextprotocol/tasks`，状态集为 `working / input_required / completed / failed / cancelled` 五态。**三套状态集互不相同**，且都小于本仓 12 态。

### 2.3 执行者有两套范式并存（决定改造落点）

`[实测]` `helloai-core/src/main/java/com/helloai/core/agent/dispatcher/SubTaskAutoExecutionDispatcher.java:31-35` 类注释原文：

> 「当前只对 `AgentAccessType.API_KEY_LLM` 创建 execution command；CLI_CLIENT 仍走收件箱/MCP 拉取链路。」

即：`CLI_CLIENT` = **pull**（执行器主动拉），`API_KEY_LLM` = **push**（平台建命令主动驱动）。**A2A 必须走 push**。

`[实测]` `helloai-common/src/main/java/com/helloai/common/constant/AgentAccessType.java`：现有 3 类 `CLI_CLIENT` / `API_KEY_LLM` / `WEB_BROWSER`，带 4 个自述方法 `usesConsumerTokenAuth()` / `usesCredentialVault()` / `requiresRuntimeLiveness()` / `defaultCapabilities()`，全部是 `this == X` 或 `switch` 形式。

> **推论**：加 A2A 执行者不是「写个 adapter」，而是「加一个 `AgentAccessType` + 扩 push 分支 + 一个 command 消费者 + 一个远端 taskId 对账循环」。

### 2.4 端口层确实存在，但 `TaskDispatchPort` 不是执行通道

`[实测]` `helloai-core/src/main/java/com/helloai/core/agent/port/TaskDispatchPort.java:22` —— 语义是**选人 + 分配到 agentId**（`assignNext(Long agentId, Long subTaskId, ...)`），不负责「怎么把任务送到执行者手上」。`helloai-core/.../task/port/ReviewPort.java:16` 同理是审核端口。

> 所以「实现 `TaskDispatchPort` 就完成了出站」是错误落点；真正的落点在 §2.3 的 execution command 侧。

### 2.5 `submitResult` 的产物是自由文本

`[实测]` `McpMcpServer.java:309-342`：`output` 是 `String`（自由文本），`error` 是 `String`，`resultId` 是幂等键（可选）。`uploadArtifact`（:268-300）要求 `storageUrl` 是**已在平台桶中**的 `minio://` 地址，不是外部 URL 或 inline bytes。

> **行号订正（2026-11-17）**：本节初稿写 `submitResult` `:258-292` / `uploadArtifact` `:218-252`，那是 `A-1`（`cancelSubTask`）落地前的坐标；`cancelSubTask` 插入 `:210-241` 后整体下移约 50 行。跨批搬运行号必失效，引用时以本次订正值为准。

> 对照 A2A：产物是 `Artifact{artifactId, name, parts[]}`，`parts` 是带类型数组（text / file / data）。

### 2.6 `contextId` 已经撞名

`[实测]` `WorkspaceRef`（`SL-8` 引入，见差距表 `G-032`）已有字段 `contextId`，语义是**沙箱上下文**。A2A 的 `contextId` 语义是**会话分组**（把多个 task 归为一个对话）。同名两义，违反「同一事实双写必分叉」。

---

## 3. 调研问题清单（`AQ-1`~`AQ-13`）

### 3.1 第一梯队：挡路的，不定就没法动工

#### `AQ-1` 加 `AgentAccessType.A2A_AGENT` 的完整影响面

- **为什么重要**：`AgentAccessType` 的 4 个自述方法 + 所有 `switch (accessType)` 消费点，漏一处就是**静默错判**（例如 `requiresRuntimeLiveness()` 若返回 true，A2A agent 会因无心跳被判不可分配，任务永远停在 `PENDING` 且无原因）。
- **查哪里**：全仓 `getAccessType()` / `switch (` 配 `AgentAccessType` 的消费点；重点 `ResilientDispatcher`（选人判活）、`SubTaskAutoExecutionDispatcher`（自动派发）、`ExecutionCommandService`、`AgentServiceImpl`（注册时的能力默认值）。Java 的 `switch` 表达式带 `default` 会吞掉新枚举值，编译器不会报错。
- **查完什么算完**：给出「消费点清单 + 每处的期望返回值」表；判定 `A2A_AGENT` 在 4 个自述方法上分别应为什么；确认 `defaultCapabilities()` 该补哪些键（参考现有 `supportsPull` / `supportsMCP` 命名）。

#### `AQ-3` 长任务与现有超时/回收机制的冲突（**最可能埋雷的一条**）

- **为什么重要**：A2A 的官方定位是「跨小时甚至跨天的任务」。而本仓有 `AssignedSubTaskTimeoutTask`（10 分钟回收 `ASSIGNED`）、租约 TTL、`SandboxIdleReaperTask`。A2A 任务跑到第 20 分钟被平台判死，比不做还糟。
- **查哪里**：所有定时回收类（`**/task/*TimeoutTask`、`SandboxIdleReaperTask`、租约续期逻辑）；`agent_execution_record` 的超时字段。
- **查完什么算完**：给出「A2A 通道专属超时口径」（例如以 `GetTask` 的 `lastUpdatedAt` 替代本地无进展判定），并列出必须放行的定时任务与其豁免条件。

#### `AQ-6` 入站任务创建的通道缺口

- **为什么重要**：`G-033` 已登记「`CreateTaskRequest` 写 `context` 现唯一可行路径是直改库」。入站 A2A 任务**必须**写 `context`（存 `contextId` / 远端 `taskId` / `messageId`），否则入站只能靠直改库。
- **查哪里**：`CreateTaskRequest` 字段与写库路径；`requirement_conversation` 创建链路；`TaskServiceImpl.create` 的入口收敛情况。
- **查完什么算完**：确认是否存在一条服务层入口能带 `context` 建任务；若无，给出最小补口（不建议等到做入站时才临时补）。

#### `AQ-8` 无心跳的执行者如何通过选人判活

- **为什么重要**：现行铁律是「心跳在线才可分派」，但那是针对 `CLI_CLIENT` 的。`requiresRuntimeLiveness()` 现在只对 `CLI_CLIENT` 返回 true。A2A agent 无心跳，必须走 `API_KEY_LLM` 同款「平台侧触发、不看在线态」。
- **查哪里**：`ResilientDispatcher` 的 fast-fail 判定（`SLEEPING` / `OFFLINE` / 心跳陈旧 / 执行密集不匹配）；`AgentAccessType.requiresRuntimeLiveness()` 的全部调用点。
- **查完什么算完**：证明「A2A agent 在 `online_status=OFFLINE` 时仍能被选中并派发」，或指出还需改哪一处。

### 3.2 第二梯队：决定对外形态，不定就没法设计映射表

#### `AQ-5` 三个不可映射状态对外怎么呈现

- **为什么重要**：`REWORK` / `AWAITING_DERIVED` / `RESTRUCTURING` 在 A2A 里没有位置，而 A2A 规定终态不可重开。这是「你的状态机比标准丰富」的正面证据，但**对外必须有投影口径**，否则外部 client 看到的是一条永远 `working` 或莫名回到 `submitted` 的任务。
- **查哪里**：这三个状态的触发点、业务含义、平均持续时长；`AWAITING_DERIVED` 与派生任务完成态传播的关系（`ADR-008`）。
- **查完什么算完**：产出「本仓 12 态 → 对外投影」表，逐条给：投影成什么 A2A 态、在 `status.message` 里说什么、是否触发 push 事件。

#### `AQ-7` `submitResult.output` 结构化的迁移面

- **为什么重要**：产物从 `String` 变成 `parts[]` 是**贯穿全链**的改动，影响审核、报告整合、前端渲染、附件物化。
- **查哪里**：`output` 字段的全部读方（`ReviewEvidenceAssembler`、`TaskFinalReport*`、前端 `SubTaskDetail.vue`、`submitArtifact` 落库链）。
- **查完什么算完**：给出消费方清单 + 每个的兼容策略（`parts` 与 `output` 并存时以谁为准、旧数据怎么回填）。

> **2026-11-17 已拍板**：方案 A（context JSONB `lastExecution.parts` + output 纯文本投影 + file part 第一批 400 拒收 + 单写点 + 64k 投影截断）。结论唯一回填于 `ADR-009` §8.6，本清单不复述结论。

#### `AQ-4` `contextId` 撞名的处置方案

- **为什么重要**：`WorkspaceRef.contextId`（沙箱上下文）与 A2A `contextId`（会话分组）同名两义。若不处置，将来读代码的人会以为是同一个东西。**2026-11-17 增补第三义**：助理域 `ArtifactItemVO.contextId` / `MyArtifactsQueryService` 的 `contextId` = 「会话 id 或任务 id，前端跳转用」（`[实测]` `helloai-api/.../dto/requirement/ArtifactItemVO.java:36`、`helloai-core/.../planner/assistant/service/MyArtifactsQueryService.java:50`），首稿漏登。
- **查哪里**：`WorkspaceRef.contextId` 的全部消费点；`SandboxRuntimeAdapter` 的 `toRef` 装配；助理域 `ArtifactItemVO.contextId` 的装配与前端跳转消费点（第三义）。
- **查完什么算完**：二选一定案——① A2A 侧对外仍叫 `contextId`、内部另起名；② 或给 `WorkspaceRef` 的字段改名。任一方案都要给出全仓 grep 清理清单。

> **2026-11-17 已拍板**：方案①（对外保名 `contextId`、内部另起名 `conversationGroupId` = taskId 最小投影；`WorkspaceRef` 不改名）。结论唯一回填于 `ADR-009` §8.7，本清单不复述结论。

### 3.3 第三梯队：技术可行性，实测才知道

#### `AQ-9` `a2a-java` 1.5.0 在本项目技术栈上能否跑通

- **为什么重要**：`[推断]` 官方 SDK 是 `org.a2aproject.sdk:a2a-java-sdk-client:1.5.0.Final`（2026-11-03 发布），要求 Java 17+。但本项目有 `spring-ai` 1.0 GA、特定 Jackson 版本、MyBatis-Plus——依赖冲突只有拉下来才知道。
- **查哪里**：新建独立 demo 模块，不起现有应用（**运行期跑 `mvn` 会污染正在运行的 JVM classpath**）。
- **查完什么算完**：一个 server 暴露 Agent Card + 一个 client 发 `SendMessage` 收到 artifact；`mvn dependency:tree` 无版本降级；记录 JDK 17（`C:\Users\史航\.jdks\ms-17.0.20.1`）下的实际行为。

#### `AQ-10` TCK 能否对自建 server 跑

- **为什么重要**：`a2a-java` 带 **0.3 与 1.0 双版本 TCK**。有 TCK 意味着合规性可自动化验证，不用猜实现对不对——这是本项目最该用的资产，上一轮外部评审没提。
- **查哪里**：TCK 的启动方式与要求的 endpoint 形态；是否要求特定 binding（JSON-RPC / gRPC / HTTP+JSON）。
- **查完什么算完**：能对本地 server 跑 TCK，全绿或明确列出不适用项及理由。

### 3.4 第四梯队：可后置，但要知道代价

#### `AQ-2` execution command 消费者能否承载远端 taskId

- **查哪里**：`ExecutionCommandService.createAssignedCommand` 之后谁消费、怎么回写；`agent_execution_record` 表结构；`AGENT_COMPLETED` 事件链。
- **查完什么算完**：能否在**不加表**的前提下存 `(subTaskId, remoteTaskId, lastPolledAt, remoteState)` 四元组；不能的话给出最小新增。

#### `AQ-11` 外部 A2A 产物怎么落 MinIO

- **为什么重要**：`uploadArtifact` 只收「已在平台桶中」的 `minio://` 地址；A2A 给的是 inline bytes 或外部 URL。中间缺一段代传。
- **查哪里**：`uploadArtifact` 的地址校验逻辑；`POST /api/artifacts/upload` 入参。
- **查完什么算完**：inline bytes / 外部 URL / 平台代传 三条路径定一条，并说明 sha256 对账怎么做（`G-034` 已有对账先例可借）。

#### `AQ-12` 鉴权方案对齐

- **为什么重要**：本仓用 `agent.api_key` 当 consumer token（`AgentAccessType.usesConsumerTokenAuth()`）；A2A 的 `securitySchemes` 支持 API key / HTTP auth / OAuth 2.0 / OpenID Connect / mTLS。
- **查哪里**：`McpAuthFilter` / `AgentAuthPort` / `McpAuthContext` 的鉴权链。
- **查完什么算完**：能声明一种 A2A `securityScheme` 并在 Agent Card 里正确表达，且不破坏现有工牌语义。

#### `AQ-13` 外部 agent 执行的证据盲区

- **为什么重要**：`G-034` 的证据台账（`callId` / `origin` / `round`）只覆盖**你自己沙箱里的命令**。外部 A2A agent 在自己的环境跑，产物无法按台账对账——审核侧凭什么相信？
- **查哪里**：`SandboxCallLedger` 的写入点是否只限 `OpenSandboxRuntime`；审核侧证据来源的分支。
- **查完什么算完**：明确「外部 A2A 产物 = 不可台账化」的降级口径，以及审核侧该看什么替代证据（建议：产物 sha256 + 产物自证 + 外部执行环境声明）。

---

## 4. 明确定的不做（避免后续反复）

| # | 不做 | 理由 |
|---|---|---|
| 1 | 不支持 A2A `TASK_STATE_AUTH_REQUIRED` | 本仓凭据由平台托管（`usesCredentialVault()`），执行器不需要自己认证 |
| 2 | 不把 `SubTaskStatus` 压成 A2A 8 态 | 见 §1 第 1 条 |
| 3 | 不在第一步引入完整 A2A server 作为主通道 | 见 §1 第 2 条；且依赖 `AQ-6` |
| 4 | 不采信未能证实的社区仓库 | 见 §1 第 3 条 |

---

## 5. 粗粒度顺序建议（供 `design/` / `plan/` 参考，不含日期）

1. **先出事实**：`AQ-1` / `AQ-3` / `AQ-8` —— 这三条决定「A2A 执行者能不能在你的调度器里活下来」，都是读代码就能有答案的，成本最低。
2. **再定形态**：`AQ-5` / `AQ-7` / `AQ-4` —— 决定对外投影表与产物结构，是 ADR 的输入。
3. **然后验可行性**：`AQ-9` / `AQ-10` —— 拉 SDK 跑 demo + 跑 TCK，不碰主工程。
4. **最后补口子**：`AQ-6` / `AQ-2` / `AQ-11` / `AQ-12` / `AQ-13` —— 按实际走到的深度取舍。

> **一个前置提醒**：无论最后走哪条路，**`cancelSubTask` 这个洞都该先补**。它与 A2A 无关，是独立缺陷（见 §2.1），且出站与入站都要用它。成本按「加一个工具 + 一次状态机流转」估。

---

## 6. 需要用户裁定的三件事

| # | 待定 | 选项 |
|---|---|---|
| 1 | 立项形态 | ① 只登记差距表 `G-036`（缺口，不立项）；② 先出 `ADR-009`（定边界，不承诺实施）；③ 直接进 `plan/` 排批次。**建议 ②**——边界没定就排批次，返工两次。**2026-11-16 已裁定 ②**：ADR-009 已 `Accepted`，`G-036` 已登记；未进 `plan/`（A 档开工时再建批次） |
| 2 | 出站 vs 入站优先级 | 建议出站优先（供给侧扩容，收益可测）；入站等 `AQ-6` 有解 |
| 3 | `AQ-9` 是否现在跑 | 这是个 1~2 天的独立验证，不碰主工程。**建议现在跑**——SDK 能不能用决定后面所有判断，而它最便宜 |

---

## 7. 引用材料

**本仓**
- `helloai-core/src/main/java/com/helloai/core/agent/mcp/McpMcpServer.java`（13 个工具定义）
- `helloai-api/src/main/java/com/helloai/api/controller/McpController.java`（工具名分发 + JSON-RPC 入口）
- `helloai-common/src/main/java/com/helloai/common/constant/SubTaskStatus.java`（12 态）
- `helloai-common/src/main/java/com/helloai/common/constant/AgentAccessType.java`（3 类 + 4 个自述方法）
- `helloai-core/src/main/java/com/helloai/core/agent/dispatcher/SubTaskAutoExecutionDispatcher.java`（push/pull 双范式）
- `helloai-core/src/main/java/com/helloai/core/agent/port/TaskDispatchPort.java`（选人端口，非执行通道）
- `doc/design/adr/ADR-008-task-decomposition-derivation.md`（派生任务与 `AWAITING_DERIVED`）
- `doc/HelloAI 实现差距表.md` `G-032`（`WorkspaceRef.contextId`）/ `G-033`（`CreateTaskRequest` 通道）/ `G-034`（证据台账）
- `doc/research/HelloAI 附件到计划到编排执行链能力调研（2026-11-13）.md` §11（从工作台起步的整体缺口）

**外部（`[推断]`，接入前须实测）**
- A2A 规范 v1.0.1（2026-05-28）：Agent Card 路径 `/.well-known/agent-card.json`；11 个操作（`SendMessage` / `SendStreamingMessage` / `GetTask` / `ListTasks` / `CancelTask` / `SubscribeToTask` / push config ×4 / `GetExtendedAgentCard`）；8 个 TaskState；`A2A-Version` 请求头协商
- `a2aproject/a2a-java` v1.5.0.Final（2026-11-03）：`a2a-java-sdk-client` 独立 artifact；extras 含 JPA task store / Kafka replicated queue / OpenTelemetry；带 0.3 与 1.0 双 TCK
- ACP（IBM/BeeAI）已于 2025-08-29 并入 A2A；2025-12-09 Linux Foundation 成立 AAIF，**MCP 与 A2A 同属其治理**——两者是分层关系，不是竞争关系
- MCP Tasks 于 2026-07-28 从核心协议移出为官方扩展 `io.modelcontextprotocol/tasks`，5 态 `working / input_required / completed / failed / cancelled`
