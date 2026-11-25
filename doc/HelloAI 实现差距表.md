# HelloAI 实现差距表

> **状态：CURRENT GAP**
>
> 本文只记录**当前 → 目标**的真实差距；**只保留最新状态、随迭代就地更新**，不累加过程记录。
>
> **最后更新：2026-11-25**｜本次变更：`G-041` 硬删四件套落地（①~③）、`G-042` 附件作用域落地并**订正口径**（改存 `sub_task.context.mediaRefIds`，不写 `attachment` 双行）、`G-047` 联动订正、**`G-050` 立案并落地**（deepseek 图片能力修复：方案 A 改走 OpenAI 兼容通道，V156 反转 V155 的 text-only 止血）。逐条状态只看 §1 矩阵；**做过什么、为什么这么定看日志**——2026-09 见 `doc/archive/log/2026-09.md`，2026-10 / 2026-11 见 `doc/log/2026-10.md` / `2026-11.md`。
>
> **本头部只放「最后更新日期 + 指针」**，不再累加批次叙述。历史批次（`VU-x` 立项、`MA-x` 二轮挂点、`G-037` 真机验证、`deploy/quickstart` 覆盖缺口、`DV-x` 立项等）的完整过程见 `doc/log/2026-11.md` 对应 `LOG-2026MMDD` 条目；本轮三件（拆解等待页 4 态过程卡 / dev 清库重建 / 派生执行链真机验证）见 `LOG-20261112` 的 `#### G` / `#### H` / `#### I`。

# 0. 当前生效的口径与决策

> 只登记**仍然生效**的口径与决策；过程与订正历史见 `doc/archive/log/2026-09.md` / `doc/log/2026-10.md`。

**编号口径（全项目唯一，防撞号）**

| 编号 | 含义 |
|---|---|
| `G-001`~`G-050` | 本表差距项，逐条状态见 §1 总体矩阵（**唯一事实源**）。编号一经登记不复用；各条登记日期与立案背景属过程记录，见 `doc/log/` 对应月份文件。`G-001`~`G-020` 的编号含义见 §1；`G-021` 起的新增条目在 §1 行内保留立案缘由。**`G-050` = deepseek 图片输入静默失效修复**（2026-11-25 立案：专用 SDK 无 media 分支，方案 A 改走 OpenAI 兼容通道）。 |
| `P0`~`P3` | **架构重构主线阶段**（唯一含义）；审计优先级档已改用 `PR0`~`PR3` |
| `RM1`~`RM13` | 代码质量路线图（与目标架构能力层不再撞号）；**该计划已于 2026-10-17 归档 `Done`**（`doc/archive/implemented/HelloAI_RM存量缺口清偿计划（2026-10-04）.md`），号段不再新增 |
| `B1`~`B5` | 目标架构能力层（阻断项）；**4 项达成**（B1 单轨 / B3 Recover / B4 QualityGate / B5 用量与成本），**仅 `B2 Sandbox` 条件触发（暂缓）**（2026-10-18 复核：契约 + 五边界诚实标注 + 真实执行通道已建，`G-005` 三触发条件仍未命中） |
| `BASE-x.y` | 基础架构专项任务（`doc/archive/implemented/HelloAI 基础架构调整实施计划.md`，2026-10-12 已完成归档；编号已退役，不再新增） |
| `REF-x.y` | 借鉴落地专项任务（`plan/HelloAI 借鉴落地实施计划.md`，2026-10-09 建号）；`x` 取 1~8 —— **`REF-8` 内部 LLM 能力面**（2026-10-12 新增，`D-2026-10-12-1`） |
| `M-x` | **供应商 / 模型注册信息完善**（magpie 借鉴，2026-10-13 建号，`x` 取 1~9）：来源 `research/magpie_借鉴分析.md`，实施见 `plan/HelloAI 借鉴落地实施计划.md` **§11**。与 `REF-x.y` **并列**——`REF-` 号段 `x` 已占满 1~8，不扩号段 |
| `S-x` | **OpenSandbox 沙箱专项任务**（2026-10-17 建号，`x` 取 1~10）：`S-1`~`S-6` 实施见 `archive/implemented/HelloAI OpenSandbox 沙箱集成计划.md`；**`S-7`~`S-9` 沙箱执行链**（S-7 执行通道 / S-8 工具接线 / S-9 机械判定接入）实施见 `archive/implemented/HelloAI 沙箱执行链设计方案（2026-10-17）.md`；**`S-10` 危险命令守卫 = 后置登记不启动**（待执行通道被真实业务高频使用后评估误用面再启动，对齐 REF-8.9 独立预算闸同款处置）。与 `REF-x.y` / `M-x` 并列——`REF-` 号段不扩，另立号段（实测 `doc/archive/**` 无 `S-` 号段 ⇒ 不撞号） |
| `W-x` | **联网搜索效果优化专项任务**（2026-10-18 建号，`x` 取 1~10）：P0（W-1 编排器请求 answer / W-3 动态 freshness）+ P1（W-2 并行搜索 / W-4 搜索词质量）+ P2（W-5 Rerank / W-6 Grounding / W-7 模态卡）+ W-8~W-10（2026-11-04 追加：查询规划器碎片过滤 / AI Search 空结果回退 / 助理检索上下文决策）；实施见 `archive/implemented/HelloAI 联网搜索效果优化实施计划（2026-10-18）.md`（**2026-11-24 归档，全部完成**）。**P3 Agentic 多轮搜索 = 登记不启动**。与 `S-x` / `M-x` / `REF-x.y` / `T-n` 并列——实测 `doc/archive/**` 无 `W-` 号段 ⇒ 不撞号 |
| `ATT-x` | **需求对话附件专项任务**（2026-10-23 建号，`x` 取 1~8）：`ATT-1` 文本 + PDF 文本层 + 两层 Prompt 注入 / `ATT-2` 视觉直读（待开始）/ `ATT-3` 片段重排 / `ATT-4` 版面增强（暂缓）/ `ATT-5` 附件意图三态判定 / `ATT-6` 分析后检索 / `ATT-7` 附件预算窗口化 / `ATT-8` 两阶段检索。**逐项定义、状态与剩余动作以 `plan/HelloAI 需求对话附件实施计划（2026-10-22）.md` §3 任务表为唯一事实源**；设计见 `design/HelloAI 需求对话附件设计.md`。★`ATT-5` 的「已完成」只覆盖澄清链，工作台链的问题归 `G-043`。 |
| `PC-x` | **Paperclip 借鉴落地专项任务**（2026-10-23 建号，`x` 取 1~19）：`PC-1`~`PC-6` 现有机制补硬（env 黑名单 / 唤醒原因 / 409 语义 / 三配额分离 / 双入口契约 / 成本闸门）/ `PC-7`~`PC-11` CLI 推通道（适配器 / headless 契约 / 输出解析 / session 续接 / run 归一）/ `PC-12`~`PC-19` 有条件再做。实施见 `plan/HelloAI Paperclip 借鉴落地计划（2026-10-21）.md`。**建号动因**：旧稿用「阶段 + 序号」寻址（如「阶段二·1」），该地址不可被外部引用，且违反治理规则 §6.5「分期标签已冻结」——本次一并清偿，治理规则 §6.5 同步记录。与 `S-x` / `W-x` / `ATT-x` / `M-x` / `REF-x.y` 并列。实测 `doc/**` 无 `PC-` 号段 ⇒ 不撞号 |
| `TD-x` | **任务拆解领域无关化专项任务**（2026-10-24 建号，`x` 取 1~12）：`TD-1` `task.task_archetype` 六类拆分维度 / `TD-2` `sub_task.acceptance_kind` 四类验收判据 / `TD-3`~`TD-12` 见计划。**逐项定义与进度以 `plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md` §3 任务表为唯一事实源**；设计见 `design/HelloAI 任务拆解领域无关化设计.md`。 |
| `SKX-x` | **技能执行面接入专项任务**（2026-10-26 建号，`x` 取 1~10；2026-10-27 续至 15）：`SKX-1` 沙箱镜像基线 / `SKX-2` 容器内 `spawn` 行为验证 / ……至 `SKX-15`。**逐项定义与进度以 `plan/HelloAI 技能执行面接入实施计划（2026-10-26）.md` §3 任务表为唯一事实源**；★技能「加载」≠「执行」的边界见该计划与 `ADR` 相关条目。 |
| `RC-x` | **需求对话自由切换与并发互斥专项任务**（2026-10-30 建号，`x` 取 1~6）：`RC-1` 后端每会话 Redisson RLock 互斥（前置段整体串行化）/ `RC-2` 抢占失败错误语义 / `RC-3` 后端单测 / `RC-4` 前端流式态 `Map<conversationId>` 化 / `RC-5` 前端 `sending` 闸门降级为仅当前会话 / `RC-6` 端到端验收 + 登记回填。实施见 `archive/implemented/HelloAI 需求对话自由切换与并发互斥实施计划（2026-10-30）.md`（2026-10-31 已归档 `Done`，号段退役不再新增）。实施次序**先 B 后 A**——A 拆前端闸门前 B 先兜住并发竞写。与 `S-x` / `W-x` / `ATT-x` / `PC-x` / `TD-x` / `SKX-x` / `M-x` / `REF-x.y` 并列；实测 `doc/**` 全库无 `RC-` 号段 ⇒ 不撞号；**刻意不用 `FS-`**（只覆盖 A 半边、语义过窄） |
| `PA-x` | **个人助理工作台专项任务**（2026-11-02 建号，`x` 取 1~17；2026-11-05 续 `PA-13`~`PA-17`）：`PA-1`~`PA-12` 见计划；`PA-13` 模型偏好记忆 / `PA-14` 先思考后检索 / `PA-15` 工作概览上下文 / `PA-16` 重生成端点 / `PA-17` 消息操作。**逐项定义与进度以 `plan/HelloAI 个人助理工作台实施计划（2026-11-02）.md` §3 任务表为唯一事实源**；设计见 `design/HelloAI 助理对话与上下文窗口设计.md`；差距项 `G-028`。 |
| `RV-x` / `SR-x` | **核验双域改造专项任务**（2026-11-04 建号，`RV-x` 取 1~15；2026-11-13 续 `RV-8`~`RV-13`，2026-11-15 续至 `RV-15`）：`RV-1` 纯静态判定校验器 / `RV-2` 三态结论 `INCONCLUSIVE` / `RV-3` `coverage[]` 结构化 + 静态覆盖批评器 / `RV-4` 抽检独立性 / `RV-5` 判定快照 / `RV-6` 驳回侧抽检 / `RV-7`~`RV-15` 见计划。**逐项定义与进度以 `plan/HelloAI 核验双域改造实施计划（2026-11-04）.md` §3 任务表为唯一事实源**；差距项 `G-034`。 |
| `SL-x` | **沙箱生命周期与台账证据专项任务**（2026-11-11 建号，`x` 取 1~10）：`SL-1` 任务级作用域 / `SL-2` 终态销毁 / `SL-3` 工作区与回读 / `SL-4` 台账 `callId`+`origin`/`round` / `SL-5` 证据引用化 / `SL-6` 验证收口 / `SL-7` 容器寿命正确性 / `SL-8` 沙箱运行时接口化 / `SL-9` 职责拆分 / `SL-10` 审核隔离。**逐项定义、拆分与进度以 `plan/HelloAI 沙箱生命周期与台账证据改造计划（2026-11-11）.md` 为唯一事实源**；差距项 `G-032` |
| `MSG-x` | **站内消息与置顶公告专项任务**（2026-11-12 建号，`x` 取 1~8）：`MSG-1` 共用出口契约 / `MSG-2` `user_notification` 表与幂等索引 / `MSG-3` 钩子接线（挂 `TaskTimelineService.recordEvent` 写点后，一处覆盖两入口）/ `MSG-4` 首批映射登记 / `MSG-5` 前端铃铛 + 消息中心 / `MSG-6`~`MSG-8` 见计划（`MSG-7` 扩展覆盖列后续）。**逐项定义与进度以 `plan/HelloAI 站内消息与置顶公告实施计划（2026-11-15）.md` §3 任务表为唯一事实源**；设计见 `design/HelloAI 站内消息与置顶公告设计.md`；差距项 `G-035`。 |
| `MA-x` | **深度审核改造专项任务**（2026-11-18 建号，`x` 取 1~10）：`MA-1` 补 `parts` 读取方 / `MA-2` 候选集快照 / `MA-3` 技能执行台账 / `MA-4` 交付物登记闸 / `MA-5` 工具面按需加载 / `MA-6` 状态契约外化 / `MA-7` ACP 适配器小样 / `MA-8` 安全扫描只记不拦 / `MA-9` 敏感动作审批 / `MA-10` 执行链超时治理。**十项的定义、排序与逐项状态以 `design/HelloAI 深度审核改造蓝图.md` 为唯一事实源**，各子号段（`MA1-x`~`MA5-x`/`MA8-x`/`MA10-x`）进度以对应计划 §3 任务表为准；**刻意不用 `B-`**（已被目标架构能力层占用）、**不用「阶段 / Phase」**（治理规则 §6.5 分期标签冻结令） |
| `GT-x` | **git 能力集成专项任务**（2026-11-24 建号，`x` 取 0~12）：`GT-0` 可行性实测前置 / `GT-1`~`GT-3` JGit 嵌入 / `GT-4`~`GT-6` 渐进式提交 + reviewer 沙箱验证 / `GT-7`~`GT-9` 拉分支集成测试（承接 `G-027`⑤⑥） / `GT-10` **并行轨·部署形态能力**（app 容器工作区可见性；**不阻塞主链开发、只阻塞容器形态验收**） / `GT-11` 分支谱系与 attempt 接力 / `GT-12` 单向 publish 与工作区前进。**逐项定义与进度以 `plan/HelloAI git 能力集成实施计划（2026-11-24）.md` §3 为唯一事实源**；**三条不可动摇的约束**：每个 ref 只有一个写者（平台只做单向 publish，禁 sync）、分支必须扁平单段且合并禁 squash、`GT-10` 与主链并行互不为前置 |
| `DV-x` | **依赖与矛盾校验专项任务**（2026-11-24 建号，`x` 取 1~3）：`DV-1` 依赖方向正确性规则 / `DV-2` 批内互斥验收检测 / `DV-3` 语义闸结论留痕。**设计与实测锚点以 `design/HelloAI 任务依赖与矛盾校验设计（规划期双闸）.md` 为唯一事实源**（规划期双闸：规则闸 `DraftGateChecker` G1-G3 + LLM 语义闸 `SemanticContractGate` G4，运行期 `RuntimeContractGate` G5）；现状=待批准立项。 |
| `VU-x` | **视觉验收与沙箱浏览器专项任务**（2026-11-24 建号，`x` 取 1~10；`VU-10` 于 2026-11-25 追认补号 = 图片能力判定收口）：`VU-1` 视觉直读通道 / `VU-2` reviewer 证据装配带媒体 / `VU-3` 沙箱浏览器 / `VU-4` 运行时 UI 验收判据 / `VU-5`~`VU-7` 受 `G-005` 条件③ 触发约束 / `VU-8` 执行面输入源 / `VU-9` 对话面媒体链 / `VU-10` 图片能力判定。**逐项定义、状态与判据以 `plan/HelloAI 视觉验收与沙箱浏览器实施计划（2026-11-24）.md` §3 任务表为唯一事实源**；事实输入见 `plan/运行时 UI 验收能力待核清单（2026-11-05）.md`（`RUI-1`~`RUI-10`，只核事实、不承载执行路线）；差距项 `G-038`。 |
| `ADR-x` | **架构决策记录**（`design/adr/ADR-00x-*.md`，三位数字）：`ADR-001` Run/Turn/Step / `ADR-002` 嵌入模型供应商 / `ADR-003` OpenSandbox 部署拓扑 / `ADR-004` 沙箱执行链与保真闸 / `ADR-005` 沙箱生命周期复用 / `ADR-006` app 加入沙箱网络 / `ADR-007` 技能执行异步化 / `ADR-008` 派生式子任务 / `ADR-009` A2A 兼容边界（见 `G-036`）。`ADR-*` = 决策本身、长期有效；`ARCH-*` = 决策发生的记录（`log/HelloAI 架构变更记录.md`）。**已作废**：`TD-16a` 的 `blockedBy` 契约、`TD-16d②`、`TD-16e-3-1b`、`TD-16e-4`、`TD-16f` |
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
> | ⑧ | **真身灰度联调**（原口径名「灰度第 0 步」，2026-10-09 补登记） | P0-B 双轨切换的联调执行口径（现已终结：灰度机制随单轨硬切不复存在） | `第 0 步`（2026-09-08） | `doc/archive/log/2026-09.md` `LOG-20260908-006`；`archive/implemented/HelloAI 重构实施计划.md`（已改称能力名） |
>
> **已知同号冲突（去标签的直接动因）**：①↔② 对 `Phase 2` 定义不同（① = Tool Executor，② = AgentLoop 多步化）；①↔③ 撞号（`Phase 2E/2F/2H` 属命令链）；`Step`／`步` 多义（① 能力供电 / ⑥ 开发流程 / ⑦ 字段值 / ⑧ 灰度联调）；`阶段` 二义（④ 报告 / ⑤ 里程碑）。
>
> **处置（2026-10-09）**：①~⑤ 已在**活文档与生产代码**中去除裸标签（保留能力名、`ADR-*` / `LOG-*` / `N-0xx` 等**durable 锚点**）；`doc/archive/**` 与 `doc/review/**`（历史现场 / 报告）**保留原样**；⑥ 保留；**⑧ 于 2026-10-09 补登记并同批去标签**（`archive/implemented/HelloAI 重构实施计划.md` 三处改称「真身灰度联调」+ 挂 `LOG-20260908-006` 锚点）。

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
| G-005 | Sandbox Provider | **OpenSandbox Provider 现役并被执行链消费**（`S-1`~`S-9` 完成，`OpenSandboxProvider` + 声明式 spec + 五边界策略 + 探针；`RuntimeTurnExecutor` 经 `SandboxProvider.resolve` 使用）；`S-10` 危险命令守卫未实现；技能执行链已在容器内真机跑通（`internal_run_skill` 模型自主触发，SKX-9 已验真） | 真正的 Provider 化执行环境与隔离策略 | PR1 | 暂缓 | 条件②已命中，重排期待议。**2026-11-06 事实并入**：Provider/执行链事实见 `design/Sandbox_Provider.md`，实施记录归档于 `archive/implemented/HelloAI OpenSandbox 沙箱集成计划.md`；**触发条件①「平台增加碰宿主工具」③「平台自持浏览器」仍未命中**；SKX-9 判据③外链口径经用户裁定保留署名、技能按通过。详见 `plan/HelloAI 技能执行面接入实施计划（2026-10-26）.md` §SKX-9 |
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
| G-017 | 附件存储一致性 | `ArtifactStorage` 双实现 + 注册前置校验 + 6h 对账巡检（三类引用源）+ 孤儿清理三重保险（默认关闭） | DB 与对象存储两侧一致，悬空 / 孤儿可发现可修复 | PR1 | 部分完成 | 部署侧改 MinIO 默认凭据 + 端口白名单；技能包来源正面样本待造（本机为空集） |
| G-018 | 备份 / 恢复 | pg_dump 全库 + 对象清单 + manifest peek + 单飞锁 + 恢复三门 + 停机 runbook | 全库与对象可备份可恢复；运行中备份不保证多文件同一瞬间 | PR1 | 已完成 | — |
| G-019 | RAG 知识库 | `kb_chunk`（vector + HNSW-cosine）+ 余弦检索 + `search_knowledge`（无 KB 即摘）+ 注入预算 + 引用溯源 | PG 单后端检索 + 条件可用语义位 + 引用溯源可点开 | PR2 | 已完成 | — |
| G-020 | 外部 Agent 工作详情快照 | 完全空白：只能审计到订单层面，子任务内部执行为黑盒 | 事后、经审批、可选提交工作详情快照 → 原文落 MinIO + 结构化审计事件 | PR2 | 待开始 | 动手前须定三项约束：与审计 schema 的张力 / 审批方 / 落时间线粒度 |
| G-021 | 内部 LLM 能力面 | 工具面已解封（读详情 / 依赖 / 状态 / 附件、交付登记、技能目录与惰性加载、自报阻塞）；五个业务面进入循环，其余十一面留单次 | 内部 LLM 有工具、有手脚、能按需装配技能 | PR1 | 已完成 | — |
| G-022 | 选人技能门（命名空间一致性） | 引用边已补（`requiredBaseCapabilities` + `satisfiesPackage` 唯一判据）；注册链派生；拆解侧承接者校验 | 技能门两端同口径：声明侧声明即有人具备 | PR1 | 部分完成 | 存量 `agent.skills` 回填（须重注册触发）；`eng-*` 装进内部 LLM 工具授权集 |
| G-023 | 内部 LLM 运行时合同 | 取消 / 超时树 + max-tokens 独立终态 + usage 五分量拆分均已接线；独立预算闸未做 | 可取消可超时、截断不静默成功、用量可拆解 | PR1 | 已完成 | — |
| G-024 | 附件与联网检索的编排 | 决策视图注入 + 模板「附件优先」四条硬规则 + 元词过滤（含兜底同源判定）+ `ATT-5` 附件意图三态（真机三轮验证：`answerable` 不联网 / `none` 不注入正文 / `needs_external` 带锚点检索）+ `ATT-6` 补搜多变体（**真机通过**：检索词为附件外部锚点、补搜 3 条不同角度、回答区分附件结论与外部核验，另补「首轮满额短路」防白付评估 LLM）+ `ATT-8` 进度文案取真实候选词（**真机通过**：检索词「规划 → 发进度帧 → 再执行」两阶段，进度帧与结果帧同源，不检索时显示跳过原因；另 `ATT-3` 片段重排接入附件检索并真机通过：开启有重排日志 / 关闭保持向量序，开关默认关） | 检索词由附件外部锚点构成；附件无可核验实体时不触发联网 | PR1 | 已完成 | — |
| G-025 | 任务拆解的领域无关化 | 拆解只有一档：静态 DAG + 软件交付语法（`planner-decompose.md` 契约先行/验收模板均为研发语境）；`GRANULARITY` 只控「拆多细」不控「按什么维度拆」；`task` 表无领域或交付物类型维度；拆解输入仅 `title`+`description`+`requiredSkills`+`difficulty`+需求包；验收 `acceptance` 为自由文本、无判据类型 | 按交付物判据分档：`task.task_archetype` 六类（BUILD/DOCUMENT/RESEARCH/DATA/OPERATE/DECIDE）+ `sub_task.acceptance_kind` 四类（AUTOMATED/STRUCTURAL/REVIEWED/ATTESTED）+ 每类对应的拆分维度与终止锚 + 拆解期上下文快照 + 「计划未知」任务的 ledger 通道 | PR1 | 部分完成 | `TD-1`~`TD-9` 已收口（archetype/kinds 落库与校验、提示词分档、写侧补列、stall 收敛、草案细拆与重写）；`TD-11`（验收契约可满足性标注，只标不拒）与 `TD-13`~`TD-15` 见 `plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md` §0 ④ 及 `LOG-20261024`~`LOG-20261108`。**剩余**：`TD-13` 的 `S0`/`S5`/`S6` + `S3` 展示面（任务详情「被拦规则明细」、子任务「自检告警」空态、任务列表「未通过自检」角标、`review_record` 侧前置展示）；`TD-10` 登记不启动 |
| G-026 | LLM 额度与用量监控 | 4 家外部供应商（deepseek / moonshot / minimax / dashscope）余额分散在各自控制台，平台侧仅能通过「调用失败」感知额度耗尽；`credential_vault` PLATFORM 级 10 条凭据（deepseek 3 / moonshot 3 / minimax 2 / dashscope 2）；博查 key 另存 `sys_config[web-search.bocha.api-key]`；`agent_completed` 的 `usage` 字段 **0 条落库**、token 仅单值无 model 维度 | 4 家（deepseek / moonshot / minimax / 博查）余额在平台内一处可查，每项带抓取时间与失败标记；通义无公开接口故不显示；只读悬浮控件 | PR2 | 已完成 | — |
| G-027 | 项目级交付编排 | 平台只有「一次任务 → 静态 DAG → 派发 → LLM 验收」内循环：无项目级计划实体（planner 域 entity 仅 `ChatAttachment`/`ChatSkillRun`/`RequirementConversation`/`RequirementMessage` 四个会话相关表）；平台进程碰不到宿主仓库（唯一 `ProcessBuilder` 为 `PgDumpRunner` 备份专用）；沙箱 `network-policy: deny` 且无仓库持有模型；代码类验收无可执行判据（LLM 判 `pass/score`，fail-open） | 计划双表示（Document + Spec）+ 滚动式拆解（Phase 基于当时 `baseCommitSha` 细拆）；可执行验收机（命令退出码为权威，LLM 只判意图）；`git worktree` 隔离 + Git 权限分级；`touchedPaths` 交集判并行安全；Context Bundle 按任务组装约束；成本预算内建 | PR2 | 设计中 | 设计成文（`design/HelloAI 交付编排能力设计.md`，`Status=Planned`）；出 `plan/` 批次；`V1`~`V5` 待验证；**2026-11-24：Git 线（⑤⑥：worktree 隔离 + 权限分级 + 短期凭证 + 合并验收机）实施承载体立项 = `plan/HelloAI git 能力集成实施计划（2026-11-24）.md` `GT-7`~`GT-9`** |
| G-028 | 个人助理工作台 | 助理对话与需求对话按 `kind` 分流（独立 `assistant` pipeline，读写隔离）；工作台 = 左对话 + 右栏三段（任务概况 / 最近任务 / 最近产物），产物三源归一（执行产物 + 会话附件 + 技能产物）；用户归属模型（`owner`）覆盖会话 / 任务 / 长期记忆；会话级模型钉住（`requirement_conversation.model_type` + `AgentModelPin` 覆盖入参，选「无角色纯 LLM 模型」与 Planner 选择彻底分离）；上下文窗口 token 分项占用 + 手动/自动压缩；`PA-13`~`PA-17` 补模型偏好记忆 / 先思考后检索 / 工作概览注入 / 重新生成 / 消息操作条 | 个人助理成为平台一等入口：能对话、能看全局、能取产物，且不新增第二套执行链 | PR2 | 部分完成 | `PA-8`~`PA-10` 登记不启动（自动化定时任务 / 插件模板市场 / IM 与移动端）；详见 `plan/HelloAI 个人助理工作台实施计划（2026-11-02）.md` |
| G-029 | 内部 LLM 跨文档核验（沙箱证据与上游引用） | 跨子任务核验类验收要求「引用上游 `storageUrl` + 文档名/章节/行号」，但沙箱按子任务隔离、上游产物只以**截断文本**注入；执行器在沙箱内 `ls /`、`find *.md`、探测 `127.0.0.1:8080/3000/9000` 均找不到附件，返工后改为在沙箱**重建**上游文档；自审闸门按命令文本精确匹配 SR-5 台账，出现 `mismatch`（自报命令无匹配）与 `missing`（自报 0 / 台账 23）后模型反复执行同一条命令（`cat verify_out.txt` 同 digest `5c94c878…` 出现两次），最终报告缺 `VERIFICATION` 围栏 → `agent_runtime_missing_verification` → `BLOCKED`。真机样本：`task=2120174471616131074` / `subTask=2120174821500776454` | 跨文档核验可满足：①上游附件以 `storageUrl` 列表 + 只读工具注入（不依赖沙箱文件系统）；②自审台账按 digest/命令前缀归一匹配，并允许「本次 attempt 已执行」复用；③文档类交付输出契约拦截工具调用残留/思维链；④长核验子任务按模型能力路由（MiniMax-M3 易写阻塞叙事） | PR2 | 待开始 | 见 `LOG-20261110`；本轮迁移验收按**环境/内容缺陷**收口，不计为迁移失败；**2026-11-15 关联进展**：`RV-14`（stage-in）已提供「附件注入沙箱」的通道形态（`SandboxExecutionService.writeArtifact`），与期望①「不依赖沙箱文件系统找附件」同向——但 stage-in 归属硬校验现限**本子任务**有效附件，G-029 要的**上游**附件注入需先做任务级附件列举，本行维持待开始（见 `LOG-20261115` #### B） |
| G-030 | 自审闸门失败未 fail-close | `FinalReportFenceHook#tryFinalizeRound` 在 `self_review_failed`（重跑耗尽、`gatePassed=false`）后仍返回 `AgentLoopResult.stop(...)`，交付物照常 `execute_submit`；真机 `subTask=2120174821500776454` 第一次执行：`self_review_evidence_checked(gatePassed=false)` → `self_review_failed(retryCount=1)` → `llm_call_end success=true` → `execute_submit`，直到 reviewer 驳回。第二次被拦是“缺 VERIFICATION 围栏”的另一条 fail-close，不是自审闸门 | 自审闸门失败 = 不可提交：返回 FAILED 态，走既有 BLOCKED/退避重派链；错误结果不得进入评审或落附件 | PR1 | 待开始 | 真机 `task=2120174471616131074`；当前设计注释明写“只留痕、不改出口”，需用户裁定改口径 |
| G-031 | Task-Team 成员表运行期无写入 | 原状：`TaskAgentMemberService.register` 无生产调用方，唯一写入是启动时 `TaskAgentMemberRebuildRunner`；根因不是「启动重建漏跑」而是运行期从未挂入队钩子。修法：在状态写入唯一入口 `SubTaskStateMachineServiceImpl` 同事务登记——`changeStatus(...agentId)`→`ASSIGNED`、`claimAtomic`→`CLAIMED`、`reassign`/`rework`/`reworkFresh` 换人→`REASSIGNED`；成员行写失败随归属事务整体回滚 | 参与成员展示与运行期一致：归属一变更即实时入队，且保留重建/读时兜底 | PR1 | 已完成 | — |
| G-032 | 沙箱生命周期与台账证据 | 原状：沙箱按子任务、turn 结束即 `release`，rework/下游看不到上一轮与其它子任务文件；自审证据靠模型手抄命令做文本前缀匹配，省略式命令必然 mismatch。现形态：槽键升格 `(Kind, scopeId, specFingerprint)` + 任务级解析，销毁时机改为任务终态（`TaskTerminalStateEvent` → `SandboxTaskTerminalListener`）；容器寿命拆 `keep-alive` / `idle-ttl` 两段，`/workspace` volume 化并区分 reviewer 只读槽；台账加 `callId`/`origin`/`round`，`EVIDENCE` 围栏由手抄命令改引用式 `{"kind":"sandbox_exec","callId":"c17"}`，对账按 `callId` 精确命中。实施见 `plan/` 沙箱生命周期改造计划（`SL-x`），决策见 `ADR-005` | 任务级沙箱（`taskId→sandbox`）+ 任务终态销毁；证据改为平台生成的 `callId` 引用台账条目，模型不再手抄命令 | PR1 | 已完成 | — |
| G-033 | 执行环境维度：拆解侧闭环、执行侧与派发校验未做 | 拆解侧已闭环（`ExecutionEnv` 四态判别 + 防御解析、`planner-decompose.md` env-first、主拆解与单条重写双渲染点、二次拆解种子与 `inheritExecutionEnv`）。**执行侧未做**：沙箱工作区仅 `/workspace/draft.md`，仓库不进沙箱；**派发校验未做**：无 `ExecutionPrereqChecker`，技能 AND 硬匹配不派发 ⇒ 无原因 `PENDING`（比 `BLOCKED` 更难排查）。架构基准：执行器是技能型执行（宿主循环 + 持久 shell + 文件工具直读），无「repo 拷贝进工作区」环节 | 拆解/继承侧保持闭环；执行侧按调查结论定案（仓库注入成立与否、或任务级共享工作区即足）；派发校验 = 派发前比 `accessType` + 执行环境，不匹配即 `BLOCKED` 且带原因 | PR2 | 待开始 | ①「仓库注入」适用性调查已完成，git 能力已立项 `GT-1`~`GT-12`，实施见 `plan/HelloAI git 能力集成实施计划（2026-11-24）.md`（第 2/3 步含 `ExecutionPrereqChecker`，第 5 步为单向发布）；②`ExecutionPrereqChecker` 派发校验（`GT-5`，含 git 四字段缺项拦截）；③`CreateTaskRequest` 写 `context` 的通道（现唯一可行写入路径是直改库，`GT-5` 前置） |
| G-034 | 审核侧证据可见性与驳回依据可达性 | 代码复勘六条：①附件注入只有自然语言 `[TRUNCATED] file=… total=… reason=…`，无附件 id、无注入行区间 ⇒ reviewer 既无法引用也无法定向补读；②注入按 `create_time DESC` 先到先得，主件与分片族结构上必然溢出，`ATT-3` 的「均分保底 + 余量回收」配额算法未用于核验侧；③`ReviewCoverageCritic` 只核 `pass=true` 的覆盖缺口，驳回侧无「缺陷依据是否可见」闸；④`INCONCLUSIVE` 路由与「不返工」已存在，但缺「只引用不可见证据的缺陷 ⇒ 降级 `evidence_unavailable`」判据；⑤`coverage.method` 白名单仅 `attachment`／`sandbox`，无 `mechanical`；⑥`internal_read_attachment` 被剪枝为头 4,096 + 尾 1,024 码点，反向劣于注入的 64k | 证据可见性结构化（`EvidenceLedger`：id + `injected` 区间 + `absent` 原因，随 `review_record.snapshot` 冻结）；预算按证据族配额 + 交付物优先 + 区间注入；表格类走确定性机械预检（行数/聚合/报告声明比对，输出 `[MECHANICAL_CHECK]`）；驳回侧 `evidence_unavailable` 降级 `INCONCLUSIVE` 且不计返工；有界分页/摘要工具；注入规则对执行方同源可见（`verify-executor-doc-parity.sh` 守卫） | PR0 | 验证中 | 代码与单测见 `plan/HelloAI 核验双域改造实施计划（2026-11-04）.md` 的 `RV-8`~`RV-15`：`RV-14` 附件惰性注入沙箱 stage-in 与 `RV-15` 审核证据自动物化均已通（`ReviewEvidenceMaterializer` 三闸护栏、`maxFiles=40`，真机「无指路」闭环 APPROVED）。**剩余**：上游附件任务级列举（stage-in 归属范围现限本子任务）、真实 35 附件批回放（`balance` 口径校验与机械比对）待补证 |
| G-035 | 站内消息与置顶公告 | 人侧消息载体已建（`user_notification` / `announcement`）。收口形态：单一出口 `NotificationService.publish`（开关闸 → 幂等键 → 落库 → `AFTER_COMMIT` 推送）；钩子挂在 `TaskTimelineServiceImpl.recordEvent` 写点后、`recordEventInNewTx` 委托覆盖两入口；白名单六类 `PLAN_DRAFT_READY`／`FINAL_REPORT_READY`／`SUBTASK_SUBMITTED`／`SUBTASK_DEAD_LETTER`／`SUBTASK_NO_CANDIDATE`／`FINAL_REPORT_REVIEW_STUCK`；前端铃铛停靠与悬浮二选一 + 抽屉（筛选/全部已读/归档）+ 深链；置顶公告三表 + 版本已读 + revision 快照；两开关 `notification.enabled` / `announcement.enabled` 默认 OFF（停发 + 隐藏） | 单一出口 `NotificationService.publish` + 事件码→消息白名单映射 + 首批 A1/A3/A5/B1/B2 + 铃铛/抽屉/工作台入口 + 置顶公告（版本已读）+ 两平台开关（默认 OFF、停发 + 隐藏）+ SSE `AFTER_COMMIT` 信令 + 多副本幂等（DB 唯一索引 `COALESCE(receiver_id,0)`） | PR2 | 已完成 | — |
| G-036 | A2A 兼容边界 | 对外执行通道只有自建 MCP 13 工具，与 A2A（v1.0.1）三层不对齐：**参数级**无 `contextId` 会话分组维（`derived_task_id` 是层级关系，非会话分组）；**操作级**无 cancel；**结构级** `submitResult.output` 为纯 `String`，`uploadArtifact` 只收平台桶内 `minio://`。状态机 12 态 vs A2A 8 态，`REWORK`/`AWAITING_DERIVED`/`RESTRUCTURING`/`PAUSED`/`REVIEW`/`DEAD_LETTER` 无落点。外部 executor 仅 2 个，接入门槛 = 实现 13 个工具 + 读 SKILL.md + 维持心跳 | **双平面**：A2A 只做口岸（身份/任务/产物三次翻译），私有 MCP 仍是管控面，**12 态内核一个不改**。投影三原则：无对应态挤 `WORKING` + `status.message`；终态只在 `DONE`/`CANCELLED`/`DEAD_LETTER` 发出；`BLOCKED` 不投影 `INPUT_REQUIRED`。**A2A task 身份 ≠ subTask 身份**（一次交付尝试 = 一个 A2A task）⇒ 终态不可重开与本仓可重跑不再矛盾 | PR2 | 部分完成 | 边界唯一事实源 `ADR-009`；待验问题 `AQ-1`~`AQ-13` 见 `research/HelloAI A2A 兼容与私有 MCP 改造调研清单（2026-11-16）.md`（结论以 ADR 为准，清单不含结论）。**A 档四洞已全部落地**（`cancelSubTask` 三通道 13→14、`submitResult.parts`、对外保名 `contextId`、`sessionId` 解耦 `authSessionId`），证据见 `archive/implemented/HelloAI ADR-009 A档第一批实施计划（2026-11-17）.md`。**B 档**（出站 client：`A2A_AGENT` + push + 远端 taskId 对账）待主线闭环；**C 档**（入站 server）被 `G-033` 阻塞。**不做**：压状态机 / 「A2A 模式」开关 / 复用 `WorkspaceRef.contextId` / 另建任务表 / 未解 `AQ-3` 就接自动派发 |
| G-037 | 任务约束类型化 | 子任务约束原为 `sub_task.constraints` 单值 TEXT，拆解时 LLM 即兴生成、无文档级契约承载；输入侧无附件/文档引用通道（`AttachmentPortAdapter` 仅产物登记，方向相反）；`agentPolicy` 只承载派发参数。落点（观察期，不标已完成）：`V154` 两表已 `jsonb`；`ConstraintDocCatalog` manifest 白名单 + 章节按标题匹配（行号漂移免疫）+ `internal_list_constraint_docs` / `internal_load_constraint_doc`（目录/正文分离，fail-open）；拆解声明 / 执行引用 / 审查带引用三环流转。内外组双通道真机已通过，但『声明环』**未触发**——planner 对诊断类任务输出 `[]`；「规范使代码改得更好」**未证实**（本批为诊断非改造） | `constraint_refs` JSONB（`[{kind,ref,scope[]}]`，`TEXT` 内联裁决 / `DOCUMENT` 文档引用 / `SKILL` 技能预留）+ 约束文档目录白名单（manifest + docRoot 解析 + 章节按标题匹配，行号漂移免疫）+ 执行侧双工具（目录/正文分离，fail-open）+ 拆解声明 / 执行引用+按需读取 / 审查带引用三环节流转 | PR2 | 部分完成 | 正式引入验收（改造类任务真机：声明环 + 内外双通道 + 审查全通 + diff 对照 scope 自检矩阵抽样合规）；「规范使代码改得更好」行为验证 = A/B/C 对照实验另立观察项，方案见 `design/HelloAI 任务约束类型化设计（正式引入）.md`。三观察点修复（声明引导去宽松出口 + `{{CONSTRAINT_DEFAULT_SET}}` 默认契约注入、manifest 补 `sandboxPath`/`repoPath`、评审 coverage 强制证据位置引导）见 `LOG-20261124` |
| G-038 | 视觉验收（运行时 UI 证据）能力全空白 | **三条证据轴缺第三条**：①代码配置 → git diff（`GT-x` 在办）；②文档报告 → 结构断言（`RV`/`SR` 已交付）；③**界面/游戏网页 → 浏览器产证据 + VLM 读证据**——本项即③，平台侧全空白。四个断点：ⓐ模型通道无多模态（全仓 `Media`/`image_url`/`vision` 零命中，`AgentLoopInput` 无媒体字段）；ⓑreviewer 证据原不带媒体（`EvidenceContent` 三分量无媒体位）；`VU-2` 已扩为五分量并随消息带图（无视觉模型仍保留「从严核验」原文案）；ⓒ沙箱无浏览器（`SandboxSpec` 无「长驻服务」表达，Playwright 须先起常驻 server，预设镜像不带 browser）；ⓓ无 `AcceptanceKind.RUNTIME_UI`。跨域端口 `AttachmentPort` 已现成 ⇒ 缺的不是端口。子项 `VU-1`~`VU-9` 见剩余动作 | **三通道 + 一条全链路**：`VU-1` 视觉直读（`List<MediaRef>` 传引用不传字节；**media 为空必须走原路径**）/ `VU-2` reviewer 证据带媒体（第 4 分量 + 按能力分支改文案，**原「从严核」不得删**）/ `VU-3` 截图存储与引用（冻结快照只存引用；**截图绝不进 git**）/ `VU-4` 沙箱浏览器能力面（独立 `spec-presets.browser`，禁 `file://`）/ `VU-5` `AcceptanceKind.RUNTIME_UI` 全链路 / `VU-6` 失败语义收口（能力缺失落显式 `INCONCLUSIVE` 或转人工）/ `VU-7` 成本与测试分层 / `VU-8` 用户上传图片通道 / `VU-9` 对话面视觉直读 | PR2 | 部分完成 | 实施见 `plan/HelloAI 视觉验收与沙箱浏览器实施计划（2026-11-24）.md`（`VU-1`~`VU-9`）；事实输入 `RUI-1`~`RUI-10`（只核「能不能做到」）。**已交付**：`VU-1` 执行链（`AgentContext.media` + `ChatModelToolLoop` 空非空分支）、`VU-9` 对话面（`AgentTask.mediaRefs` + `applyUserMessage` 三态）、`VU-8` 执行面输入源（`ConversationMediaRefPort` + `RenderedPrompt` 一次产出）、`VU-2` reviewer 证据带媒体（真机验收：有视觉能力读到图中编号，无视觉保留从严核验）。**剩余**：`attachment` 输入类禁入（契约见 `invalidateBySubTask`）；轮次留存归 `VU-3`；`VU-4`/`VU-5` 须待 `G-005` 条件③ ADR；`VU-7` 阈值须待 `RUI-9` 成本数据。与 `GT-x` 并行互不为前置，唯一交叉点 = 前端代码任务 |
| G-039 | 任务全局上下文无差别注入已完成子任务产出 | `task_running_spec.context_summary` 由 `TaskRunningSpecServiceImpl.compileSummaryFromRecords` 编译为「已完成 N 个子任务：标题 + 产出摘要」的**自由文本**，经 `TaskRunningSpecPromptRenderer.render` 注入**同任务下每一个后续子任务**的 prompt（`AgentRuntimeContextAssembler.buildUserPrompt` 无条件前置）⇒ 同一任务内子任务**互相可见彼此结论**，且**无开关、无标记、无法从 prompt 上分辨**。实测后果：对照实验中已声明 text-only 的模型从别的模型输出里读到答案，还写脚本把它硬编码进去「验证一致性」——**编造**而非读图，实验结论作废。任务完成上报等场景需要这份摘要是特性，但「各自独立判定」类任务会被确定性污染 | **区分「全局进度」与「前序产出正文」**：进度可保留（谁做完了、成功与否），产出正文须按依赖关系收窄——依赖图**有边**才注入（`buildDependencySection` 已实现该语义），**无边不得借 `context_summary` 旁路注入**；并在 prompt 里显式标注「以下内容来自其他子任务，非本任务输入」 | PR1 | 待开始 | ①`compileSummaryFromRecords` 与 `buildDependencySection` 的职责边界重划（摘要只留标题+状态，产出正文归依赖段）；②摘要注入加「非本任务输入」标记；③补单测：同任务两子任务无依赖关系时，后者 prompt **不含**前者产出正文。★这是**产品缺陷不是实验瑕疵**——任何人做对照实验都会踩 |
| G-040 | `POST /api/admin/agents` 不推导 `accessType`，建出非法组合 | `agent.access_type` 建表默认 `CLI_CLIENT`（`V1__init_all.sql:100`），而 `AgentServiceImpl.registerWithExtras`（`:367-380`）**只校验并设置 `modelType` / `modelConfig` / `skills`，从不触碰 `accessType`** ⇒ 通过管理端建一个 LLM 执行器，得到 `access_type=CLI_CLIENT` + `model_type=moonshot:kimi-k3` 的**非法组合**。该 Agent 派发后子任务停在 `ASSIGNED`、时间线**零事件**、完全不进自动执行链（`CLI_CLIENT` 靠心跳驱动在线态，而它永远不会有心跳）；无报错、无日志，表现为「派发了但什么也没发生」。对照：`updateAgentDetail:409-411` 有 `switchAccessType` 可纠正，故实测中的绕过办法是建完再 `PUT updateById` 补 `API_KEY_LLM` | 注册入口按 `modelType` 是否非空推导 `accessType`（`modelType != null && !blank` ⇒ `API_KEY_LLM`；纯 `CLI_CLIENT` 场景须**显式传**接入类型，不得靠表默认值兜底）；并与 `switchAccessType` 同一套校验（同角色同模型唯一、能力补齐） | PR1 | 待开始 | ①`registerWithExtras` 加 `accessType` 推导（或新增显式入参，禁止沉默默认）；②`validateModelType` 补齐反向校验——`CLI_CLIENT` 不允许带 `modelType`；③补单测：建 LLM 执行器后断言 `accessType=API_KEY_LLM`；④管理端建 Agent 表单同批加接入类型选择 |
| G-041 | 会话附件删除未彻底回收 ⇒ `VU-8` 判据③ 不成立 | **原状**：`RequirementClarifyServiceImpl.delete` 只删会话与消息，**不碰 `chat_attachment`、无级联外键**（`conversation_id` 无 `REFERENCES`）。执行面取图只过滤附件自己的 `deleted` 位、**不校验会话是否还活着** ⇒ 会话删了附件行仍 `deleted=0`，照旧把图送给模型。**复核订正**：单条删除实为「软删行 + 对象真删」各取一半，而 `selectStorageRefAllIncludingDeleted` 不加 deleted 过滤 ⇒ 每删一条永久造一条 **dangling** 且无法自愈。 | 会话删除后 `chat_attachment` 无该会话的存活行、无 `deleted=1` 残留行，桶内对象已回收；对账 `dangling` 不因删除而增长；执行面该路径自然返回空集合 | PR1 | 部分完成 | ①✅ 硬删四件套的①~③已落地：物理删行（绕开 `@TableLogic`，新增 3 个 Mapper 方法）、提交后批量 purge（护栏查询提到循环外）、跨表护栏复用 `G-047`。②✅ 调用点收敛**两处**（订正：`abandonByDerivedTask` 只改状态不删会话，不清附件）。③✅ 单测 130 例 + 两组反向验证。④待执行：`scripts/sql/purge-chat-attachment-soft-deleted.sql` 第 2 段 DELETE（幂等四段，存量已统计=19 行软删残留全为 VU-8 探针图，第 0 段只读已跑，**清理段待用户确认后执行**）。⑤✅ **真机验收已闭合**（2026-11-25）：目标会话 2122155243474427905 经 abandon→delete 后，附件行（含软删）物理清零、会话行/消息行软删、MinIO 桶 `vu8-test-red` 零命中；哨兵用例已从「断言仍命中」改为「断言空集合」并记真机证据，20 例全绿；另复查存量 19 行软删残留（VU-8 探针图）待批处理。 |
| G-042 | 执行面（子任务装配）无附件作用域，会话图全量投喂 | `ConversationMediaRefPort.listConversationImages(Long taskId)` **签名字段里没有任何作用域参数**，适配器直接 `listByConversation` 取全会话图片。对比对话面 `ChatAttachmentPromptRenderer#resolveScope` 有三层口径（用户勾选 > 模型推断的 `attachment_targets` 序号 > 全量回落），执行面**一层都没有**。⇒ 多图会话下每个子任务装配上下文时都拿到会话全部图片（且受 `G-046` 截断影响实际只拿到最老 5 张），prompt 被无关图占满，模型易把 A 图需求落到 B 子任务上。 | 执行面具备「本子任务相关图」的作用域：拆解时 Planner 为每个子任务声明所需附件，落 `sub_task.context.mediaRefIds`；装配时按该清单取图，无声明才回落会话全量并**显式标注回落原因**（模型必须知道拿到的是全量而非专属）。 | PR1 | 部分完成 | ①✅ **口径订正**：改存 `sub_task.context.mediaRefIds`（不写 `attachment` 双行——该表六处消费均以「一行=子任务产出」为前提，双行会让交付物闸恒判「有产物」，与设计 §3.1 同结论）。②✅ 拆解侧序号化（`attachmentRefs` + 提示词三处同改 + 解析落 context）。③✅ 清单与 prompt 一次产出（判据 32）。④✅ 执行侧 `listScopedImages`/`ScopedMediaRefs`，回落两因文案可区分。⑤✅ 单测 27 + 3 组反向验证 + 自测修两缺陷（声明顺序污染 `G-046`、端口 null NPE）。⑥真机验证部分完成（2026-11-25，同 `G-044/045/048` 链路）：对话面三态通过（部分勾选只送勾选、全排除不编造、同步端点接 `attachmentIds`）；执行面待收尾：3 图会话 2125572659870433281 已发「参考这三张图」（code:200），待 `finalizeById` 后核 `mediaRefIds` 落库与装配日志。 |
| G-043 | 工作台（助理）链未解析 `attachment_targets` | `AssistantSearchQueryPlanner` 构造 `AssistantSearchPlan` **只传 4 个参数**，`attachmentIntent` 有解析，但 `attachment_targets` **整条无解析**（对比澄清链 `ChatRoundDecisionParser` 是解析的）。⇒ 工作台里用户说「就按第 2 张图来」不生效，只能全量注入。这不是前端没做，是**后端没接** | 工作台链与需求对话链共用同一份作用域判定：`attachment_targets` 解析后与用户勾选、全量回落构成同一条 `resolveScope` 链路，两页「判定逻辑同一份、交互可不同」 | PR1 | 部分完成 | ①✅ 助理检索 prompt 本来就走 `renderDecisionPrompt`（共用 `requirement-decision.md`），模型一直输出该字段、`ChatRoundDecisionParser` 一直解析，缺的只是助理链取值环节；同批补四处（模板字段说明段、`SCENE_CONSTRAINT` 规则段点名附件必须填序号、`AssistantSearchPlan` 加 `attachmentTargets`、日志带出 targets）。②✅ `webSearchEnabled` 不再否决作用域判定（`plan(...)` 新增 `searchAllowed`），开关只否决「联网」一个结论；顺带修掉「不联网时连决策都不跑」这个更上游的断点。③✅ `PromptContext.withAttachment` 承接两段式装配（先按未指定装一次供决策判有无附件，拿到 targets 后只换附件段，不重跑记忆/历史/画像）。④✅ 单测 11 例。⑤待办：真机验证（`G-044`/`G-045`/`G-048` 同批，单测只证参数通路） |
| G-044 | 勾选排除信号在 CLARIFY / FINALIZE 路径被丢弃 | `ClarifyPromptRenderServiceImpl` 的 `renderClarifyPromptWithMedia` / `renderFinalizePromptWithMedia` 内部均走 `renderPrompt(..., true, null, null)`——**`selectedIds` 硬编码 `null`**。⇒ 需求对话转入澄清之后，每一轮（含出终稿那轮）用户的勾选完全无效，只有 CHAT 模式生效；带 `selectedIds` 的 `renderChatPrompt` 重载在生产代码里**无调用方**。实测影响：用户在澄清轮点掉一张图，模型照样看到它 | （**已定修法 A**）`selectedIds` 一路透到 CLARIFY / FINALIZE 模板，作用域仍走同一份 `resolveScope`（勾选 > 模型序号 > 全量），不为澄清链单独发明判定 | PR0 | 部分完成 | ①✅ 修法 A 落地：`selectedIds` 从控制器一路透到渲染层（`renderClarifyPromptWithMedia` / `renderFinalizePromptWithMedia` 各扩参数，改走 6 参 `renderPrompt` 不再硬编码 null），同步链与流式链全链透传。②✅ 单测 8 例（三模板吃同一份勾选、无信号回落全量、空集一张不送）。③待办：真机验证「转入方案模式后点掉一张图，模型确实看不到它」——单测只证参数通路。④语义边界：`retryRound` / `editMessage` / `enterClarify` 显式传 null（勾选是瞬时信号未持久化，凭空造「全排除」是错的），三处代码注释已写死理由 |
| G-045 | 「全部排除」语义反转：用户说都不要，系统注入全部 | 前端全点掉时 `effectiveAttachmentIds` 返回 `[]`（`RequirementChat.vue:1881` 的 `size>0` 守卫使其不走 `return null`）；后端 `ChatAttachmentPromptRenderer:292` 判 `selectedIds.isEmpty()` ⇒ **进不了显式分支** ⇒ 回落 `resolveScopedIndexes`（`:629`）⇒ **注入全部**。根因是 `null`（未指定）与 `[]`（明确一个都不要）**在协议上不可区分**。 | 「未指定」与「明确空集」在 API 与渲染层可区分：未指定 ⇒ 走回落（模型序号 > 全量）；明确空集 ⇒ **一张不送**且 prompt 显式写明「用户本轮已排除全部附件，不得引用附件内容」。 | PR0 | 部分完成 | ①✅ 语义定死并落地：`resolveScope` 改判 `selectedIds != null`（不再 `!isEmpty()`）——`null`=未指定走回落、`[]`=明确全排除返回空集、非空无匹配才 warn 回退。②✅ `buildContext` 对空集渲染专门文案「（用户本轮已排除全部 N 份附件：不要引用任何附件内容…）」，不再复用「提问指向的是其他附件」（空集场景下没有「其他附件」，那句会误导模型以为清单不完整）。③✅ 真值表 4 例 + 媒体通道 1 例，共 5 例全绿；反向验证（还原成旧写法）后 3 例准确转红、2 例仍绿，边界判定精确。④✅ 前端配合已齐：需求对话页与工作台 `effectiveAttachmentIds()` 均按三态实现（无排除项返回 null、有排除项返回剩余 id），两页同一份口径。⑤待办：真机验证「全点掉后模型明确回应已排除而不是编造内容」——单测只证参数通路。 |
| G-046 | 排序方向与截断方向相反：保留最早上传、丢弃最新上传 | `ChatAttachmentMapper.listByConversationId`（`:31`）`ORDER BY create_time ASC`，而 `AttachmentMediaAssembler` 按序截断到 `MAX_INLINE_COUNT=5`（`:152`）、`DecomposeContextSnapshotRenderer:119` 按 `MAX_ATTACHMENTS=20` 取前 N。⇒ 会话附件超过 5 张后，**用户后传的图永远送不到模型**；拆解期看到的也是**最老 20 件**。纯缺陷，与作用域无关但会放大 `G-042`（用户以为「后传的这张会被看到」，实际早被截掉）。 | 「**三处落点**（文本注入 / 拆解快照 / 执行面媒体，同一缺陷三个出现形态）：截断保新」：注入侧取**最新** N 张/件（用户后传的优先），并在清单里显式标注「共 M 件，此处展示最新 N 件」；管理/轮询侧（前端附件列表）仍按上传顺序展示，**两类消费方口径分开**。 | PR1 | 已完成 | — |
| G-047 | 附件对象回收护栏缺在跨表一侧 | **任务侧** `AttachmentObjectPurgeSupport.purgeObjects` 有护栏，但 `selectActiveObjectKeysIn` **只查 `attachment` 表**；**会话侧** `ChatAttachmentServiceImpl.purgeObjectQuietly` **完全没有护栏**，直接 `artifactStorage.removeObject`，失败只 `log.warn` 且**日志不带 objectKey/bucket**。⇒ 一旦按「同 `objectKey` 双行」建桥：用户在会话里删一张图 → 会话侧无护栏直删对象 → **子任务正在引用的那份对象被删掉**，产物审计链路断裂且不可追溯 | 双向跨表护栏：会话侧删对象前查 `attachment` 表同 `objectKey` 是否仍有活跃行，任务侧删对象前查 `chat_attachment` 表；**护栏查询失败一律 fail-safe 保守跳过**（对齐现有哲学：宁可不删也不误删）；日志必须带 `objectKey` + `bucket` | PR0 | 部分完成 | ①✅ 跨表护栏双向落地（`ArtifactReferencePort` / `ChatAttachmentReferencePort` 各增 `listActiveObjectKeysIn`，只认 `deleted=0`，与对账口径相反且必须相反；两侧回收实现已接入，查询失败一律 fail-safe 保守跳过）。②✅ 两 adapter 改直连同域 Mapper，切断 Spring 构造器环（经 Service 会成环）。③✅ 单测 21 例 + 反向验证。④口径订正：`G-042` 最终未采用「`attachment` 双行」方案（理由见该行①），故「真实双行数据下的端到端验证」这个前置**不再需要**；双向护栏本身仍是有效兜底——两张表共用一个桶、`objectKey` 可能被两侧同时指向（现存数据与将来任何桥接方案都适用），保留不动。⑤✅ 会话侧批量回收入口已随 `G-041` 补（`ChatAttachmentServiceImpl#purgeObjects(List)`，护栏查询一次覆盖整批，不再逐条 2N 次往返） |
| G-048 | 同步兜底链路不接收 `attachmentIds`，流式失败退同步即丢勾选 | `RequirementConversationController` 同步 `sendMessageById`（`:463`）只传三参、无附件参数；`ClarifyRoundOrchestratorServiceImpl:769` 的 CHAT 分支 `renderChatPromptWithMedia(..., null)` 亦硬编码 null。⇒ 流式接口一失败退到同步兜底，用户勾选就丢——而此时用户往往正因为失败在重试，最需要一致的语义。 | 同步与流式两条入口的附件参数**完全同构**（同一份 DTO / 同一份透传链），兜底切换对用户行为无感。 | PR1 | 部分完成 | ①✅ 同步端点 `sendMessageById` 已接收 `req.getAttachmentIds()` 并透传；`RequirementClarifyService` / `ClarifyRoundOrchestratorService` / 实现类补 4 参重载；`runLlmRound` CHAT 分支的 `renderChatPromptWithMedia(..., null)` 改为 `selectedIds`。②✅ 同步与流式的附件参数现已完全同构（同一份 DTO、同一份透传链），兜底切换对用户无感。③待办：同步 `create`（首轮）仍不接收 `attachmentIds`——它是流式失败兜底且调用方本就不传，本次按现状保留并更新了控制器注释里的理由；若后续要让兜底覆盖「新建会话首轮」，需同批补。 |
| G-049 | `excludedAttachmentIds` 切换/新建会话从不重置，跨会话残留排除态 | `RequirementChat.vue` 全文件对 `excludedAttachmentIds` 的**写值只有 `:1873` 一处**（点选切换），`selectConversation` / 新建会话 / `attachments` 清空处**均无重置**。⇒ 从会话 A 切到会话 B，A 里排除过的附件 id 仍留在集合里；虽然 id 不跨会话匹配、当前是「无害的脏数据」，但一旦 `G-045` 把「空集」升格为「明确一个都不要」的语义，残留集合就可能把**B 会话的附件**误判成排除态（同 id 复用或逻辑变更后）。工作台照抄该交互会**一并继承**这个缺陷。 | 切换会话 / 新建会话时清空排除集合，与附件列表刷新同一步完成（同一份「切会话」动作只做一次状态重置，避免两处各清一半）。 | PR0 | 已完成 | — |
| G-050 | deepseek 图片输入静默失效：官方支持图像，平台侧送图被丢 | 根因在传输层：`spring-ai-deepseek:1.1.8` 的 `DeepSeekChatModel#createRequest`（源码 436~472）对 USER 消息**无 media 分支** ⇒ 图被静默丢弃、provider 不报错（`spring-ai-openai` 的 `OpenAiChatModel` 有 `mapToMediaContent`）。`V155` 曾把 `capabilities.input` 改 `["text"]` 做 **fail-close 止血**（把「平台未开放」声明成「模型能力」）；`ImageInputCapabilityPolicy` 认 `capabilities.input` 决定送不送图 ⇒ deepseek 请求永不带图 | **方案 A**：deepseek 改走 OpenAI 兼容通道（`protocol_type` 本就是 `OPENAI_COMPATIBLE`、`base_url` 本就是 `api.deepseek.com`、`OpenAiChatModel` 有 media 装配）；`V156` 把 `image` 加回 `input`（**仅 flash**，pro 官方不支持图像保持 text-only）；专用 `DeepSeekProviderChatClientFactory` **停用不删**（`verify-provider-catalog-parity.sh` D-e 守卫依赖其 `DEFAULT_MODEL` 常量）；接受代价：`spring-ai-openai` 响应侧不解析 `reasoning_content` ⇒ 丢 thinking 展示（正文/工具调用不受影响） | PR1 | 已完成 | — |



## 1.1 专项任务索引（任务视角，状态不复述）

> G 矩阵（上方）回答「还差什么能力」；本表回答「各专项的任务编号落在哪个文件」。**两者编号体系不同，不可互相替代。**
>
> 状态只在「唯一事实源」里维护。本表不复制进度——同一事实写两处必然漂移，而漂移后的第一受害者正是来查状态的人。

| 体系 | 编号 | 项数 | 唯一事实源（状态只见此处） |
|---|---|---|---|
| `REF-x.y` | REF-1 ~ REF-8（含 `REF-8.1`~`REF-8.9` 子项） | 8 组 | `plan/HelloAI 借鉴落地实施计划.md` §3 ~ §10 各节 `Status` |
| `M-x` | M-1 ~ M-9 | 9 | `plan/HelloAI 借鉴落地实施计划.md` §11（**全部完成**） |
| `S-x` | S-1 ~ S-10 | 10 | `archive/implemented/HelloAI OpenSandbox 沙箱集成计划.md` §3；`S-7`~`S-9` 见 `archive/implemented/HelloAI 沙箱执行链设计方案（2026-10-17）.md`；`S-10` 登记不启动 |
| `W-x` | W-1 ~ W-10 | 10 | `archive/implemented/HelloAI 联网搜索效果优化实施计划（2026-10-18）.md`（2026-11-24 归档，**全部完成**）；`P3 Agentic 多轮搜索` 登记不启动 |
| `ATT-x` | ATT-1 ~ ATT-8 | 8 | `plan/HelloAI 需求对话附件实施计划（2026-10-22）.md` 头部状态行 + `ATT-x` 各节 |
| Paperclip | 阶段一 / 二 / 三（不引入编号前缀） | 3 阶段 | `plan/HelloAI Paperclip 借鉴落地计划（2026-10-21）.md` §1 ~ §3 |
| `TD-x` | TD-1 ~ TD-12 | 12 | `plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md` §3 任务表（各 `### TD-x` 标题下的 `**状态**` 行）；`TD-10` 登记不启动；`TD-11`/`TD-12` 为 2026-10-25 续号 |
| `SKX-x` | SKX-1 ~ SKX-15 | 15 | `plan/HelloAI 技能执行面接入实施计划（2026-10-26）.md` §3 任务表（各 `### SKX-x` 标题下的 `**状态**` 行） |
| `RC-x` | RC-1 ~ RC-6 | 已退役 | `archive/implemented/HelloAI 需求对话自由切换与并发互斥实施计划（2026-10-30）.md`（`Done`，号段不再新增） |
| `PA-x` | PA-1 ~ PA-17 | 17 | `plan/HelloAI 个人助理工作台实施计划（2026-11-02）.md` §3 任务表（各 `### PA-x` 标题下的 `**状态**` 行）；`PA-8`~`PA-10` 登记不启动；`PA-11`/`PA-12` 为 2026-11-03 续号（`PA-11` / `PA-12` 已完成）；`PA-4` 于 2026-11-05 按设计稿修订「选模型 / 活跃 Agent」口径（见 `LOG-20261105`），同日按用户裁定二次修订：失效模型改为**不下发**、面板按设计稿重做、默认模型订正为 `deepseek-v4-flash`（见 `LOG-20261105`） |
| `MSG-x` | MSG-1 ~ MSG-8 | 8 | `plan/HelloAI 站内消息与置顶公告实施计划（2026-11-15）.md` §3 任务表（各 `### MSG-x` 标题下的 `**状态**` 行）;`MSG-7` 扩展覆盖列后续;差距项 `G-035` |
| `GT-0`~`GT-12` | GT-0 ~ GT-12 | 13 | `plan/HelloAI git 能力集成实施计划（2026-11-24）.md` §3 任务表（各 `### GT-x` 标题下的 `**状态**` 行）；差距项 `G-033`（待办①锚点）+ `G-027`⑤⑥ |
| `CT-x` | CT-1 ~ CT-8 | 8 | `plan/HelloAI 任务约束类型化试点实施计划（2026-11-23）.md` §3 任务表；差距项 `G-037`（八项均落代码，计划显式保持 Active 待正式引入验收） |
| `VU-x` | VU-1 ~ VU-10 | 10 | `plan/HelloAI 视觉验收与沙箱浏览器实施计划（2026-11-24）.md` §3 任务表（表格式，含 `状态` / `剩余动作` 两列）；差距项 `G-038`；事实输入 `plan/运行时 UI 验收能力待核清单（2026-11-05）.md`（`RUI-1`~`RUI-10`，只核事实不承载执行路线） |
| `RUI-x` | RUI-1 ~ RUI-10 | 10 | `plan/运行时 UI 验收能力待核清单（2026-11-05）.md` §1 核对项（**事实核对清单，非实施计划**；`VU-x` 是其执行路线，两者不替代、不得另建第二份清单）；差距项 `G-038` |
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

**落地事实与验证证据**：`LOG-20260930-001`（硬切）/ `LOG-20260930-002`（外部 CLI_CLIENT E2E）。【过程记录原在 `archive/implemented/HelloAI 重构实施计划.md`，已于同批回填 `LOG-20260908-006`】

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
- 菜单分组（2026-11-06）：一级菜单由 17 条平铺收敛为 4 个目录 + 个人工作台 + 系统设置；目录沿用既有「聚合父」形态（`type=MENU` + `path` 非空 + `component` 空 + 有子节点，`V143`），不新增类型 / 列；读侧补「祖先链放行 + 逐层剪枝空目录 + `sort` 补 `id` 兜底」，见 `LOG-20261106`
- 登录落点可配置（2026-11-06）：`sys_permission.is_default_home`（`V145` + 部分唯一索引，全平台仅一个）+ 菜单管理页开关；前端 `resolveDefaultHomePath` 优先取标记、无权或无标记时回退菜单树首个可见路由。**0.2.1 镜像首版（12:28）漏带 `V145` 整批，已于 13:38 重打并确认 `sha256:211557c7…` 入 ACR**，见 `LOG-20261106` 末节
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
LLM 额度与用量监控（G-026，价值项）
项目级交付编排（G-027）
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
| `REF-8.1` | **工具面解封**——给 `API_KEY_LLM` 一条**不依赖 MCP 会话**的工具通道（读原附件 / 文件读写 / 正式交付动作），复用 `ToolRegistry` 语义位 + `ToolCallbackContributor`，与 `resolve` 保持单口径 | ✅ **已实施**：`InternalLlmExecutorTools`（进程内 @Tool）+ 独立 provider；`AgentMcpServerServiceImpl` 对 `API_KEY_LLM` 返回内部授权集（而非空），外部 MCP 工具仍对内部 LLM 剔除。外部工具面现已 14（`ADR-009` A-1/A-4），剔除机制不变。详见 `LOG-20261012` |
| `REF-8.2` | **补手脚 + 上下文**——新增 `WorkspaceAccess`（先只读 + 只写产物目录）+ 读附件工具；`systemPrompt` 从 `""` 改为「角色 + 工具使用指引 + 输出契约」；`maxIterations` 按任务类型分档 | ✅ **已实施**：`buildSystemPrompt` 替换空 `systemPrompt`；`maxIterations` 按 accessType 分档（内部 10 / 外部 5）。**手脚已被 `REF-8.1` 覆盖**，不再建 WorkSpaceAccess 工具（需 sandbox，`REF-3` 条件触发不排期）。详见 `LOG-20261012` |
| `REF-8.3` | **技能改「全量目录 + 惰性加载」**——注入目录段（非速览），新增惰性加载工具，`AgentSkillSpecService` 加 `get(name)`；保留 `requiredTools` 作能力级声明 | ✅ **M3 已实施（2026-10-12）**：`AgentSkillSpecService` 加 `getPackage(name)` / `loadBody(name)`（委托 `SkillPackageCatalog.byName/bodyOf`）；`InternalLlmExecutorTools` 加 `internal_list_skills`（全量目录，name/version/description/requiredTools 封顶）+ `internal_load_skill`（惰性加载正文）；`INTERNAL_LLM_TOOLS` 加 2 工具；`buildSystemPrompt` 增技能发现指引；`requiredTools` 保留能力级声明。单测 2 个新增；全量 1980 绿 |
| `REF-8.1.1` | **自报阻塞通道**——内部 LLM 缺「做不到」出口 ⇒ 只能把「做不到」写进正文（物化交付物 → 评审打回 → 返工至死信） | ✅ **已实施（2026-10-12）**：新增 `internal_report_blocked`（进程内 @Tool，带**知情拒绝闸门**——须先读过子任务详情、原因 ≥10 字）→ `block()` + 人工介入标记；阻塞后循环结束的 INTERNAL 回写因「状态非 `IN_PROGRESS`」被丢弃 ⇒ 不进评审。单测 4 例；形状对齐 DSH `update_goal.blocked`（研究文档 §1.5.3） |
| `REF-8.1.2` | **persona 两槽位（身份面）**——systemPrompt 身份写死且不可覆盖，缺「专业角色面」 | ✅ **已实施（2026-10-12）**：`AgentExecutionProperties` 增 `personaPrefix` / `personaSuffix`（`helloai.execution.*`，默认空 ⇒ 行为零变化）；装配槽位序「内置身份行 → persona prefix → 工具指引 / 输出契约 → persona suffix」；取值顺序 `agent.labels` → 部署配置 → 空（**不动表结构**）。对齐 DSH `deployment:persona-*` + `dsh-persona` 遮蔽纪律。单测 3 例 |
| `REF-8.2.1` | **返工上下文「替换而非追加」**——原把**所有轮次**审核意见全量拼入 ⇒ 思考链与 token 随轮数线性膨胀 | ✅ **已实施（2026-10-12）**：只铺开最近一轮（时间 / 问题 / 评语 / 评分 / 已自认修复），历史轮次压成一行并明写「已被本轮取代」。回归断言：历史轮次正文不进 prompt |
| `REF-8.2.2` | **验证证据围栏（结构化轮报）**——执行侧输出契约缺证据段，而评审侧 fail-close 明文要求 `VERIFICATION` 段 ⇒ 内循环必然返工至死信 | ✅ **已实施（2026-10-12）**：输出契约补 `VERIFICATION:` 围栏（证据 + `STATUS` + `NEXT_STEPS`），**复用评审侧既有判据**（`ReviewEvidenceAssembler.verificationSignal`）⇒ 评审侧零改动。实测：同一「撰写摘要并引用 marker」子任务由「返工 3 次 → 死信」变为首轮通过 |
| `REF-8.2.3` | **上下文压缩**——无预算与压缩、工具直读附件无上限、提供方报超窗即失败 | ✅ **已实施（2026-10-12）**：两道（确定性工具结果剪枝 → 仍超预算才 LLM 摘要）+ 两触发（每轮调用前压力检查 / 提供方报错后的溢出恢复）；含**净收益守卫**（仅净减才接受，否则回滚原文）与**摘要额度**（单次执行封顶）。摘要复用同一 ChatModel 与 options、逐字保留最近 `retainRatio` 尾部、不拆 assistant + tool 结果对；新增 `CONTEXT_COMPACTED` 审计事件。对齐 DSH `compaction-basic` + `compaction-tool-result-pruner` |
| `REF-8.4` | **拦截链** —— **循环自有 `LoopHook` 三档**（`beforeRequest` / `onRequestError` / `beforeTerminate`；**不套** Spring AI `Advisors`）；`LOG-20261011-007` 的 **B（收敛熔断）** 挂此 | ✅ **已交付（2026-10-13）**：① 三档挂点骨架 `09ecd747` · ② 收尾轮 `873bb559` · ③ `VerificationFence` / ④ 出口分类 `3b5224a6`（接线点 `ChatModelToolLoop:129/142/236`）；全量 2031 例绿（`LOG-20261013-010`/`-011`/`-012`）。**A 能力闸已出列** ⇒ 落 `ResilientDispatcher`（`D-2026-10-15-6`） |
| `REF-8.5` | **循环覆盖面收敛**——逐面判「该不该进循环」（子任务评审、终报告生成理论上都该带工具） | ✅ **已交付**：覆盖面 16 项已逐项裁定（进循环 4 项 / 留单次 12 项）；新增业务面循环入口 `BusinessSurfaceLoopRunner` + 单咽喉插点 + 独立开关与 scene 白名单。三条实现裁定：①业务面不复用 `RuntimeTurnExecutor`（三处契约冲突）而与它并列复用 `AgentLoop`；②业务面不写 `agent_event`（防末条事件对账误报）；③开关默认开启、回退通道保留。详见 `LOG-20261016-005` |

**收敛完成判据**（内核自洽，较「组完成」更严）：① 不依赖 MCP 会话可独立构造 / 测试；② **可见面单口径**（模型可见工具集 = `ToolRegistry.resolve` 结果，上游无第二处二次过滤——对应已证缺陷模式「**规则存在 ≠ 全路径强制**」）；③ **能力可验证**（拿一条「需读原附件 / 写产物」的验收任务，内部 LLM 能**自己调工具**完成）；④ 事件不双写（模型可见消息与事件流同源）。

**红线**：**不新建 append-only 表**（`agent_event` 已是主表 + Outbox 双写，见 `AgentEventRecorder.java:11-15`）——正确动作是**提升已有 `agent_event`**（补 `surface` 投影 + `deriveMessages()`）；**不动 `ChatModelToolLoop` 循环本体**；**不引入 LangGraph4j**（触发信号出现前）。

### LLM 额度与用量监控（G-026，2026-10-26 立项）

任务锚点：设计 `design/HelloAI LLM 额度与用量监控设计.md`（`Status=Implemented`）；实施计划 `archive/implemented/HelloAI 额度与用量监控实施计划（2026-11-02）.md`（`Q-1`~`Q-10` 已完成，验收证据见该计划 §8）。

**要解决的事**：4 家外部供应商的余额分散在各自控制台，当前只能靠「调用失败」事后感知额度耗尽。

**设计已定的六条**（详见设计文档，此处只留结论）：

```text
① SPI           BalanceProvider（key / supports / fetch），fetch 不抛异常、失败返回 failed(reason)
② 密钥来源      BalanceCredentialResolver 抽象两层：vault（3 家 LLM）/ sys_config（博查）
③ 数据          只建一张 llm_balance_snapshot；不建 llm_account（与 vault 双写）、不建 llm_usage_daily（与事件源双写）
④ 缓存          Redis RBucket TTL 60s（多实例不重复打外部接口）+ Resilience4j quotaFetch 熔断
⑤ 刷新          前端按 `sys_config["quota.refresh.interval-seconds"]` 轮询（默认 60s，60~3600 可配，0=仅手动）+ 定时采集 15min 且服从该间隔；不采用 SSE（低频数据，且已有跨实例踩坑）
⑥ 前端          MainLayout 悬挂浮球，折叠显示总额、展开列 4 家；金额型与额度型分开（MiniMax 是百分比）
```

**范围裁定**（2026-10-26 用户拍板）：通义千问**不显示**——无公开余额接口，需阿里云 AK/SK 签名，而本平台只有 `sk-` key。
「显示一个取不到数的行」比「不显示这一行」更容易被当成缺陷。

**与外部建议的三处有意分歧**（外部方案 vs 本设计）：

| 外部建议 | 本设计 | 理由 |
|---|---|---|
| 三张表（account / snapshot / usage_daily） | **只建 snapshot** | 另两张分别与 `credential_vault`、`agent_event` 双写，必然漂移 |
| SSE 增量推 | 按配置间隔轮询 + 60s 缓存 | 低频数据建高频通道，成本高于收益 |
| 余额 5 分钟轮询 | 15 分钟 | 4 家 × 96 次/天已足够，余额是日级变化 |

**前置待验证**（写 plan 前必须确认，否则实现会返工）：博查 `remaining` 单位（元/分）、博查 base-url 用 `api.bochaai.com` 还是 `api.bocha.cn`、
MiniMax `token_plan/remains` 实际结构、`deepseek-v4-flash` 是否仍适用峰谷价、`credential_vault` 同 provider 多条 ACTIVE 时的取数规则。

**明确不做**（M1）：AGENT 级 22 条凭据余额、调度联动（额度低停止派单）、MCP 工具 `queryBalance`、token 五分量与趋势图、阈值告警。

**落地与验收（2026-11-02，计划已归档）**：`Q-1`~`Q-10` 全部已完成，计划迁 `archive/implemented/HelloAI 额度与用量监控实施计划（2026-11-02）.md`（`Status: Done`，验收证据见该计划 §8）。4 家真机出数（deepseek / moonshot 金额型、MiniMax 百分比额度型、博查无 key 时 `FAILED` 不伪报 0）；刷新间隔可配（`sys_config["quota.refresh.interval-seconds"]`，`0`=仅手动 / `60~3600` 秒，系统设置「额度监控」区）+ 手动模式只读落库快照不触网 + 浮球四向可拖；quota 单测 45 例、`helloai-core` 全量 2862 例 0 失败，前端构建与架构守卫全绿；用户确认「额度监控没问题」⇒ 浏览器端可视化验收通过。

### 项目级交付编排（G-027，2026-11-04 立项）

任务锚点：设计 `design/HelloAI 交付编排能力设计.md`（`Status=Planned`）；实施计划未立（待 `V1`~`V5` 验证后出 `plan/` 批次）。

**要解决的事**：需求文档进来后，平台只有「一次任务 → 静态 DAG → 派发 → LLM 验收」内循环，没有项目级载体——后续阶段无法基于已改完的代码滚动拆解，代码类验收缺确定性判据，执行环境无可持有的代码工作区。

**设计已定的九条**（详见设计文档 §3，此处只留结论）：

```text
① 计划层      Plan 双表示（Document 人读 + Spec 执行，Markdown 只是读侧投影）+ 滚动式规划（Phase 开始才细拆）
② 领域归属    不新建 delivery 域：Plan 归 planner / 工作区归 task / 验收机归 review / Provider 归 agent
③ 验收机      命令退出码为权威（验收机上跑，不在执行 Agent 机器上跑）；LLM 只判意图；ArtifactVerifier SPI
④ 执行通道    外部 Coding Agent 起步（复用 executor 通道）；CodingAgentProvider SPI 预留 ACP 形态；不自建执行内核
⑤ Git         worktree 隔离 + 权限分级（平台代建分支、不 push 主干、收 commit hash + diff）+ Git Gateway 短期凭证
⑥ 并行        touchedPaths 交集判据（非空强制串行）+ 合并队列每合并一支重跑验收机
⑦ 约束        Context Bundle 五层 + 可信度分级（拦截靠工具与验证，Prompt 只做「知道」）
⑧ 成本        Phase 级 token 预算 + 超限熔断（复用 G-026 口径）
⑨ 漂移        PLAN_DRIFTED 三信号（基点后变更触及 touchedPaths / 用户改 Plan / 计划假设不成立）
```

**前置待验证**（写 plan 前必须确认，否则实现会返工）：外部 Agent 无头运行能力（Trae / Claude Code CLI / Aider 各实测）、worktree + 平台代建分支 + 短期凭证闭环、验收机容器计时、`touchedPaths` 产出可用率、worker 中断接续。

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
| R14 | **图片 / 二进制载荷「只存路径引用」**（A1「载荷旁路」配套第二条，来源 Octop `workspace_image.py`） | **条件触发**：判据 = 图片在 checkpoint / history 只存 `workspace://` 路径引用，仅在 model request 时展开为 `data:` URI ⇒ `base64` 永不落 state、不进历史 payload。**当前不适用**（平台无图片/多模态输入通道，`base64`/`image_url`/`data:image`/`vision` 零命中）。**触发条件**：平台新增图片/二进制多模态输入通道 ⇒ 按 `REF-5.2b` 同思路落地。**不排期** | `REF-5.2b` · `G-005` |

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
| R14 | **Sa-Token 过期会话键不回收** | **登记不修（低危）**：`sa-token.timeout = -1` ⇒ 会话键无 TTL；`active-timeout` 超时后 `checkLogin` 正确抛「登录已过期」，但两个 `login:*` 键 TTL 仍为 `-1` 且不被删除。**影响面**：仅管理端登录会话（数量级小），过期后行为安全（恒 401），实际表现为 Redis 键缓慢无界增长。**不修理由**：①主动 `logout` 会正常清理，只在「不登出直接放到过期」时残留；②修法要么改 `timeout`（连带改变会话语义）要么新增清理任务（收益不匹配）。**重估触发**：管理端会话进入千级、或 Redis 内存出现可见增长 | `G-012` · `G-013` |

### 7.1.7 `ADR-008` 派生式子任务（2026-11-09，依据 `design/adr/ADR-008-task-decomposition-derivation.md`）

> **状态：已落地（2026-11-11，`AD-1`~`AD-6` 真机通过；迁移收敛为 `V146`~`V149` 空库链，原 `V150`~`V152` 已在提交前取消）。** 本 ADR 取代原「草案暂存区与稳定编号设计」§4.3（S3 展开）与 §7（深度按 code 段数）；该设计文档的有效内容已并入 ADR §19 后删除。
> **迁移链订正（2026-11-12）**：现役为 **`V146`~`V150`**——`V150__conversation_derivation_lineage.sql` 承载 A8-11/A8-13 两条血缘列（并入原独立 `V151`；用户裁定「一次版本变更只出一个迁移号」）。本机已 apply；dev 尚未 apply（`LOG-20261112`）。
>
> **定稿四项**：① 完成态传播 = 同步 + 第 23 个对账任务，**不建生命周期 outbox**；② 草案期派生不建会话、直接建 `Y` 并触发拆解，执行期重构建 ACTIVE 会话并记 `source_sub_task_id`；③ `Y` 继承父 `archetype`，只许更严；④ ADR 为唯一设计源，计划只留批次/状态/验收。
>
> **直接作废、不再排期**：`TD-16a` 的 `blockedBy` 契约、`TD-16d②`、「确认时扁平化」、`draft_code` 多段 / `DraftCode.child()` / `resolveParentDraftCode`、`expandById` / `expandDraftByCode`、`referencingDrafts`、`TD-16e-3-1b`、`TD-16e-4`、`TD-16f`、暂存区 `parent_code` / `depth` 语义。

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| A8-1 | **失败传播**（派生任务 `Y` 失败/废除时父 `A2` 处置） | **已收敛**（`ADR-008` §13.4）——`A2 → RESTRUCTURING` 人工接管，不做 `FAILED` 冒泡。原「三选一未决」作废 | `TD-16f` 栅栏同型 |
| A8-2 | **报告回挂方式**（派生任务产出如何让下游读到） | **已定：方案 C**（§15.2）——把「`A2` 原产出 + `Y.final_report`」合并物化附件挂到 `A2`，`loadUpstreamContent` 天然附件优先 ⇒ 读侧零改、审计链不被伪造记录污染。**两个前置**（§15.4）：`AttachmentServiceImpl#register` 当前强制 agent 归属，需先开平台写入入口；且必须合并渲染，不能只挂报告（否则顶掉原产出） | `G-016` · `N6` |
| A8-3 | **`DONE` 是否允许重构** | **已定：不允许**（§11.2 保守口径）——`DONE`/`CANCELLED` 是终态，不出边。若要放开「`DONE` 但下游未消费」，须先加「谁读过我的产出」消费埋点（当前 `buildDependencySection` 读而不落痕） | — |
| A8-4 | **`RESTRUCTURING` 是否需要自动超时** | **决定：不设**（§17.3）——自动转走违背「人工接管」本意。替代：进「待我处理」列表 + 看板告警 | — |
| A8-5 | **`G5_RUNTIME_CONTRACT_BREAK` 确定性部分的误报率** | **未验**——文本包含匹配（下游 `acceptance` 是否出现本任务产出的字段名）需实测样本，误报高则整条判据走模型 | `G-007` |
| A8-6 | **派生会话入口落点** | **已落地（2026-11-12 起双入口都建会话）**——草案审核页「拆出去」改走 `POST /tasks/draftDeriveSession/{taskId}/{code}`（`task:plan`，锚 `(task_id, source_draft_code)`，`V150`；定稿才建 Y）；子任务详情页 `subtask:restructure`（进入重构 / 编辑要求 / 整改回派发）与 `subtask:derive`（拆出去，建 ACTIVE 会话）。旧的「草案期直接派生不建会话」`TaskController.deriveDraftByCode` 仅作为外部 API 契约保留、前端已不再调用。种子与落地断言见 `V148` / `V150` | `TD-16e-3-2` |
| A8-7 | **一致性契约：outbox / TCC / Saga 的取舍** | **已定（2026-11-10 修订）**——TCC 与 Saga 在生产代码命中 0（从未实现）；TCC 仍不引入（单体同库无跨服务预留）；Saga 只要动作表（`task_plan_attempt` + `DERIVE`/`RESTRUCTURE`）。**完成态传播不建生命周期 outbox**：现有两张 outbox 表分别绑 `ExecutionCommandMqMessage` 与 agent 路由键，均不承载任务生命周期；改为 `tryCloseTask` 同步传播 + 第 23 个 `TaskLifecycleReconcileTask` 三项对账 | — |
| A8-8 | **`G5` 的模型调用预算** | **未决**（§17.6）——`LOG-20261109` 实测语义门 30s **余量为零**（5 样本 2 触顶）。`G5` 是运行期门、频率更高且用户正在点「重构」，30s 等待更难接受。方向：只做确定性部分 + 语义异步补判 / 改「分批判」 | `A8-5` |
| A8-9 | **上 k3s 是否要分「单机 / 分布式」两套代码** | **已定：不分**（§18.7）——用「默认按分布式正确写 + 就绪度清单 + CI 守卫」代替双路径。盘点：**A 已安全**（`shedlock` 用 `RedisLockProvider`）/ **B 补一行**（仅 `ExecutionCommandPoller:150` 丢弃 `markPolled` 返回值且非 CAS）/ **C 本就各管各** / **D 需重设计：无**。新增 CI 守卫「`@Scheduled` 必须带 `@SchedulerLock`，白名单除外」 | `A8-7` |
| A8-10 | **草案期派生的父锚**（`X` 确认前 `A2` 尚不存在） | **已定**（§3）——`Y` 用 `(parent_task_id, parent_draft_code)` 锚定，`X` 确认物化 `A2` 时回填 `parent_sub_task_id`；两条条件唯一索引分别管两种锚 | `A8-1` |
| A8-11 | **执行期重构会话的血缘列** | **已定**（§19.6）——`requirement_conversation` 新增 `source_sub_task_id` + 部分索引（`V148`）；草案期派生同样建会话，锚为 `(task_id, source_draft_code)`（`V150`，2026-11-12 起；此前草案期不建会话故无此列）。两条血缘列（本行的 `source_draft_code` 与 A8-13 的 `derived_task_id`）**合并为同一个迁移 `V150`**，各带一只部分索引。**产出任务不在 `task_id`**（派生会话的 `task_id` 是父任务 X），见 A8-13 | `A8-6` |
| A8-13 | **派生会话的产出指针** | **已落地（2026-11-12）**——`requirement_conversation.derived_task_id`（`V150`，与本行 A8-11 的 `source_draft_code` 同属一条迁移 `V150__conversation_derivation_lineage.sql`）。派生会话的 `task_id` 按 §13.1 仍是父任务 X，产出任务 Y 单列；读侧统一 `derivedTaskId ?: taskId`（`taskExists` / 「查看任务」/ `regenerate` 守卫）。此前三者都读 `task_id` ⇒「查看任务」跳到父任务，用户在 X 上点「拒绝重拆」把整条链（含 Y 与 Y 的子任务）重置；`regenerate` 亦因 X 恒存在而永不可用。父任务 `rejectPlan` 级联取消 Y 时同步把派生会话置 `ABANDONED` | `A8-6` |
| A8-12 | **`Y` 的 archetype** | **已定**（§19.7）——继承父任务，只许更严（`maxDepth = min(父档, 本档)`），不重新判定 | `A8-1` |

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
