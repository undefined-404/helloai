# ADR-009：A2A 兼容边界——双平面、内核不改、出站优先

| 项目 | 内容 |
| --- | --- |
| **编号** | ADR-009 |
| **状态** | Accepted（2026-11-16） |
| **日期** | 2026-11-16 |
| **决策类型** | 协议边界 / 对外形态 / 分期节奏 |
| **关联文档** | `research/HelloAI A2A 兼容与私有 MCP 改造调研清单（2026-11-16）.md`（`AQ-1`~`AQ-13` 待验问题的唯一来源）；`design/adr/ADR-008-task-decomposition-derivation.md`（派生任务与 `AWAITING_DERIVED`）；`HelloAI 实现差距表` `G-036` |
| **唯一设计事实源** | 本 ADR。A2A 边界的**结论**只在这里；调研清单只提供「还没查清什么」，不提供结论 |

> **一句话**：A2A 只做外贸口岸，私有 MCP 仍是管控面；`SubTaskStatus` 12 态一个都不改，对外投影靠三条原则 + 「A2A task 身份 ≠ subTask 身份」；先补通道洞，再做出站，入站等真实需求。

> **本文不跟踪进度**。批次、状态、完成度一律在 `plan/` 与差距表；本文只定边界与禁止项。

## 1. 背景与问题

本仓对外执行通道是一套自建 MCP 语义，13 个工具（`McpMcpServer.java` 的 `@Tool` 声明，自 `:70` 起；分发在 `McpController.java:52`）：

```
pullTasks / ack / claimSubTask / startSubTask / heartbeat / uploadArtifact /
submitResult / reportBlocked / getAgentStatus / getDepsSummary /
getSubTaskDetail / checkIn / checkOut
```

与 A2A（v1.0.1，2026-05-28 稳定）的差距分三层，不是「少一个参数」：

| 层 | A2A 有 | 本仓现状 |
| --- | --- | --- |
| 参数级 | `Task.contextId` 把多个 task 归为一个会话 | 无这一维。`derived_task_id` 是层级关系，不是会话分组 |
| 操作级 | `CancelTask` | **13 个工具里一个 cancel 都没有**（全文件 grep 零命中） |
| 结构级 | `Artifact{artifactId, name, parts[]}`，parts 为 text/file/data 类型数组 | `submitResult.output` 是 `String`；`uploadArtifact` 只收平台桶内 `minio://` |

更硬的差距在状态机：本仓 `SubTaskStatus` 12 态（`helloai-common/.../constant/SubTaskStatus.java:3-34`），A2A 8 态，MCP Tasks 扩展 5 态。`REWORK` / `AWAITING_DERIVED` / `RESTRUCTURING` / `PAUSED` / `REVIEW` / `DEAD_LETTER` 在 A2A 里没有位置，且 A2A 明文规定「terminal task never restarts」——**返工与派生恰好是本仓最核心的两个能力**。

因此本文要回答的不是「要不要兼容」，而是三件事：**改哪一层、哪些永不改、按什么顺序**。

## 2. 决策

### 2.1 D1 内核不改

`SubTaskStatus` 12 态、租约（lease）、心跳、抢单（claim）、返工（`REWORK`）、死信（`DEAD_LETTER`）、派生（`AWAITING_DERIVED` / `RESTRUCTURING`）一律保留原样。

**判据**：这些是**对内治理语义**，回答「谁在什么时候能用哪份资源、失败后归谁处理」。A2A 回答的是「两个对等体之间怎么交接一个任务」。两者不是同一层东西，对齐一个不改变另一个。任何「为了对外好看而合并状态」的改动，都会让 `SubTaskStateMachine` 的出边判定、定时任务的扫描集合、看板口径同时失效——改动面是状态机的六倍（枚举 / CHECK 约束 / 转换表 / 前端映射 / 穷举 switch / 统计口径，见 `ADR-008` §4）。

### 2.2 D2 双平面

```
外部世界  ──A2A 口岸（翻译层，只此一处变形）──  HelloAI 管控面（私有 MCP + 12 态内核）
```

- **口岸**：只做「身份 → 本仓主体」「外部 task → 本仓任务」「本仓产物 → Artifact」三次翻译，不含业务判断。
- **管控面**：派发、验收、返工、派生、死信兜底、成本核算，全部留在内层，**外部不可见、不可配、不可绕**。
- 外部语义**不得**穿过口岸进入内核：不存在「A2A 模式」的开关，也不存在「A2A 专用」的状态列或分支表。

### 2.3 D3 状态投影三原则

对外只暴露 A2A 的 8 态，本仓 12 态的细粒度不外泄。三条原则按优先级排列：

**P1 — 无对应态一律挤进 `WORKING`，靠 `status.message` 说人话。**
不为「对外好看」新造中间态，也不提前发终态。宁可外部信息少，也不撒谎。

**P2 — 终态只在三处发出**：`DONE` → `COMPLETED`、`CANCELLED` → `CANCELED`、`DEAD_LETTER` → `FAILED`。其余九个状态**全部**是 `SUBMITTED` 或 `WORKING`。

**P3 — `BLOCKED` 不投影 `INPUT_REQUIRED`。**
A2A 的 `input-required` 是「**调用方**补一个信息就能继续」，控制权在 client；本仓 `BLOCKED` 是「**执行器**搞不定，通知 PLANNER 排障」，后手是 `RESTRUCTURING` / 重派 / 死信。方向相反。若照 A2A 语义实现，外部 client 会以为「补个参数就好」，实际任务正被重拆。

投影全表（**出站**：本仓状态 → 对外 A2A 态）：

| 本仓状态 | 对外态 | `status.message` 语义位 | 备注 |
| --- | --- | --- | --- |
| `PENDING` | `SUBMITTED` | 排队待派发 | |
| `ASSIGNED` | `SUBMITTED` | 已分配，等待执行器开工 | A2A 无「已分配」；尚未真开始，不发 `WORKING` |
| `IN_PROGRESS` | `WORKING` | — | |
| `BLOCKED` | `WORKING` | 执行器报阻塞，平台排障中 | **P3** |
| `REVIEW` | `WORKING` | 已交付，审核中 | **「`completed` ≠ `DONE`」的落点** |
| `REWORK` | `WORKING` | 审核驳回，返工中 | 若先发 `COMPLETED` 再回 `WORKING` 即违约 |
| `PAUSED` | `WORKING` | 已暂停 | |
| `AWAITING_DERIVED` | `WORKING` | 已拆解为独立子任务，等待其完成 | `ADR-008` 派生 |
| `RESTRUCTURING` | `WORKING` | 人工接管中 | |
| `DONE` | `COMPLETED` + `artifacts[]` | — | **唯一**发 `COMPLETED` 的入口 |
| `CANCELLED` | `CANCELED` | — | 拼写单 L |
| `DEAD_LETTER` | `FAILED` | 自动链路终止，等待人工介入 | 本仓该态**非终态**，处置见 D4 |

**逐条文案**（`status.message` 的具体措辞、是否随状态变更推 `SubscribeToTask` 事件）留 `AQ-5` 回填 §8.4；上表的**态选择**已由 P1~P3 定死，不需要再讨论。

### 2.4 D4 A2A task 身份 ≠ subTask 身份

这是本文最关键的一条，也是「A2A 终态不可重开」与「本仓可无限重跑」这一对矛盾的正解。

**不做**「把 `subTaskId` 直接当 A2A `taskId`」。改成一次交付尝试 = 一个 A2A task：

| 方向 | 粒度 | 生命周期 |
| --- | --- | --- |
| **出站** | 一个 subTask 的**一次交付尝试** = 一个 A2A task | 进入 `DEAD_LETTER` 后发 `FAILED` 并终结该 A2A task；人工打捞重跑（`DEAD_LETTER → ASSIGNED/REWORK`、`RESTRUCTURING → PENDING`）**发新的 A2A task**，旧 task 保持 `FAILED` 不变 |
| **入站** | 一次外部委派 = 一个本仓 **Task**（不是 subTask） | 外部 `COMPLETED` 只对应本仓 `DONE`；本仓内部再拆解为多个 subTask，对外仍是一条 task |

这样做的收益：

1. A2A 的「终态不可重开」**永远不被违反**，不需要任何「复活」补偿逻辑。
2. 本仓的可重开能力**一点不损失**——重跑就是新任务，语义上也更诚实（第二次尝试本来就不是第一次那个交付）。
3. 出站不需要把 `subTask` 的 12 态压缩表达，因为 A2A task 只管「这次尝试成没成」。

代价：需要一张「A2A taskId ↔ (subTaskId, attempt)」映射。**入站**另有远端 session/taskId 需要落库（`AQ-2`）。这张映射表**不得**退化成第二套任务表——它只存身份与状态投影缓存，业务事实仍在 `sub_task` / `agent_execution_record`。

### 2.5 D5 出站优先于入站

- **出站**（本仓作为 A2A client，调度外部 A2A agent）= 扩供给侧。现状外部 executor 只有 2 个（`trae-excutor` / `TeleAgent-executor`），接入一个要它实现 13 个私有工具、读 SKILL.md、维持心跳、处理租约——**门槛本身就是运营负担**。出站把门槛降到「实现一个 A2A server」，收益立刻可测。
- **入站**（本仓作为 A2A server 被外部编排）= ToB 故事，但入站被 `AQ-6` 硬阻塞（`G-033`：`CreateTaskRequest` 写 `context` 唯一可行路径是直改库），且没有真实客户前做等于赌。

「先出站」也纠正了一个容易被误读的点：**A2A 兼容是门票，不是护城河**。5 天能买到的门票不该被当成「重点整改」的战略项目去投入；真正的差异化在管控面深度（返工、派生、证据台账、成本闸门），那些不对外开放。

### 2.6 D6 三档节奏与启动判据

| 档 | 内容 | 估算 | 启动判据 |
| --- | --- | --- | --- |
| **A 档** | 补通道洞（与 A2A 无关，独立收益）：`A-1` `cancelSubTask` / `A-2` `submitResult.parts` / `A-3` 任务快照补 `contextId` / `A-4` `sessionId` 与 MCP 会话概念解耦 | 1~2 天 | **无判据，现在做** |
| **B 档** | 出站 client：`AgentAccessType.A2A_AGENT` + push 分支 + 远端 taskId 对账 | ~1 周 | 主线闭环——「工作台建任务 → 拆解 → 确认 → 派发 → 执行 → 验收 → 报告」全链路无需人工改库跑通 |
| **C 档** | 入站 server：`agent-card` + 只读面（`GetTask` / `ListTasks` / `CancelTask`）→ 完整 `SendMessage` 接入 | `C-1` 只读面 3~5 天；`C-2` 完整面另计 | 三条任一命中：① 出现第一个「只会 A2A、不愿实现私有 MCP」的供给方；② 外部执行器 ≥5 个，维护多份 SKILL.md 适配的边际成本超过 adapter；③ ToB 客户明确要求把 HelloAI 编入其 A2A 网格。**且 `AQ-6` 必须先有解** |

**判据是可观测的，不是拍脑袋的**。B 档之前不动 A2A 代码，是因为主线未闭环时做协议迁移要返工两次；C 档之前不动入站，是因为「没人会来读的 Agent Card」是零收益资产。

A 档四洞的具体口径：

| 编号 | 内容 | 落点 | 关键判据 |
| --- | --- | --- | --- |
| `A-1` | 新增 `cancelSubTask` 工具 | `McpMcpServer`（注解层）+ `McpToolService`/`Impl` + `McpController` 分发 | **状态机无需新增边**（已核实 `SubTaskStateMachine` 的 `TRANSITIONS`：除 `DONE`/`CANCELLED` 外，10 个状态都有 → `CANCELLED` 出边）。权限：只能取消**自己名下**且非终态的任务，否则显式 `new BizException(400, ...)` |
| `A-2` | `submitResult` 加可选 `parts` | `McpToolService.java:343` `SubmitResultResult` + 入参 | `parts` 存在时 `output` **不再是第二事实源**，退化为纯文本简写；存储形态待 `AQ-7` |
| `A-3` | 任务快照补 `contextId` | `McpToolService` 的 `SubTaskDetail` | 值取自 `sub_task.context` 既有键或会话分组；**禁用 `WorkspaceRef.contextId`**（见 §4.3） |
| `A-4` | `sessionId` 解耦 | 13 个工具的 `sessionId` / `_sessionId` 参数 | MCP 2026-07-28 已把 `Mcp-Session-Id` 移出核心（stateless core），本仓 `sessionId` 是自家凭据会话，同名两义必致外部作者误判。加新名 + 旧名保留一个周期 |

## 3. 实现落点（明确「不是哪里」）

### 3.1 出站落点在 execution command 侧，不在 `TaskDispatchPort`

`SubTaskAutoExecutionDispatcher.java:31-35` 类注释原文：

> 「当前只对 `AgentAccessType.API_KEY_LLM` 创建 execution command；CLI_CLIENT 仍走收件箱/MCP 拉取链路。」

即执行器有两套范式并存：`CLI_CLIENT` = **pull**（执行器自己拉），`API_KEY_LLM` = **push**（平台建命令主动驱动）。**A2A 必须走 push**。

| 正确的落点 | 错误的落点 |
| --- | --- |
| `AgentAccessType` 加 `A2A_AGENT` | 写个 `A2aDispatchAdapter implements TaskDispatchPort` |
| `SubTaskAutoExecutionDispatcher` 加 push 分支 | —— |
| `ExecutionCommandConsumer`（`helloai-core/.../agent/mqconsumer/`）加消费分支 | —— |
| 新增远端 taskId 对账循环（拿 `GetTask` 回收状态） | —— |

`TaskDispatchPort.java:22` 的语义是**选人 + 分配到某个 agentId**（`assignNext(agentId, subTaskId, ...)`），不负责「怎么把任务送到执行者手上」。把 A2A 调用写进这个端口，会让「选人」与「投递」耦死。

> 「不需要改核心域」这句话只对**内核**成立，对**通道层**不成立。真实成本是「加一个执行通道 + 一个对账循环」，比「两个 adapter」贵一倍以上。

### 3.2 `A2A_AGENT` 的两个必答项

`AgentAccessType` 有 4 个自述方法，其中两个对新类型是**静默陷阱**：

| 方法 | 必须返回 | 漏改后果 |
| --- | --- | --- |
| `requiresRuntimeLiveness()` | **`false`** | 返回 true ⇒ A2A agent 因无心跳被判 `OFFLINE` 不可分配，「心跳在线才可分派」这条铁律会把它永远钉在 `PENDING`，且**没有任何报错** |
| `defaultCapabilities()` | `supportsA2A=true`、`supportsArtifactUpload=true`、`supportsPull=false`、`supportsMCP=false` | 该方法是**不带 `default` 的 switch 语句**，新增枚举值**编译器不报错**、静默不设任何 capabilities ⇒ 选人时能力判据全空 |

这是本 ADR 唯一要求「代码写之前先看一眼」的地方——它的失败形态是静默的。

### 3.3 入站落点必须过服务层

入站适配器**不得**直写 `task` / `sub_task` / `requirement_conversation`。理由不只有分层洁癖：`G-033` 已记「`CreateTaskRequest` 写 `context` 的唯一可行路径是直改库」，直写表就等于把 `contextId` / 远端 `taskId` / `messageId` 全部丢掉，且绕过草案门禁与 `owner_id` 归属。

`AQ-6` 的结论就是这条前置。

## 4. 禁止项

| # | 禁止 | 理由 |
| --- | --- | --- |
| 4.1 | 把 `SubTaskStatus` 压成 A2A 8 态 | §2.1。六个态无落点，且 A2A 终态不可重开 |
| 4.2 | 为对齐外部协议增设「A2A 模式」开关或分支状态机 | §2.2。开关会让内层逻辑出现第二套真值路径，且差异只在故障时显现（同 `ADR-008` §18.7 对「单机/分布式双路径」的判定） |
| 4.3 | 复用 `WorkspaceRef.contextId` 承载 A2A 的会话分组 | 同名两义。`WorkspaceRef.contextId` 是**沙箱上下文**（`SL-8`，见 `G-032`），A2A `contextId` 是**会话分组**；共用一列必然分叉 |
| 4.4 | 给 A2A 另建一套任务表 / 任务状态列 | §2.4 只允许一张身份映射表（存 `taskId ↔ (subTaskId, attempt)`），业务事实仍在本仓表 |
| 4.5 | 在 `AQ-3` 未定前把 `A2A_AGENT` 接进自动派发 | `AssignedSubTaskTimeoutTask` 按 `dispatch.assigned-timeout-minutes`（默认 10）回收 `ASSIGNED`，而 A2A 的定位是跨小时任务。接上就是「跑到第 20 分钟被平台判死」，比不做更糟 |
| 4.6 | 入站直写库 | §3.3 |
| 4.7 | 对外承诺 `AUTH_REQUIRED` | 本仓凭据要么平台侧托管（`usesCredentialVault()`）、要么工牌化（`usesConsumerTokenAuth()`），不存在「执行器需自行向第三方认证」的通道；声明一个永不发生的能力等于给外部 client 埋一个死分支 |
| 4.8 | 提前发 `COMPLETED` | §2.3 P2。A2A 终态不可重开，一次早发就让后续返工无处安放 |

## 5. 待定项与回填方式

本 ADR 有意**留下七处待定**。它们不是「没想清楚」，而是「必须实测才能定」，且**都不影响 §2 的边界结论**。

| 待定 | 阻塞什么 | 回填位置 |
| --- | --- | --- |
| `AQ-1` `A2A_AGENT` 完整影响面 | B 档动工 | §8.1 |
| `AQ-3` 长任务与 10 分钟回收 / 租约 TTL 的冲突（**最可能埋雷**） | B 档派发 | §8.2 |
| `AQ-8` 无心跳执行者如何通过选人判活 | B 档派发 | §8.3 |
| `AQ-5` 逐条 `status.message` 文案与 push 事件时机 | C 档对外形态 | §8.4 |
| `AQ-6` 入站任务创建的 `context` 写库通道 | C 档（硬阻塞） | §8.5 |
| `AQ-7` `parts` 的存储形态与 `output` 兼容策略 | A-2 落库 | §8.6 |
| `AQ-4` `contextId` 撞名处置（改名 or 内部另起名） | A-3 | §8.7 |
| `AQ-2` / `AQ-11` / `AQ-12` / `AQ-13` | 远端 taskId 存储 / 产物落桶 / 鉴权 / 证据盲区 | §8.8~§8.11 |
| `AQ-9` / `AQ-10` SDK 与 TCK 实测 | B 档技术前提 | §8.12 |

**回填规则**：§8 只追加，不改写 §1~§7 的结论。若某条回填**推翻**已有决策（例如实测发现 `a2a-java` 在 JDK 17 下不可用），必须新开 ADR 并在头部标 `Supersedes: ADR-009`，不得就地改正文。

## 6. 必须按 v1.0.1 写对的六个事实

外部流传的 A2A 材料大面积停留在 v0.2/v0.3，照抄会直接跑不通。以下六条是实现时的硬约束：

| # | 常见错误写法 | v1.0.1 正确写法 |
| --- | --- | --- |
| 1 | `/.well-known/agent.json` | **`/.well-known/agent-card.json`**（v0.3.0 起改名） |
| 2 | JSON-RPC `tasks/send` 或 `message/send` | **`SendMessage`**（PascalCase） |
| 3 | 生命周期 6 态 | **8 态**（漏 `REJECTED` / `AUTH_REQUIRED`；拼写 `CANCELED` 单 L） |
| 4 | 不涉及版本 | 必须读 **`A2A-Version`** 请求头；空值按 0.3 处理；不支持须回 `VersionNotSupportedError` |
| 5 | `BLOCKED` ↔ `INPUT_REQUIRED` | **不映射**（§2.3 P3，控制权朝向相反） |
| 6 | 改造落点 = `TaskDispatchPort` | execution command 侧（§3.1） |

## 7. 后果

**正面**

- 内核零改动。12 态、租约、心跳、返工、派生、死信全部保留，`ADR-008` 的派生链不受影响。
- 对外兼容的**成本封顶在口岸层**：三个翻译动作 + 一张身份映射表，不穿透到业务。
- 「终态不可重开」与「本仓可重跑」不再矛盾（D4），不需要任何复活补偿。
- A 档四洞不依赖 A2A，先做先得：`cancelSubTask` 补上后，executor 卡死终于有体面的退出口。

**负面（如实登记）**

- **对外永久性信息损失**：12 态的细粒度在 A2A 视图里被压成 `WORKING` + 一句文本。外部 client 无法程序化区分「审核中」与「返工中」，只能读 `status.message` 或调本仓 API。这是接受的代价——把细粒度暴露成外部契约，等于把内部状态机冻结成公共 API。
- **多一个对账循环**：出站要持续 `GetTask` 回收远端状态，这是新增的运维面与失败面（远端失联、版本不兼容、taskId 丢失）。
- **一张身份映射表**：`A2A taskId ↔ (subTaskId, attempt)`，需要清理策略（旧 attempt 何时归档）。
- **C 档被 `AQ-6` 硬阻塞**：入站在 `context` 写库通道打通前无法开工，这不是排期问题而是前置缺失。
- **三档之间不是严格串行**：A 档与 B 档无依赖，可并行；但 B 档的 `AQ-3` 若不解决，风险高于收益。

## 8. 增补记录（待定项回填区）

> 只追加。每条回填须带日期、来源（实测 / 文档）、结论，以及「是否影响 §1~§7」的显式判断。

| 节 | 对应 | 状态 | 回填内容 |
| --- | --- | --- | --- |
| §8.1 | `AQ-1` | 待回填 | — |
| §8.2 | `AQ-3` | 待回填 | — |
| §8.3 | `AQ-8` | 待回填 | — |
| §8.4 | `AQ-5` | 待回填 | — |
| §8.5 | `AQ-6` | 待回填 | — |
| §8.6 | `AQ-7` | 待回填 | — |
| §8.7 | `AQ-4` | 待回填 | — |
| §8.8 | `AQ-2` | 待回填 | — |
| §8.9 | `AQ-11` | 待回填 | — |
| §8.10 | `AQ-12` | 待回填 | — |
| §8.11 | `AQ-13` | 待回填 | — |
| §8.12 | `AQ-9` / `AQ-10` | 待回填 | — |
