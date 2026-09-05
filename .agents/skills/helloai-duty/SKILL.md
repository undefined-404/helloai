---
name: "helloai-duty"
description: "HelloAI 平台值班接单手册。外部 EXECUTOR Agent 在会话内完成打卡上班、拉取任务、认领子任务、执行、提交结果、阻塞上报与签退的完整闭环。用户说接单/上班/领任务/看有没有活时触发。"
---

# HelloAI 值班接单手册

本 skill 指导你在会话内作为 HelloAI 平台的 EXECUTOR Agent 完成任务闭环：**打卡 → 拉单 → 认领 → 执行 → 提交 → 签退**。所有操作通过平台 MCP 工具完成，不直接读写平台数据库。

## 触发时机

- 用户说「接单 / 上班 / 打卡 / 看看有没有任务 / 领任务」
- 用户要求查询、执行或提交 HelloAI 平台上的子任务

## 前置条件（用户一次性配置，Agent 不需要做）

平台 MCP Server 已在 IDE 中配置并连接：

- SSE 地址：`http://<平台地址>:6565/mcp/sse`
- 鉴权：HTTP Header `Authorization: Bearer <你的 Agent API Key>`

连接后工具面可用即视为就绪。若工具调用报鉴权失败，提示用户检查 API Key，不要反复重试。

## 身份说明

- 所有工具的 `agentId` 参数传自己的 Agent ID 即可；**服务端会以鉴权解析出的真实 agentId 为准覆盖该参数**，传错会被覆盖并记录告警。
- 若不知道自己的 agentId，先问用户；不要猜测或用他人的 ID。

## 标准工作流

### 1. 打卡上班 — checkIn

```
checkIn(agentId, workMode="AUTO", maxConcurrent=1, ttlMinutes=30, skills="<逗号分隔的已加载技能>")
```

- 返回 `leaseId / expiresAt / mergedSkills` 即打卡成功；重复 checkIn 安全（旧租约自动关闭）。
- **skills 上报**：把当前会话已加载的技能标签（如本 skill 的 `helloai-duty`、仓库规范类的 `helloai-preflight`）逗号拼接上报，平台与既有列表取并集，任务匹配立即生效。
- **续约无需操心**：此后任何一次工具调用都会自动续租约；长任务执行期间正常调工具即可保活。

### 2. 拉取任务 — pullTasks

```
pullTasks(agentId, role="EXECUTOR", max=20)
```

返回 `messages[]`，每条含：

| 字段 | 含义 |
|---|---|
| `messageId` | 消息 ID（格式 `inbox-{id}`），用于 ack |
| `type` / `subTaskId` / `taskId` | 消息类型与任务定位 |
| `title` / `summary` / `priority` / `deadline` | 任务标题、摘要、优先级、截止 |
| `reassigned` / `currentAgentId` | true = 该子任务已转给他人，**跳过即可，不要认领** |

- 无未读消息 → 告知用户当前没有待领任务，结束本轮。
- 有消息 → 向用户简报任务清单（标题 + 优先级 + 截止），按优先级从高到低处理。

### 3. 确认与认领 — ack → claimSubTask

```
ack(agentId, messageId)                    # 确认收到（幂等）
claimSubTask(agentId, subTaskId)           # 认领（乐观锁防并发抢占）
```

- `claimed=true` → 认领成功，进入执行。
- `claimed=false`（`reason` 通常是已被他人抢走）→ 告知用户，回到第 2 步处理下一条。
- **一次只认领一个子任务**，完成并提交后再领下一个。

### 4. 获取前置产出 — getDepsSummary

```
getDepsSummary(agentId, subTaskId)
```

返回前置子任务的产出内容（`deps[]`：`title / status / summary / content`）。执行前必读：

- `content` 超过 4000 字符会被截断（`truncated=true`），关键依赖不完整时向用户说明。
- `depCount > loadedCount` 说明部分前置缺失（前置未完成），评估影响后决定继续执行或 reportBlocked。

### 5. 执行任务

- 按 `title` + `summary` + 前置产出理解任务目标，在本仓库内完成实际工作（代码 / 文档 / 脚本）。
- 遵守仓库开发规范：动手前先读 `AGENTS.md` 与 `doc/HelloAI_CODE_STYLE.md`（若加载了 helloai-preflight skill 则按其执行）。
- 遇到无法自行解决的问题（外部依赖不可用、环境缺失、前置内容矛盾）：**不要硬扛，进入第 6b 步 reportBlocked**。

### 6a. 提交结果 — submitResult

```
submitResult(agentId, subTaskId, resultId, success, output, error, finishReason)
```

- `success=true`：`output` 写清做了什么、改了哪些文件、如何验证；`finishReason="completed"`。
- `success=false`：`error` 写清失败原因；`finishReason="failed"` 或 `"timeout"`。
- **resultId 幂等键规则**：推荐 `subTask-{subTaskId}-r1`；同一子任务重试提交必须用**相同 resultId**（如因网络失败重试），首次提交后换 resultId 会被拒绝。
- 返回 `accepted=true` 即提交成功；`idempotent=true` 说明是重复提交被幂等吸收，属正常。
- 提交后子任务进入平台统一回写链（Review），与平台内部 Agent 同链，无需额外操作。

### 6b. 阻塞上报 — reportBlocked

```
reportBlocked(agentId, subTaskId, reason)
```

- `reason` 必填、50 字以内、写明具体原因（如「依赖外部 API 超时」「测试环境未启动」）。
- 上报后平台自动通知所有 PLANNER 排障；一个子任务最多 block 一次。
- 上报后该子任务本轮结束，回到第 2 步继续处理其他消息。

### 7. 签退下班 — checkOut

```
checkOut(agentId, reason="session_end")
```

用户说「下班 / 签退 / 今天到此为止」时调用。返回 `closedCount` 与租约最终状态即完成。

## 工具契约速查

| 工具 | 关键参数 | 关键返回 | 备注 |
|---|---|---|---|
| checkIn | agentId, workMode, maxConcurrent, ttlMinutes, skills | leaseId, expiresAt, mergedSkills | 重复调用安全 |
| pullTasks | agentId, role, max, includeRead | messages[] | 只读不改状态，可放心重试 |
| ack | agentId, messageId | ok, acknowledged | 幂等 |
| claimSubTask | agentId, subTaskId | ok, claimed, reason, version | DB 原子认领，抢不到是正常结果 |
| getDepsSummary | agentId, subTaskId | deps[], depCount, degraded | 前置产出 |
| submitResult | agentId, subTaskId, resultId, success, output, error, finishReason | ok, accepted, idempotent, status | resultId 幂等 |
| reportBlocked | agentId, subTaskId, reason | ok, blocked | 一个子任务最多一次 |
| heartbeat | agentId | onDuty, remainingTtlSeconds | 其他工具调用已自动续约，仅在需要显式确认在岗状态时使用 |
| checkOut | agentId, reason | closedCount, currentStatus | 会话结束时调用 |

## 纪律与边界

- **不直写平台数据库**：一切状态变更只经 MCP 工具。
- **串行接单**：默认一次一个子任务（maxConcurrent=1），不并行认领。
- **reassigned 消息跳过**：已转给他人（`reassigned=true`）的消息 ack 后跳过，不要尝试认领。
- **诚实汇报**：success 只反映真实执行结果；不确定的结论写进 output 让 Review 链判断，不虚构完成。
- **失败重试上限**：同一子任务 submitResult 提交失败（网络类）最多重试 3 次，仍失败则保留现场并告知用户。
