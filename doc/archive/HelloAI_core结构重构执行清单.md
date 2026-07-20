# HelloAI core 结构重构执行清单

> 目标：消除 core 模块"技术分层 + 业务域分包"两套逻辑并存的结构分裂，统一为**业务域分包 + 域内技术分层**。
> 已验证安全前提：MQ 为显式 ObjectMapper JSON 序列化（不带类全名）；Redis 无 activateDefaultTyping；改包名不影响在途消息与缓存。
> 执行工具：IDEA Move 重构（拖动包/类），勾选 "Search in comments and strings"。禁止手动剪切粘贴。

---

## 0. 前置准备

```bash
git checkout -b refactor/core-domain-package
mvn clean compile && mvn test   # 基线必须全绿，否则先修再迁
```

**纪律**：迁移期间不并行开发其他功能；每个 Step 一个独立 commit，出问题直接 revert。

---

## 1. 目标结构总览

```
com.helloai.core
├── agent/      智能体域：entity / mapper / service / domain / chat / command /
│               dispatcher / execution / executor / mqconsumer / mcp / observability
├── task/       任务域：entity / mapper / service / statemachine / score
├── system/     系统支撑域：entity / mapper / service
└── shared/     跨域基础设施：event / doorbell
```

---

## 2. Step 1 — 迁移 Service（28 个文件，纯 import 变化，零配置风险）

### → `com.helloai.core.agent.service`（11 个）

| 文件 |
|---|
| AgentService |
| AgentExecutionRecordService |
| AgentInboxService |
| AgentDutyLeaseService |
| AgentOutboxService |
| AgentCommandOutboxService |
| AgentMcpServerService |
| AgentExecutionConnectivityService |
| AgentExecutionPreviewService |
| ExternalAgentFailureTracker |
| ConversationService |

### → `com.helloai.core.task.service`（7 个）

| 文件 |
|---|
| TaskService |
| SubTaskService |
| SubTaskDispatchService |
| ReviewService |
| RewardService |
| TaskTimelineService |
| ActivityLogService |

### → `com.helloai.core.system.service`（10 个）

| 文件 |
|---|
| AuthService |
| SysUserService |
| SysConfigService |
| RuleService |
| ModuleService |
| CredentialVaultService |
| CredentialVaultBindingService |
| PromptTemplateService |
| AttachmentService |
| PatrolRecordService ⚠️ 迁移前确认该类是否仍在使用；在用就迁，不用先标记、本次不删 |

**留本步不动**：`McpToolService` —— Step 3 随 mcp 包整体迁入 `agent/mcp`。

**验证**：`mvn clean compile` 通过 → commit：`refactor: migrate services to domain packages`

---

## 3. Step 2 — 迁移 Entity + Mapper（48 个文件 + 1 处注解 + 5 个 XML 共 15 行）

### 3.1 移动 Entity（24 个）

| → `agent.entity`（9） | → `task.entity`（6） | → `system.entity`（9） |
|---|---|---|
| Agent | Task | SysUser |
| AgentCommandOutboxEvent | SubTask | SysConfig |
| AgentDutyLease | ReviewRecord | Rule |
| AgentExecutionRecord | RewardLog | Module |
| AgentInbox | TaskTimeline | CredentialVault |
| AgentMcpServer | ActivityLog | PromptTemplate |
| AgentOutboxEvent | | Attachment |
| ConversationArchive | | RequestLog |
| ConversationMessage | | PatrolRecord |

### 3.2 移动 Mapper（24 个）

| → `agent.mapper`（9） | → `task.mapper`（6） | → `system.mapper`（9） |
|---|---|---|
| AgentMapper | TaskMapper | SysUserMapper |
| AgentCommandOutboxEventMapper | SubTaskMapper | SysConfigMapper |
| AgentDutyLeaseMapper | ReviewRecordMapper | RuleMapper |
| AgentExecutionRecordMapper | RewardLogMapper | ModuleMapper |
| AgentInboxMapper | TaskTimelineMapper | CredentialVaultMapper |
| AgentMcpServerMapper | ActivityLogMapper | PromptTemplateMapper |
| AgentOutboxEventMapper | | AttachmentMapper |
| ConversationArchiveMapper | | RequestLogMapper |
| ConversationMessageMapper | | PatrolRecordMapper |

### 3.3 启动类注解（唯一 1 处）

文件：`helloai-start/src/main/java/com/helloai/HelloAIApplication.java` 第 13 行

```java
// 改前
@MapperScan("com.helloai.core.mapper")

// 改后
@MapperScan({
        "com.helloai.core.agent.mapper",
        "com.helloai.core.task.mapper",
        "com.helloai.core.system.mapper"
})
```

### 3.4 XML 逐行修改（5 个文件共 15 处，IDEA 不会自动改，必须手改）

**① `helloai-core/src/main/resources/mapper/AgentMapper.xml`（5 处）**

| 行号 | 改前 | 改后 |
|---|---|---|
| L3 | `namespace="com.helloai.core.mapper.AgentMapper"` | `namespace="com.helloai.core.agent.mapper.AgentMapper"` |
| L12 | `parameterType="com.helloai.core.entity.Agent"` | `parameterType="com.helloai.core.agent.entity.Agent"` |
| L43 | `parameterType="com.helloai.core.entity.Agent"` | `parameterType="com.helloai.core.agent.entity.Agent"` |
| L96 | `resultType="com.helloai.core.entity.Agent"` | `resultType="com.helloai.core.agent.entity.Agent"` |
| L156 | `resultType="com.helloai.core.entity.Agent"` | `resultType="com.helloai.core.agent.entity.Agent"` |

**② `AgentDutyLeaseMapper.xml`（3 处）**

| 行号 | 改前 | 改后 |
|---|---|---|
| L3 | `...core.mapper.AgentDutyLeaseMapper` | `...core.agent.mapper.AgentDutyLeaseMapper` |
| L10 | `resultType="...core.entity.AgentDutyLease"` | `resultType="...core.agent.entity.AgentDutyLease"` |
| L39 | `resultType="...core.entity.AgentDutyLease"` | `resultType="...core.agent.entity.AgentDutyLease"` |

**③ `AgentOutboxEventMapper.xml`（2 处）**

| 行号 | 改前 | 改后 |
|---|---|---|
| L3 | `...core.mapper.AgentOutboxEventMapper` | `...core.agent.mapper.AgentOutboxEventMapper` |
| L10 | `parameterType="...core.entity.AgentOutboxEvent"` | `parameterType="...core.agent.entity.AgentOutboxEvent"` |

**④ `SubTaskMapper.xml`（4 处）**

| 行号 | 改前 | 改后 |
|---|---|---|
| L3 | `...core.mapper.SubTaskMapper` | `...core.task.mapper.SubTaskMapper` |
| L12 | `parameterType="...core.entity.SubTask"` | `parameterType="...core.task.entity.SubTask"` |
| L73 | `resultType="...core.entity.SubTask"` | `resultType="...core.task.entity.SubTask"` |
| L87 | `resultType="...core.entity.SubTask"` | `resultType="...core.task.entity.SubTask"` |

**⑤ `TaskTimelineMapper.xml`（2 处）**

| 行号 | 改前 | 改后 |
|---|---|---|
| L3 | `...core.mapper.TaskTimelineMapper` | `...core.task.mapper.TaskTimelineMapper` |
| L11 | `parameterType="...core.entity.TaskTimeline"` | `parameterType="...core.task.entity.TaskTimeline"` |

### 3.5 本步验证

```bash
# 以下两条应无任何输出（XML 和 Java 里都不再有旧包名）
grep -rn "com\.helloai\.core\.\(entity\|mapper\)" --include="*.xml" helloai-core/src/main/resources/
grep -rn "com\.helloai\.core\.mapper" --include="*.java" .

mvn clean compile && mvn test
```

通过 → commit：`refactor: migrate entities/mappers + MapperScan + XML namespaces`

---

## 4. Step 3 — 剩余包归位（22 个文件）

| 源位置 | 文件 | 目标位置 |
|---|---|---|
| `core/statemachine/` | SubTaskStateMachine | `core/task/statemachine/` |
| `core/score/` | ImplicitScoreCalculator | `core/task/score/` |
| `core/mcp/`（7 个） | EchoMcpTool、McpAuthContext、McpAuthFilter、McpAuthFilterConfig、McpMcpServer、McpToolConfig、SessionAuthCleaner | `core/agent/mcp/` |
| `core/service/` | McpToolService | `core/agent/mcp/` |
| `core/observability/`（3 个） | CircuitBreakerAlertService、CircuitBreakerEventRecorder、HeartbeatService | `core/agent/observability/` |
| `core/event/`（4 个） | DutyLeaseClosedEvent、ExecutionCommandCreatedEvent、InboxMessageCreatedEvent、SubTaskAssignedEvent | `core/shared/event/` |
| `core/doorbell/`（6 个） | DoorbellDutyListener、DoorbellKeepaliveTask、DoorbellRegistry、DoorbellRinger、DoorbellService、DoorbellSignal | `core/shared/doorbell/` |
| `core/util/` | AgentCapability | `core/agent/`（agent 根包，它是 agent 能力描述） |

⚠️ `McpAuthFilterConfig` 涉及你之前排查过的 CGLIB 缓存问题，迁移后首次启动留意启动日志是否如常。

迁移完成后，旧的 `core/service`、`core/entity`、`core/mapper`、`core/statemachine`、`core/score`、`core/mcp`、`core/observability`、`core/event`、`core/doorbell`、`core/util` 应全部为空包，删除。

**验证**：`mvn clean compile && mvn test` → commit：`refactor: relocate domain infra packages`

---

## 5. Step 4 — 全局收口验证

### 5.1 旧包名残留检查（以下命令应全部无输出）

```bash
grep -rn "import com\.helloai\.core\.service\." --include="*.java" .
grep -rn "import com\.helloai\.core\.entity\." --include="*.java" .
grep -rn "import com\.helloai\.core\.mapper\." --include="*.java" .
grep -rn "import com\.helloai\.core\.\(statemachine\|score\|mcp\|observability\|event\|doorbell\|util\)\." --include="*.java" .
```

### 5.2 全量构建 + 测试

```bash
mvn clean compile && mvn test
```

### 5.3 启动 + 接口冒烟（直接跑根目录 `batch3-test.http` 一轮即可），重点覆盖：

| 冒烟点 | 验证什么 |
|---|---|
| 登录接口 | system 域读路径（SysUserMapper） |
| agent 注册/写入 | **AgentMapper.xml 的 jsonb insert**——验证 XML namespace 改对的最关键一条 |
| agent 列表/详情查询 | agent 域 select 路径 |
| 子任务列表/执行 | SubTaskMapper.xml 关联查询 |
| 等一轮 OutboxRelayTask 或手动触发 | AgentOutboxEventMapper.xml 的 insert |
| 任务时间线写入 | TaskTimelineMapper.xml 的 insert |

任一接口报 `BindingException: Invalid bound statement` 或 `TypeAlias` 相关错误 → 就是对应 XML 的全限定名没改全，回 3.4 节对照。

---

## 6. Step 5 — CODE_STYLE 回写（直接粘贴到 `doc/HelloAI_CODE_STYLE.md` 第 3 章）

```markdown
### 3.x 业务域分包规则

core 模块统一采用"业务域分包 + 域内技术分层"，禁止新增顶层 entity/mapper/service 平铺包：

- com.helloai.core.agent   智能体域（注册、调度、执行、对话、MCP、可观测）
- com.helloai.core.task    任务域（任务、子任务、评审、评分、时间线、状态机）
- com.helloai.core.system  系统支撑域（用户、配置、规则、模块、凭据、附件）
- com.helloai.core.shared  跨域基础设施（event、doorbell）

每个域内固定子包：entity / mapper / service；按域需要可扩展（chat、command、dispatcher 等）。

语义边界（强制）：
- xxx.entity = 映射数据库表的持久化实体
- xxx.domain = 不映射表的纯内存领域对象/值对象（如 ExecutionCommand、AgentTask）

新增类的放置判断：先问"它服务哪个业务域"，再问"它在域内承担什么技术角色"。
跨域通用设施才允许放 shared，放 shared 前需在提交说明中写明理由。
```

commit：`docs: add domain package rules to code style`

---

## 7. 附录：IDEA 自动联动、人工只需核对 diff 的文件

以下文件的 import 由 IDEA Move 自动更新，**不要手改**；提交前过一眼 diff，确认全是纯包名变化、无业务行改动：

- **api 模块（25 个）**：全部 Controller + AuthInterceptor、RequestLogInterceptor、WebMvcConfig
- **job 模块（10 个）**：AgentEventCompensationTask、AgentHealthCheckTask、AssignedSubTaskTimeoutTask、DutyLeaseExpirationTask、ExecutionCompensationTask、ExternalAgentFallbackTask、McpSessionAuthCleanupTask、NotificationConsumer、OutboxRelayTask、SubTaskTimeoutTask
- **test（21 个）**：core/test 下 17 个 + job/test 下 4 个（OutboxRelayTaskTest、AssignedSubTaskTimeoutTaskTest、ExecutionCompensationTaskTest、ExternalAgentFallbackTaskTest）
- **core 内部（15 个）**：agent 域各执行器/调度器中对 service 的引用

## 8. 回滚策略

| 故障 | 回滚动作 |
|---|---|
| Step 1 编译挂 | `git revert HEAD`（service 迁移 commit） |
| Step 2 启动报 Binding 错误 | 优先对照 3.4 补改 XML；无法快速定位则 `git revert HEAD` |
| Step 3 测试挂 | `git revert HEAD`，单个包单独重迁 |

全部完成后合并回主分支，删除重构分支。
