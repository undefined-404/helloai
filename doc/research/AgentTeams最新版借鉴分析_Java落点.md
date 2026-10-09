# AgentTeams 最新版借鉴分析 —— 对照 helloai 的 Java 落点

> **分析对象**：`E:\workspace\AgentTeams-main`（原 HiClaw，`agentscope-ai/AgentTeams`），changelog 覆盖 `v1.0.1` → `v1.2.2`（16 个版本）。  
> **对照基线**：`doc/archive/reference/HelloAI_外部项目借鉴技术细节.md` §1（**5 月版**已借鉴的 7 项）。  
> **配套报告**：`doc/research/Octop全库借鉴分析_综合版.md`（Octop 全库；旧版 `Octop全库借鉴分析_Java落点.md` 已并入该综合版，2026-10-09）。  
> **勘察口径（2026-10-09 按《治理规则》§3.6 统一为标记式）**：凡标 `[实测]` 的论断均由本地逐文件读取源码/文档得出并附 `路径:行号`；标 `[推断]` 的为**文档口径反推**（未能读源码，已单列说明）；**未核实的写 `[未核实]`，不用「通常/一般」充当事实**。§八 事实核查表逐条标注了本报告的勘察口径。
> **路径基准**：对 AgentTeams 的路径引用以仓库根为基准（Go 侧 `agentteams-controller/internal/...`，Python 侧 `plugins/teamharness/...`，设计文档 `docs/design/...`）；helloai 侧现状均逐文件核实。

---

## 一、结论速览

**定位判断**：AgentTeams 已从「5 月版的多 Agent 协作调度系统」**演化为「K8s 原生多 Agent 协作编排平台」**——`agentteams-controller`（Go operator + 4 个 CRD）、Higress AI Gateway、Matrix/Tuwunel 协作层、MinIO 共享状态、多 runtime（openclaw / qwenpaw / hermes / deepseek-harness / openhuman）。**与 helloai（Java 单集群任务编排平台）的定位差异比 5 月版更大**，因此本轮必须把「**可迁移的架构思维**」从「**K8s/Matrix 特定实现**」里剥出来。

**本轮最高价值 5 项（A 档，均直接命中 helloai 已知缺口）**：

| #      | 借鉴项                                                                                          | 命中 helloai 的                                                               |
| ------ | -------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| **A1** | **任务转换引擎**：一张表 + 一个入口 + 一条可审计历史                                                              | `SubTaskStatus` 有 **12 个 `updateById` 写入点**、只有 1 处做完整扇出；实体**无状态变更史**（§4.1） |
| **A3** | **sync-first, then notify**：通知是注意力信号不是回执                                                     | `changeStatus()` 的 outbox/inbox 扇出与产物落盘**先后顺序未受约束**（§4.3）                  |
| **A5** | **审计事件是「闭合 schema」**——没有自由文本字段可以藏凭据                                                          | 只有凭据域 `CredentialAuditLog`，**无通用审计**（§4.5）                                 |
| **A6** | **水位（watermark）四判据**：一 marker 不得两语义 / 失败不推进 / 不得事后 touch / 检测范围=推送范围                         | MinIO 附件与工作区同步、`UpstreamAttachmentRenderer`（§4.6）                          |
| **A8** | **能力声明式可插拔**：`Capabilities = min(声明上限, 配置)` + `ErrCapabilityNotSupported` + Hibernate/Resume | **RM10 Sandbox 是 helloai 唯一未动项**（用户决策最后做）（§4.8）                            |

**5 月版 7 项的演进终局**（§3）：7 项中 **3 项已落地并收敛**、**3 项被更抽象的机制替换**（尤其 `.processing` **锁文件 → 稳定事务键 + 幂等复用**）、**1 项被 K8s 原生化**。

**明确不抄**（§6）：CRD/Helm/leader-election、Matrix 房间与 power level、Higress Consumer Token、多 runtime 镜像矩阵、`agt` CLI 分发。

---

## 二、勘察边界与不可核实项

**已核实（一手读取）**：

- 文档：`AGENTS.md`、`docs/design/architecture.md`、`docs/design/k8s-native-orchestration.md`、`docs/design/{task-completion-notification,room-power-levels,capability-foundation,member-runtime-config-contract,l2-worker-scoped-write,l3-worker-scoped-read,audit-events-api,skill-catalog-api,team-skills}.md`、`docs/design/teamharness/{task-transition-engine,boundary-and-contracts}.md`、`docs/design/internal/issue-1107-file-sync-io-amplification.md`、`changelog/v1.1.0.md`、`v1.2.0.md`、`v1.2.1.md`
- 代码：`agentteams-controller/internal/auth/{capability.go,authorizer.go}`、`internal/backend/{interface.go,sandbox/{plugin.go,registry.go,openkruise.go}}`、`internal/accessresolver/{defaults.go,resolver.go}`、`internal/workflow/mermaid.go`、包规模统计

**不可核实（显式标注）**：

- 运行中的 `plugins/teamharness/mcp/server.py` 的 `_transition_task()` 完整实现——只有文档描述（`task-transition-engine.md` 是设计文档，标注了 `TRANSITIONS` / `TERMINAL_TASK_STATUSES` 常量位置但未给全源码）。**本文涉及该实现的结论均为「文档声称」口径**。
- `deepseek-harness` runtime 内部（17 个文件，未读）——`skill-catalog-api.md` 已自述「dsh runtime 从镜像内 plugin manifest 准备技能，`runtime.yaml` 的 skill 段对它是 no-op」。
- K8s 实测行为（本机无集群）——`k8s-native-orchestration.md` 与 `room-power-levels.md` 的 homeserver 规则均为文档口径，但文档内附了「fake 强制执行同规则」的测试证据，可信度较高。

---

## 三、5 月版 7 项的演进对照（用户明确关注）

参照 `doc/archive/reference/HelloAI_外部项目借鉴技术细节.md` §1.1~§1.7。

### §1.1 调度分离 → **已落地并收敛，新增「reconciler 幂等修复」家族**

新版把「调度」彻底交给了 K8s 控制循环：`internal/controller/`（38 文件）对 Worker/Team/Manager/Human 四个 CR 做 reconcile。真正可迁移的**不是** operator，而是其中被反复打磨出的**幂等修复判据**（`room-power-levels.md` 第 3~7 条）：

- **观察到的对象也要修复（healing path）**：reconciler 不只处理新对象，**对已观察对象同样执行「应有状态」修复** ⇒ 遗留数据在首次 reconcile 后**自愈，无需手工回填**。原文：「It now additionally ensures the human's power level in *every* desired room — new and already-observed. The observed-room pass is the healing path.」
- **稳态零写入**：`EnsureRoomPowerLevel` 先读当前态，**只有值真的变了才写** ⇒ 「Steady state = one GET per room per cycle, zero writes.」
- **失败不得吞成幂等成功**：`KickFromRoomWithToken` 曾把 403 `cannot kick` 当成功吞掉 ⇒「房间被移出 `status.rooms` 但用户仍在房里」。修复后**只有 404/not-in-room 才是幂等，其余 403 必须作为可解码错误暴露**。
- **归属不可解析时不得执行破坏性操作**：`teamRoomRevocationLag` 检测「身份对象已写入 `status`，但归属对象尚未在缓存可见」的窗口，**延迟一拍（defer one cycle）** 再判，而不是按「未知归属」直接踢人。原文把不这么做导致的后果写得很具体：**永久死锁**（`M_FORBIDDEN: cannot join a room that is not public` 每轮 reconcile 重复失败）。
- **缓存不得短路「存活探测」路径**：`TestEnsureUser_OrphanRecovery_IgnoresStaleCachedToken` —— 登录缓存 30 分钟，但**兼作账户存活检查的登录必须绕过缓存**，否则「缓存里的死 token 会短路孤儿恢复流程」。
- **代价放到异常路径**：等权降级时「**lazily** fetch self token, only after 403」⇒ 稳态**零 Matrix Login**。

> **对 helloai 的落点**：这 5 条判据与 `WatchdogLeaseRenewTask` / `LeaseReconcilerTask` / 重派退避 / `resetToPendingForDispatch` 的**幂等与竞态处理**高度同构。「归属不可解析 ⇒ 延迟一拍」尤其对口「重派前先判 `evaluateReassignGate`」这类前置闸门。**建议登记为可复用判据**，而非当作 AgentTeams 特有内容丢弃。

### §1.2 `state.json` 单一事实源 → **演化为「CRD 权威 + 派生快照 + additive 历史」**

新版的事实源分三层，且**每一层的边界都写进了契约**：

| 层     | 载体                                           | 契约要点                                                                                              |
| ----- | -------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| 权威期望态 | CRD `spec` / `status`                        | `Team.spec.workerMembers` 引用独立 Worker CR（v1.2 破坏性变更，不再支持内联成员）                                     |
| 派生消费态 | `shared/runtime/members/{name}/runtime.yaml` | `member-runtime-config-contract.md`：**只放非机密期望态**；`credentials` 段存的是「**从哪个 env / 文件路径读密钥**」，绝不是密钥值 |
| 局部事实  | `shared/tasks/{id}/meta.json`                | **新增 `history: []`（additive）**——本次演进的实质                                                           |

**最有价值的一条**：`MemberRuntimeConfig` 的 `metadata.generation` 用于**检测配置是否变更**（避免全量比对），并明确「AgentSpec 包 ≠ 插件包」——**「用户部署的 agent 模板与业务能力包」和「运行时基础设施」生命周期不同，不得建模为同一物**。

> **对 helloai 的落点**：helloai 的 agent 配置下发（`AgentRuntimeContextAssembler`）与 `AgentSkillSpec` 的「规范 vs 实现」边界，可直接套用「**配置快照只放密钥位置不放密钥值**」+「**generation 检测变更**」+「**模板包 ≠ 基础设施包**」三条。

### §1.3 Heartbeat 七步主动巡检 → **保留，且升级为「事件化的注意力模型」**

`prompts/manager/HEARTBEAT.md` 仍在标准资产清单内（`boundary-and-contracts.md:82`）。真正的演进是 **`task-completion-notification.md` v2** 把「心跳巡检」升级为**完整生命周期注意力模型**（详见 §4.3 / §4.4）。核心变化：**从「LLM 自己记得去喊」改为「代码生成契约行」**——原文诊断 v1 的失败机制是「Worker 在多轮会话 + context compaction 后**忘记了那一行**」。

### §1.4 `.processing` 工作区协调锁 → **被「稳定事务键 + 幂等复用」替换（最有信息量的一项演进）**

新版几乎看不到「锁文件」语义，取而代之的是两条：

1. **稳定事务键（stable txn）**：`delegate-<task-id>` / `submit-<task-id>-<status>` / `attention-<task-id>-<kind>-<attempt>` / `project-<project-id>-success`。Matrix 侧对**同 txn 的重投自动去重**；应用侧持久化 `eventId` 并在重试时**复用已记录的事件**。
2. **原子性靠「状态机 + 单入口」而非锁**：`delegate_task` 的原子顺序被明确写成「validate assignee room membership（**严格 `join`，不是 `invite`**）→ prepare → stable-txn notification → commit `assigned` + `event_id`」（`changelog/v1.2.1.md`）。

> **判据**：**「协调」可以用「幂等键 + 显式状态提交」表达，不一定要用「分布式锁」**。这比加锁更抗失败（不依赖锁的释放语义），但要求**幂等键必须 status-scoped**（见 §4.3 的陷阱）。

### §1.5 任务恢复流（`task-history.json` + `progress/`）→ **收敛为 `history[]` + `report_progress` + `GET /events`**

即本轮 `task-transition-engine.md` 的全部内容，见 §4.1。

### §1.6 Team 委托模型 → **三级组织 + 「委派边界」显式化**

新架构（`k8s-native-orchestration.md`）：Admin(human) → Manager(AI) → **Team Leader（仍是 Worker，只是 SOUL/skills 不同）** → Worker。关键新增不是层级本身，而是：

- **Manager 不穿透 Team**：Manager 只与 Team Leader 对话，**不直接指挥队内 Worker**。
- **房间拓扑即权限**：Leader Room（Manager + Admin + Leader）/ Team Room（**Manager 不在**）/ Worker Room / Leader DM。`changelog/v1.2.0.md` 还专门修了「remove Manager from regular Team Worker personal rooms」。
- **Human 三级权限**：`permissionLevel 1|2|3` + `accessibleTeams` / `accessibleWorkers`（见 §4.7）。

### §1.7 Worker 生命周期管理 → **声明式 `spec.state` + 后端可插拔 + 容器可随意重建**

- **声明式生命周期**：`spec.state: running | stopped`，`worker sleep/wake` 只是它的 CLI 糖；**Manager 用 `state` 做空闲超时自动休眠与按需唤醒**。
- **Worker 无状态（容器边界）**：「Workers are stateless at the container edge」——配置从 MinIO 拉取，容器可销毁重建。
- **后端可插拔**：`WorkerBackend` 接口，Docker/K8s 两实现共享同一 reconciler，**只换 driver**（类比 CRI/CSI/CNI）。

---

## 四、A 档：直接命中 helloai 已知缺口的借鉴项

### 4.1 A1｜任务转换引擎（Task Transition Engine）—— 本轮最高价值

**AgentTeams 的诊断（原文）**：

> 状态转换散落在 `delegate_task` / `ack_task` / `submit_task` / `accept_task_result` / `cancel_task` **五个调用点**，各自的手写守卫宽严不一（`planned→in_progress`、`planned→submitted` 都可达，`accept` 无来源守卫）。  
> 转换无历史：谁、什么时间、从什么状态改到什么状态，task meta 里查不到。  
> `accept_task_result` 只写 project meta，**task meta 与节点状态可永久分叉**。

**helloai 实测对照**（这是本文最需要用户看的一段；**逐行核实，含方法名与行号**）：

| 观察项                            | 实测结果                                                                                                                         |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| 状态机                            | **有**：`helloai-core/.../task/statemachine/SubTaskStateMachine.java`，`EnumMap<SubTaskStatus, Set<SubTaskStatus>> TRANSITIONS` |
| 转换表载体                          | **仅 Java 代码内联**（`static {}` 块），**无外部 fixture、无跨侧一致性断言、无前端共用**                                                                |
| `updateById` 写入点               | **12 处**：`SubTaskServiceImpl.java:192 / 200 / 368 / 462 / 809 / 901 / 987 / 1090 / 1110 / 1147 / 1407 / 1497`                |
| `setStatus` 写入点                | **8 处**：`:199 / 353 / 420 / 804 / 878 / 1088 / 1143 / 1512`                                                                  |
| `SubTaskStateMachine.validate` | 仅 **5 处**：`:345`（`changeStatus`）、`:419`（`complete`）、`:794`（`rework`）、`:875`（`reworkFresh`）、`:1140`（`reclaimExpiredLeases`）   |
| **缺 `validate` 的转换**           | **`:1088`（`resetToPendingForDispatch`）——仅 allowlist 检查，无 `validate`**                                                        |
| 状态变更史                          | **实体无 `history` 字段**（`SubTask.java` 字段清单实读确认，只有 `status` / `reworkCount` / `attemptTotal` / `lastAttemptTime`）               |

**扇出（outbox / inbox / handover / assignment event / timeline）在各路径的覆盖情况——「同一件事被手写实现 4 次，每次都不同」**：

| 路径                                | 行号           |  validate |  outbox  |                   inbox                  | handover | assignment event |        timeline        |
| --------------------------------- | ------------ | :-------: | :------: | :--------------------------------------: | :------: | :--------------: | :--------------------: |
| `changeStatus()`                  | `:330-381`   |  ✅ `:345` | ✅ `:373` |     ✅ `sendInboxNotification` `:376`     | ✅ `:378` |     ✅ `:379`     |         ❌ **无**        |
| `complete()`                      | `:415-473`   |  ✅ `:419` | ✅ `:463` | ✅ `sendApprovedInboxNotification` `:466` |     ❌    |         ❌        |         ❌ **无**        |
| `rework()`                        | `:792-825`   |  ✅ `:794` | ✅ `:815` |  ✅ `sendReworkInboxNotification` `:814`  | ✅ `:811` |         ❌        |    ✅ `:852`（仅预算耗尽分支）   |
| `reworkFresh()`                   | `:869-918`   |  ✅ `:875` | ✅ `:906` |  ✅ `sendReworkInboxNotification` `:905`  | ✅ `:903` |         ❌        |        ✅ `:913`        |
| **`resetToPendingForDispatch()`** | `:1079-1094` |     ❌     |     ❌    |                     ❌                    |     ❌    |         ❌        |        ❌ **全零**        |
| `reclaimExpiredLeases()`          | `:1125-1180` | ✅ `:1140` |     ❌    |                     ❌                    |     ❌    |         ❌        |    ✅ `:1155` `:1173`   |
| `renewCurrentNodeLeases()`        | `:1104-1120` |     —     |     —    |                     —                    |     —    |         —        | — （**只续租约、不改状态**，不属转换） |

**三个可直接指认的结论**：

1. **入站通知有 3 个各自独立的实现**（`sendInboxNotification` `:629` / `sendApprovedInboxNotification` `:580` / `sendReworkInboxNotification` `:491`），**没有共用入口**——即 AgentTeams 批评的「各自的手写守卫宽严不一」。
2. **`changeStatus()` 与 `complete()` 都不写 timeline** —— 恰好是**最主要的两条路径**没有状态变更史。而 `rework`/`reworkFresh`/`reclaimExpiredLeases` 反而写了 timeline。**这让「谁、何时、从什么状态到什么状态、为什么」在主链路上反而是空白。**
3. **`resetToPendingForDispatch()`（`:1079-1094`）是唯一的「零覆盖」路径**：`setStatus(PENDING)` + `setAssignedAgentId(null)` + `updateById`，**既无 `validate`、又无 outbox、又无 timeline**。`setAssignedAgentId(null)` 意味着**原执行者不会收到任何「任务已不在你名下」的信号**（对比 `changeStatus()` 有 `notifyAgentHandover`）。**这一条建议优先核实调用方是否在外层补了扇出**（`grep` 显示 `SubTaskServiceImpl` 内无扇出，需确认服务外调用点）。

**逐条可借鉴**：

1. **单一入口 `_transition_task()`**：所有状态变更必经「转换表校验 → 写 status → 追加 history → 同批同步（task meta 与项目节点）」。  
   **→ helloai 适配建议**：**不要新建 `history` 字段**（会与 `task_timeline` 形成双事实源）。正确做法是**把 `taskTimelineService.recordEvent` 的调用收口进 `changeStatus()`**，并让其余写入点改为调用 `changeStatus()`。**但要注意 `rework` / `reworkFresh` 带有额外语义**（共享预算消费 `consumeReworkBudget`、`recordReworkStartedSafely`、`invalidateAttachmentsOnRework`），**不能简单替换**，应表达为「`changeStatus()` 为基础 + 显式后置钩子」，让**基础扇出永不遗漏、扩展语义各归其位**。这是本次分析里**最具体、最好落地**的一条。
2. **`history` 条目格式**：`{ts, from, to, action, actor, note}`，`actor = role:account`；**同状态重入（幂等重试）不记条目**；`cancel` 的 reason 记入 `note`；**cap 50，丢最旧**。
3. **越序转换返回结构化错误并引导正确动作**：错误消息形如 `submit_task: task is 'planned'; ack_task it first`。  
   **→ helloai 现状**：`SubTaskStateMachine.validate` 只抛 `"非法状态转换: X -> Y"`，**不含「应该先做什么」**。补这一层几乎零成本，**对 AI agent 消费者价值极高**（agent 拿到可执行的下一步而不是死胡同）。
4. **「表是权威集合，其上叠若干处收紧」分离记录**：AgentTeams 用一张表列出 3 处收紧（`ack_task` / `submit_task` / `accept_task_result` 的来源守卫），并对照「原先」行为。**→ helloai 的 `TRANSITIONS` 注释里已写了不少「为什么」，但缺少「表 vs 收紧」的结构化分离**。
5. **`report_progress`（新 action）**：`from == to` 的 history 条目（`action: progress`），**note 必填 ≤200 字符，超长截断并在响应标记 `truncated`**；**不改状态、不发房间通知**（v1 防噪音）；状态门 `from ∈ {assigned, in_progress}`。  
   **→ helloai 现状**：长任务执行期**没有机器可读的进度记录**（只能靠 timeline 的零星事件）。这条直接补这个洞，且「**不通知、只记账**」的取舍很清醒。
6. **跨语言单一事实源**：Python 写侧与 Go 读侧**加载同一份 `task-transitions.json`** 并在测试期断言一致。  
   **→ helloai 已有 `helloai-ui`**，把 `TRANSITIONS` 外置为资源文件后，**前端可复用同一份表渲染状态图**（对照 `utils/sequenceFlow.ts`），后端测试断言「表 ↔ 枚举 ↔ 前端映射」三者一致。**低成本、消除一类漂移**。
7. **读侧聚合端点**：`GET /api/v1/projects/{id}/events?limit=&cursor=`，**读时聚合**项目内全部任务 history 成升序时间线，**零新存储、无写侧钩子**（`history_seq` 由既有 task meta 写入路径顺带持久化）。  
   **→ 判据**：**「只读视图不引入新的写入侧钩子」**——聚合放在读侧，序号随既有写入顺带持久化。这对 helloai 的 timeline 查询与 AG-UI 事件流很有参考价值（详见 §4.14）。


**边界（AgentTeams v1 明确不做，值得照抄的克制）**：项目级干预事件不入本时间线（由 `/history` 快照端点覆盖，两者互补）；**SSE 推送不做**（客户端轮询即可）；`record_loop_iteration` **不改任务状态 ⇒ 不入 history**（「循环记账 ≠ 节点转换」）。

### 4.2 A2｜把「转换表」外置为可断言的单一事实源

见 §4.1 第 6 条。补充 AgentTeams 的做法细节：`plugins/teamharness/contracts/task-transitions.json`，`states` / `terminal` / `transitions` 三段；**Go 侧测试直接加载该文件并与 `isTerminalTaskStatus` 断言一致**。验证用例包含「golden fixture 一致性 / 合法转换全链路（history 链完整性 / actor 格式 / RFC3339 时间戳）/ 越序拒绝（含错误消息引导）/ 同状态重入幂等 / cap 丢最旧 / accept 来源守卫 + task meta 同步 / cancel 留痕 / `report_progress` 全部分支」。**这份用例清单可直接作为 helloai 补测的清单**。

### 4.3 A3｜sync-first, then notify ——「通知是注意力信号，不是回执」

**AgentTeams 的设计**（`task-completion-notification.md`，P0 ordering）：

> The submit sequence is: local state → publish artifacts → **sync shared storage → only then notify**.  
> **Sync failure** → `ok: false`, `retryable: true`, **no notification at all**. The local task state is already `submitted`, so the retry is idempotent: the event is sent **exactly once**, on the first sync that succeeds. **This closes the "leader told done, artifacts unreachable" window** — the leader cannot be woken by an event whose artifacts it cannot read.  
> **Notification-level failure**（无房间/无 leader/无 Matrix env/成员校验失败/HTTP 错误）stays **best-effort**：返回 `{"sent": false, "skipped"?: true, "error": "..."}`，submit 仍 `ok: true`。

**两条可直接搬的判据**：

1. **「不得发出接收方无法消费的通知」**——文案极准：*the leader cannot be woken by an event whose artifacts it cannot read*。**失败模式要按「重试是否幂等」二分**：同步失败 = retryable（且**扣住通知**）；通知失败 = best-effort（**不阻断主链路**）。
2. **幂等键必须 status-scoped**：`submit-<task-id>-<status>`。原文给了反例动机：
   > a resubmit with a **changed** status invalidates the recorded pair and sends a fresh event (different txn), **so a worker that first reports `BLOCKED` and later `SUCCESS` wakes the leader again instead of being silently absorbed by the reuse branch.**  
   > **→ 陷阱**：若幂等键只用 `<task-id>`，「先报 BLOCKED 后报 SUCCESS」会被复用分支**静默吞掉**，leader 永远等不到成功信号。**helloai 的 `agentOutboxService.createEvent(snapshot, newStatus)` 已有 status 入参，值得核对幂等/去重键是否也带 status。**
3. **P0 顺序同样适用于项目级终态**：`complete_project` → 项目目录 sync → 才发 `PROJECT_COMPLETED`；**同步失败则 `projectCompletionEventId` 不落库**（「不重用一条其完成态从未到达共享存储的通知」）。

**helloai 对照要点**：`changeStatus()`（`SubTaskServiceImpl.java:368-379`）的当前顺序是 `updateById` → `createEvent` → `sendInboxNotification` → `notifyAgentHandover` → `publishAssignmentEvent`。**需要实测确认的是**：`submit`/`complete` 路径上，**产物（`deliverable` / 附件 / shared storage 写入）是否在同一事务内先于 outbox 提交**。`complete()` 在 `:419-462` 之间执行「状态写入 → `completeTime` → 隐式评分计算（含 try/catch 容错）→ `updateById`」，**再于 `:463` 发 outbox** —— 产物是否已在此时可达需逐行核对。这一条是**审视线索**，不是已确认缺陷。

### 4.4 A4｜`request_attention` —— 把「进行中的人工决策」做成一等公民事件

**AgentTeams 的设计**（`task-completion-notification.md`）：

- **动机**：`In-flight human decisions are currently "ambient room chat" — a worker that needs approval/decision/escalation pokes the group and **hopes** a human notices.`
- **载荷**：`kind ∈ approval | decision | escalation | other`；`question` 必填 ≤500；可选 `resolved: true` **不产生结果地关闭**。
- **状态**：向 task meta 追加 `attention` 记录（`kind / question / attempt / requestedAt / resolved / eventId?`）。
- **幂等**：同 kind 且已有未决记录 ⇒ **复用已记录事件（不发第二次 ping）**；新 kind 或新 attempt ⇒ 新事件（txn `attention-<task-id>-<kind>-<attempt>`）。
- **`accept_task_result` 自动关闭全部未决 attention**（「leader 的决定已经闭环」）。
- **三条易错点（原文都是评审回合补的）**：
  1. **pending 记录复用**：首次 sync 失败会留下「**pending 记录（尚无 `eventId`）**」；重试必须**复用该记录**（重 sync → 发且仅发一个事件），**绝不能建第二条记录或第二个事件**。
  2. **关闭也 sync-first**：`resolved: true` 先本地标记 → 推目录 → 才报成功；失败返回 retryable，幂等重试重 sync 已解决态（**不发新 ping、不建新记录**）。
  3. **无未决记录时拒绝 `resolved: true`**：`a resolved: true call with no same-kind record is rejected (error, nothing recorded, no ping): there is no open loop to close, and **pre-creating a resolved record would still send the new attention ping**, contradicting the "close without a new ping" contract.`

**→ helloai 落点**：helloai 用**状态**表达「要人管」（`BLOCKED` / `PAUSED` / `DEAD_LETTER` + `context.manualIntervention` 标记 + timeline）。AgentTeams 用**事件 + 显式 close**表达。建议：

- `DEAD_LETTER` 的人工兜底池可参考「**每次进死信 = 一条 attention 记录**」，人工处置（指派 / 放弃 / 验收 / 驳回改派）= `resolved: true`；
- **「无未决记录时拒绝 resolved」这条判据**可直接用于**防人工面板重复点击 / 重复处置**——现状（`SubTaskStateMachine` 允许 `DEAD_LETTER → {ASSIGNED, CANCELLED, DONE, REWORK}`）下，**重复点击可能被状态机挡住，但「已处置再点」与「首次点击」难以区分**。

**审批（`kind=approval`）的补充约定**（文档标注为 proposed，未落地）：选项**必须回显请求自身的 option id**（strict echoing）；`suggested` 是**建议，永不自动应用**；`expires_at` 过期后按策略（wait/deny/转下一个响应者）**显式记为 denied，绝不静默**；**一条请求只有一个路由（console-first 或 room），不做双投递**。

### 4.5 A5｜审计双层 + **闭合 schema**（单条最有价值的设计律）

**AgentTeams 的设计**（`capability-foundation.md` §Dual-layer audit + `audit-events-api.md`）：

- **写侧**：`internal/audit.Client.Record(ctx, Event)` 写两处——① **即时结构化日志行（永远，即使没有存储）**；② **追加式 `audit/<YYYY-MM-DD>.jsonl`**（UTC 日期），`PutObjectIfMatch` ETag 乐观并发 + **3 次重试（100/200/400 ms + jitter）**，进程内 mutex 串行化。
- **★ 秘密卫生（最重要的判据）**：
  > `Event` is a **closed schema** — `Before`/`After` carry **capability names only**, `Detail` is a **controlled summary**, and **there is no free-text field a credential could hide in**（pinned by `TestEventJSONHasClosedSchema`）。  
  > **→ 判据：「审计事件必须是闭合 schema —— 不允许存在任何自由文本字段」**。因为自由文本字段一旦存在，就**无法证明**凭据没有从那里漏出去。这是「可证明的干净」而非「约定不出错」。
- **读侧**：`GET /api/v1/audit`（`team` / `from` / `to` / `kind` / `cursor` / `limit`）；**keyset 分页（timestamp, seq）**，理由写得很清楚：
  > The store is **append-only and unbounded over time**; **offset pagination would re-scan and re-sort every page and would shift under concurrent appends**.
- **降级语义**：当日对象缺失 = **零事件，非错误**；存储读失败 = 502；**对象内某行畸形 = 整请求 502**（「绝不返回部分、静默截断的列表」）。
- **输入校验全部前置**：`from`/`to` 必须 RFC3339；**两者都存在时比较「原始时刻」**（「用于枚举每日对象的按天截断不得掩盖天内顺序」）；游标必须能 base64url JSON 解码 + `ts` 可解析，**畸形字段在解码期 400，绝不作为服务端错误在扫描期浮现**。

**→ helloai 落点**：helloai 只有**凭据域**的 `CredentialAuditLog`（`V67__create_credential_audit_log.sql` + `CredentialAuditAction` + `AuditLogResponse`），**无通用审计写入层**。可直接借鉴：

- **闭合 schema 判据**（放入 `HelloAI_CODE_STYLE.md` 或规约）；
- **append-only 存储必须 keyset 分页**（helloai 的 timeline / 事件表若用 offset 分页，并发追加下会**重复或漏读**——**建议实测核对**）；
- **「畸形一行 ⇒ 整请求失败」**：与 helloai 的「宁可 502 不给半截列表」一致，是个值得固化的取舍。

### 4.6 A6｜水位（watermark）四判据 —— 来自 `issue-1107` 的实战复现报告

这份报告（`docs/design/internal/issue-1107-file-sync-io-amplification.md`，21644 字节）是**一份完整的缺陷复现 + 根因 + 分阶段优化 + 验收**范本，其判据可脱离 K8s/MinIO 单独成立：

1. **★ 一个 marker 不得同时表达两个语义**：
   > `.last-pull` 只是一个**本地时间水位**，不记录远端对象版本、ETag 或已传输对象集合。「最近 pull」与「最近成功 push」必须是**两个独立 marker**（`.agentteams-sync/last-successful-push` / `.last-manager-pull`）。
2. **★ 失败不得推进水位**；**成功必须原子推进到 `cycle-start`（mirror 开始前的快照）**：
   > 不能在 mirror 完成时简单 `touch marker`，否则 **mirror 过程中产生但未被复制的文件可能被错误跳过**。  
   > 同步期间再次变化的文件仍比水位新，会留到下一轮。
3. **★ 检测范围必须与推送范围一致**：
   > 检测使用整个 `${WORKSPACE}`，但真正 mirror 时排除了部分目录。**只要被 mirror 排除的运行时文件持续更新，检测仍会反复触发**。
4. **★ 「扫描比较 0 B」≠「没有 I/O」**：
   > 这里的 `0 B` 只表示没有对象内容需要重新传输，并不表示没有 I/O。`mc mirror` 仍需**遍历目录、列举对象、读取元数据并比较差异**。  
   > **→ 观测指标必须能区分这两者**（原文列为功能需求：「区分『扫描比较 0 B』和『完全没有调用 mirror』」）。
5. **★ 排除项默认不加（保守默认）**：
   > 将 `.codex/tmp/**` 硬编码为排除项**不具备运行时通用性**……**名称包含 `tmp` 也不能证明内容允许丢失**。优化的默认前提应是**保留现有同步语义**，通过减少重复扫描和重复比较降低 I/O，而不是**猜测哪些目录可以删除或忽略**。  
   > **→ 只有运行时「显式声明」可丢弃路径，且同时满足三条件（Worker 重建后不需恢复 / 丢失不影响任务 / 目录不承载用户产物）才允许排除**，且该契约**由运行时镜像维护，不散落在通用同步脚本中**。
6. **后台循环必须可观测**：每次失败输出「谁 / 方向 / 耗时 / 退出码」；记录两个后台 PID 并检测异常退出（**「不能只留下 `<defunct>`」**）；连续失败加**有上限的指数退避 + jitter**（避免多 Worker **同频共振**）。
7. **健康启动不得等待全量后台任务**：P2 验收项——「**Controller 健康启动不能等待整个 bucket 下载完成**」。
8. **反证式验收**：给出固定回归场景（1 秒建 4 个 Worker → 各生成 64 MiB → 静置 10 分钟 → 重启 Controller）与**可判定的验收标准**（「静置后不再每 5 秒执行全 HOME mirror」「无 zombie」「重启不读取任何 `agents/*/.codex/tmp/**`」）。**这份验收写法本身值得借鉴**。

**→ helloai 落点**：MinIO 附件同步、工作区/产物同步、`UpstreamAttachmentRenderer` 的截断与配额、任何「增量拉取/推送」实现。**建议把 1~5 条登记为可复用判据**（它们属于「先实测再下结论」家族，且是**被真实复现证明过的**）。

### 4.7 A7｜权限三段复合式 + **零扩散**的角色设计 + **全字段探针**

**AgentTeams 的模型**（`capability-foundation.md` + `l2/l3-worker-scoped-*.md`）：

- **检查顺序固定为 `role baseline AND TeamMatches AND HasCapability`**，且 `auth/capability.go:88` 的注释明确写：
  > A capability **NEVER implies team scope**, and never the role baseline in the reverse direction.
- **`HasCapability` 的角色基线**（`capability.go:92-111`）：`admin/manager → 永远 true`；`worker → 永远 false`；`human/team-leader → 集合成员，`full_access` 为 meta 值隐含全部`；`default → false`。
- **★ 零扩散的角色设计**（`l3-worker-scoped-read.md`）：
  > The L2/L3 distinction is carried by **data, not by a new role value**: `RoleHuman` is unchanged, and an L3 identity is a `RoleHuman` caller with a **non-empty `AccessibleWorkers` set**. **Every existing team-scope check keys on `RoleTeamLeader`/`RoleHuman` plus `TeamMatches`, so nothing changes** for admins, managers, leaders, L2 humans, or worker SAs.  
  > **→ 判据**：**新增权限档位应由数据承载，而非新增角色枚举值**——后者会波及所有 `switch`，前者则**现有检查全部零改动**。helloai 的 `AgentAuthPort` / `@SaCheckPermission` / `ClaimByAgent` 若未来要加「只读观察者」「团队作用域」等档位，**强烈建议走这条路**。
- **★ level-strict isolation（防静默范围放大）**：
  > an L3 CR that also lists `accessibleTeams` or `capabilities` gets **neither** in its identity（level-strict isolation, **no silent scope widening**）；  
  > `permissionLevel` is the discriminator；  
  > `accessibleWorkers` on an L2 CR is **inert** — the worker leg activates **solely at level 3**。
- **★ deny-by-default 的字段白名单 + 全字段探针测试**（`l2-worker-scoped-write.md`）：
  > A full-request-type probe test（`TestL2WorkerUpdateFieldPolicyCoversAllRequestFields`）pins the policy: **every field of `UpdateWorkerRequest` must be explicitly decided**, so **no field can become L2-writable by omission**（deny-by-default）。  
  > **→ 判据：「对请求类型的每一个字段都必须被显式决策，否则「遗漏」会静默变成「默认可写」」。** 这是**防遗漏**的极佳做法，**直接可用于 helloai 的更新接口与被 L2/L3 约束的 DTO**。
- **handler 是唯一的真实边界**（中间件只做粗筛）：两处文档都写「the authorizer is a pass-through; **the handler enforces the real boundary after resolving the worker's team**」，因为**中间件无法解析 `worker → team`**。**→ 与 helloai「§7.2 端口反转 / 谁拥有解析权谁做判定」同族**。
- **写入面按「值是否会泄漏凭据」分档**：`remoteSkills`（registry URI 可能内嵌凭据）、`mcpServers`（**gateway bearer key 会被逐条原样注入**——L2 可控的 URL 可外泄它）对默认 L2 **关闭（400）**，只有 `skills` 开放。**→ 判据：「一个字段是否可写，取决于它的值是否携带可外泄的权威凭据」**。

### 4.8 A8｜能力声明式可插拔 + Sandbox 插件 —— **直接对应未动的 RM10**

**`WorkerBackend`**（`internal/backend/interface.go:321-352`）：`Name()` / `DeploymentMode()` / `Available(ctx)` / `NeedsCredentialInjection()` / `Create` / `Delete` / `Start` / `Stop` / `Status`。**可选能力拆成独立小接口**：`ServiceBackend`（仅 K8s 实现）、`AuthTokenProjector`（能替换运行中 Worker 的 SaToken 文件）。


**`SandboxPlugin`**（`internal/backend/sandbox/plugin.go`）：

```
Type() string
Capabilities(config ProviderConfig) ProviderCapabilities   // = min(MaxCapabilities, config.Capabilities)
CreateSandboxClaim / DeleteSandboxClaim / DeleteSandbox
HibernateSandbox / ResumeSandbox      // 不支持时返回 ErrCapabilityNotSupported
GetSandboxClaimStatus / GetSandboxStatus / ListSandboxes
Validate(config) / HealthCheck(ctx, config)
```

配套 `PluginRegistry`（`registry.go`：**重复注册 panic**）、`ProviderCapabilities{Hibernate bool; Pool bool}`、`ErrCapabilityNotSupported`。

**★ 可搬运的 5 条判据**：

1. **能力是「计算」出来的，不是布尔开关**：`Capabilities(config) = min(MaxCapabilities, config.Capabilities)` ⇒ **同一实现在不同环境有不同能力**。注释明说：
   > Capabilities are **configuration-driven**: the same plugin may have different capabilities in different clusters.
2. **不支持的操作用显式错误类型拒绝**（`ErrCapabilityNotSupported`），**不是静默 no-op**。
3. **★「能力上限里只放真正被方法消费的字段」**：
   > Only fields that are **actually consumed** by plugin methods belong here. When a new gated feature is introduced, add the corresponding field then — **not speculatively**.  
   > **→ 这是极好的克制律**：能力位表最容易腐烂成「声明了但没人读」。
4. **`Validate`（配置/CRD 可用性）与 `HealthCheck`（连通性）分离**——职责不同、失败含义不同。
5. **Hibernate / Resume 成对**：对 helloai 的 `SubTaskStatus.PAUSED` + Sandbox 休眠/恢复**直接对口**。
6. **`needsCredentialInjection()` 是后端的一个显式能力**——「这个后端是否需要 controller 中介注入凭据」。**→ 判据：把「环境差异」显式建模为后端的一个查询方法，而不是在调用点写 `if (isDocker())`。**

**`accessresolver`**（`internal/accessresolver/{defaults.go,resolver.go}`）：`DefaultEntriesForWorker()` / `DefaultEntriesForTeamMember()` / `DefaultEntriesForManager()` / `ControllerDefaults(bucket, gatewayID)` + **模板展开** + `resolveObjectStorage` / `resolveAIGateway` / `resolveAIRegistry` / `resolveEntries`。  
**→ 判据：「按身份种类解析默认访问条目」**，且**默认集按身份种类分别定义并集中在 defaults.go**，而不是散落在各处。helloai 的 per-agent 工作区 / MinIO 作用域 / 技能可见范围可直接套用。

### 4.9 A9｜声明式期望态 + 「把不拥有什么写成契约」

见 §3 §1.2。补充 `boundary-and-contracts.md` 的价值——**它明确写出了 "TeamHarness does not own:" 清单，含 10 余项**，例如：

> Worker process lifecycle, pod restart, or runtime process supervision.  
> Worker desired-state parsing, polling, apply, or diagnostics.  
> **Credential access enforcement that depends on runtime-specific file or tool guard support.**  
> **Secret value storage.**  
> Periodic workspace push/pull loops.

**→ 判据：「把『不拥有什么』也写成契约（负向边界）」**。正向职责清单无法防止越界，**负向清单可以**——它给出「这个模块不许做的事」的可审清单。**建议 helloai 的域/端口文档补「本域不拥有」段**（对比 `AGENTS.md` 的 `May import / Must NOT import` 表，那是依赖向；这里是**职责向**）。

同时注意契约的关系描述方式（`Contract Relationships`）——它按**方向**写：`Controller → runtime` / `Runtime worker → TeamHarness` / `Runtime adapter → TeamHarness` / `TeamHarness plugin package → AgentSpec package`，并在最后一条明确：

> **Updating an AgentSpec package must not be modeled as updating the TeamHarness plugin package.**

**→ 判据：「不得把两个生命周期不同的东西建模为同一个更新动作」**。

### 4.10 A10｜委派边界与「谁不在哪个房间」

见 §3 §1.6。**可搬运的判据**：

- **「谁可以指挥谁」应显式建模，并有「不穿透」约束**。helloai 的 `planner > review > task > agent` 是**依赖方向**；AgentTeams 的「Manager 不穿透 Team」是**指令权限方向**——**两者是不同维度，helloai 目前似乎只显式建模了前者**。
- **「谁不在哪个房间」是权限设计的一部分**（Team Room 刻意不含 Manager）⇒ **可见性是可以被主动收窄的设计手段**。
- **`channelPolicy` 的叠加模型**：`groupAllowExtra` / `groupDenyExtra` / `dmAllowExtra` / `dmDenyExtra` —— **「基础策略 + 允许增量 + 拒绝增量」四段式**，比单一白名单可组合。
- **Human 权限与房间 power level 的映射**（`room-power-levels.md`）：`permissionLevel 1 → 100`（co-owner）/ `2|3 → 50`（Matrix 默认成员权限）；并且**明确写出接受了 level-50 的 kick/ban/redact 权限及其理由**（「human 是这些房间的操作者；房间的 manager 在 100 之上，所以 kick/ban 不能被用来对付控制面」）。**→ 判据：「显式接受某个权限，并写出为什么它不可被用于越权」**，比「无脑抬高阈值」更有信息量。

### 4.11 A11｜技能分层 + 「目录即契约」+ 「与 deployer 同源」

`skill-catalog-api.md` + `team-skills.md` 给出四层技能：`builtin`（模板携带，**per-runtime**）/ `plugin`（**manifest 驱动**）/ `shared`（staging）/ `team`（新）。

**★ 可搬运判据（对 helloai 技能化直接对口）**：

1. **「可用性跟随部署，而不是跟随模板」**：plugin skill 的 `runtimes` 字段**故意省略**，理由：
   > a plugin skill is available to a worker **iff the worker has the plugin**（availability follows the **plugin's deployment**, not per-runtime templates）.  
   > **→ 判据：可见性必须由「真实的生效条件」决定，而不是由「某个近似代理」（模板/运行时）决定。**
2. **★ 发现由 manifest 显式声明驱动**：
   > a skill appears **iff** its plugin's `plugin.yaml`（`kind: AgentTeamPlugin`）declares it in its `skills:` block —— **unlisted `SKILL.md` directories do not leak into the catalog**, and no worker state is scraped.  
   > **→ 判据：「目录里扫到的」不等于「应该被发布的」——未声明的对象不得泄漏进 catalog。**
3. **★ catalog 必须与 deployer 的行为同源（防漂移的教科书做法）**：
   > The template→runtime mapping is **derived from `service.BuiltinAgentDir`** — **the same function the deployer calls** …… The catalog therefore **cannot drift** from what workers actually receive: if the deployer's template selection changes, the catalog's per-runtime availability changes with it, **automatically**.  
   > **→ 判据：「描述系统的读接口必须复用系统实际使用的那个函数」，而不是维护一份并行映射。** 这条对 helloai 的 `AgentSkillSpecServiceImpl.KNOWN_SPECS`（**编译期 `knownSpecs()` Map，硬编码**，`AgentSkillSpecServiceImpl.java:29/148`）是**极强对照**：AgentTeams 的做法是「同一个 `BuiltinAgentDir` 函数既驱动 deployer 又驱动 catalog」；helloai 是**一份硬编码 Map**——**需实测确认它与实际生效路径是否可能漂移**。
4. **`shared` 目录是 staging，不是分发通道**：
   > `agents/global/skills/` is a **staging area, not a distribution channel**: **no worker entrypoint consumes this prefix automatically**.  
   > 且删除它**没有级联**（不触已分发副本，也不动 `spec.skills`）。**→ 判据：「staging 与 pipeline 必须区分」**。
5. **`requires` 前置声明，但「执行取决于 Runtime」且**明确标注缺口\*\*：
   > Enforcement is **runtime-dependent**: the qwenpaw 2.2.x registry gates skill activation on it; **other runtimes have no equivalent gate yet** — the field is exposed so workbenches can **warn before assignment**.  
   > **→ 判据：「声明 ≠ 强制」必须在文档与响应里显式标注**（而不是让消费方误以为已强制）。这与 helloai 的「★『规则存在』≠『全路径强制』」判据同族。
6. **catalog 只读 metadata**：`name/description/source/version/requirements/updated_at/agents/plugin/runtimes`，**响应 schema 由 `TestSkillsCatalogFieldDiscipline` 钉住**；**绝不暴露 skill body 与 registry 凭据**。
7. **降级永不报错**：插件目录缺失 / manifest 畸形 / `SKILL.md` 缺失 ⇒ **降级为该条目不存在，绝不报错**；后端读失败 ⇒ **降级到剩下的半边，仍 200**。builtin 与 shared 冲突 ⇒ **builtin wins**。

### 4.12 A12｜技能上传的「双闸门 + 结构校验清单」

`team-skills.md` 的写侧设计：

- **两阶段闸门**：**上传（scan ①，best-effort）** / **指派时 materialize（scan ②，mandatory）**。原文的取舍很明确：「the mandatory gate is scan ②」。
- **★ 基础设施失败绝不等于通过**：
  > a scanner-import failure, a missing skill directory, or a hard crash yields `unavailable` — **an infrastructure failure, never a pass**.  
  > Scan ② 的 `unavailable` ⇒ **拒绝复制**（"the gate does not default open"）。
- **★ 探针绕过运行时配置**：probe **直接调用扫描器**（不走 runtime 的 off/warn/block 配置）；旧 runtime 回退到 `block=True`；**scanner 返回 `None`（被白名单）⇒ 记为 block finding，不是 pass**。列出的 **4 条绕过路径**（runtime config off/warn / whitelist / scanner 缺失或坏 / 非 qwenpaw manager）都有对应处置。
- **★ verdict 按内容 hash 缓存（30 min TTL / 100 条 FIFO），① 与 ② 共享同一缓存** ⇒ 「指派刚上传的技能零额外 exec」。
- **结构校验清单（8 条负例，可直接用作 helloai 技能包上传的验收清单）**：zip **恰好一个顶层目录**；`SKILL.md` 直接在根且 frontmatter `name` **等于目录名**；目录名 kebab-case ≤64；**zip-slip 防御**（绝对路径 / 反斜杠 / 空 / `.` / `..` 组件 / **symlink 条目**全部拒绝）；**64 MB 双层上限（zip 与解压后，防 zip bomb）**。
- **★ 命名三处一致**：**目录名 = `SKILL.md` frontmatter `name` = 存储 key = 指派引用**。
- **scan 结果只带 metadata**（rule id / severity / file / line / title），**绝不带文件内容**。
- **具名已知限制**：k8s 模式 SPDY-exec 未实现 ⇒ **fail closed**（uploads 标 `skipped`，materialization **拒绝复制**）。**→ 判据：未实现的路径必须 fail-closed 并显式标注，而不是 fail-open。**

### 4.13 A13｜反探测：越权响应码按「是否泄漏存在性」分

多处一致，但**不是一刀切**：

- L2/L3 跨 team 读取 ⇒ **404**（「403 会暴露 team 存在性」，「existence cannot be probed」）。
- **但** L2 写路径中「**teamless human（完全没有 `accessibleTeams`）**」⇒ **403 在中间件层**（在任何 worker 查询之前），**因为此时无需隐藏存在性**。
- 技能目录：L2/leader 跨 team 或未知 team 读取 ⇒ **404，与「无此 team」不可区分（status + body 都不可区分）**。

**→ 判据：「越权响应码的选择取决于『是否会泄漏对象存在性』——不是一律 404，也不是一律 403，而是按此二分。」** 这个细化是**可直接搬的**。

### 4.14 A14｜不透明游标 + 「游标过期」显式化

来自 `task-transition-engine.md` 的 events 端点设计（原文极其严谨，逐条都是可搬判据）：

- `cursor` = **不透明事件身份游标 `(ts, task_id, seq)`**；**`seq` 为写入端持久化的每任务序号**（`history_seq` 计数）——**理由**：
  > **跨 50 条截断稳定**，精确匹配定位、无歧义，**重复事件（同秒同内容）不跳过不重复**。
- **旧格式（无 `seq`）游标 ⇒ 返回 `cursor_expired`，强制客户端重置**。  
  旧格式「同秒重复共享同一身份」，锚定其内的游标携带**组内序号 + 列表长度快照**；**快照被截断时返回 `cursor_expired`**。
- **零新存储、无写侧钩子**（seq 由既有 task meta 写入路径顺带持久化）。
- 权限链与既有 workflow/history 一致（跨 team 隐藏为 404；**任务 meta 只取项目属主 scope，不做跨 scope 回退**）。
- **畸形条目跳过不报错**（读侧容错），但**审计查询侧**畸形行 ⇒ **整请求 502**（写侧不可信则拒绝）。**→ 两个端点的容错策略不同，取决于「数据是否 append-only 可信」**。

**→ 判据**：**① 游标必须不透明；② 游标必须能检测「其指向的历史已不可达」（显式 `cursor_expired`），而不是静默返回错页；③ 让序号在写入端持久化（而非读时计算），以使截断后仍稳定。**  
**→ helloai 落点**：`TaskTimelinePort` 的分页/时间线读取、前端 `utils/sequenceFlow.ts` 的事件序列。**建议实测现有 timeline 分页是否用 offset、是否存在并发追加下重读/漏读。**

### 4.15 A15｜其他高价值零散判据（已实测，逐条可用）

| #  | 判据                                                                                                                                                                           | 出处                                   |
| -- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| 1  | **「不得发出一条接收方无法回答的消息」**：首启欢迎消息**门控在两个条件**——Matrix 房间成员**且** LLM auth 就绪（**端到端探针**），「so the Manager never receives a message it cannot answer」                                 | `changelog/v1.1.0.md`                |
| 2  | **`Validate`（配置/CRD 可用）与 `HealthCheck`（连通性）分离**                                                                                                                              | `sandbox/plugin.go`                  |
| 3  | **重复注册 panic**（`PluginRegistry.Register`）—— 配置错误**启动即炸**，不留到运行时                                                                                                              | `sandbox/registry.go:15-21`          |
| 4  | **能力上限只放「真正被方法消费」的字段，不投机预加**                                                                                                                                                 | `sandbox/plugin.go`                  |
| 5  | **可选能力用小接口拆分**（`ServiceBackend` / `AuthTokenProjector`）——「仅部分实现者满足的能力」不污染主接口                                                                                                 | `backend/interface.go`               |
| 6  | **`NeedsCredentialInjection()`**：把「环境差异」显式建模为后端的一个查询方法                                                                                                                       | `backend/interface.go`               |
| 7  | **默认访问条目按身份种类集中定义**（`DefaultEntriesForWorker/TeamMember/Manager`）                                                                                                            | `accessresolver/defaults.go`         |
| 8  | **模板展开在解析期**（`templateCtx.expand`）——配置写模板、运行期展开                                                                                                                              | `accessresolver/resolver.go:209`     |
| 9  | **workflow 图渲染的 id 安全化 + 冲突加后缀**（`sanitizeNodeID`：非 `[A-Za-z0-9_-]` 替换为 `_`，「**Colliding ids get a numeric suffix so two different task ids can never render as one node**」） | `internal/workflow/mermaid.go:57-70` |
| 10 | **归一化状态枚举与前端一致 + 图例内联**（`pending/delegated/in-progress/completed/revision/blocked` + `classDef`）                                                                             | `internal/workflow/mermaid.go`       |
| 11 | **「稳态零写入」**：先读当前态，只有值真的变了才写                                                                                                                                                  | `room-power-levels.md`               |
| 12 | **「观察到的对象也要做应有状态修复」（healing path）**：遗留数据首次 reconcile 后自愈，无需手工回填                                                                                                              | `room-power-levels.md`               |
| 13 | **「归属不可解析 ⇒ 延迟一拍」，不得执行破坏性操作**（否则永久死锁）                                                                                                                                        | `room-power-levels.md`               |
| 14 | **「只有 404/not-found 才是幂等；其余 403 必须作为可解码错误暴露」**（不要把错误吞成幂等成功）                                                                                                                  | `room-power-levels.md`               |
| 15 | **登录缓存不得短路「存活检查」路径**（`OrphanRecovery` 必须绕过缓存）                                                                                                                                | `room-power-levels.md`               |
| 16 | **代价放到异常路径**（self token **lazily** fetch, only after 403）                                                                                                                    | `room-power-levels.md`               |
| 17 | **健康启动不得等待全量后台任务**（Controller 启动不等整个 bucket 下载完成）                                                                                                                            | `issue-1107` P2                      |
| 18 | **导出诊断必须脱敏完整事件**（"redact complete Matrix events in debug bundles"）                                                                                                           | `changelog/v1.2.0.md`                |
| 19 | **拒绝不安全的归档链接**、**生成可直接运行的导入命令** —— 工具链的输出必须是**可直接执行的**，不是示意                                                                                                                  | `changelog/v1.2.0.md`                |
| 20 | **「跨 5 分钟阈值不收敛」类缺陷要定位到「后台子进程变成 `defunct`」** —— 后台循环必须有父进程显式记录失败，不能只留僵尸                                                                                                       | `issue-1107`                         |


### 4.16 A16｜编号成对迁移 + 水位（与 Octop 同构，此处只标一致性）

AgentTeams 的 CRD 侧未展开读；`Octop全库借鉴分析_综合版.md` 已详述「编号成对迁移 + `_schema_version` 水位」与「双 ID 方案」。**两项目在同一点上收敛（各自独立），可作为该判据的第二个证据源**——建议按 Octop 报告的结论执行，此处不重复。

---

## 五、B 档：架构思维可迁移（需按 helloai 现状裁剪）

| #   | AgentTeams 做法                                                         | 可迁移的「思维」                                 | helloai 裁剪建议                                       |
| --- | --------------------------------------------------------------------- | ---------------------------------------- | -------------------------------------------------- |
| B1  | **支持矩阵 = manifest 驱动**（`plugin.yaml` 的 `skills:` 声明才发布）               | 「扫到的 ≠ 该发布的」                             | 用于 `AgentSkillSpec` 的可见性与前端技能中心                    |
| B2  | **catalog 复用 deployer 的函数**（`BuiltinAgentDir`）                        | 「读接口必须复用系统实际使用的那个函数」                     | 对照 `AgentSkillSpecServiceImpl.KNOWN_SPECS` 硬编码 Map |
| B3  | **staging ≠ pipeline**（`agents/global/skills/` 无消费者）                  | 「中间态目录必须显式标注非分发通道」                       | 用于附件/产物暂存区的语义标注                                    |
| B4  | **`Capabilities = min(上限, 配置)`**                                      | 「能力是算出来的」                                | RM10 Sandbox 的能力位                                  |
| B5  | **`ErrCapabilityNotSupported`**                                       | 「不支持要显式报错，不要静默 no-op」                    | 端口/适配器的可选方法                                        |
| B6  | **负向边界清单**（"does not own"）                                            | 「把不拥有什么也写成契约」                            | 各域/端口文档补「本域不拥有」段                                   |
| B7  | **契约按方向书写**（`Controller → runtime` …）                                 | 「契约是**关系**而非**清单**」                      | 端口文档的契约段                                           |
| B8  | **「不得把两个生命周期不同的东西建模为同一个更新动作」**（AgentSpec ≠ plugin）                    | 生命周期正交性                                  | 规范包 vs 实现包（helloai 的 `AgentSkillSpec` 规范/实现）       |
| B9  | **数据承载权限档位（零扩散）**                                                     | 「新增档位用数据，不用新角色枚举」                        | `AgentAuthPort` / Sa-Token 权限扩展                    |
| B10 | **level-strict isolation**                                            | 「上层权限字段在下层级别必须被忽略而非叠加」                   | 多级权限/多作用域的叠加语义                                     |
| B11 | **全字段探针测试**（deny-by-default）                                          | 「每个字段必须被显式决策，遗漏 = 默认可写」                  | 更新类 DTO 的字段策略测试                                    |
| B12 | **handler 是唯一真实边界**                                                   | 「谁能解析对象归属，谁做判定」                          | 与 §7.2 端口反转同族，需核对中间件与 handler 的职责切分                |
| B13 | **`report_progress`（不改状态、只记账、不通知）**                                   | 「进度 ≠ 状态」                                | SubTask 长任务进度                                      |
| B14 | **`request_attention` 一等公民化**                                         | 「进行中的人工介入是事件，不是状态」                       | `DEAD_LETTER` 人工兜底池 / 审批                           |
| B15 | **审计闭合 schema**                                                       | 「不允许存在任何自由文本字段」                          | 通用审计（新增）                                           |
| B16 | **append-only ⇒ keyset 分页**                                           | 「offset 分页在 append-only + 并发追加下会重扫重排并漂移」 | timeline / 事件表分页                                   |
| B17 | **越权响应码按「是否泄漏存在性」二分**                                                 | 反探测的分级                                   | 各 API 的错误码                                         |
| B18 | **`channelPolicy` 四段式叠加**（base + allowExtra + denyExtra，group/dm 各一组） | 「基础策略 + 允许增量 + 拒绝增量」                     | 任何策略/闸门的组合模型                                       |
| B19 | **「谁不在哪个房间」= 可见性主动收窄**                                                | 可见性是设计手段                                 | 事件可见范围 / 通知受众                                      |
| B20 | **委派边界（不穿透）**                                                         | 「指令权限方向」是独立维度                            | planner→agent 的指令权限建模                              |

---

## 六、明确不抄清单（K8s / Matrix 特定实现）

| 项                                                                                                 | 为什么不抄                                                                                                                                 |
| ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| **CRD + controller-runtime + Helm + leader election**                                             | helloai 是 Spring Boot 单集群 Java 应用，K8s operator 范式不适用。**可迁移的只有 §3 §1.1 的幂等修复判据。**                                                      |
| **`kine`(SQLite) / `etcd` 作为 CRD 后端**                                                             | 纯 K8s 生态物                                                                                                                             |
| **Matrix（Tuwunel）+ 房间 / power level / m.mentions / m.file**                                       | 协作层是 IM 协议特化。**可迁移的只有「稳定 txn 幂等键」与「谁不在哪个房间」的思维（B19）。**                                                                                |
| **Higress AI Gateway + Consumer Token ≈ SA token / allowedConsumers ≈ RBAC**                      | 需要 Higress 部署面。**可迁移的只有原则**：「真实凭证只在 Gateway，Agent 只持可撤销 token」——helloai 的 `AgentAuthPort.validateApiKey` + 凭据 AES 加密体系是不同解法，**不必替换**。 |
| **多 runtime 镜像矩阵**（openclaw / qwenpaw / hermes / deepseek-harness / openhuman）                    | 每 runtime 一个镜像 + `.copaw`→`.qwenpaw` 目录迁移等，全是容器资产问题。helloai 的外部 agent 通过心跳接入，**模型不同**。                                                |
| **`agt` CLI 分发 + tarball 插件包 + plugin-probe**                                                     | 分发机制特化                                                                                                                                |
| **矩阵房间级 power level 数值映射（100/50/0）**                                                              | Matrix 语义特化                                                                                                                           |
| **`channel_secrets` / `external_sources` / `approval_policy` / `secret_reveal` 这四个 capability 值** | **值本身不通用**（绑定其渠道与审批面）。**可抄的是「闭合值集 + 单一事实源 + 漂移钉住测试」的形式**（`ValidCapabilities` + `TestValidCapabilitiesMatchesDocumentedValueSet`）。     |
| **mermaid workflow 渲染**                                                                           | 具体实现不抄；**可抄 `sanitizeNodeID` 的 id 安全化 + 冲突加后缀**（A15-9）与「归一化状态与前端对齐 + 图例内联」（A15-10）。                                                   |

---

## 七、借鉴项索引（**本节不承载排序**）

> **《治理规则》§3.6：`research/` 不写排期与进度。** 排序唯一载体 = `doc/plan/HelloAI 借鉴落地实施计划.md`（编号 `REF-x.y`）+ 《HelloAI 实现差距表》。
> 本报告 `A1~A16` 各条的定义与证据见 **§四**；与该计划的对应关系如下（供追溯「哪条借鉴落到哪里」）：

| 本报告条目 | 实施落点（`REF-x.y`） | 状态 |
|---|---|---|
| `A2` 转换表外置为可断言资源 | `REF-6.x` 判据类（随对应组落地；建议作 `verify-*` 门禁） | — |
| `A1` 状态写入收口单一入口 | `REF-6.x` 判据类（与 `A14` 同族） | — |
| `A3` sync-first, then notify | `REF-6.1`（判据；与 Octop 侧 `E4` 同源） | ✅ 已登记 |
| `A4` `request_attention` 人工决策一等公民 | 未立项（登记为观察项） | — |
| `A5` 审计双层 + 闭合 schema | `REF-6.2`（判据；keyset 分页） | ✅ 已登记 |
| `A6` 水位四判据 | `REF-6.4`（判据） | ✅ 已登记 |
| `A7` 权限档位零扩散 + 全字段探针 | 未立项（登记为观察项） | — |
| `A8` 能力声明式可插拔 + Sandbox 插件 | `REF-3.2`（**预案**，条件触发；须先定 `min(声明,配置)` 语义——上游注释与实现相反） | ⏸ 条件触发 |
| `A9`~`A13`、`A15`、`A16` | 判据类，未单独编号（按需在实现时对照） | — |
| `A11`/`A12` 技能目录与上传双闸门 | `REF-1.2` / `REF-1.5`（`REF-1.5` 须在 `REF-1.6` 安装入口之后） | ✅ 已立项 |
| `A14` 不透明游标 + 游标过期 | `REF-6.2` 同族（keyset 分页） | ✅ 已登记 |

> **2026-10-09 说明**：本节原有「落地顺序建议（含依赖）」的 `① ~ ⑨` 排序块**已移出**——它属**项目排期口径**，按当日的排期/分期治理令不得留在 `research/`；**无信息损失**：各条 A 档借鉴项的定义、证据与依赖说明在 §四 完整保留，其排期已由 `plan/HelloAI 借鉴落地实施计划.md` 承载。原块中的两条依赖判断仍然成立、已并入上表：① `A2 → A1`（表先外置才好断言）；② `A8` 依赖 RM9/RM10 的拆法裁定（**RM9 仍未动**，见 `plan/HelloAI_RM存量缺口清偿计划（2026-10-04）.md`）。
## 八、事实核查表

| 结论                                                                      | 核实方式                                                                                                                                 | 结果                                                                      |                        |      |
| ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------- | ---------------------- | ---- |
| AgentTeams 已 K8s 原生化                                                    | `internal/` 包清单（27 包）+ `api/v1beta1` + `cmd/{agt,controller}` + `changelog/v1.1.0.md` `[实测]` | ✅ 确认                                                                    |                        |      |
| 有独立的任务转换引擎                                                              | `docs/design/teamharness/task-transition-engine.md`（6318 B）全文 `[推断]` | ✅ 确认（**文档口径**，未读到 `server.py` 全源码）                                      |                        |      |
| 转换表跨语言共享                                                                | 同上，`plugins/teamharness/contracts/task-transitions.json` + Go 测试加载 `[推断]` | ✅ 确认（文档口径）                                                              |                        |      |
| 审计是「闭合 schema」                                                          | `capability-foundation.md` §Dual-layer audit + `TestEventJSONHasClosedSchema` `[实测]` | ✅ 确认                                                                    |                        |      |
| `HasCapability` 角色基线                                                    | `internal/auth/capability.go:92-111` 实读 `[实测]` | ✅ 确认                                                                    |                        |      |
| 权限档位由数据承载                                                               | `l3-worker-scoped-read.md` §Design / §Tests `[实测]` | ✅ 确认                                                                    |                        |      |
| 全字段探针测试                                                                 | `l2-worker-scoped-write.md`：`TestL2WorkerUpdateFieldPolicyCoversAllRequestFields` `[推断]` | ✅ 确认（测试名，未读测试源码）                                                        |                        |      |
| Sandbox 能力是 min 计算                                                      | `internal/backend/sandbox/{plugin.go,openkruise.go:44-66}` 实读 `[实测]` | ✅ 确认                                                                    |                        |      |
| 能力位「不投机预加」                                                              | `plugin.go` 注释原文 `[实测]` | ✅ 确认                                                                    |                        |      |
| 水位四判据                                                                   | `issue-1107-file-sync-io-amplification.md` §六 全文 `[实测]` | ✅ 确认                                                                    |                        |      |
| **helloai 有 12 个 `updateById` / 8 个 `setStatus` 写入点** | `grep -n "updateById(\|subTask.setStatus" SubTaskServiceImpl.java` `[实测]` | ✅ 确认（updateById：192/200/368/462/809/901/987/1090/1110/1147/1407/1497） |  |  |
| **扇出被手写实现 4 次，无共用入口** | `grep -n "sendInboxNotification\|sendApprovedInboxNotification\|sendReworkInboxNotification"` → 定义在 `:629` / `:580` / `:491`，各有独立调用点 `[实测]` | ✅ 确认 |  |  |
| **`changeStatus()` 与 `complete()` 都不写 timeline**                        | `grep -n "taskTimelineService.recordEvent"` → 命中 `:852/:913/:990/:1029/:1155/:1173`，**均不在 `:330-381` 与 `:415-473` 区间内** `[实测]` | ✅ 确认                                                                    |                        |      |
| **`resetToPendingForDispatch` 零覆盖（无 validate / 无 outbox / 无 timeline）** | `sed -n '1079,1094p'` 实读：仅 allowlist + `setStatus` + `setAssignedAgentId(null)` + `updateById` `[实测]`（内层实读；外层调用点未核） | ✅ 确认（**`SubTaskServiceImpl` 内无扇出；外层调用点未核**）                             |                        |      |
| **helloai `SubTask` 无状态变更史字段**                                          | `SubTask.java` 字段清单实读 `[实测]` | ✅ 确认                                                                    |                        |      |
| **helloai 有 `taskTimelineService` 但不由 `changeStatus` 驱动** | `grep -n "taskTimelineService\|TimelineEvent"` → 仅 `:988`/`:1159` 等零星点 `[实测]` | ✅ 确认 |  |  |
| **helloai 无通用审计** | `ls db/migration \| grep audit` → 仅 `V28`（会话消息列）与 `V67`（凭据审计）；`find \| grep -i audit` → 仅凭据域 `[实测]` | ✅ 确认 |  |  |
| **helloai `SubTaskStateMachine` 仅 Java 内联**                             | 全文实读（`static {}` 块，无外部资源） `[实测]` | ✅ 确认                                                                    |                        |      |
| **`AgentSkillSpecServiceImpl.KNOWN_SPECS` 是编译期 Map**                    | `:29` `KNOWN_SPECS = knownSpecs()`；`:148` `knownSpecs()` `[实测]` | ✅ 确认（**未逐一核对是否可能漂移**，报告只做对照不做断言）                                        |                        |      |
| `plugins/teamharness/mcp/server.py` 的 `_transition_task()` 全实现          | 未读到源码 `[未核实]` | ❌ **不可核实**（本文相关结论均为文档口径）                                                |                        |      |
| `deepseek-harness` runtime 内部                                           | 未读（17 文件） `[未核实]` | ❌ **不可核实**；`skill-catalog-api.md` 自述其对 `runtime.yaml` 的 skills 段是 no-op |                        |      |
| K8s 实测行为                                                                | 本机无集群 `[未核实]` | ❌ **不可核实**（文档附 fake 强制同规则的测试证据）                                         |                        |      |

---

## 九、一句话收尾

**AgentTeams 最新版对 helloai 的最大价值，不在它新增的 K8s/Matrix 能力，而在它把「5 月版借鉴的那 7 项」各自推进到「可证明正确」的程度**——尤其是 `task-transition-engine` 把「散写状态」收口为「一张表 + 一个入口 + 一条历史」，**正好逐字命中 helloai `SubTaskServiceImpl` 的 12 个写入点与实体无状态史**；`issue-1107` 的水位四判据与 `capability-foundation` 的闭合 schema 判据，则是**可直接登记为项目级 reusable judgment 的、被真实复现证明过的**工程律。
