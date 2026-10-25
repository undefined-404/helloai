# HelloAI 草案生成与任务拆解：对照开源与 Qoder 的差距分析

- 日期：2026-10-24
- 范围：需求澄清（clarify）→ 终稿（finalize）→ 草案生成（decompose）→ 草案确认（confirm）
- 对照基准：
  - `github/spec-kit`（spec.md → plan.md → tasks.md 三段式 + `[NEEDS CLARIFICATION]` 门控）
  - `eyaltoledano/claude-task-master`（parse-prd → analyze-complexity → expand 两段式 + 任务图）
  - Qoder Quest Mode 的 Spec 驱动（**仅依据官方文档，其源码不开源，未做代码级核对**）
- 本地核对范围：`ClarifyRoundOrchestratorServiceImpl` / `ClarifyReplyParser` / `PlannerAnalysisServiceImpl` / `PlannerDecomposeAsyncServiceImpl` / `SubTaskServiceImpl` / `SubTaskController` / `TaskController` / 三份提示词（`requirement-clarify.md` 132 行、`requirement-finalize.md` 91 行、`planner-decompose.md` 93 行）
- **勘察口径分级（2026-10-24 订正）**：本报告初稿把三个外部基准笼统写成「源码/文档级核对」，标重了。实际分级如下——`spec-kit` = **模板级实测**（command 模板即其行为本体，全文已读，覆盖 specify / plan / tasks / clarify + spec-template）；`claude-task-master` = **文档级**（README 未给任务 schema，实际依据是官方文档站 `task-structure` 一页；**未读 `parse-prd` / `expand-task` 实现源码**，其复杂度 1~10 分到「推荐子任务数」的映射究竟是代码算还是模型给，本轮未核实）；`Magentic-One` = **论文级**（arXiv:2411.04468 正文已读；**未读 AutoGen 实际 orchestrator prompt**，五问的原文措辞与 stall counter 实现未做代码级核对）；Qoder = **文档级**（源码不开源）。→ 受影响结论：§五「两段收敛」中 task-master 的复杂度量级描述、§八 转向 `TD-10` 的 ledger 落地细节，均需补代码级核对后方可作为实现依据。
- **上述缺口已于同日补齐（代码级核对完成）**：`microsoft/autogen` 与 `eyaltoledano/claude-task-master` 源码核对完毕（本地 `E:\workspace`），订正与本报告相关的两处——① task-master 的「复杂度 1~10 → 推荐子任务数」**没有映射表**，两个值都是模型一次输出的字段；② Magentic-One 的 `max_stalls` **代码默认 3**，论文的 ≤2 是实验配置。详见 `LOG-20261024-036` 与 `design/HelloAI 任务拆解领域无关化设计.md` §3.3 / §6.2。

---

## 一、结论先行

**差距不在「模型会不会拆」，而在两件事：**

1. **只有一次生成机会，且产物不可编辑。** 草案在 DB 里是一批行，不是一份能读、能改、能 diff 的文档；能改的只有三个运维标注字段，四要素本体和依赖都没有编辑出口。
2. **所有质量约束都是 fail-open。** 澄清没有「够了」的客观判别式（`progress` 是模型自评且零门控），终稿四小节不全只纠偏一次就放行，`openQuestions` 非空不阻断建任务。

**反而在「字段设计」和「写入校验」上，我方领先两家开源项目**（见 §5）——所以这次不是追赶，是补两块短板。

---

## 二、产物形态：最直接的体感差距

| | 产物 | 可读/可 diff | 可局部修改 |
|---|---|---|---|
| spec-kit | `spec.md` → `plan.md` + `research.md` + `data-model.md` + `contracts/` + `quickstart.md` → `tasks.md` + `checklists/requirements.md` | 全是仓库里的文本 | 人改文本后重跑命令 |
| task-master | `tasks.json` + 每任务一个 `.md` 文件 | 可 diff | `update-task` / `update-subtask` / `move` / `expand` 单条操作 |
| Qoder | Spec 文档（四段：需求描述 / 设计方案 / 任务拆分 / 验收标准），右侧面板**流式输出 + 可下载** | 可读可下载 | **对话修改** + **文档内行内批注** |
| **HelloAI** | `requirement_conversation.final_*`（JSONB）+ `sub_task` 行 | ❌ 只在 DB | 见 §6 |

**关键观察**：`requirement-finalize.md` 里 `description` 要求的四小节（背景与目标 / 范围与边界 / 交付物 / 验收标准）与 Qoder Spec 的四段**几乎完全对应**，`package` 五字段（goal / scope / outOfScope / acceptanceCriteria / assumptions）甚至更细。

> **我方已经把 Spec 的内容设计对了，但没有把它当「文档」交付出去。** 这是同一个东西的两种呈现——一种是 JSONB 里的字段，一种是用户可以指着某一行说「这句不对」的文件。

---

## 三、澄清：缺的是「停止判别式」

### 3.1 开源怎么决定「问 vs 猜」

**spec-kit `/specify`** 给出的是三条件判别式（AND），并且给了一个硬上限：

- 只有同时满足「显著影响范围或体验」+「存在多种合理解释且后果不同」+「**没有合理默认值**」才允许提问
- **硬上限 3 个标记**，超出的强制转为「智能猜测」
- 猜测必须写进 `Assumptions` 章节（不许静默假设）
- 优先级排序：`scope > security/privacy > UX > technical details`
- 判别口诀：**"Think like a tester"** —— 任何模糊需求都应无法通过「可测试且无歧义」检查

**spec-kit `/clarify`** 是独立命令，用 **9 类歧义分类法**逐类扫描并标记 `Clear / Partial / Missing`，再用 `Impact × Uncertainty` 启发式取前 5：

> Functional Scope / Domain & Data Model / Interaction & UX / Non-Functional Quality / Integration & External Deps / Edge Cases & Failure Handling / Constraints & Tradeoffs / Terminology & Consistency / Completion Signals / Misc Placeholders

并且**一次只问一题**、每题 2–5 个互斥选项或 ≤5 词短答、答完**立即写回 spec 对应章节**（`## Clarifications` + `### Session YYYY-MM-DD`）、停止条件明确（关键歧义解完 / 用户喊停 / 达 5 题上限）。

**Qoder** 的形态更产品化：选择题 + 三个动作 `Recommend`（一键采纳默认答案）/ `Continue`（手动选）/ `Skip`（跳过）。

### 3.2 HelloAI 现状

`requirement-clarify.md`（132 行）已有**六维度自检**：业务场景 / 功能范围 / 性能并发 / 安全合规 / 交付预算 / 边界排除。方向对，但缺三个东西：

| 缺口 | 证据 | 后果 |
|---|---|---|
| `progress` 无门控 | `ClarifyReplyParser:284,289,290` 只把 `progress` 塞进 payload 供前端展示，**全仓无任何消费点** | 模型可以 `progress=95` 继续追问，也可以 `progress=60` 直接出终稿，没有任何机制约束 |
| 无问题总量上限 | `ClarifyRoundOrchestratorServiceImpl:50` `MAX_ROUNDS = 20`，每轮 ≤2 题 ⇒ 理论 **40 题** | 对齐 spec-kit 的 3 / 5 上限，追问可能变成拉锯 |
| 无「没有默认值才问」判别式 | 六维度只是「检查清单」，不是「该不该问」的判据 | 能用行业默认值补的地方也会问，用户觉得在凑数 |

**这不是提示词写得好不好的问题，是提示词里没有可判定的条件。** spec-kit 的门控之所以硬，是因为它数的是「`[NEEDS CLARIFICATION]` 标记还剩几个」——一个客观可数的量。

---

## 四、门控：我方全链路 fail-open

| 检查点 | spec-kit | HelloAI |
|---|---|---|
| spec 还有未澄清标记就进 plan | **`ERROR on gate failures or unresolved clarifications`**（`plan.md` Key rules） | 无此概念 |
| 宪法/项目原则检查 | `constitution.md` 在 design **前**和 **后**各查一次，违反且无正当理由即 ERROR | 无项目级宪法；`finishConfirm` 里那句「平台约束：子任务按 DAG 依赖顺序执行…」是**硬编码字符串**（`PlannerAnalysisServiceImpl:198`）|
| 终稿四小节完整性 | 结构模板强制 | `applyDescriptionSectionGuard` **纠偏重试一次，fail-open 不阻断建任务**（注释原文：「fail-open，不阻断建任务」） |
| 未决问题是否阻断 | 未解决即 ERROR | `package.openQuestions` 非空**不阻断**建任务与拆解 |
| 草案结构校验 | 任务格式强约束（`- [ ] T001 [P] [US1] desc + 文件路径`） | ✅ 有硬校验（见 §5），但只校验**结构与引用合法性**，不校验**内容质量** |

一句话：**spec-kit 的门是关着的，我方的门都是虚掩的。** 每条约束都以「尽力而为 + 警告日志」收尾，所以「约束存在」和「约束生效」之间隔着一次静默降级。

---

## 五、拆解：单次抽样 vs 两段收敛（最大能力差距）

### 5.1 开源的层次

**spec-kit** 是四段递进，每段是独立命令、独立产物、独立人工关卡：

```
spec.md（用户故事 P1/P2/P3，每条有 Independent Test + Given/When/Then）
   ↓ plan.md（技术栈/结构 + Phase 0 research + Phase 1 data-model/contracts/quickstart）
   ↓ tasks.md（按 user story 分 Phase：Setup → Foundational → 每故事一个 Phase → Polish）
```

任务的**组织维度是 user story，不是模块**——每个 Phase 是一个「可独立开发 / 独立测试 / 独立部署 / 独立演示」的增量。`[P]` 标记显式声明可并行（不同文件、无未完成依赖）。

**task-master** 是两段收敛：

```
parse-prd（粗粒度任务 + dependencies）
   ↓ analyze-complexity（每任务 AI 打 1~10 分 + 推荐子任务数 + 定制 expand prompt）
   ↓ expand --id=8 / --all（按复杂度**从高到低**展开）
```

复杂度报告落在 `scripts/task-complexity-report.json`，`complexity-report` 可读版列出分布，`next` 按 `priority → 依赖数 → id` 选可执行任务，`validate-dependencies` 定期查环。

### 5.2 HelloAI 现状

`PlannerDecomposeAsyncServiceImpl` 是**一次 LLM 调用出全套**，`GRANULARITY`（FINE / STANDARD / COARSE）是**一次性参数**：

- 无「先粗后细」的第二段
- 无复杂度评估，因此无法判断「哪条需要再拆」
- 粒度选错 ⇒ 只能 `rejectPlan` 全量取消再 `decompose` 重来，而 `decompose` 还要求「无既有非 CANCELLED 子任务」（`PlannerAnalysisServiceImpl:89-93`）

**这就是「感觉差距大」的技术根因**：开源是「生成 → 看 → 挑 → 再生成」，我方是「生成 → 全接受或全丢」。前者允许一次不完美，后者要求一次到位。

---

## 六、修正能力：能改标注，不能改本体

这一条是最容易被误判的，按代码事实写清楚：

| 能力 | 现状 | 证据 |
|---|---|---|
| 改 技能指派 / 约束 / 不确定性 | ✅ **有** | `PUT /subTask/updateDraftById/{id}`（`SubTaskController:263`），fail-close 「仅 `PENDING_PLAN_REVIEW` 可编辑」（`SubTaskServiceImpl:110`），传 null = 不改（局部更新语义） |
| 改 标题 / 内容 / 交付物 / 验收 / 优先级（四要素本体） | ❌ **无 API** | `updateDraft` 签名只收 `requiredSkills, constraints, uncertainties`（`SubTaskServiceImpl:104`） |
| 改 依赖（dependsOn） | ❌ **无 API 出口** | `updateDependsOn` 是领域写入原语，`helloai-api` 侧搜索为 0 命中 |
| 单条再拆（expand） | ❌ 无 | 全仓无 expand / regenerate 于草案侧（`conversation/regenerateById` 是终稿重生成，非任务展开） |
| 整条重生成 | ⚠️ 仅全量 | `rejectPlan` + `decompose` |
| 对话式修改 Spec | ⚠️ 粒度是整条消息 | `editMessage(conversationId, messageId, newContent)`（`ClarifyRoundOrchestrator:226`）——改的是**一条消息重跑**，不是**文档中某一段** |

对照 Qoder 的 `3 审核 Spec`：

> 通过对话修改 Spec（点击「执行」前可随时调整）／也可在 Spec 文档中**针对具体内容添加批注**，把反馈直接交给智能体——**无需把意见单独整理成一段话发送**

**Qoder 的批注机制命中了我方最缺的一环**：用户看到「第 3 条任务验收写得不对」，不需要描述「第 3 条是哪个、哪里不对」，直接在那句话上批注。我方的 `editMessage` 要求用户把意见**重新组织成一段话**，粒度粗了一个数量级。

---

## 七、诚实结论：这四块我方领先

对齐开源时容易只看到不足，以下四项是实测领先的，属于已有资产，不要在重构中退掉：

1. **任务字段密度**（9 个 vs task-master 的 7 个）
   `content / deliverable / acceptance / priority / dependsOn / contract / requiredSkills / constraints / uncertainties`
   其中 **`contract`（契约先行：至多 1 个且必须第 1 位、产出全局注入下游）** 和 **`uncertainties`（ASSUMPTION / UNCONFIRMED 申报 + 继承规则）** 是 task-master 完全没有的。

2. **依赖校验在写入时硬拒**
   `PlannerDecomposeAsyncServiceImpl:557` 序号越界 / 自引用即拒，再用 **Kahn 拓扑排序做环检测**，`:614` 成环**整批拒绝**。task-master 是事后 `validate-dependencies`，spec-kit 只是格式约定。**我方是 fail-close，开源是事后补救**——这一点我方更严。

3. **验收可检查性约束**
   `planner-decompose.md` 要求「每条必须可检查」，给出「判定动作 + 预期结果」的模板，明确禁止「功能正常」「体验良好」。task-master 的 `testStrategy` 没有这层细则。

4. **草案态隔离干净**
   `PENDING_PLAN_REVIEW` 对 claim / assignNext / 自动重派 / 补偿定时任务全部不可见（它们只认 PENDING），无需额外隔离逻辑——这是状态机设计上的优势。

---

## 八、建议方向（**不排期**，可执行落点见 `plan/`）

> **本节是分析结论的方向提示，不构成计划。** 按《治理规则》§3.5，`review/` 不收待执行方案——排期、逐项验收与状态一律以下列为准：
> - 设计：`design/HelloAI 任务拆解领域无关化设计.md`
> - 计划：`plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md`（`TD-1`~`TD-10`）
>
> **重要订正（同日）**：本节的 P0-1 / P0-2 属**交互层**改造，已被 `LOG-20261024-034` 判定为未触及根因（用户指出本报告的对照基准全是编码工具）。真正的根因与方案见 `review/HelloAI 任务拆解方案对照与领域无关化设计（2026-10-24）.md`，本节保留仅作历史判断记录。

### P0 — 只加产物与门控，不动模型

1. **把 `progress` 从「自评展示」改成「客观门控」**
   终稿放行条件改为可观测量：`package.openQuestions` 为空 **且** 六维度无 `Missing` **且** 已问问题数 ≤5。不满足则强制 structured 追问（不是「建议追问」）。这是把 spec-kit 的 `[NEEDS CLARIFICATION]` 思路平移过来——**数个数就能判**。

2. **澄清加 `Recommend` 一键采纳**
   `requirement-clarify.md` 的 `questions[].options[].recommended` 字段**已经有了**，缺的是「一键采纳全部推荐项并继续」的交互动作。这是 Qoder 三个按钮里最省成本、体感提升最明显的一个。

3. **草案加四要素编辑出口**
   `updateDraft` 扩到 `title / content / deliverable / acceptance / priority`，复用现有 `PENDING_PLAN_REVIEW` fail-close 门禁即可，风险低。

4. **草案渲染成可下载的文档视图**
   `final_package` + 草案列表拼成一份 Markdown（四段：需求描述 / 设计说明 / 任务拆分 / 验收标准），前端给「下载」。**这是最便宜的「体感对齐 Qoder」**——不需要改任何生成逻辑。

### P1 — 加第二段拆解

5. **拆解后加复杂度评估（1~10）+ 按阈值 expand**
   两次调用：首次出粗粒度 → 评估 → 对 ≥阈值 的子任务单独展开。`GRANULARITY` 从「一次性参数」变成「让模型建议、让用户确认」。

6. **单条 expand / 单条 regenerate 接口**
   `POST /plan/expandById/{id}` + `POST /plan/regenerateById/{id}`。这是「一次不完美」的补救通道，比全量 reject 重来便宜得多。

### P2 — 结构性，收益慢

7. **项目级宪法**：把 `finishConfirm` 里硬编码的那句约束抽成按 module / 团队可配置的 `constitution`，拆解前注入、确认前校验。

8. **跨产物一致性机器校验**：`acceptanceCriteria` ↔ 子任务 `acceptance` 的覆盖关系，现在是提示词里的「P1-1 全覆盖」愿望，没有机器校验。spec-kit 的 `/analyze` 干的就是这件事。

9. **行内批注**（成本最高、体验最好）：在 §8.4 的文档视图上，允许用户选中一段提交意见，意见随 `editMessage` 的 payload 进下一轮。等价于 Qoder 的 annotation，但要求前端有文档渲染 + 选中态能力。

---

## 九、一句话总结

> HelloAI 的需求澄清与任务拆解，**内容设计已经对齐 Qoder 的 Spec 四段，字段密度和依赖校验甚至领先开源**；真正落后的是「把 Spec 当文档交付」和「给约束装上开关」这两件工程活。前者让用户能改，后者让约束真生效——都不需要更强的模型。
