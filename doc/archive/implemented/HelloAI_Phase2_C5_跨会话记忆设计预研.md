> **状态：HISTORICAL / REFERENCE**。当前开发请以根目录 Current/Target/Gap/Plan 为准。

# HelloAI Phase 2 C5：跨会话记忆设计预研（N-009）

> 主轴：为差距表 N-009（跨会话记忆，TODO P2）产出**可实施级设计草案**（P2-C 设计预研，与 C1~C4 同批）。核心结论：**记忆平面 = "摘要式受控注入"，不是把历史 Conversation 直接当 Memory**（差距表原则）——首版最小闭环 = 会话摘要归档（finalize 生成对话摘要）+ 任务记忆摘要跨会话复用（task_running_spec.contextSummary 范式推广），配受控 recall 注入（有限条数 + 占位 + 降级）。
>
> 文档性质：设计预研（草案），表结构与接口为实施前方向；§7 决策点待用户审阅拍板。
>
> **状态**：已实施（2026-09-06，S1~S4 代码完成 + S5 文档收口；long_term_memory 表 + 会话摘要归档 + 受控 recall 注入，见 §5 路线 + R2 实施记录）。

---

## §0 设计输入与约束

### 0.1 差距表 N-009 要求

```text
现状：Planner 已具备会话上下文。
差距：尚缺独立长期记忆平面；Session Memory / Long Term Memory / User Memory / Task Memory / Agent Memory 之间边界未明。
原则：不要把所有历史 Conversation 直接当 Memory。
优先级 P2 / 状态 TODO
```

### 0.2 S0 事实基线（2026-09-06 代码盘点）

1. **Planner 会话上下文（已具备）**：`requirement_conversation`/`requirement_message`（V29+，含 task_id 软引用、planner_agent_id、mode CHAT/CLARIFY、round_count 上限）；finalize → `buildTaskFromDraft` 回填 task_id，FINALIZED 会话承载任务追溯。
2. **B4 六层 Context 已收口**（规划侧事实基线）：System（模板章节载体）/ Project（不适用）/ Conversation（40 窗口 + 首条锚点）/ Current User Input（独立节）/ Search（受控）/ MCP（不涉及）。`windowHistory` 纯函数 + 首条锚点是跨会话裁剪范式。
3. **conversation_message 是现成 Tier 2 工作记忆载体**（挂 sub_task，V1 + V28 审计列；role/sender/tool_name/token_count/seq 结构完整，注释定位"子任务执行对话流 Tier 2"）——**只有写方（ExecutionResultHandler 等），无 LLM 侧读取注入**。
4. **task_running_spec.contextSummary 是任务内摘要式记忆实践**（`compileContextSummary` 从 ExecutionRecord 重编译摘要；下游 `buildExecutorPromptSection` 注入）——可推广为 Task Memory 摘要生成范式。
5. **依赖上下文受控注入纪律**（`buildDependencySection`）：物化优先 → 原始回退 → 截断 → 降级不阻断——可作为记忆 recall 注入模板。
6. **无任何长期记忆实现**：代码/SQL/前端零 memory 表/服务/recall；`conversation_archive` 表无写入方（遗留）。
7. **文档展望**：架构参考 §1.3 Vibe-Skills memory plane/control plane 分层 + Root/Child Authority；外部项目借鉴 3-Tier Memory（Session/Working/Long-term）；架构改造长期思路 "复杂 Memory（Event Stream + Session）暂时不要碰"。
8. **红线**：AGENTS.md + 架构参考——不建第二控制面；差距表原则"不要把所有历史 Conversation 直接当 Memory"。

### 0.3 记忆分层草案（决策输入）

```text
Session Memory   = 单次需求澄清会话（requirement_conversation 已有）——任务结束后摘要化归档
Long Term Memory = 跨会话/跨任务持久摘要（本草案核心落点：long_term_memory 表）
User Memory      = 用户级偏好/历史意图（挂在用户维度，首版最小：会话摘要按用户归属）
Task Memory      = 任务内运行态摘要（task_running_spec.contextSummary 已有）——跨任务引用
Agent Memory     = Agent 侧执行记忆（conversation_message Tier2）——首版登记边界，不读取
```

---

## §1 任务定位

**preflight 四问**：改什么——新增长期记忆平面（摘要式）：会话 finalize 摘要归档 + 受控 recall 注入；为什么——差距表 N-009 P2 TODO，五类记忆边界未明、无长期记忆实现；类型——补功能（新抽象，小闭环）；不做什么——**不把历史 Conversation 全量当 Memory（摘要化）**、不建第二控制面、不做 Agent 侧记忆读取（conversation_message 只登记边界）、不做事件流/复杂记忆（架构改造长期思路明确暂时不碰）。

---

## §2 范围边界

**做**：

1. `long_term_memory` 表（摘要式：type Session/Task/User + scope + content 摘要 + 元数据）
2. **会话摘要归档**：RequirementConversation finalize 时生成对话摘要（LLM 压缩 or 规则抽取）写入记忆表
3. **受控 recall 注入**：新建需求会话时按相关性（用户/关键词）检索历史摘要，注入 Planner prompt（有限条数 + 占位 + 降级）
4. **Task Memory 跨会话引用**：任务摘要（contextSummary 已有）在相关新会话/任务创建时按需注入
5. 定向单测 + 全量回归 + 差距表 N-009 处置 + LOG

**不做**（明文边界）：

- 不做原始 Conversation 全量当 Memory（摘要化，差距表原则）
- 不做 Agent 侧记忆读取（conversation_message Tier2 首版只登记边界，不注入 LLM）
- 不建第二控制面 / 不做事件流记忆（架构改造长期思路 WONTFIX）
- 不改 B4 已收口的规划侧窗口裁剪（记忆注入为附加受控层，不冲突）

---

## §3 设计（决策草案，待拍板）

### 3.1 长期记忆表（决策 1）

```sql
long_term_memory
  id BIGSERIAL PK
  type VARCHAR(16) NOT NULL           -- SESSION / TASK / USER
  scope_key VARCHAR(128) NOT NULL     -- 归属：conversation:{id} / task:{id} / user:{id}
  title VARCHAR(255)                  -- 摘要标题
  content TEXT NOT NULL               -- 摘要正文（非原始对话）
  ref_type VARCHAR(16)                -- 来源引用：REQUIREMENT_CONVERSATION / TASK_RUNNING_SPEC
  ref_id BIGINT                       -- 来源实体 id
  tag VARCHAR(64)                     -- 关键词/主题（recall 匹配用）
  memory_time TIMESTAMPTZ             -- 记忆产生时间
  -- 审计列继承 BaseEntity
```

- **摘要化**（原则兑现）：`content` 只存压缩摘要，不存原始 message 列表。
- 用户归属（USER 类型）挂用户 id 作 scope_key（首版从会话/任务创建者推断，缺失可空）。

### 3.2 会话摘要归档（决策 2）

- 触发点：RequirementConversation finalize（buildTaskFromDraft 成功回填 task_id 后）。
- 摘要生成：LLM 压缩（复用规划侧 ChatClient 链路）或规则抽取（首版**规则抽取兜底 + LLM 优先**？倾向 LLM 压缩，失败降级规则抽取：标题 + 用户消息要点 + 关键选择）。**降级不阻断** finalize 主链路。
- 幂等：同一 conversation 只归档一次（ref_type+ref_id 唯一约束）。

### 3.3 受控 recall 注入（决策 3）

- 触发：新 RequirementConversation 创建/首轮前，按**当前会话用户 + 标题/首条输入关键词**检索相关记忆摘要（有限条数，如 ≤5 条，单条 ≤1KB）。
- 注入：Planner prompt 新增 `{{LONG_TERM_MEMORY_CONTEXT}}` 节（有则注入，无则占位"（无相关历史记忆）"）。
- 纪律：**不阻断主链路**（检索失败降级跳过）；有限条数 + 截断（ClarifyWebSearchOrchestrator / buildDependencySection 同款）。

### 3.4 五类记忆边界登记（决策 4）

| 记忆 | 载体 | 首版动作 |
|---|---|---|
| Session Memory | requirement_conversation | 已有；finalize 摘要化归档 |
| Long Term Memory | long_term_memory | 本草案核心（§3.1-3.3） |
| User Memory | long_term_memory type=USER | 摘要挂用户维度，首版最小 |
| Task Memory | task_running_spec.contextSummary | 已有；跨会话引用按需注入（§3.3） |
| Agent Memory | conversation_message（Tier2） | 只登记边界，不读取（远期） |

### 3.5 与 B4/Workflow 的解耦

- 记忆注入是 Planner 侧附加受控层，不改 B4 已收口的 40 窗口裁剪/Current User Input 分层；不感知 Workflow/Team/Browser（独立抽象，单向引用记忆表）。

---

## §4 验证方案

| # | 项 | 落点 |
|---|---|---|
| 1 | long_term_memory 表 + CRUD | V72 迁移 + MemoryService 单测（类型/归属/幂等） |
| 2 | 会话摘要归档 | finalize 触发 + 摘要生成（LLM 优先/规则兜底）+ 唯一约束幂等断言 |
| 3 | 受控 recall | 相关性检索 ≤5 条/截断/无结果占位 + 失败降级不阻断 |
| 4 | Task 记忆注入 | contextSummary 跨会话引用注入断言 |
| 5 | 全量回归 | core + api 既有回归全绿（Planner/B4 窗口裁剪不受影响） |

---

## §5 实施路线建议（骨架先行）

```text
N-009-S1 V72 long_term_memory 表 + 实体/Mapper + MemoryService（CRUD/检索/幂等）
N-009-S2 会话摘要归档：finalize 触发 + 摘要生成（LLM 优先/规则兜底）
N-009-S3 受控 recall：新会话检索 + prompt 注入 {{LONG_TERM_MEMORY_CONTEXT}}
N-009-S4 验证：§4 五项单测 + 全量回归
N-009-S5 文档回填（差距表 N-009 处置 + LOG）
```

---

## §6 风险与边界

| 风险 | 缓解 |
|---|---|
| 退化成第二控制面 | 记忆只做"摘要存取 + 受控注入"，零运行期触发；单一控制面不破 |
| 原始对话当记忆 | content 只存摘要（原则兑现），唯一约束防重复归档 |
| recall 污染/注入膨胀 | 有限条数 + 截断 + 无结果占位 + 失败降级不阻断（buildDependencySection 纪律） |
| 与 B4 窗口裁剪冲突 | 记忆注入为附加受控层，不改 B4 六层口径 |
| LLM 压缩成本 | 规则抽取兜底，压缩失败降级不阻断 finalize |

---

## §7 决策拍板记录（待用户审阅）

| # | 决策点 | 草案倾向 | 待拍板 |
|---|---|---|---|
| 1 | 长期记忆表 | `long_term_memory`（type SESSION/TASK/USER + 摘要 content + ref 引用；摘要化非原始对话） | ☐ |
| 2 | 会话摘要归档 | finalize 触发 + LLM 压缩（失败规则兜底）+ ref 唯一幂等 | ☐ |
| 3 | 受控 recall | 新会话按用户/关键词检索 ≤5 条 + `{{LONG_TERM_MEMORY_CONTEXT}}` 注入 + 占位/降级不阻断 | ☐ |
| 4 | 五类边界 | Session 已有 / Long-term 新建 / User 挂用户 / Task 复用 contextSummary / Agent 只登记不读取（远期） | ☐ |

---

## 修订记录

### R1（2026-09-06）：设计预研草案产出

- **背景**：P2-C 设计预研（与 C1~C4 同批），N-009 跨会话记忆。
- **核心**：D=摘要式长期记忆平面（不存原始对话）/ finalize 摘要归档 / 受控 recall 注入（有限条数+占位+降级）/ 五类记忆边界登记；不建第二控制面、不碰事件流复杂记忆。
- **边界**：S1~S5 骨架先行；Agent 侧记忆（conversation_message Tier2）只登记不读取；不改 B4 窗口裁剪。

### R2（2026-09-06）：实施完成（S1~S4）

- **S1**：V73 `long_term_memory` 表（type SESSION/TASK/USER CHECK + ref_type+ref_id 唯一约束防重复归档 + tag 索引）+ `LongTermMemoryType` 枚举 + 实体/Mapper + `LongTermMemoryService`（archiveSession 幂等 / searchForRecall 关键词 like ≤20 条封顶 / 分页）。
- **S2**：会话摘要归档——`ConversationMemorySummarizer` 纯函数（规则抽取：标题 + 前 5 条用户消息要点截断，原则"不存原始对话"；LLM 压缩列为后续优化替换点）；`RequirementClarifyServiceImpl.buildTaskFromDraft` finalize 建任务后 best-effort 归档（失败不阻断，幂等）。
- **S3**：受控 recall——`renderPrompt` 注入 `{{LONG_TERM_MEMORY_CONTEXT}}`（按会话标题检索 ≤5 条，单条 500 字符截断，无结果占位"（无相关历史记忆）"，失败降级不阻断）；requirement-chat/clarify/finalize 三模板加"历史记忆参考"节。
- **S4**：**全量回归：core Tests run: 1238（基线 1230 + 8）/ api 无改动**；新增 Summarizer 3 + MemoryService 5；既有 Planner/B4 窗口裁剪回归全绿无回退。
- **边界兑现**：只存摘要不存原始对话（差距表原则）；recall 有限条数 + 占位 + 降级不阻断；不建第二控制面；Agent 侧记忆（conversation_message Tier2）只登记不读取。
