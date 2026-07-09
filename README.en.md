# HelloAI

> Intelligent Agent Management Platform — Unified orchestration, monitoring, and optimization for multi-type AI Agents

#### Introduction

- **HelloAI** is an AI Agent management platform built on the Spring AI MCP protocol. It enables administrators to onboard Agents, orchestrate tasks, monitor performance, and evaluate quality from a single dashboard.
- The platform communicates with Agents via **MCP SSE** (`/mcp/sse`) and exposes standard tools: `pullTasks` / `ack` / `heartbeat` / `uploadArtifact` / `getAgentStatus` / `claimSubTask`.
- Runtime red-line: **JDK 17**. No Spring AI 2.0 / Spring Boot 4.0 upgrades unless the project explicitly opens a JDK upgrade window.

Supported Agent types (examples):
- CLI Agent
- API Key Agent (OpenAI-compatible / custom)
- Web Agent
- EXECUTOR Agent (local executor)

#### Software Architecture

**Tech stack**

| Layer | Technology | Version |
|---|---|---|
| Runtime | JDK | **17** (project red-line, permanently locked) |
| Backend framework | Spring Boot | **3.4.10** |
| AI protocol | spring-ai | **1.1.0** (permanent stable baseline) |
| MCP SDK | mcp-sdk | 0.16.0 |
| Persistence | PostgreSQL + MyBatis-Plus + Flyway | — |
| Cache | Redis (Lettuce) | — |
| Message queue | RabbitMQ | — |
| Resilience | Resilience4j CircuitBreaker | — |
| Observability | Spring Boot Actuator | — |
| Frontend | Vue 3 + TypeScript + Vite + Element Plus | — |

**Repository structure**

```
helloai/                          # Multi-module Maven project
├── helloai-common/               # Common utilities (constants, exceptions, enums)
├── helloai-api/                  # REST API layer (Controller + DTO + VO)
├── helloai-core/                 # Core services (MCP Server, dispatcher, business)
├── helloai-mq/                   # RabbitMQ config + consumers
├── helloai-job/                  # Scheduled jobs (health checks, SESSION_AUTH cleanup)
├── helloai-start/                # Application bootstrap + application.yml + Flyway
├── helloai-ui/                   # Frontend (Vue 3 SPA)
└── doc/                          # Docs
    └── HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md
```

#### Installation

**Prerequisites**

- JDK 17
- Maven 3.8+
- Docker + Docker Compose (infrastructure)

**Steps**

```bash
# 1. Start infrastructure (PostgreSQL / Redis / RabbitMQ)
docker compose up -d

# 2. Build and start backend
mvn clean package -DskipTests
java -jar helloai-start/target/helloai-start.jar

# 3. Start frontend
cd helloai-ui
npm install
npm run dev
```

After backend startup:
- API: <http://localhost:6565>
- Swagger UI: <http://localhost:6565/swagger-ui.html>
- Health: <http://localhost:6565/actuator/health>

#### Usage

**Regression scripts (golden standard)**

| Script | Scope |
|---|---|
| `verify-mcp-auth.ps1` | MCP auth regression (D1-D6) |
| `verify-mcp-e2e.ps1` | MCP end-to-end business loop (with DB evidence T1-T4) |

**Documentation**

- Roadmap: [`doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md`](doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md)
- EXECUTOR onboarding: [`.executor-onboarding.md`](.executor-onboarding.md)
- Design system: [`DESIGN.md`](DESIGN.md)
- Product definition: [`PRODUCT.md`](PRODUCT.md)

#### Contributing

1. Fork this repository
2. Create a branch `feat_xxx` or `fix_xxx`
3. Run `verify-mcp-auth.ps1` and `verify-mcp-e2e.ps1` before pushing
4. Open a Pull Request with script outputs attached

#### Tips

1. Chinese README: [`README.md`](README.md)
2. Regression scripts are the source of truth: `verify-mcp-auth.ps1` / `verify-mcp-e2e.ps1`
3. Start with the roadmap before diving into code

#### License

[LICENSE](LICENSE)
