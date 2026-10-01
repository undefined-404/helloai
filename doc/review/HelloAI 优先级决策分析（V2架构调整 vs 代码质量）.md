# HelloAI 优先级决策分析：V2 架构调整 vs 代码质量整改

> **分析日期**：2026-09-30
> **依据**：
> - `doc/review/HelloAI 架构V2进度与质量审计报告（2026-09-30）.md`（含 §10~§16 当日多轮追加，进度/差距/决策的权威源）
> - `doc/review/HelloAI 代码规范与架构偏离专项审计报告（2026-09-30）.md`（同轮专项审计，规范合规 + 耦合结构）
> - `doc/HelloAI 实现差距表.md`（G-001~G-017 矩阵）
> **性质**：**决策分析（只读）** —— 未改动任何业务代码 / 配置 / 迁移。
> **基线**：HEAD `e2c7e8c`；工作区仅一份未跟踪的审计报告。

---

## 0. 结论

> **这不是「二选一」，而是「顺序」问题。**
>
> **建议：先做一个「有界的质量-架构交集批次」（下文称 **Q1**，约 6 项，成本 S~M），再启动 V2 阶段 3（Sandbox 真隔离 + Quality Gate 泛化）。**
> **不做**全面质量重整；**也暂不**直接推阶段 3。

三条理由（按强度排序）：

1. **V2 剩下的主战场本身就是「安全 + 质量」能力，不是功能**。
   阻断项 B2 = Sandbox 真实隔离（五边界）；B4 = Quality Gate 统一决策（Rule + Test + LLM）。在「红线守卫只覆盖 3/10、事务内做对象存储网络 IO、凭据已入库、上帝类仍在长大」的地基上新增隔离与质量门，是**风险叠加而非收益叠加**。

2. **本项目已实证「大重构的代价是验证资产流失」**，所以 Q1 必须**有界**。
   单轨硬切（`be59c3a`）一次动作即造成：`SubTaskExecutionServiceTest` 1346 → 96 行，测试净损 ≈**1200 行**；新增 687 行核心组件 `AgentRuntimeContextAssembler` 一度**零专属单测**（含全链唯一 fail-close 点）。
   → 「先做一次全面质量重整」在本项目的实际形态是**再付一次同样的代价**，不成立。

3. **但纯推进 V2 亦不可取：债务在 V2 推进期间仍在增长，且缺少守卫拦截**。
   - 架构红线自动化**只覆盖 3/10 条**（`arch-baseline.txt` 仅 3 条规则），§6 明列的 `system→task` / `planner→task.mapper` / `shared→业务域` **全在冻结范围之外** → 4 条真实违规静默存活。
   - 上帝类在推进中**继续变大**：`McpToolServiceImpl` 1108 → **1144**（+36 行，来自 `f5ed8f0` / `e2c7e8c`）。

---

## 1. 两条线的现状（事实，非判断）

| 维度 | V2 架构调整 | 代码质量 |
|---|---|---|
| 进度 | P0 ≈**92%** / P1 80% / P2 55% / P3 15%，加权 ≈**73%** | 验证有效性：A ≈35% / B ≈8% / C ≈50% / D ≈7% |
| 阶段 | 阶段 0 ✅；阶段 1 ≈85%；阶段 2 ≈85%；**阶段 3 / 4 / 5 = 0%** | 工程成熟度评级 **D → C+** |
| 已闭合 | B1 单轨硬切 ✅；B5 tokenUsage ✅（均 09-30 当日） | CI 5 道门禁 ✅；B 级 IT 8/8 ✅；JDK 固定 ✅；R1/R2/P-1 修复 ✅ |
| 未动 | **B2 Sandbox 隔离 ❌ / B3 Event Recovery·Fork ❌ / B4 Quality Gate ❌** | 红线守卫 3/10 ❌；上帝类未拆 ❌；前端零行为测试 ❌；无 JaCoCo / 无 ArchUnit ❌ |
| 阻塞/风险 | 阶段 3 两项均 L 成本 | job 6 类直捅 Mapper（§4.3/§22 红线）；4 条反向依赖（§6 红线）；5 处凭据入 Git；5 处调试埋点外发面 |

> **关键**：两条线**都不健康地"空"**，但性质不同 —— V2 的缺口是「**还没建**」，质量的缺口是「**已建错但没被拦住**」。后者会**污染前者**。

---

## 2. 为什么在本项目里「质量」大部分就是「架构」

用户把问题表述为「V2 vs 代码质量」，但按本项目的规范口径，多数质量项的**本质是架构约束**：

| 质量项 | 规范出处 | 是否架构约束 |
|---|---|---|
| `system → task` 反向依赖 | CODE_STYLE §6（依赖方向红线） | **是** —— 与「五层架构收敛」同源 |
| `planner → task.mapper` 跨域直捅 | §7.1 + §7.2（Port 反转） | **是** |
| `shared → agent/task` 业务依赖 | §5.6（shared 不承载业务） | **是** |
| job 直捅业务 Mapper | §4.3 / §22 | **是**（分层） |
| 事务内做网络 IO | §8.3 + §14 | 是（事务边界） |
| §27 日志上下文 / §11.3 Entity DTO / N+1 / Redis key 前缀 | §27 / §11.3 / §18·19 / §20.1 | 否（纯工程卫生） |

→ 前 5 项**同时是质量欠账和架构欠账**；后 4 项纯属工程卫生。**只有前者需要插队。**

---

## 3. 四象限分类（见主对话配图）

| 象限 | 项 | 处置 |
|---|---|---|
| **强阻塞 · 低成本** | 红线守卫 3/10 → §6 全量；4 条真实反向依赖；凭据轮换 + 埋点清理 | **立即做（Q1）** |
| 强阻塞 · 高成本 | 事务内 IO 移出（2 处）；job 6 类去 Mapper；ArchUnit | 排期做（Q1 可含前两项） |
| 弱阻塞 · 低成本 | §27 日志 34 条；§18·19 N+1 7 处；Redis key 前缀 | 随触随改 |
| 弱阻塞 · 高成本 | 上帝类拆分（1547/1310/1144）；Entity DTO 全量；前端 Vitest | **不单独立项** |

---

## 4. Q1 建议批次（6 项，全部 S~M）

| # | 项 | 内容 | 成本 | 为什么现在做 |
|---|---|---|---|---|
| 1 | **安全止损** | 轮换 5 处凭据（含 `credential_vault` 的 AES 密钥默认值 `application.yml:285`、`registration-token`、`minio-secret-key`、`postgres`、`guest`）；Redis / MinIO 端口收白名单；`.env` 轮换 | S | 与 V2 无关，**任何阶段都必须做**；且默认值路径已在本地实测被触发过（"Tag mismatch" 那次即未注入环境变量） |
| 2 | **架构红线守卫扩至 §6 全量** | `check-arch-freeze.sh` 规则表从 3 条扩到 §6 全量（`system→task` / `system→agent` / `task→review` / `planner→task.mapper` / `shared→业务域`），用 **bash 而非 PS1** 实现以进 CI；修 `verify-dependency-direction.ps1:29-30` 的硬编码绝对路径（违反 §39） | S | **元修复**：不先做这条，Q1 修完的债会再次静默累积。现有守卫**本应判 FAILED 却从未跑过** |
| 3 | **4 条真实反向依赖收口** | `system/storage/impl/ArtifactStorageReconcileServiceImpl.java:7-8`（system→task）；`planner/service/impl/PlannerAnalysisServiceImpl.java:13,57,107`（planner→task.mapper，含物理删除）；`shared/doorbell/DoorbellServiceImpl.java:5-6`（shared→agent）；`shared/util/SubTaskDependencyOrder.java` + `SubTaskOutputExtractor.java`（shared→task 实体） | M | 全是 §6 红线，且**全在冻结基线之外**；第 2 项修完即可被守卫拦住不复发 |
| 4 | **job 6 类去 Mapper 直连** | AgentHealthCheckTask / ExecutionCompensationTask / PlanningTimeoutTask / SubTaskPendingOrphanTask / AssignedSubTaskTimeoutTask / ExternalAgentFallbackTask → 改走 core Service | M | §4.3/§22 硬红线；这 6 个恰是**可靠性关键路径**，分层缺失直接抬高事故排查成本 |
| 5 | **对象存储 IO 移出事务** | `ArtifactUploadServiceImpl.upload`（`artifactStorage.store()`）、`AttachmentServiceImpl.register`（远程 `exists()` HEAD） | M | 与 P-1 病灶区（附件链）重叠，可**合并处置**；且阶段 3 若做 Sandbox/存储相关能力会再次触碰此处 |
| 6 | **"假绿陷阱"清扫** | `mockMode` 默认值（已改 ✅）；`skipTests=true` 根 POM 默认（已有 `-DskipTests=false` 约定，建议加 CI 断言）；JaCoCo 覆盖率基线（只降不升） | S | 成本极低，防的是"看起来验证了其实没有" |

> **Q1 的边界**：不重构、不改业务语义、不动状态机、**不碰上帝类内部结构**（只做上述点位的最小改动），保证"**测试与生产比不回退**"。

---

## 5. 明确暂不排期（避免 Q1 膨胀）

- **上帝类拆分**（1547 / 1310 / 1144）：L 成本、无功能收益、且本项目已证明大重构会流失测试资产。→ 改为**「触达即拆」**：只有阶段 3/4 真要改这几个类时，才顺带拆出被改动的那一段。
- **Entity DTO 全量改造**（19~24 端点 / 4 控制器）：面状但非阻塞，且改错风险 > 收益。→ 新增接口遵守，存量随触随改。
- **N+1（7 处）**：`TeamServiceImpl:91-93` 一行 `selectBatchIds` 即可修，其余随触随改。
- **§27 日志上下文（34 条）**：多数为全局/配置级事件，危害有限。
- **§20.1 Redis key 前缀**：**规范自相矛盾**（§20.1 要 `helloai:`，§21.2 官方示例 `review:lock:` 无前缀；代码遵从 §21.2）→ **应先裁定规范，而非改代码**（改 key 涉线上数据迁移口径）。
- **`skills/{role}/SKILL.md` 改名 `onboarding/{role}.md`**：零风险消歧义（"SKILL" 一词当前指两件事，极易误判），可顺带。

---

## 6. 反面论证（诚实举证）

**如果坚持"先全力推 V2 阶段 3"，成立的条件是：**
- 项目目标是「**尽快达到 V2 目标态可演示**」，而非「可持续演进」；
- 且接受「隔离与质量门建在未收口的地基上」的返工概率。

**这是成立的** —— 本项目**自研、无生产数据、无客户数据**，短期"带着债往前跑"不会伤害任何客户；且阶段 1/2 刚双 85%，势头好。

**但即便选这条路，也至少要做 Q1 的第 1~3 项**（凭据轮换 + 守卫扩全量 + 4 条反向依赖）。理由：第 2 项是**唯一能阻止债务继续静默累积**的动作；第 1 项是安全项；第 3 项修完即被第 2 项永久锁死，边际成本最低。

**反之，如果选"先全面质量重整"，不成立**：成本 L+，收益不阻塞任何 V2 目标，且重演测试资产流失。

---

## 7. 与既有决策的关系

| 既有决策 | 与本建议的关系 |
|---|---|
| `D-2026-09-29-2`（验证与工程化基础设施优先于功能推进） | **方向一致** —— Q1 是该决策的延续，只是把重心从「验证基建」移到「架构约束落地」 |
| `D-2026-09-30-3`（不补回退手段） | 不冲突 —— Q1 不含回退开关；兜底策略 / 同层恢复不在 Q1 范围 |
| `D-2026-09-29-1` 复审条件②（P1 剩余项连续 2 迭代未启动） | **条件已达成**（自 09-14 起约 2 周零推进）→ 09-30 报告 §7-5 已建议「把 Sandbox 隔离 / Quality Gate 排入下一批次」，**与本建议的"Q1 之后启动阶段 3"衔接** |

---

## 8. 待用户拍板（唯一分支点）

> **Q1 的规模**，取决于目标优先级：

| 选项 | Q1 规模 | 适用目标 |
|---|---|---|
| **A（推荐）** | **6 项全做**（第 1~6 项），随后启动阶段 3 | 「可持续演进」——先把地基收口，再建隔离与质量门 |
| **B** | **只做第 1~3 项**（安全 + 守卫 + 反向依赖），立刻启动阶段 3，其余随触随改 | 「尽快目标态可演示」——用最小代价锁住债务不再增长 |
| C | 不做 Q1，直接阶段 3 | 不建议 —— 债务将在新增 Sandbox / Quality Gate 后复利 |

**若采纳本建议，建议登记为 `D-2026-09-30-4`**（写入 `doc/HelloAI 实现差距表.md` §0 决策登记 + 09-30 审计报告 §10），并同步把 09-30 报告 §1.3 / §5.2「架构红线：冻结守卫替代生效」的口径**订正为「仅替代 3/10 条」**。

---

## 9. 局限

- 本分析**未运行构建 / 单测**（所有判定均可静态成立，无需联网与 DB）。
- 未评估业务语义质量（LLM 提示词效果、报告可读性），故 Q1 未纳入该类项。
- 「成本 S~M」为相对量级（S ≈ 单点改动 + 定向测试；M ≈ 一处跨域重构 + 回归），**非工时估算**。

---

## 10. 附：外部独立评审的交叉验证（2026-09-30）

> 收到一份基于「Gitee 仓库 + 本批审计报告 + doc 目标架构/差距表/重构计划」的外部独立评审。**逐条核验结果如下**（所有判定均以本轮实测为准）。

### 10.1 可采信（与硬证据一致）

| 外部结论 | 核验 |
|---|---|
| 「Gitee `master` 已到 `e2c7e8c`」 | ✅ 实测 `git ls-remote origin master` = `e2c7e8c800692e2b51586b30028599950a4599cf`，本地与远端一致 |
| 「V2 没有跑偏」 | ✅ 与本报告 §0 及两份审计一致 |
| 「Router/Runtime 单轨是最大成果，**不要再大规模动 Router**」 | ✅ 与「不做全面重构」一致（本报告 §0 理由 2） |
| 「**架构门禁没进 CI 是当前最该补的工程问题**」 | ✅ **与本报告 §4 第 2 项同源**，是独立得出的同一结论 |
| 「不要造第二套 Workflow Engine / Scheduler」 | ✅ `目标架构.md:330-340` §11 非目标原文：`❌ 第二套 Scheduler` / `❌ 第二套 Workflow Runtime` |
| 「255 行 `handleReport` 是离群点」 | ✅ 本报告 §3：188 个事务方法中位数 16 行，仅此 1 个 >150 行 |
| 「两个 SKILL 概念同名双义，建议改 `onboarding/`」 | ✅ 与本报告 §5 末条一致 |
| 「先不要拍板新增 `SYNTHESIZER`，先回答『最终报告是什么』」 | ✅ 属好的架构提问 —— 但需先澄清其层级归属（见下） |
| 「`doc/README.md` 已收敛为单一主线」「目标架构声明为 TARGET 非已落地」 | ✅ `README.md:5` 与 `目标架构.md:3` 原文均如此 |

### 10.2 需打折 / 纠正

| # | 外部表述 | 实测 |
|---|---|---|
| 1 | 把 **`Synthesis`** 画进架构链（「Review/Quality Gate → … → Synthesis」） | ❌ `目标架构.md` 全文 **0 命中** `synthesis`/`synthesizer`/`整合`。`SYNTHESIZER` 出自 `design/HelloAI_契约层能力注入与最终报告整合改造方案.md` **A1**（建议项），差距表 `N2` 已登记「依赖 Role Layer 调整，**后置**」。→ **把"设计建议"当成"目标架构已要求"** |
| 2 | 层链画法不完整 | ⚠️ `目标架构 §2` 的尾段 `Agent Event Stream → Audit / Metrics / Replay → Recovery` 被整段略去；且 §2 中 **Governance 是与 Planning 并列的分支（汇入 Workflow Engine）**，非末端节点，Quality Gate 挂于 Governance 之下 |
| 3 | 「门禁只有几条冻结规则、没真正进 CI」 | 🟡 方向对但**低估**：那条能抓 `system→task` 的规则**本应判 FAILED**（bash 复现其 10 条规则 → `[FAIL] system must not depend on [task], hits=1`），且脚本 `verify-dependency-direction.ps1:29-30` **硬编码 `e:\yhzx\1027\helloai\...`（违反 §39）** → 不是"接进 CI"就能用，**必须 bash 重写** |
| 4 | 「Skill 已经不是简单 `role → SKILL.md` 了」 | 🟡 **说过头**：`skills/{role}/SKILL.md` **仍在生效**（`PromptTemplateServiceImpl.java:162/191/204` + `AgentController.java:270`；目录 `skills/{executor,planner,reviewer,patrol}` 实在）。准确表述是「**两套并存**」，非「已取代」 |
| 5 | 优先级清单 | 🟡 **缺"安全止损"且排序不当**：其第 1 项是"不要大改 Router"（一个*不做*），凭据/埋点仅列为"应优先处理"。实测 `application.yml` 有 **5 处凭据入库**，其中 `:285` 是**凭据保险库 `credential_vault` 的 AES 密钥默认值**，且本地实测走过该默认值（「Tag mismatch」那次即未注入环境变量）→ **唯一与 V2 无关、任何阶段都必须立即执行的项**，不应排在反向依赖之后 |

### 10.3 漏项（本报告 §4 有实证、其全文未提）

`job` 6 类直捅业务 Mapper（§4.3/§22 硬红线，且全在可靠性关键路径）· 事务内做对象存储网络 IO（2 处，与 P-1 病灶区重叠）· §11.3 API 暴露 Entity 19~24 端点（含 `@RequestBody Rule` 双向违规）· N+1 7 处 · §27 无上下文日志 34 条 · 上帝类 1547 / 1310 / **1144**（`McpToolServiceImpl` 在 09-30 之后仍在增长）。

> **漏因**：该评审自述「下一轮不会再全面扫代码」，其方法为**文档 + Gitee 浏览级**判断，无行级取证。
> **结论：适合当「方向确认」，不适合当「待办来源」。**

### 10.4 净结论

外部评审与本报告在**方向层高度一致**（未跑偏、不动 Router、**门禁进 CI 为最高优先**），构成一次有价值的**独立交叉验证**；但其**架构图有实际错误**（Synthesis 层级、层链缺尾段）、**门禁严重性被低估**、**优先级清单缺安全项**、并**漏掉 6 类行级实证**。
→ **采纳其方向判断，行级待办一律以两份审计报告（含行号证据）为准。**

---

## 11. 附：Doc 治理提案交叉验证（第二轮外部评审，2026-09-30）

> 收到第二份外部评审，主题为「Doc V2.1 收口整理」（建议把 `doc/` 重构为 `01-architecture/` … `07-history/` 编号目录，并提出 7 条原则）。**核验结论见下**，全部基于实测。

### 11.1 一句话

**其诊断成立，但处方已是旧方 —— 同一个治理动作在 2026-09-07 已经做过一次，且结构已建好；真正的债务在别处（引用完整性）。**

### 11.2 其提案中「已经存在」的部分（核验为已实现）

| 提案 | 实测现状 | 判定 |
|---|---|---|
| 建立「当前事实 → 目标架构 → 差距 → 计划 → 日志」单一主线 | `doc/README.md:5` 原文即此，含**权威优先级**（`代码/可验证事实 > 项目基线 > 目标架构 > 实现差距 > 实施计划 > 历史归档`） | ✅ **已有** |
| 把历史方案归档 | `doc/archive/` **已存在，42 个文件**，且已分 `implemented/ legacy/ logs/ reference/` 四类 | ✅ **已有** |
| 新建 `HelloAI_文档治理审计.md` | **该文件已存在**（62 行，2026-09-07） | ✅ **已有** |
| 需要一次文档治理 | 09-07 该文件原文：「主要问题不是缺少设计，而是**当前事实、已完成方案、未来目标、外部参考、历史过程混在一起**…不是简单润色，而是一次**文档架构重构**」 | ⚠️ **与本次提案结论逐字重合** |
| 迁移型文档移入 history | `archive/implemented/HelloAI_Phase0_C3_双轨切换预研.md` 正是此类，**已在归档区** | ✅ **已完成** |
| README 做成「文档地图」 | `doc/README.md` 已含 权威文档表 / design 主线清单 / archive 禁用声明 / 读取顺序 / 脚本索引 | ✅ **~80% 已有** |

> **注**：其建议的 `log/HelloAI_迭代执行记录.md` 是**旧文件名**；当前实为 `doc/log/2026-09.md`（按月）+ `doc/log/HelloAI 架构变更记录.md`。

### 11.3 值得采纳的（真增量，5 条）

| # | 建议 | 为什么值得做 | 成本 |
|---|---|---|---|
| 1 | **`目标架构.md` 增逐层 `Implementation Status`**（Implemented / Partially / Planned / Non-goal） | 现状仅一句全局 `状态：TARGET ARCHITECTURE`（`:3-5`），**无逐层落地标记** → 是「目标架构写得好但代码没实现」误解的根源。**数据已具备**（见本报告上轮「目标架构逐层收敛状态」） | S |
| 2 | **`design/` 去历史化** | 实测成立：11 个文件里 **3 个不是设计** —— `HelloAI_契约层能力注入与最终报告整合改造方案.md`(764 行，**改造方案**) / `HelloAI_附件存储一致性排查报告.md`(235，**排查报告**) / `HelloAI_外部Agent_v3反馈代码级复核.md`(137，**复核报告**) | S |
| 3 | **设计文档加 `Status / Scope` 头**（`ACTIVE` + 指向历史区） | 成本极低；可防「历史方案被当当前设计」 | S |
| 4 | **差距表作为唯一差距事实源，其他文档不重复维护百分比** | 正确原则，且与 09-30 报告 §11.5「追加式登记不回改正文行」是**同一病灶** | S |
| 5 | **`skills/{role}/SKILL.md` → `onboarding/{role}.md`** | 与本报告 §5 末条一致（同名双义） | S |

> 另：其 §13 的**自我收回**（「不要为了目录漂亮拆成几十个目录，推荐低成本版」）是**正确判断，应采纳**；其 §1 的 `01-~07-` 编号方案**应否决**（见 11.4）。

### 11.4 不采纳的（3 条硬理由）

1. **编号目录（`01-architecture/` … `07-history/`）→ 否决。**
   ① 与已有 `archive/` **同义重命名**，属重复劳动；② 会**打断全部现有路径引用**（`doc/design/` 一处即被 **72 个文件**引用，`HelloAI_CODE_STYLE` 被 **41 个**引用）；③ 其本人也建议不做。

2. **其文件清单是「幽灵文件」→ 不可直接照做。**
   实测 `doc/HelloAI_迭代执行记录.md` **根本不存在**，而**有 9 个脚本仍在引用它**；`doc/design/HelloAI_Phase0_C3_双轨切换预研.md` 早已移入 `archive/implemented/`（仍有 12 个脚本引用）。→ 其清单来自**旧认知**，非仓库现状。

3. **遗漏 `manual/` 与资源目录 → 结构性缺口。**
   `doc/manual/`（**20 个文件**：外部执行者职责手册 + 集成指南）是**对外契约文档**，被 `G-014 T08b` 与 `skills/executor/SKILL.md` 引用，**不属于可归档对象**；另一个外部评审的目录方案里完全没有它。此外 `doc/diagrams/ images/ helloai_avatar/`（**27 个非 md 资源**）亦未涉及。

### 11.5 真正的治理债务：**引用完整性**（其完全未提，是本次最实质发现）

按**入库文件**实测（已剔除 URL 编码与本地工具目录的假阳性）：

- **44 个被引用的 doc 路径中，27 个已失效（61%）**，失效引用合计 **126 处**。
- 来源分布：**脚本 69 处 / 文档间 30 处 / 生产代码 27 处**。
- **生产代码 27 处最值得警惕**：Java javadoc（`DoorbellRegistry.java:16` 等 **7 处**指向 `doc/HelloAI_门铃通知通道设计.md`，实际已移入 `archive/legacy/`）、**Flyway 迁移注释**（V62~V73 共 **10+ 处**指向已归档的 `Phase0/Phase1/Phase2` 方案）、`application.yml`、`prompts/subtask-review.md`。
- 分布 Top6 见上图。

> **判读**：09-07 那次治理**搬完目录就断了引用，并且一直没有被发现** —— 这说明「搬目录」这个动作在本项目历史上**已经失败过一次**。所以 Doc V2.1 的第一件事应是**修引用**，而不是**再搬一次**。

**一个必须注意的技术约束**：`V62~V73` 等**已 apply 的 Flyway 迁移文件不能改注释** —— 改动会变更 checksum，导致 `flyway validate` 失败。故引用修复须分类型处理：
- 迁移文件 / 历史代码 javadoc → **原地加「已归档至 …」重定向桩**，或维持原样并登记为已知断链；
- `scripts/`（69 处）与活文档（30 处）→ **可直接改路径**，并建议加一个 CI 检查（doc 路径存在性）防复发。

### 11.6 其建议之外、实测发现的根目录杂物（含**对本节自身两处误判的订正**）

> **订正（2026-09-30 实际治理时复核）**
>
> - ⚠️ **原判「`doc/HelloAI_项目介绍.md` 含 9 处『双轨』旧口径」不成立**：该 9 处全部是 **Reviewer 双轨纪律制 / 双轨审核 / 双轨依赖注入**，是**当前有效概念**，与「双轨执行链（Legacy / Runtime）」仅是**同名不同义**。`README.md` 同此（「双轨」全部指 Reviewer 审核）。
>   **方法论教训**：同名词多义时（双轨 = 审查纪律 / 执行链），必须看上下文再断言「旧口径」，否则会把正确内容当缺陷。
> - ⚠️ **原判「82 行 / 11 文件，含 README 级入口」为混合统计**：其中 README / 项目介绍的命中属上述同名误判；真正的问题集中在 `项目基线文档.md` 与 `项目介绍.md` 的**执行链叙事段**。

**实测成立并已处置：**

- ✅ `doc/HelloAI 项目基线文档.md`（职责是「我们现在是什么」）**确含旧链口径**（`LegacyExecutorAdapter` / `RuntimeAgentRuntimeRouter` / `TurnLlmCaller` / `runtime-enabled`，§7 组件清单与「当前含义」段）→ **已订正为单轨实况**（旧链与灰度开关 2026-09-30 已删）；
- ✅ `doc/HelloAI_项目介绍.md` 的执行链叙事段（§3.5 改造主线 / §3.8.2 落地进度 / §5.1 目标态树 / §5.2 迁移锚点表 / §5.3 迁移策略 / §6 进度速览 / §7 一页总结）**确述旧链与灰度开关** → **已加单轨硬切订正**；
- ✅ `doc/HelloAI 重构实施计划.md` §3 现状基线与 P0-B 各段、`doc/design/Agent_Runtime.md`「迁移模型」段 → 已加**历史段订正横幅**（保留过程记录，但显式标注已被单轨硬切取代）；
- ✅ `doc/HelloAI_CODE_STYLE.md` §47.2 集成测试示例里的 `LegacyExecutorAdapter`（已删类）→ 换为 `RuntimeTurnExecutor / ChatModel`；
- ✅ `doc/HelloAI_登录页原创AI虚拟人物生成提示词.md`（231 行，2026-07-23，不属架构文档体系）→ 移至 `doc/helloai_avatar/`（与生成产物同目录）；
- ✅ `doc/HelloAI 文档治理审计.md`（2026-09-07 治理记录）→ 移至 `doc/review/` 并规范命名为「（2026-09-07）」；
- ⏳ **未修（登记）**：`doc/diagrams/package-architecture-mapping.html` 命中旧链口径——图类产物，需重绘，不在本次文本治理范围。

### 11.7 净结论

**采纳其 5 条真增量（11.3），否决其目录方案（11.4），并把优先级改为：先修引用完整性，再做状态标注，最后才谈小规模归位。**

### 11.8 执行结果（2026-09-30 已落地，与本备忘录口径对齐）

| # | 项 | 结果 |
|---|---|---|
| 1 | `目标架构.md` 逐层 `Implementation Status` | ✅ 新增 §0 状态表（13 行，`Implemented\|Partial\|Planned\|Non-goal` 四值 + 差距锚点）+ 各层内联 `Status` 行 14 处；本文件自此只声明边界与状态 |
| 2 | `design/` 去历史化 | ✅ **4 份非设计文档出列**：`契约层能力注入与最终报告整合改造方案`（改造方案）→ `archive/implemented/`；`附件存储一致性排查报告`、`外部Agent_v3反馈代码级复核`、`文档治理审计`（3 份报告）→ `review/`；`登录页…提示词` → `helloai_avatar/` |
| 3 | 设计文档加 `Status / Scope` 头 | ✅ 7 份全部补齐（含 H1 后的状态行 + Scope 边界与不含项 + 差距锚点 + 状态事实源指向） |
| 4 | 差距表作唯一差距事实源 | ✅ 写入 `目标架构.md` §0 维护规则 1 + `README.md` §2 准入规则 3；`design/` 不再复述进度数字 |
| 5 | 引用完整性修复 | ✅ **137 处失效引用已修**（`scripts/` 脚本头 Ref、生产代码 javadoc、doc 三处来源）；Flyway V1/V62~V73 共 12 处因 **checksum 约束**登记不改；`archive/` `log/` 历史域 147 处**有意保留**（现场记录） |
| 6 | onboarding 迁移 | ✅ `skills/{role}/SKILL.md` → `resources/onboarding/{role}/guide.md`；**交付 ZIP 内仍为 `SKILL.md`**（第三方 IDE 约定）；`PromptTemplateServiceImplTest` **2/2 通过**；`helloai-core,helloai-api` 编译 `EXIT=0` |
| 7 | 旧链（双轨）口径清理 | ✅ `项目基线文档.md` / `项目介绍.md` / `重构实施计划.md` / `CODE_STYLE.md` / `design/Agent_Runtime.md` 已订正或加历史横幅（详见 §11.6） |
| 8 | 根目录与仓库卫生 | ✅ `skills-lock.json` untrack + 忽略；`/skills/` **根锚定**忽略（避免误伤运行时能力包）；`scripts/powershell/logs/` 加入忽略 |

**仍未做（顺延下一步）**：① `doc/diagrams/package-architecture-mapping.html` 旧口径重绘；② **CI 加「doc 路径存在性」检查**（防引用再次腐烂——这是本次治理暴露的最大机制缺口）；③ `R1` 短路判据改主判据（见 09-30 代码审计 §15）；④ `R2` 附件配额与 `DEP_CONTENT_MAX_CHARS` 联动。

### 11.9 Q1 待办项复核（2026-09-30 夜，Doc 治理后）

**方法**：对 §4 Q1 六项与 09-30 代码审计 §8 的行动项**逐条 grep 复验**（非沿用旧结论）。

| 项 | 复验结果（命令实证） |
|---|---|
| ① 凭据入 Git | 🔴 **仍成立 6 处**：`application.yml:42`（`password: postgres`）/ `:60`（`guest`）/ `:106`（`registration-token: "helloai-reg-2024"`）/ `:285`（AES 密钥**带可用默认值**）/ `:306`（`minioadmin123`） |
| ② 调试埋点 | 🔴 **仍成立**：Java 内 `debug-point` / `redispatch-stuck-blocked` **20 处**；根目录 `debug-blocked-redispatch-stuck.md`、`debug-redispatch-stuck-blocked.md` 仍在 |
| ③ 门禁规则集 | 🔴 **仍成立**：`scripts/ci/arch-baseline.txt` 仍**仅 3 条**（`agent->task=68` / `planner->agent=34` / `task->agent=45`），`ci-gate.sh:130` 只调它 |
| ④ 反向依赖 | 🔴 **4 条全部仍破**：`system→task`（`ArtifactStorageReconcileServiceImpl:7-8` 引 `task.entity.Attachment` + `task.service.AttachmentService`）；`planner→task.mapper`（`PlannerAnalysisServiceImpl:13` 引 `SubTaskMapper`）；`shared→agent`（`DoorbellServiceImpl:5-6` 引 `agent.AgentDutyLeaseService` / `HeartbeatService`） |
| ⑤ job 去 Mapper | 🔴 **仍成立 6 类**，且**恰为审计报告点名的那 6 个**：`AgentHealthCheckTask` / `AssignedSubTaskTimeoutTask` / `ExecutionCompensationTask` / `ExternalAgentFallbackTask` / `PlanningTimeoutTask` / `SubTaskPendingOrphanTask` |
| ⑥ 假绿陷阱 | 🟡 `mockMode` 已改 ✅；根 POM `skipTests=true` 默认仍在（靠 `-DskipTests=false` 约定）；JaCoCo 未引入 |

**本次 Doc 治理已顺带关闭 2 项**（09-30 代码审计 §8）：
- ✅ #11 `skills/{role}/SKILL.md` → `onboarding/{role}/guide.md`（"SKILL" 一词消歧义，§6.3）；
- ✅ #14 清理空目录 `skills/patrol/`。

> **结论：Q1 六项与 §8 前 6 项的效力今天未被削弱，优先级判断无需修订——排序仍为「安全止损 → 门禁扩全量 → 反向依赖/job 收口」。**

## 12. 下一步排序（2026-09-30 夜定稿，供开工直接引用）

**第一优先 = 安全止损**（凭据轮换 + 埋点清理）。**理由**：唯一「不做则持续实际暴露」的项；成本 S；与架构路线无关——**Q1 做不做、阶段 3 推不推，它都必须做**。

**第二优先 = 门禁扩至 §6 全量**（**元修复**）。**理由**：唯一能阻止债务**继续静默累积**的动作。现状是「写入代码时没人拦、事后靠人肉审计」——09-30 报告已证明该模式失败过一次（`system→task` 破了且无人知）。

**第三优先 = 4 条反向依赖 + job 6 类收口**。**理由**：第二项做完后，这三步的成果会被**永久锁死**；先修后修成本相同，但**早修早被拦**，边际成本最低。

**最后**才启动阶段 3（Sandbox 真隔离 + Quality Gate 泛化）——不在被污染的地基上建隔离与质量门。

**唯一仍需用户拍板的**：**Q1 规模**（§8 的 A / B 分支），取决于目标是「可持续演进」还是「尽快目标态可演示」。

---

## 13. Q1 批次执行结果（2026-09-30 夜 · 用户拍板「只做 ①+②」）

**用户决策**：在 §12 的「唯一分支点」上选择 **只做 ①（安全止损）+ ②（门禁扩全量）**；③（4 条反向依赖 + job 6 类收口）与阶段 3 暂不启动。

### 13.1 ① 安全止损 —— 已完成

| 子项 | 处置 | 证据 |
|---|---|---|
| 凭据默认值去化 | `application.yml` **6 处凭据改为无默认占位符**（`${VAR}`，缺失即启动失败，§41 例外分支）：`spring.datasource.password`、`spring.rabbitmq.password`、`helloai.agent.registration-token`、`helloai.security.credential.aes-key-base64`、`helloai.storage.minio-access-key`、`minio-secret-key` | 各处已带内联注释说明为何不设默认 |
| AES 密钥轮换 | 原**可用**默认密钥 `cWXZTi5+…KFM=` 删除（该值曾随 Git 入库 = 公开密钥，任何人可解密用其加密的凭据） | `application.yml` §security.credential |
| registration-token 轮换 | 原 `"helloai-reg-2024"` 删除；并**同步移除 `AgentConfigProperties.java:14` 的代码内兜底默认值**（§42「Token 写入代码」） | 字段改为无初始值 + javadoc 指向配置项 |
| 本地 / 测试回退 | `application-local.yml`（默认 profile）与 `application-it.yml` 各补**非机密**回退值 → 本机开发与 B 级集成测试**零环境变量可跑**；`dev`/prod 不补 → **fail-fast** | 用新生成的随机密钥各一（local / it 不同） |
| 部署入口文档 | `deploy/app/.env.example` 增 `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `HELLOAI_AGENT_REGISTRATION_TOKEN` | — |
| 调试埋点清理 | 5 个生产文件共 **5 个 `#region/#endregion debug-point` 区块 + 全部 `dbg(...)` 调用点**清除；随之移除的 import（`java.net.http.*` / `java.nio.file.*` / `ObjectMapper` / `OffsetDateTime`）与专用 helper（`dbgUrl` / `loadDbgUrl` / `dbgMap`，含 `SubTaskAutoExecutionDispatcher` 只为埋点而生的 `safeMap`）一并删除 | 残留扫描：main 源码 `debug-point` / `DBG_` / `dbgUrl` / `DEBUG_SERVER_URL` **0 命中** |
| 调试笔记出库 | 根目录 `debug-blocked-redispatch-stuck.md`、`debug-redispatch-stuck-blocked.md` 已 `git rm`（内容备份至 `.workbuddy/notes/`，该目录已忽略） | — |

> **保留诊断价值**：埋点原本外发的关键信息（如「执行命令派发失败」根因类）在既有 `log.*` 中**已存在**，删埋点未丢失可观测性；`AgentRuntimeContextAssembler.safeMap` 因被**非调试**业务（执行会话快照 / timeline 事件）复用而**保留**。

### 13.2 ② 门禁扩全量 —— 已完成

`scripts/ci/check-arch-freeze.sh` 规则表 **3 条 → 20 条**（bash 跨平台实现，已由 `ci-gate.sh` 门禁 4 调用）：

| 组 | 条数 | 内容 |
|---|---|---|
| 组 1 · §6 反向依赖 | 14 | `task->planner` / `task->review` / `agent->task` / `agent->planner` / `agent->review` / `system->planner` / `system->review` / `system->task` / `system->agent` / `shared->planner` / `shared->review` / `shared->task` / `shared->agent` / `shared->system`（其中 4 条为**存量标债**：`agent->task=68`、`system->task=2`、`shared->task=2`、`shared->agent=3`） |
| 组 2 · §7.1 跨域直捅 Mapper | 4 | `planner->task.mapper`（**存量标债=1**）/ `review->task.mapper` / `agent->task.mapper` / `task->agent.mapper` |
| 组 3 · 2026-09-29 基线既有 | 2 | `planner->agent=34` / `task->agent=45`（沿用勿删） |

`arch-baseline.txt` 经 `--update-baseline` 刷新为 20 行；**0 计数规则即回归护栏**（任何新增即刻失败）。

**正 / 负向测试**：正常态 20 条全持平 `EXIT=0`；人为把 `agent->task` 基线由 68 下调为 67 → 输出 `❌ 超出 +1` 且 `EXIT=1`（证明确实拦截）。

`verify-dependency-direction.ps1`（§7 根因缺口 2/3）：
- 硬编码绝对路径 → **按脚本位置解析仓库根**（`$PSScriptRoot` 上溯两级，修 §39 违规）；新增布局自检（缺 `core` 目录即 `exit 2`，防静默通过）；
- `system->task` 由 `[FAIL]` 改为 `[DEBT]`（该守卫按**文件数**计，上限 1，与 bash 的**行数**口径 `=2` 一致对齐），其余规则仍严格断言 0；
- `-h` 帮助改用 `sed -n '2,/^set -uo/p'`，**免维护行号**。
- 实测：从 `E:\` 运行仍 `ALL PASSED / EXIT=0`（路径无关性已验证）。

### 13.3 验证记录

| 项 | 命令 | 结果 |
|---|---|---|
| 编译 | `mvn -o -DskipTests compile` | **EXIT=0** |
| 单测 | `mvn -o -DskipTests=false test` | **1141 用例 / 0 失败 / 0 错误 / 0 跳过**，7 模块 `BUILD SUCCESS`（耗时 2:07） |
| 架构冻结 | `bash scripts/ci/check-arch-freeze.sh` | 20 条全持平 **EXIT=0** |
| 依赖方向（PS1） | `verify-dependency-direction.ps1` | **ALL PASSED / EXIT=0** |
| 残留扫描 | `grep -rn "debug-point\|DBG_\|dbgUrl\|DEBUG_SERVER_URL" helloai-*/src/main` | **0 命中** |
| 编码卫生 | BOM / 混合行尾检查 | 无 BOM 新增、无混合行尾 |

### 13.4 本次暴露的**新遗留**（必须登记）

1. **【阻塞部署·高】`docker-compose.server.yml` 未同步**：本轮对该文件的读取触发敏感内容审批超时，**未改动**。生产 app 走 `dev` profile，而 `application-dev.yml` 未覆盖 `spring.rabbitmq.password` 与 `helloai.agent.registration-token` → 二者在服务器上将**启动即失败**。**部署前必须在 compose 的 app 服务补** `SPRING_RABBITMQ_PASSWORD` 与 `HELLOAI_AGENT_REGISTRATION_TOKEN`（建议同时把硬编码的 `MINIO_ACCESS_KEY/SECRET_KEY: minioadmin/minioadmin123` 改为从 `deploy/app/.env` 注入）。
   > **【2026-10-01 部分清偿】** `spring.rabbitmq.password` 已由 `application-dev.yml` 补齐（skip-worktree 本地文件，默认 `helloai` 账号 + `/helloai` vhost + 密码默认值，可被 `RABBITMQ_PASSWORD` 覆盖）；服务器 app 重打包 jar 替换后即生效，compose 无需复制 yml。`HELLOAI_AGENT_REGISTRATION_TOKEN` 与 MINIO 凭据注入仍未处理（部署阶段仍须补）。详见 `doc/log/2026-10.md` 2026-10-01 条目。
   > **【2026-10-01 复核订正】** 实测 `git ls-files -v docker-compose.server.yml` → **`H`（已被 git 跟踪，非 skip-worktree）**，最近一次提交 `5693b62`；`git check-ignore` 未命中忽略规则。故「本机专用、不会提交」与仓库实况不符——它**已在库**。连带影响：其内写死的 `MINIO_ACCESS_KEY/SECRET_KEY: minioadmin/minioadmin123` 也在库（低危：与 `application-local.yml` / `application-it.yml` 同源本地默认，非生产凭据）。**若确要「不入库」**：`git rm --cached docker-compose.server.yml` + 写入 `.gitignore`（注意历史提交 `5693b62` 仍留有该值）。
2. **【中】`application-dev.yml` 仍带凭据默认值**：该文件被 `git update-index --skip-worktree` 标记为本地专属且含真实 IP，直接改工作区再 commit 会**泄露真实地址**。建议后续用 `git hash-object -w` + `git update-index --cacheinfo` **只改索引、不动工作区**的方式单独去化。
   > **【2026-10-01 复核】** rabbitmq 三项（username/password/virtual-host）已由工作区版本补齐并验证（见 `doc/log/2026-10.md`）。**仍缺 `helloai.agent.registration-token`**——base 已是无默认占位符，建议在 dev.yml 补 `${HELLOAI_AGENT_REGISTRATION_TOKEN:...}`（与既有「本地私有默认值」口径一致）。另注：`.idea/workspace.xml` 中仅见 `HELLOAI_CREDENTIAL_AES_KEY_BASE64`，故「dev 可启动」的 token 来源需确认（可能实际走默认 `local` profile，或 IDEA 运行资源未重建——`target/classes/application.yml` 已确认是新占位符）。
3. **【低】`scripts/powershell/logs/mcp-jar/BOOT-INF/classes/application.yml`** 内含**真实 AES 密钥与旧 registration-token**（该路径已在忽略内、未跟踪）；建议直接删除整个 `logs/` 目录。

### 13.5 复跑记录

`mvn -o -DskipTests=false test`（JAVA_HOME=ms-17.0.20.1，离线）——Reactor 7/7 `SUCCESS`，`Tests run: 1141, Failures: 0, Errors: 0, Skipped: 0`（`helloai-*/target/surefire-reports/*.xml` 汇总，176 个报告文件）。其中与本次改动直接相关的用例：`SubTaskAutoExecutionDispatcherTest`(2) / `ApiKeyAgentExecutorTest`(1) / `PlatformAgentExecutionServiceTest`(3) / `CredentialVaultBindingServiceTest` 与 `AgentRuntimeContextAssemblerTest`（该类为 mock 驱动，本次仅删埋点未改行为）均通过。

---

## 14. Q1-③ 开工记录：反向依赖收口（2026-10-01）

按 §12「第三优先」开工，**4 条反向依赖的第 1 条已收口**（其余 3 条 + job 6 类未开工）：

| 反向依赖 | 处置 | 结果 |
|---|---|---|
| `planner → task.mapper` | `physicalDeleteByTaskId` 原由 planner 域**直捅 `SubTaskMapper`**（§7.1 跨域直捅 + 跨域物理删除双重风险）→ **收口为 `SubTaskService.physicalDeleteByTaskId(Long)`**（物理删除语义的解释权归 task 域）；`PlannerAnalysisServiceImpl` 删除 `SubTaskMapper` 字段与 import、改调 service；`PlannerAnalysisServiceTest` 断言点由 mapper 改为 service | `planner->task.mapper` **1 → 0**；基线 `--update-baseline` 锁定为 **0**（复跑 EXIT=0）；`PlannerAnalysisServiceTest` **13/13**、相关 **58 用例 0 失败** |

**收口模式（余下 3 条照此办理）**：跨域访问**只能经对方域 Service 契约**，禁止直捅 Mapper 或内部实现；收口后**立即刷新基线**把改善锁死（否则下次漂移无参照）。

**剩余待做（均为 M，建议一条一提交、一验证）**：

| 项 | 位置 | 建议做法 |
|---|---|---|
| `system → task` | `system/storage/impl/ArtifactStorageReconcileServiceImpl.java:7-8` | ✅ **已完成（§15）**：端口反转——`system.port.ArtifactReferencePort`（消费方定义）+ `task` 域 `ArtifactReferencePortAdapter` 实现 |
| `shared → agent` | `shared/doorbell/DoorbellServiceImpl.java:5-6`、`shared/event/ExecutionCommandCreatedEvent.java:3` | doorbell 实现依赖 agent 两个 Service：宜移出 `shared`（归 agent 域）或经 `shared` 内 Port 反转；事件载体避免直接持有 agent 域对象 |
| `shared → task` | `shared/util/SubTaskDependencyOrder.java:3`、`SubTaskOutputExtractor.java:3` | 二者是针对 `SubTask` 的工具类，宜移入 `task/util`（`shared` 必须保持叶子域） |
| job 6 类 | `AgentHealthCheckTask` / `AssignedSubTaskTimeoutTask` / `ExecutionCompensationTask` / `ExternalAgentFallbackTask` / `PlanningTimeoutTask` / `SubTaskPendingOrphanTask` | 去 Mapper 直连，改调 core 域 Service（审计 §8 #4/#5/#6） |


## 15. Q1-③ 第 2 条收口：`system → task` 端口反转（2026-10-01）

### 15.1 事实与病灶

`system → task` 唯一命中为 `system/storage/impl/ArtifactStorageReconcileServiceImpl`（import 2 行 / 1 文件，即基线登记值 `2`）：

```java
import com.helloai.core.task.entity.Attachment;        // 第 7 行
import com.helloai.core.task.service.AttachmentService; // 第 8 行
```

病灶：**system 层的存储对账巡检反向依赖 task 域**（实体 + 服务双泄漏）。实际用量极小——只调 `AttachmentService.listAllIncludingDeleted()`，且只读 `storageUrl` / `fileSize` 两个字段（`AttachmentService` 该方法 javadoc 本就写明「仅供对账巡检使用」）。

### 15.2 方案（与 §14 原建议的差异说明）

§14 原建议「抽象为 `shared` 下 Port」。**实际未采用**，改按**本项目既有端口反转约定**「消费方定义端口、提供方实现」——四处先例（含本节新增）：

| 先例 | 端口位置 | 实现位置 | 消费方 / 提供方 | 依赖方向 |
|---|---|---|---|---|
| `ReviewPort`（§6.146） | `task.port` | `review.service.impl.ReviewPortAdapter` | task / review | `review → task` ✅ 顺向（review 高于 task） |
| `TaskDispatchPort`（§6.138） | `task.port` | `agent` 域 `ResilientDispatcher` | task / agent | `agent → task` ❌ **反向**（agent 低于 task） |
| `TaskPlannerPickerPort`（§6.134） | `task.port` | `planner` 域 `PlannerAgentPicker` | task / planner | `planner → task` ✅ 顺向（planner 高于 task） |
| `ArtifactReferencePort`（本节，最新） | `system.port` | `task.service.impl.ArtifactReferencePortAdapter` | system / task | `task → system` ✅ 顺向（task 高于 system） |

> ⚠️ **订正（2026-10-01 自查）**：`TaskDispatchPort` 原在本表标注为「顺向」，**错误**。按依赖链 `planner > review > task > agent > system > shared`，`agent` 低于 `task`，故 `agent → task` 是**反向依赖**（正是冻结守卫组 1 的 `agent->task` 规则，基线 68）。这也暴露原判据表述的不完整 —— 见下。

**判据的完整表述（修正）**：基本式「**消费方域定义接口、提供方域实现**」**只是一半**，必须叠加项目约束 **「依赖必须顺向」**（沿 `planner > review > task > agent > system > shared` 向下）；**两者冲突时以「顺向」为准**。

- 消费方**低于**提供方（本节：`system` 消费 `task`）→ 端口放**消费方** `system.port`，提供方 `task` 实现，`task → system` ✅ 顺向。**教科书式反转，可直接照此办**。
- 消费方**高于**提供方（如 `task` 消费 `agent`）→ 端口**必须放提供方域**（`agent.port`），消费方 `task` 依赖它 = `task → agent` ✅ 顺向。**不能套教科书「端口在消费方」**，否则强迫 `agent → task` **反向** —— 这就是 `TaskDispatchPort` 的病灶。
- 故 `ReviewPort` / `TaskPlannerPickerPort` 之所以合法，**不是因为「端口在消费方」**，而是因为 review / planner 恰好**高于** task，依赖恰好顺向。

**为什么不放 `shared`**：`shared` 是**叶子域**（任何域都可依赖它）；把**只服务单一消费方**的窄接口放进叶子域，会让契约归属与使用方分离、后续无谓扩散。仅当**多个域都是消费方**时才考虑。完整判据已写入 `doc/HelloAI_CODE_STYLE.md` §7.2。

### 15.3 改动清单

| 文件 | 变更 |
|---|---|
| `system/port/ArtifactReferencePort.java` | **新建**：端口契约，仅 `List<ArtifactReference> listAllIncludingDeleted()` |
| `system/port/ArtifactReference.java` | **新建**：`record ArtifactReference(String storageUrl, Long fileSize)` 只读值对象（零实体泄漏） |
| `task/service/impl/ArtifactReferencePortAdapter.java` | **新建**：实现端口，注入 `AttachmentService`，逐行映射为值对象 |
| `system/storage/impl/ArtifactStorageReconcileServiceImpl.java` | 删 2 条 `task.*` import 与 `AttachmentService` 字段；改注入 `ArtifactReferencePort`；`for (Attachment …)` → `for (ArtifactReference …)`（`row.storageUrl()` / `row.fileSize()`） |
| `…ArtifactStorageReconcileServiceImplTest.java` | mock 由 `AttachmentService` 换 `ArtifactReferencePort`；`row()` 助手改返回值对象 |
| `task/service/impl/ArtifactReferencePortAdapterTest.java` | **新建**：映射/空行/空列表 3 用例 |
| `.tmp/ReconcileDryRun.java` | 本地 dry-run（**gitignored**）同步换端口类型，保持可用 |
| `scripts/ci/check-arch-freeze.sh` | 删 `system->task` 的「存量标债（2）」注记 |
| `scripts/ci/arch-baseline.txt` | `--update-baseline` → `system->task=0` |
| `scripts/powershell/verify-dependency-direction.ps1` | `system->task` 断言由 `-KnownDebt 1` 改为**严格 0**（注释同步） |

> **命名取舍**：值对象名 `ArtifactReference` 而非 `Attachment*`，刻意与 task 域实体解耦（端口语义是「产物引用」而非「附件记录」）。

### 15.4 验证

| 项 | 命令 | 结果 |
|---|---|---|
| 定向单测 | `mvn -o -pl helloai-core -am -DskipTests=false -Dtest='ArtifactStorageReconcileServiceImplTest,ArtifactReferencePortAdapterTest' test` | **16 用例 / 0 失败**（13 + 3） |
| 架构冻结 | `bash scripts/ci/check-arch-freeze.sh` | `system->task` **2 → 0**（`✅ 改善 -2`），其余 19 条持平 |
| 基线锁定 | `--update-baseline` 后复跑 | 20 条全持平 **EXIT=0** |
| 依赖方向（PS1） | `verify-dependency-direction.ps1` | **ALL PASSED / EXIT=0**；`[PASS] system has no dependency on [task]`（已无 `[DEBT]` 豁免） |
| 全量回归 | `mvn -o -DskipTests=false test` | 见 §15.5 |

### 15.5 全量回归复跑

| 项 | 命令 | 结果 |
|---|---|---|
| 全量单测（clean） | `mvn -o -B -DskipTests=false clean test`（JAVA_HOME=ms-17.0.20.1，离线） | Reactor **7/7 SUCCESS**；**用例 1783 / failures 0 / errors 0 / skipped 0**（177 个 surefire 报告文件） |
| 分模块 | — | core **1628** / job **85** / api **70** / mq 4（含在 core 报告） |

> **口径订正（重要）**：上表 1783 按 surefire XML 内 **`<testcase>` 元素**计数，与 §13.5 及所有历史报告使用的 `<testsuite tests="N">` **属性**口径**不同**——后者会漏计全部 JUnit5 `@Nested` 用例（属性为 0，而文件内仍有 testcase）。本次同一份产物按旧口径得 **1144**，按新口径得 **1783**，**差 639**。详见 §15.6。

### 15.6 顺带发现并修复：CI 门禁「用例数」口径漏计 `@Nested`（2026-10-01）

**发现**（在核对 job 模块 `NoClassDefFoundError` 时顺带查出）：

| 证据 | 内容 |
|---|---|
| `AgentProviderResolverTest.xml` | `<testsuite … tests="0" …>`，但文件内 **`<testcase>` 12 个** |
| 控制台 | `Running resolveModel` → `Tests run: 7 … in resolveModel`、`resolveProvider` → 5，而外层 `AgentProviderResolverTest` → **0** |
| 全仓 | 含 `@Nested` 的测试类 core **39** 个 / job **8** 个；属性口径合计 **1144**，`<testcase>` 口径合计 **1783** |

**根因**：Surefire 3.5.4 把 `@Nested` 用例写进**外层类**的 XML 文件，但该文件的 `tests`（以及 failures/errors/skipped）**属性只反映外层容器自身**（通常为 0，因外层类往往不直接声明 `@Test`）。凡按属性汇总者一律漏计。

**影响面**：
- `scripts/ci/ci-gate.sh` **门禁 2**（`grep -o 'tests="[0-9]*"' … | head -1`）——**已修**；
- 所有引用「1141 用例」的历史结论（含本备忘录 §13.3/§13.5、审计报告 §9 局限 #4 记的「沿用 1764」）应理解为**属性口径**，真实执行数为 1783；
- 门禁语义未失效（旧口径下 1144 > 0 仍能拦住「零用例假绿」），但**指标本身低报 35.8%**，会掩盖单模块层面的「用例骤减」。典型例证：job 模块旧口径仅 **17**，真实 **85**（差 68）。

**修复**（`scripts/ci/ci-gate.sh` 门禁 2）：

```bash
# 旧：读 <testsuite tests="N"> 属性（漏计 @Nested）
n="$(grep -o 'tests="[0-9]*"' "$f" | head -1 | grep -o '[0-9]*')"
# 新：数 <testcase> 元素（每个用例一个，含 skipped）
n="$(grep -o '<testcase' "$f" | wc -l | tr -d '[:space:]')"
```

并在脚本内写明口径理由与「1144 vs 1783」实测数据，防后人改回。同步更新 `scripts/README.md` §6 表格。

**验证**：对同一份 clean 产物按新口径独立复算 → **177 文件 / 1783 用例 / failure 0 / error 0 / skipped 0**。

> **另注（既有失败，非本次引入）**：本轮首次全量跑时 `helloai-job` 5 个用例报 `NoClassDefFoundError: SubTaskMapper`。反编译证实其 `target/test-classes` 里是**陈旧 class**（字段描述符为默认包 `LSubTaskMapper;`，而源码是 `import com.helloai.core.task.mapper.SubTaskMapper`），且 mtime 被刷新后**令 Maven 增量编译跳过重编**；`clean` 后即消失。教训见 `doc/log/2026-10.md` 同批次条目。


## 16. Q1-③ 第 5 条预案：`agent → task`(68) 解耦分批方案（2026-10-01，本轮不实现）

### 16.1 病灶现状

`agent → task` 是当前冻结基线内**最大的单笔存量为**：

| 项 | 事实 |
|---|---|
| 计数 | `agent->task = 68`（`scripts/ci/arch-baseline.txt` 现值；`bash scripts/ci/check-arch-freeze.sh` 实测持平） |
| 性质 | CODE_STYLE §6 组 1「反向依赖」（`agent` 属链路下游，不得依赖上游 `task`） |
| 门禁语义 | `check-arch-freeze.sh` 为「**只降不升**」——68 是**上限**，解耦是**单向门**；任何回弹即触发 `❌ 超出 +N` / `EXIT=1` |
| 现状兜底 | 无 ArchUnit 编译期约束，实际拦截靠该脚本 + `scripts/powershell/verify-dependency-direction.ps1`（PS1 中 `agent->task.mapper` 已严格 0，但 `agent->task` 域级尚未入 PS1） |

> 结论：`agent → task` 的清偿**不是可选优化，而是解除 68 这道天花板**；但在清偿完成前，任何新代码都不得再触碰 `com.helloai.core.task.*`。

### 16.2 分布与分类（实测）

```bash
grep -rn "^[[:space:]]*import[[:space:]]\+com\.helloai\.core\.task" helloai-core/src/main/java/com/helloai/core/agent \
  | sed 's/.*core\.task\.\([a-z]*\).*/\1/' | sort | uniq -c | sort -rn
# => 37 service / 26 entity / 4 spec / 1 port   （合计 68）
```

| 类别 | 数量 | 典型成员 | 解耦机制 |
|---|---|---|---|
| 实体泄漏（entity） | 26 | `SubTask` 15、`RewardLog` 3、`ActivityLog` 3、`Uncertainty` 2、`Attachment` 2、`entity.*` 通配 1 | 归属纠偏 / 快照值对象 |
| Service 调用（service） | 37 | `SubTaskService` 15、`TaskTimelineService` 11、`TaskRunningSpecService` 3、`AttachmentService` 3、`RewardService` 2、`ActivityLogService` 2、`SubTaskDispatchService` 1 | 端口反转（只读）/ 领域事件（写副作用） |
| 规格对象（spec） | 4 | `ExecutionRecord` 3、`ExecutionRecordParser` 1 | 端口反转 |
| 端口（port） | 1 | `TaskDispatchPort`（`ResilientDispatcher`） | 端口归位 |
| Mapper 直捅 | **0** | ——（`agent->task.mapper` 已是 0） | 无需处理，保持 |

### 16.3 端口归属判据（以 `doc/HelloAI_CODE_STYLE.md` §7.2 为准，后续各批一律照此）

> **完整判据（与 `doc/HelloAI_CODE_STYLE.md` §7.2 逐字一致）：**
> **「让消费方↔提供方的依赖落在顺向那一侧；端口放在顺向依赖的被依赖方」。**
> 基本式「**消费方域定义接口、提供方域实现**」**只是一半**，必须叠加项目约束「**依赖必须顺向**」（沿 `planner > review > task > agent > system > shared` 向下依赖）；**两者冲突时以「顺向」为准**。
>
> - 消费方**低于**提供方（`system` 消费 `task`）→ 端口放**消费方** `system.port.*`，提供方实现，`task → system` ✅ 顺向；
> - 消费方**高于**提供方（`task` 消费 `agent`）→ 端口**必须放提供方域**（`agent.port.*`），消费方 `task` 依赖它 = `task → agent` ✅ 顺向；**套教科书「端口在消费方」会强迫 `agent → task` 反向**；
> - 多方消费（≥2 域共用同一能力、方向不可调和）→ 才考虑把**纯数据契约**放 `shared`（叶子域）；`shared` 不得 import 任何业务域。

先例（与 §15.2 同源，4 行口径已核实）：

| 先例 | 端口位置 | 实现位置 | 消费方 / 提供方 | 依赖方向 |
|---|---|---|---|---|
| `ReviewPort`（§6.146） | `task.port` | `review.service.impl.ReviewPortAdapter` | task / review | `review → task` ✅ 顺向（review 高于 task） |
| `TaskDispatchPort`（§6.138） | `task.port` | `agent` 域 `ResilientDispatcher` | task / agent | `agent → task` ❌ **反向**（agent 低于 task）→ 存量 68 的一笔 |
| `TaskPlannerPickerPort`（§6.134） | `task.port` | `planner` 域 `PlannerAgentPicker` | task / planner | `planner → task` ✅ 顺向（planner 高于 task） |
| `ArtifactReferencePort`（§15，最新） | `system.port` | `task.service.impl.ArtifactReferencePortAdapter` | system / task | `task → system` ✅ 顺向（task 高于 system） |

> **本例（本方案批次 2）**：消费方是 **agent**、提供方是 **task**；`agent` **低于** `task` ⇒ 属上述「消费方低于提供方」情形 ⇒ 端口落**消费方** `agent.port.*`、实现落提供方 `task` 域 `*PortAdapter`，实现侧依赖 `task → agent` 属**顺向合法**。
>
> **`TaskDispatchPort` 反向的根因**：判据套用错误 —— 消费方 `task` **高于** 提供方 `agent`，端口却按教科书放在了消费方 `task.port`，迫使提供方 `agent` 依赖消费者 ⇒ `agent → task` **反向**。本方案**批次 3 将其归位到提供方 `agent.port`**，从而使消费方 `task` 依赖它变成 `task → agent` **顺向**。

### 16.4 分批方案（5 批）

| 批次 | 批次目标 | 涉及文件与行数量级 | 手法 | 预估规模 | 依赖前置 | 验收口径 |
|---|---|---|---|---|---|---|
| **1** | **实体归位**：`RewardLog`(3) + `ActivityLog`(3) + `RewardService`(2) + `ActivityLogService`(2) = **10** | 3 文件（`agent/service/AgentService.java`、`agent/service/AgentStatsService.java`、`agent/service/impl/AgentServiceImpl.java`） | **归属纠偏（非反转）**：核实 agent 为唯一读写域后，把错放在 task 域的实体与服务整体迁回 agent 域 | S | 无 | `agent->task` 68 → 58；定向单测 + 门禁确认下降 + `--update-baseline` + 复跑 `EXIT=0` |
| **2** | **只读端口反转**：`TaskRunningSpecService`(3) + `AttachmentService`(3) + `spec.ExecutionRecord`(3) + `ExecutionRecordParser`(1) + `SubTaskService` 只读子集(9) = **19** | 9 文件（`command/ExecutionResultHandler`、`dispatcher/ExecutionCommandPoller`、`dispatcher/SubTaskAutoExecutionDispatcher`、`service/AgentRuntimeContextAssembler`、`service/AgentStatsService`、`service/impl/AgentDutyLeaseServiceImpl`、`service/impl/ExecutionCommandServiceImpl`、`service/impl/InFlightDbQuotaService`、`event/impl/EventReconciliationServiceImpl`） | **端口反转**：端口 `agent.port.XxxPort`（含只读 `record` 快照）定义在**消费方 agent**，实现 `XxxPortAdapter` 落**提供方 task**。**依据（§16.3 完整判据）**：消费方 `agent` **低于**提供方 `task`，故「端口放消费方」成立、实现侧 `task → agent` **顺向** ✅ —— **并非因为「端口一律要放消费方」** | M | 无（可与批次 1 并行） | 逐端口一改一测；`agent->task` 单调下降至 39 |
| **3** | **端口归位**：`TaskDispatchPort`(1) + `SubTaskDispatchService`(1) = **2** | 1 文件（`agent/dispatcher/ResilientDispatcher.java:21-22`） | **端口归位**：`TaskDispatchPort` 现落**消费方** `task.port`，迫使**提供方** `agent` 反向实现它（即存量 68 的一笔）；按 §16.3 完整判据（消费方 `task` **高于**提供方 `agent`）迁到**提供方** `agent.port`，由 `agent` 自身实现，消费方 `task` 依赖它 = `task → agent` ✅ 顺向；`SubTaskDispatchService` 同法归位或并入批次 2 端口 | S | 依赖批次 2/4（`ResilientDispatcher` 同时持有 `SubTask`/`SubTaskService`/`TaskTimelineService`，需同批收敛） | `agent->task` 再降 2 → 37 |
| **4** | **`SubTask` 快照值对象**：`SubTask` 15（含 `McpToolServiceImpl` 的 `entity.*` 通配 1）+ `Uncertainty` 2 + `Attachment` 2 = **19**（含通配计 20） | 12+ 文件（`command/ExecutionResultHandler`、`dispatcher/ExecutionCommandPoller`、`dispatcher/ResilientDispatcher`、`dispatcher/SubTaskAutoExecutionDispatcher`、`event/AgentEventContextResolver`、`event/impl/EventReconciliationServiceImpl`、`mqconsumer/LocalExecutionCommandConsumer`、`quality/ExecutorDoneIssuesBackfiller`、`service/AgentOutboxService(Impl)`、`service/ExecutionArtifactService(Impl)`、`service/impl/McpToolServiceImpl`、`service/AgentRuntimeContextAssembler`） | **快照值对象**：agent 端口返回 `record SubTaskSnapshot(…)`（**零实体泄漏**），**照抄 §15 `system/port/ArtifactReference`（record）+ `ArtifactReferencePortAdapter` 先例**；agent 不再 import `task.entity.SubTask` | L | 依赖批次 2 | 快照映射单测（正常/空值/字段截断）；`agent->task` 继续单调下降 |
| **5** | **领域事件解耦**：`TaskTimelineService`(11) + `SubTaskService` 写操作(6) = **17** | 12+ 文件（`command/ExecutionResultHandler`、`dispatcher/ResilientDispatcher`、`mqconsumer/LocalExecutionCommandConsumer`、`observability/CircuitBreakerEventRecorder`、`quality/ExecutorDoneIssuesBackfiller`、`service/AgentLifecycleService`、`service/AgentStatsService`、`service/impl/AgentServiceImpl`、`service/impl/McpToolServiceImpl`、`service/impl/ExecutionCommandServiceImpl`、`service/impl/SubTaskExecutionServiceImpl`） | **领域事件（唯一真事件化批次）**：agent 在状态推进处发布领域事件（`SubTaskExecutionStarted/Completed/Failed` 等），task 侧以 `AFTER_COMMIT` 监听**写 timeline + 推进子任务状态**；agent 不再同步调用 task 的写方法 | L | 依赖批次 2/4（写操作与实体同文件） | 事件投递/监听单测 + 端到端回归；`agent->task` 收口至 **0** |

**「软分段 → 批次」映射表（可核验，严格相加 = 68）**：

| 批次 | entity | service | spec | port | 小计 |
|---|---|---|---|---|---|
| 1 实体归位 | 6 | 4 | 0 | 0 | 10 |
| 2 只读端口反转 | 0 | 6 + 9（`SubTaskService` 只读） | 4 | 0 | 19 |
| 3 端口归位 | 0 | 1（`SubTaskDispatchService`） | 0 | 1 | 2 |
| 4 `SubTask` 快照 | 20 | 0 | 0 | 0 | 20 |
| 5 领域事件 | 0 | 11 + 6（`SubTaskService` 写） | 0 | 0 | 17 |
| **合计** | **26** | **37** | **4** | **1** | **68** ✅ |

> **软分段非互斥**：上表 entity / service / spec / port 是按 **import 类型**切的**软分段**，**同一文件的多处 import 可能分属不同批次**（例：`McpToolServiceImpl` 同时命中批次 2 的 `SubTaskService` 只读、批次 4 的 `SubTask` 实体、批次 5 的写操作）。故本表是**工作量排序**，不是互斥批次划分——实际推进规则见 §16.6。

**关键澄清（避免误传）**：**只有批次 5 是真正的「领域事件解耦」**；批次 1 是**实体归位**、批次 2/3 是**端口反转/归位**、批次 4 是**快照值对象**。把 68 整体表述为「一次事件化改造」是错的——68 里 51 处（批次 1～4）本质是**归位与端口化**，仅 17 处（批次 5）需要事件机制。

### 16.5 风险与回滚

**逐批纪律（每批、乃至每端口一级均须照做）**：**一条一改 → 定向单测 → `bash scripts/ci/check-arch-freeze.sh` 确认计数下降 → `--update-baseline` 锁定 → 复跑 `EXIT=0`**。

| 风险 | 说明 | 对策 |
|---|---|---|
| 数字回弹 | 68 → 0 期间若出现 `agent->task` **上升**，说明解耦不彻底（如 adapter 放错域、`task` 侧反向 import `agent` 实体） | 数字**只允许单调下降**；出现任何回弹视为**回归**，就地回滚到上一锁定的基线再重做 |
| 端口归属放错 | 只套教科书「端口放消费方」会**用抽象掩盖违规**：消费方**高于**提供方时，端口落消费方会迫使提供方反向依赖（`TaskDispatchPort` 即**在库反例**，见 §16.3） | 一律执行 §16.3**完整判据**「让依赖落在顺向那一侧、端口放顺向依赖的被依赖方」：消费方低于提供方 → 端口放**消费方**；消费方高于提供方 → 端口放**提供方**域 |
| 行为漂移 | 端口/快照改造中重写查询语义（尤其 `SubTaskService` 的 2 状态 vs 3 状态口径、`TaskTimelineService` 事务时序） | adapter **薄委托**、不重写 SQL/状态机；`AFTER_COMMIT` 语义逐条核对既有先例 |
| 大爆炸式改动 | 5 批一起改导致失败无法定位 | **按依赖前置分批推进**（批内按文件/端口粒度逐个）；**同一文件的多处 import 必须归并到同一批次 / 同一验证轮**（软分段非互斥，见 §16.6），如 `ResilientDispatcher`、`McpToolServiceImpl` 的跨批交叉点须同批收敛 |
| 回滚 | —— | 每批一个「基线快照」；回滚 = 还原该批文件 + `--update-baseline` 重算冻结值（禁止手改数字） |

> **判据反例警示（本方案后续 5 批最重要的前置认知）**：`TaskDispatchPort` 是**仍在库的反向依赖**，它证明「**端口反转 ≠ 自动合规**」——端口只是把直接 `import` 换成接口依赖；**若端口放错域，就等于用一层抽象把违规藏起来，反而更难回收**（`agent → task` 的 68 里就含这 1 笔）。故后续每批**新增端口前必须先按 §16.3 完整判据判定端口该落哪一侧**，再动手；**判据冲突时以「顺向」为准**。

### 16.6 本轮边界

> **本批（2026-10-01 架构收口）不实现 `agent → task` 解耦，仅登记预案。**
> 本轮实际开工范围仅为「反向依赖收口」的既有 4 条（`system → task` 已完成见 §15；`shared → task` / `shared → agent` / job 6 类见 §14「剩余待做」表）。`agent → task`(68) 属**下一轮单独立项**，本 §16 只提供可执行的批次预案与验收口径，**本轮不动任何 `agent` 域代码**。

**分批性质说明（防误读）**：

- §16.4 的 5 批是**工作量排序，不是互斥批次**——§16.4 的 entity / service / spec / port 只是按 **import 类型软分段**，**同一文件的多处 import 可能分属不同批次**（软分段非互斥）。
- 故实际推进**可按「依赖前置」交叉并行**，但**同一文件的改动必须归并到同一批次 / 同一验证轮**（避免同文件多次改、多次锁基线）。
- **试点建议**：批次 1 若只解掉 task 域实体（`RewardLog` / `ActivityLog`）这类**低风险点**，可先做**试点**，验证「定位 → 端口/归位 → 门禁 → 锁基线」的整条路径，再铺开批次 2～5。

### 16.7 与 §14 的追加指引（§14 零改动，对账全部落在本节）

> 约定：§14 是**既有开工记录、带日期快照语义**，**不回填**。本节只做**追加指引**——✅ 者只给跳转、**不复制内容**；⏳ 者指向本 §16。

**A. §14「剩余待做」表逐行追加指引**

| 项 | 本批状态 | 指引 |
|---|---|---|
| `agent → task`(68) | ⏳ **本批不实现** | → **本 §16 全篇**（68 → 0 的 5 批预案）。注：§14 原表**无此行**，本节补齐该缺口 |
| `system → task`(2) | ✅ 已在 §15 收口 | → **见 §15**（`system.port.ArtifactReferencePort` 端口反转），**不复制内容** |
| `shared → task`(2) | ✅ 本批收口 | → 本批 **② 分流处置**：`SubTaskDependencyOrder` 下移 `task/util`、`SubTaskOutputExtractor` 去实体化（`Map` 入参）留 `shared` |
| `shared → agent`(3) | ✅ 本批收口 | → 本批 **③ 整包搬迁**：`shared/doorbell/*` → `agent/doorbell`、`ExecutionCommandCreatedEvent` → `agent/event` |
| `job 6 类去 Mapper` | ✅ 本批收口 | → 本批 **④**：6 个定时任务改调对应域 Service。**注：`helloai-job` 不在 `arch-baseline.txt` 的 20 条规则内**——baseline 只覆盖 `planner/review/task/agent/system/shared` **六域**，**job 模块本身不是一个域**，故该批**不体现在 baseline 数字**上，仅体现为「`helloai-job` 内 `com.helloai.core.*.mapper` import 清零 + 全量单测全绿」 |

**B. 与 §13 / §15 的局部引用（同样不回改前文）**

| 前文位置 | 与 `agent → task` 的关系 | 指引 |
|---|---|---|
| §13.1 / §13.3（组 1 表，行 344） | 将 `agent->task=68` 登记为 4 条**存量标债**之一 | ⏳ **本 §16 为清偿预案**（未实现） |
| §13.3（行 350） | 曾把 `agent->task` 基线人为下调 67 做**负向测试**，证明门禁可拦截 | ✅ 机制有效；本 §16 复用该「只降不升」语义 |
| §15.2 先例表（行 424） | `TaskDispatchPort`（§6.138）现落 `task.port`，依赖方向为 `agent → task`（**反向，属存量 68**） | ⏳ **本 §16 批次 3 将其「端口归位」到提供方 `agent.port`**——按 §16.3 完整判据，消费方 `task` **高于**提供方 `agent`，端口应放**提供方**；归位后消费方依赖变为 `task → agent` 顺向，从而真正消除该 1 条 import |
| §15.3（`ArtifactReferencePort` 先例） | `system/port/ArtifactReference`（record）+ `ArtifactReferencePortAdapter` 是**零实体泄漏**的范式 | ✅ 本 §16 **批次 4 直接照抄**该范式做 `SubTaskSnapshot` |



