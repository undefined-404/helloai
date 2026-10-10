# HelloAI 文档体系说明

> **文档治理版本：V2.2**（2026-10-09：新增 `plan/` 计划·方案层；`review/` 回归纯报告；已实施方案归位 `archive/implemented/`。上一版 V2.1 于 2026-09-30 收口：`design/` 准入规则 + `Status/Scope` 头 + 引用完整性修复 + 旧链口径清理）
>
> 本目录采用“当前事实 → 目标架构 → 演进差距 → 实施计划/方案 → 架构决策/日志”的单一主线。
>
> **权威优先级：代码/可验证事实 > 项目基线 > 目标架构 > 实现差距 > 实施计划 > 历史归档。**
>
> **分类与准入规则详见 [`文档体系分类与治理规则.md`](文档体系分类与治理规则.md)**（本文件为索引 + 读取顺序；该文件为分类规则 + 流转纪律）。

## 1. 权威文档

| 文档 | 职责 | 当前开发是否直接依据 |
|---|---|---|
| `HelloAI 项目基线文档.md` | 当前代码和已落地能力 | ✅ |
| `HelloAI 目标架构.md` | 稳定目标边界 | ✅ |
| `HelloAI 实现差距表.md` | Current → Target 差距（**只记当前状态，随迭代就地更新**） | ✅ |
| `plan/HelloAI 重构实施计划.md` | 当前实施顺序（业务主线） | ✅ |
| `plan/HelloAI 基础架构调整实施计划.md` | 基础架构（RBAC 底座）专项实施顺序（任务编号 BASE-xxx，独立于业务主线编号） | ✅ |
| `HelloAI_CODE_STYLE.md` | 代码与工程规范 | ✅ |
| `HelloAI_AI开发协作规约.md` | AI Agent 开发行为规约（生命周期 / 验证体系 / 红线） | ✅ |
| `文档体系分类与治理规则.md` | 文档分类、准入规则与流转纪律（元文档） | 参考 |

## 2. 专项设计

`design/` 只保留仍然具有独立技术价值且会影响后续实现的专项设计。

**准入规则（2026-09-30 确立，与《目标架构》§0 维护规则 3 同源）**

1. `design/` **只收设计文档**。过程性产物一律出列：
   - 实测 / 排查 / 复核 / 审计报告 → `review/`
   - 已执行的改造方案 / 缺陷修复方案 / 预研方案 → `archive/implemented/`
2. 每份设计文档必须在 H1 之后声明 **`Status` / `Scope` 头**：`Status` 取 `Implemented | Partial | Planned | Non-goal` 四值，`Scope` 写清边界与**不含项**，并给出差距表锚点（`G-0xx`）。
3. 进度百分比与逐条差距**只以 `HelloAI 实现差距表.md` 为唯一事实源**，`design/` 不复述数字。

当前主线：

| 文档 | Status | 差距锚点 |
|---|---|---|
| `Agent_Runtime.md` | `Implemented` | G-002 · G-003 |
| `Agent_Event_Stream.md` | `Partial` | G-001 |
| `Skill_Capability.md` | `Partial` | G-004 |
| `Sandbox_Provider.md` | `Planned` | G-005 |
| `Planner_Capability_Awareness.md` | `Implemented` | G-010 |
| `Requirement_Package_Uncertainty.md` | `Implemented` | G-011 |
| `design/adr/ADR-001-run-turn-step-model.md` | （ADR） | — |

> **2026-09-30 出列记录（design/ 只留设计文档）**：
> - 改造 / 修复方案 → `archive/implemented/`：`HelloAI_契约层能力注入与最终报告整合改造方案.md`、`HelloAI_外部Agent执行通道缺陷修复方案.md`（均为**已实施**）；
> - 报告类 → `review/`：`HelloAI_附件存储一致性排查报告.md`、`HelloAI_外部Agent_v3反馈代码级复核.md`、`HelloAI 文档治理审计.md`（命名规范化为「（2026-09-07）」）；
> - 资产类 → `helloai_avatar/`：`HelloAI_登录页原创AI虚拟人物生成提示词.md`（与生成产物同目录）。

> **代码侧同名概念**：`helloai-core/src/main/resources/onboarding/{role}/guide.md` 是**外部 Agent 接入手册**（交付 ZIP 内仍名为 `SKILL.md`），不属于文档体系。
> 能力包另在 `helloai-core/src/main/resources/skills/plugins/*.md`——**「接入手册」与「能力包」是两件事，禁止混用 SKILL 一词**。

## 2.1 引用完整性规则（2026-09-30）

文档迁移后，**引用必须同步修**。本次治理修复了 **137 处**失效路径引用，覆盖三类来源：`scripts/`（脚本头 Ref 注释，最多）、生产代码 javadoc（`Doorbell*` / `*Properties` / `prompts/*.md` 等）、`doc/`（权威文档与专项设计）。

**不修的失效引用（有意保留）**：

1. `archive/**` 与 `log/**` 内指向旧路径的引用——它们是**当时事实的现场记录**，改写等于篡改历史；
2. `helloai-start/src/main/resources/db/migration/V*.sql` 注释里的已归档方案路径——**Flyway 对已应用迁移做 checksum 校验，改注释会导致 `flyway validate` 失败**，故登记不改（涉及 V1 / V62~V73 共 12 处，指向 `archive/implemented|reference/` 下的 Phase0/1/2 方案）；
3. 裸文件名引用（如 javadoc 里的 `prompts/subtask-review.md`）——同目录唯一，按名可解析，保留可读性。

> **补充（2026-10-09）**：上列 1~3 为「**路径变更型**」失效引用（有意保留）；**若被引用对象被整体删除/退役（非路径变更），其引用不再可解析、必须改写** —— 详见 [`文档体系分类与治理规则.md`](文档体系分类与治理规则.md) **§7 例外②**（`C-NN` 编号退役即实例：43 处文件名引用 + 134 处锚点同批改写）。
>
> 新增/移动文档后，请用「引用完整性」自检：搜索旧路径字符串是否仍有命中，命中的活文档必须同批修正。

## 2.2 调研文档（`research/`）

`research/` 收集**对外部项目 / 方案 / 技术的调研与借鉴分析**，回答「别人怎么做的、哪些值得借鉴、哪些明确不抄」。

**准入规则**

1. 只收**调研 / 借鉴 / 预研**类文档，`Status` 固定用 **`Analysis`**（不改变实现、不承诺排期）。
2. 调研得出的**可执行结论**若进入实施，另立 `design/` 设计文档或差距表条目，**不在 `research/` 里写排期与进度**。
3. 引用外部源码时必须标明**勘察口径**（`[实测]` 附 `路径:行号` / `[推断]` 单列），不可验证的实现不得当已确认事实。

| 文档 | Status | 主题 |
|---|---|---|
| `Octop全库借鉴分析_综合版.md` | `Analysis` | 合并沙箱/技能/WebAgent 与全库两份分析 · 含对既有结论的实测裁决 · §10 按**性质**分档（不承载排期）、§10.6 为借鉴项索引；硬约束 1 订正（2026-10-09） |
| `AgentTeams最新版借鉴分析_Java落点.md` | `Analysis` | AgentTeams v1.0.1→v1.2.2 演进对照 · `A1~A16` 借鉴项 · §七为借鉴项索引（不承载排期）、**§八事实核查表逐条标注勘察口径**（`[实测]`/`[推断]`/`[未核实]`）（2026-10-09） |
| `AgentTeams_Octop_源码复核与helloai借鉴对照.md` | `Analysis` | 两项目源码独立复核 · Fork/Return/沙箱/外部Agent/RAG 四主题对照 · §六为借鉴项索引 + 硬约束 1 订正、硬约束 2 转为 `REF-6.5`（2026-10-09） |
| `helloai四能力完善优先级与借鉴路线.md` | `Analysis` | 用户裁定四项优先级（skills > 备份/恢复 > 沙箱 > RAG；**同日两项调整：Fork 收缩为 WONTFIX、沙箱降级为条件触发**）· **§三排序块已移出**、§六为落点索引（`REF-x.y`），含实现判断勘误（2026-10-09） |
| `helloai借鉴清单_四项之外_完整版.md` | `Analysis` | 四项之外 A/B/C/D 四档借鉴清单 · 判据级与明确不抄 · §汇总为落点索引 · **全部来源行已补 `[实测]` + `路径:行号`**（2026-10-09） |

## 2.3 计划与方案（`plan/`）

`plan/` 承载**进行中 / 待执行 / 待拍板**的实施计划、方案与执行清单——这是 `design/`（稳定设计）与代码（已落地）之间的**行动层**。

**准入规则**

1. 只收**计划 / 方案 / 执行清单与预案**；已成文的稳定设计 → `design/`，对既成事实的检查 / 审计结论 → `review/`，外部调研 → `research/`。
2. 每份建议声明 `Status`（`Active | Done | Superseded`）+ 依据 + 日期。
3. **执行完成后必须迁入 `archive/implemented/`** 并标 `Done`，`plan/` 只保留进行中项。

| 文档 | 主题 |
|---|---|
| `HelloAI 重构实施计划.md` | 业务主线实施顺序（Event Stream → Dual Executor → AgentRuntime → Capability；2026-10-09：备份/恢复提前到 Sandbox 之前，Fork WONTFIX 后 Event 消费面回到 Recovery 收尾） |
| `HelloAI 基础架构调整实施计划.md` | RBAC 底座专项（`BASE-xxx`） |
| `HelloAI_RM存量缺口清偿计划（2026-10-04）.md` | 代码质量路线图 RM5/6/8/9 分轮清偿（**RM9 未动，仍在办**） |
| `HelloAI 借鉴落地实施计划.md` | 借鉴落地专项（`REF-x.y`，已登记进《差距表》§0 编号口径表）：skills > 备份/恢复 > RAG（可后置）+ 四项之外 A 档 + 判据登记 + **REF-7 工作详情快照**；v4（2026-10-09）含 11 处代码级订正、每组验证集与文档回填口径、**Fork 收缩**（`D-2026-10-09-5`）、**REF-3 沙箱降级为条件触发**与 **REF-7 新增**（`D-2026-10-09-6`）；v8（2026-10-10）**`REF-2.3` / `REF-2.3b` 落地**（备份/恢复，见 `LOG-20261010-007`） |

> **2026-10-09 出列记录（`plan/` 只留在办项）**：3 份**已执行完成**的方案 / 清单迁入 `archive/implemented/` 并标 `Done` —— `HelloAI_V2回归测试方案（2026-10-04）.md`、`HelloAI_全面复测清单（2026-10-06）.md`、`deploy-checklist-arch-collect-2026-10.md`。

## 3. 历史文档

`archive/` 中的文档只用于追溯背景、已完成的设计和被替代的方案。**冻结只读。**

| 子目录 | 存什么 |
|---|---|
| `archive/implemented/` | 已执行完成的改造 / 修复 / 预研方案、Phase 执行方案 |
| `archive/legacy/` | V2 文档体系重组前的旧事实源快照与旧体系文档 |
| `archive/log/` | 已归档的月度 Log（如 `2026-09.md`） |
| `archive/reference/` | 只读参考资料（架构设计参考 / 长期思路 / 历史进度） |

各子目录均带目录级 `README.md`（存放类型 + 准入规则 + 只读纪律）。

**禁止将 archive 中的文档重新当作当前 Roadmap 或架构入口。**

## 4. 读取顺序

AI 编程 Agent 修改代码前必须按以下顺序建立上下文：

```text
README.md
→ 文档体系分类与治理规则.md
→ HelloAI 项目基线文档.md
→ HelloAI 目标架构.md
→ HelloAI 实现差距表.md
→ plan/HelloAI 重构实施计划.md
→ 对应 design/ 专项文档
→ 代码
```

若历史文档与当前文档冲突，以当前文档和代码为准。

## 5. 脚本索引

`../scripts/README.md` 是验证与运维脚本的全量分类索引（82 个 PowerShell + 28 个 Shell + 1 Java 工具 + 1 SQL，按「验收 / 运维 / 外部 Agent 守护 / 一次性」分组），含各脚本前置条件、双平台对实现映射与已知问题。新增脚本前先查该索引。
