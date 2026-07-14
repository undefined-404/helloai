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

### 2026-07-13 Phase 2A N6 DB Poller 落地 — §5.1 阶段一收官

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

- §5.1 阶段一 四项工作全部落地完成：
  - ✅ DB Poller 消费载体（本轮）
  - ✅ SubTaskExecutionService 编排职责削薄（上一轮）
  - ✅ ExecutionResultHandler 唯一执行结果入口（早前轮）
  - ✅ ExecutionCommand 幂等 / 补偿 / 防覆盖（早前轮）
- 下一步可推进架构设计参考 §5.2 阶段二：工作单元显式建模 + 控制命令层（STOP/PAUSE/REPLAN）+ 用户输入可重入
- 当前 Poller 在主路径之外独享调度线程，Poller 自身故障不会影响主路径

---

### 2026-07-13 §5.2 启动前结构清理 — ExecutionCommand*Consumer 迁入 agent.mqconsumer

#### 1. 范围

- §5.1 阶段一收官后，进入 §5.2 阶段二之前，先把"消费者"代码从 service/ 根目录剥离，对齐 CODE_STYLE §15.1「helloai-core/agent/mqconsumer/」子包规范
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
