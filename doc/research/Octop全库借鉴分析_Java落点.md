# Octop 全库借鉴分析（面向 helloai · Java 落点）

> **Status: `Analysis`**（只做借鉴取证，不改任何实现、不承诺排期）
> **Scope**: 覆盖 `src/octop` 全部 20 个业务包（含既有报告未覆盖的 17 个），回答「哪些技术实现思路值得 helloai 借鉴」
> **分析日期**: 2026-10-09
> **勘察对象**: `E:/workspace/Octop-main`（TencentCloud/Octop main 分支快照，打包时间 2026-10-08 12:04）
> **姊妹文档**: [`Octop_借鉴分析_沙箱_技能化_WebAgent接入.md`](./Octop_借鉴分析_沙箱_技能化_WebAgent接入.md)（聚焦沙箱/技能化/Web Agent 三块，本报告**不重复**其结论，只在必要处订正）
> **勘察口径**: 凡标 `[实测]` 的论断均由本地逐文件读取源码/文档得出并附 `路径:行号`；标 `[推断]` 的为基于用法的反推。**未核实的写「未核实」，不用「通常/一般」充当事实。**

---

## 0. 结论（先读这段）

### 0.1 一句话

**Octop 的 Python 代码对 helloai 没有一个字节可以搬，但它在 8 类「横切工程」上把答案写成了几乎可照抄的形状。** 其中最该抄的三条甚至与「沙箱/技能/浏览器」都无关：

1. **横切关注点中间件化** —— 把「身份覆写 / 拒绝并纠偏 / 配额闸门 / 载荷改写」做成**工具调用拦截层**的 7 个中间件，而不是散落在每个工具里。
2. **★载荷旁路（offload）** —— 「大输出不进模型上下文、改存引用」；这是 helloai **唯一一处正在真实丢数据**的地方（`UpstreamAttachmentRenderer` 是截断）。
3. **★身份取「本次调用上下文」，不取「客户端参数」，也不取「进程级注册表」** —— 这条直接命中 helloai 一个**未登记的分布式缺陷**。

### 0.2 对既有报告的 3 处订正（重要，先划清）

| # | 既有报告的说法 | 本次实测结论 | 证据 |
|---|---|---|---|
| **订正 1** | A1「MCP 身份覆写中间件 —— **修一个已登记的安全缺口**」，称 10 个 MCP 工具「全部依赖调用方自报 agentId」 | **已实现，结论过期**。13 个 `@Tool` 全部在方法体内用 `requireAuthId(...)` 强制把入参 `agentId` 覆盖为鉴权身份（`McpMcpServer.java:88-95 / 117-123 / 148-153 / 176-180 / 204-208 / 244-248 …`）。仅类级 javadoc（`:39-41`）未同步更新，属**注释漂移**非代码缺口 | `helloai-core/.../mcp/McpMcpServer.java`；`grep -c "@Tool(name"` = 13 |
| **订正 2** | 真正的借鉴点被定为「覆写中间件」 | 真正的借鉴点应改为：**身份的「输入源」**。helloai 的鉴权身份靠**客户端在 arguments 里自报的 `_sessionId`** 反查（`McpAuthContext.java:138-151` 自述「客户端必须在 arguments 里显式传 `_sessionId`」），而 Octop 从**框架运行时上下文**取、且**取不到就 fail-closed 拒绝**。即：**「模型能填的字段」仍是身份链的输入端** | `McpAuthContext.java:138-151` vs `octop/infra/agents/middleware/browser_profile.py:26-44` |
| **订正 3** | `knowledge/gate.py` 被描述为「注入闸门：先定什么不许进上下文」 | **不是**。`gate.py` 是**能力就绪闸门**（embedding 依赖/模型下载/provider 可用性 → `feature_enabled && prerequisites_ok = usable`）。真正的「什么不许进上下文」由 `KnowledgeSearchHintMiddleware`（无 KB 就把 `search_knowledge` 从模型工具列表**摘掉**）+ `retrieve_context(char_budget=6000)` 实现 | `octop/infra/knowledge/gate.py:63-90`；`knowledge/hint.py:61-99`；`knowledge/retrieve.py:22-53` |

> 另外确认既有报告的一条边界判断**成立且更强**：本快照**连 `pyproject.toml` 都没有**（`find` 全库无 `pyproject.toml` / `setup.py` / `requirements*.txt`），因此 `octop-harness` / `octop-gateway` / `octop-memory` / `octop-browser` 的**实现层完全不可核实**，只能核实 Octop 宿主的调用点与其自写契约文档。

### 0.3 三档清单速览

| 档 | 项数 | 代表项 |
|---|---|---|
| **A. 建议直接采纳** | 9 | 载荷旁路、身份取调用上下文、工具调用拦截层、能力摘除式治理、MCP 会话指纹+串行化、目录守卫、key parity 守卫、setup 首次运行锁定、探测与落库分离 |
| **B. 需改造借鉴** | 8 | 备份/恢复体系、连接器网关模式、HITL 会话级批准、cron 触发源抽象、引用溯源、ACP 权限询问转发、双 ID 方案、悬而未决登记册 |
| **C. 明确不要抄** | 7 | 单进程无队列、进程内调度、SQLite/PG 双后端、CDP 直播编码、命名约定代替 schema 无边界使用、Python 网关移植、桌面/移动/语音交付面 |

---

## 1. 勘察边界

| 项 | 事实 |
|---|---|
| 快照规模 | `src/octop` 约 656 个 py；`infra/` 20 个子包 `[实测]` |
| 本次逐块实测的包 | `agents`(165 files/41,339 行) · `gateway`(55/11,723) · `connectors`(45/9,107) · `db`(35/8,569) · `skills`(11/3,218) · `setup`(15/3,067) · `utils`(26/4,167) · `history`(17/2,649) · `bridge`(10/2,543) · `backup`(9/2,396) · `knowledge`(17/2,275) · `users`(11/2,117) · `cron`(7/1,300) · `backend`(10/1,628) · `browser`(2/550) · `proactive`(4/913) · `auth`(26/3,585) 等 `[实测]` |
| 可读的契约文档 | `docs/` 21 篇 md（含 `adr/` 2 篇）+ 根 `AGENTS.md`（417 行）`[实测]` |
| **不可读** | `octop-harness` / `octop-gateway` / `octop-memory` / `octop-browser` **不在快照内**（无打包元数据可查版本）`[实测]` |
| **未采信** | 任何网络来源的 star 数、社区评价、安全事件 —— 与「借鉴」无关 |

**含义**：Octop 的**宿主侧设计**（中间件怎么装、载荷怎么剥、迁移怎么编号、备份怎么选内容）可逐行核实；**运行时内核**（LangGraph 图、checkpoint、token 流）只能读它自己写的契约文档，不能验证实现是否一致。凡涉内核的结论均标「文档所述」。

---

## 2. 第一梯队：3 条工程纪律（与功能无关，ROI 最高）

### 2.1 横切关注点 = 工具调用拦截层（不是策略模式，是「唯一覆写点」）

Octop 在 `manager.py` 一处装配 **7 个中间件**，每个只干一件事 `[实测]`：

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

关键设计约束，逐条 `[实测]`：

- **顺序语义**：注释明写「列表首位 = 最外层；`OctopUiOffload` 放最内层，使所有外层中间件观测面一致」（`manager.py:3223-3225`）。
- **插件也能贡献中间件**：`PluginRegistry().build_middleware_chain(global_enabled=…)`（`manager.py:3201-3203`）——**扩展点对插件开放**，不必改核心。
- **安全策略是「全局 + Agent 覆盖」合并**：`SecurityPolicy.merge(global_policy, agent_override)`（`manager.py:3204-3206`）。
- **拒绝时要「纠偏」而非「报错」**：`binary_read_guard.py:81-95` 返回的不是「禁止」，而是 `"read_file blocked for binary PDF \`x\`. Use execute_shell_command with the pdf skill (pdftotext, pdfplumber, etc.) instead."` —— **把正确路径写进错误消息**。

**对 helloai 的落点**：

1. helloai 有 `ToolRegistry` / `ToolDefinition`（`helloai-core/.../tool/ToolDefinition.java` 仅 `name + description` 两字段，javadoc 自述「元数据留待 P1 Capability System 补齐」），但**没有「工具调用拦截层」**。当前校验逻辑是散在 `@Tool` 方法体内（如 `McpMcpServer` 每个方法各写一遍 `requireAuthId` + 覆盖）。
2. 建议在 `ToolExecutor` / `ToolCallbackToolExecutor` 与工具实现之间引入 **`ToolCallInterceptor` 链**（与 spring-ai 的 `ToolCallback` 解耦），语义对齐 Octop：
   - 顺序 = 注册顺序，**最内层先看到原始返回**；
   - 每个拦截器单一职责；
   - **「模型能填的敏感参数」必须有且只有一个覆写点**（禁止在 `@Tool` 方法体内各写一遍——13 处重复即 13 个漂移风险）；
   - 拒绝时返回**可执行的纠偏文案**，而不是裸异常。

> **判据（可直接写进规范）**：*任何「模型可见且可填」的参数（`agentId`/`tenantId`/`userId`/`subTaskId`/`workspaceDir`/`credentialId`/`profile`），其取值必须由平台在拦截层无条件覆写；覆写点数量必须为 1；取不到隔离键时必须拒绝（fail-closed），不得退化为默认值。*

### 2.2 ★身份必须来自「本次调用上下文」

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

**helloai 的实测现状**（这是本报告最重要的一节）：

| 事实 | 证据 |
|---|---|
| 服务端**已**强制覆盖 `agentId`（13/13 工具） | `McpMcpServer.java:90-93, 119-122, 150-153, 177-180, 205-208, 245-248 …` |
| 但**身份的输入源是客户端自报的 `_sessionId`** | `McpAuthContext.java:138-151`：「spring-ai 1.1.0 的 `SyncMcpToolMethodCallback` 反射器不认识 `McpSyncServerExchange`，`ToolContext.getContext()` 实际为空 map，sessionId 永远自动进不来。**客户端必须在 arguments 里显式传 `_sessionId`**」 |
| 鉴权上下文存于 **`static ConcurrentMap`（进程级注册表）** | `McpAuthContext.java:45`；javadoc `:29-32` 自述「static … 进程级缓存」 |
| 清理仅**单 JVM** TTL 扫描 | `SessionAuthCleaner.java:47-52`（`@Scheduled` 扫本进程的 map） |

**由此得到两个真实缺陷（未在既有文档中登记）**：

- **D-1（身份链输入端仍是模型可填字段）**：`_sessionId` 是模型/客户端可见且可填的。虽然它指向的鉴权快照由 `McpAuthFilter` 用 Authorization 头写入（不可直接伪造），但「身份解析的入口参数可被模型填写」这一事实本身，就是提示词注入下的攻击面；且一旦 sessionId 因日志/响应/前端泄漏，即可跨主体复用。
- **D-2（进程级注册表 = 单实例假设）**：`static ConcurrentMap` 使「SSE 握手落在 A 节点、后续工具调用被负载均衡到 B 节点」⇒ `requireAuthIdBySessionId` 查不到 ⇒ **401**。这与项目 V1→V2「凡单实例假设皆为待清偿项」的口径**直接冲突，且未进入任何清偿清单**。`SessionAuthCleaner` 只是单 JVM 的 TTL 兜底，不能跨节点。

**对 helloai 的落点**（三条，按性价比排序）：

1. **把身份源改为「框架注入的 per-invocation 上下文」**：spring-ai 侧已存在更安全的入口 `McpAuthContext.requireAuthId(ToolContext)`（`:130-133`），但 `McpMcpServer` 走的是 `requireAuthId(String sessionId, String _sessionId)`（`:59-62`）即客户端传参路径。**收敛为只用 `ToolContext`**（或 `McpSyncServerExchange`），把 `_sessionId` 参数**从协议里删掉**而不是留作兜底。
2. **进程级注册表 → 外置状态**：`SESSION_AUTH` 迁到 Redis（`sessionId → AuthContext(id,name,type)`，带 TTL），或直接做成**无状态**（sessionId 自携带签名凭证，服务端只需验签 + 查 Authorization 快照）。后者更优：顺带消灭 `SessionAuthCleaner` 这个任务。
3. **把 D-1/D-2 登记进差距表**（当前是「代码注释写了、清偿清单没有」的状态）。

> **同一条纪律其实已写在项目记忆里**（「模型可传参的敏感参数必须在框架层强制覆写」）——本次实测的增量是：**helloai 只做了一半（覆写做了，身份源没换）**。

### 2.3 失败必须回叫，禁止静默；平台不替用户拉起未运行执行体

Octop 两处口径一致 `[实测]`：

- **inbox worker 最后一行**：异常 ⇒ `status=failed` 时**仍然调用** `on_reply(status=failed, error_text=…)`，让 source agent 给用户兜底说明（`docs/agent-interop-mailbox.md:148`；§11 决策 5）。
- **专家团决策 15**：「对方没运行则派工失败，主持人权走失败回叫，**不自动启动**」（`docs/expert-teams.md:30`）。即**平台不应替用户拉起一个未运行的执行体**——那是权限边界，不是可用性优化。

**对 helloai 的落点**：helloai 的三级容错（子任务核验链、报告审查链、补偿巡检）解决的是「失败怎么重试」，但**「失败之后由谁向用户解释」是另一件事**。已有的两处高价值缺口正属此类：`ResilientDispatcher.doAssignNextFallback` 只 `log.warn` 不落 timeline；「无候选」改为不熔断后，永久无候选会让 PENDING 长期等待。**建议把「派工失败 ⇒ 必须产出一条面向用户的说明（timeline + 可读原因）」定为不变量**，而不是逐点补日志。

---

## 3. 第二梯队：上下文与载荷治理（helloai 最缺的一块）

### 3.1 ★载荷旁路：截断不可行，必须「数据面 / 控制面分离」

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
- 挂载在**最内层**，对 26 个现有插件**零改动生效**。

**helloai 现状（实测）**：`helloai-core/.../shared/util/UpstreamAttachmentRenderer.java`（113 行）是**按行边界截断**——逐附件配额 + `[TRUNCATED] file=… shown=… total=… reason=dep_content_limit` 标注（`:74-88`）。预算分配本身写得很讲究（主附件保底 + 次要最低配额 + 连标题行/标注行的最坏开销都预留），**但数据是真丢了**：第二个及以后的附件全文永远进不了模型视野。

**对 helloai 的落点**：

1. 引入 **`ToolResult` 双视图**：`modelView`（摘要 + `ref`）与 `uiPayload`（全量）。全量落 **MinIO/DB（按内容哈希去重）**，摘要带 `ref`；渲染端按 `ref` 按需拉取。
2. **不要只调大截断阈值**——那只是把问题推后；只要「同一份数据既喂模型又喂 UI」，就必然在某个长度上二选一。
3. 补齐四个细节（极易漏）：① 阈值化触发而非全量剥离；② 副作用消费方的**守护条件**（helloai 的媒体路径提取同理）；③ **异常绝不破坏工具调用**；④ 原地修改保留全部协议字段。
4. 同族第二条：`WorkspaceImageMaterializeMiddleware` `[实测]` `middleware/workspace_image.py:1-6` —— 图片在 checkpoint/history 里**只存路径引用**（`workspace://…`），**只在 model request 时**物化成 data URI。「base64 永不落 state / 永不进历史 payload」。helloai 的附件链路可套用同一形状。

### 3.2 引用溯源 + 人机双读（citations marker）

`[实测]` `infra/knowledge/citations.py:10-66`：检索结果在返回文本**尾部**附加一个 HTML 注释 marker：

```
正文…

<!--octop-kb-citations:[{"kb_id":...,"doc_id":...,"filename":...,"path":...}]-->
```

- **前端**解析该 marker 渲染成引用卡片；
- **喂模型前**用 `strip_citations_marker()` **剥掉**（「LLM 通常忽略它」但不必让它占 token）；
- 引用按文档**去重**（`citations_from_ranked`，一篇文档一条引用，保持检索序）。

**对 helloai 的落点**：这是「同一段文本，机器读 marker、人读正文、模型读纯净正文」的最低成本实现。helloai 的报告/子任务结果/核验意见如果需要「可点开的引用来源」，套用此形状即可，无需引入新存储。

### 3.3 能力不可用 ⇒ 「从工具列表摘除」＞「在描述里写不要调用」

`[实测]` `infra/knowledge/hint.py:94-99` 的 docstring 一句话点题：

> *Hide the tool entirely when nothing is attached — a "do not call" note in the schema is weaker than omitting the tool from the model's tool list.*

两件事同时做：

1. **无 KB ⇒ 从 `request.tools` 里删掉 `search_knowledge`**（`:67-75`）；
2. **有 KB ⇒ 每轮重写该工具的 description**，注入**本轮实际可见的 KB 目录**（`:77-91`）——因为模型不可能知道运行时有哪几个知识库。

**对 helloai 的落点**：`ToolRegistry` 应增加两个语义位——**「按条件可用」**与**「按上下文动态描述」**。这同时是把 planner 工具收窄从硬编码变成配置的手段（既有报告 2.5 的「调度者必须被剥夺动手能力」正好需要这个位）。

### 3.4 上下文预算显式化

`[实测]`：

- `knowledge/retrieve.py:22-53`：`retrieve_context(..., k=8, char_budget=6000)` —— 注入有**字符预算**，且检索在 `run_in_executor` 里跑（阻塞 I/O 不占事件循环）。
- `agents/settings/runtime_limits.py:1-30`：`max_iters` / `max_input_length` / `temperature` / `top_p` / `max_tokens` 五个**可传参的运行时旋钮**，映射到 `recursion_limit` / `max_input_tokens`（默认 128,000）/ `model.bind(...)`。解析一律走 `_positive_int` / `_unit_float`，**非法值静默丢弃而不是报错**（`:33-77`）。
- `agents/context_breakdown.py`：上下文用量可按来源**分解**（读 `AGENTS.md`/`USER.md`/`SOUL.md` 等）。

**对 helloai 的落点**：把「每轮注入预算」写成**显式契约**（配置项 + 单测断言），而不是散落的常量。helloai 的成本选人已用「近 5 次 token 均值」，这份预算契约是它的上游。

---

## 4. 第三梯队：数据与状态

### 4.1 迁移纪律（Flyway 对照，注意不可照搬的部分）

`[实测]` `infra/db/migrate.py` + `AGENTS.md §7`：

| Octop 规则 | 证据 |
|---|---|
| 编号**成对** `00N_desc.sql`（SQLite） / `00N_desc.pg.sql`（PG），按方言分别发现 | `migrate.py:36-45` |
| **重复版本号直接 `raise RuntimeError`**，并给出修复指引「Fold unreleased schema into a single NNN_*.sql / NNN_*.pg.sql pair」 | `migrate.py:53-60` |
| `_schema_version` 是**已应用水位**，不是变更日志 | `AGENTS.md:230` |
| **未发布的 schema 改动折叠进当前未发布版本，不新开号**；只有已发布后才切新号 | `AGENTS.md:228-231` |
| 重建类 DDL（SQLite 无法 `ALTER` 的）必须**幂等** | `AGENTS.md:223-234` |
| 版本号有**测试断言**（`test_db_pool.py` 断言 `v == 20`） | `AGENTS.md:226` |

**对 helloai 的落点**：helloai 用 Flyway（`V1__init_all.sql` … `V102+`）。可借的是**纪律**而非机制：

1. **「未发布折叠」**——对 Flyway 而言，checksum 会挡住「改已发布文件」，但**挡不住「为一个小改新开 V103」的版本号膨胀**。建议把「同一开发周期内的相关改动合并进一个未合入的 V 号」写成规则。
2. **版本号断言用例**（防止有人手工改库后水位错乱）。
3. **幂等性作为 review checklist**。
4. ⚠️ **不可照搬**：helloai 铁律「已应用的 `V*.sql` 不得改注释（checksum 失败）」——Octop 的「折叠」只对**未发布**版本合法，Flyway 下等价物是「折叠进尚未合入 main 的 V 号」，不能对已上线版本操作。

### 4.2 双 ID 方案（整数代理主键 + 公有字符串 ULID）

`[实测]` `AGENTS.md:236-251`：所有**对外可见的资源表**（`agents` / `channels` / `threads` / `cron_jobs` / `knowledge_bases` / `skill_packages` / `published_experts` …）采用：

| 列 | 角色 |
|---|---|
| `id` | 整数代理主键（AUTOINCREMENT / IDENTITY） |
| `{entity}_id` | **公有字符串 UNIQUE（ULID / 短 id）**——API、路径、其他表都用它 |

- 子行存**字符串** id，`REFERENCES parent({entity}_id)`；**绝不 FK 整数 `id`**。
- 收益：对外不暴露自增（防枚举/防爬/防业务与主键耦合）；整数主键保持 B+Tree 插入局部性；跨系统引用稳定。
- **并明确列出「不要强制套用」的例外**：`users`（整数 FK，登录身份是 username）/ name-keyed 配置（`providers`/`voice_providers`/`storage_backends`）/ 追加型日志（`usage_log`/`audit_log`/`care_push_records`）/ KV（`settings`/`secrets`）/ 1:1 扩展（`proactive_care_config` keyed by `agent_id`）/ 临时行（`sso_login_states`）。

**对 helloai 的落点**：这里**最值得抄的其实是「规则 + 例外清单」这个文档形态**——它让一条 DDL 规范可以被机械校验（哪些表例外是穷举的，而不是「酌情」）。helloai 若对外 API/URL 暴露自增主键，可评估是否需引入公有 id；不必全表改造。

### 4.3 组合根与仓储纪律

`[实测]` `infra/db/services.py`（217 行）：

- `RepoBundle`（`@dataclass(frozen=True)`，`:37-68`）——一表一 repo，共 29 个，全部在 `repos/` 下，**只写 SQL**；
- `SharedServices`（`:99-208`）——**懒属性**逐项暴露（`def user_repo(self) -> UserRepo`），另有 `db()`；
- `repos/_base.py`：`now_ts()` / `bool_int()` / `map_rows()` / `partial_updates()`（None = 跳过）/ `optional_updates()`（sentinel = 字段被省略，区别「未传」与「传 null」）/ `sql_in_placeholders()`（`?`，注释明写「加 PG 时换 `%s`」）/ `sql_unix_day_bucket(column, dialect)`。

**AGENTS.md §5 的表格形态**（这才是真正可复制的部分）——每层一张 `May import` / `Must NOT import` 表，外加 6 条 **Hard bans**（`AGENTS.md:97-104`）：

```
- infra/ → api/, cli/, launch.py
- api/ → cli/, launch.py
- cli/ → api/
- infra/db/repos/ → any non-DB infra package
- infra/utils/ → any non-utils infra package
- Routers must stay thin: validate HTTP, call infra/, map errors — not new domain rules
```

**对 helloai 的落点**：helloai 是 Spring + 既有「依赖链 planner > review > task > agent > system > shared」「跨域经 Service/端口，禁直捅 Mapper」的约定。可借的是把约定写成**可生成式校验的表格 + Hard bans 清单**（项目已有「架构守卫生成式 70 条」的基础设施，把 §5 形式的禁令补进去即可）。另外 `optional_updates` 的 **sentinel 区分「未传」/「传 null」** 值得单独抄——这是 PATCH 语义正确性的关键，与项目既有「PG NULL 判据」一脉相承。

### 4.4 备份 / 恢复（helloai 完全空白）

`[实测]` `infra/backup/`（9 文件 2,396 行）+ `api/routers/backup.py`：

| 设计点 | 证据 | 价值 |
|---|---|---|
| **在线快照**：SQLite 走 online backup API；PG 走 `pg_dump -Fc` / `pg_restore` | `backup/snapshot.py:34-46`；`backup/pg_dump.py:22-50` | 不锁库、不要求停机 |
| **单飞锁 + 可轮询状态** | `backup/auto.py:49-73`：`get_backup_operation()` / `backup_status_payload()` / `raise_if_backup_busy()` | 防止并发备份、前端可轮询进度 |
| **manifest 放在归档开头**，`peek_backup_contents()` 只读头部即可判断「是否含聊天」 | `backup/store.py:135-146`；`backup/manifest.py` | **不解压就能决策**（列出/恢复前预览） |
| **自动 / 手动文件名前缀区分** + `prune_auto_backups(keep)` 只淘汰自动备份 | `backup/store.py:222-230`；`auto.py:81-89` | 用户手动备份永不被自动清理误删 |
| **停机恢复流程文档化**，并明确「运行中备份不保证多文件同一瞬间」 | `docs/versioned-history.md:96-107` | **诚实边界**，不许诺做不到的原子性 |

**对 helloai 的落点**：helloai 是 PG + MinIO + RabbitMQ，`grep -rli "backup"` 在 `helloai-core`/`helloai-api` **零命中**（实测），即**无备份/恢复能力**。可直接对应：`pg_dump -Fc` + MinIO 对象清单 + manifest 前置 + Redisson 单飞锁 + 保留策略。这是一项**纯增量、可独立交付**的运维能力。

### 4.5 版本化历史：三条可搬的「渐进替换数据」范式

`[实测]` `docs/versioned-history.md` 通篇是「如何在不停机、不迁移旧数据的前提下替换存储格式」——**这个范式本身比它的具体实现更值得抄**：

1. **开关默认关闭 + 新旧共读 + 只对新区段生效**：旧前缀只在新归档里登记边界，不复制正文（`:16-19`）。
2. **以完整回合为边界切换**，且「等待用户批准的回合继续用开始时的格式，恢复时不重选」（`:16`）——**格式切换必须绑定在一个原子单元上**。
3. **正文按内容哈希共享**：新文件拆 `documents`（消息/轨迹结构）与 `bodies`（按内容哈希共享的正文、thinking、工具参数/结果），同一大字段可被两种视图引用（`:22-24`）。

另外两条**诚实语义**值得抄：

- 回合状态机 `active/paused/complete/partial/failed/interrupted`，且明确 **「complete 仅表示本采集器通过当前回合的检查，不能证明旧会话完整，也不能证明上游未漏发事件」**（`:74-76`）——**状态名不得夸大其保证范围**。
- 读取失败**必须报错，不能按空历史处理**（`:71-72`）——失败与「空」是两件事。

### 4.6 文档形态：ADR + 「已确认决策」枚举表

`[实测]`：`docs/adr/001-single-process-model.md`（`**Status:** Accepted` / Context / Decision / Rationale / Trade-offs 表 / Consequences）；`docs/expert-teams.md` 与 `docs/agent-interop-mailbox.md §11` 用**编号决策表**（「已确认决策」）把「定下来的事 + 当时理由」冻结成可引用的清单。

**对 helloai 的落点**：给「架构变更记录」补 **`Status` 字段**，并把散落的「暂不修 / 待评估 / 已登记未修复」收敛成一份**悬而未决登记册**（D-1/D-2 正好是头两条）。

---

## 5. 第四梯队：调度与主动能力

### 5.1 让 Agent 自己管理定时任务

`[实测]` `infra/cron/`：

- **6 个工具** `cronjob_list / get / create / update / delete / run_now`（`cron/tools.py:99-226`），每个入口都先 `_require_agent_owner(mgr, agent_id, user_id)` 做所有权校验（`:73-89`）；
- 任务模型带 `task_type ∈ {text, agent}`（直推 vs 跑完 AI 再推）、`fresh_thread`、`session_key`、`model`、`mcp_servers`（`cron/job.py:21-51`）；`task_type.py` 里 `normalize_*`（未知值**收敛到默认**）与 `require_*`（用户输入**校验报错**）**分成两个函数**；
- 触发器抽象支持三种时间触发 + **外部事件源**（`AgentlyMailTrigger`，注释「An external event source, never scheduled on the wall clock」，`cron/trigger.py:19-24`）；
- `misfire_grace_time=60`（业务任务）/ `300`（系统任务）（`cron/manager.py:280, 418`）。

**对 helloai 的落点**：helloai 的 `@Scheduled` + `@SchedulerLock`（实测 20+ 处，`helloai-job/task/*`）是**多实例正确解，不要退回进程内调度**。可借鉴三点：① 把「用户/Agent 可创建的定时任务」与「平台系统任务」**分成两套**（权限与生命周期不同）；② `misfire_grace_time` 对应的**错过补偿**语义；③ **触发源抽象**（时间 vs 外部事件），这是 helloai 将来接「外部事件驱动」的自然扩展点。

### 5.2 HITL 会话级批准（Codex 式 allow-all / allow-tool）

`[实测]` `agents/security/hitl_session.py:48-96`：`HitlSessionPolicy` 是**每线程粘性批准**（`allows(tool_name)` / `with_tools` / `without_tools` / `to_json`），且解析非法输入时**退化为「每次都问」**（`:95-96`：「Accept JSON text, a dict, or None; invalid input becomes ask-every-time」）。

**对 helloai 的落点**：helloai 的核验/审批是**逐次**的。加「会话级/任务级豁免」能消掉大量无意义重复审批；关键在于**非法输入退化到更严的一侧**（不是更松）。

### 5.3 主动推送的表形态

`[实测]`：`proactive_care_config`（**1:1 扩展表 keyed by `agent_id`**）+ `care_push_records`（**追加型推送记录表**）+ `AuditRepo` 的 `ACTOR_SYSTEM` 主体。

**对 helloai 的落点**：配置用 1:1 扩展表、事件用追加日志表——**不往主表加列、不往事件表加可变状态**。这与 helloai 既有的「独立行实体 + 行级原子 upsert」判据同源。

---

## 6. 第五梯队：能力接入

### 6.1 ★连接器三模式 + 「平台侧薄适配器把异构能力包成 MCP」

`[实测]` `infra/connectors/`：

- **26 个目录条目**声明化，模式分布 `gateway 14 / remote 11 / internal 1`。语义：`remote` = 直连厂商 MCP URL；`gateway` = **Octop 进程内写一个薄适配器把没有 MCP 的服务包成 MCP**；`internal` = Octop 自托管 HTTP MCP。
- **适配器契约极小**（`gateway/registry.py`）：`list_tools()` / `call_tool(creds, name, args)` / `probe_credentials(creds)`（自检）。
- **协议实现仅 78 行**（`gateway/protocol.py:15-68`）：`initialize` / `notifications/initialized` / `tools/list` / `tools/call` / `ping`，并**把内部异常转成 MCP 的 `isError: true`**（`:58-63`）而不是抛 HTTP 500。
- **鉴权声明化**（`catalog.py:10-20`）：`AuthKind` 8 类，含 `session_cookie`（浏览器会话凭证）/ `oauth2` / `auth_code` / `imap_app_password` / `api_credentials` / `custom_fields`；`RemoteTransport` 3 类（`raw_http` / `streamable_http` / `sse`）；`ConnectorCategory` 7 类。
- **凭证表单也是数据**：`ConnectorCredentialField(key, label, field_type, required, placeholder, help, secret)`（`catalog.py:35-43`）。

**对 helloai 的落点（这是最大的一块能力缺口）**：helloai 的 MCP 是「**Server**」——Agent 通过 MCP `pullTasks/ack/claimSubTask/…` 来平台领活（13 个 `@Tool`）。缺的是「**Client/适配器**」侧：把**平台内部能力与第三方 SaaS 统一包成 MCP 工具**供 planner 使用。

- 契约形状直接照抄三方法（list/call/probe），不要设计大而全的 SDK；
- **不去要求对方支持 MCP**，而是平台侧写薄适配器——对上层完全同构；
- **`probe_credentials` 是契约的一部分**（连接器必须自带自检），这与既有报告的沙箱 probe 是同一纪律：**「配好了」必须能被机器验证**。

### 6.2 MCP 会话与工具缓存的三个细节（都可直接抄）

`[实测]` `infra/connectors/mcp_tool_cache.py`：

1. **`fingerprint_mcp_spec(spec)`（`:15-33`）**：只对**影响连接行为的字段**（`transport`/`url`/`headers`/`command`/`args`/`env`）做**规范化**（headers/env 按 key 排序、args 转 str）后取 `sha256[:16]`，**显式排除 `enabled` 等元数据** → 稳定的缓存/复用键。
   > 这就是项目既有判据「**复用同一匹配器防漂移**」的具体形状：把「什么算同一个连接」写成一个**可单测的纯函数**，而不是散在各处判断。
2. **`wrap_tools_for_shared_use(tools, lock)`（`:36+`）**：把工具包成 `StructuredTool` 并**用同一把 `asyncio.Lock` 串行化 invoke/ainvoke** —— 因为 **MCP 会话不是并发安全的**。
3. **会话探活**（`api/routers/browser/harness.py:42-62`）：`_is_session_alive()` 用**零副作用**的 `Runtime.evaluate({"expression": "1"})` + 2s 超时，探测缓存的 CDP 会话是否还活着；docstring 说明为什么必须这么做：「对象永远留在 `_registry` 里，但底层连接可能已死，复用死会话会让后续每个动作都报低层错误」。探活失败 ⇒ 丢弃 + 重连。

**对 helloai 的落点**：

- 指纹函数 → 用于 MCP 工具列表缓存、连接复用、**配置变更检测**（避免「改了 headers 但复用了旧会话」）。
- 串行化 → helloai 的 MCP session 同样不是并发安全的；**同一 session 的工具调用必须串行**（既有的「单应用→分布式」改造里，这属于「锁的粒度」问题，不要用分布式锁解决单连接互斥）。
- **探活要区分两个不同的问题**：①「对象在、连接死」⇒ 探活可解（Octop 的形状）；②「对象在**别的 JVM**」⇒ 探活**无解**，必须外置状态 —— **helloai 的 `SESSION_AUTH`（2.2 节 D-2）属于②，用探活解决不了**。

### 6.3 技能包：既有报告之外的 3 个新点

既有报告已覆盖 SKILL.md frontmatter、目录即真相、corrupt 显式化、两级治理、摄入安全闸门、来源标记。本次补充：

1. **零成本生态互通** `[实测]` `skill/presentation.py:12`：
   `_EXTENSION_NAMESPACES = ("octop", "harness", "lightclaw", "orca", "openclaw")` —— 读 frontmatter 的 `metadata.<ns>.*` 时**同时接受 5 个生态的命名空间**。一份为别家写的 SKILL.md，图标/emoji 照样能读出来。**互通的成本是「一个元组」**。
2. **`removed: true` 墓碑** `[实测]` `skill/workspace_catalog.py:131-170`：软删除靠**元数据**，不靠删目录；损坏文件以 `corrupt: true` + `error: invalid_utf8` **显式暴露在列表里**。
3. **`enabled` = 「不在禁用集合」**，且**按 `slug` 或 `name` 双匹配禁用**（`:167`）。

**对 helloai 的落点**：helloai 现状是 `AgentSkillSpecServiceImpl` 里 `KNOWN_SPECS = knownSpecs()`（`:29`/`:148`）的**编译期 Map** + classpath 4 个 `eng-*.md`。既有报告的 K1~K6 路线成立，补两条：**前 5 名生态命名空间先兼容**（成本极低）、**禁用集合双匹配**（避免「改名即失效」）。

### 6.4 ACP / Agent 互调：可借的是「共享配置按用户、开关按 Agent」的分层

`[实测]` `docs/acp.md` + `agents/settings/acp.py`：

- 每个用户一套 runner 定义，存 `settings` 表键 `acp_runners:user:{id}`；Agent 只存 `config_json.acp.tool_enabled` 一个开关（`acp.py:41-52`）；
- `acp_runner` 工具是**会话状态机**：`list / start / message / respond / status / close`，其中 `respond` 用于回答 `[permission_required]`；`close` 结束会话；
- 内置 runner 不可删、自定义可加；隐藏项 `_HIDDEN_RUNNERS`（`acp.py:17`）；`_OCTOP_BUILTIN_RUNNERS` 是「在 harness 发布内置之前，Octop 侧先合并默认值」（`:18-38`）。

**对 helloai 的落点**：helloai 已有铁律「外部 agent 必须心跳在线才可被分派、禁止模拟心跳、禁止另注册」。**最有价值的一条是「外部 Agent 的权限询问转发到对话里让人选」**——即外部执行体的越权请求应在平台上变成**一次用户可选决策**，而不是默默失败。这与 helloai 已有的人工指派兜底（`nc-fallback-*`）是同一族能力，可合并设计。

### 6.5 Bridge（跨实例）：三条可直接抄的接口纪律

`[实测]` `docs/bridge.md` + `infra/bridge/`：

- **探测与落库分离**：`POST /api/bridge/probe` 用填写的地址+凭据登录对端并拉专家摘要，**不写 `bridge_connections`、不建 WS**；只有「保存并连接」在登录 + WS `hello_ack` **都成功后才落库，失败回滚**。
- **执行侧拥有权威历史**：对话只在专家所在实例执行，发起侧仅临时会话、**不写本机 threads**；附件落到执行侧 `inbound/`。
- **影子 id 命名约定**：`bridge:{connection_id}:{remote_agent_id}`；UI 只打本机 API，本机识别远程后转发。
- **入站隧道白名单是「按能力枚举」的**：允许 agent 范围内的聊天/工作区/定时任务/状态 + 部分只读端点；**管理/认证/Bridge 控制面明确不入隧道**；技能包/全局 ACP/连接器管理/知识库管理标为 peer-only（在对端操作）。

**对 helloai 的落点**：helloai 的「外部 AI agent 接入」是**同级问题**。三条直接可用：① **探测（dry-run）与落库分离 + 失败回滚**；② **执行侧拥有权威状态**；③ **隧道白名单按能力显式枚举，控制面永不入隧道**。

---

## 7. 第六梯队：工程基建

### 7.1 后端自有词条 + 前后端 key parity 守卫

`[实测]` `AGENTS.md:255-295` + `src/octop/i18n/`：

- **服务端产生的用户可见文本**（slash 回复、IM 状态、API 错误、工具显示名、CLI 输出）**必须来自后端 bundle**，禁止硬编码英文；
- 结构：`i18n/{en,zh}.json`（canonical，**key 树必须一致**）+ `i18n/domains/*.py`（按命名空间给类型化 helper：`error_message()` / `tool_display_name()` / `slash.tr()`）；
- `ErrorCode` → JSON 的 `errors.<CODE>`；Dashboard 在 `locales/{en,zh}.json → apiErrors.*` 镜像；
- **测试强制前后端 key 必须匹配**（`tests/unit/i18n`），另有「key parity + domain helpers」用例；
- 插值用 `str.format`（`{name}`），**不用 gettext/.po**。

**对 helloai 的落点**：helloai 后端**无 `messages*.properties`**（实测 `find` 零命中），而前端有 `eventMeta.ts` / `sequenceFlow.ts` / `SubTaskDetail.vue` **三处登记**（项目记忆已记录「缺任一 ⇒ 界面显示裸 eventType」）。可借两点：

1. 把「事件码/错误码 → 文案」收敛到**服务端单一来源**；
2. **加 key parity 守卫用例**——正好治「三处登记漂移」这个已知缺陷（现在是靠人记，改成靠门禁）。

### 7.2 错误码 → HTTP 映射，语义只加在领域层

`[实测]` `AGENTS.md:279` + `infra/errors.py` / `api/errors.py`：`api/errors.py` 的职责被限定为「Map `OctopError` → HTTP status + JSON」，**禁止新增错误语义**（新语义必须加到 `infra/errors.py`）；`OctopError.to_envelope(locale=…)` 与全局异常处理器负责本地化 `message`。

**对 helloai 的落点**：约定「**错误语义只加在 domain 层，HTTP 层只映射**」——防止业务语义散落 Controller。结合项目既有的「§11.3 代码规范 P0 层」守卫，可加一条生成式校验。

### 7.3 首次运行锁定（setup lockdown）

`[实测]` `api/middleware/setup_lockdown.py:1-21`：**当系统里一个用户都没有时，锁死除 `/setup` 之外的全部端点**。

**对 helloai 的落点**：极廉价的安全默认，防止「初始化到一半的实例对外裸奔」。helloai 有初始化脚本/部署流程，可加同形守卫。

### 7.4 目录守卫：结构化拒绝码

`[实测]` `infra/utils/host_dirs.py`：

- 禁止前缀 `/proc` `/sys` `/dev` `/etc` `/root`（POSIX）；
- **结构化探测码**（比布尔好）：`not_directory` / `permission_denied` / `write_failed` / `not_allowed` / `outside_home` / `outside_root`；
- `host_path_text()` 一律 `realpath` + `as_posix`（**跨平台序列化统一**）；
- Windows 上**枚举全部就绪盘符**而不是推导单一根（注释说明理由：home 在 `C:` 的用户不该无法浏览 `D:`）。

**对 helloai 的落点**：若将来开放「用户指定工作目录 / 挂载目录」，需要同形守卫。**结构化拒绝码**这一条尤其值得单独抄——它让「为什么不让我用这个目录」可以直接给用户可读原因。

### 7.5 测试替身体系与跨平台测试纪律

`[实测]` `tests/support/`（11 个模块）：`fakes.py`（`FakeHarnessAgent` / `fake_bin_path()`）、`harness.py`（`build_harness_manager_mock()`）、`http.py`（`ASGIWebSocketSession` / `ws_token()`）、`app.py`（`write_octop_config()`）、`auth.py`（`bearer()`）、`ldap_fake.py`（`FakeLdapEntry` / `FakeLdapGroup`）、`postgresql.py`、`scenarios.py`、`secrets.py`、`testmon_staged_changes.py`、`bwrap_marks.py`。

`AGENTS.md §7` 专门有一整节 **Cross-platform tests**：不硬编码 POSIX 路径、用 `fake_bin_path()` 造不透明 token、用 `Path` 相等而非字符串前缀、`posix_only` marker 统一别名、**新 connectors/CLI/gateway 测试落文件必须 `monkeypatch.setenv("OCTOP_HOME", tmp_path)`**。

`testmon_staged_changes.py`：测试选择器与 **staged 变更**对齐。

**对 helloai 的落点**：可借的是「**共享夹具目录**」的形态——新用例默认拿替身，而不是各自起容器。这对 helloai 的门禁耗时是直接收益。另外 helloai 的「数 surefire XML `<testcase>`」口径已很讲究，`testmon` 的**staged-aware 测试选择**可评估引入。

### 7.6 门禁前置：格式化必须能回写暂存区

`[实测]` `AGENTS.md:201` + `.githooks/pre-commit`：每次 `git commit` 跑 `make all`（`format-all` + `lint` + `typecheck` + `test`）+ 前端 `npm run build`；**格式化后的文件会被重新 `git add` 回暂存区**，使提交包含格式化后的内容；明确「**不要绕钩子把红灯测试塞进仓库**」（`SKIP_PRECOMMIT=1` 仅限紧急）。

**对 helloai 的落点**：helloai 已有 `ci-gate.sh` 5 门禁。值得确认的一个细节：**格式修复要能回写暂存区**，否则开发者每次都要手动再 `git add`（这是「门禁可用性」问题，会诱导绕钩子）。

---

## 8. 明确不要抄

| # | 项 | 为什么 |
|---|---|---|
| C1 | **ADR-001 单进程、无外部队列**（`docs/adr/001-single-process-model.md:12-15`） | 与 helloai 已建的 **MQ + Outbox + 补偿巡检 + 幂等消费**完全相反。这是路线分歧不是优劣；反向改会丢掉刚建成的三级容错 |
| C2 | **进程内 APScheduler**（`cron/manager.py`） | helloai 的 `@Scheduled` + `@SchedulerLock`（Redis）是多实例正确解；退回进程内调度会在多实例下重复执行 |
| C3 | **SQLite / PostgreSQL 双后端** | 为双后端付出的代价（双份迁移、`?`→`%s` 边界改写、双备份格式、`_split_pg_sql` 的语句切分）helloai 不需要。**PG-only 是优势不是负担** |
| C4 | **CDP screencast 直播的具体编码** | 若要「可观测 + 可接管」，先做「步骤事件 + 截图」，不必抄逐帧 base64 jpeg 的帧协议 |
| C5 | **「用命名约定代替 schema」无边界使用** | 团队编制放工作区文件是好的（低写入频次 + 强所属关系）；但**执行状态、认领、幂等**这类并发写 + 需要查询的数据，绝不能放文件 |
| C6 | **Python IM 网关原样移植**（`infra/gateway/` 55 文件 11.7k 行） | 语言不通，且 helloai 是**任务编排平台不是聊天助手**。若将来接 IM，应按「通道归一化管线」在 Java 侧重写 |
| C7 | **自托管个人产品的交付面**：桌面端 / 移动端(adb) / 语音 / NAS 打包 / 自更新 / Let's Encrypt（`infra/{desktop,mobile,voice}`、`infra/setup/tls`） | 与 helloai 的定位无关 |

---

## 9. 三档采纳清单与落地顺序

### A. 建议直接采纳（按 ROI 排序）

| # | 项 | Octop 出处 | helloai 落点 | 为什么排这里 |
|---|---|---|---|---|
| **A1** | **载荷旁路**（数据面/控制面分离 + `ref` + 双视图） | `middleware/octop_ui_offload.py:56-85`；`docs/octop-ui-payload-offload.md` 全文 | `UpstreamAttachmentRenderer` + 工具结果链路 | **唯一正在真实丢数据的地方**；形状可直接抄；顺带解决上下文成本 |
| **A2** | **身份取「调用上下文」**（替换客户端 `_sessionId` 输入源） | `middleware/browser_profile.py:26-44` | `McpMcpServer` / `McpAuthContext` | 修一个**未登记**的输入面；同时把协议里一个模型可填字段删掉 |
| **A3** | **`SESSION_AUTH` 进程级注册表 → 外置/无状态** | （反向借鉴：Octop 用 per-invocation 上下文，**无**进程注册表） | `McpAuthContext.java:45` + `SessionAuthCleaner` | **单实例假设**，与 V1→V2 分布式口径直接冲突；不修则多实例必挂 |
| **A4** | **工具调用拦截层**（`ToolCallInterceptor` 链） | `manager.py:3208-3243`（7 个中间件） | `ToolExecutor` / `ToolRegistry` | 让 A2/A5/A6 有唯一落点；消除 13 处重复覆写 |
| **A5** | **能力摘除式治理**（不可用 ⇒ 从工具列表删）+ 动态描述 | `knowledge/hint.py:61-99` | `ToolRegistry` 加两个语义位 | 同时是 planner 工具收窄的实现手段；成本极低 |
| **A6** | **MCP 会话指纹 + 同会话串行化** | `connectors/mcp_tool_cache.py:15-33, 36+` | MCP 会话/工具缓存 | 「复用同一匹配器防漂移」的具体形状；并发正确性 |
| **A7** | **缓存句柄廉价探活** | `api/routers/browser/harness.py:42-62` | MCP 会话 / 外部 agent 句柄 | 拿到对象 ≠ 连接可用；约 20 行 |
| **A8** | **结构化目录守卫 + 拒绝码** | `utils/host_dirs.py` | 存储/工作目录开放前 | 廉价，且让拒绝原因可读 |
| **A9** | **首次运行锁定**（无用户 ⇒ 只放行 setup） | `api/middleware/setup_lockdown.py` | 启动守卫 | 廉价安全默认 |

### B. 需改造借鉴（能力缺口，Java 重实现）

| # | 能力 | 抄什么 | 不抄什么 |
|---|---|---|---|
| **B1** | **备份 / 恢复**（helloai 完全空白） | pg_dump + MinIO 清单 + manifest 前置 peek + 单飞锁 + 仅淘汰自动备份 + 停机恢复流程文档 | SQLite online API、双后端 |
| **B2** | **连接器三模式 + 网关适配器** | 目录条目声明化（auth_kind / 凭证表单 / transport / mode）+ `list_tools/call_tool/probe_credentials` 三方法 | 26 个 Python 适配器；只保留真实要接的几家 |
| **B3** | **key parity 守卫** | 「服务端单一来源 + 前后端 key 必须匹配」的**测试** | 不必引入 i18n 框架；先治 eventMeta 三处漂移 |
| **B4** | **引用溯源 marker** | HTML 注释 marker + 喂模型前 strip + 按文档去重 | OCR / 本地 ONNX embedding |
| **B5** | **HITL 会话级批准** | 粘性策略 + 非法输入退化到更严一侧 | Codex 交互细节 |
| **B6** | **cron 触发源抽象 + misfire 宽限** | 时间触发 / 外部事件触发分离；系统任务与用户任务分开 | 进程内 APScheduler |
| **B7** | **ACP 权限询问转发** | 外部执行体的 `[permission_required]` 变成平台上一次用户可选决策 + 会话状态机 | stdio 传输（可换 HTTP 长连接） |
| **B8** | **双 ID 方案 + 悬而未决登记册** | 「规则 + 穷举例外清单」的文档形态；ADR 的 `Status` 字段 | 不必全表改造 id；不必重构既有变更记录 |

### C. 落地顺序（依赖关系）

```
第 1 步（安全，立刻可做，互不依赖）
  A2 身份取调用上下文 ──► A3 SESSION_AUTH 外置/无状态
  A9 首次运行锁定
  └ 三者都可独立交付，且 A2 是 A3 的前提（先换输入源，再换存储）

第 2 步（上下文治理，最高业务收益）
  A4 工具调用拦截层（先有落点）
    └─► A1 载荷旁路（挂到最内层）
    └─► A5 能力摘除式治理
  └ A1 不依赖 A4（也可直接改工具结果链路），但有了 A4 才是「一次做成」

第 3 步（能力接入，依赖第 2 步的闸门）
  A6 MCP 会话指纹 + 串行化 ──► A7 探活
  B2 连接器网关模式（内部能力先包成 MCP）

第 4 步（运维与治理，可并行）
  B1 备份/恢复
  B3 key parity 守卫
  B8 登记册（把 D-1/D-2 登记进去）

第 5 步（按需）
  B4 引用溯源 ──► B6 cron 触发源 ──► B5 HITL 会话级 ──► B7 ACP 权限转发
```

**两条硬约束**：

1. **技能的脚本要在沙箱里跑** ⇒ 技能化推进到「可执行」依赖沙箱（既有报告结论，本次不重复）。若沙箱未完成，只做「安装/解析/校验」。
2. **A3 必须先于多实例部署**：`SESSION_AUTH` 是进程级注册表，一旦上多实例就是 401 随机出现，且**故障现象像鉴权 bug 而不是架构 bug**，排查成本极高。

---

## 10. 事实核查与未核实项

**本次逐块实测**（附文件级证据）：

- 横切/中间件：`agents/middleware/` 全部 7 个文件全文；装配点 `agents/manager.py:3196-3266`（含中间件列表与注释）
- 工具治理：`agents/settings/tool_catalog.py` 全文；`agents/settings/{runtime_limits,langfuse,profile,acp}.py`
- 团队与安全：`agents/teams/service.py` 头 60 行；`agents/security/{hitl_session,policy_store,tool_guard_rules}.py` 结构
- 工作区：`agents/workspace/execute_env.py:1-95`
- 数据：`db/migrate.py:1-90`；`db/repos/_base.py:1-70`；`db/services.py` 结构；`db/migrations/` 编号成对实证
- 备份：`backup/{auto,snapshot,manifest,store,pg_dump}.py` 结构与关键段
- 历史：`history/{projection,recorder,store}.py` 结构；`docs/versioned-history.md` 全文
- 调度：`cron/{job,task_type,trigger,tools,manager}.py`
- 知识库：`knowledge/{gate,citations,hint,retrieve}.py` 关键段
- 连接器：`connectors/catalog.py` 枚举与 dataclass；`connectors/{crypto,mcp_tool_cache}.py:1-60`；`connectors/gateway/protocol.py` 全文
- 后端/沙箱：`backend/docker_spec.py:1-60`；`backend/probe.py` 关键段
- API 与基建：`api/middleware/{jwt_auth,setup_lockdown,bridge_proxy}.py` 结构；`api/routers/observability.py`；`infra/metrics.py` 全文；`infra/utils/host_dirs.py:1-70`；`tests/support/*` 结构
- 文档：`AGENTS.md` 全文；`docs/{architecture,bridge,acp,agent-call-agent,agent-delegation,agent-interop-mailbox,expert-teams,agent-backend-file-io,memory-slim,octop-ui-payload-offload,versioned-history}.md`；`docs/adr/001`
- helloai 侧：`agent/mcp/{McpMcpServer,McpAuthContext,SessionAuthCleaner}.java`；`agent/tool/ToolDefinition.java`；`agent/runtime/sandbox/ExecutionPolicy.java`；`agent/skill/AgentSkillSpecServiceImpl.java`；`shared/util/UpstreamAttachmentRenderer.java`；`planner/memory/*`；Flyway 迁移目录；调度与 MeterRegistry 使用点

**未核实**：

1. **`octop-harness` / `octop-gateway` / `octop-memory` / `octop-browser` 源码不在快照内**，且本快照**无任何打包元数据**（无 `pyproject.toml`/`setup.py`/`requirements*.txt`），版本号无法确认。凡涉 harness 运行时（LangGraph 图、checkpoint、token 流、`middleware` 的 `awrap_*` 实际调用时序）的结论，**一律只核实了 Octop 侧的调用与文档描述**，实现一致性未验证。
2. **`InboxMessage` 的实际并发行为**：`docs/agent-interop-mailbox.md:151` 写「全局单 worker 串行」，`docs/expert-teams.md:92` 写「inbox 按 callee 并发」——**两处文档不一致**，代码在 harness 内不可读。本报告按「文档所述」处理并保留不确定性。
3. **快照无 git 历史**，无法判断各设计的演进顺序与稳定性。
4. **未采信**任何网络来源。

---

## 11. 一句话收尾

Octop 用 Python 写了一个自托管的个人助理；helloai 用 Java 写的是一个多 Agent 任务编排平台——**两者在队列、调度、双后端这些「路线问题」上完全不能互抄**。但在**三条横切纪律**上，Octop 的形状好到可以逐行照抄：**横切关注点做成拦截层**、**大载荷旁路而不是截断**、**身份从调用上下文取而不是从模型能填的字段取**。而这三条恰好对应 helloai 当下的三个真实缺口：**没有拦截层**、**正在截断丢数据**、**身份输入源仍是模型可填字段且鉴权状态是进程级**。
