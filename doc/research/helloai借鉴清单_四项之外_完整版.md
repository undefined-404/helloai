# helloai 借鉴清单（四项之外 · 完整版）

> **Status: `Analysis`**（借鉴预研，不改变任何实现、不承诺排期；A/B/C/D 四档为建议，排期在 `doc/plan/HelloAI 借鉴落地实施计划.md`）
> **背景**：2026-10-09 完成 AgentTeams/Octop 源码复核后，用户已裁定四项核心能力优先级（skills 可装配 > Fork/Return+备份恢复 > 沙箱 > RAG，见 `helloai四能力完善优先级与借鉴路线.md`）。本文件补全**四项之外**仍值得借鉴的内容，供用户审阅分析。
> **证据口径**：来源带 `path:line`；helloai 现状均逐文件核实（2026-10-09）。
> **性质**：清单/预研，不改代码、不承诺排期。按「ROI / 是否独立交付」分三档。

---

## A 档：独立可交付（安全/数据/可靠性，互不依赖，建议排入 backlog）

### A1 ★ 载荷旁路：数据面/控制面分离（helloai 唯一正在真实丢数据的地方）

- **来源**：Octop `infra/agents/middleware/octop_ui_offload.py:56-85` + `docs/octop-ui-payload-offload.md:19-28`。
- **问题定义（原文）**：一个 tool result 字符串同时服务 4 类消费者——LLM 上下文（越小越好）/ 历史持久化（要全量）/ Dashboard 渲染（要全量 data）/ 插件 UI（要全量）。**「模型与 UI 的需求对立，截断不可行，必须在 tool 执行后、进 state 前把数据面与控制面分离。」**
- **helloai 现状（已核实）**：`helloai-core/.../shared/util/UpstreamAttachmentRenderer.java`（113 行）**按行边界截断**——逐附件配额 + `[TRUNCATED] file=… shown=… total=… reason=dep_content_limit`（`:74-88`）。预算分配写得很讲究，**但数据是真丢了**：第二个及以后的附件全文永远进不了模型视野。
- **落点**：引入 `ToolResult` 双视图（`modelView` 摘要+`ref` / `uiPayload` 全量）；全量落 MinIO/DB（按内容哈希去重），摘要带 `ref`，渲染端按需拉取。**不要只调大截断阈值**（只是把问题推后）。
- **注意**：此项属于执行结果链路，用户四项未含，建议单独立项。
- **配套**（同族第二条）：Octop `middleware/workspace_image.py:1-6`——图片在 history 里**只存路径引用**（`workspace://…`），只在 model request 时物化成 data URI，「base64 永不落 state / 永不进历史 payload」。helloai 附件链路可套同一形状。

### A2 SSRF 出站 URL 校验（含 DNS-rebinding pinning）

- **来源**：Octop `infra/utils/ssrf_guard.py`。
- **helloai 现状**：`helloai-core/.../planner/service/impl/WebPageFetchServiceImpl.java`（planner 域 web_search 多厂商）——**无统一出站校验**；未来 Connector/MCP 外联同样需要。
- **落点**：`WebPageFetchServiceImpl` + 未来外联统一过 SSRF 守卫（DNS-rebinding pinning 必须）。
- **判据**：「平台发起的出站请求，目标 URL 必须经 SSRF 校验」——这是网络安全底座。

### A3 首次运行锁定（setup lockdown）

- **来源**：Octop `api/middleware/setup_lockdown.py:1-21`——系统里一个用户都没有时，锁死除 `/setup` 外的全部端点。
- **落点**：helloai 启动守卫。极廉价的安全默认，防止「初始化到一半的实例对外裸奔」。

### A4 目录守卫 + 结构化拒绝码

- **来源**：Octop `infra/utils/host_dirs.py`——禁止前缀 `/proc /sys /dev /etc /root`；**结构化探测码**（`not_directory / permission_denied / write_failed / not_allowed / outside_home / outside_root`）比布尔好；`host_path_text()` 一律 `realpath + as_posix`。
- **落点**：若将来开放「用户指定工作目录 / 挂载目录」，需要同形守卫。**结构化拒绝码**尤其值得单独抄——让「为什么不让我用这个目录」直接给用户可读原因。

### A5 失败回叫闭环语义（用户可感知，一行级改动）

- **来源**：Octop `infra/agents/teams/team_manager.py:284`（`event.error_text or "Background task did not complete."` 失败也有兜底文案）+ `docs/agent-interop-mailbox.md:148`（失败仍 `on_reply(status=failed, error_text=…)`）。
- **helloai 现状（已核实）**：`helloai-core/.../agent/dispatcher/ResilientDispatcher.java` 的 `doAssignNextFallback` **只 `log.warn` 不落 timeline**；「无候选」改不熔断后，永久无候选会让 PENDING 长期等待。
- **落点**：把「派工失败 ⇒ 必须产出一条面向用户的说明（timeline + 可读原因）」定为**不变量**，而不是逐点补日志。

### A6 备份 / 恢复（helloai 完全空白，纯增量）

- **来源**：Octop `infra/backup/`（9 文件 2396 行）：在线快照（PG `pg_dump -Fc` / `pg_restore`）、manifest 放归档开头（`peek_backup_contents` 不解压即可判断）、单飞锁 + 可轮询状态、自动/手动文件名前缀区分 + `prune_auto_backups` 只淘汰自动备份、停机恢复流程文档化。
- **helloai 现状**：`grep -rli "backup|restore"` 在 `helloai-core`/`helloai-api` **零命中**（唯一命中为报告回滚功能，非 DB 备份）。
- **落点**：`pg_dump -Fc` + MinIO 对象清单 + manifest 前置 + Redisson 单飞锁 + 保留策略。**已在四项规划第 2 步，此处仅标注来源明细。**

---

## B 档：判据级 / 小改动（零成本或极低成本，建议入 MEMORY.md「可复用判据」或门禁）

### B1 「通知是注意力信号，不是回执」+ 幂等键必须 status-scoped

- **来源**：AgentTeams `docs/design/task-completion-notification.md`（P0 ordering）+ `plugins/teamharness/mcp/server.py`（`submit-<task-id>-<status>`）。
- **判据 1**：「不得发出接收方无法消费的通知」——同步失败 = retryable 且**扣住通知**；通知失败 = best-effort 不阻断。
- **判据 2**（陷阱）：幂等键只用 `<task-id>` 的话，「先报 BLOCKED 后报 SUCCESS」会被复用分支**静默吞掉**，leader 永远等不到成功信号。
- **helloai 落点**：`agentOutboxService.createEvent(snapshot, newStatus)` 已有 status 入参——**核对去重/幂等键是否也带 status**。

### B2 审计闭合 schema + append-only keyset 分页

- **来源**：AgentTeams `docs/design/capability-foundation.md`（TestEventJSONHasClosedSchema pin）+ `audit-events-api.md`。
- **判据 1（★）**：「审计事件必须是闭合 schema——不允许存在任何自由文本字段」——因为自由文本字段一旦存在，就**无法证明**凭据没有从那里漏出去。helloai 只有凭据域 `CredentialAuditLog`，**无通用审计**。
- **判据 2**：append-only 且无界存储必须 **keyset 分页**（offset 并发追加下会重扫重排并漂移）。
- **helloai 落点**：timeline / 事件表若用 offset 分页（`SubTaskServiceImpl.java:226` `page(new Page<>(page, pageSize))` 已见 PageHelper 风格）——**建议实测核对**是否并发追加漂移。

### B3 能力摘除式治理：无 KB 即摘工具 + 不可关闭清单 + 条件可用

- **来源**：Octop `infra/knowledge/hint.py:94-99`（「无 KB 即从工具列表摘掉」＞「在描述里写不要调用」）+ `infra/agents/settings/tool_catalog.py:10-19`（`CRITICAL_TOOLS` 不可关闭清单）。
- **判据**：「禁用了」与「不具备」是两个不同的事实，**分开表达**（denylist 默认全开 + 不可关闭 + 条件可用三机制并存）。
- **helloai 落点**：`ToolRegistry` 补「按条件可用」「按上下文动态描述」语义位——**已纳入四项规划第 1 步**（`helloai四能力完善优先级与借鉴路线.md` §2）。

### B4 水位（watermark）四判据（来自真实复现报告 issue-1107）

- **来源**：AgentTeams `docs/design/internal/issue-1107-file-sync-io-amplification.md`。
- **四条判据**：① 一个 marker 不得同时表达两个语义（「最近 pull」与「最近成功 push」必须两个独立 marker）；② 失败不得推进水位、成功必须原子推进到 cycle-start；③ **检测范围必须 = 推送范围**（否则被 mirror 排除的运行时文件持续触发）；④ **「扫描比较 0 B」≠「没有 I/O」**（mc mirror 仍需遍历目录/列举对象/读元数据）。
- **helloai 落点**：MinIO 附件同步、工作区/产物同步、任何「增量拉取/推送」实现。**建议登记为可复用判据**。

### B5 身份必须来自「本次调用上下文」（★ 多实例部署前置条件）

- **来源**：Octop `infra/agents/middleware/browser_profile.py:26-44`（fail-closed：取不到隔离键就拒绝，不退化默认值）。
- **helloai 现状（已核实）**：13/13 工具**已**强制覆写 `agentId`（`McpMcpServer.java` grep `requireAuthId` 15 处）；但身份**输入源是客户端自报的 `_sessionId`**（`McpAuthContext.java:138-151`），鉴权快照存 `static ConcurrentMap`（进程级注册表，`:45`）。
- **裁定**：当前**单实例部署无实际风险**，是「**多实例部署前置条件**」（故障现象像鉴权 bug 不像架构 bug，排查成本极高）；**不得删除 `_sessionId` 协议参数**（违背向后兼容），长期收敛为无状态自携带签名凭证或外置 Redis(TTL)。

### B6 探活要区分两类问题

- **来源**：Octop `api/routers/browser/harness.py:43-60`（用零副作用 `Runtime.evaluate("1")` 探测缓存 CDP 会话是否还活着）。
- **判据**：任何「缓存的长连接/执行体句柄」必须有**廉价探活**；但**探活只治「对象在、连接死」，治不了「对象在别的 JVM」**——后者必须外置状态。
- **helloai 落点**：`SESSION_AUTH`（B5）属后者，探活无解；MCP 会话/工具缓存（若有）需廉价探活。

### B7 错误码 → HTTP 映射：语义只加在领域层

- **来源**：Octop `AGENTS.md:279` + `infra/errors.py` / `api/errors.py`——HTTP 层只做「Map OctopError → HTTP status + JSON」，**禁止新增错误语义**（新语义必须加到 infra/errors.py）。
- **helloai 落点**：约定「错误语义只加在 domain 层，HTTP 层只映射」——防止业务语义散落 Controller。可加一条生成式校验。

### B8 key parity 守卫（治前端三处登记漂移，靠门禁不靠人记）

- **来源**：Octop `AGENTS.md:255-295` + `src/octop/i18n/`——服务端产生的用户可见文本必须来自后端 bundle；**测试强制前后端 key 必须匹配**。
- **helloai 现状**：前端有 `eventMeta.ts` / `sequenceFlow.ts` / `SubTaskDetail.vue` **三处登记**（缺任一 ⇒ 界面显示裸 eventType），后端**无 `messages*.properties`**。
- **落点**：把「事件码/错误码 → 文案」收敛到服务端单一来源 + 加 key parity 守卫用例。

### B9 探针纪律：「配好了」必须能被机器验证

- **来源**：Octop `backend/probe.py:118-214`（docker 写→读→删真实往返，失败路径也 `destroy()`）+ 连接器 `gateway/registry.py`（`probe_credentials` 是契约一部分）。
- **判据**：连接器/沙箱/执行体的「配置可用性」必须有一个**真实业务往返**的自检，且**保证回收**；「容器能起来」≠「沙箱能用」。
- **helloai 落点**：`AgentSelector` / 执行体健康检查可照抄；沙箱探针（四项规划第 3 步）同源。

### B10 状态面最小化：只存路由，不存结果

- **来源**：Octop `docs/agent-interop-mailbox.md:49-68`——`target.call` 结果与合成回复是 worker 内**局部过程值**，不入库。
- **判据**：判断标准一句话——**有没有「事后查」的查询方**。有 → 落库；没有 → 只留在调用栈里。
- **helloai 落点**：`ExecutionRecord`/`agent_event` 落库是对的（有事后查）；但「本轮 turn 的临时拼接、review 中间态」如果也落库，就是状态面债务。

### B11 工具治理：denylist 默认全开 + 不可关闭清单

- **来源**：Octop `infra/agents/settings/tool_catalog.py:10-19`（`CRITICAL_TOOLS = {ls, read_file, glob, grep, write_todos, task}`「必须保留，即使被写进 tools_disabled 也被剔除」）。
- **判据**：默认全开（denylist 而非 allowlist）——新增工具自动可用，不会因忘了加白名单而「工具神秘消失」；「禁用了」与「不具备」分开表达。
- **helloai 落点**：与 B3 同源，`ToolRegistry` 语义位一并实现。

### B12 组合根与仓储纪律：optional_updates 的 sentinel 区分「未传」/「传 null」

- **来源**：Octop `infra/db/repos/_base.py`——`partial_updates()`（None=跳过）/ `optional_updates()`（sentinel=字段被省略，区别「未传」与「传 null」）。
- **helloai 落点**：PATCH 语义正确性的关键——如果更新接口无法区分「客户端没传这个字段」和「客户端显式传了 null」，会产生静默覆盖。

### B13 新能力默认关闭：通过注入启用，不污染默认工具集

- **来源**：Octop `docs/agent-interop-mailbox.md:164-174`——`HarnessAgentManager(team_processor=None)` 表示完全不建 inbox，行为与重构前逐位一致；传入 processor 才启用。
- **判据**：新能力（SandboxProvider / SkillPackage）若一上线就并入默认链路，会让旧路径的回归验证失去基准。**能力开关应挂在 Run 配置上，而不是改执行链默认分支。**

---

## C 档：明确不抄（路线分歧 / 定位无关，与两份报告 C 清单一致）

| # | 项 | 为什么不抄 |
|---|---|---|
| C1 | Octop 单进程 / 无外部队列（ADR-001） | 与 helloai 已建的 MQ + Outbox + 补偿巡检 + 幂等消费**完全相反**；反向改会丢掉刚建成的三级容错 |
| C2 | Octop 进程内 APScheduler | helloai `@Scheduled` + `@SchedulerLock`（Redis）是多实例正确解 |
| C3 | Octop SQLite/PostgreSQL 双后端 | PG-only 是优势不是负担 |
| C4 | AgentTeams CRD / Helm / leader-election / kine / Matrix 房间与 power level | helloai 是 Java 单集群 MQ 编排；这些是 K8s 原生平台的实现细节 |
| C5 | Octop CDP screencast 逐帧直播编码 | 若要可观测+可接管，先做「步骤事件 + 截图」即可 |
| C6 | Octop Python IM 网关原样移植（55 文件 11.7k 行） | 语言不通，且 helloai 是任务编排平台不是聊天助手 |
| C7 | Octop 桌面/移动(adb)/语音/NAS 打包/自更新/Let's Encrypt 交付面 | 与 helloai 定位无关 |
| C8 | **外部 agent 对接**（AgentTeams 封闭 runtime 枚举 `interface.go:34-39` / Octop connector 是 MCP 服务预设） | **用户裁定可借鉴内容有限**——两者均未实现真 A2A；helloai 的 MCP+SSE+心跳接单是当前最优解 |

---

## D 档：缓做 / 登记（承认观察、修正定性，不紧急）

| # | 项 | 处置 |
|---|---|---|
| D1 | MCP 身份输入源 `_sessionId` | 登记为「协议卫生 + 多实例部署前置条件」；**不删协议参数**，新客户端引导走无状态 REST 别名（与 B5 同源） |
| D2 | `SESSION_AUTH` 外置/无状态 | 多实例化前必须完成（B5） |
| D3 | 工具调用拦截层（`ToolCallInterceptor` 链） | 消除 13 处 `requireAuthId` 重复覆写，**重构项非安全修复**；配合拆 `McpToolServiceImpl`（四项规划 §2） |
| D4 | HITL 会话级批准（Codex 式 allow-all / allow-tool） | 非法输入退化到更严一侧（每次都问） |
| D5 | cron 触发源抽象 + misfire 宽限 + 用户/系统任务分离 | helloai 多实例正确解已建立，不退回进程内；只借鉴「错过补偿语义」 |
| D6 | 双 ID（整数主键 + 公有 ULID）+ 悬而未决登记册 + ADR Status 字段 | 文档形态优先，不必全表改造 |
| D7 | 版本化历史：游标分页 + 按内容哈希共享正文 | helloai `task_timeline`/AgentEvent 已是事实投影，可补 `next_cursor` 游标式分页（与 B2 同源） |

---

## 汇总：按「是否值得立即做」排序

**建议下一批做（独立、高感知、低风险）**：A5 失败回叫 → A1 载荷旁路 → A3 首次运行锁定 → A2 SSRF。

**建议登记为判据（零成本，入 MEMORY.md）**：B1-B4（幂等 status-scoped / 审计闭合 schema / 能力摘除 / 水位四判据）。

**建议随四项规划一起做**：B3/B11（ToolRegistry 语义位，四项第 1 步）、B9（探针，四项第 3 步）、A6（备份，四项第 2 步）。

**建议未来关注**：D3（工具拦截层，配合拆巨类）、D7（游标分页，配合 B2）。
