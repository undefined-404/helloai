# HelloAI 内部 LLM 执行链 与 开源 agent 架构比对（2026-10-12）

> **性质**：只读比对报告（不改业务代码 / 配置 / 迁移）。
> **动机**：V2.2 计划以 **skills 治理**为第一项，把内部 LLM 从"只能对话"提升为"有基本解决能力的 agent"。本报告回答：**加 skills 之前，先要确认骨架对不对**——否则技能加上了，模型仍是聊天模型。
> **参考源码**（实测位置）：
> ```text
> E:/workspace/deepseek-harness-master    DeepSeek Harness（TS，DSH）—— 用户点名的"技能自动装配"参考架构
> E:/workspace/AgentTeams-main             AgentTeams（Go）+ 内嵌 deepseek-harness 封装镜像
> E:/workspace/Octop-main                  Octop（Python）
> ```
> **HelloAI 基准**：HEAD `2e6ce058`，工作区干净。

---

## 0. 三句话结论

1. **HelloAI 的骨架不缺**——派发 → 命令 → 消费编排 → 手动工具循环 → turn/step 事件 → 回写 REVIEW 已闭环，结构上接近成熟产品。
2. **缺的是「手脚」**：内部 LLM（`API_KEY_LLM`）的工具面**默认被掏空**（13 个 MCP 工具全部对它过滤），技能注入是**提示词级速览**；→ 它是「能想不能做」的单轮文本生成器。
3. **技能装配形态与 DSH 不一致**：DSH 是「**全量目录注入 + 模型惰性加载正文**」，HelloAI 是「**任务侧挑技能 + 注入截断速览**」。**用户的担心成立**：只做 skills 治理（安装/闸门/目录）会产出"更多提示词"，**不会**让内部 LLM 进化成 agent。

---

## 1. 三家参考架构怎么跑「内部 LLM agent」

### 1.1 DeepSeek Harness（DSH）—— 一切皆插件的会话日志架构

| 维度 | 做法 | 证据 |
|---|---|---|
| 设计哲学 | Cordis 底座，**一切皆插件**（模型/工具/会话/循环本身都是插件），无特权内核 | `docs/architecture.zh.md:11,13` |
| 核心铁律 | **模型可见 ⟺ 已记录**——凡抵达模型请求的东西必须能从仅追加的会话日志重建 | `docs/architecture.zh.md:100`；`AGENTS.md:108` |
| 生命周期 | `followup` → 唤醒 driver → `agent/status=running` → `turn/start` → 认领输入 → **`agent/pre-step` waterfall** → `step/start` → 装配 prompt → `llm/stream` → `assistant/message` → 工具调度 → `step/end` → `turn-stopping` → `turn/end` → `idle` | `docs/agent-lifecycle.zh.md:21-73` |
| turn / step | **turn** = 一次排空已接纳输入（含 0+ step）；**step** = 一次模型请求 + 它调用的工具 | `docs/glossary.zh.md:37,38` |
| 循环本体 | `ReactLoopAgent`：`kick()` 反复 `turn()`；`step()` 内 `while(true)`——有 tool-call ⇒ `executeToolCalls` 并 `continue`，无 ⇒ `completed` | `packages/core/agent-loop/src/agent.ts:210-223,412-418` |
| 扩展点 | **waterfall 事件**（监听器必须 `next()` 才委托，否则短路）：`agent/pre-step`（唯一权威 reject/enter 链）、`agent/request`、`agent/request-error`、`llm/stream`、`tools/pre-execute → guard → execute → post-execute → result` | `docs/subsystems/core.zh.md:241,225-239`；`docs/subsystems/tools.zh.md:172` |
| 终止条件 | 无 tool-call ⇒ `completed`；`max-tokens` 为 **sticky**；工具带 `concludesTurn` ⇒ 停轮；`aborted`/`error`；pre-step 被拒 ⇒ `blocked` | `agent.ts:410-418,285-290,295-299,267-277` |
| **★ 技能自动装配** | **不是按任务挑技能**，而是「注册表发现 × `modelInvocable` × scope 可见性」三者求交：在 `agent/pre-step` 把**全量**可调用技能的**目录**（已排序 `name` + 规范化/XML 转义/长度封顶 500 的 `description`，**不含正文/路径**）注入为**持久 user-role**；后续 step 用 **sha256 digest** 感知变化并按需替换；正文由模型调 **`skill({name})` 工具惰性加载** | `docs/subsystems/skills.zh.md:231,233,235`；`packages/skill/tool-skill/src/index.ts:213-251,319-321,81-160` |
| 技能形态 | **markdown 文档**（`SKILL.md` + frontmatter：`name`/`description`/`whenToUse`/`metadata`/`disable-model-invocation`），**不是可执行体** | `packages/skill/skill-filesystem/src/index.ts:810-834` |
| 技能 ≠ 工具 | `tool-skill` 向 `ctx.tools` 注册一个名为 `skill` 的**工具**作为消费入口；技能本身不出现在工具 schema 里 | `packages/skill/tool-skill/src/index.ts:81,161` |
| 语义位 | `invocation: {modelInvocable, userInvocable}`；**没有 `requiredTools` / `dependencies`** | `docs/subsystems/skills.zh.md:98-104` |
| 能力怎么来 | **工具面**（`ctx.tools`）+ **文件系统/进程提供方**（`ctx.fs`/`ctx.shell`）——把 provider 指向远程沙箱，Bash/PTY/LSP 一并搬过去 | `docs/architecture.zh.md:106` |

> **一句话**：DSH 的技能是**说明书**，能力来自**工具与文件系统**；"自动装配"= 全量目录 + 模型自己路由 + 用到才加载。

### 1.2 Octop —— agent = 可热插拔配置 + 工作区文件系统

| 维度 | 做法 | 证据 |
|---|---|---|
| 循环本体 | LangGraph 图（在外部 PyPI 包 `octop-harness` 内），Octop 只提供输入契约与终止参数；`max_iters` → `recursion_limit` | `Makefile:57`；`settings/runtime_limits.py:47-49` |
| 产品策略切面 | **7 个中间件**按"外→内"显式排序（插件链 / TokenQuota / Reasoning / KnowledgeHint / BrowserProfile / BinaryReadGuard / WorkspaceImage / ThreadArtifacts / **OctopUiOffload 最内层**） | `agents/manager.py:3226-3243,3219` |
| 能力装配件 | 一次 `HarnessAgentConfig`：模型 / **`tools=merged_tools`** / **`skills_dir`** / `system_prompt` / `memory` / `backend`（本地·远程·沙箱同构）/ `mcp_server_configs` / `middleware` / HITL `interrupt_on` | `manager.py:3360-3389,3306-3318,3245-3250` |
| 技能 | 工作区目录扫描（`<ws>/skills`、`<ws>/.octop/skills`）；内置种子只装 `skill-manager`（含 `scripts/manage_skills.py`）⇒ **技能=文本+可执行脚本**，靠 agent 自己的 `execute_shell_command` 跑 | `workspace/dir.py:395-396`；`builtin_skills/__init__.py:41-87`；`skill-manager/SKILL.md:1-30` |
| 技能热更新 | 仅改 `skills_dir` 并触发 `_init_graph`，**不整体重建** | `manager.py:2145-2175,2461-2463` |
| prompt 装配 | 装配器层（非每轮拼接）：persona 渲染成 `system_prompt` + 工作区 `SOUL.md`/`AGENTS.md`/`MEMORY.md`，由 harness 在图构建时合成 | `persona/loader.py:38-69`；`manager.py:1950-1978,3269-3273`；`docs/personas.md:3-11` |
| 技能进视野 | 装配时挂 `skills_dir`；`skills` 是**上下文用量里独立 segment** ⇒ 以 prompt 文本形式计入每次模型调用 | `threads/context_breakdown.py:12-20` |
| 产出 | 工作区文件 + `ThreadArtifactsMiddleware` 把产物路径写进 `threads.artifacts` + 通道推送 | `middleware/thread_artifacts.py:74-123`；`threads.py:293-322` |

### 1.3 AgentTeams —— 两轴分类，内部 LLM = DSH headless

- **两轴**：基础设施 backend（docker / k8s / sandbox，`backend/interface.go:319-349`）× Worker **runtime 6 选 1**（openclaw / copaw / hermes / openhuman / qwenpaw / **deepseek-harness**，`interface.go:33-40`）。**"内部 LLM agent" = `deepseek-harness`（headless）**，判定依据：只有 `qwenpaw` 与 DSH 消费控制器投影的 `runtime.yaml`（含 `desired.model`/`gatewayUrl`/`gatewayKey`，`interface.go:67-71`；`service/runtime_config.go:84-90`）。
- **循环不在 Go**：桥接 spawn `dsh` 跑一次 turn（`matrix_bridge.py:331-364`），循环体即 1.1 的 `ReactLoopAgent`。
- **`internal/skillscan/` 不是"发现"而是安全闸门**：两级——上传尽力（block→422，不可用则放行标 `skipped`）、**指派物化强制**（扫描不可用则**不拷贝**）；`CRITICAL/HIGH→block`。`skillscan/skillscan.go:1-28,51-75`；`deployer.go:1041-1051`；`skills_handler.go:451-467`。
- **技能装配**：DSH `skill-filesystem` 发现 → **TeamHarness 按角色过滤并拷贝**到 DSH 技能根（`roles.includes(role)`）→ `cordis.patch.yml` 锁定为唯一自定义技能目录（关掉默认根）。`prepare-skills.js:26-72`；`cordis.patch.yml:1-6`；`plugin.yaml:16-45`。
- **技能↔工具**：技能 = `SKILL.md`（正文用文字指导模型调 `taskflow`）；工具 = MCP server（`mcp/server.py` 提供 `taskflow`/`filesync`/`artifact`…）；**角色级工具裁剪**（Worker 屏蔽 `message` 工具）。`plugin.yaml:46-60`；`server.py:30,92-595,615-621`。
- **prompt 装配**：DSH `SystemPrompt` 服务按 order 组装 section，TeamHarness 以 **order 20** 注入协作段。`core/system-prompt/src/index.ts:337-543,128-131`；`context.js:67-97`。
- **产出**：Workspace `outbox/` → OSS → Matrix `m.file`；控制器双层审计落 `audit/<date>.jsonl`。`matrix_bridge.py:186-285,455-499`；`audit/audit.go:1-13,106-171`。

### 1.4 三家共识（抽象出来的"成熟 agent 骨架"）

```text
① 循环 = 有界的「模型调用 ↔ 工具调用」driver；turn/step 两层；终止条件显式
② 扩展 = 可 next() 的中间件 / waterfall 拦截链（唯一策略切面）
③ 技能 = markdown 说明书（frontmatter），从文件系统/注册表发现，注入为「目录」而非全文
④ 能力 = 工具面 + 文件系统/进程后端 —— 技能只有配上「手脚」才有产出
⑤ 装配 = 统一装配器产出「上下文 + 工具 + 技能目录」，可热更新
⑥ 产出 = 落到工作区的文件 / 对象存储，再由平台物化成交付物
```

---

## 2. HelloAI 内部 LLM 现状实测（关键发现）

### 2.1 骨架：基本齐全（不必重写）

```text
派发   SubTaskAutoExecutionDispatcher:39-52（@Async+AFTER_COMMIT）→ :72-73 createAssignedCommand(…, mergeSkills(subTaskId))
命令   ExecutionCommandServiceImpl:67-99（三表同事务）→ :126-146 按 dispatch-mode 分发（EVENT/MQ/NONE）
消费者 LocalExecutionCommandConsumer:144-258（markRunning CAS → startIfNeeded → assemble → agentRuntimes.get(0).execute → afterTurn）
循环   ChatModelToolLoop:85 for(i<maxIterations) → :87 chatModel.call → :100-104 文本终态 → :106-123 工具循环回喂 → :129 上限终态
       :147-152 setInternalToolExecutionEnabled(false)（模型只出调用请求，平台执行）
事件   RuntimeTurnExecutor:76-146 —— AGENT_STARTED(step=1) / SKILL_RESOLVED(5) / TOOL_RESOLVED(6) / ENVIRONMENT_RESOLVED(7) / CONTEXT_BUILT(2) / AGENT_COMPLETED(0)
回写   ExecutionResultHandler:86-100（source=INTERNAL）→ :316-331 afterCommit 物化附件 → REVIEW
```

- **turn/step 模型存在**（ADR-001）：`doc/design/adr/ADR-001-run-turn-step-model.md:28-46,96-114`。
- 内部与外部**共用同一回写通道**（`handleReport`），仅 `source` 不同：`McpToolServiceImpl.java:626-639`（EXTERNAL）vs `ExecutionResultHandler.java:86-99`（INTERNAL）。

### 2.2 ★ 工具面：内部 LLM 被**掏空**

```text
getEnabledToolsForAccess: AgentMcpServerServiceImpl.java:267-275
    if (accessType != API_KEY_LLM) return enabled;
    return enabled.stream().filter(not in MCP_SESSION_TOOLS);
MCP_SESSION_TOOLS（13 个）= :88-102（含 getSubTaskDetail / submitResult / getDepsSummary / uploadArtifact …）
```

- 默认 EXECUTOR 授权集 = 13 → 过滤后 = **空集**。
- 实际可调工具**只剩技能 `requiredTools` 联动进来的** `web_search` / `search_knowledge`；而 4 个内置技能里**只有 `eng-web-research` 声明了它们**（`skills/plugins/eng-web-research.md:6-9`），其余 `requiredTools: []`。
- ⇒ 任务不带该技能 + 供应商凭据/KB 不可用时：**工具面为空 ⇒ 纯单轮文本生成**。

### 2.3 没有"手脚"

| 能力 | 内部 LLM | 证据 |
|---|---|---|
| 读原始附件正文 | **无** | 全仓无 `readAttachmentRaw`；唯一入口 `getSubTaskDetail` 的 `attachments` **只返元数据**（`McpMcpServer.java:392-393`；`McpToolService.java:215-233`），且它本身在 `MCP_SESSION_TOOLS` 内 → 对 `API_KEY_LLM` **被过滤**。只能吃平台在 prompt 里预内联的依赖内容（`AgentRuntimeContextAssembler.buildDependencySection:629-696`） |
| 执行脚本 | **无** | 全库 `@Tool` 16 个（13 MCP + echo + web_search + search_knowledge），无 shell；`ProcessBuilder` 全仓仅 1 处且属系统备份（`system/backup/PgDumpRunner.java`） |
| 写文件/工作区 | **无** | 无文件写工具；沙箱真身里**只观测日志**（`RuntimeTurnExecutor.observeSandbox:248-263`），`runtime/sandbox/` 仅接口无 exec 实现 |
| 产出交付物 | **平台侧代劳** | 模型只产出文本，平台解析后物化（`ExecutionArtifactServiceImpl.java:106-148`）；模型自己**没有** `uploadArtifact`（也被过滤） |
| 外部 agent 对照 | 有 | 外部 CLI_CLIENT 可用 `getSubTaskDetail` + REST `GET /api/attachments/downloadById/{id}` 取字节（同一描述、未被过滤） |

### 2.4 循环深度

- **`maxIterations` 默认 5**：`AgentLoopInput.java:47` `DEFAULT_MAX_ITERATIONS = 5`；`RuntimeTurnExecutor.java:130-139` 构造时第 6 参传 `null` ⇒ 实际取 5。
- 无上下文压缩（无 DSH 的 `dsh-compaction-basic` 对应物）、无 `concludesTurn` 式显式收尾语义。

### 2.5 ★ 技能装配形态（与 DSH 的关键错位）

| 环节 | **DSH** | **HelloAI** |
|---|---|---|
| 谁决定装哪些 | **不需要决定**：scope 发现 × `modelInvocable` 的**全量目录** | **任务侧 `requiredSkills`**（`AgentRuntimeContextAssembler.java:135-136` ← `AgentTask.skills`） |
| 注入什么 | 目录（`name` + 封顶 `description`），**持久 user-role**，digest 变更感知 | **速览**（`SkillSpeedSummaryRenderer` 在 `\n---\n` 截断、剔 h1，`:26-43`），拼进 **system prompt 段**（`:140-144`） |
| 正文怎么来 | 模型调 **`skill({name})` 惰性加载**（progressive disclosure） | **不加载正文**——正文全文只有安装/存储侧有 |
| 模型能否知道"还有别的技能" | **能**（全量目录） | **不能**——未入选技能对模型不可见 |
| 技能→能力 | 技能本身不授能力，**能力来自工具面** | `requiredTools → mergeTools`（`:155-159`）是**能力级**，但当前几乎全空 |
| 语义位 | `invocation{modelInvocable,userInvocable}`；无 `requiredTools` | **有** `requiredTools` 联动 + `REF-1.3` 条件可用/动态描述（`ToolRegistry.resolve(names, ToolContext)` 已是"模型可见工具唯一判据"，`ToolRegistry.java:49-77`） |

> **判读**：HelloAI 的 **`requiredTools` 与 `ToolRegistry` 语义位其实比 DSH 更"声明式"**——基础设施**已经具备**承载 DSH 式目录注入的条件；问题是**入口设计成了"任务侧挑选 + 摘要注入"，而不是"全量可见 + 模型自选 + 惰性加载"**。

---

## 3. 差距清单（按结构优先级，不是按工作量）

| # | 差距 | 性质 | 为什么排这个序 |
|---|---|---|---|
| **D1** | **内部 LLM 工具面被掏空**（13 工具全过滤，只剩 `requiredTools` 带进来的 2 个） | **一阶（手脚）** | 技能再准，模型没有可改变外部世界的动作 ⇒ 永远只是文本生成器。**这是"是不是 agent"的分水岭** |
| **D2** | **无原附件读取能力**（`getSubTaskDetail` 被过滤 + 无 HTTP 工具），只能吃平台预内联摘要 | 一阶 | 直接命中 `LOG-20261011-007` 事故根因："需读原始字段的任务能力上做不到" |
| **D3** | **技能装配形态错位**：任务侧挑选 + 速览注入，模型看不到全量技能、拿不到正文 | 二阶（装配） | 与 DSH 的"自动装配"语义不一致；用户担心的正是这一条 |
| **D4** | **无通用拦截链**（无 DSH waterfall / Octop middleware 对应物） | 三阶（可扩展性） | 将来每次加策略都要改主链 |
| **D5** | `maxIterations=5` 偏小且不可配；无上下文压缩 | 三阶 | 有工具后需要更多步数 |
| **D6** | 无"技能目录 digest / 变更感知"（DSH 有） | 三阶 | 技能热更新后模型视野同步 |

---

## 4. 对 V2.2 的建议（结论：skills 治理不能单独做）

**用户的判断成立**：把 skills 治理（安装 / 闸门 / 目录 / 版本）做到 DSH 那样，**如果不同时打开工具面**，内部 LLM 依然"能想不能做"。建议把 V2.2 的"提升内部 LLM 能力"拆成**两条必须并行**的线：

```text
线① 能力面（先决条件，缺它 skills 无效）
    给内部 LLM 一条「不依赖 MCP 会话」的工具通道，最小集：
      · 读原始附件正文（对齐外部 agent 的 REST 取回能力）
      · 文件/工作区读写（哪怕先只读 + 只写产物目录）
      · 一个正式的「提交/交付」动作（当前是平台代劳，模型无感知）
    落点提示：复用 ToolRegistry 语义位与 ToolCallbackContributor 端口（REF-1.3 已具备），
             经 skill 的 requiredTools 声明与内部 LLM 的授权集接通；不要新造平行通道。

线② 装配形态（对齐 DSH 的"自动装配"语义）
    · 注入「全量可调用技能目录」（name + 封顶 description），而非按任务挑 + 摘要；
      —— 落点：ToolRegistry.resolve 的 ToolContext 已能承载"本轮可见能力"，同一范式可承载"本轮可见技能"
    · 暴露 skill(name) 式惰性加载（正文按需进入上下文），与 DSH 的 progressive disclosure 对齐
    · 保留 requiredTools 作为**能力级声明**（HelloAI 比 DSH 更强的一点，别丢）

线③（可后置）扩展点与深度
    · 通用 hook 链（对应 DSH waterfall / Octop middleware）作为后续策略落点
    · maxIterations 提为可配（十位数量级）；上下文压缩
```

**不做的话会发生什么**：V2.2 交付"技能包安装/闸门/目录/多版本"，审计看着齐全，但内部 LLM 的工具面仍为空 ⇒ 它依然是"能想不能做"，skills 只是**更长的提示词**——即用户担心的"加上了 skills 但依旧是聊天模型"。

---

## 5. 证据索引（关键 path:line）

**HelloAI**：`AgentMcpServerServiceImpl.java:88-102,267-275`（工具面过滤）· `ChatModelToolLoop.java:85,100-104,106-123,129,147-152`（循环）· `AgentLoopInput.java:47`（maxIter=5）· `RuntimeTurnExecutor.java:76-146,248-263`（事件/沙箱观测）· `AgentRuntimeContextAssembler.java:135-136,140-144,155-159,629-696`（装配）· `AgentSkillSpecServiceImpl.java:41-79,121-127` + `SkillSpeedSummaryRenderer.java:26-43`（速览）· `ToolRegistry.java:49-77` + `ToolRegistryImpl.java:71-104`（语义位）· `ExecutionResultHandler.java:86-100,316-331` · `ExecutionArtifactServiceImpl.java:106-148` · `McpMcpServer.java:392-393` · `McpToolService.java:215-233` · `AgentCapability.java:118-125` · `skills/plugins/eng-web-research.md:6-9`

**DSH**：`docs/agent-lifecycle.zh.md:21-73` · `docs/architecture.zh.md:11,13,59-61,96-108,114-125` · `docs/subsystems/skills.zh.md:13,66-77,98-104,231-235,249` · `docs/glossary.zh.md:9,37,38` · `packages/core/agent-loop/src/agent.ts:38-46,64,210-223,246-330,332-420,410-418` · `packages/skill/tool-skill/src/index.ts:81-160,213-251,319-321` · `packages/core/system-prompt/src/index.ts:337-543`

**Octop**：`agents/manager.py:1950-1978,2145-2175,2583,3226-3243,3306-3318,3360-3389` · `agents/workspace/dir.py:395-396` · `agents/builtin_skills/__init__.py:41-87` · `agents/middleware/thread_artifacts.py:74-123` · `agents/settings/runtime_limits.py:47-49` · `agents/threads/context_breakdown.py:12-20` · `agents/conversation_mode.py:98-113`

**AgentTeams**：`agentteams-controller/internal/backend/interface.go:33-40,67-71,319-349` · `internal/service/runtime_config.go:18-20,84-90` · `internal/skillscan/skillscan.go:1-28,51-75` · `internal/service/deployer.go:1041-1051` · `plugins/teamharness/plugin.yaml:16-45,46-60` · `plugins/teamharness/adapters/deepseek-harness/prepare-skills.js:26-72` · `plugins/teamharness/adapters/deepseek-harness/context.js:49-97` · `deepseek-harness/scripts/matrix_bridge.py:331-364,429-537` · `internal/audit/audit.go:1-13`

---

## 6. 与既有登记的关系

- 本报告的 D1/D2（工具面 / 原附件读取）与 `LOG-20261011-007` 的 **A 能力闸**同源但**方向相反**：A 是"拦掉它做不到的活"，D1/D2 是"**让它做得到**"。两者不冲突——A 治当下止损，D1/D2 治能力缺口。
- D3（技能装配形态）是 `REF-1.2c / REF-1.3` 的自然延伸：**语义位已具备，缺的是"全量目录 + 惰性加载"的入口设计**。
- ⬜ 本报告为**分析结论**，尚未进入 `plan/` 排期；如需推进，建议单独立项（如 `REF-8` 内部 LLM 能力面）。

---

## 7. 任务执行层差距复核（2026-10-12 表核）

> 用户提供一份自研对照表（AgentTeams / Octop / HelloAI 三维），要求核对「任务执行层」差距。本节逐行**用 `path:line` 实证复核**，并订正/强化其中的表述。

### 7.1 逐行核验（用户表 8 行）

| # | 用户表结论 | 复核判定 | 实证 |
|---|---|---|---|
| 1 | 多步循环归属：HelloAI 自写 `ChatModelToolLoop` | ✅ 属实 | `ChatModelToolLoop.java:58`（`implements AgentLoop`） |
| 2 | 循环骨架完整：`for(maxIter` + `hasToolCalls()` 分支 + ToolResponseMessage 回喂 | ✅ 属实 | `:85`、`:100-104`、`:106-123` |
| 3 | 技能形态：`skills/plugins/*.md` frontmatter 扫描化，命中后拼 section | ✅ 属实 | `AgentRuntimeContextAssembler.java:140-144`（`mergeSpecSections` 拼进 user prompt） |
| 4 | 技能进模型方式：user 消息纯文本；`systemPrompt` 硬编码 `""` | ✅ 属实 | `AgentRuntimeContextAssembler.java:213` `.systemPrompt("")`；`ChatModelToolLoop.java:74-76` 空 system 不发 |
| 5 | 工具是否真注入循环：**否**（13 个 MCP 工具对被过滤，`enabledToolCallbacks=List.of()`） | ⚠️ **归因需精确**（见 7.2 订正 ②） | `AgentMcpServerServiceImpl.java:267-275` → `RuntimeTurnExecutor.java:99,135,196-198` |
| 6 | 模型输出类型：纯文本，靠原生 `hasToolCalls()`，**无自研 agent 决策层** | ⚠️ **表述偏重**（见 7.2 订正 ①） | `:100-104`（存在薄决策层） |
| 7 | 任务生命周期：子任务走 `LocalExecutionCommandConsumer→RuntimeTurnExecutor`，但大量业务 LLM 面绕开循环 | ✅ 属实，且**实测更广**（见 7.2 强化） | 见 7.2 强化 |
| 8 | 父派生/子回传：聚焦 agent 未展开 | ➖ 超出本报告范围 | — |

### 7.2 订正 2 处 + 强化 1 处

**订正 ①（表述偏重）**：用户称 HelloAI「无自研 agent 决策层」。**实际有一个薄决策层**——`ChatModelToolLoop:100-123` 明确按 `assistant.hasToolCalls()` 分支，有工具则执行 + `ToolResponseMessage` 回喂并 `continue`，无则 `stop`。准确的差距不是「没有决策层」，而是「**决策层薄**」：只有「有无 tool_call」这一档，**没有结构化规划 / 计划解析 / 终止条件表达（如 DSH 的 `concludesTurn`）**。

**订正 ②（归因更准）**：用户称「工具**不**真注入循环」。更精确的说法是——**注射机制存在且正确，注射源被清空**：
- 机制在：`RuntimeTurnExecutor.java:135` 把 `resolveVisibleCallbacks(resolvedTools)` 作为 `enabledToolCallbacks` 传入 `AgentLoopInput`；`:147-149` 由 `ChatModelToolLoop` 挂上 `setToolCallbacks(...)` ⇒ 循环**支持**注入。
- 源为空：上游 `AgentMcpServerServiceImpl.java:267-275` 对 `API_KEY_LLM` 过滤 13 个 MCP 工具；`RuntimeTurnExecutor.java:99-102` 的 `resolvedTools` 随之为空 ⇒ `resolveVisibleCallbacks:197-198` **恒返 `List.of()`**。
- ⇒ 结论同（模型接不到 schema、`hasToolCalls()` 恒 false、循环首轮即 stop），但**归因从「循环不支持」改为「无可注射之物」**——这决定了修复方向（补工具面，而非改循环）。

**强化（实测范围比用户表更广）**：用户称「planner / review / clarify / finalreport 绕开循环走单次聊天」。**实测覆盖面更大**：

```
全仓 agentLoop.run() 生产调用点 = 1 处
    RuntimeTurnExecutor.java:130          ← 唯一走「真·多步循环」的路径（仅子任务执行）

全仓 platformAgentExecutionService.executeSync/executeStream 调用点 = 15 处 / 9 个类
    AgentExecutionPreviewServiceImpl.java:53
    planner/clarify/SearchGapAssessor.java:91
    planner/prompt/impl/PromptEnhancerServiceImpl.java:59
    planner/service/impl/PlannerDecomposeAsyncServiceImpl.java:144
    planner/service/impl/RequirementClarifyServiceImpl.java:433,471,888,959,1038,1303
    planner/service/impl/SearchQueryPlannerServiceImpl.java:195
    review/service/impl/FinalReportReviewServiceImpl.java:393
    review/support/ReviewExecutionEngine.java:87          ← 子任务评审也在此列
    task/service/impl/TaskFinalReportServiceImpl.java:252,389
```

即：**除了「子任务执行」这一条路径，其余全部业务 LLM 面（规划拆解 / 需求澄清 / 检索规划 / Prompt 增强 / 搜索缺口评估 / 子任务评审 / 终报告评审 / 终报告生成 / 执行预览）都走 `AgentExecutorRouter → ApiKeyAgentExecutor` 的单次聊天**（`ApiKeyAgentExecutor.java:42-64`：`agentChatClientService.generate(...)` 一次调用即 `AgentResult.success(..., "STOP", ...)`，**无循环、无工具**）。

### 7.3 任务执行层差距（本节结论）

**任务执行层 = 「接到任务后，agent 实际怎么把它做完」**。三家在这一层都有「三件套」，差异如下：

| 维度 | AgentTeams | Octop | **HelloAI** |
|---|---|---|---|
| **循环** | 外部 `ReactLoopAgent`（DSH，成熟资产） | 外部 `octop-harness` LangGraph（成熟资产） | 自写 `ChatModelToolLoop`（**骨架对，但只覆盖 1 条路径**） |
| **工具面** | MCP 工具编译进循环（`taskflow` 等） | `merged_tools`（MCP + 内置 + cron + KB），`tool_call_chunk → tool_result` 流式 | **空**：13 个 MCP 工具对被过滤；仅剩技能 `requiredTools` 的 `web_search/search_knowledge`，且 4 技能仅 1 个声明 |
| **工作区 / 手脚** | `Workspace` + `outbox/` → OSS | 工作区文件系统 + `skills_dir` + 脚本执行 | **无**：无原附件读取、无 shell、无文件写，交付物**平台代劳**（模型无 `uploadArtifact`） |
| **循环覆盖的 LLM 面** | 全部（worker = runtime） | 全部（agent 即 harness） | **1 / 16**（仅子任务执行走 loop；15 处业务 LLM 面走单次聊天） |

**一句话差距**：
> AgentTeams 与 Octop 把「agent 执行」**整体外包给成熟 runtime** ⇒ 任务执行层**天然满配**（循环 + 全量工具 + 工作区）；HelloAI **自写循环、骨架不差**，但任务执行层实际是「**循环壳 + 空工具面 + 无手脚 + 只覆盖 1/16 的 LLM 面**」——所以内部 LLM 在任务执行层里，退化成了**单轮文本生成器**。

**关键判读**：三家的差别**不在「有没有循环」**（HelloAI 有，且自研），而在**循环的两端**——**输入端（注什么工具）**与**覆盖面（哪些 LLM 面在循环里）**。这也是为什么「只做 skills 治理」无效：skills 治理改善的是第 5 层装配形态，**碰不到**第 1/2 层的工具面与循环覆盖面。

### 7.4 本节新增实证索引

`ChatModelToolLoop.java:58,74-76,85,100-104,106-123,147-149` · `RuntimeTurnExecutor.java:99-102,130,135,196-198` · `AgentExecutorRouter.java:17-30` · `PlatformAgentExecutionServiceImpl.java:49,82` · `ApiKeyAgentExecutor.java:42-64,107-110` · `AgentExecutionPreviewServiceImpl.java:53` · `SearchGapAssessor.java:91` · `PromptEnhancerServiceImpl.java:59` · `PlannerDecomposeAsyncServiceImpl.java:144` · `RequirementClarifyServiceImpl.java:433,471,888,959,1038,1303` · `SearchQueryPlannerServiceImpl.java:195` · `FinalReportReviewServiceImpl.java:387,393` · `ReviewExecutionEngine.java:87` · `TaskFinalReportServiceImpl.java:252,389` · `AgentRuntimeContextAssembler.java:140-144,213`
