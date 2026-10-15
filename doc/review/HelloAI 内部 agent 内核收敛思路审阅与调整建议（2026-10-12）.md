# HelloAI 内部 agent 内核收敛思路 —— 审阅与调整建议（2026-10-12）

> **性质**：只读审阅 + 调整建议（不改业务代码）。
> **审阅对象**：`doc/research/HelloAI 内部 agent 内核收敛与借鉴思路（2026-10-12）.md`（下称「原方案」）。
> **方法**：原方案每条关键前提**回代码实测**（带 `path:line`）；确认对的部分、订正错的部分、补上遗漏的部分，最后给出调整后的内核轴。
> **关联**：`doc/research/HelloAI 自建 java-harness 可行性分析（2026-10-12）.md` · `doc/review/HelloAI 内部 LLM 执行链与开源 agent 架构比对（2026-10-12）.md`
> **落点更新（2026-10-12，用户裁定）**：本文 §5.2 的 `D1`~`D4` 与 §4 的 `E1` **已立项为 `REF-8.1`~`REF-8.5`**（差距锚点 **`G-021`**，决策 `D-2026-10-12-1`）；`D4` 拦截链亦为 `LOG-20261011-007` A+B 的唯一挂点。见 `plan/HelloAI 借鉴落地实施计划.md` §10、`plan/HelloAI 计划编排与排期总览.md` §2.6 / §4。
> **⚠️ 2026-10-15 订正指针（`D-2026-10-15-6`，记录 `LOG-20261015-007`）**：上句「A+B 的**唯一挂点**」**已作废** —— `REF-8.4` 拦截链已于 2026-10-13 交付（形态 = 循环自有 `LoopHook` 三档，**非** Spring AI `Advisors`）；**B 收敛熔断挂拦截链、A 能力闸出列落 `ResilientDispatcher`**（`plan/HelloAI 借鉴落地实施计划.md` §10.7.2）。**本文是 2026-10-12 的时间点快照**，**正文（全文，含 `Advisor` / 「唯一挂点」的一切表述）一律保留原样**，仅以本指针标记口径演进。

---

## §0 结论

1. **原方案的方向是对的**：不造框架、把已有碎块收敛成「内部 agent 内核」、只改装配层不动循环本体、借「架构意图」不追类名——这四条判断我都实测确认成立。
2. **但有一处必须订正**：原方案 §3 收敛动作 ①「**新增** `AgentSessionLedger`（append-only）」——**这是重复造单源**。HelloAI **已经有一条 append-only 事件主表**（`agent_event`，`AgentEventRecorderImpl.java:50-60` 注释原文「append-only 轨迹主表」）＋ Outbox（`agent_outbox_event`）＋ 已存在的事件终态投影（`EventReconciliationServiceImpl`）。正确动作是**把 `agent_event` 提升为投影源**，不是新建一条 Ledger（否则正是本仓明令禁止的「双轨并存」）。
3. **调整后的一句话轴**：内核的轴不是「会话单源化」，而是「**可见面收敛**」——把「模型的可见集（工具 + 技能目录 + 上下文）」当成内核的**唯一输出契约**，分三层收敛；会话单源化降为**支撑项**（提升已有 `agent_event`，非新建），覆盖面 1/16 单独立项。

---

## §1 原方案「对」的部分（实测确认）

| 原方案判断 | 实测 | 判定 |
|---|---|---|
| 循环本体不用重写，`ChatModelToolLoop` 骨架正确 | `loop/impl/ChatModelToolLoop.java:85,100-123,147-152`（`for(maxIter)` + `hasToolCalls()` 分支 + 回喂 + `setInternalToolExecutionEnabled(false)`） | ✅ 确认 |
| `ToolRegistry` 语义位「比 DSH 更声明式」，是落点 | `agent/tool/ToolRegistry.java:49-77` + `ToolRegistryImpl.java` | ✅ 确认 |
| 落点应复用 `ToolCallbackContributor` 端口，不新造平行通道 | **该端口确实存在**：`agent/tool/ToolCallbackContributor.java`（+ `WebSearchToolCallback`、`SearchKnowledgeToolCallback` 两个实现） | ✅ 确认 |
| 技能侧「发现/安装齐全、缺惰性加载」 | `AgentSkillSpecService` **只有 `resolve(List)`，无 `get(name)`**（`AgentSkillSpecService.java:61`）；`SkillSpeedSummaryRenderer` 只渲速览 | ✅ 确认 |
| 从 DSH 借「模型可见⟺已记录 / 全量目录+惰性加载 / 能力=工具面+手脚」三条意图 | 与 `packages/skill/tool-skill/src/index.ts:81-160,215-251`、`session/src/index.ts:876` 实测一致 | ✅ 确认 |
| 不抄 Cordis / deepagents / 原生沙箱；拦截链用 Spring AI Advisors | 判断合理 | ✅ 确认 |
| 触发信号（多 agent 子图 / HITL / 原生隔离）才上 LangGraph4j | 与可行性分析 §6 方案 C 一致 | ✅ 确认 |

**原方案的价值**：它把「补半套」从「可行性结论」推进到「改在哪一层」，并正确地把循环本体冻结为「不改」。这个「不动循环」的纪律是对的。

---

## §2 必须订正的两处

### 2.1 ★订正一：收敛动作 ① 是「重复造单源」

**原方案**（§3 表 ①）：*「新增 `AgentSessionLedger`（append-only），`AgentRuntimeContextAssembler` 从它投影」「确立『只有已 commit 的事件才可进模型视野』」*。

**实测事实**：**这条 append-only 主表已经存在。**

```java
// AgentEventRecorder 接口 javadoc（agent/event/AgentEventRecorder.java:11-15）
// "同事务双写：1) agent_event（append-only 事件轨迹，Event Stream 主表）
//              2) agent_outbox_event（Outbox，由 AgentEventCompensationTask 投递 MQ）"
// "两表共享同一 eventId（B3 对账键）；事件仅 write-only"

// AgentEventRecorderImpl.java:49-60
String eventId = UUID.randomUUID()...;
// 1) append-only 轨迹主表
AgentEvent event = new AgentEvent();
event.setEventId(eventId); ... 
agentEventMapper.insert(event);          // :60
// 2) Outbox 双写 … agentOutboxService.save(outbox);   // :79
```

**而且投影也已存在**：`.tmp/c3-events-probe.sql:27-47` 复刻的是 `EventReconciliationServiceImpl` 的**终态投影**（从 `agent_event` 末条事件映射五态 `ASSIGNED/IN_PROGRESS/REVIEW/REWORK/DONE`）——即「从事件流投影出状态」这件事**已经在跑**。

**所以正确的收敛动作**（改法）：
| 原方案 | 订正后 |
|---|---|
| 新增 `AgentSessionLedger`（append-only） | **不再新增表**。把已有 `agent_event` **提升为「唯一投影源」** |
| 新建一条单源 | 给 `agent_event` 补两件它现在缺的东西：<br>① **`surface` 投影**——标记哪些事件可进入「模型可见消息」（DSH 的 `SurfaceEventType`：`system/developer/user/assistant message` + `tool/result`，`session/src/types.ts:439-444`）<br>② **`deriveMessages()`**——`AgentRuntimeContextAssembler` 的 user prompt **从该投影 walk 出来**，而不是另拼（DSH `session/src/index.ts:984-1005`） |
| — | （可选，后置）`fork` = 复制前缀 + 追加 `session/end-seed`（DSH `fork.ts:21-30`） |

**为什么这不是文字游戏**：原方案的写法会让实施者**去建第二张 append-only 表**，而 `agent_event` 已经在双写；两张表并存 = 一份事实两处落、必然漂移，正是本仓「**禁止复制完整业务链**」与本会话反复出现的「**双轨并存**」缺陷模式。**判据：加"单源"前先问「现有 append-only 源是什么」——答案是 `agent_event`。**

### 2.2 订正二：引用精度（两处）

| 原方案写法 | 订正 |
|---|---|
| §1.2 / §6：`agents/manager.py:3068-3429,3306-3318,3360-3389,2145-2175,2695,2707,3041-3066`（标注为 **octop-harness 实测**） | **这些行号属于 Octop 主仓**（`E:/workspace/Octop-main/src/octop/infra/agents/manager.py`），**不是** `octop-harness-main`。后者 `manager.py` **只有 486 行**（`src/octop_harness/manager.py`），引不到 `:3068`。**必须区分两个仓**，否则后续按行号回查必然落空。 |
| §3 表 ⑤ 与 §5：`LOG-2026-011-007` | **编号笔误**，应为 **`LOG-20261011-007`**（`doc/log/2026-10.md:4395` 实测）。 |

> 另注：原方案 §1.1 引 `session/src/index.ts:876` 说「append-only log remains the source of truth」——`commit` 确在 `:876`，`append` 在 `:798-827`；引用基本准确（与本报告 §2.1 一致）。

---

## §3 优先级需调整：会话单源化不该排第一

原方案 §3 的收敛动作表把 **① 会话单源化** 列在第 1 行，且在 §0 称它是「把聊天变成 harness 的关键一步」。**这个排序会误导排期。**

**理由**：以「**让内部 LLM 进化成 agent**」为目标，动作要按「**是否直接产出能力**」排序：

| 动作 | 直接产出能力？ | 原方案排位 | 应排位 |
|---|---|---|---|
| **D1 工具面解封** | ✅ **是**（没工具就没有 agent） | 收敛轴（先做） | **1**（保持） |
| **D2 手脚 / 工作区** | ✅ **是**（能读能写才能交付） | ③ | **2** |
| **D3 技能全量目录 + 惰性加载** | ✅ **是**（能力按需装配） | ② | **3** |
| **D4 拦截链（Advisors）** | 🟡 间接（是策略挂点） | ⑤ | **4**（可与 2/3 并行铺） |
| **会话单源化（订正后）** | ❌ **否**——产出的是**审计 / 断点 / 可重放**，不是能力 | ① | **5**（支撑项，不阻塞前三） |

**依据**：DSH 的「模型可见⟺已记录」是它作为 harness 的**工程品格**（可审计、可 fork/replay），**不是**它 agent 能力的来源；DSH 的能力来自 `ctx.tools` + `ctx.fs`/`ctx.shell`（`docs/architecture.zh.md:106`）。原方案把「品格」排在「能力」之前，会让前 2–4 周做完却**看不到内部 LLM 变强的任何变化**，容易中途失去方向。

> **一致的口径**：会话单源化**值得做**，但它是「**做了更好**」而非「**不做就不行**」；D1/D2/D3 是「**不做就不行**」。两类不能并列排序。

---

## §4 原方案提了但没给动作的三条（关键遗漏）

原方案 §2 表里点出了这三条，但 §3 收敛方案里**没有任何一条动作承接**——这会让「收敛完成」的标准失真：

| # | 遗漏项 | 原方案在哪提过 | 影响 | 建议 |
|---|---|---|---|---|
| **E1** | **循环覆盖面 1/16**（`agentLoop.run()` 生产调用点仅 1 处；15 处业务 LLM 面走 `ApiKeyAgentExecutor` 单次聊天） | §2 表末行「覆盖 1/16」 | **做完 D1/D2/D3，内部 LLM 仍只在 1/16 的场景里是 agent**——其余 15 处（规划拆解/需求澄清/检索规划/Prompt 增强/子任务评审/终报告评审与生成…）照旧是聊天 | **单独立项**：逐面判「该不该进循环」（子任务评审、终报告生成理论上都该带工具）。**不列为本期动作，但必须显式登记为「已知边界」**，否则会被误读成"内核收敛=内部 LLM 全面进化" |
| **E2** | **`maxIterations = 5`** | §2 表「循环」行 | 有工具后 5 步明显不够（多轮工具探索易撞上限） | 提为**按任务类型分档**（可配，非全局常量） |
| **E3** | **`systemPrompt("")` 硬编码** | §2 表「装配」行（归到 D2 里） | 缺 system 级角色约束 + 工具使用指引；且 `FinalReportReviewServiceImpl:387` 同款硬编码 ⇒ 是**全链约定**非单点 | 明确为独立动作：**system prompt 从空改为「角色 + 工具使用指引 + 输出契约」**。它不属于"补手脚"（③），应单列 |

---

## §5 调整后的内核思路（综合建议）

### 5.1 内核的轴：从「会话单源化」换成「**可见面收敛**」

```
内核唯一输出契约 = 「模型的可见集」
   ├─ L1 可见工具集    由 ToolRegistry.resolve 单口径产出（源不得被上游剥空）   ← D1
   ├─ L2 可见技能目录  全量目录（name + 封顶 description），正文惰性加载        ← D3
   └─ L3 可见上下文     system prompt（非空）+ 工作区/附件读手脚               ← D2/E3
```

**为什么用「可见面」当轴**：DSH 的 `ToolRuntime.view(scope)`（`packages/core/tools/src/index.ts` 的 `view(scope)`）与技能目录注入（`tool-skill/src/index.ts:215-251`）本质是**同一件事的两个实例**——「**模型此刻能看到什么**」。把它抽成内核的第一性契约，三个缺口（D1/D2/D3）就成了**同一契约的三个截面**，而不是三个独立补丁。这也正好接住本会话已确认的关键缺陷模式：**「规则存在 ≠ 全路径强制」**——可见面必须是**唯一判据**，`getEnabledToolsForAccess`（`:267-275`）与 `ToolRegistry.resolve` 两处口径必须一致。

### 5.2 三层收敛动作（按价值序，替换原方案 §3 表）

| 序 | 动作 | 缺口 | 借鉴（意图级） | 落点（不动 `ChatModelToolLoop` 本体） |
|---|---|---|---|---|
| **1** | **工具可见面解封**：给 `API_KEY_LLM` 一条**不依赖 MCP 会话**的工具通道（读原附件 / 文件读写 / 正式交付动作） | D1 | DSH 工具按 scope 分层可见 | 复用 `ToolRegistry` 语义位 + `ToolCallbackContributor`；**只改「对 `API_KEY_LLM` 放哪些工具」**，并与 `resolve` 保持单口径 |
| **2** | **补手脚 + 上下文**：新增 `WorkspaceAccess`（先只读 + 只写产物目录）+ 读附件工具；**`systemPrompt` 从 `""` 改为角色/工具指引/输出契约** | D2 + E3 | DSH `fs/src/index.ts:87` / octop `BackendWorkspace` 的**接口形状** | `AgentRuntimeContextAssembler.java:213`；`RuntimeTurnExecutor.observeSandbox` 从「只观测」升级为「可访问」 |
| **3** | **技能改「全量目录 + 惰性加载」**：注入目录段（非速览），新增 `SkillLazyLoader` 工具，`AgentSkillSpecService` 加 `get(name)` | D3 | DSH progressive disclosure（`tool-skill/src/index.ts:81-160,215-251`） | `AgentRuntimeContextAssembler` 注入目录；保留 `requiredTools` 作能力级声明（**别丢**） |
| **4** | **拦截链**：Spring AI **Advisors API** 建统一拦截层，作为所有策略（能力闸 A / 收敛熔断 B / 审计）唯一挂点 | D4 | octop 中间件排序 / DSH waterfall | 挂在 `agentLoop.run` 外层 |
| **5** | **事件单源「提升」**（原方案①的订正版）：`agent_event` 补 `surface` 投影 + `deriveMessages()`，让装配器从已 commit 事件投影模型可见消息 | 审计/断点 | DSH 意图①（`session/src/types.ts:439-444`、`index.ts:984-1005`） | **不新建表**；`AgentEventRecorder` 保持 commit 通道 |

### 5.3 收敛完成的判据（内核自洽，比原方案更严）

原方案 §3「产出物边界」写对了一半，补两条：

1. **可独立构造/测试**：`AgentLoopInput → AgentLoopResult` 自洽，**不依赖 MCP 会话**（原方案有）。
2. **可见面单口径**：模型可见工具集 = `ToolRegistry.resolve` 结果，**上游无第二处二次过滤**（补充——对应本会话已证的「规则存在≠全路径强制」）。
3. **能力可验证**：拿一条**需要读原附件 / 写产物**的验收任务，内部 LLM 能**自己调工具**完成（补充——这才是"是不是 agent"的判据；原方案缺）。
4. **事件不双写**：模型可见消息与事件流**同源**（订正后的 ①）。

---

## §6 与原方案的关系（一句话总结）

> 原方案是**一份正确的方向书**，主要缺陷是**三处**：①把「已有的 append-only 源」误判为「缺、需新建」（会造双轨）；②把「审计品格」排在「能力」之前（排序会误导）；③§2 发现的三条（覆盖面 1/16、maxIterations、systemPrompt）**没有落到 §3 动作**。
> **调整后内核思路**：轴 = **可见面收敛**（工具集 → 技能目录 → 上下文三层）；支撑 = **提升已有 `agent_event`**（不是新建 Ledger）；**覆盖面 1/16 单独立项**；拦截链用 Spring AI Advisors；LangGraph4j 保持「触发信号出现才上」。

---

## §7 证据索引

**HelloAI（本仓实测）**
- `agent/event/AgentEventRecorder.java:11-15`（javadoc 原文「append-only 事件轨迹，Event Stream 主表」+ Outbox 双写）
- `agent/event/impl/AgentEventRecorderImpl.java:45-60,79`（`agentEventMapper.insert` / `agentOutboxService.save`）
- `.tmp/c3-events-probe.sql:27-47`（复刻 `EventReconciliationServiceImpl` 的终态投影，已从 `agent_event` 投影五态）
- `agent/tool/ToolCallbackContributor.java`（**端口已存在**）+ `planner/tool/WebSearchToolCallback.java`、`knowledge/SearchKnowledgeToolCallback.java`（实现）
- `agent/tool/ToolRegistry.java:49-77`、`ToolRegistryImpl.java`
- `agent/skill/AgentSkillSpecService.java:33-47,61`（只有 `resolve(List)`，无 `get(name)`）
- `agent/skill/SkillSpeedSummaryRenderer.java:26-43`
- `agent/service/AgentRuntimeContextAssembler.java:135-136,140-144,213`
- `agent/service/impl/AgentMcpServerServiceImpl.java:267-275`
- `agent/runtime/loop/impl/ChatModelToolLoop.java:58,85,100-123,147-152`；`AgentLoopInput.java:47`
- `agent/executor/ApiKeyAgentExecutor.java:42-64`
- `review/service/impl/FinalReportReviewServiceImpl.java:387`
- `doc/log/2026-10.md:4395`（`LOG-20261011-007`）

**DSH（本地实测）**
`packages/core/session/src/{types.ts:439-444,281-428},{index.ts:876,984-1005},{fork.ts:21-30}` · `packages/skill/tool-skill/src/index.ts:81-160,215-251` · `packages/core/tools/src/index.ts`（`view(scope)`）· `docs/architecture.zh.md:106`

**Octop 主仓 vs octop-harness（路径区分）**
`E:/workspace/Octop-main/src/octop/infra/agents/manager.py`（原方案行号实际来源）· `E:/workspace/octop-harness-main/src/octop_harness/manager.py`（**486 行**，`agent.py` 1958 行）

---

## §8 局限说明

1. 本报告为**审阅意见**，不含实现；转 `plan/` 时仍须细化类/接口签名。
2. 对原方案订正的两处均基于**当前 checkout 实测**（`agent_event` 双写、`ToolCallbackContributor` 存在、`AgentSkillSpecService` 无 `get`）；若期间代码已变，以最新为准。
3. 「可见面收敛」作为内核轴的提法属**我给出的综合建议**，需用户裁定；它与原方案的实质分歧只在**① 单源是否新建**与**排序**两点。
