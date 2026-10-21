# Paperclip 外部 Agent 接入与 CLI 适配器借鉴分析

> **状态：Analysis**
> 最后更新：2026-10-21
>
> 分析对象：`E:/workspace/paperclip-master`（github.com/paperclipai/paperclip，MIT）
> 对照对象：helloai（外部 agent 注册 → 打卡 → 轮询 → MCP 工具）
> **证据口径**：本文所有源码引用均为 `[实测]` —— 本地仓库直接读取并附 `路径:行号`（相对 paperclip 仓库根）。
> 本文不含 `[推断]` 级事实；§4「不建议照搬」为**判断**而非事实断言，已逐条给出前提条件。
> 可执行结论与排序**已迁出至 `plan/`**（见 §5），本文不承载本项目的排期与进度（《治理规则》§3.6）。

---

## 0. 一句话结论

**helloai 是「拉模型」——agent 活着、心跳在线、自己来领活；Paperclip 是「推模型」——平台算好该醒了谁，自己去 spawn 一个进程把活塞给它。**

这不是技术偏好差异，是**运行时形态**决定的：GUI agent 是常驻服务，可以被通知；CLI agent 是一次性进程，没人能通知它，只能由平台拉起来。所以 Paperclip 必须实现推模型，而你要接 CLI 产品，就必须补上这一条通道 —— 但**不能替代**现有拉模型，只能是并列的第二通道。

Paperclip 自己也不是纯推：它同时存在 4 种触发形态（`docs/adapters/overview.md`）：

| 形态                               | Adapter                                    | 谁主动            | 对应你的现状         |
| -------------------------------- | ------------------------------------------ | -------------- | -------------- |
| 平台 spawn 子进程                     | `claude_local` / `codex_local` / `process` | 平台推            | **缺**          |
| 平台 POST webhook 触发，agent 回调 REST | `http`                                     | 平台推 + agent 回拉 | 接近你的轮询，但没有回调契约 |
| 调已运行的服务端点                        | `hermes_gateway` / `openclaw_gateway`      | 平台推            | **缺**          |
| 纯轮询领取                            | 无                                          | agent 拉        | **你现在只有这条**    |

---

## 1. 重点：CLI 产品的接入是怎么实现的

### 1.1 全链路五段

```
heartbeat 判定该唤醒（定时/指派/评论/@/审批结果/重试/续跑）
  ↓ enqueueWakeup 落 heartbeat_runs 行（status=queued）
  ↓ run-preparation：解析 workspace / 注入 env / 生成 MCP 配置 / 落 instructions 文件
  ↓ runtime-execution：调 adapter.execute(ctx)
  ↓ adapter.execute：spawn 子进程 → 逐行读 stdout → onLog 流式回传 → 解析 usage → 解析 session_id
  ↓ run-completion：session 落库 / 成本入账 / 工作区 sync back / 状态机推进
```

服务端编排入口：`server/src/services/heartbeat.ts`（4141 行，是整套的核心）  
执行落地：`server/src/services/heartbeat/runtime-execution.ts`（1163 行）  
唤醒排队：`server/src/services/heartbeat/queue.ts`

### 1.2 spawn 契约（最关键的一段）

`packages/adapters/claude-local/src/server/execute.ts:860-893` 构造命令行：

```ts
const args = ["--print", "--output-format", "stream-json", "--verbose"];
if (resumeSessionId) args.push("--resume", resumeSessionId);
args.push(...permissionArgs);            // --dangerously-skip-permissions
if (passesConfiguredModel) args.push("--model", model);
if (maxTurns > 0) args.push("--max-turns", String(maxTurns));
if (attemptInstructionsFilePath && !resumeSessionId)
  args.push("--append-system-prompt-file", attemptInstructionsFilePath);
if (runtimeMcpServers.length > 0)
  args.push("--mcp-config", effectiveMcpConfigPath, "--strict-mcp-config");
args.push("--add-dir", effectivePromptBundleAddDir);
```

**要点逐条：**

1. **`--print` = headless 一次性模式**。这是 CLI 产品能被平台接管的唯一入口。没有 headless 模式的 CLI 产品，接不进来（只能靠 `tmux`/PTY 模拟，Paperclip 不做）。
2. **prompt 走 stdin，不走命令行参数**（`execute.ts:990` `stdin: prompt`）。因为 prompt 是大段 markdown（任务上下文 + 唤醒原因 + 模板 + instructions 指针），命令行长度会爆。
3. **`--resume <sessionId>` 是 CLI 接入的第二根支柱**，见 §1.3。
4. **`--append-system-prompt-file` 只在首次给**（`execute.ts:884`）。注释写得很直白：resume 时再注入一次，每心跳白烧 5-10K token，且 CLI 可能直接拒绝这个组合。这是一个真实踩过的坑。
5. **`--strict-mcp-config`**：给什么 MCP 就只认什么 MCP，不吃用户 `~/.claude` 里的全局配置。这保证了「同一台机器上跑 20 个不同 agent，工具面互不串」。
6. **`--add-dir` 挂技能目录**（`skills.ts` 用临时目录 + symlink 实现，见 `docs/adapters/claude-local.md`「Skills Injection」）—— 技能不落进工作区，避免污染 cwd。

底层落地是 `runChildProcess`（`packages/adapter-utils/src/server-utils.ts:4708`），返回 `{exitCode, stdout, stderr}`，同时 `onLog` 逐块回调。

### 1.3 session 续接：CLI 接入的价值与坑都在这里

CLI 是无状态的，每个心跳都是一次新进程。要让「员工记得昨天干了什么」，只能靠 CLI 自己的会话文件 + `--resume`。

Paperclip 的续接判据（`execute.ts:800-807`）是**四重 AND**，任一不满足就开新会话：

```ts
canResumeSession =
  runtimeSessionId.length > 0 &&
  isValidUuid &&
  hasMatchingPromptBundle &&     // 系统提示词包指纹一致
  hasMatchingSessionCwd &&       // 工作目录一致（换 worktree 就作废）
  hasMatchingExecutionTarget;    // 执行目标一致（本机/ssh/沙箱 换了就作废）
```

配一段审计日志（`execute.ts:808-845`），每条不续接的原因都单独打印。**这个设计值得直接抄** —— 续接失效是静默降级，如果没有日志，你会以为什么都正常，实际每次心跳都在重读全部上下文，成本翻倍。

**Poisoned session 自愈**（`docs/adapters/claude-local.md`「Poisoned previous_message_id」）：会话文件坏了会导致 `--resume` 每次必然 400，并且旧的坏 sessionId 被反复持久化 → 该任务永久卡死。Paperclip 的三道闸：

1. resume 返回 400 → 自动换新 session 重试一次，顺手删掉坏的 `<session>.jsonl`
2. **validate-before-persist**：带这个错误的 result 绝不写回 session store，返回 `sessionId: null` + `clearSession: true`
3. 服务端据此 `clearTaskSessions` 清库

**这是 CLI 接入最容易漏的一条**：只要允许持久化外部系统的句柄（sessionId / 容器 id / job id），就必须配一个「句柄坏了怎么自愈」的路径，否则单点坏数据 = 任务永久搁死。

> **对 helloai 的直接映射**：你的外部 agent 表里如果有 `session_id`、`worker_id` 这类外部句柄字段，同样需要 `clearSession` 语义 + 自愈重试。目前 heartbeat 只判在线/离线，句柄层的坏数据没有出口。

### 1.4 输出流解析：`stream-json` 的三类事件 + 一个去重技巧

`packages/adapters/claude-local/src/server/parse.ts:83-130`：

| 事件                        | 取什么                                                             |
| ------------------------- | --------------------------------------------------------------- |
| `system` / `subtype=init` | `session_id`、`model`                                            |
| `assistant`               | `session_id`、`message.usage` 的 token 数、`message.content[].text` |
| `result`                  | 最终结果对象（含 stop_reason、错误子类型）                                     |

**关键技巧 —— usage 去重（`parse.ts:100-114`）**：流式模式下同一条 assistant 消息会被拆成多条事件重复上报，直接累加会严重超算。Paperclip 用 `message.id` 做 key，存 `Math.max(前值, 本次值)`，只累加**增量差**。这是纯工程经验，文档里看不到，但少了它成本账全是错的。

失败分类也做成了独立函数，供上层做不同处置：`isClaudeMaxTurnsResult` / `isClaudeProviderQuotaError` / `isClaudeRefusalResult` / `isClaudeUnknownSessionError`（`server/index.ts` 导出）。**配额超限 ≠ 任务失败 ≠ 需要重试**，三者处置完全不同，Paperclip 在 adapter 里就分好类再往上抛。

### 1.5 环境注入：三层合并 + 一个「配置不可覆盖运行时」的硬规则

`execute.ts:296-317`：

```ts
for (const [key, value] of Object.entries(shapedEnvConfig)) {   // 第二层：config.env
  if (isForbiddenConfigEnvKey(key)) continue;                   // 黑名单直接丢
  if (isPaperclipRuntimeEnvKey(key) && key in env) continue;    // ★ 运行时优先
  env[key] = value;
}
if (authToken) env.PAPERCLIP_API_KEY = authToken;               // 第三层：run token
```

优先级：`运行时注入的 PAPERCLIP_*` > `adapterConfig.env` > `process.env` 继承。

**注释原文（`execute.ts:304-306`）**：`PAPERCLIP_API_KEY` 永远不接受来自 config 的值，唯一来源是 harness 现签的 run token。

**这是安全边界，不是代码风格**。用户在 agent 配置页填一个 env 就能拿到别人的 key，是这类平台最典型的越权路径。

注入的变量面（`docs/guides/agent-developer/how-agents-work.md`）：

```
PAPERCLIP_AGENT_ID / COMPANY_ID / API_URL / API_KEY / RUN_ID   ← 恒定身份
PAPERCLIP_TASK_ID / WAKE_REASON / WAKE_COMMENT_ID              ← 本次唤醒的因
PAPERCLIP_APPROVAL_ID / APPROVAL_STATUS                        ← 审批回执
PAPERCLIP_WORKSPACE_* / RUNTIME_*                              ← 工作区与服务地址
PAPERCLIP_WORKSPACES_JSON / RUNTIME_SERVICES_JSON              ← 结构化补充
```

**对比你现在的做法**：agent 通过 MCP 工具主动查「我有什么任务」。Paperclip 是**把「为什么叫醒你」直接写进环境变量**，agent 第一步就读 env，不需要先问平台。省一次往返，且唤醒原因不可能被读错。

### 1.6 双引擎：CLI wrapper vs ACP

`docs/adapters/overview.md`「Feedback Granularity」把反馈粒度分了三档：

1. **ACP 引擎**（`engine: "acp"`）—— `@agentclientprotocol/claude-agent-acp`（`claude-local/package.json:56`），双向 JSON-RPC，能拿到 `text_delta`（逐 token）、`tool_call`（工具调用实时状态）、`status`（上下文窗口用量）
2. **CLI wrapper** —— 解析各家 CLI 的 stream-json，只有 message / tool / 最终 usage
3. **generic**（`process` / `http`）—— 只有裸 stdout

**给你的判断**：ACP 是锦上添花，**不要一开始就做**。它要求 CLI 产品实现这个协议，只有 Claude Code / Codex / Gemini 有。你要接的是"能 headless 跑、能吐结构化输出"的 CLI，第二档就够了。ACP 的价值是「让人看着 agent 干活」，属于体验层，不是能力层。

### 1.7 可插拔适配器：三个注册表 + 安装的安全边界

`docs/adapters/overview.md`「Adapter Architecture」——一个 adapter 包要被**三个注册表**消费：

| 注册表    | 职责                        |
| ------ | ------------------------- |
| Server | 执行、解析、环境自检                |
| UI     | 渲染运行过程（transcript）、提供配置表单 |
| CLI    | 终端 `run --watch` 的输出格式    |

外部包通过 `POST /api/adapters/install`（`server/src/routes/adapters.ts:324`）安装，支持 npm 包名或本地路径。**注意它的安全态度**（`adapters.ts:65-72`）：注释明说「任何 npm 包都会在 server 进程里被 require，等于让实例管理员执行任意代码」，所以云端托管实例直接禁用这条路由（`adapter_install_platform_managed`）。

> **这正好命中你记忆里的 `G-005` 条件②**：平台代跑第三方代码 = 必须与 app 容器隔离 env / 网络。Paperclip 的答案是「自己不做沙箱，但承认风险并把入口收窄到 instance-admin，托管版关掉」。  
> **你的选择应该更保守**：外部 adapter 只允许「声明式清单 + 内置执行器」，不允许任意 npm/脚本。

### 1.8 工作区契约：`no-remote-git`

`packages/adapters/AUTHORING.md` 定了一条跨 run 持久化铁律：

> **唯一持久化边界 = 本地工作目录 cwd。任何 adapter 都不许依赖 git remote 做跨 run 状态。**

理由（原文）：下次醒来如果靠 `git fetch` 拿状态，会（a）破坏依赖本地 worktree 的下游任务，(b) 在没配 remote 的隔离工作区直接崩。

替代方案：`prepareWorkspaceForSshExecution` / `restoreWorkspaceFromSshExecution`（`packages/adapter-utils/src/ssh.ts`）—— run 前把本地 cwd 打包送到远程，run 结束把远程改动（含新 commit）同步回本地。**全程不需要有 remote。**

还有一道静态闸门：`scripts/check-no-git-push.mjs` 扫 adapter / runtime 源码，发现未批准的 `git push` 直接 CI fail，要白名单必须写 `// paperclip:allow-git-push: <reason>`。

> **这是「规则存在 ≠ 全路径强制」的正面样本** —— 你记忆里的高频判据①。Paperclip 把这条不变量同时做在：文档（AUTHORING.md）+ 运行时助手（ssh.ts）+ 静态检查（check-no-git-push.mjs）+ 契约测试（`ssh-fixture.test.ts` 的 `no-remote-git contract` case）。四层，而不是一层。

---

## 2. 唤醒模型：比「打卡」更细的东西

### 2.1 唤醒原因是有限枚举，不是自由字符串

实测到的取值：`issue_assigned`、`issue_comment_mentioned`、`source_scoped_recovery_action`、`execution_*`（前缀族）、`ai_connection_pool_retry`（`heartbeat.ts:689-693`、`2469-2484`）。

不同唤醒原因在编排层走**不同分支**：

- `issue_comment_mentioned` → 不触发「进度停滞」判定（`heartbeat.ts:691`）
- `execution_*` → 不触发（`heartbeat.ts:693`）
- 恢复类 → 走 `withRecoveryContext`（`scheduling.ts:33`）

**你的现状**：派发只看「谁在线 + 谁分高」。**建议补的是「为什么派」的结构化原因 + 按原因分支的处置策略**，这一层很便宜但收益大（例如「被 @ 叫醒」不该被算作「任务无进展」）。

### 2.2 可调用性判据（invokability）：10 种不可调原因

`server/src/services/agent-invokability.ts:17-31`：

```
missing / paused / terminated / pending_approval / unknown_status
manager_missing / manager_company_mismatch / manager_terminated
reporting_cycle / reporting_chain_too_deep
```

前 5 个是自身状态，后 5 个是**组织链健康度**（上级缺失、跨公司、上级已终止、上报环、层级过深）。

**这一条直接把你的「心跳必须在线才可分派」升级了**：在线只是必要条件之一。一个在线但「上级 agent 已终止」或「组织链成环」的 agent，派活下去必然烂尾。你现在把这类判断散在 `ExecutionDispatchValidator` 里，Paperclip 把它们收敛成一个纯函数 + 枚举化 reason，便于前端直接展示「为什么这活派不出去」。

### 2.3 三种「再跑一次」必须分开（这条最容易被做混）

Paperclip 明确区分（`docs/guides/agent-developer/heartbeat-protocol.md`「Run Liveness」）：

| 概念               | 含义                                                   | 计数维度                            |
| ---------------- | ---------------------------------------------------- | ------------------------------- |
| **retry**        | 进程/基础设施失败，重跑同一动作                                     | 运营层                             |
| **continuation** | 跑完了但没产出实质进展（`plan_only` / `empty_response`），有界地再叫醒一次 | `continuationAttempt`，按源 run 链计 |
| **recovery**     | 状态机走岔了（进程丢失、评审路径断），带 cause 纠偏                        | `RECOVERY_ORIGIN_KINDS`         |

关键约束：

- 只有 `plan_only` 和 `empty_response` 才允许 continuation
- continuation **不改任务状态**（不标 blocked / done）
- 次数耗尽后留审计评论，交人处理
- **「只建了工作区」不算实质进展**（原文点名：进展必须是工具事件、评论、文档修订、commit、测试）

> **对你的价值**：`ExternalAgentFailureTracker` 现在大概把「超时 / 无输出 / 报错 / 空回复」混在一个失败计数器里。混在一起的后果是「空回复」把「真失败」的配额吃掉了。**这三种应各自有独立的配额与出口。**

### 2.4 agent 侧的九步心跳协议

`docs/guides/agent-developer/heartbeat-protocol.md` 定义了一份 agent 必须遵守的契约，核心是 **REST + `X-Paperclip-Run-Id` 幂等头**，不是 MCP：

```
1 身份  GET /api/agents/me
2 审批回执（若 PAPERCLIP_APPROVAL_ID）
3 领活  GET /companies/{id}/issues?assigneeAgentId=me&status=todo,in_progress,in_review,blocked
4 选活  in_progress > in_review(被评论叫醒) > todo > blocked(仅当能解)
5 认领  POST /issues/{id}/checkout  ← 409 绝不重试
6 读上下文（含祖先链：为什么有这个任务）
7 干活（同一心跳内必须有具体动作，不许只出计划）
8 更新状态 PATCH /issues/{id}（必须带 run id 头）
9 派发子任务（必须带 parentId + goalId）
```

**最值得抄的是第 5 步的 checkout/409 语义**：抢占式认领，拿不到就换活，**永不重试 409**。你现在是「平台派给谁就是谁」，靠心跳判可达性来避免撞车 —— 但网络抖动/心跳延迟下仍可能双派。checkout 是数据库层的原子占位，比可达性判断可靠得多。


另外第 9 条注释值得看（`HEARTBEAT.md` §6）：长任务**用子任务 + 父任务被唤醒**，而不是让 agent 轮询子任务状态。这是「事件驱动替代轮询」在 agent 内部协议上的体现。

---

## 3. 其他值得借鉴的功能（按对你的价值排序）

### ★★★ C1. run-scoped 凭证，绝不发长期 key

`server/src/runtime-tools-token.ts:1-64`：自签 HS256 JWT，claims 含

```
sub=agentId, company_id, run_id, responsible_user_id,
scope="connection_intents"|"github_credentials",
iat, exp(=1h), instance_id
```

签名密钥是 `HMAC(master, "runtime-tools:{instanceId}:{companyId}")`，**每公司一把派生密钥**，`timingSafeEqual` 比较。注释明写：broker token 的生命周期绑定 run，每次使用都重新校验 run 是否还活着。

而 `PAPERCLIP_API_KEY` 作为 run token 注入子进程（`execute.ts:315`），run 结束即废。

> **你的现状是反的**：`credential_vault` + AES 主密钥解全量凭据，外部 agent 拿的是长期 key。一旦泄漏面就等于全库。  
> **改造最小形态**：外部 agent 每次心跳/领活时，平台签发一个「本 run 有效」的短票（含 agentId + runId + 允许的工具集），执行结束作废。你的 Sa-Token 体系里做得到，不必引 JWT。

### ★★★ C2. 成本核算 → 预算闸门 → 自动停机

`docs/guides/board-operator/costs-and-budgets.md`：

- 每次 run 上报 `provider / model / inputTokens / outputTokens / costCents`，按月聚合
- 公司级 + agent 级双预算
- **80% → 软告警（提示 agent 只做关键任务）；100% → 硬停（自动 pause，不再发心跳）**

token 数据不是 agent 自报，而是 adapter 从 CLI 的 stream-json 里解析出来的（§1.4），**不可伪造**。

> 这是你完全空白的一块。你的 planner 派发没有任何花费闸门，一个失控的 subtask 可以无限重试烧钱。**最小落地：在 `agent_execution_record` 上累加 token/费用，派发前查 80%/100% 阈值，超限直接改 agent 状态并拒绝派发。** 不需要前端先做。

### ★★☆ C3. 技能：catalog / company library 两层 + trust level 由文件推导

`docs/guides/agent-developer/skills-store.md`：

| 层                                        | 是什么                                             |
| ---------------------------------------- | ----------------------------------------------- |
| catalog（`@paperclipai/skills-catalog` 包） | 随产品发布的只读技能集，按 category / recommendedForRoles 检索 |
| company library（`company_skills` 表）      | 实际装进公司的技能，可编辑、可 fork、版本化                        |

安装 = 从 catalog 复制到 library，之后独立演进。每个技能带 `contentHash` 做漂移检测。

**trust level 不是自报，是从文件构成推导**：

```
markdown_only     只有 .md
assets            md + 图片/PDF
scripts_executables 含任何 .sh/.js/.py/.ts  → 最高审查
```

并且明确规定：**import 阶段永不执行脚本、hook、依赖安装、构建**。

> **这条正好接上你的 `REF-8.3`**：你已交付「技能加载」，但没有「执行」。Paperclip 的答案是分级 —— markdown_only 的技能只做提示词注入，零风险；只有 scripts 级别才需要执行通道和沙箱。**你不必一次性解决"技能执行"这个大问题，先把 markdown_only 级别的技能接进来就能产生价值。**  
> 同时 `trustLevel 由文件推导而非自报` 是一条可以直接抄的判据 —— 自报的信任等级等于没有。

### ★★☆ C4. 员工培训资产四件套

`server/src/onboarding-assets/ceo/`：

| 文件             | 作用                               |
| -------------- | -------------------------------- |
| `SOUL.md`      | 人格与判断准则（价值观、语气、取舍原则）             |
| `AGENTS.md`    | 职责边界与协作规约                        |
| `HEARTBEAT.md` | **每次醒来执行的检查清单**（编号步骤，带具体 API 调用） |
| `TOOLS.md`     | 工具手册，初始为空骨架，「边用边补」               |

`HEARTBEAT.md` 写成了可执行的编号清单（身份 → 本地计划 → 审批 → 领活 → 认领 → 派发 → 事实抽取），不是散文式提示词。

> **对你的价值**：你现在 agent 的行为准则是散在 prompt_template 里（而且 `prompt_template` 9 条种子还是缺的）。拆成四件套的好处是**可版本化、可 A/B、可给不同角色复用**。`TOOLS.md` 初始为空、边用边补这个设计尤其巧妙 —— 它让 agent 自己维护工具手册，而不是等你写文档。

### ★★☆ C5. 结构化交互替代 markdown 对话

`POST /api/issues/{issueId}/interactions`，三种 kind：

```
suggest_tasks      提议任务树，等用户勾选
ask_user_questions 结构化提问
request_confirmation 明确的 yes/no
```

配套细节：

- `continuationPolicy: "wake_assignee"` —— 用户答完自动唤醒该 agent 继续
- `supersedeOnUserComment` —— 新评论是否顶掉待答卡片（默认 false，让讨论继续）
- 计划审批必须绑定到某个 `plan` 文档**具体 revision** + 幂等键 `confirmation:{issueId}:plan:{revisionId}`

> **你的现状**：agent 和人之间靠聊天文本。文本无法机读「用户选了什么」，后续流程只能靠 LLM 再解析一遍。**把「需要人决策」抽成结构化卡片，是把 agent 流程做稳的关键一步。**

### ★☆☆ C6. 审批与治理

`docs/guides/board-operator/approvals.md`：

- 两种核心审批：`hire_agent`（招人）、CEO 战略（**未批准前 CEO 不能把任务推到 in_progress**）
- 状态机：`pending → approved | rejected | revision_requested → resubmitted → pending`
- 董事保留权：随时 pause/resume/terminate 任意 agent、重派任务、覆盖预算、绕过审批直接建 agent

> 你的 `TeamMember` / 派发体系里没有「审批」这个层。最小可用版本：只做「高危动作需人批」一类（删数据、改生产配置、对外发送），不必做招聘/战略那么重。

### ★☆☆ C7. 组织架构与委派链

`docs/guides/board-operator/delegation.md`：目标 → CEO 出战略（走审批）→ 拆任务 → 按角色派给下属 → 下属心跳执行 → CEO 监控/解阻塞/升级。每个任务带 `parentId` + `goalId`，**任何工作都能回溯到它为什么存在**。

你有 planner/review/task 分层，但缺的&#x662F;**「目标」这一层的显式实体**和 `goalId` 的全程携带。有了它，「这个 subtask 是谁为了什么目标提的」不再靠猜。

### ★☆☆ C8. 执行工作区隔离

`docs/guides/board-operator/execution-workspaces-and-runtime-services.md`：

- 每个 issue run 可以拿一个独立 worktree（独立路径 + 独立分支 + 独立运行时服务）
- 工作区是**持久的，直到人手动关闭**
- 服务（长驻）与任务（一次性）分开建模：`Services` vs `Jobs`
- 多个 issue 可**有意共享**一个工作区（打同一分支）
- 懒初始化：重型初始化命令延迟到第一次服务启动前跑一次

> 你已有 `workspace` 概念但语义可能不同。**「持久直到人关」这条很关键** —— 自动回收工作区会毁掉 agent 的中间产物。

### ★☆☆ C9. MCP 的定位：薄封装，不是业务层

`packages/mcp-server/README.md` 第一句：

> This package is a thin MCP wrapper over the existing Paperclip REST API. It does not talk to the database directly and it does not reimplement business logic.

工具面 40 个左右，命名统一 `paperclipXxx`，另有 `paperclipApiRequest` 兜底（限 `/api` 前缀 + JSON body）。

> **这条是对你现有 MCP 设计的直接校验**：MCP 工具里**不应有业务逻辑**，只应做「参数校验 → 调服务接口 → 结构化返回」。如果你现在 `McpToolService` 里塞了业务判断，那是双写风险的源头（两个入口两套规则，必然漂移）。  
> 另外「一个通用 escape hatch 工具 + 受限路径前缀」这个设计值得抄 —— 新接口不用等 MCP 工具发布，agent 就能用。

### ☆ C10. 其余可延后

- **多组织隔离**（`company_id` 全表贯穿）：你单租户，暂不需要，但表设计上留 `tenant_id` 更安全
- **evals / 保存的测试运行**（`evals/promptfoo`）：给 agent 做回归测试，属于成熟期能力
- **移动端 / 状态卡片**：展示层
- **hermes_gateway / openclaw_gateway**：本质是「调已有 HTTP 服务的 adapter」，你需要时照 `http` adapter 做就行

---

## 4. 不建议照搬的（以及为什么）

### ✗ 4.1 spawn 模式不能套用到 GUI agent

Paperclip 的 spawn 前提是：**agent 运行时和平台在同一台机器，或平台能 SSH 过去**。你的 trae-excutor / TeleAgent-executor 在别人的桌面/容器里，平台既 spawn 不了也 ssh 不进去。

所以正确形态是**双通道并列**：

```
GUI agent  → 拉模型（现有）：注册 → 心跳 → 轮询/MCP 领取 → 回报
CLI agent  → 推模型（新增）：平台 spawn headless 进程 → 读 stdout → 落 run 记录
                                        ↓
                          两者写同一张 run 表、同一套 run_log、同一套成本事件
```

**最重要的一条：两条通道的观测量必须归一。** Paperclip 无论走哪个 adapter，落库都是 `heartbeat_runs` + run log + usage receipt。如果你的内部 LLM 执行和外部 agent 执行是两套记录表，你永远做不出统一的成本/审计/复盘视图。

### ✗ 4.2 不要引入「Node 进程内 require 第三方包」的插件模型

Paperclip 的 adapter 是 npm 包，在 server 进程里 require。它自己承认这是风险（`adapters.ts:65-72`），只能靠限制成 instance-admin + 托管版禁用。

你是 Java 单体，等价做法是「往 classpath 里塞任意 jar」—— 这是你记忆里 `G-005` 明确要挡的。**要做外部适配，就走声明式清单 + 内置执行器**（`type + 命令模板 + 参数白名单 + 输出解析规则`），而不是代码插件。

### ✗ 4.3 「公司模拟」叙事不必复制

Paperclip 的产品叙事是「AI 公司」，有 CEO/CTO/招聘/战略审批。这是一套完整的**演示价值 > 工程价值**的包装。你抄它的**机制**（组织链健康度检查、目标携带、审批门），但不必抄它的**产品隐喻**。

### ✗ 4.4 `no-remote-git` 是特定假设下的解

它假设「本地 worktree 是唯一真相」，适用于「平台和代码在同一机器」的部署。如果你未来要支持「代码在远端、执行在远端」，这条契约要重新设计。**抄它的思路（跨 run 状态必须有单一边界），不要抄它的具体实现。**

---

## 5. 落地路线（已迁出至 `plan/`）

本文只回答「Paperclip 怎么做、哪些值得借鉴、哪些明确不抄」。

**可执行结论与排序已另立计划**：[`plan/HelloAI Paperclip 借鉴落地计划（2026-10-21）.md`](../plan/HelloAI%20Paperclip%20借鉴落地计划（2026-10-21）.md)（`状态：Active`）。

> 依据《文档体系分类与治理规则》§3.6 —— `research/` **不承载本项目的排期与进度**；
> 可执行结论进入实施时，另立 `plan/` 文档。本文保留「借不借、为什么」的证据，
> 计划文档保留「先做哪个、多大代价、什么前提」。

---

## 6. 三个最容易被忽略、但最贵的细节

1. **`--append-system-prompt-file` 只在首次注入**（`execute.ts:881-886`）。resume 时再注入，每心跳白烧 5-10K token。任何"每次都重新塞一遍上下文"的实现，成本都会在几十个心跳后失控。
2. **usage 去重必须按消息 id 取 max**（`parse.ts:100-114`）。流式重复上报直接累加，成本账会虚高数倍，而你会以为是模型变贵了。
3. **外部句柄必须有自愈出口**（poisoned session 三道闸）。只写不读、只存不校的外部 ID，是「静默永久故障」的标准配方 —— 这正是你记忆里判据②「无读取方的列 = 反模式」的近亲。

---

## 附：证据索引

| 主题                                 | 位置                                                                        |
| ---------------------------------- | ------------------------------------------------------------------------- |
| adapter 总览 / 四形态 / 反馈粒度            | `docs/adapters/overview.md`                                               |
| Claude CLI spawn 完整参数              | `packages/adapters/claude-local/src/server/execute.ts:860-893`            |
| prompt 走 stdin                     | `execute.ts:990`                                                          |
| session 续接四重判据                     | `execute.ts:800-807`，日志 `808-845`                                         |
| poisoned session 自愈                | `execute.ts:1171-1182`、`docs/adapters/claude-local.md`                    |
| 子进程执行器                             | `packages/adapter-utils/src/server-utils.ts:4708`                         |
| stream-json 解析 + usage 去重          | `packages/adapters/claude-local/src/server/parse.ts:83-130`               |
| env 三层合并 + 运行时优先                   | `execute.ts:296-317`                                                      |
| 平台注入的 MCP 配置                       | `server/src/services/heartbeat/run-preparation.ts:933`                    |
| run-scoped JWT                     | `server/src/runtime-tools-token.ts:1-64`                                  |
| 可调用性 10 种阻断原因                      | `server/src/services/agent-invokability.ts:17-31`                         |
| 心跳协议九步                             | `docs/guides/agent-developer/heartbeat-protocol.md`                       |
| liveness / continuation / retry 分层 | 同上「Run Liveness」                                                          |
| 员工四件套                              | `server/src/onboarding-assets/ceo/{SOUL,AGENTS,HEARTBEAT,TOOLS}.md`       |
| 成本与预算闸门                            | `docs/guides/board-operator/costs-and-budgets.md`                         |
| 技能 catalog / trust level           | `docs/guides/agent-developer/skills-store.md`                             |
| 外部 adapter 安装的安全边界                 | `server/src/routes/adapters.ts:65-72, 324`                                |
| no-remote-git 契约                   | `packages/adapters/AUTHORING.md`、`scripts/check-no-git-push.mjs`          |
| MCP 薄封装原则                          | `packages/mcp-server/README.md`                                           |
| 工作区与服务模型                           | `docs/guides/board-operator/execution-workspaces-and-runtime-services.md` |
| 审批状态机                              | `docs/guides/board-operator/approvals.md`                                 |
| 委派链路                               | `docs/guides/board-operator/delegation.md`                                |
