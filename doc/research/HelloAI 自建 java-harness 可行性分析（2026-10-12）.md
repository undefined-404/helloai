# HelloAI 自建 java-harness 可行性分析（2026-10-12）

> **Status: `Analysis`**（只读调研，不改变任何实现、不承诺排期）。
> **性质**：只读调研 + 选型可行性分析（不改业务代码）。
> **缘起**：用户问「能否照 `E:/workspace/deepseek-harness-master` 与 `E:/workspace/octop-harness-main` 写一套可独立运行的 Java harness（java-harness）？」
> **关联**：`doc/research/Agent 运行时开源选型参考（2026-10-12）.md`、`doc/review/HelloAI 内部 LLM 执行链与开源 agent 架构比对（2026-10-12）.md`
> **证据口径**：两个开源库为**本地实际代码实测**（带 `path:line`）；HelloAI 为本仓实测。工作量估算为区间，标「估」。

---

## §0 三句话结论

1. **两个库都不是「自包含的循环」**——DSH 站在 **Cordis**（自研插件元框架）之上；octop-harness **根本不实现循环**，它站在 `deepagents + LangGraph + langchain` 之上，自己只写装配层。**所以「照它们写一套」到底要写多少，取决于你照哪一层。**
2. **从零自建一套 Java harness ≈ 45k–50k 行 Java / 约 10–24 人月**（估），且要自研 Cordis 等价物 + deepagents 等价物 + 原生沙箱（Windows ACL / Landlock）——**这是「造框架」量级，不建议**。
3. **你不需要从零**：HelloAI 已具备 DSH「最小内核」13 项中的 **6 项完整 + 4 项部分**，缺的 4 项（fs / 工作区 / 技能惰性加载 / 有效工具面）恰是此前已定位的 D1~D3。**正确动作 = 按 DSH 的设计「补那半套」，而非另起炉灶造 java-harness。**

---

## §1 实测规模与「自有 vs 依赖」边界（关键）

### 1.1 DeepSeek Harness（TS monorepo）

- 全仓 **4876 个 `.ts`**（排除 node_modules），量级约 40 万行（估）。
- 关键目录实测行数：

| 目录 | 文件数 | 行数 |
|---|---:|---:|
| `packages/core`（含 core/tools） | 115 | **47,135** |
| `packages/core/tools`（工具注册表 + 执行管线） | 21 | **14,392** |
| `packages/session`（日志/投影/持久化/格式迁移） | 207 | **14,104** |
| `packages/fs` | 52 | **14,321** |
| `packages/shell` | 53 | **16,036** |
| `packages/context` | 37 | **13,558** |
| `packages/sandbox` | 50 | **10,513** |
| `packages/compaction` | 32 | **8,810** |
| `packages/skill`（skill / skill-filesystem / tool-skill / skill-office） | 13 | **7,411** |
| `vendor/cordis`（内核） | 9 | **2,696** |

- **自有 vs 依赖**：DSH **几乎全自有**——循环（`packages/core/agent-loop/src/agent.ts` 705 行，`ReactLoopAgent`）、工具注册表与五段执行管线（`packages/core/tools/src/index.ts` 1991 行）、会话 append-only 日志（`packages/core/session/src/index.ts` 1452 行）、技能三件套、fs/shell/sandbox provider、压缩引擎，**全部是它自己的代码**。唯一「借」的是 `vendor/` 里几个通用库（cosmokit / schemastery / loader）。
- **结论**：DSH 是一个**完整的框架产品**，不是「套壳」。

### 1.2 octop-harness（Python）

- `src/octop_harness/` = **204 个 `.py` / 40,630 行**；去掉 `builtin/` 后核心 **32,988 行**。
- 关键模块：

| 模块 | 文件数 | 行数 | 性质 |
|---|---:|---:|---|
| `agent.py`（`HarnessAgent` 门面） | 1 | 1,958 | 自有 |
| `config/`（`HarnessAgentConfig`） | 2 | 1,498 | 自有 |
| `middleware/`（24 个中间件） | 25 | 5,032 | 自有 |
| `backends/`（`BackendWorkspace` + 12 后端） | 17 | 6,858 | 自有 |
| `builtin/`（工具 + 技能资源） | 42 | 7,642 | 自有（含 4,356 行技能脚本） |
| `llm/factory.py`（`ChatModelFactory`） | 5 | 1,145 | 自有 |
| `memory/`（记忆**编排**） | 4 | 798 | 自有 |

- **★自有 vs 依赖（本报告最关键的一张表）**：

| 能力 | octop-harness 自己写？ | 实际来源（实测） |
|---|---|---|
| **agent 主循环 / 工具调度 / 状态机** | **❌ 没写** | `deepagents.create_deep_agent`（`agent.py:1744,1762`）＋ LangGraph |
| 中间件**基类与钩子分发** | ❌ | `langchain.agents.middleware.AgentMiddleware`（`middleware/*.py` 全部继承） |
| 工具**基类/装饰器** | ❌ | `langchain_core.tools` |
| **checkpoint 持久化** | ❌ | `langgraph.checkpoint.sqlite.aio.AsyncSqliteSaver`（`agent.py:57-63`） |
| 技能 / 记忆的**加载与 prompt 注入** | ❌ | deepagents（`agent.py:1721-1724`） |
| **L0→L3 记忆分层蒸馏算法** | ❌ | 外部包 `octop-memory`（`memory/runtime.py:16`） |
| 浏览器驱动 | ❌ | 外部包 `octop-browser` |
| MCP 客户端适配 | ❌ | `langchain-mcp-adapters` |
| 配置 / 装配 / 中间件 / 后端抽象 / 内置工具 / 技能目录 / 记忆编排 / 多 agent 注册 | **✅ 自己写** | `config/`、`agent.py`、`middleware/`、`backends/`、`builtin/`、`skills/`、`memory/`、`manager.py` |

- **结论**：octop-harness 是**薄装配层**（自我声明 "intentionally a *thin* facade", `agent.py:89-93`）。它的「成熟」有一半功劳是 **deepagents + LangGraph** 的。

---

## §2 「java-harness」到底指哪一层？

两个库揭示了 agent 运行时的**三层栈**：

```
┌─────────────────────────────────────────────────────────┐
│ L3 装配层  配置 / 中间件链 / 后端抽象 / 工具与技能目录 / 多agent  │  ← octop-harness 主要在这层
├─────────────────────────────────────────────────────────┤
│ L2 循环层  模型↔工具 driver / turn·step / 工具调度 / 会话日志    │  ← deepagents+LangGraph（octop 借）· DSH 自研
├─────────────────────────────────────────────────────────┤
│ L1 内核层  DI / 插件 / 事件总线 / 生命周期 / 作用域             │  ← Cordis（DSH 自研）· octop 无（用 langchain 组件拼）
└─────────────────────────────────────────────────────────┘
              ↑ 换个说法：octop-harness 只做了 L3；DSH 做了 L1+L2+L3 全套
```

**所以「写一套 java-harness」有三种截然不同的含义：**

| 含义 | 要写什么 | 规模（估） |
|---|---|---|
| **A. 照 DSH 写全套** | L1 内核 + L2 循环 + L3 装配 | **45k–50k 行 Java / 10–24 人月** |
| **B. 照 octop-harness 写装配层** | 只写 L3，但**仍须自研 L2 的 Java 等价物**（Java 无 deepagents） | **25k–35k 行 Java / 8–15 人月** |
| **C. 只补自己缺的那半套** | 在既有 Spring AI + 自研循环上补 D1~D6 | **6k–12k 行 Java / 2–4 人月**（估） |

**用户口语里的「照 DSH 写 java-harness」= 含义 A**，即上面第一行。

---

## §3 Java 等价物盘点：哪些有现成、哪些必须自研

| 层 | 能力 | Java 现成？ | 说明 |
|---|---|---|---|
| L1 | DI / 插件 / 生命周期 | 🟡 **部分** | Spring 容器可替 80%（`@Component`/`@Service` + `ApplicationContext`）；但 **Cordis 的「插件热插拔 + 服务变化自动卸载重载 + effect 可逆副作用」Spring 给不了** |
| L1 | 事件总线（含 `waterfall` 拦截链） | 🟡 | Spring `ApplicationEventPublisher` 是 `emit` 语义；**`waterfall`（监听器必须 `next()` 才委托）须自研** |
| L2 | **模型↔工具循环** | ✅ | **LangGraph4j**（`StateGraph` + 循环边 + `CheckpointSaver`）/ LangChain4j `-agentic`；HelloAI 也有自研 `ChatModelToolLoop` |
| L2 | 工具注册表 + 执行管线 | ✅/🟡 | Spring AI `@Tool`/`ToolCallback` 有工具面；**五段管线（pre-execute→guard→execute→post-execute→result）+ 并行调度按模型序提交**须自研（DSH `tools/src/index.ts:466-1701` 那套） |
| L2 | 会话 append-only 日志 + projection + fork/replay | 🟡 | **无现成等价物**；LangGraph4j 有 checkpoint，但「模型可见 ⟺ 已记录」的单一事件源须自研 |
| L2 | 上下文装配 + 压缩（LLM 摘要 / tool-result 剪枝 / 图片 offload） | 🟡 | 装配可自建；**压缩策略无现成 Java 件** |
| L3 | 中间件 / 拦截链 | ✅ | **Spring AI Advisors API**（HelloAI 已引入 Spring AI，直接可用） |
| L3 | 工作区后端抽象（本地/S3/PG/容器同构） | ❌ | **无现成 Java 等价物**；须照 octop `BackendWorkspace`（1311 行）自研 |
| L3 | 技能发现 + **惰性加载** | ❌ | **无现成 Java 件**；须照 DSH `tool-skill`（全量目录 + `skill({name})`）自研 |
| L3 | 记忆分层蒸馏（L0→L3） | ❌ | 无现成 Java 件；且 octop 侧该算法也在外部包 |
| —— | **原生沙箱**（Windows ACL / Linux Landlock / macOS Seatbelt） | ❌ | DSH 的 `packages/sandbox/sandbox-windows-acl`（10 文件）+ `native/system` 启动器，**Java 侧需 JNI/FFI 自研**，是硬骨头 |

**小结**：**L2 有现成（LangGraph4j / Spring AI）**；**L1 内核与 L3 的「工作区 / 技能惰性加载」基本无 Java 现成件**；**原生沙箱是跨语言都难的部分**。

---

## §4 从零自建（含义 A）的工作量估算

按 DSH 最小内核（`packages/bundle/sdk-minimal/cordis.patch.yml` 定义）逐模块折算 Java：

| 模块 | DSH 源码量 | Java 折算（估） | 人月（估） | 难度 |
|---|---:|---:|---:|---|
| 内核（Cordis 等价：Service/Plugin/Context/Event/Fiber/effect） | 2,696 | 3,000–5,000 | 1–2 | 🔴 TS Proxy/动态模块语义难移植 |
| agent-loop（driver + inbox + 工具调用调度 + 事件） | ~2,472 | ~3,000 | 1 | 🟡 |
| tools 注册表 + 五段管线 + schema 编译 | 14,392 | ~7,000 | 1.5–2 | 🟡 |
| session 日志 + projection + persistence + 格式迁移 | 14,104 | ~10,000 | 2 | 🔴 |
| system-prompt 装配 | 688 | ~1,000 | 0.5 | 🟢 |
| skill 三件套（注册/发现/惰性加载） | 7,411 | ~5,000 | 1 | 🟡 |
| fs / shell / sandbox provider（先只做 local） | 40,870 | ~6,000 | 1.5 | 🔴 原生沙箱另计 |
| context + compaction | 22,368 | ~8,000 | 1.5 | 🟡 |
| LLM adapter（Spring AI 复用可省大半） | — | ~1,000 | 0.5 | 🟢 |
| **合计** | **~105k** | **≈ 45,000–50,000 行 Java** | **10–14** | —— |

若还要求「照 octop-harness 那套装配能力」（含义 B，含多后端 + 24 中间件 + 记忆编排），再加 **3–5 人月**。若还要求**原生沙箱三平台**，再加 **3–6 人月**（含各平台原生调试）。

> **⚠️ 这不是「一个功能」，而是「一个框架产品」**——DSH 背后是 DeepSeek 的专职团队，octop-harness 背后站着 LangChain 生态。**从零自建等于开一条长期维护线**（版本、迁移、安全补丁全部自担）。

---

## §5 HelloAI 已有资产盘点：你**不是**从零

这是本报告最重要的判断依据。按 DSH 最小内核逐项对齐本仓实测：

| # | DSH 最小内核项 | HelloAI 现状 | 判定 | 实证 |
|---|---|---|---|---|
| 1 | cordis(+loader) | Spring DI（agent 域内 88 个类用 `@Component/@Service`） | 🟡 功能等价（缺热插拔/effect） | `helloai-core/.../agent/**` |
| 2 | llm(+adapter) | `buildChatModel` + provider 工厂（DeepSeek/Anthropic 协议） | ✅ | `chat/provider/*Factory.java` |
| 3 | session(+jsonl+projection) | `AgentSession` + `AgentEventRecorder` + ADR-001 Run/Turn/Step | 🟡 有模型，**缺 append-only 单源 + projection/fork** | `agent/session/entity/AgentSession.java`、`agent/event/AgentEventRecorder.java` |
| 4 | system-prompt | `AgentRuntimeContextAssembler` | 🟡 **`systemPrompt("")` 硬编码**（`:213`） | 同左 |
| 5 | tools | `ToolRegistry` + `ToolContext`（条件可用 + 动态描述语义位） | ✅ **比 DSH 更声明式** | `agent/tool/ToolRegistry.java`、`ToolRegistryImpl.java` |
| 6 | agent 注册 | `Agent` 实体 + `AgentService` | ✅ | `agent/entity/Agent.java` |
| 7 | **agent-loop** | `ChatModelToolLoop` + `AgentLoop`/`Input`/`Result` + `LoopCheckpoint` | ✅ 骨架完整（**仅 maxIter=5、覆盖面 1/16**） | `agent/runtime/loop/**` |
| 8 | **fs-local** | **无** | ❌ | 检索为空 |
| 9 | **working-directory** | **无**（内部 LLM 无工作区概念） | ❌ | 检索为空 |
| 10 | skill + skill-filesystem + tool-skill | `SkillPackageCatalog`/`Scanner`/`Parser`/`FrontMatter`/`Stamper`/`ZipGate` + `AgentSkillSpecService` | 🟡 **发现/安装齐、惰性加载缺** | `agent/skill/**` |
| 11 | 至少一个工具 | `web_search` / `search_knowledge`（仅经技能 `requiredTools`） | 🟡 **有效工具面≈空** | `AgentMcpServerServiceImpl.java:267-275` |
| 12 | （可选）compaction | **无** | ❌ | 检索为空 |
| 13 | （可选）sandbox | 仅接口 + `EnvironmentSandboxProvider`（无 exec 实现） | 🟡 空壳 | `agent/runtime/sandbox/**` |

**统计**：**完整 ✅ 4 项 / 部分 🟡 6 项 / 缺失 ❌ 3 项**（外加 compaction 可选缺）。

> **一句话**：HelloAI **已经有 java-harness 的骨架**（八件套 + loop + ToolRegistry + ADR-001 + Spring AI）。你缺的不是「一套 harness」，是**三块具体的肉**：**① 有效工具面（D1）、② 手脚 / fs / 工作区（D2）、③ 技能惰性加载（D3）**。

---

## §6 三档方案与推荐

### 方案 A：照 DSH 从零写全套 java-harness —— ❌ **不建议**

- 代价：45k–50k 行 Java、10–24 人月、长期维护一条框架线。
- 收益：**与现有资产重复**——`ChatModelToolLoop`、`ToolRegistry`、ADR-001、Spring AI 全部要推倒重来或双轨并存。
- 且必然踩坑：TS 的 Proxy Context / 动态模块类型合并 / generator 式 effect 协议，在 Java 里要么妥协要么大改设计。

### 方案 B（**推荐**）：按 DSH 的设计「补半套」—— 增量补齐

保留现有 Spring AI + `ChatModelToolLoop` + ToolRegistry + ADR-001，**只按 DSH 的思路补缺口**：

| 动作 | 借鉴来源（具体） | 补的缺口 | 规模（估） |
|---|---|---|---|
| **① 打开内部 LLM 的工具面**（非 MCP 会话通道的工具集） | DSH 工具按 scope 分层可见（`tools` `view(scope)`）；HelloAI 复用 `ToolRegistry` 语义位 | **D1** | 1–2 周 |
| **② 补手脚**：读原始附件 / 文件读写 / 正式交付动作 | DSH `packages/fs`（`FileSystem` 抽象 `fs/src/index.ts:87`）+ octop `BackendWorkspace`（`workspace.py:356`）的**接口形状** | **D2** | 3–4 周 |
| **③ 技能改「全量目录 + 惰性加载」** | DSH `tool-skill`：`agent/pre-step` 注入 `<available_skills>` 目录（`tool-skill/src/index.ts:215-253`）+ `skill({name})` 工具（`:82,128`） | **D3** | 2–3 周 |
| **④ 建拦截链**（可选，先行） | Spring AI **Advisors API**（现成）；对照 DSH `waterfall` / octop 24 中间件排序 | D4 | 1 周 |
| **⑤ 循环预算 / 覆盖面** | `maxIterations` 提为按任务分档；评估把 9 个业务 LLM 面中该带工具的接回循环 | D5 + 覆盖面 | 2–3 周 |
| **⑥ 上下文压缩**（后置） | DSH `compaction-tool-result-pruner`（模型无关、replay-safe） | D6 变体 | 2 周 |

**合计 ≈ 6k–12k 行 Java / 2–4 人月（估）**，且**零推倒**、每步独立可验、可随时停。

### 方案 C：折中——引入 LangGraph4j 只补「图层」

仅当确实需要**显式图 / 断点续跑 / HITL 关卡**（即第 7 层 Graph 缺口）时，引入 **LangGraph4j** 作为编排层。
- 代价：与 ADR-001 Run/Turn/Step + `ChatModelToolLoop` **重叠**，须先做「收编 vs 共存」取舍，**不可叠两层循环**。
- 时机：**方案 B 完成后**再评估，不要现在做。

### 推荐路线

```
现在  → 方案 B（补 D1/D2/D3/D4，2–4 人月）       ← 目标：内部 LLM 真正变成 agent
之后  → 方案 C（LangGraph4j，仅按需补图层）
永远不 → 方案 A（从零造 java-harness）
```

**判据**：你现有资产的**复用率已经很高**——重写等于把这部分投资当沉没成本扔掉，且换不来能力（D1/D2/D3 换任何框架都还得自己配）。

---

## §7 风险与边界

1. **「写 java-harness」的最诱人错觉**：以为换个 harness 内部 LLM 就会变强。**不会**——DSH/octop 的强来自「工具面 + 手脚 + 技能装配」三件套齐全，**这三件无论用谁的框架都要自己配**。
2. **框架 ≠ 隔离保证**：DSH 官方明确 sandbox ≠ 隔离保证；自建 sandbox 尤其危险（fail-closed 必须，`sandbox/src/index.ts:153-158`）。
3. **许可**：DSH / octop-harness 均 **MIT**——**「借鉴设计」完全合法**；但「照抄代码」需保留版权声明，且 TS/Python → Java 是**重写**不是移植。
4. **版本风险**：DSH 是 developer preview（官方明示破坏性变更），**不要把它的内部 API 当稳定契约来仿**——只借鉴**架构意图**（如「模型可见 ⟺ 已记录」「全量目录 + 惰性加载」），不追它的具体类名。
5. **测试成本未计入**：上表人月**不含**测试与联调；DSH 的 `packages/*/tests` 体量与 src 相当。

---

## §8 证据索引

**DSH（本地实测）**
- 内核：`vendor/cordis/src/{service,plugin,context,events,registry,fiber}.ts`（2,696 行）
- 循环：`packages/core/agent-loop/src/agent.ts:42-51,252-265,298-398,400-561`（705 行）
- 工具：`packages/core/tools/src/index.ts:466,1069,1103,1142,1184,1375,1499,1607,1647,1675`（1991 行）
- 技能：`packages/skill/tool-skill/src/index.ts:82,128,215-253,256,281`；`skill/src/index.ts:247-269,356`；`skill-filesystem/src/index.ts:149,730,804,924`
- 会话：`packages/core/session/src/{index.ts:522,798,984},{types.ts:281-428,439-478},{fork.ts:21-30}`；`session-persistence/src/index.ts:137`；`session-checkpoint-policy/src/index.ts:63-79`
- 沙箱/fs/shell：`sandbox/sandbox/src/index.ts:159,177,153-158`；`fs/fs/src/index.ts:87,132,205,252,266`；`shell/shell/src/index.ts:64,84,93`；`sandbox/sandbox-windows-acl/src/*`
- 压缩：`compaction/compaction/src/index.ts:32,119`；`compaction-basic/src/index.ts:113,148,190`
- 最小内核定义：`packages/bundle/sdk-minimal/cordis.patch.yml`

**octop-harness（本地实测）**
- 门面：`src/octop_harness/agent.py:89-93,145-202,1240-1260,1341-1370,1422-1564,1663-1767,1744,1762,1788-1847`
- 配置：`config/__init__.py:548-826,832-910`
- 中间件：`middleware/*.py`（25 个）；执行序 `agent.py:1422-1564`
- 后端：`backends/workspace.py:356,708,718,748,806,1180,1234`；`backends/__init__.py:58-72,85,342`
- 记忆：`middleware/memory.py:303-366,458-484,1113`；`memory/runtime.py:16,48,218-221`
- 技能：`skills/catalog.py:143,177,46`；`builtin/_sync.py:21,36`
- 工具：`builtin/tools/__init__.py:5-15`；装配 `agent.py:1341-1370`

**HelloAI（本仓实测）**
`agent/tool/{ToolRegistry,ToolContext}.java` · `agent/tool/impl/ToolRegistryImpl.java` · `agent/runtime/loop/**` · `agent/runtime/loop/AgentLoopInput.java:47` · `agent/service/AgentRuntimeContextAssembler.java:213` · `agent/service/impl/AgentMcpServerServiceImpl.java:267-275` · `agent/skill/**` · `agent/session/entity/AgentSession.java` · `agent/event/AgentEventRecorder.java` · `agent/runtime/sandbox/**` · `agent/chat/provider/*Factory.java`

---

## §9 局限说明

1. 人月/行数为**区间估算**（标「估」），依赖团队熟练度与是否计入测试；落地须先做**最细粒度的 spike** 校准。
2. 两个开源库的版本：DSH `0.1.0-rc.8`（本地 `package.json`）；octop-harness `1.0.2`（`pyproject.toml:5`）——**版本迭代快，引用具体行号时以当前 checkout 为准**。
3. 本报告为**可行性分析**，不含实现；任何改造仍须按规约走「设计 → 差距表登记 → 实现 → 门禁」。
