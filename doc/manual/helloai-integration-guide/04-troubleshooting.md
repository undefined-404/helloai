# 第 4 部分 排查（Troubleshooting）

> 范围：认证失败、任务调用失败、MCP 连接/调用失败、限流与权限不足的问题定位与处置；边界只做问题定位，不重复第 1–3 部分的接口完整定义。
> 标记：`[实证]`=2026-09-10 真实调用/源码确认；`[待确认]`=未验证，不得作为确定结论引用；`[冲突]`=与已交付章节不一致，须回退修订。
> 约束：不编造未确认的错误原因；不新增契约基线之外的错误码；不重写第 1–3 部分正文。

## 4.1 统一错误码表

下表为**汇总视图**：前半段逐条对应《指南契约与结构基线》「统一错误码表」原文；后半段为已在第 2 部分 2.7 登记并实测观察到的形态，本章不新增未登记条目。

| 响应形态 | 归类 | 含义 | 处置 |
| --- | --- | --- | --- |
| `401 Unauthorized` `未登录或凭证已过期` | 认证失败 | API Key 无效 / 服务地址错 / 环境不一致 | 核对 Key、地址、环境 |
| `500 Internal Server Error`（REST） | 服务端 | 服务抖动或参数异常 | 查详情；瞬时可 3~5 秒后重试 |
| `invalid_status:DONE` | 状态机 | 子任务已完结 | 只 ack，不重复提交 |
| `invalid_status:BLOCKED` | 状态机 | 平台预执行拦截 | 只 ack 继续轮询，等 Planner 重派 |
| `invalid_status:REWORK` | 状态机 | 返工态需先拉回 | `startById` → 新 `resultId` → 提交 |
| `invalid_status:REVIEW` | 状态机 | 已入审查 | 上传附件后最小化提交兜底 |
| `idempotent_duplicate` | 幂等 | 沿用旧 `resultId` | 使用递增 `resultId`（v2、v3…） |
| `success is required` | 参数 | `submitResult` 缺 `success` | 补 `success:true` |
| `claimed:false, reason:invalid_status:BLOCKED` | 认领 | 认领被拦截 | 只 ack，等待重派 |
| `非法状态转换: X -> Y` | 状态机 | 非法流转 | 检查当前状态后再操作 |
| `400` `任务名称不能为空` | 参数 | `POST /api/tasks` 缺 `title` | 补 `title`；不重试（已登记：2.7） |
| `400` `No enum constant ...TaskStatus.X` | 参数 | `status` 不在枚举内 | 改用合法枚举值；不重试（已登记：2.7） |
| HTTP 200 + `{"code":500,"msg":"任务不存在"}` | 业务 | 目标 taskId 不存在 | 核对 ID；**HTTP 200 也可能是失败**（已登记：2.7） |
| `race_condition_or_invalid_status` | 认领 | 并发认领失败 / 状态已变 | 放弃该任务，拉取其他任务（已交付：第 3 部分 4.4） |
| `already_claimed_by_other` / `not_task_owner` | 归属 | 任务已被他人认领 / 非归属者提交 | 核对凭证与任务归属（已交付：第 3 部分 4.4） |
| JSON-RPC `error.code:-32000` | MCP | 工具参数/取值不合法 | 读 `error.message`，修正参数；不重试 |

## 4.2 常见失败模式与处置

排查表列定义：**症状**（可观察现象）→ **可能原因** → **检查动作 / 日志字段**（含预期结果）→ **解决动作** → **关联错误码**。

### 4.2.1 认证类

| 症状 | 可能原因 | 检查动作 / 日志字段（预期结果） | 解决动作 | 关联错误码 |
| --- | --- | --- | --- | --- |
| 任意接口返回 401 | 缺 `Authorization` 头、`Bearer ` 前缀缺失或 Key 失效 | 回看请求头；`heartbeat` 自检（预期 `ok=true`、`agentId` 与本端一致） | 补齐头格式；更换有效 Key；见第 1 部分 | `401 未登录或凭证已过期` [实证] |
| 心跳 `agentId` 与预期不符 | Key 与 Agent 身份不匹配（Key 绑身份，`arguments.agentId` 不改变身份） | `heartbeat` 返回的 `agentId` | 换用本 Agent 的 Key | 基线「认证头与凭证字段」 |
| 握手/长连接异常 | MCP 传输会话断开（与租约相互独立） | `getAgentStatus`（预期返回在线态与 `lastSeenAt`） | 重连后先自检租约，再决定是否重新 `checkIn` | 基线 §1.5 |

### 4.2.2 任务接口类

| 症状 | 可能原因 | 检查动作 / 日志字段（预期结果） | 解决动作 | 关联错误码 |
| --- | --- | --- | --- | --- |
| 创建任务返回 400 | `title` 为空或非字符串 | 回看请求体；响应体 `msg`（预期 `任务名称不能为空`） | 补 `title` 后重建；不要盲目重试 | `400 任务名称不能为空` [实证] |
| 调用成功但任务未变更 | 业务失败被 HTTP 200 掩盖 | 同时读 HTTP 状态码与响应体 `code`（预期 `code=200` 才算成功） | 按 `msg` 定位；以 `code` 为准 | HTTP 200 + `code:500` [实证] |
| 状态变更报非法流转 | 当前状态不允许该目标状态 | `GET /api/sub-tasks/getById/{id}` 读 `status`；对照第 2 部分 2.2 流转表 | 走合法路径（如 REWORK 先 `startById`） | `非法状态转换: X -> Y` [实证] |
| 提交被拒 | 子任务已 DONE/REVIEW 或非归属者 | `getById` 读 `status`、`assignedAgent`；`listTimelineBySubTaskId` 读 `payload.idempotencyKey` | 按 4.1 处置；非归属者核对凭证 | `invalid_status:*`、`not_task_owner` |
| 结果“提交了但查不到” | `resultId` 沿用旧值被幂等拦截 | `listTimelineBySubTaskId`（预期出现 `sub_task_execute_submit` 且有 `idempotencyKey`） | 递增 `resultId` 后重提 | `idempotent_duplicate` |
| 交付物下载拿不到内容 | 误当成单报告接口 | `GET /api/tasks/downloadDeliverablesByTaskId/{id}` 查 `Content-Type`（预期 `application/octet-stream`、文件名 `...-交付物.zip`） | 按 ZIP 解包；单报告走 `findFinalReportByTaskId` | —（第 2 部分 2.3.3） |

### 4.2.3 MCP 类

| 症状 | 可能原因 | 检查动作 / 日志字段（预期结果） | 解决动作 | 关联错误码 |
| --- | --- | --- | --- | --- |
| JSON-RPC 返回 `error` 而非 `result` | 工具名错或参数取值非法 | 读 `error.code` 与 `error.message`（预期含合法取值提示） | 修正工具名/参数 | `-32000` [实证] |
| 手动拼接 JSON 报错 | JSON 转义丢失导致解析失败 | 响应是否为统一错误体且含 `traceId`（预期含） | 改用标准序列化库；带 `traceId` 上报 | HTTP 500（JSON 解析）[实证] |
| 认领返回 `claimed:false` | 并发竞争失败或状态已变 | 读 `reason`（预期 `race_condition_or_invalid_status`） | 放弃该任务，不重试，拉取其他任务 | 见 4.1 |
| 工具调用无响应/超时 | 客户端超时过短或载荷过大 | 客户端超时配置（建议 ≥30s；大 `output` ≥60s） | 调高超时后重试（须确认请求未受理） | —（非平台错误码） |

### 4.2.4 限流 / 权限类

| 症状 | 可能原因 | 检查动作 / 日志字段（预期结果） | 解决动作 | 关联错误码 |
| --- | --- | --- | --- | --- |
| 在岗但长时间收不到新任务 | 并发额度占满，调度不再派单 | 统计自身 `ASSIGNED`/`IN_PROGRESS`/`REWORK` 子任务数（`listMine`），与 `checkIn` 返回的 `maxConcurrent` 比较（预期占用 < 额度） | 先完成/交回在飞子任务；额度由 `checkIn` 的 `maxConcurrent` 决定 | 无错误码：**不派单而非报错** [实证] |
| 平台无错误却调用被拒 | 业务级授权不足（认证已过） | `401` 是否出现（预期不出现＝认证已过）；其余以业务返回定位 | 联系管理侧确认授权 | [待确认] |
| 怀疑被外部入口限流 | 未见平台对外 REST/MCP 入口限流实现 | 源码检索未见 `RateLimiter`/`429` 处理；响应是否出现 429（预期不出现） | 客户端侧自限速与退避；阈值向平台确认 | [待确认] |

> 额度口径 [实证]：额度取 ACTIVE 值班租约的 `maxConcurrent`（`checkIn` 显式承诺）> capabilities 的 `maxConcurrentTasks`；占用为 `ASSIGNED`/`IN_PROGRESS`/`REWORK`，任务终态后自动释放。本机实测：`maxConcurrent=1`、在飞 1（编写常见问题排查章节），故在此期间不会收到新派单——这不是故障。

## 4.3 日志与活动记录

可观察面（全部实测 HTTP 200，只读）：

| 用途 | 调用 | 关键字段 |
| --- | --- | --- |
| 子任务事件轨迹 | `GET /api/agent-events/traceBySubTaskId/{subTaskId}` | `eventId`、`runId`、`turn`、`step`、`eventType`、`agentId`、`payload`、`createTime` |
| 任务/运行轨迹 | `GET /api/agent-events/traceByTaskId/{taskId}`、`traceByRunId/{runId}` | 同上 |
| 任务审计分页 | `GET /api/agent-events/pageAuditByTaskId/{taskId}` | 审计条目 |
| 子任务时间线 | `GET /api/sub-tasks/listTimelineBySubTaskId/{subTaskId}` | `eventType`（如 `sub_task_dispatch_prepare`、`sub_task_execute_submit`）、`role`、`agentId`、`payload.idempotencyKey` |
| 子任务对话记录 | `GET /api/sub-tasks/listConversationBySubTaskId/{subTaskId}` | 会话消息 |
| 活动记录 | `GET /api/activity/list?page={n}&pageSize={m}` | 分页 `list`/`total`/`pages` |
| 请求溯源 | 任意 REST 失败响应体 | `traceId`（上报时必带） |

实测示例：`traceBySubTaskId/2097942377135251460` 返回 `eventType=agent_completed`、`payload.executor=cli_client`、`payload.finishReason=completed`；`listTimelineBySubTaskId/...` 返回 `sub_task_execute_submit` 且 `payload.idempotencyKey=subTask-2097942377135251460-r1`。

## 4.4 排障协作流程

1. **最小排查路径**（任何问题先走这三步）：① `heartbeat` 自检 `onDuty` 与 `agentId`；② 复现请求并记录 HTTP 状态码 + 响应体 `code`/`msg` + `traceId`；③ 用 `getById` / `listTimelineBySubTaskId` 读当前状态与最近事件。
2. **止损**：同一问题自助排查超过 3 轮仍未定位，停止重试并升级；涉及权限、数据一致性、疑似凭证泄露时立即升级，不得自行修改平台数据或配置。
3. **上报要素**：请求（方法/路径/请求体）、响应原文、`traceId`、子任务 ID、发生时间、已排除项。不得只贴"调用失败"。
4. **冲突回退**：若发现本指南章节之间互相矛盾（见 4.5），停止按矛盾内容操作，按"以实证 + 基线为准"判断，并把问题回退给对应章节的责任方修订；**不得在本章内改写其他章节正文**。
5. **不得编造**：无法确认根因时标注 `[待确认]` 并给出最小排查路径，不得写成确定结论。

## 4.5 与已交付章节的冲突登记（须回退修订）

**C-1 鉴权模型描述不一致 `[冲突]`**：

- 已交付表述：第 1 部分 2.1 把 `Admin Token` 列作「管理侧调用任务 REST 接口（创建/拆解/确认）」的凭证；第 3 部分 4.1 表述为「管理侧接口使用 `X-Admin-Token`，二者不可混用通道」。
- 实测与源码事实：平台鉴权拦截器按序接受两种凭证——先 `X-Admin-Token`，其次 `Authorization: Bearer <Agent API Key>`，二者**走同一鉴权入口**，任一有效即放行。以本执行者 Agent Key（仅 Bearer，无 Admin Token）调用 `POST /api/tasks` 与 `GET /api/tasks/getById/{id}` 均通过认证层（返回 400 参数错误 / 200 业务响应，而非 401）；仅移除凭证时才返回 401。
- 影响：集成方可能误以为任务 REST 接口不能用 Agent Key 调用，从而做多余的管理侧集成；"不可混用通道"的表述与实际不符。
- 处置建议：回退修订第 1 部分 2.1 与第 3 部分 4.1，改为"两类凭证均通过同一鉴权入口，任一有效即放行；凭证类型不构成通道隔离"，并保留"业务级授权差异未验证"的 `[待确认]`。本章不代改其正文。

## 4.6 未确认事项

| 事项 | 状态 | 最小排查路径 |
| --- | --- | --- |
| 平台对外 REST/MCP 的实际限流阈值与 429 行为 | UNCONFIRMED | 压测单一接口观察是否出现 429 / 延迟抬升；阈值向平台确认 |
| 业务级授权规则（认证通过后是否有角色限制） | UNCONFIRMED | 用 Agent Key 调用管理侧接口观察是否被拒；以源码未见角色校验为参考 |
| 平台日志文件位置与保留策略 | UNCONFIRMED | 以 4.3 的 API 面替代文件日志；文件路径向平台确认 |
| 常见错误根因的完整清单 | UNCONFIRMED | 以本章排查表为起点，按 4.4 上报要素补充 |

重复执行说明：本章排查表为固定条目，重复查阅或重复执行其中的**只读**检查动作不会产生重复条目或副作用；写操作的重复风险见 2.6 与 4.1 的幂等处置。