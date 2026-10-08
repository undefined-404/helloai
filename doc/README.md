# HelloAI 文档体系说明

> **文档治理版本：V2.1**（2026-09-30 收口：`design/` 准入规则 + `Status/Scope` 头 + 引用完整性修复 + 旧链口径清理）
>
> 本目录采用“当前事实 → 目标架构 → 演进差距 → 实施计划 → 架构决策/日志”的单一主线。
>
> **权威优先级：代码/可验证事实 > 项目基线 > 目标架构 > 实现差距 > 实施计划 > 历史归档。**

## 1. 权威文档

| 文档 | 职责 | 当前开发是否直接依据 |
|---|---|---|
| `HelloAI 项目基线文档.md` | 当前代码和已落地能力 | ✅ |
| `HelloAI 目标架构.md` | 稳定目标边界 | ✅ |
| `HelloAI 实现差距表.md` | Current → Target 差距 | ✅ |
| `HelloAI 重构实施计划.md` | 当前实施顺序 | ✅ |
| `HelloAI 基础架构调整实施计划.md` | 基础架构（RBAC 底座）专项实施顺序（任务编号 BASE-xxx，独立于业务主线编号） | ✅ |
| `HelloAI_CODE_STYLE.md` | 代码与工程规范 | ✅ |
| `HelloAI_AI开发协作规约.md` | AI Agent 开发行为规约（生命周期 / 验证体系 / 红线） | ✅ |
| `log/HelloAI 架构变更记录.md` | 架构决策 | 参考 |

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

> 新增/移动文档后，请用「引用完整性」自检：搜索旧路径字符串是否仍有命中，命中的活文档必须同批修正。

## 2.2 调研文档（`research/`）

`research/` 收集**对外部项目 / 方案 / 技术的调研与借鉴分析**，回答「别人怎么做的、哪些值得借鉴、哪些明确不抄」。

**准入规则**

1. 只收**调研 / 借鉴 / 预研**类文档，`Status` 固定用 **`Analysis`**（不改变实现、不承诺排期）。
2. 调研得出的**可执行结论**若进入实施，另立 `design/` 设计文档或差距表条目，**不在 `research/` 里写排期与进度**。
3. 引用外部源码时必须标明**勘察口径**（`[实测]` 附 `路径:行号` / `[推断]` 单列），不可验证的实现不得当已确认事实。

| 文档 | Status | 主题 |
|---|---|---|
| `Octop_借鉴分析_沙箱_技能化_WebAgent接入.md` | `Analysis` | 沙箱声明化 · 技能包治理 · Web Agent 接入路线对比 |

## 3. 历史文档

`archive/` 中的文档只用于追溯背景、已完成的设计和被替代的方案。

**禁止将 archive 中的文档重新当作当前 Roadmap 或架构入口。**

## 4. 读取顺序

AI 编程 Agent 修改代码前必须按以下顺序建立上下文：

```text
README.md
→ HelloAI 项目基线文档.md
→ HelloAI 目标架构.md
→ HelloAI 实现差距表.md
→ HelloAI 重构实施计划.md
→ 对应 design/ 专项文档
→ 代码
```

若历史文档与当前文档冲突，以当前文档和代码为准。

## 5. 脚本索引

`../scripts/README.md` 是验证与运维脚本的全量分类索引（82 个 PowerShell + 28 个 Shell + 1 Java 工具 + 1 SQL，按「验收 / 运维 / 外部 Agent 守护 / 一次性」分组），含各脚本前置条件、双平台对实现映射与已知问题。新增脚本前先查该索引。
