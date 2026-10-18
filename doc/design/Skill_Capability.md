# Skill Capability Package

> **Status: `Partial`** · **Scope: Skill Capability Package 元数据 + 生命周期（**`Install`**→Discover→Resolve→Load→Execute→Validate）；Instructions 结构化未动** · **差距锚点：`G-004`**
>
> 最后更新：2026-10-18（**新增「技能回流贡献规范（D5-3）」一节** —— `G-004` / `G-010` 的最后一个共同未达成项；同批订正元数据契约：白名单 **8 → 9 键**（`requiredBaseCapabilities` 于 `G-022` 补入）与「`name` 必须 == 文件名」这条**从未被代码强制**的错误表述）。2026-10-09（**元数据事实源迁移（REF-1.1/1.2）**：`SkillPackage` 元数据由 Java `KNOWN_SPECS` 编译期硬编码改为 **classpath `skills/plugins/*.md` 的 YAML frontmatter + 目录扫描**。状态事实源 = [`../HelloAI 实现差距表.md`](../HelloAI%20实现差距表.md)，本文件只声明设计边界，不复述进度）。**2026-10-10（`REF-1.4` / `1.5` / `1.6` 实施）**：生命周期新增 `Install` 阶段（受控存储：PG 存元数据与原始正文、MinIO 存原始 zip；不写宿主文件系统）；摄入闸门与来源打戳的边界见「生命周期」节与其后。
>
> **元数据契约（2026-10-18 订正）**：
>
> ```text
> frontmatter 已知键白名单（9 个，逐字匹配；事实源 = SkillPackageParser#KNOWN_KEYS）：
>   name / version / description / requiredTools / requiredBaseCapabilities
>   / dependencies / inputSchema / outputSchema / validationRules
> 必填：name / version（三段式，须加引号）/ description
> 约定（非强制校验）：name 应与文件名去 .md 保持一致（一致性靠命名规范与评审保证）
> 禁止：fileName —— 由目录扫描推导（写进 frontmatter 即第二事实源，判 corrupt）
> 空值语义：[] 与「省略」等价；{} 与「省略」等价
> 未知键 / 重复键 / 围栏未闭合 → corrupt（显式报错，不静默跳过）
> 目录条目排序：name 升序（确定性；不依赖文件系统返回顺序）
> ```
>
> **Discover 面的口径诚实边界**：本轮达成「**新增技能 = 丢一个 md，零改 Java 代码**」；「零发版」需外部技能目录（与摄入能力 REF-1.5/1.6 同批），**未达成**。

## 当前形态

```text
requiredSkills
    ↓
resolve
    ↓
resolvedSpecs
    ↓
instructions / context
```

当前主要能力仍然以 Markdown / instructions 为载体。

## 目标形态

```text
Skill Package
├── Name
├── Version
├── Description
├── Instructions
├── RequiredTools
├── Dependencies
├── InputSchema
├── OutputSchema
└── ValidationRules
```

## 原则

- Markdown 可以继续作为 Instructions 载体；
- Skill 与 Role 解耦；
- Skill 不直接拥有全局调度职责；
- Tool 依赖必须可声明；
- 先兼容现有 Skill，再逐步增加结构化元数据；
- 不因为 Capability Package 而建立第二套运行时。

## 生命周期

```text
Install      （REF-1.6：上传 zip → 过摄入闸门 → 元数据+正文落 PG、原始 zip 落 MinIO）
→ Discover   （目录双源：classpath 内置 + 已安装 ACTIVE 行）
→ Resolve
→ Load
→ Execute
→ Validate
```

> **`Install` 阶段的边界（2026-10-10 新增）**：
> - **落点是受控存储，不是宿主文件系统** —— 不构成 `G-005` 沙箱触发条件①（`D-2026-10-10-1①`）；
> - 进入目录的是**元数据 + 原始正文**（PG），原始 zip 仅作溯源 / 完整性 / 再分发（MinIO）；
> - 同 name 至多一行 `ACTIVE`；多版本共存供回滚，回滚 / 降版走 `activate` 而不复用安装入口；
> - 与内置 `eng-*` 同名一律拒绝（内置随发版走、不可替换）；
> - 摄入闸门（`REF-1.5`）是该阶段的**前置**：闸门不过，不落任何数据。

## 技能回流贡献规范（D5-3，2026-10-18）

> **本节回答一个问题**：任务执行过程中暴露的「能力不够 / 没有人能接 / 交付物质量反复不达标」，**如何变成平台的一项技能资产**。
> **明确不含**：技能包 **DB 化**（设计明确不做——技能包事实源是「md + 目录扫描」，落库会造第二事实源）；
> **也不含**统一技能词表（见下「不点名模型」判据）。

### 1. 缺口的四个信号源（谁发现问题）

回流不是「拍脑袋新增技能」，必须**从运行期证据出发**。四类信号按可追溯性排序：

| # | 信号 | 落点 | 说明 |
|---|---|---|---|
| 1 | `sub_task_no_candidate` | `task_timeline` | **技能缺口最直接的证据**：声明了 `required_skills`，但 `requires ⊄ provides` 导致无候选 ⇒ 子任务永久 PENDING |
| 2 | `task_plan_skill_unmatched` | `task_timeline` | 拆解侧已具备的承接者校验（`LOG-20261015-006`）：草案声明的技能没有任何在岗 Agent 可承接 |
| 3 | `missingEvidence` | `review_record.issues` | Reviewer 驳回的结构化返工线索（验收标准原文子串 + 缺什么 + 怎么补）⇒ 反复出现在同一「补什么」上，说明作业规范缺失 |
| 4 | `internal_report_blocked` / `uncertainties` | `task_timeline` / `sub_task.uncertainties` | 内部 LLM 自报阻塞（`REF-8.1.1`）/ 执行者申报的 ASSUMPTION·UNCONFIRMED ⇒ 能力面或信息面的缺口 |

> **判据**：先看这四类信号**是否重复出现**（同一「缺什么」出现 ≥2 次才值得新增技能）。单次出现优先按「本次任务的约束」处理，不升级为技能。

### 2. 先分型，再决定动作（三种缺口三种处置）

**这一步最容易做错**：把「模型能力不够」当成「缺技能」，或把「缺工具」当成「缺技能」，结果加了 md 却不生效。

| 缺口类型 | 判别 | 处置 |
|---|---|---|
| **技能缺失**（有工具、有模型能力，缺作业规范） | 工具在 `ToolRegistry` 可见名单内、模型 `capabilities` 满足；但输出格式 / 检查项 / 证据要求不达标 | **新增或修订 md**（本节 3~6 步） |
| **工具缺失** | `requiredTools` 声明的工具在平台侧不存在，或 `ToolCallbackContributor.toolAvailability()` 恒 false 被摘除 | 加 `ToolCallback`（`agent/tool/impl`），**不是**加技能 |
| **模型能力缺失** | 技能需要联网 / 推理，但承载 Agent 的 `llm_provider_model.capabilities` 为 false | 换模型，或给技能声明 `requiredBaseCapabilities` 让选人自动排除（**不要**改技能去迁就模型） |

### 3. 贡献物形态 = 一个 md + 9 键 frontmatter

**一条 md 只讲一件作业规范**。体量与结构约定：

```text
frontmatter（9 键，见开头「元数据契约」）
---
正文
├── H1 标题（一句话说清「这是什么规范」）
├── 来源与定位（从哪来、什么时候该被命中）
├── 执行速览（≤4 条，命中 required_skills 时注入执行 Prompt 的部分）
└── 详细规范（可长；不注入，供人读与后续拆分）
```

**三条硬规矩**：

1. **一条 md 一件事**。`eng-doc-standard` 不是一个「文档大杂烩」，而是「文档规范」这一件事的完整边界；把评审、验证、调研塞进同一个 md 会让 `required_skills` 的**命中粒度失真**（派了不需要的规范）。
2. **`name` 全小写连字符，建议 `eng-<动作>-<对象>`**（`eng-code-review` / `eng-web-research`）；与文件名去 `.md` 保持一致（约定，非强制校验）。
3. **执行速览 ≤4 条**，每条以「**做什么 + 为什么**」成对出现。速览会被逐字注入执行 Prompt，写成长段落会挤占模型预算。

### 4. `requiredBaseCapabilities` 的声明判据（`G-022` 引用边）

这是「细粒度技能包」指向「粗粒度模型能力」的**唯一引用边**。判定式 `requires ⊆ provides` 实现在 `SkillCapabilityRequirements#satisfiesPackage`（唯一判据）。

**当前可用谓词集**（事实源 `SkillCapabilityRequirements`）：`reasoning` / `webSearch` / `toolCalling`（后者**已备但暂不消费**）。

**声明判据**：**只在「本技能的核心动作在平台侧有对应谓词」时声明**。

- ✅ 正例：`eng-web-research` 声明 `webSearch` —— 它的核心动作就是联网检索，而 `webSearch` 是 provider 级工具可用性（与标准技能 `web-search` 同源，`M-9` 已证严格双射）。
- ✅ 留空正例：`eng-code-review` / `eng-doc-standard` / `eng-verification` **有意留空** —— 它们是「输出格式 / 检查项」的作业规范，执行只需要模型能按格式产出文本。**声明空集 = 恒真**，行为与补入该字段前逐字一致。
- ❌ 反例：为了让技能「看起来更严格」而声明 `reasoning`。**声明只会新增拒绝面**（把本来能承接的模型排除掉），不会提升质量。

> **判据一句话**：**声明的唯一理由 = 「模型没有这个能力，这项技能就真的执行不了」**。答不上这句，就留空。

### 5. `requiredTools` 的声明判据

工具名会经 `EnabledToolNames#union` 与上下文工具**并集去重保序**，再由 `ToolRegistry#resolve` 决定「模型可见集」（含条件可用摘除与描述重写）。

**声明判据**：工具名必须在 `ToolRegistry` 的注册名单内，否则并集里会多出一个**永远不会被解析**的名字 —— 技能静默失效（不报错，只是模型看不到该工具）。

**自检两步**：① 在 md 注释里写清「本技能需要这个工具做什么」；② 确认该工具在平台侧真实存在且默认可用（被 `toolAvailability()` 摘除的工具声明了也是空转）。

### 6. 四道验收闸门（缺一不可）

| # | 闸门 | 实现 | 拦截什么 |
|---|---|---|---|
| 1 | **9 键白名单 + 类型/必填校验** | `SkillPackageParser#parse` | 未知字段 / 重复键 / 类型不符 / 缺必填 / YAML 语法错 ⇒ `SkillCorruptException`，**不静默跳过** |
| 2 | **包规范校验** | `scripts/powershell/verify-skill-packages.ps1` + golden 夹具 | 键集合漂移（新增字段未同批更新契约） |
| 3 | **引用边可判定** | `SkillCapabilityRequirements#satisfiesPackage` | `requiredBaseCapabilities` 写错 ⇒ 选人恒失败 ⇒ 子任务永久 PENDING |
| 4 | **词表对齐** | `scripts/shell/verify-skill-capability-parity.sh` + 单测双向包含 | `REQUIRED` 键集 ≠ `AgentSkillDeriver#STANDARD_SKILLS`（8 项） |

> 闸门 1 是**运行时**的（坏 md 进目录时暴露为 `corrupt` 条目，经 `GET /api/skills/catalog` 可见），2~4 是**提交时**的。

### 7. 从缺到上线：六步流程

```text
① 取证   从 §1 四类信号取原始记录（timeline / uncertainties / missingEvidence）
② 分型   按 §2 判「技能缺失 / 工具缺失 / 模型能力缺失」，只有第一类走本流程
③ 起草   按 §3 写 md（9 键 + 速览 ≤4 条），按 §4/§5 决定能力与工具声明
④ 自校   verify-skill-packages.ps1 通过（闸门 1~4 全绿）
⑤ 选通道 内置（平台自有 eng-*）→ PR 评审合入 classpath
         外部贡献 → 走 Install 通道（zip + 摄入闸门，零发版，与内置同名一律拒绝）
⑥ 生效   目录可见（GET /api/skills/catalog）⇒ 承载 Agent **重注册**触发 AgentSkillDeriver 派生
```

> **⑥ 的诚实边界**：存量 `agent.skills` **不会自动回填**（刻意不写迁移，避免造第二事实源，见 `G-022`）。新增 / 修订技能包后，**必须让相关 Agent 重注册**才会把新能力并入 `agent.skills`。

### 8. 禁止事项（踩过或明确裁定不做）

| 禁止 | 原因 |
|---|---|
| 技能里**点名模型** | DSH 判据：技能不点名模型，但技能可以提需求。点名会让换模型变成改技能 |
| **统一技能词表** | 会迫使技能包点名模型（同上）；正确做法是「引用边」（`G-022`），不是合并命名空间 |
| 一条 md 塞多件事 | `required_skills` 命中粒度失真，派了不需要的规范 |
| 把执行步骤写成 SOP 流水账 | 速览注入 Prompt，长段落挤占模型预算；详细规范放正文 |
| 恢复 `KNOWN_SPECS` 硬编码 | 已于 `REF-1.1/1.2` 删除（会造第二事实源） |
| 用 DB 迁移回填 `agent.skills` | 造第二事实源；正确路径是重注册触发派生 |
| 跳过 `verify-skill-packages.ps1` | 键集合漂移不会在运行时暴露（未知键只在解析时抛 corrupt，写错了可能长期没人发现） |
