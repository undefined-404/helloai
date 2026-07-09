# HelloAI 技术方案与功能补齐清单

> 归档说明
>
> - 本文件保留为历史技术方案资产，用于回溯设计背景与阶段性补齐思路。
> - 当前项目现实边界请以 `doc/HelloAI_项目基线文档.md` 为准。
> - 当前实现与计划差异请以 `doc/HelloAI_实现差距表.md` 为准。
> - 每轮实际改动请记录到 `doc/HelloAI_迭代执行记录.md`，不再继续把执行日志灌入本文件正文。
> - 正文中出现的“现状 / 已有 / 缺失 / 需新增”等表述，默认保留本方案编写时的历史判断，不直接代表当前仓库最新结论。
> - **每次新增或修改代码前，必须先对照 `doc/HelloAI_CODE_STYLE.md`；如实现与规范不一致，应优先修正文档或实现，不允许绕过规范另起新写法。**

**版本**: v1.1.1  
**日期**: 2026-07-03  
**形成背景**: 基于当时识别出的 20 条问题反馈整理形成的历史补齐方案版本

---

## 目录

1. [架构总览](#一架构总览)
2. [与现有代码骨架的集成映射](#二与现有代码骨架的集成映射--核心)
3. [数据库变更汇总](#三数据库变更汇总)
4. [提示词/技能/规则初始化系统](#四提示词技能规则初始化系统)
5. [Agent 自注册生态系统](#五agent-自注册生态系统)
6. [Agent 收件箱模式](#六agent-收件箱模式)
7. [任务执行引擎与 AI 模型路由](#七任务执行引擎与-ai-模型路由)
8. [多轮对话与附件衔接](#八多轮对话与附件衔接)
9. [任务控制：暂停/恢复/取消](#九任务控制暂停恢复取消)
10. [通知与健康检查](#十通知与健康检查)
11. [安全设计](#十一安全设计)
12. [前端管理页面规划](#十二前端管理页面规划)
13. [测试策略](#十三测试策略)
14. [部署方案](#十四部署方案)
15. [数据迁移方案](#十五数据迁移方案)
16. [API 端点补齐清单](#十六api-端点补齐清单)
17. [实施路线图](#十七实施路线图)
18. [附录](#十八附录)

---

## 一、架构总览

### 1.1 三条核心原则

| 原则 | 说明 |
|------|------|
| **Agent 不消费 MQ** | MQ 是平台内部事件总线。Agent 永远只通过 HTTP API 与平台交互 |
| **数据库是唯一事实源** | 任务详情、对话历史、附件、规则全部从 DB 通过 HTTP API 拉取 |
| **平台不关心 Agent 运行时类型** | 只认 API Key + HTTP，无论 Claude Code / Qoder / OpenClaw 均等对待 |

### 1.2 收件箱模式

```
MQ 不做任务传输，只做事件通知。

MQ 消息体 = eventId + type + entityId    (轻量级，只含引用)
Agent 拿到消息 → HTTP API → 从 DB 拉完整上下文
```

```
┌─────────────────────────────────────────────────────────────────┐
│                     HelloAI Platform (Java)                      │
│                                                                  │
│  ┌────────────────────────┐    ┌──────────────────────────────┐ │
│  │  现有: SubTaskService   │    │  新增: NotificationConsumer   │ │
│  │  changeStatus()         │    │  (消费 MQ → 写 agent_inbox)  │ │
│  │  → AgentOutboxService   │    │                              │ │
│  │    .createEvent()       │    │  新增: AgentInboxService     │ │
│  │    (状态变更 → MQ 消息)  │    │  send() / getMessages()      │ │
│  └───────────┬────────────┘    └──────────────┬───────────────┘ │
│              │                                │                  │
│              ▼                                ▼                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │               MQ (RabbitMQ - 已有配置)                     │   │
│  │  helloai.agent.exchange                                  │   │
│  │  ├─ executor.queue   (已有)  ← 新增 notification.queue    │   │
│  │  ├─ reviewer.queue   (已有)                               │   │
│  │  ├─ planner.queue    (已有)                               │   │
│  │  └─ patrol.queue     (已有)                               │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                   HTTP API (Agent 入口)                    │   │
│  │  已有: POST /agents/register   GET /tools/cli             │   │
│  │  已有: GET /sub-tasks/**       POST /reviews              │   │
│  │  新增: GET /agent/inbox        GET /agents/me/skill       │   │
│  │  新增: GET /admin/prompts/**   GET /attachments/{id}/download ││
│  └──────────────────────────────────────────────────────────┘   │
│                          ▲                                       │
└──────────────────────────┼───────────────────────────────────────┘
                           │ HTTP (Agent 主动调用)
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
    ┌──────────┐    ┌──────────┐    ┌──────────┐
    │Claude Code│   │  Qoder   │   │ OpenClaw │
    │ 查收件箱  │    │ 查收件箱  │    │ cron 唤醒 │
    │ 拉上下文  │    │ 拉上下文  │    │ 查收件箱  │
    └──────────┘    └──────────┘    └──────────┘
```

### 1.3 实时通知：仅两种策略

| 策略 | 实时性 | 适用范围 | 说明 |
|------|:---:|---|------|
| **Agent 轮询收件箱** | 10-60s | 所有 Agent 类型（默认） | Agent prompt 要求第一步查收件箱 |
| **SSE 长连接** | < 1s | 能维持连接的 Agent | 收到 `new_message` 事件后再查收件箱 |

> ~~**已删除**: 文件系统钩子通知（`.new_messages` 文件）。Agent 可能运行在远程容器/无文件系统环境，与"Agent 类型无关"原则冲突。~~  v1.1 删除

---

## 二、与现有代码骨架的集成映射（核心）

> **此为 v1.1 新增章节**。说明每个新组件如何接入已有的 17 个 Java 类，避免"设计完美、落地断层"。

### 2.1 现有组件总览

| 组件 | 位置 | 角色 |
|------|------|------|
| `SubTaskStateMachine` | helloai-core/statemachine/ | 静态类，纯校验。`TRANSITIONS` 定义 8 种状态的合法转移 |
| `SubTaskService` | helloai-core/service/ | 扩展 `ServiceImpl`，注入 `AgentOutboxService`。`changeStatus()` 核心方法 |
| `AgentOutboxService` | helloai-core/service/ | 状态变更后写 Outbox 事件，含 `resolveRoutingKey()` 逻辑 |
| `ReviewService` | helloai-core/service/ | 创建审查 → 调用 `SubTaskService.changeStatus()` → 调用 `RewardService.addReward()` |
| `RewardService` | helloai-core/service/ | `addReward(agentId, reason, delta, subTaskId)` |
| `ImplicitScoreCalculator` | helloai-core/service/score/ | `@Component`，`calculate()` 返回 `ScoreResult`。**当前未被任何 Service 调用** |
| `RabbitMQConfig` | helloai-mq/config/ | 已有 4 个角色队列 + DLX。需新增 1 个通知队列 |
| `AuthInterceptor` | helloai-api/interceptor/ | 拦截 `/api/**`，验证 `X-Admin-Token` 或 `Authorization: Bearer` |
| `WebMvcConfig` | helloai-api/config/ | 注册拦截器，排除无需认证的路径 |
| `AgentConfigProperties` | helloai-common/config/ | `registrationToken` + `allowRegistration` |
| `SubTask` (Entity) | helloai-core/entity/ | 含 `deliverable`, `acceptance`, `priority`, `reworkCount`, `completedAt`, `@Version` |
| `Agent` (Entity) | helloai-core/entity/ | 含 `modelType`。需增加 `modelConfig`, `specializationSlug` |
| `ToolsController` | helloai-api/controller/ | `GET /api/tools/cli`。需增强：运行时替换 BASE_URL |
| `SetupController` | helloai-api/controller/ | `GET /api/setup/status`, `POST /api/setup/initialize` |
| `HealthController` | helloai-api/controller/ | `GET /api/health` |
| `AdminInitializer` | helloai-start/config/ | `CommandLineRunner`，创建默认 admin 用户 |
| `AgentHealthCheckTask` | helloai-job/task/ | 已有骨架，需补充具体逻辑 |

### 2.2 新增组件与现有组件的集成点

```
新增组件                         依赖的现有组件                  集成方式
───────────────────────────────────────────────────────────────────────
AgentInboxService               无                            独立新 Service
  └─ send()                     被 NotificationConsumer 调用

NotificationConsumer            无                            新 Consumer
  → 消费 helloai.notification.queue
  → 调用 AgentInboxService.send()

PromptTemplateService           无                            独立新 Service
  └─ composePrompt()            无                            纯查询 prompt_template 表
  └─ getSkillForAgent()         无                            查询 + 变量替换
  └─ generateOnboarding()       AgentConfigProperties         读取 registrationToken

ConversationService             无                            独立新 Service
  └─ addMessage()               SubTask (Entity)              关联 sub_task_id

AgentExecutor (接口)             PromiseTemplateService        注入 promptService
  └─ ClaudeExecutor             AgentRouter                   被 Router 调用
  └─ CodexExecutor                                             调用 Anthropic/OpenAI API

AgentRouter                     Agent (Entity)                读取 agent.modelType
  └─ route(Agent)               所有 AgentExecutor 实现类       Spring List 注入

AgentController (增强)           AgentService (已有)           添加 /me/skill 端点
  └─ getMySkill()               PromptTemplateService         新增注入

ToolsController (增强)           AgentConfigProperties         新增注入
  └─ downloadCli()              已有                          BASE_URL 替换逻辑

ReviewService (增强)             ImplicitScoreCalculator       新增注入
  └─ createReview()              已有 Rewarding 集成           审批后异步调 calculate()

SubTaskStateMachine (扩展)       无                            静态 Map 增加 PAUSED
  └─ TRANSITIONS                 SubTaskStatus (已有)          增加枚举值 PAUSED

SubTaskService (扩展)            AgentInboxService             新增注入
  └─ changeStatus()              已有 AgentOutboxService       变更后调 inbox.send()

AgentHealthCheckTask (补充)       AgentService (已有)           注入 agentService
  └─ 已有骨架，补充健康检查逻辑   AgentInboxService             注入 inboxService

RabbitMQConfig (扩展)            已有 5 个队列                 增加 1 个通知队列
  └─ notificationQueue()         已有 2 个 Exchange            绑定到 agentExchange
```

### 2.3 状态机扩展：增加 PAUSED 状态

**涉及文件**: `SubTaskStatus.java` (枚举), `SubTaskStateMachine.java` (静态转换表)

```java
// SubTaskStatus 枚举新增
PAUSED    // 已暂停（平台主动暂停任务，Agent 保留中间状态）

// SubTaskStateMachine.TRANSITIONS 变更
IN_PROGRESS → PAUSED       // 新增：平台暂停正在执行的任务
PAUSED      → IN_PROGRESS   // 新增：恢复暂停的任务
PAUSED      → CANCELLED     // 新增：取消已暂停的任务
```

完整转换图（变更后）：

```
                    ┌─────────┐
                    │ PENDING │
                    └────┬────┘
                    ┌────┴────┐
                    │ ASSIGNED │
                    └────┬────┘
                    ┌────┴──────┐
              ┌─────│IN_PROGRESS│─────┐
              │     └─────┬────┘     │
              ▼           │          ▼
         ┌────────┐       │     ┌────────┐
         │ PAUSED │◄──────┘     │BLOCKED │
         └───┬────┘             └───┬────┘
             │      恢复            │ 重新分配
             └──────────────────────┼──────► PENDING → ASSIGNED
                                   │
              ┌────────┐           │
              │ REVIEW │◄──────────┘
              └───┬────┘
         ┌────────┴────────┐
         ▼                 ▼
    ┌────────┐        ┌────────┐
    │  DONE  │        │ REWORK │──► IN_PROGRESS
    └────────┘        └────────┘
```

> **注意**: openMoss 用 `BLOCKED` 作为"暂停等待排障"，但语义不够精确。增加 `PAUSED` 后，`BLOCKED` 专门用于"遇到问题需 Planner 排障"，`PAUSED` 专门用于"平台主动暂停（人的操作）"。

> ⚠️ **实施注意 (v1.1.1)**: 增加 `PAUSED` 枚举值后，需检查所有 `switch(status)` 和 `if-else` 全覆盖逻辑：
> - `AgentOutboxService.resolveRoutingKey()` — 需增加 `PAUSED` 对应的 routing key
> - `NotificationConsumer.resolveTargetAgents()` — 已有 default 分支，无需修改
> - 前端状态标签渲染 — 需增加 `PAUSED` 的显示样式
> - IDE 会在编译时对 enum switch 语句自动标红未覆盖分支，Phase 1 Day 1 一次性修复即可

### 2.4 认证体系衔接

**现状**: `AuthInterceptor` 已实现双通道认证。无需新增 Spring Security 配置。

```
请求 → AuthInterceptor.preHandle()
       ├─ X-Admin-Token 存在 → AuthService.validateAdminToken() → 通过 (admin)
       ├─ Authorization: Bearer xxx → AuthService.validateAgentKey() → 通过 (agent)
       └─ 都不存在 → 401

路径排除 (WebMvcConfig):
  /api/auth/login, /api/auth/logout, /api/agents/register,
  /api/health/**, /api/setup/**, /api/feed/**
```

**新增端点注册**: 在 `WebMvcConfig.addInterceptors()` 中确认 `/api/agent/inbox/**` 和 `/api/agents/me/skill` 不在排除列表中（让拦截器正常验证 API Key）。

### 2.5 Review → Score → 评分链路的闭合

**现状**: `ReviewService.createReview()` 已经调用 `RewardService.addReward()` 处理**基于审查分数的即时奖惩**。但 `ImplicitScoreCalculator.calculate()` 是独立的，**没有被任何地方调用**。

**修正**: 在子任务完成（状态变为 DONE）时触发隐式评分：

```java
// SubTaskService.complete() 中增加:
@Transactional
public void complete(Long subTaskId) {
    SubTask subTask = getById(subTaskId);
    SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.DONE);

    subTask.setStatus(SubTaskStatus.DONE);
    subTask.setCompletedAt(OffsetDateTime.now());
    updateById(subTask);

    // === v1.1 新增：完成时触发隐式评分 ===
    List<ReviewRecord> reviews = reviewService.getBySubTaskId(subTaskId);
    int blockCount = activityLogService.countByAction(subTaskId, "blocked");
    int timeoutCount = subTask.getTimeoutCount();

    ImplicitScoreCalculator.ScoreResult scoreResult =
        implicitScoreCalculator.calculate(subTask, reviews, blockCount, timeoutCount);

    // 写回评分结果到 sub_task
    subTask.setScoreFactors(convertToMap(scoreResult.getFactors()));
    subTask.setCompositeScore(scoreResult.getCompositeScore());
    subTask.setScoreGrade(scoreResult.getGrade());
    updateById(subTask);

    // 隐式积分奖惩
    if (scoreResult.getRewardDelta() != null && scoreResult.getRewardDelta() != 0) {
        rewardService.addReward(
            subTask.getAssignedAgent(),
            "隐式评分(" + scoreResult.getGrade() + "级)",
            scoreResult.getRewardDelta(),
            subTaskId
        );
    }

    agentOutboxService.createEvent(subTask, SubTaskStatus.DONE);
}
```

### 2.6 conversation_archive 与 conversation_message 的关系

| 表 | 用途 | 数据来源 |
|----|------|---------|
| `conversation_archive` | **只读归档**。保留原有数据，不接受新写入 | 历史数据 |
| `conversation_message` | **活跃对话**。所有新消息写入此表 | 平台 Consumer + Agent 提交 |

Flyway 迁移脚本中不需要删除 `conversation_archive`，而是将其标记为"已废弃，仅保留读取"。新代码全部写入 `conversation_message`。

---

## 三、数据库变更汇总

### 3.1 Flyway 脚本清单

| 脚本 | 内容 | 涉及表 |
|------|------|------|
| V5__enhance_prompt_template.sql | prompt_template 增加 category/slug/description/is_example + 种子数据 | prompt_template |
| V6__agent_inbox.sql | 新建 agent_inbox 表 | agent_inbox |
| V7__conversation_message.sql | 新建 conversation_message 表 | conversation_message |
| V8__agent_model_fields.sql | agent 增加 model_config(JSONB) + specialization_slug | agent |
| V9__seed_global_rule.sql | rule 表预置全局规则 | rule |
| V10__add_paused_status.sql | sub_task 状态约束增加 PAUSED | sub_task |

> **变更说明**: v1.0 原计划 V10 增加 `deliverable/acceptance/priority/reworkCount/completedAt` 字段，但检查现有代码发现这些字段 **已存在于 SubTask Entity 和 V3__sub_task_fields.sql 中**。v1.1 移除冗余迁移。

### 3.2 核心新表 DDL

#### agent_inbox

```sql
-- V6__agent_inbox.sql
CREATE TABLE IF NOT EXISTS agent_inbox (
    id              BIGSERIAL PRIMARY KEY,
    agent_id        BIGINT NOT NULL REFERENCES agent(id),

    -- 幂等标识
    event_id        VARCHAR(64) NOT NULL,       -- MQ 事件 ID（来自 AgentOutboxEvent.eventId）
    -- 联合唯一约束：同一事件对同一 Agent 只投递一次
    CONSTRAINT uq_inbox_event_agent UNIQUE (event_id, agent_id),

    -- 消息内容
    event_type      VARCHAR(64) NOT NULL,
    title           VARCHAR(255) NOT NULL,
    summary         TEXT,

    -- 关联业务实体
    ref_type        VARCHAR(32),
    ref_id          BIGINT,

    -- Agent 处理状态
    is_read         SMALLINT NOT NULL DEFAULT 0,
    is_archived     SMALLINT NOT NULL DEFAULT 0,
    read_at         TIMESTAMPTZ,

    -- 优先级
    priority        VARCHAR(16) NOT NULL DEFAULT 'NORMAL',

    -- 过期
    expires_at      TIMESTAMPTZ,

    create_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inbox_agent_unread
    ON agent_inbox(agent_id, is_read, priority, create_time)
    WHERE is_read = 0 AND is_archived = 0;

CREATE INDEX idx_inbox_event_id ON agent_inbox(event_id);
CREATE INDEX idx_inbox_expires ON agent_inbox(expires_at)
    WHERE expires_at IS NOT NULL AND is_archived = 0;
```

> **v1.1 修正**: 将 `message_id VARCHAR(64) UNIQUE` 改为 `(event_id, agent_id)` 联合唯一约束。避免"同一事件通知多个 Agent 时 message_id 冲突"的问题。

#### conversation_message

```sql
-- V7__conversation_message.sql
CREATE TABLE IF NOT EXISTS conversation_message (
    id              BIGSERIAL PRIMARY KEY,
    sub_task_id     BIGINT NOT NULL REFERENCES sub_task(id),

    message_id      VARCHAR(64) NOT NULL UNIQUE,

    role            VARCHAR(16) NOT NULL,       -- system / user / assistant / tool
    sender_type     VARCHAR(16) NOT NULL,       -- platform / agent / human
    sender_id       BIGINT,

    content         TEXT NOT NULL,
    content_type    VARCHAR(32) DEFAULT 'text',

    reply_to_id     BIGINT,
    tool_call_id    VARCHAR(64),
    tool_name       VARCHAR(64),

    token_count     INT,

    attachment_ids  TEXT,                       -- JSON 数组字符串: [1, 2, 3]

    seq             INT NOT NULL,

    create_time     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by       VARCHAR(64) NOT NULL DEFAULT ''
);

CREATE INDEX idx_conv_sub_task ON conversation_message(sub_task_id, seq);
CREATE INDEX idx_conv_message_id ON conversation_message(message_id);
CREATE INDEX idx_conv_reply ON conversation_message(reply_to_id);
```

### 3.3 prompt_template 表 DDL

基于**现有** prompt_template 表改造（不删除现有字段，仅增加）：

```sql
-- V5__enhance_prompt_template.sql
ALTER TABLE prompt_template
    ADD COLUMN IF NOT EXISTS category      VARCHAR(32)  NOT NULL DEFAULT 'ROLE_TEMPLATE',
    ADD COLUMN IF NOT EXISTS slug          VARCHAR(128),
    ADD COLUMN IF NOT EXISTS description   VARCHAR(500) DEFAULT '',
    ADD COLUMN IF NOT EXISTS is_example    SMALLINT     NOT NULL DEFAULT 0;

-- 替换现有 4 条极简记录为完整内容（13 条种子数据）
-- ... 见 4.3 节
```

### 3.4 agent 表扩展

```sql
-- V8__agent_model_fields.sql
ALTER TABLE agent
    ADD COLUMN IF NOT EXISTS model_config         JSONB,
    ADD COLUMN IF NOT EXISTS specialization_slug  VARCHAR(128);
```

### 3.5 状态约束扩展

```sql
-- V10__add_paused_status.sql
ALTER TABLE sub_task
    DROP CONSTRAINT IF EXISTS chk_sub_task_status,
    ADD CONSTRAINT chk_sub_task_status CHECK (
        status IN ('PENDING', 'ASSIGNED', 'IN_PROGRESS', 'REVIEW',
                   'DONE', 'REWORK', 'BLOCKED', 'PAUSED', 'CANCELLED')
    );
```

### 3.6 全局规则种子数据

```sql
-- V9__seed_global_rule.sql
INSERT INTO rule (id, name, rule_type, priority, content, deleted, remark)
VALUES (
    3000000000000000001,
    '全局默认规则',
    'global',
    0,
    '<openMoss global-rule-example.md 的完整内容>',
    0,
    '系统首次启动自动创建。Agent 每次执行前通过 rules 命令获取。'
) ON CONFLICT (id) DO NOTHING;
```

---

## 四、提示词/技能/规则初始化系统

### 4.1 存储策略

**全部存 DB，统一管理**。与 v1.0 方案一致，不做变更。

- `prompt_template` 表（改造后）：存所有角色模板、Agent 专业化配置、SKILL.md
- `rule` 表（现有）：存全局规则
- `sys_config` 表（现有）：存 onboarding 引导文本
- `scripts/task-cli.py`（文件）：保留为 classpath 资源

### 4.2 category 区分

| category | 用途 | 运行时变量替换 |
|----------|------|:---:|
| `ROLE_TEMPLATE` | 角色基础身份（planner/executor/reviewer/patrol） | {{BASE_URL}} |
| `AGENT_SPECIALIZATION` | Agent 专业化变体（backend/frontend/devops/researcher/tester） | {{BASE_URL}} |
| `SKILL` | CLI 命令参考手册 | {{API_KEY}}, {{BASE_URL}}, {{AGENT_NAME}} |

### 4.3 种子数据内容（13 条 + 1 规则）

从 openMoss 的 1696 行文件中提取，写入 `V5__enhance_prompt_template.sql` 的 INSERT 语句。

| # | category | role | slug | name | 来源文件 |
|---|----------|------|------|------|------|
| 1 | ROLE_TEMPLATE | PLANNER | — | 规划者默认模板 | task-planner.md (140行) |
| 2 | ROLE_TEMPLATE | EXECUTOR | — | 执行者默认模板 | executor.md (107行) |
| 3 | ROLE_TEMPLATE | REVIEWER | — | 审查者默认模板 | task-reviewer.md (75行) |
| 4 | ROLE_TEMPLATE | PATROL | — | 巡检者默认模板 | task-patrol.md (83行) |
| 5 | AGENT_SPECIALIZATION | EXECUTOR | executor-backend | AI酱瓜-后端 | executor-backend.md (122行) |
| 6 | AGENT_SPECIALIZATION | EXECUTOR | executor-frontend | AI小珂-前端 | executor-frontend.md (121行) |
| 7 | AGENT_SPECIALIZATION | EXECUTOR | executor-devops | AI小云-运维 | executor-devops.md (123行) |
| 8 | AGENT_SPECIALIZATION | EXECUTOR | executor-researcher | AI小吴-调研 | executor-researcher.md (119行) |
| 9 | AGENT_SPECIALIZATION | EXECUTOR | executor-tester | AI小安-测试 | executor-tester.md (121行) |
| 10 | SKILL | PLANNER | — | Planner Skill | task-planner-skill/SKILL.md (104行) |
| 11 | SKILL | EXECUTOR | — | Executor Skill | task-executor-skill/SKILL.md (99行) |
| 12 | SKILL | REVIEWER | — | Reviewer Skill | task-reviewer-skill/SKILL.md (97行) |
| 13 | SKILL | PATROL | — | Patrol Skill | task-patrol-skill/SKILL.md (89行) |

> **适配要点**: openMoss 原版 prompt 中包含 "OpenClaw cron 定时唤醒" 相关描述。写入 DB 前需将唤醒方式改为"查收件箱 + 事件驱动"。具体替换：将"你通过 OpenClaw cron 定时唤醒"改为"你通过事件通知或定时轮询收件箱来获取任务"，将唤醒检查流程的第 1 步从"rules"改为"查收件箱 → GET /api/agent/inbox"。

---

## 五、Agent 自注册生态系统

### 5.1 注册链路（无变更，与 v1.0 一致）

```
管理员 compose_prompt → 操作员配置到 Agent 运行时 → Agent 自注册
  → POST /api/agents/register (验证 registration_token)
  → GET /api/tools/cli (下载 CLI，BASE_URL 已替换)
  → GET /api/agents/me/skill (获取 SKILL.md，API Key 已填入)
  → 开始工作
```

### 5.2 关键端点实现

#### POST /api/agents/register

```java
// 集成点: AgentConfigProperties (已有)
// 集成点: AgentService (已有)
@PostMapping("/register")
public R<AgentRegisterResponse> register(
        @RequestBody AgentRegisterRequest req,
        @RequestHeader("X-Registration-Token") String token) {

    // 1. 自注册开关
    if (!agentConfigProperties.isAllowRegistration()) {
        throw new BizException(403, "Agent 自注册已关闭，请联系管理员");
    }

    // 2. 注册令牌验证
    if (!agentConfigProperties.getRegistrationToken().equals(token)) {
        throw new BizException(403, "注册令牌无效");
    }

    // 3. 角色校验
    AgentRole role;
    try {
        role = AgentRole.valueOf(req.getRole().toUpperCase());
    } catch (IllegalArgumentException e) {
        throw new BizException(400, "无效角色，可选: PLANNER, EXECUTOR, REVIEWER, PATROL");
    }

    // 4. 创建 Agent
    Agent agent = new Agent();
    agent.setName(req.getName());
    agent.setRole(role);
    agent.setApiKey("ak_" + RandomStringUtils.randomAlphanumeric(32).toLowerCase());
    agent.setStatus(AgentStatus.ACTIVE);
    agent.setScore(0);
    agentService.save(agent);

    // 5. API Key 仅此时返回
    return R.ok(AgentRegisterResponse.builder()
        .id(agent.getId())
        .name(agent.getName())
        .role(agent.getRole().name())
        .apiKey(agent.getApiKey())
        .message("注册成功，请保存 API Key。该 Key 仅在本次返回，遗失需联系管理员重置。")
        .build());
}

// API Key 重置（v1.1 新增）
@PostMapping("/admin/agents/{id}/reset-key")
public R<AgentRegisterResponse> resetApiKey(@PathVariable Long id) {
    Agent agent = agentService.getById(id);
    if (agent == null) throw new BizException(404, "Agent 不存在");

    agent.setApiKey("ak_" + RandomStringUtils.randomAlphanumeric(32).toLowerCase());
    agentService.updateById(agent);

    return R.ok(AgentRegisterResponse.builder()
        .id(agent.getId())
        .apiKey(agent.getApiKey())
        .message("API Key 已重置。旧 Key 立即失效。请保存新 Key。")
        .build());
}
```

#### GET /api/agents/me/skill

```java
// 集成点: PromptTemplateService (新增)
@GetMapping("/me/skill")
public ResponseEntity<String> getMySkill(@RequestAttribute("_authId") Long agentId) {
    Agent agent = agentService.getById(agentId);

    PromptTemplate skill = promptTemplateService
        .getByRoleAndCategory(agent.getRole().name(), "SKILL");

    String content = skill.getContent();
    content = content.replace("<注册后填入>", agent.getApiKey());
    content = content.replace("{{BASE_URL}}", resolveBaseUrl());
    content = content.replace("{{AGENT_NAME}}", agent.getName());

    return ResponseEntity.ok()
        .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
        .body(content);
}
```

#### GET /api/tools/cli（增强）

```java
// 集成点: ToolsController (已有) — 增强 BASE_URL 替换逻辑
@GetMapping("/cli")
public ResponseEntity<String> downloadCli(HttpServletRequest request) {
    String content;
    try {
        content = Resources.toString(
            new ClassPathResource("scripts/task-cli.py").getURL(), StandardCharsets.UTF_8);
    } catch (IOException e) {
        throw new BizException(500, "CLI 脚本加载失败");
    }

    // 运行时替换 BASE_URL
    String baseUrl = resolveBaseUrl(request);
    content = content.replaceFirst(
        "BASE_URL\\s*=\\s*\"[^\"]*\"",
        "BASE_URL = \"" + baseUrl + "\"");

    return ResponseEntity.ok()
        .contentType(new MediaType("text", "plain"))
        .body(content);
}
```

### 5.3 不同 Agent 运行时对接（无变更）

| 运行时 | 配置方式 | 调用方式 | 查收件箱频率 |
|--------|---------|---------|:---:|
| Claude Code | CLAUDE.md (compose_prompt 输出) | Bash 工具执行 curl | 用户操作时 |
| Qoder | .qoder/config | 终端执行命令 | 用户操作时 |
| OpenClaw | Skill system prompt | cron → task-cli.py | 每 30-60s |
| 自定义 Agent | HTTP client | 直接调用 API | 自行控制 |

---

## 六、Agent 收件箱模式

### 6.1 关键设计决策（v1.1 修正）

| 决策 | v1.0 | v1.1 修正 |
|------|------|----------|
| 幂等约束 | `message_id UNIQUE` | `(event_id, agent_id)` 联合唯一 |
| 通知策略 | 3 层（轮询+文件+SSE） | 2 层（轮询+SSE），删除文件钩子 |
| 与 MQ 的关系 | NotificationConsumer 写 inbox | 不变，但明确 MQ 只用 eventId |

### 6.2 NotificationConsumer（完整实现）

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final AgentInboxService agentInboxService;
    private final MessageDeduplicationService dedupService;  // 已有 (helloai-mq)

    @RabbitListener(queues = "helloai.notification.queue")
    public void onNotification(Message message, Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        String eventId = null;
        try {
            DomainEvent event = JSON.parseObject(message.getBody(), DomainEvent.class);
            eventId = event.getEventId();

            // 1. 幂等检查（Redis + DB 双重去重，复用已有的 MessageDeduplicationService）
            if (!dedupService.markProcessed(eventId)) {
                channel.basicAck(tag, false);
                return;
            }

            // 2. 根据事件类型计算目标 Agent 列表
            List<Long> targetAgentIds = resolveTargetAgents(event);

            // 3. 为每个目标 Agent 投递收件箱消息
            for (Long agentId : targetAgentIds) {
                try {
                    agentInboxService.send(agentId, event);
                } catch (DuplicateKeyException e) {
                    // (event_id, agent_id) 联合唯一约束 → 已投递，跳过
                    log.debug("收件箱消息已存在: eventId={}, agentId={}", eventId, agentId);
                }
            }

            channel.basicAck(tag, false);

        } catch (Exception e) {
            log.error("通知处理失败: eventId={}", eventId, e);
            // 不 nack，防止死循环。通知丢失可接受（Agent 轮询可兜底）
            try { channel.basicAck(tag, false); } catch (Exception ignored) {}
        }
    }

    private List<Long> resolveTargetAgents(DomainEvent event) {
        return switch (event.getType()) {
            case "sub_task.assigned" ->
                List.of(event.getAgentId());                        // 通知被分配者
            case "sub_task.submitted" ->
                // ⚠️ 实施注意 (v1.1.1): 通知所有 Reviewer 存在并发竞争。
                // 多个 Reviewer 可能同时查收件箱并争抢同一审查任务。
                // 解决方案：在 Reviewer 的 prompt 中要求
                // "审查前先调 POST /api/sub-tasks/{id}/claim-review"，
                // 该端点使用 Redis SETNX 分布式锁保证只有一个 Reviewer 获得审查权。
                // lock key: "review:claim:{subTaskId}", TTL: 300s
                agentService.listByRole(AgentRole.REVIEWER.name())
                    .stream().map(Agent::getId).toList();
            case "sub_task.rejected" ->
                List.of(event.getAgentId());                        // 通知被驳回者
            case "sub_task.blocked" ->
                agentService.listByRole(AgentRole.PLANNER.name())   // 通知所有 Planner
                    .stream().map(Agent::getId).toList();
            case "sub_task.paused", "sub_task.resumed", "sub_task.cancelled" ->
                List.of(event.getAgentId());                        // 通知执行者
            case "task.completed" ->
                agentService.listByRole(AgentRole.PLANNER.name())
                    .stream().map(Agent::getId).toList();
            default -> List.of();
        };
    }
}
```

### 6.3 AgentInboxService

```java
@Service
@RequiredArgsConstructor
public class AgentInboxService extends ServiceImpl<AgentInboxMapper, AgentInbox> {

    @Transactional
    public void send(Long agentId, DomainEvent event) {
        AgentInbox inbox = new AgentInbox();
        inbox.setAgentId(agentId);
        inbox.setEventId(event.getEventId());   // eventId + agentId 联合唯一
        inbox.setEventType(event.getType());
        inbox.setTitle(buildTitle(event));
        inbox.setSummary(buildSummary(event));
        inbox.setRefType(event.getEntityType());
        inbox.setRefId(event.getEntityId());
        inbox.setPriority(resolvePriority(event));
        save(inbox);
    }

    public List<AgentInbox> getUnread(Long agentId, int limit) {
        return lambdaQuery()
            .eq(AgentInbox::getAgentId, agentId)
            .eq(AgentInbox::getIsRead, 0)
            .eq(AgentInbox::getIsArchived, 0)
            .orderByDesc(AgentInbox::getPriority)
            .orderByDesc(AgentInbox::getCreateTime)
            .last("LIMIT " + limit)
            .list();
    }

    public long countUnread(Long agentId) {
        return lambdaQuery()
            .eq(AgentInbox::getAgentId, agentId)
            .eq(AgentInbox::getIsRead, 0)
            .eq(AgentInbox::getIsArchived, 0)
            .count();
    }

    @Transactional
    public void markRead(Long agentId, Long inboxId) {
        lambdaUpdate()
            .eq(AgentInbox::getId, inboxId)
            .eq(AgentInbox::getAgentId, agentId)
            .set(AgentInbox::getIsRead, 1)
            .set(AgentInbox::getReadAt, OffsetDateTime.now())
            .update();
    }

    @Transactional
    public void markArchived(Long agentId, Long inboxId) {
        lambdaUpdate()
            .eq(AgentInbox::getId, inboxId)
            .eq(AgentInbox::getAgentId, agentId)
            .set(AgentInbox::getIsArchived, 1)
            .update();
    }

    private String buildTitle(DomainEvent event) { /* 根据事件类型生成标题 */ }
    private String buildSummary(DomainEvent event) { /* 生成摘要 */ }
    private String resolvePriority(DomainEvent event) { /* 判断优先级 */ }
}
```

### 6.4 Agent 查询收件箱 API

```java
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentInboxController {

    private final AgentInboxService inboxService;

    @GetMapping("/inbox")
    public R<PageResult<InboxVO>> getInbox(
            @RequestAttribute("_authId") Long agentId,
            @RequestParam(defaultValue = "unread") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        // ...
    }

    @PutMapping("/inbox/{id}/read")
    public R<Void> markRead(@RequestAttribute("_authId") Long agentId,
                            @PathVariable Long id) {
        inboxService.markRead(agentId, id);
        return R.ok();
    }

    @GetMapping("/inbox/count")
    public R<Map<String, Object>> count(@RequestAttribute("_authId") Long agentId) {
        return R.ok(Map.of("total_unread", inboxService.countUnread(agentId)));
    }
}
```

> **集成点**: AuthInterceptor 已验证 Agent API Key，将 agentId 写入 `request.setAttribute("_authId", agent.getId())`。Controller 通过 `@RequestAttribute` 获取。

---

## 七、任务执行引擎与 AI 模型路由

### 7.1 设计概述

与 v1.0 方案基本一致，但明确与 `PromptTemplateService` 的集成：

```java
public interface AgentExecutor {
    AgentResult execute(AgentTask task);
}

@Component
@RequiredArgsConstructor
public class ClaudeExecutor implements AgentExecutor {

    private final PromptTemplateService promptService;  // 注入 prompt 服务

    @Override
    public AgentResult execute(AgentTask task) {
        // 1. 拼装系统提示词
        String systemPrompt = promptService.composePromptByRole(
            task.getAgent().getRole().name());

        // 2. 拼装对话历史
        List<Message> history = conversationService.getMessages(task.getSubTaskId())
            .stream().map(ClaudeExecutor::toApiMessage).toList();

        // 3. 调用 Anthropic API
        // ...
    }
}

@Component
@RequiredArgsConstructor
public class AgentRouter {
    private final Map<String, AgentExecutor> executorMap;

    @Autowired
    public AgentRouter(List<AgentExecutor> executors) {
        this.executorMap = executors.stream()
            .collect(Collectors.toMap(
                e -> e.getClass().getSimpleName().replace("Executor", "").toLowerCase(),
                e -> e));
    }

    public AgentExecutor route(Agent agent) {
        String modelType = agent.getModelType();
        if (modelType == null) modelType = "claude-sonnet";

        // 根据 modelType 查找
        if (modelType.startsWith("claude")) return executorMap.get("claude");
        if (modelType.startsWith("codex") || modelType.startsWith("gpt")) return executorMap.get("codex");
        throw new BizException("不支持的模型类型: " + modelType);
    }
}
```

### 7.2 Consumer 接入 AgentExecutor

```java
@Component
@RequiredArgsConstructor
public class ExecutorEventConsumer {

    private final AgentRouter agentRouter;
    private final AgentExecutionRecordMapper executionRecordMapper;
    private final SubTaskService subTaskService;
    private final PromptTemplateService promptService;   // 新增注入
    private final ConversationService conversationService; // 新增注入

    // ... 已在 V1__init_schema.sql 配套的 AgentExecutionRecord 逻辑保持不变

    // 核心修改：AgentExecutor.execute() 不再直接从 event 构建 task，
    // 而是从 DB 拉取完整上下文（prompt + 对话历史 + 规则 + 附件）
}
```

---

## 八、多轮对话与附件衔接

### 8.1 对话上下文 API

```java
@GetMapping("/api/sub-tasks/{id}")
public R<SubTaskDetailVO> getDetail(
        @PathVariable Long id,
        @RequestParam(defaultValue = "false") boolean includeContext,
        @RequestAttribute("_authId") Long agentId) {

    SubTask subTask = subTaskService.getById(id);
    SubTaskDetailVO vo = SubTaskDetailVO.from(subTask);

    if (includeContext) {
        vo.setTask(taskService.getById(subTask.getTaskId()));
        vo.setMessages(conversationService.getMessages(id));        // 多轮对话
        vo.setAttachments(attachmentService.listBySubTask(id));     // 附件元数据
        vo.setReviews(reviewService.getBySubTaskId(id));            // 审查记录
        vo.setRecentLogs(activityLogService.listRecentBySubTask(id, 20));
        vo.setMergedRules(ruleService.getMergedRules(subTask.getTaskId(), id));
    }

    return R.ok(vo);
}
```

### 8.2 附件下载 API（v1.1 新增）

```java
// AttachmentController 增加
@GetMapping("/{id}/download")
public ResponseEntity<InputStreamResource> download(@PathVariable Long id) {
    Attachment attachment = attachmentService.getById(id);
    if (attachment == null) throw new BizException(404, "附件不存在");

    // 生成 MinIO 预签名 URL（1小时有效期）
    String presignedUrl = minioService.generatePresignedUrl(
        attachment.getBucketName(), attachment.getObjectKey(), 3600);

    // 302 重定向到预签名 URL
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(presignedUrl))
        .build();
}

// MinIO 预签名 URL 生成
public String generatePresignedUrl(String bucket, String key, int expirySeconds) {
    return minioClient.getPresignedObjectUrl(
        GetPresignedObjectUrlArgs.builder()
            .bucket(bucket).object(key)
            .method(Method.GET).expiry(expirySeconds)
            .build());
}
```

### 8.3 附件上传 → 关联子任务

Agent 执行完任务后上传结果文件：

```bash
# Agent 侧（已在 SKILL.md 中说明）
curl -X POST http://platform:6565/api/attachments/upload \
  -H "Authorization: Bearer ak_xxx" \
  -F "file=@result.zip" \
  -F "subTaskId=456"
```

```java
// AttachmentController.upload() 已有，无需改动
// 上传后 attachment.sub_task_id = subTaskId
// Agent 提交时带上 attachment_ids
POST /api/sub-tasks/456/submit
{ "deliverable": "已完成", "attachmentIds": [101, 102] }
```

### 8.4 对话 Token 压缩

当对话历史 token 超过阈值时（如 Claude 200K），自动摘要压缩：

```java
@Component
public class ContextManager {

    private static final int COMPRESSION_THRESHOLD = 150_000;  // 当 token > 15万时压缩
    private static final int KEEP_RECENT_ROUNDS = 10;          // 保留最近 10 轮完整对话

    public List<ConversationMessage> optimizeContext(List<ConversationMessage> messages) {
        int totalTokens = messages.stream().mapToInt(ConversationMessage::getTokenCount).sum();

        if (totalTokens <= COMPRESSION_THRESHOLD) {
            return messages;
        }

        // 拆分：最近 10 轮完整保留，更早的做摘要
        int splitIdx = findSplitIndex(messages, KEEP_RECENT_ROUNDS);
        List<ConversationMessage> recent = messages.subList(splitIdx, messages.size());
        List<ConversationMessage> earlier = messages.subList(0, splitIdx);

        // ⚠️ 实施注意 (v1.1.1): 用 Haiku 做摘要需要构造特殊的 AgentTask（无 subTaskId），
        // 走 AgentRouter 调用轻量模型。这是一个 P2 优化项。
        // Phase 1 简易方案：超过阈值后直接截断，保留最近 KEEP_RECENT_ROUNDS 轮完整对话，
        // 丢弃早期消息（风险：丢失早期上下文）。Phase 2 再引入摘要。
        String summary = summarizeWithLightModel(earlier);

        // 插入一条摘要消息
        ConversationMessage summaryMsg = new ConversationMessage();
        summaryMsg.setRole("system");
        summaryMsg.setContent("【上文摘要】" + summary);
        summaryMsg.setSeq(recent.get(0).getSeq() - 1);

        List<ConversationMessage> result = new ArrayList<>();
        result.add(summaryMsg);
        result.addAll(recent);
        return result;
    }
}
```

---

## 九、任务控制：暂停/恢复/取消

### 9.1 前提：PAUSED 状态加入状态机

已在 2.3 节详述。`SubTaskStatus` 增加 `PAUSED`，`SubTaskStateMachine.TRANSITIONS` 增加对应转换。

### 9.2 实现

```java
// SubTaskService 新增方法
@Transactional
public void pause(Long subTaskId, String reason, String operatorName) {
    SubTask subTask = getById(subTaskId);
    SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.PAUSED);

    subTask.setStatus(SubTaskStatus.PAUSED);
    subTask.setUpdateBy(operatorName);
    updateById(subTask);

    // Outbox 事件 → MQ → NotificationConsumer → agent_inbox
    agentOutboxService.createEvent(subTask, SubTaskStatus.PAUSED);

    // 立即投递收件箱（高优先级）
    agentInboxService.send(subTask.getAssignedAgent(),
        "sub_task.paused",
        "任务已暂停: " + subTask.getTitle(),
        "原因: " + reason + "\n请保存当前进度，等待恢复通知",
        "sub_task", subTaskId, "HIGH");
}

@Transactional
public void resume(Long subTaskId, String operatorName) {
    SubTask subTask = getById(subTaskId);
    SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.IN_PROGRESS);

    subTask.setStatus(SubTaskStatus.IN_PROGRESS);
    subTask.setUpdateBy(operatorName);
    updateById(subTask);

    agentOutboxService.createEvent(subTask, SubTaskStatus.IN_PROGRESS);

    agentInboxService.send(subTask.getAssignedAgent(),
        "sub_task.resumed",
        "任务已恢复: " + subTask.getTitle(),
        "请继续执行。之前的上下文可通过 GET /api/sub-tasks/" + subTaskId + "?include=context 获取",
        "sub_task", subTaskId, "HIGH");
}
```

### 9.3 API 端点

```java
@PostMapping("/{id}/pause")
public R<Void> pause(@PathVariable Long id, @RequestBody PauseRequest req) {
    subTaskService.pause(id, req.getReason(), getCurrentUser());
    return R.ok();
}

@PostMapping("/{id}/resume")
public R<Void> resume(@PathVariable Long id) {
    subTaskService.resume(id, getCurrentUser());
    return R.ok();
}
```

---

## 十、通知与健康检查

### 10.1 MQ 通知队列

```java
// RabbitMQConfig 新增
public static final String NOTIFICATION_QUEUE = "helloai.notification.queue";

@Bean
public Queue notificationQueue() {
    return QueueBuilder.durable(NOTIFICATION_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", DLX_QUEUE)
        .build();
}

@Bean
public Binding notificationBinding() {
    return BindingBuilder.bind(notificationQueue())
        .to(agentExchange()).with("sub_task.*");  // 监听所有子任务事件
}
```

### 10.2 MQ 堆积监控

```yaml
# application.yml 增加 Prometheus 指标暴露
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: helloai
```

Prometheus 告警规则：

```yaml
groups:
  - name: helloai_alerts
    rules:
      - alert: MQQueueBacklog
        expr: rabbitmq_queue_messages{queue="helloai.notification.queue"} > 1000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "通知队列消息堆积超过 1000"
```

### 10.3 Agent 健康检查

```java
// AgentHealthCheckTask（已有骨架，补充逻辑）
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentHealthCheckTask {

    private final AgentService agentService;
    private final AgentInboxService inboxService;
    private final StringRedisTemplate redis;

    private static final String LOCK_KEY = "scheduler:lock:AgentHealth";

    @Scheduled(fixedRate = 60000)
    public void check() {
        if (!tryLock()) return;
        try {
            OffsetDateTime timeout = OffsetDateTime.now().minusMinutes(30);

            // 1. 查找超过 30 分钟未活动的 Agent
            List<Agent> inactiveAgents = agentService.lambdaQuery()
                .eq(Agent::getStatus, AgentStatus.ACTIVE)
                .lt(Agent::getUpdateTime, timeout)
                .list();

            for (Agent agent : inactiveAgents) {
                log.warn("Agent 可能离线: id={}, name={}, lastActive={}",
                    agent.getId(), agent.getName(), agent.getUpdateTime());

                // 投递一条低优先级的健康检查消息
                inboxService.send(agent.getId(),
                    "system.health_check",
                    "健康检查",
                    "你已超过 30 分钟未活动，如果仍在运行请忽略此消息",
                    null, null, "LOW");
            }

            // 2. 查找超过 2 小时未活动的 Agent，自动回收其任务
            OffsetDateTime expired = OffsetDateTime.now().minusHours(2);
            List<Agent> expiredAgents = agentService.lambdaQuery()
                .eq(Agent::getStatus, AgentStatus.ACTIVE)
                .lt(Agent::getUpdateTime, expired)
                .list();

            for (Agent agent : expiredAgents) {
                // 将该 Agent 的 in_progress 子任务标记为 blocked
                subTaskService.releaseExpiredTasks(agent.getId(), expired);
                // 投递紧急通知给 Planner
                notifyPlanners("Agent " + agent.getName() + " 超 2 小时未活动，任务已回收");
            }
        } finally {
            unlock();
        }
    }
}
```

---

## 十一、安全设计

### 11.1 认证体系（基于现有 AuthInterceptor）

```
请求认证流程:
  X-Admin-Token → AuthService.validateAdminToken() → 管理员
  Authorization: Bearer xxx → AuthService.validateAgentKey() → Agent

端点权限:
  /api/admin/**        → 需要 Admin Token
  /api/agent/**        → 需要 Agent API Key
  /api/agents/register → 无需认证（仅验证 X-Registration-Token）
  /api/agents/me/skill → 需要 Agent API Key
  /api/tools/cli       → 当前实现默认放行（以 WebMvcConfig 为准，若后续收紧需同步更新文档与代码规范）
  /api/health          → 无需认证
  /api/setup/**        → 无需认证
  /api/feed/**         → 无需认证（公开）
```

### 11.2 API Key 安全

```java
// API Key 生成
public static String generateApiKey() {
    return "ak_" + RandomStringUtils.randomAlphanumeric(32).toLowerCase();
    // 32 位随机字符 + "ak_" 前缀 = 35 字符，搜索空间 ~62^32
}

// API Key 重置
// POST /api/admin/agents/{id}/reset-key
// 重置后旧 Key 立即失效，新 Key 返回一次

// 可选增强（P2）: API Key 过期
// agent 表增加 api_key_created_at 字段
// 定期任务检查超过 90 天的 Key，投递提醒消息到 inbox
```

### 11.3 注册令牌安全

```java
// sys_config 表存储
// config_key: "agent.registration_token"
// config_value: <随机生成的 32 位 hex 字符串>

// 初始化时自动生成:
// POST /api/setup/initialize → 若不提供 registration_token，自动生成并返回

// 管理员可重置:
// PUT /api/admin/config { "registration_token": "new-token" }
// 重置后旧 Token 立即失效
```

---

## 十二、前端管理页面规划

| 页面 | 路径 | 说明 | 优先级 |
|------|------|------|:---:|
| **Setup 向导** | `/setup` | 首次启动：设置管理员密码、项目名、工作目录、注册令牌 | **P0** |
| **Admin 登录** | `/admin/login` | 管理员登录 | **P0** |
| **Dashboard** | `/admin` | 概览：任务数、Agent 数、最近活动 | **P0** |
| **Agent 管理** | `/admin/agents` | 列表、创建、编辑、禁用、重置 API Key、查看收件箱 | **P0** |
| **Prompt 管理** | `/admin/prompts` | 角色模板列表、Agent 配置列表、Skill 列表、编辑/预览/compose | **P0** |
| **任务管理** | `/admin/tasks` | 任务 CRUD、子任务查看、状态变更、暂停/恢复、分配 Agent | **P0** |
| **规则管理** | `/admin/rules` | 全局/任务/子任务级别规则的 CRUD | **P1** |
| **审查记录** | `/admin/reviews` | 审查历史、评分分布 | **P1** |
| **积分排行榜** | `/admin/scores` | Agent 积分排行 | **P1** |
| **活动日志** | `/admin/logs` | 平台级日志查看 | **P2** |
| **系统配置** | `/admin/config` | 运行时配置修改 | **P2** |
| **附件管理** | `/admin/attachments` | 文件列表、预览、下载 | **P2** |

### 前端 SSE 实时刷新

管理后台 Dashboard 接入 SSE：

```java
@GetMapping("/api/admin/events")
public SseEmitter adminEvents(@RequestAttribute("_authType") String authType) {
    // 仅 admin 可访问（AuthInterceptor 已校验）
    SseEmitter emitter = new SseEmitter(600_000L);
    adminSseRegistry.register(emitter);
    // ...
}

// 子任务状态变更时推送:
adminSseRegistry.broadcast(SseEmitter.event()
    .name("sub_task_updated")
    .data(Map.of("subTaskId", id, "status", newStatus)));
```

---

## 十三、测试策略

### 13.1 测试分层

| 层级 | 工具 | 覆盖目标 |
|------|------|---------|
| **单元测试** | JUnit 5 + Mockito | Service 层、状态机、评分计算器 |
| **DAO 测试** | MyBatis-Plus Test + H2 | Mapper 层 SQL 正确性 |
| **集成测试** | Testcontainers (PG + Redis + RabbitMQ) | MQ 消费者、Outbox 补偿、收件箱投递 |
| **API 测试** | Spring MockMvc | Controller 层、认证拦截、参数校验 |
| **端到端测试** | Postman Collection | 完整 Agent 注册→执行→审查→评分链路 |

### 13.2 关键测试场景

```
☐ 状态机: 所有合法转换 + 非法转换拒绝
☐ Outbox: 事件创建 → MQ 发送 → 消费成功/失败
☐ Inbox: 同一 (event_id, agent_id) 不会重复投递
☐ 暂停/恢复: PAUSED 状态后，恢复能拿到完整上下文
☐ 评分链路: Review.createReview → Reward.addReward → ImplicitScoreCalculator.calculate
☐ ACK 丢失: kill -9 模拟 → ExecutionCompensationTask 30s 内补偿
☐ Token 压缩: 超过阈值后自动摘要，不影响最近 10 轮
☐ 认证: 错误 Key/Token 返回 401/403
☐ 附件: 上传 → MinIO 预签名 URL → 下载
```

### 13.3 Testcontainers 配置

```java
@SpringBootTest
@Testcontainers
public abstract class BaseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("helloai_test")
        .withUsername("test")
        .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
        .withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbitmq = new GenericContainer<>("rabbitmq:3.12-management")
        .withExposedPorts(5672);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbitmq.getMappedPort(5672));
    }
}
```

---

## 十四、部署方案

### 14.1 Docker Compose（生产级）

```yaml
version: '3.8'
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: helloai
      POSTGRES_USER: helloai
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pg_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"

  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes
    volumes:
      - redis_data:/data
    ports:
      - "6379:6379"

  rabbitmq:
    image: rabbitmq:3.12-management-alpine
    environment:
      RABBITMQ_DEFAULT_USER: helloai
      RABBITMQ_DEFAULT_PASS: ${MQ_PASSWORD}
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
    ports:
      - "5672:5672"
      - "15672:15672"

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_PASSWORD}
    volumes:
      - minio_data:/data
    ports:
      - "9000:9000"
      - "9001:9001"

  helloai:
    build: .
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/helloai
      SPRING_DATASOURCE_USERNAME: helloai
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_RABBITMQ_HOST: rabbitmq
      MINIO_ENDPOINT: http://minio:9000
    ports:
      - "6565:6565"
    depends_on:
      - postgres
      - redis
      - rabbitmq
      - minio
    restart: unless-stopped

  # 可选: 前端 Nginx
  nginx:
    image: nginx:alpine
    volumes:
      - ./helloai-ui/dist:/usr/share/nginx/html
      - ./nginx.conf:/etc/nginx/nginx.conf
    ports:
      - "80:80"
    depends_on:
      - helloai

volumes:
  pg_data:
  redis_data:
  rabbitmq_data:
  minio_data:
```

### 14.2 JVM 参数

```bash
java -Xms2g -Xmx2g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -Dspring.profiles.active=prod \
     -jar helloai-start-1.0.0-SNAPSHOT.jar
```

---

## 十五、数据迁移方案

### 15.1 从 openMoss 迁移

```
数据源: SQLite (tasks.db) + 文件系统 (prompts/, skills/, rules/)
目标:   PostgreSQL 16 (helloai)

迁移顺序（按外键依赖）:
  1. sys_config         ← config.yaml (registration_token, allow_registration)
  2. agent              ← SQLite agent 表
  3. task               ← SQLite task 表
  4. module             ← SQLite module 表
  5. sub_task           ← SQLite sub_task 表
  6. rule               ← SQLite rule 表 + rules/global-rule-example.md
  7. review_record      ← SQLite review_record 表
  8. reward_log         ← SQLite reward_log 表
  9. activity_log       ← SQLite activity_log 表
  10. prompt_template   ← prompts/ 文件系统中的 .md 文件

文件 → DB 映射:
  prompts/templates/*.md          → prompt_template (category=ROLE_TEMPLATE)
  prompts/agents/*.md             → prompt_template (category=AGENT_SPECIALIZATION)
  skills/task-*-skill/SKILL.md   → prompt_template (category=SKILL)
  rules/global-rule-example.md   → rule 表
```

### 15.2 migrate.py 脚本框架

```python
#!/usr/bin/env python3
"""openMoss → HelloAI 数据迁移脚本"""
import sqlite3
import psycopg2
import yaml
import frontmatter
from pathlib import Path

def migrate_agents(sqlite_conn, pg_conn):
    """迁移 Agent 数据，API Key 重新生成"""
    rows = sqlite_conn.execute("SELECT id, name, role, status, total_score FROM agent").fetchall()
    pg_cursor = pg_conn.cursor()
    for row in rows:
        pg_cursor.execute(
            "INSERT INTO agent (name, role, api_key, status, score) VALUES (%s,%s,%s,%s,%s)",
            (row[1], row[2], generate_api_key(), row[3], row[4])
        )
    pg_conn.commit()

def migrate_prompts(prompts_dir: Path, pg_conn):
    """迁移文件系统 Prompt → prompt_template 表"""
    # templates/
    for md_file in (prompts_dir / "templates").glob("*.md"):
        role = extract_role(md_file.stem)
        content = md_file.read_text(encoding="utf-8")
        content = adapt_wakeup_flow(content)  # cron唤醒 → 收件箱
        pg_cursor.execute(
            "INSERT INTO prompt_template (role, category, name, content, is_default) ...",
            (role, "ROLE_TEMPLATE", ...)
        )
    # agents/ → AGENT_SPECIALIZATION
    # skills/ → SKILL
    # ...

def adapt_wakeup_flow(content: str) -> str:
    """将 openMoss 的 cron 唤醒流程改为收件箱模式"""
    return content.replace(
        "你通过 OpenClaw cron 定时唤醒（isolated 模式）",
        "你通过事件通知或定时查收 GET /api/agent/inbox 来获取新任务"
    )
```

---

## 十六、API 端点补齐清单

### 16.1 新增端点

| 方法 | 路径 | 说明 | 优先级 | 对接现有组件 |
|------|------|------|:---:|---|
| `POST` | `/api/agents/register` | Agent 自注册（完善） | **P0** | AgentConfigProperties, AgentService |
| `GET` | `/api/agents/me/skill` | SKILL.md（API Key 填入） | **P0** | PromptTemplateService, AuthInterceptor |
| `GET` | `/api/agent/inbox` | 查收件箱 | **P0** | AgentInboxService |
| `PUT` | `/api/agent/inbox/{id}/read` | 标记已读 | **P0** | AgentInboxService |
| `GET` | `/api/agent/inbox/count` | 未读数量 | **P0** | AgentInboxService |
| `GET` | `/api/sub-tasks/{id}` | 支持 `?include=context` | **P0** | ConversationService, AttachmentService |
| `GET` | `/api/attachments/{id}/download` | 附件下载（预签名 URL） | **P0** | MinIO |
| `GET` | `/api/admin/prompts/templates` | 角色模板列表 | **P0** | PromptTemplateService |
| `GET` | `/api/admin/prompts/agents` | Agent 配置列表 | **P0** | PromptTemplateService |
| `POST` | `/api/admin/prompts/agents` | 创建 Agent 配置 | **P0** | PromptTemplateService |
| `PUT` | `/api/admin/prompts/templates/{role}` | 更新角色模板 | **P0** | PromptTemplateService |
| `GET` | `/api/admin/prompts/compose/{slug}` | 组合 Prompt | **P0** | PromptTemplateService |
| `GET` | `/api/admin/prompts/onboarding/{role}` | 接入引导 | **P0** | PromptTemplateService |
| `POST` | `/api/admin/agents/{id}/reset-key` | 重置 API Key | **P0** | AgentService |
| `POST` | `/api/sub-tasks/{id}/pause` | 暂停子任务 | **P1** | SubTaskService, SubTaskStateMachine |
| `POST` | `/api/sub-tasks/{id}/resume` | 恢复子任务 | **P1** | SubTaskService |
| `GET` | `/api/config/notification` | Agent 获取通知配置 | **P1** | sys_config |
| `GET` | `/api/scores/leaderboard` | 积分排行榜 | **P1** | RewardService |
| `GET` | `/api/agent/events` | SSE 实时推送 | **P2** | SseRegistry |

### 16.2 需修改的现有端点

| 端点 | 变更内容 |
|------|---------|
| `GET /api/tools/cli` | BASE_URL 运行时替换（注入 AgentConfigProperties） |
| `POST /api/reviews` | `ReviewService.createReview()` 中注入 `ImplicitScoreCalculator` |
| `POST /api/sub-tasks/{id}/submit` | 提交时写入 `conversation_message` |

---

## 十七、实施路线图

### Phase 1: Agent 生态基础（P0，5天）

```
Day 1-2: 数据库 + 实体
├── V5__enhance_prompt_template.sql（改表 + 13条种子数据）
├── V6__agent_inbox.sql（新表）
├── V7__conversation_message.sql（新表）
├── V8__agent_model_fields.sql（agent 加字段）
├── V9__seed_global_rule.sql（rule 种子数据）
├── V10__add_paused_status.sql（状态约束）
├── Entity: AgentInbox, ConversationMessage
└── SubTaskStatus 枚举增加 PAUSED

Day 2-3: Service 层
├── PromptTemplateService（composePrompt + getSkillForAgent + generateOnboarding）
├── AgentInboxService（send + getUnread + markRead + countUnread）
├── ConversationService（addMessage + getMessages）
├── SubTaskStateMachine 增加 PAUSED 转换
├── ReviewService 集成 ImplicitScoreCalculator
└── SubTaskService 增加 pause/resume + complete 集成隐式评分

Day 3-4: API 端点
├── POST /api/agents/register（完善）
├── GET /api/agents/me/skill（新增）
├── GET /api/agent/inbox + PUT read + GET count（新增）
├── GET /api/attachments/{id}/download（新增）
├── GET /api/admin/prompts/**（新增）
├── POST /api/admin/agents/{id}/reset-key（新增）
└── GET /api/tools/cli（增强 BASE_URL 替换）

Day 4-5: MQ 集成
├── RabbitMQConfig 增加 notificationQueue
├── NotificationConsumer 实现
├── AgentHealthCheckTask 补充逻辑
└── 集成测试（Inbox 投递 + MQ 消费）
```

### Phase 2: 任务执行引擎（P1，4天）

```
Day 1-2: AI 模型路由
├── AgentExecutor 接口 + ClaudeExecutor + CodexExecutor
├── AgentRouter（读取 agent.modelType）
└── ExecutorEventConsumer 集成 AgentExecutor

Day 2-4: 任务控制 + 附件
├── SubTaskService.pause() / resume()
├── POST /api/sub-tasks/{id}/pause, /resume
├── ContextManager（Token 压缩）
└── 附件上传 → 关联子任务 → 提交流程
```

### Phase 3: 完善（P2-P3，按需）

```
├── SSE 实时推送
├── 前端管理页面（Setup 向导 + Prompt 管理 + Agent 管理 + Dashboard）
├── 通知系统（多渠道）
├── 任务类型 (ONCE/RECURRING)
├── 排行榜
├── CLI 工具命令补齐
├── Docker 生产部署
└── Prometheus 监控 + Grafana 面板
```

---

## 十八、附录

### 18.1 与 openMoss 的核心差异

| 维度 | openMoss | HelloAI |
|------|---------|---------|
| 后端 | Python FastAPI | Java Spring Boot 3.2 |
| 数据库 | SQLite | PostgreSQL 16 |
| 消息机制 | HTTP polling (cron) | RabbitMQ 事件总线 + Agent 收件箱 |
| Prompt 存储 | 文件系统 | PostgreSQL (prompt_template 表) |
| Agent 发现任务 | 定时轮询 (30min) | 查收件箱 (10-60s) |
| 任务下发 | Agent pull | 平台 push-like (inbox) |
| 任务控制 | ❌ 不支持 | ✅ 暂停/恢复/取消 |
| 离线支持 | ❌ 错过 | ✅ 消息持久化 |
| AI 模型调用 | Agent 自己调 | 平台统一路由 (AgentRouter) |
| 多轮对话 | 文件系统 | conversation_message 表 |

### 18.2 关键设计决策记录

| # | 决策 | 理由 |
|---|------|------|
| 1 | Prompt 存 DB | Admin UI 编辑 + 版本管理 + 集群部署 |
| 2 | task-cli.py 保留文件 | 可执行 Python 代码 |
| 3 | MQ 只传 eventId | 业务数据走 HTTP+DB，减少 MQ 消息体 |
| 4 | 收件箱模式 | 离线不丢消息，天然支持暂停/恢复 |
| 5 | Agent 类型无关 | 只认 API Key + HTTP |
| 6 | 删除文件钩子通知 | 与 Agent 类型无关原则冲突 |
| 7 | (event_id, agent_id) 联合唯一 | 同一事件可通知多个 Agent |
| 8 | conversation_archive 保留只读 | 兼容历史数据 |
| 9 | PAUSED 独立于 BLOCKED | 语义不同：暂停=人的操作，阻塞=AI 问题 |
| 10 | 策略 1 轮询为主 | Agent 执行任务需几分钟，10-30s 延迟可接受 |

### 18.3 v1.0 → v1.1 变更摘要

| 分类 | 变更 |
|------|------|
| **删除** | 文件系统钩子通知策略 |
| **修正** | `agent_inbox.message_id UNIQUE` → `(event_id, agent_id)` 联合唯一 |
| **澄清** | `conversation_archive` 为只读归档，新数据写入 `conversation_message` |
| **新增** | ReviewService 集成 `ImplicitScoreCalculator`，完成时触发隐式评分 |
| **新增** | `GET /api/attachments/{id}/download` MinIO 预签名 URL 端点 |
| **新增** | "与现有代码骨架的集成映射" 章节（第 2 章） |
| **修正** | 认证描述从 "JWT + Spring Security" 改为 "AuthInterceptor 双通道" |
| **新增** | 前端管理页面清单（第 12 章） |
| **新增** | 数据迁移方案（第 15 章） |
| **新增** | `PAUSED` 状态加入状态机 + `SubTaskStatus` 枚举 |
| **新增** | API Key 重置端点 `POST /api/admin/agents/{id}/reset-key` |
| **修正** | 注册令牌存储从 application.yml 改为 sys_config 表 |
| **新增** | AgentHealthCheckTask 具体逻辑 |
| **新增** | MQ 堆积 Prometheus 监控指标 |
| **新增** | ContextManager Token 压缩 |
| **新增** | 附件上传→关联→提交的完整流程 |
| **新增** | 测试策略（第 13 章） |
| **新增** | Docker Compose 生产部署（第 14 章） |
| **新增** | WebUI SSE 实时刷新 |
| **移除** | V10 冗余字段迁移（字段已存在于现有代码） |

### 18.4 v1.1 → v1.1.1 变更（3 个实施注意事项）

| # | 位置 | 问题 | 处理方式 |
|---|------|------|---------|
| 1 | §2.3 状态机 | `PAUSED` 枚举增加后，现有 `switch(status)` 和 `if-else` 需同步更新 | 文档标注受影响的代码位置（`AgentOutboxService.resolveRoutingKey()`, 前端状态标签），IDE 编译时自动标红未覆盖分支 |
| 2 | §6.2 NotificationConsumer | `sub_task.submitted` 通知所有 Reviewer，存在并发争抢审查权 | 文档标注风险，建议增加 `POST /api/sub-tasks/{id}/claim-review` 端点 + Redis SETNX 分布式锁（lock key: `review:claim:{subTaskId}`） |
| 3 | §8.4 ContextManager | Haiku 摘要需要 AgentRouter 调度，实施复杂 | 标注为 P2 优化。Phase 1 采用简易方案：超阈值直接截断保留最近 10 轮，丢弃早期消息 |

---

*文档基于 2026-07-03 多轮讨论 + 20 条反馈修正 + 3 个实施注意事项。与现有 17 个 Java 组件的集成点已明确标注。实施过程中如有偏差，更新此文档。*
