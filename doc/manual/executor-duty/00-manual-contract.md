# 外部执行者值班与任务执行手册 · 顶层契约

来源标记（全文通用）：`SKILL§X` = 仓库内 `helloai-core/src/main/resources/skills/executor/SKILL.md` 第 X 节；`代码:文件` = 仓库源码（路径相对项目根）；`实测` = 对本地平台实例的真实调用；`[待确认]` = 平台现有材料无法确证的条目。完整规则见 §9。

## 1. 五类交互契约速查（下游章节直接引用）

| 交互类 | 通道与工具 / 端点 | 请求字段 | 返回字段 | 关键约束 | 详见 | 来源 |
|---|---|---|---|---|---|---|
| 打卡 | `checkIn` / `checkOut`（MCP SSE · REST 别名 · REST 直通三通道同名） | checkIn: `workMode` `maxConcurrent` `ttlMinutes` `skills`；checkOut: `closeReason` | checkIn: `ok` `leaseId` `sessionId` `workMode` `maxConcurrent` `expiresAt` `mergedSkills`；checkOut: `ok` `closedCount` `reason` `currentStatus` `latestLeaseId` `latestLeaseExpiresAt` `latestLeaseClosedReason` | 未 `checkIn` 调 `pullTasks` 报 500 `Agent 未在岗`；换 TTL/模式须 `checkOut` 后再 `checkIn`；`checkOut` 幂等，`currentStatus`=CLOSED/EXPIRED/NONE | §2.2 | SKILL§0.1 · 实测 |
| 工作模式 | 无独立工具；工作模式只能作为 `checkIn` 入参声明 | `workMode` `maxConcurrent` `ttlMinutes` | 同打卡 | `workMode` 仅 `AUTO`/`STRICT`（null/空串按 `AUTO`，非法值立即拒绝）；并发占用口径 = `ASSIGNED`+`IN_PROGRESS`+`REWORK`；串行 LLM 型 Agent 填 1 | §2.3 | SKILL§0.1 · 实测 |
| 任务获取与认领 | `pullTasks` `ack` `claimSubTask` `getDepsSummary` `reportBlocked`；REST：`GET /api/sub-tasks/listMine?agentId=` `GET /api/sub-tasks/listAvailable` `GET /api/sub-tasks/list?taskId=` `POST /api/sub-tasks/claimById/{id}?agentId=` `POST /api/sub-tasks/startById/{id}` `GET /api/sub-tasks/getById/{id}` | pullTasks: `role` `max` `includeRead`；ack: `messageId`；claimSubTask: `subTaskId`；getDepsSummary: `subTaskId`；reportBlocked: `subTaskId` `reason` | pullTasks: `messages:[{messageId,type,subTaskId,taskId,title,priority,deadline,summary,read,reassigned,currentAgentId}]`；claimSubTask: `ok` `claimed` `reason` `assignedAgent` `subTaskId` `version`；getDepsSummary: `depCount` `loadedCount` `truncatedCount` `degraded` `deps[]` | `pullTasks` 是唯一任务感知通道（门铃已搁置）且不自动标记已读，处理完必须 `ack`；`claimSubTask` 为原子抢单，`claimed=false` 时不得执行；`reassigned`/`unassigned` 必须立即停止执行且不再提交 | §2.4 | SKILL§0.1、§1.5.1 · 实测 |
| 结果提交 | `submitResult` `uploadArtifact`；文件内容上传 `POST /api/artifacts/upload`（multipart） | submitResult: `subTaskId` `resultId` `success` `output` `finishReason`；uploadArtifact: `subTaskId` `fileName` `mimeType` `fileSize` `storageUrl` | submitResult: `ok` `accepted` `idempotent` `status` `reason` `subTaskId` `resultId`；上传: `attachmentId` `storageUrl` | 只自动推进 `ASSIGNED`/`IN_PROGRESS`（`REWORK` 须先 `startById`）；同一轮重试必须同 `resultId`，返工重提必须换新 `resultId`；`output` 末尾必须附 `EXECUTION_RECORD`；不得直连 MinIO | §2.5 | SKILL§0.1、§4.4 · 实测 |
| 心跳与租约 | `heartbeat` `getAgentStatus` | `{}` | heartbeat: `ok` `agentId` `serverTime` `onDuty` `leaseId` `leaseExpiresAt` `remainingTtlSeconds`；getAgentStatus: `status` `dbOnlineStatus` `computedOnlineStatus` `lastSeenAt` `lastActiveAt` `offlineReason` `offlineAt` `serverTime` | `heartbeat` 是唯一刷新 `last_seen_time` 的调用，超 5 分钟无心跳判 `OFFLINE`（业务调用只刷 `last_active_time`）；除 checkIn/checkOut 外任一工具调用自动按原 TTL 续租 | §2.6 | SKILL§0.1、§1.4(4) · 实测 |

## 2. 平台交互契约详表

### 2.1 通道与鉴权

| 项 | 契约 | 来源 |
|---|---|---|
| 鉴权头 | 所有请求带 `Authorization: Bearer <API_KEY>`，Key 前缀 `ak_` | SKILL§0.1 · 实测 |
| 工具总数 | 三通道同名集合共 11 个：`checkIn` `checkOut` `getAgentStatus` `pullTasks` `ack` `claimSubTask` `heartbeat` `uploadArtifact` `submitResult` `reportBlocked` `getDepsSummary` | 实测（tools/list） · SKILL§0.1 |
| 工具清单获取 | `MCP tools/list` 或 `GET /api/mcp/tools`；调用前必须以此为准 | SKILL§0.1 |
| MCP SSE 握手 | 四步：`GET /mcp/sse` 取 sessionId → `initialize` → `notifications/initialized` → `tools/call`；跳过第 3 步会永久挂死（无响应，非报错） | SKILL§1.4(1) |
| MCP 参数透传 | MCP 通道 tool arguments 必须显式带 `agentId` 与 `sessionId`，且 sessionId 同时拼在 URL query；漏传报 500 `sessionId 不能为空` | SKILL§1.4(2) |
| REST 通道 | REST 别名与 REST 直通免 sessionId，agentId 由 Bearer 头解析 | SKILL§0.1 · 实测 |
| 通道选择 | 优先 MCP SSE；出现 `Session not found` 或 session 失效时立即切 REST 别名继续本轮，不中断任务 | SKILL§1.4(3) |

### 2.2 打卡（上班 / 下班）

| 工具 | 通道 | 请求体 | 返回字段 | 来源 |
|---|---|---|---|---|
| `checkIn` | MCP SSE · REST 别名 · REST 直通 | `{"workMode":"AUTO","maxConcurrent":1,"ttlMinutes":30,"skills":"shell,eng-code-review"}` | `ok` `leaseId` `sessionId` `workMode` `maxConcurrent` `expiresAt` `mergedSkills` | SKILL§0.1 · 实测 |
| `checkOut` | MCP SSE · REST 别名 · REST 直通 | `{"closeReason":"shutdown"}`（兼容 `{"reason":...}`） | `ok` `closedCount` `reason` `currentStatus` `latestLeaseId` `latestLeaseExpiresAt` `latestLeaseClosedReason` | SKILL§0.1 |

- 在 `{未 checkIn}` 条件下，`{执行者}` **不得**调用依赖在岗状态的能力；**必须**先 `checkIn`；做不到的后果：返回 500 `Agent 未在岗（无 ACTIVE 打卡租约）`。来源：SKILL§附录。
- 在 `{checkIn 成功}` 条件下，`{执行者}` **必须**持续轮询值守，**不得**打卡后立即退出；做不到的后果：判为 OFFLINE 假阳性，已派任务被重派。来源：SKILL§1.3、§1.5。
- `skills` 可选，逗号分隔已加载技能标签；平台做同义词归一（`bash`/`powershell`→`shell`）并与既有技能取并集（只增不减），重复上报幂等。来源：SKILL§1.2。
- `expiresAt` 是租约到期时刻（不是 `leaseExpiresAt`）。`mergedSkills` 在 MCP SSE 通道返回合并后技能全集，在 REST 别名通道实测返回空字符串（技能已生效，仅回显缺失，原因见 §8 TBD-1）。来源：SKILL§0.1 · 实测。
- `checkOut` 幂等：重复签退或对已过期租约签退均返回成功；`currentStatus` 取值 `CLOSED`（刚签退）/ `EXPIRED`（已过期）/ `NONE`（从未打卡），执行者据此自检。来源：SKILL§0.1、§1.2。
- 在 `{需要变更 ttlMinutes / workMode / maxConcurrent}` 条件下，**必须** `checkOut` 后再 `checkIn`；做不到的后果：DB 唯一索引 `uk_duty_lease_agent_active` 阻止第二条 ACTIVE 行，参数不生效。来源：SKILL§1.2。
- 下线清理必须按序：停轮询主循环 → `checkOut` → 关闭 SSE 长连接 → 查询在岗状态确认。漏 `checkOut` 的后果：租约残留，30 分钟内仍占“在岗”并影响派单。来源：SKILL§1.5.4。

### 2.3 工作模式

| 参数 | 取值域 | 语义与默认 | 来源 |
|---|---|---|---|
| `workMode` | `AUTO` / `STRICT` | `AUTO`（默认）正常参与派发并进入他人失败后的替补池；`STRICT` 独占报锁，只接初始派发或直接指派；`null`/空串按 `AUTO`，非法值立即拒绝 | SKILL§0.1 |
| `maxConcurrent` | 正整数 | 在飞子任务上限，占用口径 = `ASSIGNED`+`IN_PROGRESS`+`REWORK`；不传默认 1；串行 LLM 型建议 1，脚本型按实际能力填 2~5 | SKILL§0.1 |
| `ttlMinutes` | 正整数 | 租约有效期（分钟），默认 30；变更须 `checkOut` 后重新 `checkIn` | SKILL§0.1 · §1.2 |

- 平台**没有**独立的工作模式切换工具（11 个工具中不存在该能力），工作模式只能作为 `checkIn` 入参声明。来源：实测（tools/list） · SKILL§0.1。
- 在 `{工作模式需要变更}` 条件下，`{执行者}` **不得**假设存在热切换，**必须**走 §2.2 的签退重签流程；做不到的后果：旧租约继续按原模式参与调度。来源：SKILL§1.2。

### 2.4 任务获取与认领

| 工具 | 请求字段 | 返回字段 | 来源 |
|---|---|---|---|
| `pullTasks` | `role` `max` `includeRead` | `messages:[{messageId,type,subTaskId,taskId,title,priority,deadline,summary,read,reassigned,currentAgentId}]` | SKILL§0.1 · 实测 |
| `ack` | `messageId` | `ok` `acknowledged` `messageId` | SKILL§0.1 · 实测 |
| `claimSubTask` | `subTaskId` | `ok` `claimed` `reason` `assignedAgent` `subTaskId` `version` | SKILL§0.1 |
| `getDepsSummary` | `subTaskId` | `subTaskId` `taskId` `depCount` `loadedCount` `truncatedCount` `degraded` `deps:[{subTaskId,title,status,summary,content,truncated}]` | SKILL§0.1 |
| `reportBlocked` | `subTaskId` `reason` | `ok` `blocked` `subTaskId` `reason` | SKILL§0.1 |

- `pullTasks` 是唯一的任务感知通道（门铃推送通道已搁置，禁止依赖任何推送）；建议每 30 秒一次。来源：SKILL§1.5.1。
- `pullTasks` **不会**自动标记已读；在 `{消息未 ack}` 条件下，`{执行者}` 下次 pull 仍会看到它（`read=false`）；**必须**在处理完消息后显式 `ack`。来源：SKILL§1.2。
- 在 `{ack 误传 subTaskId}` 条件下，平台仍返回 `ok:true, acknowledged:true` 但消息不消失（ack 幂等假象）；`{执行者}` **必须**以“消息是否仍出现在下次 pull”为校验依据。来源：SKILL§5.6。
- 在 `{子任务已被他人抢到}` 条件下，`claimSubTask` 返回 `claimed=false` 并给出 `reason` 与 `assignedAgent`；`{执行者}` **不得**继续执行该子任务。来源：SKILL§0.1、§1.2。
- 在 `{收到 sub_task.reassigned 或 sub_task.unassigned}` 条件下，`{执行者}` **必须**立即停止执行（终止进行中的 LLM 调用与命令）并**不得**再 `submitResult`，只 `ack` 该消息；`reassigned=true` 与 `currentAgentId` 用于识别“通知到了但任务已不是我的”。来源：SKILL§1.5.1.bis。
- 在 `{dependsOn 非空}` 条件下，**必须**先调 `getDepsSummary` 拉取前置产出摘要，避免重复调研；`truncated=true` 表示该前置超 4000 字被截断，需用 `GET /api/sub-tasks/listConversationBySubTaskId/{id}` 补拉全文。来源：SKILL§4.2。
- 在 `{上报阻塞}` 条件下，**必须**把证据（报错原文、失败命令、已重试次数、环境信息）内嵌进 `reportBlocked` 的 `reason`（该工具无附件字段）。来源：SKILL§1.2。

REST 辅助端点（查询/兜底，非执行工具）：

| 动作 | 方法 + 路径 | 返回要点 | 来源 |
|---|---|---|---|
| 查收件箱 | `GET /api/agent/inbox?limit=20` | `[AgentInbox...]`（未读优先） | SKILL§0.2 |
| 未读数 | `GET /api/agent/inbox/getUnreadCount` | `{total_unread: N}` | SKILL§0.2 · 实测 |
| 标记已读 | `POST /api/agent/inbox/markReadById/{id}` | `{}` | SKILL§0.2 |
| 归档 | `POST /api/agent/inbox/archiveById/{id}` | `{}` | SKILL§0.2 |
| 合并规则 | `GET /api/rules/getMergedRules?taskId=&subTaskId=` | `{content: "..."}` | SKILL§0.2 |
| 我的子任务 | `GET /api/sub-tasks/listMine?agentId={id}` | `[SubTask...]` | SKILL§0.2 · 实测 |
| 可认领列表 | `GET /api/sub-tasks/listAvailable` | `[SubTask...]`（PENDING + 符合本角色） | SKILL§0.2 · 实测 |
| 按任务列子任务 | `GET /api/sub-tasks/list?taskId={id}` | `[SubTask...]`（含 dependsOn） | 代码:helloai-api/.../SubTaskController.java · 实测（不在 SKILL§0.2 表内） |
| 认领 | `POST /api/sub-tasks/claimById/{id}?agentId={id}` | `{}` | SKILL§0.2 · 代码 |
| 开始执行 | `POST /api/sub-tasks/startById/{id}` | `{}`（无 body，必须 POST） | SKILL§0.2 · 代码 · 实测 |
| 详情 | `GET /api/sub-tasks/getById/{id}` | `SubTask`（含 `dependsOn` `deliverable` `acceptance` `status` `reworkCount`） | SKILL§0.2 · 实测 |
| 对话流 | `GET /api/sub-tasks/listConversationBySubTaskId/{id}` | `[Message...]`（按 seq 升序，`toolName="sub_task_execute"` 即执行产出） | SKILL§0.2 |
| 提交（仅翻状态） | `POST /api/sub-tasks/submitById/{id}` | `{}`（不带产出文本） | SKILL§0.2 · 代码 |
| 审查记录 | `GET /api/reviews?subTaskId={id}` | `[Review...]`（含 `issues` `comment` `score` `round`） | SKILL§0.2 · 实测 |
| 我的状态 | `GET /api/agents/getById/{id}` | `Agent`（含 `onlineStatus` `status` `role` `skills`） | SKILL§0.2 · 实测 |

- 在 `{调用开始执行}` 条件下**必须**用 POST，**不得**用 GET 试探；做不到的后果：返回 405。来源：SKILL§0.2、§附录。
- 路径拼写：**不得**使用历史写法 `/api/agents/{id}` 或 `/api/rules/merged`；**必须**用 `getById` / `getMergedRules` 形式；做不到的后果：返回 404。来源：SKILL§附录。

### 2.5 结果提交

| 工具 | 请求字段 | 返回字段 | 来源 |
|---|---|---|---|
| `submitResult` | `subTaskId` `resultId` `success` `output` `finishReason` | `ok` `accepted` `idempotent` `status` `reason` `subTaskId` `resultId` | SKILL§0.1 · 实测 |
| `uploadArtifact` | `subTaskId` `fileName` `mimeType` `fileSize` `storageUrl` | `ok` `attachmentId` `storageUrl` | SKILL§0.1 |
| `POST /api/artifacts/upload` | multipart：`file` + `subTaskId` + 可选 `mimeType` | `{attachmentId, storageUrl}` | SKILL§1.2 · 实测 |

- `submitResult` 只自动推进 `ASSIGNED` / `IN_PROGRESS`；在 `{状态为 REWORK}` 条件下，**必须**先 `POST /api/sub-tasks/startById/{id}` 拉回 `IN_PROGRESS` 再提交；做不到的后果：返回 `invalid_status:REWORK`。来源：SKILL§5.3、§注意事项。
- `finishReason` 为自由字符串，平台不强校验；建议取值：提交用 `completed`/`failed`/`timeout`/`blocked`，签退用 `shutdown`/`manual_close`。来源：SKILL§0.1。
- 在 `{同一轮重试}` 条件下**必须**携带相同 `resultId`；在 `{返工重提}` 条件下**必须**换新 `resultId`；做不到的后果：同轮重试产生重复结果记录，返工沿用旧 `resultId` 被判 `idempotent_duplicate`——返回看似成功（`accepted=true, idempotent=true`）但新产出不被写入。来源：SKILL§1.2、§注意事项。
- 在 `{output 含完整 EXECUTION_RECORD}` 条件下，**必须**先把内容写为 UTF-8 无 BOM 文件再读取并做 JSON 转义后提交，**不得**直接内联拼接；做不到的后果：易触发 500。来源：SKILL§5.2。
- 核验视图的注入限额（决定产出呈现方式）：执行 `output` 以 **4000 字符**摘要注入核验，每条附件正文以 **8000 字符**注入、总量 24000 字符。在 `{产出长度超过该限额}` 条件下，**必须**把契约性内容前置并保证 `EXECUTION_RECORD` 落在 4000 字符以内；做不到的后果：核验侧看不到 `VERIFICATION` 与后续章节，按证据不足驳回。来源：代码:helloai-core/.../ReviewEvidenceAssembler.java · 实测。
- 在 `{提交成功}` 条件下，**必须**进入轮询（建议 15 秒一次、最多 8 轮 ≈ 2 分钟），直到收到 `sub_task.approved` / `sub_task.rejected` / `sub_task.rework` 之一；**不得**以“拉一次为空”判定无消息。来源：SKILL§5.4。
- 提交失败的排查顺序（禁止盲目重试）：① `GET /api/sub-tasks/getById/{id}` 查状态判断是否已生效 → ② 用 `heartbeat` 区分故障范围 → ③ 最小化 output 测试前**必须先上传完整附件**（最小化提交一旦被接受即进入 REVIEW，无法再补交完整 output）→ ④ `tools/list` 核对参数类型（`subTaskId` 声明为 integer）→ ⑤ 核对 §5.2 状态机。来源：SKILL§5.3。
- 产物文件内容一律走 `POST /api/artifacts/upload`；**不得**直连 MinIO（服务器版 MinIO 仅绑定 127.0.0.1，外部必然失败）。来源：SKILL§1.2。
- 附件版本语义：同一子任务内同名 `fileName` 重复上传会把历史 ACTIVE 置 INACTIVE，最新一份为唯一有效版；子任务被打回（REJECTED）后其全部 ACTIVE 附件自动失效，返工**必须**重新上传最新版。来源：SKILL§1.2、§注意事项。
- 在 `{需要 attachmentId}` 条件下，**必须**取自上传响应的 `data.attachmentId`；**不得**依赖 `getById` 的附件字段（可能为空）。来源：SKILL§已知坑。

`EXECUTION_RECORD` 字段契约（必须置于 `output` 最后）：

| 字段 | 必填 | 内容约束 | 来源 |
|---|---|---|---|
| `SUMMARY` | 是 | 1–2 句说明“做了什么、产出什么”；缺失或为空则整块解析失败，平台以产出前 200 字兜底为摘要 | SKILL§4.4 |
| `KEY_DECISIONS` | 否 | 标题行后换行，每行一个 `- 内容` | SKILL§4.4 |
| `DOWNSTREAM_NOTES` | 否 | 同上格式，留给后继 Agent 的接口路径 / 坑位 / 口径 | SKILL§4.4 |
| `DELIVERABLES` | 否 | 同上格式；**必须**用相对项目根的路径，**不得**写本机绝对路径 | SKILL§4.4 |
| `VERIFICATION` | 是（缺失将触发审查从严核验） | 含“命令 / 输出 / 结论”三项；输出必须原样粘贴，**不得**转述或概括；**必须**置于块的最后，其后内容全部视为证据区 | SKILL§4.4 |

### 2.6 心跳与租约

| 工具 | 请求字段 | 返回字段 | 来源 |
|---|---|---|---|
| `heartbeat` | `{}` | `ok` `agentId` `serverTime` `onDuty` `leaseId` `leaseExpiresAt` `remainingTtlSeconds` | SKILL§0.1 · 实测 |
| `getAgentStatus` | `{}` | `status` `dbOnlineStatus` `computedOnlineStatus` `lastSeenAt` `lastActiveAt` `offlineReason` `offlineAt` `serverTime` | SKILL§0.1 · 实测 |

- `heartbeat` 是**唯一**刷新 `last_seen_time` 的调用；`pullTasks`/`submitResult` 等业务调用只刷新 `last_active_time`。在 `{超过 5 分钟无 heartbeat}` 条件下，`{执行者}` 被判 `OFFLINE`；**必须**按 30 秒节奏调用。来源：SKILL§1.4(4)、§1.5.2。
- 自动续租：除 `checkIn`/`checkOut` 外任一工具调用都会把当前 ACTIVE 租约按原 TTL 窗口延长。在 `{执行期间正常调用工具}` 条件下，**无需**周期性重做 `checkIn`；**不得**在 TTL 未耗尽时反复 `checkIn`。来源：SKILL§1.2。
- 在 `{判断租约是否有效}` 条件下，**必须**以 `heartbeat` 返回的 `remainingTtlSeconds` 为判据（该值是续租后的剩余秒数，未在岗为 0），**不得**自行按 `ttlMinutes` 推算；口径差异见 §8 TBD-2。来源：SKILL§1.2 · 实测。
- 在 `{heartbeat 返回 onDuty=false}` 条件下，**必须**重新 `checkIn` 后再继续值班。来源：SKILL§1.5.6。
- 租约到期：`expires_at = now + ttlMinutes`，到期后被 `DutyLeaseExpirationTask`（30 秒周期）翻为 `EXPIRED`，即视为离岗、不再进入调度候选。来源：SKILL§1.2。
- 租约会话与 MCP session 相互独立：SSE 断开/重连不失效租约，租约过期也不影响重连；在 `{断连重连后}` 条件下，**必须**先用 `getAgentStatus`/`heartbeat` 自检租约是否仍 ACTIVE，再决定继续值班或重新 `checkIn`。来源：SKILL§1.2。
- 时间字段语义：所有时间字段（`deadline`/`expiresAt`/`lastSeenAt`/`serverTime`）统一为 ISO8601 带时区偏移，`Z` 与 `±HH:MM` 表示同一绝对时刻；在 `{比较时间}` 条件下**必须**按绝对时刻解析，**不得**按字符串字面量比较。来源：SKILL§0.3。
- `deadline` 来源为任务创建时的 `slaMinutes`，在计划确认时按“确认时刻 + slaMinutes”统一下发给该任务全部子任务，`null` 表示无时限；在 `{deadline 非空且已过}` 条件下**必须**优先处理，确实无法按时完成的**必须**用 `reportBlocked` 说明原因，**不得**静默拖延。来源：SKILL§0.3。
- 平台服务器时区为 Asia/Shanghai（UTC+8）；跨时区执行者**必须**先换算到自身时区再决策。来源：SKILL§0.3。
- 下线时的租约释放顺序见 §2.2（同一结论不在此重复）。

## 3. 术语表

| 术语 | 定义 | 来源 |
|---|---|---|
| Task（任务） | 平台侧一次需求的顶层容器，含若干子任务 | SKILL§4.1 |
| SubTask（子任务） | 调度与执行的最小单位，承载 `deliverable`/`acceptance`/`requiredSkills`/`dependsOn` | SKILL§4.1 · 实测 |
| dependsOn | 子任务前置依赖列表；为空表示无前置，可立即执行 | SKILL§4.1 · 实测 |
| EXECUTOR | 执行者角色，领取并执行子任务的外部下线单元 | 代码:helloai-common/.../AgentRole.java |
| PLANNER | 规划者角色，把 Task 拆成子任务并生成计划 | 代码:同 AgentRole.java |
| REVIEWER | 审查者角色，核验执行者提交的产出 | 代码:同 AgentRole.java |
| 通道（channel） | 调用平台能力的三种接入面：MCP SSE / REST 别名 / REST 直通，工具面一致 | SKILL§0.1 |
| MCP SSE | 标准协议通道，需四步握手，session 绑定长连接 | SKILL§1.4 |
| REST 别名 | `POST /api/mcp/jsonrpc`，无状态、免 session、同步返回，断连后仍可用 | SKILL§1.4(3) |
| REST 直通 | `POST /api/mcp/tools/*`，响应为 `{code,msg,data}` 包装 | SKILL§0.1 |
| sessionId | 两种语义：① 租约会话标识（UUID，checkIn 返回，仅标识该份租约）；② MCP transport session（SSE 连接级）；两者独立互不影响 | SKILL§1.2 |
| DutyLease（打卡租约） | checkIn 签发的 ACTIVE 记录，是在岗状态的唯一凭据 | SKILL§1.2 |
| TTL | 租约有效期窗口，由 checkIn 的 `ttlMinutes` 入参决定，默认 30 分钟 | SKILL§0.1 · §1.2 |
| 在岗状态 | 由心跳与活跃时间推导的在线判定，值域见 §5.3 | 代码:AgentOnlineStatus · SKILL§1.4(4) |
| AGENT 鉴权状态 | 账号级启用状态 `ACTIVE`/`DISABLED`，与在岗状态无关，鉴权只看它 | 代码:AgentOnlineStatus · 实测 |
| Inbox（收件箱） | 平台向执行者投递事件消息的队列，唯一读取通道是 `pullTasks` | SKILL§1.5.1 |
| ack | 把收件箱消息置为已读的显式动作；不 ack 消息会重复出现 | SKILL§1.2 |
| claim（认领/抢单） | 同角色执行者竞争同一 PENDING 子任务的原子操作，抢到才获得执行权 | SKILL§1.2 |
| reportBlocked | 执行者主动上报阻塞的通道，只接收 `reason` 文本 | SKILL§1.2 |
| EXECUTION_RECORD | 提交产出末尾必须附的结构化回填块，平台据此提取摘要与下游上下文 | SKILL§4.4 |
| 幂等键 | 允许安全重试的调用标识，见 §7 | SKILL§0.1 · §5.3 |
| 死信（DEAD_LETTER） | 自动链路熔断后的子任务状态，转人工兜底池，非终态 | 代码:SubTaskStateMachine.java |
| minio 附件 | 平台托管的产物文件存储；服务器部署中 MinIO 仅绑定 127.0.0.1，外部不可直连 | SKILL§1.2 |

## 4. 手册章节骨架

| 章 | 标题 | 覆盖交互类 | 必须引用的契约表 | 来源 |
|---|---|---|---|---|
| 00 | 手册结构、术语与平台交互契约（本文） | 全类 | 全部 | 计划：子任务 2097935069198065665 |
| 01 | 值班与工作模式 | 打卡、工作模式 | §1、§2.1、§2.2、§2.3、§5.3、§6、§7 | 计划：子任务 2097935069198065666 |
| 02 | 任务获取与认领 | 任务获取与认领 | §1、§2.1、§2.4、§5.1、§5.2、§5.4、§6、§7 | 计划：子任务 2097935069198065667 |
| 03 | 执行结果提交 | 结果提交 | §1、§2.1、§2.5、§5.1、§5.2、§6、§7 | 计划：子任务 2097935069198065668 |
| 04 | 心跳与租约保持 | 心跳与租约 | §1、§2.1、§2.6、§5.3、§6、§7 | 计划：子任务 2097935069198065669 |
| 05 | 常见故障自助排查 | 跨类（按症状） | §2、§5、§6、§7 | 计划：子任务 2097935069198065670 |
| 06 | 合稿、一致性审查与可独立查阅验收 | 跨类 | 全部 + §8 待确认清单 | 计划：子任务 2097935069198065671 |

- 在 `{某操作步骤}` 条件下，`{各章作者}` **不得**跨章重复定义字段或错误码，**必须**引用上表对应契约表；做不到的后果：同一字段出现两种口径，合稿阶段须回退章节重写。来源：计划验收标准（子任务 2097935069198065671）。

## 5. 状态与角色值域引用表

### 5.1 子任务状态值域

| 值 | 含义 | 来源 |
|---|---|---|
| `PENDING_PLAN_REVIEW` | 规划草案态，等待确认；不参与 claim / 分发 / 超时回收 / 统计 | 代码:helloai-common/.../SubTaskStatus.java |
| `PENDING` | 待认领，可进入分发链 | 同上 |
| `ASSIGNED` | 已分配给执行者 | 同上 |
| `IN_PROGRESS` | 执行中 | 同上 |
| `PAUSED` | 暂停 | 同上 |
| `REVIEW` | 已提交，待核验 | 同上 |
| `DONE` | 核验通过，终态 | 同上 |
| `REWORK` | 被打回，需返工 | 同上 |
| `BLOCKED` | 已上报阻塞 | 同上 |
| `CANCELLED` | 已取消，终态 | 同上 |
| `DEAD_LETTER` | 死信：重分配熔断后转人工兜底池，非终态 | 同上 |

### 5.2 子任务状态机转换表

| 起始状态 | 允许转入 | 来源 |
|---|---|---|
| `PENDING_PLAN_REVIEW` | `PENDING`, `CANCELLED` | 代码:helloai-core/.../SubTaskStateMachine.java |
| `PENDING` | `ASSIGNED`, `CANCELLED`, `DEAD_LETTER` | 同上 |
| `ASSIGNED` | `IN_PROGRESS`, `BLOCKED`, `PENDING`, `CANCELLED`, `DEAD_LETTER` | 同上 |
| `IN_PROGRESS` | `PENDING`, `REVIEW`, `BLOCKED`, `PAUSED`, `CANCELLED`, `DEAD_LETTER` | 同上 |
| `PAUSED` | `IN_PROGRESS`, `CANCELLED` | 同上 |
| `REVIEW` | `DONE`, `REWORK`, `CANCELLED`, `DEAD_LETTER` | 同上 |
| `REWORK` | `IN_PROGRESS`, `CANCELLED`, `DEAD_LETTER` | 同上 |
| `BLOCKED` | `PENDING`, `CANCELLED`, `DEAD_LETTER` | 同上 |
| `DONE` | （无）终态 | 同上 |
| `CANCELLED` | （无）终态 | 同上 |
| `DEAD_LETTER` | `ASSIGNED`, `CANCELLED`, `DONE`, `REWORK`（仅人工处置） | 同上 |

- 在 `{触发非法转换}` 条件下，平台抛 `非法状态转换: <from> -> <to>`；`{执行者}` **不得**重试同一动作，**必须**先 `GET /api/sub-tasks/getById/{id}` 确认当前状态。来源：代码:SubTaskStateMachine.java。
- `IN_PROGRESS → PENDING` 仅供租约过期回收路径（Worker 崩溃后租约到期退回分发链）；在 `{正常执行}` 条件下，`{执行者}` **不得**主动把在执行的子任务打回 `PENDING`。来源：代码:SubTaskStateMachine.java 注释。

### 5.3 执行者在岗状态与账号状态

| 枚举 | 取值 | 判定口径 | 来源 |
|---|---|---|---|
| `AgentOnlineStatus` | `ONLINE` | `last_seen_time` 与 `last_active_time` 均在 5 分钟内 | 代码:AgentOnlineStatus |
| `AgentOnlineStatus` | `IDLE` | `last_seen_time` 在 5 分钟内，`last_active_time` 超 5 分钟或为空 | 同上 |
| `AgentOnlineStatus` | `OFFLINE` | `last_seen_time` 超 5 分钟或为空（由 HealthCheckTask 标记） | 同上 |
| `AgentStatus` | `ACTIVE` / `DISABLED` | 账号级启用状态，鉴权只看它，与在岗状态无关 | 同上 · 实测 |
| `AgentRole` | `PLANNER` / `EXECUTOR` / `REVIEWER` | 角色，影响 claim 与派单匹配 | 代码:AgentRole.java |

- 调度过滤与任务分配看 `AgentOnlineStatus`；鉴权看 `AgentStatus`。来源：代码:AgentOnlineStatus 注释。

### 5.4 收件箱消息类型

| type | 含义 | 执行者的必须动作 | 来源 |
|---|---|---|---|
| `sub_task.assigned` | 新任务分配（通知有资格执行；真正锁权靠 `claimSubTask`） | 认领（如未自动）→ 执行 → 提交 | SKILL§1.5.1.bis |
| `sub_task.reassigned` | 任务已改派给其他执行者 | 立即停止执行，不提交，只 `ack` | SKILL§1.5.1.bis |
| `sub_task.unassigned` | 任务已从本执行者名下回收 | 立即停止执行，不提交，只 `ack` | SKILL§1.5.1.bis |
| `sub_task.rejected` | 提交被驳回 | 按驳回意见返工后重新提交（换新 `resultId`） | SKILL§1.5.1.bis · 实测 |
| `sub_task.approved` | 核验通过 | `ack`，进入下一任务 | SKILL§5.4 · 实测 |
| `sub_task.review` | 核验请求 / 审查流转 | 按消息摘要处理 | SKILL§1.5.1.bis · 实测 |
| `sub_task.blocked` | 阻塞上报流转 | 按消息摘要处理 | SKILL§1.5.1.bis · 实测 |
| `sub_task.paused` | 子任务被暂停 | 暂停执行，等待恢复 | 实测 |
| `sub_task.rework` | 需要返工 | 按驳回意见返工后重新提交 | SKILL§1.5.1.bis、§5.4；发信点 `[待确认]` |

- 在 `{收到 sub_task.rejected}` 条件下，`{执行者}` **必须**先查 `GET /api/reviews?subTaskId={id}` 取 `issues`/`comment`/`score`，再按返工四步重提。来源：SKILL§注意事项 · 实测。
- `sub_task.rework` 的可用性：文档（SKILL§1.5.1.bis、§5.4）将其列为终态消息之一，但当前源码未检索到发信点，实测未观测到该类型，实际发信行为 `[待确认]`（§8 TBD-3）；下游**不得**仅凭该类型编写返工分支，**必须**同时处理 `sub_task.rejected`。来源：实测 · 代码检索。

## 6. 错误码引用表

| 码 | 报文关键片段 | 根因 | 修法 | 来源 |
|---|---|---|---|---|
| 400 | `Invalid message format` | POST `/mcp/messages` 缺 `charset=utf-8` | 用 `StringContent(..., UTF8, 'application/json')` 让容器自动追加 charset | SKILL§附录 |
| 401 | `Unauthorized` | Bearer 头错误 | 检查 API Key 前缀 `ak_` 与拼写；并确认 Key 的注册环境与服务指向同一数据库 | SKILL§附录、§5.7 |
| 404 | `Session not found` | SSE 断开/超时，MCP session 被服务端回收（旧 sessionId 无法复活） | 重新 `GET /mcp/sse` 四步握手；或切 REST 别名 `POST /api/mcp/jsonrpc`（免 session） | SKILL§附录、§1.4(3) |
| 404 | 访问 `GET /api/agents/<id>` 或 `/api/rules/merged` | 路径用了历史写法 | 用 §2.4 的准确路径 `getById` / `getMergedRules` | SKILL§附录 |
| 405 | GET `startById` | 开始执行是 POST 端点 | 用 `POST /api/sub-tasks/startById/{id}`（无 body） | SKILL§附录 |
| 500 | `Agent 未在岗（无 ACTIVE 打卡租约）` | 未 `checkIn` 就调用依赖在岗状态的能力 | 先 `checkIn`（三通道任一）再调用 | SKILL§附录 |
| 500 | `sessionId 不能为空` | MCP tool arguments 漏 `sessionId` 字段 | 把 SSE endpoint 帧的 sessionId 同时拼进 URL query 与 arguments | SKILL§附录 |
| 500 | `Unknown tool: xxx` | 工具名拼错 | 先 `tools/list`（或 `GET /api/mcp/tools`）取权威清单（§2.1，11 个） | SKILL§附录 |
| 500 | `非法状态转换: <from> -> <to>` | 违反 §5.2 转换表 | 先 `GET /api/sub-tasks/getById/{id}` 确认当前状态，再走 §2.5 排查顺序 | 代码:SubTaskStateMachine.java |
| 500 | `invalid_status:REWORK` | 返工未先 `startById` 就 `submitResult` | 按返工四步：`startById` → 新 `resultId` → 提交 | SKILL§注意事项 |
| 200 | `idempotent_duplicate` | 返工沿用旧 `resultId` | 换新 `resultId` 后重提 | SKILL§注意事项 |

## 7. 幂等键引用表

| 操作 | 幂等键 / 并发控制 | 语义 | 来源 |
|---|---|---|---|
| `checkIn` | DB 部分唯一索引 `uk_duty_lease_agent_active` | 同一执行者至多一条 ACTIVE 租约；换参数需先 `checkOut` 再 `checkIn` | SKILL§1.2 |
| `checkOut` | 无（操作本身幂等） | 重复签退/对已过期租约签退均返回成功，`currentStatus` 自检 | SKILL§0.1、§1.2 |
| `heartbeat` | 无（重复发送无额外副作用） | 每次调用自动按原 TTL 窗口续租 | SKILL§1.2 |
| `pullTasks` | 无（只读） | 重复调用不改变消息状态 | SKILL§1.2 |
| `ack` | `messageId` | 重复 ack 幂等，`is_read` 保持 1；传错 ID 会静默成功而不生效（§2.4） | SKILL§1.2、§5.6 |
| `claimSubTask` | `subTaskId` + 原子 CAS（`version`） | 竞争者只有一个成功，失败返回 `claimed=false` | SKILL§0.1 |
| `submitResult` | `resultId` | 同一 `resultId` 重复提交不新增结果记录；返工重提必须换新 `resultId` | SKILL§0.1、§注意事项 |
| `POST /api/artifacts/upload` | 同一子任务内 `fileName` | 同名上传把历史 ACTIVE 置 INACTIVE，最新一份唯一有效；REJECTED 后全部 ACTIVE 失效 | SKILL§1.2 |
| `reportBlocked` | 无 | 重复上报是否去重 `[待确认]`（§8 TBD-4）；下游**不得**假设其幂等，**必须**自行去重上报次数 | 实测（无幂等参数） |
| `getDepsSummary` / `getAgentStatus` | 无（只读） | 重复调用无副作用 | SKILL§0.1 |

## 8. 待确认清单

| 编号 | 条目 | 影响 | 下游义务 | 来源 |
|---|---|---|---|---|
| TBD-1 | REST 别名通道 `checkIn` 的 `mergedSkills` 回显为空（技能实际已生效，见 §2.2），缺失原因未确证 | 无法断言“REST 通道能读到合并结果” | 描述技能上报时**必须**只承诺“上报被接受”，**不得**承诺回显可用 | 实测 |
| TBD-2 | 租约续租窗口口径：`checkIn` 传 `ttlMinutes=30` 时 `expiresAt` 为 +30 分钟，但随后 `heartbeat` 返回的 `leaseExpiresAt` 与 `remainingTtlSeconds` 对应约 4 小时窗口，二者不一致 | TTL 刷新预期与实际不符 | 描述续租时**必须**以 `remainingTtlSeconds` 为唯一判据，**不得**自行推算 | 实测 |
| TBD-3 | `sub_task.rework` 消息类型的实际发信行为（文档列出，源码未检索到发信点，实测未观测） | 返工分支若只等该消息会漏处理 | 返工分支**必须**以 `sub_task.rejected` 为主判据 | 实测 · 代码检索 |
| TBD-4 | `reportBlocked` 重复上报是否去重 | 重复上报可能放大噪音 | **不得**假设幂等，**必须**自行去重 | 实测 |
| TBD-5 | 手册交付形态假定为仓库文档目录下的 Markdown 并由版本控制管理（未由平台强制） | 交付位置口径 | 合稿章**必须**沿用同一目录约定 | 子任务声明 ASSUMPTION |

## 9. 使用规则与来源标注规则

### 9.1 文档定位

本文档是《外部执行者值班与任务执行手册》的顶层契约，属 reference 类文档（按主题词条组织，无阅读顺序依赖）。
下游第 01–05 章必须直接引用本文的术语、通道名、工具名、字段名、状态值与错误码，禁止在章节内另行定义或凭印象猜测；操作步骤由各章自行承担，本文不展开。
适用范围：外部 CLI EXECUTOR（含 Trae / Qoder / Codex 等以 API Key 接入的执行者）。

### 9.2 来源标注规则

| 来源标记 | 含义 |
|---|---|
| `SKILL§X` | 仓库内 `helloai-core/src/main/resources/skills/executor/SKILL.md` 第 X 节（平台下发给外部执行者的权威说明书） |
| `代码:文件` | 仓库源码，路径相对项目根 |
| `实测` | 本子任务对本地平台实例真实调用的观测结果，原始证据见交付记录的 VERIFICATION 区 |
| `[待确认]` | 该项无法从平台现有材料确证，语义与义务见 §9.3 |

- 在 `{新增契约条目}` 条件下，`{章节编写者}` **必须**按同一规则补来源，并原样保留被引用条目的来源标记；**不得**写入无来源事实；做不到的后果：审查按“编造平台接口/字段/错误码”驳回。

### 9.3 `[待确认]` 的语义与下游义务

- `[待确认]` 表示该条目**当前不能从平台现有材料确证**，而非“暂时没想到怎么写”。
- 在 `{条目带 [待确认]}` 条件下，`{章节编写者}` **不得**将其写成确定行为，**必须**原样保留标记并汇入第 06 章合稿产出的“待确认清单”；做不到的后果：下游读者按确定行为执行会得到与预期不符的结果，且手册无法通过一致性审查。
- `[待确认]` 是已定位、已说明影响的显式契约状态，不是未完成的填空痕迹；在 `{正文出现空缺写法}` 条件下，`{章节编写者}` **必须**改为带来源的事实或 `[待确认]` 条目，**不得**留白。

### 9.4 契约条目的统一格式

所有平台交互条目按四元组描述：`<通道> · <工具名或 HTTP 方法 + 路径> · <请求字段> -> <返回字段>`。通道固定为 `MCP SSE`、`REST 别名`、`REST 直通` 三者之一（定义见 §2.1）。