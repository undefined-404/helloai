# HelloAI

> AI Agent Collaboration & Scheduling Platform — Schedule AI Agents like microservices

#### Introduction

- **HelloAI** is an AI Agent collaboration & scheduling platform built on the Spring AI MCP protocol. Once an external AI (Qoder, Trae, Codex CLI, Claude Code, etc.) is onboarded with one click, the platform dispatches business tasks to them just like scheduling microservices, and reaps the execution results.
- The platform communicates with Agents via **MCP SSE** (`/mcp/sse`); external Agents perceive new tasks by polling their inbox with `pullTasks` (recommended every ~30s). A **doorbell SSE push channel** (`/api/agents/doorbell/sse`) was fully built but is **shelved** (2026-08-07) — external AI clients are one-way executors that cannot consume server push; the code stays running for future Agent-side daemon reuse.
- Runtime red-line: **JDK 17**. No Spring AI 2.0 / Spring Boot 4.0 upgrades unless the project explicitly opens a JDK upgrade window.

**Core capabilities**

| Capability | Description |
|---|---|
| Dual-mode planner dialogue | CHAT free chat / CLARIFY structured clarification (option cards + progress bar, one-click task creation from the final draft); intent words trigger an in-dialogue confirmation popup, or type the `/planner` command (optionally with extra text) to switch explicitly. Optional **web search** — session-level switch, auto-searches every round in either mode (Bocha / Tavily / DeepSeek-native providers + direct URL fetch with SPA metadata fallback + collapsible verification bar showing query/sources/latency; failures degrade silently without blocking the dialogue) |
| One-click onboarding | After registration, an external AI receives an auto-generated skills brief; following it walks the AI through connect / check-in / pick-task end-to-end |
| Duty check-in | `checkIn` / `checkOut` duty lease (`ACTIVE` / `CLOSED` / `EXPIRED` state machine + auto expiration scan); on-duty Agents are dispatched first |
| Task perception | External Agents poll the inbox via `pullTasks` (recommended every ~30s) as the only perception channel; the doorbell SSE push channel is shelved (2026-08-07) and kept running for future reuse |
| MCP tool protocol | `pullTasks` / `claimSubTask` / `submitResult` / `reportBlocked` / `heartbeat` / `uploadArtifact` and others — tool count is whatever `tools/list` actually returns |
| Elastic scheduling | External-first + idle-first + LLM fallback; external Agents that fail consecutively beyond the threshold auto-fall-back to in-platform `API_KEY_LLM`; same-role replacement |
| Reliable delivery | Transactional Outbox (`PENDING` / `SENT` / `CONFIRMED` / `FAILED` four states) + publisher confirms + timeout-driven retry |
| Stability | Resilience4j per-agent circuit breaker, Reconcile health checks, execution-timeout compensation, three-layer idempotent consumption (DB CAS + Redis + consumption log) |

**Supported Agent types**

- `CLI_CLIENT` — external AI Agents (Qoder / Trae / Codex CLI / Claude Code and others; onboarded and verified)
- `API_KEY_LLM` — platform-hosted API-key Agents (auto execution chain)
- `WEB_BROWSER` — web-based AI (enum reserved, integration chain on the roadmap)

#### Software Architecture

**Tech stack**

| Layer | Technology | Version |
|---|---|---|
| Runtime | JDK | **17** (project red-line, permanently locked) |
| Backend framework | Spring Boot | **3.4.10** |
| AI protocol | Spring AI | **1.1.8** |
| Persistence | PostgreSQL + MyBatis-Plus + Flyway | — |
| Cache | Redis (Lettuce) | — |
| Message queue | RabbitMQ (with publisher confirms / DLX) | — |
| Resilience | Resilience4j CircuitBreaker | — |
| Observability | Spring Boot Actuator | — |
| Frontend | Vue 3 + TypeScript + Vite + Element Plus | — |

**Repository structure**

```
helloai/                          # Multi-module Maven project
├── helloai-common/               # Common utilities (constants, enums, exceptions, config props)
├── helloai-mq/                   # Message queue (RabbitMQ config + idempotent-consumer base)
├── helloai-core/                 # Core business (business-domain sub-packages)
│   └── com.helloai.core/
│       ├── agent/                #   Agent domain: register / schedule / execute / chat / MCP / doorbell observability
│       ├── task/                 #   Task domain: task / sub-task / review / score / state machine / timeline
│       ├── system/               #   System support domain: user / config / rule / credential / attachment
│       └── shared/               #   Cross-domain facilities: domain events / doorbell channel
├── helloai-api/                  # REST API layer (Controller + DTO; Mapper access forbidden)
├── helloai-job/                  # Scheduled jobs (Outbox relay / timeout compensation / health check / lease expiry)
├── helloai-start/                # Bootstrap (Application + application.yml + Flyway)
├── helloai-ui/                   # Frontend (Vue 3 SPA)
├── scripts/                      # Verification scripts (powershell/ + shell/)
└── doc/                          # Project docs (see doc/README.md doc map)
```

#### Installation

**Prerequisites**

- JDK 17
- Maven 3.8+
- Docker + Docker Compose (infrastructure)

**Steps**

```bash
# 1. Start infrastructure (PostgreSQL / Redis / RabbitMQ / MinIO)
docker compose up -d

# 2. Build + start backend (Flyway auto-runs V1~V23 migrations)
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
- Health check: <http://localhost:6565/actuator/health>

#### Usage

**External AI Agent quick onboarding**

1. In the admin console create an Agent (role `EXECUTOR`, type `CLI_CLIENT`) and copy the auto-generated skills brief.
2. Paste the skills brief into the external AI (e.g. Qoder / Trae); the AI will automatically complete: register & auth → MCP connect → `checkIn` → poll `pullTasks` for duty.
3. After the platform dispatches a task, the AI notices a new inbox message via `pullTasks` and follows the skills rules: `claimSubTask` → execute → `submitResult`.
4. Exception path: if execution is blocked, call `reportBlocked` (with evidence chain); if the platform times out without submission, it auto-compensates and re-dispatches to another on-duty Agent of the same role.

**Verification & regression scripts**

All verification scripts live in `scripts/powershell/` (Windows) and `scripts/shell/` (macOS); script output is the source of truth:

| Script | Coverage |
|---|---|
| `verify-mcp-auth.*` | MCP auth regression |
| `verify-mcp-e2e.*` | MCP end-to-end business loop |
| `verify-onboarding*.ps1` | External Agent five-step onboarding (register / check-in / pull / submit) |
| `verify-doorbell-e2e.ps1` | Doorbell long-lived connection (shelved 2026-08-07; code retained) |
| `verify-agenthub-duty-e2e.ps1` | Duty lease (checkIn / checkOut / expiry scan / STRICT exclusive) |
| `verify-poller-e2e.ps1` | DB Poller fallback consumption |

**MCP channel conventions**

- Primary channel: MCP SSE (`/mcp/sse` + `/mcp/messages`) is the only primary channel; REST `tools/list` / `tools/call` are kept for backward compatibility.
- Heartbeat refresh: `last_seen_time` / online-state refresh uses the `heartbeat` tool as the primary trigger.

**Documentation**

Start with [`doc/README.md`](doc/README.md) (the doc map: positioning and fact level of each document). Four sources of truth:

- Code style: [`doc/HelloAI_CODE_STYLE.md`](doc/HelloAI_CODE_STYLE.md) (V2.0 — must read before changing code)
- Project baseline: [`doc/HelloAI 项目基线文档.md`](doc/HelloAI%20项目基线文档.md)
- Implementation gap: [`doc/HelloAI 实现差距表.md`](doc/HelloAI%20实现差距表.md)
- Current progress: [`doc/项目进度.md`](doc/项目进度.md)

Also: EXECUTOR onboarding guide [`.executor-onboarding.md`](.executor-onboarding.md) / design system [`DESIGN.md`](DESIGN.md) / product definition [`PRODUCT.md`](PRODUCT.md).

#### Contributing

1. Fork this repository
2. Create a branch `feat_xxx` or `fix_xxx`
3. Read `doc/HelloAI_CODE_STYLE.md` before changing code; for scheduling / execution-chain changes also read `doc/design/HelloAI_调度解耦重构分析.md`
4. Before submitting, run the `scripts/` verification scripts relevant to your change and attach script output to the PR

#### Tips

1. Chinese: [`README.md`](README.md)
2. Always start from the source-of-truth docs (baseline / gap / progress); historical design docs are archived in `doc/archive/` and no longer serve as development references

#### License

Licensed under the [MIT License](https://opensource.org/licenses/MIT). See [LICENSE](LICENSE).