# HelloAI 专项设计：Planner 能力感知与自适应粒度

> 主轴：让 Planner 拆解从「能力真空中的通用常识拆解」升级为「能力感知的专业拆解」——拆解侧注入技能目录、指派子任务级技能、按执行者画像与任务难度自适应调节 Plan 粒度。
>
> 定位：G-004 增量 A/B（requiredTools 联动 + 任务级技能透传）之后的拆解侧深化批次。设计依据 2026-09-09 代码事实核查（见 §0.2）。
>
> **状态：S1~S3 已实施（2026-09-09），S4 双场景实测 BLOCKED（待 dev 环境 + LLM Key + 外部 agent）**。拍板记录：2026-09-09 ①登记=新 G-010 ②粒度=rule-based ③装箱=并集 ④目录注入=常驻 ⑤constraints=显式列 ⑥回流=classpath ⑦混合粒度=FINE（白名单为空=STANDARD）。实施记录见 `doc/log/2026-09.md` 同日条目。

---

## §0 设计输入与约束

### 0.1 问题定义（为什么做）

「行动空间 + 反馈闭环」模型：模型只在给定行动空间里做选择，行动空间为空时它就是聊天工具。V2 已把反馈闭环建完（Event Stream 写侧 + Review + 对账收敛），行动空间补了一半（ToolExecutor 落地、requiredTools 联动已接），但**拆解侧仍在能力真空中工作**，产生两个已实证的失败来源：

1. **能力盲区**：Planner 不知道平台有哪些技能（eng-*）与工具，拆出的子任务无法被能力装配放大——声明了不存在的能力是幻觉，该用工具的地方退化成让模型「用文字假装做过」。
2. **粒度单一**：拆解粒度不区分执行者。同一个粗粒度子任务，外部强 agent（TeleAgent 实测：自主并行、效率递增）能发挥，内部兜底 LLM 会退化；反之，给强执行者过度细拆会束缚其自主性、增加协调开销。

代码实锤（2026-09-09 核查）：

- [planner-decompose.md](../../helloai-core/src/main/resources/prompts/planner-decompose.md) 仅 3 个占位符（标题/描述/`{{TASK_REQUIRED_SKILLS}}` 任务级透传），**无技能目录概念**，子任务输出 schema 无 `requiredSkills` 字段；
- [PlannerDecomposeAsyncServiceImpl](../../helloai-core/src/main/java/com/helloai/core/planner/service/impl/PlannerDecomposeAsyncServiceImpl.java) 对 `task.agent_policy` **零引用**——`difficulty`（LOW/MEDIUM/HIGH）与 `executorAgentIds` 信息存在但从不影响规划（difficulty 现仅作 N11 回退闸门）；
- requiredSkills 全链路为 **task 级单值**：装箱点（SubTaskServiceImpl / SubTaskDispatchServiceImpl / ExecutionCommandServiceImpl）一律取 `task.getRequiredSkills()`，子任务无自有技能声明；
- 外部 agent 感知通道缺失：pullTasks 仅拉收件箱通知，任务详情链路未下发技能标签——外部技能包无法按任务按需装配。

### 0.2 事实基线（已落地，本批不重做）

| 能力 | 状态 | 证据 |
|---|---|---|
| SkillPackage 元数据（8 字段） | ✅ | name/version/description/requiredTools/dependencies/inputSchema/outputSchema/validationRules/fileName |
| AgentSkillSpecService 三消费面 | ✅ | `resolve` / `resolvePackages` / `listPackages`（增量 A/B 已建） |
| requiredTools→tools 联动 | ✅ G-004 增量 A | Legacy/Runtime 双链 mergeTools 并集去重保序（a43735d） |
| SKILL_RESOLVED 携带 resolvedVersions | ✅ G-004 增量 A | Replay/前端按字段投影兼容 |
| 任务级技能透传拆解 | ✅ G-004 增量 B | `{{TASK_REQUIRED_SKILLS}}` + 提示词第 25 行技能对齐规则 |
| 外部轨迹加厚 | ✅ G-006 增量 C2 | AGENT_STARTED 埋点 + Replay 外部轨迹四事件（2daccea） |
| 契约先行机制 | ✅ V1 | `is_contract` + contract 全局注入下游 |
| 优先级继承 | ✅ N-006 C4-S1 | LLM 显式合法值优先，缺省继承 task.priority |

**本批净增量 = 拆解侧能力感知（目录注入 + 子任务级指派）+ 粒度自适应（执行者画像 × 难度调制）+ 外部感知通道 + 技能目录扩容回流规范。**

### 0.3 架构约束（红线）

1. **能力感知 ≠ 执行者绑定**：Planner 只声明「子任务需要什么能力」（Late Skill Binding），不做「谁来做」的决策——选人仍走 AgentSelector 现有链（G-008 之前不动）。与「节点落到角色槽位、不绑定具体 Agent 实例」红线一致。
2. **§6 依赖方向**：planner → agent 域（AgentSkillSpecService / AgentService 查执行者画像）为合法方向；**禁止** agent → task/planner 反向新增。
3. **不建平行架构**（CODE_STYLE §50.7）：技能目录消费复用 `AgentSkillSpecService`，不建 SkillRegistry 新中心；粒度决策为纯函数工具类，不建「规划引擎」。
4. **LLM 输出防御模式沿用现状**：新字段（requiredSkills/constraints/granularity）一律可选降级，解析失败/非法值不阻断拆解（与 contract 字段同模式）。
5. **Flyway 纪律**：新列走 V74 增量迁移（含 update_update_time_column 触发器与字段注释，循 V65/V66 风格），不改已提交 DDL。
6. **契约向后兼容**：外部通道（inbox payload / 任务详情 REST）只增可选字段，不破坏既有外部 agent（zip 技能包无需强制更新）。
7. **粗粒度「回退路径」的诚实口径**：G-005 沙箱未建前，粗粒度必带项不承诺真回滚，落为「止损回退动作 + 幂等性要求」。

---

## §1 核心设计决策

### D1：能力感知形态 = 「目录注入 + 指派声明」，不做能力校验拦截

拆解提示词注入技能目录摘要段（name + description + requiredTools 一行一条，来自 `listPackages()`），并扩展子任务 schema 允许 LLM 指派 `requiredSkills`。**指派是建议性输入**：落库前经目录命中过滤（未命中标签丢弃并记 timeline），执行侧 resolve 沿用既有两层过滤——不新增「指派了就必须有人能做」的硬校验（那是 G-008 capability 打分的职责，提前做会越界）。

### D2：子任务级技能存储与传递 = 新列 + 并集装箱

- `sub_task` 新增 `required_skills`（JSONB 数组，V74）；
- 装箱语义：**子任务级 ∪ 任务级**（去重保序，子任务级在前——具体优先展示）。理由：task 级是全局约束（用户声明），子任务级是拆解增强，并集保持「任务声明技能必被注入」的既有语义不变（存量任务 required_skills=[] 时子任务级独立生效，行为兼容）；
- 装箱点改造（现状 4 处 task 级取值 → 合并取值）：`SubTaskServiceImpl`（requiredSkillsOf / assignNext 路径）、`SubTaskDispatchServiceImpl`、`ExecutionCommandServiceImpl`（入参来源不变，调用方传合并结果）、审查命令装配（审查侧核验同源）。

### D3：粒度自适应 = 三档 × rule-based 决策器（先行），LLM 不自判粒度

```text
输入：task.agent_policy（executorAgentIds / difficulty / fallbackPolicy）
      ↓ PlannerGranularityResolver（纯函数，task→agent 方向查 accessType）
决策矩阵：
  执行者画像（白名单解析）              基准粒度
  ─────────────────────────────────────────────
  全部 CLI_CLIENT（外部强执行者）        COARSE
  全部 API_KEY_LLM（内部兜底）          FINE
  混合（明确强弱并存）                  FINE   ← 按弱者兜底（下限导向）
  白名单为空（不限定）                  STANDARD ← 不确定执行者不默认细拆，保持现状
  难度调制：difficulty=HIGH → 上移一档（COARSE→STANDARD→FINE）
           LOW/MEDIUM → 不变（保守，不下移）
```

「混合 → FINE」论证：细粒度对强执行者的代价（过度约束）小于粗粒度对弱执行者的伤害（无 DoD 即无法验收，Review 只能放水）——粒度决策的优化目标是**下限**，不是上限。

「白名单为空 → STANDARD」论证：不限定执行者时，平台可能派给外部强 agent 也可能派给内部兜底 LLM，无法可靠判断供给侧强弱；此时默认细拆会无谓束缚强执行者自主性，与「粒度不是越细越好」相悖——保持现状粒度（STANDARD）最保守、行为零变化，待白名单明确后再按强弱调节。

粒度三档定义（映射为提示词指令段，见 §2）：

| 档位 | 必带内容 | 子任务数量导向 |
|---|---|---|
| FINE（细） | 有序步骤（每步动作 + 中间产物）+ 每步指定 Skill + 输入契约 + 输出契约 + DoD（每步 ≥1 验证点） | 偏多（5~10），步骤可独立验收 |
| STANDARD（中，≈现状） | 四要素（目标/交付物/验收/优先级）+ 依赖 + 契约先行评估 | 3~10（现状规则） |
| COARSE（粗） | 目标 + constraints（不许改的事）+ 验收标准 + DoD（含止损回退动作 + 幂等性要求） | 宁少勿滥（3~5），禁止步骤级拆分 |

rule-based 先行的理由：粒度决策可解释、可回归（矩阵单测全覆盖）；LLM 自判粒度留作后续数据积累后的演进项，不进本批。

### D4：constraints（不许改的事）= 显式新列，不塞 context

`sub_task` 新增 `constraints`（text，V74）。理由：它是粗粒度模式的**一等公民字段**（用户可在草案确认时直接编辑、审查侧可核验「约束是否被遵守」），塞 context JSONB 会失去 schema 可见性与 UI 可编辑性。FINE/STANDARD 档可为空（NULL）。

### D5：技能目录扩容 = 贡献规范 + classpath（本批），DB 化不做

回流最小形态（外部自升级 agent 迭代技能 → 融入平台技能包）：

1. **贡献规范**：`skills/plugins/*.md` 文件承载 instructions；元数据登记于 `AgentSkillSpecServiceImpl.KNOWN_SPECS`（Java 声明，SkillPackage 构造）——两步合入即完成一个新技能包；
2. **校验脚本**：新增 `verify-skill-packages.ps1`（UTF-8 头强制）——校验 KNOWN_SPECS 声明的 fileName 均存在于 classpath、requiredTools 均命中 ToolRegistry、版本格式合法；<br>**〔后置缺口 2026-09-09〕本批未交付**，未在 S1~S3 内落地，登记待后续技能目录规模增长时补；
3. **DB 化 / 热加载**：明确不做。§50.7 不建平行 Registry 的边界解读：元数据从「代码内声明」演进为「运行时可写」是形态升级，涉及管理端/权限/版本治理，超出本批——登记为后续决策点，待技能目录规模（>10 个）或回流频次证明必要时再立项。

### D6：外部 agent 技能感知 = 下行通道增量（可选字段，向后兼容）

- inbox `sub_task.assigned` 通知摘要追加「技能要求: …」文本行（子任务级合并结果；AgentInbox 无结构化 payload，文本形态不动 inbox 契约面）；
- 任务详情 REST（子任务详情 / getDepsSummary 链路，S3 实施时按现有 DTO 核准落点）响应增加 `requiredSkills` 字段；
- SKILL.md 任务说明段补一行：外部 agent 收到 requiredSkills 后按自身技能包对应装配（可选行为，不强制）。
- **不动 zip 契约面**：新增字段对未升级的外部 agent 无感（JSON 未知字段忽略），与「外部链路代码路径零变更」的回归结论一致。

### D7：差距表登记 = 新条目 G-010（推荐）

本批跨 G-004（技能）/ G-008（执行者信息）与 Planner 提示词工程三个面，塞进 G-004 会让其失焦。推荐登记 **G-010「Planner 能力感知与自适应粒度」**（P1），G-004 保持「Capability 元数据与执行侧联动」的边界。已拍板（§7-1，新 G-010）。

---

## §2 提示词与 Schema 设计

### 2.1 planner-decompose.md 增量（三段）

**段 1：技能目录（新增，常驻注入）**

```markdown
## 平台技能目录（可指派给子任务）

{{SKILL_CATALOG}}
<!-- 渲染规则：每技能一行「标签 v版本 — 描述（依赖工具: a, b）」；目录为空时渲染「（平台暂无已登记技能包）」 -->
```

**段 2：执行者画像与粒度指令（新增，按 D3 矩阵渲染）**

```markdown
## 执行环境与拆解粒度

- 预期执行者：{{EXECUTOR_PROFILE}}（内部 LLM 兜底 / 外部 AI Agent / 混合）
- 任务难度：{{TASK_DIFFICULTY}}
- 本次拆解粒度：{{GRANULARITY}}（FINE / STANDARD / COARSE）

### 粒度指令（严格遵守）
[FINE]:  content 必须给出有序步骤，每步含动作与预期中间产物；逐步指派 requiredSkills；acceptance 每步至少一个可检查验证点；多模块协作必须评估契约先行。
[STANDARD]: 按四要素拆解（现状规则，不重复）。
[COARSE]: 只拆目标与边界，禁止步骤级拆分；constraints 必填（列出不许改动/不许越界的事项）；acceptance 必含止损回退动作与幂等性要求（如「重复执行不得产生重复副作用」；回退路径=止损回退动作，非事务回滚）。
```

**段 3：子任务 schema 扩展（输出格式节）**

```json
{
  "title": "...", "content": "...", "deliverable": "...",
  "acceptance": "...", "priority": "HIGH", "dependsOn": [],
  "contract": false,
  "requiredSkills": ["eng-doc-standard"],
  "constraints": "不得修改对外接口签名；失败时停止并上报，不得自动重试写操作"
}
```

注释规则：`requiredSkills` 只能取目录内标签（未登记标签将被丢弃）；`constraints` 仅 COARSE 必填、其余档位可空。

### 2.2 PlanDraftItem 扩展（PlannerAnalysisService）

```java
class PlanDraftItem {
    // 现有 7 字段不动
    /** 子任务级技能指派（可选；未命中目录的标签落库前过滤）。 */
    private List<String> requiredSkills;
    /** 执行约束（不许改的事；COARSE 档必填）。 */
    private String constraints;
}
```

### 2.3 渲染与装配（PlannerDecomposeAsyncServiceImpl）

- `renderPrompt` 扩展 4 个占位符渲染（目录 / 画像 / 难度 / 粒度）；
- 新增 `PlannerGranularityResolver`（task.policy 包，纯函数）：入参 policy + agent 域查询结果（`AgentService.listByIds` 批量取 accessType，一次调用），出粒度枚举 + 画像文案；
- `buildDrafts` 增量：`requiredSkills` 目录过滤（`SkillNormalizer.normalize` + KNOWN_SPECS 命中，未命中丢弃 + timeline 记 `task_plan_skill_filtered`，不阻断）；`constraints` 直接落库（COARSE 档缺失不阻断）。<br>**〔后置缺口 2026-09-09〕COARSE 档 constraints 缺失的 timeline WARN 级事件未实现**，与本节承诺不符，登记待补（见差距表 G-010 后置缺口④）。

---

## §3 数据与传递链变更

### 3.1 V74 迁移（sub_task 两列）

```sql
-- V74__subtask_skills_constraints.sql（示意，实施循 V65/V66 风格补触发器与注释）
ALTER TABLE sub_task ADD COLUMN required_skills JSONB DEFAULT '[]';
ALTER TABLE sub_task ADD COLUMN constraints TEXT;
COMMENT ON COLUMN sub_task.required_skills IS '子任务级技能标签（拆解侧指派，与任务级并集装箱）';
COMMENT ON COLUMN sub_task.constraints IS '执行约束（不许改的事；COARSE 粒度必填）';
```

存量行为：默认 `[]` / NULL——并集装箱下等价于现状（纯 task 级），零迁移数据。

### 3.2 传递链改造清单（S3 实施面）

| 环节 | 现状 | 改造 |
|---|---|---|
| SubTask 实体 | 无两字段 | 增字段（JacksonTypeHandler，同 dependsOn 模式） |
| 装箱点（SubTaskServiceImpl / SubTaskDispatchServiceImpl） | `task.getRequiredSkills()` | `mergeSkills(subTask, task)`：子任务级 ∪ 任务级去重保序 |
| 审查命令装配 | task 级同源 | 同一 merge 结果（核验与执行同清单） |
| inbox 通知摘要（sub_task.assigned） | 无技能字段 | 追加「技能要求: …」文本行（合并结果；文本形态不动 inbox 契约面） |
| 任务详情 REST | 无技能字段 | 增 `requiredSkills`（落点按现有 DTO 结构核准） |
| 草案确认 UI | 展示 7 字段 | 增展示/编辑 requiredSkills + constraints（可选编辑，空值放行） |

---

## §4 实施路线（S1~S4，小闭环推进）

| 步 | 内容 | 验证口径 |
|---|---|---|
| S1 数据层 | V74 迁移 + SubTask 实体/DTO + 单测（默认值/TypeHandler） | 存量回归：无新字段数据时全链行为零变化（编译 + 全量单测） |
| S2 拆解侧 | 提示词三段 + PlanDraftItem 扩展 + GranularityResolver + buildDrafts 过滤落库 | 矩阵单测全覆盖（4 画像 × 3 难度）；幻觉指派被过滤；非法 constraints 不阻断 |
| S3 传递链 | mergeSkills 装箱 4 点 + 审查同源 + inbox/REST 下行 + 草案确认 UI | required_skills 四段贯通（创建→拆解→派发→执行）；外部 agent 收到技能标签（dev 实测）；SKILL.md 增量 |
| S4 验证收口 | 真实任务双场景 + 文档回填（差距表 G-010 状态 / 迭代日志 / 介绍文档） | 场景 A（内部兜底）：FINE 拆解 + 技能注入 + requiredTools 联动生效（SKILL_RESOLVED 带版本）；场景 B（外部）：COARSE 拆解 + constraints 落库 + 外部按标签装配 |

依赖顺序：S1 → S2 → S3 → S4 严格串行（S2 提示词依赖 S1 字段存在，S3 依赖 S2 产出）；S3 的 UI 部分可与 S3 后端并行。

---

## §5 风险与防御

| 风险 | 防御 |
|---|---|
| 提示词膨胀（目录 >30 项时 token 爆） | 目录注入摘要形态（一行一技能）；目录超 20 项时截断至前 20 + 提示「更多技能见 constraints 声明规则」；S2 加渲染单测锚定行数上限 |
| LLM 指派幻觉标签 | 落库前目录过滤 + timeline 审计；与执行侧 resolve 两层过滤语义一致 |
| 新字段解析失败导致拆解中断 | 全部可选降级（null/非法 → 忽略该字段），沿用 contract 字段防御模式 |
| §6 方向违规（拆解服务反向依赖） | GranularityResolver 只经 AgentService 接口查询（task/planner → agent 合法）；目录只读 AgentSkillSpecService |
| 并集装箱改变存量语义 | 存量 sub_task.required_skills 恒为 [] → 并集退化为纯 task 级（数学等价）；S1 回归单测显式覆盖 |
| 外部 agent 忽略新字段 | 契约向后兼容设计（可选字段）；外部装配为可选行为，不作为验收硬门槛 |

---

## §6 验收口径（最终验收问答）

1. **能力感知**：给定带技能目录的平台，Planner 拆解产物中至少一个子任务携带合法 requiredSkills 指派，且执行侧 SKILL_RESOLVED 事件 payload 含该技能与版本（G-004 增量 A 链路自动衔接）。
2. **粒度自适应**：同一需求在「内部兜底」与「外部强执行者」两种 policy 下拆解，粒度分别为 FINE / COARSE，产物形态符合 §1-D3 表格定义；difficulty=HIGH 时 COARSE 场景上移为 STANDARD。
3. **约束落库**：COARSE 产物 constraints 非空且出现在草案确认 UI；审查侧可引用约束核验。
4. **外部感知**：外部 agent 拉取任务详情/inbox 可见 requiredSkills；未升级的外部 agent（旧 zip）行为无任何变化。
5. **存量回归**：agent_policy 缺省 + required_skills 空的存量任务，拆解产物结构、装箱行为、执行链事件序列与现状一致（粒度 STANDARD = 现状提示词规则）。

---

## §7 决策点清单（1~7 全部拍板，2026-09-09）

| # | 决策 | 选项 | 推荐 | 状态 |
|---|---|---|---|---|
| 1 | 差距表登记口径 | 新 G-010 vs 并入 G-004 | **新 G-010**（边界清晰，G-004 不失焦） | ✅ 已拍板：新 G-010 |
| 2 | 粒度决策模型 | rule-based 矩阵 vs LLM 自判 | **rule-based**（可解释可回归，LLM 自判留待数据积累） | ✅ 已拍板：rule-based |
| 3 | 装箱合并语义 | 并集（子任务∪任务） vs 子任务覆盖 | **并集**（保持任务级全局约束语义，存量等价） | ✅ 已拍板：并集 |
| 4 | 技能目录注入策略 | 常驻注入 vs 仅任务声明技能时注入 | **常驻**（3 项成本可忽略；回归口径按 §6-5 放宽为「结构不退化」） | ✅ 已拍板：常驻 |
| 5 | constraints 载体 | 显式列 vs context JSONB | **显式列**（UI 可编辑、审查可核验） | ✅ 已拍板：显式列 |
| 6 | 技能回流形态 | classpath+贡献规范（本批） vs DB 化 | **classpath**（DB 化登记为后续决策点） | ✅ 已拍板：classpath |
| 7 | 混合执行者粒度 | FINE（按弱者兜底） vs STANDARD | **FINE**（混合明确强弱并存时按弱者兜底；白名单为空=STANDARD，见 §1-D3） | ✅ 已拍板：混合=FINE / 白名单为空=STANDARD |
