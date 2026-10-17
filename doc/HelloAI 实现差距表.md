# HelloAI 实现差距表

> **状态：CURRENT GAP**
>
> 本文只记录**当前 → 目标**的真实差距；**只保留最新状态、随迭代就地更新**，不累加过程记录。
>
> **最后更新：2026-10-17**
>
> 过程与订正历史按「做过什么」归 `doc/log/`：2026-09 批次见 `doc/archive/log/2026-09.md`，2026-10 批次见 `doc/log/2026-10.md`。
>
> 当前进度**以 §1 总体矩阵为准**；本表是「当前差距」的唯一事实源。

# 0. 当前生效的口径与决策

> 只登记**仍然生效**的口径与决策；过程与订正历史见 `doc/archive/log/2026-09.md` / `doc/log/2026-10.md`。

**编号口径（全项目唯一，防撞号）**

| 编号 | 含义 |
|---|---|
| `G-001`~`G-023` | 本表差距项（见 §1 总体矩阵）；`G-021` / `G-022` / `G-023` 分别于 2026-10-12 / 2026-10-13 / 2026-10-16 登记 |
| `P0`~`P3` | **架构重构主线阶段**（唯一含义）；审计优先级档已改用 `PR0`~`PR3` |
| `RM1`~`RM13` | 代码质量路线图（与目标架构能力层不再撞号） |
| `B1`~`B5` | 目标架构能力层（阻断项）；现仅 `B2 Sandbox` 未动 |
| `BASE-x.y` | 基础架构专项任务（`doc/archive/implemented/HelloAI 基础架构调整实施计划.md`，2026-10-12 已完成归档；编号已退役，不再新增） |
| `REF-x.y` | 借鉴落地专项任务（`plan/HelloAI 借鉴落地实施计划.md`，2026-10-09 建号）；`x` 取 1~8 —— **`REF-8` 内部 LLM 能力面**（2026-10-12 新增，`D-2026-10-12-1`） |
| `M-x` | **供应商 / 模型注册信息完善**（magpie 借鉴，2026-10-13 建号，`x` 取 1~9）：来源 `research/magpie_借鉴分析.md`，实施见 `plan/HelloAI 借鉴落地实施计划.md` **§11**。与 `REF-x.y` **并列**——`REF-` 号段 `x` 已占满 1~8，不扩号段 |
| `N-x` | **明确不抄项**——**仅存于 `research/magpie_借鉴分析.md` §八**（`x` 取 1~5），不承载任务、不进排期。⚠️ 与 `doc/archive/implemented/HelloAI_全面复测清单（2026-10-06）.md` 的复测用例号 `N-1` **同名不同义**（archive 为历史现场，不改） |
| `ARCH-YYYYMMDD-NNN` | 架构决策（`log/HelloAI 架构变更记录.md`） |
| `LOG-YYYYMMDD-NNN` | 迭代记录（`log/YYYY-MM.md`） |
| `T-n` | **专项工作项局部编号**（2026-10-14 建号，`M-9` 起）：单个专项内部的任务拆分（如 `T1`~`T10`），**仅在该专项的文档/报告内寻址**；不承载排期、不进 §1 矩阵、不用于跨专项引用。实测 `doc/archive/**` 无 `T-` 号段 ⇒ 不撞号 |
| `QA-<专项>-nn` | **质量验证缺陷局部编号**（2026-10-14 建号）：随该专项的验证报告落 `doc/review/`，两个 `99` 门限内 `nn` 递增（如 `QA-M9-01`）；**不承载排期**，仅用于报告内寻址与整改闭环追溯 |

> `C-1`~`C-42`（原「差距表订正留痕」编号）**已于 2026-10-09 退役**——差距表订正与产出它的迭代记录同源，不再单独编号；一律改用 `LOG-YYYYMMDD-NNN` 寻址。`doc/archive/**` 内的 `C-NN` 为历史现场。

**状态词表（协作规约 §6.3）**

> `TODO / DESIGNING / IMPLEMENTING / VERIFYING / DONE / PARTIAL / BLOCKED / DEFERRED / WONTFIX`
>
> **本表落位 = §1 矩阵「处置」列首词**。四条配套口径：① 一行只允许一个状态词；子项状态不同时取最需关注态（偏序 `BLOCKED > DEFERRED > PARTIAL > TODO > DESIGNING > IMPLEMENTING > VERIFYING > DONE`），明细写在状态词之后；② `WONTFIX` 只用于整行都不做，单子项不做写 `DONE · 附：X 子项 WONTFIX`；③ 第 3 列「当前状态」**不得**使用状态词（改用事实语），两列都表状态是新漂移源；④ **不设进度百分比列**，需要量化时用子项计数（`未达成 3 项`）。
>
> 机械守卫：`scripts/shell/verify-doc-gap-table.sh`（bash 实现，**已在 `ci-gate.sh` 门禁 3 执行**）。

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
- **D-2026-10-11-1｜RAG 嵌入模型选型与密钥管理四项裁定**（用户，2026-10-11）：① **嵌入模型 = 阿里云百炼 DashScope `text-embedding-v4`，维度 1024** —— 在「平台已具备凭据的四家供应商」里唯一提供 embeddings（DeepSeek 官方无端点；Moonshot / MiniMax 多源不支持；OpenAI 无凭据且涉境外出境）；且其 **OpenAI 兼容** `/compatible-mode/v1/embeddings` 与仓库既有「`spring-ai-openai` 打通 dashscope chat」同源，不新增依赖形态。**前置检查** = 用现有 key 调一次 embeddings 探针（期望 1024 维），失败显式报不可用（不静默降级）。② **维度是单向门**（`pgvector` 列宽与索引绑定维度；换模型或换维度须全量重算）⇒ 改成配置但**上线即冻结**，变更走「新建列/表 → 回填 → 切换」而非原地 ALTER。③ **密钥与内部 LLM Agent 共用** `credential_vault` 既有 dashscope 条目、**不新增独立 key** —— 用户理由：同账户同资产，key 分离不产生财务隔离；平台侧零改动（vault 多条目 + 平台级解析；成本归因改为平台侧记 token 用量）⇒ **非单向门**，将来挤配额时拆 key 成本≈0。④ **数据边界：接受知识库正文发送至阿里云做向量化**（境内第三方；不引入境外供应商）；若出现「不可外发」知识域须支持按域选 provider（未设计，登记触发条件）。**向量存储沿用 `pgvector`，不引入 ES**（单后端 / 已在 REF-2 备份覆盖内 / 同库事务一致）。详案见 `design/adr/ADR-002-embedding-provider.md`。
- **D-2026-10-11-2｜事件码改服务端单一来源（取代 `D-2026-10-09-6③-4` 的「事件码 guard-only」）**（用户，2026-10-11）：事件码展示字典改为**服务端 enum + 目录端点下发**（`TimelineEventCode` 141 码 → `GET /api/events/catalog`），前端删 EVENT_META / sequenceFlow.LABEL 硬编码副本、改拉目录（与技能目录 REF-1.2c 同范式），守卫转「enum 单源 + 前端无残留」。早前判定「事件码不适合下发」指的是其**行为配置**（compaction/箭头推断），非 code-label 字典；字典越单源越稳。**收口**：后端展示通道写点替换为枚举引用已同批完成（守卫相应重构，见 `LOG-20261011-008`）。实现与验证见 `LOG-20261011-006` / `LOG-20261011-008`。
- **D-2026-10-10-2｜REF-2 备份 / 恢复实施方案六项裁定**（用户，2026-10-10）：① **执行形态** —— 应用内 `ProcessBuilder` 执行**可配置路径**的 `pg_dump` / `pg_restore` + 启动前置检查（不可用则备份功能**显式不可用**，不静默）；生产 Dockerfile 的 app 阶段补装 `postgresql-client-16`；**不做 `docker exec`**（挂 `docker.sock` 与 `D-2026-10-09-4③` 的裁定相悖）。实测三环境（app 镜像无客户端 / 本地 Windows 无 / 仅 PG 容器内有）为第一前置。② **`ProcessBuilder` 判为不触发 `G-005` 条件①** —— 该条件语境是「Agent 可调用工具面」，备份是**管理端例程、非 agent 工具** ⇒ **REF-3 沙箱排期不动**。③ **备份落独立 bucket `helloai-backups`** —— `ArtifactStorageReconcileServiceImpl` 以 `listObjects(bucket, null)` **枚举整桶**，凡无 DB 记录者即孤儿候选，共用桶会被 `orphan-cleanup` 删掉；且 `ArtifactStorage.store` 只写单一配置桶 ⇒ 需新增独立写路径。④ 触发 = 定时（`helloai-job` ShedLock，与既有 18 个任务同构）+ 管理端手动端点；长操作走**异步 + 状态守卫 + 轮询查询端点**（学拆解范式）。⑤ 保留 = 仅对**自动**备份按份数淘汰（默认 **7**，可配），**手动备份永不淘汰**；GFS 留作后续精化。⑥ 新增 `platform_backup` 记录表（迁移 **V105**，形态对齐 `V67__create_credential_audit_log.sql`）+ 管理端权限码。恢复侧三门（跨引擎拒 / schema 高过运行时拒 / 在线恢复拒）先于任何 `pg_restore` 执行。详案见 `plan/HelloAI 借鉴落地实施计划.md` §4。

- **D-2026-10-12-1｜内部 LLM 能力面立项（`REF-8` / `G-021`）（用户，2026-10-12）**：用户确认「内部 agent 内核思路」按审阅建议调整 ⇒ ① **新立 `REF-8`「内部 LLM 能力面」**（与既有 `REF-1~7` 并列），收纳 `D1` 工具面解封 / `D2` 手脚·工作区（含 `systemPrompt` 非空）/ `D3` 技能全量目录 + 惰性加载 / `D4` 拦截链（Spring AI Advisors）/ `E1` 循环覆盖面收敛；② 同批新立差距锚点 **`G-021`**（此前「内部 LLM 工具面 / 循环覆盖面」在差距表**零登记**，属孤儿项）；③ **内核轴 = 「可见面收敛」**——唯一输出契约 = 模型的可见集（L1 可见工具集 / L2 可见技能目录 / L3 可见上下文）；会话单源化降为**支撑项**（**提升已有 `agent_event`，不新建 Ledger**）；④ `LOG-20261011-007` 的 **A 能力闸 + B 收敛熔断** 挂 `REF-8` 的 `D4` 拦截链之下（~~拦截链是其唯一挂点~~ —— **★2026-10-15 订正**：**B 挂拦截链、A 出列落 `ResilientDispatcher`**，见 `D-2026-10-15-6`）。详案见 `plan/HelloAI 借鉴落地实施计划.md` §10 与 `review/HelloAI 内部 agent 内核收敛思路审阅与调整建议（2026-10-12）.md`。
- **D-2026-10-12-2｜`REF-8` 开工 + 主线指针 + 覆盖面清单**（用户，2026-10-12）：① `REF-8` 动作 **`REF-8.1 → 8.2 → 8.3` 即行开工**（当前 `T1` 最高优先，唯一直接产出「内部 LLM 变 agent」的一层）；② 权威主线《重构实施计划》§2 头部**补 `REF-8` 指针**（实测原文全 0 命中 `REF-8`，与编排总览不一致）；③ `REF-8.5` 覆盖面**先出 16 项清单待勾选**（`plan/HelloAI 借鉴落地实施计划.md` §10.6）后再立项。
- **D-2026-10-12-3｜`LOG-20261011-007` A+B 立项**（用户，2026-10-12）：**A（能力闸）+ B（收敛熔断）正式立项**——B 的「连续**相同驳回原因**」阈值 **`N = 3`**（对齐日志"连续 3 次"现场）；动手时机 = **`REF-8.4` 拦截链落地之后**（~~拦截链是唯一挂点~~ —— **★2026-10-15 订正**：**B 挂拦截链、A 出列落 `ResilientDispatcher`**，见 `D-2026-10-15-6`；且 `REF-8.4` 已于 2026-10-13 交付 ⇒ **时机已到**）；**`C`（产物接地校验）暂不立项**，单独评估。
- **D-2026-10-12-4｜`REF-7` 三约束定稿 + 继续挂起**（用户，2026-10-12）：§8.2 的 C1~C3 **全部按原倾向定稿**（C1 **双面**：原文落 MinIO + 时间线只放结构化索引 + `ref`；C2 **复用人工介入 `HUMAN_REVIEW` 通道**、不建第二套审批；C3 **一条汇总事件 + `snapshotRef`**）；但 **`REF-7` 本身继续挂起**（触发 = 子任务内部执行深度审计需求成立，当前未触发）。同批确认 **`REF-3`/`B2` 沙箱继续挂起**、**`REF-1.4` 继续挂起**、**上线核对清单 §2 待环境确认**。
- **D-2026-10-12-5｜`REF-8.1` 对外面隔离 + 执行链绑定闸门（安全口径收口）**（用户，2026-10-12）：就「§10.5 红线④『新增工具须走 `McpMcpServer` 守卫且默认关闭』vs `REF-8.1` 落地」的口径差裁定 ⇒ **红线④不是过时，而是射程需澄清 + 代码漏了一道对外边界**（用户口径：「个人建议要遵循红线；但确属架构/业务逻辑更优更准确者，可酌情记录差距表」——本次判定**不属可豁免项，改代码**）。① **红线④射程 = 「对外暴露的新工具」**：`REF-8.1` 的**内部进程内通道**为该红线**显式例外**——以「**执行链绑定闸门**」（`ToolInvocationContext`）替代会话守卫，且**不得**进入对外 MCP `tools/list`。判据：「**绕过外部守卫**」合理 ≠ 「**暴露到外部面**」合理。② **代码修正 A（闸门）**：7 个 `internal_*` 工具入口加 `requireInternalChain()` 强制闸门——未绑定执行链即拒绝（`BizException`），并**改用绑定值**（不信任调用方入参 `agentId` / `subTaskId`）⇒ 消除**已认证越权**（IDOR：任一持牌 agent 经 `tools/call` 调 `internal_*` ⇒ 跨 agent 读子任务 / 读任意附件 / 往他人子任务登记产物）。③ **结构修正 B（隔离）**：`internal_*` 移出**对外 provider**——因 spring-ai 自动配置聚合**全部** `ToolCallbackProvider` Bean，改由**非 provider 载体** `InternalToolCallbacks` 承载，三消费者（`ToolCallbackToolExecutor` / `RuntimeTurnExecutor` / `ToolRegistryImpl`）经 `InternalToolCallbacks.union(external, internal)` 合并 ⇒ 对外 `tools/list` 不再出现 `internal_*`，单 provider 纪律保留。④ **文档修正 C**：`plan/HelloAI 借鉴落地实施计划.md` §10.5 红线④加射程澄清 + §10.2 `REF-8.1` 落点「**不并入**对外 provider」/ 验收补 A+B。**验证**：受影响套件 **71 用例全绿**、`helloai-core` 全量 **BUILD SUCCESS**、`check-arch-freeze.sh` EXIT=0。见 `LOG-20261012-006`。
- **D-2026-10-13-6｜`REF-8.4` 自动触发接线**（用户，2026-10-13，记录 `LOG-20261013-011`）：**(b) 巡更为主**（复用 `AgentHealthCheckTask` 形状）**(a) 同事务仅作可选加速**——因同事务会重踩退避时钟坑（`SubTaskDispatchServiceImpl:60-66` 历史实例）。
- **D-2026-10-13-7｜`REF-8.4` 可移植性**（用户，2026-10-13，记录 `LOG-20261013-011`）：**另立独立待办，不混入 `REF-8.4`**——可移植化须动 `AgentLoopInput` 入参 + 事件出口 + `ToolInvocationContext` 线程态，与 §10.5「不动循环本体」有张力。
- **D-2026-10-13-8｜A/B 词表是否拆分**（用户，2026-10-13，记录 `LOG-20261013-011`）：**不拆分**（零 planner 改动），改在闸门内做**两段判定**——先 A 谓词粗匹配、再 B 引用边 `B.requires ⊆ A.provides` 复核。
- **D-2026-10-13-9｜形态甲/乙（V52 加列退列 vs 泛化能力谓词表）**（用户，2026-10-13，记录 `LOG-20261013-011`）：**形态乙**（泛化能力谓词表吸收 `capability_skills=["thinking"]` 这类平台策略）；遵删列前置——先迁 `validateAgentSkills` 与 `getSkillOptionsByModelType` 两消费方、再删 V52 两列。
- **D-2026-10-13-10｜`requiredBaseCapabilities` 挂点**（用户，2026-10-13，记录 `LOG-20261013-011`）：**`SkillPackage` 8→9 键四路协同**（技能须自报模态前置），不做模块级默认替代。
- **D-2026-10-13-11｜`M-x` 号段建号 + minimax 换代口径（含「发布前修改已 apply 的 Migration」例外留痕）**（用户，2026-10-13，记录 `LOG-20261013-015` / `-017`）：① **`M-x` 建号**（供应商 / 模型注册信息完善，`x` 取 1~9）：来源 `research/magpie_借鉴分析.md`，实施 `plan/HelloAI 借鉴落地实施计划.md` §11；与 `REF-x.y` **并列**——`REF-` 号段的 `x` 已占满 1~8，不扩号段（详 §0 编号口径表）。② **minimax 全系列换代**：`MiniMax-M2.5` / `M2.7` 即将下线 ⇒ 目录默认位切 **`MiniMax-M3`**，并追加 **`MiniMax-M3.1-Flash-Preview`**（`enabled=1` / 非默认）。③ **迁移承载 = 并入 `V108`，不新立 `V109`**（用户裁定）：生产尚未 apply `V108` ⇒ 单迁移即完整、不遗漏；**该操作命中《协作规约》§29 的显式例外**「**除非任务明确就是修复尚未发布的本地 Migration**」（`HelloAI_CODE_STYLE.md` §16.1 / §52 同源，`§28 Git Diff 自检` 要求说明原因）。开发库已 apply 过 ⇒ 靠 `DELETE FROM flyway_schema_history WHERE version = '108';` 重跑复位（迁移本身幂等，已事务空跑两遍验证）。⚠️ **此例外仅限「尚未发布」**，不得推广到已发布迁移；④ **四方载体同步**：前端预置 / `application.yml` / Java 兜底常量（`FALLBACK_MODELS`）/ 守卫 `SEED_DEFAULT`。
- **D-2026-10-14-1｜`M-3` 模型目录种子的播种口径与承载（含「新增 provider 不建 yml 段」）**（用户，2026-10-14，记录 `LOG-20261014-001` / `-002`）：① **承载 = 新迁移 `V109`**（不并入已 apply 的 `V*.sql`）；`M-1` 首波 5 家此前**连 `llm_provider` 行都没有** ⇒ **同批补 `builtin=1` provider 行**，否则模型种子 `INSERT…SELECT FROM llm_provider` 会**静默插 0 行**（不报错）。② **播种口径**：models.dev 快照「`text` 输出 + `context>0` + `status≠deprecated`」全量，**另加 `tool_call=true`**（剔除 `qwen3-asr-flash`/`qwen-vl-ocr`/`qwen-mt-*`/`-realtime` 等非对话模型；实测 164→**149**）。③ **默认位例外**：`deepseek-v4-flash` 在 models.dev 已标 `deprecated`，本批**不改默认位**（改默认位属状态变更，须另立决策并同步载体），仅纳入清单并标注待办。④ **新增 provider 不建 `application.yml` 段**（yml 为**遗留载体**，`V46` 起 provider 已 DB 驱动）⇒ 守卫的 yml 比对改为「**有段才比**」，并补反向「yml 段必须 ∈ `SEEDED`」与「yml 载体不得整体缺失」两道兜底（防条件化后静默失保护）。⑤ **`NO_SEED` 白名单清空**（9 家全有种子）。
- **D-2026-10-14-2｜`M-4` 能力元数据回填的承载形态与口径（形态乙落地 + `deepseek-v4-pro` 模态冲突留痕）**（用户，2026-10-14，记录 `LOG-20261014-003`）：① **承载 = 新迁移 `V110`**，落点是**新增能力谓词列 `capabilities`(JSONB)**，形如 `{"input":[模态],"output":[模态],"reasoning":bool,"toolCalling":bool,"webSearch":bool}`；模态词表 `{text,image,audio,video}`（缺省 `[text]`）。这是 `D-2026-10-13-9`（**形态乙**：泛化能力谓词表）在数据层的落地 —— **不**继续回填 V52 的 `capability_skills`/`availableOptionalSkills`（那两列按删列前置「**先切两个消费方再删列**」处理，切换归 `M-9`）。② **推导口径四方**：`input`/`output` ← models.dev `modalities`；`reasoning` ← models.dev `reasoning`；`toolCalling` ← models.dev `tool_call`；**`webSearch` 无 models.dev 字段** ⇒ 取 V52 已确立的 **provider 口径**（`moonshot`/`dashscope`/`minimax` = true，`deepseek` 等 = false）—— 联网检索是**工具可用性**而非模态，与 `requiredTools` 同源，**不臆造**。③ **全表兜底 + 逐行回填两步**：先对**全表**写保守面（仅 `text` + provider 级 `webSearch`），再对 `V109` 种子集合（150 行）写全量谓词 ⇒ 使 **V49/V108 遗留行、大小写变体行（`qwen3.8-Max` vs `qwen3.8-max`）、e2e 探针残留行**也拿到**一致**谓词，而非列默认的 `webSearch=false`（否则会把 moonshot/dashscope/minimax 的遗留行误判为「无联网」）。④ **`deepseek-v4-pro` 模态口径冲突（留痕、不在本迁移调停）**：models.dev = `["text"]`（仅文本，且该库**内部自洽** —— DeepSeek *flash* 线多模态，仅 `-pro` 为 text-only），用户 2026-10-13 口径 = `[text,image]`；本批**取快照值**（保守面 = fail-closed，宁少派图像技能），并**标注待办**（同 `deepseek-v4-flash` deprecated 的「保现状 + 标注」范式）。⑤ **复发风险已登记**：新列与 V52 同为「回填只对当时已有行生效」⇒ 后加行走列默认会**静默丢谓词**；本批以「兜底 UPDATE + `DO $$` 断言」缓解，**持续性守卫**（文件级、不依赖 DB）随 `M-9` 消费方一并落。⑥ **本批不动 Java 实体**：`LlmProviderModel` 未加 `capabilities` 字段（无读取方即等于「只写不读」反模式），**消费方与实体字段同批由 `M-9` 落**。
- **D-2026-10-14-3｜`M-5`「`extraConfig` 只写不读」闭环**（用户，2026-10-14，记录 `LOG-20261014-004`）：① **读取方落地**：新增 `ExtraConfigPaths`（`completionsPath`/`messagesPath` 键名 + 「空白 ⇒ 未配置」归一化的**唯一读取器**，防两工厂各自漂移）；`LlmProviderProfile` **追加末位字段** `extraConfig`（端口扩字段，7 参，6 处构造点同批更新）；两工厂读值并调 `Api.Builder.completionsPath(...)`。② **缺省语义 = 行为零变化**：未配置 ⇒ **不调用** builder 方法 ⇒ Spring AI 用各自默认路径（`/v1/chat/completions` / `/v1/messages`），与 `M-5` 前**逐字一致**。③ **缓存 key 必须含端点路径**：与 `baseUrl` 同属「决定实际请求 URL 的配置」；已加六元组重载，`endpointPath` 为空**不追加段**（旧 key 逐字不变）。否则改覆盖项会命中旧 `ChatModel` ⇒ 请求打到旧路径且**不自愈**（与 `ProviderChatModelCache` 既有「key 含 model 维度」的 2026-08-22 修复同源）。④ **验收 = 真实请求路径 + 反证**：8 个用例用本地 `HttpServer` 桩断言「实际收到的 path」；**停用消费逻辑后断言确实失败**（`expected "/custom/chat" but was "/v1/chat/completions"`）⇒ 非自证。⑤ **表结构不变**（复用 `V46` 的 `extra_config`，**无新迁移**）。⑥ **写入方早已存在**（`AdminLlmProviderController:105,125` + `LlmProviderServiceImpl:104` + 前端 `api/settings.ts` 三处类型）⇒ 本次只补读取方即成闭环。**未做（有意）**：前端表单控件；`M-1` 的 defer 预置未随批加入。
- **D-2026-10-14-4｜`M-6` Key 指纹标识的承载与去重口径（含「指纹不可 SQL 回填 ⇒ 应用层惰性回填」「回填必须落在 system 域」）**（用户，2026-10-14，记录 `LOG-20261014-005`）：① **承载 = 新迁移 `V111`**：`credential_vault` 加 `fingerprint VARCHAR(64)` + 部分索引 `idx_credential_vault_fingerprint (provider, fingerprint) WHERE deleted = 0`。② **口径 = SHA-256(明文 UTF-8) 前 12 字节的小写十六进制串（24 字符）**，~~对齐 magpie `internal/access/access.go:420-421`（`hex.EncodeToString(sum[:12])`）~~（**★该「对齐」归属已于 `D-2026-10-14-5` 订正为「有意加强」**）；唯一实现 `core/system/crypto/CredentialFingerprint#of`。与同包 `AgentApiKeyCipher#sha256Hex`（**全量** SHA-256，用于认证点查 SQL 精确匹配）**刻意不合并** —— 一个是「去重/区分标识」、一个是「点查哈希」，输出口径与用途均不同。③ **列可空且不设 `DEFAULT`**：两类合法 NULL = `secret_ref` 型凭证（无明文可摘要）、迁移前历史行（待回填）。不设默认值的理由同 `V110` 已登记的判据「列默认值是隐性事实源」——`ADD COLUMN … DEFAULT` 会让「未回填」伪装成「已回填」。④ **指纹不能在 SQL 侧回填**：`encrypted_value` 为 AES-GCM 密文（每次随机 nonce ⇒ 同明文两次加密密文不同），SQL 无法还原明文 ⇒ 由**应用层惰性回填** `CredentialVaultService#backfillFingerprints`（每次保存密钥时扫描该 owner+provider 的「指纹空 + 密文非空」行，解密补写；**幂等只补不覆盖**；单行解密失败只记 WARN 跳过，**不阻断**管理员保存）。⑤ **回填落在 `system` 域内而非 `agent` 域**：agent 域不得 import `system.entity`（RM5 批 3 只读快照先例），故由 `CredentialVaultServiceImpl` 注入 `CredentialCryptoService` 自行解密，对外只暴露 `backfillFingerprints` 一个方法。⑥ **判重作用域 = owner + provider 内同指纹**：同一把 Key 被不同 provider / 不同 owner 合法复用时**不得**互相判重；命中 ⇒ **不落库、不清 ChatModel 缓存**。⑦ **`saveApiKey` 返回值改契约**：`void` → `ApiKeySaveOutcome{created, fingerprint, message}`（core `agent/dto`）；API 层映射为 `LlmProviderApiKeyResponse`；`ApiKeyResponse`（`resetKey`）同步补 `fingerprint`，前端 `settings.ts` 加 `ApiKeySaveResult` 类型并在 `Settings.vue` / `AddModelFormDialog.vue` 提示「已存在」。⑧ **验收含反证**：临时停用判重分支 ⇒ `PlatformProviderConfigServiceTest` **4 条红**（`shouldNotPersistWhenFingerprintExists` 等）⇒ 断言非自证。⑨ **指纹工具落 `core.system.crypto`（非 `helloai-common`）**：`helloai-common` **无测试基础设施**（`src/test` 为空、pom 无测试依赖），而纯逻辑无单测等于不可信 ⇒ 与同域 `AgentApiKeyCipher` 同包并共享 `helloai-core` 测试树；`helloai-api` 引 `core.system.crypto` 已有先例。
- **D-2026-10-14-5｜`M-6` 指纹「对齐对象」归属订正（保留 24 字符口径）**（用户，2026-10-14，记录 `LOG-20261014-006`）：① **★发现**：magpie 存在**两个同名「指纹」、用途与长度都不同** —— `internal/provider/keys.go:69-70`（`sum := sha256.Sum256([]byte(key)); hex.EncodeToString(sum[:5])`，实现在 `keyID()`）= **前 5 字节 / 10 字符**，标识**供应商 API Key**，`AddKeys` 去重即用 `seen[keyID(key)]` 比对（`keys.go:194,198`）；而 `internal/access/access.go:417-419` 的 `legacyLANKey`（`"lan-" + hex(sum[:12])`）= **前 12 字节 / 24 字符**，标识 **LAN 调用方访问令牌**，**与供应商 Key 无关**。② **★错误本体**：`M-6` 实现与 `V111` 头注释、`plan` §11.2、`research` §七 `M-6` 行**均引 `access.go:420-421` 作「对齐依据」** ⇒ **把调用方令牌口径当成了供应商 Key 口径**；且 `research` §4.2 自己写的恰是 `keys.go:68-74` 的 **5 字节** ⇒ **同一份文档内部自相矛盾**。③ **影响 = 叙述而非功能**：指纹是 helloai **内部标识**（不跨系统互操作）⇒ 去重 / 区分的目的已达成，**无功能缺陷**；错的是归属叙述，以及长度比真口径大一倍余。④ **裁定 = 保留 12 字节 / 24 字符，只订正归属**（用户，2026-10-14）：不取「真对齐」的理由 —— ⓐ 长度对「去重 / 区分」这一目的**无影响**；ⓑ `V111` 已 apply，**文件头注释与 `COMMENT ON COLUMN` 不可改**（Flyway checksum 不可变），改长度会与写死「24 字符」的 `V111` 注释产生**代码 / DB 矛盾**，还须再开一条迁移重写列注释；ⓒ 12 字节系**有意加强**（以留碰撞余量）。⑤ **订正落点（全部避开已冻结的 `V111`）**：`CredentialFingerprint` javadoc、`plan` §11.2 `M-6` 行、`research` §4.2（新增「两个同名指纹」对照）+ §七 `M-6` 行；`D-2026-10-14-4` ② 加删除线并加指向本条的订正标记（**保留原记录可追溯，不重写历史**）。⑥ **沉淀判据**：**称某处 magpie 口径为「对齐对象」前，必先在 magpie 仓内确认该符号的语义域** —— 「同名不同域」的符号（此处的指纹 / `Key.ID`）会诱导错误归属；判据须带 `path:line` **+ 该行所属函数名**（`keyID()` vs `legacyLANKey()`），仅凭文件名与行号易错。
- **D-2026-10-14-6｜`M-7` 批量录入去重的拆分口径与落库形态（含「不覆盖主 key」「备位只能落 DISABLED」）**（用户，2026-10-14，记录 `LOG-20261014-007`）：① **拆分口径 = 逐字移植 magpie `SplitKeys`**（`internal/provider/keys.go:165-178`）：分隔符集合 `,` `;` `，` `；` 半角空格 `\t` `\n` `\r` `\u3000`；每段**两端**剥引号 `"` `'` `` ` ``（对应 `strings.Trim(f, "\"'`")`）；空段丢弃；**输入内去重且保序**。落 `core/shared/util/KeyTextSplitter`（`shared` 为叶子域，纯算法无业务依赖，符合「纯数据 / 纯算法才放 `shared`」）。② **★刻意不做归一**：不 trim、不改大小写、不做形态校验 —— Key 明文将**原样**交给加密与指纹计算，任何「顺手归一」都会让指纹与密文描述的不是同一份字节（与 `CredentialFingerprint#of` 的「不做 trim」判据同源）。③ **落库形态 = 移植 magpie `AddKeys` 的「不覆盖主 key」语义**（`keys.go:183-222`：仅当 `p.Key == ""` 才把首把升为主 key，**否则一律 append 到备位**）⇒ helloai 映射：该 provider **尚无 ACTIVE** ⇒ 本批**首把升 ACTIVE**（`primaryActivated=true`）；**已有 ACTIVE** ⇒ 本批新增**全部落 `status='DISABLED'` 备位**，**绝不覆盖正在使用的主 Key**。④ **为何备位只能落 `DISABLED`**：`credential_vault` 的部分唯一索引 `uk_credential_vault_*_active`（`WHERE deleted=0 AND status='ACTIVE'`）**只允许同 (owner, provider, type) 一条 ACTIVE** ⇒ 多把并存只能借助非 ACTIVE 状态；故新增 `CredentialVaultService#saveDisabledPlatformApiKeyCredential`（**只 INSERT、不做任何 UPDATE**），与「顶替式」的 `savePlatformApiKeyCredential`（降级旧 ACTIVE + 新落 ACTIVE）形成**语义对照**。备位**转正**（启用 / 切换 / 停用）归 `M-8`。⑤ **计数口径**：服务端**先全批判重、再统一落库**，返回 `ApiKeyBatchSaveOutcome{added, alreadyExists, primaryActivated, addedFingerprints}`，文案即验收口径「新增 N 把 / 已存在 M 把」。两遍写法的价值**不是**改变本批结果（拆分器已去重 ⇒ 两遍与边判边落结果相同），而是让**计数不依赖「拆分器一定会去重」这一前提**：若将来拆分策略放宽，边判边落会把同批刚落库的重复把计成「已存在」，使「新增 N 把」**静默退化**。⑥ **命中即跳过，连加密都不做**（省无谓的密码学开销）；**有新增才** `providerChatModelCache.clear()`。⑦ **接口**：新增 `PUT /api/admin/llm-providers/saveApiKeysById/{id}`（请求体纯字符串，与单把端点同形），响应 `LlmProviderApiKeyBatchResponse`；单把端点 `saveApiKeyById` 行为不变。⑧ **前端**：`AddModelFormDialog.vue` 的「API 密钥」加「批量录入」开关 —— **关 = 原有单把密码输入（保留脱敏）**，开 = 多行 textarea 并提示分隔符，使多行粘贴**不牺牲**既有掩码展示。⑨ **验收含反证**：把判重分支改成 `if (false && …)` ⇒ `SaveApiKeysBatch` 组 **3 条红**（`shouldSkipExistingKeysInMixedBatch` / `shouldBeIdempotentAcrossCalls` / `shouldBackfillBeforeDedup`）⇒ 断言非自证。

- **D-2026-10-14-7｜`M-8` 多 Key 主备的手动切换语义与「启用位不可缺失」判据（含脱敏口径统一、明确不移植 failover/routing）**（用户，2026-10-14，记录 `LOG-20261014-008`）：① **移植对象**：magpie `internal/provider/keys.go` 的 `UseKey`（`:346-365` 手动切换）、`SetKeyOn`（`:381-405` 停用）、`RemoveKey`（`:407-423` 删除）。**明确不移植** `internal/gateway/fallback.go:1-16` 及其 `Weight`（`:31`）/ `KeyRest`（`:52-57`）/ `failQuota` 面（按配额 / 限流 / 健康度的候选轮换 + 冷却）—— 属红线 `N-1`（账号一键切换 / 自动 failover / routing / quota 轮换）；同样**不做**加权分流。② **★与 magpie 的模型差异（忠实翻译而非简化）**：magpie 的 key 是**列表 + on/off 位**，被替换掉的第一把「stays on, next in line」（`:346-365`）**仍可被 fallback 使用**；helloai 因部分唯一索引只允许**一条 ACTIVE** ⇒ 备位只能落 `DISABLED`（`M-7` 已登记）⇒ **被降级的主 Key 不再参与调用**。这正是「不做 failover」的必然结果。③ **★单一判据「启用位不可缺失」**：任何会使 provider **失去启用位**的操作（停用 / 删除**启用位**）都必须先**顶替**，**无备位可顶即 fail-close 拒绝**（`CredentialVaultServiceImpl#requireStandbyForSuccession`，全项目唯一实现点）。理由：放行会让该 provider 静默变「未配置」（`getActive*` 查不到 ACTIVE）⇒ 线上表现为「模型突然不可用」而**无任何显式信号**；magpie 同样拒绝（`keys.go:396-398`「that's the only key in use; turn another on first」）。**继任者 = 最旧的一把备位**（`create_time ASC, id ASC`，对齐 magpie 取「下一把在用的」`:369-379`），且**在响应里如实回报**（`previousActiveId` / `activeId`）——替用户选谁也不能悄悄选。④ **★同一判据必须覆盖所有入口**：除新增端点 `deactivateApiKeyByProviderIdAndCredentialId` / `deleteApiKeyByProviderIdAndCredentialId` 外，**旧通用端点** `POST /api/credentials/revoke/{id}` 亦必须走同一判据（`revokeCredential` 内对 `PLATFORM` + `ACTIVE` 分支同样先顶替），否则守卫「存在」却可被旧入口**绕过**（「规则存在」≠「全路径强制」）。**作用域限定 `PLATFORM`**：平台级缺启用位 ⇒ 该 provider 对**全平台**不可用，故需防呆；Agent 级缺启用位只影响该 Agent 自身的绑定且可随时重绑，过度拦截会破坏 N-004 已确立的停用语义。⑤ **★「先降后升」是唯一可行顺序**：`uk_credential_vault_*_active` 部分唯一索引只允许一条 ACTIVE ⇒ 切换 / 顶替必须**先降级原 ACTIVE、再升位目标**，两步同事务且均走 CAS（`WHERE status = <期望值>`），任一步不中即整体回滚；顺序颠倒会在两步之间同时存在两条 ACTIVE ⇒ 唯一约束冲突。**该顺序被写成断言**（`InOrder` 验审计落点顺序：先 `revoke` 后 `activate`），而非只写在注释里。⑥ **删除 = 软删**：走 `IService#removeById`（`BaseEntity` 的 `@TableLogic` ⇒ `UPDATE … SET deleted = 1`），保留行以供 `credential_audit_log` 追溯；**不引入物理删除**。删除**启用位**与停用同判据（先顶替）；删除**备位 / `EXPIRED`** 直接放行（不动启用位 ⇒ 不会使 provider 失去可用 Key）。⑦ **★脱敏口径统一到 magpie `Mask`**：新增 `core/shared/util/SecretMask`（迁 `internal/provider/provider.go:1501-1506`）—— 长度 ≤ 8 ⇒ 等长点号；否则**前 4 + `…` + 后 4**。原 `PlatformProviderConfigService#maskApiKey` 的自有口径 `"****" + 尾 4` **一并切换**，使「Provider 详情页的当前 Key」与「Key 列表逐行」**共用同一实现**（否则同一件事两处判据必然分叉）。逐行脱敏为 **best-effort**：`secretRef` 型 / 单行解密失败 ⇒ `masked = null`，只记 WARN、**不阻断列表**（与 `backfillFingerprints` 同风格）。与 `SysConfigServiceImpl.SENSITIVE_MASKED`（任意敏感**配置值**整体遮蔽 `"********"`）**有意区分**：后者不保留任何字符、不认密钥结构。⑧ **★跨域契约落提供方 `system.port`**：新增两个只读快照 —— `PlatformApiKeyView`（列表行：id / status / fingerprint / masked / createTime / remark，**不含密文**）与 `PlatformApiKeyChange`（操作前后启用位：credentialId / fingerprint / previousActiveId / activeId）；消费方 `agent` 域**不 import** `system.entity.CredentialVault`（§7.2 情形②，同 `CredentialSecret` 先例）。**编排落在 `agent` 域**（`PlatformProviderConfigService`）：改启用位后必须清 `ProviderChatModelCache`，而该缓存属 agent 域、system 域不得依赖 agent 域 ⇒ 由 agent 域串起「改启用位 → 清缓存」。**清缓存为无条件**（与 `M-7`「有新增才清」不同）：本组三个操作都可能改变「哪一把在用」⇒ 宁可多清一次，不可漏清导致已停用 / 已删除的 Key 仍被发请求。⑨ **★冗余状态一律不下发**：`primary`（= `status == ACTIVE`）与 `activeChanged`（= `previousActiveId != activeId`）均由既有字段**推导**，且推导放在 **API 响应 DTO** 而非 `system.port` 记录 —— 冗余状态必然漂移，但**判据只写一次**（让前端不必重复「什么算启用位」）。**亦不下发 `message` 文案**（与 `M-6`/`M-7` 不同）：那两处的文案承载「前端无法预知的结论」（已存在 / 未重复保存）；本组操作的结论由请求方自己发起 ⇒ 前端可自述，失败一律走 `BizException` fail-close。⑩ **验收含 3 组定向反证**：新增 **38** 用例；反证 —— ⓐ 拆掉「启用位不可缺失」守卫 ⇒ **3 条红**（`shouldRejectWhenNoSuccessor` / `shouldRejectDeletingLastActiveKey` / `shouldRejectWhenNoSuccessorOnPlatformActive`，即停用 / 删除 / 旧端点三个入口**各命中一条**）；ⓑ 去掉缓存失效 ⇒ **3 条红**（`shouldClearCacheOnActivate` / `…Deactivate` / `…Delete`）；ⓒ 颠倒「先降后升」⇒ **1 条红**（`assertPromoted` 的 `InOrder`）⇒ 三处断言均非自证。`helloai-core` 全量 **2115 / 0 失败 0 错误**（基线 2077 + 38）。**架构冻结 0 漂移**：本批新增 import 全部落在 `core.*.port`，而 `api->core.entity` 规则只匹配 `…com.helloai.core.<domain>.entity.` ⇒ 该计数不变（另两条既有告警的归因见 §「当前冻结基线」）。

- **D-2026-10-14-8｜`M-9` 能力谓词消费方切换与 `V52` 两列退役（含 modality 不映射技能词表、`thinking` 改由 `reasoning` 驱动）**（用户，2026-10-14，记录 `LOG-20261014-009`）：① **载体复用而非新建**：能力谓词列 `capabilities`(JSONB) 已由 `M-4` 的迁移 `V110` 建好（形态乙，`D-2026-10-13-9`），本批**只切消费方**、不新增表或列。**`V110:32-36` 登记的"删列硬前置"在本批兑现**：`V52` 两列（`capability_skills` / `available_optional_skills`）有两个活消费方 ⇒ 必须先切、再删。② **★无损性先证后删（退役的唯一前提）**：实测本地 PG 158 行 —— `available_optional_skills ⇔ capabilities->>'webSearch'` 为**严格双射**（`mismatch=0`），`capability_skills` 的 distinct 值 = **1**（恒 `["thinking"]`，158/158）⇒ 两列取值域闭合且可由谓词复现，故删列不丢信息。③ **★谓词→技能的封闭映射 = 3 条规则，且只写一次**（`core.agent.SkillCapabilityRequirements`，全项目唯一实现点）：`thinking ⟸ reasoning==true`；`web-search ⟸ webSearch==true`；其余 6 项标准技能（`shell` / `docker` / `sql` / `code-review` / `python` / `java`）**恒真**。恒真的依据不是"图省事"，而是**实测 `available_optional_skills` 的差集只有 `web-search`** ⇒ 恒真可 100% 复现 `V52`。**`toolCalling` 不消费**（实测 `false` 有 8 行，消费会**新增拒绝面**，超出"无损重放"边界）⇒ 登记「谓词已备、消费方待定」。④ **★`thinking` 改由 `reasoning` 驱动，是"能力声明不越出模型实际能力"的必要条件**（本批唯一的有意行为变更）：`V52` 下 `capability_skills` 对**全部 158 行**无条件锁定 `thinking`，其中 **48 行**模型 `reasoning=false` ⇒ 这正是 `M-9` 验收口径要消除的**越出**本身。裁定取 `A-升级`（依据 `log/2026-10.md:5790` 形态乙原文「`thinking` 可表达为 requires `reasoning` ⇒ `capability_skills` 的职责被完全吸收」）；**连带接受拒绝集合变化**：显式声明 `thinking` 而模型 `reasoning=false` 时由放行变 `400`。**存量影响 = 0**：`agent` 表 `API_KEY_LLM` 存活 4 行，模型全为 `deepseek`（`reasoning=true`）。⑤ **★modality 不映射到技能词表**：模态是**谓词**、8 项标准技能是**任务索取的能力标签**（条目侧），硬塞会把谓词退化成条目，并与 `agent.capabilities.supports{Image,Audio,Video}Understanding` 形成**第二个模态事实源**（而 `required_skills` 又无词表约束 ⇒ 新标签得不到强制）。故 `M-9` 描述的"modality 驱动"落点 = **`agent.capabilities` 模态三键由 `capabilities.input` 收敛**，且**只能缩小不能放大**（客户端传 `true` 而模型无该模态 ⇒ 落 `false`）。⑥ **★端口契约"换内容"而非"追加末位字段"**：`LlmProviderModelProfile` 由两列字段改为 `record(ModelCapabilities capabilities)` —— 两字段的消费方恰是本次要切的消费方，保留即留"`V52` 列的代码幽灵"（对照 `M-5` 的 `LlmProviderProfile` 属**新增**能力故取追加形态，两者形态不同是对的）。**`caps == null`（未识别模型）的降级出参必须与改造前逐字一致**（`capabilitySkills=[]` / `availableOptionalSkills=["shell","code-review"]` / `degraded=true`）；"已识别"判据由"白名单非空"改为显式 `caps != null`（原判据依赖"两列 `NOT NULL` 且默认非空"这一**隐含前提**）。⑦ **★删列不可逆 ⇒ 必须留归档快照，且"语义级回滚 ≠ 逐字回滚"**：`available_optional_skills` 可正向重放（双射已证），但 `capability_skills` 在 `A-升级` 下**不可**（原 `["thinking"]`×158 重放只得 110 + 48 空）⇒ `V112` 删列前把 158 行快照进新表 `llm_provider_model_skill_cols_archive`，回滚**只按归档回填**（**不采用派生回填**：实测 2 行软删残留的原值恰好逐字等于列默认值 ⇒ 「默认值」比「派生公式」更保真）。⑧ **★摘掉 `capabilities` 的列默认值，作结构性 fail-fast 守卫**：留 `DEFAULT` 会让后加行走列默认**静默丢谓词**（`M-4` 已登记的复发风险）；摘掉后任何漏写谓词的 `INSERT` 直接 `NOT NULL` 违例 ⇒ 不依赖扫描式守卫。**代价 = 发布原子性**：`saveProviderModels` / `addModel` 必须显式写谓词，且须与 `V112` **同批发布**（否则摘默认值后表管理路径当即违约）。⑨ **★"编辑 provider 模型列表"不再静默清零谓词**：`saveProviderModels` 是"先删后插"整表重写（`remove` 走 `@TableLogic` **逻辑删除**；部分唯一索引 `uk_provider_model_active` 带 `WHERE deleted = 0` 谓词 ⇒ 逻辑删后同键可重插），重插写保守面会**静默抹掉该 provider 全部模型谓词**（比改造前多丢 `thinking` 与模态），而静态守卫与 `V110` 的一次性断言都抓不到 ⇒ 判为 `T2` 的正确性收尾（**非既有问题**），改为**重插前先读旧行、按 `modelName.trim()` 归一复用既有谓词**（键归一必须与插入侧同一套，否则 `" x "` 与 `"x"` 会被判成两把而丢谓词）。⑩ **★新增文件级三源守卫**（`scripts/shell/verify-skill-capability-parity.sh`，接 `ci-gate.sh` 门禁 3）：`S1` 后端 `STANDARD_SKILLS` ⇔ 前端 `agentSkills.ts` ⇔ `REQUIRED` 键集**三源同集合**（**此前不存在任何技能词表守卫**——原以为"有守卫"系误记，本次属**新增**）；`S2` 退役列不得回写；`S3` 新迁移凡写 `llm_provider_model` 必须显式写 `capabilities`。**★守卫必须可反证且不可被形式化绕过**：`S3` 初版为整文件子串匹配 ⇒ **注释里出现 `capabilities` 即可绕过**（作者内置的 `--inject-*` 开关恰好能拦，属**自证陷阱**）⇒ 收紧为**剥 SQL 注释 + 词边界匹配**，并把该场景固化为 `--inject-comment-bypass` 回归锁。⑪ **验收**：新增 **42** 用例；`helloai-core` 全量 **2157 / 0 失败 0 错误**（基线 2115）；`V112` 以 `BEGIN; … ROLLBACK;` 空跑（归档 158 行），**并做负向验证**（人为注入双射偏差、缺 `input` 键 ⇒ 前置断言确实 `RAISE EXCEPTION` 拒绝删列）；**无损对账由验证者自算**（不采信作者产出的 TSV）：`aos ↔ webSearch` **158/158、0 失配**，`cs ↔ reasoning` **48 失配全为 `reasoning=false`（即 ④ 的有意差异）**；守卫三类违规**手工注入**（非用内置开关）均 `EXIT≠0`；**架构冻结 0 漂移**。⑫ **登记待办（不在本批）**：`McpToolServiceImpl` 的 checkIn **自报技能并集绕过能力校验**（既有，`QA-M9-03`，属"门存在但可绕过"同类）；归档表清理须走显式迁移；CI 门禁 4 在受限沙箱因 `node-safe-delete-shim` 拒 `emptyDir(dist)` 呈**环境假失败**（判据：`type-check` 已过 + `3942 modules transformed` 已产出 ⇒ 失败发生在最后清目录步，非交付缺陷）。

- **D-2026-10-15-1｜`G-022` 补引用边（不统一词表）；`QA-M9-03` 收口**（用户，2026-10-15，记录 `LOG-20261015-001`）：① **★补引用边而非统一命名空间**（在原修法①~③ 中选① 的**精确化形态**）：统一词表会让细粒度条目依赖粗粒度词表的**命名**，实质是「技能点名能力标签」，并把能力词表的增删传导成技能包的破坏性变更；DSH 判据是「**技能不点名模型** ≠ **技能不提需求**」——两者常被混同。故新增 `SkillPackage.requiredBaseCapabilities`（frontmatter 白名单 **8→9 键**，四处联动契约：`Parser.KNOWN_KEYS` · `record` 分量 · 内置包 · **契约断言测试**；`skill_package` 同名列，迁移 **`V113`**），判定式 `requires ⊆ provides` 落在 `SkillCapabilityRequirements.satisfiesPackage`（**唯一判据**，与标准技能的 `satisfies` 同构但**不可合并为一张表**——两个关系正交）。② **★4 个内置包中只有 `eng-web-research` 声明 `webSearch`**（依据：其 `requiredTools` 含 `web_search`，与 `M-9` 已证的 `web-search ⟸ webSearch` 同一事实源）；其余 3 个（`eng-code-review` / `eng-doc-standard` / `eng-verification`）**有意留空**，理由逐包写入 frontmatter 注释——它们是**作业规范**（与 `M-9` 中 6 项恒真技能同源）。**爆炸半径实测**：`reasoning` 会拒 **48** 个模型、`toolCalling` 会拒 **8** 个 ⇒ 声明二者属**新增拒绝面**而非消除越界，延续 `M-9` 对 `toolCalling` 的同一裁定（**「恒真」也要有实测支撑**）。③ **★`V113` 保留 `NOT NULL DEFAULT '[]'`（与 `M-9` 摘默认值相反）**：此处空集**就是事实**（存量包装入时该键尚不存在），不是「静默丢数据」；fail-fast 放在**写入侧**（`SkillPackageInstallService` 与迁移同批发布，显式写解析值）。**判据是「值从哪来」，同一手法不可脱离场景照搬。**④ **消费方①（注册链）**：`AgentSkillDeriver.deriveWithCapabilities` 增第 ⑤ 步（幂等并入技能包标签）+ **兼容重载**（不传包面 ⇒ 逐字保持原行为）；**谓词筛选在装配层**（`AgentSkillPolicyService`，它持有技能包目录），目录异常时**降级为挂载前行为**，不让技能面故障阻断注册。⑤ **★`QA-M9-03` 收口（兑现 `D-2026-10-14-8` ⑫ 的首项待办）**：`checkIn` 自报技能此前**完全绕过**能力校验（实测 `inner-deepseek-flash-excutor` 被塞 `web-search` + `eng-web-research`，而其模型 `deepseek-v4-flash` 的 `webSearch=false`）⇒ 改走**同款判据**，越界项**丢弃并告警**（不抛错：抛错会打断打卡主链）；作用域**限定 `API_KEY_LLM`**（外部 Agent 的模型由其自身掌控，平台无从判其能力，与 `agent.capabilities` 模态三键的同源口径一致）。⑥ **刻意不写存量回填迁移**：挂载需同时读技能包目录与模型谓词，SQL 侧复现会造第二个事实源；存量靠重注册/编辑触发。⑦ **守卫增 `S4` / `S5`**（`verify-skill-capability-parity.sh`）：`S4` 内置包 frontmatter 谓词 ∈ 已登记词表（**拼错的谓词会放宽而非收紧匹配**，属放宽型失效方向，必须 fail-closed）· `S5` 解析器白名单与 `record` 分量同步（防「契约半改」）。⑧ **验收**：新增 **22** 用例（`SkillPackageCapabilityEdgeTest` 16 + 解析用例 1 + 契约断言更新 1 + 既有测试适配 4）；`helloai-core` 全量 **2179 / 0 失败 0 错误**（基线 2157）；守卫 `S1~S5` 正验 `EXIT=0` 且三个**手工反证**均 `EXIT≠0` ⇒ 断言非自证。⑨ **登记未达成**：存量 `agent.skills` 未回填 · 拆解侧无前置校验（静默等待仍在）· `eng-*` 装进内部 LLM 工具授权集（真实缺口在 `requiredTools` → `ToolRegistry` 接线，**不在本引用边**）。

- **D-2026-10-15-2｜外部漏洞清单核验结论 + 4 项可靠性修复（选择性修复）**（用户，2026-10-15，记录 `LOG-20261015-002`）：对另一 AI 产出的 14 项清单（`HIGH-1~3` / `MED-1~4` / `G4~G9` / `R5`）逐条实证，**6 条成立、5 条部分成立、3 条被反证**。① **★4 条结论被反证/订正**（教训：**「机制存在」≠「结论成立」**，四条均是「看到半个事实就下整体结论」、漏查同仓已有兜底）——`HIGH-1`「ASSIGNED 无 record 永久卡死、无自动重试」❌（实有 `AssignedSubTaskTimeoutTask`：`@Scheduled(fixedRate=30_000)`+ShedLock 扫 `status='ASSIGNED' AND update_time < now-10min` → `redispatchAssignedTimeout`，**且不依赖离线探测**（`SubTaskMapper.xml:183-189` 纯时间条件）⇒ 是「~10min 自愈的延迟」）；`G5`「降级替代 Agent 跳过在线/心跳/熔断/凭据」+「裸抛 500」+「需确认 ignore 配置」❌（`pickAlternative`→`pickFromCandidates` **恰含**四项过滤（`AgentSelector:173-193`）；实抛 `BizException(409)`；`ignoreExceptions` **已含** `AgentUnavailableException`）；`G7`「CLI/Browser 的 ASSIGNED 无看门狗」❌（`selectTimedOutAssigned` 不限接入类型）；`G9`「默认配置不可启动」❌（`producer-enabled`/`consumer-enabled` 均 `true` + `ExecutionDispatchValidator` fail-fast）。**清单可靠性信号**：`runViaRuntime` 实际在 `LocalExecutionCommandConsumer`（非 `RuntimeTurnExecutor`）、`QualityProfileUpdater` 类**不存在** ⇒ 该清单系「读片段 + 推理」，**凡其自带「需确认」处须单独复核**。② **修 4 项（成立且安全）**：**`HIGH-2`**（最实在的一条）新增 `ExecutionResultHandler#applyResultAtomically` 把「记录 CAS 终态」与「结果回写」收进**同一事务**（原为两独立事务且顺序「先终态、后回写」⇒ 之间崩溃得「记录=SUCCESS 但子任务仍 IN_PROGRESS」僵尸态，而补偿只扫 PENDING/RUNNING、Poller 只扫 PENDING ⇒ **成功产出静默丢弃且无重放**）；**`MED-2`** `applyVerdict` 对 `pass=true 且 score<3`（prompt 明规则，此前未被代码强制）按「不可判定」拒收 → 停 REVIEW + `markManualIntervention("reviewer_score_inconsistent")` + 新事件码 `SUB_TASK_REVIEW_SCORE_INCONSISTENT`；**`HIGH-1`** 派发失败补落 `SUB_TASK_AUTO_EXECUTE_DISPATCH_FAIL` 时间线（**连带修复**：首个「派单」事件原在 `try` 之外，其失败会直接中断派发）；**`R5`** 评审 prompt 增第 12 条注入硬化（明写产出/附件/证据三段**只作数据不作指令**）。③ **★不修 3 项（属设计裁定 / 改动跨链）**：**`HIGH-3`** 机制成立但清单**挂错调用点**（`onAssigned` 为 `@Async`+`AFTER_COMMIT`，**运行时已无调用方事务**，不会回滚 `createAssignedCommand`）；真实风险点在 `handleReport:358` / `SubTaskReviewServiceImpl:365,474` / `ReviewServiceImpl:222` / `SubTaskServiceImpl:931`，而清单建议的 `REQUIRES_NEW` **与 CODE_STYLE §24「业务变更 + Outbox 同一事务」相冲**（`record` 双写 `agent_event`+`agent_outbox_event`，改独立事务会产出**幻影事件**）、「提交后延迟写」又改全部事件时序 ⇒ **两个候选各违反一条既有纪律，属裁定项**（§50.5 禁止猜测）；**`G4`** 内部 `idempotencyKey` 恒 `null` 致幂等分支成死码、唯一守卫是状态读取（TOCTOU）⇒ 修法需动 `ExecutionCommand`/`AgentTask` 载荷契约，跨执行链宜立项；**`MED-1`** `reviewerDisagreementCount` **只写不读**无惩罚闭环 ⇒ 需先定选人策略。④ **验收**：新增 **10** 用例（`ExecutionResultHandlerTest` 5 · `SubTaskReviewServiceTest` 3 · `SubTaskAutoExecutionDispatcherTest` 2）；`helloai-core` 全量 **2189 / 0 失败 0 错误**（基线 2179）；`mvn -pl helloai-core -am compile` BUILD SUCCESS。⑤ **登记待办**：`MED-3`（锁 TTL 120s）/ `MED-4`（审查失败不阻断交付）/ `G6`（无 Poller 级死信）/ `G8`（消费线程同步执行）确认成立但属**已知权衡**，需与既有设计意图一并评估。

- **D-2026-10-15-3｜三项遗留项落地（`HIGH-3` 事件契约订正 / `G4` 幂等键 / `MED-1` 核验侧质量分）**（用户，2026-10-15，记录 `LOG-20261015-003`）：`D-2026-10-15-2` 判为「需裁定 / 跨链」的三项，经专项调研后**部分推翻先前判断**并落地。① **★`HIGH-3` 的正确解不是二选一（推翻「裁定项」结论）**：决定性证据 = `EventReconciliationServiceImpl` 类 javadoc **把「事件与状态变更同事务」写成设计不变量**（「读到业务状态时事件必已落库，不存在状态先于事件可见的竞态」），且该对账服务**只检测不修复**（`mismatches++` + `log.warn`）⇒ `REQUIRED` 是**有意设计**；`REQUIRES_NEW` 会造幻影事件并作废该不变量，「after-commit」会让事件**永久丢失且不可自愈** —— **两者皆不采纳**。真正要修的是**调用侧的假契约**：`AgentEventRecorder` 接口原文「埋点调用方应按『事件 write-only』纪律自行降级（try-catch 不阻断主链路）」为**假**（try-catch 只挡异常冒泡、挡不住共享事务被标记 rollback-only）⇒ 订正接口 javadoc 为真实语义 + 正确用法两条；`ExecutionResultHandler#handleReport` 的 `AGENT_COMPLETED` **去掉 try/catch**（fail-fast，原实现 catch 后继续做注定回滚的无效工作、根因被延迟成 commit 处的 `UnexpectedRollbackException`）；`ReviewServiceImpl` 那处（另一确认在 `@Transactional` 内的站）保留 catch 但改为**如实文案**。② **★`G4` 规模判断订正**：先前记「需动 `ExecutionCommand`/`AgentTask` 载荷契约」**有误** —— `recordId` 已在调用现场且每次执行唯一 ⇒ 幂等键直接取 `record:<recordId>`（新增 `idempotencyKeyOf` + `doHandleSuccess`），使 `handleReport` 的 `idempotent_duplicate` 分支对内部执行**不再死码**，**零契约变更**。③ **`MED-1`**：新增 `AgentQualityProfileService#computeReviewerQualityScore`（口径 = `100 - 分歧率`，只用 reviewer 维度字段 ⇒ 与执行侧分互不污染；无信号返回 `null` 而非 0，避免惩罚新人 Reviewer），并在 `ReviewerPickerImpl#listUsableReviewers` 按其**降序软排序**（只改优先级、不剔除候选 —— 分歧率高也可能是严格的正确判断）。**根因是接线缺口**：画像表早有 `reviewerReviewedCount`/`reviewerDisagreementCount` 字段却**全仓零读取**。④ **验收**：新增 **10** 用例（`ExecutionResultHandlerTest` 5 · `AgentQualityProfileServiceTest` 6，含「同键二次上报判重复且合计只 submit 一次」「两套分互不污染」「事件失败直接抛出」等断言）；全量 **2199 / 0 失败 0 错误**（基线 2189）；架构冻结 **0 漂移** · 事件码 parity PASS=5 · 差距表 4 项 · 技能能力 `S1~S5` 全过。⑤ **判据沉淀**：**「两个方案各违反一条纪律」时先问那条纪律是否为真**（本项的正确解 = 消除从未成立的假契约，而非在两害中选一个；识别「伪二难」= 找出双方共同预设的前提并验证它）。

- **D-2026-10-15-4｜剩余四项评估：`MED-3`/`G8` 升级为真缺陷并修复，`MED-4`/`G6` 经核不改（含订正我先前的错误建议）**（用户，2026-10-15，记录 `LOG-20261015-004`）：① **`MED-3`（评审锁 TTL）从「已知权衡」升级为「已量化的真缺陷」并已修**：量化证据 = LLM 读超时默认 `180000ms`、**dev 档 `600000ms`**（`application-dev.yml`，为整合报告长输出设 `deepseek-v4-pro`）**> 锁 TTL 硬编码 `120s`** ⇒ 单审调用可合法跑到读超时，锁已过期 ⇒ L1 事件 / L2 MQ / L3 孤儿扫描可抢**已过期锁**并发核验（重复 `review_record` → 画像重复计数）。**★作者早已推理过该不变量但只覆盖双审**（`ReviewProperties#dualReviewTimeoutSeconds` javadoc 原文即此推理，双审因此收紧到 90s），而**单审路径无 deadline**，其上界由 provider 读超时决定 ⇒ **同一功能的多条路径，安全属性须分开验证**。修法：`ReviewProperties#lockTtlSeconds`（新，默认 240，**语义为下限**）+ 新增 `ReviewLockTtlResolver`（有效值 = `max(配置值, 各 provider 读超时最大值 + 1s 余量)`）。**★为何不用 fail-fast（我最初方案已推翻）**：`read-timeout-ms` 按 profile 分档，而 `application-dev.yml` 被 `skip-worktree` 标记为**禁提交**的本地私有件、其 600s 又**已在仓库版中** ⇒ fail-fast 会让 **fresh clone 的 dev profile 起不来**；**约束方向唯一时，自动纠正优于 fail-fast**（同时 WARN 留痕）。② **`G8` 从「已知权衡」升级为「守卫覆盖缺口」并已修**：`LlmCallConcurrencyGuard`（8 许可）**只接在 `AgentChatClientServiceImpl.generate/generateStream`**，而执行链走**另一条缝**（`AgentRuntimeContextAssembler:250 buildChatModel` → 裸 `ChatModel` → `ChatModelToolLoop:139`，`runtime/**` grep 该闸门 **0 命中**）⇒ `maxConcurrentLlmCalls` 对**最高频 LLM 路径失效**，而 guard javadoc **主动声称**覆盖「执行」⇒ 与 `QA-M9-03` 同类。修法：新增装饰器 `ConcurrencyGuardedChatModel`，在 `buildChatModel` **出口**包装（该出口是执行链唯一取模型通道 ⇒ 一处全覆盖且新增调用点不再漏；且与 chat 路径的 `buildChatClient` 是**两条平行缝**，不会重复取许可而致**信号量死锁**）。③ **`MED-4` 不改并订正我先前的建议**：原建议「补 `markManualIntervention` 对齐惯例」**前提错误** —— 该 API 是**子任务级**，而终稿审查是**任务级**，全仓**不存在**任务级人工介入概念；且任务级既有降级惯例（`TASK_FINAL_REPORT_MAX_REVIEW_REACHED` 分支）本就是「落 timeline 事件 + `convergeToDone`」，与 LLM 失败分支**做法一致** ⇒ `convergeToDone` 系有意设计（「不收敛则 REVIEWING 永远卡住无恢复口」），机械 HARD 门禁仍可强制返工且受 `autoFinalReportMaxReview` 封顶。**教训：给跨层级概念做类比前先确认 API 的作用域。**④ **`G6` 不改**：`markTimeout`（CAS `PENDING|RUNNING → TIMEOUT`）+ `pendingTimeoutMinutes=5` ⇒ 5 分钟后转 TIMEOUT、`listOrphanPending` 的 `status=PENDING` 过滤即不命中 ⇒ 重扫**有界（≈5 次）**，非「无限」；`ExecutionCompensationTask` 提供**等价终局**。⑤ **验收**：新增 **15** 用例（`ConcurrencyGuardedChatModelTest` 5 含「delegate 抛异常仍释放许可」· `ReviewLockTtlResolverTest` 10 含「120s vs 180s 自动抬高」「dev 档 600s ⇒ 601s」）；全量 **2214 / 0 失败 0 错误**（基线 2199）；架构冻结 **0 漂移** · 事件码 parity PASS=5 · 差距表 4 项。⑥ **判据沉淀**：**「配置常量之间的序关系」是可机械发现的缺陷类**（两常量各自看都合理、只有比大小才暴露）· **javadoc 声称的覆盖面必须与 grep 接线结果对账**。

- **D-2026-10-15-5｜`D-2` 多实例鉴权：MCP 鉴权上下文外置 Redis（静态工具类 → `@Component`）**（用户，2026-10-15，记录 `LOG-20261015-005`）：① **缺陷本体**：`McpAuthContext` 原以 `static ConcurrentMap SESSION_AUTH` 作**进程级注册表** ⇒ 单实例假设；多实例下鉴权写入（握手 / `POST /mcp/messages` 落 A）与工具调用（spring-ai 反射执行 `@Tool` 落 B）**不保证同实例** ⇒ B 查不到即 401「session 未鉴权」，**现象像鉴权 bug 而非架构 bug，探活解决不了**。与 V1→V2 分布式主线**直接冲突**。② **★推翻 V1 旧决策的前提**：`archive/legacy/HelloAI_迭代执行记录_V1.md:3097`「`SESSION_AUTH` 存 Redis 无意义」的论证**只覆盖「重启耐久」**（单实例下存内存即可），V2 多实例路由**取代**该前提 ⇒ 旧结论不再成立；`research/Octop全库借鉴分析_综合版.md` 已给正解（外置 Redis + TTL，顺带消灭 `SessionAuthCleaner`；**不得删除 `_sessionId` 协议参数**）。③ **实现**：`static ConcurrentMap` → Redisson **`RBucket<String>`**（JSON + TTL）；key = `helloai:mcp:session-auth:{sessionId}`（CODE_STYLE §20.1）；TTL 配置 `helloai.mcp.session-auth-ttl-minutes`（**默认 30**，取原 core 清理器口径），**读取滑动续期**（等价原 `lastAccessAtMs` 刷新语义，活跃会话不过期）；`AuthContext` record 移除 `lastAccessAtMs`；消费方（`McpAuthFilter` / `McpAuthFilterConfig` / `McpMcpServer`）改**构造器注入**。④ **★删两个进程内清理器**（core `SessionAuthCleaner` 30min / job `McpSessionAuthCleanupTask` 120min）：**外置 ⇒ 进程内 TTL 清理器必然变死代码**（只扫本进程可见 key，跨实例写入的扫不到）；留着的害处是误导后人以为「有清理」，且原两者阈值**口径打架**。⑤ **★失败模式按可自愈性分级**：`put` 写失败 **fail-fast 抛异常** ⇒ filter 转 401「服务内部错误，请稍后重试」（宁可当场可重试，不要延迟成工具调用时**误导性的「session 未鉴权」**）；`read` Redis 故障 ⇒ 401「鉴权服务暂时不可用」（**不外泄内部细节**）；载荷损坏 ⇒ 401「上下文损坏，请重新握手」；`refreshTtl` / `evict` 失败 ⇒ **仅 WARN**（不推翻已成功结论 / 不阻断 404 提示）。⑥ **验收**：新增 `McpAuthContextTest` **19**（`McpAuthFilterTest` 重写保 9）；`helloai-core` 全量 **2239** / 0 失败 0 错误（job 96 · api 127 · start 29 全绿）；门禁 0/1/2/3/5 全 `[ OK ]`，门禁 4 `[FAIL]` = **已知沙箱删除守卫误报**（反证 `vite build --outDir dist-proof-d2 --emptyOutDir=false` ⇒ `✓ built in 29.49s` / rc=0）。⑦ **沉淀判据**：**「进程级 `static` 状态」是一类 V1→V2 待清偿项**——识别手法 = grep `static .*Map` / `static .*Set` 进程内共享态，逐个问「多实例下写 A 读 B 会怎样」；**旧决策的「前提」随架构演进失效 ≠ 旧决策当时错了**，引用历史决策前先核其论证覆盖的场景边界是否仍成立。

- **D-2026-10-15-6｜`REF-8.4` 交付状态回填 + A/B 挂点口径订正（文档漂移清偿）**（用户，2026-10-15，记录 `LOG-20261015-007`）：① **★事实回填**：`REF-8.4` 拦截链**四件套已于 2026-10-13 全部交付**（① 三档挂点骨架 `09ecd747` · ② 收尾轮 `873bb559` · ③ `VerificationFence` / ④ 出口分类 `3b5224a6`；全量 2031 例绿；记录 `LOG-20261013-010`/`-011`/`-012`），形态 = **循环自有 `LoopHook` 三档**（`beforeRequest` / `onRequestError` / `beforeTerminate`，`ChatModelToolLoop:129/142/236` 接线；`FinalReportFenceHook` 为 `beforeTerminate` 首条实现），**不是** Spring AI `Advisors`（三处硬约束见 `plan §10.7.3`）。② **★挂点口径订正**（`plan §10.7.2`「同步订正建议」的落地）：`REF-8.4` ≠ 「A+B 唯一挂点」——**B 收敛熔断挂拦截链；A 能力闸出列、落 `ResilientDispatcher`**（形态 = 与 `isExecutionDenseMismatch:174-195` 并列的 `isAttachmentReadMismatch`，复用同一 timeline 事件 + 同一 `markManualIntervention`）；判据 = **A 天然不在链上**（须在 `assignNext` 提交点之前否决，而循环内否决必然已越过提交点）。**实测**：`isAttachmentReadMismatch` 全仓 **0 命中** ⇒ A/B 仍属「已立项待实施」，本订正**只改归属、不改状态**。③ **★订正范围（§7 引用完整性同批）**：差距表 `D-2026-10-12-1` ④（加删除线 + 指针）· `D-2026-10-12-3` · `G-021` 行 · §4 `REF-8.4` 行；`plan/HelloAI 借鉴落地实施计划.md` 文档头 · §2 次序图 · §10.2 · §10.3 · §10.7.2 尾注 · §5 沙箱预案；`plan/HelloAI 计划编排与排期总览.md` §2.6 · §4 `T2` · 依赖图 · §5 尾注；`research/外部提案评估：KIMI「技能执行」分层方案与 Archify 类技能接入（2026-10-15）.md` §5。**日期裁定记录保留原文**（`plan` 文档头版本条目、编排总览 §5·§5.1），按同 `D-2026-10-14-5` 的手法**保留原记录可追溯、不重写历史**。④ **★连带订正（同域漂移）**：`plan §5` 沙箱预案原写「`ProcessBuilder` 在 `src/main` **全库 0 命中**」**已失效**（`REF-2.3` 于 2026-10-10 落 `PgDumpRunner` ⇒ 全仓实为 **1 处** `PgDumpRunner.java:300`，自身已判**不触发**条件①）；同批把 **`G-005` 条件①/② 的判定口径钉死**为「**平台侧是否出现一个 *agent 可调用* 且 *碰宿主* 的工具**」（先例 `D-2026-10-10-2②`），并据此消解 KIMI 调研文档 §5 表行② 与同节 bullet 的**自相矛盾**（原表写「否（进程外隔离）」、bullet 写「②/③ 一律重新排期」）。⑤ **沉淀判据**：**「已交付」与「未交付」同属事实 —— 把已交付项留在「未达成」里是同类缺陷的另一半**：它让人按**已作废的挂点关系**排期（本次第一优先级曾据此空转一格）。**口径订正须与代码实测同批**（本例 A/B 均未实现，状态不变），**并须一次清掉全部活文档命中**（只改一处 = 留下新的漂移源）。

- **D-2026-10-15-7｜门禁 3「守卫自身失真」三类缺陷清偿 + Shell 可移植性守卫**（用户，2026-10-15，记录 `LOG-20261015-008`）：① **★缺陷本体（三类，均实测）**——**C1** bash 4 语法：`declare -A` 在 bash 3.2 下 `-A` 判非法（非致命）⇒ 赋值退化为索引数组 ⇒ 下标走算术求值 ⇒ `set -u` 下**中止脚本却返回 0**（对照：普通变量未绑定返回 1）⇒ `shell/verify-provider-catalog-parity.sh` **自 2026-10-13 建成起从未执行过任何断言**（门禁记 `[ OK ]`）；**C2** `$VAR` 后紧跟全角标点（`$S5_KEY（`）在 bash 3.2 + UTF-8 locale 下把多字节字符**首字节并入变量名** ⇒ 同上「中止却 EXIT=0」⇒ `shell/verify-skill-capability-parity.sh` 的 S5a/S5b 从未执行（其 `EXIT=1` 是**原因错**的响亮失败）；**C3** 管道右端 `grep -q` + `set -o pipefail` ⇒ 上游 SIGPIPE(141) ⇒ **间歇假红**（实测 270 次判误判 **187 次**，`grep -c` 形态 0 次）。② **★决策**——**换实现、不加版本门槛**（同 `LOG-20261011-010` 手法：不加 `BASH_VERSINFO` 断言把守卫变成「本机不可用」，而是去掉 bash 4 依赖使其**真能跑**）；**C2 一律写 `${VAR}`**，机械判据**只判 bash 实现**（zsh 多字节解析正确，7 个 zsh 脚本 19 处同形写法**不改**）；**C3 一律禁止**（不按「生产者是否多行」逐处判断 —— 该形态依赖「生产者恰好一次小写入」的**隐性前提**，且受 **source 它的调用方**的 `pipefail` 影响）；**门禁 3 加统一执行器**（守卫须 **rc=0 ∧ 产出 ≥1 行断言 ∧ 产出完成标记**，缺一即判未生效，方向 **fail-closed**）；**新增机械守卫** `scripts/ci/check-shell-portability.sh`（P1 bash4 语法 / P2 变量插值边界 / P3 管道判据 + 反空转地板 + 自证 + 反证三连）并纳入门禁 3 ⇒ 该族**第三次**出现即被拦。③ **★范围与例外**——C3 改造 **19 处 / 10 个脚本**（`deps-context` 6 · `executor-doc` 3 · `skill-capability` 2 · `minio-artifact` 2 · 其余各 1）+ 3 处 `declare -A` + 1 处 `${VAR}`；**`deploy/` 侧 C3 同族 4 处登记为已知例外**（P3 范围 = `scripts/`，纳入方式见 `scripts/README.md` §五.4）。④ **★沉淀判据**——**(a)**「守卫存在 ≠ 守卫生效」须落到**可机械校验**的形态（断言产出 + 完成标记），否则一个 `EXIT=0` 就能把「一条断言未跑」伪装成通过；**(b)** 修假绿的收尾动作是**「重跑该守卫全量断言」**而非改一行 —— 被假绿掩盖的缺陷会**成批浮出**（本例 C1 修好后立刻冒出 8/9 条假 FAIL）；**(c)** 同族**第二次**出现时必须补**族级判据**，只修实例必然复发（`LOG-20261011-010` 当时只做了 zsh→bash）。

- **D-2026-10-16-1｜`LOG-20261011-007` A（能力闸）+ B（收敛熔断）实施，含两处前提/判据订正**（用户，2026-10-16，记录 `LOG-20261016-001`）：① **A 落派发层**（`ResilientDispatcher.isAttachmentReadMismatch`，与 `isExecutionDenseMismatch` 并列同风格；信号源 `SubTaskQueryPort#requiresOriginalAttachment` + `SubTaskDispatchService.isAttachmentReadDense` **两侧词表共现**；能力侧新增 `AgentCapability#hasStructuredAttachmentReadCapability`；**覆盖首选 / fallback / N11 三个派发入口**；复用既有 timeline 事件 `sub_task_dispatch_skip_no_capability` 与人工介入标记 `dispatch_skip_execution_dense`，仅以 payload `reason` 区分；新增独立开关 `helloai.dispatch.attachment-read-skip-enabled`，默认 true）。**★前提订正**：`LOG-20261011-007` 原文「内部 LLM 工具面**无**读附件能力」**已过期** —— 实测 `INTERNAL_LLM_TOOLS` 含 `internal_read_attachment`（`REF-8.1` 交付），实现为 `new String(content, UTF_8)` ⇒ **缺口从「没有读通道」变为「通道只做 UTF-8 解码，读不出二进制结构化文件的列」**。落点与形态不变，判据表述改为「不具备**结构化附件解析**能力」。② **B 落评审层 `RepeatedFailureGate`**（不落链上——循环内一次执行只有一份轮报，无「上一份」可比）。**★判据口径订正（重要）**：`D-2026-10-12-3` 原文为「连续 N 次**相同驳回原因**」，但「同因」只能靠文本相似度识别，而该路已被 `review/HelloAI 架构V2进度与质量审计报告（2026-09-30）.md` §15.3 用真实语料**实测证伪**（全文 Jaccard r1↔r2 = 0.2537 / r2↔r3 = 0.1927；**首条 `[defect]` 标签 Jaccard = 0.1203 / 0.1620，比全文更低**；根因 = 三轮用三套不同编号指代同一批缺失项，字符 bigram 捕捉不到语义相同）⇒ 「换比较对象为 `[defect]` 标签」这类改良**同样已证伪**，故改用**句法级信号**（零阈值标定）：**执行者轮报 `STATUS` 连续 N 轮 ∈ {`BLOCKED`,`PARTIAL`} 且窗口评分未严格提升**（= `LOG-20261012-008` 决策 3 的 `status=blocked` 半条）。数据面：`reviewHistory` 每轮新增 `executorStatus`（由 `SubTaskReviewServiceImpl#parseExecutorStatus` 经 `VerificationFence` 从 `lastExecution.output` 解析；解析不到则不写 ⇒ 判据「不适用」，不误伤无围栏的外部执行者）。命中时随证据下发 `annotation=executor_incapable_or_input_missing`，三处 payload 与对话流一并带上「能力不足 / 输入缺失」归因。新增 `helloai.dispatch.auto-review-self-stall-enabled`（默认 true）与 `auto-review-self-stall-rounds`（默认 3，对齐 `D-2026-10-12-3`）。③ **两处「刻意不做」**：A 不接认领侧（无 `isExecutionDense` 先例，且派发层已覆盖全部落库入口）；B 不做 `NEXT_STEPS` 重复半条（需文本比较，同上证伪；已登记待办）。

- **D-2026-10-16-2｜内部 LLM 运行时合同核对的**基准口径**改为「以 DSH 为准」（原报告 G1~G4 重排）**（用户，2026-10-16，记录 `LOG-20261016-002`）：① **事实层：原报告 13 条 `[实测]` 全部复核命中、无需订正**（证据链可原样沿用；见重判报告 §一）。② **基准层订正的起因**：该主题的 2026-10-15 初版核对报告（**从未入仓、已于 2026-10-16 删除**；其仍有效内容已**全量并入**重判报告 §一/§八）以**外部文章 12 节**为唯一基准，未使用本项目内部 LLM 执行逻辑**实际参照的 DSH**。实测 DSH 有三处与文章要求不符 —— **DSH 无 `refusal`**（`packages/llm/llm/src/types.ts:156-162` 只有 `{stop, tool-calls, max-tokens, aborted, error}`；`llm-deepseek/src/translate.ts:92-94` 仅三支映射；`packages/llm/` 全库 0 命中）；**DSH 无预算闸**（全库 grep `costBudget` / `budgetUsd` / `maxCost` / `haltBudget` 0 命中；`guard/` 仅 `repeat-tool-reminder`（**advisory 不否决**，阈值 `[3,5,8]`）+ `timeout-policy`（单工具 `TOOL_TIMEOUT`）两包）；**DSH 无步数上限**（`core/agent-loop/src/agent.ts:315` 是 `while (true)`；`constants.ts:6` 唯一常量 = 并行工具数 10；全库 `maxSteps` / `maxTurns` 仅见于 `subagent-claude-code` 测试的 SDK 透传参数）。③ **重排结果**：**G3 取消 / 超时**（DSH 一等公民：`agent.ts:46,50` `AbortController` + 16 处 `signal.throwIfAborted()` + 专测 `tests/cancel.spec.ts`）**> G1 的 max-tokens 半条**（DSH `agent.ts:547` 独立终态 + `:338-343` **max-tokens sticky**；HelloAI `ChatModelToolLoop:171-175` 静默放行）**> G4 usage 拆分**（DSH `types.ts:175-188` 互斥五字段）**> G2 独立预算闸（双方皆无 ⇒ 自研增强，非对齐项）**。**G1 的 refusal 半条与 unknown 半条属「双方皆无」**（DSH `assembler.ts:186-188` `this._finish ?? { kind: 'stop' }` 即「缺省当成功」，同属文章反模式① 形态）。④ **原报告漏掉的三处 DSH 机制**：`concludesTurn`（`tool-calls.ts:158`；`agent.ts:555`，工具可声明结束整个 turn）· turn/step 双层循环 + `inbox` 反馈（`agent.ts:315` / `:409`）· 空响应硬失败 `EMPTY_RESPONSE`（`translate.ts:149`，与 HelloAI 空产出护栏**功能对等**）。⑤ **订正范围与载体归并**：初版报告的 §〇 第 2、3 点定级已作废（**该文件已删除**，不再单独存在）；其 **§一~§八 事实层无条件有效**，已**全量收编**为重判报告 §一（13 条实证）与 §八 附录 A/B/C（超纲项 / 七条反模式 / 非运行时两件事）⇒ **`review/内部 LLM 执行链运行时合同核对·以 DSH 为基准的重判（2026-10-16）.md` 为该主题唯一事实源**。⑥ **沉淀判据**：**「核对基准」本身就是结论的一部分** —— 拿外部通用文章当基准，会把「参照设计早已不做」的项目判成缺口（本例 3 条），也会把「参照设计也不做」的自研项误列为对齐项。⇒ **复核对标参照设计的项目时，基准必须取「实际参照实现的源码」，文章的应然清单只能作补充维度**（同 `MEMORY` 「枚举纪律 / 归因纪律」同族：先确认真值来源，再下结论）。

- **D-2026-10-16-3｜`plan/` 侧文档漂移清偿 + `ReviewProperties` 注释恢复**（用户，2026-10-16，记录 `LOG-20261016-003`）：用户通报原报告已删除并问 plan 下一步，复核时发现**四处过期事实 + 一处被精简的推理记录**，逐项清偿。① **`编排总览 §4 T2` 状态回填**：序 6 `REF-8.4` 交付状态、序 7 `A+B` 落地状态（原写「已立项」）、序 8 `REF-8.5` 依赖列（原写 `D1~D3`，实已全部满足）—— **已完成项保留原位标 ✅**，不移动分层（「移入 `T0`」属排期重构，需用户意图）。② **行数复测**：`McpToolServiceImpl` **1229 → 1334**（+105），`SubTaskServiceImpl` 与 `RequirementClarifyServiceImpl` 不变；订正 `编排总览 §2.3` / `§6` + `RM计划 §4.1`。③ **★锚点漂移（本轮最重要的一条）**：`RM计划 §4.3` 的切分线虽标「已定稿」，但其 **24 个方法行号锚点是 2026-10-12 快照**，行数 +105 后实测**非均匀漂移**（前段 **+17** / `mergeReportedSkills` **+36** / 后段 **+105**；`checkOut` 805→910、`parseInboxId` 1217→1322）。⇒ 处理：**历史快照不重写**（保留 10-12 锚点原文，同 `D-2026-10-14-5` 手法），在 §4.1 追加复测 + 漂移幅度，并在 §4.3.1 标题标「开工前须重取」—— `RM9` 尚未开工，此为**开工前必读**。④ **差距表 `G-010` 订正**：未达成 **3 → 2 项**（第 2 项「外部技能目录 + 摄入安全闸门」已由 `REF-1.5/1.6` 交付后出列；外部目录另经 `WONTFIX` 改判为安装入口满足）；①③ **保留待单独复核，不连带推断**。⑤ **`ReviewProperties#lockTtlSeconds` 注释恢复**：其 javadoc 在工作树中被压成一行「核验互斥锁 TTL 默认240（秒）」，导致 `D-2026-10-15-4` ① 的 `MED-3` 推理（不变量「锁 TTL 不得短于单次核验 LLM 最坏时长」/「本值语义 = 下限而非精确值」/「为何不用 fail-fast：`application-dev.yml` 是 `skip-worktree` 本地件且 dev 档 600s，fail-fast 会让 fresh clone 起不来」/「双审安全 ≠ 单审安全」）**失去唯一载体** ⇒ 按 `HEAD` 版本**原样恢复**（非新撰）。**★归因声明**：该删除**非本批引入**（本批只做恢复），`git diff` 实测为零功能变化；恢复理由是**不变量依据必须可追溯**（否则后人见 240 无据，可能改回 120 重犯 `MED-3`）。⑥ **沉淀判据**：**「过期状态」与「错误状态」同害** —— 状态表写「已立项」会让读者按旧关系排期（本次），与「已交付写在未达成」（`D-2026-10-15-6` ⑤）是同一缺陷的两面。**★行数变化必须连带检查其绑定的行号锚点** —— 数字漂移肉眼可见、锚点漂移不可见，后者才会让人开工时找错方法（故「行数订正」的完整动作 = 「数字 + 其绑定锚点」）。

- **D-2026-10-16-4｜`REF-8.5` 覆盖面边界裁定 + `G1~G4` 立项（`REF-8.6`~`REF-8.9`）+ 上线清单触发条件改写**（用户，2026-10-16，记录 `LOG-20261016-004`）：用户对三项待拍板逐条答复（覆盖面「需要做」/ 运行时合同「立项吧」/ 上线清单「不设时间表」）后落地。① **`REF-8.5` 边界裁定 = 进循环 4 项 / 留单次 12 项**：**进循环** = `PlannerDecomposeAsyncServiceImpl`（拆解，需查技能 / 依赖 / 历史）· `ReviewExecutionEngine`（子任务核验，须读原始证据）· `TaskFinalReportServiceImpl`（终报告整合，须读子任务产出）· `FinalReportReviewServiceImpl`（终报告审查）；**留单次** = 其余 11 个业务面（基线 1 项已在循环）。**★🟡5 项的裁定以代码实测取证，非沿用清单作者初判**：出纲（素材经 `renderOutlinePrompt` 已在入参、`systemPrompt` 明写「只输出 JSON」）· 澄清流式 2 项（走 `executeStream` + token 去前端打字机 ⇒ 属流式链路）· 需求终稿（`executeSync`，素材已在 prompt 入参）· 搜索缺口评估（首轮结果已在入参，**不需主动查库**；且其「失败即返回 `null` 不补搜」的降级语义在循环下须重定义）⇒ **🟡5 全部留单次**。边界本身即 `REF-8.5` 的验收口径（把「哪些面**不**进」写死）。② **`G1~G4` 立项为载体 `REF-8.6`~`REF-8.9`**，**严格按重判报告的重排优先序** —— `REF-8.6` 取消 / 超时树（第 1 位）· `REF-8.7` `max-tokens` 终态 + sticky（第 2 位，**复用 `REF-8.4` 的 `beforeTerminate` 挂点**）· `REF-8.8` 观测面 usage 互斥拆分（第 3 位）· `REF-8.9` 独立预算闸（第 4 位，**非对齐项 / 自研增强**，登记不启动）。**★编号裁定**：**不得新建 `REF-9`** —— 编号口径表已定格「`REF-` 号段 `x` 取 1~8，**不扩号段**」（该条正是 `M-x` 建号的依据）⇒ 取 **`REF-8.6`~`REF-8.9`（只扩 `y` 段）**。同批新增差距锚点 **`G-023`**：四个运行时合同缺口**独立成行** —— `G-021` 的名称已限定为「工具面 / 手脚 / 技能装配 / 循环覆盖面」，运行时语义不在其中，**两轴并存不互替**；`§1` 矩阵 tripwire **22 → 23**。③ **上线清单触发条件改写**：原文「待环境确认」作废 ⇒ 改为**不设时间表**，**只有所有 `plan/` 计划完成、且 `review/` 中遗留问题全部解决后才考虑启动**（同批改写 `上线核对清单` 首节 + `编排总览 §4 T3` / `§5.1` 项 8 / 尾注）。④ **分层归位**：新立项的 `REF-8.5`~`REF-8.9` 依 `T1` 定义（无阻塞 / 最高价值）**归入 `T1`**（序 6~10）；`T2` 原序 8 上提后仅余 `REF-1.4`（条件触发挂起）。已交付项仍按 `D-2026-10-16-3` 惯例**保留原位不迁移**。⑤ **沉淀判据**：**「登记不启动」也应占有编号** —— 若 `G2` 因「非对齐项」而不给编号，将来重启时会与新建项撞号，且无从追溯「曾评估过」；`DEFERRED` 的语义正是「已评估、暂不启动」，用它承载比「不登记」更安全。

- **D-2026-10-16-5｜`REF-8.5` 落地形态三条裁定 + 两条单口径抽出**（**实施决策**，2026-10-16，记录 `LOG-20261016-005`）：`REF-8.5` 于本日按 `D-2026-10-16-4` 的边界落地。**① 业务面循环入口不复用 `RuntimeTurnExecutor`，与它并列复用 `AgentLoop`** —— 三条硬理由（子任务链契约）：入参守卫要求 `subTaskId` 非空（4 面中 3 面为任务级、无子任务）；出口分类的「空产出护栏 + `VERIFICATION` 围栏 fail-close」是「**子任务交付物必须自证**」的契约，业务面输出为 JSON 草案 / 判定 / 报告 ⇒ 会被误杀为 `FAILED`；其事件骨架写子任务级语义事件。**判据**：宁可「并列复用同一批组件」并接受一处骨架相似，也**不**在最敏感的执行链类里加键于非显然标志位的条件分支 —— 本次的首要工程目标是**执行链零回归**。**② 业务面刻意不写 `agent_event`**（`eventRecorder` / `loopCheckpointListener` 传 `null`）：`EventReconciliationServiceImpl` 的对账判据是「`sub_task_id` 的**末条事件** vs 业务状态」，而子任务核验**正在 `REVIEW` 状态下执行**（期望末条 ∈ {`AGENT_COMPLETED`, `REVIEW_STARTED`}）⇒ 循环若写 `AGENT_STARTED`，**健康的子任务会被判为对账不一致**；且业务面今日本就不产 `agent_event`（可观测面在 `conversation_message` + `task_timeline`）⇒ **不新增第二事实通道**（§10.4 判据 4）。**③ 开关默认开启**：`helloai.execution.business-loop-enabled = true`（scene 白名单默认即裁定四项）—— 裁定既定，默认关闭等于裁定不生效；mock 模式下 `MockChatModel` 不产工具调用 ⇒ 循环一轮即终态、与单次行为等价，默认开启的观测风险可控；**回退通道保留**（置 `false` 即回到裸单次）。**④ 两条单口径抽出（防口径漂移，非重构洁癖）**：`VisibleToolCallbacks`（防「模型可见工具」出现第二处二次过滤——§10.4 判据 2 明文禁止）与 `EnabledToolNames`（并集口径原先**已有三处副本**：装配器 / 执行链 / 新入口）；抽出为**逐字平移 + 委派**，语义零变化。**⑤ 沉淀判据**：**「并列复用」优于「给旧类加分支」的判据 = 契约是否相同** —— 只要新消费方与旧消费方的**入参契约 / 出口契约 / 副作用契约**三者有任一不同，就不该塞进同一个类；此时重复一段骨架的代价，小于让旧类同时承担两套契约的代价（本例中执行链承担着「子任务交付必须自证」这条 fail-close 契约，**最不该被稀释**）。

- **D-2026-10-16-6｜`REF-8.6` 取消 / 超时树落地：装配点收敛「唯一咽喉」+ 超时与取消语义分离**（**实施决策**，2026-10-16，记录 `LOG-20261016-006`）：承 `D-2026-10-16-4` 立项（重判报告排**第 1 位**真缺口），本日按第 2 批落地。① **能力落地**：内部 LLM 执行链**首次具备协作式取消与单工具超时**。`CancellationToken` 三供给源（`none()` 共享常量 / `deadlineAfter(seconds)` 自驱动截止 / `mutable()` 外部主动取消）；检查点 = **每次模型调用前 + 调用后 + 每次工具调用前**；`LoopCancellation`（`record(token, toolTimeoutMillis)` + 共享 `NONE`）为**单一入参载体**。② **★装配点收敛为「唯一咽喉」= `AgentLoopInput` 紧凑构造器**：`cancellation.active()` 时**自动**包 `CancellableChatModel` / `CancellableToolExecutor` ⇒ ① **循环本体（`ChatModelToolLoop`）一行不改**（§10.5 红线：不动循环逻辑、只动入参 / 出口）；② 任何调用方（现有两个 + **未来新增**）**都无法绕过**。判据：可取消性是**横切关注点**，若交给各调用方自行包装，即会重现已登记缺陷模式「**规则存在 ≠ 全路径强制**」（新增调用方漏包 ⇒ 取消静默失效）。`none()` 以**身份比较**判定「无需装饰」⇒ 不限时与改造前**逐字一致**（零包装 / 零开销）。③ **★超时与取消语义分离（刻意不合并）**：**取消** = 整链终止（抛 `AgentCancelledException`，本轮不应继续）；**单工具超时** = 单个动作放弃等待、**返回失败 observation 交模型决策**（换工具 / 换参数 / 收尾），**不整链失败**。若把超时也做成抛异常，会把「工具偶发慢」升级成「整链失败」（= 新增拒绝面）。取消是**独立终态**：执行链判 `FAILED` + `finishReason=CANCELLED`；**业务面不回落单次**（回落会立刻再跑一遍**注定超期**的工作，此处「回落」本身是错的）。④ **★红线：不得用进程级注册表承载外部取消**：`D-2`（MCP 鉴权进程级注册表）的同类 —— 将来接线「按任务 / 子任务取消」时，信号源**须跨实例一致**（Redisson / MQ），**不得**新增进程级 `static Map` 缓存运行中的 token。**已知边界（登记不隐藏）**：在途 HTTP 请求不打断（`ChatModel` 契约无 signal 通道）；不响应中断的工具其底层动作可能仍在后台跑完（结果被丢弃）；`mutable()` 暂**无生产接线**。⑤ **沉淀判据**：**横切关注点（可取消性）的装配点必须收敛到「唯一咽喉」** —— 否则即为「规则存在 ≠ 全路径强制」的又一实例；用**构造器强制装配**比「各调用方各写一遍 + try/catch」更可靠（新增调用方**自动受覆盖**）。**另一条**：**两种语义相近的控制信号（取消 / 超时）默认分开，除非能证明它们该合并** —— 合并的代价是把「局部降级」升级为「整链失败」。

- **D-2026-10-16-7｜`REF-8.7` `max-tokens` 终态落地：sticky 靠「出口定性」而非挂点自觉**（**实施决策**，2026-10-16，记录 `LOG-20261016-007`）：承 `D-2026-10-16-4` 立项（重判报告排**第 2 位**），本日按第 3 批落地。① **新增独立终态 `MAX_TOKENS`**（`success=false`、保留部分正文），与 `STOP` / `MAX_ITERATIONS` / `ERROR` 并列（`AgentLoopResult` 补 `maxTokens(...)` 工厂）。② **★sticky 的落点 = 「出口定性」，非「挂点自觉」**：`truncated` **只置不撤**（任一步触顶即置位），**全部出口**（`STOP` 与 `MAX_ITERATIONS`）在**进入 `beforeTerminate` 挂点之前**就定性为截断草稿 ⇒ 现有 `FinalReportFenceHook`（守卫 `success && STOP`）**自然跳过**，sticky 不依赖任何挂点自觉 —— 未来新增挂点也破坏不了。③ **结束原因取值容错**：`length` / `max_tokens` / `max_tokens_reached` / `max_output_tokens` / `max_completion_tokens`（`trim()` + `toLowerCase(Locale.ROOT)` + `contains`）；**未知取值一律不判截断（fail-open）** —— 降级优于新增拒绝面。④ **沉淀判据**：**sticky 语义的落点应是「出口定性」**（在挂点链之前把终态定死），**而不是**「每个挂点自觉保留」；后者会被下一个新挂点破坏。**已知边界**：只扩「出口定性」，不改循环控制流（§10.5 红线）。

- **D-2026-10-16-8｜`REF-8.8` 观测面 usage 互斥拆分落地：「只扩字段、不改控制流」用「加分量 + 末位装配」**（**实施决策**，2026-10-16，记录 `LOG-20261016-008`）：承 `D-2026-10-16-4` 立项（重判报告排**第 3 位**），本日按第 4 批落地。① **新增 `AgentUsage`**（**互斥五分量**：输入 / 输出 / 缓存读 / 缓存写 / 推理 + `totalTokens` + `latencyMillis`），对齐 DSH `types.ts:175-188` 注释「**Counts are DISJOINT**」。② **★「只扩字段、不改控制流」的落法 = 加 record 分量 + `withUsage` 末位装配**，**而非**重载既有工厂：`AgentLoopResult` 增第 9 分量 `usage`，**既有 8 参 factory 全保留**（末位补 `null`）⇒ **31 处调用点零改动**。③ **★用量在挂点改写之后统一装配** ⇒ 终态与用量**正交**（挂点改写终态时不会把用量丢掉；耗时含挂点耗时）。④ 落 `AGENT_COMPLETED` payload 扩 6 键（`inputTokens` / `outputTokens` / `cacheReadTokens` / `cacheWriteTokens` / `reasoningTokens` / `latencyMs`），**保留 `tokens` 总数、不新增事件类型**；provider 缺字段**降级为 `null` 不报错**；缓存 / 推理属 provider 私有 ⇒ **仅当 `nativeUsage` 是 `Map` 时** best-effort 按键读取。⑤ **沉淀判据**：**正交维度的新增优先用「加分量 + 末位装配」，而不是「改既有工厂签名」** —— 后者会迫使全部调用点同步改（31 处），前者零改动且天然向后兼容。

- **D-2026-10-16-9｜整合报告「自检围栏顶替正文」事故修复：改写型挂点的「原物仍在」出口校验 + 交付物净化 + 审查判定宽松兜底**（**实施决策**，2026-10-16，记录 `LOG-20261016-009`）：E2E 中 `FinalReportFenceHook`（`REF-8.4`）收尾轮把 28K 级整合报告顶替成 793 字符纯 `VERIFICATION` 自检段，全链无一处判失败。① **★根因 = 「说了 ≠ 强制」**：挂点用 `AgentLoopResult.stop(text, …)` **整段替换**草稿，提示词虽写「原样保留草稿正文」，模型可不遵从；原代码只有「空产出沿用草稿」一道守卫。修法 = **出口后置校验**（`preservesDraftBody` 空白无关比对），未保留 ⇒ 回退「**原草稿 + 围栏段**」（保守方向 = 不丢交付物、不新增拒绝面）。② **★二次伤害 = 明确驳回被静默改判通过**：审查方已写 `{"pass":false,"score":2}`，但 `VerdictParser` 因 JSON 瑕疵返 `null` ⇒ 记 `unparseable` ⇒ `convergeToDone` 放过。修法 = **宽松兜底**（正则提取明文 `pass`，取不到才返 null）—— **解析器职责是「读懂判定」，不是「要求模型输出完美 JSON」**。③ **★「行为契约」≠「交付物内容」**：`VERIFICATION` 段是「提交前自审」的行为契约（子任务侧仍消费），但报告模板与报告审查提示词均未要求它 ⇒ 在**交付物出口净化**（`stripTrailingFence`，**条件化**：尾段须自身合格围栏且前文非空才剥），**保留机制**而非一刀切删。④ **完整性用结构量 fail-close**：`bodyWithoutFence(report).isBlank()` ⇒ `BizException`（零阈值）。⑤ **不可解析证据留全**（300 → 4000 字符）。⑥ **沉淀判据**：**凡在出口替换交付物正文的挂点（收尾轮 / 重写轮 / 摘要轮），必须有「原物仍在」的结构性后置断言，判错时回退原物**；**凡「执行链内部协议」泄漏进用户可见交付物，优先在交付物出口剥离，而非拆掉协议**（剥离须条件化，防误截正文中的协议字样）。**★取证法**：`iterations=2 且 toolCalls=0` 只有「收尾轮」能解释（循环内第 2 轮必须由真实工具调用产生）。**已知边界**：`stripTrailingFence` 只剥「结尾处且自身合格」的围栏；`looseVerdict` 仅当严格解析失败时启用。

- **D-2026-10-16-10｜`BLOCKED` 自动重派巡检落地：关闭 `plan §10.7.6` 的唯一缺口（方案 b）**（**实施决策**，2026-10-16，记录 `LOG-20261016-010`）：`plan §10.7.6`（2026-10-13）已裁定「能力鉴别：**发现放内、处置放外**」并点名**唯一缺口 = 自动触发接线**（`dispatchBlockedSubTask` 现存调用方**均为人工入口**），本日落地。① **落方案 (b) 巡检接线**（该节已裁定，复用 `SubTaskPendingOrphanTask` 范式与既有 `dispatchBlockedSubTask` 全链）⇒ **状态机零改动**；**不落 (a) 同事务**——会让 agent 域反向依赖 `task.service`（违 `CODE_STYLE` §6），且该节已裁定 (b) 为主。② **★判定收敛在 core Service、job 只扫描计数**：新增 `SubTaskDispatchService#dispatchBlockedSubTaskAuto` 承载「哪些 BLOCKED 可自动重派」（人工介入标记 / 自报原因码 / 角色推导 / 选人），job 任务只做「扫 ID → 逐条调用 → 汇总」（`CODE_STYLE` §22 定时任务不得直接操作业务 Mapper / §4.3 job 调 core Service）。③ **先选人、后改状态**：选不出候选 ⇒ `no_candidate`、**保持 BLOCKED、不消耗重派预算**（复用 P1-1 口径）。④ **★默认跳过带人工介入标记的子任务，唯一例外显式登记**：全仓 `manualIntervention` 语义统一为「**等人工拍板 ⇒ 自动链不碰**」，**唯一例外 = `executor_reported_blocked` 且开关打开**（该码表示「当前执行者做不到」，平台有确定处置 = 换执行者）；重派成功后**同批清除该标记**（否则前端仍显示「等待人工介入」而与自动重派自相矛盾）；配套独立开关 `blocked-redispatch-self-reported-enabled`，可一键回到「一律等人工」。⑤ **★原因码收敛为唯一事实源**：`executor_reported_blocked` 落 `helloai-common.constant.ManualInterventionReasons`（**叶子域**，`agent/task/job → common` 均 §6 顺向合法）——写入方在 agent 域、判定方在 task 域，各写字面量会**静默失效**。⑥ **时钟取 `update_time` 而非 `context.blockedAt`**：后者是 JSONB 自由文本、无索引，且 BLOCKED 行最后写入即 `block()`。⑦ **沉淀判据**：**「能力存在」≠「有自动触发」** —— `dispatchBlockedSubTask` 早已是完整链、状态机早已有出边，缺的只是**接线**；此类缺口**在任何守卫上都不显形**（编译绿 / 单测绿 / 架构绿），只能靠「该能力有没有**自动**调用方」查出。**另一条**：**异常标记的默认语义与唯一例外都必须显式登记**（本例默认「等人工」、例外「自报阻塞」），否则例外会被后人当 bug 抹掉、或被无限扩大。**已知边界（登记不隐藏）**：巡检**有延迟**（默认 60s 一轮 + 5 分钟阈值，是 (b) 相对 (a) 的固有代价，该节已裁定接受）；`no_candidate` 长期无候选**仍须人工**（`NO_ELIGIBLE_AGENT` 独立告警仍挂在 `G-015` 未达成项内，本批未做）。

- **D-2026-10-16-11｜`Recovery` 消费面方向与落地（T1 序 4，会议裁定）**（**架构方向**，2026-10-16，记录 `LOG-20261016-011`）：`Recovery 消费面` 此前是**空白方向项**（`design/Agent_Event_Stream.md` 只写「Recovery（后续）」、目标架构写「Recovery 未建」），而协作规约 §42 明写「AI 可以实现方案，但不能自行定义 HelloAI 的架构方向」⇒ **方向由用户裁定**，三档候选（中断续跑 / 对账修复 / 明确不做）中选定 **① 中断续跑消费面**。① **定位**：与另三读侧**问的问题不同** —— Timeline / Replay / Audit 答「发生了什么」，Recovery 答「**从哪继续**」。② **形态 = 只读投影 + 兜底注入 + 只读端点**：`AgentEventQueryService#projectRecoveryPoint`（`RecoveryPoint`：未闭合轮次 / 中断点工具 / 该轮已完成工具 / 末事件元数据）；`AgentRuntimeContextAssembler` 恢复上下文改为「`agent_session` **优先**、事件流恢复点**兜底**」，并新增可观测键 `recoverySource`（`session` / `event`）；`GET /api/agent-events/recoveryPointBySubTaskId/{subTaskId}`。③ **为什么需要兜底（实测缺口）**：会话表是**可变表**且口径为「只看最新一行，ACTIVE / COMPLETED 一律零注入」⇒ 进程执行中途被中断时该行可能停在 ACTIVE 或不存在 ⇒ **静默零注入**，接续者不知上次做过什么、可能重复副作用；而事件流是 **append-only 事实**，悬挂 `TOOL_CALL_STARTED` 永不被覆盖。④ **纪律（红线）**：**纯读** —— 不落库、不改业务状态、不反向修正（同 `EventReconciliationService` 的「只报告不修正」口径；守 `Agent_Event_Stream.md` 原则 2「Event 不是业务状态」）；**零阈值判定**（事件层无失败终态事件 ⇒ 「无 `AGENT_COMPLETED`」是唯一未终结信号）；**不编造会话侧字段**（事件来源不注入会话 ID / 中断状态）。⑤ **不选另两档的理由**：(b) 对账修复要把「只报告」升级为「按白名单自动修」，风险是**掩盖真实埋点缺口**（本该修埋点却改状态迁就事件）；(c) 明确不做会被既有 Resume 链的**静默零注入**缺陷否决（该缺陷是实测的，不是推测）。⑥ **已知边界（登记不隐藏）**：仅「Prompt 级续跑注入 + 只读暴露」，**非** ADR-001 §8 的 Step 级续跑（不重跑已成功 Step，仍后置）；前端事件流工作台暂未加 Recovery 视图（后端端点已就绪）。

- **D-2026-10-16-12｜RM9-a：`McpToolServiceImpl` 上帝类拆分首笔（切分线执行 + 锚点重取 + 守卫留门面）**（**实施决策**，2026-10-16，记录 `LOG-20261016-012`）：`RM计划 §4.4` 的一类一笔顺序为 `McpToolServiceImpl → SubTaskServiceImpl → RequirementClarifyServiceImpl`，本笔落地**第一笔（RM9-a）**。① **依已裁定的切分线执行，不自定方向**：三类目标类名与承接方法已于 **2026-10-12 经用户裁定「全部接受」**（`RM计划 §4.3`；属架构动作，协作规约 §42）⇒ 本笔只做**实施**。② **★开工前重取锚点（该计划登记的必做项）**：`§4.3` 的 24 个方法行号锚点是 2026-10-12 快照，而 `McpToolServiceImpl` 已 **1229 → 1334**（+105），实测锚点**非均匀漂移**（前段 +17 / `mergeReportedSkills` +36 / 后段 +105）⇒ 已按当前文件**全部重取**并写入 `§4.3.1` 新增「★锚点重取」块（旧表原文保留不重写）。③ **保留原类名作薄门面**：`McpToolServiceImpl` 仍 `implements McpToolService` 且类名不变（全部外部调用方经**接口**注入：`api/McpController` · `core/mcp/McpMcpServer` · `runtime/tool/InternalLlmExecutorTools`）⇒ **外部零改动、单笔可 revert**；原 1334 行 → **409**，业务实现迁入 **3** 个包内 `@Service`（`McpTaskClaimService` 411 / `McpAgentReportService` 489 / `McpReadModelService` 327）。④ **★守卫留在门面 —— 对 2026-08-23「§7.8 不继续拆分」书面声明的正面回应**：该声明顾虑「拆分后守卫逻辑被迫复制或跨类回调」，本笔令门面成为守卫的**唯一装配点**（13 个入口各自 `assert* + refreshDutyLease*` 后逐方法委托，职责类零守卫 ⇒ 既未复制、也无需跨类回调）；声明顾虑之二「按工具分组只会平移行数」⇒ 本笔**不按工具分组**、按 `§9.1 业务职责` 切分，故不适用。**处理方式 = 保留该声明原文并指明其顾虑如何被消解**（不删史）。⑤ **两处实现层微调（登记供回读）**：`parseInboxId` 旧表列在门面「保留私有守卫」行，但实测**唯一调用方是 `ack`**（非横切）⇒ 随 `ack` 迁入 `McpTaskClaimService`（私有）；`buildSubTaskDetail` 归 `McpReadModelService` 但被 `claimSubTask` 复用 ⇒ 产生 `McpTaskClaimService → McpReadModelService` **单向无环**引用，判据「三职责类互不调用」据此**具体化为「无环」**。⑥ **事务与顺序契约不变**：9 个 `@Transactional(rollbackFor = Exception.class)` 方法的事务注解**留在门面**（守卫与业务仍同事务；职责类不加注解、随调用方传播）；`heartbeat` 的「**续租必须先于读取租约**」顺序由门面在委托前调 `refreshDutyLease(agentId, true)` 保证 ⇒ 响应 `remainingTtlSeconds`「续租后」语义逐字保留（`adaptiveRenew` 实测不读 `last_seen_time`，与 `seen()` 互不依赖）。⑦ **沉淀判据**：**「上帝类拆分」的验收判据不是「类变小了」，而是「用例数不变 + 断言未改 + 组 1/2 基线不动」** —— 纯结构重构必须让「行为等价」有**可机械核对**的证据（本笔：全仓 `@Test` 计数 **2570 = 2570**、`McpToolServiceTest` 54 = 54、全量 `<testcase>` **2569** 例 0 失败、既有 68 例断言**未改**）；**「切分线定稿」≠「锚点可用」** —— 行号锚点是**行数快照的函数**，定稿后任何行数变化都会使其失效，故「按锚点开工」的第一步**永远是重取**；**对历史「不拆声明」的正确处理不是删除，而是「指明其顾虑如何被消解」**（保留理由，避免后人重复论证或被其误导）。

- **D-2026-10-16-13｜RM9-b：`SubTaskServiceImpl` 上帝类拆分第二笔（状态机唯一入口整链搬迁 + 两档验收口径落地）**（**实施决策**，2026-10-16，记录 `LOG-20261016-013`）：`RM计划 §4.4` 的一类一笔顺序第二笔。① **依已裁定切分线执行**（`§4.3.2` 于 2026-10-12 经用户裁定「全部接受」，属架构动作 / 协作规约 §42）⇒ 只做实施，不自定方向；**保留原类名作薄门面**（`SubTaskService` 接口与类名均不变；该接口 `extends IService<SubTask>` ⇒ `getById`/`updateById` 等基类能力仍由门面提供）⇒ **外部注入点零改动、单笔可 revert**。② **开工前重取锚点**（该计划登记的必做项）：旧锚点为 10-12 快照而文件已 1559 → **1592**，实测**非均匀漂移**（前段 +0，`pause` 之后逐段 +28~+33）⇒ 已全部重取并落 `§4.3.2`「★锚点重取」块。③ **★`§4.3.2` 两条追加验收口径的落地**：**(i) 无环** —— 依赖图为 `门面 → 状态机 → 通知 → 查询` + `门面 → 查询` + `门面 → 租约 → 状态机`，**状态机类不依赖查询类**（行读取一律经同域 `SubTaskMapper` 直读，以保住「读现状 + 紧接写入」的同事务 / 同 `@Version` CAS 语义）；**(ii) 状态迁移唯一入口未被稀释** —— `claimAtomic`（SQL 级 `PENDING → ASSIGNED`）与 `reclaimExpiredLeases` 的状态写半段（`IN_PROGRESS → PENDING`）由租约簇移入状态机类（后者以新增 `reclaimToPending(SubTask)` 承接），使**全部** status 写入（含跨类调用与 SQL 级原子写）仍集中在 `SubTaskStateMachineService`；门面自有写操作**无一改 status**（`create` 落初始态属**插入非迁移**）。④ **四处实现层微调显式登记**：映射助手 `toView`/`toEntity` 因**Java 静态成员不可跨类继承**外置为同包 `SubTaskProjectionMapper`（同 `SubTaskSnapshotMapper` 既有模式）；`requiredSkillsOf`/`mergeSkills`/`claimConstraint` 三项**纯只读派生**归查询簇（**若留门面则「门面 ⇄ 通知」成构造器环**，属无环的必要项）；`saveDrafts`（**批量插入，写**）留门面以保持查询簇「真只读」定位；状态写半段下沉状态机类（同上 ③ii）。⑤ **★正面回应 2026-08-23「§7.8 选项二不继续拆分」书面声明（原文在门面 javadoc 中保留、未删史）**：声明顾虑①「事件序与事务边界在类间漂移」⇒ 状态流转链**整体原样搬迁**（`changeStatus` 的「读现状 → 校验 → 写上下文/租约 → 落库 → outbox → 收件箱 → 交接 → 领域事件 → 心跳」顺序与事务边界一字未改）；顾虑②「剩余部分无独立可测职责」⇒ **据实测不成立**（被剥离三簇各自早有独立单测）；顾虑③「按行数机械拆」⇒ 按 §9.1 业务职责切分、4 类互不转发。⑥ **等价性证据**：`src/test/java` 内 `@Test` 计数 **2598 = 2598**（工作树 vs `HEAD`）、7 个定向类逐文件用例数全等（16/11/8/4/4/2/2）、全量 `<testcase>` **2569** 例 0 失败 0 错误（与 RM9-a 基线逐数一致）。⑦ **★沉淀判据**：**「装配口径」不是「断言」** —— 上帝类拆分后测试的**桩点必然随实现下沉**（本例由「spy 门面拦 `getById`/`updateById`/MP 链式 `lambdaQuery()`」改为「直接对同域 Mapper 设桩 / 以 spy 职责类记录类内自调用」），故等价性的可机械核对项应锚定在「**用例数不变 + 断言语义未改 + 组 1/2 基线不动**」，而**不是**「测试文件 diff 为空」；**「写状态的半段」也是状态写入** —— 把 `reclaimExpiredLeases` 整体归入租约类会让「唯一入口」被事实稀释，正确做法是**按「写 status / 不写 status」拆线**而不是按业务场景拆线（场景可跨两类，写入口只能有一个）。

- **D-2026-10-17-1｜RM9-c：`RequirementClarifyServiceImpl` 上帝类拆分第三笔（门面 ⇄ 编排器破环 + `renderPrompt` 按模板拆分）**（**实施决策**，2026-10-17，记录 `LOG-20261017-001`）：`RM计划 §4.4` 的一类一笔序列**最后一笔**（三笔中体量最大，拆分前 **1557 行 / 97 方法**）。① **依已裁定切分线执行**（`§4.3.3`，2026-10-12 用户裁定「全部接受」）⇒ 只做实施、不自定方向；**保留原类名作薄门面**（接口与类名均不变）⇒ 外部注入点零改动、单笔可 revert。② **★破环（实现层微调，显式登记）**：原方法归属会造 **门面 ⇄ 编排器构造环** —— 故 `requireActive` / `isClarifyMode` / `isChatMode` 下移**叶子契约** `ClarifyCommandSearchService`（零反向依赖）；`switchToClarify` 核心下移编排器为 `enterClarify(conversationId, extraMessage)`，门面只留 `switchToChat`。③ **★`renderPrompt` 按模板拆三个对外方法**（`renderClarifyPrompt` / `renderChatPrompt` / `renderFinalizePrompt`），私有 `renderPrompt(templatePath)` 保留 ⇒ **模板路径常量不外泄**。④ **命名口径**：`ClarifyPromptRenderer` → 接口 `ClarifyPromptRenderService` + 实现 `ClarifyPromptRenderServiceImpl`（用户裁定「接口加 `Service` 后缀（严格字面）」）。⑤ **产物**：门面 **1557 → 320** 行 + **5 接口**（`ClarifyCommandSearchService` 64 / `ClarifyRoundOrchestratorService` 69 / `ClarifyPromptRenderService` 38 / `ClarifyFinalizeService` 36 / `ClarifyStreamService` 27）+ **5 实现**（`ClarifyRoundOrchestratorServiceImpl` 438 / `ClarifyPromptRenderServiceImpl` 367 / `ClarifyCommandSearchServiceImpl` 215 / `ClarifyStreamServiceImpl` 210 / `ClarifyFinalizeServiceImpl` 196）。⑥ **等价性证据**：定向 **105 例 0 失败**（与本笔前基线逐数一致）；全量 `<testcase>` **2569** 例 0 失败 0 错误（与 `RM9-a`/`RM9-b` 基线逐数一致）；组 1/2 守卫基线不动。⇒ `RM9` 三笔 **3/3 收官**。
- **D-2026-10-17-2｜RM9 命名合规整改：`service.impl` 命名归位 + 静态投影助手迁出 `{domain}.projection`**（**用户裁定驱动的合规整改**，2026-10-17，记录 `LOG-20261017-002`）：用户复核后指出按 `CODE_STYLE §8.1` / `§17`，`{domain}.service.impl` 内文件**须以 `{接口名}ServiceImpl` 命名**（`service` 包名属接口），且 `*Mapper` 不得由非 MyBatis 类占用。经 3 轮 `AskUserQuestion` 取证 + 裁定执行。① **裁定**（verbatim 要点）：命名口径 = **补接口 + Impl（严格 §8.1）**；范围 = **仅 `service.impl`**（非 `service.impl` 的 12 个 `impl` 包类**不纳入**）；4 个静态投影助手 = **迁出 + 去 `*Mapper`**；落点 = **新建 `{domain}.projection` 包 + `XxxProjection` 后缀**。② **★分水岭 = 「`Impl` 的语义是「实现某接口」」** —— 对**无接口**的类只加后缀会制造「无接口的 `Impl`」这一**新的**不合规形态，正确动作是**补接口**。③ **产物**：`task.service.impl.SubTaskProjectionMapper` → `task.projection.SubTaskProjection`；`SubTaskSnapshotMapper` → `task.projection.SubTaskSnapshotProjection`；`agent.service.impl.AgentProfileSnapshotMapper` → `agent.projection.AgentProfileSnapshotProjection`；`system.service.impl.LlmProviderProfileMapper` → `system.projection.LlmProviderProfileProjection`（后缀带 `Projection` 是因三个简单名与 `port` 契约类**同名**）；`InFlightDbQuotaService` → `agent.service.impl.ConcurrencyQuotaServiceImpl`（接口已存在，纯改名）；`FinalReportPersistService` → **补接口** `task/service/FinalReportPersistService.java` + `task/service/impl/FinalReportPersistServiceImpl.java`（消费方改经接口注入）。④ **收敛判据**：`service.impl` 下非 `*ServiceImpl` 文件 = **0**；`*Mapper` 命名（非 MyBatis）= **0**。⑤ **等价性证据**：全量 `<testcase>` **2569** 例 0 失败 0 错误（与基线逐数一致；原始计数 2591 **含 3 个改名前的陈旧 surefire 报告 22 例**，须剔除后比对）；组 1/2 守卫基线不动。⑥ **附带修复**（RM9-b 未编译遗留，本笔首次编译暴露）：4 个 `SubTask*ServiceImpl` 缺**自身接口 import**；`SubTaskNotificationServiceImpl` 类内**静态自引用**重命名后解析到同名接口；6 个 task 域测试的 `new SubTaskXxxService(...)` + 陈旧 import。

**当前冻结基线**

- **§11.3「Controller 不得暴露 core Entity」**：命中 **19 处 / 8 控制器**（冻结未清偿 —— 改动 API 响应契约须与 `helloai-ui` 联动，单独立项）；校验 `scripts/powershell/verify-code-style-p0-layer.ps1 -StaticOnly`。
- **架构守卫**：组 1 反向 / 组 2 mapper 为 `block`，基线全 0；组 3 前向为 `warn`（`task->agent` 57 / `planner->agent` 34 —— 后者含 `WebSearchToolCallback` 对 `ToolContext` 的声明面依赖，属 §7.2 端口反转的机制性代价，**不刷冻结基线**）。**组 3c `api->core.entity` 当前 52（基线 49）**：该 +3 系 `InstalledSkillPackage` / `PlatformBackup` / `SysUser` 三条 `...core.<domain>.entity.` import 于基线刷新（`1d38508f`）之后落地，**与 `M-6`~`M-8` 无关**（`M-8` 新增 import 全落 `core.*.port`，不匹配该规则）；同属 `warn` ⇒ 不拦截、**不刷冻结基线**。**`M-9` 复核（2026-10-14）**：本批新增 import 全落 `core.system.port` / `core.agent` / `core.shared`，同样不匹配 `...core.<domain>.entity.` 规则 ⇒ 组 3 各计数**不变**，`M-9` 贡献 **0 漂移**（亦无新增告警）。**`BLOCKED` 自动重派批（2026-10-16）**：新增 `job->core.entity` **14 → 15**（`SubTaskBlockedAutoRedispatchTask` 读 `SubTask` 实体，与既有 `SubTaskPendingOrphanTask` **同型**，属 job 层既有惯例、不新增模式）；同期另有 `planner->agent` 33 → 34、`api->core.entity` 49 → 52 两条提示，均于本批之前落地。组 1（反向依赖）/ 组 2（跨域 Mapper）**仍全 0** ⇒ 同属 `warn`，不刷冻结基线。**`RM9-b`（2026-10-16）**：`task->agent` **57 → 60**（+3）—— 拆分把门面的 11 条 agent import **重新分布**到 3 个职责类共 14 条（净 +3），**全为 §6 合法前向、未新增依赖模式**（`SubTaskStateMachineService` / `SubTaskNotificationService` / `SubTaskLeaseService` 三类的 agent 依赖与门面原有一一对应）；`planner->agent` 34、`api->core.entity` 52、`job->core.entity` 15 均经 `git grep … HEAD` 实测为**提交前既有**；组 1/2 **仍全 0** ⇒ 同属 `warn`，不刷冻结基线。

---

# 1. 总体矩阵

| ID | 能力 | 当前状态 | 目标 | 优先级 | 处置 |
|---|---|---|---|---|---|
| G-001 | Agent Event Stream | Run / Turn / Step + Event 契约已具备；Timeline 已并轨 agent_event；Replay / Audit 读侧已具备，并经 REST（四端点）+ UI 事件流工作台暴露 | 统一事件契约和消费体系 | **P0** | DONE · 事件契约与消费体系（Timeline / Replay / Audit）验收成立；**Recovery 消费面已建成**（只读恢复点投影 + 续跑兜底注入 + 只读端点，见 §2 Event Consumers；`D-2026-10-16-11`） |
| G-002 | Executor 迁移 | 实现层唯一化：旧链入口全部删除，`RuntimeTurnExecutor` 为唯一 `AgentRuntime`（`agentRuntimes.get(0)` 直取，无路由无排序）；消费链统一经 `AgentRuntimeContextAssembler` 装配 → 真身执行 → `ExecutionResultHandler` 回写；`runtimeEnabled` / `v2-enabled` / `gray-percent` 已删除——无配置级回退开关 | Runtime 成为唯一执行契约，旧实现退出 | **P0** | DONE · 单轨收敛完成，旧实现已退出（`LOG-20260930-001`）。回退手段 = `git revert`（`D-2026-09-30-3`：硬切为单向门，不保留回退开关，但保留兜底策略与同层恢复） |
| G-003 | AgentRuntime | 八件套已全部落地（Context / Session / Skill / Tool / Loop / Event / Environment / SandboxProvider 契约）；工具侧两个语义位已具备：「**按条件可用**」（`ToolCallbackContributor.toolAvailability()` 返回 false ⇒ 该工具从模型可见列表摘除；不落库、不改管理页、不改 MCP `tools/list`）与「**按上下文动态描述**」（`toolDescription()` 重写 description，生效粒度 = Turn）；`ToolRegistry.resolve(names, ToolContext)` 的返回即**模型可见工具的唯一判据**，工具目录加载失败时 fail-open（未知名字保留）。**不可关闭清单 `CRITICAL_TOOLS = {pullTasks, submitResult, heartbeat}`** 生效于授权面（`isToolEnabled` 短路 + `getEnabledTools` 并集）。当前唯一真实声明者 = `WebSearchToolCallback`（无搜索凭据 ⇒ 摘 `web_search`） | Context + Session + Skill + Tool + Loop + Event + Sandbox | **P0** | DONE · 八件套全部落地，真实 provider tool-calling 循环无边界问题；工具侧语义位已生效（`LOG-20261009-012`）。动态描述的生产消费者在 `REF-4.1`、`search_knowledge` 随 `REF-4`——均不属本行。边界见 `design/Agent_Runtime.md` §ToolRegistry 语义位 |
| G-004 | Skill Capability | **元数据事实源 = classpath `skills/plugins/*.md` 的 YAML frontmatter + 目录扫描**：`SkillPackage` 9 字段由 frontmatter 承载（8 键白名单，`fileName` 由扫描推导），`KNOWN_SPECS` 编译期硬编码已删除，**4 个 eng-*** 全部结构化；目录条目含 corrupt 显式原因（坏文件不静默跳过，经 `GET /api/skills/catalog` 可见，`skill:view`）；**requiredTools→tools 联动**（Legacy/Runtime 双链 mergeTools 并集去重保序，TOOL_RESOLVED 前合并）+ **SKILL_RESOLVED 携带 resolvedVersions**（Replay/前端按字段投影兼容）+ **拆解技能通路**（task.required_skills → 拆解 Prompt 注入，规划/验收与技能规范对齐）+ **契约层统一技能注入**（AgentTask.skills；execute/executeStream + 拆解/审查收口/报告 5 类 LLM 调用挂点同源，skills 空不注入、行为零变化）；前端技能标签改服务端下发（对齐副本已删除）；**技能包安装入口**（`REF-1.6`）——上传 zip → 两段式**摄入安全闸门**（`REF-1.5`）→ 元数据 + 原始正文落 PG、原始 zip 落 MinIO（不写宿主文件系统），与 classpath 内置源**双源**合并进目录；支持多版本共存 / 确认升级 / 回滚激活 / 卸载，安装-激活-卸载全量落审计 | Metadata / Version / Tools / Schema / Dependencies 全量 + **requiredTools→tools 联动** + SKILL_RESOLVED 携带版本 + 真实任务行使（任务级创建/拆解/派发/执行四段贯通）+ 受控存储安装入口（含摄入闸门与审计） | **P1** | PARTIAL · 未达成 1 项：技能回流贡献规范（D5-3）。见 `LOG-20261009-011` / `LOG-20261010-005` |
| G-005 | Sandbox Provider | 已有 Environment / Provider + SandboxProvider 契约（诚实策略，无 ISOLATED）。**当前无可隔离的执行对象**——外部 agent 在它自己终端（平台定位即派单方）；内部 agent（`API_KEY_LLM`）工具面 = 13 个平台 API 工具 + `web_search`，`McpMcpServer` 内 `File` / `Path` / `ProcessBuilder` **0 命中**；平台全库无脚本引擎 / 表达式求值器 | 真正 Provider 化执行环境与隔离策略 | **P1（条件触发）** | DEFERRED · 条件触发，不排期（`D-2026-10-09-6③`）：契约保持现状、不再扩展，不做 Docker 服务。**触发条件（任一成立 ⇒ 重新排期）**：① 平台增加「碰宿主」的工具（自持 shell / 文件写）；② 技能包要被执行；③ 平台自持浏览器。预案见 `plan/HelloAI 借鉴落地实施计划.md` §5（形态已裁定 = 独立沙箱服务） |
| G-006 | Replay / Audit | 写侧 + 对账闭环已具备；Timeline / Replay / Audit 三读侧均经 REST + UI 事件流工作台暴露（含任务 / 子任务维度端点、eventType 过滤、payload 结构化展开、事件字典与子任务时间线同源） | 基于统一 Event 查询/回放；**外部执行轨迹对齐**（外部路径 agent_execution_record 0 行、事件仅完成态，Replay 时外部任务仅「派发→完成」细线） | **P1** | PARTIAL · 未达成：**外部执行轨迹对齐**——外部路径 `agent_execution_record` 为 0 行、事件仅完成态，Replay 时外部任务仅「派发 → 完成」细线。见 `LOG-20260908-009` / `LOG-20260908-010` / `LOG-20260910-001` |
| G-007 | Quality Gate | Reviewer 闭环已具备（执行结果 → Review → PASS / REJECT → REWORK） | Rule + Test + LLM 统一决策 | **P2** | TODO · 目标形态（Rule + Test + LLM 统一决策）未启动；当前为链上增强 |
| G-008 | Agent Fleet Routing | Agent 选择机制已具备（Capability / Health / Load / Policy）；成本维度已接入选人比较链（近 5 次成功执行均值 → 候选内 min-max 反向归一 1~5 档 × `costWeight`，插在 quality 之后、score 之前）；外部执行 tokens 回报协议已就绪（`submitResult.tokenUsage` 可选字段三通道对齐，缺省 null 与旧协议一致）；多外部执行者同台已实证（背靠背竞态唯一赢家 / 技能硬门槛内按分排序 / 并行持有） | Capability + Health + Load + Policy | **P2** | PARTIAL · 未达成：存量 `agent_execution_record` 历史行 token 仍为 null，成本观测待数据积累。见 `LOG-20261003-002` / `LOG-20261003-003` |
| G-009 | Dynamic Workflow | 模板 / 版本 / 实例化 / DAG 已具备 | 动态分支、复杂运行期编排 | **P3** | DEFERRED · 后置；动态分支与复杂运行期编排未启动 |
| G-010 | Planner 能力感知与自适应粒度 | 技能目录注入拆解 Prompt 已具备；子任务级 requiredSkills / constraints 指派已具备（`sub_task.required_skills` + `constraints` 并集装箱）；粒度三档 FINE / STANDARD / COARSE 的 rule-based 决策矩阵（执行者画像 × difficulty 调制）已具备；外部感知下行通道（可选字段，向后兼容）已具备；子任务全文（含验收标准与不确定性申报）经 `claimSubTask` 内联与 `getSubTaskDetail` 一次性下发给外部执行者；inbox 通知摘要携带技能要求与待确认项 | 技能目录注入拆解 Prompt + 子任务级 requiredSkills/constraints 指派（V74 新列，并集装箱）+ 粒度三档 FINE/STANDARD/COARSE 自适应（**rule-based 决策矩阵**：执行者画像 × difficulty 调制，2026-09-09 拍板）+ 外部感知下行通道（可选字段向后兼容）+ 技能回流贡献规范 | **P1** | PARTIAL · 未达成 2 项：① 技能回流贡献规范（D5-3）；② 审查侧 constraints / requiredSkills 注入核验。原第 2 项「外部技能目录 + 摄入安全闸门」已由该组交付后出列（其中「外部目录」一项另经改判，同一「零发版」目标改由安装入口满足）。见 `LOG-20261009-013`；漂移清偿 `LOG-20261016-003` |
| G-011 | 需求包准入与不确定性显式管理 | 结构化需求包已具备（goal / scope / outOfScope / assumptions / openQuestions + 任务级 acceptanceCriteria），会话列 + `task.context` 双写；拆解继承为 `sub_task.uncertainties`（ASSUMPTION / UNCONFIRMED）显式 JSONB 列；执行侧注入（含 constraints 补偿）与审查侧分级语义（假设不成立 ≠ 执行缺陷）已接；终稿四小节校验与「澄清 → 终稿」软断言已具备；审查驳回携带 `missingEvidence` 结构化返工线索（验收标准原文子串 + 缺什么 + 怎么补） | 结构化需求包（goal / scope / outOfScope / assumptions / openQuestions，会话列 + task.context 双写）+ 拆解继承为 sub_task.uncertainties[ASSUMPTION / UNCONFIRMED] 显式 JSONB 列 + 执行侧注入（含 constraints 补偿）+ 审查侧分级语义（假设不成立 ≠ 执行缺陷）+ 外部下行可选字段向后兼容 | **P1** | PARTIAL · 未达成：`gap_kind` 实现路径分类与任务后蒸馏闭环（后置）。见 `LOG-20261009-013` |
| G-012 | 登录鉴权与 RBAC 权限体系（Sa-Token） | 登录会话由 Sa-Token 承载（token 走 X-Admin-Token 头，active-timeout 8h 滑动续期）；授权为「角色-权限码」RBAC（四表 + 内置 SUPER_ADMIN / ADMIN + 存量用户 role 迁移 + 注解鉴权）；管理侧 API（角色 CRUD / 角色-权限绑定 / 用户-角色分配 / 权限码列表）与前端动态菜单按权限码过滤已具备 | 自建登录模块 → Sa-Token 统一会话 + 用户/角色/权限码管理 API + 接口注解鉴权 + 前端动态菜单；后续可按需做菜单树建表与页面化管理 | **P2** | DONE · 底座与闭环（会话 / 授权 / 管理 API / 前端页面 / 存量会话迁移 / 菜单树 DB 化）全部落地，原登记三项后置缺口已全部关闭；深化项已转 BASE 专项（见 `G-013`） |
| G-013 | 基础架构深化（RBAC 底座，参考 JeecgBoot） | 前端动态路由（权限 = 路由可达性，无权限 URL 404）、`v-auth` 按钮级权限、动作级权限码、菜单树携带 component 驱动动态 `addRoute`、可视化菜单 / 权限管理页、角色授权差异更新、路由渲染增强（隐藏菜单 / 页面缓存 / 外链）、部门与岗位组织架构、数据权限规则（受控枚举 ALL / DEPT / DEPT_AND_CHILD / CUSTOM）均已具备 | 对齐 JeecgBoot 标杆：权限 = 路由可达性 + v-auth 按钮级权限 + 动作级权限码 + 菜单树携带 component 动态 addRoute + 可视化菜单树 CRUD + 角色授权差异更新 + 渲染增强/组织架构/数据权限 | **P1** | PARTIAL · 未达成：`active-timeout`（28800s）的**超时过期触发**未实测（仅由代码路径 + 单测覆盖）；`McpAuthFilter` 仍用 `validateAdminToken` 属**有意设计**（其异常不经 `@RestControllerAdvice`，改 `checkLogin` 会把 401 变 500），非缺口。见 `LOG-20260912-002` ~ `LOG-20260913-008` / `LOG-20260929-001` |
| G-014 | 外部 Agent 执行通道缺陷修复（依赖门禁 / 返工出口 / 附件可发现 / 截断元数据 / 在线语义 / 核验边界） | 外部 Agent 通道已与内部分发链同口径：`claimSubTask` / `listAvailable` 复用依赖门禁（reason `dependency_not_ready`）；返工出口 `startSubTask`（含状态机 `REWORK→BLOCKED`）；`getDepsSummary` 区分「前置未就绪」与「采集异常」；`SubTaskDetail` 内联 attachments 与 contributors；核验侧截断输出结构化标注行且 Prompt 声明「不可见内容不得补全」；在线判定以 ACTIVE 值班租约为存活证据；`renewLease` 到期时刻单调钳制；JSON-RPC `tools/list` 补全 `required` | 外部 Agent 通道与内部分发链同口径：依赖/技能约束一致、返工可自救（不依赖人工放行）、前置产出与附件可发现可读、核验结论只基于可见证据、在线判定不误伤在岗 Agent | **P0** | PARTIAL · 未达成：13 工具矩阵与租约在线语义的**脚本级全量断言**（`verify-tool-matrix.ps1` / `verify-mcp-e2e.ps1` / `verify-agenthub-duty-e2e.ps1`）——当前由真实外部 CLI_CLIENT Agent 端到端 A 级实测覆盖主链。见 `LOG-20260924-001` / `LOG-20260930-002` |
| G-015 | 外部 Agent 心跳/重派止血（B1：误判离线 → 在飞任务被重派打满死信） | 写侧值班租约守卫已具备（CAS 标 OFFLINE 前判 `isOnDuty`，持 ACTIVE 租约即跳过离线处置，与读侧「租约 ACTIVE → IDLE」同口径）；在飞子任务宽限（`inFlightGraceMinutes`，窗口取 `max(grace, offlineMinutes)`）；只读 / 登记类工具经 `refreshDutyLease` 顺带刷 `last_seen_time`；重派退避档绑独立列 `sub_task.last_attempt_time`（`V102`，仅 `incrementAttemptTotal` 原子写入，闸门只读该列）；离线在飞 IN_PROGRESS 置 PAUSED 保留归属（不消耗重派预算）；`redispatchDeadLetter` 清理残留 context 键；REST 双通道（直通 + JSON-RPC）透传 skills（兼容数组与 CSV）；`agent_duty_lease.ttl_minutes`（`V94`）持久化签发窗口；附件上传按「无归属 409 / 归属他人 403 / 子任务不存在 404」语义化报错；改派链路的「无替代 Agent」分支返回 **409 + 可读原因**并落 `sub_task_dispatch_no_alternative` 时间线（`REF-5.1`），前端字典同批登记 | 外部 Agent 埋头执行长任务（数分钟不触网）不被 5min 心跳窗口误判离线；判离线的读/写两视图口径统一 | **P0** | PARTIAL · 未达成：「无候选空烧预算 + `NO_ELIGIBLE_AGENT` 独立告警」未做。观察点：在飞续约仍用 `maxTtlMinutes(240)` 保活（仅体现在 `expire_time`，不污染 `ttl_minutes`）；附件取回口径在 SKILL 与 executor-duty 手册已对齐（`tools/list` 的 `attachmentId` ↔ REST 附件清单的 `id`，并在手册中写明两处来源与 `getSubTaskDetail` 不适用于前置）。见 `LOG-20260926-001` / `LOG-20260926-002` / `LOG-20261003-001` / `LOG-20261005-001` / `LOG-20261010-009` / `LOG-20261010-011` |
| G-016 | 报告整合质量 | 报告读取与子任务链同口径（物化附件优先 → `ExecutionRecord` SUMMARY / DELIVERABLES 注入 → output 兜底）；附件限额与族判定单源（`AttachmentContentPolicy`，shared）；Markdown 块级截断 + 超限结构化标注；大纲先行两段式（出纲失败降级单次调用）；核验 / 返工闭环（自审自过硬守卫 `review_skipped`）；报告版本单槽列 `final_report_prev(_agent_id/_time)`，rollback 端点 current ↔ prev 整体互换；审查异步化（REVIEWING 态 + 专用池 + `reportTime` 三重陈旧守卫 + 六出口收敛 DONE）；审查链三级容错（L1 `AFTER_COMMIT` 内存事件 / L2 Outbox 幂等消费 / L3 孤儿巡检收敛 DONE）；轮次无状态（由审查服务显式传 attempt，不落库） | 报告按主题归并有覆盖追溯，与执行链同口径读事实源；生成后自动核验、驳回可返工；自审自过不静默放行；审查不阻塞前端请求；历史报告可恢复上一版 | **P1** | PARTIAL · 未达成：前端轮询两项可选精修（keep-alive 退出未停表 / 无失败退避）。见 `LOG-20260928-002` / `LOG-20260928-003` / `LOG-20260929-005` / `LOG-20260930-007` / `LOG-20260930-008` |
| G-017 | 附件存储一致性（产物对象 ↔ attachment 记录对账） | `ArtifactStorage` 契约已具备（Local / Minio 双实现 + Composite 按 type / URL 前缀路由；`exists` fail-open / `listObjects` fail-safe / `removeObject` fail-close）；`AttachmentServiceImpl.register` 前置校验（storageUrl 必填 + `validateAddress` + `exists` 校验，不存在 400 拒绝）；对账巡检 `ArtifactStorageReconcileTask`（6h + ShedLock，attachment 全量含逻辑删除 ↔ 桶内对象双向比对，悬空 / 孤儿 / 字节不符三态）；孤儿清理默认关闭 + 三重保险（开关 + 24h 时间窗 + 单轮上限 200）；MCP `uploadArtifact` 描述重写（storageUrl 格式 + bucket 白名单 + 错误示例） | DB 与对象存储两侧一致；悬空/孤儿可发现可报告；不再产生僵尸附件；孤儿清理受控有保险 | **P1** | PARTIAL · 未达成：① 历史 106 条悬空附件不自动修复（对账可见可报告）；② 部署侧安全动作待执行（改 MinIO 默认凭据 + 端口收安全组白名单）。见 `LOG-20260928-001` |
| G-018 | 备份 / 恢复（平台数据可靠性） | 备份/恢复链已具备：备份编排（`pg_dump -Fc` 全库 + MinIO 对象清单递归枚举 + manifest 前置 peek + Redisson 单飞锁 + 仅淘汰自动备份）+ `platform_backup` 记录表（`V105`）+ 恢复侧三门（跨引擎 / schema 高过运行时 / 在线）+ 管理端 preflight、restore 端点；备份落独立 bucket `helloai-backups`（不参与产物对账）；停机恢复流程手册成文（`doc/manual/platform-backup-restore/runbook.md`：能力边界 / 备份判据 / 恢复三路径与三门 / 停机清单）；生产镜像内置 `postgresql-client-16`，备份在服务器侧可用 | `pg_dump -Fc` 全库 + MinIO 对象清单 + manifest 前置 peek（不解档即可判内容）+ 分布式单飞锁 + 自动/手动备份分离的保留策略 + 停机恢复流程文档化（诚实边界：运行中备份不保证多文件同一瞬间） | **P1** | DONE · 全链成立：备份编排 + 恢复三门 + `platform_backup` 台账 + 停机恢复流程手册 + 生产侧客户端前置。落地硬约束：① 恢复侧安全闸门（跨引擎 / schema 高过运行时 / 在线）；② 「`listObjects` 返回空」显式失败，不产出缺对象的「完整备份」；③ 复用既有 `pg_restore --no-owner --no-privileges` 写法。验证见 `LOG-20261010-007` / `LOG-20261010-008` |
| G-019 | RAG 知识库（检索增强） | 存储/检索已具备：`kb_chunk`（`V107`，`vector(1024)` + HNSW-cosine）+ 自建 `JdbcTemplate` 余弦检索 + `search_knowledge` 工具（无 KB 即摘）+ `char_budget` 注入预算 + 引用溯源 marker（`[kb:source#idx]` + 前端可点开卡片）+ 管理端 `/api/kb/*`；真实 dashscope 嵌入 + 判别性召回验证通过 | pgvector 存储与检索（**PG 单后端，不引入侧库**）+「无 KB 即摘工具」的条件可用语义位 + 注入预算（char_budget 契约）+ 引用溯源 marker（前端渲染卡片、喂模型前剥离） | **P2** | DONE · `REF-4` 全组收官（服务端单一来源 + 存储/检索 + 引用溯源 + 条件可用语义位）。交付：`kb_chunk` 存储/检索（PG 单后端）· `char_budget` 注入预算 · 无 KB 即摘工具 · 引用溯源 marker（前端可点开来源卡片）。前置全闭合：pgvector 镜像与扩展 · 通义千问 LLM 有效 · 嵌入选型 `ADR-002` 定稿 · `search_knowledge` 经 `ToolCallbackContributor` 注册并登记技能包。**生产启用**（知识库内容入口与开关）属运维决策（新能力默认关闭，`REF-6.13`）。验证见 `LOG-20261011-001`~`005` |
| G-020 | 外部 Agent 工作详情快照（子任务执行过程的取证能力） | **完全空白**：平台当前只能审计到**订单层面**（认领 / 开工 / 心跳 / 交单 / 产物 / 阻塞 / 依赖读取，经 `agent_event` + `task_timeline` + 产物对账）；**子任务内部执行过程是黑盒**（这是刻意的解耦，但带来返工争议 / 质量复盘 / 责任界定的取证盲区） | 外部 Agent **事后、经审批、可选**地提交一份全任务工作详情快照 → 平台解析 → **原文落 MinIO** + **时间线插入结构化审计事件（闭合 schema + `snapshotRef`）** | **P2（可后置）** | TODO · 新增立项（`D-2026-10-09-6⑤`），任务锚点 `REF-7`。**动手前三项设计约束必须先定**（见 `plan/HelloAI 借鉴落地实施计划.md` §8.2）：① 与 `REF-6.2`「审计闭合 schema」的张力 ⇒ 必须双面（与 `REF-5.2b` 双视图同模式）；② 审批方是谁（倾向复用人工介入通道，不建第二套审批）；③ 落时间线的粒度。新增工具须走既有 `McpMcpServer` 守卫且**默认关闭**（`REF-6.13`） |
| G-021 | 内部 LLM 能力面（工具面 / 手脚 / 技能装配 / 循环覆盖面） | `API_KEY_LLM` 工具面已解封（进程内通道，与 MCP 会话解耦），可见工具集含读详情 / 读依赖 / 读状态 / 读附件（**★仅 `new String(content, UTF_8)` 解码——二进制结构化文件（xlsx / docx / pdf）读不出列，见 `LOG-20261016-001` 前提订正**）/ 交付登记 / 技能目录 / 技能惰性加载 / 自报阻塞；技能装配为「全量目录 + 惰性加载」（正文不预注入）；systemPrompt 非空（角色 + persona 槽位 + 工具指引 + 输出契约 + 验证证据围栏）；循环预算按接入类型分档；上下文压缩（剪枝 + 摘要两道）已挂每轮模型调用前。**接通面已按裁定收窄**：全仓在循环面 **1 → 5 处**（基线 `RuntimeTurnExecutor` + 已裁定进循环的四个业务面：任务拆解 / 子任务核验 / 终报告整合 / 终报告审查），其余 11 个业务面按 `plan §10.6` **明确留单次**（边界即该表尾注；澄清两走流式链路，出纲与缺口评估的素材均已在入参）；插点为单咽喉 `PlatformAgentExecutionServiceImpl.execute`，带独立开关 `helloai.execution.business-loop-enabled` 与 scene 白名单，循环异常 / `ERROR` 终态自动回落单次 | 内部 LLM 从「单轮文本生成器」进化为**有工具、有手脚、能按需装配技能**的 agent；内核唯一输出契约 = 「模型的可见集」（L1 可见工具集 / L2 可见技能目录 / L3 可见上下文），并让多数 LLM 面进入循环 | **P1** | DONE · **`REF-8.1/8.2/8.3` 三层可见面已交付**，并已完成五个子项：`REF-8.1.1` 自报阻塞通道（带知情拒绝闸门与人工介入标记）、`REF-8.1.2` persona 两槽位（部署默认 + 按 Agent 遮蔽）、`REF-8.2.1` 返工上下文替换语义、`REF-8.2.2` 验证证据围栏、`REF-8.2.3` 上下文压缩（剪枝 + 摘要两道，含净收益守卫与摘要额度）。`REF-8.1` 安全口径已收口：`internal_*` 入口加执行链绑定闸门且移出对外 MCP `tools/list`（`InternalToolCallbacks` 非 provider 承载，`D-2026-10-12-5`）。**收敛判据③「能力可验证」已成立**：内部 LLM 在真实模型循环中自调 `internal_*` 工具、附件正文经工具输出回流进交付物，任务链路整体收敛（全部子任务完成）。**原两项未达成项均已结清**：① `REF-8.5` 覆盖面 —— 已交付（`LOG-20261016-005`，形态见 `plan §10.9`）；② ~~`LOG-20261011-007` 的 A（能力闸）+ B（收敛熔断，阈值 `N=3`）—— 已立项、待实施~~ —— **★已实施（`D-2026-10-16-1`，记录 `LOG-20261016-001`）**：**A** 落 `ResilientDispatcher.isAttachmentReadMismatch`（信号源 `SubTaskQueryPort#requiresOriginalAttachment` + `SubTaskDispatchService.isAttachmentReadDense` 两侧词表共现；能力侧 `AgentCapability#hasStructuredAttachmentReadCapability`；覆盖首选 / fallback / N11 三个派发入口；复用既有 timeline 事件与人工介入标记，仅以 payload `reason` 区分）；**B** 落评审层 `RepeatedFailureGate`（basis `self_reported_stall`）。**★B 判据口径订正**：`D-2026-10-12-3` 原文为「连续 N 次**相同驳回原因**」，但「驳回原因同因」只能靠文本相似度识别，而该路已被 `review/` 架构V2进度与质量审计报告 §15.3 用真实语料**实测证伪**（全文 Jaccard 0.2537/0.1927；首条 `[defect]` 标签 Jaccard 0.1203/0.1620 **更低**；根因 = 三轮用三套不同编号指代同一批缺失项）⇒ 改用**句法级信号**「执行者轮报 `STATUS` 连续 N 轮均为阻塞或部分完成」且评分未提升（`LOG-20261012-008` 决策 3 的 `status=blocked` 半条）。**`REF-8.4` 拦截链本身已交付**（四件套；形态 = 循环自有 `LoopHook` 三档，**非** Advisors）。任务锚点 `REF-8`；现状依据：`AgentMcpServerServiceImpl` · `RuntimeTurnExecutor` · `AgentRuntimeContextAssembler` · `AgentLoopInput` · `ContextCompactor`。**★`REF-8.1.1` 自报阻塞的「处置端」已补（`D-2026-10-16-10`，记录 `LOG-20261016-010`）**：`dispatchBlockedSubTask` 此前**只有人工入口**（=`plan §10.7.6` 登记的**唯一缺口**），已按该节裁定落**方案 (b) 巡检接线**（`SubTaskBlockedAutoRedispatchTask`，复用既有全链、**状态机零改动**）⇒ 「重启撞上在途任务」（`agent_runtime_cancelled`）与「执行者自报阻塞」（`executor_reported_blocked`）两种**阻塞落点**均可在阈值后**自动重派** |
| G-022 | 选人技能门（命名空间一致性） | 平台技能库 4 个标签均为 `eng-*` 前缀（细粒度技能包）；`agent.skills` 为模型能力标签（如 `thinking` / `code-review`，粗粒度谓词派生）——两套命名空间之间**无引用边**，AND 匹配恒失败 ⇒ `NoCandidateAgentException` ⇒ 时间线记 `sub_task_no_candidate` 并按退避重试（重试同样无解）。**实测**：`sub_task.required_skills` 34/34 行全为 `eng-*`，存活 9 个 Agent 中仅 3 个带 `eng-*`（全靠 `checkIn` 自报塞入）；`SkillNormalizer` 同义词表只覆盖 8 项标准词 ⇒ `eng-*` 零映射。**已补引用边**（见状态列）；存量数据待重注册触发回填 | 技能门两端同口径：声明侧声明即有人具备（或不存在时阻断 / 告警而非等待），能力侧 Agent 能按平台技能标签声明能力 | **P1** | PARTIAL · 引用边已补（`D-2026-10-15-1`）：**不统一命名空间**（会迫使技能包点名模型，违 DSH「技能不点名模型 ≠ 技能不提需求」判据），改为给技能包补 `requiredBaseCapabilities`（frontmatter 8→9 键 + 迁移 `V113`），判定式 `requires ⊆ provides` 落在 `SkillCapabilityRequirements.satisfiesPackage`（唯一判据）；注册链由 `AgentSkillDeriver` 第 ⑤ 步把「本模型可承接的技能包」并入 `agent.skills`，`checkIn` 自报入口同款校验（`QA-M9-03` 收口，限定 `API_KEY_LLM`）。4 个内置包中仅 `eng-web-research` 声明 `webSearch`，其余 3 个为作业规范**有意留空**（声明谓词只会新增拒绝面）。守卫增 S4/S5。**未达成**：存量 `agent.skills` 未回填（须重注册触发，刻意不写迁移以免造第二事实源）· `eng-*` 装进内部 LLM 工具授权集（缺口在 `requiredTools`→`ToolRegistry`，不在本引用边）。拆解侧承接者校验已具备（`LOG-20261015-006`）：草案声明技能后按 `SkillNormalizer.matches` 判定在岗 Agent 是否可承接，无则落 `task_plan_skill_unmatched`（告警不阻断）。详见 §7.2 `G-022` 章节 |
| G-023 | 内部 LLM 运行时合同（停止信号 / 预算 / 取消 / 观测） | 四缺口已以参照实现（DSH）源码为基准重判核定，其中**三项已接线**：**取消 / 超时树**（`AgentLoopInput` 携可取消上下文，装配点收敛为紧凑构造器「唯一咽喉」，单工具超时降级为失败 observation 交模型决策、不整链失败）；**`max-tokens` 截断独立终态 + sticky**（`ChatModelToolLoop` 出口分类在 `beforeTerminate` 挂点**之前**定性，全部出口不被下调，未知结束原因 fail-open）；**观测面 usage 互斥拆分**（`AgentUsage` 五分量 + latency，落 `AGENT_COMPLETED` payload）；**独立预算闸**双方皆无（属**自研增强维度**，参照实现同样不做） | 内部 LLM 循环的运行时合同与参照实现对齐：可取消 / 可超时、截断不静默成功、用量可拆解。**本行只治运行时语义，不扩循环覆盖面**（覆盖面边界另见 `G-021` 的 `REF-8.5`） | **P1** | DONE · **四项已结清**：`REF-8.6` 取消 / 超时树、`REF-8.7` `max-tokens` 终态 + sticky、`REF-8.8` usage 互斥拆分 + latency 均已交付；`REF-8.9` 独立预算闸**登记不启动**（**非对齐项 / 自研增强**）。依据见重判报告 §三；形态见 `plan §10.10`~§10.12；登记 `LOG-20261016-004` / `LOG-20261016-006` |

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
> **「五边界」中对 helloai 当前唯一有真实意义的是网络边界**——而它的正确实现是**出站 SSRF 守卫**（`REF-5.4`，平台自己发起的外联），**不是容器网络隔离**。**该项已交付**（`LOG-20261010-010`）：两层防线 —— **URL 层**（协议白名单默认只放 https + IP 字面量直判）与**地址层**（`OutboundUrlGuard.SafeDns` 解析即判、并以同一结果建连即 pinning）；跳转逐跳重跑，故旧实现 `Redirect.NORMAL` 的重定向式 SSRF 一并关闭。**实测结论**：OkHttp 对 **IP 字面量不走 `Dns`**（`http://169.254.169.254/` 这类最高危形态只在 URL 层拦得住），这是两层必须同时存在的直接依据。
>
> **触发条件（任一成立 ⇒ 重新进入排期，届时启动专项并出 ADR）**：① 平台增加碰宿主的工具；② 技能包要被执行；③ 平台自持浏览器。**预案**（spec 声明化 / 探针 / scope 生命周期 / 形态 = 独立沙箱服务）见 `plan/HelloAI 借鉴落地实施计划.md` §5。

### Event Consumers

顺序：

```text
Timeline / Replay
→ Audit
→ Recovery
```

> **✅ Recovery 已建（2026-10-16，`D-2026-10-16-11`，记录 `LOG-20261016-011`）**：四读侧齐备。Recovery = 回答「**从哪继续**」（Timeline / Replay / Audit 回答「发生了什么」）：① **只读投影** `AgentEventQueryService#projectRecoveryPoint` → `RecoveryPoint`（未闭合轮次 / 中断点工具 / 该轮已完成工具 / 末事件元数据），零阈值结构量判定（事件层不定义失败终态事件 ⇒ 「无 `AGENT_COMPLETED`」是唯一未终结信号）；② **只读端点** `GET /api/agent-events/recoveryPointBySubTaskId/{subTaskId}`；③ **续跑注入兜底**：`AgentRuntimeContextAssembler` 的恢复上下文改为「`agent_session` 优先、**事件流恢复点兜底**」（会话表在「进程中途被中断 ⇒ 该行停在 ACTIVE / 不存在」时静默零注入，事件流的悬挂 `TOOL_CALL_STARTED` 永不被覆盖）。设计边界见 `design/Agent_Event_Stream.md`「Recovery 消费面」。
>
> **边界（登记不隐藏）**：本项**只读**——不落库、不改业务状态、不反向修正（同 `EventReconciliationService` 的「只报告不修正」口径）；仅提供「Prompt 级续跑注入 + 只读暴露」，**非** ADR-001 §8 的 Step 级续跑（不重跑已成功 Step，仍后置）。前端事件流工作台暂未加 Recovery 视图（后端端点已就绪）。

> **Fork 处置（`D-2026-10-09-5`）**：**WONTFIX**——「触发入口 / 原 Run 冻结 / 驱动新 Run 执行」三项**不立项**。`AgentEventForkService` 作为**未接线的内部能力保留**（快照复制能力，**零生产调用方**）。依据：①「驱动执行」自始即为后置项；② 其实际用法（分叉重跑 / 路径对比）已被 **Return**（REWORK → 驳回 → 改派 → 重开工，闭环完整）与 **Replay 工作台**覆盖；③ 驱动执行须改 Run 标识模型（ADR-001）与 execution command 载荷（协作规约 §30/§31），成本与收益不匹配。**Recovery 保持在本次序内推进，优先级不因本节变化**。

### Planner 能力感知（G-010）

- 设计（唯一事实源）：`doc/design/Planner_Capability_Awareness.md` §7（决策 1~7 全部拍板）
- 当前状态与未达成项：见 §1 矩阵 `G-010` 行
- 实现过程与验证证据：`LOG-20260909-001` ~ `LOG-20260909-012`、`LOG-20260910-001`、`LOG-20261009-011`、`LOG-20261009-013`

### 选人技能门（G-022，2026-10-13 登记 · 2026-10-15 补引用边 · 2026-10-15 补拆解侧前置校验）

- 当前状态与未达成项：见 §1 矩阵 `G-022` 行
- 问题与判定：**技能门两端命名空间不一致**——声明侧用平台技能标签（`eng-*`，4 个技能包前缀），能力侧 Agent 声明的是模型能力标签（`thinking` / `code-review`），全平台**无任何 Agent 声明 `eng-*`** ⇒ 选人 AND 匹配恒失败 ⇒ 被声明了 `eng-*` 的子任务**永久 PENDING**（静默等待：仅时间线与 `context.noCandidate` 可查，操作面不可见）
- 实现过程与验证证据：`LOG-20261013-001`（证据链：`sub_task.required_skills` / `agent.skills` / 全平台 `eng-*` 计数为 0 / 时间线 `sub_task_no_candidate` + 退避字段）；修法三选见 §1 矩阵行

#### 2026-10-15 实施：补「细粒度技能 → 粗粒度模型能力」引用边（修法①的精确化）

**实测起点（本地 PG）**：`sub_task.required_skills` **34/34 行全为 `eng-*`**；`agent` 存活 9 个，其中带 `eng-*` 的仅 **3 个**（全部靠 `checkIn` 自报塞入），4 个 `API_KEY_LLM` 中仅 1 个带 ⇒ `SkillNormalizer` 同义词表只覆盖 8 项标准词、`eng-*` **零映射** ⇒ AND 匹配必失败。

**★关键判定：修法不是「统一词表」**。统一词表会迫使技能包**点名模型**，而 DSH 判据是「**技能不点名模型** ≠ **技能不提需求**」——两者常被混同。故补的是**引用边**：

- **承载**：`SkillPackage` 增 `requiredBaseCapabilities`（frontmatter 白名单 **8→9 键**，属契约变更，四处联动）；`skill_package` 增同名列（迁移 `V113`）。
- **判定式**：技能可派 ⟺ `requires ⊆ provides`，实现在 `SkillCapabilityRequirements.satisfiesPackage`（**唯一判据**，与标准技能的 `satisfies` 同构但**不可合并为一张表**——两个关系正交）。
- **填充口径（诚实边界）**：4 个内置包中**只有 `eng-web-research` 声明 `webSearch`**（依据：其 `requiredTools` 含 `web_search`，与 `web-search ⟸ webSearch` 同一事实源）；其余 3 个（`eng-code-review` / `eng-doc-standard` / `eng-verification`）**有意留空**，理由逐包写入 frontmatter 注释——它们是**作业规范**（与 `M-9` 中 6 项恒真技能同源），声明谓词只会凭空缩小可派面（`reasoning` 会拒掉 **48** 个模型、`toolCalling` 会拒掉 **8** 个），属**新增拒绝面**而非消除越界。`eng-verification` 的真实缺口在**工具面**（内部 LLM 拿不到执行工具），**不在谓词面**。
- **消费方①（注册链）**：`AgentSkillDeriver.deriveWithCapabilities` 新增第 ⑤ 步——把「本模型谓词可承接的技能包标签」并入 `agent.skills`，于是选人处 AND 匹配**有了真实的边**，且边由模型事实驱动、随模型目录变化自动重算。兼容重载（不传包面）**逐字保持原行为**。
- **消费方②（`checkIn` 自报）**：`QA-M9-03` 收口——自报技能此前**完全绕过**能力校验，任一持牌 Agent 可把模型不支持的标签写进 `agent.skills`（实测即 `inner-deepseek-flash-excutor` 被塞 `web-search` + `eng-web-research`，而其模型 `deepseek-v4-flash` 的 `webSearch=false`）。现走**同款判据**，越界项**丢弃并告警**（不抛错：抛错会打断打卡主链）；作用域**限定 `API_KEY_LLM`**（外部 Agent 的模型由其自身掌控，平台无从判其能力）。
- **守卫**：`verify-skill-capability-parity.sh` 增 **S4**（frontmatter 谓词 ∈ 已登记词表；**拼错的谓词会放宽而非收紧匹配**，属放宽型失效方向，必须 fail-closed）+ **S5**（解析器白名单与 record 分量同步，防「契约半改」），各配 `--inject-bad-predicate` / `--inject-unknown-key` 两个反证开关。
- **仍未达成**：① **存量 `agent.skills` 未回填**——须逐个重注册/编辑才能触发挂载（本批**刻意不写迁移**：挂载逻辑依赖技能包目录与模型谓词双读，SQL 侧复现会造第二个事实源）；② ~~拆解侧**无前置校验**（产出 `eng-*` 时不校验「是否存在可承接的 Agent」，静默等待仍在）~~ **已清偿（2026-10-15，`LOG-20261015-006`）**：草案声明技能后校验在岗 Agent 是否可承接（走 `SkillNormalizer.matches`，与选人门**同一匹配器**），无则落时间线 `task_plan_skill_unmatched`（**告警而非阻断**）；③ `eng-*` 装进内部 LLM 工具面的授权集（真实缺口在 `requiredTools` → `ToolRegistry` 的接线，不在本引用边）。

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

- 专项计划（**已执行完成，2026-10-12 迁入归档**）：`doc/archive/implemented/HelloAI 基础架构调整实施计划.md`（批次明细 / 验收 / 遗留）
- 残留两项（**不在该专项范围**）：`active-timeout` 超时**过期触发**未实测；`McpAuthFilter` 沿用 `validateAdminToken` 属**有意设计**
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
恢复：manifest peek 校验 ──► 恢复侧安全闸门 ──► pg_restore（灌入目标库，验证口径 = 空库）──► 停机恢复流程（手册）
```

不变量与诚实边界：

```text
运行中备份 ≠ 多文件同一瞬间一致（须在文档中明示，不得宣称「一致快照」）
对象【本体】不在备份内（只有 key + size 清单）⇒ 恢复不回填对象内容，亦不列为目标
listObjects 为空 / 失败 ⇒ 备份显式失败，不得产出「完整备份」
手动备份永不被自动保留策略淘汰（仅自动备份参与 prune）
恢复侧三门：跨引擎拒绝 / schema 版本高过运行时拒绝 / 在线恢复拒绝
恢复端点不带 --clean（冻结硬约束），故对已有同名对象的库直接执行会以非 0 退出码结束
```

**状态**：`REF-2.3` / `REF-2.3b` / `REF-2.4` **均已交付** —— 备份编排（`DatabaseBackupService`）+
恢复侧三门（`RestoreGate`）+ `platform_backup`（迁移 `V105`）+ 停机恢复流程手册
（`doc/manual/platform-backup-restore/runbook.md`）；同批补上生产镜像的 `postgresql-client-16`
（`Dockerfile` app 阶段，计划 `D-2026-10-10-2①` 承诺而此前未落地），使备份在服务器侧真正可用。

验证载体：单元测试 `RestoreGateTest` / `DbSchemaVersionReaderTest` / `PgDumpRunnerTest`；
端到端演练 `scripts/powershell/verify-backup-restore.ps1`（备份 → preflight → 真恢复进一次性探针库 → 逐表对账 → 清理）；
运维手册 `doc/manual/platform-backup-restore/runbook.md`（含停机恢复清单与诚实边界）。

**不变量**

```text
门② 的判据来源 flywayMaxVersion 必须取 flyway_schema_history.version 的「数值最大」
（该列为 VARCHAR，SQL max() 走字典序会取小，进而放行本该拒绝的更新备份）
—— 由 DbSchemaVersionReader 保证（按前导整数求最大，与 MigrationVersionResolver 同口径）
恢复演练只对一次性探针库，绝不指向配置库；app 一键恢复端点按停机操作不自动跑
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
① 基础设施：PG 镜像换 pgvector 版 —— ✅ 已落地（`pgvector/pgvector:0.8.0-pg16`，迁移 `V106` 已 `CREATE EXTENSION vector`；本地真库验证 `vector|0.8.0`，三份 compose + Testcontainers 同步）
①b 通义千问 LLM 有效性：✅ 已验证（`qwen-plus` chat 探针 200）
② 架构决策：嵌入模型供应商 / 维度 / 密钥管理（credential_vault）落 design/adr/ —— ✅ 已定稿（`ADR-002`）
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

**红线**：新增工具须走既有 `McpMcpServer` + `assertAgentActive` / `assertToolEnabled` / `refreshDutyLease` 守卫（不建平行通道），且**默认关闭**（`REF-6.13`：新能力默认关闭、通过注入启用）。★**射程澄清（`D-2026-10-12-5`，2026-10-12）**：本红线射程 = 「**对外暴露的新工具**」；**内部进程内通道**（如 `REF-8.1` 的 `internal_*`）为**显式例外**——以「**执行链绑定闸门**」（`ToolInvocationContext`）替代会话守卫，且**不进对外 MCP `tools/list`**。判据：「**绕过外部守卫**」合理 ≠ 「**暴露到外部面**」合理。

### 内部 LLM 能力面（G-021，`REF-8`，2026-10-12 立项）

**问题一句话**：平台的多 Agent 编排（`planner > review > task > agent`）已是成熟 P&E 骨架，但**内部 LLM 这一层 = 「循环骨架 + 空工具面 + 无手脚 + 只覆盖 1/16 的 LLM 面」** ⇒ 退化为单轮文本生成器。这与 `G-003`（AgentRuntime）不冲突：`G-003` 验收的是「**循环能跑**」（八件套落地），`G-021` 记的是「**循环的两端空**」（注什么工具 / 哪些面在循环里）。

**内核轴 = 「可见面收敛」**（`D-2026-10-12-1③`）：内核唯一输出契约 = **模型的可见集**。

```text
L1 可见工具集    由 ToolRegistry.resolve 单口径产出（源不得被上游剥空）      ← REF-8.1
L2 可见技能目录  全量目录（name + 封顶 description），正文惰性加载           ← REF-8.3
L3 可见上下文    system prompt（非空）+ 工作区 / 附件读手脚                 ← REF-8.2
```

**五个动作**（详案见 `plan/HelloAI 借鉴落地实施计划.md` §10）：

| 编号 | 动作 | 现状证据 |
|---|---|---|
| `REF-8.1` | **工具面解封**——给 `API_KEY_LLM` 一条**不依赖 MCP 会话**的工具通道（读原附件 / 文件读写 / 正式交付动作），复用 `ToolRegistry` 语义位 + `ToolCallbackContributor`，与 `resolve` 保持单口径 | ✅ **M1 已实施（2026-10-12）**：新增 `InternalLlmExecutorTools`（进程内 @Tool：读详情/读依赖/读状态/交付登记/读附件）+ 独立进 `McpToolConfig` provider；`AgentMcpServerServiceImpl:267-275` 对 `API_KEY_LLM` 返回内部授权集（而非空）；外部 MCP 13 工具仍对内部 LLM 剔除（防 L3 P1-1）。单测 `InternalLlmExecutorToolsTest` 6/6 + `AgentMcpServerServiceImplTest` 7/7；全量 1976 单测绿、架构守卫 EXIT=0 |
| `REF-8.2` | **补手脚 + 上下文**——新增 `WorkspaceAccess`（先只读 + 只写产物目录）+ 读附件工具；`systemPrompt` 从 `""` 改为「角色 + 工具使用指引 + 输出契约」；`maxIterations` 按任务类型分档 | ✅ **M2 已实施（2026-10-12）**：`AgentRuntimeContextAssembler` 新增 `buildSystemPrompt`（角色 + 内部 LLM 工具指引 + 输出契约，替换 `systemPrompt("")`）；`maxIterations` 分档——`AgentExecutionProperties` 增 `internalMaxIterations=10/externalMaxIterations=5` + `RuntimeTurnExecutor.resolveMaxIterations` 按 accessType 选档（`AgentLoopInput` 加 `DEFAULT_MAX_ITERATIONS_EXTRA=10`）。**手脚已被 REF-8.1 覆盖**（`internal_read_attachment` 读附件 + `internal_register_output_attachment` 交付登记），不再建 WorkSpaceAccess 文件系统工具（需 sandbox，REF-3 条件触发不排期）。单测 3 个新增；全量 1978 绿 |
| `REF-8.3` | **技能改「全量目录 + 惰性加载」**——注入目录段（非速览），新增惰性加载工具，`AgentSkillSpecService` 加 `get(name)`；保留 `requiredTools` 作能力级声明 | ✅ **M3 已实施（2026-10-12）**：`AgentSkillSpecService` 加 `getPackage(name)` / `loadBody(name)`（委托 `SkillPackageCatalog.byName/bodyOf`）；`InternalLlmExecutorTools` 加 `internal_list_skills`（全量目录，name/version/description/requiredTools 封顶）+ `internal_load_skill`（惰性加载正文）；`INTERNAL_LLM_TOOLS` 加 2 工具；`buildSystemPrompt` 增技能发现指引；`requiredTools` 保留能力级声明。单测 2 个新增；全量 1980 绿 |
| `REF-8.1.1` | **自报阻塞通道**——内部 LLM 缺「做不到」出口 ⇒ 只能把「做不到」写进正文（物化交付物 → 评审打回 → 返工至死信） | ✅ **已实施（2026-10-12）**：新增 `internal_report_blocked`（进程内 @Tool，带**知情拒绝闸门**——须先读过子任务详情、原因 ≥10 字）→ `block()` + 人工介入标记；阻塞后循环结束的 INTERNAL 回写因「状态非 `IN_PROGRESS`」被丢弃 ⇒ 不进评审。单测 4 例；形状对齐 DSH `update_goal.blocked`（研究文档 §1.5.3） |
| `REF-8.1.2` | **persona 两槽位（身份面）**——systemPrompt 身份写死且不可覆盖，缺「专业角色面」 | ✅ **已实施（2026-10-12）**：`AgentExecutionProperties` 增 `personaPrefix` / `personaSuffix`（`helloai.execution.*`，默认空 ⇒ 行为零变化）；装配槽位序「内置身份行 → persona prefix → 工具指引 / 输出契约 → persona suffix」；取值顺序 `agent.labels` → 部署配置 → 空（**不动表结构**）。对齐 DSH `deployment:persona-*` + `dsh-persona` 遮蔽纪律。单测 3 例 |
| `REF-8.2.1` | **返工上下文「替换而非追加」**——原把**所有轮次**审核意见全量拼入 ⇒ 思考链与 token 随轮数线性膨胀 | ✅ **已实施（2026-10-12）**：只铺开最近一轮（时间 / 问题 / 评语 / 评分 / 已自认修复），历史轮次压成一行并明写「已被本轮取代」。回归断言：历史轮次正文不进 prompt |
| `REF-8.2.2` | **验证证据围栏（结构化轮报）**——执行侧输出契约缺证据段，而评审侧 fail-close 明文要求 `VERIFICATION` 段 ⇒ 内循环必然返工至死信 | ✅ **已实施（2026-10-12）**：输出契约补 `VERIFICATION:` 围栏（证据 + `STATUS` + `NEXT_STEPS`），**复用评审侧既有判据**（`ReviewEvidenceAssembler.verificationSignal`）⇒ 评审侧零改动。实测：同一「撰写摘要并引用 marker」子任务由「返工 3 次 → 死信」变为首轮通过 |
| `REF-8.2.3` | **上下文压缩**——无预算与压缩、工具直读附件无上限、提供方报超窗即失败 | ✅ **已实施（2026-10-12）**：两道（确定性工具结果剪枝 → 仍超预算才 LLM 摘要）+ 两触发（每轮调用前压力检查 / 提供方报错后的溢出恢复）；含**净收益守卫**（仅净减才接受，否则回滚原文）与**摘要额度**（单次执行封顶）。摘要复用同一 ChatModel 与 options、逐字保留最近 `retainRatio` 尾部、不拆 assistant + tool 结果对；新增 `CONTEXT_COMPACTED` 审计事件。对齐 DSH `compaction-basic` + `compaction-tool-result-pruner` |
| `REF-8.4` | **拦截链** —— **循环自有 `LoopHook` 三档**（`beforeRequest` / `onRequestError` / `beforeTerminate`；**不套** Spring AI `Advisors`）；`LOG-20261011-007` 的 **B（收敛熔断）** 挂此 | ✅ **已交付（2026-10-13）**：① 三档挂点骨架 `09ecd747` · ② 收尾轮 `873bb559` · ③ `VerificationFence` / ④ 出口分类 `3b5224a6`（接线点 `ChatModelToolLoop:129/142/236`）；全量 2031 例绿（`LOG-20261013-010`/`-011`/`-012`）。**A 能力闸已出列** ⇒ 落 `ResilientDispatcher`（`D-2026-10-15-6`） |
| `REF-8.5` | **循环覆盖面收敛**——逐面判「该不该进循环」（子任务评审、终报告生成理论上都该带工具） | ✅ **已交付（2026-10-16，`LOG-20261016-005`）**：覆盖面 16 项**已逐项裁定**（进循环 4 项 / 留单次 12 项，边界即 `plan §10.6`）；新增业务面循环入口 `BusinessSurfaceLoopRunner` + **单咽喉插点**（`PlatformAgentExecutionServiceImpl.execute`，不散改 4 个调用点）+ 独立开关 `helloai.execution.business-loop-enabled` 与 scene 白名单（默认即裁定四项）。**三条实现裁定**（形态见 `plan §10.9`）：① 业务面**不复用** `RuntimeTurnExecutor`（子任务链契约三处冲突：入参守卫 / `VERIFICATION` 围栏误杀 / 子任务语义事件污染对账）而与它并列复用 `AgentLoop`；② 业务面**不写 `agent_event`**（防 `EventReconciliationService` 末条事件对账误报 + 不新增第二事实通道）；③ 开关**默认开启**（裁定即生效），回退通道保留。同批抽出两条单口径实现（`VisibleToolCallbacks` / `EnabledToolNames`，后者原为三处副本） |

**收敛完成判据**（内核自洽，较「组完成」更严）：① 不依赖 MCP 会话可独立构造 / 测试；② **可见面单口径**（模型可见工具集 = `ToolRegistry.resolve` 结果，上游无第二处二次过滤——对应已证缺陷模式「**规则存在 ≠ 全路径强制**」）；③ **能力可验证**（拿一条「需读原附件 / 写产物」的验收任务，内部 LLM 能**自己调工具**完成）；④ 事件不双写（模型可见消息与事件流同源）。

**红线**：**不新建 append-only 表**（`agent_event` 已是主表 + Outbox 双写，见 `AgentEventRecorder.java:11-15`）——正确动作是**提升已有 `agent_event`**（补 `surface` 投影 + `deriveMessages()`）；**不动 `ChatModelToolLoop` 循环本体**；**不引入 LangGraph4j**（触发信号出现前）。

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

### 内部 LLM 运行时合同（`G-023`，`REF-8.6`~`REF-8.9`，2026-10-16 立项）

> **立项裁定**：`D-2026-10-16-4`（记录 `LOG-20261016-004`）。**依据**：`review/内部 LLM 执行链运行时合同核对·以 DSH 为基准的重判（2026-10-16）.md`。

**为什么独立于 `G-021`**：`G-021` 的名称已限定为「工具面 / 手脚 / 技能装配 / 循环覆盖面」（**可见面收敛轴**）；本项是**运行时语义轴**（终止 / 取消 / 观测），两轴并存、互不替代（重判报告 §七）。

| 编号 | 缺口 | 优先序 | 验收要点 |
|---|---|---|---|
| **`REF-8.6`** | 取消 / 超时树缺失 | **1（最高）** | ✅ **已交付（`LOG-20261016-006`；形态见 `plan §10.10`）**：协作式取消（装配点收敛为 `AgentLoopInput` 紧凑构造器「唯一咽喉」，任何调用方无法绕过）+ 单工具超时（返回失败 observation 交模型决策、**不整链失败**）；**反证用例** = 工具调用中途取消 ⇒ 循环不再发起下一轮模型调用 |
| **`REF-8.7`** | `max-tokens` 截断被静默当成功 | **2** | ✅ **已交付（`LOG-20261016-007`；形态见 `plan §10.11`）**：新增独立终态 `MAX_TOKENS`（`success=false`、保留部分正文）；**sticky** 由「出口定性」实现 —— `truncated` 只置不撤，全部出口在进 `beforeTerminate` 挂点**前**即定为截断草稿 ⇒ 现有挂点自然跳过；结束原因取值容错、**未知取值 fail-open** |
| **`REF-8.8`** | 观测面 usage 只有单一 `totalTokens` | **3** | ✅ **已交付（`LOG-20261016-008`；形态见 `plan §10.12`）**：新增 `AgentUsage`（互斥五分量 + `totalTokens` + latency）；`AgentLoopResult` 增第 9 分量 `usage` + `withUsage`（**既有 8 参 factory 全保留**）；落 `AGENT_COMPLETED` payload 扩 6 键（不新增事件类型）；provider 缺字段**降级为 null、不报错** |
| **`REF-8.9`** | 独立预算闸缺失 | **4（登记不启动）** | **非对齐项 / 自研增强** —— 参照实现同样不做；前置 = 「每步可查询用量状态」载体（现不存在） |

> **落地进度**：`REF-8.6` / `REF-8.7` / `REF-8.8` 均已交付（形态分别见 `plan §10.10` / `§10.11` / `§10.12`）；`REF-8.9` 保持 `DEFERRED`（登记不启动）。详见 `plan §10.8`（立项与优先序）。

**明确不做的两项**（防后人按表面严重度反向排期）：`refusal` 当成功（参照实现同样不处理）· 独立预算闸作为「对齐项」（参照实现完全不做）。

---

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
| R7 | `SaFirewall`（Sa-Token 1.44 **默认防火墙**，应用未配置）对非法 HTTP Method 回 **HTTP 200 + 错误体**而非 4xx | 登记为**低危技术债，暂不修**：触发条件苛刻（需调用方自身发出非法方法）；修它要配 `SaFirewallCheckFailHandleFunction`，会改变**所有**非法请求的响应形状。防线改落**验证脚本侧**——须断言响应体语义、不得只看状态码（判据见 `LOG-20261010-005`） | — |

### 7.1.4 REF-5 四项之外 A 档（2026-10-10，依据 `plan/HelloAI 借鉴落地实施计划.md` §7）

> A 档六项里 `REF-5.2a` / `5.1`（`LOG-20261010-009`）与 `REF-5.3` / `5.4`（`LOG-20261010-010`）已交付；
> 本表给**每一项**留永久锚点，避免它们随批次闭环从索引里消失（`REF-5.6` 已并入 `REF-2`，故不列）。
> `R14` 为 2026-10-12 补登记 —— A1「载荷旁路」的**配套第二条**此前仅在 `research/` 正文出现，无 `REF-` 落点、未记 WONTFIX（孤儿项回流，`LOG-20261012-001`）。

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| R8 | **初始化期服务锁定**（`REF-5.3`） | **已交付**：判据 = **用户数 == 0**（不用 `system.setup_finished` —— 该值在 `V1__init_all.sql` 被预置为 `'1'`，当判据**永不生效**）；落点 `SetupLockInterceptor`，除 `/api/setup/**` 与 `/api/health/**` 外一律 `503 setup_required`，与前端 `!hasUsers` 同源。**边界**：闩锁在内存、单向 —— 清空用户表后恢复锁定需重启 | — |
| R11 | **子任务视图读判据统一**（2026-10-10 用户裁定） | **已交付**：原为**两套判据且都有问题** —— 时间线 / 对话流只认 `sub_task.assigned_agent_id`（实测把**合法团队成员**也拒：加入 team 后读附件 200、读时间线仍 403）；`/api/sub-tasks/getById` **无任何校验**（任何有效 Agent API Key 可读任意子任务详情）。三处一律收敛到 `SubTaskViewGuard` → `AttachmentVisibilityPolicy.canReadTaskScoped`（与附件读侧**同一判据**，逻辑零复制）。**未动**：MCP `getSubTaskDetail` 的「本人已分配 / 未分配且 PENDING」属**工作面**约束，与可见性面语义不同 | `G-014` |
| R14 | **图片 / 二进制载荷「只存路径引用」**（A1「载荷旁路」**配套第二条**，来源 Octop `infra/agents/middleware/workspace_image.py`） | **条件触发**（2026-10-12 用户裁定）：判据 = 图片在 checkpoint / history **只存 `workspace://` 路径引用**，仅在 model request 时展开为 `data:` URI ⇒ `base64` 永不落 state / 不进历史 payload（与 A1 主条目同属「数据面 / 控制面分离」，治的是**图片 base64** 那一半）。**当前不适用**（实测：平台**无图片 / 多模态输入通道**，业务侧 `base64` / `image_url` / `data:image` / `vision` 零命中；附件注入 `UpstreamAttachmentRenderer` 为**纯文本按行截断**）。**触发条件**：平台新增图片 / 二进制多模态输入通道 ⇒ 按 `REF-5.2b` 同思路落地（state 只留 `ref`）。**不排期** | `REF-5.2b` · `G-005` |

### 7.1.5 REF-6 判据守卫与前端事件字典（2026-10-11，依据 `plan/HelloAI 借鉴落地实施计划.md` §9 与 `LOG-20261010-013`）

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| R12 | **前后端事件码 key parity 守卫**（`REF-6.8`） | **已交付并随形两次重构**：`scripts/shell/verify-event-key-parity.sh`（静态模式入驻 ci-gate 门禁 3；`--db` 地面真值模式覆盖常量传参 / 历史码等静态盲区）。当前三断言 = 写点无裸字面量 / 写点枚举引用常量存在 / 库内真值 ⊆ 枚举。**边界**：前端硬编码字典已随服务端单一来源收口删除，守卫不再对账副本 | `G-001` · `G-006` |
| R13 | **事件码服务端单一来源 + 写点枚举化**（`REF-6.8` 另半） | **已交付**：`TimelineEventCode` 枚举（141 码）为唯一事实源 + `GET /api/events/catalog` 下发（前端删副本改拉目录）；后端展示通道写点 119 处由裸字面量改为 `TimelineEventCode.<CONST>.code()`（core 25 文件 + job 6 文件），无残留。验证见 `LOG-20261011-006` / `LOG-20261011-008` | `REF-6.8` · `G-001` |
| R9 | **载荷双视图**（`REF-5.2b`） | **已评估，结论 = 改形**（`doc/review/HelloAI REF-5.2b 与 REF-7 合并评估（2026-10-10）.md`）：不引入 `ToolResult` 双视图（该落点**在代码中不存在**）；实测前提修正 —— **数据没丢**（原文全量在 PG/MinIO），丢的是消费侧**可见性**；且 **`DEP_CONTENT_MAX_CHARS` 是整块总预算**（多前置附件合计越限即截断），按总预算实测**现网已在触发**（至少 2 条、最坏 12 条子任务越限）。**改形 = 标注行补 `id=<attachmentId>`（已交付）+ 复用既有 REST 取回通道**（实测团队成员 200），**不新增 MCP 工具**（工具面保持 13） | `G-020` · `REF-7` · `R11` |
| R10 | **目录守卫 + 结构化拒绝码**（`REF-5.5`） | **未来前置**：开放「用户指定工作目录」之前置，当前平台无该能力 ⇒ 不排期 | `G-005` |

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
