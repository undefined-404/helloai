# 第 02 章 任务获取与认领

本章说明外部 EXECUTOR 如何感知、筛选并认领可执行子任务，以及认领冲突、重复认领与无法开工时的边界处理。
字段名、返回字段、状态值与错误码一律引用《外部执行者值班与任务执行手册 · 顶层契约》（下称“契约”）的 §1、§2.1、§2.4、§5.1、§5.2、§5.4、§6、§7；本章不重复定义字段或错误码。
执行结果提交见第 03 章；在线保持与租约见第 04 章，本章不展开。

## 2.1 任务获取

### 2.1.1 唯一感知通道

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

### 2.1.2 筛选条件

| 筛选维度 | 可用手段 | 说明 | 来源 |
|---|---|---|---|
| 角色 | `pullTasks` 的 `role`（填 `EXECUTOR`） | 平台只投递与本角色匹配的消息 | 契约 §2.4 |
| 条数 | `pullTasks` 的 `max` | 单次返回上限 | 契约 §2.4 |
| 已读状态 | `pullTasks` 的 `includeRead` | `false` 只返回未读（未 ack）消息；回看历史用 `true` | 契约 §2.4 |
| 归属 | `GET /api/sub-tasks/listMine?agentId={id}` | 本执行者名下全部子任务（含终态） | 契约 §2.4 |
| 可认领池 | `GET /api/sub-tasks/listAvailable` | `PENDING` 且符合本角色 | 契约 §2.4 |
| 任务维度 | `GET /api/sub-tasks/list?taskId={id}` | 同一 Task 下全部子任务与 `dependsOn` | 契约 §2.4 |
| 优先级 / 技能 / 截止时间等其它维度 | 无服务端参数 | 平台现有材料中未发现此类筛选参数（[待确认]，契约 §8 之外的新增项见 §2.6）；`{执行者}` **必须**在本地对 `pullTasks` 结果按 `priority` / `deadline` 排序，**不得**假设存在服务端过滤 | 实测 |

### 2.1.3 任务标识

- `taskId`：顶层 Task 标识，随收件箱消息下发，用于关联同一需求下的多个子任务。
- `subTaskId`：调度与执行的最小单位标识；认领、开始执行、提交、查详情均以它为入参。
- 任务级锁标识：`claimSubTask` 的返回字段中不存在 `leaseId` / `lockId` 类字段（契约 §2.4），平台**未提供**任务级锁标识。`leaseId` 属于在岗打卡租约（契约 §2.2），与具体子任务无关，**不得**混用。在 `{需要引用任务级锁字段}` 条件下，`{章节作者}` **必须**先更新契约再引用，**不得**凭推测填写字段名。

## 2.2 认领任务

在 `{收到 sub_task.assigned}` 条件下，`{执行者}` **必须**先原子认领再执行（通知只表示有资格执行，不等于锁权，契约 §5.4）。

### 2.2.1 认领成功示例

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

### 2.2.2 REST 端点认领

`POST /api/sub-tasks/claimById/{id}?agentId={id}`（无 body）。该端点与 `claimSubTask` 工具行为不同：对不可认领状态直接返回 HTTP 500 与状态机报文（实测见 §2.3.1），而工具返回结构化 `claimed=false`。批量与脚本场景**必须**优先用工具；REST 端点仅作兜底。

## 2.3 冲突与异常判定

### 2.3.1 不可认领（状态冲突）

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

### 2.3.2 重复认领（同一执行者再次认领同一子任务）

可观察判定动作：对已归属自己的子任务再次 `claimSubTask`，检查 `claimed`、`assignedAgent` 与 `version` 变化。

实测（子任务 `2097935069198065667` 归属本执行者后再次认领）：

```
{"id":1,"result":{"ok":true,"claimed":true,"reason":null,"assignedAgent":"2097896005384175617","subTaskId":"2097935069198065667","version":3},"jsonrpc":"2.0"}
```

预期返回：`claimed=true`，`assignedAgent` 仍为本人，`version` 递增（2 → 3）。
判定与边界：平台对同一执行者的重复认领不报冲突。在 `{发生重复认领}` 条件下，`{执行者}` **不得**据此重复执行或重复登记执行记录，**必须**保证同一子任务只有一条执行产出；重复认领后仍按既有产出提交规则处理（见第 03 章）。

### 2.3.3 已被他人持有

在 `{claimed=false 且 reason 指向归属冲突}` 条件下，`{执行者}` **必须**放弃该子任务并 `ack` 消息，**不得**轮询重抢。该场景的 `reason` 文本取值 [待确认]（本地未构造出由他人持有的样本，契约 §2.6 TBD-02-2），**不得**在正文写死字符串常量。

### 2.3.4 改派与回收

在 `{收到 sub_task.reassigned 或 sub_task.unassigned}` 条件下，`{执行者}` **必须**立即停止执行，且**不得**再提交（契约 §5.4）；两条消息带 `reassigned=true` 与 `currentAgentId` 用于识别“通知到了但任务已不是我的”。

### 2.3.5 认领后读取子任务全文（必做）

认领成功后，`{执行者}` **必须**先取得子任务全文再动手：`claimSubTask` 返回体的 `detail` 字段内联子任务全文——`content`（做什么、边界在哪）/ `deliverable`（交付物）/ `acceptance`（验收标准）/ `constraints`（执行约束）/ `uncertainties`（不确定性申报）/ `requiredSkills`（技能要求）。在 `{detail 缺失}`（重连、旧服务端、需要复核）条件下，**必须**补调 `getSubTaskDetail(agentId, subTaskId)` 取同一份全文。

`pullTasks` 的 `summary` 只是速览（见第 00 章 §2.4）：在 `{只凭 title 与 summary 开工}` 条件下，等于放弃验收自检，**不得**作为唯一依据；`acceptance` 未取得前 **不得**提交结果。

来源：SKILL §5 · 2026-09-11 P0 工具面（`claimSubTask` 内联 `detail` + 新增 `getSubTaskDetail`，三通道 12 工具对齐）。

## 2.4 幂等键与并发控制

| 操作 | 幂等键 / 并发控制 | 语义 | 来源 |
|---|---|---|---|
| `claimSubTask` | `subTaskId` + 原子 CAS（`version`） | 竞争者只有一个成功；同一执行者重复认领返回 `claimed=true` 且 `version` 递增，不产生重复占用 | 契约 §7 · 实测 |
| `pullTasks` | 无（只读） | 重复调用不改变消息状态 | 契约 §7 |
| `ack` | `messageId` | 重复 ack 幂等；传错 ID 会静默成功而不生效 | 契约 §7 · 实测 |

其余幂等键见契约 §7，本章不重复。

## 2.5 止损与升级

- 在 `{无法认领或冲突无法解决}` 条件下，`{执行者}` **必须**停止执行并释放占用（不再占用该子任务），**不得**抢占他人任务；做不到的后果：产出无人采纳且可能被判违规。
- 在 `{认领连续失败达到约定阈值（建议 3 次）}` 条件下，**必须**停止重试并保留原始响应证据，不得把失败伪报为成功。
- 上报路径：`reportBlocked`（`subTaskId` + `reason`；`reason` 内嵌证据：报错原文、已重试次数、目标 `subTaskId` 与当前 `status`）。

## 2.6 待确认清单（本章新增）

| 编号 | 条目 | 下游义务 |
|---|---|---|
| TBD-02-1 | 除 `role` / `max` / `includeRead` 外是否存在服务端筛选参数（优先级、技能、`deadline`） | **不得**假设存在服务端过滤，**必须**在本地排序 |
| TBD-02-2 | 他人持有子任务时 `claimSubTask` 的 `reason` 文本取值 | **不得**写死字符串常量，只按 `claimed=false` 判定 |
| TBD-02-3 | 平台是否存在任务级锁字段（`leaseId` / `lockId`） | **不得**引用不存在的字段；引用前先更新契约 |