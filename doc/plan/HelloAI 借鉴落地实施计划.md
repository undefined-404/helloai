# HelloAI 借鉴落地实施计划

> **状态：Active**
> **编号体系：`REF-x.y`**（借鉴落地专项任务，`x` = 能力组，`y` = 组内序号）**+（2026-10-13 起）`M-x`**（供应商 / 模型注册信息完善，见 §11）。**已按差距表 §7.1「孤儿项回流规则」登记进《HelloAI 实现差距表》§0 编号口径表**，与 `G-xxx` / `RMx` / `B1~B5` / `BASE-x.y` 均不撞号。
> **依据**：`doc/research/` 5 份调研（2026-10-09）+ **2026-10-09 对 helloai 源码与 `~/Downloads/{Octop-main,AgentTeams-main}` 参考源码的逐条复核（v2 修订）**。
> **用户裁定**：`D-2026-10-09-4`（见《差距表》§0「当前生效的取舍决策」）——四项能力优先级、备份/恢复与 RAG 入目标架构、沙箱生产形态、语义位与不可关闭清单同批。
> **性质**：本计划只收**可执行结论**（借鉴落点 → 动作 → 验收 → 验证集 → 回填），不含调研过程（在 `research/`）与稳定设计（在 `design/`）。
> **完成迁移**：执行完成后迁入 `doc/archive/implemented/` 并标 `Done`。
> **最后更新：2026-10-14**（版本条目仍为 **`v18`**，2026-10-12 —— 后续变更遵《治理规则》§3.2「版本变更史冻结新增」，**不新增版本条目**）
> **v18 = 新增 `REF-8`「内部 LLM 能力面」**（`D-2026-10-12-1`，差距锚点 **`G-021`**）：
> 用户确认「内部 agent 内核收敛思路」按审阅建议调整 ⇒ 立 `REF-8`，收纳 `D1` 工具面解封 / `D2` 手脚·工作区（含 `systemPrompt` 非空 + `maxIterations` 分档）/ `D3` 技能全量目录 + 惰性加载 / `D4` 拦截链（Spring AI Advisors）/ `E1` 循环覆盖面收敛；
> **内核轴 = 「可见面收敛」**（唯一输出契约 = 模型的可见集，三层）；**会话单源化降为支撑项**（提升已有 `agent_event`，**不新建表**）；`LOG-20261011-007` 的 A 能力闸 + B 收敛熔断**挂 `REF-8.4` 拦截链**之下。见 §10。
> 同批：原 §10~§12 顺延为 **§11~§13**（`§13 裁定记录` 的内部引用已同步）。
> **2026-10-12 后续计划侧变更（不新增版本条目 —— 遵治理规则 §3.2「版本变更史冻结新增」）**：`REF-8.1~8.3` **开工**；`REF-8.4` 承载 `LOG-20261011-007` **A+B**（阈值 `N=3`，`D-2026-10-12-3`）；新增 **§10.6 覆盖面清单（16 项，待勾选）**；`REF-7` 三约束 **C1~C3 定稿**（§8.2，`D-2026-10-12-4`）。**裁定记录本体见 `doc/log/2026-10.md` `LOG-20261012-002`**。
> **2026-10-13 后续计划侧变更（不新增版本条目 —— 遵治理规则 §3.2「版本变更史冻结新增」）**：按《治理规则》§3.6「可执行结论若进入实施，另立 `plan/` 或 `design/` 文档」，把 `research/magpie_借鉴分析.md` 的 `M-1`~`M-9` 落成**新增 §11**（`M-x` 号段，已入《差距表》§0 编号表）；原 §11~§13（明确不做 / 统一收口 / 裁定记录）**顺延为 §12~§14**，内部引用已同步。同批修好 §11.1 所列 **D-a~D-f 六个现网缺陷**（预置陈旧值及其 5 个同源扩散点，含被误当「规范真值」的 `application.yml` 自身）；`M-2③` 守卫随后扩为**四方比对 + 覆盖完整性 + 双向反证**（`d20a0160`）。**同日另起 `V108`**：minimax 因 `M2.5`/`M2.7` 全系列即将下线，目录默认位改 `MiniMax-M3`（含 4 处载体同步，见 §11.1 D-a 与 `research` §九「第四轮」）。
> **v17 = A1「载荷旁路」配套项（Octop `middleware/workspace_image.py` 图片路径引用）补登记为「条件触发」**（2026-10-12，`LOG-20261012-001`）：
> 该条此前**仅在 `research/` 正文出现，无 `REF-` 落点、未记 WONTFIX**（孤儿）⇒ 按 §1「孤儿项回流」补登记差距表 §7.1.4 `R14`，**不排期**（实测平台无图片 / 多模态输入通道，现状不适用）。
> **v16 = REF-6.8 写点替换交付**（2026-10-11，`LOG-20261011-008`）：
> 31 文件 / 119 处展示码写点枚举化（core 25 + job 6）；守卫改「写点无裸字面量 + 枚举引用存在 + 库内 ⊆ 枚举」；
> 订正原批次两处漏（未加 import / 漏 `helloai-job` 整模块）。REF-6.8 至此**两半均收口**。
> **v15 = REF-4.3 交付 ⇒ REF-4（RAG）收官**（2026-10-11，`LOG-20261011-005`）：
> 引用溯源 marker = 内联 `[kb:source#idx]` token，前端 `MarkdownView` 渲染可点开来源卡片 + `/api/kb/chunk` 端点；REF-4 `0a/0b/1/2/3 + 前置` 全部完成。
> **v14 = REF-4.1 + REF-4.2 + 前置③ 交付**（2026-10-11，`LOG-20261011-004`）：
> `search_knowledge` 无KB即摘 + char_budget + 登记技能；`kb_chunk`（V107）+ 自建 JdbcTemplate vector 检索 + `/api/kb/*`；
> 前置③ 判别性召回通过（真实嵌入 + 余弦 top1 三题全对），`verify-rag-e2e.sh` PASS=4；
> `search_knowledge` 并入内部 ToolRegistry、MCP 13 工具面不变（S1 确定）。REF-4.3（溯源 marker + 前端）后置。
> **v13 = REF-4 落地前置①b（通义千问 LLM 有效）+ ② REF-4.0a（pgvector 镜像 + 扩展）交付**（2026-10-11）：
> ①b `qwen-plus` chat 探针 `200` 通过；REF-4.0a 镜像换 `pgvector/pgvector:0.8.0-pg16`（四处同步）+ `V106` `CREATE EXTENSION vector`，
> 本地真库验证 `vector|0.8.0` / Flyway `V106` success / `:6565` UP。见 §6 `Status` / `LOG-20261011-003`。
> **v12 = REF-4 落地前置① embeddings 探针通过 + 用户新增前置①b**（2026-10-11）：
> `text-embedding-v4` + 1024 维探针 `200`/`1024` 通过（失败分支以误用 DeepSeek key→401 实证）；
> valid key = 平台 LLM 供应商派通义千问凭据、REF-4 复用不新增、**key 值不入档**；
> 新增前置①b「RAG 启用前先验证通义千问 LLM 模型有效性」，见 §6 `Status` / `LOG-20261011-002`。
> 此前 v1~v11 完整变更史：v1 勘误已并入正文；v2 新增 **11 处动作订正**（会导致回归失败或做不出来的部分）、**每组的验证集与文档回填**、**许可证口径**、**REF-6 载体修正**；v3 **Fork 相关条目全部取消**（`D-2026-10-09-5`，REF-2 收敛为「备份 / 恢复」）；**v4 两项重大调整**（`D-2026-10-09-6`）：① **REF-3 沙箱整组降级为「条件触发」**——当前**没有可隔离的执行对象**，不排期、只留预案（修正原「第 3 优先级」排序）；② **新增 `REF-7`「外部 Agent 工作详情快照」**（差距锚点 `G-020`）。原 §11 待拍板 5 条**已全部裁定**并移入 §12 裁定记录，**本计划当前无待拍板项**；**v5 = REF-1.1 / 1.2 / 1.3 / 1.3b 已完成**（2026-10-09），各组结果与未达成分项见各节 `Status` 行；**v6 = REF-1.4 / 1.5 / 1.6 次序与 REF-1.6 设计定稿**（`D-2026-10-10-1`，见该节）——次序定为 **`REF-1.6 → REF-1.5 → REF-1.4`**；同时落定十条：存储形态走受控存储（不触发 G-005 条件①）、技能正文存 PG、**不建新 ADR**、外部技能目录 **WONTFIX**、命名统一「**技能包**」+ 清单文件 `skill-package-manifest.md`、安装 / 卸载落 `skill_package_audit`、**版本策略**（多版本共存 / 高版本需确认 / 降版走独立接口）、**ACTIVE 选版 + 内容键 `{name}@{version}`**、**内置保护（同名一律拒绝安装）**、**存原始正文**；**v7 = REF-2 实施方案定稿**（`D-2026-10-10-2`，见 §4）——落定六项：执行形态走应用内 `ProcessBuilder` + 前置检查 + Dockerfile 补装 PG 客户端（不做 `docker exec`）、**`ProcessBuilder` 判为不触发 `G-005` 条件①**（故 REF-3 排期不动）、备份落**独立 bucket `helloai-backups`**（对账巡检枚举整桶，共用会被当孤儿删）、定时 + 手动双触发且长操作走异步 + 轮询、仅自动备份按份数淘汰（默认 7）且手动永不淘汰、新增 `platform_backup` 记录表（V105）；**v8 = `REF-2.3` / `REF-2.3b` 落地并验证**（2026-10-10，见 §4 `Status`）——编排层 + 恢复三门 + `platform_backup`；单测 22 例、演练脚本 `verify-backup-restore.ps1` 29 项全过；**v9 = `REF-2.4` + `REF-5.2a` / `REF-5.1` 落地**（2026-10-10）——停机恢复流程手册 + 部署侧上线核对清单（§4）；常量单源化 + 「无替代 Agent」分支可读化（409 + 时间线）与前端字典补齐（§7 `Status`）；**v10 = `REF-5.3` / `REF-5.4` 落地**（2026-10-10，见 §7 `Status`）——初始化期服务锁定（判据取用户数）+ 出站 SSRF 守卫（OkHttp 自定义 `Dns`，URL 层 + 地址层两层）；**v11 = `REF-5.2b` 结项 + 交付文档一致性守卫**（2026-10-11，见 §7 `Status` / `LOG-20261010-011`·`012`）——截断标注补可寻址 `id` 并复用既有取回通道（不新增工具）+ 子任务视图读判据统一（`R11`）；新增 `scripts/shell/verify-executor-doc-parity.sh` 并接入门禁 3。**A 档至此交付五项**，仅余 `REF-5.5`（未来前置）。

---

# 1. 编号与治理前置

| 项 | 口径 |
|---|---|
| `REF-x.y` 含义 | 借鉴落地专项任务；`x` 取 1~8（技能 / 备份·恢复 / 沙箱〔条件触发〕 / RAG / A 档 / 判据 / 工作详情快照 / **内部 LLM 能力面**） |
| `M-x` 含义 | **供应商 / 模型注册信息完善**（magpie 借鉴，2026-10-13 建号，见 **§11**）；`x` 取 1~9。来源 `research/magpie_借鉴分析.md`。与 `REF-x.y` **并列**——`REF-` 号段 `x` 已占满 1~8，不扩号段 |
| 与 `G-0xx` 的关系 | 每个 REF 组必须映射到差距锚点：`REF-1 → G-004`、`REF-2 → G-018`（**原 `REF-2.1/2.2 → G-001·G-006` 已随 Fork WONTFIX 取消映射**）、`REF-3 → G-005`（**条件触发**）、`REF-4 → G-019`、`REF-5.1 → G-015`、`REF-5.3/5.4 → 新增 G 项或就地登记`、`REF-7 → G-020`、**`REF-8 → G-021`**（2026-10-12 新增） |
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
REF-8 内部 LLM 能力面：**当前最高价值**（直接产出「LLM 变 agent」的能力，2026-10-12 新增）
      次序 8.1 工具面 ──► 8.2 手脚+上下文 ──► 8.3 技能惰性加载 ──► 8.5 覆盖面
      8.4 拦截链可并行铺（是 LOG-20261011-007 A+B 的唯一挂点）
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

> **Status（2026-10-11，v13）**： `REF-4.0b` **已定稿** —— `design/adr/ADR-002-embedding-provider.md`（`D-2026-10-11-1`）：
> 嵌入模型 = DashScope `text-embedding-v4` / **1024 维（单向门，上线即冻结）**；密钥**共用**既有 dashscope 条目；
> 数据边界 = 接受正文发送至阿里云（不引入境外供应商）；向量存储沿用 `pgvector`（不引入 ES）。
> **落地前置**：
> - **① embeddings 探针 ✅ 通过**（2026-10-11，`LOG-20261011-002`）：`text-embedding-v4` + `dimensions=1024` 返回 `200` / `1024` 维，
>   现有套餐覆盖向量模型。首把误用 **DeepSeek** key → `401 invalid_api_key`（失败显式报分支实证），换**通义千问** key → `200`。
>   **valid key = 平台配置「LLM 供应商」中已启用且验证有效的通义千问凭据**；REF-4 复用、不新增（对齐 ADR-002 §4）。
>   **key 值不入档**。
> - **①b（用户新增）✅ 已验证**（2026-10-11，`LOG-20261011-003`）：`qwen-plus` 通义千问 LLM chat 探针 `200` / 有内容，RAG 启用前置满足。
> - **② `REF-4.0a` ✅ 已交付**（2026-10-11，`LOG-20261011-003`）：PG 镜像换 `pgvector/pgvector:0.8.0-pg16`（docker-compose.yml / docker-compose.server.yml / deploy/middleware/docker-compose.yml / ItContainers.java 四处同步）+ 迁移 `V106__create_vector_extension.sql`（`CREATE EXTENSION IF NOT EXISTS vector`）。
>   本地真库验证：`vector|0.8.0` 扩展入库、Flyway `V106` success、应用 `:6565` UP。镜像内置 PG `16.10`（Debian），仍 16 大版本 ⇒ REF-2 跨引擎恢复门不受影响；16.4→16.10 小版本原地升级，数据卷保留无需迁移。CODE_STYLE §2792 基建行 / 差距表 G-019 前置同步。
> - **③ 前置③（召回质量验证）✅ 已通过**（2026-10-11，`LOG-20261011-004`）：真实 dashscope 嵌入 + 余弦检索，对三主题小样本判别性 top1 全对（部署→`deploy-manual` / 消息队列→`mq-arch` / 向量库→`manual-rag`），`verify-rag-e2e.sh` PASS=4。
> - **REF-4.1 ✅ 已交付**（2026-10-11）：`search_knowledge` 经 `ToolCallbackContributor` 注册且「无 KB 即摘除」（`toolAvailability()`=`hasKnowledge()`，REF-6.13 默认关）+ `char_budget` 注入预算（复用 `TextTruncator` 行边界）+ 登记 `eng-web-research` requiredTools（§35.1）。
> - **REF-4.2 ✅ 已交付**（2026-10-11，`LOG-20261011-004`）：`kb_chunk` 表（`V107`，`vector(1024)` + HNSW-cosine）+ **自建 `JdbcTemplate`+`vector` SQL** 存储/检索（PG 单后端，不引侧库）+ 管理端 `/api/kb/*`；单测 + core 全量单测通过；`search_knowledge` 并入内部 ToolRegistry、**不暴露 MCP 13 工具面**（S1 断言确定）。
> - **REF-4.3 ✅ 已交付**（2026-10-11，`LOG-20261011-005`）：引用溯源 marker = 内联 `[kb:{source}#{idx}]` token（模型在报告/核验里输出）→ 前端 `MarkdownView` 渲染可点开来源卡片（`kbApi.chunk` 懒取分片）；后端补 `GET /api/kb/chunk/{source}/{idx}`（`kb:view`）。前端 type-check 通过。
> - **REF-4 ✅ 收官**（2026-10-11）：`0a/0b/1/2/3 + 前置①②①b③` 全部交付，见 `LOG-20261011-001`~`005`。<br>**REF-4 验证集**：`verify-rag-e2e.sh`（含 S4 溯源取分片）+ 既有 `verify-agent-skill-capability` / `verify-tool-matrix`（MCP 面回归）；门禁 `ci-gate.sh`。

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

> **Status（2026-10-11，v11）**：A 档六项**已交付五项** —— `REF-5.2a` / `REF-5.1`（`LOG-20261010-009`）、
> `REF-5.3` / `REF-5.4`（`LOG-20261010-010`）；`REF-5.6` 已并入 `REF-2`。
>
> ① **常量单源化**（`5.2a`）：`DEP_CONTENT_MAX_CHARS` 收敛为 `UpstreamAttachmentRenderer.DEP_CONTENT_MAX_CHARS`
> （两处消费方引用同一常量）；`McpToolServiceImpl` 旧注释指向**已删除类** `SubTaskExecutionService` 的陈旧指认一并清除。
> ② **无替代分支可读化**（`5.1`）：落 `sub_task_dispatch_no_alternative` 时间线 + 抛 **409**（可读原因，不再是裸 500）；
> 前端登记 `eventMeta.ts`（`EVENT_META` + `PAYLOAD_KEY_LABEL`）与 `sequenceFlow.ts`（`LABEL`）—— **第三处
> `SubTaskDetail.vue` 无需改动**（经 `EVENT_META` 取标签；这两个事件是必须可见的调度决策节点，**不进** `COMPACT_HIDDEN_EVENTS`）。
> ⚠️ **后续更新（2026-10-11）**：上句所提 `EVENT_META` / `LABEL` 两份前端字典**已随 REF-6.8 服务端单一来源收口删除**
> （`LOG-20261011-006`），改经 `GET /api/events/catalog` 拉取；本行其余结论（含「不进 `COMPACT_HIDDEN_EVENTS`」）不受影响。
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
> 回流锚点 `R11`。仍待：`REF-5.5`（未来前置）。回流锚点见差距表 §7.1.4（`R8`/`R9`/`R10`/`R11`/`R14`）。
>
> **补登记：A1 的「配套第二条」→ `R14`（2026-10-12）**。A1 主条目（`REF-5.2a`/`5.2b`）治的是 **tool result 文本**过大，
> 其**配套第二条**治的是**图片 base64** 过大（Octop `infra/agents/middleware/workspace_image.py`：图片在 checkpoint / history
> **只存 `workspace://` 路径引用**，仅 model request 时展开为 `data:` URI ⇒ base64 永不落 state / 不进历史 payload）。
> 该条此前只在 `research/` 正文以「配套」一句话带过，**既无 `REF-` 落点也未记 WONTFIX** ⇒ 属**孤儿项**（违反 §1「孤儿项回流」）。
> 经复核：**对 helloai 现状不适用**（业务侧 `base64` / `image_url` / `vision` 零命中，附件注入为纯文本按行截断，平台无图片输入通道）
> ⇒ 按「**条件触发**」补登记为差距表 §7.1.4 `R14`，**不排期**（触发 = 平台新增图片 / 二进制多模态输入通道）。

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

## 8.2 三条设计约束（**2026-10-12 已定稿**，全部按原倾向）

| # | 约束 | 说明与倾向 |
|---|---|---|
| C1 | **与 `REF-6.2`「审计事件闭合 schema」的张力** | `REF-6.2` 的判据是「审计事件**不允许任何自由文本字段**」（一旦有，就无法证明凭据没从那里漏出去）。而「工作详情快照」**天然是自由文本 / 大对象** ⇒ **必须双面**：**原文落 MinIO**（已就绪），**时间线只放结构化索引 + `ref`**。这与 `REF-5.2b` 的「双视图」是**同一个模式** |
| C2 | **审批方是谁** | 待定：平台侧新增审批流？还是挂到既有 Reviewer 双轨？还是复用人工介入（HUMAN_REVIEW）通道？**倾向复用人工介入通道**——不新建第二套审批（治理红线：不建第二套状态机） |
| C3 | **落时间线的粒度** | 待定：一条汇总事件（最快）/ 按阶段拆多条 / 只登记上链标记不给事件。**倾向先做「一条汇总事件 + 快照 ref」**，逐阶段拆解后置 |

> **2026-10-12 用户裁定（`D-2026-10-12-4`）**：C1 / C2 / C3 **全部按原倾向定稿**（C1 双面 / C2 复用人工介入通道 / C3 一条汇总事件 + `snapshotRef`）；但 **`REF-7` 本身继续挂起**（触发条件 = 子任务内部执行深度的审计需求成立，当前未触发）——三条约束**先定不先做**，避免触发时再补拍板。

## 8.3 建议形态（C1~C3 已于 2026-10-12 定稿）

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

> **Status（2026-10-11）· `REF-6.8` ✅ 已收口**（`LOG-20261011-006`）：
> **① 单一来源 enum 已交付** —— `TimelineEventCode` 枚举（`common.constant`，**141 个用户可见展示码**，
> code+label+desc；与粗粒度 `AgentEventType`（agent_event，16 条）区分、互不取代）。
> **② 目录端点已交付** —— `GET /api/events/catalog`（权限复用 `event:view`，已绑 ADMIN/NORMAL_USER/GUEST）；
> `EventCatalogService` 遍历枚举全量下发。
> **③ 前端字典化已交付** —— 删 `EVENT_META`（134）/ `sequenceFlow.LABEL`（137）硬编码，改 `stores/eventCatalog.ts`
> 懒加载拉目录（`eventMeta.eventLabel` / `sequenceFlow.shortLabel` / 两视图消费），未命中回退原始码（fail-open）。
> **④ 守卫重构已交付** —— `verify-event-key-parity.sh` 改为断言「显示通道写点码 ∪ 库内真值 ⊆
> `TimelineEventCode` + 前端无 EVENT_META/LABEL 残留」（静态 PASS=4 / `--db` PASS=6）。写点 82/库内 53 均 ⊆ 枚举（0 缺口）。
> **写点替换 ✅ 已交付**（2026-10-11，`LOG-20261011-008`）：31 个生产文件、**119 处**展示码写点由
> 裸字面量改为 `TimelineEventCode.<CONST>.code()`（core 25/113 + job 6/6；行为保真，`code()` 与原字面量逐字相等）。
> **订正原批次两处漏**：① 25 个文件**未加 import**（照原状编译必失败）；② **漏了 `helloai-job` 整模块**（残余 6 处）。
> 守卫 `verify-event-key-parity.sh` 同步重构为「①写点无裸字面量 + ②写点枚举引用常量存在 + ③库内 ⊆ 枚举」——
> 原「写点字面量 ⊆ 枚举」在替换后静态提取由 82 码 → 0，会**静默空转**。验证：compile BUILD SUCCESS + 守卫 PASS=5 + 反证 EXIT=1。
> **REF-6.8 至此两半均收口**（服务端单一来源 + 写点枚举化）。
> **先例裁定**：早前 `D-2026-10-09-6③-4` 判「事件码 guard-only 不上发」已被本批**取代**（服务端下发），见 §14 / 差距表 §0。

---

# 10. REF-8 内部 LLM 能力面（2026-10-12 新增）

> **立项**：`D-2026-10-12-1`（用户确认内核收敛审阅建议）。**差距锚点 `G-021`**（此前该缺口在差距表**零登记**，属孤儿项）。
> **依据**：`review/HelloAI 内部 agent 内核收敛思路审阅与调整建议（2026-10-12）.md`（对 `research/HelloAI 内部 agent 内核收敛与借鉴思路（2026-10-12）.md` 的审阅结论）+ `review/HelloAI 内部 LLM 执行链与开源 agent 架构比对（2026-10-12）.md` §7。
> **性质**：本组治的是「**内部 LLM 不是 agent**」——平台编排层（`planner > review > task > agent`）已成熟，但内部 LLM 是「**循环骨架 + 空工具面 + 无手脚 + 只覆盖 1/16 的 LLM 面**」（实测：`AgentMcpServerServiceImpl:267-275` 对 `API_KEY_LLM` 过滤全部 13 个 MCP 工具；`agentLoop.run()` 生产调用点仅 `RuntimeTurnExecutor:130` 一处）。

## 10.1 内核轴 = 「可见面收敛」

内核唯一输出契约 = **模型的可见集**（DSH 的 `ToolRuntime.view(scope)` 与技能目录注入，本质是同一件事的两个实例）：

```text
L1 可见工具集    由 ToolRegistry.resolve 单口径产出（源不得被上游剥空）      ← REF-8.1
L2 可见技能目录  全量目录（name + 封顶 description），正文惰性加载           ← REF-8.3
L3 可见上下文    system prompt（非空）+ 工作区 / 附件读手脚                 ← REF-8.2
```

**为什么用「可见面」当轴**：三个缺口（工具 / 技能 / 上下文）从「三个独立补丁」变成「**同一契约的三个截面**」；并接住已证缺陷模式「**规则存在 ≠ 全路径强制**」——可见面必须是**唯一判据**，`getEnabledToolsForAccess` 与 `ToolRegistry.resolve` 两处口径必须一致。

## 10.2 动作明细

| 项 | 动作 | 落点（不动 `ChatModelToolLoop` 本体） | 验收 |
|---|---|---|---|
| **REF-8.1** | **工具面解封**：给 `API_KEY_LLM` 一条**不依赖 MCP 会话**的工具通道（读原附件 / 文件读写 / 正式交付动作） | **✅ M1 已交付（2026-10-12）**：复用 `ToolRegistry` 语义位 + `ToolCallbackContributor`（端口**已存在**）；新增 `InternalLlmExecutorTools`（进程内 @Tool，**不并入**对外 provider——`D-2026-10-12-5` B）；`AgentMcpServerServiceImpl` 对 `API_KEY_LLM` 返回内部授权集而非空 | 内部 LLM 的工具列表**非空**；「读原附件」「交付登记」工具可调通。单测 `InternalLlmExecutorToolsTest` 6/6 + `AgentMcpServerServiceImplTest` 7/7；全量 1976 用例绿、架构守卫 EXIT=0。**补充（`D-2026-10-12-5` A+B，2026-10-12）**：内部工具入口加「执行链绑定」强制闸门（`ToolInvocationContext`）+ 移出对外 MCP `tools/list`（`InternalToolCallbacks` 承载）⇒ 「可调通」自此**真正成立**（此前单测直连、未走模型循环）。见 `LOG-20261012-006` |
| **REF-8.1.1** | **内部「做不到」通道（blocked）**：内部 LLM 7 个 `internal_*` 工具**无自报阻塞能力**（实测 `tool_resolved`）⇒ 「没能力」只能写进正文 → 物化交付物 → 送审 → 返工 → 死信。新增自报阻塞工具（结构化 `{code, message}`），**带连续 N 轮门槛**防一遇难即甩锅 | **✅ 已实施（2026-10-12）**：新增 `internal_report_blocked`（`InternalLlmExecutorTools`，复用 `REF-8.1` 通道）+ **知情拒绝闸门**（`ToolInvocationContext.markCalled/hasCalled`：必须先读过子任务详情才许报阻塞）+ 原因 ≥10 字 + `block()` + `markManualIntervention()`；`buildSystemPrompt` 工具指引明写「做不到就报阻塞、不要硬写产出」。**`ExecutionResultHandler` 零改动**——既有「状态非 IN_PROGRESS 即丢弃上报」守卫已保证阻塞后不进评审。**门槛口径（对齐说明）**：DSH 的「连续 N 轮」在本域（一次执行 = 一条执行链）取等价物「**知情拒绝**」；不采用「先耗 N 轮再许拒绝」——那会把「一两轮就看清做不到」的诚实情形反过来拖长，与收敛意图相反（口径见类 javadoc） | 内部 LLM 可见工具 **7→8**（实测 `tool_resolved` 事件）；未读详情即报阻塞被拒 / 原因敷衍被拒 / 知情后落 `BLOCKED`+人工介入（单测 4 例新增，`InternalLlmExecutorToolsTest` 14/14）；E2E 复跑判据层全绿 |
| **REF-8.1.2** | **persona 两槽位（身份面）**（**2026-10-12 立项并实施**）：内部 LLM 的 systemPrompt 此前把身份写死（「你是 HelloAI 平台的子任务执行者（Agent: X）」）且**不可覆盖**，缺「专业角色面」（与外部样本 WorkBuddy 对照确认）。对齐 DSH `deployment:persona-prefix/suffix` + `dsh-persona` 的**遮蔽纪律**：部署给默认、单个 Agent 可覆盖 | **✅ M1.2 已实施（2026-10-12）**：`AgentExecutionProperties` 增 `personaPrefix`/`personaSuffix`（`helloai.execution.*`，默认空 ⇒ 零行为变化）；`AgentRuntimeContextAssembler.buildSystemPrompt` 改为「内置身份行 → persona prefix → 工具指引/输出契约 → persona suffix（末尾）」；取值顺序 `agent.labels.personaPrefix/Suffix` → 部署配置 → 空。**不动表结构**（`Agent.labels` 为既有 JSON map，注册/更新 API 已支持） | 默认空时 systemPrompt 与改造前逐字一致（回归断言）；prefix 落在身份行之后、工具指引之前（槽位序断言）；`labels` 覆盖生效且部署默认被遮蔽。单测 3 例新增（`AgentRuntimeContextAssemblerTest` 33/33）。**技能侧 `role` 属性（按任务匹配角色的那一档）未做**，见 §10.3 次序注 |
| **REF-8.2** | **补手脚 + 上下文**：`WorkspaceAccess`（先只读 + 只写产物目录）+ 读附件工具；`systemPrompt` 从 `""` → 「角色 + 工具使用指引 + 输出契约」；`maxIterations` **按任务类型分档**（可配，非全局常量） | **✅ M2 已交付（2026-10-12）**：`systemPrompt` 已填（`buildSystemPrompt`）；`maxIterations` 分档（`AgentExecutionProperties` + `RuntimeTurnExecutor.resolveMaxIterations`）。**手脚由 REF-8.1 覆盖**（`internal_read_attachment` / `internal_register_output_attachment`）；不建 WorkSpaceAccess 文件系统工具（需 sandbox，REF-3 条件触发不排期） | 内部 LLM 有非空 systemPrompt + 分档预算 + 读附件/交付登记手脚。单测 3 个新增；全量 1978 绿 |
| **REF-8.2.1** | **返工上下文「替换而非追加」**：`appendReworkContext` 现把**所有轮次**审核意见全量拼入（"共 N 轮历史审核"）⇒ 返工轮思考链与 token 随轮数线性膨胀，且对「能力不足」零收益 | **✅ 已实施（2026-10-12）**：`AgentRuntimeContextAssembler.appendReworkContext` 重写为替换语义——只铺开**最近一轮**（含时间/问题/评语/评分/已自认修复），历史轮次压成**一行摘要**（每轮首条问题截断、最多 3 轮 + 省略号）并明写「此前 N 轮意见**已被本轮取代**」 | 回归断言：历史轮次正文不进 prompt（`doesNotContain("### 第 1 轮")` 等）+ ≥4 轮封顶断言——**无论返工多少轮，返工段历史部分恒定 ≤ 一行** |
| **REF-8.2.2** | **结构化轮报（证据 + 状态 + 下一步）**：内部 LLM 输出契约**无证据段**，而评审侧 fail-close 明文要求 `VERIFICATION` 段（`prompts/subtask-review.md:51-52`「未携带验证证据 ⇒ 不得判 pass=true」）⇒ 两边**不同源**，内循环必然返工至死信（`LOG-20261012-007` 实测复现 2 次）。把「证据 / 状态 / 下一步」做成结构化块，一个形状同时喂评审证据、做不到通道、早停判据 | **✅ 已实施（2026-10-12）**：输出契约（`buildSystemPrompt` + 产出回填要求）补 **`VERIFICATION:` 围栏**（证据 + `STATUS: DONE|PARTIAL|BLOCKED` + `NEXT_STEPS`），**复用评审侧既有判据**（`ReviewEvidenceAssembler.verificationSignal` 仅判 `contains("VERIFICATION:")`）⇒ **评审侧零改动**即从「从严核验、不得判 pass=true」切回「核对一致性」。`LOG-20261011-007` **B 判据升级**（改挂轮报 `status=blocked` / `nextSteps` 重复）待 `REF-8.4` 拦截链落地时接 | **E2E 实测（决定性）**：同一任务改写摘要子任务 **返工 3 次进死信 → 首轮通过（rework=0）**；三个子任务全 DONE、`taskStatus=DONE`、脚本 **EXIT=0（判据层 + 流程层双 PASS）**——为 `REF-8` 首次全绿跑通 |
| **REF-8.2.3** | **上下文压缩**（**2026-10-12 立项并实施**，对齐 DSH `compaction-basic` + `compaction-tool-result-pruner`）：两道 = ①**确定性工具结果剪枝**（纯文本 + 标记，可审计）②**仍超预算才 LLM 摘要**（替换而非追加）；两条触发 = 每轮模型调用前的**压力检查**（窗口 × thresholdRatio）+ 提供方报错后的**溢出恢复**（压缩后重试本轮，有界） | **✅ M2.3 已实施（2026-10-12）**：新增 `ToolResultPruner`（阈值 8192 / 头 4096 / 尾 1024、标记文案与 DSH 逐字一致、**按 Unicode 码点**计长）与 `ContextCompactor`（Spring bean；复用同一 ChatModel+options 做摘要，输入＝system＋首条 user＋被压区间＋压缩指令；**逐字保留最近 retainRatio=0.16 尾部**；**绝不拆 assistant(tool_calls)+tool_result 对**）；`ChatModelToolLoop` 加**每轮调用前挂点**（DSH `agent/pre-step` 等价物）与**溢出重试**（DSH `agent/request-error` 等价物，成功响应重置）；`AgentEventType` 加 `CONTEXT_COMPACTED` 审计事件；配置 8 项入 `helloai.execution.*`（默认与 DSH 对齐：窗口 64000、0.8/0.16、8192/4096/1024） | **与标杆的一处微调（已登记）**：剪枝后已回到预算内则**不再调摘要**（省一次 LLM 调用，语义不变）。单测 12 例新增（`ToolResultPrunerTest` 5 / `ContextCompactorTest` 7：开关短路 · 剪枝足够不摘要 · 摘要替换且首尾逐字保留 · 摘要失败不阻断 · 溢出强制路径 · no-op 实例）；既有 `ChatModelToolLoopTest` 18/18 不受影响（无压缩器构造 ⇒ 行为与改造前逐字一致） |
| **REF-8.3** | **技能改「全量目录 + 惰性加载」**：注入目录段（非速览），新增惰性加载工具，`AgentSkillSpecService` 加 `get(name)` | **✅ M3 已交付（2026-10-12）**：`AgentSkillSpecService` 加 `getPackage(name)`/`loadBody(name)`；`InternalLlmExecutorTools` 加 `internal_list_skills`/`internal_load_skill`；`INTERNAL_LLM_TOOLS` 加 2 工具；`buildSystemPrompt` 增技能发现指引；**保留 `requiredTools`** 能力级声明 | 目录含全部技能；正文按需加载。单测 2 个新增；全量 1980 绿 |
| **REF-8.4** | **拦截链**（Spring AI **Advisors API**）——所有策略（能力闸 / 收敛熔断 / 审计）的**唯一挂点** | 挂在 `agentLoop.run` 外层 | `LOG-20261011-007` **A（能力闸）+ B（收敛熔断，阈值 `N=3`）已立项**（`D-2026-10-12-3`），**挂本行落地之后**；`C` 单独评估 |
| **REF-8.5** | **循环覆盖面收敛**：逐面判「该不该进循环」（子任务评审、终报告生成理论上都该带工具） | `RuntimeTurnExecutor:130`（**1 处**）vs `executeSync/executeStream`（**15 处 / 9 类**） | 覆盖面显式收窄并登记边界 |

## 10.3 执行次序（按「是否直接产出能力」）

```text
REF-8.1 工具面解封 ──► REF-8.2 手脚 + 上下文 ──┐
REF-8.3 技能惰性加载 ────────────────────────┼──► REF-8.5 覆盖面收敛
REF-8.4 拦截链（可并行铺）───────────────────┘──────► LOG-20261011-007 A + B
```

> **会话单源化降为支撑项**（**不是** `REF-8` 的首项）：**提升已有 `agent_event`**（补 `surface` 投影 + `deriveMessages()`），**不新建表**——`agent_event` 已是 append-only 事件主表（`AgentEventRecorder.java:11-15`）+ Outbox 双写 + 终态投影已在跑（`EventReconciliationServiceImpl`）。依 DSH 判据：能力来自 `ctx.tools` + `ctx.fs`/`ctx.shell`，「可见⟺已记录」是**工程品格**而非能力来源。

> **2026-10-12 用户裁定（`D-2026-10-12-2` / `D-2026-10-12-3`）**：
> ① **`REF-8.1` → `REF-8.2` → `REF-8.3` 即行开工**（当前 `T1` 最高优先，唯一直接产出「内部 LLM 变 agent」的一层）；
> ② `LOG-20261011-007` 的 **A（能力闸）+ B（收敛熔断）正式立项**，**连续相同驳回原因阈值 `N = 3`**（对齐日志"连续 3 次"现场），动手时机 = **`REF-8.4` 落地之后**（拦截链是其唯一挂点）；**`C`（产物接地校验）单独评估，暂不立项**；
> ③ `REF-8.5` 覆盖面收敛**不阻塞前三**，但**必须先定「哪些面进循环」**——清单见 §10.6，待用户勾选后立项。

> **2026-10-12 追加（DSH `0.2.1-alpha.2` 复核后，按用户指令顺次实施）**：新增三项**子工作项**，次序为
> `REF-8.1.1`（内部 blocked 通道，紧随 `REF-8.1`）→ `REF-8.2.1`（装配替换语义）→ `REF-8.2.2`（结构化轮报），
> 三者**不改变** `REF-8.4` 拦截链的挂点关系；`REF-8.2.2` 与 `LOG-20261011-007` **B（收敛熔断）同源**——B 的判据由「同因驳回次数」**升级为「轮报 `status=blocked` 或 `nextSteps` 重复」**（更早、更准），落地时机仍在 `REF-8.4` 之后。复核依据：`doc/research/HelloAI 内部 agent 内核收敛与借鉴思路（2026-10-12）.md` §1.5。
>
> **2026-10-12 追加（二，§1.6 复核后）**：新增 `REF-8.1.2`（persona 两槽位，**已实施**）；**已登记并实施**：`REF-8.2.3`（上下文压缩，见上表行）；**未登记候选**（`§1.6.4`，待裁定）：技能侧 `role` 属性（按**声明命中**的技能给身份，多命中按 priority 裁决、并列回退平台默认）、技能正文长度纪律（与压缩修剪阈值耦合，随 `REF-8.2.3` 一并定）、`REF-8.4` 挂点细化（拦截链 = `pre-step`/`request` 两挂点）。

## 10.4 收敛完成判据（较「组完成」更严）

1. **不依赖 MCP 会话**可独立构造 / 测试（`AgentLoopInput → AgentLoopResult` 自洽）；
2. **可见面单口径**——模型可见工具集 = `ToolRegistry.resolve` 结果，上游**无第二处二次过滤**；
3. **能力可验证**——拿一条「需读原附件 / 写产物」的验收任务，内部 LLM 能**自己调工具**完成；
4. **事件不双写**——模型可见消息与事件流同源。

## 10.5 红线

- **不新建 append-only 表**（`agent_event` 已是主表）；违反即造「一份事实两处落」的双轨；
- **不动 `ChatModelToolLoop` 循环本体**（骨架正确）；
- **不引入 LangGraph4j**——仅在「显式图 / 断点续跑 / HITL」触发信号出现时再评估（与《自建 java-harness 可行性分析》方案 C 一致）；
- 新增**对外**工具须走既有 `McpMcpServer` 守卫且**默认关闭**（`REF-6.13`）。**★射程 = 「对外暴露的新工具」**（`D-2026-10-12-5`）——`REF-8.1` 的**内部进程内通道**（`API_KEY_LLM` 无 MCP 会话，走会话守卫必 401＝L3 P1-1）为该红线**显式例外**：以「**执行链绑定闸门**」（`ToolInvocationContext`）替代会话守卫，且**不得**进入对外 MCP `tools/list`（`internal_*` 经非 `ToolCallbackProvider` 的 `InternalToolCallbacks` 承载）。判据：「**绕过外部守卫**」合理 ≠ 「**暴露到外部面**」合理。详见 `log/2026-10.md` `LOG-20261012-006`。

## 10.6 覆盖面清单（`REF-8.5`，16 项，**待用户勾选**）

**实测口径（2026-10-12）**：全仓生产代码 `agentLoop.run()` 调用点 **1 处**（已在循环）；业务 LLM 面走 `executeSync/executeStream` 单次聊天共 **15 处 / 9 类**。`PlatformAgentExecutionServiceImpl:90`（`:80-92` `executeStream` 内部派发）是**派发点非业务面**，不计入。合计 **1 + 15 = 16 项**。

**判据**：某面若「产出需查阅 / 校验外部证据（附件原文 / 库内容 / 多步推理）」⇒ **该进循环**（带工具）；若「纯文本改写 / 结构化抽取 / 单轮判定」⇒ **留单次**即可（进循环只增成本与不确定性）。

| # | 类 | 位置 | `scene` | 用途 | 建议 |
|---|---|---|---|---|---|
| 1 | `RuntimeTurnExecutor` | `helloai-core/…/core/agent/runtime/RuntimeTurnExecutor.java:130` | — | **唯一在循环的面**（工具调用循环） | ✅ 已循环（基线） |
| 2 | `PlannerDecomposeAsyncServiceImpl` | `helloai-core/…/core/planner/service/impl/PlannerDecomposeAsyncServiceImpl.java:144` | `planner_decompose` | 任务拆解 | ⭐ 进循环（可查技能 / 依赖 / 历史） |
| 3 | `ReviewExecutionEngine` | `helloai-core/…/core/review/support/ReviewExecutionEngine.java:87` | `subtask_review` | 子任务核验 | ⭐ 进循环（核验需读原始证据） |
| 4 | `TaskFinalReportServiceImpl` | `helloai-core/…/core/task/service/impl/TaskFinalReportServiceImpl.java:252` | `task_final_report` | 终报告正文整合 | ⭐ 进循环（需读子任务产出） |
| 5 | `FinalReportReviewServiceImpl` | `helloai-core/…/core/review/service/impl/FinalReportReviewServiceImpl.java:393` | `task_final_report_review` | 终报告审查 | ⭐ 进循环 |
| 6 | `TaskFinalReportServiceImpl` | `…/TaskFinalReportServiceImpl.java:389` | `task_final_report_outline` | 终报告出纲 | 🟡 可留单次（纯结构化出纲） |
| 7 | `RequirementClarifyServiceImpl` | `helloai-core/…/core/planner/service/impl/RequirementClarifyServiceImpl.java:433` | `requirement_chat` | 澄清流式主回复 | 🟡 可留单次（对话） |
| 8 | `RequirementClarifyServiceImpl` | `…/RequirementClarifyServiceImpl.java:471` | `requirement_clarify` | 澄清流式主回复 | 🟡 可留单次 |
| 9 | `RequirementClarifyServiceImpl` | `…/RequirementClarifyServiceImpl.java:888` | `requirement_finalize` | 需求终稿 | 🟡 可留单次 |
| 10 | `RequirementClarifyServiceImpl` | `…/RequirementClarifyServiceImpl.java:959` | (retry) | 终稿重试 | ⬜ 留单次 |
| 11 | `RequirementClarifyServiceImpl` | `…/RequirementClarifyServiceImpl.java:1038` | `DECISION_SCENE` | 澄清决策判定 | ⬜ 留单次（单轮判定） |
| 12 | `RequirementClarifyServiceImpl` | `…/RequirementClarifyServiceImpl.java:1303` | `requirement_clarify`/`requirement_chat` | 同步澄清 / 聊天 | ⬜ 留单次 |
| 13 | `SearchQueryPlannerServiceImpl` | `helloai-core/…/core/planner/service/impl/SearchQueryPlannerServiceImpl.java:195` | `REWRITE_SCENE` | 检索查询改写 | ⬜ 留单次（纯改写） |
| 14 | `SearchGapAssessor` | `helloai-core/…/core/planner/clarify/SearchGapAssessor.java:91` | `GAP_SCENE` | 搜索缺口评估 | 🟡 可选（需查库判缺口则应进） |
| 15 | `PromptEnhancerServiceImpl` | `helloai-core/…/core/planner/prompt/impl/PromptEnhancerServiceImpl.java:59` | `prompt_enhance` | 提示词增强 | ⬜ 留单次 |
| 16 | `AgentExecutionPreviewServiceImpl` | `helloai-core/…/core/agent/service/impl/AgentExecutionPreviewServiceImpl.java:53` | — | 执行预览 | ⬜ 留单次 |

> **勾选方式**：你只需对 `⭐`（其建议进循环）与 `🟡`（待定）表态——「进循环 / 留单次」；`⬜` 若无异议默认留单次。确认后 `REF-8.5` 立项，**把「进循环面清单」写死为验收边界**（明确「哪些面**不**进」，避免「内核收敛 = 全面进化」的误读）。

## 10.7 `REF-8.4` 本轮范围与红线②实操判定（**已裁定**，2026-10-13；5 项结论 = `D-2026-10-13-6~10`，记录 `LOG-20261013-011`）

> **Status（2026-10-13）**：`REF-8.4` 四件套 **① 挂点骨架 · ② 收尾轮 · ③ VerificationFence · ④ 出口分类 已全部交付**（① `09ecd747` · ③④ `3b5224a6` · ② `873bb559`）；全量 2031 例绿。记录 `LOG-20261013-010`/`-011`/`-012`。

> **缘由**：`REF-8.4` 自身「范围多大」「A 挂哪一层」两个问题此前未定；`plan` 现表述为「拦截链 = A+B 的**唯一挂点**」，与 research §1.7.2 红线②「否决内容留在派发层」**自相矛盾**。本节给出**可裁定的单一建议**（含 A 层归属的机械判据）。裁定后按 §13 收口（决策入 `log/2026-10.md` `### 决策` + 差距表 `D-2026-10-13-x`）。

### 10.7.1 建议范围：一个挂点骨架 + 一条链上策略

**`REF-8.4` 本轮 = ① + ② + ③ + ④；A 出列，三小候选另批，step 内重试延后。**

| # | 内容 | 形态（落点） | 验收 |
|---|---|---|---|
| ① | **三档挂点骨架** | `ChatModelToolLoop` 现有两处内联压缩**搬入**具名挂点 `beforeRequest`（原 `:106`）/ `onRequestError`（原 `:123`），并**净新增** `beforeTerminate`（现不存在）。载体 = 循环自有 hook 列表，**不强套 Spring AI `Advisors`**（理由见 10.7.3） | **不挂任何 hook ⇒ 行为与改造前逐字一致**（同 `REF-8.2.3` 的 no-op 构造验收手法）；既有 `ChatModelToolLoopTest` 18/18 不动 |
| ② | **`beforeTerminate` 首条实现 = 轮报合格性 + 按需收尾轮**（**已交付**，`873bb559`，`LOG-20261013-012`） | 读 `AgentLoopResult.text()` 的 `VERIFICATION:` 围栏；**仅在缺失/不合格时**追加**一次**收尾轮（有界），仍不合格则交 ④ fail-close（`ExecutionStatus.FAILED`） | 围栏齐全的执行 ⇒ **零额外 LLM 调用**；缺失 ⇒ 恰好一次收尾轮；超限有明确终态 |
| ③ | **`VerificationFence` 解析器（含 `STATUS` / `NEXT_STEPS`）** | 新构件；**与评审侧 `ReviewEvidenceAssembler.verificationSignal:346-351` 共用同一匹配器**（防「两处各写一份围栏判定」漂移） | 解析单测 + **同源断言**（同一输入两处判定一致） |
| ④ | **出口分类接线** | 轮报不合格 → 与 `RuntimeTurnExecutor:187-196`（「空产出护栏」先例）**同层**分类，**只改 `ExecutionStatus`，不碰 `SubTaskStatus`** | 红线①**反证**：插一条不合格轮报 ⇒ 分类生效且子任务状态未变 |

**出列 / 延后**：

| 项 | 处置 | 理由（代码依据） |
|---|---|---|
| **A 能力闸** | **出列 `REF-8.4`** ⇒ 落 `ResilientDispatcher`（与 `REF-5.3` 同族） | A 天然不在链上（见 10.7.2）；挂点抽象只需**一条**链上策略即可验证，A 出列**不削弱** `REF-8.4` 验收 |
| **三小候选**（技能 `role` / 技能正文字符纪律 / 读附件上限+分段） | 另批（无依赖，可并行） | §1.7.1 判为零状态机影响；与挂点骨架无耦合 |
| **step 内重试** | **延后**（先埋点采样 provider 失败分布） | 现有 `ChatModelToolLoop:113-134` 的 `while + overflowRetries` 已覆盖主形态（溢出，经 `compactOnOverflow`）；429/5xx 分布本仓**无实测数据**；泛化会放大欠佳 provider 的「重试 + 压缩」成本 |
| **B 的「跨轮重复」判据** | **并进评审层 `RepeatedFailureGate`**（不落链上） | 循环内**不可观测**：一次执行最多一份轮报（`lastText` 只在 `ChatModelToolLoop:148` 取），无「上一份」可比；跨执行轮次的比较属 `reviewHistory` 领域 |

### 10.7.2 A 的「否决」层级：**留派发层**

**建议裁定**：A 挂 `ResilientDispatcher`，复用 V27.1 先例模板（`isExecutionDenseMismatch:174-195`：timeline 事件 + `markManualIntervention` + 抛 `AgentUnavailableException` 走 fallback）；A 的落地形态 = 给 `SubTaskQueryPort` 加一个「需读原始附件」信号（与 `isExecutionDense:108` 并列、**同风格**），在 `ResilientDispatcher` 加 `isAttachmentReadMismatch`，与既有判定**共用同一 timeline 事件与同一 `markManualIntervention`**。

红线②的**实操判定**（不是口号，逐条可机械核对）：

| # | 判据 | 通过条件 | 代码锚点 |
|---|---|---|---|
| ① | **提交点** | reject 分支执行时，目标实体**尚未越过提交点** | `ResilientDispatcher:166`（`assignNext` = 提交）；循环内否决**必然已越过**（那时子任务已 `IN_PROGRESS`） |
| ② | **回退量** | reject 后**无需撤销**任何已提交的状态迁移 | 派发层 reject 只是「不提交」，回退量 = 0；循环内否决 ⇒ 回退 = 新造状态迁移 ⇒ 踩红线① |
| ③ | **已付成本** | reject 时刻不可回收成本 = 0 次 LLM 调用 | 派发层 = 0；循环内 = 已付 N 次 |
| ④ | **归属** | 复用既有可观测与人工介入语义（不新造） | 既有 `SUB_TASK_DISPATCH_SKIP_NO_CAPABILITY` / `DISPATCH_SKIP_EXECUTION_DENSE` + `markManualIntervention` 形状；循环内新造否决 ⇒ 新事件码 + **可观测 3 处登记** + 新介入语义 |

> **一句可执行口径**：*「能否在 `assignNext` 之前拦下」——能则留派发层；不能则说明该策略本就不该是闸门（应改作可观测或建议型）。*

**同步订正建议**：`plan` §10.2 `REF-8.4` 行「拦截链 = A+B 的**唯一挂点**」⇒ 改为「**B 挂拦截链；A 挂 `ResilientDispatcher`**」；差距表 `D-2026-10-12-1` ④「A+B 挂 `D4` 拦截链之下」同口径订正。

### 10.7.3 为何**不**套 Spring AI `Advisors`（三处硬约束）

1. Advisors 只能挂 **`ChatClient`**；`ChatModelToolLoop:115` 调的是**裸 `ChatModel`**，且全仓 **0 处** `Advisor` 引用 ⇒ 要走 Advisors 须先把 provider 工厂（`AnthropicCompatibleProtocolFactory` / `DeepSeekProviderChatClientFactory`）改造成 `ChatClient` + 注入 advisor。
2. DSH `agent/pre-step`（`agent.ts:275`）是**可重入 waterfall**（`compaction-basic:158` 做完副作用后调 `next()` 放行）；Advisor 是**线性装饰器**（`chain.nextCall()`）⇒「副作用后放行」这个形态**表达不出来**。
3. DSH `agent/turn-stopping`（`agent.ts:362`）是 **`serial` 而非 waterfall** ⇒ 本就不该塞进 Advisors。

⇒ 借的是**挂点位置**（DSH 的语义），不是**框架容器**（Advisors 的 API）。

### 10.7.4 挂点 ↔ DSH 同构物对照

| 挂点 | DSH 同构物 | HelloAI 落点 | 本轮挂什么 |
|---|---|---|---|
| 请求前 | `agent/pre-step` waterfall `agent.ts:275` | `ChatModelToolLoop:106`（搬入 `beforeRequest`） | 保持压缩；**不挂 A** |
| 请求异常 | `agent/request-error` `agent.ts:512` | `ChatModelToolLoop:123`（搬入 `onRequestError`） | 保持溢出恢复；通用化延后 |
| **终止前** | `agent/turn-stopping` serial `agent.ts:362` | **不存在 ⇒ 本节净新增** | **② 轮报合格性 + 按需收尾轮** |
| 循环出口分类 | （DSH 无此层，属平台） | `RuntimeTurnExecutor:187-196`（空产出护栏先例） | ④ 接线 |
| 派发 / 认领 | （DSH 无编排层） | `ResilientDispatcher:159 / 174-195` | A（已出列，落此层） |

**红线核对**：①② 为既有自动机制（仅做压缩，不落状态）；③ 收尾轮**不落任何平台状态**；④ 只改 `ExecutionStatus`；A 在认领前。⇒ **红线① 不破**。`ExecutionResultHandler.handleReport:197`（状态非 `IN_PROGRESS` 即丢弃）仍是平台状态机唯一边界，**本轮不改**。

### 10.7.5 附带订正：research §1.7.4 引用漂移

`LOG-20261012-011`（`doc/log/2026-10.md:5271 / 5273 / 5277 / 5286`）称研究文档含 **§1.7.4「待实施清单」**，但该文档 §1.7 **实际止于 §1.7.3**（§1.7.3 自述「待实施项清单见 `plan/` §10.3 + 差距表」）⇒ 建议按治理规则修正引用（研究文档只答「会碰什么」，清单归 `plan/`）。

### 10.7.6 增补（2026-10-13 二）：能力鉴别的落点 —— 「发现放内、处置放外」

> 依据：`research/DSH 与 HelloAI 执行流与状态机比对（2026-10-13）.md`。本节**不改变** 10.7.1~10.7.5 的结论，只补「能力鉴别在哪一层」的裁定与其唯一缺口。

**裁定**：**发现放内部 LLM 内部流程；处置放平台派发层。** 既不走「评审驳回」（消耗返工轮次、把能力不足记成质量不合格），也不走「循环内换 apiKey / 换模型」（DSH 无此机制可抄；且污染成本画像与打分、造成会话流归属歧义）。

**理由（两条实测）**：

1. **DSH 没有「LLM 内部换模型」可抄**：模型路由是 agent 创建时**声明**（`packages/core/agent-loop/src/agent.ts:598-599` 缺 `provider`/`model` 直接抛错），`packages/llm/llm-retry` 重试**同一条 route**；「换执行者」在 DSH 靠**委派 subagent**（`packages/subagent/README.zh.md`）。⇒ 「照抄标杆」这条原则本身否定「循环内换 key」。
2. **HelloAI 的「换执行者」链已存在**（本轮新发现）：`SubTaskStateMachine:31` 已有 `BLOCKED → PENDING`；`SubTaskDispatchServiceImpl.dispatchBlockedSubTask:70-95` 已是完整链（闸门熔断 + 退避 → `accumulateReassignAttempt` → `resetToPendingForDispatch(BLOCKED)` → `assignNext` 选人/fallback）。⇒ **状态机零改动**（满足「任务状态机流转保持不变」）。

**唯一缺口 = 自动触发接线**：`dispatchBlockedSubTask` 现存调用方**均为人工入口**（`SubTaskController.reassignById` 需 `subtask:reassign`；`redispatchInProgress`）。两条候选（**均不改状态机**）：

| 候选 | 做法 | 评价 |
|---|---|---|
| **(a) 同事务接线** | `internal_report_blocked` 落 BLOCKED 后，若存在同角色可替代候选，调用 `dispatchBlockedSubTask`（自带闸门） | 早、准；但把「派发」语义塞进执行侧工具 |
| **(b) 巡检接线** | 仿 `SubTaskPendingOrphanTask` 增一个「因能力不足而 BLOCKED」的巡检自动重派 | 层次干净（平台侧）；代价是延迟 |

**建议**：(b) 为主、(a) 为可选加速。理由：`block` 与 `dispatch` 都是平台服务，让**平台侧巡检**决定「是否换人」比让执行侧工具直接派发更符合 §1.7.2 红线②的精神（**判定与处置留在平台**）。

**预算口径（须知晓）**：两条出边（`REWORK` 与 `BLOCKED→重派`）**共享** `attempt_total`（上限 `helloai.dispatch.max-reassign-attempts = 5`，`AgentDispatchProperties:70`），且重派受 `REASSIGN_BACKOFF_SECONDS = {60,180,600,1800}`（`SubTaskDispatchServiceImpl:67`）闸门约束 ⇒ 「自报阻塞换人」**并非零成本**，但**不消耗 `reworkCount`**、**不进评审**。

**可移植性判据（用户最关注项）**：

> **内核可移植 ⟺ 内核只依赖「抽象对话模型 + 工具集 + 会话日志」，不认识 `task` / `subTask` / `turn` / `agentId` / 平台事件枚举。**

按此核对：`AgentLoopInput` 13 字段含 **6 个平台概念**（`runId/taskId/subTaskId/turn/agentId/eventRecorder`）；`ChatModelToolLoop` 直接写 `AgentEventType.*`；`ToolInvocationContext` 为 `static`。⇒ **现状不符**，`REF-8.4` 的挂点抽象为**必要不充分**。

⚠️ **与既有红线的张力（已裁定，`D-2026-10-13-7`）**：§10.5 红线写着「**不动 `ChatModelToolLoop` 循环本体**」。可移植化必然要动它的**输入契约与事件出口**（可「不动循环逻辑、只动入参/出口」，二者不必然冲突）。**已裁定（2026-10-13）**：可移植性**登记为独立待办**，不列入 `REF-8.4` 本轮范围（记录 `LOG-20261013-011`）。

---

# 11. `M-1`~`M-9` 供应商 / 模型注册信息完善（magpie 借鉴，2026-10-13 新增）

> **编号**：`M-x`（已登记《差距表》§0 编号表；与 `REF-x.y` **并列**——`REF-` 号段 `x` 已占满 1~8，不扩号段）。
> **来源**：`research/magpie_借鉴分析.md`（勘察口径 `[实测]` 附 `path:line` / `[推断]` 单列；§九为事实核查表）。
> **与既有组的关系**：本组**不并入** `REF-8`（那是「内部 LLM 能力面 / 可见面收敛」，域不同）；二者共用的只是「输出契约」这一概念，实现互不依赖。
> **本组性质**：面向**供应商与模型的注册信息**（预置目录 / 模型目录 / 能力元数据 / Key 管理），面向运维与 Agent 注册的前置数据。
> **§12/§13/§14 顺延说明**：本组插入为 §11，原 §11~§13（明确不做 / 统一收口 / 裁定记录）顺延为 **§12~§14**，内部引用已同步。

## 11.1 起点：已确认的现网缺陷（2026-10-13 实测，随本次复核一并修好）

同一批「陈旧值」在仓里有 **6 个扩散点**，全部已订正（明细见 `research/magpie_借鉴分析.md` §六第 2b 行与 §九复核条目）：

| # | 缺陷 | 证据（`path:line`） | 状态 |
|---|---|---|---|
| D-a | 预置 4 条 `defaultModel` **全为目录外陈旧值** | `V61` 注释（moonshot `moonshot-v1-8k` 已下线、**调用 404 是「验证失败」直接根因**）；`V101` 注释（`deepseek-chat` / `qwen-plus` **目录内已无、注册被拒**）；目录真值 `V49:72-73,85-89,101-103,115`。**另：minimax 默认位因 `M2.5`/`M2.7` 全系列即将下线，已于 `V108` 再订正为 `MiniMax-M3`**（参考 magpie `workbuddy.go:91`/`factory.go:625`）；`V108` 同批**追加第二种可选模型 `MiniMax-M3.1-Flash-Preview`**（非默认，用户 2026-10-13 提供官方口径） | **已修**（`providerCatalog.ts`，`b580cff4`；minimax 于 `V108` 同步） |
| D-b | 预置 3 条 `baseUrl` 与平台口径冲突（多带 `/v1`） | `application.yml:305`「OpenAI 兼容模式实际端点为 `{base-url}/v1/chat/completions`」⇒ 原值拼出 `/v1/v1/...` | **已修**（`b580cff4`） |
| D-c | 预置 minimax `protocolType` 与平台内置**相反** | `V46:50` + `application.yml:301` + `:285` 注释「minimax 走 Anthropic 兼容接口」 | **已修**（`b580cff4`） |
| D-d | **密钥验证兜底模型表**同源陈旧（deepseek / minimax / dashscope 三条） | `LlmProviderKeyVerifyServiceImpl.java:45-51`（moonshot 已于 V61 时单独修过，其余三条漏修——正是 404 命门） | **已修**（`43ea2494`） |
| D-e | DeepSeek 末位兜底模型同源陈旧 | `DeepSeekProviderChatClientFactory.java:25` | **已修**（`43ea2494`） |
| D-f | **`application.yml` 自身也是陈旧源**（`default-model` 2/4 陈旧） | `git show 794cd631^:helloai-start/src/main/resources/application.yml` = `DEEPSEEK_CHAT_MODEL:deepseek-chat` / `DASHSCOPE_CHAT_MODEL:qwen-plus` | **已修**（`794cd631`） |

> **根因**：预置（前端常量）/ 迁移种子（V46·V49·V61·V101·V108）/ `application.yml` / Java 兜底常量 —— **四种载体手工维护、无一致性守卫**，且**彼此都会漂移**（D-f 即证：`application.yml` 曾被当作「规范真值」，其实自己 2/4 是陈旧的）。**唯一真值 = `llm_provider_model.is_default=1`**。这正是 `M-2③` 要补的东西（现守卫已覆盖四方，见 §11.2 `M-2` 行）。
> **写回路径**（防复发重点）：`AddModelFormDialog.vue:311-313` 在**创建**预置供应商时直接取 `entry.defaultModel`（编辑分支 `:296` 只改 `billingType`）⇒ 删除后重加会把迁移已订正的值写回旧值。注意既有内置 provider 走 `existingProvider` 分支（`UpdateLlmProvider` 只改 `billingType`），故该风险仅在「该 provider 行不存在」时触发。

## 11.2 动作明细

| 编号 | 动作 | 落点 | 验收口径 |
|---|---|---|---|
| `M-1` | 供应商预置目录扩充（vendor 直连优先，relay 按需） | `helloai-ui/src/views/settings/providerCatalog.ts` | 每条 `providerCode` 唯一；新增条目的 `protocolType/baseUrl/defaultModel` 均与平台口径一致。**Status（2026-10-13）**：首波已落地 5 条直连厂商（openai/anthropic/xai/mistral/groq → 共 9 条，`b580cff4`）；端点/协议符合工厂约定、defaultModel 取 models.dev 当前值；**defer**（需 M-5 extraConfig / 默认有歧义）：zhipu·zai·google·tencent（非 `/v1` 路径）·siliconflow·modelscope·nvidia·openrouter（聚合器无单一默认）。★**可用性前置（2026-10-13 复核补）**：新预置在 `llm_provider_model` 中**无种子行** ⇒ 密钥可配、但 **Agent 注册必被拒**（`AgentSkillPolicyService.validateModelType:126` → `isModelAvailable` ⇒ 400「模型不可用或已禁用」），需待 `M-3` 补目录或先在供应商详情手工 `addModelByProviderId`。**余量（2026-10-13 实测）**：magpie 顶层 56 条 − 已借鉴 9 = **余 47**（`KindVendor` 24 / `KindRelay` 18 / `KindLocal` 4 / `KindRemote` 1）；api-key 类共 51 条 ⇒ 已借鉴 **9/51** |
| `M-2` | ① **去陈旧硬编码**（D-a~D-f，已完）；② 补多协议端点 / 模型目录 id / KeysURL；③ **加一致性守卫**（预置 ↔ 种子 `is_default=1` ↔ `application.yml` ↔ Java 兜底常量 **四方** + 覆盖完整性） | `providerCatalog.ts` + `LlmProviderKeyVerifyServiceImpl.java:45-51` + `application.yml` + `scripts/shell/verify-provider-catalog-parity.sh` | **守卫须反证可拦**：把某条预置 `defaultModel` 改偏离 `llm_provider_model.is_default=1` ⇒ 守卫 **EXIT=1**；且**目录新增 providerCode 未登记守卫白名单 ⇒ 亦须 EXIT≠0**（防新增条目静默不受保护）。**Status（2026-10-14）**：①③ 已完（③ 于 `d20a0160` 扩为四方 + 集合比对 + 双向反证）；**② 已完**——`CatalogProvider` 增补 **`catalogId`**（models.dev 目录 id / ⇐ magpie `PresetDef.Catalog`，必填，9 条已逐条填值）+ `apiKeyUrl` 改**可选**（对齐 magpie `KeysURL` 的 `omitempty` 语义，为后续免 Key / 本地预置留位）；守卫新增第 6 项「`catalogId` 非空」结构性检查 + 反证③（`--inject-missing-catalog-id`）。⚠️ **多协议端点按 §七「与 `M-5` 同批处理」暂不落前端字段**——避免复刻 `extraConfig`「只写不读」反模式（运行时消费归 `M-5`） |
| `M-3` | 模型目录种子（models.dev 快照 → `llm_provider_model`），替代「手工静态行」 | 迁移 / 一次性导入任务 | 各内置 provider 恰有 1 条 `is_default=1`，且与预置 `defaultModel` 一致。**Status（2026-10-14）**：**已完** —— 迁移 **`V109__seed_builtin_provider_models.sql`**。① 为 `M-1` 首波 5 家（openai/anthropic/xai/mistral/groq）**补 `llm_provider` 行**（`builtin=1`；此前无行 ⇒ 直接写模型种子会静默 `INSERT 0`），`ON CONFLICT (provider_code) WHERE deleted=0`（V51 部分唯一索引，无谓词报 42P10）；② 播种 9 家模型清单（**150 行**，见口径）；③ 全行 `is_default=0` 插入 → **先降后升**收敛默认位（避 V49 的 `(provider_id,is_default)` 部分唯一索引）；④ 能力列**显式写**（防 V52 不回填丢 `web-search`）；⑤ `DO $$` 断言「9 家各恰 1 条默认位且 == 预置」。**播种口径**（用户 2026-10-14 裁定 + 一项收紧）：`text 输出 + context>0 + status≠deprecated` 全量，**另加 `tool_call=true`**（不加会混入 `qwen3-asr-flash`/`qwen-vl-ocr`/`qwen-mt-*`/`-realtime` 等**非对话**模型 ⇒ 进 Agent 模型选择器即缺陷；实测 164→149）。**默认位例外**：`deepseek-v4-flash` 在 models.dev 已标 `deprecated`，本批**不改默认位**，仅纳入清单并标注待办。**未做（有意）**：逐模型能力精化属 `M-4`；多协议端点属 `M-5`；5 家新增 provider 采**不建 yml 段**的口径（yml 为遗留载体，`V46` 起已 DB 驱动） |
| `M-4` | 能力元数据回填（modality/efforts → `capabilitySkills` / `availableOptionalSkills`） | `llm_provider_model` 能力字段 | 回填后无模型行缺能力字段；`thinking` / 图像 / `web-search` 推导有断言。**Status（2026-10-14）**：**已完** —— 迁移 **`V110__llm_provider_model_capabilities.sql`**。承载形态按已裁定 **形态乙**（`D-2026-10-13-9`）：`llm_provider_model` **新增能力谓词列 `capabilities`(JSONB)** = `{"input":[模态],"output":[模态],"reasoning":bool,"toolCalling":bool,"webSearch":bool}`，模态词表 `{text,image,audio,video}`（缺省 `[text]`）。① 兜底：全表先写保守面 + provider 级 `webSearch`（使 V49/V108 遗留行、大小写变体行、e2e 探针残留行也拿到**一致**谓词，而非列默认 `webSearch=false`）；② 逐行回填 `V109` 种子集合（150 行）按快照写全量谓词（`input/output←modalities`、`reasoning←reasoning`、`toolCalling←tool_call`、`webSearch←V52 provider 口径`）；③ `DO $$` 断言 5 条（无缺字段 / `output⊇{text}` / 含 image + `kimi-k3` 含 image + `MiniMax-M3` 含 video / 有 `reasoning=true` / 三家 `webSearch` **全行** true 且 deepseek 全 false）。实测：158 行、缺字段 0、reasoning 110、含 image 108、`webSearch` 69。**★与 V52 两列的关系（本批不动）**：`capability_skills`/`available_optional_skills` 有**两个活消费方**（`AgentSkillPolicyService.validateAgentSkills:53-68` + `getSkillOptionsByModelType`）⇒ 遵删列前置，**消费方切换归 `M-9`**（本迁移只新增谓词列，不改 V52 两列任何值）。**★已知口径冲突（留痕）**：`deepseek-v4-pro` 的 input，models.dev = `["text"]`（仅文本，且该库内部自洽：DeepSeek *flash* 线多模态、仅 `-pro` 为 text-only），而用户 2026-10-13 口径为 `[text,image]` ⇒ 本批**取快照值**（保守面 = fail-closed）并**标注待办**（同 `deepseek-v4-flash` deprecated 范式）。**★复发风险已登记**：新列与 V52 同为「回填只对当时已有行生效」⇒ 后加行走列默认会丢谓词；本批用「兜底 UPDATE + `DO $$` 断言」缓解，**持续性守卫**（文件级，不依赖 DB）随 `M-9` 消费方一并落 |
| `M-5` | 多协议端点承载 —— **必须包含"新增 `extraConfig` 消费逻辑"**（该字段此前为**只写不读**：全仓 0 处消费，`completionsPath`/`messagesPath` 仅存在于 `V46:27,63` 列注释与 `LlmProvider.java:59` javadoc） | `OpenAiCompatibleProtocolFactory`（`baseUrl` 装配 `:80-82`）/ `AnthropicCompatibleProtocolFactory`（`:83`） | 设 `extraConfig.completionsPath` 能**改变实际请求路径**（有单测断言）；表结构不变。**Status（2026-10-14）**：**已完**。① **消费逻辑（本次核心）**：新增 `ExtraConfigPaths`（键名 `completionsPath`/`messagesPath` + 空白即视为未配置的**唯一读取器**，防两工厂各自漂移）；`LlmProviderProfile` **追加末位字段 `extraConfig`**（端口扩字段，7 参；6 处构造点同批更新）；两工厂读该值并调 Spring AI 的 `Api.Builder.completionsPath(...)`（**未配置 ⇒ 不设置**，走各自默认路径 ⇒ 与 M-5 前运行时**逐字一致**）；并**把端点路径并入 ChatModel 缓存 key**（新增六元组重载，`endpointPath` 为空**不追加段** ⇒ 旧 key 逐字不变），否则改覆盖项会命中旧实例、请求打到旧路径且不自愈。② **表结构不变** ✅（复用 `V46` 既有 `extra_config` JSONB，无新迁移）。③ **验收有反证**：新增 8 个用例（OpenAI 5 / Anthropic 3），以**本地 `HttpServer` 桩断言「实际收到的请求路径」**——设 `/custom/chat`（或 `/custom/messages`）⇒ 桩收到的就是该路径，未配置 ⇒ 默认 `/v1/chat/completions`（`/v1/messages`）；**停用消费逻辑后该断言确实失败**（`expected "/custom/chat" but was "/v1/chat/completions"`）⇒ 非测试自证。④ **闭环**：写入方本就存在（`AdminLlmProviderController:105,125` + `LlmProviderServiceImpl:104` + 前端 `api/settings.ts` 三处类型），此前缺的只是读取方 ⇒ 「只写不读」至此消解。**★未做（有意）**：前端**表单控件**未增（API 已可传 `extraConfig`，加控件属 UI 体验项）；`M-1` 遗留的 defer 预置（zhipu/zai/google/tencent 等）**未随本批加入**（属 `M-1` 扩目录范畴） |
| `M-6` | Key 指纹标识（SHA-256 前缀，用于同 key 去重 / 多 key 区分，不暴露明文） | `ApiKeyResponse` + 添加模型弹窗 | 同一 key 二次录入被识别为「已存在」。**Status（2026-10-14）**：**已完** —— 新迁移 **`V111__credential_vault_fingerprint.sql`**。① **承载**：`credential_vault` 加 `fingerprint VARCHAR(64)`（**可空、不设默认值** + 部分索引 `(provider, fingerprint) WHERE deleted = 0`）；口径 = **SHA-256(明文) 前 12 字节小写 hex（24 字符）**。**★归属订正（2026-10-14 实测）**：magpie 的**供应商 API Key** 口径实为 `internal/provider/keys.go:69-70`（`hex.EncodeToString(sum[:5])` = **前 5 字节 / 10 字符**，`AddKeys` 去重即用该值比对）；原文所引 `internal/access/access.go:417-419`（`hex.EncodeToString(sum[:12])`）是 **LAN 调用方访问令牌** 的 ID，**与供应商 Key 无关** ⇒ helloai 取 12 字节属**有意加强**（以留碰撞余量）、**非对齐**。唯一实现 `core/system/crypto/CredentialFingerprint#of`（与同包 `AgentApiKeyCipher#sha256Hex` 的**全量**点查哈希刻意不合并）。② **判重**：作用域 = **owner + provider**（同一把 Key 跨 provider / 跨 owner 合法复用 ⇒ 不互相判重）；命中 ⇒ `created=false` + 「已存在」文案，**不落库、不清 ChatModel 缓存**（原 `saveApiKey` 走 `rotatePlatformApiKey` ⇒ 每次保存都新建一行，是本次修的缺陷本体）。③ **历史行回填**：AES-GCM 密文（随机 nonce）**无法在 SQL 侧还原明文** ⇒ 迁移**不回填数据**，由 `CredentialVaultService#backfillFingerprints` **应用层惰性回填**（写入前扫该 owner+provider 的「指纹空 + 密文非空」行 ⇒ 解密补写；只补不覆盖、单行解密失败只 WARN 不阻断）；回填**落在 `system` 域内**（agent 域不得 import `system.entity`，RM5 批 3 只读快照先例）⇒ vault 服务注入 `CredentialCryptoService` 自行解密，对外只暴露一个方法。④ **契约面**：`PlatformProviderConfigService#saveApiKey` 由 `void` → `ApiKeySaveOutcome{created,fingerprint,message}`（core `agent/dto`），API 层映射 `LlmProviderApiKeyResponse`；`ApiKeyResponse`（`resetKey`）补 `fingerprint`；前端 `api/settings.ts` 加 `ApiKeySaveResult` 并在 `Settings.vue` / `AddModelFormDialog.vue` 提示「已存在」。⑤ **验收**：新增 **12** 用例（`CredentialFingerprintTest` 7 —— 含**外部 Python 独立算出的 golden 向量**，改算法/改前缀长度必红；`PlatformProviderConfigServiceTest#SaveApiKeyDedup` 5 —— 含 `InOrder` 断言「**回填必须早于判重**」）；**★反证**：临时停用判重分支 ⇒ 该组 4 条红 ⇒ 非自证。⑥ **回归**：`helloai-core` 全量 **2057 用例 0 失败 0 错误**（基线 2045 + 新增 12）；`vue-tsc --noEmit` **EXIT=0**；`V111` 以 `BEGIN; … ROLLBACK;` 在本地 PG 空跑四步全 OK。**未做（有意）**：批量录入去重归 `M-7`、多 Key 主备归 `M-8`，本批只交**指纹基座**（前端未加「Key 列表」UI —— 当前仍是单 ACTIVE 语义，多条目待 `M-8`） |
| `M-7` | Key 批量录入去重（粘贴多把自动拆分；换行 / 逗号 / 分号 / 空格分隔） | `AddModelFormDialog.vue` + `saveLlmProviderApiKey` 批量接口 | 返回「新增 N 把 / 已存在 M 把」 |
| `M-8` | 多 Key（主 / 备 + 手动切换 / 停用；**不做**自动切换与 routing） | `llm_provider` 关联 key 表或 `credential_vault` 多条目 | 主/备可手动切换；**无**自动 failover（红线 `N-1`） |
| `M-9` | 模型能力 → Agent skills 细化（efforts / modality 驱动，当前只有 `thinking` 开关） | `Agent.skills` 注册校验 + `llm_provider_model` 白名单 | 能力声明不越出模型实际能力 |

## 11.3 执行次序

`M-2①`（**已完**）→ **`M-2③`（守卫，防复发——优先于扩目录）**（**已立**，`d20a0160`）→ `M-1`（**首波已落地**）+ `M-2②`（**已完**，2026-10-14）→ `M-3`（目录种子，**已完** `V109`，2026-10-14）→ `M-4`（能力回填，**已完** `V110`，2026-10-14）→ `M-5`（多协议端点 + 消费逻辑，**已完**，2026-10-14）→ `M-6`（Key 指纹，**已完** `V111`，2026-10-14）→ `M-7`~`M-8`（批量去重 / 多 Key）→ `M-9`。

**依赖约束**：

- `M-3` 依赖 `M-1`（先有 provider 才有其模型清单）；`M-4` 依赖 `M-3`；`M-9` 依赖 `M-4`。
- `M-1` 的**可用性**反向依赖 `M-3`：`M-3` 未落地前，新增预置在 `llm_provider_model` 无模型行 ⇒ 只能配置密钥、**不能注册 Agent**（§11.2 `M-1` 行的「可用性前置」）。故 `M-1` 首波是**结构先行、能力待 `M-3`**。
- `M-2③` 的守卫**先立**的理由不是「能覆盖新增条目」，而是——新增条目**必须被显式归类**：有迁移种子的进 `SEEDED` 做四方比对；运行时建档（无种子）的进 `NO_SEED` 白名单并只做结构性检查（`protocolType` 枚举 / `baseUrl` 不带 `/v1`）。**归类漏了即 FAIL**（`d20a0160` 起生效），故守卫必须先于扩目录，否则扩进来的条目会静默不受保护。

## 11.4 验证集

- `M-2`/`M-1`：`cd helloai-ui && npm run type-check` EXIT=0
- `M-2③` 守卫：**双向反证**——`--inject-deviation`（篡改期望值）与 `--inject-catalog`（篡改目录副本）**均须 EXIT≠0**；正验 EXIT=0；已接入 `scripts/ci/ci-gate.sh` 门禁 3
- **守卫覆盖边界（须随改动同步，不得口头承诺）**：① 有种子 **9 家**（deepseek/moonshot/minimax/dashscope + `M-3` 收编的 openai/anthropic/xai/mistral/groq）做「预置 ↔ 种子默认位 ↔ Java 兜底（有条目才比）」比对；② 全部预置条目做结构性检查（`protocolType` 枚举 / `baseUrl` 不带 `/v1` / **`catalogId` 非空**）；③ **`NO_SEED` 白名单已于 `V109` 清空**（9 家全有种子）；yml 比对按「**有段才比**」进行（yml 是遗留载体，新增 provider 不建段），另加反向检查「yml 段必须 ∈ SEEDED」与「yml 载体不得整体缺失」
- 全量：`helloai-core` 单测 0 失败 + `bash scripts/ci/ci-gate.sh` 5 门禁
- **端到端回归（覆盖 D-a 写回路径）**：删除某内置 provider → 从预置重新添加 → 密钥验证通过（不再 404）
- **新增预置可用性回归（覆盖 `M-1` 可用性前置）**：以某新增预置（如 openai）配好密钥后，用其 `defaultModel` 注册 Agent ⇒ **`M-3` 前预期 400「模型不可用或已禁用」**（说明须先补 `llm_provider_model` 行）；`M-3` 后预期注册通过
- `M-5`：断言 `extraConfig.completionsPath` 生效（**已完**，8 个 `HttpServer` 桩用例 + 反证）
- `M-6`：**已完** —— 同 Key 二次录入 ⇒ `created=false` 且**不新增行**（`SaveApiKeyDedup` 5 条 + `InOrder` 断言「回填早于判重」+ 反证）；指纹口径由外部 golden 向量锁定
- `M-7`：批量录入返回「新增 N 把 / 已存在 M 把」（待办）

## 11.5 红线

- **不抄**：`N-1`~`N-5`（账号切换/failover、Agent 配置注入、价格 tier 计费、Key 存明文、**端点 URL 原样照抄**）。
- **不改表结构**（`M-5` 只用既有 `extraConfig` JSONB，补消费逻辑即可）。
- 预置值是**功能性字符串**：改动须同步「预置 ↔ 迁移种子（`is_default=1`）↔ `application.yml` ↔ Java 兜底常量」**四方**（并由 `verify-provider-catalog-parity.sh` 兜底），且不得新增 `V*.sql` 的改动（Flyway 校验和不可变，订正一律新迁移）。
- 凭据仍走 `credential_vault`（PLATFORM 级加密存储），**不得**退回本地明文文件。

---

# 12. 明确不做（C 档，与治理红线一致）

- Octop 单进程 / 双后端 / 进程内调度（C1~C3）——路线分歧；
- AgentTeams CRD / Helm / leader-election / kine / Matrix 房间（C4）——K8s 原生平台实现细节；
- CDP 逐帧直播 / Python IM 网关 / 桌面移动语音交付面（C5~C7）——定位无关；
- **外部 agent 对接**（C8）——用户裁定可借鉴内容有限。**v2 补独立证据**：AgentTeams 全仓 `a2a` **零命中**、`interface.go:32-40` 为**封闭 runtime 枚举**（新增 runtime 必须改码）；helloai 的 MCP+SSE+心跳接单是当前最优解。
- **Fork 触发入口 / 原 Run 冻结 / 驱动新 Run 执行**（C9，2026-10-09 新增）——**WONTFIX**（`D-2026-10-09-5`）。理由：①「驱动执行」自 2026-10-04 拍板起即为后置项（与 Resume 对称的「先做成、再做好」）；② 实际用法（分叉重跑 / 路径对比）已被 **Return**（REWORK→驳回→改派→重开工，闭环完整）与 **Replay 工作台**覆盖；③ 驱动执行须改 ADR-001 的 Run 标识模型 + execution command 载荷（协作规约 §30/§31），成本收益不匹配。**已建部分**：`AgentEventForkService` 快照复制（6 单测全绿、零生产调用方）**保留**为未接线的内部能力；是否删除另行裁定。回流登记：《差距表》§7.1.2 `R1`/`R2`。

---

# 13. 每组的统一收口（验证 + 回填）

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

# 14. 裁定记录（原「待拍板」段，2026-10-09 已全部裁定）

> **本计划当前无待拍板项**（REF-7 §8.2 的 C1~C3 三条设计约束已于 2026-10-12 **定稿**，见 §8.2；`REF-7` 本身仍挂起）。
> 原 5 条待拍板已全部裁定，记入《差距表》`D-2026-10-09-5`（Fork）与 **`D-2026-10-09-6`**（下述 5 条）。

| # | 议题 | **裁定** | 依据 / 取舍（保留供追溯） |
|---|---|---|---|
| ③-1 | **REF-1.3 语义位落点** | **采纳建议**：放 `ToolRegistry.resolve(...)` 的上下文参数（新增 `ToolContext`，命名对齐 `SandboxContext`），**不进 `ToolDefinition` record** | 两者是不同层次事实：「工具是什么」（注册事实，单一事实源 = `@Tool` 注解）vs「本轮能不能用/怎么说」（运行时事实）；record 加 `Predicate`/`Function` 会破坏值语义与可序列化。`resolve` 仅 **2 个调用点**（`RuntimeTurnExecutor:93`、`AgentRuntimeContextAssembler:164`）且都是**每轮装配时**调用 ⇒「每轮重写 description」时序天然成立。实施时只在 Tool 侧加参、Skill 侧签名不动。**⚠️ 本行「时序天然成立」的技术前提已于 2026-10-09 证伪**（两处调用结果都不进模型可见工具，实际范围含同批改造消费点）；订正过程见 `LOG-20261009-012`，`REF-1.3` 节的 `Status` 行记录了对后续同类改造的预估修正 |
| ③-2 | **REF-5.4 出站客户端** | **采纳建议**：**OkHttp + 自定义 `Dns`** 做 pinning；协议白名单**默认只放 https**，本地开发放行 http 走**显式开关（默认关）** | ① **OkHttp 已在类路径**（`pom.xml:184` 注释：MinIO SDK 的传递依赖）——落地必须在 `pom.xml` **显式声明**；② 「IP 直连 + Host 头」**已排除**（SNI 变 IP ⇒ 证书主机名校验失败，绕过等于关校验）；③ JDK 内置 `HttpClient` **无公开 DNS 钩子** |
| ③-3 | **REF-3 沙箱** | **采纳建议（修正原优先级）**：**整组降级为「条件触发」，不排期**；契约保持现状不再扩展；`G-005` 改「`Planned`（条件触发）」并登记三个触发条件 | 当前**无可隔离的执行对象**（外部 agent 在它自己终端；内部 agent 工具面全是平台 API、无 shell / 文件写；平台全库无脚本引擎 / 表达式求值）。触发条件：① 平台增加碰宿主的工具 / ② 技能包要被执行 / ③ 平台自持浏览器。**此项修正了原「第 3 优先级」排序** |
| ③-4 | **REF-1.2 技能目录的下游一致性** | **采纳建议**：**服务端下发**（复用 REF-1.2c 的技能目录 API）；parity 守卫只留给**不适合下发的词表**（`AGENT_SKILL_OPTIONS` ↔ 后端 `KEYWORD_SKILLS`/`SYNONYMS`）与**事件码**（`REF-6.8`） | 前端常量有 **3 类语义**（`TaskFormDialog:210` 下拉拼接 / `PlanReviewDialog:489` 平台技能判定 / `skillLabelOf` 中文标签，被 `SubTaskDetail:209` 等 3 处调用）。保留常量 ⇒ 新增技能「后端生效、前端看不见」，直接抵消 REF-1.2 收益 |
| ⑤ | **工作详情快照** | **采纳建议**：登记为 **`REF-7`**（§8），差距锚点 `G-020`；可后置，触发条件 = 子任务内部执行深度的审计需求成立 | 与 `REF-6.2`「审计闭合 schema」存在张力 ⇒ 必须双面（原文落 MinIO + 时间线放结构化索引 + `ref`），与 `REF-5.2b` 双视图同模式；审批方倾向复用人工介入通道（不建第二套审批）；新增工具须走既有 `McpMcpServer` 守卫且**默认关闭**（`REF-6.13`） |

> **2026-10-12 裁定（9 项）**：本轮用户逐条拍板的 9 项裁定（RM9 类名 / REF-8 开工 / 主线指针 / LOG-007 A+B 立项 `N=3` / 覆盖面清单 / REF-7 三约束 / 沙箱 / 上线清单 / REF-1.4），**记录本体入 `doc/log/2026-10.md` `LOG-20261012-002`**（遵治理规则 §3.2「`plan/` 冻结新增决策留痕」）；决策登记见《差距表》`D-2026-10-12-2` / `-3` / `-4`。**计划侧影响已就地写入本文件 §8.2、§10.2、§10.3、§10.6**。
