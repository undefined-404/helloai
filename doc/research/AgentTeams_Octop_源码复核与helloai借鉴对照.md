# AgentTeams + Octop 源码复核：Fork/Return、沙箱、外部 Agent、RAG 借鉴对照

> **Status: `Analysis`**（借鉴预研，不改变任何实现、不承诺排期）
> **目的**：用户要求「再次分析两个开源项目代码，结合 helloai 现状」，特别关注四个主题——**Fork/Return 回退**、**沙箱**、**外部 AI agent 对接**、**RAG 知识库**，并核对两份既有报告的观点与独立分析的一致性。
> **勘察对象**：`E:/workspace/AgentTeams-main`（Go+Python 混合，K8s 原生）、`E:/workspace/Octop-main`（Python 单进程，LangGraph）。
> **对照基线**：helloai（Java 分布式任务编排，`E:/yhzx/1027/helloai`）。
> **证据口径**：凡结论均带 `path:line` 实证；helloai 侧现状均逐文件核实。本报告是对两份既有报告的**独立复核**，与报告冲突处以本报告为准（标 ★）。
> **分析日期**：2026-10-09。

---

## 〇、结论速览（先读这段）

用户关注的四个主题中，**前三个（Fork/Return、外部 Agent、沙箱）在两个项目里都有明确实现，且与 helloai 现状形成三种不同的「已完成度」**：

| 主题 | AgentTeams | Octop | helloai 现状 | 借鉴价值 |
|---|---|---|---|---|
| **Fork/Return 回退** | ✅ 任务级 **revision**（退回重做）+ 转换史 | ✅ 对话级 **Fork**（复制 checkpoint 前缀到新线程） | 🔶 Fork **快照已做、驱动执行后置**；Return（REWORK 驳回→改派）**闭环已完整** | **高**——两份实现正好补 helloai Fork 的「后置半截」 |
| **沙箱** | ✅ K8s SandboxClaim + **Hibernate/Resume 冷启动** | ✅ 声明式 docker spec + **写→读→删真实探针** | 🔴 `ExecutionPolicy` 五边界已设计，`Status: Planned` **零实现** | **高**——Octop 的 spec+probe 形状可直接照抄 |
| **外部 AI agent 对接** | ✅ MCP `tool+action` 分发表 + 房间消息驱动 | ✅ 三模式连接器 + bridge 跨实例 + 失败回叫 | 🔶 REST/MCP 领活 + 心跳铁律已有；缺「失败必须回叫」不变量 | **中高**——回叫语义是真实缺口 |
| **RAG 知识库** | 🔶 技能/目录注入（非向量 RAG） | ✅ 完整链路：分块→embedding→SQLite 索引→检索→引用 marker | 🔴 **完全空白**（零命中 embedding/向量/知识库） | **高**——Octop 形状可抄，但 pgvector 替代其 SQLite 侧库 |

**与两份报告的一致性裁定**（详见 §5）：**大方向完全一致**——报告的「先抄功能、别动架构」「载荷旁路是唯一真丢数据处」「沙箱 spec 声明化」「RAG 先定边界再检索」四条判断我全部同意。**两处分歧**（本报告独立分析得出）：① 报告对「Fork/Return 回退」几乎没覆盖（AgentTeams 报告的 `REVISION_NEEDED` 只当状态机枚举提了一句，Octop 报告的 `fork.py` 完全没读）——而这是用户点名关心的能力；② 报告把「外部 agent 对接」主要落在 connector/MCP 网关模式上，**忽略了 helloai 已有的 REWORK→改派闭环**已等价实现了 AgentTeams 的 revision 主体语义。

---

## 一、Fork / Return 回退（用户点名、两份报告都漏掉的部分）

### 1.1 AgentTeams：任务级「退回重做（revision）」+ 可审计转换史

**核心事实**：AgentTeams 的「return」是**任务级 revision**——Leader 验收不合格时把任务**退回**（状态 `submitted → revision`），Worker 修正后重新提交。这是 helloai `REWORK` 的**同构物**，但 AgentTeams 把它做进了「一张表 + 单入口 + 可审计历史」的转换引擎里。

**状态机**（`plugins/teamharness/contracts/task-transitions.json`）：
```json
"states": ["planned","prepared","assigned","in_progress","submitted",
           "completed","revision","blocked","cancelled"],
"transitions": {
  "submitted": ["completed","revision","blocked","cancelled"]
}
```
- **`revision` 是终态之一**（`server.py:4482` `TERMINAL_TASK_STATUSES = {"completed","revision","blocked","cancelled"}`）——注意：revision 一旦落定即为终态，**新的执行靠「re-dispatch」新建一条任务流**，不是原地改状态。

**revision 的产生**（`server.py:3384-3385`）：
```python
accepted = _payload_bool(payload.get("accepted"), True)
node_status = _accepted_node_status(result_status_value)
if not accepted and node_status == "completed":
    result_status_value = "REVISION_NEEDED"
    node_status = "revision"
```
即：Leader 收到结果时若传 `accepted: false`，则本应 completed 的结果被强制改写为 `REVISION_NEEDED` → 节点状态 `revision`。

**退回通知**（`server.py:4952`）：
```
@leader TASK_REVISION_NEEDED: <task-id> - <summary>
```
Matrix 房间内用**契约行**唤醒 Leader 处理退回任务（与 helloai 的 inbox 通知同族）。

**退回后如何重做**（`server.py:5390-5403`）——**最关键的一段**：
```python
# /re-dispatch of a broken or revision state) must not erase
# the audit trail already recorded on this task: the fresh
# meta dict below replaces meta.json, so carry the existing
# entries over before the write.
prior_history = existing_task.get("history")
if isinstance(prior_history, list) and prior_history:
    task["history"] = list(prior_history)
...
_append_transition_history(task, _delegate_from, "prepared", "delegate_task",
                           _transition_actor(arguments),
                           note=("re-delegate: new assignee" if redelegate
                                 else "repair: assigned without eventId"))
```
**判据（★）**：退回重派 = **re-delegate 到 `prepared` + 保留原 history（不抹审计）**。terminal 状态的 revision 任务可被 re-dispatch 重新打开为 `prepared`——但**审计轨迹（谁、何时、为何退回）永久保留**。

**可审计转换史**（`server.py:4633` `_transition_task`）：
- 单入口：表校验 → 写 status → 追加 `history`（cap 50 丢最旧）→ 同批同步；
- 条目格式：`{ts, from, to, action, actor, note, seq}`，`actor = role:account`，同状态重入不记，`seq` 为游标提供稳定身份；
- 新增 `report_progress` action：`from == to`，note 必填 ≤200 字符，**不改状态、不发房间通知**（v1 防噪音）。

### 1.2 Octop：对话级 Fork（复制 checkpoint 前缀到新线程）

**核心事实**：Octop 的「Fork」是**对话级分叉**——从某条 assistant 回复处把整段历史**复制到一个新 thread**，原会话不动，用户在新分支继续。

**完整实现**（`src/octop/infra/agents/threads/fork.py`，290 行）：
- `find_assistant_fork_index`（`:94-135`）：定位要分叉的 assistant 回答——优先按「从末尾数第 N 个回答」(`assistant_turns_from_end`)，其次按 `message_id` / `content` 回退；**跳过只发工具调用、没有最终回答的 AIMessage**（`:78-91`，与前端「一个回答一个气泡」对齐）；
- `load_checkpoint_messages`（`:138-156`）：从 LangGraph checkpoint 读全量 transcript（`_FORK_HISTORY_LIMIT = 100_000`）；
- `write_checkpoint_messages`（`:159-178`）：用 `aupdate_state` 把 `prefix = messages[:idx+1]` 灌进新 thread——**「包含被选中的那条 assistant 消息及其之前的全部工具流量」**；
- `fork_dashboard_thread`（`:181-289`）：建新 thread → 写 checkpoint → **标题加后缀**（`_fork_title`，`:25-30`）→ 继承 `model_ref / reasoning_mode` → **rebind 到同一 session_key**（`:275-279`）→ 返回 `{thread_id, source_thread_id, copied_messages, ...}`；
- **失败回滚**（`:260-264`）：写入失败 → `archive.remove_thread` + `thread_registry.delete_thread`，不留脏线程；
- 与版本化历史联动（`:202-212`）：源会话若已走 v2 分段归档，则从 `history_archive` 读、否则从 checkpoint 读——**fork 兼容两种存储形态**。

**API 入口**（`src/octop/api/routers/chat/history.py:419-445`）：`POST /agents/{agent_id}/threads/{thread_id}/fork`，body 为 `ForkThreadBody`（`models.py:152`）。`versioned-history.md:83` 明写「分叉从兼容读取结果取选定前缀，**原会话不被回填或重写**」。

**判据（★）**：Octop 的 fork = **「读 checkpoint 前缀 → 写入全新 thread → 继承会话上下文」**，原会话只读不改。与 helloai 的 `AgentEventForkService` 是**同一思维**（快照复制不扰动原链路），但 Octop 完成了「**驱动新会话可继续跑**」这后半截。

### 1.3 helloai 现状（已核实）——Fork 半截、Return 已完整

**Fork（半成品）**：`helloai-core/.../agent/event/AgentEventForkService.java`（109 行）：
- `forkRun(taskId)`（`:64-95`）：把 `run-{taskId}-1` 全部 `agent_event` 复制到新 `run_id = run-{taskId}-1-fork-{seq}`（seq = 已有 fork 数 + 1）；
- 新 `event_id`（UUID）+ 新雪花 id，payload 防御性拷贝，remark 标 `forked from <originRunId>`；
- **不复制 `agent_outbox_event`**（fork 是只读快照，不重投 MQ）；
- 注释明写三处**未做**（`:32-40`）：① 不暴露 API（无 REST 端点）；② 原 Run 不冻结（无 `frozen` 列）；③ **不驱动新 Run 执行**（「fork 后让新 Run 跑起来」需 run_id 上下文接线，属 Agent Governance）。

**Return（已完整）**：helloai 的「退回重做」= **人工/自动驳回 → `REWORK` → 改派 → 重开工**：
- 状态机（`helloai-common/.../constant/SubTaskStatus.java:12`）：`REWORK` 在 `REVIEW` 之后；
- 驳回流程（`ReviewServiceImpl.java:127-162`）：`REVIEW_REJECTED` 事件 → `reworkFresh(subTaskId, reworkAgentId)`（重置返工计数 + 清人工介入标记）→ 对 `API_KEY_LLM` 执行者补发执行命令（`createAssignedCommand(..., "manual-review-rework", ...)`，`:153`）；
- 返工计数：`reworkCount` + `attemptTotal` 双计数，`AgentEventContextResolver.resolveTurn = 1 + reworkCount + attemptTotal`（`AgentEventContextResolver.java:40`）；
- 开工白名单：`ASSIGNED / REWORK / PAUSED → IN_PROGRESS`（`SubTaskCommandPort.java:44`，`McpToolServiceImpl.java:418-422`）；
- **与 AgentTeams 的对应**：helloai 的 `REWORK → 改派` 已等价实现 AgentTeams `revision → re-dispatch` 主体语义，且多了「返工预算上限 + 死信兜底」两级容错——**这部分 helloai 不落后**。

### 1.4 借鉴落点（Fork/Return）

| # | 借鉴项 | 来源 | helloai 落点 |
|---|---|---|---|
| F1 | **转换史收口进 `changeStatus()`**：单一入口 + `history[]`（cap 50）+ `actor` | AgentTeams `_transition_task` | 不新建字段（避免与 `task_timeline` 双事实源），把 `taskTimelineService.recordEvent` 收口进 `changeStatus()`；`rework/reworkFresh` 的扩展语义表达为「基础 + 后置钩子」 |
| F2 | **re-dispatch 保留审计**：退回重派必须**携带原 history**、不得抹轨迹 | AgentTeams `server.py:5390-5403` | helloai `reworkFresh` 已重置计数——**需核对改派时 `task_timeline` 是否保留**（当前 review 驳回路径有 `REVIEW_REJECTED` 落 timeline，基本满足，但建议加断言） |
| F3 | **Fork 驱动执行**（补 helloai 后半截）：fork 快照后，让 `sub_task`/execution command 带 fork run_id 上下文接线 | Octop `fork.py`（新线程可继续） | `AgentEventForkService` 注释已自述方向（ADR-001 §2）；接线 = `SubTaskSnapshot`/execution command 增加 fork run_id |
| F4 | **对话级 Fork（Octop 形态）**：若 helloai 未来要做「从某条消息分叉对话」，Octop 的「读 checkpoint 前缀→新 thread→继承会话上下文」可照抄 | Octop `fork.py` | helloai 事件模型是 `agent_event`（run 级）而非 LangGraph checkpoint——需评估是否值得为对话级分叉引入 thread 抽象 |
| F5 | **report_progress**：长任务 `from==to` 进度记账，note 必填截断，不通知 | AgentTeams `task-transition-engine.md` §4 | helloai 长任务执行期无机器可读进度，可补 `progress` 事件 |

---

## 二、沙箱（报告已覆盖，本报告复核实现细节 + 提炼可直接照抄的形状）

### 2.1 Octop：声明式 spec + 真实探针（**最值得抄的形状**）

**声明式后端 spec**（`src/octop/infra/backend/docker_spec.py:10-29`）：`_DOCKER_PASSTHROUGH_KEYS` 共 17 键——`allow_network / memory / cpus / pids_limit / command_timeout / max_output_bytes / auto_remove / workspace_path / volumes / environment / environment_file / agent_id / container_name / sandbox_scope / sandbox_prefix / username / sandbox_id / previewable`。

**scope 三态**（`docker_spec.py:72-77`）：
- `agent`（默认）：一 Agent 一沙箱，注入 `agent_id`；
- `user`：同用户多专家共用，注入 `username`；
- `fixed`：固定共享（需显式 `sandbox_id`）。

**真实探针**（`probe.py:118-214` `_probe_docker`，写→读→删完整往返）：
1. `ensure_docker_image`（`:159`）确保镜像；
2. `spec["auto_remove"] = True`（`:168`）**探针容器必然一次性**；
3. `backend.write(test_path, _PROBE_CONTENT)`（`:176`）→ `backend.read(test_path)`（`:179`）→ **比对内容**（`:184` `content != _PROBE_CONTENT` 判失败）；
4. `execute(f"rm -f -- {test_path}")`（`:189`）清理；
5. `finally` 块 `destroy()` / `close()` + `shutil.rmtree(workspace)`（`:202-213`）——**失败路径也必须回收**。
**这是「容器能起来 ≠ 沙箱能用」的正确答案**。

**可观测性与隔离二选一**（`docker_spec.py:39-52`）：docker 后端默认**不让 Admin 浏览文件**，仅 `fixed` scope 默认可浏览或显式 `previewable: true`。

### 2.2 AgentTeams：K8s SandboxClaim + Hibernate/Resume 冷启动

**接口**（`agentteams-controller/internal/backend/sandbox/plugin.go:12-53`）：
```go
type SandboxPlugin interface {
    Type() string
    Capabilities(config ProviderConfig) ProviderCapabilities  // = min(MaxCapabilities, config.Capabilities)
    CreateSandboxClaim(...) / DeleteSandboxClaim / DeleteSandbox
    HibernateSandbox(...) / ResumeSandbox(...)   // 不支持时 ErrCapabilityNotSupported
    GetSandboxClaimStatus / GetSandboxStatus / ListSandboxes
    Validate(config) / HealthCheck(ctx, config)
}
```
- **注册表**（`registry.go:16-30`）：按 type 注册，**重复注册 panic**，未注册报错；
- **能力位**（`plugin.go:62-65`）：`ProviderCapabilities{Hibernate bool; Pool bool}`——**声明能力 ≤ 配置能力**（`min(MaxCapabilities, config.Capabilities)`，`plugin.go:18`）；
- **沙箱 ≠ Worker 容器**：SandboxClaim 从 SandboxSet 领一个实例绑定到 Worker（`plugin.go:71-80`），PodSpec 字段归 pod backend 路径（`plugin.go:69-70`）——**Claim（绑定）与 Sandbox（实体）两层分离**；
- **Hibernate/Resume**（`plugin.go:30-36`）：沙箱可休眠/唤醒——这是 K8s 生态的**冷启动优化**（省资源），不是隔离安全（隔离由 CRD/namespace 提供）。

### 2.3 helloai 现状（已核实）——抽象已对、实现为零

`ExecutionPolicy.java`（47 行）：
- **五边界 record**：`filesystem / network / process / resource / credential` × `ISOLATED / PARTIAL / NONE`；
- 注释自述（`:5-10`）：**「当前实现必须诚实标注——平台尚未提供任何真实安全沙箱，环境策略一律 NONE 或天然 PARTIAL，不得标记 ISOLATED」**；
- 两个静态工厂：`noIsolation()`（local-process）、`remoteTerminal()`（网络天然 PARTIAL）；
- `SandboxProvider` 唯一实现 `EnvironmentSandboxProvider` 只做环境路由（53 行）。

### 2.4 借鉴落点（沙箱）——与报告 S1~S5 一致，补两条

报告建议 S1~S5（DockerPolicy/BubblewrapPolicy 静态工厂 → SandboxSpec 声明式 → DockerSandboxProvider → probe → scope/生命周期）。**我完全同意**，补充：

| # | 借鉴项 | 来源 | 说明 |
|---|---|---|---|
| S-a | **写→读→删真实探针**（不是 `docker ps`） | Octop `probe.py:118-214` | helloai 的执行体健康检查/`AgentSelector` 可照抄「真实业务往返 + 失败必回收」 |
| S-b | **scope（agent/user/fixed）+ 容器不自动销毁、显式回收** | Octop `docker_spec.py:72-77` + 生命周期口径 | 对齐 `Sandbox_Provider.md` 实施原则 4「安全沙箱引入必须有权限与资源策略，不是只加一个 Docker 类」 |
| S-c | **Capabilities = min(声明, 配置) + 能力位** | AgentTeams `plugin.go:18,62-65` | helloai `ExecutionPolicy` 可加「能力声明 ≤ 配置」口径 |
| S-d | **探针可观测性登记** | 综合 | 沙箱探针结果要落 timeline/事件，否则「容器起来了但 agent 用不了」用户不可见 |

---

## 三、外部 AI agent 对接（报告落在 connector 模式；本报告补「回叫语义」与「helloai 已有等价物」）

### 3.1 Octop：三模式连接器 + 失败必须回叫

**三模式连接器**（`src/octop/infra/connectors/catalog.py`，26 条目录）：`remote`（harness 直连厂商 MCP URL）/ `gateway`（进程内写适配器把无 MCP 的服务包成 MCP）/ `internal`（自托管 HTTP MCP）。适配器契约仅三方法（`gateway/registry.py:22-30`）：
```python
class GatewayAdapter(Protocol):
    def list_tools(self) -> list[dict[str, Any]]: ...
    def call_tool(self, creds: dict, name: str, args: dict) -> str: ...
    def probe_credentials(self, creds: dict) -> None: ...   # 自检
```

**失败必须回叫**（`infra/agents/teams/team_manager.py:279` `on_reply`）：
```python
text = (event.error_text or "Background task did not complete."
        if event.status != "done"
        else (event.reply_text or "(empty)"))
```
- inbox worker **失败时仍然调用 `on_reply(status=failed, error_text=…)`**，让 source agent 给用户兜底说明（`docs/agent-interop-mailbox.md:148`）；
- `team_manager.py:284`：`event.error_text or "Background task did not complete."` ——**失败也有用户可见的兜底文案**；
- `docs/expert-teams.md:30`：「对方没运行则派工失败，主持人权走失败回叫，**不自动启动**」——平台不替用户拉起未运行执行体。

### 3.2 AgentTeams：MCP 分发表 + 房间消息驱动

**工具分发表**（`plugins/teamharness/mcp/server.py:610-645`）：`call_tool(name, args)` 一入口 → `if name == ... elif name == ...` 分发到 `message / roomflow / filesync / artifact / projectflow / taskflow` 六个大工具，每个大工具再按 `action` 路由子命令（如 `taskflow` 的 `delegate_task / ack_task / submit_task / accept_task_result / cancel_task / report_progress`）。**工具面 = MCP，协作面 = Matrix 房间**：
- 派发：Leader 调 `taskflow/delegate_task` → 校验成员房间资格（`server.py:4727` `_validate_assignee_membership`，**严格 `join` 不是 `invite`**）→ 发布 spec 到 shared storage → **同步成功才发 Matrix 通知**；
- Worker 领活：MCP 轮询 / 房间消息；
- **外部 agent 形态**：`deepseek-harness/` 把 DSH 包成 Worker——Matrix 房间驱动、**每房间一个 DSH 会话**、bridge state 持久化（`deepseek-harness/README.md:20-24`）。

### 3.3 helloai 现状（已核实）——REST/MCP 领活 + 心跳铁律

- 外部 agent 通道：`Authorization: Bearer` → `AgentAuthPort.validateApiKey`（401/403），**不校验心跳**；
- MCP 通道：`pullTasks/ack/claimSubTask/...`，13/13 工具强制覆写 `agentId`（`McpMcpServer.java`）；
- 心跳铁律：外部 agent 必须心跳在线才可被分派、禁止模拟心跳、禁止另注册（工作记忆登记）。

### 3.4 借鉴落点（外部 agent 对接）

| # | 借鉴项 | 来源 | helloai 落点 |
|---|---|---|---|
| E1 | **「派工失败 ⇒ 必须产出面向用户的说明」定为不变量** | Octop `team_manager.py:284` + 报告 §2.3 | `ResilientDispatcher.doAssignNextFallback` 只 `log.warn` 不落 timeline——改成「失败必须回叫（timeline + 可读原因）」 |
| E2 | **探活区分两类问题**：①对象在、连接死 → 探活可解；②对象在别的 JVM → 探活无解、必须外置状态 | Octop `harness.py:43-60` + 报告 §5.2c | helloai `SESSION_AUTH` 属②，用探活解决不了；登记为「多实例部署前置条件」 |
| E3 | **连接器三方法（list/call/probe）+ 鉴权声明化** | Octop `gateway/registry.py:22-30` | helloai 只有 MCP Server，缺 Client/适配器侧；`probe_credentials` 是契约一部分（「配好了必须能被机器验证」） |
| E4 | **stable txn + sync-first, then notify** | AgentTeams `server.py:5390` + 报告 §4.3 | helloai outbox 已有 status 幂等；核对「产物先落盘再发通知」顺序 |

---

## 四、RAG 知识库（报告已覆盖；本报告核实存储层细节）

### 4.1 Octop 完整链路（`src/octop/infra/knowledge/`，17 文件 2275 行）

| 环节 | 实现 | 关键点 |
|---|---|---|
| 分块 | `chunk.py`（21 行） | 极简 |
| 解析 | `parse.py`（307 行） | 文档解析 |
| OCR | `ocr.py`（341 行） | 图片/PDF 文本提取 |
| Embedding | `embed.py`（70 行） | **默认本地 ONNX**（`onnx_service.embed_texts`），可切远程 OpenAI 兼容 API（批量 ≤20）；`backend ∈ {onnx, remote}` |
| 存储 | `index.py`（134 行） | **每知识库一个 SQLite 侧库**：`chunks(chunk_id, doc_id, ordinal, text, embedding BLOB, meta_json)`，embedding 用 `struct.pack("<Nf")` 存 float 数组 |
| 检索 | `index.py:98-134` | **进程内余弦相似度**：读出全表 → 逐行算 cos → 排序取 top-k（**无 ANN 索引**，SQLite 全扫——小规模够用，大数据量是瓶颈） |
| 注入 | `retrieve.py:22-53` | `retrieve_context(k=8, char_budget=6000)`，`run_in_executor` 跑阻塞 I/O |
| 引用 | `citations.py:10-66` | 检索结果尾部追加 HTML 注释 marker，前端渲染引用卡片、喂模型前剥掉 |
| 闸门 | `gate.py:63-90` | `feature_enabled && prerequisites_ok = usable`；`hint.py:94-99` **无 KB 即从工具列表摘掉 `search_knowledge`** |

### 4.2 helloai 现状：**完全空白**

`grep -rniE "pgvector|embedding|knowledge_base|知识库|vector|retriev" helloai-core/src/main helloai-api/src/main` → **零命中**。无任何 RAG/向量/知识库基础设施。

### 4.3 借鉴落点（RAG）

| # | 借鉴项 | 来源 | helloai 落点 |
|---|---|---|---|
| R1 | **「什么不许进上下文」先于检索**：无 KB 即摘工具、有 KB 每轮重写工具描述注入可见目录 | Octop `hint.py:67-99` | helloai `ToolRegistry` 补「按条件可用」「按上下文动态描述」语义位（与报告 §2.6 一致） |
| R2 | **注入预算显式化**：`char_budget=6000` + 检索在 executor 跑 | Octop `retrieve.py:22-53` | helloai 成本选人（近 5 次 token 均值）是预算契约的上游，可一起设计 |
| R3 | **引用 marker：机器读 marker、人读正文、模型读纯净正文** | Octop `citations.py` | 报告/子任务结果/核验意见如需可点开引用来源，套此形状 |
| R4 | **存储选型**：Octop 用 SQLite 侧库（小规模够用）→ **helloai 应直接用 pgvector**（PG 单后端是优势，不引入第二个 DB） | 综合 | 报告 §10.3 C1 建议 pgvector，同意；Octop 的 SQLite 侧库形态不抄 |

---

## 五、与两份报告的一致性裁定

### 5.1 完全一致的观点（本报告独立分析得出相同结论）

| 报告观点 | 我的独立分析 | 一致点 |
|---|---|---|
| Octop 综合版 §0.1「Octop 对 helloai 没有一个字节可以搬，但横切工程可照抄」 | 核实属实：Octop 单进程/双后端/进程内调度与 helloai 路线相反；但**探针、回叫、能力摘除、载荷旁路**的形状确实可直接照抄 | ✅ 一致 |
| 「**载荷旁路是唯一正在真实丢数据的地方**」（A1） | `UpstreamAttachmentRenderer`（113 行）确实按预算截断、第二及以后附件全文进不了模型视野；Octop `octop_ui_offload.py` 的「数据面/控制面分离 + ref」确实是对的解 | ✅ 一致 |
| 「沙箱 spec 声明化 → 探针 → 实现」（B1） | `ExecutionPolicy` 五边界与 Octop docker spec 17 键**逐项映射**（filesystem↔workspace_path/volumes、network↔allow_network 默认 false、process↔pids_limit、resource↔memory/cpus、credential↔env 白名单）；探针形状可直接照抄 | ✅ 一致 |
| 「**RAG 先定边界再检索**」（§2.6/§2.7） | `hint.py:94-99`「无 KB 即从工具列表摘掉」与 `retrieve.py char_budget` 确实是「先定什么不许进上下文」的实现 | ✅ 一致 |
| 「**失败必须回叫，平台不替用户拉起未运行执行体**」（§2.3） | `team_manager.py:284` 失败兜底文案 + `expert-teams.md:30` 不自动启动，核实属实；helloai `ResilientDispatcher.doAssignNextFallback` 确实只 log.warn | ✅ 一致 |
| 「**先抄功能，别动架构**」（§12） | 核实：当前只有「多实例部署前置条件」（`SESSION_AUTH` 进程级注册表）是真实未来债，其余建议都不需动架构 | ✅ 一致 |
| 「身份从调用上下文取，不依赖客户端自报」 | `browser_profile.py` 的 fail-closed 覆写 vs helloai 13 处 `requireAuthId`——两侧都已在做，方向一致 | ✅ 一致 |

### 5.2 分歧 / 补充（本报告独立分析得出，报告未覆盖或判断不同）

| # | 报告观点 | 我的独立分析 | 裁定 |
|---|---|---|---|
| ★D1 | **两份报告几乎没覆盖 Fork/Return 回退**：AgentTeams 报告只在 §4.1 把 `REVISION_NEEDED` 当状态机枚举提了一句；Octop 报告的 `fork.py` 全文未读 | **这是用户点名关心的能力，且实现最具体**：AgentTeams 的「revision → re-dispatch 保留审计」、Octop 的「checkpoint 前缀复制到新线程」都值得专门一节（见 §1） | **报告重大遗漏**，本报告 §1 补齐 |
| ★D2 | AgentTeams 报告 §4.1 批评 helloai「状态转换散落、无历史」，把任务转换引擎列为最高价值 A1 | 核实属实（12 处 `updateById`、3 个独立 inbox 通知实现）；**但须注意**：helloai 的 `REWORK→改派` 已实现 revision 主体语义，真正缺的是**转换史收口**（F1）而非退回能力本身 | 一致但需定位：缺的是「可审计收口」不是「退回能力」 |
| ★D3 | Octop 报告把「外部 agent 对接」主要落在 connector 三模式 / MCP 网关适配器（C8/D 档） | 对 helloai 而言**更优先的是「失败回叫闭环」**（E1）——`doAssignNextFallback` 只 log.warn 是用户能感知的真实缺口；连接器三模式是「将来要接多家 SaaS」时才需要 | 报告低估回叫语义优先级，本报告 E1 上提 |
| ★D4 | Octop 报告 RAG 部分未提「helloai 应直接用 pgvector 而非照抄 SQLite 侧库」 | Octop 的 SQLite 全扫余弦（`index.py:98-134`，无 ANN）**小规模够用，大规模是瓶颈**；helloai 是 PG 单后端优势，应直接用 pgvector | 报告 C1 提了 pgvector，本报告补「不抄 SQLite 侧库形态」的明确理由 |

### 5.3 一句话收尾

两份报告的大方向**全部经得起源码复核**——「先抄功能、别动架构」「载荷旁路」「沙箱 spec+探针」「RAG 先定边界」「失败必须回叫」全部成立。**唯一的重大遗漏是 Fork/Return 回退**：两个项目都把它做成了具体可借鉴的形态（AgentTeams 任务级 revision+审计史、Octop 对话级 checkpoint fork），而 helloai 的 `AgentEventForkService` 恰恰停在「快照已做、驱动执行后置」——**这正是用户想补的那半截**。

---

## 六、给 helloai 的落地优先级建议（按 ROI 排序）

1. **F3｜Fork 驱动执行接线**（补 helloai 后半截）：`AgentEventForkService` + `sub_task`/execution command 带 fork run_id → fork 后的 Run 能真的跑起来。改动集中、直接命中用户心愿。
2. **E1｜派工失败回叫不变量**：`ResilientDispatcher` 失败必落 timeline + 可读原因；人工兜底池（`nc-fallback-*`）接入 attention 语义。低风险高感知。
3. **S1~S5｜沙箱 spec 声明化 + 探针**：先做 spec 解析与探针（不真起 Docker），对齐 `Sandbox_Provider.md`；探针形状照抄 Octop `probe.py`。
4. **F1｜转换史收口**：`changeStatus()` 统一扇出 + 越序错误带「下一步引导」；不动 `history` 字段（用 timeline）。
5. **R1~R4｜RAG 知识库（pgvector）**：先定「无 KB 即摘工具 + 注入预算」，再谈存储与检索；引用 marker 最后补。

> **硬约束**（与项目红线对齐）：① 技能的脚本要在沙箱里跑 ⇒ 技能化推进到「可执行」依赖沙箱完成；② `SESSION_AUTH` 进程级注册表必须先于多实例部署解决（故障现象像鉴权 bug，排查成本极高）。
