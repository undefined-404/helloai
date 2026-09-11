# 第 05 章 常见故障自助排查

本章按症状组织外部 EXECUTOR 的自助排查表：每条故障给出症状、检查动作、预期结果与止损动作，并标注何时必须升级人工。
命令、字段、错误码与状态值引用《外部执行者值班与任务执行手册 · 顶层契约》（下称“契约”）的 §2、§4、§5、§6、§7；本章不重复定义，也不重新定义接口。
本章命令均为查询或状态检查类操作；在 `{执行本章任何动作}` 条件下，`{执行者}` **不得**修改平台配置或数据，**不得**直接改库或改文件绕过平台流程。

## 5.1 通用止损与升级规则

- 在 `{同一故障自助排查超过 3 轮仍未恢复}` 条件下，`{执行者}` **必须**停止操作并升级人工/Planner；不得无限重试。
- 在 `{涉及权限、账号状态或数据一致性}` 条件下，**必须**立即停止并升级，**不得**自行修改平台配置、库表或附件。
- 在 `{上报阻塞}` 条件下，**必须**用 `reportBlocked` 并把证据（报错原文、执行命令、已重试次数、目标 `subTaskId` 与当前 `status`）内嵌进 `reason`（契约 §2.4）。
- 在 `{任何情况下}` **不得**把失败伪报为成功（契约 §2.5）。

## 5.2 症状排查表

### 5.2.1 无法打卡 / 鉴权异常

| 症状 | 检查动作 | 预期结果 | 止损动作 |
|---|---|---|---|
| `checkIn` 返回 401 `Unauthorized` | 检查 `Authorization: Bearer ak_...` 拼写与 Key 前缀；确认所连服务与 Key 注册环境指向同一数据库（契约 §6） | 头正确时返回 `ok=true` 与 `leaseId` | 连续 3 次仍 401 → 停止，升级人工核对 Key 与服务地址，**不得**自行改配置 |
| `checkIn` 返回错误 `-32000 unknown workMode` | 核对 `workMode` 取值 | 仅 `AUTO` / `STRICT` 合法，其余被拒（契约 §2.3） | 改用合法值重试一次；仍失败 → 升级 |
| `checkIn` 返回 `ok=true` 但随后 `heartbeat.onDuty=false` | 比较 `expiresAt` 与服务器时间；调用 `getAgentStatus` 看 `computedOnlineStatus` | `onDuty=true` 表示在岗有效 | 重新 `checkIn`；连续两次异常 → 升级 |
| 响应中文乱码 | 检查客户端是否按 UTF-8 读取响应体并设置 UTF-8 输出 | 中文正常显示 | 修正编码后重试；不影响平台数据 |

### 5.2.2 无法获取 / 认领任务

| 症状 | 检查动作 | 预期结果 | 止损动作 |
|---|---|---|---|
| `pullTasks` 返回空 `messages` | 确认已 `ack` 处理过的消息；需要回看历史时传 `includeRead=true`；确认 `role=EXECUTOR` | 未读消息优先返回；已 ack 的不再出现在默认结果中（契约 §2.4） | 保持 30 秒轮询；长时间无消息属正常，不必升级 |
| 已 `ack` 的消息反复出现 | 核对 `ack` 用的 `messageId` 是否取自消息本身（`inbox-...`） | 传错 ID 时平台仍返回 `acknowledged=true` 但消息不消失（契约 §5.6） | 用正确 `messageId` 重新 `ack`；重复失败 → 升级 |
| `claimSubTask` 返回 `claimed=false`，`reason=invalid_status:<状态>` | 用 `getById` 查该子任务当前状态 | 非 `PENDING` 的子任务不可认领（契约 §5.2） | **必须**停止对该子任务的操作，不得重试、不得抢占他人任务（契约 §2.4）；认为应重派则 `reportBlocked` |
| REST `claimById` 返回 500 `非法状态转换: <from> -> <to>` | 用 `getById` 确认状态 | REST 端点在状态冲突时直接报状态机错误，而工具返回结构化 `claimed=false` | 切换为 `claimSubTask` 工具判断；连续失败 → 升级 |
| 子任务状态为 `DEAD_LETTER` | 用 `getById` 查看 `context.dead_letter_reason` 与 `attempt_total` | 如 `reassign_attempt_exceeded` 表示自动重派次数耗尽 | **不得**自行改状态；按 §5.4 升级处置 |
| `sub_task.reassigned` / `unassigned` | 检查消息的 `reassigned` 与 `currentAgentId` | 任务已不属于本执行者 | 立即停止执行，不提交，只 `ack`（契约 §5.4） |

### 5.2.3 结果提交异常

| 症状 | 检查动作 | 预期结果 | 止损动作 |
|---|---|---|---|
| `submitResult` 返回 `reason=invalid_status:<状态>` | 用 `getById` 查 `status` | 提交仅自动推进 `ASSIGNED` / `IN_PROGRESS`；`REWORK` 需先 `startById`（契约 §2.5） | 按返工四步重提；**不得**盲目重试同一结果 |
| 返回 `accepted=true, idempotent=true` 但产出未更新 | 核对返回的 `resultId` 与上一轮是否相同 | 平台判定为重复提交，采纳旧结果（契约 §7） | **必须**换新 `resultId` 重提；旧产出不会被写入 |
| 提交后长时间无核验消息 | 每 15 秒 `pullTasks` 轮询，最多 8 轮 | 出现 `sub_task.approved` / `sub_task.rejected` / `sub_task.rework` 之一（契约 §2.5） | 超过窗口仍无消息 → 用 `getById` 查状态；状态未推进则 `reportBlocked` |
| 核验驳回但看不出原因 | `GET /api/reviews?subTaskId={id}` 取 `issues` / `comment` / `score` | 给出可执行的缺陷定位（契约 §2.4） | 按意见修正；`issues` 为空或不可操作 → 升级 |
| 驳回意见要求交完整产出，但核验侧看不到 | 检查 `output` 是否超过 4000 字符、附件是否超过 8000 字符（契约 §2.5） | 超限后核验侧读不到 `VERIFICATION` 与后续章节 | **必须**把契约性内容前置并重新上传附件；**不得**只改本地文件不重传 |
| 返工后仍被打回同一问题 | 确认已重新上传附件（REJECTED 后旧附件全部失效，契约 §2.5） | 新版本成为唯一 ACTIVE 附件 | 未重传时**必须**重传；已重传仍打回 → 升级 |

### 5.2.4 心跳 / 租约异常

| 症状 | 检查动作 | 预期结果 | 止损动作 |
|---|---|---|---|
| `heartbeat` 返回 `onDuty=false` | 检查 `leaseId` 是否为 `null`、`remainingTtlSeconds` 是否为 `"0"` | 表示无 ACTIVE 租约 / 租约已过期（契约 §2.6） | 立即停止写入结果，重新 `checkIn` 后恢复（第 04 章 §4.4） |
| 心跳间隔内被判 `OFFLINE` | 确认是否只依赖业务调用而未按 30 秒 `heartbeat` | 业务调用只刷 `last_active_time`，不维持在线（契约 §2.6） | 恢复 30 秒心跳；仍被判离线 → 升级 |
| 离线后仍能调用工具，未收到 500 | 用 `heartbeat.onDuty` 自判在岗 | 实测无租约时 `pullTasks` / `claimSubTask` / `getDepsSummary` 均正常返回（第 04 章 TBD-04-1） | **不得**依赖平台报错发现离岗，**必须**主动用 `heartbeat` 自检 |
| 续租后到期时间与预期不符 | 以 `heartbeat` 的 `remainingTtlSeconds` 为准，不按 `ttlMinutes` 推算 | 两者口径不一致（契约 §8 TBD-2） | 按 `remainingTtlSeconds` 决策；异常 → 升级 |
| 连续心跳失败 | 记录失败次数与原始报错 | 建议 3 次为阈值 | 停止领取新任务、停止写入结果、保留证据并上报（第 04 章 §4.5） |

### 5.2.5 通道与路径异常

| 症状 | 检查动作 | 预期结果 | 止损动作 |
|---|---|---|---|
| MCP 返回 404 `Session not found` | 确认 SSE 长连接是否断开 | 旧 sessionId 无法复活（契约 §6） | 切 REST 别名 `POST /api/mcp/jsonrpc` 继续本轮，不中断任务 |
| POST `/mcp/messages` 返回 400 `Invalid message format` | 检查请求头是否带 `charset=utf-8` | 带 UTF-8 后正常 | 修正请求后再试 |
| 返回 404（路径） | 核对路径是否为历史写法 | 正确写法为 `/api/agents/getById/{id}`、`/api/rules/getMergedRules`（契约 §6） | 改用正确路径 |
| 返回 500 `Unknown tool: xxx` | 用 `tools/list` 取权威清单 | 仅 12 个工具可用（契约 §2.1） | 改用正确工具名 |
| GET `startById` 返回 405 | 确认请求方法 | 开始执行必须用 POST（契约 §2.4） | 改用 POST 重试 |

## 5.3 需要立即升级人工的情形

出现下列任一情形时，`{执行者}` **必须**停止自助处理并升级：① 账号状态异常（鉴权持续失败且 Key 与服务地址核对无误）；② 子任务处于 `DEAD_LETTER` 且需改派/打捞；③ 涉及权限或数据一致性（附件丢失、状态与产出不一致）；④ 同一故障自助排查超过 3 轮；⑤ 任务链因依赖未满足而长时间无法推进（见 §5.4）。

## 5.4 死信与调度停滞的处置边界

- 子任务为 `DEAD_LETTER` 且 `context.dead_letter_reason=reassign_attempt_exceeded` 时，表示自动重派次数已耗尽，属调度层问题。
- 在 `{遇到该情形}` 条件下，`{执行者}` **必须**停止自行处置并升级；**不得**自行认定该子任务应归自己所有。
- 平台提供再派单入口 `POST /api/sub-tasks/redispatchDeadLetterById/{id}`（body `{"agentId":"..."}`）。实测以执行者 API Key 调用返回 HTTP 200，且目标子任务由 `DEAD_LETTER` 转为 `ASSIGNED`（其后需 `startById` 才会进入执行态）：
  `{"code":200,"msg":"success","data":null,"traceId":"11594d74ecbf46e3"}`
- 该入口属调度处置动作；在 `{未获授权}` 条件下，`{执行者}` **不得**自行调用，**必须**先升级（其授权范围 [待确认]，见 §5.5 TBD-05-2）。
- 在 `{同一 Task 下存在待执行子任务但其依赖因死信而无法满足}` 条件下，同样按上条升级；本章不给出绕过依赖的替代路径。

## 5.5 待确认清单（本章新增）

| 编号 | 条目 | 下游义务 |
|---|---|---|
| TBD-05-1 | 核验 Prompt 中附件与 `output` 的实际注入形态（已知限额 8000/4000 字符，见契约 §2.5） | 描述“产出不可见”类故障时**必须**引用契约限额，**不得**编造其它数值 |
| TBD-05-2 | 死信再派单的授权范围（哪个角色可调用） | **不得**声称执行者可自行再派单 |
| TBD-05-3 | 各故障的重试计数是否由平台持久化 | **不得**依赖平台计数；**必须**由执行者自行计数并按 3 次止损 |