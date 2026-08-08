# HelloAI 长连接门铃通知通道最小技术设计

> **状态注记（2026-08-07）**：本设计已**搁置**。技术瓶颈——外部 AI Agent（安装版 / CLI 版）均为“单向执行器”，无法处理平台推送的门铃信号，且 Agent 端代码不可修改；任务感知一律由 `pullTasks` 轮询承担。Java 代码全部保留运行（含 REST 端点 `/api/agents/doorbell/sse`），SKILL.md / 脚本已下线门铃内容。若未来 Agent 端常驻 daemon（官方插件 / CLI 包装器）落地，可复用本设计。

> 文档定位
>
> - 本文档是对 `doc/HelloAI_agenthub.md` §8（V3 门铃通知通道）与 `doc/HelloAI_架构设计参考.md` §5.0（外部 Agent 实时协作闭环最高优先级）的**技术落地细化**。
> - 本文档描述的是设计方案与落点建议，**不代表当前代码已实现**；当前现实基线以 `doc/HelloAI_项目基线文档.md`、`doc/HelloAI_实现差距表.md` 为准。
> - 本文档若与历史文档冲突，按事实源优先级判断：代码与运行结果 > `V1__init_all.sql` > 验收脚本 > 实现差距表 > 项目基线文档 > 本文档 > 历史草案。

**版本**：2026-07-17
**适用范围**：外部 Agent（`CLI_CLIENT` / 未来 `WEB_BROWSER`）实时唤醒通道
**阅读前置**：`doc/HelloAI_agenthub.md` §8、`doc/HelloAI_架构设计参考.md` §3.8 / §4.7 / §5.0
**思路来源**：本设计的"门铃 vs 开门（唤醒 vs 能力调用）"切分、SSE 首选、三类接入 Agent 分通道，直接承接两份前期文档——`doc/agent_communication_architecture_analysis.md`（长连接方案对比：方案A SSE / 方案B TCP+Bridge / 方案C 混合，本文取其结论并落实到当前代码基线）与 `doc/helloai_agenthub_complete.md`（已归档早期草案：打卡上班心智、"通知即时、消费自主"、Python Bridge 守护进程；其 WebSocket 主通道 / `AgentStatus` 扩展 / `ShiftManager` 与当前基线不一致，本文不沿用其实现方式，仅继承其设计原则）。

---

## 1. 背景与问题

### 1.1 现状（已核对代码事实）

外部 Agent 最小闭环已就绪，但**全部靠客户端轮询**：

- MCP 主线：`spring-ai-starter-mcp-server-webmvc`，SSE 端点 `/mcp/sse`、消息端点 `/mcp/messages`（`application.yml`）。
- 会话层：`McpAuthContext.SESSION_AUTH`（`sessionId → AuthContext` 进程级 Map），**只做鉴权关联，不持有连接引用，无法向指定客户端主动推送**。
- 通知层：`TaskController.create`、`SubTaskService.sendInboxNotification`、`NotificationConsumer.onNotification` 三处最终都调用 `AgentInboxService.send(...)` 写 `agent_inbox` 表；Agent 通过 MCP `pullTasks` **建议每 30 秒轮询一次**读取。
- 值班层：`AgentDutyLeaseService`（`checkIn/checkOut/renew/expire`）+ `agent_duty_lease` 表已落地。
- 长连接基础设施：**项目内不存在任何 WebSocket / STOMP / Netty 依赖或配置**（全仓 grep 0 命中）。

### 1.2 问题

任务从"发布"到"外部 Agent 感知"存在 **0~30 秒轮询延迟**。这是外部 Agent 三层可用性模型（`架构设计参考` §3.8）中**中间"长连接"层缺失**的直接后果：

- 打卡（`duty lease`）已解决"愿不愿意接单"（准入）✅
- 双心跳已解决"是否在正常干活"（监控）✅
- **长连接"电话线"缺失** → 平台只能等 Agent 自己回头看，做不到"任务一到就实时唤醒" ❌

### 1.3 目标一句话

> 补一条**只负责"门铃/唤醒"的服务端 → 客户端单向长连接**，把外部 Agent 的响应时延从轮询级降到秒级；MCP 仍是唯一任务协议层，门铃丢失可无损回退到轮询。

---

## 2. 设计目标与非目标

### 2.1 目标

- 平台在"有新任务给某个在岗 Agent"时，能**秒级**推送一个轻量信号，触发该 Agent 立即走 MCP `pullTasks`。
- 门铃通道与 MCP 主线**职责分离**：门铃只送"有事了"信号，**不送任务内容、不推进状态机、不承载结果回写**。
- 门铃**尽力而为**：断连 / 丢信号 / 平台重启都不致命，Agent 始终保留轮询兜底。
- 复用现有鉴权（Bearer apiKey）、值班租约、收件箱、双心跳，不新增第二套 Agent 主状态。

### 2.2 非目标（本轮明确不做）

- ❌ 不做本地常驻 Bridge / Daemon（AgentHub V2，另行推进）。
- ❌ 不做一键安装 / `systemd` / `launchd`（AgentHub V3 产品化，后置）。
- ❌ 不引入 WebSocket / STOMP / Netty（见 §4 选型结论）。
- ❌ 门铃**不**下发任务 payload、**不**替代 `pullTasks/claim/submitResult`。
- ❌ 不做跨实例广播（多副本 fanout 作为 §10 演进项，本轮按单实例落地）。
- ❌ 不改 `AgentStatus` / `AgentOnlineStatus` 枚举。

### 2.3 三类接入 Agent 的通道分工（承接"方案C"）

`agent_communication_architecture_analysis.md` 的**方案C（混合架构）**把接入方分三类，门铃只负责其中一格，其余各有归属：

| 接入类型 | 实时唤醒机制 | 门铃是否适用 |
|---|---|---|
| 内部 `API_KEY_LLM`（平台内保底执行器） | 平台内直接编排 / MQ 消费，进程内直连，无"感知延迟"问题 | 不需要（§5.0 已定其为兜底角色，非首选） |
| 外部 `CLI_CLIENT`（Qoder/Trae/Claude Code/Codex 等常驻客户端） | **本设计的门铃 SSE**：能长连即秒级唤醒；不支持长连的 stdio 型，后续由 AgentHub V2 本地 Bridge 订阅门铃再中转（门铃契约不变） | **是，本轮目标** |
| 外部 `WEB_BROWSER`（网页版 AI 站点） | MCP 浏览器工具输入 + 定时轮询抓取，**放弃实时性**（方案C 结论一致） | 不需要 |

> 即：门铃是"外部 `CLI_CLIENT` 实时唤醒"这一格的最小落地；方案B 的"TCP+本地 Bridge"被降级为**未来对 stdio 型客户端的接入层增强**（§10），不进入本轮内核。

---

## 3. 术语

| 术语 | 含义 |
|---|---|
| 门铃（Doorbell） | 服务端 → 客户端的单向长连接，只推"有新任务/有新消息"的轻量信号 |
| 响铃（Ring） | 平台向某个已连门铃的 Agent 推送一次信号的动作 |
| 门铃信号 | 极简 JSON，如 `{"type":"inbox","refType":"sub_task","refId":123,"eventType":"sub_task.assigned"}`，**不含任务正文** |
| 兜底轮询 | Agent 端保留的 `pullTasks` 定时拉取，门铃不可用时的安全网 |

---

## 4. 协议选型

### 4.1 候选对比

| 选项 | 契合度 | 说明 |
|---|---|---|
| **SSE（Server-Sent Events）** ✅ 选定 | 高 | 单向 server→client 恰好匹配"门铃"语义；项目已是 WebMVC（Servlet）栈，Spring MVC 原生支持 `SseEmitter`，**零新增依赖**；spring-ai MCP 本身就用 SSE，客户端心智一致；天然走 HTTP，复用现有 Bearer 鉴权与反代 |
| WebSocket | 中 | 双向能力对"门铃"是过剩的；需引入 `spring-boot-starter-websocket` 新依赖与新编程模型；握手/心跳/重连需自管。门铃不需要客户端→服务端通道（那条走 MCP） |
| 裸 TCP / Netty | 低 | 引入独立网络栈与协议自定义成本最高；与现有 HTTP 鉴权、反代、可观测体系割裂 |
| Redis Pub/Sub 直连客户端 | 否 | 违反"外部 Agent 不直连平台基础设施"边界（`架构设计参考` §4.7） |

### 4.2 结论

**选 SSE 专用门铃端点**。理由：门铃本质是单向唤醒，SSE 是最小充分解；WebMVC + `SseEmitter` 零新增依赖即可落地；与 MCP-over-SSE 客户端心智统一。

> 边界重申（`agenthub.md` §8.2）：真正的设计重点不是传输协议本身，而是"门铃层"与"任务协议层"的职责切分。未来若需双向（如服务端下发控制命令 `STOP/PAUSE`），可平滑升级到 WebSocket，本设计的注册表/响铃抽象不变。

### 4.3 为什么不直接复用 MCP 自带的 SSE transport（回应"方案A"）

`agent_communication_architecture_analysis.md` 的**方案A**首选是"复用 MCP 的 SSE transport，让服务端在同一条连接上主动 Push"。方向对，但**在当前代码基线下不可直接落地**，核对结论如下：

- `spring-ai-starter-mcp-server-webmvc` 把 SSE 连接与消息分发**封装在框架内部**，业务侧未持有 `/mcp/sse` 那条 `SseEmitter`/连接句柄，**没有暴露"向指定 session 主动下发一条自定义帧"的 API**。
- 项目自身的会话层 `McpAuthContext.SESSION_AUTH` 只存 `sessionId → AuthContext` 用于鉴权关联，**既不持有连接引用，也没有 `agentId → 连接` 的反向注册表**，因此平台侧无法定位"某个 Agent 的那条 MCP SSE 连接"并主动写入。
- 强行复用意味着要侵入/魔改 spring-ai MCP server 的传输层，与"不改 Spring AI 基线"的项目边界冲突（`AGENTS.md` 技术边界）。

因此本设计采纳方案A 的**协议选型（SSE）**，但落地为**一条业务自管的专用门铃 SSE 端点**（`/api/agents/doorbell/sse`），连接句柄由 `DoorbellRegistry` 显式持有——这既拿到了方案A"标准协议、无额外重组件"的优点，又绕开了"MCP transport 无主动 push 接口"的现实约束。MCP 主线继续只负责 `pullTasks/claim/submitResult`，与门铃彻底解耦。

---

## 5. 总体架构

```text
                         [平台内]
  任务/子任务发布
  (TaskController.create / SubTaskService / NotificationConsumer)
        │
        ▼
  AgentInboxService.send()  ──写入──►  agent_inbox 表   （已存在，不变）
        │
        │ (事务提交后) 发布 Spring 事件 InboxMessageCreatedEvent
        ▼
  DoorbellRinger  @TransactionalEventListener(AFTER_COMMIT)
        │  ring(agentId, signal)
        ▼
  DoorbellRegistry  (agentId → SseEmitter，进程内 ConcurrentHashMap)
        │  emitter.send(signal)   ← 尽力而为
        ▼
════════════ SSE 长连接（门铃） ════════════
        ▼
                         [外部 Agent]
  收到门铃信号 → 立即调用 MCP pullTasks（不再等 30s 轮询）
        │
        ▼
  claimSubTask → 执行 → submitResult   （走既有 MCP 主线，不变）
```

### 5.1 新增组件（全部最小化）

| 组件 | 模块 | 职责 |
|---|---|---|
| `DoorbellRegistry` | helloai-core | 维护 `agentId → SseEmitter` 进程内映射；注册/注销/查询/响铃；仿 `McpAuthContext` 单例风格 |
| `DoorbellService` | helloai-core | 对外统一 API：`connect(agentId)` 返回 `SseEmitter`、`ring(agentId, signal)`、`disconnect(agentId)`；封装 keep-alive 与异常清理 |
| `InboxMessageCreatedEvent` | helloai-core | 收件箱写入成功后发布的领域事件（携带 `agentId/eventType/refType/refId`） |
| `DoorbellRinger` | helloai-core | `@TransactionalEventListener(AFTER_COMMIT)` 监听上述事件，调用 `DoorbellService.ring(...)` |
| `AgentDoorbellController` | helloai-api | 暴露 `GET /api/agents/doorbell/sse`（Bearer 鉴权），返回 `SseEmitter` |

> 选择"事件 + AFTER_COMMIT 监听"而非在 `send()` 里直接响铃的原因：`AgentInboxService.send()` 是**唯一收件箱写入收口**，在此处发事件可一处覆盖全部三条通知路径（Controller 直发 / SubTaskService 直发 / MQ NotificationConsumer）；`AFTER_COMMIT` 保证"先落库、后响铃"，与项目既有 Outbox / 本地事件的 AFTER_COMMIT 时序哲学一致（`架构设计参考` §5.1 Phase 2F），避免"响了铃但收件箱还没提交，Agent pull 不到"。

---

## 6. 连接生命周期

### 6.1 建连（Agent → 平台）

1. Agent 先 `checkIn`（拿到 ACTIVE 值班租约）——**门铃建连要求持有有效租约**，落实三层模型"先打卡再接电话"。
2. Agent 发起 `GET /api/agents/doorbell/sse`，头带 `Authorization: Bearer {apiKey}`。
3. `AgentDoorbellController` 用现有 Agent apiKey 鉴权解析出 `agentId`；校验该 Agent `status=ACTIVE` 且存在 ACTIVE 租约（`AgentDutyLeaseService.isOnDuty`）。
4. 调 `DoorbellService.connect(agentId)`：创建 `SseEmitter`（超时建议 30 分钟、可配置），注册进 `DoorbellRegistry`（同一 agentId 已有连接则先关旧连、防泄漏，参照 `startLease` 关旧再建新）。
5. 立即回推一条 `type=connected` 的握手信号，便于客户端确认门铃可用。

### 6.2 保活与被动心跳

- 服务端每 N 秒（建议 15s，可配置）发送 SSE keep-alive 注释帧，穿透反代空闲超时。
- **协同双心跳**：门铃 keep-alive / 建连本身可顺带调用 `HeartbeatService.seen(agentId)` 刷新 `last_seen_at`（可选增强，降低 Agent 额外 heartbeat 调用频率）。本轮可先不启用，避免与 §2.2 边界扩张，留作演进。

### 6.3 响铃（平台 → Agent）

1. 任意路径写入 `agent_inbox` 成功并事务提交。
2. `DoorbellRinger` 收到 `InboxMessageCreatedEvent`。
3. `DoorbellService.ring(agentId, signal)`：
   - `DoorbellRegistry` 查 `agentId` 是否有活跃 `SseEmitter`；
   - 有 → `emitter.send(signal)`（信号仅含 `type/eventType/refType/refId`，**无正文**）；发送异常 → 注销该连接（等待客户端重连），**不重试、不抛错**；
   - 无 → 静默跳过（Agent 未连门铃 / 未在岗，靠兜底轮询兜住）。

### 6.4 断连与清理

- 客户端断开 / `SseEmitter` `onCompletion` / `onTimeout` / `onError` → 从 `DoorbellRegistry` 注销 `agentId`。
- Agent `checkOut` 或租约 `EXPIRED` → 可主动 `DoorbellService.disconnect(agentId)`（本轮可依赖 SSE 超时自然回收，主动断连作为增强）。
- 平台重启 → 所有门铃连接丢失，客户端负责重连（指数退避）；重连前的空窗由兜底轮询覆盖。

---

## 7. 与现有主线的对接点（精确到类）

| 对接面 | 现有类 / 位置 | 改动 |
|---|---|---|
| 收件箱写入收口 | `AgentInboxService.send()`（helloai-core） | `save(inbox)` 成功后 `applicationEventPublisher.publishEvent(new InboxMessageCreatedEvent(...))`；`DuplicateKey` 幂等跳过分支**不发**事件（避免重复响铃） |
| 事件监听响铃 | 新增 `DoorbellRinger`（helloai-core） | `@TransactionalEventListener(phase = AFTER_COMMIT)` |
| 门铃端点 | 新增 `AgentDoorbellController`（helloai-api） | `GET /api/agents/doorbell/sse`，复用现有 Agent Bearer 鉴权链 |
| 鉴权 | 现有 Agent apiKey 鉴权（与 REST 工具同源） | 复用，不新增 token 体系 |
| 值班校验 | `AgentDutyLeaseService.isOnDuty(agentId)` | 建连前置校验 |
| 双心跳 | `HeartbeatService.seen(agentId)` | 可选：建连/保活时顺带刷新（本轮可不启用） |
| MCP 消费主线 | `McpMcpServer.pullTasks/claimSubTask/submitResult` | **完全不改**，门铃只是提前触发它们 |
| MQ | `RabbitMQConfig` / `NotificationConsumer` | **不改 topology**；响铃发生在 inbox 写入之后，与 MQ 是否参与该条通知无关 |

> 关键：门铃**不新增任何 MQ 队列 / 交换机**，也**不让外部 Agent 接触 MQ**。它挂在"收件箱写入"这一进程内事件上，与通知是否经过 `NOTIFICATION_QUEUE` 解耦。

---

## 8. 门铃信号契约（最小）

```json
{
  "type": "inbox",
  "eventType": "sub_task.assigned",
  "refType": "sub_task",
  "refId": 12345,
  "serverTime": "2026-07-17T10:00:00+08:00"
}
```

- `type`：`connected`（握手）/ `inbox`（有新收件箱消息）/ `keepalive`（保活，可用 SSE 注释帧替代）。
- 其余字段直接取自 `agent_inbox` 行，**不含 title/summary/正文**——正文由 Agent 随后 `pullTasks` 获取，保证门铃丢失不丢信息。
- 客户端约定（写入注册时下发的 skills）：**收到 `type=inbox` 即调用 `pullTasks`**，不依赖 `refId`（`refId` 仅用于日志/去抖）。

---

## 9. 可靠性与边界

- **尽力而为**：响铃失败/无连接一律静默降级到轮询；门铃**永不**成为任务可达性的唯一依赖。
- **不丢消息**：消息事实永远先落 `agent_inbox`；门铃只是"催一下"。这与 `agenthub.md` §8.4"通知丢失不致命"完全一致。
- **幂等**：Agent 可能同时被门铃唤醒并撞上定时轮询，`pullTasks` 只读不改状态、`claimSubTask` DB 原子认领，天然幂等，无重复消费风险。
- **单实例假设**：`DoorbellRegistry` 是进程内 Map。若 Agent 的门铃连在实例 B、而收件箱写入发生在实例 A，A 的响铃找不到连接 → 该次靠轮询兜底。多副本实时性优化见 §10。
- **连接上限**：`SseEmitter` 数量 = 在岗外部 Agent 数，量级可控；仍建议对单 Agent 只保留一条门铃连接（关旧建新）。
- **鉴权**：门铃端点必须 Bearer 鉴权并校验在岗；未鉴权/未在岗一律拒连。

---

## 10. 代码落点与最小 PR 拆分

建议按以下顺序小步落地，每步可独立验证：

1. **PR-1 门铃内核**：`DoorbellRegistry` + `DoorbellService` + `AgentDoorbellController`（`GET /api/agents/doorbell/sse`），先做"能连上、能收到 `connected` 握手、断连能清理"。此时无响铃来源，用一个临时管理端点或单测手动 `ring` 验证。
2. **PR-2 响铃接线**：`InboxMessageCreatedEvent` + 在 `AgentInboxService.send()` 发事件 + `DoorbellRinger` 监听 `AFTER_COMMIT` 响铃。打通"发任务 → 门铃响 → 客户端被唤醒"。
3. **PR-3 值班/鉴权收口 + 兜底验证**：建连前置 `isOnDuty` 校验；构造"门铃断开仍能靠轮询消费"的回归用例。
4. **（可选）PR-4 增强**：门铃保活顺带刷 `last_seen_at`；`checkOut`/租约到期主动断门铃。

代码落点建议：

| 模块 | 落点 |
|---|---|
| 门铃注册表 / 服务 / 事件 / 监听 | `helloai-core/.../doorbell/`（新增包） |
| 门铃 SSE 端点 | `helloai-api/.../controller/AgentDoorbellController.java` |
| 收件箱发事件 | `helloai-core/.../service/AgentInboxService.java`（`send()` 末尾） |
| 配置项 | `helloai.doorbell.*`（超时、keep-alive 间隔、是否刷心跳），仿 `helloai.dispatch.*` 集中管理 |

> 不新增 Flyway / 表结构：门铃是纯运行时连接态，不落库（连接态本就不该持久化）。

---

## 11. 验收标准

门铃通道最小版完成后，至少应能验证：

1. 在岗 Agent 能通过 `GET /api/agents/doorbell/sse` 建立 SSE 门铃并收到 `connected` 握手。
2. 给该 Agent 产生一条收件箱消息（创建任务 / 分配子任务），门铃**秒级**收到 `type=inbox` 信号。
3. Agent 收到信号后调用 `pullTasks` 能取到该消息 → `claim` → `submitResult` 全链路走通（与既有主线一致）。
4. **门铃断开**（关闭 SSE）后再产生消息，Agent 仍能通过 `pullTasks` 定时轮询消费——证明门铃丢失不致命。
5. 同一 Agent 重复建连只保留一条活跃连接，旧连接被正确清理（无泄漏）。
6. 未鉴权 / 未在岗的建连请求被拒绝。

---

## 12. 后续演进（超出本轮范围）

- **多实例实时性**：`DoorbellRinger` 改为发布到 Redis Pub/Sub（或复用 RabbitMQ fanout），各实例订阅后只向本地 `DoorbellRegistry` 命中的连接响铃；解决"连接与写入不在同一实例"的空窗。
- **AgentHub V2 Bridge**：为 stdio / 用户驱动型 Agent 提供本地常驻 Bridge，订阅门铃后在本机触发 CLI / MCP Client；门铃契约不变。
- **双向升级**：若需服务端下发控制命令（`STOP/PAUSE/REPLAN`，见 `架构设计参考` §3.7），可将门铃从 SSE 升级为 WebSocket，注册表与响铃抽象保持不变。
- **一键安装 / 产品化接入包**：AgentHub V3 后段。

---

## 13. 一句话总结

> 门铃通道 = 挂在"收件箱写入"事件上的一条 SSE 单向长连接：先打卡、再连门铃，任务一落库就秒级催 Agent 走 MCP 拉活；MCP 仍是唯一任务协议，门铃丢了就退回轮询，绝不丢任务。
