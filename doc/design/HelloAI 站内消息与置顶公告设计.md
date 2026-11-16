# HelloAI 站内消息与置顶公告设计

> **Status**: `Active`（设计已定稿并立项实现;任务登记见本文 §3.9 `MSG-1`~`MSG-8`，逐条进度以 `doc/plan/HelloAI 站内消息与置顶公告实施计划（2026-11-15）.md` §3 为唯一事实源）
> **Scope**：
> - **含**：① 站内**事件消息**的对象模型与幂等契约；② 各业务模块推送消息的**单一出口接口**（`NotificationService`）；③ 需人工处置 / 异步完成节点的**埋点清单**；④ **置顶公告**（管理员可编辑、带版本与编辑历史、按版本判已读）；⑤ 前端呈现位（顶栏铃铛 + 消息中心 + 公告条 + 工作台红框位）；⑥ 多副本（k3s / 集群）下的重复推送防线。
> - **不含**：站外渠道（邮件 / 短信 / IM webhook 外发）、消息订阅偏好（逐类开关）、移动端推送、@提及与私信、消息内直接操作（审批按钮走原业务接口）、跨租户隔离。
> **差距锚点**：`MSG-x`（差距表 §0 编号口径表）;差距项 `G-035`（差距表 §1 矩阵已建条;`G-033`/`G-034` 已占用，`G-035` 为本次登记水位）
> **最后更新**：2026-11-16（第八次：§3.10 规格与代码对齐（公告条闸门拆两套 / 开关读取口径）+ `seen` 上报入参硬化，见文末「修订记录」第八次条目;前次同日第七次：铃铛点击改为开合切换（停靠/悬浮同口径）;同日第六次：复核二轮修复（未读数标量类型 / 迁移历史时区 / 开关口径）;同日第四次：外部审查修复批 A1/A3 + B1~B6;2026-11-15 同日二次裁定：反转 `MSG-6` 暂缓、新增 §3.10 平台开关;首次更新为对当前代码逐条复核后订正;初版 2026-11-12，口径来自四轮调研分析）
> **优先级裁定（2026-11-15，用户;同日二次裁定）**：**首批事件消息 + 置顶公告一并交付**（`MSG-1`~`MSG-6` + `MSG-8` 核心验证），`MSG-7` 扩展覆盖列后续阶段。外部 AI Agent「完成任务提交」的提醒**纳入第一期**（§3.5 A5，主通道）。**消息与公告均受平台配置开关控制，默认关闭，只有启用后才在工作台可见**（§3.10）。
> 〔2026-11-15 二次裁定反转〕原首次裁定「置顶公告（`MSG-6`）条件性暂缓」已被用户反转——公告纳入本次交付，§3.7 设计据此实现。原暂缓口径保留于文末「修订记录」首次条目。

---

## 1. 背景与问题

平台的「需要人知道」的信号目前只有三处，且都不可靠：

1. **Agent 收件箱面向机器，不面向人。** `agent_inbox` 的收件人是 `agent_id`（[V1__init_all.sql:776-803](file:///E:/yhzx/1027/helloai/helloai-start/src/main/resources/db/migration/V1__init_all.sql#L776-L803)），前端 `paths.ts` 也只把它挂在 agent 管理面下。人这一侧没有对等对象。
2. **SSE 帧不是消息。** `WorkbenchEventHub` 只挂了两个监听（[WorkbenchEventHub.java:65-75](file:///E:/yhzx/1027/helloai/helloai-api/src/main/java/com/helloai/api/support/WorkbenchEventHub.java#L65-L75)），帧体只有一个 `taskId`（[WorkbenchEventHub.java:110-116](file:///E:/yhzx/1027/helloai/helloai-api/src/main/java/com/helloai/api/support/WorkbenchEventHub.java#L110-L116)），语义是「你该刷新了」，落不了库、断线即失、且连接是进程内的——多副本部署会漏推（该类注释已自认此为体验增强）。
3. **最长的那条等待没有任何通知。** 需求拆解、派生拆解、最终报告整合都是异步长任务，用户只能停在页面上等；`PlanReviewDialog.vue:199-200` 已经对用户**写明了**「产出后站内提醒 / 草案就绪会推送一条通知」（[PlanReviewDialog.vue:195-203](file:///E:/yhzx/1027/helloai/helloai-ui/src/views/task/components/PlanReviewDialog.vue#L195-L203)），而后端无对应实现——这是一张空头承诺。

同时，`SubTaskNotificationServiceImpl.sendInboxNotification` 的 `switch` 只覆盖 `ASSIGNED` / `REWORK` / `BLOCKED` / `PAUSED` / `REVIEW` 五种状态，其余走 `default -> { }`（[SubTaskNotificationServiceImpl.java:140-153](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/impl/SubTaskNotificationServiceImpl.java#L140-L153)）——**`DEAD_LETTER`（驳回达上限、需人工死信处置）零通知**，而这恰恰是最需要人立刻知道的一件事。

用户新增的两条诉求据此定性为**两个不同对象**，不能塞进一张表：

| 诉求 | 对象 | 生成方式 | 受众 | 已读语义 |
|---|---|---|---|---|
| 「AI 任务完成后提醒我」 | **事件消息**（notification） | 业务事件自动产生 | 单一归属人（`owner_id`） | 布尔：看过即看过 |
| 「管理员编辑、打开项目第一时间看到最近更新了什么」 | **置顶公告**（announcement） | 人工撰写 / 系统级发布 | 全体登录用户 | **按版本**：内容更新即视为未读 |

两者的生命周期、数量级、失败影响、编辑语义全都不同；混在一张表里必然让公告的可读性污染消息流的已读态。故本设计**一张表管事件消息、一组表管公告**，只共用前端呈现位与未读徽标。

---

## 2. 现状勘察（`[实测]`）

### 2.1 可直接复用的既有形状

| 资产 | 事实 | 复用点 |
|---|---|---|
| `agent_inbox` | `CONSTRAINT uq_inbox_event_agent UNIQUE (event_id, agent_id)`（[V1__init_all.sql:796](file:///E:/yhzx/1027/helloai/helloai-start/src/main/resources/db/migration/V1__init_all.sql#L796)）+ 未读局部索引 `idx_inbox_agent_unread`（:798-800）+ `is_read`/`read_at`/`is_archived`/`priority`/`expires_at`/`ref_type`/`ref_id` | **人侧消息表按同一形状建**：幂等靠 DB 唯一索引、深链靠 `ref_type`+`ref_id`、优先级三档、过期时间列 |
| `task_plan_attempt` | `batch_key` = 一次操作事件的幂等键，`attempt_no` = 同键第几次尝试，`UNIQUE (batch_key, attempt_no) WHERE deleted = 0`（[V146__plan_state_machine_and_attempts.sql:127-142](file:///E:/yhzx/1027/helloai/helloai-start/src/main/resources/db/migration/V146__plan_state_machine_and_attempts.sql#L127-L142)） | **消息幂等键的语义模板**：键的粒度是「事件实例」而非「业务对象」，重试由 `attempt_no` 自然区分 |
| `event_consumption_log` | `UNIQUE (message_id, consumer)`（[V18__event_consumption_log.sql:35-36](file:///E:/yhzx/1027/helloai/helloai-start/src/main/resources/db/migration/V18__event_consumption_log.sql#L35-L36)），注释写明是「Redis 未命中时的 DB 兜底」 | **消费侧去重**：将来若加外发渠道，去重落在消费端而非生产端 |
| `task_timeline` | 主写入口：`TaskTimelineService.recordEvent` / `recordEventInNewTx`（[TaskTimelineService.java:39-53](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/TaskTimelineService.java#L39-L53)），Mapper 只有一个自定义 `INSERT`（[TaskTimelineMapper.xml:11-30](file:///E:/yhzx/1027/helloai/helloai-core/src/main/resources/mapper/TaskTimelineMapper.xml#L11-L30)），`id` 由应用侧雪花预生成（`BaseEntity` `@TableId(IdType.ASSIGN_ID)`）⇒ 写前即可拿到行 id。**〔2026-11-15 订正〕写点并非只有一处**：`TaskServiceImpl.changeStatus` 在任务取消时直接 `taskTimelineMapper.insert` 写 `task_cancelled`（[TaskServiceImpl.java:246-251](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/impl/TaskServiceImpl.java#L246-L251)），绕过 `recordEvent`——第一期不映射该码故无损失，但「挂一处即全覆盖」不成立（见 §3.3）。事件码注册表 [TimelineEventCode.java](file:///E:/yhzx/1027/helloai/helloai-common/src/main/java/com/helloai/common/constant/TimelineEventCode.java) 已含 167 条码（`[实测]` 枚举常量计数），本文 §3.5 的 A 类（含 A5 提交）、C 类与 B1/B2/B5 **均已有现成码**；B4 未扫到独立码、B7 属会话域、C4 属 Agent 域（见 §4 边界 4） | **消息的主埋点通道**：不新增调用点，在该写入口挂一张「事件码 → 是否生成消息」映射表；时间线行 id 即事件实例标识（见 §3.3） |
| `WorkbenchEventHub` | SSE 骨架可用：25s 心跳、`EMITTER_TIMEOUT_MS=1_800_000L`、按 `ownerId` 过滤后 `send`（[WorkbenchEventHub.java:36-37](file:///E:/yhzx/1027/helloai/helloai-api/src/main/java/com/helloai/api/support/WorkbenchEventHub.java#L36-L37)、:105-120） | **只做「新消息到了」的信令**，帧体带 `notificationId`，正文一律回源查库（见 §3.6） |
| `QuotaFloatWidget` | 已作为全局浮层挂在 `MainLayout` 外层（[MainLayout.vue:158-159](file:///E:/yhzx/1027/helloai/helloai-ui/src/layouts/MainLayout.vue#L158-L159)） | 铃铛 / 公告条的**挂载先例**：证明「不新增第二套布局、在 MainLayout 同级挂全局件」这条路走得通 |

### 2.2 缺口（`[实测]`）

1. **人侧无消息表**：`grep` 全仓（`helloai-*/src` + `helloai-ui/src`）对 `user_notification` / `site_message` / `UserNotification` / `SiteMessage` **0 命中**（`[实测]` 2026-11-12）——既无实体、无迁移，也无前端封装。
2. **`DEAD_LETTER` 零通知**：见 §1 第 4 段；`grep DEAD_LETTER` 在通知类里没有命中。
3. **拆解失败与超时回收不通知人**：`task_plan_failed` / `task_plan_gate_blocked` / 租约回收（`V146` 的 `lease_until`）只落时间线事件，用户必须主动打开任务详情才看得见。
4. **`markManualIntervention` 全仓 28 处命中**（定义 + 调用点），即「需要人来看」的入口散落各域，无统一出口——这正是用户提的「共用消息接口」要收的口。
5. **公告没有现成载体**：`sys_config` 是 `UNIQUE (config_key) WHERE deleted = 0` 的键值表（[V1__init_all.sql:710-723](file:///E:/yhzx/1027/helloai/helloai-start/src/main/resources/db/migration/V1__init_all.sql#L710-L723)），`config_value TEXT NOT NULL`，**没有历史版本、没有按用户已读位**。放 `sys_config` 只能承载「全站一条、改了就覆盖、无法知道谁看过哪一版」，不满足「其他人更新代码后打开项目第一时间看到最近更新了什么」。结论：公告必须新建表。
6. **`Settings.vue` 的通知配置是纯展示**：`notifyChannels` 硬编码 `['web']`（[Settings.vue:797](file:///E:/yhzx/1027/helloai/helloai-ui/src/views/Settings.vue#L797)），后端无对应开关——本设计**不接管**这块（属不含项「订阅偏好」），但登记它是个空壳。

### 2.3 运行期事实：为什么「自动从仓库生成更新公告」不成立

镜像里没有源码历史：`.dockerignore` 的 `# Git` 段排除 `.git`、`# Docs / misc` 段排除 `doc/` 与 `*.md`；`Dockerfile` 四阶段只 COPY 模块源码与打好的 jar。`application.yml:558-562` 暴露的 actuator 端点为 `health,metrics,prometheus,circuitbreakers`，**无 `info`**，也没有 build-info 插件产物。

⇒ 运行期拿不到 commit / 变更说明。公告正文**必须人工撰写**；机器侧唯一可用的「本库做过哪些结构变更」台账是 `flyway_schema_history`，可作为撰写时的**素材来源**（管理页给一个「按迁移版本列出近期变更」的辅助读视图），但不是自动发布。

### 2.4 是否借鉴开源（结论：不借鉴代码，只借鉴形态）

判据记录在此，避免后续重复讨论：① 站内消息中心 + 顶栏铃铛 + 公告条是**形态共识**而非算法，没有值得抄的实现；② 典型候选（通用 notification 框架）自带模板引擎 / 多渠道路由 / 订阅偏好三件套，与本项目「不复制第二套控制面」（[doc/log/README.md §10.1](file:///E:/yhzx/1027/helloai/doc/log/README.md)）冲突，且本项目已有 RBAC、SSE、雪花 ID、MyBatis-Plus 四套既有约束，接入成本高于自写；③ 本项目需要的恰好是**幂等键与唯一索引**这类数据库层设计，任何外部框架都得让位于此。**只有当引入站外渠道（邮件 / IM）时**，模板渲染与重试路由才值得重新评估借鉴。

---

## 3. 设计

### 3.1 对象模型二分

```
事件消息  user_notification   1 行业务事件 → N 个收件人 → N 行；幂等键去重；按 owner 过滤；布尔已读
置顶公告  announcement        全局单条可编辑内容 + revision 历史；按 version 判已读；不绑定业务对象
```

两表**不共表**，只共用：未读徽标聚合接口、前端消息中心页签。

`agent_inbox` 保持不动——它是**平台 → 外部 Agent** 的下行通道，方向与本设计（**平台 → 人**）相反。可共骨架（同样的 `event_id`/`ref_type`/`priority` 三件套语义），**不共表**：收件人身份体系不同（`agent_id` vs `user_id`），共表会让外部通道的授权过滤污染人侧查询。

### 3.2 共用接口：标准化契约，不标准化文案

各模块推送一律走单一出口，业务侧只上报**事实**，文案与深链由消息域集中渲染：

```java
// 唯一入口（helloai-core，与 AgentInboxService 同层）
void publish(NotificationEvent event);   // 幂等：重复调用同 key 不产生第二条
```

```java
NotificationEvent {
    NotificationType type;        // 枚举，见下表；禁止各模块自造字符串
    String  sourceType;           // TASK / SUB_TASK / CONVERSATION / AGENT / RELEASE
    Long    sourceId;             // 业务对象 id
    String  instanceKey;          // 事件实例标识：主通道 = task_timeline.id；例外通道见 §3.3
    String  timelineEventCode;    // 主通道由钩子带入；例外通道可为空
    String  receiverId | List     // 单个归属人；或 scope=ALL 广播
    Severity severity;            // NORMAL / HIGH / URGENT（对齐 agent_inbox.priority 三档）
    Map<String,Object> payload;   // 只放渲染所需的**数据**（条数、原因码、attempt_no），不放句子
}
```

**两类调用方，同一个出口**：

- **主通道（自动）**：`TaskTimelineServiceImpl.recordEvent` / `recordEventInNewTx` 在插入时间线行后，查映射表决定是否 `publish`。业务模块**不需要知道消息这件事存在**——这是「共用接口」真正的收益：新事件只要写时间线，提醒能力自动覆盖，不需要每个域再去学一遍怎么发消息。
- **例外通道（显式）**：不写 `task_timeline` 的事件（会话域澄清收敛、Agent 域外部上线、管理域公告发布）由该域显式调 `publish`。`MSG-7` 评估是否给这些域补一张对等的会话/Agent 域事件表——**第一期不做第二套时间线**。


**为什么文案必须集中、契约必须收口**：同一句「草案已就绪」散在多个调用点时，改一次措辞要发一片补丁，而人与外部 Agent 读到的措辞就是判断系统行为的依据（`G-014` 的「外部通道与内部分发同口径」是同一教训）。因此：

- **业务侧**：只负责「发生了什么、在哪、严重度」，不拼句子、不拼 URL；
- **消息域**：`NotificationType` 枚举 → 标题模板 + 正文模板 + 深链路由 + severity 默认值，一处注册表，一处改；
- **注册表即清单**：新增一类消息必须先在 `NotificationType` 登记，禁止 `type` 传裸字符串（编译期挡住散落）。

`NotificationType` 首批枚举（语义 ↔ 埋点编号一一对应，见 §3.5）：
`PLAN_DRAFT_READY` / `PLAN_FAILED` / `PLAN_GATE_BLOCKED` / `PLAN_LEASE_RECALLED` / `DERIVED_PLAN_READY` / `FINAL_REPORT_READY` / `FINAL_REPORT_FAILED` / `FINAL_REPORT_REVIEW_STUCK` / `TASK_DONE` / `SUBTASK_DEAD_LETTER` / `SUBTASK_NO_CANDIDATE` / `SUBTASK_BLOCKED` / `RESTRUCTURE_STUCK` / `REVIEW_INCONCLUSIVE` / `CLARIFY_STALLED` / `EXTERNAL_AGENT_ONLINE` / `SUBTASK_SUBMITTED` / `ANNOUNCEMENT_PUBLISHED`。

> **〔2026-11-15 订正〕`SUBTASK_SUBMITTED` 取代原 `EXTERNAL_ARTIFACT_SUBMITTED`**：实测外部 Agent「完成提交」（`submitResult`）与内部执行器共用 `ExecutionResultHandler.handleReport` 收口，成功即写 `sub_task_execute_submit` 时间线（[ExecutionResultHandler.java:369-380](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/agent/command/ExecutionResultHandler.java#L369-L380)），payload 带 `source`（INTERNAL/EXTERNAL）。故该提醒走**主通道**（§3.5 A5），不再是例外通道；原「外部产物提交归 `MSG-7` 例外通道」的计划作废。

> **〔2026-11-16 审查修复〕新增 `FINAL_REPORT_REVIEW_STUCK` + refType 口径**：① B1「报告审查达上限」（`task_final_report_max_review_reached`）是 **task 级事实**（无子任务进死信），原复用 `SUBTASK_DEAD_LETTER` 造成文案与深链归属双错，现单列 TASK 型（URGENT），首批激活类型变为 6 类；② 深链 `ref_type` **取事件的 `sourceType`**（钩子按 subTaskId 有无给出 TASK/SUB_TASK），注册表按类型固定的 refType 仅作例外通道未给 sourceType 时的兜底——否则 task 级事件映射进 SUB_TASK 型时会把 taskId 当子任务 id，产生死链（详见 §6 第四次条目）。

### 3.3 幂等：键的粒度是「事件实例」，不是「任务」

用户提的「一个任务只推 1 条消息」口径**不成立**：同一任务同时会有草案就绪、报告完成、死信等多类消息；同一子任务可多次进 `DEAD_LETTER`（每次重派后再驳回）；拆解失败重试后再次失败是新一次事件。按任务去重会让第二次真实事故**静默丢失**——这是比重复推送严重得多的错误。

正确口径：**一类事件的一次发生 = 一条消息**。

「一次发生」需要一个**已经在库里唯一的标识**。勘察结论：**不自造，直接借时间线行 id**——`task_timeline.id` 在 `INSERT` 前由应用侧雪花生成（[TaskTimelineMapper.xml:11-30](file:///E:/yhzx/1027/helloai/helloai-core/src/main/resources/mapper/TaskTimelineMapper.xml#L11-L30)），一条事件行天然全局唯一，且**跨节点唯一**（两个副本不可能各自造出同一逻辑事件的两次插入而 id 相同）。**〔2026-11-15 订正〕写点实为两处**：除 `recordEvent` 外，`TaskServiceImpl.changeStatus` 任务取消时直接 `taskTimelineMapper.insert` 写 `task_cancelled`（[TaskServiceImpl.java:246-251](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/impl/TaskServiceImpl.java#L246-L251)）。两处都用 `ASSIGN_ID` 雪花，**id 全局唯一的论证不受影响**；但钩子挂 `recordEvent` 会漏掉 `task_cancelled`——第一期不映射该码故无损失，日后若要提醒任务取消，须先把这条收编进 `recordEvent` 或单独挂钩。

```
主通道   dedupe_key = {NotificationType}:{timelineEventCode}:{task_timeline.id}
例外通道 dedupe_key = {NotificationType}:{sourceType}:{sourceId}:{instanceKey}
```

例外通道只服务于**不写 `task_timeline` 的事件**（澄清收敛属会话域、外部 Agent 上线属 Agent 域、公告发布属管理域）。其 `instanceKey` 一律取现成单源，不自造计数器：

| 事件 | instanceKey 来源 | 依据 |
|---|---|---|
| 拆解 / 重拆 / 派生拆解（会话域触发时） | `task.decompose_attempt_id` | [V146:30](file:///E:/yhzx/1027/helloai/helloai-start/src/main/resources/db/migration/V146__plan_state_machine_and_attempts.sql#L30)，注释明写它是「本次操作事件的幂等键」 |
| 子任务死信 / 驳回达上限 | `sub_task.attempt_total` | [V64:17](file:///E:/yhzx/1027/helloai/helloai-start/src/main/resources/db/migration/V64__sub_task_add_attempt_total.sql#L17)（全局共享重试预算，单一权威写入点） |
| 报告类（A3 / C3 / C5） | 走主通道，用 `task_final_report_generated` / `task_final_report_failed` / `task_final_report_max_review_reached` 各自行 id | 这三类**已有时间线码**（[TimelineEventCode.java:132-135](file:///E:/yhzx/1027/helloai/helloai-common/src/main/java/com/helloai/common/constant/TimelineEventCode.java#L132-L135)），无需再找 attempt 列 |

> **继承风险的诚实说明（必须在 `MSG-1` 单测里锁定）**：`TaskTimelineService` 的类注释第 21 行明写「幂等性：recordEvent 不去重（事件本身就是审计，重写是 OK 的）」。借行 id 做键 ⇒ **时间线重复写几条，消息就发几条**。这对「一次生成一次报告」类事件是正确的（重复写确实是两次发生）；对**循环重试类**事件（如 `sub_task_dispatch_fail` 每轮一条）会刷屏。处置不在消息层猜，而在 §3.5 的映射表上按事件码显式声明形态：`ONCE`（逐条发）/ `BUCKET`（按窗口折叠，窗口键替换 `instanceKey` 段）/ `SILENT`（映射为不生成）。默认 `ONCE`，重试类一律显式标 `BUCKET`。

落库唯一约束（照 `agent_inbox` 形状，一处收紧）：

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_notification_dedupe
    ON user_notification(dedupe_key, COALESCE(receiver_id, 0)) WHERE deleted = 0;
```

> **`COALESCE` 不是装饰**：Postgres 唯一索引把 NULL 视为互不相等，广播消息（`receiver_id IS NULL`）走普通唯一索引**根本不去重**。这是本表最容易踩的一处，必须在迁移里写死并在单测里锁定。

**幂等 vs 聚合是两件事，本设计分开处理**：

- **幂等**（同事件不重复）：靠上面的唯一索引，`INSERT` 撞键即当作已发过，静默返回、不报错、不重试。
- **聚合 / 折叠**（多事件合一条，即上表的 `BUCKET`）：把 `dedupe_key` 的实例段换成滚动窗口（如 `{yyyyMMddHH}`），同窗口内只落第一条并在 `payload` 里累加计数。第一期只对 `SUBTASK_NO_CANDIDATE`、派发重试类开 `BUCKET`——它们是仅有的会成规模刷屏的一类。

### 3.4 多副本下的重复推送：三层防线，不靠运气

| 层 | 机制 | 保证 / 不保证 |
|---|---|---|
| **生成侧** | 唯一索引 `uk_user_notification_dedupe`（DB 层） | **跨副本的最终防线**：两个节点同时判定「该发消息」，一方 `INSERT` 撞键。这是唯一能真正杜绝重复的层 |
| **调度侧** | 巡检 / 超时类消息由既有的定时任务发起，复用项目现行锁形态（Redisson `tryLock(0, TTL, SECONDS)`，同 [FinalReportReviewLock.java:20](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/review/support/FinalReportReviewLock.java#L20) 与其调用点） | 减少无效竞争，**不作为正确性依赖**——锁失效时生成侧仍在兜底 |
| **投递侧** | SSE 只发 `notificationId` 信令，正文一律回源查库 | 漏推 / 重复推都只影响「提示时机」，不影响数据；断线后靠未读列表补齐 |

**明确结论：不为消息建状态机。** 理由两条：① 消息只有「未读 / 已读 / 归档」三态，是布尔位组合，没有转移语义；② 源头状态机（`Task` 6 态 / `SubTask` 10 态 / `task_plan_attempt` 五态）已存在且是唯一权威，为消息再加一套状态即违反「如果能够从任务/执行数据推导，则优先查询推导，不重复增加数据库状态字段」（[doc/log/README.md §10.5](file:///E:/yhzx/1027/helloai/doc/log/README.md)）。消息的「有效性」不判状态，判**深链回源**：点开时按 `ref_type`+`ref_id` 现查业务对象，对象已变则展示当前状态而非消息写入时的快照（`payload` 只用于列表行摘要，不作事实依据）。

### 3.5 埋点清单（事件码 → 消息映射表）

**埋点不是插桩**。勘察确认：本文列出的每个节点**今天都已经在写 `task_timeline`**（`[实测]` 见下表「事件码」列），缺的只是「写完之后有没有人被告知」。因此第一期交付物是**一张映射表 + 一处钩子**，而不是 20 个新调用点——这也从根上保证消息与时间线同源、不会漂移。

映射表每行四列：`事件码 → 消息类型`、`severity`、`形态`（`ONCE` 逐条 / `BUCKET` 窗口折叠 / `SILENT` 显式不发）、`是否第一期`。默认策略：**未登记的事件码不发消息**（白名单，不是黑名单）——时间线有 167 条码，默认全发等于把审计流水复制一遍到用户眼前。

**A 类：异步长任务完成——用户只需知道，不需要动作**

| 编号 | 事件码 `[实测]` | 消息类型 | severity | 形态 |
|---|---|---|---|---|
| A1 | `task_plan_generated`（TimelineEventCode:165） | `PLAN_DRAFT_READY` | NORMAL | ONCE |
| A2 | `task_plan_draft_derived`（:159，ADR-008 派生拆解） | `DERIVED_PLAN_READY` | NORMAL | ONCE |
| A3 | `task_final_report_generated`（:133） | `FINAL_REPORT_READY` | NORMAL | ONCE |
| A4 | `task_auto_completed`（:129） | `TASK_DONE` | NORMAL | ONCE |
| A5 | `sub_task_execute_submit`（:75，**内部/外部 Agent 提交共用**） | `SUBTASK_SUBMITTED` | NORMAL | ONCE |

> A1 同时兑现 `PlanReviewDialog.vue:199-200` 已写明的「产出后站内提醒」承诺；前端 3s 轮询 + 5 分钟封顶（[PlanReviewDialog.vue:961-962](file:///E:/yhzx/1027/helloai/helloai-ui/src/views/task/components/PlanReviewDialog.vue#L961-L962)）在消息上线后降级为可选体验，不作为唯一通知路径。
>
> **A5（2026-11-15 新增，用户诉求「外部 AI Agent 完成任务提交也提醒」）**：外部 Agent 经 `McpController.submitResult` → `McpAgentReportServiceImpl`（`source=EXTERNAL`）→ `ExecutionResultHandler.handleReport`，与内部执行器（`source=INTERNAL`）**同一收口**，成功即写 `sub_task_execute_submit`（[ExecutionResultHandler.java:369-380](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/agent/command/ExecutionResultHandler.java#L369-L380)）。故主通道钩子自动覆盖内外提交，零额外插桩。形态默认 `ONCE`（每轮提交都提醒，含返工重提）；若真机刷屏，按 §3.3 机制改 `BUCKET` 即可，不改代码。payload 的 `source` 支持日后「只提醒外部」过滤，第一期内外统一发。

**B 类：必须人来拍板——不发消息就一直卡着**

| 编号 | 事件码 `[实测]` | 消息类型 | severity | 形态 |
|---|---|---|---|---|
| B1 | `sub_task_dead_letter`（:54）· `sub_task_review_dead_letter`（:102，核验熔断）· `task_final_report_max_review_reached`（:135，报告审查达上限） | `SUBTASK_DEAD_LETTER` | **URGENT** | ONCE |
| B2 | `sub_task_no_candidate`（:94）· `sub_task_dispatch_no_alternative`（:61）· `task_plan_skill_unmatched`（:170，技能无承接者将静默等待） | `SUBTASK_NO_CANDIDATE` | HIGH | **BUCKET**（1h） |
| B3 | 长等待 10 轮仍无候选（由 `BUCKET` 计数达到阈值时升级一条 URGENT，不新增事件码） | `SUBTASK_NO_CANDIDATE` | URGENT | ONCE |
| B4 | 子任务卡 `RESTRUCTURING` 的事件码 **待 `MSG-7` 回源确认**（`TimelineEventCode` 内未扫到独立码） | `RESTRUCTURE_STUCK` | HIGH | ONCE |
| B5 | `sub_task_auto_review_inconclusive`（:51）· `subtask_review_inconclusive`（:119） | `REVIEW_INCONCLUSIVE` | HIGH | ONCE |
| B6 | 其余 `markManualIntervention`（全仓 28 处命中）——逐个映射到其伴随的事件码；无伴随码的归入 `MSG-7` | `MANUAL_INTERVENTION` | HIGH | ONCE |
| B7 | 澄清轮数用尽仍收敛（`TD-8` stall 强制收敛、不建任务）**属会话域，不写 `task_timeline`** | `CLARIFY_STALLED` | NORMAL | 例外通道 |

> **〔2026-11-16 审查修复〕B1/B2 行口径更新**：`task_final_report_max_review_reached` 改映射 `FINAL_REPORT_REVIEW_STUCK`（TASK 型，URGENT/ONCE 不变），不再复用 `SUBTASK_DEAD_LETTER`，上表 B1 行保留为历史口径。B2 的 `task_plan_skill_unmatched` 是 task 级事件，深链 `ref_type` 跟随 `sourceType=TASK`（类型仍 `SUBTASK_NO_CANDIDATE`，「暂无可用执行者」文案仍成立）。另：`sub_task_no_candidate` 的孤儿巡检写点在 catch 块作用域拿不到 taskId，钩子已补「taskId 缺省 → 经 subTaskId 查 `SubTaskMapper` 兜底解析归属」，否则该码消息静默丢失（A3，详见 §6 第四次条目）。

**C 类：需要补救——发生了就是坏事**

| 编号 | 事件码 `[实测]` | 消息类型 | severity | 形态 |
|---|---|---|---|---|
| C1 | `task_plan_failed`（:161）· `task_plan_gate_blocked`（:163） | `PLAN_FAILED` / `PLAN_GATE_BLOCKED` | HIGH | ONCE |
| C2 | `task_plan_timeout_recovered`（:171，拆解超租约被巡检恢复） | `PLAN_LEASE_RECALLED` | HIGH | ONCE |
| C3 | `task_final_report_failed`（:132）· `task_final_report_outline_failed`（:136） | `FINAL_REPORT_FAILED` | HIGH | ONCE |
| C4 | 外部 Agent 认领后超时未提交（超时任务侧，属 Agent 域） | `EXTERNAL_CLAIM_TIMEOUT` | NORMAL | 例外通道 |
| C5 | `task_final_report_review_failed`（:140）· `task_final_report_review_unparseable`（:146） | `REPORT_REVIEW_FAILED` | HIGH | ONCE |

**第一期范围（用户裁定的优先级在此表达，不写成进度）**：A1 / A3 / **A5（外部/内部 Agent 提交）** / B1 / B2 五类先接;其余按 `MSG-7` 补齐。**置顶公告（`MSG-6`）纳入本次交付**（2026-11-15 用户二次裁定反转首次暂缓），受 `announcement.enabled` 开关控制（§3.10）。

**显式标 `SILENT`（映射表里写明「不发」，防止后人顺手加）**：子任务逐条进度 / 派发心跳 / `task_plan_llm_call_start`·`end` / `task_final_report_llm_call_start` / `sub_task_dispatch_prepare`·`skip_*` / 门禁 `warned` / 草案单条 `regenerated`·`rejected` / 澄清单轮问答。理由：这些是**过程**，用户看了不能做任何事；`agent_inbox` 的五类机器通知也不做人侧复制。注意 `sub_task_execute_submit`（A5）**不在** SILENT 之列——它是「提交完成」这一用户要被告知的节点，不是过程心跳。

### 3.6 投递与呈现

- **落库与推送解耦**：数据源是表，SSE 是加速器。客户端断线重连后拉 `unreadCount` + 列表即可补齐，不依赖在线时刻。
- **消息写在哪条事务上，随时间线写点而定——这里不能想当然。** `TaskTimelineService` 有两个写入口（[TaskTimelineService.java:39-53](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/TaskTimelineService.java#L39-L53)）：`recordEvent` 默认 `REQUIRED`「让正常路径的事件与业务同生共死」；`recordEventInNewTx` 是 `REQUIRES_NEW`、注释明写「**失败路径的审计写点专用**」，理由是不能随失败的业务一起回滚。钩子只需挂 `recordEvent` 一处——`recordEventInNewTx` 内部即委托 `recordEvent`（[TaskTimelineServiceImpl.java:61](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/impl/TaskTimelineServiceImpl.java#L61)），故事务语义自动跟随调用方（正常 `REQUIRED` / 失败 `REQUIRES_NEW`），消息天然分成两种命运：
  - **正常路径**（A1/A2/A3/A4/B5/C1…）：与业务同事务，业务回滚则消息一并消失——**这正是想要的**，不会出现「消息说草案好了、其实那批草案回滚了」。
  - **失败路径**（C1 的失败码 / C3 / C5 等经 `recordEventInNewTx` 写出的）：落在独立事务，业务回滚不影响它——**这也是想要的**，事故已经发生，消息不该跟着一起被抹掉。代价是失败消息与业务不在同一原子域，崩溃窗口内可能「时间线有、消息没有」，由 `MSG-8` 的一条对账 SQL 兜（`task_timeline` 有映射码而 `user_notification` 无对应 `dedupe_key` 的行）。
- **不再另设「按 severity 决定是否同事务」的规则**。severity 只决定**呈现与提醒强度**，不决定事务语义；事务语义由写入口决定，与时间线保持同源。
- **对既有 best-effort 纪律的偏离点要说准**：`SubTaskNotificationServiceImpl` 的类注释是全局 best-effort——「全部投递口失败只 warn/error、绝不向上抛」（[SubTaskNotificationServiceImpl.java:34-35](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/impl/SubTaskNotificationServiceImpl.java#L34-L35)）。这条纪律对**机器通道**成立。人侧新链只在「映射表命中且写库异常」时保留同一降级（`NORMAL` 级失败不阻断业务），但 **`URGENT` 级（B1 死信 / B3 长等待）写失败必须 error 级留痕并计入对账**，不得只 warn 而过。
- **`agent_inbox` 那条链保持原样**，本设计不迁移它、不让它走新出口（它是机器通道，语义与文案模板都不同）。
- **SSE 在 `AFTER_COMMIT` 发**，避免推了一个已回滚的消息。帧体只放 `notificationId`。
- **前端两处**：① 顶栏**铃铛 + 未读数** + 消息抽屉（列表 / 全部已读 / 按类型筛选 / 点击跳深链）；② 用户红框指的位置——工作台右侧信息栏页签行右端（[Workbench.vue:542-565](file:///E:/yhzx/1027/helloai/helloai-ui/src/views/workbench/Workbench.vue#L542-L565) 的 `.wb-panel-head`，其 `.wb-panel-ops` 刷新/更多下拉已被注释但样式保留）作为**站内消息的最小入口**；全局铃铛挂 [MainLayout.vue:139-151](file:///E:/yhzx/1027/helloai/helloai-ui/src/layouts/MainLayout.vue#L139-L151) 外层，与 `QuotaFloatWidget` 同级。两处呈现同一份数据，不做两份状态。
- **置顶公告条**：页面顶部固定条（可折叠，折叠状态按用户记住），有未读新版本时展开并计入铃铛角标。
- **〔2026-11-16 铃铛交互修订〕** 工作台铃铛与悬浮铃铛**二选一**，默认停靠工作台（`bellMode='docked'`）：
  - 从工作台铃铛**拖出** → 切换为悬浮（`floating`），工作台原位留虚线占位坑（`.wb-dock-ghost`），悬浮铃铛出现在原坑位并跟手移动；拖出后位置落 `localStorage`（`ha.notification.bellPos`）。
  - 悬浮铃铛**拖回**占位坑（命中 `[data-notif-dock]` 矩形 ±10px）→ 复原为工作台铃铛，悬浮件卸载；形态本身持久化（`ha.notification.bellMode`）。
  - 轻点（未拖动，4px 阈值）任一形态 → 打开消息抽屉；拖拽结束补发的 click 被吞一次，避免误开。

### 3.7 置顶公告与版本已读

> **〔2026-11-15 二次裁定反转〕** 原首次裁定「置顶公告（`MSG-6`）条件性暂缓」已被用户反转——**公告纳入本次交付**，本节设计据此实现，受 `announcement.enabled` 开关控制（默认 OFF，§3.10）。原暂缓口径保留于文末「修订记录」首次条目。

```
announcement (1 条置顶内容)
  ├─ version INT  —— 每次「发布」自增，是已读判据的唯一依据
  ├─ status       —— DRAFT / PUBLISHED / ARCHIVED
  ├─ pinned       —— 是否顶部展示
  └─ announcement_revision —— 每次发布留一份全量快照（编辑历史，不可改写）
       └─ announcement_seen (user_id, announcement_id, seen_version) —— 按版本判已读
```

- **已读 = `seen_version >= version`**，不是布尔位。这才能实现「管理员更新了内容，所有人重新看到它」——正是用户要的「打开项目第一时间看到最近更新了什么」。
- **不做多条置顶**（第一期）：`pinned` 是单条语义，管理页支持草稿/发布/归档与历史查看，但顶部条只渲染一条。判据：多条时「最近有什么更新」的注意力价值被稀释成第二个消息中心，与消息流职责重叠。扩展位保留在 `announcement` 表结构（不引入 `sys_config` 式的 key 约定），第一期不开。
- **权限**：读接口登录即可（无用户态返回默认值，同 [助理对话设计 §3.6 授权口径](file:///E:/yhzx/1027/helloai/doc/design/HelloAI%20助理对话与上下文窗口设计.md)）；写接口用新动作码 `notice:edit`（对齐 `AdminConfigController` 的 `config:view` / `config:edit` 两段式，[AdminConfigController.java:35-97](file:///E:/yhzx/1027/helloai/helloai-api/src/main/java/com/helloai/api/controller/AdminConfigController.java#L35-L97)）。
- **撰写辅助**：管理页给一个只读视图「按迁移版本列出近期结构变更」（数据源 `flyway_schema_history`），供管理员据此写公告；**不自动发布、不把 DB 变更当公告**。

### 3.8 数据模型（Flyway 合并迁移 `V151`，以立项时实际水位为准）

```sql
-- 人侧站内消息
CREATE TABLE IF NOT EXISTS user_notification (
    id           BIGINT       NOT NULL PRIMARY KEY,      -- 雪花，与 BaseEntity 一致
    receiver_id  BIGINT,                                 -- sys_user.id；NULL = 广播
    type         VARCHAR(64)  NOT NULL,                  -- NotificationType 枚举名
    severity     VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    title        VARCHAR(255) NOT NULL,
    summary      TEXT,
    dedupe_key   VARCHAR(160) NOT NULL,
    ref_type     VARCHAR(32),                            -- TASK / SUB_TASK / CONVERSATION ...
    ref_id       BIGINT,
    payload      JSONB,                                  -- 列表行摘要用，不作事实依据
    is_read      SMALLINT     NOT NULL DEFAULT 0,
    read_at      TIMESTAMPTZ,
    is_archived  SMALLINT     NOT NULL DEFAULT 0,
    expires_at   TIMESTAMPTZ,
    create_by    VARCHAR(64)  NOT NULL DEFAULT '',
    update_by    VARCHAR(64)  NOT NULL DEFAULT '',
    create_time  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remark       VARCHAR(255),
    deleted      SMALLINT     NOT NULL DEFAULT 0
);
-- 幂等最终防线（跨副本）；COALESCE 不可省，见 §3.3
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_notification_dedupe
    ON user_notification(dedupe_key, COALESCE(receiver_id, 0)) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_user_notification_unread
    ON user_notification(receiver_id, is_read, severity, create_time)
    WHERE is_read = 0 AND is_archived = 0 AND deleted = 0;

-- 置顶公告
CREATE TABLE IF NOT EXISTS announcement (
    id            BIGINT       NOT NULL PRIMARY KEY,
    title         VARCHAR(255) NOT NULL,
    body          TEXT         NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    pinned        SMALLINT     NOT NULL DEFAULT 1,
    version       INT          NOT NULL DEFAULT 1,
    published_at  TIMESTAMPTZ,
    -- create_by/update_by/create_time/update_time/deleted 同 BaseEntity
    CONSTRAINT chk_announcement_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED'))
);
CREATE TABLE IF NOT EXISTS announcement_revision (
    id              BIGINT       NOT NULL PRIMARY KEY,
    announcement_id BIGINT       NOT NULL REFERENCES announcement(id),
    version         INT          NOT NULL,
    title           VARCHAR(255) NOT NULL,
    body            TEXT         NOT NULL,
    published_by    VARCHAR(64)  NOT NULL DEFAULT '',
    published_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- …BaseEntity 列
    CONSTRAINT uq_announcement_revision UNIQUE (announcement_id, version)
);
CREATE TABLE IF NOT EXISTS announcement_seen (
    id              BIGINT       NOT NULL PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES sys_user(id),
    announcement_id BIGINT       NOT NULL REFERENCES announcement(id),
    seen_version    INT          NOT NULL,
    seen_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_announcement_seen UNIQUE (user_id, announcement_id)
);
```

> 迁移纪律：原计划**一份迁移只做一件事**（消息表 / 公告表 / RBAC 各一条 `V151`/`V152`/`V153`）;〔2026-11-15 第三次裁定〕用户要求「数据库版本 151-153 合并成一个，依次 plan 任务是一个小版本」，故三者合并为单一 `V151__notification_announcement.sql`（§1 消息表 / §2 公告三表 / §3 RBAC），见 §6 第三次条目。上线前仍核对 `flyway_schema_history` 实际水位再定号——`helloai-start/src/main/resources/application.yml:89` 里 `validate-on-migrate=false`，校验失败的混合 schema 不会被启动拦下，号段只能靠实查水位确定。

> **〔2026-11-16 审查修复〕`announcement.expires_at` 已删除**：该列无写入方也无读取方（公告生命周期由 `status`/`pinned` 管理，无过期语义），属死结构，已从 `V151` §2 与 `Announcement` 实体同批删除（`V151` 未提交未应用、两库水位 `V150`，删除不触发 checksum 问题）。`user_notification.expires_at` **保留**——读取侧已有过期过滤（`pageByReceiver`/`unreadCount`），MSG-7 清理器将写入。

### 3.9 API 与分阶段

**API**

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/user-notification/pageByReceiver` | 登录，按 `owner` 过滤 | 列表（未读优先，可按 `type` 筛） |
| GET | `/user-notification/unreadCount` | 登录 | 铃铛角标，与公告未读合并计数 |
| POST | `/user-notification/markRead/{id}` | 登录且是本行收件人 | 置 `is_read` + `read_at` |
| POST | `/user-notification/markAllRead` | 登录 | 只影响当前收件人 |
| POST | `/user-notification/archive/{id}` | 登录且是本行收件人 | 软归档 |
| GET | `/user-notification/stream` | 登录 | SSE 信令帧（`notificationId`），与 `WorkbenchEventHub` 同款骨架 |
| GET | `/user-notification/features` | 登录即可读 | 返回 `{notificationEnabled, announcementEnabled}` 两开关态，供前端闸门（§3.10） |
| GET | `/announcement/published` | 登录即可读 | 当前置顶公告 + 我的 `seen_version` |
| POST | `/announcement/seen/{id}` | 登录 | 上报 `seen_version`（幂等：只单调递增） |
| GET/POST/PUT | `/announcement/*` | `notice:view` / `notice:edit` | 草稿、发布（`version+1` + 写 revision）、归档、历史列表 |
| GET | `/announcement/schemaHistory` | `notice:view` | 只读辅助视图，取 `flyway_schema_history` |

> **登录可读端点登记（CODE_STYLE §43）**：以下读取端点**登录即可访问、无动作级权限码**，越权由接口层按归属过滤挡住、不依赖前端隐藏——`/user-notification/{pageByReceiver,unreadCount,stream,features}`（按 `receiver_id` = 当前登录用户过滤;`features` 只返回两开关态）、`/announcement/published`（全体登录用户读当前置顶 + 自己的 `seen_version`）、`/announcement/seen/{id}`（只写自己的已读位）。**写入/管理端点**仍走动作码：公告草稿/发布/归档/历史 = `notice:edit` / `notice:view`;消息 `markRead` / `markAllRead` / `archive` 限本行收件人（接口层校验 `receiver_id`）。**开关 OFF 时上述读取端点一律返回空**（`unreadCount=0`、列表空、`published` 空;§3.10）。

**分阶段**（`MSG-1`~`MSG-8`，号段于差距表 §0 同日登记；依赖为硬约束，按序做）

| 编号 | 内容 | 依赖 |
|---|---|---|
| `MSG-1` | `NotificationType` / `Severity` 枚举 + `NotificationEvent` + `NotificationService` 单一出口 + 文案注册表 + **事件码→消息映射表**（含 `ONCE`/`BUCKET`/`SILENT` 三形态）。单测锁三件事：同 `dedupe_key` 重复 publish 不产生第二行、未登记事件码不发、`SILENT` 显式登记项不发 | — |
| `MSG-2` | 迁移：`user_notification` + 两个索引（**含 `COALESCE` 唯一索引的 NULL 陷阱单测**）+ 实体/Mapper | — |
| `MSG-3` | **钩子接线**：`TaskTimelineServiceImpl.recordEvent` 写点后调 `publish`（`recordEventInNewTx` 委托 `recordEvent`，一处钩子覆盖两入口，见 §3.6）；SSE 在 `AFTER_COMMIT` 发；`URGENT` 写失败 error 留痕。单测锁「业务回滚 ⇒ 正常路径消息不留半条」「失败路径 `REQUIRES_NEW` ⇒ 业务回滚后消息仍在」 | `MSG-1` `MSG-2` |
| `MSG-4` | 首批映射表登记 A1 / A3 / **A5（Agent 提交，内外共用）** / B1 / B2（B2 用 `BUCKET` 1h）—— 并同批兑现 `PlanReviewDialog.vue:199-200` 的既有承诺 | `MSG-3` |
| `MSG-5` | 前端：顶栏铃铛 + 未读数 + 消息抽屉 + 深链跳转 + 工作台红框位最小入口 | `MSG-4` |
| `MSG-6` | 置顶公告：三表 + 版本已读 + `notice:edit` 管理页 + 顶部公告条 + 折叠态记忆 + `announcement.enabled` 开关（默认 OFF，§3.10）。**〔2026-11-15 二次裁定反转〕** 原条件性暂缓已反转，本项纳入本次交付 | `MSG-2` |
| `MSG-7` | 覆盖补全：A2 / A4 / B3~B7 / C1~C5（含 B4 `RESTRUCTURING` 事件码回源确认）+ 外部 Agent 上线（例外通道；**产物提交已归第一期 A5 主通道**）+ `task_timeline`↔`user_notification` 对账 SQL | `MSG-4` |
| `MSG-8` | 验证收口：真机消息 + 公告可见性 + 多副本幂等（双节点并发同一事件，落库仍一行）+ 开关 OFF 停发隐藏验证 + 引用回填（差距表 / Log / plan §3）。崩溃窗口对账 SQL 随 `MSG-7` 后续 | `MSG-5`、`MSG-6` |

### 3.10 平台开关（默认 OFF，停发 + 隐藏）

> **〔2026-11-15 二次裁定新增〕** 用户要求「消息与公告都可在平台配置中控制是否启用，只有启用后才可以在工作台中看到」。本节据此定义两个平台级开关与「停发 + 隐藏」语义。

**两个 `sys_config` 键**（键值表 `UNIQUE(config_key) WHERE deleted=0`，[V1__init_all.sql:710-723](file:///E:/yhzx/1027/helloai/helloai-start/src/main/resources/db/migration/V1__init_all.sql#L710-L723)）：

| 键 | 控制对象 | 默认 | 开启判据 |
|---|---|---|---|
| `notification.enabled` | 站内事件消息（`MSG-1`~`MSG-5`） | **OFF** | 值为 `"1"` 或 `"true"`（忽略大小写）才 ON |
| `announcement.enabled` | 置顶公告（`MSG-6`） | **OFF** | 同上 |

- **默认 OFF 靠键缺省**：不写 `sys_config` 种子，键不存在即 OFF。上线后行为与现状完全一致，管理员在「系统设置 → 通知配置」打开才生效。
- **读取逻辑镜像 `SysConfigServiceImpl.isRegisterEnabled()`**（[SysConfigServiceImpl.java:97-101](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/system/service/impl/SysConfigServiceImpl.java#L97-L101)，`"1"/"true"` 默认 OFF）。**禁止照抄 `QuotaWidgetSettings.isWidgetEnabled()` 的 `!"0"` fail-open**（[QuotaWidgetSettings.java:48-49](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/system/quota/QuotaWidgetSettings.java#L48-L49)，默认 ON）——本特性要求默认 OFF，fail-open 会让未配置时意外可见。新增 `isNotificationEnabled()` / `isAnnouncementEnabled()` 两方法。

**「停发 + 隐藏」三层语义**（用户确认口径，非「仅隐藏」）：

| 层 | OFF 时行为 | 落点 |
|---|---|---|
| **生成侧（停发）** | `NotificationService.publish()` **首行**查 `isNotificationEnabled()`，OFF 即 `return`，不写库、不发事件 | `NotificationServiceImpl.publish` |
| **读取侧（隐藏）** | 消息读取端点 OFF 时返回空：`pageByReceiver` 空列表、`unreadCount=0`、`stream` 不推帧;公告 `published` OFF 时返回空 | `UserNotificationController` / `AnnouncementController` 读取方法首行查开关 |
| **前端（不渲染）** | 铃铛 / 抽屉 / 工作台入口 / 公告条在各自开关 OFF 时均不渲染、不发请求;闸门分两套：铃铛 / 抽屉 / 工作台入口 = `isAdmin && notificationEnabled`，公告条 = `announcementEnabled && 有已发布置顶`（公告为平台级，**全员可见**，不看 `isAdmin`;`features` 端点取开关态，默认 OFF） | `NotificationBell.vue` / `AnnouncementBar.vue` / `Workbench.vue` 入口 |

- **ON 只产生新消息**：开关从 OFF→ON 后，仅此后发生的业务事件产生消息;**历史不回填**（与 §4 边界 3 一致）。OFF 期间发生的事件永久不补发。
- **公告写入不受 `announcement.enabled` 限制**：管理员可在 OFF 时预先撰写/发布草稿（`notice:edit`），只是前端不展示;打开开关即对全员可见。`publish` 停发只约束**事件消息**，公告是人工撰写、无「停发」语义，OFF 只隐藏读取与展示。
- **两开关独立**：可只开消息不开公告，或反之。`unreadCount` 角标 = 消息未读 +（公告 ON 时）公告未读;公告 OFF 时其未读不计入。
- **前端设置页**：「5 通知配置」段（[Settings.vue:574-608](file:///E:/yhzx/1027/helloai/helloai-ui/src/views/Settings.vue#L574-L608)）加两个 `el-switch`;`load()` 用 `isSwitchOn()`（同后端 `'1'`/`'true'` 口径，默认 OFF，**非** `!== '0'`）;`handleSave()` 经 `batchUpdateConfig` 写 `notification.enabled` / `announcement.enabled`（`'1'`/`'0'`）。原只读「站内通知」checkbox（`notifyChannels` 硬编码 `['web']`）属订阅偏好空壳，本特性不接管（§4 边界 2）。〔2026-11-16 订正〕原稿写 `=== '1'`，与后端对 `true` 的口径分叉（库里手写 `true` 时后端已生效、设置页显示关闭），实现已改 `isSwitchOn()`，见文末修订记录第六次 / 第八次条目。

---

## 4. 边界与风险（诚实边界）

落地后**仍不成立**的事，必须照此登记，不得因「消息表有了」而宣称「提醒可用」：

1. **SSE 仍是单进程可见**。`WorkbenchEventHub` 的连接表在进程内存里，多副本部署下某副本推不到的帧依然推不到。本设计靠「未读列表补齐」兜住了**数据**，没兜住**时机**——真·跨副本实时需引入 Redis pub/sub 或 MQ 广播，属不含项。
2. **只做站内**，不外发。邮件 / IM webhook / 短信未接入；`Settings.vue:797` 那个硬编码的 `['web']` 仍是个空壳开关，本设计不接管、也不假装它生效。
3. **历史不回填**。消息从钩子上线那一刻起才有，已发生的死信、失败、超时不会补发。
4. **两类事件的实例标识尚未回源确认**：B4「子任务卡 `RESTRUCTURING`」在 `TimelineEventCode` 中未扫到独立码，C4 属 Agent 域不写 `task_timeline`——两者在 `MSG-7` 动手前必须回源确认可用事件码/写点，确认不到则该条**不接**（宁可不提醒，也不做一条会重复的键）。报告类（A3/C3/C5）本条已解决：三类都有现成码（§3.5），键借时间线行 id。
5. **聚合只预留形态**。第一期除 `SUBTASK_NO_CANDIDATE` 与派发重试类显式标 `BUCKET` 外全部逐条落库。若真机出现刷屏，处置是在映射表上加一行 `BUCKET`，不是加用户级订阅开关（开关属不含项）。
6. **公告不自动生成**。镜像内无 `.git`、无 `doc/`、无 build-info（§2.3），公告正文依赖人工撰写；`flyway_schema_history` 只能当素材，写不出「这次改了什么业务」的人话。
7. **消息权限模型沿用现有 RBAC**，不新增数据权限规则；`receiver_id` 越权读他人消息由接口层过滤挡住，**不依赖前端隐藏**。
8. **主通道的覆盖面等于时间线的覆盖面**。钩子只看得见写了 `task_timeline` 的事件；某个新节点若只改状态、不写事件，就**不会**被提醒，且这种遗漏是静默的。对策不是给消息再加一套扫描，而是把「重要状态变化必须写时间线」当作既有纪律来看——`MSG-7` 的对账 SQL 只覆盖已登记码，覆盖不到未登记的码。另有一处**已知的写点绕过**：`task_cancelled` 经 `TaskServiceImpl` 直插、不走 `recordEvent`（§3.3），钩子同样看不见——第一期不映射它，日后要提醒须先收编写点。
9. **偏离既有纪律之处只有一处，且要指名**：`SubTaskNotificationServiceImpl` 的全局 best-effort（类注释 :34-35「投递口失败绝不向上抛」）在**机器通道**成立；本设计对 `URGENT` 级要求写失败必须 error 留痕并进对账（§3.6），比原纪律更严，但**不改变业务事务结果**。实现时须在新代码注释里写明依据本文件，并在 `MSG-3` 的验证里证明「业务回滚时正常路径不留半条消息、失败路径独立事务的消息仍在」。

---

## 5. 引用材料

- 治理：`doc/文档体系分类与治理规则.md`（§3.3 `design/` 准入三条 / §5 文档头 / §6 命名与分期标签冻结 / §9 自检清单）
- 号段登记：`doc/HelloAI 实现差距表.md` §0 编号口径表（`MSG-x`，2026-11-12 建号；实测 `doc/**` 全库无 `MSG-` 号段 ⇒ 不撞号）
- 同源设计：`doc/design/HelloAI 助理对话与上下文窗口设计.md`（SSE 协议与授权口径先例）
- 相关设计：`doc/design/Agent_Event_Stream.md`（时间线事件 ≠ 站内消息，两者分工：时间线供事后审计，消息供当下叫醒）· `doc/design/HelloAI 交付编排能力设计.md`
- 复用形状：`agent_inbox`（`V1__init_all.sql:776-803`）· `task_plan_attempt`（`V146__plan_state_machine_and_attempts.sql:105-142`）· `event_consumption_log`（`V18__event_consumption_log.sql:35-36`）
- 主通道依据：`helloai-common/.../constant/TimelineEventCode.java`（事件码注册表）· `helloai-core/.../task/service/TaskTimelineService.java:39-53`（`REQUIRED` / `REQUIRES_NEW` 两写入口及其注释）· `helloai-core/src/main/resources/mapper/TaskTimelineMapper.xml:11-30`（唯一 `INSERT`，id 应用侧预生成；另见 §3.3 第二写点）
- 现状依据：`SubTaskNotificationServiceImpl.java:34-35,140-153` · `WorkbenchEventHub.java:36-37,65-75,105-120` · `PlanReviewDialog.vue:195-203,961-962` · `AdminConfigController.java:35-97` · `MainLayout.vue:139-151,158-159` · `Workbench.vue:542-565` · `Settings.vue:574-607,797` · `application.yml:89`
- 纪律来源：`doc/log/README.md` §10.1「不复制第二套控制面」· §10.5「状态不重复建模」
- 后续（未启动）：`plan/` 实施计划与差距表 §1 矩阵建条由 `MSG-1` 立项时同批产出

---

## 6. 修订记录

### 2026-11-15 — 对当前代码逐条复核后订正（未改生产代码）

**触发**：用户要求复评本设计可执行性，裁定「置顶先暂放、先打通消息通知、共用接口须覆盖外部 AI Agent 完成任务提交的提醒」。

**复核结论**：设计核心机制对当前代码成立（迁移 `V151` 空闲、钩子点 intact、雪花 id、SSE 骨架、首批事件码均在）。订正 3 处 + 范围裁定：

1. **第二写入口**（§2.1 / §3.3 / §4 边界 8）：原文「写入口只有 `recordEvent` 一处」不成立——`TaskServiceImpl.changeStatus` 任务取消时直接 `taskTimelineMapper.insert` 写 `task_cancelled`（[TaskServiceImpl.java:246-251](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/impl/TaskServiceImpl.java#L246-L251)）。id 唯一性论证不受影响（两处均 `ASSIGN_ID`），但「挂一处即全覆盖」需加 caveat；第一期不映射 `task_cancelled`，无实际损失。
2. **外部 Agent 提交归主通道**（§3.2 / §3.5 A5 / §3.9）：实测外部 `submitResult` 与内部执行器共用 `ExecutionResultHandler.handleReport`，成功写 `sub_task_execute_submit`（[ExecutionResultHandler.java:369-380](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/agent/command/ExecutionResultHandler.java#L369-L380)）。故新增 `SUBTASK_SUBMITTED` 取代原 `EXTERNAL_ARTIFACT_SUBMITTED`，纳入第一期 A5；原「外部产物提交归 `MSG-7` 例外通道」作废。
3. **事件码行号刷新**（§3.5 / §3.3）：`TimelineEventCode` 锚点整体 +1 漂移（码都在、行号移位），逐条订正；事件码总数 166 → 167（`[实测]`）。

**范围裁定**：置顶公告（`MSG-6`）条件性暂缓（§3.7 设计保留）；第一期 = A1 / A3 / A5 / B1 / B2，`MSG-1`~`MSG-5` + `MSG-8` 消息侧。〔2026-11-15 二次裁定已反转此暂缓——公告纳入交付，见下方第二次条目;本段保留为首次裁定历史〕

**钩子简化**（§3.6 / §3.9 `MSG-3`）：`recordEventInNewTx` 委托 `recordEvent`（[TaskTimelineServiceImpl.java:61](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/impl/TaskTimelineServiceImpl.java#L61)），一处钩子覆盖两入口，事务语义自动跟随。

### 2026-11-15（第二次）— 用户反转暂缓 + 新增平台开关，立项实现

**触发**：用户裁定「这个生成计划执行吧……置顶可以做，但是可以在平台配置中控制是否启用，消息也是，只有启用后才可以在工作台中看到」。经 AskUserQuestion 确认两项口径：① 开关语义 = **停发 + 隐藏**（非仅隐藏）;② 交付范围 = **首批 + 公告**（`MSG-1`~`MSG-6` + `MSG-8` 核心验证，`MSG-7` 扩展覆盖列后续）。

**修订内容**：

1. **反转 `MSG-6` 暂缓**（头部优先级裁定 / §3.5 第一期范围 / §3.7 banner / §3.9 `MSG-6` 行）：首次裁定的「置顶公告条件性暂缓」全部反转为「纳入本次交付」。原暂缓文字按追加式惯例保留并加反转注记，不删除;§3.7 设计据此实现。
2. **新增 §3.10 平台开关**：`notification.enabled` / `announcement.enabled` 两个 `sys_config` 键，**默认 OFF**（键缺省即 OFF，无需种子）;读取镜像 `isRegisterEnabled()`（`"1"/"true"`），**禁用** `QuotaWidgetSettings` 的 fail-open;「停发 + 隐藏」三层（`publish` 早退 / 读取返回空 / 前端不渲染）;ON 只产生新消息、历史不回填;两开关独立;公告写入不受开关限制（OFF 只隐藏读取与展示）。
3. **§3.9 登记登录可读端点**（CODE_STYLE §43）：新增 `/user-notification/features` 返回两开关态;显式登记登录可读读取端点（按归属过滤、无动作码）与写入/管理端点（`notice:view`/`notice:edit`）的分界;开关 OFF 时读取端点返回空。
4. **`MSG-8` 依赖订正**：由 `MSG-5`、`MSG-7` 改为 `MSG-5`、`MSG-6`（公告纳入本次，收口须覆盖公告;崩溃窗口对账 SQL 随 `MSG-7` 后续）。
5. **差距锚点订正**：头部「`G-033` 起为可用水位」过期——实查差距表 §0，`G-033`（执行环境）/`G-034`（审核侧）已占用，本次登记 **`G-035`**;§1 矩阵建条、§1.1 索引登记 `MSG-x`。
6. **迁移水位实查**：`[实测]` 磁盘最大 `V150__conversation_derivation_lineage.sql`，**`V151` 空闲**（项目记忆中 `V151`/`V152`/`V155` 为过期登记，磁盘不存在）;本次 `V151` 消息表 / `V152` 公告三表 / `V153` RBAC。〔2026-11-15 第三次裁定：三迁移合并为单一 `V151__notification_announcement.sql`，本条「V151/V152/V153 三分」为合并前历史口径，见下方第三次条目〕
7. **Status `Planned`→`Active`**：立项文档 `doc/plan/HelloAI 站内消息与置顶公告实施计划（2026-11-15）.md` 已建，§3 为逐条进度唯一事实源。

**范围裁定（第二次，取代首次）**：本次交付 = `MSG-1`~`MSG-6` + `MSG-8` 核心验证;`MSG-7`（A2/A4/B3~B7/C1~C5 扩展覆盖 + 外部 Agent 上线例外通道 + `task_timeline`↔`user_notification` 对账 SQL + B4 `RESTRUCTURING` 事件码回源）列后续阶段。首次「`MSG-1`~`MSG-5` + `MSG-8` 消息侧、`MSG-6` 暂缓」口径作废，保留于上方首次条目作历史。

### 2026-11-15（第三次）— 三迁移合并为单一小版本 `V151`

**触发**：用户裁定「建议数据库版本 151-153 合并成一个，而非 3 个，依次 plan 任务是一个小版本」。本批 `MSG-1`~`MSG-6` + `MSG-8` 属同一个小版本发布，迁移按版本聚合而非按表拆分。

**修订内容**：

1. **迁移合并**：原 `V151__user_notification.sql`（消息表）/ `V152__announcement.sql`（公告三表）/ `V153__notification_announcement_rbac.sql`（RBAC）三个**未提交、未应用**的迁移合并为单一 `V151__notification_announcement.sql`，内分三段：§1 `user_notification` + 两索引、§2 `announcement`/`announcement_revision`/`announcement_seen`、§3 RBAC（`notice:view`/`notice:edit` 权限 + 角色授予 + 菜单 + DO $$ 断言，断言标签 `[V153]`→`[V151]`）。SQL 内容为三原文件的忠实拼接，无逻辑改动。
2. **覆盖「一份迁移只做一件事」纪律**：§3.8 迁移纪律注记、§6 第二次条目 #6 均加合并注记;原三分口径保留为历史，不删除。
3. **`V153` 号段释放**：合并后 `V152`/`V153` 空闲，`V153` 仍留给后续 AD-5a 存量草案迁入（[entities.ts:173](file:///E:/yhzx/1027/helloai/helloai-ui/src/types/entities.ts#L173) 的「待 V153 存量迁入」与本特性无关，不受影响）。

**Flyway 安全性**：`[实测]` 合并前 local（172.20.0.7）与 dev（172.18.0.5）两库 `flyway_schema_history` 最大 installed_rank=140、水位 `V150`，`V151`/`V152`/`V153` 均未应用;三原文件 git 未跟踪（`??`）。合并属「未提交 + 未应用」迁移重组，不触发 checksum 校验、不产生混合 schema。

**验证**：`[实测]` 合并后 `UserNotificationDedupeIT`（4）+ `AnnouncementVersionReadIT`（6）共 10 用例在 Testcontainers 全新 PG 上 `BUILD SUCCESS`（0 失败 0 错误）。首次重跑曾报 `Found more than one migration with version 151`——根因是 `target/classes/db/migration/` 残留合并前的旧编译副本（Maven 资源拷贝不删除已移除源文件），非 SQL 缺陷;外科式删除三个旧副本（非 `mvn clean`，避免 wiping 已编译 class）后复跑全绿。

### 2026-11-16（第四次）— 外部静态审查逐条取证 + 修复批（A1/A3 + B1~B6）

**触发**：用户贴外部静态审查要求核验（「你看一下，这个检查结果是否正确？」）。逐条取证判定：8 项中 7 项成立、A2 误报，另发现审查遗漏的 A3;用户裁定「把上面的问题修正吧」。

**判定要点**：

- **A2 误报**：审查称「工作台第二入口缺失」——实际已实现（[Workbench.vue:548-567](file:///E:/yhzx/1027/helloai/helloai-ui/src/views/workbench/Workbench.vue#L548-L567)，`v-if notif.visible` + 未读徽标 + 铃铛开抽屉，与全局铃铛共享同一 store），无需修。
- **A3（审查遗漏）**：`sub_task_no_candidate` 的唯一写点在孤儿巡检 catch 块，作用域拿不到 taskId（[SubTaskPendingOrphanTask.java:199-207](file:///E:/yhzx/1027/helloai/helloai-job/src/main/java/com/helloai/job/task/SubTaskPendingOrphanTask.java#L199-L207)），原钩子归属解析恒空 → 消息静默丢失。审查「其余 4 个都是真 subTaskId 没问题」不成立。

**修复内容**（8 项）：

1. **A1 深链归属**（§3.2 / §3.5）：`NotificationServiceImpl.publish` 的 `ref_type` 改取 `event.sourceType`（钩子按 subTaskId 有无给 TASK/SUB_TASK），注册表按类型固定的 refType 仅作例外通道未给 sourceType 时的兜底;`NotificationType` 新增 `FINAL_REPORT_REVIEW_STUCK`（TASK 型，URGENT），`task_final_report_max_review_reached` 改映射它——一并修正「taskId 被当子任务 id」死链与「子任务进入死信」文案错。首批激活类型 5 → 6。
2. **A3 归属兜底**（§3.5 B2 注记）：钩子 taskId 缺省时经 subTaskId 查 `SubTaskMapper.selectById` 解析归属（仍在 task 域内，消息域不反向依赖）。
3. **B1 SSE 反代缓冲**：`/user-notification/stream` 与 `/requirement-conversation/workbench/stream` 响应头补 `X-Accel-Buffering: no` + `Cache-Control: no-cache`（与对话流 `streamSse` 同口径）。nginx `/api/` 通用 location 未配 SSE 参数（[nginx.server.conf:25-32](file:///E:/yhzx/1027/helloai/nginx.server.conf#L25-L32)），25s 心跳已重置读超时，主风险是帧缓冲而非 300s 断流;响应头方案对任意反代生效，nginx 配置本体不动。
4. **B2 降级边界**：`incrementBucketCount` 纳入与 insert 同款 try-catch——异常穿过 publish 的 `@Transactional` 代理边界会把共享事务标 rollback-only，外层业务 commit 失败（PG 侧 SQL 错误即中止事务，与钩子是否吞异常无关）。
5. **B3 草稿校验**：公告标题超 255 字拒绝（400），对齐列宽 `VARCHAR(255)`。
6. **B4 死列**（§3.8）：`announcement.expires_at` 从 V151 §2 与 `Announcement` 实体删除（无写入方也无读取方）;`user_notification.expires_at` 保留（读取侧有过期过滤，MSG-7 清理器将写入）。V151 未提交未应用、两库水位 V150，删除安全。
7. **B5 TASK 深链**：抽屉 TASK 跳转改 `/tasks?taskId={refId}`（TaskList 已支持该过滤参数）;类型筛选补「报告审查达上限」项。
8. **B6 并发发布**：publish 改走 `getManageableForUpdate()`（`LIMIT 1 FOR UPDATE` 行锁）串行化并发发布，防两人同时 publish 撞 `uq_announcement_revision`;管理读取仍走无锁 `getManageable()`。

**测试**：四个测试类同批更新——`NotificationMappingTest` B1 断言改新类型;`NotificationServiceImplTest` +3 例（主通道 refType 跟随 sourceType / 例外通道兜底 / BUCKET 累加异常降级）;`TaskTimelineServiceImplTest` 构造器 4 参 +1 例 A3 兜底;`AnnouncementServiceImplTest` publish stub 改行锁方法 +1 例超长标题。

**验证**（`[实测]` 2026-11-16）：定向 44 例全绿;`bash scripts/ci/ci-gate.sh --no-clean --offline` 五门全过（0 项失败）——单测 3910（core 3644 / job 111 / api 155，较修复前 +5 恰为新增用例）、静态守卫全过、前端 type-check + build 通过、B 级 IT 54 例全过（Testcontainers 真实 PG）。

**未修 / 不变**：A2（误报）;MSG-7 暂缓项;nginx 配置本体;真机验证并入 `MSG-8`（需启动后端 + 应用 V151，按迁移流程待用户授权）。

### 2026-11-16（第五次）— `MSG-8` 真机收口 + 两处真机缺陷修复

**真机通过项**：铃铛停靠/悬浮二选一（拖出 / 拖回 / 点击 / 刷新保持）、开关 OFF 停发隐藏、公告全流程（草稿 → 发布 v1 → 公告条 + 版本已读 → 关闭 → 归档）、A1 消息行 + 角标 + 抽屉 + 深链 + 已读回写、落库提交后 SSE 实时推送（未读数无刷新更新）。

**两处缺陷与口径订正**：

1. **任务无归属人 ⇒ 消息静默零输出**：钩子「无主任务跳过」本身正确（消息接收者即任务归属人），缺陷在 `TaskServiceImpl.createTask` 未写 `owner_id`，导致 HTTP 建任务链路恒无接收者。修上游：建任务落当前用户；无用户态（外部通道）保持 null，行为不变。**设计不变**：§3.5「无主任务不发」保持。
2. **SSE 断开被记成系统异常**：客户端断开后心跳/完成回调写帧，容器先抛 `AsyncRequestNotUsableException`，错误分派重抛时又可能表现为纯 `IOException`;两者此前都落 `Exception` 兜底记 ERROR。现约定：`AsyncRequestNotUsableException` 只记 debug;`IOException` 仅当「SSE 请求 + 响应已提交」时按连接收敛（返回 null，不写帧），其余请求的 `IOException` 仍按系统异常 500。**§4 边界补充**：SSE 通道的「客户端断开」属于正常终点，不产生错误帧、不产生 ERROR 级日志。

**未变**：`MSG-7` 扩展覆盖仍暂缓;SSE 单进程广播边界照 §4.1 登记。

### 2026-11-16（第六次）— 复核二轮：未读数标量类型、迁移历史时区、开关口径

**三项修复**：

1. **未读数下发字符串（必现，用户可见）**：`R<Long>.data` 经泛型擦除按 `Object` 序列化，`JacksonConfig` 的「非 ID 数值写数字」例外层按属性名生效、够不到泛型 data，故 `/user-notification/unreadCount` 与 `/announcement/unreadCount` 实际下发 `"0"`。前端后果：`"0" === 0` 为 false（未读 0 仍显示角标）、`store.unreadCount + announcementStore.unreadCount` 退化为字符串拼接（消息 2 + 公告 1 显示 `"21"`）。取治本侧：两接口改 `R<Integer>` + `Math.toIntExact`（计数不超过 int），`JacksonConfigLongSerializationTest` 增「盲区 3」用例锁住「`R<Long>` 为字符串 / `R<Integer>` 为数字」。分页 `total/pages/current` 走的是属性级判定，不受影响。
2. **迁移历史时间偏 8 小时**：`flyway_schema_history.installed_on` 为 `timestamp without time zone`，Flyway 写入的是 JVM 墙钟（实际 10:15），JDBC 会话时区为 UTC、实体字段为 `OffsetDateTime`，直读把墙钟当 UTC 再按 `spring.jackson.time-zone` 转 +08:00，展示成 18:15。修法：`AnnouncementMapper.selectSchemaHistory` 改 `installed_on AT TIME ZONE 'Asia/Shanghai'`（应用展示时区即该值），转换后为真实时刻 `02:15Z`，前端按本地时区渲染回 10:15。SQL 内注释写明「不可省」的原因，防后人简化回裸列。
3. **开关判定口径不一致**：`Settings.vue` 读配置只认 `'1'`，后端 `SysConfigServiceImpl` 认 `'1'` 或 `'true'`。库里手写 `true` 时后端已生效、设置页显示「已关闭」，再保存一次会把开关静默写回 `0`。修法：设置页改用 `isSwitchOn()`（同后端口径），保存仍写 `'1'/'0'`。

**文案订正**：「暂无可用执行者」摘要原为「系统将自动重试派发」，对同属该类型的 `task_plan_skill_unmatched`（技能无承接者，等人工配 Agent）不成立；改为「需人工介入或调整承接配置」，三类来源（`sub_task_no_candidate` / `sub_task_dispatch_no_alternative` / `task_plan_skill_unmatched`）均成立。

**订正一条复核误判**：复核意见称「`announcement.expires_at`（V151:36）是死列，实体无字段、全仓无读写」。取证：V151:36 是 `user_notification.expires_at`，`announcement` 表无该列（V151 §2 建表语句内）；该列有读取方——`NotificationServiceImpl.pageByReceiver` 的过期过滤 `expires_at IS NULL OR expires_at > now()`，实体 `UserNotification.expiresAt` 也在。当前无写入方是有意为之：不设过期即永不过期，写入归 `MSG-7` 清理器。不作为缺陷处理。

**业务语义记一笔**：`TaskServiceImpl.createTask` 新增 `setOwnerId(CurrentUser.userId())` 是为消息投递（接收者 = 任务归属人）而动业务字段。边界已核：`pageTasks` / `listActiveTasksForOwner` 对「自己的 or 无归属」宽松并入（`owner_id IS NULL OR owner_id = 当前用户`），存量任务不消失;派生任务继承 `parent.getOwnerId()`（`TaskDerivationServiceImpl` 三处），不受影响。

### 2026-11-16（第七次）— 铃铛点击改为开合切换（停靠与悬浮同口径）

**改动**：铃铛点击语义统一为**开合切换**——抽屉已打开时再点即 `closeDrawer()`（与点遮罩关闭同效），抽屉关闭时才 `openDrawer()`。落点两处：`Workbench.vue` 的 `onDockBellClick`（停靠铃铛）、`NotificationBell.vue` 的 `onBellClick`（悬浮铃铛）。拖拽结束补发的 click 仍由 `suppressClick` 拦截一次，不误触发开合。

**动机**：铃铛可拖出为悬浮件后，「点开侧边栏 → 再点一次收回」是自然操作；原实现第二次点击无动作，只能去点遮罩或 ×。

**验证**（`[实测]` 2026-11-16，in-app browser + `vue-tsc`）：停靠铃铛点击开 → 再点关；拖出为悬浮铃铛后同样开 → 关;`vue-tsc --noEmit` 0 错误。

### 2026-11-16（第八次）— §3.10 规格与代码对齐 + `seen` 上报入参硬化（外部复核）

**触发**：用户转交外部复核意见并裁定按其排序修复：①（必改）公告已读上报的 500 与版本锁死；② 前端错误白名单按方法区分；③④ 死代码与重复路径键清理；⑤⑥ 本文与 `CODE_STYLE` 的规格订正。

**文档订正（本节此前与代码不一致，代码为正确侧）**：

1. **§3.10 前端闸门拆两套**：原表把公告条并入 `isAdmin && enabled`。实际公告是平台级、全员可见——`AnnouncementBar` 的 `visible = announcementEnabled && published != null`（`helloai-ui/src/stores/announcement.ts`），`isAdmin` 只约束铃铛 / 抽屉 / 工作台入口（`helloai-ui/src/stores/notification.ts`）。
2. **§3.10 开关读取口径**：原稿 `load()` 写 `=== '1'`，实现已改 `isSwitchOn()`（`'1'` 或 `'true'`，与后端 `SysConfigServiceImpl` 同口径，见第六次条目）；规格段同步订正并加注记，防后人照旧稿改回。

**同批代码修复（要点，逐文件与验证见同日 `LOG-20261116` 条目）**：

1. **`markSeen` 入参硬化**：`POST /announcement/seen/{id}?version=N` 登录即可调、id 与 version 全由客户端给。原实现直接 upsert：不存在的 id 触发 `announcement_seen.announcement_id` 外键违约 → 系统异常 500 + ERROR 日志；超大 version 经 `GREATEST` 把 `seen_version` 永久抬高，该账号此后收不到公告未读。现：公告不存在（含逻辑删除）→ 幂等忽略；版本钳制到 `[0, 最新快照版本]`，快照为 0（从未发布）→ 忽略。
2. **前端错误白名单按方法生效**：博查 Key 的 GET（状态探针，需静默）与 PUT（保存，需报错）同路径，原按 URL 前缀一刀切，保存失败被静默。改为条目可限定 method（该项仅 `get` 放行）；`Settings.vue` 保存 catch 改为静默，由拦截器统一回显后端 msg（避免双提示）。
3. **死代码与重复路径键**：`Workbench.vue` 的 `onPanelCommand` / `refreshAll` 已无引用（「更多」下拉注释后的遗留），删除；`paths.ts` 同值双键 `webSearchApiKey` / `webSearchApiKeyState` 合并为一个（GET/PUT 按方法区分）。

**验证**：定向 `AnnouncementServiceImplTest` 13 例全绿（新增「脏 id / 超大版本钳制 / 负版本」3 例）；`vue-tsc --noEmit` 0 错误；全量 `ci-gate --no-clean` 见同日 `LOG-20261116` 条目。

**未变**：`MSG-1`~`MSG-6`、`MSG-8` 状态不变（已完成）；`MSG-7` 暂缓。
