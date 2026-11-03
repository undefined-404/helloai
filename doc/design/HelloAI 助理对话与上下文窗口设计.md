# HelloAI 助理对话与上下文窗口设计

> **Status**: `Planned`（设计中；任务登记见 `plan/HelloAI 个人助理工作台实施计划（2026-11-02）.md` `PA-11` / `PA-12`，状态事实源以该计划 §3 为准）
> **Scope**：
> - **含**：① 个人工作台中间框改为「助理对话」（跨历史检索、不产出任务草案）；② 助理会话与需求会话**同构存储、按 `kind` 分流行为**；③ 新建/助理会话统一的「上下文窗口」——token 占用分项展示 + 手动压缩 + 自动压缩；④ 「从当前对话创建任务」的显式工具与血缘。
> - **不含**：多租户隔离、向量检索（一期用 `pg_trgm`/ILIKE）、助理人格市场、IM/移动端。
> **差距锚点**：`PA-x`（差距表 §0 / §1.1）
> **最后更新**：2026-11-03（设计初版；参考 QwenPaw / Yuxi / LobsterAI / Dify / LangGraph 源码逻辑）

---

## 1. 背景与问题

个人工作台第一版把中间对话直接复用了 `RequirementChat`，而它本质是**需求澄清/建任务**流程：用户发一句话，进入澄清轮，产出任务需求终稿。这与「个人助理」的预期不符：

- 用户想的是「问助理，最近多个会话处理了哪些事」，助理应**检索历史并回答**，而不是重新开一轮需求澄清。
- 用户说「对这件事创建一个任务」时，应当是**显式动作**（新建需求会话 + 进入澄清 + 留血缘），而不是让对话悄悄换语义。

另外，长会话缺少**上下文治理**：`ClarifyPromptRenderServiceImpl` 目前只有「固定 40 条滑窗 + 首条用户锚点」（`HISTORY_WINDOW=40`），没有 token 计量、没有摘要压缩、没有占用面板、没有手动/自动压缩。会话越长，越容易顶到模型上下文上限或成本失控。

---

## 2. 参考项目机制（源码复核）

| 项目 | 上下文/压缩机制 | 存储粒度 |
|---|---|---|
| **QwenPaw** | **9 段渐进压缩**：persist → trigger → pre-fold（折叠已完成轮的工具结果）→ split（可淘汰中段 \| 保留尾部 + 当前轮）→ summarize（滚动摘要）→ add_eviction（中段折进索引，可 `recall_history(op=expand)` 回查）→ live-fold → think-fold → active-fold。触发条件 `tokens > trigger_ratio × context_size`，并预留输出 token（`effective_hard_limit = context_size - output_reserve`）。手动 `/compact` 把 `trigger_ratio` 降到强制触发。 | 原始对话按消息行存 SQLite `conversation_history`（`session_id`/`agent_id`/`created_at`），**不按天**；「按天」只在精选记忆 `MEMORY.md`/`memory/YYYY-MM-DD.md` 日记层。 |
| **Yuxi** | 摘要中间件：超过 **256×1024 = 262144 token** 触发摘要，保留最近 **10 条** + 摘要，旧历史归档到文件；先对工具结果做确定性降级（>300 token 的工具结果先卸载）。支持 `aforce_summarize` 手动压缩。 | Postgres `messages` 按消息行 + `thread_id`；**不按天**。 |
| **Dify** | 每轮只读当前会话最近 **≤500 条**，再按剩余 token 裁剪（约 2000 token）；无摘要、无跨会话。 | `messages` 按消息行 + `conversation_id`。 |
| **LobsterAI** | 压缩后用 capsule + top-K evidence 修补上下文；`forkSession({sourceSessionId, forkedFromMessageId, forkMode})` 可从某条消息 fork 出新会话，带 `parent_session_id`/`forked_from_message_id`/`fork_mode` 血缘。 | `cowork_messages` 按消息行 + `session_id`。 |
| **LangGraph** | 只给机制（checkpointer 单线程、BaseStore 跨线程），长上下文需自写 `pre_model_hook`（裁剪/摘要）。 | checkpoint 按 `thread_id`。 |

**共同结论**：
1. 原始对话只增不改，**不按天存储**；"按天"只是精选记忆的日记文件。
2. 模型上下文**有界**，靠「折叠工具结果 → 摘要中段 → 保留最近尾」控制；原文保留在库，可回查。
3. 检索是「过滤 + 排序 + LIMIT + 预算」的 top-k，不是从头线性遍历；有索引（QwenPaw FTS5）比无索引（Yuxi ILIKE）代价低。
4. 助理会话与新建对话**同构存储**（会话+消息），差别在流程与工具，不在表。

---

## 3. 设计

### 3.1 存储：同构 + `kind` 分流

- `requirement_conversation` 增列 `kind VARCHAR(16) NOT NULL DEFAULT 'REQUIREMENT'`，取值 `REQUIREMENT` / `ASSISTANT`。
- 消息仍统一落 `requirement_message`（真相源，已有 `conversation_id`/`seq`/`owner_id`）。
- **行为分流**：`kind=ASSISTANT` 走助理 pipeline（通用助理 + 工具）；`kind=REQUIREMENT` 走现有澄清 pipeline。
- **不复用两套表**：跨历史检索、owner 过滤、上下文窗口都只需要一套真相源；新建 `assistant_conversation` 会割裂真相源。

### 3.2 助理对话与「从对话创建任务」

助理 pipeline 暴露工具（工具结果进入 ReAct 循环，不预灌全量历史）：

| 工具 | 作用 | 范围 |
|---|---|---|
| `search_history(query, scope)` | 在历史消息中检索，返回有界片段（会话标题/时间/id） | `scope=CURRENT` 仅当前会话；`scope=ALL` 当前用户全部会话（owner 过滤） |
| `read_conversation(conversationId, anchorMessageId?, limit?)` | 按会话读窗口（锚点前后） | owner 过滤 |
| `create_task_from_context(summary, sourceConversationId, sourceMessageIds)` | 新建 `kind=REQUIREMENT` 会话，写入摘要 + 源引用（`parent_conversation_id`/`forked_from_message_id` 血缘），进入澄清 | 显式动作，返回新会话链接 |

**原则**：助理对话**不静默切模式**；「建任务」必须由工具/按钮显式触发，并回执「已从本次对话创建需求会话」。

> **v1 实现口径（2026-11-03）**：检索改为「调用 LLM 前预注入 top-k 历史片段 + 精选记忆」（`ConversationHistorySearchService`，对齐 QwenPaw auto-recall），复用 `PlatformAgentExecutionService.executeSync`；「建任务」为显式 REST 接口 `createTaskFromContext`。未用 LLM 工具调用（`@Tool` 需绑定层 `agent_mcp_server` 授权，成本高，留二期）。

### 3.3 上下文窗口：计量

新增 `ContextUsageService`，按会话计算占用：

```
usage = {
  systemPromptTokens,  // 系统提示词
  toolTokens,          // 工具 schema
  skillTokens,         // 注入的技能规范
  messageTokens,       // 压缩后仍进入 prompt 的消息
  otherTokens,         // 框架/临时开销
  totalTokens,
  contextSize,         // 模型上下文窗口
  ratio = totalTokens / contextSize
}
```

- **计量来源**：优先用**上一次模型调用返回的 `usage.prompt_tokens`**（准确）；调用前预测用 `TokenEstimator`（纯函数：`charsPerToken` 可配 + 每条消息固定开销，单测覆盖中英混排）。
- 分项与截图一致：系统提示词 / 系统工具 / Skill / 消息 / 其他。

### 3.4 自动压缩与手动压缩

统一入口 `ContextCompactor.compact(conversationId, force)`：

```
1. persist：确保窗口消息已落库（真相源）
2. trigger：非 force 且 tokens <= trigger_ratio * contextSize → 不压缩
3. pre-fold：折叠已完成轮次的工具结果（保留可回查指针）
4. if 仍超阈值：cutoff = 保留尾部起点（尾部预算 = reserve_ratio * contextSize + 首条用户锚点）
5. summarize：把 compacted_until_seq .. cutoff 的中段摘要进滚动摘要（失败保留上一版）
6. update state：compacted_until_seq = cutoff, summary, version+1
7. rebuild prompt = system + 滚动摘要 + 最近尾 + 当前轮
```

- **自动**：每次 LLM 调用前执行（`force=false`）。默认 `trigger_ratio=0.80`，`reserve_ratio=0.20`（对齐 QwenPaw；可配）。
- **手动**：`POST /requirement-conversations/compactById/{id}` 或对话内 `/compact`，`force=true` 跳过阈值，立即压缩并返回前后 token。
- **原文不删**：压缩只改「喂给模型的上下文」，`requirement_message` 全量保留；被压缩内容通过 `search_history` 可回查（对齐 QwenPaw recall）。
- **摘要定位**：滚动摘要作为「快速参考」，原文检索作为「事实依据」，system prompt 明确二者优先级。

### 3.5 数据模型（Flyway V135+）

```sql
-- 会话类型
ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'REQUIREMENT';
-- CHECK (kind IN ('REQUIREMENT','ASSISTANT'))
-- 血缘（create_task_from_context）
ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS parent_conversation_id BIGINT;
ALTER TABLE requirement_conversation
    ADD COLUMN IF NOT EXISTS forked_from_message_id BIGINT;

-- 上下文压缩状态（每会话一行）
CREATE TABLE IF NOT EXISTS conversation_context_state (
    id                  BIGINT PRIMARY KEY,              -- 应用侧雪花（与 BaseEntity 一致）
    conversation_id     BIGINT NOT NULL,                 -- 逻辑唯一（软删后允许重建）
    compacted_until_seq INTEGER NOT NULL DEFAULT 0,
    summary             TEXT,
    summary_version     INTEGER NOT NULL DEFAULT 0,
    trigger_ratio       NUMERIC(4,3) NOT NULL DEFAULT 0.800,
    reserve_ratio       NUMERIC(4,3) NOT NULL DEFAULT 0.200,
    enabled             INTEGER NOT NULL DEFAULT 1,
    context_size        INTEGER,
    last_token_usage    INTEGER,
    owner_id            BIGINT,
    last_compact_at     TIMESTAMPTZ,
    create_by           VARCHAR(64) NOT NULL DEFAULT '',
    update_by           VARCHAR(64) NOT NULL DEFAULT '',
    create_time         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_conversation_context_state_conv
    ON conversation_context_state (conversation_id) WHERE deleted = 0;
```

### 3.6 API 与前端

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/requirement-conversations/contextWindowById/{id}` | 返回分项占用、百分比、已压缩到的 seq、摘要版本、阈值 |
| POST | `/requirement-conversations/compactById/{id}` | 手动压缩，返回压缩前后 token 与淘汰条数 |
| GET | `/requirement-conversations?kind=ASSISTANT` | 助理会话列表（左栏历史） |

前端：对话页/工作台增加「上下文窗口」浮层（环形或进度条 + 分项行 + 「压缩上下文」按钮 + 接近阈值的自动提示）；工作台中间框为助理模式，左栏历史可搜索，命中历史可「继续问」。

**授权口径（CODE_STYLE §43 登记）**：`assistantProfile` / `myArtifacts` / `workbench` / `workbenchStream` / `contextWindowById` 为**登录即可读**——按 `owner` 过滤，无用户态（如外部 Agent 通道）返回空/默认值；`compactById` 为写动作，复用 `conversation:send` 动作码。此为既定口径。

### 3.7 分阶段

| 任务 | 内容 | 依赖 |
|---|---|---|
| `PA-11` 助理对话与跨历史检索 | `kind` 分流、助理 pipeline、`search_history`/`read_conversation`/`create_task_from_context`、`pg_trgm` 索引 | `PA-1` / `PA-2` |
| `PA-12` 上下文窗口与压缩 | `ContextUsageService`、`TokenEstimator`（纯函数+单测）、`ContextCompactor`、`conversation_context_state`、API、前端浮层 | `PA-1`；与 `PA-11` 共用会话 |

---

## 4. 边界与风险

- **压缩不改真相源**：任何压缩不得删除 `requirement_message`；否则跨历史检索与审计失效。
- **摘要质量**：首版摘要用 LLM，失败保留上一版并降级为「仅折叠工具结果」，不阻断对话。
- **检索成本**：一期 `ILIKE`/`pg_trgm` 会有扫描成本；数据量大时上 `tsvector`/向量（二期）。
- **单用户边界**：owner 过滤已由 `PA-1` 落地；多租户另议。
- **模式切换可见**：`create_task_from_context` 必须回执并留血缘，禁止静默转模式。

---

## 5. 引用材料

- 计划：`plan/HelloAI 个人助理工作台实施计划（2026-11-02）.md`（`PA-11` / `PA-12`）
- 现状：`helloai-core/.../ClarifyPromptRenderServiceImpl.java`（`HISTORY_WINDOW=40` 固定滑窗）
- 参考源码：
  - QwenPaw：`scroll/manager.py`（9 段压缩、`should_compress`、`/compact`）、`scroll/memoryspace.py`（FTS5 + 行/时间预算）、`memory/prompts.py`（日记/精选分层）
  - Yuxi：`runtime/middlewares/summary.py`（256K 阈值、保留 10 条）、`runtime/middlewares/memory.py`（memory 注入 + 检索工具）、`repositories/memory_history.py`（跨线程/单线程检索）
  - LobsterAI：`coworkStore.ts`（`cowork_messages`、`forkSession` 血缘）
  - Dify：`core/memory/token_buffer_memory.py`（最近 500 条）
  - LangGraph：`checkpoint/base`（thread）、`store/base`（跨线程 BaseStore）
- 治理：`doc/文档体系分类与治理规则.md`