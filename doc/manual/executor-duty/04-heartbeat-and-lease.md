# 第 04 章 心跳与租约保持

本章说明外部 EXECUTOR 如何通过心跳保持在线、租约如何自动续期、心跳或续租失败时的降级与恢复动作，以及租约过期后的边界（停止执行、释放任务或上报）。
字段名、返回字段、状态值与错误码引用《外部执行者值班与任务执行手册 · 顶层契约》（下称“契约”）的 §1、§2.1、§2.2、§2.6、§5.3、§6、§7；本章不重复定义。值班打卡与工作模式见第 01 章，任务获取与认领见第 02 章。

## 4.1 心跳示例

```
POST {{BASE_URL}}/api/mcp/jsonrpc
Authorization: Bearer <API_KEY>
Content-Type: application/json; charset=utf-8

{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"heartbeat","arguments":{}}}
```

实测响应（连续两次调用）：

```
{"id":1,"result":{"ok":true,"agentId":"2097896005384175617","serverTime":"2026-09-10T14:42:56.970677400+08:00","onDuty":true,"leaseId":"2097937944812888066","leaseExpiresAt":"2026-09-10T10:42:56.953502Z","remainingTtlSeconds":"14399"},"jsonrpc":"2.0"}
{"id":1,"result":{"ok":true,"agentId":"2097896005384175617","serverTime":"2026-09-10T14:42:57.230178300+08:00","onDuty":true,"leaseId":"2097937944812888066","leaseExpiresAt":"2026-09-10T10:42:57.211837Z","remainingTtlSeconds":"14399"},"jsonrpc":"2.0"}
```

字段表：

| 字段 | 含义 | 来源 |
|---|---|---|
| `ok` | 调用是否成功 | 契约 §2.6 · 实测 |
| `onDuty` | 是否持有 ACTIVE 租约（在岗判定） | 契约 §2.6 · 实测 |
| `leaseId` | 当前租约标识；未在岗时为 `null` | 契约 §2.6 · 实测 |
| `leaseExpiresAt` | 租约到期时刻（ISO8601 带时区；UTC 写作 `Z`） | 契约 §2.6 · 实测 |
| `remainingTtlSeconds` | 续租后的剩余秒数；字段类型为**字符串**，未在岗时为 `"0"` | 实测 |
| `serverTime` | 平台服务器时间（Asia/Shanghai） | 契约 §2.6 · 实测 |

注意：`heartbeat` 返回的到期字段是 `leaseExpiresAt`，`checkIn` 返回的是 `expiresAt`（契约 §2.2）；两者是同一租约的不同字段名，**不得**互换使用。比较时间**必须**按绝对时刻解析，不得按字符串字面量比较（契约 §2.6）。

## 4.2 推荐间隔与超时阈值

| 项 | 取值 | 说明 | 来源 |
|---|---|---|---|
| 心跳间隔 | 每 30 秒 | 与 `pullTasks` 同一主循环 | 契约 §2.6 |
| 判离线阈值 | 5 分钟无 `heartbeat` | 业务调用只刷 `last_active_time`，不维持在线 | 契约 §2.6 · §5.3 |
| 平台巡检周期 | 30 秒 | 租约到期由 `DutyLeaseExpirationTask` 翻为 `EXPIRED` | 契约 §2.6 |

在岗状态口径（契约 §5.3）：`ONLINE`（`last_seen_time` 与 `last_active_time` 均在 5 分钟内）/ `IDLE`（`last_seen_time` 在 5 分钟内、`last_active_time` 超 5 分钟或为空）/ `OFFLINE`（`last_seen_time` 超 5 分钟或为空）。

在 `{值班中}` 条件下，`{执行者}` **必须**每 30 秒调用一次 `heartbeat`；**不得**只依赖业务调用维持在线；做不到的后果：被判 `OFFLINE`，任务被重派。

## 4.3 自动续租

除 `checkIn` / `checkOut` 外，**任一**工具调用都会把当前 ACTIVE 租约按原 TTL 窗口延长（契约 §2.6）。

- 在 `{执行长任务期间正常调用工具}` 条件下，`{执行者}` **无需**周期性重做 `checkIn`。
- 在 `{TTL 未耗尽}` 条件下，`{执行者}` **不得**反复 `checkIn`；重复 `checkIn` 会轮换 `leaseId`（第 01 章 §1.6）。
- 在 `{判断租约是否有效}` 条件下，**必须**以 `heartbeat` 返回的 `remainingTtlSeconds` 为判据，**不得**按 `ttlMinutes` 自行推算（契约 §8 TBD-2：实测 `checkIn` 传 `ttlMinutes=30` 时 `expiresAt` 为 +30 分钟，而 `heartbeat` 返回的窗口约 4 小时，二者不一致）。

## 4.4 租约过期处理

| 阶段 | 可观察判定动作 | 实测返回 | 执行者的动作 |
|---|---|---|---|
| 租约仍有效 | `heartbeat` 返回 `onDuty=true` 且 `remainingTtlSeconds>0` | `{"onDuty":true,"leaseId":"2097938864783138818","remainingTtlSeconds":"14399"}` | 继续执行 |
| 租约已失效 | `heartbeat` 返回 `onDuty=false`、`leaseId=null`、`remainingTtlSeconds="0"` | `{"id":1,"result":{"ok":true,"agentId":"...","serverTime":"...","onDuty":false,"leaseId":null,"leaseExpiresAt":null,"remainingTtlSeconds":"0"},"jsonrpc":"2.0"}` | 立即停止写入结果，重新 `checkIn` 后再恢复 |
| 已签退 | 查 `getAgentStatus` 的 `computedOnlineStatus` | `{"status":"ACTIVE","dbOnlineStatus":"ONLINE","computedOnlineStatus":"ONLINE"}` / 过期后为 `OFFLINE` | 未在岗时不领取新任务 |

在 `{租约失效}` 条件下，`{执行者}` **必须**停止执行并停止 `submitResult`，**不得**在租约失效后继续写入结果；恢复路径是重新 `checkIn`（第 01 章 §1.2）。

平台对无租约状态的门禁实测结果（重要）：

- 在 `{无 ACTIVE 租约}` 条件下，`pullTasks`、`claimSubTask`、`getDepsSummary` 实测**均正常返回**，**未**出现契约 §6 与 SKILL 所述的 500 `Agent 未在岗（无 ACTIVE 打卡租约）`：

```
checkOut 后 pullTasks: {"id":1,"result":{"messages":[{"messageId":"inbox-2097938816284401665","type":"sub_task.assigned",...}]},"jsonrpc":"2.0"}
checkOut 后 claimSubTask: {"id":1,"result":{"ok":true,"claimed":false,"reason":"invalid_status:DONE","assignedAgent":null,"subTaskId":null,"version":null},"jsonrpc":"2.0"}
checkOut 后 getDepsSummary: {"id":1,"result":{"subTaskId":"2097935069198065665","taskId":"2097934881767202817","depCount":0,"loadedCount":0,"truncatedCount":0,"degraded":false,"deps":[]},"jsonrpc":"2.0"}
```

- 因此在 `{无租约}` 条件下，`{执行者}` **不得**依赖平台报错来发现自己已离岗，**必须**用 `heartbeat` 自行判定 `onDuty`；该门禁的适用范围（是否仅对 MCP SSE 通道或特定工具生效）[待确认]（§4.7 TBD-04-1）。

## 4.5 心跳或续租失败的降级与恢复

- 在 `{单次 heartbeat 失败（超时/连接错误）}` 条件下，`{执行者}` **必须**在下一个 30 秒周期重试，**不得**跳过心跳继续执行长任务。
- 在 `{连续心跳失败达到约定阈值（建议 3 次）}` 条件下，**必须**：① 停止领取新任务；② 停止对在执行的子任务写入结果；③ 保留原始响应证据（报错原文、命令、时间点、已重试次数）；④ 用 `reportBlocked` 上报（需先恢复租约；若无法恢复，则通过人工渠道上报并停止本机轮询）。
- 在 `{恢复连接后}` 条件下，**必须**先用 `heartbeat` 或 `getAgentStatus` 判定租约是否仍 ACTIVE，再决定继续执行还是重新 `checkIn`（契约 §2.6）。
- 在 `{租约已过期且正在执行子任务}` 条件下，**必须**停止写入结果并上报；**不得**以“结果已生成”为由强行提交。

## 4.6 幂等约束

- 在 `{重复发送同一 heartbeat}` 条件下，平台**不**签发新租约：实测连续两次 `heartbeat` 返回同一 `leaseId`（`2097937944812888066`），仅 `leaseExpiresAt` 前移（续期），**不**产生额外租约或重复副作用。
- 在 `{重复续租}` 条件下，**不得**产生额外租约记录；租约数量由 DB 唯一索引 `uk_duty_lease_agent_active` 约束为至多一条 ACTIVE（契约 §7）。
- 换 TTL 或工作模式**必须**走 `checkOut` → `checkIn`（第 01 章 §1.3.1），**不得**靠重复 `heartbeat` 实现。

## 4.7 待确认清单（本章新增）

| 编号 | 条目 | 下游义务 |
|---|---|---|
| TBD-04-1 | 无 ACTIVE 租约时 500 `Agent 未在岗` 门禁的适用范围（实测 REST 别名通道下 pullTasks/claimSubTask/getDepsSummary 均未触发） | **不得**声称平台会在离岗时报错；**必须**用 `heartbeat.onDuty` 自行判定 |
| TBD-04-2 | `heartbeat` 续租窗口口径与 `checkIn` 的 `ttlMinutes` 不一致（契约 §8 TBD-2） | **必须**以 `remainingTtlSeconds` 为判据，**不得**推算 |
| TBD-04-3 | 心跳间隔/阈值是否可按 Agent 配置 | **不得**引用契约外配置项；**必须**按 30 秒 / 5 分钟执行 |