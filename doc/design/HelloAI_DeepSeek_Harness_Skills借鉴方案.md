# HelloAI × DeepSeek Harness Skills 借鉴调整方案

> 状态：已拍板（2026-08-20），P0 已实施。P1/P2 按路线图推进。
> 事实源：`deepseek-harness-master\.agents\skills`（11 个 SKILL.md，逐文件核对；2026-08-23 复核，§2 清单与本地源码完全一致）。

## 1. 文档定位

本方案回答三件事：

- DeepSeek Harness 内置 skills 中，哪些可以嵌入 HelloAI 的三个角色（Reviewer / Planner / Executor）
- 两者的定位层级差异是什么、哪些边界不可混淆
- 分阶段怎么落地（P0 已完成 / P1 / P2）

本文档不承担 Harness skills 原文翻译或逐条对照的职责。

---

## 2. 事实校准：Harness 真实 skill 清单与价值分级

官方仓库 `.agents\skills` 实际内置 11 个 SKILL.md。**注意：常被引用的 `dsh-planning`、`dsh-goals`、`dsh-subagent` 在当前仓库中不存在**（可能源自旧版或其他来源）；「文档规范」职能实际由 `dsh-doc-standards` 承担，「目标可验证性」分散在 `dsh-prose-standard` 的 contract 概念与 `dsh-code-review` 的 evidence 检查中。

| # | Skill | 核心内容 | 对 HelloAI 价值 |
|---|---|---|---|
| 1 | dsh-code-review | 接口契约双端追踪、生命周期/并发（竞态/取消/所有权/dispose）、消费者契合、范围必要性、测试强度、报告四元组（defect/location/impact/evidence） | ★★★ 直接嵌入（Reviewer） |
| 2 | dsh-prose-standard | 「命题完整保留」规则（actor/condition/modality/negative guarantee/失败模式）、契约必须文档化、按 prose 位置的覆盖要求表 | ★★★ 直接嵌入（Reviewer/报告） |
| 3 | dsh-doc-standards | tutorial/reference 分离、文档层级与 detail 预算、slop 审计清单 | ★★ 概念借鉴（Planner 报告） |
| 4 | dsh-trim-cot-leakage | 思维链泄漏 8 类 taxonomy + 保留规则 | ★★ 直接嵌入（Reviewer 文档审查） |
| 5 | dsh-find-simplifications | 过度设计/死代码/手写代码 vs 依赖的简化候选证据链 | ★ 单维度借鉴（scope/necessity） |
| 6 | dsh-pre-push-checks | 按 diff 范围选择最小验证证据集 | ★ 概念同构（VERIFICATION 围栏） |
| 7 | dsh-archive-agent-notes | 决策记忆按未来价值归档/保留/删除 | ★ 概念对应（迭代记录体系） |
| 8 | dsh-merging-stacked-prs | GitHub PR 栈合并 | ✗ 不适用 |
| 9 | dsh-doc-site-sync | VitePress 文档站点投影 | ✗ 不适用 |
| 10 | dsh-translate-docs | 双语文档翻译工作流 | ✗ 不适用 |
| 11 | record-browser-gif | GUI PR 必须附 GIF 证据 | ✗ 不适用 |

---

## 3. 层级差异边界（不可混淆）

| 层级 | DeepSeek Harness | HelloAI |
|---|---|---|
| 定位 | 单 Agent 运行时（给模型一双手） | 多 Agent 协作调度平台（AI 项目经理） |
| Skills 作用域 | 单 Agent 的能力规范 | 跨 Agent 的角色分工（Planner/Executor/Reviewer） |
| 通信协议 | 本地工具调用（文件编辑、Shell、搜索） | 网络级 MCP SSE + 门铃 + RabbitMQ |
| 可观测性 | Trajectory 轨迹（单 Agent 回放） | 依赖 DAG + 时序图 + 时间线 |

结论：Harness skills 作为 HelloAI 外部 Agent 的「能力插件」引入，不替代 HelloAI 的调度、熔断、死信池、上下文注入等核心机制。

---

## 4. 已拍板决策（2026-08-20）

1. **Reviewer 纪律定位**：全面升级为纪律制——工程纪律清单与验收标准**并列**成为判定依据，任一维度 blocker 均可驳回（改变「验收标准唯一判定依据」的旧产品行为）。
2. **最终报告哲学**：完全拥抱 trim 哲学——废除 `task-final-report.md` 的 50% 字数红线与全量抄录铁律，按 dsh-prose-standard 重写为「契约性事实完整保留 + 过程叙事压缩链接」的信息密度导向模板。
3. **外部技能规范库命名**：平台自命名 `eng-` 前缀（eng-code-review / eng-doc-standard / eng-verification），不保留 dsh- 前缀，摆脱上游命名演进耦合。
4. **落盘与推进**：方案写入本文档，P0 同步实施。

---

## 5. 分角色调整方案

### 5.1 Reviewer（P0，已实施）

- `prompts/subtask-review.md` 升级为双轨纪律制：
  - 轨道 A（验收制）：逐条对照验收标准，判定达标情况（原 10 条核验要求的核心保留）。
  - 轨道 B（纪律制，按交付物类型条件激活）：
    - 代码类：C1 接口契约（签名/返回值区分/异常约定/边界条件文档化）、C2 生命周期与并发（资源创建/释放成对、竞态、取消与错误上报）、C3 验证强度（断言真会失败于目标回归）、C4 范围与必要性（投机泛化、过度抽象）。
    - 文档类：D1 契约与命题完整（必须/不得/失败模式/归属/后果）、D2 无思维链泄漏（8 类 taxonomy 速查）、D3 结构（tutorial/reference 混写、层级混乱）。
  - 分层驳回：blocker（验收未满足 / 纪律缺陷实际造成误用、泄漏、竞态、文档代码矛盾）→ pass=false；nit（风格/命名/格式）→ 不驳回只进 comment。
  - issues 四元组格式：`[defect] 缺陷 [location] 位置 [impact] 影响 [evidence] 依据`。
- 零后端改动：JSON schema 五字段（pass/score/issues/comment/analysis）不变，`ReviewVerdict` 解析无感知。

### 5.2 Planner（P1，概念互补）

1. `planner-decompose.md`：验收标准「尽量可量化」升级为**必须可检查**——每条 acceptance 必须含可观察验证点，与 VERIFICATION 围栏形成拆解侧/审查侧闭环。
2. `requirement-clarify.md`：五维自检增加第六维「边界与排除项」（什么明确不做），对齐 prose-standard 的「scope 必须显式」。
3. `task-final-report.md`（P2 首项，已落地 §6.115）：trim 哲学重写，契约性事实（表格/代码/参数/阈值/路径）100% 保留，叙事文字压缩链接。

### 5.3 Executor（P1~P2，能力插件）

**已具备的衔接点**（V47/A2/A3，§6.59/6.70/6.71）：`agent.skills` + `task.required_skills` AND 匹配 + `SkillNormalizer` 同义词归一 + `AgentSelectionConstraints` 全链注入 + 注册时 `body.get("skills")` 显式传入优先 + 非标准技能标签豁免校验。**选人链路已存在，缺的是词汇表与规范正文下发。**

1. **技能标签上报（文档级）**：executor SKILL.md 注册示例补 `skills: ["eng-code-review", ...]`，零 Java 改动。
2. **平台侧外部技能规范库（新增资产）**：`helloai-core/src/main/resources/skills/plugins/{name}.md`，首批 3 份（每份约 80~150 行，标注「提炼自 DeepSeek Harness dsh-*，已按 HelloAI 语义适配」）：
   - `eng-code-review.md`：四类检查 + 四元组输出 + blocker/nit 分层
   - `eng-doc-standard.md`：命题完整保留 + tutorial/reference 分离 + cot-leakage 8 类速查
   - `eng-verification.md`：最小证据集原则（与 HelloAI VERIFICATION 围栏对齐）
   - 下发通道：沿用 `GET /api/agents/me/skill` classpath 读取机制（`AgentController` 已有兜底）。
3. **Task Running Spec 注入**：`TaskRunningSpecService.buildExecutorPromptSection` 已有注入点，当 `required_skills` 命中插件标签时在「平台约束」段追加规范摘要（约 20 行提炼版）。
4. **值班能力分级**：`checkIn` 上报「已加载技能列表」，合并（取并集）进 `agent.skills`，调度器 AND 匹配自动生效，无新表新枚举。
5. **标准化产出格式收口**：四元组 + blocker/nit 分层写入平台版规范，执行侧按此产出、审查侧按此解析，形成闭环。

---

## 6. 分期路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 | Reviewer 双轨纪律制 + 四元组 + 分层驳回（prompt 级零 schema 变更）；顺手清洗 prompt 模板 cot-leakage | ✅ 已实施（2026-08-20） |
| P1 | 外部技能规范库 3 份 + executor SKILL 注册示例补 skills + Spec 注入 + decompose 验收可检查性 + clarify 第六维 | ✅ 已实施（2026-08-20，§6.114） |
| P2 | 值班 checkIn 技能上报 + task-final-report.md trim 重写（方向已拍板） | ✅ 已实施（2026-08-20，§6.115） |

**文档回填约定**：每阶段收口后更新 `doc/HelloAI_实现差距表.md`（N18「外部技能规范库与纪律制审查」条目）与 `doc/log/HelloAI_迭代执行记录.md`。

---

## 7. 明确不借鉴

merging-stacked-prs / doc-site-sync / translate-docs / record-browser-gif 为 GitHub/VitePress 专用工作流，跳过。dsh-archive-agent-notes 的决策记忆治理概念与 HelloAI 迭代记录体系职能重叠，仅概念参考。dsh-find-simplifications 只取 scope/necessity 单维度并入 Reviewer 纪律清单 C4。
