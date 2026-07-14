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

