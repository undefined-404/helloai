# HelloAI 实现差距表

> **状态：CURRENT GAP**
>
> 本文只记录当前 → 目标的真实差距。
>
> 最后更新：2026-09-12

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
| G-010 | Planner 能力感知与自适应粒度 | **S1~S4 已落地（2026-09-09）**：S1 数据层（V74 sub_task.required_skills JSONB + constraints TEXT）+ S2 拆解侧（技能目录常驻注入 / 子任务级 requiredSkills+constraints 指派 / 目录过滤 task_plan_skill_filtered 审计 / rule-based 粒度三档 FINE/STANDARD/COARSE + 目录超 20 项截断）+ S3 传递链（mergeSkills 并集装箱 5 装箱点同源 / inbox 技能要求行 / REST 下行 / 草案确认 UI 展示编辑 + updateDraftById 端点 / SKILL.md 增量） | 技能目录注入拆解 Prompt + 子任务级 requiredSkills/constraints 指派（V74 新列，并集装箱）+ 粒度三档 FINE/STANDARD/COARSE 自适应（**rule-based 决策矩阵**：执行者画像 × difficulty 调制，2026-09-09 拍板）+ 外部感知下行通道（可选字段向后兼容）+ 技能回流贡献规范 | **P1** | S1~S3 PASS（2026-09-09，见 log）；S4 实测平台内链 PASS（2026-09-09，与 G-011 S5 合并执行）——verify-login-e2e 10 项 / verify-requirement-clarify 10 步 / verify-planner-decompose 12 步 EXIT=0（登录 → 澄清 → 终稿 → 建任务 → AI 拆解 → 确认/拒绝闭环）；外部执行链（外部 EXECUTOR agent 场景）已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log）；**后置缺口**：①技能回流贡献规范（D5-3 DB 化）②verify-skill-packages.ps1 校验脚本（D5-2 未交付）；③审查侧 constraints/requiredSkills 注入核验（D4 验收口径）④COARSE 档 constraints 缺失的 timeline WARN 级事件（设计 §2.3 承诺）已由 G-011 S3/S4 清偿（2026-09-09）；**P0「打通下发」补强（2026-09-11）**：claimSubTask 返回体内联子任务全文（content/deliverable/acceptance/constraints/uncertainties/requiredSkills）+ 新增 getSubTaskDetail 工具（三通道 12 工具对齐；V76 seed + 默认 12 + 懒启用三重兜底）+ inbox sub_task.assigned 摘要升级为「交付物 + 验收标准 + 约束 + 待确认正文」结构化文本；纯增量向后兼容，66 相关单测全绿（新增 1 处 agent→task entity 只读引用已按 CODE_STYLE §6.1 显式豁免，记录见 SubTaskDetail Javadoc）；**P2-3（2026-09-11）**：COARSE 档 acceptance 补「与交付物的可观察判定方式（判定动作 + 预期结果）」（不动 STANDARD/FINE 两档） |
| G-011 | 需求包准入与不确定性显式管理 | **S1~S5 已落地（2026-09-09）**：S1 数据层（V75 双列 sub_task.uncertainties JSONB DEFAULT '[]' + requirement_conversation.final_package JSONB + 双实体字段 + Uncertainty 值对象落 task 域避反向依赖 + RequirementPackageParser 防御式静态工具）+ S2 澄清侧（两处终稿提示词 package 结构化五字段 + ClarifyReply.package JsonNode 防御承接 + updateFinalDraftFields 条件覆盖写 + buildTaskFromDraft 双写）+ S3 拆解侧（模板四增量 + uncertainties 落库归一 + COARSE constraints 缺失 WARN + D5 兜底审计）+ S4 传递链（执行注入 D6 三段 / 审查核验 D7 双占位符 + 轨道 A 第 8/9 条 / REST 下行四参 updateDraft / inbox 待确认行 / 草案 UI 不确定性列）+ S5 实测（平台内链 PASS，与 G-010 S4 合并执行） | 结构化需求包（goal / scope / outOfScope / assumptions / openQuestions，会话列 + task.context 双写）+ 拆解继承为 sub_task.uncertainties[ASSUMPTION|UNCONFIRMED] 显式 JSONB 列 + 执行侧注入（含 constraints 补偿）+ 审查侧分级语义（假设不成立 ≠ 执行缺陷）+ 外部下行可选字段向后兼容 | **P1** | S1~S4 PASS（2026-09-09，见 log：V75+实体+解析器 10 单测 / 澄清侧 / 拆解侧 / 传递链各增量，core 全量单测 1359 用例 0 失败，api 编译 + UI type-check 通过）；S5 实测（2026-09-09，与 G-010 S4 合并）：平台内链 PASS——澄清终稿 final_package jsonb 真实落库（jsonb 定点写 bug 修复：Mapper+XML ::jsonb 条件写，RequirementClarifyServiceTest 92 用例全绿）+ 确认卡 selections 协议核验 + 异步拆解轮询适配（360s 窗口）；外部执行链已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log：审查者引用 uncertainties 申报作驳回依据实证）；**不建自动闸门**（openQuestions 不阻断，人工裁决 + fail-close BLOCKED 链上报）；gap_kind 实现路径分类与任务后蒸馏闭环后置批次二/三；技术债：Agent 注册幂等顺序缺陷仍在（validateModelType 先于 registerOrGet；配套的 api_key_hash 落库缺列已于 2026-09-10 修复）；配套修复：JSONB uncertainties CCE 死锁（inbox 通知静默失败 → 外部链死锁根因，2026-09-10）；**uncertainties 正文下行（2026-09-11，P0「打通下发」）**：ASSUMPTION / UNCONFIRMED 逐条 note 随子任务全文（摘要 + claimSubTask 内联 + getSubTaskDetail）直达执行者，分级后缀与执行/审查侧同源；**P1/P2 批次（2026-09-11，生成能力退化修复）**：①P1-1 需求包补回任务级 acceptanceCriteria（六字段，设计 §7#11；#2 决策反转已同步登记，JSONB 加键零迁移）+ planner-decompose「任务级验收覆盖」规则（封闭集合全覆盖，无需求包任务不做回溯要求；提示词硬约束 + 人工核验，不做 fail-close）②P1-2 拆解四必填字段（title/content/deliverable/acceptance）缺失即整批 BizException + timeline `task_plan_draft_field_missing` 审计（设计 §7#14）③P1-3 终稿四小节关键词组校验 + 单次重试 + fail-open（timeline `requirement_description_section_missing`）+ 两提示词 description/package 职责写死（设计 §7#13）④P2-1 主任务详情弹窗展示需求包六字段（后端零改动，无需求包整块隐藏）⑤P2-2 verify-requirement-clarify-structured 扩至「澄清 → 终稿」软断言（小节组 ≥3 / 长度 ≥300 / final_package 存在）⑥P2-3 COARSE acceptance 可观察判定方式⑦P2-4 审查 missingEvidence 缺失证据清单（轨道 A 第 10 条 + reviewHistory 加键 + 返工 inbox 摘要携带，设计 §7#17）；**无 DDL 迁移、无新增 agent→task 依赖**；单测 core 全量 1380 + api 58 用例 0 失败，UI type-check 通过 |
| G-012 | 登录鉴权与 RBAC 权限体系（Sa-Token） | **底座 + 闭环已落地（2026-09-12）**：登录会话由自建 Redis Token 切换为 Sa-Token（token 走 X-Admin-Token 头，active-timeout 8h 滑动续期）；授权从 AdminOnlyInterceptor 前缀二元判断升级为「角色-权限码」RBAC（V77 四表 + 内置 SUPER_ADMIN/ADMIN + 存量用户 role 迁移 + StpInterfaceImpl + @SaCheckPermission）；管理侧 API（角色 CRUD / 角色-权限绑定 / 用户-角色分配 / 权限码列表）+ 登录响应携带 permissions/roles + 前端菜单按权限码动态过滤 | 自建登录模块 → Sa-Token 统一会话 + 用户/角色/权限码管理 API + 接口注解鉴权 + 前端动态菜单；后续可按需做菜单树建表与页面化管理 | **P2** | 底座 S1~S4 PASS（2026-09-12，见 log）：V77 迁移 + 四实体/四 Mapper + 角色服务 + 权限查询服务 + 三管理 Controller + SaInterceptor 注解鉴权 + NotLogin/NotPermission 异常归一；单测 SysRoleServiceImplTest 6 + SysPermissionQueryServiceImplTest 5 + AuthServiceTest 10 = 20 用例 0 失败，core 全量 1393 用例 0 失败；UI type-check 通过；V77 在 dev 库事务回滚验证 PASS（2 角色 + 23 权限码 + 20 角色权限 + 存量用户迁移）。**后置缺口（原登记三项已全部关闭，2026-09-12 同日闭环）**：①角色/权限/用户管理前端页面（S5 PASS，三页 + 路由 + rbac.ts）；②存量会话无缝迁移（S2 PASS，原 token 重建会话删旧 key，Docker 实测）；③菜单树 DB 化（S6 PASS，MenuController /api/admin/menus/tree + MainLayout 动态渲染）；配套基础设施修复 S7 PASS（分页 total 恒 0 根因二连）。**深化项已转 BASE 专项（见 G-013）** |
| G-013 | 基础架构深化（RBAC 底座，参考 JeecgBoot） | **批次一/二/三全部落地（2026-09-12）**：①前端动态路由（权限=路由可达性，无权限 URL 404）；②v-auth 按钮级权限；③动作级权限码；④菜单树携带 component 驱动动态 addRoute；⑤可视化菜单/权限管理页（树形 CRUD）；⑥角色授权差异更新；⑦**路由渲染增强**（隐藏菜单/页面缓存/外链）；⑧**部门/岗位组织架构**（部门树 + 用户-部门/岗位多对多 + 两管理页 + 用户「组织归属」分配）；⑨**数据权限规则**（受控枚举 ALL/DEPT/DEPT_AND_CHILD/CUSTOM，作用于用户列表可见范围） | 对齐 JeecgBoot 标杆：权限 = 路由可达性 + v-auth 按钮级权限 + 动作级权限码 + 菜单树携带 component 动态 addRoute + 可视化菜单树 CRUD + 角色授权差异更新 + 渲染增强/组织架构/数据权限 | **P1** | **三批次全部 PASS（2026-09-12，见 log）**：BASE-1.x（V79 component+动作码 / V80 path 补丁 / SysPermissionService CRUD + DTO 投影 / 白名单守卫动态路由 / v-auth / 菜单管理页 / 授权差集）；BASE-3.1（V81 hidden/keep_alive/external_link + 前端渲染适配）；BASE-3.2（V82 部门/岗位四表 + 8 权限码 + SysDepart/SysPosition Service+Controller + 用户组织归属 + DepartList/PositionList 页）；BASE-3.3（V83 rule_flag + 数据规则表 + 受控枚举解析 + 用户列表数据权限 + 规则配置 UI）。**实测修复 5 个缺陷**：SaInterceptor 未注册（G-012 疏漏）/ addRoute 父参数须为 name / 登出后路由权限串用 / **catch-all redirect 导致登录后 404**（改直接渲染 NotFound）/ **会话失效静默落 404**（HTTP 401 未清态 → 补 request 拦截器清登录态 + 守卫跳登录页）。**验证**：core 1444 用例 0 失败 + UI type-check/build + Docker 全链路 + 浏览器实测（SUPER_ADMIN 23 菜单含部门/岗位页；ADMIN 17 菜单且 `/system/departs` 404；数据权限 CUSTOM 过滤生效）；既有 PS1 本机无 pwsh → NOT RUN（等价断言 PASS） |

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

- P1/P2 批次（2026-09-11，生成能力退化修复）：PASS（代码 + 单测；实测回归脚本已升级待跑）——①P1-1 任务级验收条目：RequirementPackage 六字段 + RequirementPackageParser（KEY_ACCEPTANCE_CRITERIA / parse / render，JSONB 加键**无 DDL 迁移**）+ requirement-clarify.md / requirement-finalize.md package schema + planner-decompose.md「任务级验收覆盖」bullet（封闭集合全覆盖 + 子任务 acceptance 适用时标注「（对应任务级验收条目 N）」+ 契约/过程性子任务可不标注 + acceptanceCriteria 空则不做要求）；设计文档同步修订（状态横幅 P1/P2 登记 + D1 六字段 + §2.2 schema + §4 S2/S3/S5 + §6 验收 7~11 + §7 决策 #2 反转与 11~17），差距表本行同批登记（诚实登记原则）；②P1-2 拆解必填字段 fail-close：parseDraftItems 逐条校验 title/content/deliverable/acceptance（null/blank 即整批 BizException），抛错前记 timeline `task_plan_draft_field_missing`（payload: draftSeq/field/title/rawOutputSummary）；③P1-3 终稿信息量：四小节关键词组（背景|目标 / 范围|边界 / 交付物|交付 / 验收）宽容匹配，缺小节同轮追加纠偏指令**重试 1 次**；重试异常/非 final/仍缺 → 放行首轮 + timeline WARN `requirement_description_section_missing`（fail-open，不阻断建任务；runFinalizeLlmRound / runLlmRound 两终稿轮同覆盖），两提示词「与 description 同源」改为职责划分（description=完整规格四小节正文，package=结构化索引，禁止以 package 概括代替正文）；④P2-1 主任务详情：TaskList.vue 描述弹窗扩展为「任务详情」（任务描述 + 需求包六字段含任务级验收标准块），`task.context.requirementPackage` 缺失/非法/六字段全空整块隐藏（后端零改动，context 无 @JsonIgnore）；⑤P2-2 回归口径：verify-requirement-clarify-structured.ps1 由「追问即 abandon」扩至「澄清 → 终稿」，nudge「没有其他要求，请直接生成终稿」后 finalizeById，软断言 description 小节组 ≥3 / 长度 ≥300 / final_package 存在（LLM 输出不可控不 hard fail；FINALIZED 时跳过 abandon 并提示清理测试数据）；⑥P2-3：planner-decompose.md COARSE 行 acceptance 补「与交付物的可观察判定方式（判定动作 + 预期结果）」（不动 STANDARD/FINE）；⑦P2-4 审查驳回可执行性：subtask-review.md 轨道 A 第 10 条「缺失证据清单」+ 输出 schema `missingEvidence`（acceptanceRef 用验收标准**原文子串**机械可核 / missing / howTo），VerdictParser.normalizeMissingEvidence 防御归一（缺失/非数组/元素非对象 → 空清单），rejectAndRework 写入 `context.reviewHistory` 当前轮（JSONB 加键零迁移，同 executorDoneIssues 先例），buildReworkSummary 渲染「缺失证据清单」段随返工 inbox 摘要下行（不做 acceptance 编号化协议，方案 B 否决理由见设计 §7#17）。**测试证据**：P1-1 相关四单测文件全绿（RequirementPackageParserTest / RequirementClarifyServiceTest / PlannerDecomposeAsyncServiceImplTest / RequirementPromptTemplateContractTest）；P2-4 相关（SubTaskReviewServiceTest 46 / SubTaskServiceHandoverTest 16 / 模板契约 4）0 失败；core 全量 1380 + api 58 用例 0 失败；UI `npm run type-check` 0 error。**红线**：全程无 DDL 迁移、无新增 agent→task 依赖（RequirementPackage 在 planner 域、review 读 task context 均为既有合法方向）。


### 登录鉴权与 RBAC 权限体系（G-012）

登记口径：新 G-012（登录自建 → Sa-Token 统一会话 + RBAC 授权；2026-09-12 底座 + 闭环落地）。

已落地：

- S1 数据层：PASS——V77 迁移四表（sys_role / sys_permission / sys_user_role / sys_role_permission）+ 内置种子（SUPER_ADMIN 全权限 / ADMIN 显式 20 权限码，23 权限码 = 17 MENU + 6 API）+ 存量 sys_user.role 单字段迁移关联表；dev 库事务回滚验证 PASS。V78 扩展 sys_permission 增加 parent_id / path / icon（菜单树 DB 化数据底座）+ 新增 user:view / role:view / permission:view / deadletter:view 四个 MENU 权限码 + ADMIN 绑定 deadletter:view。
- S2 会话层：PASS——AuthServiceImpl 由自建 Redis Token 会话切换 Sa-Token（StpUtil.login / getLoginIdByToken / logoutByTokenValue，token 走 X-Admin-Token 头，active-timeout=28800s 滑动续期，Redis satoken: 前缀）；AuthService 接口清掉旧常量与文档；AuthServiceTest 10 用例覆盖。**存量会话无缝迁移**：validateAdminToken 在 getLoginIdByToken 返回 null（Sa-Token 对未命中 token 返回 null 而非抛异常，1.44.0 实测语义）时回退读旧 Redis key（auth:admin:token:{token}），命中则以**原 token 值**重建 Sa-Token 会话并删除旧 key——前端零改动、旧会话自动续用；Docker 实测：预置旧会话 → /api/auth/me 200 且旧 key 清除、同一 token 二次请求仍 200（已由 Sa-Token 会话承接）。损坏 JSON 清理 + 双未命中 401 有单测覆盖。
- S3 授权层：PASS——StpInterfaceImpl（用户 → 角色码 + 权限码，SUPER_ADMIN 返回 "*" 全权限通配）；SaInterceptor 注册 /api/** 注解鉴权；SysRoleController（role:manage）/ SysUserRoleController（user:manage）/ SysPermissionController（role:manage）@SaCheckPermission 接入；GlobalExceptionHandler 补 NotLoginException→401 / NotPermissionException→403。
- S4 前端菜单过滤：PASS——登录响应携带 permissions/roles（SUPER_ADMIN="*"）；auth store 持久化权限码 + hasPermission（"*" 通配兜底）；MainLayout 菜单数据化按权限码过滤（15 个菜单项 ↔ V77 MENU 权限码一一对应）。
- S5 角色 / 权限 / 用户管理前端页面：PASS——新增 UserList.vue / RoleList.vue / PermissionList.vue 三页（用户分页+搜索+编辑+分配角色+重置密码 / 角色 CRUD+权限绑定（菜单/接口分组勾选，SUPER_ADMIN 禁删）/ 权限码列表+搜索）；路由 system/users|roles|permissions；rbac.ts + paths.rbac + types/system.ts 接入；Element Plus 表格/对话框/分页齐全。
- S6 菜单树 DB 化：PASS——MenuController /api/admin/menus/tree + SysPermissionQueryService.listMenuTree（type=MENU + 权限码过滤 + parent 挂接 + 「DB 有子但过滤后无可见子节点」剔除空父）；MainLayout 改调菜单树接口动态渲染（icon 按 @element-plus/icons-vue 组件名映射）；Docker 实测：SUPER_ADMIN 返回 18 根节点（含系统设置→用户/角色/权限管理父子层级、死信池 query 菜单），ADMIN 无 user/role/permission:view 时系统设置整体隐藏。
- S7 基础设施修复：PASS——用户分页 total 恒 0 根因二连：① mybatis-plus 3.5.9 起 PaginationInnerInterceptor 拆分到 mybatis-plus-jsqlparser 独立模块且为 optional，需显式引入（helloai-start 增加 mybatis-plus-jsqlparser-4.9）；② mybatis-plus 版本 3.5.9 → 3.5.12（聚合 POM 已含 jsqlparser 模块，本地 m2 全量缓存）。修复后 Docker 实测 /api/admin/users/page 返回 total=2 / pages=1 / LIMIT 生效。

验证基线：core 全量单测 1399 用例 0 失败（AuthServiceTest 10 含会话迁移 3 用例 + SysRoleServiceImplTest 6 + SysPermissionQueryServiceImplTest 5 等，mybatis-plus 3.5.12 下复跑全绿）；api 全量单测 58 用例 0 失败；UI vue-tsc type-check + 生产构建通过。**Docker 本机实测**（postgres/redis/rabbitmq/minio 容器 + local profile，2026-09-12）：admin/admin123 登录 → /api/auth/me 权限=["*"]；菜单树 18 根节点；角色 CRUD + 权限绑定（创建 TESTER→绑 18/19→回读→改名→删除）全链路 200；用户分页 total=2、testuser1 分配 ADMIN 角色后 roleCodes=['ADMIN']；存量旧 Redis 会话无缝迁移 200 且旧 key 清除、同 token 复用 200；前端登录 + 侧边栏动态菜单渲染（浏览器实测），三管理页面模块 Vite 转换 200 无报错。

后置缺口（原登记三项已全部关闭）：无。

### 基础架构深化（G-013，参考 JeecgBoot，实施编排独立）

登记口径：G-012 闭环后，参考同级目录标杆项目 JeecgBoot-main 的菜单/用户/角色/权限实现，
梳理出基础架构深化差距（动态路由 / 按钮权限 / 动作级权限码 / 菜单管理页 / 差异更新 / 扩展能力），
作为**基础架构专项**独立实施——任务编号 **BASE-1.x / BASE-2.x / BASE-3.x**，
与差距表 G-xxx、重构实施计划 P0~P3 / A1~A7 / S1~S8 完全错开，不混用。

- 专项文档：`doc/HelloAI 基础架构调整实施计划.md`（背景 / 已完成基线 G-012 S1~S8 / 标杆设计要点 / 三批次任务明细 / 验收）。
- 目标架构融合：`doc/HelloAI 目标架构.md` 新增 §12 基础架构（平台底座），与业务五层正交。
- 批次状态：**三批次（BASE-1.1~1.6 / 2.1~2.3 / 3.1~3.3）已全部落地（2026-09-12，PASS）**——详见 `log/2026-09.md`「批次一 + 批次二全量落地」「批次三全量落地」两段。
- 授权补充（2026-09-12，PASS）：**ADMIN 角色绑定部门管理权限**（BASE-3.2 授权补充）——V84 迁移 `V84__rbac_admin_depart_permission.sql`（role_id=2 × `depart:view/add/edit/delete`，`ON CONFLICT DO NOTHING` 幂等）+ 界面等效路径 `PUT /api/admin/roles/2/permissions`（`lastPermissionIds` 差集，原 21 码零丢失 → 25 码）。验证：Flyway version=84 success=t 且二次执行 `INSERT 0 0` 无重复；ADMIN 身份 `/api/auth/me` 含 4 个 depart 码、菜单树含「系统设置 → 部门管理」、部门 tree/新增/删除全 200；未授予 `position:*`；测试数据零残留。详见 `log/2026-09.md`「ADMIN 角色绑定部门管理权限」段。
- 落地要点：V79 component + 12 动作级权限码（V80 补 path）；SysPermissionService CRUD + DTO 投影；三 Controller 方法级 `@SaCheckPermission`；**SaInterceptor 注册修复**（G-012 遗漏）；前端动态路由（`router/dynamic.ts` + 白名单守卫 + NotFound）+ v-auth；菜单管理页树形 CRUD；授权 `lastPermissionIds` 差集；**BASE-3.1** V81 hidden/keep_alive/external_link + 前端渲染适配；**BASE-3.2** V82 部门四表（部门 + 用户-部门）+ Service/Controller + DepartList + 用户组织归属；**BASE-3.3** V83 rule_flag + 数据规则表 + 受控枚举解析 + 用户列表数据权限。
- 收口（2026-09-12，PASS）：**岗位能力彻底移除 + 拆出独立「菜单管理」入口**（V85 / V86）——岗位判定对本系统无场景（部门已承担组织归属 + 数据权限范围），经确认彻底移除：V85 清 `position:*` 关联并软删 4 码 + `DROP TABLE sys_user_position`/`sys_position`（表中 0 行，不可逆）；**V86 将软删遗留行物理清理**（`DELETE FROM sys_permission WHERE code LIKE 'position:%'`，含任意 deleted 状态；关联表防御性清理）。后端删 11 文件（entity×2/mapper×2/Service(+Impl)×2/Controller/DTO×3/单测）+ 收敛 SysUserRoleController/SysUserItem；前端删 PositionList.vue + 岗位 API/路径/类型 + UserList 岗位列与勾选（保留部门）。菜单维护独立：V85 新增 `menu:view`（id=48，`/system/menus` → `system/MenuList`），新增 MenuList.vue（仅 type=MENU 树形 CRUD）、PermissionList.vue 收敛为「权限管理」= 仅 type=API 权限码 + 数据规则，两页共用 `/api/admin/permissions`。详见 `log/2026-09.md`「岗位能力移除 + 菜单管理独立入口」段。
- 实测修复（5 个）：① SaInterceptor 从未注册；② `addRoute` 父参数须为路由 name；③ 登出后 `routesBuilt` 未重置致权限串用（改按 token 跟踪）；④ **catch-all 用 redirect 导致登录后落 404**（改直接渲染 NotFound 组件，保证「首次导航 → 构建 → 重入原目标」链路）；⑤ **会话失效静默落 404**（HTTP 401 走 axios error 分支未清登录态 → `request.ts` 补 401 清态 + `router` 守卫区分 401/未登录跳 `/login`、其余失败重试 1 次并提示）。
- 验证：core **1437 用例 0 失败**（V85 后移除岗位测试 7 用例）/ api 58 用例 0 失败 / UI type-check + build / Docker API 全链路（部门/数据权限/渲染字段；岗位端点已 404）/ 浏览器实测（SUPER_ADMIN 系统设置 = 用户/角色/菜单/权限/部门，无「岗位管理」；ADMIN 授权前无系统设置且 `/system/departs` 404，V84 授权后可见「系统设置 → 部门管理」并可 CRUD；数据权限 CUSTOM 过滤生效）；既有 PS1 因本机无 pwsh 为 NOT RUN（等价断言 PASS，环境限制非跳过）。
- 红线：不新增第二套权限体系、不触碰 Event/状态机/Scheduler/Workflow/Review、外部 Agent 契约不变、V77~V85 已提交 DDL 只读（新增 V86）、数据权限受控枚举无动态 SQL 拼接。
- 合规基线（2026-09-12）：专项文档 §4 强制遵循《HelloAI_AI开发协作规约》（Step 0~9 生命周期 / 复用既有 PS1 / 完成报告 16 节模板）+《HelloAI_CODE_STYLE》（RBAC 归 system 域不反向依赖业务域 / DTO 投影不暴露 Entity / @Transactional / Flyway V79+ / §43 认证授权分离 / v-auth 统一 hasPermission）。


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
