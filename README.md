# HelloAI

> 智能 Agent 管理平台 — 多类型 Agent 编排、监控、优化一体化

#### 介绍

- **HelloAI** 是一个基于 Spring AI MCP 协议的 AI Agent 管理平台，管理员可在统一面板内完成 Agent 接入、任务编排、性能监控与质量评估。
- 平台通过 **MCP SSE**（`/mcp/sse`）与 Agent 双向通信，当前主线已具备 MCP 工具注册、鉴权、任务拉取/认领、回执、心跳与在线状态管理能力。
- 项目运行时红线：**JDK 17**；不引入 Spring AI 2.0 / Spring Boot 4.0 路线（除非项目方主动开启 JDK 升级窗口）。

平台支持多类型 Agent 接入（示例）：
- CLI Agent
- API Key Agent（OpenAI-compatible / 自定义）
- Web Agent
- EXECUTOR Agent（本地执行器）

#### 软件架构

**技术栈**

| 层 | 技术 | 版本 |
|---|---|---|
| 运行时 | JDK | **17**（项目红线，永久锁定）|
| 后端框架 | Spring Boot | **3.4.10** |
| AI 协议 | spring-ai | **1.1.8**（当前仓库运行版本，已通过 macOS 主链路回归）|
| MCP SDK | mcp-sdk | 0.16.0 |
| 持久化 | PostgreSQL + MyBatis-Plus + Flyway | — |
| 缓存 | Redis (Lettuce) | — |
| 消息队列 | RabbitMQ | — |
| 弹性 | Resilience4j CircuitBreaker | — |
| 监控 | Spring Boot Actuator | — |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus | — |

**项目结构**

```
helloai/                          # 多模块 Maven 工程
├── helloai-common/               # 公共基础（常量、异常、枚举）
├── helloai-api/                  # REST 接口层（Controller + DTO + VO）
├── helloai-core/                 # 核心业务（Service + MCP Server + 熔断调度）
├── helloai-mq/                   # 消息队列（RabbitMQ 配置 + 消费者）
├── helloai-job/                  # 定时任务（健康检查 + SESSION_AUTH 清理）
├── helloai-start/                # 启动模块（Application + application.yml + Flyway）
├── helloai-ui/                   # 前端（Vue 3 SPA）
└── doc/                          # 项目文档
    ├── HelloAI_项目基线文档.md
    ├── HelloAI_实现差距表.md
    ├── HelloAI_迭代执行记录.md
    ├── HelloAI_调度解耦重构分析.md
    ├── HelloAI_执行链路架构分析.md
    ├── HelloAI_架构设计参考.md
    └── HelloAI_外部项目借鉴技术细节.md
```

#### 安装教程

**前置依赖**

- JDK 17
- Maven 3.8+
- Docker + Docker Compose（基础设施）

**步骤**

```bash
# 1. 启动基础设施（PostgreSQL / Redis / RabbitMQ）
docker compose up -d

# 2. 编译 + 启动后端
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

**验收与回归（金标准脚本）**

| 脚本 | 范围 |
|---|---|
| `verify-mcp-auth.ps1` | MCP 鉴权回归（D1-D6） |
| `verify-mcp-e2e.ps1` | MCP 端到端业务循环（含 T1-T4 DB 取证） |
| `verify-mcp-auth.sh` | macOS 版 MCP 鉴权回归（D1-D6） |
| `verify-mcp-e2e.sh` | macOS 版 MCP 端到端业务循环（含 T1-T4 DB 取证） |

当前版本说明：

- 当前父工程 `pom.xml` 运行版本为 `spring-ai 1.1.8`
- 已在 macOS 下完成 `verify-mcp-auth.sh` + `verify-mcp-e2e.sh` 主链路回归
- Windows 下原 `verify-mcp-auth.ps1` + `verify-mcp-e2e.ps1` 保留为兼容验证入口

**MCP 通道与工具口径**

- 主通道：MCP SSE（`/mcp/sse` + `/mcp/messages`）是唯一主通道
- 兼容通道：`McpController` 的 REST `tools/list` / `tools/call` 属于兼容保留（计划逐步标记 deprecated，详见差距表 D2）
- 工具数量：不在 README 写死数量；以 `tools/list` 返回为准（详见差距表 D1/N3）
- 心跳刷新：`last_seen_at`/在线态刷新以 `heartbeat` 为主；是否将 pull/ack 也计入活跃需代码配合再升级（详见差距表 D6）

**文档导航**

- 项目基线：[`doc/HelloAI_项目基线文档.md`](doc/HelloAI_项目基线文档.md)
- 实现差距：[`doc/HelloAI_实现差距表.md`](doc/HelloAI_实现差距表.md)
- 迭代执行记录：[`doc/log/HelloAI_迭代执行记录.md`](doc/log/HelloAI_迭代执行记录.md)
- 调度架构分析：[`doc/design/HelloAI_调度解耦重构分析.md`](doc/design/HelloAI_调度解耦重构分析.md)
- 执行链路分析：[`doc/archive/HelloAI_执行链路架构分析.md`](doc/archive/HelloAI_执行链路架构分析.md)
- 架构设计参考：[`doc/design/HelloAI_架构设计参考.md`](doc/design/HelloAI_架构设计参考.md)
- 外部项目借鉴技术细节：[`doc/design/HelloAI_外部项目借鉴技术细节.md`](doc/design/HelloAI_外部项目借鉴技术细节.md)
- EXECUTOR 接入指南：[`.executor-onboarding.md`](.executor-onboarding.md)
- 设计系统：[`DESIGN.md`](DESIGN.md)
- 产品定义：[`PRODUCT.md`](PRODUCT.md)

#### 参与贡献

1. Fork 本仓库
2. 新建 `feat_xxx` 或 `fix_xxx` 分支
3. 提交前必须按当前开发环境跑通对应回归脚本：
   - Windows：`verify-mcp-auth.ps1` + `verify-mcp-e2e.ps1`
   - macOS：`verify-mcp-auth.sh` + `verify-mcp-e2e.sh`
4. 新建 Pull Request，附上脚本输出

#### 特技

1. English: [`README.en.md`](README.en.md)
2. 回归脚本是事实源：Windows 用 `verify-mcp-auth.ps1` / `verify-mcp-e2e.ps1`，macOS 用 `verify-mcp-auth.sh` / `verify-mcp-e2e.sh`
3. 优先读项目基线与实现差距，再看历史路线图：[`doc/HelloAI_项目基线文档.md`](doc/HelloAI_项目基线文档.md)

#### 许可证

[LICENSE](LICENSE)
