# HelloAI 迭代执行记录

## 1. 文档定位

本文档用于记录每一轮实际执行了什么，不再把实施日志写回历史路线图正文。

记录目标：

- 让后来者快速知道最近做了哪些事
- 让差距表可以对应到“哪一轮关闭了哪一项”
- 让历史路线图保持“目标态文档”的可读性

---

## 2. 近期关键轮次

### 2026-07 环境与主线收口

#### 1. 范围

- 对齐基础环境与主线工程
- 清理文档职责混写问题

#### 2. 实际落地

- 对齐 JDK 17、本地 DB 初始化与 Spring Boot 3.x 兼容项
- 修复部分后端接口与前端主流程问题
- 建立“项目基线 / 实现差距 / 迭代执行记录”三层文档体系

#### 3. 遗留

- README 与历史文档仍需持续按差距表校正
- 工作流模板、浏览器 Agent、独立消费载体等能力仍未闭环

---

### 2026-07 调度解耦主链收口

#### 1. 范围

- 按 `doc/HelloAI_调度解耦重构分析.md` 推进执行链收口

#### 2. 实际落地

- 将 `ASSIGNED` 后的执行路径收口为“命令创建 -> 本地 consumer -> 结果回写”
- 真实 blocked 样本已验证可从重分配推进到 `REVIEW`
- 补齐并发双击 `/api/sub-tasks/execute/{id}` 去重与超时补偿稳定收敛的运行态证据

#### 3. 遗留

- 消费者仍为本地 Spring 事件，尚未切换为独立 MQ / DB poller
- `SubTaskExecutionService` 仍保留部分执行编排职责
- offline 场景仍需补更强的运行态取证

---

### 2026-07 DB Poller 主线化

#### 1. 范围

- 将执行命令消费载体从“EVENT 主消费 + Poller 兜底”推进到“DB Poller 主消费”（默认）
- 修复 POLLER 模式下 Poller 找不到消费者导致无法启动的 wiring 问题
- 本轮不新增 MQ Consumer，不扩展 RabbitMQ 业务消费链

#### 2. 实际落地

- `AgentExecutionProperties` 支持 `EVENT / POLLER / BOTH` 三种模式，默认 `POLLER`；默认扫描周期调整为 `1000ms`
- `ExecutionCommandService`：
  - `POLLER` 模式只落库 PENDING 命令，不发布本地事务事件
  - `EVENT / BOTH` 模式继续发布事件
- `ExecutionCommandPoller`：
  - `POLLER / BOTH` 模式扫描全部 PENDING 作为主消费
  - `EVENT` 模式仅扫描孤儿 PENDING 作为兜底
  - 改为依赖抽象 `ExecutionCommandConsumer`
- `LocalExecutionCommandConsumer`：
  - 消费 Bean 始终存在（供 Poller 注入）
  - 本地事务事件仅作为 `EVENT/BOTH` 模式的适配入口
- `application.yml` 默认配置改为：`consumer-mode: POLLER`、`poller-interval-ms: 1000`（避免多开关冲突）

#### 3. 验证

- 启动期验证：`consumer-mode=POLLER` 时应用可正常启动，Poller 能正常注入并调用消费者
- 行为验证：`POLLER` 模式下命令创建后不依赖事务事件，PENDING 记录可被 Poller 周期扫描推进

#### 4. 影响

- 对外行为变化：执行命令主消费载体默认切换为 DB Poller
- 配置变化：`helloai.execution.consumer-mode` 默认 `POLLER`；`helloai.execution.poller-interval-ms` 默认 `1000`
- 代码变化：执行命令发布/消费链路按 `consumer-mode` 分流，Poller 逻辑从“孤儿兜底”升级为“主消费”

#### 5. 遗留

- 执行命令尚未新增 MQ Consumer，未形成“执行命令 → MQ → 独立 Consumer”的主链路
- 需要补齐 POLLER 主消费模式下的运行态取证脚本与回归用例（崩溃恢复/重复消费/晚到结果）

---

### 2026-07-11 文档矩阵二次修订

#### 1. 范围

- 修正文档矩阵中的二次失真
- 重新收口历史路线图、设计参考与核心三层文档的职责边界

#### 2. 实际落地

- 识别出 `HelloAI_多类型Agent接入与调度可靠性开发路线图_v3.0.md` 存在多处事实性失真，不适合继续作为路线图或事实参考
- 将 `v3.0` 降级并重写为 `doc/HelloAI_架构设计参考.md`，只保留：
  - 参考来源说明与综合吸收边界（OpenMOSS / AgentTeams-main / Vibe-Skills-main / 优先级机制设计文档 / trade-cloud）
  - 技术栈版本表
  - 核心概念定义（调度分离、双心跳、熔断、Outbox、TCC、工作单元、控制命令）
  - 目标态方向说明
- 将 `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4.md` 归档为：
  - `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4_archived.md`
- 更新《实现差距表》：
  - D3 / D4 / D7 标记为已关闭
  - N1 明确为“Outbox 基础能力已具备，但执行命令尚未接入独立 MQ / DB poller”
  - N6 明确为“消费者仍为本地 Spring 事件，尚未切换到独立 MQ / DB poller”
- 更新《项目基线文档》，明确最终文档矩阵：
  - 核心三层
  - 专项分析
  - 历史资产
  - 设计参考
- 更新 README、README.en 与《当前能力确认矩阵》中的文档引用口径
- 三轮文档矩阵二次分析：确认三个历史文档（v1.1、OpenMOSS 对比、v2.0 开发清单）已有归档标记
- 修复 README.en.md 文档列表不完整（补上《调度解耦重构分析》与《执行链路架构分析》）
- 修正 `McpController.java` Javadoc 中"预计 v3.0 移除"为"预计下个大版本移除"
- 继续扩写 `doc/HelloAI_架构设计参考.md`：将 `OpenMOSS / AgentTeams-main / Vibe-Skills-main / HelloAi Agent 任务调度优先级机制设计文档 / trade-cloud` 的吸收边界、适用落点与开发顺序写清楚
- 更新《项目基线文档》：新增“已确认的参考吸收原则”与“已确认的后续开发方向”，明确哪些来源指导接入层、调度层、运行时层与可靠性层

#### 3. 验证

- 文档链路检查：核心文档已不再相互引用错误的 `v3.0` 路径
- 职责边界检查：设计理念、现实基线、差距判断、执行记录已重新分层
- 引用一致性检查：README / 基线 / 差距表 / 能力矩阵已切到新矩阵口径
- 参考来源边界检查：外部项目已按“接入层 / 调度层 / 运行时层 / 可靠性层”拆分，不再混成单一方案来源

#### 4. 影响

- 对外行为变化：无
- 文档变化：
  - 新增 `doc/HelloAI_架构设计参考.md`
  - 新增 `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4_archived.md`
  - 收口 `doc/HelloAI_项目基线文档.md`
  - 收口 `doc/HelloAI_实现差距表.md`
  - 回写 `doc/HelloAI_迭代执行记录.md`
  - 收口 `README.en.md`（补全文档列表）
  - 收口 `helloai-api/.../McpController.java`（v3.0 措辞修正）
- 数据结构变化：无

#### 5. 遗留

- D1 / D2 / D5 / D6 仍需继续按代码事实逐份清理历史文档
- 若后续新增设计文档，必须先判断是否已经可被"基线 / 差距 / 执行记录 / 专项分析"覆盖，避免再次出现职责重叠

---

### 2026-07-11 文档资产清理与借鉴技术细节沉淀

#### 1. 范围

- 按借鉴项目维度整理外部参考的具体技术细节
- 清理已无留存价值的历史文档

#### 2. 实际落地

- 新增 `doc/HelloAI_外部项目借鉴技术细节.md`：按 AgentTeams-main / Vibe-Skills-main / OpenMOSS / 优先级设计文档 / trade-cloud 五个维度，列出具体文件路径、代码模式与 HelloAI 落点映射，含借鉴优先级速查表
- 删除 4 个历史文档：
  - `HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4_archived.md`（仅剩归档声明，无实质内容）
  - `HelloAI_vs_OpenMOSS_功能对比与实现方案.md`（历史对标分析，其洞察已吸收到架构设计参考和借鉴技术细节中）
  - `HelloAI_技术方案与补齐清单_v1.1.md`（1714 行历史方案，与当前代码现实严重脱节）
  - `HelloAI_Agent接入内容生成功能开发清单_v2.0.md`（功能已基本落地，开发清单已完成使命）
- 更新《项目基线文档》§6 文档矩阵：移除"历史资产"分类，新增"设计参考""能力确认""工程规范""其他参考"分层
- 更新 README.md / README.en.md 文档列表与导航链接
- 更新 AGENTS.md 必读文档列表（移除 v2.0，替换为架构设计参考）

#### 3. 影响

- 对外行为变化：无
- 文档变化：
  - 新增 1 份
  - 删除 4 份
  - 修改 5 份（基线文档 / 迭代记录 / README / README.en / AGENTS.md）
- 数据结构变化：无

#### 4. 遗留

- `doc/HelloAI_当前能力确认矩阵.md` 与《实现差距表》存在部分内容重叠，后续可考虑合并或明确差异边界
- README 项目结构图中不再列举已删除的历史文档

---

### 2026-07-13 多 Agent Skills / Rules 口径同步

#### 1. 范围

- 将多家 Agent 使用的本地 preflight skill / rule 统一到新的文档矩阵口径

#### 2. 实际落地

- 更新 `.agents/skills/helloai-preflight/SKILL.md`：
  - 必读文档从 5 份调整为 6 份
  - 移除已删除的 `HelloAI_Agent接入内容生成功能开发清单_v2.0.md`
  - 新增 `doc/HelloAI_调度解耦重构分析.md` 与 `doc/HelloAI_架构设计参考.md`
  - 补充“调度、执行链、异步回写、MQ 解耦优先遵循调度解耦分析”的规则
- 将上述 preflight skill 同步镜像到：
  - `.trae/skills/helloai-preflight/SKILL.md`
  - `.qoder/skills/helloai-preflight/SKILL.md`
  - `.cursor/skills/helloai-preflight/SKILL.md`
  - `.claude/skills/helloai-preflight/SKILL.md`
- 同步更新 `.trae/rules/执行规则.md`，确保 Trae 规则文件与 skill 口径一致
- 确认 `.codex` 当前只有 `hooks.json`，没有独立本地 skills 目录，因此本轮不新增重复 skill 配置

#### 3. 验证

- 全目录检索确认多家 preflight skill 已不再引用 `v2.0` 开发清单
- 全目录检索确认多家 preflight skill 已统一引用《调度解耦重构分析》与《架构设计参考》
- 确认 Trae rule 与共享 preflight skill 文本一致

#### 4. 影响

- 对外行为变化：无
- 配置变化：
  - 修改 6 份 preflight skill / rule 文件
- 数据结构变化：无

#### 5. 遗留

- 若后续新增面向 Codex 的本地 skills 目录，应继续沿用 `.agents/skills/helloai-preflight` 作为母版


### 2026-07-13 P0 文档失真关闭——D1/D2/D5/D6

#### 1. 范围

- 关闭实现差距表中全部四项文档失真（D1/D2/D5/D6）
- 同步收口 N3（MCP Server 工具集口径）

#### 2. 实际落地

- **D1（MCP 工具数量口径）**：确认 README 已明确 "工具数量不写死，以 	ools/list 实际输出为准"，关闭
- **D2（兼容通道定位）**：确认 README 已明确 "MCP SSE 是唯一主通道，REST 	ools/list / 	ools/call 属兼容保留"，关闭
- **D5（/api/tools/cli 鉴权口径）**：代码验证——WebMvcConfig 中 /api/tools/cli 已通过 excludePathPatterns 排除鉴权（作为 CLI 工具的公开下载入口，设计如此），关闭
- **D6（心跳刷新规则口径）**：确认 README 已明确 "last_seen_at/在线态刷新以 heartbeat 为主"，关闭
- 同步将 D1-D7 的状态从 "未关闭/已关闭" 统一为 "✅ 已关闭"
- 更新 N3 状态：从 "已交付但口径未完全收口" 收口为 "已交付"
- 更新 Section 5 优先级：将条目 1 标记为已完成

#### 3. 影响

- 对外行为变化：无
- 文档变化：doc/HelloAI_实现差距表.md（6 行状态修改 + 1 行 N3 修改 + Section 5 更新）
- 数据结构变化：无

#### 4. 遗留

- N1/N6/N9/N10 仍待推进（属于后续工作）
- 接近零遗留——本轮是所有文档失真项的最终关闭轮
---

### 2026-07-15 AgentHub 方案文档收口

#### 1. 范围

- 将外部 Agent 接入层增强思路从历史草案中收编为新的专项方案文档
- 统一 V1 / V2 / V3 三阶段版本口径，作为后续扩展参考

#### 2. 实际落地

- 新增 `doc/HelloAI_agenthub.md`，作为 AgentHub 方向的主方案文档，明确：
  - 本文档用于描述外部 Agent 接入层增强方案，而非当前实现事实
  - 方案分为三阶段：
    - V1 最小版：`agent_duty_lease` + `checkIn/checkOut` + 值班优先分配 + 看板展示
    - V2 增强版：Bridge 守护进程桥接当前 `/mcp/sse` 主通道
    - V3 产品版：门铃通知通道 + 一键安装，通知层只负责唤醒
  - 当前主线约束：
    - 不引入第二控制面
    - 不改变 `MCP-over-SSE` 为主协议的定位
    - 不新增与 `online_status` 平行竞争的 Agent 主状态枚举
- 将 `doc/helloai_agenthub_complete.md` 降级为历史草案，并补充顶部归档说明，明确：
  - 旧文档保留原始设想与灵感
  - 其中关于 `AgentStatus` 扩展、WebSocket 主通道、ShiftManager 的方案不再直接作为开发主参考
  - 后续统一以 `doc/HelloAI_agenthub.md` 为主
- 在新文档中补充“旧文档能力映射表”，把旧草案中的核心想法收口为：
  - 值班租约模型
  - `checkIn/checkOut`
  - `submitResult` 语义扩展
  - Bridge
  - 门铃通知通道
  - 看板增强

#### 3. 影响

- 对外行为变化：无
- 文档变化：
  - 新增 `doc/HelloAI_agenthub.md`
  - 修改 `doc/helloai_agenthub_complete.md`
  - 回写 `doc/HelloAI_迭代执行记录.md`
- 数据结构变化：无

#### 4. 遗留

- `agent_duty_lease`、`checkIn/checkOut`、Bridge、门铃通知目前仍处于方案阶段，尚未进入代码实现
- 后续若基于该方案开始开发，应先按 preflight 守则对照基线 / 差距 / 调度解耦分析，再按 V1 → V2 → V3 顺序推进，避免跳阶段

### 2026-07-13 P1 代码修复——双回写风险 + LLM 调用可观测性

#### 1. 范围

- 修复 LocalExecutionCommandConsumer.consume() catch 块中的双重回写风险
- 为 SubTaskExecutionService.executeOnce() 增加 LLM 调用前后的可观测 timeline 事件

#### 2. 实际落地

- **修复 1：移除 Consumer 中的 	hrow e**
  - 原逻辑：catch 中 markFailed() + 	hrow e，导致 executeOnce() 内部的 handleFailure() 和 consumer 的 markFailed() 形成双重回写竞态
  - 新逻辑：catch 中仅 markFailed()，不再 rethrow，注释说明子任务降级已由内部 handleFailure 完成
  - 影响文件：helloai-core/.../LocalExecutionCommandConsumer.java（1 行改动）

- **修复 2：增加 LLM 调用前后可观测事件**
  - 在 platformAgentExecutionService.executeSync() 前后分别记录 sub_task_llm_call_start 和 sub_task_llm_call_end 到 task_timeline
  - 在异常路径中记录 sub_task_llm_call_failed
  - 这三个新事件使外部可以区分"卡在执行编排层"还是"卡在 LLM HTTP 调用中"
  - 影响文件：helloai-core/.../SubTaskExecutionService.java（+9 行）

#### 3. 影响

- 对外行为变化：无（LLM 调用事件仅为观测增强，不影响业务路径）
- 代码变化：LocalExecutionCommandConsumer.java（语义变更：不再 rethrow）、SubTaskExecutionService.java（新增 timeline 事件）
- 数据结构变化：	ask_timeline 表新增三种事件类型（sub_task_llm_call_start / sub_task_llm_call_end / sub_task_llm_call_failed）

#### 4. 遗留

- 并发场景回归测试已在 P2 轮次完成
- SubTaskExecutionService.executeOnce() 的编排-执行-回写混合结构未在本轮解决（属于 Phase 2 WorkUnit 显式建模的范畴）

### 2026-07-13 P2 并发缺陷修复与测试

#### 1. 范围

- 修复 P2-2 揭示的真实并发缺陷：补偿任务将 subTask 推进到 BLOCKED 后，consumer 的 handleSuccess 缺少状态前置检查
- 补充 P2-1/P2-2/P2-3 三个 Mockito 单元测试

#### 2. 实际落地

- **P2-2 缺陷修复**：在 ExecutionResultHandler.handleSuccess() 中增加状态前置检查
  - 如果 subTask.status != IN_PROGRESS，不推进到 REVIEW，不覆写 context
  - 记录 sub_task_execute_result_discarded 事件到 timeline，包含当前状态和 LLM 结果信息
  - 添加 @Slf4j 注解

- **P2-2 测试**：shouldNotReviveSubTaskWhenStatusIsBlocked
  - 验证 BLOCKED 状态下 handleSuccess 不调用 submit、不覆写 context、记录 discarded 事件

- **P2-3 测试**：shouldNotBlockWhenStatusIsNotInProgress
  - 验证 handleFailure 对已是 BLOCKED 的子任务不重复调用 block

- **P2-1 测试**：shouldUseBothRowLockAndHasPendingOrRunningForDuplicatePrevention
  - 验证 getByIdForUpdate（行锁）和 hasPendingOrRunning（应用层检查）被按序调用

- **P1 测试同步更新**：LocalExecutionCommandConsumerTest.shouldMarkFailedWhenConsumeThrowsException
  - 移除 try/catch 包装，因为 P1 修复后 consume 不再 rethrow

#### 3. 影响

- 代码变化：
  - ExecutionResultHandler.java（+状态前置检查 +@Slf4j）
  - ExecutionResultHandlerTest.java（+2 个测试）
  - ExecutionCommandServiceTest.java（+1 个测试）
  - LocalExecutionCommandConsumerTest.java（移除 try/catch）
- 数据结构变化：	ask_timeline 新增 sub_task_execute_result_discarded 事件类型
- 对外行为变化：被补偿任务正确 BLOCKED 的子任务不再被 consumer 的迟到结果"干扰"

#### 4. 遗留

- 本轮为单元测试（Mockito），未覆盖集成测试（需要真实 DB + 并发线程）
- SubTaskExecutionService.executeOnce() 的编排-执行-回写混合结构未在本轮拆分

---

### 2026-07-13 P2 测试验证——4 类 7 个单元测试全部通过

#### 1. 范围

- 在 IDEA 中手动执行本轮 P1/P2 涉及的全部单元测试，验证无编译错误、无逻辑缺陷
- 修复构建过程中暴露的 BOM 字符、缺失 import、mock 返回值不完整等问题
- 为防重拦截路径补充可观测日志

#### 2. 实际落地

- **ExecutionResultHandlerTest（4 个测试 ✅）**
  - `shouldHandleSuccess`：IN_PROGRESS 状态正常推进 REVIEW
  - `shouldHandleFailure`：IN_PROGRESS 状态正常推进 BLOCKED
  - `shouldNotReviveSubTaskWhenStatusIsBlocked`（P2-2）：BLOCKED 状态下不调用 submit、不覆写 context、记录 discarded 事件——日志输出 `跳过 handleSuccess：子任务状态已非 IN_PROGRESS`
  - `shouldNotBlockWhenStatusIsNotInProgress`（P2-3）：BLOCKED 状态下不重复调用 block，仍记录失败 timeline

- **ExecutionCommandServiceTest（3 个测试 ✅）**
  - `shouldCreateExecutionCommandAndPublishEvent`：正常创建命令并发布事件——日志输出 `执行命令已创建`
  - `shouldRejectWhenPendingOrRunningRecordExists`：已有进行中记录时抛 BizException——日志输出 `跳过创建执行命令：子任务已有进行中的执行记录`
  - `shouldUseBothRowLockAndHasPendingOrRunningForDuplicatePrevention`（P2-1）：验证 getByIdForUpdate（行锁）先于 hasPendingOrRunning（应用层检查）调用

- **LocalExecutionCommandConsumerTest（3 个测试 ✅）**
  - `shouldConsumeWhenCommandCreatedEventArrives`：正常消费并 markSuccess
  - `shouldMarkFailedWhenConsumeThrowsException`：异常路径 markFailed（P1 后 consume 不再 rethrow，异常由内部 log.error 记录）
  - `shouldSkipExecutionWhenMarkRunningReturnsFalse`：markRunning CAS 失败时提前 return——日志输出 `跳过执行(记录已非 PENDING)`

- **ExecutionCompensationTaskTest（3 个测试 ✅）**
  - `shouldMarkPendingTimeoutWithoutBlockingWhenSubTaskNotInProgress`：PENDING 超时 + subTask=ASSIGNED → 仅标记 TIMEOUT，不调用 handleFailure
  - `shouldHandleFailureWhenRunningRecordTimesOut`：RUNNING 超时 + subTask=IN_PROGRESS → markTimeout + handleFailure 推进 BLOCKED
  - `shouldIgnoreWhenNoTimedOutRecords`：无超时记录时不触发任何补偿动作

- **构建问题修复**
  - 4 个 Java 文件带 UTF-8 BOM（`﻿`）：移除前 3 字节
  - `ExecutionResultHandlerTest` 缺失 `import static org.mockito.Mockito.never`：补上
  - `LocalExecutionCommandConsumerTest` 中 `markSuccess`/`markFailed` mock 未设返回值导致输出"被拒绝" warn：补上 `thenReturn(true)`
  - `ExecutionCommandService` 防重拦截路径缺日志：新增 `log.warn`

#### 3. 影响

- 代码变化：
  - `ExecutionCommandService.java`（+1 行 log.warn）
  - `ExecutionResultHandlerTest.java`（+1 行 import）
  - `LocalExecutionCommandConsumerTest.java`（+2 行 mock 返回值）
  - 4 个文件去除 BOM（内容无变化）
- 测试结果：4 类共 13 个单元测试全部通过，exit code 0

#### 4. 遗留

- 未覆盖集成测试（需要真实 DB + 并发线程模拟补偿 vs consumer 真实竞态）
- `ExecutionCompensationTaskTest` 已验证补偿任务的 CAS + 状态守卫逻辑正确，与 P2 `handleSuccess` 守卫形成"补偿先到 BLOCKED / consumer 后到不复活"的双向保护闭环

#### 5. 可复现验证

执行以下命令可复现本轮全部测试：

```bash
# helloai-core 模块（P1/P2 涉及的 3 个测试类，共 10 个用例）
mvn test -pl helloai-core -Dtest="ExecutionResultHandlerTest,ExecutionCommandServiceTest,LocalExecutionCommandConsumerTest"

# helloai-job 模块（补偿任务，3 个用例）
mvn test -pl helloai-job -Dtest="ExecutionCompensationTaskTest"
```

或指定完整类名：

| 测试类 | 用例数 | 验证重点 |
|--------|--------|----------|
| `com.helloai.core.service.ExecutionResultHandlerTest` | 4 | handleSuccess 守卫（P2-2）、handleFailure 不重复 block（P2-3） |
| `com.helloai.core.service.ExecutionCommandServiceTest` | 3 | 命令创建 + 行锁+应用层双重防重（P2-1） |
| `com.helloai.core.service.LocalExecutionCommandConsumerTest` | 3 | consume 不再 rethrow（P1）、markRunning CAS 跳过 |
| `com.helloai.job.task.ExecutionCompensationTaskTest` | 3 | 补偿 markTimeout CAS + handleFailure 状态守卫 |

---

### 2026-07-13 Phase 2A N9 Provider 配置复用

#### 1. 范围

- 收口多 Provider 统一配置入口（`helloai.providers.<name>.*`），解决配置散落、路径杂糅、factory 每次 new ChatModel 三个问题
- 统一 provider/model 解析逻辑到 `AgentProviderResolver`，消除 `ApiKeyAgentExecutor` 和 `AgentChatClientService` 中的重复解析

#### 2. 实际落地

- **新增 `AgentProviderProperties`（helloai-common）**
  - `@ConfigurationProperties(prefix = "helloai.providers")`，统一管理 baseUrl / defaultModel / 超时
  - `getConfig(provider)` 大小写不敏感查找
  - 通过 `@EnableConfigurationProperties` 激活（非 `@Component` 扫描）

- **新增 `AgentProviderResolver`（helloai-core）**
  - 静态工具类，从 `Agent.modelType`（格式 `provider:model`）解析 provider 和 model
  - `resolveProvider(agent, fallback)` / `resolveModel(agent, fallback)`

- **配置更新（application.yml）**
  - 新增 `helloai.providers.deepseek.*` 段，替代散落的 `spring.ai.deepseek.*`
  - 支持环境变量 fallback（`DEEPSEEK_BASE_URL` / `DEEPSEEK_CHAT_MODEL` / `DEEPSEEK_CONNECT_TIMEOUT_MS` / `DEEPSEEK_READ_TIMEOUT_MS`）

- **重构 `DeepSeekProviderChatClientFactory`（helloai-start）**
  - 移除所有 `@Value` 注解
  - 注入 `AgentProviderProperties`，从统一配置读取参数
  - model 优先级：参数传入 > properties.defaultModel > 常量默认值

- **重构 `AgentChatClientService.generate()`**
  - factory 分支：通过 `AgentProviderResolver.resolveModel()` 解析 model，选 factory → 调 `createChatClient`
  - 保留 mock 模式和 ChatClient.Builder fallback 路径

- **重构 `ApiKeyAgentExecutor.execute()`**
  - 删除 `resolveProvider()` 本地方法
  - provider 解析统一委托 `AgentProviderResolver.resolveProvider()`

#### 3. 验证

- 旧 `@Value` 注解全量移除：`grep @Value.*deepseek` 零命中
- 旧 `spring.ai.deepseek` 引用全量移除：Java 代码零命中
- `AgentProviderResolverTest` 12 个用例全部通过（resolveProvider 5 + resolveModel 7），覆盖 null/blank/无冒号/冒号无模型等边界
- `mvn test -pl helloai-core -Dtest="AgentProviderResolverTest"` → BUILD SUCCESS

#### 4. 影响

- 对外行为变化：无（配置路径从 `spring.ai.deepseek.*` 迁移到 `helloai.providers.deepseek.*`，语义等价）
- 代码变化：
  - 新增 `AgentProviderProperties.java`（helloai-common）
  - 新增 `AgentProviderResolver.java`（helloai-core）
  - 重构 `DeepSeekProviderChatClientFactory.java`（移除 @Value，注入 properties）
  - 重构 `AgentChatClientService.java`（factory 分支使用 resolver）
  - 重构 `ApiKeyAgentExecutor.java`（删除 resolveProvider）
  - 修改 `HelloAIApplication.java`（+@EnableConfigurationProperties）
  - 修改 `application.yml`（+helloai.providers 段）
- 新增测试：`AgentProviderResolverTest.java`（12 用例）
- 数据结构变化：无

#### 5. 遗留

- N9 标记为"部分落地"——Provider 配置入口已统一，但 ChatModel 缓存优化（避免每次 new）未在本轮实施
- N10（credential_vault 轮换/迁移/权限颗粒度）仍为独立后续工作
- 后续新增 Provider（如 OpenAI）只需：① YAML 加一段配置 ② 新增一个 `ProviderChatClientFactory` 实现

---

### 2026-07-13 Phase 2A N6 executeOnce 削薄

#### 1. 范围

- 按架构设计参考 §5.1「继续削薄 `SubTaskExecutionService` 的编排职责」推进 executeOnce 拆解
- 将「状态推进 + 纯执行 + 结果回写」三层混合职责拆开，让消费者拿到完整分层调用能力

#### 2. 实际落地

- **`SubTaskExecutionService.executeOnce(subTask, agent)` 削薄为纯执行**
  - 原职责（混合）：状态守卫 + startIfNeeded 状态推进 + timeline sub_task_execute_start + 组装 AgentTask + timeline llm_call_start/end + 调 platform.executeSync() + handleSuccess/handleFailure 结果回写
  - 新职责（纯执行）：状态守卫（DONE/CANCELLED 拒入） + 组装 AgentTask + timeline sub_task_llm_call_start/end + 调 platformAgentExecutionService.executeSync() + 返回 AgentResult / 抛异常
  - 不再做 startIfNeeded、不再做 handleSuccess/Failure
  - private → public，供分层消费者调用

- **`SubTaskExecutionService.startIfNeeded(subTaskId, status)` 保持 public**
  - 状态推进前置，让消费者可以在调 executeOnce 之前先确保 subTask 状态正确

- **`SubTaskExecutionService.executeCommand(command)` 保持完整编排入口**
  - 内部按 startIfNeeded → executeOnce → handleSuccess/handleFailure 串成完整链
  - 向后兼容：现有 executeCommand 调用方（外部 API 层）继续可用

- **`LocalExecutionCommandConsumer.consume(command)` 重写为 6 步分层**
  - ① 加载 subTask + agent + 一致性校验
  - ② startIfNeeded 推进 subTask 到 IN_PROGRESS
  - ③ markRunning CAS
  - ④ timeline sub_task_execution_command_consume + sub_task_execute_start
  - ⑤ executeOnce 纯执行
  - ⑥ handleSuccess / handleFailure + markSuccess / markFailed CAS
  - 失败路径：executeOnce 抛异常 → 记录 sub_task_llm_call_failed timeline → handleFailure → markFailed

#### 3. 影响

- 对外行为变化：无（消费者外部行为不变；执行链路完全等价）
- 代码变化：
  - `SubTaskExecutionService.java`：executeOnce 由 private → public + 削薄；executeCommand 补全完整链；类注释更新
  - `LocalExecutionCommandConsumer.java`：consume 重写为 6 步分层；新增 AgentService 注入；新增 ExecutionResultHandler 注入
  - `SubTaskExecutionServiceTest.java`：拆分 ExecuteOnce / ExecuteCommand / StartIfNeeded 三个 @Nested，共 11 个测试
  - `LocalExecutionCommandConsumerTest.java`：拆分 HappyPath / SkipPath 两个 @Nested，共 7 个测试
- 数据结构变化：无
- 新 timeline 事件：`sub_task_execution_command_consume_skipped`（仅当 startIfNeeded 拒绝时记录）

#### 4. 验证

- `mvn -pl helloai-core -Dtest="SubTaskExecutionServiceTest,LocalExecutionCommandConsumerTest,ExecutionResultHandlerTest,ExecutionCommandServiceTest,AgentProviderResolverTest" test` → 37 个测试全部通过
- `mvn -pl helloai-job -Dtest="ExecutionCompensationTaskTest" test` → 3 个测试全部通过
- `mvn -DskipTests clean install` → 6 个模块全部 BUILD SUCCESS

---

### 2026-07-13 Phase 2A N6 DB Poller 落地 — §5.1 主链已跑通，E2E 已验证，当前处于可靠性收尾窗口

#### 1. 范围

- 按架构设计参考 §5.1「将本地 Spring 事件消费者继续收口到独立 MQ / DB poller 消费模型」落地 DB Poller 独立消费载体
- 关闭实现差距表 N6 「消费者仍为本地 Spring 事件」遗留点
- 补齐 agent_execution_record 兑底扫描所需的存储字段 + 扫描索引

#### 2. 实际落地

- **Flyway V16：`V16__agent_execution_record_poller_fields.sql`**
  - 扩展 `agent_execution_record` 表：新增 `trigger` / `agent_id` / `access_type` / `last_attempt_at` 4 个字段
  - `agent_id` / `access_type` 为兑底扫描时的「命令恢复」元数据
  - `last_attempt_at` 为 DB Poller 兑底扫描的状态机字段（NULL 表示尚未被 Poller 触及过）
  - 新增部分索引 `idx_exec_record_pending_attempt ON agent_execution_record(last_attempt_at, create_time) WHERE status='PENDING'`
  - 启动日志输出 `[V16] agent_execution_record poller 字段补全完成，已存在相关列数 = N`

- **`AgentExecutionRecord` 实体扩展**：补齐 4 个字段 + Javadoc 说明冗余存储语义

- **`AgentExecutionRecordService` 签名变更 + 新增**
  - `createPending(eventId, subTaskId, agentId, accessType, trigger)`：冗余存储 trigger / agentId / accessType
  - `listOrphanPending(thresholdSeconds, limit)`：扫描 `status='PENDING' AND (last_attempt_at IS NULL OR last_attempt_at < now - threshold)` 行，按 `create_time` 升序返回 `LIMIT`
  - `markPolled(id)`：记录 Poller 触及痕迹，下个周期不会重复扫到
  - 为空阈值 / limit 增加防御性短路返回 `List.of()`

- **`ExecutionCommandService.createAssignedCommand`**：调用新签名的 createPending，写入完整字段

- **`ExecutionCommandPoller`（新建）**
  - `@ConditionalOnProperty(name = "helloai.execution.poller-enabled", ...)` 开启可控
  - `@Scheduled(fixedDelayString = "${helloai.execution.poller-interval-ms:30000}")` 周期扫描
  - poll() 入口先看 `executionProperties.isPollerEnabled()`（运行时动态开关），false 直接 return
  - 对每条孤儿记录依次：markPolled → 完整性校验（缺 subTaskId/agentId/accessType 跳过） → 记录 timeline `sub_task_execution_command_poll_recovery` → 构造 `ExecutionCommand`（trigger 前缀 `poll-recovery:`）→ 调用 `localExecutionCommandConsumer.consume()`
  - 单条异常不影响整批扫描，listOrphanPending 异常向上抛出让调度框架处理

- **`AgentExecutionProperties`（helloai-common）补全 4 个 poller 字段**：`pollerEnabled` / `pollerIntervalMs` / `pollerOrphanThresholdSeconds` / `pollerBatchSize`，默认值与架构参考对齐

- **`application.yml`**：`helloai.execution.poller-*` 四项配置带上注释

#### 3. 双路径主链

- **实时路径**：`SubTaskAutoExecutionDispatcher → ExecutionCommandService → publishEvent(ExecutionCommandCreatedEvent) → @Async @TransactionalEventListener → LocalExecutionCommandConsumer.consume()`（保留，实时性优先）
- **兑底路径**：`ExecutionCommandPoller.@Scheduled → agentExecutionRecordService.listOrphanPending() → 重建 ExecutionCommand → LocalExecutionCommandConsumer.consume()`（新，独立可工作）
- **幂等保护**：两条路径都会调用 `markRunning` CAS，被另一条路先推进状态后，后到路径被 CAS 拒绝，自然跳过
- **兑底场景**：应用重启 / @Async 线程池积压 / 主路径异常丢失时，Poller 接管，避免 PENDING 长期孤儿化

#### 4. 影响

- 对外行为变化：无（新增兑底路径不改变主路径语义；事件丢失场景反而能被恢复）
- 代码变化：
  - 新增 `ExecutionCommandPoller.java`（156 行）
  - 新增 `ExecutionCommandPollerTest.java`（11 个测试用例：3 HappyPath + 8 SkipPath）
  - 变更 `AgentExecutionRecordService.java`：createPending 签名扩展 + 新增 listOrphanPending / markPolled
  - 变更 `AgentExecutionRecord.java`：实体加 4 个字段
  - 变更 `ExecutionCommandService.java`：调用新签名的 createPending
  - 变更 `ExecutionCommandServiceTest.java`：适配新签名（+import eq）
  - 变更 `AgentExecutionProperties.java`：加 4 个 poller 字段
- 配置变化：`application.yml` `helloai.execution.poller-*` 4 项
- 数据结构变化：
  - `agent_execution_record` 表加 4 列 + 1 个部分索引（Flyway V16）
  - `task_timeline` 表新增事件类型 `sub_task_execution_command_poll_recovery`

#### 5. 验证

- `mvn clean install` → 7 个模块 BUILD SUCCESS
- `mvn test -pl helloai-core` → 72 个测试全部通过（包含 ExecutionCommandPollerTest 11 用例）
- `mvn test -pl helloai-common,helloai-core,helloai-mq,helloai-job,helloai-api,helloai-start` → 全量 BUILD SUCCESS
- `grep createPending` 全仓检索 → 唯一调用点 ExecutionCommandService 已适配

#### 6. 遗留

- §5.1 的执行主链基础能力已落地，但可靠性收尾尚未结束：
  - ✅ DB Poller 消费载体（本轮）
  - ✅ SubTaskExecutionService 编排职责削薄（上一轮）
  - ✅ ExecutionResultHandler 唯一执行结果入口（早前轮）
  - ✅ ExecutionCommand 幂等 / 补偿 / 防覆盖（早前轮）
- MQ 主链虽已完成 Phase 2G E2E 冒烟验证，但 Outbox Confirm / Retry、失败可恢复验证与 Poller 兜底职责重定位仍未完成。
- 后续按依赖顺序推进：Phase 2H ②a Outbox 最小闭环 → ②b Publisher Confirm / Retry → RabbitMQ 失败可恢复 E2E → Poller 降级；§5.2 阶段二后置。
- 当前 Poller 保留为现行消费载体，待可靠投递闭环稳定后再降级为孤儿 / 超时 / 补偿兜底。

---

### 2026-07-13 §5.2 启动前结构清理 — ExecutionCommand*Consumer 迁入 agent.mqconsumer

#### 1. 范围

- §5.1 主链基础能力落地后、在 §5.2 阶段二启动前，先把"消费者"代码从 service/ 根目录剥离，对齐 CODE_STYLE §15.1「helloai-core/agent/mqconsumer/」子包规范
- 纯结构调整：5 个文件物理位置变更 + import 改写，**业务逻辑零变化**
- 用户决策点：先按"修法 1"最小代价路线执行（不迁 ExecutionCommandPoller，也不动 service/ 子域拆分）

#### 2. 实际落地

- **新建 `core/agent/mqconsumer/` 与 `core/test/.../mqconsumer/` 两个目录**
  - 补齐 §15.1 缺失的子包，与现有 `agent/domain`、`agent/executor`、`agent/chat` 平级
- **迁入 3 个文件（main + test）**
  - `ExecutionCommandConsumer.java`（接口，18 行）— package 从 `core.service` → `core.agent.mqconsumer`
  - `LocalExecutionCommandConsumer.java`（实现，179 行）— package 同步迁移，并补 6 行 import 解决跨包调用 6 个 Service（AgentExecutionRecordService / AgentService / ExecutionResultHandler / SubTaskExecutionService / SubTaskService / TaskTimelineService）
  - `LocalExecutionCommandConsumerTest.java`（244 行）— 跟随生产同包迁移，并补 6 行 import 解决 @Mock 跨包
- **补 2 个 import（留在 service/ 的 Poller + PollerTest）**
  - `ExecutionCommandPoller.java`：原同包依赖变跨包，补 `import com.helloai.core.agent.mqconsumer.LocalExecutionCommandConsumer;`
  - `ExecutionCommandPollerTest.java`：同上
- **未迁移的 4 个文件保持原位**
  - `ExecutionCommandService.java` + Test：发布事件，不直接调用消费者
  - `ExecutionCommandPoller.java` + Test：兜底调度任务，按 §14 规范属调度域而非消费者域

#### 3. 影响

- 对外行为变化：无（package 路径变更，类名 / 方法名 / Spring Bean 名全部不变；@Component 自动扫描仍生效）
- 代码变化：
  - 新建 2 个目录（main/test）
  - 迁移 3 个文件位置
  - 4 个文件加 import（Poller ×1 + PollerTest ×1 + LocalConsumer ×6 + LocalConsumerTest ×6 = 共 14 行 import）
  - 3 个文件改 package 声明
- 数据结构变化：无
- 测试覆盖：本地事件消费者与 Poller 的 18 个测试全部保持原位运行不需调整

#### 4. 验证

- `mvn clean install` → 7 个模块 BUILD SUCCESS
- `mvn test -pl helloai-core` → 72 个测试全部通过（含 `LocalExecutionCommandConsumerTest` 7 用例 + `ExecutionCommandPollerTest` 11 用例）
- `mvn test -pl helloai-job` → 3 个测试全部通过
- `grep "package com.helloai.core.service"` 命中：剩余文件均为真实 Service / Poller / Scheduler，不再包含 ExecutionCommand*Consumer
- `grep "import com.helloai.core.agent.mqconsumer"` 命中 2 处（Poller + PollerTest），证明跨包引用正确

#### 5. 遗留

- N6 状态不变（双路径主链已闭环，本轮仅是代码结构调整，不修改文档失真项 / 差距项状态）
- `ExecutionCommandPoller` 仍在 `core/service/`，未迁出；后续若推进 service/ 子域拆分，可考虑把 Poller 移到 `core/job/`（但独立子模块会因依赖方向产生循环，仅供未来架构设计参考）
- `core/service/` 下仍混有策略类（AgentSelector / ResilientDispatcher / SubTaskAutoExecutionDispatcher）以及评分计算器 ImplicitScoreCalculator；后续可按业务子域重新拆分
- 下一步目标：架构设计参考 §5.2 阶段二（工作单元显式建模 + 控制命令层 STOP / PAUSE / REPLAN + 用户输入可重入）

---

### 2026-07-13 §5.2 启动前结构清理 — service/ 根目录杂类分层

#### 1. 范围

- 承接上一轮消费者迁移，继续清理 `helloai-core/core/service/` 根目录中不属于业务 Service 的 Agent 执行链与可观测性组件
- 按用户确认的 A + B 范围执行：Agent 全家桶与 observability 横切组件；`service/score/ImplicitScoreCalculator` 不在本轮范围内
- 纯结构重构：只迁移物理位置、修改 package 并补齐跨包 import，业务逻辑、Bean 行为、数据结构与对外接口均不变

#### 2. 实际落地

- **Agent 执行链组件归入 `core/agent/` 分层**
  - `agent/executor/`：`AgentSelector`
  - `agent/chat/`：`AgentChatClientService`
  - `agent/command/`：`ExecutionCommandService`、`ExecutionResultHandler`
  - `agent/execution/`：`SubTaskExecutionService`、`PlatformAgentExecutionService`
  - `agent/dispatcher/`：`SubTaskAutoExecutionDispatcher`、`ExecutionCommandPoller`、`ResilientDispatcher`
- **横切可观测性组件归入 `core/observability/`**
  - `CircuitBreakerAlertService`
  - `CircuitBreakerEventRecorder`
  - `HeartbeatService`
- **测试与引用同步调整**
  - 9 个对应测试类跟随生产代码迁入新的 Agent 子包
  - 同步更新迁出类自身、反向引用方及测试类的跨包 import
  - `core/service/` 根目录现仅保留 25 个业务 Service；评分计算器 `ImplicitScoreCalculator` 继续保留在 `service/score/` 子目录（已在下一轮迁出，详见后文）

#### 3. 影响

- 对外行为变化：无
- 代码变化：迁移 12 个生产文件与 9 个测试文件，新增 `agent/dispatcher`、`agent/command`、`agent/execution`、`core/observability` 等职责明确的目录
- 数据结构变化：无
- 差距项变化：无；N6 仍为“部分落地”，本轮不改变执行命令双路径主链及后续 §5.2 控制命令层目标

#### 4. 验证

- `mvn clean install` → 7 个模块 BUILD SUCCESS
- `helloai-core` → 72 个测试全部通过
- `helloai-job` → 3 个测试全部通过
- 目录复核：9 个 Agent 执行链生产类与 3 个 observability 生产类均位于目标子包，`service/` 根目录不再混放上述 Selector、Dispatcher、Poller、Command、Execution、Chat 与可观测性组件

#### 5. 遗留

- 评分计算器 `ImplicitScoreCalculator` 下轮单独迁出到 `core/score/`，与 `core/observability/` 对齐形成“顶层领域子包”粒度
- 下一步仍按架构设计参考 §5.2 推进工作单元显式建模、控制命令层与用户输入可重入

---

### 2026-07-13 §5.2 启动前结构清理 — ImplicitScoreCalculator 迁入 core/score/

#### 1. 范围

- 承接上一轮 `service/ 根目录杂类分层` 的遗留，单独处理评分计算器
- 不动业务逻辑、不改 Bean 行为、不改对外接口：仅迁移物理位置、修改 package 并补齐跨包 import
- 目标：让 `core/service/` 只剩真业务 Service；评分域做成与 `core/observability/` 平级的顶层领域子包

#### 2. 实际落地

- **迁移生产文件**：`ImplicitScoreCalculator` 从 `core/service/score/` → `core/score/`，package 从 `com.helloai.core.service.score` → `com.helloai.core.score`
- **删除空目录**：旧 `core/service/score/` 整个删除
- **反向 import 更新**：`SubTaskService` 中 2 行 `com.helloai.core.service.score.*` → `com.helloai.core.score.*`（含 `ImplicitScoreCalculator` 与 `ImplicitScoreCalculator.ScoreResult` 内部类）
- **未带测试文件**：`helloai-core/src/test` 下没有 `ImplicitScoreCalculator` 配套测试，故仅生产代码调整

#### 3. 影响

- 对外行为变化：无（类名、Bean 名、`@Component` 注解、字段与方法签名全部不变）
- 代码变化：1 个生产文件位置迁移 + 1 个反向引用 import 调整 + 1 个空目录删除
- 数据结构变化：无
- 差距项变化：无

#### 4. 验证

- `mvn clean install` → 7 个模块 BUILD SUCCESS（Total time 21.266s）
- `grep "com.helloai.core.service.score"` 全仓检索 → 0 命中，旧路径已无任何残留
- `grep "com.helloai.core.score"` 全仓检索 → 命中 3 处（新文件本身 1 处 + `SubTaskService` import 2 处）
- `core/service/` 根目录现仅保留 25 个业务 Service

---

### 2026-07-14 Phase 2B 外部 Agent 执行闭环补齐 + 调度策略 3（外部优先/空闲优先/LLM 保底）

#### 1. 范围

- 将“执行结果回写”收口为统一领域入口，供平台内执行链与 MCP 外部 Agent 共用
- 补齐外部 Agent 上报阻塞原因的证据链（timeline/context/inbox/outbox）
- 推进调度策略 3：同角色候选优先外部 Agent、空闲优先、并提供“纯 LLM 回归”强制开关
- 扩展为“初始分配也按外部优先选人”（提供自动分配入口与可控开关）

#### 2. 实际落地

- **统一回写入口（结果回写层）**
  - 新增 `ExecutionResultReport` 标准输入对象
  - `ExecutionResultHandler` 新增 `handleReport(report)` 作为唯一状态推进与审计落痕入口
  - 平台内执行链与外部 MCP 均转换为 `ExecutionResultReport` 后进入该入口

- **外部适配器：MCP `submitResult`**
  - 新增 MCP 工具 `submitResult`：接收外部 Agent 的结果 payload，做鉴权/归属/幂等等校验后进入统一回写入口
  - 目标：让 `CLI_CLIENT`（Qoder/Trae/Codex 等）具备“领取任务 → 执行 → 上交结果 → 状态收敛”的最小闭环

- **外部阻塞证据链补齐**
  - `reportBlocked` 传入 `reason` 不再丢弃：写入 `sub_task.context` 并记录 timeline 事件 `sub_task_report_blocked`
  - `BLOCKED` 通知摘要优先展示 `blockedReason`，便于 Planner 排障
  - 对应 outbox payload 增补 `blockedReason` 字段，便于后续 MQ/补偿链消费

- **调度策略 3（可配置）**
  - 新增 `helloai.dispatch.*` 配置：
    - `prefer-external`：同角色候选优先 `CLI_CLIENT`（默认 false，不影响纯 LLM 回归）
    - `require-idle`：要求候选当前无 `IN_PROGRESS` 子任务（默认 true）
    - `force-access-type`：强制仅在指定接入类型内选人（典型：`API_KEY_LLM` 纯保底回归）
    - `auto-assign-on-create`：创建子任务后是否自动进入初始分配（默认 false，保持 PENDING+claim 工作流不变）
  - `AgentSelector` 新增 `pickPreferred(role)`，并在候选过滤中统一应用上述策略

- **初始分配自动选人入口**
  - 新增 `SubTaskDispatchService.dispatchPendingSubTaskAuto(subTaskId, role)`：对 PENDING 子任务按策略选首选 Agent，并交给 `ResilientDispatcher.assignNext` 进入 fast-fail + 熔断 + fallback 的最终分配链
  - `SubTaskController.create` 在 `auto-assign-on-create=true` 且未指定 `assignedAgent` 时触发自动分配

#### 3. 影响

- 对外行为变化：
  - 默认无变化（调度策略默认 `prefer-external=false`、`auto-assign-on-create=false`）
  - 外部 Agent 现在可通过 MCP `submitResult` 上交结果并驱动子任务状态收敛
  - 外部 Agent `reportBlocked(reason)` 的原因进入证据链，Planner 可见且可追溯
- 配置变化：新增 `helloai.dispatch.*` 段并在 `application.yml` 给出默认值
- 数据结构变化：无

#### 4. 验证

- `mvn -DskipTests package` → BUILD SUCCESS

#### 5. 遗留

- 调度策略“外部执行超时/掉线多次后回退 LLM”的阈值计数闭环尚未落地（需要明确计数来源与自动重分配策略）
- 执行命令主链仍未接入 MQ Consumer（仍按 N6 后续推进“MQ 主链路 + DB 状态中心 + Poller 兜底恢复”）

---

### 2026-07-14 Phase 2C N11 外部 Agent 阈值回退 LLM 闭环

#### 1. 范围

- 关闭 Phase 2B 遗留“外部执行超时/掉线多次后回退 LLM”的阈值计数闭环
- 把 N11 从「策略配置已收口」升级为「策略配置 + 自动回退闭环」已交付
- 三处失败来源（handleReport / ExecutionCompensationTask / AgentHealthCheckTask）统一累加计数并触发重新分发
- 重新分发绕过 `AgentSelector`（避免 `preferExternal=true` 又选回 CLI_CLIENT）

#### 2. 实际落地

- **Flyway V17：`V17__agent_external_fallback_fields.sql`**
  - `agent` 表新增 `consecutive_failure_count INT NOT NULL DEFAULT 0` / `last_failure_at TIMESTAMPTZ` / `last_fallback_at TIMESTAMPTZ`
  - `sub_task` 表新增 `external_fallback_count INT NOT NULL DEFAULT 0`
  - 部分索引 `idx_agent_external_failure_scan ON agent(consecutive_failure_count, last_fallback_at) WHERE access_type='CLI_CLIENT' AND deleted=0`
  - 启动日志 `[V17] agent / sub_task 阈值回退字段补全完成`

- **`AgentFallbackProperties`（helloai-common）**
  - `@ConfigurationProperties(prefix = "helloai.dispatch.fallback")`
  - 字段：`enabled`（默认 true）/ `failureThreshold`（默认 3）/ `cooldownMinutes`（默认 10）/ `scanIntervalMs`（默认 60_000L）

- **实体扩展**
  - `Agent` 新增 3 个 N11 字段：`consecutiveFailureCount` / `lastFailureAt` / `lastFallbackAt`
  - `SubTask` 新增 `externalFallbackCount`

- **Mapper 扩展**
  - `AgentMapper`：incrementConsecutiveFailure / resetConsecutiveFailure / markFallbackTriggered / selectFallbackCandidates
  - `SubTaskMapper`：incrementExternalFallbackCount / selectInFlightByAgent
  - 写入路径用 `REQUIRES_NEW` 事务，rollback 不会丢计数

- **`ExternalAgentFailureTracker`（helloai-core 新建）**
  - `recordFailure(agentId)` / `recordSuccess(agentId)` / `markFallbackTriggered(agentId)` 全部 `Propagation.REQUIRES_NEW`
  - `findFallbackCandidates()`：阈值 + 冷却期过滤 + 按 count desc / last_failure_at asc 排序
  - `shouldFallback(agent)` 纯函数：CLI_CLIENT + 阈值达标 + 冷却期满
  - try/catch 包裹所有写入，避免计数器异常打断主链路

- **`SubTaskDispatchService.redispatchForFallback(subTaskId, failedAgentId, reason)`（新建）**
  - 复用 `subTaskService.resetToPendingForDispatch(...)` 把 ASSIGNED/IN_PROGRESS/BLOCKED/REWORK 拉回 PENDING
  - **绕过 `AgentSelector`**：直接 `agentService.listActive().stream().filter(API_KEY_LLM).filter(role).filter(ONLINE/IDLE).max(score)`
  - 记录 timeline `agent_external_fallback_dispatched`，payload 含 trigger / preferredAgentId / previousAgentId / reason
  - 走 `resilientDispatcher.assignNext(fallbackAgentId, subTaskId)` 进入 fast-fail + 熔断 + fallback 链

- **三处失败来源统一注入**
  - `ExecutionResultHandler.handleReport`：CLI_CLIENT + 失败 → `recordFailure`；成功 → `recordSuccess`
  - `ExecutionCompensationTask.compensate`：`markFailed` / `markTimeout` 之后追加 `recordFailure(failedAgentId)`
  - `AgentHealthCheckTask.processStaleAgent`：超时未心跳 + 还在 `IN_PROGRESS` → `recordFailure`

- **`ExternalAgentFallbackTask`（helloai-job 新建）**
  - `@Scheduled(fixedDelayString = "${helloai.dispatch.fallback.scan-interval-ms:60000}")` 周期扫描
  - Redis 分布式锁 `scheduler:lock:ExternalAgentFallback`
  - 5 道前置：开关 / 锁 / 候选非空 / 单 Agent 非空 / 记录 timeline `agent_external_fallback_triggered`
  - 对每个候选 Agent 的在飞子任务逐条 `redispatchForFallback`，最后 `markFallbackTriggered` 写回 `last_fallback_at`

#### 3. 影响

- 对外行为变化：
  - 默认阈值 `failure-threshold=3` + `cooldown-minutes=10`，外部 Agent 连续失败 3 次后下一次定时扫描自动把在飞子任务转交同角色 API_KEY_LLM Agent
  - 阈值与冷却期可调（`helloai.dispatch.fallback.*`）
  - 外部 Agent 成功上报 → 自动 `recordSuccess` → 计数器清零
- 配置变化：`application.yml` `helloai.dispatch.fallback.*` 4 项默认值
- 数据结构变化：
  - `agent` 加 3 列 + `sub_task` 加 1 列 + 1 个部分索引（Flyway V17）
  - `task_timeline` 新增事件 `agent_external_fallback_dispatched` / `agent_external_fallback_triggered`
- 差距项变化：N11 从「部分落地」收口为「已交付」

#### 4. 验证

- `mvn test -pl helloai-core` → 113 个测试全部通过（含 `ExternalAgentFailureTrackerTest` 15 用例 + `SubTaskDispatchServiceTest` 新增 3 用例）
- `mvn test -pl helloai-job` → `ExternalAgentFallbackTaskTest` 10 用例全部通过（含 `shouldSkipWhenDisabled` / `shouldSkipWhenLockNotAcquired` / 候选扫描 / 单 Agent 处理 / 时序）
- 全量 `mvn -DskipTests package` → 6 模块 BUILD SUCCESS
- `grep "agent_external_fallback"` 验证 timeline 事件名拼写一致：2 处生产代码命中 + 2 处测试命中

#### 5. 遗留

- N11 阈值回退闭环已落地，本轮是 Phase 2B 遗留项的最终关闭
- 执行命令 MQ Consumer 主链路仍未接入（仍属 N6 范围，下一轮 P2.3 推进“共用 `ExecutionCommandConsumer` 接口 + 新增 MQ Consumer”骨架）
- 冷却期与阈值当前是全局配置，暂未支持 per-Agent 覆写（按需后续加 `agent.fallback_threshold_override` 列）

---

### 2026-07-14 Phase 2D N6 MQ ExecutionCommand Consumer 骨架（默认 CONDITIONAL 关闭）

#### 1. 范围

- 关闭 Phase 2B/2C 遗留“执行命令 MQ Consumer 主链路未接入”项
- 遵循 `doc/HelloAI_调度解耦重构分析.md` 的“调度只发命令、执行独立消费、结果异步回写”哲学，新建 `MqExecutionCommandConsumer` 骨架
- `MqExecutionCommandConsumer` 与 `LocalExecutionCommandConsumer` **共用 `ExecutionCommandConsumer` 接口**，最终执行链都收敛在同一套 6 步流程上
- **默认 CONDITIONAL 关闭**（`helloai.mq.execution-command.enabled=false`），不影响现有 POLLER / EVENT 主链路；生产/具备 RabbitMQ 的回归环境可手动开启

#### 2. 实际落地

- **`helloai-core/pom.xml`**
  - 新增 `com.helloai:helloai-mq` 依赖（`P2.3a`）——`MqExecutionCommandConsumer` 需要 `AbstractIdempotentConsumer` / `MessageDeduplicationService` / `RabbitMQConfig` / `@RabbitListener` 等 MQ 组件

- **`MqExecutionCommandProperties`（helloai-common 新建）**
  - `@ConfigurationProperties(prefix = "helloai.mq.execution-command")`
  - 字段：`enabled`（默认 `false`）/ `exchange` / `queue` / `routingKey`

- **`ExecutionCommandMqMessage`（helloai-core/agent/mqconsumer 新建 DTO）**
  - 由于 `ExecutionCommand` 使用 Lombok `@Value`、缺少无参构造与 setter，与 Jackson 反序列化不兼容
  - 单独提供 `@Data @Builder` 的 MQ 载体，字段：`recordId / eventId / subTaskId / agentId / trigger / accessType`
  - 枚举 `AgentAccessType` 以**字符串**形式落地，避免枚举顺序漂移导致反序列化失败
  - `from(ExecutionCommand)` / `toDomain()` 两端转换，未知枚举值按 `null` 处理（保留向后兼容）

- **`RabbitMQConfig`（helloai-mq 扩展）**
  - 新增常量 `EXECUTION_COMMAND_QUEUE` / `EXECUTION_COMMAND_EXCHANGE`
  - 新增 3 个 Bean：`executionCommandExchange`（TopicExchange）/ `executionCommandQueue`（durable，绑 `x-dead-letter-exchange = DLX_EXCHANGE` 与 `x-dead-letter-routing-key = DLX_QUEUE`）/ `executionCommandBinding`（`execution.command.*`）
  - 复用 `helloai-mq` 既有 `DLX_EXCHANGE` / `DLX_QUEUE`，不新增 DLX 拓扑

- **`MqExecutionCommandConsumer`（helloai-core/agent/mqconsumer 新建）**
  - `implements ExecutionCommandConsumer` + `extends AbstractIdempotentConsumer`（遵循 `CODE_STYLE §10.3`）
  - `consume(ExecutionCommand)` 直接委托给 `LocalExecutionCommandConsumer.consume(command)`，**不重复实现 6 步执行链**
  - `@RabbitListener(queues = EXECUTION_COMMAND_QUEUE, ackMode = "MANUAL")`：MANUAL ACK 语义
  - 消息体反序列化失败 / `eventId` 缺失/空白 → `basicAck`（不阻塞队列）
  - `tryConsume(eventId, CONSUMER_NAME, () -> consume(command))` 走 Redis + DB 双层幂等
  - 消费成功 → `basicAck`；消费失败 → `basicNack(requeue=false)` 走 DLX
  - `@ConditionalOnProperty(name = "helloai.mq.execution-command.enabled", havingValue = "true")` 默认不注册 Bean

- **`application.yml`（helloai-start）**
  - 新增 `helloai.mq.execution-command.*` 4 项默认配置：`enabled=false` / `exchange=helloai.execution-command.exchange` / `queue=helloai.execution-command.queue` / `routing-key=execution.command.created`
  - 附注释说明：默认 CONDITIONAL 关闭，生产或具备 RabbitMQ 的回归环境可打开

- **`MqExecutionCommandConsumerTest`（helloai-core 测试 新建）**
  - Mockito 为主 + 真实 `ObjectMapper` 序列化，4 个 `@Nested`：`HappyPath` / `EdgeCases` / `Deduplication` / `ChannelIo`
  - 覆盖 6 类行为：正常消息委托+ACK / 坏 JSON ACK / 缺 eventId ACK / 空白 eventId ACK / 委托异常 NACK→DLX / 幂等命中不重复调 consume / `consume(command)` 显式委托 `LocalExecutionCommandConsumer` / `channel.basicAck` 抛 `IOException` 透传

#### 3. 影响

- 对外行为变化：
  - 默认无变化（`enabled=false`，Bean 不注册，MQ 主路径不启用）
  - `enabled=true` 开启后：调度端向 `helloai.execution-command.exchange`（`execution.command.*` 路由）发消息 → `MqExecutionCommandConsumer.onMessage` 消费 → 委托 `LocalExecutionCommandConsumer` 执行 6 步链 → ACK / NACK
- 配置变化：`application.yml` 新增 `helloai.mq.execution-command.*` 4 项
- 数据结构变化：无（不涉及 schema / Flyway）
- 差距项变化：N6 从“实现路径待定”进展为“骨架已交付（CONDITIONAL 关闭）”

#### 4. 验证

- `mvn -pl helloai-core,helloai-mq -am test -Dtest=MqExecutionCommandConsumerTest -Dsurefire.failIfNoSpecifiedTests=false` → 8 个用例全部通过（`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`），`BUILD SUCCESS`
- `git status` 脏文件清单与本轮改动一致：`helloai-core/pom.xml` / `RabbitMQConfig.java` / `application.yml` 3 处修改 + `MqExecutionCommandProperties.java` / `ExecutionCommandMqMessage.java` / `MqExecutionCommandConsumer.java` / `MqExecutionCommandConsumerTest.java` 4 处新增

#### 5. 遗留

- MQ Consumer 默认关闭，需要在具备 RabbitMQ 的环境打开 `helloai.mq.execution-command.enabled=true` 做 E2E 验证
- 生产端（`ExecutionCommandService`）仍只发本地事件 + DB Poller，暂未同时发 MQ 消息（本轮只加 Consumer 骨架）
- `MqExecutionCommandConsumer` 未注入 `MqExecutionCommandProperties`（仅占位 `describeProperties()`），后续接入配置可读与启动期日志

> ⚠️ **Phase 2E / 2F 更新说明（上方旧描述仅作历史快照，以下方标注为准）：**
>
> - `helloai.mq.execution-command.enabled` **已于 Phase 2E 拆分废弃**，当前配置项为 `helloai.mq.execution-command.producer-enabled` 与 `helloai.mq.execution-command.consumer-enabled`，默认均 `false`
> - 上方遗留 ②（生产端未发 MQ）**已于 Phase 2E 关闭**：新增 `ExecutionCommandMqPublisher`，由 `AgentExecutionProperties.dispatch-mode` 控制是否发 MQ
> - 上方遗留 ③（Consumer 未注入 Properties）**已于 Phase 2E 关闭**
> - ②另外存在两个阻断性问题：事务时机与消息编码，**已于 Phase 2F 修复**（Publisher 接入 `TransactionSynchronization.afterCommit()` + 显式 JSON 序列化）
> - E2E 验证的开关也相应从单 `enabled=true` 变为 `dispatch-mode=BOTH` + `producer-enabled=true` + `consumer-enabled=true`

---

### Phase 2E：N6 生产端接入 MQ + 派发模式对称化

#### 1. 范围

- 关闭 Phase 2D 遗留 ②「生产端 `ExecutionCommandService` 未发 MQ」与 ③「Consumer 未注入 Properties」
- 遵循 `doc/HelloAI_调度解耦重构分析.md` "调度只发命令、执行独立消费"目标态：为生产端 / 调度侧引入与 `consumer-mode` **语义对称**的 `dispatch-mode`（`NONE / EVENT / MQ / BOTH`），把生产端行为从消费侧配置上摧开
- MQ 生产 / 消费开关**独立灰度**：`producer-enabled` 与 `consumer-enabled` 拆开
- **默认零行为变化**：`dispatch-mode` 默认 `NONE`，命令只落库交给 DB Poller 兜底，与当前 `consumer-mode=POLLER` 事实配套
- **fail-fast 而非隐式回退**：`dispatch-mode ∈ {MQ, BOTH}` 但 producer 开关未开 / Publisher Bean 不可用 → 启动 & 运行期均抛 `IllegalStateException`
- 本轮不做 E2E（RabbitMQ 环境 ready 后再跑），也不切 Poller 兜底

#### 2. 实际落地

- **`AgentExecutionProperties`（helloai-common）**
  - 新增枚举 `DispatchMode { NONE, EVENT, MQ, BOTH }`
  - 新增字段 `private DispatchMode dispatchMode = DispatchMode.NONE`
  - 新增辅助方法 `isDispatchEvent()` / `isDispatchMq()`，与既有 `isEventMode()` / `isPollerMain()` 语义对称

- **`MqExecutionCommandProperties`（helloai-common）**
  - `enabled` 拆成 `producerEnabled`（默认 `false`）+ `consumerEnabled`（默认 `false`）
  - `exchange` / `queue` / `routingKey` JavaDoc 明确"仅作为启动日志与调试参考，topology 由 `RabbitMQConfig` 常量声明"

- **`ExecutionCommandMqPublisher`（helloai-core/agent/command 新建）**
  - `@ConditionalOnProperty(name = "helloai.mq.execution-command.producer-enabled", havingValue = "true")` 默认不注册
  - 依赖 `RabbitTemplate` + `MqExecutionCommandProperties`
  - `publish(ExecutionCommand)`：`ExecutionCommandMqMessage.from(cmd)` → `convertAndSend(EXCHANGE, routingKey, msg, mpp)`
  - 消息后处理：`messageId = correlationId = eventId`（去重键在消息头显式携带），`deliveryMode = PERSISTENT`
  - 结构化日志：`mq.execution-command.publish eventId=... subTaskId=... agentId=... routingKey=...`

- **`ExecutionCommandService`（helloai-core）**
  - 生产端读 `dispatch-mode` 分发（与 `consumer-mode` 完全解耦）；Publisher 通过 `ObjectProvider<ExecutionCommandMqPublisher>` 注入，避免 producer 关闭时启动失败
  - `NONE`：只落库 + DEBUG 日志
  - `EVENT`：`applicationEventPublisher.publishEvent(ExecutionCommandCreatedEvent)`
  - `MQ`：`mqPublisher.publish(command)`；`getIfAvailable() == null` → 抛 `IllegalStateException`
  - `BOTH`：先发本地事件，再发 MQ（Publisher 缺失同样 fail-fast）
  - 汇总日志加 `dispatch-mode` 与 `consumer-mode` 双字段
  - 移除 `@RequiredArgsConstructor`，改为显式构造函数（为兼容 `ObjectProvider` 参数）

- **`ExecutionDispatchValidator`（helloai-core/agent/command 新建）**
  - `@PostConstruct` 一次性把 `dispatch-mode` / `consumer-mode` / `producer-enabled` / `consumer-enabled` / `exchange` / `queue` / `routing-key` 打印到启动日志
  - `dispatch-mode ∈ {MQ, BOTH}` 但 `producer-enabled=false` 或 Publisher Bean 不可用 → 抛 `IllegalStateException` 阻断上下文启动
  - `dispatch-mode ∈ {MQ, BOTH}` 但 `consumer-enabled=false` → 只 WARN 不阻断（允许 shadow / 跨实例消费场景）

- **`MqExecutionCommandConsumer`（helloai-core）**
  - `@ConditionalOnProperty` 从 `enabled` → `consumer-enabled`
  - 构造函数注入 `MqExecutionCommandProperties`，`describeProperties()` 从返回 `null` 改为返回真实 properties

- **`application.yml`（helloai-start）**
  - 修复历史缩进 bug：Phase 2D 追加时 `exchange` / `queue` 顶格错乱（运行时靠 `MqExecutionCommandProperties` 默认值兜住），本轮正为规范缩进
  - `helloai.execution.dispatch-mode: NONE`（显式默认）
  - `helloai.mq.execution-command.enabled` → 拆成 `producer-enabled: false` + `consumer-enabled: false`
  - 附注释说明 4 挡语义与"支持先开生产端 shadow 观察队列堆积、再开消费端"的灰度节奏

- **测试**
  - `MqExecutionCommandConsumerTest`：构造函数从 4 参改为 5 参（+ `MqExecutionCommandProperties`），既有 8 用例继续绿
  - `ExecutionCommandServiceDispatchTest`（新建，6 用例）：`DispatchByMode` 覆盖 NONE / EVENT / MQ / BOTH 各分支的事件与 MQ 调用次数；`FailFast` 覆盖 `MQ` / `BOTH` 缺 Publisher 时的 `IllegalStateException` + 异常消息包含 `dispatch-mode=`

#### 3. 影响

- 对外行为变化：
  - **默认零变化**：`dispatch-mode=NONE`，命令只落库交给 Poller 兜底，与 Phase 2D 之前完全一致
  - `dispatch-mode=MQ` + `producer-enabled=true` + `consumer-enabled=true` 开启后：`ExecutionCommandService` → `ExecutionCommandMqPublisher.publish` → RabbitMQ (`execution.command.created`) → `MqExecutionCommandConsumer.onMessage` → 委托 `LocalExecutionCommandConsumer` 执行 6 步链
  - `dispatch-mode` 与 `producer-enabled` 配置组合矛盾时启动 fail-fast
- 配置变化：`helloai.execution.dispatch-mode` 新增；`helloai.mq.execution-command.enabled` 拆成 `producer-enabled` + `consumer-enabled`
- 数据结构变化：无
- 差距项变化：N6 从"骨架已交付（CONDITIONAL 关闭）" → "主链路已连通（producer/consumer 独立开关，E2E 待验证）"

#### 4. 验证

- `mvn "-pl=helloai-core" "-am" test "-Dtest=MqExecutionCommandConsumerTest,ExecutionCommandServiceDispatchTest" "-Dsurefire.failIfNoSpecifiedTests=false"` → `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`
- 手工核对：`ExecutionCommandServiceDispatchTest` 日志显示 4 种 `dispatch-mode` 均按预期打印分发路径；`fail-fast` 用例异常消息包含 `dispatch-mode=MQ`

#### 5. 遗留

- E2E 冒烟仍未跑（需 RabbitMQ 环境）：至少覆盖 `dispatch-mode=BOTH + producer-enabled=true + consumer-enabled=true`，观察 Redis + DB 幂等确实抵消双消费
- Poller 兜底切除 / 主路径切换未做，本轮明确保留 Poller 作为兜底路径
- 未做消费侧回写链路（`AsyncExecutionResultConsumer`）改造，`ExecutionResultHandler.handleReport` 现有主路径不动

---

### Phase 2F：N6 两个阻断性问题修复（事务时机 + 消息编码）

#### 1. 范围

- 关闭 Phase 2E 遗留的两个阻断性问题（均影响 MQ 主链路能否真正跑通）
- 保持方向不变（方案 B：dispatch-mode + 双开关），仅修正实现与本地事件路径语义不对齐的两处细节
- 修完后 N6 才真正能描述为“MQ 主链路已连通（E2E 待验证）”；未修之前属于“骨架已搭好但链路断开”

#### 2. 实际落地

- **`ExecutionCommandMqPublisher.publish()` 事务时机对齐 AFTER_COMMIT**
  - 原实现：`ExecutionCommandService.createAssignedCommand` 在 `@Transactional` 方法体里直接 `mqPublisher.publish(command)`，DB 事务未提交时消息已发；本地事件路径用的是 `@TransactionalEventListener(AFTER_COMMIT)`，两路径语义不对称
  - 两类事故风险：（a）事务回滚后消息已发出；（b）消费端读“还未提交”的 `subTask` / `agent` / `record` 而走 ACK 丢弃分支（`MqExecutionCommandConsumer` 现有做法就是将“读不到实体”当尚未就绪情况 ACK）
  - 修复：`publish()` 里先判 `TransactionSynchronizationManager.isSynchronizationActive()`，有事务上下文时仅 `registerSynchronization` 一个 `afterCommit()` 回调，无事务上下文（脚本 / 单测）退化为立即发送；`Service` 层零修改，语义完全内嵌到 Publisher
- **`ExecutionCommandMqPublisher.publish()` 改为显式 JSON 序列化**
  - 原实现：`rabbitTemplate.convertAndSend(exchange, routingKey, POJO)` 依赖默认 `SimpleMessageConverter`，而 `ExecutionCommandMqMessage` 既非 `Serializable` 也无对应 converter，直接抛 `MessageConversionException` → “链路根本发不出去”；而消费端已不对称地按 JSON 用 `objectMapper.readValue(byte[])` 解析
  - 修复：改为 `objectMapper.writeValueAsBytes(message)` + `rabbitTemplate.send(exchange, routingKey, new Message(body, props))`，手动设 `contentType=application/json` / `contentEncoding=UTF-8` / `messageId=eventId` / `correlationId=eventId` / `deliveryMode=PERSISTENT`；与消费端 `readValue(byte[])` 完全对称；不依赖默认 converter，不侵入全局 `RabbitTemplate`，避免波及 `DomainEventPublisher` 等其他路径
- **`ExecutionCommandMqPublisher` 构造函数新增 `ObjectMapper` 参数**（Spring Boot 默认能提供）与新增 `doPublish(command)` 私有方法（封装真正发送）
- **`ExecutionCommandMqPublisherTest` 新增**（5 用例）
  - `NoTransactionContext`：无事务 → 立即发送，`MessageProperties` 字段全对；body 为 JSON，`objectMapper.readValue(byte[])` 可还原全部字段
  - `ActiveTransactionContext`：有事务 → 仅注册 sync，未 `afterCommit` 前 broker 零调用；手动触发 `syncs.get(0).afterCommit()` 后才真发；模拟回滚（`clearSynchronization` 不触发 afterCommit）→ 永不发送
  - `FailurePaths`：JSON 序列化失败 → 抛 `IllegalStateException`（包含 `eventId` 与 `JsonProcessingException` cause），broker 零调用

#### 3. 影响

- 对外行为变化：默认 `dispatch-mode=NONE` + 双开关 `false`，Publisher Bean 不注册 → 本轮对默认行为零影响
- 行为衍生：开启 `dispatch-mode=MQ` 或 `BOTH` 后，MQ 消息总于“DB 事务提交之后”才交给 broker，不会出现“先发后提交”或“提交失败但消息已发”；消费端可以直接信任 `subTask / agent / record` 已存在
- 配置变化：无（开关与消费结构不变）
- 数据结构变化：无
- 差距项变化：N6 从“骨架已搭好但链路断开” → “主链路已连通（producer/consumer 独立开关，E2E 待验证）”的描述真正成立（Phase 2E 描述超前，本轮补统）

#### 4. 验证

- `mvn -pl helloai-core -am test -Dtest=MqExecutionCommandConsumerTest,ExecutionCommandServiceDispatchTest,ExecutionCommandMqPublisherTest -Dsurefire.failIfNoSpecifiedTests=false`
  → `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`
  （具体：MqExecutionCommandConsumerTest 8 + ExecutionCommandServiceDispatchTest 6 + ExecutionCommandMqPublisherTest 5）
- 新增 5 用例覆盖：无事务直发 / JSON 可还原 / 有事务延后 / 回滚不发 / 序列化失败

#### 5. 遗留（下一轮路线已拍板，三个阶段有严格依赖关系，不并列）

1. **先跑 E2E 冒烟**（前提：具备 RabbitMQ 环境）
   - 开 `dispatch-mode=BOTH` + `producer-enabled=true` + `consumer-enabled=true`
   - 重点验证 Redis + DB 双层幂等能否抵消本地事件与 MQ 双消费
   - 确认 MQ 主链路真实可跑后才进入第二阶段
2. **再补生产端可靠投递**（前提：① 已通过）
   - Publisher 接入 `CorrelationData` / publisher-confirms 回执，现阶段仅靠 `RabbitMQConfig.rabbitTemplate` 的 confirm callback 日志可见性
   - Outbox 可靠投递层与回执失败重发策略一同考虑
3. **最后处理 Poller 与回写链路**（前提：①② 已稳定）
   - Poller **不切除**，而是从“主消费载体”降级为孤儿 / 超时 / 补偿兜底（保留作为 MQ 主链异常时的恢复机制）
   - `AsyncExecutionResultConsumer` 消费侧回写链路改造后置，等 MQ 主链与生产端可靠性稳定后再动

> ❗ 不得跳过上一阶段直接进下一阶段；尤其不得在 E2E 冒烟未跑前就推 Outbox 或在生产端可靠性未就绪前变动 Poller 当前职责。

### Phase 2G：E2E 冒烟（MQ + Local 双路同时消费，验证 Redis + DB 幂等抵消）

#### 1. 范围

- 接 Phase 2F 遗留的第①阶段：在本地 Docker RabbitMQ + Postgres + Redis 环境，跑 `dispatch-mode=BOTH` + `producer/consumer=true` 的全链路冒烟
- 重点验证：
  1. Publisher `afterCommit` 之后才真正发送（防止事务回滚后误发）
  2. 本地事件消费与 MQ 消费同时到达时，幂等层能否保证**只有一次**实际执行
  3. Redis + DB 双层幂等层都生效（不靠"Redus 误以为 DB 已经写入了"的虚假判断）

#### 2. 实际落地

- **Flyway V18**：`helloai-start/src/main/resources/db/migration/V18__event_consumption_log.sql`，创建 `event_consumption_log` 表 + `(message_id, consumer)` 复合唯一索引
  - ⚠️ Phase 2E/2F 引入幂等层时**该表 DDL 漏写**，Spring Boot 启动后 MQ Consumer 任何 `isDuplicate` 调用都会报 `BadSqlGrammarException: relation "event_consumption_log" does not exist`，被 catch 静默吞掉
  - 后果：DB 幂等层实际未生效，只靠 Redis 一层兜底。Redis 一旦 flush 或过期，双消费就会重放
  - E2E 启动后第一时间从 `spring-boot-run.log` 看到这个 BadSqlGrammar 才反向定位到 DDL 缺失
- **MessageDeduplicationService 修复 ON CONFLICT**：在 V18 创建的复合唯一索引上，`ON CONFLICT (message_id)` 与索引不匹配，PG 抛 `there is no unique or exclusion constraint matching the ON CONFLICT specification`，被 catch 静默吞掉
  - 修后为 `ON CONFLICT (message_id, consumer) DO NOTHING`
  - 修后：重跑 E2E，`event_consumption_log` 成功写入 1 条 `MqExecutionCommandConsumer / CONSUMED` 记录 ✓
- **ExecutionCommandPoller 构造器歧义修复**：Phase 2E 引入 `MqExecutionCommandConsumer` 后，`ExecutionCommandConsumer` 接口出现两个实现 (`localExecutionCommandConsumer` + `mqExecutionCommandConsumer`)，Spring Bean 工厂报 `expected single matching bean but found 2`，应用起不来
  - 修后：Poller 显式构造器参数类型为 `LocalExecutionCommandConsumer`，语义上也是对的（Poller 是兜底路径，必须投递到本地执行链，不能循环回 MQ）
- **login-raw.ps1 密码错**：脚本里写的是 `helloai123`，V1 迁移默认 admin 账号密码是 `admin123`，修正
- **认证 header 修正**：`POST /api/sub-tasks/execute/{id}` 要走 `X-Admin-Token`，不是 `Authorization: Bearer ...`
- **启动脚本中文路径修复**：`start-sb-e2e-mq.ps1` 里 `$javaExe = 'C:\Users\史航\.jdks\...\java.exe'` 被 Node fallback shell 编码坏，`Start-Process` 报 "系统找不到指定的文件"；改成运行时枚举 `C:\Users\*\.jdks\ms-17.0.18\bin\java.exe`，脚本本身不再含中文字节
- **E2E 触发参数**：造 `sub_task(id=9998887771001, status=ASSIGNED, assigned_agent=2074741030123651073)` + `agent(id=2074741030123651073, name=stage4-api-llm-agent-v4, access_type=API_KEY_LLM)`，`POST /api/sub-tasks/execute/9998887771001` 触发，eventId 动态生成

#### 3. E2E 证据

启动关键日志：
- `execution-command.mq-publisher.init exchange=helloai.execution-command.exchange routingKey=execution.command.created`
- `execution-dispatch.config dispatch-mode=BOTH consumer-mode=POLLER mq.producer-enabled=true mq.consumer-enabled=true`
- `execution-dispatch.validate dispatch-mode=BOTH producer-enabled=true publisher-bean=ready`
- `Flyway: Successfully applied 1 migration to schema "public", now at version v18`

触发后关键日志序列（eventId=`0d774054e1e14f7fbcd869388cb64805`，recordId=`2077000530561904642`）：
1. `mq.execution-command.publish.register-after-commit eventId=...` （Publisher 只注册 afterCommit，未实际发）
2. `mq.execution-command.publish eventId=... routingKey=execution.command.created bodyBytes=192` （提交后才发）
3. `[exec-cmd-1]` 本地事件 `consume`：`startIfNeeded` 被另一条路径抢先推进到 IN_PROGRESS → 记录 `sub_task_execution_command_consume_skipped` → 返回
4. `[ntContainer#0-3]` MQ `tryConsume`：Redis miss → DB miss → 执行 `localDelegate.consume()` → 推进 subTask 到 IN_PROGRESS → 走完整 6 步执行链 → `sub_task_execution_command_consume` + `sub_task_execute_start` + `sub_task_llm_call_start` + `sub_task_llm_call_failed`
5. `MessageDeduplicationService.markConsumed` → Redis SET + DB INSERT (修复后生效) → `MqExecutionCommandConsumer` 36ms 完成 → MQ ACK

DB 验证：
- `event_consumption_log`: 1 条 `MqExecutionCommandConsumer / CONSUMED / 0d774054e1e14f7fbcd869388cb64805` ✓
- `task_timeline`: 8 条事件，**仅 1 次 `sub_task_llm_call_start/failed`**，未出现双 LLM 调用 ✓
- `agent_execution_record`: 1 条 `status=FAILED`（业务失败：Agent 未配置启用态托管凭证 `provider=deepseek`），未出现双 RUNNING ✓
- `sub_task`: status=`BLOCKED`，version 递增正常
- RabbitMQ Management API: `publish=2, ack=1, deliver=2`（同 eventId 投递 2 次但只 ack 1 次，符合预期；最初一次是带 confirm 的 publish，ack 是消费者处理完）

#### 4. 关键结论

- **✅ Phase 2F Publisher afterCommit + 显式 JSON 序列化的两个修复点全部生效**：register-after-commit 日志和 publish 日志有时间顺序，证明发布是在事务提交后才发出的；bodyBytes=192 表明 ObjectMapper 显式序列化成功。
- **✅ 双消费幂等抵消**：
  - 场景：本地事件与 MQ 几乎同时到达本地 execute 链
  - 谁赢？**MQ 路径抢先**（RabbitListener 线程 + `localDelegate.consume()`），把 subTask 推到 IN_PROGRESS 并记录 consume timeline；本地事件路径随后进入 `consume(command)`，`startIfNeeded` 拒绝（当前状态已是 IN_PROGRESS），被本地 startIfNeeded 防御性 catch 拦住 → record `sub_task_execution_command_consume_skipped` → 返回。
  - 结果：`sub_task_llm_call_start` / `sub_task_llm_call_failed` **只发生 1 次**，没有出现双 LLM 调用。
  - 兜底机制分层：
    1. **DB CAS 层（最稳）**：`agent_execution_record.markRunning(recordId)` PENDING→RUNNING CAS，与 subTask startIfNeeded 协同保证只有一条消费路径真正推进业务。
    2. **Redis 快路径**：`mq:dedup:<eventId>` TTL 24h，对同一消息多消费者竞争时直接拦截。
    3. **DB event_consumption_log 兜底**（Phase 2G 修复后才真正生效）：Redis 失效时通过 `(message_id, consumer)` 唯一索引识别已消费。
- **⚠️ 顺手抓到的 3 个隐性 bug**（V18 + ON CONFLICT + Poller 双实现歧义）都是 Phase 2E/2F 引入 MQ 主链时埋下的，未跑 E2E 完全不会暴露。这反向说明"先 E2E 冒烟再继续推生产端可靠性"这个顺序判断是对的。

#### 5. 遗留

- ② Publisher Confirm / Outbox 可靠投递（前提：① 已通过）
- ③ Poller 降级为孤儿 / 超时 / 补偿兜底（前提：①② 已稳定）
- 后续可考虑的细化（不在本轮范围）：
  - `MessageDeduplicationService.markConsumed` 的 PK 用 `System.nanoTime()`，高并发下撞值风险，建议切到 Snowflake ID 生成器
  - `MqExecutionCommandConsumer.onMessage` 在 `tryConsumeEnhanced` 返回 true 时仍然 NACK→DLX；区分"幂等跳过"与"业务失败"，前者应该 ACK 而不是 NACK（否则 DLX 会堆积大量"重复消息"，干扰真实失败信号）
  - `login-raw.ps1` 密码仍写错（`helloai123`），同步成 `admin123`（不在本轮范围，单独立一个文档 / 脚本维护轮）

---

### 2026-07-15 Phase 2H N1 Outbox 最小闭环（②a）

#### 1. 范围

- 按 N1 与架构设计参考 §5.1 的拆步方案，先落地 `ExecutionCommand -> agent_command_outbox -> OutboxRelayTask -> RabbitMQ` 的最小闭环。
- `dispatch-mode=MQ/BOTH` 时，执行命令对应的 `agent_execution_record` 与 Outbox 行在同一事务内写入；`NONE/EVENT` 路径保持原有语义不变。
- 明确隔离两类生命周期：`agent_execution_record` 只表示执行生命周期，`agent_command_outbox` 只表示 MQ 投递生命周期；本轮不把投递状态字段塞入执行记录，也不把普通投递重试噪声写入 `task_timeline`。
- 本轮只完成 ②a，不提前实施 ②b Confirm/Retry、T4 失败可恢复 E2E、T5 Poller 降级或 §5.2 阶段二。

#### 2. 实际落地

- **Flyway V19：`V19__agent_command_outbox.sql`**
  - 新建独立 `agent_command_outbox` 表，与既有 `agent_outbox_event`（SubTask 状态变更事件）分离，避免不同 payload / routing 语义互相扫描。
  - `aggregate_type` 固定为 `EXECUTION_COMMAND`；payload 使用 JSONB；本轮状态为 `PENDING / SENT / FAILED` 三态。
  - 保留 `retry_count`、`next_retry_at`、`error_msg`，并补齐 eventId 唯一索引、PENDING 扫描索引与状态审计索引。

- **Outbox 基础对象（helloai-common / helloai-core）**
  - 新增 `AgentCommandOutboxStatus`、`OutboxAggregateType` 与 `AgentCommandOutboxRelayProperties`。
  - 新增 `AgentCommandOutboxEvent`、`AgentCommandOutboxEventMapper` 与 `AgentCommandOutboxService`。
  - `AgentCommandOutboxService` 提供 5 个最小方法：`createPending`、`listReadyForRelay`、`markSent`、`markFailed`、`markFinalFailed`。
  - `createPending` 依赖外层事务；状态更新按 `status=PENDING` 条件保护，失败重试使用应用侧指数退避。

- **`ExecutionCommandService` 接入 Outbox**
  - `dispatch-mode=MQ/BOTH` 改为在创建执行记录的同一事务内写入 Outbox PENDING 行，不再由业务服务直接调用 Publisher。
  - `dispatch-mode=NONE/EVENT` 保持原有只落库 / 发布本地事件语义。
  - `ExecutionCommandMqPublisher` 本轮仍作为 Relay 使用的底层发送器；Publisher 角色抽象与进一步下沉后移至 ②b（T2.4-Deferred）。

- **`OutboxRelayTask`（helloai-job）**
  - 使用 `@Scheduled` 默认每 `1000ms` 扫描，单批默认 `50` 行；通过 Redis `SETNX` 锁（30 秒 TTL）保证多实例串行 Relay。
  - 读取到期 PENDING 行，反序列化 payload 后调用 `ExecutionCommandMqPublisher`；调用未抛异常则标记 `SENT`，失败则回写 `retry_count / next_retry_at / error_msg`，超过阈值标记 `FAILED`。
  - ②a 的 `SENT` 仅表示当前发送调用成功返回，不代表 Broker Confirm；`CONFIRMED` 与 Confirm-aware Retry 留到 ②b。
  - payload 反序列化失败按不可重试的终态错误处理，直接标记 `FAILED`，不污染业务时间线。

#### 3. 影响

- 数据结构：新增 V19 `agent_command_outbox`；未向 `agent_execution_record` 增加 MQ 投递状态字段。
- 状态归属：Broker 投递、重试节奏与最终失败只回写 Outbox；超过阈值或最终失败是否补业务级 timeline，留待 ②b 的告警 / 业务事件设计统一处理。
- 默认行为：默认 `dispatch-mode=NONE` 不受影响；Poller 当前仍保留为现行消费载体，待可靠投递闭环稳定后再调整职责。

#### 4. 验证

- `ExecutionCommandServiceDispatchTest`：4 个 dispatch-mode 用例全部通过，验证 NONE / EVENT / MQ / BOTH 分支行为。
- `ExecutionCommandServiceTest`：5 个用例全部通过，验证 MQ 路径改为写 Outbox。
- `OutboxRelayTaskTest`：5 个用例全部通过，覆盖发送成功、可重试失败、终态失败、空批次与 payload 反序列化失败。
- 本轮共 14 个单元测试通过；RabbitMQ Confirm / 失败可恢复 E2E 不在本轮验证范围，Phase 2G 已完成的主链 E2E 证据保持有效。

#### 5. 遗留与下一步

- ②b：补 `CorrelationData`、publisher confirms、`CONFIRMED` 状态与 Confirm-aware Retry，并明确 `FAILED / CONFIRMED / next_retry_at / retry_count` 的状态机边界。
- T4：在真实 RabbitMQ 环境跑失败可恢复 E2E，验证的不只是“能发”，而是 Broker 异常后可重试、可确认、可终态收敛。
- T5：可靠投递稳定后，将 Poller 从默认主消费降为孤儿 / 超时 / 补偿兜底，保留作为 MQ 主链异常恢复机制。
- T6：§5.2 的 WorkUnit、STOP/PAUSE/REPLAN、用户输入可重入继续后置。

---

### 2026-07-15 Phase 2H N1 Confirm / Retry（②b）

#### 1. 范围

- 按 §5.1 路线拍板的"②b Confirm / Retry"——只做最小闭环，承接 Phase 2H ②a 的 `agent_command_outbox` 与 `OutboxRelayTask`，把状态机从 `PENDING / SENT / FAILED` 三态扩到 `PENDING / SENT / CONFIRMED / FAILED` 四态，并补齐 publisher confirms / `CorrelationData` / Confirm-aware Retry / SENT 超时回退。
- 明确本轮**不**做：Poller 降级、`OutboxCompensationTask` 新增调度（沿用 `OutboxRelayTask`）、DLQ、per-event 业务级熔断；T4 E2E 失败可恢复、T5 Poller 降级、T6 §5.2 继续后置。

#### 2. 实际落地

- **Flyway V20：`V20__agent_command_outbox_confirms.sql`**
  - 通过 `information_schema.columns` 判型，对 `agent_command_outbox.status` 做 `VARCHAR → SMALLINT USING (CASE …)` 兼容迁移，覆盖 `PENDING/SENT/FAILED/CONFIRMED` 与 `0/1/2/3` 双向兼容；落地后 status 默认值 `0`。
  - 新增 `last_sent_at` / `confirmed_at` 两列（`TIMESTAMPTZ`），仅由 `OutboxRelayTask` 维护，不与执行生命周期混用。
  - 重建 PENDING 部分索引（`next_retry_at`，`WHERE status = 0 AND deleted = 0`），并新增 SENT 部分索引 `idx_agent_command_outbox_sent_scan`（`last_sent_at`，`WHERE status = 1 AND deleted = 0`）支撑 Confirm 超时回退扫描。
  - CHECK 约束扩展到 `status IN (0, 1, 2, 3)`，覆盖新增的 `CONFIRMED`。
  - 不向 `agent_execution_record` 增加任何 MQ 投递状态字段；执行生命周期与投递生命周期继续严格分层。

- **状态机（`helloai-common/.../AgentCommandOutboxStatus`）**
  - 新增 `CONFIRMED(3)`，实现 `IEnum<Integer>`，与 `OutboxStatus`（`agent_outbox_event`）继续正交。
  - 状态迁移表更新为：`PENDING ─[发送调用成功]→ SENT ─[broker ACK 且无 return]→ CONFIRMED`；`SENT ─[NACK / return / 超时 / confirm 回调丢失]→ PENDING（指数退避）` 或 `→ FAILED（超阈值）`；`PENDING ─[发送失败重试额度耗尽]→ FAILED`。

- **实体（`helloai-core/.../AgentCommandOutboxEvent`）**
  - 补齐 `lastSentAt` / `confirmedAt` 字段；`payload` 仍由 `JacksonTypeHandler` 映射 `jsonb`，字段与 `ExecutionCommandMqMessage` 完全对称。

- **`AgentCommandOutboxService`（helloai-core）**
  - 新增 `listExpiredSentForRetry(limit)`：扫描 `status = SENT AND confirmed_at IS NULL AND last_sent_at <= now - confirmTimeout AND retry_count < maxRetry`，按 `last_sent_at` 升序，单批上限由调用方控制。
  - 收紧 `markSent(id, sentAt)`（二参）并新增 `markConfirmed(id, confirmedAt)`：保留 `WHERE status = PENDING / SENT` 的悲观 CAS 保护，状态不漂移。
  - 新增 `markFailedFromSent` / `markFinalFailedFromSent`：SENT → PENDING 回退或 SENT → FAILED 终态；与既有 `markFailed` / `markFinalFailed` 形成"发送前失败"与"发送后失败"两套对称更新路径。
  - `error_msg` 仍走 1000 字符截断，避免 broker 异常堆栈撑爆单行。

- **`ExecutionCommandMqPublisher`（helloai-core，Publisher 角色下沉前过渡）**
  - 新增 `publishWithCorrelation(command, correlationKey)` 返回 `CorrelationData`，底层仍走 `rabbitTemplate.send(exchange, routingKey, message, correlationData)`；`eventId` 仍作为 `MessageProperties.messageId / correlationId` 落到消息头。
  - 序列化与 `afterCommit` 时机对齐 ②a 的语义；现有 `publish(command)` 路径未删，但 Relay 已切换到 `publishWithCorrelation`。

- **`OutboxRelayTask`（helloai-job）**
  - 单条处理链：调用 `publishWithCorrelation(command, outboxId)` → 同步 `markSent(id, now)` → `attachConfirmCallback(row, correlationData)`。
  - `handleConfirm` 区分 ACK / NACK / `CorrelationData.getReturned()` / `confirm-timeout`，命中 ACK 且无 return 时 `markConfirmed`；其它路径走 `scheduleRetryFromSent`，复用既有指数退避（`baseBackoffSeconds * 2^retryCount`，截断到 2^10 避免溢出）。
  - 每轮扫描前先调 `revertExpiredSent(batchLimit)`：扫出历史 SENT 超时未确认行（应对重启后 in-flight future 丢失）并走相同回退路径；不与 PENDING 主扫描冲突，分两步执行。
  - `RelayOutcome` 指标仍为 `SENT / FAILED / FINAL_FAILED / SKIPPED`，本轮不引入 `CONFIRMED` 计数（状态收敛在 confirm 回调，不在 batch 出口）。

- **RabbitMQ 配置（`application.yml` + `RabbitMQConfig`）**
  - `spring.rabbitmq.publisher-confirm-type: correlated`、`publisher-returns: true`；`RabbitMQConfig` 在自定义 `RabbitTemplate` 上注册 `ConfirmCallback` / `ReturnsCallback` 并 `setMandatory(true)`，确保 correlated confirms 与 return 都能触发；当前 outbox 路径使用 `publishWithCorrelation` 拿到 `CorrelationData.getFuture()`，独立消费 confirm；不依赖 template 级回调。

- **测试**
  - `OutboxRelayTaskTest`：用例从 5 扩到 7，覆盖 Publisher 成功（含 `markSent` + `markConfirmed` 顺序）、Publisher 异常但 < maxRetry、Publisher 异常 ≥ maxRetry、空批次、payload 反序列化失败、**Confirm NACK → `markFailedFromSent`**、**SENT 超时 → `markFailedFromSent`**；`properties.getConfirmTimeoutSeconds()`、`outboxService.listExpiredSentForRetry(anyInt())` 走 `lenient()` 默认 stub，单元层不依赖真实 broker。
  - `mvn -pl helloai-common,helloai-core,helloai-job -am test` ✅；`mvn -DskipTests clean install` ✅。

#### 3. 影响

- 数据结构：`agent_command_outbox.status` 由 `VARCHAR(32)` 转为 `SMALLINT`（保留旧值的兼容映射），新增 `last_sent_at` / `confirmed_at` 两列；既有 V19 索引正确 drop & recreate；CHECK 约束扩展到 `0/1/2/3`。
- 状态归属：`CONFIRMED` 仅由 `CorrelationData.getFuture()` 完成时回写 outbox；技术噪声（NACK / return / 超时）只动 outbox 表，不写入 `task_timeline`。
- 执行侧：`MqExecutionCommandConsumer` 与 Outbox 状态机正交，CONFIRMED 只在生产端可见，消费端按既有 MANUAL ACK + 幂等逻辑收敛，不感知 outbox 内部状态。
- Publisher 角色抽象（`OutboxCommandSender` 接口下沉）继续后置，待 ②b 实战稳定后再启动。

#### 4. 遗留与下一步

- T4：在真实 RabbitMQ 环境跑失败可恢复 E2E，验证"Broker 异常后可重试、可确认、可终态收敛"，覆盖 NACK、broker 重启、回调丢失三种场景。
- T5：可靠投递稳定后将 Poller 从默认主消费降级为孤儿 / 超时 / 补偿兜底，保留作为 MQ 主链异常恢复机制；`AsyncExecutionResultConsumer` 回写链路改造后置。
- T6：§5.2 WorkUnit / STOP/PAUSE/REPLAN / 用户输入可重入继续后置。
- R2：`ExecutionCommandMqPublisher.publish()` 旧方法仍存在并被既有单测使用，但内部已不注册 `whenComplete` 监听 confirm future，存在"未来静默丢失"的潜在风险，待 T3 实战稳定后单独清理。
- R3：V20 不回填 V19 era 的历史 SENT 行 `last_sent_at`，导致 `listExpiredSentForRetry` 暂时不会触及这些行；考虑到 Phase 2H 才刚上线且 V19 era 内 SENT 行极少，影响面有限。

---

### T4: Outbox ②b RabbitMQ 故障恢复路径 E2E 验证

#### 1. 范围

承接 Phase 2H ②b "遗留与下一步" 中 T4 项的"E2E 验证"，覆盖 OutboxRelayTask 在真实 RabbitMQ 故障下的三条恢复路径：

- **S1 broker NACK**：队列容量耗尽，broker 主动 nack
- **S2 mandatory return**：publish 路由无 binding 命中，`mandatory=true` 触发 ReturnsCallback
- **S3 confirm timeout**：broker 响应丢失 / in-flight future 丢失，由 `revertExpiredSent` 兜底回收
- **S4 control happy path**：对照基线，验证正常 ACK 路径下 `confirmed_at` + `last_sent_at` 同时回写

#### 2. 实际落地

- **交付脚本**：`verify-outbox-relay-confirm-e2e.ps1`（≈ 770 行，PowerShell 5.1 兼容）
  - 参数：`-SkipPrepare` 复用上一轮 sample agent / sub_task；`-Cleanup` 幂等删除本 runTag 产生的所有 outbox 行 + 恢复 broker 配置
  - **pre-flight probe**：插一条临时 PENDING 行 + 8s 等待 status 变化，避免 `dispatch-mode=NONE` / `producer-enabled=false` 时 relay 静默 FAIL；本轮实测因 IDEA 启动未切 MQ 一度全场景 FAIL，probe 段介入后准确定位到配置根因
  - 幂等 runTag：`yyyyMMdd-HHmmss` 后缀，event_id / agentId / taskId / subTaskId / outboxId 均按 runTag 派生，重跑不冲突；outboxId 用 `epoch_ms * 1000` 雪花种子 + 单调计数器派生 snowflake-shaped bigint
  - 4 个场景独立 INSERT + 等待循环 + 多字段断言（status / last_sent / confirmed / retry_count / error_msg）

- **实测结果**（runTag=`20260715-133106`，agentId=`714468167`，subTaskId=`714468187`，`helloai.execution.dispatch-mode=MQ` + `helloai.mq.execution-command.producer-enabled=true` 启用后）

  | 场景 | 故障模拟方式 | 终态（id 状态 重试 last_sent confirmed 错误信息） | 结果 |
  |---|---|---|---|
  | **S1 broker NACK** | RabbitMQ policy `max-length=1, overflow=reject-publish`，灌 2 条 PENDING | row1 `0 / 1 / 0 / –`；row2 `0 / 1 / 3 / 0 / "confirm-nack: null"` | **FAIL 但 NACK 路径触发已证实**：`error_msg=confirm-nack` + `retry_count=3` 是 broker NACK 路径生效的不容辩驳证据；但 `max-length=1` 太严，Spring AMQP publisher confirms 异步时序导致 row1 也被拒，未达成"row1 ACK + row2 NACK"对照语义 |
  | **S2 mandatory return** | DELETE exchange→queue 上**所有** binding（用 `properties_key` 而非 routing key，避免遗漏 `routing_key=null` 兜底 binding），`mandatory=true` | `1784093466325004 / 0 / 1 / 1 / 0 / "returned: NO_ROUTE"` | **PASS**：`status=PENDING` + `last_sent_at` 已写 + `confirmed_at` 空 + `error_msg=NO_ROUTE`，完整验证 `ReturnsCallback` → `scheduleRetryFromSent("returned: ...")` 路径 |
  | **S3 confirm timeout** | SQL 直接插 `status=1` 行 + `last_sent_at=now-120s`，1.2s 内查询 | `1784093466325005 / 0 / 1 / 1 / 0 / "confirm-timeout: expired-sent"` | **PASS**：`revertExpiredSent` 把 SENT 超时行拉回 PENDING + 写 `confirm-timeout` 标记，完整验证 broker ack 丢失场景的兜底回收 |
  | **S4 control happy path** | 正常 broker 配置 + 灌 1 条 PENDING | `1784093466325006 / 3 / 0 / 1 / 1 / ""` | **PASS**：`status=CONFIRMED(3)` + `last_sent_at` 与 `confirmed_at` **同时回写**，证明 ACK 且无 return 路径完整闭环 |

- **脚本工程经验沉淀**

  RabbitMQ Management API 三个坑位（直接决定故障模拟能否生效）：
  1. **PUT queue arguments 不可靠**：`PUT /api/queues/{vhost}/{name}` 修改已存在 queue 的 arguments 在本轮 broker 版本返回 HTTP 400 `not_json` 且 arguments 不更新；改用 `PUT /api/policies/{vhost}/{policy-name}` 设 `max-length` / `overflow` 并 `apply-to=queues`，policy 热生效、不破坏 queue 自身 DLX 配置
  2. **DELETE binding 必须用 `properties_key`**：URL 段必须用 binding 的 `properties_key` 字段（带 properties_hash，可能是字面字符串 `"null"`），不能用 routing key；当 exchange 上存在 `routing_key=null` 兜底 binding 时只按 routing_key 删会遗漏，mandatory return 因此失败
  3. **confirm-timeout 等待窗口 < 1 个 relay 周期**：默认 `helloai.outbox.relay.interval-ms=1000`，S3 等待窗口必须 ≤ 1.2s，否则会被下一轮 relay 重新 publish + ACK 把 `PENDING(0)` 中间态覆盖成 `CONFIRMED(3)`，断言看到永远是最终态；正确做法是等待 ≤ 1.2s 后查"中间态" + 再等 3s 看"最终态"

  PowerShell 5.1（.NET Framework 4.x）三个兼容性问题：
  1. **`[System.Net.Http.HttpClient]` 不存在**：仅 .NET 5+ 有；改用 PS 5.1 原生 `Invoke-WebRequest -UseBasicParsing -Headers @{Authorization="Basic ..."} -TimeoutSec 3`
  2. **`agent_command_outbox.id` NOT NULL 无 default**：MyBatis-Plus `ASSIGN_ID` 雪花由 Java 端写入；直接 SQL INSERT 必须显式指定 id；用 `epoch_ms * 1000` 作为种子 + 单调计数器派生 snowflake-shaped bigint
  3. **单元素数组 unroll**：`$rows[0]` 在 PS 5.1 单元素数组场景下被当 Char 集合处理，`.Split('|')` 失败；改用 `[string]($rows | Select-Object -First 1)` 强转字符串

#### 3. 影响

- 对外行为：无变化（T4 为 E2E 验证脚本，不改业务代码）
- 代码变化：新增 1 个 PS1 脚本 `verify-outbox-relay-confirm-e2e.ps1`
- 数据结构变化：无
- 差距项变化：
  - **N1（Phase 2H ②b Outbox 可靠性）闭环证据完整**：S2/S3/S4 三场景实测 PASS + S1 通过 `error_msg=confirm-nack` + `retry_count=3` 验证 broker NACK 路径触发，差距表 N1 可标"已交付"
  - 新增 R4：T4.1 S1 语义修正方案 A 待落地

#### 4. 遗留与下一步

- **T4.1（S1 语义修正）**：将 S1 调整为 `max-length=2` + 3 条 PENDING，达成"row1 + row2 ACK → CONFIRMED，row3 NACK → PENDING + error_msg=confirm-nack"的对照语义；预计 1 轮脚本修改 + 1 次重跑即可拿到全绿四场景
- **T4.2（建议）**：把"dispatch-mode=MQ + producer-enabled=true"验证场景放进独立的 Spring profile（如 `mq-e2e`），避免每次 E2E 都需手工改 `application.yml` + 重启 IDEA；下个迭代阶段一并推进
- **T5**：Poller 降级为孤儿 / 超时 / 补偿兜底（按 ②b "遗留与下一步"原计划推进）
- R2 / R3：维持 ②b 阶段遗留（Publisher 旧方法 `publish()` 静默丢失 confirm future / V19 era SENT 行 `last_sent_at` 未回填），暂不处置

---

### T5：N6 Poller 主动降级为孤儿 / 超时 / 补偿兜底 + Validator 启动期 fail-fast 闭环

#### 1. 范围

承接 T4 "遗留与下一步" 中 T5 项的 "Poller 降级为孤儿 / 超时 / 补偿兜底"，按差距表 N6 处理建议推进。本轮是 **功能定位重塑**（非文档纠错或纯重构），4 个用户拍板决策点：

- `consumer-enabled=false`：默认主线下直接 fail-fast（不允许 "主消费路径全关但 Poller 仅兜底，PENDING 永远不被消费" 的事故形态）
- `listAllPending`：删除 Poller 对它的调用（统一走 `listOrphanPending`）
- `ExecutionCompensationTask`：不并入 Poller（保持独立 `Scheduled`）
- `application.yml` 默认值：切到 MQ 主链 + Poller 兜底（`consumer-enabled=true`）

不动：`ExecutionCompensationTask` 既有职责、与 MQ 主链路（Phase 2D-2H）的协作模式、§5.2 阶段二（WorkUnit / 控制命令 / 用户输入可重入）。

#### 2. 实际落地

- **`AgentExecutionProperties.ConsumerMode`（helloai-common）注释重塑**
  - 三种模式都明确为 "Poller 仅作孤儿 / 超时 / 补偿兜底"，区别只在 **主消费路径** 由谁承担：
    - `EVENT`：`@TransactionalEventListener(AFTER_COMMIT)` 主消费（本地 Spring 事件）
    - `POLLER`：本轮已无独立 "DB Poller 主消费"，主消费路径由 `MqExecutionCommandConsumer` 承担（MQ 路径）
    - `BOTH`：本地事务事件 + MQ 双主消费，CAS 幂等抵消
  - 辅助方法 `isPollerMain()` / `isEventMode()` 名称保留但语义更新为 "主消费路径能力开关"：`isEventMode()` = 本地事务事件启用；`isPollerMain()` = MQ 主消费路径启用（与 `MqExecutionCommandProperties.consumer-enabled` 配套）
  - 类注释明确说明 "Poller 在三种模式下都是兜底恢复机制，不再是主消费载体"

- **`ExecutionCommandPoller`（helloai-core/agent/dispatcher）改造**
  - 删除 `listAllPending` 分支：所有 `consumer-mode` 统一调用 `agentExecutionRecordService.listOrphanPending(threshold, batchSize)`
  - `scanType` 恒为 `listOrphanPending`，不再有 `polled_main` / `poll_main` 双值
  - `trigger` 前缀恒为 `poll-recovery:`，不再有 `poll-main:` 分支
  - timeline 事件恒为 `sub_task_execution_command_poll_recovery`
  - 类注释更新："T5 起 Poller 不再作为主消费载体，仅作孤儿 / 超时 / 补偿兜底，负责 MQ Consumer 异常 / 应用重启 / 事件丢失场景的恢复"

- **`ExecutionDispatchValidator` 新增 POLLER/BOTH fail-fast（helloai-core/agent/command）**
  - `consumer-mode ∈ {POLLER, BOTH}` 但 `consumer-enabled=false` → 抛 `IllegalStateException`
    - 错误消息包含 `consumer-mode=POLLER` / `consumer-enabled=true` / `POLLER/BOTH 模式下没有主消费路径` / `agent_execution_record PENDING 行将永远不被消费`
    - 阻止 "主消费路径全关但 Poller 仅兜底，PENDING 永远不被消费" 的事故形态
  - 保留 Phase 2E 的 `dispatch-mode ∈ {MQ, BOTH}` 但 `producer-enabled=false` 或 Publisher Bean 不可用 → 抛 `IllegalStateException`
  - 保留 `dispatch-mode ∈ {MQ, BOTH}` + `consumer-enabled=false` 的 WARN（跨实例消费 / shadow 场景）
  - 启动期 `@PostConstruct` 一次性打印 4 配置 + 4 Bean 可用性

- **`application.yml`（helloai-start）**
  - `helloai.mq.execution-command.consumer-enabled: true`（默认值由 `false` 改为 `true`）
  - 注释重塑为 "Poller 兜底" 语义：MQ 主链默认开启，Poller 仅作孤儿 / 超时 / 补偿兜底
  - YAML 注释完整写进灰度节奏：
    1. MQ 环境就绪后先开 `producer-enabled=true` 观察队列堆积
    2. `producer/consumer=true` + `dispatch-mode=BOTH` 进入双消费，CAS 抵消
    3. 主链稳定后可选 `consumer-enabled=false` + `consumer-mode=EVENT`，退回纯本地事件主消费 + Poller 兜底

- **`AgentExecutionRecordService.listAllPending`（helloai-core）兼容保留**
  - 加 `@Deprecated(forRemoval=false)` 注解
  - Javadoc 更新为 "T5 起 Poller 不再调用本方法，保留仅为兼容历史代码与排查工具；新代码请使用 `listOrphanPending(int, int)` 扫描孤儿 PENDING"
  - 既有调用方（验证脚本 / 排查工具）继续可用，不强制移除

- **`ExecutionCompensationTask`（helloai-job）保持独立**
  - 不并入 Poller，职责边界清晰：Poller 只扫 "孤儿 PENDING"（基于 `last_attempt_at`），补偿任务只扫 "PENDING 超时"（基于 `create_time`）+ `RUNNING 超时`（基于 `last_attempt_at`）
  - 合并会导致调度复杂度升高、Poller 设计目标被覆盖；当前实现已通过 `ExecutionCompensationTaskTest` 3 用例验证 CAS + 状态守卫逻辑
  - 即用户拍板决策点之三："`ExecutionCompensationTask` 不并入 Poller"

- **`ExecutionCommandPollerTest`（helloai-core 测试）改造**
  - 删除 `PollerMain` 嵌套类（5 个 `listAllPending` 主路径用例，全部基于 "Poller 主消费" 假设）
  - 新增 `DowngradeConsistency` 嵌套类（5 个用例）：
    - `EVENT 模式：调 listOrphanPending，永不调 listAllPending`
    - `POLLER 模式：调 listOrphanPending，永不调 listAllPending`
    - `BOTH 模式：调 listOrphanPending，永不调 listAllPending`
    - `三种模式：trigger 前缀恒为 poll-recovery:`
    - `POLLER 模式空批次：不调 listAllPending，直接返回`
  - 类注释更新："T5 起 Poller 不再作为主消费载体，三种 consumer-mode 都仅扫孤儿 PENDING"

- **`ExecutionDispatchValidatorTest`（helloai-core 测试 新建）**
  - 5 个 `@Nested` 共 14 用例：
    - `DispatchModeFailFastOnProducer`（3 用例）：NONE 通过 / MQ 缺 producer / BOTH 缺 Publisher Bean
    - `DispatchModeFailFastOnRelay`（3 用例）：NONE 通过 / MQ 缺 relay / BOTH 缺 relay
    - `ConsumerModeFailFast`（5 用例）：POLLER 缺 consumer 抛错 / BOTH 缺 consumer 抛错 / EVENT 缺 consumer 通过（允许）/ POLLER 合法通过 / BOTH 合法通过
    - `DispatchWarnOnConsumerDisabled`（2 用例）：MQ + consumer=false WARN / BOTH + consumer=false WARN（不阻断）
    - `ValidCombinationsAndPriority`（4 用例）：4 类合法组合路径 + Producer fail-fast 优先级高于 Consumer fail-fast
  - 完整覆盖 ②a / ②b 闭环 + T5 新闭环 + WARN 不阻断 + 合法组合与组合优先级

- **`verify-poller-e2e.ps1` 同步更新**
  - 顶部注释 v1 → v2，明确 T5 后模型：Poller 仅作孤儿 / 超时 / 补偿兜底，不再作主消费
  - timeline 事件名统一：`sub_task_execution_command_polled_main` → `sub_task_execution_command_poll_recovery`（与 Poller 重命名后的实际事件名一致）
  - 修复 PS 5.1 `[System.Net.Http.HttpClient]` 兼容性 bug：改用 `Invoke-WebRequest -UseBasicParsing -Headers @{Authorization="Basic ..."} -TimeoutSec 3`
  - 本轮未重跑（脚本本身属于历史 V16 era 的 Poller 主消费取证，与 T5 降级后模型不同，验证场景需重新设计 —— 详见 §5 遗留与下一步 S5）

#### 3. 影响

- 对外行为变化：
  - **默认反转**：旧默认 `consumer-enabled=false` + Poller 主消费（POLLER 模式）→ 新默认 `consumer-enabled=true` + MQ 主消费 + Poller 兜底
  - **阻断形态**：`consumer-mode=POLLER/BOTH` + `consumer-enabled=false` 现在启动期直接 fail-fast，不再允许 "静默退化"
  - **可选形态**：主链稳定后可切 `consumer-mode=EVENT` + `consumer-enabled=false`，回到纯本地事件主消费 + Poller 兜底
- 配置变化：
  - `application.yml` `helloai.mq.execution-command.consumer-enabled` 默认值 `false → true`
  - `application.yml` `helloai.execution.dispatch-mode` 维持显式 `NONE`（保留 Phase 2E 兼容性）
  - 注释完整重写为 "Poller 兜底" 语义
- 代码变化：
  - 修改 5 个生产文件（AgentExecutionProperties / ExecutionCommandPoller / ExecutionDispatchValidator / AgentExecutionRecordService / application.yml）
  - 修改 1 个测试文件（ExecutionCommandPollerTest）+ 新建 1 个测试文件（ExecutionDispatchValidatorTest）
  - 修改 1 个验证脚本（verify-poller-e2e.ps1）
  - 总计 8 个文件
- 数据结构变化：无（T5 是定位重塑，不涉及 schema / Flyway）
- 差距项变化：
  - **N6 完成 "消费者定位重塑" 最后一段**：从 "POLLER 默认 + MQ 可选" → "MQ 主链（POLLER/BOTH）+ Poller 仅兜底（默认全开）"
  - Poller 永久不再作为主消费载体，仅作 MQ Consumer 异常 / 应用重启 / 事件丢失场景的恢复机制

#### 4. 验证

- `mvn -pl helloai-core test -Dtest="ExecutionCommandPollerTest"` → DowngradeConsistency 5 用例全过 + 既有 6 用例回归
- `mvn -pl helloai-core test -Dtest="ExecutionDispatchValidatorTest"` → 14 用例全过
- `mvn -pl helloai-common,helloai-core,helloai-mq,helloai-job,helloai-api,helloai-start -DskipTests clean install` → 6 模块 BUILD SUCCESS
- 启动期验证（`SpringBootApplication.run`）：
  - `consumer-mode=POLLER` + `consumer-enabled=false` → 启动期抛 `IllegalStateException`，错误消息包含 `consumer-mode=POLLER` / `consumer-enabled=true` / `POLLER/BOTH 模式下没有主消费路径` / `agent_execution_record PENDING 行将永远不被消费`（由 `ExecutionDispatchValidatorTest.ConsumerModeFailFast.shouldFailFastWhenConsumerPollerButConsumerDisabled` 钉死）
  - `consumer-mode=POLLER` + `consumer-enabled=true` + `dispatch-mode=BOTH` + `producer-enabled=true` → 正常启动并打印 4 配置 + 4 Bean 可用性

#### 5. 遗留与下一步

- **T6**：§5.2 WorkUnit / STOP/PAUSE/REPLAN / 用户输入可重入继续后置（不在本轮范围）
- **R2**：`ExecutionCommandMqPublisher.publish()` 旧方法静默丢失 confirm future，维持现状待单独立项清理（待 T3 实战稳定后启动）
- **R3**：V20 不回填 V19 era 历史 SENT 行 `last_sent_at`，维持现状（Phase 2H 阶段内 SENT 行极少，影响面有限）
- **新增建议项 S5（Poller 兜底场景观测 E2E）**：本轮关闭了 "Poller 作为主消费" 语义，但兜底扫描是否真的能在 MQ 主链异常时接住孤儿 PENDING，仍缺 E2E 验证；建议下个迭代阶段单独立项做一次 "故意停 MQ Consumer + 注入一条孤儿 PENDING + 观察 Poller 恢复" 的对照实验，并把 `verify-poller-e2e.ps1` 完全重写以匹配 T5 后模型（当前脚本仍带 Poller 主消费 era 的 V16 假设，不能直接用于验证 Poller 兜底）

> ⚠️ T5 后的 "灰度节奏" 建议（不在 N6 处理建议内，仅作运维参考）：
>
> 1. MQ 环境就绪：`dispatch-mode=BOTH` + `producer/consumer=true`，跑全链路冒烟后保留双开
> 2. 主链稳定：`dispatch-mode=MQ` + `producer/consumer=true`，去掉 EVENT 路径噪声
> 3. 退回纯本地事件（可选）：`consumer-mode=EVENT` + `consumer-enabled=false` + `dispatch-mode=NONE`，依赖本地事务事件主消费 + Poller 兜底
>
> 不得在不调整 `consumer-mode` 的情况下单独关闭 `consumer-enabled=false`，本轮 fail-fast 已经把这条红线钉死在 `ExecutionDispatchValidator` 里。

---

### 前端积分流水修复 + Agent ID 选择组件化

#### 1. 范围

- 修复积分流水页面（RewardList.vue）展示数据为空的问题
- 将散落在多个页面中的"手工输入 Agent ID"统一为下拉选择组件
- 修复认领子任务时 Agent ID 硬编码为 1 的 Bug

#### 2. 实际落地

- **积分流水数据修复**
  - 根因：前端 RewardList.vue 调用 `GET /api/scores/leaderboard`（返回 Agent 积分排行榜 `{agentId, agentName, role, totalScore}`），但表格列绑定的 prop 为 `reason / delta / balance / createTime`（reward_log 表字段），前后端数据结构不匹配导致全部单元格为空
  - 后端新增 `GET /api/scores/logs?page=&pageSize=` 端点，调用 `RewardService.listAllLogs()` 分页查询 reward_log 表按创建时间倒序返回，字段与前端表格列完全对齐
  - 前端 RewardList.vue 切到新端点，解析 IPage.records，新增分页组件

- **AgentSelect 组件新建**
  - 新建 `components/AgentSelect.vue`：可复用的 Agent 下拉选择组件，挂载时自动从 `GET /agents` 加载列表，支持 filterable 搜索，选项格式 `名称 (角色)`，支持 v-model 双向绑定

- **RewardList.vue 手动调整积分弹窗**：Agent ID 输入框从 `<el-input>` 替换为 `<AgentSelect>`，不再手工填写

- **SubTaskList.vue 认领子任务**
  - 将 `ElMessageBox.prompt('输入 Agent ID')` 替换为弹窗 + `<AgentSelect>` 下拉选择
  - **Bug 修复**：原逻辑 `subTaskApi.claim(row.id, 1)` 中 agentId 硬编码为 1，无论 prompt 输入什么值都被忽略；修复后改为使用弹窗中选中的 agent ID

- **环境修复**：Shell 默认 JDK 24 与项目 Lombok 不兼容导致编译失败（TypeTag :: UNKNOWN），切回 JDK 17 后正常；`helloai-common` 模块未 mvn install 导致 IDE 报"程序包 com.helloai.common.base 不存在"

#### 3. 影响

- 对外行为变化：积分流水页正确展示 reward_log 数据；Agent ID 不再需要手工输入；认领子任务不再硬编码 agentId=1
- 代码变化：
  - 后端 2 文件：RewardService.java（+listAllLogs）、ScoreController.java（+/logs 端点）
  - 前端 4 文件：AgentSelect.vue（新建）、reward.ts（+logs API）、RewardList.vue（切端点+分页+AgentSelect）、SubTaskList.vue（弹窗+AgentSelect+Bug 修复）
- 数据结构变化：无
- 差距项变化：无（本轮为 UX 收口与 Bug 修复，不涉及核心差距项）

#### 4. 遗留

- 认领子任务后续应按流程中注册的有效角色 agent 进行筛选，甚至降级到 LLM 模型自创建的 agent（当前仅全量列出所有 Agent）
- AgentSelect 组件当前使用 `GET /agents`（全量），后续数据量增大时可考虑接入管理端分页接口 `GET /admin/agents`

---

### AgentHub V1 轮后反馈修复——credential_vault / redispatch / duty_lease / Redis 锁 / Poller 兜底验证

#### 1. 范围

针对 AgentHub V1 四轮迭代后由用户反馈暴露出来的 5 个隐患进行收口修复：

- credential_vault 轮换被唯一索引直接卡死
- `redispatchAssignedTimeout` 可能把任务重分回原 Agent 造成原地打转
- agent_duty_lease 缺少 DB 层的“同一 Agent 同时只能有一条 ACTIVE lease”约束
- `AssignedSubTaskTimeoutTask` Redis 锁释放不安全（固定 value + 简单 delete）
- `verify-poller-e2e.ps1` 未覆盖“主消费路径不可达”的 Poller 兑底验证

#### 2. 实际落地

- **credential_vault 唯一索引**（`V1__init_all.sql`）
  - 原索引 `uk_credential_vault_owner_provider_type` 不区分状态，`rotateAgentApiKey()` 会在第一次轮换命中 `EXISTING → EXPIRED + INSERT ACTIVE` 的唯一约束冲突。
  - 改为部分唯一索引 `uk_credential_vault_owner_provider_type_active`，`WHERE status = 'ACTIVE' AND deleted = 0`，允许同一 (owner_type, owner_id, provider, credential_type) 多条历史状态共存。

- **`redispatchAssignedTimeout` 排除原 Agent**（`SubTaskDispatchService`）
  - 原实现 `agentSelector.pickPreferred(role)` 不带排除参数，原 Agent 静默丢弃但仍在线且分数最高时会造成原地打转。
  - 改为 `agentSelector.pickAlternative(originalAgentId, role)`，与"同角色排除指定 Agent"的选人逻辑复用。
  - `SubTaskDispatchServiceTest` 新增两个用例：`shouldExcludeOriginalAgentWhenRedispatchingAssignedTimeout`、`shouldNotCallDispatcherWhenNoAlternativeAvailable`。

- **agent_duty_lease 库级约束**（`V1__init_all.sql`）
  - service 层“先关旧 lease 再开新 lease”能被并发 `checkIn` 击穿。
  - 补部分唯一索引 `uk_duty_lease_agent_active` (`agent_id` WHERE `status='ACTIVE' AND deleted=0`)，并加 FK `fk_duty_lease_agent` 引用 `agent(id)`。

- **Redis 锁安全释放**（`AssignedSubTaskTimeoutTask`）
  - 原实现固定 value `"1"` + 简单 `delete`；单轮扫描 >60s 后锁过期会被其他实例重抢，原实例 finally 会误删别人的锁。
  - `scan()` 生成 UUID 作为 token，`tryLock(token)` 使用 `SET NX EX` 带 TTL，`unlock(token)` 走 Lua：`if get == ARGV[1] then del`，保证只有自己 token 能解锁。
  - `AssignedSubTaskTimeoutTaskTest` 新增 `shouldUseLuaUnlockScriptWithMatchingToken`，验证 Lua 脚本作为参数被传入且带正确 token。

- **Poller 兑底验证**（`verify-poller-e2e.ps1` → v3.1）
  - 脚本无法重启 Spring Boot 关闭 MQ/Event 消费者，采用轻量等价：直接 `INSERT INTO agent_execution_record`，绕过 `ExecutionCommandService.publish()`。这种记录不会进入 MQ (`agent_command_outbox` 也不会写)，也不会发布本地 Spring 事件，Poller 是唯一可能处理者。
  - 新增 S5 场景，四个断言：
    - (a) `last_attempt_at IS NOT NULL`：`markPolled` 被调用（仅 Poller 调用）
    - (b) timeline 含 `sub_task_execution_command_poll_recovery`：仅 Poller 写
    - (c) `sub_task_execution_command_consume` 事件的 `payload.trigger` 以 `poll-recovery:` 开头：Poller 会重写 trigger 前缀，主消费者不会
    - (d) 反证：不存在 `trigger` 不以 `poll-recovery:` 开头的 `consume` 事件（出现即证明主消费者也参与了处理）
  - 额外：S5 入口先 `UPDATE sub_task SET status='PENDING'`，避开 S1/S4 后 sub_task 终态导致 `startIfNeeded` 拒绝、只写 `consume_skipped` 不带 trigger 的假阴性。
  - S6（手动场景）说明加入头部注释：需重启 Spring Boot 时设 `helloai.mq.execution-command.consumer-enabled=false`、单独跑 S1-S4，不能从脚本中自动运行。

#### 3. 影响

- 对外行为变化：调用 `rotateAgentApiKey()` 可正常轮换；ASSIGNED 超时回收不再原地打转；并发 `checkIn` 会被 DB 层拒绝重复 ACTIVE；Redis 锁释放不再误伤他人；Poller 兑底验证脚本可以证明主消费路径隔离下 Poller 仍能兑底。
- 代码变化：
  - `helloai-start/src/main/resources/db/migration/V1__init_all.sql`：1 个索引重定义 + 1 个索引新增 + 1 个 FK 约束
  - `helloai-core/src/main/java/com/helloai/core/service/SubTaskDispatchService.java`：`redispatchAssignedTimeout` 改调 `pickAlternative`
  - `helloai-core/src/test/java/com/helloai/core/service/SubTaskDispatchServiceTest.java`：2 个测试新增
  - `helloai-job/src/main/java/com/helloai/job/task/AssignedSubTaskTimeoutTask.java`：UUID token + Lua 解锁
  - `helloai-job/src/test/java/com/helloai/job/task/AssignedSubTaskTimeoutTaskTest.java`：1 个测试新增
  - `verify-poller-e2e.ps1`：v3 → v3.1，1 个场景（S5）新增，头部注释扩充
- 数据结构变化：`credential_vault` 与 `agent_duty_lease` 表的索引 / FK 约束变化（需 Flyway 重跑 V1 环境需重置或手动修复索引名）；其他无。

#### 4. 遗留

- 如果未来需要同一 owner 多条 ACTIVE credential（例如主/备密钥同时生效），当前部分唯一索引会拒绝这种情况，后续要重新调整索引条件。
- `agent_duty_lease` FK 加上后，`agent` 表中删除 Agent 会联动拦截，未在 `AgentService` 里预检；后续如需支持硬删除 Agent，需先处理其 duty_lease。
- Poller E2E v3.1 的 S5 依赖 sub_task 被重置为 PENDING 后被重新推进；若后续 `startIfNeeded` 增强、限制某些来源不允重启，本场景需要重写。
- S6（manual MQ-isolation）未实现为脚本可执行步骤，依赖人工手动重启验证，未保留 CI 路径。

---

### 2026-07-16 AgentHub V1 P0 真实环境 e2e 落地 + skill 规则 6 同步

#### 1. 范围

- T4.1 调度策略 §4.10 “值班优先” 收口（AgentSelector 增加 `dutyRank` 排序）
- AgentHub V1 P0 三件：checkIn / checkOut / DutyLeaseExpirationTask 真实环境 E2E
- skill 规则 6 “脚本必须显式声明 UTF-8 编码” 同步到 5 份 SKILL.md + AGENTS.md

#### 2. 实际落地

- **T4.1 §4.10 值班优先收口（方案 A）**
  - `AgentSelector` 注入 `AgentDutyLeaseService`，在多候选 comparator 排序时调用 `agentDutyLeaseService.isOnDuty(agentId)` 优先选择值班中的 Agent。
  - 单候选用例（如 `shouldSkipSleeping` / `shouldReturnNullWhenNoCandidates` 等）不走 comparator，`setUp` 里 `when(...isOnDuty...).thenReturn(false)` 是防御式默认 stub，但 Mockito STRICT_STUBS 检测不到调用会报 `UnnecessaryStubbing`。
  - `AgentSelectorTest.setUp` 改为 `lenient().when(...)` 避开误报，9 个测试零无关逻辑变化。

- **AgentHub V1 P0-A：checkIn / checkOut**
  - `agent_duty_lease` 表（`V1__init_all.sql` 第 1508 行随初始化建表，AgentHub V1 T3；**注：早期本记录误写 Flyway V18，V18 实为 `event_consumption_log`**）：`status ∈ {ACTIVE / CLOSED / EXPIRED}`，部分唯一索引 `uk_duty_lease_agent_active` (`agent_id` WHERE `status='ACTIVE' AND deleted=0`) 阻止同一 Agent 多条 ACTIVE 行。
  - `AgentDutyLeaseService.checkIn(agentId, workMode, maxConcurrent, ttlMinutes)`：开启 ACTIVE 租约，`expires_at = now + ttlMinutes`，同时调用 `heartbeatService.seen(agentId)` 联动在线态；`ttlMinutes` 为 null 或 ≤0 默认 30。
  - `AgentDutyLeaseService.closeLease(agentId, closeReason)`：将 ACTIVE 翻为 CLOSED，`closeReason` 为 null 时默认 `"manual_close"`。
  - `McpMcpServer.checkIn` / `checkOut` 两个 `@Tool`：参数 `agentId / workMode / maxConcurrent / ttlMinutes / sessionId / _sessionId`，`requireAuthId(sessionId, _sessionId)` 鉴权后覆盖客户端传的 agentId。
  - `checkOut` 参数名修复：服务端 `@ToolParam reason` 改为 `closeReason`（主字段名），保留 `reason` 作为 alias（兼容旧客户端）。

- **AgentHub V1 P0-C：DutyLeaseExpirationTask**
  - `helloai-job` 新增 `@Scheduled fixedRate=30_000` + Redis Lua 锁。
  - 扫描 `agent_duty_lease` 中 `status='ACTIVE' AND expires_at < now()` 的行，翻为 `status='EXPIRED'`, `close_reason='lease_expired'`。

- **新增 NOT NULL 字段填写修复（N11 遗留）**
  - `Agent.consecutiveFailureCount` 字段在 entity 里有，但 `MyBatisPlusMetaObjectHandler.insertFill` 没填默认值（业务逻辑不填 → `AgentService.register()` INSERT 撞 NOT NULL 约束 → 500 `DataIntegrityViolationException`）。
  - `MyBatisPlusMetaObjectHandler.insertFill` 补 `setFieldValByName("consecutiveFailureCount", 0, metaObject)`，覆盖所有 INSERT Agent 路径。

- **E2E 脚本：`verify-agenthub-duty-e2e.ps1`（新增）**
  - S1：MCP-over-SSE `tools/call checkIn` (workMode=NORMAL, maxConcurrent=3, ttlMinutes=5) → docker exec psql 断言 `status='ACTIVE' / work_mode='NORMAL' / max_concurrent='3' / expires_at > now()`。
  - S2：MCP-over-SSE `tools/call checkOut` (closeReason='e2e_test_close') → docker exec psql 断言 `status='CLOSED' / close_reason='e2e_test_close'`。
  - S3：手工 INSERT 一条 `expires_at=now-1min` 的 ACTIVE 租约，等 35s，DutyLeaseExpirationTask 巡检翻为 `status='EXPIRED' / close_reason='lease_expired'`。
  - `-Cleanup` 开关删 lease/inbox，幂等回归。
  - 复用 `verify-mcp-e2e.ps1` 的 MCP SSE 长连接样板 + `verify-outbox-relay-confirm-e2e.ps1` 的 `Run-Psql / Get-PsqlFields` 样板。
  - 最终 ALL PASSED 顺序：**S1 OK → S2 OK → S3 OK → ALL PASSED**（实测 2026-07-16 11:34 通过）。

- **skill 规则 6 “脚本必须显式声明 UTF-8 编码” 同步**
  - 5 份 `helloai-preflight/SKILL.md`（`.agents` 母版 + `.qoder/.trae/.cursor/.claude` 4 镜像）+ `AGENTS.md` 同步新增以下子项：
    1. **运行时输出编码**：`[Console]::OutputEncoding = [System.Text.Encoding]::UTF8` + `$OutputEncoding = [System.Text.Encoding]::UTF8`，Linux shell 用 `export LANG=zh_CN.UTF-8` + `export LC_ALL=zh_CN.UTF-8`。
    2. **源文件 BOM**：PS 5.1 中文 Windows 默认按 GBK 解析源码，UTF-8 no-BOM 会导致中文字符串解析错；脚本文件应保存为 UTF-8 with BOM（前 3 字节 `EF BB BF`）；同时交付前用 `Parser.ParseFile` 做静态语法自检。
    3. **管道原始字节传输**：PS 5.1 字符串通过管道喂给 docker/ssh/mysql 时以 UTF-16 LE+BOM 写 stdin，会被外部命令识别不了；要么 `cmd /c type <file> | <external>` 透传字节，要么用 `[Diagnostics.Process]` + `StandardInputEncoding=UTF8` + `BaseStream.Write()`。
    4. **here-string 串入 U+FEFF 隐限**：UTF-8 with BOM 的 .ps1 文件被 PS 5.1 解析时，here-string `@"..."@` 内容首字符是源文件 BOM；helper 入口必须 `$input = $input.TrimStart([char]0xFEFF)`。
  - 同步状态：5 份 SKILL.md 均一致更新。

- **e2e 脚本踩到的真实坑位（沉淀进 skill）**
  - **脚本源文件必须 UTF-8 with BOM**：早期版本用 `WriteAllText(..., UTF8NoBom)` 写脚本，PS 5.1 按 GBK 解析中文报错 `字符串缺少终止符: "`；修复用 `New-Object System.Text.UTF8Encoding($true)` 重写脚本加 BOM。
  - **`Get-Content -Raw` 默认 ANSI 解码**：从 utf-8 临时文件读 SQL 时塞进 U+FEFF；最终改用 `Process API` 完全控制 stdin 字节流。
  - **PS 5.1 管道 UTF-16 LE**：字符串 `| docker` 时被包装成 UTF-16 LE+BOM，psql 收到乱码字节；改用 `.NET Process` API + `BaseStream.Write()` 写字节。
  - **here-string 污染**：脚本本身是 UTF-8 BOM 后，`$Sql` 变量首字符是 U+FEFF；`Run-Psql` 入口 `TrimStart([char]0xFEFF)` 剥掉。

#### 3. 影响

- 对外行为变化：Agent 现可通过 MCP SSE `checkIn` 主动声明值班，调度器在多候选用 `pickAlternative` 时优先选值班中的 Agent；过期的 ACTIVE 租约会自动翻为 EXPIRED。
- 代码变化：
  - `helloai-core/.../mcp/McpMcpServer.java`：新增 `checkIn` / `checkOut` 两个 `@Tool`；`checkOut` 主字段名 `closeReason` 兼容 `reason`。
  - `helloai-core/.../service/AgentDutyLeaseService.java`：新增 `checkIn / closeLease / isOnDuty`。
  - `helloai-core/.../agent/executor/AgentSelector.java`：增加 `dutyRank` 排序。
  - `helloai-core/.../entity/Agent.java` + `MyBatisPlusMetaObjectHandler.insertFill`：补 `consecutiveFailureCount` 默认填充。
  - `helloai-job/.../task/DutyLeaseExpirationTask.java`：新增 `@Scheduled` 巡检。
  - `verify-agenthub-duty-e2e.ps1`：新增 S1/S2/S3 三场景脚本。
  - 5 份 SKILL.md + AGENTS.md 同步规则 6 四子项。
- 数据结构变化：`agent_duty_lease` 表已在 `V1__init_all.sql`（第 1508 行）随初始化建表，**非本轮新增**（早期本记录误写 Flyway V18，V18 实为 `event_consumption_log`）；本轮实际新增的 schema 变更仅 `agent_mcp_server` 表 `checkIn/checkOut` 默认 seed（Flyway V21 `V21__seed_agent_mcp_server_duty_tools.sql`）。

#### 4. 遗留

- `b7-a mvn -q -DskipTests package` 全项目冒烟：通过 Node fallback shell 调起的 `mvn` launcher 在 OpenJDK 17.0.18+8 + Windows 11 环境下崩溃（`EXCEPTION_ACCESS_VIOLATION` 在 `jvm.dll+0x2cf4ce`，elapsed time 0.023s，11 个 hs_err_pid*.log 同一症状），与本轮代码无关。用户后续在 IDEA 内 Rebuild + Maven clean + package 验证均通过，等价于 b7-a 验证。后续 `mvn` 命令应直接从 IDEA Run/Debug 或原生 `cmd /c mvn ...` 调用，避免 Node fallback shell。
- AgentHub P0 未做的项目：dashboard / 值班报表、`workMode=STRICT` 下的独占报锁语义、动态 TTL 自适应、多 Agent 同时值班的 concurrency 预扣语义；后续 AgentHub V1 P1 启动时按优先级推。
- E2E 脚本依赖用户手动在 IDEA 启动后端 + docker compose 起 postgres/redis/rabbitmq；CI 路径未沉淀。

---

### 2026-07-16 A 档收尾：值班只读报表接口 + S6 重定义为启动守卫 + 文档失真修正

#### 1. 范围

- N12 P1 收尾：新增值班租约只读报表接口（分页列表 + 状态概览），作为后续 dashboard 数据源。
- N6 遗留 S6 收口：把"手动 MQ-isolation 重启验证"重定义为独立的启动期 fail-fast 守卫脚本。
- 文档失真修正：差距表 + 迭代记录中 `agent_duty_lease` 被误记为 Flyway V18 的两处（实为 `V1__init_all.sql` 建表，V18 是 `event_consumption_log`）。
- 明确不做：`AgentExecutionProperties.java` 注释（核查后无 T5 前旧语义残留，见下）、dashboard 前端、`workMode=STRICT` 独占报锁、concurrency 预扣。

#### 2. 实际落地

- **N12 P1：值班只读报表接口**
  - `AgentDutyLeaseService` 新增两个只读查询：`listLeases(agentId, status, pageNum, pageSize)`（`LambdaQueryWrapper` 条件过滤 + `orderByDesc(startedAt)` + MyBatis-Plus `page(...)` 分页）、`countByStatus()`（按 `AgentDutyLeaseStatus` 枚举逐状态 `count(...)`，`LinkedHashMap` 保序）。
  - 新增 DTO（`helloai-api/dto/duty/`）：`DutyLeaseResponse`（租约列表项，含 agentId/agentName/sessionId/workMode/maxConcurrent/status/startedAt/lastRenewedAt/expiresAt/closeReason）、`DutyOverviewResponse`（active/closed/expired/total 状态概览）。
  - 新增 `AgentDutyLeaseController`（`@RestController @RequestMapping("/api/admin/duty-leases") @RequiredArgsConstructor`，构造器注入 `AgentDutyLeaseService` + `AgentMapper`）：
    - `GET /api/admin/duty-leases`：`list(agentId, status, page=1, size=20)` → `R<PageResult<DutyLeaseResponse>>`，`@RequestParam` 显式 `value`+`defaultValue`；列表项 `agentName` 用局部 `nameCache`（`HashMap` + `computeIfAbsent`）避免逐行查 Agent 名的 N+1。
    - `GET /api/admin/duty-leases/overview` → `R<DutyOverviewResponse>`，从 `countByStatus()` 组装。
  - 遵循 CODE_STYLE：Controller 薄、返回 `R<T>`、查询返回 Response DTO、逻辑删除交 `@TableLogic` 自动过滤。

- **N6 遗留 S6：重定义为独立启动守卫脚本 `verify-execution-dispatch-guard.ps1`（新增）**
  - 背景：T5 引入 `ExecutionDispatchValidator` 后，旧 S6 组合（consumer-mode ∈ {POLLER,BOTH} + consumer-enabled=false）会在 `@PostConstruct` 阶段直接 `IllegalStateException` fail-fast，应用根本起不来——旧 S6 已不再是"能跑的验证"，而是"被启动期守卫拦截的非法部署形态"。它本质需要"重启 JVM + 观察启动成败"，与 `verify-poller-e2e.ps1` 的"运行期 Poller 兜底 E2E"不是一类验证，故单独成脚本、不再塞进 poller 脚本。
  - 三场景：G1（`consumer-enabled=false` → 期望 fail-fast，日志含 `consumer-mode=POLLER` + `consumer-enabled=true`）、G2（`producer-enabled=false` → 期望 fail-fast，日志含 `dispatch-mode=MQ` + `producer-enabled=true`）、G3（`dispatch-mode=NONE` + `consumer-mode=EVENT` → 期望启动成功 + `/api/health` 200，合法最简组合不依赖 MQ）。
  - 断言口径：`Verify-FailFast`（进程在超时内退出 + exitCode≠0 + 日志命中期望 ASCII 片段 + 6565 未 Listen）；`Verify-Healthy`（进程持续存活 + `/api/health` 200）。脚本跑完不自动重启正常实例，仅打印恢复提示。遵循 skill 规则 6 编码防护。
  - `verify-poller-e2e.ps1` 头注释 S6 段同步改写：从"手动 MQ-isolation"改为"已迁出，见 `verify-execution-dispatch-guard.ps1`"，并说明 T5 fail-fast 使旧组合作废。

- **文档失真修正（两处 V18→V1）**
  - 差距表 N6 处理建议：S6 从"手动 MQ-isolation 补充对照实验"改写为"独立启动期 fail-fast 守卫脚本"；N12 处理建议：标注值班只读报表接口已交付；§5 优先级第 3 条同步。
  - 迭代记录：2026-07-16 AgentHub 轮的两处 `agent_duty_lease（Flyway V18）` 修正为 `V1__init_all.sql 第 1508 行建表`，并注明 V18 实为 `event_consumption_log`、本轮实际新增 schema 仅 V21 `agent_mcp_server` duty tools seed。

#### 3. 影响

- 对外行为变化：新增 `GET /api/admin/duty-leases`（分页列表）+ `GET /api/admin/duty-leases/overview`（状态概览）两个只读管理端点。
- 代码变化：
  - `helloai-core/.../service/AgentDutyLeaseService.java`：新增 `listLeases` / `countByStatus` 两个只读方法（+ `LambdaQueryWrapper` / `IPage` / `Page` / `LinkedHashMap` / `Map` import）。
  - `helloai-api/.../controller/AgentDutyLeaseController.java`（新增）、`helloai-api/.../dto/duty/DutyLeaseResponse.java`（新增）、`helloai-api/.../dto/duty/DutyOverviewResponse.java`（新增）。
  - `verify-execution-dispatch-guard.ps1`（新增，S6 v1.0；交付后用户实测触发 PS 5.1 解析错误 `Unexpected token '}'`，定位为双引号字符串内含中文全角括号叠加隐藏 BOM 字节被解析器提前闭合，已全量重构为**单引号 + `+` 拼接、runtime 字面量纯 ASCII、头注释去中文**）、`verify-poller-e2e.ps1`（头注释 S6 段改写）。
  - skill 规则 6 补第 5 子项（双引号 CJK 提前闭合陷阱 + 单引号拼接修复范式）：5 份 `helloai-preflight` SKILL.md（`.agents` 母版 + `.qoder/.trae/.cursor/.claude` 4 镜像）+ `AGENTS.md`（Additional rules 补一条英文精简条目）同步；差距表 D8 补第 5 子项。注：`.agents/helloai-guidance.master.json` 生成器母版不在仓库内，AGENTS.md 本轮按其既有精简英文风格手工补条，未走"改母版→重生成"路径。
  - `doc/HelloAI_实现差距表.md`（N6/N12 处理建议 + §5 优先级）、`doc/HelloAI_迭代执行记录.md`（两处 V18→V1 失真修正 + 本轮记录）。
- 数据结构变化：无（值班报表复用既有 `agent_duty_lease` 表，纯只读查询）。
- 主动不改：`AgentExecutionProperties.java` —— 用户反馈"下面字段注释还写着 DB Poller 成为主消费路径"，Grep 全文核查后注释已全是 T5 新语义（"Poller 仅作兜底"/"MQ 主消费 + Poller 孤儿兜底"/"不再是主消费路径（T5 语义）"），无该陈旧残留，故本轮不动此文件；真正的歧义源是枚举值名 `POLLER` 本身与"MQ 主消费"语义不符，改名为破坏性变更，建议单独立项，本轮不做。

#### 4. 遗留

- 值班报表 Java 改动（Controller + 2 DTO + Service 只读方法）需在 IDEA 内 Rebuild 验证编译：Bash 工具经 Node fallback shell 调 `mvn` 会必现 JVM `EXCEPTION_ACCESS_VIOLATION` 崩溃，本轮已逐一静态核对依赖点（`PageResult.of` / `R` / `LambdaQueryWrapper` / `AgentMapper` 均为既有可用 API），编译验证转 IDEA。
- `verify-execution-dispatch-guard.ps1` 需在"后端可启动 + docker compose 起 postgres/redis/rabbitmq + jar 已构建"环境下实测 G1/G2/G3；本轮仅交付脚本，未跑真实三场景。
- dashboard 前端接入值班报表接口、`workMode=STRICT` 独占报锁语义、动态 TTL 自适应、多 Agent 同时值班的 concurrency 预扣语义仍为 AgentHub V1 P1 后续项。

---

### 2026-07-16 A 档收尾验证：值班报表编译确认 + S6 守卫脚本实测 12/12 PASS

#### 1. 范围

- 关闭上一轮（“A 档收尾”）两处遗留：值班报表 Java 编译验证、`verify-execution-dispatch-guard.ps1` 三场景实测。
- 明确不做：值班报表两个只读端点的运行时冒烟（`GET /api/admin/duty-leases` 与 `/overview`），按用户约定推迟到前后端联调时一并测；dashboard 前端、`workMode=STRICT` 独占报锁、concurrency 预扣不做。

#### 2. 实际落地

- **值班报表编译验证（上轮遗留①关闭）**
  - 用户在 IDEA Rebuild + Maven clean + package 通过；核实 `AgentDutyLeaseController.class` / `DutyLeaseResponse.class` / `DutyOverviewResponse.class`（helloai-api）+ `AgentDutyLeaseService.class`（helloai-core）均于 15:07 重新编译，`helloai-start-1.0.0-SNAPSHOT.jar`（约 60MB）同批产出。`mvn package` 成功即等价编译验证，无需 verify-*.ps1。

- **`verify-execution-dispatch-guard.ps1` 实测 + 三处修复（上轮遗留②关闭）**
  - 实测前脚本因运行环境暴露三个 bug，逐一修复：
    1. **java 解析健壮化**：裸 `java` 命中 Oracle javapath 存根（静默空转、无输出）、且用户机上 `ms-17.0.18` 这套 JDK 安装本身损坏（连 `java -version` 都直接 `EXCEPTION_ACCESS_VIOLATION @ jvm.dll+0x2cf4ce` 崩溃）。改 `Resolve-JavaExe` 为探测式：按 显式 `-JavaExe` → `JAVA_HOME` → `where.exe`（跳过 javapath/WindowsApps）→ `%USERPROFILE%\.jdks\*` 降序 逐个 `Probe-JavaVersion`（用 Start-Process 跑 `-version`），跳过静默/崩溃候选，选中首个能真正打印版本号者（实测自动选中健康的 `ms-17.0.19`）。新增 `-JavaExe` 手动覆盖参数。
    2. **退出码取空**：`Start-Process -PassThru -NoNewWindow` 起的进程退出后 `.ExitCode` 返回空（断言 `exit code non-zero (got )` 假失败）。修复：Start-Process 后立刻 `$null = $proc.Handle` 缓存句柄，保留 ExitCode。
    3. **`[string]` 参数类型约束强转**：`param([string]$JavaExe)` 使脚本作用域的 `$script:JavaExe` 被约束为 [string]，直接把 `Resolve-JavaExe` 返回的 hashtable 赋给它会被 `.ToString()` 成字符串 `"System.Collections.Hashtable"`，导致 FilePath 为空。修复：用独立无类型变量 `$javaInfo` 接 hashtable，只把 `.Exe` 字符串赋 `$script:JavaExe`。
  - 修复后实测三场景（真实 jar，docker postgres Up）：**G1（`consumer-enabled=false`）fail-fast + exit code 1 + 日志命中 `consumer-mode=POLLER`/`consumer-enabled=true` + 6565 未 Listen；G2（`producer-enabled=false`）fail-fast + exit code 1 + 日志命中 `dispatch-mode=MQ`/`producer-enabled=true` + 6565 未 Listen；G3（`dispatch-mode=NONE` + `consumer-mode=EVENT`）进程存活 + `/api/health` 200。PASS: 12 / FAIL: 0（2026-07-16 14:59 实测）。** 证明 T5 `ExecutionDispatchValidator` 启动期 fail-fast 守卫在真实环境按预期拦截非法组合、放行合法最简组合。

#### 3. 影响

- 对外行为变化：无（本轮为验证 + 脚本健壮化，无业务代码改动）。
- 代码变化：
  - `verify-execution-dispatch-guard.ps1`：`Resolve-JavaExe` 重写为探测式 + 新增 `Probe-JavaVersion` + 新增 `-JavaExe` 参数 + `Start-App` 加 `$null = $proc.Handle` + preflight 用独立变量 `$javaInfo` 避免类型强转 + exit code null 假阳性修复。
  - `doc/HelloAI_实现差距表.md`（N6 S6 补实测结论）、`doc/HelloAI_迭代执行记录.md`（本轮记录）。
- 数据结构变化：无。

#### 4. 遗留

- 值班报表两个只读端点（`GET /api/admin/duty-leases` 分页列表 + `/overview` 状态概览）的运行时冒烟未做，约定在 AgentHub V1 P1 dashboard 前后端联调时一并验证。
- `ms-17.0.18` 这套 JDK 安装已损坏（非项目问题），建议用户删除或重装；守卫脚本已能自动绕过、优先选健康 JDK。
- dashboard 前端接入、`workMode=STRICT` 独占报锁、动态 TTL 自适应、多 Agent 同时值班的 concurrency 预扣仍为 AgentHub V1 P1 后续项。
