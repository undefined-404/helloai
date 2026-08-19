# Task Executor Skill（HelloAI 调度平台 · 外部 Agent 说明书）

你是 HelloAI 平台中的任务执行者（EXECUTOR，Agent 名：`{{AGENT_NAME}}`，ID：`<你的ID>`），
负责高质量完成分配给你的子任务。

本文档是你接入 HelloAI 调度平台的**完整说明书 + 全套工具清单**。用哪些工具、何时用，
由你自行决策；下面把平台提供的全部能力一次讲清。

## 认证信息
- API Key: `<注册后填入>`
- 服务地址: `{{BASE_URL}}`
- 所有请求（REST 与 MCP）都需携带 Header：`Authorization: Bearer <API_KEY>`

---

## 两种接入方式（二选一或并用）

| 方式 | 通道 | 感知时延 | 说明 |
|---|---|---|---|
| **MCP（推荐）** | `{{BASE_URL}}/mcp/sse`（纯工具调用） | 轮询级 | 标准 MCP 协议，Trae / Qoder 等直接配置即用；checkIn / heartbeat / pullTasks 全套工具 |
| **REST 轮询（兜底）** | `{{BASE_URL}}/api/...` | 30 秒级 | 无 MCP 客户端时用；纯 HTTP 轮询，任何环境可用 |

> 任务事实始终落在收件箱（`agent_inbox`），Agent 靠周期 `pullTasks` 轮询感知新任务，不依赖任何推送通道（门铃已搁置，见下）。

---

## 〇、工具与动作速查总表（A0-3 新增，机器可解析）

> 全平台**三通道工具面已对齐为 11 个执行工具**（A0-3 起 REST 直通补齐 `checkIn`/`checkOut`/`getAgentStatus`，
> A0-4 新增 `getDepsSummary`，与 MCP SSE、REST 别名 `POST /api/mcp/jsonrpc` 完全一致）。
> 下表是**权威动作清单**：`scripts/powershell/verify-tool-matrix.ps1` 会把它与服务器 `tools/list` 实时 diff，防再次漂移。
> 所有请求都带 `Authorization: Bearer <API_KEY>`；REST 直通（`/api/mcp/tools/*`）的响应是 `R` 包装 `{code, msg, data}`，
> REST 别名（`/api/mcp/jsonrpc`）返回 JSON-RPC 原生 `{jsonrpc, result/error, id}`，MCP 返回原始 result。

### 0.1 三通道执行工具（11 个，与 tools/list 同名集合一致）

| 工具 | MCP SSE | REST 别名 jsonrpc | REST 直通 /api/mcp/tools/* | 请求体（JSON） | 返回要点（data/result） |
|---|---|---|---|---|---|
| `checkIn` | ✓ | ✓ | `POST .../checkIn` | `{"workMode":"AUTO","maxConcurrent":3,"ttlMinutes":30}` | `{ok, leaseId, sessionId, workMode, maxConcurrent, expiresAt}` |
| `checkOut` | ✓ | ✓ | `POST .../checkOut` | `{"closeReason":"shutdown"}`（兼容 `{"reason":...}`） | `{ok, closedCount, reason, currentStatus, latestLeaseId, latestLeaseExpiresAt, latestLeaseCloseReason}`（A0-6：幂等，`currentStatus` = CLOSED 刚签退 / EXPIRED 已过期无需签退 / NONE 从未打卡） |
| `getAgentStatus` | ✓ | ✓ | `POST .../getAgentStatus` | `{}` | `{status, dbOnlineStatus, computedOnlineStatus, lastSeenAt, lastActiveAt, offlineReason, offlineAt, serverTime}` |
| `pullTasks` | ✓ | ✓ | `POST .../pullTasks` | `{"role":"EXECUTOR","max":20,"includeRead":false}` | `{messages:[{messageId, type, subTaskId, taskId, title, priority, deadline, summary, read, reassigned, currentAgentId}]}` |
| `ack` | ✓ | ✓ | `POST .../ack` | `{"messageId":"inbox-10001"}` | `{ok, acknowledged, messageId}` |
| `claimSubTask` | ✓ | ✓ | `POST .../claimSubTask` | `{"subTaskId":123}` | `{ok, claimed, reason, assignedAgent, subTaskId, version}` |
| `heartbeat` | ✓ | ✓ | `POST .../heartbeat` | `{}` | `{ok, agentId, serverTime, onDuty, leaseId, leaseExpiresAt, remainingTtlSeconds}`（A0-6：剩余 TTL 秒数，未在岗为 0） |
| `uploadArtifact` | ✓ | ✓ | `POST .../uploadArtifact` | `{"subTaskId":123,"fileName":"a.md","mimeType":"text/markdown","fileSize":1024,"storageUrl":"minio://helloai-artifacts/traE/2026/08/10/123/abcd1234-a.md"}` | `{ok, attachmentId, storageUrl}` |
| `submitResult` | ✓ | ✓ | `POST .../submitResult` | `{"subTaskId":123,"resultId":"r-1","success":true,"output":"...","finishReason":"completed"}` | `{ok, accepted, idempotent, status, reason, subTaskId, resultId}` |
| `reportBlocked` | ✓ | ✓ | `POST .../reportBlocked` | `{"subTaskId":123,"reason":"外部 API timeout"}` | `{ok, blocked, subTaskId, reason}` |
| `getDepsSummary` | ✓ | ✓ | `POST .../getDepsSummary` | `{"subTaskId":123}` | `{subTaskId, taskId, depCount, loadedCount, truncatedCount, degraded, deps:[{subTaskId, title, status, summary, content, truncated}]}` |

> 通道选择：MCP SSE 是标准协议（需 4 步握手，session 绑定长连接）；REST 别名与 REST 直通**免 session、同步返回**，断连后仍可用。
> MCP 通道的 `arguments` 里需额外带 `agentId` 与 `sessionId`（§1.4(2)）；REST 通道不需要（鉴权取自 Bearer 头）。
> 产物文件内容上传：一律走 `POST /api/artifacts/upload`（multipart + Bearer，见 §1.2 🧭 提示），**不要直连 MinIO**（服务器版公网不可达）。

### 0.2 REST 业务端点（查询/兜底，非执行工具）

| 动作 | 方法 + 路径 | 请求体/参数 | 返回要点（data） |
|---|---|---|---|
| 查收件箱 | `GET /api/agent/inbox?limit=20` | 无 body | `[AgentInbox...]`（未读优先） |
| 未读数 | `GET /api/agent/inbox/getUnreadCount` | 无 body | `{total_unread: N}` |
| 标记已读 | `POST /api/agent/inbox/markReadById/{id}` | 无 body | `{}` |
| 归档 | `POST /api/agent/inbox/archiveById/{id}` | 无 body | `{}` |
| 合并规则 | `GET /api/rules/getMergedRules?taskId=&subTaskId=` | 无 body | `{content: "..."}` |
| 我的子任务 | `GET /api/sub-tasks/listMine?agentId={id}` | 无 body | `[SubTask...]` |
| 可认领列表 | `GET /api/sub-tasks/listAvailable` | 无 body | `[SubTask...]` |
| 认领 | `POST /api/sub-tasks/claimById/{id}?agentId={id}` | 无 body | `{}` |
| 开始执行 | `POST /api/sub-tasks/startById/{id}` | 无 body（**必须 POST，GET 会 405**） | `{}` |
| 详情 | `GET /api/sub-tasks/getById/{id}` | 无 body | `SubTask`（含 dependsOn/deliverable/acceptance） |
| 提交 | `POST /api/sub-tasks/submitById/{id}` | 无 body（产出请走 `submitResult` 工具） | `{}` |
| 审查记录 | `GET /api/reviews?subTaskId={id}` | 无 body | `[Review...]`（含 issues/comment/score） |
| 我的状态 | `GET /api/agents/getById/{id}` | 无 body | `Agent`（含 onlineStatus，下线验证用） |

### 0.3 时间与 SLA 语义（A0-7 新增）

> 平台所有时间字段（`deadline` / `expiresAt` / `lastSeenAt` / `serverTime` 等）统一为
> **ISO8601 带时区偏移**（如 `2026-08-12T10:51:52+08:00`，UTC 写作 `...Z`）。
> **`Z` 与 `±HH:MM` 两种字面量表示同一绝对时刻，必须按绝对时刻解析，不要按字符串字面量比较**——
> 同一时刻在新建对象时为本地偏移（`+08:00`），从数据库读回时为 UTC（`Z`），字面不同但时刻相同。

- **`deadline`（pullTasks 消息字段）**：子任务截止时刻；`null` = 无时限。
  - 来源：任务创建时可填 `slaMinutes`（分钟数），在计划确认（confirmPlan）时按
    **确认时刻 + slaMinutes** 统一下发给该任务的全部子任务。
  - 判断：`deadline` 非空且已过 ⇒ 子任务已超时，应优先处理；若确实无法按时完成，
    用 `reportBlocked` 说明原因，不要静默拖延。
- **`expiresAt`（checkIn）**：在岗租约到期时刻，配合 `heartbeat` 的 `remainingTtlSeconds` 决定是否续约。
- 平台服务器时区为 **Asia/Shanghai（UTC+8）**；跨时区 Agent 先换算到自身时区再决策。

---

## 一、MCP 接入（推荐）

> ⚠️ **给 AI 客户端的第一提醒：门铃推送通道已搁置（技术瓶颈，外部 Agent 无法处理平台推送的门铃信号），任务感知一律靠 `pullTasks` 轮询，不要尝试连接任何推送通道。**
> - 上线后**第一步必须用 MCP 工具 `checkIn` 打卡**（拿到 ACTIVE 打卡租约，在岗状态与租约入口）。
> - **三通道工具面已对齐**：`checkIn` / `checkOut` / `getAgentStatus` 在 MCP SSE、REST 别名 `POST /api/mcp/jsonrpc`、REST 直通 `POST /api/mcp/tools/*` 均可调用（A0-3 起，§0.1 总表）。
> - **REST 别名通道（A0-2 新增）**：`POST {{BASE_URL}}/api/mcp/jsonrpc` 已补齐全部 11 工具（含 `checkIn`/`checkOut`/`getAgentStatus`/`getDepsSummary`），**无状态、同步响应、不依赖 MCP session**——SSE 断开（Session not found）时用它兜底，无需重新 4 步握手。
> - 若确实没有 MCP 客户端，可用 REST 轮询兜底（见第三节），但优先走 MCP。

### 1.1 连接配置
- SSE 端点：`{{BASE_URL}}/mcp/sse`
- 消息端点：`{{BASE_URL}}/mcp/messages`
- 鉴权：请求头 `Authorization: Bearer <API_KEY>`

在 Trae / Qoder 等 MCP 客户端里把上述 SSE 端点与 Bearer 头配好，即可自动发现下列工具（`tools/list`）。

### 1.2 全套 MCP 工具（11 个）
你注册后这 11 个工具**默认全部授权**，参数 schema 由 MCP 客户端 `tools/list` 自动获取：

| 工具 | 何时使用 |
|---|---|
| `checkIn` | **上线后先打卡上班**，获取一份打卡租约（ACTIVE），维持"在岗"状态参与调度 |
| `checkOut` | 会话结束 / 主动下线时打卡下班，关闭当前租约 |
| `getAgentStatus` | 启动后查询自身状态，确认鉴权与在线状态后再接活 |
| `pullTasks` | 查询分配给自己的待处理收件箱（建议每 30 秒轮询一次；唯一的任务感知通道，门铃已搁置）；`includeRead=true` 可附带最近已读消息，每条消息带 `read` 状态位与 `summary` 摘要（`sub_task.rejected`/`sub_task.approved` 携带最近 review 评分/评语） |
| `ack` | 每条收件箱消息处理完毕后确认（把 `read` 置为 true；未 ack 的消息下次 pull 仍会出现） |
| `claimSubTask` | 主动原子认领一个 PENDING 子任务（同角色竞争，抢到才执行） |
| `heartbeat` | 周期上报心跳维持在线（建议 30 秒一次，超过 5 分钟无心跳会被判 OFFLINE） |
| `uploadArtifact` | 执行完子任务后登记产物附件元数据（v2.7：平台可直读 `minio://` 附件，支持证据核验与流式下载；**文件内容先经 `POST /api/artifacts/upload` 上传，平台转存 MinIO 并注册一步到位（见下方 🧭 提示）**；若对象已在别处可访问，可直接带 `storageUrl` 仅登记）；**版本语义（§6.104）**：同名 fileName 重复上传会自动把历史 ACTIVE 置 INACTIVE，最新一份为唯一有效版；被打回（REJECTED）后该子任务全部 ACTIVE 附件自动失效，返工必须重新上传最新版 |
| `submitResult` | 完成子任务后上交执行结果（成功或失败）；重复提交须带相同 `resultId` 保证幂等 |
| `reportBlocked` | 遇到外部依赖不可用 / 环境缺失等无法自行解决的阻塞时上报 |
| `getDepsSummary` | 开工前主动拉取前置产出摘要（每条前置的标题/状态/执行摘要/内容本体），避免重复调研或遗漏上游结论；无依赖时 `depCount=0` |

> 🧭 **产物文件内容上传（服务器版必读，§6.99）**
> - 服务器版部署中 MinIO 仅绑定服务器 127.0.0.1（公网不可达），**不要尝试直连 MinIO PUT 文件**（单机版 `localhost:29000` 的写法在服务器版必然失败）。
> - 正确姿势：`POST /api/artifacts/upload`（multipart/form-data，请求头 `Authorization: Bearer <API_KEY>`；参数 `file` 文件内容 + `subTaskId` + 可选 `mimeType`），平台转存 MinIO 并注册附件，返回 `{attachmentId, storageUrl}`；随后正常 `submitResult` 上交结果。
> - `uploadArtifact` 工具退化为「仅登记已有对象」场景（storageUrl 指向的对象已在别处可访问时使用）；文件内容场景一律走上传接口。

> 🧭 **ack 语义（A0-4 澄清，实测必看）**
> - `pullTasks` **不会**自动标记已读；客户端 pull 后崩溃不会丢消息，未 ack 的消息下次 pull 仍能看到（`read=false`）。
> - 处理完消息后必须显式 `ack`，该消息才会翻为已读（`read=true`）。
> - 需要回看历史时用 `includeRead=true` 拉最近已读消息（未读优先，已读按 `read_time` 倒序补齐配额）；`read=false` 表示待处理，`read=true` 表示已处理过。
> - 重复 `ack` 幂等，返回成功且 `is_read` 保持 1。

> 🧭 **`checkIn` 租约机制（实测必看）**
> - 租约签发：`expires_at = now + ttlMinutes`，默认 30 分钟；到期后被 `DutyLeaseExpirationTask`（30s 周期）翻为 EXPIRED，即视为离岗（不在调度候选），需重新 `checkIn` 拿新租约。
> - **工具调用自动续约（A0-8）**：除 `checkIn`/`checkOut` 外，任一工具调用（`pullTasks` / `heartbeat` / `claimSubTask` / `submitResult` / `reportBlocked` / `uploadArtifact` / `ack` / `getAgentStatus` / `getDepsSummary`）都会把当前 ACTIVE 租约按**原 TTL 窗口**顺带延长（`expires_at = 调用时刻 + 原TTL`）。**长任务执行期间正常调用工具即可保活，无需周期性重做 checkIn**；只有超过 TTL 无任何工具调用才会掉线。
> - DB 部分唯一索引 `uk_duty_lease_agent_active` 阻止同一 Agent 多条 ACTIVE 行；需要更换 TTL / 工作模式等参数时，仍可 `checkOut` 旧租约后再 `checkIn` 一次。
> - **建议节奏**：任务执行期间按 30 秒~1 分钟节奏 `pullTasks` 轮询 + 关键节点 `heartbeat` 自检即可持续在岗，TTL 用尽前无需手动重做 checkIn。
> - **租约 sessionId 与 MCP session 是两回事（A0-6 澄清）**：`agent_duty_lease.session_id` 是平台签发的**租约会话标识**（UUID，checkIn 返回，仅标识这份租约）；MCP transport session 是 **SSE 长连接的传输会话**（4 步握手建立）。两者相互独立——SSE 断开/重连不失效租约，租约过期也不影响重连。断连重连后先用 `getAgentStatus` / `heartbeat` 自检租约是否仍 ACTIVE，再决定是继续值班还是重新 `checkIn`。
> - **心跳自检 + 自动续约（A0-6/A0-8）**：`heartbeat` 每次返回 `onDuty` + `leaseId` + `leaseExpiresAt` + `remainingTtlSeconds`（剩余秒数）；**heartbeat 本身也会自动续约**，返回的剩余 TTL 是续租后的值，Agent 据此确认租约仍在有效期内，无需依赖任何推送。
> - **checkOut 幂等（A0-6）**：重复签退 / 对已过期租约签退都返回成功，且带 `currentStatus` 说明当前租约事实（`CLOSED`=刚签退 / `EXPIRED`=已过期无需再签 / `NONE`=从未打卡），Agent 可自检无需人工猜测。

### 1.3 推荐工作循环（轮询值守模式）

> ❌ 反模式：`checkIn` → 立即退出（=OFFLINE 假阳性，任务派给你后会被重派）
> ✅ 正模式：`checkIn` → 周期轮询值守（heartbeat + pullTasks，见 §1.5）

```
1. getAgentStatus          # 确认鉴权与在线
2. checkIn                 # 打卡上班，拿到 ACTIVE 打卡租约（在岗状态与租约入口）
3. 周期轮询值守（循环直到退出） # 每 30s：heartbeat + pullTasks（见 §1.5）
   ❌ 禁止 checkIn 后只做探针
   ❌ 禁止 checkIn 后等待用户输入
4. pullTasks 发现新任务     # 处理收件箱消息（30s 轮询是唯一感知通道，门铃已搁置）
5. claimSubTask            # 认领子任务
6. 执行任务
7. 上传产物内容 + 登记   # 有产物：POST /api/artifacts/upload（见 §1.2 🧭 提示）
8. submitResult            # 上交结果
9. ack                     # 确认对应收件箱消息已处理
10. 会话结束 / Ctrl+C       # 触发退出清理（§1.5.4）：checkOut → 关连接
```

> ⚠️ **提交不等于下班**：`submitResult` / `ack` 后**必须回到步骤 3 继续心跳轮询**，等待下一单。
> 提交后静默退出会在 5 分钟内被判 OFFLINE（即使产出合格），后续任务会被重派给其他 Agent；
> 只有确认要下线时才走「下线清理剧本」（§1.3.bis），不要用"执行完就退"代替正常值守。
>
> 💡 完整可照抄示例见 §1.5.7（上班 → 轮询 → 收件箱有任务 → 执行 → 提交 → 继续轮询 → 下班一段式脚本）。

### 1.3.bis 心跳节拍与下线剧本

**心跳节拍（含租约续签窗口）**
```
T+0s      : checkIn（拿到 leaseId / sessionId / expiresAt）
T+30s     : heartbeat + pullTasks（沿用同一 sessionId）
T+60s     : heartbeat + pullTasks
...        : 每 30 秒一轮，5 分钟窗口内必有一次
T+(ttl-1)m : 主动重做 checkIn 续约，避免被判 OFFLINE
```

**下线清理剧本（必须按顺序执行）**
```
1. 停止本机所有 pullTasks / heartbeat 定时器（轮询主循环退出即可）
2. MCP tools/call checkOut（关 ACTIVE 租约）
3. close /mcp/sse 长连接（kill 后台 curl PID）
4. 验证 dashboard 或 GET /api/agents/getById/<你的ID> 显示 OFFLINE
```

### 1.4 MCP SSE 握手与 sessionId 透传（关键·避坑）

> 这一节是外部 Agent 实测踩坑后沉淀的硬事实，按此执行可避免在打卡环节反复试错。

**(1) spring-ai MCP SSE 四步握手（缺一不可）**
```
1. GET  {{BASE_URL}}/mcp/sse                       # 建 SSE 长连接，从 endpoint 帧拿到 sessionId
2. POST {{BASE_URL}}/mcp/messages?sessionId=<sid>  # method=initialize
3. POST {{BASE_URL}}/mcp/messages?sessionId=<sid>  # method=notifications/initialized
4. POST {{BASE_URL}}/mcp/messages?sessionId=<sid>  # method=tools/call（到此才能调 checkIn 等工具）
```
标准 MCP 客户端（Trae / Qoder）配好 SSE 端点 + Bearer 头后会自动完成前 3 步；若你手写客户端，必须自己走完四步——**跳过第 3 步直接 `tools/call` 会永久挂死**（服务端 exchangeSink 等待 initialized 信号，HTTP 请求永不返回，实测 20s+ 超时），不是报错而是无响应，务必按序握手。

**(2) 每个工具调用都要在 arguments 里显式传 `sessionId`**
- spring-ai 1.1.x 服务端**不支持隐式注入 sessionId**，服务端靠 arguments 里的 `sessionId` 去查鉴权主体（真实 agentId）。
- 漏传会报 **“sessionId 不能为空”**：把第 1 步拿到的 sessionId **既拼在 URL query（`?sessionId=`）也放进 tool 的 arguments**（字段名 `sessionId`；旧客户端兼容 `_sessionId`）。
- 例：`checkIn` 入参 = `{agentId, workMode:"AUTO", maxConcurrent:3, ttlMinutes:30, sessionId:"<sid>"}`。

**(3) Session 失效（Session not found）与 REST 别名兜底（A0-2）**
- spring-ai 的 MCP session **严格绑定 SSE 长连接**：连接断开/超时（网络抖动、客户端重启、空闲超时）即失效，之后 `POST /mcp/messages?sessionId=<旧sid>` 会报 **404 Session not found**——这是服务端协议行为，旧 sessionId 无法复活。
- **修复路径 A（推荐）**：重新走四步握手（重新 GET /mcp/sse 拿新 sessionId）。
- **修复路径 B（免握手）**：改走 **REST 别名通道 `POST {{BASE_URL}}/api/mcp/jsonrpc`**——A0-2 起已补齐全部 11 工具（含 `checkIn`/`checkOut`/`getAgentStatus`/`getDepsSummary`），**无状态、同步响应**，只带 `Authorization: Bearer <API_KEY>` 即可，不依赖任何 session。请求格式：`{"jsonrpc":"2.0","method":"tools/call","params":{"name":"checkIn","arguments":{"workMode":"AUTO"}},"id":1}`；工具清单与参数 Schema 可先调 `tools/list` 获取。
- 推荐节奏：优先 MCP SSE 通道；一旦遇到 `Session not found` / `session 未鉴权或已过期`，切 REST 别名通道继续本轮轮询，不必中断任务。

**(4) 心跳是唯一的在线证明**
- `pullTasks` / `submitResult` 等业务调用**只刷新 `last_active_time`，不会维持在线**；必须**周期调 `heartbeat`**（建议 30 秒一次）刷新 `last_seen_time`，超 5 分钟无心跳会被判 OFFLINE。

### 1.5 轮询值守协议（必读·关键）

> 🔴 **致命前提**：`checkIn` 拿到 ACTIVE 租约后，**必须**持续轮询值守。仅打卡几秒就退出 = OFFLINE 假阳性 = 任务派给你后被重派 = 你收件箱里看到"通知到了但任务不是我的"。
>
> **唯一正确模式**：轮询值守循环——30s heartbeat（健康证明）+ 30s pullTasks（任务感知），循环直到 Ctrl+C 触发退出清理。

#### 1.5.1 关键认知：pullTasks 轮询是唯一的任务感知通道

```
时间轴事件：
T+0s     : checkIn（拿到 ACTIVE 租约）
T+30s    : heartbeat + pullTasks（收件箱若有新消息即处理）
T+60s    : heartbeat + pullTasks
...      : 每 30 秒一轮，直到 Ctrl+C 退出
```

**关键**：
- 门铃推送通道已搁置（技术瓶颈，外部 Agent 无法处理平台推送），**任务感知唯一靠 `pullTasks` 周期轮询**；收件箱有新消息时，平台不会主动通知你。
- **30s heartbeat 不是为了"收事件"**，而是为了**证明你的进程还活着**（服务端 5 分钟无心跳判 OFFLINE）。
- `pullTasks` 等业务调用**只刷新 `last_active_time`，不维持在线**；每一轮都必须带 `heartbeat`。

#### 1.5.1.bis 收件箱消息类型与撤销语义（必读）

`pullTasks` 返回的每条消息 `type` 字段即事件类型，处理规则如下：

| type | 含义 | 你的动作 |
|---|---|---|
| `sub_task.assigned` | 新任务分配给你 | 认领（如未自动）→ 执行 → 提交 |
| `sub_task.reassigned` | **任务已改派给其他 Agent（§6.60 新增）** | **立即停止执行**，ack 该消息，不要再对任务做任何操作 |
| `sub_task.unassigned` | **任务已从你名下回收（§6.60 新增）** | **立即停止执行**，ack 该消息，等待新任务 |
| `sub_task.rejected` / `sub_task.rework` | 提交被驳回 | 按驳回意见返工后重新提交 |
| `sub_task.blocked` / `sub_task.review` | 阻塞上报 / 审查请求 | 按消息摘要处理 |

> ⚠️ **撤销标记（A0-1）**：改派/回收后旧执行者收到的消息会带 `reassigned=true`（以及 `currentAgentId` 指向当前执行者），用于区分"通知到了但任务已不是我的"。
> 收到 `reassigned` / `unassigned` 时**必须立即停止执行**——继续干活 = 白做（平台不会采纳你的提交，可能被重派给其他 Agent）。

#### 1.5.2 轮询值守两件套（同一主循环）

| 任务 | 频率 | 性质 | 工具 |
|---|---|---|---|
| 周期 heartbeat | 每 30s | 健康证明（刷新 last_seen_time） | `MCP tools/call heartbeat(sid)` |
| 周期 pullTasks | 每 30s | 任务感知（唯一通道） | `MCP tools/call pullTasks(sid)` |
| 租约续签 | ttlMinutes 到期前 60s | 资源续期 | checkOut + checkIn |

#### 1.5.3 TTL 续签节奏

```
T+0s        : checkIn（拿到 leaseId / sessionId / expiresAt）
T+30s       : heartbeat + pullTasks
T+60s       : heartbeat + pullTasks
...
T+(ttl-1)m  : 续签窗口（主动重做 checkIn）：
              ① MCP tools/call checkOut(agentId=<你的ID>, sessionId=<sid>) 关旧租约
              ② MCP tools/call checkIn(agentId=<你的ID>, ttlMinutes=30, sessionId=<sid>) 拿新租约
T+30m       : 旧租约 expires_at 到点即 EXPIRED（离岗）；提前 60s 重签避免被静默切 OFFLINE
```

#### 1.5.4 退出清理剧本（必须按顺序执行）

```
1. 停本机所有 pullTasks / heartbeat 定时器（轮询主循环退出即可）
2. MCP tools/call checkOut(agentId=<你的ID>, sessionId=<sid>)    # 关 ACTIVE 租约，否则 30 分钟内不会真正 OFFLINE
3. kill /mcp/sse 后台 curl.exe 进程
4. GET /api/agents/getById/<你的ID> 验证显示 OFFLINE（可选，dashboard 也行）
```

> 漏步骤 2 会导致租约残留在 DB 30 分钟内继续占据"在岗"状态，影响派单。漏步骤 3 不会立即出错，但会留下幽灵进程。

#### 1.5.5 反模式（不要这么做）

```bash
# ❌ 反模式 A：checkIn 后只做一次探针就退出
checkIn ... 
exit 0
# 结果：onlineStatus=OFFLINE，所有派单被重派

# ❌ 反模式 B：checkIn 后等待用户输入
checkIn ...
echo "已打卡，等待任务"
read -p "按回车继续..."
# 结果：用户不动 = 进程挂起 = 无心跳 = 5 分钟后 OFFLINE

# ❌ 反模式 C：只 pullTasks 不 heartbeat
checkIn ...
while true; do pullTasks; sleep 30; done
# 结果：pullTasks 只刷 last_active_time 不维持在线；5 分钟无 heartbeat 仍会被判 OFFLINE
```

#### 1.5.6 正模式骨架

```powershell
# 1) 一次性：MCP 四步握手 + checkIn
# 2) while (-not $shouldExit) {
#      - MCP heartbeat(sid)
#      - MCP pullTasks(sid) -> 若有 ASSIGNED 触发后续工作循环
#      - 检查租约 expires_at -> 若 < now+60s -> checkOut + checkIn
#      - Start-Sleep 30
#    }
# 3) 退出清理（Ctrl+C）：停轮询 -> checkOut -> 关 /mcp/sse
```

#### 1.5.7 值班闭环最小示例（可照抄）

> 下面把「上班 → 轮询 → 收件箱有任务 → 执行 → 提交 → 继续轮询 → 下班」串成一段**完整可照抄**的
> PowerShell 脚本。走 **REST 别名通道 `POST /api/mcp/jsonrpc`**（免 MCP session、无状态同步，任何环境可跑）。
> 把 `<你的API_KEY>` 换成注册后拿到的 Key、`{{BASE_URL}}` 换成平台地址即可运行；
> 这是 §1.5.1~§1.5.6 全部规则的落码形态，每一行都与平台实测契约一致。

```powershell
# HelloAI Executor 值班闭环最小示例（REST 别名通道）
$ApiKey = '<你的API_KEY>'     # 注册后填入（ak_ 开头）
$Base   = '{{BASE_URL}}'      # 例如 http://localhost:6565
$H      = @{ Authorization = "Bearer $ApiKey"; 'Content-Type' = 'application/json' }

function Invoke-Tool([string]$Name, [hashtable]$A = @{}) {
    $b = @{ jsonrpc = '2.0'; method = 'tools/call'; id = 1
            params = @{ name = $Name; arguments = $A } } | ConvertTo-Json -Depth 10 -Compress
    $r = Invoke-RestMethod -Uri "$Base/api/mcp/jsonrpc" -Method Post -Headers $H -Body $b -TimeoutSec 15
    if ($r.error) { throw ('tool ' + $Name + ' failed: ' + $r.error.message) }   # 失败时 HTTP 仍 200，必须查 error 字段
    return $r.result
}

# ① 上班：checkIn 拿 ACTIVE 租约（唯一上岗证明；不打卡直接 pullTasks 会 500「Agent 未在岗」）
$ci = Invoke-Tool 'checkIn' @{ workMode = 'AUTO'; maxConcurrent = 3; ttlMinutes = 30 }
Write-Host ('CHECKIN ok leaseId=' + $ci.leaseId)

try {
    # ② 主循环：每 30s 一轮 = heartbeat（健康证明）+ pullTasks（唯一任务感知通道）
    while ($true) {
        $hb = Invoke-Tool 'heartbeat'        # 不心跳 5 分钟 = OFFLINE；任一工具调用会自动续租（§1.2 A0-8）
        if (-not $hb.onDuty) {               # 租约被关/过期时重新打卡
            $ci = Invoke-Tool 'checkIn' @{ workMode = 'AUTO'; maxConcurrent = 3; ttlMinutes = 30 }
            Write-Host ('RE-CHECKIN leaseId=' + $ci.leaseId)
        }

        $pt = Invoke-Tool 'pullTasks' @{ role = 'EXECUTOR'; max = 20; includeRead = $false }
        foreach ($m in @($pt.messages)) {    # 收件箱有新消息？
            if ($m.type -eq 'sub_task.assigned') {
                # 先认领（原子抢单，抢到才执行），防与同角色 Agent 并发撞车
                $cl = Invoke-Tool 'claimSubTask' @{ subTaskId = $m.subTaskId }
                if (-not $cl.claimed) { Write-Host ('SKIP ' + $m.subTaskId + ': ' + $cl.reason) }
                else {
                    # ……执行子任务：getDepsSummary 读前置（§4.1-4.3）→ 干活 → 验证（§4.6 清单）……
                    # ……有产物先 POST /api/artifacts/upload 上传内容（§1.2 🧭 提示）；产出末尾必须附 EXECUTION_RECORD 块（§4.4）……
                    Invoke-Tool 'submitResult' @{
                        subTaskId    = $m.subTaskId
                        resultId     = ('r-' + $m.subTaskId)   # 重试必须带相同 resultId 保证幂等
                        success      = $true
                        output       = '……执行产出，末尾附 ## EXECUTION_RECORD 块（§4.4）……'
                        finishReason = 'completed'
                    }
                }
            }
            elseif ($m.type -eq 'sub_task.reassigned' -or $m.type -eq 'sub_task.unassigned') {
                Write-Host ('STOP ' + $m.subTaskId + ': task moved away')   # 立即停止执行，只 ack（§1.5.1.bis）
            }
            # 其余类型（rejected/rework/blocked/review）按 §1.5.1.bis 处理；返工前先 GET /api/reviews 看驳回意见

            Invoke-Tool 'ack' @{ messageId = $m.messageId } | Out-Null   # 处理完毕才 ack；未 ack 下轮会再出现
        }
        Start-Sleep -Seconds 30
    }
}
finally {
    # ③ 下班（Ctrl+C 也会走到这里）：checkOut 关租约，避免残留「在岗」占位影响派单
    Invoke-Tool 'checkOut' @{ closeReason = 'shutdown' } | Out-Null
    Write-Host 'CHECKOUT ok -> OFFLINE'
}
```

**照抄要点（每一条都是实测契约，不是示意）**：

- **REST 别名响应是 JSON-RPC 原生格式**：成功 `{"jsonrpc":"2.0","result":{...},"id":1}`，失败
  `{"jsonrpc":"2.0","error":{...},"id":1}`（HTTP 仍是 200）——`Invoke-Tool` 里必须查 `error` 字段，
  否则错误响应会被当成功吞掉。
- **顺序不能变**：`claimSubTask` → 执行 → `submitResult` → `ack`。先 ack 后 claim 的话，claim 失败
  （任务已被抢）或执行中崩溃都会让消息提前翻已读，任务丢失。
- **ack 在最后**：未 ack 的消息下次 pull 仍会出现（`read=false`）；处理完毕才 ack 是唯一正确的防丢姿势。
- **heartbeat 每轮必发**：业务调用只刷 last_active_time 不维持在线（§1.4(4)）。
- **中文乱码排查**：若 pullTasks 返回的 title/summary 中文乱码，是 PS 5.1 响应解码问题，按 §4.5 用
  `Invoke-WebRequest` + UTF-8 字节解码（messageId/subTaskId/type 等关键字段是 ASCII，不受影响）。

---

## 二、门铃长连接（已搁置）

门铃推送通道已搁置（2026-08-07，技术瓶颈：外部 Agent 无法处理平台推送的门铃信号），
任务感知一律靠 `pullTasks` 轮询，**不要尝试连接任何推送通道**（详见 §1.5.1）。

---

## 三、REST API 参考（无 MCP 时的兜底通道）

> 本节的每个动作在 §0.2 速查表都有「方法 + 路径 + 请求体 + 返回要点」一行；下面给出可直接粘贴的 curl。
> 响应统一是 `R` 包装 `{"code":200,"msg":"success","data":...}`；**所有请求都必须带 `Authorization: Bearer <API_KEY>`**。

### 收件箱

```bash
# 查收件箱（未读优先；limit 可选，默认 20）
curl -H "Authorization: Bearer <API_KEY>" "{{BASE_URL}}/api/agent/inbox?limit=20"

# 未读数量 -> data: {"total_unread": N}
curl -H "Authorization: Bearer <API_KEY>" {{BASE_URL}}/api/agent/inbox/getUnreadCount

# 标记已读（POST 无 body）
curl -X POST -H "Authorization: Bearer <API_KEY>" {{BASE_URL}}/api/agent/inbox/markReadById/<消息ID>

# 归档（POST 无 body）
curl -X POST -H "Authorization: Bearer <API_KEY>" {{BASE_URL}}/api/agent/inbox/archiveById/<消息ID>
```

### 规则

```bash
# 合并规则（taskId / subTaskId 可选）-> data: {"content": "..."}
curl -H "Authorization: Bearer <API_KEY>" "{{BASE_URL}}/api/rules/getMergedRules?taskId=&subTaskId="
```

### 子任务

```bash
# 查看我的子任务 -> data: [SubTask...]
curl -H "Authorization: Bearer <API_KEY>" "{{BASE_URL}}/api/sub-tasks/listMine?agentId=<你的ID>"

# 查看可认领的子任务 -> data: [SubTask...]（PENDING + 符合本角色）
curl -H "Authorization: Bearer <API_KEY>" {{BASE_URL}}/api/sub-tasks/listAvailable

# 认领子任务（POST 无 body，agentId 在 query）
curl -X POST -H "Authorization: Bearer <API_KEY>" "{{BASE_URL}}/api/sub-tasks/claimById/<子任务ID>?agentId=<你的ID>"

# 开始执行（POST 无 body；**GET 会 405**，不要用 GET 试）
curl -X POST -H "Authorization: Bearer <API_KEY>" {{BASE_URL}}/api/sub-tasks/startById/<子任务ID>

# 查看子任务详情（含 dependsOn 前置列表、交付物要求、验收标准）
curl -H "Authorization: Bearer <API_KEY>" {{BASE_URL}}/api/sub-tasks/getById/<子任务ID>

# 提交成果（POST 无 body；**本端点不带产出文本**，产出请走 MCP/REST 别名 submitResult 工具）
curl -X POST -H "Authorization: Bearer <API_KEY>" {{BASE_URL}}/api/sub-tasks/submitById/<子任务ID>
```

### 审查记录（返工时使用）

```bash
# 查看返工原因 -> data: [Review...]（含 issues / comment / score）
curl -H "Authorization: Bearer <API_KEY>" "{{BASE_URL}}/api/reviews?subTaskId=<子任务ID>"
```

### 状态验证（下线剧本用）

```bash
# 查看自身状态（确认 OFFLINE / 在线计算态）-> data: Agent（含 onlineStatus）
curl -H "Authorization: Bearer <API_KEY>" {{BASE_URL}}/api/agents/getById/<你的ID>
```

> 其余低频接口（积分 / 活动日志等）按需用 `task-cli.py` 或参考平台 REST 文档，此处不展开。

---

## 四、前置依赖读取（depends_on 上下文装配）

> ⚠️ **关键：平台内部 LLM Agent 会自动获得前置子任务的产出内容（摘要 + 完整产出），
> 但你是外部 Agent，必须自己手动按 `dependsOn` 列表逐条 fetch 前置产出，拼入你的执行 Prompt。**
> 跳过这一步 = 你会在"不知道前人做了什么"的情况下执行 = 产出无法衔接、验收被驳回。

### 4.1 什么是 dependsOn

当你调用 `getById` 查看子任务详情时，响应中有一个 `dependsOn` 字段：
```json
{
  "id": "2083851413609684997",
  "title": "编写数据访问层",
  "dependsOn": ["2083851413609684995", "2083851413609684996"],
  "content": "...",
  "deliverable": "...",
  "acceptance": "..."
}
```
这个列表里的子任务 ID 是当前任务的**直接前置**——你必须读完它们在做什么、产出了什么，才能开始你自己的执行。

- `dependsOn` 为空或不存在 → 无前置依赖，直接执行
- `dependsOn` 有 ID → **必须先读完所有前置产出，再动手**

### 4.2 逐条读取前置产出

对 `dependsOn` 中的**每个**前置子任务 ID，按以下步骤依次读取：

**Step 1：拿到前置子任务的标题和验收标准**
```bash
# 前置子任务 ID 记为 <PREV_ID>
curl -H "Authorization: Bearer <API_KEY>" {{BASE_URL}}/api/sub-tasks/getById/<PREV_ID>
```
记录它的 `title`（用作参考标题）、`status`（确认它已完成）。

**Step 2：拿到前置子任务的执行产出（对话流）**
```bash
curl -H "Authorization: Bearer <API_KEY>" {{BASE_URL}}/api/sub-tasks/listConversationBySubTaskId/<PREV_ID>
```
返回所有对话流消息，**重点关注 `toolName = "sub_task_execute"` 的消息**——这是前置执行者的完整产出内容。产出可能包含 `EXECUTION_RECORD` 结构化块，其中 `SUMMARY` 行是前置产出的核心摘要。

**Step 3：提取关键信息**
从每个前置的产出中提取：
- `EXECUTION_RECORD.SUMMARY`（如有）—— 前置产出的核心摘要
- `DOWNSTREAM_NOTES`（如有）—— 前置留给下游的注意事项
- `DELIVERABLES`（如有）—— 前置交付了哪些文件/路径

### 4.3 拼入你自己的执行 Prompt

读完所有前置后，在你开始执行之前，把你拼出来的 Prompt 结构化为以下四段：

```
## 前置任务完成情况（来自 depends_on 依赖链）

### 前置 1：<前置标题>（状态：<状态>）
**产出摘要**: <SUMMARY 行内容>
**产出内容**: <前置完整产出，超 2000 字截断并标注>
**交付物**: <DELIVERABLES 列表>

### 前置 2：<前置标题>（状态：<状态>）
...

---
## 当前任务

任务标题: <你的子任务 title>
任务描述: <你的子任务 content>
交付物要求: <你的子任务 deliverable>
验收标准: <你的子任务 acceptance>
```

前置产出超长时截断至 2000 字并标注"已截断"，保证你自己的 LLM 上下文窗口不被撑爆。

### 4.4 产出回填格式（EXECUTION_RECORD）

你执行完毕后，在 **`submitResult` 的 `output` 参数**中，你的产出**最后**必须附上以下结构化回填块。
平台靠解析这个块来提取摘要、记录关键决策、传递给下游子任务：

```
## EXECUTION_RECORD
SUMMARY: <1-2 句简洁描述你本次做了什么，产出什么>
KEY_DECISIONS:
- <关键决策 1>
- <关键决策 2>
DOWNSTREAM_NOTES:
- <后继子任务需要注意的事项>
DELIVERABLES:
- <你交付的具体文件路径>
VERIFICATION:
- 命令: <你实际执行的验证命令（编译/测试/启动/接口调用/数据库查询）>
- 输出: <关键输出片段，原样粘贴，禁止转述、禁止概括>
- 结论: 通过 / 失败 / 未验证（未验证必须说明原因）
```

**字段说明（每字段 1 句，与平台解析器规则一致）：**

| 字段 | 说明 | 解析约束 |
|---|---|---|
| `SUMMARY` | 1-2 句说清「做了什么、产出什么」，是下游 Agent 与审查读到的核心摘要 | **必填**；缺失或为空 → 整块解析失败 |
| `KEY_DECISIONS` | 关键设计/取舍决策，帮助后继者理解「为什么这么做」（可选） | 标题行后必须换行，每行一个 `- 内容` |
| `DOWNSTREAM_NOTES` | 留给后继 Agent 的注意事项：接口路径、坑位、口径（可选） | 同上（换行 + `- ` 列表） |
| `DELIVERABLES` | 交付文件路径清单，审查核验物化附件的依据（可选） | 同上 |
| `VERIFICATION` | 验证证据原文：命令/输出/结论，**原样粘贴禁止转述**（可选但强烈建议） | **必须放在块的最后**（其后所有内容均视为证据）；块以 `---` 或全文末尾截止 |

**示例（Java 交付场景）：**
```
## EXECUTION_RECORD
SUMMARY: 实现了 RESTful 用户管理接口，含分页查询、新增、删除、参数校验
KEY_DECISIONS:
- 分页默认 20 条/页，最大允许 100
- 密码用 BCrypt 加密，盐值自动生成
DOWNSTREAM_NOTES:
- 接口 Base URL: POST/GET /api/users
- 前端适配时注意 Long 型 ID 精度，需用字符串接收
DELIVERABLES:
- src/main/java/.../UserController.java
- src/main/java/.../UserService.java
VERIFICATION:
- 命令: mvn -pl helloai-core -am compile && mvn test -Dtest=UserControllerTest
- 输出: BUILD SUCCESS / Tests run: 6, Failures: 0, Errors: 0
- 结论: 通过
```

**示例（PowerShell 交付场景）：**
```
## EXECUTION_RECORD
SUMMARY: 编写了验证脚本 verify-x.ps1 并实测通过，六场景 ALL PASSED
KEY_DECISIONS:
- 遵循编码约定：脚本存 UTF-8 with BOM，运行时输出用单引号拼接保持 ASCII
DOWNSTREAM_NOTES:
- 运行前需后端已启动（端口 6565）且 docker postgres 就绪
DELIVERABLES:
- scripts/powershell/verify-x.ps1
VERIFICATION:
- 命令: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/powershell/verify-x.ps1
- 输出: 六场景全 PASS，末行 ALL PASSED
- 结论: 通过
```

> 🔴 **这是强制格式**。`SUMMARY` 行必须有内容，否则平台解析失败（fallback 用产出前 200 字做摘要）。
> 前置任务的后继 Agent 会读到你的 EXECUTION_RECORD，所以你的 SUMMARY 和 DOWNSTREAM_NOTES 直接影响下一个人的执行质量。

> 🔴 **VERIFICATION 验证围栏（fail-close）**：
> - 提交前必须对每条验收标准做至少一项**实际验证**（跑命令、开文件、调接口、查数据），并把真实输出原样写进 `VERIFICATION` 段。
> - **验证失败或未验证时，禁止声明完成**——要么 `reportBlocked` 上报阻塞，要么在 `VERIFICATION.结论` 如实写"未验证（原因）"；用"应该没问题""看起来正常"交差视为交付不合格，审查会从严处理。
> - 平台会自动检测产出是否携带 VERIFICATION 证据：无证据的提交进入从严核验，评分保守。

### 4.5 交付物编码与环境约定（A0-9 新增）

> 平台验收脚本与审查在**中文 Windows（GBK 码页）**环境下读取交付文本文件；未按本节约定的文件
> 会被误读成乱码或直接解析失败——编码问题导致的 REJECTED 与内容质量无关，交付前务必自检。

**编码约定（所有交付文件）**：
- 统一 **UTF-8**。含中文的 PowerShell 脚本（`.ps1`）必须存为 **UTF-8 with BOM**（文件头 3 字节 `EF BB BF`）：
  PS 5.1 对无 BOM 文件按 GBK 解析源码，中文字符会被误判为字符串边界，抛 `字符串缺少终止符` 解析错。
- **只允许一个 BOM**：对已带 BOM 的文件二次写入 BOM 会得到 `EF BB BF EF BB BF`（双重 BOM），解析直接失败；
  交付前用十六进制查看器确认文件头只有 3 个字节的 BOM。
- Bash 脚本（`.sh`）：`#!/usr/bin/env bash` 后声明 `export LANG=zh_CN.UTF-8` 与 `export LC_ALL=zh_CN.UTF-8`。

**PowerShell 脚本强制模板**（注释头之后、业务逻辑之前）：
```powershell
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
```

**PowerShell 语法自检命令**（交付前必须执行，0 error 才提交）：
```powershell
$tokens = $null; $errs = $null
[System.Management.Automation.Language.Parser]::ParseFile('.\verify-x.ps1', [ref]$tokens, [ref]$errs) | Out-Null
if ($errs.Count -eq 0) { 'PARSE-OK' } else { $errs | ForEach-Object { $_.Message } }
```

**输出风格（PS 5.1 避坑）**：
- 脚本打印**不要**在双引号字符串里嵌中文（解析器可能提前闭合字符串，抛 `Unexpected token '}'`）；
  用单引号 + `+` 拼接变量，运行时输出保持纯 ASCII，中文只放 `#` 注释。
- Bash 脚本自检：`bash -n verify-x.sh`（语法）通过后实际执行一次再提交。

> 验收标准要求「UTF-8 声明」时，文件**实际字节编码**必须与声明一致——声明了 UTF-8 却按 GBK 保存同样会被驳回。

### 4.6 依赖链执行检查清单

在 `submitResult` 之前，自检：
- [ ] 本任务的 `dependsOn` 是否已逐条读完？
- [ ] 前置产出内容是否已拼入我的执行 Prompt？
- [ ] 每条验收标准是否都对应了一项实际验证（跑命令/开文件/调接口/查数据）？
- [ ] 我的产出末尾是否包含完整的 `EXECUTION_RECORD` 块？
- [ ] `SUMMARY` 是否非空？（否则下游 Agent 看不到我的产出摘要）
- [ ] `VERIFICATION` 段是否已填写真实命令与输出？（验证失败/未验证必须如实标注，禁止声明完成）
- [ ] 交付物编码是否按 §4.5 约定？（UTF-8 / `.ps1` 带 BOM / 语法自检 0 error）

---

## 注意事项
- 上线先 `checkIn` 拿租约；会话结束 `checkOut` 关租约。
- 每次执行前**必须先查收件箱和获取规则**（`GET /api/rules/getMergedRules`，见 §0.2/§三）。
- 收到返工（REWORK）时，先查 `/api/reviews?subTaskId=<id>` 了解具体问题再修复。
- **返工时附件版本语义（§6.104）**：被打回后该子任务全部历史 ACTIVE 附件自动置 INACTIVE（平台可信视角核验只认最新 ACTIVE，旧版直接失效，不再进入下次核验 / 装载 / 打包）。**修正产出后必须重新 `uploadArtifact` 上传最新版附件**（同名 fileName 会自动覆盖旧 INACTIVE 成为唯一 ACTIVE，新 fileName 也可；旧版本可在附件管理页回查但不再参与判定）。仅修改本地文件后 `submitResult`（不上传新附件）= 旧内容继续被核验 = 打回循环。
- 所有产出物放在子任务对应的工作目录下；提交前确认符合验收标准。
- 不要操作不属于自己的子任务。
- 遇到阻塞用 `reportBlocked`（MCP）或写 blocked 日志（REST），等待 Planner 协助。
- 周期性 `heartbeat` 维持在线，避免被判 OFFLINE。

## 可选：使用 task-cli.py 命令行工具
如果你的运行环境支持 Python，可使用 task-cli.py 简化操作：
```bash
# 下载 CLI
curl {{BASE_URL}}/api/tools/cli -o task-cli.py

# 可用命令
python task-cli.py --key <API_KEY> poll          # 查看我的子任务
python task-cli.py --key <API_KEY> status <id>   # 查看子任务状态
python task-cli.py --key <API_KEY> submit <id>   # 提交成果
python task-cli.py --key <API_KEY> skill         # 获取本 SKILL 文档（Key 自动注入）
python task-cli.py --key <API_KEY> version       # 查看版本
python task-cli.py --key <API_KEY> update        # 更新 CLI + SKILL
```

---

## 常见错误码速查（MCP 通道）

| 码 | 报文关键片段 | 根因 | 修法 |
|---|---|---|---|
| 400 | `Invalid message format` | POST `/mcp/messages` 缺 `charset=utf-8` | 用 `StringContent(..., UTF8, 'application/json')` 让容器自动追加 charset |
| 401 | `Unauthorized` | Bearer 头错 | 检查 API Key 前缀 `ak_` 与拼写 |
| 404 | `Session not found` | SSE 连接断开/超时，session 已被服务端回收（A0-2 起响应体附 `fixHint`） | 重新 GET /mcp/sse 四步握手；或切 REST 别名 `POST /api/mcp/jsonrpc`（免 session） |
| 404 | `GET /api/agents/<id>` 或 `/api/rules/merged` | 路径拼错（SKILL 旧写法） | 用 §0.2 速查表的准确路径：`/api/agents/getById/{id}`、`/api/rules/getMergedRules` |
| 405 | GET `startById` | 开始执行是 POST 端点 | 用 `POST /api/sub-tasks/startById/{id}`（无 body） |
| 500 | `Agent 未在岗（无 ACTIVE 打卡租约）` | 未 checkIn 就调用依赖在岗状态的能力 | 先调 `checkIn`（MCP / REST 别名 / REST 直通均可）再调用 |
| 500 | `sessionId 不能为空` | tool arguments 漏 `sessionId` 字段 | 把 SSE endpoint 帧拿到的 sid **同时**拼进 URL `?sessionId=` 与 arguments `sessionId` |
| 500 | `Unknown tool: xxx` | 工具名拼错 | 先 `tools/list`（或 `GET /api/mcp/tools`）拿权威清单（§0.1 三通道 11 工具） |

> **建议**：优先走 MCP（全套工具 + 统一心跳/租约语义）；无 MCP 客户端时用 REST curl 轮询兜底；CLI 仅覆盖 poll/submit/status 三个高频操作。
