# HelloAI 多类型 Agent 接入与调度可靠性开发路线图 v2.4

**文档版本**: v2.4  
**创建日期**: 2026-07-05（v1.0）  
**修订日期**: 2026-07-07（v2.4，补全实现细节——消除落地歧义）  
**修订说明**:
- v2.0：基于阿里 AgentTeams 的全面架构调研，融入经过生产验证的设计模式
- v2.1：修正代码事实错误、统一 DDL 字段风格、拆分阶段 2 为 2A/2B、管理态/计算态分离
- v2.2：补充 task_timeline DDL、移除 AgentStatus.OFFLINE 冗余、统一 MCP 工具清单、补全 5 因子加权定义、P1/P2 字段落地、向后兼容验收
- v2.3：消除三方传递不一致、agent_mcp_server 重构为纯策略表、HeartbeatService.seen() 增加防护逻辑、明确 N4 心跳方案 B
- v2.4：api_key 迁移方案明确（P4+2B.8）、5 因子评分计算时机+幂等键定义、isSlow 实时任务调度规则、Reconcile 健康检查 CAS 并发保护  
**适用范围**: HelloAI 多 Agent 协作调度平台

---

## 目录

1. [项目背景与现状评估](#一项目背景与现状评估)
   - [1.4 设计原则（v2.0 新增，v2.1 修订）](#14-设计原则v20-新增v21-修订)
2. [核心需求清单](#二核心需求清单)
3. [目标架构](#三目标架构)
   - [3.3 架构哲学：Reconcile 式状态收敛](#33-架构哲学reconcile-式状态收敛)
   - [3.4 与 AgentTeams 的架构差异与定位](#34-与-agentteams-的架构差异与定位)
4. [数据模型扩展](#四数据模型扩展)
5. [阶段 0：Agent 接入类型枚举 + 能力画像 + 状态扩展](#五阶段-0agent-接入类型枚举--能力画像--状态扩展)
6. [阶段 1：可配置工作流模板](#六阶段-1可配置工作流模板)
7. [阶段 2A：API Key 类 Agent Executor（基础链路）](#七阶段-2aapi-key-类-agent-executor基础链路)
8. [阶段 2B：多 Provider + 重试 + 凭证保险库](#八阶段-2b多-provider--重试--凭证保险库)
9. [阶段 3：MCP Server + CLI Agent 桥接](#九阶段-3mcp-server--cli-agent-桥接)
10. [阶段 4：在线状态与熔断降级](#十阶段-4在线状态与熔断降级)
11. [阶段 5：网页版 AI 接入](#十一阶段-5网页版-ai-接入)
12. [技术选型决策表](#十二技术选型决策表)
13. [风险与缓解措施](#十三风险与缓解措施)
14. [简历价值最大化建议](#十四简历价值最大化建议)

**附录**

- [附录 A：与现有 doc 文档的关联](#附录-a与现有-doc-文档的关联)
- [附录 B：术语表](#附录-b术语表)
- [附录 C：AgentTeams 借鉴清单](#附录-cagentteams-借鉴清单)
- [附录 D：与 AgentTeams 的关键差异与 HelloAI 优势](#附录-d与-agentteams-的关键差异与-helloai-优势)
- [附录 E：实施优先级建议](#附录-e实施优先级建议)

---

## 一、项目背景与现状评估

### 1.1 项目本质（基于代码事实，v2.1 修正）

| 维度 | 实际情况 | 数据来源 |
|------|----------|----------|
| 后端栈 | Spring Boot 3.2.6 + MyBatis-Plus 3.5.9 + PostgreSQL 16 + RabbitMQ + Redis | `pom.xml` |
| LLM 集成 | ❌ pom.xml 0 处 LLM SDK 依赖，src 下 0 处 LLM 调用 | 全局 grep 验证 |
| Agent 实现 | Agent = 带 role + apiKey + modelConfig + status + score 的多维调度对象，modelConfig JSONB 描述模型参数（temperature/max_tokens 等）。当前不包含 capabilities / labels / 心跳字段 | `Agent.java`（9 个业务字段） |
| MQ 现状 | Producer 完整；通用模块（helloai-mq）只有 `AbstractIdempotentConsumer` 基类；实际消费逻辑在 job 模块（`NotificationConsumer`，投递通知→inbox）；sub_task 任务消费、CLI 消息消费尚未实现 | `helloai-mq/consumer/` + `helloai-job/consumer/NotificationConsumer.java` |
| MCP 集成 | ❌ 0 处 model.context.protocol 引用 | 全局 grep 验证 |

### 1.2 已有能力盘点（v2.1 修正）

| 能力 | 文件/类 | 状态 |
|------|---------|------|
| Agent 自注册 + onboarding | `AgentController` + `AgentService` | ✅ 已实现 |
| Task / SubTask 状态机 | `TaskController` + `SubTaskService` | ✅ 已实现 |
| Outbox 事务保障 | `AgentOutboxEvent` + `AgentOutboxEventMapper.xml` | ✅ 已实现 |
| Agent Inbox 通知 | `AgentInboxService` | ✅ 已实现 |
| 健康检查任务 | `AgentHealthCheckTask` | ⚠️ AgentStatus 枚举当前仅 ACTIVE/DISABLED 两态，需先扩展枚举才能做状态切换（阶段 0 补充） |
| 隐式评分 | `RewardService` | ⚠️ scoreFactors 5 因子数据模型（SubTask.scoreFactors JSONB）已就位，加权计算逻辑待实现 |
| SKILL.md 动态生成 | `PromptTemplateService.getSkillForAgent()` | ✅ 已实现（类名 PromptTemplateService，实际承担 SKILL.md 渲染职责） |
| E2E 链路验证 | 上轮已 8/8 通过 | ✅ 已验证 |

### 1.3 核心需求

> **重要约定**：本节用 **As a / I want / so that** 用户故事格式描述，描述"用户要什么"，不涉及"如何实现"。

#### 1.3.1 三大产品愿景

| 编号 | 愿景 | 一句话描述 |
|------|------|------------|
| V1 | **协议中立** | 任何能跑 MCP 协议的客户端（不论是 IDE、CLI 还是网页）都能变成可被调度的 AI Agent |
| V2 | **模型解耦** | 调度核心不耦合任何具体 LLM；平台只认身份，不认模型 |
| V3 | **门槛最低** | 让无开发环境的普通用户也能通过白嫖网页 AI 参与协作 |

#### 1.3.2 核心需求 → 工程任务映射表

| 用户故事 | 对应阶段 | 对应工程任务 |
|----------|----------|--------------|
| US-A01 (IDE 客户端接入) | 阶段 3 | MCP Server 实现 + Qoder/Trae 配置文档 |
| US-A02/A03 (工作流模板) | 阶段 1 | 流程模板 CRUD + 调度逻辑改造 |
| US-A04 (全链路可观测) | 阶段 1 | timeline.jsonl 审计快照 |
| US-A05 (在线状态) | 阶段 4 | Redis + DB 双心跳 + HealthCheckTask 改写 |
| US-B01/B02 (API Key 接入) | 阶段 2A | Spring AI 引入 + 复用 springai ChatClient |
| US-B03 (网页 AI) | 阶段 5 | 网页浏览器 MCP 封装 |
| US-B04 (可配置流程) | 阶段 1 | 阶段 1 全流程 |
| US-C01/C04 (CLI 自注册) | 阶段 3 | heartbeat 工具 + MCP 鉴权 |
| US-C02/C03 (任务拉取) | 阶段 3 | pullTasks/ack/uploadArtifact 工具 |
| US-D01/D03 (在线过滤) | 阶段 4 | 双心跳 + 在线过滤（last_seen_at 判定在线，last_active_at 判定活跃） |
| US-D02/D04 (熔断转发) | 阶段 4 | Resilience4j 熔断 + 任务转发 |
| US-D05 (统一接口) | 阶段 2A | AgentExecutor 接口 + ApiKeyExecutor 实现 |
| US-E01 (评分) | 已有 + 阶段 4 完善 | RewardService + scoreFactors 加权计算 |
| US-E02 (择优循环) | 阶段 4 | pickAlternative 选人策略 |

### 1.4 设计原则（v2.0 新增，v2.1 修订）

> 以下原则综合了 AgentTeams 架构调研与 HelloAI 自身定位，作为后续所有开发阶段的**架构约束**。

| # | 原则 | 来源 | 说明 |
|---|------|------|------|
| **P1** | **DB 是唯一权威事实源** | HelloAI 原创 | 所有状态变更落 DB。Redis（心跳缓存）和 MinIO（timeline.jsonl 审计快照）都是**派生镜像**——任何时候可由 DB 重建。关键判定（如离线）必须有 DB 侧可追溯字段（last_seen_at、offline_reason、offline_at——已在 4.1 DDL 中落地）。Redis 重启、MinIO 故障不影响系统正确性 |
| **P2** | **期望态收敛（Reconcile 模式）** | AgentTeams 启发 | 通过状态机 + 审计快照（task_timeline）+ 定时任务实现类 Reconcile 模式——定时任务持续将"实际态"推进到"期望态"。每步操作幂等，失败重试不产生副作用。**不显式引入 desired_status 字段**（避免破坏现有 SubTask 状态机简洁性），而是通过 SubTask.status + task_timeline 事件流 + Job 补偿来驱动收敛。与现有 Outbox 模式互补（Outbox 管消息可靠性，Reconcile 管状态收敛） |
| **P3** | **能力画像优于类型枚举** | AgentTeams 启发 | Agent 的接入方式不仅是 `accessType` 枚举，而是一组可独立配置的 `capabilities`（supportsPull/supportsSSE/supportsMCP 等）。调度器按实际能力匹配 Agent，而非按类型 if/else 分支。accessType 的默认 capabilities 只是注册时的便利初始化，**每个 Agent 实例可以覆盖**——这才是 P3 区别于"按类型分派"的关键 |
| **P4** | **Agent 不持真凭证（工牌模式）** | AgentTeams 启发 | **api_key 永远只代表工牌（consumerToken）**，用于身份识别。真实 LLM API Key 永远只存在于服务端 `credential_vault` 表的 `encrypted_value` 列。历史数据迁移策略（见阶段 2B 任务 2B.8）：核查已存在的 agent.api_key → 若含真实 Key 则加密搬迁至 vault 并重置为新 consumerToken → 若不含则直接语义升级。迁移脚本幂等可回滚 |
| **P5** | **调度核心不耦合具体 LLM** | HelloAI 原创 | 调度核心（状态机、Outbox、Inbox、选人策略）不依赖任何 LLM SDK。平台通过可插拔 `AgentExecutor` 接口承载 LLM 适配——调度逻辑只调接口，不感知底层是 DeepSeek 还是 Claude。Agent 可以是任何 HTTP 端点（人/脚本/IDE/CLI/网页/API） |
| **P6** | **工作流可审计化** | AgentTeams 启发 | 每步状态推进除了更新 DB，同步写一份**人类可读的时间线快照**到 MinIO（`tasks/{taskId}/timeline.jsonl`）。注意：DB 中的 `task_timeline` 表（JSONB）是权威记录，MinIO 是归档镜像——以便管理台直接 SQL 查询和分页，不依赖 MinIO |

---

## 二、核心需求清单

### 2.1 需求总览（按优先级）

| 编号 | 需求 | 优先级 | 类别 |
|------|------|--------|------|
| N1 | Agent 区分三种接入类型 + 能力画像（新增 capabilities 列 + labels 列） | P0 | 数据模型 |
| N2 | 可配置工作流模板（最少 2 角色，最多 4 角色，支持嵌套 Team 模式） | P0 | 产品功能 |
| N3 | MCP Server 暴露 MQ/任务/心跳工具给外部 Agent（工具集合固定，agent_mcp_server 表做开关+策略配置） | P0 | 协议集成 |
| N4 | Agent 心跳与在线判定：last_seen_at（在线依据）+ last_active_at（活跃度）+ Redis TTL（快速过滤缓存） | P0 | 可靠性 |
| N5 | 熔断降级：Agent 失败后任务转发给同角色其他 Agent | P1 | 可靠性 |
| N6 | API Key 类 Agent Executor（直接调 LLM API，含能力校验） | P1 | LLM 集成 |
| N7 | 健康检查任务改写（Reconcile 式：尝试恢复 → 标记离线） | P0 | 可靠性 |
| N8 | 网页版 AI 通过浏览器 MCP 接入 | P2 | 差异化 |
| N9 | Spring AI ChatClient 复用 springai 项目 | P1 | 资源复用 |
| N10 | Agent 工牌模式（api_key 语义升级 + credential_vault 表，仅 API_KEY_LLM 场景） | P1 | 安全 |

### 2.2 详细需求描述

**N1: 三种 Agent 接入方式 + 能力画像**

| 类型 | 枚举 | 典型代表 | 接入方式 | 默认 capabilities（注册时可覆盖） |
|------|------|----------|----------|-------------------|
| A 类 | `CLI_CLIENT` | Qoder / Trae / Codex / Claude Code | MCP-over-SSE | `{supportsPull:true, supportsSSE:true, supportsMCP:true, supportsArtifactUpload:true}` |
| B 类 | `API_KEY_LLM` | OpenAI / Claude / DeepSeek API | HTTP 直调（平台侧 @Async 触发） | `{supportsPull:false, supportsSSE:false, supportsMCP:false, supportsArtifactUpload:false}` |
| C 类 | `WEB_BROWSER` | 网页 DeepSeek / Kimi / Minimax | 浏览器 MCP（Playwright） | `{supportsPull:false, supportsSSE:false, supportsMCP:false, isSlow:true}` |

> **v2.1 关键说明**: capabilities 为 **新增列** `agent.capabilities JSONB`（不是"已有的 context 列"——Agent 表当前仅有 `model_config` JSONB，语义是模型参数，不复用）。capabilities 默认值只是注册时的便利初始化，**每个 Agent 实例可以单独覆盖**——例如两台 Qoder，一台支持 ArtifactUpload，一台不支持，这就是 P3 的核心价值。
>
> **v2.3 调度规则——isSlow 与实时任务**：`isSlow=true` 的 Agent（如 WEB_BROWSER）**不接实时任务**。实时任务定义（满足任一）：
> 1. 模板 `config.priority=REALTIME` 或 `config.sla_minutes <= 30`
> 2. `sub_task.deadline` 距当前 < 30 分钟
>
> isSlow Agent 仅接非实时或低优先级（LOW/BACKGROUND）任务。调度器在 `AgentSelector` 中根据任务属性 + Agent capabilities 联合过滤。

**N2: 可配置工作流**

- 最小配置：`PLANNER + EXECUTOR`（2 角色，线性链）
- 完整配置：`PLANNER + EXECUTOR + REVIEWER + PATROL`（4 角色，线性链）
- Team 模式（v2.0 新增）：`PLANNER → TEAM_LEADER → [EXECUTOR_1, EXECUTOR_2] → REVIEWER`（嵌套子链）
- Agent 选择支持标签匹配（`label_filters`，如 `{"specialty":"frontend","runtime":"claude"}`）

**N3: MCP Server 工具集**

| 工具名 | 用途 | 附带操作说明（mini SKILL.md） |
|--------|------|-------------|
| `pullTasks` | Agent 拉取分配给自己的任务（subscribe 为其别名/兼容入口） | 轮询间隔建议、超时处理 |
| `ack` | 确认消息已处理 | 幂等性说明 |
| `heartbeat` | Agent 上报心跳（刷新 last_seen_at + Redis TTL） | TTL 与续约频率 |
| `uploadArtifact` | 上传执行结果到 MinIO | 文件大小限制、支持的 MIME |
| `getAgentStatus` | 查询 Agent 当前状态 | 返回字段说明 |
| `claimSubTask` | 主动领取任务（同角色竞争）| 并发竞争注意事项 |

> **v2.1 说明**: MCP 工具集合先做固定 6 个（P0），`agent_mcp_server` 表做开关+策略配置+权限控制。运行时动态生成 @Tool 方法留到后续版本评估复杂度。

**N4: 心跳与在线判定（v2.1 修订为三件套）**

| 字段 | 存储 | 更新时机 | 用途 |
|------|------|----------|------|
| `last_seen_at` | DB agent 表 | 收到心跳/拉取/ack 任一请求即刷新 | **在线判定依据**（"Agent 进程是否存活"） |
| `last_active_at` | DB agent 表 | 子任务执行时更新 | **活跃度依据**（"Agent 是否在干活"，用于选人策略偏好） |
| Redis TTL | Redis `agent:heartbeat:{id}` | 心跳请求刷新，5 分钟过期 | **快速过滤缓存**（避免每次调度都查 DB），Redis 重启后回落到 DB last_seen_at |

在线状态判定逻辑：
- **ONLINE**：`last_seen_at` 在 5 分钟内 + `last_active_at` 在 5 分钟内
- **IDLE**：`last_seen_at` 在 5 分钟内 + `last_active_at` 超过 5 分钟或为空
- **OFFLINE**：`last_seen_at` 超过 5 分钟或为空
- **SLEEPING**：用户主动设置，暂停接活但保留身份（仅通过 API 设置，系统不会自动将 Agent 置为 SLEEPING）

**心跳写回策略（v2.3 明确）**：本项目选择**方案 B——心跳即时写回计算态**。心跳请求不仅刷新 `last_seen_at` + Redis TTL，还即时计算 `online_status` 并写回 DB，以提升管理台实时性。防护规则：(1) online_status=SLEEPING 时不被心跳覆盖；(2) `offline_reason`/`offline_at` 仅在 online_status 变为 OFFLINE 时写入；(3) 从 OFFLINE 恢复时清除这两个字段。

---

## 三、目标架构

### 3.1 整体架构图

```
┌──────────────────────────────────────────────────────────────┐
│                    HelloAI 调度平台（核心）                     │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  工作流引擎                                            │    │
│  │   - 流程模板（process_template，支持嵌套 role_chain）   │    │
│  │   - 角色绑定（template_role_binding + label_filters）  │    │
│  │   - 任务状态机 + DB task_timeline 表（MinIO 归档镜像）  │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  Agent 抽象层                                          │    │
│  │   - accessType: CLI_CLIENT / API_KEY_LLM / WEB_BROWSER│   │
│  │   - capabilities JSONB（新增列，可独立覆盖默认值）      │    │
│  │   - labels JSONB（新增列，标签过滤用）                  │    │
│  │   - api_key（语义升级为 consumerToken 工牌）            │    │
│  │   - 三件套心跳：last_seen_at + last_active_at + Redis  │    │
│  │   - AgentOnlineStatus（ONLINE/IDLE/OFFLINE/SLEEPING）  │    │
│  │     计算态，与管理态 AgentStatus(ACTIVE/DISABLED) 分离  │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  AgentExecutor 接口（可插拔）                           │    │
│  │   - ApiKeyExecutor（@Async 触发，Spring AI ChatClient）│   │
│  │   - WebBrowserExecutor（Playwright 桥接）              │    │
│  │   - CliCallbackExecutor（MQ 反向通知，阶段 3）          │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  MCP Server（spring-ai-starter-mcp-server-webmvc）    │    │
│  │   - agent_mcp_server 表（开关+策略配置+权限）           │    │
│  │   - 固定 6 工具：pullTasks/ack/heartbeat/uploadArtifact│   │
│  │     /getAgentStatus/claimSubTask                       │    │
│  │   - 每个工具附带 mini SKILL.md（操作说明 + Gotchas）   │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  可靠性保障                                            │    │
│  │   - Resilience4j 熔断（按 Agent 粒度）                  │    │
│  │   - Reconcile 式健康检查（尝试恢复 → 标记离线）         │    │
│  │   - 三件套心跳在线判定                                  │    │
│  │   - 任务超时重试 / 自动转发（含标签匹配）               │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  凭证保险库（Credential Vault，仅 API_KEY_LLM 场景）    │    │
│  │   - credential_vault 表：AES 加密存储真实 API Key      │    │
│  │   - api_key 语义升级为 consumerToken（工牌）           │    │
│  │   - CLI_CLIENT 类型不受影响（沿用现有 api_key 鉴权）    │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
└──────────────────────────────────────────────────────────────┘
              ▲                       ▼
┌──────────────────────────────────────────────────────────────┐
│                外部 Agent 客户端（多样化）                       │
│  A 类: Qoder / Trae / Codex / Claude Code (MCP-over-SSE)      │
│  B 类: 服务端调用的 LLM（Spring AI ChatClient，平台侧触发）     │
│  C 类: 网页版 AI（Playwright 自动化）                          │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 三类 Agent 协作时序

```
┌─────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│用户 │    │HelloAI   │    │PLANNER   │    │EXECUTOR  │    │REVIEWER  │
└──┬──┘    └────┬─────┘    └────┬─────┘    └────┬─────┘    └────┬─────┘
   │ POST       │               │               │               │
   │ /tasks     │               │               │               │
   ├───────────▶│               │               │               │
   │            │ inbox         │               │               │
   │            ├──────────────▶│               │               │
   │            │               │ 读 SKILL.md   │               │
   │            │               │ 拆子任务       │               │
   │            │               │               │               │
   │            │               │ @Async 触发    │               │
   │            │               │ ApiKeyExecutor│               │
   │            │               ├──────────────▶│               │
   │            │               │               │ 执行           │
   │            │               │               │ 提交结果       │
   │            │ sub_task.review                │               │
   │            ├───────────────────────────────────────────────▶│
   │            │               │               │               │ 打分
   │            │               │               │ reward +N     │
   │            │               │               │◀──────────────┤
   │ GET /tasks │               │               │               │
   │◀───────────┤               │               │               │
```

> **v2.1 说明**: PLANNER/REVIEWER/PATROL 是 API_KEY_LLM 类型，走 `@Async` 同步触发；EXECUTOR 可能是 CLI_CLIENT（MQ 通知→MCP pullTasks）或 API_KEY_LLM（@Async 触发）。两类执行路径在 AgentExecutor 接口层统一，但实际调用通道不同。

#### 最小协议草案：PLANNER 自动拆分 → SubTask 落库（PlannerPlanV1）

> 目标：让“用户输入 → PLANNER → N 条 sub_task”成为**可机械解析**、**可回放**、**可失败回退**的标准协议。协议风格借鉴 AgentTeams `projectflow/taskflow`：输出必须结构化，平台只做解析与落库，不做“猜你意思”。

**输入如何到达 PLANNER**

- 触发点：`TaskController.create()` 创建 Task 后，向所有 `PLANNER` 写入 inbox 消息 `task.created`（现有实现）。
- PLANNER（无论是人、API_KEY_LLM、或 IDE/CLI）收到该 inbox 后，执行“拆分”并产出 `PlannerPlanV1`。

**PLANNER Prompt 模板（建议）**

```
你是 HelloAI 的 PLANNER。你将用户需求拆分为可独立执行的子任务（SubTask）。

输入：
- taskId: {{taskId}}
- userInput: {{userInput}}
- taskContext: {{taskContextJsonOrText}}
- 可用角色与约束（roleChainSummary）：{{roleChainSummary}}
  （示例：[{"role":"EXECUTOR","specialization":"backend","agentId":10,"score":85},
           {"role":"EXECUTOR","specialization":"frontend","agentId":11,"score":72},
           {"role":"REVIEWER","agentId":20}]）

要求：
1) 产出 1-20 条 SubTask（上限可配置，默认 20）
2) 每条必须包含：title、description、deliverable、acceptance、priority
3) priority ∈ HIGH/MEDIUM/LOW（默认 MEDIUM）
4) assignedAgent 可选：若能明确某个 agent 更合适可填写其 agentId，否则留空
   - 留空 → 子任务状态 PENDING，由 EXECUTOR 自由认领（见 EXECUTOR Loop 协议）
   - 填写 → 子任务状态 ASSIGNED，定向通知该 EXECUTOR
5) dependsOn 可选：若子任务 B 依赖子任务 A 先完成，可在 B 中填写 A 的 title（P1 特性，MVP 可忽略）
6) 不要输出任何解释性文本，只输出 JSON

输出格式（必须严格遵守）：
返回一个 JSON 对象，符合 PlannerPlanV1（见下方 JSON Schema）。
注意：不要用 ```json 代码块包裹，直接输出裸 JSON。
```

**输出 JSON（必须严格遵守）**

`PlannerPlanV1`（字段与 `CreateSubTaskRequest` 对齐，天然可落库）：

```json
{
  "taskId": 123,
  "subTasks": [
    {
      "moduleId": null,
      "title": "实现 /api/sub-tasks/{id}/start 接口",
      "description": "补齐 start 的状态流转与幂等性处理。",
      "deliverable": "可通过 ApiPost 调用 start，状态变更为 IN_PROGRESS。",
      "acceptance": "重复 start 不报错；非 ASSIGNED 状态 start 被拒绝；有审计记录。",
      "priority": "HIGH",
      "assignedAgent": null,
      "dependsOn": []
    }
  ]
}
```

> `taskId` 只在外层声明一次，`subTasks[]` 中每条不再重复 `taskId`（从外层继承，避免 PLANNER 填错导致不一致）。
> `dependsOn` 为 `string[]`，填写依赖项的 `title` 精确匹配。P0/MVP 阶段留空数组即可，P1 启用 DAG 依赖调度。

**平台解析与落库（机械规则）**

- 预处理：去除可能的 markdown 代码块包裹（` ```json ... ``` ` 或 ` ``` ... ``` `），再 JSON.parse
- 解析：`JSON.parse(plannerOutput)` → 校验顶层 `taskId` 存在且与当前 Task 一致
- 校验：对每条子任务按 `CreateSubTaskRequest` 进行 DTO 校验（title 非空等；`taskId` 由平台统一注入，不从 PLANNER 输出中取）
- 落库：**全部校验通过后，在一个 `@Transactional` 中批量插入**；任一校验失败则整体回滚，不产生孤儿 SubTask
- `assignedAgent` 非空 → `status = ASSIGNED`，发 inbox 通知该 EXECUTOR
- `assignedAgent` 为空 → `status = PENDING`，由 EXECUTOR 通过 `pullTasks`/`available` 自由认领
- 审计：将 PLANNER 原始 JSON 写入 Task 级存储（`task.context.planRaw`，每条 SubTask 不冗余存储），同时在 `task_timeline` 写入审计快照

**失败回退（必须可观测）**

- JSON 解析失败 / 校验失败 / 超时 / 输出为空：
  - 将原始输出写入 `task_timeline`（或最小落库到 `task.remark/context`），标记 `planner_failed`
  - 给 PLANNER 写 inbox：`task.planner_failed`（附失败原因与期望输出格式）
  - 若连续失败达到阈值（如 3 次），将 Task 标记为需要人工介入（通知管理员/PLANNER）

### 3.3 架构哲学：Reconcile 式状态收敛

> 借鉴 AgentTeams Controller 的 Reconcile Loop 思想，但不引入 K8s。

**核心思路**：为关键资源引入"期望态"字段，定时任务从"补偿脚本"升级为"控制器"——持续把实际状态推进到期望状态。每步操作幂等，失败重试不产生副作用。

**与现有 Outbox 补偿的关系**：不冲突。Outbox 负责**事务性消息发布**的可靠性；Reconcile 负责**业务状态收敛**的可靠性。两者互补。

### 3.4 与 AgentTeams 的架构差异与定位

| 维度 | AgentTeams | HelloAI |
|------|-----------|---------|
| **定位** | 基础设施级（管 Agent 容器/网络/通信） | 业务级（管任务状态/质量/调度） |
| **控制面** | K8s CRD + Controller（Go） | DB 表 + Job 控制器（Java） |
| **权威状态** | etcd（Raft 强一致）→ MinIO 投影 JSON 文件 | PostgreSQL（唯一事实源），Redis/MinIO 为派生缓存/镜像 |
| **通信方式** | Matrix 房间 + @mention | MQ 事件（外部 CLI）+ @Async（内部 LLM 调用）+ HTTP Inbox |
| **Agent 运行时** | 容器化，Controller 管完整生命周期 | 不管理运行时，只做身份+调度 |
| **凭证管理** | TokenVault 引用，不存真值 | api_key 语义升级 + credential_vault（仅 LLM 场景） |
| **工作流存储** | MinIO 文件树（投影，无索引无查询能力） | DB 状态机 + DB task_timeline + MinIO 归档 |
| **评分系统** | 无，靠 LLM 自评 | scoreFactors 5 因子数据模型已就位 + Reward Ledger |

**一句话定位**：AgentTeams 是"Agent 协作的操作系统"，HelloAI 是"Agent 协作的业务中台"。两者互补而非竞争。

---

## 四、数据模型扩展（v2.1 修订：统一 BaseEntity 风格）

> **重要约定**: 所有新表严格遵循项目现有 BaseEntity 风格——字段名 `create_by/update_by/create_time/update_time/deleted/remark`，`deleted` 用 `SMALLINT DEFAULT 0`，`create_time/update_time` 用 `TIMESTAMPTZ`，并创建 `update_xxx_update_time` 触发器。

### 4.1 Agent 表扩展：accessType + capabilities + labels + 双心跳 + 状态拆分

**修改文件**：`helloai-core/src/main/java/com/helloai/core/entity/Agent.java`

```java
// === 阶段 0 新增字段 ===

// 接入类型
private AgentAccessType accessType;  // CLI_CLIENT / API_KEY_LLM / WEB_BROWSER

// 能力画像（新增独立列，不复用 modelConfig）
private Map<String, Object> capabilities;
// 示例: {"supportsPull":true, "supportsSSE":false, "supportsMCP":true,
//        "supportsArtifactUpload":true, "maxConcurrentTasks":3, "isSlow":false}

// 标签（新增独立列）
private Map<String, Object> labels;
// 示例: {"specialty":"frontend", "runtime":"claude", "region":"us-east"}

// 双心跳（新增列）
private OffsetDateTime lastSeenAt;   // 收到心跳/拉取/ack 即刷新（在线判定依据）
private OffsetDateTime lastActiveAt; // 任务执行时刷新（活跃度/选人策略偏好）

// v2.2 新增：离线追溯字段（P1 原则落地）
private String offlineReason;         // 最近一次离线原因（仅 OFFLINE 时写）：heartbeat_lost / ping_failed。SLEEPING 不写此字段
private OffsetDateTime offlineAt;     // 最近一次被判定离线的时间

// 注意：capabilities/labels 使用 JacksonTypeHandler 落库（与 modelConfig 一致），
// 实体字段类型为 Map<String, Object>，Mapper XML 中指定 typeHandler

// === 状态拆分（v2.2 明确边界）===
// AgentStatus（管理态）：ACTIVE / DISABLED
//   - 仅由人工操作改变（管理员启用/禁用 Agent）
//   - 鉴权只看这个：AuthService.validateAgentKey 只拦 DISABLED
//   - 不引入 OFFLINE——"离线"是计算态概念
// AgentOnlineStatus（计算态，系统定时计算，不提供给人工直接修改）：
//   - ONLINE / IDLE / OFFLINE / SLEEPING
//   - SLEEPING 仅由管理员手动设置（暂停接活但保留身份）
//   - 所有调度相关过滤看这个，不看 AgentStatus
//   - "ACTIVE 的 Agent 也可以是 OFFLINE"——含义是"管理员没禁用它，但它现在不在线"

// 设计决策（v2.2）：AgentStatus 不引入 OFFLINE
// 理由：
//   1. 现有 AgentService 查询大量用 ACTIVE 过滤，引入 OFFLINE 会导致
//      管理台列表、统计、drop-down 等全部受冲击
//   2. "离线"是瞬时计算态（5分钟不续约），不应该和"人工启用/禁用"混在
//      同一个字段
//   3. 鉴权只关心 DISABLED（被禁用的 Agent 不应该访问 API），不关心 OFFLINE
```

**注意**：`api_key` 字段保持不变，语义升级为 consumerToken（工牌）。不新增 `consumer_token` 列，避免双 token 迁移复杂度。

**DB 迁移**：`V2__add_agent_fields.sql`

```sql
-- 阶段 0：Agent 表扩展
ALTER TABLE agent ADD COLUMN access_type VARCHAR(32) NOT NULL DEFAULT 'CLI_CLIENT';
ALTER TABLE agent ADD CONSTRAINT chk_agent_access_type
    CHECK (access_type IN ('CLI_CLIENT', 'API_KEY_LLM', 'WEB_BROWSER'));

ALTER TABLE agent ADD COLUMN capabilities JSONB DEFAULT '{}';
ALTER TABLE agent ADD COLUMN labels JSONB DEFAULT '{}';
ALTER TABLE agent ADD COLUMN last_seen_at TIMESTAMPTZ;
ALTER TABLE agent ADD COLUMN last_active_at TIMESTAMPTZ;
ALTER TABLE agent ADD COLUMN offline_reason VARCHAR(64);
ALTER TABLE agent ADD COLUMN offline_at TIMESTAMPTZ;

-- 管理态约束不变（ACTIVE/DISABLED），不引入 OFFLINE
-- "离线"只用 online_status + offline_reason/offline_at 表达
-- （AgentStatus 保持纯人工管理态，鉴权只拦 DISABLED）

-- 计算态：新增 online_status 列（由系统计算——心跳即时写回 + 定时任务兜底，不由用户直接修改）
ALTER TABLE agent ADD COLUMN online_status VARCHAR(16) DEFAULT 'OFFLINE';
ALTER TABLE agent ADD CONSTRAINT chk_agent_online_status
    CHECK (online_status IN ('ONLINE', 'IDLE', 'OFFLINE', 'SLEEPING'));

COMMENT ON COLUMN agent.access_type IS 'Agent接入类型：CLI_CLIENT/API_KEY_LLM/WEB_BROWSER';
COMMENT ON COLUMN agent.capabilities IS '能力画像：supportsPull/supportsSSE/supportsMCP等，注册时可独立覆盖默认值';
COMMENT ON COLUMN agent.labels IS '标签：specialty/runtime/region等，用于调度标签过滤';
COMMENT ON COLUMN agent.last_seen_at IS '最近一次心跳/拉取/ack时间（在线判定依据）';
COMMENT ON COLUMN agent.last_active_at IS '最近一次任务执行时间（活跃度/选人策略偏好）';
COMMENT ON COLUMN agent.offline_reason IS '离线原因（仅 online_status=OFFLINE 时写入）：heartbeat_lost/ping_failed。SLEEPING 是手动暂停，不写此字段';
COMMENT ON COLUMN agent.offline_at IS '最近一次被判定离线的时间';
COMMENT ON COLUMN agent.online_status IS '计算态在线状态（ONLINE/IDLE/OFFLINE/SLEEPING），由系统计算——心跳即时写回 + 定时任务兜底';

CREATE INDEX idx_agent_access_type ON agent(access_type) WHERE deleted = 0;
CREATE INDEX idx_agent_last_seen ON agent(last_seen_at) WHERE deleted = 0;
CREATE INDEX idx_agent_online_status ON agent(online_status) WHERE deleted = 0;
```

### 4.2 新增 `process_template` 表（BaseEntity 风格）

**DB 迁移**：`V3__create_process_template.sql`

```sql
CREATE TABLE IF NOT EXISTS process_template (
    id              BIGINT PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    template_key    VARCHAR(64) UNIQUE NOT NULL,
    description     TEXT,
    is_builtin      SMALLINT DEFAULT 0,
    is_active       SMALLINT DEFAULT 1,
    role_chain      JSONB NOT NULL,
    config          JSONB,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);

CREATE INDEX idx_process_template_key ON process_template(template_key) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_process_template_update_time ON process_template;
CREATE TRIGGER update_process_template_update_time BEFORE UPDATE ON process_template
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE process_template IS '工作流模板表';
COMMENT ON COLUMN process_template.role_chain IS '角色链定义 JSONB，支持 linear/nested 两种类型';
COMMENT ON COLUMN process_template.config IS '模板配置：audit_snapshot_enabled, sla 等';
```

### 4.3 新增 `template_role_binding` 表（BaseEntity 风格）

**DB 迁移**：`V4__create_template_role_binding.sql`

```sql
CREATE TABLE IF NOT EXISTS template_role_binding (
    id                  BIGINT PRIMARY KEY,
    template_id         BIGINT NOT NULL REFERENCES process_template(id),
    role                VARCHAR(32) NOT NULL,
    agent_ids           JSONB NOT NULL,
    selection_strategy  VARCHAR(32) DEFAULT 'ROUND_ROBIN',
    label_filters       JSONB DEFAULT '{}',
    create_by           VARCHAR(64)  NOT NULL DEFAULT '',
    update_by           VARCHAR(64)  NOT NULL DEFAULT '',
    create_time         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0,
    remark              VARCHAR(255)
);

CREATE INDEX idx_trb_template_role ON template_role_binding(template_id, role) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_trb_update_time ON template_role_binding;
CREATE TRIGGER update_trb_update_time BEFORE UPDATE ON template_role_binding
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE template_role_binding IS '模板角色绑定表';
COMMENT ON COLUMN template_role_binding.label_filters IS '标签过滤（AND逻辑），如 {"specialty":"frontend"}';
```

### 4.4 新增 `agent_mcp_server` 表（BaseEntity 风格）

**DB 迁移**：`V5__create_agent_mcp_server.sql`

```sql
CREATE TABLE IF NOT EXISTS agent_mcp_server (
    id              BIGINT PRIMARY KEY,
    agent_id        BIGINT NOT NULL,
    tool_name       VARCHAR(64) NOT NULL,      -- 工具名：pullTasks/ack/heartbeat/uploadArtifact/getAgentStatus/claimSubTask
    is_enabled      SMALLINT DEFAULT 1,        -- 开关
    rate_limit      INTEGER DEFAULT 0,         -- 频率限制（次/分钟），0=不限
    param_constraints JSONB DEFAULT '{}',      -- 参数约束：{"max":50, "allowedContentTypes":["image/png"]}
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);

CREATE UNIQUE INDEX idx_ams_agent_tool ON agent_mcp_server(agent_id, tool_name) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_ams_update_time ON agent_mcp_server;
CREATE TRIGGER update_ams_update_time BEFORE UPDATE ON agent_mcp_server
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE agent_mcp_server IS '按 Agent 维度的 MCP 工具开关/策略/权限配置表';
COMMENT ON COLUMN agent_mcp_server.tool_name IS '工具名：pullTasks/ack/heartbeat/uploadArtifact/getAgentStatus/claimSubTask';
COMMENT ON COLUMN agent_mcp_server.is_enabled IS '是否启用该工具（开关），0=禁用';
COMMENT ON COLUMN agent_mcp_server.rate_limit IS '频率限制（次/分钟），0=不限';
COMMENT ON COLUMN agent_mcp_server.param_constraints IS '参数约束 JSONB，如 {"max":50}';
```

> HelloAI 自己是 MCP Server（暴露 6 个固定工具），外部 Qoder/Trae 是 MCP Client。本表控制"某个 Agent 被允许使用哪些工具、什么频率、什么参数约束"。MCP 工具集合先固定 6 个，运行时动态生成 @Tool 方法的方案留到后续版本评估复杂度。

### 4.5 新增 `credential_vault` 表（BaseEntity 风格，仅 API_KEY_LLM 场景）

**DB 迁移**：`V6__create_credential_vault.sql`

```sql
CREATE TABLE IF NOT EXISTS credential_vault (
    id              BIGINT PRIMARY KEY,
    agent_id        BIGINT,
    provider        VARCHAR(32) NOT NULL,
    credential_type VARCHAR(32) DEFAULT 'api_key',
    encrypted_value TEXT NOT NULL,
    tool_whitelist  JSONB DEFAULT '["*"]',
    is_active       SMALLINT DEFAULT 1,
    expires_at      TIMESTAMPTZ,
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);

CREATE INDEX idx_cred_vault_agent ON credential_vault(agent_id) WHERE deleted = 0;
DROP TRIGGER IF EXISTS update_cred_vault_update_time ON credential_vault;
CREATE TRIGGER update_cred_vault_update_time BEFORE UPDATE ON credential_vault
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE credential_vault IS '凭证保险库（AES加密存储真实API Key）';
COMMENT ON COLUMN credential_vault.encrypted_value IS '应用层AES加密后的凭证值';
```

> **v2.1 说明**: CLI_CLIENT 类型 Agent 不受此表影响，沿用现有 `api_key` 鉴权。credential_vault 仅在 ApiKeyExecutor 调用 LLM API 时解密注入。

### 4.6 新增 `task_timeline` 表（工作流审计权威记录）（v2.2 新增）

**DB 迁移**：`V8__create_task_timeline.sql`

```sql
CREATE TABLE IF NOT EXISTS task_timeline (
    id              BIGINT PRIMARY KEY,
    task_id         BIGINT NOT NULL,
    sub_task_id     BIGINT,
    event_type      VARCHAR(64) NOT NULL,  -- step_started/step_completed/agent_offline/circuit_open/reassign/timeout
    role            VARCHAR(32),           -- PLANNER/EXECUTOR/REVIEWER/PATROL
    agent_id        BIGINT,
    payload         JSONB,                 -- {"from_status":"IN_PROGRESS","to_status":"DONE","duration_ms":1234,...}
    create_by       VARCHAR(64)  NOT NULL DEFAULT '',
    update_by       VARCHAR(64)  NOT NULL DEFAULT '',
    create_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0,
    remark          VARCHAR(255)
);

CREATE INDEX idx_tl_task ON task_timeline(task_id, create_time) WHERE deleted = 0;
CREATE INDEX idx_tl_sub_task ON task_timeline(sub_task_id, create_time) WHERE deleted = 0;
CREATE INDEX idx_tl_event_type ON task_timeline(event_type, create_time) WHERE deleted = 0;

DROP TRIGGER IF EXISTS update_task_timeline_update_time ON task_timeline;
CREATE TRIGGER update_task_timeline_update_time BEFORE UPDATE ON task_timeline
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();

COMMENT ON TABLE task_timeline IS '工作流审计时间线表（DB 权威记录）';
COMMENT ON COLUMN task_timeline.event_type IS '事件类型：step_started/step_completed/agent_offline/circuit_open/reassign/timeout';
COMMENT ON COLUMN task_timeline.payload IS '事件载荷 JSONB：状态变更前后值、耗时、触发原因等';
```

> **P1 落地说明**：task_timeline 是工作流审计的 **DB 权威记录**，可直接 SQL 查询和分页。MinIO 的 `timeline.jsonl` 是归档镜像（从 task_timeline 导出），MinIO 故障不影响系统正确性和管理台时间线视图。
>
> **双写策略**：WorkflowAuditService 采用**同步写 DB → 异步写 MinIO**（`@Async` + 失败重试 3 次 + 死信到 `audit.snapshot.dlq`）。MinIO 写失败仅日志告警，不阻塞主流程，不丢 DB 记录。

### 4.7 新增实体类清单

**阶段 0 新增/修改**：
- `Agent.java` 加字段：accessType, capabilities, labels, lastSeenAt, lastActiveAt, offlineReason, offlineAt, onlineStatus（共 8 个，与 DDL 列顺序一致）
- `AgentStatus.java` 保持 ACTIVE/DISABLED 两态（v2.2 决策不引入 OFFLINE）
- `AgentOnlineStatus.java` 枚举（新建）：ONLINE, IDLE, OFFLINE, SLEEPING
- `AgentAccessType.java` 枚举（新建）：CLI_CLIENT, API_KEY_LLM, WEB_BROWSER（含默认 capabilities 映射方法）
- `AgentCapability.java` 工具类（新建）：解析 capabilities JSONB + 默认值合并 + 能力匹配

**阶段 1 新增**：
- `ProcessTemplate.java` + Mapper + Service
- `TemplateRoleBinding.java` + Mapper + Service

**阶段 2B 新增**：
- `CredentialVault.java` + Mapper + Service

**阶段 3 新增**：
- `AgentMcpServer.java` + Mapper + Service

---

## 五、阶段 0：Agent 接入类型枚举 + 能力画像 + 状态扩展

**工作量**：1.5 天（约 12 小时，v2.1 增加状态枚举扩展 + last_seen_at 字段）  
**前置**：无

### 5.1 任务列表

| # | 任务 | 技术点 | 工时 |
|---|------|--------|------|
| 0.1 | 创建 `AgentAccessType` 枚举（含默认 capabilities 映射方法 + 注释说明可覆盖） | Java enum | 30 分钟 |
| 0.2 | 创建 `AgentOnlineStatus` 枚举（ONLINE/IDLE/OFFLINE/SLEEPING） | Java enum | 15 分钟 |
| 0.3 | ~（v2.2 撤销）AgentStatus 保持 ACTIVE/DISABLED，不引入 OFFLINE~ | 见 4.1 设计决策 | 0 |
| 0.4 | `Agent` 实体加 accessType + capabilities + labels + lastSeenAt + lastActiveAt + onlineStatus + offlineReason + offlineAt（共 8 个字段） | 字段 + getter/setter + JacksonTypeHandler 注解 | 1 小时 |
| 0.5 | 创建 V2 Flyway 迁移脚本（ALTER TABLE + 约束 + 索引 + 注释） | PostgreSQL DDL | 45 分钟 |
| 0.6 | 创建 `AgentCapability` 工具类（解析 + 默认值合并 + 能力匹配方法） | JacksonUtil + Map 合并 | 45 分钟 |
| 0.7 | 扩展 `AgentController.registerWithToken()` 接受 accessType + labels + capabilities（可选覆盖） | DTO + 校验 + 默认值填充 | 2 小时 |
| 0.8 | 扩展 Agent DTO（accessType + labels + capabilities 摘要 + onlineStatus） | DTO + 文档注释 | 45 分钟 |
| 0.9 | 前端 `AgentEditDialog.vue` 加 accessType 下拉 + labels 编辑 + capabilities 展示 | Vue3 + Element Plus | 1 小时 |
| 0.10 | 单元测试：register 三种 accessType + capabilities 默认值 + 覆盖逻辑 | JUnit 5 + Mockito | 1 小时 |

> 以上任务合计约 8.5h，剩余 3.5h 留给联调 + buffer。

### 5.2 验收标准

- [ ] Agent 表新增 `access_type`、`capabilities`、`labels`、`last_seen_at`、`last_active_at`、`offline_reason`、`offline_at`、`online_status` 列（共 8 列）
- [ ] `AgentStatus` 枚举保持 ACTIVE/DISABLED 两态（v2.2 决策不引入 OFFLINE——离线判定走 AgentOnlineStatus）
- [ ] `AgentOnlineStatus` 枚举含 ONLINE/IDLE/OFFLINE/SLEEPING 四态
- [ ] `capabilities` 按 accessType 自动填充默认值，但允许注册时覆盖
- [ ] 已有数据默认 `CLI_CLIENT`，capabilities 按 accessType 自动填充，online_status 初始为 OFFLINE
- [ ] 非法 accessType 返回 400
- [ ] 前端注册页面有 accessType 下拉 + labels 编辑入口
- [ ] **v2.2 新增** 向后兼容：不传 accessType/capabilities/labels 的老客户端注册请求仍然成功，自动获得默认 capabilities
- [ ] 编译无错，迁移脚本可正常执行

---

## 六、阶段 1：可配置工作流模板

**工作量**：4.5 天（约 35 小时，1.5 的 WorkflowAuditService 从 v1.0 的 3h 调增至 6h——MinIO 客户端封装 + 异步写 + 重试 + DB task_timeline 双写）  
**前置**：阶段 0 完成

### 6.1 任务列表

#### 6.1.1 后端 API（约 19 小时）

| # | 任务 | 端点 | 工时 |
|---|------|------|------|
| 1.1 | `ProcessTemplateService` CRUD（含 role_chain JSONB 校验 + **config.priority/config.sla_minutes 字段校验**：缺省值 LOW/480，类型 STRING/INTEGER，范围 REALTIME|HIGH|NORMAL|LOW|BACKGROUND 和 1-10080） | - | 4 小时 |
| 1.2 | `ProcessTemplateController` | `GET/POST/PUT/DELETE /api/process-templates` | 3 小时 |
| 1.3 | `TemplateRoleBindingService`（含 label_filters 标签过滤 + Agent 在线状态过滤） | `GET /api/process-templates/{id}/bindings` | 4 小时 |
| 1.4 | 扩展 `TaskController.create()` 接受 templateId | `POST /api/tasks` | 2 小时 |
| 1.5 | `WorkflowAuditService`：写 DB task_timeline 表（权威记录）+ MinIO timeline.jsonl（归档镜像） | MinIO client + 异步写 + 文件命名约定 | 6 小时 |

#### 6.1.2 调度逻辑改造（约 8 小时）

| # | 任务 | 技术点 | 工时 |
|---|------|--------|------|
| 1.6 | `SubTaskService.assignNext()` 按模板 role_chain 推进（含嵌套子链递归解析） | 递归/迭代解析 role_chain | 4 小时 |
| 1.7 | `AgentSelector`：roundRobin / bestScore + label_filters 标签交集匹配 + online_status 过滤 | 策略模式 | 3 小时 |
| 1.8 | `AgentSelector.pickAlternative()`：同角色更换 Agent（跳过失败/离线/熔断的 Agent） | round-robin 重试 | 1 小时 |

#### 6.1.3 前端 UI（约 5 小时）

| # | 任务 | 文件 | 工时 |
|---|------|------|------|
| 1.9 | 模板选择器 | `views/task/components/TemplateSelector.vue` | 2 小时 |
| 1.10 | 流程模板管理页 | `views/template/TemplateList.vue` + `TemplateEditDialog.vue` | 2 小时 |
| 1.11 | 角色绑定编辑器（含标签过滤配置 UI） | `views/template/components/RoleBindingEditor.vue` | 1 小时 |

#### 6.1.4 种子数据（约 3 小时）

| # | 任务 | 内容 | 工时 |
|---|------|------|------|
| 1.12 | 内置 `minimal` 模板（2 角色线性） | PLANNER+EXECUTOR | 0.5 小时 |
| 1.13 | 内置 `full` 模板（4 角色线性） | 全链路 | 0.5 小时 |
| 1.14 | 内置 `team` 模板（嵌套 Team） | PLANNER→LEADER→[EXECUTOR×2]→REVIEWER | 0.5 小时 |
| 1.15 | 迁移脚本 `V7__seed_process_templates.sql` | 内置模板 + 默认绑定 | 1.5 小时 |

> 以上任务合计约 35h ≈ 4.5d。1.5（WorkflowAuditService）从 v1.0 的 3h 调增至 6h——MinIO 客户端封装 + 异步写 + 重试 + DB task_timeline 双写，原估算偏少。

### 6.2 验收标准

- [ ] 可创建/编辑/删除自定义工作流模板（线性 + 嵌套 Team）
- [ ] 模板可绑定角色与 Agent，支持标签过滤 + online_status 过滤 + 多种选择策略
- [ ] 创建任务时选择模板，调度按 role_chain 推进
- [ ] 每个 step 推进时写 DB task_timeline（权威）+ MinIO timeline.jsonl（归档镜像）
- [ ] 内置 minimal、full、team 三个模板
- [ ] 最小配置 2 角色可端到端跑通

---

## 七、阶段 2A：API Key 类 Agent Executor（基础链路）

**工作量**：2 天（约 18 小时）  
**前置**：阶段 0 完成  
**说明**：v2.1 将原阶段 2 拆为 2A（基础链路，P1 优先）和 2B（多 Provider + 重试 + 凭证保险库，在阶段 4 之后做）。2A 只跑通一条 LLM 执行链路（DeepSeek EXECUTOR），验证 AgentExecutor 接口可行。

### 7.1 任务列表

| # | 任务 | 技术点 | 工时 |
|---|------|--------|------|
| 2A.1 | 引入 Spring AI BOM + DeepSeek starter | Maven 依赖 | 1.5 小时 |
| 2A.2 | 复用 springai 项目的 DeepSeek ChatClient Bean（跨项目调研→提取公共配置→集成调试→连通性测试） | 跨项目依赖打通 + 配置提取 | 4 小时 |
| 2A.3 | 创建 `AgentExecutor` 接口 | `execute(SubTask, prompt)` + `supports(Agent)` + `checkCapability(Agent, required)` | 1 小时 |
| 2A.4 | 实现 `ApiKeyExecutor`（单 Provider：DeepSeek ChatClient） | ChatClient 调用 + 结果解析 | 3 小时 |
| 2A.5 | `SubTaskService.assignNext()` 中 @Async 触发 ApiKeyExecutor（不通过 MQ） | `@Async` + `TaskExecutor` 线程池配置 | 3 小时 |
| 2A.6 | Prompt 拼装：SKILL.md + task context + 附件链接 | `PromptBuilder` 工具类 | 2 小时 |
| 2A.7 | 执行结果回写：SubTask 状态推进 + 更新 `agent.last_active_at`（活跃度刷新） | SubTaskService.submit() + active() | 2 小时 |
| 2A.8 | 失败记录到异常日志 + 状态回退到 ASSIGNED（暂不做自动重试，留给阶段 4） | 异常处理 + 状态回退 | 1.5 小时 |

> 以上任务合计约 18h ≈ 2d（含 2h buffer，2A.2 跨项目集成预留弹性）。

> **v2.1 关键修正**: 阶段 2A 走 `@Async` 触发，**不走 MQ Consumer**。MQ 的 `sub_task.assigned` 队列是给外部 CLI 客户端用的（阶段 3）。API Key 类 Agent 是平台服务端直调 LLM API，属于"内网同步 RPC"，不应和"外网异步通知"混在同一通道。

### 7.2 关键接口

```java
public interface AgentExecutor {
    AgentExecutionResult execute(SubTask subTask, String systemPrompt, String userPrompt);
    boolean supports(Agent agent);
    boolean checkCapability(Agent agent, Map<String, Object> required);
}
```

> v2.1 简化：去掉 `validateCredentials()` 方法。2A 阶段先用 agent.modelConfig 中的 apiKey（或 application.yml 的默认 Key），凭证保险库留到 2B。

### 7.3 验收标准

- [ ] DeepSeek ChatClient 在 helloai 项目中可成功调用
- [ ] API_KEY_LLM EXECUTOR 分配任务后 @Async 触发执行
- [ ] 执行前校验 capabilities，能力不足则选择替代 Agent
- [ ] 执行结果自动提交，子任务状态推进到 REVIEW
- [ ] Prompt 正确拼接（SKILL.md + task context + 附件链接）
- [ ] 失败任务状态回退到 ASSIGNED + 记录异常日志（自动重试留给阶段 4）
- [ ] 任务执行时更新 `agent.last_active_at`
- [ ] 单 LLM 调用超时 60 秒可配置

---

## 八、阶段 2B：多 Provider + 重试 + 凭证保险库

**工作量**：2 天（约 16 小时）  
**前置**：阶段 4 完成（依赖 Resilience4j 熔断 + 任务转发机制）  
**说明**：在基础链路跑通 + 可靠性基建就位后，扩展多 LLM Provider 和安全凭证管理。

### 8.1 任务列表

| # | 任务 | 技术点 | 工时 |
|---|------|--------|------|
| 2B.1 | 引入 Claude / OpenAI starter（按需） | Maven 依赖 | 1 小时 |
| 2B.2 | 实现多 Provider ChatClient 配置（DeepSeek/Claude/OpenAI） | 多 Bean 配置 | 3 小时 |
| 2B.3 | `ApiKeyExecutor` 内部根据 `agent.modelType` 选择 ChatClient | defaultOptions 切换 | 3 小时 |
| 2B.4 | 创建 `CredentialVaultService`（AES 加密/解密 + vault CRUD） | AES + vault 表操作 | 3 小时 |
| 2B.5 | `ApiKeyExecutor` 集成 vault lookup（从 vault 解密真实 Key 注入调用） | Executor + vault 集成 | 3 小时 |
| 2B.6 | 集成 Resilience4j 熔断（`@CircuitBreaker` 按 Agent 粒度，失败率 > 30% 熔断 60s） | 注解 + 配置 | 2 小时 |
| 2B.7 | 集成 `AgentSelector.pickAlternative()` 失败转发 | 同角色选人重试 | 1.5 小时 |
| 2B.8 | **v2.3 新增** api_key 迁移：核查历史 agent.api_key 是否存放真实 LLM Key；若是则加密搬迁到 credential_vault 并重置 api_key 为新 consumerToken；迁移脚本需幂等（通过 vault 已存在判断）且脱敏日志 | 数据迁移 + Flyway | 2 小时 |

> v2.1 去掉了"AgentRouter 按 modelType 路由到不同 Executor"的设计——Spring AI ChatClient 可通过 defaultOptions 切换模型，一个 ApiKeyExecutor 足够，省一层不必要的抽象。

### 8.2 验收标准

- [ ] 支持 DeepSeek / Claude / OpenAI 三家 Provider
- [ ] 真实 API Key 加密存在 credential_vault，Agent 的 api_key 仅做身份识别
- [ ] 单 Agent 失败率 > 30% 触发熔断，任务自动转发给同角色其他 Agent
- [ ] 熔断恢复后 Agent 自动重新加入可用池

---

## 九、阶段 3：MCP Server + CLI Agent 桥接

**工作量**：6.5 天（约 52 小时，v1.0 为 5d，v2.1 为 6.5d）  
**前置**：阶段 0 完成

### 9.1 任务列表

| # | 任务 | 技术点 | 工时 |
|---|------|--------|------|
| 3.1 | 加 `spring-ai-starter-mcp-server-webmvc` 依赖 | Spring AI MCP BOM | 1 小时 |
| 3.2 | 最小 EchoMcpTool 验证连通 | `@Tool` + `@ToolParam` | 2 小时 |
| 3.3 | `MqMcpServer` 实现 6 个固定工具（每工具附 mini SKILL.md 注解，含 Gotchas） | pullTasks/ack/heartbeat/uploadArtifact/getAgentStatus/claimSubTask | 1.5 天 |
| 3.4 | `AgentMcpServerService`：读取 `agent_mcp_server` 表做工具的开关+策略配置+权限 | 表驱动配置，工具集合固定 | 1 天 |
| 3.5 | Agent MCP 鉴权（Bearer api_key，复用现有 AuthService 校验逻辑） | SSE 连接时拦截校验 | 4 小时 |
| 3.6 | heartbeat 工具：收到心跳 → 刷新 `agent.last_seen_at`（调用 HeartbeatService.seen） + Redis TTL 续约 | HeartbeatService.seen() | 3 小时 |
| 3.7 | `PromptTemplateService` 增加 SKILL.md marker 机制（`<!-- helloai-builtin-start -->` 包裹 builtin 段） | marker 注入 + 用户内容提取保护 | 4 小时 |
| 3.8 | Qoder MCP 配置文档（客户端侧 SKILL.md 或 onboarding 文档） | 配置指南 | 4 小时 |
| 3.9 | Trae MCP 配置文档 | 同上 | 4 小时 |
| 3.10 | E2E 测试：Qoder 接入 MCP → pullTasks 收到任务 → ack → uploadArtifact → 全链路验证 | 全链路 | 4 小时 |

> 以上任务合计约 47h ≈ 6d，剩余 0.5d 留给联调 + buffer。

> **v2.1 关键修正**:
> - 类名从 `RabbitMqMcpServer` 改为 `MqMcpServer`，避免与 RabbitMQ 强绑定误导
> - heartbeat 工具只负责刷新 `last_seen_at` + Redis TTL，不负责更新 `last_active_at`（那是任务执行时才更新的）
> - 3.6 调用 `HeartbeatService.seen()`（阶段 0 已建骨架），核心心跳逻辑在阶段 0 和阶段 4 完成
> - MCP 工具集合固定 6 个，`agent_mcp_server` 表做开关+策略，不做运行时动态生成 @Tool

#### 最小协议草案：EXECUTOR Loop（MCP + REST）与并发协调

> 目标：定义“外部 EXECUTOR 客户端（Qoder/Trae/CLI）”的最小循环结构与工具契约。借鉴 AgentTeams `taskflow ack_task/submit_task`：先 claim/ack，再执行，再提交，所有动作幂等可重试。

**执行循环（推荐）**

```
loop:
  1. heartbeat(agentId)                        // 维持在线（频率可配置，默认 30s）
  2. messages = pullTasks(agentId, role, max)  // 拉取待处理 inbox 消息（只读，不标记已读）
  3. for each msg in messages:
       if msg 指向可竞争任务:
         result = claimSubTask(agentId, subTaskId)   // 并发互斥，DB 原子条件更新
         if not result.claimed: continue             // 被别人抢走，跳过
         REST POST /api/sub-tasks/{id}/start         // 标记 IN_PROGRESS
       try:
         执行任务（本地工具/LLM/IDE）
         如有产物: uploadArtifact(agentId, subTaskId, ...) → artifactUrl
         REST POST /api/sub-tasks/{id}/submit         // 提交进入 REVIEW
       catch BlockedException:
         REST POST /api/sub-tasks/{id}/block          // 遇到无法自行解决的阻塞
         （inbox 自动通知 PLANNER 排障）
       finally:
         ack(agentId, messageId)                      // 标记 inbox 已处理（幂等）
```

> 轮询频率：默认 30s，CLI_CLIENT 可通过 `agent_mcp_server.config.pullIntervalSec` 覆盖（如 60s）。
> 与 AgentTeams 的差异：AgentTeams 是事件驱动（Matrix @mention 推送唤醒），HelloAI 是**轮询驱动**（pullTasks 拉 inbox），更适合无长连接的 HTTP CLI 客户端。

**工具输入/输出（最小字段集）**

- `pullTasks` 请求：

```json
{ "agentId": 1, "role": "EXECUTOR", "max": 20 }
```

`pullTasks` 查询逻辑：
```sql
SELECT * FROM agent_inbox
WHERE agent_id = ? AND is_read = 0 AND is_archived = 0
ORDER BY CASE priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 ELSE 2 END, create_time
LIMIT ?
```
**`pullTasks` 不标记已读**——消息在 `ack` 调用前保持 `is_read=0`，若客户端 pull 后崩溃，消息不会丢失。

`pullTasks` 响应（示例）：

```json
{
  "messages": [
    {
      "messageId": "inbox-10001",
      "type": "sub_task.assigned",
      "subTaskId": 20001,
      "taskId": 30001,
      "title": "实现 start 接口",
      "priority": "HIGH",
      "deadline": "2026-07-08T12:00:00+08:00"
    }
  ]
}
```

- `claimSubTask` 请求：

```json
{ "agentId": 1, "subTaskId": 20001 }
```

`claimSubTask` 响应：

```json
{ "ok": true, "claimed": true, "assignedAgent": 1, "version": 3 }
```

并发语义（必须落实为 DB 原子条件更新）：

```sql
UPDATE sub_task
SET assigned_agent = ?, status = 'ASSIGNED', version = version + 1
WHERE id = ? AND status = 'PENDING' AND assigned_agent IS NULL
```
- affected rows = 1 → claim 成功，返回 `claimed=true`
- affected rows = 0 → 进入幂等判定：查询 sub_task 当前 `status/assigned_agent`
  - 若 `status='ASSIGNED'` 且 `assigned_agent=agentId` → 返回 `claimed=true`（幂等成功，不再重复 update）
  - 否则 → 返回 `claimed=false`（已被他人抢走或状态已变，客户端应跳过）

**幂等与一致性要求**

- `ack`：设置 `agent_inbox.is_read=1, read_at=NOW()`。重复 ack 返回幂等成功
- `claimSubTask`：重复 claim 同一 sub_task（已归属于自己）返回 `claimed=true`，归属于他人返回 `claimed=false`
- `start/submit`：重复调用应返回幂等成功或明确的“已完成/已提交”错误码，避免客户端因重试造成异常状态
- **`reportBlocked`（异常上报）**：EXECUTOR 执行中遇到外部依赖不可用、环境缺失等无法自行解决的阻塞时，调用 REST `POST /api/sub-tasks/{id}/block`，inbox 会自动通知所有 PLANNER 排障（现有 `SubTaskService.sendInboxNotification()` BLOCKED 分支已实现此逻辑）

> 注：阶段 3 固定工具集不包含 `start/submit`，因此 MVP 可用 REST 调用补齐执行闭环；后续若希望全量走 MCP，可新增 `startSubTask/submitSubTask` 工具，但需评估工具集扩张对安全与权限的影响。

**可选扩展（P1）：补齐 startSubTask/submitSubTask，做到“纯 MCP 闭环”**

> 目标：在不破坏“固定工具集 P0 可交付”的前提下，为后续版本提供可选扩展，使外部 EXECUTOR 无需混用 REST。
>
> 推荐策略：保持阶段 3 的固定 6 工具不变；在阶段 4/5 或 v2.5 追加“扩展工具集”，并通过 `agent_mcp_server` 表默认关闭，按 Agent 白名单开启。

**工具：startSubTask**

- 语义：EXECUTOR 确认已 claim 后，启动执行（等价 REST `POST /api/sub-tasks/{id}/start`）
- 权限：仅 `capabilities.supportsMCP=true` 的 Agent；且 `agent_mcp_server(agent_id, tool_name='startSubTask').is_enabled=1`
- 并发规则：只能对“归属于自己”的 sub_task start（assigned_agent=agentId）

请求：

```json
{ "agentId": 1, "subTaskId": 20001 }
```

响应：

```json
{ "ok": true, "started": true, "subTaskId": 20001, "status": "IN_PROGRESS", "version": 4 }
```

幂等：

- 若已是 `IN_PROGRESS` 且 assigned_agent=agentId：返回 `started=true` 幂等成功
- 若状态不允许 start：返回 `{ok:false, error:"invalid_state"}`（不应静默成功）

**工具：submitSubTask**

- 语义：提交执行结果并进入 REVIEW（等价 REST `POST /api/sub-tasks/{id}/submit`）
- 输入：建议只传“摘要 + artifact 引用”，避免把大文本/大文件塞进 tool 参数；大文件走 `uploadArtifact`
- 权限：同上（capabilities + agent_mcp_server 开关）

请求（最小字段集）：

```json
{
  "agentId": 1,
  "subTaskId": 20001,
  "summary": "实现 startSubTask/submitSubTask MCP 工具并完成联调",
  "artifactUrls": [
    "minio://tasks/30001/subtasks/20001/result.json",
    "minio://tasks/30001/subtasks/20001/log.txt"
  ]
}
```

响应：

```json
{ "ok": true, "submitted": true, "subTaskId": 20001, "status": "REVIEW", "version": 5 }
```

幂等：

- 若已 `REVIEW` 且 assigned_agent=agentId：返回 `submitted=true` 幂等成功
- 若已 `DONE`：返回 `{ok:false, error:"already_done"}`（避免重复提交导致状态回退）

**安全与滥用防护（必须写死）**

- **参数上限**：summary 最大长度（如 2000 字），artifactUrls 最大数量（如 20）
- **速率限制**：按 `agent_mcp_server.rate_limit` 限制（默认 0=不限；建议 start/submit 默认为 60/min）
- **敏感信息**：禁止在 summary 中输出明文 api_key / vault 解密值（日志同样禁止）

### 9.2 关键代码

#### MCP Server 工具（MqMcpServer）

```java
@Component
public class MqMcpServer {

    @Tool(description = """
        【何时使用】Agent 需要查询分配给自己的待处理任务时调用。
        【调用频率】建议每 30 秒轮询一次，不要超过每 10 秒一次。
        【Gotchas】
        - 拉取后不会自动 ack，需要显式调用 ack 工具确认
        - max 参数上限 50，超出将截断
        - 返回空列表表示当前无待处理任务，不是错误
        【相关工具】ack、heartbeat
        """)
    public List<TaskMessage> pullTasks(Long agentId, String role, Integer max) { ... }

    @Tool(description = """
        【何时使用】Agent 确认消息已处理完毕。
        【Gotchas】每条消息只能 ack 一次，重复 ack 返回幂等成功（不会报错）。
        """)
    public void ack(Long agentId, String messageId) { ... }

    @Tool(description = """
        【何时使用】Agent 上报心跳，维持在线状态。每 30 秒调用一次。
        【效果】刷新 last_seen_at + Redis TTL 续约，不影响 last_active_at。
        【Gotchas】超过 5 分钟未调用将被判定 OFFLINE。
        """)
    public HeartbeatResult heartbeat(Long agentId) { ... }
}
```

#### SKILL.md Marker 机制

```java
// PromptTemplateService 中增加
private static final String BUILTIN_START = "<!-- helloai-builtin-start -->";
private static final String BUILTIN_END = "<!-- helloai-builtin-end -->";

public String renderSkillWithMarkers(String builtinTemplate, String existingContent) {
    String builtinSection = BUILTIN_START + "\n" + builtinTemplate + "\n" + BUILTIN_END;
    if (existingContent == null || existingContent.isEmpty()) return builtinSection;
    String userContent = extractUserContent(existingContent);
    return builtinSection + "\n\n" + userContent;
}
```

### 9.3 验收标准

- [ ] Spring AI MCP Server 启动成功，监听 `/mcp/sse`
- [ ] Qoder 可调用 heartbeat（刷新 last_seen_at）、pullTasks、ack、uploadArtifact
- [ ] SKILL.md builtin 段有 marker，模板更新不覆盖用户自定义内容
- [ ] `agent_mcp_server` 表控制工具开关（is_active=0 的工具不暴露）
- [ ] 完整 E2E：建任务→PLANNER 拆→EXECUTOR(Qoder)收到任务→执行→提交→REVIEWER 评分
- [ ] MCP 鉴权正常，非法 api_key 返回 401

---

## 十、阶段 4：在线状态与熔断降级

**工作量**：4.5 天（约 36 小时）  
**前置**：阶段 3 完成（heartbeat 工具可调用，last_seen_at 已被刷新）

### 10.1 任务列表

| # | 任务 | 技术点 | 工时 |
|---|------|--------|------|
| 4.1 | `HeartbeatService` 完整实现：`seen(agentId)` 刷新 last_seen_at + Redis TTL；`active(agentId)` 刷新 last_active_at；`checkOnlineStatus(agentId)` 三态判定 | DB update + Redis setIfAbsent + TTL | 3 小时 |
| 4.2 | **重构** `AgentHealthCheckTask`：Reconcile 式健康检查（扫描 last_seen_at 超时 → 尝试 ping → 仍不可达 → online_status=OFFLINE + offline_reason/offline_at 写入 + 重新分配任务 + 写 audit 记录）。注意：**不修改 AgentStatus**。**并发保护（v2.3）**：标记 OFFLINE 必须用 `UPDATE ... WHERE last_seen_at < cutoff AND online_status <> 'SLEEPING'`（乐观条件更新），避免 heartbeat seen() 刷新后仍被覆盖 | Reconcile 三步骤 + ping 探测 + CAS 更新 | 4 小时 |
| 4.3 | SLEEPING 状态 API：管理员手动暂停/恢复 Agent（`PUT /api/agents/{id}/sleep` + `PUT /api/agents/{id}/wake`） | online_status 切换 + 审计日志 | 2 小时 |
| 4.4 | 引入 Resilience4j 依赖 | `resilience4j-spring-boot3` | 1 小时 |
| 4.5 | `ResilientDispatcher` 包裹任务下发（`@CircuitBreaker` 按 Agent 粒度，failureRateThreshold=30, waitDurationInOpenState=60s） | 注解 + yml 配置 | 4 小时 |
| 4.6 | `AgentSelector.pickAlternative()` 同角色选人（跳过已失败/离线/熔断的 Agent，按 label_filters + round-robin 重试） | 多条件过滤 + 重试 | 4 小时 |
| 4.7 | 超时事件 + 熔断事件写入 DB task_timeline 表 + MinIO timeline.jsonl | WorkflowAuditService 集成 | 3 小时 |
| 4.8 | 熔断状态写日志 + 报警（Micrometer + 钉钉/飞书 Webhook） | Micrometer + RestTemplate | 3 小时 |
| 4.9 | **v2.2 新增** 5 因子加权计算实现（见下方因子定义和计算时机） | scoreFactors JSONB 解析 + 加权公式 + 幂等记账 | 4 小时 |
| 4.10 | 单元测试 + 集成测试 | JUnit 5 + Testcontainers | 6 小时 |

> 以上任务合计约 40h ≈ 5d，剩余 ~8h 留给联调 + buffer。

#### 10.1.1 5 因子评分定义（v2.2 新增）

> `SubTask.scoreFactors` JSONB 在 V1 建表时已定义，以下为加权计算公式。

| 因子 | 权重 | 说明 | 计算方式 |
|------|------|------|----------|
| **timeliness**（时效性） | 25% | deadline 前完成 | 提前→满分；超时按比例扣分；无 deadline 默认 48h |
| **quality**（质量） | 30% | Reviewer 评分平均 | AVG(review_record.score) / 5 × 100 |
| **collaboration**（协作度） | 25% | 返工少则高 | rework=0→100；1→70；2→40；≥3→10 |
| **stability**（稳定性） | 15% | 阻塞/超时少则高 | timeout=0→100；1→60；≥2→20 |
| **efficiency**（效率） | 5% | 实际耗时 vs 预估 | 基准 100；快于预估 bonus(≤120)；慢 2 倍则减分 |

**公式**：`compositeScore = timeliness×0.25 + quality×0.30 + collaboration×0.25 + stability×0.15 + efficiency×0.05`

**等级映射**：S(90-100,+5) / A(80-89,+3) / B(60-79,0) / C(40-59,-3) / D(0-39,-5)

**计算时机（v2.3 新增）**：
- **主路径**：REVIEW→DONE 状态变更时**同步计算** compositeScore 并写入 SubTask + RewardLog
- **兜底**：定时任务每 5 分钟扫描"应算未算（status=DONE 但 compositeScore=null）"的 sub_task 重算
- **幂等保护**：RewardLog 以 `(sub_task_id, reason='score_calculated')` 为幂等键（reward_log 表现有 `reason` 列，不复用 `event_type`）。**需建 DB 唯一约束** `CREATE UNIQUE INDEX idx_reward_score_once ON reward_log(sub_task_id) WHERE reason='score_calculated' AND deleted=0`。插入时 `reason` 固定写 `'score_calculated'`。靠应用层 if 判断在并发场景下可能重复记账

> **v2.1 关键修正**:
> - 4.3 只做"管理员手动 SLEEPING/Wake"，不做"超时自动 SLEEPING"。系统判定离线→OFFLINE，用户主动暂停→SLEEPING，两者不混
> - HeartbeatService 拆为 `seen()`（心跳刷新）和 `active()`（业务活跃刷新），调用点不同：seen 由 heartbeat 工具触发，active 由任务执行触发

#### 最小协议草案：REVIEWER 自动评分（ReviewerAutoReviewV1）

> 目标：让 REVIEWER 的输出可被平台**自动解析**为 `CreateReviewRequest` 并调用 `ReviewService.createReview()`，实现“提交 → 自动评审 → 自动记账”的闭环。借鉴 AgentTeams：工具/结果必须有固定结构，平台只做校验与状态推进。

**触发时机与方式**

- 触发点：`SubTaskService.submit()` 将子任务置为 `REVIEW` 后（现有链路，见 `SubTaskService.java:77-79`）。
- 触发方式：通过 `@Async` 异步触发 REVIEWER Agent（API_KEY_LLM 类型）执行，具体调用链见 §3.2 时序图。
- REVIEWER Agent 选择：从 `agent` 表查询第一个 `role='REVIEWER' AND status='ACTIVE'` 的 Agent；若无可用 REVIEWER，写入 `task_timeline` 并 inbox 通知管理员。

**REVIEWER Prompt 模板（含输入变量与输出约束）**

```
你是 HelloAI 的 REVIEWER。请审查以下子任务的执行成果并给出评分。

输入：
- subTaskId: {{subTaskId}}
- 任务标题: {{title}}
- 期望交付物: {{deliverable}}
- 验收标准: {{acceptance}}
- 执行者提交摘要: {{submitSummary}}
- 产物链接: {{artifactUrls}}
- 历史审查记录（如有返工）: {{previousReviews}}

审查原则：
1) 严格对照验收标准逐项检查
2) 评分必须客观，基于交付物与验收标准的匹配程度
3) 驳回时 issues 必须具体、可执行（“XX 功能未实现”而非“质量不行”）

评分标准：
| 分数 | 含义     | 判定       |
| ---- | -------- | ---------- |
| 5    | 超出预期 | APPROVED   |
| 4    | 完全达标 | APPROVED   |
| 3    | 基本达标 | APPROVED   |
| 2    | 部分不足 | REJECTED   |
| 1    | 严重不足 | REJECTED   |

## 输出格式（必须严格遵守）
你的审查结论必须只输出一个 JSON，不要输出任何其他内容（包括 Markdown、解释、代码块）。

输出 JSON 必须符合以下结构：
```json
{
  "subTaskId": "<Long>",
  "result": "APPROVED | REJECTED",
  "score": "<1-5>",
  "issues": "<REJECTED 时必填，描述问题与修改建议；APPROVED 时可为空>",
  "comment": "<简短评价>"
}
```

> 注：此 Prompt 模板存入 `prompt_template` 表（`role='REVIEWER', category='ROLE_TEMPLATE'`），运行时平台注入 `{{变量}}` 后发送给 LLM。现有 DB seed（`V1__init_all.sql:923-967`）已有 REVIEWER 基础模板，需在其末尾追加上述“输出格式”约束段。

**平台解析与落库（机械规则）**

- 预处理：去除可能的 markdown 代码块包裹，再 JSON.parse（与 PlannerPlanV1 解析同策略）
- 解析：`JSON.parse(reviewerOutput)`
- DTO 映射：映射为 `CreateReviewRequest`（subTaskId/result/score/issues/comment）
- 校验：`REJECTED` 时 issues 必填（由现有 `ReviewService` 校验兜底）
- `reviewerAgentId`：使用触发时选中的 REVIEWER Agent ID（见上文"触发时机与方式"）
- 落库与状态推进：调用 `ReviewService.createReview()`，由其负责：
  - 写 `review_record`
  - APPROVED → `SubTaskService.complete()` → DONE
  - 触发隐式评分与 RewardLog（主路径）+ 兜底重算（定时任务）

**失败回退（必须可观测）**

- 输出非 JSON / JSON 不合规 / 超时 / 空输出：
  - 写入 `task_timeline`（或最小落库到 sub_task.context.reviewRaw + reason）
  - 给 PLANNER/管理员写 inbox：`sub_task.review_failed`（包含 subTaskId 与失败原因）
  - 子任务保持 `REVIEW`（不自动通过/不自动驳回），等待人工介入或重试策略触发

### 10.2 关键代码

#### HeartbeatService

```java
@Service
public class HeartbeatService {
    /** 心跳/拉取/ack 时调用：刷新在线判定依据（last_seen_at + Redis TTL）。
     * 本项目选择方案 B——心跳即时写回计算态（提升管理台实时性），而非等定时任务计算。
     * 防护规则：
     *   - SLEEPING 不覆盖（仅管理员手动设置/解除）
     *   - OFFLINE Agent 心跳恢复后，按 checkOnlineStatus() 计算（可能是 IDLE）
     *   - offline_reason/offline_at 只在 online_status 变为 OFFLINE 时写入 */
    public void seen(Long agentId) {
        redis.opsForValue().set("agent:heartbeat:" + agentId,
            Instant.now().toString(), Duration.ofMinutes(5));
        Agent agent = agentMapper.selectById(agentId);
        // SLEEPING 保护：心跳不能唤醒手动暂停的 Agent
        if (agent.getOnlineStatus() == AgentOnlineStatus.SLEEPING) {
            agent.setLastSeenAt(OffsetDateTime.now());
            agentMapper.updateById(agent);
            return;
        }
        agent.setLastSeenAt(OffsetDateTime.now());
        AgentOnlineStatus computed = checkOnlineStatus(agent);
        // 从 OFFLINE 恢复：清除离线追溯字段
        if (agent.getOnlineStatus() == AgentOnlineStatus.OFFLINE
            && computed != AgentOnlineStatus.OFFLINE) {
            agent.setOfflineReason(null);
            agent.setOfflineAt(null);
        }
        agent.setOnlineStatus(computed);
        agentMapper.updateById(agent);
    }

    /** 任务执行时调用：刷新活跃度 */
    public void active(Long agentId) {
        agentMapper.updateLastActiveAt(agentId, OffsetDateTime.now());
    }

    /** 三态判定（供调度器使用，OffsetDateTime 与 Agent 实体其余时间字段一致）*/
    public AgentOnlineStatus checkOnlineStatus(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(5);
        if (agent.getLastSeenAt() == null
            || agent.getLastSeenAt().isBefore(cutoff)) {
            return AgentOnlineStatus.OFFLINE;
        }
        if (agent.getLastActiveAt() != null
            && agent.getLastActiveAt().isAfter(cutoff)) {
            return AgentOnlineStatus.ONLINE;
        }
        return AgentOnlineStatus.IDLE;
    }
}
```

#### Reconcile 式健康检查

```java
@Scheduled(fixedRate = 60000)
public void checkHealth() {
    if (!tryLock()) return;
    try {
        List<Agent> staleAgents = agentMapper.selectByLastSeenBefore(
            OffsetDateTime.now().minusMinutes(5));
        for (Agent agent : staleAgents) {
            if (agent.getOnlineStatus() == AgentOnlineStatus.SLEEPING) continue;
            // Step 1: 尝试 ping 恢复（幂等操作：带 timeout + 重试上限 2 次）
            //         失败不产生副作用，仅返回 boolean
            if (tryPingAgent(agent)) { continue; }
            // Step 2: 标记 OFFLINE（只改 onlineStatus，不改 AgentStatus）
            //         使用 CAS 条件更新防止并发覆盖——如果 last_seen_at 已被 seen() 刷新
            //         则 WHERE 条件不匹配，update 返回 0 行，跳过本次标记
            int updated = agentMapper.updateOfflineIfStale(
                agent.getId(), cutoff,
                AgentOnlineStatus.OFFLINE, "heartbeat_lost", OffsetDateTime.now());
            if (updated == 0) continue;  // seen() 已刷新，放弃标记
            reassignStaleTasks(agent.getId());
            // Step 3: 写审计记录（DB task_timeline 权威 + MinIO 镜像）
            taskTimelineService.recordAgentOffline(agent.getId(), "heartbeat_lost");
        }
    } finally { unlock(); }
}
```

### 10.3 验收标准

- [ ] heartbeat 调用后 `last_seen_at` 刷新 + Redis TTL 续约，online_status 按 N4 规则计算（可能是 IDLE，不一定是 ONLINE）
- [ ] 任务执行时 `last_active_at` 刷新
- [ ] 健康检查区分 ONLINE / IDLE / OFFLINE 三态
- [ ] 5 分钟无心跳 → ping → 仍不可达 → online_status=OFFLINE + 任务重新分配 + 审计记录
- [ ] SLEEPING 状态仅由管理员手动设置/解除，不会被系统自动置为 SLEEPING
- [ ] 失败率 > 30% 触发熔断，任务自动路由给同角色其他 ONLINE/IDLE Agent
- [ ] scoreFactors 5 因子加权计算跑通，Reward Log 正常写入

---

## 十一、阶段 5：网页版 AI 接入

**工作量**：10-14 天  
**优先级**：P2  
**前置**：阶段 3 完成

### 11.1 任务列表

| # | 任务 | 技术点 | 工时 |
|---|------|--------|------|
| 5.1 | 引入 Playwright + Chromium | `playwright-java` | 4 小时 |
| 5.2 | WebBrowser Agent 能力画像注册 | capabilities: `{isSlow:true, supportsStreaming:false}` | 2 小时 |
| 5.3 | 浏览器 MCP 工具封装（deepseek/kimi/minimax） | 每工具一个网站流程 | 3 天 |
| 5.4 | Cookie/会话持久化 | Storage State | 1 天 |
| 5.5 | 选择器容错（多选择器 + 模糊匹配） | 页面改版降级 | 2 天 |
| 5.6 | 网页 AI 结果解析 | DOM MutationObserver | 2 天 |
| 5.7 | 验证码检测 + 人工接管提示 | 截图 + 通知 | 1 天 |
| 5.8 | 限流策略 | 每账号每日 N 次 | 1 天 |
| 5.9 | E2E：网页 DeepSeek 完成 PLANNER 角色任务 | 全链路验证 | 2 天 |

### 11.2 验收标准

- [ ] 网页 DeepSeek 可作为 PLANNER 完成简单任务
- [ ] WebBrowser Agent 的 `isSlow:true` 能力正确注册，调度器按 N1 实时任务规则自动过滤（不将 REALTIME 或 deadline < 30min 的任务发给 isSlow Agent）
- [ ] 限流策略生效

---

## 十二、技术选型决策表（v2.1 修订）

| 选型项 | 候选 | 推荐 | 理由 |
|--------|------|------|------|
| MCP 框架 | Spring AI MCP / 官方 SDK / 手写 | **Spring AI MCP** | 复用经验、快速验证 |
| 心跳存储 | Redis / DB / 三件套 | **last_seen_at(DB) + last_active_at(DB) + Redis TTL(缓存)** | v2.1：DB 做权威判定依据，Redis 做快速过滤缓存，重启可回落 DB |
| 熔断框架 | Resilience4j / Sentinel | **Resilience4j** | 轻量、兼容 Spring Boot 3 |
| 浏览器自动化 | Playwright / Selenium | **Playwright** | 稳定性高、自动等待 |
| 工作流模板存储 | JSONB / 文件 | **DB task_timeline(权威) + MinIO(归档镜像)** | v2.1：DB 可直接 SQL 查询分页，MinIO 是镜像 |
| 选人策略 | Round-Robin / Best-Score | **RR 默认 + Best-Score 可选 + label_filters 标签匹配 + online_status 过滤** | v2.1 加在线状态过滤 |
| SKILL.md 更新策略 | 全量覆盖 / Marker 分段 | **Marker 分段** | builtin vs 用户自定义隔离 |
| 凭证存储 | agent 表 / vault 加密 | **api_key 语义升级 + credential_vault（仅 LLM 场景）** | 避免双 token 迁移 |
| MCP 工具注册 | 代码写死 / 表驱动开关 | **agent_mcp_server 表做开关+策略（工具集合固定）** | v2.1 先固定 6 工具，动态生成留后续 |
| LLM 触发方式 | MQ Consumer / @Async | **@Async + TaskExecutor** | v2.1：API Key Agent 是内部同步调用，MQ 留给 CLI 客户端 |
| AgentRouter | 按 modelType 路由到不同 Executor / 单 Executor 内切换 | **单 ApiKeyExecutor 内按 modelType 切换 ChatClient** | 避免不必要的抽象层 |

---

## 十三、风险与缓解措施

### 13.1 技术风险

| # | 风险 | 影响 | 缓解 |
|---|------|------|------|
| R1 | MCP Server 单点故障 | 所有外部 Agent 失联 | 多实例部署 |
| R2 | LLM API 调用失败 / 限流 | 任务中断 | Resilience4j 熔断 + 多 Provider 备份（阶段 2B） |
| R3 | 网页 AI 反爬升级 | 工具大面积失效 | 多账号 + 降级到 API Key 类 |
| R4 | Qoder/Trae MCP 协议升级 | 兼容性问题 | 锁定协议版本 |
| R5 | Spring AI 版本 API 变化 | 代码需重构 | 锁定 BOM 版本 |
| R6 | **v2.1 新增** 设计过度复杂 | 工期膨胀 | **先跑通 E2E 再优化架构**（见附录 E）；阶段 2 拆为 2A/2B，MCP 工具先固定 6 个 |

### 13.2 业务风险

| # | 风险 | 影响 | 缓解 |
|---|------|------|------|
| R7 | 用户注册了 Agent 但未配置凭证 | 任务下发给空 Agent | 注册时校验 capabilities + API_KEY_LLM 需配 credential_vault |
| R8 | 网页 AI 账号被封禁 | 用户服务中断 | 多账号池 + 备用 API Key |
| R9 | 任务执行超时无明确 SLA | 用户体验差 | 模板 config 配置 SLA + 超时报警 |

---

## 十四、简历价值最大化建议

### 14.1 推荐项目标题

> **HelloAI — 多类型 Agent 协作调度平台**
>
> 设计借鉴阿里 AgentTeams（K8s-native Agent 编排），通过 DB + MQ + MinIO 实现业务级 Agent 调度

### 14.2 推荐简历要点（4 条）

1. **多类型 Agent 接入架构**：CLI/API/Web 三种接入方式，每种具备独立可覆盖的能力画像（capabilities），调度器按实际能力+在线状态匹配
2. **可配置工作流引擎**：role_chain 驱动，支持线性链与嵌套 Team 模式，标签过滤 + 在线状态过滤 + 负载均衡
3. **调度可靠性保障**：三件套心跳（last_seen_at + last_active_at + Redis TTL）+ Reconcile 式健康检查 + Resilience4j 熔断 + 工牌模式凭证安全
4. **架构设计能力**：深入研究 AgentTeams（etcd + CRD + Matrix），将其 Reconcile/能力画像/工牌模式适配到 Spring Boot + DB 技术栈，保持自身在事务一致性、状态机严谨性、评分体系上的优势

### 14.3 推荐技术栈展示

```
Java 17 · Spring Boot 3.2 · MyBatis-Plus 3.5 · PostgreSQL 16 (JSONB) ·
RabbitMQ · Redis · Resilience4j · Spring AI MCP · Playwright ·
Flyway · Docker Compose · MinIO (S3)
```

---

## 附录 A：与现有 doc 文档的关联

| 关联文档 | 关联点 |
|----------|--------|
| [HelloAI_技术方案与补齐清单_v1.1.md](HelloAI_技术方案与补齐清单_v1.1.md) | 路线图涵盖 LLM 接入、Executor 抽象、消息可靠性等补齐项 |
| [HelloAI_vs_OpenMOSS_功能对比与实现方案.md](HelloAI_vs_OpenMOSS_功能对比与实现方案.md) | 多类型 Agent 接入对应 OpenMOSS 多客户端支持 |
| [HelloAI_CODE_STYLE.md](HelloAI_CODE_STYLE.md) | 所有新代码遵循该规范，特别是新表 DDL 统一 BaseEntity 风格 |

---

## 附录 B：术语表

| 术语 | 解释 |
|------|------|
| MCP | Model Context Protocol，Anthropic 提出的工具调用标准化协议 |
| SSE | Server-Sent Events，单向长连接推送协议 |
| Outbox Pattern | 事件发布的事务性保障模式，先写 DB outbox，再异步发 MQ |
| Circuit Breaker | 熔断器，防止级联故障 |
| role_chain | 工作流模板中定义的角色执行链 |
| Reconcile | 声明式状态收敛，持续将"实际态"推进到"期望态" |
| 工牌模式 | Agent 的 api_key 仅做身份识别（consumerToken），真实 LLM Key 在 vault |
| 三件套心跳 | last_seen_at（在线判定）+ last_active_at（活跃度）+ Redis TTL（快速缓存） |
| 能力画像 | Agent capabilities JSONB，供调度器做能力匹配，可独立覆盖默认值 |
| SKILL Marker | `<!-- helloai-builtin-start -->` 标记包裹 builtin 段，保护用户自定义内容 |
| 管理态 vs 计算态 | AgentStatus（ACTIVE/DISABLED，纯人工管理态）vs AgentOnlineStatus（ONLINE/IDLE/OFFLINE/SLEEPING，系统计算态）。调度过滤看 onlineStatus，鉴权只看 AgentStatus.DISABLED |

---

## 附录 C：AgentTeams 借鉴清单

### C.1 A 类：强烈推荐直接借鉴

| # | 借鉴点 | AgentTeams 做法 | HelloAI 阶段 | 改动量 | 优先级 |
|---|--------|------------------|-------------|--------|--------|
| A1 | **能力画像** | Worker runtime/model/skills + label 过滤 | 阶段 0 capabilities 列 + labels 列（含实体/DTO/Controller/前端） | +0.5d | P0 |
| A2 | **工牌模式** | CredentialBinding → TokenVault 引用 | api_key 语义升级 + 阶段 2B credential_vault 表 | +3h | P1 |
| A3 | **声明式 MCP 配置** | CR 中 `{Name, URL, Transport}` → 自动注入 | 阶段 3 agent_mcp_server 表做开关+策略（工具固定） | +1d | P0 |
| A4 | **SKILL.md Marker** | wrapWithBuiltinMarkers() | 阶段 3 PromptTemplateService 增加 marker | +0.5d | P1 |
| A5 | **心跳与活跃分离** | LastHeartbeat + LastActiveAt 分开 | 阶段 0 last_seen_at + last_active_at + Redis TTL（2 个字段 30 分钟） | +0.5h | P0 |
| A6 | **Reconcile 健康检查** | Controller reconcile loop 幂等收敛 | 阶段 4 尝试恢复→标记离线→重新分配+审计 | +0.5d | P1 |

### C.2 B 类：思路借鉴，适配使用

| # | 借鉴点 | AgentTeams 做法 | HelloAI 简化 | 阶段 | 改动量 |
|---|--------|------------------|-------------|------|--------|
| B1 | 多 Runtime | openclaw/copaw/hermes/qwenpaw | accessType（CLI/API/Web）已够用 | 阶段 0 | 0 |
| B2 | 标签选择器 | 4 层 Labels 优先级 | label_filters JSONB | 阶段 1 | +0.5d |
| B3 | SLEEPING 状态（手动） | WorkerSpec.State = Running/Sleeping/Stopped | AgentOnlineStatus.SLEEPING（仅手动设置） | 阶段 4 | +0.5d |
| B4 | 工作流外置状态 | taskflow/projectflow 文件树 | DB task_timeline + MinIO timeline.jsonl 镜像 | 阶段 1 | +0.5d |

### C.3 C 类：明确不借鉴

| # | 项 | 理由 |
|---|----|------|
| C1 | K8s CRD + Controller（Go） | 过重。MyBatis-Plus + Job 控制器够用，DB 做权威状态更便于查询审计 |
| C2 | Matrix IM 协议 | inbox/outbox + MQ 已实现可靠分发，Matrix 运维复杂度过高 |
| C3 | Higress AI Gateway | AgentExecutor 直调 LLM API 即可，多一层增加延迟和故障点 |
| C4 | Nacos 模板市场 | 个人项目阶段先不做 |

### C.4 汇总

| 类别 | 数量 | 借鉴点对应工作量（已包含在阶段任务中，不与阶段任务叠加计算） |
|------|------|-----------|
| A 类（强烈推荐） | 6 | +3.5 天 |
| B 类（思路借鉴） | 4 | +1.5 天 |
| C 类（明确不学） | 4 | 0 |
| **总计** | **14** | **+5 天** |

> **重要说明**：本表"借鉴点对应工作量"用于追溯各借鉴点的实施成本，**这些工作已包含在阶段 0/1/2A/2B/3/4 的任务清单中**，不额外叠加。例如 B2 标签选择器（+0.5d）对应的就是阶段 1 任务 1.7（AgentSelector 标签过滤，3h），不是独立任务。

**总工作量**：
- v1.0 合计：阶段 0(1d) + 阶段 1(3d) + 阶段 2(3d) + 阶段 3(5d) + 阶段 4(3d) + 阶段 5(10~14d) = **25~29d**
- v2.2 合计：阶段 0(1.5d) + 阶段 1(4d) + 阶段 2A(2d) + 阶段 2B(2d) + 阶段 3(6.5d) + 阶段 4(4.5d) + 阶段 5(10~14d) = **30.5~34.5d**
- 增量：+5.5d（约 +5d 来自 AgentTeams 借鉴模式，+0.5d 来自状态拆分、task_timeline 新增表和阶段重组）

---

## 附录 D：与 AgentTeams 的关键差异与 HelloAI 优势

### D.1 HelloAI 的独有优势（v2.1 修正）

| # | 维度 | HelloAI 优势 | AgentTeams 对比 |
|---|------|-------------|-----------------|
| 1 | **事务一致性** | PostgreSQL 事务 + Outbox 模式，状态变更有 ACID 保证 | etcd 强一致（Raft），但 MinIO 投影的 JSON 文件无索引无查询能力 |
| 2 | **状态机严谨性** | 显式 9 状态枚举，状态变更通过 SubTaskService 集中管控 | state.json 字符串管理，依赖 Agent 自律 |
| 3 | **评分系统** | scoreFactors 5 因子数据模型已就位，Reward Log 闭环 | 完全缺失，靠 LLM 自评 |
| 4 | **轻量部署** | Docker Compose 单机即可运行 | 生产必须 K8s 或复杂多容器编排 |
| 5 | **可观测性** | ActivityLog + RewardLog + AgentInbox + DB task_timeline（v2.2 新增），全链路结构化审计 | 仅靠 Matrix 房间消息，排障靠翻聊天记录 |

### D.2 定位陈述

> **AgentTeams = Agent 协作的"操作系统"（基础设施级）**
>
> **HelloAI = Agent 协作的"业务中台"（业务级）**
>
> 两者互补而非竞争。HelloAI v2.1 吸收了 AgentTeams 的 Reconcile / 能力画像 / 工牌模式 / SKILL Marker，但在状态存储（DB ACID）、评分体系、部署轻量性上保持了自身优势。

---

## 附录 E：实施优先级建议

### E.1 核心原则

> **"先跑通 E2E，再优化架构"**

HelloAI 当前最大瓶颈不是缺少架构模式，而是 **LLM 执行链路完全没通**。AgentTeams 的精妙设计是在核心链路跑通之后才演化出来的。

### E.2 推荐实施顺序

```
阶段 0 ──→ 阶段 2A ──→ 阶段 3 ──→ 阶段 1 ──→ 阶段 4 ──→ 阶段 2B ──→ 阶段 5
  │            │           │           │           │           │
  │            │           │           │           │           └─ P2 远期
  │            │           │           │           └─ P1 多 Provider + 凭证保险库
  │            │           │           └─ P0 工作流 + 审计快照
  │            │           └─ P0 CLI 接入
  │            └─ P1 跑通一条 LLM 链路（DeepSeek EXECUTOR）
  └─ P0 身份 + 能力画像 + 三件套心跳 + 状态扩展
```

> v2.1 调整：阶段 2 明确拆为 2A（基础链路，在阶段 3 之前）和 2B（多 Provider + 重试 + vault，在阶段 4 之后）。附录 E.2 的顺序不再产生歧义。

### E.3 里程碑

| 里程碑 | 完成标志 | 预计 |
|--------|---------|------|
| **M1: Agent 身份就绪** | 阶段 0 完成：三种 accessType + capabilities + labels + 三件套心跳机制（last_seen_at/last_active_at/Redis TTL）+ 状态枚举扩展 | 第 1 周 |
| **M2: 第一条 E2E 链路** | 阶段 2A 完成：API_KEY_LLM EXECUTOR @Async 调 DeepSeek 成功执行子任务并回写状态 | 第 2 周 |
| **M3: CLI 客户端接入** | 阶段 3 完成：Qoder 通过 MCP 接收任务并回传结果 | 第 3-4 周 |
| **M4: 工作流可配置** | 阶段 1 完成：minimal/full/team 三种模板端到端可用 | 第 4-5 周 |
| **M5: 生产级可靠性** | 阶段 4 完成：三件套心跳 + 熔断 + 自动转发 + Reconcile 健康检查 + 评分完善 | 第 5-6 周 |
| **M6: 多 Provider + 安全凭证** | 阶段 2B 完成：多 LLM Provider + credential_vault 加密存储 | 第 6-7 周 |
| **M7: 差异化能力** | 阶段 5 完成（可选）：网页 AI 作为 PLANNER 完成简单任务 | 第 8-10 周 |

---

**文档结束** — 路线图 v2.3 共 7 个阶段 + 5 个附录，预计 30.5~34.5 工作日。欢迎逐条沟通确认。
