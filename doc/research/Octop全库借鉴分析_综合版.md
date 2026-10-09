# Octop 全库借鉴分析（综合版 · 含采纳裁决）

> **Status: `Analysis`**（借鉴预研，不改变任何实现、不承诺排期）
> **分析日期**: 2026-10-09
> **勘察对象**: `E:/workspace/Octop-main`（TencentCloud/Octop `1.0.2b6`，main 分支快照，打包时间 2026-10-08 12:04）
> **关联**: [`Sandbox_Provider.md`](../design/Sandbox_Provider.md)（G-005）· [`Skill_Capability.md`](../design/Skill_Capability.md)（G-004）· `HelloAI 实现差距表.md`（G-001~G-016）· [`../archive/implemented/HelloAI_Phase2_C3_BrowserAgent设计预研.md`](../archive/implemented/HelloAI_Phase2_C3_BrowserAgent设计预研.md) · [`AgentTeams最新版借鉴分析_Java落点.md`](./AgentTeams最新版借鉴分析_Java落点.md)（配套报告）
> **沿革**: 本文档**合并并取代** `Octop_借鉴分析_沙箱_技能化_WebAgent接入.md` 与 `Octop全库借鉴分析_Java落点.md`（2026-10-09 归档删除）；对两份既有分析的关键论断已按 HelloAI 侧代码**实测复核并裁决**（§0.2），采纳清单按裁决重排（§10）
> **勘察口径**: 凡标 `[实测]` 的论断均由本地逐文件读取源码/文档得出并附 `路径:行号`；标 `[推断]` 的为基于 Octop 侧用法与文档的反推，已单独说明；**未核实的写「未核实」，不用「通常/一般」充当事实**。

---

## 0. 结论（先读这段）

### 0.1 一句话

**Octop 的 Python 代码对 helloai 没有一个字节可以搬，但它在「横切工程」上把答案写成了几乎可照抄的形状。** 最该抄的三条与功能无关：**① 大载荷旁路（数据面/控制面分离）—— 这是 helloai 唯一正在真实丢数据的地方；② 身份取「本次调用上下文」而非客户端参数 / 进程级注册表；③ 失败必须回叫、平台不替用户拉起未运行执行体。**

同时要划清边界：**helloai 与 Octop 是路线相反的两种架构**（分布式任务编排 vs 单进程自托管对话助手），Octop 的「单进程 / 无外部队列 / SQLite 双后端 / 进程内调度」等架构决策 **一律不要抄**（§9），这不是优劣，是路线分歧。

### 0.2 对两份既有分析的裁决（先划清，再引用，避免错误结论带入）

> 本节 7 条为 2026-10-09 按 HelloAI 侧代码实测复核后给出的裁定。原文档与本节冲突处以本节为准。

| # | 原文档结论 | 实测裁定 | 证据 |
|---|---|---|---|
| **1** | Doc1 A1「MCP 身份覆写中间件 = **修一个已登记的安全缺口**」，称 MCP 工具「依赖调用方自报 agentId」 | **已实现，结论过期**。13 个 `@Tool` 全部在方法体内 `requireAuthId(...)` 强制把入参 agentId 覆盖为鉴权身份（`helloai-core/.../mcp/McpMcpServer.java:88-93 / 119-122 / 150-153 / …`）；仅类级 javadoc（`:38-41`）未同步更新，属**注释漂移**非代码缺口 | `McpMcpServer.java`（抽查 pullTasks/ack 两处确认） |
| **2** | Doc1 A1 的「真正借鉴点 = 覆写中间件」 | 真正借鉴点应改为**身份的「输入源」**：helloai 鉴权身份靠客户端在 arguments 里自报的 `_sessionId` 反查（`McpAuthContext.java:138-151` 自述「spring-ai 1.1.0 不支持隐式注入，客户端必须显式传 `_sessionId`」） | `McpAuthContext.java:138-151` vs `octop/infra/agents/middleware/browser_profile.py:26-44` |
| **3** | Doc2 订正 3「`knowledge/gate.py` 是注入闸门」 | **成立**。`gate.py` 实为**能力就绪闸门**（embedding 依赖/模型下载/provider 可用性 → `feature_enabled && prerequisites_ok = usable`）；真正「什么不许进上下文」由 `KnowledgeSearchHintMiddleware`（无 KB 即从工具列表摘掉）+ `retrieve_context(char_budget=6000)` 实现 | `src/octop/infra/knowledge/gate.py:63-90`；`knowledge/hint.py:61-99`；`knowledge/retrieve.py:22-53` |
| **4** | Doc2 D-1「身份输入源 `_sessionId` 是模型可填字段 = 攻击面」 | **观察属实，定性夸大**。`_sessionId` 只是**查找键**，指向的鉴权快照由 `McpAuthFilter` 从 Authorization 头写入（不可伪造）；模型乱填只有 401 或命中「已用他人合法凭证建立过的会话」两种结果。属**协议卫生 / 纵深防御**问题，非可直接利用的活漏洞 | `McpAuthFilter.java:114-141`（鉴权源 = 头）+ `McpAuthContext.java:164-172`（查不到即 401） |
| **5** | Doc2 D-2「`SESSION_AUTH` 进程级注册表 = 单实例假设，**不修则多实例必挂**」 | **结构事实成立，严重度夸大**。① 当前部署为**单实例**（`docker-compose.server.yml:217-238` 无 replicas，nginx 仅反代一个 app），SSE 握手与工具调用必落同一进程；② 代码注释**已登记接受**「helloai 当前阶段可接受（§3.1 收官后再优化）」（`McpAuthContext.java:29-32`）；③ 系统**已有无状态 REST 别名通道** `POST /api/mcp/jsonrpc`（走 `@RequestAttribute("_authId")`，零 session；`checkIn/checkOut` 文档明写「无状态，无需 MCP session」）。正确定性：**「多实例部署前置条件」，非当前缺陷** | `McpAuthContext.java:45`；`McpController.java:266-341, 429-456`；`docker-compose.server.yml:217-238` |
| **6** | Doc2 A2「把 `_sessionId` 参数从协议里删掉」 | **不采纳**。删除 = 对存量外部 Agent（Qoder/Trae/CLI，当前即靠传 `_sessionId` 工作）做破坏性变更，直接违背 helloai 目标架构铁律「**REST / inbox 只增可选字段，外部 Agent 未升级无感知**」。正确做法：保留接收，新客户端引导走无状态 REST 别名，待 spring-ai 支持隐式注入后再平滑移除 | `doc/HelloAI 目标架构.md` §3 Planner 定界原则；`McpMcpServer.java:86-87`（sessionId/_sessionId 双参数兼容） |
| **7** | Doc2 A4「工具调用拦截层，消除 13 处重复覆写」 | **合理，但是重构而非缺陷修复**。13 处 `requireAuthId` 是「重复但正确」；抽 `ToolCallInterceptor` 链的价值是消除漂移风险（维护性），不应排在安全修复位 | `McpMcpServer.java`（13 处独立覆写） |

**另一条边界判断成立且更强**：Octop 快照**连 `pyproject.toml` 都没有**（`find` 全库无 `pyproject.toml` / `setup.py` / `requirements*.txt`），因此 `octop-harness` / `octop-gateway` / `octop-memory` / `octop-browser` 的**实现层完全不可核实**，只能核实 Octop 宿主的调用点与其自写契约文档。凡涉 harness 运行时（LangGraph 图、checkpoint、token 流、`awrap_*` 时序）的结论，一律只核实了 Octop 侧调用与文档描述。

### 0.3 三档采纳清单速览（裁决后）

| 档 | 代表项 |
|---|---|
| **A. 立即做**（安全/数据，独立交付，不动架构） | 载荷旁路 · SSRF 出站校验 · 首次运行锁定 · 结构化目录守卫 |
| **B. 填充既有规划**（不是改架构） | 沙箱 spec 声明化 → 探针 → 实现（对齐 `Sandbox_Provider.md` S2~S5）· 备份/恢复 |
| **C. 能力补全**（对齐差距表） | RAG 知识库 · 技能 frontmatter/目录扫描/摄入闸门 · 失败回叫闭环语义 · 能力摘除式治理 · MCP 会话指纹/串行化/探活 |
| **D. 缓做 / 登记** | MCP 身份链（登记为「多实例前置条件 + 协议卫生」，不删 `_sessionId`、不紧急重构）· 工具调用拦截层（按重构排期） |
| **E. 明确不做** | 单进程/双后端/进程内调度/Python 网关/CDP 逐帧直播/桌面移动语音交付面/命名约定代替 schema 无边界 |

---

## 1. 勘察边界

| 项 | 事实 |
|---|---|
| 快照规模 | `src/octop` 约 656 个 py / 144,008 行；`tests` 501 个 py / 90,411 行 `[实测]` |
| 可读的业务包 | `infra/{backend,skills,connectors,browser,agents,knowledge,history,gateway,cron,db,backup,setup,users,utils,bridge,voice,...}` 共 24 个 `[实测]` |
| **不可读**（外部依赖） | `octop-harness`（LangGraph 运行时）· `octop-gateway` · `octop-memory` · `octop-browser`；**无任何打包元数据**（无 `pyproject.toml`/`setup.py`/`requirements*.txt`），版本号无法确认 `[实测]` |
| 可读的契约文档 | `docs/` 21 篇 md（含 `adr/` 下 2 篇）+ 根 `AGENTS.md`（417 行）`[实测]` |
| 未采信 | 任何网络来源的 star 数、安全事件、社区评价 |

**含义**：Octop 的**宿主侧设计**（中间件怎么装、载荷怎么剥、沙箱 spec 怎么组装、技能包怎么校验、迁移怎么编号、备份怎么选内容）可逐行核实；**运行时内核**只能读契约文档，不能验证实现是否一致。

---

## 2. 横切工程纪律（最高价值，与功能无关）

### 2.1 横切关注点 = 工具调用拦截层（不是策略模式，是「唯一覆写点」）

Octop 在 `agents/manager.py` 一处装配 **7 个中间件**，每个只干一件事 `[实测]`：

| 中间件 | 挂载点 | 做什么 | 失败语义 |
|---|---|---|---|
| `TokenQuotaMiddleware` `:3228-3231` | `before_agent` | 用户 token 配额闸门 | 抛异常拦截整轮 |
| `ReasoningRequestMiddleware` `:3232` | `wrap_model_call` | 按轮覆写 provider 的 reasoning 参数 | 无覆盖则原样 |
| `KnowledgeSearchHintMiddleware` `:3233` | `wrap_model_call` | 重写 `search_knowledge` 描述 / 无 KB 时摘掉该工具 | 无 KB ⇒ 摘除 |
| `BrowserProfileMiddleware` `:3234` | `wrap_tool_call` | **强制覆写** `profile` 参数 = 当前用户 | **取不到用户 ⇒ fail-closed 拒绝** |
| `BinaryReadGuardMiddleware` `:3235` | `wrap_tool_call` | 拦截 `read_file` 读二进制 | 返回 `status="error"` + **告诉模型正确做法** |
| `WorkspaceImageMaterializeMiddleware` `:3236` | `wrap_model_call` | `workspace://` 引用在调模型前才物化成 data URI | — |
| `ThreadArtifactsMiddleware` `:3237-3241` | `wrap_tool_call` | 成功工具产出的文件路径登记到 `threads.artifacts` | 吞异常仅告警 |
| `OctopUiOffloadMiddleware` `:3242` | `wrap_tool_call`（**最内层**） | 大载荷剥离到 `artifact` | 吞异常，**绝不破坏工具调用** |

关键设计约束 `[实测]`：

- **顺序语义**：注释明写「列表首位 = 最外层；`OctopUiOffload` 放最内层，使所有外层中间件观测面一致」（`manager.py:3223-3225`）；
- **插件也能贡献中间件**：`PluginRegistry().build_middleware_chain(...)`（`manager.py:3201-3203`）——扩展点对插件开放；
- **安全策略 = 全局 + Agent 覆盖合并**：`SecurityPolicy.merge(global_policy, agent_override)`（`manager.py:3204-3206`）；
- **拒绝时要「纠偏」而非「报错」**：`binary_read_guard.py:81-95` 返回的是 `"read_file blocked for binary PDF \`x\`. Use execute_shell_command with the pdf skill ..."` —— **把正确路径写进错误消息**。

**对 helloai 的落点**：helloai 有 `ToolRegistry` / `ToolDefinition`（仅 `name + description` 两字段），但**没有「工具调用拦截层」**，校验逻辑散在 `@Tool` 方法体内（`McpMcpServer` 每方法各写一遍 `requireAuthId`）。建议在 `ToolExecutor` 与工具实现之间引入 **`ToolCallInterceptor` 链**：顺序 = 注册顺序、最内层先见原始返回；每个拦截器单一职责；**「模型能填的敏感参数」必须有且只有一个覆写点**；拒绝时返回可执行的纠偏文案。**注意（裁决 #7）**：这是重构项，不是缺陷修复项。

> **判据（可直接写进规范）**：*任何「模型可见且可填」的参数（`agentId`/`tenantId`/`userId`/`subTaskId`/`workspaceDir`/`credentialId`/`profile`），其取值必须由平台在拦截层无条件覆写；覆写点数量必须为 1；取不到隔离键时必须拒绝（fail-closed），不得退化为默认值。*

### 2.2 身份必须来自「本次调用上下文」（★对 helloai 最重要的一节）

**Octop 的答案（全文 71 行，形状可直接照抄）** `[实测]` `agents/middleware/browser_profile.py`：

```python
def _bind_browser_profile(request):
    if name != "browser_use": return request
    user_id = parse_octop_user_id(get_config()["configurable"].get("user"))   # ← 框架运行时上下文
    if user_id is None:
        return ToolMessage(content=_MISSING_USER, status="error")             # ← fail-closed，不是默认值
    args = dict(request.tool_call.get("args") or {})
    args["profile"] = user_browser_profile(user_id)                           # ← 覆盖，不是"建议"
    return request.override(tool_call={**tool_call, "args": args})
```

三件事同时成立：**覆写点在拦截层 / 参数不可选 / 拿不到隔离键就拒绝**。

**helloai 的实测现状**：

| 事实 | 证据 |
|---|---|
| 服务端**已**强制覆盖 `agentId`（13/13 工具） | `McpMcpServer.java:90-93, 119-122, …` |
| 但**身份输入源是客户端自报的 `_sessionId`** | `McpAuthContext.java:138-151`：「spring-ai 1.1.0 的 `SyncMcpToolMethodCallback` 反射器不认识 `McpSyncServerExchange`，`ToolContext.getContext()` 实际为空 map，sessionId 永远自动进不来。**客户端必须在 arguments 里显式传 `_sessionId`**」 |
| 鉴权上下文存于 `static ConcurrentMap`（进程级注册表） | `McpAuthContext.java:45` |
| 清理仅单 JVM TTL 扫描 | `SessionAuthCleaner.java:47-52`（`@Scheduled` 扫本进程 map） |

**综合裁定**（对应 §0.2 #4/#5/#6）：
- `_sessionId` 是模型可填字段、且身份解析入口可被客户端填写——这是**协议卫生欠账**，但鉴权快照本身由 `McpAuthFilter` 从 Authorization 头写入，**不可直接伪造**，非活漏洞；
- `SESSION_AUTH` 进程级注册表在**单实例部署下无实际风险**（当前部署即单实例），是「**多实例部署前置条件**」而非当前缺陷，且系统已有无状态 REST 别名通道可绕开 session；
- 修复路径按性价比：**长期**收敛为「无状态自携带签名凭证（验签 + Authorization 快照）」或外置 Redis（TTL），可顺带消灭 `SessionAuthCleaner`；**但不得删除 `_sessionId` 协议参数**（违背向后兼容铁律），应保留接收、引导新客户端走无状态通道。

### 2.3 失败必须回叫，禁止静默；平台不替用户拉起未运行执行体

Octop 两处口径一致 `[实测]`：

- **inbox worker 最后一行**：异常 ⇒ `status=failed` 时**仍然调用** `on_reply(status=failed, error_text=…)`，让 source agent 给用户兜底说明（`docs/agent-interop-mailbox.md:148`；§11 决策 5）；
- **专家团决策 15**：「对方没运行则派工失败，主持人权走失败回叫，**不自动启动**」（`docs/expert-teams.md:30`）——平台不应替用户拉起一个未运行的执行体，那是权限边界不是可用性优化。

**对 helloai 的落点**：helloai 的三级容错（子任务核验链、报告审查链、补偿巡检）解决「失败怎么重试」，但「**失败之后由谁向用户解释**」是另一件事。已有两处高价值缺口正属此类：`ResilientDispatcher.doAssignNextFallback` 只 `log.warn` 不落 timeline；「无候选」改不熔断后，永久无候选会让 PENDING 长期等待。**建议把「派工失败 ⇒ 必须产出一条面向用户的说明（timeline + 可读原因）」定为不变量**，而不是逐点补日志。

### 2.4 ★载荷旁路：截断不可行，必须「数据面 / 控制面分离」

**Octop 的问题定义（极其精准）** `[实测]` `docs/octop-ui-payload-offload.md:19-28`：一个 tool result 字符串同时服务 **4 类消费者**——

| 消费者 | 需求 |
|---|---|
| LLM 上下文 | **越小越好**（进 state，之后每轮全量携带） |
| 历史持久化 | 需要完整数据供回放 |
| Dashboard 实时渲染 | 需要完整 data |
| 插件 UI | 需要完整 data |

> 结论：*「模型与 UI 的需求对立。**截断不可行**（UI 丢剧集），必须在「tool 执行后、ToolMessage 进 state 前」把**数据面（大 payload）与控制面（摘要 + 渲染提示）分离**。」*

**实现（88 行，形状可直接照抄）** `[实测]` `agents/middleware/octop_ui_offload.py:56-85`：

- 触发条件四合一：`content` 是 str、长度 ≥ `_OFFLOAD_MIN_CHARS = 4000`、可解析为 JSON 且含非空 `octop_ui.renderer`、`data` 非空；
- **守护条件**：含 `file://` 则不剥离（保护媒体/路径提取消费方，`:30-32`）；
- 动作：**原地修改**（不新建对象）——`result.artifact = data`，`content` 留 `data_ref: "artifact"`，**保留 `id`/`name`/`tool_call_id`/`status`**；
- **异常必须吞掉**：`except Exception: logger.warning(...)`，注释「Offload must never break a tool call」；
- 挂载在**最内层**，对现有插件**零改动生效**。

**helloai 现状（实测）**：`helloai-core/.../shared/util/UpstreamAttachmentRenderer.java`（113 行）是**按行边界截断**——逐附件配额 + `[TRUNCATED] file=… shown=… total=… reason=dep_content_limit` 标注（`:74-88`）。预算分配写得很讲究（主附件保底 + 次要最低配额 + 标题/标注行最坏开销预留），**但数据是真丢了**：第二个及以后的附件全文永远进不了模型视野。

**对 helloai 的落点**（最高 ROI，对应裁决 A1）：

1. 引入 **`ToolResult` 双视图**：`modelView`（摘要 + `ref`）与 `uiPayload`（全量）。全量落 **MinIO/DB（按内容哈希去重）**，摘要带 `ref`；渲染端按 `ref` 按需拉取；
2. **不要只调大截断阈值**——那只是把问题推后；只要「同一份数据既喂模型又喂 UI」，就必然在某个长度上二选一；
3. 补齐四个细节（极易漏）：① 阈值化触发而非全量剥离；② 副作用消费方的**守护条件**（helloai 的媒体路径提取同理）；③ **异常绝不破坏工具调用**；④ 原地修改保留全部协议字段；
4. 同族第二条：`WorkspaceImageMaterializeMiddleware` `[实测]` `middleware/workspace_image.py:1-6` —— 图片在 checkpoint/history 里**只存路径引用**（`workspace://…`），**只在 model request 时**物化成 data URI。「base64 永不落 state / 永不进历史 payload」。helloai 的附件链路可套用同一形状。

### 2.5 引用溯源 + 人机双读（citations marker）

`[实测]` `infra/knowledge/citations.py:10-66`：检索结果在返回文本**尾部**附加一个 HTML 注释 marker：

```
正文…

<!--octop-kb-citations:[{"kb_id":...,"doc_id":...,"filename":...,"path":...}]-->
```

- **前端**解析该 marker 渲染成引用卡片；
- **喂模型前**用 `strip_citations_marker()` **剥掉**（不必让它占 token）；
- 引用按文档**去重**（`citations_from_ranked`，一篇文档一条引用，保持检索序）。

**对 helloai 的落点**：这是「同一段文本，机器读 marker、人读正文、模型读纯净正文」的最低成本实现。helloai 的报告/子任务结果/核验意见如果需要「可点开的引用来源」，套用此形状即可，无需引入新存储。

### 2.6 能力不可用 ⇒ 「从工具列表摘除」＞「在描述里写不要调用」

`[实测]` `infra/knowledge/hint.py:94-99` 的 docstring 一句话点题：

> *Hide the tool entirely when nothing is attached — a "do not call" note in the schema is weaker than omitting the tool from the model's tool list.*

两件事同时做：① **无 KB ⇒ 从 `request.tools` 里删掉 `search_knowledge`**（`:67-75`）；② **有 KB ⇒ 每轮重写该工具的 description**，注入**本轮实际可见的 KB 目录**（`:77-91`）——模型不可能知道运行时有哪几个知识库。

**对 helloai 的落点**：`ToolRegistry` 应增加两个语义位——**「按条件可用」**与**「按上下文动态描述」**。这同时是把 planner 工具收窄从硬编码变成配置的手段（见 2.12 的「调度者必须被剥夺动手能力」）。

### 2.7 上下文预算显式化

`[实测]`：
- `knowledge/retrieve.py:22-53`：`retrieve_context(..., k=8, char_budget=6000)` —— 注入有**字符预算**，且检索在 `run_in_executor` 里跑（阻塞 I/O 不占事件循环）；
- `agents/settings/runtime_limits.py:1-30`：`max_iters` / `max_input_length` / `temperature` / `top_p` / `max_tokens` 五个**可传参的运行时旋钮**；解析一律走 `_positive_int` / `_unit_float`，**非法值静默丢弃而不是报错**（`:33-77`）；
- `agents/context_breakdown.py`：上下文用量可按来源**分解**。

**对 helloai 的落点**：把「每轮注入预算」写成**显式契约**（配置项 + 单测断言），而不是散落的常量。helloai 的成本选人已用「近 5 次 token 均值」（`AgentSelector.resolveCostRanks`），这份预算契约是它的上游。

### 2.8 工具治理：denylist + 不可关闭清单 + 条件可用

`[实测]` `infra/agents/settings/tool_catalog.py:10-19`：

```python
CRITICAL_TOOLS = frozenset({"ls", "read_file", "glob", "grep", "write_todos", "task"})
# "Tools that must remain available; ignored if present in tools_disabled."
```

三条设计合起来很讲究：
1. **默认全开**（denylist 而非 allowlist）——新增工具自动可用，不会因忘了加白名单而「工具神秘消失」；
2. **不可关闭清单容错**——即使被写进 `tools_disabled` 也会被剔除（`normalize_tools_disabled` 里 `names - CRITICAL_TOOLS`）；
3. **可用性另行判定**（`builtin_tool_available`）：「禁用了」与「不具备」是**两个不同的事实**，分开表达。

**对 helloai 的落点**：`ToolRegistry` / `ToolDefinition` 可补「**不可关闭**」与「**条件可用**」两个语义位；planner 工具收窄（2.12）用这套机制表达只需一行配置。

### 2.9 状态面最小化：只存路由，不存结果

`docs/agent-interop-mailbox.md:49-68` 定义 `InboxMessage` 时有一条明确的决策注记：

> `target.call` 的结果与 `source` 合成回复都是 worker 内的**局部过程值**，不挂在 `InboxMessage` 上：不入库、历史靠 checkpoint，没有事后查结果的场景。

**为什么值得抄**：helloai 把「执行结果」落库（`ExecutionRecord`、`agent_event`）是**正确的**——因为有「事后查结果」的场景（报告、审查、回溯）。但**中间过程值**（本轮 turn 的临时拼接、review 的中间态）如果也落库，就会变成状态面债务。**判断标准就一句话：有没有「事后查」的查询方。有 → 落库；没有 → 只留在调用栈里。**

### 2.10 新能力默认关闭：通过注入启用，不污染默认工具集

`docs/agent-interop-mailbox.md:164-174`：`HarnessAgentManager(team_processor=None)` 表示完全不建 inbox、`stream`/`call` 行为与重构前**逐位一致**；传入 processor 才启用。`peer_agent` 工具被**从 `builtin/` 移出**到 `teams/`（`:202`）。

**为什么值得抄**：helloai 的 `SandboxProvider` / `SkillPackage` 属于「新增能力」，若一上线就并入默认链路，会让**旧路径的回归验证失去基准**。**默认行为必须是可证明不变的**，新能力只在显式注入时生效——能力开关应挂在 Run 配置上，而不是改执行链默认分支。

### 2.11 串行消费者：用「同 target 天然串行」代替分布式锁

`docs/agent-interop-mailbox.md:151`：**全局单 worker 串行**消费；后续 `docs/expert-teams.md`（"运行时"节）演化为「**inbox 按 callee 并发：同一成员串行，不同成员并行**」。

**为什么值得抄**：helloai 已用 `@SchedulerLock` + Redisson 解决分布式互斥——**这条不要改**。可借鉴的是**粒度判断**：Octop 把「同一 Agent 不能被并发写同一 thread」收敛到**收件箱的消费粒度**上，而不是散落到每个写入点加锁。helloai 的 `SubTaskReview` / `SubTaskClaim` 已有等价约束，值得反查一次：**是否所有「同 Agent 并发写」都被同一处挡住**，还是靠多把锁拼出来。

> 注：`InboxMessage` 实际并发行为（单 worker 串行 vs 按 callee 并发）两处文档不一致，代码在 harness 内不可读，**未核实**。

### 2.12 房间模型：复用 Thread，不引入新实体 + 主持人工具收窄

`docs/expert-teams.md`「身份模型」「房间与转播」两节：

> 团队不是平行实体，而是 `agents.kind = team` 的特殊专家；`team_id` = 主持人 `agent_id`。
> 成员编制只写在主持人工作区 `.octop/manifest.json`——**没有成员表**。
> `conversation_id` = 主持人 `thread_id`；成员 checkpoint = `conversation_id~成员id`。

这是「**用命名约定代替 schema**」的做法：团队编制是**低写入频次、强所属关系**的数据，放工作区文件合理。但**边界必须守住**：执行状态、认领、幂等这类**并发写 + 需要查询**的数据，绝不能放文件（§9 E）。

同时注意 Octop 对主持人做的**工具收窄**（`docs/expert-teams.md`："Octop 主持人装配"节）：

> `tools_disabled` 只保留 `agent_list` / `ask_agent` / 记忆 / `current_time`（含原本不可关的文件系统与 `task`）

**这是对 helloai 最有价值的一条**：调度者**必须**被剥夺动手能力，否则它会自己干活、不派工。helloai 的 planner 若发现「本该派出去的子任务被 planner 自己做了」，根因大概率就在这里。

---

## 3. 沙箱：Octop 的实现 vs helloai 的契约

### 3.1 helloai 现状（已核实）

| 组件 | 事实 |
|---|---|
| `ExecutionEnvironment` | 表达「在哪执行」——`local-process` / `remote-agent`（`agent/runtime/ExecutionEnvironment.java`） |
| `ExecutionPolicy` | 表达「隔离到什么程度」——文件/网络/进程/资源/凭证**五边界** × `ISOLATED`/`PARTIAL`/`NONE`（`agent/runtime/sandbox/ExecutionPolicy.java`） |
| `SandboxProvider` | `Sandbox resolve(SandboxContext)`，当前**唯一实现** `EnvironmentSandboxProvider` 只做环境路由（53 行） |
| **实现事实** | 代码注释自述：**当前无任何环境达到 `ISOLATED`，一律 `NONE`/`PARTIAL`**；`Sandbox_Provider.md` 亦声明 `Status: Planned` |

也就是说：**helloai 的沙箱抽象已经设计对了（环境与策略分离、五边界、诚实标注），缺的是「让五边界真的成立」的那个实现。**

### 3.2 Octop 的沙箱形态（实测）

Octop 把执行环境做成一个**声明式 spec**，由数据（DB 行 / Agent 配置）驱动，运行时再解析：

```
storage_backends 表行  ──(adapter.py:44 row_to_backend_spec)──►  harness backend spec (dict)
agent.config.backend   ──(resolver.py:79  resolve_agent_backend_spec)──►  展开 named / composite
                                    │
                                    ▼
              resolve_backend(spec, workspace_dir=...)  ← harness 侧（外部包）
```

支持的 `type`（`src/octop/infra/backend/adapter.py:11-24`）`[实测]`：
`cos` / `s3` / `oss` / `obs` / `custom` / `filesystem` / `local_shell` / `postgres` / **`docker`** / **`opensandbox`**，另有两个**组合子**：
- **`named`** —— 间接引用（Agent 配置只写名字，真实 spec 在库里），便于集中轮换；
- **`composite`** —— `default` + `routes{前缀→子spec}`（`resolver.py:103-117`）`[实测]`。

> `composite` 是**一个虚拟文件系统的挂载表**：默认落到沙箱，`skills/` 前缀可以落到本地目录，`artifacts/` 前缀可以落到对象存储。**Agent 只看见一棵树，平台在下面拼。**

Docker 沙箱的完整字段（`src/octop/infra/backend/docker_spec.py:8-27`）`[实测]` 与 helloai 五边界的**逐项映射**：

| helloai `ExecutionPolicy` 边界 | Octop docker spec 字段 | 语义 |
|---|---|---|
| `filesystem` | `workspace_path` · `volumes` | 容器内**同名绝对路径**、**不 bind-mount**；`volumes` 原样透传、**不自动挂 named volume** |
| `network` | **`allow_network`** | **默认 `false`**；需要 pip/curl 才显式打开 |
| `process` | `pids_limit` · `auto_remove` | PID 数上限；容器一次性/常驻由 `auto_remove` 决定 |
| `resource` | `memory` · `cpus` · `command_timeout` · `max_output_bytes` | 内存/CPU/单命令超时/输出字节上限 |
| `credential` | `env`（`execute_env.py:68-90`）· `environment_file` | **只注入 4 个变量**：`OCTOP_AGENT_ID` / `OCTOP_AUTH_DIR` / `OCTOP_HOME` / `OCTOP_SKILLS_DIR`；不继承宿主全量环境 |

另有一个**非 Docker 的轻量隔离**方案（`docs/agent-backend-file-io.md` §12）`[实测]`：

> Linux + `virtual_mode=True` + `root_dir` 非主机 `/` + 宿主有 `bwrap` ⇒ 走 `BubbledLocalShellBackend`，把 `execute`（含技能脚本）包进 **bubblewrap**。

**这就是「沙箱不该只有一个 Docker 实现」的具体答案**：Docker 给强隔离，bubblewrap 给**能力降级时的最小可用隔离**，无 `bwrap` 时明确退化为**无目录狱**（并诚实标注）。

### 3.3 三个可直接搬的设计细节

**(a) `sandbox_scope`：容器复用策略 = 权限与资源策略的最小载体**（`docs/agent-backend-file-io.md` §13）`[实测]`

| scope | 容器名 | 语义 |
|---|---|---|
| `agent`（默认） | `{prefix}_agent_{agentId}` | 一 Agent 一沙箱 |
| `user` | `{prefix}_{username}` | **同用户多专家共用**一个沙箱 |
| `fixed` | `sandbox_id` | 固定共享（需显式指定） |

生命周期口径极其克制：**没有则创建；`close()` / 删除专家都不删容器；只有显式 `destroy()` 才 stop+remove**。

> `Sandbox_Provider.md` 的实施原则 4 说「安全沙箱的引入必须有权限与资源策略，而不是只增加一个 Docker 类」——`sandbox_scope` 正是那个「策略」的最小载体。

**(b) 自检探针：真跑一次写→读→删**（`src/octop/infra/backend/probe.py:118-213`）`[实测]`

`_probe_docker()`：确保镜像 → 建一个 `auto_remove=True` 的**临时容器** → `backend.write()` 写入探针串 → `backend.read()` 读回 → 比对内容 → `execute("rm")` → `destroy()` + 清理临时目录。

**这比「容器能起来就是好的」强得多**，也比 `docker ps` 强得多。helloai 的 `AgentSelector` / 执行体健康检查可以照抄这个形状：**探针必须做一次真实业务往返，并保证回收**。

**(c) `previewable`：隔离与可观测性显式二选一**（`docker_spec.py:39-51`）`[实测]`

Docker 后端默认**不让** Admin 浏览文件（只有 `fixed` scope 默认可浏览，或显式 `previewable: true`）；但「探测」按钮始终可用。**理由**：`agent`/`user` scope 的容器是运行时私有态，浏览会破坏隔离语义。

### 3.4 落地路线（对齐 helloai 既有设计，不改架构）

> 只写「做什么、验收什么」，不写工期。

| 步 | 动作 | 验收 |
|---|---|---|
| S1 | 给 `ExecutionPolicy` 补 `DockerPolicy` / `BubblewrapPolicy` 两个静态工厂，**只声明事实**；`EnvironmentSandboxProvider` 增加 `docker` 分支（先只做**解析**，不做执行） | 单测：`SandboxContext(docker 配置)` → 返回带非 `NONE` 五边界的 `Sandbox` |
| S2 | 定义 **`SandboxSpec`（声明式）**：`type` + 五边界字段 + `scope`；来源可以是 Agent 配置或平台默认（对应 Octop 的 `named` 间接层） | 同一份 spec 能渲染出容器创建参数；**配置可被单测断言**，不需要真起 Docker |
| S3 | 实现 `DockerSandboxProvider`：起容器 + 注入**白名单 env（≤4 个变量）** + `allow_network=false` 默认 + 资源上限 | 集成测试（Testcontainers）：容器内 `ls/read/write/execute` 可用；**宿主环境变量不出现在容器内** |
| S4 | 照抄 probe：**写→读→删的真实往返** + 保证回收 | 失败路径也必须 `destroy()`（用带清理的 try/finally 断言） |
| S5 | `scope`（agent/user/fixed）与容器生命周期：**不自动销毁，显式回收** | 单测覆盖「同 Agent 复用同一容器」「删除 Agent 不删容器」 |

**强烈建议**：S2 的 spec **必须声明式、可序列化**（不要写成 Java 里的一堆 `if (isDocker())`）。这正是 Octop 与 helloai `Sandbox_Provider.md` 两边的共识——**Runtime 不直接绑定 Docker/K8s API**。

---

## 4. 技能化：从「编译期常量」到「可安装的包」

### 4.1 helloai 现状（已核实）

| 维度 | 事实 |
|---|---|
| 载体 | classpath markdown：`helloai-core/src/main/resources/skills/plugins/eng-*.md`（**4 个**）`[实测]` |
| 注册 | **编译期硬编码**：`AgentSkillSpecServiceImpl.KNOWN_SPECS = knownSpecs()`（`skill/AgentSkillSpecServiceImpl.java:31`）`[实测]` |
| 元数据 | `SkillPackage` record：name/version/description/requiredTools/dependencies/inputSchema/outputSchema/validationRules/fileName `[实测]` |
| 文件格式 | **无 YAML frontmatter**，纯 markdown `[实测]` |
| 解析 | `required_skills`（任务声明）→ 标签命中 → 渲染速览段 → 拼进执行 Prompt；**纯函数式** `[实测]` |
| 缺失能力 | **无** 安装 / 导出 / 分享 / 市场 / 来源标记 / 每 Agent 覆盖 / 技能自带脚本 / 第三方摄入安全 |

对照 `Skill_Capability.md` 的生命周期，helloai 只覆盖了 **Resolve**，`Discover` 和 `Load` 是空的。

### 4.2 Octop 的技能形态（实测）

**(a) SKILL.md 包格式**（`.cursor/skills/publish/SKILL.md`、`plugins/demo-greeting-skill/skills/polite-greeting/SKILL.md`）`[实测]`

```yaml
---
name: polite-greeting
description: >-            # ← 这是模型做「技能发现」时唯一看到的字段，必须写清「何时用」
  Use a concise, polite greeting ... Trigger this skill when the user says hello ...
metadata:
  octop:
    emoji: "👋"
---
# 标题 + 正文
```

`metadata` 命名空间**同时接受 5 个生态**（`skill/presentation.py:12`）`[实测]`：

```python
_EXTENSION_NAMESPACES = ("octop", "harness", "lightclaw", "orca", "openclaw")
```

**含义**：一份为别家写的 SKILL.md，只要用了这些命名空间，emoji/icon 照样能被读出来。**这是零成本的生态互通。**

**(b) 「目录即真相」**（`skill/workspace_catalog.py:131-170`）`[实测]`

技能目录扫描 `skills/` 与 `.octop/skills`，**没有技能表**。三个细节：
1. **`removed` 墓碑**：frontmatter 里 `removed: true` 的技能被跳过 —— 软删除靠**元数据**，不靠删目录；
2. **损坏不静默**：`SKILL.md` 非 UTF-8 时先尝试修复，修不了则以 `corrupt: true` + `error: invalid_utf8` **显式暴露**在列表里，不假装不存在；
3. **`enabled` 是「不在禁用集合」**：支持按 slug **或** `name` 禁用（`workspace_catalog.py:167`）。

**(c) 两级治理：全局技能包 ↔ Agent 工作区**

```
SkillPackageStore（全局共享包：DB 行 + 磁盘内容 + copy_policy）
        ▲  copy_package_skills_to_workspace / copy_workspace_skill_to_package
        ▼
{workspace}/skills/<slug>/（Agent 私有技能目录）
```

`copy_policy ∈ {snapshot, lock, deny}`（`skill/skill_package_store.py:32`）`[实测]`：
- `snapshot`：拷贝成独立快照；
- `lock`：拷贝时在 SKILL.md 盖上 `origin: <package_id>` + `locked: true`（`skill/skill_transfer.py:54-72`）—— **来源可追溯 + 禁止被下游改坏**；
- `deny`：不允许他人拷出。

冲突语义也很干净：目标已存在**活跃**技能时**拒绝覆盖**（`SkillTransferConflict`），而不是静默合并。

**(d) 第三方摄入的供应链安全（最该抄的一段）**

`skill/skill_packages.py:10-11` 与 `skill/skillhub_market.py:189-240` `[实测]`：

| 检查 | 阈值/规则 |
|---|---|
| 文件数 | ≤ 2000 |
| 解压后总大小 | ≤ 64 MB |
| 单条目压缩比 | > 100 且原始 > 1 MB ⇒ **拒绝**（zip bomb） |
| 路径 | 绝对路径 / `..` / 盘符 / 反斜杠 / NUL ⇒ 拒绝 |
| 重复条目 | 拒绝 |
| 条目类型 | symlink / 非常规文件 ⇒ 拒绝；**加密条目 ⇒ 拒绝** |
| 读取时 | 边读边按**声明大小**设上限，读完校验 `total == file_size`（防尺寸谎报） |
| 清单 | 必须含根 `SKILL.md`，必须 UTF-8 |
| 形态归一 | 若整包只有一层包裹目录且内含 `SKILL.md` ⇒ 自动提升一层 |
| HTTP | 响应体 ≤ 32 MB，分块读 64 KB |

**来源中立**：市场包、URL 包、CLI 安装目录、工作区上传，四种来源全部归一到同一个 `ResolvedSkillPackage(slug, files, source, source_url)`，再走同一个 `commit_skill_install(target, package)`（`skill/install.py:142-154`）`[实测]`。**装进 Agent 工作区还是装进全局包，只是换一个 target 实现。**

### 4.3 落地路线（对齐 `Skill_Capability.md` 的「先兼容、不建第二套运行时」）

| 步 | 动作 | 验收 |
|---|---|---|
| K1 | 给现有 4 个 `eng-*.md` **补 YAML frontmatter**（`name` / `description` / `version` / `required_tools`），保留正文不变 | 现有渲染行为不变（回归）；新增解析器能读出 frontmatter |
| K2 | `KNOWN_SPECS` 硬编码 → **目录扫描**（`skills/plugins/` + 可选外部目录），解析失败**显式报 corrupt** | 单测：坏文件出现在列表且带 `error`，不静默跳过 |
| K3 | 引入「**技能来源标记**」：`origin` + `locked`，拷贝进 Agent 工作区时打标 | 单测：带标技能被下游改写时能被识别 |
| K4 | **第三方摄入安全闸门**（照抄 4.2(d) 的表）：路径/类型/压缩比/大小/条目数/清单 | 针对每一类攻击各写一个**必失败**用例（zip bomb、`../`、symlink、无 SKILL.md） |
| K5 | 导出/安装闭环：`export`（打包为 zip）→ `install`（过 K4 闸门） | 端到端：导出再导入，技能可解析且 `requiredTools` 一致 |
| K6 | （可选）每 Agent 技能启用/禁用：启用 = 不在禁用集合（支持 slug 与 display name 两种匹配） | 单测覆盖两种匹配 |

> **不要做的**：不要为了技能化引入独立的技能运行时。Octop 的技能就是**目录 + markdown + 可选脚本**，脚本通过**已有的 `execute` 能力**在沙箱里跑。helloai 也应当如此——技能的「执行」是沙箱的事（第 3 章），不是新引擎的事。这正是 `Skill_Capability.md` 原则里那句「不因为 Capability Package 而建立第二套运行时」。
>
> **两条硬约束**：① 技能包的脚本要在沙箱里跑 ⇒ 技能化「执行」环节依赖沙箱（§3）；若沙箱未完成，只做「安装/解析/校验」，**不要开放技能脚本执行**。② 平台自持浏览器是执行体 ⇒ 必须先有沙箱（§5.3 方案 B）。

---

## 5. Web Agent 接入：三模式连接器 + 自持浏览器

### 5.1 helloai 现状（已核实）

```
AgentAccessType.WEB_BROWSER
   └─ BrowserAgentExecutor.execute()  ──►  BrowserAgentGateway.push(agent, task)  ──►  外部 Browser Agent 服务
                                          （HttpBrowserAgentGateway，同步等待 output 文本）
```
（`agent/executor/BrowserAgentExecutor.java`、`agent/browser/gateway/BrowserAgentGateway.java`）

这是 **「形态 A 桥接」**：平台把子任务推给外部自持 Playwright 的服务，同步等结果。**这条路线本身没错**（省去平台级浏览器运维），但它天然缺三件事：
1. **不可观测** —— 平台看不到浏览器里发生了什么；
2. **不可接管** —— 遇到登录/验证码/二次确认，没有任何入口让人工介入；
3. **态不可控** —— 会话/登录态存在外部服务里，隔离边界不在平台手上。

### 5.2 Octop 的做法（实测）

**(a) 连接器的三模式模型**（`src/octop/infra/connectors/catalog.py`）`[实测]`

26 个目录条目，`mcp_mode` 分布：`gateway` 14 · `remote` 11 · `internal` 1。

| 模式 | 含义 |
|---|---|
| `remote` | harness **直接**连厂商的 MCP URL（标准 MCP client） |
| `gateway` | **Octop 进程内写一个 Python 适配器，把没有 MCP 的服务包成 MCP**（`gateway/protocol.py` 只用了 ~80 行实现 `initialize` / `tools/list` / `tools/call` / `ping`） |
| `internal` | Octop 自托管的 HTTP MCP（`/api/internal/mcp`） |

适配器契约极小（`gateway/registry.py:22-30`）：

```python
class GatewayAdapter(Protocol):
    def list_tools(self) -> list[dict[str, Any]]: ...
    def call_tool(self, creds: dict, name: str, args: dict) -> str: ...
    def probe_credentials(self, creds: dict) -> None: ...   # 自检
```

**这才是「对接 web 端各类 AI / SaaS」的通用姿势**：不去要求对方提供 MCP，而是**平台侧写薄适配器，把异构能力统一成 MCP**。鉴权被声明化（`catalog.py:10-20`）`[实测]`：`AuthKind` 8 类，含 `session_cookie`（浏览器会话凭证）/ `oauth2` / `auth_code` / `imap_app_password`；`RemoteTransport` 3 类（`raw_http` / `streamable_http` / `sse`）；凭证表单本身也是**数据**（`ConnectorCredentialField`：key/label/field_type/required/placeholder/help/secret）。

> 对 helloai 的落点：helloai 的 MCP 是「**Server**」（Agent 经 `pullTasks/ack/claimSubTask/…` 来平台领活）。缺的是「**Client/适配器**」侧：把平台内部能力与第三方 SaaS 统一包成 MCP 工具供 planner 使用。契约形状直接照抄三方法（list/call/probe），不要设计大而全的 SDK；**`probe_credentials` 是契约的一部分**（连接器必须自带自检）——与沙箱 probe 是同一纪律：**「配好了」必须能被机器验证**。

**(b) 自持浏览器：直播 + 人机接管 + 录制回放**

| helloai 缺口 | Octop 对应 |
|---|---|
| 不可观测 | `api/routers/browser/stream.py`：CDP **screencast WebSocket**，逐帧 base64 jpeg 推到前端；协议在文件头完整写明（`{"type":"frame"...}` / `{"type":"tabs"...}`）`[实测]` |
| 不可接管 | `api/routers/browser/harness.py:24-41`：**控制权归属** `_CONTROL_OWNERS[session_id] ∈ {agent, user}`，`HandoffBody{target, reason}`；注释明确它是**协调提示**（agent 在用户接管期间暂停交互），且**独立于实时会话存储**，仪表盘刷新 / WS 重连后接管状态不丢 `[实测]` |
| 录制回放 | `api/routers/browser/record_replay.py`（324 行）`[实测]` |

**(c) 一条通用的健壮性教训**（`harness.py:43-60`）`[实测]`：`_is_session_alive()` 用一个**零副作用的 `Runtime.evaluate("1")`** 去探测缓存的 CDP 会话是否还活着——「之前注册过的会话，底层 CDP WebSocket 可能已死（崩溃/OOM/网络抖动），但对象永远留在 `_registry` 里；复用死会话会让后续每个动作都报低层错误」。

> **这条对 helloai 直接适用**：任何「缓存的长连接/执行体句柄」都必须有**廉价探活**。但**探活要区分两个不同的问题**：①「对象在、连接死」⇒ 探活可解；②「对象在**别的 JVM**」⇒ 探活**无解**，必须外置状态 —— **helloai 的 `SESSION_AUTH`（2.2 节）属于②，用探活解决不了**。

**(d) MCP 会话与工具缓存的三个细节**（`infra/connectors/mcp_tool_cache.py`）`[实测]`：
1. **`fingerprint_mcp_spec(spec)`（`:15-33`）**：只对**影响连接行为的字段**（`transport`/`url`/`headers`/`command`/`args`/`env`）做**规范化**后取 `sha256[:16]`，**显式排除 `enabled` 等元数据** → 稳定的缓存/复用键。这就是「复用同一匹配器防漂移」的具体形状；
2. **`wrap_tools_for_shared_use(tools, lock)`（`:36+`）**：用**同一把锁串行化 invoke** —— 因为 **MCP 会话不是并发安全的**；
3. 会话探活同 (c)。

> 对 helloai 的落点：指纹函数 → MCP 工具列表缓存 / 连接复用 / **配置变更检测**；串行化 → helloai 的 MCP session 同样非并发安全，**同一 session 的工具调用必须串行**（这是「锁的粒度」问题，不要用分布式锁解决单连接互斥）。

### 5.3 你必须先做的架构决策

**Octop 的浏览器是「平台自持」，helloai 的是「外部桥接」——这两条路线不能只抄一半。** 三种可选落点：

| 方案 | 做什么 | 代价 |
|---|---|---|
| **A. 只补观测与控制面**（推荐先做） | 保留外部 Browser Agent，但要求它**回传过程事件**（截图帧 + 当前 URL + 步骤），平台侧复用；并加一个**「请求人工接管」信号 + 人工指令回传**通道 | 需要外部服务配合改造；但不动平台执行链，风险最低 |
| **B. 平台自持浏览器** | 在 helloai 侧引入 CDP/Playwright 执行体，`browser_use` 类工具，profile 按 **用户维度隔离** | 平台要承担浏览器运维、镜像、并发与资源治理；但观测/接管/隔离三件事一次性解决 |
| **C. 桥接 + 自持双形态** | 两者并存，按 Agent 的 `accessType` 路由 | 维护两套；只在 B 稳定后再谈 |

**建议**：先按 A 补「可观测 + 可接管」——这两件事用户能直接感知，且不改变已建好的调度/选人/心跳豁免链。B 作为独立排期，且**必须先把第 3 章沙箱做完**：自持浏览器本身就是「需要被沙箱化的执行体」。

---

## 6. 数据与状态

### 6.1 迁移纪律（Flyway 对照，注意不可照搬的部分）

`[实测]` `infra/db/migrate.py` + `AGENTS.md §7`：

| Octop 规则 | 证据 |
|---|---|
| 编号**成对** `00N_desc.sql`（SQLite）/ `00N_desc.pg.sql`（PG），按方言分别发现 | `migrate.py:36-45` |
| **重复版本号直接 `raise RuntimeError`**，并给出修复指引 | `migrate.py:53-60` |
| `_schema_version` 是**已应用水位**，不是变更日志 | `AGENTS.md:230` |
| **未发布的 schema 改动折叠进当前未发布版本，不新开号**；只有已发布后才切新号 | `AGENTS.md:228-231` |
| 重建类 DDL（SQLite 无法 `ALTER` 的）必须**幂等** | `AGENTS.md:223-234` |
| 版本号有**测试断言**（`test_db_pool.py` 断言 `v == 20`） | `AGENTS.md:226` |

**对 helloai 的落点**：helloai 用 Flyway。可借的是**纪律**而非机制：
1. **「未发布折叠」**——Flyway 的 checksum 挡不住「为一个小改新开 V103」的版本号膨胀。把「同一开发周期内的相关改动合并进一个未合入的 V 号」写成规则；
2. **版本号断言用例**（防手工改库后水位错乱）；
3. **幂等性作为 review checklist**；
4. ⚠️ **不可照搬**：helloai 铁律「已应用的 `V*.sql` 不得改注释（checksum 失败）」——Octop 的「折叠」只对**未发布**版本合法，Flyway 下等价物是「折叠进尚未合入 main 的 V 号」，不能对已上线版本操作。

### 6.2 双 ID 方案（整数代理主键 + 公有字符串 ULID）

`[实测]` `AGENTS.md:236-251`：所有**对外可见的资源表**采用：

| 列 | 角色 |
|---|---|
| `id` | 整数代理主键（AUTOINCREMENT / IDENTITY） |
| `{entity}_id` | **公有字符串 UNIQUE（ULID / 短 id）**——API、路径、其他表都用它 |

- 子行存**字符串** id，`REFERENCES parent({entity}_id)`；**绝不 FK 整数 `id`**；
- 收益：对外不暴露自增（防枚举/防爬/防业务与主键耦合）；整数主键保持 B+Tree 插入局部性；跨系统引用稳定；
- **并明确列出「不要强制套用」的例外**：`users`（整数 FK，登录身份是 username）/ name-keyed 配置 / 追加型日志 / KV / 1:1 扩展 / 临时行。

**对 helloai 的落点**：这里**最值得抄的其实是「规则 + 例外清单」这个文档形态**——它让一条 DDL 规范可以被机械校验（哪些表例外是穷举的，而不是「酌情」）。helloai 若对外 API/URL 暴露自增主键，可评估是否引入公有 id；不必全表改造。

> 注：`AgentTeams最新版借鉴分析_Java落点.md` §4.16 确认 AgentTeams 与 Octop 在「编号成对迁移 + 水位」「双 ID」上**各自独立收敛**，可作为该判据的第二个证据源。

### 6.3 组合根与仓储纪律

`[实测]` `infra/db/services.py`（217 行）：
- `RepoBundle`（`@dataclass(frozen=True)`，`:37-68`）——一表一 repo，只写 SQL；
- `SharedServices`（`:99-208`）——**懒属性**逐项暴露；
- `repos/_base.py`：`partial_updates()`（None = 跳过）/ `optional_updates()`（sentinel = 字段被省略，区别「未传」与「传 null」）/ `sql_in_placeholders()`（`?`，注释明写「加 PG 时换 `%s`」）。

**AGENTS.md §5 的表格形态**（真正可复制的部分）——每层一张 `May import` / `Must NOT import` 表，外加 6 条 **Hard bans**（`AGENTS.md:97-104`）：

```
- infra/ → api/, cli/, launch.py
- api/ → cli/, launch.py
- cli/ → api/
- infra/db/repos/ → any non-DB infra package
- infra/utils/ → any non-utils infra package
- Routers must stay thin: validate HTTP, call infra/, map errors — not new domain rules
```

**对 helloai 的落点**：helloai 有「依赖链 planner > review > task > agent > system > shared」「跨域经 Service/端口，禁直捅 Mapper」的约定。可借的是把约定写成**可生成式校验的表格 + Hard bans 清单**（项目已有「架构守卫生成式」基础设施）。另 `optional_updates` 的 **sentinel 区分「未传」/「传 null」** 值得单独抄——这是 PATCH 语义正确性的关键。

### 6.4 备份 / 恢复（helloai 完全空白）

`[实测]` `infra/backup/`（9 文件 2,396 行）+ `api/routers/backup.py`：

| 设计点 | 证据 | 价值 |
|---|---|---|
| **在线快照**：SQLite 走 online backup API；PG 走 `pg_dump -Fc` / `pg_restore` | `backup/snapshot.py:34-46`；`backup/pg_dump.py:22-50` | 不锁库、不要求停机 |
| **单飞锁 + 可轮询状态** | `backup/auto.py:49-73`：`raise_if_backup_busy()` | 防止并发备份、前端可轮询进度 |
| **manifest 放在归档开头**，`peek_backup_contents()` 只读头部即可判断「是否含聊天」 | `backup/store.py:135-146`；`backup/manifest.py` | **不解压就能决策** |
| **自动 / 手动文件名前缀区分** + `prune_auto_backups(keep)` 只淘汰自动备份 | `backup/store.py:222-230`；`auto.py:81-89` | 用户手动备份永不被自动清理误删 |
| **停机恢复流程文档化**，明确「运行中备份不保证多文件同一瞬间」 | `docs/versioned-history.md:96-107` | **诚实边界**，不许诺做不到的原子性 |

**对 helloai 的落点**：helloai 是 PG + MinIO + RabbitMQ，`grep -rli "backup"` 在 `helloai-core`/`helloai-api` **零命中**（2026-10-09 复核确认），即**无备份/恢复能力**。可直接对应：`pg_dump -Fc` + MinIO 对象清单 + manifest 前置 + Redisson 单飞锁 + 保留策略。**纯增量、可独立交付。**

另有一条**恢复语义陷阱**值得直接记入迁移文档（`backup/snapshot.py`）：恢复用户数据时**用 UPDATE-or-INSERT 而不是 DELETE+INSERT**（后者触发 `ON DELETE CASCADE` 清空子表），并支持所有权重映射 + JWT secret 保留——helloai 的 Flyway + 数据迁移同样有级联风险。

### 6.5 版本化历史：三条可搬的「渐进替换数据」范式

`[实测]` `docs/versioned-history.md` 通篇是「如何在不停机、不迁移旧数据的前提下替换存储格式」——**这个范式本身比它的具体实现更值得抄**：

1. **开关默认关闭 + 新旧共读 + 只对新区段生效**：旧前缀只在新归档里登记边界，不复制正文（`:16-19`）；
2. **以完整回合为边界切换**，「等待用户批准的回合继续用开始时的格式，恢复时不重选」（`:16`）——**格式切换必须绑定在一个原子单元上**；
3. **正文按内容哈希共享**：新文件拆 `documents`（消息/轨迹结构）与 `bodies`（按内容哈希共享的正文、thinking、工具参数/结果），同一大字段可被两种视图引用（`:22-24`）。

另外两条**诚实语义**值得抄：
- 回合状态机 `active/paused/complete/partial/failed/interrupted`，且明确 **「complete 仅表示本采集器通过当前回合的检查，不能证明旧会话完整」**（`:74-76`）——**状态名不得夸大其保证范围**；
- 读取失败**必须报错，不能按空历史处理**（`:71-72`）——失败与「空」是两件事。

**对 helloai 的落点**：`task_timeline` 与 AgentEvent 已是事实投影。可补：① Timeline/Replay 分页改为**游标式**（`next_cursor`，防 offset 翻页错位）；② 大正文按内容哈希去重存储。

---

## 7. 调度与主动能力

### 7.1 让 Agent 自己管理定时任务（cron）

`[实测]` `infra/cron/`：
- **6 个工具** `cronjob_list / get / create / update / delete / run_now`（`cron/tools.py:99-226`），每个入口都先 `_require_agent_owner` 做所有权校验（`:73-89`）；
- 任务模型带 `task_type ∈ {text, agent}`（直推 vs 跑完 AI 再推）、`fresh_thread`、`session_key`、`model`、`mcp_servers`（`cron/job.py:21-51`）；`task_type.py` 里 `normalize_*`（未知值收敛到默认）与 `require_*`（用户输入校验报错）**分成两个函数**；
- 触发器抽象支持三种时间触发 + **外部事件源**（`AgentlyMailTrigger`，「An external event source, never scheduled on the wall clock」，`cron/trigger.py:19-24`）；
- `misfire_grace_time=60`（业务任务）/ `300`（系统任务）（`cron/manager.py:280, 418`）。

**对 helloai 的落点**：helloai 的 `@Scheduled` + `@SchedulerLock` 是**多实例正确解，不要退回进程内调度**。可借鉴三点：① 把「用户/Agent 可创建的定时任务」与「平台系统任务」**分成两套**（权限与生命周期不同）；② `misfire_grace_time` 对应的**错过补偿**语义；③ **触发源抽象**（时间 vs 外部事件），是将来接「外部事件驱动」的自然扩展点。

### 7.2 HITL 会话级批准（Codex 式 allow-all / allow-tool）

`[实测]` `agents/security/hitl_session.py:48-96`：`HitlSessionPolicy` 是**每线程粘性批准**（`allows(tool_name)` / `with_tools` / `without_tools` / `to_json`），且解析非法输入时**退化为「每次都问」**（`:95-96`：「invalid input becomes ask-every-time」）。

**对 helloai 的落点**：helloai 的核验/审批是**逐次**的。加「会话级/任务级豁免」能消掉大量无意义重复审批；关键在于**非法输入退化到更严的一侧**（不是更松）。

### 7.3 主动推送的表形态

`[实测]`：`proactive_care_config`（**1:1 扩展表 keyed by `agent_id`**）+ `care_push_records`（**追加型推送记录表**）+ `AuditRepo` 的 `ACTOR_SYSTEM` 主体。

**对 helloai 的落点**：配置用 1:1 扩展表、事件用追加日志表——**不往主表加列、不往事件表加可变状态**。这与 helloai 既有的「独立行实体 + 行级原子 upsert」判据同源。

### 7.4 ACP 会话状态机 + 权限询问转发

`[实测]` `docs/acp.md` + `agents/settings/acp.py`：
- 每个用户一套 runner 定义，存 `settings` 表键 `acp_runners:user:{id}`；Agent 只存 `config_json.acp.tool_enabled` 一个开关（`acp.py:41-52`）——**共享配置按用户、开关按 Agent**；
- `acp_runner` 工具是**会话状态机**：`list / start / message / respond / status / close`，其中 `respond` 用于回答 `[permission_required]`；`close` 结束会话；
- 内置 runner 不可删、自定义可加；隐藏项 `_HIDDEN_RUNNERS`（`acp.py:17`）。

**对 helloai 的落点**：helloai 已有铁律「外部 agent 必须心跳在线才可被分派、禁止模拟心跳、禁止另注册」。**最有价值的一条是「外部 Agent 的权限询问转发到对话里让人选」**——即外部执行体的越权请求应在平台上变成**一次用户可选决策**，而不是默默失败。这与 helloai 已有的人工指派兜底（`nc-fallback-*`）是同一族能力，可合并设计。

### 7.5 Bridge（跨实例）：三条可直接抄的接口纪律

`[实测]` `docs/bridge.md` + `infra/bridge/`：
- **探测与落库分离**：`POST /api/bridge/probe` 用填写的地址+凭据登录对端并拉专家摘要，**不写连接表、不建 WS**；只有「保存并连接」在登录 + WS `hello_ack` **都成功后才落库，失败回滚**；
- **执行侧拥有权威历史**：对话只在专家所在实例执行，发起侧仅临时会话、**不写本机 threads**；附件落到执行侧 `inbound/`；
- **影子 id 命名约定**：`bridge:{connection_id}:{remote_agent_id}`；UI 只打本机 API，本机识别远程后转发；
- **入站隧道白名单按能力显式枚举**：允许 agent 范围内的聊天/工作区/定时任务/状态 + 部分只读端点；**管理/认证/Bridge 控制面明确不入隧道**。

**对 helloai 的落点**：helloai 的「外部 AI agent 接入」是**同级问题**。三条直接可用：① 探测（dry-run）与落库分离 + 失败回滚；② 执行侧拥有权威状态；③ 隧道白名单按能力显式枚举，控制面永不入隧道。

---

## 8. 工程基建

### 8.1 后端自有词条 + 前后端 key parity 守卫

`[实测]` `AGENTS.md:255-295` + `src/octop/i18n/`：
- **服务端产生的用户可见文本必须来自后端 bundle**，禁止硬编码英文；
- 结构：`i18n/{en,zh}.json`（canonical，**key 树必须一致**）+ `i18n/domains/*.py`（类型化 helper）；
- **测试强制前后端 key 必须匹配**（`tests/unit/i18n`）；插值用 `str.format`，不用 gettext/.po。

**对 helloai 的落点**：helloai 后端**无 `messages*.properties`**（实测零命中），而前端有 `eventMeta.ts` / `sequenceFlow.ts` / `SubTaskDetail.vue` **三处登记**（缺任一 ⇒ 界面显示裸 eventType）。可借两点：① 把「事件码/错误码 → 文案」收敛到**服务端单一来源**；② **加 key parity 守卫用例**——治「三处登记漂移」靠门禁而非靠人记。

### 8.2 错误码 → HTTP 映射，语义只加在领域层

`[实测]` `AGENTS.md:279` + `infra/errors.py` / `api/errors.py`：`api/errors.py` 职责被限定为「Map `OctopError` → HTTP status + JSON」，**禁止新增错误语义**（新语义必须加到 `infra/errors.py`）。

**对 helloai 的落点**：约定「**错误语义只加在 domain 层，HTTP 层只映射**」——防止业务语义散落 Controller。可加一条生成式校验。

### 8.3 首次运行锁定（setup lockdown）

`[实测]` `api/middleware/setup_lockdown.py:1-21`：**当系统里一个用户都没有时，锁死除 `/setup` 之外的全部端点**。

**对 helloai 的落点**：极廉价的安全默认，防止「初始化到一半的实例对外裸奔」。

### 8.4 目录守卫：结构化拒绝码

`[实测]` `infra/utils/host_dirs.py`：
- 禁止前缀 `/proc` `/sys` `/dev` `/etc` `/root`（POSIX）；
- **结构化探测码**（比布尔好）：`not_directory` / `permission_denied` / `write_failed` / `not_allowed` / `outside_home` / `outside_root`；
- `host_path_text()` 一律 `realpath` + `as_posix`（**跨平台序列化统一**）；
- Windows 上**枚举全部就绪盘符**而不是推导单一根。

**对 helloai 的落点**：若将来开放「用户指定工作目录 / 挂载目录」，需要同形守卫。**结构化拒绝码**尤其值得单独抄——它让「为什么不让我用这个目录」可以直接给用户可读原因。

### 8.5 测试替身体系与跨平台测试纪律

`[实测]` `tests/support/`（11 个模块）：`fakes.py`（`FakeHarnessAgent` / `fake_bin_path()`）、`harness.py`（`build_harness_manager_mock()`）、`http.py`（`ASGIWebSocketSession` / `ws_token()`）、`app.py`、`auth.py`、`ldap_fake.py`、`postgresql.py`、`scenarios.py`、`secrets.py`、`testmon_staged_changes.py`、`bwrap_marks.py`。

`AGENTS.md §7` 有整节 **Cross-platform tests**：不硬编码 POSIX 路径、用 `Path` 相等而非字符串前缀、`posix_only` marker 统一别名、新 connectors/CLI/gateway 测试必须 `monkeypatch.setenv("OCTOP_HOME", tmp_path)`。

**对 helloai 的落点**：可借「**共享夹具目录**」形态——新用例默认拿替身，而不是各自起容器；`testmon` 的 **staged-aware 测试选择**可评估引入。

### 8.6 门禁前置：格式化必须能回写暂存区

`[实测]` `AGENTS.md:201` + `.githooks/pre-commit`：每次 `git commit` 跑 `make all` + 前端 `npm run build`；**格式化后的文件会被重新 `git add` 回暂存区**；明确「不要绕钩子把红灯测试塞进仓库」。

**对 helloai 的落点**：helloai 已有 `ci-gate.sh` 门禁。值得确认：**格式修复要能回写暂存区**，否则开发者每次都要手动再 `git add`（「门禁可用性」问题，会诱导绕钩子）。

### 8.7 文档形态：ADR + 「已确认决策」枚举表

`[实测]`：`docs/adr/001-single-process-model.md`（**`Status: Accepted`** / Context / Decision / Rationale / Trade-offs / Consequences）；`docs/expert-teams.md` 与 `docs/agent-interop-mailbox.md §11` 用**编号决策表**把「定下来的事 + 当时理由」冻结成可引用的清单。

**对 helloai 的落点**：给「架构变更记录」补 **`Status` 字段**，并把散落的「暂不修 / 待评估 / 已登记未修复」收敛成一份**悬而未决登记册**。

---

## 9. 明确不要抄（C 清单）

| # | 项 | 为什么 |
|---|---|---|
| C1 | **ADR-001 单进程、无外部队列**（`docs/adr/001-single-process-model.md:12-15`） | 与 helloai 已建的 **MQ + Outbox + 补偿巡检 + 幂等消费**完全相反。路线分歧不是优劣；反向改会丢掉刚建成的三级容错 |
| C2 | **进程内 APScheduler**（`cron/manager.py`） | helloai 的 `@Scheduled` + `@SchedulerLock`（Redis）是多实例正确解；退回进程内调度会在多实例下重复执行 |
| C3 | **SQLite / PostgreSQL 双后端** | 为双后端付出的代价（双份迁移、`?`→`%s` 边界改写、双备份格式）helloai 不需要。**PG-only 是优势不是负担** |
| C4 | **CDP screencast 直播的具体编码** | 若要「可观测 + 可接管」，先做「步骤事件 + 截图」，不必抄逐帧 base64 jpeg 的帧协议 |
| C5 | **「用命名约定代替 schema」无边界使用** | 团队编制放工作区文件是好的（低写入频次 + 强所属关系）；但**执行状态、认领、幂等**这类并发写 + 需要查询的数据，绝不能放文件 |
| C6 | **Python IM 网关原样移植**（`infra/gateway/` 55 文件 11.7k 行） | 语言不通，且 helloai 是**任务编排平台不是聊天助手**。若将来接 IM，应按「通道归一化管线」在 Java 侧重写 |
| C7 | **自托管个人产品的交付面**：桌面端 / 移动端(adb) / 语音 / NAS 打包 / 自更新 / Let's Encrypt（`infra/{desktop,mobile,voice}`、`infra/setup/tls`） | 与 helloai 的定位无关 |

---

## 10. 综合采纳清单（裁决版）

> **本节不承载排期**（《治理规则》§3.6：`research/` 不写排期与进度）。以下按**性质**分档（安全与数据 / 既有规划内增量 / 能力补全 / 登记项 / 明确不做），**不表示执行先后**；**排序唯一载体 = `doc/plan/HelloAI 借鉴落地实施计划.md`（`REF-x.y`）+ 《HelloAI 实现差距表》**。
> `A*/B*/C*/D*` 为**裁决编号**（被 `helloai借鉴清单_四项之外_完整版.md` 与 `helloai四能力完善优先级与借鉴路线.md` 交叉引用），保留不变。

### 10.1 安全与数据类（独立交付，互不依赖）

| # | 项 | Octop 出处 | helloai 落点 | 采纳理由 |
|---|---|---|---|---|
| A1 | **载荷旁路**（数据面/控制面分离 + `ref` + 双视图） | `middleware/octop_ui_offload.py:56-85`；`docs/octop-ui-payload-offload.md` | `UpstreamAttachmentRenderer` + 工具结果链路 | **唯一正在真实丢数据的地方**；形状可直接抄；顺带解决上下文成本 |
| A2 | **SSRF 出站 URL 校验**（含 DNS-rebinding pinning） | `infra/utils/ssrf_guard.py` | `WebPageFetchServiceImpl` + 未来 Connector/MCP 外联 | web_search 多厂商，无统一出站校验；网络安全底座 |
| A3 | **首次运行锁定**（无用户 ⇒ 只放行 setup） | `api/middleware/setup_lockdown.py` | 启动守卫 | 廉价安全默认 |
| A4 | **结构化目录守卫 + 拒绝码** | `utils/host_dirs.py` | 存储/工作目录开放前 | 廉价，且让拒绝原因可读 |

### 10.2 填充既有规划（不是改架构）

| # | 项 | 落点 | 说明 |
|---|---|---|---|
| B1 | **沙箱 spec 声明化 + 探针 + 实现** | `Sandbox_Provider.md` 的 S2~S5（§3.4） | 把 `Status: Planned` 变成有实现的第一刀；五边界与既有 `ExecutionPolicy` 天然一一对应 |
| B2 | **备份 / 恢复** | pg_dump + MinIO 清单 + manifest 前置 peek + 单飞锁 + 仅淘汰自动备份 + 停机恢复流程文档（§6.4） | helloai 完全空白；纯增量、可独立交付 |

### 10.3 能力补全（对齐差距表）

| # | 项 | 落点 | 说明 |
|---|---|---|---|
| C1 | **RAG 知识库 + 引用溯源 marker** | pgvector + `retrieve_context(char_budget)` + citations marker（§2.5/§2.7） | 先定「什么不许进上下文」，再谈检索；OCR / 本地 ONNX embedding 可后置 |
| C2 | **技能 frontmatter + 目录扫描 + 摄入安全闸门** | `AgentSkillSpecServiceImpl`（§4.3 K1~K4） | Discover/Load 从 0 到 1；开放技能导入前必须过闸门 |
| C3 | **失败回叫闭环语义** | `ResilientDispatcher`（§2.3） | 改成不变量（「派工失败必须产出面向用户的说明」），不是逐点补日志 |
| C4 | **能力摘除式治理 + CRITICAL_TOOLS** | `ToolRegistry` 加「按条件可用」「不可关闭」语义位（§2.6/§2.8） | 同时是 planner 工具收窄的实现手段；成本极低 |
| C5 | **MCP 会话指纹 + 同会话串行化 + 缓存句柄廉价探活** | MCP 会话/工具缓存（§5.2d） | 「复用同一匹配器防漂移」的具体形状；并发正确性 |
| C6 | **技能来源标记（origin/locked）+ 冲突拒绝覆盖** | `SkillTransfer`（§4.2c） | 技能可搬迁而不失溯源 |
| C7 | **key parity 守卫** | 事件码/错误码服务端单一来源 + 前后端 key 匹配测试（§8.1） | 治 eventMeta 三处漂移，靠门禁不靠人记 |
| C8 | **连接器三模式 + 网关适配器** | 目录条目声明化 + `list_tools/call_tool/probe_credentials` 三方法（§5.2a） | 只保留真实要接的几家，不抄 26 个 Python 适配器 |

### 10.4 登记为判据 / 观察项（承认观察、修正定性）

| # | 项 | 处置 |
|---|---|---|
| D1 | **MCP 身份输入源（`_sessionId`）** | 登记为「协议卫生 + 多实例部署前置条件」；**不删协议参数**，保留接收、引导新客户端走无状态 REST 别名（§2.2，裁决 #4/#5/#6） |
| D2 | **`SESSION_AUTH` 外置/无状态** | 登记为「**多实例部署前置条件**」，当前单实例无实际风险；若多实例化，先于部署完成（§2.2，裁决 #5） |
| D3 | **工具调用拦截层（`ToolCallInterceptor` 链）** | 按重构排期，作为消除「13 处重复覆写」的维护项，不作为安全修复（§2.1，裁决 #7） |
| D4 | **HITL 会话级批准** | 非法输入退化到更严一侧（§7.2） |
| D5 | **cron 触发源抽象 + misfire 宽限 + 用户/系统任务分离** | 产品能力补全，按需（§7.1） |
| D6 | **ACP 权限询问转发** | 与人工指派兜底合并设计（§7.4） |
| D7 | **双 ID + 悬而未决登记册 + ADR Status 字段** | 文档形态优先（§6.2/§8.7） |

### 10.5 明确不做

见 §9 C1~C7（单进程/双后端/进程内调度/Python 网关/CDP 逐帧直播/命名约定无边界/桌面移动语音交付面）。

### 10.6 借鉴项索引（**本节不承载排序**）

> **排序唯一载体 = `doc/plan/HelloAI 借鉴落地实施计划.md`（编号 `REF-x.y`）+ 《HelloAI 实现差距表》**；本节只做「哪条借鉴落到哪里」的追溯索引。各条的定义与 Octop 出处见 §10.1~§10.5 与正文对应小节。

| 本报告编号 | 实施落点（`REF-x.y`） | 状态 |
|---|---|---|
| `A1` 载荷旁路 | `REF-5.2a`（常量单源化）/ `REF-5.2b`（双视图，独立评估项） | ✅ 已立项 |
| `A2` SSRF 出站校验 | `REF-5.4` | ✅ 已立项 |
| `A3` 首次运行锁定 | `REF-5.3` | ✅ 已立项 |
| `A4` 目录守卫 + 拒绝码 | `REF-5.5`（**未来前置**：开放用户指定工作目录之前置） | — |
| `B1` 沙箱 spec + 探针 + 实现 | `REF-3.1`~`REF-3.5`（**预案**） | ⏸ **条件触发**（`D-2026-10-09-6③`） |
| `B2` 备份 / 恢复 | `REF-2`（`G-018`） | ✅ 已立项 |
| `C1` RAG + 引用溯源 | `REF-4`（`G-019`，可后置） | ✅ 已立项 |
| `C2` 技能 frontmatter + 扫描 + 闸门 | `REF-1.1` / `REF-1.2` / `REF-1.5` / `REF-1.6` | ✅ 已立项 |
| `C3` 失败回叫闭环 | `REF-5.1`（`G-015`） | ✅ 已立项 |
| `C4` 能力摘除式治理 + `CRITICAL_TOOLS` | `REF-1.3` / `REF-1.3b` | ✅ 已立项 |
| `C5` MCP 会话指纹 / 串行化 / 探活 | `REF-6.5` / `REF-6.6`（判据） | ✅ 已登记 |
| `C6` 技能来源标记 `origin/locked` | `REF-1.4` | ✅ 已立项 |
| `C7` key parity 守卫 | `REF-6.8`（判据） | ✅ 已登记 |
| `C8` 连接器三模式 + 网关适配器 | 未立项（登记为观察项） | — |
| `D1`~`D7` | 登记项（`D2` 见 `REF-6.5`；`D3` 工具拦截层依赖 RM9 另行裁定） | — |

> **2026-10-09 历史说明**：本节原有「第 1~4 步」排序块**已移出**——它属**项目分期/排期口径**，按当日的冻结令不得留在活文档；**无信息损失**（各条定义在 §10.1~§10.5 完整保留）。其中两条判断在此保留：① **`B1` 必须先于「平台自持浏览器」**——浏览器本身就是待沙箱化的执行体（故若触发条件③成立，沙箱须先做）；② ~~技能的脚本要在沙箱里跑~~ 见下订正，该前提在当前形态下不成立。

**两条硬约束**（与项目红线对齐）：
1. **技能的脚本要在沙箱里跑** ⇒ 技能化推进到「可执行」依赖沙箱。若沙箱未完成，只做「安装/解析/校验」，不开放技能脚本执行；
   > **2026-10-09 订正（对照 helloai 代码）**：本约束的前提**在当前平台形态下不成立**——helloai **不执行任何脚本**（`ProcessBuilder` / `Runtime.getRuntime` / `ScriptEngine` / 表达式求值器在 `src/main` 全库 0 命中；`onboarding/executor/scripts/*` 是打包给外部 agent 的交付物；技能是**文本规范**而非可执行包）。故「只做安装/解析/校验」**无需额外机制即可成立**；**反过来看，若将来要开放脚本执行，那本身就是沙箱的触发条件之一**（《目标架构》§7）。
2. **D2 必须先于多实例部署**：`SESSION_AUTH` 是进程级注册表，一旦上多实例就是 401 随机出现，且**故障现象像鉴权 bug 而不是架构 bug**，排查成本极高。
   > **2026-10-09 复核：本条仍然成立**，已登记为 `REF-6.5`。

---

## 11. 事实核查与未核实项

**本次复核（2026-10-09，helloai 侧关键论断，逐文件重读）**：

- MCP 身份链：`helloai-core/.../mcp/{McpMcpServer,McpAuthContext,McpAuthFilter,SessionAuthCleaner}.java` 全文 —— 确认 13/13 工具强制覆盖、`_sessionId` 输入源、`SESSION_AUTH` 进程级、注释漂移；
- 无状态别名：`helloai-api/.../controller/McpController.java` 全文 —— 确认 `POST /api/mcp/jsonrpc` + `checkIn/checkOut`「无需 MCP session」；
- 部署拓扑：`docker-compose.server.yml` / `docker-compose.yml` 全文 —— 确认**单实例**部署（无 replicas）；
- 载荷渲染：`helloai-core/.../shared/util/UpstreamAttachmentRenderer.java` 全文 —— 确认按预算截断、逐附件配额、`[TRUNCATED]` 标注；
- 备份空白：`grep -rli "backup|restore"` 在 `helloai-core`/`helloai-api` **零命中**（唯一命中为报告回滚功能，非 DB 备份）；
- Octop 侧关键模块（本会话直接读取）：`cron/{manager,task_type}.py`、`knowledge/{retrieve,index}.py`、`utils/ssrf_guard.py`、`backup/snapshot.py`、`history/projection.py`、`connectors/service.py`，以及 `docs/{architecture,agent-interop-mailbox,expert-teams,memory-slim,versioned-history,octop-ui-payload-offload,bridge}.md`。

**既有两份分析的逐块实测清单**（本文 §2~§8 已承载）：

- 沙箱：`infra/backend/{adapter,resolver,docker_spec,probe}.py` 全文；`infra/agents/workspace/execute_env.py` 全文；`docs/agent-backend-file-io.md` 全文（含 §13 Docker sandbox backend）
- 技能：`infra/skills/{skill_packages,install,skill_transfer,skill_package_store,workspace_catalog,presentation,skillhub_common}.py` 全文；`skillhub_market.py` 的 zip 校验与 HTTP 客户端部分
- 连接器：`infra/connectors/catalog.py`（26 条目录 + 枚举）；`gateway/{protocol,registry,cli_runner,cli_fingerprint}.py` 全文；`mcp_tool_cache.py`
- 浏览器：`api/routers/browser/{harness,stream,record_replay}.py`；`infra/agents/middleware/browser_profile.py` 全文
- 横切/中间件：`agents/middleware/` 全部 7 个文件全文；装配点 `agents/manager.py:3196-3266`
- 工具治理：`agents/settings/tool_catalog.py` 全文；`agents/settings/{runtime_limits,langfuse,profile,acp}.py`
- 数据：`db/migrate.py`；`db/repos/_base.py`；`db/services.py`；`db/migrations/` 编号成对实证
- 备份：`backup/{auto,snapshot,manifest,store,pg_dump}.py`；历史：`history/{projection,recorder,store}.py`
- 调度：`cron/{job,task_type,trigger,tools,manager}.py`；知识库：`knowledge/{gate,citations,hint,retrieve}.py`
- API 与基建：`api/middleware/{jwt_auth,setup_lockdown,bridge_proxy}.py`；`infra/metrics.py` 全文；`infra/utils/host_dirs.py`；`tests/support/*`
- 文档：`AGENTS.md` 全文；`docs/` 21 篇相关 md；`docs/adr/001`

**未核实（沿用两份既有报告的边界，未在本次复核范围）**：

1. **`octop-harness` / `octop-gateway` / `octop-memory` / `octop-browser` 源码不在快照内**，且快照无任何打包元数据（无 `pyproject.toml`/`setup.py`/`requirements*.txt`），版本号无法确认。凡涉 harness 运行时（LangGraph 图、checkpoint、token 流、`middleware` 的 `awrap_*` 实际调用时序、bwrap 路由、CDP 会话管理）的结论，**一律只核实了 Octop 侧调用与文档描述**，实现一致性未验证。
2. **`InboxMessage` 的实际并发行为**：`docs/agent-interop-mailbox.md:151` 写「全局单 worker 串行」，`docs/expert-teams.md:92` 写「inbox 按 callee 并发」——**两处文档不一致**，代码在 harness 内不可读。按「文档所述」处理并保留不确定性。
3. **快照无 git 历史**，无法判断各设计的演进顺序与稳定性。
4. **未采信**任何网络来源的 star 数、安全事件、社区评价。

---

## 12. 一句话收尾

Octop 用 Python 写了一个自托管的个人助理；helloai 用 Java 写的是一个多 Agent 任务编排平台——**两者在队列、调度、双后端这些「路线问题」上完全不能互抄**。但在四条横切纪律上，Octop 的形状好到可以逐行照抄：**大载荷旁路而不是截断**（helloai 正在丢数据）、**身份从调用上下文取而不是从模型能填的字段取**、**失败必须回叫**、**能力不可用就从工具列表摘掉**。真正值得警惕的是「让我改架构」的建议——核到底，当前只有「多实例部署前置条件」这一条是真实的未来债，其余都是夸大或重构项；先抄功能，别动架构。
