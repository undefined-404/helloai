# 第 03 章 执行结果提交

本章说明外部 EXECUTOR 在执行完成后如何提交结果（成功 / 失败 / 部分完成）、结果格式与证据要求、幂等键的使用、重复提交的处理，以及提交失败的重试边界与人工升级路径。
字段名、返回字段、状态值与错误码引用《外部执行者值班与任务执行手册 · 顶层契约》（下称“契约”）的 §1、§2.1、§2.5、§5.1、§5.2、§6、§7；本章不重复定义。任务获取与认领见第 02 章，在线保持与租约见第 04 章。

## 3.1 提交入口

提交的唯一入口是 `submitResult` 工具（MCP SSE / REST 别名 / REST 直通三通道同名）。`POST /api/sub-tasks/submitById/{id}` 只翻状态、不带产出文本，**不得**用于交产出（契约 §2.4）。

### 3.1.1 成功提交示例

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

### 3.1.2 结果字段表

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

## 3.2 结果格式与证据要求

- 在 `{提交产出}` 条件下，`{执行者}` **必须**在 `output` 末尾附完整 `EXECUTION_RECORD` 块，字段与约束见契约 §2.5；`SUMMARY` 必填，`VERIFICATION` 必须置于块的最后且原样粘贴命令输出，**不得**转述。
- 在 `{产出含文件}` 条件下，**必须**先 `POST /api/artifacts/upload` 上传文件内容并在 `DELIVERABLES` 中列相对项目根路径；**不得**只声明路径而不上传，**不得**直连 MinIO（契约 §2.5）。
- 在 `{被打回后重新提交}` 条件下，**必须**重新上传最新版附件；否则旧内容继续被核验，形成打回循环（契约 §2.5）。
- 提交呈现限额：`output` 以 4000 字符摘要注入核验，附件每份 8000 字符（契约 §2.5）。在 `{产出超过限额}` 条件下，**必须**把契约性内容前置并让 `EXECUTION_RECORD` 落在 4000 字符内；做不到的后果：核验侧读不到 `VERIFICATION`，按证据不足驳回。

## 3.3 成功与失败判定

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

## 3.4 重复提交与幂等

契约口径（契约 §7、§2.5）：

- 幂等键是 `resultId`。在 `{同一轮重试}` 条件下，**必须**沿用相同 `resultId`；平台判定“与上一次执行相同的 `resultId` = 重复提交”，**采纳上一次结果，不新增结果记录**。
- 在 `{返工重提}` 条件下，**必须**换新 `resultId`；沿用旧 `resultId` 会被判 `idempotent_duplicate`——返回看似成功（`accepted=true, idempotent=true`），但新产出**不会**被写入。

可观察预期与判定动作：

1. 提交前用 `GET /api/sub-tasks/getById/{id}` 确认状态处于可提交集（`ASSIGNED`/`IN_PROGRESS`）；否则会先被状态守卫拦截（§3.3 实测）。
2. 提交后若返回 `idempotent=false`，说明本次是新结果记录；若返回 `idempotent=true`，说明命中了重复提交，**必须**核对返回的 `resultId` 与上一轮是否相同，并**不得**据此认为新产出已生效。
3. 在 `{需要确认提交是否已生效}` 条件下，**必须**查 `GET /api/sub-tasks/getById/{id}` 的 `status` 与 `completedAt`，**不得**仅凭 `accepted=true` 判定。
4. 重复提交**不得**产生重复结果记录或重复副作用；同一子任务**必须**只有一条有效执行产出。

## 3.5 提交后的核验等待

在 `{提交成功}` 条件下，`{执行者}` **必须**进入轮询（建议 15 秒一次、最多 8 轮 ≈ 2 分钟），直到收到 `sub_task.approved` / `sub_task.rejected` / `sub_task.rework` 之一；**不得**以“拉一次为空”判定无消息（契约 §2.5）。

| 结果 | 后续动作 |
|---|---|
| `sub_task.approved` | `ack` 该消息，进入下一任务 |
| `sub_task.rejected` | 查 `GET /api/reviews?subTaskId={id}` 取 `issues`/`comment`/`score`，按返工四步重提（第 02 章 §2.3.2 同理：`startById` → 新 `resultId` → 重新上传附件 → 附 `EXECUTION_RECORD`） |

## 3.6 提交失败的重试边界与升级路径

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

## 3.7 待确认清单（本章新增）

| 编号 | 条目 | 下游义务 |
|---|---|---|
| TBD-03-1 | 同轮重复提交同一 `resultId` 时的精确返回报文（本地仅在状态守卫层观测到 `invalid_status`，未在可提交状态窗口内构造出重复提交） | 描述重复提交时**必须**以 `idempotent` 字段为判据，**不得**写死期望报文全文 |
| TBD-03-2 | 是否存在“部分完成”专用状态值或字段 | **不得**引用契约外字段；按 §3.3 以 `success=false` + 说明处理 |
| TBD-03-3 | 提交失败计数的服务端阈值 | **不得**声称平台自动熔断；**必须**由执行者自行计数并按 3 次止损 |