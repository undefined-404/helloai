# HelloAI 任务拆解领域无关化设计

> **状态：Planned**
> 最后更新：2026-10-24
>
> **Scope**：草案生成（`planner-decompose`）与任务拆解的**领域无关化口径**——`taskArchetype` 六类拆分维度、`acceptanceKind` 四类验收判据、拆解期上下文快照、以及「计划未知」任务用的 ledger 通道边界。
> **不含**：实施顺序与逐项验收（见 `plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md`）；外部方案调研与差距判定（见 `review/HelloAI 草案生成与任务拆解 对照开源与 Qoder 的差距分析（2026-10-24）.md` / `review/HelloAI 任务拆解方案对照与领域无关化设计（2026-10-24）.md`）；澄清链路的门控与交互改造（属 `G-024` 相邻面，不在本文）。
> **差距表锚点**：`G-025`（任务拆解的领域无关化）；任务号段 `TD-1`~`TD-10`（《差距表》§0 编号口径表）。
> **实施状态**：`TD-1` / `TD-2` / `TD-6` / `TD-7` 已落地，`TD-3` 已落地待真实样本验证（六套拆分维度 + `acceptanceKind` 输出契约 + 数量条件渲染均已进提示词，验收②需跑一轮真实 `DOCUMENT` 任务）。**能力仍未生效**——`TD-4`（archetype 判定链）未做 ⇒ `task_archetype` 恒 `BUILD`；`TD-5`（验收按 kind 分流）未做 ⇒ `acceptance_kind` 虽可声明但不影响校验。生效判据仍以 plan §3 的 `TD-3` + `TD-5` 为准。逐项进度只以《差距表》§1 与 plan 为准，本文不复述。

---

## 1. 问题与边界

当前的拆解能力只覆盖**软件交付**这一种任务形态。证据是提示词而非代码——`planner-decompose.md` 的「拆解原则」整段是软件交付语法：

| 条款 | 原文举例 | 隐含假设 |
|---|---|---|
| 契约先行 | 「前后端联调 / 服务间对接 / 库表 + 接口 + 页面联合交付」 | 交付物是**接口与数据模型** |
| 技能指派 | `requiredSkills: ["eng-doc-standard"]` | 技能标签是**研发技能** |
| 验收标准 | 「运行 X 命令输出含 Y」「接口 Z 返回 200 且字段 W 存在」 | 存在**可执行的客观裁判** |
| 边界硬约束 | 「不得修改对外接口签名」 | 存在**对外接口** |

而 `GRANULARITY`（FINE / STANDARD / COARSE）只控制**拆多细**，**完全不控制按什么维度拆**。

### 1.1 三个失效的锚点

非代码任务不是「拆得粗一点」的问题，是三个决定拆解结构的锚点同时缺失：

| 锚点 | 代码任务怎么得到 | 非代码任务的困境 |
|---|---|---|
| **终止锚**（拆到何时算完） | 覆盖所有待改动点，IDE / 编译器能列出剩余项 | 文档拆几章？调研拆几个子问题？**无天然边界** |
| **验收锚**（单条怎么算完成） | 编译过 / 测试过 / lint 过 | 只有「够不够好」，**主观判断** |
| **依赖锚**（为什么 A 必须在 B 前） | 接口定义 → 实现 → 调用，类型系统给出偏序 | 「先调研再写方案」是真依赖还是可并行？**说不清** |

### 1.2 本文的边界

本文**不主张**做「通用任务拆解」。主流方案里不存在这种东西，因为**拆解维度与验收判据是由交付物的性质决定的**。

本文的立场是：**把「按什么维度拆」和「谁来判怎么判」变成显式声明**，让拆解从「只有一档」变成「按判据分档」。

---

## 2. 设计总览

两层字段，各司其职，**不互相替代**：

```
task.task_archetype          →  「按什么维度拆」「终止锚在哪」         （任务级，单值）
sub_task.acceptance_kind     →  「谁来判、怎么判」                    （子任务级，可逐条不同）
```

**为什么不给子任务也设 archetype**：一个任务里可以有不同类型的子任务（写一份方案的任务里，「查竞品」是 RESEARCH、「写正文」是 DOCUMENT）。但**拆解维度必须由主交付物决定**，否则拆出来的骨架是混的。子任务级别的差异由 `acceptance_kind` 表达已足够——它管的是「怎么判」，不参与「怎么拆」。

---

## 3. `taskArchetype`：六类拆分维度

分类依据是**「怎么判定完成」**，不是行业。原因：拆解的边界由判据决定，同一个行业的不同交付物可以有完全不同的判据。

| archetype | 终止锚 | 验收锚 | 自然拆分维度 | 典型任务 |
|---|---|---|---|---|
| `BUILD` | 覆盖所有改动点 | 编译 / 测试 / lint | 模块分层 + **契约先行** | 代码、配置、部署、脚本 |
| `DOCUMENT` | 覆盖读者的决策链 | 结构可检查 + 审查 | **读者问题序**（不是章节数） | 方案、报告、PRD、白皮书、汇报材料 |
| `RESEARCH` | 每个子问题都能独立佐证或推翻结论 | 证据充分性（有出处） | **问题分解树**（子问题互斥且合并覆盖） | 竞品调研、技术选型、故障诊断、合规排查 |
| `DATA` | 数据流每阶段校验通过 | 行数 / 分布 / 对账 | 采集 → 清洗 → 校验 → 加载 → 复验 | 报表、ETL、数据标注、对账 |
| `OPERATE` | 时间轴节点全部触发且留痕 | 可观测结果（回执 / 日志 / 截图） | 前置 → 触发 → 监控 → 复盘 | 活动、发布、巡检、应急 |
| `DECIDE` | 选项穷尽 + 评估维度覆盖 | 决策记录（ADR 式） | 选项生成 → 维度评估 → 推荐 | 技术选型、排期、供应商选择 |

### 3.1 六个 archetype 的拆解约束（供提示词直接消费）

**`BUILD`**
- 拆分维度：模块 / 层（presentation / application / domain / infrastructure）
- 契约先行：多模块协作时，最上游子任务必须是契约定义（**保留现规则，其余 archetype 不强制**）
- 依赖锚：接口签名 → 实现 → 调用方
- 禁止：把「写测试」和「测一下」拆成两条（合并到同一子任务的 acceptance）

**`DOCUMENT`**
- 拆分维度：**每个子任务对应一个「读者要回答的问题」**，不是文档的一个章节
- 终止锚：读者决策链闭环（读完能做出目标决策），不是「章节齐了」
- 交付物必须写明「读者」「读者要做的决策」「本段提供什么依据」
- 禁止：按「写引言 / 写正文 / 写结论」这类版面结构拆——那是打字顺序，不是任务分解

**`RESEARCH`**
- 拆分维度：问题分解树，要求**子问题互斥且合并覆盖原问题**
- 每个子问题必须写明「什么证据能佐证」「什么证据能推翻」
- 交付物 = 证据 + 结论 + **置信度**（不得只给结论）
- 依赖锚：分叉型（子问题之间可并行），汇合点才需要依赖
- 禁止：拆成「先搜集资料 → 再分析 → 再总结」这类流水线——那是 RESEARCH 的**内部步骤**，不是子任务

**`DATA`**
- 拆分维度：数据流阶段，**阶段之间必须有数据契约**（输入 schema → 输出 schema）
- 每阶段 acceptance 必须含可数断言（行数守恒、字段齐全率、空值率、对账差额）
- 依赖锚：强串行（下游读上游产出）
- 禁止：只写「清洗数据」不写清洗规则与判定

**`OPERATE`**
- 拆分维度：时间轴 + 依赖资源，**依赖锚由「时点」表达而非「产出」**
- 每个节点必须含：触发条件、执行人 / 通道、可观测留痕（回执 / 日志 / 截图）
- 必须有一个**回滚 / 止损节点**
- 禁止：拆分出「监控中」这类无交付物的持续态子任务

**`DECIDE`**
- 拆分维度：选项生成 / 维度评估 / 推荐三段
- 交付物 = 决策记录（选项 + 维度 + 评分 + 结论 + 未采纳理由）
- acceptance 必须能判定「选项是否穷尽」「评估维度是否覆盖」
- 禁止：产出「执行动作」——本 archetype 只到「建议」，执行是下游任务

### 3.2 archetype 怎么定

三路，优先级递减：

1. **用户显式指定**（任务创建 / 编辑时可选）——人工为权威输入
2. **澄清终稿产出**（`final_package` 增加 `archetype` 字段）——模型在产终稿时判，因为那时它已经理解了交付物
3. **回落 `BUILD`**——判不出时保持现行为，**不做猜测性判定**

> 关键取舍：**不在拆解时判 archetype**。拆解阶段模型只看到 title + description，判不准；终稿阶段模型见过完整对话，判得准。这也是「顺序」问题——判定必须发生在信息最全的那个环节。

### 3.3 拆解数量：门槛与数量必须分离（`[实测]` 借鉴自 task-master）

原始假设「复杂度 1~10 分 → 推荐子任务数 有映射表」**经代码核对不成立**：`analyze-complexity.json` 里 `complexityScore` 与 `recommendedSubtasks` **两者都是模型一次输出的字段**，没有任何映射函数。所以「照抄映射表」这条路不存在。

但它有一个更值得借的设计——**把「要不要展开」和「展开成几个」拆成两件事**：

| 环节 | 谁决定 | 可否覆盖 |
|---|---|---|
| **要不要展开**（门槛） | 独立参数 `threshold`，默认 5；分数 ≥ 门槛才进展开候选 | 可配置 |
| **展开成几个**（数量） | 模型给的 `recommendedSubtasks` | **四级回退链**（见下） |

数量的四级回退（`[实测]` `expand-task.js:186-207`）：

1. **人工显式传入**（`numSubtasks`）→ 用它，且 prompt 里渲染为 `exactly {N}`
2. 复杂度报告里的 `recommendedSubtasks`（**模型给的**）
3. 配置默认（`DEFAULT_SUBTASKS`）
4. 非法值兜底 → 硬编码 `3`

**对我方的意义**：现在的 `GRANULARITY`（FINE / STANDARD / COARSE）把「要不要拆细」和「拆几个」混在一个枚举里，且是**全任务统一**一个档位。可借鉴的两点：

- **数量可按任务给**，不必全任务同档
- **prompt 层支持「钉死数量」**：`{{#if (gt subtaskCount 0)}}exactly {{subtaskCount}}{{else}}an appropriate number of{{/if}}` —— 默认放给模型，需要时可钉死

> 但**不借它的依赖处理**：task-master 的子任务 `dependencies` 只是「依赖哪些 ID」，不做拓扑校验；我方有 Kahn + 环检测 fail-close，比它严，保留。

---

## 4. `acceptanceKind`：四类验收判据

`acceptance` 现在是一段自由文本。让模型写「判定动作 + 预期结果」在代码场景有效（有命令可跑），在非代码场景只能编。所以需要声明**谁来判、怎么判**：

| kind | 判定者 | 适用交付物 | 写法硬约束 | 判不过的后果 |
|---|---|---|---|---|
| `AUTOMATED` | 机器 | 代码、配置、数据文件 | 必须给出可执行命令或断言，含预期输出 | 执行侧直接判失败 |
| `STRUCTURAL` | 规则 + 机器 | 文档、报告、PPT、清单 | 必须是**可数的结构断言**（章节数 / 必填要素 / 字数下限 / 条目数 / 字段齐全） | 结构校验不通过即失败 |
| `REVIEWED` | 审查 agent | 方案、设计、分析、改稿 | 必须给出**逐条可打勾的审查清单** | 审查 agent 输出 pass/fail + 理由 |
| `ATTESTED` | 人 | 决策、创意、对外沟通、现场动作 | 必须写明 **谁 / 什么时点 / 留什么痕** | 缺少三者之一即校验失败 |

### 4.1 与既有 review 域的关系

**这是本设计里最重要的一条降本判断：`acceptanceKind` 不需要新建链路。**

| kind | 现有可复用的执行侧 |
|---|---|
| `AUTOMATED` | 执行侧的既有校验（`VERIFICATION` 围栏） |
| `STRUCTURAL` | 新增一个轻量结构校验器（数条目 / 查关键词），**不需要 LLM** |
| `REVIEWED` | **现有审查 agent**（`subtask-review.md` 轨道 A）——按 acceptance 逐条打勾，本来就是它干的事 |
| `ATTESTED` | 现有的人工确认动作（变更状态 + 留痕时间线） |

也就是说：`acceptanceKind` 的作用是**把已有能力的适用边界显式声明出来**，而不是新增能力。

### 4.2 校验的分流点

拆解侧（`PlannerDecomposeAsyncServiceImpl` 落库前的校验段）按 kind 分别校验：

- `AUTOMATED`：acceptance 内必须含「命令 / 断言」与「预期结果」两个成分
- `STRUCTURAL`：必须含至少一个可数量词（数字 + 单位 / 条数 / 字段名）
- `REVIEWED`：必须能被拆成 ≥2 条独立可判定的检查项
- `ATTESTED`：必须含判定人、时点、留痕方式三要素

**铁律：kind 与文本写法必须匹配。** `AUTOMATED` 却写「文档质量良好」⇒ 校验失败；`STRUCTURAL` 却写「运行 pytest」⇒ 校验失败。这是把「验收假可判定」挡在落库之前的地方。

---

## 5. 拆解期上下文快照

### 5.1 现状

拆解时实际注入的占位符只有五项（`PlannerDecomposeAsyncServiceImpl:214-223`）：

| 占位符 | 来源 | 是否够用 |
|---|---|---|
| `{{TASK_TITLE}}` | `task.title`（255 字） | 太薄 |
| `{{TASK_DESCRIPTION}}` | `task.description` | 取决于用户写多细 |
| `{{TASK_REQUIRED_SKILLS}}` | `task.requiredSkills` | — |
| `{{TASK_DIFFICULTY}}` | `agentPolicy.difficulty` | — |
| `{{REQUIREMENT_PACKAGE}}` | `task.context.requirementPackage` | 未走澄清链路时为空 |

对非代码任务这等于**让 Planner 蒙着眼睛拆**：做「Q3 竞品分析」时，它不知道历史报告在哪、竞品清单定没定、交付给谁做决策。

### 5.2 快照内容

新增 `{{TASK_CONTEXT_SNAPSHOT}}`，四类信息，**全部有预算上限**：

| 内容 | 取法 | 预算 |
|---|---|---|
| 附件清单 | 任务关联附件的名称 + 类型 + 可读性（**不注入正文**） | 20 条以内 |
| 知识库命中 | 以 `title + description` 检索，取 topN 的标题 + 一句摘要（**不注入全文**） | 5 条，每条 ≤100 字 |
| 历史同类任务 | 最近 N 条 title + 子任务标题（**给骨架参考，不给全文**） | 5 条 |
| 需求包 | 既有 `requirementPackage` | 沿用现预算 |

**设计原则：快照只给「有什么」，不给「是什么」。** 全文注入会撑爆预算，且会让 Planner 直接抄历史产出而不是重新拆解。

### 5.3 与 `TaskBaseline` 的分工（不合并）

| | 时机 | 用途 | 载体 |
|---|---|---|---|
| **上下文快照** | 拆解**前** | 拆解的输入视图 | Prompt 占位符，不落库 |
| **`TaskBaseline`** | 确认草案**后** | 执行期的运行基准（goal / constraints / raw） | `task_running_spec` 表 |

**两者不合并、不互换顺序。** 快照是「给拆解看的现状」，baseline 是「给执行看的契约」。当前 `TaskBaseline` 在 `finishConfirm` 里初始化（`PlannerAnalysisServiceImpl:203`）——这个时机对 baseline 本身没错，错的是此前**没有任何东西**填补拆解期的信息缺口。补的是快照，不是把 baseline 提前。

---

## 6. ledger 通道：给「计划未知」的任务

### 6.1 适用判据

静态 DAG 有一硬前提：**计划可以事先画出**。这在 `BUILD` / `DATA` / `OPERATE` 成立，在 `RESEARCH` / `DECIDE` 常常不成立——下一步做什么取决于上一步发现了什么。

**触发条件（任一）**：
- `archetype ∈ {RESEARCH, DECIDE}`
- 用户显式选择「探索模式」

**不触发的场景**：`archetype ∈ {BUILD, DOCUMENT, DATA, OPERATE}` 一律走现有静态 DAG。**ledger 不是升级，是针对另一类任务的另一条路。**

### 6.2 两个账本

**Task Ledger**（任务级内存，四类信息，与领域完全无关）：

| 字段 | 含义 | 与现有能力的关系 |
|---|---|---|
| `facts` | 任务里已给出的、已验证的事实 | 可从 `description` + 需求包派生 |
| `factsToLookUp` | 需要检索的（联网 / 附件 / 知识库） | 复用 `WebSearchService` / `ChatAttachmentService` / `KnowledgeBase` |
| `factsToDerive` | 需要计算或推理得出的 | — |
| `educatedGuesses` | 带标注的猜测，允许卡住时兜底 | **等价于现有 `uncertainties` 的 ASSUMPTION** |

> 关键：现有 `uncertainties`（`ASSUMPTION` / `UNCONFIRMED`）**已经是 facts / guesses 分离的雏形**。缺的只是 `factsToLookUp` / `factsToDerive` 两类，以及下面的自检层。

**Progress Ledger**（每步自检五问，**原文已核对** `_prompts.py:59-100`）：

1. 请求是否已完全满足？
2. 是否在原地打转（looping）？—— 原文补充：「循环**可以跨多轮**，且包含反复上下滚动超过若干次这类重复动作」
3. 是否在推进（forward progress）？—— 原文补充：判定为否的依据包括「陷入循环」**或「存在显著成功障碍，例如读不到必需的文件」**
4. 下一个该谁说话？（**从注入的候选人名单中选**，不是自由生成）
5. 该给它什么指令？（要求「像直接对这个人说话一样」，并带上它需要的信息）

**三处实现约束（照抄级细节，`[实测]` `_magentic_one_orchestrator.py`）**：

- **每问必须输出 `{reason, answer}` 两个字段**，reason 在前。这是可观测性设计——判定结果与判定理由同时留痕，出问题能查「它为什么认为有进展」。
- **`next_speaker` 必须在候选人名单内**，否则判为解析失败并重试（`_magentic_one_orchestrator.py:368-374`）。这是防幻觉的关键闸门：模型编一个不存在的 agent 名会被挡住。
- **单 agent 时确定性赋值** next_speaker，不浪费一次 LLM 输出（`:342-346`）。

**stall counter（`[实测]` `_magentic_one_orchestrator.py:393-406`）——是「净值计数」不是「累加计数」**：

```
if not is_progress_being_made:   n_stalls += 1
elif is_in_loop:                 n_stalls += 1
else:                            n_stalls = max(0, n_stalls - 1)   // 有进展则减 1
if n_stalls >= max_stalls:       跳出内层循环 → 更新 ledger → 重入外层
```

三条必须照做的要点：

1. **有进展时减 1（且不为负）**——这是「有限恢复预算」，让小错误不至于立刻触发重规划，但也**不会被后续进展完全抹掉**。纯累加的实现在长任务上会误触发重规划。
2. **判定优先级是 `is_progress_being_made` 先于 `is_in_loop`**（`elif` 结构）——两者同时为假时只 +1 不 +2，避免同一现象被双重计数。
3. **阈值语义是 `>=`，默认 `max_stalls = 3`**（`_magentic_one_group_chat.py:119`）。

> **订正（2026-10-24 代码级核对）**：本文初稿写「≤2 继续、阈值 2 取自论文实验值」，**两处均需修正**——论文的 ≤2 是**实验配置**（不是默认值），代码默认是 **3**。可作为实施默认，但应保持可配置。

**重规划的准确含义（`[实测]` `:270-298`）**：`_message_thread.clear()` 清掉**全部对话历史**，然后用 `(task, team, facts, plan)` 四项重建初始上下文。→ 所以「清空上下文」不是清空 ledger，而是**丢弃过程、保留结论**：facts 与 plan 留下，所有中间对话与子任务状态丢弃。

### 6.3 载体与边界

- **载体**：首期落 `task.context.ledger`（JSONB），**不新建表**。理由：ledger 是过程态，不需要关系查询；新表会引入与 `sub_task` 的双写分叉风险。
- **形态按字段分开（`[实测]` 参考实现的做法）**：
  | 字段 | 形态 | 理由 |
  |---|---|---|
  | `facts` | **自由文本字符串**（四个强制标题分段：GIVEN / TO LOOK UP / TO DERIVE / GUESSES） | 内容形态不固定（代码引用、数据、猜测混排），结构化反而限制表达 |
  | `plan` | **自由文本字符串**（bullet-point） | 同上；参考实现明确「plan 只作为思维链提示，不要求严格照做」 |
  | `progress` | **结构化**（五问固定 schema，每问 `{reason, answer}`） | schema 固定，且需要逐次留痕比对「判定是否漂移」 |

  > 取舍：不要为了「好查询」把 `facts` 结构化——参考实现用字符串是刻意的，因为事实的类型跨领域无法枚举。我方要查询的只是 `progress` 的历史趋势。
- **`facts` 的四个标题是硬约束**：参考实现在 prompt 里写明「DO NOT include any other headings」「DO NOT list next steps or plans until asked to do so」——**事实提取与计划生成是两次独立调用**，混在一次会互相污染（模型会先想计划再"倒推"事实）。
- **不替换现有调度**：ledger 模式的执行走**单步派发**（选人 → 下发 → 收结果 → 更新 ledger → 再选人），不走拓扑调度。`sub_task` 仍按一行一步落库，DAG 字段留空。
- **不做的**：不做 ledger 与 DAG 的自动互转；不做跨任务的 ledger 共享。

---

## 7. 与既有设计的关系（不动的部分）

明确列出不改的东西，防止实施时越界：

1. **`sub_task` 四要素契约不变**——`content` / `deliverable` / `acceptance` / `priority` 仍是必填四字段。本文只**新增** `acceptanceKind` 一列并给 `acceptance` 增加写法约束，不改四要素本身。
2. **Kahn 拓扑排序与环检测不变**——`PlannerDecomposeAsyncServiceImpl:557`（越界 / 自引用即拒）、`:614`（成环整批拒绝）是既有领先能力，`acceptanceKind` 与 `archetype` 都不参与图结构。
3. **契约先行规则不变**——`contract` 仍是「至多 1 个、必须第 1 位、无前置依赖」，但**改为 `BUILD` 专属**。其余 archetype 不强制（`DOCUMENT` 的契约是「读者决策链」，`DATA` 的是「数据 schema」，形态不同，不套用 `contract` 字段）。
4. **`PENDING_PLAN_REVIEW` 状态机不变**——草案态对 claim / 重派 / 补偿定时任务不可见的隔离语义保留。
5. **`GRANULARITY` 保留**——它管「拆多细」，`archetype` 管「按什么维度拆」，两者正交，不合并。

---

## 8. 明确不做

| 不做 | 为什么 | 依据 |
|---|---|---|
| **不做通用任务拆解** | 不存在这种东西。拆解维度与验收判据由交付物性质决定，只能分档不能统一 | `review/HelloAI 任务拆解方案对照与领域无关化设计（2026-10-24）.md` §一 |
| **不做 archetype 自动学习** | 样本量为 0 时任何学习都是噪声。先靠「模型判 + 人工可改」积累标注 | 同上 §3.2 |
| **不引入第二套执行调度器** | ledger 走单步派发即可，复用现有分发链。新调度器会让「DAG / ledger」两条执行路径的事实源分叉 | 本项目「同一事实双写必分叉」判据 |
| **不做跨 archetype 自动转换** | 交付物类型变了是**新任务**，不是原任务换骨架 | — |
| **不在拆解阶段判 archetype** | 拆解时模型只见 title + description，判不准 | 本文 §3.2 |
| **不改 `TaskBaseline` 的初始化时机** | 它的时机对它自己是正确的；缺的是拆解期快照，不是把 baseline 提前 | 本文 §5.3 |

---

## 9. 引用材料

- **差距判定依据**：
  - `review/HelloAI 草案生成与任务拆解 对照开源与 Qoder 的差距分析（2026-10-24）.md`
  - `review/HelloAI 任务拆解方案对照与领域无关化设计（2026-10-24）.md`
- **外部方案原始出处（2026-10-24 已升级为代码级核对，源码在本地 `E:\workspace`）**：
  - `microsoft/autogen`：`python/.../teams/_group_chat/_magentic_one/_prompts.py`（五问原文 / Task Ledger 四问 / JSON schema）、`_magentic_one_orchestrator.py`（stall 净值计数 `:393-406`、重规划 `:270-298`、next_speaker 白名单校验 `:368-374`）、`_magentic_one_group_chat.py:119`（`max_stalls=3`）
  - `eyaltoledano/claude-task-master`：`src/prompts/analyze-complexity.json`、`expand-task.json`、`update-task.json`、`parse-prd.json`；`scripts/modules/task-manager/expand-task.js:186-207`
  - `github/spec-kit`：`templates/commands/{specify,plan,tasks,clarify}.md`、`templates/spec-template.md`
  - `Magentic-One`：arXiv:2411.04468（**机制描述以论文为准，实现细节与默认值以 autogen 代码为准**——论文的 ≤2 是实验配置，代码默认 3）
  - Qoder Quest Mode：`docs.qoder.com/zh/user-guide/quest/spec-driven`（**源码不开源，仅据文档**）
- **差距表锚点**：`G-025`（任务拆解的领域无关化）；相邻项 `G-010`（Planner 能力感知与自适应粒度——`GRANULARITY` 出自此项，本文与其正交）、`G-011`（需求包与不确定性——`uncertainties` 字段出处，ledger 复用）
- **实施计划**：`plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md`
