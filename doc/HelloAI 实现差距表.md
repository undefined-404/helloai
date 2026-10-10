# HelloAI 实现差距表

> **状态：CURRENT GAP**
>
> 本文只记录**当前 → 目标**的真实差距；**只保留最新状态、随迭代就地更新**，不累加过程记录。
>
> **最后更新：2026-10-09**
>
> 过程与订正历史按「做过什么」归 `doc/log/`：2026-09 批次见 `doc/archive/log/2026-09.md`，2026-10 批次见 `doc/log/2026-10.md`。
>
> 当前进度**以 §1 总体矩阵为准**；本表是「当前差距」的唯一事实源。

# 0. 当前生效的口径与决策

> 只登记**仍然生效**的口径与决策；过程与订正历史见 `doc/archive/log/2026-09.md` / `doc/log/2026-10.md`。

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

**状态词表（协作规约 §6.3）**

> `TODO / DESIGNING / IMPLEMENTING / VERIFYING / DONE / PARTIAL / BLOCKED / DEFERRED / WONTFIX`
>
> **本表落位 = §1 矩阵「处置」列首词**。四条配套口径：① 一行只允许一个状态词；子项状态不同时取最需关注态（偏序 `BLOCKED > DEFERRED > PARTIAL > TODO > DESIGNING > IMPLEMENTING > VERIFYING > DONE`），明细写在状态词之后；② `WONTFIX` 只用于整行都不做，单子项不做写 `DONE · 附：X 子项 WONTFIX`；③ 第 3 列「当前状态」**不得**使用状态词（改用事实语），两列都表状态是新漂移源；④ **不设进度百分比列**，需要量化时用子项计数（`未达成 3 项`）。
>
> 机械守卫：`scripts/shell/verify-doc-gap-table.sh`。

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
- **D-2026-10-10-1｜能力包安装入口（REF-1.6）技术裁定**（用户，2026-10-10）：① 技能包落**受控存储**（PG 元数据 + 正文 / MinIO 原始 zip 存档），**不走宿主文件系统** ⇒ **不触发** `G-005` 条件①「平台增加碰宿主的工具（自持 shell / 文件写）」，REF-3 沙箱维持「条件触发」；但**不得**据此宣称「安全沙箱已完成」（协作规约 §22）；② 技能正文落 PG——`resolve()` 在每轮装配热路径读正文，正文只在 MinIO 会使「MinIO 故障 ⇒ 技能注入整链断」；③ **不建新 ADR**（全仓仅 `ADR-001` 执行模型定稿；升级触发见 `§7.1.3 R6`）；④ 命名统一「**技能包**」/ `Skill Package`，清单文件 `skill-package-manifest.md`（**取代**原「能力包 = Capability Package」建议）；⑤ 外部技能目录 **WONTFIX**（`§7.1.3 R4`）；⑥ 安装 / 卸载显式落 `skill_package_audit`（`§7.1.3 R5`）；⑦ **版本策略**：唯一键 `(name, version)` **多版本共存**；同名且版本更高 ⇒ 需确认（409 + `confirmUpgrade` 重发），更低或相同 ⇒ 拒绝；**回滚 / 降版不复用安装入口**，走 `POST /api/skills/packages/{name}/activate`（权限复用 `skill:install`）；⑧ **选版与内置保护**：同 name 至多一行 `ACTIVE`（partial unique index），目录只出 ACTIVE 行（**取代** `byName()` 的静默 `map.put` 覆盖）；**与内置 `eng-*` 同名一律拒绝安装**（不论版本高低）。详案与执行次序（`REF-1.6 → REF-1.5 → REF-1.4`）见 `plan/HelloAI 借鉴落地实施计划.md` REF-1.4/1.5/1.6 节。

**当前冻结基线**

- **§11.3「Controller 不得暴露 core Entity」**：命中 **19 处 / 8 控制器**（冻结未清偿 —— 改动 API 响应契约须与 `helloai-ui` 联动，单独立项）；校验 `scripts/powershell/verify-code-style-p0-layer.ps1 -StaticOnly`。
- **架构守卫**：组 1 反向 / 组 2 mapper 为 `block`，基线全 0；组 3 前向为 `warn`（`task->agent` 57 / `planner->agent` 34 —— 后者含 `WebSearchToolCallback` 对 `ToolContext` 的声明面依赖，属 §7.2 端口反转的机制性代价，**不刷冻结基线**）。

---

# 1. 总体矩阵

| ID | 能力 | 当前状态 | 目标 | 优先级 | 处置 |
|---|---|---|---|---|---|
| G-001 | Agent Event Stream | Run / Turn / Step + Event 契约已具备；Timeline 已并轨 agent_event；Replay / Audit 读侧已具备，并经 REST（四端点）+ UI 事件流工作台暴露 | 统一事件契约和消费体系 | **P0** | DONE · 事件契约与消费体系（Timeline / Replay / Audit）验收成立。Recovery 消费面见 §2 Event Consumers |
| G-002 | Executor 迁移 | 实现层唯一化：旧链入口全部删除，`RuntimeTurnExecutor` 为唯一 `AgentRuntime`（`agentRuntimes.get(0)` 直取，无路由无排序）；消费链统一经 `AgentRuntimeContextAssembler` 装配 → 真身执行 → `ExecutionResultHandler` 回写；`runtimeEnabled` / `v2-enabled` / `gray-percent` 已删除——无配置级回退开关 | Runtime 成为唯一执行契约，旧实现退出 | **P0** | DONE · 单轨收敛完成，旧实现已退出（`LOG-20260930-001`）。回退手段 = `git revert`（`D-2026-09-30-3`：硬切为单向门，不保留回退开关，但保留兜底策略与同层恢复） |
| G-003 | AgentRuntime | 八件套已全部落地（Context / Session / Skill / Tool / Loop / Event / Environment / SandboxProvider 契约）；工具侧两个语义位已具备：「**按条件可用**」（`ToolCallbackContributor.toolAvailability()` 返回 false ⇒ 该工具从模型可见列表摘除；不落库、不改管理页、不改 MCP `tools/list`）与「**按上下文动态描述**」（`toolDescription()` 重写 description，生效粒度 = Turn）；`ToolRegistry.resolve(names, ToolContext)` 的返回即**模型可见工具的唯一判据**，工具目录加载失败时 fail-open（未知名字保留）。**不可关闭清单 `CRITICAL_TOOLS = {pullTasks, submitResult, heartbeat}`** 生效于授权面（`isToolEnabled` 短路 + `getEnabledTools` 并集）。当前唯一真实声明者 = `WebSearchToolCallback`（无搜索凭据 ⇒ 摘 `web_search`） | Context + Session + Skill + Tool + Loop + Event + Sandbox | **P0** | DONE · 八件套全部落地，真实 provider tool-calling 循环无边界问题；工具侧语义位已生效（`LOG-20261009-012`）。动态描述的生产消费者在 `REF-4.1`、`search_knowledge` 随 `REF-4`——均不属本行。边界见 `design/Agent_Runtime.md` §ToolRegistry 语义位 |
| G-004 | Skill Capability | **元数据事实源 = classpath `skills/plugins/*.md` 的 YAML frontmatter + 目录扫描**：`SkillPackage` 9 字段由 frontmatter 承载（8 键白名单，`fileName` 由扫描推导），`KNOWN_SPECS` 编译期硬编码已删除，**4 个 eng-*** 全部结构化；目录条目含 corrupt 显式原因（坏文件不静默跳过，经 `GET /api/skills/catalog` 可见，`skill:view`）；**requiredTools→tools 联动**（Legacy/Runtime 双链 mergeTools 并集去重保序，TOOL_RESOLVED 前合并）+ **SKILL_RESOLVED 携带 resolvedVersions**（Replay/前端按字段投影兼容）+ **拆解技能通路**（task.required_skills → 拆解 Prompt 注入，规划/验收与技能规范对齐）+ **契约层统一技能注入**（AgentTask.skills；execute/executeStream + 拆解/审查收口/报告 5 类 LLM 调用挂点同源，skills 空不注入、行为零变化）；前端技能标签改服务端下发（对齐副本已删除） | Metadata / Version / Tools / Schema / Dependencies 全量 + **requiredTools→tools 联动** + SKILL_RESOLVED 携带版本 + 真实任务行使（任务级创建/拆解/派发/执行四段贯通） | **P1** | PARTIAL · 未达成 3 项：①「新增技能**零发版**」（当前仅达「零改 Java 代码」，待 `REF-1.6` 安装入口）；② 技能回流贡献规范（D5-3）；③ 技能摄入安全闸门（`REF-1.5` / `REF-1.6`）。见 `LOG-20261009-011` |
| G-005 | Sandbox Provider | 已有 Environment / Provider + SandboxProvider 契约（诚实策略，无 ISOLATED）。**当前无可隔离的执行对象**——外部 agent 在它自己终端（平台定位即派单方）；内部 agent（`API_KEY_LLM`）工具面 = 13 个平台 API 工具 + `web_search`，`McpMcpServer` 内 `File` / `Path` / `ProcessBuilder` **0 命中**；平台全库无脚本引擎 / 表达式求值器 | 真正 Provider 化执行环境与隔离策略 | **P1（条件触发）** | DEFERRED · 条件触发，不排期（`D-2026-10-09-6③`）：契约保持现状、不再扩展，不做 Docker 服务。**触发条件（任一成立 ⇒ 重新排期）**：① 平台增加「碰宿主」的工具（自持 shell / 文件写）；② 技能包要被执行；③ 平台自持浏览器。预案见 `plan/HelloAI 借鉴落地实施计划.md` §5（形态已裁定 = 独立沙箱服务） |
| G-006 | Replay / Audit | 写侧 + 对账闭环已具备；Timeline / Replay / Audit 三读侧均经 REST + UI 事件流工作台暴露（含任务 / 子任务维度端点、eventType 过滤、payload 结构化展开、事件字典与子任务时间线同源） | 基于统一 Event 查询/回放；**外部执行轨迹对齐**（外部路径 agent_execution_record 0 行、事件仅完成态，Replay 时外部任务仅「派发→完成」细线） | **P1** | PARTIAL · 未达成：**外部执行轨迹对齐**——外部路径 `agent_execution_record` 为 0 行、事件仅完成态，Replay 时外部任务仅「派发 → 完成」细线。见 `LOG-20260908-009` / `LOG-20260908-010` / `LOG-20260910-001` |
| G-007 | Quality Gate | Reviewer 闭环已具备（执行结果 → Review → PASS / REJECT → REWORK） | Rule + Test + LLM 统一决策 | **P2** | TODO · 目标形态（Rule + Test + LLM 统一决策）未启动；当前为链上增强 |
| G-008 | Agent Fleet Routing | Agent 选择机制已具备（Capability / Health / Load / Policy）；成本维度已接入选人比较链（近 5 次成功执行均值 → 候选内 min-max 反向归一 1~5 档 × `costWeight`，插在 quality 之后、score 之前）；外部执行 tokens 回报协议已就绪（`submitResult.tokenUsage` 可选字段三通道对齐，缺省 null 与旧协议一致）；多外部执行者同台已实证（背靠背竞态唯一赢家 / 技能硬门槛内按分排序 / 并行持有） | Capability + Health + Load + Policy | **P2** | PARTIAL · 未达成：存量 `agent_execution_record` 历史行 token 仍为 null，成本观测待数据积累。见 `LOG-20261003-002` / `LOG-20261003-003` |
| G-009 | Dynamic Workflow | 模板 / 版本 / 实例化 / DAG 已具备 | 动态分支、复杂运行期编排 | **P3** | DEFERRED · 后置；动态分支与复杂运行期编排未启动 |
| G-010 | Planner 能力感知与自适应粒度 | 技能目录注入拆解 Prompt 已具备；子任务级 requiredSkills / constraints 指派已具备（`sub_task.required_skills` + `constraints` 并集装箱）；粒度三档 FINE / STANDARD / COARSE 的 rule-based 决策矩阵（执行者画像 × difficulty 调制）已具备；外部感知下行通道（可选字段，向后兼容）已具备；子任务全文（含验收标准与不确定性申报）经 `claimSubTask` 内联与 `getSubTaskDetail` 一次性下发给外部执行者；inbox 通知摘要携带技能要求与待确认项 | 技能目录注入拆解 Prompt + 子任务级 requiredSkills/constraints 指派（V74 新列，并集装箱）+ 粒度三档 FINE/STANDARD/COARSE 自适应（**rule-based 决策矩阵**：执行者画像 × difficulty 调制，2026-09-09 拍板）+ 外部感知下行通道（可选字段向后兼容）+ 技能回流贡献规范 | **P1** | PARTIAL · 未达成 3 项：① 技能回流贡献规范（D5-3）；② 外部技能目录 + 摄入安全闸门（`REF-1.5/1.6`）；③ 审查侧 constraints / requiredSkills 注入核验。见 `LOG-20261009-013` |
| G-011 | 需求包准入与不确定性显式管理 | 结构化需求包已具备（goal / scope / outOfScope / assumptions / openQuestions + 任务级 acceptanceCriteria），会话列 + `task.context` 双写；拆解继承为 `sub_task.uncertainties`（ASSUMPTION / UNCONFIRMED）显式 JSONB 列；执行侧注入（含 constraints 补偿）与审查侧分级语义（假设不成立 ≠ 执行缺陷）已接；终稿四小节校验与「澄清 → 终稿」软断言已具备；审查驳回携带 `missingEvidence` 结构化返工线索（验收标准原文子串 + 缺什么 + 怎么补） | 结构化需求包（goal / scope / outOfScope / assumptions / openQuestions，会话列 + task.context 双写）+ 拆解继承为 sub_task.uncertainties[ASSUMPTION / UNCONFIRMED] 显式 JSONB 列 + 执行侧注入（含 constraints 补偿）+ 审查侧分级语义（假设不成立 ≠ 执行缺陷）+ 外部下行可选字段向后兼容 | **P1** | PARTIAL · 未达成：`gap_kind` 实现路径分类与任务后蒸馏闭环（后置）。见 `LOG-20261009-013` |
| G-012 | 登录鉴权与 RBAC 权限体系（Sa-Token） | 登录会话由 Sa-Token 承载（token 走 X-Admin-Token 头，active-timeout 8h 滑动续期）；授权为「角色-权限码」RBAC（四表 + 内置 SUPER_ADMIN / ADMIN + 存量用户 role 迁移 + 注解鉴权）；管理侧 API（角色 CRUD / 角色-权限绑定 / 用户-角色分配 / 权限码列表）与前端动态菜单按权限码过滤已具备 | 自建登录模块 → Sa-Token 统一会话 + 用户/角色/权限码管理 API + 接口注解鉴权 + 前端动态菜单；后续可按需做菜单树建表与页面化管理 | **P2** | DONE · 底座与闭环（会话 / 授权 / 管理 API / 前端页面 / 存量会话迁移 / 菜单树 DB 化）全部落地，原登记三项后置缺口已全部关闭；深化项已转 BASE 专项（见 `G-013`） |
| G-013 | 基础架构深化（RBAC 底座，参考 JeecgBoot） | 前端动态路由（权限 = 路由可达性，无权限 URL 404）、`v-auth` 按钮级权限、动作级权限码、菜单树携带 component 驱动动态 `addRoute`、可视化菜单 / 权限管理页、角色授权差异更新、路由渲染增强（隐藏菜单 / 页面缓存 / 外链）、部门与岗位组织架构、数据权限规则（受控枚举 ALL / DEPT / DEPT_AND_CHILD / CUSTOM）均已具备 | 对齐 JeecgBoot 标杆：权限 = 路由可达性 + v-auth 按钮级权限 + 动作级权限码 + 菜单树携带 component 动态 addRoute + 可视化菜单树 CRUD + 角色授权差异更新 + 渲染增强/组织架构/数据权限 | **P1** | PARTIAL · 未达成：`active-timeout`（28800s）的**超时过期触发**未实测（仅由代码路径 + 单测覆盖）；`McpAuthFilter` 仍用 `validateAdminToken` 属**有意设计**（其异常不经 `@RestControllerAdvice`，改 `checkLogin` 会把 401 变 500），非缺口。见 `LOG-20260912-002` ~ `LOG-20260913-008` / `LOG-20260929-001` |
| G-014 | 外部 Agent 执行通道缺陷修复（依赖门禁 / 返工出口 / 附件可发现 / 截断元数据 / 在线语义 / 核验边界） | 外部 Agent 通道已与内部分发链同口径：`claimSubTask` / `listAvailable` 复用依赖门禁（reason `dependency_not_ready`）；返工出口 `startSubTask`（含状态机 `REWORK→BLOCKED`）；`getDepsSummary` 区分「前置未就绪」与「采集异常」；`SubTaskDetail` 内联 attachments 与 contributors；核验侧截断输出结构化标注行且 Prompt 声明「不可见内容不得补全」；在线判定以 ACTIVE 值班租约为存活证据；`renewLease` 到期时刻单调钳制；JSON-RPC `tools/list` 补全 `required` | 外部 Agent 通道与内部分发链同口径：依赖/技能约束一致、返工可自救（不依赖人工放行）、前置产出与附件可发现可读、核验结论只基于可见证据、在线判定不误伤在岗 Agent | **P0** | PARTIAL · 未达成：13 工具矩阵与租约在线语义的**脚本级全量断言**（`verify-tool-matrix.ps1` / `verify-mcp-e2e.ps1` / `verify-agenthub-duty-e2e.ps1`）——当前由真实外部 CLI_CLIENT Agent 端到端 A 级实测覆盖主链。见 `LOG-20260924-001` / `LOG-20260930-002` |
| G-015 | 外部 Agent 心跳/重派止血（B1：误判离线 → 在飞任务被重派打满死信） | 写侧值班租约守卫已具备（CAS 标 OFFLINE 前判 `isOnDuty`，持 ACTIVE 租约即跳过离线处置，与读侧「租约 ACTIVE → IDLE」同口径）；在飞子任务宽限（`inFlightGraceMinutes`，窗口取 `max(grace, offlineMinutes)`）；只读 / 登记类工具经 `refreshDutyLease` 顺带刷 `last_seen_time`；重派退避档绑独立列 `sub_task.last_attempt_time`（`V102`，仅 `incrementAttemptTotal` 原子写入，闸门只读该列）；离线在飞 IN_PROGRESS 置 PAUSED 保留归属（不消耗重派预算）；`redispatchDeadLetter` 清理残留 context 键；REST 双通道（直通 + JSON-RPC）透传 skills（兼容数组与 CSV）；`agent_duty_lease.ttl_minutes`（`V94`）持久化签发窗口；附件上传按「无归属 409 / 归属他人 403 / 子任务不存在 404」语义化报错 | 外部 Agent 埋头执行长任务（数分钟不触网）不被 5min 心跳窗口误判离线；判离线的读/写两视图口径统一 | **P0** | PARTIAL · 未达成：`SKILL.md` 与 `doc/manual/executor-duty/` 的 `attachmentId` 字段名同步。观察点：在飞续约仍用 `maxTtlMinutes(240)` 保活（仅体现在 `expire_time`，不污染 `ttl_minutes`）；「无候选空烧预算 + NO_ELIGIBLE_AGENT 独立告警」未做。见 `LOG-20260926-001` / `LOG-20260926-002` / `LOG-20261003-001` / `LOG-20261005-001` |
| G-016 | 报告整合质量 | 报告读取与子任务链同口径（物化附件优先 → `ExecutionRecord` SUMMARY / DELIVERABLES 注入 → output 兜底）；附件限额与族判定单源（`AttachmentContentPolicy`，shared）；Markdown 块级截断 + 超限结构化标注；大纲先行两段式（出纲失败降级单次调用）；核验 / 返工闭环（自审自过硬守卫 `review_skipped`）；报告版本单槽列 `final_report_prev(_agent_id/_time)`，rollback 端点 current ↔ prev 整体互换；审查异步化（REVIEWING 态 + 专用池 + `reportTime` 三重陈旧守卫 + 六出口收敛 DONE）；审查链三级容错（L1 `AFTER_COMMIT` 内存事件 / L2 Outbox 幂等消费 / L3 孤儿巡检收敛 DONE）；轮次无状态（由审查服务显式传 attempt，不落库） | 报告按主题归并有覆盖追溯，与执行链同口径读事实源；生成后自动核验、驳回可返工；自审自过不静默放行；审查不阻塞前端请求；历史报告可恢复上一版 | **P1** | PARTIAL · 未达成：前端轮询两项可选精修（keep-alive 退出未停表 / 无失败退避）。见 `LOG-20260928-002` / `LOG-20260928-003` / `LOG-20260929-005` / `LOG-20260930-007` / `LOG-20260930-008` |
| G-017 | 附件存储一致性（产物对象 ↔ attachment 记录对账） | `ArtifactStorage` 契约已具备（Local / Minio 双实现 + Composite 按 type / URL 前缀路由；`exists` fail-open / `listObjects` fail-safe / `removeObject` fail-close）；`AttachmentServiceImpl.register` 前置校验（storageUrl 必填 + `validateAddress` + `exists` 校验，不存在 400 拒绝）；对账巡检 `ArtifactStorageReconcileTask`（6h + ShedLock，attachment 全量含逻辑删除 ↔ 桶内对象双向比对，悬空 / 孤儿 / 字节不符三态）；孤儿清理默认关闭 + 三重保险（开关 + 24h 时间窗 + 单轮上限 200）；MCP `uploadArtifact` 描述重写（storageUrl 格式 + bucket 白名单 + 错误示例） | DB 与对象存储两侧一致；悬空/孤儿可发现可报告；不再产生僵尸附件；孤儿清理受控有保险 | **P1** | PARTIAL · 未达成：① 历史 106 条悬空附件不自动修复（对账可见可报告）；② 部署侧安全动作待执行（改 MinIO 默认凭据 + 端口收安全组白名单）。见 `LOG-20260928-001` |
| G-018 | 备份 / 恢复（平台数据可靠性） | **完全空白**：全仓无 DB 备份/恢复能力（`helloai-core` / `helloai-api` 内 grep `backup` / `restore` 零功能命中；唯一真实 `pg_dump -Fc` 用法是 `deploy/middleware/scripts/migrate.sh` 的一次性跨机迁移脚本）。**可复用资产已就绪**：MinIO 为生产主存储（`storage.type=minio`，bucket `helloai-artifacts`）+ `ArtifactStorage.listObjects(bucket, prefix)` 递归枚举（Minio 实现）；Redisson 4.0.0 `RLock` 与 ShedLock 6.6.0 `@SchedulerLock` 既有单飞范式 | `pg_dump -Fc` 全库 + MinIO 对象清单 + manifest 前置 peek（不解档即可判内容）+ 分布式单飞锁 + 自动/手动备份分离的保留策略 + 停机恢复流程文档化（诚实边界：运行中备份不保证多文件同一瞬间） | **P1** | TODO · 未启动（`REF-2.3` / `REF-2.4`）。落地硬约束：① 必补**恢复侧安全闸门**（跨引擎拒恢复 / schema 版本高过运行时拒恢复 / 在线恢复拒绝）；② 「`listObjects` 返回空」既可能是真空也可能是实现不支持（fail-safe 默认返回 `List.of()`）——**必须显式失败，不得静默产出缺对象的「完整备份」**；③ 复用既有 `pg_restore --no-owner --no-privileges` 写法 |
| G-019 | RAG 知识库（检索增强） | **完全空白**：pgvector / embedding / 知识库 / 向量 全仓零命中 | pgvector 存储与检索（**PG 单后端，不引入侧库**）+「无 KB 即摘工具」的条件可用语义位 + 注入预算（char_budget 契约）+ 引用溯源 marker（前端渲染卡片、喂模型前剥离） | **P2** | TODO · 未启动（`REF-4.x`）。**前置三件**：① PG 镜像换 `pgvector` 版（现 `postgres:16.4-alpine`，无扩展，属基础设施变更）；② 嵌入模型供应商 / 维度 / 密钥管理选型落 `design/adr/`；③ `search_knowledge` 必须经 `ToolCallbackContributor` 端口注册并登记技能包（`CODE_STYLE §35.1` 平台能力接线规则），不得给 LLM 直插域内 Service。**前置④「无 KB 即摘工具」的语义位已具备**——`search_knowledge` 只需在 `ToolCallbackContributor.toolAvailability()` 声明「KB 不存在 ⇒ false」即从模型可见列表摘除，无需改动 `ToolRegistry` / `RuntimeTurnExecutor` |
| G-020 | 外部 Agent 工作详情快照（子任务执行过程的取证能力） | **完全空白**：平台当前只能审计到**订单层面**（认领 / 开工 / 心跳 / 交单 / 产物 / 阻塞 / 依赖读取，经 `agent_event` + `task_timeline` + 产物对账）；**子任务内部执行过程是黑盒**（这是刻意的解耦，但带来返工争议 / 质量复盘 / 责任界定的取证盲区） | 外部 Agent **事后、经审批、可选**地提交一份全任务工作详情快照 → 平台解析 → **原文落 MinIO** + **时间线插入结构化审计事件（闭合 schema + `snapshotRef`）** | **P2（可后置）** | TODO · 新增立项（`D-2026-10-09-6⑤`），任务锚点 `REF-7`。**动手前三项设计约束必须先定**（见 `plan/HelloAI 借鉴落地实施计划.md` §8.2）：① 与 `REF-6.2`「审计闭合 schema」的张力 ⇒ 必须双面（与 `REF-5.2b` 双视图同模式）；② 审批方是谁（倾向复用人工介入通道，不建第二套审批）；③ 落时间线的粒度。新增工具须走既有 `McpMcpServer` 守卫且**默认关闭**（`REF-6.13`） |

# 2. P0 主线

## G-001 Event Stream

验收：

- Legacy 与 Runtime 产生同一 Event Model；
- Timeline / Audit 逐步统一从 Event 获取事实；
- 一个 Run 可以按 sequence 重建轨迹；
- Event 不成为第二业务状态源；
- 写入具备幂等和可对账能力。

## G-002 Dual Executor

> **已终结**：双轨不复存在（旧链入口与 Adapter 全部删除，无灰度开关），`RuntimeTurnExecutor` 为唯一 `AgentRuntime` 实现。

单轨后的结构约束（长期有效）见 `design/Agent_Runtime.md` §迁移模型。

**落地事实与验证证据**：`LOG-20260930-001`（硬切）/ `LOG-20260930-002`（外部 CLI_CLIENT E2E）。【过程记录原在 `plan/HelloAI 重构实施计划.md`，已于同批回填 `LOG-20260908-006`】

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

> **Fork 处置（`D-2026-10-09-5`）**：**WONTFIX**——「触发入口 / 原 Run 冻结 / 驱动新 Run 执行」三项**不立项**。`AgentEventForkService` 作为**未接线的内部能力保留**（快照复制能力，**零生产调用方**）。依据：①「驱动执行」自始即为后置项；② 其实际用法（分叉重跑 / 路径对比）已被 **Return**（REWORK → 驳回 → 改派 → 重开工，闭环完整）与 **Replay 工作台**覆盖；③ 驱动执行须改 Run 标识模型（ADR-001）与 execution command 载荷（协作规约 §30/§31），成本与收益不匹配。**Recovery 保持在本次序内推进，优先级不因本节变化**。

### Planner 能力感知（G-010）

- 设计（唯一事实源）：`doc/design/Planner_Capability_Awareness.md` §7（决策 1~7 全部拍板）
- 当前状态与未达成项：见 §1 矩阵 `G-010` 行
- 实现过程与验证证据：`LOG-20260909-001` ~ `LOG-20260909-012`、`LOG-20260910-001`、`LOG-20261009-011`、`LOG-20261009-013`

### 需求包准入与不确定性显式管理（G-011）

- 设计（唯一事实源）：`doc/design/Requirement_Package_Uncertainty.md` §7（决策 1~17，含 P1/P2 修订与决策反转登记）
- 当前状态与未达成项：见 §1 矩阵 `G-011` 行
- 实现过程与验证证据：`LOG-20260909-006` ~ `LOG-20260909-012`、`LOG-20260910-001`、`LOG-20261009-009`、`LOG-20261009-013`

### 登录鉴权与 RBAC 权限体系（G-012）

- 会话：Sa-Token 承载（token 走 `X-Admin-Token` 头，active-timeout 8h 滑动续期）；授权：角色-权限码 RBAC（四表 + 内置 SUPER_ADMIN / ADMIN + 注解鉴权）；`active-timeout` 过期触发的实测归 `G-013`（同底座）
- 当前状态与未达成项：见 §1 矩阵 `G-012` 行
- 实现过程与验证证据：`LOG-20260912-001`、`LOG-20260913-007` / `LOG-20260913-008`
- 深化项已转**基础架构专项**（见 `G-013`）

### 基础架构深化（G-013，参考 JeecgBoot，实施编排独立）

- 专项计划（唯一事实源）：`doc/plan/HelloAI 基础架构调整实施计划.md`（批次明细 / 验收 / 遗留）
- 编号：`BASE-x.y`——与 `G-xxx` 及重构主线编号完全错开，不混用
- 目标架构融合：`doc/HelloAI 目标架构.md` §12 基础架构（平台底座），与业务五层正交
- 当前状态与未达成项：见 §1 矩阵 `G-013` 行
- 实现过程与验证证据：`LOG-20260912-002` ~ `LOG-20260913-008`、`LOG-20260929-001`
- **设计边界（非缺口）**：`McpAuthFilter` 沿用 `validateAdminToken`——其异常不流经 `@RestControllerAdvice`，改 `checkLogin` 会把 401 变成 500
- 红线：不新增第二套权限体系；不触碰 Event / 状态机 / Scheduler / Workflow / Review；外部 Agent 契约不变；已提交 DDL 只读（新增迁移编号递增）
- 合规基线：专项文档 §4 强制遵循《协作规约》（生命周期 / 复用既有 PS1 / 完成报告 16 节）与《CODE_STYLE》（RBAC 归 system 域 / DTO 投影 / `@Transactional` / §43 认证授权分离 / `v-auth` 统一 `hasPermission`）

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

### 7.1.3 技能包安装入口（2026-10-10，依据 `plan/HelloAI 借鉴落地实施计划.md` §REF-1.6 节 + `D-2026-10-10-1`）

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| R4 | **外部技能目录**（`REF-1.2a` 的「可选外部目录」配置项） | **WONTFIX**（2026-10-10）——「新增技能零发版」的目标改由 `REF-1.6` 安装入口满足；保留目录会与受控存储形成两个事实源，且 Docker 部署需挂卷 | G-004 |
| R5 | 技术债：`MyBatisPlusMetaObjectHandler.getCurrentUser()` 恒返 `"system"`，全平台 `create_by` 不记操作人 | 登记；**不在 REF-1.6 内修**（平台级语义变更，牵动所有表），另行立项 | — |
| R6 | 沙箱 ADR（「平台与宿主文件系统的边界」这类系统性议题） | 登记升级触发；`REF-1.6` 走受控存储**不触发** `G-005` 条件①，本批**不建新 ADR** | G-005 |

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

旧编号明细与登记背景见 `archive/legacy/V1_HelloAI 实现差距表.md` 与 `doc/archive/log/2026-09.md`（LOG-20260907-001）。
