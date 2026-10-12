# HelloAI 平台在 7 种 Agent 架构演进中的能力定位

> 参照框架：《AI Agent 架构怎么选？从单 Agent 到图工作流，一篇讲清 7 种常见模式》（夜雨聆风，2026-09-30）＋ 同源速记「Agent 7 大主流架构 | 从简单到企业级」。
> 参照对象：用户上传的「从单 Agent 到企业级 WorkflowAgent 的 7 种主流架构的演进」图。
> 分析日期：2026-10-12 ｜ 关联：`LOG-20261012-002`、`doc/review/HelloAI 内部 LLM 执行链与开源 agent 架构比对（2026-10-12）.md`
> 证据口径：本报告所有 HelloAI 结论均带 `文件:行号` 实证；未实证的推断一律显式标注「推断」。

---

## §0 三句话结论

1. **HelloAI 不是「架构落后」，而是「跳级建设」**——它把演进阶梯的**上层（编排 / 多 Agent / 流程内核）**建到了企业级，却在**底层（执行者本体：单 Agent 工具面 / ReAct 循环 / Skill 装载）**留空。
2. **用户「内部 LLM 依旧只能聊天」的判断在代码层完全成立**：内部 LLM 执行者（`API_KEY_LLM`）的 MCP 工具授权面被 100% 过滤为空，4 个内置技能中仅 1 个声明工具 ⇒ 默认状态下内部 LLM 的可用工具集 **≈ 空**，只剩一份几行的「技能速览」文本注入 System Prompt。
3. **V2.2「skills 治理放第一项」方向正确，但落点必须从「补第 5 层（Router+Skill）」下沉到「先补第 1/2 层（执行者的工具面与循环预算）」**——否则技能加上了，也仍是「有技能说明书、但没手」的模型。

---

## §1 参照框架（7 种架构 + 标准进阶路径）

| # | 架构 | 一句话定位（原文） | 该层要解决的核心问题 |
|---|---|---|---|
| 1 | **单 Agent** | 先用一个执行者跑通闭环 | 一个执行者 + 模型 + 工具，能把一个任务闭环做完 |
| 2 | **ReAct** | 根据反馈探索下一步 | 路径无法预先写死时，推理↔行动↔观察交替推进 |
| 3 | **Plan & Execute** | 把长目标拆成可检查的阶段 | 长任务有可验收的阶段与进度，而非一次猜完 |
| 4 | **Multi-Agent** | 让可分拆的工作分工协作 | 任务可拆、需不同上下文/工具时，协调者+专家分工 |
| 5 | **Router + Skill** | 把高频任务交给对应能力处理 | 能力路由 + 技能（说明/脚本/模板/检查规则）按需装载 |
| 6 | **黑板 Blackboard** | 围绕共享证据动态推进问题 | 多模块围绕共享工作区，证据/假设驱动协作推进 |
| 7 | **Graph Workflow** | 把执行路径和业务关卡明确下来 | 节点/边/状态显式化，可追溯、可中断、业务关卡强制 |

**标准进阶路径**（原文）：`单 Agent → ReAct → Plan & Execute → Multi-Agent → Router+Skill → 黑板 → Graph 流程架构`。

原文同时给出一条重要的**反教条提醒**：「不必照着升级」——真实且更实用的演进方式是**先完成最小闭环，再根据实际瓶颈增加机制**（「能力选择混乱 → 加路由与技能；长任务偏离 → 加规划与检查；独立任务太慢 → 引入并行；过程难控 → 补图编排」）。

> 本报告据此采用**两条判据**同时评估：(A) HelloAI 在各层的**绝对能力**（够不够用）；(B) HelloAI 沿阶梯的**相对结构**（哪层厚、哪层薄）。

---

## §2 HelloAI 逐层定位（7 层实测）

判定图例：**✅ 具备** ｜ **⚠️ 部分/形似实缺** ｜ **🔴 缺失**

### 第 1 层 · 单 Agent —— ⚠️ 「形具备、能力面被掏空」

- **框架骨架齐全**：`AgentRuntime` 八件套（Context/Session/Skill/Tool/Loop/Event/Environment/SandboxProvider）；ADR-001 Run/Turn/Step 模型；`ChatModelToolLoop` 已实现「模型 ↔ 工具」内循环。
- **★核心缺口（本会话实测）**：内部 LLM 执行者（`AgentAccessType.API_KEY_LLM`）在取工具列表时被**整表过滤**掉 13 个 MCP 会话工具：
  - `helloai-core/.../agent/service/impl/AgentMcpServerServiceImpl.java:267-275`
    ```java
    public List<String> getEnabledToolsForAccess(Long agentId, AgentAccessType accessType) {
        List<String> enabled = getEnabledTools(agentId);
        if (accessType != AgentAccessType.API_KEY_LLM) return enabled;
        return enabled.stream()
                .filter(tool -> !MCP_SESSION_TOOLS.contains(tool))   // 13 个全在清单里 ⇒ 过滤后为空
                .collect(Collectors.toList());
    }
    ```
  - 过滤常量 `MCP_SESSION_TOOLS` 与默认授权集 `DEFAULT_EXECUTOR_TOOLS`（12 个）**近乎同集合**（`AgentMcpServerServiceImpl.java:88-102`），⇒ 内部 LLM 过滤后**授权集为空**。
  - 该过滤本身**是正确修复**（`2026-10-06 L3 P1-1`：进程内调用无 `sessionId`，注入必然 401，曾占内循环约 14.8%，见 `:83-86` 的缺陷背景注释）。
- **结论**：单 Agent 的**骨架 ✅**，但**执行者本体 ⚠️**——「人还在，但手上没工具」。

### 第 2 层 · ReAct —— ⚠️ 「弱 ReAct，步数预算过低」

- `ChatModelToolLoop` 有 while 内循环，但：
  - **步数硬上限 = 5**：`helloai-core/.../agent/runtime/loop/AgentLoopInput.java:47` `DEFAULT_MAX_ITERATIONS = 5`。对照 Octop / DSH 的循环预算通常在 20~50+，5 步**不足以完成需要多轮工具探索的任务**。
  - **非原生 function-calling 循环**：循环内 `setInternalToolExecutionEnabled(false)`（`ChatModelToolLoop.java:100-104` 一带），工具执行由平台手动编排 ⇒ 与 DSH `ReactLoopAgent`（`E:/workspace/deepseek-harness-master/packages/core/agent-loop/src/agent.ts:246-330`）的原生 ReAct 存在**语义差**（推断：这会削弱「模型自主决定下一步调什么工具」的灵活度）。
- **结论**：**弱 ReAct**。不是没有循环，而是「轮数不够 + 自主度受限」。

### 第 3 层 · Plan & Execute —— ⚠️ 「规划层强、执行侧阶段检查弱」

- **规划层是 HelloAI 相对成熟的一环**：`planner` 域拆解 → `SubTask` 落库分派 → `review` 域评审 → 返工上限 `autoReviewMaxRework=3`（`helloai-common/.../config/AgentDispatchProperties.java:131`）。这**本身就是 Plan & Execute 的教科书骨架**。
- **缺口**：ADR-001 已有 Run/Turn/Step 模型，但**执行侧的「step 级阶段验收」未接线**——当前只有 review 域的**事后整体评审**，缺少「每一步有明确输入/输出/完成条件」的阶段检查（这正是原文强调的 P&E 价值点）。
- **结论**：**长任务的规划与评审在，执行过程的阶段化控制在途**。

### 第 4 层 · Multi-Agent —— ✅ 「具备且有企业级色彩（本平台最强环）」

- 分层编排：`planner(0) > review(1) > task(2) > agent(3) > system(4) > shared(5)`；子任务分派 + 多 executor（`trae-excutor` / `TeleAgent-executor`）；借鉴 AgentTeams 的两轴分类（backend × runtime，`E:/workspace/AgentTeams-main/agentteams-controller/internal/backend/interface.go:33-40,319-349`）。
- 但需注意：**内部 LLM 在这里是「被分派者」之一，而非「协调者」**——Multi-Agent 强 ≠ 内部 LLM 强；反而更凸显内部 LLM 是整条链的**薄弱节点**。
- **结论**：Multi-Agent **✅**（强），但这层的强**放大了**下层的弱。

### 第 5 层 · Router + Skill —— 🔴 「V2.2 主战场，Skill 侧尚未成『可装配能力包』」

- **Router 有雏形**：技能命中 `/ 按 `requiredTools` 联动 resolve 工具并集（`AgentRuntimeContextAssembler.java:135-136,140-144`）。
- **★Skill 侧是「prompt 级速览注入」而非「惰性可加载能力包」**：
  - 注入物是**文本**：`SkillSpeedSummaryRenderer.java:26-43` 把技能正文按 `\n---\n` 截断、剔除 h1，只把「执行速览」段拼进 System Prompt。
  - 对照 DSH：技能是**全量目录注入（name+description）+ `skill({name})` 惰性加载正文/资源**（`E:/workspace/deepseek-harness-master/packages/skill/tool-skill/src/index.ts:213-251,81-160`）——**模型先看到「有哪些技能」，再按需把某个技能「打开」**。
  - HelloAI 现状是**相反的顺序**：命中即把速览塞进 Prompt，**没有「选择 → 加载 → 校验」的闭环**，也没有技能可携带的脚本/资源。
- **★技能盘点（实测）**：`helloai-core/src/main/resources/skills/plugins/` 仅 4 个技能，其中
  | 技能 | `requiredTools` | 性质 |
  |---|---|---|
  | `eng-code-review` | `[]` | 纯 prompt 约束 |
  | `eng-doc-standard` | `[]` | 纯 prompt 约束 |
  | `eng-verification` | `[]` | 纯 prompt 约束 |
  | `eng-web-research` | `[web_search, search_knowledge]` | 唯一带工具 |
  ⇒ **3/4 技能对「执行者能力」零贡献**，仅 1 个带来工具。
- **结论**：**🔴 这是最关键的落点**——不是「没有 Router」，而是「Skill 还不是能真正增强执行者的能力单元」。

### 第 6 层 · 黑板 Blackboard —— 🔴 「缺失（仅有事件日志，非共享推理工作区）」

- 现状：`SubTask` 状态 + `timeline` 事件流（可观测性登记 3 处），但这些是**事件日志**，不是「多模块围绕共享工作区读/写证据、假设、中间结果」。
- 关联：计划里的 **REF-7「工作详情快照」仅登记未落地**，与黑板的「共享工作区」沾边但不等价。
- **结论**：**🔴 缺**。对当前「内部 LLM 单打独斗」的形态，这一层**优先级可后置**。

### 第 7 层 · Graph Workflow —— ⚠️ 「编排内核具备、显式图 + 人工关卡 + 状态恢复缺失」

- **内核具备**：`planner/task/review` + **执行密集闸门**（`EXECUTION_DENSE_PATTERN` + `isExecutionDenseMismatch` + `hasLocalExecutionCapability`，`SubTaskDispatchService.java:219-238`、`ResilientDispatcher.java:159-162,174-195`）+ **重复失败短路**（`RepeatedFailureGate`，阈值 `autoReviewRepeatFailureSimilarity=0.85`）+ 分布式调度。这在功能上**等价于企业级流程编排**的「业务关卡」。
- **缺口**：
  1. **非显式图**——是代码硬编码的流水线，无节点/边/状态的显式定义（对比 LangGraph 「图即 API」）；
  2. **无 HITL 人工审批节点**（原文把 HITL 列为关键关卡，`Graph Workflow` 强调「必须经过的检查和审批」）；
  3. **无状态持久化恢复**（对比 LangGraph checkpoint / Temporal durable execution）——当前靠 DB 状态 + 重跑，非「断点续跑」。
- **结论**：**编排内核 ✅ / 显式图形态、HITL、恢复 ⚠️/🔴**。

---

## §3 定位全景

```
能力曲线（相对强度）            HelloAI 现状
高 ┤            ●4Multi-Agent(强)      ●7Graph内核(强)
   ┤      ●3P&E规划层(强)
中 ┤  ●2ReAct(弱)                            ●6Blackboard(缺)
   ┤●1单Agent(骨架✅/工具面空)   ●5Router+Skill(Skill侧空)
低 └────────────────────────────────────────────────→
    1     2     3     4     5     6     7
      【执行者本体：薄】   【编排层：厚】    【协作/图：偏薄】
```

**一句话读图**：HelloAI 的能力分布是 **「中间与右侧厚、左侧（执行者）空」**——它是**从阶梯的中段（4/7）往上盖**的，而**阶梯的地基（1/2/5）没打**。

---

## §4 关键结论：为什么「skills 加上了，内部 LLM 还是只能聊天」

用户在本次质询中的判断——「**否则 skills 虽然加上了，但内部 LLM 依旧是只能聊天的模型，而非进化成 agent**」——**在代码层被完整证实**，因果链如下：

```
① 内部 LLM(API_KEY_LLM) 取工具 → 13 个 MCP 工具被全量过滤 ⇒ 授权集 = 空
        ↓
② 技能侧补工具 → 4 个技能仅 eng-web-research 声明 [web_search, search_knowledge]
        ↓
③ 且技能注入是「prompt 级速览文本」，不是「可加载能力包」
        ↓
④ 循环预算 maxIterations = 5，且非原生 function-calling
        ↓
结果：内部 LLM 默认 ≈ 「一份几行技能的 System Prompt + 近乎零工具 + 5 步预算」
        = 用户说的「只能聊天的模型」
```

**这不是「架构选择错误」，而是「建设顺序错位」**：V2.2 把 skills 治理（第 5 层）放第一项是对的意图，但**第 5 层的价值必须先有第 1/2 层承接**——技能装载出来的工具，得有工具面可挂、有循环步数可跑。**先补地基，再谈装配**。

---

## §5 与 V2.2 / 既有登记的映射

| 架构层 | HelloAI 判定 | V2.2 对应动作 | 关联编号 |
|---|---|---|---|
| 1 单 Agent | ⚠️ 工具面掏空 | **恢复内部 LLM 的能力面**（在「不注入 MCP 会话工具」前提下，补齐非会话类工具面） | 建议新立 `REF-8` 内部 LLM 能力面 |
| 2 ReAct | ⚠️ 步数 5 | 提升循环预算（按任务类型分档，而非全局 5）；评估原生 function-calling 回路 | 同上 |
| 3 P&E | ⚠️ 阶段检查弱 | 执行侧 step 级验收接线（ADR-001 已有模型） | 待排期 |
| 4 Multi-Agent | ✅ | —（保持） | — |
| 5 Router+Skill | 🔴 主战场 | **skills 治理**：从「prompt 速览」升级为「目录注入 + 惰性加载 + 可携带资源」 | V2.2 第一项 |
| 6 Blackboard | 🔴 | 后置 | REF-7（登记未落地） |
| 7 Graph | ⚠️ 内核✅ | 显式图 / HITL 关卡 / 断点恢复 | 远期 |

**排期建议（与 `LOG-20261011-007` A+B 的关系）**：
- A+B（能力闸 + 收敛熔断）治「接错活 + 重复失败」，属**第 3/7 层的守门**——必要，但**不解决执行者没手的问题**。
- 真正让内部 LLM「进化成 agent」的关键路径是 **先做第 1/2 层的能力面与循环**，再做第 5 层的 skills 装配。**建议把「内部 LLM 能力面」显式立为 V2.2 的独立前置项。**

---

## §6 证据索引

**HelloAI（只读实测，未修改）**
| 事实 | 位置 |
|---|---|
| 内部 LLM 工具整表过滤 | `helloai-core/.../agent/service/impl/AgentMcpServerServiceImpl.java:267-275` |
| 过滤常量 = 默认授权集（13/12 近乎同集合） | 同文件 `:88-102` |
| 过滤的缺陷背景（401 / 14.8%） | 同文件 `:83-86` |
| 循环步数硬上限 5 | `helloai-core/.../agent/runtime/loop/AgentLoopInput.java:47` |
| 内循环手动排工具执行 | `helloai-core/.../agent/runtime/loop/impl/ChatModelToolLoop.java:100-104` |
| 技能速览渲染（文本截断） | `helloai-core/.../agent/skill/SkillSpeedSummaryRenderer.java:26-43` |
| 技能注入 + requiredTools 联动 | `helloai-core/.../agent/service/AgentRuntimeContextAssembler.java:135-144` |
| 4 个内置技能定义 | `helloai-core/src/main/resources/skills/plugins/*.md` |
| 执行密集闸门 | `helloai-core/.../task/service/SubTaskDispatchService.java:219-238` |
| 返工上限 / 相似度阈值 | `helloai-common/.../config/AgentDispatchProperties.java:131,154` |

**参考架构（实测）**
| 事实 | 位置 |
|---|---|
| DSH 原生 ReAct 循环 | `E:/workspace/deepseek-harness-master/packages/core/agent-loop/src/agent.ts:246-330` |
| DSH 技能「目录注入 + 惰性加载」 | `E:/workspace/deepseek-harness-master/packages/skill/tool-skill/src/index.ts:213-251,81-160` |
| DSH agent 生命周期文档 | `E:/workspace/deepseek-harness-master/docs/agent-lifecycle.zh.md` |
| AgentTeams 两轴分类 | `E:/workspace/AgentTeams-main/agentteams-controller/internal/backend/interface.go:33-40,319-349` |
| Octop Agent 管理器 | `E:/workspace/Octop-main/src/octop/infra/agents/manager.py:3226-3243,3360-3389` |

---

## §7 局限说明

1. **参照框架系「回推」**：用户上传的图片本机未能定位读取，本报告第 7 种架构阶梯据公开同源文章（夜雨聆风 2026-09-30 + 同源速记）回推。**若图片阶梯与此不同，请以图片为准，本报告 §2 结论可原样映射到等价层。**
2. 第 1/2 层部分结论（如「非原生 function-calling 语义差」）含**推断**成分，已在正文标注，落地前需按「先 diff 再下结论」复核。
3. 本报告为**定位分析**，不含改造实现；改造须按规约走「设计 → 差距表登记 → 实现 → 门禁」链路。
