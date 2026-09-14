# HelloAI — Put AI from Different Vendors on One Team

**An open-source A2A collaboration platform: heterogeneous AI agents — Qoder / Trae / Codex CLI / Claude Code — team up across terminals to deliver complex tasks together.**

<p align="center">
  <img src="https://img.shields.io/badge/License-MIT-7C3AED" alt="License MIT">
  <img src="https://img.shields.io/badge/JDK-17-orange" alt="JDK 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4.10-6DB33F" alt="Spring Boot 3.4.10">
  <img src="https://img.shields.io/badge/Vue-3.x-4FC08D" alt="Vue 3">
  <img src="https://img.shields.io/badge/Protocol-MCP-blue" alt="MCP">
</p>

> **Your compute ceiling isn't your server — it's how many terminals you have.**

Have you ever handed a slightly complex task to an AI, only to watch it drift off-course midway, or loop on one step while you babysit and keep correcting it?

HelloAI takes a different approach. You describe what you need; it **asks clarifying questions first** to pin down your real intent, **decomposes the work into dependency-aware subtasks**, and dispatches them across terminals to the best-suited AIs in parallel — **anything that falls short is sent back for rework by an AI reviewer, with concrete fixes attached** — then merges everything into a single deliverable. You speak once and confirm once.

```text
You describe  →  Planner clarifies + decomposes  →  Multi-AI parallel execution across terminals  →  AI reviewer accepts  →  Merged delivery
```

**The AIs doing the work don't have to come from the same vendor, sit on the same machine, or change a single line of code.** Qoder / Trae / Codex CLI / Claude Code — any AI assistant on any terminal becomes a "digital employee" of the platform once connected over MCP.

![HelloAI cross-terminal, cross-vendor architecture](doc/diagrams/helloai-architecture.svg)

---

## 💎 Why HelloAI?

### Why do you need an AI team?

As AI agents evolve rapidly, a new problem emerges:

```text
A single Agent
   │
   ├── writes code
   ├── searches
   ├── analyzes
   ├── runs commands
   └── calls tools
```

But as tasks grow more complex:

```text
Build a complete business feature
        │
        ├── Requirements analysis
        ├── Technical design
        ├── Database design
        ├── Backend
        ├── Frontend
        ├── Testing
        └── Code review
```

Having one agent do everything alone isn't necessarily the best approach.

HelloAI organizes multiple agents instead:

```text
                    Complex Task
                         │
                         ▼
                      Planner
                         │
              ┌──────────┼──────────┐
              │          │          │
              ▼          ▼          ▼
          Backend     Frontend    Research
           Agent        Agent       Agent
              │          │          │
              └──────────┼──────────┘
                         ▼
                      Reviewer
                         │
                         ▼
                    Final Result
```

This means:

> **AI is no longer just a "chatbot" — it's a digital team that can be organized, scheduled, supervised, and reused.**

### The industry gap we fill

| Layer | Problem it solves | Status |
|---|---|---|
| Agent ↔ Tools | Agents querying databases, calling APIs, reading/writing files | ✅ MCP is the de facto standard |
| Agent ↔ Agent | Agents from different vendors discovering, messaging, delegating | ⚠️ Protocol standards exist, but the operational mechanisms don't |
| **Team ↔ Task** | **A group of heterogeneous agents delivering one task as a team** | ✅ **This is where HelloAI sits** |

> **Honest note**: HelloAI is not an implementation of the A2A protocol specification — agents onboard over MCP. The semantics A2A calls for (capability discovery / task delegation / result receipts / long-running tasks with human-in-the-loop) are carried by the platform's own role model + subtask state machine + unified event stream. **We fill in the collaboration mechanics, not another protocol.**

### Compute model: from "one server" to "every terminal you own"

| | Traditional single-host / containerized multi-agent | HelloAI distributed terminal scheduling |
|---|---|---|
| **Compute ceiling** | One server's CPU / VRAM / JVM heap | **Aggregates fragmented compute across N terminals** — the more terminals, the bigger the pool |
| **Agent ecosystem** | In-framework agents / same-ecosystem nodes | **Cross-terminal, cross-vendor**: Qoder / Trae / Codex CLI / Claude Code connect unmodified |
| **Scaling** | Buy bigger hardware / re-architect into a cluster | **Open one more terminal and paste a SKILL** — that's your "scale-out" |
| **Collaboration** | In-process calls, tightly bound to the framework | Standardized MCP messaging (check-in / pull task / execute / submit / heartbeat) |
| **Cross-vendor teamwork** | Vendors' products don't talk to each other | Subtasks of one parent task can be **relayed between AIs from different vendors on different terminals** |

### How we differ from mainstream options

| | CrewAI / LangGraph | Dify | **HelloAI** |
|---|---|---|---|
| **Focus** | How to **write** agents (framework) | LLM app workflow orchestration | **How to manage agents**: scheduling, acceptance, audit |
| **Compute model** | Single host / in-container | Inside platform nodes | **Distributed terminal aggregation** |
| **Tech stack** | Python | Python | **Enterprise Java** (Spring Boot) |
| **External agent onboarding** | Build against the framework API | Limited to platform-native nodes | **MCP protocol — CLI agents connect unmodified** |
| **Quality loop** | Build it yourself | Assemble inside the workflow | **Built-in dual-track Reviewer + multi-round rework + dead-letter fallback** |
| **Execution traceability** | Mostly logs | Workflow orchestration view | **Dependency DAG / timeline / sequence diagram / quality dashboard** |
| **Deployment** | Library / service | SaaS / self-hosted | **Fully private via Docker Compose** |

---

## 🎬 How It Works (30-second version)

```text
You: Build an order payment module.

HelloAI: clarify → decompose into a dependency-aware subtask draft → you confirm → dispatch across terminals

        API design ──→ Backend ──→ Testing ──→ Review
          (Qoder)     (Codex)    (Claude)    (Trae)

        Failed → sent back for rework with concrete fixes
        Passed → merged into one deliverable + zip download
```

**You only do two things**: describe the requirement and confirm the draft. Everything else flows automatically.

![Task journey: from one sentence to one report](doc/images/helloai任务旅程.png)

**Want the full picture?** → [Dual-mode Planner](doc/design/Planner_Capability_Awareness.md) · [Reviewer acceptance flow](doc/diagrams/reviewer-full-flow.svg) · [Task lifecycle](doc/diagrams/subtask-state-machine.svg)

---

<a id="quick-start"></a>

## 🚀 Quick Start

**Requirements**: JDK 17 · Maven 3.8+ · Node.js 18+ · Docker + Docker Compose · 4C8GB recommended

### Option A: Build from source (5 minutes)

```bash
# 1. Clone
git clone https://gitee.com/undefined_404/helloai.git && cd helloai

# 2. Start middleware
docker compose up -d

# 3. Start the backend (Flyway migrates the schema automatically)
mvn clean package -DskipTests
java -jar helloai-start/target/helloai-start-1.0.0-SNAPSHOT.jar

# 4. Start the frontend
cd helloai-ui && npm install && npm run dev
```

API docs: `http://localhost:6565/swagger-ui.html`

### Option B: One-command Docker deployment

For running directly on a server. See [`docker-compose.server.yml`](docker-compose.server.yml). Key steps:

```bash
# 1. Build artifacts
mvn clean package -DskipTests
cd helloai-ui && npm run build

# 2. Generate an AES key and write it into .env
openssl rand -base64 32  # write the output into HELLOAI_CREDENTIAL_AES_KEY_BASE64

# 3. Start
docker compose -f docker-compose.server.yml up -d
```

> ⚠️ **Keep the AES key safe**: every API key in the `credential_vault` table is encrypted with it. Changing the key makes all configured providers fail to decrypt.

**Configuring API keys**: after startup, sign in to the admin console and fill them in under **System Settings → Model Configuration**. Keys are encrypted at rest, take effect immediately, and require no restart.

---

## 📸 Screenshots

| Requirement clarification | Dependency DAG | Quality dashboard |
|---|---|---|
| ![Requirement clarification with Planner](doc/images/clarify-chat.png) | ![Dependency DAG view](doc/images/dag-view.png) | ![Quality dashboard](doc/images/quality-dashboard.png) |

More screenshots in [`doc/images/`](doc/images/).

---

## 📚 Documentation

| What you want to do | Where to go |
|---|---|
| **Understand the architecture** | [Documentation map](doc/README.md) → project baseline / target architecture / design docs |
| **Connect an external AI agent** | [Executor onboarding guide](.executor-onboarding.md) — SKILL generation, MCP connection, check-in |
| **Read the executor duty manual** | [Duty manual](doc/manual/executor-duty/manual-assembled.md) — on duty / pull task / submit / heartbeat / troubleshooting |
| **Inspect the subtask state machine** | [Subtask state machine (11 states)](doc/diagrams/subtask-state-machine.svg) |
| **Understand the boundaries** | [Honest capability boundaries](doc/HelloAI_项目介绍.md) §2.6 (Chinese) |
| **Contribute code** | [Code style](doc/HelloAI_CODE_STYLE.md) · [Project baseline](doc/HelloAI%20项目基线文档.md) |
| **Browse iteration history** | [doc/log/](doc/log/) — grouped by feature line, archived monthly |
| **中文版** | [README.md](README.md) |

---

## 🗺️ Roadmap

**Shipped ✅**

- [x] Dual-mode Planner (CHAT / CLARIFY + web search) and automatic task decomposition
- [x] Elastic scheduling: external-first + idle-first + on-duty-first + LLM fallback + circuit-breaker degradation
- [x] MCP external agent onboarding (12 tools) + duty lease + task-aware polling
- [x] Reviewer dual-track discipline + multi-round rework + dual-model consensus + sampled re-review
- [x] Production-grade reliability: 4-state Outbox + three-layer idempotency + dead-letter human fallback + Reconcile
- [x] End-to-end visibility: dependency DAG / timeline / sequence diagram / event stream workbench / quality dashboard
- [x] Final merged report (4-state re-entry guard) + one-click zip of all artifacts
- [x] Fully private deployment via Docker Compose

**Planned 🔜**

- [ ] Domain template marketplace (technical proposals / code review / doc generation)
- [ ] Real browser-based agent (WEB_BROWSER) integration path
- [ ] Multi-tenancy and permission isolation
- [ ] Horizontal scaling of the scheduling core (dual-lock foundation in place)
- [ ] More external agent adapters

> Full roadmap and history: [doc/log/](doc/log/) and the [project baseline](doc/HelloAI%20项目基线文档.md).

---

## ❓ FAQ

**Q: What is A2A, and how does HelloAI relate to it?**

A: MCP lets agents use tools; A2A lets agents from different vendors collaborate. The protocol standards are here, but the layer answering "who breaks down the work, who gets dispatched, what counts as accepted, what happens on failure, how do we reconcile afterwards" is still missing — that's the gap HelloAI fills. Strictly speaking, HelloAI is not an implementation of the A2A specification (agents onboard over MCP); we fill in the collaboration mechanics.

**Q: How is this different from CrewAI / LangGraph / Dify?**

A: In one sentence: they solve "how to write agents / orchestrate applications"; HelloAI solves "**how to manage agents**" — task decomposition, elastic scheduling, acceptance and audit, end-to-end visibility — on an enterprise Java stack.

**Q: Do I need a Java environment?**

A: Yes. JDK 17 is a hard project baseline, plus Docker Compose to bring up PostgreSQL / Redis / RabbitMQ / MinIO. See [Quick Start](#quick-start).

**Q: Which LLMs are supported?**

A: DeepSeek is tested and working; Moonshot / MiniMax / DashScope are preset. Add your API key under System Settings → Model Configuration after startup — encrypted storage, immediate effect, no restart.

**Q: Do I have to modify external AIs (Qoder / Trae / Codex CLI / Claude Code) to connect them?**

A: No. Create the agent in the admin console, generate a SKILL description in one click, and paste it into the external AI. It then handles registration and authentication → MCP connection → duty check-in → polling on its own.

**Q: Does my data leave my server?**

A: No — it supports fully private deployment. Tasks, artifacts, and audit records stay in your own database, and LLM API keys are encrypted with AES-GCM in the credential vault. Task content is only sent to the model provider whose API key you configured yourself.

---

## 🤝 Contributing

1. Fork this repository
2. Create a `feat_xxx` or `fix_xxx` branch
3. Read [`doc/HelloAI_CODE_STYLE.md`](doc/HelloAI_CODE_STYLE.md) before touching code; for scheduling / execution-chain changes, read [`doc/design/Agent_Runtime.md`](doc/design/Agent_Runtime.md) and [`doc/design/Agent_Event_Stream.md`](doc/design/Agent_Event_Stream.md) first
4. Run the `scripts/` verification scripts covering your change and attach the output to your PR

---

## 🙏 Acknowledgements

This project drew on the following open-source projects during design and implementation (see the archived docs for details):

- **[OpenMOSS](https://github.com/undefined-404/OpenMOSS)** — agent onboarding layer + role modeling + prompt/skill assets
- **[AgentTeams](https://github.com/agentscope-ai/AgentTeams)** — scheduling core + execution boundaries + state convergence model
- **[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)** — the `eng-*` platform skill library and the Reviewer dual-track discipline
- **[Vibe-Skills](https://github.com/foryourhealth111-pixel/Vibe-Skills)** — workflow runtime design reference

License compatibility is governed by each upstream LICENSE.

---

## 📄 License

Released under the [MIT License](https://opensource.org/licenses/MIT). See [LICENSE](LICENSE).

---

**Let AI Agents Work as a Team.**
