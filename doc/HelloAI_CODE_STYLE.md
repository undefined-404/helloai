# HelloAI 代码开发规范

> 适用项目：HelloAI（AI Agent 协作调度平台）  
> 生效范围：后端单体服务 + 前端管理后台（Vue 3 / Element Plus）  
> 版本：V1.4  
> 最后更新：2026-07-18  
> 本版重点：对齐 core 业务域分包重构后的代码事实——修正 3.2 启动类 @MapperScan 示例、3.x 资源文件位置、4.1 包命名示例、8.4 Flyway 规范与多版本迁移现实的冲突；补写 6.3 Controller 职责边界（含分层红线与待收口清单）；3.x 业务域分包规则补全子包清单与 outbox 归属决策；8.5 实施要点追加"变更残留检查范围"
> 致敬：Hello World! —— 每一位程序员的第一行代码
---

## 摘要

### 文档定位

本文件是 HelloAI 的代码事实规范，目标是减少以下歧义：

- 当前仓库真实技术栈与历史文档不一致
- 示例代码与当前项目推荐写法不一致
- 开发时不知道“该参考技术方案、路线图，还是代码规范”

### 使用要求

- 每次新增或修改代码前，先对照本文件确认是否存在明确规范。
- 若当前代码实现与本文件不一致，应优先判断是旧代码待收口，还是规范本身过时；不要绕过规范直接新增第三种写法。
- 若修改引入新的公共约定，应同步更新本文件，而不是只留在 PR、聊天记录或临时说明中。
- 若与项目基线文档、实现差距表存在冲突，以代码事实和项目基线为准，并尽快回写本文件。
- 若修改涉及调度、执行链、异步回写、MQ 解耦，开发前必须先阅读 `doc/design/HelloAI_调度解耦重构分析.md`，并按任务计划节点回看 `E:\workspace\AgentTeams-main` 相关源码，确认没有偏离当前收敛方向。

### 维护边界

- 本文件负责“怎么写代码”
- `HelloAI_项目基线文档.md` 负责“当前项目是什么”
- `HelloAI_实现差距表.md` 负责“计划与现实差在哪里”
- `log/HelloAI_迭代执行记录.md` 负责“这一轮到底做了什么”

---

## 目录

1. [总体原则](#1-总体原则)
   - [1.x 代码注释规范](#1x-代码注释规范)
2. [技术栈与版本约束](#2-技术栈与版本约束)
3. [项目结构与模块职责](#3-项目结构与模块职责)
   - [3.x 配置属性类规范](#3x-配置属性类规范)
   - [3.x 资源文件存放规范](#3x-资源文件存放规范)
4. [命名规范](#4-命名规范)
   - [4.x 接口使用原则](#4x-接口使用原则)
   - [4.x 常量与枚举命名](#4x-常量与枚举命名)
5. [实体类规范](#5-实体类规范)
   - [5.5 日期时间处理规范](#55-日期时间处理规范)
6. [Controller 规范](#6-controller-规范)
7. [Service 规范](#7-service-规范)
   - [7.6 空值与 Optional 处理规范](#76-空值与-optional-处理规范)
8. [数据库设计规范](#8-数据库设计规范)
9. [Outbox 事务性消息规范](#9-outbox-事务性消息规范)
10. [消息队列编码规范](#10-消息队列编码规范)
11. [分布式锁编码规范](#11-分布式锁编码规范)
12. [异常处理规范](#12-异常处理规范)
13. [日志与链路追踪规范](#13-日志与链路追踪规范)
14. [定时任务编码规范](#14-定时任务编码规范)
15. [Agent 驱动层编码规范](#15-agent-驱动层编码规范)
16. [代码模板（附录）](#16-代码模板附录)
17. [开发高频校验清单（附录）](#17-开发高频校验清单附录)
18. [Vue 页面规范](#18-vue-页面规范)
19. [新增代码前校验清单](#19-新增代码前校验清单)
20. [测试规范](#20-测试规范)

---

## 1. 总体原则

| 条目 | 规范 |
|------|------|
| 文件编码 | **所有 `.java` 文件必须使用 UTF-8 without BOM**，禁止 BOM 头 (`EF BB BF`) |
| 统一返回 | 使用 `R` 类：`R.ok(data)` / `R.fail(msg)` / `R.fail(code, msg)` |
| 实体继承 | 业务实体继承 `BaseEntity` + `@Data`；关系表只需 `@Data` + `@TableName`（详见 5.2） |
| 异常处理 | 业务异常使用 `BizException`，由 `GlobalExceptionHandler` 统一捕获 |
| ID 策略 | `IdType.ASSIGN_ID`（雪花算法 Long），**不使用 String/UUID** |
| 依赖注入 | **构造器注入**（不用 `@Autowired` 字段注入） |
| 事务注解 | `@Transactional(rollbackFor = Exception.class)` |
| 逻辑删除 | `@TableLogic` 标注 `deleted` 字段，0=未删除 / 1=已删除 |
| 乐观锁 | 使用 `@Version` 注解，禁止手动写 `version = version + 1` SQL |
| 状态常量 | **禁止硬编码**，统一使用枚举类（`SubTaskStatus`、`AgentRole` 等） |
| 开发入口 | **每次改代码前先读本规范，再对照 `HelloAI_项目基线文档.md`、`HelloAI_实现差距表.md` 与 `design/HelloAI_调度解耦重构分析.md` 判断边界** |

> **强制要求**：如果本文件已有明确规范，开发实现必须优先遵守；若确需例外，必须同时更新文档说明例外条件。

---

### 1.x 代码注释规范

| 场景 | 规范 |
|------|------|
| 类注释 | 所有 `@Service`、`@Component`、`@RestController` 类**必须**写 Javadoc 类注释，说明职责和主要依赖 |
| 公共方法 | `public` 方法**建议**写 Javadoc，说明参数、返回值、异常；私有方法用单行注释说明"为什么这样做" |
| 复杂逻辑 | 超过 10 行的业务逻辑块**必须**加注释，说明意图而非描述代码 |
| TODO | `// TODO(author): 描述` — 必须带作者和日期 |
| FIXME | `// FIXME: 描述 (关联Issue)` — 必须关联 Issue 或限期修复 |

```java
/**
 * Agent 收件箱服务。
 * 负责将 MQ 事件投递到各 Agent 的持久化收件箱，支持离线积攒、上线补处理。
 *
 * 核心设计：同一 (eventId, agentId) 最多投递一次（联合唯一约束）。
 */
@Service
public class AgentInboxService {

    /**
     * 向指定 Agent 投递收件箱消息。幂等。
     *
     * @param agentId 目标 Agent ID
     * @param event   MQ 领域事件，含 eventId / type / entityId
     * @throws BizException 当 Agent 不存在或已禁用时
     */
    public void send(Long agentId, DomainEvent event) { ... }
}
```

> **原则**: 注释解释"为什么"（Why），代码解释"是什么"（What）。不写 `// i++ 自增` 这类无意义注释。

---

## 2. 技术栈与版本约束

| 组件 | 版本 | 备注 |
|------|------|------|
| Java | 17 | 编译目标 17，LTS 版本 |
| Spring Boot | 3.4.10 | 当前主线稳定版本 |
| Spring AI | 1.1.8 | 当前父 POM 实际版本，MCP 主线依赖 |
| MyBatis-Plus | 3.5.9 | 使用 `mybatis-plus-spring-boot3-starter` |
| MyBatis-Spring | 3.0.4 | Spring Boot 3.x 配套版本 |
| PostgreSQL | 16 | 主数据库，支持 JSONB、pgvector 扩展 |
| PostgreSQL Driver | 42.7.3 | JDBC 驱动 |
| Redis | 7.x | 缓存 + 分布式锁 + 去重 + 上下文存储 |
| RabbitMQ | 3.12+ | 消息中间件，Topic Exchange + DLX |
| MinIO | Docker Compose 当前为 `latest` | 开发环境以仓库当前配置为准 |
| SpringDoc | 2.8.0 | OpenAPI 3 文档 |
| Flyway | 10.14.0 | 数据库版本迁移 |
| Lombok | - | Spring Boot 管理版本 |
| Guava | 33.2.0-jre | 工具库 |

**版本锁定原则：** 所有依赖版本由父 POM `dependencyManagement` 统一管理，子模块**禁止**自行指定版本号。

**单体架构说明：** 当前为单体 Spring Boot 应用（端口 6565），按 DDD 分层拆分为 6 个 Maven 模块。未来微服务化时，通过 Nacos + Spring Cloud Gateway 拆分，当前预留扩展点。

---

## 3. 项目结构与模块职责

### 3.1 单体模块拆分

```
helloai/
├── helloai-common/          # 基础工具：BaseEntity、R、BizException、常量、工具类
├── helloai-mq/              # MQ 层：RabbitMQ 配置、Producer、幂等消费基类、去重服务
├── helloai-job/             # 定时任务：Outbox 补偿、通知重试、超时巡检、健康检查、执行记录补偿
├── helloai-core/            # 核心领域：实体、Mapper、Service、状态机、Outbox、Agent 驱动层、存储层
├── helloai-api/             # REST 层：Controller、DTO、认证、请求日志拦截器、全局异常处理
└── helloai-start/           # 启动入口：HelloAIApplication、application.yml、Flyway 脚本
```

| 层 | 职责 | 可被依赖 |
|----|------|----------|
| `helloai-common` | 基础实体、统一返回、业务异常、常量枚举 | 是（所有模块） |
| `helloai-mq` | RabbitMQ 配置、消息发布、幂等消费抽象 | 是（helloai-core） |
| `helloai-job` | 定时任务调度、补偿逻辑 | 是（helloai-core） |
| `helloai-core` | 领域实体、Mapper、Service、状态机、Agent 驱动、存储 | 是（helloai-api） |
| `helloai-api` | REST 接口、DTO、认证、拦截器 | 否 |
| `helloai-start` | 启动类、配置文件、资源 | 否 |

### 3.2 启动类配置

```java
package com.helloai;

@SpringBootApplication(scanBasePackages = "com.helloai")
@EnableConfigurationProperties(AgentProviderProperties.class)
@MapperScan({
        "com.helloai.core.agent.mapper",
        "com.helloai.core.task.mapper",
        "com.helloai.core.system.mapper"
})
@EnableScheduling
@EnableAsync(proxyTargetClass = true)
public class HelloAIApplication {
    public static void main(String[] args) {
        // 禁用 CGLIB 类缓存（spring-ai 1.x + spring-boot 3.4 + McpAuthFilterConfig
        // CGLIB 增强在异常退出后偶发导致下次启动失败），详见启动类注释
        SpringApplication.run(HelloAIApplication.class, args);
    }
}
```

> ⚠️ `scanBasePackages` **必须**设为 `"com.helloai"`，确保所有模块的 Bean 能被扫描到。
> ⚠️ `@MapperScan` **必须**显式列出三个业务域的 mapper 包（core 域分包重构后已不存在统一的 `core.mapper` 包）；新增业务域时在此追加对应 mapper 包。

### 3.3 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| HelloAI API | 6565 | 单体应用主端口 |
| PostgreSQL | 15432 | Docker Compose 开发环境映射端口 |
| Redis | 26379 | Docker Compose 开发环境映射端口 |
| RabbitMQ | 25672 / 25673 | 消息队列 / 管理后台 |
| MinIO | 29000 / 29001 | 对象存储 / Console |

### 3.x 配置属性类规范

```java
@Data
@ConfigurationProperties(prefix = "moss.thread.pool")
public class MossThreadPoolProperties {
    /** 核心线程数 */
    private int coreSize = 8;
    /** 最大线程数（线上可调至 128/256） */
    private int maxSize = 64;
    /** 队列容量 */
    private int queueCapacity = 1000;
    /** 空闲线程存活秒数 */
    private int keepAliveSeconds = 60;
}
```

| 规则 | 说明 |
|------|------|
| 命名 | `XxxProperties`，如 `AgentConfigProperties`、`MossThreadPoolProperties` |
| 注解 | `@Data` + `@ConfigurationProperties(prefix = "...")` |
| 放置位置 | `com.helloai.common.config` |
| 默认值 | **必须**提供合理默认值，避免配置缺失导致启动失败 |
| 校验 | 复杂配置类用 `@Validated` + `@Min`/`@Max`/`@NotEmpty` 等约束注解 |
| 前缀 | 小写 + 点号分隔（`helloai.agent`、`moss.thread.pool`） |

### 3.x 资源文件存放规范

```
helloai-start/src/main/resources/
├── application.yml                  # 主配置
└── db/
    └── migration/                   # Flyway 多版本迁移脚本（V1__init_all.sql ～ V23__field_naming_normalization.sql）

helloai-core/src/main/resources/
├── mapper/                          # 自定义 Mapper XML（AgentMapper.xml 等 5 个）
├── scripts/
│   └── task-cli.py                  # Agent CLI 工具（可执行脚本）
└── skills/
    ├── planner/SKILL.md             # Planner 技能文档
    ├── executor/SKILL.md            # Executor 技能文档
    └── reviewer/SKILL.md            # Reviewer 技能文档
```

| 目录 | 模块 | 用途 |
|------|------|------|
| `db/migration/` | helloai-start | Flyway 数据库迁移脚本（多版本，详见 8.4） |
| `mapper/` | helloai-core | 需要覆盖 BaseMapper 或自定义 SQL 的 Mapper XML |
| `scripts/` | helloai-core | 可执行脚本（Python CLI 等） |
| `skills/` | helloai-core | 角色技能文档（SKILL.md），运行时可替换变量 |

> **强制**: 所有资源文件**必须通过 `ClassPathResource` 读取**，禁止硬编码绝对路径。

### 3.x 业务域分包规则

core 模块统一采用"业务域分包 + 域内技术分层"，禁止新增顶层 entity/mapper/service 平铺包：

- com.helloai.core.agent   智能体域（注册、调度、执行、对话、MCP、可观测）
- com.helloai.core.task    任务域（任务、子任务、评审、评分、时间线、状态机）
- com.helloai.core.system  系统支撑域（用户、配置、规则、模块、凭据、附件）
- com.helloai.core.shared  跨域基础设施（event、doorbell）

每个域内固定子包：entity / mapper / service；按域需要可扩展。当前各域完整子包：

- **agent**：entity / mapper / service / domain / chat / command / dispatcher / execution / executor / mqconsumer / mcp / observability
- **task**：entity / mapper / service / statemachine / score
- **system**：entity / mapper / service
- **shared**：event / doorbell

语义边界（强制）：
- xxx.entity = 映射数据库表的持久化实体
- xxx.domain = 不映射表的纯内存领域对象/值对象（如 ExecutionCommand、AgentTask）
- agent.chat = 面向业务的 ChatClient 服务层（路由入口、Provider 目录、provider/model 解析）；agent.chat.provider = Provider 接入族（Factory 契约 + 各厂商实现 + ChatModel 缓存），新增 LLM 厂商只动 chat.provider + 一段 yml，chat 父包零感知

新增类的放置判断：先问"它服务哪个业务域"，再问"它在域内承担什么技术角色"。
跨域通用设施才允许放 shared，放 shared 前需在提交说明中写明理由。

**outbox 归属决策**：事务性 outbox 的两张表（agent_outbox_event、agent_command_outbox）及其 entity / mapper / service 归属 agent 域（它们服务的就是 agent 命令与事件分发）；中继调度 OutboxRelayTask 属 helloai-job，MQ 收发侧属 helloai-mq。当第二个业务域引入 outbox 时，再评估将 entity/mapper/service 下沉至 shared/outbox；不要提前建空包占位。

**start 模块配置类归属**：启动模块配置类统一放在 `com.helloai.start.config`；`MyBatisPlusMetaObjectHandler`、`AdminInitializer` 已并入该包，`DeepSeekProviderChatClientFactory` 已迁至 `core.agent.chat.provider`（与 ChatClient 工厂族同源）。新配置类一律放 `start.config`，不允许再出现分裂包。

---

## 4. 命名规范

### 4.1 包命名

```
com.helloai.{模块名}.{层}
```

示例：
- `com.helloai.common.base`
- `com.helloai.common.constant`
- `com.helloai.core.agent.entity` / `com.helloai.core.agent.mapper` / `com.helloai.core.agent.service`
- `com.helloai.core.agent.executor` / `com.helloai.core.agent.mqconsumer` / `com.helloai.core.agent.mcp`
- `com.helloai.core.task.entity` / `com.helloai.core.task.statemachine` / `com.helloai.core.task.score`
- `com.helloai.core.system.entity` / `com.helloai.core.system.service`
- `com.helloai.core.shared.event` / `com.helloai.core.shared.doorbell`
- `com.helloai.api.controller` / `com.helloai.api.dto` / `com.helloai.api.config`
- `com.helloai.job.task`
- `com.helloai.mq.config` / `com.helloai.mq.consumer`

> **禁止**新增 `com.helloai.core.entity` / `core.mapper` / `core.service` 等顶层平铺包（已随业务域分包重构废弃）；core 下新增类必须先定位业务域，详见 3.x 业务域分包规则。

### 4.2 类命名

| 类型 | 命名模式 | 示例 |
|------|----------|------|
| 实体 | `{Name}` | `Task`、`SubTask`、`Agent`、`ReviewRecord` |
| Mapper | `{Entity}Mapper` | `SubTaskMapper`、`AgentMapper` |
| Service 接口 | `{Name}Service` | `SubTaskService`、`ReviewService` |
| Service 实现 | `{Name}ServiceImpl` | `SubTaskServiceImpl`（当前项目可直接用类，不强制接口） |
| Controller | `{Name}Controller` | `SubTaskController`、`AgentController` |
| DTO | `{Action}Request` / `{Action}Response` | `CreateTaskRequest`、`TaskResponse` |
| MQ 消费者 | `{Name}Consumer` | `ExecutorEventConsumer`、`ReviewerEventConsumer` |
| 定时任务 | `{Name}Task` | `AgentEventCompensationTask`、`SubTaskTimeoutTask` |
| 常量类 | `{Name}Status` / `{Name}Role` | `SubTaskStatus`、`AgentRole` |
| 状态机 | `{Name}StateMachine` | `SubTaskStateMachine` |
| 异常 | `BizException` | `BizException` |
| 执行器 | `{Name}Executor` | `CodexExecutor`、`ClaudeExecutor` |
| 回调处理器 | `{Name}CallbackHandler` | `AgentCallbackHandler` |
| 存储服务 | `{Name}StorageService` | `MinioStorageService`、`AttachmentService` |

### 4.3 方法命名

| 动作 | 前缀 | 示例 |
|------|------|------|
| 创建 | `create` | `createTask`、`createReview` |
| 查询单个 | `getBy{Field}` / `selectById` | `getById`、`selectBySubTaskId` |
| 查询列表 | `list` / `query` | `listByStatus`、`queryPending` |
| 更新 | `update` / `change` / `mark` | `changeStatus`、`markBlocked` |
| 删除 | `delete` / `remove` | `deleteById` |
| 状态机校验 | `validate` | `validateTransition` |
| 评分计算 | `calculate` | `calculateCompositeScore` |
| AI 执行 | `execute` | `execute`、`executeAsync` |
| 回调处理 | `handle` | `handle`、`handleFailure` |
| 归档 | `archive` | `archiveOnComplete` |
| 补偿 | `compensate` | `compensateOutbox`、`compensateExecution` |

### 4.4 数据库字段命名

- 表名：蛇形（`task`、`sub_task`、`agent_execution_record`）
- 字段名：蛇形（`task_id`、`assigned_agent_id`、`create_time`）
- 时间字段：`create_time` / `update_time`（**非** `created_at` / `updated_at`）
- JSONB 字段：`context`、`score_factors`、`detail`、`payload`
- 布尔/状态：`status`、`deleted`、`result`

> 字段命名的强制细则（时间 `xxx_time`、外键 `xxx_id`、计量 `xxx_count`、避开关键字、主键策略）以 **8.5 字段命名强制规则** 为唯一权威。

### 4.x 接口使用原则

| 场景 | 是否强制接口 | 说明 |
|------|:----------:|------|
| Service 层 | **不强制** | 单一实现时直接写 `XxxService` 类即可；存在多实现（如多数据源、Mock）时才提取接口 |
| Mapper 层 | 不强制 | MyBatis-Plus `BaseMapper` 已满足，自定义方法直接写在 Mapper 接口中即可 |
| 策略模式 | **强制** | 如 `AgentExecutor`（Claude/Codex/Local 三实现），必须有接口 |
| Feign 客户端 | **强制** | 未来微服务化时 API 层必须定义接口 |

> **原则**: 不要为了"面向接口编程"而制造空接口。接口是抽象边界的产物，不是代码模板的填充物。

### 4.x 常量与枚举命名

| 类型 | 命名规则 | 示例 |
|------|----------|------|
| 常量类 | `XxxConstant`（单数） | `CacheConstant`、`MqConstant` |
| 常量值 | 全大写 + 下划线 | `MAX_RETRY_COUNT = 5` |
| 枚举类 | `XxxStatus`、`XxxType`、`XxxRole` | `SubTaskStatus`、`AgentRole` |
| 枚举值 | 全大写 | `PENDING`、`ASSIGNED`、`IN_PROGRESS` |
| 配置项前缀 | 小写 + 点号 | `moss.thread.pool.core-size` |

> **禁止**: 在业务代码中硬编码魔法数字（如 `if (status == 3)`、`if ("active".equals(s))`），必须改用枚举。

---

## 5. 实体类规范

### 5.1 BaseEntity 定义

```java
package com.helloai.common.base;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
public abstract class BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private String createBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updateTime;

    private String remark;
}
```

> ⚠️ **时间戳使用 `OffsetDateTime`**（带时区），对应 PostgreSQL `TIMESTAMPTZ`。

### 5.2 业务实体 vs 关系表

**业务实体**（承载业务数据，需要审计追踪）必须遵循以下规则：

1. **必须继承 `BaseEntity`**
2. **必须使用 `@Data` + `@EqualsAndHashCode(callSuper = true)`**（不手写 getter/setter，确保继承字段参与 equals/hashCode）
3. **必须使用 `@TableName`** 指定数据库表名
4. **只定义业务字段**，公共字段由 `BaseEntity` 提供
5. 非数据库字段使用 `@TableField(exist = false)`
6. **JSONB 字段使用 `@TableField(typeHandler = JacksonTypeHandler.class)`**

**关系表**（多对多关联）仅记录外键关联，不承载审计信息：
- `@Data` + `@TableName`，无需继承 `BaseEntity`，仅定义外键字段
- **复合主键**：`PRIMARY KEY (fk1, fk2)`，不需要独立 `id`
- 不需要 `deleted`/`create_by`/`update_by`/`create_time`/`update_time`/`remark`
- **更新逻辑**：先 `DELETE` 旧关联再批量 `INSERT` 新关联，无需逐行更新

```java
// ✅ 业务实体 — 正确示范（SubTask.java）
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sub_task")
public class SubTask extends BaseEntity {
    private Long taskId;
    private Long moduleId;
    private String title;

    @TableField("status")
    private SubTaskStatus status;

    private Long assignedAgent;
    private String content;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> context;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> scoreFactors;

    private Integer compositeScore;
    private String scoreGrade;
    private OffsetDateTime deadline;

    @Version
    private Integer version;

    private Integer timeoutCount;
}
```

```java
// ❌ 错误示范
@Data
@TableName("sub_task")
public class SubTask {  // 缺少继承 BaseEntity
    private Long id;              // ❌ BaseEntity 已包含
    private String title;
    private OffsetDateTime createTime;  // ❌ BaseEntity 已包含
    private OffsetDateTime updateTime;  // ❌ BaseEntity 已包含
}
```

```java
// ✅ 关系表 — 正确示范
@Data
@TableName("sys_user_role")
public class SysUserRole {
    private Long userId;
    private Long roleId;
}
```

```sql
-- 对应的 DDL
CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);
```

### 5.3 自动填充机制

`MyBatisPlusMetaObjectHandler` 使用 `setFieldValByName` 实现自动填充：

```java
@Component
public class MyBatisPlusMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        this.setFieldValByName("deleted", 0, metaObject);
        this.setFieldValByName("createBy", getCurrentUser(), metaObject);
        this.setFieldValByName("updateBy", getCurrentUser(), metaObject);
        this.setFieldValByName("createTime", OffsetDateTime.now(), metaObject);
        this.setFieldValByName("updateTime", OffsetDateTime.now(), metaObject);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.setFieldValByName("updateTime", OffsetDateTime.now(), metaObject);
        this.setFieldValByName("updateBy", getCurrentUser(), metaObject);
    }

    private String getCurrentUser() {
        // 从 SecurityContext 获取当前用户，未登录返回 "system"
        return "system";
    }
}
```

> ⚠️ **使用 `setFieldValByName`，不使用 `strictInsertFill` / `strictUpdateFill`**

### 5.4 JSONB 字段规范

PostgreSQL JSONB 字段在实体中的映射：

```java
// 方式 1：Map<String, Object>（通用）
@TableField(typeHandler = JacksonTypeHandler.class)
private Map<String, Object> context;

// 方式 2：自定义对象（类型安全）
@TableField(typeHandler = JacksonTypeHandler.class)
private ScoreFactors scoreFactors;

// 方式 3：String（原始 JSON，不推荐）
@TableField(typeHandler = JacksonTypeHandler.class)
private String payload;
```

**JSONB 查询规范**（MyBatis-Plus Wrapper）：

```java
// 查 score_factors->>'grade' = 'S' 的任务
LambdaQueryWrapper<SubTask> wrapper = new LambdaQueryWrapper<>();
wrapper.apply("score_factors->>'grade' = {0}", "S")
       .eq(SubTask::getDeleted, 0);

// 查 context 中包含特定 key 的任务
wrapper.apply("context @> {0}::jsonb", "{\"hasError\": true}");
```

### 5.5 日期时间处理规范

| 场景 | Java 类型 | 数据库类型 | 序列化格式 |
|------|----------|-----------|-----------|
| 创建/更新时间戳 | `OffsetDateTime` | `timestamptz` | ISO 8601 (`2026-07-03T12:00:00+08:00`) |
| 纯日期（如交付日期） | `LocalDate` | `date` | `YYYY-MM-DD` |
| 纯时间（如定时触发） | `LocalTime` | `time` | `HH:mm:ss` |
| 持续时间 | `java.time.Duration` | — | — |

> **禁止**:
> - 禁止使用 `java.util.Date` 和 `java.sql.Timestamp`（已过时）
> - 禁止在数据库使用 `timestamp without time zone`，一律用 `timestamptz`
> - 禁止在 Controller 层手动格式化时间为字符串，前端负责格式化

**序列化示例**:
```java
// 实体中
private OffsetDateTime createTime;     // MyBatis-Plus 自动映射 timestamptz
private LocalDate deadlineDate;        // 纯日期字段

// JSON 序列化时自动输出 ISO 8601 格式
// {"createTime": "2026-07-03T12:00:00+08:00", "deadlineDate": "2026-07-10"}
```

---

### 6.1 基本规则

- 使用 `@RestController` + `@RequestMapping("/api/{业务}")`
- 返回值统一用 `R<T>`
- 构造器注入依赖
- 日志使用 SLF4J
- Controller 保持薄，只负责请求入口、参数转换、返回封装
- 复杂业务编排放在 Service 层

### 6.2 标准示例

```java
@RestController
@RequestMapping("/api/sub-tasks")
public class SubTaskController {

    private static final Logger log = LoggerFactory.getLogger(SubTaskController.class);
    private final SubTaskService subTaskService;

    public SubTaskController(SubTaskService subTaskService) {
        this.subTaskService = subTaskService;
    }

    @PostMapping("/change-status")
    public R<Void> changeStatus(@RequestBody ChangeStatusRequest request) {
        subTaskService.changeStatus(request.getSubTaskId(), request.getNewStatus(), request.getAgentId());
        return R.ok();
    }

    @GetMapping("/{id}")
    public R<SubTaskResponse> getById(@PathVariable Long id) {
        SubTask subTask = subTaskService.getById(id);
        if (subTask == null) return R.fail("SubTask not found");
        // ... 转换 Response DTO
        return R.ok(dto);
    }
}
```

### 6.3 Controller 职责边界

Controller 只允许做三件事：**参数接收与校验、调用 Service、封装返回**。

**分层红线（强制）**：

1. **禁止注入 Mapper**——任何查询/写入都必须经过 Service，Controller 出现 `private final XxxMapper` 即违规；
2. **禁止书写 SQL / QueryWrapper 条件**——条件构造属 Service 层职责；
3. **禁止事务注解**——`@Transactional` 只允许出现在 Service；
4. 返回 DTO 不返回 Entity（见 6.7）；异常统一交 `GlobalExceptionHandler`，不在 Controller 里 try-catch 业务异常。

> ✅ 收口完成：6 个历史违规 Controller（ActivityController、AdminDashboardController、AgentDutyLeaseController、AttachmentController、DashboardController、FeedController）已全部迁回 Service，对应 Mapper 调用与 QueryWrapper 已下移至对应 Service 新增方法（ActivityLogService / AdminDashboardService / AgentDutyLeaseService / AttachmentService / DashboardService / FeedService）。上述四条分层红线作为硬约束持续生效，新增接口不得再次触发。

### 6.4 嵌套资源路径规范

父子资源关系用路径嵌套表达：`/api/{parent}/{parentId}/{child}`

```java
// Task 下的 Module
@RestController
@RequestMapping("/api/tasks/{taskId}/modules")
public class ModuleController {
    @GetMapping
    public R<List<Module>> list(@PathVariable Long taskId) { ... }
    
    @PostMapping
    public R<Module> create(@PathVariable Long taskId, @RequestBody @Valid ModuleCreateRequest req) { ... }
}
```

### 6.5 状态操作端点规范

状态变更使用专用动作端点，**优先**使用 `/{action}/{id}`，避免把标识参数夹在路径中间；若历史接口已对外提供 `/{id}/{action}`，可短期兼容保留，但新代码与新文档统一按前者书写。

```java
// 子任务状态操作（每个动作一个独立端点）
@PostMapping("/claim/{id}")
public R<SubTask> claim(@PathVariable Long id, @RequestBody ClaimRequest req) { ... }

@PostMapping("/start/{id}")
public R<SubTask> start(@PathVariable Long id) { ... }

@PostMapping("/submit/{id}")
public R<SubTask> submit(@PathVariable Long id) { ... }
```

### 6.6 分页查询规范

列表接口统一接收 `page` 和 `pageSize` 查询参数，默认值 `page=1, pageSize=20`。
当使用 `@RequestParam(defaultValue = "...")` 时，必须显式声明参数名，避免运行时因未开启参数名保留而绑定失败。

```java
@GetMapping
public R<PageResult<T>> list(
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
        // ... 过滤参数
) {
    Page<T> result = service.page(new Page<>(page, pageSize), wrapper);
    return R.ok(PageResult.of(result));
}
```

如果列表接口对外返回 `Response DTO`，则在 Controller 中完成实体到 DTO 的轻量映射，推荐统一使用 `PageResult.of(page, mapper)`：

```java
@GetMapping
public R<PageResult<TaskResponse>> list(
        @RequestParam(value = "page", defaultValue = "1") int page,
        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
) {
    Page<Task> result = taskService.page(new Page<>(page, pageSize), wrapper);
    return R.ok(PageResult.of(result, this::toResponse));
}
```

分页响应封装：

```java
@Data
public class PageResult<T> {
    private List<T> list;
    private long total;
    private long pages;
    private long current;
    
    public static <T> PageResult<T> of(Page<T> page) {
        PageResult<T> r = new PageResult<>();
        r.setList(page.getRecords());
        r.setTotal(page.getTotal());
        r.setPages(page.getPages());
        r.setCurrent(page.getCurrent());
        return r;
    }

    public static <S, T> PageResult<T> of(Page<S> page, Function<S, T> mapper) {
        PageResult<T> r = new PageResult<>();
        r.setList(page.getRecords().stream().map(mapper).toList());
        r.setTotal(page.getTotal());
        r.setPages(page.getPages());
        r.setCurrent(page.getCurrent());
        return r;
    }
}
```

### 6.7 Request/Response DTO 分类规范

| 类型 | 包路径 | 命名规则 | 示例 |
|------|--------|----------|------|
| 请求 DTO | `dto/{domain}/` | `{Action}Request` | `CreateTaskRequest` |
| 响应 DTO | `dto/{domain}/` | `{Action}Response` | `TaskResponse` |
| 通用分页 | `dto/` | `PageResult<T>` | `PageResult<SubTask>` |

默认规则：

- 查询接口、详情接口、列表接口默认返回 `Response DTO`
- 创建/更新接口如果返回资源快照，也默认返回 `Response DTO`
- 纯命令型接口（如状态推进、删除、触发动作）优先返回 `R<Void>`
- 聚合看板、技能下发、简单 KV 响应可返回专用聚合 DTO 或 `Map<String, Object>`，但不直接暴露实体


| 允许 | 禁止 |
|------|------|
| 参数校验 | 业务逻辑 |
| 调用 Service | 在普通业务接口中直接操作 Mapper/DB |
| DTO 转换 | 事务管理 |
| 返回 R | 跨服务调用逻辑（当前单体，未来微服务化后走 Feign） |

---

## 7. Service 规范

### 7.1 基本规则

- `@Service` 注解
- 构造器注入所有依赖
- 方法级 `@Transactional(rollbackFor = Exception.class)`
- 使用 LambdaQueryWrapper / LambdaUpdateWrapper
- `ServiceImpl` 可以注入多个 Mapper，但仅限当前模块所属 Mapper
- 若一个 `ServiceImpl` 同时承担"本地领域逻辑 + 多流程聚合"，应考虑拆分为领域 Service 与编排型 Service

### 7.2 标准编写模式

```java
@Service
@RequiredArgsConstructor
public class SubTaskService extends ServiceImpl<SubTaskMapper, SubTask> {

    private final AgentOutboxService agentOutboxService;
    private final AgentExecutionRecordService executionRecordService;

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId) {
        // 1. 查询
        SubTask subTask = getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }

        // 2. 状态机校验
        SubTaskStateMachine.validate(subTask.getStatus(), newStatus);

        // 3. CAS 更新（@Version 乐观锁）
        subTask.setStatus(newStatus);
        subTask.setAssignedAgent(agentId);

        boolean updated = updateById(subTask);
        if (!updated) {
            throw new BizException("并发修改，请重试");
        }

        // 4. 同事务写入 Outbox
        agentOutboxService.createEvent(subTask, newStatus);
    }
}
```

### 7.3 查询规范

```java
// 单条查询
SubTask subTask = subTaskMapper.selectOne(
    new LambdaQueryWrapper<SubTask>()
        .eq(SubTask::getId, subTaskId)
        .eq(SubTask::getDeleted, 0));

// 更新（MyBatis-Plus @Version 自动处理）
subTask.setStatus(SubTaskStatus.ASSIGNED);
subTaskMapper.updateById(subTask);

// 条件更新
subTaskMapper.update(null,
    new LambdaUpdateWrapper<SubTask>()
        .eq(SubTask::getId, subTaskId)
        .eq(SubTask::getVersion, subTask.getVersion())
        .set(SubTask::getStatus, SubTaskStatus.ASSIGNED));
```

> ⚠️ **乐观锁必须使用 `@Version` + `updateById`**，禁止手动拼接 `setSql("version = version + 1")`。

---

### 7.4 状态机模式


复杂状态流转（如子任务状态机）需要在 Service 层明确定义合法转移表：

```java
public class SubTaskService extends ServiceImpl<SubTaskMapper, SubTask> {

    private static final Map<SubTaskStatus, Set<SubTaskStatus>> VALID_TRANSITIONS = Map.of(
        SubTaskStatus.PENDING,     Set.of(SubTaskStatus.ASSIGNED),
        SubTaskStatus.ASSIGNED,    Set.of(SubTaskStatus.IN_PROGRESS, SubTaskStatus.PENDING),
        SubTaskStatus.IN_PROGRESS, Set.of(SubTaskStatus.REVIEW),
        SubTaskStatus.REVIEW,      Set.of(SubTaskStatus.DONE, SubTaskStatus.REWORK),
        SubTaskStatus.REWORK,      Set.of(SubTaskStatus.IN_PROGRESS),
        SubTaskStatus.BLOCKED,     Set.of(SubTaskStatus.PENDING),
        SubTaskStatus.DONE,        Set.of(),
        SubTaskStatus.CANCELLED,   Set.of()
    );

    private void validateTransition(SubTaskStatus from, SubTaskStatus to) {
        Set<SubTaskStatus> allowed = VALID_TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new BizException(String.format(
                "状态转移不合法: %s → %s", from, to));
        }
    }
}
```

每个对外状态操作方法（`claim`、`start`、`submit`、`complete`、`rework`、`block`）内部先调用 `validateTransition()`，再执行字段更新 + 事务提交。

### 7.5 跨 Service 事务协作模式

当一个操作需要跨多个 Service 更新数据时（例如审查通过 → 修改子任务状态 + 加积分），使用以下模式：

```java
@Service
@RequiredArgsConstructor  // 所有依赖通过构造器注入
public class ReviewService {

    private final SubTaskService subTaskService;  // 跨 Service 注入
    private final RewardService rewardService;

    @Transactional(rollbackFor = Exception.class)  // 统一事务边界
    public ReviewRecord createReview(...) {
        // 1. 写审查记录
        save(reviewRecord);

        // 2. 推进子任务状态（跨 Service，同一事务）
        if ("approved".equals(result)) {
            subTaskService.complete(subTaskId);       // 同事务
            rewardService.grant(subTaskId, score);    // 同事务
        } else {
            subTaskService.rework(subTaskId, reworkAgent);
        }
    }
}
```

> ⚠️ **禁止**在跨 Service 调用中使用 `@Transactional(propagation = Propagation.REQUIRES_NEW)`，除非有明确的异步隔离需求。默认使用 `REQUIRED`（同一事务）。

### 7.6 空值与 Optional 处理规范

| 场景 | 规范 |
|------|------|
| Service 返回单个实体 | 查不到返回 `null`，调用方用 `Objects.requireNonNullElse` 或判空后抛 `BizException` |
| Service 返回列表 | 查不到返回 `Collections.emptyList()`，**绝不**返回 `null` |
| Mapper 查询 | `selectOne` 可能返回 `null`，Controller 层**必须**判空 |
| Optional | 仅在链式操作中使用（`Optional.ofNullable(x).map(...).orElse(...)`），**禁止**作为方法参数、类字段或返回值类型 |

```java
// ✅ 推荐：列表方法绝不返回 null
public List<SubTask> listByStatus(SubTaskStatus status) {
    List<SubTask> list = lambdaQuery().eq(SubTask::getStatus, status).list();
    return list != null ? list : Collections.emptyList();
}

// ❌ 禁止：列表方法可能返回 null
public List<SubTask> listByStatus(SubTaskStatus status) {
    return lambdaQuery().eq(SubTask::getStatus, status).list();
}
```

> **原则**: 方法返回集合类型时，"没有数据"和"出错"是两个概念。前者返回空集合，后者抛异常。


## 8. 数据库设计规范

### 8.1 业务表必备公共字段

**业务数据表**必须包含以下审计字段（关系表除外，见 5.2）：

```sql
id          bigint NOT NULL,                                                  -- 雪花ID（ASSIGN_ID）
deleted     smallint NOT NULL DEFAULT 0,                                     -- 逻辑删除: 0=未删除, 1=已删除
create_by   varchar(64) NOT NULL DEFAULT '',                                  -- 创建人
update_by   varchar(64) NOT NULL DEFAULT '',                                  -- 更新人
create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,                  -- 创建时间
update_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,                   -- 更新时间（PG 用触发器更新）
remark      varchar(255) DEFAULT NULL                                         -- 备注
```

### 8.2 JDBC 连接配置

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:15432/helloai?currentSchema=public&reWriteBatchedInserts=true
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
```

### 8.3 建表规范

- **业务表**主键：`id bigint NOT NULL`，应用层生成雪花 ID
- **关系表**主键：`PRIMARY KEY (fk1, fk2)`，复合主键，不需要独立 `id`
- 时间戳：`timestamptz`（带时区），不用 `datetime`
- 布尔值：`smallint`（0/1），不用 `boolean`（与 Java 映射兼容性更好）
- 金额字段：`decimal(18,2)` 或 `decimal(20,4)`
- 状态字段：`varchar(32)` 或 `smallint`，配合常量类
- JSON 字段：`jsonb`（不要用 `json`，`jsonb` 支持索引和压缩）
- 索引：业务唯一键建唯一索引（如 `event_id`）
- **PG 触发器**：`update_time` 通过触发器自动更新（PG 无 `ON UPDATE CURRENT_TIMESTAMP`）

```sql
-- 触发器函数
CREATE OR REPLACE FUNCTION update_update_time_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.update_time = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- 绑定到表
CREATE TRIGGER update_sub_task_update_time BEFORE UPDATE ON sub_task
    FOR EACH ROW EXECUTE FUNCTION update_update_time_column();
```

### 8.4 Flyway 迁移规范

- 仓库实际为多版本迁移（`V1__init_all.sql` ～ `V23__field_naming_normalization.sql`），**禁止再修改已执行的 V1～V23 历史脚本**（改历史脚本会导致已有环境 checksum 校验失败）
- 所有新增 DDL（建表、加字段、索引、种子数据、字段改名）**必须新建 `V{N+1}__<用途>.sql`**，版本号顺延，用途用小写蛇形描述
- 所有 DDL 必须使用 `IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS` / `ON CONFLICT DO NOTHING` 等幂等语法；脚本末尾附 `DO $$ ... RAISE NOTICE` 验证块（参照 V16/V17/V23 样式）
- `application.yml` 中 Flyway 配置只保留 `enabled: true` + `locations`，**不加** `baseline-on-migrate` 和 `repair-on-migrate`
- 字段改名类迁移必须与 entity 字段改名、XML 裸列名、Java 裸列名字符串在同一提交内完成（参照 8.5 实施要点）

### 8.5 字段命名强制规则

> 来源：V23 字段命名规范化迁移（`helloai-start/src/main/resources/db/migration/V23__field_naming_normalization.sql`，2026-07-18 落地 20 列）。
> 本节是字段命名的"唯一权威"，与本规范 8.1～8.4 冲突时以本节为准。

| 类别 | 强制规则 |
|------|----------|
| 时间字段 | 一律 `xxx_time`（对齐 `create_time` / `update_time`），**禁止** `_at` 后缀；DATE 类型一律 `xxx_date` |
| 外键 / ID 引用 | 一律 `xxx_id`；同一 ID 多角色引用必须加角色前缀：`assigned_agent_id`、`reviewer_agent_id` |
| 状态字段 | `varchar` + 枚举字符串（不使用数字枚举） |
| 计量字段 | 一律 `xxx_count`（如 `consecutive_failure_count`、`timeout_count`），**禁止** `total_xxx` / `num_xxx` 混用 |
| 关键字 | 列名必须避开数据库关键字（`trigger` / `order` / `level` / `user` 等）；无法避开时使用 `xxx_type` / `xxx_source` 形式（如 `trigger_type`） |
| 主键 | 新增表主键一律 `BIGINT` + 应用层雪花 ID，**禁止**新增 `bigserial` 自增主键 |

实施要点：

1. **DB 列改名后 Java 字段必须同步改名**——项目采用 `map-underscore-to-camel-case: true`，不能靠 `@TableField(value="旧列名")` 挂旧名。
2. **DTO 字段保持稳定**——API 契约不因 entity 改名而变；`AgentResponse.lastSeenAt`、`SubTaskResponse.assignedAgent` 等仍保留旧名，entity→DTO 装配点显式 `response.setXxx(entity.getNewName())`。
3. **MyBatis-Plus 自定义 XML 需手改两处**：SQL 裸列名 + `#{et.xxx}` OGNL 引用（IDEA Rename Field **不会**联动）。
4. **Java 端裸列名 UpdateWrapper 字符串**也需手改（项目内唯一已知位置：`AgentHealthCheckTask` L138-139 UpdateWrapper）。
5. **PG `RENAME COLUMN` 自动更新**引用该列的索引 / 约束定义，本项目 4 个索引（`idx_agent_command_outbox_pending_scan` / `idx_agent_command_outbox_sent_scan` / `idx_exec_record_pending_attempt` / `idx_agent_external_failure_scan`）无需重建；列注释随列保留。
6. 迁移文件管理按 8.4 执行（历史脚本冻结、新建 `V_NN__<用途>.sql`）。
7. **变更残留检查范围（强制）**：字段改名 / 包重构完成后，旧名残留 grep 的检查范围 = **Java 源码 + mapper XML + `scripts/`（PowerShell 与 shell 验证脚本）+ `doc/`**。scripts 不参与编译启动，是残留高发区；消息契约除外——jsonb payload 内的键名（如 `trigger`）属域对象序列化契约，不随 DB 列改名。

---

## 9. Outbox 事务性消息规范

### 9.1 核心思路

业务操作与事件写入同一本地事务，确保一致性。Outbox 表由定时任务补偿发送。

```java
@Transactional(rollbackFor = Exception.class)
public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId) {
    // 1. 业务操作
    subTask.setStatus(newStatus);
    updateById(subTask);

    // 2. 同事务写入 Outbox 事件（关键！）
    AgentOutboxEvent outbox = new AgentOutboxEvent();
    outbox.setEventId(UUID.randomUUID().toString().replace("-", ""));
    outbox.setEventType("sub_task." + newStatus.name().toLowerCase());
    outbox.setRoutingKey(resolveRoutingKey(newStatus));
    outbox.setPayload(buildPayload(subTask, newStatus));
    outbox.setStatus(OutboxStatus.PENDING);
    outboxEventMapper.insert(outbox);

    // 3. 尝试立即发布（失败不影响事务）
    try {
        eventPublisher.publish(outbox.getRoutingKey(), outbox.getPayload());
        outbox.setStatus(OutboxStatus.SUCCESS);
        outboxEventMapper.updateById(outbox);
    } catch (Exception e) {
        log.error("发布失败，定时任务将补偿", e);
    }
}
```

### 9.2 Outbox 状态流转

`PENDING(0)` → `SUCCESS(1)` / `FAILED(2)` → 重试 → 超过最大重试次数 → 人工介入

### 9.3 补偿任务

```java
@Scheduled(fixedRate = 15000) // 15秒
public void compensate() {
    // 查询 PENDING 且超过一定时间的记录
    // 重新发送 MQ
    // 更新状态或增加重试计数
}
```

---

## 10. 消息队列编码规范

### 10.1 Exchange / Queue / RoutingKey 命名

```
Exchange:   helloai.{角色}.exchange
Queue:      helloai.{角色}.queue
RoutingKey: agent.{角色}.{动作}
DLX:        helloai.dlx.exchange
DLQ:        helloai.dlx.queue
```

项目实际命名：

| 名称 | 值 |
|------|------|
| AGENT_TOPIC_EXCHANGE | `helloai.agent.exchange` |
| DLX_EXCHANGE | `helloai.dlx.exchange` |
| EXECUTOR_QUEUE | `helloai.executor.queue` |
| REVIEWER_QUEUE | `helloai.reviewer.queue` |
| PLANNER_QUEUE | `helloai.planner.queue` |
| DLX_QUEUE | `helloai.dlx.queue` |

### 10.2 队列配置要点

```java
@Bean
public Queue executorQueue() {
    return QueueBuilder.durable(EXECUTOR_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", DLX_QUEUE)
        .build();
}
```

**关键配置：**
- 所有业务队列**必须绑定 DLX**（死信交换机）
- Publisher Confirm + Mandatory 模式
- Consumer ACK 模式：MANUAL（手动确认）

### 10.3 幂等消费者编写规范

所有消费者**必须继承 `AbstractIdempotentConsumer`**，提供 Redis + DB 双层去重：

```java
@Component
@RabbitListener(queues = RabbitMQConfig.EXECUTOR_QUEUE, ackMode = "MANUAL")
public class ExecutorEventConsumer extends AbstractIdempotentConsumer {

    public ExecutorEventConsumer(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                 MessageDeduplicationService deduplicationService) {
        super(jdbcTemplate, objectMapper, deduplicationService);
    }

    @RabbitHandler
    public void onMessage(String payload) {
        String messageId = extractMessageId(payload);

        tryConsume(messageId, "ExecutorEventConsumer", () -> {
            // 业务逻辑
        });
    }
}
```

### 10.4 消费顺序（防 ACK 丢失）

```java
// 正确顺序：
// 1. 插入 agent_execution_record（PENDING）
// 2. channel.basicAck()（ACK）
// 3. 提交线程池异步执行
// 4. 更新 execution_record（RUNNING）
// 5. AI 执行
// 6. 更新 execution_record（SUCCESS/FAILED）
```

> ⚠️ **绝对禁止先 ACK 再持久化**。ACK 后 JVM 崩溃，消息永久丢失且无法追踪。

---

## 11. 分布式锁编码规范

### 11.1 统一使用 Redis 分布式锁

**禁止直接操作 Redis 客户端**，必须通过封装工具统一加锁。

### 11.2 锁键命名规范

| 业务场景 | 锁键格式 |
|----------|----------|
| 定时任务 | `scheduler:lock:{任务名}` |
| 子任务状态变更 | `subtask:lock:{subTaskId}` |
| Outbox 补偿 | `scheduler:lock:AgentOutbox` |
| 执行记录补偿 | `scheduler:lock:ExecutionComp` |
| 通知重试 | `scheduler:lock:AgentNotify` |
| 超时巡检 | `scheduler:lock:SubTaskTimeout` |
| 健康检查 | `scheduler:lock:AgentHealth` |

### 11.3 使用方式

```java
// 函数式（推荐）— 无返回值
redisLockUtil.executeWithLock(
    "scheduler:lock:AgentOutbox",
    () -> {
        // 业务逻辑
    }
);

// 手动管理（不推荐，仅特殊场景）
Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 30, TimeUnit.SECONDS);
if (!locked) throw new BizException("获取锁失败");
try {
    // ...
} finally {
    redisTemplate.delete(lockKey);
}
```

### 11.4 默认参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| waitTime | 5 秒 | 等待获取锁的超时时间 |
| leaseTime | 30 秒 | 锁持有时间（定时任务场景） |

---

## 12. 异常处理规范

### 12.1 异常体系

```
RuntimeException
└── BizException(code, msg)    -- 业务异常（可预期）
    ├── code = 500（默认）
    └── code = 自定义业务码
```

### 12.2 使用方式

```java
// 简洁形式
throw new BizException("子任务不存在");

// 带错误码
throw new BizException(4001, "并发修改，请重试");

// 资源校验模式
SubTask subTask = subTaskMapper.selectById(subTaskId);
if (subTask == null) {
    throw new BizException("子任务不存在: " + subTaskId);
}
```

### 12.3 禁止事项

- ❌ 不要 `catch (Exception e)` 后吞掉异常不处理
- ❌ 不要在 Controller 层手动 try-catch 返回 R.fail（交给全局异常处理器）
- ❌ 不要抛出 checked exception（如 `throws IOException`）
- ✅ 业务校验失败统一抛 `BizException`
- ✅ JSON 序列化异常包装为 `RuntimeException` 抛出

---

## 13. 日志与链路追踪规范

### 13.1 日志框架

- 使用 SLF4J + Logback
- Logger 声明：`private static final Logger log = LoggerFactory.getLogger(当前类.class);`

### 13.2 日志级别使用

| 级别 | 场景 |
|------|------|
| ERROR | 系统异常、不可恢复错误、AI API 调用失败 |
| WARN | 可恢复异常、降级操作、Outbox 补偿失败、执行记录超时 |
| INFO | 关键业务节点（任务创建/状态变更/审查完成/积分变动） |
| DEBUG | 锁获取/释放、详细中间状态、Prompt 模板渲染 |

### 13.3 日志内容规范

```java
// ✅ 包含关键业务标识
log.info("任务状态变更: subTaskId={}, from={}, to={}, agentId={}", 
    subTaskId, oldStatus, newStatus, agentId);
log.info("审查完成: subTaskId={}, result={}, score={}", 
    subTaskId, reviewResult, score);
log.warn("Execution PENDING timeout: eventId={}, subTaskId={}", 
    eventId, subTaskId);
log.error("AI 调用失败: subTaskId={}, model={}, error={}", 
    subTaskId, modelType, e.getMessage());

// ❌ 缺少业务标识
log.info("任务完成");
log.error("发送失败");
```

### 13.4 链路追踪

- 使用 `traceId` 贯穿请求全链路
- `RequestLogInterceptor` 生成 traceId，放入 MDC
- 所有日志自动携带 traceId
- AI 调用时 traceId 传入 Prompt，便于追踪

---

## 14. 定时任务编码规范

### 14.1 任务放置位置

所有定时任务统一放在 `helloai-job` 模块的 `task` 包下。

### 14.2 编写规范

- 使用 `@Scheduled` 注解
- 任务内加 Redis 分布式锁防并发
- 幂等设计（重复执行不产生副作用）
- 记录开始/结束日志

### 14.3 典型场景

| 任务 | 功能 | 间隔 |
|------|------|------|
| AgentEventCompensationTask | 重试发送 PENDING 状态的 Outbox 事件 | 15s |
| AgentNotifyRetryTask | 阶梯退避重试通知 | 10s |
| SubTaskTimeoutTask | 检查超时子任务，触发 BLOCKED | 30s |
| AgentHealthCheckTask | 检查 Agent 健康状态 | 60s |
| **ExecutionCompensationTask** | **扫描 PENDING/RUNNING 超时执行记录** | **30s** |

### 14.4 线程池参数化（关键）

Agent 驱动层的线程池参数外置到 `application.yml`：

```yaml
moss:
  thread:
    pool:
      core-size: 8          # 核心线程数
      max-size: 64          # 最大线程数（线上可调至 128/256）
      queue-capacity: 1000  # 队列容量
      keep-alive-seconds: 60 # 空闲线程存活时间
```

> ⚠️ **生产环境建议初始 `max-size: 128`**。如果 CPU 闲置但任务堆积，直接改配置重启，无需改代码。

---

## 15. Agent 驱动层编码规范

### 15.1 模块结构

```
helloai-core/agent/
├── domain/
│   ├── AgentTask.java              # 任务封装：subTaskId, role, prompt, context, workingDir
│   └── AgentResult.java            # 结果封装：output, tokenUsage, errorMsg, finishReason
├── executor/
│   ├── AgentExecutor.java          # 接口：execute(AgentTask) → CompletableFuture<AgentResult>
│   ├── CodexExecutor.java          # Codex 实现（OpenAI API）
│   ├── ClaudeExecutor.java         # Claude 实现（Anthropic API）
│   └── AgentRouter.java            # 按角色路由模型
├── prompt/
│   └── PromptTemplateEngine.java   # Prompt 模板引擎（从 rule 表读取模板）
├── callback/
│   └── AgentCallbackHandler.java   # 统一回调处理，驱动状态机
├── context/
│   └── ContextManager.java         # 对话历史管理（Redis 主存 + PostgreSQL/归档表）
└── mqconsumer/
    ├── ExecutorEventConsumer.java  # 消费 executor 队列
    ├── ReviewerEventConsumer.java  # 消费 reviewer 队列
    └── PlannerEventConsumer.java   # 消费 planner 队列
```

### 15.2 消费顺序（强制规范）

```java
@RabbitListener(queues = EXECUTOR_QUEUE, ackMode = "MANUAL")
public void onMessage(Message message, Channel channel, long tag) {
    // 1. 先插入 agent_execution_record（PENDING）
    // 2. ACK（channel.basicAck）
    // 3. 提交线程池异步执行
    // 4. 更新 execution_record（RUNNING）
    // 5. 调用 AI API（120s 超时）
    // 6. 回调处理
    // 7. 更新 execution_record（SUCCESS/FAILED）
}
```

> ⚠️ **步骤 1 必须在 ACK 之前完成**。如果先 ACK 再插入记录，JVM 崩溃后消息丢失且无法追踪。

### 15.3 AI 调用超时控制

```java
// 虚拟线程内同步等待（JDK 17 用平台线程池）
AgentResult result = agentExecutor.execute(task).get(120, TimeUnit.SECONDS);
```

- 超时：120 秒
- 超时后：标记 execution_record 为 FAILED，触发 SubTask BLOCKED
- 重试：指数退避，最多 3 次

### 15.4 模型路由策略

| 角色 | 模型 | 理由 |
|------|------|------|
| PLANNER | Claude Opus | 强推理能力，适合任务分解 |
| EXECUTOR | Codex | 代码生成能力最强 |
| REVIEWER | Claude Sonnet | 代码审查，平衡性能与成本 |

### 15.5 上下文管理

```java
@Service
@RequiredArgsConstructor
public class ContextManager {

    private final StringRedisTemplate redis;

    // 热数据：Redis，活跃任务上下文
    public void appendMessage(Long subTaskId, String role, String content) {
        String key = "helloai:context:" + subTaskId;
        // ... 写入 Redis List，TTL 24h
    }

    // 冷归档：任务完成后异步写入 PostgreSQL / 对应归档表
    public void archiveOnComplete(Long subTaskId) {
        // ... 读取 Redis → 写入 conversation_archive → 删除 Redis
    }
}
```

---

## 16. 代码模板（附录）

> **注意**: 本章为快速拷贝模板，内容与正文第 5-7 章、第 15 章有重叠。**正文规范优先于模板**——模板仅作参考，实际编码以正文规范为准。

### 16.1 Entity 模板

```java
package com.helloai.core.{domain}.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("{table_name}")
public class {Name} extends BaseEntity {
    // 只写业务字段，BaseEntity 已含 id/deleted/createBy/updateBy/createTime/updateTime/remark

    private Long taskId;
    private String title;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> context;

    private OffsetDateTime deadline;
}
```

### 16.2 DTO 模板

```java
package com.helloai.api.dto.{domain};

import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class {Name}Response {
    private Long id;
    private String title;
    private String status;
    private OffsetDateTime createTime;
}
```

### 16.3 Controller 模板

```java
package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.api.dto.{domain}.{Name}Response;
import com.helloai.core.{domain}.service.{Name}Service;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/{name}s")
@RequiredArgsConstructor
public class {Name}Controller {

    private static final Logger log = LoggerFactory.getLogger({Name}Controller.class);
    private final {Name}Service service;

    @PostMapping("/change-status")
    public R<Void> changeStatus(@RequestBody ChangeStatusRequest request) {
        service.changeStatus(request.getId(), request.getNewStatus(), request.getAgentId());
        log.info("{} status changed: id={}, status={}", "{Name}", request.getId(), request.getNewStatus());
        return R.ok();
    }

    @GetMapping("/{id}")
    public R<{Name}Response> getById(@PathVariable Long id) {
        return R.ok(service.getResponseById(id));
    }
}
```

### 16.4 ServiceImpl 模板

```java
package com.helloai.core.{domain}.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.core.{domain}.entity.{Name};
import com.helloai.core.{domain}.mapper.{Name}Mapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class {Name}Service extends ServiceImpl<{Name}Mapper, {Name}> {

    private static final Logger log = LoggerFactory.getLogger({Name}Service.class);

    @Transactional(rollbackFor = Exception.class)
    public void create({Name}Request request) {
        // 1. 幂等检查
        // 2. 构造实体
        // 3. 持久化
        // 4. 写入 Outbox（如需要）
        // 5. 返回结果
    }
}
```

### 16.5 MQ Consumer 模板

```java
package com.helloai.core.agent.mqconsumer;

import com.helloai.core.agent.callback.AgentCallbackHandler;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.executor.AgentExecutor;
import com.helloai.core.agent.executor.AgentRouter;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.agent.mapper.AgentExecutionRecordMapper;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.mq.config.RabbitMQConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.concurrent.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class {Name}EventConsumer {

    private final AgentRouter agentRouter;
    private final AgentCallbackHandler callbackHandler;
    private final AgentExecutionRecordMapper executionRecordMapper;
    private final SubTaskService subTaskService;
    private final ThreadPoolExecutor executor;  // 从配置注入

    @RabbitListener(queues = RabbitMQConfig.{QUEUE_NAME}, ackMode = "MANUAL")
    public void onMessage(Message message, Channel channel, 
                          @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        try {
            AgentEvent event = parseEvent(message);

            // 1. 先持久化 execution_record（PENDING）
            AgentExecutionRecord record = new AgentExecutionRecord();
            record.setEventId(event.getEventId());
            record.setSubTaskId(event.getSubTaskId());
            record.setStatus(ExecutionStatus.PENDING);
            record.setWorkerNode(InetAddress.getLocalHost().getHostName());
            executionRecordMapper.insert(record);
            Long recordId = record.getId();

            // 2. ACK
            channel.basicAck(tag, false);

            // 3. 提交线程池
            executor.submit(() -> {
                try {
                    executionRecordMapper.updateStatus(recordId, ExecutionStatus.RUNNING.name(), null);

                    AgentTask task = buildAgentTask(event);
                    AgentExecutor agentExecutor = agentRouter.route(task.getRole());
                    AgentResult result = agentExecutor.execute(task).get(120, TimeUnit.SECONDS);

                    callbackHandler.handle(task.getSubTaskId(), result);

                    executionRecordMapper.updateStatus(recordId, ExecutionStatus.SUCCESS.name(), null);
                } catch (Exception ex) {
                    log.error("Agent执行失败", ex);
                    executionRecordMapper.updateStatus(recordId, ExecutionStatus.FAILED.name(), 
                        ex.getMessage());
                    subTaskService.changeStatus(event.getSubTaskId(), SubTaskStatus.BLOCKED, null);
                }
            });
        } catch (Exception ex) {
            log.error("Consumer处理失败", ex);
            try { channel.basicNack(tag, false, false); } catch (Exception ignored) {}
        }
    }
}
```

### 16.6 状态机模板

```java
package com.helloai.core.task.statemachine;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public class SubTaskStateMachine {

    private static final Map<SubTaskStatus, Set<SubTaskStatus>> TRANSITIONS = new EnumMap<>(SubTaskStatus.class);

    static {
        TRANSITIONS.put(SubTaskStatus.PENDING,     Set.of(SubTaskStatus.ASSIGNED, SubTaskStatus.CANCELLED));
        TRANSITIONS.put(SubTaskStatus.ASSIGNED,     Set.of(SubTaskStatus.IN_PROGRESS, SubTaskStatus.PENDING));
        TRANSITIONS.put(SubTaskStatus.IN_PROGRESS,  Set.of(SubTaskStatus.REVIEW, SubTaskStatus.BLOCKED));
        TRANSITIONS.put(SubTaskStatus.REVIEW,       Set.of(SubTaskStatus.DONE, SubTaskStatus.REWORK));
        TRANSITIONS.put(SubTaskStatus.REWORK,       Set.of(SubTaskStatus.IN_PROGRESS));
        TRANSITIONS.put(SubTaskStatus.BLOCKED,      Set.of(SubTaskStatus.PENDING));
        TRANSITIONS.put(SubTaskStatus.DONE,        Set.of());
        TRANSITIONS.put(SubTaskStatus.CANCELLED,   Set.of());
    }

    public static boolean canTransition(SubTaskStatus from, SubTaskStatus to) {
        Set<SubTaskStatus> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static void validate(SubTaskStatus from, SubTaskStatus to) {
        if (!canTransition(from, to)) {
            throw new BizException(String.format("非法状态转换: %s -> %s", from, to));
        }
    }
}
```

### 16.7 定时任务模板

```java
package com.helloai.job.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class {Name}CompensationTask {

    private final StringRedisTemplate redis;
    private static final String LOCK_KEY = "scheduler:lock:{Name}";

    @Scheduled(fixedRate = 15000)
    public void compensate() {
        if (!tryLock()) return;
        try {
            // 补偿逻辑
        } finally {
            unlock();
        }
    }

    private boolean tryLock() {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", 30, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    private void unlock() {
        redis.delete(LOCK_KEY);
    }
}
```

---

## 17. 开发高频校验清单（附录）

> **用途**: 开发中的速查卡片，按类别分类，适合打印贴在显示器旁。提交前的完整 Checklist 见第 19 章。

### 17.1 后端速查

| 类别 | 快速检查 | 参考章节 |
|------|----------|----------|
| 文件编码 | `.java` 文件必须是 UTF-8 without BOM，文件首字节直接是 `package` | [1. 总体原则](#1-总体原则) |
| 实体与主键 | 业务实体继承 `BaseEntity`，关系表不用；ID 使用 `Long + ASSIGN_ID` | [5. 实体类规范](#5-实体类规范) |
| 自动填充 | 使用 `setFieldValByName`，不手动补 `createTime` / `updateTime` / `deleted` | [5.3 自动填充机制](#53-自动填充机制) |
| 启动类 | `scanBasePackages` 保持 `"com.helloai"`，`@MapperScan` 显式列出 `core.agent/task/system` 三个 mapper 包 | [3.2 启动类配置](#32-启动类配置) |
| 数据库连接 | JDBC URL 指向 PostgreSQL，使用 `timestamptz` | [8.2 JDBC 连接配置](#82-jdbc-连接配置) |
| Controller 与 Service | Controller 只做参数接收、DTO 转换、返回封装；查询默认返回 `Response DTO`；事务放在 Service 层 | [6. Controller 规范](#6-controller-规范)、[7. Service 规范](#7-service-规范) |
| 依赖注入 | 使用构造器注入，非 `@Autowired` 字段注入 | [1. 总体原则](#1-总体原则) |
| 状态与常量 | 禁止硬编码状态值，统一使用枚举类（`SubTaskStatus`、`AgentRole`） | [4. 命名规范](#4-命名规范)、[16.6 状态机模板](#166-状态机模板) |
| 事务与一致性 | `@Transactional(rollbackFor = Exception.class)`；Outbox 与业务操作同一事务 | [9. Outbox 事务性消息规范](#9-outbox-事务性消息规范) |
| MQ 与幂等 | 消费者继承 `AbstractIdempotentConsumer`，队列绑定 DLX，ACK 前插入 execution_record | [10. 消息队列编码规范](#10-消息队列编码规范)、[15.2 消费顺序](#152-消费顺序) |
| 分布式锁 | 定时任务使用 Redis `setIfAbsent` 分布式锁 | [11. 分布式锁编码规范](#11-分布式锁编码规范) |
| 乐观锁 | 使用 `@Version` + `updateById`，禁止手动写 `version = version + 1` | [1. 总体原则](#1-总体原则) |
| JSONB 字段 | 使用 `@TableField(typeHandler = JacksonTypeHandler.class)` | [5.4 JSONB 字段规范](#54-jsonb-字段规范) |
| 线程池 | Agent 驱动层线程池参数外置到 `application.yml` | [14.4 线程池参数化](#144-线程池参数化) |
| 日志 | 包含关键业务标识（subTaskId、eventId、agentId） | [13.3 日志内容规范](#133-日志内容规范) |

### 17.2 前端速查

| 类别 | 快速检查 | 参考章节 |
|------|----------|----------|
| 页面骨架 | 列表页优先使用 `<el-card>`，Header 保持"标题 + 操作按钮" | [18.1 列表页标准结构](#181-列表页标准结构) |
| 表格布局 | 表格整体流式，主体数据列 `min-width`，功能列固定 `width` | [18.1 列表页标准结构](#181-列表页标准结构) |
| 操作列 | 统一 `fixed="right"`，宽度按按钮数量预留 | [18.1 列表页标准结构](#181-列表页标准结构) |
| 长文本列 | 流水号、事件ID 等统一使用 `show-overflow-tooltip` | [18.1 列表页标准结构](#181-列表页标准结构) |
| 脚本分区 | `data → computed → filters → created → methods` | [18.2 Script 结构](#182-script-结构) |
| 样式边界 | 使用 `<style scoped>`，不额外重写页面外层布局 | [18.3 样式规范](#183-样式规范) |

---

## 18. Vue 页面规范

### 18.1 列表页标准结构

前端使用 Vue 3 + Element Plus，列表页遵循以下约定：

- 列表页优先使用 `<el-card>` 作为根容器，Header 保持"标题 + 右侧操作按钮"的统一结构
- 表格整体保持流式布局：`<el-table ... style="width:100%">`
- 主体数据列使用 `min-width`，保证不同分辨率下仍有最小可读宽度
- 功能列使用固定 `width`，如 `ID`、选择列、状态列、排序列、数量列、操作列
- 操作列统一 `fixed="right"`，宽度按按钮数量预留，单个按钮通常 `80` 到 `90`
- 长文本列配合 `show-overflow-tooltip`，如事件ID、Prompt 摘要、路径

```html
<template>
   <el-card>
      <div slot="header">
         <span><i class="el-icon-s-xxx"></i> 任务列表</span>
         <el-button size="mini" type="primary" style="float:right" @click="load">刷新</el-button>
      </div>

      <el-table :data="list" border stripe style="width:100%" v-loading="loading">
         <el-table-column prop="id" label="ID" width="80" />
         <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
         <el-table-column label="状态" width="100">
            <template slot-scope="{row}">
               <el-tag :type="tagMap(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
         </el-table-column>
         <el-table-column label="评分" width="80">
            <template slot-scope="{row}">
               <el-tag :type="scoreTagMap(row.scoreGrade)" size="small">{{ row.scoreGrade }}</el-tag>
            </template>
         </el-table-column>
         <el-table-column label="时间" min-width="160" />
         <el-table-column label="操作" width="120" fixed="right">
            <template slot-scope="{row}">
               <el-button size="mini" @click="handleDetail(row)">详情</el-button>
            </template>
         </el-table-column>
      </el-table>

      <!-- 空状态 -->
      <div v-if="!list.length && !loading" style="text-align:center;padding:60px;color:#909399">
         <i class="el-icon-s-xxx" style="font-size:64px;display:block;margin-bottom:12px"></i>
         <p>暂无数据</p>
      </div>

      <!-- 分页 -->
      <el-pagination v-if="total > 0" background layout="prev, pager, next" :total="total"
                     :page-size="20" @current-change="load" style="margin-top:16px;text-align:center" />
   </el-card>
</template>
```

### 18.2 Script 结构

`<script>` 块按固定顺序分区，导入 → 组件定义 → data → computed → filters → 生命周期 → methods：

```javascript
<script>
   import { apiFunction } from '@/api/module'

   export default {
   // 1. data — 响应式状态
   data() {
   return { list: [], total: 0, loading: false }
},
   // 2. computed — 派生状态（如 Pinia store getters）
   computed: {
   isAdmin() { return this.$store.getters.isAdmin }
},
   // 3. filters — 格式化（时间、金额等）
   filters: {
   fmt(v) { return v || '-' }
},
   // 4. 生命周期 — 初始化加载
   created() { this.load(1) },
   // 5. methods — 按功能分组（加载 → 搜索 → 操作 → 工具）
   methods: {
   load(page) { /* API 调用 */ },
   search() { this.load(1) },
   handleDetail(row) { /* ... */ },
   tagMap(v) { /* 状态颜色映射 */ },
   scoreTagMap(v) { /* S/A/B/C/D 颜色映射 */ }
}
}
</script>
```

### 18.3 样式规范

- `<style scoped>` — 只用 scoped 样式
- **不写**外层包装样式（`max-width` / `margin` / `padding`），由 `<el-main>` 统一控制
- **不写**卡片圆角覆盖（`border-radius`），使用 Element UI 默认
- 只定义弹窗、特殊布局等页面独有的样式

### 18.4 要素检查清单

| 要素 | 要求 |
|------|------|
| 根元素 | `<el-card>`，无外层 div 包装 |
| Header | `<div slot="header">` — 标题 + 操作按钮 |
| 表格 | `<el-table border stripe v-loading>` |
| 表格列宽 | 主体数据列用 `min-width`，功能列用固定 `width` |
| 操作列 | `fixed="right"`，宽度按按钮数量预留 |
| 空状态 | `v-if="!list.length && !loading"` — 图标 + 文案 |
| 分页 | 数据量大时用 `<el-pagination>`，数据少时用刷新按钮 |
| 弹窗 | `<el-dialog>` + `top="10vh"` |
| Script 分区 | data → computed → filters → created → methods |
| 样式 | scoped，不写外层布局 |

---

## 19. 新增代码前校验清单

> **用途**: 提交 PR 前的完整逐项 Checklist。开发中快速查阅用第 17 章速查卡片。

- [ ] `.java` 文件为 UTF-8 without BOM
- [ ] 业务实体继承 `BaseEntity` + `@Data` + `@TableName`；关系表不继承 `BaseEntity`，复合主键
- [ ] 业务实体未重复定义 id/deleted/createBy/updateBy/createTime/updateTime
- [ ] ID 类型为 Long（雪花），非 String/UUID
- [ ] 乐观锁使用 `@Version`，禁止手动写 `version = version + 1`
- [ ] Controller 返回 `R<T>`，Logger 有实际使用
- [ ] Service 写操作加 `@Transactional(rollbackFor = Exception.class)`
- [ ] 使用构造器注入，非 `@Autowired` 字段注入
- [ ] 启动类 `scanBasePackages = "com.helloai"`
- [ ] JDBC URL 指向 PostgreSQL，使用 `timestamptz`
- [ ] 状态值使用枚举类（`SubTaskStatus`、`AgentRole`），不硬编码数字
- [ ] Outbox 与业务操作同一事务
- [ ] MQ 消费者先插入 `execution_record` 再 ACK
- [ ] 定时任务使用 Redis 分布式锁
- [ ] JSONB 字段使用 `@TableField(typeHandler = JacksonTypeHandler.class)`
- [ ] 线程池参数外置到 `application.yml`
- [ ] 日志包含关键业务标识（subTaskId、eventId、agentId）
- [ ] Vue 列表页：根元素 `<el-card>` + header，表格 `<el-table border stripe>`，空状态占位
- [ ] Vue 页面：`data()` / `computed` / `filters` / `created()` / `methods` 分区清晰
- [ ] 状态机转移在 Service 层明确定义 `VALID_TRANSITIONS`，不散落在各方法中
- [ ] 跨 Service 事务调用使用 `@Transactional(rollbackFor = Exception.class)` 统一管理事务边界
- [ ] SKILL.md 等静态资源文件放在 `resources/skills/` 下，不硬编码在 Java 代码中
- [ ] 嵌套资源路径遵循 `/api/{parent}/{parentId}/{child}` 格式
- [ ] 状态操作端点使用 `POST /{id}/{action}` 格式，一个动作对应一个独立方法

---

## 20. 测试规范

### 20.1 测试分层

| 层级 | 类命名 | 包路径 | 工具 |
|------|--------|--------|------|
| 单元测试 | `XxxTest` | `src/test/java/...` | JUnit 5 + Mockito |
| 集成测试 | `XxxIntegrationTest` | `src/test/java/...` | Testcontainers (PG+Redis+RabbitMQ) |
| API 测试 | `postman/` | 项目根目录 | Postman Collection |

### 20.2 单元测试规范

```java
@ExtendWith(MockitoExtension.class)
class SubTaskStateMachineTest {

   @Test
   @DisplayName("PENDING → ASSIGNED 是合法转换")
   void pendingToAssigned_shouldPass() {
      assertDoesNotThrow(() ->
              SubTaskStateMachine.validate(PENDING, ASSIGNED));
   }

   @Test
   @DisplayName("PENDING → DONE 是非法转换，应抛出 BizException")
   void pendingToDone_shouldThrow() {
      BizException ex = assertThrows(BizException.class, () ->
              SubTaskStateMachine.validate(PENDING, DONE));
      assertEquals("非法状态转换: PENDING -> DONE", ex.getMessage());
   }
}
```

| 规则 | 说明 |
|------|------|
| 测试类命名 | `被测类名 + Test` |
| 测试方法命名 | `方法名_场景_预期结果`（英文下划线） |
| `@DisplayName` | 写中文描述，清晰表达测试意图 |
| Mock | 使用 `@Mock` + `@InjectMocks`，不手动 new 对象 |
| 断言 | 优先使用 `assertThrows` / `assertDoesNotThrow`，再用 `assertEquals` |

### 20.3 集成测试规范

```java
@SpringBootTest
@Testcontainers
class NotificationConsumerIntegrationTest extends BaseIntegrationTest {
   // 继承 BaseIntegrationTest（已定义 PG + Redis + RabbitMQ 容器）
   // 测试真实 MQ 消费 → inbox 投递 → Agent 查询的完整链路
}
```

### 20.4 强制覆盖要求

| 模块 | 行覆盖率目标 | 说明 |
|------|:----------:|------|
| `helloai-core/statemachine` | **100%** | 状态机所有转换路径必须覆盖 |
| `helloai-core/service/score` | **100%** | 评分计算器所有档位必须覆盖 |
| `helloai-mq/consumer` | ≥ 70% | MQ 消费逻辑覆盖 ACK/补偿/幂等 |
| `helloai-job/task` | ≥ 50% | 补偿任务覆盖超时/正常路径 |
| `helloai-core/service` | ≥ 60% | 业务逻辑覆盖主流程 |

### 20.5 关键测试场景

```
☐ 状态机: 所有 9 种状态的合法/非法转换全覆盖
☐ Outbox: 事件创建 → MQ 发送成功 → 状态更新
☐ Outbox: 事件创建 → MQ 发送失败 → 补偿重试
☐ Inbox: 同一 (event_id, agent_id) 投递两次，第二次被去重
☐ 评分: 5 个档位（S/A/B/C/D）的分数计算正确
☐ ACK 丢失: kill -9 模拟 → ExecutionCompensationTask 30s 内补偿
☐ 认证: 错误 API Key → 401，错误 Registration Token → 403
☐ 暂停/恢复: PAUSED 后恢复，Agent 能获取完整上下文
```
