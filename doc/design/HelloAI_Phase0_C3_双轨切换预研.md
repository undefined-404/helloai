# HelloAI Phase 0 C3 双轨切换预研

> 状态：Step 0-3 已执行——Step 0 落地（LOG-20260902-010）+ Step 1 预检通过（LOG-20260902-011，verify-c3-env 12/12）+ 灰度命中验证（LOG-20260903-003）+ Step 2 对账确认（LOG-20260903-007：窗口 0 WARN / 事件成对 / 无 RUNNING 滞留；人工审核埋点缺口已修复，迁移 SQL v2 已执行，c3gs-r4/r5 已入 DEAD_LETTER）+ Step 3 放量 25% 验收通过（LOG-20260903-009：造数 20 任务命中 5/20=25% 精确；违例 0 行；对账 0 WARN；P1 成对 / P3 无滞留；新样本终态投影 5/5 ok，存量 1 例已归因）。Step 4 全量档已配置 gray-percent=100 待重启验证（本地 25%→100，LOG-20260903-012）
> 日期：2026-09-02（更新 2026-09-03）
> 关联：`doc/design/HelloAI_Phase0_架构改造执行方案.md`（C3 节）、plan Task C3、LOG-20260902-006/007/008/009/010/011
> 事实来源优先级：代码与运行行为 > 本预研文档 > plan（本预研所有结论均以当前代码事实校验）

## 一、C3 目标与 plan 原文

plan Task C3（新 Runtime 接管 → 下线旧 Executor）：

| 步骤 | plan 原文 |
|---|---|
| 1 | 按 `run_id % 100 < N` 灰度打开 Feature Toggle |
| 2 | 监控双轨期事件对账，确认 100% 一致 |
| 3 | `agent.runtime.v2.enabled: true` 全量 |
| 4 | 删除 `LegacyExecutorAdapter` 和旧 Executor 路径 |
| 5 | 关闭 Feature Toggle（不再需要） |

依赖：C2 完成 + 双轨跑通 N 个 Run + 事件对账 100%。

## 二、现状核对（代码事实）

| 依赖项 | 状态 | 事实 |
|---|---|---|
| C1 契约 | ✅ | `agent/runtime` 四件套（AgentRuntime / AgentContext / AgentExecutionResult / ExecutionEnvironment） |
| C2 Adapter + 开关 | ✅ | `LegacyExecutorAdapter`（@ConditionalOnProperty `helloai.agent.runtime.v2-enabled` 默认 false）；**当前无任何调用方** |
| B3 事件对账 | ✅ | `EventReconciliationTask`（60s 集群单例）+ `EventReconciliationService`（业务表最近变更 10 分钟窗口 → 末条事件单向投影校验，不一致仅 WARN 不修正） |
| run_id 可得性 | ✅ | `AgentEventContextResolver.resolveRunId(taskId)` = `run-{taskId}-1`（ADR-001 §3.1，Phase 0 轮次固定 1）；`agent_event.run_id NOT NULL`（V65） |
| run_id 入命令链 | ❌ | `ExecutionCommand` 无 runId 字段；埋点处（TASK_ASSIGNED / executeOnce）才临时 resolveRunId |
| 运行期比例路由 | ❌ | `@ConditionalOnProperty` 只控制 Bean 存在性（装配期），无运行期百分比路由能力 |
| 统一执行入口 | ❌ | 执行由 `LocalExecutionCommandConsumer.consume`（`@TransactionalEventListener AFTER_COMMIT` + `@Async executionCommandExecutor`）与 DB Poller 驱动，直连 `SubTaskExecutionService`，不经 AgentRuntime 接口 |

## 三、关键现实校准（Code > Plan，5 项）

### 校准 1：`run_id` 灰度 → 按 `taskId` 灰度

`resolveRunId(taskId)` 现实输出 `run-{taskId}-1`（轮次恒 1，`task_iteration.round_num` 是报告生成后的回填快照，运行时无权威轮次字段）。即 **run_id 对同一 task 恒定，唯一变化维度是 taskId**。`run_id % 100 < N` 与 `taskId % 100 < N` 在本期等价；路由键直接用 taskId（或 subTaskId → taskId），无需先解析 run_id。ADR §8 引入 Plan 实体后才升级为 `run-{planInstanceId}`，届时路由键同步升级。

### 校准 2：开关形态不足 → 需要「总开关 + 灰度百分比」两层

- `helloai.agent.runtime.v2-enabled`（现有）：Bean 存在性总开关，语义保持；
- 新增 `helloai.agent.runtime.gray-percent`（0~100 整数，默认 0）：运行期路由比例，路由层每次读取（不回滚代码即可即时回退）。

### 校准 3：双轨实质是「入口形态双轨」，非执行逻辑分叉

代码事实：两条路径共享同一执行核心 `SubTaskExecutionService.executeOnce` + `ExecutionResultHandler` 回写：

- 旧直连：`LocalExecutionCommandConsumer.consume`（校验 → startIfNeeded → markRunning CAS → timeline → executeOnce → 回写）
- Runtime 路径：`LegacyExecutorAdapter.execute`（构造 ExecutionCommand → `executeCommand`：校验 → startIfNeeded → executeOnce → 回写）

对账（B3）验证的是「命令 → 事件 → 业务终态」链路在 Runtime 入口封装下无回归，而非逻辑分叉对照。两路径的差异面 = 入口校验顺序 / 参数翻译 / 异常降级行为（Adapter 将 BizException/异常契约化为 FAILED，不向上抛）。

### 校准 4：「删除旧 Executor 路径」的边界（Analyst 界定）

**删除**：`LocalExecutionCommandConsumer` 对 `onCommandCreated` / Poller 的直连消费路径，执行统一收敛到 AgentRuntime 接口（唯一出口）。
**保留**：`SubTaskExecutionService` / `executeOnce` / `ExecutionResultHandler` / `startIfNeeded` —— Adapter 内部依赖它们，删除 = 执行功能死亡。`MqExecutionCommandConsumer`（默认 CONDITIONAL 关闭的 MQ 骨架）与 C3 无关，不动。

### 校准 5：C3 步骤 4 之前必须先有「Runtime 调用方」

当前 Adapter 无调用方，即使 `v2-enabled=true` 也不会执行任何任务。C3 的 Step 0 必须补齐「统一执行入口 + 路由」，否则步骤 1-3 无对象。这是 plan 未覆盖的前置缺口。

## 四、目标态与修订步骤

```text
Step 0（前置实现，C3 一部分）✅ 已落地（LOG-20260902-010，LocalExecutionCommandConsumer 内 routeToRuntime/runViaRuntime）
  统一执行入口 + 路由层：
  - 路由决策：taskId % 100 < gray-percent → AgentRuntime（Adapter）执行；否则走旧直连
  - 路由点：一致性校验后、startIfNeeded 前（D1=A 已确认：改造 LocalExecutionCommandConsumer，收口单处）
  - record CAS + route 观察点 timeline 已补齐（executeCommand 不含 CAS/回写重复问题）
Step 1  灰度 5%：gray-percent=5，观察 ≥ N 个 Run 完成 ✅ 已执行（LOG-20260903-003，命中样本 c3gs-r1 全链闭环）
Step 2  对账确认：B3 窗口内 0 不一致 + 事件成对 + 终态投影一致（验收标准见 §六）✅ 已执行（LOG-20260903-007；人工审核路径埋点缺口已修复，存量例外 1 例已出窗口）
Step 3  阶梯放量 25% ✅ 已执行（LOG-20260903-009：gray-percent 5→25 重启生效，造数 20 任务命中 5 个（占比精确 25%），验收通过：违例 0 行 / 对账 0 WARN / P1 成对 / P3 无滞留 / 新样本终态投影 5/5 ok；脚本升级 v1.1 -WindowMinutes 按档位窗口统计占比）；100% 档放量由用户决策
Step 4  全量稳定：100% 保持 ≥ 1 天（建议），无新增 WARN/ERROR 类型 ✅ 已执行（LOG-20260904-001 多模型实测；用户决策：24h 观察提前收口，观察首轮全绿记录于 LOG-20260904-005，以回滚演练实测替代长期观察作为 Step 5 前置）
Step 5  下线旧直连路径（校准 4 边界）→ 执行统一走 AgentRuntime ✅ 已执行（LOG-20260904-006：回滚演练实测通过后删除 consume 旧直连分支，执行统一经 Runtime；全量回归 1047 全绿；4 类 inner agent 冒烟闭环）
Step 6  清理：gray-percent 固定 100（或移除路由分支）、v2-enabled 固化 true；
        删除路由层灰度分支，保留 AgentRuntime 接口为唯一执行契约 ✅ 已执行（LOG-20260904-006：routeToRuntime/grayPercent 字段删除，LegacyExecutorAdapter 去 @ConditionalOnProperty 恒注册；yml 键保留仅作文档语义，verify-c3 脚本继续兼容）
```

回滚预案（任意阶段）：

| 层级 | 动作 | 是否需要发版 |
|---|---|---|
| 灰度期 | `gray-percent=0`（配置刷新） | 否 |
| 全量期 | `gray-percent=0` + `v2-enabled=false` 双保险 | 否（重启或配置中心） |
| 下线后 | 回退到旧直连需代码回滚（C3 Step 5 前必须满足验收标准才可执行） | 是 |

## 五、前置门禁检查清单（开始 C3 前逐项确认）

- [ ] dev 环境全栈可用（DB / RabbitMQ / 后端 6140 等），确认当前队列与任务无积压脏数据
- [ ] 事件对账任务运行中：观察 24h 窗口 **0 条不一致 WARN**（基线干净，否则先修 B3 告警）
- [ ] 至少 1 个 executor Agent 在线（注册一律人工，用户执行）
- [ ] N 值确定（建议 20 个 Run，覆盖 ASSIGNED / REWORK / DONE / FAILED 至少各 1）
- [ ] 造数方式确认（任务创建 → 分配 → 执行用既有链路，DB 写操作仅提供 SQL 由用户执行）
- [ ] 灰度阶梯与观察窗口确定（建议 5% → 25% → 100%，每档 ≥ 1 个完整观察窗口）
- [ ] 验收脚本骨架就绪（§七）
- [ ] 回滚预案演练过一次（§四表格）

## 六、验收标准（exit criteria，Step 2/4 判别依据）

1. 灰度期完成 ≥ N 个 Run，其中经 Runtime 路径的执行占比与 gray-percent 偏差在 ±10% 内（路由生效且无倾斜）；
2. B3 对账：观察窗口内不一致 WARN = 0；
3. 事件完整性：每个经 Runtime 路径的 Run 存在 `TASK_ASSIGNED → AGENT_STARTED → … → AGENT_COMPLETED` 序列（成对，无孤儿）；
4. 业务终态：sub_task 终态（DONE / FAILED / REWORK）与末条事件投影一致（复用 B3 五态映射）；
5. 执行记录：`agent_execution_record` 终态覆盖（markSuccess / markFailed 无遗漏，无 RUNNING 滞留）；
6. 全量 100% 后 ≥ 1 天窗口内：无新增 WARN/ERROR 类型、无执行重试风暴、对账保持 0 不一致。

## 七、验收脚本骨架规格（预研只给规格，不落码；落地时遵循规则 6 UTF-8 模板）

| 脚本 | 检查点 | 依赖 | 退出条件 |
|---|---|---|---|
| `verify-c3-env.ps1` ✅ 已落地（LOG-20260902-011，首轮 12/12 PASS） | 服务端口存活 / 对账任务日志尾行时间 / executor Agent 在线数（口径对齐 AgentSelector：ACTIVE + 内部 LLM 豁免或 ONLINE+心跳新鲜） | dev 全栈 | 全 PASS 才进入灰度 |
| `verify-c3-route.ps1` ✅ 已落地（LOG-20260903-008，3/3 PASS + MCP 探针违例 0 行） | taskId % 100 两侧归属断言：反侧违例（mod100 >= gray 却走 runtime）0 行 + 命中占比 vs gray 偏差 ≤ ±10%（验收标准 1） | 路由层已实现 | 两侧样本均符合 |
| `verify-c3-reconcile.ps1` ✅ 已落地（LOG-20260903-007，6/6 PASS） | 抓 `EventReconciliationTask` WARN 计数（窗口内）= 0；B3 口径复刻 SQL 探针（.tmp/c3-reconcile-probe.sql），只读 SQL 走 MCP；本机有 psql 时自动执行断言 | B3 运行中 | 0 不一致 |
| `verify-c3-events.ps1` ✅ 已落地（LOG-20260903-007，P1 成对/P3 无滞留 PASS） | 事件成对性 / 终态投影 / RUNNING 滞留（三支只读探针 .tmp/c3-events-probe.sql）；无 psql 时走会话内 MCP 执行 | DB | 全部通过 |
| `verify-c3-rollback.ps1` ✅ 已落地（LOG-20260903-008，GUIDE 态实测；正式演练待用户按指引执行） | gray-percent=0 后新任务回旧路径断言（日志窗口 'route=agent_runtime' = 0 + DB 探针 rt_new=0 且 consume_new>0） | 路由层已实现 | 新任务走旧路径 |

统一约束：PowerShell 5.1 UTF-8 头模板；只读查询走 `postgres_helloai` MCP；DB 写操作仅提供 SQL 由用户执行；含中文输出用单引号 + 拼接。

## 八、风险与对策

| 风险 | 现象 | 对策 |
|---|---|---|
| R1 灰度期不一致 | B3 WARN > 0 | 立即 gray-percent=0，定位入口封装差异（校准 3 差异面） |
| R2 Adapter 吞异常 | 契约化 FAILED 掩盖真实错误 | 路由层增加 route 观察点（log + timeline），对账终态校验兜底 |
| R3 双入口并发 | @Async 与 Poller 同时触发 | 路由只换入口不换核心，CAS/幂等语义复用旧链，不新增并发面 |
| R4 下线后回滚难 | 需代码回滚 | Step 5 前置条件 = 验收标准全部满足 + 全量稳定 ≥ 1 天 |
| R5 灰度倾斜 | 路由占比偏差 > ±10% | 校验 taskId 分布（长 id 取模均匀性），必要时换盐（如 subTaskId） |

## 九、待决策点

| 编号 | 决策 | 选项 | 建议 |
|---|---|---|---|
| D1 | 路由层实现位置 | A：改造 `LocalExecutionCommandConsumer`（consume 首步路由）；B：新建路由消费者包裹旧消费者 | A（改动最小、路由点集中单处；下线时同一处收口） |
| D2 | 灰度基数 N | 10 / 20 / 50 | 20（覆盖多状态路径的可接受最小样本） |
| D3 | 灰度阶梯 | 5→25→100；或 5→50→100 | 5→25→100（每档 ≥ 1 个观察窗口） |
| D4 | 全量稳定窗口 | 1 天 / 3 天 | ≥ 1 天（与 B3 对账窗口同量级） |
| D5 | 配置语义 | gray-percent 并入 v2-enabled（单布尔）；或分离双配置 | 分离（总开关 + 百分比，回滚粒度高） |

## 十、结论

C3 不是「开关一开就完事」：前置缺口是**统一执行入口 + 运行期路由层**（Step 0），灰度键按现实收敛为 `taskId % 100`，双轨对账验证的是 Runtime 入口封装无回归（共享 executeOnce 核心）。预研后可直接开工 C3 Step 0（D1 建议项改动面最小），灰度数据准备与 Agent 在线依赖 dev 环境可用。