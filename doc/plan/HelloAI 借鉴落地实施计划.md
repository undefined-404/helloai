# HelloAI 借鉴落地实施计划

> **状态：Active**
> **编号体系：`REF-x.y`**（借鉴落地专项任务，`x` = 能力组，`y` = 组内序号）。**已按差距表 §7.1「孤儿项回流规则」登记进《HelloAI 实现差距表》§0 编号口径表**，与 `G-xxx` / `RMx` / `B1~B5` / `BASE-x.y` 均不撞号。
> **依据**：`doc/research/` 5 份调研（2026-10-09）+ **2026-10-09 对 helloai 源码与 `~/Downloads/{Octop-main,AgentTeams-main}` 参考源码的逐条复核（v2 修订）**。
> **用户裁定**：`D-2026-10-09-4`（见《差距表》§0「当前生效的取舍决策」）——四项能力优先级、备份/恢复与 RAG 入目标架构、沙箱生产形态、语义位与不可关闭清单同批。
> **性质**：本计划只收**可执行结论**（借鉴落点 → 动作 → 验收 → 验证集 → 回填），不含调研过程（在 `research/`）与稳定设计（在 `design/`）。
> **完成迁移**：执行完成后迁入 `doc/archive/implemented/` 并标 `Done`。
> **最后更新：2026-10-10（v10）**——v1 勘误已并入正文；v2 新增 **11 处动作订正**（会导致回归失败或做不出来的部分）、**每组的验证集与文档回填**、**许可证口径**、**REF-6 载体修正**；v3 **Fork 相关条目全部取消**（`D-2026-10-09-5`，REF-2 收敛为「备份 / 恢复」）；**v4 两项重大调整**（`D-2026-10-09-6`）：① **REF-3 沙箱整组降级为「条件触发」**——当前**没有可隔离的执行对象**，不排期、只留预案（修正原「第 3 优先级」排序）；② **新增 `REF-7`「外部 Agent 工作详情快照」**（差距锚点 `G-020`）。原 §11 待拍板 5 条**已全部裁定**并移入 §12 裁定记录，**本计划当前无待拍板项**；**v5 = REF-1.1 / 1.2 / 1.3 / 1.3b 已完成**（2026-10-09），各组结果与未达成分项见各节 `Status` 行；**v6 = REF-1.4 / 1.5 / 1.6 次序与 REF-1.6 设计定稿**（`D-2026-10-10-1`，见该节）——次序定为 **`REF-1.6 → REF-1.5 → REF-1.4`**；同时落定十条：存储形态走受控存储（不触发 G-005 条件①）、技能正文存 PG、**不建新 ADR**、外部技能目录 **WONTFIX**、命名统一「**技能包**」+ 清单文件 `skill-package-manifest.md`、安装 / 卸载落 `skill_package_audit`、**版本策略**（多版本共存 / 高版本需确认 / 降版走独立接口）、**ACTIVE 选版 + 内容键 `{name}@{version}`**、**内置保护（同名一律拒绝安装）**、**存原始正文**；**v7 = REF-2 实施方案定稿**（`D-2026-10-10-2`，见 §4）——落定六项：执行形态走应用内 `ProcessBuilder` + 前置检查 + Dockerfile 补装 PG 客户端（不做 `docker exec`）、**`ProcessBuilder` 判为不触发 `G-005` 条件①**（故 REF-3 排期不动）、备份落**独立 bucket `helloai-backups`**（对账巡检枚举整桶，共用会被当孤儿删）、定时 + 手动双触发且长操作走异步 + 轮询、仅自动备份按份数淘汰（默认 7）且手动永不淘汰、新增 `platform_backup` 记录表（V105）；**v8 = `REF-2.3` / `REF-2.3b` 落地并验证**（2026-10-10，见 §4 `Status`）——编排层 + 恢复三门 + `platform_backup`；单测 22 例、演练脚本 `verify-backup-restore.ps1` 29 项全过；**v9 = `REF-2.4` + `REF-5.2a` / `REF-5.1` 落地**（2026-10-10）——停机恢复流程手册 + 部署侧上线核对清单（§4）；常量单源化 + 「无替代 Agent」分支可读化（409 + 时间线）与前端字典补齐（§7 `Status`）；**v10 = `REF-5.3` / `REF-5.4` 落地**（2026-10-10，见 §7 `Status`）——初始化期服务锁定（判据取用户数）+ 出站 SSRF 守卫（OkHttp 自定义 `Dns`，URL 层 + 地址层两层），A 档六项已交付四项。

---

# 1. 编号与治理前置

| 项 | 口径 |
|---|---|
| `REF-x.y` 含义 | 借鉴落地专项任务；`x` 取 1~7（技能 / 备份·恢复 / 沙箱〔条件触发〕 / RAG / A 档 / 判据 / 工作详情快照） |
| 与 `G-0xx` 的关系 | 每个 REF 组必须映射到差距锚点：`REF-1 → G-004`、`REF-2 → G-018`（**原 `REF-2.1/2.2 → G-001·G-006` 已随 Fork WONTFIX 取消映射**）、`REF-3 → G-005`（**条件触发**）、`REF-4 → G-019`、`REF-5.1 → G-015`、`REF-5.3/5.4 → 新增 G 项或就地登记`、`REF-7 → G-020` |
| 与 `B1~B5` 的关系 | `REF-3` 即《差距表》§0 所指「现仅 **B2 Sandbox** 未动」的那一项；**REF-3 组内不得另立第二套能力层编号** |
| 与本文件的关系 | REF 项**只在本文件排期**；`G-` 项状态**只在差距表更新**（唯一事实源），双方不得互相复述进度 |
| 孤儿项回流 | 本文件作出的「后置 / 降级 / 不做」决定，必须同批在差距表登记条目或显式记 WONTFIX（差距表 §7.1） |

**治理前置（已完成，2026-10-09）**：`REF-x.y` 入《差距表》§0 编号口径表 ✅；`G-018`/`G-019` 立项 ✅；《目标架构》§13/§14 新增 ✅；《重构实施计划》次序同步 ✅；当月 Log 记账 ✅。

**许可证口径（新增，2026-10-09）**：本计划多处使用「照抄 / 直译」措辞，落地时按上游许可处理——

```text
Octop（MIT）        ⇒ 可自由复制；保留上游版权与许可声明即可
AgentTeams（Apache-2.0）⇒ 可复制；必须①保留版权/许可/NOTICE 声明 ②注明本处已作修改
落地纪律：凡复制上游代码，源文件头部注明来源项目 + 许可 + 是否改动
```

---

# 2. 执行次序（用户裁定 + 依赖约束）

```text
REF-1 技能可装配  ──►  REF-2 备份 / 恢复  ──►  REF-4 RAG 知识库（可后置）
REF-3 沙箱：条件触发（不排期）
REF-5 四项之外 A 档：独立交付，可插队（互不依赖）
REF-7 工作详情快照：能力外延，可后置（触发 = 审计深度需求）
REF-6 判据：随对应组落地，不单独排期
备份/恢复（REF-2）为纯增量、可独立交付，不触任何 MQ 载荷契约
```

> **与主线的对接**：本次序已同步进《重构实施计划》头部——**备份/恢复提前到 Sandbox Provider 之前**，RAG 排在最后。
> **Fork 已于 2026-10-09 裁定 WONTFIX**（`D-2026-10-09-5`）⇒ Event 消费面**回到既有 `Timeline → Replay → Audit → Recovery` 次序**，`Recovery` 留在 P1 剩余项内按原次序推进，**不加 `REF-` 编号**（它不是借鉴项，不需要借 REF 的名义排期）。
> **REF-3 沙箱已于 2026-10-09 整组降级为「条件触发」**（`D-2026-10-09-6③`）：当前**没有可隔离的执行对象**（外部 agent 在它自己终端；内部 agent 的工具面全是平台 API，无 shell / 文件写），故不排期，只保留预案与三个触发条件。
> **REF-7 为本次新增**（`D-2026-10-09-6⑤`）：来自用户对平台定位的澄清——外部 agent 事后经审批提交「全任务工作详情快照」，平台解析后插入执行时间线等审计信息。

---

# 3. REF-1 技能可装配（最高优先）

> 调研依据：`research/helloai四能力完善优先级与借鉴路线.md` §2 + Octop `src/octop/infra/skills/`。
> helloai 现状（2026-10-09 源码复核）：`AgentSkillSpecServiceImpl.KNOWN_SPECS` 编译期硬编码（`:29`、`:148-210`，4 条手工 `put`）；classpath 4 个 `eng-*.md` **无 frontmatter**；`ToolDefinition` 仅 `name + description` 两字段（`ToolDefinition.java:11`）；`ToolRegistry.resolve(List)` 单向 best-effort；技能侧**无任何 Controller**；**无**任何摄入/安装代码。

### REF-1.1 补 frontmatter

> **Status：✅ 已完成**（2026-10-09）。**须遵守的口径**：`fileName` 由文件名推导、**不得写入 frontmatter**（写入即判 corrupt）；技能元数据以 frontmatter 为唯一事实源。

| 项 | 动作 | 验收 |
|---|---|---|
| REF-1.1a | **先改解析**：`AgentSkillSpecServiceImpl` 剥离 frontmatter **之后**再按 `DETAIL_SEPARATOR`（`"\n---\n"`，`:32` 定义、`:129` `indexOf` 取首个命中）切「执行速览」 | 改造前后**同一份 md 的注入输出逐字一致** |
| REF-1.1b | 给 4 个 `eng-*.md` 补 YAML frontmatter，**承载 `SkillPackage` 全 9 字段**（name/version/description/requiredTools/dependencies/inputSchema/outputSchema/validationRules；`fileName` 由目录扫描推导） | 新增解析器能读出 9 字段且与现状逐一相等 |

> ⚠️ **v1 订正（两处硬伤）**：
> ① **不加 REF-1.1a 直接加围栏 ⇒ 回归必挂**：frontmatter 的**闭合围栏**本身就是 `\n---\n`，会被 `indexOf` 先命中，注入内容退化成 frontmatter 块本身。
> ② **只搬 4 个字段 ⇒ 存量元数据丢失**：`eng-code-review` 已实填 `inputSchema` / `outputSchema`；字段集必须取全 9 个。

### REF-1.2 目录扫描替代 `KNOWN_SPECS`

> **Status：✅ 已完成**（2026-10-09）。**未达成项已改判（2026-10-10）**：原「零发版需外部技能目录」的**手段**已裁 WONTFIX（`D-2026-10-10-1④`）——「零发版」的**目标**改由 `REF-1.6` 安装入口直接满足，不再需要文件系统外部目录。

| 项 | 动作 | 验收 |
|---|---|---|
| REF-1.2a | `KNOWN_SPECS` → 目录扫描（classpath `skills/plugins/`；~~可选外部目录~~ —— **2026-10-10 裁 WONTFIX**，见 REF-1.6 节 `D-2026-10-10-1④`） | 新增一个 md 即出现在技能目录，零改码 |
| REF-1.2b | 解析失败**显式报 corrupt 且仍出现在列表中**（不静默跳过） | 单测：坏文件在列表且带 `error` 字段 |
| REF-1.2c | **新增技能目录查询 API**（当前技能侧 0 个 Controller——「出现在列表」缺宿主） | 列表接口可查、坏文件带 `error` |

> ⚠️ **v1 漏项（同批必改）**：
> - `helloai-ui/src/constants/agentSkills.ts:17-27` 是 `KNOWN_SPECS` 的**对齐副本**（含中文 description）——扫描化后必然漂移。 ✅ **已裁定（`D-2026-10-09-6③-4`）**：**改服务端下发**（消费 REF-1.2c 的技能目录 API，前端 5 个消费点改为读 store，拉取失败回退原始标签）；**parity 守卫留给不适合下发的词表**（`AGENT_SKILL_OPTIONS` ↔ 后端 `KEYWORD_SKILLS`/`SYNONYMS`）与**事件码**（`REF-6.8`）。
> - `scripts/powershell/verify-skill-packages.ps1` 靠**正则解析 Java 源码文本**（`List.of(...)`）取声明——元数据迁到 md 后解析基础消失，**必须同批重写**；现有 4 组断言（fileName 存在于 classpath / requiredTools ⊆ 已注册 `@Tool` / version 三段式 / name+fileName 唯一）**等价保留**。

### REF-1.3 工具注册的「条件可用」与「动态描述」

| 项 | 动作 | 验收 |
|---|---|---|
| REF-1.3 | **新增**两个语义位——「按条件可用」（`false` 即从工具列表**摘除**）与「按上下文动态描述」（每轮重写 description） | 无 KB 时 `search_knowledge` 不在工具列表；每轮 description 反映本轮可见资源 |
| REF-1.3b | 同批实现 `CRITICAL_TOOLS` 式**不可关闭清单**（写进禁用列表也被剔除） | 单测：把关键工具写入禁用列表后仍在列表内 |

> **Status：✅ 已完成**（2026-10-09）。**需调整一处**：本组原按「`resolve` 仅 2 个调用点、且都在每轮装配时调用 ⇒ 语义位天然生效」排期，该前提**不成立**（两处调用的结果都不进模型可见工具）⇒ **实际范围含「同批改造消费点」**（`resolve` 结果成为模型可见工具的唯一判据），复用本组做同类改造时按此预估工期与风险。
> **未具备**：动态描述的生产消费者（待 `REF-4.1`）；字面验收项 `search_knowledge` 随 `REF-4`；生效粒度 = Turn（非每次迭代）。过程、判据与边界正文见 `LOG-20261009-012` 与 `design/Agent_Runtime.md` §ToolRegistry 语义位。

> ⚠️ **v1 订正（三处）**：
> ① **前提不成立**：所谓「planner 工具收窄**从硬编码变配置**」——现状**根本没有收窄机制**（`excludeTool` / `disabledTool` / `availability` 全库 0 命中）；唯一近似是 **Agent-工具绑定层** `agent_mcp_server.is_enabled` + `getEnabledToolsForAccess`。本项应表述为「**新增**可用性语义位」，**双层边界须显式界定**（用户裁定 `D-2026-10-09-4④`）：绑定层 = 某 Agent 是否启用某工具（DB 事实）；语义位 = 平台/运行时事实是否具备该能力（进程内事实）。**「禁用了」与「不具备」分开表达**。
> ② **别塞进 record**：`ToolDefinition` 是 record，追加 `Predicate` / `Function` 字段会破坏值语义与可序列化。语义位应落在 **`ToolRegistry.resolve(...)` 的上下文参数**上（新增 `ToolContext`），而不是 record 的字段。 ✅ **已裁定采纳（`D-2026-10-09-6③-1`）**：实施时**只在 Tool 侧加参**，`AgentSkillSpecService.resolve(List)` 签名不动（避免污染 Skill/Tool 共用元数据面）。
> ③ **它不再是拆巨类的前置**：v1 曾称「先语义位、再拆 `McpToolServiceImpl`」——实测该因果不成立（该类 `requireAuthId` **0 处**，头注释 `:57-74` 记录 2026-08-23 书面「不拆」结论）。语义位对 `McpMcpServer`（真正的 `@Tool` 协议层）更有意义。

### REF-1.4 / 1.5 / 1.6 来源标记与摄入闭环

> **执行次序（2026-10-10 定稿，`D-2026-10-10-1`）：`REF-1.6 → REF-1.5 → REF-1.4`**，取代 v1 的两段式（「REF-1.6 → REF-1.5」）：
> ① **REF-1.6 先**：安装入口是**闸门与来源标记共同的宿主**，无它两者无处可挂。
> ② **REF-1.5 紧随**：最简闸门**必须与 REF-1.6 同批**——否则等于上线一个无校验的对外摄入面，与仓库既有的「消除 fail-open」纪律相悖；本组落最简版，**完整攻击用例矩阵与阈值精化归 REF-1.5**。
> ③ **REF-1.4 最后**：`origin` 的取值来源（`BUILTIN` / `INSTALLED`）与 `locked` 的下游改写检测，都依赖包模型与**摘要**先存在。
> ④ **REF-1.4 的字段部分并入 REF-1.6 建表**（`origin` / `locked` 只是 `skill_package` 上的两列，零成本）；REF-1.4 只留「拷贝进 Agent 工作区时文本打戳」那半。

> **Status（2026-10-10）**：
> - **`REF-1.6` ✅ 已完成** —— 安装 / 激活（回滚、降版）/ 卸载 + 审计；V104 迁移（两表 + `skill:install` / `skill:uninstall` 权限）；`/api/skills/packages` 四端点；目录双源（classpath 内置 + PG 已安装）。e2e 脚本 `verify-skill-package-install.ps1` **15/15 全绿**。
> - **`REF-1.5` ✅ 已完成** —— 两段式闸门（`SkillPackageZipGate`）：文件数 / 解压总量 / 压缩比（仅 >1MiB）/ 路径穿越 / symlink / 非普通文件 / 加密位 / 清单存在 / 严格 UTF-8。`SkillPackageZipGateTest` **16/16**（15 类攻击各一「必失败」用例 + 1 正常路径）。
> - **`REF-1.4` ⚠️ 部分完成（能力已落、未接线）** —— `origin` / `locked` 字段随 V104 建表已落；`copy_policy ∈ {SNAPSHOT, LOCK, DENY}` 语义与打戳器 `SkillPackageStamper`（含「下游改写可识别」，单测 **6/6**）已实现。**但本仓库不存在「把技能包拷贝进 Agent 工作区」的动作**（全库检索零命中，详见 `LOG-20261010-005`），按用户裁定**只落能力、不接路径**。**触发条件**：平台出现该拷贝动作时，必须经由本器打戳并补端到端用例。

| 项 | 动作 | 验收 |
|---|---|---|
| REF-1.6 | **安装入口 + 受控存储**：上传 zip → 过最简闸门 → 解析 → 落 PG（元数据 + 正文）+ MinIO（原始 zip 存档）→ 进技能目录；含卸载与审计 | 端到端：导出再导入可解析，且 `/api/skills/catalog` 可见 |
| REF-1.5 | **摄入安全闸门（完整版）**：攻击用例矩阵 + 阈值精化 + 可读拒绝码（最简版随 REF-1.6 落地） | 每类攻击各一个**必失败**用例 |
| REF-1.4 | 技能来源标记 `origin` + `locked` 的**下游打戳**（字段已随 REF-1.6 建表） | 单测：带标技能被下游改写时能被识别 |

**REF-1.6 关键设计裁定（2026-10-10，`D-2026-10-10-1`）**

```text
① 存储形态（决策 A）：技能包落「受控存储」（PG 元数据+正文 / MinIO 原始 zip），不走宿主文件系统
   ⇒ G-005 触发条件①「平台增加碰宿主的工具（自持 shell / 文件写）」不成立，
     REF-3 沙箱维持「条件触发，不排期」；但**不得**据此宣称「安全沙箱已完成」（协作规约 §22）。
② 正文存 PG（D-a）：resolve() 在每轮装配热路径读技能正文（loadSpeedSummary），
   若正文只在 MinIO，则「MinIO 故障 ⇒ 技能注入整链断」；落 PG 使热路径零外部 I/O。
   MinIO 只存**原始上传 zip**（溯源 / 完整性 / 再分发）。
③ 不建新 ADR：全仓仅 ADR-001（Run/Turn/Step 执行模型定稿），门槛 = 地基级模型定稿；
   本项属既有 G-005 判定的**适用判定**，记《差距表》§0 决策区。
   升级触发：出现「平台与宿主文件系统的边界」这类系统性议题时再升 ADR。
④ 不造「外部技能目录」（WONTFIX）：原 REF-1.2a 的「可选外部目录」取消——
   「零发版」的目标由安装入口直接满足；保留目录会与受控存储形成**两个事实源**，
   且 Docker 部署需挂卷。按 §1 孤儿项回流，同批在《差距表》登记 WONTFIX。
⑤ 命名（决策 B）：中文统一「技能包」，英文 Skill Package；清单文件 = `skill-package-manifest.md`（根，UTF-8）。
   理由：代码族（SkillPackage / skills/plugins / /api/skills/* / skill:view / 前端常量）全是 skill，
   文档侧改一个词的成本远低于改代码族；用 capability 会给同一事物再造第二个名字。
   本项**取代** v1 的「能力包 = Capability Package」建议。
⑥ 审计：安装 / 卸载显式落 `skill_package_audit`（操作人取自 Sa-Token），对齐凭证域
   CredentialAuditLog 先例——「安装」是供应链入口，非普通管理端 CRUD。
   ⚠️ 连带发现：MyBatisPlusMetaObjectHandler.getCurrentUser() 为硬编码桩（恒返 "system"），
   全平台 create_by 不记操作人 —— 属平台级问题，**本组不改**，另行立项。
⑦ 版本策略（2026-10-10 追加裁定）：唯一键 `(name, version)`，**多版本共存**（回滚需要历史行）。
   安装命中「同名 + 版本更高」⇒ 不直接装，返 409 要求确认。**实现注记（2026-10-10）**：原写的
   `{needsConfirm, currentVersion, incomingVersion}` **结构化载荷未实现**——现有 `R.fail(code,msg)`
   不带 data、`GlobalExceptionHandler` 也只透 code+msg，做成结构化须改全局异常处理器，超出本组范围；
   实际返回**可读消息** `[NEEDS_CONFIRM] 当前生效版本 X，新版本 Y 更高；…`，调用方若需当前版本
   可查 `GET /api/skills/packages`（安装 UI 本来就要展示该列表）。偏差详见 `LOG-20261010-005` 决策 3。
   带 `confirmUpgrade=true` 重发才覆盖；「同名 + 版本更低或相同」⇒ 拒绝。
   回滚 / 降版**不复用安装入口**，走 `POST /api/skills/packages/{name}/activate`：
   目标版本已在库 ⇒ 直接翻 ACTIVE（不重传包体）；不在库 ⇒ 须带包体（低版本覆盖）。权限复用 `skill:install`。
   版本比较 = 三段式数字逐段按数值比较（对齐 verify-skill-packages.ps1 的「三段式数字」断言，不引入 pre-release）。
⑧ 选版与内容键：同 name **至多一行 ACTIVE**（partial unique index `WHERE state='ACTIVE'` 保证）；
   目录层只出 ACTIVE 行（**取代** `byName()` 的静默 `map.put` 覆盖，`:69`）；
   内容键 = `{name}@{version}`（多版本共存后 `name` 不再唯一指向一份内容）。
   内容读取抽 `SkillContentSource`（classpath 实现 + DB 实现），
   `AgentSkillSpecServiceImpl.loadSpeedSummary` 不再硬编码 `ClassPathResource`（现 `:121`）。
⑨ 内置保护：与内置 `eng-*` 同名**一律拒绝安装**（不论版本高低）——内置随发版走、不可替换，
   否则发版基线与运行内容会分叉。
⑩ PG 存**原始正文**（非预渲染速览）：保真、渲染器升级后存量包自动重算；
   `resolve()` 每轮装配的渲染成本与现状同构（今天 classpath 亦是每轮读取）。
```

**DB / API / 组件（REF-1.6）**

| 面 | 内容 |
|---|---|
| 迁移 | **V104**：`skill_package`（元数据 + `body` 原始正文 + `origin` / `locked` + `checksum_sha256` + `origin_zip_url` + `state`(ACTIVE / HISTORICAL / DISABLED)；唯一 `(name, version)` + **partial unique `(name) WHERE state='ACTIVE'`**）+ `skill_package_audit` + 播种 `skill:install` / `skill:uninstall`（按 `V103` 体例） |
| API | `POST /api/skills/packages`（`skill:install`；命中「同名 + 更高版本」返 409 + `confirmUpgrade=true` 重发）· `POST /api/skills/packages/{name}/activate`（`skill:install`；回滚 / 降版）· `GET /api/skills/packages`（`skill:view`）· `DELETE /api/skills/packages/{id}`（`skill:uninstall`）；既有 `GET /api/skills/catalog` **改为合并双源**（classpath 内置 + 已安装，只出 ACTIVE） |
| 存储接线 | `ArtifactStorage` **加重载** `store(ownerName, fileName, content)`——现有签名带 `taskId` / `subTaskId`，技能包没有 |
| 目录 | `SkillPackageCatalog` 双源合并（classpath + DB），**按 ACTIVE 行选版**；`SkillPackageScanner` 只管内置源（不动）；`refresh()` 保持为安装后的失效钩子；新增 `SkillContentSource`（classpath + DB 两实现）供正文读取 |
| 分层 | Controller 只收 / 校验 / 转（§10）；闸门 + 解析 + 落库 + 审计收在一个 `@Transactional` Service（§8.2 / §14.1） |
| MQ | 无（不触任何载荷契约） |
| 回滚 | Flyway 无 down ⇒ ① `git revert` ② `UPDATE skill_package SET state='DISABLED'` 停用已装包 ③ 表与 MinIO 工件保留（不删，保审计与溯源） |

**闸门阈值（含 v1 订正 + 2026-10-10 改名）**：

```text
文件数 ≤ 2000              解压总量 ≤ 64MB              上传体 ≤ 32MB
压缩比 > 100 拒 —— 但【仅对 > 1MiB 的文件判定】（小文件高压缩比属正常，照抄会误伤）
路径含 ".." / 绝对路径 / 反斜杠 / 盘符前缀 拒
symlink 拒（目录与文件）        非普通文件拒        加密 zip 拒（加密位）
清单必须含根 skill-package-manifest.md（且 UTF-8 可解码）
```

> ⚠️ **v1 订正（保留供追溯）**：
> ① 压缩比阈值**不可无条件套用**（上游对小文件豁免）。
> ② ~~顺序应调整为 REF-1.6 → REF-1.5~~ —— **已由 2026-10-10 的「REF-1.6 → REF-1.5 → REF-1.4」取代**（见本节开头）。
>
> ⚠️ **术语红线**：`AdminAgentController.getMySkillZipByAgentId`（`:378`）是**外部 Agent 角色接入手册 ZIP**（交付物内名 `SKILL.md`），与 `skills/plugins/*.md` **技能包**是两件事——《文档体系分类与治理规则》§3.7 **禁止混用 SKILL 一词**（活文档四处登记：治理规则 `:160`、`doc/README:53-54`、本节、`SkillPackageResponse.java:18-20`）。REF-1.6 的安装 / 卸载必须使用**不同 API 路径与不同术语**；本组按 `D-2026-10-10-1⑤` 定名——**技能包 = Skill Package / `skills`**，接入手册 = Onboarding Guide。（v1 建议的「能力包 = Capability Package」已按同一裁定退役。）
>
> ⚠️ **许可证**：闸门借鉴 Octop（MIT）zip 安全清单 ⇒ 源文件头须标「来源项目 + 许可 + 是否改动」（口径见本文件 §「许可证口径」）。

**REF-1 验证集**

```text
Required   ：verify-skill-packages.ps1、verify-agent-skill-capability.ps1、verify-skill-package-install.ps1（安装 → 同版本拒 → 需确认 → 确认升级 → 目录可见 → 回滚 → 卸载）、闸门攻击用例（SkillPackageZipGateTest 15 类）、InstalledSkillResolveTest（双源注入）
  ⚠️ **分工订正（2026-10-10）**：原写「verify-skill-packages.ps1 **须扩展为双源**」**未采纳且不采纳**——
  该脚本是**纯静态**校验（扫 classpath md + 静态扫 Java 里的 @Tool），而已安装包在 PG 里、静态脚本看不见；
  强行扩展会把它从静态守卫变成运行时脚本。**双源覆盖改由分工承担**：静态守卫管内置源，
  verify-skill-package-install.ps1（经 API）与 InstalledSkillResolveTest（经替身源，零 DB）管已安装源。
Regression ：verify-tool-matrix.ps1（`requiredTools` 联动）、verify-a2-skill-derive.ps1、verify-a3b-agent-edit-skills.ps1、verify-planner-decompose.ps1
Diagnosis  ：.tmp/diag-skill-scan.*（仅诊断，不得作为正式验证）
门禁       ：bash scripts/ci/ci-gate.sh
```

---

# 4. REF-2 备份 / 恢复

> 调研依据：`research/AgentTeams_Octop_源码复核与helloai借鉴对照.md` §1 + Octop `infra/backup/`。
> helloai 现状（2026-10-09 源码复核）：REWORK 闭环完整；**备份完全空白**。
> **MinIO 是生产主存储**：`storage.type=minio`、bucket `helloai-artifacts`；`docker-compose.yml:101-127` 定义容器（29000/29001）；`helloai-core` 依赖 minio SDK（8.5.12）；`system/storage/` 有完整 `ArtifactStorage` 抽象（Minio/Local/Composite + 对账巡检）→ 备份方案的「MinIO 对象清单」可直接落地，无需新增基础设施。

> **v3 范围收缩（用户裁定 2026-10-09，`D-2026-10-09-5`）：Fork 相关条目全部取消** —— 原 `REF-2.1`（触发入口 / 驱动新 Run 执行 / seq 并发加固）与 `REF-2.2`（快照可观测 / 前端三处登记）**不再执行**。理由：①「驱动执行」自 2026-10-04 拍板起即为后置项；② 实际用法已被 **Return**（闭环完整）与 **Replay 工作台**覆盖；③ 驱动执行须改 ADR-001 的 Run 标识模型 + execution command 载荷（协作规约 §30/§31）。
> **处置**：`AgentEventForkService`（108 行，零生产调用方）**保留**为未接线的内部能力，不删不接线；回流登记见《差距表》§7.1.2 `R1`/`R2`。
> **连带收益**：去掉 Fork 后，本专项**不再改动任何 MQ 载荷契约**，也不再触及 `frozen` 列、轮次编号归属与 Replay 的 run 归属裁决。
> **仍成立的判据**（不随 Fork 取消而失效）：`sub_task_dispatch_fallback` 后端已落 timeline 而前端三处登记 0 命中——「后端落库 ≠ 用户可见」，该反例继续作为 `REF-5.1` 与 `REF-6.8`（key parity 守卫）的依据。

### REF-2.3 / 2.4 备份 / 恢复

| 项 | 动作 | 验收 |
|---|---|---|
| REF-2.3 | `pg_dump -Fc` 全库 + **MinIO 对象清单（复用 `ArtifactStorage.listObjects` 递归枚举）** + manifest 前置 peek + **Redisson 单飞锁** + 仅淘汰自动备份 | 备份可恢复；运行中备份不锁库；手动备份永不被自动清理误删 |
| REF-2.3b | **恢复侧安全闸门**：跨引擎拒恢复 / schema 版本高过运行时拒恢复 / 在线恢复拒绝（均给出可读原因） | 三类非法恢复各有一个必拒绝用例 |
| REF-2.4 | 停机恢复流程文档化（诚实边界：运行中备份**不保证**多文件同一瞬间） | 文档成文并随能力交付 |

> **Status（2026-10-10，v9）**：`REF-2.3` / `REF-2.3b` **已交付并验证**（编排层 + 恢复三门 + `platform_backup` V105；单测 22 例全绿、演练脚本 `verify-backup-restore.ps1` 29 项全过，含「字典序版本」安全回归）；`REF-2.4` **已交付**（停机恢复流程手册 `doc/manual/platform-backup-restore/runbook.md`：能力边界 / 备份判据 / 恢复三路径与三门 / 停机清单）。同批补上 ① 承诺而此前**未落地**的生产前置 —— `Dockerfile` app 阶段装 `postgresql-client-16`（**实测**：容器内 `pg_dump` / `pg_restore` 均为 16.15，与 `postgres:16.4` 服务端同主版本；基础镜像同时钉到 `-noble` —— 浮动 tag 现已指向 Ubuntu 26.04，其源里没有 16 客户端）。验证见 `LOG-20261010-007` / `LOG-20261010-008`。落地方案见下。

**实施方案（2026-10-10 定稿，`D-2026-10-10-2`）**

```text
① 执行形态（P1 = A1）：应用内 ProcessBuilder 执行**可配置路径**的 pg_dump / pg_restore。
   启动做前置检查（`pg_dump --version`）；不可用 ⇒ **备份功能显式不可用**（不静默，其余功能不受影响）。
   生产：Dockerfile 的 app 阶段补装 postgresql-client-16（客户端主版本须 ≥ 服务端 16）；
   本地（**已落地，B 路线**）：PG 官方 zip 版二进制——**免安装、不注册服务**——解压到仓库内
   `.tools/pgsql/`（72MB，**已 gitignore**；与 JDK / Maven 同类的「本地源码开发」前置，
   不违背「部署用 Docker 统一」：服务器一键部署由镜像内的 postgresql-client 提供，零宿主依赖），
   经 `helloai.backup.pg-dump-path` 指向。
   实测：`pg_dump` / `pg_restore` 均为 **16.4**（与服务端 `postgres:16.4-alpine` 同主版本）；
   对运行中的库 dump 出 **2.85MB** 归档，`pg_restore -l` 可读（TOC 956 条，**不解档**）。
   ⚠️ **顺带证实 ⑦① 的判据来源**：`pg_restore -l` 头部直接给出
   `Dumped from database version: 16.4` / `Dumped by pg_dump version: 16.4` ——
   「跨引擎拒」读的就是这里，无需解档。
   **明确不做 docker exec** —— 那要挂 docker.sock，与 D-2026-10-09-4③ 的裁定相悖。
   ⚠️ 实测三环境：app 镜像无客户端 / 本地 Windows 无 / 仅 PG 容器内有 —— 这是本组第一前置。
② G-005 边界（P2 = **不触发**）：ProcessBuilder 是平台首次获得「执行宿主可执行文件」的能力
   （全仓此前 ProcessBuilder / Runtime.exec 零命中），但 G-005 条件① 的语境是**Agent 可调用工具面**
   （其判据逐主体分析的是"agent 能碰什么"），备份是**管理端例程、非 agent 工具** ⇒ 不构成触发。
   REF-3 沙箱排期因此**不动**。
③ 落点（P3）：**独立 bucket `helloai-backups`**，不复用 helloai-artifacts。
   硬依据：ArtifactStorageReconcileServiceImpl:70 是 listObjects(bucket, null) **枚举整桶**，
   凡无 DB 记录的对象都是孤儿候选 —— 备份若落共用桶，一旦开 orphan-cleanup-enabled 就会被当孤儿删掉。
   且 ArtifactStorage.store(...) 只写配置的单一桶 ⇒ 需**新增独立写路径**，不硬塞进"产物"语义。
④ 触发：**两者都要** —— helloai-job 的 ShedLock 定时（与既有 18 个任务同构）+ helloai-api 管理端手动端点。
   长操作走**异步 + 状态守卫 + 轮询查询端点**（学拆解 PlannerDecomposeAsyncServiceImpl:103-105 的范式；
   仓库无"返回 taskId 让前端轮询"的通用范式）。
⑤ 保留策略：仅对**自动**备份按份数淘汰（默认 **N=7**，可配）；**手动备份永不淘汰**（计划硬要求）。
   注：GFS（keep-daily/weekly/monthly）留作后续精化，本批不做。
⑥ 记录表：新增 `platform_backup`（迁移 **V105**，形态对齐 V67__create_credential_audit_log.sql）——
   记 谁/何时/类型(手动|自动)/对象键/摘要/大小/状态；同批播种管理端权限码。
⑦ 恢复侧三门（先于任何 pg_restore 执行）：
   ① 跨引擎拒 —— dump 的 PG **主版本** ≠ 运行时主版本；
   ② schema 高过运行时拒 —— manifest 携带 flywayMaxVersion，> 代码内已知最高迁移号即拒；
   ③ 在线恢复拒 —— 存在在飞子任务 / 活动连接即拒（计划 REF-2.4 是「停机恢复」）。
⑧ 配置：`BackupProperties` 放 **helloai-common · com.helloai.common.config**（全仓 20+ 个 @ConfigurationProperties
   的既定落点），前缀 `helloai.backup`。DB 连接信息**独立配置 + 默认从 spring.datasource 派生**（防两处漂移）。
⑨ 组件落位：BackupScheduler → **helloai-job · job.task**；DatabaseBackupService / PgDumpRunner /
   BackupStorage / BackupManifest / RestoreGate → helloai-core 新增 backup 域；BackupController → helloai-api（薄透传）。
⑩ 危险操作防护：恢复演练**只对一次性隔离库**（Testcontainers 或临时 PG 容器），
   **绝不指向 localhost:15432/helloai**；启动即打印目标库，检测到非隔离目标直接拒跑。
   ProcessBuilder 以 **env 传 PGPASSWORD**（不进命令行，避免密码进 ps）。
```

> ⚠️ **v1 漏项（三条硬约束）**：
> ① **恢复侧闸门不可省**：这是备份功能里风险最高的一段（上游 `system_archive.py` 中约 650 行）。无闸门的「可恢复」= 可把生产库恢复成不一致状态。
> ② **`listObjects` 为空必须显式失败**：`ArtifactStorage.listObjects` 是 **fail-safe 默认返回 `List.of()`**（`:145-147`）；「枚举为空」既可能是**真空桶**，也可能是**实现不支持**。静默通过 ⇒ 产出「完整备份」但没有对象，**故障直到恢复时才暴露**。
> ③ **单飞锁用 Redisson，不用上游形态**：上游是**进程内 `asyncio.Lock`**（其项目默认单进程）。helloai 已有 Redisson 4.0.0 `RLock`（`SubTaskReviewServiceImpl:233-249` 范式）与 ShedLock 6.6.0 `@SchedulerLock`，直接用前者。
> **可复用资产**：`deploy/middleware/scripts/migrate.sh:67-81` 已有 `pg_dump -Fc` 与 `pg_restore --no-owner --no-privileges -j 4` 的现成写法。

**Return 部分**：REWORK→驳回→改派→重开工闭环已完整，**不新做**。原「建议加一条断言：改派时 `task_timeline` 保留」
**已落地**（`verify-subtask-redispatch-auto-execution.ps1` 改派前后取时间线 id 快照，blocked 场景实测 `retained=1/1`）。

**REF-2 验证集**

```text
Required   ：新增 verify-backup-restore 演练脚本（备份 → 恢复 → 对账，含三类非法恢复必拒绝用例）
             ✅ 已交付（2026-10-10）：`scripts/powershell/verify-backup-restore.ps1`（S0..S7，29 项）；
                三类非法恢复必拒绝用例落 `RestoreGateTest`（单测，线上造不出伪造归档头/manifest）；
                真恢复只对一次性探针库，app 一键恢复端点按停机操作不端到端跑（脚本打印说明，不假装验过）
Regression ：✅ 已跑（2026-10-10）：`verify-c3-reconcile.ps1` PASS=6 FAIL=0（另跑 DB 侧探针：窗口内可判定行失配 0）；
             `verify-subtask-redispatch-auto-execution.ps1 -Scenario blocked` 通过（**含改派后 `task_timeline` 保留 1/1**）；
             `verify-minio-artifact.ps1` PASS=6 FAIL=0；`verify-c3-rollback.ps1` 判 **N/A** —— 它依赖的 `gray-percent`
             已随 G-002 单轨硬切删除（演练前提消失，非 FAIL）。
             ⚠️ 跑通前先修掉三处脚本漂移（本轮一并修）：退役路径（`/block` `/reassign` `/sub-tasks/{id}` → `*ById`）、
             管理端鉴权头 `Authorization: Bearer` → `X-Admin-Token`、MinIO 健康检查拿基址直打根路径（补 `/minio/health/live`）。
             ⚠️ offline 场景 **BLOCKED**：两场景 target agent 硬编码同一模型，平台限「同角色同模型唯一」⇒ 先跑者占用，后跑者 409。
门禁       ：✅ `bash scripts/ci/ci-gate.sh` **5/5 全过**（累计用例 2128；B 级集成 29 例 0 失败）
Deploy     ：部署侧核对项（镜像内 `pg_dump` / 服务器侧首次真备份 / 停机恢复路径 C 演练）见
             `doc/plan/HelloAI 上线核对清单.md` §1 —— 本地走 `.tools/pgsql` + 本地 MinIO，
             与服务器「镜像内客户端 + 自建桶」是两条路径，**本地通过不代表部署通过**
```

---

# 5. REF-3 沙箱（B2 能力层）——**整组降级为「条件触发」**

> 调研依据：`research/helloai四能力完善优先级与借鉴路线.md` 第 3 组 + Octop `src/octop/infra/backend/probe.py` + AgentTeams `internal/backend/sandbox/plugin.go`。
> helloai 现状：`ExecutionPolicy` 五边界 record 已设计、诚实标注「无真实沙箱」；`EnvironmentSandboxProvider` 唯一实现只做环境路由（53 行）；`Sandbox_Provider.md` `Status: Planned`。

> ## ⚠️ v4 重大调整（用户裁定 2026-10-09，`D-2026-10-09-6③`）：本组**不排期**，改为「条件触发」
>
> **结论：REF-3.1 ~ REF-3.5 全部暂缓；`ExecutionPolicy` / `SandboxProvider` 契约保持现状，不再扩展。** 现状不是「隔离能力弱」，而是**没有被隔离的执行对象**。
>
> **核实依据（2026-10-09）**——逐主体看「沙箱拦谁」：
>
> | 主体 | 在哪跑 | 平台能否/该否隔离 |
> |---|---|---|
> | 外部 agent（Qoder / Claude Code / Codex） | **它自己的终端** | 不能也不该——这正是平台定位（派单方 ≠ 执行方） |
> | 内部 LLM agent（`API_KEY_LLM`，平台跑 AgentLoop） | 平台进程内 | 工具面 = 平台 API，**无可隔离面**（见下） |
>
> - **内部 agent 的工具面无可隔离面**：`McpMcpServer` 的 13 个 `@Tool` 全是订单生命周期操作（`pullTasks` / `claimSubTask` / `heartbeat` / `uploadArtifact` / `submitResult` / `getDepsSummary` …），**`File` / `Path` / `ProcessBuilder` 在该文件 0 命中**；唯一碰存储的 `uploadArtifact` 走 `ArtifactStorage` 抽象，已有路径穿越守卫（`LocalArtifactStorage:51-53`）。
> - **平台不执行任何外部内容**：`ProcessBuilder` / `Runtime.getRuntime` / `ScriptEngine` / 表达式求值器（Groovy / SpEL / Aviator / QLExpress）在 `src/main` **全库 0 命中**；`onboarding/executor/scripts/*` 是打包给外部 agent 的交付物。
> - 故「五边界」中对 helloai **当前唯一有真实意义的是网络边界**——而它的正确实现是 **`REF-5.4` 的出站 SSRF 守卫**（平台自己发起的外联），**不是容器网络隔离**。
>
> **三个触发条件（任一成立 ⇒ 本组重新进入排期，届时启动专项）**：
>
> ```text
> ① 平台增加「碰宿主」的工具（自持 shell / 文件写）
> ② 技能包要被执行（Octop 那种「技能带脚本、平台跑脚本」的形态）
> ③ 平台自持浏览器（README 待办里的 WEB_BROWSER 真实接入链路）
> ```
>
> **连带修正**：原「`REF-1.5` 技能脚本执行依赖本组」这条硬约束**已在当前形态下自动满足**——平台本来就不执行脚本，故技能摄入只做「安装 / 解析 / 校验」的限制无需额外机制即可成立；**若将来要开放脚本执行，那本身就是触发条件 ②**。

## 预案（保留调研结论，触发后再执行）

> 以下是触发条件成立时的执行预案，**当前不作为待办**。`REF-3.1` / `REF-3.2`（声明式 spec）排在专项之前，其唯一目的是给专项提供确定的 spec 形状。

| 项 | 动作 | 验收 |
|---|---|---|
| REF-3.1（预案） | `ExecutionPolicy` 补 `DockerPolicy` / `BubblewrapPolicy` 静态工厂（只声明事实）；`EnvironmentSandboxProvider` 增加解析分支（**先解析不执行**） | 单测：`SandboxContext(docker 配置)` → 返回带非 NONE 五边界的 `Sandbox` |
| REF-3.2（预案） | 定义 `SandboxSpec`（声明式）：type + 五边界字段 + scope；**必须是纯声明，不得内嵌 Docker API 调用参数**（否则换成独立服务要返工） | 同一份 spec 能渲染出容器创建参数；配置可被单测断言 |
| REF-3.3（预案） | **形态已裁定 = 独立沙箱服务**（低权限面，`D-2026-10-09-4③`）；即**不走**「把 `docker.sock` 挂进 app 容器」路线。专项须产出 ADR + 部署拓扑变更清单（compose / `.env` / 基线技术栈表） | 生产形态可验证（**Testcontainers 只证明「本机能起容器」，不证明部署形态可行**） |
| REF-3.4（预案） | **照抄 probe**：写→读→删真实往返 + 失败路径也 `destroy()` | 探针结果落 timeline（可观测登记） |
| REF-3.5（预案） | `scope`（agent/user/fixed）与容器生命周期：不自动销毁，显式回收 | 单测：「同 Agent 复用同一容器」「删除 Agent 不删容器」 |

> **触发后落地时，三条别照抄上游**：
> ① **spec 字段集不能照抄**：AgentTeams 的 claim spec 只有 8 字段且**完全没有网络配置项**——五边界里的**网络边界没有可抄的源**，必须自己设计（建议默认 `allow_network=false`）。
> ② **`Capabilities` 语义先定死**：上游注释说「零值回退 `MaxCapabilities`」，实现却是布尔 AND（零值 = **全部关闭**），**注释与实现相反**。若采用「min(声明, 配置)」语义，先定语义并写单测钉住。
> ③ **诚实边界**：《协作规约》§22 + 《目标架构》§7 —— **不得在无真实隔离时宣称「安全沙箱已完成」**。

**REF-3 验证集（触发后适用）**

```text
Required   ：单测（spec 渲染 / 五边界非 NONE / scope 复用）+ 探针用例（含失败路径回收）
Regression ：verify-agent-execution-preview.ps1、verify-c3-events.ps1（ENVIRONMENT_RESOLVED 语义不变）
门禁       ：bash scripts/ci/ci-gate.sh
```

---

# 6. REF-4 RAG 知识库（可后置）

> 调研依据：`research/helloai四能力完善优先级与借鉴路线.md` 第 4 组 + Octop `src/octop/infra/knowledge/`。
> helloai 现状：**完全空白**（pgvector / embedding / 知识库零命中）。

| 项 | 动作 | 验收 |
|---|---|---|
| REF-4.0a | **基础设施**：PG 镜像换 `pgvector` 版（现 `postgres:16.4-alpine` 无扩展）+ `CREATE EXTENSION vector` | 迁移可执行；两份 compose 与 deploy 文档同步 |
| REF-4.0b | **架构决策**：嵌入模型供应商 / 维度 / 密钥管理（`credential_vault`）落 `design/adr/ADR-00x` | ADR 成文 |
| REF-4.1 | 先定「什么不许进上下文」：无 KB 即摘工具（**消费 REF-1.3 语义位，已具备**）+ 注入预算（`char_budget` 契约 + 单测断言） | 无 KB 时 `search_knowledge` 不在工具列表 |
| REF-4.2 | pgvector 存储与检索（**不抄上游 SQLite 侧库**，PG 单后端优势） | 检索命中正确；预算截断生效 |
| REF-4.3 | 引用溯源 marker：检索结果尾部附 marker，前端渲染卡片、喂模型前剥掉 | 报告/核验意见可点开引用来源 |

> **接线口径（`CODE_STYLE §35.1`）**：`search_knowledge` 属**程序化能力**，必须经 `ToolCallbackContributor` 端口注册，并登记技能包（至少一个 `AgentTask.skills` 挂点）；**禁止**给 LLM 直插域内 Service。

**REF-4 验证集**

```text
Required   ：检索单测 + 预算截断断言 + 无 KB 摘除断言
Regression ：verify-agent-skill-capability.ps1（技能包登记）、verify-websearch-e2e.sh（工具面不受扰）
门禁       ：bash scripts/ci/ci-gate.sh
```

---

# 7. REF-5 四项之外 A 档（独立交付，可插队）

> 调研依据：`research/helloai借鉴清单_四项之外_完整版.md` A 档（A1~A6）。

| 项 | 动作 | 验收 | 备注 |
|---|---|---|---|
| REF-5.1 | **失败回叫闭环**：`alternative == null` 分支（`ResilientDispatcher:256-261`）补 `sub_task_dispatch_no_alternative` timeline + 可读原因 | 无替代 Agent 时用户可见原因（不再是裸 500） | 小改动，可先做 |
| REF-5.2a | **常量单源化**：`DEP_CONTENT_MAX_CHARS` 现**两份 64000**（`McpToolServiceImpl:105`、`AgentRuntimeContextAssembler:99`）→ 收敛单源；顺带修 `McpToolServiceImpl:98` 指向**已删除类** `SubTaskExecutionService` 的陈旧注释 | 单源、无陈旧引用 | 零风险、先做 |
| REF-5.2b | **载荷旁路（双视图）**：`ToolResult` 的 `modelView`（摘要 + ref）/ `uiPayload`（全量） | 超长附件全量可回取 | **独立评估项**，见下 |
| REF-5.3 | **首次运行锁定**：锁死除 setup 外全部端点 | 初始化中实例不对外裸奔 | 触发条件见下订正 |
| REF-5.4 | **SSRF 出站校验**（含 DNS-rebinding pinning） | 外联 URL 必过校验 | 落点 `WebPageFetchServiceImpl` |
| REF-5.5 | **目录守卫 + 结构化拒绝码** | 拒绝原因可读 | **未来前置**（开放工作目录之前置，当前无该能力） |
| REF-5.6 | **备份/恢复**（= REF-2.3，此处仅索引） | — | 与 REF-2 合并 |

> **Status（2026-10-10，v10）**：A 档六项**已交付四项** —— `REF-5.2a` / `REF-5.1`（`LOG-20261010-009`）、
> `REF-5.3` / `REF-5.4`（`LOG-20261010-010`）；`REF-5.6` 已并入 `REF-2`。
>
> ① **常量单源化**（`5.2a`）：`DEP_CONTENT_MAX_CHARS` 收敛为 `UpstreamAttachmentRenderer.DEP_CONTENT_MAX_CHARS`
> （两处消费方引用同一常量）；`McpToolServiceImpl` 旧注释指向**已删除类** `SubTaskExecutionService` 的陈旧指认一并清除。
> ② **无替代分支可读化**（`5.1`）：落 `sub_task_dispatch_no_alternative` 时间线 + 抛 **409**（可读原因，不再是裸 500）；
> 前端登记 `eventMeta.ts`（`EVENT_META` + `PAYLOAD_KEY_LABEL`）与 `sequenceFlow.ts`（`LABEL`）—— **第三处
> `SubTaskDetail.vue` 无需改动**（经 `EVENT_META` 取标签；这两个事件是必须可见的调度决策节点，**不进** `COMPACT_HIDDEN_EVENTS`）。
> ③ **初始化期服务锁定**（`5.3`）：判据 = **用户数 == 0**（**不用** `system.setup_finished` —— 该值在
> `V1__init_all.sql` 被预置为 `'1'`，当判据**永不生效**）；落点 `SetupLockInterceptor`，除 `/api/setup/**` 与
> `/api/health/**` 外一律 `503` + `setup_required`，与前端 `!setupStatus.hasUsers` 同源。
> ⚠️ **v2 订正原文「现无任何拦截器」不准确**：实测已有 `RequestLog` / `Auth` / `AdminOnly` / `Sa` 四个拦截器，
> 只是**没有**初始化锁定这一条 —— 落地是**新增一个**并接入 `WebMvcConfig`，不是从零建拦截器体系。
> ④ **出站 SSRF 守卫**（`5.4`）：`WebPageFetchServiceImpl` 由 JDK `HttpClient` 改 **OkHttp + 自定义 `Dns`**；
> 两层防线 = **URL 层**（协议白名单默认只放 https + **IP 字面量直判**）+ **地址层**（解析即判并以同一结果建连 = pinning）；
> 跳转**逐跳**重跑守卫（旧 `Redirect.NORMAL` 的重定向式 SSRF 随之关闭）。OkHttp 由传递依赖转为**显式声明**
> （根 pom `okhttp.version`，按 `D-2026-10-09-6③-2`「传递依赖落地必须显式声明」）。
> **实测结论（本项的关键依据）**：OkHttp 对 **IP 字面量不走 `Dns`** —— `http://169.254.169.254/` 这类最高危形态
> 只在 URL 层拦得住，这是「两层必须同时存在」的直接证据；另实测 `0177.0.0.1` 在本 JDK 下解析为 **177.0.0.1（公网）**，
> 不构成绕过（守卫的不变量是「最终连的地址必须公网」）。
>
> 验证：`SetupLockInterceptorTest` 3 例 / `OutboundUrlGuardTest` 15 例 / `WebPageFetchServiceImplTest` 14 例全过
> （含「默认拒明文 http」「默认拒回环字面量」「十进制写法解析为 127.0.0.1 被拒」）；前端 type-check 通过；
> `ci-gate.sh` 全绿（累计用例见本轮 Log）。
> **`REF-5.2b` 已结项（2026-10-10，用户确认改形）**：结论与实测见 `doc/review/HelloAI REF-5.2b 与 REF-7 合并评估（2026-10-10）.md`。
> **不引入** `ToolResult` 双视图（该落点**在代码中不存在**）；实测把前提摆正 —— **数据没丢**（原文全量在 PG/MinIO），
> 丢的是**消费侧可见性**；且 `DEP_CONTENT_MAX_CHARS` 是**整块总预算**（多前置附件合计越限即截断），**现网已在触发**。
> **改形落地 = ① 截断标注行补 `id=<attachmentId>`（可寻址 ref）+ ② 复用既有 REST 取回通道**
> —— 实测团队成员读 `attachments/downloadById/{id}` 已 200，**故不新增 MCP 工具**（工具面保持 13 个，避免动外部 Agent 契约）。
> 连带（用户裁定）：**子任务视图读判据统一**到 task-team（时间线 / 对话流 / `getById` 三处，后者此前**无任何校验**）——
> 回流锚点 `R11`。仍待：`REF-5.5`（未来前置）。回流锚点见差距表 §7.1.4（`R8`/`R9`/`R10`/`R11`）。

> **v2 订正（逐条）**：
>
> - **REF-5.1**：v1 勘误成立（成功降级路径已于 2026-10-05 落 `sub_task_dispatch_fallback`）。**剩余缺口收敛为「无替代分支」**。补两点：① 该分支抛的是 `BizException`（默认 500）→ 用户看到的是 500 而非可读原因；② **连带前端三处登记**（`sub_task_dispatch_fallback` 至今前端 0 命中，会裸显示英文）。
> - **REF-5.2**：v1 **表格验收列与勘误自相矛盾**（仍写「第二个附件全文不再丢」/「唯一真丢数据处」）——现对齐为 5.2a + 5.2b 两项。**另一处勘误**：上游 Octop 的 offload **没有「内容哈希去重」**（`data_ref` 是固定哨兵字符串 `"artifact"`，非内容哈希）——「按内容哈希去重」是本项目自研增量，其成本收益必须单独论证，不得以「借鉴」名义默认带上。当前真丢数据点 = **超 64K 极端超长附件被兜底截断**。
> - **REF-5.3**：**触发条件错了**。`system.setup_finished` 在 `V1__init_all.sql:875` 被**预置为 `'1'`**——新实例一开始就是「已完成初始化」，用它做判据等于**永不生效**。上游 Octop 的判据是**用户数 == 0**；helloai 的 `SetupController.getStatus` 已返回 `hasUsers` / `userCount`，且前端 `Login.vue` 已在用 `!setupStatus.hasUsers`。**改用 `userCount == 0` ⇒ 503 `{setup_required:true}`**，与前端既有语义天然一致。现无任何拦截器（`WebMvcConfig:51` 只放行 `/api/setup/**`）。
> - **REF-5.4**：现状确认**无任何出站校验**，且 `WebPageFetchServiceImpl:80` 用 `HttpClient.Redirect.NORMAL` **跟随跳转且不校验跳转目标** ⇒ 重定向式 SSRF 与 DNS rebinding 均真实存在。**不能 1:1 移植上游**：上游的 pinning 依赖 `httpx` 私有属性（`self._pool._network_backend`），而 helloai 用的是 **JDK 内置 `java.net.http.HttpClient`——没有公开的 DNS pin 钩子**。
>
> ✅ **已裁定（用户，2026-10-09，`D-2026-10-09-6②`）**：**采用 OkHttp + 自定义 `Dns`** 做 pinning；**协议白名单默认只放 https**，本地开发放行 http 走**显式开关（默认关）**。
> - 「IP 直连 + 手写 Host 头」**已排除**：SNI 会变成 IP ⇒ https 证书主机名校验失败，绕过等于关校验，安全性反而更差。
> - OkHttp **已在类路径**（`pom.xml:184` 注释：MinIO SDK 的传递依赖），但落地时**必须在 `pom.xml` 显式声明**，不裸用传递依赖。
> - 顺带治 `WebPageFetchServiceImpl:80` 的 `Redirect.NORMAL`：换成 OkHttp 后以拦截器对**每次跳转目标**重跑守卫。

---

# 8. REF-7 外部 Agent 工作详情快照（**2026-10-09 新增**）

> **来源**：用户对平台定位的澄清（2026-10-09）——「后期如果需要了解具体的工作详情，让外部 AI agent 在**审批通过后**，再次提交一份**全任务工作详情的快照**，平台进行分析后插入执行时间线等关键审计信息中」。
> **性质**：**能力外延，正式立项**（`D-2026-10-09-6⑤`），差距锚点 **`G-020`**；可后置，触发条件 = 「对子任务内部执行深度的审计需求」成立。
> **与平台定位的关系**：这是「外卖员事后补交行程记录」——平台**不改变**「不干涉 agent 怎么做」的原则，也不要求 agent 实时上报；快照是**事后、经审批、可选**的补充。

## 8.1 要解决的空白

现状平台能审计到的是**订单层面**：认领 / 开工 / 心跳 / 交单 / 产物 / 阻塞 / 依赖读取（`agent_event` + `task_timeline` + 产物对账）。**子任务内部的执行过程是黑盒**——这是刻意的解耦，但带来了取证盲区：返工争议、质量复盘、责任界定都只能看结果，看不到过程。

## 8.2 三条设计约束（动手前必须先定）

| # | 约束 | 说明与倾向 |
|---|---|---|
| C1 | **与 `REF-6.2`「审计事件闭合 schema」的张力** | `REF-6.2` 的判据是「审计事件**不允许任何自由文本字段**」（一旦有，就无法证明凭据没从那里漏出去）。而「工作详情快照」**天然是自由文本 / 大对象** ⇒ **必须双面**：**原文落 MinIO**（已就绪），**时间线只放结构化索引 + `ref`**。这与 `REF-5.2b` 的「双视图」是**同一个模式** |
| C2 | **审批方是谁** | 待定：平台侧新增审批流？还是挂到既有 Reviewer 双轨？还是复用人工介入（HUMAN_REVIEW）通道？**倾向复用人工介入通道**——不新建第二套审批（治理红线：不建第二套状态机） |
| C3 | **落时间线的粒度** | 待定：一条汇总事件（最快）/ 按阶段拆多条 / 只登记上链标记不给事件。**倾向先做「一条汇总事件 + 快照 ref」**，逐阶段拆解后置 |

## 8.3 建议形态（待 C1~C3 拍板后细化）

```text
外部 Agent（事后、可选）
    └─► 提交工作详情快照（新 MCP 工具，走既有 13 工具同款鉴权 / 心跳前置守卫）
            └─► 快照原文落 MinIO（内容哈希 key，去重）
                    └─► 平台解析 → 结构化摘要
                            └─► 时间线插入一条审计事件（闭合 schema + snapshotRef）
```

**注意**：新增工具必须走既有 `McpMcpServer` + `assertAgentActive` / `assertToolEnabled` / `refreshDutyLease` 守卫（不新建平行通道），且**默认关闭**（`REF-6.13`：新能力默认关闭、通过注入启用），避免污染既有工具面与外部 agent 契约。

## 8.4 验证集（定性后细化）

```text
Required   ：快照提交 → MinIO 落对象 → 时间线出现审计事件且 payload 闭合（无自由文本）
Regression ：verify-mcp-auth.ps1（工具面鉴权不变）、verify-c3-events.ps1（事件成对性）
门禁       ：bash scripts/ci/ci-gate.sh
```

---

# 9. REF-6 判据登记（跨组判据，随对应组落地）

> 本节不是能力组，而是**跨组判据登记表**（调研依据：`research/helloai借鉴清单_四项之外_完整版.md` B 档 B1~B13），故编号排在 `REF-7` 之后；**不单独排期**。

> ⚠️ **v2 订正（载体）**：v1 写「入 `MEMORY.md` 可复用判据」——**该载体当前不存在**：全仓 `find -iname MEMORY.md` **零命中**（`2026-10-03` / `2026-10-04` 的历史 Log 曾以它为回填目标，`.workbuddy/memory/` 现仅存按日文件）。且 `MEMORY.md` 即便存在也**不在 `doc/` 治理体系内**（治理规则 §3.1 只覆盖 `doc/`），`plan/` 引用体系外载体 = 引用一个无人保证存在的对象。**改按载体分流**：
>
> ```text
> 能机器校验的  ⇒ 落 scripts/ 的 verify-*.ps1/.sh（CODE_STYLE §49：优先脚本化，别继续加 Markdown）
> 属口径/决策的 ⇒ 落《差距表》§0「当前生效的取舍决策」
> 属设计边界的 ⇒ 落 design/*.md 的 Scope 段
> 不得新造第 8 份根文档
> ```

| 项 | 判据 | 落地 |
|---|---|---|
| REF-6.1 | 幂等键必须 **status-scoped**（「先报 BLOCKED 后报 SUCCESS」不被静默吞） | 核对 `agentOutboxService` 去重键 |
| REF-6.2 | 审计闭合 schema（无自由文本字段）；append-only 必须 **keyset 分页** | 见下订正 |
| REF-6.3 | 能力摘除式治理（无 KB 即摘工具 / 不可关闭清单 / 条件可用） | ✅ **已具备**（随 REF-1.3，2026-10-09）：语义位 + 生效面齐备，当前消费者 = `web_search`；`search_knowledge` 侧随 `REF-4.1` |
| REF-6.4 | 水位四判据（一 marker 不得两语义 / 失败不推进 / 检测范围=推送范围 / 「扫描 0 B」≠「无 I/O」） | MinIO 附件同步 |
| REF-6.5 | 身份调用上下文（`_sessionId` 进程级 = 多实例前置） | 登记，多实例前解决 |
| REF-6.6 | 探活两类区分（对象在连接死 vs 对象在别 JVM） | 登记 |
| REF-6.7 | 错误语义 domain 层，HTTP 层只映射 | 生成式校验候选 |
| REF-6.8 | key parity 守卫（事件码服务端单一来源 + 前后端 key 匹配测试） | 治 `eventMeta` 三处漂移（已被 `sub_task_dispatch_fallback` 实例证伪） |
| REF-6.9 | 探针纪律（「配好了」必须能被机器验证 + 保证回收） | 随 REF-3.4 |
| REF-6.10 | 状态面最小化（有没有「事后查」的查询方） | 登记 |
| REF-6.11 | denylist 默认全开 + 不可关闭清单 | ✅ **已具备**（随 REF-1.3b，2026-10-09）：MCP `tools/list` 保持「denylist 默认全开」（摘除不作用于该暴露面）；`CRITICAL_TOOLS = {pullTasks, submitResult, heartbeat}` 生效于授权面，不抵销 `API_KEY_LLM` 的可注入面过滤 |
| REF-6.12 | sentinel 区分「未传」/「传 null」 | PATCH 语义 |
| REF-6.13 | 新能力默认关闭（通过注入启用） | Sandbox / Skill 上线时 |

> **REF-6.2 订正**：`task_timeline` 的读侧**不是 offset 分页——是完全没有分页**（`TaskTimelineServiceImpl:53-61` 全量 `list()`，REST 端点直返全量 List）。所以动作不是「把 offset 换成 keyset」，而是「**补上 keyset 分页**」。另：上游的闭合 schema 测试是**单样本式**（只断言一个样本对象的 key 集合，不具声明式约束力）——移植时应做得更严（如 `FAIL_ON_UNKNOWN_PROPERTIES` + 显式字段白名单），别只抄形状。

---

# 10. 明确不做（C 档，与治理红线一致）

- Octop 单进程 / 双后端 / 进程内调度（C1~C3）——路线分歧；
- AgentTeams CRD / Helm / leader-election / kine / Matrix 房间（C4）——K8s 原生平台实现细节；
- CDP 逐帧直播 / Python IM 网关 / 桌面移动语音交付面（C5~C7）——定位无关；
- **外部 agent 对接**（C8）——用户裁定可借鉴内容有限。**v2 补独立证据**：AgentTeams 全仓 `a2a` **零命中**、`interface.go:32-40` 为**封闭 runtime 枚举**（新增 runtime 必须改码）；helloai 的 MCP+SSE+心跳接单是当前最优解。
- **Fork 触发入口 / 原 Run 冻结 / 驱动新 Run 执行**（C9，2026-10-09 新增）——**WONTFIX**（`D-2026-10-09-5`）。理由：①「驱动执行」自 2026-10-04 拍板起即为后置项（与 Resume 对称的「先做成、再做好」）；② 实际用法（分叉重跑 / 路径对比）已被 **Return**（REWORK→驳回→改派→重开工，闭环完整）与 **Replay 工作台**覆盖；③ 驱动执行须改 ADR-001 的 Run 标识模型 + execution command 载荷（协作规约 §30/§31），成本收益不匹配。**已建部分**：`AgentEventForkService` 快照复制（6 单测全绿、零生产调用方）**保留**为未接线的内部能力；是否删除另行裁定。回流登记：《差距表》§7.1.2 `R1`/`R2`。

---

# 11. 每组的统一收口（验证 + 回填）

任何 REF 组完成，**必须同批**做以下动作（《协作规约》§28/§32 + 《文档体系分类与治理规则》§4）：

| 动作 | 落点 |
|---|---|
| 测试 + 门禁 | 单测 → 本文件该组「验证集」→ `bash scripts/ci/ci-gate.sh` |
| 仅功能实现 | 《差距表》对应 `G-` 行就地更新（唯一进度事实源） |
| 当前真实架构变化 | 《项目基线文档》对应节 |
| 目标边界变化 | 《目标架构》对应节 + §0 状态行（**只改状态必同步改判定依据**） |
| 实施顺序变化 | 《重构实施计划》 |
| 重大架构决策（**即时**） | `log/2026-10.md` 的 `### 决策` 段 + 《差距表》 |
| 重大架构决策（**定期**） | `log/HelloAI 架构变更记录.md`（按周期汇总回填，非逐条） |
| 长期设计锚点 | `design/adr/ADR-00x` |
| 引用完整性 | 移动/改名后同批修正活文档引用（治理规则 §7） |
| 完成后 | 本组条目迁 `doc/archive/implemented/` 并标 `Done` |

---

# 12. 裁定记录（原「待拍板」段，2026-10-09 已全部裁定）

> **本计划当前无待拍板项**（除 REF-7 §8.2 的 C1~C3 三条设计约束，在 REF-7 触发后、动手前再定）。
> 原 5 条待拍板已全部裁定，记入《差距表》`D-2026-10-09-5`（Fork）与 **`D-2026-10-09-6`**（下述 5 条）。

| # | 议题 | **裁定** | 依据 / 取舍（保留供追溯） |
|---|---|---|---|
| ③-1 | **REF-1.3 语义位落点** | **采纳建议**：放 `ToolRegistry.resolve(...)` 的上下文参数（新增 `ToolContext`，命名对齐 `SandboxContext`），**不进 `ToolDefinition` record** | 两者是不同层次事实：「工具是什么」（注册事实，单一事实源 = `@Tool` 注解）vs「本轮能不能用/怎么说」（运行时事实）；record 加 `Predicate`/`Function` 会破坏值语义与可序列化。`resolve` 仅 **2 个调用点**（`RuntimeTurnExecutor:93`、`AgentRuntimeContextAssembler:164`）且都是**每轮装配时**调用 ⇒「每轮重写 description」时序天然成立。实施时只在 Tool 侧加参、Skill 侧签名不动。**⚠️ 本行「时序天然成立」的技术前提已于 2026-10-09 证伪**（两处调用结果都不进模型可见工具，实际范围含同批改造消费点）；订正过程见 `LOG-20261009-012`，`REF-1.3` 节的 `Status` 行记录了对后续同类改造的预估修正 |
| ③-2 | **REF-5.4 出站客户端** | **采纳建议**：**OkHttp + 自定义 `Dns`** 做 pinning；协议白名单**默认只放 https**，本地开发放行 http 走**显式开关（默认关）** | ① **OkHttp 已在类路径**（`pom.xml:184` 注释：MinIO SDK 的传递依赖）——落地必须在 `pom.xml` **显式声明**；② 「IP 直连 + Host 头」**已排除**（SNI 变 IP ⇒ 证书主机名校验失败，绕过等于关校验）；③ JDK 内置 `HttpClient` **无公开 DNS 钩子** |
| ③-3 | **REF-3 沙箱** | **采纳建议（修正原优先级）**：**整组降级为「条件触发」，不排期**；契约保持现状不再扩展；`G-005` 改「`Planned`（条件触发）」并登记三个触发条件 | 当前**无可隔离的执行对象**（外部 agent 在它自己终端；内部 agent 工具面全是平台 API、无 shell / 文件写；平台全库无脚本引擎 / 表达式求值）。触发条件：① 平台增加碰宿主的工具 / ② 技能包要被执行 / ③ 平台自持浏览器。**此项修正了原「第 3 优先级」排序** |
| ③-4 | **REF-1.2 技能目录的下游一致性** | **采纳建议**：**服务端下发**（复用 REF-1.2c 的技能目录 API）；parity 守卫只留给**不适合下发的词表**（`AGENT_SKILL_OPTIONS` ↔ 后端 `KEYWORD_SKILLS`/`SYNONYMS`）与**事件码**（`REF-6.8`） | 前端常量有 **3 类语义**（`TaskFormDialog:210` 下拉拼接 / `PlanReviewDialog:489` 平台技能判定 / `skillLabelOf` 中文标签，被 `SubTaskDetail:209` 等 3 处调用）。保留常量 ⇒ 新增技能「后端生效、前端看不见」，直接抵消 REF-1.2 收益 |
| ⑤ | **工作详情快照** | **采纳建议**：登记为 **`REF-7`**（§8），差距锚点 `G-020`；可后置，触发条件 = 子任务内部执行深度的审计需求成立 | 与 `REF-6.2`「审计闭合 schema」存在张力 ⇒ 必须双面（原文落 MinIO + 时间线放结构化索引 + `ref`），与 `REF-5.2b` 双视图同模式；审批方倾向复用人工介入通道（不建第二套审批）；新增工具须走既有 `McpMcpServer` 守卫且**默认关闭**（`REF-6.13`） |
