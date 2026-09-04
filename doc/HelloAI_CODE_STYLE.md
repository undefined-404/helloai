# HelloAI 代码开发规范

> 适用项目：HelloAI（AI Agent 协作调度平台）  
> 生效范围：后端单体服务 + 前端管理后台  
> 当前架构：Spring Boot 单体 + DDD 业务域 + RabbitMQ + PostgreSQL + Redis + Vue 3  
> 文档定位：**当前有效的代码与架构工程规范**  
> 版本：V2.0  
> 最后更新：2026-09-01
>
> **重要：本文件只描述“当前有效规则”，不记录历史迭代过程。**
>
> 历史变更请记录在：
>
> `log/HelloAI 迭代执行记录.md`

---

# 0. 使用方式

## 0.1 本文件解决什么问题

本规范用于约束：

1. 新增代码应该放在哪里；
2. 不同业务域之间如何依赖；
3. Controller / Service / Mapper 如何分工；
4. 数据库、事务、MQ、Outbox、分布式锁如何使用；
5. Agent / Planner / Task / Review 如何保持职责边界；
6. AI Coding Agent 修改代码时必须遵守什么原则；
7. 哪些问题属于架构红线，哪些只是代码风格建议。

---

## 0.2 修改代码前的阅读顺序

涉及已有代码修改时，推荐：

```text
1. 当前任务 / 用户需求
        ↓
2. 本文件
        ↓
3. 当前代码实现
        ↓
4. 相关调用方 / 接口 / 配置 / 数据库
        ↓
5. 相关设计文档
        ↓
6. 实施修改
        ↓
7. 编译 / 测试 / 校验脚本
```

如果文档与代码存在冲突：

```text
当前代码事实
    +
实际运行行为
    +
数据库结构
    ↓
优先判断真实情况
    ↓
再修正文档
```

**禁止仅依据旧文档猜测代码结构。**

---

# 1. 规则优先级

当多个规范发生冲突时，按以下优先级处理：

| 等级 | 类型 | 说明 |
|---|---|---|
| P0 | 安全 / 数据完整性 / 架构红线 | 必须遵守 |
| P1 | 并发 / 事务 / MQ / 状态机 / 跨域边界 | 原则上必须遵守 |
| P2 | 项目统一开发规范 | 新代码必须遵守 |
| P3 | 代码风格 / 可读性建议 | 推荐遵守 |

例如：

```text
P0 架构安全要求
    >
P1 数据一致性要求
    >
P2 项目编码规范
    >
P3 代码风格
```

如果为了满足 P3 而破坏 P0/P1，必须优先保证 P0/P1。

---

# 2. 总体原则

## 2.1 核心原则

HelloAI 遵循以下原则：

```text
简单优先
明确边界
最小改动
复用优先
避免重复抽象
数据一致性优先
可测试
可观测
AI 可理解
```

---

## 2.2 禁止无意义重构

一个功能需求只允许修改实现该需求所必需的代码。

禁止：

```text
修改一个 Controller
    ↓
顺便重构整个 Service
    ↓
顺便修改 Entity
    ↓
顺便调整整个包结构
```

除非现有结构已经直接阻碍需求实现。

---

## 2.3 新增抽象前必须回答

新增：

- Service
- Manager
- Helper
- Handler
- Strategy
- Adapter
- Port
- Factory
- Registry

之前，必须先确认：

```text
现有代码是否已经存在相同能力？
        ↓
能否直接复用？
        ↓
能否扩展已有抽象？
        ↓
只有确实无法复用时才新增。
```

**禁止为了“看起来更符合设计模式”而新增抽象。**

---

# 3. 技术栈

当前主线技术以仓库实际 `pom.xml` / 配置为准。

| 组件 | 当前约束 |
|---|---|
| Java | 17 |
| Spring Boot | 3.x |
| Spring AI | 以父 POM 当前版本为准 |
| MyBatis-Plus | 3.x |
| PostgreSQL | 16 |
| Redis | 7.x |
| RabbitMQ | 3.x |
| Flyway | 10.x |
| Vue | Vue 3 |
| UI | Element Plus |

依赖版本：

```text
统一由父 POM dependencyManagement 管理。
```

子模块：

```text
禁止自行指定已有依赖的版本号。
```

如果确需新增版本：

```text
先确认父 POM 是否已经存在统一版本。
```

---

# 4. Maven 模块边界

当前项目保持单体架构，不因为业务增长提前拆微服务。

```text
helloai/
├── helloai-common
├── helloai-mq
├── helloai-job
├── helloai-core
├── helloai-api
└── helloai-start
```

## 4.1 helloai-common

职责：

```text
基础实体
统一返回
业务异常
公共枚举
基础工具
公共常量
```

允许：

```text
被其他模块依赖
```

禁止：

```text
依赖 core / api / job
```

---

## 4.2 helloai-mq

职责：

```text
RabbitMQ 配置
消息发布
消费者基础设施
幂等消费基础能力
```

原则：

```text
MQ 基础设施属于 mq 模块。
业务消费者属于对应业务域。
```

---

## 4.3 helloai-job

职责：

```text
定时任务
补偿任务
超时巡检
健康检查
周期性后台任务
```

业务逻辑：

```text
尽量调用 core Service。
```

禁止：

```text
在 job 中直接操作业务 Mapper。
```

---

## 4.4 helloai-core

核心业务域：

```text
agent
task
planner
review
system
shared
```

---

## 4.5 helloai-api

职责：

```text
Controller
请求 DTO
响应 DTO
认证
授权
Web 拦截器
全局异常处理
```

Controller：

```text
禁止承载复杂业务逻辑。
```

---

## 4.6 helloai-start

职责：

```text
Spring Boot 启动入口
application.yml
Flyway
启动配置
基础设施装配
```

---

# 5. Core 业务域结构

当前 core 采用：

> **业务域分包 + 域内技术分层**

禁止重新建立：

```text
core/entity
core/mapper
core/service
```

这种全局平铺结构。

---

## 5.1 agent 域

职责：

```text
Agent 注册
Agent 生命周期
Agent 调度
Agent 执行
Agent Chat
Agent MCP
Agent 凭据
Agent Skill
Agent 可观测
Agent Execution
```

典型结构：

```text
agent/
├── entity
├── mapper
├── service
├── service.impl
├── domain
├── chat
├── command
├── dispatcher
├── executor
├── mqconsumer
├── mcp
├── observability
└── output
```

---

## 5.2 task 域

职责：

```text
Task
SubTask
状态机
评分
时间线
任务策略
任务事件
任务执行生命周期
```

典型结构：

```text
task/
├── entity
├── mapper
├── service
├── service.impl
├── policy
├── spec
├── statemachine
├── score
└── listener
```

---

## 5.3 planner 域

职责：

```text
需求理解
需求澄清
任务规划
自动拆解
搜索
Planner Agent 选择
Prompt 输入增强
```

典型结构：

```text
planner/
├── entity
├── mapper
├── service
├── service.impl
├── picker
├── search
└── prompt
```

其中：

```text
prompt
```

用于 Planner 相关的 Prompt 能力。

例如：

```text
PromptEnhancer
```

只负责：

```text
用户当前输入
    ↓
LLM
    ↓
优化后的输入
```

禁止：

```text
PromptEnhancer
    ↓
MCP
```

或：

```text
PromptEnhancer
    ↓
数据库查询
```

或：

```text
PromptEnhancer
    ↓
任务执行
```

Prompt Enhancement 是 Planner 的**辅助能力**，不是新的 Planner 状态。

---

## 5.4 review 域

职责：

```text
评审
核验
ReviewRecord
ReviewRecheckLog
自动审查
双审
返工
质量统计
Review MQ Consumer
```

评审产生的业务产物归 review 域。

---

## 5.5 system 域

职责：

```text
用户
系统配置
规则
凭据
附件
存储
LLM Provider
系统级能力
```

system 是平台支撑域。

禁止将具体业务流程塞入 system。

---

## 5.6 shared 域

职责：

```text
跨域基础设施
Domain Event
Doorbell
公共 Handler
真正跨域复用的 Util
```

禁止：

```text
把业务 Service 放进 shared。
```

禁止：

```text
为了绕过跨域依赖，
把任意业务对象放进 shared。
```

---

# 6. Core 域依赖方向

当前核心依赖方向：

```text
planner
   ↓
review
   ↓
task
   ↓
agent
   ↓
system
   ↓
shared
```

更准确地说：

```text
planner → task
review  → task
task    → agent
agent   → system
*       → shared
```

并且：

```text
禁止反向依赖。
```

例如：

```text
task → planner      ❌
task → review       ❌
agent → task        ❌
system → agent      ❌
system → task       ❌
```

### 6.1 补充说明（2026-09-04，agent → task 存量标债）

`agent → task` 反向依赖为【已知技术债】：现存约 66 处 import（service 36 / entity 25 /
port·spec 5；盘点口径：helloai-core agent 域主代码 import task 域，2026-09-04）。

约束：

```text
禁止新增；
新增 agent → task 依赖必须在 code review 显式豁免并记录理由。
```

回收方向：随 AgentRuntime 改造逐步回收（Port 反转 / 职责上移）。本注记仅标记债务与
禁增边界，不构成对既有先例的合规背书——§7.1「跨域走 Service 不直捅 Mapper」只约束
依赖方式，不豁免本节的依赖方向。

---

# 7. 跨域依赖

## 7.1 禁止跨域直捅 Mapper

例如：

```java
// ❌
private final AgentMapper agentMapper;
```

如果代码属于 task：

```text
task → agent.mapper
```

禁止。

正确方式：

```text
task
 ↓
AgentService
```

或者：

```text
task
 ↓
TaskDispatchPort
 ↓
agent 实现
```

---

## 7.2 Port 反转

当：

```text
A 需要 B 的能力
```

但直接依赖会造成：

```text
A → B
B → A
```

优先使用：

```text
A
 ↓
A 自己定义 Port
 ↓
B 实现 Port
```

例如：

```text
task.port.ReviewPort
        ↑
        │
review.ReviewPortAdapter
```

原则：

> **Port 应该定义在能力需求方，而不是能力提供方。**

---

## 7.3 Adapter

Adapter 用于：

```text
跨域接口适配
外部协议适配
旧接口兼容
第三方能力适配
```

禁止为了简单调用新增 Adapter。

只有存在：

```text
协议差异
依赖方向问题
职责隔离
兼容要求
```

时才使用。

---

# 8. Service 规范

## 8.1 当前 Service 结构

当前项目业务 Service 统一采用：

```text
{domain}.service
    XxxService

{domain}.service.impl
    XxxServiceImpl
```

Controller 和跨域调用：

```text
只依赖 XxxService
```

不得依赖：

```text
XxxServiceImpl
```

---

## 8.2 Service 职责

Service 负责：

```text
业务规则
事务边界
数据查询
业务状态变化
跨 Service 编排
一致性控制
```

Service 不负责：

```text
HTTP 协议细节
前端页面逻辑
MQ 基础设施配置
复杂 UI 数据格式
```

---

## 8.3 ServiceImpl 不要变成“万能类”

如果一个 Service 同时包含：

```text
解析
LLM 调用
数据库编排
协议构造
状态机
MQ
文件处理
统计
```

应评估拆分。

拆分优先按照：

```text
业务职责
```

而不是：

```text
方法数量
```

---

# 9. 类规模治理

类规模不是绝对规则，而是复杂度预警。

建议：

| 规模 | 处理方式 |
|---|---|
| < 400 行 | 正常 |
| 400~600 行 | 关注 |
| 600~800 行 | 新增功能前评估职责 |
| > 800 行 | 原则上不得继续无脑堆功能 |
| > 1000 行 | 必须进行拆分评估 |

同时关注：

```text
构造器依赖数量
方法数量
职责数量
分支复杂度
外部依赖数量
```

---

## 9.1 什么时候应该拆

满足以下任意情况时优先考虑拆分：

```text
一个类存在明显的两个以上业务职责
```

例如：

```text
RequirementClarifyService
    ├── 意图识别
    ├── Web Search 编排
    ├── Reply Parser
    └── Confirm Card Protocol
```

这种情况适合按职责拆分。

---

## 9.2 什么时候不要拆

以下情况不要为了“行数”机械拆：

```text
一个强内聚状态机
一个简单 CRUD Service
一个非常稳定的领域对象
一个生命周期完整的小型业务闭环
```

不要出现：

```text
FooService
FooManager
FooHelper
FooUtil
FooProcessor
FooHandler
```

每个只有十几个方法、相互转发的情况。

---

# 10. Controller 规范

Controller 只负责：

```text
1. 接收请求
2. 参数校验 / 转换
3. 调用 Service
4. 返回结果
```

禁止：

```text
Controller → Mapper
Controller → QueryWrapper
Controller → SQL
Controller → @Transactional
Controller → 复杂业务判断
Controller → MQ 业务编排
```

---

## 10.1 返回值

统一：

```java
R<T>
```

例如：

```java
return R.ok(data);
```

失败：

```java
return R.fail("message");
```

业务异常：

```java
throw new BizException("message");
```

由：

```text
GlobalExceptionHandler
```

统一处理。

---

# 11. DTO / Entity

## 11.1 Entity

业务实体通常：

```java
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("xxx")
public class Xxx extends BaseEntity {
}
```

Entity 只描述数据库持久化模型。

---

## 11.2 禁止 Entity 重复定义 BaseEntity 字段

如果 BaseEntity 已包含：

```text
id
deleted
createBy
updateBy
createTime
updateTime
remark
```

业务 Entity 不得重复定义。

---

## 11.3 DTO

API 层：

```text
Request DTO
Response DTO
```

放在：

```text
helloai-api
```

Controller：

```text
DTO → Service
Service → DTO / VO
```

原则：

> API 层不要直接暴露数据库 Entity。

---

# 12. ID 与状态

## 12.1 ID

业务主键：

```text
Long
ASSIGN_ID
```

禁止新业务主键使用：

```text
String UUID
```

除非存在明确的外部协议需求。

---

## 12.2 状态

禁止：

```java
if (status == 3)
```

使用：

```java
SubTaskStatus.REVIEW
```

状态机必须明确合法状态转换。

---

# 13. 状态机

复杂业务状态必须集中定义状态流转。

例如：

```text
PENDING
  ↓
ASSIGNED
  ↓
IN_PROGRESS
  ↓
REVIEW
  ├── DONE
  └── REWORK
```

禁止：

```text
在多个 Service 方法中散落状态转移规则。
```

对外状态操作：

```text
claim
start
submit
complete
rework
block
```

等方法必须：

```text
先校验合法状态
再修改状态
```

---

# 14. 事务规范

## 14.1 默认规则

业务写操作默认：

```java
@Transactional(rollbackFor = Exception.class)
```

事务边界优先放在：

```text
Service
```

---

## 14.2 哪些操作必须考虑事务

以下场景必须使用事务：

```text
多表写入
状态变化 + 业务记录
业务写入 + Outbox
多实体一致性更新
跨 Service 的同一业务事务
```

---

## 14.3 单语句写操作

如果是：

```text
单实体
单条 UPDATE / DELETE
不存在跨实体一致性要求
```

可以根据实际情况不增加事务。

但必须确保：

```text
不存在依赖当前事务边界的后续操作。
```

如果该方法要求：

```text
必须在调用方事务内执行
```

必须在接口 / Javadoc 明确说明。

---

## 14.4 不允许滥用事务

不要：

```text
@Transactional
public void querySomething() {
}
```

只读查询默认不增加事务。

---

# 15. 乐观锁

使用：

```java
@Version
```

更新优先：

```java
updateById(entity)
```

禁止手动：

```sql
version = version + 1
```

禁止绕过 MyBatis-Plus 乐观锁机制。

---

# 16. 数据库规范

数据库：

```text
PostgreSQL
```

时间字段优先：

```text
timestamptz
```

Java：

```text
OffsetDateTime
```

---

## 16.1 Flyway

数据库结构变更必须使用：

```text
Flyway migration
```

禁止直接修改已经执行过的历史 migration。

正确方式：

```text
V23__xxx.sql
V24__new_change.sql
```

版本号必须递增。

---

## 16.2 数据库命名

推荐：

```text
snake_case
```

例如：

```text
sub_task
execution_record
review_record
```

---

## 16.3 JSONB

JSONB 映射：

```java
@TableField(typeHandler = JacksonTypeHandler.class)
private Map<String, Object> context;
```

或者：

```java
@TableField(typeHandler = JacksonTypeHandler.class)
private XxxContext context;
```

优先使用：

```text
类型安全对象
```

而不是全部使用：

```text
String
```

---

# 17. MyBatis / Mapper

Mapper 只负责：

```text
数据库访问
SQL
持久化映射
```

业务逻辑放：

```text
Service
```

禁止：

```text
Mapper 中编排业务流程
```

禁止：

```text
跨域 Service 直接调用其他域 Mapper
```

---

# 18. 查询规范

查询必须考虑：

```text
数据量
索引
分页
N+1
批量查询
```

尤其禁止：

```java
for (...) {
xxxMapper.select...
        }
```

如果数据量可能较大：

```text
优先批量查询
```

例如：

```text
提取唯一 ID
    ↓
IN 查询
    ↓
Map 分组
    ↓
回填结果
```

---

# 19. N+1 查询

以下模式必须警惕：

```text
查询 100 条主记录
        ↓
循环查询 100 次子表
```

优先：

```text
主表查询
    ↓
提取 ID
    ↓
批量查询子表
    ↓
Map 分组
    ↓
组装
```

如果确实只能逐条查询：

```text
必须有明确理由。
```

---

# 20. Redis

Redis 用于：

```text
缓存
上下文
去重
临时状态
分布式锁底层存储
```

禁止把 Redis 当成：

```text
关系数据库
```

---

## 20.1 Key

统一使用：

```text
helloai:{domain}:{resource}:{id}
```

示例：

```text
helloai:context:subtask:123
```

---

## 20.2 TTL

临时数据必须设置 TTL。

禁止新增：

```text
永不过期临时 Key
```

除非有明确设计。

---

# 21. 分布式锁

当前只允许两类实现：

```text
定时任务单例锁 → ShedLock
业务互斥锁     → Redisson RLock
```

禁止新增：

```text
RedisTemplate.setIfAbsent
```

手写分布式锁。

---

## 21.1 ShedLock

用于：

```text
@Scheduled
```

防止多实例重复执行。

示例：

```java
@Scheduled(fixedRate = 30_000)
@SchedulerLock(
        name = "subTaskTimeout",
        lockAtMostFor = "PT60S"
)
public void scan() {
}
```

规则：

```text
name 使用稳定任务名
lockAtMostFor 必须合理
不要自行实现 token + unlock
```

---

## 21.2 Redisson

用于：

```text
业务级互斥
请求级互斥
动态 key
```

示例：

```java
RLock lock = redissonClient.getLock("review:lock:" + subTaskId);

if (!lock.tryLock(0, 120, TimeUnit.SECONDS)) {
        return;
        }

        try {
        // business
        } finally {
        if (lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
            }
```

原则：

```text
显式 leaseTime
```

禁止无明确理由使用：

```java
lock.lock();
```

---

# 22. 定时任务

定时任务统一放：

```text
helloai-job
```

规则：

```text
@Scheduled
+
ShedLock（除非属于明确豁免任务）
+
幂等
+
可观测日志
```

定时任务不得：

```text
直接操作业务 Mapper
```

应该：

```text
Job
 ↓
Service
```

---

# 23. RabbitMQ

MQ 用于：

```text
异步解耦
跨域事件
Agent 调度
执行结果
补偿
通知
```

---

## 23.1 Producer

消息发送必须考虑：

```text
消息 ID
Publisher Confirm
Mandatory
失败处理
幂等
```

---

## 23.2 Consumer

Consumer 必须考虑：

```text
幂等
ACK
异常
重试
DLX
```

当前消费者统一使用：

```text
AbstractIdempotentConsumer
```

或项目已有等价基础能力。

---

## 23.3 ACK

核心原则：

> **消息不能在业务状态无法恢复之前被 ACK。**

当前执行链必须保持：

```text
持久化必要的执行记录
        ↓
ACK
        ↓
异步执行
        ↓
更新 execution_record
```

禁止：

```text
先 ACK
    ↓
再持久化唯一业务记录
```

否则 JVM 在中间崩溃可能导致：

```text
消息丢失
且无法追踪
```

---

# 24. Outbox

涉及：

```text
数据库业务变更
+
MQ 事件
```

必须优先考虑 Outbox。

正确模式：

```text
业务变更
   +
Outbox Event
   ↓
同一数据库事务
   ↓
提交
   ↓
异步 Relay
   ↓
RabbitMQ
```

---

## 24.1 禁止

禁止：

```text
业务事务提交
    ↓
再直接发送 MQ
```

如果 MQ 发送失败：

```text
数据库已经成功
消息却丢失
```

---

# 25. Outbox 状态

推荐：

```text
PENDING
   ↓
SUCCESS
```

失败：

```text
PENDING
   ↓
FAILED
   ↓
重试
   ↓
人工介入
```

Outbox 补偿任务必须：

```text
幂等
可重复执行
可观测
```

---

# 26. 异常处理

业务异常：

```java
throw new BizException("xxx");
```

统一由：

```text
GlobalExceptionHandler
```

处理。

Controller 不应：

```java
try {
        ...
        } catch (BizException e) {
        }
```

除非存在明确的协议转换需求。

---

# 27. 日志

日志必须包含能够定位业务上下文的字段。

例如：

```text
subTaskId
taskId
agentId
executionId
eventId
traceId
```

推荐：

```java
log.info(
    "任务状态变更: subTaskId={}, from={}, to={}, agentId={}",
    subTaskId,
    oldStatus,
    newStatus,
    agentId
    );
```

禁止只有：

```text
任务完成
发送失败
执行失败
```

这种无法定位上下文的日志。

---

# 28. 日志级别

```text
INFO
正常业务生命周期

WARN
可恢复异常 / 降级 / 重试 / 数据异常

ERROR
真正需要关注的失败
```

禁止：

```text
正常重试
正常 fallback
正常用户输入错误
```

全部使用 ERROR。

---

# 29. TraceId

请求链路使用：

```text
traceId
```

并通过：

```text
MDC
```

进入日志。

异步任务 / MQ / Agent 执行链路必须尽量保持：

```text
traceId
+
业务 ID
```

可追踪。

---

# 30. Agent 架构

Agent 层是 HelloAI 的核心能力之一。

基本链路：

```text
Planner
   ↓
Task / SubTask
   ↓
Agent Dispatcher
   ↓
Agent Executor
   ↓
AI Model / MCP / Tool
   ↓
Execution Record
   ↓
Callback
   ↓
Task State
   ↓
Review
```

---

## 30.1 AgentExecutor

负责：

```text
执行 AgentTask
调用具体 Agent 能力
返回 AgentResult
```

不负责：

```text
Task 状态机
Review 业务逻辑
Planner 决策
```

---

## 30.2 AgentRouter

负责：

```text
根据角色 / 能力选择 Agent
```

不要把：

```text
业务状态判断
```

全部塞进 Router。

---

## 30.3 AgentTask

用于描述：

```text
subTaskId
role
prompt
context
workingDir
```

原则：

> AgentTask 是执行输入，不承担业务状态。

---

## 30.4 AgentResult

用于描述：

```text
output
tokenUsage
errorMsg
finishReason
```

执行结果和业务状态更新分离。

---

# 31. Planner

Planner 负责：

```text
需求理解
需求澄清
任务规划
任务拆解
Agent 选择
搜索
```

Planner 不负责：

```text
真正执行代码
直接修改任务执行结果
Review
```

---

# 32. Planner Chat

Planner Chat 的职责：

```text
用户
 ↓
对话
 ↓
需求理解
 ↓
必要时澄清
 ↓
形成可执行需求
```

如果增加输入优化能力：

```text
用户当前输入
      ↓
PromptEnhancer
      ↓
优化结果
      ↓
用户确认
      ↓
Planner Chat
```

---

## 32.1 PromptEnhancer

PromptEnhancer 只做：

> **当前用户输入的语义增强和结构化表达。**

输入：

```text
当前用户输入
```

输出：

```text
优化后的用户输入
```

第一阶段：

```text
不读取完整 Conversation
不调用 MCP
不查询数据库
不执行代码
不执行 Planner
不创建 Task
```

---

## 32.2 PromptEnhancer 的语义保护

优化过程中：

```text
不能改变用户明确表达的业务含义。
```

禁止：

```text
用户：最低底价

优化后：
qualityStatus
```

禁止擅自创造：

```text
数据库表
字段
接口
路径
业务规则
```

允许：

```text
结构化
补充表达
整理业务逻辑
整理边界条件
提出待确认事项
```

---

# 33. Reviewer

Reviewer 负责：

```text
结果核验
代码审查
质量判断
返工建议
Review Record
```

Reviewer 不负责：

```text
Planner 任务拆解
Agent 执行
用户输入优化
```

---

# 34. MCP

MCP 是：

```text
外部工具 / 外部能力接入协议
```

MCP 的使用必须由业务 Agent / Planner / Executor 根据任务需要决定。

禁止：

```text
PromptEnhancer
    ↓
直接执行 MCP
```

Prompt 中可以表达：

```text
可以使用 postgres_oa MCP 查询数据库校验。
```

但：

```text
“描述需要使用工具”
```

与：

```text
“实际执行工具”
```

必须严格区分。

---

# 35. Skill

Skill 用于：

```text
角色能力说明
Prompt 约束
领域知识
操作规范
```

Skill 不等同于：

```text
Java Service
```

也不应该把大量业务逻辑直接写入 Skill。

如果逻辑需要：

```text
事务
数据库
状态机
权限
一致性
```

应该落到代码中。

---

# 36. LLM Provider

LLM Provider 属于：

```text
system
```

负责：

```text
模型配置
Provider 信息
模型信息
平台级 LLM 配置
```

具体 Agent 使用什么模型：

```text
由 Agent / Router / 配置策略决定
```

禁止在业务代码中大量硬编码：

```text
model = "xxx"
```

---

# 37. LLM 调用规范

LLM 调用必须考虑：

```text
timeout
异常
重试
token
模型
traceId
上下文
```

AI 调用失败不能导致：

```text
业务线程永久阻塞
```

必须有：

```text
合理 timeout
```

---

# 38. Prompt 规范

Prompt 分为：

```text
System Prompt
User Prompt
Context
Tool Description
```

原则：

```text
职责单一
变量明确
避免隐式上下文
不要把业务规则散落在 Java 字符串中
```

复杂 Prompt 应优先：

```text
独立资源文件
```

或：

```text
独立 Prompt Template
```

而不是在 Service 中拼接几百行字符串。

---

# 39. Resource 规范

资源文件：

```text
helloai-core/src/main/resources
```

典型：

```text
mapper/
scripts/
skills/
prompt/
```

读取资源：

```text
ClassPathResource
```

禁止：

```text
硬编码本机绝对路径
```

例如：

```text
E:\workspace\...
/Users/xxx/...
```

---

# 40. 配置规范

配置类：

```java
@ConfigurationProperties
```

命名：

```text
XxxProperties
```

例如：

```text
AgentProviderProperties
MossThreadPoolProperties
ReviewProperties
```

配置前缀：

```text
小写 + 点号
```

例如：

```yaml
helloai:
   agent:
      ...
```

---

# 41. 配置默认值

新增配置：

```text
尽量提供合理默认值。
```

但以下情况除外：

```text
缺失配置必须阻止应用启动
```

例如：

```text
生产环境必须配置的 Secret
```

不得使用：

```text
fake-secret
123456
```

作为默认生产凭据。

---

# 42. 安全规范

禁止：

```text
密码写入代码
API Key 写入代码
Token 写入代码
数据库密码提交 Git
```

敏感配置：

```text
application-local.yml
环境变量
Secret
```

具体采用项目现有配置体系。

---

# 43. 权限规范

认证与授权分离：

```text
Authentication
    ↓
Authorization
```

`/api/admin/**`：

```text
必须经过 Admin 授权。
```

禁止仅因为：

```text
用户已经登录
```

就允许：

```text
Admin API
```

---

# 44. 前端 API 路径

前端 API 路径统一通过：

```text
helloai-ui/src/api/paths.ts
```

维护。

禁止在多个页面中散落：

```javascript
request('/api/xxx')
```

新增接口：

```text
先增加 paths.ts
再由 API 模块引用。
```

路径参数统一：

```text
encodeURIComponent
```

避免重复编码。

---

# 45. 前端 Vue

项目使用：

```text
Vue 3
Element Plus
```

页面原则：

```text
结构清晰
状态明确
API 调用集中
避免页面直接承担复杂业务逻辑
```

---

## 45.1 页面职责

Vue 页面负责：

```text
展示
交互
表单
调用 API
页面状态
```

不应该：

```text
在页面中复制大量业务规则。
```

---

## 45.2 API

建议：

```text
页面
 ↓
api/*.ts
 ↓
HTTP
```

不要：

```text
多个页面重复写请求 URL
```

---

# 46. 前端复杂度治理

当一个 Vue 页面同时包含：

```text
大量 API
大量状态
大量弹窗
大量计算逻辑
大量业务规则
```

应考虑拆分：

```text
components/
composables/
api/
utils/
```

但同样：

> 不为了文件数量而机械拆分。

---

# 47. 测试

新增重要业务逻辑必须考虑测试。

优先测试：

```text
状态机
权限
事务边界
幂等
MQ
Outbox
Prompt Parser
核心业务规则
```

---

## 47.1 单元测试

适合：

```text
纯业务逻辑
状态转换
Parser
Calculator
Policy
```

---

## 47.2 集成测试

适合：

```text
数据库
Redis
MQ
Spring Bean
事务
Mapper
```

---

# 48. 验证方式

代码修改后至少根据影响范围执行：

```text
编译
单元测试
相关集成测试
依赖方向检查
前端检查
启动检查
```

后端基本验证：

```bash
mvn -pl helloai-start -am compile
```

如果修改 MQ / 数据库 / Spring 配置：

```text
必须增加对应启动或集成验证。
```

---

# 49. 自动化校验

能通过脚本检查的规则：

> **优先脚本化，而不是继续增加 Markdown 文字。**

例如：

```text
verify-dependency-direction.ps1
verify-admin-authz.ps1
verify-code-style-p1-ui-sync.ps1
```

自动化检查优先覆盖：

```text
跨域依赖
权限
前端 API 路径
Mapper 登记
禁止模式
```

---

# 50. AI Coding Agent 开发协议

HelloAI 经常使用：

```text
Qoder
Trae
Cursor
Claude Code
Codex
```

等 AI Coding Agent 进行开发。

因此 AI 修改代码必须遵守以下协议。

---

## 50.1 修改前

必须先：

```text
1. 找到真实代码
2. 找到调用方
3. 找到接口
4. 找到实现
5. 找到配置
6. 找到数据库 / MQ / MCP 等相关依赖
```

禁止：

```text
仅凭类名猜测代码结构。
```

---

## 50.2 先搜索，再修改

修改一个类之前：

```text
搜索类
    ↓
搜索接口
    ↓
搜索调用方
    ↓
搜索相关字段
    ↓
搜索配置
    ↓
确认影响范围
```

---

## 50.3 最小修改原则

AI 不得因为一个小需求：

```text
重构整个模块
```

除非：

```text
现有结构已经阻碍需求实现
```

---

## 50.4 优先复用

新增代码前必须搜索：

```text
是否已有相同 Service
是否已有 Mapper
是否已有工具类
是否已有 DTO
是否已有状态枚举
是否已有异常
是否已有配置
是否已有 Prompt
是否已有 MCP
```

禁止：

```text
同能力创建第二套实现。
```

---

## 50.5 禁止猜测

AI 不得擅自创造：

```text
数据库字段
数据库表
接口
文件路径
业务规则
状态
枚举值
MCP 工具
```

如果无法确认：

```text
保留不确定性
```

或者：

```text
向用户确认。
```

---

## 50.6 文档与代码冲突

如果：

```text
CODE_STYLE
    ≠
实际代码
```

AI 必须：

```text
先确认当前代码事实
```

然后：

```text
如果代码正确：
更新文档

如果代码错误：
修代码

如果无法判断：
暂停并询问。
```

禁止：

```text
为了满足旧文档
强行把当前代码改回旧实现。
```

---

## 50.7 不新增平行架构

禁止出现：

```text
OldService
NewService
V2Service
EnhancedService
```

仅仅为了绕开旧代码。

如果确实需要新架构：

```text
明确迁移策略
```

并最终：

```text
删除旧路径。
```

---

# 51. AI 修改复杂类的规则

如果目标类：

```text
> 800 行
```

AI 在继续增加大量逻辑之前，必须先判断：

```text
是否已经存在职责混杂？
```

但：

```text
本次需求 ≠ 自动触发全类重构
```

正确方式：

```text
先完成需求
+
必要时抽取本需求涉及的独立职责
```

不要：

```text
借需求之名
重写整个 Service。
```

---

# 52. AI 修改数据库的规则

涉及数据库：

```text
先查看 Entity
再查看 Mapper
再查看现有 SQL
再查看 migration
再查看调用方
```

新增字段必须考虑：

```text
数据库
Entity
DTO
Mapper
查询
写入
前端
兼容旧数据
```

禁止：

```text
只修改 Entity
不修改数据库。
```

---

# 53. AI 修改 MQ 的规则

涉及 MQ：

```text
先查看 Producer
再查看 Exchange
再查看 Queue
再查看 RoutingKey
再查看 Consumer
再查看 ACK
再查看幂等
再查看 DLX
```

禁止只修改其中一个环节。

---

# 54. AI 修改 MCP 的规则

涉及 MCP：

```text
先确认 Tool 定义
再确认参数
再确认调用方
再确认异常处理
再确认超时
```

禁止：

```text
仅凭 Tool 名称猜参数。
```

---

# 55. AI 修改 Prompt 的规则

Prompt 修改属于：

```text
业务行为修改
```

不能简单视为：

```text
字符串修改。
```

修改 Prompt 后必须检查：

```text
输入
输出
变量
上下文
Tool
JSON 格式
边界情况
```

Prompt 如果承担核心业务规则：

```text
必须有对应测试样例。
```

---

# 56. Prompt Enhancement 测试原则

PromptEnhancer 至少测试：

```text
简单需求
模糊需求
接口修改
数据库修改
Bug 修复
性能优化
MCP 请求
多条件业务规则
```

重点检查：

```text
语义是否保持
字段是否保持
数字是否保持
接口是否保持
工具名称是否保持
约束是否保持
```

禁止：

```text
用户说 A
AI 自己变成 B。
```

---

# 57. Git 修改原则

提交前检查：

```text
是否存在无关文件修改
是否存在调试代码
是否存在临时日志
是否存在本地绝对路径
是否存在密钥
是否存在无关格式化
```

禁止：

```text
一次功能提交顺便修改几十个无关文件。
```

---

# 58. 重构原则

HelloAI 已经经历多轮重构。

后续重构必须遵循：

```text
先发现问题
    ↓
证明问题真实存在
    ↓
明确收益
    ↓
最小范围重构
    ↓
验证
    ↓
删除旧代码
```

禁止：

```text
因为“感觉不够优雅”
```

就进行大规模架构重构。

---

# 59. 重构触发条件

以下情况可以触发重构：

```text
明显循环依赖
跨域 Mapper 直连
同一能力出现多套实现
核心 Service 持续膨胀
大量重复代码
状态机散落
事务边界错误
MQ 一致性问题
安全问题
性能问题
AI 修改错误率明显升高
```

---

# 60. 不触发重构的情况

以下通常不值得单独重构：

```text
命名略有不同
一个类多几十行
某个方法还能更优雅
某个工具类还能再抽象
某个 DTO 可以换成另一种写法
```

除非：

```text
已经形成实际维护成本。
```

---

# 61. 代码质量目标

HelloAI 的目标不是：

> “代码看起来像教科书。”

而是：

> **代码结构能够支撑持续快速迭代，并且让人和 AI 都能安全修改。**

核心指标：

```text
边界清晰
依赖单向
职责明确
修改可控
失败可追踪
数据可恢复
AI 可理解
```

---

# 62. 新功能开发标准流程

新增功能推荐：

```text
需求
 ↓
确认业务边界
 ↓
搜索已有实现
 ↓
确定所属业务域
 ↓
确定调用链
 ↓
确定数据变化
 ↓
确定事务 / MQ / MCP
 ↓
实现
 ↓
测试
 ↓
自动化校验
 ↓
更新必要文档
```

---

# 63. 新增文件前检查

新增类前问：

```text
这个类是否真的需要？
```

如果答案是：

```text
“只是为了符合某种设计模式”
```

则不要新增。

如果答案是：

```text
“已有类职责已经明显不同”
```

才新增。

---

# 64. 最终提交前 Checklist

## 架构

- [ ] 所属业务域正确
- [ ] 没有跨域直捅 Mapper
- [ ] 没有新增循环依赖
- [ ] 没有创建平行架构
- [ ] 没有无意义新增抽象

## Java

- [ ] 构造器注入
- [ ] Entity 没有重复 BaseEntity 字段
- [ ] 状态使用枚举
- [ ] Service 事务边界正确
- [ ] 异常统一处理
- [ ] 日志包含业务标识

## 数据库

- [ ] Migration 已增加
- [ ] Entity 已同步
- [ ] Mapper 已同步
- [ ] 索引已考虑
- [ ] 没有明显 N+1

## MQ

- [ ] Producer / Consumer 链路完整
- [ ] 消息幂等
- [ ] ACK 顺序正确
- [ ] DLX 正确
- [ ] Outbox 一致性正确

## Redis

- [ ] Key 命名统一
- [ ] 临时数据有 TTL
- [ ] 锁使用正确实现
- [ ] 没有新增手写 setIfAbsent 锁

## Agent / Planner

- [ ] Planner 不承担 Executor 职责
- [ ] Executor 不承担 Planner 职责
- [ ] Reviewer 不承担执行职责
- [ ] MCP 使用边界正确
- [ ] PromptEnhancer 不执行工具

## 前端

- [ ] API 路径使用 paths.ts
- [ ] 页面没有重复 URL
- [ ] 页面没有大量业务逻辑
- [ ] 修改范围可控

## AI Coding Agent

- [ ] 修改前已搜索真实代码
- [ ] 已搜索调用方
- [ ] 已搜索配置
- [ ] 没有根据猜测创建字段 / 接口
- [ ] 没有进行无关重构
- [ ] 没有新增重复能力
- [ ] 已执行必要测试 / 编译 / 校验

---

# 65. 一句话原则

如果只能记住这份规范的十句话：

```text
1. 先看代码，再写代码。

2. 先找已有能力，再新增能力。

3. 一个需求尽量只改一个闭环。

4. 业务域边界优先于类结构。

5. 跨域不要直捅 Mapper。

6. 一致性问题优先考虑事务、Outbox、幂等。

7. 定时任务用 ShedLock，业务互斥用 Redisson。

8. 不要为了设计模式而增加抽象。

9. AI 不允许猜测业务事实。

10. 让代码既适合人维护，也适合 AI 安全修改。
```

---

# 66. 规范维护原则

本文件只保留：

```text
当前有效规则
```

不记录：

```text
历史版本
重构过程
某次修复过程
某个具体 Bug
已经废弃的实现
```

这些内容分别进入：

```text
log/
doc/design/
HelloAI 项目基线文档.md
HelloAI 实现差距表.md
```

当代码事实发生长期变化时：

```text
先修改代码
    ↓
验证
    ↓
更新本规范
```

保证：

> **CODE_STYLE 永远描述当前有效工程规则，而不是项目历史。**