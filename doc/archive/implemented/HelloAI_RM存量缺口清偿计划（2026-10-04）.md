# HelloAI RM 存量缺口清偿计划（2026-10-04）

> **状态：Done** — 归档 2026-10-17（原 `doc/plan/HelloAI_RM存量缺口清偿计划（2026-10-04）.md`）。
> **完成依据**：① **RM8**（9 个跨域适配器迁 `adapter/` 包）+ **RM6**（`TaskPlannerPickerPort` 去实体，返回 `PlannerAgentRef`）批 1 完成（2026-10-04）；② **RM5 前向实体泄漏六项全部归零（61→0）** —— 批 2（`AgentProfileSnapshot` 收 25 行）/ 批 3（`agent→system.entity` 11→0）/ 批 4（`review→task.entity` 13→0）/ 批 5a（`planner→task.entity` 9→0）/ 批 5b（`task→agent.entity` 3→0）全部完成（2026-10-04）；③ **RM9 上帝类拆分三笔 3/3 收官** —— `McpToolServiceImpl` 1334 → **409**（2026-10-16）/ `SubTaskServiceImpl` 1592 → **551**（2026-10-16）/ `RequirementClarifyServiceImpl` 1557 → **320**（2026-10-17），详见 §7.5~§7.7；④ 同批**命名合规整改** —— `{domain}.service.impl` 下非 `*ServiceImpl` 文件 = **0**（投影助手迁出 `{domain}.projection`）。
> **代码实测（2026-10-17 归档前复核）**：`review→task.entity` = **0**、`planner→task.entity` = **0**、`task→agent.entity` = **0**（逐域 `grep -rn "helloai.core.task.entity"` / `"helloai.core.agent.entity"` 取证）⇒ **RM5 六项全归零，本计划无遗留项**。
> **★正文口径说明（勿据下列段落判定「未完成」）**：本文件 §0 状态行「RM9 上帝类拆分未动」、§3.3「分轮建议」、§7 表末行「RM9 …（2/3 笔）」、§8「批 4 / 批 5a 未完成 —— 需先出设计稿」均为**执行过程中的现场表述**，已被 §7.5~§7.7 与归档前实测取代。按归档纪律（**不改史、不更新正文**）原样保留；**终态一律以 §7.5~§7.7 与《HelloAI 实现差距表》为准**。
> **本文件冻结只读**，不再更新正文；当前状态以《HelloAI 实现差距表》为准。
>
> **范围**：代码质量路线图 **RM5 / RM6 / RM8 / RM9**（阶段 B 剩余存量缺口）
> **依据**：`doc/review/HelloAI 架构V2进度与质量审计报告（2026-10-02）.md` §6.3
> **规范**：`doc/HelloAI_AI开发协作规约.md`（§9 生命周期 / §13 先计划 / §14 最小变更 / §40 红线）；`doc/HelloAI_CODE_STYLE.md`（§6 依赖方向 / §7.2 端口反转判据 / §7.3 Adapter / §9 类规模）
> **原则**：先做成、再做好；最小变更；可验证、可回滚、可解释、可追踪（规约 §42）。

---

## 0. 总览（落点均为本轮实测，非引用）

| 项 | 定级 | 落点（实测） | 改法判据 | 本轮批次 |
|---|---|---|---|---|
| **RM8** 适配器包卫生 | **S** | `task/service/impl/` 内 **8** 个 `*PortAdapter` + `review/service/impl/` 内 **1** 个 | §7.3（Adapter 独立概念）——**纯包移动、零行为变更** | **批 1（本轮）** |
| **RM6** 端口契约去实体 | **S** | `task/port/TaskPlannerPickerPort.pickForTask(Long): Agent`；main 消费方 **1 处**（`TaskFinalReportServiceImpl:227`） | §7.2 值对象纪律——返回 `PlannerAgentRef` record（消费方用 `executeSync(Long, ...)` 已有重载） | **批 1（本轮）** |
| **RM5** 前向实体泄漏 61→0 | **M~L** | **61 行 / 6 域对**：task→agent 14 / review→task 13 / agent→system 11 / planner→task 9 / planner→agent 7 / review→agent 7 | §7.2「只读→快照、写→不透明命令、判定→搬提供方」 | **分轮**（批 2 起） |
| **RM9** §9 上帝类拆分 | **L** | 3 类：`McpToolServiceImpl`(1181/40 法) · `SubTaskServiceImpl`(1379/79 法) · `RequirementClarifyServiceImpl`(1558/97 法) | §9.1「按业务职责拆分」，**一类一笔提交** | **后续专项**（一类一笔） |

> **为什么分批（不是「一次硬做」）**：RM5 报告自身标注「**分轮**」，RM9 标注「**一类一笔提交**」——审计报告的建议粒度就是分批。一次性改 61 处跨域解耦 + 拆 3 个 1000+ 行类，违反规约 §14（最小变更）与 §42（可回滚/可追踪），且回归面不可控。故按「S 级先落 → M~L 分轮 → L 一类一笔」推进。

---

## 1. RM8 适配器包卫生（批 1，S）

### 1.1 现状（实测）
9 个跨域适配器与业务 `ServiceImpl` 混在 `service/impl/`，违反 §7.3「Adapter 是独立概念」的组织精神：

| 适配器 | 现位置 | 实现端口 | 端口归属 |
|---|---|---|---|
| `ArtifactReferencePortAdapter` | `task/service/impl/` | `ArtifactReferencePort` | `system/port` |
| `AttachmentPortAdapter` | `task/service/impl/` | `AttachmentPort` | `agent/port` |
| `SubTaskCommandPortAdapter` | `task/service/impl/` | `SubTaskCommandPort` | `agent/port` |
| `SubTaskQueryPortAdapter` | `task/service/impl/` | `SubTaskQueryPort` | `agent/port` |
| `SubTaskReviewContextPortAdapter` | `task/service/impl/` | `SubTaskReviewContextPort` | `agent/port` |
| `SubTaskStatsPortAdapter` | `task/service/impl/` | `SubTaskStatsPort` | `agent/port` |
| `TaskRunningSpecPortAdapter` | `task/service/impl/` | `TaskRunningSpecPort` | `agent/port` |
| `TaskTimelinePortAdapter` | `task/service/impl/` | `TaskTimelinePort` | `agent/port` |
| `ReviewPortAdapter` | `review/service/impl/` | `ReviewPort` | `task/port` |

### 1.2 改法
- 8 个 `task` 侧适配器迁 **`task/adapter/`**（新建包）；`ReviewPortAdapter` 迁 **`review/adapter/`**（新建包）。
- 仅改 `package` 声明 + 物理位置；类名、内容、注解（`@Service`/`@RequiredArgsConstructor`）**零变更**。
- Spring 组件扫描根为 `com.helloai.core`，新子包自动覆盖；适配器实现的是 `public` 端口接口，保持 `public` 类即可。
- 更新测试中直接 import 适配器具体类的少数引用（实测 ≤2 处/类）。

### 1.3 风险 / 回滚
- 风险：**极低**（纯包移动、零行为变更）。唯一风险点 = 包私有可见性/组件扫描（已确认无）。
- 回滚：`git checkout` 或反向移动。

---

## 2. RM6 端口契约去实体（批 1，S）

### 2.1 现状（实测）
`TaskPlannerPickerPort.pickForTask(Long): Agent` 返回 **agent 域实体**；端口在 task 域、由 planner 域 `PlannerAgentPicker` 实现。main 侧经端口消费 **仅 1 处**：`TaskFinalReportServiceImpl:227`（`Agent planner = plannerPickerPort.pickForTask(taskId)`，用 `getId()`/`getName()`，并传执行服务）。
`PlatformAgentExecutionService` **已有** `executeSync(Long agentId, AgentTask)` 重载 ⇒ 消费方可用 ID 版本。

### 2.2 改法
- 新建值对象 **`PlannerAgentRef`**（record：`id` + `name`，按 RM5 同源诉求的最小子集）。
  - 归属判据（§7.2）：端口在 `task`，由 `planner` 实现 ⇒ 值对象随端口放 **`task/port/`**（消费方定义）。
- 端口签名改 `PlannerAgentRef pickForTask(Long taskId)`；`PlannerAgentPicker` 实现侧映射 `Agent → PlannerAgentRef`。
- `TaskFinalReportServiceImpl` 改用 `ref.id()` 调 `executeSync(Long, ...)`、`ref.name()` 记事件、`planOutlineQuietly` 同步改签名取 `ref`。
- 测试适配：`TaskFinalReportServiceTest`（stub 端口）、`PlannerAgentPickerTest`（实现侧断言改 ref）。

### 2.3 风险 / 回滚
- 风险：低-中（1 端口 + 1 消费方 + 测试；`planOutlineQuietly` 若需 Agent 更多字段需评估）。
- 回滚：单笔 revert。

---

## 3. RM5 前向实体泄漏 61→0（批 2 起，M~L，分轮）

### 3.1 落点（实测 61 行 / 6 域对）
| 域对 | 行数 | 主要文件 |
|---|---|---|
| task → agent.entity | 14（Agent 11 + Team/TeamMember 3） | TaskServiceImpl / SubTaskServiceImpl / TaskFinalReportServiceImpl / TaskPlannerPickerPort / observability / policy 等 |
| review → task.entity | 13 | SubTaskReviewServiceImpl / FinalReportReviewServiceImpl / ReviewServiceImpl / picker / support |
| agent → system.entity | 11 | LlmProvider* / CredentialVault / `McpToolServiceImpl`(通配 import) |
| planner → task.entity | 9 | PlannerDecomposeAsyncServiceImpl / PlannerAnalysisServiceImpl / RequirementClarify* |
| planner → agent.entity | 7 | PlannerAgentPicker / SearchGapAssessor / PromptEnhancer* |
| review → agent.entity | 7 | ReviewerPicker* / SubTaskReviewServiceImpl / support |

### 3.2 改法（§7.2 三判据）
- **只读对方实体字段 → 建读侧契约返回「快照」（record/VO）**，提供方映射器顺带算派生字段（W11）。
- **写对方实体 → 不透明命令端口**（传 ID + 目标值，契约不携带 `@Version`；W10）。
- **判定产出状态变更决策 → 搬提供方；产出对外响应契约 → 留消费方**（W7/W8）。

### 3.3 分轮建议
- **批 2**：`AgentProfileSnapshot` 端口一次收 **25 行**（同源诉求「要 Agent 画像」：task→agent.Agent 11 + planner/review→agent.Agent 14）——收益最大、最内聚。
- **批 3**：`agent → system.entity` 11 行（LLM Provider/Credential 配置读侧契约）。
- **批 4**：`review → task.entity` 13 行（评审读侧快照：SubTask/Task/Attachment/Uncertainty）。
- **批 5**：`planner → task.entity` 9 行 + `task→agent` 余 3 行（Team/TeamMember）。
- 每批独立提交、独立验证（定向单测 + 全量 + 守卫）。

---

## 4. RM9 上帝类拆分（后续专项，L，一类一笔）

### 4.1 现状（实测）

> **行数复测（2026-10-12）**：三类自 2026-10-04 起继续增长 —— `McpToolServiceImpl` 1181 → **1229**、`SubTaskServiceImpl` 1379 → **1559**、`RequirementClarifyServiceImpl` 1558 → **1557**（方法清单同批取证，见 §4.3）。
>
> **★行数复测（2026-10-16）**：`McpToolServiceImpl` **1229 → 1334**（+105，本轮唯一变化）；`SubTaskServiceImpl` **1559** 与 `RequirementClarifyServiceImpl` **1557** 均**不变**。
> **★连带影响（开工前必读）**：§4.3 的**方法行号锚点是 2026-10-12 快照**。实测 `McpToolServiceImpl` 的 24 个锚点**已全部漂移且非均匀** —— 前段（`pullTasks`~`checkIn`）**+17**、`mergeReportedSkills` **+36**、后段（`checkOut`~`parseInboxId`）**+105**。⇒ **开工前必须按当前文件重取该表全部行号**（勿按旧锚点定位）。另两类行数未变 ⇒ 推定锚点未漂移，同批复核即可。
> **★RM9-a 已执行（2026-10-16）**：上述「开工前须重取锚点」已**按当前 1334 行文件执行完毕**（重取结果见 §4.3.1 的「★锚点重取」块），并按切分线完成 `McpToolServiceImpl` 拆分 —— 原类 **1334 → 409 行**（薄门面）+ 三个包内职责类：`McpTaskClaimService` **411** / `McpAgentReportService` **489** / `McpReadModelService` **327**。`SubTaskServiceImpl` / `RequirementClarifyServiceImpl` **本笔未动**。执行结果与验证见 §7.5。
>
> **★★行数复测（2026-10-16 二次，RM9-b 开工前）—— 订正上一条的「不变」结论**：上条记「`SubTaskServiceImpl` **1559** 不变」**已过期**。实测 **1592**（+33）—— 增长来自**本日 `RM9-a` 之前的 `BLOCKED` 自动重派笔**（新增 `listStaleBlockedSubTaskIds` / `clearManualIntervention` 两个方法），即**同一批工作日内**的变化，故上条取证时点早于该笔。`RequirementClarifyServiceImpl` 仍 **1557**（未变）。
> **★连带影响（RM9-b 开工前必读）**：`§4.3.2` 的锚点同样是 **2026-10-12 快照**，「行数未变 ⇒ 推定锚点未漂移」的推定对 `SubTaskServiceImpl` **已不成立**。实测其锚点**非均匀漂移**：前段（`requiredSkillsOf`~`changeStatus`、`claim`~`complete`）**+0**（`changeStatus:331` / `claim:392` / `submit:404` / `complete:410` 均**未漂移**）；自 `pause` 之后逐步漂移（`block:1036` vs 旧 1008、`cancel:1072` vs 1044、`assignNext:1083` vs 1055、`resetToPendingForDispatch:1108` vs 1080、`reassign:1221` vs 1193、`listReviewOrphans:1254` vs 1226、`claimAtomic:1332` vs 1304、`listPausedBefore:1380` vs 1352、`listTimedOutAssigned:1388` vs 1360、`incrementExternalFallbackCount:1393` vs 1365、`mergeSkills(subTaskId):1450` vs 1417、`claimConstraint:1468` vs 1435、`saveDrafts:1514` vs 1481、`updateDeadline:1530` vs 1497、`listDraftsInInsertOrder:1567` vs 1534、`listViewsByIds:1589` vs 1556）。⇒ **RM9-b 仍须按当前文件全部重取**（重取结果见 §4.3.2 的「★锚点重取」块）。
> **★沉淀**：「**行数未变 ⇒ 推定锚点未漂移**」这条推定**在同一工作日内的多笔也适用失败** —— 任何一笔新增方法都会同时破坏「行数不变」与其派生推定；故**锚点重取只能以「开工那一刻的文件」为准**，不得继承任何历史结论。
>
> **★行数复测（2026-10-16 三次，RM9-b 交付后）**：`SubTaskServiceImpl` **1592 → 551**（薄门面），业务实现迁入 **4** 个包内 `@Service`（`SubTaskStateMachineService` **655** / `SubTaskQueryService` **380** / `SubTaskNotificationService` **357** / `SubTaskLeaseService` **195**）+ 1 个包内映射器（`SubTaskProjectionMapper` **78**）= **2216** 行。`McpToolServiceImpl` **409**（RM9-a 后未变）、`RequirementClarifyServiceImpl` **1557**（仍未变）。执行结果与验证见 §7.6。
> **★行数复测（2026-10-17 四次，RM9-c 交付后）**：`RequirementClarifyServiceImpl` **1557 → 320**（薄门面），业务实现迁入 **5** 个实现类（`ClarifyRoundOrchestratorServiceImpl` **438** / `ClarifyPromptRenderServiceImpl` **367** / `ClarifyCommandSearchServiceImpl` **215** / `ClarifyStreamServiceImpl` **210** / `ClarifyFinalizeServiceImpl` **196**）+ **5** 个接口（`ClarifyRoundOrchestratorService` 69 / `ClarifyCommandSearchService` 64 / `ClarifyPromptRenderService` 38 / `ClarifyFinalizeService` 36 / `ClarifyStreamService` 27）。另：`McpToolServiceImpl` **409**（RM9-a 后未变）、`SubTaskServiceImpl` **551**（RM9-b 后未变）。执行结果与验证见 §7.7 ⇒ **`RM9` 三笔 3/3 收官**。
>
> **★命名整改连带对照（2026-10-17，`D-2026-10-17-2` / `LOG-20261017-002`）**：本计划 §4.3 的目标名中 `ClarifyPromptRenderer` **已按用户裁定改为接口 `ClarifyPromptRenderService` + 实现 `ClarifyPromptRenderServiceImpl`**（口径 =「接口加 `Service` 后缀（严格字面）」）；§7.2~§7.6 行文内出现的 4 个映射器**已迁出 `service.impl` 并去 `*Mapper` 后缀** —— `SubTaskProjectionMapper` → `task.projection.SubTaskProjection`、`SubTaskSnapshotMapper` → `task.projection.SubTaskSnapshotProjection`、`AgentProfileSnapshotMapper` → `agent.projection.AgentProfileSnapshotProjection`、`LlmProviderProfileMapper` → `system.projection.LlmProviderProfileProjection`；`InFlightDbQuotaService` → `agent.service.impl.ConcurrencyQuotaServiceImpl`。**历史笔次的行文一律不改**（只给当前名对照）。
>

| 类 | 行数(10-12) | 方法数 | 主要职责（§9.1 判据：两个以上即应拆） |
|---|---|---|---|
| `McpToolServiceImpl` | 1229 | 40 | MCP 工具集（pull/ack/claim/start/heartbeat/upload/submit/block/checkIn/checkOut/status/deps/detail）+ 私有辅助 |
| `SubTaskServiceImpl` | 1559 | 79 | 状态机流转 + 认领/租约 + rework 预算 + inbox 通知 + 统计 + 批量创建 |
| `RequirementClarifyServiceImpl` | 1557 | 97 | 会话 CRUD + 澄清轮次 + chat/clarify 模式切换 + Web Search + prompt 渲染 + 命令解析 |

### 4.2 改法

- 按 **§9.1 业务职责**拆分（**不按方法数量**）；§9.2 禁止拆成互相转发的 `FooHelper/FooManager`。
- **一类一笔提交**；执行顺序：`McpToolServiceImpl`（协议门面 / 任务认领 / 上报 / 只读组装四簇最清晰）→ `SubTaskServiceImpl` → `RequirementClarifyServiceImpl`。
- **保留原类名作薄门面**（`implements` 接口不变）⇒ 外部调用方与 Spring 注入点**零改动**，新增职责类为包内 `@Service`，门面逐方法委托。这使「一类一笔」的每一笔都能**独立编译 + 独立回归 + 单笔 revert**。
- ✅ **目标类名与切分线已确认**（架构动作，规约 §42；**2026-10-12 用户裁定：全部接受**）⇒ 下列 `4.3` 为**定稿**，可开工（一类一笔）。

### 4.3 切分线（**已定稿**，2026-10-12 逐类取证 + 用户裁定全部接受）

> 取证口径：`grep -nE "public |private |protected "` 取方法清单 + 字段依赖表；职责簇按**调用链内聚度**归并（同一入参族 / 同一状态机阶段 / 同一外部副作用面）。

#### 4.3.1 `McpToolServiceImpl`（1229 行 = 2026-10-12 取证快照；**★已执行 2026-10-16（RM9-a）** —— 拆分前实测 **1334** 行、锚点已按当时文件重取，见本小节「★锚点重取」块）→ 门面 + 3 职责类

> **表内行号 = 2026-10-12 快照（历史原文，不重写）**；RM9-a 实施所用的重取锚点在表后「★锚点重取」块。

| 目标类 | 承接方法（行号） | 内聚依据 |
|---|---|---|
| `McpToolServiceImpl`（**保留，薄门面**） | 全部 13 个 `@Tool` 入口**逐方法委托**；保留私有守卫 `assertAgentActive:1161` / `assertToolEnabled:1171` / `refreshDutyLease:1193,1203` / `parseInboxId:1217` | 守卫是**横切**（每个工具入口都调），且 `@Tool` 协议面必须保持单一注册点 |
| `McpTaskClaimService` | `pullTasks:114,126` · `ack:198` · `claimSubTask:228` · `startSubTask:375` + `recordClaimStarted:444` · `recordClaimRejected:469` | 「任务获取 → 确认 → 认领 → 开工」是**同一条前置链**，共享 `subTaskQueryPort` / `subTaskCommandPort` |
| `McpAgentReportService` | `heartbeat:496` · `uploadArtifact:547` · `submitResult:576` · `reportBlocked:662` · `checkIn:712,718` · `checkOut:805` · `getAgentStatus:841` + `mergeReportedSkills:760` | 「Agent 上报自身状态与结果」——共享 `heartbeatService` / `agentDutyLeaseService` / `agentInboxService` |
| `McpReadModelService` | `getDepsSummary:880` · `getSubTaskDetail:1046` + `loadUpstreamContent:995` · `buildSubTaskDetail:1073` · `buildContributors:1103` · `buildAttachmentItems:1132` | **纯只读组装**（零状态变更），共享 `taskRunningSpecPort` / `attachmentPort` |

> 判据：门面**只做委托 + 守卫**，不含业务分支；三职责类之间**互不调用**（无 helper 环）。**★RM9-a 实测具体化**：三类间存在的唯一引用 = `McpTaskClaimService → McpReadModelService.buildSubTaskDetail`（认领成功路径复用餐详情组装），**单向无环** ⇒ 判据的实质 = 「**无环**」而非「零引用」（原「互不调用」表述据此收窄，见下表微调②）。

> **★锚点重取（2026-10-16，RM9-a 开工前必做项的执行结果；口径 = 拆分前的 1334 行文件）**
>
> | 目标类 | 重取锚点（1334 行快照） |
> |---|---|
> | `McpToolServiceImpl`（**保留，薄门面**） | 全部 13 个 `@Tool` 入口**逐方法委托**；保留私有守卫 `assertAgentActive:1266` / `assertToolEnabled:1276` / `refreshDutyLease:1298,1308` |
> | `McpTaskClaimService` | `pullTasks:131,143` · `ack:215` · `claimSubTask:245` · `startSubTask:392` + `recordClaimStarted:461` · `recordClaimRejected:486` + `parseInboxId:1322`（见微调①） |
> | `McpAgentReportService` | `heartbeat:513` · `uploadArtifact:564` · `submitResult:593` · `reportBlocked:679` · `checkIn:729,735` · `checkOut:910` · `getAgentStatus:946` + `mergeReportedSkills:796` · `filterReportedSkillsByCapability:835` · `findPackage:879` |
> | `McpReadModelService` | `getDepsSummary:985` · `getSubTaskDetail:1151` + `loadUpstreamContent:1100` · `buildSubTaskDetail:1178` · `buildContributors:1208` · `buildAttachmentItems:1237` |
>
> **★与旧表的两处实现层微调（已执行，登记供回读；不改职责切分与对外契约）**：
> ① **`parseInboxId` 随 `ack` 迁移**：旧表把它列在门面「保留私有守卫」行（因它与守卫同处 `// helpers` 段），但实测其**唯一调用方是 `ack`**（非横切守卫）⇒ 迁入 `McpTaskClaimService`（私有），门面不再持有。
> ② **`buildSubTaskDetail` 形成单向类间依赖**：旧表已把它归 `McpReadModelService`，而 `claimSubTask` 认领成功路径要复用 ⇒ 抽取后必然产生 `McpTaskClaimService → McpReadModelService`（单向、无环）；这是**按定稿切分线执行的必然结果**，非新增设计。
>
> **★守卫为何留在门面（对 2026-08-23「不继续拆分」声明的正面回应）**：该声明的顾虑之一是「拆分后守卫逻辑被迫复制或跨类回调」。本笔让**门面成为守卫的唯一装配点**（13 个入口各自 `assert* + refreshDutyLease*` 后逐方法委托，职责类零守卫），使守卫既**未复制**、也**无需跨类回调**；顾虑之二「按工具分组拆分只会平移行数」⇒ 本笔**不按工具分组**，改按 `§9.1 业务职责` 切分（认领链 / 上报面 / 只读组装），故不适用。

#### 4.3.2 `SubTaskServiceImpl`（1559 = 2026-10-12 取证快照；**★已执行 2026-10-16（RM9-b）** —— 拆分前实测 **1592** 行、锚点已按当时文件重取，见本小节「★锚点重取」块）→ 门面 + 4 职责类

> **表内行号 = 2026-10-12 快照（历史原文，不重写）**；RM9-b 实施以「★锚点重取」块为准（该块为开工前重取结果，实施时按其定位）。

| 目标类 | 承接方法（行号） | 内聚依据 |
|---|---|---|
| `SubTaskServiceImpl`（**保留，薄门面**） | `extends ServiceImpl<SubTaskMapper, SubTask>` 保留（CRUD / `toEntity` / `toSnapshot` / `toView`）；`requiredSkillsOf:123` · `mergeSkills:141,1417` · `updateDraft:162` · `updateContext:1407` · `claimConstraint:1435` · `updateDeadline:1497` · `createBatch:1200` | MyBatis-Plus 基类能力 + 草稿/技能等**配置面**（低频写） |
| `SubTaskStateMachineService` | `changeStatus:331,337` · `claim:392` · `start:398` · `submit:404` · `complete:410` · `pause:477` · `resume:483` · `rework:792` · `reworkFresh:873` · `block:1002,1008` · `cancel:1044` · `reassign:1193` · `assignNext:1055` · `resetToPendingForDispatch:1080` · `markManualIntervention:973` + `consumeReworkBudget:838` · `recordReworkStartedSafely:925` · `invalidateAttachmentsOnRework:950` | **状态迁移唯一入口**（写入 + `AgentEventRecorder` 埋点）；是「第二状态机」风险的**单点收敛位** |
| `SubTaskNotificationService` | `sendReworkInboxNotification:492` · `buildReworkSummary:513` · `formatMissingEvidence:554` · `sendApprovedInboxNotification:581` · `buildApprovedSummary:600` · `clip:620` · `sendInboxNotification:630` · `buildUncertaintyLines:731` · `notifyAgentHandover:762` · `publishAssignmentEvent:782` | **出站副作用面**（inbox 投递 + 摘要渲染 + 领域事件），零状态机依赖 |
| `SubTaskLeaseService` | `renewCurrentNodeLeases:1100` · `reclaimExpiredLeases:1126` · `claimAtomic:1304` · `incrementExternalFallbackCount:1365` · `listPausedBefore:1352` · `listTimedOutAssigned:1360` · `listReviewOrphans:1226` + `listReviewOrphanViews:1456` | **租约 / 回收 / 巡检**（调度侧只读候选 + 原子认领），共享 `watchdogProperties` / `concurrencyQuotaService` |
| `SubTaskQueryService` | `list:209` · `listAvailable:231` · `filterReady:246` · `listMine:271` · `getByIdForUpdate:288` · `isReady:296` · `getView:1395` · `listViewsByTaskId:1449` · `listViewsByTaskIdAndStatus:1491` · `listViewsByIds:1556` · `listDraftsInInsertOrder:1534` · `saveDrafts:1481` · `countByStatusForAgent:1257` 等全部 `count*` / `listViews*` / `listInFlight*` | **只读查询与视图投影**（无状态变更），是三类中最大且最安全的一簇 |

> **★锚点重取（2026-10-16，RM9-b 开工前的预检结果；口径 = 当前 1592 行文件）**
>
> | 目标类 | 重取锚点（1592 行快照） |
> |---|---|
> | `SubTaskServiceImpl`（**保留，薄门面**） | `extends ServiceImpl<SubTaskMapper, SubTask>` 保留；`requiredSkillsOf:123` · `mergeSkills(SubTask):141` · `updateDraft:162` · `create:198` · `updateDependsOn:313` · `createBatch:1228` · `updateContext:1440` · `mergeSkills(id):1450` · `claimConstraint:1468` · `updateDeadline:1530` + `toSnapshot:1421` · `toView:1494` · `toEntity:1540` |
> | `SubTaskStateMachineService` | `changeStatus:331,337` · `claim:392` · `start:398` · `submit:404` · `complete:410` · `pause:477` · `resume:483` · `rework:792` · `reworkFresh:873` · `markManualIntervention:973` · `clearManualIntervention:1008` · `block:1030,1036` · `cancel:1072` · `assignNext:1083` · `resetToPendingForDispatch:1108` · `reassign:1221` + `consumeReworkBudget:838` · `recordReworkStartedSafely:925` · `invalidateAttachmentsOnRework:950` |
> | `SubTaskNotificationService` | `sendReworkInboxNotification:492` · `buildReworkSummary:513` · `formatMissingEvidence:554` · `sendApprovedInboxNotification:581` · `buildApprovedSummary:600` · `clip:620` · `sendInboxNotification:630` · `buildUncertaintyLines:731` · `notifyAgentHandover:762` · `publishAssignmentEvent:782` |
> | `SubTaskLeaseService` | `renewCurrentNodeLeases:1128` · `reclaimExpiredLeases:1154` · `listReviewOrphans:1254` · `claimAtomic:1332` · `listPausedBefore:1380` · `listTimedOutAssigned:1388` · `incrementExternalFallbackCount:1393` + `listReviewOrphanViews:1489` |
> | `SubTaskQueryService` | `list:209` · `listAvailable:231` · `filterReady:246` · `listMine:271` · `getByIdForUpdate:288` · `isReady:296` · `countByStatusForAgent:1285` · `countByAssignedAgent:1309` · `countReviewByReviewerAgent:1315` · `selectInFlightByAgent:1338` · `countInFlightByAgent:1343` · `countInFlightAll:1348` · `existsInFlightAssignedOrInProgress:1364` · `listInFlightAssignedOrInProgress:1372` · `getView:1428` · `listViewsByTaskId:1482` · `listViewsByTaskIdAndStatus:1524` · `listDraftsInInsertOrder:1567` · `listViewsByIds:1589` · `countByTaskIdExcludingStatus:1577` · `countByTaskIdAndStatus:1583` · `saveDrafts:1514` |
>
> **★旧表未列、需在 RM9-b 归位的「后增方法」（自 2026-10-12 快照后新增，共 3 类）**：
> ① **`RM5/RM9-a` 之前的存量补充**：`create:198` · `updateDependsOn:313` · `listRecentlyChanged:1270` · `unlinkByAssignedAgent:1323` · `physicalDeleteByTaskId:1354` · `listPendingUnassignedWithoutActiveExecutionRecord:1398` · `listStalePendingWithoutExecutionRecord:1403` · `countByTaskIdExcludingStatus:1577` · `countByTaskIdAndStatus:1583` —— 按职责归位（`create`/`updateDependsOn` 属门面配置面或状态机；`list*`/`count*` 属 `SubTaskQueryService`；`unlinkByAssignedAgent`/`physicalDeleteByTaskId` 属删除/解绑面）。
> ② **本日 `BLOCKED` 自动重派笔新增 2 个**：`clearManualIntervention:1008`（归状态机，与 `markManualIntervention` 对称）· `listStaleBlockedSubTaskIds:1408`（归 `SubTaskQueryService`，只读巡检候选）。
> ③ **★RM9-b 的验收口径在 `§4.5` 基础上再加两条**：**(i) 状态机类不得反向依赖查询类**（否则成环，`§4.5` 风险提示已点名）；**(ii) 「状态迁移唯一入口」的语义不得被稀释** —— 拆分后所有状态写入仍须经 `SubTaskStateMachineService`，不得因拆分出现第二条写状态路径。
>
> **★与旧表的四处实现层微调（已执行，登记供回读；不改职责切分与对外契约）**
> ① **映射助手随调用方外置**：原两个 `private static`（`toView` / `toEntity`）无法被新建职责类复用（**Java 静态成员不可跨类继承**），故外置为同包映射器 `SubTaskProjectionMapper`（与既有 `SubTaskSnapshotMapper` 同款「映射集中一处」模式），三处调用方（查询簇 / 租约巡检 / 门面 `saveDrafts`）共用一份口径。`toSnapshot` 一并改由状态机类内私有包装 `SubTaskSnapshotMapper.toSnapshot`。
> ② **`requiredSkillsOf` / `mergeSkills` / `claimConstraint` 归 `SubTaskQueryService`**（旧表列在门面行）：三者均为**纯只读派生**（读 Task 级 `required_skills` ∪ 子任务级、读 `agent_policy` 白名单），按 §9.1「只读查询」归位本簇；且 `SubTaskNotificationService` 构造 `assigned` 摘要需 `mergeSkills`，若留门面会形成**「门面 ⇄ 通知」构造器环**，故该归位是**保证无环的必要项**而非偏好。
> ③ **`saveDrafts` 留门面**（旧表列在查询簇）：它是**批量插入（写）**，与查询簇「只读查询与视图投影」的定位自相矛盾，且依赖 MyBatis-Plus 基类能力 `saveBatch`（`§4.3.2` 门面行本就保留 `extends ServiceImpl` 供 CRUD）⇒ 按「写归门面、读归查询」归位。
> ④ **「状态迁移唯一入口」加固（口径 ii 的落地）**：`claimAtomic`（SQL 级 `PENDING → ASSIGNED`）与 `reclaimExpiredLeases` 的**状态写入半段**（`IN_PROGRESS → PENDING` + 清 owner/lease）从租约簇移入 `SubTaskStateMachineService`（后者以新增的 `reclaimToPending(SubTask)` 承接，租约类只保留「挑候选 + 回收副作用编排」）⇒ 拆分后 `sub_task.status` 的**全部**写入仍集中在状态机类内（含跨类调用与 SQL 级原子写），无第二条写状态路径。
>
> **★依赖方向（无环，口径 i 的落地）**：`门面 → 状态机 → 通知 → 查询`（单向链）+ `门面 → 查询` + `门面 → 租约 → 状态机`；**状态机类不依赖查询类**（行读取一律经同域 `SubTaskMapper` 直读，以保住「读现状 + 紧接写入」的同事务 / 同 `@Version` CAS 语义）。

#### 4.3.3 `RequirementClarifyServiceImpl`（1557 行）→ 门面 + 5 职责类

| 目标类 | 承接方法（行号） | 内聚依据 |
|---|---|---|
| `RequirementClarifyServiceImpl`（**保留，薄门面**） | 会话 CRUD（`create:225,237,272` · `listConversations:746` · `detail:755` · `abandon:661` · `delete:675` · `requireActive:1395`）+ 模式切换（`switchToClarify:697,711` · `switchToChat:731` · `isClarifyMode:1269` · `isChatMode:1274`）+ `listPlannerOptions:526` | 会话生命周期与模式开关（**入参最浅、无 LLM 语义**） |
| `ClarifyRoundOrchestrator` | `sendMessage:282,295` · `retryRound:348` · `doRound:776,780` · `runRoundCore:812` · `runFinalizeLlmRound:876` · `runLlmRound:1285` · `makeRoundDecision:1028` · `applyClarifyDecision:1071` · `runClarifySearchRound:827` | **轮次编排主链**（读状态 → 决策 → LLM → 落库） |
| `ClarifyStreamService` | `streamRound:372` · `runStreamRound:382` · `runClarifyStream:451` · `playClarifyQuestion:497` · `streamErrorMessage:515` + 常量 `CLARIFY_PLAY_CHUNK_SIZE:84` / `CLARIFY_PLAY_DELAY_MS:87` | **SSE 流式面**（`Flux<ChatStreamEvent>`），与同步链零耦合 |
| `ClarifyFinalizeService` | `finalize:558` · `finalizeAfterDraft:535` · `casFinalize:570` · `regenerate:588` · `buildTaskFromDraft:613` · `updateFinalDraftFields:1415` | **成任务链路**（终稿 → `Task`，含 CAS 幂等） |
| `ClarifyCommandSearchService` | `isPlannerCommand:1216` · `isTaskCommand:1226` · `commandMatches:1231` · `plannerCommandExtra:1243` · `taskCommandExtra:1257` · `runTaskDirectRound:850` · `resolveChatSearchOutcome:1090` · `doChatWebSearch:1116` · `resolveSearchSource:1169` · `isWebSearchEnabled:1189` · `SearchPolicy:1195` · `resolveSearchPolicy:1205` | **命令解析 + Web Search 决策**（纯函数 + 已有 `ClarifyWebSearchOrchestrator` 依赖） |
| `ClarifyPromptRenderer` | `renderPrompt:1443` · `renderDecisionPrompt:1126` · `applyDescriptionSectionGuard:941` · `isFinalMissingSections:987` · `hasAllDescriptionSections:993` · `recordDescriptionSectionWarn:1010` + 常量 `DESCRIPTION_SECTION_KEYWORDS:135` / `DESCRIPTION_SECTION_RETRY_HINT:142` / 各 `*_TEMPLATE_PATH` | **模板渲染 + 描述段守卫**（纯函数，无状态写入） |

### 4.4 迁移顺序与批次（一类一笔）

```text
RM9-a  McpToolServiceImpl     → 门面 + 3 类（最小、协议面单一，先做以验证手感）  ✅ 已完成（2026-10-16，见 §7.5）
RM9-b  SubTaskServiceImpl     → 门面 + 4 类（含状态机单点收敛，风险最高）        ✅ 已完成（2026-10-16，见 §7.6）
RM9-c  RequirementClarifyServiceImpl → 门面 + 5 类（最大，纯编排无跨域副作用）  ✅ 已完成（2026-10-17，见 §7.7）
```

> **★2026-10-17 命名整改（`D-2026-10-17-2`）**：本序列三笔的产物命名已按 `CODE_STYLE §8.1`/`§17` 归位 —— ① 所有拆出的职责类在 `{domain}.service.impl` 内**一律以 `{接口名}ServiceImpl` 命名**（并**补齐接口**置于 `{domain}.service`）；② 4 个纯静态投影助手**迁出** `service.impl` 到新建 `{domain}.projection` 包、去 `*Mapper` 后缀。整改后 `{domain}.service.impl` 下**非 `*ServiceImpl` 文件 = 0**。详见 §7.7 末段与 `LOG-20261017-002`。

**每笔内部子序**：① 先抽**只读簇**（`McpReadModelService` / `SubTaskQueryService` / `ClarifyPromptRenderer`、`ClarifyCommandSearchService`）⇒ 零行为变更、易验证；② 再抽**副作用簇**（通知 / 流式 / 租约）；③ 最后抽**状态机 / 编排簇**（风险最高）。

### 4.5 验收与回滚（每笔必做）

| 层级 | 手段 |
|---|---|
| 编译 | `JAVA_HOME=ms-17.0.20.1 mvn -o -B -pl helloai-core -am test-compile`（**不 clean**，应用在跑） |
| 定向单测 | 三类各自的 `*Test` + 状态机 / 租约 / SSE 相关测试 |
| 全量单测 | `mvn -o -B -DskipTests=false test` 7 模块 BUILD SUCCESS；`<testcase>` 数**不低于**本笔前基线 |
| 架构守卫 | `bash scripts/ci/check-arch-freeze.sh` EXIT=0；组 1/2 基线不动（拆分**不得**新增跨域前向 import） |
| 行为等价 | 契约型方法补「等价性」用例（`SubTaskView` 投影字段逐字一致） |
| 回滚 | **单笔 revert**（保留门面 ⇒ 无外部调用方改动，revert 即回原状） |

> **风险提示**：`SubTaskServiceImpl` 是**状态机唯一入口**（`changeStatus` 被全链调用），拆分时**不得**让状态类反向依赖查询类（会造环）；`RequirementClarifyServiceImpl` 的 `doRound` / `runRoundCore` 存在重载对，抽取时须**整组迁移**。

---

## 5. 统一验证方案（每批必做）

| 层级 | 手段 |
|---|---|
| 编译 | `JAVA_HOME=ms-17.0.20.1 mvn -o -B -DskipTests=false -Dtest=... test` |
| 定向单测 | 受影响类的 `*Test`，`<testcase>` 元素口径数 |
| 全量单测 | `mvn -o -B -DskipTests=false test`，7 模块 BUILD SUCCESS |
| 架构守卫 | `bash scripts/ci/check-arch-freeze.sh` → 组 1/2 EXIT=0；组 3 计数下降 |
| 行为等价 | 涉及返回值/协议变更的，补「等价性」用例锁定逐字一致 |
| PS1 / E2E | 按规约 §27 如实标注 PASS / NOT RUN / BLOCKED |

## 6. 回滚方案
- 每批**独立提交**；任一批出问题单笔 `git revert`，不影响其他批。
- 纯包移动（RM8）可直接反向移动。

---

## 7. 本计划执行状态

| 批次 | 内容 | 状态 |
|---|---|---|
| 批 1 | RM8（迁 9 适配器）+ RM6（`PlannerAgentRef`） | ✅ **已完成（2026-10-04）** |
| 批 2 | RM5：`AgentProfileSnapshot` 收 25 行 | ✅ **已完成（2026-10-04）** |
| 批 3 | RM5：agent→system 11 行 | ✅ **已完成（2026-10-04）** |
| 批 4 | RM5：review→task 13 行 | ✅ **已完成（2026-10-04）** |
| 批 5 | RM5：planner→task 9 行 + task→agent 余 3 行 | ✅ **已完成（2026-10-04）**：5b（`task→agent.entity` 3→0）+ **5a**（`planner→task.entity` 9→0）⇒ **RM5 前向实体泄漏六项全部归零（61→0）** |
| 专项 | RM9：McpToolServiceImpl → SubTaskServiceImpl → RequirementClarifyServiceImpl | 🟡 **进行中（2/3 笔）**：**RM9-a `McpToolServiceImpl` ✅ 已完成（2026-10-16，§7.5）**；**RM9-b `SubTaskServiceImpl` ✅ 已完成（2026-10-16，§7.6）**；`RequirementClarifyServiceImpl` ⬜ 待办（开工前须重取 `§4.3.3` 锚点） |

### 7.1 批 1 执行结果（2026-10-04）

**RM8（适配器包卫生）**
- 9 个跨域适配器迁包：task 侧 8 个 → `task/adapter/`；review 侧 1 个（`ReviewPortAdapter`）→ `review/adapter/`。
- 6 个测试随迁（`ArtifactReferencePortAdapterTest` / `AttachmentPortAdapterTest` / `SubTaskCommandPortAdapterTest` / `SubTaskQueryPortAdapterTest` / `SubTaskReviewContextPortAdapterTest` / `TaskRunningSpecPortAdapterTest`）。
- 连带改动（跨包最小必要）：`SubTaskSnapshotMapper` 由 `final class` + 包私有方法 → `public final class` + 3 静态方法 `public`（它**不迁入** adapter 包：同时服务端口适配器与 `SubTaskServiceImpl` outbox，非纯 adapter 概念）。

**RM6（端口契约去实体）**
- 新增值对象 `PlannerAgentRef`（record `id`+`name`），随端口放 `task/port/`。
- `TaskPlannerPickerPort.pickForTask(Long)` 返回 `Agent` → `PlannerAgentRef`；`PlannerAgentPicker` 加 `toRef(Agent)` 映射。
- 消费方 `TaskFinalReportServiceImpl` / `PlannerDecomposeAsyncServiceImpl`：`planner.getId()/getName()` → `planner.id()/name()`；`executeSync(planner, ...)` → `executeSync(planner.id(), ...)`（复用既有 `Long` 重载）。**注意**：`PlannerDecomposeAsyncServiceImpl:142` 原调用 `executeSync(planner, agentTask)`（`Agent` 重载），RM6 后必须一并对齐，否则不编译。
- `TaskIterationService.backfillForTask(Long, List<SubTask>, Agent plannerAgent)` → `(..., Long plannerAgentId)`（**W6**：该参仅用于日志关联，不写入记录；跨域形参优先取 ID）。
- 测试适配：`TaskFinalReportServiceTest`（stub 端口 → `PlannerAgentRef`；`executeSync` matcher → `anyLong()`）、`PlannerAgentPickerTest`（实现侧断言改 `.id()`）、`PlannerDecomposeAsyncServiceImplTest`（`llmPlanner()` → `PlannerAgentRef`；`executeSync` matcher → `anyLong()`；`cliExecutor()` 保留 `Agent`——它喂 `agentService.listByIds`，属 RM5 范畴）。

**验证（批 1）**
| 层级 | 结果 |
|---|---|
| 编译 | `mvn -pl helloai-core -am test-compile`（**不 clean**，应用在跑）SUCCESS |
| 定向单测 | `PlannerDecomposeAsyncServiceImplTest` + `TaskFinalReportServiceTest` + `PlannerAgentPickerTest` + `AgentEventForkServiceTest` = **88 例 0 失败** |
| 全量单测 | `mvn -DskipTests=false test` **7 模块 BUILD SUCCESS**；`<testcase>` **1927** 例 0 failure/error（core 1752 / api 77 / job 85 / start 13） |
| 架构守卫 | `check-arch-freeze.sh` **EXIT=0**；`task→agent.entity` **14 → 11（−3）**、域级 `task→agent` **58 → 55（−3）**（与 RM6 移除的 3 个 import 站点逐一对应） |
| PS1 / E2E | **NOT RUN** |

> **★用例口径陷阱（本轮实证）**：RM8 类名迁包后，`target/surefire-reports/` 内旧包 `TEST-com.helloai.core.task.service.impl.*PortAdapterTest.xml` 成为**过期残留**（另计 **58** 例），裸 `grep -c "<testcase "` 会虚高为 **1985**。**须先删过期 XML 或按「本次已建集合」截断重建，再数 `<testcase>`**——否则用例数口径失真。

### 7.2 批 2 执行结果（2026-10-04）

**目标**：把 `planner/review/task` 三域对 `agent.entity.Agent` 的 **22 个 import 站点**收口为提供方读侧快照，`Team`/`TeamMember` 3 处留批 5。

**契约（提供方 agent 域，§7.2 情形②）**
- 新增 `agent/port/AgentProfileSnapshot.java`：`@Builder record`，9 字段 `id/name/role/accessType/status/onlineStatus/score/modelType/localExecutionCapable`；字段并集 = 22 站点**实际读取项**。
- `localExecutionCapable` 为 **W11 提供方派生字段**（`= AgentCapability.hasLocalExecutionCapability(entity)`，null 视为 true），消费方零判定。
- 新增映射器 `agent/service/impl/AgentProfileSnapshotMapper`（`toSnapshot`/`toSnapshots`，提供方侧，镜像 `SubTaskSnapshotMapper`）。

**提供方扩展（不新建端口/适配器 ⇒ 前向计数不 +1）**
- `AgentService` 扩 5 个只读方法：`getProfileById` / `listProfilesByIds` / `listProfilesByRole` / `listActiveProfiles` / `listProfilesOrderByScoreDesc`；实现 = 既有查询 + 映射，零新增 Mapper/SQL/DB 往返。
- agent 域内伴随重载 4 处：`AgentSelector.pickPreferredProfile(AgentRole)`（**方案 A**，只增不改）、`AgentLlmCredentialResolver.hasUsableCredential(S)`、`AgentProviderResolver.resolveProvider(String,String)`、`PlatformAgentExecutionService.executeStream(Long,AgentTask)`。

**22 站点改写**：task 8 / planner 7 / review 7，空值语义逐字保留；`RequirementClarifyService`（接口）的 import 实测未用 ⇒ 直接删（零行为变更）。

**★高风险点（已按等价口径实现 + 用例锁定）**
- `SubTaskReviewServiceImpl`：原 `AgentCapability.hasLocalExecutionCapability(submitter)`（**null ⇒ true ⇒ 不跳过**）改写为 `submitter != null && !submitter.localExecutionCapable()`；写成 `== null || !…` 会**反向误跳过**。
- `PlannerAgentPicker.pick(Long)` 返回类型改快照，`validateSelectable`/`isUsable` 四道校验逐字保留。
- **22 站点对 Agent 实体零写** ⇒ 本批不引入不透明命令端口。

**验证（批 2）**
| 层级 | 结果 |
|---|---|
| 编译 | `mvn -pl helloai-core -am test-compile`（**不 clean**）SUCCESS |
| 全量单测 | `mvn -DskipTests=false test` **7 模块 BUILD SUCCESS**；`<testcase>` **1916** 例 0 failure/error（core 1754 / api 77 / job 85 / start 0） |
| 架构守卫 | **EXIT=0**：`planner→agent.entity` **7→0**、`review→agent.entity` **7→0**、`task→agent.entity` **14→3**；已 `--update-baseline` |
| 等价性用例 | `SubTaskReviewServiceTest` +2 态（提交者缺失→不跳过 / 无本机能力→跳过）；净 +1 例 |
| PS1 / E2E | **NOT RUN** |

> **★用例口径再订正（新环境事实）**：`cat *.xml \| grep -o '<testcase '` 在 Windows Git Bash 下因**命令行长度上限静默截断**（实测 1904 vs 1916，差 12）。**必须用 `find … -print0 \| xargs -0 grep -ho '<testcase '`** 或「跑完即 `cp` 快照再数」。另：`helloai-start` 的 `*IT`/`*PhaseBIT` **不由 `mvn test` 执行**，稳定贡献 **0** 例（此前 13 例为历史手工运行残留 XML）。

### 7.3 批 3 执行结果（2026-10-04）

**目标**：`agent → system.entity` 11 个 import 站点归零。

**契约（提供方 system 域，§7.2 情形②）**
- 新增 `system/port/LlmProviderProfile`（`providerCode`/`providerName`/`baseUrl`/`defaultModel`/`protocolType`/`enabled`）、`LlmProviderModelProfile`（`capabilitySkills`/`availableOptionalSkills`）、`CredentialSecret`（`encryptedValue`/`secretRef`）。
- 新增提供方映射器 `system/service/impl/LlmProviderProfileMapper`（`toProfile`/`toProfiles`）。

**★「只新增不改」的强制约束**
`LlmProviderQueryService` / `LlmProviderModelQueryService` / `CredentialVaultService` 被 **`api` 层 `AdminLlmProviderController` 共用** ⇒ 既有实体返回方法不动，仅加快照方法：
- `LlmProviderQueryService`：`findProfileByCode` / `listEnabledProfiles` / `listAllProfiles`
- `LlmProviderService`：`getProfileById`
- `LlmProviderModelQueryService`：`findCapabilityProfileByModelType`
- `CredentialVaultService`：`getActiveAgentApiKeySecret` / `getActivePlatformApiKeySecret`

**消费方改写 10 站点**：`AnthropicCompatibleProtocolFactory` / `OpenAiCompatibleProtocolFactory` / `LlmProviderChatClientFactoryRegistry`（含内嵌 `ProtocolFactory` 接口签名）/ `ExecutorIssueResolutionAssessor` / `AgentSkillPolicyService` / `AgentExecutionConnectivityServiceImpl` / `LlmProviderCatalogServiceImpl` / `LlmProviderKeyVerifyServiceImpl` / `PlatformProviderConfigServiceImpl`；`McpToolServiceImpl` 通配 import **零引用 ⇒ 删除**。

**验证（批 3）**
| 层级 | 结果 |
|---|---|
| 编译 | `mvn -pl helloai-core -am test-compile` SUCCESS |
| 全量单测 | `mvn -DskipTests=false test` **7 模块 BUILD SUCCESS**；`<testcase>` **1916** 例 0 failure/error（core 1754 / api 77 / job 85） |
| 架构守卫 | **EXIT=0**：`agent→system.entity` **11→0**；已 `--update-baseline` |
| 测试适配 | 8 个测试文件（辅助构造改 record + stub 方法名对齐） |
| PS1 / E2E | **NOT RUN** |

> **★操作教训（本轮实证）**：批量脚本替换时**不可在计数不符时 `continue` 跳过**——那会把部分出现处留成旧形态、随类型变更后编译报"找不到符号"。正确做法：先 grep 定位全部出现处的上下文确认为同一变量，再**无条件全局替换**。本轮因该 bug 多花了一轮编译往返。

### 7.4 批 5b 执行结果（2026-10-04）：`task→agent.entity` 3 → 0

**契约（提供方 agent 域，§7.2 情形②）**
- 新增 `agent/port/TeamMemberView(id, agentId, slotRole, weight)`（字段并集 = `TeamPolicyExpander` 实读项）。
- **`Team` 不需要快照 record**：`TaskServiceImpl` 只读 `team.getStatus()` ⇒ 在 `TeamService` 加 `TeamStatus getTeamStatus(Long)`（返回共享常量，语义同 `getTeam`：不存在抛 `BizException`）。

**★「只新增不改」**：`TeamService.getTeam` / `listMembers` 被 `api` 层 `TeamController` 共用 ⇒ 一律不动；只加 `getTeamStatus` + `listMemberViews`（顺序同 `listMembers`）。

**消费方改写**
- `task/policy/TeamPolicyExpander`：`List<TeamMember>` → `List<TeamMemberView>`（`Comparator` / `memberOrder` / 流式 accessor 同步；排序口径 weight desc、id asc 逐字保留）。
- `task/service/impl/TaskServiceImpl.expandAgentPolicy`：改走 `getTeamStatus` / `listMemberViews`。

**验证（批 5b）**
| 层级 | 结果 |
|---|---|
| 编译 | `mvn -pl helloai-core -am test-compile` SUCCESS |
| 定向 | `TaskServiceImplTest`+`TaskServiceTest`+`TeamServiceImplTest`+`TeamPolicyExpanderTest` = **36 例 0 失败** |
| 全量 | **7 模块 BUILD SUCCESS**；`<testcase>` **1916** 例 0 failure/error |
| 守卫 | **EXIT=0**：`task→agent.entity` **3→0**、`task→agent` **54→53**；已 `--update-baseline` |
| PS1 / E2E | **NOT RUN** |

### 7.5 RM9-a 执行结果（2026-10-16）：`McpToolServiceImpl` → 薄门面 + 3 职责类

**切分（按 §4.3.1 定稿，锚点按当前 1334 行文件重取）**

| 类 | 行数 | 承接 |
|---|---|---|
| `McpToolServiceImpl`（**保留原类名，薄门面**） | **409**（原 1334） | 13 个 `@Tool` 入口逐方法委托 + 横切守卫（`assertAgentActive` / `assertToolEnabled` / `refreshDutyLease`×2）；**唯一守卫装配点** |
| `McpTaskClaimService`（新增） | **411** | `pullTasks` / `ack` / `claimSubTask` / `startSubTask` + `recordClaimStarted` / `recordClaimRejected` / `parseInboxId` |
| `McpAgentReportService`（新增） | **489** | `heartbeat` / `uploadArtifact` / `submitResult` / `reportBlocked` / `checkIn` / `checkOut` / `getAgentStatus` + `mergeReportedSkills` / `filterReportedSkillsByCapability` / `findPackage` |
| `McpReadModelService`（新增） | **327** | `getDepsSummary` / `getSubTaskDetail` + `loadUpstreamContent` / `buildSubTaskDetail` / `buildContributors` / `buildAttachmentItems`（纯只读组装） |

**★外部调用方零改动**：`McpToolService` 接口与 `McpToolServiceImpl` 类名均不变（`api/McpController` · `core/mcp/McpMcpServer` · `runtime/tool/InternalLlmExecutorTools` 注入点均为**接口**），故 `git revert` 单笔即回原状。

**★事务边界不变**：原 9 个 `@Transactional(rollbackFor = Exception.class)` 方法（`ack` / `claimSubTask` / `startSubTask` / `uploadArtifact` / `submitResult` / `reportBlocked` / `checkIn`×2 / `checkOut`）的事务注解**留在门面** ⇒ 守卫与业务仍在**同一事务**内（职责类不加 `@Transactional`，随调用方事务传播）。

**★顺序契约（`heartbeat`）**：原实现「`seen()` → `refreshDutyLease(agentId, true)` → 读租约构响应」，响应中的 `remainingTtlSeconds` 语义 = 「**续租后**剩余 TTL」。抽取后门面在**委托前**调 `refreshDutyLease(agentId, true)`，职责类再读租约 ⇒ 该语义**逐字保留**（`seen` 与 `adaptiveRenew` 实测互不依赖：`adaptiveRenew` 只读 ACTIVE 租约 TTL 与在跑子任务，不读 `last_seen_time`）。

**验证（RM9-a）**

| 层级 | 结果 |
|---|---|
| 编译 | ✅ `mvn -o -B -pl helloai-core -am test-compile`（**不 clean**）**BUILD SUCCESS** |
| 定向 | ✅ `McpToolServiceTest`（**54**）+ `InternalLlmExecutorToolsTest`（**14**）= **68 例 0 失败 0 错误** |
| 全量 | ✅ **7 模块 BUILD SUCCESS**；`<testcase>` **2569** 例 / **0 失败 0 错误**（246 个 surefire 报告） |
| ★用例数不变性 | ✅ 本笔**未新增/未删除任何用例**：全仓 `@Test`/`@ParameterizedTest` 计数 **2570 = 2570**（工作树 vs `HEAD`）；唯一改动的测试文件 `McpToolServiceTest` 用例数 **54 = 54**（仅重写 `setUp` 的手工装配）⇒ `<testcase>` 口径**结构性不变** |
| 守卫 | ✅ `scripts/ci/check-arch-freeze.sh` **EXIT=0**；**组 1（反向依赖）/ 组 2（跨域 Mapper）全 0**，组 1/2 基线**未动**（拆分未新增任何跨域前向 import；组 3 三条 warn 为提交前既有漂移，与本笔无关） |
| 行为等价 | ✅ 68 例既有断言**全部通过且断言未改**（含 `claimSubTask` 幂等 / 准入拒绝 / 开工状态白名单 / `getDepsSummary` 降级 / `getSubTaskDetail` 授权 / `checkIn` 技能能力校验等契约型路径） |
| 回滚 | ✅ 单笔 `git revert`（门面保留原类名 ⇒ 外部零改动） |
| PS1 / E2E | **NOT RUN** |

### 7.6 RM9-b 执行结果（2026-10-16）：`SubTaskServiceImpl` → 薄门面 + 4 职责类（风险最高的一笔）

**切分（按 §4.3.2 定稿，锚点按当前 1592 行文件重取）**

| 类 | 行数 | 承接 |
|---|---|---|
| `SubTaskServiceImpl`（**保留原类名，薄门面**） | **551**（原 1592） | 领域写入原语（`updateDraft` / `create` / `createBatch` / `updateDependsOn` / `updateContext` / `updateDeadline` / `saveDrafts` / `unlinkByAssignedAgent` / `physicalDeleteByTaskId`，**均不改 status**）+ 对 4 职责类的**逐方法委托**；`extends ServiceImpl<SubTaskMapper, SubTask>` 保留 |
| `SubTaskStateMachineService`（新增） | **655** | **状态迁移唯一入口**：`changeStatus`×2 / `claim` / `start` / `submit` / `complete` / `pause` / `resume` / `cancel` / `block`×2 / `rework` / `reworkFresh` / `assignNext` / `resetToPendingForDispatch` / `reassign` / `claimAtomic` / `reclaimToPending` / `markManualIntervention` / `clearManualIntervention` + 私有 `consumeReworkBudget` / `recordReworkStartedSafely` / `invalidateAttachmentsOnRework` / `toSnapshot` |
| `SubTaskQueryService`（新增） | **380** | 只读查询与视图投影：`list` / `listAvailable` / `filterReady` / `listMine` / `listRecentlyChanged` / `getByIdForUpdate` / `isReady` / 全部 `count*` / `selectInFlight*` / `listInFlight*` / `listPending*` / `listStale*` / `getView` / `listViews*` / `listDraftsInInsertOrder` + `requiredSkillsOf` / `mergeSkills`×2 / `claimConstraint`（纯只读派生） |
| `SubTaskNotificationService`（新增） | **357** | 出站副作用面：`sendInboxNotification` / `notifyAgentHandover` / `publishAssignmentEvent` / `sendReworkInboxNotification` / `buildReworkSummary` / `formatMissingEvidence` / `sendApprovedInboxNotification` / `buildApprovedSummary` / `clip` / `buildUncertaintyLines` |
| `SubTaskLeaseService`（新增） | **195** | 租约 / 回收 / 巡检：`renewCurrentNodeLeases` / `reclaimExpiredLeases` / `listReviewOrphans` / `listReviewOrphanViews` / `listPausedBefore` / `listTimedOutAssigned` / `incrementExternalFallbackCount` |
| `SubTaskProjectionMapper`（新增，包内映射器） | **78** | `toView(SubTask)` / `toEntity(SubTaskDraft)`（原门面两个 `private static`；**Java 静态成员不可跨类继承**，故外置；同包三处调用方共用一份口径） |

**★外部调用方零改动**：`SubTaskService` 接口与 `SubTaskServiceImpl` 类名均不变（`SubTaskService extends IService<SubTask>`，故 `getById` / `updateById` / `list` 等基类能力仍由门面提供），全部外部注入点（`SubTaskDispatchServiceImpl` / `AgentServiceImpl` / `HeartbeatServiceImpl` / `InFlightDbQuotaService` / `ReviewPortAdapter` / `AttachmentServiceImpl` / job 侧定时任务等）**无感** ⇒ `git revert` 单笔即回原状。

**★「状态迁移唯一入口」未被稀释（§4.3.2 验收口径 ii）**：`sub_task.status` 的全部写入仍集中在 `SubTaskStateMachineService` 内 —— 含**跨类调用**（租约簇的过期回收经 `reclaimToPending`）与 **SQL 级原子写**（`claimAtomic`）。门面自己的写操作**无一改 status**（唯一例外 `create` 在**插入新行**时落初始态 `PENDING`，属插入非迁移）。

**★无环（§4.3.2 验收口径 i）**：`门面 → 状态机 → 通知 → 查询`（单向链）+ `门面 → 查询` + `门面 → 租约 → 状态机`；**状态机类不依赖查询类**（行读取一律经同域 `SubTaskMapper` 直读，保住「读现状 + 紧接写入」的同事务 / 同 `@Version` CAS 语义）。

**★对 2026-08-23「§7.8 选项二不继续拆分」声明的正面回应（声明原文在门面 javadoc 中保留，未删史）**

| 声明的顾虑 | 本笔的处置 |
|---|---|
| ①「状态流转各入口共享同一状态机与事件发布链，拆分会造成**事件序与事务边界在类间漂移**」 | **整条状态流转链原样整体搬迁**入 `SubTaskStateMachineService`：`changeStatus` 的「读现状 → `validate` → 写上下文/租约 → 落库 → outbox → 收件箱 → 交接通知 → 领域事件 → 心跳」**顺序与事务边界一字未改**，仍在同一个类内 ⇒ 漂移风险**未被引入**；门面只在外部入口保留 `@Transactional` 以维持原事务开启位置 |
| ②「已外置的均为无状态工具或独立引擎，剩余部分**无独立可测职责**可继续剥离」 | **该判断据实测不再成立**：被剥离的三簇**各自早已有独立单测**（`SubTaskServiceIsReadyTest` / `...MergeSkillsTest` / `...LeaseReclaimTest` / `...QuotaTest` / `...ReworkBudgetTest` / `...HandoverTest`）⇒ 它们本就具备「独立可测职责」，只是此前与状态机同处一文件 |
| ③（隐含）「按行数机械拆」 | 本笔**不按方法数量**拆，按 §9.1 业务职责切分（只读查询 / 出站副作用 / 租约调度 / 状态迁移）；4 类**互不转发**、方向无环 ⇒ 不落 §9.2 所禁的 `FooHelper`/`FooManager` 形态 |

**验证（RM9-b）**

| 层级 | 结果 |
|---|---|
| 编译 | ✅ `mvn -o -B -pl helloai-core -am test-compile`（**不 clean**）**BUILD SUCCESS** |
| 定向 | ✅ 7 个定向类 **47 例 0 失败 0 错误**：`SubTaskServiceHandoverTest` 16 · `SubTaskServiceMergeSkillsTest` 11 · `SubTaskServiceIsReadyTest` 8 · `SubTaskServiceQuotaTest` 4 · `SubTaskServiceReworkBudgetTest` 4 · `SubTaskServiceLeaseReclaimTest` 2 · `SubTaskServiceUpdateContextTest` 2 |
| 全量 | ✅ **7 模块 BUILD SUCCESS**；`<testcase>` **2569** 例 / **0 失败 0 错误**（246 个 surefire 报告）—— 与 `RM9-a` 本笔前基线**逐数一致** |
| ★用例数不变性 | ✅ 本笔**未新增/未删除任何用例**：`src/test/java` 内 `@Test`/`@ParameterizedTest` 计数 **2598 = 2598**（工作树 vs `HEAD`）；7 个定向类逐文件**用例数全等**（16/11/8/4/4/2/2）⇒ `<testcase>` 口径**结构性不变** |
| 守卫 | ✅ `scripts/ci/check-arch-freeze.sh` **EXIT=0**；**组 1（反向依赖）/ 组 2（跨域 Mapper）全 0**、组 1/2 基线**未动**。组 3 四条 warn 中 **`task->agent` 57→60（+3）为本笔唯一归因**（拆分把 11 条 agent import 从门面重新分布到 3 个职责类共 14 条，净 +3，**均为 §6 合法前向**）；另三条 `planner->agent` 33→34、`api->core.entity` 49→52、`job->core.entity` 14→15 经 `git grep … HEAD` 实测**提交前既有漂移**，与本笔无关 |
| 行为等价 | ✅ 既有 47 例**断言语义逐条保留**且全通过（覆盖依赖门禁 / 技能并集去重保序 / 草案 fail-close 编辑门禁 / 共享重试预算熔断与死信 / 并发额度原子防线 / 租约回收中断点 / 换人撤销通知 / 审查通过与驳回摘要渲染等契约型路径）。**装配口径随实现下沉**：原「spy 门面拦 `getById`/`updateById`/`lambdaQuery()`」改为「直接对同域 `SubTaskMapper` 设桩，或以 spy 职责类记录类内自调用」 |
| 文档守卫 | ✅ `scripts/shell/verify-doc-gap-table.sh` **4/4**（tripwire 仍 **23**：本批**未新增矩阵行**，只在 §0 加决策） |
| 回滚 | ✅ 单笔 `git revert`（门面保留原类名 ⇒ 外部零改动） |
| PS1 / E2E | **NOT RUN** |

### 7.7 RM9-c 执行结果（2026-10-17）：`RequirementClarifyServiceImpl` → 薄门面 + 5 职责类（收官笔）

**决策**：`D-2026-10-17-1`（记录 `LOG-20261017-001`）。**本笔为 RM9 一类一笔序列的最后一笔**（三笔中体量最大：拆分前 **1557 行 / 97 方法**）。切分线已定稿（§4.3.3，2026-10-12 用户裁定「全部接受」）⇒ 只做实施。

**产物**

| 文件 | 行数 | 说明 |
|---|---|---|
| `planner/service/impl/RequirementClarifyServiceImpl.java`（**保留原类名，薄门面**） | **320**（原 1557） | 门面职责：`create`×3 / `sendMessage`×2 / `retryRound` / `streamRound` / `finalize` / `regenerate` / `abandon` / `delete` / `switchToClarify`×2 / `switchToChat` / `listConversations` / `detail` / `listPlannerOptions`（多数为逐方法委托） |
| `planner/service/ClarifyCommandSearchService.java`（新增·接口） | **64** | **叶子契约**（零反向依赖）：命令解析 + 模式谓词 + Web Search 决策；含 `enum SearchPolicy` |
| `planner/service/impl/ClarifyCommandSearchServiceImpl.java`（新增） | **215** | 上述实现 |
| `planner/service/ClarifyRoundOrchestratorService.java`（新增·接口） | **69** | 编排契约（含 `record RoundDecision` + `static degradedDefaults()`） |
| `planner/service/impl/ClarifyRoundOrchestratorServiceImpl.java`（新增） | **438** | 编排簇（`doRound` **重载对整组迁移** + `enterClarify`） |
| `planner/service/ClarifyPromptRenderService.java`（新增·接口） | **38** | 渲染契约 |
| `planner/service/impl/ClarifyPromptRenderServiceImpl.java`（新增） | **367** | 渲染簇（含包内静态 `windowHistory`，供单测直引） |
| `planner/service/ClarifyFinalizeService.java`（新增·接口） | **36** | 成任务契约 |
| `planner/service/impl/ClarifyFinalizeServiceImpl.java`（新增） | **196** | 终稿 → `Task`（含 CAS 幂等） |
| `planner/service/ClarifyStreamService.java`（新增·接口） | **27** | SSE 流式契约 |
| `planner/service/impl/ClarifyStreamServiceImpl.java`（新增） | **210** | `Flux<ChatStreamEvent>` 流式面 |

**★门面 ⇄ 编排器破环（实现层微调，显式登记）**：按原方法归属直接拆会造**构造器环**（门面 `switchToClarify` 需编排器能力；而编排器的「会话激活校验 / 模式谓词」又需门面入口）⇒ `requireActive` / `isClarifyMode` / `isChatMode` 下移**叶子契约** `ClarifyCommandSearchService`；`switchToClarify` 的核心下移编排器为 `enterClarify(conversationId, extraMessage)`，门面保留 `switchToChat`。

**★`renderPrompt` 按模板拆分**：原单入口 `renderPrompt(templatePath)` 对外改为 `renderClarifyPrompt` / `renderChatPrompt` / `renderFinalizePrompt` 三个方法，私有 `renderPrompt(templatePath)` 保留 ⇒ **模板路径常量不外泄**、可测性更好。

**★依赖方向无环**：`门面 → 编排器 → {命令搜索, 渲染, 终稿}` + `编排器 → 状态机` + `流式 → 编排器 / 渲染 / 命令搜索`；`ClarifyCommandSearchService` 为叶子。

**验证**

| 项 | 结果 |
|---|---|
| 编译 | ✅ `mvn -o -B -pl helloai-core -am test-compile`（**不 clean**，应用在跑）**BUILD SUCCESS** |
| 定向回归 | ✅ **105 例 0 失败 0 错误**（`RequirementClarifyServiceTest` 95 / `RequirementPromptTemplateContractTest` 4 / `RequirementClarifyContextWindowTest` 6）—— 与本笔前基线**逐数一致** |
| 全量 | ✅ **7 模块 BUILD SUCCESS**；`<testcase>` **2569** 例 / **0 失败 0 错误** —— 与 `RM9-a`/`RM9-b` 基线**逐数一致** |
| 守卫 | ✅ `scripts/ci/check-arch-freeze.sh` **EXIT=0**（组 1 反向 / 组 2 跨域 Mapper 持平）；`scripts/shell/verify-doc-gap-table.sh` **4/4** |
| 回滚 | ✅ 单笔 `git revert`（接口与类名不变 ⇒ 外部注入点零改动） |

**★命名合规整改（2026-10-17，`D-2026-10-17-2` / `LOG-20261017-002`）**：用户复核后指出三笔产物命名未完全符合 `CODE_STYLE §8.1`/`§17`（`{domain}.service.impl` 内应 `XxxServiceImpl`；`*Mapper` 不该被非 MyBatis 类占用）。经 3 轮问询裁定后执行：① **4 个纯静态投影助手迁出** `service.impl` → 新建 `{domain}.projection` 包 + `XxxProjection` 后缀（`SubTaskProjection` / `SubTaskSnapshotProjection` / `AgentProfileSnapshotProjection` / `LlmProviderProfileProjection`）；② `InFlightDbQuotaService` → `ConcurrencyQuotaServiceImpl`（接口已存在，纯改名）；③ `FinalReportPersistService` → **补接口** + `FinalReportPersistServiceImpl`（消费方改经接口注入）。**收敛判据**：`find … main/java/*service/impl/* -name '*.java' ! -name '*ServiceImpl.java'` = **空**。全量 `<testcase>` **2569** 例 0 失败 0 错误（与基线逐数一致）。

---

## 8. 批 4 / 批 5a 未完成 —— 需先出设计稿（结构性难点）

> **为什么这两批不能照搬批 2/3/5b 的「加个快照 record 就换掉」**：批 4（`review→task.entity` 13）与批 5a（`planner→task.entity` 9）泄漏的是 **`SubTask` / `Task` 这两个「中心工作实体」**——它们不是「查出来读几个字段」的读投影，而是**作为方法形参跨类传递**，且**存在实体写操作**。

**批 4 实测数据**
- 逐站点：`ReviewerPicker`(SubTask) / `ReviewerPickerImpl`(SubTask,Task) / `FinalReportReviewServiceImpl`(SubTask,Task) / `ReviewServiceImpl`(SubTask) / `SubTaskReviewServiceImpl`(SubTask,Task) / `ReviewEvidenceAssembler`(Attachment,SubTask) / `ReviewExecutionEngine`(SubTask,Uncertainty) / `ReviewRecheckExecutor`(SubTask)。
- **`SubTask` 读取字段并集（12）**：`taskId`(25 处) / `id`(21) / `status`(5) / `assignedAgentId`(5) / `reworkCount`(4) / `uncertainties`(3) / `constraints`(3) / `deliverable`(3) / `acceptance`(3) / `context`(2) / `title`(2) / `content`(1)。
- **`Task` 读取字段并集（7）**：`finalReport` / `description` / `title` / `finalReportStatus` / `finalReportAgentId` / `agentPolicy` / `finalReportTime`。
- **`Attachment`（5）**：`fileName` / `fileType` / `fileSize` / `mimeType` / `id`。**`Uncertainty`（2）**：`note` / `kind`。
- **★2 处实体写**：`FinalReportReviewServiceImpl` 的 `probe.setTaskId(...)`（临时探测对象）、`SubTaskReviewServiceImpl` 的 `fresh.setContext(...)`（重加载后回写）⇒ 按 §7.2/W10 需**不透明命令端口**，不能只做读快照。

**批 5a 实测数据**：`PlannerAgentPicker`(Task) / `PlannerAnalysisServiceImpl`(SubTask,Task) / `PlannerDecomposeAsyncServiceImpl`(SubTask,Task,Uncertainty) / `RequirementClarifyServiceImpl`(Task) / `PlannerAnalysisService`(SubTask) / `RequirementClarifyService`(Task)。

#### 批 5a 补充取证（2026-10-04 实测）：**本批是「写侧批次」，不能照搬读快照**

| 站点 | 性质 | 实测写/协议面 |
|---|---|---|
| `picker/PlannerAgentPicker` | **纯读** | `taskService.getById(taskId)` → `TaskAgentPolicy.plannerAgentId(task.getAgentPolicy())`。可安全换 `getView`+`TaskView` |
| `service/impl/PlannerAnalysisServiceImpl` | **读+写+对外返回实体** | 写：`taskService.lambdaUpdate().set(Task::getStatus,…)`（CAS）、`taskService.updateById(task)`、`subTaskService.updateById(draft)`、`subTaskService.changeStatus(…)`、`physicalDeleteByTaskId`；**返回 `List<SubTask>`** |
| `service/PlannerAnalysisService`（接口） | **对外契约返回实体** | `decompose`/`listDrafts`/`confirmPlan` 均返回 `List<SubTask>`，**消费方是 api 层 `TaskController`** |
| `service/impl/PlannerDecomposeAsyncServiceImpl` | **实体创建 + LLM 协议反序列化** | `new SubTask()` + 11 个 setter（taskId/title/content/deliverable/acceptance/priority/isContract/requiredSkills/constraints/uncertainties/status/context）→ `subTaskService.saveBatch`；`Uncertainty` 经 Jackson `objectMapper.convertValue(node, new TypeReference<List<Uncertainty>>(){})` **直接从 LLM 输出的 JSON 反序列化**，并**原地改** `u.setKind(...)` |
| `service/impl/RequirementClarifyServiceImpl` | **实体创建 + 对外返回实体** | `Task task = new Task()`（`buildTaskFromDraft`）；`finalize`/`regenerate` 返回 `Task` |
| `service/RequirementClarifyService`（接口） | **对外契约返回实体** | `finalize`/`regenerate` 返回 `Task`，消费方 api 层 `RequirementConversationController` |

**⇒ 与批 4 的本质差别**：
1. 批 4 只换「读投影 + 1 处 context 覆写」；批 5a 要新增 **task 域写命令**（创建草案 / 创建任务 / 批量状态推进的 DTO 形态）。
2. `PlannerAnalysisService` / `RequirementClarifyService` 的**公共接口直接返回实体**，改动会**外溢到 api 层**（`api→core.entity` 为 warn 级，但接口语义变化需一并评估）。
3. `Uncertainty` 是**LLM 输出的 JSON 反序列化目标**（`planner-decompose.md` 的输出契约）——换 DTO 必须保证**字段名与缺省语义逐字不变**，否则静默改变拆解行为。

**建议切法（待拍板，§42）**
- 读侧：`PlannerAgentPicker` 直接换 `TaskView`（零风险）。
- 写侧新增 task 域命令（**扩方法优先，不新建端口**；`SubTaskService`/`TaskService` 已是被依赖方）：
  - `SubTaskService.saveDrafts(List<SubTaskDraft>) → List<SubTaskView>`（`SubTaskDraft` 含上述 11 字段 + `List<UncertaintyDraft>`；`UncertaintyDraft(kind, note)` 字段名与 `Uncertainty` **逐字对齐**以保持 LLM JSON 契约不变）
  - `TaskService.createFromDraft(TaskDraft) → TaskView`（`buildTaskFromDraft` 用）
  - `TaskService.casStatus(Long taskId, TaskStatus expect, TaskStatus target) → boolean`（替换 `PlannerAnalysisServiceImpl` 的 `lambdaUpdate()` 直写）
- 对外契约：`PlannerAnalysisService.decompose/listDrafts/confirmPlan` 与 `RequirementClarifyService.finalize/regenerate` 返回 `*View`，api 层（`TaskController`/`RequirementConversationController`）随之适配。
- 因涉及**对外接口语义 + LLM 协议面**，建议**先只做读侧 1 处**观察，或按上述命令形态整体推进——请指示。

**建议切法（待拍板）**
1. 在**提供方 task 域**新增 `task/port/`：`SubTaskView`（上述 12 字段，含 `UncertaintyView` 列表）/ `TaskView`（上述 7 字段）/ `AttachmentView`（5 字段）；`SubTaskService` / `TaskService` **只新增** `getView(Long)` / `listViews(...)` 等（既有方法被 api 层共用，不动）。
2. 消费方 review / planner 的内部方法签名 `SubTask`→`SubTaskView`、`Task`→`TaskView`（同类内部私有方法，波及面可控）。
3. **写侧**：`probe.setTaskId` 改为「构造视图局部变量」；`fresh.setContext` 需 task 域暴露一个**不透明命令**（如 `SubTaskService.updateContext(Long, Map)`）或确认可否改由已有 `SubTaskCommandPort` 承担。
4. 因涉及**签名链波及 + 写侧语义**，按规约 §42 属「架构动作」，**拆法与命令端口命名需与用户确认后**再动手。
