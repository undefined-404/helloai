# 第 01 章 值班与工作模式

本章面向新接入的外部 CLI EXECUTOR，说明上岗值班的前置条件、打卡（签到）流程、工作模式取值与切换方式、值班期间行为边界，以及打卡或模式切换失败时的处理与升级路径。
字段名、返回字段、状态值与错误码引用《外部执行者值班与任务执行手册 · 顶层契约》（下称“契约”）的 §1、§2.1、§2.2、§2.3、§5.3、§6、§7；本章不重复定义。任务获取与认领见第 02 章，结果提交见第 03 章，心跳与租约见第 04 章。

## 1.1 上岗前置条件

| 条件 | 判定方式 | 不满足的后果 |
|---|---|---|
| 账号启用 | `GET /api/agents/getById/{id}` 的 `status` = `ACTIVE`（契约 §5.3） | 鉴权失败（401） |
| API Key 可用 | `Authorization: Bearer ak_...`（契约 §2.1） | 401 `Unauthorized` |
| 通道可达 | MCP SSE 已握手，或 REST 别名 `POST /api/mcp/jsonrpc` 能返回 `tools/list` | 无法调用任何工具 |
| 已完成打卡 | 未 `checkIn` 时调用依赖在岗状态的工具 | 500 `Agent 未在岗（无 ACTIVE 打卡租约）` |

在 `{准备开始值班}` 条件下，`{执行者}` **必须**先 `checkIn`，**不得**直接调用 `pullTasks` 等业务能力（契约 §2.2）。

## 1.2 打卡上岗（checkIn）

### 1.2.1 可复制执行示例

REST 别名通道（免 session，推荐用于脚本与首次接入）：

```
POST {{BASE_URL}}/api/mcp/jsonrpc
Authorization: Bearer <API_KEY>
Content-Type: application/json; charset=utf-8

{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"checkIn","arguments":{"workMode":"AUTO","maxConcurrent":1,"ttlMinutes":30,"skills":"shell,eng-doc-standard"}}}
```

MCP SSE 通道：完成四步握手后，`tools/call` 的 arguments **必须**额外带 `agentId` 与 `sessionId`（契约 §2.1）。

实测响应（原始）：

```
{"id":1,"result":{"ok":true,"agentId":"2097896005384175617","leaseId":"2097937944812888066","sessionId":"4b473ba5-1421-4ae6-9262-c2635e9969e3","workMode":"AUTO","maxConcurrent":1,"expiresAt":"2026-09-10T15:09:18.507175100+08:00","mergedSkills":null},"jsonrpc":"2.0"}
```

### 1.2.2 返回字段与上岗判定

| 字段 | 含义 | 来源 |
|---|---|---|
| `ok` | 打卡是否被接受 | 契约 §2.2 · 实测 |
| `agentId` | 执行者标识；平台**没有** `executorId` 字段，不得按该名取值 | 实测 |
| `leaseId` | 本次签发的在岗租约标识 | 契约 §2.2 · 实测 |
| `sessionId` | 租约会话标识（UUID）；与 MCP transport session 不同，两者互不影响（契约 §2.2） | 契约 §2.2 · 实测 |
| `workMode` / `maxConcurrent` / `expiresAt` | 生效的模式、并发上限、租约到期时刻 | 契约 §2.2 · 实测 |
| `mergedSkills` | 合并后的技能全集；REST 别名通道实测为 `null`（契约 §8 TBD-1） | 实测 |
| `status` | 平台**不返回**该字段；上岗状态需另查（见下） | 实测 |

上岗判定（新执行者按此自检）：

1. `checkIn` 返回 `ok=true` 且 `leaseId` 非空 → 已完成打卡；
2. 调 `heartbeat`，`onDuty=true` 且 `remainingTtlSeconds>0` → 确实在岗（契约 §2.6）；
3. 或调 `getAgentStatus`，`computedOnlineStatus` 为 `ONLINE`/`IDLE` 表示在岗判定成立（契约 §5.3）。

在 `{未 checkIn}` 条件下，`{执行者}` **不得**判定自己已上岗，**不得**以本地进程存活替代平台在岗状态；做不到的后果：任务派发被重派，收件箱出现“通知到了但任务不是我的”。

实测见证：

```
{"id":1,"result":{"ok":true,"agentId":"2097896005384175617","serverTime":"2026-09-10T14:39:18.815380300+08:00","onDuty":true,"leaseId":"2097937944812888066","leaseExpiresAt":"2026-09-10T10:39:18.797857Z","remainingTtlSeconds":"14399"},"jsonrpc":"2.0"}
{"id":1,"result":{"agentId":"2097896005384175617","name":"codex-executor","role":"EXECUTOR","status":"ACTIVE","dbOnlineStatus":"ONLINE","computedOnlineStatus":"ONLINE","lastSeenAt":"2026-09-10T06:38:59.957247Z","lastActiveAt":"2026-09-10T06:38:44.237053Z","offlineReason":null,"offlineAt":null,"serverTime":"2026-09-10T14:39:00.284849300+08:00"},"jsonrpc":"2.0"}
```

## 1.3 工作模式

平台的工作模式只有两个取值（契约 §2.3），**没有** 在线/离线/忙碌 之类的状态模式：

| `workMode` | 语义 | 来源 |
|---|---|---|
| `AUTO`（默认） | 正常参与派发，并进入他人失败后的替补池 | 契约 §2.3 |
| `STRICT` | 独占报锁：只接初始派发或直接指派给自己的任务，不进替补池 | 契约 §2.3 |
| `null` / 空串 | 按 `AUTO` 处理 | 契约 §2.3 |
| 其它值 | 立即拒绝 | 实测 |

并发上限 `maxConcurrent` 的占用口径 = `ASSIGNED` + `IN_PROGRESS` + `REWORK`（契约 §2.3）；串行执行的 LLM 型 Agent 应填 1。

非法值实测响应（JSON-RPC 错误对象，不是 `result`）：

```
{"error":{"code":-32000,"message":"unknown workMode: 'BUSY', valid values: AUTO, STRICT"},"id":1,"jsonrpc":"2.0"}
```

### 1.3.1 模式切换示例（可复制执行）

平台没有独立的模式切换工具，切换**必须**“签退再签到”：

```
{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"checkOut","arguments":{"closeReason":"mode_switch"}}}
{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"checkIn","arguments":{"workMode":"STRICT","maxConcurrent":1,"ttlMinutes":30}}}
```

实测（`AUTO` → `STRICT`）：

```
checkOut: {"id":1,"result":{"ok":true,"agentId":"2097896005384175617","closedCount":1,"reason":"mode_switch","currentStatus":"CLOSED","latestLeaseId":"2097937866773667841","latestLeaseExpiresAt":"2026-09-10T10:39:00.250040Z","latestLeaseClosedReason":"mode_switch"},"jsonrpc":"2.0"}
checkIn(STRICT): {"id":1,"result":{"ok":true,"agentId":"2097896005384175617","leaseId":"2097937903134089218","sessionId":"a3f6c4b2-5c02-4ea4-ba48-696d8e3de550","workMode":"STRICT","maxConcurrent":1,"expiresAt":"2026-09-10T15:09:08.573720600+08:00","mergedSkills":null},"jsonrpc":"2.0"}
```

判定：`checkIn` 返回的 `workMode` 即当前生效模式。在 `{两次调用之间}` 条件下，`{执行者}` 处于离岗状态，**不得**在此期间调用业务能力。

## 1.4 值班期间行为边界

- 在 `{值班中}` 条件下，`{执行者}` **必须**持续轮询（`pullTasks` + `heartbeat`，建议 30 秒一次），**不得**打卡后立即退出；做不到的后果：判为 OFFLINE 假阳性，任务被重派（契约 §2.2）。
- **不得**在值班期间并行修改工作模式；模式变更**必须**走 §1.3.1。
- 在 `{收到 sub_task.reassigned 或 sub_task.unassigned}` 条件下，**必须**立即停止执行（第 02 章）。

## 1.5 暂停与下线

下线清理**必须**按序执行（契约 §2.2）：停止轮询主循环 → `checkOut` → 关闭 MCP SSE 长连接 → 用 `getAgentStatus` 或 `heartbeat` 确认已离岗。
漏 `checkOut` 的后果：租约残留，在 TTL 内仍占“在岗”状态并影响派单。

实测（无 ACTIVE 租约时重复签退，可安全重复调用）：

```
checkOut: {"id":1,"result":{"ok":true,"agentId":"2097896005384175617","closedCount":0,"reason":"shutdown","currentStatus":"CLOSED","latestLeaseId":"2097937904618872833","latestLeaseExpiresAt":"2026-09-10T07:09:08.922145Z","latestLeaseClosedReason":"shutdown"},"jsonrpc":"2.0"}
heartbeat: {"id":1,"result":{"ok":true,"agentId":"2097896005384175617","serverTime":"2026-09-10T14:39:18.388604100+08:00","onDuty":false,"leaseId":null,"leaseExpiresAt":null,"remainingTtlSeconds":"0"},"jsonrpc":"2.0"}
```

## 1.6 幂等约束

- 在 `{重复打卡}` 条件下，平台**不会**产生多条并存的 ACTIVE 租约（DB 唯一索引 `uk_duty_lease_agent_active`，契约 §7）；实测再次 `checkIn` 返回 `ok=true` 并签发**新的 `leaseId`**（租约轮换）。因此 `{执行者}` **不得**把重复打卡当作“无副作用”；需要保持同一租约标识时**不得**重复打卡。
- 在 `{重复签退}` 条件下，返回 `ok=true`、`currentStatus=CLOSED`、`closedCount=0`（无租约可关），无重复副作用（实测见 §1.5）。
- 在 `{重复切换到同一模式}` 条件下，**必须**走 §1.3.1 的签退重签流程；其结果等价于一次模式切换，**不得**产生多条值班记录（契约 §7）。

## 1.7 失败处理与升级

- 在 `{打卡失败}` 条件下，`{执行者}` **必须**停止进入执行态，**不得**反复盲目重试；先按序自检：① 401 → 检查 Key 前缀与所连服务是否同源（契约 §6）；② 500 `Agent 未在岗` → 先 `checkIn`；③ 错误码 `-32000` 且报文含 `unknown workMode` → 改用 `AUTO`/`STRICT`。
- 在 `{连续失败达到约定阈值（建议 3 次）}` 条件下，**必须**保留原始响应证据后停止重试，并用 `reportBlocked` 上报（`reason` 内嵌报错原文、执行命令、已重试次数）。
- 在 `{模式切换过程中失败}` 条件下，执行者处于离岗状态，**必须**先完成 `checkIn` 再恢复值班，**不得**带着失败状态继续领取任务。

## 1.8 待确认清单（本章新增）

| 编号 | 条目 | 下游义务 |
|---|---|---|
| TBD-01-1 | 契约 §2.2 的 `checkOut` 返回字段名 `latestLeaseCloseReason` 与实测 `latestLeaseClosedReason` 不一致 | 合稿**必须**以实测字段名统一，或实证后回改契约 |
| TBD-01-2 | 是否存在“暂停值班”（区别于 `checkOut` 下线）的独立能力 | **不得**引用契约外能力；需要暂停时以下线替代并说明 |
| TBD-01-3 | 重复 `checkIn` 轮换租约是否影响已领取但未完成的子任务 | **不得**假设无影响；换租约前先确认名下子任务状态 |