> **状态：HISTORICAL / REFERENCE**。当前开发请以根目录 Current/Target/Gap/Plan 为准。

# HelloAI Phase 2 A4：MQ 业务治理（N-010 收口）执行方案

> 主轴：把差距表 N-010（MQ 业务治理，PARTIAL P2）收口为**两个人工恢复入口（outbox FAILED 重入 + 死信台账重放）+ 消费失败分类 + 口径登记**。对应 P2-A 批收尾项（D-A3-2 拍板：outbox FAILED 人工恢复入口推至 A4，与 N-010 同批；③ Poller 重定位已确认 T5 完成）。
>
> 文档约定：§0 事实基线（2026-09-05 代码盘点）；§1 任务定位与拍板；§2 范围边界；§3 设计（恢复入口 / 重放语义 / 失败分类 / 口径登记）；§4 Step 分解与验收口径；§5 工程规范适配；§6 提交约定。
>
> **状态**：已实施（2026-09-06，S1~S4 全部落地：outbox FAILED 人工恢复 + 死信台账重放 + 消费失败分类 + 口径登记；详见修订记录 R2 + LOG-20260905-010）。

---

## §0 事实基线（2026-09-05 代码盘点）

### 0.1 现状链路

```text
【投递侧】ExecutionCommandService 同事务写 agent_command_outbox(PENDING)
      │
      ▼
OutboxRelayTask（helloai-job，1s 扫 / ShedLock / 批上限）
      │  PENDING → publish → SENT ──confirm ACK──▶ CONFIRMED
      │        │失败/confirm NACK/超时            （指数退避回流 PENDING，
      │        ▼                                   retry_count ≥ maxRetry → FAILED 死终态）
      │   markFailed / markFinalFailed
      ▼
【消费侧】executionCommandQueue → MqExecutionCommandConsumer（MANUAL ACK）
      │  解析失败/缺 eventId → ACK（跳过）
      │  消费成功 → ACK
      │  消费异常 → basicNack(requeue=false, false) → DLX
      ▼
dlxQueue → DlxAlertConsumer（台账 mq_dead_letter_archive V60 → 告警 log.error → ACK）
      │  replayed_at 列已预留（V60 注释：重放工具回填，本轮不实现）
      ▼
无（doorstop：无重放入口）
```

### 0.2 关键事实

1. **outbox FAILED 无人工恢复入口**（V19 表有 error_msg，但无 API/SQL 辅助重入；D-A3-2 拍板推 A4）。
2. **死信台账 V60 已预留 `replayed_at`**，注释明示「重放工具按 original_routing_key 重发，本轮不实现」——A4 是兑现该预留。
3. **消费侧无失败分类**：任意异常一律 NACK → DLX。而实际消费链（`LocalExecutionCommandConsumer`）契约是「失败以 status 表达不抛异常」（内部 catch-all 兜底并 markFailed 执行记录），真正逃逸到 MQ 边界的异常几乎只有**技术类**（DB/Redis 抖动、装配意外）；`BizException` 类（业务终态断言，如「Agent 不存在」「执行命令不能为空」）防御性逃逸时，重投也只会再次跳过——进 DLX 属 poison 噪音。
4. **幂等现状**：`AbstractIdempotentConsumer`（Redis 24h TTL + DB `event_consumption_log` 双层）；`event_consumption_log` 唯一索引 `(message_id, consumer)`；失败也插 FAILED 行（`markFailed`）。**推论：重放前必须重置去重台账**（详见 §3.2 D-A4-5）。
5. **约束**（A3 §3.2 责任矩阵已定死）：outbox FAILED 补人工恢复入口；MQ 不业务重试（nack 直达 DLX 防毒消息循环）；MQ 是执行链基础设施，不成为第二业务控制面；技术噪声只动 outbox 表 / 死信台账，不落业务 timeline（架构参考 L429-432）。

---

## §1 任务定位与拍板

**preflight 四问**：改什么——outbox FAILED 重入 + 死信台账重放两入口（Admin REST）+ 消费失败分类（ACK/NACK 分流）+ 口径登记；为什么——N-010 P2 PARTIAL 唯一遗留批（P2-A 收尾项），FAILED 死终态与「重放预留未兑现」是实案；类型——补功能（小闭环）+ 文档口径登记；不做什么——不推翻 A3 矩阵（MQ 仍不业务重试）、不做业务级熔断（D-A4-4 口径关闭）、不建消息生命周期统一框架、不动执行链 6 步语义。

### 决策记录

| # | 决策点 | 拍板 | 理由 |
|---|---|---|---|
| D-A4-1 | 人工恢复入口形态 | **Admin REST API**（helloai-api 转发 + core 编排） | 运维动作不应走 agent 鉴权 MCP 面；Admin*Controller 既有惯例，api 只转发、编排在 core（§6.3 红线） |
| D-A4-2 | 消费失败分类落地 | **ACK/NACK 分流**：`BizException`（含 cause 链）→ ACK + 分类日志（重投无意义，防 poison 死信噪音）；其余异常 → NACK → DLX（技术故障待人工重放） | 对齐 A3「MQ 不重试防毒循环」；零 DDL；解析失败 ACK 口径不变 |
| D-A4-3 | 重放粒度 | **单条 + 时间窗口列表查询**：outbox FAILED 窗口分页 + 死信台账窗口分页，恢复按 id 单条触发，CAS 防重复 | 运维可精确挑选或批量恢复故障窗口（页面挑单选） |
| D-A4-4 | 业务级熔断 | **本轮不做 + 口径关闭** | MQ 无业务重试 → 无毒消息循环 → per-eventId 熔断无实际场景；outbox 已有限次退避 + 终态隔离；登记 WONTFIX 理由入差距表口径 |
| D-A4-5 | 重放幂等重置 | 重放前**重置去重台账**：删 `event_consumption_log` 该 (message_id, consumer) 行 + 删 Redis 去重键，再重发，发成功才写 `replayed_at` | §0.2-4 推论：FAILED 台账行会使命中 `isDuplicate` 导致重放被静默跳过；台账行是幂等用途非审计用途（审计由 mq_dead_letter_archive 保留） |

---

## §2 范围边界

**做**：

1. outbox 人工恢复：`AgentCommandOutboxService.requeueFailed(id)`（CAS `status=FAILED→PENDING`，`retry_count=0`、`next_retry_at=now`、清 error_msg——由 Relay 自然拾回）+ `listFailed(from, to, page, size)` 窗口分页
2. 死信台账重放：`DeadLetterRecoveryService`（core `dlx` 包）——窗口分页查询（含 replayed 过滤）+ `replay(id)`（去重重置 → `RabbitTemplate.send` 原 exchange/routingKey 重发 → CAS 写 `replayed_at`）
3. 消费失败分类：`MqExecutionCommandConsumer.onMessage` 异常分流（BizException 链 → ACK；其余 → NACK→DLX），分类日志明示
4. Admin REST：`AdminMqRecoveryController`（`/api/admin/mq-recovery`，4 端点，纯转发 + DTO 装配）
5. 口径登记：DLQ 业务语义 / 消费失败分类 / 告警 / 幂等 / 业务级熔断（WONTFIX 理由）写入本方案 §3.4，并修订 A3 文档矩阵 A4 备注
6. 定向单测 + core/api 全量回归 + 差距表 N-010 按完成规则处置 + LOG-20260905-010

**不做**（明文边界）：

- 不推翻 A3 §3.2 矩阵：MQ 仍不业务重试、不 requeue、不 TTL 化
- 不建业务级熔断（D-A4-4）
- 不改执行链 6 步语义 / 不动 `LocalExecutionCommandConsumer` 兜底契约
- 不新增告警通道（维持 log.error 口径，登记即可）
- 零 DDL：`replayed_at` V60 已有、outbox 列全齐，无新版本号

---

## §3 设计

### 3.1 outbox 人工恢复（S1）

```text
运维挑选 FAILED 行                    requeueFailed(id)
（窗口分页列表，可看 error_msg）  ──────────▶  CAS UPDATE
                                              WHERE id=? AND status='FAILED'
                                              SET status='PENDING', retry_count=0,
                                                  next_retry_at=now(), error_msg=NULL
                                                   │
                              ┌─ 影响 0 行（已非 FAILED：已重入/已确认/不存在）→ 返回冲突（fail-close，不静默）
                              └─ 影响 1 行 → 返回成功
                                                   │
                                                   ▼
                     OutboxRelayTask 下轮扫描自然拾回 PENDING → 正常投递全链
```

- 重入只支持 `status='FAILED'`（PENDING/SENT 由 Relay 在管，CONFIRMED 终态禁手改——防止「confirmed 后重发」引入重复执行窗口）。
- `retry_count=0`：人工恢复是「重新开始一轮有限退避」，与自动重试计数分离（error_msg 清空便于区分旧故障信息）。
- payload 损坏行（deserialize 终态失败）重入后仍会 FINAL_FAILED：可接受，运维应先看 error_msg（日志/列表均可见）。

### 3.2 死信台账重放（S2）

```text
replay(id)
   │
   ├─ 1) 读取台账行；replayed_at 非空 → 返回「已重放」冲突（CAS 幂等）
   │
   ├─ 2) 解析 body 取 eventId / messageId（ExecutionCommandMqMessage 载荷）——
   │      取不到 → 抛 BizException「无法识别消息标识，禁止重放」（fail-close，不做盲发）
   │
   ├─ 3) 重置去重台账（D-A4-5）：DELETE event_consumption_log WHERE message_id=?（幂等行）
   │      + DEL redis key `mq:dedup:{messageId}`
   │
   ├─ 4) RabbitTemplate.send(original_exchange, original_routing_key, body)
   │      发送异常 → 不写 replayed_at、返回失败（仍可再次重放，无状态污染）
   │
   └─ 5) 发送成功 → CAS UPDATE mq_dead_letter_archive SET replayed_at=now()
          WHERE id=? AND replayed_at IS NULL（并发双点重放只生效一次）
```

- **重发载体**：body 存的是 `executionCommandQueue` 的原始消息体（JSON 文本）；content-type 从 headers 快照回放（取不到默认 JSON）；交换器/路由键用 `original_exchange` / `original_routing_key`（V60 注释既定语义）。
- **幂等重置的次序**：先重置后重发——若重发失败，台账行仍在且未标重放，可重试整个 replay，无副作用（重置是删「失败记录」，不影响审计）。
- **跨载体一致性**：重放后的消息重新走消费全链（幂等/执行/CAS 防覆盖），不出现第二套执行路径。

### 3.3 消费失败分类（S3）

```text
onMessage 消费异常
   │
   ├─ BizException（含 cause 链命中）→ 分类日志「business-terminal」→ basicAck
   │     （业务终态：执行链已落失败/跳过，重投只会再次失败——不产生死信）
   │
   └─ 其余异常（RuntimeException / DataAccessException 等技术类）
         → 分类日志「technical-unrecoverable-here」→ basicNack(requeue=false, false) → DLX → 台账 + 告警
```

- 现有「解析失败 / 缺 eventId → ACK」口径不变（本就是 poison message 出口）。
- 分流仅变 MQ 边界行为，不进执行链、不落业务 timeline（§0.2-5 约束）。

### 3.4 口径登记（差距表 N-010 七子项收口口径）

| 子项 | 收口口径 |
|---|---|
| DLQ 业务语义 | DLX = 「已确认投递但消费被技术故障打断」的隔离区；业务终态失败不进死信（§3.3 分流）；毒消息（解析失败/缺 eventId）ACK 跳过；唯一出口 = 台账人工重放（§3.2） |
| 消费失败分类 | 三分法：POISON（解析失败/缺 eventId → ACK）/ BUSINESS_TERMINAL（BizException → ACK）/ TECHNICAL（其余 → DLX） |
| poison message | 解析失败 + 缺 eventId + 业务终态断言均不重投不循环 |
| 告警 | 维持 log.error 双通道（OutboxRelay FINAL_FAILED 日志 + DlxAlertConsumer 台账告警日志）；平台无独立告警通道，此为既有事实口径（N-011 回退告警亦同模式） |
| 人工恢复 | 本方案 §3.1 + §3.2 两入口（Admin REST） |
| 消息幂等 | 已有：Redis+DB 双层去重 + `event_consumption_log` 唯一索引 + outbox `event_id` 唯一 + 重放前重置（D-A4-5）补全闭环 |
| 业务级熔断 | WONTFIX（本轮）：MQ 无业务重试 → 无循环 → per-eventId 熔断无场景；outbox 有限次退避 + FAILED 终态隔离已具「投递侧熔断」语义。若未来引入消费重试/新载体，再评估 |

---

## §4 Step 分解与验收口径

| Step | 内容 | 依赖 | 验收口径 |
|---|---|---|---|
| S0 | 本方案文档定稿 | — | 决策记录 D-A4-1~5 齐备；§3 覆盖两入口 + 分流 + 口径 |
| S1 | `AgentCommandOutboxService` 加 `requeueFailed(id)` / `listFailed(from,to,page,size)`（Mapper 显式 SQL）+ Impl | S0 | 单测：FAILED→PENDING 归零重入；非 FAILED 行 CAS 冲突不生效；窗口过滤仅 FAILED；分页正确 |
| S2 | core `dlx/DeadLetterRecoveryService`（+ Impl）：分页查询（replayed 过滤）+ `replay(id)`（去重重置→重发→replayed_at CAS） | S0 | 单测：已重放冲突不重发；eventId 缺失 fail-close；发失败不标 replayed_at；成功路径重置台账 + 按原 exchange/routingKey 重发 + 写 replayed_at；Redis 键删除 |
| S3 | `MqExecutionCommandConsumer` 异常分流（BizException 链→ACK；其余→NACK）+ 分类日志 | S0 | 单测：BizException→ACK 不 NACK；RuntimeException→NACK 不 ACK；解析失败/缺 eventId 口径不变 |
| S4 | `AdminMqRecoveryController`（`/api/admin/mq-recovery`：outbox 列表/重入 + 死信列表/重放，DTO 装配 + `R` 封装） | S1+S2 | api 单测：4 端点转发与返回封装正确；controller 无编排（断言只调 core 服务） |
| S5 | 全量回归 | S1~S4 | `helloai-core`、`helloai-api` 单测全绿；job 零改动（rg 确认） |
| S6 | 文档回填：差距表 N-010 按完成规则移除 + LOG-20260905-010 + A3 方案 §0.1/§3.2 矩阵 A4 备注修订（R3）+ 本方案 R2（实施记录） | S5 | 差距表无 N-010 残留；LOG 完整记录决策与验证；A3 矩阵口径与代码事实一致 |

---

## §5 工程规范适配

- **零 DDL**：`replayed_at`（V60）/ outbox 列全齐；不新增版本号
- **api 分层红线（§6.3）**：Controller 仅参数接收 + DTO 装配 + `R` 封装；编排（CAS/重发/重置）全部在 core 服务接口
- **Mapper 显式 SQL**：新增查询走 `@Select` 显式 SQL（无 MyBatis 容器时 lambda 缓存不可用，AgentSessionMapper 先例）
- **CAS 幂等**：`requeueFailed`（WHERE status='FAILED'）、`replayed_at`（WHERE replayed_at IS NULL）——重复点击零副作用
- **事务**：`requeueFailed` 单 UPDATE 自带原子性；`replay` 的「重置→重发→标注」不做大事务（重发不可回滚），按序 best-effort，先重置后重发保证可重试
- **发送载荷**：重发用 `RabbitTemplate.send(exchange, routingKey, Message)` 显式字节（对齐 `ExecutionCommandMqPublisher` 前车之鉴，不依赖 SimpleMessageConverter）
- **测试**：S1 沿用既有 outbox 测试 spy+TableInfo 预热先例；S2 纯 Mockito + mock JdbcTemplate；S3 参照既有 consumer 测试

---

## §6 提交约定

- 代码笔（core 服务/消费分流 + api 控制器 + 测试）与文档笔（本方案 + 差距表 + LOG + A3 修订）分离提交
- commit message 走 UTF-8；git push 由用户执行

---

## 修订记录

### R1（2026-09-05）：A4 执行方案定稿

- **背景**：P2-A 批收尾项（A3 文档 §0.3 已前瞻：A4 主体 = N-010 MQ 业务治理 + outbox FAILED 重放入口 D-A3-2）。
- **拍板**：D-A4-1=Admin REST / D-A4-2=ACK-NACK 分流 / D-A4-3=单条+窗口 / D-A4-4=熔断口径关闭 / D-A4-5=重放前重置去重台账。
- **范围**：两恢复入口 + 消费失败分类 + 口径登记；零 DDL；不动执行链语义。

### R2（2026-09-05）：实施完成

- **实施**：S1 `requeueFailed(id)` CAS 重入 + `listFailed` 显式 SQL 窗口查询；S2 `DeadLetterRecoveryService`（去重重置 → 原路由重发 → `replayed_at` CAS）；S3 `MqExecutionCommandConsumer` 异常分流（BizException 链 → ACK / 技术 → NACK→DLX）；S4 `AdminMqRecoveryController` 4 端点纯转发；§3.4 七子项口径登记；零 DDL。
- **验证**：core **1118**（基线 1100 + 净增 18：outbox +4 / dlx +12 / consumer 分类 +2）全绿；api **27**（+4）全绿；job 零改动，install 后复跑 66 全绿（本地仓库 core 快照陈旧 fork 缺类的工程坑，LOG-20260905-008 已登记）。
- **回填**：差距表 N-010 按完成规则移除（总览行 + §13 章节，后续重排编号，结论清单去「MQ / 异步链路进一步治理」，最后更新 2026-09-05）；A3 方案矩阵 A4 备注同步修订（R3）；LOG-20260905-010。

