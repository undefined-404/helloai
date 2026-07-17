# HelloAI AgentHub 方案文档

> 文档定位
>
> - 本文档用于沉淀 HelloAI 在“外部 Agent 接入层增强”方向上的专项方案，供后续逐步扩展实现时参考。
> - 本文档描述的是设计方案、版本拆分、能力映射与演进边界，不直接代表当前代码已经实现的事实状态。
> - 当前项目现实基线以 `doc/HelloAI_项目基线文档.md` 为准。
> - 当前差距判断与排期优先级以 `doc/HelloAI_实现差距表.md` 为准。
> - 实际某一轮做了什么，以 `doc/HelloAI_迭代执行记录.md` 为准。
> - 本文档吸收并替代 `doc/helloai_agenthub_complete.md` 中仍有保留价值的设计想法；旧文档保留为历史草案，不再作为后续扩展的主参考。

**版本**：2026-07-15  
**适用范围**：HelloAI 外部 Agent 接入层、值班态建模、Bridge 守护进程、门铃通知通道与看板增强  
**阅读前置**：`doc/HelloAI_项目基线文档.md`、`doc/HelloAI_实现差距表.md`、`doc/HelloAI_调度解耦重构分析.md`、`doc/HelloAI_架构设计参考.md`、`doc/HelloAI_CODE_STYLE.md`

---

## 1. 背景与问题定义

### 1.1 背景

当前 HelloAI 已具备外部 Agent 的最小执行闭环：

- `MCP-over-SSE` 主通道
- `pullTasks / ack / claimSubTask / heartbeat / uploadArtifact / submitResult / reportBlocked / getAgentStatus`
- `last_seen_at / last_active_at / online_status` 在线状态三件套
- 调度与执行逐步解耦后的统一结果回写链

但在外部 Agent 接入体验上，仍存在以下问题：

- 外部 Agent 是否“在线”与是否“愿意接单”尚未显式区分
- 现有 `pull -> ack -> claim` 模型能保证可靠性，但无法单独表达“值班中、优先分配、随时待命”的业务语义
- 对不能稳定保持后台常驻形态的第三方 Agent，还缺少一层本地 Bridge / Daemon
- 管理后台当前已有统计能力，但还没有面向“值班态 / 接单态 / 待唤醒态”的专项视图

### 1.2 本文档要解决的问题

本文档不尝试再造第二控制面，也不重做一套新的任务协议，而是回答：

- 如何在不破坏当前 `MCP SSE` 主线的前提下，引入“打卡上班”的业务语义
- 如何分阶段补齐 `duty lease`、Bridge、通知门铃与看板增强
- 如何吸收 `helloai_agenthub_complete.md` 中有价值的想法，同时避免其与当前项目基线冲突

### 1.3 一句话定位

> HelloAI AgentHub 不是新的调度内核，而是建立在现有 `MCP-over-SSE + 统一结果回写 + DB 事实中心` 之上的外部 Agent 接入层增强方案。

---

## 2. 与旧文档的关系

`doc/helloai_agenthub_complete.md` 中的核心灵感是正确的：

- 用“打卡上班”隐喻表达 Agent 是否进入接单窗口
- 强调“通知即时，消费自主”
- 引入 Bridge 守护进程降低第三方 Agent 接入门槛
- 用看板提升可观测性

但旧文档中的部分具体实现不适合直接照搬到当前项目：

- 不应直接把 `AgentStatus` 扩成 `OFFLINE / ON_DUTY / WORKING / OFF_DUTY / SUSPENDED`
- 不应把 WebSocket 提升为新的主任务协议平面
- 不应让 `taskDone` 直接绕过平台结果回写层推进状态机
- 不应把值班管理、状态机、执行协议、通知协议揉成一套新控制面

因此，本文档采取“保留理念、重写落点”的方式，统一为三阶段版本：

- V1 最小版：补值班租约与调度优先语义
- V2 增强版：补 Bridge 守护进程，但仍桥接当前 `/mcp/sse`
- V3 产品版：补门铃通知通道与一键安装，但通知通道只负责唤醒

---

## 3. 统一设计边界

### 3.1 保持不变的项目级约束

以下约束为本方案的硬边界：

- 不引入第二控制面
- 不改变当前 `MCP-over-SSE` 作为主协议通道的定位
- 不让外部 Agent 直接消费 RabbitMQ 或直接写 DB
- 不新增第二套与 `online_status` 平行竞争的 Agent 主状态枚举
- 不让外部 Agent 直接推进平台状态机，只允许上报事实

### 3.2 本方案中的三类状态语义

为了避免与当前基线冲突，本方案将状态拆为三层，而不是塞进一个枚举：

| 层次 | 当前来源 | 语义 | 是否新增 |
|---|---|---|---|
| 管理态 | `AgentStatus` | Agent 是否启用、是否允许被平台纳管 | 否 |
| 在线计算态 | `AgentOnlineStatus` + 心跳三件套 | Agent 最近是否在线、是否活跃、是否休眠 | 否 |
| 值班/接单态 | `agent_duty_lease` 或同义模型 | Agent 是否主动进入“愿意接单”的值班窗口 | 是 |

这意味着：

- `ONLINE` 不等于“正在值班”
- `IDLE` 不等于“可优先分配”
- “上班/下班”应建模为**值班租约**，而不是硬塞进 `AgentStatus`

### 3.3 事实源优先级

本方案若与其他历史文档冲突，按以下顺序判断：

1. 代码与运行结果
2. `V1__init_all.sql` 与数据库结构
3. MCP 验收脚本与可复现实验结果
4. `doc/HelloAI_实现差距表.md`
5. `doc/HelloAI_项目基线文档.md`
6. 本文档
7. `doc/helloai_agenthub_complete.md` 等历史草案

---

## 4. 当前代码基线映射

本方案不是从零开始，当前代码中已有多处可直接承接的骨架：

### 4.1 已有协议与工具

- `McpToolService.pullTasks()`：拉取待处理收件箱
- `McpToolService.ack()`：确认消息已处理
- `McpToolService.claimSubTask()`：原子认领
- `McpToolService.heartbeat()`：刷新 `last_seen_at`
- `McpToolService.submitResult()`：进入统一结果回写链
- `McpToolService.reportBlocked()`：上报阻塞事实

这些能力已经满足“消费自主、结果回传”的主协议要求。

### 4.2 已有调度与选择能力

- `ResilientDispatcher` 负责弹性分配与 fallback
- `AgentSelector` 已支持：
  - 仅选 `ACTIVE`
  - 跳过 `OFFLINE / SLEEPING`
  - 按 `preferExternal / requireIdle / forceAccessType` 进行选择

因此，V1 只需要在候选过滤中叠加“是否存在有效 duty lease”能力，不需要推翻现有调度器。

### 4.3 已有健康检查与看板骨架

- `AgentHealthCheckTask` 已有离线巡检与离线重分配
- `AdminDashboardController` 已有概览、高亮、趋势接口

因此，V1/V2 的看板增强可以走“新增聚合字段 / 新增专项接口 / 前端轮询”，而不是先引入实时推送。

### 4.4 已有架构方向约束

`doc/HelloAI_架构设计参考.md` 已明确：

- 外部 Agent 的最小闭环仍以 `pull -> ack -> claim -> result/blocked` 为核心
- 对不能稳定常驻的第三方 Agent，后续可以补 `Bridge / Daemon + 通知通道`
- 通知通道只负责“门铃/唤醒”，不替代 MCP 能力接口

这正是 V2/V3 的基础。

---

## 5. 总体演进路线

### 5.1 版本拆分

| 版本 | 核心目标 | 主要能力 | 协议形态 |
|---|---|---|---|
| V1 最小版 | 显式化值班态，优先派给值班 Agent | `agent_duty_lease`、`checkIn/checkOut`、选择器接入、看板展示 | 仍以现有 `MCP-over-SSE` 与后台轮询为主 |
| V2 增强版 | 降低第三方接入门槛，补本地常驻能力 | Bridge 守护进程、值班续租、纯主线协议桥接 | 仍桥接 `/mcp/sse`，不新增第二条业务协议 |
| V3 产品版 | 提升实时性和安装体验 | 门铃通知通道、一键安装、产品化接入包 | 通知通道只负责唤醒，MCP 仍是任务协议 |

### 5.2 逐步推进原则

- 先做“小而闭环”的值班态
- 再做“接入便利性”的 Bridge
- 最后做“实时性优化”的门铃通知

不建议的推进顺序：

- 直接先做 WebSocket 主任务通道
- 先扩一整套班次状态机再回头补协议映射
- 在值班模型未稳定前先做复杂前端实时看板

---

## 6. V1 最小版

### 6.1 目标

V1 的目标不是提升“强实时”，而是先把“我现在愿意接单”从在线态中显式剥离出来：

- Agent 可显式上班
- Agent 可显式下班
- 调度时可优先选值班 Agent
- 后台能看到哪些 Agent 正在值班

### 6.2 范围

V1 只包含：

- `agent_duty_lease`（或同义命名）的最小租约模型
- `checkIn / checkOut` 工具
- 选择器接入“仅值班 Agent 可优先分配”
- 管理后台展示值班状态

### 6.3 明确不做

V1 不做以下内容：

- 不做 WebSocket 通知通道
- 不做完整 ShiftManager
- 不做强绑定 8 小时班次模型
- 不改 `AgentStatus` / `AgentOnlineStatus` 枚举体系
- 不把 `taskDone` 作为新状态机命令单独落地

### 6.4 数据模型建议

建议新增一张轻量租约表，例如 `agent_duty_lease`：

| 字段 | 含义 |
|---|---|
| `id` | 主键 |
| `agent_id` | Agent ID |
| `session_id` | 值班会话标识，可用于关联 MCP 会话或 Bridge 会话 |
| `work_mode` | `AUTO / STRICT` 等值班模式（`AUTO` 等同早期口径的 `NORMAL`，表示 Agent 默认可与他人并行；`STRICT` 为独占报锁，仅专属任务才匹配，详见 §6.5） |
| `max_concurrent` | 期望最大并发 |
| `status` | `ACTIVE / CLOSED / EXPIRED / FORCE_CLOSED` |
| `started_at` | 上班时间 |
| `last_renewed_at` | 最近续租时间 |
| `expires_at` | 租约过期时间 |
| `close_reason` | 下班或关闭原因 |

设计意图：

- 用租约表达“接单意愿”
- 用过期时间表达“值班窗口”
- 用会话标识表达“当前这次值班”

### 6.5 工具语义建议

#### `checkIn`

语义：

- Agent 显式声明“进入值班窗口”
- 若已有旧租约，可按“关闭旧租约 -> 创建新租约”处理
- 返回值中不必出现 WebSocket 地址，只需返回租约信息与建议续租间隔

建议返回：

- `leaseId`
- `sessionId`
- `expiresAt`
- `renewAfterSeconds`
- `message`

#### `checkOut`

语义：

- Agent 主动结束当前值班窗口
- 关闭租约
- 对名下未完成子任务触发“主动离岗补偿逻辑”

注意：

- `checkOut` 与“掉线离线”不是同一个事件
- 可复用部分重分配能力，但审计事件必须区分

### 6.6 调度接入建议

V1 不建议把“只派给值班 Agent”做成全局硬规则，而是建议做成：

- 候选优先级 1：同角色 + `ACTIVE` + 在线可用 + 有有效 duty lease
- 候选优先级 2：同角色 + `ACTIVE` + 在线可用 + 无 lease，但允许兜底

这样做的好处：

- 保留当前主线调度兼容性
- 不会因为没有值班 Agent 就让系统完全不可分配
- 能逐步把值班机制引入现网

### 6.7 看板建议

V1 看板只需要轮询版即可，建议新增以下聚合视图：

- 值班 Agent 数
- 值班中且空闲 Agent 数
- 值班中且执行中 Agent 数
- 在线但未值班 Agent 数
- 待认领任务数
- 值班租约即将过期数

### 6.8 代码落点建议

V1 的主要落点建议如下：

| 模块 | 建议落点 |
|---|---|
| SQL / Flyway | `helloai-start/src/main/resources/db/migration/V1__init_all.sql` |
| 核心实体 | `helloai-core/src/main/java/.../entity` |
| 值班服务 | `helloai-core/src/main/java/.../service` |
| MCP 工具扩展 | `McpToolService` / `McpMcpServer` |
| 选择器逻辑 | `AgentSelector` |
| 看板接口 | `AdminDashboardController` 或独立 AgentHub Dashboard Controller |
| 定时收敛 | `helloai-job/src/main/java/.../task` |

### 6.9 验收标准

V1 完成后，至少应能验证：

1. Agent 可通过 `checkIn` 建立值班租约
2. Agent 可通过 `checkOut` 主动结束值班租约
3. 调度优先选择有效租约 Agent
4. 租约过期后不再被视为值班 Agent
5. 后台可区分“在线但未值班”和“在线且值班”

### 6.10 当前已落地部分（2026-07）

截至当前版本，V1 已有以下前置骨架或相关支撑能力落地：

- `agent_duty_lease` 最小模型已进入代码与 `V1__init_all.sql`
- `AgentDutyLeaseService` 已具备：
  - 查询当前有效租约
  - 关闭旧租约
  - 新建租约
  - 续约
- 数据库层已补：
  - `uk_duty_lease_agent_active`，防止同一 Agent 出现多条 `ACTIVE` 租约
  - `fk_duty_lease_agent`，保证租约归属 Agent 的引用完整性
- 与 V1 相关的两条配套能力也已补齐：
  - `ASSIGNED` 超时未 `claim` 的巡检回收与重新分配
  - `credential_vault` 的 Agent API Key 最小轮换语义（`ACTIVE / EXPIRED`）

仍未进入代码实现的部分：

- `checkIn / checkOut`
- `AgentSelector` 的值班优先选择
- 管理后台值班看板
- Bridge 与门铃通知通道

因此，当前项目状态更准确地说是：

- V1 的数据模型和部分配套可靠性能力已落地
- V1 的工具层、调度层与前端层尚未收口完成

---

## 7. V2 增强版

### 7.1 目标

V2 的目标是补一层本地常驻 Bridge / Daemon，解决以下问题：

- 某些第三方 Agent 不能自然保持后台常驻
- 某些执行器更适合由本地守护进程统一代管
- 用户需要更低成本的接入方式

### 7.2 核心原则

V2 必须坚持以下边界：

- Bridge 不是新的平台协议
- Bridge 只是当前 `MCP-over-SSE` 主通道的本地桥接层
- 不引入第二条业务任务协议
- 不让 Bridge 直接写平台 DB

### 7.3 Bridge 职责

Bridge 负责：

- 本地保持与平台 `/mcp/sse` 的会话
- 代执行 `checkIn / heartbeat / pullTasks / ack / claimSubTask / submitResult / reportBlocked`
- 对接本机的 Claude Code / Trae / Qoder / Codex 或其他 CLI
- 维护本地重连、退避、日志与轻量缓存

Bridge 不负责：

- 重写平台状态机
- 直接解释业务审计规则
- 自行决定数据库状态

### 7.4 对旧文档的适配

旧文档中的 Python Bridge 思路可以保留，但要重写为：

- 不再调用伪造的 `/api/mcp/checkIn` / `/api/mcp/taskDone`
- 优先桥接当前 `/mcp/sse + /mcp/messages`
- 将“任务完成后继续接单或下班”的语义，改为：
  - `submitResult(..., stayOnDuty 扩展参数)`，或
  - `submitResult` 之后显式继续续租，必要时再 `checkOut`

### 7.5 与 V1 的关系

V2 建立在 V1 之上：

- V1 解决值班态建模
- V2 解决本地接入运行形态

如果没有 V1 的 `duty lease`，V2 的 Bridge 仍然可以运行，但会缺少明确的值班表达。

### 7.6 验收标准

V2 完成后，至少应能验证：

1. 本地 Bridge 能维持 `/mcp/sse` 会话
2. Bridge 能完成值班租约续期
3. Bridge 能在本地执行 `pull -> claim -> result/blocked`
4. Bridge 异常退出后，平台能在租约或心跳层正确收敛

---

## 8. V3 产品版

### 8.1 目标

V3 才进入“实时接入体验增强”阶段，核心目标是：

- 门铃通知通道
- 一键安装
- 更像产品而不是脚本集合的接入体验

### 8.2 通知通道定位

V3 可以考虑 WebSocket，但它只能承担：

- 唤醒 Bridge
- 告诉本地 Agent “有新任务了”
- 提前触发本地 `pullTasks`

它不能承担：

- 完整任务协议
- 状态机推进
- 结果回写主链

换句话说，V3 的 WebSocket 只是“门铃层”，不是“任务协议层”。

### 8.3 一键安装

V3 可补以下产品化能力：

- 平台生成安装命令
- 安装 Bridge 到本机固定目录
- 写入配置文件
- 注册 `launchd` / `systemd`
- 提供 `start / stop / status / logs` 统一入口

### 8.4 通知与可靠性边界

V3 的通知机制必须遵守以下原则：

- 通知丢失不致命
- 有通知时加速处理
- 无通知时仍可回退到 `pull` 模型

因此，V3 的理想关系应为：

```text
门铃通知
-> Bridge 被唤醒
-> 仍调用 MCP 主线工具
-> 结果进入统一回写链
```

### 8.5 验收标准

V3 完成后，至少应能验证：

1. 新任务到达时，Bridge 可被门铃通道快速唤醒
2. 通知通道断开时，系统仍能依靠 pull 模型继续工作
3. 一键安装后的 Bridge 可在 macOS / Linux 自启动
4. MCP 主协议与通知通道职责清晰分离

---

## 9. 旧文档能力映射表

| 旧文档能力 | 当前处理方式 | 新文档归属 |
|---|---|---|
| `AgentStatus` 扩展 | 不直接采用 | 改为“管理态 + 在线态 + 值班租约”三层模型 |
| `checkIn` | 保留 | V1 |
| `checkOut` | 保留 | V1 |
| `taskDone` | 不单独直接落 | 融入 `submitResult` 语义扩展 |
| WebSocket Server | 不作为当前主线 | V3 门铃层候选 |
| ShiftManager | 不直接采用 | 改为轻量 `duty lease` |
| 班次记录 | 保留思想，简化 | V1 `agent_duty_lease` |
| 实时任务通知 | 保留方向，后置 | V3 |
| Python Bridge | 保留并重写 | V2 |
| 一键安装脚本 | 保留并产品化 | V3 |
| 实时调度看板 | 保留并分阶段实现 | V1 先轮询，V3 再增强 |

---

## 10. 推荐开发顺序

### 10.1 优先级

| 优先级 | 内容 | 原因 |
|---|---|---|
| P0 | `agent_duty_lease` + `checkIn/checkOut` | 最小改动即可建立值班语义 |
| P0 | 选择器接入“值班优先” | 能立刻让值班态影响调度 |
| P1 | 看板轮询版 | 便于验证值班模型是否好用 |
| P1 | Bridge 纯主线桥接版 | 降低第三方接入门槛 |
| P1 | `ASSIGNED` 超时未 claim 巡检 | 补齐值班模型下的回收能力 |
| P2 | 门铃通知通道 | 真实实时性增强 |
| P2 | 一键安装与产品化交付 | 提升易用性 |

### 10.2 不建议的顺序

- 先做完整 WebSocket 再回头补值班模型
- 先做完整前端大屏再回头补值班租约
- 先做 Bridge 安装器再回头定义协议边界

---

## 11. 后续维护约定

后续若基于本方案继续扩展，应遵守以下约定：

- 新增能力先判断属于 V1 / V2 / V3 哪一层，不要把不同阶段内容混写
- 当前代码实现状态不要直接写进本文档正文，应写入《迭代执行记录》
- 若某项能力已经落地并改变现实基线，需同步更新：
  - `doc/HelloAI_实现差距表.md`
  - `doc/HelloAI_迭代执行记录.md`
  - 必要时更新 `doc/HelloAI_项目基线文档.md`

---

## 12. 一句话总结

> HelloAI AgentHub 的正确实现方式，不是再造一套新的调度控制面，而是围绕现有 `MCP-over-SSE + 统一结果回写 + DB 事实中心` 主线，按 V1 值班租约、V2 Bridge、V3 门铃通知三个阶段稳步增强外部 Agent 接入体验。
