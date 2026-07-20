# HelloAI 执行链路架构分析

> 编写日期：2026-07-10
> 目的：为 blocked 场景静默卡死问题提供完整的代码级链路图，帮助从全局视角寻找破局角度

---

## 一、总体架构：三层调度模型

```
┌─────────────────────────────────────────────────────────────────┐
│                        调度层 (Dispatch)                         │
│  ResilientDispatcher → AgentSelector → SubTaskService.assignNext │
│  负责：选人、熔断、fallback、写入 ASSIGNED 状态                    │
└──────────────────────────┬──────────────────────────────────────┘
                           │ SubTaskAssignedEvent (AFTER_COMMIT)
┌──────────────────────────▼──────────────────────────────────────┐
│                       自动执行层 (Auto-Execute)                   │
│  SubTaskAutoExecutionDispatcher → SubTaskExecutionService        │
│  负责：@Async 监听、启动任务、超时包裹、结果/错误回写              │
└──────────────────────────┬──────────────────────────────────────┘
                           │ platformAgentExecutionService.execute()
┌──────────────────────────▼──────────────────────────────────────┐
│                      实际调用层 (Invoke)                          │
│  ApiKeyAgentExecutor → CredentialVaultBindingService             │
│                     → AgentChatClientService                     │
│                     → DeepSeekProviderChatClientFactory           │
│  负责：取凭证、拼 Prompt、调 LLM、返回结果                        │
└─────────────────────────────────────────────────────────────────┘
```

---

## 二、核心文件逐一定位与职责

### 2.1 状态与实体层

#### `SubTaskStatus` — `helloai-common/.../constant/SubTaskStatus.java`

子任务生命周期的 9 个状态枚举。理解卡死问题的前提是理解这张状态表：

| 状态 | 含义 | 谁推进 |
|------|------|--------|
| `PENDING` | 待分配 | 调度器 |
| `ASSIGNED` | 已分配，等待执行 | 自动执行监听器 |
| `IN_PROGRESS` | 执行中 | **这是卡死发生的位置** |
| `PAUSED` | 暂停 | 人工 |
| `REVIEW` | 待审查 | 执行成功回写 |
| `DONE` | 完成 | Reviewer |
| `REWORK` | 返工 | Reviewer |
| `BLOCKED` | 阻塞 | 执行失败 / 超时巡检 |
| `CANCELLED` | 取消 | 人工 |

#### `SubTaskStateMachine` — `helloai-core/.../statemachine/SubTaskStateMachine.java`

合法状态转移表。关键转移：
- `BLOCKED → PENDING`：合法（重分配入口）
- `ASSIGNED → IN_PROGRESS`：合法（开始执行）
- `IN_PROGRESS → REVIEW`：合法（执行成功）
- `IN_PROGRESS → BLOCKED`：合法（执行失败）
- `ASSIGNED → PENDING`：**不合法**（但 `resetToPendingForDispatch` 通过直接 set 绕过了校验，用于离线补偿场景）
- `IN_PROGRESS → PENDING`：**不合法**（同上，离线补偿绕过）

**重要发现**：`resetToPendingForDispatch` 方法直接 `subTask.setStatus(PENDING)`，**不走状态机校验**，这意味着状态机的"合法转移"约束在离线重分配路径上被有意绕开了。

#### `SubTask` — `helloai-core/.../entity/SubTask.java`

关键字段：
- `status`：当前状态（SmallInt 映射到 SubTaskStatus 枚举）
- `assignedAgent`：被分配到的 Agent ID
- `context`：JSONB，存储 `lastExecution` 结果
- `version`：`@Version` 乐观锁
- `timeoutCount`：超时计数

#### `Agent` — `helloai-core/.../entity/Agent.java`

关键字段：
- `accessType`：CLI_CLIENT / API_KEY_LLM / WEB_BROWSER
- `apiKey`：consumerToken（工牌），CLI 鉴权用
- `onlineStatus`：ONLINE / OFFLINE / SLEEPING
- `modelType`：模型类型（如 `deepseek`）
- `capabilities`：JSONB 能力画像

#### `AgentAccessType` — `helloai-common/.../constant/AgentAccessType.java`

两个关键判断方法：
- `requiresRuntimeLiveness()`：只有 `CLI_CLIENT` 返回 true。这意味着 `API_KEY_LLM` 不会因为 `OFFLINE` 被调度器拦截
- `usesCredentialVault()`：只有 `API_KEY_LLM` 返回 true。决定执行时是否从 vault 取凭证

---

### 2.2 调度层（Dispatch Layer）

#### `SubTaskController` — `helloai-api/.../controller/SubTaskController.java`

REST 入口，薄层。关键端点：

| 端点 | 方法 | 作用 |
|------|------|------|
| `POST /sub-tasks/reassign/{id}` | `reassign()` | **blocked 场景的主入口** → 调用 `SubTaskDispatchService.dispatchBlockedSubTask()` |
| `POST /sub-tasks/block/{id}` | `block()` | 把子任务标记为 BLOCKED |
| `POST /sub-tasks/execute/{id}` | `execute()` | 手工触发执行（兼容保留） |

#### `SubTaskDispatchService` — `helloai-core/.../service/SubTaskDispatchService.java`

**职责**：把"需要重新分配"的子任务统一编排为"重置 PENDING → 入调度器"。

两个入口方法：

```
dispatchBlockedSubTask(subTaskId, preferredAgentId)
  → resetToPendingForDispatch(subTaskId, {BLOCKED})
  → resilientDispatcher.assignNext(preferredAgentId, subTaskId)

redispatchOfflineSubTask(subTaskId, offlineAgentId)
  → resetToPendingForDispatch(subTaskId, {ASSIGNED, IN_PROGRESS})  ← 绕过状态机！
  → resilientDispatcher.assignNext(offlineAgentId, subTaskId)
```

**这是统一调度入口**。所有重分配都经过这里，再进入 `ResilientDispatcher`。

#### `ResilientDispatcher` — `helloai-core/.../service/ResilientDispatcher.java`

**职责**：带熔断保护的任务分配器。`@CircuitBreaker(name = "agentDispatch")`。

核心方法 `assignNext(agentId, subTaskId)`：
1. 检查 Agent 是否 SLEEPING（抛 `AgentUnavailableException`，不计入熔断）
2. 检查 Agent 是否 OFFLINE 且 `requiresRuntimeLiveness()`（CLI_CLIENT 需要在线，API_KEY_LLM 不拦截）
3. 调用 `subTaskService.assignNext(agentId, subTaskId)` 写入 ASSIGNED

Fallback 方法 `assignNextFallback(agentId, subTaskId, t)`：
1. 获取原 Agent 的 role
2. 调用 `agentSelector.pickAlternative(agentId, role)` 找同角色替代者
3. 用替代者重新 `assignNext()`

#### `AgentSelector` — `helloai-core/.../service/AgentSelector.java`

**职责**：选人策略。`pickAlternative(excludeAgentId, role)` 从同角色、ACTIVE、ONLINE 的 Agent 中选一个。

#### `SubTaskService` — `helloai-core/.../service/SubTaskService.java`

**这是全链路最核心的类**，承担了过多职责。关键方法：

```
changeStatus(subTaskId, newStatus, agentId)
  → 状态机校验
  → 更新 DB（@Version 乐观锁）
  → 写 Outbox 事件
  → 发 AgentInbox 通知
  → ★ 发布 SubTaskAssignedEvent（当 newStatus == ASSIGNED 且有 agent）
  → 心跳 active()（当 IN_PROGRESS/REVIEW）

resetToPendingForDispatch(subTaskId, allowedStatuses)
  → 校验当前状态在 allowedStatuses 内
  → ★ 直接 setStatus(PENDING) + 清空 assignedAgent（绕过状态机）
  → 不发布任何事件
```

**关键设计决策**：`resetToPendingForDispatch` 不发布事件，因为后续的 `ResilientDispatcher.assignNext` → `changeStatus(ASSIGNED)` 会重新走标准流程发布 `SubTaskAssignedEvent`，从而触发自动执行。

---

### 2.3 自动执行层（Auto-Execute Layer）

#### `SubTaskAssignedEvent` — `helloai-core/.../event/SubTaskAssignedEvent.java`

简单的 DTO：`subTaskId` + `agentId`。这是调度层和自动执行层之间的**唯一桥梁**。

#### `SubTaskAutoExecutionDispatcher` — `helloai-core/.../service/SubTaskAutoExecutionDispatcher.java`

**职责**：监听分配事件，对 API_KEY_LLM 类型自动触发执行。

```java
@Async                                    // ← 异步，不阻塞调度事务
@TransactionalEventListener(
    phase = TransactionPhase.AFTER_COMMIT  // ← 等调度事务提交后才触发
)
public void onAssigned(SubTaskAssignedEvent event) {
    // 只处理 API_KEY_LLM，跳过 CLI_CLIENT
    // 记录 sub_task_auto_execute_dispatch 时间线
    // 调用 subTaskExecutionService.executeOnce(subTaskId)
}
```

**这是卡死问题的第一关键节点**：
- `@Async` 使用的线程池是 Spring 默认的 `applicationTaskExecutor`。如果没有显式配置，Spring Boot 默认使用 `SimpleAsyncTaskExecutor`（每任务一个新线程，无上限），或者如果检测到 `TaskExecutor` bean，则使用该 bean。
- `@TransactionalEventListener(phase = AFTER_COMMIT)` 确保只在调度事务成功提交后才触发，避免了"事务回滚但已发送 LLM 请求"的问题。

#### `SubTaskExecutionService` — `helloai-core/.../service/SubTaskExecutionService.java`

**职责**：编排单次执行的生命周期。**这是卡死问题的第二关键节点**。

核心方法 `executeOnce(subTaskId)`：
```
1. startIfNeeded(id, status)
   → 如果 ASSIGNED/REWORK/PAUSED：调用 subTaskService.start(id)
   → subTaskService.start() 内部：状态机校验 → changeStatus(IN_PROGRESS)
   → 记录 sub_task_execute_start 时间线

2. executeWithRescueTimeout(agent, task, timeoutSeconds)
   → 提交到 platformExecuteRescueExecutor 线程池
   → future.get(timeout, TimeUnit.SECONDS) 等待
   → 超时：cancel(true) + 抛 BizException
   → 成功：返回 AgentResult

3. 成功路径：
   → 保存结果到 subTask.context.lastExecution
   → subTaskService.submit(subTaskId) → 状态变为 REVIEW
   → 记录 sub_task_execute_submit 时间线

4. 失败路径：
   → saveExecutionError(id, agentId, e)
   → 如果子任务仍是 IN_PROGRESS：subTaskService.block(subTaskId)
   → 记录 sub_task_execute_failed 时间线
```

**救援式超时机制**：
```java
CompletableFuture<AgentResult> future = CompletableFuture.supplyAsync(
    () -> platformAgentExecutionService.execute(agent, task).join(),
    platformExecuteRescueExecutor    // ← 专用线程池
);
return future.get(timeoutSeconds, TimeUnit.SECONDS);  // ← 带超时的阻塞等待
```

**`platformExecuteRescueExecutor` 配置**：core=2, max=8, queue=200。两个核心线程同时被占用时，后续任务排队。

---

### 2.4 实际调用层（Invoke Layer）

#### `PlatformAgentExecutionService` — `helloai-core/.../service/PlatformAgentExecutionService.java`

**职责**：统一的平台内执行入口，路由到正确的执行器。

```java
public CompletableFuture<AgentResult> execute(Agent agent, AgentTask task) {
    AgentExecutor executor = agentExecutorRouter.route(agent);
    executor.checkCapability(agent, task.getRequiredCapabilities());
    heartbeatService.active(agent.getId());
    return executor.execute(agent, task);  // 返回 CompletableFuture
}
```

#### `AgentExecutorRouter` — `helloai-core/.../agent/executor/AgentExecutorRouter.java`

**职责**：遍历所有 `AgentExecutor` bean，找到第一个 `supports(agent)` 返回 true 的。

```java
public AgentExecutor route(Agent agent) {
    return executors.stream()
        .filter(e -> e.supports(agent))
        .findFirst()
        .orElseThrow(() -> new BizException("No executor for agent"));
}
```

#### `AgentExecutor` — `helloai-core/.../agent/executor/AgentExecutor.java`

接口，定义了执行器的契约：
- `execute(Agent, AgentTask) → CompletableFuture<AgentResult>`
- `supports(Agent) → boolean`
- `checkCapability(Agent, List<String>) → void`

#### `ApiKeyAgentExecutor` — `helloai-core/.../agent/executor/ApiKeyAgentExecutor.java`

**职责**：API_KEY_LLM 的实际执行器。**这是卡死问题的第三关键节点**。

```java
supports(agent) → agent.accessType == API_KEY_LLM

execute(agent, task):
  → provider = agent.modelType  // 如 "deepseek"
  → CompletableFuture.supplyAsync(() -> {
      // 在 apiKeyLlmExecutor 线程池中执行
      
      if (不是 mock 模式) {
          // ★ 从 vault 获取解密后的 API Key
          vaultApiKey = credentialVaultBindingService
              .getAgentApiKeyPlaintext(agentId, provider);
          // 如果 requireVault && vaultApiKey == null → 抛异常
      }
      
      // ★ 调用 LLM（这是最可能阻塞的地方）
      String content = agentChatClientService.generate(
          agent, systemPrompt, userPrompt, provider, vaultApiKey
      );
      
      return AgentResult.success(content, "STOP", getName(), totalTokens);
  }, apiKeyLlmExecutor)
```

**`apiKeyLlmExecutor` 配置**：core=4, max=16, queue=200, prefix=`api-key-llm-`

#### `AgentChatClientService` — `helloai-core/.../service/AgentChatClientService.java`

**职责**：构建 ChatClient 并调用。真正的 HTTP 调用发生在这里。

```java
generate(agent, systemPrompt, userPrompt, provider, vaultApiKey):
  if (mock 模式):
      → MockChatModel.echo(prompt)  // 立即返回，不会阻塞
  else:
      if (vaultApiKey != null):
          → ProviderChatClientFactory.createChatClient(apiKey, agent, model)
          → chatClient.prompt().user(...).call().chatResponse()
      else:
          → 使用默认 ChatClient.Builder（全局配置的 API Key）
```

#### `ProviderChatClientFactory` — `helloai-core/.../agent/chat/ProviderChatClientFactory.java`

接口。`supports(provider) → boolean` 和 `createChatClient(apiKey, agent, model) → ChatClient`。

#### `DeepSeekProviderChatClientFactory` — `helloai-start/.../chat/DeepSeekProviderChatClientFactory.java`

```java
supports(provider) → "deepseek".equals(provider)
createChatClient(apiKey, agent, model):
  → new DeepSeekChatModel(apiKey, options)
  → ChatClient.builder(model).build()
```

真正的 HTTP 调用发生在这里。如果 DeepSeek API 响应慢或无响应，会一直阻塞直到 HTTP 超时。

#### `CredentialVaultBindingService` — `helloai-core/.../service/CredentialVaultBindingService.java`

**职责**：从 `credential_vault` 表中查找 Agent 绑定的凭证，并返回解密后的明文。

```java
getAgentApiKeyPlaintext(agentId, provider):
  → 查 credential_vault 表中 ACTIVE 记录
  → 如果是 secretRef：从环境变量读取
  → 如果是 encryptedValue：调用 credentialCryptoService.decryptFromBase64()
  → 返回明文 API Key
```

#### `CredentialCryptoService` — `helloai-common/.../crypto/CredentialCryptoService.java`

AES-256-GCM 加解密。AES Key 从 `helloai.security.credential.aes-key-base64` 配置或 `HELLOAI_CREDENTIAL_AES_KEY_BASE64` 环境变量读取。

---

### 2.5 补偿与健康检查层

#### `AgentHealthCheckTask` — `helloai-job/.../task/AgentHealthCheckTask.java`

每 60 秒扫描一次：
1. 找到 `lastSeenAt > 5分钟前` 的 Agent（排除 SLEEPING）
2. Redis 二次确认
3. CAS 标 OFFLINE
4. `reassignStaleTasks(agentId)` → 把 ASSIGNED/IN_PROGRESS 的子任务重新调度

#### `SubTaskTimeoutTask` — `helloai-job/.../task/SubTaskTimeoutTask.java`

每 30 秒扫描：
- 找到 IN_PROGRESS 且 `update_time > 2小时前` 的子任务
- 直接 `block(subTaskId)` 转 BLOCKED

---

## 三、完整链路调用图（含线程池边界）

```
                                    ┌─ 调度事务边界 ─┐
POST /sub-tasks/reassign/{id}
  SubTaskController.reassign()
    SubTaskDispatchService.dispatchBlockedSubTask()
      SubTaskService.resetToPendingForDispatch()     [status: BLOCKED→PENDING]
      ResilientDispatcher.assignNext()               [可能触发熔断 fallback]
        AgentSelector.pickAlternative()              [同角色备选]
        SubTaskService.assignNext()
          SubTaskService.changeStatus(ASSIGNED)      [status: PENDING→ASSIGNED]
            → 写 Outbox
            → 发 Inbox 通知
            → ★ publishEvent(SubTaskAssignedEvent)
                                    └─ 事务提交 ─┘

                                    ┌─ @Async 线程 ─┐
SubTaskAutoExecutionDispatcher.onAssigned(event)
  → 检查 agent.accessType == API_KEY_LLM
  SubTaskExecutionService.executeOnce(subTaskId)
    startIfNeeded():
      SubTaskService.start(subTaskId)                [status: ASSIGNED→IN_PROGRESS]
        → 记录 sub_task_execute_start
    
    executeWithRescueTimeout(agent, task, 180s):
      ┌─ platformExecuteRescueExecutor 线程 ─┐
      CompletableFuture.supplyAsync(() -> {
        PlatformAgentExecutionService.execute(agent, task).join()
          AgentExecutorRouter.route(agent) → ApiKeyAgentExecutor
          
          ┌─ apiKeyLlmExecutor 线程 ─┐
          ApiKeyAgentExecutor.execute(agent, task)
            → vaultApiKey = credentialVaultBindingService.getAgentApiKeyPlaintext()
            → ★ agentChatClientService.generate(...)  ← 真正的 HTTP 调用
                → DeepSeekProviderChatClientFactory.createChatClient()
                → ChatClient.call()
                → ★ HTTP 请求到 DeepSeek API        ← 阻塞点
          └────────────────────────────┘
          
        }).join()  ← 等待 apiKeyLlmExecutor 线程完成
      }, platformExecuteRescueExecutor)
      └──────────────────────────────────────┘
      
      future.get(180, SECONDS)  ← 最多等 180 秒
      
    [成功] → subTaskService.submit()                 [status: IN_PROGRESS→REVIEW]
    [失败] → subTaskService.block()                  [status: IN_PROGRESS→BLOCKED]
                                    └─────────────────┘
```

---

## 四、静默卡死的可能根因分析

基于以上链路分析，卡死在 `IN_PROGRESS`（`task_timeline` 只到 `sub_task_execute_start`）的可能原因：

### 假说 A：HTTP 调用层阻塞（优先级：最高）

**位置**：`AgentChatClientService.generate()` → `DeepSeekProviderChatClientFactory.createChatClient()` → `ChatClient.call()`

**现象**：DeepSeek HTTP 请求发出后，服务端无响应，也没有触发 HTTP 超时。

**为什么 rescue timeout 没生效**：
- `executeWithRescueTimeout` 中的 `future.get(180, SECONDS)` 等待的是 `apiKeyLlmExecutor` 中的 `supplyAsync` 完成
- `future.get()` 只是不再等待了，然后 `cancel(true)` 对已经发起的 HTTP 请求无能为力
- 如果 HTTP 请求卡在 socket read，`cancel(true)` 只能中断等待线程，无法中断底层 socket

**验证方式**：抓 thread dump，看 `api-key-llm-*` 线程是否卡在 `SocketInputStream.socketRead0()`（native 方法）

### 假说 B：线程池耗尽（优先级：中）

**位置**：`platformExecuteRescueExecutor`（core=2, max=8, queue=200）

**现象**：前两个执行任务占用了核心线程并阻塞，后续任务在队列中排队但永远不会被执行（因为前面的永远不会完成）。

**为什么 rescue timeout 没生效**：
- `CompletableFuture.supplyAsync(..., platformExecuteRescueExecutor)` 提交任务到线程池
- 如果线程池队列已满或被阻塞任务占满核心线程，任务可能永远不被调度
- `future.get(180, SECONDS)` 等待的是任务被调度并完成，但如果任务从未被调度...

**验证方式**：查看 `platformExecuteRescueExecutor` 的活跃线程数和队列大小

### 假说 C：@Async 线程池耗尽（优先级：中）

**位置**：`SubTaskAutoExecutionDispatcher.onAssigned()` 的 `@Async` 线程

**现象**：Spring 默认的 `@Async` 执行器线程全部被占用，新的分配事件无法被处理。

**为什么表现为卡在 IN_PROGRESS**：
- `dispatchBlockedSubTask` 中的 `ResilientDispatcher.assignNext()` 在调度事务中同步执行
- 事务提交后发布 `SubTaskAssignedEvent`
- `@TransactionalEventListener(phase = AFTER_COMMIT)` 需要 `@Async` 线程来消费
- 如果 `@Async` 线程池满了，事件永远不会被消费，子任务永远停在 ASSIGNED... 

**但这个假说与当前现象不一致**：当前现象是子任务能进入 `IN_PROGRESS`（说明 @Async 消费了事件），但之后卡住。所以这个假说可能性较低。

### 假说 D：executeOnce 异常逃逸（优先级：低，已修复）

**位置**：`SubTaskExecutionService.executeOnce()`

**现状**：已经加了 try/catch 包裹 `AgentTask` 构建和 prompt 拼装。但仍有可能某些异常路径没有被覆盖（如 finally 块中的异常）。

### 假说 E：@TransactionalEventListener 与 @Async 的组合行为（优先级：中）

**位置**：`SubTaskAutoExecutionDispatcher.onAssigned()`

**现象**：`AFTER_COMMIT` + `@Async` 的组合在某些 Spring 版本中可能有边缘行为——事务提交后异步线程启动，但如果异步线程池的队列策略是 CallerRunsPolicy 或被拒绝...

**验证方式**：检查 Spring 默认 `@Async` 执行器的配置和拒绝策略。

---

## 五、破局角度建议

### 角度 1：加 HTTP 超时（治标）

给 DeepSeek ChatClient 设置 connect timeout 和 read timeout：
```yaml
spring:
  ai:
    deepseek:
      connect-timeout: 30s
      read-timeout: 120s
```
并确认这些配置确实被 `DeepSeekProviderChatClientFactory` 使用。

### 角度 2：独立线程 + 真正的 Future 取消（治本）

当前架构用 `CompletableFuture.cancel(true)` 无法真正中断阻塞的 HTTP 调用。改为用 `ExecutorService.submit()` + `Future.cancel(true)`，在独立的线程中执行，超时后关闭底层 HTTP 连接。

### 角度 3：从 rescue 超时改为看门狗（换个思路）

不在调用层做超时，而是在后台加一个独立的"看门狗"定时任务：
- 每 N 秒扫描 IN_PROGRESS 且 `update_time` 超过阈值的子任务
- 直接标记为 BLOCKED
- 这样可以确保无论什么原因卡死，都能在固定时间内恢复

这类似于 `SubTaskTimeoutTask`，但把 2 小时的阈值降到 60-120 秒，专门用于平台内执行。

### 角度 4：去掉二次 CompletableFuture 嵌套（简化）

当前执行链有三层异步嵌套：
```
@Async → platformExecuteRescueExecutor → apiKeyLlmExecutor
```

每一层都可能引入调度延迟和线程池耗尽风险。考虑简化：
```java
// SubTaskAutoExecutionDispatcher.onAssigned() 中直接同步调用
SubTaskExecutionService.executeOnce(subTaskId);  // 整个方法在 @Async 线程中同步执行
```

去掉 `executeWithRescueTimeout` 中的 `CompletableFuture.supplyAsync`，改为在 `@Async` 线程中直接调用 `platformAgentExecutionService.execute(agent, task).join()`，然后用 `Future.get(timeout)` 在最外层等待。

### 角度 5：换用虚拟线程（JDK 21+）

如果升级到 JDK 21+，可以用虚拟线程替代所有平台线程池，消除线程池耗尽和上下文切换开销。

### 角度 6：加入心跳式执行追踪（可观测性）

在 `ApiKeyAgentExecutor.execute()` 中，在执行前后分别写入 Redis 一个"执行心跳"，看门狗任务检查心跳是否在更新，如果没有则判定卡死。

---

## 六、文件索引（按模块）

### helloai-common
| 文件 | 作用 |
|------|------|
| `constant/SubTaskStatus.java` | 子任务状态枚举 |
| `constant/AgentAccessType.java` | Agent 接入类型 + liveness/credential 判断 |
| `constant/AgentRole.java` | Agent 角色枚举（PLANNER/EXECUTOR/REVIEWER/PATROL） |
| `config/AgentExecutionProperties.java` | 执行配置（mock/real/timeout） |
| `crypto/CredentialCryptoService.java` | AES-GCM 加解密 |
| `base/BizException.java` | 业务异常 |

### helloai-core
| 文件 | 作用 |
|------|------|
| `entity/SubTask.java` | 子任务实体 |
| `entity/Agent.java` | Agent 实体 |
| `entity/CredentialVault.java` | 凭证保险库实体 |
| `statemachine/SubTaskStateMachine.java` | 子任务状态机 |
| `service/SubTaskService.java` | **核心**：状态变更、分配、发布事件 |
| `service/SubTaskDispatchService.java` | 重分配编排（PENDING → 调度器） |
| `service/SubTaskExecutionService.java` | **核心**：执行编排（start → execute → submit/block） |
| `service/SubTaskAutoExecutionDispatcher.java` | **核心**：@Async 监听 ASSIGNED 事件 |
| `service/ResilientDispatcher.java` | 带熔断的任务分配 |
| `service/AgentSelector.java` | 备选 Agent 选择 |
| `service/PlatformAgentExecutionService.java` | 平台内统一执行入口 |
| `service/AgentChatClientService.java` | ChatClient 构建与调用 |
| `service/CredentialVaultBindingService.java` | 凭证绑定与解密读取 |
| `service/HeartbeatService.java` | 心跳在线态管理 |
| `agent/executor/AgentExecutor.java` | 执行器接口 |
| `agent/executor/ApiKeyAgentExecutor.java` | **核心**：API_KEY_LLM 执行器实现 |
| `agent/executor/AgentExecutorRouter.java` | 执行器路由器 |
| `agent/chat/ProviderChatClientFactory.java` | Provider ChatClient 工厂接口 |
| `event/SubTaskAssignedEvent.java` | 分配事件 DTO |

### helloai-api
| 文件 | 作用 |
|------|------|
| `controller/SubTaskController.java` | 子任务 REST 端点 |
| `controller/AgentExecutionController.java` | 执行预览 + LLM 连通性测试端点 |

### helloai-job
| 文件 | 作用 |
|------|------|
| `task/AgentHealthCheckTask.java` | Agent 心跳巡检 + 离线重分配 |
| `task/SubTaskTimeoutTask.java` | IN_PROGRESS 超时 → BLOCKED 巡检 |

### helloai-start
| 文件 | 作用 |
|------|------|
| `config/ApiKeyLlmExecutorConfig.java` | apiKeyLlmExecutor 线程池配置 |
| `config/PlatformExecuteRescueExecutorConfig.java` | platformExecuteRescueExecutor 线程池配置 |
| `chat/DeepSeekProviderChatClientFactory.java` | DeepSeek ChatClient 构建 |
