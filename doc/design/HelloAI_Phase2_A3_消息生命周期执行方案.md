# HelloAI Phase 2 A3：统一消息生命周期（N-008 收口）执行方案

> 主轴：把「Message / Inbox 超时未消费 → 重新分派」（差距表 N-008，P1 PARTIAL）收口为**统一口径登记 + inbox 过期机制最小补缺**。对应 P2-A 批第三步（D1=甲拍板：A1/A2 后 A3 随后；R2 后 A1 主体已砍，A2 已验证收口）。
>
> 文档约定：§0 事实基线（五载体 × 六动词盘点）；§1 任务定位与拍板；§2 范围边界；§3 统一生命周期口径（本方案的核心交付物——责任矩阵与语义定死）；§4 最小补缺设计（inbox 过期机制）；§5 Step 分解与验收口径；§6 工程规范适配；§7 提交约定。
>
> **状态**：已实施（2026-09-05，LOG-20260905-008；用户拍板 D-A3-1=口径+最小补缺、D-A3-2=outbox 恢复推至 A4）。差距表 N-008 已按完成规则移除。

---

## §0 事实基线（2026-09-05 代码盘点）

### 0.1 五载体 × 六动词覆盖矩阵（现状）

| 载体 | ACK | Claim | Timeout | Retry | Reassign | Dead Letter |
|---|---|---|---|---|---|---|
| **agent_inbox 消息** | `ack` 工具（isRead=1，幂等） | 无（纯通知，不承载工作） | **无**（`expireTime` 死字段：V1 建表至今零读零写） | 无 | 无（由子任务层覆盖，见 0.2） | 无 |
| **SubTask 子任务** | — | `claimSubTask`（DB 原子认领，乐观锁） | IN_PROGRESS 卡死→BLOCKED（`SubTaskTimeoutTask`，30s 扫）；ASSIGNED 10min 未 claim→重派（`AssignedSubTaskTimeoutTask`） | 无（fail-close：BLOCKED 需人工） | 4 条链：`redispatchAssignedTimeout` / `redispatchForFallback`(N11) / PENDING 孤儿兜底 / §4.1 遗留兜底 | BLOCKED≈人工死信（fail-close 设计，memory 红线） |
| **执行记录 agent_execution_record** | markSuccess/markFailed（CAS） | markRunning（CAS，PENDING→RUNNING） | RUNNING 超时→失败 + 计 N11（`ExecutionCompensationTask`） | 无 | Poller 重放孤儿 PENDING（`ExecutionCommandPoller`：1s 扫 / 60s 阈值 / 批 20，只兜底不主推） | 无 |
| **outbox** | CONFIRMED | — | 超时未 confirm | 指数退避重试（有限次） | — | **FAILED 死终态，无人工恢复入口**（盘点发现的附带缺口，D-A3-2 拍板推至 A4） |
| **RabbitMQ 消息** | manual ACK | — | 无 TTL | **无业务重试**（nack requeue=false 直达 DLX） | — | DLX→dlxQueue→`DlxAlertConsumer`（台账 mq_dead_letter_archive V60 + 告警，永不 requeue） |

### 0.2 三个关键发现

1. **N-008 的原始诉求「超时未消费 → 重新分派」已在子任务层闭环**：`AssignedSubTaskTimeoutTask`（`assigned-timeout-minutes: 10`）覆盖的正是「消息派给 Agent 无人消费 → 子任务重新分派」。inbox 消息本身是通知不承载工作；子任务被重派后旧消息经 `reassigned=true` 标记被消费侧跳过（helloai-duty skill 已教）。
2. **真正的 inbox 缺口是卫生问题**：`expireTime` 字段死置（`AgentInbox` L53 有字段、`send()` 不写、`getUnread()` 不查）——unread 消息永不过期、无限堆积、永被 pullTasks 重投。
3. **Retry 语义五载体五套**（outbox 退避 / MQ 无重试直达 DLX / SubTask fail-close 人工 / 执行记录无重试 / inbox 无）——各套在其载体上语义大多合理，缺的是**统一登记**（这正是差距表「避免不同业务各自实现一套」的实锤与正确解法：先定死口径，未来新载体按口径对齐，而非另起第六套）。

### 0.3 附带事实（A4 前瞻）

③ Poller 重定位**实际已完成**（T5 重塑语义：`consumer-mode: POLLER` 现值，Poller 已统一降级为孤儿兜底，yml L190 注释明示）。A4 剩余主体 = N-010 MQ 业务治理（DLQ 业务语义 / 消费失败分类 / **人工恢复（含 outbox FAILED 重放入口，D-A3-2）** / 幂等）。

---

## §1 任务定位与拍板

**preflight 四问**：改什么——补 inbox 消息过期机制（TTL 写入 + 投递过滤 + 定时归档）+ 写统一生命周期口径文档；为什么——N-008 P1 PARTIAL 唯一遗留，expireTime 死字段与 unread 无限堆积是实案；类型——补功能（小闭环）+ 文档口径登记；不做什么——不建 MessageLifecycle 抽象框架（§50.7 平行架构风险，各载体机制大多合理）、不动 SubTask/outbox/MQ 现有生命周期（各自语义已定死且经过验证）、不做 outbox 恢复入口（推 A4）。

### 决策记录

| # | 决策点 | 拍板 | 理由 |
|---|---|---|---|
| D-A3-1 | N-008 落地形态 | **口径统一 + 最小补缺**（否决「统一抽象框架」与「纯文档」） | 框架与 CODE_STYLE §2.3「新增抽象前必须回答」冲突；纯文档无法关差距项（死字段实案存留） |
| D-A3-2 | outbox FAILED 无人工恢复入口 | **推至 A4 批** | 与 N-010「DLQ 业务语义 / 人工恢复」同范畴，本轮不扩界 |

---

## §2 范围边界

**做**：

1. 统一生命周期口径登记（本方案 §3——五载体 × 六动词责任矩阵 + 语义定死）
2. inbox 过期机制最小补缺：`send()` 写 `expireTime`（TTL 可配）→ `getUnread()` 过滤已过期 → 定时归档任务（软删 `isArchived=1`）
3. 配置：`helloai.agent.inbox.*`（新 `AgentInboxProperties`）
4. 定向单测 + 全量回归 + 差距表/LOG 回填

**不做**（明文边界）：

- 不建 MessageLifecycle 统一抽象接口/基类（§50.7）
- 不动 SubTask / 执行记录 / outbox / MQ 的现有生命周期语义
- 不做 outbox FAILED 恢复入口（A4 / N-010 批）
- 不做按消息类型差异化 TTL（统一默认值起步，留演进）
- 零 DDL：`expire_time` 字段 V1 已有，无需新版本（checksum 零风险）

---

## §3 统一生命周期口径（核心交付物）

> 本节是「统一登记」本体：五载体在六动词上的语义**就此定死**。新增消息类载体时按本矩阵对齐口径，禁止另起一套语义。

### 3.1 语义定义（六动词）

| 动词 | 统一语义 |
|---|---|
| **ACK** | 消费方确认「已收到且已处理」，此后投递链不再重投。幂等是硬要求（重复 ACK 无副作用） |
| **Claim** | 消费方对**工作载体**的独占认领（DB 原子 / 乐观锁）。纯通知类载体无此动词 |
| **Timeout** | 载体停留某状态超过阈值后的**被动判定**，由扫描任务承担，触发后续动作（重派 / 失败 / 归档） |
| **Retry** | 失败后的**有限次**自动重投（指数退避）。Retry 不是无限重试——超过上限进 Dead Letter 或死终态 |
| **Reassign** | 工作从一方转移到另一方（换 Agent / 换消费者）。通知类载体不重派——重派工作载体，旧通知标记跳过 |
| **Dead Letter** | 不可自动恢复的终态隔离（台账 + 告警 + 人工介入）。fail-close 哲学：不确定场景停等人工，不静默丢弃 |

### 3.2 责任矩阵（定死，谁在哪做）

| 载体 | ACK | Claim | Timeout | Retry | Reassign | Dead Letter |
|---|---|---|---|---|---|---|
| inbox 消息 | `McpToolServiceImpl.ack` | —（通知无认领） | **A3 新增**：`InboxExpireCleanupTask` 过期归档 | —（通知不重试） | —（子任务层覆盖） | 归档即软死信（`isArchived=1`，保留审计） |
| SubTask | — | `claimSubTask` | `SubTaskTimeoutTask` / `AssignedSubTaskTimeoutTask` | —（fail-close） | 4 条重派链（见 §0.1） | BLOCKED（人工介入，唯一出口） |
| 执行记录 | markSuccess/markFailed | markRunning | `ExecutionCompensationTask` | — | `ExecutionCommandPoller`（孤儿重放） | —（超时即失败记账） |
| outbox | CONFIRMED | — | relay 超时 | 指数退避（有限次） | — | FAILED（A4 补人工恢复入口） |
| MQ 消息 | basicAck | — | —（无 TTL，靠业务侧超时链） | —（不重试，防毒消息循环） | — | DLX→台账+告警（`DlxAlertConsumer`） |

### 3.3 inbox 过期机制设计（最小补缺）

```text
send() 落库: expire_time = now + TTL(默认 168h)
     │
     ▼
getUnread() / pullTasks: WHERE expire_time IS NULL OR expire_time > now
     │  （防清理任务周期窗口内投递已过期消息）
     ▼
InboxExpireCleanupTask（helloai-job，5min 一扫，批 200）
     │  扫 expire_time < now AND is_archived = 0
     ▼
软删: is_archived = 1（复用现有归档语义：getUnread/pullTasks 天然过滤）
     │  （零 DDL；过期归档 = 系统替收件人归档，与手动归档同语义不同来源）
     ▼
日志计数（本轮归档 N 条）——审计事实由 agent_event / timeline 承担（best-effort 既有约定）
```

**软删 vs 物理删 trade-off**：选软删（`isArchived=1`）——保留审计查询能力（与 fail-close 哲学一致，不丢数据）；物理删除不可逆且 inbox 本就是通知副本。代价：归档来源与手动归档不可区分（可接受——清理任务日志有计数，且本任务不做归档来源字段——那需要 DDL，违反零 DDL 边界）。

**先查后更（PG 兼容关键）**：`archiveExpired` 采用 SELECT LIMIT + 按 id 集中 UPDATE（参照 `AgentDutyLeaseServiceImpl.expireLeases` 批量模式）——PostgreSQL 不支持 `UPDATE ... LIMIT`，LIMIT 只能落在 SELECT 上；UPDATE 侧重验 `is_archived=0`，与并发手动归档谁先归档谁生效。

**配置**（`helloai.agent.inbox.*`，新 `AgentInboxProperties`）：

| key | 默认 | 说明 |
|---|---|---|
| `expire-hours` | 168（7 天） | 消息 TTL；`send()` 写入 `expire_time` |
| `cleanup-enabled` | true | 清理任务开关（逃生口） |

清理任务扫描间隔（5min fixedDelay）与批量上限（200）固定值起步（与 `DutyLeaseExpirationTask` 同模式），不过度配置化。

---

## §4 Step 分解与验收口径

| Step | 内容 | 依赖 | 验收口径 |
|---|---|---|---|
| S1 | `AgentInboxProperties`（common）+ `send()` 写 expireTime + `getUnread()` 过期过滤 + `archiveExpired()` service 方法 | 方案定稿 | 单测：send 后 entity 的 expireTime = now+TTL；getUnread 查询含 `expire_time IS NULL OR > now` 条件；存量 NULL 消息行为不变（防存量数据被误过滤） |
| S2 | `InboxExpireCleanupTask`（helloai-job，ShedLock + 5min fixedDelay + 批 200 + enabled 开关） | S1 | 单测：过期且未归档→归档；无过期→零动作；异常只告警不抛出（参照既有 job task 模式） |
| S3 | application.yml 配置段 + 定向测试 + 全量回归（基线 1085+） | S1+S2 | 定向全绿 + `mvn -pl helloai-core test -DskipTests=false` 与 `mvn -pl helloai-job test -DskipTests=false` 全绿 |
| S4 | 文档回填：差距表 N-008 PARTIAL→DONE（按差距表头部完成规则处置）+ 本方案状态 + LOG-20260905-008 | S3 | 差距表口径与代码事实一致；LOG 完整记录决策与验证 |

---

## §5 工程规范适配

- **零 DDL**：`expire_time` V1 已有（checksum 零风险）；不新增版本号
- **依赖方向**：`InboxExpireCleanupTask`（job）→ `AgentInboxService`（core agent 域接口），与 `DutyLeaseExpirationTask`→`AgentDutyLeaseService` 同模式，合法
- **事务**：`archiveExpired` 先查后更（SELECT LIMIT + 按 id UPDATE，PG 兼容），`@Transactional` 单事务收口；`send()` 沿用既有事务边界
- **测试**：`AgentInboxServiceTest` 补用例（spy + query chain mock 既有模式，注意 MP 3.5.9 lambda 缓存坑——实体 lambda 需在测试外预热或显式 TableInfo 缓存）；`InboxExpireCleanupTaskTest` 参照 `ExternalAgentFallbackTaskTest`（纯 Mockito）
- **命名**：`helloai.agent.inbox.*` 前缀（对齐 `helloai.agent.health` 惯例）

---

## §6 提交约定

- 代码笔（common + core + job + yml + 测试）与文档笔（本方案状态 + 差距表 + LOG）分离提交
- commit message 走 UTF-8 文件 + `git commit -F`；git push 由用户执行

---

## 修订记录

### R1（2026-09-05）：A3 执行方案定稿

- **背景**：P2-A 批第三项（D1=甲）。盘点发现 N-008 原始诉求「超时未消费→重派」已在子任务层闭环（AssignedSubTaskTimeoutTask 10min），真实缺口 = inbox 过期机制死置（expireTime 零读零写）+ 五载体生命周期语义无统一登记。
- **拍板**：D-A3-1 = 口径统一 + 最小补缺（否决抽象框架与纯文档两案）；D-A3-2 = outbox FAILED 恢复入口推至 A4（与 N-010 人工恢复同批）。
- **范围**：§3 口径矩阵定死 + inbox 过期三件套（TTL 写入 / 投递过滤 / 定时归档软删）；零 DDL；不动其余四载体现有语义。

### R2（2026-09-05）：实施完成 + PG 兼容修正

- **实施**：`AgentInboxProperties`（helloai.agent.inbox.expire-hours=168 / cleanup-enabled=true）+ `send()` 写 TTL + `getUnread()`/`countUnread()` 过期过滤 + `archiveExpired()` + `InboxExpireCleanupTask`（5min 扫 / 批 200 / ShedLock）+ yml 配置段。
- **关键修正**：`archiveExpired` 初版直接 `UPDATE ... LIMIT`（PostgreSQL 非法语法，定向单测的 mock 层无法暴露）——实施中按 `expireLeases` 先查后更惯例重写（SELECT LIMIT + 按 id 集中 UPDATE，UPDATE 重验 is_archived=0 防并发手动归档冲突）。
- **验证**：`AgentInboxServiceTest` 12 例（+6）、`InboxExpireCleanupTaskTest` 3 例全绿；core 全量 1091（基线 1085 + 6）、job 全量 66（+3），Failures/Errors=0。
- **回填**：差距表 N-008 按完成规则移除（总览行 + §13 章节，后续章节重排编号，最后更新日期 2026-09-05）；e2e（真实 DB 行为）与门铃/duty/N11 同批待环境。
