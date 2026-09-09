# HelloAI 专项设计：需求包准入与不确定性显式管理

> 主轴：让「需求理解」从自然语言文本升级为结构化需求包，让「推断与缺口」从静默写进计划升级为显式登记、分级消费——Planner 拿到的输入有边界，执行者知道哪里要验证， Reviewer 知道什么不能当缺陷驳回。
>
> 定位：G-010（拆解侧能力感知与自适应粒度）之后的**准入侧 + 契约侧**深化批次。方法论依据：阿里《"架构师 Agent" 系统化落地》需求准入（prd-digest）/ Gap 三分类 / 待确认项显式登记 / 事实回源原则，结合 ChatGPT / Kimi / 元宝三方反馈交叉验证（2026-09-09 讨论），压缩到 HelloAI 最小可执行形态。设计依据 2026-09-09 代码事实核查（见 §0.2）。
>
> **状态：设计落稿待实施（2026-09-09）**。拍板记录：2026-09-09 上轮方案讨论 ①登记=新 G-011 ②需求包=5 字段压缩版 ③存储=会话列+task.context 双写 ④uncertainties=显式 JSONB 列 ⑤gap_kind=后置到中期 ⑥不建自动闸门（人工裁决 + fail-close 上报）。

---

## §0 设计输入与约束

### 0.1 问题定义（为什么做）

外部参考（阿里原文 + 三方 AI 反馈）与本项目代码交叉核查，收敛出**同一个上游缺口**：G-010 解决了「Planner 不知道平台有什么能力」，但没有解决「Planner 不知道需求边界在哪里、哪些前提是猜的」。三个已实证的失败来源：

1. **终稿无结构**：澄清链路已有六维度自检（含第 6 维「边界与排除项：明确不做什么……必须显式写清」），但终稿产物是纯文本——`finalTitle`/`finalDescription` 压平了全部结构，不做项 / 待确认项随之消失在自然语言里。Planner 拿到的输入与用户直接手写一段描述无差别，「不做项」无法被机器约束，只能靠 LLM 阅读理解。
2. **推断伪装成事实**：拆解时 LLM 遇到信息缺口（「这个接口有没有存量调用方」「假设用户环境是 JDK17」），现状是静默写进计划。执行者不知道哪里需要自己验证；Reviewer 把「该验证没验证」当质量缺陷驳回 → 无效返工循环。
3. **缺口无分级出口**：执行者发现前提不成立时，唯一出口是整体失败上报；没有「这条是已申报假设（我可自行验证）/ 这条是待确认缺口（我验证不了，上报等裁决）」的分级动作。

代码实锤（2026-09-09 核查）：

- [RequirementClarifyServiceImpl.buildTaskFromDraft](../../helloai-core/src/main/java/com/helloai/core/planner/service/impl/RequirementClarifyServiceImpl.java) 仅写 `task.title` / `task.description`，`task.context` JSONB 只承载 runningSpec，无需求包概念；
- [requirement-clarify.md](../../helloai-core/src/main/resources/prompts/requirement-clarify.md) / [requirement-finalize.md](../../helloai-core/src/main/resources/prompts/requirement-finalize.md) 的 `final` 形态只有 `title` / `message` / `description` 三字段，六维自检产出无结构化出口；
- [planner-decompose.md](../../helloai-core/src/main/resources/prompts/planner-decompose.md) 拆解要求 9 条、子任务 schema 9 字段，无任何不确定性 / 实现路径申报字段；
- **G-010 后置缺口（本轮新发现）**：`sub_task.constraints` 落库后全项目无消费点（`getConstraints()` 仅拆解落库一处调用），[SubTaskExecutionServiceImpl.buildUserPrompt](../../helloai-core/src/main/java/com/helloai/core/agent/service/impl/SubTaskExecutionServiceImpl.java)「当前子任务四要素」段不含 constraints——约束声明了但执行者看不见，本批一并清偿。

### 0.2 事实基线（已落地，本批不重做）

| 能力 | 状态 | 证据 |
|---|---|---|
| 双模澄清（CHAT/CLARIFY + 意图确认卡） | ✅ | RequirementClarifyServiceImpl / ConfirmCardProtocol |
| 澄清六维自检（含边界与排除项维度） | ✅ 有意识 | requirement-clarify.md §六维度自检——维度存在但产出压平进 description 文本 |
| 澄清终稿建任务 + regenerate | ✅ | buildTaskFromDraft / regenerate（终稿在会话侧，任务可重建） |
| G-010 拆解侧能力感知 | ✅ S1~S3 | 技能目录注入 / 子任务级 requiredSkills / 三档粒度 / constraints 列 |
| 草案确认人工裁决环节 | ✅ | PENDING_PLAN_REVIEW 状态 + updateDraftById fail-close 端点 |
| BLOCKED 重排链 | ✅ | PAUSED → IN_PROGRESS → BLOCKED → PENDING → ASSIGNED |
| 审查事实数据源（蒸馏闭环原料） | ✅ | ReviewPort facts + agent_event + ImplicitScoreCalculator |
| V74 迁移风格基线 | ✅ | 头注释块 + ADD COLUMN IF NOT EXISTS + COMMENT + 验证日志 |

**本批净增量 = 需求包 schema（澄清终稿结构化）+ 子任务 uncertainties 一列 + 拆解继承规则 + 执行侧注入（含 constraints 补偿）+ 审查侧分级语义（含 constraints 核验）。**

**不吸收清单**（三方共识 + 判断标准「需要组织数据才能跑的不做，纯机制、数据由用户自带才做」）：KBase 业务知识库 / AITOM 架构图谱 / service-knowledge 体系 / 周期性校准机制 / 95% 完备度目标 / PRD 价值影响评估。后续演进项（gap_kind 实现路径分类、六维审阅清单 UI、LLM 二遍拆解、任务后蒸馏闭环、ContextSource 抽象）登记见 §4 批次说明，不进本批。

### 0.3 架构约束（红线）

1. **不建自动闸门**：openQuestions 存在不阻断拆解、不阻断派发——裁决点在草案确认（人工逐条处理）与执行侧（fail-close 走既有 BLOCKED 链上报），与「不确定场景 pause for manual handling」哲学一致，但不新增 fail-block 拦截器。
2. **不建平行架构**：需求包解析为静态工具类（同 TaskAgentPolicy 模式），不建「需求管理中心」；uncertainties 消费并入既有审查轨道 A，不新增审查轨道。
3. **§6 依赖方向**：需求包 schema 定义与解析在 planner 域（澄清构造、拆解消费），存储在 task 域（conversation / task.context / sub_task）——planner → task 为合法方向，无反向新增。
4. **LLM 输出防御模式沿用**：package / uncertainties 一律可选降级，解析失败 / 非法值不阻断主流程（与 contract / requiredSkills / constraints 同模式）。
5. **Flyway 纪律**：新列走 V75 增量迁移（循 V74 风格），不改已提交 DDL。
6. **契约向后兼容**：REST / inbox 只增可选字段；外部 agent 未升级无感知（JSON 未知字段忽略）。
7. **命名消歧**：`uncertainties[].kind` 取 `ASSUMPTION` / `UNCONFIRMED`（不用 GAP，为未来 `sub_task.gap_kind` 的「实现路径分类」语义预留命名空间——后者对齐阿里 Gap 分析三分类，本批后置见 D4）。

---

## §1 核心设计决策

### D1：需求包 schema = 5 字段压缩版（三方建议并集的裁剪）

```json
{
  "goal": "可验收目标（一句话）",
  "scope": ["需求范围条目"],
  "outOfScope": ["明确不做项"],
  "assumptions": ["关键假设（推断项，须标注）"],
  "openQuestions": ["待确认 / 阻断项"]
}
```

裁剪口径：元宝的 `reviewTriggers`（专项评审触发项）不取——组织级评审节奏，违反判断标准；`blockers` 不独立成列，并入 `openQuestions`（是否阻断由草案确认人工裁决，不建自动闸门）；不加任务级 `acceptanceCriteria`——子任务 acceptance 已承接验收职责，避免双份验收口径漂移。`goal` / `scope` 与 description 文本部分重叠，保留理由：拆解提示词需要**可机器引用的边界字段**（D5 继承规则按字段引用），重叠成本可接受（渲染时三者并列注入）。

### D2：存储 = 会话列 + task.context 双写

- `requirement_conversation` 新增 `final_package`（JSONB，V75）：**终稿的权威存储**。regenerate 路径依赖会话终稿重建任务，需求包必须存活于会话侧；每次终稿轮覆盖写（与 finalTitle / finalDescription 同模式）。
- `task.context.requirementPackage`（复用现有 JSONB，无新列）：**拆解链读取点**。`buildTaskFromDraft` 建任务时双写；context 键空间与 runningSpec 隔离（创建期仅 requirementPackage，运行期 runningSpec 后续合入，键不冲突）。
- 不走澄清链路的任务（TaskController.create 直建）：无需求包，拆解渲染占位文案（见 §2.1），行为等于现状。

### D3：uncertainties = 显式 JSONB 列，kind 二值分级

`sub_task` 新增 `uncertainties`（JSONB，V75）：

```json
[{"kind": "ASSUMPTION", "note": "假设用户环境为 JDK17"},
 {"kind": "UNCONFIRMED", "note": "该接口是否有存量调用方未确认"}]
```

- `ASSUMPTION`（已申报假设）：拆解时做出的推断，执行者可自行验证 / 推翻；**审查侧不因该假设的存在而驳回**（见 D7）。
- `UNCONFIRMED`（待确认缺口）：无法由现有信息证实，执行者**须先验证再动手**；验证不了走既有 BLOCKED 链上报。
- 非法 kind 处理：**降级 UNCONFIRMED + timeline 记 `task_plan_uncertainty_degraded`**（不丢弃——不确定性信息丢失比保留更危险；降级到更严语义符合 fail-close）。与 G-010 幻觉标签「丢弃」模式的差异理由：requiredSkills 目录是闭合集合（未命中即无意义），uncertainties 的 note 是自由文本（标注本身有信息量）。

### D4：gap_kind = 后置到中期（G-008 能力可验证基线就绪后），本批不做

`sub_task.gap_kind`（REUSE/EXTEND/NEW_BUILD 实现路径分类）**不进本批**，登记为批次二（§4）。

后置理由（2026-09-09 上轮结论修正）：

1. **「已有能力」须可最小验证，而非纯文本匹配**：REUSE/EXTEND 的判定依据是「平台/仓库确有可验证、可行使的能力」。当前拆解 LLM 仅经 G-010 看到平台技能目录（文本态清单），既不能区分「平台技能」与「仓库代码级能力」的边界，也无法验证该能力「实实在在可用」——此刻采集 gap_kind 的标注可靠度不足，产出是噪声而非资产。
2. **能力可验证基线后置**：判定「已有能力」的权威基线 = 能力完成核验后才计入目录。外部 AI agent 注册的技能须经「技能测试任务」核验通过后方可采信（非注册即采信，属后续功能优化方向）；内部技能须经 G-010 后置缺口 D5-2 `verify-skill-packages.ps1` 校验锚定。基线就绪后 gap_kind 才有客观分类依据，届时随 G-008 成本路由一并接入（采集侧 schema 扩展同步补，避免现在加无用列）。
3. **价值预埋不变**：REUSE → 近零成本（内部弱模型优先）/ EXTEND → 中档 / NEW_BUILD → 最高（外部强执行者）。接入时序归口 G-008，本批不占列、不占 schema。

### D5：拆解继承 = 提示词硬约束 + timeline WARN 兜底，不建代码校验拦截

- 提示词三条硬约束（§2.1）：
  1. **outOfScope 内条目不得出现在任何子任务的目标 / 内容 / 交付物中**（边界硬约束）；
  2. **openQuestions 逐条评估，与某子任务相关的必须继承进该子任务 uncertainties（kind=UNCONFIRMED）**；
  3. **assumptions 逐条评估，与某子任务强相关的必须继承进该子任务 uncertainties（kind=ASSUMPTION）**（全局适用的任务级假设由需求包段常驻渲染承载，不逐条落子任务，避免冗余）。
- 代码侧仅做兜底审计：任务 openQuestions 非空但拆解产物无任何子任务携带 uncertainties 时，timeline 记 WARN 级 `task_plan_uncertainty_missing`（不阻断落库）——与 G-010 后置缺口④（COARSE constraints 缺失 WARN）同模式，本批一并实现，顺手清偿该欠账。**仅审计 openQuestions → UNCONFIRMED，不审计 assumptions**：assumptions 已在需求包段常驻渲染、LLM 可见，丢失风险低于 openQuestions；且「全局假设 vs 子任务相关假设」边界模糊，代码侧无法可靠判定，故只做提示词硬约束、不加 WARN。

### D6：执行侧注入 = buildUserPrompt 三段增量，顺带清偿 G-010 constraints 缺口

[SubTaskExecutionServiceImpl.buildUserPrompt](../../helloai-core/src/main/java/com/helloai/core/agent/service/impl/SubTaskExecutionServiceImpl.java)「当前子任务」段增三行（空值零注入）：

1. **执行约束**（`constraints` 非空时）——清偿 G-010 后置缺口（约束落库后执行者不可见）；
2. **不确定性申报**（`uncertainties` 非空时）：ASSUMPTION 条目后缀「（可自行验证，推翻即上报）」、UNCONFIRMED 条目后缀「（须先验证再动手，无法验证则 BLOCKED 上报）」；
3. **事实回源声明**（固定文本，无占位符，常驻注入）：

```text
验收事实回源：生产系统当前行为以代码与配置为准；业务意图以本任务描述与已确认需求包为准；
历史兼容行为不得在未声明的情况下「优化」移除。
```

外部 agent 同步下行：任务详情 REST（SubTaskResponse）增 `uncertainties` 可选字段；inbox 通知摘要在 uncertainties 非空时追加「待确认: N 项」文本行（D6 of G-010 同款文本形态，不动 inbox 契约面）；SKILL.md 任务说明段补一行（可选行为，不强制）。

### D7：审查语义 = 不确定性分级 + 约束遵守核验，并入既有轨道 A

[subtask-review.md](../../helloai-core/src/main/resources/prompts/subtask-review.md) 增量（不新增轨道）：

- 待核验子任务段增两行（由 [ReviewExecutionEngine](../../helloai-core/src/main/java/com/helloai/core/review/support/ReviewExecutionEngine.java) 渲染）：
  - `{{CONSTRAINTS}}`：执行约束（非空时注入「执行约束：…」，空时「（无）」）——**清偿 G-010 后置缺口③（审查侧 constraints 未消费，ReviewExecutionEngine 此前仅替换 8 占位符）**；
  - `{{UNCERTAINTIES}}`：不确定性申报，空时「（无）」。
- 轨道 A 补两条：
  - **ASSUMPTION 类申报不构成驳回理由**（按「假设是否被产出尊重」核验，而非「假设是否存在」）；**UNCONFIRMED 类申报的产出中须含验证结论或 BLOCKED 上报痕迹**，既未验证也未上报的按不达标处理；
  - **执行约束（constraints）核验**：产出不得违反 constraints 声明的「不许改/不许越界」项（例：constraints=「不得改对外接口签名」而产出含接口签名变更 → 按不达标驳回）；constraints 为空时本条跳过。

---

## §2 提示词与 Schema 设计

### 2.1 planner-decompose.md 增量（一段 + 一条拆解要求 + schema 一字段）

**段：需求包（新增，置于「待拆解任务」段之后）**

```markdown
## 需求包（结构化准入产物）

{{REQUIREMENT_PACKAGE}}
<!-- 渲染规则：五字段逐项列表（空数组字段不渲染）；无需求包时渲染
「（本任务未经过澄清链路，无结构化需求包——按任务描述拆解）」 -->
```

**拆解要求增补（第 10 条）**

```markdown
10. **不确定性申报（uncertainties，G-011）**：拆解中做出的推断标注为
    {"kind":"ASSUMPTION"}，无法证实的信息缺口标注为 {"kind":"UNCONFIRMED"}。
    继承规则：需求包 assumptions 中与该子任务强相关的条目须继承（kind=ASSUMPTION）；
    需求包 openQuestions 中与该子任务相关的条目必须继承（kind=UNCONFIRMED）。
    禁止把推断 silently 写进目标而不申报。
```

**硬约束（拆解原则段增补）**

```markdown
- 需求包 outOfScope 内条目不得出现在任何子任务的目标、内容或交付物中（边界硬约束）。
```

**输出 schema 扩展**

```json
{
  "title": "...", "content": "...", "deliverable": "...",
  "acceptance": "...", "priority": "HIGH", "dependsOn": [],
  "contract": false,
  "requiredSkills": ["eng-doc-standard"],
  "constraints": "...",
  "uncertainties": [{"kind": "UNCONFIRMED", "note": "该接口是否有存量调用方未确认"}]
}
```

注释规则：`uncertainties` 可空数组（无不确定性时 `[]`）。

### 2.2 澄清终稿形态扩展（requirement-clarify.md / requirement-finalize.md 同步）

`final` 形态增可选 `package` 字段（两处提示词同步，终稿说明引导语补一句）：

```json
{"type": "final", "progress": 100, "title": "...", "message": "...",
 "description": "结构化需求描述",
 "package": {
   "goal": "...", "scope": ["..."], "outOfScope": ["..."],
   "assumptions": ["..."], "openQuestions": ["..."]
 }}
```

提示词约束增补：package 从对话中提炼，**推断项必须进 assumptions 并在 description 同步标注（推断），不得伪装成用户确认过的事实**；六维自检第 6 维（边界与排除项）的产出落 outOfScope；各数组可为空（`[]`），不得为凑格式虚构条目。

### 2.3 解析与落库（PlannerAnalysisService / RequirementClarifyService）

```java
class PlanDraftItem {
    // 现有 9 字段不动
    /** 不确定性申报（可选；非法 kind 降级 UNCONFIRMED）。 */
    private List<Uncertainty> uncertainties;
}

record Uncertainty(String kind, String note) {}
```

澄清侧：`ClarifyReplyParser` 解析 `package`（缺失 / 非法 → null，降级纯文本终稿 = 现状行为）；`RequirementConversation.finalPackage` 落库；`buildTaskFromDraft` 双写 `task.context.requirementPackage`。

需求包解析静态工具（planner 域，同 TaskAgentPolicy 防御式模式）：`RequirementPackageParser.parse(Map<String, Object>)` → 防御式五字段读取，键缺失 / 类型异常返回对应默认值（空集合），拆解侧渲染与后续消费统一走此入口。

---

## §3 数据与传递链变更

### 3.1 V75 迁移（两列）

```sql
-- V75__g011_uncertainty_management.sql（示意，实施循 V74 风格补头注释与验证日志）
ALTER TABLE sub_task ADD COLUMN IF NOT EXISTS uncertainties JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE requirement_conversation ADD COLUMN IF NOT EXISTS final_package JSONB;

COMMENT ON COLUMN sub_task.uncertainties IS '不确定性申报（JSONB 数组：kind=ASSUMPTION 已申报假设/UNCONFIRMED 待确认缺口；拆解侧指派，执行/审查侧分级消费）';
COMMENT ON COLUMN requirement_conversation.final_package IS '澄清终稿结构化需求包（goal/scope/outOfScope/assumptions/openQuestions；终稿轮覆盖写）';
```

存量行为：默认 `[]` / NULL——全链零变化（无需求包渲染占位文案，无 uncertainties 零注入）。

### 3.2 传递链改造清单（S4 实施面）

| 环节 | 现状 | 改造 |
|---|---|---|
| SubTask / RequirementConversation 实体 | 无两字段 | 增字段（uncertainties 用 JacksonTypeHandler，同 dependsOn 模式） |
| 澄清终稿解析 | final 三字段 | ClarifyReplyParser 增 package 解析（可选降级）+ 会话落库 |
| 建任务 | 只写 title/description | buildTaskFromDraft 双写 task.context.requirementPackage（regenerate 自动复用） |
| 拆解渲染 | 7 占位符 | 增 {{REQUIREMENT_PACKAGE}}（RequirementPackageParser 防御式读取） |
| buildDrafts 落库 | requiredSkills 过滤 / constraints 直落 | 增 uncertainties kind 降级 + 一类 timeline 审计 + D5 兜底 WARN |
| 内部执行 prompt | 四要素段 | 增三段：constraints 补偿 / uncertainties 分级后缀 / 事实回源声明（空值零注入） |
| 审查装配 | 八占位符 | ReviewExecutionEngine 增 {{CONSTRAINTS}} + {{UNCERTAINTIES}} 渲染（空时「（无）」） |
| 任务详情 REST | 无一字段 | SubTaskResponse 增 uncertainties 可选字段 |
| inbox 摘要 | 技能要求行 | uncertainties 非空时追加「待确认: N 项」文本行 |
| 草案确认 UI | 展示/编辑 requiredSkills + constraints | 增展示/编辑 uncertainties（逐条增删改 kind/note，同 DraftUpdateRequest 模式，空值放行） |

---

## §4 实施路线（S1~S5，小闭环推进）

| 步 | 内容 | 验证口径 |
|---|---|---|
| S1 数据层 | V75 迁移 + SubTask / RequirementConversation 实体 + RequirementPackageParser + 单测 | 存量回归：无新字段数据时全链行为零变化（编译 + 全量单测）；解析器防御式用例全覆盖 |
| S2 澄清侧 | 两处终稿提示词 package 扩展 + ClarifyReplyParser + 会话落库 + buildTaskFromDraft 双写 | final 带 package 全链落库；package 缺失/非法降级纯文本终稿（= 现状）；regenerate 双写复用 |
| S3 拆解侧 | planner-decompose.md 一段一要求 + PlanDraftItem 一字段 + buildDrafts 降级/审计落库 + D5 兜底 WARN | openQuestions 继承可见；非法 kind 降级 UNCONFIRMED；越界拆解由实测核验 |
| S4 传递链 | buildUserPrompt 三段 + ReviewExecutionEngine + subtask-review.md 语义 + REST / inbox 下行 + 草案确认 UI | 内部执行 prompt 三段贯通（空值零注入）；审查 prompt 含 constraints + uncertainties；外部 REST 可见；UI 可编辑 |
| S5 实测收口 | 真实任务双场景 + 文档回填（差距表 G-011 / 迭代日志 / 介绍文档） | **与 G-010 S4 合并执行**（同一环境一轮双场景：场景 A 内部兜底 / 场景 B 外部 agent，同时核验 G-010 六维与 G-011 五维验收口径） |

依赖顺序：S1 → S2 → S3 → S4 严格串行；S5 与 G-010 S4 合并（两者改动面在执行实测处汇合，省一次环境搭建）。G-010 S4 的 BLOCKED（dev 环境 + LLM Key + 外部 agent）**不阻塞** G-011 S1~S4 开工——本批改动面是澄清侧 + 提示词 + 装配链，与实测解耦。

**批次边界**（上轮方案批次划分的落点）：本文档承载批次一（G-011 主体）；批次二（gap_kind 采集与成本路由接入，依赖 G-008 能力可验证基线 + 六维审阅清单 UI 侧栏 + G-010 欠账 verify-skill-packages.ps1）与批次三（任务后蒸馏闭环：DONE 后 best-effort 跑 distill 模板 → 候选知识 markdown → 人工按 G-010 D5 贡献规范合入，不自动回写）另行登记，不进本批验收口径。

---

## §5 风险与防御

| 风险 | 防御 |
|---|---|
| 提示词膨胀（需求包 + 目录 + 粒度指令三段叠加） | 五字段逐项列表、空数组字段不渲染；S3 渲染单测锚定行数上限（G-010 §5 同款） |
| 澄清 LLM 产出非法 package JSON | fail-open 降级纯文本终稿 = 现状行为，不阻断建任务（ClarifyReplyParser 防御式） |
| LLM 滥用 uncertainties 甩锅（该查的标待确认） | openQuestions 在草案确认时逐条人工裁决（UI 展示）；审查侧「UNCONFIRMED 须验证或上报」核验兜底 |
| context 键空间冲突 | requirementPackage 与 runningSpec 键隔离；创建期仅写 requirementPackage，S1 回归单测显式覆盖 |
| §6 方向违规 | 需求包 schema/解析器在 planner 域，存储在 task 域（planner → task 合法）；uncertainties 消费在 agent/review 域读 task 域实体（既有合法方向），零反向新增 |
| 存量会话/任务无需求包 | 键缺失渲染占位文案；列默认空零注入；全链行为零变化（G-010 兼容模式复用） |
| 双写不一致（会话侧 vs task.context） | 会话列为权威源，regenerate 以会话终稿重建双写；task.context 仅拆解链读取，不承担权威职责 |

---

## §6 验收口径（最终验收问答）

1. **需求包贯通**：澄清终稿含 package → `task.context.requirementPackage` → 拆解 prompt 需求包段逐字段可见；未走澄清的任务渲染占位文案、拆解行为与现状一致。
2. **边界守卫**：outOfScope 非空的需求，拆解产物中无任何子任务的目标 / 内容 / 交付物包含越界条目（S5 实测人工核验）。
3. **不确定性继承**：openQuestions 至少一条落到相关子任务 uncertainties（kind=UNCONFIRMED）；任务有 openQuestions 但产物零 uncertainties 时 timeline WARN 已记。
4. **执行注入**：内部执行 prompt 含不确定性申报段（分级后缀）+ 事实回源声明 + constraints（COARSE 非空时）；外部 agent 经任务详情 REST 可见 uncertainties；旧外部 agent（未升级 zip）行为无变化。
5. **审查语义**：ASSUMPTION 类申报不构成驳回理由（审查提示词实测：假设被产出尊重即通过）；UNCONFIRMED 既未验证也未上报 → 按不达标驳回；constraints 非空时产出违反「不许改/不许越界」项 → 按不达标驳回。
6. **存量回归**：无 package / uncertainties=[] 的存量任务，澄清、拆解、执行、审查全链行为与现状一致（编译 + 全量单测 + 双场景实测对照组）。

---

## §7 决策点清单（1~6 全部拍板，2026-09-09 上轮方案讨论）

| # | 决策 | 选项 | 拍板 | 状态 |
|---|---|---|---|---|
| 1 | 登记口径 | 新 G-011 vs 并入 G-010 | **新 G-011**（G-010=拆解侧，G-011=准入侧+契约侧，同 G-010 D7 边界论证） | ✅ 已拍板 |
| 2 | 需求包 schema | 三方并集 vs 压缩版 | **5 字段压缩版**（不取 reviewTriggers / blockers 并入 openQuestions / 不加任务级 acceptanceCriteria） | ✅ 已拍板 |
| 3 | 需求包存储 | 会话列 vs task.context vs 双写 | **双写**（会话列为权威源，regenerate 依赖会话终稿；task.context 为拆解链读取点） | ✅ 已拍板 |
| 4 | uncertainties 载体 | 显式列 vs context JSONB | **显式 JSONB 列**（同 G-010 D4 论证：草案 UI 可编辑、审查可引用；kind=ASSUMPTION/UNCONFIRMED 消歧命名） | ✅ 已拍板 |
| 5 | gap_kind 时机 | 采集即路由 vs 采集先行 vs 后置 | **后置到中期**（G-008 能力可验证基线就绪后随成本路由一并接入；REUSE→弱 / EXTEND→中 / NEW_BUILD→强） | ✅ 已拍板（2026-09-09 上轮二次修正） |
| 6 | openQuestions 处置 | 自动闸门 vs 人工裁决 | **人工裁决**（草案确认逐条处理 + 执行侧 fail-close 走既有 BLOCKED 链；不建 fail-block 拦截器） | ✅ 已拍板 |

**本轮起草新增的落地决策**（随文档审阅一并确认）：

| # | 决策 | 取值 | 理由 |
|---|---|---|---|
| 7 | 非法 kind 处理 | 降级 UNCONFIRMED + timeline | 不确定性信息不丢弃；降级到更严语义符合 fail-close（与 requiredSkills 丢弃模式的差异：闭合集合 vs 自由文本） |
| 8 | G-010 constraints 执行侧缺口 | 本批 S4 一并清偿 | 缺口为 G-011 注入点的同一改动面（buildUserPrompt），拆开做两次无意义 |
| 9 | D5 兜底 WARN 与 G-010 缺口④ | 本批 S3 一并实现 | 同为 buildDrafts 的 timeline WARN 模式，顺手清偿欠账 |
| 10 | G-010 constraints 审查侧缺口 | 本批 S4 一并清偿 | 审查侧注入 {{CONSTRAINTS}} + 轨道 A 约束遵守核验（D7），与执行侧注入（决策 8）构成 constraints 完整闭环，避免留半截 |
