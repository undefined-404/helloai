# HelloAI 实现差距表

> **状态：CURRENT GAP**
>
> 本文只记录**当前 → 目标**的真实差距；**只保留最新状态、随迭代就地更新**，不累加过程记录。
>
> **最后更新：2026-10-25**（**三模型对照测试**：4 个同型文档任务分别由 deepseek-v4-flash / MiniMax-M3 / qwen3.7-plus 执行，产出行为画像——deepseek 9 次执行全一轮过、MiniMax 2 次全一轮过、**qwen 首轮被驳回**（`EXECUTION_RECORD` 泄漏进交付正文导致「恰好 3 个 H2」结构断言失败，返工后通过），另发现「执行密集闸门与评审闸门同判据」矛盾（内部 agent 无本机能力标记 ⇒ `dispatch_skip_execution_dense` + `review_skip_execution_dense_no_capability`，AUTOMATED 子任务无法自动闭环，见 `LOG-20261025-019`）。**纠错**：一度拟增设的「验收契约内部矛盾」判据被终审记录推翻、已撤回，同轮修 `B3` 并登记 `B4`，见 `LOG-20261025-018`。**G-025 续建**：契约死锁事故复盘后追加 `TD-11`（验收契约可满足性标注，只标不拒）+ `TD-12`（核验侧前置条件豁免），号段由 1~10 扩为 1~12；同日暴露 `B1a`（`SubTaskReviewServiceImpl:712` `rework()` 早退吞掉评审结论与 `SUB_TASK_AUTO_REVIEW_REJECTED` 事件）、`B1b`（前端未渲染驳回 `score`/`issues`）、`B2`（平台自身故障消耗返工预算）三项缺陷，批次次序见计划 §4.2，见 `LOG-20261025-014`。前次 **G-025 登记**：任务拆解的领域无关化——拆解只有一档（静态 DAG + 软件交付语法，`planner-decompose.md:60-93` 的契约先行/验收模板均为研发语境），`GRANULARITY` 只控「拆多细」不控「按什么维度拆」，`task` 表无领域/交付物类型维度，验收 `acceptance` 无判据类型；已立 `TD-1`~`TD-10`（`TD-10` 登记不启动）+ 设计 `design/HelloAI 任务拆解领域无关化设计.md` + 计划 `plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md`，**生效开关 = `TD-3`+`TD-5`**——二者未落地前两列恒为默认值、行为不变，见 `LOG-20261024-034`；`TD-1`/`TD-2` 迁移已 applied 复核通过、`TD-3` 真实样本验收②通过（DOCUMENT 任务产出 5 条非「写引言/写正文/写结论」草案）、`TD-4` 判定链已完成、`TD-5` **已完成并真实回归全通过**（fail-close 拦截显式错配、长度闸零误伤、**写侧缺口根治**——`SubTaskMapper.xml` 自定义 insert/updateById 补 `acceptance_kind` 列，重拆终验库里 5 条落显式 kind STRUCTURAL×4+REVIEWED×1，**观察项已解决**：模型已遵从 `acceptanceKind` 字段，见 `LOG-20261024-042`/`LOG-20261024-043`）⇒ **能力部分生效**。前次 **G-024 登记**：附件与联网检索的编排——决策 Prompt 原不含任何附件信息，检索词只能取自用户问句，实测产出「我上传的附件」「分析一下」等指代元词，首词已命中仍整轮耗时 42s；已补决策视图注入 + 模板「附件优先」规则 + 规则层元词过滤，见 `LOG-20261023-010`。G-008 复核：token 实为非空——37 条执行记录全为内部 LLM，SUCCESS 且带 token 22 条，12 条 null 均在 `V97` 迁移前；未达成项由「待数据积累」修正为「候选间比较待第 2 个 agent」，见 `LOG-20261023-005`；G-017 新增未达成项 ③：对账数据源未覆盖 `chat_attachment`，且两者同桶不同前缀会被判「会话附件 = 孤儿」，为开启孤儿清理的阻塞前置；G-017 未达成项 ③ 由「两类引用源」扩为「三类」：清理历史残留时发现 `skill_package.origin_zip_url`（技能包原始 zip，agent 域）也从不在对账口径内，且它不叫 `storage_url` 故按列名检索必漏；三类端口均已接入、分列字段齐备，剩余真机双来源对账验收。前次更新见 `LOG-20261022-001` / `LOG-20261021-008` / `LOG-20261021-006` / `LOG-20261021-005`）
>
> 过程与订正历史按「做过什么」归 `doc/log/`：2026-09 批次见 `doc/archive/log/2026-09.md`，2026-10 批次见 `doc/log/2026-10.md`。
>
> 当前进度**以 §1 总体矩阵为准**；本表是「当前差距」的唯一事实源。

# 0. 当前生效的口径与决策

> 只登记**仍然生效**的口径与决策；过程与订正历史见 `doc/archive/log/2026-09.md` / `doc/log/2026-10.md`。

**编号口径（全项目唯一，防撞号）**

| 编号 | 含义 |
|---|---|
| `G-001`~`G-024` | 本表差距项（见 §1 总体矩阵）；`G-021` / `G-022` / `G-023` / `G-024` 分别于 2026-10-12 / 2026-10-13 / 2026-10-16 / 2026-10-23 登记 |
| `P0`~`P3` | **架构重构主线阶段**（唯一含义）；审计优先级档已改用 `PR0`~`PR3` |
| `RM1`~`RM13` | 代码质量路线图（与目标架构能力层不再撞号）；**该计划已于 2026-10-17 归档 `Done`**（`doc/archive/implemented/HelloAI_RM存量缺口清偿计划（2026-10-04）.md`），号段不再新增 |
| `B1`~`B5` | 目标架构能力层（阻断项）；**4 项达成**（B1 单轨 / B3 Recover / B4 QualityGate / B5 用量与成本），**仅 `B2 Sandbox` 条件触发（暂缓）**（2026-10-18 复核：契约 + 五边界诚实标注 + 真实执行通道已建，`G-005` 三触发条件仍未命中） |
| `BASE-x.y` | 基础架构专项任务（`doc/archive/implemented/HelloAI 基础架构调整实施计划.md`，2026-10-12 已完成归档；编号已退役，不再新增） |
| `REF-x.y` | 借鉴落地专项任务（`plan/HelloAI 借鉴落地实施计划.md`，2026-10-09 建号）；`x` 取 1~8 —— **`REF-8` 内部 LLM 能力面**（2026-10-12 新增，`D-2026-10-12-1`） |
| `M-x` | **供应商 / 模型注册信息完善**（magpie 借鉴，2026-10-13 建号，`x` 取 1~9）：来源 `research/magpie_借鉴分析.md`，实施见 `plan/HelloAI 借鉴落地实施计划.md` **§11**。与 `REF-x.y` **并列**——`REF-` 号段 `x` 已占满 1~8，不扩号段 |
| `S-x` | **OpenSandbox 沙箱专项任务**（2026-10-17 建号，`x` 取 1~10）：`S-1`~`S-6` 实施见 `plan/HelloAI OpenSandbox 沙箱集成计划.md`；**`S-7`~`S-9` 沙箱执行链**（S-7 执行通道 / S-8 工具接线 / S-9 机械判定接入）实施见 `archive/implemented/HelloAI 沙箱执行链设计方案（2026-10-17）.md`；**`S-10` 危险命令守卫 = 后置登记不启动**（待执行通道被真实业务高频使用后评估误用面再启动，对齐 REF-8.9 独立预算闸同款处置）。与 `REF-x.y` / `M-x` 并列——`REF-` 号段不扩，另立号段（实测 `doc/archive/**` 无 `S-` 号段 ⇒ 不撞号） |
| `W-x` | **联网搜索效果优化专项任务**（2026-10-18 建号，`x` 取 1~7）：P0（W-1 编排器请求 answer / W-3 动态 freshness）+ P1（W-2 并行搜索 / W-4 搜索词质量）+ P2（W-5 Rerank / W-6 Grounding / W-7 模态卡）；实施见 `plan/HelloAI 联网搜索效果优化实施计划（2026-10-18）.md`。**P3 Agentic 多轮搜索 = 登记不启动**。与 `S-x` / `M-x` / `REF-x.y` / `T-n` 并列——实测 `doc/archive/**` 无 `W-` 号段 ⇒ 不撞号 |
| `ATT-x` | **需求对话附件专项任务**（2026-10-23 建号，`x` 取 1~7）：`ATT-1` 文本 + PDF 文本层 + 两层 Prompt 注入（**已落地**，`LOG-20261022-014`）/ `ATT-2` 视觉直读（图片与扫描件共用一条通道）/ `ATT-3` 大文件 RAG + 引用回源 / `ATT-4` 版面增强（Docling sidecar，**登记不启动**）/ `ATT-5` 附件意图三态判定（`none`/`answerable`/`needs_external`）/ `ATT-6` 分析后检索（不确定点→检索词，补搜多变体）/ `ATT-7` 附件预算窗口化（按模型窗口折算注入预算）。实施见 `plan/HelloAI 需求对话附件实施计划（2026-10-22）.md`（2026-10-23 由「设计方案」拆分更名；设计见 `design/HelloAI 需求对话附件设计.md`，调研见 `research/HelloAI 需求对话附件产品对标调研（2026-10-22）.md`）。与 `S-x` / `W-x` / `M-x` / `REF-x.y` 并列——`REF-` 号段不扩。实测 `doc/**` 全库无 `ATT-` 号段 ⇒ 不撞号；**刻意不用 `A-`**：`archive/implemented/deploy-checklist-arch-collect-2026-10.md` 内有 `A-1`/`A-2` 操作选项号，且「REF-5 四项之外 A 档」已在用「A 档」二字，两处都会混 |
| `PC-x` | **Paperclip 借鉴落地专项任务**（2026-10-23 建号，`x` 取 1~19）：`PC-1`~`PC-6` 现有机制补硬（env 黑名单 / 唤醒原因 / 409 语义 / 三配额分离 / 双入口契约 / 成本闸门）/ `PC-7`~`PC-11` CLI 推通道（适配器 / headless 契约 / 输出解析 / session 续接 / run 归一）/ `PC-12`~`PC-19` 有条件再做。实施见 `plan/HelloAI Paperclip 借鉴落地计划（2026-10-21）.md`。**建号动因**：旧稿用「阶段 + 序号」寻址（如「阶段二·1」），该地址不可被外部引用，且违反治理规则 §6.5「分期标签已冻结」——本次一并清偿，治理规则 §6.5 同步记录。与 `S-x` / `W-x` / `ATT-x` / `M-x` / `REF-x.y` 并列。实测 `doc/**` 无 `PC-` 号段 ⇒ 不撞号 |
| `TD-x` | **任务拆解领域无关化专项任务**（2026-10-24 建号，`x` 取 1~12）：`TD-1` `task.task_archetype` 六类拆分维度 / `TD-2` `sub_task.acceptance_kind` 四类验收判据 / `TD-3` 拆解提示词按 archetype 分支（**生效开关**）/ `TD-4` archetype 判定链 / `TD-5` 验收校验按 kind 分流 / `TD-6` 拆解期上下文快照 / `TD-7` `uncertainties` 扩 `TO_LOOK_UP`+`TO_DERIVE` / `TD-8` 澄清侧 stall 计数 / `TD-9` 单条 expand+regenerate / `TD-10` ledger 通道（**登记不启动**）/ `TD-11` 验收契约可满足性标注（2026-10-25 续号，只标不拒）/ `TD-12` 核验侧前置条件豁免（2026-10-25 续号，读 `TD-11` 标注做 `SKIPPED(blocked_by)`）。实施见 `plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md`，设计见 `design/HelloAI 任务拆解领域无关化设计.md`。与 `S-x` / `W-x` / `ATT-x` / `PC-x` / `M-x` / `REF-x.y` 并列。实测 `doc/**` 全库无 `TD-` 号段 ⇒ 不撞号；**刻意不用 `T-`**（`T-n` 已是专项工作项局部编号，`M-9` 起在用，复用会与 `T1`~`T10` 混）；**不用「阶段 / Phase」寻址**（违反治理规则 §6.5 分期标签冻结令，`PC-` 建号时同类问题已清偿一次） |
| `N-x` | **明确不抄项**——**仅存于 `research/magpie_借鉴分析.md` §八**（`x` 取 1~5），不承载任务、不进排期。⚠️ 与 `doc/archive/implemented/HelloAI_全面复测清单（2026-10-06）.md` 的复测用例号 `N-1` **同名不同义**（archive 为历史现场，不改） |
| `ARCH-YYYYMMDD-NNN` | 架构决策（`log/HelloAI 架构变更记录.md`） |
| `LOG-YYYYMMDD-NNN` | 迭代记录（`log/YYYY-MM.md`） |
| `T-n` | **专项工作项局部编号**（2026-10-14 建号，`M-9` 起）：单个专项内部的任务拆分（如 `T1`~`T10`），**仅在该专项的文档/报告内寻址**；不承载排期、不进 §1 矩阵、不用于跨专项引用。实测 `doc/archive/**` 无 `T-` 号段 ⇒ 不撞号 |
| `QA-<专项>-nn` | **质量验证缺陷局部编号**（2026-10-14 建号）：随该专项的验证报告落 `doc/review/`，两个 `99` 门限内 `nn` 递增（如 `QA-M9-01`）；**不承载排期**，仅用于报告内寻址与整改闭环追溯 |

> `C-1`~`C-42`（原「差距表订正留痕」编号）**已于 2026-10-09 退役**——差距表订正与产出它的迭代记录同源，不再单独编号；一律改用 `LOG-YYYYMMDD-NNN` 寻址。`doc/archive/**` 内的 `C-NN` 为历史现场。

**状态词表（协作规约 §6.3 的九态；本表一律写中文值，英文名只作与协约对照）**

| 中文值 | 协约英文名 | 含义 |
|---|---|---|
| 待开始 | `TODO` | 未启动 |
| 设计中 | `DESIGNING` | 方案未定稿 |
| 实现中 | `IMPLEMENTING` | 正在写代码 |
| 验证中 | `VERIFYING` | 已实现、待验证 |
| 已完成 | `DONE` | 验收成立；剩余动作必须为 `—` |
| 部分完成 | `PARTIAL` | 主体已交付，仍有未达成项 |
| 阻塞 | `BLOCKED` | 有明确外部阻塞 |
| 暂缓 | `DEFERRED` | 条件触发或后置，不排期 |
| 明确不做 | `WONTFIX` | 已裁定不做 |

> **本表落位 = §1 矩阵「状态」列**（严格等于上表中文值之一，不含任何叙述）。四条配套口径：① 一行只允许一个状态值；子项状态不同时取最需关注态（偏序 `阻塞 > 暂缓 > 部分完成 > 待开始 > 设计中 > 实现中 > 验证中 > 已完成`），明细写在「剩余动作」列；② `明确不做` 只用于整行都不做，单子项不做不占用行状态；③ 第 3 列「当前状态」**不得**使用状态词（改用事实语），两列都表状态是新漂移源；④ **不设进度百分比列**，需要量化时用子项计数（`未达成 3 项`）。
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

> **只列仍然约束未来判断的取舍。** 其余裁定的完整论证、反证过程与验收细节在 `log/`（按 `D-YYYY-MM-DD-N` 编号可检索）——本表不复述，复述必与代码事实分叉。

### 仍然生效的取舍

- **`D-2026-09-29-1`｜MVP 可用性优先于 P1 剩余项推进**（阶段性有效）：事故清偿告一段落后**必须回到 P1 剩余项**。**复审触发**：① 新 P0 事故；② P1 剩余项连续 2 个迭代未启动；③ 用户要求推进隔离 / 治理类能力。
- **`D-2026-09-29-2`｜验证与工程化基础设施优先于功能推进**：新建功能之前先补齐 CI 门禁、可用 JDK 固定、架构漂移冻结守卫、B 级 IT。
- **`D-2026-09-30-3`｜不补回退手段（硬切 = 单向门）**：撤销的是「回退开关」；**不撤销**「兜底策略」（超时重派 / 掉线回收 / 死信 / 孤儿巡检）与「同层恢复」。回退手段 = `git revert`。

### 其余裁定索引（编号可检索，正文只在 log）

| 日期 | 编号 | 裁定 | 记录 |
|---|---|---|---|
| 2026-10-09 | `D-2026-10-09-4` | 借鉴落地专项立项与四项裁定 | `log/2026-10.md` |
| 2026-10-09 | `D-2026-10-09-5` | Fork 驱动执行不立项（WONTFIX） | `log/2026-10.md` |
| 2026-10-09 | `D-2026-10-09-6` | 借鉴落地专项余下五项裁定 | `log/2026-10.md` |
| 2026-10-10 | `D-2026-10-10-1` | 能力包安装入口（REF-1.6）技术裁定 | `log/2026-10.md` |
| 2026-10-11 | `D-2026-10-11-1` | RAG 嵌入模型选型与密钥管理四项裁定 | `log/2026-10.md` |
| 2026-10-11 | `D-2026-10-11-2` | 事件码改服务端单一来源（取代 D-2026-10-09-6③-4 的「事件码 guard-only」） | `LOG-20261011-008` |
| 2026-10-10 | `D-2026-10-10-2` | REF-2 备份 / 恢复实施方案六项裁定 | `log/2026-10.md` |
| 2026-10-12 | `D-2026-10-12-1` | 内部 LLM 能力面立项（REF-8 / G-021） | `LOG-20261011-007` |
| 2026-10-12 | `D-2026-10-12-2` | REF-8 开工 + 主线指针 + 覆盖面清单 | `log/2026-10.md` |
| 2026-10-12 | `D-2026-10-12-3` | LOG-20261011-007 A+B 立项 | `LOG-20261011-007` |
| 2026-10-12 | `D-2026-10-12-4` | REF-7 三约束定稿 + 继续挂起 | `log/2026-10.md` |
| 2026-10-12 | `D-2026-10-12-5` | REF-8.1 对外面隔离 + 执行链绑定闸门（安全口径收口） | `LOG-20261012-006` |
| 2026-10-13 | `D-2026-10-13-6` | REF-8.4 自动触发接线 | `LOG-20261013-011` |
| 2026-10-13 | `D-2026-10-13-7` | REF-8.4 可移植性 | `LOG-20261013-011` |
| 2026-10-13 | `D-2026-10-13-8` | A/B 词表是否拆分 | `LOG-20261013-011` |
| 2026-10-13 | `D-2026-10-13-9` | 形态甲/乙（V52 加列退列 vs 泛化能力谓词表） | `LOG-20261013-011` |
| 2026-10-13 | `D-2026-10-13-10` | requiredBaseCapabilities 挂点 | `LOG-20261013-011` |
| 2026-10-13 | `D-2026-10-13-11` | M-x 号段建号 + minimax 换代口径（含「发布前修改已 apply 的 Migration」例外留痕） | `LOG-20261013-015` |
| 2026-10-14 | `D-2026-10-14-1` | M-3 模型目录种子的播种口径与承载（含「新增 provider 不建 yml 段」） | `LOG-20261014-001` |
| 2026-10-14 | `D-2026-10-14-2` | M-4 能力元数据回填的承载形态与口径（形态乙落地 + deepseek-v4-pro 模态冲突留痕） | `LOG-20261014-003` |
| 2026-10-14 | `D-2026-10-14-3` | M-5「extraConfig 只写不读」闭环 | `LOG-20261014-004` |
| 2026-10-14 | `D-2026-10-14-4` | M-6 Key 指纹标识的承载与去重口径（含「指纹不可 SQL 回填 ⇒ 应用层惰性回填」「回填必须落在 system 域」） | `LOG-20261014-005` |
| 2026-10-14 | `D-2026-10-14-5` | M-6 指纹「对齐对象」归属订正（保留 24 字符口径） | `LOG-20261014-006` |
| 2026-10-14 | `D-2026-10-14-6` | M-7 批量录入去重的拆分口径与落库形态（含「不覆盖主 key」「备位只能落 DISABLED」） | `LOG-20261014-007` |
| 2026-10-14 | `D-2026-10-14-7` | M-8 多 Key 主备的手动切换语义与「启用位不可缺失」判据（含脱敏口径统一、明确不移植 failover/routing） | `LOG-20261014-008` |
| 2026-10-14 | `D-2026-10-14-8` | M-9 能力谓词消费方切换与 V52 两列退役（含 modality 不映射技能词表、thinking 改由 reasoning 驱动） | `LOG-20261014-009` |
| 2026-10-15 | `D-2026-10-15-1` | G-022 补引用边（不统一词表）；QA-M9-03 收口 | `LOG-20261015-001` |
| 2026-10-15 | `D-2026-10-15-2` | 外部漏洞清单核验结论 + 4 项可靠性修复（选择性修复） | `LOG-20261015-002` |
| 2026-10-15 | `D-2026-10-15-3` | 三项遗留项落地（HIGH-3 事件契约订正 / G4 幂等键 / MED-1 核验侧质量分） | `LOG-20261015-003` |
| 2026-10-15 | `D-2026-10-15-4` | 剩余四项评估：MED-3/G8 升级为真缺陷并修复，MED-4/G6 经核不改（含订正我先前的错误建议） | `LOG-20261015-004` |
| 2026-10-15 | `D-2026-10-15-5` | D-2 多实例鉴权：MCP 鉴权上下文外置 Redis（静态工具类 → @Component） | `LOG-20261015-005` |
| 2026-10-15 | `D-2026-10-15-6` | REF-8.4 交付状态回填 + A/B 挂点口径订正（文档漂移清偿） | `LOG-20261015-007` |
| 2026-10-15 | `D-2026-10-15-7` | 门禁 3「守卫自身失真」三类缺陷清偿 + Shell 可移植性守卫 | `LOG-20261015-008` |
| 2026-10-16 | `D-2026-10-16-1` | LOG-20261011-007 A（能力闸）+ B（收敛熔断）实施，含两处前提/判据订正 | `LOG-20261011-007` |
| 2026-10-16 | `D-2026-10-16-2` | 内部 LLM 运行时合同核对的**基准口径**改为「以 DSH 为准」（原报告 G1~G4 重排） | `LOG-20261016-002` |
| 2026-10-16 | `D-2026-10-16-3` | plan/ 侧文档漂移清偿 + ReviewProperties 注释恢复 | `LOG-20261016-003` |
| 2026-10-16 | `D-2026-10-16-4` | REF-8.5 覆盖面边界裁定 + G1~G4 立项（REF-8.6~REF-8.9）+ 上线清单触发条件改写 | `LOG-20261016-004` |
| 2026-10-16 | `D-2026-10-16-5` | REF-8.5 落地形态三条裁定 + 两条单口径抽出 | `LOG-20261016-005` |
| 2026-10-16 | `D-2026-10-16-6` | REF-8.6 取消 / 超时树落地：装配点收敛「唯一咽喉」+ 超时与取消语义分离 | `LOG-20261016-006` |
| 2026-10-16 | `D-2026-10-16-7` | REF-8.7 max-tokens 终态落地：sticky 靠「出口定性」而非挂点自觉 | `LOG-20261016-007` |
| 2026-10-16 | `D-2026-10-16-8` | REF-8.8 观测面 usage 互斥拆分落地：「只扩字段、不改控制流」用「加分量 + 末位装配」 | `LOG-20261016-008` |
| 2026-10-16 | `D-2026-10-16-9` | 整合报告「自检围栏顶替正文」事故修复：改写型挂点的「原物仍在」出口校验 + 交付物净化 + 审查判定宽松兜底 | `LOG-20261016-009` |
| 2026-10-16 | `D-2026-10-16-10` | BLOCKED 自动重派巡检落地：关闭 plan §10.7.6 的唯一缺口（方案 b） | `LOG-20261016-010` |
| 2026-10-16 | `D-2026-10-16-11` | Recovery 消费面方向与落地（T1 序 4，会议裁定） | `LOG-20261016-011` |
| 2026-10-16 | `D-2026-10-16-12` | RM9-a：McpToolServiceImpl 上帝类拆分首笔（切分线执行 + 锚点重取 + 守卫留门面） | `LOG-20261016-012` |
| 2026-10-16 | `D-2026-10-16-13` | RM9-b：SubTaskServiceImpl 上帝类拆分第二笔（状态机唯一入口整链搬迁 + 两档验收口径落地） | `LOG-20261016-013` |
| 2026-10-17 | `D-2026-10-17-1` | RM9-c：RequirementClarifyServiceImpl 上帝类拆分第三笔（门面 ⇄ 编排器破环 + renderPrompt 按模板拆分） | `LOG-20261017-001` |
| 2026-10-17 | `D-2026-10-17-2` | RM9 命名合规整改：service.impl 命名归位 + 静态投影助手迁出 {domain}.projection | `LOG-20261017-002` |**当前冻结基线**

**当前冻结基线**

- **§11.3「Controller 不得暴露 core Entity」**：命中 **19 处 / 8 控制器**（冻结未清偿 —— 改动 API 响应契约须与 `helloai-ui` 联动，单独立项）；校验 `scripts/powershell/verify-code-style-p0-layer.ps1 -StaticOnly`。
- **架构守卫**：组 1 反向 / 组 2 mapper 为 `block`，基线全 0；组 3 前向为 `warn`（`task->agent` 57 / `planner->agent` 34 —— 后者含 `WebSearchToolCallback` 对 `ToolContext` 的声明面依赖，属 §7.2 端口反转的机制性代价，**不刷冻结基线**）。**组 3c `api->core.entity` 当前 52（基线 49）**：该 +3 系 `InstalledSkillPackage` / `PlatformBackup` / `SysUser` 三条 `...core.<domain>.entity.` import 于基线刷新（`1d38508f`）之后落地，**与 `M-6`~`M-8` 无关**（`M-8` 新增 import 全落 `core.*.port`，不匹配该规则）；同属 `warn` ⇒ 不拦截、**不刷冻结基线**。**`M-9` 复核（2026-10-14）**：本批新增 import 全落 `core.system.port` / `core.agent` / `core.shared`，同样不匹配 `...core.<domain>.entity.` 规则 ⇒ 组 3 各计数**不变**，`M-9` 贡献 **0 漂移**（亦无新增告警）。**`BLOCKED` 自动重派批（2026-10-16）**：新增 `job->core.entity` **14 → 15**（`SubTaskBlockedAutoRedispatchTask` 读 `SubTask` 实体，与既有 `SubTaskPendingOrphanTask` **同型**，属 job 层既有惯例、不新增模式）；同期另有 `planner->agent` 33 → 34、`api->core.entity` 49 → 52 两条提示，均于本批之前落地。组 1（反向依赖）/ 组 2（跨域 Mapper）**仍全 0** ⇒ 同属 `warn`，不刷冻结基线。**`RM9-b`（2026-10-16）**：`task->agent` **57 → 60**（+3）—— 拆分把门面的 11 条 agent import **重新分布**到 3 个职责类共 14 条（净 +3），**全为 §6 合法前向、未新增依赖模式**（`SubTaskStateMachineService` / `SubTaskNotificationService` / `SubTaskLeaseService` 三类的 agent 依赖与门面原有一一对应）；`planner->agent` 34、`api->core.entity` 52、`job->core.entity` 15 均经 `git grep … HEAD` 实测为**提交前既有**；组 1/2 **仍全 0** ⇒ 同属 `warn`，不刷冻结基线。

---

# 1. 总体矩阵

| 编号 | 项目 | 当前状态 | 目标（最终口径） | 优先级 | 状态 | 剩余动作 |
|---|---|---|---|---|---|---|
| G-001 | Agent Event Stream | Run / Turn / Step + Event 契约；Timeline 并轨 agent_event；Replay / Audit 经 REST 四端点 + UI 事件流工作台 | 统一事件契约与消费体系；Recovery 消费面提供只读恢复点投影与续跑兜底 | PR0 | 已完成 | — |
| G-002 | Executor 迁移 | `RuntimeTurnExecutor` 为唯一 `AgentRuntime`；旧链入口全删；无配置级回退开关 | Runtime 成为唯一执行契约 | PR0 | 已完成 | — |
| G-003 | AgentRuntime | 八件套契约齐备；工具侧两个语义位（按条件可用 / 按上下文动态描述）生效；`CRITICAL_TOOLS` 三件套不可关闭 | Context + Session + Skill + Tool + Loop + Event + Environment + SandboxProvider 全落地 | PR0 | 已完成 | — |
| G-004 | Skill Capability | 元数据源 = classpath frontmatter + 目录扫描（9 字段）；技能包安装入口含摄入闸门、受控存储与审计 | Metadata / Version / Tools / Schema / Dependencies 全量 + requiredTools→tools 联动 + 受控存储安装 | PR1 | 已完成 | — |
| G-005 | Sandbox Provider | 契约齐备但无可隔离执行对象；沙箱执行链仅作内部 LLM 受控验证通道（默认禁网 / 零凭据 / 短 TTL） | 真正的 Provider 化执行环境与隔离策略 | PR1 | 暂缓 | 触发条件（任一成立即重排期）：平台增加碰宿主工具 / 技能包被执行 / 平台自持浏览器 |
| G-006 | Replay / Audit | 写侧 + 对账闭环；Timeline / Replay / Audit 三读侧经 REST + UI 工作台；外部执行轨迹与事件同址 | 基于统一 Event 查询回放；外部 Agent 全周期轨迹可对齐 | PR1 | 已完成 | — |
| G-007 | Quality Gate | Reviewer 闭环（执行 → Review → PASS / REJECT → REWORK） | Rule + Test + LLM 统一决策 | PR2 | 待开始 | 统一决策未启动，当前仅有链上增强 |
| G-008 | Agent Fleet Routing | 选人四维（Capability / Health / Load / Policy）+ 成本维度已入比较链；外部 tokens 回报协议就绪 | 五维选人完整生效，成本可参与候选间比较 | PR2 | 部分完成 | 成本候选间比较待第 2 个 LLM agent 样本；成本闸门接线决策 |
| G-009 | Dynamic Workflow | 模板 / 版本 / 实例化 / DAG | 动态分支与复杂运行期编排 | PR3 | 暂缓 | 动态分支与运行期编排未启动 |
| G-010 | Planner 能力感知与自适应粒度 | 技能目录注入拆解 Prompt；子任务级 requiredSkills / constraints 指派；粒度三档 rule-based 决策矩阵；审查侧同口径核验 | 技能感知 + 粒度自适应 + 外部下行通道 + 技能回流贡献规范 | PR1 | 已完成 | — |
| G-011 | 需求包准入与不确定性显式管理 | 结构化需求包双写；uncertainties 显式 JSONB 列；执行侧注入 + 审查侧分级语义；驳回带结构化返工线索 | 需求包准入与不确定性显式管理的全链路闭环 | PR1 | 部分完成 | `gap_kind` 实现路径分类与任务后蒸馏闭环 |
| G-012 | 登录鉴权与 RBAC（Sa-Token） | Sa-Token 会话 + 角色-权限码 RBAC + 管理 API + 前端动态菜单按权限码过滤 | 统一会话 + 权限码管理 + 注解鉴权 + 动态菜单 | PR2 | 已完成 | — |
| G-013 | 基础架构深化（RBAC 底座） | 动态路由（权限 = 可达性）+ v-auth 按钮级 + 动作级权限码 + 菜单树驱动 addRoute + 组织架构 + 数据权限规则 | 对齐 JeecgBoot 标杆的权限与组织体系 | PR1 | 已完成 | — |
| G-014 | 外部 Agent 执行通道 | 外部通道与内部分发同口径：依赖门禁 / 返工出口 / 附件可发现 / 截断元数据 / 在线语义 / 核验边界 | 外部 Agent 通道与内部分发链同口径，返工可自救 | PR0 | 已完成 | — |
| G-015 | 心跳 / 重派止血 | 值班租约守卫 + 在飞宽限 + 退避独立列 + 离线置 PAUSED + 无候选 409 可读原因 | 长任务不被心跳窗口误判离线；判离线读写两视图口径统一 | PR0 | 已完成 | — |
| G-016 | 报告整合质量 | 报告链贯通：物化附件优先 + 附件限额单源 + 块级截断 + 审查异步化 + 三级容错 | 报告按主题归并可追溯、驳回可返工、自审不静默放行 | PR1 | 已完成 | — |
| G-017 | 附件存储一致性 | `ArtifactStorage` 双实现 + 注册前置校验 + 6h 对账巡检（三类引用源）+ 孤儿清理三重保险（默认关闭） | DB 与对象存储两侧一致，悬空 / 孤儿可发现可修复 | PR1 | 部分完成 | 真机双来源对账验收；部署侧改 MinIO 默认凭据 + 端口白名单 |
| G-018 | 备份 / 恢复 | pg_dump 全库 + 对象清单 + manifest peek + 单飞锁 + 恢复三门 + 停机 runbook | 全库与对象可备份可恢复；运行中备份不保证多文件同一瞬间 | PR1 | 已完成 | — |
| G-019 | RAG 知识库 | `kb_chunk`（vector + HNSW-cosine）+ 余弦检索 + `search_knowledge`（无 KB 即摘）+ 注入预算 + 引用溯源 | PG 单后端检索 + 条件可用语义位 + 引用溯源可点开 | PR2 | 已完成 | — |
| G-020 | 外部 Agent 工作详情快照 | 完全空白：只能审计到订单层面，子任务内部执行为黑盒 | 事后、经审批、可选提交工作详情快照 → 原文落 MinIO + 结构化审计事件 | PR2 | 待开始 | 动手前须定三项约束：与审计 schema 的张力 / 审批方 / 落时间线粒度 |
| G-021 | 内部 LLM 能力面 | 工具面已解封（读详情 / 依赖 / 状态 / 附件、交付登记、技能目录与惰性加载、自报阻塞）；五个业务面进入循环，其余十一面留单次 | 内部 LLM 有工具、有手脚、能按需装配技能 | PR1 | 已完成 | — |
| G-022 | 选人技能门（命名空间一致性） | 引用边已补（`requiredBaseCapabilities` + `satisfiesPackage` 唯一判据）；注册链派生；拆解侧承接者校验 | 技能门两端同口径：声明侧声明即有人具备 | PR1 | 部分完成 | 存量 `agent.skills` 回填（须重注册触发）；`eng-*` 装进内部 LLM 工具授权集 |
| G-023 | 内部 LLM 运行时合同 | 取消 / 超时树 + max-tokens 独立终态 + usage 五分量拆分均已接线；独立预算闸未做 | 可取消可超时、截断不静默成功、用量可拆解 | PR1 | 已完成 | — |
| G-024 | 附件与联网检索的编排 | 决策视图注入 + 模板「附件优先」四条硬规则 + 元词过滤（含兜底同源判定）+ `ATT-5` 附件意图三态（真机三轮验证：`answerable` 不联网 / `none` 不注入正文 / `needs_external` 带锚点检索）+ `ATT-6` 补搜多变体 | 检索词由附件外部锚点构成；附件无可核验实体时不触发联网 | PR1 | 部分完成 | 进度文案取真实候选词；`websearch-gap-assess.md` 补「检索词由附件外部锚点构成」约束（`ATT-6` 目前只做多变体） |

| G-025 | 任务拆解的领域无关化 | 拆解只有一档：静态 DAG + 软件交付语法（`planner-decompose.md:60-93` 契约先行/验收模板均为研发语境）；`GRANULARITY` 只控「拆多细」不控「按什么维度拆」；`task` 表无领域或交付物类型维度；拆解输入仅 `title`+`description`+`requiredSkills`+`difficulty`+需求包；验收 `acceptance` 为自由文本、无判据类型 | 按交付物判据分档：`task.task_archetype` 六类（BUILD/DOCUMENT/RESEARCH/DATA/OPERATE/DECIDE）+ `sub_task.acceptance_kind` 四类（AUTOMATED/STRUCTURAL/REVIEWED/ATTESTED）+ 每类对应的拆分维度与终止锚 + 拆解期上下文快照 + 「计划未知」任务的 ledger 通道 | PR1 | 部分完成 | `TD-1` / `TD-2` **已完成**（迁移 `V124` / `V125` 应用重启后复核 `Success`：存量 10 条任务全 `BUILD`、45 条子任务全 `AUTOMATED`，CHECK 拒绝非法值 `NOPE`/`MAYBE` 已实测）；`TD-3` 提示词分档已落地 + **真实样本验收②通过**（`taskArchetype=DOCUMENT` 任务产出 5 条草案，title 脱离「写引言/写正文/写结论」结构；**观察项已解决**：模型已遵从 `acceptanceKind` 结构化字段——重拆实测 5 条全显式声明，见 `LOG-20261024-043`）；`TD-4` **已完成**（`package.archetype` → 防御式解析 → 写 `task.task_archetype`，未走澄清链路回落 `BUILD`，单测锁定回落路径；六类真实判定待澄清链路回归观测）；`TD-5` **已完成**（`AcceptanceKindValidator` 四套 kind 校验 + 显式声明 fail-close / 缺失回落 Warn 放行 + `acceptance ≥ 10` / `deliverable ≥ 8` 字符下限质量闸；**真实回归全通过**——fail-close 拦截显式错配（`2113976255295021058` 整批拒绝 + `task_plan_failed`）、长度闸零误伤（真实产物最短 219/46 字符）、**写侧缺口根治**（`SubTaskMapper.xml` 自定义 `insert`/`updateById` 补 `acceptance_kind` 列，重拆终验库里 5 条落显式 kind：STRUCTURAL×4 + REVIEWED×1，insert 日志参数带 `STRUCTURAL(String)` 铁证）、**验收③ REVIEWED 接审查域完成**（timeline payload 补 `acceptanceKind`+`analysis` 逐条核验、对话流结果文本补「逐条核验」段，单测 61+8 例全绿，见 `LOG-20261024-044`；另人工修正两个历史回归任务 `2113947011709964289`/`2113971974332534786` 共 11 条草案 kind——写侧缺陷期间产物，文本 STRUCTURAL/REVIEWED 风格库记 AUTOMATED，修正后 9 STRUCTURAL + 2 REVIEWED）；`TD-6` / `TD-7` 已完成；`TD-8` **已完成**（澄清侧 stall 计数——**判据经用户拍板 = 用户信息量判据**：selections 的 values/customText 全空 + 纯文本敷衍词即「无新信息」，连续 2 轮（可配置）强制收敛出终稿、不自动建任务、未决项 openQuestions 完整列出；`ClarifyStallGuard` 纯函数 + `runConvergeRound` 接入，单测 13+3 例全绿，见 `LOG-20261024-045`）；`TD-9` **已完成**（单条草案细拆 `expandById` + 同粒度重写 `regenerateById`——expand：单条 → N 条 FINE 子草案、组内依赖 Kahn 校验后落库回写、**逻辑删原草案**、被其他草案依赖拒绝展开（幽灵依赖 fail-close）；regenerate：**不变量白名单**只改 content/deliverable/acceptance/acceptanceKind，title/id/dependsOn 一律不动（LLM 输出被忽略取原值）；专用提示词 `planner-expand.md`/`planner-regenerate.md` + `SubTaskService.updateDraftContent` 写命令 + `POST /sub-tasks/expandDraftById/{id}`/`regenerateDraftById/{id}`（`subtask:edit`）+ 前端草案审阅弹窗「展开」「重生成」按钮；同步执行 `@Transactional` 失败回滚不破坏原草案；单测 5 例全绿 + 全量 2601 例全绿，见 `LOG-20261024-046`），`TD-10` 登记不启动；**能力部分生效**——`TD-4` 已接 ⇒ 走澄清链路的任务可自动判 archetype，未走链路仍 `BUILD`；`TD-5` 已接 ⇒ 显式声明的 `acceptance_kind` 触发分流校验（错配 fail-close）且落库保留显式 kind（写侧已补列），缺失回落放行不影响存量；`TD-8` 已接 ⇒ 连续无信息收敛出终稿不建任务；`TD-9` 已接 ⇒ 草案态支持局部返工（细拆/重写）；**2026-10-25 续建 `TD-11`（验收契约可满足性标注，只标不拒）+ `TD-12`（核验侧前置条件豁免）**——起因是契约死锁事故：`content` 要求「不要自行替换成其他产品」∧ `acceptance` 要求「≥15 条带真实 URL 的事实」⇒ 执行者无解，两版产出各走极端均判 2 分，且驳回理由因 `SubTaskReviewServiceImpl:712` 的 `rework()` 早退被整条吞掉（`B1a`）；`TD-5` 未拦住是因 `validateAcceptanceKinds` 签名不含 `uncertainties`、`AcceptanceKindValidator` 只判文本写法（见 `LOG-20261025-014`）；**2026-10-25 批次 6 已落地 `B1a`（驳回留痕前移到 `rework()` 之前）+ `B1b`（事件行渲染评分与 issues）+ `TD-11`（只标注不阻断，`AcceptanceSatisfiabilityChecker` 四条件 + `ref` 补齐）**，全量 2930 例 0 失败、守卫全绿，`TD-11` 置「验证中」待真机样本；`B2` 按计划留第 8 批（见 `LOG-20261025-015`）；**同日真机全链路实测 + 一次纠错**：拆解产出 8 条草案、`TD-11` 标注机制全通（`ref` 落库 / `context` 键 / 时间线事件均正常）但**准确率 0**——标了 2 条都不阻断、漏了实际 `BLOCKED` 的 2 条；期间一度据「`acceptance` 内部自相矛盾」提出「甲判据」，被同一任务的终审记录推翻（草案 4 的第 5 条与草案 2 **一字不差**，且 round 2 `APPROVED score=4`，理由明写「有官方来源」∧「全文未出现代号—真实产品对应表述」）⇒ **甲判据撤回**、`TD-12` 豁免入口维持 `acceptancePreconditions` 不变（见 `LOG-20261025-018`）；同轮修 `B3`（终稿 `archetype` 回落不可见 → `TaskArchetypeResolver` 纯函数 + `task_archetype_fallback` 事件 + 7 例单测，真机现场：终稿包顶层键**根本没有 `archetype`**，落库却是 `BUILD`）并登记 `B4`（`sub_task_report_blocked` 的 `reason` 恒为空，失败原因在相邻 `execute_failed` 事件上 ⇒ 转展示层合并，不动执行侧契约）；见 `plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md` §0 ④ |

## 1.1 专项任务索引（任务视角，状态不复述）

> G 矩阵（上方）回答「还差什么能力」；本表回答「各专项的任务编号落在哪个文件」。**两者编号体系不同，不可互相替代。**
>
> 状态只在「唯一事实源」里维护。本表不复制进度——同一事实写两处必然漂移，而漂移后的第一受害者正是来查状态的人。

| 体系 | 编号 | 项数 | 唯一事实源（状态只见此处） |
|---|---|---|---|
| `REF-x.y` | REF-1 ~ REF-8（含 `REF-8.1`~`REF-8.9` 子项） | 8 组 | `plan/HelloAI 借鉴落地实施计划.md` §3 ~ §10 各节 `Status` |
| `M-x` | M-1 ~ M-9 | 9 | `plan/HelloAI 借鉴落地实施计划.md` §11（**全部完成**） |
| `S-x` | S-1 ~ S-10 | 10 | `plan/HelloAI OpenSandbox 沙箱集成计划.md` §3；`S-7`~`S-9` 见 `archive/implemented/HelloAI 沙箱执行链设计方案（2026-10-17）.md`；`S-10` 登记不启动 |
| `W-x` | W-1 ~ W-7 | 7 | `plan/HelloAI 联网搜索效果优化实施计划（2026-10-18）.md` 各节；`P3 Agentic 多轮搜索` 登记不启动 |
| `ATT-x` | ATT-1 ~ ATT-4 | 4 | `plan/HelloAI 需求对话附件实施计划（2026-10-22）.md` 头部状态行 + `ATT-x` 各节 |
| Paperclip | 阶段一 / 二 / 三（不引入编号前缀） | 3 阶段 | `plan/HelloAI Paperclip 借鉴落地计划（2026-10-21）.md` §1 ~ §3 |
| `TD-x` | TD-1 ~ TD-12 | 12 | `plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md` §3 任务表（各 `### TD-x` 标题下的 `**状态**` 行）；`TD-10` 登记不启动；`TD-11`/`TD-12` 为 2026-10-25 续号 |
| `RM1`~`RM13` | 已退役 | — | `archive/implemented/HelloAI_RM存量缺口清偿计划（2026-10-04）.md`（`Done`，号段不再新增） |
| `BASE-x.y` | 已退役 | — | `archive/implemented/HelloAI 基础架构调整实施计划.md`（`Done`，号段不再新增） |

**维护口径**：本表只登记「编号体系 → 落点」。**项数变化时**（如 `S-x` 扩到 11、`W-x` 新增 8）须同步本表与 §0 编号口径表两处；只改一处即视为失效。

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

### 7.1.6 会话过期回收（2026-10-18，`G-013` 超时实测的副产品）

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| R14 | **Sa-Token 过期会话键不回收** | **登记不修（低危）**。实测：`sa-token.timeout = -1`（token 永不过期）⇒ 会话键无 TTL；`active-timeout = 28800s` 超时后 `checkLogin` 正确抛「登录已过期」，但 `X-Admin-Token:login:token:<tk>` 与 `X-Admin-Token:login:last-active:<tk>` **两键 TTL 仍为 `-1` 且不被删除**，反复调用不复活也不清理。**影响面**：仅管理端登录会话（数量级小），且过期后行为安全（恒 401）；实际表现是 Redis 键**缓慢无界增长**。**不修理由**：① 主动 `logout` 路径（`AuthServiceImpl#logoutByTokenValue`）会正常清理，只在「不登出直接放到过期」时残留；② 修法要么改 `timeout` 为非 -1（会连带改变会话语义），要么新增清理任务（新增能力，收益不匹配）。**重估触发条件**：若管理端会话量进入千级、或 Redis 内存出现可见增长，再立清理任务 | `G-012` · `G-013` |

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
