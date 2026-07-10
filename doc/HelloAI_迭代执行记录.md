# HelloAI 迭代执行记录

## 摘要

### 1. 文档定位

本文件用于记录每一轮实际执行了什么，不再把实施日志写回历史路线图正文。

记录目标：

- 让后来者快速知道最近做了哪些事
- 让差距表可以对应到“哪一轮关闭了哪一项”
- 让历史路线图保持“目标态文档”的可读性

### 2. 使用规则

- 一次相对独立的开发/修复/收口，记为一轮
- 每轮只记录事实，不写空泛规划
- 每轮都要写“范围、落地、影响、遗留”

### 3. 当前记录说明

本文件先补入最近一轮已明确确认的关键执行结果，后续按模板持续追加。

---

## 细则

### 2026-07 环境与主线收口

#### 1. 基础环境与初始化

- 对齐本地开发环境为 JDK 17
- 修复 Spring Boot 3.x 与 MyBatis-Plus / MyBatis-Spring 兼容问题
- 整理 Flyway 初始化方式，收敛为单一初始化脚本 `V1__init_all.sql`
- 重置 PostgreSQL 开发库，并按当前仓库配置重新初始化
- 初始化脚本补入默认管理员账号 `admin / admin123`

#### 2. 后端主线修复

- 修复 Redis 配置前缀，适配 Spring Boot 3.x
- 修复枚举与 PostgreSQL `SMALLINT` 映射问题
- 修复分页 `Page<Entity> -> PageResult<DTO>` 泛型冲突
- 推进 Controller 出参按 `Response DTO` 收口
- 修复多个列表接口因 `@RequestParam(defaultValue)` 缺少显式参数名导致的 400 问题
- 修复可选枚举筛选参数为空时提前求值导致的 500 问题

#### 3. 前端主线修复

- 修复前端构建期 TypeScript 报错
- 修复 `Login.vue` 登录响应类型推断问题
- 修复活动流页面把分页对象当数组渲染导致的页面卡住问题
- 统一补齐部分页面对后端返回结构的保护性处理

#### 4. 文档治理

- 确认历史路线图与技术方案已不适合继续混写执行现状
- 重新建立三类文档分层：
  - 项目基线文档
  - 实现差距表
  - 迭代执行记录

#### 5. 当前遗留

- 路线图 N2 工作流模板仍未落地
- 路线图 N6/N9 平台内 AgentExecutor / ChatClient 执行链仍未落地
- 路线图 N10 `credential_vault` 与工牌模式仍未落地
- README 与部分历史文档仍需持续按差距表校正

### 文档治理专项

#### 1. 目标

- 将“计划、现状、执行”三类信息拆分到独立文档
- 降低历史路线图持续膨胀的问题
- 为后续迭代建立稳定维护入口

#### 2. 本轮落地

- 新增《项目基线文档》
- 新增《实现差距表》
- 新增《迭代执行记录》
- 在 README 中重建文档导航
- 在历史主文档顶部加入定位说明
- 将《实现差距表》从概要版补充为 `D1-D6` 文档失真项与 `N1-N10` 逐项对表版

### 2026-07 Spring AI 版本口径与跨平台回归收口

#### 1. 范围

- 本轮目标：收口 `spring-ai` 当前运行版本口径，并补齐 macOS 下的 MCP 回归脚本
- 关联差距项：D4

#### 2. 实际落地

- 新增 macOS 原生验证脚本：
  - `verify-mcp-auth.sh`
  - `verify-mcp-e2e.sh`
- 修复 macOS `verify-mcp-e2e.sh` 对 SSE 结果的误判，改为“外层 `data:` JSON + 内层 `content[0].text` JSON”双层解析
- 在 macOS 下完成 `verify-mcp-auth.sh` 与 `verify-mcp-e2e.sh` 主链路回归
- 将现行文档口径统一为：当前父工程运行版本为 `spring-ai 1.1.8`

#### 3. 验证

- `./verify-mcp-auth.sh`：通过
- `./verify-mcp-e2e.sh`：通过
- 验证结论：MCP 鉴权、SSE、任务拉取/认领、附件登记、回执、状态推进主链路在 macOS + `spring-ai 1.1.8` 下可用

#### 4. 影响

- 对外行为变化：无新增业务能力，仅补齐 macOS 验证入口
- 文档变化：README、项目基线、实现差距表、历史路线图归档说明同步收口
- 数据结构变化：无

#### 5. 遗留

- Windows 下仍建议继续保留 `verify-mcp-auth.ps1` / `verify-mcp-e2e.ps1` 作为兼容验证入口
- 历史路线图中的 `1.1.0` 结论只保留归档意义，不再作为现行约束

### 2026-07 Agent 接入内容生成功能收口

#### 1. 范围

- 本轮目标：将 Agent 接入内容生成能力沉淀为干净的功能方案文档，并把调试/执行痕迹迁出
- 关联文档：
  - `doc/HelloAI_Agent接入内容生成功能开发清单_v2.0.md`

#### 2. 实际落地

- 为 `HelloAI_Agent接入内容生成功能开发清单_v2.0.md` 增加顶部定位说明，明确其职责仅为：
  - 功能方案
  - 接口设计
  - 开发清单
  - 验收口径
- 将文档中的执行性表述收口为方案性表述，避免继续混入“某轮怎么改、谁提出、哪里修了”的痕迹
- 将“实际开发过程中的调试记录、联调结果、修复经过与阶段性执行结论”统一归口到《迭代执行记录》

#### 3. 验证

- 文档检查：`HelloAI_Agent接入内容生成功能开发清单_v2.0.md` 已不再承担执行日志职责
- 结构检查：方案文档与执行记录的职责边界已明确

#### 4. 影响

- 对外行为变化：无
- 文档变化：
  - `HelloAI_Agent接入内容生成功能开发清单_v2.0.md` 更适合作为长期维护的功能方案文档
  - 《迭代执行记录》继续承担实际开发/调试历史沉淀
- 数据结构变化：无

#### 5. 遗留

- 如果后续继续推进该功能的真实开发，应只在方案文档维护“目标与验收口径”
- 联调问题、界面调整、字段修复、上线结论等内容，后续只追加到《迭代执行记录》

### 2026-07 旧功能清单口径统一收口

#### 1. 范围

- 本轮目标：继续清理历史功能清单/对比方案文档中的旧执行口径，统一纳入“三层文档体系”引用边界
- 关联文档：
  - `doc/HelloAI_技术方案与补齐清单_v1.1.md`
  - `doc/HelloAI_vs_OpenMOSS_功能对比与实现方案.md`

#### 2. 实际落地

- 为 `HelloAI_技术方案与补齐清单_v1.1.md` 补充“历史现状判断不等于当前仓库基线”的说明
- 将 `HelloAI_技术方案与补齐清单_v1.1.md` 顶部版本描述由执行反馈式表述收口为历史形成背景说明
- 为 `HelloAI_vs_OpenMOSS_功能对比与实现方案.md` 增加顶部定位说明，明确其职责为历史对标分析与补齐方案资产
- 在 `HelloAI_vs_OpenMOSS_功能对比与实现方案.md` 中明确：当前现实边界看《项目基线文档》，差异判断看《实现差距表》，执行事实看《迭代执行记录》
- 将 `HelloAI_vs_OpenMOSS_功能对比与实现方案.md` 的“源码路径”改写为“历史对表路径”，避免误导为当前本地工程路径
- 顺手修正文档内部一处章节编号重复问题，降低后续维护歧义

#### 3. 验证

- 文档检查：两份历史文档均已具备统一的定位说明或归档边界
- 结构检查：方案文档、差距文档、执行记录之间的职责划分进一步收口

#### 4. 影响

- 对外行为变化：无
- 文档变化：
  - 历史技术方案文档更适合作为设计背景资产
  - 历史对比分析文档不再与当前运行基线混用
- 数据结构变化：无

#### 5. 遗留

- `HelloAI_vs_OpenMOSS_功能对比与实现方案.md` 正文中的具体差异项仍是历史快照，若后续要继续作为执行输入，应按《实现差距表》逐项回刷
- 其他仍存历史执行口径的旧文档，后续可继续按同一模板逐份收口

### 2026-07 N10 + N6 底座一期（T1-T3）

#### 1. 范围

- 本轮目标：先把 `credential_vault` 最小数据底座、`agent.api_key` 工牌语义，以及 `AgentExecutor` 骨架立住
- 关联差距项：
  - `N6`
  - `N10`

#### 2. 实际落地

- 新增 `credential_vault` 最小模型：
  - 在 `V1__init_all.sql` 中补入初始化表结构
  - 新增增量迁移 `V14__create_credential_vault.sql`
  - 新增 `CredentialVault` 实体、Mapper、Service
- 收口工牌模式语义：
  - `agent.api_key` 注释与 SQL 注释明确为 consumerToken 工牌
  - `AgentAccessType` 增加“是否走工牌鉴权 / 是否走托管凭证”的语义方法
  - `AgentService.register/resetApiKey` 改为显式下发 consumerToken
- 新增平台内执行骨架：
  - 新增 `AgentTask` / `AgentResult`
  - 新增 `AgentExecutor` 接口
  - 新增 `ApiKeyAgentExecutor` 占位实现
  - 新增 `AgentExecutorRouter`
  - 新增 `PlatformAgentExecutionService` 作为统一执行入口

#### 3. 验证

- 构建验证：
  - `mvn -pl helloai-common,helloai-core,helloai-start -am compile`：通过
- 代码检查：
  - T1/T2/T3 相关新增类与迁移已进入主工程编译链

#### 4. 影响

- 对外行为变化：
  - 当前无新增外部接口，仅补齐后端底座和内部语义
- 文档变化：
  - 《实现差距表》中的 `N6`、`N10` 已从“未落地”调整为“部分落地”
- 数据结构变化：
  - 新增 `credential_vault` 表
  - `agent.api_key` 字段名不变，但语义进一步收口为 consumerToken

#### 5. 遗留

- `N9` 仍未开始，尚未接入真实 `ChatClient`
- `ApiKeyAgentExecutor` 目前还是占位实现，尚未回写子任务状态
- `credential_vault` 仅完成最小表结构与服务，尚未补应用层加解密与历史数据迁移
- 后续建议：按既定顺序继续推进 `T4 + T5`

### 2026-07 N9 + N6 最小执行链（T4-T5）

#### 1. 范围

- 本轮目标：把 `ApiKeyAgentExecutor` 从占位实现推进为可调用的最小执行链，并提供一个可验证入口
- 关联差距项：
  - `N6`
  - `N9`

#### 2. 实际落地

- 在 `helloai-core` 引入 `spring-ai-client-chat`
- 新增 `AgentExecutionProperties`，默认开启稳定 mock 模式
- 新增 `AgentChatClientService`，用 Spring AI `ChatClient` 组装最小执行调用
- 将 `ApiKeyAgentExecutor` 从“占位失败”改为“可执行并返回结果”
- 新增 `AgentExecutionPreviewService` 作为最小编排层
- 新增 `AgentExecutionController` 与请求/响应 DTO，提供管理员验证入口：
  - `POST /api/agent-executions/{agentId}/preview`
- 新增 `PlatformAgentExecutionServiceTest`，验证 mock 链路可跑通

#### 3. 验证

- 构建验证：
  - `mvn -pl helloai-common,helloai-core,helloai-api,helloai-start -am compile`：通过
- 自动化验证：
  - `mvn -pl helloai-core -am -Dtest=PlatformAgentExecutionServiceTest "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过

#### 4. 影响

- 对外行为变化：
  - 新增一个最小管理员验证入口，用于触发平台内执行链预览
- 文档变化：
  - 《实现差距表》中的 `N9` 已从“未落地”调整为“部分落地”
- 数据结构变化：无

#### 5. 遗留

- 当前只接通稳定 mock `ChatClient`，尚未打真实 Provider
- `ApiKeyAgentExecutor` 仍未与子任务状态推进、Prompt 拼装、结果回写做深度集成
- 后续建议：继续推进真实 `ChatClient` Provider 配置与子任务闭环

### 2026-07 N10 + N6 + N9 最小闭环验收（T1-T6）

#### 1. 范围

- 本轮目标：完成 T1-T6 的最小闭环验收取证，确保 DB / 鉴权 / mock 执行 / real 执行均可复现
- 关联差距项：
  - `N6`
  - `N9`
  - `N10`

#### 2. 实际落地

- 补齐真 Provider 配置位（DeepSeek）与开关机制：
  - 支持 `helloai.execution.mock-mode=true/false` 切换 mock/real
  - 增加 `spring.ai.deepseek.*` 配置位，真实 key 通过环境变量注入
- 收口验收入口：
  - `POST /api/agent-executions/{agentId}/preview` 作为平台内执行链最小取证端点
  - `verify-agent-execution-preview.ps1` 支持默认 `admin/admin123`，并支持 `-SkipOutputAssert`（real 模式下不依赖固定输出）

#### 3. 验证

- DB 取证（T1）：
  - `select count(*) from credential_vault;` => `0`（表存在，数据为空属正常）
- 鉴权取证（T2）：
  - `GET /api/auth/me` with `Authorization: Bearer ak_...` => `200`，`type=agent`
- 执行链取证（T4-T6）：
  - `.\verify-agent-execution-preview.ps1`（mock）=> 通过
  - `.\verify-agent-execution-preview.ps1 -SkipOutputAssert`（real）=> 通过

#### 4. 影响

- 对外行为变化：无新增业务功能，主要是补齐可验证入口与验收脚本
- 数据结构变化：无新增表（`credential_vault` 已在 T1 落地）
- 文档变化：本轮追加验收取证，确保后续迭代可复现

#### 5. 遗留

- real 模式目前仍使用全局 Provider 配置进行执行；`credential_vault` 的“托管凭证注入 + 加解密链”尚未落地
- `ApiKeyAgentExecutor` 未与子任务状态推进、Prompt 拼装、结果回写集成
- 下一步建议：推进“凭证托管（vault）→ real 执行注入 → 子任务状态闭环”的二期

### 2026-07 N10 vault 注入 + 子任务闭环（二期骨架）

#### 1. 范围

- 本轮目标：推进 N10 的“托管凭证可用化”，并补一条子任务最小执行闭环入口，形成可继续扩展的二期骨架
- 关联差距项：
  - `N10`
  - `N6`
  - `N9`

#### 2. 实际落地

- 凭证托管加解密：
  - 增加 `helloai.security.credential.aes-key-base64` 配置位（优先读配置，其次读环境变量 `HELLOAI_CREDENTIAL_AES_KEY_BASE64`）
  - 新增 `CredentialCryptoService`（AES-GCM）与 `CredentialVaultBindingService`（绑定与解密读取）
- Provider 注入路径：
  - `ApiKeyAgentExecutor` 在 real 模式下支持从 `credential_vault` 取出解密后的 apiKey 并执行
  - 增加 `helloai.execution.require-vault` 开关（为 true 时 real 模式必须已绑定 vault）
  - 引入 `ProviderChatClientFactory` 扩展点，并补 `DeepSeekProviderChatClientFactory` 实现（按 apiKey 动态构建 DeepSeek ChatClient）
- 子任务最小闭环入口：
- 新增 `POST /api/sub-tasks/execute/{id}`（admin only，兼容保留 `POST /api/sub-tasks/{id}/execute`）
  - 执行逻辑：ASSIGNED/REWORK/PAUSED → IN_PROGRESS → 执行 → 写入 `sub_task.context.lastExecution` → submit 到 REVIEW
  - 时间线取证：记录 `sub_task_execute_start / sub_task_execute_submit / sub_task_execute_failed`
- 回归收紧：
  - `verify-agent-execution-preview.ps1` 新增 `-BindVault`，可在验收时把 `DEEPSEEK_API_KEY` 写入 vault 并校验 vault 记录存在（不打印 secret）

#### 3. 验证

- 构建/单测：
  - `mvn -pl helloai-common,helloai-core,helloai-api,helloai-start -am test`：通过
- 手工/脚本取证：
  - `.\verify-agent-execution-preview.ps1 -SkipOutputAssert -BindVault`：通过（写入 vault → 从 vault 注入 apiKey → real preview 执行成功）
  - DB：`select id, owner_type, owner_id, provider, credential_type, status, expires_at, create_time from credential_vault order by create_time desc limit 5;` 可见多条 `AGENT/deepseek/API_KEY/ACTIVE` 记录

#### 4. 影响

- 对外行为变化：
  - 新增 admin-only 的凭证绑定与子任务执行接口（用于平台内执行链路与二期闭环验收）
- 数据结构变化：无（复用既有 `credential_vault` 与 `sub_task.context`）

#### 5. 遗留

- vault 注入能力当前仅补齐 DeepSeek 的动态构建路径，其他 Provider 需要按同一扩展点补齐
- 子任务执行闭环仍是最小版：未做输出结构化、未做产物/附件沉淀、未做 REVIEW 评分闭环串联

### 2026-07 credential_vault ACTIVE 唯一性收口

#### 1. 范围

- 本轮目标：允许凭证历史版本保留，但对同一 `(owner_type, owner_id, provider, credential_type)` 收口为“最多 1 条 ACTIVE（deleted=0）”

#### 2. 实际落地

- Flyway：新增迁移清理存量重复 ACTIVE（保留最新，其余置为 DISABLED），并新增部分唯一索引约束
- 服务端：bind 新凭证前先将同 owner/provider/type 的旧 ACTIVE 批量置为 DISABLED，再插入新记录（保留历史）
- 回归脚本：`verify-agent-execution-preview.ps1` 增加 `-BindVaultTwice`，可验证重复绑定后 ACTIVE 仍为 1

#### 3. 验证

- 构建/单测：`mvn -pl helloai-common,helloai-core,helloai-api,helloai-start -am test`：通过

### 2026-07 API_KEY_LLM 分配后自动执行接线

#### 1. 范围

- 本轮目标：把 API_KEY_LLM 类型子任务从“仅支持手工 `/execute`”推进到“ASSIGNED 后自动进入平台内执行链”
- 关联差距项：
  - `N6`
  - `N9`

#### 2. 实际落地

- 在 `helloai-core` 新增 `SubTaskAssignedEvent`，用于承接子任务分配后的内部事件
- 新增 `SubTaskAutoExecutionDispatcher`：
  - 使用 `@TransactionalEventListener(phase = AFTER_COMMIT)` 确保只在分配事务提交后触发
  - 使用 `@Async` 保持 LLM 调用与调度事务隔离
  - 当前仅对 `API_KEY_LLM` 触发自动执行；`CLI_CLIENT` 仍保留收件箱/MCP 拉取链路
- `SubTaskService.changeStatus()` 在进入 `ASSIGNED` 且存在 `assignedAgent` 时发布分配事件
- `SubTaskController.create()` 改为复用 `SubTaskService.create(..., assignedAgentId)`，避免创建即分配场景绕开统一分配/通知/自动执行链路
- 时间线补点：
  - 新增 `sub_task_auto_execute_dispatch` 事件，记录自动触发来源为 `assigned`

#### 3. 验证

- 编译验证：
  - `mvn -pl helloai-api,helloai-core,helloai-start -am compile`：通过
- 单测验证：
  - `mvn -pl helloai-core -am "-Dtest=SubTaskAutoExecutionDispatcherTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过
  - `mvn -pl helloai-core -am "-Dtest=ResilientDispatcherTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过
  - `mvn -pl helloai-core -am "-Dtest=PlatformAgentExecutionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过

#### 4. 影响

- 对外行为变化：
- `API_KEY_LLM` 类型 Agent 在子任务成功进入 `ASSIGNED` 后，不再必须手工调用 `/api/sub-tasks/execute/{id}` 才能进入平台内执行（旧路径 `/{id}/execute` 仍兼容）
- 数据结构变化：无
- 文档变化：
  - 《实现差距表》对 N6 的描述同步收口为“ASSIGNED 后可异步自动触发执行”

#### 5. 遗留

- 当前自动执行只接到“已知 assignedAgent”的链路，尚未补 Planner 自动选人后的完整调度入口
- 自动执行失败目前以日志 + 时间线隔离，不回推更细粒度的调度补偿策略
- 下一步建议：继续把 `ResilientDispatcher` 的真实调用入口接出来，并补一条从“选人 -> ASSIGNED -> 自动执行 -> REVIEW”的端到端验收脚本

### 2026-07 动作型端点调用方收口与 E2E/DB 取证

#### 1. 范围

- 本轮目标：把前端与验收脚本中的动作型接口调用统一切到 `/{action}/{id}`，并补齐一轮从接口到数据库的闭环取证
- 关联差距项：
  - `D6`
  - `N6`
  - `N9`

#### 2. 实际落地

- 规范收口：
  - `HelloAI_CODE_STYLE.md` 的动作端点规范调整为“新代码优先使用 `/{action}/{id}`，旧 `/{id}/{action}` 短期兼容保留”
- 调用方适配：
  - 前端 `helloai-ui/src/api/subTask.ts` 改为使用：
    - `/sub-tasks/claim/{id}`
    - `/sub-tasks/start/{id}`
    - `/sub-tasks/submit/{id}`
    - `/sub-tasks/block/{id}`
    - `/sub-tasks/pause/{id}`
    - `/sub-tasks/resume/{id}`
  - 前端 `helloai-ui/src/api/inbox.ts` 改为使用：
    - `/agent/inbox/read/{id}`
    - `/agent/inbox/archive/{id}`
  - 前端 `helloai-ui/src/api/agent.ts` 改为使用：
    - `/admin/agents/status/{id}`
    - `/admin/agents/reset-key/{id}`
  - 验收脚本 `verify-mcp-e2e.ps1` / `verify-mcp-e2e.sh` 改为使用：
    - `/api/sub-tasks/start/{id}`
    - `/api/sub-tasks/submit/{id}`
    - `/api/sub-tasks/complete/{id}`

#### 3. 验证

- 前端构建：
  - `npm run build`（`helloai-ui`）：通过
- E2E 接口链路：
  - `.\verify-mcp-e2e.ps1`：通过
  - 关键结果：
    - `POST /api/sub-tasks/start/{id}` => `200`
    - `POST /api/sub-tasks/submit/{id}` => `200`
    - `POST /api/sub-tasks/complete/{id}` => `200`
    - `GET /api/sub-tasks/{id}` 最终返回 `status=DONE`
- DB 取证：
  - T1 `agent_inbox`：
    - 最新 `sub_task.assigned` 记录 `is_read=1`
    - `read_at=2026-07-10 12:55:44.253393 +08:00`
  - T2 `attachment`：
    - 可见 `M5-result.txt`
    - `storage_url=minio://helloai-test/M5-test/2075443748186779650/result.txt`
    - `status=ACTIVE`
  - T3 `sub_task`：
    - `status=DONE`
    - `completed_at=2026-07-10 12:55:46.322481 +08:00`
    - `composite_score=90`
    - `score_grade=S`
  - T4 `agent`：
    - `last_seen_at=2026-07-10 12:55:40.140613 +08:00`
    - `last_active_at=2026-07-10 12:55:46.303552 +08:00`
    - `online_status=ONLINE`

#### 4. 影响

- 对外行为变化：
  - 前端与验收脚本已切到新的动作路径口径 `/{action}/{id}`
  - 旧路径仍由后端兼容保留，现阶段不会打断已有调用
- 文档变化：
  - `HelloAI_CODE_STYLE.md` 的动作端点规范已同步更新
  - 本轮执行证据已回填到《迭代执行记录》
- 数据结构变化：无

#### 5. 遗留

- `helloai-core` 内置 skills、MCP 提示文案和少量历史说明中仍残留旧动作路径示例，后续可继续统一
- 目前 E2E 脚本复用了历史测试 Agent，因此 `inbox/count` 结果包含历史未读，不适合作为“必须为 0”类断言
- 下一步建议：继续收口资源文件中的旧接口示例，并视需要增加“优先走新路径”的显式兼容下线计划

### 2026-07 旧动作路径兼容层下线

#### 1. 范围

- 本轮目标：在前端、脚本、skills、MCP 提示文案都已切到 `/{action}/{id}` 后，删除后端控制器中旧的 `/{id}/{action}` 兼容映射
- 关联差距项：
  - `D6`

#### 2. 实际落地

- 资源与提示文案收口：
  - `helloai-core/src/main/resources/skills/executor/SKILL.md`
  - `helloai-core/src/main/resources/skills/planner/SKILL.md`
  - `helloai-core/src/main/resources/skills/patrol/SKILL.md`
  - `helloai-core/src/main/resources/skills/reviewer/SKILL.md`
  - `helloai-core/src/main/java/com/helloai/core/mcp/McpMcpServer.java`
- 控制器删除兼容映射，仅保留 `/{action}/{id}`：
  - `SubTaskController`
  - `TaskController`
  - `AgentInboxController`
  - `AdminAgentController`
- 具体收口后的动作端点包括：
  - `/api/sub-tasks/claim/{id}`
  - `/api/sub-tasks/start/{id}`
  - `/api/sub-tasks/submit/{id}`
  - `/api/sub-tasks/complete/{id}`
  - `/api/sub-tasks/rework/{id}`
  - `/api/sub-tasks/block/{id}`
  - `/api/sub-tasks/reassign/{id}`
  - `/api/sub-tasks/pause/{id}`
  - `/api/sub-tasks/resume/{id}`
  - `/api/sub-tasks/execute/{id}`
  - `/api/tasks/status/{id}`
  - `/api/agent/inbox/read/{id}`
  - `/api/agent/inbox/archive/{id}`
  - `/api/admin/agents/status/{id}`
  - `/api/admin/agents/sleep/{id}`
  - `/api/admin/agents/wake/{id}`
  - `/api/admin/agents/reset-key/{id}`

#### 3. 验证

- 静态检索：
  - 仓库内已无这批旧动作路径 `/{id}/{action}` 的残留控制器映射和 skills/MCP 示例
- 编译验证：
  - `mvn -pl helloai-api,helloai-core,helloai-start -am compile`：通过
- 回归验证：
  - `.\verify-mcp-e2e.ps1`：通过
  - 关键结果：
    - `POST /api/sub-tasks/start/{id}` => `200`
    - `POST /api/sub-tasks/submit/{id}` => `200`
    - `POST /api/sub-tasks/complete/{id}` => `200`
    - 子任务最终 `status=DONE`
- 运行态下线验证（后端重启后）：
  - `PUT /api/tasks/{id}/status` => `404`
  - `POST /api/sub-tasks/{id}/start` => `404`
  - `POST /api/admin/agents/{id}/reset-key` => `404`
  - 返回体统一为：`{"code":404,"msg":"请求的接口不存在",...}`

#### 4. 影响

- 对外行为变化：
  - 新代码口径下，动作型接口只保留 `/{action}/{id}`
  - 继续调用旧路径 `/{id}/{action}` 的外部调用方，在服务重启到本轮代码后已确认不可用
- 文档变化：
  - skills 与 MCP 提示文案中的动作路径示例已同步改为新规范
  - 《迭代执行记录》新增本轮兼容层下线记录
- 数据结构变化：无

#### 5. 遗留

- 若需要对外明确发布兼容下线说明，可在 README 或 API 文档中追加一条“旧路径已移除”的变更提醒

### 2026-07 调度入口接回自动执行主链

#### 1. 范围

- 本轮目标：把“重新进入分配”的真实调度入口接回 `ResilientDispatcher`，让 `BLOCKED` 重分配与离线补偿也能重新触发 `ASSIGNED -> 自动执行`
- 关联差距项：
  - `N6`
  - `N9`

#### 2. 实际落地

- 新增 `SubTaskDispatchService`，统一承接“重置为 PENDING 后重新进入弹性调度”的编排逻辑：
  - `dispatchBlockedSubTask(subTaskId, preferredAgentId)`
  - `redispatchOfflineSubTask(subTaskId, offlineAgentId)`
- `SubTaskService` 补充 `resetToPendingForDispatch(...)`：
  - 允许系统路径将 `BLOCKED / ASSIGNED / IN_PROGRESS` 等指定状态重置为 `PENDING`
  - 同时清空 `assignedAgent`，避免旧负责人残留干扰后续 ASSIGNED 事件
- `SubTaskController.reassign()` 改为走 `SubTaskDispatchService`，不再直接改库后手工切状态
- `AgentHealthCheckTask.reassignStaleTasks()` 改为走 `SubTaskDispatchService`：
  - 离线 Agent 的遗留任务先重置为 `PENDING`
  - 再交给 `ResilientDispatcher` 处理 fast-fail + fallback
  - 重新发布标准 `ASSIGNED` 事件，从而可再次进入 API_KEY_LLM 自动执行链
- 运行态验收脚本：
  - 新增 `verify-subtask-redispatch-auto-execution.ps1`
  - 支持 `blocked` / `offline` 两种场景，覆盖“重分配后自动执行到 REVIEW”的端到端取证，并生成 `task_timeline` / `agent` / `sub_task` SQL 快照脚本
- 运行态问题定位（blocked 场景）：
  - 运行 `.\verify-subtask-redispatch-auto-execution.ps1 -Scenario blocked -BindVault` 时，子任务可从 `ASSIGNED -> IN_PROGRESS`，但在超时窗口内未进入 `REVIEW`
  - 已启用基于 Debug Server 的运行态取证，会话文件：`debug-redispatch-stuck-blocked.md`
  - 已在以下链路补齐取证埋点（仅用于定位卡点，不记录 secret）：
    - `SubTaskAutoExecutionDispatcher`
    - `SubTaskExecutionService`
    - `PlatformAgentExecutionService`
    - `ApiKeyAgentExecutor`
    - `CredentialVaultBindingService`
  - 现有证据显示执行链可到达 `PlatformAgentExecutionService` 的 `platform_execute_before_executor`，但 `ApiKeyAgentExecutor` 的执行段仍未完成，需继续确认卡点在 vault 获取/解密、ChatClient 调用，还是异步线程池阻塞
  - 追加数据库取证结论：
    - `sub_task` 长时间停留在 `IN_PROGRESS`，`task_timeline` 只记录到 `sub_task_execute_start`
    - 对应 `credential_vault` 已存在 `ACTIVE` 记录，数据库无连接池耗尽、锁阻塞或长事务迹象
    - 当前 blocked 链路排查优先级已切换为“先验证 LLM 最小连通性，再回到完整调度链”
- LLM 最小连通性验证入口：
  - 新增 `POST /api/agent-executions/connectivity/{agentId}`
  - 该入口只验证 `vault -> provider -> ChatClient` 的最小真实调用链，不写入 `sub_task` / `agent_execution_record` / `task_timeline`
  - 返回信息包含 `provider`、`model`、`mockMode`、vault 凭证就绪情况、`latencyMs`、`stage`、`rootException/rootMessage`
  - 新增脚本 `verify-agent-llm-connectivity.ps1`，用于管理员登录、注册 `API_KEY_LLM` Agent、可选绑定 vault 后直接触发连通性探针
  - 连通性取证结果：
    - `verify-agent-llm-connectivity.ps1 -BindVault` 已返回 `success=true`、`stage=chat_ok`、`latencyMs≈1136ms`、`output=OK`
    - 可排除 `DeepSeek API Key` 不可用、vault 无法解密、Provider 无法连通作为 blocked 主因
  - 线程诊断补充：
    - 对运行中 `HelloAIApplication` 进程抓取 thread dump 后，未观察到明确的 `ForkJoinPool.commonPool-worker-*` 执行栈
    - blocked 子任务仍永久停留在 `IN_PROGRESS`，结合 debug 事件停在 `api_key_llm_before_vault_fetch`，优先怀疑 `ApiKeyAgentExecutor.execute()` 中的二次 `CompletableFuture.supplyAsync()` 造成执行与回写链路脱节
  - 最小修复验证：
    - `ApiKeyAgentExecutor.execute()` 已改为在上游 `@Async` 线程中同步执行 `agentChatClientService.generate(...)`，不再额外切到 `ForkJoinPool.commonPool`
    - 该调整的目标是先验证 blocked 场景是否恢复到 `REVIEW` 或显式失败回写，后续再决定是否需要保留独立线程池方案
  - 进一步修复（避免“静默永久 IN_PROGRESS”）：
    - `SubTaskExecutionService.executeOnce()` 将 `AgentTask` 构建与 prompt 拼装纳入 try/catch，确保异常能走 `saveExecutionError + block + sub_task_execute_failed`
    - `ApiKeyAgentExecutor` 恢复异步执行，但改为使用 Spring 管理的 `apiKeyLlmExecutor` 专用线程池承载 `supplyAsync`，避免依赖 `ForkJoinPool.commonPool`
    - `SubTaskAutoExecutionDispatcher / SubTaskExecutionService / PlatformAgentExecutionService` 的 Debug Server URL 获取取消负缓存，避免重启后丢失取证能力
- 调试稳定性修正：
  - 修复 `ApiKeyAgentExecutor` 中 `vaultApiKey` 被 lambda 捕获时“非 effectively final”导致的启动编译错误
  - 修复部分调试埋点对 `DEBUG_SERVER_URL` 的负缓存逻辑（首次缺失会缓存为空串，导致后续永远不上报），改为“缺失时不缓存，后续可重试”
- 调度可分配性修正：
  - 运行脚本时暴露出 `API_KEY_LLM` 新注册默认 `online_status=OFFLINE`，会被 `ResilientDispatcher` fast-fail 拦截，导致 `BLOCKED -> reassign` 停在 `PENDING`
  - 已收口为：只有 `CLI_CLIENT` 这类依赖运行时心跳的 Agent 才受 `OFFLINE` 门禁；`API_KEY_LLM / WEB_BROWSER` 不再因默认 OFFLINE 被误判为不可分配
- 熔断模板配置兜底：
  - 运行中暴露出 `AgentHealthCheckTask` 离线重分配场景可能抛 `ConfigurationNotFoundException: agentDispatch`
  - 根因是 per-agent 熔断器创建时强依赖命名模板配置；已改为“优先复用 `agentDispatch`，缺失时自动回退到 registry 默认配置”
- 时间线补点：
  - 新增 `sub_task_dispatch_prepare` 事件，区分 `blocked_reassign` 与 `agent_offline` 两类触发来源
- 2026-07-10 下午 blocked 场景进一步排查与诊断记录：
  - 运行脚本 `.\verify-subtask-redispatch-auto-execution.ps1 -Scenario blocked -BindVault` 多轮复现，典型样本包括：
    - `subTaskId=2075471695878721538`
    - `subTaskId=2075482250295316481`
    - `subTaskId=2075491240563625986`
    - `subTaskId=2075496223262543874`
    - `subTaskId=2075505306791219202`
    - `subTaskId=2075511820859977729`
    - `subTaskId=2075514853803102209`
  - 各轮共同现象：
    - 子任务可稳定从 `ASSIGNED -> IN_PROGRESS`
    - 脚本默认等待窗口内始终未进入 `REVIEW`
    - `task_timeline` 长期只停留在：
      - `sub_task_dispatch_prepare`
      - `sub_task_auto_execute_dispatch`
      - `sub_task_execute_start`
    - 对应 `sub_task.update_time` 停留在进入 `IN_PROGRESS` 的时间点附近，之后不再更新
  - 数据库侧辅助诊断结论：
    - `credential_vault` 已存在与目标 `API_KEY_LLM` Agent 对应的 `ACTIVE` 凭证
    - `pg_stat_activity` 未观察到连接池耗尽、锁等待、长事务或死锁迹象
    - 某些更早样本（例如 `2075482250295316481`）会在更晚时间被回写成 `BLOCKED`，但不是当前脚本窗口内产生的即时结果，说明系统内存在延迟失败回写样本，不能作为“本轮 55 秒超时配置已生效”的证据
  - LLM 最小链路已独立验证通过：
    - 运行 `.\verify-agent-llm-connectivity.ps1 -BindVault`
    - 返回 `success=true`、`stage=chat_ok`、`latencyMs≈1136ms`、`output=OK`
    - 因此可排除以下主因：
      - DeepSeek API Key 无效
      - vault 无法解密
      - Provider / ChatClient 最小真实调用链不可达
  - 围绕“静默卡死”的代码级尝试与结果：
    - 尝试 1：去掉 `ApiKeyAgentExecutor.execute()` 中的二次 `CompletableFuture.supplyAsync()`，改为在上游 `@Async` 线程里同步执行 `agentChatClientService.generate(...)`
      - 目的：验证是否是 `ForkJoinPool.commonPool` 任务未被调度
      - 结果：blocked 脚本现象无变化，仍停在 `IN_PROGRESS`
    - 尝试 2：恢复异步执行，但改为使用 Spring 管理的 `apiKeyLlmExecutor` 专用线程池，避免依赖 `ForkJoinPool.commonPool`
      - 目的：验证是否是公共线程池争用/调度问题
      - 结果：blocked 脚本现象仍无本质变化
    - 尝试 3：在 `SubTaskExecutionService.executeOnce()` 增加 try/catch 收口，并将 `AgentTask` 构建、prompt 拼装、context 组装纳入异常保护范围
      - 目的：避免异常逃逸后只留下 `IN_PROGRESS`
      - 结果：未能改变当前 blocked 运行态现象
    - 尝试 4：补充“救援式超时”方案
      - 增加 `platformExecuteRescueExecutor`
      - 使用 `Future.get(timeout)` + `cancel(true)` 包裹平台内执行，目标是在底层 join/HTTP 阻塞时也能强制回到失败分支
      - 结果：代码已接入，但在今天下午的运行态复现中，仍未看到当前脚本样本在窗口内转为 `BLOCKED`
    - 尝试 5：补 DeepSeek HTTP connect/read timeout 与平台同步执行 timeout
      - 配置项包括：
        - `DEEPSEEK_CONNECT_TIMEOUT_MS`
        - `DEEPSEEK_READ_TIMEOUT_MS`
        - `HELLOAI_EXECUTION_SYNC_TIMEOUT_SECONDS`
      - 同时新增一键脚本 `run-redispatch-diagnose.ps1`，用于统一设置 env、可选重启后端、跑验收脚本并输出 SQL 快照
      - 结果：
        - 当仅在 PowerShell 中 set env 但不重启后端时，配置不会进入已运行的 Java 进程
        - 即使后续在 IDEA Run Configuration 中补充了环境变量并重启，2026-07-10 下午最后一轮样本 `subTaskId=2075514853803102209` 仍在默认脚本窗口内停留在 `IN_PROGRESS`
  - 工具链/测试/启动过程中的补充修正：
    - 新增 `verify-agent-llm-connectivity.ps1` 用于最小 LLM 连通性验证
    - 新增 `run-redispatch-diagnose.ps1`，统一 env 设置、健康检查等待、脚本执行与 SQL snapshot 输出
    - 修复 `run-redispatch-diagnose.ps1` 参数透传错位问题，改为按参数名传递给 `verify-subtask-redispatch-auto-execution.ps1`
    - 修复 `ApiKeyAgentExecutor` 构造器调整引发的：
      - `PlatformAgentExecutionServiceTest` 构造参数不匹配
      - Spring Boot 启动时 `No default constructor found`
    - 修复 `SubTaskAutoExecutionDispatcher` 中 debug 埋点使用 `Map.of(..., null)` 导致单测 `NullPointerException`
    - 相关单测回归通过：
      - `PlatformAgentExecutionServiceTest`
      - `SubTaskAutoExecutionDispatcherTest`
  - 截至 2026-07-10 晚间的阶段性结论：
    - `blocked -> reassign` 已重新接回统一调度入口
    - `API_KEY_LLM` 的最小 vault + DeepSeek 连通性已证实可用
    - 当前真正未闭合的问题已经收敛为：
      - 在完整 blocked 调度链中，子任务进入 `sub_task_execute_start` 后，执行线程/回写链未能在期望时间窗口内完成 `submit` 或 `failed -> block`
    - 该问题当前应明确标记为“静默卡死（silent hang）”：
      - 表现为 `sub_task.status=IN_PROGRESS`
      - `task_timeline` 只到 `sub_task_execute_start`
      - 默认验收脚本窗口内无 `sub_task_execute_submit`
      - 默认验收脚本窗口内无 `sub_task_execute_failed`
  - 建议下次继续排查时优先保留的证据入口：
    - 使用 IDEA 启动 `HelloAIApplication`，把 timeout env 固定写入 Run Configuration
    - 继续使用 `.\run-redispatch-diagnose.ps1 -Scenario blocked -BindVault -RestartBackend:$false` 作为统一复现入口
    - 每次失败后优先保留：
      - `sqlSnapshot=...`
      - `runLog=...`
      - 当前 `subTaskId` 的倒序 `task_timeline`
      - 若仍只到 `sub_task_execute_start`，立即抓取 thread dump 辅助定位

#### 3. 验证

- 编译验证：
  - `mvn -pl helloai-core,helloai-job,helloai-api,helloai-start -am compile`：通过
- 单测验证：
  - `mvn -pl helloai-core -am "-Dtest=SubTaskDispatchServiceTest,SubTaskAutoExecutionDispatcherTest,ResilientDispatcherTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过
- 回归补充：
  - `mvn -pl helloai-core,helloai-common -am "-Dtest=ResilientDispatcherTest,AgentSelectorTest,SubTaskDispatchServiceTest,SubTaskAutoExecutionDispatcherTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过
  - `mvn -pl helloai-core,helloai-api,helloai-start -am -DskipTests compile`：通过
- 验证结论：
  - `BLOCKED` 重分配已重新走统一调度入口
  - 离线补偿不再只改 `assigned_agent`，而是会重新触发标准分配链
  - 原有自动执行与熔断降级单测未被破坏
  - API_KEY_LLM 调度可分配性问题已被单测覆盖
  - 缺少 `agentDispatch` 模板配置时，`ResilientDispatcher` 也可回退默认配置继续工作
  - 已补齐独立于调度链的 LLM 连通性探针，后续可先验证 API Key / vault / DeepSeek 调用是否可用，再继续 blocked 联调
- 2026-07-10 下午的多轮运行态复现已明确：当前 blocked 场景的主要未关闭问题不是“密钥不通”或“调度入口未接回”，而是完整执行链在 `sub_task_execute_start` 之后仍可能出现静默卡死

#### 4. 影响

- 对外行为变化：
  - `POST /api/sub-tasks/reassign/{id}` 现在会重新进入弹性调度器，而不是直接把新 Agent 写死到子任务上
  - 离线 Agent 的遗留任务在补偿时会重新触发标准 ASSIGNED 链路，API_KEY_LLM 因而可继续自动执行
  - `verify-subtask-redispatch-auto-execution.ps1` 可作为后续运行态回归入口
  - `POST /api/agent-executions/connectivity/{agentId}` 可作为 LLM API Key / vault / DeepSeek 最小连通性验证入口
  - `run-redispatch-diagnose.ps1` 可作为下午后续排查 blocked 静默卡死时的统一诊断入口
- 文档变化：
  - 《实现差距表》对 `N6` 的描述同步收口为“重分配也已重新入调度链”
  - 《迭代执行记录》追加了 2026-07-10 下午 blocked 静默卡死的完整复现与诊断过程
- 数据结构变化：无

#### 5. 遗留

- blocked 场景的“重分配 -> 自动执行 -> REVIEW”仍未在默认超时窗口内跑通，当前卡在 `IN_PROGRESS`，需要在下一会话继续完成根因定位与最小修复
- blocked 场景下一步应先用 `verify-agent-llm-connectivity.ps1` 或 `/api/agent-executions/connectivity/{agentId}` 验证当前项目的 vault + DeepSeek 最小调用链，再决定是否继续收紧超时/网络配置
- 若连通性已通过但 blocked 仍永久卡住，则优先验证 `ApiKeyAgentExecutor` 去除 `supplyAsync()` 后是否恢复 `REVIEW/FAILED` 回写
- offline 场景尚未完成运行态取证（需等待健康检查触发离线重分配，并观察是否可自动执行到 `REVIEW`）
- 离线补偿当前只覆盖 `ASSIGNED / IN_PROGRESS`，后续若要纳入更多状态需再评估状态机语义
- 自动执行失败后的更细粒度补偿策略仍未收口
- 对本轮新增的专用线程池、救援式超时、调试脚本与 timeout 配置，仍需在”后端进程确认读取到 env”前提下重新做一次受控复现，避免把旧进程样本误判为新代码无效

#### 6. 2026-07-10 下午 blocked 静默卡死：诊断总结

##### 6.1 问题定义

blocked 场景下 `重分配 → 自动执行 → REVIEW` 的主链路，子任务可稳定从 `ASSIGNED` 进入 `IN_PROGRESS`，但此后永远停在 `IN_PROGRESS`，不会推进到 `REVIEW` 或回退到 `BLOCKED`。`task_timeline` 永久只到 `sub_task_execute_start`，无 `sub_task_execute_submit` 也无 `sub_task_execute_failed`。

##### 6.2 已排除的主因

按排查顺序，以下方向均已独立验证通过，不作为 blocked 静默卡死的主因：

| 排查方向 | 验证方式 | 结论 |
|---------|---------|------|
| DeepSeek API Key 无效 | `verify-agent-llm-connectivity.ps1 -BindVault` | `success=true, stage=chat_ok, output=OK` |
| vault 无法解密 | 同上 + DB 确认 ACTIVE 记录存在 | vault 解密链路正常 |
| Provider/ChatClient 最小调用链不可达 | 同上 | 独立调用链可正常返回 |
| 调度入口未接回 | 代码审查 + 单测 | `dispatchBlockedSubTask` → `ResilientDispatcher` → `ASSIGNED` 链路完整 |
| API_KEY_LLM 被 OFFLINE 误判 | 代码审查 + 单测 | `requiresRuntimeLiveness()` 已仅对 CLI_CLIENT 返回 true |
| 数据库连接池/锁/长事务 | `pg_stat_activity` 查询 | 无异常 |
| `ForkJoinPool.commonPool` 线程调度 | 已改为 `apiKeyLlmExecutor` 专用线程池 | 现象无变化 |
| `executeOnce` try/catch 未覆盖 | 已补 try/catch 包裹 | 现象无变化 |
| rescue 超时未生效 | 已接入 `platformExecuteRescueExecutor` + `future.get(timeout)` | 现象无变化 |
| HTTP connect/read timeout 缺失 | 已增加配置项并写入 env | 即使配置了也未能改变现象 |
| 未重启导致旧进程不读新 env | 已在 IDEA Run Configuration 中固定写入 env 并重启 | 现象无变化 |

##### 6.3 当前问题定位

问题已收敛为：**执行线程/回写链在 `sub_task_execute_start` 之后静默消失或永久阻塞**。

三层线程池嵌套结构是高度可疑的架构因素：

```
@Async (Spring 默认执行器)
  → SubTaskExecutionService.executeOnce()
    → executeWithRescueTimeout()
      → platformExecuteRescueExecutor.submit()
        → PlatformAgentExecutionService.execute().join()
          → ApiKeyAgentExecutor.execute()
            → apiKeyLlmExecutor.submit()
              → agentChatClientService.generate()
                → ★ HTTP 调用（阻塞点）
```

**最可能的机制**：HTTP 调用层阻塞（DeepSeek API 的 socket read 无限等待），而上层的 `CompletableFuture.cancel(true)` 只能中断等待线程，无法中断底层 socket。于是 `cancel` 后 `api-key-llm-*` 线程可能仍卡在 `SocketInputStream.socketRead0()` native 方法中，线程池核心线程被泄漏/占用，后续任务排队但永不执行。

##### 6.4 下一步建议

按优先级排列：

1. **定位阻塞点**：在 IDEA 中启动后端，运行 `run-redispatch-diagnose.ps1 -Scenario blocked -BindVault -RestartBackend:$false`，卡死后立即抓取 thread dump，查 `api-key-llm-*` 线程的调用栈
2. **确认 HTTP 超时生效**：验证 `DeepSeekProviderChatClientFactory` 构建的 `DeepSeekChatModel` 确实使用了配置的 connect/read timeout
3. **降低复杂度的最小实验**：临时去掉 `ApiKeyAgentExecutor.execute()` 中的 `supplyAsync(apiKeyLlmExecutor)` 和 `executeWithRescueTimeout` 中的 `supplyAsync(platformExecuteRescueExecutor)`，让所有代码在 `@Async` 线程中同步执行，观察是否能走到 `REVIEW` 或显式异常
4. **架构收口**（若 3 验证成功）：将三层异步收为单层，在 `@Async` 线程中直接 try/catch 包裹同步调用链，timeout 只靠 `Future.get(timeout)` 在 `@Async` 外层做
5. **新增《执行链路架构分析》文档**：`doc/HelloAI_执行链路架构分析.md`，完整记录了每个 Java 类的职责、线程池边界、可能阻塞点和破局角度

##### 6.5 相关资产

- 诊断脚本：`run-redispatch-diagnose.ps1`、`verify-subtask-redispatch-auto-execution.ps1`
- 连通性脚本：`verify-agent-llm-connectivity.ps1`
- 架构分析：`doc/HelloAI_执行链路架构分析.md`
- 差距表：N6 描述已收口为”主链部分可用，但 blocked 场景仍存在静默卡死”

#### 7. 2026-07-10 晚间：调度设计方向切换决定

##### 7.1 决策

- 当前以 `SubTaskAssignedEvent -> SubTaskExecutionService -> PlatformAgentExecutionService -> ApiKeyAgentExecutor -> 同链路回写 REVIEW/BLOCKED` 为核心的“平台内同步长链闭环”方案，不再作为后续调度演进的主方向。
- 后续涉及调度、执行链、MQ 解耦的设计与实现，统一优先参考：
  - `doc/HelloAI_调度解耦重构分析.md`
  - `E:\workspace\AgentTeams-main` 的实际调度源码与状态收敛思路

##### 7.2 原因

- 经过 2026-07-10 下午的多轮 blocked 场景运行态复现，已证明当前链路即使不断叠加超时、线程池、救援逻辑，也仍容易出现 `sub_task_execute_start` 之后的静默卡死。
- 现有结构把“调度决策、执行触发、结果回写”绑在一条长链上，导致耦合度过高，排障成本明显偏高，且已偏离最初希望通过 MQ 实现解耦的设计初衷。
- 对照 `AgentTeams-main` 后确认：更合适的收敛方向应是“调度只负责分配和发命令，执行结果再异步回流状态机，由最终一致完成收敛”。

##### 7.3 对后续开发的约束

- 以后凡是修改以下主题：
  - 子任务调度
  - 自动执行
  - Agent 执行消费
  - 执行结果回写
  - MQ/命令解耦
- 都应先阅读 `doc/HelloAI_调度解耦重构分析.md`，再按任务计划节点回看 `E:\workspace\AgentTeams-main` 对应源码，确认没有偏离开发初衷。

##### 7.4 当前推荐的最小迁移顺序

- 第一步：把 `SubTaskAutoExecutionDispatcher` 从“直接执行”改为“只生成执行命令”
- 第二步：将 `SubTaskExecutionService` 拆分为“执行命令消费”与“执行结果回写”两段
- 第三步：收紧 `SubTaskService` 职责，只保留状态机权威入口与状态更新能力

### 2026-07 调度解耦第一刀：ASSIGNED 改为生成 execution command

#### 1. 范围

- 本轮目标：落实调度解耦第一刀，把 `SubTaskAutoExecutionDispatcher` 从“直接执行”改成“只生成 execution command”
- 关联差距项：
  - `N6`

#### 2. 实际落地

- 新增最小命令载体与事件：
  - `ExecutionCommand`
  - `ExecutionCommandCreatedEvent`
  - `ExecutionCommandConsumer` 扩展接口
- 新增 `ExecutionCommandService`：
  - 在 `ASSIGNED` 后创建 execution command
  - 复用 `agent_execution_record` 插入 `PENDING` 记录，作为当前不改表结构前提下的最小命令持久化痕迹
  - 记录 `sub_task_execution_command_created` 时间线事件
  - 发布命令创建事件，给后续独立消费端留边界
- 改造 `SubTaskAutoExecutionDispatcher`：
  - 保留 `sub_task_auto_execute_dispatch` 时间线
  - 不再调用 `subTaskExecutionService.executeOnce(...)`
  - 改为调用 `executionCommandService.createAssignedCommand(...)`
- 收口执行服务扩展点：
  - `SubTaskExecutionService` 新增 `executeCommand(ExecutionCommand command)`，为后续独立 `ExecutionCommandConsumer` 预留统一执行入口

#### 3. 验证

- 单测验证：
  - `SubTaskAutoExecutionDispatcherTest` 已改为断言“创建执行命令”而非“直接执行”
  - 新增 `ExecutionCommandServiceTest`，验证命令创建、执行记录落 `PENDING`、时间线记录与事件发布
- 构建验证：
  - `mvn -pl helloai-core -am "-Dtest=SubTaskAutoExecutionDispatcherTest,ExecutionCommandServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

#### 4. 影响

- 对外行为变化：
  - `ASSIGNED` 事件现在只生成 execution command，不再在同一事件监听器里直连平台执行链
  - 管理员手工 `/api/sub-tasks/execute/{id}` 入口保持可用，便于后续并行验证
- 文档变化：
  - 《实现差距表》中的 `N6` 已同步收口为“第一刀已完成，但独立消费者与异步回写仍待继续”
- 数据结构变化：
  - 无
  - 继续复用既有 `agent_execution_record`，未新增 execution command 专用表

#### 5. 遗留

- `ExecutionCommandConsumer` 仍是扩展点，尚未落地真实独立消费实现
- `SubTaskExecutionService` 的结果回写链还未完全从旧长链中拆出
- blocked 场景静默卡死尚未因本轮自动消失，下一步需要继续完成“命令消费 / 结果异步回写 / 超时补偿”三段式收口

### 2026-07 调度解耦第二刀：接上本地 ExecutionCommandConsumer

#### 1. 范围

- 本轮目标：在不改表结构、不引入 MQ 的前提下，把 `ExecutionCommandCreatedEvent` 接到本地独立消费端
- 关联差距项：
  - `N6`

#### 2. 实际落地

- 新增 `LocalExecutionCommandConsumer`：
  - 实现 `ExecutionCommandConsumer`
  - 使用 `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` 在事务提交后异步消费 execution command
  - 通过 `subTaskExecutionService.executeCommand(command)` 复用现有最小执行链
- 执行记录状态推进收口：
  - 命令消费开始前，将 `agent_execution_record` 从 `PENDING` 推进到 `RUNNING`
  - 命令执行成功后推进到 `SUCCESS`
  - 命令执行失败后推进到 `FAILED`
- 时间线补点：
  - 新增 `sub_task_execution_command_consume` 事件，显式标记“命令已进入消费端”
- 单测补齐：
  - 新增 `LocalExecutionCommandConsumerTest`
  - 覆盖“事件到达后进入消费端”与“消费失败时执行记录进入 FAILED”两个场景

#### 3. 验证

- 单测验证：
  - `mvn -pl helloai-core -am "-Dtest=ExecutionCommandServiceTest,LocalExecutionCommandConsumerTest,SubTaskAutoExecutionDispatcherTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过
- 构建验证：
  - `mvn -pl helloai-api,helloai-core,helloai-start -am -DskipTests compile`：通过

#### 4. 影响

- 对外行为变化：
  - `ASSIGNED -> execution command created -> local command consumer -> executeCommand(...)` 的最小链路已形成
  - `agent_execution_record` 不再只停留在 `PENDING`，而是具备最小的命令消费状态推进
- 文档变化：
  - 《实现差距表》中的 `N6` 已同步更新为“命令创建 + 本地消费者已接通”
- 数据结构变化：
  - 无

#### 5. 遗留

- 当前消费者仍是本地 Spring 事件版，不是独立进程 / MQ / DB poller 版消费端
- `SubTaskExecutionService` 内部仍同时承担执行编排与结果回写职责，后续还需继续拆分
- blocked 场景静默卡死仍可能沿旧执行链出现，本轮只是把“命令创建”和“命令消费”真正分层，并没有直接关闭该问题

### 2026-07 调度解耦第三刀：拆出 ExecutionResultHandler

#### 1. 范围

- 本轮目标：把执行成功/失败后的状态机回写，从 `SubTaskExecutionService` 中拆到独立结果处理器
- 关联差距项：
  - `N6`

#### 2. 实际落地

- 新增 `ExecutionResultHandler`：
  - 统一承接成功结果回写
    - 更新 `sub_task.context.lastExecution`
    - 推进 `submit -> REVIEW`
    - 记录 `sub_task_execute_submit`
  - 统一承接失败结果回写
    - 更新 `sub_task.context.lastExecution`
    - 在 `IN_PROGRESS` 时推进 `block`
    - 记录 `sub_task_execute_failed`
- 收紧 `SubTaskExecutionService`：
  - 保留“加载执行上下文 / startIfNeeded / 构建 AgentTask / 调平台执行”的职责
  - 不再自己持有 `saveExecutionResult / saveExecutionError`
  - 改为调用 `executionResultHandler.handleSuccess/handleFailure`
- 单测补齐：
  - 新增 `ExecutionResultHandlerTest`
  - 覆盖成功回写与失败回写两个场景

#### 3. 验证

- 单测验证：
  - `mvn -pl helloai-core -am "-Dtest=ExecutionResultHandlerTest,ExecutionCommandServiceTest,LocalExecutionCommandConsumerTest,SubTaskAutoExecutionDispatcherTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过
- 构建验证：
  - `mvn -pl helloai-api,helloai-core,helloai-start -am -DskipTests compile`：通过

#### 4. 影响

- 对外行为变化：
  - 无新增外部接口
  - 执行成功/失败后的状态推进职责已经有独立 service 边界，后续更容易接到 MQ/轮询消费端
- 文档变化：
  - 《实现差距表》中的 `N6` 已同步更新为“命令分发 / 命令消费 / 结果回写”三层最小骨架已建立
- 数据结构变化：
  - 无

#### 5. 遗留

- `LocalExecutionCommandConsumer` 仍是本地 Spring 事件版消费端，不是独立进程 / MQ / DB poller
- `SubTaskExecutionService` 仍保留执行编排职责，后续还可继续向“纯执行器入口”收紧
- blocked 场景静默卡死仍可能发生在旧执行编排链内部，本轮只是把结果回写边界先拆清楚，没有直接关闭该问题


### 2026-07 调度解耦 P1：真实 blocked 场景验收收口

#### 1. 范围
- 本轮目标：对 P1“单层 consumer 同步执行模型”做一次真实 blocked 重分配验收，并把最终运行结果正式回填到项目文档
- 关联差距项：
  - `N6`

#### 2. 实际落地
- 验收前置：
  - 先按 preflight 重新核对基线、差距表、迭代记录、调度解耦分析和代码规范文档
  - 复用已有 ACTIVE `credential_vault` 的真实 `API_KEY_LLM` Agent 作为目标执行器：`targetAgentId=2075602256649543682`
  - 由于当前 shell 中未注入 `DEEPSEEK_API_KEY`，本轮未重新绑定 vault，而是直接复用已有可用凭证做真实调用验收
- 运行态补修：
  - 修复 `ApiKeyAgentExecutor` 调试埋点中的 `Map.of(...)` 空值问题，避免异常路径再次触发 `NullPointerException`
  - 修复 `AgentChatClientService.generate(...)` 在空 `systemPrompt` 时仍调用 `.system("")` 的问题，避免 Spring AI `Assert.hasText(...)` 直接中断真实执行
  - 重新 `mvn -pl helloai-start -am -DskipTests package` 并重启后端，确保运行态使用的是最新 jar
- 最终通过样本：
  - `taskId=2075606213899923459`
  - `subTaskId=2075606214105444353`
  - `sourceAgentId=2075606213811843074`
  - `targetAgentId=2075602256649543682`

#### 3. 验证
- 构建/测试：
  - `mvn -pl helloai-core -am "-Dtest=ApiKeyAgentExecutorTest,AgentChatClientServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：通过
  - `mvn -pl helloai-api,helloai-core,helloai-start -am -DskipTests compile`：通过
  - `mvn -pl helloai-start -am -DskipTests package`：通过
- 手工验证：
  - 真实 blocked 样本连通性探针返回 `connectivitySuccess=True`、`stage=chat_ok`、`credentialReady=True`
  - 运行脚本后轮询结果从 `ASSIGNED` 推进到 `REVIEW`
  - `spring-boot-run.log` 中确认消费线程名为 `exec-cmd-1`，且存在：
    - `sub_task_execution_command_consume`
    - `sub_task_execute_start`
    - `API_KEY_LLM 执行完成`
    - `sub_task_execute_submit`
  - PostgreSQL 取证结果：
    - `sub_task.id=2075606214105444353` -> `status=REVIEW`
    - `agent_execution_record.id=2075606214600372225` -> `status=SUCCESS`
    - `task_timeline` 完整包含：
      - `sub_task_dispatch_prepare`
      - `sub_task_auto_execute_dispatch`
      - `sub_task_execution_command_created`
      - `sub_task_execution_command_consume`
      - `sub_task_execute_start`
      - `sub_task_execute_submit`

#### 4. 影响
- 对外行为变化：
  - blocked 重分配后的平台内真实执行主链已可稳定走到 `REVIEW`，不再停留在 `IN_PROGRESS`
- 文档变化：
  - 《实现差距表》中的 `N6` 已同步改口为“真实 blocked 验收已通过，当前遗留为消费载体与执行编排进一步收紧”
  - 《迭代执行记录》补记了本轮真实验收样本、运行态补修点与数据库/日志证据
- 数据结构变化：
  - 无新增表结构，本轮只复用既有 `agent_execution_record`、`task_timeline` 与 `credential_vault`

#### 5. 遗留
- `LocalExecutionCommandConsumer` 仍是本地 Spring 事件版消费端，尚未切换到独立 MQ / DB poller
- `SubTaskExecutionService` 仍保留部分执行编排职责，后续还可继续向“纯执行器入口”收紧
- offline 场景尚未做同等级别的真实运行态验收，后续应补一条“离线重分配 -> 自动执行 -> REVIEW”的正式取证

### 2026-07 调度解耦 P2 + P4：运行态验证收口

#### 1. 范围
- 本轮目标：对 P2 + P4 收口后的运行态做一次专门验收，重点压两类场景：
  - 并发双击 `/api/sub-tasks/execute/{id}`
  - 超时补偿回滚
- 关联差距项：
  - `N6`

#### 2. 实际落地
- 验收环境：
  - 使用最新 fat jar 启动 `6565`
  - 启动参数临时收紧为：
    - `--helloai.execution.pending-timeout-minutes=1`
    - `--helloai.execution.running-timeout-minutes=1`
  - 运行中确认当前进程命令行为：
    - `helloai-start-1.0.0-SNAPSHOT.jar --helloai.execution.pending-timeout-minutes=1 --helloai.execution.running-timeout-minutes=1`
- 并发双击 `execute` 验证：
  - 新造独立样本：
    - `agentId=2075629381196828674`
    - `taskId=2075629381452681217`
    - `subTaskId=2075629381607870465`
  - 10 路并发打 `POST /api/sub-tasks/execute/{id}`
  - 实际结果：
    - 仅 1 个请求成功创建命令：
      - `recordId=2075629437459222529`
      - `eventId=d2d4e7892e96406296d972f75fc88fb5`
    - 其余请求未再创建新命令，后续都被业务拒绝
  - 运行态取证：
    - `agent_execution_record` 最终仅 1 条目标记录
    - `task_timeline` 仅 1 组命令/执行时间线：
      - `sub_task_execution_command_created`
      - `sub_task_execution_command_consume`
      - `sub_task_execute_start`
      - `sub_task_execute_failed`
- 超时补偿验证：
  - 先做“正常超时补偿”样本：
    - `agentId=2075629705173258241`
    - `taskId=2075629705261338625`
    - `subTaskId=2075629705341030402`
    - 手工插入过期 `RUNNING` 执行记录：
      - `recordId=10783703669672`
      - `eventId=rt-timeout-2dc360b6ce1b4884998c9b124ff41565`
  - 再做“超时补偿回滚撞窗”样本，代表性样本为：
    - `subTaskId=2075633062499700737`
    - `recordId=10783704462190`
    - `eventId=rt-timeout-f808b8630227479aaff36703ae3ab25e`
  - 围绕 `ExecutionCompensationTask` 的 `@Scheduled(fixedRate = 30000)` 节奏，对下一次补偿 tick 做了多轮对时压测，并发触发大量 `POST /api/sub-tasks/pause/{id}` 试图撞进 `markTimeout -> handleFailure -> block()` 的事务窗口

#### 3. 验证
- 运行态日志确认：
  - 当前补偿任务仍按 `fixedRate = 30000` 触发
  - 代表性超时日志：
    - `2026-07-11T01:28:05.552+08:00 Execution RUNNING timeout: eventId=rt-timeout-f808b8630227479aaff36703ae3ab25e, subTaskId=2075633062499700737`
  - 同一补偿事务内继续看到：
    - `sub_task.status: IN_PROGRESS -> BLOCKED`
    - `TaskTimeline event recorded: type=sub_task_execute_failed`
- 并发双击 `execute` 结论：
  - 并发请求下只落 1 条 execution command / execution record
  - 未再观察到重复发命令窗口
- 超时补偿“正常收敛”结论：
  - 过期 `RUNNING` 记录可稳定收敛为：
    - `agent_execution_record.status = TIMEOUT`
    - `error_msg = 执行命令超时`
    - `sub_task.status = BLOCKED`
    - `task_timeline` 存在 `sub_task_execute_failed`
- 超时补偿“撞窗回滚”结论：
  - 多轮撞窗里，最近一次已把首波 `pause` 请求压到补偿开始后约 `174ms`
  - 但补偿事务窗口极短，日志显示从补偿开始到 `BLOCKED + failed timeline` 落库大致只有十几毫秒
  - 当前仍未靠纯外部压测把请求打进事务内部
  - 所有命中的 `pause` 请求最终都撞在补偿完成之后，表现为 `BLOCKED -> PAUSED` 非法状态转移
  - 本轮未观察到 `agent_execution_record=TIMEOUT` 但 `sub_task` 仍停留 `IN_PROGRESS` 的状态撕裂样本

#### 4. 影响
- 对外行为变化：
  - `/api/sub-tasks/execute/{id}` 的“发命令而非直执行业务链”路径已拿到运行态去重证据
  - `ExecutionCompensationTask` 的超时补偿主路径已拿到运行态收敛证据
- 文档变化：
  - 《实现差距表》中的 `N6` 已同步补入：
    - 并发双击 `execute` 去重验证通过
    - 超时补偿可稳定收敛到 `TIMEOUT + BLOCKED + failed timeline`
    - “补偿回滚原子性”当前仍属“运行态未见撕裂，但未完成强证明”
- 数据结构变化：
  - 无

#### 5. 遗留
- `LocalExecutionCommandConsumer` 仍是本地 Spring 事件版消费端，尚未切换到独立 MQ / DB poller
- `SubTaskExecutionService` 仍保留部分执行编排职责，后续还可继续向“纯执行器入口”收紧
- “超时补偿回滚原子性”当前只拿到了运行态侧的反证不足和未见撕裂结论，尚未通过带人工延迟点的受控实验做强证明
- offline 场景仍未补齐与 blocked / execute / timeout 同等级别的正式运行态取证
- 若后续要继续收口 P4，建议优先增加测试专用延迟点或更可控的集成测试，再专门验证“`markTimeout` 已执行但 `handleFailure/block` 抛异常”时是否整条事务回滚


---


### 1. 单轮执行记录模板

```md
### YYYY-MM-DD [迭代主题]

#### 1. 范围
- 本轮目标：
- 关联差距项：

#### 2. 实际落地
- 修改模块：
- 关键文件：
- 完成内容：

#### 3. 验证
- 构建/测试：
- 手工验证：

#### 4. 影响
- 对外行为变化：
- 文档变化：
- 数据结构变化：

#### 5. 遗留
- 未完成项：
- 风险点：
- 下一步建议：
```

### 2. 差距关闭回填模板

```md
#### 差距关闭回填

- 差距编号：
- 关闭日期：
- 关闭方式：改文档 / 做兼容 / 真补功能
- 对应提交/改动：
- 备注：
```
