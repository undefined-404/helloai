# HelloAI 站内消息与置顶公告设计

> **Status**: `Planned`（设计已定稿，未启动实现；任务登记见本文 §3.9 `MSG-1`~`MSG-8`，实施计划与逐条进度待 `plan/` 立项后以其 §3 为唯一事实源）
> **Scope**：
> - **含**：① 站内**事件消息**的对象模型与幂等契约；② 各业务模块推送消息的**单一出口接口**（`NotificationService`）；③ 需人工处置 / 异步完成节点的**埋点清单**；④ **置顶公告**（管理员可编辑、带版本与编辑历史、按版本判已读）；⑤ 前端呈现位（顶栏铃铛 + 消息中心 + 公告条 + 工作台红框位）；⑥ 多副本（k3s / 集群）下的重复推送防线。
> - **不含**：站外渠道（邮件 / 短信 / IM webhook 外发）、消息订阅偏好（逐类开关）、移动端推送、@提及与私信、消息内直接操作（审批按钮走原业务接口）、跨租户隔离。
> **差距锚点**：`MSG-x`（差距表 §0 编号口径表）；差距项待 §1 矩阵建条（`G-033` 起为可用水位）
> **最后更新**：2026-11-12（设计初版；口径来自 2026-11-12 四轮调研分析，全程未改生产代码）

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
| `task_timeline` | 单一写入口：`TaskTimelineService.recordEvent` / `recordEventInNewTx`（[TaskTimelineService.java:39-53](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/TaskTimelineService.java#L39-L53)），Mapper 只有一个自定义 `INSERT`（[TaskTimelineMapper.xml:12-27](file:///E:/yhzx/1027/helloai/helloai-core/src/main/resources/mapper/TaskTimelineMapper.xml#L12-L27)），`id` 由应用侧雪花预生成 ⇒ 写前即可拿到行 id。事件码注册表 [TimelineEventCode.java](file:///E:/yhzx/1027/helloai/helloai-common/src/main/java/com/helloai/common/constant/TimelineEventCode.java) 已含 166 条码（`[实测]` 枚举常量计数），本文 §3.5 的 A 类、C 类与 B1/B2/B5 **均已有现成码**；B4 未扫到独立码、B7 属会话域、C4 属 Agent 域（见 §4 边界 4） | **消息的主埋点通道**：不新增调用点，在该写入口挂一张「事件码 → 是否生成消息」映射表；时间线行 id 即事件实例标识（见 §3.3） |
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
`PLAN_DRAFT_READY` / `PLAN_FAILED` / `PLAN_GATE_BLOCKED` / `PLAN_LEASE_RECALLED` / `DERIVED_PLAN_READY` / `FINAL_REPORT_READY` / `FINAL_REPORT_FAILED` / `TASK_DONE` / `SUBTASK_DEAD_LETTER` / `SUBTASK_NO_CANDIDATE` / `SUBTASK_BLOCKED` / `RESTRUCTURE_STUCK` / `REVIEW_INCONCLUSIVE` / `CLARIFY_STALLED` / `EXTERNAL_AGENT_ONLINE` / `EXTERNAL_ARTIFACT_SUBMITTED` / `ANNOUNCEMENT_PUBLISHED`。

### 3.3 幂等：键的粒度是「事件实例」，不是「任务」

用户提的「一个任务只推 1 条消息」口径**不成立**：同一任务同时会有草案就绪、报告完成、死信等多类消息；同一子任务可多次进 `DEAD_LETTER`（每次重派后再驳回）；拆解失败重试后再次失败是新一次事件。按任务去重会让第二次真实事故**静默丢失**——这是比重复推送严重得多的错误。

正确口径：**一类事件的一次发生 = 一条消息**。

「一次发生」需要一个**已经在库里唯一的标识**。勘察结论：**不自造，直接借时间线行 id**——`task_timeline.id` 在 `INSERT` 前由应用侧雪花生成（[TaskTimelineMapper.xml:12-27](file:///E:/yhzx/1027/helloai/helloai-core/src/main/resources/mapper/TaskTimelineMapper.xml#L12-L27)），一条事件行天然全局唯一，且**跨节点唯一**（写入口只有 `TaskTimelineServiceImpl.recordEvent` 一处，两个副本不可能各自造出同一逻辑事件的两次插入而 id 相同）。

```
主通道   dedupe_key = {NotificationType}:{timelineEventCode}:{task_timeline.id}
例外通道 dedupe_key = {NotificationType}:{sourceType}:{sourceId}:{instanceKey}
```

例外通道只服务于**不写 `task_timeline` 的事件**（澄清收敛属会话域、外部 Agent 上线属 Agent 域、公告发布属管理域）。其 `instanceKey` 一律取现成单源，不自造计数器：

| 事件 | instanceKey 来源 | 依据 |
|---|---|---|
| 拆解 / 重拆 / 派生拆解（会话域触发时） | `task.decompose_attempt_id` | [V146:30](file:///E:/yhzx/1027/helloai/helloai-start/src/main/resources/db/migration/V146__plan_state_machine_and_attempts.sql#L30)，注释明写它是「本次操作事件的幂等键」 |
| 子任务死信 / 驳回达上限 | `sub_task.attempt_total` | [V64:17](file:///E:/yhzx/1027/helloai/helloai-start/src/main/resources/db/migration/V64__sub_task_add_attempt_total.sql#L17)（全局共享重试预算，单一权威写入点） |
| 报告类（A3 / C3 / C5） | 走主通道，用 `task_final_report_generated` / `task_final_report_failed` / `task_final_report_max_review_reached` 各自行 id | 这三类**已有时间线码**（[TimelineEventCode.java:131-134](file:///E:/yhzx/1027/helloai/helloai-common/src/main/java/com/helloai/common/constant/TimelineEventCode.java#L131-L134)），无需再找 attempt 列 |

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

映射表每行四列：`事件码 → 消息类型`、`severity`、`形态`（`ONCE` 逐条 / `BUCKET` 窗口折叠 / `SILENT` 显式不发）、`是否第一期`。默认策略：**未登记的事件码不发消息**（白名单，不是黑名单）——时间线有 166 条码，默认全发等于把审计流水复制一遍到用户眼前。

**A 类：异步长任务完成——用户只需知道，不需要动作**

| 编号 | 事件码 `[实测]` | 消息类型 | severity | 形态 |
|---|---|---|---|---|
| A1 | `task_plan_generated`（TimelineEventCode:164） | `PLAN_DRAFT_READY` | NORMAL | ONCE |
| A2 | `task_plan_draft_derived`（:158，ADR-008 派生拆解） | `DERIVED_PLAN_READY` | NORMAL | ONCE |
| A3 | `task_final_report_generated`（:132） | `FINAL_REPORT_READY` | NORMAL | ONCE |
| A4 | `task_auto_completed`（:128） | `TASK_DONE` | NORMAL | ONCE |

> A1 同时兑现 `PlanReviewDialog.vue:199-200` 已写明的「产出后站内提醒」承诺；前端 3s 轮询 + 5 分钟封顶（[PlanReviewDialog.vue:961-962](file:///E:/yhzx/1027/helloai/helloai-ui/src/views/task/components/PlanReviewDialog.vue#L961-L962)）在消息上线后降级为可选体验，不作为唯一通知路径。

**B 类：必须人来拍板——不发消息就一直卡着**

| 编号 | 事件码 `[实测]` | 消息类型 | severity | 形态 |
|---|---|---|---|---|
| B1 | `sub_task_dead_letter`（:53）· `sub_task_review_dead_letter`（:101，核验熔断）· `task_final_report_max_review_reached`（:134，报告审查达上限） | `SUBTASK_DEAD_LETTER` | **URGENT** | ONCE |
| B2 | `sub_task_no_candidate`（:93）· `sub_task_dispatch_no_alternative`（:60）· `task_plan_skill_unmatched`（:169，技能无承接者将静默等待） | `SUBTASK_NO_CANDIDATE` | HIGH | **BUCKET**（1h） |
| B3 | 长等待 10 轮仍无候选（由 `BUCKET` 计数达到阈值时升级一条 URGENT，不新增事件码） | `SUBTASK_NO_CANDIDATE` | URGENT | ONCE |
| B4 | 子任务卡 `RESTRUCTURING` 的事件码 **待 `MSG-7` 回源确认**（`TimelineEventCode` 内未扫到独立码） | `RESTRUCTURE_STUCK` | HIGH | ONCE |
| B5 | `sub_task_auto_review_inconclusive`（:50）· `subtask_review_inconclusive`（:118） | `REVIEW_INCONCLUSIVE` | HIGH | ONCE |
| B6 | 其余 `markManualIntervention`（全仓 28 处命中）——逐个映射到其伴随的事件码；无伴随码的归入 `MSG-7` | `MANUAL_INTERVENTION` | HIGH | ONCE |
| B7 | 澄清轮数用尽仍收敛（`TD-8` stall 强制收敛、不建任务）**属会话域，不写 `task_timeline`** | `CLARIFY_STALLED` | NORMAL | 例外通道 |

**C 类：需要补救——发生了就是坏事**

| 编号 | 事件码 `[实测]` | 消息类型 | severity | 形态 |
|---|---|---|---|---|
| C1 | `task_plan_failed`（:160）· `task_plan_gate_blocked`（:162） | `PLAN_FAILED` / `PLAN_GATE_BLOCKED` | HIGH | ONCE |
| C2 | `task_plan_timeout_recovered`（:170，拆解超租约被巡检恢复） | `PLAN_LEASE_RECALLED` | HIGH | ONCE |
| C3 | `task_final_report_failed`（:131）· `task_final_report_outline_failed`（:135） | `FINAL_REPORT_FAILED` | HIGH | ONCE |
| C4 | 外部 Agent 认领后超时未提交（超时任务侧，属 Agent 域） | `EXTERNAL_CLAIM_TIMEOUT` | NORMAL | 例外通道 |
| C5 | `task_final_report_review_failed`（:139）· `task_final_report_review_unparseable`（:145） | `REPORT_REVIEW_FAILED` | HIGH | ONCE |

**第一期范围（用户裁定的优先级在此表达，不写成进度）**：A1 / A3 / B1 / B2 四类先接；其余按 `MSG-7` 补齐。

**显式标 `SILENT`（映射表里写明「不发」，防止后人顺手加）**：子任务逐条进度 / 派发心跳 / `task_plan_llm_call_start`·`end` / `task_final_report_llm_call_start` / `sub_task_dispatch_prepare`·`skip_*` / 门禁 `warned` / 草案单条 `regenerated`·`rejected` / 澄清单轮问答。理由：这些是**过程**，用户看了不能做任何事；`agent_inbox` 的五类机器通知也不做人侧复制。

### 3.6 投递与呈现

- **落库与推送解耦**：数据源是表，SSE 是加速器。客户端断线重连后拉 `unreadCount` + 列表即可补齐，不依赖在线时刻。
- **消息写在哪条事务上，随时间线写点而定——这里不能想当然。** `TaskTimelineService` 有两个写入口（[TaskTimelineService.java:39-53](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/TaskTimelineService.java#L39-L53)）：`recordEvent` 默认 `REQUIRED`「让正常路径的事件与业务同生共死」；`recordEventInNewTx` 是 `REQUIRES_NEW`、注释明写「**失败路径的审计写点专用**」，理由是不能随失败的业务一起回滚。钩子挂在两者之上，于是消息天然分成两种命运：
  - **正常路径**（A1/A2/A3/A4/B5/C1…）：与业务同事务，业务回滚则消息一并消失——**这正是想要的**，不会出现「消息说草案好了、其实那批草案回滚了」。
  - **失败路径**（C1 的失败码 / C3 / C5 等经 `recordEventInNewTx` 写出的）：落在独立事务，业务回滚不影响它——**这也是想要的**，事故已经发生，消息不该跟着一起被抹掉。代价是失败消息与业务不在同一原子域，崩溃窗口内可能「时间线有、消息没有」，由 `MSG-8` 的一条对账 SQL 兜（`task_timeline` 有映射码而 `user_notification` 无对应 `dedupe_key` 的行）。
- **不再另设「按 severity 决定是否同事务」的规则**。severity 只决定**呈现与提醒强度**，不决定事务语义；事务语义由写入口决定，与时间线保持同源。
- **对既有 best-effort 纪律的偏离点要说准**：`SubTaskNotificationServiceImpl` 的类注释是全局 best-effort——「全部投递口失败只 warn/error、绝不向上抛」（[SubTaskNotificationServiceImpl.java:34-35](file:///E:/yhzx/1027/helloai/helloai-core/src/main/java/com/helloai/core/task/service/impl/SubTaskNotificationServiceImpl.java#L34-L35)）。这条纪律对**机器通道**成立。人侧新链只在「映射表命中且写库异常」时保留同一降级（`NORMAL` 级失败不阻断业务），但 **`URGENT` 级（B1 死信 / B3 长等待）写失败必须 error 级留痕并计入对账**，不得只 warn 而过。
- **`agent_inbox` 那条链保持原样**，本设计不迁移它、不让它走新出口（它是机器通道，语义与文案模板都不同）。
- **SSE 在 `AFTER_COMMIT` 发**，避免推了一个已回滚的消息。帧体只放 `notificationId`。
- **前端两处**：① 顶栏**铃铛 + 未读数** + 消息抽屉（列表 / 全部已读 / 按类型筛选 / 点击跳深链）；② 用户红框指的位置——工作台右侧信息栏页签行右端（[Workbench.vue:542-565](file:///E:/yhzx/1027/helloai/helloai-ui/src/views/workbench/Workbench.vue#L542-L565) 的 `.wb-panel-head`，其 `.wb-panel-ops` 刷新/更多下拉已被注释但样式保留）作为**站内消息的最小入口**；全局铃铛挂 [MainLayout.vue:139-151](file:///E:/yhzx/1027/helloai/helloai-ui/src/layouts/MainLayout.vue#L139-L151) 外层，与 `QuotaFloatWidget` 同级。两处呈现同一份数据，不做两份状态。
- **置顶公告条**：页面顶部固定条（可折叠，折叠状态按用户记住），有未读新版本时展开并计入铃铛角标。

### 3.7 置顶公告与版本已读

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

### 3.8 数据模型（Flyway `V151` 起，以立项时实际水位为准）

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
    expires_at    TIMESTAMPTZ,
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

> 迁移纪律：**一份迁移只做一件事**（消息表 / 公告表 / 索引各一条），且上线前核对 `flyway_schema_history` 实际水位再定号——`helloai-start/src/main/resources/application.yml:89` 里 `validate-on-migrate=false`，校验失败的混合 schema 不会被启动拦下，号段只能靠实查水位确定。

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
| GET | `/announcement/published` | 登录即可读 | 当前置顶公告 + 我的 `seen_version` |
| POST | `/announcement/seen/{id}` | 登录 | 上报 `seen_version`（幂等：只单调递增） |
| GET/POST/PUT | `/announcement/*` | `notice:view` / `notice:edit` | 草稿、发布（`version+1` + 写 revision）、归档、历史列表 |
| GET | `/announcement/schemaHistory` | `notice:view` | 只读辅助视图，取 `flyway_schema_history` |

**分阶段**（`MSG-1`~`MSG-8`，号段于差距表 §0 同日登记；依赖为硬约束，按序做）

| 编号 | 内容 | 依赖 |
|---|---|---|
| `MSG-1` | `NotificationType` / `Severity` 枚举 + `NotificationEvent` + `NotificationService` 单一出口 + 文案注册表 + **事件码→消息映射表**（含 `ONCE`/`BUCKET`/`SILENT` 三形态）。单测锁三件事：同 `dedupe_key` 重复 publish 不产生第二行、未登记事件码不发、`SILENT` 显式登记项不发 | — |
| `MSG-2` | 迁移：`user_notification` + 两个索引（**含 `COALESCE` 唯一索引的 NULL 陷阱单测**）+ 实体/Mapper | — |
| `MSG-3` | **钩子接线**：`TaskTimelineServiceImpl.recordEvent` / `recordEventInNewTx` 两处写点后调 `publish`；SSE 在 `AFTER_COMMIT` 发；`URGENT` 写失败 error 留痕。单测锁「业务回滚 ⇒ 正常路径消息不留半条」「失败路径 `REQUIRES_NEW` ⇒ 业务回滚后消息仍在」 | `MSG-1` `MSG-2` |
| `MSG-4` | 首批映射表登记 A1 / A3 / B1 / B2（B2 用 `BUCKET` 1h）—— 并同批兑现 `PlanReviewDialog.vue:199-200` 的既有承诺 | `MSG-3` |
| `MSG-5` | 前端：顶栏铃铛 + 未读数 + 消息抽屉 + 深链跳转 + 工作台红框位最小入口 | `MSG-4` |
| `MSG-6` | 置顶公告：三表 + 版本已读 + `notice:edit` 管理页 + 顶部公告条 + 折叠态记忆 | `MSG-2` |
| `MSG-7` | 覆盖补全：A2 / A4 / B3~B7 / C1~C5（含 B4 `RESTRUCTURING` 事件码回源确认）+ 外部 Agent 上线与产物提交两类（例外通道）+ `task_timeline`↔`user_notification` 对账 SQL | `MSG-4` |
| `MSG-8` | 验证收口：真机消息可见性 + 多副本幂等（双节点并发同一事件，落库仍一行）+ 崩溃窗口对账 + 引用回填（差距表 / Log） | `MSG-5`~`MSG-7` |

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
8. **主通道的覆盖面等于时间线的覆盖面**。钩子只看得见写了 `task_timeline` 的事件；某个新节点若只改状态、不写事件，就**不会**被提醒，且这种遗漏是静默的。对策不是给消息再加一套扫描，而是把「重要状态变化必须写时间线」当作既有纪律来看——`MSG-7` 的对账 SQL 只覆盖已登记码，覆盖不到未登记的码。
9. **偏离既有纪律之处只有一处，且要指名**：`SubTaskNotificationServiceImpl` 的全局 best-effort（类注释 :34-35「投递口失败绝不向上抛」）在**机器通道**成立；本设计对 `URGENT` 级要求写失败必须 error 留痕并进对账（§3.6），比原纪律更严，但**不改变业务事务结果**。实现时须在新代码注释里写明依据本文件，并在 `MSG-3` 的验证里证明「业务回滚时正常路径不留半条消息、失败路径独立事务的消息仍在」。

---

## 5. 引用材料

- 治理：`doc/文档体系分类与治理规则.md`（§3.3 `design/` 准入三条 / §5 文档头 / §6 命名与分期标签冻结 / §9 自检清单）
- 号段登记：`doc/HelloAI 实现差距表.md` §0 编号口径表（`MSG-x`，2026-11-12 建号；实测 `doc/**` 全库无 `MSG-` 号段 ⇒ 不撞号）
- 同源设计：`doc/design/HelloAI 助理对话与上下文窗口设计.md`（SSE 协议与授权口径先例）
- 相关设计：`doc/design/Agent_Event_Stream.md`（时间线事件 ≠ 站内消息，两者分工：时间线供事后审计，消息供当下叫醒）· `doc/design/HelloAI 交付编排能力设计.md`
- 复用形状：`agent_inbox`（`V1__init_all.sql:776-803`）· `task_plan_attempt`（`V146__plan_state_machine_and_attempts.sql:105-142`）· `event_consumption_log`（`V18__event_consumption_log.sql:35-36`）
- 主通道依据：`helloai-common/.../constant/TimelineEventCode.java`（事件码注册表）· `helloai-core/.../task/service/TaskTimelineService.java:39-53`（`REQUIRED` / `REQUIRES_NEW` 两写入口及其注释）· `helloai-core/src/main/resources/mapper/TaskTimelineMapper.xml:12-27`（唯一 `INSERT`，id 应用侧预生成）
- 现状依据：`SubTaskNotificationServiceImpl.java:34-35,140-153` · `WorkbenchEventHub.java:36-37,65-75,105-120` · `PlanReviewDialog.vue:195-203,961-962` · `AdminConfigController.java:35-97` · `MainLayout.vue:139-151,158-159` · `Workbench.vue:542-565` · `Settings.vue:574-607,797` · `application.yml:89`
- 纪律来源：`doc/log/README.md` §10.1「不复制第二套控制面」· §10.5「状态不重复建模」
- 后续（未启动）：`plan/` 实施计划与差距表 §1 矩阵建条由 `MSG-1` 立项时同批产出
