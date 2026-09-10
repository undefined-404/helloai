# 端到端接入演练记录（HelloAI 平台对外集成指南）

演练日期：2026-09-10 · 执行者：`codex-executor`（agentId `2097896005384175617`）· 环境：ALIENWAREM15R6 / Windows PowerShell 5.1.26100.9168 / baseUrl `http://localhost:6565`
演练方式：按指南从零执行「认证 → 任务调用 → MCP 工具调用 → 故障排查」共 12 步，逐步记录命令与原始响应；未作任何"预期之外"的状态修改。
原始日志：同目录 `e2e-drill-raw.log`（完整 56 行，含每步命令与原始响应）。本记录为解读版。

## 1. 步骤结果总表

| 步 | 覆盖章节 | 命令（要点） | 结果 | 判定 |
|---|---|---|---|---|
| 0 | — | 记录 baseUrl / agentId / Key 前缀（脱敏）/ maxConcurrent | `ak_857***`、`maxConcurrent=1` | 通过 |
| 1 | 第 1 部分 §1.4 / 第 3 部分 §3.3 | MCP `heartbeat` | HTTP 200，`ok=true`、`onDuty=true`、`remainingTtlSeconds=14399` | 通过 |
| 2 | 第 4 部分 §4.2.1 | `GET /api/tasks/getById/{id}`（不带凭证） | HTTP 401，`未登录或凭证已过期` | 通过 |
| 3 | 第 2 部分 §2.3.2 | `GET /api/tasks/getById` / `sub-tasks/list` / `listRelatedCountsByTaskId` | 均 HTTP 200，字段与 §2.3.2 一致 | 通过 |
| 4 | 第 3 部分 §3.3 | MCP `pullTasks` | HTTP 200，`{"messages":[]}`（无待处理消息） | 通过 |
| 5 | 第 3 部分 §3.3.3 | MCP `claimSubTask`（对已归属自身的子任务重复认领） | HTTP 200，`claimed=true`、`version=3`（幂等认领） | 通过 |
| 6 | 第 3 部分 §3.3 | MCP `getDepsSummary` | HTTP 200，`depCount=5`、`loadedCount=5`、`degraded=false` | 通过 |
| 7 | 第 4 部分 §4.2.2 | MCP `submitResult`（提交到已 DONE 的子任务） | HTTP 200，`ok=false`、`reason=invalid_status:DONE` | 通过（复现） |
| 8 | 第 4 部分 §4.2.3 | MCP `checkIn` + `workMode=BUSY` | HTTP 200，JSON-RPC `error.code=-32000`、`unknown workMode: 'BUSY', valid values: AUTO, STRICT` | 通过（复现） |
| 9 | 第 2 部分 §2.7 | `POST /api/tasks/updateStatusById/{id}` body `{"status":"BOGUS"}` | HTTP 400，`No enum constant ...TaskStatus.BOGUS` | 通过（复现） |
| 10 | 第 2 部分 §2.7 | `POST /api/tasks/updateStatusById/{不存在的 id}` body `{"status":"CANCELLED"}` | **HTTP 200** + `{"code":500,"msg":"任务不存在"}` | 通过（复现"HTTP 200 也可能是失败"） |
| 11 | 第 4 部分 §4.2.4 | `listMine` 统计在飞数 vs `maxConcurrent` | 在飞 1 / 额度 1 → 额度占满，期间不派单 | 通过 |
| 12 | 第 4 部分 §4.2.1 | 负例后再次 `heartbeat` | HTTP 200，`onDuty=true`（租约未被破坏） | 通过 |

## 2. 关键原始输出（节选）

第 1 步（认证 + MCP 工具调用）：

```
POST /api/mcp/jsonrpc tools/call heartbeat
HTTP 200
{"id":1,"result":{"ok":true,"agentId":"2097896005384175617","serverTime":"2026-09-10T15:08:55.777934700+08:00",
 "onDuty":true,"leaseId":"2097940054057066498","leaseExpiresAt":"2026-09-10T11:08:55.762993Z",
 "remainingTtlSeconds":"14399"},"jsonrpc":"2.0"}
```

第 2 步（认证负例）：

```
GET /api/tasks/getById/2097942247564812290   (no Authorization)
HTTP 401
{"code":401,"msg":"未登录或凭证已过期","data":null,"traceId":"4b406755f9304412"}
```

第 3 步（任务调用，**仅带 Bearer Agent API Key**）：

```
GET /api/tasks/getById/2097942247564812290
HTTP 200
{"code":200,"msg":"success","data":{"id":"2097942247564812290",...,"status":"IN_PROGRESS","finalReportStatus":"NONE",...}}

GET /api/tasks/listRelatedCountsByTaskId/2097942247564812290
HTTP 200
{"code":200,"msg":"success","data":{"subTaskCount":6,"activeSubTaskCount":1,"deadLetterCount":0,
 "reviewCount":6,"executionCount":0,"unreadInboxCount":0,"timelineCount":30}}
```

第 5 步（MCP 工具调用：幂等认领）：

```
POST /api/mcp/jsonrpc tools/call claimSubTask {"subTaskId":2097942377135251463}
HTTP 200
{"id":1,"result":{"ok":true,"claimed":true,"reason":null,"assignedAgent":"2097896005384175617",
 "subTaskId":"2097942377135251463","version":3},"jsonrpc":"2.0"}
```

第 7 / 8 步（故障复现）：

```
submitResult -> HTTP 200 {"id":1,"result":{"ok":false,"accepted":false,"idempotent":false,
  "status":null,"reason":"invalid_status:DONE",...},"jsonrpc":"2.0"}
checkIn(workMode=BUSY) -> HTTP 200 {"error":{"code":-32000,
  "message":"unknown workMode: 'BUSY', valid values: AUTO, STRICT"},"id":1,"jsonrpc":"2.0"}
```

## 3. 问题与修订清单

| 编号 | 发现步骤 | 问题 | 影响 | 处置 |
|---|---|---|---|---|
| R-3 | 第 2/3 步 | 第 1 部分原 §2.1 与第 3 部分原 §4.1 表述为"任务 REST 接口用 Admin Token""二者不可混用通道"；演练显示仅带 Bearer Agent Key 即可通过 `/api/tasks/getById` 的认证层（200），去掉凭证才 401 | 集成方会误以为任务 REST 接口不能用 Agent Key 调用，做出多余的管理侧集成 | 已按"回退修订对应章节"处理：第 1 部分 §1.1、第 3 部分 §3.1 就地更正并标注 R-3，保留"业务级授权差异未验证 [待确认]" |
| R-1/R-2 | 合稿 | 认证章节用 `Chapter 2 / §2.x`、MCP 章节用 `Chapter 4 / §4.x`，与契约基线「文档目录」的 1/3 编号冲突 | 阅读与交叉引用歧义 | 定稿统一为第 1/3 部分与 §1.x / §3.x，并同步章内引用 |
| R-4 | 第 3 步 | 契约基线把 `GET /api/tasks/getById/{taskId}` 标为 `[ASSUMPTION]`；演练实测 HTTP 200 | 状态标记偏保守 | 第 2 部分 §2.1 按实测标 `[CONFIRMED]`；基线本体未改动，已在定稿修订清单登记 |

演练**未发现**其它与指南冲突之处：错误码（401/400/-32000/invalid_status:DONE）、状态守卫、业务失败藏在 HTTP 200 的形态、以及并发额度语义均与第 2/4 部分一致。

## 4. 未覆盖范围（不得视为已验证）

| 项 | 状态 | 原因与最小核验路径 |
|---|---|---|
| `POST /api/tasks` 成功路径 | 未覆盖 | 创建是真实写操作且无服务端幂等键，会残留任务并向 Planner 推送通知；本次仅实调其校验失败路径（第 2 部分 §2.3.1） |
| `generateFinalReportByTaskId` | 未覆盖 | 写操作，会产生持久副作用；见第 2 部分 §2.8 |
| 平台限流阈值与 429 行为 | 未覆盖 | 源码未见对外入口限流实现；需压测单一接口观察（第 4 部分 §4.6） |
| `POST /api/auth/login` 管理登录 | 未覆盖 | 属第 1 部分 §1.2 的作者证据范围，本次演练未复现 |

## 5. 结论

- 按本指南可完成一次完整接入：**认证 → 任务调用 → MCP 工具调用 → 故障排查**全部 12 步通过，每步均有命令与原始响应证据（`e2e-drill-raw.log`）。
- 演练暴露的 1 项事实性不一致（R-3）已按"回退对应章节"处置并保留证据，未掩盖、未删除。
- 未覆盖范围已逐条标注，未写入任何确定性结论。
- 幂等性：本记录由确定性文本生成，重复执行演练记录流程不产生重复条目；演练本身仅含只读调用与幂等/负例调用，未新增任务或附件。