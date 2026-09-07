> **状态：HISTORICAL / REFERENCE**。当前开发请以根目录 Current/Target/Gap/Plan 为准。

# HelloAI Phase 2 B4：Planner Context 管理收口（N-012）执行方案

> 主轴：把差距表 N-012（Planner Context 管理，PARTIAL P1）收口为 **Conversation 窗口裁剪 + Current User Input 独立分层 + 六层 Context 口径登记**。对应 P2-B（P1 PARTIAL 清账批）B4 项（B1 = N-007 / B2 = N-004 / B3 = N-005 已收口）。
>
> **状态**：已实施（2026-09-05，LOG-20260905-013；核心交付：历史窗口裁剪 + Current User Input 独立分层 + 六层口径登记；差距表 N-012 已按完成规则移除）。

---

## §0 现状盘点（2026-09-05 代码核查）

### 0.1 Planner prompt 装配现状

```text
RequirementClarifyServiceImpl.renderPrompt（主回复：CHAT/CLARIFY/FINALIZE 三模板共用）
  ├─ {{CONVERSATION_HISTORY}}   ← messageService.listByConversation 全量拼接（无裁剪）
  ├─ {{WEB_SEARCH_CONTEXT}}     ← ClarifyWebSearchOrchestrator 受控注入（单轮 ≤8~9KB，无资料渲染"（无可用联网资料）"）
  └─ {{SYSTEM_TIME_CONTEXT}}    ← SystemTimeContextBuilder 逐轮实时渲染
前置联合决策（CHAT 轮）：requirement-decision.md，历史裁剪 ≤6 条（DECISION_HISTORY_LIMIT）
任务拆解：PlannerDecomposeAsyncServiceImpl，注入 TASK_TITLE/TASK_DESCRIPTION（无历史）
```

### 0.2 六层 Context 对照

| 层（差距表目标） | 现状 | 判定 |
|---|---|---|
| System Context | 规划侧 `systemPrompt("")` 恒空；角色/规则/输出协议作为 user prompt 模板内 Markdown 章节（`## 系统当前时间` / 角色职责 / `## 输出形态`）；`AgentChatClientServiceImpl.doGenerate` 已支持 system/user 双消息 | **部分**：模板章节即 System 载体；双消息链路已具备，未用（口径登记，不强拆——避免模板大改与行为漂移） |
| Project Context | 平台无项目/仓库维度；任务级上下文由 Task/SubTask 承载（拆解时注入 TASK_TITLE/TASK_DESCRIPTION） | **不适用**：登记口径，不硬造 |
| Conversation Context | 主回复**全量拼接**（CHAT 上限 50 轮 / CLARIFY 20 轮，O(n²) token）；决策调用已裁剪 ≤6 条 | **真缺口**：主回复历史窗口裁剪（本轮落地） |
| Current User Input | 最新用户消息作为 transcript 末行并入历史，无独立占位符 | **真缺口**：独立注入（本轮落地） |
| Optional Search Context | 受控注入 + 无资料占位符，语义节稳定 | 已闭环（登记） |
| Optional MCP Context | 规划侧完全不涉及（MCP 仅执行器侧） | 已闭环（登记） |

### 0.3 测试现状

`RequirementClarifyServiceTest`（2200+ 行，LLM mock + captor 断言 userPrompt 含模板角色段"资深需求分析师"与搜索内容节"这里是官网正文内容"）——均不依赖历史全量拼接，窗口裁剪改动不破坏既有断言。

---

## §1 任务定位与拍板

**preflight 四问**：改什么——主回复历史窗口裁剪 + Current User Input 独立分层（代码）+ 六层口径登记（文档）；为什么——N-012 核心诉求"避免 Context 持续膨胀"，历史全量拼接是主要膨胀源（O(n²)），Current User Input 未独立；类型——补功能（小闭环）+ 文档口径登记；不做什么——不强拆 system 消息（模板大改风险，登记口径）、不建 Project Context（平台无该维度）、不注入 MCP 到规划侧（执行器职责）、不动决策调用（已裁剪 ≤6 条）与拆解链路。

### 决策记录

| # | 决策点 | 拍板 | 理由 |
|---|---|---|---|
| D-B4-1 | 历史窗口策略 | **最近 40 条 + 首条用户消息锚点**（超窗时前置首轮意图；窗内不裁剪；不含本轮最新用户消息——后者独立注入 Current User Input 层） | 首轮意图是需求起点，锚点保留语义价值；最近窗口承载近期上下文；决策调用 6 条已证"近期上下文足够意图识别"，主回复 40 条余量充分 |
| D-B4-2 | Current User Input 注入 | 最新用户消息从历史中抽离，独立 `{{CURRENT_USER_MESSAGE}}` 节注入（避免与历史末行重复） | 分层目标明示 Current User Input 为独立层；聚焦模型当前输入 |
| D-B4-3 | System 层落地 | **不拆 system 消息，登记口径**：模板章节即 System 载体；`AgentChatClientServiceImpl.doGenerate` 双消息能力已具备，未来可平滑迁移 | 强拆需重构 3 模板 + 渲染 + 所有调用点，行为漂移风险高；当前章节分层已满足"角色/规则/协议"承载 |
| D-B4-4 | 窗口值可配性 | **常量起步（40），登记演进**：如需可配再提为 `helloai.planner.context.*` properties | 避免改全参构造器（测试多处 new）；先固化合理值 |

---

## §2 范围边界

**做**：

1. `RequirementClarifyServiceImpl.renderPrompt` 改造：`listByConversation` 全量 → 抽离最新用户消息（Current User Input）→ 剩余历史窗口裁剪（最近 40 + 首条用户消息锚点）→ 渲染 `{{CURRENT_USER_MESSAGE}}` 独立节
2. 模板 3 个（requirement-chat.md / requirement-clarify.md / requirement-finalize.md）加 `{{CURRENT_USER_MESSAGE}}` 节 + `{{CONVERSATION_HISTORY}}` 注释更新
3. 单测：`windowHistory` 静态方法（窗口裁剪 + 锚点 + 窗内不裁剪 + 空列表）+ renderPrompt 集成（CURRENT_USER_MESSAGE 注入 + 超窗裁剪）
4. 口径登记：六层 Context 对照（§0.2）写入本方案 + 差距表收口说明

**不做**（明文边界）：

- 不拆 system 消息（D-B4-3）
- 不建 Project Context（平台无项目维度，登记"不适用"）
- 不注入 MCP 到规划侧（执行器职责）
- 不动决策调用（已裁剪 ≤6 条）与拆解链路
- 不新增 properties（D-B4-4 常量起步）

---

## §3 设计

### 3.1 历史窗口裁剪 + Current User Input（S1）

```text
messages = messageService.listByConversation(conversationId)
   │
   ├─ 抽离最新用户消息 → currentUserInput（从后往前找第一条 user 角色）
   │     （历史中移除该条，避免与 CURRENT_USER_MESSAGE 重复）
   │
   ├─ windowHistory(history, 40)
   │     1) size ≤ 40 → 原样
   │     2) size > 40 → 最近 40 条 + 首条用户消息锚点（若锚点在窗口外，前置）
   │
   ▼
transcript = 用户：…/助手：… 逐行拼接（窗口内，不含 currentUserInput）
template.replace({{CONVERSATION_HISTORY}}, transcript)
        .replace({{CURRENT_USER_MESSAGE}}, currentUserInput)
        .replace({{WEB_SEARCH_CONTEXT}}, ...)
        .replace({{SYSTEM_TIME_CONTEXT}}, ...)
```

- **锚点语义**：首条用户消息 = 需求起点（"做一个报表"），超窗后仍前置保留，避免长会话丢失原始意图。
- **行为变化**：仅长会话（>40 条）prompt 变小（丢弃窗口外历史）；短会话行为不变；Current User Input 从历史末行变为独立节（语义聚焦，无信息损失——最新用户消息仍在）。

### 3.2 模板节（S2）

```text
## 当前用户输入

{{CURRENT_USER_MESSAGE}}

## 对话历史

{{CONVERSATION_HISTORY}}
```

- 三个主回复模板统一加节（渲染统一替换，缺节会出现字面占位符——必须 3 个都加）。
- `{{CONVERSATION_HISTORY}}` 注释更新：最近 40 条 + 首轮意图锚点（服务端裁剪），不含本轮最新用户消息。

---

## §4 Step 分解与验收口径

| Step | 内容 | 依赖 | 验收口径 |
|---|---|---|---|
| S0 | 本方案定稿 | — | 盘点 + 决策齐备 |
| S1 | `windowHistory` 静态方法 + `renderPrompt` 改造 + 单测 | S0 | 单测：超窗裁剪最近 N + 首条锚点前置；窗内不裁剪；空列表空；currentUserInput 从历史抽离；renderPrompt 注入 CURRENT_USER_MESSAGE 且窗口外消息不在 prompt |
| S2 | 模板 3 个加 CURRENT_USER_MESSAGE 节 + 注释 | S1 | 模板含节；无字面占位符残留（grep） |
| S3 | 全量回归 | S1+S2 | core 基线 1131 + 新增全绿；既有 RequirementClarifyServiceTest 不破 |
| S4 | 文档回填：差距表 N-012 收口 + LOG-20260905-013 + 本方案 R1 + 基线 | S3 | 差距表无 N-012 残留；六层口径登记 |

---

## §5 工程规范适配

- **静态方法可测**：`windowHistory` 提为 package-private static（纯函数，无容器依赖）
- **模板统一替换**：renderPrompt 统一 replace `{{CURRENT_USER_MESSAGE}}`，模板必须都含该占位符（防字面残留）
- **零 DDL / 零配置**：常量起步，无新表无新 properties
- **测试**：`RequirementClarifyServiceTest` 补用例（既有 LLM mock + captor 模式）；`windowHistory` 独立单测

---

## §6 提交约定

- 代码笔（core + 模板）与文档笔（本方案 + 差距表 + LOG）分离提交
- commit message 走 UTF-8 文件 + `git commit -F`；git push 由用户执行

---

## 修订记录

### R1（2026-09-05）：B4 执行方案定稿

- **背景**：P2-B 清账批 B4。差距表 N-012（PARTIAL P1）"Context 持续膨胀"——盘点确认主回复历史全量拼接是主要膨胀源，Current User Input 未独立。
- **拍板**：D-B4-1=窗口 40 + 首条锚点 / D-B4-2=Current User Input 独立节 / D-B4-3=System 不拆消息登记口径 / D-B4-4=常量起步。
- **范围**：历史窗口裁剪 + User Input 独立分层（代码）+ 六层口径登记（文档）；零 DDL 零配置。

### R2（2026-09-05）：实施完成

- **实施**：`RequirementClarifyServiceImpl` 新增 `HISTORY_WINDOW=40` + `windowHistory` 静态方法（最近 40 条 + 首条用户消息锚点前置，窗内原样，锚点在窗内不重复）；`renderPrompt` 抽离最新用户消息独立 `{{CURRENT_USER_MESSAGE}}` 注入 + 历史窗口裁剪；requirement-chat/clarify/finalize 三模板加「## 当前用户输入」节 + 注释更新；六层口径登记（§0.2）。
- **验证**：`RequirementClarifyContextWindowTest` 6 例 + `RequirementClarifyServiceTest` 新增 1 例全绿（既有 86 例不破）；core **1138**（基线 1131 + 7）全绿，Failures/Errors=0。
- **行为影响**：短会话仅新增 Current User Input 节（无信息损失）；长会话 token 从 O(n²) 收敛为 O(40) 量级。
- **回填**：差距表 N-012 按完成规则移除（总览行 + §11 章节，后续重排编号，最后更新 2026-09-05）；LOG-20260905-013。
