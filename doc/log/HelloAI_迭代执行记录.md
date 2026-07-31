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

