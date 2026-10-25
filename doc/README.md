# HelloAI 文档体系说明

> **文档治理版本：V2.2**（2026-10-09：新增 `plan/` 计划·方案层；`review/` 回归纯报告；已实施方案归位 `archive/implemented/`。上一版 V2.1 于 2026-09-30 收口：`design/` 准入规则 + `Status/Scope` 头 + 引用完整性修复 + 旧链口径清理）
>
> 本目录采用“当前事实 → 目标架构 → 演进差距 → 实施计划/方案 → 架构决策/日志”的单一主线。
>
> **权威优先级：代码/可验证事实 > 项目基线 > 目标架构 > 实现差距 > 实施计划 > 历史归档。**
>
> **分类与准入规则详见 [`文档体系分类与治理规则.md`](文档体系分类与治理规则.md)**（本文件为索引 + 读取顺序；该文件为分类规则 + 流转纪律）。

## 1. 权威文档

| 文档 | 职责 | 当前开发是否直接依据 |
|---|---|---|
| `HelloAI 项目基线文档.md` | 当前代码和已落地能力 | ✅ |
| `HelloAI 目标架构.md` | 稳定目标边界 | ✅ |
| `HelloAI 实现差距表.md` | Current → Target 差距（**只记当前状态，随迭代就地更新**） | ✅ |
| `plan/HelloAI 重构实施计划.md` | 当前实施顺序（业务主线） | ✅ |
| `HelloAI_CODE_STYLE.md` | 代码与工程规范 | ✅ |
| `HelloAI_AI开发协作规约.md` | AI Agent 开发行为规约（生命周期 / 验证体系 / 红线） | ✅ |
| `文档体系分类与治理规则.md` | 文档分类、准入规则与流转纪律（元文档） | 参考 |

## 2. 专项设计

`design/` 只保留仍然具有独立技术价值且会影响后续实现的专项设计。

**准入规则（2026-09-30 确立，与《目标架构》§0 维护规则 3 同源）**

1. `design/` **只收设计文档**。过程性产物一律出列：
   - 实测 / 排查 / 复核 / 审计报告 → `review/`
   - 已执行的改造方案 / 缺陷修复方案 / 预研方案 → `archive/implemented/`
2. 每份设计文档必须在 H1 之后声明 **`Status` / `Scope` 头**：`Status` 取 `Implemented | Partial | Planned | Non-goal` 四值，`Scope` 写清边界与**不含项**，并给出差距表锚点（`G-0xx`）。
3. 进度百分比与逐条差距**只以 `HelloAI 实现差距表.md` 为唯一事实源**，`design/` 不复述数字。

当前主线：

| 文档 | Status | 差距锚点 |
|---|---|---|
| `Agent_Runtime.md` | `Implemented` | G-002 · G-003 |
| `Agent_Event_Stream.md` | `Partial` | G-001 |
| `Skill_Capability.md` | `Partial` | G-004 |
| `Sandbox_Provider.md` | `Planned` | G-005 |
| `Planner_Capability_Awareness.md` | `Implemented` | G-010 |
| `Requirement_Package_Uncertainty.md` | `Implemented` | G-011 |
| `HelloAI Task-Team 与附件可见性设计.md` | `Partial` | G-014 |
| `HelloAI 需求对话附件设计.md` | `Partial` | G-016 · G-017 · G-024 |
| `HelloAI 任务拆解领域无关化设计.md` | `Planned` | G-025 |
| `design/adr/ADR-001-run-turn-step-model.md` | （ADR） | — |
| `design/adr/ADR-002-embedding-provider.md` | （ADR · Accepted 2026-10-11） | `G-019` |
| `design/adr/ADR-003-opensandbox-deployment-topology.md` | （ADR · Accepted 2026-10-17） | OpenSandbox 集成（S-5） |

> **2026-09-30 出列记录（design/ 只留设计文档）**：
> - 改造 / 修复方案 → `archive/implemented/`：`HelloAI_契约层能力注入与最终报告整合改造方案.md`、`HelloAI_外部Agent执行通道缺陷修复方案.md`（均为**已实施**）；
> - 报告类 → `review/`：`HelloAI_附件存储一致性排查报告.md`、`HelloAI_外部Agent_v3反馈代码级复核.md`、`HelloAI 文档治理审计.md`（命名规范化为「（2026-09-07）」）；**其中 `文档治理审计（2026-09-07）` 已于 2026-10-17 再迁 `archive/reference/`**（结论已全部消化，见《治理规则》§3.5 归档判据）；
> - 资产类 → `helloai_avatar/`：`HelloAI_登录页原创AI虚拟人物生成提示词.md`（与生成产物同目录）。

> **代码侧同名概念**：`helloai-core/src/main/resources/onboarding/{role}/guide.md` 是**外部 Agent 接入手册**（交付 ZIP 内仍名为 `SKILL.md`），不属于文档体系。
> 能力包另在 `helloai-core/src/main/resources/skills/plugins/*.md`——**「接入手册」与「能力包」是两件事，禁止混用 SKILL 一词**。

## 2.1 引用完整性规则（2026-09-30）

文档迁移后，**引用必须同步修**。本次治理修复了 **137 处**失效路径引用，覆盖三类来源：`scripts/`（脚本头 Ref 注释，最多）、生产代码 javadoc（`Doorbell*` / `*Properties` / `prompts/*.md` 等）、`doc/`（权威文档与专项设计）。

**不修的失效引用（有意保留）**：

1. `archive/**` 与 `log/**` 内指向旧路径的引用——它们是**当时事实的现场记录**，改写等于篡改历史；
2. `helloai-start/src/main/resources/db/migration/V*.sql` 注释里的已归档方案路径——**Flyway 对已应用迁移做 checksum 校验，改注释会导致 `flyway validate` 失败**，故登记不改（涉及 V1 / V62~V73 共 12 处，指向 `archive/implemented|reference/` 下的 Phase0/1/2 方案）；
3. 裸文件名引用（如 javadoc 里的 `prompts/subtask-review.md`）——同目录唯一，按名可解析，保留可读性。

> **补充（2026-10-09）**：上列 1~3 为「**路径变更型**」失效引用（有意保留）；**若被引用对象被整体删除/退役（非路径变更），其引用不再可解析、必须改写** —— 详见 [`文档体系分类与治理规则.md`](文档体系分类与治理规则.md) **§7 例外②**（`C-NN` 编号退役即实例：43 处文件名引用 + 134 处锚点同批改写）。
>
> 新增/移动文档后，请用「引用完整性」自检：搜索旧路径字符串是否仍有命中，命中的活文档必须同批修正。

## 2.2 调研文档（`research/`）

`research/` 收集**对外部项目 / 方案 / 技术的调研与借鉴分析**，回答「别人怎么做的、哪些值得借鉴、哪些明确不抄」。

**准入规则**

1. 只收**调研 / 借鉴 / 预研**类文档，`Status` 固定用 **`Analysis`**（不改变实现、不承诺排期）。
2. 调研得出的**可执行结论**若进入实施，另立 `design/` 设计文档或差距表条目，**不在 `research/` 里写排期与进度**。
3. 引用外部源码时必须标明**勘察口径**（`[实测]` 附 `路径:行号` / `[推断]` 单列），不可验证的实现不得当已确认事实。

| 文档 | Status | 主题 |
|---|---|---|
| `Octop全库借鉴分析_综合版.md` | `Analysis` | 合并沙箱/技能/WebAgent 与全库两份分析 · 含对既有结论的实测裁决 · §10 按**性质**分档（不承载排期）、§10.6 为借鉴项索引；硬约束 1 订正（2026-10-09） |
| `AgentTeams最新版借鉴分析_Java落点.md` | `Analysis` | AgentTeams v1.0.1→v1.2.2 演进对照 · `A1~A16` 借鉴项 · §七为借鉴项索引（不承载排期）、**§八事实核查表逐条标注勘察口径**（`[实测]`/`[推断]`/`[未核实]`）（2026-10-09） |
| `AgentTeams_Octop_源码复核与helloai借鉴对照.md` | `Analysis` | 两项目源码独立复核 · Fork/Return/沙箱/外部Agent/RAG 四主题对照 · §六为借鉴项索引 + 硬约束 1 订正、硬约束 2 转为 `REF-6.5`（2026-10-09） |
| `helloai四能力完善优先级与借鉴路线.md` | `Analysis` | 用户裁定四项优先级（skills > 备份/恢复 > 沙箱 > RAG；**同日两项调整：Fork 收缩为 WONTFIX、沙箱降级为条件触发**）· **§三排序块已移出**、§六为落点索引（`REF-x.y`），含实现判断勘误（2026-10-09） |
| `helloai借鉴清单_四项之外_完整版.md` | `Analysis` | 四项之外 A/B/C/D 四档借鉴清单 · 判据级与明确不抄 · §汇总为落点索引 · **全部来源行已补 `[实测]` + `路径:行号`**（2026-10-09） |
| `Agent 运行时开源选型参考（2026-10-12）.md` | `Analysis` | 外部 agent runtime（DSH / octop-harness）与 Java 等价物（LangGraph4j）选型；结论「借鉴 > 替换」（2026-10-12） |
| `HelloAI 自建 java-harness 可行性分析（2026-10-12）.md` | `Analysis` | 照 DSH / octop-harness 自建可独立运行 Java harness 的可行性；结论：从零 ≈45k–50k 行 / 10–24 人月，改走「按 DSH 补半套」（2026-10-12） |
| `HelloAI 内部 agent 内核收敛与借鉴思路（2026-10-12）.md` | `Analysis` | 内部内核收敛轴「可见面收敛」（L1 工具集 / L2 技能目录 / L3 上下文）+ `D1~D4`·`E1` 落点（引流 `REF-8`）；**§1.5~§1.7 追加**：DSH 调用链源码复核 / 角色形态与 LLM 全生命周期（14 节点 + 2 旁路）/ 抄袭影响面评估与两条红线；**只答「别人怎么做、会碰什么」，不含排期与进度**；非设计、非计划（2026-10-12，§1.5~§1.7 于 2026-10-13 补登） |
| `magpie_借鉴分析.md` | `Analysis` | magpie（Go 本地网关）供应商/模型注册信息借鉴：`presets` 56 条预置（`M-1/M-2` PR0）、models.dev 模型目录与能力元数据（`M-3/M-4` PR1）、API Key 指纹/批量去重/多 Key（`M-6~M-8` PR2，不做自动切换）；明确不抄账号 failover / Agent 配置注入 / 价格计费 / 端点原样照抄（`N-1~N-5`）；§九为事实核查表。**2026-10-13 复核**：原表锚点全中，新增 §六第 2b 行「预置取值陈旧＝现网缺陷」（含 HTTP 404 直接根因），`M-2` 升级为「去陈旧硬编码 + 预置/种子/yml 三方一致性守卫」，同批复核已修 5 处扩散点；实施入口 `plan/HelloAI 借鉴落地实施计划.md` §11。**落地进展（2026-10-13）**：`M-2①` 全清（含 D-d/D-e 两处 Java 兜底常量）· `M-2③` 守卫扩为「四方比对 + 覆盖完整性 + 双向反证」· `M-1` 首波 9 条（新增预置须待 `M-3` 补模型行方可注册 Agent）；余 `M-2②`/`M-3`~`M-9`。**同日续**：minimax 全系列换代（`M2.5`/`M2.7` 下线 → `MiniMax-M3`，并入迁移 `V108`；同批追加 `MiniMax-M3.1-Flash-Preview`）· 预置覆盖口径按 `Kind` **实测订正**（`KindVendor` 33 / `KindRelay` 18 / Local 4 / Remote 1 = 56 ⇒ 已借鉴 9、余 47）。**2026-10-14 进展**：`M-2②`（`catalogId` + `apiKeyUrl` 可选化 + 守卫第 6 项）· `M-3`（模型目录种子 `V109`，9 家 150 行，`NO_SEED` 清空）· `M-4`（**能力谓词列 `capabilities` `V110`**，形态乙 `D-2026-10-13-9` 数据层，5 条断言）**均已完**；**2026-10-14 续**：`M-5`（`extraConfig` 消费逻辑落地 —— `ExtraConfigPaths` 唯一读取器 + `LlmProviderProfile` 追加 `extraConfig` + 两工厂调 `Api.Builder#completionsPath` + 端点路径并入缓存 key，8 个 HTTP 桩用例含反证）**已完**；**2026-10-14 再续**：`M-6`（**Key 指纹** —— 新迁移 `V111` 给 `credential_vault` 加 `fingerprint`（可空、无默认，口径 = SHA-256 前 12 字节 hex / 24 字符，**系对 magpie 供应商 Key 口径（`internal/provider/keys.go:70` = 5 字节）的有意加强，非「对齐」**——原引 `access.go:421` 属归属错误，订正见 `D-2026-10-14-5`）+ 同 provider 同指纹去重（命中不落库）+ 历史行**应用层惰性回填**（AES-GCM 密文不可 SQL 还原）；判重用例含反证）**已完**；**2026-10-14 三续**：`M-7`（**批量录入去重** —— `KeyTextSplitter` 迁自 magpie `SplitKeys`（八类分隔符 / 两端剥引号 / 输入内去重保序 / **不做归一**）；落库对齐 `AddKeys`「**不覆盖主 key**」：无 ACTIVE 则首把升 ACTIVE、已有 ACTIVE 则新增**全落 `DISABLED` 备位**（新增 `saveDisabledPlatformApiKeyCredential`，只 INSERT）；新增 `PUT saveApiKeysById/{id}` 批量端点 + 前端「批量录入」开关（**保留单把密码掩码**）；20 条用例含反证）**已完**；**2026-10-14 四续**：`M-8`（**多 Key 主备** —— 平台级 API Key 列表 + **手动切换 / 停用 / 删除**（软删）；对齐 magpie `UseKey`/`SetKeyOn`/`RemoveKey`（`keys.go:346-423`）并守其守卫「最后一把在用的留下」；单一判据「**启用位不可缺失**」覆盖新端点**与**旧端点 `POST /api/credentials/revoke/{id}`（无备位可顶 ⇒ fail-close 拒绝），顶替取**最旧**备位并**如实回报**；切换 / 顶替在单事务内**先降后升**（部分唯一索引只允许一条 ACTIVE，顺序写成 `InOrder` 断言）；**脱敏口径统一**到 magpie `Mask`（`provider.go:1501-1506`，前 4 + … + 后 4），原 `maskApiKey` 一并切换 ⇒ 全仓唯一实现；**明确不移植** `internal/gateway/fallback.go` 的配额 / 限流轮换与 `Weight`/`KeyRest` ⇒ 红线 `N-1`；38 条新用例 + **3 组定向反证**（3 / 3 / 1 条红）；**架构冻结 0 漂移**）**已完**；**2026-10-14 五续**：`M-9`（**能力谓词消费方切换 + `V52` 两列退役** —— 消费方切到 `M-4` 的 `capabilities` 谓词并退役 `V52` 两列（`V112`：四前置断言 + 归档 158 行 + `DROP COLUMN` + **摘列默认值**作结构性 fail-fast + 文末回滚 SQL）；谓词→技能**封闭映射 3 条**且只写一次（`core.agent.SkillCapabilityRequirements`）；**`thinking` 改由 `reasoning` 驱动**（唯一有意行为变更，涉 48 行、存量 agent 影响 0）；**modality 不映射技能词表**，改由 `agent.capabilities` 三键按 `capabilities.input` **收敛（只缩不放）**；端口 `LlmProviderModelProfile` **换内容**为 `ModelCapabilities`；**新增三源词表守卫**（`verify-skill-capability-parity.sh` 接门禁 3，`S3` 已收紧为剥注释 + 词边界）；42 条新用例 + **无损对账/负向验证/手工反证**（`V112` 注入偏差确实 `RAISE`；`aos↔webSearch` 158/158、0 失配））**已完**；**`M` 组至此 9/9 全完** |
| `DSH 与 HelloAI 执行流与状态机比对（2026-10-13）.md` | `Analysis` | 以 **DeepSeek Harness（DSH）源码实测**为基准比对内部 LLM 执行流与状态机；给出四项缺口重排（取消 / `max-tokens` / 观测面 / 独立预算闸）与「能力鉴别落点」裁定依据 —— **`plan` §10.8 `REF-8.6`~`8.9` 立项与 `G-023` 登记的直接出处**（2026-10-13） |
| `外部提案评估：KIMI「技能执行」分层方案与 Archify 类技能接入（2026-10-15）.md` | `Analysis` | 外部提案（KIMI k3 分层设计）评估 + Archify 类技能接入：厘清「技能**加载**（`REF-8.3` 已交付）≠ 技能**执行**（无通道）」；逐项裁决 KIMI 6 类组件为本仓已有/更优、2 条值得采纳（执行失败 stdout 回灌自修 / node 缺失优雅降级）；**★致命缺陷**——同容器 `ProcessBuilder` 跑第三方 Node 可经 `HELLOAI_CREDENTIAL_AES_KEY_BASE64` 取到凭据主密钥，隔离位置必须独立容器；接入三路径与 `G-005` **触发条件②**「技能包要被执行」的关系（2026-10-15）。**2026-10-15 复核订正（3 处）**：§5 触发判据归一为机械判据「平台侧是否出现一个 *agent 可调用* 且 *碰宿主* 的工具」（表行② 与同节 bullet 的原矛盾已消解；先例 `D-2026-10-10-2②`）· `REF-8.4` **已交付 2026-10-13**（形态 = 循环自有 `LoopHook` 三档，**非** Advisors）· 「拦截链 = A+B **唯一挂点**」**已作废**（**B 挂拦截链、A 出列落 `ResilientDispatcher`**）。记录 `LOG-20261015-007` |
| `沙箱运作机制与HelloAI沙箱设计分析（2026-10-17）.md` | `Analysis` | 本地三仓源码实测（Octop / octop-harness / DeepSeek Harness）：① Octop = 容器/远程世界（`DockerSandbox` 默认禁网 512MB/1CPU/256pids/120s/100KB + OpenSandbox 远程 + Linux bwrap 自动升级）；② DSH = 同主机进程监禁世界（Linux bwrap→Landlock / macOS Seatbelt / Windows ACL restricted-token，三档策略随调用携带 + fail-closed 拒跑）；③ **可引入开源沙箱**：首选 **OpenSandbox**（Apache-2.0，Octop 官方已集成，MCP 接口 + 独立 server 形态最契合 G-005 裁定）、次选 E2B（Firecracker 微VM，多租户）、避坑 Daytona（2026-06 核心转私有）/ Modal（无自托管）；④ HelloAI 设计蓝图（条件触发时执行）= DSH 策略模型（政策层）+ Octop 声明式 spec / scope 生命周期（执行层）+ **凭据主密钥红线**（独立容器、不共享 env/网络）。本报告为 `G-005` 预案的调研支撑，**不改变「条件触发、不排期」现状**（2026-10-17） |
| `Paperclip 外部Agent接入与CLI适配器借鉴分析（2026-10-21）.md` | `Analysis` | Paperclip（MIT，Node/React）推模型平台源码实测：CLI 适配器 spawn 契约（`--print --output-format stream-json --verbose` + stdin 传 prompt + `--resume` + `--mcp-config --strict-mcp-config` + `--add-dir`）、session 续接四重 AND 与 poisoned 句柄自愈、usage 按 `message.id` 取 max 去重、run-scoped JWT、invokability 10 种 block reason、retry/continuation/recovery 三配额分离、成本 80%/100% 闸门、技能 trustLevel 由文件推导、员工四件套；§4 明确不抄 6 条；**可执行结论已迁 `plan/`**（2026-10-21） |
| `HelloAI 需求对话附件产品对标调研（2026-10-22）.md` | `Analysis` | 外部产品（Claude / ChatGPT / Kimi / 通义 / Dify / RAGFlow）在「需求对话挂附件」上的形态与限制，支撑附件设计的选型；2026-10-23 自 plan 的「设计方案」§2 拆出 |

**留存判据（2026-10-17 定则）**：`research/` **不因「执行完毕」而归档** —— 它与 `review/` 的分工不同：`review/` 是**一次性结论**，结论被消化即可退休；`research/` 是**编号的依据**（`REF-x.y` / `M-*` / `A*` / `N-*` 的出处与取材过程），归档会切断「编号 → 依据」链。

故本表 13 份**全部留原地**，包括 2026-10-09 那批（`Octop全库借鉴分析` / `AgentTeams_*` / `helloai借鉴清单_四项之外` / `helloai四能力完善优先级`）、2026-10-17 新增的 `沙箱运作机制与HelloAI沙箱设计分析` 与 2026-10-21 新增的 `Paperclip 外部Agent接入与CLI适配器借鉴分析`——它们的可执行结论已 100% 转化为 `REF-1`~`REF-6` 并执行完毕，但**仍是这些编号的唯一来源**。只有同时满足「结论已消化 **且** 不被任何活文档引用」的**报告类**文档才迁 `archive/reference/`（判据与 2026-10-17 批次见《治理规则》§3.5）。

## 2.3 计划与方案（`plan/`）

`plan/` 承载**进行中 / 待执行 / 待拍板**的实施计划、方案与执行清单——这是 `design/`（稳定设计）与代码（已落地）之间的**行动层**。

**准入规则**

1. 只收**计划 / 方案 / 执行清单与预案**；已成文的稳定设计 → `design/`，对既成事实的检查 / 审计结论 → `review/`，外部调研 → `research/`。
2. 每份建议声明 `Status`（`Active | Done | Superseded`）+ 依据 + 日期。
3. **执行完成后必须迁入 `archive/implemented/`** 并标 `Done`，`plan/` 只保留进行中项。

| 文档 | 主题 |
|---|---|
| `HelloAI 重构实施计划.md` | 业务主线实施顺序（Event Stream → Dual Executor → AgentRuntime → Capability；2026-10-09：备份/恢复提前到 Sandbox 之前，Fork WONTFIX 后 Event 消费面回到 Recovery 收尾）。**2026-10-17 复核：§2 / §3 / §4 / §7 四节已完成并划线退场；§5 / §8 / §9 主体已落地、仅列尾项缺口（技能回流规范 / 审查侧核验 / gap_kind 后置）—— 各节状态见该文件头「章节状态速查」表** |
| `HelloAI 上线核对清单.md` | **只在部署环境可验**的核对项（口径：本地可验项归各专项验证集，不进本清单）；首节「备份/恢复（`REF-2`）」三条：镜像内 `pg_dump`、服务器侧首次真备份、停机恢复路径 C 演练 |
| `HelloAI 借鉴落地实施计划.md` | 借鉴落地专项（`REF-x.y`，已登记进《差距表》§0 编号口径表）：技能可装配 > 备份/恢复 > RAG（可后置）+ 四项之外 A 档 + `REF-6` 判据登记 + `REF-7` 工作详情快照；含各组验证集与文档回填口径。已裁定：Fork 收缩（`D-2026-10-09-5`）、**REF-3 沙箱降级为条件触发**与 **REF-7 新增**（`D-2026-10-09-6`）。**当前版本与逐项 `Status` 以文档头 / 各节为准，本索引不复述（避免腐烂）** |
| `HelloAI 计划编排与排期总览.md` | 各计划之间的**依赖与冲突视图 + 统一执行次序**（编排视图，**不承载状态**）；含 2026-10-12 横向比对（陈旧快照冲突 / 无编号待排项 `D1~D4`·`E1`）与 `T0~T3` 分层排期 |
| `HelloAI OpenSandbox 沙箱集成计划.md` | **OpenSandbox 沙箱专项**（`S-1`~`S-6`，2026-10-17 建号，已登记进《差距表》§0 编号口径表）：**能力预研 + 契约落地**（用户裁定）——声明式 spec（`S-1`）+ 诚实五边界策略（`S-2`）+ `OpenSandboxSandboxProvider`（`S-3`，Kotlin SDK `com.alibaba.opensandbox:sandbox:1.1.1`）+ 探针（`S-4`）+ 部署拓扑（`S-5`，独立网络、app 不加入）+ 验证集（`S-6`）。**不触发 `G-005` 重新排期**（三触发条件均未命中）、不接业务执行链、不宣称「安全沙箱已完成」；`enabled=false` 默认关闭。调研支撑：`research/沙箱运作机制与HelloAI沙箱设计分析（2026-10-17）.md` |
| `HelloAI Paperclip 借鉴落地计划（2026-10-21）.md` | **Paperclip 借鉴落地专项**（`PC-1`~`PC-19`，2026-10-23 建号，已登记进《差距表》§0 编号口径表）：`PC-1`~`PC-6` 现有机制补硬（唤醒原因入响应 / checkout 409 / 三配额分离 / env 不可覆盖运行时 key / MCP 薄封装复核 / 成本双阈值闸门）· `PC-7`~`PC-11` CLI 推通道（含**开工四前置**，未定则 `PC-7` 挂起、`PC-8`~`PC-10` 可先行）· `PC-12`~`PC-19` 有条件再做 · 明确不借 6 条 · 执行次序与前置待决。**2026-10-23 改号**：旧稿用「阶段 + 序号」寻址（不可被外部引用，且违反治理规则 §6.5 分期标签冻结令），现全部改为 `PC-x`。调研支撑：`research/Paperclip 外部Agent接入与CLI适配器借鉴分析（2026-10-21）.md` |
| `HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md` | **任务拆解领域无关化专项**（`TD-1`~`TD-10`，2026-10-24 建号，已登记进《差距表》§0 编号口径表）：`TD-1` `task.task_archetype` 六类拆分维度（BUILD/DOCUMENT/RESEARCH/DATA/OPERATE/DECIDE）· `TD-2` `sub_task.acceptance_kind` 四类验收判据（AUTOMATED/STRUCTURAL/REVIEWED/ATTESTED）· `TD-3` 拆解提示词按 archetype 分支（**生效开关**）· `TD-4` archetype 判定链 · `TD-5` 验收校验按 kind 分流（**生效开关**）· `TD-6` 拆解期上下文快照 · `TD-7` `uncertainties` 扩两类 · `TD-8` 澄清侧 stall 计数 · `TD-9` 单条 expand+regenerate · `TD-10` ledger 通道（**登记不启动**）。设计见 `design/HelloAI 任务拆解领域无关化设计.md`；差距判定见 `review/HelloAI 任务拆解方案对照与领域无关化设计（2026-10-24）.md`。**不触发 `G-005`**、不改 `sub_task` 四要素契约与 DAG 校验 |
| `HelloAI 需求对话附件实施计划（2026-10-22）.md` | **需求对话附件专项**（`ATT-1`~`ATT-7`，2026-10-23 建号，已登记进《差距表》§0 编号口径表）：`ATT-1` 文本 + PDF 文本层 + 两层 Prompt 注入（清单恒注入 + 正文按预算注入，**已落地**）；`ATT-2` 视觉直读（图片与扫描件共用一条通道）；`ATT-3` 大文件 RAG + 引用回源；`ATT-4` 版面增强（Docling sidecar，**登记不启动**）。**不新增 `G-` 差距项**——能力缺口由 `PRODUCT.md` 与 `G-016` / `G-017` 承载；关联 `ADR-002`（RAG 选型） |

> **2026-10-09 出列记录（`plan/` 只留在办项）**：3 份**已执行完成**的方案 / 清单迁入 `archive/implemented/` 并标 `Done` —— `HelloAI_V2回归测试方案（2026-10-04）.md`、`HelloAI_全面复测清单（2026-10-06）.md`、`deploy-checklist-arch-collect-2026-10.md`。
>
> **2026-10-12 出列记录**：`HelloAI 基础架构调整实施计划.md`（`BASE-1.x`~`BASE-4.x` 批次一~四全部 PASS + 6 项技术债清零）迁入 `archive/implemented/` 并标 `Done`；同批从 §1 权威文档表与本节表格摘出。残留两项归差距表 `G-013`（`active-timeout` 过期触发未实测 + `McpAuthFilter` 有意设计），**不在该专项范围**。
>
> **2026-10-17 出列记录**：`HelloAI_RM存量缺口清偿计划（2026-10-04）.md`（代码质量路线图 `RM5/6/8/9`）**全部执行完成**，迁入 `archive/implemented/` 并标 `Done` —— `RM8`/`RM6` 批 1（2026-10-04）、`RM5` 前向实体泄漏**六项归零 61→0**（批 2~5b，2026-10-04）、`RM9` 上帝类拆分**三笔 3/3 收官**（2026-10-16~17）+ 同批命名合规整改（`LOG-20261017-002`）。归档前实测 `review→task.entity` / `planner→task.entity` / `task→agent.entity` 均为 **0**。

## 2.4 对外手册（`manual/`）

`manual/` 是对外交付的**使用手册**：回答「怎么用」，随接口演进；不承载内部架构决策与进度数字（进度只认《实现差距表》）。

| 目录 | 主题 |
|---|---|
| `executor-duty/` | 外部执行者（Agent）职责手册（`00-manual-contract` ~ `06-consistency-audit` + 拼装产物 `manual-assembled.md`） |
| `helloai-integration-guide/` | 第三方接入指南 + 端到端演练记录 |
| `platform-backup-restore/` | 平台备份与停机恢复流程（`runbook.md`：能力边界 / 备份判据 / 恢复三路径与三门 / 停机清单） |

## 2.5 报告（`review/`）

`review/` 收**对既成事实的检查结论**（审计 / 代码级复核 / 排查 / 独立验证 / 优先级与决策分析），属**时间点快照**：
不承诺排期、**不含待执行动作** —— 待执行的方案与清单一律归 `plan/`（见 [治理规则](文档体系分类与治理规则.md) §3.5）。

文档按**日期或主题**命名（如 `…审计报告（2026-10-02）.md`、`HelloAI_外部Agent_v3反馈代码级复核.md`）。
**本表不逐份维护** —— 该目录文档增删频繁，写死列表必然腐烂；**全量以目录为准**，新报告在对应 `LOG-YYYYMMDD-NNN` 的
「### 关联」段登记出处即可。

## 3. 历史文档

`archive/` 中的文档只用于追溯背景、已完成的设计和被替代的方案。**冻结只读。**

| 子目录 | 存什么 |
|---|---|
| `archive/implemented/` | 已执行完成的改造 / 修复 / 预研方案、Phase 执行方案 |
| `archive/legacy/` | V2 文档体系重组前的旧事实源快照与旧体系文档 |
| `archive/log/` | 已归档的月度 Log（如 `2026-09.md`） |
| `archive/reference/` | 只读参考资料（架构设计参考 / 长期思路 / 历史进度） |

各子目录均带目录级 `README.md`（存放类型 + 准入规则 + 只读纪律）。

**禁止将 archive 中的文档重新当作当前 Roadmap 或架构入口。**

## 4. 读取顺序

AI 编程 Agent 修改代码前必须按以下顺序建立上下文：

```text
README.md
→ 文档体系分类与治理规则.md
→ HelloAI 项目基线文档.md
→ HelloAI 目标架构.md
→ HelloAI 实现差距表.md
→ plan/HelloAI 重构实施计划.md
→ 对应 design/ 专项文档
→ 代码
```

若历史文档与当前文档冲突，以当前文档和代码为准。

## 5. 脚本索引

`../scripts/README.md` 是验证与运维脚本的全量分类索引（87 个 PowerShell + 32 个 Shell + 1 Java 工具 + 1 SQL，按「验收 / 运维 / 外部 Agent 守护 / 一次性」分组），含各脚本前置条件、双平台对实现映射与已知问题。新增脚本前先查该索引。
