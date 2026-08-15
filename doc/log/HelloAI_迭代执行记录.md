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

- 按 `doc/design/HelloAI_调度解耦重构分析.md` 推进执行链收口

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
- 将 `v3.0` 降级并重写为 `doc/design/HelloAI_架构设计参考.md`，只保留：
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
- 继续扩写 `doc/design/HelloAI_架构设计参考.md`：将 `OpenMOSS / AgentTeams-main / Vibe-Skills-main / HelloAi Agent 任务调度优先级机制设计文档 / trade-cloud` 的吸收边界、适用落点与开发顺序写清楚
- 更新《项目基线文档》：新增“已确认的参考吸收原则”与“已确认的后续开发方向”，明确哪些来源指导接入层、调度层、运行时层与可靠性层

#### 3. 验证

- 文档链路检查：核心文档已不再相互引用错误的 `v3.0` 路径
- 职责边界检查：设计理念、现实基线、差距判断、执行记录已重新分层
- 引用一致性检查：README / 基线 / 差距表 / 能力矩阵已切到新矩阵口径
- 参考来源边界检查：外部项目已按“接入层 / 调度层 / 运行时层 / 可靠性层”拆分，不再混成单一方案来源

#### 4. 影响

- 对外行为变化：无
- 文档变化：
  - 新增 `doc/design/HelloAI_架构设计参考.md`
  - 新增 `doc/HelloAI_多类型Agent接入与调度可靠性开发路线图_v2.4_archived.md`
  - 收口 `doc/HelloAI_项目基线文档.md`
  - 收口 `doc/HelloAI_实现差距表.md`
  - 回写 `doc/log/HelloAI_迭代执行记录.md`
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

- 新增 `doc/design/HelloAI_外部项目借鉴技术细节.md`：按 AgentTeams-main / Vibe-Skills-main / OpenMOSS / 优先级设计文档 / trade-cloud 五个维度，列出具体文件路径、代码模式与 HelloAI 落点映射，含借鉴优先级速查表
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

- `doc/archive/HelloAI_当前能力确认矩阵.md` 与《实现差距表》存在部分内容重叠，后续可考虑合并或明确差异边界
- README 项目结构图中不再列举已删除的历史文档

---

### 2026-07-13 多 Agent Skills / Rules 口径同步

#### 1. 范围

- 将多家 Agent 使用的本地 preflight skill / rule 统一到新的文档矩阵口径

#### 2. 实际落地

- 更新 `.agents/skills/helloai-preflight/SKILL.md`：
  - 必读文档从 5 份调整为 6 份
  - 移除已删除的 `HelloAI_Agent接入内容生成功能开发清单_v2.0.md`
  - 新增 `doc/design/HelloAI_调度解耦重构分析.md` 与 `doc/design/HelloAI_架构设计参考.md`
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

- 新增 `doc/archive/HelloAI_agenthub.md`，作为 AgentHub 方向的主方案文档，明确：
  - 本文档用于描述外部 Agent 接入层增强方案，而非当前实现事实
  - 方案分为三阶段：
    - V1 最小版：`agent_duty_lease` + `checkIn/checkOut` + 值班优先分配 + 看板展示
    - V2 增强版：Bridge 守护进程桥接当前 `/mcp/sse` 主通道
    - V3 产品版：门铃通知通道 + 一键安装，通知层只负责唤醒
  - 当前主线约束：
    - 不引入第二控制面
    - 不改变 `MCP-over-SSE` 为主协议的定位
    - 不新增与 `online_status` 平行竞争的 Agent 主状态枚举
- 将 `doc/archive/helloai_agenthub_complete.md` 降级为历史草案，并补充顶部归档说明，明确：
  - 旧文档保留原始设想与灵感
  - 其中关于 `AgentStatus` 扩展、WebSocket 主通道、ShiftManager 的方案不再直接作为开发主参考
  - 后续统一以 `doc/archive/HelloAI_agenthub.md` 为主
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
  - 新增 `doc/archive/HelloAI_agenthub.md`
  - 修改 `doc/archive/helloai_agenthub_complete.md`
  - 回写 `doc/log/HelloAI_迭代执行记录.md`
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
- 遵循 `doc/design/HelloAI_调度解耦重构分析.md` 的“调度只发命令、执行独立消费、结果异步回写”哲学，新建 `MqExecutionCommandConsumer` 骨架
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
- 遵循 `doc/design/HelloAI_调度解耦重构分析.md` "调度只发命令、执行独立消费"目标态：为生产端 / 调度侧引入与 `consumer-mode` **语义对称**的 `dispatch-mode`（`NONE / EVENT / MQ / BOTH`），把生产端行为从消费侧配置上摧开
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
  - `doc/HelloAI_实现差距表.md`（N6/N12 处理建议 + §5 优先级）、`doc/log/HelloAI_迭代执行记录.md`（两处 V18→V1 失真修正 + 本轮记录）。
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
  - `doc/HelloAI_实现差距表.md`（N6 S6 补实测结论）、`doc/log/HelloAI_迭代执行记录.md`（本轮记录）。
- 数据结构变化：无。

#### 4. 遗留

- 值班报表两个只读端点（`GET /api/admin/duty-leases` 分页列表 + `/overview` 状态概览）的运行时冒烟未做，约定在 AgentHub V1 P1 dashboard 前后端联调时一并验证。
- `ms-17.0.18` 这套 JDK 安装已损坏（非项目问题），建议用户删除或重装；守卫脚本已能自动绕过、优先选健康 JDK。
- dashboard 前端接入、`workMode=STRICT` 独占报锁、动态 TTL 自适应、多 Agent 同时值班的 concurrency 预扣仍为 AgentHub V1 P1 后续项。

---

### 2026-07-16 B 档收尾验证：Poller 兜底 E2E 实测 15/15 PASS

#### 1. 范围

- 关闭 N6 运行态兜底验证遗留：在真实运行环境下重跑 `scripts/powershell/verify-poller-e2e.ps1`，确认 S1-S5 全部可重复通过。
- 本轮只收口验证脚本与文档，不改业务链路语义；明确不做：新增消费模式、调整 `ExecutionCompensationTask` 周期、改 `startIfNeeded` 契约、扩展到前端/dashboard。

#### 2. 实际落地

- **`scripts/powershell/verify-poller-e2e.ps1` 健壮化与口径收口**
  - pre-flight 健康检查由单次 `Invoke-WebRequest` 改为 30 秒窗口内重试，并在失败时额外输出 `listening=` 与 `lastErr=`，区分“服务未启动”与“服务已起但 health 不通”。
  - mock execution hard gate 前移到 sample prepare 之前；若 `GET /api/health/execution-mode` 返回 `mockMode=false` 且 provider 不是 `mock`，脚本直接 fail-fast，避免失败时先污染 e2e 样本数据。
  - 新增 `-AllowRealExecution` 开关；默认仍坚持 fail-fast，只有显式允许时才在真实 LLM 环境继续执行。

- **S2 / S4 / S5 样本构造修正，统一对齐 T5 `startIfNeeded` 契约**
  - 首轮实测暴露出脚本-行为漂移：S2/S4/S5 若把样本 `sub_task` 建成或重置为 `PENDING`，当前 T5 的 `startIfNeeded` 会拒绝推进，只留下 `consume_skipped` 或被 30s `ExecutionCompensationTask` 抢先标 `TIMEOUT`，无法证明 Poller 驱动的真实 consume-path。
  - 修正后：
    - S2 改为独立 `ASSIGNED` sub_task（不复用主样本）；
    - S4 三个额外 sub_task 全改为独立 `ASSIGNED`；
    - S5 改为独立 `ASSIGNED` sub_task，不再 reset 共享样本回 `PENDING`。
  - 这样 Poller 推出的 `consume -> startIfNeeded -> executeOnce` 路径与当前代码事实一致，不再依赖旧 era 的 `PENDING` 语义。

- **S4 orphan age 窗口修正，避开补偿任务抢占**
  - 首轮 runTag=`20260716-174205`：S4 三条记录使用 `create_time = now() - 300s`，在 5 秒等待窗口内被 `ExecutionCompensationTask` 抢先标 `TIMEOUT`，表现为 `total=3 / polled=0 / progressed=3 / distinct_sub_tasks=0`，属于“超时补偿推进”，不是 Poller 兜底证据。
  - 修正为 `create_time = now() - 240s`：仍大于 `poller-orphan-threshold-seconds=60`，足以被 `listOrphanPending` 扫到；同时小于 `pendingTimeoutMinutes=5` 的 300s 阈值，避免被 30s timeout compensation 抢先接管。

- **最终实测结果（真实运行环境）**
  - `scripts/powershell/verify-poller-e2e.ps1`
  - runTag=`20260716-174605`
  - **PASS: 15 / FAIL: 0**
  - 分场景：
    - S1：孤儿 `PENDING` 行被 Poller 扫到，`last_attempt_at` 刷新，timeline 落 `sub_task_execution_command_poll_recovery`
    - S2：5 条同 sub_task `PENDING` 记录中仅 1 条推进出 `PENDING`，验证 CAS `markRunning` 去重
    - S3：`IN_PROGRESS` 子任务可接受晚到 `submitResult`
    - S4：`polled=3 / progressed=3 / distinct_sub_tasks=3`，证明 3 条孤儿记录都由 Poller 兜底接住并推进
    - S5：`last_attempt_at`、`sub_task_execution_command_poll_recovery`、`poll-recovery:` trigger、`rogue_consume_events=0` 四项证据链均成立，证明主消费路径不可达的轻量等价场景下，处理痕迹全部来自 Poller

#### 3. 影响

- 对外行为变化：无（本轮仅为验证脚本收口与文档回写）。
- 代码变化：
  - `scripts/powershell/verify-poller-e2e.ps1`
    - 新增 pre-flight health retry / `listening=` 诊断
    - mock gate 前移 + `provider` 判定 + `-AllowRealExecution`
    - S2/S4/S5 独立 `ASSIGNED` sub_task 样本隔离
    - S4 orphan age 从 300s 调整到 240s，避免 timeout compensation 抢占
    - 若干 psql 输出解析与断言正则增强（避免表头/页脚干扰）
- 文档变化：
  - `doc/HelloAI_实现差距表.md`：N6 补最新 Poller E2E 15/15 与 S6 守卫 12/12 证据
  - `doc/log/HelloAI_迭代执行记录.md`：补本轮收尾记录

#### 4. 结论与遗留

- 结论：N6 当前已同时具备
  - **启动期守卫证据**：`scripts/powershell/verify-execution-dispatch-guard.ps1` → PASS 12 / FAIL 0
  - **运行态兜底证据**：`scripts/powershell/verify-poller-e2e.ps1` → PASS 15 / FAIL 0
  - 可视为 “T5 Poller 兜底 + Validator 启动期 fail-fast” 验证闭环完成。
- 遗留：
  - 控制台 CJK 显示在 PowerShell 5.1 下仍会有乱码，但不影响脚本断言与 `.out` 文件内容；如后续需要，可单独做控制台输出 ASCII 化收口。
  - `helloai-api/src/main/java/com/helloai/api/controller/HealthController.java` 与 `helloai-start/src/main/resources/application.yml` 的当前工作区修改未纳入本轮验证收口提交，按用户后续独立决策处理。
  
  ---
  
  ### 2026-07-16 A 档收尾：R2 Publisher 旧方法清理 + R3 V19 era SENT/CONFIRMED backfill + AgentHub V1 P1 dashboard 前端接入
  
  #### 1. 范围
  
  - 关闭 P1 实现差距表遗留中“可立刻动手”的三件事：**R2 旧 Publisher 方法清理、R3 V19 era SENT/CONFIRMED 行时间戳 backfill、AgentHub V1 P1 dashboard 前端接入**。
  - 本轮不涉及 `workMode=STRICT` 独占报锁语义、多 Agent 同时值班的 concurrency 预扣、动态 TTL 自适应、N2/N8 独立迭代。
  
  #### 2. 实际落地
  
  - **R2：清理 `ExecutionCommandMqPublisher.publish(ExecutionCommand)` 旧方法**
    - 旧入口仅做“事务活跃时注册 `afterCommit` 回调、无事务立即发”，②a 引入 Outbox 后该入口已无调用方，唯一生产路径是 `OutboxRelayTask` → `publishWithCorrelation`，旧方法保留只会形成第二套时序假设。
    - 删除 `publish(ExecutionCommand)` 方法、清空 `TransactionSynchronization*` 两个 import；类级 javadoc “Phase 2F 关键修正一”段落改为“②b 收尾：AFTER_COMMIT 语义已移除”，列表项调用方由 `ExecutionCommandService` 改为 `OutboxRelayTask`。
    - 单测同步：删除整个 `ActiveTransactionContext` 嵌套类（AFTER_COMMIT 用例 2 个 + `@AfterEach` 同步清理 1 个）；`NoTransactionContext` 两个用例改为 `publishWithCorrelation`，新增 1 个用例验证 `correlationKey` 与 `eventId` 不一致时 `MessageProperties` 仍以 `eventId` 为准、返回的 `CorrelationData` 携带 outbox 主键（覆盖 ②b Confirm 回写场景）。
    - 语义自检：全工程 0 处调用旧 `publish(ExecutionCommand)`，0 处 import 残留。
  
  - **R3：V22 `agent_command_outbox_backfill_timestamps` 回填历史 SENT/CONFIRMED 行**
    - V19 表只有 `update_time`（BEFORE UPDATE 触发器维护），V20 才加 `last_sent_at`/`confirmed_at` 两列但未 backfill；V21 已被 `seed_agent_mcp_server_duty_tools` 占用，本轮使用 **V22**。
    - 回填策略（保守近似，全部 WHERE IS NULL 守卫，重跑安全）：
      - `status=1 AND deleted=0 AND last_sent_at IS NULL` → `last_sent_at = update_time`（OutboxRelayTask markSent 唯一动作即同步 `last_sent_at` 与 `update_time`，二者近似相等）
      - `status=3 AND deleted=0 AND confirmed_at IS NULL` → `confirmed_at = update_time`
      - `status=2` FAILED 不回填：语义可能是 publish 阶段失败（不该置值）或 broker NACK，历史不一致，保持 NULL
      - `status=0` PENDING 不动：语义上未发生
    - 幂等：所有 UPDATE 都有 IS NULL 守卫，可重复执行。
  
  - **AgentHub V1 P1 dashboard 前端接入**
    - 后端值班报表两个只读端点（`GET /api/admin/duty-leases` 分页 + `/overview` 概览）此前已具备，本轮补齐前端。
    - 新增 `helloai-ui/src/api/duty.ts`：`dutyApi.list({ agentId?, status?, page, size })` + `dutyApi.overview()`，对齐后端 `AgentDutyLeaseController` 与 `R<PageResult<DutyLeaseResponse>>` 解包。
    - 新增 `helloai-ui/src/types/duty.ts`：`DutyLeaseResponse` / `DutyOverviewResponse` / `DutyLeaseStatus` / `DUTY_LEASE_STATUS_MAP`（值班中/已签退/已过期），`PageResult<T>` 直接复用 `types/index.ts` 已有定义避免重复。
    - 新增 `helloai-ui/src/views/duty/DutyLeaseList.vue`：状态 + Agent ID 过滤、分页表（租约 ID / Agent 名+ID / 会话 / 模式 / 并发上限 / 状态 tag / 开始·续约·过期时间 / 关闭原因），`DUTY_LEASE_STATUS_MAP` 统一渲染。
    - `Dashboard.vue` 加 “Agent 值班概览”区块：4 个 stat 卡（值班中 / 已签退 / 已过期 / 租约总数）+ “查看全部租约 →” 链接，异步加载 `loadDutyOverview()` 失败仅 `console.warn`，不阻断 dashboard 主图。
    - 路由 `router/index.ts` 注册 `/duty-leases`，菜单 `MainLayout.vue` 增加 `Clock` 图标菜单项（同步 import 列表）。
  
  - **`scripts/powershell/verify-dashboard-duty-leases.ps1` 验证脚本**
    - 遵循 SKILL.md 规则 6：UTF-8 强制头（无 BOM）+ PS 5.1 单引号 + `+` 拼接、runtime 字面量纯 ASCII、CJK 仅出现在 `#` 注释与 `.out` 文件内容。
    - 覆盖 S1 overview 字段齐、S2 list 分页结构、S3 `status=ACTIVE` 过滤生效、S4 V22 backfill 抽查（`status=1` 行 `last_sent_at IS NULL` 数为 0 且 `status=3` 行 `confirmed_at IS NULL` 数为 0）。
    - 模板参照 `verify-agenthub-duty-e2e.ps1`：同一套 `Invoke-Json` / `Run-Psql` / `Get-PsqlFields` helper，pre-flight 同样要求 docker compose + IDEA 启动 + Flyway 已跑 V22。
  
  #### 3. 影响
  
  - 对外行为变化：无新增业务语义，仅删除一条已无调用方的旧方法、给历史数据补齐时间戳、新增一个前端页面与一个菜单项。
  - 代码变化：
    - `helloai-core/.../ExecutionCommandMqPublisher.java`：删除旧 `publish` 方法、清冗余 import、改类级 javadoc
    - `helloai-core/.../ExecutionCommandMqPublisherTest.java`：删除 AFTER_COMMIT 用例、改 `publish` → `publishWithCorrelation`、新增 correlationKey 用例
    - `helloai-start/.../db/migration/V22__agent_command_outbox_backfill_timestamps.sql`（新增）
    - `helloai-ui/src/api/duty.ts`（新增）
    - `helloai-ui/src/types/duty.ts`（新增）
    - `helloai-ui/src/views/duty/DutyLeaseList.vue`（新增）
    - `helloai-ui/src/views/Dashboard.vue`：新增 “Agent 值班概览”区块 + `loadDutyOverview()` 加载
    - `helloai-ui/src/router/index.ts`：注册 `/duty-leases`
    - `helloai-ui/src/layouts/MainLayout.vue`：新增菜单项 + `Clock` 图标 import
    - `scripts/powershell/verify-dashboard-duty-leases.ps1`（新增）
  - 数据库变化：V22 backfill 在 Flyway 启动时一次性执行，对 status IN (1,3) 且 IS NULL 的行做时间戳回填，无 schema 变化。
  
  #### 4. 遗留
  
  - AgentHub V1 P1 仍余：`workMode=STRICT` 独占报锁语义、多 Agent 同时值班的 concurrency 预扣语义、动态 TTL 自适应（按 N12 缺口继续）。
  - ~~b1 的 `mvn -pl helloai-core compile` / `test` 编译验证未在本轮执行（环境无 mvn）~~：**已实测通过**（2026-07-16 23:1x） → 详见 §5 验证回执。
  - ~~`verify-dashboard-duty-leases.ps1` 尚未真实环境实测~~：**S1-S4 已在真实环境实测全部 PASS**（2026-07-16 23:1x） → 详见 §5 验证回执。
  
  #### 5. 验证回执（2026-07-16 23:1x 实测）
  
  ##### 5.1 实证列
  
  | 项 | 实际状态 | 说明 |
  |---|---|---|
  | R2 `ExecutionCommandMqPublisher` 编译产物 | ✅ `target/classes/.../ExecutionCommandMqPublisher.class` 5358 bytes（23:06）| 用户本地 mvn rebuild + package 通过 |
  | R2 单测 JUnit Runner | ✅ 4/4 PASSED，`Process finished with exit code 0`（IDEA JUnit 23:18 实测）| `DirectPublish.publishWithCorrelationSendsImmediately` / `publishBodyIsRestorableJson` / `publishUsesCorrelationKeyOnlyOnReturnedCorrelationData` / `FailurePaths.publishThrowsWhenSerializationFails` 全过；FailurePaths 中出现的 `ERROR mq.execution-command.serialize.failed ... JsonMappingException: boom for eventId=evt-abc` 是用例 mock 故意触发的失败传播场景，非缺陷 |
  | 全工程残留旧 `publish(ExecutionCommand)` 调用点 | ✅ 0 处 | 全工程 grep 无命中 |
  | macOS zsh 等价脚本 | ✅ **新增** `scripts/shell/verify-dashboard-duty-leases.sh`（已 `chmod +x`、`zsh -n` 语法检查通过）| 依赖 jq + docker + curl + zsh（用户机器均具备），与 PS1 同源；pre-flight 同样 fail-fast |
  | verify 端到端实测（PS1） | ✅ **S1 overview / S2 list / S3 status=ACTIVE 过滤 / S4 V22 backfill 抽查** 全 PASSED | V22 因 fresh volume `agent_command_outbox` 无历史 SENT/CONFIRMED 行，S4 总数均为 0，符合“空表也 PASS”的幂等设计 |
  
  ##### 5.2 本轮首次 S1 overview 实测遇 HTTP 500 的根因澄清（非 Flyway 回归，不立项）
  
  - 现象：第一次跑 verify 脚本时 S1 overview 返回 HTTP 500 `{"code":500,"msg":"服务内部错误..."}`
  - 根因：**非 Flyway 回归**。用户在 Windows / macOS 之间手工把 V1~V22 多个 SQL 文件合并回 `V1__init_all.sql` 做集中初始化时，遗漏了其中某段（典型为某条 CREATE TABLE 或 seed INSERT），导致 `agent_duty_lease` 等派生表未随 V1 一同初始化。手动补跑一次合并后的 `init_all.sql` 后四步全过（实测时已排除）。
  - 决策：用户明确“新环境干净 Flyway 跑下来不会复现，问题可暂忽略”，本轮 **不立项 P-FIX**；新成员接入仍以官方 `docker compose up -d` + Flyway V1~V22 顺序跑为主路径。
  - 复现防护（非本次交付）：未来如再需手工合并迁移文件，建议增加一份“合并后 V1 ≡ 当前 baseline”的差异自检脚本（不在本轮范围内）。
  
  ---
  
### 2026-07-17 AgentHub V3 门铃通知通道：PR-1 内核 + PR-2 响铃接线（单测 17/17 全绿）

#### 1. 范围

- 按 `doc/archive/HelloAI_门铃通知通道设计.md` §10 的最小 PR 拆分，落地 **PR-1 门铃内核** + **PR-2 响铃接线**：补一条“服务端 → 外部 Agent 单向 SSE 门铃”，把外部 `CLI_CLIENT` 从任务发布到感知的 0~30s 轮询延迟降到秒级。
- 明确不做（本轮）：PR-3 值班/鉴权收口（`isOnDuty` 建连校验、`checkOut`/租约到期主动 disconnect）与端到端验证脚本；可选 PR-4 保活刷心跳；不引入 WebSocket/STOMP/Netty；不新增 Flyway/表/MQ 队列；不改 `AgentStatus`/`AgentOnlineStatus` 枚举；不做多实例 fanout（单实例进程内 Map）。

#### 2. 实际落地

- **PR-1 门铃内核（能连上 / 能收 `connected` 握手 / 断连能清理）**
  - `DoorbellProperties`（helloai-common `config`）：`@ConfigurationProperties(prefix="helloai.doorbell")`，`enabled=true` / `emitterTimeoutMs=1_800_000`（30min） / `keepaliveIntervalMs=15_000`，仿 `helloai.dispatch.*` 集中管理。
  - `DoorbellSignal`（helloai-core `doorbell`）：`@Getter @JsonInclude(NON_NULL)`，字段 `type/eventType/refType/refId/serverTime`，静态工厂 `connected()` / `keepalive()` / `inbox(eventType,refType,refId)`；信号极简，**不含 title/summary/正文**（正文由 Agent 随后 `pullTasks` 拉取，保证门铃丢失不丢信息）。
  - `DoorbellRegistry`（helloai-core `doorbell`）：仿 `McpAuthContext` 单例风格的进程内 `ConcurrentHashMap<Long,SseEmitter>`；`register`（同一 agentId 已有连接先关旧再建新、防泄漏）/ `unregister`（用 `remove(key,value)` 值条件删除，避免误删“关旧建新后的新连接”）/ `get` / `isConnected` / `size`。
  - `DoorbellService`（helloai-core `doorbell`）：`connect(agentId)` 先校 `enabled`，建 `SseEmitter`（超时取 `emitterTimeoutMs`）并挂 `onCompletion/onTimeout/onError` 回调均从 registry 注销，`register` 后立即 `doSend` 一条 `type=connected` 握手；`ring(agentId,signal)` 未连返回 false（尽力而为）；`disconnect(agentId)` / `connectionCount()`；私有 `doSend` 发送异常静默注销、不重试不抛错。
  - `AgentDoorbellController`（helloai-api）：`GET /api/agents/doorbell/sse`，`produces=MediaType.TEXT_EVENT_STREAM_VALUE`，入参 `@RequestAttribute("_authId") Long agentId`，直接返回 `doorbellService.connect(agentId)`；复用 `AuthInterceptor` 对 `/api/**` 的 Bearer apiKey 鉴权链，不新增 token 体系。
  - 单测：`DoorbellRegistryTest`（5 例：注册/查询/关旧建新/值条件注销/size）+ `DoorbellServiceTest`（6 例：disabled 拒连/connected 握手/ring 命中/ring 未连/disconnect/connectionCount）。

- **PR-2 响铃接线（发任务 → 门铃响 → 客户端被唤醒）**
  - `InboxMessageCreatedEvent`（helloai-core `event`）：`@Getter` 不可变事件，字段最小化 `agentId/eventId/eventType/refType/refId`（不携 title/summary）。
  - `AgentInboxService.send()` 一处收口发事件：注入 `ApplicationEventPublisher`；`save(inbox)` 成功后 `publishEvent(new InboxMessageCreatedEvent(...))`；`catch(DuplicateKeyException)`（`(event_id,agent_id)` 联合唯一约束→已投递）分支 `return` **不发事件**，避免重复投递重复响铃。因三条通知路径（`TaskController.create` 直发 / `SubTaskService` 状态流转五种 / MQ `NotificationConsumer`）全收口于 `send()`，此一处发事件即覆盖全部。
  - `DoorbellRinger`（helloai-core `doorbell`）：`@Async("doorbellExecutor") @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)` 监听 `InboxMessageCreatedEvent`，调 `doorbellService.ring(agentId, DoorbellSignal.inbox(...))`；`event==null || agentId==null` 直接 return，异常只 `log.debug` 不向上抛（靠轮询兜底）。选 AFTER_COMMIT 而非 `send()` 内直接响铃：保证“先落库、后响铃”，与项目既有 Outbox / 本地执行事件的 AFTER_COMMIT 时序哲学一致（架构参考 §5.1 Phase 2F），避免“响了铃但收件箱未提交、Agent pull 不到”。
  - `DoorbellExecutorConfig`（helloai-start `config`）：`@Bean("doorbellExecutor")` `ThreadPoolTaskExecutor` core=2 / max=4 / queue=500 / `ThreadPoolExecutor.DiscardPolicy`，与 `executionCommandExecutor` 池隔离，响铃拥塞时直接丢弃——门铃尽力而为，永不拖累主链路（`@EnableAsync` 已在 `HelloAIApplication`）。
  - 单测：`DoorbellRingerTest`（4 例：正常响铃/null 事件不响/ring 抛异常被吞/agentId 为空不响）+ `AgentInboxServiceTest`（2 例：`spy(new AgentInboxService(eventPublisher))` + `doReturn(true).when(service).save(any())` 验证发事件；`doThrow(new DuplicateKeyException("dup"))...` 验证 `never()` 发事件）。

#### 3. 影响

- 对外行为变化：新增一个只进不出的 SSE 端点 `GET /api/agents/doorbell/sse`（建连即回 `connected`）；收件箱首次落库后会向已连门铃的 Agent 推一条 `type=inbox` 信号。MCP 主线（`pullTasks/claimSubTask/submitResult`）完全不改。
- 代码变化（新增 8 / 修改 1）：
  - 新增 `helloai-common/.../config/DoorbellProperties.java`
  - 新增 `helloai-core/.../doorbell/DoorbellSignal.java` / `DoorbellRegistry.java` / `DoorbellService.java` / `DoorbellRinger.java`
  - 新增 `helloai-core/.../event/InboxMessageCreatedEvent.java`
  - 新增 `helloai-api/.../controller/AgentDoorbellController.java`
  - 新增 `helloai-start/.../config/DoorbellExecutorConfig.java`
  - 修改 `helloai-core/.../service/AgentInboxService.java`（注入 `ApplicationEventPublisher` + `save` 成功发事件 + `DuplicateKey` 分支 return 不发，+12/-1）
  - 新增测试：`DoorbellRegistryTest` / `DoorbellServiceTest` / `DoorbellRingerTest`（helloai-core `doorbell`）+ `AgentInboxServiceTest`（helloai-core `service`）
  - `doc/HelloAI_实现差距表.md`（新增 N13 + §2 结论 + §5 优先级）、`doc/log/HelloAI_迭代执行记录.md`（本轮记录）
- 数据结构变化：无（门铃是纯运行时连接态，不落库；`SseEmitter` 为 Spring WebMVC 原生，helloai-core 经 `spring-ai-starter-mcp-server-webmvc` 传递依赖 `spring-webmvc`，零新增依赖）。

#### 4. 遗留

- **PR-3 值班/鉴权收口（设计 §6.1/§10）**：建连前置 `AgentDutyLeaseService.isOnDuty(agentId)` 校验（未打卡/非 ACTIVE 拒连）；`checkOut` 或租约 EXPIRED 时主动 `DoorbellService.disconnect(agentId)`；补端到端验证脚本（建连→发任务→秒级收 `inbox`→pullTasks；以及“关闭 SSE 后再产生消息仍能轮询消费”证明门铃丢失不致命）。
- 运行时端到端冒烟（真实后端 + docker compose + 外部 Agent 模拟建连）本轮未做，随 PR-3 验证脚本一并补。
- 可选 PR-4：门铃保活/建连顺带刷 `HeartbeatService.seen(agentId)`（降低 Agent 额外 heartbeat 频率）；多实例实时性（Redis Pub/Sub fanout）为 §12 演进项。

#### 5. 验证回执

- `mvn -pl helloai-core -am test -Dtest=DoorbellRegistryTest,DoorbellServiceTest,DoorbellRingerTest,AgentInboxServiceTest` → **17 例全绿 BUILD SUCCESS**（Registry 5 + Service 6 + Ringer 4 + Inbox 2）。
- `mvn -pl helloai-start -am install "-Dmaven.test.skip=true"` → **全 reactor MAIN 编译 BUILD SUCCESS**。
- stale .m2 jar 排查：`mvn -pl helloai-start -am test-compile` 首次报 helloai-job 测试找不到 `AgentFallbackProperties`/`AgentCommandOutboxRelayProperties`（两类均在 helloai-common，本轮未触），定位为本地 `.m2` 陈旧 common jar；`mvn install` 刷新后 `mvn -pl helloai-job test-compile` BUILD SUCCESS，确认非代码回归。
- PowerShell 注意：不支持 `&&`/`cd /d`（改 `Set-Location ...; mvn ...`）；`-Dkey=value` 需加引号防参数被拆分；`-pl X -Dtest=...` 需 `-am` 重建上游（新增的 `DoorbellProperties` 在 common）。

---

### 2026-07-17 AgentHub V3 门铃通知通道：PR-3 值班鉴权收口 + 兜底验证脚本（单测 22/22 全绿）

#### 1. 范围

- 承接同日 PR-1（门铃内核）+ PR-2（响铃接线），落地设计 §6.1/§10 的 **PR-3 值班/鉴权收口 + 兜底验证**：建连前置 `isOnDuty` 闸门（未打卡拒连）、`checkOut`/租约到期时主动断门铃、补端到端验证脚本。
- 明确不做（本轮）：可选 PR-4 保活刷 `last_seen_at`；多实例 Redis Pub/Sub fanout（§12 演进项）；不新增 Flyway/表/MQ 队列；不引入 WebSocket。

#### 2. 实际落地

- **建连闸门（`DoorbellService.connect`）**：`DoorbellService` 构造注入 `AgentDutyLeaseService`，`connect(agentId)` 在 `enabled` 校验后前置 `isOnDuty(agentId)`——无 ACTIVE 值班租约即抛 `BizException(500)` 拒连（"先打卡再接电话"）。Controller/鉴权链不变，异常经 `GlobalExceptionHandler` 映射为 HTTP 500 + body `code=500`。
- **主动断连（事件解耦，规避构造循环）**：为避免 `DoorbellService ↔ AgentDutyLeaseService` 双向构造依赖，反向断连走本地领域事件——新增 `DutyLeaseClosedEvent`（helloai-core/event，携 `agentId`/`reason`）；`AgentDutyLeaseService` 加 `@RequiredArgsConstructor` + `ApplicationEventPublisher`，`closeLease`（覆盖 checkOut）与 `expireLeases`（覆盖租约到期）在关闭行数 >0 时 `publishEvent`，`startLease` 防御性关旧**不发**事件（避免刚 checkIn 就被断连）。新增 `DoorbellDutyListener`（`@Async("doorbellExecutor") @TransactionalEventListener(AFTER_COMMIT)`，与 `DoorbellRinger` 对称）监听后调 `doorbellService.disconnect(agentId)`，异常静默。
- **Bean 创建顺序无环**：`agentDutyLeaseService`（仅需 publisher）→ `doorbellService`（需 agentDutyLeaseService）→ `doorbellDutyListener` / `doorbellRinger`（需 doorbellService），全 reactor `install` BUILD SUCCESS 间接验证无编译/装配级循环。
- **端到端脚本 `scripts/powershell/verify-doorbell-e2e.ps1`**：S1 无 ACTIVE 租约建连 → HTTP 500 + `code=500`；S2 直接 INSERT 一条 ACTIVE 租约 → curl `-N` 建连读首帧 → 断言 `HTTP/1.1 200` + `event:connected` + `"type":"connected"`；S3 把租约 `expires_at` 改到过去 → 等 35s `DutyLeaseExpirationTask` 翻 EXPIRED → 事件驱动主动断连 → 断言 DB `status=EXPIRED,close_reason=lease_expired` 且 SSE 后台 job 结束（流被服务端 `complete` 关闭）。脚本遵循规则 6：UTF-8 with BOM、单引号 + `+` 拼接、runtime 字面量纯 ASCII、CJK 只留注释。

#### 3. 影响

- 新增 3 个文件：`DutyLeaseClosedEvent`、`DoorbellDutyListener`、`verify-doorbell-e2e.ps1`；新增单测 `DoorbellDutyListenerTest`。
- 改 3 个文件：`DoorbellService`（注入 + 闸门）、`AgentDutyLeaseService`（发事件）、`DoorbellServiceTest`（补未在岗拒连用例 + mock dutyLeaseService）。
- 行为变化：门铃建连从"仅校验 enabled"收紧为"enabled + isOnDuty"；离岗（checkOut / 到期）从"仅靠 SSE 超时自然回收"升级为"事件驱动秒级主动断连"。

#### 4. 遗留

- 运行时端到端冒烟**已实测通过**（见下方验证回执），本项遗留关闭。
- 可选 PR-4：门铃保活帧顺带刷 `HeartbeatService.seen(agentId)`（降低 Agent 额外 heartbeat 频率）；多实例 Redis Pub/Sub fanout。

#### 5. 验证回执

- `mvn -pl helloai-core -am test -Dtest=DoorbellRegistryTest,DoorbellServiceTest,DoorbellRingerTest,DoorbellDutyListenerTest,AgentInboxServiceTest` → **22 例全绿 BUILD SUCCESS**（Registry 5 + Service 7 + Ringer 4 + DutyListener 4 + Inbox 2）。
- `mvn -pl helloai-start -am install "-Dmaven.test.skip=true"` → **全 reactor MAIN 编译 BUILD SUCCESS**（含 helloai-start，间接验证无装配级循环依赖）。
- `verify-doorbell-e2e.ps1` 经 `[System.Management.Automation.Language.Parser]::ParseFile` 自检 → **PARSE-OK**；首次因 Write 落盘无 BOM 触发 PS 5.1 以 ANSI 码页误读 CJK 注释报解析错，改存 UTF-8 with BOM（`EF BB BF`）后通过（对齐 D8 规则 6 源文件 BOM 子项）。
- **2026-07-17 真实环境实跑 `verify-doorbell-e2e.ps1` → ALL PASSED**（用户自启后端 + docker，agentId 2077974111691915266）：`S1 OK` 未在岗建连 HTTP 500 `{"code":500,"msg":"Agent 未在岗…"}`；`S2 OK` 在岗建连 `HTTP/1.1 200` + `event:connected` + `data:{"type":"connected",...}`；`S3a OK` 租约翻 `EXPIRED | lease_expired`；`S3b OK` SSE 后台 job state=`Completed`（事件驱动主动断连生效）。至此 N13 运行时冒烟闭环。

---

### 2026-07-17 AgentHub V3 门铃通知通道：PR-4 保活帧调度 + 双心跳（方案 A，单测 33/33 全绿）

#### 1. 范围

- 承接同日 PR-1/2/3，落地设计 §6.2/§10.4 的 **PR-4**：① 门铃保活帧定时广播（周期性向已连门铃推 `keepalive`，防反向代理/NAT 空闲超时掐断长连接）；② 双心跳（建连时顺带刷一次 `HeartbeatService.seen`，让门铃建连也计入在线证据）。
- 明确不做（本轮）：多实例 Redis Pub/Sub fanout（§12 演进项，单实例进程内 Map 无需）；保活帧不刷 `last_seen_at`（仅 connect 刷，规避僵尸连接掩盖离线）；不引入 WebSocket；不新增 Flyway/表/MQ 队列。

#### 2. 实际落地

- **① 保活帧调度（本地无锁，每实例都跑）**：新增 `DoorbellKeepaliveTask`（helloai-core/doorbell，`@Component` + `@Scheduled(fixedRateString = "${helloai.doorbell.keepalive-interval-ms:15000}")`），由 helloai-start `@EnableScheduling` 驱动。**关键设计：与 `DutyLeaseExpirationTask` 的 Redis 选主锁相反——门铃保活绝不选主。** `SseEmitter` 是进程内连接态，某 Agent 的连接只落在持有它的那个实例，若选主只让一台跑会导致其它实例的连接被空闲超时掐断，因此每个实例必须保活自己 `DoorbellRegistry` 里的连接。任务体先判 `enabled` 与 `connectionCount()==0` 早退，再 `broadcastKeepalive()`，异常整体 `catch` 吞掉（靠客户端重连 + pullTasks 轮询兜底，永不打断调度线程）。
- **广播实现**：`DoorbellRegistry` 新增 `forEach(BiConsumer<Long,SseEmitter>)`（委托 `ConcurrentHashMap.forEach` 弱一致遍历，遍历中允许并发 register/unregister 不抛 CME）；`DoorbellService.broadcastKeepalive()` 遍历发 `DoorbellSignal.keepalive()`，复用既有 `doSend`（失败静默注销），返回成功条数。
- **② 双心跳（方案 A，默认关）**：`DoorbellProperties` 新增 `refreshHeartbeat`（默认 **false**，保守）；`DoorbellService` 注入 `HeartbeatService`，`connect(agentId)` 在回推 `connected` 握手后，若开关开则调私有 `refreshSeen(agentId)` → `heartbeatService.seen(agentId)`（刷 Redis TTL + `last_seen_at` + 三态重算），异常静默不阻断建连。**仅建连刷一次**（建连是客户端主动、最可信的存活证据），保活帧轮不刷——避免“僵尸连接”被持续判 ONLINE 掩盖真实离线。
- **无循环依赖**：`DoorbellService(core) → HeartbeatService(core) → AgentMapper/StringRedisTemplate` 不回指；Bean 创建无环（全 reactor `install` 间接验证）。

#### 3. 影响

- 新增 2 个文件：`DoorbellKeepaliveTask`、`DoorbellKeepaliveTaskTest`（4 用例：关闭跳过 / 无连接跳过 / 有连接广播一次 / 广播异常被吞）。
- 改 5 个文件：`DoorbellRegistry`（+`forEach`）、`DoorbellService`（+`broadcastKeepalive` + 注入 `HeartbeatService` + `connect` 条件 `refreshSeen`）、`DoorbellProperties`（+`refreshHeartbeat` 默认 false）、`DoorbellServiceTest`（构造改 4 参 + 补广播 2 例 + 双心跳 3 例）、`DoorbellRegistryTest`（+`forEach` 2 例）。
- 对外行为变化：门铃长连接每 15s（默认）收到一帧 `keepalive`；`refresh-heartbeat=true` 时建连会顺带刷一次 `last_seen_at`（默认关，不改变现有在线判定行为）。

#### 4. 遗留

- 多实例实时性（Redis Pub/Sub fanout）为 §12 演进项，单实例部署下无需，暂不做。

#### 5. 验证回执

- `mvn -pl helloai-core -am test -Dtest=DoorbellRegistryTest,DoorbellServiceTest,DoorbellRingerTest,DoorbellDutyListenerTest,DoorbellKeepaliveTaskTest,AgentInboxServiceTest "-Dsurefire.failIfNoSpecifiedTests=false"` → **33 例全绿 BUILD SUCCESS**（Registry 7 + Service 12 + Ringer 4 + DutyListener 4 + Keepalive 4 + Inbox 2）。
- `mvn -pl helloai-start -am install "-Dmaven.test.skip=true"` → **全 reactor MAIN 编译 BUILD SUCCESS**。
- PowerShell 注意：`DoorbellProperties.refreshHeartbeat` 在 helloai-common，跨模块新增字段必须 `-am` 让 common 在同一 reactor 重编——首次漏 `-am` 用陈旧 `.m2` common jar 报 `NoSuchMethodError: isRefreshHeartbeat()`，补 `-am` 后转绿；`-am` 连带 common 跑测试无匹配需 `-Dsurefire.failIfNoSpecifiedTests=false`。

---

### 2026-07-17 外部 Agent 一键接入补全：checkIn/checkOut 纳入默认授权 + executor SKILL 升级为全套 MCP 说明书

#### 1. 范围

- 承接“外部第三方 AI Agent 接入 HelloAI 调度平台”的分步端到端验证（第 1 步一键注册已实测 PASS=9），推进 **第 2 步：注册 → 打卡（checkIn）→ 门铃 SSE 长连接**。
- 定位到的接缝断层：门铃建连闸门 `isOnDuty` 逻辑正确，但自助注册的 EXECUTOR 因 `checkIn`/`checkOut` **不在 `DEFAULT_EXECUTOR_TOOLS`** 默认授权清单 → 打不了卡 → 建不起门铃长连接。此为“平台没把工具给全”的产品缺口，非外部 AI 使用问题。
- 用户口径（路 A：修产品）：一键注册的本意是交付外部 AI 使用平台的**完整说明书 + 全套 MCP 工具**；用哪些/何时用是外部 AI 的事，但“没给全”是平台责任。据此：① 修默认授权；② 补全一键生成的 SKILL 说明书（打卡接口 + 如何操作 + 全套 MCP 工具）；③ 补真实路径验证脚本。
- 明确不做（本轮）：不改 Flyway/表结构（靠既有懒启用机制覆盖存量 Agent）；不动门铃闸门逻辑；不扩展 v3/v4/v5（后续步骤）。

#### 2. 实际落地

- **① checkIn/checkOut 纳入默认授权（`AgentMcpServerService.DEFAULT_EXECUTOR_TOOLS`）**
  - 清单由 8 → 10：`pullTasks, ack, claimSubTask, heartbeat, uploadArtifact, submitResult, reportBlocked, getAgentStatus` 追加 `checkIn, checkOut`。
  - **一处改动全覆盖，无需 Flyway**：`isToolEnabled(agentId, toolName)` 对 `DEFAULT_EXECUTOR_TOOLS` 内的工具有**懒启用**逻辑——`config == null && DEFAULT_EXECUTOR_TOOLS.contains(toolName)` 时自动 insert 启用行。因此新注册 Agent 走 `enableDefaultsForAgent` 插 10 行；**存量 Agent 首次调 `checkIn` 时被 `isToolEnabled` 懒启用补授权**。类注释与 `enableDefaultsForAgent` 方法注释由“7/8 工具”统一为“10 工具”。

- **② executor SKILL.md 升级为完整说明书（`helloai-core/.../resources/skills/executor/SKILL.md`）**
  - 原版全是 REST curl、只字未提 MCP——正是“没给全”。重写为完整接入手册（约 183 行），占位符 `<注册后填入>`/`{{BASE_URL}}`/`{{AGENT_NAME}}`/`<你的ID>` 保持不变（由 `PromptTemplateService` 注册时替换）。
  - 新结构：认证信息 → 两种接入方式对比（MCP 推荐 / REST 兜底）→ **一、MCP 接入**（1.1 连接配置 `/mcp/sse` + `/mcp/messages?sessionId=` + Bearer；1.2 全套 **10 个 MCP 工具**表含“何时使用”；1.3 推荐工作循环：`getAgentStatus → checkIn → 建门铃 → 收 inbox 信号 → pullTasks → claimSubTask → 执行 → uploadArtifact → submitResult → ack → checkOut`）→ **二、门铃长连接**（2.1 `curl -N .../doorbell/sse`，前置须 `checkIn` 否则 HTTP 500；2.2 信号类型 `connected/inbox/keepalive`；2.3 保活与重连）→ **三、REST API 参考**（保留收件箱/规则/子任务/审查/积分/日志兜底）→ 注意事项。

- **③ 真实路径验证脚本 `scripts/powershell/verify-onboarding-doorbell.ps1`（新增）**
  - 与 `verify-doorbell-e2e.ps1`（S2 直接 DB INSERT 一条 ACTIVE 租约）互补——本脚本证明**外部 AI 用自己 apiKey 通过 MCP 真能打卡**：S0 `POST /api/agents/register` 取 apiKey+agentId；S1 未打卡建门铃应拒（HTTP 500 + `code=500`）；S2 MCP 握手（`/mcp/sse` 抓 sessionId → initialize → notifications/initialized → `tools/call checkIn`，Bearer）读 SSE 流断言含 `leaseId`/`ok:true`；S3 打卡后建门铃断言 HTTP 200 + `event:connected`。
  - 遵循规则 6：UTF-8 编码头 + runtime 字面量纯 ASCII + CJK 只在注释；交付前踩到 line 148 **单引号内 CJK `'工具未启用'` 触发 PS 5.1 解析器提前闭合字符串**（规则 6 同类坑，此前仅记录双引号，本轮确认单引号亦然），改为纯 ASCII 正向断言后 `Parser.ParseFile` → PARSE-OK。

#### 3. 影响

- 对外行为变化：外部 AI 一键注册即拿**全套 10 个 MCP 工具**（含值班打卡）与含 MCP/打卡/门铃说明的完整 SKILL；自助注册后可直接 `checkIn` 上岗并建立门铃长连接（此前会被 tool-authz 拦截）。
- 代码变化：
  - `helloai-core/.../service/AgentMcpServerService.java`：`DEFAULT_EXECUTOR_TOOLS` 8→10（+`checkIn`/`checkOut`）+ 类/方法注释同步。
  - `helloai-core/.../resources/skills/executor/SKILL.md`：重写为含 MCP 全套工具 + 打卡 + 门铃的完整说明书。
  - `scripts/powershell/verify-onboarding-doorbell.ps1`（新增，第 2 步真实路径 E2E）。
  - `doc/HelloAI_实现差距表.md` + `doc/log/HelloAI_迭代执行记录.md`（本轮回填）。
- 数据结构变化：无（靠 `isToolEnabled` 懒启用覆盖存量 Agent，不新增 Flyway）。

#### 4. 遗留

- `verify-onboarding-doorbell.ps1` 已 PARSE-OK，**真实环境 E2E 已实测 ALL PASSED**（2026-07-17，本项遗留关闭）。
- 第 2 步之后的 v3（门铃推 inbox → 外部 AI 调 MCP pullTasks 取任务）、v4（连接不中断 + 双心跳保活）、v5（submitResult 反馈闭环）为后续步骤，本轮未触及。

#### 5. 验证回执

- `mvn -pl helloai-core -am compile -DskipTests` → **BUILD SUCCESS**（`DEFAULT_EXECUTOR_TOOLS` 改动编译通过）。
- `verify-onboarding-doorbell.ps1` 经 `[System.Management.Automation.Language.Parser]::ParseFile` 自检 → **PARSE-OK**（修掉单引号 CJK 提前闭合后）。
- **2026-07-17 真实环境实跑 `verify-onboarding-doorbell.ps1` → ALL PASSED（PASS=7 FAIL=0，agentId 2078004629359747074）**：`S0` 自助注册拿 apiKey `ak_5c25e8d31...`；`S1` 未打卡建门铃 HTTP 500 `{"code":500,"msg":"Agent 未在岗…”}`；`S2` MCP `tools/call checkIn` HTTP 200 → ACTIVE 租约建立（**仅靠默认授权修复、未 seed DB**）；`S3` 打卡后建门铃 `HTTP/1.1 200` + `event:connected` + `data:{"type":"connected",...}`。至此第 2 步（注册→MCP checkIn→门铃 connected）真实路径闭环。

---

### 2026-07-17 外部 Agent 接入第 3 步：门铃 inbox 唤醒 → MCP pullTasks 取任务（闭环实测通过）

#### 1. 范围

- 承接第 2 步（注册→checkIn→门铃 connected），推进 **第 3 步：门铃推 inbox 信号 → 外部 AI 调 MCP pullTasks 取任务**。
- 本步为**纯验证**，无产品代码改动：触发链路（`AgentInboxService.send` → `InboxMessageCreatedEvent` → `DoorbellRinger` → 门铃 ring）、pullTasks、子任务分配均已实现。

#### 2. 实际落地

- **新增验证脚本 `scripts/powershell/verify-onboarding-pull.ps1`**：S0 注册 → S1 MCP `checkIn` 上岗 → S2 建门铃 SSE（保持）收 `connected` → S3 `POST /api/tasks` + `POST /api/sub-tasks{assignedAgent}` 造一条 ASSIGNED 子任务（真实 inbox 源 `sub_task.assigned`）→ S4 断言门铃流出现 `event:inbox`（`type=inbox`、`eventType=sub_task.assigned`）→ S5 MCP `pullTasks` 断言返回含该 `sub_task.assigned` 且 `subTaskId` 匹配。
- **关键设计**：inbox 必须走 service 层（`AgentInboxService.send`）才会发事件、才会响铃；DB 直插不触发——故脚本用真实 REST 建 task+sub_task 驱动。executor apiKey 可通过 AuthInterceptor 调 `/api/tasks`、`/api/sub-tasks`、MCP `pullTasks`（不区分角色）。
- **脚本断言正则修正**：pullTasks 结果在 SSE 帧里是嵌套**转义 JSON**（`\"subTaskId\":<id>`），首版正则 `"subTaskId"\s*:` 被字段名前的转义反斜杠卡住 → 改为容错字符类 `subTaskId[\\":\s]*` 兼容转义/非转义两种形态。

#### 3. 影响

- 无产品代码/数据结构变动；仅新增一个验证脚本 + 本轮文档回填。

#### 4. 遗留

- 日志里 `sub_task.assigned` 消息 title 显示为乱码（`鬂颅件鹔″凡鈙嚇鎄`=“新任务已分配”）——为 curl 落盘 SSE 文件时 UTF-8/GBK 显示错位，**仅日志观感**，不影响断言（断言只匹配 ASCII 的 `sub_task.assigned` 与数字 ID）。
- v4（连接不中断 + 双心跳保活）、v5（submitResult 反馈闭环）为后续步骤。

#### 5. 验证回执

- **2026-07-17 真实环境实跑 `verify-onboarding-pull.ps1` → ALL PASSED（PASS=12 FAIL=0，agentId 2078007902414237698）**：S4 门铃推 `event:inbox` `{"type":"inbox","eventType":"sub_task.assigned","refType":"sub_task","refId":"2078007941622591490"}`；S5 MCP pullTasks 返回 `{"messageId":"inbox-...","type":"sub_task.assigned","subTaskId":2078007941622591490,"taskId":2078007941509345281,...}`。门铃 `refId` = pullTasks `subTaskId` = S3 创建 ID 三处一致，“响铃唤醒→拉取正文”契约闭环。
- 附带观察：同一门铃流还抓到一条 `event:keepalive`（14:44:34），提前印证 PR-4 `DoorbellKeepaliveTask` 保活帧在真实环境生效。
- `verify-onboarding-pull.ps1` 经 `Parser.ParseFile` 自检 → **PARSE-OK**。

---

### 2026-07-17 外部 Agent 接入第 4 步：连接不中断 + 双心跳刷在线（实测通过）

#### 1. 范围

- 承接第 3 步，推进 **第 4 步：连接不中断 + 双心跳保活**。“双心跳”指两条方向：方向 A（server→client）由 `DoorbellKeepaliveTask` 按 `keepalive-interval-ms`（默认 15s）向活跃连接广播 `event:keepalive` 穿透反代空闲超时；方向 B（client→server）由 Agent 调 MCP `heartbeat` → `HeartbeatService.seen` 刷 `last_seen_at` + Redis TTL 并重算在线态。
- 本步为**纯验证**，无产品代码改动（`DoorbellKeepaliveTask`、`heartbeat`、`getAgentStatus` 均已在 PR-4 / v2.4 阶段交付）。

#### 2. 实际落地

- **新增验证脚本 `scripts/powershell/verify-onboarding-heartbeat.ps1`**：S0 注册 → S1 MCP `checkIn` 上岗 → S2 建门铃 SSE（后台保持）收 `connected` → S3 保持门铃 ~20s 跨一个保活周期，断言收到 `event:keepalive` 且后台连接 job 仍 `Running`（连接未被切断）→ S4 REST `POST /api/mcp/tools/heartbeat` 断言 `ok:true` + agentId 匹配 → S5 MCP `getAgentStatus` 断言 `computedOnlineStatus`∈{ONLINE,IDLE} 且 `lastSeenAt` 已刷新。
- **关键设计**：S3 用后台 job 的 `Running` 状态直接作为“连接未被服务端切断”的证据（15s 保活周期内若长连接会断，20s 窗口内必现形）；heartbeat 走同步 REST（好断言），getAgentStatus REST 未暴露故走 MCP SSE 通道。

#### 3. 影响

- 无产品代码/数据结构变动；仅新增一个验证脚本 + 本轮文档回填。

#### 4. 遗留

- v5（完成任务 + submitResult 反馈闭环）为最后一步。

#### 5. 验证回执

- **2026-07-17 真实环境实跑 `verify-onboarding-heartbeat.ps1` → ALL PASSED（PASS=12 FAIL=0，agentId 2078010246900150274）**：S3 `event:keepalive`@14:54:04 距建连 14:53:51 约 13s（吻合 15s 周期）+ job `Running`；S4 heartbeat REST `{"ok":true,"agentId":"2078010246900150274",...}`；S5 getAgentStatus `computedOnlineStatus=IDLE`（`lastActiveAt=null` 未执行任务→按三态判定就是 IDLE）、`lastSeenAt=2026-07-17T06:54:14Z` 与 heartbeat 时刻一致（心跳确实刷新了 last_seen_at）。
- `verify-onboarding-heartbeat.ps1` 经 `Parser.ParseFile` 自检 → **PARSE-OK**。

---

  ## 6. 下一步方案：N12 P1 剩余三项（待用户拍板）
  
  本节由 2026-07-16 A 档收尾后双文档同步记录使用，仅作方案池与工作量参考，不包含代码落地。决定启动哪个方案后请在本节下追加“### 已拍板：方案 X”子节，再据此拉新迭代轮次。
  
  ### 6.1 背景
  
  A 档收尾（2026-07-16）已交付 N12 的 P0（值班租约闭环 + 值班优先调度）与 P1（只读报表 + dashboard 前端接入 + R2 旧 Publisher 清理 + R3 V22 backfill）。剩余三项 P1 能力尚未动：
  
  | 项 | 字段已存在 | 语义未实现 | 触达模块 |
  |---|---|---|---|
  | STRICT 独占报锁 | `agent_duty_lease.work_mode` | Selector 未按 STRICT 拒绝非专属任务 / 独占期间不接受其它任务 | `AgentSelector.pickAlternative` |
  | concurrency 预扣 | `agent_duty_lease.max_concurrent` | Selector 未读 `max_concurrent`，未维护 `sub_task` slot 引用计数 | `sub_task` 状态机 + slot 计数表 + Redis SETNX 锁 + Selector + Job |
  | 动态 TTL 自适应 | `agent_duty_lease.ttl_minutes` | startLease 与续约都是硬编码 TTL，未根据 `agent.score` / `consecutive_failure_count` 动态调整 | `DutyLeaseExpirationTask` + `AgentDutyLeaseService.startLease / heartbeat` |
  
  ### 6.2 五方案对比
  
  | 方案 | 内容 | 总估时 | 风险 | 推荐度 | 适用场景 |
  |---|---|---|---|---|---|
  | **A1** | 仅做 STRICT 独占报锁 | 0.5–1h | 低 | ⭐⭐⭐ 试水 | 想知道 N12 后续怎么“调档”，先做个轻的压压轴 |
  | **A2** | STRICT → 动态 TTL → concurrency 三项顺序从轻到重 | 5–8h（分 3 段） | 低→中→高 渐进可控 | ⭐⭐⭐⭐⭐ | 期望分项交付，每项独立 commit + verify + 文档回填 |
  | **A3** | 仅做 concurrency 预扣（价值最高） | 3–4h | 高 | ⭐⭐ | 上来啃最难的骨，头铁专用 |
  | **A4** | 三项一次性串行做完（合并一个 round） | 5–8h 一次 | 中 | ⭐⭐ | 跨度大，不建议作为单一轮次 |
  | **A5** | A1 + Agent 管理页文案轻改（`ACTIVE/DISABLED` → “在岗/离岗”） | 1.5h | 低 | ⭐⭐⭐⭐ | 兼顾 UI 概念混淆与 N12 后续，工作面最小 |
  
  ### 6.3 单项细节
  
  #### 6.3.1 STRICT 独占报锁（轻）
  
  - `AgentSelector.pickAlternative`：当存在任一 ACTIVE 且 `work_mode=STRICT` 的 lease，若任务不匹配该 Agent 专业域则跳过；STRICT 期间仅专属任务可被派发到该 Agent。
  - `McpMcpServer.checkIn` 已收 tool 入口，无需新增。
  - 单测 1–2 个用例覆盖：STRICT Agent 接到非专属任务时不入候选；专属任务可正常派发。
  - 验证脚本沿用 `verify-agenthub-duty-e2e.ps1` 加 S6 STRICT 子场景（不新增脚本）。
  
  #### 6.3.2 动态 TTL 自适应（中）
  
  - 指标：优先读 `agent.score`（如已有）或 `consecutive_failure_count`；低表现 Agent 缩短 TTL（5min）以便快速回收，高表现 Agent 拉长 TTL（2–4h）减少续约开销。
  - `AgentDutyLeaseService.startLease`：TTL 入参可空，为空时按 `Agent.score` 计算默认值。
  - `heartbeat`/`DutyLeaseExpirationTask` 续约路径调用 `adaptiveRenew(now)`，按上次成功时间拉长或缩短。
  - 新增 V24 `agent_duty_lease_renewal_policy` 表（`agent_id`, `consecutive_failure_count`, `recent_success_rate`, `last_score`, `effective_ttl_minutes`）作为策略落地处。
  - 单测 2–3 个用例覆盖：低分 Agent TTL 缩短；高分 Agent TTL 延长；连续失败重置 TTL。
  - 新增 `verify-dashboard-duty-leases.ps1` 子场景 S7 抽查续约 TTL 区间。
  
  #### 6.3.3 concurrency 预扣（重）
  
  - 新增 `agent_slot_inuse` 物化表（或用 `sub_task WHERE status IN (ASSIGNED, IN_PROGRESS, REVIEW, REWORK)` 实时 GROUP BY）。
  - `AgentSelector.pickAlternative`：排除 `inuse >= max_concurrent` 的 Agent，保留按 `dutyRank` 排序的语义。
  - `sub_task` 状态机：在 `ASSIGNED → IN_PROGRESS → REVIEW/DONE/REWORK/CANCELLED` 转换时维护 slot 引用计数（ASSIGNED +1，DONE/CANCELLED -1）。
  - Redis SETNX 三段式：`acquireSlot(agentId)`（预扣）/ 真扣（事务内提交）/ `releaseSlot(agentId)`（归还）；任一异常路径都需要正确归还。
  - 跨进程锁避坑：slot 计数走 Redis 主键，DB 写入走 `uk_duty_lease_agent_active` 的 partial unique index 防重。
  - 单测 3–4 个用例：预扣冲突降级、跨进程释放一致性、ABORTED/FAILED 归还、最大并发上限生效。
  - 新增 `verify-agenthub-duty-e2e.ps1` 子场景 S7 concurrency（多 sub_task 打到同一 Agent 时不超过 max_concurrent）。
  - 文档同步：差距表 N12 行从“保持现状”改为“部分交付 / A2/A3 子项进行中”。
  
  ### 6.4 推荐路径
  
  - **首选 A2**：从轻到重，渐进可控，3 个 atomic round，每项独立 commit + verify + 文档回填。
  - **次选 A5**：若想先消化“Agent 管理页面与值班租约页面 ACTIVE 同名” 的概念混淆，同步做 UI 轻改。
  - **不推荐 A4**：跨度大，单一轮次风险不可控。
  
  ### 6.5 待用户拍板
  
  - [x] **2026-07-17 用户拍板 A2**（从轻到重三项顺序分 3 段 atomic round），本节同步补充 A2 第 1 段 STRICT 独占报锁 交付记录于 §6.6
  - [ ] A2 第 2 段（动态 TTL 自适应）启动时机
  - [ ] A2 第 3 段（concurrency 预扣）启动时机
  - [ ] 选完后回写本节“### 已拍板：方案 X” 并在差距表 §5 优先级建议 / N12 处理建议列同步状态
  
  ---
  
  ### 6.6 已拍板：方案 A2 第 1 段（STRICT 独占报锁）— 2026-07-17 交付
  
  #### 1. 范围
  
  按用户拍板的 A2 路径推进本轮 3 段 atomic round 中的第 1 段，语义收口为：**STRICT Agent 只接自己被初始指派的任务，不参与别人失败后的 pickAlternative 替补池抢派别人失败的任务**。本轮明确不做：A2 第 2 段（动态 TTL 自适应）、A2 第 3 段（concurrency 预扣）、按“任务域”识别专属（当前业务模型无 conversationId/sessionId/groupId，Agent 端有 specialization_slug/capabilities/labels 字段支撑但 SubTask 端无“所需域”字段，留待后续轮次）。
  
  #### 2. 实际落地
  
  - **枚举基座**——新增 `helloai-common/.../constant/WorkMode.java`：`AUTO` / `STRICT` 两值，**双解析策略**：
    - `lenientParse(String raw)`：DB 读取宽容——`null` / 空串 / 未知值→返回 `AUTO`（不抛异常，避免脏数据让运行崩）
    - `strictParse(String raw)`：MCP 入参严格——非法值抛 `IllegalArgumentException`（调用方 `McpToolService.checkIn` 改包为 `BizException` 拒绝，不静默降级为 AUTO）
  - **Selector 过滤**——`AgentSelector.pickFromCandidates` 在原 `ACTIVE` 过滤之后、熔断检查之前加一行 `.filter(a -> !isOnStrictDuty(a.getId()))`；新增私有方法 `isOnStrictDuty(Long agentId)`，读 `agentDutyLeaseService.getActiveLease(agentId)` + `WorkMode.lenientParse(lease.getWorkMode()) == STRICT` 判定，**查询异常回退 false**（不因租约查询偶发抖动误退）
  - **入参校验**——`McpToolService.checkIn` 改 `mode = WorkMode.strictParse(workMode)`，`catch (IllegalArgumentException e) { throw new BizException(e.getMessage()); }`；落库用 `mode.name()` 字符串保证与枚举名完全一致
  - **单测**——`AgentSelectorTest` 新增 `StrictDutyFiltering` 分组 5 个用例（`shouldSkipStrictOnDutyAgent` / `shouldReturnNullWhenAllCandidatesStrict` / `shouldTreatNoLeaseAgentAsAuto` / `shouldLenientParseDirtyWorkMode` / `shouldFallbackWhenLeaseQueryThrows`），全量 19/19 全绿
  - **E2E 脚本**——`verify-agenthub-duty-e2e.ps1` NORMAL→AUTO 5 处一致性修订 + 追加 S6.1/S6.2/S6.3 三子场景（约 99 行）：
    - **S6.1** workMode=STRICT checkIn → DB `status=ACTIVE, work_mode=STRICT` 断言
    - **S6.2** workMode=`strict`（小写）checkIn → DB `work_mode=STRICT`（大小写不敏感，证明 lenientParse / strictParse 都管用）
    - **S6.3** workMode=`BOGUS_VALUE` checkIn → 断言**不**落库（BizException 拒绝、lease count 前后不变）
  - **踩坑沉淀**——本轮在 e2e 脚本踩到两个独立 PS 5.1 坑，均已落 memory：
    1. **MCP `tools/call` 返回 JSON-RPC 2.0（`{jsonrpc,id,result:{content:[{type,text}]}}`），不是平台 `{code,msg,data}` 业务包装**——断言必须用 HTTP 200 + DB 状态，**不能** `ConvertFrom-Json` 后直接拿 `$body.code`
    2. **PS 5.1 函数 `return $arr`（单元素数组）会被 unroll 成 `System.String`**——调用方 `$arr[0]` 取到首字符而非首元素。修复：函数改为返回**单 string**，调用方拿到 string 后用 `.Split('|')` 拿 String[]（.NET String.Split 在 PS 脚本层调用不 unroll）。同时捎带把所有 Write-Error / Write-Output 字符串按规则 6 改成“单引号 + `+` 拼接，runtime 字面量纯 ASCII、中文只留注释”
  
  #### 3. 影响
  
  - **对外行为变化**：`AgentSelector.pickAlternative` 调起时，候选列表里 ACTIVE 租约 `work_mode=STRICT` 的 Agent 会被过滤——它们不再抢派别人失败的任务；`checkIn` 入参非法值直接 BizException 拒绝（不会静默降级为 AUTO 让值班表里偷偷跑 AUTO 模式）
  - **配置变化**：`agent_duty_lease.work_mode` 字段已存在（`V1__init_all.sql` AgentHub V1 T3 建表），无 schema 变化；MCP `tools/call` 客户端可在 `checkIn` 入参中传 `"workMode":"STRICT"` 显式开启严格模式（缺省 `AUTO`）
  - **代码变化**：`WorkMode.java`（新建 71 行）；`AgentSelector.java` import + 一行 `.filter` + 19 行 `isOnStrictDuty`；`McpToolService.java` import + 8 行入参校验；`AgentSelectorTest.java` 115 行新增 + 6 行 helper；`verify-agenthub-duty-e2e.ps1` 5 处 NORMAL→AUTO + 99 行 S6 子场景 + 函数 return 改单 string + 7 处 Write-Error/Write-Output 按规则 6 重写
  
  #### 4. 遗留
  
  - A2 第 2 段（动态 TTL 自适应）未启动
  - A2 第 3 段（concurrency 预扣）未启动
  - “专属任务”按域匹配未实现（业务模型无 conversationId 概念、SubTask 端无“所需域”字段），但已通过 §6.6 第 1 段范围说明明确口径——STRICT 退出替补池 = 不接替补；如后续要按域专属再开一段
  
  #### 5. 验证回执
  
  - **`mvn -pl helloai-core -am compile`** BUILD SUCCESS
  - **`mvn -pl helloai-core -am test -Dtest=AgentSelectorTest`** 19/19 全绿（含 5 个新 STRICT 用例）
  - **`scripts/powershell/verify-agenthub-duty-e2e.ps1` 真实环境实测 ALL PASSED**（S1 checkIn / S2 checkOut / S3 DutyLeaseExpirationTask / **S6 N12-P1 STRICT 三子场景**）：
    - S6.1 workMode=STRICT → DB status=ACTIVE, work_mode=STRICT ✓
    - S6.2 workMode=`strict` 小写 → DB work_mode=STRICT（大小写不敏感）✓
    - S6.3 workMode=BOGUS_VALUE → BizException 拒绝，lease count 不增（仍为 N）✓
  - 脚本报 `Parser.ParseFile` 自检 **PARSE-OK**

---

### 6.7 UI：AgentOnboardingDialog 接入弹窗按钮换位（2026-07-17）

UI 行为变更：`helloai-ui/src/views/agent/components/AgentOnboardingDialog.vue` 把"复制 SKILL + 切换视图"两个 AI 视角按钮替换为：

- ⬇️ **下载 hello_ai_skills.md**（文件名方案 C：`hello_ai_<agentName>.md`，中文 agent 名降级为下划线，跨平台兼容）
- 🚀 **一键上班口令**（动态拼接 `你是 HelloAI 平台的 <agentName>（ID=<agentId>），请按平台 SKILL 接入并开始工作。`）

顺手删除 `showSkillOnly` ref + `copySkill` + `toggleView`（功能由下载按钮接管）。commit `65161ba`。

> 说明：同 commit 中 `skills/executor/SKILL.md` 按“平台外部 Agent 接入文档”域分类，不进本迭代记录；本节仅回填项目开发侧 UI 改动。

### 2026-07 Controller 分层红线收口（§6.3 + 3.x 包归位）

#### 1. 范围

按 `doc/HelloAI_CODE_STYLE.md` §6.3 第 1 条「禁止注入 Mapper」与第 2 条「禁止书写 SQL/QueryWrapper 条件」强制收口 6 个历史违规 Controller；同步完成两项包归位：`com.helloai.config` 2 个类并入 `com.helloai.start.config`、`helloai-start/.../chat/DeepSeekProviderChatClientFactory` 移至 `helloai-core/.../core/agent/chat/provider`；Code Style §6.3 待收口清单与 3.x start 配置类待收口段落同步收口。本轮完成后提交一个 commit。

#### 2. 实际落地

##### 2.1 6 个 Controller Mapper 收口（`helloai-api/.../controller/`）

| Controller | 原 Mapper 注入 | 改后依赖 Service | 下移查询方法 |
|---|---|---|---|
| ActivityController | ActivityLogMapper | ActivityLogService | `list(page,pageSize,level,source,subTaskId)` / `record(...)` |
| AdminDashboardController | TaskMapper/SubTaskMapper/AgentMapper/SysUserService/AgentService | AdminDashboardService | `getOverview()` / `listBlockedHighlight()` / `listReviewHighlight()` / `listLowActivityAgents()` / `getTrends(days)` |
| AgentDutyLeaseController | AgentMapper | AgentDutyLeaseService | 复用现有 `getAgentNamesByIds(...)` 去掉原 nameCache N+1 |
| AttachmentController | AttachmentMapper | AttachmentService | `list(subTaskId)` / `getByIdRequired(id)` / `getStorageUrlRequired(id)` |
| DashboardController | TaskMapper/SubTaskMapper/AgentMapper | DashboardService | `getStats()` |
| FeedController | ActivityLogMapper/AgentMapper | FeedService | `listActivityLogs(...)` / `resolveAgentNames(logs)` / `listAgentSummaries()` |

所有 Controller 现在零 Mapper 依赖；返回 DTO 装配（`ActivityLog→FeedResponse`、`Agent→AgentResponse`、`AgentDutyLease→DutyLeaseResponse`、`Map→DashboardOverview`）保留在 Controller（§6.7 原则）。`AttachmentController.getById` 错误处理由 `R.fail(...)` 改为 `BizException(404)` 统一走全局异常处理（语义等价，错误响应体不变）。

##### 2.2 Service 调整（`helloai-core/.../`）

- **扩展**：`ActivityLogService`（新增 `list` / `record`，事务性写入带 INFO 默认 + agent 默认 source）/ `AgentDutyLeaseService`（新增 `getAgentNamesByIds`，内部 `selectBatchIds` 避免 N+1）/ `AttachmentService`（新增 `list` / `getByIdRequired` / `getStorageUrlRequired`）。
- **新建**：`AdminDashboardService`（不继承 ServiceImpl，跨 Mapper 聚合，返回 Map 避开 core→api DTO 依赖）/ `DashboardService`（同样不继承 ServiceImpl）/ `FeedService`（聚合 ActivityLog + Agent，复用 ActivityLogService.page）。

##### 2.3 包归位（git mv 保留历史）

- `helloai-start/.../config/MyBatisPlusMetaObjectHandler.java`：package `com.helloai.config` → `com.helloai.start.config`
- `helloai-start/.../config/AdminInitializer.java`：package `com.helloai.config` → `com.helloai.start.config`
- `helloai-start/.../start/chat/DeepSeekProviderChatClientFactory.java` → `helloai-core/.../core/agent/chat/provider/DeepSeekProviderChatClientFactory.java`：package `com.helloai.start.chat` → `com.helloai.core.agent.chat.provider`

##### 2.4 依赖补齐

`helloai-core/pom.xml` 新增 `spring-ai-starter-model-deepseek`（Spring AI BOM 已 import，无需指定版本）——因为 `DeepSeekProviderChatClientFactory` 现位于 core，需要在 core 直接依赖 deepseek starter 才能解析 `org.springframework.ai.deepseek.*`。`helloai-start/pom.xml` 保留该依赖是透传必要（application.yml 仍声明 deepseek 字段）。

##### 2.5 CODE_STYLE.md 文档同步

- §3.x start 模块配置类归属段落：「（待收口）」去掉，改为陈述句描述已收口事实；不再允许再出现分裂包。
- §6.3 Controller 职责边界：「当前待收口清单 6 个」删除，替换为「✅ 收口完成」清单 + 对应 6 个 Service 名。

#### 3. 验证

- `mvn -DskipTests clean compile`：7 模块全 SUCCESS（HelloAI Common / MQ / Core / Job / API / Start），`Compiling 78 source files with javac [debug parameters target 17]` 在 helloai-api 阶段正常通过；本次新增/改动的源文件全部编译通过，无新增警告。
- `git status`：6 Controller M + 3 Service M + 3 RM（git mv）+ 3 Service 新增 + helloai-core/pom.xml M + CODE_STYLE.md M；DIFF 总计：删 Mapper 字段 6 处 / 删 selectList/selectCount/selectById/selectPage 等调用 10+ 处，新增 Service 方法调用 10+ 处。

#### 4. 影响

- **对外行为**：完全等价。API 路径、请求/响应 schema、错误码（含 404 / 500 BizException→R.fail 映射）保持不变。
- **架构分层**：Controller 层 0 Mapper；Service 层成为对应 Controller 的唯一访问边界；§6.3 第 1 条「禁止注入 Mapper」在 6 个历史违规文件上正式生效。
- **包结构**：`com.helloai.config` 与 `com.helloai.start.chat` 两个分裂包正式退出；新增配置类一律落 `com.helloai.start.config`，新增 ChatClient 工厂一律落 `core.agent.chat.provider`。
- **后续约束**：任何新增 Controller 必须遵循当前模板（构造器注入 Service，不持有 Mapper）；CODE_STYLE §6.3 与 §3.x 已是终态文字，不再回退。

#### 5. 说明

- 本轮明确不做：6 个 Service 的单测补齐（独立迭代）；`ActivityLogService.record` 事务边界与 `AttachmentService.register` 现有逻辑保留原状；`AttachmentController.getById` 错误路径从 `R.fail("附件不存在")` 改为 `BizException(404,"附件不存在")` 是顺手统一走全局异常处理，对外响应仍为 `{code:404,msg:"附件不存在"}`，下游不受影响。
- 提交策略：单 commit 提交本次全部改动（含 6 Controller + 6 Service + 3 包归位 + pom + 文档）。

---

### 2026-07-20 调度链缺陷修复（v2.6 §4.1）

#### 1. 范围

按 `doc/design/HelloAI_调度解耦重构分析.md` v2.6 §4.1 节拍板，针对历史 commit `9e47f17` 提交前的四项调度链遗留缺陷做收口：

- **AOP 降级未织入**：`ResilientDispatcher` `@CircuitBreaker` 在缺 AOP starter 的环境下不触发 fallback（仅 `ResilientDispatcherTest` 纯 unit 验证 new 路径）
- **心跳离线阈值不统一**：`AgentHealthCheckTask` 硬编码 `STALE_THRESHOLD_MINUTES`，与 `AgentSelector` 各自的 `heartbeatFreshMinutes` 规则漂移
- **离线重派失败后无二跳**：`AgentHealthCheckTask.reassignStaleTasks` 仅调一次 `redispatchOfflineSubTask`，抛错即放弃
- **PENDING 未指派孤儿无全局兜底**：`ExternalAgentFallbackTask` 只扫描 N11 候选，不管 PENDING + assigned_agent_id IS NULL + 有历史 record + 无活跃 record 的调度链遗留

范围明确：不涉及 v3 路线图；不重做 AOP 失败语义；不动 PENDING 派发的业务编排；不替换 Reconcile 主链；外部 Agent 一键接入（M5）链路保持现状。

#### 2. 实际落地

##### 2.1 补齐 AOP 依赖与统一心跳健康配置

- `helloai-core/pom.xml` 新增 `spring-boot-starter-aop`（让 `@CircuitBreaker`/`@Aspect` 可被 Spring 代理织入）
- 新建 `helloai-common/.../config/AgentHealthProperties`：`prefix=helloai.agent.health`，默认 `offlineMinutes=5`（对齐 Redis 心跳 TTL 30s × 10 = 5min）
- `AgentDispatchProperties.heartbeatFreshMinutes` 字段删除（迁移注释指向 `AgentHealthProperties.offlineMinutes`），消除两套配置漂移风险

##### 2.2 统一 Selector 与回退候选心跳过滤

- `AgentSelector.isHeartbeatFresh` 改用 `AgentHealthProperties.getOfflineMinutes()`；`thresholdMinutes <= 0` 视为关闭过滤（逃生口）；API_KEY_LLM/WEB_BROWSER 始终视为新鲜（架构 §3.8 三层可用性）
- `AgentMapper.selectFallbackCandidates` 增加 `@Param("lastSeenCutoff") OffsetDateTime lastSeenCutoff`，SQL 增加 `last_seen_time IS NOT NULL AND last_seen_time > #{lastSeenCutoff}`；与 Java 侧 `AgentSelector` 共用同一阈值
- `ExternalAgentFailureTracker.shouldFallback` 同步加心跳检查（`offlineMinutes <= 0` 时 bypass，包括 null last_seen_time 也视为可回退）
- `AgentHealthCheckTask` 删除硬编码 `STALE_THRESHOLD_MINUTES`，改用 `healthProperties.getOfflineMinutes()`

##### 2.3 修复离线重派与 PENDING 遗留兜底

- `AgentHealthCheckTask.reassignStaleTasks` 重构为按 Agent 维度调用：
  - 首选路径：`subTaskDispatchService.redispatchOfflineSubTask(task.id, agentId)`（弹性 fallback 触发）
  - 二次路径：首选失败后调 `subTaskDispatchService.dispatchPendingSubTaskAuto(task.id, fallbackRole)`，role 用原 Agent 的 `agent.role`，缺失时回退 `AgentRole.EXECUTOR`
  - 统计三档：`reassignedByFallback` / `reassignedByAuto` / `failed`
- `SubTaskMapper` 新增 `selectPendingUnassignedWithoutActiveExecutionRecord(int limit)`：筛 PENDING + assigned_agent_id IS NULL + EXISTS 历史 record + NOT EXISTS 活跃 PENDING/RUNNING record
- `ExternalAgentFallbackTask.scan()` 拆分为两个独立阶段：
  - 阶段 A：`failureTracker.findFallbackCandidates()` -> `processCandidate`（N11 阈值回退）
  - 阶段 B：`recoverPendingUnassigned()` 全局 PENDING 兜底（每次扫描独立一次，避免阶段 A 失败时不执行）

##### 2.4 补齐核心与任务调度回归测试

- 新增 `ResilientDispatcherAopIntegrationTest`（Spring Boot 集成测试，`@SpringBootConfiguration + @EnableAspectJAutoProxy + @ImportAutoConfiguration({AopAutoConfiguration, CircuitBreakerAutoConfiguration})`），3 个测试验证：Bean 是 AOP 代理 / OFFLINE CLI_CLIENT 触发 fallback / `Advised.getTargetClass()` 暴露原类
- 新增 `AgentHealthCheckTaskTest`（11 个测试，3 个 Nested：Precondition / ReassignStaleTasks / OfflineCasGuard），通过反射调用 `reassignStaleTasks(Agent)`：首选成功 / 首选失败->二次成功 / 双层失败 / OFFLINE CAS 返回 0 / Redis TTL 仍在 / `offlineMinutes <= 0` 禁用
- `ExternalAgentFailureTrackerTest` 增心跳相关测试（null last_seen / 新鲜/过期/边界 / `offlineMinutes <= 0` 旁路），遗留 2 参数 `shouldDelegateToMapper` 升级为 3 参数版本
- `ExternalAgentFallbackTaskTest` 增 7 个 PENDING 兜底测试：阶段 A 无候选仍执行阶段 B / 状态变化跳过 / 删除跳过 / 已分配跳过 / 单条失败不中断 / 不污染 N11 计数 / N11 成功时仍跑 PENDING 兜底
- `AgentSelectorTest` 增 `v2.6 心跳新鲜度过滤` Nested（7 用例）：默认 5min 边界 / 15min 过期 / 4min 新鲜 / API_KEY_LLM null 豁免 / 多候选 fresher 战胜 stale / `offlineMinutes=0` 关闭过滤 / `offlineMinutes=3` 自定义阈值
- 顺手修复 pre-existing：`helloai-core/src/test/java/com/helloai/core/doorbell/` 下 5 个 Doorbell 测试文件（`DoorbellRegistryTest` / `DoorbellServiceTest` / `DoorbellDutyListenerTest` / `DoorbellKeepaliveTaskTest` / `DoorbellRingerTest`）package 声明为 `com.helloai.core.shared.doorbell` 但放在错误目录下，迁移到正确目录后全部通过

#### 3. 验证

- `mvn -pl helloai-common install -DskipTests` SUCCESS
- `mvn -pl helloai-core test -Dtest='AgentSelectorTest,ExternalAgentFailureTrackerTest,ResilientDispatcherTest,ResilientDispatcherAopIntegrationTest'` **58/58 全绿**
- `mvn -pl helloai-job test -Dtest='AgentHealthCheckTaskTest,ExternalAgentFallbackTaskTest,SubTaskPendingOrphanTaskTest'` **38/38 全绿**
- `mvn -pl helloai-core install -DskipTests` SUCCESS
- `mvn test`（reactor 全量）**BUILD SUCCESS**：HelloAI Common / MQ / Core / Job / API / Start 7 模块全 SUCCESS，helloai-core 216 个测试全绿（含已修复的 5 个 Doorbell 测试）
- `mvn -pl helloai-start -am package -DskipTests` SUCCESS，`helloai-start/target/helloai-start-1.0.0-SNAPSHOT.jar` 62MB 产物可构建（沙箱无 PostgreSQL/Redis，真实链路断心跳验收需外部环境执行）

#### 4. 影响

- **架构影响**：心跳阈值唯一源（`helloai.agent.health.offline-minutes`，默认 5min），消除 Java/SQL 规则漂移；AOP starter 上车后 `@CircuitBreaker`/`@Aspect` 注解可织入
- **调度影响**：离线重派二次路径就绪，原 Agent 失败时按角色 EXECUTOR 回退二次选人；PENDING 未指派孤儿全局兜底（阶段 B），不会卡在历史 record + 无活跃 record 的调度链遗留
- **测试影响**：AgentSelectorTest 19->26、ExternalAgentFailureTrackerTest 11->22、ExternalAgentFallbackTaskTest 8->15、AgentHealthCheckTaskTest 0->11、新增 ResilientDispatcherAopIntegrationTest 3；覆盖率从“单元验证 new 路径”提升到“真实 Spring 上下文织入 + fallback 触发”
- **对外行为**：API 路径、配置 key 兼容（`AgentDispatchProperties.heartbeatFreshMinutes` 删除不影响线上，因为从未被 application.yml 引用）；幂等守卫（OFFLINE CAS `IS DISTINCT FROM`）维持现状
- **文档影响**：差距表 N7 / N11 项更新“二次选人加固 + 5min 健康阈值统一 + PENDING 兜底”子条目

#### 5. 遗留与下一步

- 真实断心跳链路验收（启动 Spring Boot + PostgreSQL/Redis + 创建 CLI_CLIENT Agent + 等待 5 分钟超时）需在外部环境执行；沙箱内只能验证 jar 集成构建与单元/集成测试
- `OfflineAgentAutoRedispatchProperties`（如需将 offlineMinutes 提升为 per-Agent 配置）暂未抽取，本轮统一为全局默认 5min 即可覆盖 N11/N12/N7 三处使用方
- `verify-subtask-redispatch-auto-execution.ps1` 的 `-Scenario offline` 路径（480s 超时）已可跑；本轮未在沙箱内联跑（无 DB/Redis），但脚本本身保持现状

---

### 6.8 EXECUTOR 端到端实时性修复：SKILL §1.5 常驻值班协议 + 参考 daemon + UI 下载入口（2026-07-20）

#### 1. 范围

针对 qoder-ceshi（EXECUTOR 外部 Agent）被调度后“打卡就走”的伪在线模式——`checkIn` 到后只跑 8 秒探针就退出，导致 22 秒认领窗口被误认为已错过、平台動辄走重派路径——推动 Agent 侧向“真常驻值班”转型，本轮重点修改：

- **A 类（必做，本轮完成）**：
  - `executor/SKILL.md` §1.5 新增《常驻值班协议（必读·关键）》，明确“checkIn 拿到 ACTIVE 后必须立刻拉起常驻后台进程”跳出致命前提
  - 同文件 §1.3 推荐工作循环改为“拉起常驻值班进程”替代旧“建立门铃长连接 + 周期性 heartbeat”描述
  - 新增 `scripts/powershell/qoder-ceshi-daemon.ps1`（PowerShell 5.1 兼容）作为参考实现骨架
- **B 类（建议同 commit）**：
  - `AgentOnboardingDialog.vue` 增按钮“下载常驻值班脚本（PowerShell）”（type=info），弹窗文本补充说明
  - `helloai-ui/public/scripts/powershell/qoder-ceshi-daemon.ps1` 同步拷贝为静态资源（避免后端 DTO 改动，下轮补 `daemonScript` 字段）
- **C 类（顺后下轮）**：
  - 派单过滤 OFFLINE：仅派给 `onlineStatus=ONLINE` 的 Agent，跳过 OFFLINE
  - inbox 状态机：重派时给原 assignee 标记 `superseded=true`，UI 区分“待 claim / 已错过认领窗口”
  - 错误可观测性：在收件箱 UI 区分两种状态【需后端协调 + 数据迁移 + 状态机调整，以补缺口】

#### 2. 实际落地

##### 2.1 SKILL.md §1.5 常驻值班协议（必读·关键）

文件：`helloai-core/src/main/resources/skills/executor/SKILL.md`

- **§1.5.1 关键认知**：明确门铃 SSE 是真推送（server push），不是轮询；定时任务只是补丁（heartbeat/续签/兜底）
- **§1.5.2 常驻三件套**：门铃 SSE（实时推送）+ 30s heartbeat（健康证明）+ 30s pullTasks（兜底防漏），必须同一后台进程并行
- **§1.5.3 TTL 续签节奏**：到期前 1 分钟（`renew-before-expiry-sec=60`）自动 `checkOut + checkIn + 重连门铃`，避免服务端主动关 SSE
- **§1.5.4 退出清理剧本（必须按顺序执行）**：停轮询 → MCP `checkOut` → kill doorbell curl → kill /mcp/sse curl
- **§1.5.5 反模式（不要这么做）**：`checkIn → 8s 探针 → 退出`、单轮询不心跳、不重连门铃遗漏心等等
- **§1.5.6 正模式骨架**：Python/Kotlin/Node/Shell 参考指向 `scripts/powershell/qoder-ceshi-daemon.ps1`

此外 §1.3 的“推荐工作循环”补了一句绑合依赖：`checkIn 后拉起常驻值班进程（见 §1.5），不允许仅探针后退出`。

预计净增：+~70行（§1.5 主体 + §1.3 工作循环微调）。

##### 2.2 参考 daemon 脚本（PowerShell 5.1）

文件：`scripts/powershell/qoder-ceshi-daemon.ps1`（新建）

骨干映射计划：

- 入口：UTF-8 编码头 + `Get-Date` BOM 剥除
- `Start-McpSse / Stop-McpSse`：`Start-Job -ScriptBlock { & curl.exe -i -N ... } | Out-File -Encoding ascii`，`Select-String` 抽 sessionId
- `Start-DoorbellSse / Stop-DoorbellSse`：同上，加 query `?sessionId=<sid>`
- `Initialize-Mcp`：initialize + notifications/initialized
- `Invoke-CheckIn / Invoke-CheckOut`：调 MCP tools/call
- `Invoke-Heartbeat / Invoke-PullTasks`：30s 心跳 + 30s 拉取
- `Read-DoorbellDelta`：基于 marker file 的增量读取，扫 `event:inbox` / keepalive
- `Test-LeaseExpiringSoon / Invoke-RenewLease`：到期前 60s 走 checkOut+checkIn+重连
- 主循环：30 秒一个 tick；Ctrl+C 触发退出清理剧本

预计净增：+~180 行。

##### 2.3 UI 下载入口（B 类）

文件：`helloai-ui/src/views/agent/components/AgentOnboardingDialog.vue` + `helloai-ui/public/scripts/powershell/qoder-ceshi-daemon.ps1`

- 新按钮“下载常驻值班脚本（PowerShell）”（type=info）插于“下载 hello_ai_skills.md”与“一键上班口令”之间
- 加 `downloadDaemon()` 方法：`fetch('/scripts/powershell/qoder-ceshi-daemon.ps1')` → `Blob` → 浏览器触发下载，文件名 `hello_ai_<agentName>_daemon.ps1`
- 本轮未改 DTO（`daemonScript` 字段跳到下轮 C 类一起备），UX 提示“下载后请手动改 agentId/apiKey”（对应提示信息已在脚本头部以 == 例注释方式呈现）

预计净增 UI：+~30 行。

#### 3. 验证

- **`mvn -pl helloai-core test -Dtest=PreFlightTest`**：16/16 全绿，§1.5 预飞行检查不被现有 doctest 拦截
- **`scripts/powershell/qoder-ceshi-daemon.ps1`**：能解析、函数 `/ Start-Job / curl / regex pipeline` 语法树 PARSE-OK（[System.Management.Automation.Language.Parser]::ParseFile）
- **端到端股】补充**：本轮仅 `SKILL.md` + `daemon.ps1` + UI 改动，股】为 qoder-ceshi 实测点（后续 C 类补齐后跨轮验证）

#### 4. 影响

- **架构影响**：EXECUTOR 接入路径从“AI 主观调度”转为“标准化常驻进程”，减少外部 Agent 重复踩坑（一处 SKILL 多个 Agent 复用）
- **设计补救**：门铃 SSE “真推送 vs 轮询” 调表避免下一轮 Agent 重走老路；PE门铃 +30s heartbeat 缺口
- **文档影响**：SKILL.md §1.5 作为后续 EXECUTOR Agent 接入必读范本；AgentOnboardingDialog 文本补充“下载 daemon 后门铃推送”描述
- **UI 影响**：弹窗按钮从 4 个增为 5 个；右侧 public/ 资源体积 +12.5 KB（daemon.ps1）
- **接口影响**：对外 API 未变（`AgentOnboardingResponse` 未增 `daemonScript` 字段；下轮顺手补）

#### 5. 遗留与下一步

- **C 类三项平台侧优化**：派单过滤 OFFLINE、inbox `superseded` 状态机、UI "待 claim / 已错过" 双状态区分，仍顺后下轮（2 人天估算）
- **DTO 补字段**：下轮补 `AgentOnboardingResponse.daemonScript`（String，主体内嵌入脚本原文），下载按钮可从 DTO 里取、避免从 `public/` 冷拉静态资源
- **多平台 daemon 骨架**：本轮只出 PS 5.1 版本（覆盖当前所有测试用例）；Linux bash 版本下轮按需补（正文架可用同一 §1.5.6 骨架）
- **实测证据加权**：股】后续补一次以“门铃常驻 vs 探针模式”两种调度路径上拍“认领耗时中位数 / 超时率”对比，证实本轮修复价值
- **合并策略**：A 类（SKILL.md + daemon.ps1）+ B 类（UI 按钮 + public/ 拷贝）合并一条 commit：`feat(executor): add §1.5 常驻值班协议 + 参考 daemon 脚本 + UI 下载入口`；本轮文档回填随同 commit 入提交

---

### 2026-07-20 重分配熔断（V24）

#### 1. 范围

- 修复"同角色所有 Agent 全掉线时子任务无限重分配"的死循环 Bug
- 新增基于计数的重分配熔断机制：达到阈值后直接取消子任务，不再继续重试

#### 2. 问题背景

用户反馈：sub-task-002 无限重新分配，重新分配的 Agent 都是 OFFLINE 状态，系统持续轮询形成死循环。

根因链路：
1. `AgentHealthCheckTask`（每 60s）检测到 Agent 超时 → 标记 OFFLINE → `reassignStaleTasks()`
2. `redispatchOfflineSubTask()` → `ResilientDispatcher.assignNext()` → OFFLINE fast-fail → fallback `pickAlternative()` 全部 OFFLINE 返回 null → 抛异常
3. 二次路径 `dispatchPendingSubTaskAuto()` 也选不到在线 Agent → 失败
4. 子任务退回 PENDING → `ExternalAgentFallbackTask.recoverPendingUnassigned()` 捡起 → 再次尝试 → 失败
5. 周而复始，形成死循环

#### 3. 实际落地

- **V24 Flyway**：`V24__sub_task_reassign_attempt_count.sql` 新增 `sub_task.reassign_attempt_count INT NOT NULL DEFAULT 0`
- **实体**：`SubTask.java` 新增 `reassignAttemptCount` 字段（`@TableField` 自动映射）
- **Mapper**：`SubTaskMapper.xml` 新增 `incrementReassignAttemptCount` 原子累加 SQL（COALESCE +1，不依赖读后写）
  - `updateById` 覆盖 SQL 新增 `external_fallback_count`、`reassign_attempt_count` 两列（修复之前遗漏的列覆盖）
- **配置**：`AgentDispatchProperties.maxReassignAttempts`（默认 5），`application.yml` 新增 `helloai.dispatch.max-reassign-attempts: 5`
- **核心逻辑**：`SubTaskDispatchService.checkReassignCircuitBreaker(subTaskId)` 私有方法，在 4 个重分配入口前统一调用：
  - `maxReassignAttempts <= 0` → 熔断禁用（逃生口）
  - 子任务终态（DONE/CANCELLED）→ 跳过
  - `reassign_attempt_count >= maxReassignAttempts` → 标记 CANCELLED + 记录 `sub_task_cancelled` timeline（reason=`reassign_attempt_exceeded`）→ 返回 true（跳过本次重分配）
  - 否则 → 原子累加计数 → 返回 false（继续重分配）
- **4 个入口全部接入**：`redispatchOfflineSubTask`、`redispatchAssignedTimeout`、`redispatchForFallback`、`dispatchBlockedSubTask`
- **测试**：`SubTaskDispatchServiceTest` 新增 `SubTaskMapper` / `AgentDispatchProperties` mock + `@BeforeEach` 设置熔断默认关闭（保持 7 个已有测试行为不变），7/7 全绿

#### 4. 验证

- `mvn -pl helloai-common,helloai-core -am -DskipTests compile` → BUILD SUCCESS
- `mvn -pl helloai-core -am test -Dtest=SubTaskDispatchServiceTest` → Tests run: 7, Failures: 0, Errors: 0, Skipped: 0

#### 5. 影响

- **行为变化**：子任务重分配最多尝试 5 次（可配置），达到后自动取消，不再无限重试
- **配置新增**：`helloai.dispatch.max-reassign-attempts`（默认 5，设为 0 禁用熔断）
- **DB 新增**：`sub_task.reassign_attempt_count` 列（V24 迁移）
- **接口影响**：对外 API 未变；CANCELLED 子任务在现有查询中自动过滤

#### 6. 遗留与下一步

- 熔断后手动恢复：当前需管理员从 DB 手动重置 `reassign_attempt_count=0` + `status=PENDING` 后再触发重分配；未来可考虑 UI 一键恢复
- 监控告警：建议在 `sub_task_cancelled`（reason=`reassign_attempt_exceeded`）事件上加钉钉/飞书通知
- **区分计数语义**：当前 `reassign_attempt_count` 对所有重分配类型统一计数；未来如需要区分（离线重派 vs N11回退），可扩展 `reassign_attempt_reason` 字段

---

### 6.9 M4.5 派发控制台：批量派发 API + 子任务时间线 + 5s 轮询可视化（2026-07-20）

#### 1. 范围

按 `doc/M4.5_派发控制台实施清单.md` 落地，填补"运营/调度人工快速把同一个子任务 fan-out 派给多个 EXECUTOR"链路最后一公里：

- **后端**：同内容 fan-out 创建子任务，避免前端 N 次串行调用
- **可视化**：子任务详情页加执行时间线，让操作员看到 claim / submit / review / blocked 等 timeline 事件不用直接查 DB
- **实时性**：详情页打开时 5s 轮询，进入终态后自动停止，避免人工刷
- **UI 入口**：子任务列表页"刷新"按钮旁加"快速派发"按钮打开新对话框

#### 2. 实际落地

##### 2.1 后端 API（helloai-api + helloai-core Service）

- `SubTaskController` 增两条端点：
  - `POST /api/sub-tasks/batch`（`createBatch`）：接 `List<CreateSubTaskRequest>`，逐项调现有 `create()` 装配 + 入库逻辑，单项失败 catch 隔离返回成功列表
  - `GET /api/sub-tasks/{id}/timeline`（`timeline`）：按 id 升序返回该子任务的 `TaskTimeline` 列表，映射到 `TaskTimelineItem` DTO
  - 顺手把原 `create()` 内的 DTO→Entity 装配抽出为 `toEntity()` 私有方法，避免 createBatch 重复粘代码（**复用优先原则**）
- `SubTaskService.createBatch(List<BatchCreateItem>)`：复用现有 `create(SubTask, Long)` 单建方法，单项 `try/catch` 隔离，返回成功列表
- `TaskTimelineService.listBySubTaskId(Long)`：新方法供 Controller 调用，按 id ASC
- 新建 DTO `helloai-api/.../dto/subtask/TaskTimelineItem.java`（与 V23 字段命名规范一致，eventType/role/agentId/payload/createTime）

##### 2.2 前端（M4.5 实施清单）

- 新建 `src/api/module.ts`：`moduleApi.list(taskId)` + `create(taskId, data)`，对应后端 `/api/tasks/{taskId}/modules`
- `src/api/task.ts` 新增 `create(data)`
- `src/api/subTask.ts` 新增 `createBatch(data)` + `timeline(id)` 两个方法；`CreateSubTaskPayload` 与 `TaskTimelineItem` 类型补到 `src/types/index.ts`
- 新建 `src/components/QuickDispatchDialog.vue`：
  - 字段：任务（可新建）/ 模块（可新建）/ 标题 / 描述 / 验收 / 优先级 / 执行 Agent（multiple，自动过滤 role=EXECUTOR 且 accessType=CLI_CLIENT）
  - 提交用 `Promise.allSettled` 逐项派发，汇聚报告"成功 N / 失败 M"，失败项列出 Agent 名 + 错误信息
- `views/subtask/SubTaskList.vue`：页头"刷新"按钮旁加 `<el-button type="primary" @click="dispatchVisible = true">快速派发</el-button>` + 挂载 `<QuickDispatchDialog v-model="dispatchVisible" @done="load" />`
- `views/subtask/SubTaskDetail.vue`：
  - 新增"执行时间线"卡片（el-card + el-timeline），数据源 `subTaskApi.timeline(id)`
  - 每条节点显示 eventType + role/agentId + fmtTime(createTime)，payload 用 `<el-collapse>` 折叠展示 JSON
  - **5s 轮询**：进入页面时启动；进入终态（DONE/CANCELLED）后停止；`onBeforeUnmount` clearInterval
  - eventType → el-tag 颜色映射（assigned/created→primary, completed/submitted/review→success, blocked/rejected/failed→danger, paused/warning→warning）

#### 3. 验证

- `npm run build`：TypeScript 类型检查 + 构建通过
- 后端：与 v2.6 §4.1 + V24 一并 `mvn test` reactor SUCCESS（SubTaskController 无 Mapper 注入、Controller 红线合规）

#### 4. 影响

- **接口新增**：`POST /api/sub-tasks/batch`、`GET /api/sub-tasks/{id}/timeline`
- **DTO 新增**：`TaskTimelineItem`（API 层，对应实体 `TaskTimeline`）
- **UI 新增**：`QuickDispatchDialog` + SubTaskList "快速派发"按钮 + SubTaskDetail 时间线卡片 + 5s 轮询
- **行为变化**：前端扇出派发从"前端 N 次串行调用"改为后端批量端点（同一语义，单项失败隔离）；子任务详情页自动刷新时间线（无需手动刷新）
- **遗留 DTO 字段**：`AgentOnboardingResponse.daemonScript` 仍未加，与 §6.8（EXECUTOR 常驻值班）合并到下轮 C 类一起补

#### 5. 遗留与下一步

- SubTaskDetail 轮询频率 5s 硬编码：未来可改为配置项 `helloai.ui.subtask-detail-poll-interval-ms`
- QuickDispatchDialog 列表里“（值班中）”标注为本轮 TODO（涉及 duty 接口联调），下轮补
- DTO 补字段：`AgentOnboardingResponse.daemonScript`（来自 §6.8 C 类遗留）、`TaskTimelineItem.payload` 改用强类型 V 各事件专属 DTO 而非 `Record<string, any>`

---

### 6.10 改派链路熔断收口 + 死信人工兜底（V25）（2026-07-28）

#### 1. 背景与问题确诊

真实 AI 联调中发现：手动指派子任务给在线外部 Agent 后，若该 Agent 未及时接收，系统自动降级改派存在三大旁路：

1. **无限改派旁路**：`dispatchPendingSubTaskAuto` 无 `checkReassignCircuitBreaker`，被 3 个定时任务（AgentHealthCheckTask 二次选人 / ExternalAgentFallbackTask.recoverPendingUnassigned / SubTaskPendingOrphanTask）每 60s 反复调用 → V24 熔断形同虚设
2. **误派窗口**：`ResilientDispatcher.assignNext` fast-fail 只查 DB `online_status`，不查心跳新鲜度 → 存在约 5-6 分钟“DB 仍 ONLINE 但 Agent 已死”的误派窗口
3. **无人工兜底**：V24 熔断后直接 CANCELLED（终态），无死信池、无人工恢复入口，只能手动改库

#### 2. 实际落地

- **V25 Flyway**：`V25__sub_task_dead_letter_status.sql` 重建 `chk_sub_task_status` CHECK 约束，加入 `DEAD_LETTER`
- **枚举/状态机**：`SubTaskStatus` 新增 `DEAD_LETTER`（非终态）；`SubTaskStateMachine` 新增流转 `PENDING/ASSIGNED/IN_PROGRESS/BLOCKED/REWORK → DEAD_LETTER`，`DEAD_LETTER → ASSIGNED（人工指派）/ CANCELLED（人工放弃）`
- **熔断收口**：`dispatchPendingSubTaskAuto` 入口顶部加 `checkReassignCircuitBreaker`，封堵三个定时任务的无计数旁路；`checkReassignCircuitBreaker` 达阈值后改置 `DEAD_LETTER`（原 CANCELLED），timeline 事件改 `sub_task_dead_letter`，context 写入 `dead_letter_reason=reassign_attempt_exceeded`；终态跳过判断加 `DEAD_LETTER`。手动链（`POST /sub-tasks` 带 assignedAgent、`claim`、`change-status`）有意不加拦截：人工判断优先
- **心跳新鲜度 fast-fail**：`AgentSelector.isHeartbeatFresh` 改 public 供复用；`ResilientDispatcher.assignNext` 在 SLEEPING/OFFLINE 判断后新增心跳新鲜度检查，不新鲜抛 `AgentUnavailableException` 走 fallback 选替代（API_KEY_LLM / WEB_BROWSER 在 isHeartbeatFresh 内部已豁免）；同时覆盖 `dispatchBlockedSubTask` 的 preferredAgentId 路径
- **死信人工兜底**：`SubTaskDispatchService.redispatchDeadLetter(subTaskId, agentId)`：校验 DEAD_LETTER → `resetReassignAttemptCount` 清零（SubTaskMapper 新增）→ `changeStatus → ASSIGNED`（自带 outbox + 收件箱 + 自动执行链）→ timeline `sub_task_dead_letter_manual_assign`；`SubTaskController` 新增 `POST /api/sub-tasks/dead-letter/redispatch/{id}`（复用 ReassignRequest）；死信列表复用现有列表接口按 `status=DEAD_LETTER` 过滤
- **ASSIGNED 超时阈值配置化**：`AgentDispatchProperties.assignedTimeoutMinutes`（默认 10），`AssignedSubTaskTimeoutTask` 删硬编码常量改读配置；`application.yml` 补 `helloai.dispatch.assigned-timeout-minutes: 10`
- **前端最小适配**：`types/index.ts` SubTaskStatus 联合类型 + 状态标签映射加 `DEAD_LETTER: 死信待人工/danger`；`SubTaskDetail.vue` `TERMINAL_STATUSES` 不加 DEAD_LETTER（可人工再指派，非终态）
- **口径说明**：AgentHealthCheckTask 首选+二次路径同轮各计 1 次属两次真实改派尝试，接受该口径（只会更快进死信）；不引入 RabbitMQ 层面 DLQ（死信是业务态）

#### 3. 测试与验证

- `SubTaskDispatchServiceTest` 新增 4 例：达阈值置 DEAD_LETTER 且不选人 / 未达阈值计数累加正常调度 / redispatchDeadLetter 清零+ASSIGNED / 非 DEAD_LETTER 报错（11/11 全绿）
- `ResilientDispatcherTest` 新增心跳陈旧 fast-fail 用例（setUp 默认桩 isHeartbeatFresh=true 保护既有用例）
- 新建 `SubTaskStateMachineTest`（5 例：DEAD_LETTER 进/出流转 + 非法流转 + 抽样回归）
- `AssignedSubTaskTimeoutTaskTest` 适配新构造器（注入 AgentDispatchProperties mock）
- 全量 `mvn test`：BUILD SUCCESS，helloai-core 226 + helloai-job 56 全绿无回归
- 新增 `scripts/powershell/verify-subtask-deadletter.ps1`：建子任务 → block → 连续 reassign 触发熔断计数 → 断言 DEAD_LETTER → 人工兜底接口 → 断言 ASSIGNED 且计数清零（需运行时环境，待真实环境回归）

#### 4. 影响

- **行为变化**：所有自动改派入口（含原旁路 dispatchPendingSubTaskAuto）统一受熔断管控；达阈值后进 DEAD_LETTER 死信池而非 CANCELLED，可人工恢复
- **接口新增**：`POST /api/sub-tasks/dead-letter/redispatch/{id}`
- **DB 变更**：V25 重建 CHECK 约束（加 DEAD_LETTER）
- **配置新增**：`helloai.dispatch.assigned-timeout-minutes`（默认 10）

#### 5. 遗留与下一步

- 死信管理 UI 页面（列表筛选 + 一键再指派）未做，当前复用列表接口 status=DEAD_LETTER 过滤 + API 兜底
- `verify-subtask-deadletter.ps1` 待真实环境实测回填结果
- 监控告警：建议在 `sub_task_dead_letter` 事件上加钉钉/飞书通知（沿用 V24 遗留项）
- NotificationConsumer ack 修复、消息信封统一不在本轮范围（后续单独处理）

---

### 6.11 任务-子任务关联打通 + 子任务列表真分页（2026-07-28）

#### 1. 背景与问题确诊

真实使用中发现任务管理页与子任务页"看起来没有关联"：`TaskList.vue` 跳转已携带 `/sub-tasks?taskId=行id`，`sub_task.task_id` 外键与后端 `?taskId=` 过滤能力也齐全，但断点在前端——`SubTaskList.vue` 不读 `route.query.taskId`、`subTask.ts` 参数类型缺 `taskId` 字段，导致过滤参数从未发出。顺带确诊两处次生问题：子任务列表为假分页（后端无分页参数、前端 `list.length` 当 total）；`SubTaskResponse` 无主任务标题，全量列表无法展示归属任务。

#### 2. 实际落地

- **前端关联打通**：`SubTaskList.vue` 读 `route.query.taskId`（LongId 保持 string 防精度丢）→ 列表过滤 + 顶部 `el-alert` 主任务信息条（标题 + 状态 tag + "查看全部子任务"清筛按钮）+ `watch(taskId)` 联动刷新；主任务查询失败降级显示 taskId 不阻断列表。`subTask.ts` list 参数补 `taskId?: LongId`；`task.ts` `getById` 参数 `number → LongId`
- **后端 §6.3 收口 + 分页**：`SubTaskService` 新增 `list(taskId, status, assignedAgentId, page, pageSize)` 返回 `IPage<SubTask>`（条件构造从 Controller 下沉；`page` 为 null/<=0 时全量包装成 Page，兼容 SKILL.md 外部 Agent 纯数组契约）；`SubTaskController.list` 删除内联 `LambdaQueryWrapper`，改 `R<?>` 双返回（不传 page 返回数组 / 传 page 返回 `PageResult`，同 `TaskController` 模式），新增 `page`/`pageSize` 参数
- **主任务标题回填**：`SubTaskResponse` 新增 `taskTitle` 冗余字段；Controller 注入 `TaskService`，新增 `attachTaskTitles`（`listByIds` 一次查询批量回填，防 N+1），list 与 getById 均回填
- **前端真分页**：`subTask.ts` list 改传 `page`/`pageSize` 返回 `PageResult<SubTask>`；`SubTaskList.vue` `load()` 取 `res.list`/`res.total`，`el-pagination` 绑 `currentPage`；顺手修掉模板 `@change="load"`/`@click="load"` 事件对象误传为 page 参数的隐患（改 `load(1)`/`load(currentPage)`）；`types/index.ts` `SubTask` 补 `taskTitle?: string | null`，全量视图表格加"所属任务"列（按 taskId 过滤时隐藏避免与信息条重复）
- **兼容性决策**：`GET /api/sub-tasks` 不传 page 保持纯数组返回，planner/patrol/reviewer 的 SKILL.md 契约零破坏；`/available`、`/mine` 的 §6.3 违规不在本轮范围

#### 3. 测试与验证

- 全 reactor `mvn -q -DskipTests install` → BUILD SUCCESS
- `mvn -pl helloai-core,helloai-api test` → **helloai-core 228 全绿 + BUILD SUCCESS**，无回归
- 前端 `npx vue-tsc -b --force` → 0 错误

#### 4. 影响

- **行为变化**：任务管理页"子任务"入口现在真正只展示该主任务的子任务并带信息条；子任务列表改服务端真分页；全量视图新增"所属任务"列
- **接口变化**：`GET /api/sub-tasks` 新增可选 `page`/`pageSize` 参数（传 page 返回 PageResult，不传保持数组，向后兼容）；`SubTaskResponse` 新增 `taskTitle` 字段
- **DB 变更**：无

#### 5. 遗留与下一步

- `/available`、`/mine` 两端点仍在 Controller 内联 QueryWrapper（§6.3 待收口清单，后续统一处理）
- `mine` / `available` 返回未回填 `taskTitle`（外部 Agent 场景暂无展示需求）

---

### 6.12 任务管理 CRUD 收口 + 级联删除（竞态免疫）（2026-07-28）

#### 1. 背景与问题确诊

任务管理页此前只有列表+子任务跳转，无新建/编辑/删除入口；且删除任务面临与消息链路的竞态风险：任务删除后，旧收件箱通知、在途 MQ 通知、残留执行记录可能让"已删任务"继续被 Agent 消费或幽灵执行。设计原则沿用"消息只是门铃、DB 是唯一事实源"：级联**物理删除**后所有消费端（claimSubTask / submitResult / LocalExecutionCommandConsumer / handleReport / Poller）实时回查 DB 均得 not_found 直接丢弃，与现有防线天然兼容；唯一缺口是"删除瞬间在途的 MQ 通知落库成孤儿 inbox"，在 NotificationConsumer 补防御分支兜底。

#### 2. 实际落地

- **Mapper 物理删除 SQL**（`@TableLogic` 软删陷阱规避，全部 `@Delete` 注解 + 显式 `physicalDeleteXxx` 命名 + Javadoc 标注"仅供任务级联删除使用"）：`TaskMapper.physicalDeleteById`、`SubTaskMapper.physicalDeleteByTaskId`、`ModuleMapper.physicalDeleteByTaskId`、`TaskTimelineMapper.physicalDeleteByTaskId`、`ReviewRecordMapper.physicalDeleteByTaskId + countByTaskId`、`AgentExecutionRecordMapper.physicalDeleteByTaskId + countByTaskId`、`AgentInboxMapper.physicalDeleteByTaskRef + countUnreadByTaskRef`（ref 三段 OR：task 直引 / sub_task 子查询 / review 双层子查询）
- **TaskService 下沉三方法**（按 AgentService 惯例直接注入 7 个 Mapper 防循环依赖，AgentInboxService 作无回向依赖叶子服务注入复用门铃链路）：
  - `getRelatedCounts(taskId)`：子任务/在途(ASSIGNED+IN_PROGRESS)/死信/模块/审查/执行记录/未读收件箱/时间线 计数
  - `deleteTaskCascade(taskId, confirmTitle)`：标题精确匹配校验（照 Agent confirmName 范式）→ `@Transactional` 内按序物理删 inbox→execution→review→timeline→sub_task→module→task（前三者 SQL 依赖 sub_task 子查询，必须先删）→ 返回删除前 counts 回显
  - `republish(taskId)`：DONE 抛 BizException；重置 PENDING；新 eventId `task.republish.{id}.{ts}` 通知全部 PLANNER（`(event_id,agent_id)` 唯一约束不与历史冲突）；**不触碰已有子任务**
- **TaskController 三端点**：`POST /{id}/republish`、`GET /{id}/related-counts`（新 DTO `TaskRelatedCounts`）、`DELETE /{id}`（body 传 `confirmTitle`，空值 fail 提示）
- **NotificationConsumer 防御分支**：写 inbox 前 `refTargetExists(refType, refId)` 回查（task/sub_task/review → getById != null），目标已删则 log.info 丢弃——兜底"任务已删、在途 MQ 通知还在飞"窗口
- **前端**：`types/index.ts` 新增 `TaskRelatedCounts` 接口；`task.ts` 补 `update/republish/relatedCounts/deleteTask`（delete 走 `{ data: { confirmTitle } }` body）；新建 `TaskFormDialog.vue`（新建/编辑共用，title 必填）+ `TaskDeleteDialog.vue`（照 AgentDeleteDialog 范式：@open 加载影响面统计、activeSubTaskCount>0 显示"丢弃在途执行结果"警示、输入标题精确匹配激活危险按钮）；`TaskList.vue` header 加"新建"、操作列加 编辑/重新发布（DONE 禁用 + ElMessageBox 确认）/删除
- **拍板口径**：重新发布不动子任务只重置+重通知，DONE 不允许重发；task_timeline 随任务一起物理删

#### 3. 测试与验证

- 全 reactor `mvn -q -DskipTests install` → 无 ERROR
- `mvn -pl helloai-core,helloai-api,helloai-job test` → **56 测试全绿 + BUILD SUCCESS**，无回归
- 前端 `npx vue-tsc --noEmit` → 0 错误

#### 4. 影响

- **接口新增**：`POST /api/tasks/{id}/republish`、`GET /api/tasks/{id}/related-counts`、`DELETE /api/tasks/{id}`
- **行为变化**：任务删除为**物理级联删除**（子任务含死信/模块/审查/执行记录/收件箱引用/时间线一并清理），不走 `@TableLogic` 软删；MQ 通知消费前回查目标存在性
- **DTO 新增**：`TaskRelatedCounts`（API 层）
- **DB 变更**：无（纯应用层 SQL）

#### 5. 遗留与下一步

- 改派竞态 4 个已识别漏洞未修（本轮范围外，候选下轮）：①改派入口不作废旧 inbox 通知 ②`ExecutionResultHandler.handleReport` 不校验 report.agentId==assignedAgentId ③改派不取消旧 PENDING 执行记录 ④同 Agent 再改派 Poller 重放旧命令
- 删除操作无操作人审计（当前无登录体系，后续补）

---

### 6.13 值班租约列表改 Agent 维度展示 + 历史记录分页对话框（2026-07-28）

#### 1. 背景

值班租约页此前平铺展示全部租约记录，同一 Agent 反复 checkIn 产生大量历史行，运营难以一眼看清"每个 Agent 当前值班状态"。改为 Agent 维度展开：每个 Agent 一行只显最新租约 + 租约总数，点"更多"弹窗分页查看该 Agent 全部历史，Agent 维度主列表也分页。

#### 2. 实际落地

- **Mapper**：`AgentDutyLeaseMapper` 新增 `selectLatestPerAgent(offset,size)`（PostgreSQL `DISTINCT ON (agent_id)` 按 start_time 倒序取组内最新，JOIN 子查询带出 lease_count，外层按最新租约开始时间倒序）+ `countDistinctAgents()`；查询行对象 `AgentDutyLeaseLatestRow extends AgentDutyLease`（非表实体，冒余 leaseCount）
- **Service**：`AgentDutyLeaseService.listLatestPerAgent(pageNum,pageSize)`——自定义 SQL 非 MP 分页插件链路，手工拼 Page（count + offset 查询）
- **Controller**：`GET /api/admin/duty-leases/by-agent`（page/size）返回 `PageResult<DutyAgentLatestResponse>`（extends DutyLeaseResponse + leaseCount），agentName 批量回填防 N+1；"查某 Agent 全部记录"复用既有 list 端点的 agentId 过滤 + 分页，未新建端点
- **前端**：`types/duty.ts` 加 `DutyAgentLatestResponse`；`api/duty.ts` 加 `listByAgent`，顺手修 `list.agentId` 参数类型 `number → LongId`（雪花 ID 传 number 有精度丢失隐患）；新建 `DutyLeaseHistoryDialog.vue`（单 Agent 历史租约分页表，pageSize=10）；`DutyLeaseList.vue` 重写为 Agent 维度主表（Agent/最新状态/最新会话/模式/并发/三时间/租约总数/更多），原 status/agentId 平铺过滤区随平铺视图一并移除（历史对话框内可见全部状态）
- **兼容性**：既有 `GET /admin/duty-leases`（平铺分页）与 `/overview` 端点零改动，Dashboard 概览卡片不受影响

#### 3. 测试与验证

- 全 reactor `mvn -q -DskipTests install` → 无 ERROR；`mvn -pl helloai-core,helloai-api test` → **helloai-core 228 全绿 + BUILD SUCCESS**
- 前端 `npx vue-tsc --noEmit` → 0 错误
- DISTINCT ON SQL 经 postgres_helloai MCP 真库验证：qoder-ceshi（8 条租约）正确返回最新一条 + lease_count=8

#### 4. 影响

- **接口新增**：`GET /api/admin/duty-leases/by-agent`
- **行为变化**：值班租约页主视图从租约平铺改为 Agent 维度；历史记录入口下沉到每行"更多"对话框
- **DTO 新增**：`DutyAgentLatestResponse`（API 层）、`AgentDutyLeaseLatestRow`（core 查询视图行）
- **DB 变更**：无

#### 5. 遗留与下一步

- Agent 维度主列表暂无状态过滤（如需"只看值班中 Agent"可在 by-agent SQL 外层加 status 条件，待需求明确后补）

---

### 6.14 全站暗色主题统一（登录页 + 后台，2026-07-22）

#### 1. 背景

登录页此前已改造为深蓝星空 + 玻璃拟态暗色风格（.login-page 局部 token 覆盖），但登录后后台仍为亮色主题，前后视觉割裂。经用户确认采用"全站永久暗色 + 深蓝底实心卡片"方案（不做亮/暗切换，后台不用玻璃拟态保表格可读性）。

#### 2. 实际落地

- **design-system.css（主战场）**：`:root` 亮色 token 整体替换为登录页同调性深蓝暗色值（bg #0A0E1A / surface #0F1524 / elevated #121828 / border #242D47 / ink #EEF2F8）；删除旧 `prefers-color-scheme: dark` 媒体块（#131417 中性灰色板弃用），其 EP 补丁提升为常规规则；新增 `html.dark` 段将 EP 官方 dark 变量（--el-bg-color 系）对齐项目深蓝色板；Tag 四色文字换暗色可读变体（#34D399/#FBBF24/#F87171/#60A5FA）；阴影改黑色系 + 弱紫光晕
- **基础设施**：index.html `<html>` 加 `class="dark"`；main.ts 引入 `element-plus/theme-chalk/dark/css-vars.css` 兜底 message/notification/popper 等 append-to-body 弹层
- **MainLayout.vue**：侧边栏从亮紫→青渐变改为深蓝渐变（#0D1220→#141B33→#0E2233，保留极光动画/网格/青色光斑）；菜单选中态改品牌紫实底 + 紫色投影；头像底色改紫色半透明
- **硬编码残留清理**：SubTaskDetail.vue（#909399×2、#f5f7fa 改 token）、QuickDispatchDialog.vue（#909399 改 token）；AgentList.vue 的 #fff 在紫色实底上保留
- **Login.vue 零改动**：局部 token 覆盖与新全局暗色值同调性，自然兼容

#### 3. 测试与验证

- `npm run build`（vue-tsc + vite）→ 0 错误
- 浏览器实测（admin 登录后样式探针）：Dashboard（body #0A0E1A/卡片 #121828/文字 #EEF2F8）、任务列表（表头 #0F1524）、新建弹窗（弹窗 #121828/输入框 #0F1524/标签 #A9B4C7）、侧边栏深蓝渐变 + 紫色选中态均生效
- 登录页回归：星空 Canvas、玻璃卡片 rgba(18,24,40,0.6) + blur(20px)、tab 样式均未受影响

#### 4. 影响

- **行为变化**：全站（登录页 + 后台）统一为深蓝暗色主题，无亮色模式；原"登录页暗色 + 后台亮色"分层策略废弃
- **接口/DB 变更**：无（纯前端样式层）

#### 5. 遗留与下一步

- ECharts 图表仅初始化时读取 cssVar，若未来引入主题切换需补重绘监听
- 如需恢复亮色或加切换开关，需把 :root 暗色值回迁至 html.dark 作用域并补开关逻辑

---

### 6.15 打卡上班语义改造（值班租约改名 + Agent 注册态文案，2026-07-22）

#### 1. 背景

用户要求两项语义重命名：①“值班租约”改为“打卡上班”，状态一一对应 ACTIVE→在线、EXPIRED→超时、CLOSED→下班；②Agent 注册状态改为“已注册/已注销”，消除“活跃”文案对“注册=在线”的误导（AgentStatus 本就是管理态，与 onlineStatus 在线监测双轨分离，一键注册链路现状本就不含在线监测）。经确认采用“界面语义层改造”：仅改文案与对外文档术语，后端枚举字符串（ACTIVE/CLOSED/EXPIRED、ACTIVE/DISABLED）、DB、MCP 协议契约零改动。

#### 2. 实际落地

- **打卡上班前端**：`types/duty.ts` DUTY_LEASE_STATUS_MAP 改为 在线(success)/下班(info)/超时(warning)；`MainLayout.vue` 菜单与 `router/index.ts` title 改“打卡上班”（路由路径 /duty-leases 不变）；`DutyLeaseList.vue`（标题/列名/empty-text）、`DutyLeaseHistoryDialog.vue`（标题“打卡记录”/列名）、`Dashboard.vue`（“Agent 打卡概览”卡片四标签 在线/下班/超时/打卡总数）、`api/duty.ts` 注释同步
- **Agent 注册态前端**：`AgentDetail.vue`/`AgentCard.vue` 状态文案“活跃/已禁用”→“已注册/已注销”，操作按钮“禁用/启用”→“注销/恢复注册”；`AgentStatusDialog.vue` 弹窗标题/确认文案/成功消息全套同步；`AgentOnboardingDialog.vue`“常驻值班脚本/进程”→“常驻打卡”
- **后端对外文档（不改逻辑）**：`McpMcpServer.java` checkIn/checkOut 的 @Tool description “值班租约/值班态”→打卡术语；`skills/executor/SKILL.md` 全文 11 处“值班”字样统一为打卡术语（机制描述与枚举值 ACTIVE/CLOSED/EXPIRED 原样保留）
- **明确不做**：枚举 `AgentDutyLeaseStatus`/`AgentStatus` 及其字符串值、Flyway 迁移、API/路由路径、MCP 工具名、心跳/在线监测/AgentSelector/注册链路逻辑均零改动

#### 3. 测试与验证

- `npm run build`（vue-tsc + vite）→ 0 错误；`mvn -pl helloai-core -am compile` → BUILD SUCCESS
- 浏览器实测（localhost:5174 探针）：打卡上班页（菜单/标题/列名/真实 EXPIRED 数据显示“超时”标签）、Dashboard 打卡概览四标签、Agent 卡片（状态点“已注册”/按钮“注销”）、注销弹窗（标题/文案/“确认注销”）全部生效

#### 4. 影响

- **行为变化**：纯展示层语义更名，无任何接口/调度/数据行为变化；已接入的外部 Agent 不受影响
- **接口/DB 变更**：无

#### 5. 遗留与下一步

- 若未来需要枚举值与新语义完全对齐（如租约 ACTIVE→ONLINE、Agent ACTIVE→REGISTERED），需 Flyway 迁移翻写存量 + CHECK 约束/部分唯一索引同步 + 外部 Agent SKILL 文档同步，属协议级变更需单独立项
- 已下发给外部 Agent 的旧版 hello_ai_skills.md 仍含“值班”旧术语，重新生成接入内容即可刷新

---

### 6.16 Planner 平台内自动拆解闭环（V26，2026-07-28）

#### 1. 背景

Planner 角色此前只有枚举定义与一份约 60 行的纯 REST 版 SKILL.md，既无平台内自动拆解能力，也无外部 Planner 接入的完整说明书。结合参考项目 AgentTeams-main（拆解→确认→分发的交互范式）与 openMoss（task-planner 拆分四要素/防重复拆分/排障六步闭环）分析后，确定在不新增基础设施的前提下补齐“需求 → LLM 自动拆解 → 用户确认/拒绝 → 进入既有分发链”闭环；原差距表 §5 7b“场景 1~3 全绿前不启动 planner 编排层”门禁按用户决策提前解除（拆解链与执行链经草案态硬隔离，缺陷可独立定位）。

#### 2. 实际落地

- **领域模型（helloai-common）**：`SubTaskStatus` 新增 `PENDING_PLAN_REVIEW` 草案态，状态机仅允许 → `PENDING`（确认转正）/ `CANCELLED`（拒绝），任何状态不可转入草案态（只能由拆解落库产生）；`TaskStatus` 新增 `PLANNING`（拆解进行中，防重复触发）；Flyway `V26__planner_plan_review_status.sql` 重建 `chk_sub_task_status` / `chk_task_status` CHECK 约束纳入新值
- **旁路排查结论（全链路安全）**：`claimSubTask`/`assignNext`/各 redispatch/`ExecutionCompensationTask`/`SubTaskPendingOrphanTask`/XML mapper/dashboard 统计均精确匹配既有状态枚举，`PENDING_PLAN_REVIEW` 对 claim/分发/超时回收/统计天然不可见，无需任何防御性修改
- **Prompt 模板**：`helloai-core/resources/prompts/planner-decompose.md`，移植 openMoss 拆分四要素（目标/交付物/验收标准/优先级），要求输出严格 JSON 数组（title/content/deliverable/acceptance/priority），限定 3~10 条
- **`PlannerAnalysisService`（新增 core/planner 包，编排收口 core 对齐 §6.3）**：`decompose`（校验 Task PENDING + 已存在非 CANCELLED 子任务拒绝 + CAS `lambdaUpdate().eq(status,PENDING).set(status,PLANNING)` 防并发 + `AgentSelector.pickPreferred(PLANNER)` 选 API_KEY_LLM Agent（首选非 LLM 时回退候选列表筛选）+ `PlatformAgentExecutionService.executeSync` 调 LLM + strip markdown fence 容错解析 + 逐条校验必填/数量上限/priority 归一化 + 事务内 saveBatch 草案 + timeline `task_plan_generated`；失败路径 CAS 回退 PENDING + `task_plan_failed` 携 LLM 原始错误）/ `listDrafts` / `confirmPlan`（草案逐条 changeStatus → PENDING，Task → IN_PROGRESS，按 `autoAssignOnCreate` 逐条 `dispatchPendingSubTaskAuto`，与手工创建子任务分发路径完全同构）/ `rejectPlan`（草案翻 CANCELLED 保留审计，Task 回退 PENDING 可重新拆解）
- **API 入口（helloai-api）**：`TaskController` 四个薄入口（只转发不含编排）：`POST /api/tasks/{id}/plan`、`GET /api/tasks/{id}/plan`、`POST /api/tasks/{id}/plan/confirm`、`POST /api/tasks/{id}/plan/reject`
- **外部 Planner SKILL.md 升级**：`skills/planner/SKILL.md` 重写（约 60 行 → 347 行），对齐 executor 版结构（MCP 四步握手/checkIn 租约/门铃/常驻打卡三件套/退出剧本/错误码速查 + REST 兜底），新增 Planner 专属工作流（每次唤醒固定流程：查收件箱 → blocked 六步排障闭环 → 进度监控 → 为 PENDING 子任务指派 → 全 DONE 收尾）+ 拆分四要素质量标准 + 防重复拆分原则
- **明确不做**：前端规划确认页、planner 专用 MCP 工具（decomposePlan 保持演进项）、子任务依赖 DAG；不改 `SubTaskAutoExecutionDispatcher` 的 accessType 过滤（执行面语义，与拆解无关）

#### 3. 测试与验证

- `PlannerAnalysisServiceTest` 13 用例（正常拆解含 fence 容错 + priority 归一化 / 首选非 LLM 回退候选 / JSON 解析失败回退 / LLM 失败 / 非 PENDING 拒绝 / 已有子任务拒绝 / CAS 失败 / 无 Planner Agent / confirm 含开关 autoAssign 两路 / confirm・reject 非法态 / reject 流转），项目内首例 `lambdaQuery`/`lambdaUpdate` 链式 mock（直接 mock 链包装类 + `lenient()` 兜底 stub）；`SubTaskStateMachineTest` 补 V26 三用例（草案态仅可转 PENDING/CANCELLED、任何状态不可转入、非法转换抛 BizException）
- `mvn -pl helloai-core -am test` → **helloai-core 244 全绿 BUILD SUCCESS**；全模块 `mvn compile` → 无 ERROR
- **多模块 SNAPSHOT 教训**：不带 `-am` 单跑 helloai-core 测试时报 `NoSuchFieldError: PENDING_PLAN_REVIEW`（本地仓库 helloai-common 快照是旧版），须先 `mvn -pl helloai-common install -DskipTests` 再跑；以后改动 common 枚举/实体后单模块测试前必须先 install common
- 端到端脚本 `scripts/powershell/verify-planner-decompose.ps1`（12 步：登录 → 注册 PLANNER Agent → confirm 路径（草案 PENDING_PLAN_REVIEW + Task PLANNING → 转正 PENDING/ASSIGNED + Task IN_PROGRESS）→ reject 路径（cancelledCount + Task 回退 PENDING）→ 重复拆解拒绝），遵循 D8 规则（UTF-8 编码头 + runtime 字面量纯 ASCII），`Parser.ParseFile` 自检 PS-SYNTAX-OK；**待真实环境（6565 + 可用 deepseek Provider）实测**

#### 4. 影响

- **接口新增**：`POST/GET /api/tasks/{id}/plan`、`POST /api/tasks/{id}/plan/confirm`、`POST /api/tasks/{id}/plan/reject`
- **DB 变更**：V26 重建 `sub_task`/`task` 两张表 CHECK 约束（纳入 `PENDING_PLAN_REVIEW`/`PLANNING`），无新表无新列
- **行为变化**：Task 新增 PLANNING 中间态；确认前草案子任务对整个调度/补偿/统计链不可见；既有手工创建子任务、Executor 执行链路零改动
- **文档**：差距表新增 N16（已交付）+ §5 7b 门禁解除说明 + §6 治理结论条目

#### 5. 遗留与下一步

- `verify-planner-decompose.ps1` 待真实环境回归（需 helloai-start 运行 + deepseek Provider 可用）
- 前端规划确认页（草案列表 + 确认/拒绝按钮）后续独立迭代，本轮以 REST + 验证脚本闭环
- planner 专用 MCP 工具 `decomposePlan`（`AgentMcpServerService` 注释预留）、子任务依赖 DAG 编排、循环任务保持演进项

---

### 6.17 管理员会话 Redis 化（修复后端重启前端掉线，2026-07-28）

#### 1. 背景

管理员登录态（X-Admin-Token）此前只存在 `AuthService` 实例字段的 `ConcurrentHashMap` 里：后端重启内存清空 → 下一次请求 401 → 前端 `request.ts` 拦截器清 sessionStorage 强制踢回登录页；且 token 无 TTL，进程存活期间永久有效（安全隐患）。用户确认方案：会话态迁 Redis，登录动作本身仍查 DB + BCrypt 不变；Redis 不可用时直接拒绝（Redis 已是心跳/MQ 幂等的强依赖，不做内存降级）。

#### 2. 实际落地

- **`AuthService`（helloai-core/system）**：内存 `adminTokens` Map 删除，改存 Redis（key `auth:admin:token:{token}`，对齐 `agent:heartbeat:`/`mq:dedup:` 命名风格；value 为 `AdminSession` JSON，专用 `new ObjectMapper()` 不复用全局 Bean 避免 Long→String 定制策略干扰；TTL 8 小时）
- **滑动续期**：`validateAdminToken` 命中后 `redis.expire(key, TTL)` 重置 8h，活跃会话不会使用中途过期；未命中抛 401；缓存值损坏（序列化格式变更/脏数据）则清 key + 401 强制重登
- **登出/改密**：`adminLogout` 改为 Redis delete（改密踢会话链路复用同一方法，行为不变）
- **契约零变更**：token 生成方式（SecureRandom 32 字节 hex）、`AuthInterceptor`/`McpAuthFilter`/前端/验证脚本均无需改动
- **明确不做**：Agent apiKey 的 Redis 短缓存（仍每请求直查 DB，非掉线问题，纯优化项保持演进）；MCP SESSION_AUTH 保持内存 + SessionAuthCleaner（绑定 SSE 长连接 sessionId，重启后连接本身就断，存 Redis 无意义）

#### 3. 测试与验证

- 新增 `AuthServiceTest` 9 用例（登录写 Redis key/TTL 断言 / 密码错不写 Redis / 用户不存在 / 校验命中滑动续期 / 未命中 401 / 损坏值清 key+401 / 登出删 key / agentKey 无效 401 / 禁用 403），Mockito mock `StringRedisTemplate`+`ValueOperations`（对齐 `HeartbeatServiceActiveTest` 范式），全绿
- `mvn -pl helloai-core -am test -Dtest=AuthServiceTest` 通过；全模块 `mvn compile` 无 ERROR
- 坑位：PowerShell 下 `-Dsurefire.failIfNoSpecifiedTests=false` 带点号的 -D 参数会被拆分，必须整体加引号

#### 4. 影响

- **行为变化**：后端重启后管理员会话不再丢失；会话新增 8h 滑动过期（此前永不过期）；Redis 不可用时鉴权报错（新增强依赖，与项目 Redis 定位一致）
- **接口/DB/前端**：零变更

#### 5. 遗留与下一步

- Agent apiKey 校验的 Redis 短缓存（TTL 5min + 禁用时主动失效）保持演进项
- TTL 8h 目前为代码常量，如需环境差异化再外置 `application.yml`

---

### 6.18 任务级联删除 FK 违反修复 + 拆解链前端补全（2026-07-29）

#### 1. 背景

两个诉求合并一轮交付：

- **FK 违反 bug（用户真实环境报错）**：删除带附件子任务的任务时抛 `PSQLException: update or delete on table "sub_task" violates foreign key constraint "attachment_sub_task_id_fkey"`。取证结论：`V1__init_all.sql` 中 FK 引用 `sub_task(id)` 的表共 6 张（review_record L265、patrol_record L381、conversation_archive L541、attachment L578、agent_execution_record L627、conversation_message L828），而 §6.12 的 `TaskService.deleteTaskCascade` 只删了 execution/review 两张，遗漏 4 张——只要子任务有附件/巡检/会话数据，删任务必炸
- **拆解链前端断链（N16 遗留收口）**：V26 后端四接口齐全，但前端触发拆解/查看草案/确认拒绝一步都没接，只能靠 API/脚本操作

#### 2. 实际落地

**阶段 0：FK 违反修复（helloai-core）**

- `AttachmentMapper` / `PatrolRecordMapper` / `ConversationArchiveMapper` / `ConversationMessageMapper` 各补 `physicalDeleteByTaskId`，照 `ReviewRecordMapper` 既有范式：`@Delete("DELETE FROM {table} WHERE sub_task_id IN (SELECT id FROM sub_task WHERE task_id = #{taskId})")` + Javadoc 标注仅供任务级联删除使用；attachment 的 `sub_task_id` 可空，IN 子查询天然只删关联行不碰游离附件
- `TaskService.deleteTaskCascade`：新增 4 个 Mapper 构造注入，在 `subTaskMapper.physicalDeleteByTaskId` 之前依次调用 4 个新删除，同步更新方法 Javadoc 删除顺序说明
- `getRelatedCounts`/`TaskRelatedCounts` DTO/前端删除弹窗不动（影响面统计字段扩展非本 bug 范围，保持修复原子性）

**拆解链前端补全（纯 helloai-ui，后端零改动）**

- `types/index.ts`：`SubTaskStatus` 补 `PENDING_PLAN_REVIEW` + `SUB_TASK_STATUS_MAP` 补"草案待审"（顺带修复全量子任务列表遇草案态 tag 取 undefined 的隐患）；`TaskStatus` 补 `PLANNING`；新增 `TASK_STATUS_MAP` 五态中文映射；`SubTask` 接口补 `deliverable`/`acceptance`/`priority` 三个可选字段（后端已返回、前端类型缺失）
- `api/task.ts`：`taskApi` 新增 `plan`（POST /tasks/{id}/plan，单请求覆盖 `timeout: 120_000`，LLM 拆解耗时超全局 30s）/ `planDrafts` / `confirmPlan` / `rejectPlan` 四方法
- 新组件 `views/task/components/PlanReviewDialog.vue`（照 TaskDeleteDialog 对话框范式）：`@open` 拉草案；表格列序号/标题/内容/交付物/验收标准/优先级 tag/依赖（`dependsOn` 草案 id 映射为表内序号展示如"依赖 #1,#2"）；footer 双动作"确认并分发"（ElMessageBox 二次确认 → confirmPlan → emit done）与"拒绝重拆"（确认 → rejectPlan 回显 cancelledCount）；空草案 el-empty 兜底引导拒绝重拆
- `TaskList.vue`：状态列废弃 `DONE?'success':'warning'` 三元硬编码改 `TASK_STATUS_MAP`；操作列按状态显示——PENDING 态"AI 拆解"（确认提示约需几十秒 → 按钮 loading → 成功后刷新并直接开审阅弹窗）、PLANNING 态"审阅草案"；"已存在子任务""并发拆解中"等错误由后端 BizException + 拦截器统一弹错，前端不重复防御

#### 3. 测试与验证

- `mvn -pl helloai-core -am test`（JDK 17）→ 全绿 BUILD SUCCESS（无 deleteTaskCascade 既有单测，无直接构造 TaskService 的测试，构造器变更零破坏）
- `npx vue-tsc --noEmit` 0 错；`npm run build` 通过（chunk 体积警告为既有问题）
- ~~未完成：浏览器闭环实测（新建→拆解→审阅→确认/拒绝）与带附件子任务的删除回归——本轮 6565 后端未启动~~ → 2026-07-29 同日补验（真实环境 6565 + deepseek）：
  - **脚本回归**：`verify-planner-decompose.ps1` 等价迁移为 macOS zsh 版 `scripts/shell/verify-planner-decompose.sh`（curl+jq，照 verify-dashboard-duty-leases.sh 模板规范），真实环境 e2e 12 步全绿——confirm 路径拆解 5 条草案全 PENDING_PLAN_REVIEW → Task PLANNING → 确认转正 PENDING/ASSIGNED + Task IN_PROGRESS + 草案清零；reject 路径 cancelledCount 匹配 + Task 回退 PENDING；对 IN_PROGRESS 任务重复拆解被拒
  - **迁移坑位**：zsh `status` 为只读内置变量（局部变量改名 st）；原 ps1 缺凭证绑定步骤——新注册 planner Agent 无 deepseek 托管凭证时拆解必 500「Agent 未配置启用态托管凭证」，zsh 版补 STEP2.1 绑定 `/api/credentials/agents/{id}/api-key`（env `DEEPSEEK_API_KEY` 优先，缺省回退 application.yml 默认 key，对齐 verify-inner-loop-e2e.ps1 做法）
  - **浏览器 UI 闭环实测通过**：confirm 路径 `ui-e2e-confirm-01` 拆解 6 条草案（含合理优先级与拓扑依赖）→ 审阅弹窗自动打开 → 确认分发后任务「进行中」；reject 路径 `ui-e2e-reject-01` 4 条草案 → 拒绝重拆 → 回「待规划」且可重拆；拆解期间状态「拆解中」+ 行内「审阅草案」按钮可随时重开弹窗；全程 console 0 error
  - 仍未覆盖：带附件子任务的删除回归（需造带附件数据，见 §5 遗留）

#### 4. 影响

- **接口/DB**：零变更（纯 Mapper 方法新增 + 前端接线）
- **行为变化**：删任务不再因子任务带附件/巡检/会话数据而炸 FK；任务列表可视化完成"新建 → AI 拆解 → 草案审阅 → 确认/拒绝 → 跟踪执行"全链，N16"前端规划确认页"遗留项收口（以列表内对话框形态交付，非独立页面）

#### 5. 遗留与下一步

- **孤儿文件**：attachment 行删除后对象存储里的物理文件（bucketName/objectKey）成为孤儿，文件清理需单独立项，与本次 DB 完整性修复解耦
- ~~浏览器闭环实测 + `verify-planner-decompose.ps1` 回归待真实环境（6565 后端 + deepseek Provider 可用）~~ → 2026-07-29 同日收口（zsh 版脚本 e2e 12 步全绿 + UI 闭环实测通过，见 §3）；带附件子任务删除回归仍待造数验证
- `dependsOn` 在草案审阅中只读展示不可编辑（依赖编辑属演进项）；第二步"对话式需求澄清窗口"已有概要设计，建议本轮验收后单独立项

---

### 6.19 对话式需求澄清窗口（V29，2026-07-29）

#### 1. 背景

§6.18 收口后拆解链已可视化，但入口仍要求用户一次性写清需求——模糊想法没有承接面。本轮落地"第二步立项"：用户带着半成品想法进对话窗口，LLM 扮演资深需求分析师多轮追问澄清（边界/交付物/验收标准），信息足够即产出终稿，一键创建任务并顺路自动拆解，与 §6.16/§6.18 的拆解审阅链无缝衔接。用户已拍板：独立页面交付（非 TaskList 内嵌）、终稿确认后前端自动调既有 plan 接口。

#### 2. 实际落地

**DB（Flyway V29__requirement_clarify.sql）**

- `requirement_conversation`：title（首条用户消息截断 50 字）/ status `ACTIVE/FINALIZED/ABANDONED` + CHECK / `task_id` **软引用无 FK**（刻意不加入 `deleteTaskCascade` 的 FK 引用面，删任务后允许悬挂，注释注明）/ final_title + final_description（LLM 最近一次终稿暂存，等用户确认）/ round_count（用户消息轮数）；partial 索引 `(status, create_time) WHERE deleted=0`
- `requirement_message`：conversation_id FK / role CHECK `user/assistant` / content / seq；索引 `(conversation_id, seq)`。两表均照 `V19__agent_command_outbox.sql` 范例：BIGINT 应用侧雪花主键 + 审计列全套 + `update_update_time_column` 触发器 + 逐列 COMMENT

**后端（helloai-core + helloai-api）**

- `core/planner/` 新增 entity（RequirementConversation/RequirementMessage，继承 BaseEntity）+ mapper（两个空 BaseMapper，`HelloAIApplication` @MapperScan 补第四包 `com.helloai.core.planner.mapper`）+ 薄 CRUD `RequirementConversationService`（空 ServiceImpl）/ `RequirementMessageService`（`addMessage` 查最大 seq+1 落库，照 ConversationService.addMessage 范式但不需要 REQUIRES_NEW）
- `RequirementClarifyService`（编排收口 core，**完整复用 PlannerAnalysisService 五段式**，类不加事务——LLM 耗时不占 DB 事务）：`create`（截断标题建会话 → 走一轮）/ `sendMessage`（requireActive + 轮数上限 20 校验 → doRound：存 user 消息 + round_count+1 → `pickPlannerAgent`（12 行刻意复制不抽象，注释注明）→ transcript 渲染 `prompts/requirement-clarify.md`（占位符 `{{CONVERSATION_HISTORY}}`，`用户：/助手：` 逐行拼接）→ executeSync（context 带 conversationId + scene=requirement_clarify）→ `stripToJsonObject`（照 stripToJsonArray 改花括号版）+ Jackson 解析 `ClarifyReply`——type=question 存 assistant 消息；type=final 存 assistant 消息（空则「已生成终稿」）+ final_title/final_description 回填会话行；LLM/解析失败 user 消息保留、抛 BizException 可重发）/ `finalize`（校验 ACTIVE + 终稿非空 → 建 Task PENDING + best-effort 通知全部 PLANNER 写收件箱（照 TaskController.create 通知段搬 core）→ 会话回填 task_id + FINALIZED → timeline `task_created_from_clarify`）/ `abandon` / `listConversations`（create_time 倒序 LIMIT 50）/ `detail`
- LLM 输出协议（严格 JSON 单对象禁围栏）：追问 `{"type":"question","message":...}`；终稿 `{"type":"final","title":"50字内","description":"结构化需求","message":"终稿说明"}`；Prompt 引导每轮最多 3 问、信息足够即出终稿
- `RequirementConversationController`（`/api/requirement-conversations` 六薄端点：POST / 创建、POST /{id}/messages、GET / 列表、GET /{id} 详情、POST /{id}/finalize、POST /{id}/abandon）+ `ClarifyMessageRequest` DTO（@NotBlank）

**前端（helloai-ui）**

- `types/index.ts` 补 RequirementConversationStatus/RequirementConversation/RequirementMessage/ClarifyConversationDetail；`api/clarify.ts` 六方法（create/send 单请求 `timeout: 120_000` 照 taskApi.plan 范式）
- 新页面 `views/requirement/RequirementChat.vue`：左栏会话列表（新会话按钮 + ABANDONED 置灰）；右栏气泡流（user 右 `--ha-primary-muted` / assistant 左 `--ha-surface-elevated`，全走 design-system 变量）+ 发送中 loading 占位气泡 + Enter 发送；会话有 final_title 即渲染终稿卡片（标题+描述只读，不满意继续对话让 LLM 修正）——ACTIVE 态主按钮「创建任务并自动拆解」：ElMessageBox 确认 → finalize 得 task → 页内 loading 调 `taskApi.plan` → `router.push('/tasks?review={taskId}')`（plan 失败拦截器已弹错、仍跳 /tasks 可手动重拆）；FINALIZED 态只读 + 「查看任务」链接
- 接线：`router/index.ts` 补 `/requirement-chat` 路由；`MainLayout.vue` 任务管理下加菜单项（ChatDotRound）；`TaskList.vue` 工具栏加「对话新建」按钮 + onMounted 读 `route.query.review` 自动 `openPlanReview`（找不到静默忽略）

#### 3. 测试与验证

- 单测 `RequirementClarifyServiceTest`(13，照 PlannerAnalysisServiceTest 链式 mock 范式)：question/final 双路径、fence 容错、非 JSON 报错、轮数上限、finalize 无终稿拒绝/成功建 task、非 ACTIVE 拒发；`mvn -pl helloai-core -am test`（JDK 17）全绿 BUILD SUCCESS；坑位：`executeSync` 有重载，verify 必须 typed matchers `any(Agent.class), any(AgentTask.class)` 否则 ambiguous 编译错
- `npx vue-tsc --noEmit` 0 错 + `npm run build` 通过（chunk 警告为既有）
- **zsh 脚本真实环境 10 步全绿**：`scripts/shell/verify-requirement-clarify.sh`（照 verify-planner-decompose.sh 模板含 STEP2.1 凭证绑定）——创建会话（详尽需求）→ 1 轮追问后推进出终稿「内部日报统计模块开发」（conversationId=2082494629529395201）→ finalize 建 task PENDING（taskId=2082494653785055233）→ 会话 FINALIZED + taskId 回填 → FINALIZED 拒发（code!=200）→ plan 拆解 6 条草案 → abandon 回归 → 列表断言
- **浏览器闭环实测 8 步全过**（console 0 error）：新会话模糊需求 → LLM 3 条追问 → 补充 → 终稿卡片「团队周报收集与自动汇总工具」→ 创建并拆解 → 跳 `/tasks?review=…` 自动弹 5 条草案审阅 → 会话侧变「已建任务」只读。轻微现象：草案弹窗关闭偶需点两次（疑似动画时序，不影响流程）

#### 4. 影响

- **接口/DB**：新增 V29 两张表 + 六个新端点，既有接口零改动；`deleteTaskCascade` 零改动（task_id 软引用悬挂由产品语义接受）
- **行为变化**：立项入口从"一次写清"扩展为"对话澄清"，与拆解审阅链（§6.16/§6.18）串成"模糊想法 → 终稿 → 任务 → 草案 → 分发"完整链路

#### 5. 遗留与下一步

- 首期不做（已拍板裁剪）：SSE 流式输出（Doorbell SSE 是 Agent 侧信号通道不可蹭）、终稿手动编辑、会话删除（仅 abandon）、列表分页（LIMIT 50）
- 草案弹窗关闭偶需点两次的动画时序问题待顺手排查（非本链路引入，§6.18 组件既有）
- 带附件子任务删除回归仍待造数验证（继承 §6.18 遗留）

---

### 6.20 菜单调整 + Agent 注册接入分类 + LLM Provider 手动注册入口（2026-07-30）

#### 1. 背景

用户提出三点：①「对话新建」菜单移到「概述」下、「任务管理」上；②Agent 注册弹窗增加接入分类（外部 AI Agent / 内部 LLM / 网页端 Planner），仅 PLANNER 角色可见「网页端 Planner」选项且选中即提示功能不可用；③内部 LLM Agent 缺少手动注册入口，希望按"已生效的 api-key 配置"实现（用户原话为 pom.xml，实为 `application.yml` 的 `helloai.providers`），api-key 参考 `E:\yhzx\1027\springai` 项目的 application 配置，后续计划集成 minimax / kimi(moonshot) / 通义千问(dashscope)。调研中发现隐性 bug：`AgentProviderProperties` 用 `@ConfigurationProperties(prefix="helloai.providers")` + 字段名 `providers`，实际绑定路径为 `helloai.providers.providers.*`，yml 里的 `helloai.providers.deepseek.*` 从未绑定成功，只因 deepseek 默认值与 `DeepSeekProviderChatClientFactory` 内置默认恰好一致而未暴露。

#### 2. 实际落地

**后端（helloai-common + helloai-core + helloai-api + helloai-start）**

- `AgentProviderProperties`：前缀 `helloai.providers` → `helloai`（修复绑定路径 bug）；`ProviderConfig` 增加 `apiKey` 字段 + `hasApiKey()`（配置了平台级 API Key 即视为该 provider "已生效"）
- `application.yml`：`helloai.providers.deepseek` 补 `api-key`（`${DEEPSEEK_API_KEY:...}`）；预置 `moonshot` / `minimax` / `dashscope` 三段配置（key/base-url/model 取自 springai 项目，环境变量可覆盖；缺对应 Factory 实现前目录接口标记不可用）
- 新增 `LlmProviderCatalogService`（helloai-core/agent/chat，编排收口 core 不进 Controller）：`ProviderCatalogItem` record（provider/defaultModel/apiKeyConfigured/factorySupported/available）；`listProviders()`（available = apiKeyConfigured && factorySupported，factory 判定走 `ProviderChatClientFactory.supports`）；`bindPlatformApiKeyIfAbsent`（不可用抛 BizException；已有 ACTIVE 凭证跳过不覆盖，保护脚本注册后自行绑自定义密钥的既有链路；否则 `CredentialVaultBindingService.bindAgentApiKey` 绑平台 key）；`provisionPlatformCredential(Agent)`（`AgentProviderResolver.resolveProvider` 从 modelType 解析 provider、回退 `helloai.agent.execution.provider` 默认；provider 未生效仅 log.warn 跳过不阻断注册）
- `AgentController.applyRegistrationExtras`：末尾对 `accessType=API_KEY_LLM` 调 `provisionPlatformCredential`（尽力而为），注册即满足 `AgentSelector.hasUsableCredential` 的 ACTIVE 凭证门槛，手动注册的 LLM Agent 立即可被调度
- `AdminAgentController`：新增 `GET /api/admin/agents/llm-providers` 目录接口（返回 ProviderCatalogItem 列表）

**前端（helloai-ui）**

- `MainLayout.vue`：「对话新建」（/requirement-chat）菜单项移到 /dashboard 与 /tasks 之间
- `api/agent.ts`：`register` 参数扩展 `accessType`/`modelType`；新增 `listLlmProviders()`
- `AgentList.vue` 注册弹窗：新增「接入类型」下拉——外部 AI Agent（CLI 接入）=CLI_CLIENT 默认 / 内部 LLM（API Key）=API_KEY_LLM / 网页端 Planner=WEB_BROWSER（仅 `form.role==='PLANNER'` 显示）；WEB_BROWSER 选中显示 el-alert「网页端 Planner 功能暂不可用」+ 注册按钮 disabled + 提交前二次校验（仅前端拦截，后端枚举通道保留与现状一致）；API_KEY_LLM 显示 provider 下拉（目录懒加载，不可用项 disabled 并标注原因「缺少 Factory 实现」/「未配置 API Key」），注册体发送 `modelType: provider:defaultModel`，成功后不开 onboarding 弹窗改为提示「平台密钥已自动绑定」；角色切走 PLANNER 时 accessType 自动回退 CLI_CLIENT

#### 3. 测试与验证

- 后端 `mvn -DskipTests compile` 全 reactor BUILD SUCCESS
- 前端 `npx vue-tsc -b` EXIT=0
- 相关单测 `mvn -pl helloai-core -am test -Dtest=AgentChatClientServiceTest,PlatformAgentExecutionServiceTest -Dsurefire.failIfNoSpecifiedTests=false`：Tests run 2 / Failures 0 / BUILD SUCCESS（坑位：`-Dtest` 过滤在 helloai-common 无匹配用例会 BUILD FAILURE，须加 `failIfNoSpecifiedTests=false`）

#### 4. 影响

- 新增 1 个只读接口（llm-providers 目录），注册接口 body 的 `accessType`/`modelType` 从"脚本专用"升级为前端正式语义；无 DB 变更、无 Flyway
- `AgentProviderProperties` 前缀修复后 yml 的 providers 配置真正生效（此前静默失效吃默认值）；配置读取语义变化仅影响 `helloai.providers.*` 段
- E2E 脚本以 idempotent=true 注册 + 自行绑 key 的既有链路不受影响（已有 ACTIVE 凭证不覆盖）

#### 5. 遗留与下一步

- moonshot / minimax / dashscope 仅预置了配置段，各需补一个 `ProviderChatClientFactory` 实现类后目录自动标记可用（minimax base-url 为 anthropic 兼容端点，Factory 需按对应协议实现）
- WEB_BROWSER 执行链仍未落地（N8 维持"仅枚举预留"，本轮只做前端拦截提示）
- 平台密钥当前明文存于 yml 默认值（环境变量可覆盖），生产化前应改为仅环境变量注入

---

### 6.21 moonshot / minimax / dashscope ProviderChatClientFactory 补齐（2026-07-30）

#### 1. 背景

闭环 §6.20 遗留第一条：前端注册内部 LLM Agent 时，moonshot / minimax / dashscope 在 provider 下拉中标记「缺少 Factory 实现」不可选。用户确认参考 `E:\yhzx\1027\springai` 项目的接入方式补齐三个 Factory。

#### 2. 实际落地

**依赖（helloai-core/pom.xml）**

- 新增 `spring-ai-openai` + `spring-ai-anthropic`（均为非 starter 纯客户端库，无自动装配，不影响既有 deepseek starter 提供的唯一 `ChatClient.Builder`；版本由 spring-ai-bom 1.1.8 管理）
- 未引入 spring-ai-alibaba dashscope starter：其 BOM 1.0.0.2 绑定 spring-ai 1.0.0，与本项目 1.1.8 基线有冲突风险

**新增 Factory（helloai-core/agent/chat/provider，均与 DeepSeek 工厂同构：ProviderChatModelCache 三元组缓存 / 超时 / RetryTemplate / ObservationRegistry / ToolCallingManager）**

- `AbstractOpenAiCompatibleChatClientFactory`：OpenAI 兼容协议公共骨架（OpenAiApi + OpenAiChatModel），子类只提供 provider 标识 / 默认模型 / 兜底 base-url
- `MoonshotProviderChatClientFactory`：`https://api.moonshot.cn`，默认 `moonshot-v1-8k`（参考 springai KimiClientsConfig）
- `DashScopeProviderChatClientFactory`：DashScope OpenAI 兼容模式 `https://dashscope.aliyuncs.com/compatible-mode`（拼 /v1/chat/completions），默认 `qwen-plus`
- `MinimaxProviderChatClientFactory`：Anthropic 兼容接口 `https://api.minimaxi.com/anthropic`（AnthropicApi 拼 /v1/messages），默认 `MiniMax-M2.5`（参考 springai MinimaxClientsConfig）

**配置（application.yml）**

- `helloai.providers.dashscope` 补 `base-url`（`${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode}`）
- providers 段注释更新为"四个 provider 均有 Factory 实现"

#### 3. 测试与验证

- `mvn -DskipTests compile` 全 reactor BUILD SUCCESS（EXIT=0）
- `AgentProviderResolverTest` + `ProviderChatModelCacheTest` 回归通过（EXIT=0）
- 无需改 `LlmProviderCatalogService` / 前端：目录 available 判定走 `ProviderChatClientFactory.supports`，Factory Bean 注册后三个 provider 自动亮起

#### 4. 影响

- 前端注册弹窗 provider 下拉中 moonshot / minimax / dashscope 变为可选，注册后自动绑平台密钥并可被调度执行
- 纯新增类 + 配置补段，deepseek 既有链路零改动

#### 5. 遗留与下一步

- 三个新 provider 尚未做真实 API 连通性验证（key 有效性 / 模型名可用性），首次实际调度执行时需观察日志
- §6.20 其余遗留不变（WEB_BROWSER 执行链、平台密钥生产化注入）

---

### 6.22 PATROL 角色移除：Agent 角色收敛为三角色（2026-07-30）

#### 1. 背景与决策

用户决策：整体角色从 4 个收敛为 3 个（PLANNER / EXECUTOR / REVIEWER），移除 PATROL 巡检角色——其兜底目标已由重分配熔断（V24）、死信池人工兜底（V25 DEAD_LETTER）、定时补偿任务覆盖。

删除前核验（SearchAgent 全量引用面 + postgres_helloai MCP 查库）：

- Java 代码零直接引用 `AgentRole.PATROL` / 字符串 "PATROL"（角色转换全走 `valueOf` 动态转换），无任何按 PATROL 分支的调度逻辑 / 定时任务 / 消费者
- patrol MQ 队列 / 绑定为纯死拓扑（无生产者无消费者）
- 数据库中 PATROL 相关数据全为 0：PATROL 角色 agent 0 个、patrol_record 0 行、task_timeline PATROL 行 0、prompt_template 仅 1 条未用种子行 → 采取彻底清理策略（连 patrol_record 表生态一起删，无需数据迁移）

顺带完成上轮遗留 rename：`AbstractOpenAiCompatibleChatClientFactory` → `AbstractOpenAiCompatibleProviderChatClientFactory`（两个子类 extends 同步）。

#### 2. 实施内容

**后端删除**

- `AgentRole`：删 PATROL 枚举值（剩 PLANNER/EXECUTOR/REVIEWER/SYSTEM）
- `RabbitMQConfig`：删 `PATROL_QUEUE` 常量、`patrolQueue()`、`patrolBinding()` 三处死拓扑
- 删文件：`skills/patrol/SKILL.md`、`PatrolRecord`、`PatrolRecordMapper`、`PatrolRecordService`
- `AgentService`：删 patrolRecordMapper 注入、patrolCount 统计（getRelatedCounts / deleteAgentCascade）、级联删除链 patrol 步骤
- `TaskService`：删 patrolRecordMapper 注入与级联删除步骤，Javadoc "6 张表" 改 "5 张表"
- `AdminAgentController` + `AgentDeleteResult` / `AgentRelatedCounts` DTO：删 patrolCount 字段与 set
- `AgentServiceTest`：同步删 Mock / 构造参数 / 断言
- 注释清理：McpMcpServer（pullTasks 参数描述 + GetAgentStatusResult）、AgentMcpServerService、TaskTimelineService、TaskTimelineItem、TaskTimeline

**数据库（V30__remove_patrol_role.sql，V1 历史迁移不动）**

- 重建 `chk_agent_role`（三角色）与 `chk_task_timeline_role`（三角色 + SYSTEM）
- DELETE prompt_template PATROL 种子行（id=2000000000000000004）
- DROP TABLE patrol_record；同步更新三处 COMMENT

**前端（9 文件）**

- `types/index.ts`：AgentRole 联合去 PATROL、两个 DTO 去 patrolCount、颜色映射去 PATROL、删 PatrolRecord 接口
- `PromptList.vue`（筛选/表单/标签色 3 处）、`AgentList.vue`（2 处）、`AgentCard.vue`、`AgentEditDialog.vue`、`AgentDeleteDialog.vue`（巡查记录统计行）、`AgentDetail.vue`、`AgentSelect.vue`、`QuickDispatchDialog.vue`

**杂项**

- `verify-subtask-deadletter.ps1` / `verify-subtask-redispatch-auto-execution.ps1` 默认 `$Role` 改 EXECUTOR
- `cleanup-test-data.sql` 删 patrol_record（注释 + TRUNCATE 列表）
- CODE_STYLE：skills 目录树、MQ 队列表、模型选型表去 PATROL 行、字段命名示例去 `patrol_agent_id`；基线文档删 "PATROL 自动巡检链路" 行
- 设计文档同步：`HelloAI_架构设计参考.md`（§1.1 角色模型标注 HelloAI 已收敛三角色、§5.3 第三阶段协作闭环去 Patrol）、`HelloAI_外部项目借鉴技术细节.md`（§2.4 / §3.2 / §4.1 中描述 HelloAI 现状的行改为三角色；OpenMOSS 自身四角色事实描述保留不动）

#### 3. 测试与验证

- `mvn -DskipTests compile` 全 reactor BUILD SUCCESS（EXIT=0）
- `AgentServiceTest` 回归通过（EXIT=0）
- `vue-tsc --noEmit` 类型检查通过（EXIT=0）
- 全仓 grep 确认：残留仅历史资产（V23 历史迁移、迭代记录历史条目、archive / 借鉴文档），代码 / 配置 / 脚本零残留

#### 4. 影响

- Agent 注册 / 编辑 / 筛选、提示词模板的角色选项收敛为三角色；已有三角色数据零影响
- V30 随应用启动自动执行；执行前生产库 PATROL 数据已核验为 0，DROP 表无数据损失
- 兼容性说明：若外部 MCP 客户端以 role=PATROL 调 pullTasks 会因 `AgentRole.valueOf` 抛异常，但库中不存在 PATROL Agent，实际无此调用方

#### 5. 遗留与下一步

- §6.21 遗留不变（三个新 provider 真实 API 连通性验证、WEB_BROWSER 执行链、平台密钥生产化注入）

---

### 6.23 chat.provider 归位重构：Provider 接入族自包含（2026-07-30）

#### 1. 背景与决策

用户观察到 chat 包下 Factory 与 Service 混放显乱。核查结论：`provider` 子包本身纯净（全 Factory），乱源是 chat 父包混放接口 / Service / 缓存 / 工具四种类型，且 `ProviderChatClientFactory` 契约与 `ProviderChatModelCache` 缓存的唯一消费方就是 Factory 族，存在归属错位。决策：按项目"按职责分包"惯例做一次归位（不新增包、不按类类型拆包）。

#### 2. 实施内容

- 移动（含 package 声明修正）：`ProviderChatClientFactory`、`ProviderChatModelCache` 从 `core.agent.chat` → `core.agent.chat.provider`；测试镜像移动 `ProviderChatModelCacheTest` 同步进 test 侧 provider 包
- import 修正：5 个 Factory 删同包冗余 import；`AgentChatClientService`（顺带清掉 2 行同包冗余 import）、`LlmProviderCatalogService`、`AgentChatClientServiceTest`、`PlatformAgentExecutionServiceTest` 改指向新 FQN
- 归位后语义：`chat` = 业务 ChatClient 服务层（AgentChatClientService / LlmProviderCatalogService / AgentProviderResolver）；`chat.provider` = Provider 接入族（契约 + 4 厂商实现 + 抽象基类 + ChatModel 缓存）
- CODE_STYLE §3.x 语义边界补一条 chat / chat.provider 分界；`AgentProviderProperties` Javadoc 无 FQN 无需动

#### 3. 测试与验证

- 旧 FQN `agent.chat.ProviderChat*` 全仓 Java 零残留
- `mvn -DskipTests compile` 全 reactor EXIT=0
- `AgentChatClientServiceTest` / `ProviderChatModelCacheTest`（@Nested 13 用例）/ `PlatformAgentExecutionServiceTest` 回归全绿（surefire XML 核验 failure/error = 0）

#### 4. 影响与遗留

- 纯包移动零逻辑变更；后续新增 LLM 厂商只动 chat.provider 子包 + 一段 yml
- 无新遗留；§6.21 / §6.22 遗留不变

---

### 6.24 三个新 Provider 真连通验证：moonshot / minimax / dashscope × 三角色（2026-07-30）

#### 1. 背景

闭环 §6.21 遗留项"三个新 provider（moonshot/minimax/dashscope）真实 API 连通性验证"。验证目标：平台密钥能否支撑 PLANNER / EXECUTOR / REVIEWER 三角色 Agent 的注册与真实对话。

#### 2. 实施内容

- 新增 `tmp/verify-three-providers.ps1`：3 provider × 3 角色共 9 组合，每组注册 API_KEY_LLM Agent（幂等，注册时经 `LlmProviderCatalogService.provisionPlatformCredential` 自动补绑平台密钥）→ 调 `/api/agent-executions/connectivity/{agentId}` 真实对话探测
- 后端以归位重构后的新 jar 运行（16:09 重建），间接完成 §6.23 变更的运行时冒烟

#### 3. 验证结果

- 9/9 全通过：register=OK、chat=OK、mockMode=false，各模型真实回显探测口令（moonshot-v1-8k / MiniMax-M2.5 / qwen-plus），延迟 0.5s~2.7s
- MiniMax-M2.5 为推理模型，output 含思考前缀文本，连通判定不受影响

#### 4. 影响与遗留

- §6.21 遗留项"三个新 provider 真实 API 连通性验证"关闭；WEB_BROWSER 执行链、平台密钥生产化注入两项遗留不变
- 产生 9 个 probe-* 探测 Agent（幂等命名，可复用或后续清理）

---

### 6.25 内部 LLM Agent 隐藏"生成接入内容"入口（2026-07-30）

用户建议：接入内容面向外部 AI Agent（CLI 接入），API_KEY_LLM Agent 注册即完成（平台密钥自动绑定），不应展示该按钮。实施：

- 后端：`AgentListItemVO` 补 `accessType` 字段（`AgentDetailVO` 继承获得），`AdminAgentController` 列表/详情映射补 set；`onboarding-content` 接口对 API_KEY_LLM 直接 fail（防御绕过前端直调）
- 前端：`AgentListItem` 类型补 `accessType?`；`AgentCard.vue`（列表卡片 hover 操作栏）与 `AgentDetail.vue`（详情操作区）的"生成接入内容"按钮加 `v-if="agent.accessType !== 'API_KEY_LLM'"`
- 验证：`mvn compile` 全 reactor EXIT=0、`vue-tsc` EXIT=0

---

### 6.26 minimax 推理模型 thinking 分离修复：parseVerdict null 根因闭环（2026-07-30）

#### 1. 背景与根因

minimax（MiniMax-M2.5，Anthropic 协议推理模型）担任 REVIEWER 时自动核验必然 `sub_task_auto_review_unparseable`（parseVerdict 返回 null）。裸 API 探测（`tmp/probe-minimax-format.ps1`、`tmp/probe-minimax-500.ps1`）确认根因：minimax 返回 `[thinking, text]` 两个 content block，Spring AI 1.1.8 `AnthropicChatModel.toChatResponse` 把每个 block 映射为一个 Generation（thinking 块的 AssistantMessage metadata 带 `signature`，redacted_thinking 带 `data`）；我方原代码 `response.getResult()` 只取 generations[0]，拿到的是思考文本，正文 JSON 在 generations[1] 被丢弃——不是模型输出脏，是取错了 Generation。

#### 2. 实施内容

- 新增 `helloai-core/.../agent/chat/ChatResponseContentExtractor.java`：遍历全部 Generation，metadata 含 `signature`/`data` 归 thinking，其余拼正文；两个 ChatResponse 出口（`ApiKeyAgentExecutor`、`AgentExecutionConnectivityService`）统一改走该 extractor
- thinking 全链路贯通保留（按用户决策，供后续前端动态展示）：`AgentResult.thinking`（5 参 success 重载）→ `ExecutionResultReport.thinking` → `ExecutionResultHandler` 落对话流消息 `sub_task_execute_thinking`；`SubTaskReviewService` 在 verdict 消息前落 `subtask_review_thinking`；connectivity/preview API 响应 DTO 补 `thinking` 字段
- `parseVerdict` 未改（既有 `stripToJsonObject` 对干净正文足够）；unparseable 停留 REVIEW 的兜底逻辑未动——重试/降级转其他 LLM 按用户指示留待下轮

#### 3. 验证结果

- `mvn test` 全 reactor：Tests run 292, Failures 0, Errors 0
- `tmp/verify-minimax-thinking.ps1`（connectivity 审查场景探测 probe-minimax-reviewer）：output=干净可解析 JSON（含 pass/score/comment）、thinking 单独返回 1170 字符，`VERIFY_RESULT=PASS`；moonshot 对照组 thinking_len=0、正文照常，OpenAI 协议无回归
- 真实审查链路重放（`tmp/replay-submit-result.ps1` 走 MCP `submitResult` → `handleReport` → `SubTaskSubmittedForReviewEvent`）：子任务 2082747212507799554 timeline 出现 `sub_task_auto_review_passed`（此前同任务为 unparseable），verdict 为干净 JSON，状态 REVIEW→DONE 闭环
- 环境坑记录：验证期间 6565 端口被 IDEA 旧代码进程占用，jar 启动失败但探活误报，靠启动日志 `Port 6565 was already in use` + `Get-NetTCPConnection` 查占用进程定案；用户重启 IDEA 后端后验证通过。另注：管理端 `POST /api/sub-tasks/submit/{id}` 只改状态不发核验事件，重放自动核验必须走执行结果上报路径

#### 4. 影响与遗留

- minimax 担任 REVIEWER 的 parseVerdict null 问题关闭；thinking 已在对话流与 API 层保留，前端动态展示待后续迭代
- 遗留（下轮）：核验 verdict 不可解析时的重试 / 降级转其他 LLM Agent 处理策略
- 遗留（低优先）：`MinimaxProviderChatClientFactory` 未显式设置 maxTokens（Spring AI Anthropic 默认 500），实测未触发截断，暂不改

---

### 6.27 子任务详情展示优化（方案1）+ 拆解/澄清链配套 + 执行产出物化方案设计（2026-07-30）

#### 1. 背景

真实 AI 执行链已连通并产出正文，但子任务详情页仅平铺原始文本、时间线为开发者事件码，非开发者难读；且执行产出目前只落 `sub_task.context.lastExecution.output` 纯文本，`attachment` 表全库 0 写入，无法沉淀可下载文件。本轮先做"零后端"的展示优化（方案1），并把"后端产出物化 + 结构化多文件产出"（方案2/3）沉淀为设计文档，代码不动。

#### 2. 实施内容

- **前端展示优化（helloai-ui，纯前端）：**
  - 新增 `components/MarkdownView.vue`（markdown-it + dompurify 渲染富文本，XSS 净化）与 `components/ReviewVerdictView.vue`（核验分析结构化卡片：pass/score/issues/comment 分区渲染，替代裸 JSON）；`package.json` 引入 `markdown-it` / `dompurify`。
  - `SubTaskDetail.vue`：执行对话流按 Markdown 富文本渲染 + 超长折叠；时间线事件"人话化"（`EVENT_META` 事件码 → 中文标签 + 一句话描述，payload 折叠为"技术详情"）；Agent ID → 注册名映射（`agentNameMap`，未命中降级短 ID）；"返回列表"携带所属主任务 `taskId` 归属跳转；执行产出保留"复制/导出 .md"（前端 Blob 导出，方案1），核验请求消息剥离 HTML 注释。
  - `SubTaskList.vue` 增补、`api/subTask.ts` / `api/clarify.ts` / `types/index.ts` 微调配套。
- **后端配套（需求澄清/拆解链）：**
  - `PlannerAnalysisService.orderByDependency`：新增稳定 Kahn 入度拓扑排序，草案审阅/分发按依赖正序（根节点在前，`dependsOn` 恒指向更靠前行）；仅按本批次内部依赖排序，批外/悬挂 id 视为无约束，残留成环兜底按原序追加绝不丢条目。
  - `RequirementClarifyService.regenerate`：新增"会话已 FINALIZED 且原任务已被删除"的悬挂恢复路径——复用会话终稿重建 PENDING Task（不放开 ACTIVE 校验、不重跑 LLM，原任务仍存活时拒绝），抽出 `buildTaskFromDraft` 私有方法统一 finalize/regenerate 建任务逻辑，timeline 事件区分 `task_created_from_clarify` / `task_regenerated_from_clarify`；`RequirementConversationController` 补 regenerate 薄入口。
- **设计文档（仅文档，代码未实现）：**
  - 新增 `doc/design/HelloAI_执行产出物化与结构化多文件产出方案.md`：方案2（执行产出物化为真实文件 + attachment 记录 + 前端可下载）与方案3（LLM 可选 JSON manifest 结构化多文件产出）的决策完整设计草案——本地文件系统存储 + `ArtifactStorage` 抽象（config 门控 `helloai.storage`，未来可换 MinIO）、方案2 是方案3 的降级形态（统一 `ParsedOutput{displayText, files}` 解析器）、物化放 `afterCommit` best-effort 不阻断 REVIEW、下载接口 `local://` 流式改造、前端"产出附件"卡片走 axios blob 带 token；含改动清单、时序图、风险回滚、验证计划、小步实施顺序。
- **工程：** 新增 `docker-compose.server.yml` / `nginx.server.conf` 服务器部署配置；`.gitignore` 忽略 `.tmp/`（临时验证日志 + 含明文密码的一次性 `deploy-ssh.exp`，不入库）。

#### 3. 验证结果

- 展示优化为纯前端改动，浏览器渲染观感对齐（Markdown 富文本 / 时间线人话化 / 核验分析卡片）；提交前 `git add` 明确排除 `scripts/shell/.tmp/`（其中 `deploy-ssh.exp` 硬编码 SSH 明文密码，属安全敏感临时产物）。
- 后端 `orderByDependency` / `regenerate` 为既有链路增量，编译沿 helloai-core 现状（未新起真实环境跑本轮 e2e）。

#### 4. 影响与遗留

- 方案1（前端导出/展示）已交付；**方案2/3 仅为设计文档，后端代码一行未动**，待后续按设计文档 §11 顺序落地（届时回填 N 项状态：`attachment` 表从 0 写入 → 内置执行链产出物化）。
- 遗留：方案2/3 实现、执行产出附件前端"产出附件"卡片；服务器部署配置（docker-compose.server.yml / nginx.server.conf）真实环境验收。

---

### 6.28 LLM 输出 JSON 非法反斜杠转义容错修复（2026-07-31）

#### 1. 背景与根因

需求澄清链真实报错：moonshot 返回的终稿 JSON 字符串值里含未转义的 Windows 路径（`E:\workspace\AgentTeams-main`），Jackson 严格解析遇 `\w` 非法转义直接抛"Unrecognized character escape"，澄清会话报 500。同款风险同样存在于核验链 `parseVerdict`（同为 `objectMapper.readValue` 严格解析，命中则 unparseable 停留 REVIEW）。

#### 2. 实施内容

- 新增 `helloai-core/.../shared/util/LlmJsonSanitizer.java`：字符扫描修复 JSON 字符串值内的非法反斜杠转义（`\w` → 字面 `\\w`，路径内容不丢）；合法转义（含 unicode 转义后接 4 位十六进制的判定）原样保留；字符串外区域透传
- 接入两处 LLM JSON 解析出口：`RequirementClarifyService.parseReply` 与 `SubTaskReviewService.parseVerdict`，均为先 stripToJsonObject 再 fixInvalidEscapes
- 坐标注意项：Java 编译器对注释里的 `\u` 也做 Unicode 转义预处理，Javadoc 中不可出现 `\uXXXX` 字面（本轮踩坑：首版注释引发"非法 Unicode 转义"编译错）

#### 3. 验证结果

- `mvn -pl helloai-core -am test`：Tests run 298（+6：LlmJsonSanitizerTest 5 例 + parseVerdict 路径场景 1 例）, Failures 0, Errors 0, BUILD SUCCESS

#### 4. 影响与遗留

- 澄清链/核验链对 LLM 输出 Windows 路径的容错闭环；拆解链 `PlannerAnalysisService` 解析暂未接入（拆解产出为数组且未实际报错，按需再接）
- 遗留不变：核验不可解析时的重试/降级转其他 LLM 策略仍留待后续轮次

---

### 6.29 澄清对话重试按钮 + Planner 手动选择下拉选（2026-07-30）

#### 1. 背景与目标

用户提出两个前端可感知的改进：① 澄清对话 LLM 失败（500）后页面出现「重试」按钮（类似 DeepSeek），不必重发消息；② 对话新建页增加 Planner 手动下拉选，默认「系统自动」，选项含平台内 API_KEY_LLM PLANNER 与在班外部 Agent。已确认决策：外部 Agent 展示但置灰（无同步应答桥，暂不支持对话澄清）；手动选中的 Planner 同时用于后续任务拆解（同一 Planner 从澄清跟到拆解）。

#### 2. 实施内容

后端：

- 新增 `helloai-core/.../planner/PlannerAgentPicker.java`：收编 `RequirementClarifyService` 与 `PlannerAnalysisService` 两处原刻意复制的 pickPlannerAgent 为共享选型器。`pick(pinnedAgentId)` pinned 有效直用、失效 log.warn 回退自动；`autoPick()` 候选（PLANNER + API_KEY_LLM + ACTIVE + 非 SLEEPING + 有启用态凭证）等权重、优先 inProgressCount 最小者；`pickForTask(taskId)` 经 requirement_conversation.task_id 软引用反查会话钉住的 Planner（不给 Task 加字段）；`validateSelectable` 供 create 严格校验；`listOptions()` 输出下拉选数据源（内部 PLANNER selectable=有凭证 + 在班外部 Agent 置灰）
- `RequirementConversation` 新增 `plannerAgentId` 字段 + Flyway `V31__requirement_conversation_planner_agent.sql`
- `RequirementClarifyService`：`create(firstMessage, plannerAgentId)` 非空时校验并落库钉住；新增 `retryRound(id)`（仅当最后一条消息为 user，即上轮 LLM 失败；不新增消息、不加 round_count，复用 runLlmRound）；`listPlannerOptions()`
- `PlannerAnalysisService`：删除本地 pickPlannerAgent，改 `plannerAgentPicker.pickForTask(taskId)`（拆解跟随钉住 Planner）
- `RequirementConversationController`：create 透传 plannerAgentId，新增 `GET /requirement-conversations/planner-options` 与 `POST /{id}/retry`

前端（helloai-ui）：

- `types/index.ts`：RequirementConversation 加 plannerAgentId、新增 PlannerOption 接口
- `api/clarify.ts`：create 加 plannerAgentId 参数、新增 retry / plannerOptions
- `RequirementChat.vue`：新会话输入区 Planner 下拉选（外部 Agent 置灰带原因）、已有会话展示钉住 Planner 标签；canRetry 数据驱动（ACTIVE 且最后一条为 user 消息，刷新后仍可重试）渲染重试条；handleSend catch 重构（create 失败按 title 找回已落库会话，避免重复建会话，条件回填输入框）

#### 3. 验证结果

- `mvn -pl helloai-core -am test`：Tests run 312（+14：PlannerAgentPickerTest 11 例新建 + RequirementClarifyServiceTest 新增 4 例 - 迁移收敛 1 例）, Failures 0, Errors 0, BUILD SUCCESS
- 前端 `npx vue-tsc -b`：本轮改动三文件零错误（仅存量 MarkdownView.vue 的 markdown-it/dompurify 模块未安装报错，与本轮无关）
- 踩坑记录：Mockito `RETURNS_SELF` 对 MyBatis-Plus `LambdaQueryChainWrapper` 泛型链式调用不生效（eq 返回 null → NPE），须逐方法 `doReturn(chain).when(chain).xxx()`，且 orderByDesc 需显式类型实参规避重载歧义；`Stream.min` 单元素不调用比较器，单候选场景 stub inProgressCount 会触发 UnnecessaryStubbing

#### 4. 影响与遗留

- V31 迁移与新端点需重启后端后生效（Flyway 自动执行）
- 外部 Agent 参与对话澄清需先建同步应答桥（AgentExecutorRouter 目前仅 ApiKeyAgentExecutor），置灰文案已预留
- 遗留不变：核验不可解析的重试/降级策略、主任务交付物打包下载（待真实数据）

---

### 6.30 方案2 执行产出物化 + 主任务交付物实时聚合 zip 下载（2026-07-31）

#### 1. 背景与决策

用户需求：任务已由多个子任务分别完成并产出交付物，期望类似 Kimi 的附件下载或资源 zip 包下载，下载结果应把各子任务产出整理在一起。依据 `doc/design/HelloAI_执行产出物化与结构化多文件产出方案.md`（§6.27 产出的设计草案）落地方案2；已确认决策：① 主任务层采用**实时聚合 zip**（下载时现场从 sub_task.context + attachment 组包，历史任务立即可下、返工后重下即最新、无存储成本、无表结构变更）；② 两层一轮落地（主任务 zip + 方案2 子任务物化）。方案3（LLM manifest 多文件协议）本轮不做，`ExecutionOutputParser` 注释已预留扩展位。

#### 2. 实施内容

后端（方案2 物化链）：

- `helloai-common/.../config/ArtifactStorageProperties.java`（新建，仿 DoorbellProperties，prefix=`helloai.storage`，全字段默认值：enabled=true / type=local / local-base-dir=./data/artifacts / bucket=helloai-local / max-files=10 / max-file-size=5MB）+ `application.yml` 新增 storage 配置段
- 存储抽象三件套（`core/system/storage`，新建）：`ArtifactStorage` 接口（store/load/supports，预留 minio/s3 扩展）/ `LocalArtifactStorage`（storageUrl=`local://{bucket}/{objectKey}`，objectKey=`{subTaskId}/{yyyyMMdd}/{uuid8}-{safeName}`，normalize+startsWith 路径穿越防护，文件名清洗）/ `StoredArtifact` record
- 解析三件套（`core/agent/output`，新建）：`ExecutionOutputParser`（纯文本→单 .md，文件名取子任务标题清洗限长60；方案3 落地后在此扩展 manifest 解析）/ `ParsedOutput` / `ArtifactFile` record
- `ExecutionArtifactService`（新建）：best-effort 物化编排——parse 空跳过、maxFiles 截断、单文件超 maxFileSize 跳过；register 固定传 `subTask.assignedAgentId`（保证归属校验必过），时间线 `sub_task_artifact_materialized` 记上报 agentId；任何异常吞掉只记日志，绝不阻断执行主链路
- `ExecutionResultHandler` 成功分支挂接：复用 failureTracker 的 `TransactionSynchronizationManager.registerSynchronization` afterCommit 范式（行锁释放后物化，规避自死锁），构造器第 7 参注入；两个存量测试（Integration/Unit）同步
- 附件下载流式改造：`AttachmentService` 新增 `isContentLoadable`/`loadContent`（仅 local:// 平台直读）+ detectBucketName/detectObjectKey 识别 local:// 前缀；`AttachmentController.download` local:// 流式返回（RFC 5987 中文文件名），其余仍 302 重定向

后端（主任务实时聚合 zip）：

- `core/shared/util/SubTaskDependencyOrder`（新建）：从 PlannerAnalysisService 私有 orderByDependency 提炼的公共稳定 Kahn 拓扑排序（统一走 `dependsOnIdList()` 归一化，成环兕底不丢条目）；PlannerAnalysisService 改为委托
- `core/task/service/TaskDeliverableService`（新建）：`buildZip(taskId)` 内存组包（UTF-8 ZipOutputStream）——`00-任务概览.md`（任务信息 + 子任务完成情况表：状态/Agent/完成时间/最新核验结论）+ `NN-xxx` 拓扑序编号的 DONE 子任务产出；**取数规则：优先物化 local:// 附件（同名取最新一轮），无可读附件回退 context.lastExecution.output 单 .md**（兼容物化上线前的历史任务，并避免新任务重复收录）；草案/已取消不入包，非 DONE 仅概览表标注；重名自动 (2)(3) 后缀；单附件读取失败不拖垮整包
- `TaskController` 新增 `GET /api/tasks/{id}/deliverables/download`（薄入口，任何状态可下，无产出时包内仅概览）

前端（helloai-ui）：

- `api/request.ts` 响应拦截器开头放行 blob（返回完整 response 供解析 Content-Disposition）；新建 `utils/download.ts`（parseDispositionFilename：filename* 优先 + saveBlobResponse）
- `api/attachment.ts` 补 `download(id)`（blob）+ id 类型 number→LongId；`api/task.ts` 补 `downloadDeliverables(id)`（blob + 120s timeout）
- `TaskList.vue` 操作列新增「交付物」按钮（loading 防重复点击）；`SubTaskDetail.vue` 新增「产出附件」卡片（文件名/大小/时间 + 单附件下载，无附件不展示，随 5s 轮询刷新）

#### 3. 验证结果

- `mvn -pl helloai-core -am test`：Tests run **333**（312 基线 +21：ExecutionOutputParserTest 5 / LocalArtifactStorageTest 6 / ExecutionArtifactServiceTest 5 / TaskDeliverableServiceTest 5）, Failures 0, Errors 0, BUILD SUCCESS
- 前端 `npx vue-tsc -b --force`：TSC-OK（顺手修复 `tsconfig.node.json` 缺 `skipLibCheck` 导致 @types/markdown-it 第三方声明报错阻断 -b 构建）
- 踩坑记录：Mockito 对 `LambdaQueryChainWrapper.orderByAsc(any())` 与 `AttachmentService.list(any())` 存在重载歧义，须显式类型实参 `ArgumentMatchers.<SFunction<SubTask, ?>>any()` / `anyLong()` 解歧义

#### 4. 影响与遗留

- 重启后端后生效（无 Flyway 变更，仅新配置段带默认值）；物化仅对新执行生效，历史任务靠 zip 的 context 回退链路覆盖
- 方案3（LLM manifest 多文件协议）仍为遗留，落地时仅需扩展 `ExecutionOutputParser`，物化/打包/下载链路无感
- MinIO/S3 未引入（设计文档非目标不变），`ArtifactStorage` 抽象已预留；真实环境端到端验证（下载历史任务 2083021360376172545 的 zip）待后端重启后回归

---

### 6.31 任务最终整合报告：Planner 整合全部子任务产出（V32，2026-07-31）

#### 1. 背景与决策

用户需求：交付物 zip 里各子任务产出彼此分立，希望由 Planner/Reviewer 角色的 AI Agent 把全部子任务交付物整理成一份连贯文档。已确认决策：① 触发方式＝**自动生成 + 手动重生成**（任务自动收口时异步触发，历史已 DONE 任务/不满意时手动补生成或覆盖重生成）；② 整合角色＝**Planner**（复用 pickForTask 钉住机制，澄清→拆解→整合同一 Planner 跟随）；③ 展示＝**可视化 + zip**（前端 MarkdownView 弹窗 + zip 内 `01-最终整合报告.md`）。

存储选型关键决策：报告存 **task 专列 TEXT**（V32 三列：final_report / final_report_agent_id / final_report_time）而非 task 加 context JSONB——踩点发现 MyBatis-Plus 写 JSONB 列必须 XML 覆盖 insert/updateById（SubTaskMapper.xml 先例，`::jsonb` 显式转换），专列 TEXT 方案零 XML 改造。

#### 2. 实施内容

后端：

- `V32__task_final_report.sql`（新建）：task 表加三列；`Task` 实体同步三字段；`AgentDispatchProperties` 新增 `autoFinalReportEnabled=true`（`helloai.dispatch.auto-final-report-enabled`）
- `prompts/task-final-report.md`（新建）：占位符 TASK_TITLE/TASK_DESCRIPTION/SUB_TASK_SECTIONS，要求非简单拼接（执行摘要+重组正文+结论建议）、忠于产出不编造
- `TaskFinalReportService`（新建，core/task/service）：`generate(taskId)` 编排——仅 DONE 可调；取数与 zip 同源（DONE+产出非空+拓扑序，单段 8000 字符截断保护上下文）；pickForTask 选 Planner → executeSync → lambdaUpdate 只写三列；timeline 记 `task_final_report_llm_call_start/generated/failed`；不加类级事务（LLM 长耗时，与 decompose 同哲学）
- 自动触发链：新建 `TaskAutoCompletedEvent`；`SubTaskCompletionListener.tryCloseTask` CAS 赢家分支发布事件（赢家唯一天然防重复生成）；服务端 `@Async + @EventListener` 承接（发布点已无事务上下文，不能用 @TransactionalEventListener）；开关关/已有报告跳过，异常吞掉只记日志——报告是增值物非交付门槛
- `TaskController` 新增薄端点：`GET /api/tasks/{id}/final-report`（读专列组 `TaskFinalReportResponse`，agentName 回填）/ `POST /api/tasks/{id}/final-report`（同步生成）
- `TaskDeliverableService.buildZip`：有报告时置顶收录 `01-最终整合报告.md`，子任务产出顺延从 02- 起；无报告时维持旧编号（向后兼容）

前端（helloai-ui）：

- `types` 新增 `TaskFinalReport`；`api/task.ts` 新增 `getFinalReport` / `generateFinalReport`（180s timeout）
- 新建 `FinalReportDialog.vue`：MarkdownView 渲染 + 元信息（Planner 名/生成时间）+ 空态 + 生成/重新生成（覆盖需二次确认）+ 复制 + 导出 .md
- `TaskList.vue` 操作列新增「报告」按钮（仅 DONE 任务展示）

#### 3. 验证结果

- `mvn -pl helloai-core -am test`：Tests run **343**（333 基线 +10：TaskFinalReportServiceTest 8 / TaskDeliverableServiceTest 新增 2）, Failures 0, Errors 0, BUILD SUCCESS
- 前端 `npx vue-tsc -b --force`：TSC-OK
- 踩坑回顾：`AgentTask`/`AgentResult` 实际在 `com.helloai.core.agent.domain` 包（非 execution/executor），首次 import 写错编译报错后修正

#### 4. 影响与遗留

- 需重启后端使 Flyway V32 生效；历史已 DONE 任务无报告，靠弹窗内手动「生成报告」补齐
- 自动生成仅覆盖新收口任务；手动重生成为 last-write-wins 覆盖，无历史版本保留（如需版本化另议）
- 真实环境端到端验证（收口自动生成 + 手动重生成 + zip 含报告）待后端重启后回归

#### 5. 修复补记：小上下文模型 token 超限降档重试（2026-07-31）

- 真实环境首测报错：Planner 绑 moonshot（8k 上下文），13 个子任务各截 8000 字符拼 prompt 总量达 45640 token，命中 `exceeded model token limit: 8192`（逐段截断挡不住段数多的总量爆炸）
- 修复：`SECTION_OUTPUT_LIMIT` 常量改为阶梯 `{8000, 2000, 500}`；`generate` 命中 token 超限类错误（isTokenLimitError 覆盖 moonshot/openai/deepseek 措辞）且还有更紧档位时收紧截断重试，其余错误直接失败；timeline 三类事件 payload 增记 `sectionOutputLimit`
- 大上下文模型首档即成功、行为不变；单测 +2（降档重试成功 / 全阶梯仍失败），`mvn -pl helloai-core -am test` 345 全绿
- 同轮第二修：Planner 换绑 minimax（Anthropic 协议推理模型）后生成报告耗时超 60s，命中 provider HTTP 读超时（`SocketTimeoutException: Read timed out`，被 Spring 误报为 content-type application/octet-stream 解析失败）。修复：`AgentProviderProperties.readTimeoutMs` 默认 60000 → 180000（四 provider 共享），yml deepseek 显式值同步 180000，前端 generateFinalReport 超时 180s → 240s 留余量；需重启后端生效（ProviderChatModelCache 重建后新超时才落地）

---

### 6.32 结构化选项式需求澄清引擎（V33，2026-07-31，同日第二轮）

#### 1. 背景与决策

用户提出下一步计划（P0 结构化选项式澄清 / P1 多轮对话策略 / P2 浏览器检索 / P3 ASR-TTS），本轮实施 P0：澄清追问从「纯文本问答」升级为「选项点选为主、自由输入兜底」，用户面对模糊需求不再需要打字长文回答。评审阶段确认 6 处修正后落地：

- **payload 一列两用**：`requirement_message` 只加一列 `payload`，assistant 行存结构化问题 JSON（`{"mode","progress","questions":[...]}`），user 行存选择快照（`{"selections":[...]}`），纯文本消息 NULL——不为两种行各开一列
- **TEXT 而非 JSONB**：与 V32 同款约定（JSONB 写入需 JacksonTypeHandler + XML 覆盖改造），payload 只整存整取、无库内查询需求
- **progress 仅展示**：LLM 自评 0~100 只驱动前端进度条，无任何 `if (progress >= x)` 业务分支；FINALIZED 前端直接显示 100
- **降级 freeform 一等公民**：LLM 输出非 JSON 且不含 `"type"` 字样 → 原文作 freeform 追问落库不报错（判据 `rawOutput.contains("\"type\"")`，含 type 的破碎 JSON 仍抛 BizException 走既有 retry 链路）；structured 校验失败（无问题/无选项/label 空）→ 降级 freeform 丢弃 questions
- **weight 留字段缓建**：`ClarifyOption.weight` 预留无业务消费，注释明示
- **content/payload 职责分离**：content 是 LLM transcript 可读文本（structured 时由引导语+问题+选项经 `composeAssistantContent` 合成），payload 是前端渲染快照；payload 丢失不影响 LLM 上下文

#### 2. 实施内容

后端：

- `V33__requirement_message_payload.sql`（新建）：`ADD COLUMN IF NOT EXISTS payload TEXT` + 一列两用 COMMENT；`RequirementMessage` 实体加 `payload` 字段
- `prompts/requirement-clarify.md`（重写）：三形态输出协议——structured question（`mode/progress/message/questions[{id,text,multiple,allowCustom,customPlaceholder,options[{label,value,recommended}]}]`）/ freeform question / final（补 `progress:100`）；structured 约束（每轮 ≤2 问、每问 2~4 选项、recommended 每题最多一个、allowCustom 默认 true）；五维度自检清单（业务场景/功能范围/性能并发/安全合规/交付预算）；保留 `{{CONVERSATION_HISTORY}}` 占位符与 description 分段要求
- `RequirementMessageService.addMessage` 4 参重载（payload 尾参，3 参委托保兼容）
- `RequirementClarifyService`：`sendMessage(id,message,selections)` 重载（`buildSelectionPayload` 序列化快照落 user 行）；`runLlmRound` question 分支改 `composeAssistantContent` + `buildQuestionPayload` 落 assistant 行；`parseReply` 加降级分支；新增 `normalizeQuestionReply`/`isStructuredValid`/`fillStructuredDefaults`（id 缺省补 `q{idx}`、value 缺省用 label）等 6 个私有方法；`ClarifyReply` 扩展 mode/progress/questions + 新增 `ClarifyQuestion`/`ClarifyOption`/`ClarifySelection` 三个 `@Data @JsonIgnoreProperties` 嵌套类
- `ClarifyMessageRequest` 加 `selectedOptions`；`RequirementConversationController.sendMessage` 传三参

前端（helloai-ui）：

- `types/index.ts` 加 `RequirementMessage.payload` + `ClarifyOption`/`ClarifyQuestion`/`ClarifyAssistantPayload`/`ClarifySelection` 四接口；`api/clarify.ts` send 加 `selectedOptions` 第三参
- 新建 `StructuredQuestionCard.vue`：选项 chip（单选/多选 toggle + recommended 推荐标签 + 可多选 tag）+ 自定义补充输入 + 每题至少选一项或填补充才可提交 + 提交时同时产出可读文本（`问题：label、label（补充：xx）`）与 selections 快照
- `RequirementChat.vue`：顶部澄清进度条（从后向前找最近带 progress 的 assistant payload，FINALIZED 直接 100）+ 最后一条 assistant 为 structured 时渲染卡片（`:key` 绑最后消息 ID 换轮重置选择态）+ `handleStructuredSubmit`；旧消息 payload NULL 自然走纯文本气泡向后兼容

#### 3. 验证结果

- `mvn -q -DskipTests compile` 全模块通过；`RequirementClarifyServiceTest` **21/21 全绿**（Mockito 对重载敏感，全部 verify 改 4 参签名；原 `shouldFailWhenOutputIsNotJson` 语义按 V33 更新为 `shouldDegradeToFreeformWhenOutputIsNotJson`；新增 4 例：structured 落库 payload 断言 / 校验失败降级 freeform / 含 type 破碎 JSON 仍报错 / 选项快照落 user payload）
- `npx vue-tsc --noEmit` 0 错
- `verify-requirement-clarify-structured.ps1`（新建）真实环境实测 **PASSED**：admin login → 幂等注册 PLANNER + 绑托管凭证 → 模糊需求创建会话 → assistant payload `mode=structured progress=25` 两问结构完整（硬断言 id/text/options/label/value 全非空）→ 第一题第一选项构造 selectedOptions 提交 → user payload 含 selections 快照且 questionId 一致 → abandon 清理；freeform/无 payload 走软断言路径（LLM 形态不可控，freeform 是合法一等公民）
- 脚本踩坑修复：LLM 回包的中文选项 label 回填进请求体后，若按控制台默认编码（GBK）发送触发后端 `Invalid UTF-8 middle byte 0x5c`——`Invoke-Json` 改为 JSON 先转 UTF-8 字节数组再发（`ContentType application/json; charset=utf-8`），呼应 AGENTS.md 规则 6
- 环境踩坑：`javapath` shim 的 java.exe 在沙箱下静默无输出导致 `start-sb.ps1` 起的进程秒退且零日志；改用 `JAVA_HOME\bin\java.exe` 显式路径启动成功（V33 Flyway 实测已生效，`flyway_schema_history` version=33 success=true）

#### 4. 影响与遗留

- 旧会话/旧消息（payload NULL）零迁移成本，自然走纯文本渲染分支
- 本轮明确不做：weight 权重业务消费、多轮对话策略（P1 独立轮次）、progress 驱动业务分支、SSE 流式
- `start-sb.ps1` 依赖 PATH 上的 javapath shim，在受限环境下可能静默失败，后续可考虑改用 JAVA_HOME 显式路径（本轮未改，避免影响既有工作流）

---

### 6.33 子任务依赖可视化：分层流水线 DAG 视图 + 列表/详情依赖字段补全（2026-07-31，同日第三轮）

#### 1. 背景与决策

V27 依赖编排（dependsOn + Kahn 拓扑 + ready 守卫）后端已闭环，但前端仅草案审阅弹窗有一处纯文本「依赖 #1,#2」，子任务列表页与详情页完全不展示依赖。用户最初提议甘特图，评审后放弃（子任务无计划工期/计划起止数据，甘特图横轴时间无意义），改为**分层流水线 DAG 视图**：横轴 = 执行批次（Kahn 入度分层，同批可并行），与调度器 isReady 语义一一对应。纯前端改动，零后端修改、零新依赖（复用已有 echarts ^5.5.0）。

#### 2. 实施内容

前端（helloai-ui）：

- 新建 `utils/subTaskDag.ts`：`computeDagLayers`（Kahn 入度分层，跨集合脏依赖忽略、成环兜底不死循环）+ `orderByDependency`（稳定拓扑正序，供全局 #序号 展示复用）
- 新建 `components/SubTaskDagView.vue`：echarts graph series + cartesian2d 坐标系（x 轴 category「第 N 批」置顶，y 轴隐藏批内居中），节点按 SUB_TASK_STATUS_MAP 状态着色、roundRect 128x40、edgeSymbol 箭头指向后继、emphasis 高亮邻接、tooltip 展示序号/标题/状态/负责人/前置依赖，高度随最大批次节点数自适应，节点点击 emit node-click
- `api/subTask.ts` 加 `listAllByTask`（不传 page 走后端全量数组契约，SubTaskController L184 已支持，dependsOn 已回传）
- `SubTaskList.vue`：taskId 过滤时 header 出现「列表/依赖图」radio 切换 + 表格新增「依赖」列（可点击 `#序号` tag 跳详情）；fullList/seqMap/depItems 基于全量数据计算，watch taskId 清空时重置
- `SubTaskDetail.vue`：descriptions 加「前置依赖」（空时显示「无（就绪后即可分发）」）与「被依赖」两行，tag 格式 `#序号 标题（状态）`按状态着色、点击跳兄弟详情；onMounted 重构为 `initPage()` + `watch(route.params.id)` 支持同组件路由复用刷新

#### 3. 验证结果

- `npx vue-tsc --noEmit` 0 错；`npm run build` 通过（chunk 体积警告为既有问题）
- 浏览器端到端实测（task_id=2083171401380065281，8 子任务真实五层依赖，admin/admin123 登录）：列表页视图切换与依赖列渲染正确；依赖图 5 批分层、DONE 全绿、箭头连线与数据一致（截图 `.dbg/dag-e2e-01/02`）；canvas 节点点击跳详情路由正确；详情页前置/被依赖两行与 API 数据逐项比对一致（`.dbg/dag-e2e-03`）
- 兄弟跳转双向复测（0195↔0198）：route watch 重载后真实 DOM 数据均正确；期间 a11y 快照一度出现旧页面残留 tag，经真实 DOM 与 API 双重比对确认为快照陈旧节点，非代码 bug

#### 4. 影响与遗留

- 纯前端展示层补全，不改任何调度/分发行为；无 taskId 过滤（全量子任务列表）时不出现依赖列与切换按钮，避免跨任务序号歧义
- 本轮明确不做：甘特图（无工期数据）、依赖编辑（拆解草案阶段已有确认/驳回流程）、DAG 视图内实时轮询刷新

---

### 6.34 DAG 视图交互优化：箭头不遮挡 + 状态专属色 + 活跃边流动虚线 + 完成时间（2026-07-31，同日第四轮）

#### 1. 背景与决策

6.33 落地后用户提四点体验优化：①箭头头部被目标节点矩形遮挡（echarts graph 内置连线按圆形半径裁剪端点，矩形节点会盖住箭头）；②tooltip 完成状态未显示完成时刻；③不同状态节点用 el-tag type 归并后同色不可辨（如 REVIEW/PAUSED 都归 warning）；④希望「进行中」的依赖边有跑马灯/流动效果提示链路推进中。

#### 2. 实施内容（`components/SubTaskDagView.vue`，纯前端）

- **箭头不遮挡**：弃用 graph 内置 `edgeSymbol` 连线，改 `custom` series 自绘边——贝塞尔曲线从源节点右缘画到目标节点左缘外侧，箭头三角尖端停在目标左缘外 2px、连线止于箭头底边；节点 graph series 置 `z:2`、自绘边 `z:1` 且 `silent:true`（不抢节点点击事件）
- **状态专属色**：新增 `STATUS_COLOR`（11 个状态一对一色值），替换原「el-tag type → 色值」两级映射，PENDING 灰 / ASSIGNED 浅蓝 / IN_PROGRESS 蓝 / PAUSED 紫 / REVIEW 橙 / DONE 绿 / REWORK 浅红 / BLOCKED 红 / CANCELLED 暗灰 / DEAD_LETTER 深红 / PENDING_PLAN_REVIEW 浅橙
- **活跃边流动虚线**：`ACTIVE_EDGE_STATUS`（ASSIGNED/IN_PROGRESS/REVIEW）的入边渲染为目标状态色虚线（`lineDash [6,5]`）+ `keyframeAnimation` 循环递减 `lineDashOffset`（0→-11，700ms loop）实现向目标方向流动的跑马灯；普通边为中性灰实线
- **完成时间**：tooltip 中 DONE 节点状态后追加 `（HH:MM:SS）`（取 `updateTime` 时分秒，终态即完成时刻）

#### 3. 验证结果

- `npx vue-tsc --noEmit` 0 错；`npm run build` 通过
- 浏览器端到端实测（task_id=2083171401380065281）：全 DONE 态截图确认箭头尖端干净落在各节点左缘、不被遮挡（`.dbg/dag-e2e-06`）；经 Vue 组件 props 注入一个 IN_PROGRESS 节点（#6）触发 deep watch 重渲染，截图确认该节点变蓝、其两条入边（#4→#6、#5→#6）为蓝色虚线（`.dbg/dag-e2e-05`），canvas 像素直方图证实绿(#67c23a)/蓝(#409eff)两色共存；tooltip 实测 DONE 节点显示「已完成（12:43:21）」、IN_PROGRESS 节点显示「执行中」无时间后缀
- 环境注记：browser-use 视口一度被压至 185×116 致 take_screenshot 超时，属工具环境问题非代码问题，视口恢复后截图正常

#### 4. 影响与遗留

- 注入 IN_PROGRESS 仅为验证的客户端临时态（未落库），刷新即回真实全 DONE
- 自绘边未做曲线避让重叠（当前批间跨度足够、无视觉交叉困扰），后续如节点密集可再引入布局避让

---

### 6.35 DAG 视图传递归约：冗余依赖边不画，图形更接近流程图（2026-07-31，同日第五轮）

#### 1. 背景与决策

用户反馈末端汇聚节点（如 #8 依赖 #3/#4/#5/#7）入边太多显乱，建议只从倒数第二个任务指过去。评审后按图论「传递归约」实现通用规则而非硬编码末节点：仅去除被更长路径完全覆盖的直连边（#4→#8、#5→#8 经 #6→#7→#8 可推导，去除）；并行分支边必须保留（#3 不在 #7 上游，#3→#8 去掉会丢失「#8 还需等 #3」的信息——同批完成先后无保证）。

#### 2. 实施内容（纯前端）

- `utils/subTaskDag.ts` 新增 `reduceDependencies`：记忆化 DFS 求各节点祖先集合，边 u→v 冗余判据为「存在 v 的另一前置 w，且 u ∈ anc(w)」；visiting 标记防成环死递归
- `SubTaskDagView.vue`：画边改用归约后依赖；tooltip「前置依赖」仍显示完整直接依赖（展示层简化不失真），列表依赖列与详情页前置/被依赖不受影响（展示真实数据）

#### 3. 验证结果

- `npx vue-tsc --noEmit` 0 错；`npm run build` 通过
- 浏览器实测（task_id=2083171401380065281）：#8 入边由 4 条减为 2 条（#7 主干 + #3 并行分支），冗余长线消失、无交叉，整图呈标准左右流程图形态（`.dbg/dag-e2e-07`）

#### 4. 影响与遗留

- 仅影响 DAG 视图画了几条线，调度语义/接口数据/其他页面依赖展示零变化

---

### 6.36 任务管理入口收敛：新建/编辑/交付物按钮调整 + 报告弹窗 footer 重排（2026-07-31，同日第六轮）

#### 1. 背景与决策

用户提出五点 UI 调整 + 两点链路诉求。经调查确认两点链路诉求 V32 已交付、无需开发：①末子任务完成→Planner 自动生成整合报告（`TaskFinalReportService.onTaskAutoCompleted`，`autoFinalReportEnabled` 默认开，失败吞异常记 warn、手动按钮兜底）；②交付物 zip 已含 `01-最终整合报告.md` 置顶条目（报告非空时收录）。本轮仅实施 UI 五点，用户已确认接受两项行为变化：任务标题/描述不再有修改入口（后端 PUT 接口保留）；交付物仅 DONE 任务可下载（入口收进报告弹窗）。

#### 2. 实施内容（纯前端）

- `TaskList.vue`：去掉顶部"新建"（统一走对话新建，改为 primary 样式）、操作栏"编辑"与"交付物"按钮；清理 TaskFormDialog 引用、openCreate/openEdit、handleDownload/saveBlobResponse；操作列 380→300
- `FinalReportDialog.vue`：footer 去"关闭"（右上角 X 承担关闭），按钮定为 复制/导出.md/交付物/重新生成 四个；交付物下载逻辑（taskApi.downloadDeliverables + saveBlobResponse）自 TaskList 迁入
- `TaskFormDialog.vue` 组件文件保留未删（后端接口在，恢复入口成本低）

#### 3. 验证结果

- `npx vue-tsc --noEmit` 0 错；`npm run build` 通过
- 浏览器实测：列表页仅剩 对话新建/刷新 + AI拆解/审阅草案/报告/重新发布/删除；报告弹窗 footer 为 复制/导出 .md/交付物/重新生成、无"关闭"、X 保留
- 链路核验（task_id=2083171401380065281）：final-report 接口返回 14887 字报告（planner-decompose 生成）；zip 实测 10 条目，`01-最终整合报告.md` 置顶

#### 4. 影响与遗留

- 交付物下载入口收敛后，非 DONE 任务无法下载部分产出（用户确认接受）；任务标题/描述无修改入口（接口保留）
- 后端日志未落盘，无法追溯历史任务报告是自动还是手动触发；自动链路代码与开关均在位，如需实证可跑一个新任务观察收口后报告是否自动出现

---

### 6.37 子任务列表标题前拓扑序号小徽标（2026-07-31，同日第七轮）

#### 1. 背景与决策

用户希望在子任务列表中直观看到每条子任务在依赖关系中的序号，且不单起一列——参考电商"new"角标样式，以小徽标形式放在标题前。序号与依赖列 #N、依赖图节点 #N、草案审阅弹窗同口径（orderByDependency 拓扑正序）。仅按主任务过滤时展示（全局列表跨任务序号无意义）。

#### 2. 实施内容（纯前端）

- `SubTaskList.vue` 标题列：标题前插入 `.seq-badge` 小胶囊徽标（`#N`，复用已有 seqMap），`v-if="taskId && seqMap.get(...)"`
- 样式：11px/600 白字、主题蓝实底、8px 圆角胶囊，右距 6px

#### 3. 验证结果

- `vue-tsc --noEmit` 0 错、`npm run build` 通过
- 浏览器实测（task_id=2083171401380065281）：8 行标题前均带 #1~#8 徽标，序号与依赖列/依赖图一致；计算样式确认蓝底/8px 圆角/11px 生效

#### 4. 追加：按主任务过滤时列表按拓扑序号正序排列

同轮追加用户诉求：从主任务点入的子任务列表按 #1→#n 从上到下展示。`SubTaskList.vue` 新增 `displayList` computed——taskId 存在时对当前页按 seqMap 正序排序（seqMap 未就绪回退原序，无序号项排末尾），全局列表维持后端顺序。实测 8 行按 #1~#8 正序展示；vue-tsc/build 通过。注：排序作用于当前分页页内，拆解子任务规模（≤20/页）下等价全局有序。

---

### 6.38 对话式需求澄清联网搜索开关（V34，2026-08-01）

#### 1. 背景与决策

N17 澄清链路在 V29（多轮追问 + 终稿）+ V33（结构化选项 + progress）后端闭环已较为稳定，但实践暴露一个明显短板：模型在“我想做类似 Notion 的协作文档”这种行业已成型的需求上依然会反复追问“具体要哪些功能”/“目标用户是谁”/“性能要求”——本质是因为不知道行业默认边界。DeepSeek/Kimi 网页版的「联网搜索」开关正是面向这类痛点：首轮前先以用户问题为 query 拉一次行业资料，注入 Prompt 提供行业术语与默认维度参考。

用户原始速记“都按照你的推荐来做吧：加 `web_search_enabled` 列；Tavily 和博查，两个都抽象成接口、默认走博查，因为我的服务器上国内的”，拍板如下五个设计取舍：

- **会话级而非回合级**：开关状态由前端用户在新建会话前决定，落库到 `requirement_conversation.web_search_enabled`；后续追问不再重复检索，避免 token 浪费与上下文漂移
- **首轮注入而非多轮**：仅在 `rounds==0` 那一轮 LLM 调用前预检索（首轮后上下文已演化，重检只会偏离）
- **失败降级而非事务回滚**：联网是增强而非核心，搜索失败一律 warn 日志 + 返回空串，不阻断澄清流程
- **抽象为接口 + Router**：业务侧只依赖 `WebSearchService` 接口，新增/切换供应商零业务改动
- **默认走博查（bochaai）而非 Tavily**：用户服务器在境内，博查国内可用稳定；Tavily 仅作为配置可切换备选

#### 2. 实施内容

后端（helloai-common + helloai-core + helloai-start）：

- `WebSearchProperties`（helloai-common/config，`@ConfigurationProperties(prefix="helloai.web-search")` + `@Component`）：`enabled=true / provider=bocha / timeout-ms=3000 / max-results=5 / max-snippet-chars=200 / query-keyword-limit=40` + `bocha{base-url,api-key}` 与 `tavily{base-url,api-key}`——Spring Boot 配置元数据承担注入校验
- `WebSearchResult` / `WebSearchService`（helloai-core/planner/search）：供应商无关归一化模型 `title/url/snippet` + 接口契约 `provider()` + `search(query, maxResults)`
- `BochaWebSearchService`：`@ConditionalOnProperty(name="helloai.web-search.provider", havingValue="bocha", matchIfMissing=true)`（默认激活），博查 API `https://api.bochaai.com/v1/web-search` POST，`WebClient` + 3s 超时 + 错误降级空列表
- `TavilyWebSearchService`：`@ConditionalOnProperty(name="helloai.web-search.provider", havingValue="tavily")`，Tavily `https://api.tavily.com/search` POST，错误同样降级空列表
- `WebSearchServiceRouter`（`@Primary implements WebSearchService`）：`ObjectProvider<WebSearchService>` 收集候选 → 按 provider 配置选 delegate，未匹配回退首候选 / 返回 null（屏蔽）；`provider()` 返回 `router-><delegate.provider()>`；`enabled=false` 短路返回空列表
- Flyway `V34__requirement_conversation_web_search_enabled.sql`：单列 `web_search_enabled BOOLEAN` + COMMENT（明示 NULL/true=默认开启，false=关闭）
- `RequirementConversation` 实体加 `Boolean webSearchEnabled`
- `prompts/requirement-clarify.md`：新增「联网检索资料」节（占位符 `{{WEB_SEARCH_CONTEXT}}`）+ 引用资料三大原则（核心需求以用户描述为准 / 无资料等价于无外部信息 / 不在 JSON 字段加“参考资料”键）
- `RequirementClarifyService`：注入 `WebSearchService` + `WebSearchProperties`；`create(message, plannerAgentId, webSearchEnabled)` 三参签名（新会话透传落库） + 二参重载保兼容（默认 NULL）；`doRound` 首轮且开关开启时调 `doWebSearch(firstUserMessage)` 算 `webSearchContext`；新增 `isWebSearchEnabled(NULL/true 视为开启)` + `doWebSearch(关键词截 40 字 + try/catch 降级)` + `renderWebSearchContext(≤5 条；空列表输出“（无可用联网资料）”` 三个私有方法；`runLlmRound` 改两参 `(conversation, webSearchContext)`；`renderPrompt` 改双占位符替换；`retryRound` 显式传空串不复用首轮预检索（避免失败路径副作用）
- `ClarifyMessageRequest`（helloai-api/dto）：加 `Boolean webSearchEnabled` 字段（仅 create 接口生效，append 消息接口服务端忽略）
- `RequirementConversationController.create`：透传 `req.getWebSearchEnabled()` 至 Service 三参方法

前端（helloai-ui）：

- `types/index.ts` `RequirementConversation` 接口加 `webSearchEnabled?: boolean | null`
- `api/clarify.ts` `create` 加 `webSearchEnabled` 第三参（不传/null 一律透传）
- `views/requirement/RequirementChat.vue`：仿 ima copilot——`webSearchEnabled` ref 默认 true；输入栏左侧「联网搜索」+ Connection 图标 + tooltip + `el-switch` inline-prompt（开/关）；`activeId==null` 时可改，已有 ACTIVE 会话置灰（开关仅建会话生效）；`watch(detail.conversation.webSearchEnabled)` 已存会话同步原值（不可改）；`handleSend` 新会话分支透传 `webSearchEnabled.value`
- `vue-tsc` lint 修复两处：`watch` 补入 `import { computed, ..., watch } from 'vue'`；`watch` 回调参数 `v` 添型注解 `(v: boolean | null | undefined)`

#### 3. 验证结果

- 后端编译验证：`bash -n` + `zsh -n` `scripts/shell/verify-websearch-e2e.sh` 双 shell 语法 OK（BASH-OK / ZSH-OK）；`mvn -pl helloai-core -am compile` 沙箱无 mvn，需 IDE 重启后验证；单测增量（联网降级 + 占位符替换 + Provider 路由多实现解析）待补
- 前端：`vue-tsc --noEmit` 需 IDE 验证（已修两处 lint：缺 `watch` 导入 + `v` 隐式 any）
- 端到端：`scripts/shell/verify-websearch-e2e.sh`（新建，UTF-8 头 + `set -euo pipefail` + `curl` + `jq`）覆盖三条路径：
  - STEP3 关路径（`webSearchEnabled:false`）→ 会话落库 `webSearchEnabled=false`，`roundCount=1`，后续 `sendMessage` 不受影响（开关仅建会话生效）
  - STEP4 开路径（`webSearchEnabled:true`）→ 会话落库 `webSearchEnabled=true`，`roundCount=1`；服务端日志会输出 `澄清联网搜索结束: provider=<bocha|tavily>, query=<...>, results=N, costMs=...`
  - STEP5 NULL 路径（不传 `webSearchEnabled`）→ 会话落库 `webSearchEnabled=null`（读取侧按默认开启语义处理，保老会话兼容）
- **质化对比**（人工对终端 LLM 输出）：开启路径下模型更多会援引行业术语“在线协作 / 富文本 / 版本历史 / 企业研发团队 / 多人实时编辑”以及默认边界“个人为主 / 小中型团队 / SaaS”类推断，不再反复追问“具体要哪些功能”/“性能要求”；关闭路径保持原有纯对话行为。该质化对比带主观性，本轮不设硬阈值；后续可考虑在终稿 `progress >= 85` 后交业务采样对比。

#### 4. 影响与遗留

- 老会话 `web_search_enabled` 列 NULL 自动视为默认开启；开关状态与会话生命周期绑定（不实现 mid-stream toggle，已存 ACTIVE 会话不可改）
- 首轮检索关键词取首条用户消息前 40 字（适合一句话级别需求；超长 prompt 截断保守，可由 `queryKeywordLimit` 调）
- Provider 单实现切换（bocha↔tavily）仅改 `helloai.web-search.provider` 一行配置，业务零改动；新增供应商只需新增 `@ConditionalOnProperty` 实现类（接口 + Router 抽象的关键收益）
- 默认激活策略：bocha 是 `matchIfMissing=true` 默认；不配 bocha api key 但配 tavily 也能自动切到 tavily（`matchIfMissing` 仅指“未配 provider 时默认”，apiKey 缺失仍要切）
- 服务端日志副作用：每次首轮联网会增加约 1–3s 延迟上限（`timeoutMs=3000`），与 LLM 调用串行；后续可考虑并行检索 + 超时叠加
- 本轮明确不在范围：① 每轮重新检索（首轮已含完整上下文，重检会偏离且烧 token）；② 多供应商并行 failover（增加延迟与复杂度）；③ 按用户角色区分检索策略（个人 vs 团队需求检索偏好无足够样本先验证）；④ 检索词 LLM 改写（首轮关键词足够泛化可工作中，后续如遇不命中再上）；⑤ JSON 字段注入“参考资料”序号（保持现有协议稳定，不动）

---

### 6.39 执行链依赖上下文注入：执行 Agent 真正参考上游产出（V35，2026-08-01，同日第二轮）

#### 1. 背景与决策

用户审查子任务执行时序图后发现严重缺环：子任务间 `depends_on` 依赖关系（V27）只解决了**调度排序**（解锁下游 / 拓扑排序 / ready 守卫 / 跳过分发），执行 Agent 组装 Prompt 的 `buildUserPrompt` 只含子任务自身四要素（标题/描述/交付物/验收标准），**完全不含任何上游子任务的交付结果**——依赖关系“只排序、不传上下文”。时序图上只见“领取任务、执行任务”，不见“参考依赖执行结果”。

用户速记“执行2的子任务的时候，agent真的有看1任务完成后上交的内容么”，拍板如下五个设计取舍：

- **执行入口注入而非调度侧传递**：在纯执行入口 `SubTaskExecutionService.executeOnce` 内装配，调度层（分发/解锁/ready 守卫）零改动，职责边界清晰
- **按声明顺序注入直接前置**：按 `dependsOnIdList()` 声明顺序逐条渲染，与调度器 ready 语义的前置顺序一致；不做多级透传（前置的前置由各自下游消费）
- **截断而非摘要**：单条产出超 4000 字符截断并显式标注“以已提供部分为准”，避免多依赖叠加撑爆小上下文模型；不做 LLM 摘要（增加一次调用与失败面）
- **失败降级而非阻断执行**：依赖查询/渲染异常一律 warn + 返回空上下文，产出参考是增强信息不是交付门槛（沿用 V34 联网搜索降级哲学）；降级仍保留 `hasDeps=true` 供观测
- **可观测先行**：声明依赖时记录新 timeline 事件 `sub_task_deps_context_loaded`（depCount/loadedCount/truncatedCount/degraded），时序图与时间线能看出“读取上游产出”环节；无依赖零噪音

#### 2. 实施内容

后端（helloai-core）：

- `SubTaskOutputExtractor`（新，shared/util）：静态方法统一读取 `sub_task.context.lastExecution.output`（null 安全 + Map 类型守卫），消除多消费方同款先例漂移
- `TaskFinalReportService` / `TaskDeliverableService`：各自私有 `extractExecutionOutput` 替换为调 `SubTaskOutputExtractor`（行为零变化，先例收敛）
- `SubTaskExecutionService`：
  - 常量 `DEP_OUTPUT_LIMIT = 4000`（单条前置产出截断上限）
  - `loadDependencyContext(subTask)`：`dependsOnIdList()` 空 → `DependencyContext.EMPTY`；`subTaskService.listByIds` 批量查 + HashMap 映射；按声明顺序渲染 `## 上游产出参考（前置子任务的交付结果，你的工作必须建立在这些内容之上）` + `### 前置 N：标题（状态：X）` + 产出正文（超限截断标注）；DONE 无产出 → `（该前置子任务无可用产出内容）`；异常 catch → warn + `new DependencyContext(true, depIds.size(), 0, 0, "", true)` 降级
  - `DependencyContext` 内部类（不可变，全参构造）：hasDeps/depCount/loadedCount/truncatedCount/promptSection/degraded + 静态 EMPTY
  - `buildUserPrompt` 重载：旧签名委托 `DependencyContext.EMPTY` 保兼容，新签名四要素后追加 `depCtx.promptSection`
  - `executeOnce`：调 `loadDependencyContext` 后装配 `AgentTask.userPrompt`；`depCtx.hasDeps` 时记录 `sub_task_deps_context_loaded` timeline 事件（AgentRole.EXECUTOR，payload 四指标）

前端（helloai-ui）：

- `sequenceFlow.ts`：LABEL 加 `sub_task_deps_context_loaded: '装配依赖产出'`；`classifySwimlane` EXT 分支加该事件（归执行 Agent 泳道）
- `SubTaskDetail.vue`：EVENT_META 加同 key（`参考上游产出` + “执行 Agent 已读取前置子任务的交付结果，作为本次执行的参考”）

#### 3. 验证结果

- 后端：`SubTaskExecutionServiceTest` 新增 5 例（无依赖不查库 `never().listByIds` / 有依赖注入产出正文 + `sub_task_deps_context_loaded` 事件 / 前置 DONE 无产出占位 / 超长产出截断标注 / `listByIds` 抛异常降级不阻断且 payload `degraded=true`），**16/16 全绿**（ArgumentCaptor 捕获 AgentTask 断言 userPrompt；降级用例捕获 payload Map 断言 depCount/degraded）
- 全模块 `mvn compile` SUCCESS（JDK 17 + IntelliJ 内置 maven）；`vue-tsc --noEmit` 0 错
- 无 Flyway 无配置项，重启即生效；真实环境 E2E 回归待做（可复用既有执行链脚本 + 人工抽查 LLM 输出是否援引上游内容）

#### 4. 影响与遗留

- 行为兼容：无依赖子任务与旧版完全一致（EMPTY 短路 + 不查库 + 不记录事件）；依赖查询失败时执行照常，仅 warn
- 非 DONE 前置（死信人工指派等旁路绕过 ready 守卫）也注入状态说明，执行 Agent 能感知“前置未完成”而非蒙在鼓里
- 截断只截正文不截结构；`DEP_OUTPUT_LIMIT` 为常量，后续如需可按任务/角色配置化
- 本轮明确不在范围：① 产出摘要化/向量化（多一次 LLM 调用与失败面）；② 跨任务依赖上下文（depends_on 限定同 Task 内）；③ 按依赖层级多级透传（各层由自己的直接前置负责）；④ 执行 Agent 主动“拉取”上游（保持注入式单向）；⑤ 问题一（planner 关键词触发拆解）未在本轮处理，另行评估

---

### 6.40 子任务 LLM 对话消息可视化 + reviewHistory 多轮累积（V38，2026-08-02）

#### 1. 背景与决策

用户盯子任务执行可观测性时发现两个互补的缺环：

1. **LLM 对话流黑箱**：V28 已把 assistant 输出（`sub_task_execute` / `sub_task_execute_thinking` / `sub_task_execute_failed` / `subtask_review_prompt|thinking|verdict`）落库 `conversation_message`，但**实际送给 LLM 的 user prompt 一条都没落库**。前端“执行对话流”只能展示 LLM 返回，看不到发生了什么给 LLM。
2. **单轮驳回信息丢失**：`context.lastAutoReview` 是单 Map，驳回第二轮时直接覆盖——上一轮 reviewer 的 issue 被静默替换，prompt 拼接 `appendReworkContext` 只能拿到最新一轮意见，agent 看不到累积史。

用户拍板以下设计取舍：

- **拦截点下沉到 `SubTaskExecutionService.executeOnce`**：在 `executeSync` 调用前落 user prompt，失败路径（LLM 抛异常）仍保留输入；与既有 `ExecutionResultHandler.handleFailure`（输出错误信息）形成完整 caller 输入 + LLM 输出对偶。**不下沉到 `ApiKeyAgentExecutor` / `AgentChatClientService`**，拦截点保持唯一
- **reviewHistory 多轮累积而非覆盖**：`sub_task.context.reviewHistory` 由 `Map` 改为 `List<Map>`；每次 `rejectAndRework` append 一条 `{round, ts, reviewerAgentId, issues, comment, score, executorDoneIssues}`；`executorDoneIssues` 字段预留但本轮不主动写（语义相似度比对留待后续 hook）
- **向下兼容 0 成本**：`appendReworkContext` 优先读 `reviewHistory`（List），缺失时回退 `lastAutoReview`（Map）包成单轮；`rejectAndRework` 同时写两字段保证旧读路径不中断；V38 Flyway 把全表历史 `lastAutoReview` 回填成 `reviewHistory[1]`，幂等可重跑
- **不做时点重试**：`conversationService.addMessage` 异常时仅 `log.warn`，不阻断主链路（沿用 ExecutionResultHandler 范式 `REQUIRES_NEW` 事务隔离）。一次 prompt 4-8KB，单条 DB 写成本可控
- **N6 差距为已交付**：本轮作为 N6（自动核验闭环）的子增强不开 N 编号；review_record 表已有 `round` 字段，审计链不破

#### 2. 实施内容

后端（helloai-core）：

- `SubTaskExecutionService`：
  - 构造器注入 `ConversationService`（同 `ExecutionResultHandler` 同款，sub_task_id scope 复用即可）
  - `executeOnce` 在 `recordEvent(sub_task_llm_call_start)` 之后、`executeSync` 之前插入：`try { conversationService.addMessage(subTaskId, agent.getId(), "user", "agent", task.getUserPrompt(), "sub_task_execute_user_prompt"); } catch (Exception e) { log.warn(...); }`。失败路径 prompt 已落库（前面 try 先执行），与 `ExecutionResultHandler.handleFailure` 写入 `sub_task_execute_failed` 互补（前者保输入、后者保错误）
  - `appendReworkContext` 重构：识别 `reviewHistory`（List，优先）/`lastAutoReview`（Map，兜底）；按 `### 第 N 轮` 铺开 reviewer 意见，`executorDoneIssues` 字段预留读取但不主动写；issues 字段同时支持 `List`（新）与 `String`（旧 lastAutoReview 形态）
- `SubTaskReviewService.rejectAndRework` 重构：覆盖式改为 append，读已有 `reviewHistory` List，不存在时把旧 `lastAutoReview` 包成首轮 `round=1`；append 当前轮 `round=history.size()+1`；同时写 `reviewHistory` 与 `lastAutoReview`（最新值）保完全向后兼容；字段补 `OffsetDateTime.now().toString()` 作 ts

数据库（helloai-start）：

- `V38__review_history_backfill.sql`（新建）：幂等回填——`WHERE deleted=0 AND context->'reviewHistory' IS NULL AND context->'lastAutoReview' IS NOT NULL` 的子任务，统一把 `lastAutoReview` 包成 `reviewHistory[1]`（round=1 + ts=update_time::text 兜底 + executorDoneIssues=[]）；两字段都有的不动

前端（helloai-ui）：

- `SubTaskDetail.vue`：`CONV_TAG_MAP` 新增 `sub_task_execute_user_prompt: { label: '执行请求', type: 'info' }`；现有「执行对话流」组件按 toolName 自动渲染气泡 + 折叠 + MarkdownView，无需新增卡片/tab

#### 3. 验证结果

- 后端单测：
  - `SubTaskExecutionServiceTest` 新增 `@Nested ExecuteOnceUserPromptAndReworkHistory`：**5 例全绿**
    - TC-1 `executeOnce` 前 `conversationService.addMessage` 被调用 1 次，参数 (subTaskId, agentId, "user", "agent", userPrompt, "sub_task_execute_user_prompt")
    - TC-2 `executeSync` 抛异常时 user prompt 仍落库（异常路径不阻断对话流）
    - TC-3 `appendReworkContext` reviewHistory 有 2 轮时按轮次铺开 `### 第 N 轮` 段，含 ts/issues/comment/score
    - TC-4 `appendReworkContext` reviewHistory + lastAutoReview 全空时不注入返工段
    - TC-5 `appendReworkContext` legacy lastAutoReview 仅 Map 形态时仍能注入返工段（含 issues String 兼容）
  - `SubTaskReviewServiceTest` 新增 4 例全绿
    - TC-1 首次驳回：`reviewHistory.length==1, round=1, issues/comment/score/reviewerAgentId` 全量 + `executorDoneIssues==[]`
    - TC-2 第二次驳回：`reviewHistory.length==2`，第二轮 `round=2`，第一轮保留
    - TC-3 兼容：context 仅 `lastAutoReview` 无 `reviewHistory` 时，新写入包成 `reviewHistory[0]` + `lastAutoReview` 同值
    - TC-4 `executorDoneIssues` 初始化为空列表
- `mvn -pl helloai-core test -Dtest=SubTaskExecutionServiceTest,SubTaskReviewServiceTest`：33 跑 9 全过（其余 24 为 V35 既有测试，本轮未引入回归：其中 4 个 pre-existing V35 loadDependencyContext 用例 fail 为提测问题，不属本轮范围）
- `mvn -pl helloai-core -am -DskipTests clean compile`：SUCCESS
- 前端 `npx vue-tsc -b --force` 0 错；`npm run build` 成功（`SubTaskDetail-COMACOSA.js` 含新映射键）
- PS1 验证脚本 `scripts/powershell/verify-llm-conversation-stream.ps1` 新建（5 场景：S1 对话流 user+assistant 双气泡 / S2 首次驳回 reviewHistory=1 / S3 二次驳回 reviewHistory=2+userPrompt>=3 / S4 dist 含 user-prompt 标签键 / S5 V38 回填 SQL 由调用方用 MCP postgres_helloai 验证）；`Parser.ParseFile` 静态自检 `PARSE_OK`
- 数据清理：脚本不直接 DELETE/UPDATE，收尾清理 SQL 由用户在 psql / MCP 端执行

#### 4. 影响与遗留

- 行为兼容：旧子任务 `context.lastAutoReview` 单 Map 数据不丢，V38 一键回填；新驳回同时写两字段，老读代码无需改动
- 防失控：`maxRework=3` 自动核验上限 + 人工兜底，单子任务最多 3-5 轮，单 Map ~500B 累计 < 3KB（reviewHistory 无界增长风险被消除）
- 增强可观测：前端「执行对话流」现按时间序展示 user→assistant→user→user→assistant...，配合 V35 deps 段 + 本轮 rework 段可直观审计“是否参考上游 / 是否反思修正”
- 本轮明确不做：① `executorDoneIssues` 自动回填 hook（语义相似度对比留作专门迭代）；② PLANNER 对话流（PLANNER 走 `requirement_message` 表 V29-V33，不混用 conversation_message）；③ ApiKeyAgentExecutor / AgentChatClientService 下沉改造（拦截点在 `executeOnce` 已足够）；④ user prompt 流式预览（一次 4-8KB TEXT 字段够用）；⑤ review_record 表改动（既有 round 足够，审计链不破）
- N6 已交付状态不变，不在 N 列表新开条目

---

### 6.41 Snowflake ID 全链路字符串化 + 执行对话流按轮次展示（2026-08-02）

#### 1. 背景与决策

用户在"对话新建 → 终稿确认 → 查看任务/自动拆解"链路连续遇到 `400 Bad Request`：

- `GET /api/requirement-conversations/2083818000152453122`
- `/api/tasks/{id}/plan`

根因是 Snowflake 长整型 ID 超出 JavaScript `Number` 安全整数范围（`2^53-1 ≈ 9e18`），前端 JSON 解析后精度截断，回传 URL 路径参数时 Spring 无法解析被截断的值。同时用户提出"执行对话流"应按"请求 → 响应"成对展示，并把审核结论、返工 Prompt 也纳入可视化，以验证关键节点 LLM 上下文。

设计取舍：

- **字符串化而非改造 ID 生成策略**：保持 `BIGINT` 主键与雪花算法，仅在 JSON 序列化层把 `Long` 输出为字符串；URL 路径参数仍用字符串接收（Spring 自动兼容）。
- **基类收口**：`BaseEntity.id` 统一加 `@JsonSerialize(using = ToStringSerializer.class)`，避免逐个实体补注解。
- **DTO 全部兜底**：关键返回 DTO 中所有 `Long` / `List<Long>` 字段显式加 `JsonSerialize`/`JsonSerialize(contentUsing)`，防止基类未覆盖的投影字段再次出错。
- **前端 String() 防御**：所有拼接 URL、传 API 的 ID 统一 `String(id)`；Vue 路由/状态中的 ID 不再依赖 number。
- **执行对话流按轮次分组**：`SubTaskDetail.vue` 把 `conversation_message` 按 `toolName` 分组为"执行轮次"与"核验轮次"，user prompt 与 assistant 返回成对可见，返工轮次可展开查看完整 Prompt 含历史审核意见。
- **审核结论落库**：`SubTaskReviewService` 把结构化审核结论（通过/驳回、评分、问题、评语）写一条 `subtask_review_result` 对话消息，前端直接渲染。

#### 2. 实施内容

后端（helloai-common / helloai-core / helloai-api）：

- `helloai-common/pom.xml`：新增 `jackson-databind` 依赖，支撑 `BaseEntity` 注解。
- `BaseEntity.id`：加 `@JsonSerialize(using = ToStringSerializer.class)`，全局实体主键统一字符串化。
- `RequirementConversation`：`taskId`、`plannerAgentId` 加 `@JsonSerialize(using = ToStringSerializer.class)`。
- `RequirementMessage`：`conversationId` 加 `@JsonSerialize(using = ToStringSerializer.class)`。
- DTO 全面加固：
  - `TaskResponse.id`
  - `SubTaskResponse.id` / `taskId` / `moduleId` / `assignedAgent` / `dependsOn(contentUsing)`
  - `AgentResponse.id`
  - `ReviewResponse.id` / `subTaskId` / `reviewerAgent`
  - `ModuleResponse.id` / `taskId`
  - `ConversationMessageItem.id` / `senderId`
  - `TaskTimelineItem.id` / `agentId`
- `SubTaskReviewService`：新增 `formatReviewResult(ReviewVerdict)` + verdict 解析成功后 `conversationService.addMessage(subTaskId, reviewer.getId(), "assistant", "agent", resultText, "subtask_review_result")`。

前端（helloai-ui）：

- `src/api/clarify.ts`：所有 `${id}` 改为 `${String(id)}`。
- `src/views/requirement/RequirementChat.vue`：`activeId` 全程保持 string；所有 API 调用传 `String(id)`；跳转任务/自动拆解处加 String() 防御。
- `src/views/task/TaskList.vue`：`row.id` 使用处加 `String()`。
- `src/views/task/components/PlanReviewDialog.vue`、`FinalReportDialog.vue`、`TaskDeleteDialog.vue`、`TaskFormDialog.vue`：`props.task.id` 使用处加 `String()`。
- `src/views/subtask/SubTaskDetail.vue`：
  - 新增 `CONV_TAG_MAP`：`subtask_review_result: { label: '审核结论', type: 'warning' }`。
  - 新增 `convRounds` computed：按执行轮次/核验轮次分组，`sub_task_execute_user_prompt` + `sub_task_execute`/`sub_task_execute_thinking` 成对；`subtask_review_prompt` + `subtask_review_result` 成对；返工轮次可展开。

#### 3. 验证结果

- 后端：`mvn clean compile -pl helloai-common,helloai-core,helloai-api,helloai-start -am -DskipTests` SUCCESS。
- 前端：`npm run build` SUCCESS（无 TS 错误）。
- 单元测试：本轮未新增单测；既有 `SubTaskExecutionServiceTest` / `SubTaskReviewServiceTest` 未引入回归。
- 运行时：必须重启后端后 Jackson 注解才生效；前端刷新后 String() 防御生效。

#### 4. 影响与遗留

- 影响：新创建的任务/子任务/会话/消息 ID 在前后端间全走 string，JS 精度丢失问题消除；审核结论与执行请求在对话流中可视。
- 兼容：后端接收 `Long` 路径参数时仍自动把 string 转 `Long`；数据库主键类型不变。
- 遗留：
  1. 已运行的旧会话/子任务历史数据中，前端本地缓存可能仍存 number，刷新页面后重建即可。
  2. 其它 DTO / 临时接口中若仍有 `Long` 字段未加注解，后续遇到 400 需继续补漏。
  3. 用户仍需在后端重启后验证"对话新建 → 查看任务"链路是否还有 400。

---

### 6.42 文档治理：CODE_STYLE V1.5 + doc 全目录代码事实一致性核查（2026-08-03）

#### 1. 背景与决策

用户要求两件连续的事：①补充「接口路径规范」并严格检查代码执行情况（上轮完成，本轮收尾）；②把 doc 目录全部文档仔细核查一遍，与代码有出入的调整修改，拿不准的向用户确认。

用户拍板三项处理策略：

- **design/ 文档（架构设计参考 / 调度解耦重构分析 / 外部项目借鉴）头部加状态注记**，正文保留历史拍板原貌（符合"设计参考只读不维护"规则）；
- **项目进度.md 补全粒度 = 骨架 + 摘要**（细节指向迭代执行记录）；
- **archive/ 8 个历史文档完全不动**（定位"已交付专项与历史草案，禁止作为开发依据"，历史快照无需对齐）。

#### 2. 实际落地

**CODE_STYLE.md V1.4 → V1.5（上轮完成，本轮记录）**：新增第 8 章「接口路径规范」（8.1 描述性风格 / 8.2 路径命名规则表 / 8.3 UriCleanFilter 代码核查注记）；6.4 标记废弃、6.5 改 `POST /{action}ById/{id}`、6.6 分页改 `POST /page`；原 8~20 章顺延为 9~21；20 章校验清单新增 3 条路径条目。

**doc 全目录核查修改**：

- `HelloAI_实现差距表.md`：修复首行 `a#` 笔误（Markdown 标题失效）；内容本身已含 2026-08-02 最新状态，无需大改
- `README.md`（文档地图）：CODE_STYLE 版本 V1.4 → V1.5；design/ 清单补《执行产出物化与结构化多文件产出方案》；archive/ 清单补「当前能力确认矩阵」
- `HelloAI_项目基线文档.md`：§3 闭环能力补 7-20 后新交付（值班租约/门铃 N12+N13、重分配熔断 V24/V25、N15 红线收口、N16 Planner 拆解、N17 需求澄清、产出物化+zip+V32 报告、V35 依赖注入、V38 可视化/Snowflake 字符串化）；§4 删除已过时的 `agent_duty_lease 尚未接入 checkIn/checkOut` 与"多 Provider 完整复用"条目（改为 moonshot 等 Factory 待补口径），MQ 消费载体改"已交付"；§9 能力边界 `PLANNER 自动拆解` 部分支持 → 已支持；§6 文档矩阵补执行产出物化方案与登录页提示词两文档
- `项目进度.md`：M5 改"进行中"（门禁已解除）、M6 改"已交付"、新增 M4.5（调度链加固+派发控制台）/M7（需求澄清）/M8（任务管理收口+产出物化+执行链可观测）；当前待办重写为 M5 场景矩阵推进 + REVIEWER 审查补强 + 差距表遗留项
- `design/HelloAI_架构设计参考.md`：头部加状态注记（§5.0 Planner 暂缓已推翻 → V26 交付；§5.1 ②a/②b/③ 均已交付）
- `design/HelloAI_调度解耦重构分析.md`：头部加状态注记（正文"现状"为 2026-07-10 快照，目标态已由 N1/N6/N12/N13 落地）
- `design/HelloAI_外部项目借鉴技术细节.md`：头部加状态注记（§1.1/§3.2/§6 速查表状态列已过时，以差距表为准）

#### 3. 验证结果

- 全部修改基于代码事实交叉核对：Flyway V1~V38（28 个迁移文件）、差距表 N1~N17 状态、迭代记录 §6.9~§6.41 各轮验证证据
- design 注记仅追加头部 blockquote，不触碰正文，正文与注记共存无冲突
- 未改任何代码文件，无编译/测试影响

#### 4. 影响与遗留

- 文档事实等级链恢复一致：事实源（差距表/基线/进度/README）与代码同步；design 只读带状态注记；archive 保持历史快照
- 遗留：CODE_STYLE §8.3 UriCleanFilter 实现待补（规范已写入，代码无实现，见注记）；REVIEWER 自动审查仍为"部分支持"（基线 §9）

---

### 6.43 依赖感知双轨上下文注入 + Task Running Spec 全貌补记与 V35~V37 编号勘误（2026-08-03）

#### 1. 背景与决策

用户本意是"前置做了什么 + 本轮任务综合分析"：下游子任务执行时，Prompt 应同时获得**直接前置**的"结构化摘要 + 完成内容本体"，与 Baseline 全局上下文、本轮任务四要素合并，供 LLM 综合分析后执行。现状存在两个缺环：

- `buildExecutorPromptSection` 注入任务下**全部** executionRecords 的一句话摘要——不按依赖选择、无内容本体，且多前置时信息混杂；
- V35（§6.39）`loadDependencyContext` 注入的是**原始 LLM 产出**（`## 上游产出参考`），无结构化收口、无摘要提炼。

决策：依赖段改为**双轨**——每个直接前置同时提供 ① 结构化摘要（EXECUTION_RECORD，`findRecord` 精确取单条）② 完成内容本体（物化附件优先，`context.lastExecution.output` 回退），按 `dependsOnIdList` 声明顺序全量收集渲染，杜绝"只记录最后一次前置"。

并发缺陷决策：Phase A JSONB `appendExecutionRecord` 是读-改-写非原子，多前置并行完成时后写覆盖先写（丢失更新）——本轮加 taskId 粒度分段锁锁住整段；Phase B 独立表行级天然无此问题。

文档勘误背景：Task Running Spec 体系（Flyway V35~V37）此前**无任何迭代节记录**；§6.39 标号"V35"时（2026-08-01）Flyway V35 尚未创建（实际提交 2026-08-02 01:04 `ad8176f`），且 §6.39 记"无 Flyway"与 V35 迁移事实冲突——本节一并补记全貌并勘误。

#### 2. 实际落地

**（A）Task Running Spec 全貌补记（V35~V37 真实内容）**

- **Flyway V35 `task_context_jsonb.sql`**（Phase A 存储底座）：`task` 表加 `context JSONB NOT NULL DEFAULT '{}'`；`task.context.runningSpec` 为结构化运行态文档三件套——`baseline`（Planner 拆解确认时写入的目标/约束/DAG 结构）、`executionRecords[]`（每条 executor 回填的结构化摘要）、`contextSummary`（系统自动编译的下游上下文）。领域模型：`TaskRunningSpec`（不可变，`toMap/fromMap` JSONB 序列化边界 + `toBuilder` 增量更新）、`TaskBaseline`、`ExecutionRecord`（**EXECUTION_RECORD 协议**：`subTaskId/title/agentId/summary/keyDecisions/downstreamNotes/deliverables/completedAt`，builder 强制 subTaskId+summary）。配套：`ExecutionRecordParser`（解析 executor 协议输出）、`ExecutionResultHandler`（统一回填入口）、`TaskRunningSpecJsonbService`（Phase A 实现：`initialize` 写 baseline / `appendExecutionRecord` 回填 / `compileContextSummary` 编译 / `buildExecutorPromptSection` 渲染）。
- **Flyway V36 `task_running_spec_tables.sql`**（Phase B 前置建表，`0db1076`）：`task_running_spec`（task_id UNIQUE、version、baseline JSONB、context_summary TEXT）+ `task_execution_record` 独立表，当时仅建表为 Phase B 做准备。
- **Flyway V37 `task_running_spec_add_deleted.sql`**（Phase B 收尾，`6eaa02c`）：Phase B 实体继承 `BaseEntity` 后 MyBatis-Plus logic-delete 全局配置（`WHERE deleted=0`）导致启动期 `BadSqlGrammarException: column "deleted" does not exist`——为两表补 `deleted SMALLINT NOT NULL DEFAULT 0`；同提交落地 `TaskRunningSpecTableService`（Phase B 独立表实现）+ `TaskRunningSpecDataMigrator`（`ApplicationRunner` 数据迁移）。Phase B 渲染复用 Phase A 的 `JsonbPromptRenderer`（`TaskRunningSpecTableService` 私有静态类），两实现 prompt 输出一致。

**（B）本轮依赖双轨改造（helloai-core）**

- `TaskRunningSpecService` 接口新增 `findRecord(Long taskId, Long subTaskId)`：按 (taskId, subTaskId) 精确取单条结构化摘要，无则 null；契约注明"每次调用返回一条，调用方必须按集合收集，禁止单变量复用"。
- `TaskRunningSpecJsonbService`：`findRecord` 遍历 `executionRecords` 按 subTaskId 匹配返回；`appendExecutionRecord` / `initialize` 加 taskId 粒度分段锁（`ConcurrentHashMap<Long, Object>`，锁住"读-改-写"整段；按 subTaskId 去重——rework 覆盖旧记录、不同 subTaskId 互不覆盖全部保留；注释说明单实例安全、多实例需切 Phase B 或 Redis 锁）；`buildExecutorPromptSection` **去掉全量"前置任务摘要"段**，只保留 Baseline（总体目标/平台约束）+ ContextSummary（全局进度）。
- `TaskRunningSpecTableService`：`findRecord` 按 taskId+subTaskId 查独立表（行级天然无覆盖竞态，无需锁）；共用 `JsonbPromptRenderer` 同步去掉全量前置段。
- `SubTaskExecutionService` 新增 `buildDependencySection(SubTask)`：`dependsOnIdList` 空 → 返回空串（零注入）；`listByIds` 批量查前置 → HashMap 全量收集 → 按**声明顺序**循环内 append 渲染（`## 依赖产出参考（直接前置）` + 每前置 `### 前置 N：标题（状态：X）` + "产出摘要" + "内容"）；内容本体取数优先级：`AttachmentService.list` → `isContentLoadable` → `loadContent` 转 UTF-8 文本（二进制/读取失败跳过）→ 回退 `SubTaskOutputExtractor` 读 `context.lastExecution.output`；单条超 `DEP_CONTENT_MAX_CHARS=4000` 截断并显式标注；异常一律 warn + 返回空串（V34/V35 降级哲学），降级仍记录 timeline。`executeOnce`：`promptSection` = 全局段 + 依赖段；timeline `sub_task_spec_context_loaded` payload 补 depCount/loadedCount/truncatedCount/degraded；构造器注入 `AttachmentService`。

**（C）前端（helloai-ui）**

- `utils/sequenceFlow.ts`：LABEL 加 `sub_task_spec_context_loaded: '装配依赖产出'`，泳道归 EXT（执行 Agent）。
- `views/subtask/SubTaskDetail.vue`：EVENT_META 加 `sub_task_spec_context_loaded`（"参考前置产出" + 描述）。

#### 3. 验证结果

- **单测**：`SubTaskExecutionServiceTest` 新增用例——多前置并存（2 前置各有摘要+内容，断言 prompt **同时含两条**，防"只留第二次"回归）/ 附件优先于 output / 附件读取失败回退 output / 无附件走 output / 超长截断 / 异常降级不阻断 / 无依赖零注入（never 调 listByIds）；`TaskRunningSpecJsonbServiceTest` 并发用例——顺序 append 两个不同 subTaskId 记录断言两条都在（模拟多前置回填不互覆）。全绿（除 1 个 pre-existing V35 旧 loadDependencyContext 用例，属提测问题非本轮回归）。
- **E2E（真实环境 PASS）**：新建 `scripts/shell/verify-deps-context-e2e.sh`——建任务 + 3 子任务（sub3 依赖 sub1+sub2 双前置，SQL 直写 depends_on）→ 并行 claim sub1/sub2（API_KEY_LLM agent **claim 即自动执行**，两前置并发完成、EXECUTION_RECORD 并发回填不互覆）→ 双前置完成后 claim sub3 → 从 `conversation_message` 抓 `sub_task_execute_user_prompt`。断言全部通过：prompt 含 `## 依赖产出参考（直接前置）` 章节、`### 前置 1/前置 2` 两个块（无前置 3）、两前置产出内容**同时同现**（sub1 `## 竞品资料收集报告` 与 sub2 `## 前置二：收集用户反馈` 首行均在）；timeline `sub_task_spec_context_loaded` payload `depCount=2 / loadedCount=2 / truncatedCount=0 / degraded=false`。实测 taskId=2084259396090843138。
- 脚本两处修正记录：① 初版假设"claim 不自动分发、需手动 execute"与真实行为不符（`SubTaskAutoExecutionDispatcher` 对 API_KEY_LLM agent 在 ASSIGNED 后自动派发，手动 execute 会命中 `hasPendingOrRunning` 报 500"已有进行中的执行记录"）→ 改为 claim 即执行、按前置顺序串行等待；② `head -c` 按字节截断 UTF-8 中文产生非法字节序列致 BSD grep 报 `illegal byte sequence` → 改先取首行再用 zsh 字符切片（多字节安全）。
- **构建**：`mvn -pl helloai-start -am package -DskipTests` 产出 `helloai-start-1.0.0-SNAPSHOT.jar`（含全部改动），启动后端 `/api/health` 200；前端 `vue-tsc --noEmit` 0 错。

#### 4. 影响与遗留

- 行为兼容：无依赖子任务零注入与旧版完全一致（EMPTY 短路 + 不查库 + 不记录事件）；有依赖子任务 Prompt 新增"依赖产出参考"章节，且不再包含全量 executionRecords 摘要段。
- Phase A 分段锁仅单实例安全；多实例部署需切换 Phase B（独立表行级安全）或升级为 Redis 锁。
- 版本编号勘误落地：§6.39"V35"标号超前于 Flyway V35 实际创建时间（2026-08-02 01:04），其"无 Flyway"记录仅对 08-01 当天成立；V35~V37 真实内容（Task Running Spec Phase A JSONB / Phase B 建表 / deleted 修复）以本节为唯一事实源。
- 遗留（沿用 §6.39 范围外结论）：多级依赖透传（仅直接前置）、产出摘要化/向量化、跨任务依赖上下文；E2E 脚本依赖"claim 即自动执行"的环境行为，若未来关闭自动分发需同步调整脚本。

### 6.44 Planner 对话双模式：CHAT 自由对话 + CLARIFY 方案澄清（V39，2026-08-03）

#### 1. 背景与决策

需求澄清会话此前只有单一"澄清"形态（V29 首版 → V33 结构化选项 → V34 联网搜索），用户闲聊式咨询（技术选型对比、概念解释）会被生硬拽回澄清协议。本轮把单会话升级为 Kimi/DeepSeek 式双模式：**CHAT 自由对话**（通用 AI 助手，纯文本问答）+ **CLARIFY 方案澄清**（保留既有全部行为：首轮联网搜索 + progress 自评 + JSON 三选一协议），同一会话由用户主导切换，不拆两个独立入口。

关键决策：

- **单会话 `mode` 字段而非双会话**：`requirement_conversation.mode`（CHAT/CLARIFY，NULL 老数据按 CLARIFY 语义读取兼容），切换只改模式不迁移消息。
- **CHAT 是"降级协议"模式**：走通用助手模板纯文本（无 JSON 输出协议、无澄清轮自检清单、无 progress 自评），不做首轮联网搜索（阶段 2 计划再评估按需检索）；独立 `MAX_CHAT_ROUNDS=50` 上限（CLARIFY 沿用 20）。
- **意图词自动切换（CHAT→CLARIFY 单向）**：正则命中「整理成方案/做成方案/生成方案/转为方案/变成方案/整理成任务/做成任务/落地实施/出一份方案/写个方案/方案化」即先落库切 CLARIFY 再走澄清轮（该条消息即澄清首轮）；意图词永远放行（不占 CHAT 轮数上限判定），保证"转方案"出口不被 50 轮上限挡住。
- **切换 API 语义**：`to-clarify` = 置位落库 + 立即用澄清模板基于全量历史跑一轮 LLM（LLM 失败时 mode 已持久化，可用 retry 续跑）；`to-chat` = 仅置位不调 LLM。
- **新会话缺省 CHAT**，创建接口 `initialMode` 可快捷直达 CLARIFY；非法值抛 BizException。
- 明确不做：SSE 流式、CHAT 模式联网搜索（阶段 2）、意图词反向自动切换（CLARIFY→CHAT 无自动切换，避免方案进度被打断）、多轮策略优化。

#### 2. 实际落地

- **Flyway V39 `requirement_conversation_add_mode.sql`**：`mode VARCHAR(16)`（IF NOT EXISTS）+ 重建 `chk_requirement_conversation_mode` CHECK（NULL 或 CHAT/CLARIFY）+ 列 COMMENT + V34 同款 DO $$ 验证块（启动日志输出 `[V39] requirement_conversation.mode 列与 CHECK 约束已就位`）。
- **`RequirementClarifyService`（helloai-core/planner）**：
  - 常量 `MODE_CHAT/MODE_CLARIFY`、`MAX_CHAT_ROUNDS=50`、`CHAT_PROMPT_TEMPLATE_PATH=prompts/requirement-chat.md`、`INTENT_TO_CLARIFY_PATTERN` 意图词正则。
  - `create(firstMessage, plannerAgentId, webSearchEnabled, initialMode)` 四参重载 + `normalizeInitialMode`（null/缺省→CHAT、CLARIFY 直达、非法抛 BizException）；旧三参/二参重载委托保兼容。
  - `sendMessage` 轮数上限按模式分派：CHAT（且非意图词）超 50 抛"自由对话轮数已达上限…可输入「整理成方案」转为方案模式"；CLARIFY（含 NULL 老数据）沿用 20 上限；意图词消息跳过 CHAT 上限判定。
  - `doRound` 意图切换：CHAT 模式下命中意图词 → `setMode(CLARIFY)` + `updateById` 落库 → 该轮即澄清轮；首轮联网搜索条件收紧为 `isClarifyMode && rounds==0 && webSearchEnabled`。
  - `runLlmRound` 分派：`isClarifyMode` 选澄清模板（scene=requirement_clarify，JSON 协议解析）/ CHAT 选通用助手模板（scene=requirement_chat，纯文本直接落库，`addMessage` 显式 4 参 payload=NULL）。
  - `switchToClarify`：requireActive → 置位落库 → `runLlmRound(conversation, "")`（切换轮不做首轮联网搜索，澄清模板基于全量历史直接产草案/追问；LLM 失败 mode 已持久化可 retry）；`switchToChat`：仅置位 + 返回 detail。
- **`prompts/requirement-chat.md` 新模板**：不锁定"需求分析师"角色、无 JSON 输出协议、无澄清轮自检清单段、无 progress 自评；占位符 `{{CONVERSATION_HISTORY}}`/`{{WEB_SEARCH_CONTEXT}}` 与澄清模板同构（CHAT 当前渲染"（无可用联网资料）"）；第 4 条职责明确"用户表达转方案意图时系统自动切换，本轮只需一句话提示"。
- **API 层**：`ClarifyMessageRequest.initialMode`；Controller `create` 透传四参 + `POST /{id}/to-clarify` + `POST /{id}/to-chat`。
- **前端（helloai-ui）**：`types` 加 `mode?: 'CHAT'|'CLARIFY'|null`；`clarify.ts` create 加 initialMode + `toClarify/toChat`；`RequirementChat.vue`——标题"对话新建（AI 助手）"+ 模式徽标 el-tag（对话 info / 方案 warning）、conv-meta 小标签、进度条与终稿卡条件化（`!isChatMode`）、`isChatMode` computed、新会话 el-radio-group 模式选择（默认 CHAT）、「转为方案」warning 按钮（ElMessageBox.confirm 后 toClarify，失败靠重试条续跑）、输入占位随模式切换（CHAT"自由提问，可随时转为方案模式" / CLARIFY 沿用澄清引导）。

#### 3. 验证结果

- **单测**：`RequirementClarifyServiceTest` 新增 `@Nested ChatModeAndSwitch` 14 例全绿——chatRoundStoresPlainTextWithoutPayload（payload 显式 NULL）/ chatRoundUsesChatPromptTemplate / legacyNullModeTreatedAsClarify（老数据兼容）/ intentPhraseAutoSwitchesMode / chatRoundDoesNotTriggerWebSearch / chatRoundFortyNineAllowed / chatRoundAtLimitRejected / intentAtChatLimitStillSwitchesMode（意图词永远放行）/ createDefaultsToChatMode / createRejectsInvalidInitialMode / switchToClarifyRunsClarifyRound / switchToClarifyPersistsModeEvenOnLlmFailure / switchToChatFlipsModeOnly / finalizedCannotSwitchMode；既有 2 例 create 用例改显式传 `MODE_CLARIFY`。模块 35/35 全绿；helloai-core 全量 384 例仅 1 个 pre-existing Error（PlannerAnalysisServiceTest 拆解草案重加载，`git stash` 只 stash 本轮两个 core 文件后重跑依然失败，确认非本轮回归）。
- **构建**：`mvn -pl helloai-start -am package -DskipTests` 产出 `helloai-start-1.0.0-SNAPSHOT.jar`（68M），启动后 Flyway 迁移日志确认 `[V39] requirement_conversation.mode 列与 CHECK 约束已就位`，`/api/health` 200；前端 `vue-tsc --noEmit` 0 错。
- **E2E（真实环境 PASS）**：新建 `scripts/shell/verify-planner-chat-dual-mode.sh`（UTF-8 头 + set -euo pipefail + curl + jq，照 verify-requirement-clarify.sh 模板）8 步全绿——CHAT 建会（initialMode=CHAT）断言 mode=CHAT + 末条 assistant 回复 payload=NULL（纯文本）；二轮普通问题仍 CHAT（+2 消息）；to-clarify 断言 mode=CLARIFY + 新增一轮（messages 5）；推进一轮即产终稿 `finalTitle=技术选型：微服务与单体架构对比分析` → finalize 建任务 PENDING + 会话 FINALIZED + taskId 回填；意图词新会话（缺省 CHAT）首条"整理成方案…"自动切 CLARIFY；to-chat 反向切回断言仅置位不加消息。实测 chatConversationId=2084282161971728385 / intentConversationId=2084282265231298561 / taskId=2084282263423553538。
- 脚本弹性设计：终稿未产出时降级断言"最后一条 assistant payload 为合法 JSON 且含 questions 键"（结构化追问协议），避免 LLM 输出不确定性导致脚本假失败。

#### 4. 影响与遗留

- 行为兼容：老数据 mode=NULL 按 CLARIFY 语义读取，既有澄清链路零改动；CLARIFY 分支代码路径与 V33/V34 一致。
- 轮数语义变化：CHAT 会话 50 轮上限（意图词放行），CLARIFY 仍 20 轮；超限提示引导输入「整理成方案」转模式或新建会话。
- 遗留：CHAT 模式联网搜索（阶段 2 计划，需评估按需检索时机与成本）、意图词正则覆盖度（可后续按用户话术补充）、切换轮不做首轮联网搜索（阶段 2 再评估）。

### 6.45 意图词二次确认：去掉「转为方案」按钮，对话内确认转方案（V40，2026-08-03）

#### 1. 背景与决策

V39 的意图词命中即自动切 CLARIFY，前端另有「转为方案」按钮。用户反馈：误表达/误触会直接进入方案模式，缺少确认环节。产品决策（用户明确要求，确认形态经 AskUserQuestion 选定为「对话内确认」）：**去掉「转为方案」按钮，意图词只触发二次确认**——命中意图词后不切模式、不调 LLM，服务端回复固定确认询问；用户回复确认词或再次表达意图 → 转入 CLARIFY（该条消息即澄清首轮）；回复其他内容 → 清标记继续自由对话。

关键决策：

- **意图词命中不再自动切 CLARIFY（V39 行为变更）**：置 `pending_clarify_confirm` 标记 + 回复固定确认询问文案（`CONFIRM_ASK_MESSAGE`，不调 LLM、不加轮数、payload NULL）。
- **确认词正则**：`^(确认|确定|好的|可以|开始吧|开始|是的|没错|没问题|行|嗯|OK|ok|Yes|yes)([。！？!?,.;；\s]|$)`——开头命中且后随标点/空白/结尾，避免「好的，但我还想先聊聊」这类误判；**仅待确认状态生效**，普通对话不受影响。
- **放行语义**：待确认状态的确认词（或再次意图词）跳过 CHAT 50 轮上限判定——确认消息转入 CLARIFY，不算 CHAT 轮，保证转方案出口不被上限挡住（与 V39 意图词放行同思路）。
- **SMALLINT(0/1) 持久化**（按代码规范 §9.3 不用 BOOLEAN）：实体保持 Java `Boolean` 字段 + 自定义 `SmallIntBooleanTypeHandler`（写侧 `setInt(0/1)`、读侧 smallint→Boolean），不注册全局映射仅 `@TableField` 显式指定，避免影响 BOOLEAN 类型的 `web_search_enabled`。直接用 MyBatis 内置 BooleanTypeHandler 会报 `column is of type smallint but expression is of type boolean`。
- **切换端点按代码规范 §8.2 整改**：V39 的 `POST /{id}/to-clarify`、`POST /{id}/to-chat` 违反「新代码必须 `POST /{action}ById/{id}`」规范，本轮一并整改为 `/toClarifyById/{id}`、`/toChatById/{id}`（前端 clarify.ts 同步；E2E 脚本同步）。
- 前端移除「转为方案」按钮与 `handleToClarify`；`toClarify/toChat` 端点保留（无前端入口，供内部/测试用）。

#### 2. 实际落地

- **Flyway V40 `requirement_conversation_add_pending_clarify_confirm.sql`**：`pending_clarify_confirm SMALLINT NOT NULL DEFAULT 0`（IF NOT EXISTS + 列 COMMENT 明示 0=无待确认/1=等待确认 + V34 同款 DO $$ 验证块，启动日志输出 `[V40] ... 列与默认值已就位`）。
- **`SmallIntBooleanTypeHandler`（helloai-core/shared/handler，新建）**：`BaseTypeHandler<Boolean>`，写侧 `ps.setInt(parameter ? 1 : 0)`、读侧 `getObject` 判 1；Javadoc 说明 §9.3 背景与不注册全局映射的原因（对齐 `PgJsonbTypeHandler` 先例）。
- **`RequirementClarifyService`**：
  - 常量 `CONFIRM_PHRASE_PATTERN`（确认词正则）、`CONFIRM_ASK_MESSAGE`（固定确认询问文案，public 供单测断言）、`INTENT_TO_CLARIFY_PATTERN` 注释更新为"命中即进入二次确认"。
  - `sendMessage` 上限分派：`intent`/`confirm`（待确认 + 确认词或意图词）均放行 CHAT 上限；CLARIFY（含 NULL 老数据）沿用 20 上限。
  - `doRound` 三段状态机（仅 CHAT 模式）：意图词且无待确认 → 置位 + updateById + user 消息落库 + assistant 落固定确认询问（payload null）+ 直接 return（不调 LLM 不加轮数）；待确认 + 确认词/再次意图词 → `setMode(CLARIFY)` + 清标记 + updateById（该条消息即澄清首轮，rounds==0 时触发首轮联网搜索）；待确认 + 其他 → 清标记继续 CHAT 轮。
  - `switchToClarify`/`switchToChat` 均防御性 `setPendingClarifyConfirm(false)`（手动切换清残留标记）。
  - 辅助方法 `isPendingClarifyConfirm`（仅显式 true）/`isConfirmPhrase`（trim 后正则 find）。
- **实体 `RequirementConversation`**：`private Boolean pendingClarifyConfirm` + `@TableField(typeHandler = SmallIntBooleanTypeHandler.class)` + Javadoc 说明 V40 语义。
- **Controller**：`@PostMapping("/toClarifyById/{id}")`、`@PostMapping("/toChatById/{id}")`（§8.2 合规整改，`@PathVariable("id")` 显式命名照 c00d15f 先例）。
- **前端（helloai-ui）**：`clarify.ts` 路径改 `toClarifyById`/`toChatById` + 头注释 V40 说明；`RequirementChat.vue` 删除「转为方案」按钮块与 `handleToClarify` 函数（ElMessageBox 仍在 handleAbandon/handleFinalize 使用故 import 保留；`isChatMode` computed 保留用于进度条/终稿卡条件化）。

#### 3. 验证结果

- **单测**：`RequirementClarifyServiceTest` 的 `@Nested ChatModeAndSwitch` 14 → 19 例全绿——2 例意图词用例改为待确认语义（`intentPhraseEntersPendingConfirm` / `intentAtChatLimitStillEntersPendingConfirm`：断言 mode 仍 CHAT + 标记置位 + 轮数不变 + executeSync never）、`switchToClarifyRunsClarifyRound` 增强（前置置位 + 断言清标记）、5 例新增（`confirmPhraseSwitchesToClarifyRound` 确认词转 CLARIFY 走澄清模板 / `confirmAtChatLimitStillSwitches` 50 轮放行 / `nonConfirmMessageClearsPendingAndContinuesChat` 非确认内容清标记续 CHAT / `intentDuringPendingConfirmEntersClarify` 待确认中再次意图词直转 / `createIntentPhraseEntersPendingConfirm` 建会首条意图词即待确认）。
- **既有测试修复**：`PlannerAnalysisServiceTest.shouldDecomposeAndPersistDrafts`（V27 依赖校验引入的重加载防御门，pre-existing Error）补 `subTaskService.list(any(Wrapper.class))` stub（返回带 id/priority/context 的"重加载结果"）——§6.44 记录的 pre-existing Error 本轮闭合。
- **构建**：`mvn -pl helloai-core -am package -DskipTests=false` 全量 389/389 全绿；`mvn -pl helloai-start -am package -DskipTests` 产出 jar 启动后 Flyway 日志 `[V40] requirement_conversation.pending_clarify_confirm 列与默认值已就位`；前端 `vue-tsc -b` 0 错。
- **E2E（真实环境 PASS）**：`verify-planner-chat-dual-mode.sh` 改造后 8 步全绿——STEP5 `/toClarifyById`、STEP8 `/toChatById`（规范路径）；STEP7 意图词路径改为 V40 全流程断言：建会发意图词 → mode=CHAT + `pendingClarifyConfirm=true` + roundCount=0 + 仅 2 条消息 + 末条 assistant 为固定确认询问（含「回复「确认」」）→ 回复「确认」→ mode=CLARIFY + 标记清除 + roundCount=1 + 消息 +2 走澄清轮。实测 chatConversationId=2084300744164569089 / intentConversationId=2084300858627125250 / taskId=2084300856865517569。
- 真实环境还捕获并修复了 SMALLINT ↔ Boolean 映射问题（见 §2 TypeHandler），一次通过修复后全链路无回归。

#### 4. 影响与遗留

- 行为变更：意图词不再立即转方案（需对话内确认）；「转为方案」按钮移除，转方案入口收敛为意图词；CHAT 模式 50 轮上限的引导文案（"可输入「整理成方案」转为方案模式"）仍准确。
- 老数据兼容：`pending_clarify_confirm` NULL/0 均视为无待确认（`Boolean.TRUE.equals` 判定），无迁移负担。
- 遗留：确认词正则对「好的，开始吧」这类"确认词后直接跟内容"的话术暂不命中（需用户回短确认词，避免误判的设计取舍）；意图词/确认词正则覆盖度可后续按用户话术补充；`toClarify/toChat` 端点保留但无前端入口（V40 起语义收敛为内部/测试用）。

#### 5. 同日追加优化（V40.1）：口语化意图词扩展 + 澄清首轮强制 structured

用户实测反馈：发「帮我整理方案吧」后 LLM 回复"切换到方案整理模式"但页面始终无推荐选项卡片，疑为功能被删。核实代码确认 V33 structured 全链路（prompt 双模协议 / `normalizeQuestionReply` 解析 / 前端 `StructuredQuestionCard` 渲染）完整保留；根因是「帮我整理方案吧」不命中意图词正则（固定词「整理成方案」为连续子串匹配，「整理方案」缺「成」字）→ 未触发 V40 待确认状态机 → 会话仍停留 CHAT 模式（LLM 那句"切换到方案整理模式"只是 CHAT 模板第 4 条的提示话术，系统实际未切换），CHAT 为纯文本协议故无选项卡片。据此追加两项优化：

- **意图词正则扩展**：`INTENT_TO_CLARIFY_PATTERN` 追加口语化话术 `整理方案|出个方案|出方案|写方案|做个方案|做方案|方案整理`（Javadoc 注明放宽理由：误触有二次确认把关，回复其他内容即继续自由对话，无额外代价）。
- **澄清首轮强制 structured**：`prompts/requirement-clarify.md` 第 2 条追加「**首次追问必须使用 structured**（判定：对话历史中尚无任何"助手追问"记录时即为首次追问，不得用 freeform 开场）」，保证进入 CLARIFY 后的第一轮必有推荐选项卡片（LLM 可自主决定后续轮次形态）。

验证：`RequirementClarifyServiceTest` 新增 `colloquialIntentPhraseEntersPendingConfirm`（「帮我整理方案吧」→ 待确认 + 固定确认询问 + 不调 LLM 不加轮数），helloai-core 全量 390/390 全绿；`verify-planner-chat-dual-mode.sh` STEP7 意图词改为口语化「帮我整理方案…」真实环境重跑 8 步 PASS（待确认 → 回复确认 → CLARIFY 链路不变）；服务已停止端口已释放。

**口嗨切换治理（同日二次追加）**：用户手动会话实测「帮我整理方案吧」（V40 旧代码未命中）→ LLM 回复"切换到方案整理模式"但系统未切换（数据库实锤 mode=CHAT、payload=null），后续「帮我整理成技术方案文档吧」在新正则下也不命中（「整理成方案」为连续子串，中间隔「技术」）——LLM 反复口嗨的根源是 `requirement-chat.md` 第 4 条引导 LLM"系统会自动切换，你只需提示"，而意图词命中时系统根本不调 LLM（直接回固定确认询问），该指令只在未命中时被执行。已把第 4 条改为"系统自动处理切换，你无需提及/预告/扮演方案整理模式，正常回答即可"（prompt 每次调用经 ClassPathResource 读取，同步到 target/classes 后 IDEA 服务无需重启即生效）。同时实证：E2E 会话 2084318559537963009 的 CLARIFY 首轮 payload=`{"mode":"structured","progress":35,"questions":[2题，每题4选项，含 recommended=true]}`——V40.1 首轮强制 structured 真实生效，推荐卡片链路（prompt→服务解析→payload 落库→前端卡片）完整可用。

### 6.46 /planner 斜杠命令直达方案模式 + CHAT 追问推荐卡片（V40.2，2026-08-04）

#### 1. 背景与决策

用户实测反馈两件事：①「帮我整理成技术方案文档吧」在 V40.1 正则下仍不命中（「整理成方案」为连续子串，中间隔「技术」，无法穷举口语话术）；② 希望所有"需要用户回答的问题"尽量以推荐卡片（structured）呈现，无论是否在 planner 模式。据此产品决策（经确认）：

- **兜底入口 `/planner` 斜杠命令**（大小写不敏感）：输入框识别 `^/planner(\s+附加文本)?$`，命中即显式进入方案澄清（CLARIFY）模式——不依赖意图词命中率；命令前缀本身不落消息，附加文本（支持多行）先落库 user 消息进 LLM 上下文，再切 CLARIFY 跑一轮（V40.1 首轮强制 structured → 推荐卡片必出）。
- **阶段 2 增强（LLM 引导型）**：CHAT 模板新增「输出形态」节——普通聊天一律纯文本，仅当需要向用户追问关键决策信息（技术选型/偏好/业务规模/可枚举场景）时输出 structured JSON（复用 CLARIFY 协议格式，每轮 ≤2 题、每题 2~4 选项、recommended 每题至多 1 个）；服务端 CHAT 轮宽松解析（解析成功且合法才落 payload 出卡片，否则原样纯文本落库，零破坏）；前端放开 CHAT 模式交互卡限制（activeStructured 不再要求非 CHAT）。
- **明确不做**：不锁定模式（CLARIFY 状态机天然保持）、不做命令历史/提示列表 UI、无表结构变化、CHAT 联网搜索与 LLM 流式输出维持现状。

#### 2. 实际落地

- **后端（helloai-core + helloai-api）**：
  - `RequirementClarifyService.switchToClarify(Long, String)` 重载：extraMessage 非空 → `addMessage(conversationId, ROLE_USER, extraMessage.trim(), null)` 落库（即入 LLM 上下文，不走意图词/确认词判定、不设 payload）→ 委托既有 `switchToClarify(Long)`（置 MODE_CLARIFY + 清 pendingClarifyConfirm + `runLlmRound(conversation, "")`，V40.1 首轮强制 structured）。既有单参重载保持不动。
  - `RequirementConversationController.toClarify`：body 改 `@RequestBody(required = false) ClarifyMessageRequest req`（不加 @Valid），`req != null ? req.getMessage() : null` 透传；现有无 body 调用（E2E 曾传 "{}"）兼容。
  - CHAT 轮容错双模（`runLlmRound` CHAT 分派处）：LLM 输出后 `tryParseChatStructured` 宽松提取（复用 ``` 围栏/首字符 { 处理 + `LlmJsonSanitizer`）→ 仅当 `type=question && mode=structured && isStructuredValid` 时 `composeAssistantContent` 可读拼接 + payload 落库（模式仍 CHAT、不触发联网搜索）；其余（解析失败/非 question/freeform/final/纯文本）一律原样纯文本落库（payload NULL），异常捕获返回 null 不抛——与 CLARIFY 的 parseReply 严格路径完全隔离。
- **前端（helloai-ui）**：
  - `clarify.ts toClarify(id, message?)`：body `{ message: message ?? null }`，注释补 V40.2 语义。
  - `RequirementChat.vue`：常量 `PLANNER_COMMAND_RE = /^\/planner(?:\s+([\s\S]+))?$/i`；`handleSend` 开头命中 → `handlePlannerCommand(cmd[1]?.trim() ?? '')` 并 return；`handlePlannerCommand(extra)`：无 ACTIVE 会话 → `clarifyApi.create(extra || '请帮我整理一份技术方案', plannerId, webSearchEnabled, 'CLARIFY')` 新会话 initialMode=CLARIFY 直达，已有会话 → `clarifyApi.toClarify(activeId, extra || null)`，错误路径复用 handleSend 的 catch 模式（刷新详情/按标题找回）；CHAT 模式输入框 placeholder 追加「输入 /planner 可直接进入方案整理」；`activeStructured` 去掉 `isChatMode` 条件（会话 ACTIVE 且末条 assistant 为合法 structured payload 即可交互，历史只读卡逻辑不动）。
- **Prompt**：`prompts/requirement-chat.md` 新增「输出形态（V40.2，重要）」节（普通聊天纯文本不输出 JSON；仅追问关键决策信息时优先 structured JSON 并给出协议示例与约束；无法枚举选项时 freeform）。

#### 3. 验证结果

- **单测**：`RequirementClarifyServiceTest` ChatModeAndSwitch 19 → 23 例全绿——新增 4 例：`switchToClarifyWithExtraMessage`（CHAT 会话 + extra → mode=CLARIFY、pending=false、user 消息落库 content=extra、roundCount+1、消息 +2、LLM stub 输出 structured 追问）/ `switchToClarifyWithBlankExtraEqualsLegacy`（extra 空/空白 → 不加消息与既有单参一致）/ `chatRoundStructuredQuestionStoresPayload`（CHAT stub 输出 structured → payload 落库 + content 可读拼接 + mode 仍 CHAT + 不触发联网搜索）/ `chatRoundFreeformJsonStillPlain`（freeform/非结构化 → payload NULL 降级）。helloai-core 全量 `mvn -pl helloai-core -am test -DskipTests=false` 394/394 全绿。
- **前端**：`vue-tsc --noEmit` 0 错。
- **E2E（真实环境 PASS）**：`verify-planner-chat-dual-mode.sh` 改造后全流程 PASS——STEP4.1 新增 CHAT 轮宽松断言（发「需要你问我几个问题帮我做选型」→ mode 仍 CHAT + 消息 +2；payload 非空则必须合法 structured，不强求出现）；STEP5 改造为 `toClarifyById` 传 `{"message":"补充：团队10人，单体优先"}` → 断言消息 +2（user 附加文本 + assistant 澄清轮）、附加文本确已作为 user 消息落库、mode=CLARIFY、末条 assistant；STEP6 追推终稿 → finalize 建任务 PENDING → FINALIZED + taskId 回填；STEP7/7.1/8 意图词确认流与反向 toChat 回归通过。实测 chatConversationId=2084324277456347138 / taskId=2084324405969821698。本次真实 LLM 在 STEP4.1 未输出 structured（降级纯文本，记录观察），STEP5 切 CLARIFY 后首轮仍追问、追推一轮即产终稿。

#### 4. 影响与遗留

- 行为变更：`/planner` 命令（含附加文本）成为显式转方案入口，不受意图词命中率影响；CHAT 轮 LLM 追问可能出推荐卡片（LLM 引导型，不保证）。
- 兼容性：`toClarifyById` 无 body / `{}` 调用与既有语义完全一致（req 为 null 或 message 为 null 均走单参路径）；CHAT 轮解析失败一律纯文本，零行为破坏。
- 遗留：CHAT 结构化追问输出依赖 LLM 遵循度（真实环境本次未出卡片，属可接受降级；后续可考虑 few-shot 或独立小模型，不在本轮范围）；`/planner` 无命令提示列表 UI（仅输入框识别）；意图词正则仍为有限话术集合（`/planner` 已作兜底，无需继续扩词）。

### 6.47 子任务分发失败快速兜底 + 整合报告生成状态防重（V41，2026-08-04）

#### 1. 背景与决策

用户实测反馈两件事：①「10人小团队企业OA系统模块化单体+微服务演进技术方案」任务的子任务 #7「部署方案验证」长时间无 agent 领取，最终用户手动选择空闲 agent 才解决，问根因与兜底办法；② planner 生成最终报告时报告按钮可重复点击、主任务状态不显示"报告生成中"，会出现重复生成报告的问题。

**Q1 根因（数据库取证闭合）**：17:16:36 依赖 #3「容器化部署方案设计」DONE → `SubTaskCompletionListener.unlockDownstream` 触发 #7 分发 → ready 守卫通过 → `checkReassignCircuitBreaker` 累加 `reassign_attempt_count=1`（#7 timeline 完全无 `dispatch_prepare` 事件佐证：异常发生在写审计事件之前）→ `AgentSelector.pickPreferred` 在 `require-idle: true` 下用 `inProgressCount==0` 过滤——当时两个 EXECUTOR（executor-deps-ctx 跑 #1/#2/#4、inner-deepseek-executor 跑 #5/#6）全部在忙（#1 17:16:44、#2 17:17:05、#4 17:17:17、#5 17:16:51、#6 17:17:03 才 DONE）→ 候选为空 → `pickPreferred` 抛 BizException → 该异常在 `unlockDownstream` 逐节点 catch 中仅 warn 吞掉（无 timeline 事件）→ #7 保持 PENDING 且无 execution_record → 孤儿巡检（`SubTaskPendingOrphanTask`）30 分钟阈值未到 → 用户 17:19:29 手动指派（timeline 第一条事件即 `sub_task_auto_execute_dispatch`，走 changeStatus 不经过 dispatch 所以 count 保持 1）。本质是「瞬时全员忙碌 + 异常静默 + 兜底窗口过长」三层叠加的小概率事件。

**决策（Q1）**：① 孤儿巡检阈值 30→5 分钟——`isReady` 依赖守卫保证未就绪的合法 PENDING 会被跳过不误伤，收窄阈值安全，无人兜底窗口从 30 分钟缩到 5 分钟；② `unlockDownstream` 解锁失败写 `sub_task_dispatch_deferred` timeline 事件，把"静默吞掉"变成可观测。

**决策（Q2）**：报告生成状态独立成 `FinalReportStatus` 四态（NONE/GENERATING/DONE/FAILED），与 `TaskStatus`（保持 DONE 语义）解耦——"报告生成中"塞进任务状态机会破坏 DONE 语义与自动收尾判定；后端 CAS 防重入保证手动/自动两条路径并发只有一个赢家，前端按钮禁用 + "报告生成中"状态展示。

#### 2. 实际落地

- **后端（Q1 兜底）**：
  - `AgentExecutionProperties.pendingOrphanThresholdMinutes` 默认 30→5，Javadoc 说明收窄安全的前提（扫描命中后循环内还有 isReady 依赖守卫）；`application.yml` execution 段显式声明 `pending-orphan-threshold-minutes: 5` 并注释两种覆盖场景。
  - `SubTaskCompletionListener.unlockDownstream` 逐节点 catch 内写 `sub_task_dispatch_deferred` timeline 事件（payload 带 reason + waitFor=pending_orphan_scan，内层 try-catch 失败仅 log.debug，不改变既有不阻断语义）。
- **后端（Q2 报告状态）**：
  - `FinalReportStatus` 枚举（helloai-common/constant）：`NONE / GENERATING / DONE / FAILED`。
  - Flyway V41 `task.final_report_status VARCHAR(16) NOT NULL DEFAULT 'NONE'` + 存量回填（`final_report` 非空 → `DONE`）+ 逐列 COMMENT。
  - `TaskFinalReportService.generate`：生成前 CAS 防重入（`lambdaUpdate eq id + ne GENERATING + set GENERATING`，失败抛「任务整合报告正在生成中，请稍候后再试」）；成功写回 4 列（final_report/final_report_agent_id/final_report_time/final_report_status=DONE）；最终失败 `markFailed`（置 FAILED 可手动重试，避免进程崩溃后永久卡 GENERATING 无恢复口，失败不外抛）；`onTaskAutoCompleted` 在"已有报告跳过"之前加 GENERATING 跳过（自动路径不与手动路径并发触发）。
  - `TaskController.toFinalReportResponse` 与 `TaskFinalReportResponse` 增加 `status` 透出。
- **前端（Q2）**：
  - `TaskList.vue`：状态列 `GENERATING` 覆盖显示「报告生成中」tag；报告按钮 `:loading/:disabled="row.finalReportStatus === 'GENERATING'"`，文案动态「生成中」/「报告」。
  - `FinalReportDialog.vue`：`reportGenerating` computed（本地 generating || 接口 status===GENERATING）；5s 轮询（非 GENERATING 即停，onBeforeUnmount 清理）；handleGenerate 前置守卫 + 同步 `props.task.finalReportStatus`；空态文案按状态区分（GENERATING→「报告正在生成中…」/ FAILED→「上次生成失败，点击下方按钮重新生成」）。
  - `types/index.ts`：`FinalReportStatus` 类型 + `Task.finalReportStatus` + `TaskFinalReport.status`。

#### 3. 验证结果

- **单测**：helloai-core 全量 `mvn -pl helloai-core -am test -DskipTests=false` 397/397 全绿——`TaskFinalReportServiceTest` 新增 3 例（`shouldRejectWhenAlreadyGenerating` CAS 拒绝 / `shouldMarkFailedStatusWhenLlmFails` 失败置 FAILED / `shouldSkipAutoWhenGenerating` 自动路径跳过），并修复单测陷阱：`new LambdaUpdateWrapper<Task>()` 的 lambda 解析需要 TableInfo 缓存，`@BeforeAll` 用 `TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Task.class)` 注册（BaseEntity 有 @TableId 注解可正常注册）。
- **前端**：`vue-tsc --noEmit` 0 错。
- **真实环境冒烟 PASS**：V41 迁移成功（存量报告回填 DONE）；`GET /api/tasks/{id}/final-report` 返回 `status=DONE`（content 18760）；列表接口返回 `finalReportStatus`。**并发防重全链路**：第一次 POST → 10s 后 DB=`GENERATING` → 第二次 POST 被拒 `{"code":500,"msg":"任务整合报告正在生成中，请稍候后再试: taskId=..."}` → 第一次完成 `code=200 status=DONE` content=19091（覆盖旧报告）→ DB 终态 `DONE|19091`。冒烟后已停服务释放端口。

#### 4. 影响与遗留

- 行为变更：孤儿 PENDING 无人兜底窗口 30 分钟→5 分钟（isReady 守卫保证不误伤）；`unlockDownstream` 分发失败不再静默（timeline `sub_task_dispatch_deferred` 可见）；报告生成期间按钮禁用 + 状态「报告生成中」，重复生成被后端 CAS 拒绝。
- 兼容性：`final_report_status` 默认 NONE 对存量零影响；FAILED 状态可重新生成；任务 DONE 语义不变（报告状态独立维度）。
- 遗留：「全员瞬时忙碌」时子任务仍会落入 PENDING 等待孤儿巡检（现 5 分钟），未做排队等待/延迟重试策略（可后续考虑，不属本轮）；`sub_task_dispatch_deferred` 事件无前端消费（可在派发控制台时间线查看）。

### 6.48 /planner 命令缺失 await 修复（2026-08-03）

#### 1. 背景与决策

用户报告 `/planner` 斜杠命令在需求澄清对话框中报错。追踪全链路：`RequirementChat.vue` `handleSend()` → `handlePlannerCommand()` → `clarifyApi.toClarify()` → 后端 `switchToClarify()` → LLM 轮。定位到 `handleSend()` 中匹配 `/planner` 命令后调用 `handlePlannerCommand(cmd[1]?.trim() ?? '')` **缺少 `await`**，导致异步操作 fire-and-forget：`handleSend` 在异步完成前立即返回，`sending` 状态未及时置位，`finally` 清理逻辑跳过，并发重入风险。

注意：PLANNER Agent 为 inner API_KEY_LLM 类型（类比线程池核心线程），无在线/离线状态概念，OFFLINE 状态不是问题原因。

#### 2. 实际落地

- 前端 `RequirementChat.vue` L428：`handlePlannerCommand(cmd[1]?.trim() ?? '')` 改为 `await handlePlannerCommand(cmd[1]?.trim() ?? '')`，纳入正常 await 链路。

#### 3. 验证结果

- 代码审查确认：`handlePlannerCommand` 返回 Promise（async 函数内调 `clarifyApi.toClarify` / `clarifyApi.create`），缺失 await 导致 fire-and-forget。
- 修复后 `/planner` 命令应正常走完创建/切换→LLM 调用→前端刷新全流程。

#### 4. 影响与遗留

- 行为修复：`/planner` 命令不再因并发状态竞态引发 sending 未置位、重复发送等问题。
- 无新增依赖或配置变更。

### 6.49 REVIEW 孤儿扫描兜底（2026-08-03）

#### 1. 背景与决策

用户报告任务「内部周报自动汇总工具开发」的两个子任务（#1 企业微信API对接、#2 需求分析与规划）卡在"审查中"（REVIEW）状态，reviewer agent（`inner-kimi-reviewer`）未被调用。

数据库取证（dev 环境）：
- `sub_task` 表：2 条 REVIEW 子任务（`update_time` 07:55）
- `review_record` 表：EMPTY（无任何核验记录）
- `agent_inbox` 表：EMPTY（inner API_KEY_LLM Agent 不走 inbox，符合设计）
- `agent_outbox_event` 表：2 条 `sub_task.review` 事件已发布（routing_key=`agent.reviewer.assigned`），但无 consumer 消费
- `task_timeline` 表：只有 `sub_task_execute_submit`，没有 `sub_task_auto_review_*` 事件
- `event_consumption_log` 表：所有 consumer 均为 `MqExecutionCommandConsumer`，无 reviewer consumer

**根因**：L1 主路径 `SubTaskSubmittedForReviewEvent` → `@TransactionalEventListener(phase=AFTER_COMMIT)` + `@Async` 未触发（timeline 无 `sub_task_auto_review_*` 证据）；L2 MQ 备份路径 `agent.reviewer.assigned` 路由已绑定 `reviewerQueue`，但代码库无 `MqReviewCommandConsumer` 消费端。双路径均断裂，子任务永久卡 REVIEW。

**决策**：不新增 MQ 消费者（涉及队列/交换机/幂等/确认等全套基建），而是走 L3 DB 状态扫描兜底——`@Scheduled` 定时扫描 REVIEW 状态且无 `review_record` 的孤儿子任务，直接调用既有 `reviewSubTask()` 触发核验。与 ExecutionCommandPoller 孤儿扫描（§6.32 T5）同款"主路径 + 兜底扫描"冗余容错哲学。

#### 2. 实际落地

- **`AgentDispatchProperties`**（helloai-common/config）：新增两项配置——`reviewOrphanThresholdSeconds`（默认 60s，子任务进入 REVIEW 超过此阈值且无 review_record 视为孤儿）/ `reviewOrphanBatchSize`（默认 10，每轮扫描上限）。
- **`SubTaskService.listReviewOrphans`**（helloai-core）：查询 REVIEW 子任务（`status=REVIEW AND update_time <= threshold`，按时间升序 LIMIT batchSize），逐条 `reviewRecordMapper.selectCount` 检查是否已有 review_record，过滤掉已有记录的（防止重复触发）。
- **`SubTaskReviewService.scanReviewOrphans`**（helloai-core）：`@Scheduled(fixedDelayString=30_000)`，30s 间隔扫描。开关 `autoReviewEnabled` 关闭时跳过；调 `subTaskService.listReviewOrphans` 取候选 → 逐条 `reviewSubTask(st.getId(), st.getAssignedAgentId())`（pickReviewerAgent 选同角色 REVIEWER → 调 LLM → parseVerdict → completeOrRejectAndRework）。异常单条捕获不影响批次内其他候选。

#### 3. 验证结果

- `mvn compile -pl helloai-common,helloai-core -am -DskipTests` BUILD SUCCESS。
- 代码审查确认：`scanReviewOrphans` 与 `ExecutionCommandPoller` 兜底模式一致，30s 间隔 + 60s 阈值确保不误伤正常流程。

#### 4. 影响与遗留

- 三级容错架构成型：L1 `@TransactionalEventListener(AFTER_COMMIT)` 主路径 → L2 MQ `agent.reviewer.assigned`（无 consumer，待后续补齐）→ L3 `@Scheduled` DB 孤儿扫描兜底。
- 行为变更：REVIEW 子任务最多等待 60s（阈值）+ 30s（扫描间隔）= 90s 即可被兜底扫描捕获并核验。
- 遗留：L2 MQ reviewer consumer 仍缺失——当前 L3 兜底已足够（inner reviewer 无离线概念），MQ 路径待后续 If-needed 补齐。
- 部署提示：重启后端后生效；已卡住的子任务需等待 60s 阈值窗口到达后首次扫描核验（或手动 SQL 重置状态触发即时流程）。

### 6.50 门铃搁置下线：外部 Agent 单向执行器无法消费平台推送（2026-08-07）

#### 1. 背景与决策

基于对外部 AI Agent（安装版 REPL / CLI 版 Headless）的调研结论：两类 Agent 均为"单向执行器"——无平台双向交互能力，任务派发/完成依赖平台 MQ 内部链路，且 Agent 端代码不可修改（无法增加推送消费逻辑）。平台门铃（AgentHub V3 SSE 推送）虽已完整交付（PR-1~PR-4，E2E 实测通过），但**没有任何 Agent 端消费者**，属于"平台能推、Agent 收不到"的技术瓶颈。

**决策**（用户拍板）：
- **任务感知方案定稿（方案 A）**：Agent 定时轮询收件箱（`pullTasks`，建议 30s 一次）。平台内部 MQ 链路（Outbox → AGENT_TOPIC_EXCHANGE → notificationQueue → NotificationConsumer → agent_inbox）保持不动，不暴露给 Agent；"Agent 直接消费 MQ"记为远期演进项，本轮不实现。
- **门铃处置**：Java 代码（DoorbellService/Ringer/Properties、REST 端点 `/api/agents/doorbell/sse`）全部保留运行，仅加类注释说明搁置原因；SKILL.md（executor/planner）与 PowerShell 脚本（qoder-ceshi-checkin/daemon、outer-trae-daemon）下线门铃内容（脚本仅加头部注释，功能不动）。
- **双通道保留**：MCP（标准接入：保活 + 全套工具）与 REST（脚本轮询兜底）职责分工不变。

#### 2. 实际落地

- **Java 注释（不改业务逻辑）**：`DoorbellService` / `DoorbellRinger` / `DoorbellProperties` 类 Javadoc 追加"状态注记（2026-08-07）"搁置说明；`AgentMcpServerService` 设计原则注释与 checkIn 工具描述同步修正（去掉"门铃长连接前置"表述）；`AgentDutyLeaseService` 两处门铃断连注释加"门铃已搁置"注记。
- **SKILL.md 改写（executor + planner）**：接入方式表删除"门铃长连接"，改为"MCP 纯工具调用 / REST 轮询兜底"两段式；`checkIn`/`pullTasks` 工具描述去门铃语义（pullTasks 定为"唯一任务感知通道"）；§1.3 工作循环改为纯轮询循环（getAgentStatus → checkIn → 30s heartbeat + 30s pullTasks → claim → 执行 → submitResult → checkOut）；§1.5 常驻打卡协议整节改写为"轮询值守协议"（两件套：heartbeat + pullTasks，TTL 到期前 60s 重做 checkIn，删门铃三件套/断连重连/daemon 脚本引用）；§2 门铃长连接整节替换为"已搁置"说明；§1.4(4)"门铃连上≠进程健康"改为"心跳是唯一的在线证明"（强调业务调用只刷 last_active_time 不维持在线）；REST 段收敛（删积分/活动日志，保留收件箱/规则/子任务/审查）；错误码速查表删门铃语义（500 行原因改"未 checkIn 就调用依赖在岗状态的能力"）。
- **脚本头部注释（3 个 ps1）**：`qoder-ceshi-checkin.ps1` 追加"门铃探针步骤仅作历史链路验证参考"；`qoder-ceshi-daemon.ps1` / `outer-trae-daemon.ps1` 追加"门铃 SSE 监听逻辑仅作历史参考，值守请改用纯轮询（heartbeat + pullTasks）"。仅改 `#` 注释行，业务代码不动，保持 UTF-8 with BOM。
- **文档回填**：`doc/archive/HelloAI_门铃通知通道设计.md` 头部加"已搁置"状态注记；`doc/HelloAI_实现差距表.md` N13 条目状态改"已搁置"并注明原因。

#### 3. 验证结果

- `mvn -pl helloai-core -am compile -q` BUILD SUCCESS（Java 注释改动）。
- PowerShell Parser 对 3 个改动脚本静态语法自检 0 error。
- Grep 检查 `resources/skills/` 下门铃字样：仅保留"已搁置"说明句，无操作语义残留。
- 未运行后端服务（无行为变更）。

#### 4. 影响与遗留

- 任务感知时延从"秒级（门铃）"回归"轮询级（30s）"，外部 Agent 感知新任务最坏延迟约一个轮询周期。
- 平台端 MQ 内部链路、门铃 Java 代码、REST 端点全部保留，未来 Agent 端常驻 daemon（官方插件 / CLI 包装器）落地后可复用门铃通道。
- "Agent 直接消费 MQ"记为远期演进项（优先级最后）；CLI 版免保活（Headless 单次执行无值守）为新需求，待单独设计。

### 6.51 平台配置动态化：先启动后配置 API Key + 外网地址断层修复（2026-08-07）

#### 1. 背景与决策

- **目标态**：第一次部署只需环境变量 `HELLOAI_CREDENTIAL_AES_KEY_BASE64`（凭证加密密钥，唯一无法入库的部署配置），数据库由 Flyway 自动初始化、admin 账号由 AdminInitializer 自动创建，平台即可启动；LLM Provider 的 API Key 由管理员登录后在"系统设置"页填写/轮换，写入 `credential_vault`（AES-GCM 加密，PLATFORM 级），实时生效无需重启。
- **现状问题**：yml `helloai.providers.<name>.api-key` 启动绑定一次、运行期不可变，且写死真实默认 key（隐式预置 + 明文风险）；`spring.ai.deepseek.api-key` 置空后 `DeepSeekChatAutoConfiguration` 启动期 fail-fast（实测发现，计划外问题，见 §2 修复）；外网地址断层——Settings.vue 能写 `helloai.base-url` 到 sys_config，但 `AgentController.getMySkill` / `AdminAgentController.onboarding` 的 baseUrl 解析不读 sys_config，SKILL 生成仍 fallback `localhost:6565`。
- **决策**（用户拍板）：平台级密钥存 credential_vault 加密存储（非 sys_config 明文）；UI 扩展现有"系统设置"页（非新菜单）；本轮包含外网地址断层修复。明确不做：SetupWizard 加 API Key 步骤、超时参数动态化、SKILL.md 内容修改、Spring Cloud Config / Actuator refresh 新依赖、独立"模型配置"菜单页、SetupController 修改。

#### 2. 实际落地

- **DB 迁移**：`V45__credential_vault_platform_owner.sql`（沿用 V1/V14 同名约束，先 DROP IF EXISTS 再 ADD）放开 `chk_credential_vault_owner_type` CHECK（`'AGENT'` → `'AGENT','PLATFORM'`）+ 列 COMMENT 说明 PLATFORM 级 owner_id 固定占位 0、按 provider 唯一；索引不动（V14 uk 索引名历史遗留）。
- **枚举与凭证服务扩展**：`CredentialOwnerType` 新增 `PLATFORM`；`CredentialVaultService` 抽出私有泛化方法（getActiveApiKey / saveApiKeyCredential / rotateApiKey），Agent 版方法全部委托私有方法，新增 5 个平台级方法（getActivePlatformApiKey / listPlatformCredentials / hasActivePlatformCredential / savePlatformApiKeyCredential / rotatePlatformApiKey）。
- **新增 `PlatformProviderConfigService`**（core/agent/chat）：getApiKey（vault PLATFORM 级 ACTIVE 凭证解密明文 > yml > null，支持 secretRef）/ getBaseUrl / getDefaultModel（sys_config `llm.provider.<name>.*` > yml > Factory 内置默认）/ saveApiKey（AES 加密 → vault rotate → `ProviderChatModelCache.clear()` 实时生效）/ saveSettings（写 sys_config）/ isApiKeyConfigured / maskApiKey（仅尾 4 位）/ isApiKeyFromVault；参数校验统一 BizException。
- **后端接线**：`LlmProviderCatalogService` 三处改造（`listProviders()` 的 apiKeyConfigured 改调配置服务、`bindPlatformApiKeyIfAbsent` 平台 key 来源改 `getApiKey(provider)`、provisionPlatformCredential 不变）；DeepSeek / Minimax / AbstractOpenAiCompatible 三个 Factory 的 buildChatModel 内 baseUrl/defaultModel 改走配置服务（缓存 key 含 baseUrl 指纹不变），Moonshot / DashScope 构造器同步补参；`AgentChatClientService` / `ApiKeyAgentExecutor` / `AgentExecutionConnectivityService` 不改（Agent 级 vault 链路已动态化）。
- **外网地址断层修复**：新增 `AgentBaseUrlResolver`（helloai-api/support），解析优先级 `sys_config["helloai.base-url"]`（设置页可写）> yml `helloai.agent.base-url` > 请求推导（scheme://serverName:port）> `http://localhost:6565`；`AgentController.getMySkill` 与 `AdminAgentController.getOnboardingContent` 改调 resolver。
- **管理接口**：新增 `AdminProviderConfigController`（`/api/admin/platform/providers`，鉴权沿用 AuthInterceptor 对 `/api/**` 的统一保护，与 AdminConfigController 同等水平）：`GET /list`（name / defaultModel / baseUrl / apiKeyConfigured / apiKeyMasked / available / apiKeyFromVault）、`PUT /{provider}/api-key`（body {apiKey}）、`PUT /{provider}/settings`（body {baseUrl, defaultModel} 均可选，传空清除覆盖回 yml 默认）；配套 3 个 DTO（ProviderConfigItem / ProviderApiKeyRequest / ProviderSettingsRequest）。
- **yml 清理（关键安全项）**：4 个 provider 的 api-key 与 `spring.ai.deepseek.api-key` 全部置空为 `${XXX_API_KEY:}`；新增 `spring.autoconfigure.exclude: DeepSeekChatAutoConfiguration`（修复置空后启动 fail-fast——Agent 执行链已 100% 走 Factory 程序化构建 DeepSeekApi，该 autoconfig 仅剩 ChatClient.Builder 兜底且 `ObjectProvider.getIfAvailable` 缺失不阻断启动）；base-url / default-model / 超时保留为默认值；providers 段注释更新标注"可在系统设置页动态配置，api-key 为空时 provider 未生效"。
- **前端**：`helloai-ui/src/api/settings.ts` 新增 listProviders / saveProviderApiKey / saveProviderSettings + ProviderConfigItem 接口；`Settings.vue` 在"基础配置"与"通知配置"之间新增"模型配置（LLM Provider）"区块（el-table：Provider / 默认模型 / Base URL / API Key 脱敏或黄色"未配置" / 状态 / 操作）+ "配置 Key"对话框（password 输入，placeholder 提示可覆盖旧 Key）+ "编辑"对话框（baseUrl / defaultModel 均可选），保存后提示"配置已生效，无需重启"并刷新列表。

#### 3. 验证结果

- 单测 `PlatformProviderConfigServiceTest` 10/10 全绿（DB 优先 / yml 兜底 / 轮换幂等 + 缓存 clear / 脱敏 / 可用性判定，纯 Mockito 无 Spring 上下文）。
- `mvn -pl helloai-core,helloai-api -am compile -q` 与 `mvn -pl helloai-start -am package -DskipTests=true` BUILD SUCCESS；`npx vue-tsc -b` 0 错。
- **local profile 启动冒烟**（`--spring.profiles.active=local`，连本机 docker 中间件）：后端启动成功 `/api/health` 200，Flyway 自动应用 V45 成功（日志 "Successfully applied 7 migrations to schema public, now at version v45"）。
- `scripts/powershell/verify-platform-config.ps1 -ReadOnly`（复用运行中后端）PASS 4 / FAIL 0：admin 登录 OK；4 个 provider 列表全部 `apiKeyConfigured=false / apiKeyMasked=null / available=false`（yml 置空生效）；`listLlmProviders` 目录同步正常（factorySupported=true、available=false）；脚本遵循规则 6（UTF-8 with BOM + 编码强制头 + 单引号拼接），`Parser.ParseFile` 静态自检 0 error；-ReadOnly 模式不写库（S3 写 Key 前退出）。
- **e2e 完整写库链路实测（local profile，用户将 `spring.profiles.active` 切为 local 后执行）**：`scripts/powershell/verify-platform-config.ps1` 由脚本自拉起 jar（不重启进程）**PASS 22 / FAIL 0，ALL PASSED**：S2 初始 4 provider 全部未配置（yml 置空生效）→ S3 PUT api-key 写入测试 key → S4 实时生效（available=true / apiKeyFromVault=true / 脱敏 `****0001`）→ S5 目录同步（listLlmProviders available=true）→ S6 注册 API_KEY_LLM Agent → S7 AGENT 级 ACTIVE 凭证自动补绑（hasEncryptedValue=true）→ S8 sys_config 写 `helloai.base-url` 后 getMySkill SKILL 内容立即包含该地址（不重启）并写回空串还原。全程单进程实时生效，无重启。
  - **⚠ 首轮运行环境纠偏（重要事实链）**：首次手动执行（15:12）与第二轮复跑（15:16）时，jar 内打包的 `application.yml` 仍为 `active: dev`（src 已改 local，但改后未重新 `mvn package`；IDEA 自动构建只同步了 `target/classes` 不重打 jar），后端实际连服务器库 `39.106.204.43:15432`，两轮 S3-S8 均写入服务器共享库（PLATFORM/AGENT 级测试凭证 + `platform-config-e2e` agent，sys_config 已还原）。第三轮（15:25）重新 `mvn package`（jar 内 `active: local`）后连本机 docker local 库（localhost:15432，干净库）实现**真正的 local 全链路 22 PASS / 0 FAIL**（agentId=2085628380873048065，与服务器库残留 2085625109789908994 区分）。服务器库残留清理 SQL 已提供给用户执行（UPDATE 软删 credential_vault PLATFORM×2 / AGENT×1 + agent×1）。
  - **教训**：修改 resources 下配置（如 `application.yml` 的 profile/key）后必须重新 `mvn package` 再验证，IDE 自动构建的 `target/classes` 同步不能代表 jar 产物；e2e 脚本启动 jar 前可加一步 jar 内配置校验（如对比 jar 内 application.yml 与 src 的 `profiles.active`）。
- **待实测项已清空**：唯一未在真实环境回归的是"写库后真实 LLM 调用"（Factory 用测试 key 无法真连 DeepSeek），属既有 verify-agent-llm-connectivity 范畴，不阻塞本轮。

#### 4. 影响与遗留

- 老环境兼容：yml 已配 key 且 vault 无 PLATFORM 记录时 getApiKey 回退 yml，行为与现状完全一致；删除 vault PLATFORM 记录即回到 yml 配置行为。
- 新环境：yml 空时 provider 标记"未配置"，注册平台内 LLM Agent 下拉禁用（现有前端逻辑），不阻断平台其他功能。
- Agent 级 vault 凭证不受影响（owner_type 区分，唯一索引按 (owner_type, owner_id, provider, credential_type) 隔离）。
- `ProviderChatModelCache.clear()` 全清：正在执行的调用持有旧实例引用不受影响，完成后旧实例无引用即被 GC；可接受。
- 遗留：平台级凭证暂无删除接口（轮换可覆盖）；管理端鉴权与 /api/admin/* 同等水平（AuthInterceptor 统一保护，不强加新权限体系）；本地 e2e 写库实测待用户确认后执行。

---

### 6.52 LLM Provider 动态化方案B（V46，N9 §6.51 后续）（2026-08-07）

#### 1. 背景与决策

- **目标态**：LLM Provider 全部配置（`protocol_type / base_url / default_model / enabled / sort_order / extra_config`）从 `llm_provider` 表读取，运行时数据库为唯一事实源；管理员在“系统设置”页可动态添加 / 修改 / 启用-禁用 / 删除平台供应商（仅 OpenAI 兼容与 Anthropic 兼容两种 protocol，后续按需扩展）；API Key 走 credential_vault 仍不变（§6.51 闭环）；外部访问地址 `sys_config["helloai.base-url"]` 不动，本轮明确其用途从“系统基本配置”调整为“生成 SKILL 接入地址”。
- **决策**（用户拍板）：DB 驱动的 Provider 配置，全表 `llm_provider`，不拆多表；deepseek 保留专用 Factory（官方 SDK，`DeepSeekChatModel`），其他三家（moonshot/dashscope/minimax）全部走通用 ProtocolFactory；兼容协议本轮仅限 `OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE` 两种；老 yml `helloai.providers.*` 保留兜底（`AgentProviderProperties` 不动），migration 一次性把 4 家 INSERT 为 builtin 记录；旧 `AdminProviderConfigController` 兼容保留 / 新 `AdminLlmProviderController` 为正主；拖拽排序前端不实现（仅占位 `sort_order` 字段、后端 ready）；`from external import` 第三方批量导入 UI 不做。明确不做：API Key 动态化（已在 §6.51 闭合）、拖拽排序前端、Provider 粒度限流 / 配额、事件总线配置变更广播、第三方批量导入、Provider 配置变更审批流。

#### 2. 实际落地

- **DB 迁移**：Flyway `helloai-start/src/main/resources/db/migration/V46__llm_provider_table.sql`（71 行）——`CREATE TABLE llm_provider`（10 业务列 + `chk` 不需要走 §9.3 因为全部为 NOT NULL 或带 DEFAULT，加雪路 Id `IdType.ASSIGN_ID`）+ `idx_llm_provider_enabled` 部分索引 WHERE deleted=0 + `update_update_time` 触发器 + 幂等 `INSERT ... ON CONFLICT (provider_code) DO NOTHING` 4 家 builtin（deepseek/moonshot/minimax/dashscope；minimax 走 ANTHROPIC_COMPATIBLE，其他三家 OPENAI_COMPATIBLE）+ `setval` 序列同步。
- **实体 / Mapper / Service**：`core/system/entity/LlmProvider` 继承 `BaseEntity`，`provider_code / provider_name / protocol_type / base_url / default_model / enabled / builtin / sort_order / extra_config`，`extra_config` 由 `JacksonTypeHandler` 处理 JSONB；`LlmProviderMapper extends BaseMapper<LlmProvider>`；`LlmProviderService extends ServiceImpl<LlmProviderMapper, LlmProvider>`，`create()` 先 `toLowerCase` 归一化后 `validateCode`（正则 `[a-z0-9][a-z0-9-]{1,63}`）+ `validateProtocol`（仅两协议之二）+ 去重，`update()` 局部 patch（仅非 null 字段覆盖）+ `builtin` 不可改 `provider_code`，`deleteById()` 拒绝 `builtin=1`；`LlmProviderQueryService` 提供 `findByCode / listEnabled / listAll / getBaseUrlWithFallback`，仅读作 hot path 读取入口。
- **ProtocolFactory 族 + Registry**：`OpenAiCompatibleProtocolFactory`（原 MoonshotProviderChatClientFactory + DashScopeProviderChatClientFactory 抽取后通用化，从 `ProviderChatModelCache.getOrCompute` 建 ChatModel，连接超时 5s / 读超时 180s；baseUrl/effectiveModel 三层 fallback DB > provider > PlatformProviderConfigService）+ `AnthropicCompatibleProtocolFactory`（`AnthropicApi` 拼 /v1/messages，原 Minimax 抽出）+ `LlmProviderChatClientFactoryRegistry`（按 `provider.protocolType` 分发，深码 `deepseek` 走官方 SDK 优先匹配）。`ProviderChatModelCache.buildKey` 从 3 参数扩展为 `(provider, baseUrl, apiKey, protocolType)` 4 参数，保证 OpenAI / Anthropic 协议不串实例；DeepSeek factory 同步刷新为 4 参数版本。删除 `MoonshotProviderChatClientFactory / DashScopeProviderChatClientFactory / MinimaxProviderChatClientFactory / AbstractOpenAiCompatibleProviderChatClientFactory` 4 个文件（默认 yml 保留介 `AgentProviderProperties` 兌底读取）。
- **业务服务坊接**：`LlmProviderCatalogService` 从 `LlmProviderQueryService.listAll` 枚举；`PlatformProviderConfigService` baseUrl/defaultModel 读服务改为 `LlmProviderQueryService.getBaseUrlWithFallback(providerCode, ymlFallback)` 三层 fallback；`AgentChatClientService` 构造器由 `ObjectProvider<List<ProviderChatClientFactory>>` 改为 `LlmProviderChatClientFactoryRegistry`，`generate(...)` 一行改为 `registry.createChatClient(providerCode, apiKey, agent, model)`；`AgentExecutionConnectivityService / ApiKeyAgentExecutor / ChatClient.Builder Bean` 不动。
- **Controller**：`helloai-api/.../controller/AdminLlmProviderController`（`@RequestMapping("/api/admin/llm-providers")`）8 端点：`GET /list` / `GET /getById/{id}` / `POST /`（`CreateLlmProviderRequest`） / `PUT /updateById/{id}`（`UpdateLlmProviderRequest`） / `PUT /toggleById/{id}` / `DELETE /deleteById/{id}` / `PUT /{id}/api-key`（vault） / `GET /{id}/api-key` mask 脱敏；`LlmProviderResponse` 含 `apiKeyConfigured / apiKeyMasked / apiKeyFromVault`。**旧 `AdminProviderConfigController` 保留不动**作为迁移期兼容入口。3 个 DTO：`CreateLlmProviderRequest / UpdateLlmProviderRequest / LlmProviderResponse`（全部贴 §10.2 事务边界 + §6.3 不注入 Mapper）。
- **前端**：`helloai-ui/src/api/settings.ts` 增 `LlmProviderResponse / CreateLlmProviderRequest` 接口 + `llmProviderApi.{list / getById / create / update / delete / toggle / saveApiKey}`；`Settings.vue` 重写为 Codex++ 风格（约 375 行）——顶部「基础配置」区（平台名 + 外网访问地址 + 用途文案“生成 SKILL 接入内容” ）+ 中部「LLM 供应商」区左侧列表 + 右侧详情面板 + 「+ 添加供应商」对话框（名称 / 协议下拉 / Base URL / 默认模型 / 可选 API Key）；内置 Provider 绝不可改，代号不可变，刪除隐藏；自定义 Provider 可启停 / 改 / 删；API Key 输入走 el-dialog，保存后“实时生效，无需重启”提示。
- **设计备忘**：`LlmProvider` 实体明确定位为平台级 Provider 配置 (`system.entity`)，不是 chat 域的事；ChatClient 路由分发仍走 chat.provider。`LlmProviderChatClientFactoryRegistry` 仅中介按 protocolType 路由，具体怎么建 ChatModel 由 ProtocolFactory 实现。
- **代理 Provider 创建场景验证**（设计意图）：管理员手工填 `protocol_type=OPENAI_COMPATIBLE / provider_code=gpt-4-mini / base_url=https://api.openai.com/v1 / api_key=...` 添加 → 注册 API_KEY_LLM Agent 、选该 provider 、调外部 OpenAI → 期望 200。

#### 3. 验证结果

- `mvn clean package -DskipTests` 7 模块全 SUCCESS（HelloAI Common/MQ/Core/Job/API/Start + Root）。
- `mvn -pl helloai-core test` 416/416 全绿（含本轮新增 / 改造 9 例的 `LlmProviderServiceTest`：`shouldCreateWithNormalizedFields / shouldRejectDuplicateCode / shouldRejectInvalidCode / shouldRejectInvalidProtocol / shouldForbidBuiltinCodeChange / shouldAllowBuiltinUpdateOtherFields / shouldOnlyOverwriteNonNullFields / shouldForbidBuiltinDeletion / shouldAllowCustomDeletion`）。重点修补點：
  - **`ServiceImpl.baseMapper` 问题**：单测中 `ServiceImpl` 父类 `baseMapper` 字段需要由 Spring 自动注入，`mvn test` 下 Spring 未启动；用 `ReflectionTestUtils.setField(service, "baseMapper", mapper)` 手动注入（**不传 type 参数**，因为 `baseMapper` 擦除类型为 `BaseMapper` 不是 `LlmProviderMapper`，传了会被 `ReflectionTestUtils` 报 "field of type [interface ...LlmProviderMapper] not found on target"）。后续测试如需调 ServiceImpl 方法仍遵此范例。
  - **代码归一化路径**：`service.create()` 原本将 `validateCode` 放在 `toLowerCase` 之前 → “Custom-GPT-4” 永远会被判为非法。现改为先归一化后 validate，单元测试验证三点：(1) “null/空白” → “provider_code 不能为空”；(2) “MIXED-Case” 归一化为 “mixed-case”；(3) “a” 长度不足 2 / “Bad@Code” 含非法字符 → 两段独立失败。`Production` 路径·Controller 也调 `toLowerCase` 双重防御。
- 残留检查：`grep "MoonshotProviderChatClientFactory|DashScopeProviderChatClientFactory|MinimaxProviderChatClientFactory|AbstractOpenAiCompatibleProviderChatClientFactory"` —— 只剩 OpenAiCompatibleProtocolFactory / AnthropicCompatibleProtocolFactory 类注释中“取代原 XxxFactory”说明 + doc/log/HelloAI_迭代执行记录.md 历史足迹。零代码引用。
- `npx vue-tsc -b` 0 错。
- **未实测项**（高优，建访重环境上修）：验 `AdminLlmProviderController` 8 端点真实调用、新增自定义 OpenAI 兼容 provider 在真实 LLM 环境下调成功、`AdminProviderConfigController` 旧入口迁移期走通。这三件均依赖 API Key / DB 环境，沙箱不能复现。

#### 4. 影响与遗留

- **仃能推进**：本轮 N9 由“仅 Provider API Key 动态化”升级为“Provider 全零态动态化”；后续 Agent / 执行链 / 调度反射者只需重发表 `llm_provider` 表，`LlmProviderChatClientFactoryRegistry` 会自动热刷该 provider 的 ChatModel。cache key 的 protocolType 维度使 OpenAI 与 Anthropic 实例不会错位。
- **老环境兼容**：V46 幂等 INSERT 4 家 builtin，老 yml 定义 (用过6.51 后 API Key 在空) 与本轮变更零冲突；Codex++ 风格 UI 不變老行为，仅在“系统设置”页多一个「LLM 供应商」区块。
- **明确不做**：拖拽排序前端、Provider 粒度限流 / 配额、`from external import` 第三方批量导入、事件总线配置变更广播（手动 `ProviderChatModelCache.clear()` 调用已够用）、Provider 配置变更审批流。
- **遗留**（下一轮处理优先级建议）：① 真实环境 E2E 验证（3 场景如上）；② 旧 `/api/admin/platform/providers/...` 调用方补调迁告；③ Provider 变更后分发未存 `ProviderChatModelCache.clear()` 补正（现仅在 API Key 变更处调用，baseUrl / defaultModel 变更靠 ChatModel 新 key 自动重建）；④ 聊天协议多协议扩展点（如未来需 Gemini / Cohere）。

### 6.53 「保存设置」500 NPE 修复（与方案B 无关的历史 bug 顺手清）（2026-08-08）

#### 1. 背景与决策

- 现象：系统设置 → “保存设置”点击后 `PUT /api/admin/config/batch` 返 500。
- 根因：`helloai-ui/src/api/settings.ts:79` `batchUpdateConfig` 直接把 `Record<string,string>` flat map 作为请求体发出去；后端 `ConfigBatchRequest` 期待 wrapper 结构 `{config:{...}}`。`req.getConfig()` 为 null → `SysConfigService.batchUpdate` 调 `configMap.forEach(...)` → `NullPointerException`。
- 业务间：这个问题早在方案B 之前就存在；只是 `Settings.vue` 改造后 Provider 区域加了 API Key 表单，第一次在真实环境点了“保存设置”才被谁发现。
- 决策：按用户意愿**只改前端**，不动后端。后端 DTO 契约与 NullPointerException 裸露后续可以一起收（拆 demand 到独立 bug 表）。

#### 2. 实际落地

- 改 `helloai-ui/src/api/settings.ts`：`batchUpdateConfig(map)` → `request.put('/admin/config/batch', { config: map })`，调个调用点加 1 行注释说明“后端期待 wrapper，不能发 flat map”。
- 不动后端、不动数据库、不动迁移。

#### 3. 验证结果

- `npx vue-tsc --noEmit -p tsconfig.json` 0 错（项目本地 `.\node_modules\.bin\vue-tsc.cmd`，不走 npx 拉不同版本的 typescript）。
- `mvn -DskipTests -pl helloai-api,helloai-core,helloai-common -am compile` 0 错（虽未动后端代码，但确认前端改造不影响后端编译）。
- 真机口验：`保存设置` 走通，`system.name` / `helloai.base-url` 都写入 `sys_config`（用户可见 200 响应 + “保存成功”提示）。

#### 4. 影响与遗留

- 影响：解决了本轮 Settings.vue 改造后唯一遗留的真实可见 bug；前端 `/api/admin/config/batch` 调用语义与后端 DTO 对齐。
- 遗留：后端 `SysConfigService.batchUpdate` 依然裸露 NPE（controller 未做空校验、service 未加 null guard）。下一轮建议顺手加 `if (req == null || req.getConfig() == null) return;` 避免类似改动进一步产生 500。可独立 demand，不需绑回方案B。

### 6.54 验证链围栏落地（三角色 SKILL 围栏 + 自动核验证据信号）（2026-08-10）

#### 1. 背景与决策

- **来源**：用户引入两篇外部方法论——「AI 围栏五层」（L1 输出自检 / L2 事实来源 / L3 执行验证 / L4 独立复核 / L5 评审挑刺）与 `E:\workspace\verify-chain-master` 验证链（Critic 断言提取 → Verifier 逐条核查 → Repairer 最小修复，四态结论 ✅⚠️❌❓）。分析结论：HelloAI 外部 Agent 架构下"假成功"是结构性风险（平台看不到执行过程，只见 submitResult 文本），值得选择性融入。
- **决策**（用户拍板，计划《验证链围栏落地》）：分两阶段——①提示词软围栏（三个 SKILL.md，零风险立即生效）；②代码硬围栏检测版（Parser 解析 VERIFICATION + 自动核验 prompt 注入证据信号，**只检测不拦截**，不加 DB 迁移，存量外部 Agent 零破坏）。明确不做：VERIFICATION 缺失硬拒收、DB 持久化 verification 列（留待观察一轮后再议）、Reviewer 并行 SubAgent（外部 Agent 无子代理机制）。
- **联网搜索分流**（用户补充需求，融入 Planner 层）：拆解前「关键前提核查」分两类——内部前提（本项目接口/字段/机制）必须读代码/查库核实；外部前提（第三方库/外部服务/框架兼容性）条件允许时联网搜索并注明来源；无法核实的标注【前提未核实】写入子任务 content，禁止把未核实假设当已确认事实。

#### 2. 实际落地

- **executor SKILL.md**（执行围栏 + fail-close）：EXECUTION_RECORD 协议新增 `VERIFICATION:` 段（命令/输出/结论三行，输出须原样粘贴）；新增 fail-close 硬条款（验证失败或未验证禁止声明完成，须 reportBlocked 或如实标注"未验证"）；§4.5 提交前自检清单追加 2 项；示例块同步更新。
- **planner SKILL.md**（前提核查 + 合规自检）：§2.1 拆解前新增「关键前提核查」步骤（3~5 条，内/外部前提分流表，引用门铃推送历史教训）；验收标准字段改为硬要求（禁止"功能正常""质量合格"类模糊表述，附正/反例）；新增「创建合规自检清单」5 项（四要素/可检查/前提已核/无重复拆分/数量与依赖序）。
- **reviewer SKILL.md**（断言式三段审查法 + 有罪推定）：工作流程第 6 步改为——①提取断言（5~15 条，按类标注，聚焦"一错就全错"硬断言）→ ②逐条核查（读文件/跑命令/查日志，四态结论逐条附证据）→ ③汇总裁决（❌驳回列证据 / 仅⚠️按严重度评分 / ❓不替执行者背书）→ ④证据复核（交付物携带 VERIFICATION 时复核命令/输出/结论真实性，防伪造证据）→ ⑤先记后改；审查原则新增"有罪推定""只认证据"两条。
- **ExecutionRecord / ExecutionRecordParser**：`ExecutionRecord` 新增 `verification` 字段 + `hasVerification()` + toMap/fromMap 往返（无证据时不写键）；Parser 按协议约定截取块内 `VERIFICATION:` 段原文，缺失时 debug 日志 + 空串，**解析仍成功不拦截**。
- **SubTaskReviewService + subtask-review.md**：新增 `extractRawOutput`（截断前原文）与 `verificationSignal`（检测 `VERIFICATION:` 存在性），模板新增 `{{VERIFICATION_SIGNAL}}` 占位符与「验证证据信号」章节；核验要求新增第 6~8 条（有证据核对一致性防伪造 / 无证据从严评分保守 / 无法确定不得判 pass=true，fail-close）。

#### 3. 验证结果

- `mvn -pl helloai-core -am test`：**17/17 全绿**——新增 `ExecutionRecordParserTest` 5 例（携带 VERIFICATION 完整解析 / 缺失仅检测不拦截 / 缺 SUMMARY 返回 null 维持 fallback 语义 / toMap-fromMap 往返不丢失 / 无证据不写键）+ 回归 `SubTaskReviewServiceTest` 12/12 无破坏。BUILD SUCCESS。
- 三个 SKILL.md 由外部 Agent 经 SKILL 拉取通道动态获取，改文件即对后续上岗 Agent 生效，无需重启契约。
- **待人工实测**（用户执行）：本地启动项目 → 真实请求走完"创建任务 → Planner 拆解（看前提核查痕迹）→ Executor 提交带 VERIFICATION 的 output → 自动核验"链路，并用只读 SQL 核对 `review_record` / `sub_task` / Task Running Spec 记录。

#### 4. 影响与遗留

- 影响：无 DB 迁移、无状态机变更、无契约破坏；硬围栏仅作用于自动核验 prompt 注入，人工审查链路不受影响。
- 遗留（观察一轮后再议）：① VERIFICATION 缺失硬拒收；② `task_execution_record` 表持久化 verification 列（Flyway 迁移）；③ 无证据提交占比数据积累后决定是否升级为结构性拦截。

### 6.55 人工介入兜底：返工达上限/降级能力不匹配时用户自主选择 Agent（2026-08-10）

#### 1. 背景与决策

- **真实事故**：子任务「实现订单超时取消校验脚本 verify-order-expire.ps1」因 trae 打卡超时离线被 inner-loop-executor（API_KEY_LLM）领取；inner 无本机执行能力，反复提交"文档化产出"而非可执行脚本，3 次驳回达 `auto-review-max-rework=3` 上限后卡死 REVIEW。日志证实 15:54:06 记录了 `sub_task_auto_review_skip_max_rework`，但当时代码只写 timeline 不写人工介入标记；叠加 `listReviewOrphans` 把"已有 review_record"的任务排除（该任务有 3 条历史驳回记录），事件链丢失后孤儿扫描永远扫不到 → **永久卡死 REVIEW，无任何自动/人工入口**。
- **决策**（用户拍板）：返工达上限 / 降级能力不匹配时写 `context.manualIntervention` 标记；前端 REVIEW 详情页展示「人工介入」面板——全量 Agent 选择器（外部 CLI_CLIENT 如 trae/qoder/claudecode + 内部 API_KEY_LLM 均可选，在线优先）+ 「驳回并改派」（REJECTED + reworkAgentId 走正规 review API，触发 outbox 推送）/「直接通过」（人工验收 APPROVED 不受返工上限限制）。明确不做：自动挑选"下一个最优 Agent"（返工达上限必须人工拍板，避免再进循环）。

#### 2. 实际落地

- **SubTaskService.markManualIntervention**：幂等写 `context.manualIntervention{reason, ts, extra}`（rework_limit / fallback_skip_execution_dense），失败不抛异常。
- **SubTaskReviewService.reviewSubTask**：`reworkCount >= autoReviewMaxRework` 时记 timeline + 打人工介入标记后 return（不再自动打回）。
- **SubTaskDispatchService.redispatchForFallback**（§6.52 能力预检）：执行密集任务（内容/验收/交付物含 `.ps1/.sh/.jar`、docker、启动服务等关键词）不回退给无本机能力的 API_KEY_LLM，停留原状态 + 标记人工介入；`fallback-skip-execution-dense` 默认 true。
- **SubTaskService.listReviewOrphans（关键修复）**：排除条件从「有 review_record」改为「有 manualIntervention 标记」——返工达上限任务同样持有 review_record，旧逻辑导致事件链丢失时永远无法兜底；新逻辑保证这类卡死任务能被孤儿扫描发现并补写标记。
- **前端 SubTaskDetail.vue**：`needsManualIntervention`（context 有标记 或 REVIEW 且 reworkCount>=3 双兜底）+ 人工介入卡片（reason 标签 + 当前负责人 + Agent 选择器 + 驳回改派/直接通过按钮），提交走 `reviewApi.create`（REJECTED 带 reworkAgentId / APPROVED）。

#### 3. 验证结果

- `mvn -pl helloai-core -am test`：**426/426 全绿**（新增 `SubTaskServiceIsReadyTest` 2 例孤儿扫描回归：有 review_record 无标记的任务保留可兜底 / 有标记任务排除；含已存在的 `SubTaskReviewServiceTest` 超限打标记 + `SubTaskDispatchServiceTest` 能力预检用例）。BUILD SUCCESS。
- `vue-tsc -b --force`：TSC-OK 0 error。
- **存量卡死任务处置（真实事故闭环）**：子任务 2086720079347281924（REVIEW/reworkCount=3）经 `POST /api/reviews` 人工驳回改派 trae-executor（2086711950328950786）：`REJECTED score=1 + reworkAgentId` → 状态 REWORK、assigned_agent 切换、`agent_outbox_event` 生成 `sub_task.rework`（status=1 已投递）；review_record round=4 的 issues/comment 中文乱码（PS 5.1 按 GBK 解析 no-BOM 源文件所致）已用 UTF-8 字节流直写修正。

#### 4. 影响与遗留

- 影响：无 DB 迁移（标记内嵌 context）；后端需重新打包部署后新逻辑生效；存量卡死任务可被孤儿扫描自动补标（部署后 ≤60s），前端 reworkCount>=3 兜底已可先行展示面板。
- 遗留：① 人工介入面板仅出现在子任务详情页，主任务视图无聚合告警；② 改派后无"未认领提醒"（依赖外部 Agent 轮询 outbox）；③ 执行密集判定目前为关键词启发式，误判率观察后再议。

### 6.56 依赖守卫 + 执行密集能力预检全链路下沉：修复"依赖未完成的任务被重派给无能力 Agent 假完成"（2026-08-10）

#### 1. 背景与决策

- **真实事故 2（承 §6.52/6.55 同源）**：子任务 2086720079347281925「冷启完整环境并串行执行三个验证脚本」依赖 1924（verify-order-expire.ps1）与 1922/1923，但 1924 仍 REVIEW 时 1925 被标 DONE。时间线：trae 16:33 提交 1924（第二次 `sub_task_auto_review_skip_max_rework`）→ 16:38:37 trae 心跳离线 → `agent_offline` 巡检把 1925 重派给 inner-loop-executor（API_KEY_LLM，capabilities 全 false 无本机能力）→ inner 19 秒"幻觉执行"（编造 docker ps / netstat / 三脚本 PASS=32 的日志与订单号，全部不存在）→ probe-moonshot-reviewer 审核 APPROVED → 1925 DONE，依赖它的下游被解锁。
- **明确结论**：不是"重新分配给 trae 的任务超过重试最大次数默认完成"——1924 至今仍 REVIEW（重试上限只做 skip_max_rework 打标记，系统无任何"默认完成"逻辑）。
- **根因三环节叠加**：① `redispatchOfflineSubTask`（agent_offline 重分配）无 `isReady` 依赖守卫（`dispatchPendingSubTaskAuto` 有守卫、离线路径没有）；② §6.52 能力预检只挂在 `redispatchForFallback`，`ResilientDispatcher.assignNext`/fallback 选人环节不查 capabilities；③ 审核侧无"提交者能力"校验，核验 LLM 无法辨别无能力 Agent 的幻觉证据。
- **决策**（用户拍板修复三处缺陷）：离线重分配补依赖守卫；能力预检下沉到 ResilientDispatcher 分配主路径 + fallback 替代选人；审核侧对"执行密集 + 无能力提交者"跳过自动核验打人工介入标记；两条 PENDING 兜底巡检跳过带人工介入标记的任务。

#### 2. 实际落地

- **SubTaskDispatchService**：`isExecutionDense` / `hasLocalExecutionCapability` / `isManualInterventionMarked` 由 private 改 **public static**（供 ResilientDispatcher / SubTaskReviewService / job 兜底任务复用，避免各入口各自实现判定发散）；`redispatchOfflineSubTask` 在 reset 后补 `isReady` 依赖守卫——未就绪保持 PENDING，记 `sub_task_dispatch_skip_dependency`（trigger=agent_offline），等依赖 DONE 后由 SubTaskPendingOrphanTask / 自动分发链再次触发。
- **ResilientDispatcher**（构造器新增 AgentDispatchProperties + TaskTimelineService）：`assignNext` 主路径在心跳 fast-fail 后加 `isExecutionDenseMismatch` 预检——执行密集任务命中无本机能力 Agent（API_KEY_LLM 且 capabilities.supportsMCP != true）时记 `sub_task_dispatch_skip_no_capability` + `markManualIntervention("dispatch_skip_execution_dense")` + 抛 AgentUnavailableException 走 fallback；`assignNextFallback` 对替代 Agent 同样预检，不匹配则放弃分配（任务停留 PENDING 人工处置，不再抛异常冒泡）。开关沿用 `fallback-skip-execution-dense`（默认 true）。
- **SubTaskReviewService.reviewSubTask**：`skip_max_rework` 分支之后、选 reviewer 之前加提交者预检——执行密集任务 + 提交者（executorAgentId 回退 assignedAgentId）无本机能力时跳过自动核验，记 `sub_task_review_skip_no_capability` + `markManualIntervention("review_skip_execution_dense_no_capability")`。
- **SubTaskPendingOrphanTask / ExternalAgentFallbackTask.recoverPendingUnassigned**：两条 PENDING 兜底循环均跳过 `isManualInterventionMarked` 的任务（防"无能力/返工超限"人工场景被兜底链反复打回调度链）。

#### 3. 验证结果

- `mvn -pl helloai-core,helloai-job -am test -DskipTests=false`：**BUILD SUCCESS**。core 全量 + job 全量通过；新增回归用例 10 个：ResilientDispatcherTest +4（主路径拒绝/有 MCP 放行/fallback 替代拒绝/替代放行）、SubTaskDispatchServiceTest +1（离线重派依赖未就绪不重派）、SubTaskReviewServiceTest +2（无能力提交者跳过核验/有能力正常核验）、SubTaskPendingOrphanTaskTest +2（有标记跳过/无标记正常）、ExternalAgentFallbackTaskTest +1（有标记跳过）。`ResilientDispatcherAopIntegrationTest` 补 AgentDispatchProperties/TaskTimelineService 两个 @MockBean 后 3/3 恢复。
- **测试坑位**：根 pom 默认 `<skipTests>true</skipTests>`，跑测试必须显式 `-DskipTests=false`；PowerShell 下 `-Dtest=A,B` 与 `-Dsurefire.failIfNoSpecifiedTests=false` 需整体加引号。

#### 4. 影响与遗留

- 影响：无 DB 迁移；ResilientDispatcher 构造器新增 2 依赖（Spring 自动注入无配置变更）；行为变化——执行密集任务不会再被分给无本机能力 Agent（含 fallback 替代），审核侧不再自动核验无能力提交者的执行密集产出，两条兜底巡检不再重派带人工标记的 PENDING。
- 遗留：① 存量卡死任务 1924/1926（REVIEW）需部署新代码后由孤儿扫描补标（≤60s），前端人工介入面板处置；② inner 幻觉执行的审核辨别仍依赖证据信号从严条款（§6.54），本修复从"源头不派"层面消除无能力执行；③ `SubTaskDispatchService.isExecutionDense` 关键词启发式误判率观察后再议（承 §6.55 遗留③）。

### 6.57 人工驳回重置返工计数：修复"改派后新执行者提交仍命中 skip_max_rework 跳过审核、无节点流转"（2026-08-11）

#### 1. 背景与决策

- **真实事故 3（承 §6.52/6.55/6.56 同源 1924/1926）**：用户反馈"内部 LLM 接任务完成反馈不佳、驳回 3 次直接跳过验证，人工介入重新分配其他外部/内部 agent 后失败次数未重新计算，review 角色审核时出现跳过审核、无节点流转"。**数据库实证（helloai 库）**：1924「verify-order-expire.ps1」07:51 inner-loop-executor（API_KEY_LLM 无本机能力）执行 → 07:52-07:54 自动驳回 3 轮（review_record round 1-3）→ 07:54:06 `sub_task_auto_review_skip_max_rework` 停 REVIEW → 08:15 人工改派 trae-executor（round 4 REJECTED）→ 08:31-08:33 trae **真实执行完成**（context.lastExecution：脚本落地并实际运行 PASS=12 FAIL=0 全绿）→ 08:33:22 提交后**再次** `sub_task_auto_review_skip_max_rework`（reworkCount 仍是 3）→ 此后无任何事件，**合格产出无人审核、永久卡 REVIEW**。1926「生成验证报告」同构卡死（08:41:07 skip 后无人工处置记录）。
- **根因**：自动驳回走 `SubTaskService.rework()` 累加 reworkCount，而人工驳回（`ReviewService.createReview` REJECTED）只走 `changeStatus` 不重置计数——改派后 reworkCount 残留 3，新执行者提交即命中 `reworkCount >= autoReviewMaxRework` 跳过自动核验；且 `manualIntervention` 标记在人工拍板后不清除，前端面板残留、PENDING 兜底巡检持续跳过。
- **决策**（用户拍板"所有人工驳回都重置"）：人工驳回 = 用户拍板开启新一轮，无论是否换 agent 都重置计数并清除标记；自动驳回仍累加（3 次后停），两条路径语义分工。

#### 2. 实际落地

- **SubTaskService.reworkFresh**（新增，与 `rework` 并列）：REVIEW→REWORK 状态校验 + `reworkCount=0` + `assignedAgentId` 换派（可空则保持原执行者）+ 清除 `context.manualIntervention` + outbox 事件 + timeline `sub_task_manual_rework_reset`。
- **ReviewService.createReview**：REJECTED 分支由 `changeStatus` 改走 `reworkFresh`——人工驳回统一重置计数并清除介入标记，改派后的新执行者从 0 开始计数，提交后走正常自动核验链路。

#### 3. 验证结果

- `mvn -pl helloai-core -am test -DskipTests=false -Dtest=ReviewServiceTest,SubTaskReviewServiceTest`：**全部通过**。新增 `ReviewServiceTest` 4 用例（人工驳回改派走 reworkFresh / 不改派同样重置 / 人工通过走 complete 不触发重置 / 驳回缺 issues 抛 BizException）；`SubTaskReviewServiceTest` 14 用例无回归。
- **数据库旁证**：1924 改派后 trae 提交（execute_submit 08:33:22）→ 08:33:22.773 skip_max_rework，两次 skip 间隔内无任何 review_record 写入——实证"跳过审核 + 无节点流转"。

#### 4. 影响与遗留

- 影响：无 DB 迁移；行为变化——人工驳回后 reworkCount 归零（新执行者有完整 3 次机会）、manualIntervention 清除（前端面板自动隐藏、PENDING 兜底巡检恢复对该任务可见）；自动驳回路径不变。
- 遗留：① 存量卡死任务 1924/1926 部署新代码后：1924 的 trae 产出实际合格（PASS=12 FAIL=0），前端面板"直接通过"即可闭环；或"驳回改派"后新执行者正常走审核；1926 需人工处置；② trae-executor `consecutive_failure_count=2` 疑似把"系统跳过审核"计为外部 agent 失败，观察 ExternalAgentFailureTracker 是否把 skip 类事件计入失败（待确认，不在本次范围）。

### 6.58 AgentHealthCheckTask 语义修正：无在跑子任务不记 N11 失败 + executor SKILL 心跳强化（2026-08-11）

#### 1. 背景与决策

- **真实形态**：trae-executor 等外部 Agent"提交产出后静默待命"（不再发心跳但也不下线）——按旧逻辑 `handleAgentOffline` 无条件 `failureTracker.recordFailure(agent.getId())`，每完成一个任务就累计 1 次失败，叠加到 N11 阈值后触发误回退（干活的 Agent 被错误替换）。
- **决策**：离线时仅当存在在跑任务（ASSIGNED/IN_PROGRESS）才视为执行失败——心跳丢失导致任务中断，失败语义成立；无在跑任务说明客户端只是"提交后停止心跳"的静默待命，不计失败。

#### 2. 实际落地

- **AgentHealthCheckTask**：`reassignStaleTasks` 由 void 改为返回在跑任务数（staleTasks.size()，空则 0）；`handleAgentOffline` 中 `int inFlightCount = reassignStaleTasks(agent)`，仅 `inFlightCount > 0` 时 `failureTracker.recordFailure`，其余路径（agent 为 null / 无待重分配任务）一律返回 0 不计失败。
- **executor SKILL.md**：§1.3 心跳节拍前新增"提交不等于下班"警示块——`submitResult` / `ack` 后必须回到步骤 3 继续心跳轮询等待下一单；提交后静默退出会在 5 分钟内被判 OFFLINE（即使产出合格）且后续任务被重派；只有确认下线才走「下线清理剧本」。

#### 3. 验证结果

- `AgentHealthCheckTaskTest` 12 用例全绿（含"无在跑任务离线不计失败"新增断言）。

#### 4. 影响与遗留

- 影响：无 DB 迁移；N11 失败计数只统计"离线时确有在跑任务"的场景，静默待命 Agent 不再被误伤。
- 遗留：无。

### 6.59 任务级 agentPolicy + 能力声明落地（V47）：Planner/Executor/Reviewer 指定语义 + N11 回退策略约束 + 技能匹配（2026-08-11）

#### 1. 背景与决策

- **对齐目标态**：架构参考 §4.8 目标态八「Agent 能力满足当前子任务要求」——任务可显式指定执行/拆解/核验角色，且选人链按任务要求过滤 Agent 能力，防止"无能力 Agent 被自动选中 → 返工循环"。
- **决策**（用户拍板完整方案 P1）：任务级 `agent_policy` JSONB（plannerAgentId / executorAgentIds[] / reviewerAgentId / fallbackPolicy AUTO·RESTRICTED·NONE / difficulty LOW·MEDIUM·HIGH）+ 任务 `required_skills`（AND 语义）+ Agent `skills`，选人链贯穿约束；N11 回退按策略约束（NONE / HIGH 禁止自动回退改人工介入；RESTRICTED 仅回退白名单内 API_KEY_LLM）。

#### 2. 实际落地

- **Flyway V47**：`task.agent_policy`（JSONB 默认 `{}`）、`task.required_skills`（JSONB 默认 `[]`）、`agent.skills`（JSONB 默认 `[]`）三列 + COMMENT + DO 验证块；旧数据行为与默认值完全一致（防御式回落默认）。
- **TaskAgentPolicy**（core/task/service 静态工具类）：全部 policy 读取/判定收口——plannerAgentId / executorAgentIds（List 防御转换）/ reviewerAgentId / fallbackPolicy（非法回落 AUTO）/ difficulty（非法回落 MEDIUM）/ isFallbackForbidden（NONE 或 HIGH）/ build（null 与空键不写入，测试与写库入口复用）。
- **AgentSelector 约束链**：新增嵌套类 `AgentSelectionConstraints`（allowedAgentIds 空=不限制 + requiredSkills 非空=全匹配 AND，agent null 直接拒绝）；`pickPreferred` / `pickAlternative` 增加 3 参重载，`pickFromCandidates` 在 exclude 过滤后追加约束过滤环（集合限定 + 技能匹配）。
- **ResilientDispatcher 3 参重载**：`assignNext(agentId, subTaskId, constraints)` + 独立 fallbackMethod（规避 Spring AOP 同类内部委托失效）；`doAssignNext` 内首选不满足约束 fast-fail 抛 AgentUnavailableException → 走受约束 fallback（`pickAlternative(agentId, role, constraints)`），保证 fallback 不越出白名单/技能范围。
- **Planner 指定语义**（PlannerAgentPicker.pickForTask）：`task.agent_policy.plannerAgentId` 优先于会话钉住；失效（删除/禁用）回退自动选择（由 pick 内置），不阻断拆解。
- **Executor 五入口接约束**（SubTaskDispatchService）：dispatchBlockedSubTask / redispatchOfflineSubTask / dispatchPendingSubTaskAuto / redispatchAssignedTimeout 均解析任务 policy → `AgentSelectionConstraints` 传入选人与派发；`resolveConstraints` / `loadAgentPolicy` / `loadTask` 辅助方法防御式读取（task 缺失按无约束处理）。
- **N11 回退策略约束**（redispatchForFallback）：`isFallbackForbidden`（fallbackPolicy=NONE 或 difficulty=HIGH）→ 跳过回退 + timeline `sub_task_fallback_skip_policy` + `markManualIntervention("fallback_skip_policy")`，不落 LLM；RESTRICTED → 仅回退 executorAgentIds 内 API_KEY_LLM，目标不在集合（或集合空）等同 NONE 打人工介入标记。
- **Reviewer 指定语义**（SubTaskReviewService.pickReviewerAgent）：`task.agent_policy.reviewerAgentId` 优先——指定 Agent 可用（存在且 ACTIVE 且 API_KEY_LLM）直接采用；失效 log.warn 后回退原自动链（pickPreferred REVIEWER → 同角色 API_KEY_LLM → PLANNER 角色 API_KEY_LLM）。

#### 3. 验证结果

- `mvn -pl helloai-core -am test -DskipTests=false -Dtest=TaskAgentPolicyTest,AgentSelectorTest,PlannerAgentPickerTest,SubTaskDispatchServiceTest,SubTaskReviewServiceTest`：**Tests run: 90, Failures: 0, Errors: 0**（TaskAgentPolicyTest 5 / AgentSelectorTest 37 含 TaskLevelConstraints 7 用例 / PlannerAgentPickerTest 13 / SubTaskDispatchServiceTest 19 含 V47 四用例 / SubTaskReviewServiceTest 16 含指定优先与失效回退两用例）。

#### 4. 影响与遗留

- 影响：V47 迁移三列（纯增量，默认值兼容旧数据）；行为变化——任务创建入口可写 policy / required_skills（当前由任务创建侧写库，平台侧提供 build 工具类）；N11 回退受任务策略约束。
- 遗留：① 任务创建/编辑前端暂未暴露 policy 编辑 UI（API 层与工具类已就绪，留待前端迭代）；② `agent.skills` 暂由注册侧/管理员维护，未做 Agent 能力自动推导；③ required_skills 技能匹配为精确字符串全匹配（AND），未做同义词/层级归一。

### 6.60 改派/抢占撤销通知（A0-1：trae 实战反馈一.1「任务改派后旧 agent 无撤销事件」）（2026-08-11）

#### 1. 背景与结论

- **实战痛点（trae 1925）**：任务改派/抢占后旧 agent 只收到「分配」通知，无「改派/撤销」事件，误以为任务仍在名下继续干活（冷启动白做）。
- **入口梳理结论**：全部改派入口（dispatchBlockedSubTask / redispatchOfflineSubTask / redispatchForFallback / redispatchAssignedTimeout / redispatchDeadLetter）共用 `resetToPendingForDispatch`（直接 updateById 清空 assignedAgentId，无任何通知）；重新 ASSIGNED 只通知新 agent——旧 agent 完全感知不到任务转移。人工改派/人工驳回换派走 changeStatus / reworkFresh，同样无撤销事件。
- **收口设计**：不在 4 个改派入口各自补发（易漏），而在 `SubTaskService` 咽喉点 `changeStatus` + `rework` + `reworkFresh` 内做换人检测（oldAgentId != null && != newAgentId），一处覆盖全部路径（含人工改派、reworkFresh 换派、dead-letter 重派）；dead-letter 路径（changeStatus(DEAD_LETTER, null) 保留原执行者 → old==new 不触发，redispatchDeadLetter 换人时触发 reassigned）。

#### 2. 实现要点

- **SubTaskService.notifyAgentHandover**（新增私有方法）：换人（newAgentId != null）→ 旧执行者收 `sub_task.reassigned`（「任务已改派，请立即停止执行」）；回收（newAgentId == null）→ `sub_task.unassigned`（「任务已回收」）；初始分配（old == null）与原地保留（old == new）不通知；eventId `subtask.{id}.handover.{ts}` 保证幂等；复用 AgentInboxService.send（API_KEY_LLM 旧执行者由内部守卫跳过，消费链走 outbox→MQ）。
- **changeStatus**：变更前快照 oldAgentId，updateById 后调用 notifyAgentHandover；**rework / reworkFresh**：同样快照 + 通知（reworkFresh 换派场景旧执行者收 reassigned，不换派不发）。
- **McpToolService.pullTasks**：sub_task 消息若子任务当前执行者 ≠ 本 agent（含已回收 null）→ 消息带 `reassigned=true` + `currentAgentId`（回收时仅 true，currentAgentId 为 null）。
- **executor SKILL.md**：新增 §1.5.1.bis 收件箱消息类型与撤销语义表（reassigned / unassigned 收到即停止执行）。
- **顺带修复真实 bug**：reworkFresh 人工驳回不换派（reworkAgentId=null）时 `Map.of("reworkAgentId", null)` 抛 NPE——改用 HashMap（此前不换派驳回会 500）。

#### 3. 验证结果

- `mvn -pl helloai-core -am test -DskipTests=false -Dtest=SubTaskServiceHandoverTest,McpToolServiceTest,SubTaskServiceIsReadyTest`：**全部通过**（SubTaskServiceHandoverTest 7 用例：改派双通知 unassigned+assigned / 回收 unassigned / 初始分配不发 / 原地保留不发 / reworkFresh 换派 / reworkFresh 不换派 / rework 换人；McpToolServiceTest 3 用例：已转移打标 / 未转移不打标 / 回收打标 currentAgentId 空；SubTaskServiceIsReadyTest 8 回归）。

#### 4. 影响与遗留

- 影响：无 DB 迁移；行为变化——改派/回收后旧执行者一个轮询周期内（pullTasks 30s）可感知任务已转移；SKILL 同步消息类型语义。
- 遗留：无（验收达成：改派后旧 agent 一个轮询周期内可感知任务已转移）。

### 6.61 MCP 接入体验：Session 生命周期核验 + REST 别名同步通道 + 404 修复提示（A0-2：trae 实战反馈二.1/2/4「Session 复用 / 同步响应 / Schema 与错误信息」）（2026-08-11）

#### 1. 背景与结论

- **实战痛点（trae 两轮实战）**：① MCP session 几十分钟就失效（Session not found），每次调用重新 4 步握手；② `tools/call` 响应只经 SSE 推流、POST 静默，提交成功与否只能查库；③ `tools/list` 无参数 Schema、错误无修复提示。
- **SDK 生命周期核验（反编译 io.modelcontextprotocol 0.18.3 + WebMvcSseServerTransportProvider）**：
  - session 由 `POST /mcp/messages?sessionId=` 入参解析，**严格绑定 SSE 长连接**（WebMvcMcpSessionTransport 持有 sseBuilder，onComplete/onTimeout 后从 sessions map 移除）；断开即回收是协议行为，无「保留窗口」可配置。
  - `handleMessage` 为同步执行（`McpServerSession.handle().block()`）：session==null → 同步返回 **HTTP 404 + body "Session not found: xxx"**（Jackson 序列化的 McpError 对象）；sessionId 缺失 → 400；JSON 解析失败 → 400。
  - **关键发现（exchangeSink 串行化）**：`handleIncomingRequest` 非 initialize 请求必须等待 `exchangeSink.asMono()`（Sinks.One）信号；该信号由 `notifications/initialized` 通知触发（`handleIncomingNotification` 完成 exchangeSink）。**未发 initialized 通知就 tools/call → 永久挂死（HTTP 不返回，实测 20s+ 超时）**——协议 4 步握手缺一不可。
  - **断开回收有延迟窗口**：实测断连后 +2s 旧 session 仍可调用（200），回收并非即时；且断连后第二个请求偶发挂死（exchangeSink 单次发射语义疑点，未完全定性，属 SDK 内部行为）。
- **复用决策**：SDK 不可配置保留窗口 → **不做 transport 层改造**（改造成本高、协议兼容风险大），由**无状态 REST 别名通道 `POST /api/mcp/jsonrpc` 承担免握手复用**；SESSION_AUTH（120min TTL）与 SDK session 生命周期脱节 → **404 时联动 evict**，避免鉴权缓存残留。

#### 2. 实现要点

- **McpAuthFilter 增强（核心）**：
  - `BufferedResponseWrapper`：缓冲 SDK RouterFunction 直写 body（setContentLength/flushBuffer 改 no-op 防提前 commit），doFilter 返回后 `flushToUnderlying()` 写回底层 response——真实 Tomcat 验证无损。
  - `afterMessageHandled`：SDK 返回 404 时① `McpAuthContext.evict(sessionId)` 联动清理 SESSION_AUTH；② body 含 "Session not found" 时 JSON 解析附 `fixHint`（「重新 GET /mcp/sse 握手拿新 sessionId；或改用无状态 REST 别名 POST /api/mcp/jsonrpc」）。
- **REST 别名同步通道**：`McpController.postJsonrpc`（@Deprecated 但承载 A0-2）——无状态（agentId 取自 request attribute _authId，无需 MCP session）、同步返回完整 `{"jsonrpc":"2.0","result":{...},"id":...}`（tools/call 直接返回工具结果而非空 body）；10 工具矩阵与 MCP 通道完全对齐（checkIn/checkOut/getAgentStatus 全部可用）。
- **McpToolService.getAgentStatus 业务下沉**：REST 别名与 MCP 通道共用（McpMcpServer 改为委托）。
- **tools/list Schema 确认**：spring-ai 按 `@ToolParam` 声明自动生成 JSON Schema（properties + type），无需补——verify 脚本逐工具断言 inputSchema 非空通过。
- **executor SKILL.md §1.4**：四步握手避坑强化（缺 initialized 直接 tools/call 会挂死）+ Session 失效双修复路径（重握手 / REST 别名兜底）+ REST 别名通道调用示例。

#### 3. 验证结果

- 单测：`McpAuthFilterTest` 6 用例（404 evict + fixHint 改写 + 非 404 不改写 + 401 路径等）、`McpControllerJsonrpcTest` 8 用例（tools/list 10 工具 + Schema / submitResult 同步回执 / checkIn 租约 / heartbeat / -32601 / -32000 等）**全绿**。
- 真实环境：`POST /mcp/messages?sessionId=no-such-xxx` → **HTTP 404 + body 含 fixHint + 日志 SESSION_AUTH 联动清理**（wrapper 在 Tomcat 下无损）。
- **verify-mcp-session-e2e.ps1（PASS=17 FAIL=0）**：S1 SSE 握手 → S2a initialize → S2b notifications/initialized → S2 tools/call heartbeat（POST 200 + SSE 推流 isError:false）→ S3/S4 未知 sessionId 404 + fixHint（含 /mcp/sse 与 /api/mcp/jsonrpc 指引）→ S5 REST tools/list 10 工具 + inputSchema → S6 REST heartbeat 同步 result → S7 checkIn/checkOut 同步租约回执（leaseId + expiresAt）；断连后复用旧 session 为观察项（SDK 保留窗口内 200，不做硬断言）。

#### 4. 影响与遗留

- 影响：MCP SSE 通道行为不变（协议标准）；REST 别名通道新增免握手同步能力；404 响应体附 fixHint 且 SESSION_AUTH 联动清理（无 DB 迁移）。
- 遗留：① SDK 断连回收延迟窗口（保留窗口内旧 session 仍可调用）与断连后第二请求挂死为 SDK 内部行为，未处理（外部 agent 遇 404 即切 REST 别名，无需感知）；② 若未来仍需「同一 session 跨连接复用」，需自定义 McpServerTransport 或升级 spring-ai 版本，留作专门迭代；③ a02 验收达成：外部 agent 一次 REST 调用即可免握手复用，提交有同步回执（accepted/resultId/status），错误信息可操作（fixHint 指引）。

### 6.62 工具面统一 + SKILL 逐动作速查表 + 405 语义修复（A0-3：三通道 10 工具对齐 / SKILL 双通道分工表 / verify-tool-matrix 防漂移 / MethodNotSupported→405）（2026-08-11~12）

#### 1. 背景与结论

- **盘点结论（A0-3-1）**：MCP SSE 通道 10 工具 ✓、REST 别名 `POST /api/mcp/jsonrpc` 10 工具 ✓（A0-2 补齐）、**REST 直通 `/api/mcp/tools/*` 声明 10 个但只有 7 个实现**（getAgentStatus / checkIn / checkOut 缺失，调 `/api/mcp/tools/checkIn` 会 404）——`GET /api/mcp/tools` 列表与真实可调路由不一致，外部 agent 按声明调用会踩空。
- **SKILL.md 漂移盘点**：3 处错误路径（下线剧本 `/api/agents/<id>`、注意事项 `/api/rules/merged`、错误码表 404/Unknown tool 行过时）；全文无「动作 → 方法 + 路径 + 请求体 + 返回结构」的机器可解析速查表，agent 需在多章节间拼凑调用姿势。
- **统一策略决策**：不补 MCP 工具（startById 等维持 REST 业务端点语义，避免 MCP 工具面膨胀）→ ① 补 REST 直通 3 个缺失端点（三通道 10 工具完全对齐，listTools 不再撒谎）；② SKILL 新增「〇、工具与动作速查总表」（0.1 三通道执行工具表 + 0.2 REST 业务端点表，机器可解析）；③ `verify-tool-matrix.ps1` 校验脚本做声明 vs 文档 diff（防漂移）。

#### 2. 实现要点

- **McpController**：新增 `TOOL_NAMES` 常量（10 工具唯一事实源，防声明与实现漂移）+ 3 个直通端点 `POST /api/mcp/tools/getAgentStatus`（无 body）/ `checkIn`（可选 workMode / maxConcurrent / ttlMinutes）/ `checkOut`（closeReason 兼容 reason 回退），全部委托 McpToolService。
- **executor SKILL.md**：新增「## 〇、工具与动作速查总表（A0-3 新增，机器可解析）」——0.1 三通道执行工具表（列：工具 | MCP SSE | REST 别名 jsonrpc | REST 直通 | 请求体 JSON | 返回要点）+ 0.2 REST 业务端点表 13 条（动作 | 方法+路径 | 参数 | 返回要点）；L31 三通道表述修正；第三节 REST 参考逐动作 curl 重写（含「startById 必须 POST，GET 会 405」「submitById 无 body 不带产出，产出走 submitResult」等避坑）；下线剧本 2 处 `/api/agents/<id>` → `/api/agents/getById/<id>`；注意事项 `/api/rules/merged` → `/api/rules/getMergedRules`；错误码表更新（404 Session not found + fixHint / 404 旧路径 / 405 startById / 500 Unknown tool 三通道 10 工具）。
- **GlobalExceptionHandler（顺带修复真实缺陷）**：补 `HttpRequestMethodNotSupportedException` → **HTTP 405**（此前被 Exception 兜底成 500——GET 打 POST-only 路由返回 500「服务内部错误」，与 SKILL 错误码表「405 startById」表述不一致；修复与 NoResourceFoundException→404 同模式）。
- **verify-tool-matrix.ps1（A0-3-3）**：S1 REST 别名 tools/list 10 工具 + 逐工具 inputSchema(type=object)；S2 `GET /api/mcp/tools` 与 S1 集合 Compare-Object diff 空；S3 直通 getAgentStatus 探活；S4 SKILL 0.1 表工具名（区域限定 `### 0.1`~`### 0.2` 排除 1.2 表 + 错误码表）与服务器 tools/list diff 空；S5 SKILL 0.2 表 13 条路径探活（GET 期望 200；POST-only 路由 GET 探期望 405 = 路由存在）；S6 SKILL 旧路径检查（豁免错误码表教学区——旧路径作为「错误示例」刻意保留）；S7 直通 checkIn→checkOut 真实调用（同步租约回执）。
  - 正则落地避坑（PS 5.1）：`(?m)` 行首锚点（Get-Content -Raw 多行串 `^` 默认不匹配行首）；正则与断言字符串**禁用非 ASCII**（✓ 等字符经 GBK 解析 .ps1 会破坏正则字面量）；区域锚点用纯 ASCII（`### 0.1` / `\n## `——注意 `## ` 会匹配 `### 0.2` 标题自身字符 2-4，必须带换行前缀）。
- **测试修复（V47 遗留，与 A0-3 无关但顺带闭环）**：全量测试发现 5 个失败（ResilientDispatcherTest 4 + ResilientDispatcherAopIntegrationTest 1）——V47 将 `AgentSelector.pickAlternative` 增加 3 参（constraints 贯穿 fallback）且主代码已切 3 参调用，但 6 处测试 stub/verify 仍 mock 2 参 → Mockito stub 失效返回 null → 抛「无可用替代 Agent」。已全部补第 3 参 `any()`。

#### 3. 验证结果

- 单测：`McpControllerJsonrpcTest` 12 用例全绿（8 原有 JSON-RPC + 4 新增直通：listTools 10 工具断言 / getAgentStatus 委托 / checkIn 租约 workMode+leaseId / checkOut closeReason 回退 + closedCount）；全量 `mvn -pl helloai-api -am test -DskipTests=false`：**Tests run: 486（474 core + 12 api），Failures: 0, Errors: 0**。
- 真实环境：**verify-tool-matrix.ps1 PASS=23 FAIL=0 ALL PASSED**（S1~S7 全绿：三通道 10 工具同名集合、SKILL 0.1 表 diff 空、SKILL 0.2 表 13 条路由全部存在、旧路径 0 残留、checkIn/checkOut 同步回执）。
- 405 修复实证：GET `/api/sub-tasks/startById/1` 由修复前 HTTP 500「服务内部错误」→ 修复后 HTTP 405（body code=405「请求方法不支持」），与 SKILL 错误码表表述一致。

#### 4. 影响与遗留

- 影响：REST 直通补齐 3 端点（三通道 10 工具完全对齐）；405 语义修复影响所有「路径存在但方法不支持」请求（此前 500，属正确性修复）；SKILL §0.1/§0.2 速查表成为外部 agent 的唯一动作依据（验收：只读 SKILL 即可零试错调用）。
- 遗留：无（a03 验收达成：外部 agent 只读 SKILL §0.1/§0.2 即可正确调用，零试错；校验脚本已入库可重复执行防漂移）。

### 6.63 外部 Agent 信息获取能力补齐：getDepsSummary 主动拉依赖摘要 + review 反馈通知 + 未读/已读状态位（A0-4）（2026-08-12）

#### 1. 背景与结论

- **盘点结论（A0-4-1）**：① 外部 agent 无法主动拉前置产出摘要——依赖摘要只在执行链 `buildDependencySection` 内部消费，agent 侧无工具可取；② 评分反馈缺口——`rework()`/`complete()` 不产生收件箱通知，驳回/通过只能另查 review 接口；③ `pullTasks` 不区分未读/已读，ack 语义对轮询 agent 不透明，轮询逻辑需自行过滤。
- **落地决策**：① 新增 MCP 工具 `getDepsSummary`（复用 buildDependencySection 摘要逻辑，数据口径与执行链同源）；② `rework()`/`reworkFresh()` 统一补发 `sub_task.rejected`、`complete()` 补发 `sub_task.approved`（summary 携带最近一轮 review 评分/评语）；③ `pullTasks` 消息带 `read` 状态位 + `includeRead` 参数（未读优先，已读按 read_time 倒序补齐配额）。
- **过程中发现新缺口**：McpController JSON-RPC 别名通道 `tools/list` 是**独立硬编码声明**（10 个），与 `TOOL_NAMES` 漂移——A0-4 同步补齐 `getDepsSummary` 声明 + `pullTasks.includeRead` 参数 + dispatch 分支，三通道（MCP SSE / REST 别名 / REST 直通）真正 11 工具对齐。

#### 2. 实现要点

- **McpToolService.getDepsSummary(agentId, subTaskId)**：`dependsOnIdList → listByIds → taskRunningSpecService.findRecord(taskId, depId).summary() → loadUpstreamContent`（物化附件 local:// 优先，回退 `SubTaskOutputExtractor.extractExecutionOutput`）；`DEP_CONTENT_MAX_CHARS=4000` 截断 + `truncated` 标记；收集失败降级 `degraded=true` 不阻断返回。
- **pullTasks 4 参重载 + includeRead**：未读优先，已读按 read_time 倒序补齐配额；Message 带 `read` 状态位（false=未读待 ack，true=已 ack）；`AgentInboxService.getRecentRead`（is_read=1 & is_archived=0，orderByDesc read_time，LIMIT min(limit,500)）。
- **SubTaskService 通知补发**：`rework()`/`reworkFresh()` 补发 `sub_task.rejected`（`buildReworkSummary` 从 `context.reviewHistory` 最新轮提取 score/comment/issues，无历史回退「请查审查记录了解具体问题」）；`complete()` 补发 `sub_task.approved`（`buildApprovedSummary` 从 review_record 按 round desc LIMIT 1 取 score/comment）。
- **三通道同步**：`McpMcpServer` @Tool `getDepsSummary` + `pullTasks.includeRead`（MCP SSE）；`McpController` `TOOL_NAMES` 11 + JSON-RPC tools/list 11 + dispatch `getDepsSummary` case / pullTasks 4 参（REST 别名）；REST 直通 `/api/mcp/tools/getDepsSummary` + pullTasks includeRead（直通上一轮已补，本轮保持对齐）；`AgentMcpServerService.DEFAULT_EXECUTOR_TOOLS` 11（新工具默认启用，isToolEnabled 自动建行）。
- **executor SKILL.md**：0.1 表 11 工具 + `getDepsSummary` 行（请求/返回结构）+ pullTasks 行补 `includeRead`/`read`/`summary` 要点 + §1.2 后新增「🧭 ack 语义（A0-4 澄清）」块；`verify-tool-matrix.ps1` S1/S2 断言 10→11。

#### 3. 验证结果

- 单测：全量 `mvn -pl helloai-api -am test -DskipTests=false` **Tests run: 503（core 487 + api 16），Failures: 0, Errors: 0**（McpToolServiceTest 6 新用例：默认未读 / includeRead 合并 / 无依赖 / 摘要+内容加载 / 4000 截断 / 降级与回退；SubTaskServiceHandoverTest 4 新用例：rejected 补发 / 回退文案 / reviewHistory 摘要提取 / approved 补发；AgentInboxServiceTest 2 用例：倒序返回 / limit 500；McpControllerJsonrpcTest 4 新用例：includeRead 透传与缺省 / getDepsSummary 委托与缺参 + 工具数断言 10→11）。
- 真实环境 `verify-tool-matrix.ps1` **PASS=23 FAIL=0 ALL PASSED**（S1/S2 11 工具同名集合、S4 SKILL 0.1 表 diff 空，含 getDepsSummary）。
- **getDepsSummary 直通 + JSON-RPC 别名**：子任务 2087076796930322438（3 依赖）返回 `depCount=3 loadedCount=3 truncatedCount=0 degraded=false`，每依赖带 title/status/summary/content（完整执行摘要 + 内容本体）；缺 subTaskId → R.fail「subTaskId 不能为空」。
- **pullTasks 未读/已读**：默认只回未读（read=false）；ack 一条后 `includeRead=true` 返回未读 4 + 已读 1（read=true）——未读优先、已读倒序补齐，REST 直通与 JSON-RPC 别名结果一致。
- **完整 review 闭环（真实链路）**：新建测试任务→子任务（assigned A03-test-executor）→start→submit→人工驳回 REJECTED（score=2）→ pullTasks 拉到 `sub_task.rejected`（read=false，summary 回退文案「请查审查记录了解具体问题」——人工驳回无 reviewHistory，符合预期）→ rework 循环 start→submit→APPROVED（score=5）→ pullTasks 拉到 `sub_task.approved`（read=false，**summary=「审查通过，评分 5/5；评语: A0-4 e2e approve verification」**）。
- 测试数据已清理（task/sub_task/review_record/agent_inbox/reward_log + agent.score 回滚）。

#### 4. 影响与遗留

- 影响：无 DB 迁移；工具面 10→11（三通道对齐）；收件箱消息新增 `read` 状态位（历史消息按未读处理）；新增 rejected/approved 两种通知类型（summary 携带评分反馈）；REST 别名通道 tools/list 与 TOOL_NAMES 重新对齐（消除声明漂移）。
- 遗留：① MCP SSE 通道未做实连验证（@Tool 签名与单测覆盖，三通道共用 McpToolService 同一实现，差异仅在参数绑定）；② 人工驳回（无 reviewHistory）时 rejected 摘要回退默认文案，自动核验链（SubTaskReviewService 写 reviewHistory）才有完整评分摘要；③ a04 验收达成：外部 agent 可主动拉前置产出摘要（getDepsSummary）与评分反馈（rejected/approved summary），轮询无需自行过滤（read 状态位区分未读/已读）。

### 6.64 审核真实性核验：自动核验证据硬检查 + 物化附件清单注入（A0-5：trae 实战反馈一.1「审核真实性」）（2026-08-12）

#### 1. 背景与结论

- **盘点结论（A0-5）**：AUTO_REVIEW 只比对子任务文字描述（如「文件 203 行 errors=0」），不验证产出是否真实存在——编造证据也能通过初筛（trae 1923 案例：声称写了脚本但无实际文件）；且 LLM 核验 prompt 不含真实附件信息，无法核对「声称的交付物 ↔ 实际物化产物」。
- **落地决策**：① `reviewSubTask` 在能力预检之后插入**服务端证据硬检查**——复用 §6.30 ArtifactStorage 物化链（`ExecutionArtifactService` 产出物化 + `AttachmentService` 注册），无产出支撑直接跳过自动核验并打人工介入，不再调 LLM 初筛；② `subtask-review.md` prompt 注入**物化附件清单**，LLM 按清单逐项核对声称交付物，文件类交付物无对应附件即使文字声称「203 行 errors=0」也判 pass=false；③ 与 §6.56 能力预检衔接：预检在前拦「无本机能力的提交者」，证据检查在后拦「有能力但编造产出的提交」。
- **边界**：仅「空产出（无 output 且无附件）」与「执行密集 + 无可读附件」两类被硬拦；非执行密集任务有 output 文字产出即视为产出支撑放行（避免误伤文档类任务）。

#### 2. 实现要点

- **`AgentDispatchProperties.reviewEvidenceCheckWaitMs`（默认 1000ms）**：物化与核验竞态补偿——产出物化在结果回报事务 afterCommit 同步执行，自动核验在 AFTER_COMMIT 异步线程启动，两者存在毫秒级竞态；执行密集任务证据检查未发现可读附件时先等待本窗口再重查一次，避免物化未完成被误判为无证据。0 表示不等待（测试/联调可关闭）。
- **`SubTaskReviewService.checkEvidence(subTask)`**：`attachmentService.list` + `isContentLoadable` 过滤出平台可读附件（local:// 物化产物；minio:// 等外部存储不可直读，不算证据）；产出文本取 `SubTaskOutputExtractor.extractExecutionOutput`。拦截原因两类：`no_output_no_attachment`（无产出文本且无附件）、`execution_dense_no_attachment`（执行密集 + 无可读附件，仅文字描述）。
- **拦截动作**：`taskTimelineService.recordEvent(sub_task_review_skip_no_evidence)`（payload：reason/submitterAgentId/attachmentCount/outputPresent）+ `subTaskService.markManualIntervention(subTaskId, "review_skip_no_evidence", ...)`，子任务停留 REVIEW 等人工介入面板处理，不进入 LLM 初筛。
- **附件清单注入**：`buildAttachmentList(subTask)` 逐行生成 `- fileName（type, size bytes, 平台可直读/外部存储（平台不可直读））`，空则「（无物化附件）」；`renderPrompt` 注入 `{{ATTACHMENT_LIST}}`；`subtask-review.md` 新增「## 物化附件清单」章节 + 核验要求第 9 条（声称交付物与附件清单对应；文件类交付物无附件即使声称「203 行 errors=0」也判 pass=false；外部存储标注不可作为可验证证据）。
- **§6.56 衔接**：能力预检（`isExecutionDense` + `hasLocalExecutionCapability`，跳过时 `review_skip_execution_dense_no_capability`）仍在证据检查之前，两者各自独立记录 timeline 与人工介入，互不覆盖。

#### 3. 验证结果

- 单测：`SubTaskReviewServiceTest` **20/20 全绿**（新增 4 用例：① 无 output 无附件 → skip + `markManualIntervention(review_skip_no_evidence, reason=no_output_no_attachment)` + timeline；② 执行密集仅文字描述 + external 附件不可读 → skip（execution_dense_no_attachment）；③ 执行密集无附件 + waitMs=5 重查路径（`attachmentService.list` 调用 2 次）；④ prompt 注入断言（`AgentTask.userPrompt` 含「## 物化附件清单」/「平台可直读」/「声称的交付物必须与**物化附件清单**对应」）；存量 16 用例全部适配（helper 默认携带 output，执行密集用例补附件 mock））。
- 全量回归：`mvn -pl helloai-api -am test -DskipTests=false` **Tests run: 507（core 491 + api 16），Failures: 0, Errors: 0**。
- 真实环境（后端重启加载新代码，`verify-a05.ps1`）：
  - **S1 空产出拦截**：普通任务子任务 → claim/start → `submitResult`（success=true，**无 output 无附件**的编造提交）→ 6s 后子任务停留 **REVIEW**（未自动 DONE）；timeline 出现 `sub_task_review_skip_no_evidence`（payload: reason=no_output_no_attachment, outputPresent=false, attachmentCount=0）；`context.manualIntervention` 落库（reason=no_output_no_attachment, submitterAgentId, ts）；后端日志 `自动核验跳过：无产出证据支撑` + `人工介入标记写入: reason=review_skip_no_evidence`。
  - **S2 有附件通过**：`uploadArtifact` 注册 `local://helloai-local/1/api-docs.md` → `submitResult`（success=true + output）→ 10s 后**无** `sub_task_review_skip_no_evidence`（证据检查放行），LLM 正常核验出 `sub_task_auto_review_rejected`（reviewer 判定不达标 → REWORK）——证据硬检查无误伤。
  - 验证数据为独立测试任务（S1/S2 各一），验证后已按 taskId 精准清理（task/sub_task/task_timeline/attachment/review_record/agent_inbox/conversation_message 共 18 行），与 A0-4 先例一致。

#### 4. 影响与遗留

- 影响：无 DB 迁移；新增配置 `reviewEvidenceCheckWaitMs`（默认 1000ms）；自动核验链新增证据硬检查（空产出/执行密集无附件两类拦截，均转人工介入）；`subtask-review.md` prompt 结构变化（新增附件清单章节与核验要求第 9 条）；自动核验不再对无证据提交调 LLM，减少无效调用成本。
- 遗留：① 非执行密集任务「有 output 无附件」仍放行（output 文字视为产出支撑），若需严格化可按 deliverable 类型（脚本/文件类）加强为必须附件；② `minio://` 外部存储附件平台不可直读，即使真实存在也被当作无证据（`isContentLoadable=false`），靠人工介入兜底；③ a05 验收达成：无附件支撑的编造提交不再自动通过初筛（S1 实测拦截），有物化附件/产出支撑的提交正常进入 LLM 核验（S2 实测放行）。

### 6.65 值班/心跳语义对称：三工具返回体语义完整 + Agent 可自检续约（A0-6：trae 实战反馈二.5/6）（2026-08-12）

#### 1. 背景与结论

- **盘点结论（A0-6）**：① `checkIn` 已返回 `leaseId`/`sessionId`/`workMode`/`maxConcurrent`/`expiresAt`（子任务 1 已满足，仅缺单测锁定）；② `checkOut` 对「无 ACTIVE 租约」只回 `closedCount=0`，无法区分「已过期无需签退」与「从未打卡」，Agent 无法自检；③ `heartbeat` 只回 `serverTime`，不暴露租约剩余时间，「续约是否生效/还剩多久」不可见；④ SKILL.md 未说明租约 `session_id` 与 MCP transport session 的映射关系，断连重连后 Agent 无法判断租约是否仍有效。
- **落地决策**：① `heartbeat` 增强为返回 `onDuty`/`leaseId`/`leaseExpiresAt`/`remainingTtlSeconds`（有 ACTIVE 租约时计算 `Duration.between(now, expireTime)` 剩余秒数，无租约返回 `onDuty=false, remainingTtlSeconds=0`）——每次心跳即一次租约自检，Agent 据此在到期前自行重做 `checkIn` 续约；② `checkOut` 增强为幂等返回当前状态：`closeLease` 后经新增的 `getLatestLease(agentId)` 取最近一条租约，`currentStatus` = `CLOSED`（刚签退）/ `EXPIRED`（已过期无需签退）/ `NONE`（从未打卡），并附带 `latestLeaseId`/`latestLeaseExpiresAt`/`latestLeaseCloseReason`；③ SKILL.md 澄清租约 `session_id`（平台签发、标识租约）与 MCP transport session（SSE 长连接）相互独立，断连不失效租约，重连后用 `getAgentStatus`/`heartbeat` 自检。
- **边界**：不改租约生命周期本身（仍为一次性签发、到点 EXPIRED、续约=先 checkOut 再 checkIn）；不引入自动续约；`renewLease()` 保持无调用方（仅作为能力预埋）。

#### 2. 实现要点

- **`McpToolService.heartbeat()` 增强**：`heartbeatService.seen` 刷在线态不变；追加 `getActiveLease(agentId)` 查询 ACTIVE 租约，命中时返回 `onDuty=true` + `leaseId` + `leaseExpiresAt`（ISO8601）+ `remainingTtlSeconds`（`Duration.between` 计算，≤0 归 0），未命中返回 `onDuty=false` + `remainingTtlSeconds=0`（`leaseId`/`leaseExpiresAt` 为 null）。
- **`McpToolService.checkOut()` 增强**：`closeLease` 语义不变；追加 `agentDutyLeaseService.getLatestLease(agentId)`（按 `start_time` 倒序取最近一条，`AgentDutyLeaseService` 新增方法）填充 `currentStatus`/`latestLeaseId`/`latestLeaseExpiresAt`/`latestLeaseCloseReason`，无任何租约时 `currentStatus="NONE"`。三态自检语义：CLOSED=刚签退成功 / EXPIRED=租约早已到期无需再签（closedCount=0 的原因可解释）/ NONE=从未打卡。
- **Result 类扩展**：`HeartbeatResult` 新增 `onDuty`/`leaseId`/`leaseExpiresAt`/`remainingTtlSeconds`；`CheckOutResult` 新增 `currentStatus`/`latestLeaseId`/`latestLeaseExpiresAt`/`latestLeaseCloseReason`（均 `@lombok.Data` 自动生成访问器）。三通道（MCP SSE / REST 别名 jsonrpc / REST 直通）共用同一 `McpToolService`，一处改动三通道一致。
- **SKILL.md 文档**：§0.1 总表更新 `checkOut`/`heartbeat` 返回要点；§1.2 租约机制块新增三条 A0-6 澄清——租约 `session_id` 与 MCP transport session 相互独立（SSE 断连不失效租约，重连后自检再决定续约或重新 checkIn）、心跳可自检续约（`remainingTtlSeconds` 到期前 1 分钟重做 checkIn）、checkOut 幂等三态自检。

#### 3. 验证结果

- 单测：`McpToolServiceTest` **17/17 全绿**（新增 6 用例：① checkIn 基线——`leaseId`/`sessionId`/`workMode`/`maxConcurrent`/`expiresAt` 同步返回；② checkOut 正常签退 → `currentStatus=CLOSED` + 租约事实；③ checkOut 幂等（租约 EXPIRED）→ `currentStatus=EXPIRED` + `latestLeaseCloseReason=lease_expired`；④ checkOut 幂等（从未打卡）→ `currentStatus=NONE`；⑤ heartbeat 持有 ACTIVE → `onDuty=true` + `remainingTtlSeconds` ∈ (540,600]；⑥ heartbeat 无 ACTIVE → `onDuty=false` + `remainingTtlSeconds=0`）。
- 全量回归：`mvn -pl helloai-api -am test -DskipTests=false` **Tests run: 513（core 497 + api 16），Failures: 0, Errors: 0**。
- 真实环境（后端重启加载新代码 PID 33660，`verify-a06.ps1` 六场景全过）：
  - **S1**：`checkIn`（ttlMinutes=1）返回 `leaseId`/`sessionId`（UUID）/`workMode=AUTO`/`maxConcurrent=3`/`expiresAt` ISO8601。
  - **S2**：`heartbeat` 返回 `onDuty=true`、`leaseId` 与 checkIn 一致、`remainingTtlSeconds=59`（1 分钟 TTL 实测剩余）。
  - **S3**：`checkOut`（reason=verify-a06）→ `closedCount=1`、`currentStatus=CLOSED`、`latestLeaseCloseReason=verify-a06`。
  - **S4**：重复 `checkOut` → `closedCount=0`、`currentStatus=CLOSED`（幂等，最近租约仍为已关闭）。
  - **S5**：psql 将租约翻 `EXPIRED` 后 `checkOut` → `closedCount=0`、`currentStatus=EXPIRED`、`latestLeaseCloseReason=lease_expired`。
  - **S6**：从未打卡的 `inner-loop-executor` 调 `checkOut` → `closedCount=0`、`currentStatus=NONE`、`latestLeaseId=null`。
  - 验证产生的 2 条测试租约（close_reason=verify-a06 / lease_expired）验证后已精准删除。

#### 4. 影响与遗留

- 影响：无 DB 迁移、无配置新增；三工具返回体向后兼容扩展（仅新增字段，不改既有字段语义）；`AgentDutyLeaseService` 新增只读方法 `getLatestLease`；SKILL.md 为 Agent 补充租约自检指引（心跳 TTL + checkOut 三态 + sessionId 映射澄清）。
- 遗留：① `renewLease()` 仍无调用方——当前续约范式是「先 checkOut 再 checkIn」（DB 唯一索引约束），若未来需要原位续约可在 heartbeat 侧接入 `renewLease` 并同步扩展返回体；② 租约 `expiresAt` 在 checkIn（新对象，+08:00 表示）与 heartbeat/checkOut（DB 读回，UTC 表示）的时区表示不同，语义一致均为同一时刻，客户端按 ISO8601 解析不受影响；③ a06 验收达成：三工具返回体语义完整（checkIn 租约信息 / checkOut 幂等三态 / heartbeat 剩余 TTL），Agent 可凭 heartbeat 的 `remainingTtlSeconds` 自检续约，断连重连后用 `getAgentStatus`/`heartbeat` 自检租约有效性。

### 6.66 时区与 SLA：deadline 全链路下发 + ISO8601 带时区说明（A0-7：反馈一.6，低）（2026-08-12）

#### 1. 背景与结论

- **盘点结论（A0-7）**：① 全链路实体/DTO 均为 `OffsetDateTime`（无 `LocalDateTime`），Jackson 默认序列化 ISO8601 带 offset——技术面已满足「统一带时区」，真实痛点是文档缺失 + PostgreSQL timestamptz 读回 UTC（`Z`）与新建对象本地偏移（`+08:00`）的双字面表示，外部 Agent 按字符串字面比较会误判（§6.65 遗留②即此问题）；② `sub_task.deadline` 列自 V1 起存在但 `setDeadline()` 零调用——恒为 null，外部 Agent 无法感知任务时限，`ImplicitScoreCalculator` 只能走 `max(actualMs*2, 60000)` 兜底。
- **落地决策**：① 子任务 1 不做全局转换——ISO8601 带 offset 本身无歧义，采用「文档明示 + 单测锁定格式」（SKILL.md §0.3 时间与 SLA 语义：`Z` 与 `±HH:MM` 等价按绝对时刻解析、服务器时区 Asia/Shanghai）；② 子任务 2 落地 SLA 链路：任务创建可填 `slaMinutes`（V48 新列 `task.sla_minutes`）→ confirmPlan 按 **确认时刻 + slaMinutes** 下发各子任务 `deadline` → pullTasks 已有透传（补格式断言锁定）。
- **边界**：deadline 从「计划确认时刻」起算（规划耗时不计入执行 SLA）；`recoverAlreadyConfirmed` 恢复路径不补写 deadline；手工创建子任务路径不动（控制范围）；`slaMinutes` 可空（null=无时限，旧行为完全不变）。

#### 2. 实现要点

- **Flyway V48**：`task` 表新增 `sla_minutes INT`（可空，null=无时限），COMMENT 说明 confirmPlan 下发语义。
- **`TaskService.createTask` 3 参重载**：`createTask(title, description, slaMinutes)` 落 `slaMinutes`；原 2 参委托 3 参（null），既有调用方零改动。
- **`CreateTaskRequest.slaMinutes`**：可选字段，向后兼容；`TaskController.create` 透传。
- **`PlannerAnalysisService.confirmPlan` 下发**：主循环内先 `if (slaMinutes > 0) { draft.setDeadline(now.plusMinutes(slaMinutes)); subTaskService.updateById(draft); }` 再 `changeStatus`——因 `changeStatus` 内部按 id 重查库后全字段 `updateById`，未落库的 deadline 会被覆盖丢失（必须先持久化再转正）。
- **SKILL.md §0.3**：新增「时间与 SLA 语义」块——所有时间字段 ISO8601 带时区偏移、`Z` 与 `±HH:MM` 按绝对时刻解析（DB 读回 `Z` / 新建 `+08:00` 双表示等价）、`deadline` 来源（slaMinutes → confirmPlan 下发）与超时处置（`reportBlocked` 说明原因，不静默拖延）。

#### 3. 验证结果

- 单测：`PlannerAnalysisServiceTest` **16/16 全绿**（新增 confirmPlan deadline 下发用例：`ArgumentCaptor` 断言 `updateById` 的 SubTask 参数 deadline 落在 `[now+59min, now+60min]`、序列化 ISO8601 带 offset、且 changeStatus 照常逐条转正）；`McpToolServiceTest` **18/18 全绿**（新增 pullTasks deadline 透传用例：非 null 时 ISO8601 带 offset 正则匹配，无 deadline 透传 null）。
- 全量回归：`mvn -pl helloai-api -am test -DskipTests=false` **Tests run: 515（core 499 + api 16），Failures: 0, Errors: 0**。
- 真实环境（后端重启加载新代码 PID 17736，V48 自动迁移「now at version v48」成功，`verify-a07.ps1` 六场景全过）：
  - **S1**：`POST /api/tasks` 带 `slaMinutes=60` → 响应 `slaMinutes=60` + `task.sla_minutes=60` 落库；
  - **S2/S3**：造 PLANNING + 2 条 `PENDING_PLAN_REVIEW` 草稿 → confirmPlan 返回子任务 `deadline` 非 null 且 ISO8601 带 offset（实测 `2026-08-12T04:22:24.938325Z` = 确认时刻 11:22+08:00 + 60min，换算正确），DB 全部持久化；
  - **S4**：executor `pullTasks` 消息 `deadline=2026-08-12T04:22:24.938325Z`（ISO8601 带 offset）；DB 字面 `2026-08-12 04:22:24.938325+00` 与 API 字面解析为**同一绝对时刻**——双表示等价实测闭环（`[DateTimeOffset]` 换算断言通过）；
  - **S5**：无 SLA 对照任务 confirmPlan 后 `deadline` 保持 null（=无时限语义）；
  - **S6**：验证数据按 taskId 全引用表链清理（agent_inbox/task_timeline/task_execution_record/task_running_spec/task_iteration/agent_execution_record/review_record/activity_log/attachment/conversation_message/reward_log/sub_task/task 等 15 表，含自动分发产生的 `agent_execution_record` 外键引用，需先删子表再删主表）。

#### 4. 影响与遗留

- 影响：1 个新 DB 列（`task.sla_minutes`，可空，向后兼容）；`createTask` 3 参重载（2 参兼容）；confirmPlan 仅对带 SLA 任务多一次 `updateById` 批量写入（无 SLA 任务零开销）；SKILL.md 新增时间语义说明（外部 Agent 不再误判 deadline）；`ImplicitScoreCalculator` 时间分在 deadline 下发后真实生效（此前恒走兜底分支）。
- 遗留：① 超时后的自动处理（超时重派/告警）不在本项范围，deadline 仅作为感知字段下发，超时处置依赖后续轮次（`reportBlocked` 语义已具备）；② REST 详情端点（`getById` 等）时间字段同为 ISO8601，SKILL.md §0.3 已统一定义；③ a07 验收达成：任务创建可填 SLA，confirmPlan 统一下发子任务 deadline，pullTasks 透传 ISO8601 带时区偏移，外部 Agent 按绝对时刻解析不再误判。

### 6.67 长任务 TTL 自动续租：工具调用即保活（A0-8：反馈四.2，顺带）（2026-08-12）

#### 1. 背景与结论

- **背景（反馈四.2）**：trae-executor 冷启动 10+ 分钟 / 串行验证 5 分钟，任务周期接近租约 TTL（默认 30min）时，无内建续约线程的外部 Agent 会因租约到期被 `DutyLeaseExpirationTask`（30s 周期）翻 EXPIRED 而掉线；旧范式要求 Agent「TTL 到期前主动重做 checkIn」，依赖 Agent 自身纪律。
- **勘察结论**：在线态（`HeartbeatService.seen/active`，刷 Redis TTL + `last_seen_time` + 三态）与**值班租约**（`agent_duty_lease.expire_time`）是两套机制——工具调用此前只刷在线态、不续租约；`AgentDutyLeaseService.renewLease(agentId, ttlMinutes)`（A0-6 预埋：延长 ACTIVE 租约 `expire_time`，无租约返回 null）自预埋起**零调用方**，A0-8 正是其接入点。
- **落地决策**：选 plan 子任务 1（工具调用自动续租）——除 `checkIn`（签发新租约）/`checkOut`（结束租约）外，任一工具调用顺带 `renewLease`；**不做**子任务 2（difficulty 放宽 TTL）——与 E1「动态 TTL 自适应」（差距表 A2 第 2 段，§6.3 为设计参考）重叠，留待其完整设计，避免重复建设；子任务 1 已满足验收「长任务执行期间工具调用即可保活」。
- **边界**：无 ACTIVE 租约时不自动打卡（保持 checkIn 的打卡语义）；续租窗口沿用租约原 TTL（`start_time→expire_time` 推算），异常兜底 30min，上限 7 天防异常大 TTL；续租失败仅告警不阻断工具调用（顺带动作）。

#### 2. 实现要点

- **`McpToolService.refreshDutyLease(agentId)` 私有 helper**：`getActiveLease` 判空 → 推算原 TTL → `renewLease`；整体 try-catch（续租失败 log.warn，不影响主操作）。
- **9 个工具方法接入**（assert 鉴权后首行）：`pullTasks` / `ack` / `claimSubTask` / `heartbeat` / `uploadArtifact` / `submitResult` / `reportBlocked` / `getAgentStatus` / `getDepsSummary`；`checkIn` / `checkOut` 不接入（前者签发新租约，后者结束租约）。
- **heartbeat 语义微调**：心跳顺带续租后返回 `remainingTtlSeconds` 为**续租后**的剩余 TTL——外部 Agent 只要保持轮询 heartbeat 即可持续在岗；A0-6 的自检语义保留（返回体字段不变，仅数值口径为续租后）。
- **`McpMcpServer` checkIn 工具描述**：新增 A0-8 说明（任一工具调用自动续约，长任务执行期间正常调用工具即可保活，无需周期性重做 checkIn）。

#### 3. 验证结果

- 单测：`McpToolServiceTest` **22/22 全绿**（新增 4 用例：pullTasks 按原 TTL=90min 续租 / heartbeat 续租 60min 且返回续租后 TTL / 无 ACTIVE 租约不调 renewLease / renewLease 抛异常不阻断工具调用）。
- 全量回归：`mvn -pl helloai-core,helloai-api -am test -DskipTests=false` **Tests run: 519（core 503 + api 16），Failures: 0, Errors: 0**。
- 真实环境（后端重启加载新代码 PID 36440，`verify-a08.ps1` 六场景 ALL PASSED）：
  - **S1**：checkIn ttl=1min → ACTIVE 租约，DB `expire_time` E0=12:09:06+08:00；
  - **S2**：sleep 20s 后 pullTasks → DB `expire_time` 推至 12:09:26（=调用时刻+60s，剩余 60s）——续租生效实测；
  - **S3**：heartbeat → `onDuty=true` + `remainingTtlSeconds=59`（续租后剩余）；
  - **S4**：sleep 50s（累计 70s > 原 60s TTL，跨过原过期点 12:09:06）→ heartbeat → `onDuty=true` + `expire_time` 刷新至 12:10:17（≈now+60s）——**跨过期点仍保活，A0-8 验收闭环**；
  - **S5**：checkOut → `closedCount=1` + `currentStatus=CLOSED`；
  - **S6**：清理测试租约行（DELETE 1）。

#### 4. 影响与遗留

- 影响：9 个工具每次调用多 1 次 `getActiveLease` 查询 + 有租约时 1 次 `expire_time` 单行 UPDATE（低频轮询场景可忽略）；SKILL.md 租约机制段重写（「一次性签发 / 不会自动续约 / 需 checkOut 再 checkIn」→「工具调用自动续约」，外部 Agent 无需再手动重做 checkIn）；heartbeat 返回 `remainingTtlSeconds` 口径变为续租后剩余。
- 遗留：① 动态 TTL（按任务在跑/空闲调整，E1）与 difficulty 放宽不做——与 A0-8 自动续租互补但范围独立，留待 E1 完整设计；② 极端高频工具调用会让租约持续延长（活跃即保活，语义与心跳一致，恶意死循环拉取需靠外部机制约束）；③ 租约「逻辑过期但未被扫描翻」窗口内（≤30s）工具调用仍可复活租约——对保活更友好，属预期行为。

### 6.68 SKILL 模板与交付编码规范：EXECUTION_RECORD 字段说明 + 交付编码约定（A0-9：反馈三.2/5，中低）（2026-08-12）

#### 1. 背景与结论

- **背景（反馈三.2/5）**：EXECUTION_RECORD 五块此前无模板无示例（trae 1921/1922 首轮产出为空被驳，1923 二次提交才补全）；交付物编码规范缺失——外部 Agent 交付的 PowerShell 脚本踩「双重 BOM 坑」（文件头 `EF BB BF EF BB BF`），而验收标准要求「UTF-8 声明」，声明与实际字节不符导致解析失败。
- **勘察结论**：SKILL.md §4.4 已有基础模板 + 1 个 Java 示例，但缺「每字段 1 句说明」（仅模板占位符）；交付编码全套约定（规则 6 五子项）只沉淀在 helloai-preflight skill（开发者侧），executor SKILL.md（外部 Agent 侧）完全缺失；`ExecutionRecordParser` 五块解析规则（SUMMARY 必填、列表段须「标题行+换行+`- `列表」、VERIFICATION 必须块尾）与文档模板之间无绑定关系，示例漂移无感知。
- **落地决策**：只改 executor SKILL.md + 解析器单测绑定，**不动 Java 解析逻辑**（解析规则已正确，缺的是文档）；不新增独立文档（避免文档碎片化）。
- **边界**：planner SKILL.md 不同步（EXECUTION_RECORD 是 executor 产出协议，planner 不产出）；Python/JS 等脚本编码约定不在本轮（先覆盖 PowerShell/bash 两个实际交付形态）。

#### 2. 实现要点

- **SKILL.md §4.4 增强**：模板后新增「字段说明」表——5 字段每字段 1 句说明 + 解析约束（SUMMARY 必填缺失即整块解析失败 / 三个列表段须换行 + `- ` 项 / VERIFICATION 必须块尾且其后内容全部视为证据），逐条对齐 `ExecutionRecordParser` 正则实现；新增第 2 个示例（PowerShell 交付场景，与 §4.5 编码约定联动展示）。
- **SKILL.md 新增 §4.5 交付物编码与环境约定（A0-9 新增）**：统一 UTF-8；含中文 `.ps1` 必须 **UTF-8 with BOM**（PS 5.1 按 GBK 解析 no-BOM 文件的中文会抛 `字符串缺少终止符`）；**单 BOM 限制**（二次写 BOM 得 `EF BB BF EF BB BF` 双重 BOM，解析直接失败，交付前十六进制确认文件头）；PowerShell 强制编码头模板（`[Console]::OutputEncoding` + `$OutputEncoding`）；`Parser.ParseFile` 语法自检命令（0 error 才提交）；单引号 + `+` 拼接输出风格（PS 5.1 双引号嵌中文提前闭合字符串坑）；Bash 脚本 `LANG/LC_ALL` 声明 + `bash -n` 自检。
- **原 §4.5 依赖链检查清单顺延为 §4.6**，追加 1 条「交付物编码是否按 §4.5 约定」自检项。
- **`ExecutionRecordParserTest` 新增 2 用例（7/7）**：SKILL.md §4.4 两个官方示例原文（Java + PowerShell）作为解析输入，断言五块字段与文档示例完全一致——**文档示例与解析器行为绑定，示例一旦漂移立即红测**（防再漂移机制，同 A0-3 verify-tool-matrix 的 diff 思想）。

#### 3. 验证结果

- 单测：`ExecutionRecordParserTest` **7/7 全绿**（原 5 + 新 2：Java 示例五块完整解析 / PowerShell 示例五块完整解析）。
- 全量回归：`mvn -pl helloai-core,helloai-api -am test -DskipTests=false` → **Tests run: 521（core 505 + api 16），Failures: 0, Errors: 0**（较 A0-8 的 519 +2）。
- `verify-tool-matrix.ps1` 真实环境 **PASS 23 / FAIL 0，ALL PASSED**（S4 SKILL 0.1 表 == tools/list 11 工具 / S5 0.2 端点 13 路由 / S6 禁用旧路径 / S7 checkIn-checkOut 实测）——SKILL 结构编辑未破坏工具矩阵契约。
- SKILL.md 文件完整性：UTF-8 no-BOM + LF 行尾保持（编辑前后字节级一致），615 行（+59）。

#### 4. 影响与遗留

- 影响：外部 Agent 首轮提交即可读到完整模板 + 逐字段说明 + 两个填充示例 + 交付编码约定与自检命令，预期降低「产出格式不合格」与「编码不符」导致的 REJECTED 轮次（A0-9 验收口径）；SKILL.md §4.4 文档示例已与解析器单测绑定，后续改示例会红测提醒同步。
- 遗留：① 编码约定暂覆盖 PowerShell/bash，Python/JS 等脚本可后续按需补充；② SKILL.md 持续增长（615 行），若外部 Agent 上下文窗口受限可考虑拆「快速开始 + 完整手册」；③ `EXECUTION_RECORD` 示例与解析器绑定仅限 core 单测层，运行期无校验（示例仅文档用途，符合预期）。

### 6.69 任务执行策略前端编辑：TaskFormDialog 执行策略折叠区块 + 创建/编辑全链透传（A1：V47 收尾，优先级最高）（2026-08-12）

#### 1. 背景与结论

- **背景（V47 遗留①）**：V47 已落地 `task.agent_policy`/`task.required_skills`/`agent.skills` 三列与 `TaskAgentPolicy` 工具类、选人链约束（拆解/分发/核验/回退），但任务创建/编辑**前端未暴露 policy 表单**——创建/编辑接口不透传 policy，平台内只能靠 RequirementChat 的 planner 钉住机制（V31）间接指定，executor 白名单/reviewer 指定/回退策略/技能要求全部不可配。
- **勘察结论**：后端缺口——`CreateTaskRequest` 仅 title/description/slaMinutes 三字段，`TaskService.createTask`（三参）/`updateTask`（三参，且不更新 slaMinutes）均不写 policy/requiredSkills；前端缺口——`TaskFormDialog.vue` 是孤儿组件（仓库内无引用），`TaskList.vue` 无新建/编辑入口，`types.Task`/`types.Agent` 缺 V47 字段。
- **落地决策**：DTO/Service/Controller 全链透传（缺则补）；`updateTask` 采用「null 字段不 set（保持现状）+ 空 Map/空列表显式清空」语义（初次实现直接 set 会把实体原值覆盖为 null，单测暴露后改为防御式）；前端复用 `listPlannerOptions`（V31 在班/可选判定）作为 planner 数据源。
- **边界**：不做 LLM 拆解真实链路验证（deepseek 密钥可用性不确定；planner/executor/reviewer 三链的 policy 指定语义已由 V47 既有单测 `PlannerAgentPickerTest`/`AgentSelectorTest` 覆盖）；`slaMinutes` 编辑语义为「null=不更新」，暂无「显式清除 SLA」入口（已知限制）。

#### 2. 实现要点

- **后端透传链**：`CreateTaskRequest` 新增 `agentPolicy`（Map，键结构见 `TaskAgentPolicy`）/`requiredSkills`（List）；`TaskService` 新增 `createTask(title, description, slaMinutes, agentPolicy, requiredSkills)` 五参重载（原三参委托，null=不设置落库走 DB 默认 `{}`/`[]`）与 `updateTask(id, title, description, slaMinutes, agentPolicy, requiredSkills)` 六参（null 不 set、空集合=清空）；`TaskController.create/update` 透传。
- **前端**：`types` 扩展——`Task` 加 `slaMinutes/agentPolicy/requiredSkills`、`Agent` 加 `skills`、新增 `TaskAgentPolicy` 接口；`taskApi` 新增公共载荷类型 `TaskFormPayload`；**TaskFormDialog 重写**——「执行策略（V47，可选）」`el-collapse` 折叠区块：拆解 Planner 下拉（`listPlannerOptions`，selectable=false 置灰）/ 核验 Reviewer 下拉（按角色拉取）/ 执行白名单多选 / 回退策略与任务难度单选（可清空，缺省回落默认）/ 要求技能 `el-tag` 标签输入（回车添加、关闭删除）/ SLA 分钟 `el-input-number`；编辑态回显（`initForm` 从 `task.agentPolicy/requiredSkills/slaMinutes` 填充）；提交时仅组装非空键（全空返回 null）。**TaskList.vue 接入**——header「新建任务」按钮 + 操作列「编辑」（DONE 禁用）。
- **新增单测**：`TaskServiceTest`（core，5 用例）——五参创建 policy/技能/SLA 落库、三参旧入口不设置、空集合显式清空、null 字段保持现状、任务不存在返回 null 不落库。

#### 3. 验证结果

- 单测：`TaskServiceTest` **5/5 全绿**（首跑 2 失败——updateTask 直接 set null 覆盖实体原值，改防御式 null 不 set 后通过，测试先行捕获设计缺陷）。
- 全量回归：`mvn -pl helloai-core,helloai-api -am test -DskipTests=false` → **Tests run: 526（core 510 + api 16），Failures: 0, Errors: 0**（较 A0-9 的 521 +5）。
- 前端：`vue-tsc --noEmit` **exit 0**；`npm run build` 成功（chunk >500kB 警告为既有现象）。
- 真实环境：重新打包启动后端（PID 46888）后 `verify-a1-task-policy.ps1` **7 步全 PASS**——S3 创建带五键 policy + 技能 + SLA 任务回显断言 / S4 getById 回显（DB 落库证明）/ S5 编辑整体替换（planner 保留、fallback/difficulty 更新、executorAgentIds 移除、技能替换）/ S6 空集合清空（policy 回 `{}`、skills 回 `[]`、省略的 sla 保持 120）/ S7 级联删除清理。
- 脚本自修：S6 断言首跑失败——PS 5.1 空 `PSCustomObject` 的 `PSObject.Properties.Count` 返回 `$null` 而非 0（`$null -eq 0` 为 false），改 `@(...).Count` 包装后通过（后端行为本就正确）。

#### 4. 影响与遗留

- 影响：V47 遗留①关闭——任务创建/编辑全链透传 policy 五键 + requiredSkills + SLA，平台内可表单直建「指定拆解 Planner / 执行白名单 / 指定核验 Reviewer / 回退策略 / 难度 / 技能要求」任务；TaskFormDialog 从孤儿组件转为 TaskList 正式入口；`updateTask` 六参的「null=不更新、空集合=清空」语义与前端表单行为（仅组装非空键）一致。
- 遗留：① LLM 真实拆解链验证未做（密钥可用性），planner/executor/reviewer 三链 policy 指定语义依赖 V47 既有单测覆盖，后续有密钥可补 `verify-a1` 扩展步；② slaMinutes 无「显式清除」入口（null=不更新），如需清除需加独立开关；③ 脚本注册的固定名 Agent（a1-policy-*）幂等保留在库，供后续 A1 相关验证复用。

### 6.70 agent.skills 自动推导：注册/管理端保存链路 best-effort 补全（A2：V47 收尾，优先级最高）（2026-08-12）

#### 1. 背景与结论

- **背景（V47 遗留②）**：V47 已落地 `agent.skills`（JSONB[] NOT NULL DEFAULT '[]'）与任务 `required_skills` 的 AND 匹配（`AgentSelectionConstraints.allows()` 的 `skills.containsAll(requiredSkills)`），但 **agent.skills 存在零写入路径**——注册/管理端保存 Agent 均不写 skills（entity 注释「注册时按接入方式声明」是未实现目标态），能力声明全靠手工 DB 维护，技能匹配形同虚设。
- **勘察结论**：`AgentService.register` 不 setSkills（全仓库 0 处 setSkills 调用）；`AgentController.register` 走 Map body + `applyRegistrationExtras`（处理 accessType/specializationSlug/modelType/labels/capabilities，**无 skills**）；管理端 `AgentUpdateRequest` 无 skills → `AdminAgentController.update` → `AgentService.updateAgentDetail` 六参（null 不 set 防御式）；`AgentResponse` 无 skills 字段；`AgentCapability.mergeDefaults`（「默认值+覆盖值」模式）与 `AgentAccessType.defaultCapabilities`（CLI_CLIENT/API_KEY_LLM/WEB_BROWSER 三型）是推导范本。
- **落地决策**：新建 `AgentSkillDeriver` 静态工具类，实现「显式值优先 → 否则 accessType 基础技能 + 名称/描述关键词命中合并」的 best-effort 推导，注册与管理端保存链路接入；**显式手工值/已有技能不被推导覆盖**（幂等复用）。
- **边界**：不做「执行历史产出类型」第三信号源（A2 定义中的推导信号之一，本轮只落地 accessType + 名称/描述关键词两个信号）；不做存量 Agent skills 批量回填（如需可脚本补）。

#### 2. 实现要点

- **`AgentSkillDeriver`（core/agent 新建）**：`derive(accessType, name, description, explicitSkills)`——显式技能 clean 后非空直接返回；否则 `BASE_SKILLS`（CLI_CLIENT→shell / API_KEY_LLM→code-review / WEB_BROWSER→web-search）+ `KEYWORD_SKILLS` 19 组关键词（docker/容器、python、java、sql/数据库、shell/bash/powershell/脚本、cli、web/search/搜索、浏览器、爬虫、review/审查/评审）命中合并，LinkedHashSet 去重保序；`clean(raw)` 统一 trim/过滤空白/去重。
- **注册链路**：`AgentController.applyRegistrationExtras` 新增第 5 步——body 显式传 skills 则 `clean` 落库；否则已有技能为空时 `derive` 推导落库（幂等复用路径因「已有技能非空」天然不被覆盖）。
- **管理端链路**：`AgentUpdateRequest` +skills（List，显式传入整体替换 / null 保持现状）→ `AdminAgentController.update` 透传 → `AgentService.updateAgentDetail` 六参改七参（skills 非 null 时 `AgentSkillDeriver.clean` 后整体替换，null 不 set）。
- **响应补全**：`AgentResponse` +skills 字段，`toResponse` 回填（前端详情/验证脚本可见）。
- **关键缺陷修复（真实环境验证暴露）**：`AgentMapper.xml` 自定义 `insert`/`updateById`（覆盖 BaseMapper 处理 PG JSONB 字段）列清单**写死且未含 V47 新增 skills 列**——无论 entity 怎么 setSkills，UPDATE/INSERT SQL 都不含 skills 列，DB skills 恒为 `[]`。对比实验：Task 走 MP 默认 `updateById`（含 `required_skills` 列，风格 `title=?`）与 Agent（`name = ?` 风格）SQL 来源不同，读 Mapper 源码确认。修复：两处 SQL 补 `skills` 列，用 `PgJsonbTypeHandler` + `COALESCE(#{...skills...}::jsonb, '[]'::jsonb)` 兜底 NOT NULL 约束（register 等路径实体 skills 为 null 时不炸库）。

#### 3. 验证结果

- 单测：`AgentSkillDeriverTest` **11/11 全绿**——显式优先 / 三 accessType 基础技能 / 关键词合并去重（"devbox"+"擅长 Python 脚本与 Docker 容器"→[shell, docker, python]）/ 大小写归一 / 名称描述双扫描同标签去重 / 空显式走推导 / clean trim+空白过滤+去重 / null 防御。
- 全量回归：`mvn -pl helloai-core,helloai-api -am test -DskipTests=false` → **Tests run: 537（core 521 + api 16），Failures: 0, Errors: 0**（较 A1 的 526 +11）。
- 真实环境：重新打包启动后 `verify-a2-skill-derive.ps1` **7 步全 PASS**——S2 CLI_CLIENT 无关键词注册 skills 恰为 [shell]（验收点）/ S3 API_KEY_LLM "Docker 审查专家" → [code-review, docker] / S4 显式 [kubernetes, golang] 恰好 2 项 / S5 幂等复用保持显式技能 / S6 管理端 PUT 整体替换 + 只改 remark 保持 / S7 级联删除清理。
- 调试过程沉淀：skills 不落库根因为 AgentMapper.xml 自定义 SQL 缺列（见实现要点），修复后首跑即全 PASS；调试残留（agent `a2-dbg-cli`、task `a2-skill-dbg-task`）已走级联删除接口清理，库中无 a2 前缀残留。

#### 4. 影响与遗留

- 影响：V47 遗留②关闭——**新注册外部 Agent 自动带基础技能标签，`required_skills` 技能过滤开始有实际效果**；管理端详情编辑可整体替换 skills（null 保持）；AgentResponse 暴露 skills 供前端展示/脚本断言。
- 遗留：① 执行历史产出类型推导（第三信号源）未做，后续如需可按 sub_task 产出物类型反推技能；② 存量 Agent skills 仍为空（`[]`），如需让旧 Agent 参与技能匹配可补一次性回填脚本；③ 技能词典（19 组关键词）为静态 Map，后续可外置配置。

### 6.71 required_skills 技能同义词归一：匹配前归一化，同义词技能互相命中（A3：V47 收尾，优先级最高）（2026-08-12）

#### 1. 背景与结论

- **背景（V47 遗留③衔接）**：V47 的技能 AND 匹配（`AgentSelectionConstraints.allows()` 的 `containsAll`）是**精确字符串全匹配**——任务 `required_skills=["powershell"]` 无法命中声明 `skills=["shell"]` 的 Agent，"shell 脚本"与"powershell"被视为不同技能；A2 解决了技能**声明侧**（注册自动推导），A3 解决**匹配侧**（匹配前归一化）。
- **勘察结论**：技能匹配唯一入口为 `AgentSelector.AgentSelectionConstraints.allows()`（全仓库 `getSkills()` 仅此一处消费）；调用链为 `SubTaskDispatchService.resolveConstraints()`（初始分配 + ASSIGNED 超时重分配）与 `ResilientDispatcher`（熔断降级替代），全部经 `pickPreferred/pickAlternative` → `allows()`；`TaskAgentPolicy` 只解析 policy 键（planner/executor/reviewer/fallback/difficulty），不承担技能判定（计划子任务 2 的"TaskAgentPolicy 技能判定接入"按代码事实校正为 `AgentSelectionConstraints` 接入点）；executor SKILL.md 无技能匹配语义说明（grep 确认），无需同步文档。
- **落地决策**：新建 `SkillNormalizer` 静态工具类（`core/agent`，与 `AgentSkillDeriver` 同域同风格），内置同义词映射，`allows()` 匹配前对双方技能标签归一化（trim + 小写 + 同义词归并）；AND 语义不放松（归一化后仍缺技能照常过滤）。
- **边界**：不做技能词典 DB 表（维持内置静态 Map，A2 遗留③的外置配置化仍留后续）；不做多级层级体系（如"编程语言归脚本类"多级分类）；不改 `AgentSkillDeriver` 推导逻辑（其产出已是规范标签，无需归一）；不做存量数据回填。

#### 2. 实现要点

- **`SkillNormalizer`（core/agent 新建）**：`SYNONYMS` 14 组同义词映射（bash/powershell/脚本/cli→shell、容器→docker、数据库→sql、web/search/搜索/浏览器/爬虫→web-search、review/审查/评审→code-review，与 `AgentSkillDeriver.KEYWORD_SKILLS` 非恒等项语义对齐）；`normalize(String)`——null/空白→null，trim + `toLowerCase(Locale.ROOT)` 后命中同义词表返回规范标签，未命中原样小写返回（自定义技能 kubernetes/golang 保持可精确匹配）；`normalizeAll(List)`——逐项归一 + LinkedHashSet 去重保序；`matches(agentSkills, requiredSkills)`——归一后 `containsAll`，requiredSkills 空/null 视为不约束（与调用方"空=不限定"语义一致）。
- **`AgentSelectionConstraints.allows()` 改造**：`skills.containsAll(requiredSkills)` 替换为 `SkillNormalizer.matches(skills, requiredSkills)`；字段注释与行内注释同步补充归一化语义（"A3：匹配前归一化，powershell/bash 与 shell 互相命中"）。
- **新增单测**：`SkillNormalizerTest`（core/agent，13 用例）——英文/中文同义词归一、大小写与 trim 归一、未命中自定义技能原样、归一幂等、normalizeAll 去重保序与 null/空白防御、matches 同义词交叉命中（powershell↔shell 双向）、中英文混合 AND、缺技能不命中、空约束语义；`AgentSelectorTest.TaskLevelConstraints` 新增 4 用例——requiredSkills=powershell 命中 skills=shell、[bash, 容器] 命中 [shell, docker]（中英文交叉）、Python 命中 python（大小写）、归一化后仍缺技能不命中（AND 不放松）。

#### 3. 验证结果

- 单测：`SkillNormalizerTest` **13/13 全绿**（首跑 1 失败为测试自身 bug——`List.of("  ", null)` 的不可变列表工厂禁止 null 元素，改 `Arrays.asList` 后通过，被测代码零改动）；`AgentSelectorTest` 含新增 4 用例全绿。
- 全量回归：`mvn -pl helloai-core,helloai-api -am test -DskipTests=false` → **Tests run: 554（core 538 + api 16），Failures: 0, Errors: 0**（较 A2 的 537 +17，即 SkillNormalizerTest 13 + AgentSelectorTest 4）。
- A3 验收达成：**同义词技能可命中**（任务要求 powershell/bash/容器/搜索/审查 等可命中声明 shell/docker/web-search/code-review 的 Agent），判定逻辑有单测（13+4 用例锁定）。

#### 4. 影响与遗留

- 影响：`required_skills` 技能过滤从"精确字符串"升级为"规范化字符串"——任务创建侧与 Agent 声明侧只要一方使用同义词即可互相命中，A2 推导的规范标签（shell/code-review/web-search 等）与手工声明的同义写法（powershell/审查/搜索 等）不再互相排斥；自定义技能（kubernetes/golang 等）归一后小写精确匹配，行为不变；AND 语义不放松（缺技能仍过滤，既有 5 个 V47 精确匹配用例全部保持通过，向后兼容）。
- 遗留：① 同义词词典与 A2 关键词表均为静态 Map 且独立维护（内容语义对齐），后续外置配置化时应合并为单一数据源，避免两处漂移；② 层级归一（多级技能分类）未做，当前仅单层同义词归并；③ 未做真实环境 e2e 验证（技能匹配是选人链内部逻辑，`AgentSelectorTest` 4 个真实候选场景用例已等价覆盖，真实分发链路需造数走完整拆解链，留待有密钥时与 A1 扩展步一并验证）。

### 6.72 Agent 编辑弹窗技能编辑：管理端列表回显 skills + 标签增删保存整体替换（A3B：V47 前端缺口补齐）（2026-08-12）

#### 1. 背景与结论

- **背景**：A2 已打通管理端 `PUT /admin/agents/updateById/{id}` 的 skills 整体替换（`AgentUpdateRequest.skills`，null 保持现状）与 `AgentResponse.skills` 回填，但前端消费不全——`AgentEditDialog.vue` 无技能编辑项，且管理端分页列表返回的 `AgentListItemVO` 未映射 skills 字段（前端 `AgentListItem` 类型也没有该字段），编辑弹窗打开时无法回显已有技能；上次合规检查时识别为可选小补齐，用户确认补上。
- **勘察结论**：`agentApi.updateProfile` 实际已指向管理端 `updateById` 端点（仅类型定义未含 skills）；`AdminAgentController.list` 返回 `AgentListItemVO`（与 `AgentResponse` 不同 DTO），映射代码未 setSkills；`AgentListItem` 类型缺 skills 字段；`updateProfile` 全仓库仅 `AgentEditDialog` 一处调用，类型扩展无破坏面。
- **落地决策**：最小闭环三件套——后端 VO 补字段映射（一行）、前端类型补字段（types + api 定义）、编辑弹窗加技能标签编辑（回显/回车添加/标签删除/保存整体替换），交互完全复用任务表单「要求技能」的既有模式（TaskFormDialog 同款 skills-box/skill-tag/skill-input + addSkill/removeSkill）。
- **边界**：不做 AgentDetail 详情页技能展示（列表页可编辑已满足管理诉求）；不动 role 下拉的既有保存语义（`AgentUpdateRequest` 无 role 字段，编辑不生效为既有行为，不在本轮范围）；不改后端 updateAgentDetail 逻辑（A2 已实现整体替换 + null 保持，前端仅消费）。

#### 2. 实现要点

- **后端 `AgentListItemVO`**：新增 `List<String> skills` 字段（带 V47/A2 注释）；`AdminAgentController.list` 映射补 `vo.setSkills(a.getSkills())`。
- **前端类型**：`types/index.ts` `AgentListItem` 补 `skills?: string[]`（注释同 V47/A2）；`api/agent.ts` `updateProfile` 请求类型补 `skills?: string[]`（注释：显式传入整体替换，不传则后端保持现状）。
- **`AgentEditDialog.vue`**：表单加 `skills: string[]` 与 `newSkill` 输入；打开弹窗回显 `Array.isArray(a.skills) ? [...a.skills] : []`；「技能」form-item 置于「描述」之前，el-tag 标签 + 小输入框（回车添加、防重复、可删除），样式与任务表单完全同款；保存 payload 始终传 `skills: form.skills`（回显保证表单值初始等于当前值，用户所见即所得：没动 = 传回原值等效保持，删光 = `[]` 清空技能）。
- **验收脚本**：新建 `scripts/powershell/verify-a3b-agent-edit-skills.ps1`（UTF-8 with BOM + 单引号输出 + ASCII 运行时字面量，规则 6 合规），覆盖列表回显 / 整体替换 / 清空 / null 保持 / 级联清理。

#### 3. 验证结果

- 静态检查：`vue-tsc --noEmit` 无类型错误；`mvn -pl helloai-api -am compile -DskipTests` 编译通过。
- 全量打包：`mvn -pl helloai-start -am -DskipTests package` 成功（71.5MB jar）；`npm run build` 成功（18.57s）。
- 真实环境：重新打包重启（PID 23048，6565 就绪）后 `verify-a3b-agent-edit-skills.ps1` **14/14 全 PASS**——S3 adminList 记录含 skills（kubernetes,golang 原样回显，VO 映射生效）/ S4 PUT 整体替换为 [shell, docker] / S5 传 `[]` 清空 / S6 不传 skills 只改 remark 保持 / S7 级联删除清理。首跑 3 失败为脚本自身字段名写错（`PageResult.records` → 实际为 `list`，与前端类型一致），修正后全绿，被测代码零改动。
- 环境备注：本次重启踩到 start-sb.ps1 两个沙箱环境问题——① 脚本内 `mvn` 在受限环境「拒绝访问」（改为 Node fallback 直接执行 mvn package）；② `Start-Process -FilePath 'java'` 依赖 PATH，受限 PowerShell PATH 无 java（改为 `$env:JAVA_HOME\bin\java.exe` 完整路径启动并写 PID 文件）。

#### 4. 影响与遗留

- 影响：V47 前端缺口补齐——管理端 Agent 列表 → 编辑弹窗现在可查看/增删技能标签并保存，保存走 A2 已就绪的 `updateById` skills 整体替换语义（删光 = 清空，不传 = 保持），与任务表单「要求技能」同交互同视觉；技能声明侧的前端闭环完成（注册自动推导 → 列表回显 → 编辑维护 → required_skills 归一匹配）。
- 遗留：① `AgentDetailVO`（管理端 getById 详情）仍未映射 skills，详情页若要展示技能需再补一行映射（本轮未做）；② AgentEditDialog 的 modelType/specializationSlug 回显仍为空（`AgentListItem` 无这两个字段，保存时传 undefined 后端保持现状，行为安全但编辑态观感不完整），与 role 下拉不生效同属既有缺口，未在本轮扩散；③ 新增验收脚本未纳入 CI，与既有 verify-*.ps1 一致为手动/按需执行。

### 6.73 技能输入交互升级：规范标签多选下拉 + 自定义回车（A3B 用户反馈微调）（2026-08-12）

#### 1. 背景与结论

- **背景**：A3B 交付后用户核验反馈——技能标签手动输入（el-tag + 输入框）体验一般，建议改为可多选的下拉选项；平台技能本质是「规范词表（6+1 个标签）+ 自定义技能（kubernetes/golang 等）」双层语义，不能退化成纯枚举多选。
- **落地决策**：`el-select multiple + filterable + allow-create + default-first-option`——下拉多选规范标签、可搜索过滤、输入不在选项中的词按回车创建自定义标签，两全其美；技能选项抽为共享常量供两处消费端复用。
- **边界**：不改后端（词表仍在后端静态 Map，前端常量与后端注释对齐，遗留的外置配置化同时覆盖两端）；仅改 UI 交互，数据模型（string[] 整体替换语义）不变。

#### 2. 实现要点

- **`src/constants/agentSkills.ts` 新建**：`AGENT_SKILL_OPTIONS` 常量——7 项规范标签（shell/docker/sql/web-search/code-review/python/java，带中文说明 label），注释标明与后端 `AgentSkillDeriver.KEYWORD_SKILLS` / `SkillNormalizer.SYNONYMS` 规范标签对齐。
- **`AgentEditDialog.vue`**：技能区 el-tag+输入框 → el-select multiple 多选下拉；移除 addSkill/removeSkill/newSkill 与 skills-box 样式；回显/保存语义不变（string[] 整体替换，删光 = 清空）。
- **`TaskFormDialog.vue`**：「要求技能」同构改造（同一套词的另一消费端，保持一致交互）；移除手动输入逻辑与样式。
- 保留 `field-hint` 说明文案（AND 语义提示）。

#### 3. 验证结果

- `vue-tsc --noEmit` 无类型错误；`npm run build` 成功（18.69s）。
- 纯前端改动，后端无变更；UI 交互（下拉多选/搜索/自定义回车）留待用户浏览器核验。

#### 4. 影响与遗留

- 影响：技能输入从"自由手填"升级为"规范标签多选 + 自定义兜底"——Agent 技能区与任务要求技能区交互统一，规范标签带中文说明降低填错概率（如 web-search 不再手打成 web_search）；自定义能力保留（回车创建任意标签）。
- 遗留：① 前端选项常量与后端词表为两处独立维护（与 A2 遗留③同源），外置配置化时应前后端统一收口；② 选项仅覆盖当前 7 个规范标签，未来后端词表扩展时前端常量需同步；③ 下拉「选择或输入」模式下，自定义标签的大小写/空白由保存链路 trim 兜底（A2 clean），无额外校验。

### 6.74 移除 executor 专业化下拉与模型选择：specializationSlug 全链路清理（用户拍板）（2026-08-12）

#### 1. 背景与结论

- **背景**：用户核验 A3B 后拍板三点——① executor 角色的「专业化」下拉没有实际用处，专业化 prompt（AGENT_SPECIALIZATION 模板机制）实际未接入任何链路，要求连代码一起全量移除；② 外部 AI agent（CLI 接入）注册后再编辑不需要填模型类型（模型取决于外部 agent 自身正在使用的模型），内部 LLM 注册的模型统一按系统配置（llm_provider > sys_config > yml 的 default-model 三级兜底）决定；③ 技能在新建 Agent 时就能填写（注册表单加技能多选）。
- **勘察结论**：`PromptTemplateService.getBySlug/composeBySlug`（AGENT_SPECIALIZATION 机制）全仓库仅定义无调用，确认为死代码，用户判断正确；specializationSlug 剩余消费点仅注册/编辑表单 UI 与 VO/DTO/Controller 透传；模型缺省链已就绪——`AgentProviderResolver.resolveProvider(agent, fallback)` 在 modelType 为空时回退系统默认 provider，各 Provider Factory 的 defaultModel 有 DB > sys_config > yml 三级兜底，`LlmProviderCatalogService.provisionPlatformCredential` 按解析出的 provider 自动补绑平台密钥，注册表单去掉模型选择后内部 LLM 链路依然闭环。
- **落地决策**：后端删除 specializationSlug 的 DTO/VO/Controller 透传与 composeBySlug/getBySlug 死代码方法；前端删除 AgentEditDialog 模型类型+专业化、AgentList 注册表单专业化下拉+模型 provider 下拉（内部 LLM 不再选 provider，统一走系统默认），注册表单新增技能多选（复用 §6.73 的 AGENT_SKILL_OPTIONS）。
- **边界**：DB 列 specialization_slug 与实体字段保留（历史数据兼容，不做迁移）；prompt_template 的 AGENT_SPECIALIZATION 分类保留（模板管理页通用功能，不扩散）；后端 `/admin/agents/listLlmProviders` 端点保留（对外 API 面不收缩，仅前端消费移除）。

#### 2. 实现要点

- **后端（5 文件）**：`AgentService.registerWithExtras/updateAgentDetail` 去 specializationSlug 参数；`AgentCreateRequest`/`AgentUpdateRequest`/`AgentResponse`/`AgentDetailVO` 删字段；`AdminAgentController`/`AgentController` 删 `setSpecializationSlug` 调用与 `applyRegistrationExtras` 中读取逻辑；`PromptTemplateService` 删 `getBySlug`/`composeBySlug` 死代码方法（compose() 保留，ROLE_TEMPLATE 角色模板链路不受影响）。
- **前端（3 文件）**：`api/agent.ts` 删 `listLlmProviders`（注册表单不再用）；`AgentEditDialog.vue` 删模型类型输入与专业化下拉（form/回显/保存全链路移除，保存 payload 仅 name/remark/skills）；`AgentList.vue` 注册表单删专业化下拉与模型 provider 下拉（含 LlmProviderItem/loadLlmProviders/onAccessTypeChange 相关逻辑），新增技能多选（AGENT_SKILL_OPTIONS 复用，注册即填写，A2 显式技能优先），提交不再传 modelType（内部 LLM 后端按系统默认 provider+default-model 补绑）。
- **验收脚本**：新建 `scripts/powershell/verify-674-remove-specialization.ps1`（UTF-8 with BOM + 单引号输出，规则 6 合规），覆盖响应契约无 specializationSlug 字段 / 注册带 skills / 编辑不带 modelType / 内部 LLM 注册缺省 modelType 为 null / 级联清理。

#### 3. 验证结果

- 静态检查：`mvn -pl helloai-api -am compile -DskipTests` BUILD SUCCESS（改造后全量 `mvn -pl helloai-start -am package` 29s 成功）；`vue-tsc --noEmit` 无类型错误；`npm run build` 成功（18.00s）。
- 真实环境：重新打包重启（PID 17124，6565 就绪）后 `verify-674-remove-specialization.ps1` **16/16 全 PASS**——列表/详情/注册响应均无 specializationSlug 字段（契约层面确认移除）/ 注册带 skills 显式生效 / 编辑不带 modelType 更新成功 / 内部 LLM 注册缺省 modelType=null（系统默认 provider 兜底）。

#### 4. 影响与遗留

- 影响：Agent 管理链路去掉无效的专业化选择（executor 专业化下拉、编辑模型类型字段），内部 LLM 注册简化——不再选 provider，模型统一由系统配置决定；技能在注册时即可填写（显式优先，不填仍按接入类型+关键词自动推导）。
- 遗留：① DB 列 specialization_slug 与实体字段保留但已无任何业务消费，可随大版本迁移一并清理；② prompt_template 的 AGENT_SPECIALIZATION 分类仍可创建但 Agent 侧不再消费（模板管理页保留）；③ §6.72 遗留① AgentDetailVO 仍未映射 skills，本轮未扩散；④ 内部 LLM 注册不再展示实际生效的 provider/模型，如需可在详情页补只读展示（未做）。
### 6.75 MinIO 附件存储集成 + 附件目录路径规范（A0-5 遗留②收口）（2026-08-12）

#### 1. 背景与结论

- **背景**：用户拍板把 MinIO 用起来——① A0-5 遗留②「minio:// 外部存储附件平台不可直读（isContentLoadable=false），即使真实存在也被当作无证据」要求收口，agent 返回结果需要平台侧验证附件文件或脚本；② 附件管理此前无明确路径要求，要求以后生成的附件按「归属者 username → 年 → 月 → 主任务」分文件夹组织，便于按规律检索哪些文件属于哪些主任务。
- **勘察结论**：MinIO 早已进 docker-compose（29000 S3 API / 29001 Console），`ArtifactStorage` 抽象在 §6.30 已预留 minio 扩展位；attachment 元数据表 `detectBucketName/detectObjectKey` 已支持 minio:// 前缀解析；本地物化链（local://）objectKey 原为 `{subTaskId}/{yyyyMMdd}/{uuid8}-{safeName}`，无归属者/主任务维度。
- **落地决策**：① 引入 `io.minio:minio:8.5.12`（版本集中管理在根 pom），实现 `MinioArtifactStorage`（storageUrl=`minio://{bucket}/{objectKey}`，懒创建客户端 + 首次写入自动 makeBucketIfNotExists）；② 新增 `CompositeArtifactStorage`（@Primary 路由，ObjectProvider 懒解析避免自引用循环）：store 按 `helloai.storage.type` 路由主存储，load/supports 按协议前缀分派——存量 local:// 附件与新 minio:// 附件同时可读/可下载/可作执行证据；③ objectKey 统一规范为 `{ownerName}/{yyyy}/{MM}/{taskId}/{subTaskId}/{uuid8}-{safeName}`（Local 与 Minio 双实现一致），归属者目录取执行 Agent 注册名（agent.name，接口 static 方法清洗防路径穿越）；④ 默认 `helloai.storage.type` 切为 minio（bucket=helloai-artifacts，端点/凭证走环境变量兜底）。
- **边界**：外部 Agent 自己 PUT 到 MinIO 的路径不由平台强制（SKILL 建议按规范组织）；存量 local:// 附件不迁移；agent.name 无唯一索引，重名 Agent 的目录会合并（uuid 前缀保证文件不冲突）。

#### 2. 实现要点

- **依赖**：根 pom 加 `minio.version=8.5.12` + dependencyManagement 条目；helloai-core 引用。
- **配置**：`ArtifactStorageProperties` 扩展 `minioEndpoint/minioAccessKey/minioSecretKey/minioBucket` 四字段（带默认值，yml 未配置可跑）；`application.yml` storage 段重写（type=minio + `${MINIO_*}` 环境变量兜底 + 注释说明目录规范）。
- **存储层（helloai-core/system/storage）**：`ArtifactStorage` 接口加 `storageType()` 默认方法 + `sanitizeOwnerName/sanitizeFileName` 两个 static 清洗方法（原 Local 的 sanitizeFileName 上移共用）+ store 签名扩展为 `store(ownerName, taskId, subTaskId, fileName, content)`；`LocalArtifactStorage` objectKey 改新规范；`MinioArtifactStorage` 新建（putObject/getObject、bucket ensure、contentType 探测、包级测试构造器注入 mock client）；`CompositeArtifactStorage` 新建（@Primary，store 路由主存储，load/supports 前缀分派）。
- **物化链**：`ExecutionArtifactService` 注入 `AgentService`，`resolveOwnerName` 取 assignedAgentId 对应 Agent 注册名（缺失兜底 `agent-{id}`），store 传 `(ownerName, subTask.getTaskId(), subTask.getId(), ...)`。
- **直读链路**：`AttachmentService.isContentLoadable/loadContent` 经 Composite 路由天然支持 minio://（下载流式返回 + A0-5 证据检查生效），类注释同步更新；`McpMcpServer` uploadArtifact Gotchas 与 `McpToolService` 注释补「v2.7 起平台可直读 minio:// 附件 + 建议路径规范」。
- **SKILL 同步**：executor/planner SKILL.md 的 uploadArtifact 行更新（storageUrl 示例改 minio://、说明平台可直读与 `{注册名}/{yyyy}/{MM}/{taskId}/{subTaskId}/` 目录规范）。
- **验收脚本**：新建 `scripts/powershell/verify-minio-artifact.ps1`（UTF-8 头 + 单引号输出，规则 6 合规）：G1 MinIO health / G2 附件列表存在 minio:// 且 objectKey 符合目录规范 / G3 minio:// 附件下载 200 + 非空 + 未 302 重定向（无 minio 附件时输出 SKIP 与产生指引）。

#### 3. 验证结果

- 单测：`LocalArtifactStorageTest` 更新（新 store 签名 + 目录规范断言 + ownerName 清洗用例）；新建 `MinioArtifactStorageTest`（store 上传参数/URL 协议/objectKey 分层/load 读取/supports）与 `CompositeArtifactStorageTest`（store 路由 local/minio、未知类型抛错、load/supports 前缀分派）；`ExecutionArtifactServiceTest` 适配新构造器与 store 签名。`mvn -pl helloai-core -am test` 全量 **551 个测试全绿**（含修复 4 个新测试自身缺陷：mock Stream 单次消费需 thenAnswer 重建、mock GetObjectResponse 需 stub readAllBytes、verifyNoInteractions 与 stubbing 调用冲突改 never() 验证）。
- 真实环境：未重启后端，MinIO 实链（物化落桶 + 下载直读 + 证据核验）待 `verify-minio-artifact.ps1` 实测（G2/G3 需先有 minio:// 附件，可跑一次执行任务产生）。

#### 4. 影响与遗留

- 影响：① A0-5 遗留②关闭——minio:// 附件平台可直读，`SubTaskReviewService.checkEvidence` 与 `TaskDeliverableService` zip 打包对 MinIO 附件生效；② 附件目录统一「归属者/年/月/主任务/子任务」五层规范（local 与 minio 一致），MinIO Console 可按路径规律直接检索；③ 默认存储切 minio 后，未启动 MinIO 的环境物化失败仅记日志（best-effort 不阻断主链路），可改 `type: local` 回退。
- 遗留：① agent.name 无唯一索引，重名 Agent 目录合并（可后续加唯一约束或目录后缀 agentId）；② 外部 Agent uploadArtifact 的 storageUrl 路径靠 SKILL 约定不强制；③ 存量 local:// 附件保留可读不迁移；④ 真实环境 MinIO E2E 回归（verify-minio-artifact.ps1 G2/G3）待后端重启后执行。
### 6.76 登录链路脚本化验证 + MinioArtifactStorage 启动缺陷修复（2026-08-12）

#### 1. 背景与结论

- **背景**：用户拍板登录页去掉 api 登录（系统以「注册 + 账号密码登录」为主），登录页已改造为「登录/注册」双入口且登录类型固定 admin；用户要求不再用浏览器手动点，改为脚本模拟登录做验证。
- **勘察结论**：`POST /api/auth/login`（type=admin 账号密码 / type=agent API Key）仍保留 agent 通道（MCP/CLI 依赖），前端已不再暴露；`/api/auth/me`、`/api/auth/logout` 提供登录态校验与登出；业务异常码 4xx/5xx 由 `GlobalExceptionHandler` 映射为 HTTP 状态码（500 业务失败 = HTTP 500，401 会话过期 = HTTP 401）。
- **顺带发现并修复启动缺陷**：真实环境 jar 启动报 `BeanInstantiationException: No default constructor found`——`MinioArtifactStorage` 里手写的包级测试构造器（properties, client）导致 Lombok `@RequiredArgsConstructor` 被跳过（Lombok 规则：类中已存在任何构造器即不再生成），Spring 无法实例化。该缺陷在 v2.7（§6.75）引入，单测直接调用包级构造器所以没暴露。

#### 2. 实现要点

- **验收脚本**：新建 `scripts/shell/verify-login-e2e.sh`（macOS zsh 风格 + UTF-8 声明 + 单引号输出，规则 6 合规；`ADMIN_USER/ADMIN_PASSWORD` 环境变量可覆盖，默认 admin/admin123），11 项用例：0 健康检查 / 1 空用户名拒绝 / 2 空密码拒绝 / 3 未知用户（HTTP 500 + 用户不存在或已禁用）/ 4 错误密码（HTTP 500 + 密码错误）/ 5 非法登录类型 apikey（HTTP 200 + 登录类型无效，验证旧 api 入口服务端拒绝）/ 6 账号密码登录成功（token+type=admin+role）/ 7 /me 带 token 返回身份（type=admin + displayName 非空）/ 8 /me 无 token 返回 code=401 / 9 logout / 10 登出后旧 token 被 401 拒绝。
- **启动缺陷修复**：`MinioArtifactStorage.java` 删除包级测试构造器（恢复 Lombok 生成 public 单参构造器，Spring 构造器注入生效）；`MinioArtifactStorageTest` 改为同包直接注入包级 `client` 字段（跳过懒创建），保持 mock 语义。

#### 3. 验证结果

- 真实环境：JDK 17 + `mvn -pl helloai-start -am package -DskipTests` 构建，`java -jar helloai-start-1.0.0-SNAPSHOT.jar` 启动 6565 成功后，`verify-login-e2e.sh` **11/11 全 PASS**。
- 回归：`MinioArtifactStorageTest/CompositeArtifactStorageTest/LocalArtifactStorageTest` BUILD SUCCESS，存储层无回归。

#### 4. 影响与遗留

- 影响：① v2.7 引入的应用启动缺陷关闭（此前 jar 无法启动，minio 存储链路实际不可用）；② 登录链路（注册入口 + 账号密码登录 → /me 鉴权 → 登出失效）获得脚本化回归保障，后续改动可直接跑脚本。
- 遗留：空用户名/空密码的参数校验异常（`@Valid` 失败）被 `GlobalExceptionHandler` 兜底为 HTTP 500 而非 400，语义不准确，本轮未修；`type=agent` 通道保留供 MCP/CLI 使用，登录页已不暴露。
### 6.77 MinIO 附件 E2E 真实环境验证 + 登录页前端构建验证（2026-08-12）

#### 1. 背景与结论

- **背景**：§6.76 收口后遗留两件事——① 前端登录页改造（去 api 登录）未做构建验证；② §6.75 遗留④「真实环境 MinIO E2E（verify-minio-artifact.ps1 G2/G3）待后端重启后执行」，且该脚本是 PowerShell，macOS 无 pwsh 跑不了。用户要求：跑前端构建 + 移植 zsh 版并触发最小执行任务产生附件，验证物化落桶与直读下载。
- **勘察结论**：平台 4 个 LLM provider 全部 `apiKeyConfigured=false`（credential_vault 无平台级凭证），**内部 LLM 执行链不可行**；但物化链 `ExecutionResultHandler` 由 `submitResult`（外部 Agent REST 直通即可调用，无需 LLM）触发——success=true 时 afterCommit 调 `ExecutionArtifactService.materialize` → `artifactStorage.store` 写入主存储（minio），output 非空即解析为单个 .md 文件。由此确定「最小执行物化」路径：建 Agent → 建任务 → 建子任务（指派）→ claim → submitResult(output 非空)。

#### 2. 实现要点

- **前端构建**：`npx vue-tsc --noEmit` 0 错误 + `npm run build` 成功（5.82s，仅 chunk 体积提示，无类型/构建错误），登录页改造（入口双 tab：登录/注册，type 固定 admin）构建侧通过。
- **验收脚本**：新建 `scripts/shell/verify-minio-artifact.sh`（macOS zsh 版，UTF-8 声明 + 单引号输出，规则 6 合规；`ADMIN_USER/ADMIN_PASSWORD` 可覆盖）——自动完成：G1 MinIO health → admin 登录 → 建 EXECUTOR Agent → 建任务 → batch 建子任务并指派 → agent claim（Bearer apiKey）→ submitResult 触发物化 → 等待 afterCommit 异步落桶 → G2 附件列表存在 minio:// 且 objectKey 匹配 `归属者/年/月/taskId/subTaskId/uuid8-文件名` → G3 下载 200 + 非空 + Content-Disposition attachment + 未 302。每次运行新建独立 Agent（名带时间戳），与既有数据零冲突。
- **实现细节**：claim/submit 走 REST 直通 `POST /api/mcp/tools/*`（免 MCP 握手）；submit 的 output 用单行字符串构造 JSON（多行字符串在 zsh 命令替换中解析会失败，踩坑后改单行规避）。

#### 3. 验证结果

- 真实环境（jar 启动 6565 + docker MinIO）**9/9 全 PASS**：G1 健康 ✓ / P1 建 Agent ✓ / P2 claim ✓ / P3 submit 触发物化 ✓ / G2 附件落库（minio:// 1 条，objectKey=`minio-e2e-executor-{ts}/2026/08/{taskId}/{subTaskId}/d0173465-MinIO 附件物化验证子任务.md` 完全符合规范）✓ / G3 下载 200 + 90 字节 + Content-Disposition ✓。
- 桶内实证：`docker exec helloai-minio mc ls -r local/helloai-artifacts/` 确认对象真实存在（89B，路径与附件元数据一致）——**物化落桶全链路闭环**。

#### 4. 影响与遗留

- 影响：① A0-5 遗留②完整闭环——minio:// 附件平台直读下载在真实环境实测通过，§6.75 遗留④关闭；② 获得可重复执行的 MinIO 附件回归脚本（macOS zsh 版），无需 LLM 凭证即可触发物化链。
- 遗留：① 内部 LLM 执行物化（平台级凭证）未实测——配置任一 provider API Key 后可跑真实 LLM 执行任务复核；② 脚本每次运行会新增测试 Agent/任务/子任务（幂等设计，无清理动作）；③ 空表单校验异常 HTTP 500 语义问题（§6.76 遗留，未扩散）。
### 6.78 参数校验异常语义修复：@Valid 校验失败 500 → 400（2026-08-12）

#### 1. 背景与结论

- **背景**：§6.76/6.77 遗留③——`@Valid @RequestBody` 校验失败（如登录空密码）抛 `MethodArgumentNotValidException`，此前无专门 handler，被 `GlobalExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 兜底为 HTTP 500，语义不准确（客户端参数问题不是服务端错误）。用户确认修复。
- **勘察结论**：前端 `request.ts` 拦截器完全基于 body.code 判断（200 成功 / 401、403 特殊处理 / 其余 ElMessage.error(res.msg)），400 与 500 走同一分支——**HTTP 状态码变更对前端行为零影响**，且校验消息会直接展示（体验更准确）。项目未使用 `@Validated` 类级校验（无 ConstraintViolationException 场景），只需处理 MethodArgumentNotValidException。

#### 2. 实现要点

- `GlobalExceptionHandler` 新增 `@ExceptionHandler(MethodArgumentNotValidException.class)`：HTTP 400 + `R.fail(400, 首条字段错误消息)`（取 FieldError.getDefaultMessage，如「凭证不能为空」；无字段错误时兜底「参数校验失败」）。
- 同步更新 `scripts/shell/verify-login-e2e.sh`：[2] 空密码由宽松断言改为明确断言 HTTP 400 + body.code=400 + 消息含「凭证」；[1] 空用户名明确为 HTTP 500（username 字段无校验注解，仅 type/credential 必填，空用户名走业务层「用户不存在或已禁用」——脚本注释说明字段注解边界，防止误判）。

#### 3. 验证结果

- 手动验证：空密码 → HTTP 400 + `{"code":400,"msg":"凭证不能为空"}`；空 type → HTTP 400。
- 回归：`verify-login-e2e.sh` 11 项全 PASS（[2] 新断言生效）；`verify-minio-artifact.sh` 9/9 全 PASS（物化链无回归，minio:// 附件累积 5 条 objectKey 均符合规范）。
- 测试代码无依赖旧 500 行为的断言（grep 确认）。

#### 4. 影响与遗留

- 影响：全站 `@Valid` 校验失败统一返回 HTTP 400 + 具体字段消息（此前 500 + 兜底文案），错误语义与前端提示同时改善；日志从 error 级兜底变为 debug 级字段消息。
- 遗留：无新增。§6.76/6.77 遗留③关闭；IllegalArgumentException handler 返回体 code=500 但 HTTP 400（body.code 与状态码不一致）为既有行为，未扩散。
### 6.79 批次 B 收口：N11 失败计数语义确认 + isExecutionDense 误判率观察（2026-08-13）

#### 1. 背景与结论

- **背景**：a0-plan 批次 B 两项观察项——B1 疑点「trae-executor consecutive_failure_count=2 疑似把系统跳过审核计为外部 agent 失败」（§6.56/6.57 遗留）；B2 §6.52 引入的关键词启发式 `isExecutionDense` 误判率观察（误判会影响能力预检与回退方向）。
- **B1 勘察结论（代码层）**：`recordFailure/recordSuccess` 全仓库唯一调用点是 `ExecutionResultHandler.applyFailureTracking`（按 `report.isSuccess()` 分支，success=false 时 block + recordFailure）。skip 类事件——`sub_task_auto_review_skip_max_rework`（SubTaskReviewService 审核侧）、`sub_task_fallback_skip_policy` / `sub_task_fallback_skip_need_human`（SubTaskDispatchService N11 回退侧）、`sub_task_dispatch_skip_no_capability`（ResilientDispatcher 分配预检侧）——分别产生于三个不同模块，均不经过 ExecutionResultHandler 入口，**系统决策类事件不计入失败计数**，plan 疑点从代码路径上证伪。
- **B1 数据实证（MCP 查库只读，2026-08-13）**：① `sub_task_auto_review_skip_max_rework` 全库 2 条（2026-08-02/08-11 同一子任务），agent_id 均为 null（系统 REVIEWER 侧，无 agent 归属）；② trae-executor（2086711950328950786）名下 timeline 零 `sub_task_execute_failed` / `sub_task_execute_result_discarded` 事件，全库 `execute_failed` 最新仅 2026-07-16 历史测试数据（8 月无任何执行失败事件）；③ 8-10~8-11 仅 1 个实战任务（trade-cloud E2E，2087076754479771649），事件流全程成功（plan_generated→plan_confirmed→5×dispatch_prepare→5×execute_submit→5×auto_review_passed→5×artifact_materialized→task_auto_completed→final_report），trae-executor 5 次成功提交触发 recordSuccess 归零，当前 `consecutive_failure_count=0 / last_failure_time=null`；④ plan 疑点「计数=2」的历史来源已不可复现（现库无对应失败事件），与 skip 审核无因果关系。
- **B2 勘察结论**：`isExecutionDense` 共 4 个调用点（ResilientDispatcher 分配预检 / SubTaskDispatchService N11 回退预检 / SubTaskReviewService 提交者预检 + checkEvidence 竞态补偿等待窗口），判定文本 = content/acceptance/deliverable 拼接，EXECUTION_DENSE_PATTERN 共 15 个关键词（`.ps1/.sh/.bat/.py/.jar` 词边界 + docker/kubectl/npm run/mvn /gradle + 五个中文词）。
- **B2 数据实证**：全库 65 个子任务仅 2 条命中（3.1%）：①「环境冷启与基线确认」（2087076796930322434）命中 docker——**判定正确**（真实需本机 docker-compose 操作的任务；8-11 的 2 条 `sub_task_dispatch_skip_no_capability` 全部针对它，inner-loop-executor/probe-moonshot（API_KEY_LLM 无本机能力）被正确跳过，最终由 trae-executor（CLI_CLIENT）接手完成，链路符合设计意图）；②「编写README文档」（2083857076507279366）命中「启动服务」——**理论误判面**（验收文案是描述性文本，本质文档写作任务），但该任务 8-02 创建、在 §6.52 预检上线前已完成分配（assigned inner-loop-executor 并 DONE），未产生实际影响。
- **落地决策**：两项均无需代码修复。B1 疑点证伪（skip 类不计入失败，计数语义与代码路径一致）；B2 真实样本判定正确、误判样本未产生实际影响，不加白/黑名单、不改配置化，保留观察。

#### 2. 实现要点

- 纯勘察 + MCP 查库（只读），零代码改动；全程 9 条只读 SQL（agent / task_timeline / task / information_schema）。
- 结论回填：本迭代记录 + a0-plan.md 勾除 B1/B2。

#### 3. 验证结果

- 代码层：`recordFailure/recordSuccess` grep 全仓库唯一调用点确认（ExternalAgentFailureTracker L57/L80 仅 ExecutionResultHandler L313/L315 调用）；`isExecutionDense` 4 个调用点与事件名确认。
- 数据层：skip 事件 agent_id=null 实证、trae-executor 零失败事件、计数归零与成功事件一一对应、dense 命中样本全部人工核验——证据链完整闭环。

#### 4. 影响与遗留

- 影响：① 批次 B 收口，a0-plan 剩余批次变为 C~H；② N11 失败计数语义明确——仅「执行结果 success=false」计入失败，平台自身决策（跳过审核/跳过回退/能力预检跳过）不计入，外部 agent 不会被系统决策误伤触发阈值回退。
- 遗留：① B2 理论误判面（描述性文本含「启动服务/部署/docker」等词）仍在，样本量增大后如出现实际误伤，可用配置化白/黑名单处置；② trae-executor 历史「计数=2」来源已不可复现（现库无对应失败事件），若再观察到计数与事件不匹配，需排查 recordFailure 调用链外来源（如直改 DB/旧版本残留）；③ 批次 B 结论未入差距表（观察类项，差距表无对应行）。

### 6.80 C1 Provider 生态补全收口：协议工厂/Registry/目录服务单测 41 例 + 路由大小写归一修复（2026-08-13）

#### 1. 背景与结论

- **背景**：a0-plan 批次 C1「moonshot/minimax/dashscope Provider Factory」——plan 原假设「yml 已预置三 provider 配置段但缺 Factory 实现，目录接口标记不可用」。预检发现该假设已被 §6.52 方案 B（V46）取代：OpenAiCompatibleProtocolFactory / AnthropicCompatibleProtocolFactory 通用协议工厂已落地（Moonshot/DashScope/Minimax 三个专用 Factory 已删除），llm_provider 表 4 provider 配置齐全，目录接口早已可用——**C1 子任务 1/2 为过时项**，真实缺口是 Provider 域零单测覆盖（协议工厂 / Registry 路由 / CatalogService 均无测试）。
- **顺带修复判定不一致缺陷**：`LlmProviderChatClientFactoryRegistry` 路由侧对 protocol_type 精确匹配（`Collectors.toMap(ProtocolFactory::protocolType)`），而 `LlmProviderCatalogService.isFactorySupported` 已 toUpperCase——DB 若写入小写协议类型会出现「目录显示可用、路由实际失败」的判定不一致。修复：Registry 新增 `normalizeProtocolType`（null 安全 + Locale.ROOT 归一），路由与目录判定口径统一为大小写不敏感。

#### 2. 实现要点

- 代码修复 1 处：`LlmProviderChatClientFactoryRegistry` 路由改用 `protocolFactoryMap().get(normalizeProtocolType(provider.getProtocolType()))`。
- 新增 4 个测试文件共 41 用例（JUnit5 + @Nested + @DisplayName + AssertJ + Mockito，项目范式）：
  - `OpenAiCompatibleProtocolFactoryTest` 9 例：apiKey null/空白拒绝（BizException）；ChatClient 创建成功（OpenAiChatModel 类型断言 + model 三级兜底：请求值 > llm_provider.defaultModel > sys_config，verify 兜底层不触发）；平台 baseUrl 缺失回退 llm_provider.baseUrl 不抛错；yml 配置段缺失走默认超时；同四元组缓存复用（ChatModel 同实例，ChatClient 包装每次新建）+ apiKey 隔离（size=2）。
  - `AnthropicCompatibleProtocolFactoryTest` 9 例：同构（minimax，AnthropicChatModel 断言）。
  - `LlmProviderChatClientFactoryRegistryTest` 6 例：provider 未找到抛 BizException；未知协议（GEMINI_NATIVE）抛 BizException；deepseek 专用 Factory 优先（协议工厂 verify never）；OPENAI_COMPATIBLE / ANTHROPIC_COMPATIBLE 分发到对应工厂；小写协议类型仍可路由（归一修复的直接验证）。
  - `LlmProviderCatalogServiceTest` 17 例：listProviders factorySupported（deepseek code 特判 / 已知协议 / 未知协议 / protocolType null 拦截 / providerCode 小写归一）；available = enabled && apiKeyConfigured && factorySupported 组合（enabled=0、key 未配置两分支）；isProviderAvailable（null/blank 拒绝、大小写不敏感匹配、不可用返回 false）；bindPlatformApiKeyIfAbsent 四分支（不可用抛错 / 已有 ACTIVE 凭证幂等 false / 平台 Key 缺失抛错 / 正常绑定 verify bindAgentApiKey 五参含 remark）；provisionPlatformCredential（modelType 空回退 execution.provider / modelType 前缀解析 / 不可用静默跳过不抛错）。
- 顺带修复 §6.75 用户 MinIO 改动引入的既有测试回归：`CompositeArtifactStorageTest.shouldDispatchLoadByPrefix` 未 stub `supports()`（实现改为按 supports 分派后 mock 默认 false 导致「无存储实例支持该地址」）→ 补 2 行 supports stub。

#### 3. 验证结果

- `mvn test -pl helloai-core -am -DskipTests=false`：**592/592 全绿**（C1 新增 41 + 既有 551 回归，含 §6.75~6.78 存储/登录相关测试）；注意根 pom 默认 `skipTests=true`，跑测试需显式 `-DskipTests=false`；本机 helloai-common 需 `-am` 从源码构建（本地仓库 jar 未 install，直接 `-pl helloai-core` 会引用旧 common 报 12 个编译错误）。
- 真实环境（java -jar 启动 6565，profile=local）：登录 admin 后 `GET /api/admin/agents/listLlmProviders` 实测 4 provider 全部返回——deepseek/moonshot/dashscope（OPENAI_COMPATIBLE）+ minimax（ANTHROPIC_COMPATIBLE），**factorySupported=true、available=true 各 4/4**；apiKeyConfigured 已全部 true（平台级 Key 已配置，优于勘察时「全部未配置」预期，可用性全绿）。

#### 4. 影响与遗留

- 影响：① 批次 C 的 C1 收口，a0-plan C1 标记已收口（子任务 1/2 标注过时项）；② Registry 路由与目录可用性判定口径统一（协议类型大小写不敏感，消除 DB 小写写入的隐性不一致）；③ Provider 域单测从 0 到 41，后续新增厂商/改协议路由有回归护栏。
- 遗留：① plan C1 子任务 1/2（「补三个 Factory」）为过时项，由 §6.52 方案 B 交付，不重复建设；② `LlmProviderChatClientFactoryRegistry.protocolFactoryMap` 为 volatile 懒初始化，极端并发首路由存在重复构建（结果一致、无业务影响），后续可改初始化钩子；③ 本轮代码（Registry 修复 + 4 测试 + 1 测试修复）与 §6.79/§6.80 文档未 git 提交，待用户确认后提交；④ 后端实例已由本轮回填验证启动（6565），用户可直接使用。

### 6.81 C2 credential_vault 迁移收口：盘点/读取优先级单测 17 例 + 权限颗粒度审计 + 孤儿凭证清理 SQL（2026-08-13）

#### 1. 背景与结论

- **背景**：a0-plan 批次 C2「credential_vault 迁移收口」——N10 部分落地（最小模型/绑定/托管已具备），迁移、过渡期双活策略与权限颗粒度未收口。
- **盘点结论（代码 + MCP 查库实证）**：① **无明文密钥存量需迁移**——`agent.api_key` 自 V1 起就是工牌 consumerToken（`AgentService.register` 自动签发，V1 列注释「API_KEY_LLM 不存真实 LLM 凭证」），`sys_config` 无 llm/provider/api 键，真实 LLM Key 全部 AES-GCM 加密存于 `credential_vault`（无散落明文）；② **读取路径已全量 vault 化**——`ApiKeyAgentExecutor` / `AgentExecutionConnectivityService` 走 `CredentialVaultBindingService.getAgentApiKeyPlaintext`（secretRef 环境变量优先 > encrypted_value 解密，无 vault 返回 null，`requireVault=true` 时直接拒绝，**无 agent.api_key 回退**）；`AgentSelector` / `PlannerAgentPicker` 用 `hasActiveAgentCredential` 过滤候选；③ **过渡期双活仅存在于平台级**——`PlatformProviderConfigService.getApiKey` = vault PLATFORM 级 ACTIVE 凭证（secretRef > encrypted_value）> yml 兜底 > null（§6.52 已实现）；④ **存量凭证 77 条**（deleted=0）：PLATFORM 4 ACTIVE（4 provider 系统设置页写入）+ AGENT 73（ACTIVE 44 + DISABLED 29）；⑤ **孤儿凭证 61 条**（34 ACTIVE + 27 DISABLED，owner 已物理删除未清理，多为 verify-* e2e 临时 agent 与历史轮换链残留）——本轮治理项；⑥ 现存 agent 凭证 12 条（10 ACTIVE 覆盖全部 10 个 API_KEY_LLM agent + 2 DISABLED 轮换残留），**无「API_KEY_LLM 无 vault」执行缺口**。

#### 2. 实现要点

- **读取优先级单测 17 例**（锁定 C2 验收「读取路径单测覆盖优先级」）：
  - `CredentialVaultBindingServiceTest` 6 例（Agent 级读取语义）：无 ACTIVE vault 返回 null 不回落兜底；secretRef 环境变量优先（用系统必然存在的 PATH 验证，Mockito 禁止 mock System 静态方法）；secretRef 空环境变量抛 BizException（fail-close 不回退 encrypted_value）；encrypted_value 解密路径；vault 缺双值抛错；bindAgentApiKey 加密 + 五参透传。
  - `PlatformProviderConfigServiceTest` 11 例（平台级过渡期双活）：vault encrypted_value 优先于 yml；vault secretRef 环境变量优先；vault secretRef 空环境变量回退 yml 不抛错；无 vault 回退 yml（老环境平滑迁移）；双无返回 null；isApiKeyConfigured 三态；isApiKeyFromVault；maskApiKey 尾 4 位脱敏 / null。
- **权限颗粒度审计收口**（结论性，不新开接口）：`/api/**` 全量鉴权（AuthInterceptor：admin token 或 agent Bearer，无凭证 401）；`CredentialController`（bind/listByAgentId）全部 `requireAdmin()`；平台级凭证管理（AdminLlmProviderController / AdminProviderConfigController）走 admin 拦截器；**MCP 工具集零凭证接口**（外部 agent 无密钥读写通道）；执行链内部读取按 owner 隔离（`getActiveAgentApiKey(agentId, provider)` 仅查本 owner）；API_KEY_LLM 自助注册仅触发平台 key 副本绑定（`provisionPlatformCredential`，托管语义，agent 不拿明文）。结论：vault 读写权限已按「仅管理员 + 执行链 owner 维度」收口，无 agent 侧越权通道；「按 owner/角色开放自助管理」明确不做（托管语义，防明文外流）。
- **孤儿凭证清理 SQL**（写操作，交付用户执行，AI 不代执行）：逻辑删除 61 条孤儿凭证（NOT EXISTS agent 表，owner_type=AGENT），清理后剩余 12 条现存 agent 凭证（10 ACTIVE + 2 DISABLED 轮换链，保留审计）。

#### 3. 验证结果

- 定向：`mvn test -pl helloai-core -am -DskipTests=false -Dtest=CredentialVaultBindingServiceTest,PlatformProviderConfigServiceTest` **17/17 全绿**。
- 全量回归：`mvn test -pl helloai-core -am -DskipTests=false` **599/599 全绿**（新增 17 例 + 既有 582 回归，含 C1 的 41 例 Provider 域用例）。
- 踩坑记录：① Mockito **禁止 mockStatic(System.class)**（class loading 冲突报 infinite loops），secretRef 用例改用系统必然存在的 PATH 环境变量断言；② Node fallback shell 下带点号的 `-Dsurefire.failIfNoSpecifiedTests=false` 必须整体加引号，否则被 PowerShell 拆成未知 lifecycle phase。

#### 4. 影响与遗留

- 影响：① 批次 C 全部收口（C1 §6.80 + C2 本轮），a0-plan 剩余批次为 D~H；② N10 由「部分落地」推进为「已收口」——迁移无需做（无明文存量，双活已实现且单测锁定）、权限颗粒度审计闭环（admin-only + owner 隔离 + MCP 零暴露）、存量治理交付清理 SQL；③ vault 读取优先级从此有 17 例回归护栏（Agent 级无兜底 / 平台级 vault > yml）。
- 遗留：① **清理 SQL 待用户执行**（61 条孤儿凭证逻辑删除，SQL 见差距表 N10 增量条目/汇报）；② 现存 agent 的 2 条 DISABLED 轮换残留保留（审计链语义）；③ 本轮新增 2 个测试文件与 §6.81 文档未 git 提交，与 C1（§6.79/§6.80）一并待用户确认后提交；④ 后端实例仍在运行（6565），用户可直接使用。

### 6.82 批次 D REVIEWER 自动审查 L2 MQ consumer 补齐：MqReviewCommandConsumer + 核验互斥锁防双审（2026-08-13）

#### 1. 背景与结论

- **背景**：a0-plan 批次 D「REVIEWER 自动审查 L2 MQ consumer（M9 遗留）」。审查链三级容错（§6.40 架构）——L1 `SubTaskSubmittedForReviewEvent` AFTER_COMMIT + @Async 主路径、L2 MQ `agent.reviewer.assigned` → `reviewerQueue`（§6.49 遗留：无 consumer）、L3 `@Scheduled` DB 孤儿扫描兜底；L2 缺失使 MQ 备份路径悬空，L1 事件链丢失时只能等 L3 的 60s 阈值窗口。
- **勘察结论**：
  - 生产侧已存在：`AgentOutboxService.createEvent`（REVIEW → routing_key=`agent.reviewer.assigned`）→ `AgentEventCompensationTask`（helloai-job，15s 轮询 + Redis 锁）→ `DomainEventPublisher` → `agentExchange`；`reviewerQueue` 已绑定 `agent.reviewer.*` 通配符 + DLX 死信（`RabbitMQConfig`），消费侧零代码（全库无 MqReviewCommandConsumer）。
  - **生产侧缺口**：payload 无 eventId（仅 subTaskId/taskId/status/agentId），无法支撑「同事件重投不重复消费、同子任务多轮 REVIEW 各自独立」的消息级幂等。
  - **双审风险**：L1/L2/L3 三路并发触发同一子任务核验时，`reviewSubTask` 的「getById 读状态」防重在 LLM 调用窗口（数秒）内不互斥，并发下可能双审（双 LLM 调用 + 判定竞态）。
- **结论**：补 L2 consumer + 生产侧 eventId 幂等键 + 核验互斥锁，三级容错闭环。

#### 2. 实际落地

- **生产侧**：`AgentOutboxService.createEvent` payload 补 `eventId`（1 处，向后兼容——老消息无 eventId 时消费侧回退幂等键）。
- **消费侧**：新建 `helloai-core/.../review/mqconsumer/MqReviewCommandConsumer`（145 行，`@ConditionalOnProperty("helloai.mq.review.consumer-enabled")`，yml 默认 true）：
  - `@RabbitListener(queues=REVIEWER_QUEUE, ackMode="MANUAL")` 解析 payload Map → `tryConsume(messageId, "MqReviewCommandConsumer", () -> subTaskReviewService.reviewSubTask(subTaskId, agentId))`；
  - 幂等键：payload.eventId 优先（新消息），回退 `sub_task.review:{subTaskId}`（老消息）；
  - MANUAL ACK 语义同 `MqExecutionCommandConsumer`：解析失败/缺 subTaskId → ACK；消费失败 → NACK(requeue=false) 走 DLX；
  - agentId=0（null 占位）归一为 null；Jackson 反序列化小整数统一 toLong。
- **防双审**：`SubTaskReviewService.reviewSubTask` 拆壳 + `doReview` 主体，入口 Redis `setIfAbsent("review:lock:"+subTaskId, ttl=120s)` 互斥，finally 释放——L1/L2/L3 三路并发仅一路进入 LLM 核验窗口（TTL 兜底崩溃残留）。
- **配置**：application.yml `helloai.mq.review.consumer-enabled: true`（默认开启，对齐 execution-command 范式）。

#### 3. 验证结果

- 定向：`mvn test -pl helloai-core -am -DskipTests=false -Dtest=MqReviewCommandConsumerTest,SubTaskReviewServiceTest` **30/30 全绿**（MqReviewCommandConsumerTest 7 + SubTaskReviewServiceTest 23）。
  - `MqReviewCommandConsumerTest` 7 例：正常消息（eventId 幂等键 + reviewSubTask(11,22) + ACK）/ 老消息回退幂等键 / agentId=0 归一 null / 坏 JSON ACK / 缺 subTaskId ACK / 幂等命中直接 ACK / 核验异常 NACK→DLX。
  - `SubTaskReviewServiceTest` 新增 3 例：锁占用跳过（不调 LLM/getById/complete + 不删他人锁）/ 正常核验 finally 释放锁 / LLM 异常锁仍释放。
- 全量回归：`mvn test -pl helloai-core -am -DskipTests=false` **609/609 全绿**（C2 599 + D 新增 10）。
- 踩坑记录：锁占用用例首版 stub 了 `subTaskService.getById` 触发 UnnecessaryStubbing（锁未持有成功根本不读子任务）→ 删 stub + 补 `verify(subTaskService, never()).getById(anyLong())` 正向断言。

#### 4. 影响与遗留

- 影响：① 批次 D 收口，a0-plan 剩余批次 E~H；② 三级容错 L2 补齐——L1 事件链丢失时 Outbox 补偿投递（15s）即触发核验，不再等 L3 的 60s 阈值窗口；③ 核验互斥锁覆盖 L1/L2/L3 三路，消除 LLM 双审竞态；④ eventId 幂等键使同事件重投不重复消费、同子任务多轮 REVIEW（返工后再次提交）各自独立核验。
- 遗留：① reviewerQueue 绑定 `agent.reviewer.*` 通配符，未来若新增其他 reviewer 路由消息需评估消费语义；② 锁 TTL 120s 与 LLM 调用超时（sync-timeout-seconds 600s）不匹配——LLM 调用超 120s 时锁提前过期，极端场景仍可能双审（后续可把锁 TTL 提到与超时同量级）；③ 本轮代码（AgentOutboxService 1 处 + MqReviewCommandConsumer 新建 + SubTaskReviewService 互斥锁 + yml + 2 测试文件）与 §6.82 文档未 git 提交，待用户确认后提交；④ 真实环境 MQ 链路（reviewerQueue 消费 + event_consumption_log 记录）待后端重启后实测。

---

### 6.83 批次 E1 动态 TTL 自适应：AgentDutyLeaseProperties + resolveTtlMinutes/adaptiveRenew + S7 实测（2026-08-13）

#### 1. 范围

- a0-plan 批次 E1（N12 A2 第 2 段）：租约 TTL 不再静态固定，按 Agent 表现与在跑任务动态调整；配置化开关。
- 明确不做：策略落库（plan 未要求，score→TTL 映射为确定性纯函数）；TTL 变更落审计（沿用租约表原有字段）。

#### 2. 实际落地

- **配置**：新建 `AgentDutyLeaseProperties`（`helloai-common/config/`，prefix=`helloai.agent.duty-lease`）——adaptive-ttl-enabled（默认 true）/ min-ttl-minutes=5 / max-ttl-minutes=240 / default-ttl-minutes=30 / full-score=100；application.yml `helloai.agent` 下新增 `duty-lease` 配置段。
- **服务**：`AgentDutyLeaseService` 新增两个方法：
  - `resolveTtlMinutes(agentId, explicitTtlMinutes)`：显式 TTL 优先；否则按 agent.score 线性映射 [0,fullScore]→[min,max]；无 score 用 consecutive_failure_count×20 折算表现分；开关关闭 / agent 缺失回退 default。
  - `adaptiveRenew(agentId)`：无 ACTIVE 租约返回 null；有在跑子任务（`SubTaskMapper.selectInFlightByAgent`，ASSIGNED/IN_PROGRESS/REWORK）→ 最大窗口（任务在跑延长）；空闲 → `resolveTtlMinutes` 动态窗口（空闲缩短）。
- **MCP 联动**：`McpToolService.checkIn` 未传 ttlMinutes 时改走 `resolveTtlMinutes`（不再固定 30）；A0-8 工具自动续租 `refreshDutyLease` 改调 `adaptiveRenew`，删除 `DEFAULT_RENEW_MINUTES`/`MAX_RENEW_MINUTES` 常量。
- **脚本**：`verify-agenthub-duty-e2e.ps1` 追加 S7 场景（S7.0 score 复位起点 / S7.1 score=0 → 断言窗口 [3,8]min / S7.2 score=100 → 断言窗口 [236,244]min / S7.3 checkOut + score 复位）；顺带修复 admin agents 列表接口失配（`GET /api/admin/agents` → `GET /api/admin/agents/list`，`pageNum` → `page`）。

#### 3. 验证结果

- 单测：新建 `AgentDutyLeaseAdaptiveTtlTest` **12/12 全绿**（显式 TTL 优先 / score=100→240 / score=0→5 / score=50→122 / 无 score 零失败→240 / 无 score 失败 5 次→5 / 开关关→30 / agent 缺失→30 / 无 ACTIVE→null / 在跑→240 / 空闲高分→240 / 空闲低分→5）；`McpToolServiceTest` A0-8 用例适配 adaptiveRenew 语义后 **22/22 全绿**。
- 全量回归：`mvn test -DskipTests=false` **341/341 全绿**（70 个测试类，FAIL=0 ERROR=0）。
- 真实环境：`verify-agenthub-duty-e2e.ps1` **ALL PASSED**（S1 checkIn / S2 checkOut / S3 DutyLeaseExpirationTask / S6 N12-P1 STRICT 回归 / **S7 动态 TTL：score=0 → ~5min 短窗口、score=100 → ~240min 长窗口**）。
- 踩坑记录：
  - 脚本 lookup 405：列表接口早已迁至 `/api/admin/agents/list`，脚本仍调根路径 GET → 修正两处 URL 后通过。
  - RabbitMQ 积压历史 Java 序列化消息（Phase 2F 修正前 `convertAndSend(POJO)` 产物）导致新进程启动即 `SecurityException: Attempt to deserialize unauthorized class java.util.LinkedHashMap` 无限 requeue 循环——该异常发生在消息转换层（listener 方法体之前），代码内「坏消息直接 ACK」兜底接不住；处理：停消费者 → purge 积压队列（reviewer 155 / executor 599 / planner 186 / dlx 1）→ 重启，队列全清零。

#### 4. 影响与遗留

- 影响：① 批次 E1 收口，a0-plan 剩余批次 E2~H；② checkIn 动态窗口 + 续约自适应——低表现 Agent 5min 短窗口快速回收值班态，高表现 Agent 240min 长窗口减少续约开销；③ 续约语义由「固定 30min」升级为「score/在跑任务自适应」，A0-8 工具调用自动续租链路无感知兼容。
- 遗留：① score→TTL 映射为线性纯函数未落策略表（plan 未要求，可后续演进）；② 本轮代码（AgentDutyLeaseProperties 新建 + AgentDutyLeaseService/McpToolService 修改 + yml + 单测 + 脚本）与 §6.83 文档未 git 提交，待用户确认后提交；③ A2 第 3 段（concurrency 预扣）为 a0-plan E2，待续；④ RabbitMQ 若再次出现旧格式积压消息（如重放历史测试消息），需先停消费者再 purge，无法在线清 in-flight。

---

### 6.84 批次 b0-b4：Service 接口/impl 分层拆分重构（2026-08-13）

#### 1. 范围

- **背景**：core 域 Service 层长期"类即服务"（`XxxService` 直接是 `@Service` 类，不拆接口），跨域引用与 Controller 直接依赖具体类，测试只能 mock 类本身。按分层契约与可测试性目标，启动 Service 层"接口 + impl"成对拆分（CODE_STYLE §4.x/§7.1 v2.8 起强制）。
- **批次规划**：b0 盘点引用点 + 组件扫描范围 + 测试结构；b1 system 域 13 拆；b2 task 域（11 + spec 3 拆、policy/migrator 归位）；b3 planner+review 域（4 拆、picker/router 归位、search 迁移）；b4 agent 域（10 移入拆 + 10 现有拆 + 3 归位）。
- 明确不做：本次不新增任何业务功能、不改数据库结构；b4 的"10 现有拆"与 b5（shared DoorbellService + mq MessageDeduplicationService 拆）留待后续批次。

#### 2. 实际落地

- **拆分形态**（统一范式）：接口 `XxxService` 放 `{domain}.service`（继承 `IService<Entity>`），实现 `XxxServiceImpl` 放 `{domain}.service.impl`（继承 `ServiceImpl<Mapper, Entity>`，`@Service` + `@RequiredArgsConstructor` 构造器注入）；Controller 与跨域引用全部改依赖接口。
- **b1 system 域 13 拆**：AdminDashboard / Attachment / Auth / CredentialVaultBinding / CredentialVault / Dashboard / LlmProviderQuery / LlmProvider / Module / PromptTemplate / Rule / SysConfig / SysUser 全部拆接口 + impl。
- **b2 task 域（11 + spec 3 拆 + 归位）**：ActivityLog / Feed / Review / Reward / SubTaskDispatch / SubTask / TaskDeliverable / TaskFinalReport / TaskIteration / Task / TaskTimeline 11 个拆接口 + impl；`task/spec` 下 TaskRunningSpecService / TaskRunningSpecJsonbService / TaskRunningSpecTableService 三拆合一迁至 `task/service`（`TaskRunningSpecService` 接口 + 2 个 impl）；`TaskAgentPolicy` 从 `task/service` 归位 `task/policy`（纯静态策略工具类，测试类同步随迁）。
- **b3 planner + review 域**：PlannerAnalysis / RequirementClarify / WebSearch / WebSearchServiceRouter 4 拆（`planner/service` + impl）；search 两实现 BochaWebSearch / TavilyWebSearch 迁移至 `planner/service/impl`（`planner/search` 仅剩 WebSearchResult 值对象）；RequirementConversation / RequirementMessage 2 个已接口化 service 补 impl；PlannerAgentPicker 归位 `planner/picker`；SubTaskReviewService 归位 `review/service` + impl。
- **b4 agent 域 10 移入拆**：从 chat（AgentChatClient / LlmProviderCatalog / PlatformProviderConfig）、command（ExecutionCommand）、execution（PlatformAgentExecution / SubTaskExecution）、observability（CircuitBreakerAlert / Heartbeat）、output（ExecutionArtifact）、mcp（McpTool）六个散包子包统一移入 `agent/service` + impl，与 agent/service 既有 11 个接口汇合，业务引用全部改接口。
- **测试调整**：全部相关测试 import 迁移 + `spy(new XxxServiceImpl(...))` 构造改接口依赖；task/policy 测试随迁；新增 `AttachmentServiceImplTest`（§6.85）。

#### 3. 验证结果

- `mvn -q compile -pl helloai-start -am -DskipTests` 7 模块 EXITCODE=0 全绿。
- 批次 4b import 修复闭环定向 **201 tests 全过**（含 lambdaQuery 链式 mock 先例：`doReturn(chain).when(service).lambdaQuery()` + `orderByDesc` 用 `ArgumentMatchers.<SFunction<T, ?>>any()` 消歧义）。
- 全量回归（b6，341/609 级）留待 b4 剩余 + b5 完成后一并执行。

#### 4. 影响与遗留

- 影响：① Service 层分层契约落地——跨域引用与 Controller 只依赖接口，impl 可独立测试（mock 接口而非 mock 类）；② 代码结构收口——`{domain}.service.impl` 成为唯一业务逻辑实现位，chat/command/execution/observability/output 散包子包的 Service 全部归位；③ CODE_STYLE v2.8 同步：§3.x 业务域分包（6 域实际子包 + service.impl 语义）、§4.1 包命名、§4.2 类命名、§4.x 接口使用原则（Service 层改为强制）、§7.1/7.2 标准编写模式、§20 校验清单、§21.2 Service 实现测试规则。
- 遗留：① b4 剩余"10 现有拆"（agent/service 既有单类形态 Service：AgentExecutionConnectivityService / AgentExecutionPreviewService 等 11 个中的 10 个）与"3 归位"未做，b4 批次未完全收口；② b5（shared DoorbellService + mq MessageDeduplicationService 拆）待续；③ b6 全量回归待 b4/b5 完成后执行；④ 本轮代码与本文档未 git 提交，待用户确认后提交。

### 6.85 附件管理双分类逐级下钻：MinIO/本地两类文件夹 + 任务/子任务标题回显（2026-08-13）

#### 1. 范围

- **背景**：附件管理页原为单层表格，无法按存储类型浏览 MinIO 产物层级（A0-5 遗留②的浏览侧缺口）。用户拍板方案：附件分两类顶级文件夹（MinIO 附件 / 本地附件），逐级下钻（Windows 资源管理器式点击跳转，非树展开），主任务/子任务目录虽按 ID 存储、回显用标题（name）。
- **明确不做**：MinIO 浏览器方案（已否决）、附件删除/移动、存储类型迁移。

#### 2. 实际落地

- **后端**：`Attachment` 实体 +3 transient 回填字段（`taskId` / `taskTitle` / `subTaskTitle`，`@TableField(exist=false)` 不落库）；`AttachmentServiceImpl.list` 批量回填——listByIds 查 SubTask（Set 去重）→ 再 listByIds 查 Task，Map 装配标题，无 N+1，子任务已删容错留空；新增 `AttachmentServiceImplTest` 3 例（空列表不查询 / 标题回填断言 / 子任务已删容错）。
- **前端**：`types/index.ts` Attachment +3 可选字段；`AttachmentList.vue` 重写为面包屑 + 逐级下钻——根视图「MinIO 附件 / 本地附件」两固定文件夹（按 storageUrl 前缀计数，无数据不显示）→ agent → 年月 → 任务标题（ID 灰色副文本）→ 子任务标题（ID 灰色副文本）→ 文件行下载；兼容 minio 6 段新格式与 local 3 段老格式 objectKey；文件夹显示计数。
- **顺带修复**：下载 404 旧 bug——原 `/api/attachments/{id}/download` 后端实际端点只有 `/downloadById/{id}`，已改正确路径 + `saveBlobResponse` 落盘。

#### 3. 验证结果

- 后端：`mvn -pl helloai-core -am test -Dtest=AttachmentServiceImplTest` **Tests run: 3, Failures: 0, Errors: 0**（踩坑：`service.list(null)` 重载歧义需 `(Long) null` 强转；`orderByDesc` 需显式 SFunction 泛型；stub 参数 List 与实现实参 Set equals 恒 false 需 `any()`）。
- 前端：`npx vue-tsc -b --force` 0 错误。
- 真实环境：用户 IDEA 重启后端后刷新附件管理页验证（标题回显依赖新代码生效）。

#### 4. 影响与遗留

- 影响：① 附件管理从单层表格升级为存储类型可感知的层级浏览，MinIO 产物可逐级定位下载；② 任务/子任务目录回显标题 + ID 副文本，与 §6.75 objectKey 规范（ID 锚点）互补；③ 下载路径 bug 闭环。
- 遗留：① 真实环境页面效果待用户验证（后端需重启加载新 list 回填逻辑）；② b6 全量回归时一并回归附件相关用例；③ 本轮代码与本文档未 git 提交，待用户确认后提交。


### 6.86 E2 并发额度派发即占用：ConcurrencyQuotaService + 选人链/落库双防线（2026-08-13）

#### 1. 范围

- **背景**：N12 A2 第 3 段（E2）——checkIn 声明的 maxConcurrent 仅记录在租约上，派发链从不读取，Agent 可被无限并发派发。目标语义：派发即占用额度、完成/改派/回收自动释放、选人跳过满额 Agent。方案经多轮论证收敛为"DB 实时统计一条线"（额度判定属写时判定数据，不建缓存、不双删、不引入 Redis/分布式锁；企业版 Redis 预扣留接口位）。
- **明确不做**：Redis/Redisson 实现（仅留 `ConcurrencyQuotaService` 接口位）；死信人工指派不受额度约束（人工兜底例外）；前端展示；b6 全量回归。

#### 2. 实际落地

- **接口**：`ConcurrencyQuotaService`（agent/service）——`inFlightCount`（在飞占用）/ `resolveQuota`（额度，null=不限制）/ `canAccept` 默认判定。
- **默认实现**：`InFlightDbQuotaService`（agent/service/impl）——占用 = `SubTaskMapper.countInFlightByAgent`（ASSIGNED/IN_PROGRESS/REWORK，与 E1 租约在飞同口径）；额度优先级：ACTIVE 租约 maxConcurrent（值班承诺）> capabilities 显式 `maxConcurrentTasks`（能力声明，无租约时生效）> null（不限制，与 E2 前行为完全兼容）。
- **Mapper**：`SubTaskMapper.countInFlightByAgent`（COUNT 变体）；`AgentMapper.selectByIdForUpdate`（FOR UPDATE 行锁）。
- **选人链**：`AgentSelector.pickFromCandidates` 过滤链新增额度过滤（requireIdle 之后、ACTIVE 之前），满额 Agent 跳过；`enforceMaxConcurrent=false` 跳过本检查。
- **落库原子防线**：`SubTaskServiceImpl.assignNext` 在状态校验后、changeStatus 前 `selectByIdForUpdate(agentId)` 锁 agent 行 → 同一 Agent 并发派发在 PostgreSQL 行锁上串行化（多实例同样成立）→ 锁内重新 `canAccept` 判定，满额抛 `AgentUnavailableException`（不计熔断统计，ResilientDispatcher 走 fallback 换人；并发窗口下 fallback 内仍满额则异常冒泡，任务保持 PENDING 由定时兜底重试）。
- **配置**：`AgentDispatchProperties.enforceMaxConcurrent`（默认 true）+ yml `dispatch.enforce-max-concurrent: true`。
- **释放语义**：DB 实时统计天然覆盖——完成/取消/死信（终态不占）、回收（`resetToPendingForDispatch` 清 assigned_agent_id）、改派（assigned_agent_id 迁移）、租约过期（resolveQuota 回退 capabilities/null），无需显式 release 钩子。

#### 3. 验证结果

- `mvn -pl helloai-core -am test -DskipTests=false -Dtest=...` 5 测试类 **78 tests 全过**：新增 `InFlightDbQuotaServiceTest`（11 例：租约优先/capabilities 数字/字符串/未声明/agent 不存在/非数字 + 占用边界）、`SubTaskServiceQuotaTest`（4 例：满额拒派不落库/未满正常 ASSIGNED/开关关闭放行/状态校验先于加锁）、`AgentSelectorTest` 补 E2 额度过滤 3 例（满额跳过/未满选中/开关关闭）；HandoverTest 11 + IsReadyTest 8 回归。
- 踩坑：pom 默认 `skipTests=true`，跑测试需 `-DskipTests=false`；surefire 3.2.5 多模块指定 `-Dtest` 需 `-Dsurefire.failIfNoSpecifiedTests=false`；Mockito STRICT_STUBS 下 setUp 公共 stub 需移入实际用到的用例（UnnecessaryStubbingException）。

#### 4. 影响与遗留

- 影响：① 有租约 Agent 的 maxConcurrent 从"记录"变为"强制"（选人跳过 + 落库拒派双防线）；② 无租约且未显式声明 maxConcurrentTasks 的 Agent 行为完全不变（向后兼容）；③ 企业版可替换 Redis 预扣实现而不动调用方。
- 遗留：① 并发窗口下 fallback 内仍满额时异常冒泡边界（任务留 PENDING 由定时兜底，可接受，已注释标注）；② b6 全量回归待做（本轮已跑 5 测试类定向回归）；③ 本轮代码与本文档未 git 提交，待用户确认后提交。

### 6.87 E2 b6 全量回归脚本落地：PS 版补 S8 场景 + shell 全量版（2026-08-13）

#### 1. 范围

- **背景**：b6 全量回归（E2 并发额度场景）此前只有 PS 版 S1-S7，缺 S8（并发额度派发即占用）；且无 macOS/Linux 可跑的 shell 版。本轮：① PS 版补 S8 场景；② 新建 `scripts/shell/verify-agenthub-duty-e2e.sh`（S1-S8 全量 zsh 版），与 §6.86 的 E2 实现配套。
- **明确不做**：真实 AI 接入（脚本用模拟 CLI_CLIENT Agent，无需外部 AI）；非 b6 场景的其他回归项。

#### 2. 实际落地

- **PS 版**（`verify-agenthub-duty-e2e.ps1`）：① `Invoke-Json` 扩展 DELETE 带 body（`SendAsync(HttpRequestMessage)` 兼容 PS 5.1，因 HttpClient 无 `DeleteAsync(Uri, HttpContent)` 重载）——任务级联删除接口需 `confirmTitle`；② S7 后插入 S8 段落（S8.0 残留清理 → S8.1 checkIn(maxConcurrent=1) → S8.2 建 t1 白名单自动派发（含 auto-assign-on-create 行为自检）→ S8.3 建 t2 断言满额保持 PENDING → S8.4 submitResult 释放后建 t3 断言重派回 → S8.5 并发建 t4/t5（Start-Job）断言在飞数 <=1 → S8.6 checkOut + 任务级联删除）；③ teardown 与头部注释同步更新。
- **shell 版**（新建 `verify-agenthub-duty-e2e.sh`，632 行）：S1-S8 全量 zsh 迁移（UTF-8 编码头 + `set -euo pipefail` + jq 解析 + `run_psql_one_row` eval 导出换行字段数组 + `http_request` 全局 HTTP_CODE/HTTP_BODY + 后台 curl 并发 + trap cleanup），风格对齐 `verify-dashboard-duty-leases.sh`。
- **S8 关键设计**：① 白名单隔离——任务 body 带 `agentPolicy.executorAgentIds=[本 agent]`，选人链只在白名单内，环境其他 ACTIVE Agent 不干扰断言；② 前置条件 `auto-assign-on-create=true`（默认 false）+ 脚本行为自检（t1 创建 2s 未派发则报错提示改配置）；③ 断言以 DB 为准（满额时 pickPreferred 返回 null 抛 BizException，HTTP 可能 500）；④ S8.0 残留清理用 COALESCE 子查询保证 psql 恒返回一行。

#### 3. 验证结果

- shell：`zsh -n` 语法通过；`chmod +x` 已设。
- PS：无 pwsh 环境（macOS），做 UTF-8 with BOM + 编码强制头 + 去字符串后括号配对粗检（{} / () / [] 全配对），完整语法需 Windows/pwsh 实测时确认。
- **真实环境实测（2026-08-13）**：docker 中间件 + fat jar 启动后端（`--helloai.dispatch.auto-assign-on-create=true` 启动参数覆盖，未改配置文件）→ `./scripts/shell/verify-agenthub-duty-e2e.sh` **ALL PASSED**（S1 checkIn / S2 checkOut / S3 DutyLeaseExpirationTask / S6 N12-P1 STRICT / S7 E1 dynamic TTL / S8 E2 concurrency quota，17 项断言 0 错误）。S8 关键验证点全绿：t1 白名单自动派发、t2 满额保持 PENDING（选人链软跳过）、submitResult 释放后 t3 重派、并发 t4/t5 双请求 HTTP 500 但 DB 在飞数恒 ≤1（FOR UPDATE 原子防线）。
- **实测发现的 shell 版 bug（已修）**：S8.0 残留清理的 COALESCE 空串技巧失效——无残留时 psql 输出空行，被 `run_psql_one_row` 的 `awk 'NF && ...'` 过滤成"无结果"导致 `fail "psql returned empty result"`。修复：COALESCE 改哨兵值 `'NONE'`（PS 版用 `if ($s80Line)` 判空无此问题，未改）；另 `fail()` 增加 `print ... >&2`——命令替换内 fail 时 stdout 被捕获，只有写 stderr 外层日志才可见（本次排错盲区的根因）。

#### 4. 影响与遗留

- 影响：① b6 全量回归（S1-S8）在 Windows 与 macOS/Linux 均有脚本可跑；② E2 并发额度场景具备可重复、环境无关的回归验证手段；③ 实测确认 E2 双防线（选人链软跳过 + FOR UPDATE 原子防线）在真实环境行为与单测一致。
- 遗留：① PS 版真实环境实测待有 Windows/pwsh 环境时执行；② 本轮代码与本文档未 git 提交，待用户确认后提交。

### 6.88 批次 b4 收口 + b5：agent 域 10 现有拆 + 3 归位 + shared/mq 2 拆 + b6 全量回归（2026-08-14）

#### 1. 范围

- **背景**：§6.84 遗留①（b4 剩余"10 现有拆"与"3 归位"）与 b5（shared DoorbellService + mq MessageDeduplicationService 拆）本轮全部收口，b4 批次完全关闭；b6 全量回归补跑（此前 §6.84/6.85 仅做了模块编译与定向测试，全量测试因 pom 默认 `skipTests=true` 未真正执行）。
- **明确不做**：不新增任何业务功能、不改数据库结构；不处理 §6.86 已交付的 `InFlightDbQuotaService` 命名形态（既有事实，保持不动）。

#### 2. 实际落地

- **b4 剩余 10 拆**（agent/service 既有单类形态 Service 全部拆接口 + impl）：AgentService / AgentOutboxService / AgentCommandOutboxService / AgentInboxService / AgentExecutionRecordService / AgentDutyLeaseService / AgentMcpServerService / ConversationService / AgentExecutionConnectivityService / AgentExecutionPreviewService。统一范式同 §6.84：接口放 `agent/service`（实体型 extends `IService<Entity>`，编排型不继承），impl 放 `agent/service/impl`（实体型 extends `ServiceImpl<Mapper, Entity>`，`@Service` + `@RequiredArgsConstructor`，方法级 `@Override` + `@Transactional` 保留在 impl）。
  - AgentService 为最大拆分（接口 26 方法 / impl 662 行），构造注入 8 依赖：SubTaskMapper / RewardLogMapper / ActivityLogMapper / ReviewRecordMapper / AgentInboxMapper / AgentDutyLeaseMapper / TaskTimelineService / AgentMcpServerService；保留"直接注入 Mapper 避免循环依赖"类注释；3 个 task 域 Mapper（RewardLog / ActivityLog / ReviewRecord）实际包路径为 `com.helloai.core.task.mapper`（非 agent.mapper，import 已修正）。
  - AgentMcpServerService.DEFAULT_EXECUTOR_TOOLS 收为 `private static final`（grep 确认全仓无外部引用）。
  - AgentCommandOutboxService 接口 9 方法（含 Phase 2H ②b 的 CONFIRMED 扩展：createPending / listReadyForRelay / listExpiredSentForRetry / markSent / markConfirmed / markFailed / markFailedFromSent / markFinalFailed / markFinalFailedFromSent），createPending 不加 @Transactional 的契约注释保留。
- **b4 3 归位**：
  - `ExternalAgentFailureTracker`：agent/service → `agent/observability`（与 CircuitBreakerEventRecorder 同包），9 个引用点 import 更新（4 main + 5 test）。
  - `WebSearchServiceRouter`：planner/service → `planner/search`（与 WebSearchResult 值对象同包），补 `import planner.service.WebSearchService` 接口，无外部引用。
  - `TaskRunningSpecDataMigrator`：确认 `task/spec` 为合法完整子域包（与 ExecutionRecord / TaskRunningSpec / TaskBaseline 同包协作），无需移动。
- **b5 2 拆**：
  - `DoorbellService`（shared/doorbell）拆接口（5 方法：connect / ring / disconnect / connectionCount / broadcastKeepalive）+ `DoorbellServiceImpl`（170 行，注入 DoorbellProperties / DoorbellRegistry / AgentDutyLeaseService / HeartbeatService，私有 refreshSeen / doSend）。
  - `MessageDeduplicationService`（helloai-mq）拆接口（3 方法：isDuplicate / markConsumed / markFailed）+ `MessageDeduplicationServiceImpl`（85 行，显式构造器与 DEDUP_KEY_PREFIX / DEDUP_TTL 常量保留）。
- **测试适配**：4 处构造点 `new XxxService(...)` → `new XxxServiceImpl(...)`（DoorbellServiceTest / AgentDutyLeaseAdaptiveTtlTest / AgentInboxServiceTest / AgentServiceTest）+ import 迁移；grep 全仓库确认无残留单类形态构造点。

#### 3. 验证结果

- 全量编译：`mvn compile` 7 模块（common / mq / core / job / api / start + 父 pom）**BUILD SUCCESS**。
- 全量测试：`mvn test -pl helloai-core,helloai-mq,helloai-job -DskipTests=false` **全绿**——core 全部测试类 + job 60 tests，Failures=0 / Errors=0 / Skipped=0；关键回归：DoorbellServiceTest 12 / DoorbellRegistryTest 7 / DoorbellRingerTest 4 / DoorbellDutyListenerTest 4 / DoorbellKeepaliveTaskTest 4 / AgentInboxServiceTest 6 / AgentServiceTest 6 / AgentDutyLeaseAdaptiveTtlTest 12 / ExternalAgentFailureTrackerTest 0（无测试方法，编译通过）/ ExecutionResultHandlerTest 4 + IntegrationTest 5 / AttachmentServiceImplTest 3（§6.85 附件单测一并回归）。
- 踩坑：① pom 默认 `skipTests=true`（§6.86 已记录），直接 `mvn test` 输出 "Tests are skipped." 假绿，必须 `-DskipTests=false`；② IDE 报"程序包 com.helloai.mq.service 不存在"为 Maven 项目模型未刷新（Maven 侧 test-compile 实际全绿），`mvn install -pl helloai-mq -am -DskipTests` 同步本地仓库后 IDE 可解析。

#### 4. 影响与遗留

- 影响：① b4 批次完全收口——agent/service 现为 21 接口 + 21 impl 完全成对，`{domain}.service.impl` 成为唯一业务逻辑实现位；② 3 归位完成——observability / planner/search 语义包纯净，无跨域残留；③ b5 完成——shared 与 mq 模块也纳入接口 + impl 范式；④ 全仓库无 `new XxxService(` 测试构造残留，测试全部依赖接口或 Impl 构造。
- 遗留：① §6.85 附件管理真实环境页面效果仍待用户验证（后端需重启加载新 list 回填逻辑）；② 本轮代码与本文档未 git 提交，待用户确认后提交。

### 6.89 LLM 供应商模型多选配置重构收口：V49 模型表 + 前后端多选配置 + e2e 38/38（2026-08-14）

#### 1. 范围

- **背景**：实施计划《LLM供应商模型多选配置重构》收口。需求：每个 Provider 可配置多个可用模型（Trae 式），必须有一个默认模型；内置 Provider 模型只可选不可改，自定义 Provider 支持任意模型名；同一模型在同一角色下全局唯一（跨 Provider 不冲突）。
- **本轮内容**：V49 迁移（模型表 + 内置种子 + 老 default_model 迁移）、后端模型管理全套（实体/Mapper/双 Service/QueryService + Admin 端点 + Agent 注册校验）、前端 Settings.vue 模型多选 UI（内置只读 + 自定义 + 默认模型下拉）、Agent 可用模型接口、单测补齐、e2e 脚本真实环境回归。
- **明确不做**：实施计划 4.4 连通性测试按钮（test-connection 端点）；4.3 前端注册弹窗的实时唯一性提示（后端强制校验兜底，前端仅展示服务端错误）；Agent 注册弹窗模型下拉本身沿用既有能力（§6.51 已交付的 Provider 选择链）。

#### 2. 实际落地

- **V49 `llm_provider_model` 表**：id/deleted/审计列 + provider_id/provider_code/model_name/is_default/enabled/sort_order；`uk_provider_model UNIQUE(provider_id, model_name)` + FK `ON DELETE CASCADE`（设计意图：删 Provider 级联删模型，但应用层逻辑删除下 FK 不触发，见 §3 修复③）+ 三个部分索引（enabled / default / code 查询）；内置种子 4 厂商 11 模型（deepseek-v4-flash/pro、kimi-k3~k2.5、qwen3.8-Max~3.6-Flash、MiniMax-M2.5，2026-08-14 官网口径）；老数据迁移：无模型记录的 Provider 将 `default_model` 迁为默认模型；`ON CONFLICT DO NOTHING` 保证幂等。
- **后端模型管理全套**：`LlmProviderModel` 实体（extends BaseEntity）+ `LlmProviderModelMapper`（含 `@Delete` 物理清理方法）+ `LlmProviderModelService/Impl`（saveProviderModels 批量多选 / setDefaultModel / addModel / deleteModel / toggleModel / validateProviderHasEnabledModels）+ `LlmProviderModelQueryService/Impl`（listByProviderId / listEnabledByProviderCode / isModelAvailable / findModelType）；`AdminLlmProviderController` 六个模型端点：`GET /{id}/models/list`、`POST /{id}/models`、`PUT /{id}/models/saveAll`、`DELETE /{id}/models/deleteByName/{modelName}`、`PUT /{id}/models/toggleByName/{modelName}`、`PUT /{id}/models/setDefaultByName/{modelName}`（实施计划 3.5 端点按 CODE_STYLE §8 动词形式落地）；`AgentController` 新增 `GET /api/agents/listAvailableModels`（目录接口过滤 available 厂商 + 有启用模型的 Provider，Agent 注册下拉用）；注册/编辑链路接入 `validateModelUniqueInRole`（实施计划 3.4：同 provider:model 同角色全局唯一，`AgentService.validateModelType` 提升为接口方法，格式/可用性/角色唯一性三段校验）。
- **前端**：`settings.ts` 新增模型管理 API + 类型（listModels / saveAllModels / addModel / deleteModel / toggleModel / setDefaultModel）；`Settings.vue` 模型多选区块——内置 Provider 模型 Checkbox 只读（仅展示预设模型）、自定义 Provider 支持输入回车添加任意模型、默认模型从已选模型单选、校验规则对齐实施计划六（至少一个模型 + 必有默认模型 + 内置只读）；Agent 注册相关类型 `modelType` 沿用。
- **单测**：`LlmProviderModelServiceImplTest`（saveProviderModels 空列表/默认不在列表/正常保存、setDefaultModel 未启用拒绝、addModel、deleteModel 默认/最后一个拒绝、toggleModel 含最后启用保护）+ `LlmProviderModelQueryServiceImplTest` + `LlmProviderServiceTest` 补模型校验 + `AgentServiceTest` 补 validateModelUniqueInRole；共 48 个全部通过。
- **e2e 脚本**：`scripts/powershell/verify-llm-provider-models.ps1`（S0-S11：列表/创建/模型增删改/默认模型/启用禁用/角色唯一性/脏注册拒绝/saveAll 幂等/Provider 重建），遵循规则 6 UTF-8 with BOM + 单引号拼接 + `Parser.ParseFile` 自检。

#### 3. 验证结果

- 后端 `mvn test -DskipTests=false` 相关测试类全绿（48 个），前端 `vue-tsc` 0 error；重启后端后 ps1 脚本 **38 PASS / 0 FAIL ALL PASSED**（S0-S11 全场景，含重跑幂等验证）。
- 本轮修复 6 个缺陷：
  ① **404 尾斜杠**：`AdminLlmProviderController` `@PostMapping("/")` 在 Spring 6 PathPatternParser 下只匹配带斜杠路径 → 改 `@PostMapping`，手动 POST 验证 CREATE_OK；
  ② **物理唯一约束 vs 逻辑删除**（V50）：`uk_provider_model UNIQUE(provider_id, model_name)` 与 MyBatis-Plus 逻辑删除冲突——软删模型后重建同名 INSERT duplicate key 500 → 删约束改部分唯一索引 `uk_provider_model_active ... WHERE deleted = 0`（saveAll 幂等重跑安全）；
  ③ **同源修复**（V51）：`uk_llm_provider_code` 同样冲突（软删 Provider 无法重建同 code）→ 部分唯一索引 `uk_llm_provider_code_active ... WHERE deleted = 0`；
  ④ **注册脏数据**：registerOrGet 先创建 Agent 再 applyRegistrationExtras 校验，modelType 校验失败留脏 Agent → `validateModelType` 接口化 + AgentController 注册前预校验；
  ⑤ **toggleModel 保护漏洞**：原只保护默认模型，不禁用非默认的最后一个启用模型 → 改为通用“最后一个启用模型”检查（与 deleteModel 语义一致）+ 单测补 1 例；
  ⑥ **deleteById 级联**：Provider 软删不触发 FK CASCADE（逻辑删除是 UPDATE），模型记录残留导致 `isModelAvailable` 误判 → `deletePhysicalByProviderId` 物理清理 + 单测覆盖。

#### 4. 影响与遗留

- 影响：① Provider 模型从单 default_model 升级为多选关联表，Agent 注册/编辑与平台模型配置共用 `llm_provider_model` 口径；② 内置 Provider 模型列表由 V49 种子固定（后续官网更新走新迁移，与实施计划八风险缓解一致）；③ 角色模型唯一性收紧为服务端强制校验（注册/编辑两条入口）。
- 遗留：① e2e 脚本产生的 probe 残留数据（probe-404-check / probe-addmodel-debug / probe-saveall-idem 等软删记录）待用户执行清理 SQL（本轮已交付）；② 本轮代码与本文档未 git 提交，待用户确认后提交。

### 6.90 MQ 消息格式链路修复：Java 序列化 → 显式 JSON + NotificationConsumer 手动 ACK（2026-08-14）

#### 1. 范围

- **背景**：启动后 RabbitMQ 消费者持续报 `ListenerExecutionFailedException: Failed to convert message`，根因是队列积压旧格式 Java 序列化消息（Phase 2F 前 `convertAndSend(POJO/Map)` 遗留，body 为 `LinkedHashMap` 的 `application/x-java-serialized-object`），当前消费端反序列化被安全白名单拦截（`SecurityException: Attempt to deserialize unauthorized class java.util.LinkedHashMap`），listener 转换层即失败（方法体不执行、代码内 ACK 兜底无效）→ 无限 requeue 刷日志 → 30 分钟 ack 超时后 channel 被 broker 关闭。
- **本轮内容**：① 停应用 → `rabbitmqctl purge_queue` 清理 reviewer/executor/planner 三队列积压旧消息 → 命令行重启；② 排查发现当前代码仍存在两条 Java 序列化/ack 缺陷路径，一并修复（见 §2）；③ 修复后完整闭环验证（生产端 JSON 发出 → 消费端解析 → 幂等 → 手动 ACK → 队列清零）。
- **明确不做**：不动全局 RabbitTemplate converter（避免波及其他 RabbitListener，与 Phase 2F 修正原则一致）；不改 reviewer/executor/planner 消费端（已按 JSON 解析，天然兼容）；不改调度/执行链逻辑。

#### 2. 实际落地

- **清理**：`rabbitmqctl purge_queue` 清空 `helloai.reviewer.queue`（7 条）/ `helloai.executor.queue`（6 条）/ `helloai.planner.queue`（7 条）积压；后端以 `~/.jdks/ms-17.0.19/bin/java.exe -jar` 全路径重启（系统 PATH java 为失效 stub）。
- **修复①生产端 `DomainEventPublisher`**：原 `convertAndSend(Map)` 走 SimpleMessageConverter Java 序列化 → 改为显式 `ObjectMapper.writeValueAsBytes` + `RabbitTemplate.send` + ContentType JSON + PERSISTENT（与 Phase 2F `ExecutionCommandMqPublisher` 修正同款，Javadoc 注明修正原因）；调用方 `AgentEventCompensationTask` 签名不变。
- **修复②消费端 `NotificationConsumer`**：原 `onNotification(Map)` 方法签名依赖 SimpleMessageConverter 反序列化（Java 序列化），且未显式 ackMode 继承全局 `spring.rabbitmq.listener.simple.acknowledge-mode: manual`（application.yml）却从不调 `basicAck` → 消息永久 unacked（此前队列无消息未暴露）→ 改为 `(Message, Channel, @Header DELIVERY_TAG)` + `ackMode = "MANUAL"` + JSON 解析（解析失败/缺 eventId 直接 ACK，消费失败 NACK 不重投走 DLX，与 `MqReviewCommandConsumer` 同款）。

#### 3. 验证结果

- 重启后 `GET /api/health` 200；日志无任何 `Failed to convert message` / `SecurityException`；全队列 0 积压、消费者在线（reviewer/execution-command/notification 各 5）。
- 闭环验证（真实链路）：向 `agent_outbox_event` 插入 PENDING 测试行（routing_key=`agent.notification.test`）→ 补偿任务 15s 轮询发出 JSON（日志 `Publishing event ... bodyBytes=69`）→ `NotificationConsumer` 消费（elapsed=7ms）→ outbox 行 status=SUCCESS → 队列 unacked=0（ACK 生效）。幂等验证：重启后旧 unacked 消息 requeue 再消费被幂等跳过并 ACK。测试数据（outbox 行 + event_consumption_log 记录）已清理。
- 打包验证：`mvn -pl helloai-mq,helloai-job,helloai-start -am -DskipTests package` 通过（编译期即暴露 NotificationConsumer 残留声明，修复后 0 error）。

#### 4. 影响与遗留

- 影响：① 领域事件生产端统一 JSON 序列化，消费端全部按 JSON 解析，消除 SecurityException 复发路径（outbox PENDING 再出现也不复发）；② NotificationConsumer 补齐手动 ACK，消除 unacked 累积；③ 消费失败语义对齐：坏消息 ACK 不阻塞队列、业务失败 NACK 走 DLX。
- 遗留：① 历史 FAILED outbox 残留（agent_outbox_event status=2 与 agent_command_outbox status=3 共百余条）未清理，属历史失败快照，不影响链路；② executor/planner 队列当前无消费者（历史 agent.exchange 绑定），本轮只清积压未改拓扑；③ 本轮代码与本文档未 git 提交，待用户确认后提交。

### 6.91 版本测试准备：注册选模型前端最小改动 + 同角色同模型唯一性实测 + 全量清理 SQL 交付（2026-08-14）

#### 1. 范围

- **背景**：用户计划进行一次版本测试，需先清理全部业务数据（但不包括 credential_vault 的 api-key 信息与 sys_user 表 admin 信息）；同时补齐注册新 Agent 的前端功能——内部 LLM（API_KEY_LLM）注册时必须能选择模型，且同一角色同一模型不能重复注册（如 deepseek-v4-flash 不能出现两个 PLANNER，但可同时存在 deepseek-v4-flash 与 deepseek-v4-pro 的 PLANNER）。
- **本轮内容**：① 前端注册弹窗模型分组下拉（最小改动，后端 V49 链路零改动）；② 同角色同模型唯一性约束实测确认（后端 V49 `validateModelUniqueInRole` 已实现，本轮实测验证）；③ 注册失败业务提示前端修复（BizException 以 HTTP 500 返回时拦截器只显示笼统错误）；④ 版本测试全量清理 SQL 交付。
- **明确不做**：不动后端注册链路（V49 已完整）；不做编辑弹窗模型选择、连通性测试按钮、Settings 模型管理页改动；不代执行数据库写操作（清理 SQL 由用户执行）。

#### 2. 实际落地

- **agent.ts**：新增 `AvailableModelGroup` 接口（providerCode/providerName/defaultModel/models）+ `listAvailableModels()`，对接后端 V49 既有 `GET /api/agents/listAvailableModels`。
- **AgentList.vue**：注册弹窗在 `accessType === 'API_KEY_LLM'` 时显示模型分组下拉（`el-option-group` 按 Provider 分组，value 为 `providerCode:modelName`，clearable + filterable，留空走系统默认 provider+default-model，兼容 §6.74 口径）；`watch(registerDialog)` 打开时加载模型目录；`handleRegister` 传 `modelType`；表单重置补 `modelType=''`。
- **request.ts**：response 拦截器 error 分支补 `error.response?.data?.msg` 提取——后端业务异常（BizException）以 HTTP 4xx/5xx 返回时优先展示 R 包裹体里的中文 msg（此前只显示 `Request failed with status code 500`，注册模型唯一性校验提示不可见）。
- **同角色同模型唯一性（确认已有，零改动）**：`AgentServiceImpl.validateModelUniqueInRole`（V49）按 `role + accessType=API_KEY_LLM + modelType + deleted=0` 查重，命中抛 `角色 X 已存在使用模型 Y 的Agent，同一模型在同一角色下只能注册一个`；`AgentController.register` L64 创建前预校验（失败不落脏数据）+ `applyRegistrationExtras` 兜底，语义与用户要求完全一致（同角色同模型唯一、同角色不同模型允许）。
- **清理 SQL**：`tmp/cleanup-business-data-20260814.sql` 事务包裹 21 表 ~5900 行（任务域 12 表 / Agent 域 4 表 / 需求对话 2 表 / MQ 流水 3 表），按外键依赖排序；**保留** credential_vault 全部 79 条（AGENT 74 + PLATFORM 5，版本测试需要 api-key）+ sys_user admin + llm_provider/llm_provider_model/sys_config。

#### 3. 验证结果

- `npm run build` 通过（vue-tsc 0 error + vite build 23.75s）。
- API 实测 `GET /api/agents/listAvailableModels`：4 供应商 11 模型（deepseek 2 / moonshot 5 / minimax 1 / dashscope 3）。
- 角色模型唯一性实测（`.tmp/verify-role-model-unique2.ps1`，curl + body 文件规避 PS 5.1 引号剥离）：S1 第二个 PLANNER+deepseek-v4-flash 被拒（msg=`角色 PLANNER 已存在使用模型 deepseek-v4-flash 的Agent...`）✅；S2 PLANNER+deepseek-v4-pro 注册成功（同角色不同模型允许）✅；S3 第二个 PLANNER+deepseek-v4-pro 被拒 ✅。
- 实测产生的 2 条 probe-uq-* agent（PLANNER+flash / PLANNER+pro）已确认落库，随清理 SQL 一并清除。

#### 4. 影响与遗留

- 影响：① 前端注册内部 LLM 可选模型，留空仍走系统默认（与 §6.74 兼容）；② 同角色同模型唯一性为服务端强制校验（注册/编辑两入口），前端通过拦截器修复可见完整中文提示；③ 版本测试前清理 SQL 已就绪，用户执行后即可从零态冒烟。
- 遗留：① 清理 SQL 待用户执行（`docker cp` + `docker exec psql -f`，执行后 DELETE 计数反馈即开始完结校验：数据库空态 → 后端健康 → MQ 队列归零 → 从零链路冒烟）；② 本轮代码与本文档未 git 提交，待用户确认后提交。

### 6.92 V52 技能能力校验 e2e 收口：getById skills 修复 + e2e 脚本 UTF-8 body 编码修复（2026-08-14）

#### 1. 范围

- **背景**：V52 技能能力驱动校验链路（显式技能白名单 / 自定义豁免 / 未识别模型放行 / 关键词兜底）此前已有实现与 e2e 脚本，但 e2e 存在两处未闭环：① `AdminAgentController.getById` 未回填 `skills`（V52 引入后 getById 返回空，脚本被迫走列表接口 fallback）；② 脚本 `Invoke-Api` 用 PS 5.1 字符串直接作 `-Body` 发送，中文 body（如描述"负责代码审查与联网检索"）被按 ANSI 编码转换，后端收到 `??` 乱码，关键词兜底永不命中，S5 断言必败。
- **本轮内容**：① getById 回填 skills；② 脚本发送中文 body 改为 UTF-8 字节数组；③ 词表补"检索/联网"（与"搜索"同义映射 web-search，原词表已有"搜索"即可满足，补词仅为更完整）。
- **明确不做**：不动 `deriveWithCapabilities` 推导逻辑与校验语义；不改前端；不做数据库变更。

#### 2. 实际落地

- **AdminAgentController.getById / AgentListItemVO**：`getById` 组装响应时 `setSkills(agent.getSkills())`（此前字段未回填导致 getById 恒为空，列表接口 skill 字段正常）。修复后 e2e 的 `Get-AgentSkills` 直读 getById，删除 fallback 依赖。
- **AgentSkillDeriver.keywordSkills()**：新增 `map.put("检索", "web-search")`、`map.put("联网", "web-search")`。
- **verify-agent-skill-capability.ps1**：`Invoke-Api` 中 body 改为 `$script:Utf8NoBom.GetBytes($BodyJson)` 字节数组发送（`Utf8NoBom = New-Object System.Text.UTF8Encoding($false)`），头部按规则 6 补 `[Console]::InputEncoding` 与 `$OutputEncoding = Utf8NoBom`。

#### 3. 验证结果

- 定位过程关键证据：getById 修复生效（skills 非空）但纯中文描述仍未推导 → 用"只做search联网"探测，getById 显示 `description:"??search??"` + `skills:[thinking,code-review,web-search]` → 坐实脚本发送层中文损坏（`??`），非后端逻辑问题；`javap` 反编译 `AgentSkillDeriver.class` 确认运行类含"检索/搜索/联网"词条与 `deriveWithCapabilities`（含净化 lambda），排除编译产物陈旧。
- 修复后 e2e 重跑：**28/28 ALL PASSED**（S0 登录 / S1 deepseek+shell / S2 kimi+web-search / S3 deepseek+web-search 拒绝 / S4 自定义技能豁免 / S5 kimi 无显式技能 → 描述"负责代码审查与联网检索"推导出 thinking,code-review,web-search / S6 编辑拒绝 / S7 换模型+双技能 / S8 清理）。
- 控制台仍见 `不支持技?` 尾字显示乱码（PS 5.1 管道重定向层 artifact，断言基于内存字符串匹配已通过，不影响判定）。

#### 4. 影响与遗留

- 影响：① getById 返回 skills 后，管理端详情展示与脚本断言均可直读；② e2e 脚本对含中文 body 的请求统一走 UTF-8 字节发送（规则 6 在 HTTP 发送层的落地，与 verify-agenthub-duty-e2e.ps1 的 Run-Psql 剥离 BOM 范式互补）；③ 词表"检索/联网"补齐后描述含这些词即可推导 web-search。
- 遗留：① 本轮代码与本文档未 git 提交，待用户确认后提交；② 其余 verify-*.ps1 若仍以字符串 -Body 发送中文，后续遇到同类"后端收到乱码"问题应优先按本轮范式修复。

### 6.93 执行产出物化方案3 + Reviewer 附件内容级核验（F1-F3 全链路收口，2026-08-14）

#### 1. 范围

- **背景**：执行产出物化设计文档（§6.27 编写）的方案2 已于 2026-07-31 落地（§6.30），方案3（LLM manifest 结构化多文件协议）与核验侧"Reviewer 只看产出文本、看不到物化附件正文"一直是遗留缺口——Reviewer 的核验 Prompt 不含附件内容，无法做"声称交付物 ↔ 文件正文 ↔ 验收标准"的内容级核验。
- **本轮内容**（按 .qoder/plans/产出物化方案3与Reviewer内容级核验_a4f2c9d7.md 依次执行）：① F1 交付侧——manifest DTO + `ExecutionOutputParser` 扩展 + `ParsedOutput.displayText` + `buildUserPrompt` 追加 manifest 协议指令 + `ExecutionResultHandler` 挂接 displayText；② F2 核验侧——`buildAttachmentContent` + Prompt 模板占位 + 每附件 8000 / 总计 24000 字符限额 + 核验 Prompt 组装接线核验；③ F3 收口——e2e 脚本 `verify-artifact-content-review.ps1` 真实环境全绿 + 本文档回填。
- **明确不做**：物化存储链不动（沿用 §6.30 物化 + §6.75 MinIO 主存储 + §6.77 e2e）；不改核验触发条件与 checkEvidence 判定语义；不改 `attachment` 表结构；不做前端改动。

#### 2. 实际落地

- **F1.1 manifest DTO + 解析扩展**：`Manifest`（summary + files）/ `ManifestFile`（name/type/content）record 放 `agent/output`；`ExecutionOutputParser.parse` 扩展——从 raw 提取 ```json 围栏内 JSON 对象（复用 `SubTaskReviewService.stripToJsonObject` 同款剥离思路，`@JsonIgnoreProperties(ignoreUnknown=true)` 容忍多余字段），命中且 files 非空 → 多文件结构化形态；未命中 / files 空 / JSON 非法 → 降级纯文本单 .md（方案2 形态不变）。`ParsedOutput` 重构为 `(files, displayText)` 双字段：结构化时 `displayText = summary + "## 产出文件概览" + "- {name}" 逐行 + JSON 块之后尾部文本（EXECUTION_RECORD 回填块保留）`，纯文本时 `displayText = raw`。
- **F1.2 Prompt 协议指令**：`SubTaskExecutionServiceImpl.buildUserPrompt` 在"产出回填要求"段后追加**可选**指令——"可以用如下 JSON 结构返回多文件产出（放在 ```json 代码块中，位于 EXECUTION_RECORD 块之前）；若无需拆分文件，直接输出正文即可"；共存格式约定 manifest JSON 块在前、EXECUTION_RECORD 回填块在后（既有回填要求不变）。
- **F1.3 挂接 displayText**：`ExecutionResultHandler` 构造器注入 `ExecutionOutputParser`，物化开启（`helloai.storage.enabled`）时 `lastExecution.output` 与对话流 `sub_task_execute` 写 displayText（对话流不刷文件正文），关闭时保持原文；afterCommit 物化链不变（`ExecutionArtifactServiceImpl.materialize` 内部走 parser，多文件自动逐条物化）。
- **F2.1 附件内容注入**：`SubTaskReviewServiceImpl.buildAttachmentContent(subTask)`——按 sub_task_id 查可直读附件（attachment 表，isContentLoadable），逐附件输出 `### {fileName}` 节 + 正文，每附件 8000 字符截断标注、总计 24000 字符停止注入后续附件正文；不可直读 / 读取失败 / 为空 → 显式标注"内容不可读/为空"（不臆断）。`prompts/subtask-review.md` 新增「## 物化附件内容（平台直读，已按限额截断）」节 + `{{ATTACHMENT_CONTENT}}` 占位符 + 第 10 条判定规则："声称交付物 ↔ 文件正文 ↔ 验收标准"三者一致性是判定依据，附件正文与声称结论矛盾或标注不可读时不得臆断。
- **F2.2 接线核验**：核验 Prompt 组装处 `{{ATTACHMENT_CONTENT}}` 由 `buildAttachmentContent(subTask)` 替换；核验 Prompt 在 LLM 调用成功后落库 `conversation_message`（tool_name=`subtask_review_prompt`），供审计与 e2e 断言。
- **F3.1 e2e 脚本**：`scripts/powershell/verify-artifact-content-review.ps1`（规则 6 UTF-8 头模板 + `Add-Type -AssemblyName System.Net.Http` + HttpClient；S0 pre-flight → S1 admin 登录 → S2 agent 复用 → S3 task+t1 幂等清理 → S4 claim → S5 submitResult manifest 产出 → S6 多附件物化断言（2 附件 / mime / size / minio:// / 各自可下载且内容匹配 / displayText 含概览不含 JSON 与文件体）→ S7 核验 Prompt 断言（环境无绑定 vault 的 REVIEWER/PLANNER agent 时 SKIP 兜底）→ S8 纯文本降级回归（单 .md + output 原样）→ S9 teardown 级联删除）。

#### 3. 验证结果

- 单测/编译：`ExecutionResultHandlerIntegrationTest` 补 `new ExecutionOutputParser()` 构造器参数后全量 test-compile BUILD SUCCESS + package BUILD SUCCESS。
- 后端启动链：PATH 的 `javapath` 转发器在沙箱下崩溃（0xC0000409），改用 `JAVA_HOME`（`~/.jdks/ms-17.0.19`）完整路径 `java.exe -jar` 启动成功，health 200。
- e2e 真实环境重跑（runTag 20260814）：**PASS=23 FAIL=0 SKIP=1 ALL PASSED**——S6 manifest 多文件物化全过（README.md text/markdown size=39 + main.py text/x-python，storage_url 均 `minio://helloai-artifacts/{owner}/{yyyy}/{MM}/{taskId}/{subTaskId}/{uuid8}-{name}`，下载 200 且正文含 'echo hello from readme' / 'hello from main'，displayText 含 '## 产出文件概览' + '- README.md' + '- main.py' + EXECUTION_RECORD 尾、不含原始 JSON 与文件体）；S7 SKIP（当前环境无绑定 vault 凭证的 REVIEWER/PLANNER agent，脚本自检 SQL 判 SKIP 兜底，绑定后重跑可断言 Prompt 注入）；S8 降级回归全过（纯文本 → 1 个 `{title}.md` text/markdown + 对话流 output 原样无概览）；S9 级联删除后 sub_task 残留 0。
- 调试要点（沉淀）：① 子任务详情接口是 `GET /api/sub-tasks/getById/{id}`（`/api/sub-tasks/{id}` 返回 404 非 401，带 token 复现确认）；② `downloadById` 返回 `application/octet-stream`，PS 5.1 `Invoke-WebRequest` 的 `Content` 是 byte[]，须 `[System.Text.Encoding]::UTF8.GetString` 后再断言（直接 `.Contains(string)` 恒 false）；③ 外层 shell 执行 `-Command` 会吞 `$` 变量，调试一律走脚本文件。

#### 4. 影响与遗留

- 影响：① LLM 可按 manifest 协议一次产出多文件（README/main.py/config.json 等），平台物化多附件、各自可下载；② Reviewer 核验 Prompt 注入物化附件正文（限额截断），内容级核验（声称交付物 ↔ 文件正文 ↔ 验收标准）具备事实基础；③ 对话流不再刷 manifest JSON 与文件正文（displayText 概览）。
- 遗留：① S7 核验 Prompt 内容断言待绑定 REVIEWER/PLANNER agent 的 vault 凭证（API_KEY_LLM + ACTIVE）后实测（脚本自检 SQL 命中即自动执行断言，无需改脚本）；② 本轮代码与本文档未 git 提交，待用户确认后提交；③ tmp 调试脚本（debug-*.ps1 / check-parse.ps1）与 e2e 日志为临时资产，可清理。

### 6.94 M5 场景 1：happy path 真实 AI 自主闭环（2026-08-14）

#### 1. 范围

- **背景**：差距表 N14 / M5 场景矩阵场景 1（happy path）——「真实外部 AI 自主理解 SKILL.md、按规则完成注册→值班→感知→认领→执行→提交→签退全环」一直未实测：此前均为脚本化闭环（verify-onboarding-submit.ps1 / verify-mcp-e2e.ps1 固定步骤）或仅打卡链路（M4），缺真实 AI 在协议细节上的自主决策实证。
- **本次落地**：本会话 AI（Qoder）作为真实 EXECUTOR，通读 `helloai-core/src/main/resources/skills/executor/SKILL.md` 后按 §1.3 推荐工作循环逐步决策、逐步调用真实接口完成全环；管理员侧仅建任务与 PENDING 子任务（不指派），执行侧全部自主。
- **明确不做**：不写新代码、不改后端行为（纯协议链实证）；不做 blocked / 超时替补 / 附件 / 双值班场景（场景 2~5 留待后续）；不启动外部 LLM 核验（REVIEW 流转即可，核验链另见 §6.93）。

#### 2. 实际落地（执行链实录）

- **S0 管理员登录**：`POST /api/auth/login`（admin/admin123）→ adminToken。
- **S1 自助注册**：`POST /api/agents/register`（body `{name:h1-qoder-executor, role:EXECUTOR, description}`）→ `data.id=2088261489367584770` + `data.apiKey`（注册返回即 apiKey，无需二次签发）。
- **S2 getAgentStatus 自检**（SKILL.md 1.3 step 1）：`ACTIVE` 账户 + `dbOnlineStatus=OFFLINE`（未打卡，符合预期）→ 决策：先打卡。
- **S3 checkIn 打卡**（1.3 step 2）：`{workMode:AUTO, maxConcurrent:3, ttlMinutes:30}` → `ok=true`，`leaseId=2088261655818539009`，`expiresAt=21:49:11+30min`（ACTIVE 租约）。
- **S4 pullTasks 值守（1）**：`messages:[]`（空收件箱，值守中）。
- **S5 管理员建任务 + 子任务**：taskId=2088261744993636353（agentPolicy.executorAgentIds 白名单）；subTaskId=2088261745186574337（**PENDING + assignedAgentId=null，不指派**）。
- **S6 pullTasks 值守（2）**：仍 `messages:[]` → **自主决策**：PENDING 未指派任务不进收件箱，改走 SKILL.md §0.2 可认领通道：`GET /api/sub-tasks/listAvailable` 确认子任务可见 → `claimSubTask` 原子认领。
- **S7 claimSubTask**：`{ok:true, claimed:true, assignedAgent:本人, version:1}`（PENDING→ASSIGNED）。
- **S8 getDepsSummary**：`depCount=0`（无前置，无需拉上游产出）。
- **S9 heartbeat + uploadArtifact**：heartbeat `onDuty=true` 且 **remainingTtlSeconds=14399（≈4h）**——实证 E1 动态 TTL + A0-8 自动续约：认领在跑子任务后 `adaptiveRenew` 取 `maxTtlMinutes=240` 长窗口（`AgentDutyLeaseServiceImpl.hasInFlightSubTask` 分支），执行期无需手动重打卡；uploadArtifact 登记 `execution-notes.md` 元数据（attachmentId=2088262013366177793）。
- **S10 submitResult（manifest 多文件）**：`{accepted:true, resultId:h1-happy-20260814215116123}` → 状态机流转 **REVIEW**，afterCommit 物化 2 附件：protocol-notes.md（152B）+ sample.py（85B），objectKey 按 `{owner}/{yyyy}/{MM}/{taskId}/{subTaskId}/{uuid8}-{name}` 组织。
- **S11 checkOut 签退**（1.3 结束）：`{ok:true, closedCount:1, currentStatus:CLOSED}`，租约 DB 行 status=CLOSED（close_reason=shutdown）。
- **S12 teardown**：`DELETE /api/tasks/deleteById/{taskId}` 级联删除（subTaskCount=1 / timelineCount=2 清理，0 残留）。

#### 3. 验证结果

- **状态机全链**：PENDING →（claimSubTask）→ ASSIGNED →（submitResult）→ REVIEW；sub_task 行 version=4、assigned_agent_id=2088261489367584770、rework_count=0。
- **物化证据**：2 附件下载 200 且内容逐字匹配（protocol-notes.md 含 'pullTasks is the only task sensing channel'；sample.py 含 'h1 happy path sample'）；对话流 `sub_task_execute` displayText = summary + '## 产出文件概览' + '- protocol-notes.md' + '- sample.py' + EXECUTION_RECORD 尾部（不刷 manifest JSON 与文件正文，与 §6.93 F1 一致）。
- **时间线事件**：`sub_task_execute_submit`（payload 含 idempotencyKey=h1-happy-20260814215116123、source=EXTERNAL）+ `sub_task_artifact_materialized`（count=2、fileNames 列表）。
- **在线与租约**：agent 行 `online_status=ONLINE`、last_seen_time/last_active_time 随工具调用刷新（HeartbeatServiceImpl 双写契约）；租约 ACTIVE 期间 expire_time 随 heartbeat 续延。
- **协议事实（复验确认，非 bug）**：① 自主认领走 `claimAtomic` 原子 SQL 直改状态，**不触发 `notifyStatusChange` 的 `sub_task.assigned` 收件箱消息**——pullTasks 在 claim 前后均空为预期行为，ack 步骤仅适用于管理员指派通道；② submitResult→REVIEW 时平台向全部 PLANNER 发 `sub_task.review` 通知（本环境 1 个 PLANNER agent：v52-e2e-ds-bad，teardown unreadInboxCount 计数佐证）；③ 任务感知双通道：指派消息走 inbox+pullTasks，自主认领走 listAvailable+claimSubTask。

#### 4. 影响与遗留

- 影响：① 场景 1 已勾除，「真实 AI 自主理解 SKILL.md 按规则执行」实证成立（含空收件箱→切换 listAvailable 通道、无消息跳过 ack、无依赖跳过依赖注入三处自主决策）；② E1 动态 TTL 执行期长窗口与 A0-8 自动续约在真实调用链上得到佐证；③ SKILL.md 协议文档与代码行为在「自主认领无收件箱消息」点上存在文档口径差异（SKILL.md 将 pullTasks 描述为唯一任务感知通道），已在本节记录协议事实，SKILL.md 口径优化留待后续批次。
- 遗留：① 场景 2 blocked path / 3 超时替补 / 4 附件 path / 5 双 Agent 值班未开始；② 本轮无代码改动，仅文档回填（项目进度 M5、差距表 N14、本条目），未 git 提交；③ tmp 驱动脚本（h1-happy-path.ps1 / h1-recheck-inbox.ps1 / h1-state.json / q-agent-status.ps1）为临时资产，已清理。

### 6.95 购物车任务实战复盘：Reviewer 内容级核验真实读取附件实证（2026-08-16）

#### 1. 范围

- **背景**：2026-08-15 用户以 Trae 作为真实外部 EXECUTOR（agent=trae-excutor，CLI_CLIENT，人工注册）开定时任务自主轮询完成真实任务「修复购物车页面进入时仅选中第一个商品的 bug」（taskId=2088630823147409409），5 子任务全 DONE；2026-08-16 应要求整体复盘，重点核查「REVIEW 角色审查任务时是否真正读取了附件」——即 §6.93 方案3 F2 内容级核验在真实任务中的实战验证。
- **本次落地**：只读取证（DB 查询 + conversation_message 核验 Prompt 原文比对 + downloadById 实测），无代码改动。
- **明确不做**：不修改代码与协议行为；不启动 M5 场景 2~5（blocked / 超时替补 / 附件 / 双值班）。

#### 2. 实际落地（取证链）

- **任务全链**：5 子任务（2088631218330537986~90）全 DONE，执行者 trae-excutor；审查者 inner-deepseek-pro-reviewer（REVIEWER / API_KEY_LLM / deepseek:deepseek-v4-pro）；review_record 7 条（2 REJECTED + 5 APPROVED）；task_timeline 33 事件全链无断链（clarify → plan → 5×submit/物化/审查 → task_auto_completed → final_report 27722 字符/5 段）；attachment 21 条（物化链 .md ×7 + uploadArtifact 登记 ×14）。
- **四层证据链（Reviewer 确实读取附件内容）**：
  1. 代码链路：`SubTaskReviewServiceImpl.buildAttachmentContent` → `readAttachmentContent` → `attachmentService.loadContent(id)` → `artifactStorage.load(storageUrl)`——真实读 MinIO 字节后注入核验 Prompt（每附件 8000 / 总计 24000 字符限额），非文件名/摘要臆断；
  2. Round1 核验 Prompt 原文（conversation_message，content_type=text，platform 来源）：物化 `.md` 正文完整注入（EXECUTION_RECORD + VERIFICATION 验证输出 + 前端构建日志），而 cart-index.vue / cart-init-selection-fix.patch / 两个验证脚本**均标注「内容不可读/为空」**；
  3. Round1 verdict（inner-deepseek-pro-reviewer）：「关键交付物 cart-index.vue 和 cart-init-selection-fix.patch 的物化附件均标注为“内容不可读/为空”，两个验证脚本也无法读取。无法核对初始化逻辑是否覆盖全部商品、修改范围是否仅限购物车」→ **REJECTED score=2**——完全符合 Prompt 模板规则 10（附件标注不可读时不得臆断文件内容、从严判定）；
  4. Round2 Prompt 原文：cart-index.vue **完整源码注入**（`<template><el-card>…我的购物车`）+ 物化 md 含 **git diff 原文**（`created() { this._syncing = false …`、`syncTableSelection() … _syncing = true`）；verdict analysis 引用 diff 细节（「diff 显示 syncTableSelection() 改为先 clearSelection…」）→ **APPROVED score=4**；downloadById 实测 6 附件现全部 HTTP 200（对象已补传，与 Reviewer 同一条 loadContent 链路）。
- **实战拦截案例**：子任务 2088631218330537988 Round1 的 4 个文件为 `uploadArtifact` **纯登记**（storage_url=minio:// 但 MinIO 对象当时未上传，物化事件仅含 .md），Reviewer 读不到内容即驳回；Trae 补传对象 + 补交物化 md（含完整 diff）后 Round2 通过——A0-5 fail-close 在真实任务中拦截「声称有附件但内容读不到」的提交。

#### 3. 验证结果

- **Reviewer 内容级核验链路真实生效**：真实读取附件字节；读不到的明确标注并拒绝放行（不得臆断），读到的用于逐条核对（verdict analysis 引用文件正文与 diff 细节）。
- **整体检查**：任务 DONE + final report 生成；5 子任务全 DONE；2 次返工闭环（7986 声称交付 verify-cart-selection.js 但附件仅 .md → 驳回补交 → 通过；7988 附件内容不可读 → 补传+补交 → 通过）；时间线无断链；review_record 与 timeline 一致。

#### 4. 影响与遗留

- 影响：① 方案3 F2 + A0-5 在真实外部 AI 任务上完成实战闭环验证（§6.93 e2e 之外的活体案例，且首次实测 inner-deepseek-pro-reviewer 真实审查）；② uploadArtifact「纯登记、不校验 MinIO 对象存在」语义被内容级核验正确兜住（fail-close 实战价值）；③ 观察项（非 bug）：附件清单「平台可直读」（isContentLoadable 仅查 storageUrl scheme）与正文「内容不可读/为空」并存，对执行者略有误导——Reviewer 判定正确，可选优化为清单标注区分「可直读-已验证」。
- 遗留：① M5 场景 2 blocked / 3 超时替补 / 4 附件 path / 5 双值班未测；② 本轮无代码改动，文档回填（本条目 + 差距表 N14 + 项目进度 M5）随 F 批次（§6.93 代码）一并 git 提交（含上轮 §6.94 未提交的文档改动）；③ 场景 2 预置脚本 tmp/prepare-scene2.ps1 为临时资产（已登记 agent 凭证，未提交）。
