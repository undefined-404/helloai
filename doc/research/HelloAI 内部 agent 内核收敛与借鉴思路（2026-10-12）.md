# HelloAI 内部 agent 内核收敛与借鉴思路（2026-10-12）

> **Status: `Analysis`**（只读调研，不改变任何实现、不承诺排期）。
> **性质**：只读调研 · 调研后**架构调整思路**（非设计文档、非实施计划，Status=`Analysis`）。
> **2026-10-13 追加（四）**：§9「DSH ↔ HelloAI 执行形态 / 状态机 / 能力鉴别 比对」——把 `LOG-20261011-007` 的 A/B/C 判据摆到标杆的**执行形态**旁核：标杆**没有「驳回到选人」这一层**，我方**有且链已完整**，缺的只是「谁来触发」；并落定「**发现放内、处置放外**」（方案 C）的合法性双口径、以及模型能力事实源的形态（`model_name` = 官方 API 字段原样透传；禁写死代码表的本仓判例见 §9.6.4）。
> **2026-10-12 追加**：§8「后续专项：内核 / 对接协议层 可移植 jar 化（长期愿景）」——用户提出的对外可移植方向，登记为**后续专项、不排期**。
> **2026-10-12 追加（三）**：§1.6「DSH 的**角色形态**（persona 槽位 + agent preset，非 role 枚举）与 **LLM 全生命周期 14 节点 + 2 旁路**」——对照用户提供的外部样本（WorkBuddy「调用 skills 后的上下文」）核准「身份面 + 装配时机」的差距；候选工作项 `REF-8.1.2` / `REF-8.2.3` / `REF-8.4` 挂点细化（**未登记，待裁定**）。
> **2026-10-12 追加（二）**：§1.5「DSH 内部 LLM 调用链源码复核（本地 `0.2.1-alpha.2`）」——`REF-8` 双平台实测（`LOG-20261012-007`）后回标核对**循环内部**机制：装配**替换语义** / 结构化 **blocked**（含连续 N 轮门槛）/ 结构化**轮报**；对应新登记 `REF-8.1.1` / `REF-8.2.1` / `REF-8.2.2`，并与 `LOG-20261011-007` 的 A/B/C 对齐射程。
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

### 1.5 DSH 内部 LLM 调用链源码复核（本地 checkout `0.2.1-alpha.2`）

> **复核缘起**：`REF-8` 首次双平台实测（`LOG-20261012-007`）暴露三处**执行侧缺口**——内部 LLM 无「做不到」通道 / 返工上下文全量追加 / 证据契约缺段。回到标杆核对这三件事的**形状**。行号以本机 checkout 为准（§6），**此节结论不改变 §1.1 的三条架构意图**，只补「循环内部」的机制细节。
> 可执行结论已另立行动层条目（`plan/HelloAI 借鉴落地实施计划.md` §10.2）与差距表锚点（`G-021`）——本节只留调研结论与形状对照。

#### 1.5.1 调用链与终止（与 HelloAI 的差异）

| 事实 | DSH 落地（实测） | HelloAI 现状 |
|---|---|---|
| 循环归属 | `ReactLoopAgent`：`turn() while(await this.turn())` → `step() while(true)`；**step = 一次模型请求 + 它触发的工具**，turn = 0+ steps（`packages/core/agent-loop/src/agent.ts:214,252-254,298,400,409,564,604,453`） | `ChatModelToolLoop`（语义等价，`LOG-20261006` 判据一致） |
| 终止 | step：无 tool_calls / `max-tokens` / **工具结果携带 `concludesTurn`**（`agent.ts:547-555`、`tool-calls.ts:158`）；turn：`turnEnds` 且 inbox 空（`agent.ts:361-365`）；终态枚举含 **`blocked`**（`session/src/types.ts:201-232`） | 无 tool_calls 即停；`maxIterations` 分档 5/10 |
| 预算 | **循环内无预算**（`packages/core` 全域无 maxSteps/maxTurns）；预算全在外层：goal rounds 默认 **256**（`goal/goal/src/index.ts:244`）、provider 重试 **5**、实验性 Ralph **256**（`experimental/tool-ralph/src/index.ts:35`） | 预算在循环内（`resolveMaxIterations`） |

> **判读**：两种风格无优劣——DSH 把「循环」做成纯 driver，预算交给外层编排；HelloAI 循环自带上限（防死循环）。**不建议改**，但「工具结果可结束 turn」（`concludesTurn`）值得记：它让「交付/阻塞」这类**终止性工具**不必靠模型自觉停止。

#### 1.5.2 装配 = 「一次装配 + 每步重投影」，且**替换而非追加**

- **分层固定槽位**：`SECTION_ORDERS`（`system-prompt/src/index.ts:131-165`）——identity −1000 / persona 0 / plan policy 500 / team 600 / per-tool guidance 1000-5000 / deliverable refs 9000 / structured output 9900 / harness source 10000 / web 10100；runtime context **另起一条 user-role 消息**（`agent.ts:277-282,411-440`）。
- **重试不重塞**：只有 `if (firstAttempt)` 才纳入 `decision.messages`；重试**重新推导请求**，不重新 admit users、不重跑 `agent/pre-step`（`agent.ts:426`；`docs/architecture.md:110`）。
- **运行期上下文用「取代」语义**：注入文本明写 "This snapshot supersedes earlier runtime-context snapshots"，且 `refreshContext` 是**替换**不是追加（`system-prompt/src/index.ts:313,578`）。
- 可拦截：`agent/pre-step` waterfall 可改写或**拒绝**本批（`agent.ts:274-287`）——这是「拒绝执行」的唯一装配层挂点。

> **落到 HelloAI**：`AgentRuntimeContextAssembler.appendReworkContext:585-587` 把**所有轮次**审核意见全量拼入（"共 N 轮历史审核"），与标杆的「替换 + 只重导请求」**方向相反**——这正是「没能力却仍加载全部驳回内容、思考链变长」的机制来源。**收敛动作 = `REF-8.2.1`**。

#### 1.5.3 「做不到」= 结构化 blocked，且带**连续 N 轮门槛**

- **模型可自报**：`update_goal{action:"blocked", blocked_reason}`（必填）→ 落 `GoalBlockReason{code,message}` + `phase:'blocked'`（`goal/tool-goal/src/index.ts:301-318`、`goal/goal/src/types.ts:45-57`）。
- **防滥用**：连续 `blockedAfterConsecutiveRounds`（默认 **3**）轮**之前拒绝** blocked（`tool-goal/src/index.ts:39`）——即「不许一遇难就甩锅」。
- **下游**：轮驱动见 `phase !== 'active'` 即停驱动并解挂（`goal-round-driver/src/index.ts:165`）；工具侧 `deferContext(renderWrapupContext(...))` **补一段收尾上下文**（`tool-goal/src/index.ts:319-330`）。
- 另有**人工升级**工具：`ask_user_question` / `exit_plan_mode` / 工具级 `ask` 审批。

> **落到 HelloAI**：内部 LLM 的 7 个 `internal_*` 工具**无等价物**（实测 `tool_resolved` 事件：`tools`/`effectiveTools` 均只有 `internal_*`，`removedTools=[]`），"做不到"只能写进正文 → 被物化成交付物 → 送审 → 返工 → 死信。**收敛动作 = `REF-8.1.1`**（含 N 轮门槛与终态收尾，形状照抄 1.5.3）。

#### 1.5.4 证据契约的标杆形状 = **结构化轮报**

- DSH 正式路径**没有交付物核验门**：`present` 工具只 `lstat` 校验文件存在、成功后记 `deliverables/presented`（`deliverables/tool-present/src/index.ts:77-108`）；证据要求只在**提示词层**（"gather evidence … before claiming completion"，`goal-round-driver/src/prompt.ts:20-23`）。
- 真正带证据契约的是**实验性 Ralph 循环**：每轮必须交结构化报告 `{status: continue|complete|blocked, evidence[], nextSteps[], blocker}`，非空 evidence，到 `maxRounds` 终止为 `budget-limited`（`experimental/tool-ralph/src/index.ts:89-100,151-174`）。

> **落到 HelloAI**：HelloAI 的 fail-close 评审（`prompts/subtask-review.md:51-52` 的 VERIFICATION 段）**比标杆更严**——**抄不到现成物**；但**形状可借**：把「证据 + 状态 + 下一步」做成结构化轮报，**一个形状同时喂三件事**——评审要的证据、做不到通道、早停判据（`nextSteps` 重复即收敛失败）。**收敛动作 = `REF-8.2.2`**，并与 `LOG-20261011-007` 的 B 判据合并（见 1.5.5）。

#### 1.5.5 与已登记 A / B / C 的接口（判据可升级）

| 已登记项 | 标杆对照（实测） | 接口结论 |
|---|---|---|
| **A 能力闸** | DSH **同样不做任务级能力注册表**：只在**结构性不可行**上派发前拒（`prepareCall` 抛 `UNSUPPORTED_REASONING_EFFORT` / `NO_ADAPTER`，`llm/llm/src/index.ts:885-995`），其余靠**降级**（images→text，`:1078-1082`）与**工具级报错**（无图模态时 `fs/tool-fs/src/read-image.ts:129-137` 直接错） | A 的**方向被标杆印证**；口径应写明「**结构性标记**（需读原始附件/需本机执行）⇒ 拦派发」，不承诺「任务级能力判定」 |
| **B 收敛熔断** | 同类物 `repeat-tool-reminder` 只在 3/5/8 次**相同调用**时注入纠正，**仅劝告、从不否决**（`guard/repeat-tool-reminder/src/index.ts:36,78-92`） | 我方 B **比标杆硬**（真熔断）；**判据建议升级**：从「同因驳回次数」改为「**结构化轮报里 `status=blocked` 或 `nextSteps` 重复**」——比数驳回次数更早、更准（与 `REF-8.2.2` 同源） |
| **C 产物接地校验** | 标杆 `present` 只查存在性、不查接地 | **无参考物可抄**，仍是自建；维持「暂不立项」 |

> **一句判语**：标杆在「能力门 / 证据核验」上**比我方松**（不做任务级能力判定、不做交付核验），在「自报阻塞 / 装配收敛」上**比我方紧**（结构化 blocked + N 轮门槛、替换语义）。**要抄的是后两条；前两条我方已更严，别被带松。**
> **2026-10-13 补**：本节 A/B/C 三行判据的**执行形态对照**（含「我方重派链已完整、缺自动触发」与合法性双口径）已收于 §9.4；标杆模态字段的**三值语义**与 `outputModalities` 缺位见 §9.6。

### 1.6 DSH 的「角色形态」与 LLM 全生命周期（本地 checkout `0.2.1-alpha.2`）

> **复核缘起**：① 与外部样本（WorkBuddy 的「调用 skills 后的内部 LLM 上下文」，用户提供）对照发现我们**缺专业角色面**（人设）；② 我们的上下文是「一次拼装、全程不变」，而标杆是「每轮/每步重装配」。本节把标杆的**角色形态**与**全生命周期节点**核准到可借鉴粒度。
> **数据源**：DSH 官方文档 `docs/agent-lifecycle.zh.md`（时序图）+ `docs/architecture.zh.md#turn-flow`（节点文字版）+ `packages/preset/**` 源码（代码抽样验证，非仅文档）。
> **候选工作项**（**尚未登记**，待裁定）：`REF-8.1.2`（persona 槽位）/ `REF-8.2.3`（上下文预算与压缩）/ `REF-8.4` 挂点细化（拦截链 = 两个 waterfall 挂点）。

#### 1.6.1 角色 = persona（身份面）+ preset（能力面），**不是** role 枚举

| 面 | 机制 | 证据 |
|---|---|---|
| **身份面：persona** | 两个**固定槽位**：`deployment:persona-prefix`（顺序 **−1000**，在 harness identity 之后/固定顺序第一位）与 `deployment:persona-suffix`（**10200**，最后一位）；插件配置项 `personaPrefix` / `personaSuffix`（`{{variable}}` 严格引用） | `packages/core/system-prompt/src/index.ts:181-189,253-268`；槽位序见 `SECTION_ORDERS`（同文件 `:131-165`） |
| **单 agent 人设** | `@deepseek-ai/dsh-persona` 包：**preset 挂载**该行 → 注册 prefix/suffix **遮蔽部署级默认**；还可把 prefix 变成**该会话的完整系统提示词**（抑制其他所有段落）并为该会话**关闭动态 runtime-context 快照**。文档原话：**「没有这一行，preset 能改变 agent 的工具，却永远改不了它的身份。」** 挂载有纪律：必须在 preset 内挂——全局挂会与提示词注册表自身的人设注册**相撞并明确报错**（人设已有归属） | `packages/preset/persona/README.zh.md` |
| **能力面：agent preset** | 普通 YAML 声明：`id`/`plugins`（必填）+ `name`/`description`/`order`（roster 位次）；出货 ids = `standard` / `ptc` / `minimal` / `cordis`（`presets/<id>.patch.yml`）。**Host 共享 Agent loop；每个 Agent 只看到自己选定 revision 的 tools/prompts/skills**；registry 管选择 + **修订保留** + profile 编辑；声明提前加载，**改配置只影响之后创建的 Agent** | `packages/preset/agent-preset/README.zh.md:1-25`；格式细则见其 `skills/editing-cordis-compositions/SKILL.md` |
| **无编排角色枚举** | 全库扫描 `packages/preset` 与 `packages/subagent`：`role` **只**出现在 LLM 消息的 `system`/`user`/`assistant`/`tool` 上——**没有** PLANNER/EXECUTOR/REVIEWER 这类编排角色。协作由 `agent-team`（持久 roster + 任务状态 + inbox）与 `subagent`（子 agent 自带路由/推理档 + `toolFilter`/`maxDepth`）承担 | 全库 grep（本机 checkout） |

> **落到 HelloAI**：我们的 `AgentRole`(PLANNER/EXECUTOR/REVIEWER) 是**平台编排角色**，标杆不需要（它是"一个人的 harness"，无编排层）—— 这层**不必抄**。但 **「agent 的身份面」我们是硬编码亏损**：`AgentRuntimeContextAssembler.buildSystemPrompt:867-874` 写死「你是 HelloAI 平台的子任务执行者（Agent: X）」**且不接受覆盖**；标杆是「部署默认 persona + 按 agent 遮蔽」。**收敛动作候选 = `REF-8.1.2`**（persona 两槽位：来源可为 Agent 行字段或技能包 frontmatter；默认来自平台、按 agent 遮蔽——照抄其挂载纪律）。

#### 1.6.2 LLM 全生命周期节点（turn/step 两层：14 节点 + 2 条旁路）

> 语义：**一个 step = 一次模型请求 + 它调用的工具**；**一个 turn = 0..N 个 step**，在领取首条输入前打开、不再欠工作时关闭。

| # | 节点 | 运行方式（机制） |
|---|---|---|
| 1 | `followup(content)` | 入 inbox（`agent/inbox/spliced` / `inserted`，**实时**事件） |
| 2 | 唤醒 driver | 排队工作唤醒 → `agent/status: running` |
| 3 | **`turn/start`** | **认领** next-step input + 一条排队消息（`agent/inbox/claimed`） |
| 4 | **`system-prompt/assemble`** | 固定槽位顺序组装 + 工具 schema —— **每轮/每步重装配**（非一次拼装） |
| 5 | **`agent/pre-step`（waterfall）** | 决定「**接纳**哪些消息」：`reject` \| `enter(messages, startsRequestSeries?)`；**拒绝 → 关闭不含 step 的轮次** |
| 6 | `step/start` | 持久事件 |
| 7 | **`agent/request`（waterfall）** | 可改写请求（换路由/加护栏） |
| 8 | **`llm.prepareCall(config, signal)`** | 解析**实际路由与能力**；任一异步阶段取消 ⇒ system/users **都不提交** |
| 9 | 同步准入 | `system/message`（按节点顺序协调）→ `user/message`（逐条、**只准入一次**）→ 按需 `request/header`/`request/context` |
| 10 | **派生并冻结请求** | `deriveMessages()` 从会话日志投影历史 → 冻结（铁律「**模型可见 ⟺ 已记录**」） |
| 11 | **流式** | `llm/stream` waterfall → 瞬态 `agent/assistant-stream` chunk*；提交 `assistant/message`（**内嵌**精确 stream）/ 失败 → `assistant/attempt`（**不进历史**）；空内容不派生 |
| 12 | **工具流水线** | 按 `executionMode` 分类 → barrier + **有界滚动池并发** → `tool/call` →（有序 pre / 并发 execute / 有序 post）→ `tool/result`；失败 step 记录**缺失的工具结果** |
| 13 | `step/end` | 还欠工具 or 来了新输入 → 认领新批次 → **回到 5**（同轮继续） |
| 14 | **`agent/turn-stopping`（serial）→ `turn/end` → `status: idle`** | 终态检查点（自然停止且 inbox 空时） |

**两条旁路（对我们最有借鉴价值）**

- **压缩**：`compaction-basic` 在**派生请求之前**经 `agent/pre-step` 处理压力（**不等溢出**）；`agent/request-error` 只服务"规范的上下文溢出"。顺序 = **先剪工具结果**（head/middle/tail + 标记）→ **再选摘要**；恢复在**仍打开的 step 内**，且**只有 surface replacement generation 推进才重试**。
- **重试**：提供方级失败 → `agent/request-error` 返回 retry 或保留原错；重试**不重跑 assemble、不重跑 pre-step、不重新准入 users**，只重新 prepare + 协调同一渲染组装。
  ⇒ 标杆把「**step 内重试**」与「**内容级返工**」分开：后者根本不在循环内（靠新输入 / goal 轮 / Ralph 外层）。我们目前是「评审驳回 → 整链重跑」——平台级返工保留，但**缺「step 内重试」这一档**。

**事件分域**：**持久**（`turn/*`、`step/*`、`system/message`、`user/message`、`assistant/message`、`assistant/attempt`、`tool/*`，广播于 `session/event`）vs **实时**（`agent/*`，携带活跃 Agent）；**waterfall** = `agent/pre-step` / `agent/request` / `llm/stream` / `tools/pre|execute|post`；**serial** = `agent/turn-stopping`。SDK 取回放读 `session/event`，实时 UI 读 `agent/assistant-stream`。

#### 1.6.3 与 WorkBuddy 样本的对照（「调用 skills 后的上下文」形态差）

| 样本里的段 | HelloAI 现状 | 判定 |
|---|---|---|
| 角色人设（「后端工程师白客」） | 「平台子任务执行者（Agent: X）」，**不可覆盖** | **缺**（= 1.6.1 的 `REF-8.1.2`） |
| 派发人 / 汇报对象（主理人 → `team-lead`） | 无（上游 = 平台） | 平台编排语境不同，**不必抄**；但「向谁回填什么」我们已有 EXECUTION_RECORD 承接 |
| 背景 / 目标 / 文件清单 | 子任务四要素（内容/交付物/验收/约束） | **结构等价**，且我们多了验收标准 + 依赖产出参考 |
| 行为规格（**优先级从高到低**） | 验收标准 + 约束（**无显式优先级序**） | 可借：约束段加「硬约束 > 软要求」的显式序 |
| 硬约束（违反即返工） | systemPrompt 仅一句「不得伪造」 | **平台级硬约束未成段**——应放**稳定面**（systemPrompt），与 persona 同批改 |
| 交付要求（汇报含哪些字段） | EXECUTION_RECORD + **VERIFICATION 围栏**（`REF-8.2.2` 落点） | **我方更结构化**（证据/STATUS/NEXT_STEPS） |

#### 1.6.4 落到 HelloAI：五条可借（按性价比）

| # | 借鉴点 | 我们的现状 | 落点（候选编号） |
|---|---|---|---|
| 1 | **persona 两槽位**（默认来自平台、按 agent 遮蔽；挂载纪律：不许全局挂） | `buildSystemPrompt` 首句硬编码且不可覆盖 | 落点 `REF-8.1.2`（来源：Agent 行字段 或 技能包 frontmatter） |
| 2 | **拦截链 = 两个 waterfall 挂点**（`pre-step` 管准入/拒绝/压缩触发；`request` 管改写请求） | `REF-8.4` 现表述为「Advisors 唯一挂点」（单点） | **`REF-8.4` 挂点细化**：**A 能力闸**天然属 `pre-step`（接纳这批输入时否决）；**B 收敛熔断**更贴 `turn-stopping` / `request-error` 侧 |
| 3 | **上下文预算的触发时机与顺序**（装配前查预算 → 先剪工具结果 → 再摘要；重试不重装配） | 无预算/无压缩，只有分段硬截断（依赖段 64000 字符） | 落点 `REF-8.2.3`（两道 + 两触发 + 审计事件；锚点 `G-021`） |
| 4 | **技能正文尺寸纪律**：出货 `SKILL.md` 渲染后 **< 8192 字符**（与工具结果修剪阈值协同） | `internal_load_skill` 拉全文，**无尺寸约束** | 技能包侧加正文上限（或装配侧按预算截断）；与上下文预算同批 |
| 5 | **可版本化 preset**（改配置只影响之后创建的 Agent + 修订保留） | 改 agent 配置**立即生效且不可追溯** | 登记即可（不急；与 §8 jar 化同批评估） |

> **与 §1.5 的关系**：§1.5 解决「执行侧到评审侧的**契约同源**」（结论已立行动层条目）；本节解决「**身份面 + 装配时机**」。两条线共同指向同一个判断：**我们的循环本体不缺，缺的是"装配层与挂点"**——与 §3「收敛动作都改在装配层、不动循环本体」的结论一致。

### 1.7 影响面评估与实施清单（"抄之前先看会碰什么"）

> **用途**：把「哪些候选会碰架构 / 状态机 / 业务流程」一次摆清，作为**逐项实施前的影响预判表**（只答「会碰什么」，不答「做到哪一步」）。

#### 1.7.1 逐候选影响面

| 候选 | 架构 | 状态机 | 业务流程 | 判定 |
|---|---|---|---|---|
| **persona 槽位**（`REF-8.1.2`） | 装配层 1 处 + Agent/技能字段 | **无** | 无（prompt 多一段） | 装配层改动，零状态机影响 ⇒ 可抄 |
| **技能 `role` 属性** | 技能 frontmatter + `SkillPackage` 加字段 + 装配读取 | **无** | 无 | 放心抄 |
| **上下文预算/压缩**（`REF-8.2.3`） | 装配前挂点 + 工具结果剪枝/摘要 | 无（只产审计事件，不改状态） | 无（省 token、防打爆） | 装配层改动，零状态机影响 ⇒ 可抄 |
| **技能正文长度纪律**（<8192） | 校验规则 + 技能包 | **无** | **无** | 放心抄（与压缩修剪阈值耦合） |
| **拦截链两挂点**（`REF-8.4` 细化） | 循环外层挂点（Advisors） | **潜在**：A 能力闸「否决」若落在循环内 ⇒ 多一条状态迁移 | 可能影响派单成功率 | 抄挂点**位置**，否决内容留**派发层**（复用 V27.1 先例） |
| **step 内重试**（1.6.2 旁路） | 循环内 | ⚠️ **禁止**挂平台状态机（必须与「评审返工」严格分开；混起来会改 `REWORK`/`reassign_attempt_count` 口径） | 无 | 抄，但划红线 |
| **会话单源投影**（§3 动作①） | **唯一有结构成本的一条**：数据写路径（提升现有 `agent_event`） | 无（同一状态、换投影面） | 审计/回放增强 | 已降支撑项；低成本可做 |
| **可版本化 preset**（1.6.4-5） | **新增数据层 + 管理面** | 按「只影响之后创建的 Agent」⇒ 无；若做成「运行中切换 revision」**会碰状态机** | 能力变更可追溯 | 后置；**别做运行中切换** |

#### 1.7.2 两条红线（实施时逐条核对）

1. **循环内的事（准入 / 重试 / 压缩）不得直接改平台状态机**——平台状态机只能由「循环结束后的回写」驱动；我们已有这条**唯一边界**：`ExecutionResultHandler.handleReport`（其"状态非 IN_PROGRESS 即丢弃上报"守卫已两次证明能兜住新路径）。
2. **拦截链抄"挂点位置"，否决内容留在派发层**——能力闸/熔断的判定复用 V27.1「拒派 + 标人工介入」先例，不要在循环内新造否决路径。

#### 1.7.3 影响面的验证方式（不写进度）

- **已实施项的影响实测证据**：见 `doc/log/` 对应 `LOG-` 条目（过程记录的唯一载体）；
- **待实施项清单（含落点 / 验收 / 依赖顺序）**：见 `plan/HelloAI 借鉴落地实施计划.md` §10.3 追加注与差距表锚点（`G-021` / `G-022`）；
- **本节只回答「会碰什么」（影响面与红线），不回答「做到哪一步了」**——后者以差距表为唯一事实源（治理规则 §3.3/§3.6）。

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

**DSH（本地实测 `E:/workspace/deepseek-harness-master`；§1.5 复核用检出 `deepseek-harness-master` 版本 `0.2.1-alpha.2`）**
- 调用链/终止/预算（§1.5.1）：`packages/core/agent-loop/src/agent.ts:214,252-254,298,361-365,400,409,453,547-555,564,604`、`tool-calls.ts:158`、`session/src/types.ts:201-232`、`goal/goal/src/index.ts:244`、`experimental/tool-ralph/src/index.ts:35`
- 装配槽位/替换语义（§1.5.2）：`system-prompt/src/index.ts:131-165,313,578`、`agent.ts:274-287,277-282,411-440,426`
- blocked 通道（§1.5.3）：`goal/tool-goal/src/index.ts:39,301-318,319-330`、`goal/goal/src/types.ts:45-57`、`goal/goal-round-driver/src/index.ts:165`
- 证据契约/轮报（§1.5.4）：`deliverables/tool-present/src/index.ts:77-108`、`goal-round-driver/src/prompt.ts:20-23`、`experimental/tool-ralph/src/index.ts:89-100,151-174`
- 能力门/降级（§1.5.5）：`llm/llm/src/index.ts:885-995,1078-1082`、`fs/tool-fs/src/read-image.ts:129-137`、`guard/repeat-tool-reminder/src/index.ts:36,78-92`
- 会话铁律：`packages/core/session/src/index.ts:876`、`surface.ts:7`
- **角色形态（§1.6.1，`0.2.1-alpha.2` 复核）**：`packages/core/system-prompt/src/index.ts:131-165`（`SECTION_ORDERS` 槽位）/ `:181-189`（`PERSONA_PREFIX_SECTION` / `PERSONA_SUFFIX_SECTION`）/ `:253-268`（`personaPrefix` / `personaSuffix` 配置）；`packages/preset/persona/README.zh.md`（单 agent 人设 + 遮蔽 + 挂载纪律）；`packages/preset/agent-preset/README.zh.md:1-25`（preset YAML / revision / roster）
- **全生命周期（§1.6.2）**：`docs/agent-lifecycle.zh.md`（时序图，14 节点）、`docs/architecture.zh.md#turn-flow`（节点文字版）、`:110-116`（事件分域 + waterfall/serial 纪律）、`:122-130`（会话日志 ↔ `deriveMessages()` 投影）
- **技能尺寸纪律（§1.6.4-4）**：`packages/preset/agent-preset/README.zh.md:50`（出货 SKILL.md < 8192 字符，与工具结果修剪阈值协同）
- 技能目录+惰性加载：`packages/skill/tool-skill/src/index.ts:81-160,215-251,319-321`；发现 `packages/skill/skill-filesystem/src/index.ts:149,730`
- 工具 scope 分层：`packages/core/tools/src/index.ts`（`view(scope)`）
- 能力来源：`docs/architecture.zh.md:106`
- 沙箱 fail-closed：`packages/sandbox/sandbox/src/index.ts:153-158`

**DSH（`0.2.1-alpha.2`；§9 执行形态复核）**
- 执行形态（§9.1）：agent 仅 idle/running；**无任务级换模型** —— `packages/core/agent-loop/src/agent.ts:575,594,604`、`session/src/types.ts:201-232`
- 模型模态词表与三值语义（§9.6.1）：`packages/llm/llm/src/types.ts:230-237`（`ModelModalityMap` = text/image，merge-extensible）、`:339-340`（`inputModalities` 缺省 = **未知** ≠ 负面能力）；`llm/llm/src/index.ts:177,733-750,1078-1081`；**全仓 `outputModalities` 0 命中**
- 工具侧硬拒（§9.1）：`packages/fs/tool-fs/src/read-image.ts:120-131`
- 结构性抛错（§9.1.2）：`packages/llm/llm/src/index.ts:885-995`

**octop-harness（本地实测 `E:/workspace/octop-harness-main`）**
- 装配一次+逐 turn：`src/octop_harness/agent.py`；Octop `agents/manager.py:3068-3429,3306-3318,3360-3389`
- 逐 turn 再装配/重编：`agents/manager.py:2145-2175,2695,2707,3041-3066`
- 中间件排序：`agents/manager.py:3226-3243`；基类 `langchain.agents.middleware.AgentMiddleware`
- 后端抽象：`backends/workspace.py:356`

**HelloAI（本仓实测）**
`agent/runtime/loop/impl/ChatModelToolLoop.java:58,85,100-123,147-152` · `agent/runtime/loop/AgentLoopInput.java:47` · `agent/tool/{ToolRegistry,ToolContext}.java` · `agent/tool/impl/ToolRegistryImpl.java` · `agent/skill/**` · `agent/service/impl/AgentMcpServerServiceImpl.java:267-275` · `agent/service/AgentRuntimeContextAssembler.java:135-136,140-144,155-159,213,629-696` · `agent/session/entity/AgentSession.java` · `agent/event/AgentEventRecorder.java` · `ExecutionResultHandler.java:86-100,316-331` · `ExecutionArtifactServiceImpl.java:106-148` · `agent/runtime/sandbox/**` · `agent/executor/ApiKeyAgentExecutor.java:42-64`

**HelloAI（§9 追加：执行形态 / 状态机 / 能力鉴别）**：`task/service/impl/SubTaskServiceImpl.java:792-822`（`rework` 共享预算）、`:1008-1039`（`block` 三态守卫 + `SUB_TASK_REPORT_BLOCKED` / `source=agent_report`） · `task/service/impl/SubTaskDispatchServiceImpl.java:67,70-95,795` · `helloai-job/.../task/AgentHealthCheckTask.java:71,98,317-320`（**巡更先例**） · `agent/event/AgentEventContextResolver.java:29-42` · `common/config/AgentDispatchProperties.java:70` · `agent/AgentSkillDeriver.java:45-46,127,150` · `agent/service/AgentSkillPolicyService.java:53-67` · `agent/chat/AgentProviderResolver.java:56-66` · `db/migration/V49__llm_provider_model_table.sql:10-11` · `V52__llm_provider_model_capability_skills.sql:7-8` · `api/controller/AdminLlmProviderController.java:300-319`

---

## §7 局限说明

1. 本报告是**调研后的调整思路**，不是设计稿——收敛动作 ①②③④⑤ 的**具体实现结构（新类、接口签名、包归属）需在转 `plan/` 时再细化**，本报告只定「方向与改在哪层」。
2. 引用行号以当前本地 checkout 为准（§1.1/§1.2 按 DSH `0.1.0-rc.8`、octop-harness `1.0.2`；**§1.5 / §1.6 已按 DSH `0.2.1-alpha.2` 重新定位**——两版之间行号会漂，架构意图未变）——版本迭代快，落地时按当时代码重新定位。
3. §1.6 的**节点表以 DSH 官方文档（`docs/agent-lifecycle.zh.md` / `architecture.zh.md`）为主源**，代码抽样验证限于 persona/preset 与槽位常量；事件名与挂点纪律如与实现有出入，以当时代码为准。
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
---

## §9 DSH ↔ HelloAI 执行形态 / 状态机 / 能力鉴别 比对（2026-10-13 追加）

> **缘起**：承 §1.5（循环内部机制）与 §1.6（身份面 + 装配时机），本条补上**第三条线**——「内部 LLM 遇到自己**做不到**的活，怎么让它说出来、说出来之后**由谁处置**」。这条线在 `LOG-20261011-007` 立为 A/B/C 判据，本条把它与标杆的**执行形态**摆在一起核。
> **口径**：DSH 为本机检出 `E:/workspace/deepseek-harness-master`（`0.2.1-alpha.2`）实测，HelloAI 为本仓实测，均带 `path:line`。
> **治理约束（先声明）**：按 `doc/文档体系分类与治理规则.md` §3.6，**`research/` 只写形态 / 事实 / 判据，不写排期与进度**。因此本节**只回答「两边长什么样、差在哪、判据是什么」**；**裁定与在办项**由 **§9.8 指针表**路由到 `plan/` 与 `doc/log/`——本文件**不二次记账**。

### 9.1 DSH 一侧的真实形态

#### 9.1.1 三个关键形状

| 形状 | 事实 | 证据 |
|---|---|---|
| **agent 状态极简** | `AgentStatus` 只有 `idle` / `running` 两态；**没有**「能力不足 / 阻塞 / 返工」这类业务态。`blocked` 不在 agent 状态里，而在**会话终态枚举**里 | `packages/core/agent-loop/src/agent.ts`（`AgentStatus`）；`session/src/types.ts:201-232`（终态枚举含 `blocked`） |
| **不做「任务级换模型」** | 循环在**构造时声明单一 route**，请求期只做**配置级改写**；全库 grep `anotherModel` / `nextModel` / `switchToModel` / `fallbackModel` / `modelFallback` / `moreCapable` **0 命中**。换执行者只有一条路：**委派 `subagent`** | `agent-loop/src/agent.ts:575`（声明 route）、`:594`（`agent/request` 配置级改写）、`:604`（`prepareCall` 只绑一个 adapter） |
| **「做不到」= 「拒」与「降」并存** | **工具侧硬拒**：读图前先断言路由能力，模型未声明 image 输入 ⇒ **抛错**；**LLM 侧软降**：消息里的图片被投影成文本 | 拒 = `fs/tool-fs/src/read-image.ts:120-131`；降 = `llm/llm/src/index.ts:1078-1081` |

#### 9.1.2 五个「做不到」可挂的扩展点

| 扩展点 | 位置 | 能做什么 | 是否迁移业务状态 |
|---|---|---|---|
| `agent/pre-step`（waterfall） | 装配前 | 接纳 / **拒绝**本批输入、触发压缩 | 否（只关轮次） |
| `agent/request`（waterfall） | 请求前 | 改写请求（换路由 / 加护栏） | 否 |
| `llm/stream`（waterfall） | 流式 | 观察 / 改写 chunk | 否 |
| 工具结果携带 `concludesTurn` | step 内 | 用**工具结果**结束本 turn（终止性工具） | 否 |
| `update_goal{action:"blocked", blocked_reason}` | goal 层 | **模型自报阻塞**（带连续 N 轮门槛，默认 3） | 是，但改的是 **goal phase**，非 agent status |

> **另有一条「结构性抛错」**：`prepareCall` 对结构性不可行直接抛 `UNSUPPORTED_REASONING_EFFORT` / `NO_ADAPTER`（`llm/llm/src/index.ts:885-995`）——**它是抛错，不是选人**：标杆没有「驳回 → 重新选派」这个动作。

### 9.2 HelloAI 一侧的真实形态

| 形状 | 事实 | 证据 |
|---|---|---|
| **子任务状态机 11 态** | 有 `BLOCKED` / `REWORK` / `DEAD_LETTER` 等业务态——**比标杆多一整层**（标杆把这些放在会话/goal 上） | `SubTaskStatus` 枚举 |
| **两条「能力不足」出边（共享同一预算）** | ① `REVIEW → REWORK`：**须先进评审**，`reworkCount+1` **且** `attemptTotal+1`；② `IN_PROGRESS/ASSIGNED/REWORK → BLOCKED`：**不进评审**，`reworkCount` **不变**。上限 `helloai.dispatch.max-reassign-attempts`（默认 **5**），`Turn = 1 + reworkCount + attemptTotal` | `task/service/impl/SubTaskServiceImpl.java:792-822`（`rework`，注释 `LOG-20260904-007` 明写「返工与重派同源预算，杜绝 3×5=8 次叠乘」）、`:1008-1039`（`block`，守卫只允许三态，事件 `SUB_TASK_REPORT_BLOCKED` + `source=agent_report`）；`common/config/AgentDispatchProperties.java:70`；`agent/event/AgentEventContextResolver.java:29-42` |
| **换人重派链已完整存在** | 闸门（熔断 + 退避 `{60,180,600,1800}`）→ `accumulateReassignAttempt` → `resetToPendingForDispatch(BLOCKED)` → `assignNext`；退避时钟 = `sub_task.last_attempt_time`（**仅**由 `incrementAttemptTotal` 原子写） | `task/service/impl/SubTaskDispatchServiceImpl.java:67`（退避表）、`:70-95`（`dispatchBlockedSubTask`）、`:795`（计数） |
| **但入口全是人工** | `dispatchBlockedSubTask` / `redispatchInProgress` 现存调用方**只有** `SubTaskController` 的两个 HTTP 端点 | `helloai-api/.../SubTaskController.java:341,365`；`:758` 自述「人工入口」 |
| **★但「巡更式自动重派」已有活先例** | `AgentHealthCheckTask` 是 `@Scheduled(fixedRate = 60000)` 的定时 Job，**自动**对离线 agent 名下子任务走重派链（首选 `redispatchOfflineSubTask`、补偿 `dispatchPendingSubTaskCompensating`，两层） | `helloai-job/.../task/AgentHealthCheckTask.java:71,98,317-320` |

> **一句判语**：标杆**没有「驳回到选人」这一层**（它的 agent 只 idle/running，换人靠新派 subagent）；HelloAI **有**这一层而且**链已完整**，缺的只是「谁来触发」。而「谁来触发」在**本仓已有先例**（`AgentHealthCheckTask`）——这不是新发明，是**复用既有巡更形状**。

### 9.3 逐项形态比对

| 维度 | DSH | HelloAI | 形态差 |
|---|---|---|---|
| 执行者状态 | 2 态（idle/running） | 11 态子任务机 | 我方多一层业务态（**不是缺陷**，是编排平台与单 agent harness 的定位差） |
| 「做不到」的表达 | 模型自报 `blocked`（goal 层，N 轮门槛）/ 工具抛错 | 写进正文 → 被物化成交付物 → 送审 → 驳回 → 死信 | **缺「说出来」的通道**（= `REF-8.1.1`） |
| 换人 | 委派 subagent（新起一条链） | `REWORK`（进评审）或 `BLOCKED`（不进评审）→ 重派链 | 我方两条出边**语义已分好**，链已完整 |
| 谁触发换人 | 由调用方再派 | 现状**仅人工**；巡更先例存在 | **缺自动触发**（形态可选，见 §9.4） |
| 预算 | 循环内无预算，全在外层（goal rounds / retry） | 循环内 `maxIterations`；重派预算 `maxReassignAttempts` | 两侧都有上限，口径不同 |
| 模型事实源 | **adapter 运行时解析**（可异步查厂商），静态目录仅 advisory | **静态表**（`llm_provider_model`），运行时不回查厂商 | 见 §9.6 |
| 模态词表 | `text` / `image`（**merge-extensible**），**无 output 模态** | 无模态列；模态挂在 `agent.capabilities`（**人工填写**） | 见 §9.6 |

### 9.4 能力鉴别的落点形态（方案 C）

**用户裁定的方向**：**发现放内、处置放外**——内核只产出「缺什么」，处置走平台既有重派链。

```text
内部 LLM 报能力缺口（adds: internal_report_blocked / 结构化轮报）
        │  ← 循环内，只「产出」，不改平台状态
        ▼
   SubTaskStatus.BLOCKED        ← 复用既有出边②（不进评审、reworkCount 不变）
        │
        ▼
   既有重派链：熔断 + 退避 + 选人 + fallback
        │  （SubTaskDispatchServiceImpl.dispatchBlockedSubTask）
        ▼
     PENDING → ASSIGNED …       ← 状态机不变、轮次不变
```

| 落点方案 | 内容 | 合法性判定 |
|---|---|---|
| **A 循环内直接否决/改派** | 在 `ChatModelToolLoop` 内部拦下并换人 | ✗ **违红线**：自动循环机制不得改平台状态机 |
| **B 内核只抛错** | 内核抛异常，外层不做处置 | ✗ 悬空：异常不等于「换人」，任务会烂在原地 |
| **C 发现放内、处置放外（推荐）** | 内核用**显式工具动作**（`internal_report_blocked`）落 `BLOCKED`，平台既有重派链接手 | ✓ 合法：**显式工具动作**可改状态机，「换人」属派发层 |

**★合法性双口径（唯一标尺）**：
- **显式工具动作**（如 `internal_report_blocked` → `subTaskCommandPort.block`）**可以**改平台状态机——它是「模型做的动作」，与人点按钮同构；
- **自动循环机制**（准入 / 重试 / 压缩）**不可以**——它不能替平台做状态决策。

**红线②的四条机械判据**（判断一个策略「是否本就属派发层」）：
1. 能否在 `ResilientDispatcher` 的 **`assignNext`（提交点）之前**拦下？
2. 是否**需要撤销**任何已提交的状态迁移？
3. 已花掉的 LLM 成本是否为 **0**（还没开始执行）？
4. 是否**复用**既有事件 + `markManualIntervention` 语义（不新造一套）？

> **一句话**：**能否在 `assignNext` 之前拦下——能，则该策略本就属派发层。** 方案 C 正是「拦在派发层」，因此不碰循环本体。

**自动触发方式（形态可选，属 §9.8 待裁定项）**

| 形态 | 说明 | 本仓先例 |
|---|---|---|
| 同事务触发 | 落 `BLOCKED` 的同一事务里直接调重派 | 无（会与 `@Transactional` 边界纠缠，退避闸门读不到新 `last_attempt_time`） |
| **巡更触发（推荐）** | 定时 Job 扫 `BLOCKED` 且在重派窗口内的子任务，走既定链 | ✓ **已有**：`AgentHealthCheckTask`（`@Scheduled(fixedRate=60000)`，自动重派离线 agent 名下子任务） |

> 注：`SubTaskDispatchServiceImpl:60-66` 的注释记录了**同一类坑的历史实例**——退避时钟曾误用 `update_time`，而 `block()` / `resetToPendingForDispatch()` 都会 `updateById` 刷新它，导致「换人」入口先 `block()` 再重派时**闸门读到被自身刷新的 now，100% 静默失败**。⇒ 自动触发若走「同事务」形态，会重踩这个坑。

### 9.5 A/B 两个坐标系：是**引用关系**，不是「同一词表」

**订正**（用户指出后修正）：平台里有**两个永不相交**的命名空间，但二者之间**必须有引用边**，否则等于默认「任何模型都能跑任何技能」。

| 坐标系 | 载体 | 值域（实测值） | 源 |
|---|---|---|---|
| **A 能力谓词** | `agent.skills` | `{shell, docker, sql, web-search, code-review, python, java, thinking}` | `LlmProviderModel.capabilitySkills` / `availableOptionalSkills` |
| **B 技能条目** | `task.required_skills` | `eng-verification` / `eng-code-review` / `eng-doc-standard` / `eng-web-research` | `SkillPackage.name`（planner 按技能目录选的就是这个） |

**正确关系**：`B.requires ⊆ A.provides`（**引用边**），**不是** `A ∩ B`。

**「解耦」的准确含义**：技能**不指名**某个模型（所以 `deepseek-flash` 与 `claude` 都能挂同一份说明书），**不等于**技能**不声明要求**。`deepseek-flash` 跑不了「识别图片文字」的技能——不是因为它不认识那个技能，而是**它不满足该技能的模态前置**。

**当前断层（= `G-022`，P1）**：

| 环节 | 实测行为 | 后果 |
|---|---|---|
| 技能推导 | `AgentSkillDeriver.STANDARD_SKILLS` 只有 8 个**固定值**（`agent/AgentSkillDeriver.java:45-46`） | A 坐标系被**硬编码**，写不进 B 系的值 |
| 注册闸门 | `AgentSkillPolicyService.validateAgentSkills:66-67` 先按 `STANDARD_SKILLS::contains` 过滤 ⇒ **完全看不见 B** | 送进来的 `eng-*` 被静默丢弃 |
| 选人匹配 | `AgentSelector.pickFromCandidates` 做 `A ∩ B` | **恒为空** ⇒ 子任务永久 `PENDING`（实测：全平台 0 个 agent 持有 `eng-*`） |
| 同义归一 | `SkillNormalizer.matches:66-73` = `normalizedAgent.containsAll(normalizedRequired)`；唯一的「关系表」是 `synonyms():75-97`（`bash`/`powershell`/`脚本` → `shell`） | 平台**只建模同义，从不建模蕴含** |

> **缺的那条边**：需要一个 `B.requires ⊆ A.provides` 的**引用**判定。`requiredTools` 反推**只能覆盖内置 4 个技能中的 1 个**（只有 `eng-web-research` 声明了 `[web_search, search_knowledge]`，另 3 个 `requiredTools: []`），且它的维度是**工具依赖**而非**模型能力** ⇒ 需要技能侧显式声明（`required-base-capabilities` 之类），但那是 **`SkillPackage` 8 键白名单 → 9 键的契约变更**（`SkillPackageParser.KNOWN_KEYS:33-35`），须四路协同。
> **更深的阻塞**：A 侧的值**不是模型声明的，是人填的**——`AgentAccessType.defaultCapabilities:85-88` 对图片/音频/视频三类统一默认 `false` 并注明「模态理解能力取决于底层模型而非接入通道，注册时可覆盖」，覆盖入口在 `AgentController:257-267`（`AgentCapability.mergeDefaults:37-43` 只 `putAll`，**无词表/拼写校验**）。⇒ **有入口，但无词表校验、无模型对照**。

### 9.6 模型能力事实源的形态

#### 9.6.1 标杆：运行时解析 + 三值语义 + 只有 input 没有 output

| 事实 | 证据 |
|---|---|
| 模型信息由 **adapter 运行时解析**（可异步查厂商）；静态目录仅 advisory，不控制路由 | `llm/llm/src/index.ts:733-750`（`resolveModelInfo` → `registration.adapter.resolveModel(...)`）；`:729-731` 注释「catalog membership remains advisory and does not control request routing」 |
| 模态词表 = `{text, image}`，且**可由插件 merge 扩展** | `llm/llm/src/types.ts:230-237`（`ModelModalityMap` 注释「Merge-extensible provider model modality vocabulary」） |
| **三值语义**：`inputModalities` 缺省 = **未知**（不是「没有」）；显式省略才是负面能力 | `types.ts:339-340` 注释原文「absent means unknown, while an explicit omission is negative capability」 |
| **两层判据不同口径** | 工具侧（硬拒）：`undefined \|\| !includes('image')` ⇒ **抛错**（fail-closed，`read-image.ts:129`）；循环侧（软降）：`!== undefined && !includes('image')` ⇒ **才降级**（`llm/llm/src/index.ts:1078-1079`）——缺省时**不降级**，交给提供方失败 |
| **全仓无 `outputModalities`** | grep `outputModalities` ⇒ **0 命中** |

> ⇒ 「输出模态」这条判据**在标杆处没有先例**，是 HelloAI 自有的（见 §9.7）。而「输入模态」标杆比我们细：它是**三值**（未知 / 显式为空 / 列出），我们是**布尔**（`agent.capabilities` 的 true/false）。

#### 9.6.2 HelloAI：`model_name` 是**官方 API 字段原样透传**

| 字段 | 语义（实测） |
|---|---|
| `provider_code` | **厂商**（决定 baseUrl / apiKey / 协议），**不是模型版本** |
| `model_name` | **调用 API 的官方模型字段**，**无映射层、无别名表、无后缀处理** ⇒ 必须与官方一致 |
| `model_version` | **当前根本不存在该字段** |

**透传链（实测，五步无一处改写）**：`AgentProviderResolver.resolveModel:56-66`（取 `modelType` 中 `:` 之后的子串）→ 工厂 `buildChatModel` 的 `effectiveModel` → `.model(effectiveModel)`（`AnthropicCompatibleProtocolFactory:87-95`；`OpenAiCompatibleProtocolFactory:94`；`DeepSeekProviderChatClientFactory:90`）。

> **★潜在调用级缺陷**：`V49` 种子写的是 `deepseek-v4-flash` / `deepseek-v4-pro`，而官方字段（用户给出）是 `deepseek-flash`（含 `[1m]` 变体）；且 `LlmProviderModelQueryService.isModelAvailable:43` **只查库、从不回查厂商** + 工厂原样透传 ⇒ 一旦库内值与官方不符，症状是「**注册通过、调用失败**」。若厂商要求 `[1m]` 这类后缀，它必须进 `model_name`（或另设变体列）。

#### 9.6.3 `V52` 两列的三种语义（删列前必须拆开）

| 列值 | 实际语义 | 可否被模态列替代 |
|---|---|---|
| `capability_skills = ["thinking"]` | **所有模型都一样** ⇒ 是**平台策略**，不是模型事实 | ✗ 不能替代，须另行安置 |
| `available_optional_skills` 里的 `shell` / `code-review` | **默认值**，零差分信息 | ✓ 可退化为常量 |
| `available_optional_skills` 里的 **`web-search`** | **唯一有差分**的值（deepseek 无；kimi/dashscope/minimax 有）——但它是**工具可用性**，不是模态 | ⇒ 其对应物是 `SkillPackage.requiredTools` + `getEnabledToolsForAccess` |

> **删列前置条件（不能先删）**：`V52` 两列有**两个在跑的消费方**——① `AgentSkillPolicyService.validateAgentSkills:53-68`（**注册闸门的唯一输入**）；② `GET /api/admin/llm-providers/getSkillOptionsByModelType/{modelType}`（`AdminLlmProviderController:300-319`，前端技能勾选框）。先删 ⇒ 闸门落进 `capability == null` 的「**不认识该模型 ⇒ 不校验**」降级放行分支 = **闸门失效**（与 `G-022` 同类）。
> **正确次序**：建模态判据 → 迁移上述两个消费方 → **再**删列。

#### 9.6.4 禁「写死代码表」——本仓已有判例

用户提议「写一个配置表固定死」在**意图**上可行，但**落点必须换**，因为本仓已有判例：

- `V52` 头注原文：「能力映射以**表驱动**落地：`llm_provider_model` 加两列 JSONB，与 V49 模型清单同源，**避免新建静态模板类造成两套事实源漂移**」。
- `V49` 头注原文：「**内置 Provider 模型列表固定，只可选不可改**」+「**自定义 Provider 支持任意模型名称**」⇒ 代码里的静态表**无法表达**自定义 Provider 的任意模型名。

⇒ **正确形状**：`词表进枚举/常量 + 值进数据（种子 + `builtin` 只读） + 「是否在线」为运行时**（`enabled` + `LlmProviderCatalogService.isProviderAvailable`）**。即：把「固定」落在**数据**上，而不是落在**类**上。

### 9.7 粗选的两条独立判据（不可合并）

| # | 判据 | 层级 | 拦谁 | 例子 |
|---|---|---|---|---|
| **①** | `output_modalities ⊇ {text}` = **执行者资格** | **模型级**（技能匹配之前） | 排掉「根本没有文本出口」的模型 | MiniMax Music 3（输出立体声音频）/ MiniMax H3（输出音视频） |
| **②** | `input_modalities ⊇ 任务所需模态` = **技能可派** | **技能级** | 排掉「模型读不了这类输入」的技能 | 「识别图片文字」技能 ⇒ 需要 `input ⊇ {image}`；`deepseek-flash` 只有 `{text}` ⇒ 不可派 |

> **两条为何不能合并**：① 判的是「它能不能当执行者」（与任务无关，只看模型）；② 判的是「这个技能它跑不跑得了」（依赖任务/技能所需模态）。**粗选（派发前 ② 的静态判定）+ 细选（工具级运行时复查）两层都需要**，因为**声明会说谎**（人工误填 / 配置漂移 / 模型版本升级）⇒ 运行时必须按**当前**模型事实再查一次（标杆正是这么做的：`resolveModelInfo` 运行时解析 + 工具侧 `assertImageCapableRoute` 硬拒）。

> **参照样本**：用户提供的 MiniMax 多模态表（音频/视频/图片/文字皆可为**输入**，**输出**只有文本）——它同时印证了 ①（输出侧只看「有没有文本」）和 ②（输入侧看「任务需要什么模态」）两条独立判据。

### 9.8 结论落点（指针表：本文件不记裁定与进度）

| 内容 | 唯一落点 |
|---|---|
| **方案 C 的裁定本身**（发现放内、处置放外） | `doc/log/2026-10.md` 当月 `### 决策` + 差距表条（`D-2026-10-13-x`） |
| **在办项 / 落点 / 验收 / 依赖顺序** | `doc/plan/HelloAI 借鉴落地实施计划.md` §10.7.6（范围见 §10.7.1~§10.7.2） |
| **自动触发方式**（同事务 vs **巡更（推荐）**） | **待裁定** → 裁定后入 `plan` §10.7.6 |
| **可移植性是否纳入本轮 `REF-8.4`** | **待裁定**（建议另立） → 裁定后入 `plan` §10.7.6 或 §8 |
| **A/B 词表是否拆分**（`agent.skills` / `task.required_skills` 混装） | **待裁定**（建议**不拆**，改在闸门内做两段判定，零 planner 改动） |
| **形态甲/乙**（加 3 列后退 V52 两列 **vs** 泛化成能力谓词表并吸收 `capability_skills`） | **待裁定** |
| **`requiredBaseCapabilities` 挂点**（挂 `SkillPackage` ⇒ 8→9 键四路协同；或只做模块级默认） | **待裁定** |
| **当前状态**（已实施 / 未清偿） | **差距表**（唯一事实源）；本文件不记 |
| **逐项实测过程** | `doc/log/2026-10.md` `LOG-20261013-005` ~ `008` |
| **执行流 / 状态机明细比对底稿** | `doc/research/DSH 与 HelloAI 执行流与状态机比对（2026-10-13）.md` |
