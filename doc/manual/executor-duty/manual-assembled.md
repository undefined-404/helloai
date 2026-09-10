# 外部执行者值班与任务执行手册

面向以 API Key 接入 HelloAI 调度平台的外部 CLI EXECUTOR（Trae / Qoder / Codex 等）。

## 版本记录

| 版本 | 覆盖范围 | 说明 |
|---|---|---|
| v1.0 | 第 00–05 章 + 本前置部分 | 首次合稿：顶层契约、值班与工作模式、任务获取与认领、执行结果提交、心跳与租约保持、常见故障自助排查。章节正文逐字取自各章已审批交付物，装配仅统一标题层级、不改动正文内容。 |

## 阅读约定与勘误

引用约定：`契约 §X` 指第 00 章的顶层契约小节；`第 NN 章 §X` 指对应章节的小节；单独的 `§X` 仅在上下文已限定到某一章时使用。
第 02 章与第 05 章的章内小节号与契约小节号存在同号不同义的情况（例如 `2.4` 在契约中指“任务获取与认领”，在第 02 章中指“幂等键与并发控制”），阅读时以本约定消歧。

勘误表（合稿阶段不改动已审批章节正文，发现的冲突以勘误方式公开）：

| 编号 | 位置 | 原文 | 应读作 | 依据 |
|---|---|---|---|---|
| E-1 | 第 05 章 §5.2.2“已 ack 的消息反复出现”行 | 契约 §5.6 | 契约 §2.4（ack 语义） | 契约小节编号止于 §9，不存在 §5.6；ack 幂等语义定义于契约 §2.4，其来源为平台说明的 SKILL§5.6 |
| E-2 | 第 01 章 §1.7、第 05 章 §5.2.1 | 错误码 `-32000` | 实测有效值，但契约 §6 尚未收录 | 实测报文 `{"error":{"code":-32000,"message":"unknown workMode: 'BUSY', valid values: AUTO, STRICT"}}` |
| E-3 | 契约 §6 行“500 `Agent 未在岗（无 ACTIVE 打卡租约）`” | 视为确定行为 | 本环境未复现，不得作为确定行为 | 第 04 章实测：无 ACTIVE 租约时 `pullTasks` / `claimSubTask` / `getDepsSummary` 均正常返回（TBD-04-1） |
| E-4 | 契约 §2.2 | `latestLeaseCloseReason` | `latestLeaseClosedReason` | 实测 checkOut 返回字段名，已在契约内更正并在此存档 |

## 目录

- 第 00 章 顶层契约（手册结构骨架、术语表、五类交互契约表、状态与角色值域、错误码、幂等键、待确认清单、使用规则）
- 第 01 章 值班与工作模式
- 第 02 章 任务获取与认领
- 第 03 章 执行结果提交
- 第 04 章 心跳与租约保持
- 第 05 章 常见故障自助排查

可独立查阅性：每章开头均声明本章范围、与其他章的分工边界以及所引用的契约小节，任一主题均可在单章内完成基本操作判定。

## 术语索引

以下术语定义于第 00 章（契约）§3 术语表；各章出现的其余名词均引用该表。

| 术语 | 定义位置 |
|---|---|
| Task（任务） | 第 00 章 §3 |
| SubTask（子任务） | 第 00 章 §3 |
| dependsOn | 第 00 章 §3 |
| EXECUTOR | 第 00 章 §3 |
| PLANNER | 第 00 章 §3 |
| REVIEWER | 第 00 章 §3 |
| 通道（channel） | 第 00 章 §3 |
| MCP SSE | 第 00 章 §3 |
| REST 别名 | 第 00 章 §3 |
| REST 直通 | 第 00 章 §3 |
| sessionId | 第 00 章 §3 |
| DutyLease（打卡租约） | 第 00 章 §3 |
| TTL | 第 00 章 §3 |
| 在岗状态 | 第 00 章 §3 |
| AGENT 鉴权状态 | 第 00 章 §3 |
| Inbox（收件箱） | 第 00 章 §3 |
| ack | 第 00 章 §3 |
| claim（认领/抢单） | 第 00 章 §3 |
| reportBlocked | 第 00 章 §3 |
| EXECUTION_RECORD | 第 00 章 §3 |
| 幂等键 | 第 00 章 §3 |
| 死信（DEAD_LETTER） | 第 00 章 §3 |
| minio 附件 | 第 00 章 §3 |

## 待确认清单汇总

全手册共 20 项待确认。清单中任一项均不得在下游被写成确定行为。

| 编号 | 所属 | 条目 | 下游义务 |
|---|---|---|---|
| TBD-1 | 第 00 章 | REST 别名通道 `checkIn` 的 `mergedSkills` 回显为空（技能实际已生效，见 §2.2），缺失原因未确证 | 描述技能上报时**必须**只承诺“上报被接受”，**不得**承诺回显可用 |
| TBD-2 | 第 00 章 | 租约续租窗口口径：`checkIn` 传 `ttlMinutes=30` 时 `expiresAt` 为 +30 分钟，但随后 `heartbeat` 返回的 `leaseExpiresAt` 与 `remainingTtlSeconds` 对应约 4 小时窗口，二者不一致 | 描述续租时**必须**以 `remainingTtlSeconds` 为唯一判据，**不得**自行推算 |
| TBD-3 | 第 00 章 | `sub_task.rework` 消息类型的实际发信行为（文档列出，源码未检索到发信点，实测未观测） | 返工分支**必须**以 `sub_task.rejected` 为主判据 |
| TBD-4 | 第 00 章 | `reportBlocked` 重复上报是否去重 | **不得**假设幂等，**必须**自行去重 |
| TBD-5 | 第 00 章 | 手册交付形态假定为仓库文档目录下的 Markdown 并由版本控制管理（未由平台强制） | 合稿章**必须**沿用同一目录约定 |
| TBD-01-1 | 第 01 章 | 契约 §2.2 的 `checkOut` 返回字段名 `latestLeaseCloseReason` 与实测 `latestLeaseClosedReason` 不一致 |  |
| TBD-01-2 | 第 01 章 | 是否存在“暂停值班”（区别于 `checkOut` 下线）的独立能力 |  |
| TBD-01-3 | 第 01 章 | 重复 `checkIn` 轮换租约是否影响已领取但未完成的子任务 |  |
| TBD-02-1 | 第 02 章 | 除 `role` / `max` / `includeRead` 外是否存在服务端筛选参数（优先级、技能、`deadline`） |  |
| TBD-02-2 | 第 02 章 | 他人持有子任务时 `claimSubTask` 的 `reason` 文本取值 |  |
| TBD-02-3 | 第 02 章 | 平台是否存在任务级锁字段（`leaseId` / `lockId`） |  |
| TBD-03-1 | 第 03 章 | 同轮重复提交同一 `resultId` 时的精确返回报文（本地仅在状态守卫层观测到 `invalid_status`，未在可提交状态窗口内构造出重复提交） |  |
| TBD-03-2 | 第 03 章 | 是否存在“部分完成”专用状态值或字段 |  |
| TBD-03-3 | 第 03 章 | 提交失败计数的服务端阈值 |  |
| TBD-04-1 | 第 04 章 | 无 ACTIVE 租约时 500 `Agent 未在岗` 门禁的适用范围（实测 REST 别名通道下 pullTasks/claimSubTask/getDepsSummary 均未触发） |  |
| TBD-04-2 | 第 04 章 | `heartbeat` 续租窗口口径与 `checkIn` 的 `ttlMinutes` 不一致（契约 §8 TBD-2） |  |
| TBD-04-3 | 第 04 章 | 心跳间隔/阈值是否可按 Agent 配置 |  |
| TBD-05-1 | 第 05 章 | 核验 Prompt 中附件与 `output` 的实际注入形态（已知限额 8000/4000 字符，见契约 §2.5） |  |
| TBD-05-2 | 第 05 章 | 死信再派单的授权范围（哪个角色可调用） |  |
| TBD-05-3 | 第 05 章 | 各故障的重试计数是否由平台持久化 |  |

## 一致性审查结论

审查范围、方法、逐项证据与处置见同目录 `06-consistency-audit.md`。结论：命令、字段、错误码与状态值均与契约一致；发现的 4 项问题已在上表以勘误方式公开，其中 2 项属契约缺项或未复现项，需契约维护方处理。

## 外部执行者值班与任务执行手册 · 顶层契约

来源标记（全文通用）：`SKILL§X` = 仓库内 `helloai-core/src/main/resources/skills/executor/SKILL.md` 第 X 节；`代码:文件` = 仓库源码（路径相对项目根）；`实测` = 对本地平台实例的真实调用；`[待确认]` = 平台现有材料无法确证的条目。完整规则见 §9。

### 1. 五类交互契约速查（下游章节直接引用）

| 交互类 | 通道与工具 / 端点 | 请求字段 | 返回字段 | 关键约束 | 详见 | 来源 |
|---|---|---|---|---|---|---|
| 打卡 | `checkIn` / `checkOut`（MCP SSE · REST 别名 · REST 直通三通道同名） | checkIn: `workMode` `maxConcurrent` `ttlMinutes` `skills`；checkOut: `closeReason` | checkIn: `ok` `leaseId` `sessionId` `workMode` `maxConcurrent` `expiresAt` `mergedSkills`；checkOut: `ok` `closedCount` `reason` `currentStatus` `latestLeaseId` `latestLeaseExpiresAt` `latestLeaseClosedReason` | 未 `checkIn` 调 `pullTasks` 报 500 `Agent 未在岗`；换 TTL/模式须 `checkOut` 后再 `checkIn`；`checkOut` 幂等，`currentStatus`=CLOSED/EXPIRED/NONE | §2.2 | SKILL§0.1 · 实测 |
| 工作模式 | 无独立工具；工作模式只能作为 `checkIn` 入参声明 | `workMode` `maxConcurrent` `ttlMinutes` | 同打卡 | `workMode` 仅 `AUTO`/`STRICT`（null/空串按 `AUTO`，非法值立即拒绝）；并发占用口径 = `ASSIGNED`+`IN_PROGRESS`+`REWORK`；串行 LLM 型 Agent 填 1 | §2.3 | SKILL§0.1 · 实测 |
| 任务获取与认领 | `pullTasks` `ack` `claimSubTask` `getDepsSummary` `reportBlocked`；REST：`GET /api/sub-tasks/listMine?agentId=` `GET /api/sub-tasks/listAvailable` `GET /api/sub-tasks/list?taskId=` `POST /api/sub-tasks/claimById/{id}?agentId=` `POST /api/sub-tasks/startById/{id}` `GET /api/sub-tasks/getById/{id}` | pullTasks: `role` `max` `includeRead`；ack: `messageId`；claimSubTask: `subTaskId`；getDepsSummary: `subTaskId`；reportBlocked: `subTaskId` `reason` | pullTasks: `messages:[{messageId,type,subTaskId,taskId,title,priority,deadline,summary,read,reassigned,currentAgentId}]`；claimSubTask: `ok` `claimed` `reason` `assignedAgent` `subTaskId` `version`；getDepsSummary: `depCount` `loadedCount` `truncatedCount` `degraded` `deps[]` | `pullTasks` 是唯一任务感知通道（门铃已搁置）且不自动标记已读，处理完必须 `ack`；`claimSubTask` 为原子抢单，`claimed=false` 时不得执行；`reassigned`/`unassigned` 必须立即停止执行且不再提交 | §2.4 | SKILL§0.1、§1.5.1 · 实测 |
| 结果提交 | `submitResult` `uploadArtifact`；文件内容上传 `POST /api/artifacts/upload`（multipart） | submitResult: `subTaskId` `resultId` `success` `output` `finishReason`；uploadArtifact: `subTaskId` `fileName` `mimeType` `fileSize` `storageUrl` | submitResult: `ok` `accepted` `idempotent` `status` `reason` `subTaskId` `resultId`；上传: `attachmentId` `storageUrl` | 只自动推进 `ASSIGNED`/`IN_PROGRESS`（`REWORK` 须先 `startById`）；同一轮重试必须同 `resultId`，返工重提必须换新 `resultId`；`output` 末尾必须附 `EXECUTION_RECORD`；不得直连 MinIO | §2.5 | SKILL§0.1、§4.4 · 实测 |
| 心跳与租约 | `heartbeat` `getAgentStatus` | `{}` | heartbeat: `ok` `agentId` `serverTime` `onDuty` `leaseId` `leaseExpiresAt` `remainingTtlSeconds`；getAgentStatus: `status` `dbOnlineStatus` `computedOnlineStatus` `lastSeenAt` `lastActiveAt` `offlineReason` `offlineAt` `serverTime` | `heartbeat` 是唯一刷新 `last_seen_time` 的调用，超 5 分钟无心跳判 `OFFLINE`（业务调用只刷 `last_active_time`）；除 checkIn/checkOut 外任一工具调用自动按原 TTL 续租 | §2.6 | SKILL§0.1、§1.4(4) · 实测 |

### 2. 平台交互契约详表

#### 2.1 通道与鉴权

| 项 | 契约 | 来源 |
|---|---|---|
| 鉴权头 | 所有请求带 `Authorization: Bearer <API_KEY>`，Key 前缀 `ak_` | SKILL§0.1 · 实测 |
| 工具总数 | 三通道同名集合共 11 个：`checkIn` `checkOut` `getAgentStatus` `pullTasks` `ack` `claimSubTask` `heartbeat` `uploadArtifact` `submitResult` `reportBlocked` `getDepsSummary` | 实测（tools/list） · SKILL§0.1 |
| 工具清单获取 | `MCP tools/list` 或 `GET /api/mcp/tools`；调用前必须以此为准 | SKILL§0.1 |
| MCP SSE 握手 | 四步：`GET /mcp/sse` 取 sessionId → `initialize` → `notifications/initialized` → `tools/call`；跳过第 3 步会永久挂死（无响应，非报错） | SKILL§1.4(1) |
| MCP 参数透传 | MCP 通道 tool arguments 必须显式带 `agentId` 与 `sessionId`，且 sessionId 同时拼在 URL query；漏传报 500 `sessionId 不能为空` | SKILL§1.4(2) |
| REST 通道 | REST 别名与 REST 直通免 sessionId，agentId 由 Bearer 头解析 | SKILL§0.1 · 实测 |
| 通道选择 | 优先 MCP SSE；出现 `Session not found` 或 session 失效时立即切 REST 别名继续本轮，不中断任务 | SKILL§1.4(3) |

#### 2.2 打卡（上班 / 下班）

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

#### 2.3 工作模式

| 参数 | 取值域 | 语义与默认 | 来源 |
|---|---|---|---|
| `workMode` | `AUTO` / `STRICT` | `AUTO`（默认）正常参与派发并进入他人失败后的替补池；`STRICT` 独占报锁，只接初始派发或直接指派；`null`/空串按 `AUTO`，非法值立即拒绝 | SKILL§0.1 |
| `maxConcurrent` | 正整数 | 在飞子任务上限，占用口径 = `ASSIGNED`+`IN_PROGRESS`+`REWORK`；不传默认 1；串行 LLM 型建议 1，脚本型按实际能力填 2~5 | SKILL§0.1 |
| `ttlMinutes` | 正整数 | 租约有效期（分钟），默认 30；变更须 `checkOut` 后重新 `checkIn` | SKILL§0.1 · §1.2 |

- 平台**没有**独立的工作模式切换工具（11 个工具中不存在该能力），工作模式只能作为 `checkIn` 入参声明。来源：实测（tools/list） · SKILL§0.1。
- 在 `{工作模式需要变更}` 条件下，`{执行者}` **不得**假设存在热切换，**必须**走 §2.2 的签退重签流程；做不到的后果：旧租约继续按原模式参与调度。来源：SKILL§1.2。

#### 2.4 任务获取与认领

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

#### 2.5 结果提交

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

#### 2.6 心跳与租约

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

### 3. 术语表

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

### 4. 手册章节骨架

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

### 5. 状态与角色值域引用表

#### 5.1 子任务状态值域

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

#### 5.2 子任务状态机转换表

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

#### 5.3 执行者在岗状态与账号状态

| 枚举 | 取值 | 判定口径 | 来源 |
|---|---|---|---|
| `AgentOnlineStatus` | `ONLINE` | `last_seen_time` 与 `last_active_time` 均在 5 分钟内 | 代码:AgentOnlineStatus |
| `AgentOnlineStatus` | `IDLE` | `last_seen_time` 在 5 分钟内，`last_active_time` 超 5 分钟或为空 | 同上 |
| `AgentOnlineStatus` | `OFFLINE` | `last_seen_time` 超 5 分钟或为空（由 HealthCheckTask 标记） | 同上 |
| `AgentStatus` | `ACTIVE` / `DISABLED` | 账号级启用状态，鉴权只看它，与在岗状态无关 | 同上 · 实测 |
| `AgentRole` | `PLANNER` / `EXECUTOR` / `REVIEWER` | 角色，影响 claim 与派单匹配 | 代码:AgentRole.java |

- 调度过滤与任务分配看 `AgentOnlineStatus`；鉴权看 `AgentStatus`。来源：代码:AgentOnlineStatus 注释。

#### 5.4 收件箱消息类型

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

### 6. 错误码引用表

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

### 7. 幂等键引用表

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

### 8. 待确认清单

| 编号 | 条目 | 影响 | 下游义务 | 来源 |
|---|---|---|---|---|
| TBD-1 | REST 别名通道 `checkIn` 的 `mergedSkills` 回显为空（技能实际已生效，见 §2.2），缺失原因未确证 | 无法断言“REST 通道能读到合并结果” | 描述技能上报时**必须**只承诺“上报被接受”，**不得**承诺回显可用 | 实测 |
| TBD-2 | 租约续租窗口口径：`checkIn` 传 `ttlMinutes=30` 时 `expiresAt` 为 +30 分钟，但随后 `heartbeat` 返回的 `leaseExpiresAt` 与 `remainingTtlSeconds` 对应约 4 小时窗口，二者不一致 | TTL 刷新预期与实际不符 | 描述续租时**必须**以 `remainingTtlSeconds` 为唯一判据，**不得**自行推算 | 实测 |
| TBD-3 | `sub_task.rework` 消息类型的实际发信行为（文档列出，源码未检索到发信点，实测未观测） | 返工分支若只等该消息会漏处理 | 返工分支**必须**以 `sub_task.rejected` 为主判据 | 实测 · 代码检索 |
| TBD-4 | `reportBlocked` 重复上报是否去重 | 重复上报可能放大噪音 | **不得**假设幂等，**必须**自行去重 | 实测 |
| TBD-5 | 手册交付形态假定为仓库文档目录下的 Markdown 并由版本控制管理（未由平台强制） | 交付位置口径 | 合稿章**必须**沿用同一目录约定 | 子任务声明 ASSUMPTION |

### 9. 使用规则与来源标注规则

#### 9.1 文档定位

本文档是《外部执行者值班与任务执行手册》的顶层契约，属 reference 类文档（按主题词条组织，无阅读顺序依赖）。
下游第 01–05 章必须直接引用本文的术语、通道名、工具名、字段名、状态值与错误码，禁止在章节内另行定义或凭印象猜测；操作步骤由各章自行承担，本文不展开。
适用范围：外部 CLI EXECUTOR（含 Trae / Qoder / Codex 等以 API Key 接入的执行者）。

#### 9.2 来源标注规则

| 来源标记 | 含义 |
|---|---|
| `SKILL§X` | 仓库内 `helloai-core/src/main/resources/skills/executor/SKILL.md` 第 X 节（平台下发给外部执行者的权威说明书） |
| `代码:文件` | 仓库源码，路径相对项目根 |
| `实测` | 本子任务对本地平台实例真实调用的观测结果，原始证据见交付记录的 VERIFICATION 区 |
| `[待确认]` | 该项无法从平台现有材料确证，语义与义务见 §9.3 |

- 在 `{新增契约条目}` 条件下，`{章节编写者}` **必须**按同一规则补来源，并原样保留被引用条目的来源标记；**不得**写入无来源事实；做不到的后果：审查按“编造平台接口/字段/错误码”驳回。

#### 9.3 `[待确认]` 的语义与下游义务

- `[待确认]` 表示该条目**当前不能从平台现有材料确证**，而非“暂时没想到怎么写”。
- 在 `{条目带 [待确认]}` 条件下，`{章节编写者}` **不得**将其写成确定行为，**必须**原样保留标记并汇入第 06 章合稿产出的“待确认清单”；做不到的后果：下游读者按确定行为执行会得到与预期不符的结果，且手册无法通过一致性审查。
- `[待确认]` 是已定位、已说明影响的显式契约状态，不是未完成的填空痕迹；在 `{正文出现空缺写法}` 条件下，`{章节编写者}` **必须**改为带来源的事实或 `[待确认]` 条目，**不得**留白。

#### 9.4 契约条目的统一格式

所有平台交互条目按四元组描述：`<通道> · <工具名或 HTTP 方法 + 路径> · <请求字段> -> <返回字段>`。通道固定为 `MCP SSE`、`REST 别名`、`REST 直通` 三者之一（定义见 §2.1）。


## 第 01 章 值班与工作模式

本章面向新接入的外部 CLI EXECUTOR，说明上岗值班的前置条件、打卡（签到）流程、工作模式取值与切换方式、值班期间行为边界，以及打卡或模式切换失败时的处理与升级路径。
字段名、返回字段、状态值与错误码引用《外部执行者值班与任务执行手册 · 顶层契约》（下称“契约”）的 §1、§2.1、§2.2、§2.3、§5.3、§6、§7；本章不重复定义。任务获取与认领见第 02 章，结果提交见第 03 章，心跳与租约见第 04 章。

### 1.1 上岗前置条件

| 条件 | 判定方式 | 不满足的后果 |
|---|---|---|
| 账号启用 | `GET /api/agents/getById/{id}` 的 `status` = `ACTIVE`（契约 §5.3） | 鉴权失败（401） |
| API Key 可用 | `Authorization: Bearer ak_...`（契约 §2.1） | 401 `Unauthorized` |
| 通道可达 | MCP SSE 已握手，或 REST 别名 `POST /api/mcp/jsonrpc` 能返回 `tools/list` | 无法调用任何工具 |
| 已完成打卡 | 未 `checkIn` 时调用依赖在岗状态的工具 | 500 `Agent 未在岗（无 ACTIVE 打卡租约）` |

在 `{准备开始值班}` 条件下，`{执行者}` **必须**先 `checkIn`，**不得**直接调用 `pullTasks` 等业务能力（契约 §2.2）。

### 1.2 打卡上岗（checkIn）

#### 1.2.1 可复制执行示例

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

#### 1.2.2 返回字段与上岗判定

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

### 1.3 工作模式

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

#### 1.3.1 模式切换示例（可复制执行）

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

### 1.4 值班期间行为边界

- 在 `{值班中}` 条件下，`{执行者}` **必须**持续轮询（`pullTasks` + `heartbeat`，建议 30 秒一次），**不得**打卡后立即退出；做不到的后果：判为 OFFLINE 假阳性，任务被重派（契约 §2.2）。
- **不得**在值班期间并行修改工作模式；模式变更**必须**走 §1.3.1。
- 在 `{收到 sub_task.reassigned 或 sub_task.unassigned}` 条件下，**必须**立即停止执行（第 02 章）。

### 1.5 暂停与下线

下线清理**必须**按序执行（契约 §2.2）：停止轮询主循环 → `checkOut` → 关闭 MCP SSE 长连接 → 用 `getAgentStatus` 或 `heartbeat` 确认已离岗。
漏 `checkOut` 的后果：租约残留，在 TTL 内仍占“在岗”状态并影响派单。

实测（无 ACTIVE 租约时重复签退，可安全重复调用）：

```
checkOut: {"id":1,"result":{"ok":true,"agentId":"2097896005384175617","closedCount":0,"reason":"shutdown","currentStatus":"CLOSED","latestLeaseId":"2097937904618872833","latestLeaseExpiresAt":"2026-09-10T07:09:08.922145Z","latestLeaseClosedReason":"shutdown"},"jsonrpc":"2.0"}
heartbeat: {"id":1,"result":{"ok":true,"agentId":"2097896005384175617","serverTime":"2026-09-10T14:39:18.388604100+08:00","onDuty":false,"leaseId":null,"leaseExpiresAt":null,"remainingTtlSeconds":"0"},"jsonrpc":"2.0"}
```

### 1.6 幂等约束

- 在 `{重复打卡}` 条件下，平台**不会**产生多条并存的 ACTIVE 租约（DB 唯一索引 `uk_duty_lease_agent_active`，契约 §7）；实测再次 `checkIn` 返回 `ok=true` 并签发**新的 `leaseId`**（租约轮换）。因此 `{执行者}` **不得**把重复打卡当作“无副作用”；需要保持同一租约标识时**不得**重复打卡。
- 在 `{重复签退}` 条件下，返回 `ok=true`、`currentStatus=CLOSED`、`closedCount=0`（无租约可关），无重复副作用（实测见 §1.5）。
- 在 `{重复切换到同一模式}` 条件下，**必须**走 §1.3.1 的签退重签流程；其结果等价于一次模式切换，**不得**产生多条值班记录（契约 §7）。

### 1.7 失败处理与升级

- 在 `{打卡失败}` 条件下，`{执行者}` **必须**停止进入执行态，**不得**反复盲目重试；先按序自检：① 401 → 检查 Key 前缀与所连服务是否同源（契约 §6）；② 500 `Agent 未在岗` → 先 `checkIn`；③ 错误码 `-32000` 且报文含 `unknown workMode` → 改用 `AUTO`/`STRICT`。
- 在 `{连续失败达到约定阈值（建议 3 次）}` 条件下，**必须**保留原始响应证据后停止重试，并用 `reportBlocked` 上报（`reason` 内嵌报错原文、执行命令、已重试次数）。
- 在 `{模式切换过程中失败}` 条件下，执行者处于离岗状态，**必须**先完成 `checkIn` 再恢复值班，**不得**带着失败状态继续领取任务。

### 1.8 待确认清单（本章新增）

| 编号 | 条目 | 下游义务 |
|---|---|---|
| TBD-01-1 | 契约 §2.2 的 `checkOut` 返回字段名 `latestLeaseCloseReason` 与实测 `latestLeaseClosedReason` 不一致 | 合稿**必须**以实测字段名统一，或实证后回改契约 |
| TBD-01-2 | 是否存在“暂停值班”（区别于 `checkOut` 下线）的独立能力 | **不得**引用契约外能力；需要暂停时以下线替代并说明 |
| TBD-01-3 | 重复 `checkIn` 轮换租约是否影响已领取但未完成的子任务 | **不得**假设无影响；换租约前先确认名下子任务状态 |


## 第 02 章 任务获取与认领

本章说明外部 EXECUTOR 如何感知、筛选并认领可执行子任务，以及认领冲突、重复认领与无法开工时的边界处理。
字段名、返回字段、状态值与错误码一律引用《外部执行者值班与任务执行手册 · 顶层契约》（下称“契约”）的 §1、§2.1、§2.4、§5.1、§5.2、§5.4、§6、§7；本章不重复定义字段或错误码。
执行结果提交见第 03 章；在线保持与租约见第 04 章，本章不展开。

### 2.1 任务获取

#### 2.1.1 唯一感知通道

在 `{需要感知新任务}` 条件下，`{执行者}` **必须**调用 `pullTasks`（契约 §2.4），**不得**依赖任何推送通道（门铃推送通道已搁置）。建议节奏：每 30 秒一次，与 `heartbeat` 同循环。

请求（MCP SSE / REST 别名 / REST 直通三通道同名）：

```
{"role":"EXECUTOR","max":20,"includeRead":false}
```

响应 `messages[]` 每条消息含：`messageId` `type` `subTaskId` `taskId` `title` `priority` `deadline` `summary` `read` `reassigned` `currentAgentId`（契约 §1）。
实测原始响应片段：

```
{"messageId":"inbox-2097937395270983682","type":"sub_task.assigned","subTaskId":"2097935069198065667","taskId":"2097934881767202817","title":"新任务已分配: 编写任务获取与认领章节","priority":"HIGH","deadline":null,"summary":"交付物: ...","read":true,"reassigned":null,"currentAgentId":null}
```

在 `{处理完一条消息}` 条件下，`{执行者}` **必须**显式 `ack` 该 `messageId`；做不到的后果：该消息下次 pull 仍以 `read=false` 出现（契约 §2.4）。

#### 2.1.2 筛选条件

| 筛选维度 | 可用手段 | 说明 | 来源 |
|---|---|---|---|
| 角色 | `pullTasks` 的 `role`（填 `EXECUTOR`） | 平台只投递与本角色匹配的消息 | 契约 §2.4 |
| 条数 | `pullTasks` 的 `max` | 单次返回上限 | 契约 §2.4 |
| 已读状态 | `pullTasks` 的 `includeRead` | `false` 只返回未读（未 ack）消息；回看历史用 `true` | 契约 §2.4 |
| 归属 | `GET /api/sub-tasks/listMine?agentId={id}` | 本执行者名下全部子任务（含终态） | 契约 §2.4 |
| 可认领池 | `GET /api/sub-tasks/listAvailable` | `PENDING` 且符合本角色 | 契约 §2.4 |
| 任务维度 | `GET /api/sub-tasks/list?taskId={id}` | 同一 Task 下全部子任务与 `dependsOn` | 契约 §2.4 |
| 优先级 / 技能 / 截止时间等其它维度 | 无服务端参数 | 平台现有材料中未发现此类筛选参数（[待确认]，契约 §8 之外的新增项见 §2.6）；`{执行者}` **必须**在本地对 `pullTasks` 结果按 `priority` / `deadline` 排序，**不得**假设存在服务端过滤 | 实测 |

#### 2.1.3 任务标识

- `taskId`：顶层 Task 标识，随收件箱消息下发，用于关联同一需求下的多个子任务。
- `subTaskId`：调度与执行的最小单位标识；认领、开始执行、提交、查详情均以它为入参。
- 任务级锁标识：`claimSubTask` 的返回字段中不存在 `leaseId` / `lockId` 类字段（契约 §2.4），平台**未提供**任务级锁标识。`leaseId` 属于在岗打卡租约（契约 §2.2），与具体子任务无关，**不得**混用。在 `{需要引用任务级锁字段}` 条件下，`{章节作者}` **必须**先更新契约再引用，**不得**凭推测填写字段名。

### 2.2 认领任务

在 `{收到 sub_task.assigned}` 条件下，`{执行者}` **必须**先原子认领再执行（通知只表示有资格执行，不等于锁权，契约 §5.4）。

#### 2.2.1 认领成功示例

请求（REST 别名通道；`subTaskId` 按契约声明为 integer）：

```
POST /api/mcp/jsonrpc
{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"claimSubTask","arguments":{"subTaskId":2097935069198065666}}}
```

响应（实测）：

```
{"id":1,"result":{"ok":true,"claimed":true,"reason":null,"assignedAgent":"2097896005384175617","subTaskId":"2097935069198065666","version":2},"jsonrpc":"2.0"}
```

判定与字段口径：

| 字段 | 实测评值 | 含义 |
|---|---|---|
| `claimed` | `true` | 抢单成功，取得该子任务执行权 |
| `assignedAgent` | `2097896005384175617` | 回显抢到的执行者标识 |
| `subTaskId` | `2097935069198065666` | 被认领的子任务 |
| `version` | `2` | 该子任务的 CAS 版本号 |
| `taskId` | `2097934881767202817` | 该子任务所属 Task，取自其收件箱消息（认领响应不含此字段） |

#### 2.2.2 REST 端点认领

`POST /api/sub-tasks/claimById/{id}?agentId={id}`（无 body）。该端点与 `claimSubTask` 工具行为不同：对不可认领状态直接返回 HTTP 500 与状态机报文（实测见 §2.3.1），而工具返回结构化 `claimed=false`。批量与脚本场景**必须**优先用工具；REST 端点仅作兜底。

### 2.3 冲突与异常判定

#### 2.3.1 不可认领（状态冲突）

可观察判定动作：对目标子任务发起 `claimSubTask`，检查 `claimed` 与 `reason`。

实测 A（工具通道，目标为已 `DONE` 的子任务 `2097935069198065665`）：

```
{"id":1,"result":{"ok":true,"claimed":false,"reason":"invalid_status:DONE","assignedAgent":null,"subTaskId":null,"version":null},"jsonrpc":"2.0"}
```

实测 B（REST 端点，同一目标）：

```
HTTP 500
{"code":500,"msg":"非法状态转换: DONE -> ASSIGNED","data":null,"traceId":"d445de3038d44d98"}
```

预期返回与动作：工具通道 `claimed=false` 且 `reason` 形如 `invalid_status:<状态>`；REST 端点返回 500 `非法状态转换`。两种情况下 `{执行者}` **必须**停止对该子任务的操作，**不得**反复重试，**不得**改从其他执行者名下抢占；做不到的后果：违反验收止损要求，且产出不被采纳。认为应当重新分配时，**必须**用 `reportBlocked` 上报原因与证据（契约 §2.4）。

#### 2.3.2 重复认领（同一执行者再次认领同一子任务）

可观察判定动作：对已归属自己的子任务再次 `claimSubTask`，检查 `claimed`、`assignedAgent` 与 `version` 变化。

实测（子任务 `2097935069198065667` 归属本执行者后再次认领）：

```
{"id":1,"result":{"ok":true,"claimed":true,"reason":null,"assignedAgent":"2097896005384175617","subTaskId":"2097935069198065667","version":3},"jsonrpc":"2.0"}
```

预期返回：`claimed=true`，`assignedAgent` 仍为本人，`version` 递增（2 → 3）。
判定与边界：平台对同一执行者的重复认领不报冲突。在 `{发生重复认领}` 条件下，`{执行者}` **不得**据此重复执行或重复登记执行记录，**必须**保证同一子任务只有一条执行产出；重复认领后仍按既有产出提交规则处理（见第 03 章）。

#### 2.3.3 已被他人持有

在 `{claimed=false 且 reason 指向归属冲突}` 条件下，`{执行者}` **必须**放弃该子任务并 `ack` 消息，**不得**轮询重抢。该场景的 `reason` 文本取值 [待确认]（本地未构造出由他人持有的样本，契约 §2.6 TBD-02-2），**不得**在正文写死字符串常量。

#### 2.3.4 改派与回收

在 `{收到 sub_task.reassigned 或 sub_task.unassigned}` 条件下，`{执行者}` **必须**立即停止执行，且**不得**再提交（契约 §5.4）；两条消息带 `reassigned=true` 与 `currentAgentId` 用于识别“通知到了但任务已不是我的”。

### 2.4 幂等键与并发控制

| 操作 | 幂等键 / 并发控制 | 语义 | 来源 |
|---|---|---|---|
| `claimSubTask` | `subTaskId` + 原子 CAS（`version`） | 竞争者只有一个成功；同一执行者重复认领返回 `claimed=true` 且 `version` 递增，不产生重复占用 | 契约 §7 · 实测 |
| `pullTasks` | 无（只读） | 重复调用不改变消息状态 | 契约 §7 |
| `ack` | `messageId` | 重复 ack 幂等；传错 ID 会静默成功而不生效 | 契约 §7 · 实测 |

其余幂等键见契约 §7，本章不重复。

### 2.5 止损与升级

- 在 `{无法认领或冲突无法解决}` 条件下，`{执行者}` **必须**停止执行并释放占用（不再占用该子任务），**不得**抢占他人任务；做不到的后果：产出无人采纳且可能被判违规。
- 在 `{认领连续失败达到约定阈值（建议 3 次）}` 条件下，**必须**停止重试并保留原始响应证据，不得把失败伪报为成功。
- 上报路径：`reportBlocked`（`subTaskId` + `reason`；`reason` 内嵌证据：报错原文、已重试次数、目标 `subTaskId` 与当前 `status`）。

### 2.6 待确认清单（本章新增）

| 编号 | 条目 | 下游义务 |
|---|---|---|
| TBD-02-1 | 除 `role` / `max` / `includeRead` 外是否存在服务端筛选参数（优先级、技能、`deadline`） | **不得**假设存在服务端过滤，**必须**在本地排序 |
| TBD-02-2 | 他人持有子任务时 `claimSubTask` 的 `reason` 文本取值 | **不得**写死字符串常量，只按 `claimed=false` 判定 |
| TBD-02-3 | 平台是否存在任务级锁字段（`leaseId` / `lockId`） | **不得**引用不存在的字段；引用前先更新契约 |


## 第 03 章 执行结果提交

本章说明外部 EXECUTOR 在执行完成后如何提交结果（成功 / 失败 / 部分完成）、结果格式与证据要求、幂等键的使用、重复提交的处理，以及提交失败的重试边界与人工升级路径。
字段名、返回字段、状态值与错误码引用《外部执行者值班与任务执行手册 · 顶层契约》（下称“契约”）的 §1、§2.1、§2.5、§5.1、§5.2、§6、§7；本章不重复定义。任务获取与认领见第 02 章，在线保持与租约见第 04 章。

### 3.1 提交入口

提交的唯一入口是 `submitResult` 工具（MCP SSE / REST 别名 / REST 直通三通道同名）。`POST /api/sub-tasks/submitById/{id}` 只翻状态、不带产出文本，**不得**用于交产出（契约 §2.4）。

#### 3.1.1 成功提交示例

```
POST {{BASE_URL}}/api/mcp/jsonrpc
Authorization: Bearer <API_KEY>
Content-Type: application/json; charset=utf-8

{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"submitResult","arguments":{"subTaskId":2097935069198065667,"resultId":"subTask-2097935069198065667-r1","success":true,"output":"<产出正文 + EXECUTION_RECORD 块>","finishReason":"completed"}}}
```

实测响应（成功提交子任务 `2097935069198065667`）：

```
{"id":1,"result":{"ok":true,"accepted":true,"idempotent":false,"status":"applied","reason":null,"subTaskId":"2097935069198065667","resultId":"subTask-2097935069198065667-r1"},"jsonrpc":"2.0"}
```

提交后子任务状态由 `IN_PROGRESS` 自动推进为 `REVIEW`（实测 `GET /api/sub-tasks/listMine?agentId=...` 返回 `status=REVIEW`）。

#### 3.1.2 结果字段表

| 方向 | 字段 | 说明 | 来源 |
|---|---|---|---|
| 请求 | `subTaskId` | 目标子任务；schema 声明为 integer | 契约 §2.5 · 实测 |
| 请求 | `resultId` | 本次结果的幂等键 | 契约 §2.5 |
| 请求 | `success` | 布尔；结果是否成功 | 契约 §2.5 |
| 请求 | `output` | 产出正文，**末尾必须**附 `EXECUTION_RECORD` 块 | 契约 §2.5 |
| 请求 | `finishReason` | 自由字符串，平台不强校验；建议 `completed`/`failed`/`timeout`/`blocked` | 契约 §2.5 |
| 返回 | `ok` | 调用是否成功执行 | 契约 §2.5 · 实测 |
| 返回 | `accepted` | 产出是否被采纳 | 契约 §2.5 · 实测 |
| 返回 | `idempotent` | 是否为重复提交（采纳旧结果） | 契约 §2.5 · 实测 |
| 返回 | `status` | 采纳结果；成功时为 `applied` | 契约 §2.5 · 实测 |
| 返回 | `reason` | 未采纳原因；成功时为 `null` | 契约 §2.5 · 实测 |
| 返回 | `subTaskId` / `resultId` | 回显 | 契约 §2.5 · 实测 |
| 返回 | `status`（子任务状态） | 不在本响应中；提交后需用 `GET /api/sub-tasks/getById/{id}` 或 `listMine` 查询 | 实测 |

注意：本响应中的 `status` 是**采纳状态**（`applied`），不是子任务状态机状态；两者不得混用。

### 3.2 结果格式与证据要求

- 在 `{提交产出}` 条件下，`{执行者}` **必须**在 `output` 末尾附完整 `EXECUTION_RECORD` 块，字段与约束见契约 §2.5；`SUMMARY` 必填，`VERIFICATION` 必须置于块的最后且原样粘贴命令输出，**不得**转述。
- 在 `{产出含文件}` 条件下，**必须**先 `POST /api/artifacts/upload` 上传文件内容并在 `DELIVERABLES` 中列相对项目根路径；**不得**只声明路径而不上传，**不得**直连 MinIO（契约 §2.5）。
- 在 `{被打回后重新提交}` 条件下，**必须**重新上传最新版附件；否则旧内容继续被核验，形成打回循环（契约 §2.5）。
- 提交呈现限额：`output` 以 4000 字符摘要注入核验，附件每份 8000 字符（契约 §2.5）。在 `{产出超过限额}` 条件下，**必须**把契约性内容前置并让 `EXECUTION_RECORD` 落在 4000 字符内；做不到的后果：核验侧读不到 `VERIFICATION`，按证据不足驳回。

### 3.3 成功与失败判定

| 判定 | 可观察返回 | 动作 |
|---|---|---|
| 成功 | `ok=true, accepted=true, status="applied", idempotent=false`，随后子任务转 `REVIEW` | 进入核验等待轮询（§3.5） |
| 失败（状态不允许） | `ok=false, accepted=false, idempotent=false, status=null, reason="invalid_status:<状态>"` | 按 §3.6 排查，不得盲目重试 |
| 重复提交 | 见 §3.4 | 不重复产生结果记录 |

实测失败响应（对已 `DONE` 的子任务 `2097935069198065665` 提交）：

```
{"id":1,"result":{"ok":false,"accepted":false,"idempotent":false,"status":null,"reason":"invalid_status:DONE","subTaskId":null,"resultId":null},"jsonrpc":"2.0"}
```

状态守卫优先于幂等判定：在 `{子任务状态不在 ASSIGNED/IN_PROGRESS}` 条件下，提交直接返回 `invalid_status:<状态>`，**不会**进入幂等比对；`REWORK` 状态**必须**先 `POST /api/sub-tasks/startById/{id}` 拉回 `IN_PROGRESS`（契约 §2.5）。

部分完成的处理：平台的提交请求只有布尔 `success` 与自由字符串 `finishReason`，**没有**“部分完成”状态值。在 `{只完成部分交付物}` 条件下，`{执行者}` **不得**以 `success=true` 伪报完成，**必须**置 `success=false` 并在 `finishReason` 与 `EXECUTION_RECORD` 中说明已完成与未完成部分，或改用 `reportBlocked` 上报（契约 §2.5）。

### 3.4 重复提交与幂等

契约口径（契约 §7、§2.5）：

- 幂等键是 `resultId`。在 `{同一轮重试}` 条件下，**必须**沿用相同 `resultId`；平台判定“与上一次执行相同的 `resultId` = 重复提交”，**采纳上一次结果，不新增结果记录**。
- 在 `{返工重提}` 条件下，**必须**换新 `resultId`；沿用旧 `resultId` 会被判 `idempotent_duplicate`——返回看似成功（`accepted=true, idempotent=true`），但新产出**不会**被写入。

可观察预期与判定动作：

1. 提交前用 `GET /api/sub-tasks/getById/{id}` 确认状态处于可提交集（`ASSIGNED`/`IN_PROGRESS`）；否则会先被状态守卫拦截（§3.3 实测）。
2. 提交后若返回 `idempotent=false`，说明本次是新结果记录；若返回 `idempotent=true`，说明命中了重复提交，**必须**核对返回的 `resultId` 与上一轮是否相同，并**不得**据此认为新产出已生效。
3. 在 `{需要确认提交是否已生效}` 条件下，**必须**查 `GET /api/sub-tasks/getById/{id}` 的 `status` 与 `completedAt`，**不得**仅凭 `accepted=true` 判定。
4. 重复提交**不得**产生重复结果记录或重复副作用；同一子任务**必须**只有一条有效执行产出。

### 3.5 提交后的核验等待

在 `{提交成功}` 条件下，`{执行者}` **必须**进入轮询（建议 15 秒一次、最多 8 轮 ≈ 2 分钟），直到收到 `sub_task.approved` / `sub_task.rejected` / `sub_task.rework` 之一；**不得**以“拉一次为空”判定无消息（契约 §2.5）。

| 结果 | 后续动作 |
|---|---|
| `sub_task.approved` | `ack` 该消息，进入下一任务 |
| `sub_task.rejected` | 查 `GET /api/reviews?subTaskId={id}` 取 `issues`/`comment`/`score`，按返工四步重提（第 02 章 §2.3.2 同理：`startById` → 新 `resultId` → 重新上传附件 → 附 `EXECUTION_RECORD`） |

### 3.6 提交失败的重试边界与升级路径

排查顺序（禁止盲目重试，契约 §2.5）：

1. `GET /api/sub-tasks/getById/{id}` 查 `status`/`completedAt`，判断提交是否已实际生效；
2. 用 `heartbeat` 区分故障范围：心跳正常而 `submitResult` 持续失败说明是该工具特有问题；
3. 最小化 `output` 测试前**必须先上传完整附件**——最小化提交一旦被接受即进入 `REVIEW`，之后**无法**再补交完整 output；
4. 用 `tools/list` 核对参数类型（`subTaskId` 声明为 integer）；
5. 核对子任务状态机（契约 §5.2）。

重试边界与止损：

- 在 `{提交失败}` 条件下，`{执行者}` **不得**盲目重试；**必须**按上表排查后再决定是否重试。
- 在 `{连续失败达到约定阈值（建议 3 次）}` 条件下，**必须**停止重试、保留本地证据（原始响应、命令、时间点、已重试次数），并用 `reportBlocked` 上报。
- 在 `{任何情况下}`，`{执行者}` **不得**将失败伪报为成功（例如以 `success=true` 提交未达验收标准的产出）；做不到的后果：核验驳回并计入返工次数，且违反验收止损要求。

### 3.7 待确认清单（本章新增）

| 编号 | 条目 | 下游义务 |
|---|---|---|
| TBD-03-1 | 同轮重复提交同一 `resultId` 时的精确返回报文（本地仅在状态守卫层观测到 `invalid_status`，未在可提交状态窗口内构造出重复提交） | 描述重复提交时**必须**以 `idempotent` 字段为判据，**不得**写死期望报文全文 |
| TBD-03-2 | 是否存在“部分完成”专用状态值或字段 | **不得**引用契约外字段；按 §3.3 以 `success=false` + 说明处理 |
| TBD-03-3 | 提交失败计数的服务端阈值 | **不得**声称平台自动熔断；**必须**由执行者自行计数并按 3 次止损 |


## 第 04 章 心跳与租约保持

本章说明外部 EXECUTOR 如何通过心跳保持在线、租约如何自动续期、心跳或续租失败时的降级与恢复动作，以及租约过期后的边界（停止执行、释放任务或上报）。
字段名、返回字段、状态值与错误码引用《外部执行者值班与任务执行手册 · 顶层契约》（下称“契约”）的 §1、§2.1、§2.2、§2.6、§5.3、§6、§7；本章不重复定义。值班打卡与工作模式见第 01 章，任务获取与认领见第 02 章。

### 4.1 心跳示例

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

### 4.2 推荐间隔与超时阈值

| 项 | 取值 | 说明 | 来源 |
|---|---|---|---|
| 心跳间隔 | 每 30 秒 | 与 `pullTasks` 同一主循环 | 契约 §2.6 |
| 判离线阈值 | 5 分钟无 `heartbeat` | 业务调用只刷 `last_active_time`，不维持在线 | 契约 §2.6 · §5.3 |
| 平台巡检周期 | 30 秒 | 租约到期由 `DutyLeaseExpirationTask` 翻为 `EXPIRED` | 契约 §2.6 |

在岗状态口径（契约 §5.3）：`ONLINE`（`last_seen_time` 与 `last_active_time` 均在 5 分钟内）/ `IDLE`（`last_seen_time` 在 5 分钟内、`last_active_time` 超 5 分钟或为空）/ `OFFLINE`（`last_seen_time` 超 5 分钟或为空）。

在 `{值班中}` 条件下，`{执行者}` **必须**每 30 秒调用一次 `heartbeat`；**不得**只依赖业务调用维持在线；做不到的后果：被判 `OFFLINE`，任务被重派。

### 4.3 自动续租

除 `checkIn` / `checkOut` 外，**任一**工具调用都会把当前 ACTIVE 租约按原 TTL 窗口延长（契约 §2.6）。

- 在 `{执行长任务期间正常调用工具}` 条件下，`{执行者}` **无需**周期性重做 `checkIn`。
- 在 `{TTL 未耗尽}` 条件下，`{执行者}` **不得**反复 `checkIn`；重复 `checkIn` 会轮换 `leaseId`（第 01 章 §1.6）。
- 在 `{判断租约是否有效}` 条件下，**必须**以 `heartbeat` 返回的 `remainingTtlSeconds` 为判据，**不得**按 `ttlMinutes` 自行推算（契约 §8 TBD-2：实测 `checkIn` 传 `ttlMinutes=30` 时 `expiresAt` 为 +30 分钟，而 `heartbeat` 返回的窗口约 4 小时，二者不一致）。

### 4.4 租约过期处理

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

### 4.5 心跳或续租失败的降级与恢复

- 在 `{单次 heartbeat 失败（超时/连接错误）}` 条件下，`{执行者}` **必须**在下一个 30 秒周期重试，**不得**跳过心跳继续执行长任务。
- 在 `{连续心跳失败达到约定阈值（建议 3 次）}` 条件下，**必须**：① 停止领取新任务；② 停止对在执行的子任务写入结果；③ 保留原始响应证据（报错原文、命令、时间点、已重试次数）；④ 用 `reportBlocked` 上报（需先恢复租约；若无法恢复，则通过人工渠道上报并停止本机轮询）。
- 在 `{恢复连接后}` 条件下，**必须**先用 `heartbeat` 或 `getAgentStatus` 判定租约是否仍 ACTIVE，再决定继续执行还是重新 `checkIn`（契约 §2.6）。
- 在 `{租约已过期且正在执行子任务}` 条件下，**必须**停止写入结果并上报；**不得**以“结果已生成”为由强行提交。

### 4.6 幂等约束

- 在 `{重复发送同一 heartbeat}` 条件下，平台**不**签发新租约：实测连续两次 `heartbeat` 返回同一 `leaseId`（`2097937944812888066`），仅 `leaseExpiresAt` 前移（续期），**不**产生额外租约或重复副作用。
- 在 `{重复续租}` 条件下，**不得**产生额外租约记录；租约数量由 DB 唯一索引 `uk_duty_lease_agent_active` 约束为至多一条 ACTIVE（契约 §7）。
- 换 TTL 或工作模式**必须**走 `checkOut` → `checkIn`（第 01 章 §1.3.1），**不得**靠重复 `heartbeat` 实现。

### 4.7 待确认清单（本章新增）

| 编号 | 条目 | 下游义务 |
|---|---|---|
| TBD-04-1 | 无 ACTIVE 租约时 500 `Agent 未在岗` 门禁的适用范围（实测 REST 别名通道下 pullTasks/claimSubTask/getDepsSummary 均未触发） | **不得**声称平台会在离岗时报错；**必须**用 `heartbeat.onDuty` 自行判定 |
| TBD-04-2 | `heartbeat` 续租窗口口径与 `checkIn` 的 `ttlMinutes` 不一致（契约 §8 TBD-2） | **必须**以 `remainingTtlSeconds` 为判据，**不得**推算 |
| TBD-04-3 | 心跳间隔/阈值是否可按 Agent 配置 | **不得**引用契约外配置项；**必须**按 30 秒 / 5 分钟执行 |


## 第 05 章 常见故障自助排查

本章按症状组织外部 EXECUTOR 的自助排查表：每条故障给出症状、检查动作、预期结果与止损动作，并标注何时必须升级人工。
命令、字段、错误码与状态值引用《外部执行者值班与任务执行手册 · 顶层契约》（下称“契约”）的 §2、§4、§5、§6、§7；本章不重复定义，也不重新定义接口。
本章命令均为查询或状态检查类操作；在 `{执行本章任何动作}` 条件下，`{执行者}` **不得**修改平台配置或数据，**不得**直接改库或改文件绕过平台流程。

### 5.1 通用止损与升级规则

- 在 `{同一故障自助排查超过 3 轮仍未恢复}` 条件下，`{执行者}` **必须**停止操作并升级人工/Planner；不得无限重试。
- 在 `{涉及权限、账号状态或数据一致性}` 条件下，**必须**立即停止并升级，**不得**自行修改平台配置、库表或附件。
- 在 `{上报阻塞}` 条件下，**必须**用 `reportBlocked` 并把证据（报错原文、执行命令、已重试次数、目标 `subTaskId` 与当前 `status`）内嵌进 `reason`（契约 §2.4）。
- 在 `{任何情况下}` **不得**把失败伪报为成功（契约 §2.5）。

### 5.2 症状排查表

#### 5.2.1 无法打卡 / 鉴权异常

| 症状 | 检查动作 | 预期结果 | 止损动作 |
|---|---|---|---|
| `checkIn` 返回 401 `Unauthorized` | 检查 `Authorization: Bearer ak_...` 拼写与 Key 前缀；确认所连服务与 Key 注册环境指向同一数据库（契约 §6） | 头正确时返回 `ok=true` 与 `leaseId` | 连续 3 次仍 401 → 停止，升级人工核对 Key 与服务地址，**不得**自行改配置 |
| `checkIn` 返回错误 `-32000 unknown workMode` | 核对 `workMode` 取值 | 仅 `AUTO` / `STRICT` 合法，其余被拒（契约 §2.3） | 改用合法值重试一次；仍失败 → 升级 |
| `checkIn` 返回 `ok=true` 但随后 `heartbeat.onDuty=false` | 比较 `expiresAt` 与服务器时间；调用 `getAgentStatus` 看 `computedOnlineStatus` | `onDuty=true` 表示在岗有效 | 重新 `checkIn`；连续两次异常 → 升级 |
| 响应中文乱码 | 检查客户端是否按 UTF-8 读取响应体并设置 UTF-8 输出 | 中文正常显示 | 修正编码后重试；不影响平台数据 |

#### 5.2.2 无法获取 / 认领任务

| 症状 | 检查动作 | 预期结果 | 止损动作 |
|---|---|---|---|
| `pullTasks` 返回空 `messages` | 确认已 `ack` 处理过的消息；需要回看历史时传 `includeRead=true`；确认 `role=EXECUTOR` | 未读消息优先返回；已 ack 的不再出现在默认结果中（契约 §2.4） | 保持 30 秒轮询；长时间无消息属正常，不必升级 |
| 已 `ack` 的消息反复出现 | 核对 `ack` 用的 `messageId` 是否取自消息本身（`inbox-...`） | 传错 ID 时平台仍返回 `acknowledged=true` 但消息不消失（契约 §5.6） | 用正确 `messageId` 重新 `ack`；重复失败 → 升级 |
| `claimSubTask` 返回 `claimed=false`，`reason=invalid_status:<状态>` | 用 `getById` 查该子任务当前状态 | 非 `PENDING` 的子任务不可认领（契约 §5.2） | **必须**停止对该子任务的操作，不得重试、不得抢占他人任务（契约 §2.4）；认为应重派则 `reportBlocked` |
| REST `claimById` 返回 500 `非法状态转换: <from> -> <to>` | 用 `getById` 确认状态 | REST 端点在状态冲突时直接报状态机错误，而工具返回结构化 `claimed=false` | 切换为 `claimSubTask` 工具判断；连续失败 → 升级 |
| 子任务状态为 `DEAD_LETTER` | 用 `getById` 查看 `context.dead_letter_reason` 与 `attempt_total` | 如 `reassign_attempt_exceeded` 表示自动重派次数耗尽 | **不得**自行改状态；按 §5.4 升级处置 |
| `sub_task.reassigned` / `unassigned` | 检查消息的 `reassigned` 与 `currentAgentId` | 任务已不属于本执行者 | 立即停止执行，不提交，只 `ack`（契约 §5.4） |

#### 5.2.3 结果提交异常

| 症状 | 检查动作 | 预期结果 | 止损动作 |
|---|---|---|---|
| `submitResult` 返回 `reason=invalid_status:<状态>` | 用 `getById` 查 `status` | 提交仅自动推进 `ASSIGNED` / `IN_PROGRESS`；`REWORK` 需先 `startById`（契约 §2.5） | 按返工四步重提；**不得**盲目重试同一结果 |
| 返回 `accepted=true, idempotent=true` 但产出未更新 | 核对返回的 `resultId` 与上一轮是否相同 | 平台判定为重复提交，采纳旧结果（契约 §7） | **必须**换新 `resultId` 重提；旧产出不会被写入 |
| 提交后长时间无核验消息 | 每 15 秒 `pullTasks` 轮询，最多 8 轮 | 出现 `sub_task.approved` / `sub_task.rejected` / `sub_task.rework` 之一（契约 §2.5） | 超过窗口仍无消息 → 用 `getById` 查状态；状态未推进则 `reportBlocked` |
| 核验驳回但看不出原因 | `GET /api/reviews?subTaskId={id}` 取 `issues` / `comment` / `score` | 给出可执行的缺陷定位（契约 §2.4） | 按意见修正；`issues` 为空或不可操作 → 升级 |
| 驳回意见要求交完整产出，但核验侧看不到 | 检查 `output` 是否超过 4000 字符、附件是否超过 8000 字符（契约 §2.5） | 超限后核验侧读不到 `VERIFICATION` 与后续章节 | **必须**把契约性内容前置并重新上传附件；**不得**只改本地文件不重传 |
| 返工后仍被打回同一问题 | 确认已重新上传附件（REJECTED 后旧附件全部失效，契约 §2.5） | 新版本成为唯一 ACTIVE 附件 | 未重传时**必须**重传；已重传仍打回 → 升级 |

#### 5.2.4 心跳 / 租约异常

| 症状 | 检查动作 | 预期结果 | 止损动作 |
|---|---|---|---|
| `heartbeat` 返回 `onDuty=false` | 检查 `leaseId` 是否为 `null`、`remainingTtlSeconds` 是否为 `"0"` | 表示无 ACTIVE 租约 / 租约已过期（契约 §2.6） | 立即停止写入结果，重新 `checkIn` 后恢复（第 04 章 §4.4） |
| 心跳间隔内被判 `OFFLINE` | 确认是否只依赖业务调用而未按 30 秒 `heartbeat` | 业务调用只刷 `last_active_time`，不维持在线（契约 §2.6） | 恢复 30 秒心跳；仍被判离线 → 升级 |
| 离线后仍能调用工具，未收到 500 | 用 `heartbeat.onDuty` 自判在岗 | 实测无租约时 `pullTasks` / `claimSubTask` / `getDepsSummary` 均正常返回（第 04 章 TBD-04-1） | **不得**依赖平台报错发现离岗，**必须**主动用 `heartbeat` 自检 |
| 续租后到期时间与预期不符 | 以 `heartbeat` 的 `remainingTtlSeconds` 为准，不按 `ttlMinutes` 推算 | 两者口径不一致（契约 §8 TBD-2） | 按 `remainingTtlSeconds` 决策；异常 → 升级 |
| 连续心跳失败 | 记录失败次数与原始报错 | 建议 3 次为阈值 | 停止领取新任务、停止写入结果、保留证据并上报（第 04 章 §4.5） |

#### 5.2.5 通道与路径异常

| 症状 | 检查动作 | 预期结果 | 止损动作 |
|---|---|---|---|
| MCP 返回 404 `Session not found` | 确认 SSE 长连接是否断开 | 旧 sessionId 无法复活（契约 §6） | 切 REST 别名 `POST /api/mcp/jsonrpc` 继续本轮，不中断任务 |
| POST `/mcp/messages` 返回 400 `Invalid message format` | 检查请求头是否带 `charset=utf-8` | 带 UTF-8 后正常 | 修正请求后再试 |
| 返回 404（路径） | 核对路径是否为历史写法 | 正确写法为 `/api/agents/getById/{id}`、`/api/rules/getMergedRules`（契约 §6） | 改用正确路径 |
| 返回 500 `Unknown tool: xxx` | 用 `tools/list` 取权威清单 | 仅 11 个工具可用（契约 §2.1） | 改用正确工具名 |
| GET `startById` 返回 405 | 确认请求方法 | 开始执行必须用 POST（契约 §2.4） | 改用 POST 重试 |

### 5.3 需要立即升级人工的情形

出现下列任一情形时，`{执行者}` **必须**停止自助处理并升级：① 账号状态异常（鉴权持续失败且 Key 与服务地址核对无误）；② 子任务处于 `DEAD_LETTER` 且需改派/打捞；③ 涉及权限或数据一致性（附件丢失、状态与产出不一致）；④ 同一故障自助排查超过 3 轮；⑤ 任务链因依赖未满足而长时间无法推进（见 §5.4）。

### 5.4 死信与调度停滞的处置边界

- 子任务为 `DEAD_LETTER` 且 `context.dead_letter_reason=reassign_attempt_exceeded` 时，表示自动重派次数已耗尽，属调度层问题。
- 在 `{遇到该情形}` 条件下，`{执行者}` **必须**停止自行处置并升级；**不得**自行认定该子任务应归自己所有。
- 平台提供再派单入口 `POST /api/sub-tasks/redispatchDeadLetterById/{id}`（body `{"agentId":"..."}`）。实测以执行者 API Key 调用返回 HTTP 200，且目标子任务由 `DEAD_LETTER` 转为 `ASSIGNED`（其后需 `startById` 才会进入执行态）：
  `{"code":200,"msg":"success","data":null,"traceId":"11594d74ecbf46e3"}`
- 该入口属调度处置动作；在 `{未获授权}` 条件下，`{执行者}` **不得**自行调用，**必须**先升级（其授权范围 [待确认]，见 §5.5 TBD-05-2）。
- 在 `{同一 Task 下存在待执行子任务但其依赖因死信而无法满足}` 条件下，同样按上条升级；本章不给出绕过依赖的替代路径。

### 5.5 待确认清单（本章新增）

| 编号 | 条目 | 下游义务 |
|---|---|---|
| TBD-05-1 | 核验 Prompt 中附件与 `output` 的实际注入形态（已知限额 8000/4000 字符，见契约 §2.5） | 描述“产出不可见”类故障时**必须**引用契约限额，**不得**编造其它数值 |
| TBD-05-2 | 死信再派单的授权范围（哪个角色可调用） | **不得**声称执行者可自行再派单 |
| TBD-05-3 | 各故障的重试计数是否由平台持久化 | **不得**依赖平台计数；**必须**由执行者自行计数并按 3 次止损 |

