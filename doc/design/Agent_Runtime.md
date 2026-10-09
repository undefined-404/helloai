# Agent Runtime

> **Status: `Implemented`** · **Scope: 单次 Agent Turn 的执行契约（`AgentRuntime` 接口 + 八件套）；不含任务调度** · **差距锚点：`G-002 · G-003`**
>
> 最后更新：2026-10-09（**ToolRegistry 两个语义位**：新增「绑定层 vs 注册层」边界与三条作用面边界（§ToolRegistry 语义位）。状态事实源 = [`../HelloAI 实现差距表.md`](../HelloAI%20实现差距表.md)，本文件只声明设计边界，不复述进度）

## 定义

> AgentRuntime 负责“一次 Agent Turn 如何执行”，而不是负责整个任务如何调度。

## 目标接口

```java
public interface AgentRuntime {
    AgentExecutionResult execute(AgentContext context);
}
```

## Runtime 内部组成

```text
AgentRuntime
├── AgentContext
├── Session
├── SkillResolver
├── ToolRegistry
├── ToolExecutor
├── AgentLoop
├── SandboxProvider
└── EventRecorder
```

## 不属于 Runtime

```text
Planner
Global Scheduler
Workflow State Machine
Reviewer Decision
Agent Fleet Routing
```

## 迁移模型（已终结）

> **2026-09-30 G-002 单轨硬切**：迁移期结束——旧链与 Adapter 已删除，不存在双轨，也不存在灰度开关。

```text
Legacy Executor          已删除
LegacyExecutorAdapter    已删除（含 RuntimeAgentRuntimeRouter / TurnLlmCaller）
        ↓
AgentRuntime Contract    唯一执行契约
        ↑
RuntimeTurnExecutor      唯一实现（agentRuntimes.get(0) 直取，无路由无开关）
```

### 单轨后的结构约束（长期有效）

> Dual Executor 只是迁移策略，**不是长期架构**。该约束已达成（双轨不复存在），但由它派生的四条禁令对任何**未来**的执行路径变更仍然有效：

```text
禁止 复制完整业务链
禁止 复制第二套状态机
禁止 复制第二套 Review
禁止 无幂等地再次执行副作用
```

> 原「迁移结构图」（`ExecutionRouter` → Runtime / LegacyAdapter 分叉）已随旧链删除而失效，不再保留。

## ToolRegistry 语义位

`ToolRegistry.resolve(enabledToolNames, context)` 返回「**生效形态**」的工具定义，
是**模型可见工具的唯一判据**。两个语义位都由各域经 `ToolCallbackContributor` 声明：

```text
按条件可用      toolAvailability()  → false ⇒ 摘除（不落库、不改管理页、不改 MCP tools/list）
按上下文动态描述 toolDescription()   → 重写 description（生效粒度 = Turn）
```

声明面是 **`default` 方法**（默认空 Map = 不声明）⇒ 无声明者的贡献者行为与「无语义位」时逐一相同；
当前声明者 = `WebSearchToolCallback`（无搜索凭据 ⇒ 摘除 `web_search`）。
**内容级回退开关**即「删掉这一个覆写」——不需要回滚任何基础设施或测试。

### 绑定层 vs 注册层：两个不同的事实，分开表达

| | **绑定层** | **注册层** |
|---|---|---|
| 载体 | `agent_mcp_server` / `AgentMcpServerService` | `ToolRegistry` + `ToolCallbackContributor` 的声明面（`toolAvailability` / `toolDescription`） |
| 回答 | 「**这个 Agent 被授权使用哪些工具**」 | 「**平台/运行时此刻是否具备该工具的运行条件**」 |
| 事实性 | 持久化（DB 行）、管理页可改、按 Agent 粒度 | 进程内（凭据是否配置、本轮上下文里有什么）、不落库 |
| `is_enabled` 语义 | **授权 / 未授权** | —— |
| 下界 | **不可关闭清单 `CRITICAL_TOOLS`** = `{pullTasks, submitResult, heartbeat}` | —— |

**合成关系 = 交集**：模型可见工具 = 绑定层授权集 ∩ 注册层可用集。两层互不改写对方
（注册层摘除**不写** `agent_mcp_server`；绑定层禁用**不改**声明）。

三条禁令（违反即层次错位）：

1. 不得用 `agent_mcp_server` 表达「不具备」——写脏数据，且进程状态恢复后无法自动复原；
2. 不得用注册层声明表达「某 Agent 未授权」——声明者会被迫读 DB，把已消除的反向依赖拉回来；
3. 条件不可用**必须**表现为「不在模型可见列表」，不得退化为 description 里写「不要调用」（模型不保证遵守）。

**不可关闭清单的生效面**：`isToolEnabled`（短路判启用）与 `getEnabledTools`（并集兜底）——
即「授权面」；**刻意不抵销** `getEnabledToolsForAccess(API_KEY_LLM)` 的 MCP 会话工具过滤：
「不可关闭」≠「必须出现在每一个执行者的可见列表」，内部 LLM 执行者进程内无 MCP 会话，
注入这 3 个工具必然 401（2026-10-06 L3 P1-1 实测缺陷）。

### 三条作用面边界（须登记）

```text
① 生效粒度 = Turn：一次 AgentRuntime.execute 装配一次；同一 Turn 内 AgentLoop 的多轮迭代
   共享同一份 schema（ChatModelToolLoop 每轮都用同一个 AgentLoopInput 实例）。
   真·每轮描述需将解析点下沉到循环内 —— REF-4 视需要评估，当前不冒充已达成。
② 目录降级 fail-open：工具目录加载失败时「未知」不构成摘除理由（catalogDegraded），
   否则一次反射失败 = 所有 Agent 的所有工具从模型视野消失。
③ MCP tools/list 暴露面不受影响：McpToolConfig.mcpToolCallbacks() → spring-ai MCP Server
   自动配置对全部注册工具暴露（denylist 默认全开）。本语义位作用于**进程内 Runtime 的
   模型可见列表**（内部 LLM 执行者）；外部 MCP Agent 依旧看得到全部工具，调用时走既有
   优雅降级兜底——与「平台把 AI Agent 当人来用、只关心能否办妥」的定位一致。
```

## Agent Loop

长期目标：

```text
Model
 ↓
Decision
 ↓
Tool
 ↓
Observation
 ↓
Model
 ↓
...
 ↓
Final Result
```

第一阶段允许 Runtime 继续支持单步/单次执行，不要求一次性完成完整 Tool Loop。
