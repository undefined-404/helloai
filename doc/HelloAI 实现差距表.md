# HelloAI 实现差距表

> **状态：CURRENT GAP**
>
> 本文只记录**当前 → 目标**的真实差距；**只保留最新状态、随迭代就地更新**，不累加过程记录。
>
> **最后更新：2026-10-09**（本表结构回归「只记当前差距」：原头部 25 条「最后更新」链与 `§0` 订正记录已外迁 ——
> 过程记录与订正历史按「做过什么」归入 `doc/log/`：2026-09 批次见 `doc/log/2026-09.md`（已归档至 `doc/archive/log/`），2026-10 批次见 `doc/log/2026-10.md`。
> Gap 内容最近一次状态变更 = **2026-10-09**：① 新增 `G-018`（备份/恢复）、`G-019`（RAG 知识库）—— 二者此前在活文档零登记，属能力外延的正式立项（`D-2026-10-09-4`）；② **Fork 驱动执行 WONTFIX**（`D-2026-10-09-5`），§2 Event Consumers 次序回到 `Timeline / Replay → Audit → Recovery`，回流登记见 §7.1.2；③ **`G-005` 沙箱降级为「条件触发」**（`D-2026-10-09-6③`，修正原 P1 排序）+ 新增 **`G-020` 工作详情快照**（`D-2026-10-09-6⑤`）
>
> 当前进度**以 §1 总体矩阵为准**；本表是「当前差距」的唯一事实源。

# 0. 当前生效的口径与决策

> 只登记**仍然生效**的口径与决策；过程与订正历史见 `doc/log/2026-09.md`（归档） / `doc/log/2026-10.md`。

**编号口径（全项目唯一，防撞号）**

| 编号 | 含义 |
|---|---|
| `G-001`~`G-020` | 本表差距项（见 §1 总体矩阵） |
| `P0`~`P3` | **架构重构主线阶段**（唯一含义）；审计优先级档已改用 `PR0`~`PR3` |
| `RM1`~`RM13` | 代码质量路线图（与目标架构能力层不再撞号） |
| `B1`~`B5` | 目标架构能力层（阻断项）；现仅 `B2 Sandbox` 未动 |
| `BASE-x.y` | 基础架构专项任务（`plan/HelloAI 基础架构调整实施计划.md`） |
| `REF-x.y` | 借鉴落地专项任务（`plan/HelloAI 借鉴落地实施计划.md`，2026-10-09 建号） |
| `ARCH-YYYYMMDD-NNN` | 架构决策（`log/HelloAI 架构变更记录.md`） |
| `LOG-YYYYMMDD-NNN` | 迭代记录（`log/YYYY-MM.md`） |

> `C-1`~`C-42`（原「差距表订正留痕」编号）**已于 2026-10-09 退役**——差距表订正与产出它的迭代记录同源，不再单独编号；一律改用 `LOG-YYYYMMDD-NNN` 寻址。`doc/archive/**` 内的 `C-NN` 为历史现场。

> **历史分期编号注册表（2026-10-09 登记并冻结）**
>
> 项目历史上**并行存在 6 套互不相同的分期标签**，且**均以裸名 `Phase` / `阶段` / `Step` 引用**（限定词被省略），长期造成「同号不同义」。**自 2026-10-09 起全部冻结**：活文档与生产代码**不得再新增**分期标签；分期口径只进 `doc/log/`（做过什么）与本注册表（曾有哪些）。
>
> | # | 体系 | 语义 | 取值 | 历史载体 |
> |---|---|---|---|---|
> | ① | **P0-C 能力吸收** | `AgentRuntime` 八件套逐件落地 | `Phase 0`（契约）→ `Phase 1 Step 1~4`（Skill / Tool / Session / Env 供电）→ `Phase 2`（Tool Executor）→ `Phase 3`（AgentLoop）→ `Phase 4`（Sandbox） | `doc/log/` 各月；`design/adr/ADR-001`；协作规约 §19 |
> | ② | **ADR-001 标识模型** | Run / Turn / Step 落地 | `Phase 0`（仅定义契约）/ `Phase 2`（AgentLoop 多步化）/ `Phase 3`（Plan 实体路线） | `design/adr/ADR-001-run-turn-step-model.md` |
> | ③ | **执行命令链** | 双轨切换 / Outbox / Doorbell / 命令 MQ | `Phase 0 A*`·`B*`·`C*`；`Phase 2D`·`2E`·`2F`·`2H` | 生产代码 javadoc 历史原注 |
> | ④ | **最终报告整合** | 报告链改造 | `阶段二` / `三` / `四`（`3A` / `3B` / `3C`） | `G-016`；`LOG-20260928-*` |
> | ⑤ | **V2 里程碑** | 项目级路线 | `阶段 0`（MVP）~ `阶段 5`（能力经济性） | `review/HelloAI 架构V2进度与质量审计报告（2026-09-29 / 09-30）.md` |
> | ⑥ | **开发生命周期** | 每次开发任务的流程（**仍活跃 ⇒ 保留**） | `Step 0`（工作区检查）~ `Step 9` | 协作规约 §10-14 |
> | ⑦ | **运行时字段** | `step` = 事件类型槽位（**非分期**，仅同名） | `0` / `5` / `6` / `7` … | `ADR-001 §5.2`；`agent_event.step` |
> | ⑧ | **真身灰度联调**（原口径名「灰度第 0 步」，2026-10-09 补登记） | P0-B 双轨切换的联调执行口径（现已终结：灰度机制随单轨硬切不复存在） | `第 0 步`（2026-09-08） | `doc/archive/log/2026-09.md` `LOG-20260908-006`；`plan/HelloAI 重构实施计划.md`（已改称能力名） |
>
> **已知同号冲突（去标签的直接动因）**：①↔② 对 `Phase 2` 定义不同（① = Tool Executor，② = AgentLoop 多步化）；①↔③ 撞号（`Phase 2E/2F/2H` 属命令链）；`Step`／`步` 多义（① 能力供电 / ⑥ 开发流程 / ⑦ 字段值 / ⑧ 灰度联调）；`阶段` 二义（④ 报告 / ⑤ 里程碑）。
>
> **处置（2026-10-09）**：①~⑤ 已在**活文档与生产代码**中去除裸标签（保留能力名、`ADR-*` / `LOG-*` / `N-0xx` 等**durable 锚点**）；`doc/archive/**` 与 `doc/review/**`（历史现场 / 报告）**保留原样**；⑥ 保留；**⑧ 于 2026-10-09 补登记并同批去标签**（`plan/HelloAI 重构实施计划.md` 三处改称「真身灰度联调」+ 挂 `LOG-20260908-006` 锚点）。

**当前生效的取舍决策**

- **D-2026-09-29-1｜MVP 可用性 > P1 剩余项推进**（阶段性有效）：事故清偿告一段落后**必须回到 P1 剩余项**。复审触发：① 新 P0 事故；② P1 剩余项连续 2 个迭代未启动；③ 用户要求推进隔离 / 治理类能力。
- **D-2026-09-29-2｜验证与工程化基础设施优先于功能推进**：已落地 CI 门禁 + 可用 JDK 固定 + 架构漂移冻结守卫 + B 级 IT（4 IT / 门禁 5）；**未落地** R2/R3 的 A 级 E2E 补证（待排期）。
- **D-2026-09-30-3｜不补回退手段**（硬切 = 单向门）：撤销的是「回退开关」；**不撤销**「兜底策略」（超时重派 / 掉线回收 / 死信 / 孤儿巡检）与「同层恢复」（checkpoint 每轮落库）。唯一回退手段 = `git revert`。
- **D-2026-10-09-4｜借鉴落地专项立项与四项裁定**（用户，2026-10-09）：① 借鉴落地专项启用 **`REF-x.y`** 编号（`plan/HelloAI 借鉴落地实施计划.md`），与 `G-` / `RM` / `B` / `BASE-` 错开；② **备份/恢复**与 **RAG 知识库**正式进**目标架构与差距表**（新增 `G-018` / `G-019`，二者此前在活文档零登记）；③ **沙箱生产形态裁定为「独立沙箱服务」**——不走「把 `docker.sock` 挂进 app 容器」路线（低改造成本但高权限面），其改造方案另行专项分析；④ 工具/技能侧的**条件可用语义位**与 `CRITICAL_TOOLS` 式**不可关闭清单同批实现**，落地时须显式界定与既有 `agent_mcp_server.is_enabled`（Agent-工具绑定层）的层次边界。
- **D-2026-10-09-5｜Fork 驱动执行不立项（WONTFIX）**（用户，2026-10-09）：借鉴落地专项**去掉 Fork**——`REF-2.1` / `REF-2.2`（触发入口 / 原 Run 冻结 / 驱动新 Run 执行 / 快照可观测与前端登记）**全部取消**。理由：①「驱动执行」自 2026-10-04 拍板起即为后置项（与 Resume 对称的「先做成、再做好」）；② 实际用法（分叉重跑 / 路径对比）已被 **Return**（闭环完整）与 **Replay 工作台**覆盖；③ 驱动执行须改 ADR-001 的 Run 标识模型 + execution command 载荷（协作规约 §30/§31），成本收益不匹配；④ 去掉后本专项**不再触任何 MQ 载荷契约**。**处置**：`AgentEventForkService` 本体**保留**为未接线的内部能力（零调用方）；Event 消费面**Recovery 留在既有 P1 剩余项内按原次序推进，不因本次调整改变优先级**（不加 `REF-` 编号）。回流登记见 §7.1.2。
- **D-2026-10-09-6｜借鉴落地专项余下五项裁定**（用户，2026-10-09）：
  - **③ REF-3 沙箱整组降级为「条件触发」**（**修正原「第 3 优先级」排序**）：`REF-3.1~3.5` **不排期**，契约保持现状不再扩展，不做 Docker 服务、不启动专项。判据：**当前没有可隔离的执行对象**——外部 agent 在它自己终端（平台定位 = 派单方 ≠ 执行方）；内部 `API_KEY_LLM` agent 的工具面全是平台 API（无 shell / 文件写，见 `G-005` 行实测）；平台全库无脚本引擎 / 表达式求值器。**触发条件（任一成立 ⇒ 重新排期）**：① 平台增加碰宿主的工具 / ② 技能包要被执行 / ③ 平台自持浏览器。连带修正：原「技能脚本执行依赖沙箱」的硬约束在现形态下**自动满足**（平台本来就不执行脚本）。
  - **⑤ 新增 `G-020`「外部 Agent 工作详情快照」**（任务锚点 `REF-7`，`P2` 可后置）：来自用户对平台定位的澄清——外部 agent 事后经审批提交全任务工作详情快照，平台解析后插入执行时间线等审计信息。
  - **③-1 REF-1.3 语义位落点**：采纳「放 `ToolRegistry.resolve(...)` 上下文参数（新增 `ToolContext`）、**不进 `ToolDefinition` record**」；实施时只在 Tool 侧加参，`AgentSkillSpecService.resolve(List)` 签名不动。
  - **③-2 REF-5.4 出站客户端**：采纳「**OkHttp + 自定义 `Dns`** 做 pinning；协议白名单**默认只放 https**，本地 http 走显式开关（默认关）」；「IP 直连 + Host 头」已排除（SNI 变 IP ⇒ 证书主机名校验失败）。
  - **③-4 REF-1.2 技能目录下游一致性**：采纳「**服务端下发**」（消费 REF-1.2c 技能目录 API）；parity 守卫只留给不适合下发的词表（`AGENT_SKILL_OPTIONS` ↔ 后端 `KEYWORD_SKILLS` / `SYNONYMS`）与事件码（`REF-6.8`）。

**当前冻结基线**

- **§11.3「Controller 不得暴露 core Entity」**：命中 **19 处 / 8 控制器**（冻结未清偿 —— 改动 API 响应契约须与 `helloai-ui` 联动，单独立项）；校验 `scripts/powershell/verify-code-style-p0-layer.ps1 -StaticOnly`。
- **架构守卫**：组 1 反向 / 组 2 mapper 为 `block`，基线全 0；组 3 前向为 `warn`（`task->agent` 57）。

---

# 1. 总体矩阵

| ID | 能力 | 当前状态 | 目标 | 优先级 | 处置 |
|---|---|---|---|---|---|
| G-001 | Agent Event Stream | 已有 Run/Turn/Step + Event 基础 + Timeline 并轨（A6）+ Replay/Audit 读侧（A7） | 统一事件契约和消费体系 | **P0** | P0-A 完整闭环（A1~A7 已落地，验收全量成立） |
| G-002 | Executor 迁移 | **✅ 单轨收敛（2026-09-30 硬切）**：实现层唯一化——旧链入口全部删除，`RuntimeTurnExecutor` 为唯一 `AgentRuntime`（`agentRuntimes.get(0)` 直取，无路由无排序） | Runtime 成为唯一执行契约，旧实现退出 | **P0** | ✅ **已收敛：双轨→单轨硬切（2026-09-30，见 `LOG-20260930-001`）**——用户决策「不设灰度、不设退场管理，一次性禁用旧链」（自研项目无生产数据）。**落地**：删除 `RuntimeAgentRuntimeRouter` / `LegacyExecutorAdapter` / `TurnLlmCaller` / `TurnLlmCallContext` + 3 专属测试；新建 `AgentRuntimeContextAssembler`（687 行）承载装配；`SubTaskExecutionServiceImpl` 906→30 行；`LocalExecutionCommandConsumer` 分层编排（startIfNeeded → markRunning CAS → assemble → execute → afterTurn → 终态 CAS → ExecutionResultHandler 回写）；`runtimeEnabled` / `v2-enabled` / `gray-percent` 死配置全部删除。**验证**：全量单测 BUILD SUCCESS（core 1551 例 0 失败，独立复跑 3m12s）；B 级 IT 8/8 绿（B2 MQ 消费走真链路）；**DB 实证单轨路由 `route=agent_runtime` 9/9 命中，零 legacy**。**演进遗留（见 §0.4）**：①**真身 loop 每轮 checkpoint——✅ 已落地（2026-09-30，当日实施）**：`ChatModelToolLoop` 每轮 iteration 边界经 `LoopCheckpointListener` 落 `agent_session.snapshot.loop`（零 DDL；`AgentLoopInput` 旧 12 参构造器保留，调用点零改动），重派恢复段渲染循环进度接续上下文；保持 V66 边界（不做 LLM 级断点续接、不回放消息历史），loop 级失败的轮次级进度自此可观测、可接续（「整子任务重跑」为唯一兜底的口径相应收窄）。实现详情见 `doc/log/2026-09.md` 2026-09-30 条目。②**tokenUsage 端到端落地——✅ 已落地（2026-09-30，B5 清偿）**：loop 每轮累加 `usage.totalTokens` → `AgentLoopResult` / `AgentExecutionResult.tokenUsage` → 终态 CAS 落 `agent_execution_record.token_usage`（V97 迁移），`AGENT_COMPLETED` payload 增 `tokens`；内部链路真实任务 E2E 三通道对账 7 值完全一致（见 §0.6）。以下为历史口径（09-29 订正记录，保留追溯）⚠️（口径订正见 §0 `LOG-20260929-001`）——2026-09-08 dev 真身联调（RuntimeAgentLoop 点亮）/ 对账全绿 / 回滚零差异 / 外部 Agent 回归通过 / 真实任务全链闭环（外部端到端 14 分钟 5 子任务零故障，见 log 2026-09-08）；但该次灰度系**一次性联调后即回滚**，非持续运行态。**当前生产恒走 Legacy**：`runtimeEnabled` 默认 false（`AgentExecutionProperties.java:76`、`application.yml:196`），`LegacyExecutorAdapter` 恒注册（`LegacyExecutorAdapter.java:33` `@Order(2)`），消费侧构造 AgentContext 不注入 chatModel/prompt → `RuntimeAgentRuntimeRouter` 恒回落；`application.yml:117-121` 的 `v2-enabled: true` 为**零读取死配置**。**本行「目标」列要求「旧实现退出」，与现状自相矛盾，且命中《目标架构》§11 自身列为非目标的「❌ Dual Executor 永久双轨」**。**收敛要求**：设定明确退场时点，或显式登记 WONTFIX 并撤销该目标（见审计报告 §7 建议 4）。**剩余阻断项 B1**（单轨收敛 + Legacy 退场，工作量 L，最高风险）：`RuntimeTurnExecutor.java:65` 硬校验 subTaskId/agentId/chatModel/prompt，真身 loop 每轮 checkpoint 已落地（2026-09-30，见演进遗留①）、tokens 统计已落地（2026-09-30，见演进遗留②） |
| G-003 | AgentRuntime | 八件套已全部落地（Context / Session / Skill / Tool / Loop / Event / Environment / SandboxProvider 契约） | Context + Session + Skill + Tool + Loop + Event + Sandbox | **P0** | P0 完整闭环（P0-A/B/C 收官）；真实 provider tool-calling 循环 2026-09-08 联调通过，无边界问题 |
| G-004 | Skill Capability | SkillPackage 元数据层已落地（name/version/description/requiredTools/dependencies/inputSchema/outputSchema/validationRules，3 个 eng-* 已结构化）+ **requiredTools→tools 联动已接**（Legacy/Runtime 双链 mergeTools 并集去重保序，TOOL_RESOLVED 前合并）+ **SKILL_RESOLVED 携带 resolvedVersions**（Replay/前端按字段投影兼容）+ **拆解技能通路已接**（task.required_skills → 拆解 Prompt 注入，规划/验收与技能规范对齐；增量 B） | Metadata / Version / Tools / Schema / Dependencies 全量 + **requiredTools→tools 联动** + SKILL_RESOLVED 携带版本 + 真实任务行使（任务级创建/拆解/派发/执行四段已贯通；带 required_skills 真实任务实测已于 2026-09-10 完成，见处置列） | **P1** | 元数据层（ed14e40 / 234bed4）+ 联动接线（增量 A）+ 拆解技能通路（增量 B，全量 1295 单测 0 失败）落地；真实任务带 required_skills 端到端实测已于 2026-09-10 完成（Round2 全任务 eng-doc-standard 硬门槛准入 + Round3 技能分布派单与 135:20 分排序实证，见 log 2026-09-10）；**契约层统一技能注入（增量 C，2026-09-28）**：AgentTask.skills 承载 + PlatformAgentExecutionService.execute/executeStream 统一注入（checkCapability 后、调执行器前，skills 空不注入行为零变化）；5 类同步 LLM 调用挂点同源（子任务执行/执行流/拆解/审查收口/报告）；新技能包 eng-web-research（requiredTools=[web_search]，复用联动） |
| G-005 | Sandbox Provider | 已有 Environment / Provider + SandboxProvider 契约（诚实策略，无 ISOLATED）。**2026-10-09 复核：当前无可隔离的执行对象**——外部 agent 在它自己终端（平台定位即派单方）；内部 agent（`API_KEY_LLM`）工具面 = 13 个平台 API 工具 + `web_search`，`McpMcpServer` 内 `File` / `Path` / `ProcessBuilder` **0 命中**；平台全库无脚本引擎 / 表达式求值器 | 真正 Provider 化执行环境与隔离策略 | **P1（条件触发）** | **降级为「条件触发」不排期（`D-2026-10-09-6③`）**：契约保持现状、不再扩展，不做 Docker 服务、不启动专项。**三个触发条件（任一成立 ⇒ 重新进入排期）**：① 平台增加「碰宿主」的工具（自持 shell / 文件写）；② 技能包要被执行（技能带脚本、平台跑脚本）；③ 平台自持浏览器（`WEB_BROWSER` 真实接入链路）。**预案**见 `plan/HelloAI 借鉴落地实施计划.md` §5（`REF-3.1~3.5`，形态已裁定 = 独立沙箱服务） |
| G-006 | Replay / Audit | 写侧+对账闭环；Timeline 已暴露（API+UI）；Replay/Audit 读侧 service 就绪；**Replay/Audit API 已暴露**（增量 C1：helloai-api AgentEventController——GET /api/agent-events/traceByRunId/{runId} + GET /api/agent-events/pageAuditByTaskId/{taskId}，API 层 DTO 投影 + ControllerTest 5 用例）+ **UI 工作台已上线**（/event-stream 事件流：Replay 轨迹时间线 + Audit 分页表格 + eventType 过滤 + payload 原文折叠；事件字典抽离 utils/eventMeta 与 SubTaskDetail 时间线同源共享）+ **增量 D**（traceByTaskId / traceBySubTaskId 任务/子任务维度端点 + 工作台选择器 / Agent 名称解析 / payload 结构化展开 / 事件流深链） | 基于统一 Event 查询/回放；**外部执行轨迹对齐**（外部路径 agent_execution_record 0 行、事件仅完成态，Replay 时外部任务仅「派发→完成」细线） | **P1** | 增量 C1 落地（2026-09-08）：Timeline ✅ / Replay ✅ / Audit ✅（API+UI，api 56 单测 + type-check/build 全绿）；增量 C2 落地（2026-09-08）：外部认领埋点 AGENT_STARTED + Replay run 级汇总卡（core 799 单测 + type-check/build 全绿），外部轨迹加厚为「AGENT_STARTED → AGENT_COMPLETED → REVIEW_STARTED → REVIEW_APPROVED」四事件；增量 D 落地（2026-09-10）：任务/子任务维度查询端点 + UI 工作台增强（选择器 / 名称解析 / payload 展开 / 深链） |
| G-007 | Quality Gate | Reviewer 闭环已存在 | Rule + Test + LLM 统一决策 | **P2** | 现有链上增强 |
| G-008 | Agent Fleet Routing | 已有 Agent 选择机制；**多外部执行者同台已实证**（2026-09-10 Round3：双执行者背靠背竞态 231ms 唯一赢家 / 技能硬门槛内按分排序 / 2 执行者 3 子任务并行持有）；外部执行 tokens 回报**协议侧已收口**（2026-10-03 B5.1：`submitResult` 新增可选 `tokenUsage`，三通道（MCP SSE / REST 别名 / REST 直通）对齐，缺省 null 与旧协议一致，见 `LOG-20261003-002`）；存量历史记录仍为 null（成本观测盲区，待数据积累）；**成本维度已接入选人比较链**（2026-10-03 B5.2/B5.3，见 `LOG-20261003-003`：`AgentExecutionRecordService.averageRecentSuccessTokens` 近 5 次成功执行均值 → `AgentSelector.resolveCostRanks` 候选内 min-max 反向归一 1~5 档 × `costWeight`(默认 0.1)，插在 quality 之后、score 之前；无成本数据记 0 档） | Capability + Health + Load + Policy | **P2** | 渐进升级；多外部执行者对照场景已于 2026-09-10 验证完成（见 log）；token 回传（成本观测）**协议就绪**（2026-10-03 B5.1，见 `LOG-20261003-002`）；**成本选人已完成**（2026-10-03 B5.2/B5.3，见 `LOG-20261003-003`）；**web_search 能力目录化前置登记（2026-09-28）**：联网搜索 Capability 化——planner 域 WebSearchToolCallback 经 ToolCallbackContributor 端口反转注册为平台工具 web_search（McpToolConfig 单 bean 收集合并）+ 技能包 eng-web-research（requiredTools 联动）+ ClarifyWebSearchOrchestrator 按工具名调 ToolExecutor.execute（失败空列表降级，webSearchEnabled 会话开关保留）；WebSearchService 直调路径保留至端到端验证通过后删除 |
| G-009 | Dynamic Workflow | 已有模板/实例化/DAG | 动态分支、复杂运行期编排 | **P3** | 后置 |
| G-010 | Planner 能力感知与自适应粒度 | **S1~S4 已落地（2026-09-09）**：S1 数据层（V74 sub_task.required_skills JSONB + constraints TEXT）+ S2 拆解侧（技能目录常驻注入 / 子任务级 requiredSkills+constraints 指派 / 目录过滤 task_plan_skill_filtered 审计 / rule-based 粒度三档 FINE/STANDARD/COARSE + 目录超 20 项截断）+ S3 传递链（mergeSkills 并集装箱 5 装箱点同源 / inbox 技能要求行 / REST 下行 / 草案确认 UI 展示编辑 + updateDraftById 端点 / SKILL.md 增量） | 技能目录注入拆解 Prompt + 子任务级 requiredSkills/constraints 指派（V74 新列，并集装箱）+ 粒度三档 FINE/STANDARD/COARSE 自适应（**rule-based 决策矩阵**：执行者画像 × difficulty 调制，2026-09-09 拍板）+ 外部感知下行通道（可选字段向后兼容）+ 技能回流贡献规范 | **P1** | S1~S3 PASS（2026-09-09，见 log）；S4 实测平台内链 PASS（2026-09-09，与 G-011 S5 合并执行）——verify-login-e2e 10 项 / verify-requirement-clarify 10 步 / verify-planner-decompose 12 步 EXIT=0（登录 → 澄清 → 终稿 → 建任务 → AI 拆解 → 确认/拒绝闭环）；外部执行链（外部 EXECUTOR agent 场景）已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log）；**后置缺口**：①技能回流贡献规范（D5-3 DB 化）②verify-skill-packages.ps1 校验脚本（D5-2 未交付）；③审查侧 constraints/requiredSkills 注入核验（D4 验收口径）④COARSE 档 constraints 缺失的 timeline WARN 级事件（设计 §2.3 承诺）已由 G-011 S3/S4 清偿（2026-09-09）；**P0「打通下发」补强（2026-09-11）**：claimSubTask 返回体内联子任务全文（content/deliverable/acceptance/constraints/uncertainties/requiredSkills）+ 新增 getSubTaskDetail 工具（三通道 12 工具对齐；V76 seed + 默认 12 + 懒启用三重兜底）+ inbox sub_task.assigned 摘要升级为「交付物 + 验收标准 + 约束 + 待确认正文」结构化文本；纯增量向后兼容，66 相关单测全绿（新增 1 处 agent→task entity 只读引用已按 CODE_STYLE §6.1 显式豁免，记录见 SubTaskDetail Javadoc）；**P2-3（2026-09-11）**：COARSE 档 acceptance 补「与交付物的可观察判定方式（判定动作 + 预期结果）」（不动 STANDARD/FINE 两档） |
| G-011 | 需求包准入与不确定性显式管理 | **S1~S5 已落地（2026-09-09）**：S1 数据层（V75 双列 sub_task.uncertainties JSONB DEFAULT '[]' + requirement_conversation.final_package JSONB + 双实体字段 + Uncertainty 值对象落 task 域避反向依赖 + RequirementPackageParser 防御式静态工具）+ S2 澄清侧（两处终稿提示词 package 结构化五字段 + ClarifyReply.package JsonNode 防御承接 + updateFinalDraftFields 条件覆盖写 + buildTaskFromDraft 双写）+ S3 拆解侧（模板四增量 + uncertainties 落库归一 + COARSE constraints 缺失 WARN + D5 兜底审计）+ S4 传递链（执行注入 D6 三段 / 审查核验 D7 双占位符 + 轨道 A 第 8/9 条 / REST 下行四参 updateDraft / inbox 待确认行 / 草案 UI 不确定性列）+ S5 实测（平台内链 PASS，与 G-010 S4 合并执行） | 结构化需求包（goal / scope / outOfScope / assumptions / openQuestions，会话列 + task.context 双写）+ 拆解继承为 sub_task.uncertainties[ASSUMPTION / UNCONFIRMED] 显式 JSONB 列 + 执行侧注入（含 constraints 补偿）+ 审查侧分级语义（假设不成立 ≠ 执行缺陷）+ 外部下行可选字段向后兼容 | **P1** | S1~S4 PASS（2026-09-09，见 log：V75+实体+解析器 10 单测 / 澄清侧 / 拆解侧 / 传递链各增量，core 全量单测 1359 用例 0 失败，api 编译 + UI type-check 通过）；S5 实测（2026-09-09，与 G-010 S4 合并）：平台内链 PASS——澄清终稿 final_package jsonb 真实落库（jsonb 定点写 bug 修复：Mapper+XML ::jsonb 条件写，RequirementClarifyServiceTest 92 用例全绿）+ 确认卡 selections 协议核验 + 异步拆解轮询适配（360s 窗口）；外部执行链已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log：审查者引用 uncertainties 申报作驳回依据实证）；**不建自动闸门**（openQuestions 不阻断，人工裁决 + fail-close BLOCKED 链上报）；gap_kind 实现路径分类与任务后蒸馏闭环后置批次二/三；技术债：Agent 注册幂等顺序缺陷仍在（validateModelType 先于 registerOrGet；配套的 api_key_hash 落库缺列已于 2026-09-10 修复）；配套修复：JSONB uncertainties CCE 死锁（inbox 通知静默失败 → 外部链死锁根因，2026-09-10）；**uncertainties 正文下行（2026-09-11，P0「打通下发」）**：ASSUMPTION / UNCONFIRMED 逐条 note 随子任务全文（摘要 + claimSubTask 内联 + getSubTaskDetail）直达执行者，分级后缀与执行/审查侧同源；**P1/P2 批次（2026-09-11，生成能力退化修复）**：①P1-1 需求包补回任务级 acceptanceCriteria（六字段，设计 §7#11；#2 决策反转已同步登记，JSONB 加键零迁移）+ planner-decompose「任务级验收覆盖」规则（封闭集合全覆盖，无需求包任务不做回溯要求；提示词硬约束 + 人工核验，不做 fail-close）②P1-2 拆解四必填字段（title/content/deliverable/acceptance）缺失即整批 BizException + timeline `task_plan_draft_field_missing` 审计（设计 §7#14）③P1-3 终稿四小节关键词组校验 + 单次重试 + fail-open（timeline `requirement_description_section_missing`）+ 两提示词 description/package 职责写死（设计 §7#13）④P2-1 主任务详情弹窗展示需求包六字段（后端零改动，无需求包整块隐藏）⑤P2-2 verify-requirement-clarify-structured 扩至「澄清 → 终稿」软断言（小节组 ≥3 / 长度 ≥300 / final_package 存在）⑥P2-3 COARSE acceptance 可观察判定方式⑦P2-4 审查 missingEvidence 缺失证据清单（轨道 A 第 10 条 + reviewHistory 加键 + 返工 inbox 摘要携带，设计 §7#17）；**无 DDL 迁移、无新增 agent→task 依赖**；单测 core 全量 1380 + api 58 用例 0 失败，UI type-check 通过 |
| G-012 | 登录鉴权与 RBAC 权限体系（Sa-Token） | **底座 + 闭环已落地（2026-09-12）**：登录会话由自建 Redis Token 切换为 Sa-Token（token 走 X-Admin-Token 头，active-timeout 8h 滑动续期）；授权从 AdminOnlyInterceptor 前缀二元判断升级为「角色-权限码」RBAC（V77 四表 + 内置 SUPER_ADMIN/ADMIN + 存量用户 role 迁移 + StpInterfaceImpl + @SaCheckPermission）；管理侧 API（角色 CRUD / 角色-权限绑定 / 用户-角色分配 / 权限码列表）+ 登录响应携带 permissions/roles + 前端菜单按权限码动态过滤 | 自建登录模块 → Sa-Token 统一会话 + 用户/角色/权限码管理 API + 接口注解鉴权 + 前端动态菜单；后续可按需做菜单树建表与页面化管理 | **P2** | 底座 S1~S4 PASS（2026-09-12，见 log）：V77 迁移 + 四实体/四 Mapper + 角色服务 + 权限查询服务 + 三管理 Controller + SaInterceptor 注解鉴权 + NotLogin/NotPermission 异常归一；单测 SysRoleServiceImplTest 6 + SysPermissionQueryServiceImplTest 5 + AuthServiceTest 10 = 20 用例 0 失败，core 全量 1393 用例 0 失败；UI type-check 通过；V77 在 dev 库事务回滚验证 PASS（2 角色 + 23 权限码 + 20 角色权限 + 存量用户迁移）。**后置缺口（原登记三项已全部关闭，2026-09-12 同日闭环）**：①角色/权限/用户管理前端页面（S5 PASS，三页 + 路由 + rbac.ts）；②存量会话无缝迁移（S2 PASS，原 token 重建会话删旧 key，Docker 实测）；③菜单树 DB 化（S6 PASS，MenuController /api/admin/menus/tree + MainLayout 动态渲染）；配套基础设施修复 S7 PASS（分页 total 恒 0 根因二连）。**深化项已转 BASE 专项（见 G-013）** |
| G-013 | 基础架构深化（RBAC 底座，参考 JeecgBoot） | **批次一/二/三全部落地（2026-09-12）**：①前端动态路由（权限=路由可达性，无权限 URL 404）；②v-auth 按钮级权限；③动作级权限码；④菜单树携带 component 驱动动态 addRoute；⑤可视化菜单/权限管理页（树形 CRUD）；⑥角色授权差异更新；⑦**路由渲染增强**（隐藏菜单/页面缓存/外链）；⑧**部门/岗位组织架构**（部门树 + 用户-部门/岗位多对多 + 两管理页 + 用户「组织归属」分配）；⑨**数据权限规则**（受控枚举 ALL/DEPT/DEPT_AND_CHILD/CUSTOM，作用于用户列表可见范围） | 对齐 JeecgBoot 标杆：权限 = 路由可达性 + v-auth 按钮级权限 + 动作级权限码 + 菜单树携带 component 动态 addRoute + 可视化菜单树 CRUD + 角色授权差异更新 + 渲染增强/组织架构/数据权限 | **P1** | **三批次全部 PASS（2026-09-12，见 log）**：BASE-1.x（V79 component+动作码 / V80 path 补丁 / SysPermissionService CRUD + DTO 投影 / 白名单守卫动态路由 / v-auth / 菜单管理页 / 授权差集）；BASE-3.1（V81 hidden/keep_alive/external_link + 前端渲染适配）；BASE-3.2（V82 部门/岗位四表 + 8 权限码 + SysDepart/SysPosition Service+Controller + 用户组织归属 + DepartList/PositionList 页）；BASE-3.3（V83 rule_flag + 数据规则表 + 受控枚举解析 + 用户列表数据权限 + 规则配置 UI）。**实测修复 5 个缺陷**：SaInterceptor 未注册（G-012 疏漏）/ addRoute 父参数须为 name / 登出后路由权限串用 / **catch-all redirect 导致登录后 404**（改直接渲染 NotFound）/ **会话失效静默落 404**（HTTP 401 未清态 → 补 request 拦截器清登录态 + 守卫跳登录页）。**验证**：core 1444 用例 0 失败 + UI type-check/build + Docker 全链路 + 浏览器实测（SUPER_ADMIN 23 菜单含部门/岗位页；ADMIN 17 菜单且 `/system/departs` 404；数据权限 CUSTOM 过滤生效）；既有 PS1 本机无 pwsh → NOT RUN（等价断言 PASS） |
| G-014 | 外部 Agent 执行通道缺陷修复（依赖门禁 / 返工出口 / 附件可发现 / 截断元数据 / 在线语义 / 核验边界） | **P0-1、P0-2 与 P1-3~P1-7、P2-8、P2-10 已修复（2026-09-24）**：①依赖门禁——`listAvailable` 批量就绪过滤 + `claimSubTask` 复用 `isReady`（新增 reason `dependency_not_ready`）；②返工出口——新增 MCP `startSubTask`（ASSIGNED/REWORK/PAUSED→IN_PROGRESS，归属校验 + 幂等），`DEFAULT_EXECUTOR_TOOLS` 12→13，状态机补 `REWORK→BLOCKED`（返工途中可上报阻塞）；③`getDepsSummary` 增 `ready`/`notReadyCount`（与 `degraded` 正交，区分「前置未就绪」与「采集异常」）与 `DepItem.loaded`/`contentChars`；④`SubTaskDetail` 内联 `attachments`（含 attachmentId，附件可发现）；⑤核验侧截断输出结构化 `[TRUNCATED] file/shown/total/reason` 标注行 + 核验 Prompt 增「不可见内容不得补全」条款；⑥`SubTaskDetail.contributors`（产出归属可见性）；⑦在线判定引入 ACTIVE 值班租约作为存活证据（心跳过期不直接判 OFFLINE）；⑧`renewLease` 到期时刻单调钳制；⑨JSON-RPC `tools/list` 全量补 `required` + `checkIn` 补 `skills` | 外部 Agent 通道与内部分发链同口径：依赖/技能约束一致、返工可自救（不依赖人工放行）、前置产出与附件可发现可读、核验结论只基于可见证据、在线判定不误伤在岗 Agent | **P0** | 按 8 任务落地（T01~T08）：T01 依赖门禁 / T02 返工出口 / T03 结构化元数据 / T04 附件可发现（功能性）/ T05 在线语义 / T06 核验边界 + contributors / T07 schema / T08 租约单调。**已做（口径订正见 §0 `LOG-20260924-001` / `LOG-20261003-001`）**：~~T04b 附件读端归属授权~~ ✅ **已完成**——`AttachmentController.java:124-146` 实测存在 `_authType=="agent"` 归属比对 + `BizException(403,"无权访问…")`，四端点（`:52/65/79/106`）全部接线；`SubTaskController.java:113/129` 的 `listTimeline` / `listConversation` 亦调 `assertSubTaskReadableByAgent`；配套单测 `AttachmentControllerAuthScopeTest`（平台账号放行 / 归属者放行 / 非归属者 403 三态）；commit `5f48a64`（2026-09-24）。**原「任意有效 Agent Key 可读他人子任务附件正文 / 核验 Prompt」的越权读风险在代码层已闭环**（验证强度 C 级：单测，E2E 未跑）。~~T08b `SKILL.md` 与 `doc/manual/executor-duty/` 文档一致性~~ ✅ **部分已完成**——`helloai-core/src/main/resources/onboarding/executor/guide.md` 含 `startSubTask` 9 处（返工出口文档已同步）；`doc/manual/executor-duty/` 限额章节口径（2000→4000 / blocked 日志承诺 / startById 指引）待复核。**验证**：单测批次全绿（McpToolService 45 / SubTaskStateMachine 12 / SubTaskReviewService 46 / HeartbeatServiceActive 12 / McpControllerJsonrpc 16 / AgentDutyLeaseService 17，均 0 失败）；**✅ E2E 已实跑（2026-09-30 订正，见 `LOG-20260930-002`）**——**原「NOT RUN（本机无 Docker / PG 客户端且 `ms-17.0.19` JVM 崩溃，需服务器复验）」的阻塞已解除**：JDK 固定（`lib-jdk.sh` 黑名单 + 实测大版本）与 Docker 环境到位后，以**真实外部 CLI_CLIENT Agent 端到端 A 级实测**替代脚本级抽验——5 任务 15 子任务全 DONE（同名去重不重发）、技能 AND 匹配派单、评审真实拦截 3 类交付缺陷并返工闭环、心跳掉线回收 + 退避重派无死信；DB 独立复核：`sub_task_dispatch_prepare`×29 / `sub_task_unclaimed_timeout_reassign`×3 / `agent_offline`×4 / `sub_task_auto_review_rejected`×6。脚本 `verify-external-agent-e2e.ps1`（含 `-AssertOnly` 回查模式）。**仍待补**：`verify-tool-matrix.ps1` / `verify-mcp-e2e.ps1` / `verify-agenthub-duty-e2e.ps1` 的**脚本级全量矩阵**复验（当前由真实 Agent 端到端覆盖主链，但 13 工具矩阵与租约在线语义未逐项断言） |
| G-015 | 外部 Agent 心跳/重派止血（B1：误判离线 → 在飞任务被重派打满死信） | **B1 止血已落地（2026-09-26）**：①写侧值班租约守卫——`AgentHealthCheckTask.processStaleAgent` 在 CAS 标 OFFLINE 前先判 `AgentDutyLeaseService.isOnDuty`，持 ACTIVE 租约即跳过离线处置，与读侧 `HeartbeatServiceImpl.checkOnlineStatus`「租约 ACTIVE → IDLE」同口径；②在飞子任务宽限——持 `ASSIGNED/IN_PROGRESS` 子任务时改用 `AgentHealthProperties.inFlightGraceMinutes`（新增，默认 30min，`max(grace, offlineMinutes)` 决定扫描窗口）计算 CAS cutoff，阈值放宽而非取消；③只读/登记类工具（pullTasks/ack/startSubTask/uploadArtifact/reportBlocked/getAgentStatus/getDepsSummary/getSubTaskDetail）在 `refreshDutyLease` 持租约时顺带 `seen()` 刷 `last_seen_time`（heartbeat 工具已显式 `seen()` 故传 `true` 避免重复双写）；④「租约 ACTIVE + dbOnlineStatus OFFLINE」双视图分裂随 ①③ 收敛。**无新增 Flyway 迁移、不改状态机、不加新枚举** | 外部 Agent 埋头执行长任务（数分钟不触网）不被 5min 心跳窗口误判离线；判离线的读/写两视图口径统一 | **P0** | **B1 PASS（2026-09-26）**：编译 SUCCESS；单测 `AgentHealthCheckTaskTest` 17（含新增 G-015 B1 守卫 5 例：持租约跳过 / 在飞宽限 cutoff / 常规 cutoff / 租约查询异常 fail-close / 在飞查询异常 fail-close）+ `McpToolServiceTest` 47（含新增 seen 刷新 2 例）+ `HeartbeatServiceActiveTest` 12，均 0 失败。**B2~B4 已落地（2026-09-26）**：B2.1 离线重派补偿路径改走 `dispatchPendingSubTaskCompensating`（复用闸门但不重复计数，单轮最多 +1 而非 +2）；B2.2 重派退避 `REASSIGN_BACKOFF_SECONDS={60,180,600,1800}`（退避时刻取 `sub_task.update_time`，零新增列/context 键；**2026-10-05 订正**：该时钟选择有缺陷——`block()/resume()/changeStatus()/resetToPendingForDispatch()` 均经 `updateById` 刷新 `update_time`，「换人」入口先 `block()` 再重派时闸门读到被自身刷新的 now，`nextAllowed=now+600s` 必然拦截，**attempt_total≥1 时「换人」100% 静默失败**（子任务 804 实测）。已改绑独立列 `sub_task.last_attempt_time`（**V102 迁移**，仅 `incrementAttemptTotal` 原子写入、`resetAttemptTotal` 清空；`updateById` 不写），闸门只读该列；并补 `sub_task_redispatch_skipped`/`sub_task_dispatch_fallback` 时间线与接口明确语义，详见 ）；B2.3 离线在飞 IN_PROGRESS 子任务置 PAUSED 保留归属（不消耗重派预算）+ `reclaimExpiredPausedTasks` 在 CAS 前回收「PAUSED 超宽限」任务（经 `redispatchInProgress` 改派链）；B3.1 `redispatchDeadLetter` 补清 `dead_letter_reason`/`attempt_total`/`max_reassign_attempts` 残留 context 键；B3.2 修 `startSubTask` 越权分支回显自相矛盾（改为回显真实归属）；B4.2 REST 双通道（直通 + JSON-RPC）透传 `skills`，`resolveSkills` 兼容数组与 CSV（`mergedSkills` 不再恒 null）；B4.3 **V94** 新增 `agent_duty_lease.ttl_minutes` 并持久化签发窗口、空闲续约复用该值（消除 P2-10 窗口跳变）；B4.4 附件上传按「无归属 409 / 归属他人 403 / 子任务不存在 404」语义化报错（原单参 BizException → 500 误导）。**未做**：B4.1/B4.5 的 `SKILL.md` + `doc/manual/executor-duty/` 文档同步（**限额数字已于 2026-10-03 上调**：8000 每附件 / 24000 总 / `output` 同受 8000 → **64000 / 200000 / 64000**，`guide.md` + `doc/manual/executor-duty/` + 基线文档均已同步，见 `LOG-20261003-001`；`attachmentId` 字段名同步仍待做）。**验证**：`clean test` 全量 **PASS**（core / job / api 三模块 BUILD SUCCESS）；**✅ E2E 已实跑（2026-09-30 订正，见 `LOG-20260930-002`）**——**B1 止血的有效性已获 A 级实证**：真实外部 Agent（TeleAgent）04:04–04:20 UTC 断线 → 其认领的 3 个子任务被回收（ASSIGNED）→ 退避窗口 `REASSIGN_BACKOFF_SECONDS` 第 3 档 600s 到期后自然重派 → 心跳恢复后正常接单，**无死信（`maxReassignAttempts` 未打满）**；DB 证据 `agent_offline`×4 + `sub_task_unclaimed_timeout_reassign`×3。原「E2E 与生产复测 NOT RUN（需部署 + PG/Docker）」已解除。**残留口径**：在飞续约仍用 `maxTtlMinutes(240)` 保活（E1 既定设计），仅体现在 `expire_time`，不污染 `ttl_minutes`；「无候选空烧预算 + NO_ELIGIBLE_AGENT 独立告警」未做，登记为后续观察点 |
| G-016 | 报告整合质量 | 报告链改造已完成（2026-09-28）：报告读取与子任务链同口径（物化附件优先 + ExecutionRecord SUMMARY/DELIVERABLES 注入 + output 兜底）；附件限额/族判定单源（AttachmentContentPolicy 上移 shared）；Markdown 块级截断 + 超限标注（[SUMMARIZED]/[TRUNCATED]，删「以已提供部分为准」字样）；报告 Prompt 目标重写（读者与用途/篇幅预算/主线论点/覆盖追溯表/冲突矛盾显式输出）；大纲先行两段式（出纲失败降级单次调用）；核验/返工闭环（TaskFinalReportGeneratedEvent + FinalReportReviewListener + rework + 自审自过硬守卫 review_skipped）；§12 遗留三项（2026-09-28）：**V95 单槽列**（final_report_prev/_prev_agent_id/_prev_time 三列，覆盖前 setSql 落槽，rollback 端点 current↔prev 整体互换可反复切换，无 prev/在途 409）+ **审查异步化**（REVIEWING 状态 + reportReviewExecutor 专用池 AbortPolicy + 事件 reportTime 微秒截断 + 三重陈旧守卫（审查前/rework 前/收敛条件写回）+ 6 出口收敛 DONE + 开关 autoFinalReportReviewEnabled）+ **轮次去状态化**（rework 3 参显式传轮次，删进程内 Map 计数） | 报告按主题归并有覆盖追溯，与执行链同口径读事实源；生成后自动核验、驳回可返工；自审自过不静默放行；审查不阻塞前端请求；历史报告可恢复上一版 | **P1** | 报告链改造 PASS（2026-09-28）：编译 7 模块 BUILD SUCCESS；TaskFinalReportServiceTest 36 + FinalReportReviewListenerTest 19 全绿；core 全量 **1546 用例 0 失败**（`.tmp/core-full-test-r12.log`）；前端 type-check 通过；依赖方向 verify 10/10 PASS；ui-sync Channel B 0 违规（Channel A 的 1 项为既有脚本正则盲区，非本批引入）。**附注**：附件限额/族判定口径已单源（AttachmentContentPolicy）；装配渲染层归并为可选优化；**§12.5 三级容错补齐（2026-09-28）**：FinalReportStateMachine 状态机收口写口 + FinalReportPersistService 事务边界（报告写回 / agent_outbox_event / L1 事件三者原子，消除双写丢失）+ L1 改 @TransactionalEventListener(AFTER_COMMIT) + L2 helloai.report-review.queue 幂等消费（eventId 幂等键，AgentEventCompensationTask 15s 补投）+ L3 FinalReportReviewOrphanTask 巡检（REVIEWING 超 300s 收敛 DONE，刻意不重投审查）+ 前端 FinalReportDialog 轮询修复（收敛后未 emit status-change 致列表行永久卡「报告审核中」的直接根因，改为逐轮广播 + 关窗补发）；core+job 全量 **1686 用例 0 失败**（新增状态机 5 / 事务边界 3 / L3 巡检 6 例，FinalReportReviewServiceImplTest 22 / TaskFinalReportServiceTest 36），UI type-check 通过。**§12.5 已知遗留与修复（代码审查发现，详见改造方案 §12.5.5）**：✅#1（已修）状态机缺 `FAILED→DONE` + `rollback` 断言在 CAS 之后 → 补迁移 + 断言前移到 CAS 之前 + CAS 改 `eq(from)` 与断言同源 + `GENERATING` 前置拦截（消除「FAILED 且 prev 非空点『恢复上一版』500 且库已静默改」确定性回归）；✅#2（已修）`generateWithAttempt` 断言用陈旧快照 → 前置拦截 + 断言前移 + CAS 改 `eq(from)`，对齐 `transitFinalReportStatus` 正确范式（消除并发下永久卡 GENERATING）；✅#3（已修）L3 孤儿巡检不抢防双审锁 + 阈值 300s<锁 TTL 600s → 抽 `FinalReportReviewLock`（键前缀 + `TTL_SECONDS=600` 唯一事实源，L1/L2/L3 共用）+ `converge()` 抢同款锁抢不到即跳过不收敛 + 阈值 300→660（`TaskServiceImpl` 兜底默认同步）；✅#4（已修，2026-09-29）AFTER_COMMIT 兜底落库未声明 REQUIRES_NEW（池饱和时收敛/审计可能丢）→ 下沉 `FinalReportReviewFallbackWriter`（`convergeToDone` / `recordSkippedAndConverge` 两方法声明 `REQUIRES_NEW`），AFTER_COMMIT 发布线程的兜底收敛与 `review_skipped` 审计经独立事务确定提交，池饱和不再丢（同 `ExternalAgentFailureTracker` 模式，规避私有方法自调用不走代理）；✅#5（已修，2026-09-29）L2 抢锁失败被误判消费成功并 ACK（L1 崩溃后无重投）→ `reviewInternal` 抢锁失败/中断/锁异常统一抛 `ReviewNotExecutedException`：L1 `reviewQuietly` 捕获记 debug 静默跳过，L2 向上传播经 `tryConsume` markFailed + `basicNack(requeue=false)` 入死信台账可重放；已抢到锁后 `doReview` 异常仍就地吞掉（避免重投重复烧 LLM）；前端列表兜底轮询 #6 keep-alive 退出未停表 / #7 无失败退避为可选精修。**#1~#5 已全部修复（#1~#3 见 2026-09-28 批次；#4/#5 于 2026-09-29 修复，全量 core+job+api `mvn -o test -DskipTests=false` **1756 例 0 失败 0 错误** BUILD SUCCESS，新增 FallbackWriter 委托 2 例 + L2 抢锁失败抛出 / 锁后异常吞掉 2 例回归）；前端 #6/#7 可选精修仍登记待修。** |
| G-017 | 附件存储一致性（产物对象 ↔ attachment 记录对账） | **存储抽象与对账巡检已落地（2026-09-28）**：ArtifactStorage 契约（Local/Minio 双实现 + Composite 按 type/URL 前缀路由；exists fail-open / listObjects fail-safe / removeObject fail-close 默认策略）；`AttachmentServiceImpl.register` 前置校验（storageUrl 必填 + validateAddress + supports 时 exists 校验，不存在 400 拒绝，@Transactional 回滚）；对账巡检 `ArtifactStorageReconcileTask`（6h fixedRate + ShedLock PT20M + reconcile-enabled 开关——attachment 全量含逻辑删除 ↔ 桶内对象双向比对，悬空/孤儿/字节不符三态）；孤儿清理默认关闭 + 三重保险（开关 + 24h 时间窗 + 单轮上限 200）；MCP `uploadArtifact` 描述重写（storageUrl 格式固定 + bucket 白名单 + 错误示例 + 整合子任务复用上游引用）；dev 端点配置已覆盖（R1） | DB 与对象存储两侧一致；悬空/孤儿可发现可报告；不再产生僵尸附件；孤儿清理受控有保险 | **P1** | R1/R3/R4/R5 处置落地（2026-09-28）：storage 定向测试 **72 用例 0 失败**（Composite 13 / Local 8 / Minio 20 / 对账 13 / AttachmentService 18，`.tmp/test-storage-r12.log`）；R2 缓解（对账可见可报告，历史 106 条悬空不自动修复）；R6 文档面引导（描述复用上游引用）；R7 部署侧安全动作待用户执行（改 MinIO 默认凭据 + 29000/29001/15432/26379/25672 端口收安全组白名单） |
| G-018 | 备份 / 恢复（平台数据可靠性） | **完全空白**：全仓无 DB 备份/恢复能力（`helloai-core` / `helloai-api` 内 grep `backup` / `restore` 零功能命中；唯一真实 `pg_dump -Fc` 用法是 `deploy/middleware/scripts/migrate.sh:67-81` 的一次性跨机迁移脚本）。**可复用资产已就绪**：MinIO 为生产主存储（`storage.type=minio`，bucket `helloai-artifacts`）+ `ArtifactStorage.listObjects(bucket, prefix)` 递归枚举（`MinioArtifactStorage.java:144`）；Redisson 4.0.0 `RLock` 与 ShedLock 6.6.0 `@SchedulerLock` 既有单飞范式 | `pg_dump -Fc` 全库 + MinIO 对象清单 + manifest 前置 peek（不解档即可判内容）+ 分布式单飞锁 + 自动/手动备份分离的保留策略 + 停机恢复流程文档化（诚实边界：运行中备份不保证多文件同一瞬间） | **P1** | 未启动（`REF-2.3` / `REF-2.4`）。落地硬约束：① 必补**恢复侧安全闸门**（跨引擎拒恢复 / schema 版本高过运行时拒恢复 / 在线恢复拒绝）；② 「`listObjects` 返回空」既可能是真空也可能是实现不支持（`ArtifactStorage.listObjects` 为 fail-safe 默认返回 `List.of()`）——**必须显式失败，不得静默产出缺对象的「完整备份」**；③ 复用既有 `pg_restore --no-owner --no-privileges` 写法 |
| G-019 | RAG 知识库（检索增强） | **完全空白**：pgvector / embedding / 知识库 / 向量 全仓零命中 | pgvector 存储与检索（**PG 单后端，不引入侧库**）+「无 KB 即摘工具」的条件可用语义位 + 注入预算（char_budget 契约）+ 引用溯源 marker（前端渲染卡片、喂模型前剥离） | **P2** | 未启动（`REF-4.x`）。**前置三件**：① PG 镜像换 `pgvector` 版（现 `postgres:16.4-alpine`，无扩展，属基础设施变更）；② 嵌入模型供应商 / 维度 / 密钥管理选型落 `design/adr/`；③ `search_knowledge` 必须经 `ToolCallbackContributor` 端口注册并登记技能包（`CODE_STYLE §35.1` 平台能力接线规则），不得给 LLM 直插域内 Service |
| G-020 | 外部 Agent 工作详情快照（子任务执行过程的取证能力） | **完全空白**：平台当前只能审计到**订单层面**（认领 / 开工 / 心跳 / 交单 / 产物 / 阻塞 / 依赖读取，经 `agent_event` + `task_timeline` + 产物对账）；**子任务内部执行过程是黑盒**（这是刻意的解耦，但带来返工争议 / 质量复盘 / 责任界定的取证盲区） | 外部 Agent **事后、经审批、可选**地提交一份全任务工作详情快照 → 平台解析 → **原文落 MinIO** + **时间线插入结构化审计事件（闭合 schema + `snapshotRef`）** | **P2（可后置）** | **新增立项（`D-2026-10-09-6⑤`，2026-10-09）**，任务锚点 `REF-7`。**动手前三项设计约束必须先定**（见 `plan/HelloAI 借鉴落地实施计划.md` §8.2）：① 与 `REF-6.2`「审计闭合 schema」的张力 ⇒ 必须双面（与 `REF-5.2b` 双视图同模式）；② 审批方是谁（倾向复用人工介入通道，不建第二套审批）；③ 落时间线的粒度。新增工具须走既有 `McpMcpServer` 守卫且**默认关闭**（`REF-6.13`） |

# 2. P0 主线

## G-001 Event Stream

验收：

- Legacy 与 Runtime 产生同一 Event Model；
- Timeline / Audit 逐步统一从 Event 获取事实；
- 一个 Run 可以按 sequence 重建轨迹；
- Event 不成为第二业务状态源；
- 写入具备幂等和可对账能力。

## G-002 Dual Executor

原则：

> Dual Executor 只是迁移策略，不是长期架构。

```text
ExecutionRouter
      ↓
 ┌────┴─────┐
 ↓          ↓
Runtime    LegacyAdapter
```

禁止：

```text
复制完整业务链
复制第二套状态机
复制第二套 Review
无幂等地再次执行副作用
```

**落地状态（2026-09-30 硬切单轨，`LOG-20260930-001`）**：上述迁移结构图已随旧链删除而失效——`RuntimeAgentRuntimeRouter` / `LegacyExecutorAdapter` / `TurnLlmCaller` 及全部旧链入口已删除，`RuntimeTurnExecutor` 为唯一 `AgentRuntime` 实现，消费链路全部真实经 `AgentRuntimeContextAssembler` 装配后走入真身；「Dual Executor 只是迁移策略」的约束已达成（双轨不复存在）。

## G-003 AgentRuntime

推荐提取顺序：

```text
1. Context
2. EventRecorder
3. ToolRegistry / ToolExecutor
4. AgentLoop
5. Session
6. Sandbox
```

# 3. P1

### Skill Capability

保持 Markdown 兼容，同时增加：

```text
version
requiredTools
dependencies
inputSchema
outputSchema
validationRules
```

### Sandbox Provider（**条件触发，不排期**）

第一阶段只完成 Provider Contract，不把 Docker/K8s 当作已完成安全隔离。

> **2026-10-09 降级（`D-2026-10-09-6③`）**：本项**不排期**。判据见 `G-005` 行实测——**当前没有可隔离的执行对象**：
>
> ```text
> 外部 agent        → 跑在它自己的终端（平台定位 = 派单方 ≠ 执行方）
> 内部 LLM agent    → 工具面 = 13 个平台 API 工具 + web_search，无 shell / 文件写
> 平台自身          → 全库无脚本引擎 / 表达式求值器，不执行任何外部内容
> ```
>
> **「五边界」中对 helloai 当前唯一有真实意义的是网络边界**——而它的正确实现是**出站 SSRF 守卫**（`REF-5.4`，平台自己发起的外联），**不是容器网络隔离**。
>
> **触发条件（任一成立 ⇒ 重新进入排期，届时启动专项并出 ADR）**：① 平台增加碰宿主的工具；② 技能包要被执行；③ 平台自持浏览器。**预案**（spec 声明化 / 探针 / scope 生命周期 / 形态 = 独立沙箱服务）见 `plan/HelloAI 借鉴落地实施计划.md` §5。

### Event Consumers

顺序：

```text
Timeline / Replay
→ Audit
→ Recovery
```

> **Fork 处置（2026-10-09，`D-2026-10-09-5`）**：**不再作为开发要求（WONTFIX）**。已建部分 = `AgentEventForkService`（快照复制，6 单测全绿、**零生产调用方**），作为**未接线的内部能力保留**；其「触发入口 / 原 Run 冻结 / 驱动新 Run 执行」三项**不立项**。依据：① 「驱动执行」自 2026-10-04 拍板起即为后置项（`review/HelloAI 架构V2进度与质量审计报告（2026-10-02）.md` §307「先做成、再做好」）；② Fork 的实际用法（分叉重跑 / 路径对比）已被 **Return**（REWORK→驳回→改派→重开工，闭环完整）与 **Replay 工作台**覆盖；③ 驱动执行须改 Run 标识模型（ADR-001）与 execution command 载荷（协作规约 §30/§31），成本与收益不匹配。**Recovery 保持在本次序内推进，优先级不因本节变化**。

### Planner 能力感知（G-010）

设计：`doc/design/Planner_Capability_Awareness.md`（2026-09-09 落稿，§7 决策 1~7 全部拍板）。

已拍板：

```text
登记口径：新 G-010（不并入 G-004）
粒度决策：rule-based 矩阵（执行者画像 × difficulty；LLM 自判后置）
装箱语义：并集（子任务级 ∪ 任务级，去重保序，子任务级在前）
目录注入：常驻（每技能一行摘要，超 20 项截断）
constraints：显式列（仅 COARSE 必填）
回流：classpath 声明态（DB 化/热加载后置）
混合粒度：FINE（白名单为空 = STANDARD）
```

实施状态（2026-09-09）：

- S1 数据层：PASS（V74 迁移 + SubTask 实体/DTO + 单测）；
- S2 拆解侧：PASS（提示词三段 + PlanDraftItem 扩展 + PlannerGranularityResolver 矩阵单测 + buildDrafts 目录过滤落库）；
- S3 传递链：PASS（mergeSkills 并集装箱：SubTaskAutoExecutionDispatcher / ReviewServiceImpl / SubTaskReviewServiceImpl / SubTaskController.execute / SubTaskDispatchServiceImpl 选人约束五点同源；inbox summary 技能要求行；SubTaskResponse REST 下行；草案确认 UI 展示/编辑 + updateDraftById fail-close 端点；executor SKILL.md 子任务级指派说明）；
- S4 双场景实测（2026-09-09，平台内链）：PASS——本机 docker 四件套 + 6565 后端 + 5173 前端实测「登录 → 平台 PLANNER 注册/绑定（API_KEY_LLM + DeepSeek）→ 需求澄清终稿 → finalize 建任务 → 异步拆解（经 findPlanByTaskId 轮询）→ 确认/拒绝」全闭环；verify-login-e2e 10 项 / verify-requirement-clarify 10 步 / verify-planner-decompose 12 步全过（EXIT=0，与 G-011 S5 合并执行，见 log 2026-09-09）。外部执行链（外部 EXECUTOR agent 场景）已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log 2026-09-10）。

验证基线：core 全量单测 0 失败；api 模块编译通过；UI type-check 通过。

### 需求包准入与不确定性显式管理（G-011）

设计：`doc/design/Requirement_Package_Uncertainty.md`（2026-09-09 落稿，§8 决策 1~10 全部拍板）。

已拍板：

```text
登记口径：新 G-011（G-010=拆解侧，G-011=准入侧+契约侧，同 G-010 D7 边界论证）
需求包：5 字段压缩版（goal / scope / outOfScope / assumptions / openQuestions）
存储：会话列 + task.context 双写
uncertainties：显式 JSONB 列（kind=ASSUMPTION / UNCONFIRMED；命名与 gap_kind 消歧）
gap_kind：后置到批次二（「已有能力」须可最小验证，依赖 G-008 能力可验证基线）
闸门：不建自动闸门（openQuestions 不阻断；人工裁决 + fail-close BLOCKED 链上报）
```

实施状态（2026-09-09）：

- S1 数据层：PASS（V75 迁移 sub_task.uncertainties JSONB DEFAULT '[]' + requirement_conversation.final_package JSONB，含列注释与验证日志；SubTask 实体 uncertainties（JacksonTypeHandler 同 dependsOn 模式）+ RequirementConversation 实体 finalPackage；Uncertainty 值对象落 task 域（kind=ASSUMPTION/UNCONFIRMED 常量，避开 task→planner 反向依赖）；RequirementPackage record + RequirementPackageParser 静态工具（planner 域，同 TaskAgentPolicy 防御式模式：键缺失/类型异常回落空集合、数组元素仅保留字符串；fromContext 键空间隔离；render 五字段逐项列表空数组字段不渲染）；单测 10 用例覆盖全字段/降级/键隔离/渲染；core 全量单测 1329 用例 0 失败）；
- S2 澄清侧：PASS（两处终稿提示词 requirement-clarify.md / requirement-finalize.md 同步增 package 五字段结构化输出要求——goal/scope/outOfScope/assumptions/openQuestions + 提炼约束（推断项进 assumptions 且 description 同步标注（推断）、六维自检第 6 维产出落 outOfScope、数组可为空不得虚构条目）；ClarifyReply 增 package 字段（JsonNode 防御接收防非法类型击穿解析 + @JsonProperty 映射关键字键名）；ClarifyReplyParser.resolvePackage 防御式解析（缺失/非对象形态 → null 降级纯文本终稿 = 现状行为）；updateFinalDraftFields 定点写扩展 final_package 条件覆盖写（null 不动列），runFinalizeLlmRound（/task 直出）与 runLlmRound（澄清轮）两终稿轮同步扩展；buildTaskFromDraft 建任务双写 task.context.requirementPackage（regenerate 复用会话侧需求包自动双写；与 runningSpec 键空间隔离）；单测 7 用例（终稿带/无/非法 package 三态 + finalize/regenerate 双写 + 两模板契约防回退）；core 全量单测 1336 用例 0 失败）；
- S3 拆解侧：PASS（planner-decompose.md 模板四增量——占位符清单增 {{REQUIREMENT_PACKAGE}} + 「需求包（结构化准入产物）」段（明确 openQuestions=经人工确认的待确认缺口、assumptions=已申报推断项，拆解须按下文继承规则逐条评估）+ 拆解要求第 10 条 uncertainties 申报与继承规则（assumptions 强相关条目继承 ASSUMPTION、openQuestions 相关条目必须继承 UNCONFIRMED、禁止把推断 silently 写进目标）+ 拆解原则增 outOfScope 边界硬约束（明确排除项绝不拆进任何子任务）+ schema 增 uncertainties 字段（可空数组，kind 只能取 ASSUMPTION/UNCONFIRMED）；PlanDraftItem 增 uncertainties（JsonNode 防御承接，非数组/转换失败回落空列表不阻断拆解）；renderPrompt 增 {{REQUIREMENT_PACKAGE}} 渲染（RequirementPackageParser.render(fromContext) 统一入口，无需求包渲染占位文案行为零变化）；buildDrafts 三增量——uncertainties 落库（非法 kind 降级 UNCONFIRMED 不丢弃 + timeline task_plan_uncertainty_degraded 审计；空白 note 丢弃；与 G-010 幻觉标签丢弃模式差异理由：note 是自由文本标注本身有信息量，降级到更严语义符合 fail-close）+ COARSE constraints 缺失 timeline task_plan_constraints_missing WARN（G-010 缺口④清偿；粒度判定提前 doDecompose 一次判定 renderPrompt 与 buildDrafts 共用）+ D5 兜底审计（需求包 openQuestions 非空但拆解产物无任何 UNCONFIRMED 继承 → task_plan_uncertainty_missing WARN，仅审计 openQuestions→UNCONFIRMED 不审计 assumptions，不阻断落库）；单测 11 用例（渲染/占位/落库/降级审计/非数组防御/COARSE 两态/D5 三态）+ 模板契约测试扩 planner 1 用例防回退；core 全量单测 1347 用例 0 失败）；
- S4 传递链：PASS（五个消费点全链路落地——①内部执行 prompt：buildUserPrompt 四要素段后增 D6 三段（执行约束补偿行（G-010 缺口清偿：约束落库后执行者不可见）+ 不确定性分级申报（ASSUMPTION 后缀「可自行验证，推翻即上报」/ 其余含人工编辑非法值一律 UNCONFIRMED 语义后缀 fail-close）+ 验收事实回源声明固定文本常驻，空值零注入）；②审查装配：ReviewExecutionEngine 增 {{CONSTRAINTS}}/{{UNCERTAINTIES}} 两占位符（空时渲染「（无）」）+ subtask-review.md 待核验段两行 + 轨道 A 增第 8 条执行约束遵守核验（G-010 缺口③清偿）与第 9 条不确定性分级核验（ASSUMPTION 不构成驳回理由、UNCONFIRMED 产出须含验证结论或 BLOCKED 上报痕迹，否则不达标）；③REST 下行：SubTaskResponse + toResponse 同步 uncertainties + DraftUpdateRequest/Controller/Service 四参 updateDraft 链（null=不修改/空数组=清空/空白 note 丢弃/非法 kind 降级 UNCONFIRMED，D3 fail-close 同口径）；④inbox 摘要：ASSIGNED 分支 UNCONFIRMED 计数>0 追加「待确认: N 项」（ASSUMPTION 不计入，纯文本形态不动 inbox 契约面）；⑤草案确认 UI：实体/API 类型扩展 + PlanReviewDialog 不确定性列（「N 项待确认 · M 项假设」压缩展示）与编辑弹窗逐条增删改（kind 下拉 + note 输入 + 保存前过滤空行）；单测 12 用例（执行 prompt 四用例：注入/零注入/空列表/非法降级 + updateDraft 归一化三用例 + 审查渲染两用例 + inbox 摘要两用例 + subtask-review 模板契约一用例）；core 全量单测 1359 用例 0 失败；api 编译通过；UI type-check 通过）；
- S5 实测（2026-09-09，与 G-010 S4 合并执行）：PASS（平台内链）——①jsonb 定点写 bug 修复：updateFinalDraftFields 原 lambdaUpdate.set(Map) 直绑 SQL 被 PG 拒（wrapper 路径不套用实体 JacksonTypeHandler），改 RequirementConversationMapper + XML `#{finalPackageJson}::jsonb` 条件写（null 不动列，参照 SubTaskMapper.updateDependsOn 先例），RequirementClarifyServiceTest 92 用例全绿，实测终稿 finalTitle/finalPackage 真实落库；②确认卡协议核验：卡切换分支仅认 selections 快照（isAcceptSelected），纯文本「确认」不触发切换，脚本改带 selectedOptions 应答（questionId=confirm-switch）后终稿正常产出；③脚本端点失配修复（N-0xx RPC 风格化收口效应，shell 2 脚本 20 处 + ps1 6 脚本 22 处）：sendMessageById/finalizeById/abandonById 系列 + planById/findPlanByTaskId/confirmPlanByTaskId/rejectPlanByTaskId 系列 + 拆解异步化轮询适配（planById 恒空返回，deepseek v4-pro 实测拆解约 4 分钟 → 轮询窗口默认 360s）；④实测结果：verify-requirement-clarify 10 步（终稿 finalTitle=内部日报统计模块 → finalize → FINALIZED → 异步拆解 6 草案 → abandon 回归）、verify-planner-decompose 12 步（5 草案 → PLANNING → confirm 转正 PENDING/ASSIGNED → IN_PROGRESS；reject 路径 CANCELLED → Task 回退 PENDING；重复拆解守卫 500）全过；⑤技术债登记：AgentController.validateModelType 在 registerOrGet 幂等查找之前执行，同模型同角色残留 agent 重复注册必 500（重跑前须逻辑删除残留 agent）；实测残留数据：agent 5 + task 3 + 会话 2 + 草案若干（cleanup-test-data.sql 可清理）。外部执行链（外部 agent 场景）已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log 2026-09-10）。

- P1/P2 批次（2026-09-11，生成能力退化修复）：PASS（代码 + 单测；实测回归脚本已升级待跑）——①P1-1 任务级验收条目：RequirementPackage 六字段 + RequirementPackageParser（KEY_ACCEPTANCE_CRITERIA / parse / render，JSONB 加键**无 DDL 迁移**）+ requirement-clarify.md / requirement-finalize.md package schema + planner-decompose.md「任务级验收覆盖」bullet（封闭集合全覆盖 + 子任务 acceptance 适用时标注「（对应任务级验收条目 N）」+ 契约/过程性子任务可不标注 + acceptanceCriteria 空则不做要求）；设计文档同步修订（状态横幅 P1/P2 登记 + D1 六字段 + §2.2 schema + §4 S2/S3/S5 + §6 验收 7~11 + §7 决策 #2 反转与 11~17），差距表本行同批登记（诚实登记原则）；②P1-2 拆解必填字段 fail-close：parseDraftItems 逐条校验 title/content/deliverable/acceptance（null/blank 即整批 BizException），抛错前记 timeline `task_plan_draft_field_missing`（payload: draftSeq/field/title/rawOutputSummary）；③P1-3 终稿信息量：四小节关键词组（背景|目标 / 范围|边界 / 交付物|交付 / 验收）宽容匹配，缺小节同轮追加纠偏指令**重试 1 次**；重试异常/非 final/仍缺 → 放行首轮 + timeline WARN `requirement_description_section_missing`（fail-open，不阻断建任务；runFinalizeLlmRound / runLlmRound 两终稿轮同覆盖），两提示词「与 description 同源」改为职责划分（description=完整规格四小节正文，package=结构化索引，禁止以 package 概括代替正文）；④P2-1 主任务详情：TaskList.vue 描述弹窗扩展为「任务详情」（任务描述 + 需求包六字段含任务级验收标准块），`task.context.requirementPackage` 缺失/非法/六字段全空整块隐藏（后端零改动，context 无 @JsonIgnore）；⑤P2-2 回归口径：verify-requirement-clarify-structured.ps1 由「追问即 abandon」扩至「澄清 → 终稿」，nudge「没有其他要求，请直接生成终稿」后 finalizeById，软断言 description 小节组 ≥3 / 长度 ≥300 / final_package 存在（LLM 输出不可控不 hard fail；FINALIZED 时跳过 abandon 并提示清理测试数据）；⑥P2-3：planner-decompose.md COARSE 行 acceptance 补「与交付物的可观察判定方式（判定动作 + 预期结果）」（不动 STANDARD/FINE）；⑦P2-4 审查驳回可执行性：subtask-review.md 轨道 A 第 10 条「缺失证据清单」+ 输出 schema `missingEvidence`（acceptanceRef 用验收标准**原文子串**机械可核 / missing / howTo），VerdictParser.normalizeMissingEvidence 防御归一（缺失/非数组/元素非对象 → 空清单），rejectAndRework 写入 `context.reviewHistory` 当前轮（JSONB 加键零迁移，同 executorDoneIssues 先例），buildReworkSummary 渲染「缺失证据清单」段随返工 inbox 摘要下行（不做 acceptance 编号化协议，方案 B 否决理由见设计 §7#17）。**测试证据**：P1-1 相关四单测文件全绿（RequirementPackageParserTest / RequirementClarifyServiceTest / PlannerDecomposeAsyncServiceImplTest / RequirementPromptTemplateContractTest）；P2-4 相关（SubTaskReviewServiceTest 46 / SubTaskServiceHandoverTest 16 / 模板契约 4）0 失败；core 全量 1380 + api 58 用例 0 失败；UI `npm run type-check` 0 error。**红线**：全程无 DDL 迁移、无新增 agent→task 依赖（RequirementPackage 在 planner 域、review 读 task context 均为既有合法方向）。


### 登录鉴权与 RBAC 权限体系（G-012）

登记口径：新 G-012（登录自建 → Sa-Token 统一会话 + RBAC 授权；2026-09-12 底座 + 闭环落地）。

已落地：

- S1 数据层：PASS——V77 迁移四表（sys_role / sys_permission / sys_user_role / sys_role_permission）+ 内置种子（SUPER_ADMIN 全权限 / ADMIN 显式 20 权限码，23 权限码 = 17 MENU + 6 API）+ 存量 sys_user.role 单字段迁移关联表；dev 库事务回滚验证 PASS。V78 扩展 sys_permission 增加 parent_id / path / icon（菜单树 DB 化数据底座）+ 新增 user:view / role:view / permission:view / deadletter:view 四个 MENU 权限码 + ADMIN 绑定 deadletter:view。
- S2 会话层：PASS——AuthServiceImpl 由自建 Redis Token 会话切换 Sa-Token（StpUtil.login / getLoginIdByToken / logoutByTokenValue，token 走 X-Admin-Token 头，active-timeout=28800s 滑动续期；Redis 会话键前缀取 sa-token.token-name，即 X-Admin-Token:，非库默认 satoken:——勘误 2026-09-13，见《基础架构调整实施计划》§12.1）；AuthService 接口清掉旧常量与文档；AuthServiceTest 10 用例覆盖。**存量会话无缝迁移**：validateAdminToken 在 getLoginIdByToken 返回 null（Sa-Token 对未命中 token 返回 null 而非抛异常，1.44.0 实测语义）时回退读旧 Redis key（auth:admin:token:{token}），命中则以**原 token 值**重建 Sa-Token 会话并删除旧 key——前端零改动、旧会话自动续用；Docker 实测：预置旧会话 → /api/auth/me 200 且旧 key 清除、同一 token 二次请求仍 200（已由 Sa-Token 会话承接）。损坏 JSON 清理 + 双未命中 401 有单测覆盖。
- S3 授权层：PASS——StpInterfaceImpl（用户 → 角色码 + 权限码，SUPER_ADMIN 返回 "*" 全权限通配）；SaInterceptor 注册 /api/** 注解鉴权；SysRoleController（role:manage）/ SysUserRoleController（user:manage）/ SysPermissionController（role:manage）@SaCheckPermission 接入；GlobalExceptionHandler 补 NotLoginException→401 / NotPermissionException→403。
- S4 前端菜单过滤：PASS——登录响应携带 permissions/roles（SUPER_ADMIN="*"）；auth store 持久化权限码 + hasPermission（"*" 通配兜底）；MainLayout 菜单数据化按权限码过滤（15 个菜单项 ↔ V77 MENU 权限码一一对应）。
- S5 角色 / 权限 / 用户管理前端页面：PASS——新增 UserList.vue / RoleList.vue / PermissionList.vue 三页（用户分页+搜索+编辑+分配角色+重置密码 / 角色 CRUD+权限绑定（菜单/接口分组勾选，SUPER_ADMIN 禁删）/ 权限码列表+搜索）；路由 system/users|roles|permissions；rbac.ts + paths.rbac + types/system.ts 接入；Element Plus 表格/对话框/分页齐全。
- S6 菜单树 DB 化：PASS——MenuController /api/admin/menus/tree + SysPermissionQueryService.listMenuTree（type=MENU + 权限码过滤 + parent 挂接 + 「DB 有子但过滤后无可见子节点」剔除空父）；MainLayout 改调菜单树接口动态渲染（icon 按 @element-plus/icons-vue 组件名映射）；Docker 实测：SUPER_ADMIN 返回 18 根节点（含系统设置→用户/角色/权限管理父子层级、死信池 query 菜单），ADMIN 无 user/role/permission:view 时系统设置整体隐藏。
- S7 基础设施修复：PASS——用户分页 total 恒 0 根因二连：① mybatis-plus 3.5.9 起 PaginationInnerInterceptor 拆分到 mybatis-plus-jsqlparser 独立模块且为 optional，需显式引入（helloai-start 增加 mybatis-plus-jsqlparser-4.9）；② mybatis-plus 版本 3.5.9 → 3.5.12（聚合 POM 已含 jsqlparser 模块，本地 m2 全量缓存）。修复后 Docker 实测 /api/admin/users/page 返回 total=2 / pages=1 / LIMIT 生效。

验证基线：core 全量单测 1399 用例 0 失败（AuthServiceTest 10 含会话迁移 3 用例 + SysRoleServiceImplTest 6 + SysPermissionQueryServiceImplTest 5 等，mybatis-plus 3.5.12 下复跑全绿）；api 全量单测 58 用例 0 失败；UI vue-tsc type-check + 生产构建通过。**Docker 本机实测**（postgres/redis/rabbitmq/minio 容器 + local profile，2026-09-12）：admin/admin123 登录 → /api/auth/me 权限=["*"]；菜单树 18 根节点；角色 CRUD + 权限绑定（创建 TESTER→绑 18/19→回读→改名→删除）全链路 200；用户分页 total=2、testuser1 分配 ADMIN 角色后 roleCodes=['ADMIN']；存量旧 Redis 会话无缝迁移 200 且旧 key 清除、同 token 复用 200；前端登录 + 侧边栏动态菜单渲染（浏览器实测），三管理页面模块 Vite 转换 200 无报错。

后置缺口（原登记三项已全部关闭）：无。

### 基础架构深化（G-013，参考 JeecgBoot，实施编排独立）

登记口径：G-012 闭环后，参考同级目录标杆项目 JeecgBoot-main 的菜单/用户/角色/权限实现，
梳理出基础架构深化差距（动态路由 / 按钮权限 / 动作级权限码 / 菜单管理页 / 差异更新 / 扩展能力），
作为**基础架构专项**独立实施——任务编号 **BASE-1.x / BASE-2.x / BASE-3.x**，
与差距表 G-xxx、重构实施计划 P0~P3 / A1~A7 / S1~S8 完全错开，不混用。

- 专项文档：`doc/plan/HelloAI 基础架构调整实施计划.md`（背景 / 已完成基线 G-012 S1~S8 / 标杆设计要点 / 三批次任务明细 / 验收）。
- 目标架构融合：`doc/HelloAI 目标架构.md` 新增 §12 基础架构（平台底座），与业务五层正交。
- 批次状态：**三批次（BASE-1.1~1.6 / 2.1~2.3 / 3.1~3.3）已全部落地（2026-09-12，PASS）**——详见 `log/2026-09.md`「批次一 + 批次二全量落地」「批次三全量落地」两段。
- **批次四（2026-09-13 立项，部分落地）**：认证收口 + 角色体系 + 全量接口授权化（BASE-4.1~4.5）——代码核查暴露三处缺口：① Sa-Token 认证未收口（全仓 0 处 `StpUtil.checkLogin()`，`active-timeout` 滑动续期实际失效）；② 身份数据双写不一致（`sys_user.role` 死字段 + `SysUserServiceImpl.create()` 漏写权威关联表）；③ 接口授权覆盖极低（`@SaCheckPermission` 仅 24 / 234，业务面 125 接口全无授权，只读角色无法落地）。任务明细见《HelloAI 基础架构调整实施计划》§9，目标边界收敛见《HelloAI 目标架构》§12.2/§12.3。**BASE-4.1 PASS**（新增 `authenticateAdmin` 标准 checkLogin 守门 + `AdminOnlyInterceptor` 事实源回归 Sa-Token + `/registerWithToken` 白名单修正；core 1449 用例 0 失败；E2E 授权断言改造前后一致 + 滑动续期实证 + 存量会话迁移 OK；残留：`McpAuthFilter` 未收口、active-timeout 超时触发未实测）；**BASE-4.2 PASS**（V87 补齐 + `DROP COLUMN sys_user.role`；`create()` 增 `remark` 参数并补插 `sys_user_role`；删 `SysUserItem.role`/`LoginResponse.role`/`AdminSession.role` + 前端类型同步；core 1453 / api 58 / job 69 用例 0 失败；E2E 列已删 + 登录 `permissions=["*"]` + 建号签发角色与 remark 落库 + 测试数据零残留）；**BASE-4.3 / 4.4 合并实施（PASS，2026-09-13）**：V88 新增 71 动作码（管理面 32 + 业务面 39，id 49~119）+ 6 个粗粒度码退役；V89 新增 NORMAL_USER / GUEST 角色并完成 ADMIN (57) / NORMAL_USER (55) / GUEST (16) 绑定（GUEST 零写码）；`@SaCheckPermission` 由 24 → **131 处**（新增 107，覆盖 23 控制器；**2026-09-29 复测为 144 处，见 §0 `LOG-20260929-001`**），Agent / 公开白名单 12 控制器实测 0 注解；前端 v-auth 对齐 9 个页面 + 补漏 4 处。**核对**：V88 的 71 码全部被引用（零幽灵码），注解引用 88 码 = 71 + 既有 17；编译 exit 0 / core+job+api 单测全绿 / UI type-check 0 error；V88/V89 事务内干跑通过（INSERT 0 32+39 / 2 角色，ROLLBACK 无残留）；**四角色差异 E2E 待重启后执行**。**BASE-4.5 已实施（2026-09-13）**：管理员建号 `POST /api/admin/users`（`user:add`，建号即签发角色）+ 自助注册 `POST /api/auth/register`（受 `sys_config.auth.register.enabled` 门控，**默认关闭** —— V90；注册固定绑 GUEST 只读）+ UserList「新增用户」+ 登录页注册表单（开关开启时渲染）；`/api/setup/getStatus` 增 `registerEnabled` 供登录页判定。验证：编译 exit 0 / core+job+api 单测全绿 / UI type-check 0 error；E2E 待重启复验。**批次四（4.1~4.5）实施完毕**。详见 `doc/log/2026-09.md`「批次四收官」段。
- 授权补充（2026-09-12，PASS）：**ADMIN 角色绑定部门管理权限**（BASE-3.2 授权补充）——V84 迁移 `V84__rbac_admin_depart_permission.sql`（role_id=2 × `depart:view/add/edit/delete`，`ON CONFLICT DO NOTHING` 幂等）+ 界面等效路径 `PUT /api/admin/roles/2/permissions`（`lastPermissionIds` 差集，原 21 码零丢失 → 25 码）。验证：Flyway version=84 success=t 且二次执行 `INSERT 0 0` 无重复；ADMIN 身份 `/api/auth/me` 含 4 个 depart 码、菜单树含「系统设置 → 部门管理」、部门 tree/新增/删除全 200；未授予 `position:*`；测试数据零残留。详见 `log/2026-09.md`「ADMIN 角色绑定部门管理权限」段。
- 落地要点：V79 component + 12 动作级权限码（V80 补 path）；SysPermissionService CRUD + DTO 投影；三 Controller 方法级 `@SaCheckPermission`；**SaInterceptor 注册修复**（G-012 遗漏）；前端动态路由（`router/dynamic.ts` + 白名单守卫 + NotFound）+ v-auth；菜单管理页树形 CRUD；授权 `lastPermissionIds` 差集；**BASE-3.1** V81 hidden/keep_alive/external_link + 前端渲染适配；**BASE-3.2** V82 部门四表（部门 + 用户-部门）+ Service/Controller + DepartList + 用户组织归属；**BASE-3.3** V83 rule_flag + 数据规则表 + 受控枚举解析 + 用户列表数据权限。
- 收口（2026-09-12，PASS）：**岗位能力彻底移除 + 拆出独立「菜单管理」入口**（V85 / V86）——岗位判定对本系统无场景（部门已承担组织归属 + 数据权限范围），经确认彻底移除：V85 清 `position:*` 关联并软删 4 码 + `DROP TABLE sys_user_position`/`sys_position`（表中 0 行，不可逆）；**V86 将软删遗留行物理清理**（`DELETE FROM sys_permission WHERE code LIKE 'position:%'`，含任意 deleted 状态；关联表防御性清理）。后端删 11 文件（entity×2/mapper×2/Service(+Impl)×2/Controller/DTO×3/单测）+ 收敛 SysUserRoleController/SysUserItem；前端删 PositionList.vue + 岗位 API/路径/类型 + UserList 岗位列与勾选（保留部门）。菜单维护独立：V85 新增 `menu:view`（id=48，`/system/menus` → `system/MenuList`），新增 MenuList.vue（仅 type=MENU 树形 CRUD）、PermissionList.vue 收敛为「权限管理」= 仅 type=API 权限码 + 数据规则，两页共用 `/api/admin/permissions`。详见 `log/2026-09.md`「岗位能力移除 + 菜单管理独立入口」段。
- 实测修复（5 个）：① SaInterceptor 从未注册；② `addRoute` 父参数须为路由 name；③ 登出后 `routesBuilt` 未重置致权限串用（改按 token 跟踪）；④ **catch-all 用 redirect 导致登录后落 404**（改直接渲染 NotFound 组件，保证「首次导航 → 构建 → 重入原目标」链路）；⑤ **会话失效静默落 404**（HTTP 401 走 axios error 分支未清登录态 → `request.ts` 补 401 清态 + `router` 守卫区分 401/未登录跳 `/login`、其余失败重试 1 次并提示）。
- 验证：core **1437 用例 0 失败**（V85 后移除岗位测试 7 用例）/ api 58 用例 0 失败 / UI type-check + build / Docker API 全链路（部门/数据权限/渲染字段；岗位端点已 404）/ 浏览器实测（SUPER_ADMIN 系统设置 = 用户/角色/菜单/权限/部门，无「岗位管理」；ADMIN 授权前无系统设置且 `/system/departs` 404，V84 授权后可见「系统设置 → 部门管理」并可 CRUD；数据权限 CUSTOM 过滤生效）；既有 PS1 因本机无 pwsh 为 NOT RUN（等价断言 PASS，环境限制非跳过）。
- 红线：不新增第二套权限体系、不触碰 Event/状态机/Scheduler/Workflow/Review、外部 Agent 契约不变、V77~V85 已提交 DDL 只读（新增 V86）、数据权限受控枚举无动态 SQL 拼接。
- 合规基线（2026-09-12）：专项文档 §4 强制遵循《HelloAI_AI开发协作规约》（Step 0~9 生命周期 / 复用既有 PS1 / 完成报告 16 节模板）+《HelloAI_CODE_STYLE》（RBAC 归 system 域不反向依赖业务域 / DTO 投影不暴露 Entity / @Transactional / Flyway V79+ / §43 认证授权分离 / v-auth 统一 hasPermission）。

### 备份 / 恢复（G-018）

任务锚点：`plan/HelloAI 借鉴落地实施计划.md` `REF-2.3`（备份/恢复）与 `REF-2.4`（停机恢复流程文档化）；设计参考 Octop `infra/backup/`（`pg_dump -Fc` + manifest 前置 peek + 单飞锁 + 仅淘汰自动备份）。

链路：

```text
备份：pg_dump -Fc 全库 ──► MinIO 对象清单（listObjects 递归枚举）──► manifest 前置写 ──► 分布式单飞锁
恢复：manifest peek 校验 ──► 恢复侧安全闸门 ──► pg_restore ──► 对象回填 ──► 停机恢复流程（文档）
```

不变量与诚实边界：

```text
运行中备份 ≠ 多文件同一瞬间一致（须在文档中明示，不得宣称「一致快照」）
listObjects 为空 / 失败 ⇒ 备份显式失败，不得产出「完整备份」
手动备份永不被自动保留策略淘汰（仅自动备份参与 prune）
恢复侧三门：跨引擎拒绝 / schema 版本高过运行时拒绝 / 在线恢复拒绝
```

# 4. P2

```text
Quality Gate
Capability-based Agent Routing
Historical Success
Cost / Latency
RAG 知识库（G-019，可后置）
外部 Agent 工作详情快照（G-020，可后置）
```

### RAG 知识库（G-019）

任务锚点：`plan/HelloAI 借鉴落地实施计划.md` `REF-4.x`；设计参考 Octop `infra/knowledge/`（chunk → parse → embed → index → retrieve → citations），但**不抄其 SQLite 侧库**——PG 单后端是既有优势。

前置三件（未完成前不进入实现）：

```text
① 基础设施：PG 镜像换 pgvector 版（现 postgres:16.4-alpine 无扩展）
② 架构决策：嵌入模型供应商 / 维度 / 密钥管理（credential_vault）落 design/adr/
③ 接线口径：search_knowledge 经 ToolCallbackContributor 端口注册 + 登记技能包（CODE_STYLE §35.1）
```

先定「什么不许进上下文」，再做检索：

```text
无 KB ⇒ 工具从列表摘除（消费条件可用语义位，与 G-004 同源）
注入预算（char_budget 契约）⇒ 超预算截断可断言
引用溯源 marker ⇒ 喂模型前剥离、前端渲染卡片
```

### 外部 Agent 工作详情快照（G-020）

任务锚点：`plan/HelloAI 借鉴落地实施计划.md` §8（`REF-7`）。**来源 = 用户对平台定位的澄清**：平台**不干涉**外部 agent 怎么做（派单方 ≠ 执行方），但需要一个**事后、经审批、可选**的取证通道，把子任务内部过程补进审计。

链路（形态待三项约束拍板后细化）：

```text
外部 Agent（事后、可选）→ 提交工作详情快照（新 MCP 工具，走既有守卫）
        → 原文落 MinIO（内容哈希 key）
        → 平台解析 → 结构化摘要
        → 时间线插入一条审计事件（闭合 schema + snapshotRef）
```

**动手前三项约束必须先定**（未定不进实现）：

```text
C1 与 REF-6.2「审计闭合 schema」的张力 ⇒ 必须双面（原文落对象存储 + 时间线只放结构化索引 + ref）
C2 审批方是谁 ⇒ 倾向复用人工介入（HUMAN_REVIEW）通道，不建第二套审批
C3 落时间线的粒度 ⇒ 倾向先做「一条汇总事件 + ref」，逐阶段拆解后置
```

**红线**：新增工具须走既有 `McpMcpServer` + `assertAgentActive` / `assertToolEnabled` / `refreshDutyLease` 守卫（不建平行通道），且**默认关闭**（`REF-6.13`：新能力默认关闭、通过注入启用）。

# 5. P3

```text
Dynamic Workflow
LLM-generated branching
Advanced Sandbox
Cross-session optimization
```

# 6. 明确不再作为开发要求的口径

```text
❌ 为了凑 Harness 功能而复制 Harness
❌ SkillRegistry 作为独立“大框架”重新建设
❌ Sandbox = Local/Remote Environment 的同义词
❌ Dual Executor 永久双轨
❌ Planner / Executor / Reviewer 各自拥有完整执行能力
❌ 新建第二套 Workflow Runtime
```

# 7. Gap 登记与回流规则

## 7.1 孤儿项回流规则与已登记项

设计文档（专项设计 / 执行方案 / ADR）中作出「推迟到 X 期」「划远期」「降级交付」的决定时，必须在本表同步登记对应条目或显式记 WONTFIX，不得只留存在设计文档内——否则该承诺会随批次闭环从索引中消失。

### 7.1.1 契约层能力注入与最终报告整合（2026-09-28，依据 `doc/archive/implemented/HelloAI_契约层能力注入与最终报告整合改造方案.md` §8）

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| N1 | 报告分章 map-reduce（逐子任务章节独立成文） | 登记待决策项（A5），决策前不实施 | G-016 |
| N2 | SYNTHESIZER 角色 / 任务级 reportAgentId（报告执笔 Agent） | 依赖 Role Layer 角色模型调整，后置 | G-016 |
| N3 | 同步 LLM 调用迁入 AgentRuntime | 后置；契约层注入挂点随迁 | G-002 / G-016 |
| N4 | 通用 Quality Gate（Rule + Test + LLM 统一决策） | 后置；报告场景已有实例 | G-007 / G-016 |
| N5 | Skill Instructions 字段结构化 | 约定反转登记，后置 | G-004 |
| N6 | final_report 注册为 attachment（交付形态统一） | 后置 | G-016 |

### 7.1.2 借鉴落地专项（2026-10-09，依据 `plan/HelloAI 借鉴落地实施计划.md` + `D-2026-10-09-5`）

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| R1 | **Fork 触发入口 / 原 Run 冻结 / 驱动新 Run 执行** | **WONTFIX**（用户裁定，2026-10-09）——「驱动执行」自 2026-10-04 起即为后置项，其用法已被 Return + Replay 覆盖；须改 ADR-001 Run 模型与 execution command 载荷，成本收益不匹配 | G-001 · G-006 |
| R2 | `AgentEventForkService` 快照服务本体 | **保留**（未接线的内部能力，零生产调用方、零运行成本）；删除与否另行裁定 | — |
| R3 | 备份/恢复与 RAG 知识库 | 正式立项 → `G-018` / `G-019`（非回流项，此处仅登记立项出处） | G-018 · G-019 |

## 7.2 历史编号映射（2026-09-07 文档重构）

| 旧编号（已归档） | 新编号 | 能力 |
|---|---|---|
| N-013 | G-003 | Harness 执行循环（AgentLoop / ToolExecutor） |
| N-014 | G-004 | Skill 元数据结构化 |
| N-015 | G-005 | Sandbox Provider 化 |
| N-016 | G-006 | Event Stream 消费侧（Replay / Audit） |
| N-017 | G-008 | Agent Fleet 能力化选人 |
| N-018 | G-007 | Quality Gate |
| N-019 | G-009 | Workflow Engine 增强（远期） |

旧编号明细与登记背景见 `archive/legacy/V1_HelloAI 实现差距表.md` 与 `doc/log/2026-09.md`（LOG-20260907-001）。
