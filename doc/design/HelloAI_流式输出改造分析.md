# HelloAI 流式输出改造分析

> 编写日期：2026-08-31
> 目标：基于两份外部 AI 建议（《建议.md》第二章「Chat SSE 流式输出」与《helloai_chat_refactor_analysis.md》Chat-First 重构报告）与现有代码事实的三方比对，给出对话主回复流式输出的最小闭环设计，并明确后续阶段边界。本文档只做分析与设计，不承载实施日志；落地状态以《实现差距表》N29 与《迭代执行记录》为准。

> **状态注记（2026-08-31）**：S1 最小闭环已实施并验证通过（helloai-core 998/998 全绿 + 前端 vue-tsc 0 error）；落地详情见《实现差距表》N29（已交付）与《迭代执行记录》§6.170。S2/S3 仍为后续阶段，本文 §4 的接口签名以代码为准（流式轮入口为 `RequirementClarifyService.streamRound` + `POST /requirement-conversations/streamSendById/{id}`，事件协议 token/done/error）。

---

## 1. 结论先行

两份外部建议文档与代码三方比对后的核心结论：**搜索决策、意图路由、三态搜索、可插拔搜索、对话状态机均已在现有代码落地，当前唯一的真实 P0 缺口是「对话主回复 SSE 流式输出」**。

- 建议.md 第二章（SseEmitter + 事件分类 + StreamProfile + 前端 ReadableStream）与代码现状吻合度最高，作为流式落地的主参照；
- helloai_chat_refactor_analysis.md 的 P0 搜索策略重构、P1 意图路由与状态机重构在代码中已实现（该报告未读实际代码，论断基于 README 推测），只取其「前端中间态 UI」与显式命令思路，不再按其重复建设；
- 流式改造分三段：**S1 最小闭环（token/done/error 三事件）→ S2 中间态事件与 L0 规则快通道（绑定搜索改造）→ S3 StreamProfile/ModelRouter/TTFT 埋点**。本文档详设 S1，S2/S3 只给边界。

---

## 2. 现状事实核查（代码证据）

### 2.1 对话链路（全部同步，无流式）

- API 入口：[RequirementConversationController](helloai-api/src/main/java/com/helloai/api/controller/RequirementConversationController.java) 全部为同步 POST，返回 `R<ClarifyConversationDetail>` 全量结果；
- 轮次编排：[RequirementClarifyServiceImpl#runRoundCore](helloai-core/src/main/java/com/helloai/core/planner/service/impl/RequirementClarifyServiceImpl.java) 结构为「决策（同步 executeSync）→ 搜索（同步）→ 主回复（同步 executeSync）」三段；CHAT 轮 = `makeRoundDecision` 联合决策 + `runLlmRound` 主回复；CLARIFY 轮 = `runClarifySearchRound` 规则搜索；
- 执行层：[PlatformAgentExecutionService](helloai-core/src/main/java/com/helloai/core/agent/service/PlatformAgentExecutionService.java) 仅有 `execute / executeSync`，无流式通道；`AgentChatClientServiceImpl#doGenerate` 最终为 `chatClient.prompt().user(...).call().chatResponse()`（同步）；
- Mock 通道：[AgentChatClientServiceImpl.MockChatModel](helloai-core/src/main/java/com/helloai/core/agent/service/impl/AgentChatClientServiceImpl.java) 仅实现 `call(Prompt)`，**未实现 `stream(Prompt)`**——流式改造第一步必须补齐，否则 mock 模式（本地默认验证通道）无法闭环。

### 2.2 已被外部建议"要求做"但代码已落地的部分（明确不再做）

| 外部建议项 | 代码现状 |
|---|---|
| LLM 自主决策是否搜索（分析报告 P0 `WebSearchDecision`） | [ChatRoundDecisionParser.SearchDecision](helloai-core/src/main/java/com/helloai/core/planner/clarify/ChatRoundDecisionParser.java)：`need_search / search_query / reason` record + 顶层字段白名单拒绝（对齐 ZLAgent JsonIntentRouter） |
| 三态搜索 ALWAYS_ON / AUTO / OFF（两份文档共有） | [RequirementClarifyServiceImpl.SearchPolicy](helloai-core/src/main/java/com/helloai/core/planner/service/impl/RequirementClarifyServiceImpl.java)：由 `web_search_enabled` 持久值派生（NULL=AUTO / true=ALWAYS_ON / false=OFF），AUTO 降级时规则搜索兜底 |
| 意图路由 + 二次确认（分析报告 P1） | 联合决策 intent（chat/clarify）+ intent_reason 4 值词表；intent=clarify 落库单条确认卡（`ConfirmCardProtocol` 1 题 2 选项确认/取消） |
| 状态机三态（分析报告 P1） | `RequirementConversation.mode`：CHAT / CLARIFY / FINALIZE，会话级 `web_search_enabled` 开关，`/planner` 斜杠命令显式切换 |
| WebSearchProvider 可插拔抽象（建议.md 1.3） | [WebSearchService](helloai-core/src/main/java/com/helloai/core/planner/service/WebSearchService.java) 接口 + `helloai.web-search.provider` 配置驱动激活（bocha / tavily / deepseek-native） |
| Query 改写（建议.md 1.2） | `queryRewriteEnabled` + SearchQueryPlannerServiceImpl：LLM 改写 → 规则规划词 → 兜底截断 → 域名前置；相对时间词归一化由 RelativeTimeNormalizer 承担（2026-08-31 时间感知防线） |
| 前端 Chat 默认入口（分析报告 1.1 诊断） | 新会话始终 CHAT 自由对话（V46 起 initialMode 废弃），auto 意图路由 + 斜杠命令触发转方案 |

### 2.3 可复用的既有基建

- **SseEmitter 范式**：门铃模块 [DoorbellServiceImpl](helloai-core/src/main/java/com/helloai/core/shared/doorbell/DoorbellServiceImpl.java) 已具备显式超时、onCompletion / onTimeout / onError、心跳、registry 管理全套模式（含测试），流式端点可直接复制该范式；
- **并发限流**：`LlmCallConcurrencyGuard` 已覆盖真实 Provider 模式 acquire/release，流式通道复用同一把锁；
- **Nginx 长连接模板**：[nginx.server.conf](nginx.server.conf) `/mcp/` location 已有 `proxy_buffering off` + `proxy_cache off` + `proxy_read_timeout 3600s` 先例，流式端点落地时套用；
- **TTFT 埋点条件**：helloai-start 已有 `spring-boot-starter-actuator` + micrometer-prometheus，`micrometer-core` 在 helloai-core 可用。

---

## 3. 两份外部建议的比对裁决

| 分歧点 | 建议.md | 分析报告 | 裁决（以代码为准） |
|---|---|---|---|
| 后端流式形态 | SseEmitter（明确不切 WebFlux） | Controller 返回 `Flux<ServerSentEvent>`（示例为响应式返回类型） | **SseEmitter**：符合 CODE_STYLE 薄 Controller 约定，直接复用门铃范式，不引入 WebFlux 依赖面 |
| 搜索触发 | conditional reflection 三层（L0 规则快通道 / L1 轻量意图 / L2 生成后 CRAG 校验） | 纯 LLM 决策（成本可控） | 现状 = 分析报告方案（LLM 联合决策）；L0 快通道作为 S2 增量，L2 CRAG 与重生成链路代价大，另行评估 |
| 意图模式 | 未展开 | 显式 PLANNING 模式 + 二次确认弹窗 | 代码已有「intent=clarify → 确认卡 → 切换」闭环（ZLAgent「响应即问题」），不再新增模式枚举 |

**两份文档交集 = 真实差距清单**（代码确认未实现）：

1. **SSE 流式全链路**（唯一 P0）：后端 SseEmitter 端点 + `ChatClient.stream()` 通道 + MockChatModel 伪流式 + `[DONE]` 协议 + 前端 fetch/ReadableStream + 节流渲染；
2. **L0 规则快通道 + CRAG 后置校验**（P1，建议.md 独家）：时效词/URL/实体命中直接搜（省一次 LLM 决策调用）+ 生成后 gate 触发补搜重生成（带最大重试计数防死循环）；
3. **前端中间态 UI**（P1+，分析报告独家）：搜索状态指示器、模式切换视觉反馈、打字机 + 增量 Markdown。

---

## 4. S1 最小闭环设计（本次落地范围）

### 4.1 目标

对话主回复流式化：用户发送 → 决策/搜索保持同步前置（前端维持 loading）→ 主回复逐字/逐块流式到达 → `[DONE]` 后前端拉一次会话详情对齐落库。**不做**：中间态事件分类、搜索异步化、多模型流策略、增量 Markdown 库替换。

### 4.2 后端

1. **新端点**：`POST /api/requirement-conversations/streamSendById/{id}`，`produces = TEXT_EVENT_STREAM_VALUE`，返回 `SseEmitter`（显式超时 120s，对齐现有前端 120s 对话超时档位）：
   - 事件协议（S1 仅三类）：`event: token`（data: 增量文本块，按 50–200ms / 10~50 字符聚合再 send，防逐字打爆连接）、`event: done`（data: `[DONE]`，随后 `emitter.complete()`；同事件或紧随事件携带该轮消息 id / 会话详情摘要，前端凭此拉详情）、`event: error`（data: 错误消息，随后 `completeWithError`）；
   - 别名保持：`token` 事件后前端仍走既有 `create/send` 语义的前提是**先落库占位**（主回复消息先以"生成中"写入消息表，done 后更新内容），避免流中断时两端不一致——具体落库时机在实施时按 `runLlmRound` 现有消息持久化点确认，若改动面大，退回「done 后前端拉详情刷新」的简洁语义。
2. **执行层流式通道**：`PlatformAgentExecutionService` 新增 `executeStream(Agent agent, AgentTask task)`（或等价签名），仅对**平台内 LLM 通道**生效：
   - 路由到外部 Agent（HTTP/门铃，`AgentExecutorRouter`）时抛出/回退同步结果，并在响应中以非阻塞事件提示前端「该 Planner 为外部 Agent，暂不支持流式」；
   - `AgentChatClientService` 新增 `generateStream(...)`；`AgentChatClientServiceImpl` 内部：mock 模式用 `MockChatModel.stream()` 伪流式（对 `call()` 结果按固定分片 + 小延时发射 `Flux`）；真实模式复用 `providerRegistry.createChatClient(...)` 后走 `chatClient.prompt().stream().content()`；
   - 并发限流：`LlmCallConcurrencyGuard.acquire/release` 语义与 `generate` 完全一致。
3. **MockChatModel 补 `stream(Prompt)`**：基于 `call(prompt)` 拼出的同一份 content 分片模拟（如每 8 字符 + 20ms 延时），保证流式链路与同步链路输出一致，测试可断言。
4. **错误与终止**：订阅端 `onError → emitter.completeWithError`（含 `llmCallConcurrencyGuard.release` 兜底）；`onTimeout/onCompletion` 清理与门铃一致。

### 4.3 前端

1. **流式通道封装**：新增 `src/api/chatStream.ts`（或并入 clarify.ts），使用 **fetch + ReadableStream**（非 EventSource：需要 POST body 与 `X-Admin-Token`/Bearer 鉴权头，与 [request.ts](helloai-ui/src/api/request.ts) 的 axios 实例并行，不走 JSON 拦截器）；`TextDecoder('utf-8')` + `decode(..., {stream: true})` 处理跨 chunk 截断，按 `\n\n` 分帧解析 `event:`/`data:`。
2. **渲染**：`RequirementChat.vue` 新增流式消息区——首帧到达前维持现有「思考中…」loading；token 累积用 **50–100ms 节流**（requestAnimationFrame 或定时器）驱动现有 [MarkdownView.vue](helloai-ui/src/components/MarkdownView.vue) 全量重渲染；未闭合 `**`/``` 造成的闪烁属 S1 已知受限项，记录为 S3 增量（streamdown-vue / markstream-vue 评估）。
3. **done 后的收敛**：收到 `[DONE]` 后调用既有 `detail(id)` 拉取全量会话详情，替换流式消息区为服务端权威内容（对齐落库）。

### 4.4 运维

- Nginx：流式接口若挂在 `/api/` 下，新增独立 location（或加 `X-Accel-Buffering: no` 响应头）关闭缓冲，模板直接取自 `/mcp/` 段；`proxy_read_timeout` 对齐 SseEmitter 超时（建议 ≥ 240s）。

### 4.5 测试与验收

- 单元：`MockChatModel.stream()`（输出与 call 一致、可完成）；`AgentChatClientServiceImpl` 流式构造（mock/真实双通道、限流 acquire/release）；前端 type-check + eslint 通过；
- 集成/脚本：按项目 `scripts/` 规范新增或扩展 verify 脚本（S1：mock 模式建会话 → 流式端点收到 `token` 若干 + `[DONE]` → detail 一致；S2：断连/超时路径）；
- 验收口径：**token 首帧（TTFT，决策+搜索之后）明显短于全量返回**、无连接掐断、`[DONE]` 后落库内容一致。

---

## 5. S2 / S3 边界（本次不做，只定边界）

- **S2 中间态事件与搜索增强**（绑定 Planner 对话化）：`thinking / search_start / search_result / plan_draft` 事件分类（对标 Dify 事件模型），需要先把决策/搜索从同步前置拆成事件流，与 `L0 规则快通道`（时效词/URL/实体命中直接搜，省决策调用）一并评估；CRAG 后置校验（补搜重生成，带最大重试计数）独立立项；
- **S3 流策略与可观测**：`StreamProfile`/`ModelRouter` 多模型流适配（伪流式 provider 兜底）、TTFT/P95 埋点（Micrometer 条件已具备）、断连取消（前端「停止生成」）、增量 Markdown 渲染库选型；均绑定模型能力路由与 Planner 对话化阶段。

## 6. 明确不做

- **不切 WebFlux**（MVC 栈 SseEmitter 足够，切栈 ROI 极低，两份外部建议与代码裁决一致）；
- **不为外部 Agent 建流式通道**（外部 Agent 无 SSE 消费能力，回退同步）；
- **不重复建设**已落地的搜索决策/意图路由/三态搜索/可插拔搜索；
- **不做** HyDE / cross-encoder rerank / freshness 下推 / Redis 搜索缓存（建议.md 1.2/1.3 增量项，与 P1 搜索改造一起另行评估）。

---

## 7. 相关文档

- 《HelloAI_实现差距表.md》N29（本文档对应差距项）
- 《建议.md》第二章（外部输入，历史资产）
- 《helloai_chat_refactor_analysis.md》（外部输入，历史资产）
- 《HelloAI_CODE_STYLE.md》（实施时必须遵循，尤其 §1.x 注释、§5.5 java.time、§7.8 类规模、§21 测试规范）