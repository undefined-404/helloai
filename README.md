# HelloAI

> AI Agent 协作调度平台 — 让 AI Agent 像微服务一样被调度

#### 介绍

- **HelloAI** 是一个基于 Spring AI MCP 协议的 实现多AI厂商（Qoder/Trae/Codex CLI/Claude Code 等）的跨平台任务协作的调度平台：外部 AI（Qoder、Trae、Codex CLI、Claude Code 等）一键接入后，平台像调度微服务一样向它们派发子任务，并回收执行结果。
- 平台通过 **MCP SSE**（`/mcp/sse`）与 Agent 通信，通过**门铃 SSE 长连接**（`/api/agents/doorbell/sse`）实现任务秒级唤醒——外部 AI 收到响铃信号后主动调 MCP 工具取件，替代传统的定时轮询待办模式。
- 项目运行时红线：**JDK 17**；不引入 Spring AI 2.0 / Spring Boot 4.0 路线（除非项目方主动开启 JDK 升级窗口）。

**核心能力**

| 能力 | 说明 |
|---|---|
| 一键接入 | 外部 AI 注册后获得一键生成的 skills 说明，按说明即可完成连接、打卡、领任务全流程 |
| 值班打卡 | `checkIn`/`checkOut` 值班租约（ACTIVE/CLOSED/EXPIRED 状态机 + 到期自动扫描），值班 Agent 优先派单 |
| 门铃唤醒 | 服务端 → Agent 的 SSE 单向长连接，新任务秒级响铃；先打卡才允许建连，下班/租约到期自动断铃 |
| MCP 工具协议层 | `pullTasks` / `claimSubTask` / `submitResult` / `reportBlocked` / `heartbeat` / `uploadArtifact` 等工具，工具数量以 `tools/list` 实际返回为准 |
| 弹性调度 | 外部优先 + 空闲优先 + LLM 保底；外部 Agent 连续失败超阈值自动回退平台内 API_KEY_LLM；同角色替补 |
| 可靠投递 | 事务性 Outbox（PENDING/SENT/CONFIRMED/FAILED 四态）+ publisher confirms + 超时回退重试 |
| 稳定性 | Resilience4j per-agent 熔断降级、Reconcile 健康检查、执行超时补偿、三层幂等消费（DB CAS + Redis + 消费日志） |

**支持接入的 Agent 类型**

- `CLI_CLIENT`：外部 AI Agent（Qoder / Trae / Codex CLI / Claude Code 等，已实测接入）
- `API_KEY_LLM`：平台托管的 API Key 型 Agent（自动执行链路）
- `WEB_BROWSER`：网页版 AI（枚举预留，接入链路规划中）

#### 软件架构

**技术栈**

| 层 | 技术 | 版本 |
|---|---|---|
| 运行时 | JDK | **17**（项目红线，永久锁定）|
| 后端框架 | Spring Boot | **3.4.10** |
| AI 协议 | Spring AI | **1.1.8** |
| 持久化 | PostgreSQL + MyBatis-Plus + Flyway | — |
| 缓存 | Redis (Lettuce) | — |
| 消息队列 | RabbitMQ（含 publisher confirms / DLX） | — |
| 弹性 | Resilience4j CircuitBreaker | — |
| 监控 | Spring Boot Actuator | — |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus | — |

**项目结构**

```
helloai/                          # 多模块 Maven 工程
├── helloai-common/               # 公共基础（常量、枚举、异常、配置属性）
├── helloai-mq/                   # 消息队列（RabbitMQ 配置 + 幂等消费基类）
├── helloai-core/                 # 核心业务（业务域分包）
│   └── com.helloai.core/
│       ├── agent/                #   智能体域：注册/调度/执行/对话/MCP/门铃可观测
│       ├── task/                 #   任务域：任务/子任务/评审/评分/状态机/时间线
│       ├── system/               #   系统支撑域：用户/配置/规则/凭据/附件
│       └── shared/               #   跨域设施：领域事件/门铃通道
├── helloai-api/                  # REST 接口层（Controller + DTO，禁连 Mapper）
├── helloai-job/                  # 定时任务（Outbox 中继/超时补偿/健康检查/租约过期）
├── helloai-start/                # 启动模块（Application + application.yml + Flyway）
├── helloai-ui/                   # 前端（Vue 3 SPA）
├── scripts/                      # 验证脚本（powershell/ + shell/）
└── doc/                          # 项目文档（见 doc/README.md 文档地图）
```

#### 安装教程

**前置依赖**

- JDK 17
- Maven 3.8+
- Docker + Docker Compose（基础设施）

**步骤**

```bash
# 1. 启动基础设施（PostgreSQL / Redis / RabbitMQ / MinIO）
docker compose up -d

# 2. 编译 + 启动后端（Flyway 自动执行 V1~V23 迁移）
mvn clean package -DskipTests
java -jar helloai-start/target/helloai-start.jar

# 3. 启动前端
cd helloai-ui
npm install
npm run dev
```

后端启动后访问：
- API: <http://localhost:6565>
- Swagger UI: <http://localhost:6565/swagger-ui.html>
- 健康检查: <http://localhost:6565/actuator/health>

#### 使用说明

**外部 AI Agent 快速接入**

1. 管理端创建 Agent（角色 EXECUTOR，类型 CLI_CLIENT），复制一键生成的 skills 说明；
2. 在外部 AI（如 Qoder / Trae）中粘贴执行该 skills，AI 将自动完成：注册鉴权 → MCP 连接 → `checkIn` 打卡 → 建立门铃长连接；
3. 平台派单后 AI 收到门铃信号，按 skills 规则 `pullTasks` → `claimSubTask` → 执行 → `submitResult`；
4. 异常路径：执行受阻调 `reportBlocked`（带证据链）；超时未提交由平台自动补偿并改派同角色值班 Agent。

**验证与回归脚本**

所有验证脚本位于 `scripts/powershell/`（Windows）与 `scripts/shell/`（macOS），脚本输出即事实源：

| 脚本 | 覆盖范围 |
|---|---|
| `verify-mcp-auth.*` | MCP 鉴权回归 |
| `verify-mcp-e2e.*` | MCP 端到端业务循环 |
| `verify-onboarding*.ps1` | 外部 Agent 接入五步闭环（注册/打卡/门铃/拉任务/提交） |
| `verify-doorbell-e2e.ps1` | 门铃长连接（建连/握手/到期断连） |
| `verify-agenthub-duty-e2e.ps1` | 值班租约（checkIn/checkOut/过期扫描/STRICT 独占） |
| `verify-poller-e2e.ps1` | DB Poller 兜底消费 |

**MCP 通道口径**

- 主通道：MCP SSE（`/mcp/sse` + `/mcp/messages`）是唯一主通道；REST `tools/list` / `tools/call` 为兼容保留
- 心跳刷新：`last_seen_time` / 在线态刷新以 `heartbeat` 工具为主

**文档导航**

先看 [`doc/README.md`](doc/README.md)（文档地图：每份文档的定位与事实等级），四份事实源：

- 代码规范：[`doc/HelloAI_CODE_STYLE.md`](doc/HelloAI_CODE_STYLE.md)（V1.4，改代码前必读）
- 项目基线：[`doc/HelloAI_项目基线文档.md`](doc/HelloAI_项目基线文档.md)
- 实现差距：[`doc/HelloAI_实现差距表.md`](doc/HelloAI_实现差距表.md)
- 当前进度：[`doc/项目进度.md`](doc/项目进度.md)

其他：EXECUTOR 接入指南 [`.executor-onboarding.md`](.executor-onboarding.md) / 设计系统 [`DESIGN.md`](DESIGN.md) / 产品定义 [`PRODUCT.md`](PRODUCT.md)

#### 参与贡献

1. Fork 本仓库
2. 新建 `feat_xxx` 或 `fix_xxx` 分支
3. 改代码前必读 `doc/HelloAI_CODE_STYLE.md`；涉及调度/执行链改动需先读 `doc/design/HelloAI_调度解耦重构分析.md`
4. 提交前跑通与改动面相关的 `scripts/` 验证脚本，PR 附上脚本输出

#### 特技

1. English: [`README.en.md`](README.en.md)
2. 优先读事实源文档（基线/差距/进度），历史设计文档已归档至 `doc/archive/`，不作为开发依据

#### 许可证

[LICENSE](LICENSE)