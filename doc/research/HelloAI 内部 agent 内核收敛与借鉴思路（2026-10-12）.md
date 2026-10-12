# HelloAI 内部 agent 内核收敛与借鉴思路（2026-10-12）

> **Status: `Analysis`**（只读调研，不改变任何实现、不承诺排期）。
> **性质**：只读调研 · 调研后**架构调整思路**（非设计文档、非实施计划，Status=`Analysis`）。
> **2026-10-12 追加**：§8「后续专项：内核 / 对接协议层 可移植 jar 化（长期愿景）」——用户提出的对外可移植方向，登记为**后续专项、不排期**。
> **缘起**：承 `doc/research/HelloAI 自建 java-harness 可行性分析（2026-10-12）.md`——那篇回答了「要不要从零写一套 java-harness」（结论：不；按 DSH 思路补半套，2–4 人月）。本篇回答**剩下的那半个问题**：**「补半套」具体怎么补、把散在 helloai-core 的执行碎块怎么收敛成一个内部 agent 内核、以及每一步分别借鉴开源里哪一处的哪一段思路。**
> **关联**：`doc/review/HelloAI 内部 LLM 执行链与开源 agent 架构比对（2026-10-12）.md`（差距 D1–D6 事实源）· `doc/research/Agent 运行时开源选型参考（2026-10-12）.md` · `doc/research/HelloAI 自建 java-harness 可行性分析（2026-10-12）.md`（本篇的可行性依据）。
> **证据口径**：开源库为本地实际代码实测（带 `path:line`，DSH=`E:/workspace/deepseek-harness-master`、octop=`E:/workspace/octop-harness-main`）；HelloAI 为本仓实测。为「思路」，不含排期进度——如需推进，另立 `plan/` 或 `design/`。

---

## §0 三句话结论

1. **你要的不是"再写一套 harness"，而是"把已经写出来的 harness 从平台业务里剥出来、让它自洽"。** 散在 helloai-core 的 `ChatModelToolLoop` + `ToolRegistry` + `SkillPackage*` + `AgentEventRecorder` 已经是一个 DSH 内核雏形，缺的是**让它们成为一个可独立运行内核的组织方式 + 三块肉**（工具面 / 手脚 / 技能惰性加载）。
2. **收敛的关键动作 = 把「循环 + 会话日志 + 工具注入 + 技能目录」四者从「平台 MCP 会话耦合」中解出来**，改成 DSH 式的「`modelInvocable` 可见性 + `skill({name})` 惰性加载 + append-only 会话单源」。这四件事**都改在装配层，不重写循环**。
3. **借鉴绝不等于抄框架**：DSH 值得借鉴的是「铁律」与「目录注入」这两条**架构意图**，不是它的 Cordis / 具体类名；octop-harness 值得借鉴的是「装配一次 + 逐 turn 再装配 + 中间件排序」这一条**装配心法**，不是它的 deepagents + LangGraph。

---

## §1 借鉴参考思路：三个开源里，分别抄哪一段

> 先立原则：**只借鉴「架构意图」，不追类名**。DSH 是 developer preview（官方明示破坏性变更，见 `README.md`），octop-harness 站在 LangChain 生态；把它们的内部 API 当稳定契约仿，等于固定两套快速漂移的参考系。**借的是「为什么这么设计」，不是「类叫什么」。（可行性分析 §7.3 §7.4）**

### 1.1 从 DSH 借三条「架构意图」

| # | 借什么（意图） | DSH 落地（实测证据） | 落到 HelloAI 哪里 |
|---|---|---|---|
| **① 模型可见 ⟺ 已记录（session 单一事件源）** | append-only 会话日志是唯一事实源，只有已 commit 的事件才进 `surface` 投影喂给模型（`packages/core/session/src/index.ts:876` 注释「append-only log remains the source of truth」、`surface.ts:7`）。这是 DSH「从一个聊天框架变成 harness」的**灵魂** | 把 ADR-001 的 Run/Turn/Step + `AgentSession` + `AgentEventRecorder` 收敛成**一条 append-only 事件单源 + 投影面**（见 §3 收敛方案 ①） |
| **② 技能 = 全量目录 + `skill({name})` 惰性加载** | 不是按任务挑技能，而是在 `agent/pre-step` 把**全量可调用技能的目录**（`name`+封顶 `description`，不含正文）注入为持久 user-role；正文由模型调 `skill({name})` 工具**惰性加载**（`packages/skill/tool-skill/src/index.ts:215-251,319-321`；`:81-160`）。`skill-filesystem` 仅做 SKILL.md 发现（`:149,730`）。**这叫 progressive disclosure** | 把现「任务侧 `requiredSkills` 挑选 + 速览注入」改为「全量目录注入 + skill 惰性加载」——**这是 D3**（见 §3 收敛方案 ②） |
| **③ 能力 = 工具面 + 手脚（fs/shell provider）** | 技能只是说明书，**能力来自 `ctx.tools` + `ctx.fs`/`ctx.shell` provider**；把 provider 指向远程沙箱，Bash/PTY/LSP 一并搬过去（`docs/architecture.zh.md:106`）。工具按 `scope` 分层可见（`packages/core/tools/src/index.ts` `view(scope)`） | 给内部 LLM 解封工具面（D1）+ 补工作区/读原附件（D2）——**手脚，见 §3 收敛方案 ③** |

### 1.2 从 octop-harness 借一条「装配心法」（+ 一个可后置项）

| # | 借什么（意图） | octop 落地（实测证据） | 落到 HelloAI 哪里 |
|---|---|---|---|
| **④ 装配一次 + 逐 turn 再装配 + 变更重编** | agent 启动时只用 `_build_harness_config` **一次性**把 模型/tools/skills/system_prompt/memory/mcp/middleware 拼成 `HarnessAgentConfig` 交给 harness（`agent.py` / `agents/manager.py:3068-3429`）；随后**每个 turn** 经 `_prepare_stream_request`/`_resolve_turn_mcp_servers` 动态再装配；bootstrap 完成或配置变更后经 `_init_graph` **重编图而非重启**（`manager.py:2695,2707`）。技能仅改 `skills_dir` 并触发重编，不整体重建（`manager.py:2145-2175`） | 把 HelloAI 的 `AgentRuntimeContextAssembler` 收敛成「一次装配出内核契约 + 逐 turn 微调可见集」；技能热更新走重新 resolve 而非重建（见 §3 收敛方案 ④） |
| ⑤（可后置）**中间件排序 = 唯一策略切面** | 24 个中间件按「外→内」显式排序（插件链/TokenQuota/Reasoning/…/OctopUiOffload 最内层，`agents/manager.py:3226-3243`；基类来自 `langchain.agents.middleware.AgentMiddleware`）——**是 DSH `waterfall` 的对等物** | 用 **Spring AI Advisors API**（HelloAI 已引入 Spring AI）承接，作为将来策略的统一挂点（见 §3 收敛方案 ⑤） |

### 1.3 三家共识抽出的「成熟 agent 骨架」（本篇收敛以它为准绳）

```
① 循环  = 有界的「模型调用 ↔ 工具调用」driver；turn/step 两层；终止条件显式
② 扩展  = 可拦截的中间件 / waterfall 链（唯一策略切面）
③ 技能  = markdown 说明书（frontmatter），从文件系统/注册表发现，注入为「目录」而非全文
④ 能力  = 工具面 + 文件系统/进程后端 —— 技能只有配上「手脚」才有产出
⑤ 装配  = 统一装配器产出「上下文 + 工具 + 技能目录」，可热更新
⑥ 产出  = 落到工作区的文件 / 对象存储，再由平台物化成交付物
```

### 1.4 明确不抄（避免垮掉的清单）

| 不抄 | 原因 |
|---|---|
| **Cordis 插件元框架**（`vendor/cordis`，TS Proxy 动态模块/effect 可逆副作用） | Spring DI 已覆盖 80%；热插拔/effect 用不上。用 Spring 容器 + **自修 ~50 行 waterfall 责任链**复刻拦截语义即可（可行性分析 §3 L1 行） |
| **deepagents + LangGraph 图**（octop 借的） | HelloAI 不需要显式 StateGraph；自研 `ChatModelToolLoop` 已是同一语义。**别上 LangGraph4j**（除非以后要多 agent 子图/HITL，见 §4） |
| **原生沙箱三平台**（Windows ACL / Landlock / Seatbelt，DSH `packages/sandbox/*`） | Java 需 JNI/FFI，跨语言都难；用 docker/OCI 或外包 `bwrap` 进程。**fail-closed 原则必须**（`sandbox/src/index.ts:153-158`） |
| **PTY / LSP / web / desktop / browser-use / computer-use / win32 koffi-FFI** | 全 Node 专属/dev-tooling，对 HelloAI 内核零价值 |
| **octop 记忆分层蒸馏（L0→L3）**（`octop-memory`） | 算法本在外部包；属产品级，非内核必需 |

---

## §2 现状盘点：scattered 在 helloai-core 的执行碎块

> 依据比对报告（差距 D1–D6）与本仓实测。**注意：这些都是「碎片」，不是一个内核。**

| 碎块 | 文件/位置 | 已是内核件？ | 缺什么 |
|---|---|---|---|
| **循环** `ChatModelToolLoop` | `agent/runtime/loop/impl/ChatModelToolLoop.java:58,85,100-123,147-152`（`for(maxIter=5)` + `hasToolCalls()` 分支 + 工具回喂 + `setInternalToolExecutionEnabled(false)`） | ✅ 骨架正确（就是 DSH `ReactLoopAgent`） | **工具注入源被掏空**（D1）· maxIter=5 · 覆盖面 1/16 |
| **工具注册** `ToolRegistry` + `ToolContext` | `agent/tool/ToolRegistry.java:49-77`（条件可用 + 动态描述语义位，REF-1.3）、`ToolRegistryImpl.java`、`ToolCallbackContributor` | ✅ **比 DSH 更声明式** | 对 `API_KEY_LLM` 的工具面在 `getEnabledToolsForAccess` 被剥光（D1） |
| **技能发现/安装** `SkillPackage*` | `agent/skill/**`（`SkillPackageCatalog`/`Scanner`/`Parser`/`FrontMatter`/`ZipGate`/`Stamper`）+ `AgentSkillSpecService` | ✅ 发现+安装齐全 | **注入的是「速览」而非「全量目录+惰性加载」**（D3） |
| **装配** `AgentRuntimeContextAssembler` | `agent/service/AgentRuntimeContextAssembler.java:135-136,140-144,155-159,629-696,213` | 🟡 有 | `systemPrompt("")` 硬编码（D2）；装配不是「一次内核契约 + 逐 turn 微调」 |
| **会话/事件** `AgentSession` + `AgentEventRecorder` | `agent/session/entity/AgentSession.java`、`agent/event/AgentEventRecorder.java` + ADR-001 | 🟡 有 Run/Turn/Step 模型 | **缺 append-only 单源 + projection/fork**（没有「模型可见⟺已记录」铁律） |
| **回写/产出** `ExecutionResultHandler` + `ExecutionArtifactServiceImpl` | `ExecutionResultHandler.java:86-100,316-331`、`ExecutionArtifactServiceImpl.java:106-148` | ✅ 有 | 模型自己拿不到 `uploadArtifact`（也被过滤）；交付物平台代劳 |
| **sandbox** `EnvironmentSandboxProvider` | `agent/runtime/sandbox/**` | 🟡 空壳（无 exec 实现） | 缺手脚（D2） |
| **业务 LLM 面** | `ApiKeyAgentExecutor.java:42-64` + 15 处调用点（planner/clarify/review/finalreport/preview…） | ⚠️ 全走单次聊天、不走循环 | **覆盖 1/16** |

> **一句判语**：这些碎片已经覆盖了 DSH 八件套的 6 项（见可行性分析 §5：完整 ✅4 / 部分 🟡6 / 缺失 ❌3）。**缺的不是零件，是把零件拧成一个自洽内核的「架构轴」——那个轴就是 §1.1 的三条意图。**

---

## §3 收敛方案（详细）

> 目标：把上述碎块收敛成一个**可独立运行、可独立测试、可被平台编排层调用**的内部 agent 内核（借 DSH 的叫法，叫 `agent kernel`，不叫 java-harness——因为它不重写，是把已有零件归位）。**四件事都改在「装配层/可见性层」，不动现有 `ChatModelToolLoop` 的循环本体。**

### 收敛轴：把「循环」与「平台 MCP 会话」解耦（先做）

现状断点在 `AgentMcpServerServiceImpl.getEnabledToolsForAccess:267-275`——它对 `API_KEY_LLM` 把 13 个 `MCP_SESSION_TOOLS` 全剥，导致 `resolvedTools` 空 → `resolveVisibleCallbacks` 恒返 `List.of()` → 模型看不到 tool schema → `hasToolCalls()` 恒 false → 循环首轮即 `stop`（**比对报告 §2.2 / §7.3**）。

**收敛动作 A（工具注入解封 = D1）**：
- 给内部 LLM 一条**不依赖「MCP 会话」**的工具通道。最小集（对齐外部 agent 已能做的）：
  - 读原始附件正文（对齐外部 REST `GET /api/attachments/downloadById/{id}`；**注意 `getSubTaskDetail` 表示是附着在 `MCP_SESSION_TOOLS` 内的，故要么开放它、要么另开一个非会话挂的读附件工具**）
  - 文件/工作区读写（先只读 + 只写产物目录）
  - 一个正式的「提交/交付」动作（当前平台代劳，模型无感知）
- **落点**：复用 `ToolRegistry` 语义位（REF-1.3 已具备）+ `ToolCallbackContributor` 端口，经技能 `requiredTools` 声明 + 内部 LLM 授权集接通。**不新造平行通道**——只改「对 `API_KEY_LLM` 该放哪些工具」的可见面。

### 收敛动作 ①②③④⑤（按依赖序）

| # | 收敛动作 | 借鉴来源 | 补的缺口 | 改在哪（不动循环本体） |
|---|---|---|---|---|
| **① 会话单源化** | 把 `AgentSession`/`AgentEventRecorder`/ADR-001 收敛成**一条 append-only 事件源 + 投影面**；确立「只有已 commit 的事件才可进模型视野」（可先不落库，内存单源 + 可选 DB 持久化）。**这是把「聊天」变成「harness」的关键一步** | DSH 意图①（session 单一事件源） | 会话铁律 / 审计 / 断点 | 新增 `AgentSessionLedger`（append-only），`AgentRuntimeContextAssembler` 从它投影「模型可见消息」；`AgentEventRecorder` 作为它的 commit 通道 |
| **② 技能改「全量目录 + 惰性加载」** | 注入从「任务侧 `requiredSkills` 挑选 + 速览摘要」改为「**全量可调用技能目录（`name`+封顶 `description`）+ 模型调 `skill({name})` 惰性加载正文**」。保留 `requiredTools` 作为**能力级声明**（HelloAI 比 DSH 强的一点，不丢） | DSH 意图②（progressive disclosure）、`tool-skill/src/index.ts:215-251` | D3 | 新增一个 `skill` 工具（`SkillLazyLoader`）；`AgentRuntimeContextAssembler` 注入目录段而非速览；`AgentSkillSpecServiceImpl` 加 `get(name)` 惰性加载 |
| **③ 补手脚** | 按 DSH `fs`（`fs/src/index.ts:87`）与 octop `BackendWorkspace`（`workspace.py:356`）的**接口形状**，给内部 LLM 一个**只读工作区 + 写产物目录**的能力（先不做全沙箱，fd 级最小集） | DSH fs / octop BackendWorkspace 接口形状 | D2 | 新增 `WorkspaceAccess` + 读附件工具；`RuntimeTurnExecutor.observeSandbox` 从「只观测日志」升级为「可读工作区」 |
| **④ 装配收敛** | `AgentRuntimeContextAssembler` 从「每次从头拼」改为「**一次装配出内核契约（system/context/tool 可见集/skill 目录）+ 逐 turn 微调可见集**」；技能热更新走重新 resolve 而非重建 | octop 意图④（装配一次 + 逐 turn 再装配 + 变更重编） | 装配一致性 | 收敛 `AgentRuntimeContextAssembler`：产物是一个不可变「内核装配对象」，`report` 变更时只重 resolve 可见集 |
| **⑤ 拦截链（可后置但强烈建议随 ①②③ 一起铺）** | 用 **Spring AI Advisors API** 建立统一拦截层，作为将来所有策略（能力闸/收敛熔断/审计）的唯一挂点，替代「每加一个策略改主链」 | octop 意图⑤（中间件排序）/ DSH `waterfall` | D4 | 在 `agentLoop.run` 外层挂 Spring AI `Advisor`；能力闸（LOG-2026-011-007 A）与收敛熔断（B）作为两个 Advisor 落地 |

### 收敛的产出物边界（收敛成一个「内核」的核心判据）

收敛完成后，内部 agent 内核应满足：
- **可独立构造、可独立测试**：输入一个 `AgentLoopInput`（上下文 + 工具可见集 + 技能目录），输出 `AgentLoopResult`（文本 + 事件流），**不依赖 MCP 会话是否存在**。
- **平台编排层只是调用方**：`LocalExecutionCommandConsumer` / `RuntimeTurnExecutor` 从「组装者」退化为「调度器 + 订阅者」，内核契约由 `AgentRuntimeContextAssembler` 一次产出。
- **事件全量**：`AGENT_STARTED / SKILL_RESOLVED / TOOL_RESOLVED / ENVIRONMENT_RESOLVED / CONTEXT_BUILT / AGENT_COMPLETED`（已经有一套，保留）皆从 `AgentSessionLedger` 投影，不双写。

---

## §4 什么情况才需要「再进一步」（避免过早造）

| 触发信号 | 才做 | 借鉴来源 |
|---|---|---|
| 出现显式的**多 agent 父子子图**（父 agent 派子 agent 并要图级收敛、子失败重试） | 才评估 **LangGraph4j** 作为编排层 | octop 借 deepagents+LangGraph；AgentTeams 的矩阵派发 |
| 出现强 **HITL（人工审批中断/恢复）** 需求，且惯用 checkpoint 语义 | 才引入 LangGraph4j `CheckpointSaver` / 或给 `ChatModelToolLoop` 补 `LoopCheckpoint` 持久化 | octop `AsyncSqliteSaver` / DSH `session-checkpoint-policy` |
| 需要**原生进程/沙箱隔离**给不可信 agent | 才做 docker/OCI 外包 或 SSH 沙箱（Java JSch/sshd），**不做** JNI/FFI 原生沙箱 | DSH fs/shell/sandbox provider seam |

> **在这些触发信号出现前，收敛到 §3 所定义的内核即可，不再造框架。** 判据：HelloAI 现有 `ChatModelToolLoop` 已与 DSH `ReactLoopAgent` 等价（可行性分析 §5 项 7）；再造第二套循环 = 双轨并存，违背本仓「禁止复制完整业务链」禁令。

---

## §5 与既有登记的关系

- **D1（工具面）/ D2（手脚）/ D3（技能装配）** 与比对报告一致，是收敛方案的先决；且与 **LOG-20261011-007 A（能力闸）/ B（收敛熔断）** 同源但方向相反——A 治"拦掉它做不到的活"，收敛方案治"让它做得到"。两者可并行：A/B 正是 §3 收敛动作 ⑤ 的两个 Advisor。
- 收敛动作 ②（技能目录注入）是 `REF-1.2c / REF-1.3`（技能摄入闸门 + 语义位）的**消费端**——比对报告明确指出「resolve 的调用结果均不进模型可见工具，属同批改造消费点」，收敛动作 ② 正是把这个消费点接通。
- 本报告为**调研后的架构调整思路**，不含排期进度；如需推进，建议按规约转 `plan/`（立 REC 编号）后实现。

---

## §6 证据索引

**DSH（本地实测 `E:/workspace/deepseek-harness-master`）**
- 会话铁律：`packages/core/session/src/index.ts:876`、`surface.ts:7`
- 技能目录+惰性加载：`packages/skill/tool-skill/src/index.ts:81-160,215-251,319-321`；发现 `packages/skill/skill-filesystem/src/index.ts:149,730`
- 工具 scope 分层：`packages/core/tools/src/index.ts`（`view(scope)`）
- 能力来源：`docs/architecture.zh.md:106`
- 沙箱 fail-closed：`packages/sandbox/sandbox/src/index.ts:153-158`

**octop-harness（本地实测 `E:/workspace/octop-harness-main`）**
- 装配一次+逐 turn：`src/octop_harness/agent.py`；Octop `agents/manager.py:3068-3429,3306-3318,3360-3389`
- 逐 turn 再装配/重编：`agents/manager.py:2145-2175,2695,2707,3041-3066`
- 中间件排序：`agents/manager.py:3226-3243`；基类 `langchain.agents.middleware.AgentMiddleware`
- 后端抽象：`backends/workspace.py:356`

**HelloAI（本仓实测）**
`agent/runtime/loop/impl/ChatModelToolLoop.java:58,85,100-123,147-152` · `agent/runtime/loop/AgentLoopInput.java:47` · `agent/tool/{ToolRegistry,ToolContext}.java` · `agent/tool/impl/ToolRegistryImpl.java` · `agent/skill/**` · `agent/service/impl/AgentMcpServerServiceImpl.java:267-275` · `agent/service/AgentRuntimeContextAssembler.java:135-136,140-144,155-159,213,629-696` · `agent/session/entity/AgentSession.java` · `agent/event/AgentEventRecorder.java` · `ExecutionResultHandler.java:86-100,316-331` · `ExecutionArtifactServiceImpl.java:106-148` · `agent/runtime/sandbox/**` · `agent/executor/ApiKeyAgentExecutor.java:42-64`

---

## §7 局限说明

1. 本报告是**调研后的调整思路**，不是设计稿——收敛动作 ①②③④⑤ 的**具体实现结构（新类、接口签名、包归属）需在转 `plan/` 时再细化**，本报告只定「方向与改在哪层」。
2. 引用行号以当前本地 checkout 为准（DSH `0.1.0-rc.8`、octop-harness `1.0.2`）——版本迭代快，落地时按当时代码重新定位。
3. 收敛动作 ①（会话单源化）若要落库，涉及事务/幂等边界，需与 `ExecutionResultHandler` 的 afterCommit 回写对齐，属实现期细节，本报告不展开。

---

## §8 后续专项：内核 / 对接协议层 可移植 jar 化（长期愿景，2026-10-12 登记）

> **性质**：**后续专项 · 长期愿景 · 不排期**（Status=`Analysis`；仅登记方向，不纳入当前 `REF-8` 执行范围）。触发与次序见 §8.4。
> **来源**：用户思考——随着项目越大，把 HelloAI 特有的两类能力做成**可移植 jar / Maven 包**（类 deepseek-harness），既做对外传播，也作为「功能二次验证」。

### 8.1 什么被登记为「后续专项」

把本文档 §3 收敛出来的内部 agent 内核 + §1 考察的「对接外部 agent 协议层」，**物化成可在独立 Java 项目中复用（并可相互组合）的可移植 jar**。这不是「从零写 java-harness」（对 `research/HelloAI 自建 java-harness 可行性分析（2026-10-12）.md` 的方案 A，仍**不建议**），而是「把既有正确边界物化封装」。

### 8.2 两个待寻件（哈希分解，可移植度不同）

| jar | 是什么 | 可移植性 | HelloAI 业务耦合（实测） |
|---|---|---|---|
| **a. 内部执行内核**（loop / tool / session / skill / system-prompt 抽象与自洽实现） | 让一个 LLM 变成能多步思考、调工具、装技能的 agent | **高**——本就 #不携带业务字段 | 接口 `ToolRegistry`/`ToolContext`/`ToolDefinition` 仅引 `java.util.List` / `AgentAccessType` 常量；`AgentContext`/`AgentLoopInput` 只依赖 `ToolExecutor` + Spring AI → **抽 jar ≠ 重构，是物化既有正确边界** |
| **b. 对接外部 agent 的协议层**（宿主作为平台，经 MCP/HTTP 持工牌接入外部 agent：认领 / 上报 / 心跳 / 产物交换 / duty lease / access type） | 任何「宿主 agent 平台 + 外部 agent 协作」都要的通用底座 | **中~高**——**协议骨架**干净 | `McpMcpServer` 仅业务依赖 `McpToolService`，其余全是 Spring AI 标准接口 → **协议面也可独立**；但**协议背后的业务契约（子任务 / 评审 / 验收 / 券商数据 / artifact schema）是 HelloAI 自己的，留平台** |

**关键划界**：jar 复用的是**骨架 + 状态机**（认领、上报、心跳、产物交换、duty lease、access type、loop/tool/skill 抽象），**不是业务语义**（子任务/评审/验收/具体 artifact）。业务永远作为「使用 jar 的项目」自己实现的部分。

### 8.3 附带的「二次验证」价值

可移植 jar 的核心价值不只是传播/知名度——**它强制证明内核真的自洽**：若内核只能 helloai 里跑，说明仍背着隐形平台依赖；抽成能独立跑的 jar（带一个 `main` 方法 demo：装 agent → 派任务 → 调工具 → 交产物）即达成 REF-8.4 验收判据①「不依赖 MCP 会话可独立构造 / 测试」。所以 jar 化 = **「内核是否真干净的二次验证」+「对外能力沉淀」**。

### 8.4 触发 / 次序（避免过度抽象）

- **触发**：本专项**不排期**，仅作为 `REF-8` **收官后**的可选方向。判据：先让内核自洽（REF-8），再把自洽后的内核与协议层 jar 化。
- **两段式路径**：
  ```text
  阶段1（REF-8 内）：让内部内核自洽（工具 / 手脚 / 技能 / 覆盖面）—— 顺手守内核接口不脏
  阶段2（REF-8 收官后）：把内核 + 对接协议层 jar 化：
       └ 进 jar：loop + tool + session + skill + system-prompt 抽象与自洽实现 + 协议骨架
       └ 留平台：Mcp 工具、子任务/调停/评审/订单、回写通道（作为 adapter）
  ```
- **红线（防过度抽象）**：抽象与业务耦合是两端；力求「抽象进 jar、业务留平台、adapter 连接」，**不**把所有东西塞进一个号称通用的 jar，**不**为可移植让平台代码变得晦涩。

### 8.5 未决待评估（不在此定）

- 命名 / 形态：`helloai-harness` 独立 Maven 模块 vs 独立 repo；对外发布渠道（本地 nexus / 中央仓库）。
- 是否对外开源 / 许可；社区传播所需配套（空跑 demo、doc、测试、零 HelloAI 业务残留）——属**产品化成本**，非纯代码搬迁。
- 与《自建 java-harness 可行性分析》方案 C（LangGraph4j 覆盖）的关系——仅当触发信号出现再评估。