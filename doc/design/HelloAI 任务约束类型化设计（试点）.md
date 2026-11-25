# HelloAI 任务约束类型化设计（试点）

> **状态：Active**（试点设计已定稿，`CT-1`~`CT-8` 已于 2026-11-23 实施完成并通过门禁；实施见 `plan/HelloAI 任务约束类型化试点实施计划（2026-11-23）.md`；落地进度以差距表 `G-037` 行为唯一事实源，本文件不复述数字；真机链路复验与行为观察未跑，观察期保持 Active）
> **Scope**：本设计只定义「任务约束类型化」的数据模型、约束文档目录（白名单 manifest）、执行侧按需读取工具契约、以及**拆解 / 执行 / 审查三环节**的约束流转。试点只打通 `DOCUMENT` 类型的读取闭环。**不含**：`SKILL` 类型实现、约束编辑 UI / 管理界面、跨任务约束继承策略自动化、审查侧硬性 fail-close 校验、`TEXT` 类型与现有 `constraints` 字段的合并重构。
> **差距表锚点**：`G-037`
> 最后更新：2026-11-23

---

# 0. 定位与边界

## 0.1 问题来源

- 用户实测（多轮对话，2026-11-23）：`doc/HelloAI_CODE_STYLE.md`（**3918 行 / 151 个章节**）的约束越详细、越具体，不同 AI agent / 不同 LLM 的代码产出越收敛于项目级要求；而当前子任务的执行约束仅是 `sub_task.constraints` 单值 TEXT 字段（V74 引入），拆解时由 LLM 即兴生成几句话——信息量差两个数量级，「几行字段达不到相同效果」。
- 前序调研：`research/需求规约化与两级拆解调研（2026-11-23）.md` §4 落点分析——doc 治理契约的注入位在「任务派发 → 执行约束」，拆解侧只声明引用、不复制全文（唯一事实源判据）。
- 现状缺陷（代码实测）：任务/子任务输入侧**无附件式约束通道**（`AttachmentPortAdapter` 是产物登记，方向相反）；`Task.agentPolicy` 只承载派发参数（选人 / 难度 / fallback），不含约束文本；执行侧 18 个 internal 工具中无「读约束文档」通道。

## 0.2 本设计做什么

把子任务约束从「单值自由文本」升级为**类型化约束列表**：

1. **约束列表** `constraint_refs`：`[{kind, ref, scope[]}]`，`kind ∈ TEXT（内联裁决）/ DOCUMENT（契约文档引用）/ SKILL（预留，走现成技能通道）`；
2. **约束文档目录（白名单 manifest）**：平台级登记可被引用的契约文档与可用章节范围，未登记 ref 一律拒绝（防任意文件读取）；
3. **执行侧读取工具** `internal_list_constraint_docs` / `internal_load_constraint_doc`：目录 / 正文分离、按 scope 拉取章节，fail-open（文件缺失不伤主链）；
4. **三环节流转**：拆解声明 → 执行注入引用 + 按需读取 → 审查携带同一引用。

试点只落地 `DOCUMENT` 一条闭环，用真实 `CODE_STYLE` 验证「文档约束比字段约束更收敛 AI 产出」的链路可行性。

## 0.3 本设计不做什么（边界）

| 不做 | 为什么 | 依据 |
|---|---|---|
| `SKILL` 类型实现（只预留枚举位） | 走 `internal_list_skills → internal_load_skill` 现成通道，零新增；试点焦点是「读项目契约文档」，与技能语义不同 | 差异出在 `ref` 指向：技能注册表 vs 项目 doc 文档 |
| 约束编辑 UI / 管理界面 | 试点为后端链路验证；UI 属产品化，等链路验证通过再立项 | 治理纪律：先验证机制再铺面 |
| 跨任务约束继承策略自动化 | 试点由拆解提示词引导 LLM 按任务内容声明；继承策略（粗任务→细任务 scope 裁剪）留 `G-027` 交付编排实施时定义 | 依赖多级拆解落地（`G-027` 未实施） |
| 审查侧硬性 fail-close 校验 | 试点只把引用注入审查提示词（reviewer 可见）；「产出不符合约束文档即驳回」需真机误报率数据支撑 | 与 MA-9 弹卡同理：硬前置未满足，先告知后拦截 |
| `TEXT` 类型与 `constraints` 字段合并重构 | 现有字段已承载裁决语义且被多面消费（D6 段 / 审查侧 G-010 缺口③清偿点）；试点新增 `constraint_refs` 列并存，向后兼容 | 判据：最小改动，杜绝「改字段就开新号」 |

## 0.4 诚实边界

- `DOCUMENT` 约束链路打通 ≠「约束已自动治理」：试点只证明「目录 → 声明 → 落库 → 注入 → 按需读取」闭环通；
- 「产出规范符合率提升」属**行为验证**，需真实任务对照（有 / 无约束文档），另立观察项，不并入试点验收；
- 约束文档目录白名单试点仅登记 `doc/HelloAI_CODE_STYLE.md` 一份；后续扩目录须同批维护 manifest（引用完整性规则）。

---

# 1. 数据模型

## 1.1 约束列表 `constraint_refs`

`JSONB` 数组，每项：

```json
{
  "kind": "DOCUMENT",
  "ref": "code-style",
  "scope": ["s6", "s7"]
}
```

| 字段 | 取值 | 说明 |
|---|---|---|
| `kind` | `TEXT` / `DOCUMENT` / `SKILL` | `TEXT` 本次不落库（现有 `constraints` 字段已承载）；`SKILL` 仅预留 |
| `ref` | 约束文档目录注册名（白名单） | 必须存在于 manifest，未登记即拒绝 |
| `scope` | 章节 id 白名单子集数组 | 空数组 / 缺省 = 整文档；非空 = 只取列出的章节 |

落库位置（两个，保持草案 → 转正同构）：

- `plan_draft.constraint_refs JSONB NOT NULL DEFAULT '[]'::jsonb`（V147 表补列；拆解草案暂存）
- `sub_task.constraint_refs JSONB NOT NULL DEFAULT '[]'::jsonb`（V74 表补列；确认转正时同步）

## 1.2 约束文档目录（manifest，唯一事实源）

**位置**：`helloai-core/src/main/resources/constraint-docs/manifest.json`（classpath；与技能包 `skills/` 同级，语义同一范式）

```json
{
  "docs": [
    {
      "ref": "code-style",
      "path": "doc/HelloAI_CODE_STYLE.md",
      "title": "HelloAI 代码开发规范",
      "summary": "代码契约：规则优先级 / 模块边界 / 域依赖方向 / Service 规范（3918 行）",
      "sections": [
        { "id": "s6", "titlePattern": "^# 6\\. " },
        { "id": "s7", "titlePattern": "^# 7\\. " }
      ]
    }
  ]
}
```

- **`path` 相对 `docRoot` 解析**：`docRoot` 由配置注入（`helloai.constraint-doc-root`，缺省相对运行目录的 `../doc`）；文件不存在 / 不可读 → 工具返回「不可用」降级，**不抛异常、不阻断主链**（判据 27：观测不得伤主链）。
- **章节按标题前缀匹配**（运行时解析文档，`^# ` / `^## ` 标题行定位边界）：登记 `titlePattern` 而非行号 —— 文档增删内容导致行号漂移时仍能命中（行号免疫）。
- **白名单过滤**：未登记 `ref`、未登记 `section` 一律拒绝；`internal_load_constraint_doc` 的入参必须命中白名单。

---

# 2. 工具契约（执行侧读取通道）

仿照 `internal_list_skills → internal_load_skill` 的「目录 / 正文分离 + 按需加载（progressive disclosure）」范式，新增两个 internal 工具（文件：`InternalLlmExecutorTools.java`）：

## 2.1 `internal_list_constraint_docs`

- 入参：无
- 返回：全量白名单目录 `[{ref, title, summary, sections:[{id,title}]}]`，**不含正文**
- 语义：模型判断当前任务该按哪份约束文档执行时调用；场景对齐技能目录

## 2.2 `internal_load_constraint_doc`

- 入参：`ref`（必填，白名单注册名）+ `scope`（可选，章节 id 数组）
- 返回：
  - 正常：`{ref, title, sections:[{id,title,content}]}`（`scope` 为空 = 全文）
  - 降级：`{ref, unavailable: true, reason: "..."}`（文件缺失 / 解析失败 / 未登记）——**fail-open**，模型据此知道约束不可用，不得编造
- 语义：正文按需取，不注入执行上下文；`scope` 控制 token 预算（CODE_STYLE 151 章节，一个任务通常只需 2~3 节）

**不新增「约束文档遥测 / 统计」**：试点不观测读取行为（避免过度设计），`G-037` 差距表只记链路状态。

---

# 3. 三环节约束流转

## 3.1 拆解（声明）

- `planner-decompose.md` 新增指引：子任务 JSON 增加 `constraintRefs` 数组字段；引导 LLM 按任务内容从**约束文档目录**（注入目录摘要 `{{CONSTRAINT_DOC_CATALOG}}`，见 §3.4）中声明所需文档与章节 scope；`constraints`（裁决文本）与 `constraintRefs`（契约引用）并存、互补。
- JSON schema 说明追加 `constraintRefs` 字段语义（必填任何格式校验对齐现有 `dependsOn` 等字段的写法）。
- 落库：PlanDraftItem 解析 → `plan_draft.constraint_refs`；确认转正 → `sub_task.constraint_refs`。

## 3.2 执行（引用 + 按需读取）

- `AgentRuntimeContextAssembler` D6 段（G-011 三段增量，行 490 附近）：在「执行约束（不许改的事）」之后追加「约束文档引用: `ref[scope...]`」摘要行——**只注入引用、不注入正文**（空值零注入，与既有三段同规则）。
- `internal_get_sub_task_detail` 返回体带出 `constraintRefs`（执行者可复查完整引用）。
- 正文一律经 `internal_load_constraint_doc` 按需取。

## 3.3 审查（携带引用）

- `ReviewExecutionEngine` 已在「审查侧 constraints 消费点」（`G-010` 后置缺口③清偿位，行 516 附近）注入 `constraints`；同位置追加 `constraintRefs` 摘要注入——reviewer 与执行者看到**同一份引用清单**，并可用 `internal_load_constraint_doc` 读取正文核验。
- 不做强制核验（见 §0.3）。

## 3.4 目录摘要注入

- 拆解提示词装配处（`PlannerDecomposeAsyncServiceImpl.renderSkillCatalog()` 同构）新增 `renderConstraintDocCatalog()`：把 manifest 目录（ref + title + summary + sections，**不含正文**）注入 `{{CONSTRAINT_DOC_CATALOG}}` 占位。
- 执行 / 审查侧**不注入目录**（有 `internal_list_constraint_docs` 工具全量可见），只注入本子任务已声明的引用——避免上下文膨胀。

---

# 4. 试点验收判据（一行）

> 端到端链路：任务拆解产出 `constraintRefs=[{kind:DOCUMENT,ref:code-style,scope:[...]}]` → 落 `plan_draft` → 确认转正落 `sub_task` → 执行上下文 D6 段出现「约束文档引用」摘要 → `internal_load_constraint_doc` 按 scope 返回 CODE_STYLE 对应章节 → 审查提示词带同一引用摘要。**链路全通即试点验收通过**；「产出规范符合率」行为验证另立观察项（需真机对照，不并入本试点）。

自动化覆盖（单测）：manifest 解析与白名单、章节按标题匹配切分、`scope` 空 / 非空两路、`DOCUMENT` 类型 JSONB 解析落库、拆解提示词模板含 `{{CONSTRAINT_DOC_CATALOG}}` 与 `constraintRefs` 字段说明、D6 段装配含引用摘要（空值零注入）、工具降级路径（文件缺失 fail-open）。

---

# 5. 依赖与后续（未立项）

- **前置**：无（独立小专项，不依赖 `G-027`）；`G-027` 交付编排落地后，约束继承策略（粗→细 scope 裁剪）可叠加在本模型上。
- **后续扩展方向**（本批明确不做）：`SKILL` 类型接线（`ref` 指向技能注册表）；`TEXT` 与 `constraints` 合并为统一列表；约束编辑管理面；审查侧硬校验；约束文档目录扩登记（协作规约 / 差距表 / 基线文档）。
- **关联**：`MA-9` 敏感动作审批的「告知不拦截」口径与本设计一致（先链路后硬闸）。

---

# 6. 引用材料

- `doc/HelloAI_CODE_STYLE.md`（试点唯一登记的约束文档）
- `doc/HelloAI 实现差距表.md` §0（`G-037` 登记）与 §1 矩阵 `G-037` 行
- `doc/research/需求规约化与两级拆解调研（2026-11-23）.md` §4（doc-template 落点：执行侧主注入 / 拆解侧摘要引用）
- `doc/文档体系分类与治理规则.md` §3.3（design 准入与 `Status/Scope` 头要求）
- 代码锚点（2026-11-23 实测）：`InternalLlmExecutorTools.java`（`internal_list_skills` :488 / `internal_load_skill` :532）、`AgentRuntimeContextAssembler.java`（D6 段 :490）、`ReviewExecutionEngine.java`（审查 constraints 消费 :516）、`PlannerDecomposeAsyncServiceImpl.java`（拆解提示词装配 :927）、`V74__sub_task_add_required_skills_constraints.sql`、`V147__plan_draft_staging.sql`、`PlanDraft.java` / `SubTask.java`