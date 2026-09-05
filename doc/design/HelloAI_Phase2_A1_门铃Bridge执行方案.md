# HelloAI Phase 2 A1：门铃通道验收收口 + Python Bridge（外部 Agent 实时协作闭环）

> 主轴：把外部 Agent 的任务感知时延从轮询级（0~30s）降到秒级，打通「打卡上班（V1 已交付）→ 平台发任务 → 门铃唤醒 → Bridge 代执行 MCP → 结果统一回写」全链。对应架构设计参考 §5.0 战略拍板「外部 AI Agent 实时协作闭环 = 最高优先级」。
>
> 文档约定：§0 事实基线校准（本方案最重要的架构知识）；§1 任务定位与拍板决策；§2 范围边界；§3 Bridge 架构与职责；§4 Bridge 状态机；§5 Step 分解（S0-S4 含验收口径）；§6 工程规范适配与技术默认值；§7 依赖与提交。
>
> **状态**：复核后主体搁置（2026-09-05 R2）。外部 Agent 单向执行器不可改造 + 无人值守 headless 执行不可行，Bridge（A1b S1-S4）**砍除**；A1a（S0 门铃服务端回归验收）保留为独立小任务。§1 拍板 D-A1-1/D-A1-2 随 R2 撤销。

---

## §0 事实基线校准（动手前必读）

本节纠正「门铃是唯一缺失硬骨头」的文档口径偏差。按事实源优先级（代码 > 差距表 > 历史文档）核实：

### 0.1 门铃服务端（V3 门铃层）已 100% 落地且在运行

设计文档 [HelloAI_门铃通知通道设计.md](../archive/HelloAI_门铃通知通道设计.md) §10 的 PR-1~PR-4 **全部做完**：

| PR | 设计内容 | 代码现状 |
|---|---|---|
| PR-1 内核 | Registry/Service/Controller | ✅ `helloai-core/.../shared/doorbell/`（DoorbellRegistry/DoorbellService/DoorbellServiceImpl/DoorbellSignal）+ `helloai-api/.../AgentDoorbellController`（`GET /api/agents/doorbell/sse`） |
| PR-2 响铃接线 | inbox 事件 → AFTER_COMMIT 响铃 | ✅ `AgentInboxServiceImpl.send()` 发 `InboxMessageCreatedEvent` → `DoorbellRinger`（`@Async("doorbellExecutor")` + AFTER_COMMIT） |
| PR-3 值班/鉴权收口 | isOnDuty 建连校验 | ✅ `connect()` 前置校验 + AuthInterceptor Bearer 鉴权（`_authId` 注入） |
| PR-4 增强 | keepalive + 租约断连 + 双心跳 | ✅ `DoorbellKeepaliveTask`（15s）+ `DoorbellDutyListener`（DutyLeaseClosedEvent 断连）+ `refresh-heartbeat`（默认关） |

配套 5 个测试类（Registry/Service/Ringer/KeepaliveTask/DutyListener）+ `DoorbellProperties`（`helloai.doorbell.enabled` 默认 **true**）。

### 0.2 2026-08-07 搁置的真实原因（客户端瓶颈）

各门铃代码类头部状态注记一致：**外部 AI Agent（Qoder/Trae/Claude Code 等 CLI 形态）是「用户发起 → 执行一次 → 结束」的单向执行器，无法常驻监听 SSE 信号，且 Agent 端代码不可修改**。任务感知一律由 `pullTasks` 轮询承担，SKILL.md 已下线门铃内容。

### 0.3 结论：A1 的真实形态

架构参考 §5.0 说「唯一缺失的硬骨头 = V3 门铃层 + V2 轻量 Bridge」——**V3 门铃层实际已在，真正缺的是 V2 轻量 Bridge**（agenthub.md §7.3：运行在用户本机、订阅门铃、收到信号后唤起本机 CLI Agent 的常驻 daemon；门铃设计 §12 明确「门铃契约不变」）。

因此：

- **A1a 服务端验收收口**（小）：Phase 0/1 大改（Consumer 重构、Runtime 唯一化）后，回归验证门铃服务端链路仍通（§5 S0）
- **A1b V2 轻量 Bridge**（主体）：客户端侧新交付物，解决「信号无人消费」

---

## §1 任务定位与拍板决策

**目标一句话**：门铃响了有人接——Bridge 作为本机常驻 daemon，收到门铃信号后代 Agent 走 MCP 全链（pull → claim → 执行 → 回传），外部 CLI Agent 只是本机执行引擎。

### 决策记录

| # | 决策点 | 选项 | 拍板 | 理由 |
|---|---|---|---|---|
| D-A1-1 | Bridge 消费门铃信号后的唤起形态 | 甲 headless 自动拉起 / 乙 桌面通知半自动 / 丙 Bridge 代执行 MCP / 丁 分阶段混合 | **丙** | 接入体验最完整，接近 agenthub §7.3 完整 V2 定义；CLI Agent 无需任何改造（headless 模式只吃提示词吐结果，不感知平台协议） |
| D-A1-2 | Bridge 技术栈 | Python / Node.js / Java | **Python** | 单文件或小包即可常驻，aiohttp/httpx 生态成熟，跨 Windows/macOS/Linux，可演进 pip 安装包；与平台 Java 栈解耦（客户端工具不绑平台栈） |

### 与 P2 批次的关系

本任务是 P2-A 批（外部 Agent 实时协作闭环）的第一步：A1 门铃+Bridge → A2 回退对接（N11 阈值回退与门铃链对接验收）→ A3 N-008 消息生命周期 → A4 ③ Poller 重定位 + N-010 MQ 治理。P2-B（P1 PARTIAL 清账）按用户拍板穿插推进；P2-C 设计预研并行。

---

## §2 范围边界

**做**：

- A1a：验证现有门铃服务端在 Phase 0/1 大改后链路无退化（不改代码，除非发现退化）
- A1b：Python Bridge 常驻 daemon——MCP 会话维持 + 值班代管 + 门铃订阅 + 任务消费 + CLI 执行编排 + 结果回传 + 可靠性

**不做**（明文边界）：

- 平台 Java 侧主链零改动（A1a 发现退化除外）
- 多实例门铃 fanout（门铃设计 §12 演进项，单实例假设维持：连接与写入不在同一实例时靠轮询兜底）
- 一键安装/产品化接入包（AgentHub V3 后段）
- Bridge 并行多任务槽（串行起步，留演进）
- Planner 自动拆解（§5.0 暂缓）、Workflow/Team（P2-C）
- 不动 MQ topology、不新增门铃之外的队列、不碰 `AgentStatus` 枚举

**职责边界**（agenthub.md §7.2，红线）：Bridge 不是新的平台协议、不直写平台 DB、不重写状态机；任务正文与结果一律走 MCP 工具面。

---

## §3 Bridge 架构与职责

```text
[平台侧 Java，已就绪，不改]                [用户本机，A1b 新增]

 TaskController.create ──► agent_inbox 落库
        │ (AFTER_COMMIT)
        ▼
 InboxMessageCreatedEvent ──► DoorbellRinger ──► DoorbellRegistry(agentId→SseEmitter)
        │                                              ▲
        │                                              ║ SSE 长连接（门铃，Bearer 鉴权）
        ▼                                              ║ GET /api/agents/doorbell/sse
 ════════════ MCP-over-SSE（任务协议，唯一） ═════════════════════════════════════
        ║                                              ║
        ║ /mcp/sse + /mcp/messages                     ▼
        ║                          ┌──────────────────────────────────┐
        ║   checkIn/heartbeat  ◄───┤  Python Bridge（常驻 daemon）    │
        ║   pullTasks/ack      ◄───┤  ① MCP Client 会话维持           │
        ║   claimSubTask       ◄───┤  ② 门铃订阅 + 兜底轮询            │
        ║   submitResult/      ◄──┤  ③ 任务编排状态机                 │
        ║   reportBlocked         │  ④ 结果回传                      │
        ║                          │  ⑤ CLI Agent 适配器（命令模板可配） │
        ║                          └──────────────┬───────────────────┘
        ▼                                         │ spawn + 超时控制
   平台状态机统一回写（ExecutionResultHandler      ▼
   / Review 链，与内部 Agent 同链）          本机 CLI Agent（Qoder/Trae/Claude
                                          Code 等，headless 模式，只吃提示词
                                          吐执行结果，不感知平台协议）
```

### Bridge 五大职责（对应 §4 状态机）

1. **MCP Client 会话维持**：GET `/mcp/sse` 握手（首帧 endpoint 事件带 sessionId）→ initialize → `tools/call` JSON-RPC 封装；实现时以实际抓包为准（spring-ai webmvc SSE transport）
2. **值班代管**：启动 checkIn → 定时 heartbeat → 优雅关闭 checkOut；崩溃不 checkOut 时靠平台租约过期回收（A2 验收点）
3. **门铃订阅 + 兜底轮询**：门铃 SSE `type=inbox` 秒级唤醒；门铃不可用/断连期间兜底轮询（30s）照常 pull——**门铃永不成为任务可达性的唯一依赖**
4. **任务编排**：pullTasks → ack → claimSubTask（DB 原子认领）→ 组装提示词 → spawn CLI → 采集结果
5. **结果回传**：submitResult（成功/失败两路）或 reportBlocked；resultId 由 subTaskId 派生稳定幂等键

### 提示词组装的数据来源（契约已核实）

`pullTasks` 返回 `Message`：messageId/type/subTaskId/taskId/title/priority/deadline/summary/read/reassigned/currentAgentId（[McpToolService.java](../../helloai-core/src/main/java/com/helloai/core/agent/service/McpToolService.java) L66-87）；前置产出经 `getDepsSummary`；skills 取注册时下发的清单。S3 落地时以该文件实际字段为准。

---

## §4 Bridge 状态机

```text
                 ┌─────────┐ 启动: 读配置, 校验 api-key
                 │ BOOTING │
                 └────┬────┘
                      ▼  checkIn（失败: 指数退避重试, 5 次后 FATAL 退出）
              ┌──────────────┐
      ┌──────►│   IDLE 值班中 │────────────────────────────┐
      │       │ (心跳T+租约T) │                              │
      │       └──┬───────┬───┘                              │
      │          │       │                                  │
      │  门铃 type=inbox 或 兜底轮询(30s)                      │
      │          │       └── pullTasks 无可领消息 ──► 保持 IDLE │
      │          ▼                                          │
      │   ┌────────────┐  有消息: ack → claimSubTask          │
      │   │  PULLING   │─────── claim 失败(被抢) ──► IDLE ────┤
      │   └─────┬──────┘                                    │
      │         │ claim 成功                                  │
      │         ▼                                           │
      │   ┌────────────┐  组装提示词(title/summary/          │
      │   │ EXECUTING  │   getDepsSummary 前置产出/skills)    │
      │   │ (CLI 超时T)│── spawn 本机 CLI, 采集 stdout         │
      │   └─────┬──────┘                                    │
      │         │ CLI 结束/超时/失败                           │
      │         ▼                                           │
      │   ┌────────────┐  成功: submitResult(success,        │
      │   │ REPORTING  │   output)   失败: submitResult(failed │
      │   └─────┬──────┘   ,error) 或 reportBlocked(reason)  │
      │         │ 回传完成（含提交失败重试 3 次）                │
      └─────────┴───────────────────────────────────────────┘

 异常路径:
  门铃断连 ──► 退避重连(1s/2s/4s/...max 60s) ──► 期间由兜底轮询覆盖
  心跳失败 ──► 连续 N 次失败 ──► 重新 checkIn（租约可能已被平台回收）
  租约过期 ──► 平台侧: 租约回收 + N11 阈值回退 API_KEY_LLM（A2 验收点）
  Ctrl+C  ──► 优雅关闭: checkOut(reason=bridge_shutdown)
```

**幂等要点**：Agent 可能同时被门铃唤醒并撞上定时轮询——`pullTasks` 只读不改状态、`claimSubTask` DB 原子认领、`submitResult` resultId 幂等，天然无重复消费风险（门铃设计 §9 同源结论）。

---

## §5 Step 分解（依赖顺序 + 验收口径）

| Step | 内容 | 依赖 | 验收口径 |
|---|---|---|---|
| **S0 = A1a** 服务端门铃验收收口 | 跑门铃 5 个测试类回归；环境可用时 e2e：curl 建连收 `connected` 握手 → 造一条 inbox 消息 → 秒级收到 `type=inbox` → 断连后轮询仍可消费 → 未鉴权/未在岗拒连（对照门铃设计 §11 六条） | 无 | 六条全过 + LOG 回填；**发现退化则先修复再继续**（重点核对 Phase 0/1 改造是否碰过 `AgentInboxServiceImpl.send()` 事件链） |
| **S1** Bridge 骨架 + MCP 会话 + 值班代管 | `bridge/` 目录建 Python 工程；config.yaml（platform base-url / api-key / heartbeat 间隔 / CLI 命令模板 / 轮询间隔）；MCP client（握手 → initialize → tools/call 封装）；checkIn → 定时 heartbeat → Ctrl+C 优雅 checkOut | S0 | 平台侧租约 ACTIVE 且持续续期 ≥5 分钟；checkOut 后租约 CLOSED；kill -9 后平台租约到期自然回收 |
| **S2** 门铃订阅 + 任务拉取 | 门铃 SSE 订阅（Bearer）→ `type=inbox` → pullTasks(EXECUTOR) → ack → claimSubTask；门铃断连退避重连；兜底轮询 30s | S1 | 平台派单 → 门铃秒级响 → Bridge claim 成功（DB 认领原子）；杀门铃连接 → 轮询兜底仍消费到同一条；重复 pull 无副作用（幂等） |
| **S3** CLI 执行编排 + 结果回传 | claim 后组装提示词 → spawn 本机 CLI（模板可配）→ 超时控制 → 采集结果；submitResult 两路 + reportBlocked；串行单任务槽 | S2 | 全链 e2e：平台派单 → 子任务 IN_PROGRESS → CLI 执行 → submitResult → 子任务进 REVIEW（与内部 Agent 同回写链）；CLI 失败 → submitResult(failed) 路径；CLI 超时 → 超时路径 |
| **S4** 可靠性收口 + A2 对接验证 + 文档回填 | 日志（本地文件+轮转）；重连/退避/异常路径全面演练；**A2 验收**：Bridge kill -9 不 checkOut → 平台租约过期回收 + N11 阈值回退 API_KEY_LLM 全链；Bridge README（使用说明，必要文档）；差距表登记 + LOG | S3 | Bridge 崩溃 → 平台在租约 TTL + 阈值后正确回退/回收；回退子任务执行成功；全部文档回填完成 |

---

## §6 工程规范适配与技术默认值

### 工程规范

- Bridge 是 Python 工程，`.ps1/.sh` 编码头规则不适用；所有 py 文件 UTF-8
- 平台侧零改动承诺内含红线：不动 MQ topology、不新增门铃之外的队列、不碰 `AgentStatus` 枚举
- Bridge 目录放仓库根 `bridge/`（与 helloai-* 模块平级，不进 Maven 工程）
- 配置仿 `helloai.dispatch.*` 集中管理思路：config.yaml 单文件 + 环境变量覆盖

### 技术默认值（可执行中反馈调整）

| 项 | 默认值 |
|---|---|
| 任务槽 | 串行单任务（并行留演进） |
| 兜底轮询 | 30s |
| heartbeat 间隔 | 60s |
| CLI 执行超时 | 30min |
| resultId | subTaskId 派生稳定幂等键 |
| 门铃重连退避 | 1s 起指数，60s 封顶 |
| checkIn 重试 | 5 次后 FATAL 退出 |
| submitResult 提交失败重试 | 3 次 |

---

## §7 依赖与提交

- **前置依赖**：无硬前置（门铃服务端已在；本方案 S0 即其验收）
- **提交拆分**：A1a 若有修复 → Java 代码笔 + 文档笔（仓库惯例）；A1b → `bridge/` Python 代码独立笔 + 文档笔
- **LOG 编号**：续 `doc/log/2026-09.md`（LOG-20260905-004 起）
- **文档回填点**（收口时）：本方案状态表、差距表（门铃闭环 + Bridge 条目登记）、架构设计参考 §5.0 口径校准（「唯一硬骨头」表述更新为 Bridge）、月度 LOG
- **git push 由用户执行**（项目惯例：AI commit，用户 push）

---

## 修订记录

### R2（2026-09-05）：复核后主体搁置——Bridge 砍除，S0 保留

- **背景**：用户复核质疑「门铃 Bridge 是否可不做」——调研结论（外部 AI agent 无法常驻/改造，只能靠 SKILL + 会话内调试达到自动接单提交效果）与 §0.2 搁置事实一致；Bridge 以「本机常驻 Python daemon + headless CLI 执行引擎」绕道，依赖三个前提均不成立：①各 CLI 无可靠无人值守 headless 模式（无 IDE/仓库上下文、agentic 循环受限）②用户本机常驻 daemon + 代持 Agent 身份（审计归因失真 + 运维成本）③秒级唤醒价值只在无人值守场景成立（有人值守会话模式 30s 轮询可接受）。
- **决策**：A1b（S1-S4 Python Bridge）**砍除**；A1a（S0 服务端门铃回归验收）**保留**为独立小任务（半天级，防 Phase 0/1 大改退化 + 保住已运行资产状态声明）。§1 D-A1-1/D-A1-2 撤销；§3/§4 架构与状态机保留为历史设计资产不再实施。
- **替代方向**：P2-A 外部协作主线替换为「接单类 SKILL 试点」（会话内 pullTasks → 执行 → submitResult 闭环；skill 多 IDE 分发机制已由 helloai-preflight 验证可行）——见 LOG-20260905-004。
- **口径回填**：架构参考 §5.0 状态注记追加复核结论；差距表无对应条目不变更；LOG-20260905-004。

### R1（2026-09-05）：A1 执行方案定稿

- **背景**：Phase 1 五件套收口（LOG-20260905-003）后进入 P2-A 批；用户拍板 D1=甲（A3 后置）/ D2=乙（B 批穿插）/ D3=乙（C 批设计预研并行）。
- **关键校准**：核实代码事实——门铃服务端（V3 门铃层）已全量落地（PR-1~PR-4 + 测试 + 默认 enabled），2026-08-07 搁置原因是客户端瓶颈而非服务端缺失；A1 主体因此从「建门铃」转为「建 Bridge」（V2 轻量 Bridge，Python）。
- **拍板**：D-A1-1 = Bridge 代执行 MCP（CLI Agent 只是本机执行引擎）；D-A1-2 = Python。
- **范围**：S0 服务端验收收口 + S1-S4 Bridge 落地（骨架/门铃订阅/执行编排/可靠性收口）；平台 Java 侧零改动（退化除外）。
