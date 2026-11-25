# HelloAI 任务依赖与矛盾校验设计（规划期双闸）

> **Status：Planned**（机制已部分落地，本设计为系统化定案稿，实施立项与排期待用户拍板）
> **Scope**：把散落在 `DraftGateChecker` / `SemanticContractGate` / `RuntimeContractGate` 的既有校验机制系统化为「规划期双闸」专项——规则闸（确定性、可 block）+ 语义闸（LLM、只 warn），并定义扩展点（依赖方向正确性 / 矛盾检测 / 结果留痕）。**不含**：git 仓库集成（`GT-x` 承载）、执行期依赖就绪派发校验（既有 `SubTaskDispatchServiceImpl` 已承载）、前端校验结果可视化、`P0`~`P3` 架构重构主线阶段排期。
> **差距表锚点**：`G-033`（调研结论 5 的差异化自研区落点）
> 最后更新：2026-11-24

---

# 0. 定位与边界

## 0.1 问题来源

用户真问题（2026-11-24 调研原文转述）：「如何将一个最小可执行的任务正确拆分，验证任务之间依赖是否正确、是否有前后矛盾，进而确保不同外部 AI agent 对任务执行的质量和结果进行有效约束」。`research/HelloAI git 能力集成与外部代码任务调研（2026-11-24）.md` §3 结论 5 已论证：

- **git 不是「依赖/矛盾校验」的解药**（正交）：git 只覆盖「执行侧路径可信」，对「声明引导偏差」「reviewer coverage 声明缺失」无关；反向依赖成立（git 引入本身依赖约束机制）。
- **「规划期任务依赖 / 前后矛盾校验」无开源可抄**：各厂商均靠执行期测试兜底（CAID 的 manager 依赖图 + worktree 是学界最接近形态，验在 merge，不在规划期）；HelloAI 的 `DraftGateChecker`（Kahn 环检测）+ `confirmPlan` 唯一放行口在规划期静态闸上属领先位置，**应作为差异化自研区扩展**。

本专项即该结论的落点：不做「新造一个校验器」，而是把**已存在但分散**的校验机制系统化为「规划期双闸」统一形态，把欠缺口补全。

## 0.2 本设计做什么

| 层 | 现状（已落地） | 本专项扩展 |
|---|---|---|
| 规则闸 | `DraftGateChecker`（`helloai-common`，零外部依赖）：G1 依赖图（越界/自引用/环，Kahn）+ G2 单条字段写法 + G3 深度上限；**block 权只给确定性规则** | `DV-1` 依赖方向正确性规则（时序锚：接口→实现→调用；`DEFAULT` 类「先调研再写」可并行歧义提示） |
| 语义闸 | `SemanticContractGate`（`planner/policy`，G4「验收×上游约束」冲突）：只在 `confirmPlan` 前跑一次，传递上游收集，fail-open 留痕「语义门未执行」 | `DV-2` 矛盾检测扩展：批内验收标准互相矛盾 / 验收与产出形态冲突；`DV-3` 语义闸结论留痕（judge 快照 / 可人工复核） |
| 运行期"第三闸" | `RuntimeContractGate`（`task` 域，G5「改要求影响下游/上游」）：`ADR-008` 决策 17 = LLM 结论永无 block 权，只 warn；确定性已阻断即不调模型 | 规划期双闸与运行期 G5 的分工边界显式化（§3.1） |

## 0.3 不做什么

- **git 仓库注入 / 卷内 repo / repoPath 约束通道**：属 `GT-1`~`GT-9`（`G-033` 承载体），本专项只消费其「约束通道生效」后的形态，不重实现。
- **执行期依赖就绪派发校验**（`depends_on` 未就绪保持 `PENDING`）：既有 `SubTaskDispatchServiceImpl:244-249,300-306` 已承载，本专项不重复。
- **前端校验结果可视化 / 编辑冲突 UI**：本专项只定义后端机制与数据形态，前端接入另立。
- **把 LLM 语义结论升级为 block 权**：维持 `ADR-008` 决策 17「LLM 结论永无 block 权」——语义闸只给 warn，把风险摆给用户。

## 0.4 诚实边界

- 语义闸判定一次有界模型调用（30s），**超时/失败/输出不可解析均降级为 warn「语义门未执行」并留痕**——「规则存在」≠「路径上生效」（判据 1 原话），未执行必须可观测。
- 语义矛盾检测是**概率性**的：模型说「可能矛盾」是 warn 不是判决；规则闸才是确定性防线。语义闸的召回率不做承诺，优先保证不误伤（fail-open）。
- 规则闸 `RULE_VERSION = s5-3`，新增规则号后必须升版（`s5-4`）。

---

# 1. 建号声明（DV-x）

本专项建号 `DV-x`（Dependency Verification / 依赖与矛盾校验），`x` 取正整数。实施任务分解与排期属 `plan/`，本设计只定机制与判据。

| 编号 | 内容 | 归属层 |
|---|---|---|
| `DV-1` | 依赖方向正确性规则（时序锚 + 可并行歧义提示） | 规则闸（G1 扩展） |
| `DV-2` | 矛盾检测扩展（批内验收互斥 / 验收×产出形态冲突） | 语义闸（G4 扩展） |
| `DV-3` | 语义闸结论留痕与人工复核（judge 快照） | 语义闸（观测） |

与 `GT-x` / `TD-x` / `RV-x`·`SR-x` 并列；实测 `doc/**` 全库无 `DV-` 号段 ⇒ 不撞号；**刻意不用 `DEP-`/`VD-`**（语义过泛、易与「抽取（extract）」等混）；**不用「阶段 / Phase」**（违反治理规则 §6.5 分期标签冻结令）。

---

# 2. 现状（实测，2026-11-24）

## 2.1 规则闸：`DraftGateChecker`（`helloai-common`，零外部依赖）

- 判据唯一来源，`RULE_VERSION = s5-3`；`RULE_VERSION` 为**运行期强制版本**，落库时携带，版本不符即回滚/拒收。
- 常量即登记处：**所有 ruleId 只有一个登记处**（前端按 ruleId 分类展示时不两头找）。
- 门 1 + 门 2 全量判定 `check(...)`；单条编辑走 `checkFields`（`dependsOn` 不在任何编辑白名单，单条编辑不可能改变批内依赖图 ⇒ 无需门 1）——**「双写单向」防线下的合理裁剪**。
- block 权只给「结论与样本分布无关」的规则（判据 9：约束强弱看 fail-open/fail-close；本闸对结构类规则 fail-close）。

主要 ruleId：`G1_DEP_OUT_OF_RANGE`（悬空引用）/ `G1_SELF_DEP`（自引用）/ `G1_CYCLE`（环）/ `G2_BLANK` / `G2_LEN_MIN` / `G2_KIND_FORM_CONFLICT` / `G3_DEPTH_LIMIT`；gate 分区：`gate1_structure` / `gate2_quality` / `gate3_semantic`。

## 2.2 规划期语义闸：`SemanticContractGate`（`helloai-core/planner/policy`）

- `G4_UNSATISFIABLE_CONTRACT`：验收要求撞上**上游约束**——规则门看不见这一层（G1 只判依赖图、G2 只判单条、G3 只判层级，都不读「验收 × 上游约束」的语义关系）。
- **为什么落在 planner 而不进 `DraftGateChecker`**：完整判定依赖一次模型调用，而 `DraftGateChecker` 在 `helloai-common`、零外部依赖（不许调 LLM）。ruleId 常量仍登记在 `DraftGateChecker`，保持「所有 ruleId 一个登记处」。
- **只在确认前跑一次**：`confirmPlan` 是唯一放行口，也是唯一值得付一次模型调用的时点。拆解 / 展开 / 编辑三条生成与改造路径不调模型（改造类另有回滚兜底）。
- 判据纪律：`Node` 收集**传递上游**（`seen` 防环：门 1 判过环，这里只是防御脏数据）；跨批次依赖拿不到上游约束时规则门 G1 已单独报。
- fail-open 全类包裹：模型不可用 / 超时 / 空正文 / 输出非约定 JSON / 判定异常 → 一律折算 warn「语义门未执行」并留痕。

## 2.3 运行期"第三闸"：`RuntimeContractGate`（`helloai-core/task`）

- G5 语义门：**运行期改要求**（返工 / 编辑）后，判断该改动是否影响**下游依赖**（已分发 / 在飞 / PENDING 等待者）与**上游约束**。
- `ADR-008` 决策 17：**LLM 结论永无 block 权**——模型说「可能影响下游」时，正确动作是把风险摆给用户，而不是替用户否决整改。
- **确定性已阻断就不调模型**：确定性结论已给出可操作证据（哪条下游、哪个标识）时，不付模型调用；只有确定性层放行但语义存疑才调模型。语义降级为 warn「G5 语义门未执行」，不阻断正确整改，也不让「只过确定性层」静默消失。
- 装配视角：`【下游期待】`（谁依赖、下游的 content/acceptance 原文）+ `【上游产出/约束】` 双视角装配——与规划期 G4 的「上游约束」单视角形成互补。

## 2.4 缺口盘点（本专项补什么）

| 缺口 | 现状 | 本专项 |
|---|---|---|
| 依赖**方向**正确性 | G1 只判「图结构」（环/悬空/自引用），不判「方向是否合理」（如 `先调研后出方案` 实为可并行，被误标为依赖） | `DV-1` 方向规则：时序锚（接口→实现→调用）+ 可并行歧义提示（warn 不 block） |
| 批内验收标准互相矛盾 | G4 只管「验收 × 上游约束」，**不管「验收 × 验收」**（两条验收标准互相排斥） | `DV-2` 语义扩展：批内互斥验收检测 |
| 语义闸结论可复核性 | G4 只把 findings 落 `PlanDraftFindingsStore`，**不保留模型原始判断依据**（prompt 输入 / 原文引用） | `DV-3` judge 快照：原始 judging 上下文 + 冲突引文落库，人工复核可溯源 |

---

# 3. 扩展设计

## 3.1 双闸分工边界（显式化，防职责漂移）

```
规划期（confirmPlan 前）
├─ 规则闸 DraftGateChecker（确定性 · 可 block · fail-close on structure）
│   G1 依赖图 / G2 字段写法 / G3 深度上限 /（DV-1 方向规则）
└─ 语义闸 SemanticContractGate（概率性 · 只 warn · fail-open）
    G4 验收×上游约束 /（DV-2 验收×验收互斥）
运行期（requirement 改要求）
└─ 语义闸 RuntimeContractGate（G5 改要求×下游/上游 · 只 warn · 确定性先断）
```

- **block 权只属于规则闸**：`ADR-008` 决策 17 全链路适用（G4 / G5 / DV-2 一致）。
- **模型调用时点受控**：G4 只在 `confirmPlan` 前一次；G5 只在确定性放行且语义存疑时；DV-2 挂 G4 同一次调用判定（不新增调用）。
- **降级留痕统一口径**：语义闸任何失败 → `warn` + 「语义门未执行」+ 原因；等级与文案与 G4/G5 现状一致。

## 3.2 `DV-1` 依赖方向正确性（规则闸扩展，G1 内新增 ruleId）

| 项 | 设计 |
|---|---|
| 判据 | 批内成对 `dependee → dependent` 的「方向合理性」：`interface`（接口/契约）→ `impl`（实现）→ `caller`（调用方）是唯一强合理方向；其余（如 `调研 → 方案`、`数据核对 → 报告`）为「可并行歧义」 |
| 输入 | 草案行的 `content` / `title` 中可机械提取的时序词（实现/调用/依赖/基于/产出物名）——**机械提取，不调模型** |
| 输出 | 歧义依赖 → `G1_DEP_DIRECTION_AMBIGUOUS`（`warn`，写入 comment 提示「该依赖方向存疑：可能可并行」）；明确反时序（调用方依赖接口未定义）→ block |
| 版本 | `RULE_VERSION` 升至 `s5-4` |

**确定性优先**：方向判据能用机械规则判的绝不调模型（对齐 G5「确定性已阻断就不调模型」同款成本纪律）。

## 3.3 `DV-2` 矛盾检测扩展（语义闸扩展，G4 同次调用）

| 项 | 设计 |
|---|---|
| 判据 | 批内**验收标准互相排斥**（如「必须为纯 ASCII」vs「必须含中文说明」）、**验收与产出形态冲突**（如要求 `.jar` 可执行产物但交付声明为 `.md`） |
| 复用 | 挂在 `SemanticContractGate.judge` 同一次模型调用（复用 `renderUserPrompt` 的 Node 装配，不新增调用、不新增超时窗口） |
| 输出 | 命中 → `G4_ACCEPTANCE_MUTUAL_CONFLICT`（`warn`）；沿用现有 conflicts 装配路径（`MAX_CONFLICTS` 上限截断） |
| 失败 | 与 G4 同款降级：不新增失败面（模型不可用 →「语义门未执行」warn，不因新增判据拖垮 confirmPlan） |

## 3.4 `DV-3` 语义闸结论留痕（观测扩展）

| 项 | 设计 |
|---|---|
| 现状 | G4 findings 落 `PlanDraftFindingsStore`（唯一写点 `applyBatch`），但**不保留模型调用输入输出原文** |
| 扩展 | judge 快照：`{judgeAgentId, promptHash, rawResponse, conflicts[], created_at}` 随 findings 一并落库（新增表或 `task_plan_draft` 追加 JSONB 列——实施时按 `插入新列幂等 SQL + 生产级增量迁移` 纪律定） |
| 消费 | `confirmPlan` 被驳 / 转人工时，人工可读取模型"看到什么、判了什么、依据哪段原文"；评审报告可引用 |
| 边界 | 快照只记**判定依据**，不记非必要正文全文（`FIELD_LIMIT=400` / `UPSTREAM_LIMIT=5` 的既有截断保持） |

---

# 4. 演进边界（与相邻专项的关系）

| 相邻专项 | 关系 |
|---|---|
| `GT-x`（git 能力） | `DV-1` 方向规则与`GT-3`（`repoPath` 约束通道生效）正交：方向校验消费草案字段，git 消费文件树；**先约束后 git** 的次序结论不变 |
| `G-037` 任务约束类型化 | DV-2「验收×产出形态冲突」与 G-037 的「交付形态核验」（技能要求覆盖核验）同源互补：前者在规划期拦，后者在审查期拦，**不重复实现**（规划期拦不到才靠审查期兜底） |
| `ADR-008` 派生式子任务 | 运行期 G5 与本专项规则（`跨层依赖禁止`）一致；本专项不改派生机制，只补规划期校验强度 |
| `SubTaskDispatchServiceImpl` 派发校验 | 本专项管「规划期依赖是否写得对」，派发校验管「执行期依赖是否已就绪」——**时序分工，不重叠** |

---

# 5. 引用材料

- `research/HelloAI git 能力集成与外部代码任务调研（2026-11-24）.md` §3 结论 5（本专项立项依据）
- `plan/HelloAI git 能力集成实施计划（2026-11-24）.md` §0.3（引用本设计文件名）
- `ADR-008-task-decomposition-derivation.md`（决策 17「LLM 永无 block 权」、跨层依赖禁止、运行期上游向语义门）
- 代码锚点：`helloai-common/.../DraftGateChecker.java`（`RULE_VERSION=s5-3`、G1/G2/G3、all-ruleId 登记处）；`helloai-core/planner/policy/SemanticContractGate.java`（G4）；`helloai-core/task/service/impl/RuntimeContractGateServiceImpl.java`（G5）；`PlannerAnalysisServiceImpl.java`（confirmPlan 前语义闸接线）；`PlanDraftServiceImpl.java`（checkOne 接线）；`PlannerDecomposeAsyncServiceImpl.java`（拆解路径）