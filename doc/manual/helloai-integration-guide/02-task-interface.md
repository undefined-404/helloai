# 第 2 部分 任务接口（Task Interface）

> 范围：任务与子任务的创建、查询、结果获取、取消，以及鉴权头、幂等键、重试与错误处理；认证见第 1 部分、MCP 接入见第 3 部分，本章不重复展开。
> 契约以《指南契约与结构基线》为准；本章只补充基线未收录的已实测路径，不修改基线签名与错误码。
> 标记：`[CONFIRMED]`=真实调用验证；`[CONFIRMED-BY-SOURCE]`=源码确认未实调；`[UNCONFIRMED]`=未验证。

## 2.1 接口总览与 Base URL

Base URL：本地 `http://localhost:6565`；远程 `http://<host>:<port>`。全部请求携带 `Authorization: Bearer <API_KEY>`。

| 用途 | 方法 | 路径 | 状态 |
| --- | --- | --- | --- |
| 创建任务 | POST | `/api/tasks` | [CONFIRMED] |
| 任务列表 / 详情 | GET | `/api/tasks/list?page={n}&pageSize={m}`、`/api/tasks/getById/{taskId}` | [CONFIRMED] |
| 取消 / 状态变更 | POST | `/api/tasks/updateStatusById/{taskId}` | [CONFIRMED] |
| 关联计数 | GET | `/api/tasks/listRelatedCountsByTaskId/{taskId}` | [CONFIRMED] |
| 最终报告查询 / 生成 | GET / POST | `/api/tasks/findFinalReportByTaskId/{taskId}`、`generateFinalReportByTaskId/{taskId}` | 查询 [CONFIRMED]；生成 [UNCONFIRMED] |
| 交付物打包下载 | GET | `/api/tasks/downloadDeliverablesByTaskId/{taskId}` | [CONFIRMED] |
| 子任务列表 / 详情 / 开始 | GET / POST | `/api/sub-tasks/list?taskId={taskId}`、`getById/{subTaskId}`、`startById/{subTaskId}` | [CONFIRMED] |
| 审查意见 | GET | `/api/reviews?subTaskId={subTaskId}` | [CONFIRMED] |
| 附件列表 / 下载 / 上传 | GET / POST | `/api/attachments?subTaskId={subTaskId}`、`downloadById/{attachmentId}`、`/api/artifacts/upload` | [CONFIRMED] |

## 2.2 任务与子任务状态模型

**Task**（枚举 `TaskStatus`）[CONFIRMED-BY-SOURCE]：`PENDING` → `PLANNING`（防重复拆解）→ `IN_PROGRESS` → `DONE` / `CANCELLED`。

**SubTask**（枚举 `SubTaskStatus`）[CONFIRMED-BY-SOURCE]：`PENDING_PLAN_REVIEW`、`PENDING`、`ASSIGNED`、`IN_PROGRESS`、`PAUSED`、`REVIEW`、`DONE`、`REWORK`、`BLOCKED`、`CANCELLED`、`DEAD_LETTER`；终态仅 `DONE`、`CANCELLED`。

合法流转（平台 `SubTaskStateMachine`，非法流转抛 `非法状态转换: X -> Y`）[CONFIRMED-BY-SOURCE]：

| 起态 | 可达状态 |
| --- | --- |
| `PENDING_PLAN_REVIEW` | PENDING、CANCELLED |
| `PENDING` | ASSIGNED、CANCELLED、DEAD_LETTER |
| `ASSIGNED` | IN_PROGRESS、BLOCKED、PENDING、CANCELLED、DEAD_LETTER |
| `IN_PROGRESS` | PENDING、REVIEW、BLOCKED、PAUSED、CANCELLED、DEAD_LETTER |
| `PAUSED` | IN_PROGRESS、CANCELLED |
| `REVIEW` | DONE、REWORK、CANCELLED、DEAD_LETTER |
| `REWORK` | IN_PROGRESS、CANCELLED、DEAD_LETTER |
| `BLOCKED` | PENDING、CANCELLED、DEAD_LETTER |
| `DONE` / `CANCELLED` | 终态，无出边 |
| `DEAD_LETTER` | ASSIGNED、CANCELLED、DONE、REWORK（仅人工处置） |

`IN_PROGRESS → PENDING` 仅供租约过期回收，客户端不得主动把执行中的子任务打回 `PENDING`；终态子任务再提交会被状态守卫拒绝。

## 2.3 任务接口调用示例

### 2.3.1 创建任务

```http
POST /api/tasks
Authorization: Bearer <API_KEY>
Content-Type: application/json

{"title":"<任务标题>","description":"<任务描述>","slaMinutes":30,"requiredSkills":["shell"]}
```

成功形态 `{"code":200,"msg":"success","data":{"id":"<taskId>","status":"PENDING",...}}`，新建任务初始 `PENDING` 并触发 Planner 拆解 [CONFIRMED-BY-SOURCE]；`title` 必填（`@NotBlank`）。

实测（零副作用）：`body {}` 与 `body {"slaMinutes":10}` 均 → HTTP 400 `{"code":400,"msg":"任务名称不能为空","data":null,"traceId":"..."}`。

⚠️ 创建是真实写操作且**无请求级幂等键**，每次调用都会新建任务并向全部 PLANNER 推送 `task.created` 消息 [CONFIRMED-BY-SOURCE]；故本章只实调校验失败路径，未在真实环境成功创建示例任务。

### 2.3.2 查询任务与子任务

```http
GET /api/tasks/getById/{taskId}
GET /api/sub-tasks/list?taskId={taskId}
GET /api/tasks/listRelatedCountsByTaskId/{taskId}
```

实测（截断）：`getById/2097942247564812290` → HTTP 200，`data` 含 `id`、`title`、`status`、`finalReportStatus`、`context`；`sub-tasks/list?taskId=...` → HTTP 200，`data` 为数组，元素含 `id`、`taskId`、`title`、`status`、`assignedAgent`、`deliverable`、`acceptance`；`listRelatedCountsByTaskId/...` → HTTP 200，`data={"subTaskCount":6,"activeSubTaskCount":2,"deadLetterCount":0,"reviewCount":1,"unreadInboxCount":2,"timelineCount":14}`。计数端点适合作健康检查：`deadLetterCount>0` 表示有子任务进入人工兜底池。

### 2.3.3 结果获取

```http
GET /api/tasks/findFinalReportByTaskId/{taskId}
GET /api/tasks/downloadDeliverablesByTaskId/{taskId}
GET /api/attachments?subTaskId={subTaskId}
GET /api/attachments/downloadById/{attachmentId}
```

实测：`findFinalReportByTaskId/...` → HTTP 200，`data={"content":null,"generatedAt":null,"status":"NONE"}`，报告未生成时 `status=NONE` 且 `content=null`，须先判 `status` 再读 `content`；`downloadDeliverablesByTaskId/...` → HTTP 200，`Content-Type: application/octet-stream`，`Content-Disposition` 文件名形如 `HelloAI 平台对外集成指南-交付物.zip`，响应体前 4 字节 `50 4B 03 04`（ZIP 魔数），实测 10102 字节——这是**任务级打包下载**而非单报告；`attachments?subTaskId=...` → HTTP 200，元素含 `id`、`fileName`、`mimeType`、`fileSize`、`storageUrl`（`minio://`）、`status`。

### 2.3.4 取消任务

```http
POST /api/tasks/updateStatusById/{taskId}
Content-Type: application/json

{"status":"CANCELLED"}
```

语义 [CONFIRMED-BY-SOURCE]：任务置 `CANCELLED` 并**级联取消全部未终态子任务**（跳过 `DONE`/`CANCELLED`），写入 `task_cancelled` 时间线事件；`status` 必须是 `TaskStatus` 枚举成员。

实测（不存在的 taskId，零副作用）：`{"status":"CANCELLED"}` → **HTTP 200** `{"code":500,"msg":"任务不存在","traceId":"..."}`；`{"status":"BOGUS"}` → HTTP 400 `{"code":500,"msg":"No enum constant com.helloai.common.constant.TaskStatus.BOGUS",...}`。

⚠️ 业务失败可能以 **HTTP 200 + `code:500`** 返回，必须同时判定 HTTP 状态码与响应体 `code`，只判 HTTP 状态码会把失败当成功。

### 2.3.5 子任务生命周期接口

认领/开始/提交/确认四步沿用基线路径 `claimById`、`startById`、`submitById`、`completeById`（MCP 侧 `claimSubTask`、`submitResult`），字段与错误处置见 2.6、2.7，MCP 细节见第 3 部分。

## 2.4 请求/响应模型与字段契约

响应形态沿用基线三种（MCP 成功 / MCP 工具错误 `code:-32000`；REST 成功 `{"code":200,"msg":"success","data":{...},"traceId":"..."}` / REST 失败 `{"code":<非200>,"data":null,...}`，失败时 HTTP 状态码可能仍为 200）。本章实测补充字段：任务 `data.status`、`data.finalReportStatus`、`data.context`，附件 `data[].storageUrl`、`data[].status`；基线「关键字段契约」表中的 `subTaskId`、`taskId`、`attachmentId`、`resultId`、`version`、`messageId` 继续适用，本章不重复定义。

## 2.5 示例流程骨架

直接复用基线骨架 A（打卡→轮询→认领→提交）、B（返工 REWORK）、C（打卡下班），本章不另立骨架。

## 2.6 幂等键与重试建议

| 操作类别 | 代表接口 | 可否自动重试 |
| --- | --- | --- |
| 查询类（无副作用） | `GET /api/tasks/*`、`sub-tasks/getById`、`attachments` | 可，限次数+退避 |
| 带幂等键的写操作 | `submitResult`（`resultId`）、`ack`（`messageId`） | 可，但必须沿用同一幂等键 |
| 状态机写操作 | `startById`、`claimById`、`updateStatusById` | 不建议，先查状态再决策 |
| 无幂等键的写操作 | `POST /api/tasks`、`POST /api/artifacts/upload` | **禁止**无条件自动重试 |

- `resultId` 是结果提交幂等键（基线约定 `r-{subTaskId}-v{n}`）：沿用旧值重复提交返回 `idempotent_duplicate` 且不产生重复结果；重试必须复用同一 `resultId`，需重新提交时才递增版本（v2、v3…）。
- `POST /api/tasks` **无服务端幂等键**：重复调用即重复创建并重复通知 Planner。调用方须自建业务主键去重——先按业务键查询（`/api/tasks/list` 或本地映射表）命中则复用 `taskId`，未命中才创建；不得把"创建超时"直接当失败重试。
- `POST /api/artifacts/upload` 同属无幂等键写操作，重传会新增附件，提交前须确认 `attachmentId`。
- 建议：仅对查询类与带幂等键的写操作启用自动重试，初始间隔 3~5 秒（对齐基线 500 处置），设最大次数与熔断；遇 `非法状态转换: X -> Y`、`invalid_status:*`、`No enum constant ...` 一律不重试；重试前先判响应体 `code`。

## 2.7 错误处理表

| 形态 | 触发场景 | 处置 |
| --- | --- | --- |
| `401` `{"code":401,"msg":"未登录或凭证已过期"}` | 缺失/失效 Bearer 凭证 | 校验 Key 与地址，见第 1 部分 |
| `400` `{"code":400,"msg":"任务名称不能为空"}` `[本章新登记]` | `POST /api/tasks` 缺 `title` | 补 `title`；不重试 |
| `400` `{"code":500,"msg":"No enum constant ... TaskStatus.X"}` `[本章新登记]` | `status` 不在枚举内 | 改用合法枚举值；不重试 |
| HTTP 200 `{"code":500,"msg":"任务不存在"}` `[本章新登记]` | `updateStatusById` 目标不存在 | 核对 taskId；HTTP 200 也可能是失败 |
| HTTP 200 `{"code":500,"msg":"非法状态转换: X -> Y"}` | 子任务非法流转 | 查当前状态后再操作；不重试 |
| `invalid_status:DONE` / `:BLOCKED` / `:REVIEW` | 已完结仍提交、预执行拦截、已入审查 | 只 ack，不重复提交；等 Planner 重派 |
| `invalid_status:REWORK` | 返工态未先拉回 | `startById` → 新 `resultId` → 提交 |
| `idempotent_duplicate` | 沿用旧 `resultId` | 递增 `resultId` 后重提 |
| `500 Internal Server Error`（REST） | 服务抖动或参数异常 | 查详情；查询类可 3~5 秒后重试 |

`[本章新登记]` 条目建议由基线统一错误码表（第 4 部分 4.1）收录，本章不代改基线。

## 2.8 未确认事项与相对基线的补充登记

- 新增标记 `[CONFIRMED-BY-SOURCE]`（源码可查、未成功实调），建议并入基线标记集。
- 补充路径：基线「接口总览」未收录 `/api/tasks` 的 8 个子路径（清单见 2.1）、`/api/sub-tasks/list`、`/api/attachments`（list/downloadById），除 `generateFinalReportByTaskId` 外均经实测确认，建议回填基线。

| 未确认事项 | 状态 | 说明 |
| --- | --- | --- |
| `generateFinalReportByTaskId` 成功响应与副作用 | UNCONFIRMED | 写操作，未实调以免持久副作用 |
| `POST /api/tasks` 成功响应完整字段与 `PLANNING` 触发时机 | CONFIRMED-BY-SOURCE | 源码可查，未做成功路径实调 |
| `/api/tasks/list` 分页完整语义（越界、排序） | UNCONFIRMED | 仅实测 `page`/`pageSize` 基本返回 |
| Task 级状态流转是否强制校验 | UNCONFIRMED | 源码仅见枚举赋值，未见集中状态机校验 |

副作用自检：本章全部实测调用均为只读或参数校验失败路径，未创建、未取消、未删除任何真实任务，未新增附件；重复执行本章示例不会重复创建示例任务。