# HelloAI — AI Agent 协作调度平台

> 让多个 AI Agent 像团队一样协作，可靠、透明、可追踪

<p align="center">
  <a href="http://39.106.204.43:5173/#/login">🌐 在线体验</a> •
  <a href="#快速开始">🚀 快速开始</a> •
  <a href="#核心概念">📖 文档</a> •
  <a href="#架构设计">🏗️ 架构</a>
</p>

---

## 📌 项目介绍

HelloAI 是一个面向复杂任务拆解的 **AI Agent 协作调度平台**。它不只是一个对话工具，而是一个生产级的"AI 项目经理"——能够自动理解需求、多轮澄清、拆解任务、调度多个 Agent 并行/串行执行、审核质量、最终合并输出完整报告。

核心定位：**解决多 Agent 协作中的调度混乱、上下文断裂、执行不可追踪问题。**

> 当前业界框架（如 CrewAI、LangGraph）侧重"如何写 Agent"，HelloAI 侧重"如何管 Agent"——调度、容错、可视化、审计。

HelloAI 基于 **Spring Boot + Spring AI MCP 协议**实现多 AI 厂商（Qoder / Trae / Codex CLI / Claude Code 等）的跨平台任务协作：外部 AI 一键接入后，平台像调度微服务一样向它们派发子任务，并回收执行结果。

---

## ✨ 核心功能

### 1. 智能任务规划（Planner）
- **双模对话式需求澄清**：同一会话内自由切换——**CHAT 自由对话**（通用 AI 助手，纯文本问答，闲聊/咨询不被打断）+ **CLARIFY 方案澄清**（结构化选项点选 + 可选联网搜索 + 完成度进度条，产出终稿一键立项）；CHAT 中表达「整理成方案」等意图词经**对话内二次确认**后转入方案模式，或直接输入 **`/planner` 斜杠命令**（可带附加文本）显式直达
- **自动任务拆解**：需求确认后自动拆解为带依赖关系的子任务草案（`PENDING_PLAN_REVIEW` 草案态，不进分发链），用户确认/拒绝后进入既有分发链
- **依赖 DAG**：子任务支持 `depends_on` 依赖，拓扑排序保证执行顺序，上游产出自动注入下游上下文

### 2. 多 Agent 弹性调度（Executor）
- **平台内 API_KEY_LLM**：平台托管的 API-Key 型 Agent（DeepSeek 等），自动执行链路，保底执行
- **外部 CLI Agent**：Qoder / Trae / Codex CLI / Claude Code 等经 MCP 一键接入，实测可用
- **弹性策略**：外部优先 + 空闲优先 + 值班优先（STRICT 独占）+ LLM 保底，策略可配置（`preferExternal` / `requireIdle` / `forceAccessType` / `autoAssignOnCreate`）
- **值班打卡**：外部 Agent `checkIn`/`checkOut` 值班租约（ACTIVE/CLOSED/EXPIRED 状态机 + 到期自动扫描），值班 Agent 优先派单
- **门铃秒级唤醒**：服务端 → Agent 的 SSE 单向长连接（`/api/agents/doorbell/sse`），新任务秒级响铃，替代传统定时轮询；下班/租约到期自动断铃

### 3. 上下文连续性保障
- **Task Running Spec 结构化运行规范**：每个任务持有 Baseline（目标/约束/DAG 结构）+ 各子任务执行的结构化摘要（EXECUTION_RECORD）+ 系统自动编译的全局上下文，统一注入执行 Prompt
- **双轨依赖注入**：下游子任务按 `depends_on` 声明顺序同时获得直接前置的**结构化摘要**与**完成内容本体**（物化附件优先、`context.lastExecution.output` 回退），渲染于 `## 依赖产出参考（直接前置）` 章节，多前置全量收集不覆盖；JSONB 分段锁保证并发回填互不覆盖
- 被 Reviewer 驳回后重新生成时携带：
  - 前置任务结果
  - 本轮任务要求
  - 上次生成结果
  - 全部历史驳回意见（`reviewHistory` 多轮累积）
- 真正做到"听人话、会修改"

### 4. 可视化追踪
- **依赖图**：子任务列表按主任务过滤时切换 DAG 视图，拓扑分层流水线展示执行顺序与依赖
- **时间线列表**：记录每一步操作的实际执行步骤（`task_timeline` 事件流）
- **执行时序图**：泳道式 mermaid 时序图，完整展示单个子任务周期内的所有关键节点（领取/执行/返工/驳回/熔断全可看）

### 5. 质量审核（Reviewer）
- 独立 Reviewer Agent 对产出进行审核，支持多轮驳回-修正循环
- 审核意见（`subtask_review_result`）与执行对话流一起可视化展示
- 最终由 Planner 整合所有子任务产出，生成连贯的最终整合报告

### 6. 报告生成与交付物
- **最终整合报告**：任务收口后 Planner 将全部 DONE 子任务产出整合为一份连贯报告（执行摘要 + 重组正文 + 结论），自动触发 + 手动补生成；**生成状态四态（NONE/GENERATING/DONE/FAILED）** + CAS 防重入（重复触发被拒），失败可一键重试，前端实时展示「报告生成中」
- **交付物 zip 下载**：按拓扑序打包全部子任务产出（优先物化附件），单任务一键下载
- **执行产出物化**：执行成功后的 LLM 输出自动落盘为附件（`local://` 存储抽象，流式下载）

### 7. 生产级可靠性
| 场景 | 策略 |
|------|------|
| 外部 Agent 连续失败（默认 3 次） | 自动回退平台内 API_KEY_LLM 保底执行（同角色替补） |
| 调度/执行链路异常 | Resilience4j per-agent 熔断（滑动窗口 10 次 / 30% 失败率触发） |
| 重分配达阈值（默认 5 次） | 子任务转入 `DEAD_LETTER` 死信池，人工审核后一键重新派发（`POST /api/sub-tasks/dead-letter/redispatch/{id}`） |
| 执行命令投递 | 事务性 Outbox（PENDING/SENT/CONFIRMED/FAILED 四态）+ RabbitMQ publisher confirms + 超时回退重试 |
| 消息重复消费 | 三层幂等：DB CAS + Redis + 消费日志 |
| 外部 Agent 离线/超时 | Reconcile 健康检查 + 离线重分配 + 执行超时补偿（TIMEOUT/BLOCKED） |
| 前置任务未完成 | `depends_on` 拓扑守卫：下游不提前分发，不会无效分配 Agent |
| 子任务滞留 PENDING（孤儿） | 5 分钟快速巡检兜底（isReady 依赖守卫不误伤未就绪任务），分发异常写 `sub_task_dispatch_deferred` 事件可观测 |
| 在线状态判定 | 三件套：`last_seen_at` / `last_active_at` / `online_status`，`heartbeat` 工具刷新 |

---

## 🏗️ 架构设计

```
┌──────────────────────────────────────────────────────────────┐
│                    用户层（Vue 3 SPA）                         │
│         需求澄清 / 计划确认 / 依赖图 / 时序图 / 报告下载         │
└──────────────────────────────────────────────────────────────┘
                              │ REST (/api/**)
┌──────────────────────────────────────────────────────────────┐
│                   调度核心层（Spring Boot）                     │
│  ┌──────────────┐   ┌─────────────────┐   ┌──────────────┐   │
│  │ Planner      │ → │ 拆解/草案/确认    │ → │ 弹性调度器    │   │
│  │ 双模对话/拆解 │   │ PENDING_PLAN_   │   │ 外部优先/空闲 │   │
│  │              │   │ REVIEW → PENDING │   │ 优先/LLM保底 │   │
│  └──────────────┘   └─────────────────┘   └──────────────┘   │
│                              ↓                                │
│  执行命令（Outbox → RabbitMQ）→ 执行消费 → 结果回写（幂等）      │
│                              ↓                                │
│   ┌────────────┐   ┌──────────┐   ┌──────────────────────┐    │
│   │ Reviewer   │ → │ 驳回返工  │ → │ Planner 最终整合报告   │    │
│   │ 质量审核    │   │ 多轮循环  │   │ + 交付物 zip 下载      │    │
│   └────────────┘   └──────────┘   └──────────────────────┘    │
└──────────────────────────────────────────────────────────────┘
           │ MCP（/mcp/sse）            │ 门铃 SSE（秒级唤醒）
┌───────────────────────────┐   ┌────────────────────────────┐
│ 平台内 API_KEY_LLM Agent   │   │ 外部 CLI Agent             │
│ （DeepSeek 等 API-Key）    │   │ Qoder / Trae / Codex / CC   │
└───────────────────────────┘   └────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│                 基础设施层（Docker Compose）                    │
│    PostgreSQL 16.4  •  Redis 7.2  •  RabbitMQ 3.12           │
│    MinIO（对象存储）• 本地物化存储兜底（local:// / minio://）      │
└──────────────────────────────────────────────────────────────┘
```

**关键通道口径**

- MCP SSE（`/mcp/sse` + `/mcp/messages`）是外部 Agent 的唯一主协议通道；REST `tools/list` / `tools/call` 为兼容保留
- 门铃 SSE（`/api/agents/doorbell/sse`）是服务端 → Agent 的单向唤醒信号，先打卡才允许建连；门铃丢失无损回退轮询
- MCP 工具集：`pullTasks` / `ack` / `claimSubTask` / `heartbeat` / `uploadArtifact` / `submitResult` / `reportBlocked` / `getAgentStatus` / `checkIn` / `checkOut`，工具数量以 `tools/list` 实际返回为准

---

## 🛠️ 技术栈

| 层级 | 技术 | 版本/说明 |
|------|------|------|
| 运行时 | JDK | **17**（项目红线，永久锁定） |
| 后端框架 | Spring Boot | **3.4.10** |
| AI 框架 | Spring AI | **1.1.8**（MCP Server / 多 LLM 统一接入） |
| 持久化 | PostgreSQL + MyBatis-Plus + Flyway | 16.4 / 3.5.9 / 自动迁移 |
| 缓存 | Redis（Lettuce） | 7.2 |
| 消息队列 | RabbitMQ | 3.12（publisher confirms / DLX / 手动 ACK） |
| 对象存储 | MinIO + 本地物化存储 | `minio://` 默认 / `local://` 兜底（v2.7 起 minio:// 附件平台可直读） |
| 弹性 | Resilience4j CircuitBreaker | — |
| 协议 | MCP（SSE）/ 门铃 SSE 长连接 | Spring AI MCP Server |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus | + ECharts / Mermaid |
| 监控 | Spring Boot Actuator | health / metrics / circuitbreakers |
| 部署 | Docker Compose + Nginx | 见 `docker-compose.yml` / `docker-compose.server.yml` |

**项目结构**（多模块 Maven 工程）

```
helloai/
├── helloai-common/   # 公共基础（常量、枚举、异常、配置属性）
├── helloai-mq/       # 消息队列（RabbitMQ 配置 + 幂等消费基类）
├── helloai-core/     # 核心业务（业务域分包：agent/task/system/shared/planner）
├── helloai-api/      # REST 接口层（Controller + DTO，禁连 Mapper）
├── helloai-job/      # 定时任务（Outbox 中继/超时补偿/健康检查/租约过期）
├── helloai-start/    # 启动模块（Application + application.yml + Flyway 迁移）
├── helloai-ui/       # 前端（Vue 3 SPA）
├── scripts/          # 验证脚本（powershell/ + shell/，脚本输出即事实源）
└── doc/              # 项目文档（见 doc/README.md 文档地图）
```

---

## 🚀 快速开始

### 环境要求
- JDK 17（项目红线）
- Maven 3.8+
- Node.js 18+
- Docker + Docker Compose（PostgreSQL / Redis / RabbitMQ / MinIO 基础设施）

### 1. 克隆项目
```bash
git clone https://gitee.com/undefined_404/helloai.git
cd helloai
```

### 2. 启动基础设施
```bash
docker compose up -d
# PostgreSQL(15432) / Redis(26379) / RabbitMQ(25672) / MinIO(29000)
```

### 3. 配置后端
编辑 `helloai-start/src/main/resources/application.yml`（或通过环境变量覆盖）：
- 数据库 / Redis / RabbitMQ 连接（本地 Docker 默认即可）
- **唯一必需的部署配置**：`HELLOAI_CREDENTIAL_AES_KEY_BASE64`（凭证加密密钥，AES-GCM；yml 内默认值仅供开发环境）
- LLM Provider 的 API Key **无需在部署前配置**（先启动后配置）：启动后管理员登录 "系统设置 → 模型配置（LLM Provider）" 页填写/轮换，加密写入 `credential_vault`，实时生效无需重启；yml 中 `helloai.providers.<name>.api-key` 已置空，仅作为环境变量兜底（`DEEPSEEK_API_KEY` / `MOONSHOT_API_KEY` / `MINIMAX_API_KEY` / `DASHSCOPE_API_KEY`）

### 4. 启动后端（Flyway 自动执行数据库迁移）
```bash
mvn clean package -DskipTests
java -jar helloai-start/target/helloai-start.jar
```

后端启动后访问：
- API: <http://localhost:6565>
- Swagger UI: <http://localhost:6565/swagger-ui.html>
- 健康检查: <http://localhost:6565/actuator/health>

### 5. 启动前端
```bash
cd helloai-ui
npm install
npm run dev
```

访问 <http://localhost:5173> 即可体验。

---

## 📖 核心概念

| 角色 | 职责 | 类比 |
|------|------|------|
| **Planner** | 需求澄清、任务拆解、草案确认、最终整合报告 | 项目经理 |
| **Executor** | 消费子任务、调用 LLM/Agent 执行、记录执行日志 | 开发团队 |
| **Reviewer** | 审核产出质量、提出修改意见、触发返工 | QA / 架构师 |
| **API_KEY_LLM** | 平台托管的 API-Key 模型 Agent，保底执行 | 正式员工 |
| **CLI_CLIENT** | 通过 MCP 接入的外部 AI（Qoder / Trae / Codex / Claude Code） | 外包人员 |

### 任务生命周期
```
需求输入 → Planner 双模对话（CHAT 自由对话 / CLARIFY 方案澄清，意图词二次确认 / /planner 直达）
    → 终稿 → 自动拆解为子任务草案 → 用户确认草案
    → 子任务入队分发（ASSIGNED）→ 弹性调度 Agent
    → 执行（Baseline + 双轨依赖注入 + 结构化摘要）→ 结果提交
    → Reviewer 审核 → [通过] → Planner 整合 → 最终报告（四态防重）+ zip 下载
                    → [驳回] → 携带历史驳回意见重新执行 → 再次审核
异常路径：外部 Agent 超时/失败 → 熔断回退 API_KEY_LLM → 重分配达阈值 → DEAD_LETTER 人工兜底
```

### 外部 AI Agent 快速接入
1. 管理端创建 Agent（角色 EXECUTOR，类型 CLI_CLIENT），复制一键生成的 SKILL 说明；
2. 在外部 AI（如 Qoder / Trae）中粘贴执行该 SKILL，AI 将自动完成：注册鉴权 → MCP 连接 → `checkIn` 打卡 → 建立门铃长连接；
3. 平台派单后 AI 收到门铃信号，按 SKILL 规则 `pullTasks` → `claimSubTask` → 执行 → `submitResult`；
4. 异常路径：执行受阻调 `reportBlocked`（带证据链）；超时未提交由平台自动补偿并改派同角色值班 Agent。

---

## 🖼️ 功能预览

> 在线演示：http://39.106.204.43:5173/#/login

| 功能 | 说明 |
|------|------|
| 登录页 | AI 主题动态交互设计（星空背景 + 原创虚拟人物） |
| 需求澄清对话 | 与 Planner 双模对话（CHAT 闲聊 / CLARIFY 方案澄清），结构化选项点选 + 联网搜索 + /planner 直达，终稿一键立项 |
| 自动拆解 | 需求确认后自动生成子任务草案，用户确认/拒绝 |
| 依赖图 | 拓扑分层流水线，展示子任务依赖关系 |
| 时间线 / 时序图 | 记录每一步操作详情，泳道式展示单任务执行周期 |
| 值班看板 | 外部 Agent 值班租约列表与状态概览 |
| 报告下载 | 最终整合报告 + 全子任务产出 zip 一键下载 |

---

## 🗺️ 路线图

**已交付 ✅**

- [x] 多 LLM 模型统一接入（DeepSeek 实测 + Moonshot / MiniMax / DashScope 预置）
- [x] 双模对话式需求澄清（CHAT 自由对话 / CLARIFY 方案澄清 / 意图词二次确认 / /planner 直达 / 推荐卡片）
- [x] 任务自动拆解 + 草案确认（依赖 DAG / 拓扑排序 / 双轨依赖注入）
- [x] 上下文连续性保障（Task Running Spec 双轨注入 + reviewHistory 多轮累积）
- [x] 多轮审核-修正机制
- [x] 可视化依赖图 / 时间线 / 时序图
- [x] 弹性调度（外部优先 + 空闲优先 + 值班优先 + LLM 保底 + 熔断降级）
- [x] MCP 外部 Agent 接入 + 值班打卡 + 门铃秒级唤醒
- [x] 可靠投递（Outbox 四态 + publisher confirms + 三层幂等 + 死信人工兜底）
- [x] 报告生成与交付物（四态防重最终报告 + zip 下载 + 产出物化）

**待办 🔜**

- [ ] 领域模板市场（技术方案 / 代码审查 / 文档生成）
- [ ] 执行层扩展（文件操作 / 联网搜索 / 工具调用，Agent 侧）
- [ ] 浏览器型 Agent（WEB_BROWSER）真实接入链路
- [ ] 工作流模板与 Team 编排
- [ ] 多租户与权限隔离
- [ ] 分布式调度扩展（多实例门铃 fanout 等）
- [ ] 值班租约增强（动态 TTL 自适应 / concurrency 预扣）
- [ ] 结构化多文件产出物化（方案 3：LLM manifest）

---

## 📚 文档导航

先看 [`doc/README.md`](doc/README.md)（文档地图：每份文档的定位与事实等级），四份事实源：

- 代码规范：[`doc/HelloAI_CODE_STYLE.md`](doc/HelloAI_CODE_STYLE.md)（改代码前必读）
- 项目基线：[`doc/HelloAI_项目基线文档.md`](doc/HelloAI_项目基线文档.md)
- 实现差距：[`doc/HelloAI_实现差距表.md`](doc/HelloAI_实现差距表.md)
- 当前进度：[`doc/项目进度.md`](doc/项目进度.md)

其他：EXECUTOR 接入指南 [`.executor-onboarding.md`](.executor-onboarding.md) / 设计系统 [`DESIGN.md`](DESIGN.md) / 产品定义 [`PRODUCT.md`](PRODUCT.md) / English [`README.en.md`](README.en.md)

---

## 🤝 参与贡献

1. Fork 本仓库
2. 新建 `feat_xxx` 或 `fix_xxx` 分支
3. 改代码前必读 `doc/HelloAI_CODE_STYLE.md`；涉及调度/执行链改动需先读 `doc/design/HelloAI_调度解耦重构分析.md`
4. 提交前跑通与改动面相关的 `scripts/` 验证脚本，PR 附上脚本输出

---

## 📄 许可证

本项目采用 [木兰宽松许可证，第2版](http://license.coscl.org.cn/MulanPSL2) 开源，详见 [LICENSE](LICENSE)。
