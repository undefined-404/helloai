# HelloAI 实现差距表

> **状态：CURRENT GAP**
>
> 本文只记录当前 → 目标的真实差距。
>
> 最后更新：2026-09-10

# 1. 总体矩阵

| ID | 能力 | 当前状态 | 目标 | 优先级 | 处置 |
|---|---|---|---|---|---|
| G-001 | Agent Event Stream | 已有 Run/Turn/Step + Event 基础 + Timeline 并轨（A6）+ Replay/Audit 读侧（A7） | 统一事件契约和消费体系 | **P0** | P0-A 完整闭环（A1~A7 已落地，验收全量成立） |
| G-002 | Executor 迁移 | Runtime 契约单轨 + 真身 + 主链接线注入（runtime-enabled 开关，默认 Legacy 零变化） | Runtime 成为唯一执行契约，旧实现退出 | **P0** | P0 主线收官 + 真灰度闭环：2026-09-08 dev 真身联调（RuntimeAgentLoop 点亮）/ 对账全绿 / 回滚零差异 / 外部 Agent 回归通过 / 真实任务全链闭环（外部端到端 14 分钟 5 子任务零故障，见 log 2026-09-08） |
| G-003 | AgentRuntime | 八件套已全部落地（Context / Session / Skill / Tool / Loop / Event / Environment / SandboxProvider 契约） | Context + Session + Skill + Tool + Loop + Event + Sandbox | **P0** | P0 完整闭环（P0-A/B/C 收官）；真实 provider tool-calling 循环 2026-09-08 联调通过，无边界问题 |
| G-004 | Skill Capability | SkillPackage 元数据层已落地（name/version/description/requiredTools/dependencies/inputSchema/outputSchema/validationRules，3 个 eng-* 已结构化）+ **requiredTools→tools 联动已接**（Legacy/Runtime 双链 mergeTools 并集去重保序，TOOL_RESOLVED 前合并）+ **SKILL_RESOLVED 携带 resolvedVersions**（Replay/前端按字段投影兼容）+ **拆解技能通路已接**（task.required_skills → 拆解 Prompt 注入，规划/验收与技能规范对齐；增量 B） | Metadata / Version / Tools / Schema / Dependencies 全量 + **requiredTools→tools 联动** + SKILL_RESOLVED 携带版本 + 真实任务行使（任务级创建/拆解/派发/执行四段已贯通；带 required_skills 真实任务实测已于 2026-09-10 完成，见处置列） | **P1** | 元数据层（ed14e40 / 234bed4）+ 联动接线（增量 A）+ 拆解技能通路（增量 B，全量 1295 单测 0 失败）落地；真实任务带 required_skills 端到端实测已于 2026-09-10 完成（Round2 全任务 eng-doc-standard 硬门槛准入 + Round3 技能分布派单与 135:20 分排序实证，见 log 2026-09-10） |
| G-005 | Sandbox Provider | 已有 Environment / Provider + SandboxProvider 契约（诚实策略，无 ISOLATED） | 真正 Provider 化执行环境与隔离策略 | **P1** | 契约已落地；Docker/K8s 隔离能力后置 |
| G-006 | Replay / Audit | 写侧+对账闭环；Timeline 已暴露（API+UI）；Replay/Audit 读侧 service 就绪；**Replay/Audit API 已暴露**（增量 C1：helloai-api AgentEventController——GET /api/agent-events/traceByRunId/{runId} + GET /api/agent-events/pageAuditByTaskId/{taskId}，API 层 DTO 投影 + ControllerTest 5 用例）+ **UI 工作台已上线**（/event-stream 事件流：Replay 轨迹时间线 + Audit 分页表格 + eventType 过滤 + payload 原文折叠；事件字典抽离 utils/eventMeta 与 SubTaskDetail 时间线同源共享）+ **增量 D**（traceByTaskId / traceBySubTaskId 任务/子任务维度端点 + 工作台选择器 / Agent 名称解析 / payload 结构化展开 / 事件流深链） | 基于统一 Event 查询/回放；**外部执行轨迹对齐**（外部路径 agent_execution_record 0 行、事件仅完成态，Replay 时外部任务仅「派发→完成」细线） | **P1** | 增量 C1 落地（2026-09-08）：Timeline ✅ / Replay ✅ / Audit ✅（API+UI，api 56 单测 + type-check/build 全绿）；增量 C2 落地（2026-09-08）：外部认领埋点 AGENT_STARTED + Replay run 级汇总卡（core 799 单测 + type-check/build 全绿），外部轨迹加厚为「AGENT_STARTED → AGENT_COMPLETED → REVIEW_STARTED → REVIEW_APPROVED」四事件；增量 D 落地（2026-09-10）：任务/子任务维度查询端点 + UI 工作台增强（选择器 / 名称解析 / payload 展开 / 深链） |
| G-007 | Quality Gate | Reviewer 闭环已存在 | Rule + Test + LLM 统一决策 | **P2** | 现有链上增强 |
| G-008 | Agent Fleet Routing | 已有 Agent 选择机制；**多外部执行者同台已实证**（2026-09-10 Round3：双执行者背靠背竞态 231ms 唯一赢家 / 技能硬门槛内按分排序 / 2 执行者 3 子任务并行持有）；外部执行 tokens=null（成本观测盲区，submitResult 未回传） | Capability + Health + Load + Policy | **P2** | 渐进升级；多外部执行者对照场景已于 2026-09-10 验证完成（见 log）；token 回传（成本观测）仍为推进前置 |
| G-009 | Dynamic Workflow | 已有模板/实例化/DAG | 动态分支、复杂运行期编排 | **P3** | 后置 |
| G-010 | Planner 能力感知与自适应粒度 | **S1~S4 已落地（2026-09-09）**：S1 数据层（V74 sub_task.required_skills JSONB + constraints TEXT）+ S2 拆解侧（技能目录常驻注入 / 子任务级 requiredSkills+constraints 指派 / 目录过滤 task_plan_skill_filtered 审计 / rule-based 粒度三档 FINE/STANDARD/COARSE + 目录超 20 项截断）+ S3 传递链（mergeSkills 并集装箱 5 装箱点同源 / inbox 技能要求行 / REST 下行 / 草案确认 UI 展示编辑 + updateDraftById 端点 / SKILL.md 增量） | 技能目录注入拆解 Prompt + 子任务级 requiredSkills/constraints 指派（V74 新列，并集装箱）+ 粒度三档 FINE/STANDARD/COARSE 自适应（**rule-based 决策矩阵**：执行者画像 × difficulty 调制，2026-09-09 拍板）+ 外部感知下行通道（可选字段向后兼容）+ 技能回流贡献规范 | **P1** | S1~S3 PASS（2026-09-09，见 log）；S4 实测平台内链 PASS（2026-09-09，与 G-011 S5 合并执行）——verify-login-e2e 10 项 / verify-requirement-clarify 10 步 / verify-planner-decompose 12 步 EXIT=0（登录 → 澄清 → 终稿 → 建任务 → AI 拆解 → 确认/拒绝闭环）；外部执行链（外部 EXECUTOR agent 场景）已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log）；**后置缺口**：①技能回流贡献规范（D5-3 DB 化）②verify-skill-packages.ps1 校验脚本（D5-2 未交付）；③审查侧 constraints/requiredSkills 注入核验（D4 验收口径）④COARSE 档 constraints 缺失的 timeline WARN 级事件（设计 §2.3 承诺）已由 G-011 S3/S4 清偿（2026-09-09） |
| G-011 | 需求包准入与不确定性显式管理 | **S1~S5 已落地（2026-09-09）**：S1 数据层（V75 双列 sub_task.uncertainties JSONB DEFAULT '[]' + requirement_conversation.final_package JSONB + 双实体字段 + Uncertainty 值对象落 task 域避反向依赖 + RequirementPackageParser 防御式静态工具）+ S2 澄清侧（两处终稿提示词 package 结构化五字段 + ClarifyReply.package JsonNode 防御承接 + updateFinalDraftFields 条件覆盖写 + buildTaskFromDraft 双写）+ S3 拆解侧（模板四增量 + uncertainties 落库归一 + COARSE constraints 缺失 WARN + D5 兜底审计）+ S4 传递链（执行注入 D6 三段 / 审查核验 D7 双占位符 + 轨道 A 第 8/9 条 / REST 下行四参 updateDraft / inbox 待确认行 / 草案 UI 不确定性列）+ S5 实测（平台内链 PASS，与 G-010 S4 合并执行） | 结构化需求包（goal / scope / outOfScope / assumptions / openQuestions，会话列 + task.context 双写）+ 拆解继承为 sub_task.uncertainties[ASSUMPTION|UNCONFIRMED] 显式 JSONB 列 + 执行侧注入（含 constraints 补偿）+ 审查侧分级语义（假设不成立 ≠ 执行缺陷）+ 外部下行可选字段向后兼容 | **P1** | S1~S4 PASS（2026-09-09，见 log：V75+实体+解析器 10 单测 / 澄清侧 / 拆解侧 / 传递链各增量，core 全量单测 1359 用例 0 失败，api 编译 + UI type-check 通过）；S5 实测（2026-09-09，与 G-010 S4 合并）：平台内链 PASS——澄清终稿 final_package jsonb 真实落库（jsonb 定点写 bug 修复：Mapper+XML ::jsonb 条件写，RequirementClarifyServiceTest 92 用例全绿）+ 确认卡 selections 协议核验 + 异步拆解轮询适配（360s 窗口）；外部执行链已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log：审查者引用 uncertainties 申报作驳回依据实证）；**不建自动闸门**（openQuestions 不阻断，人工裁决 + fail-close BLOCKED 链上报）；gap_kind 实现路径分类与任务后蒸馏闭环后置批次二/三；技术债：Agent 注册幂等顺序缺陷仍在（validateModelType 先于 registerOrGet；配套的 api_key_hash 落库缺列已于 2026-09-10 修复）；配套修复：JSONB uncertainties CCE 死锁（inbox 通知静默失败 → 外部链死锁根因，2026-09-10） |

# 2. P0 主线

## G-001 Event Stream

验收：

- Legacy 与 Runtime 产生同一 Event Model；
- Timeline / Audit 逐步统一从 Event 获取事实；
- 一个 Run 可以按 sequence 重建轨迹；
- Event 不成为第二业务状态源；
- 写入具备幂等和可对账能力。

## G-002 Dual Executor

原则：

> Dual Executor 只是迁移策略，不是长期架构。

```text
ExecutionRouter
      ↓
 ┌────┴─────┐
 ↓          ↓
Runtime    LegacyAdapter
```

禁止：

```text
复制完整业务链
复制第二套状态机
复制第二套 Review
无幂等地再次执行副作用
```

## G-003 AgentRuntime

推荐提取顺序：

```text
1. Context
2. EventRecorder
3. ToolRegistry / ToolExecutor
4. AgentLoop
5. Session
6. Sandbox
```

# 3. P1

### Skill Capability

保持 Markdown 兼容，同时增加：

```text
version
requiredTools
dependencies
inputSchema
outputSchema
validationRules
```

### Sandbox Provider

第一阶段只完成 Provider Contract，不把 Docker/K8s 当作已完成安全隔离。

### Event Consumers

顺序：

```text
Timeline / Replay
→ Audit
→ Recovery
→ Fork
```

### Planner 能力感知（G-010）

设计：`doc/design/Planner_Capability_Awareness.md`（2026-09-09 落稿，§7 决策 1~7 全部拍板）。

已拍板：

```text
登记口径：新 G-010（不并入 G-004）
粒度决策：rule-based 矩阵（执行者画像 × difficulty；LLM 自判后置）
装箱语义：并集（子任务级 ∪ 任务级，去重保序，子任务级在前）
目录注入：常驻（每技能一行摘要，超 20 项截断）
constraints：显式列（仅 COARSE 必填）
回流：classpath 声明态（DB 化/热加载后置）
混合粒度：FINE（白名单为空 = STANDARD）
```

实施状态（2026-09-09）：

- S1 数据层：PASS（V74 迁移 + SubTask 实体/DTO + 单测）；
- S2 拆解侧：PASS（提示词三段 + PlanDraftItem 扩展 + PlannerGranularityResolver 矩阵单测 + buildDrafts 目录过滤落库）；
- S3 传递链：PASS（mergeSkills 并集装箱：SubTaskAutoExecutionDispatcher / ReviewServiceImpl / SubTaskReviewServiceImpl / SubTaskController.execute / SubTaskDispatchServiceImpl 选人约束五点同源；inbox summary 技能要求行；SubTaskResponse REST 下行；草案确认 UI 展示/编辑 + updateDraftById fail-close 端点；executor SKILL.md 子任务级指派说明）；
- S4 双场景实测（2026-09-09，平台内链）：PASS——本机 docker 四件套 + 6565 后端 + 5173 前端实测「登录 → 平台 PLANNER 注册/绑定（API_KEY_LLM + DeepSeek）→ 需求澄清终稿 → finalize 建任务 → 异步拆解（经 findPlanByTaskId 轮询）→ 确认/拒绝」全闭环；verify-login-e2e 10 项 / verify-requirement-clarify 10 步 / verify-planner-decompose 12 步全过（EXIT=0，与 G-011 S5 合并执行，见 log 2026-09-09）。外部执行链（外部 EXECUTOR agent 场景）已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log 2026-09-10）。

验证基线：core 全量单测 0 失败；api 模块编译通过；UI type-check 通过。

### 需求包准入与不确定性显式管理（G-011）

设计：`doc/design/Requirement_Package_Uncertainty.md`（2026-09-09 落稿，§8 决策 1~10 全部拍板）。

已拍板：

```text
登记口径：新 G-011（G-010=拆解侧，G-011=准入侧+契约侧，同 G-010 D7 边界论证）
需求包：5 字段压缩版（goal / scope / outOfScope / assumptions / openQuestions）
存储：会话列 + task.context 双写
uncertainties：显式 JSONB 列（kind=ASSUMPTION / UNCONFIRMED；命名与 gap_kind 消歧）
gap_kind：后置到批次二（「已有能力」须可最小验证，依赖 G-008 能力可验证基线）
闸门：不建自动闸门（openQuestions 不阻断；人工裁决 + fail-close BLOCKED 链上报）
```

实施状态（2026-09-09）：

- S1 数据层：PASS（V75 迁移 sub_task.uncertainties JSONB DEFAULT '[]' + requirement_conversation.final_package JSONB，含列注释与验证日志；SubTask 实体 uncertainties（JacksonTypeHandler 同 dependsOn 模式）+ RequirementConversation 实体 finalPackage；Uncertainty 值对象落 task 域（kind=ASSUMPTION/UNCONFIRMED 常量，避开 task→planner 反向依赖）；RequirementPackage record + RequirementPackageParser 静态工具（planner 域，同 TaskAgentPolicy 防御式模式：键缺失/类型异常回落空集合、数组元素仅保留字符串；fromContext 键空间隔离；render 五字段逐项列表空数组字段不渲染）；单测 10 用例覆盖全字段/降级/键隔离/渲染；core 全量单测 1329 用例 0 失败）；
- S2 澄清侧：PASS（两处终稿提示词 requirement-clarify.md / requirement-finalize.md 同步增 package 五字段结构化输出要求——goal/scope/outOfScope/assumptions/openQuestions + 提炼约束（推断项进 assumptions 且 description 同步标注（推断）、六维自检第 6 维产出落 outOfScope、数组可为空不得虚构条目）；ClarifyReply 增 package 字段（JsonNode 防御接收防非法类型击穿解析 + @JsonProperty 映射关键字键名）；ClarifyReplyParser.resolvePackage 防御式解析（缺失/非对象形态 → null 降级纯文本终稿 = 现状行为）；updateFinalDraftFields 定点写扩展 final_package 条件覆盖写（null 不动列），runFinalizeLlmRound（/task 直出）与 runLlmRound（澄清轮）两终稿轮同步扩展；buildTaskFromDraft 建任务双写 task.context.requirementPackage（regenerate 复用会话侧需求包自动双写；与 runningSpec 键空间隔离）；单测 7 用例（终稿带/无/非法 package 三态 + finalize/regenerate 双写 + 两模板契约防回退）；core 全量单测 1336 用例 0 失败）；
- S3 拆解侧：PASS（planner-decompose.md 模板四增量——占位符清单增 {{REQUIREMENT_PACKAGE}} + 「需求包（结构化准入产物）」段（明确 openQuestions=经人工确认的待确认缺口、assumptions=已申报推断项，拆解须按下文继承规则逐条评估）+ 拆解要求第 10 条 uncertainties 申报与继承规则（assumptions 强相关条目继承 ASSUMPTION、openQuestions 相关条目必须继承 UNCONFIRMED、禁止把推断 silently 写进目标）+ 拆解原则增 outOfScope 边界硬约束（明确排除项绝不拆进任何子任务）+ schema 增 uncertainties 字段（可空数组，kind 只能取 ASSUMPTION/UNCONFIRMED）；PlanDraftItem 增 uncertainties（JsonNode 防御承接，非数组/转换失败回落空列表不阻断拆解）；renderPrompt 增 {{REQUIREMENT_PACKAGE}} 渲染（RequirementPackageParser.render(fromContext) 统一入口，无需求包渲染占位文案行为零变化）；buildDrafts 三增量——uncertainties 落库（非法 kind 降级 UNCONFIRMED 不丢弃 + timeline task_plan_uncertainty_degraded 审计；空白 note 丢弃；与 G-010 幻觉标签丢弃模式差异理由：note 是自由文本标注本身有信息量，降级到更严语义符合 fail-close）+ COARSE constraints 缺失 timeline task_plan_constraints_missing WARN（G-010 缺口④清偿；粒度判定提前 doDecompose 一次判定 renderPrompt 与 buildDrafts 共用）+ D5 兜底审计（需求包 openQuestions 非空但拆解产物无任何 UNCONFIRMED 继承 → task_plan_uncertainty_missing WARN，仅审计 openQuestions→UNCONFIRMED 不审计 assumptions，不阻断落库）；单测 11 用例（渲染/占位/落库/降级审计/非数组防御/COARSE 两态/D5 三态）+ 模板契约测试扩 planner 1 用例防回退；core 全量单测 1347 用例 0 失败）；
- S4 传递链：PASS（五个消费点全链路落地——①内部执行 prompt：buildUserPrompt 四要素段后增 D6 三段（执行约束补偿行（G-010 缺口清偿：约束落库后执行者不可见）+ 不确定性分级申报（ASSUMPTION 后缀「可自行验证，推翻即上报」/ 其余含人工编辑非法值一律 UNCONFIRMED 语义后缀 fail-close）+ 验收事实回源声明固定文本常驻，空值零注入）；②审查装配：ReviewExecutionEngine 增 {{CONSTRAINTS}}/{{UNCERTAINTIES}} 两占位符（空时渲染「（无）」）+ subtask-review.md 待核验段两行 + 轨道 A 增第 8 条执行约束遵守核验（G-010 缺口③清偿）与第 9 条不确定性分级核验（ASSUMPTION 不构成驳回理由、UNCONFIRMED 产出须含验证结论或 BLOCKED 上报痕迹，否则不达标）；③REST 下行：SubTaskResponse + toResponse 同步 uncertainties + DraftUpdateRequest/Controller/Service 四参 updateDraft 链（null=不修改/空数组=清空/空白 note 丢弃/非法 kind 降级 UNCONFIRMED，D3 fail-close 同口径）；④inbox 摘要：ASSIGNED 分支 UNCONFIRMED 计数>0 追加「待确认: N 项」（ASSUMPTION 不计入，纯文本形态不动 inbox 契约面）；⑤草案确认 UI：实体/API 类型扩展 + PlanReviewDialog 不确定性列（「N 项待确认 · M 项假设」压缩展示）与编辑弹窗逐条增删改（kind 下拉 + note 输入 + 保存前过滤空行）；单测 12 用例（执行 prompt 四用例：注入/零注入/空列表/非法降级 + updateDraft 归一化三用例 + 审查渲染两用例 + inbox 摘要两用例 + subtask-review 模板契约一用例）；core 全量单测 1359 用例 0 失败；api 编译通过；UI type-check 通过）；
- S5 实测（2026-09-09，与 G-010 S4 合并执行）：PASS（平台内链）——①jsonb 定点写 bug 修复：updateFinalDraftFields 原 lambdaUpdate.set(Map) 直绑 SQL 被 PG 拒（wrapper 路径不套用实体 JacksonTypeHandler），改 RequirementConversationMapper + XML `#{finalPackageJson}::jsonb` 条件写（null 不动列，参照 SubTaskMapper.updateDependsOn 先例），RequirementClarifyServiceTest 92 用例全绿，实测终稿 finalTitle/finalPackage 真实落库；②确认卡协议核验：卡切换分支仅认 selections 快照（isAcceptSelected），纯文本「确认」不触发切换，脚本改带 selectedOptions 应答（questionId=confirm-switch）后终稿正常产出；③脚本端点失配修复（N-0xx RPC 风格化收口效应，shell 2 脚本 20 处 + ps1 6 脚本 22 处）：sendMessageById/finalizeById/abandonById 系列 + planById/findPlanByTaskId/confirmPlanByTaskId/rejectPlanByTaskId 系列 + 拆解异步化轮询适配（planById 恒空返回，deepseek v4-pro 实测拆解约 4 分钟 → 轮询窗口默认 360s）；④实测结果：verify-requirement-clarify 10 步（终稿 finalTitle=内部日报统计模块 → finalize → FINALIZED → 异步拆解 6 草案 → abandon 回归）、verify-planner-decompose 12 步（5 草案 → PLANNING → confirm 转正 PENDING/ASSIGNED → IN_PROGRESS；reject 路径 CANCELLED → Task 回退 PENDING；重复拆解守卫 500）全过；⑤技术债登记：AgentController.validateModelType 在 registerOrGet 幂等查找之前执行，同模型同角色残留 agent 重复注册必 500（重跑前须逻辑删除残留 agent）；实测残留数据：agent 5 + task 3 + 会话 2 + 草案若干（cleanup-test-data.sql 可清理）。外部执行链（外部 agent 场景）已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log 2026-09-10）。

# 4. P2

```text
Quality Gate
Capability-based Agent Routing
Historical Success
Cost / Latency
```

# 5. P3

```text
Dynamic Workflow
LLM-generated branching
Advanced Sandbox
Cross-session optimization
```

# 6. 明确不再作为开发要求的口径

```text
❌ 为了凑 Harness 功能而复制 Harness
❌ SkillRegistry 作为独立“大框架”重新建设
❌ Sandbox = Local/Remote Environment 的同义词
❌ Dual Executor 永久双轨
❌ Planner / Executor / Reviewer 各自拥有完整执行能力
❌ 新建第二套 Workflow Runtime
```

# 7. Gap 登记与回流规则

## 7.1 孤儿项回流

设计文档（专项设计 / 执行方案 / ADR）中作出「推迟到 X 期」「划远期」「降级交付」的决定时，必须在本表同步登记对应条目或显式记 WONTFIX，不得只留存在设计文档内——否则该承诺会随批次闭环从索引中消失。

## 7.2 历史编号映射（2026-09-07 文档重构）

| 旧编号（已归档） | 新编号 | 能力 |
|---|---|---|
| N-013 | G-003 | Harness 执行循环（AgentLoop / ToolExecutor） |
| N-014 | G-004 | Skill 元数据结构化 |
| N-015 | G-005 | Sandbox Provider 化 |
| N-016 | G-006 | Event Stream 消费侧（Replay / Audit） |
| N-017 | G-008 | Agent Fleet 能力化选人 |
| N-018 | G-007 | Quality Gate |
| N-019 | G-009 | Workflow Engine 增强（远期） |

旧编号明细与登记背景见 `archive/legacy/V1_HelloAI 实现差距表.md` 与 `archive/logs/2026-09.md`（LOG-20260907-001）。
