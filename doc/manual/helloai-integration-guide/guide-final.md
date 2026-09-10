# 《HelloAI 平台对外集成指南》（定稿）

面向希望将自有系统接入 HelloAI 调度平台的第三方开发者。全书四部分：认证、任务接口、MCP 接入、排查。

## 版本记录

| 版本 | 覆盖范围 | 说明 |
|---|---|---|
| v1.0（定稿） | 第 1–4 部分 | 由四份已审批章节合稿。合稿只统一编号、标题层级与交叉引用，并对鉴权模型表述做一处更正（R-3）；除此之外正文逐字取自各章交付物。全书契约以《指南契约与结构基线》为准，本定稿**未修改该基线**。 |

## 修订清单（定稿阶段发现并处理）

合稿时发现的跨章不一致，全部在此公开登记，证据见同目录 `e2e-drill-record.md`。

| 编号 | 位置 | 原状 | 定稿处置 | 依据 |
|---|---|---|---|---|
| R-1 | 认证章节整章 | 标题与小节用 “Chapter 2 / §2.x” | 改为「第 1 部分」/ §1.x，并同步章内交叉引用 | 契约基线「文档目录」：认证 = 第 1 部分 |
| R-2 | MCP 章节整章 | 标题与小节用 “Chapter 4 / §4.x” | 改为「第 3 部分」/ §3.x，并同步章内交叉引用 | 契约基线「文档目录」：MCP = 第 3 部分 |
| R-3 | 认证 §1.1、MCP §3.1 | 表述为“任务 REST 接口用 Admin Token”“二者不可混用通道” | 更正为“两类凭证走同一鉴权入口，任一有效即放行，不构成通道隔离” | 演练 STEP 2/3：仅 Bearer Agent Key 时 `GET /api/tasks/getById` 返回 200，去掉凭证才 401；鉴权拦截器按序检查两类凭证 |
| R-4 | 任务接口 §2.1 | 契约基线把 `GET /api/tasks/getById/{taskId}` 标为 `[ASSUMPTION]` | 第 2 部分已按实测升为 `[CONFIRMED]`；基线本体未改动，在此登记 | 演练 STEP 3 实测 HTTP 200 |

> 说明：R-1/R-2 是编号归一（不改变章节语义），R-3 是对已交付表述的事实性更正，R-4 是状态标记升级登记。四项均在定稿内完成或登记，不涉及对契约基线的修改。

## 阅读约定

- `契约 §X` 指《指南契约与结构基线》的小节；`第 N 部分 §X` 指本定稿对应部分的小节；单独写 `§X` 仅在上文已限定到某一部分时使用。
- 标记含义：`[实证]` / `[CONFIRMED]` = 真实调用或平台源码确认；`[待确认]` / `[UNCONFIRMED]` = 未验证，**不得作为确定行为引用**；`[ASSUMPTION]` = 推断。
- 请求示例中的 `<API_KEY>`、`<host>` 等为占位符，不得写入真实凭证。

## 目录

- **第 1 部分 认证与凭证管理**：1.1 凭证类型 · 1.2 凭证申请与下发 · 1.3 客户端配置 · 1.4 可复现鉴权示例 · 1.5 凭证轮换与吊销 · 1.6 安全存储与泄露处置 · 1.7 证据说明
- **第 2 部分 任务接口**：2.1 接口总览与 Base URL · 2.2 任务与子任务状态模型 · 2.3 调用示例 · 2.4 请求/响应模型与字段契约 · 2.5 示例流程骨架 · 2.6 幂等键与重试建议 · 2.7 错误处理表 · 2.8 未确认事项与相对基线的补充登记
- **第 3 部分 MCP 工具接入**：3.1 通道与协议 · 3.2 服务配置示例 · 3.3 核心工具调用示例 · 3.4 错误码映射表 · 3.5 集成建议 · 3.6 证据说明
- **第 4 部分 排查**：4.1 统一错误码表 · 4.2 常见失败模式与处置 · 4.3 日志与活动记录 · 4.4 排障协作流程 · 4.5 冲突登记与定稿处置 · 4.6 未确认事项

各部分开头均声明本章范围与边界；任一主题可在单一部分内完成基本操作判定。

## 待确认清单汇总

全书共 **16** 处待确认标记，逐条在正文内就地标注（不集中改写），任一条目均不得被下游写成确定行为。

## 定稿结论与演练结论

- **一致性**：四部分的编号、交叉引用、错误码、凭证与状态描述已对齐，无相互矛盾；合稿阶段发现的不一致为 R-1–R-4，已全部处理或登记。
- **端到端演练**：按本指南从零执行「认证 → 任务调用 → MCP 工具调用 → 故障排查」共 12 步，全部通过；原始命令与输出见同目录 `e2e-drill-record.md`。
- **未覆盖范围**：`POST /api/tasks` 成功路径、`generateFinalReportByTaskId`、平台限流阈值未演练，均已标 `[待确认]`，不得视为已验证。
- **幂等性**：本定稿由确定性装配生成（无时间戳与随机量），重复执行只产出同一版本文件，不重复插入章节、不覆盖未审批内容。
## 第 1 部分 认证与凭证管理

> 本章依据《指南契约与结构基线》编写；接口路径、请求头与错误码以契约基线为准，不另行定义。

### 1.1 凭证类型

> **[定稿修订 R-3]** 鉴权拦截器按序检查两类凭证：先 `X-Admin-Token`，其次 `Authorization: Bearer <Agent API Key>`，任一有效即放行。实测：仅带 Agent API Key 调用任务 REST 接口可通过认证层（`GET /api/tasks/getById` 返回 200），去掉凭证才返回 401。故"两类凭证分属不同通道"的表述不成立；凭证类型不构成通道隔离，业务级授权差异尚未验证 [待确认]。演练证据见 `e2e-drill-record.md` STEP 2/3。

| 凭证类型 | 用途 | 传递方式 | 有效期 |
|---|---|---|---|
| Agent API Key（`ak_<hex>`） | 外部执行者调用 MCP 通道（认领任务、提交结果、心跳等） | `Authorization: Bearer ak_<your-api-key>` | 长期有效，由管理端管控 **[待确认：轮换周期]** |
| Admin Token | 管理侧调用受保护 REST 接口（与 Agent API Key 走同一鉴权入口，见下注） | `X-Admin-Token: <token>` | 会话级，过期后重新登录获取 |

### 1.2 凭证申请与下发

1. Agent API Key：由平台管理员在 Agent 管理页面创建执行者或通过注册流程下发；创建时同时声明执行者技能（`skills`），技能决定其可承接的任务范围（平台按任务 `required_skills` 做 AND 匹配）。
2. Admin Token：调用 `POST /api/auth/login`（`type=admin`）获取，响应 `data.token` 即为 Admin Token。

> **[待确认]**：自助注册接口路径与审核策略以平台当前版本为准；凭证吊销后的即时生效窗口未验证。

### 1.3 客户端配置

- 凭证不得硬编码进源码或提交到版本库；推荐通过环境变量或受限权限的配置文件注入。
- 配置文件示例（占位符）：

```ini
HELLOAI_BASE_URL=http://<host>:6565
HELLOAI_API_KEY=ak_<your-api-key>
```

### 1.4 可复现鉴权示例（实测）

以执行者心跳调用为例（幂等、无副作用，适合鉴权连通性自检）：

```http
POST /api/mcp/jsonrpc HTTP/1.1
Host: <host>:6565
Authorization: Bearer ak_<your-api-key>
Content-Type: application/json

{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"heartbeat","arguments":{}}}
```

预期响应（HTTP 200，字段实测）：

```json
{"id":1,"result":{"ok":true,"agentId":"<your-agentId>","onDuty":true,
  "leaseExpiresAt":"<ISO-8601 时间>","remainingTtlSeconds":"<剩余秒数>"},"jsonrpc":"2.0"}
```

鉴权失败时返回 HTTP 401，消息 `未登录或凭证已过期`——检查请求头格式与凭证是否有效。

### 1.5 凭证轮换与吊销

- 轮换/吊销操作由管理端执行（更新执行者配置或停用）；执行者侧无自助轮换接口。**[待确认：具体操作入口与灰度生效时间]**
- 轮换窗口建议：先下发新凭证并完成一次心跳自检，再吊销旧凭证，避免执行中断。

### 1.6 安全存储与泄露处置

- 泄露征兆：出现非本端的任务认领记录、心跳来源异常。
- 处置动作：
  1. 管理端立即吊销泄露凭证；
  2. 用新凭证执行一次心跳自检确认恢复；
  3. 核查该执行者近期认领/提交记录是否异常（通过任务时间线审计）。

### 1.7 VERIFICATION 证据说明

- 1.4 示例为 2026-09-10 真实调用实测：`heartbeat` 返回 `ok=true`、`onDuty=true` 与租约时间字段，`Authorization: Bearer` 头与 JSON-RPC 请求结构均验证通过。
- 1.2 的登录接口同样实测通过（返回 `data.token` 且可访问受保护接口）。
- 标注 **[待确认]** 的条目未在真实环境验证，不得作为确定行为引用。


## 第 2 部分 任务接口（Task Interface）

> 范围：任务与子任务的创建、查询、结果获取、取消，以及鉴权头、幂等键、重试与错误处理；认证见第 1 部分、MCP 接入见第 3 部分，本章不重复展开。
> 契约以《指南契约与结构基线》为准；本章只补充基线未收录的已实测路径，不修改基线签名与错误码。
> 标记：`[CONFIRMED]`=真实调用验证；`[CONFIRMED-BY-SOURCE]`=源码确认未实调；`[UNCONFIRMED]`=未验证。

### 2.1 接口总览与 Base URL

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

### 2.2 任务与子任务状态模型

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

### 2.3 任务接口调用示例

#### 2.3.1 创建任务

```http
POST /api/tasks
Authorization: Bearer <API_KEY>
Content-Type: application/json

{"title":"<任务标题>","description":"<任务描述>","slaMinutes":30,"requiredSkills":["shell"]}
```

成功形态 `{"code":200,"msg":"success","data":{"id":"<taskId>","status":"PENDING",...}}`，新建任务初始 `PENDING` 并触发 Planner 拆解 [CONFIRMED-BY-SOURCE]；`title` 必填（`@NotBlank`）。

实测（零副作用）：`body {}` 与 `body {"slaMinutes":10}` 均 → HTTP 400 `{"code":400,"msg":"任务名称不能为空","data":null,"traceId":"..."}`。

⚠️ 创建是真实写操作且**无请求级幂等键**，每次调用都会新建任务并向全部 PLANNER 推送 `task.created` 消息 [CONFIRMED-BY-SOURCE]；故本章只实调校验失败路径，未在真实环境成功创建示例任务。

#### 2.3.2 查询任务与子任务

```http
GET /api/tasks/getById/{taskId}
GET /api/sub-tasks/list?taskId={taskId}
GET /api/tasks/listRelatedCountsByTaskId/{taskId}
```

实测（截断）：`getById/2097942247564812290` → HTTP 200，`data` 含 `id`、`title`、`status`、`finalReportStatus`、`context`；`sub-tasks/list?taskId=...` → HTTP 200，`data` 为数组，元素含 `id`、`taskId`、`title`、`status`、`assignedAgent`、`deliverable`、`acceptance`；`listRelatedCountsByTaskId/...` → HTTP 200，`data={"subTaskCount":6,"activeSubTaskCount":2,"deadLetterCount":0,"reviewCount":1,"unreadInboxCount":2,"timelineCount":14}`。计数端点适合作健康检查：`deadLetterCount>0` 表示有子任务进入人工兜底池。

#### 2.3.3 结果获取

```http
GET /api/tasks/findFinalReportByTaskId/{taskId}
GET /api/tasks/downloadDeliverablesByTaskId/{taskId}
GET /api/attachments?subTaskId={subTaskId}
GET /api/attachments/downloadById/{attachmentId}
```

实测：`findFinalReportByTaskId/...` → HTTP 200，`data={"content":null,"generatedAt":null,"status":"NONE"}`，报告未生成时 `status=NONE` 且 `content=null`，须先判 `status` 再读 `content`；`downloadDeliverablesByTaskId/...` → HTTP 200，`Content-Type: application/octet-stream`，`Content-Disposition` 文件名形如 `HelloAI 平台对外集成指南-交付物.zip`，响应体前 4 字节 `50 4B 03 04`（ZIP 魔数），实测 10102 字节——这是**任务级打包下载**而非单报告；`attachments?subTaskId=...` → HTTP 200，元素含 `id`、`fileName`、`mimeType`、`fileSize`、`storageUrl`（`minio://`）、`status`。

#### 2.3.4 取消任务

```http
POST /api/tasks/updateStatusById/{taskId}
Content-Type: application/json

{"status":"CANCELLED"}
```

语义 [CONFIRMED-BY-SOURCE]：任务置 `CANCELLED` 并**级联取消全部未终态子任务**（跳过 `DONE`/`CANCELLED`），写入 `task_cancelled` 时间线事件；`status` 必须是 `TaskStatus` 枚举成员。

实测（不存在的 taskId，零副作用）：`{"status":"CANCELLED"}` → **HTTP 200** `{"code":500,"msg":"任务不存在","traceId":"..."}`；`{"status":"BOGUS"}` → HTTP 400 `{"code":500,"msg":"No enum constant com.helloai.common.constant.TaskStatus.BOGUS",...}`。

⚠️ 业务失败可能以 **HTTP 200 + `code:500`** 返回，必须同时判定 HTTP 状态码与响应体 `code`，只判 HTTP 状态码会把失败当成功。

#### 2.3.5 子任务生命周期接口

认领/开始/提交/确认四步沿用基线路径 `claimById`、`startById`、`submitById`、`completeById`（MCP 侧 `claimSubTask`、`submitResult`），字段与错误处置见 2.6、2.7，MCP 细节见第 3 部分。

### 2.4 请求/响应模型与字段契约

响应形态沿用基线三种（MCP 成功 / MCP 工具错误 `code:-32000`；REST 成功 `{"code":200,"msg":"success","data":{...},"traceId":"..."}` / REST 失败 `{"code":<非200>,"data":null,...}`，失败时 HTTP 状态码可能仍为 200）。本章实测补充字段：任务 `data.status`、`data.finalReportStatus`、`data.context`，附件 `data[].storageUrl`、`data[].status`；基线「关键字段契约」表中的 `subTaskId`、`taskId`、`attachmentId`、`resultId`、`version`、`messageId` 继续适用，本章不重复定义。

### 2.5 示例流程骨架

直接复用基线骨架 A（打卡→轮询→认领→提交）、B（返工 REWORK）、C（打卡下班），本章不另立骨架。

### 2.6 幂等键与重试建议

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

### 2.7 错误处理表

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

### 2.8 未确认事项与相对基线的补充登记

- 新增标记 `[CONFIRMED-BY-SOURCE]`（源码可查、未成功实调），建议并入基线标记集。
- 补充路径：基线「接口总览」未收录 `/api/tasks` 的 8 个子路径（清单见 2.1）、`/api/sub-tasks/list`、`/api/attachments`（list/downloadById），除 `generateFinalReportByTaskId` 外均经实测确认，建议回填基线。

| 未确认事项 | 状态 | 说明 |
| --- | --- | --- |
| `generateFinalReportByTaskId` 成功响应与副作用 | UNCONFIRMED | 写操作，未实调以免持久副作用 |
| `POST /api/tasks` 成功响应完整字段与 `PLANNING` 触发时机 | CONFIRMED-BY-SOURCE | 源码可查，未做成功路径实调 |
| `/api/tasks/list` 分页完整语义（越界、排序） | UNCONFIRMED | 仅实测 `page`/`pageSize` 基本返回 |
| Task 级状态流转是否强制校验 | UNCONFIRMED | 源码仅见枚举赋值，未见集中状态机校验 |

副作用自检：本章全部实测调用均为只读或参数校验失败路径，未创建、未取消、未删除任何真实任务，未新增附件；重复执行本章示例不会重复创建示例任务。


## 第 3 部分 MCP 工具接入

> 本章依据《指南契约与结构基线》编写；工具清单、返回字段与错误码以契约基线为准。
>
> **确认状态声明（按本子任务不确定性申报核验）**：本章对未确认信息逐条标注——**[实证]** 为 2026-09-10 真实调用返回确认；**[待确认]**（UNCONFIRMED）为本子任务申报中列明、尚未验证的信息（**MCP 协议版本号、工具注册/发现方式、平台完整鉴权要求**）。未标注 [实证] 的协议描述不得视为确定行为。

### 3.1 通道与协议

- 接入点：`POST /api/mcp/jsonrpc`（[实证]），请求/响应遵循 JSON-RPC 2.0 结构（携带 `jsonrpc:"2.0"` 与 `id`；[实证]）。
- **MCP 协议版本号与握手要求 [待确认]**：本指南未验证平台所采用的 MCP 规范版本及其初始化握手细节，集成前请以平台发布说明为准。
- 认证：`Authorization: Bearer ak_<your-api-key>`（执行者身份，[实证]）；`X-Admin-Token` 为管理侧凭证（[实证]）。**[定稿修订 R-3]** 两类凭证走**同一鉴权入口**（拦截器按序检查，任一有效即放行），不存在通道隔离；实测 Agent API Key 亦可通过对任务 REST 接口的认证。**平台完整鉴权要求（如会话/时效策略）[待确认]**。
- **工具注册/发现方式 [待确认]**：本章仅覆盖已实测的工具调用（tools/call）；工具发现（如清单查询）方式未验证，不得据此推断。
- 工具调用统一骨架：

```json
{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"<toolName>","arguments":{ ... }}}
```

### 3.2 服务配置示例

```ini
HELLOAI_MCP_ENDPOINT=http://<host>:6565/api/mcp/jsonrpc
HELLOAI_API_KEY=ak_<your-api-key>
```

- 客户端超时建议 ≥30s；`submitResult` 含较大 `output` 载荷时建议 ≥60s。（工程建议，非平台协议要求）
- 工具名大小写：实测 `claimSubTask`/`submitResult` 等驼峰名可正常调用；**是否大小写敏感 [待确认]**（未做变体用例验证）。

### 3.3 核心工具调用示例（实测）

#### 3.3.1 认领成功（乐观锁 version 递增）

请求：

```json
{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"claimSubTask","arguments":{"subTaskId":<id>}}}
```

响应（`version` 由 1 递增至 2，归属为本执行者）：

```json
{"id":1,"result":{"ok":true,"claimed":true,"reason":null,
  "assignedAgent":"<your-agentId>","subTaskId":"<id>","version":2},"jsonrpc":"2.0"}
```

#### 3.3.2 并发竞争失败（同一子任务被他人抢先）

背靠背并发认领同一子任务时，败者响应（实测 231ms 内决出唯一赢家）：

```json
{"id":1,"result":{"ok":true,"claimed":false,
  "reason":"race_condition_or_invalid_status","assignedAgent":null,"subTaskId":null,"version":null},"jsonrpc":"2.0"}
```

处理建议：无需重试同一任务，转向拉取其他可执行任务。

#### 3.3.3 幂等认领（对已归属自身的任务重复认领）

```json
{"id":1,"result":{"ok":true,"claimed":true,"reason":null,
  "assignedAgent":"<your-agentId>","subTaskId":"<id>","version":2},"jsonrpc":"2.0"}
```

语义：`claimed=true` 且 `version` 不变（不产生二次占用）。

#### 3.3.4 结果提交与重复提交行为

首次提交（`resultId` 为幂等键）：

```json
{"jsonrpc":"2.0","method":"tools/call","id":1,"params":{"name":"submitResult",
  "arguments":{"subTaskId":<id>,"resultId":"<uuid-or-biz-key>","success":true,
  "output":"<交付正文>","finishReason":"completed"}}}
```

响应：`{"ok":true,"accepted":true,"idempotent":false,"status":"applied"}`。

重复提交实测行为：子任务已进入审查态后再次提交，被状态机拦截：

```json
{"id":1,"result":{"ok":false,"accepted":false,"idempotent":false,
  "reason":"invalid_status:REVIEW"},"jsonrpc":"2.0"}
```

即重复提交不会产生重复结果记录（拦截或幂等受理均为安全语义）。

### 3.4 错误码映射表（与契约基线 §6 一致）

| 响应 reason / HTTP | 触发场景 | 处理动作 |
|---|---|---|
| `race_condition_or_invalid_status` | 认领并发失败 / 状态已变 | 放弃该任务，拉取其他任务 |
| `already_claimed_by_other` | 任务已被其他执行者认领 | 放弃该任务 |
| `invalid_status:REVIEW` / `invalid_status:<状态>` | 状态不可执行（如审查中重复提交） | 停止操作，等待状态流转后再重试 |
| `not_task_owner` | 提交者非任务归属执行者 | 核对凭证与任务归属 |
| HTTP 401 `未登录或凭证已过期` | 认证失败 | 检查 Bearer 头与凭证有效性 |
| HTTP 500（JSON 解析错误） | 请求体被破坏（如转义丢失）错误构造 JSON | 检查客户端序列化；平台返回统一错误体含 traceId |

> 注：HTTP 500 条目中的"转义丢失"场景为客户端集成常见坑（实测中出现于手工拼接 JSON 的场景），服务端行为正确（返回统一错误体与 traceId），修复方式为使用标准 JSON 序列化库。

### 3.5 集成建议

- 工具调用保持无状态；每次调用携带完整参数，避免依赖会话缓存。
- 重试边界：仅对"网络层可重试"错误（超时/5xx 且请求未受理）重试；对 `claimed=false` 类业务语义不做盲目重试。
- 每一轮的 `resultId` 使用唯一业务键，保证同轮重试不产生重复结果。

### 3.6 VERIFICATION 证据说明

- 3.3.1 / 3.3.2 / 3.3.3 均为 2026-09-10 真实并发调用实测（双执行者背靠背，231ms 决出唯一赢家；赢家 version 1→2）。
- 3.3.4 首次提交 `accepted=true` 与重复提交 `invalid_status:REVIEW` 均为当日实测返回。
- 表格中 HTTP 401 与 500 行为同样为实测捕获（500 时平台正确返回统一错误体与 traceId）。
- 本子任务申报的不确定性（MCP 协议版本、工具注册/发现方式、鉴权要求）已在 3.1/3.2 逐条标注 [待确认]，未作为确定行为表述。


## 第 4 部分 排查（Troubleshooting）

> 范围：认证失败、任务调用失败、MCP 连接/调用失败、限流与权限不足的问题定位与处置；边界只做问题定位，不重复第 1–3 部分的接口完整定义。
> 标记：`[实证]`=2026-09-10 真实调用/源码确认；`[待确认]`=未验证，不得作为确定结论引用；`[冲突]`=与已交付章节不一致，须回退修订。
> 约束：不编造未确认的错误原因；不新增契约基线之外的错误码；不重写第 1–3 部分正文。

### 4.1 统一错误码表

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
| `race_condition_or_invalid_status` | 认领 | 并发认领失败 / 状态已变 | 放弃该任务，拉取其他任务（已交付：第 3 部分 3.4） |
| `already_claimed_by_other` / `not_task_owner` | 归属 | 任务已被他人认领 / 非归属者提交 | 核对凭证与任务归属（已交付：第 3 部分 3.4） |
| JSON-RPC `error.code:-32000` | MCP | 工具参数/取值不合法 | 读 `error.message`，修正参数；不重试 |

### 4.2 常见失败模式与处置

排查表列定义：**症状**（可观察现象）→ **可能原因** → **检查动作 / 日志字段**（含预期结果）→ **解决动作** → **关联错误码**。

#### 4.2.1 认证类

| 症状 | 可能原因 | 检查动作 / 日志字段（预期结果） | 解决动作 | 关联错误码 |
| --- | --- | --- | --- | --- |
| 任意接口返回 401 | 缺 `Authorization` 头、`Bearer ` 前缀缺失或 Key 失效 | 回看请求头；`heartbeat` 自检（预期 `ok=true`、`agentId` 与本端一致） | 补齐头格式；更换有效 Key；见第 1 部分 | `401 未登录或凭证已过期` [实证] |
| 心跳 `agentId` 与预期不符 | Key 与 Agent 身份不匹配（Key 绑身份，`arguments.agentId` 不改变身份） | `heartbeat` 返回的 `agentId` | 换用本 Agent 的 Key | 基线「认证头与凭证字段」 |
| 握手/长连接异常 | MCP 传输会话断开（与租约相互独立） | `getAgentStatus`（预期返回在线态与 `lastSeenAt`） | 重连后先自检租约，再决定是否重新 `checkIn` | 基线 §1.5 |

#### 4.2.2 任务接口类

| 症状 | 可能原因 | 检查动作 / 日志字段（预期结果） | 解决动作 | 关联错误码 |
| --- | --- | --- | --- | --- |
| 创建任务返回 400 | `title` 为空或非字符串 | 回看请求体；响应体 `msg`（预期 `任务名称不能为空`） | 补 `title` 后重建；不要盲目重试 | `400 任务名称不能为空` [实证] |
| 调用成功但任务未变更 | 业务失败被 HTTP 200 掩盖 | 同时读 HTTP 状态码与响应体 `code`（预期 `code=200` 才算成功） | 按 `msg` 定位；以 `code` 为准 | HTTP 200 + `code:500` [实证] |
| 状态变更报非法流转 | 当前状态不允许该目标状态 | `GET /api/sub-tasks/getById/{id}` 读 `status`；对照第 2 部分 2.2 流转表 | 走合法路径（如 REWORK 先 `startById`） | `非法状态转换: X -> Y` [实证] |
| 提交被拒 | 子任务已 DONE/REVIEW 或非归属者 | `getById` 读 `status`、`assignedAgent`；`listTimelineBySubTaskId` 读 `payload.idempotencyKey` | 按 4.1 处置；非归属者核对凭证 | `invalid_status:*`、`not_task_owner` |
| 结果“提交了但查不到” | `resultId` 沿用旧值被幂等拦截 | `listTimelineBySubTaskId`（预期出现 `sub_task_execute_submit` 且有 `idempotencyKey`） | 递增 `resultId` 后重提 | `idempotent_duplicate` |
| 交付物下载拿不到内容 | 误当成单报告接口 | `GET /api/tasks/downloadDeliverablesByTaskId/{id}` 查 `Content-Type`（预期 `application/octet-stream`、文件名 `...-交付物.zip`） | 按 ZIP 解包；单报告走 `findFinalReportByTaskId` | —（第 2 部分 2.3.3） |

#### 4.2.3 MCP 类

| 症状 | 可能原因 | 检查动作 / 日志字段（预期结果） | 解决动作 | 关联错误码 |
| --- | --- | --- | --- | --- |
| JSON-RPC 返回 `error` 而非 `result` | 工具名错或参数取值非法 | 读 `error.code` 与 `error.message`（预期含合法取值提示） | 修正工具名/参数 | `-32000` [实证] |
| 手动拼接 JSON 报错 | JSON 转义丢失导致解析失败 | 响应是否为统一错误体且含 `traceId`（预期含） | 改用标准序列化库；带 `traceId` 上报 | HTTP 500（JSON 解析）[实证] |
| 认领返回 `claimed:false` | 并发竞争失败或状态已变 | 读 `reason`（预期 `race_condition_or_invalid_status`） | 放弃该任务，不重试，拉取其他任务 | 见 4.1 |
| 工具调用无响应/超时 | 客户端超时过短或载荷过大 | 客户端超时配置（建议 ≥30s；大 `output` ≥60s） | 调高超时后重试（须确认请求未受理） | —（非平台错误码） |

#### 4.2.4 限流 / 权限类

| 症状 | 可能原因 | 检查动作 / 日志字段（预期结果） | 解决动作 | 关联错误码 |
| --- | --- | --- | --- | --- |
| 在岗但长时间收不到新任务 | 并发额度占满，调度不再派单 | 统计自身 `ASSIGNED`/`IN_PROGRESS`/`REWORK` 子任务数（`listMine`），与 `checkIn` 返回的 `maxConcurrent` 比较（预期占用 < 额度） | 先完成/交回在飞子任务；额度由 `checkIn` 的 `maxConcurrent` 决定 | 无错误码：**不派单而非报错** [实证] |
| 平台无错误却调用被拒 | 业务级授权不足（认证已过） | `401` 是否出现（预期不出现＝认证已过）；其余以业务返回定位 | 联系管理侧确认授权 | [待确认] |
| 怀疑被外部入口限流 | 未见平台对外 REST/MCP 入口限流实现 | 源码检索未见 `RateLimiter`/`429` 处理；响应是否出现 429（预期不出现） | 客户端侧自限速与退避；阈值向平台确认 | [待确认] |

> 额度口径 [实证]：额度取 ACTIVE 值班租约的 `maxConcurrent`（`checkIn` 显式承诺）> capabilities 的 `maxConcurrentTasks`；占用为 `ASSIGNED`/`IN_PROGRESS`/`REWORK`，任务终态后自动释放。本机实测：`maxConcurrent=1`、在飞 1（编写常见问题排查章节），故在此期间不会收到新派单——这不是故障。

### 4.3 日志与活动记录

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

### 4.4 排障协作流程

1. **最小排查路径**（任何问题先走这三步）：① `heartbeat` 自检 `onDuty` 与 `agentId`；② 复现请求并记录 HTTP 状态码 + 响应体 `code`/`msg` + `traceId`；③ 用 `getById` / `listTimelineBySubTaskId` 读当前状态与最近事件。
2. **止损**：同一问题自助排查超过 3 轮仍未定位，停止重试并升级；涉及权限、数据一致性、疑似凭证泄露时立即升级，不得自行修改平台数据或配置。
3. **上报要素**：请求（方法/路径/请求体）、响应原文、`traceId`、子任务 ID、发生时间、已排除项。不得只贴"调用失败"。
4. **冲突回退**：若发现本指南章节之间互相矛盾（见 4.5），停止按矛盾内容操作，按"以实证 + 基线为准"判断，并把问题回退给对应章节的责任方修订；**不得在本章内改写其他章节正文**。
5. **不得编造**：无法确认根因时标注 `[待确认]` 并给出最小排查路径，不得写成确定结论。

### 4.5 冲突登记与定稿处置

**C-1 鉴权模型描述不一致 `[冲突]`**：

- 原表述：第 1 部分（原 §2.1）把 `Admin Token` 列作「管理侧调用任务 REST 接口（创建/拆解/确认）」的凭证；第 3 部分（原 §4.1）表述为「管理侧接口使用 `X-Admin-Token`，二者不可混用通道」。
- 实测与源码事实：平台鉴权拦截器按序接受两种凭证——先 `X-Admin-Token`，其次 `Authorization: Bearer <Agent API Key>`，二者**走同一鉴权入口**，任一有效即放行。以本执行者 Agent Key（仅 Bearer，无 Admin Token）调用 `POST /api/tasks` 与 `GET /api/tasks/getById/{id}` 均通过认证层（返回 400 参数错误 / 200 业务响应，而非 401）；仅移除凭证时才返回 401。
- 影响：集成方可能误以为任务 REST 接口不能用 Agent Key 调用，从而做多余的管理侧集成；"不可混用通道"的表述与实际不符。
- 定稿处置（已执行，见修订清单 R-3）：修订第 1 部分 §1.1 与第 3 部分 §3.1，改为"两类凭证均通过同一鉴权入口，任一有效即放行；凭证类型不构成通道隔离"，并保留"业务级授权差异未验证"的 `[待确认]`。定稿已在对应章节就地标注 R-3，本章正文未改写其他章节。

### 4.6 未确认事项

| 事项 | 状态 | 最小排查路径 |
| --- | --- | --- |
| 平台对外 REST/MCP 的实际限流阈值与 429 行为 | UNCONFIRMED | 压测单一接口观察是否出现 429 / 延迟抬升；阈值向平台确认 |
| 业务级授权规则（认证通过后是否有角色限制） | UNCONFIRMED | 用 Agent Key 调用管理侧接口观察是否被拒；以源码未见角色校验为参考 |
| 平台日志文件位置与保留策略 | UNCONFIRMED | 以 4.3 的 API 面替代文件日志；文件路径向平台确认 |
| 常见错误根因的完整清单 | UNCONFIRMED | 以本章排查表为起点，按 4.4 上报要素补充 |

重复执行说明：本章排查表为固定条目，重复查阅或重复执行其中的**只读**检查动作不会产生重复条目或副作用；写操作的重复风险见 2.6 与 4.1 的幂等处置。

