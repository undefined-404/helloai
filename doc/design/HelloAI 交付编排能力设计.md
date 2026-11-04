# HelloAI 交付编排能力设计

> **Status**: `Planned`
> **Scope**：
> - **含**：项目级计划层（Plan 双表示 + 滚动式规划 + 版本化）、执行通道统一（`CodingAgentProvider`，首接外部 Agent）、代码工作区与 Git 权限分级、可执行验收机、并行冲突判据、约束上下文注入（Context Bundle）、成本预算内建。**首个垂直场景 = 代码开发**。
> - **不含**：自建代码编辑 / 终端执行内核与代码索引 RAG；Temporal 等工作流引擎；Document / PPT / Video / 3D 等其它产物类型的验收器实现（只立 `ArtifactVerifier` 形态）；物理设备（3D 打印）控制；多 Agent 并行合并队列（留待单 worker 闭环稳定后）。
> **差距锚点**: `G-027`
> **最后更新**: 2026-11-04
>
> 本文是**设计**（怎么建）。实施顺序与批次另立 `plan/`。来源：用户设想（2026-11-04，跨终端多 Agent 协作交付）+ 外部分析稿经逐项裁定后收敛，裁定明细见 §6。

---

# 1. 目标与边界

## 1.1 要解决的问题

平台已能完成「一次任务 → 拆子任务 → 派发 → 验收」的内循环，但**没有项目级载体**：

- 一份需求文档进来，只能拆成一棵**静态 DAG**，拆完即定稿——后续阶段的拆解无法基于「前一阶段已改完的代码」滚动生成；
- 代码类验收主要靠 LLM 判 `pass/score`（fail-open），缺「编译 / 测试退出码」这类确定性判据；
- 执行环境只有「对话 + 一次性沙箱」，没有可持有代码仓库、可隔离、可回滚的工作区模型。

**目标**：用户上传需求文档后，平台能「出总体计划 → 当前阶段细拆 → 派发执行 → 确定性验收 → 滚动进入下一阶段」。首场景为代码开发；计划层与验收层按产物类型抽象，为以后扩展到文档 / PPT / 视频预留形态（见 §5）。

## 1.2 成功的判据

| 判据 | 度量 |
|---|---|
| 计划可滚动 | Phase N 完成（代码已变更）后，Phase N+1 的子任务基于**新的** `baseCommitSha` 生成，且能证明不是旧计划的复读 |
| 验收可信 | 代码类验收的通过判据 = 验收机上的命令退出码 / 测试报告；LLM 只判「意图是否符合」 |
| 并行安全 | 两个子任务并行 ⇒ 其 `touchedPaths` 交集为空（平台确定性计算，非 LLM 判断） |
| 可追溯 | 任意交付物能回答：基于哪个 plan 版本、哪个 baseCommit、哪份约束版本、通过了哪些验证 |
| 成本可见 | 每个 Phase 的 token 消耗可汇总，超预算可熔断（复用 `G-026` 口径） |

## 1.3 明确边界（不做什么 → 依据）

| 不做 | 依据 |
|---|---|
| 不自建 IDE / diff 编辑器 / 代码索引 RAG | 借外部 Coding Agent 的上下文能力；自建属另一产品形态，且与本平台差异化（编排 + 验收 + 留痕）无关 |
| 不引入 Temporal 等工作流引擎 | 与现有 DB 状态机双写必分叉（判据 4）；真撞上「状态机无法恢复」再评估 |
| 不新建 `delivery` 领域 | 见 §3.2：归属现有 planner / task / review / agent 域扩展 |
| 不给 Agent `push` 主干权限 | 不可逆副作用；平台收 `commit hash + diff`，合并由平台 / 人执行 |
| 不上来就多 Agent 并行同仓 | 单 worker 闭环未稳定前并行 = 放大故障半径 |
| 项目工作台 UI 不先行 | 工作台是事件流投影；事实源未立之前做 UI 会倒推出错误的数据模型（判据 2「无读取方的列 = 反模式」的镜像） |

---

# 2. 现状实测（决定设计形态的事实）

## 2.1 已有地基（[实测]）

| 能力 | 实测位置 |
|---|---|
| DAG 依赖 + ready 守卫 | `SubTask.dependsOn`（JSONB `List<Long>`，`helloai-core/.../task/entity/SubTask.java:155-156`）；`SubTaskQueryServiceImpl.isReady`（前置全 DONE，`SubTaskQueryServiceImpl.java:277`）；派发侧 ready 守卫 `SubTaskDispatchServiceImpl.java:238/304` |
| 完成后自动推进 | `SubTaskCompletionListener`（完成事件 → 逐个尝试 `dispatchPendingSubTaskAuto`，未就绪自动过滤） |
| 产物类型抽象 | `TaskArchetype` 六类 BUILD / DOCUMENT / RESEARCH / DATA / OPERATE / DECIDE（`helloai-common/.../constant/TaskArchetype.java:17-35`），BUILD 注释即「验收靠编译 / 测试 / lint」 |
| 外部 Agent 通道 | `trae-excutor` / `TeleAgent-executor`，心跳在线门槛 + 离线重派（`G-014` / `G-015` 已完成） |
| 机器强制的约束样本 | `scripts/ci/check-arch-freeze.sh`（依赖方向守卫）+ `scripts/ci/ci-gate.sh` 五门禁 |
| 验收域 | review 域 35 类 + `RV-x` / `SR-x` 核验双域改造进行中（三态判定 / 覆盖批评器 / 判定快照 / 沙箱调用台账） |
| 事件流审计 | `agent_event` 主表 + Outbox 双写 |
| 成本可见性 | `G-026` 已完成（4 家供应商余额悬浮控件） |

## 2.2 三个硬缺口（[实测]）

| 缺口 | 实测 |
|---|---|
| **无计划实体** | planner 域 entity 只有 `ChatAttachment / ChatSkillRun / RequirementConversation / RequirementMessage`——任务之上没有「计划」这层 |
| **平台进程碰不到宿主仓库** | 全仓唯一 `ProcessBuilder` 是 `PgDumpRunner.java:300`（备份专用）；沙箱 `network-policy.default-action: deny`（`application.yml` `spec-presets.node`）、镜像 `node:20-bookworm-slim`、`/workspace` 默认不存在（`OpenSandboxExecutionService.java:586-591`）、「沙箱文件从不自动上传」（`SandboxExecutionService.java:75`） |
| **代码类验收无可执行判据** | review 判定 = LLM 输出 `pass/score`；`RV-x` 正在补静态校验与覆盖批评，但「在干净环境跑编译 / 测试」的验收机不存在 |

## 2.3 并发现状

派发以单个子任务为单位（`SubTaskDispatchServiceImpl` 无并发聚合），但 DAG ready 守卫意味着「多个同时就绪的子任务可同时在飞」——**并行在调度层已自然成立**。缺的不是并发原语，而是「并行是否安全」的判据（§3.6）。

---

# 3. 核心设计决策

## 3.1 D1 计划双表示 + 滚动式规划（Rolling-wave）

**决策**：计划实体一分为二——`Plan Document`（人读，可讨论修改）与 `Plan Specification`（系统执行，结构化 JSONB）。拆解不一次到最细：Plan 只到 Phase 级，Phase 开始时才由 planner 基于**当时的代码**生成该 Phase 的 DAG。

**为什么**：一次拆到底 = 计划必然过时。Phase 1 完成时代码已变，Phase 2 的细任务必须基于新代码分析，`baseCommitSha` 即体现这一点。

**版本化字段**：`planVersion` / `phaseVersion` / `baseCommitSha` / `constraintVersion`（见 §4）。已完成的 Phase 不因 Plan 改版而失去历史依据。

## 3.2 D2 不新建 `delivery` 域——归属现有域

| 新能力 | 落点 | 理由 |
|---|---|---|
| Plan / Phase 实体与生命周期 | `planner` 域扩展 | 「规划」本就是 planner 的职责；新域会让「计划」与「拆解」分家 |
| 工作区 / 分支管理 | `task` 域扩展（执行单元持有工作区句柄） | 工作区生命周期 = 子任务生命周期 |
| 可执行验收机 | `review` 域扩展（`ArtifactVerifier` 形态） | 验收规则已在 review；「怎么验」不该在别处 |
| 外部 Coding Agent 接入 | `agent` 域扩展（`CodingAgentProvider` SPI） | 与现有 executor 通道同源 |

**依据**：外部分析稿建议新建 delivery / plan / phase / execution / artifact / integration 目录——其未读现有代码。按本项目「不建超级 Service」与依赖链纪律，**扩展优于新建**；新域的判据应是「现有域装不下」，而这里装得下。

## 3.3 D3 验收机先行，可执行验收 > LLM 判断

**决策**：代码类验收的权威判据 = **验收机上的命令退出码**（编译 / 测试 / lint / 架构守卫），LLM 只回答窄问题「改动是否满足业务意图」。

三条铁律：

1. **验收必须在验收机上跑，不在执行 Agent 自己机器上跑**——「Agent 说它测过了」= 零信息（自述不算证据，同 `SR-x` 口径）。
2. 验收环境**容器化且规格固定**（镜像 / JDK / 依赖缓存策略平台统一），否则「A 机器过 B 机器不过」无法归因。
3. 验收失败 ⇒ 失败日志结构化回落为返工任务输入，不是让人去翻 CI 页面。

**形态**：`ArtifactVerifier` SPI（`supports(archetype)` + `verify(artifact) → Evidence[]`）。BUILD 类 = 编译 / 测试 / 守卫；其余 archetype 的 Verifier **现在只立形态不实现**（见 §5）。验收机是本设计的能力地基——没有它，「代码交付可信」无从谈起。

## 3.4 D4 执行通道：外部 Coding Agent 起步，ACP 形态预留

**决策**：首接**外部 Coding Agent**（复用现有 executor 通道：任务包下发 + 心跳 + 结果上报），不在平台侧自建代码执行内核。接口形态预留 ACP（Agent Client Protocol）式会话抽象：

```java
interface CodingAgentProvider {
    AgentSession createSession(CodingExecutionContext ctx);   // 含 repo / baseCommit / 约束版本
    AgentExecutionResult execute(AgentSession s, CodingTaskContract contract);
    AgentExecutionStatus getStatus(AgentSession s);
    void cancel(AgentSession s);
}
```

**取舍**：OpenHands 可当 Runtime 的**参考与候选 Provider**，但其 Workspace 抽象与本平台 OpenSandbox 是竞争关系（两套沙箱、两套回读、两套超时），**只取 ACP 接入思路、不接其运行时**。Trae / Qoder 为商业闭源产品，是否支持无头远程控制**必须单独实测**，不能默认（见 §7 V1）。

**依据**：本平台差异化在「编排 + 验收 + 留痕」，不在「当 IDE」；全仓唯一 `ProcessBuilder` 是备份专用（§2.2），平台进程不执行宿主任意命令的边界不破。

## 3.5 D5 Git 权限分级 + worktree 隔离

| 操作 | 权限 |
|---|---|
| clone / fetch | 允许（经 Git Gateway，短期最小权限凭证） |
| 创建任务分支 | **平台代为执行**（`task/<subTaskId>`） |
| 写工作区 | 只允许自己的 worktree |
| 本地 commit | 允许 |
| push 任务分支 | 可配置（默认允许，凭证回收即失效） |
| push `develop` / `main`、force-push、删他人分支 | **禁止** |

**形态**：`git worktree` 每子任务一个独立工作目录（共享对象库）；凭证不进 Agent 环境变量，走凭证管理服务短期下发。**并行前置条件 = 契约先行**：跨任务共享的接口（API 路径 / 请求响应 / 错误码）在并行前定稿，否则 Git 不冲突也可能语义不兼容。

## 3.6 D6 并行冲突判据 = touchedPaths 交集

**决策**：planner 产出子任务时必须带 `touchedPaths`（预计修改的文件集合，可粗粒度到目录）；平台计算两两交集——**非空 ⇒ 强制串行（改写 DAG 依赖），空 ⇒ 允许并行**。合并顺序 = DAG 拓扑序 + 合并队列：**每合并一支即重跑验收机**（Git 只解决文本冲突，不解决语义冲突）。

**依据**：确定性计算，不靠 LLM 自觉；「文本不相交」只是并行的**必要条件**而非充分条件。

## 3.7 D7 约束上下文：Context Bundle + 可信度分级

**决策**：不把所有文档塞给每个 Agent。按任务组装 `Context Bundle`：

```text
L1 项目宪章（技术栈 / 依赖方向 / 禁止事项）
L2 目标架构与基线（doc/ 根文档投影）
L3 目标模块约束（模块级说明）
L4 任务契约（目标 / 允许与禁止的修改范围 / 验收命令）
L5 运行时硬策略（命令白名单 / 沙箱策略 / Git 权限）
```

每份 Bundle 带 `constraintVersion` 与来源记录，事后能回答「它当时依据了哪些规则」。

**可信度分级**（决定设计重心放哪）：

| 约束方式 | 可靠性 | 本项目对应 |
|---|---|---|
| Prompt 文本 | 低（fail-open，判据 9） | 提示词规则段 |
| 文档 | 中 | `doc/` 体系 |
| 工具限制 | 较高 | worktree 只写、命令白名单 |
| 自动化验证 | 高 | `check-arch-freeze.sh` / `ci-gate.sh` |
| Git / CI 门禁 | 高 | 分支保护 + 验收机 |

**设计重心放在后三层**：Prompt 层只做「让模型知道」，拦截靠工具与验证。

## 3.8 D8 成本约束内建

**决策**：Phase 级 token 预算 + 超限熔断，复用 `G-026` 的口径与展示。**依据**：多 Agent 并行 + 滚动拆解 + 每步验收，token 消耗是单任务的 10~50 倍量级；外部分析稿完全未提成本，但它是平台级功能的前置约束，不是可选项。

## 3.9 D9 计划漂移判据

**决策**：Plan 有版本还不够，必须有「何时 replan」的信号。触发条件（其一命中即标记 `PLAN_DRIFTED`，由人决定是否重拆）：

1. `baseCommitSha` 之后的变更触及当前 Phase 的 `touchedPaths`；
2. 用户修改 Plan Document（版本 +1）；
3. Phase 验收失败的根因是「计划假设不成立」而非「执行质量」。

---

# 4. 数据模型（草样）

```json
{
  "planId": "…", "planVersion": 3, "goal": "…",
  "phases": [{
    "phaseId": "…", "phaseVersion": 1,
    "status": "NOT_STARTED | IN_PROGRESS | COMPLETED | BLOCKED",
    "dependsOn": ["…"],
    "acceptanceCriteria": ["…"],
    "baseCommitSha": "进入本 Phase 时的仓库基点",
    "constraintVersion": "本 Phase 使用的约束版本"
  }]
}
```

`Plan Document`（Markdown）由 Spec 投影生成，**不允许反向以 Markdown 为执行依据**——同一事实双写必分叉（判据 4）：Markdown 是读侧投影，不是第二事实源。

---

# 5. 交付物类型扩展路径（现在只立形态）

`TaskArchetype` 已提供类型维度（§2.1），每种产物的验收规则经 `ArtifactVerifier` 收口：

| archetype | Verifier 判据示例 |
|---|---|
| BUILD | 编译 / 测试 / lint / 架构守卫 |
| DOCUMENT | 结构 + 引用完整性 + 事实核验（与源码锚点对账） |
| DATA | 输入输出 schema 契约 |
| OPERATE | 时点 + 回滚节点 |

**现在不实现** BUILD 以外的 Verifier；先让代码场景闭环，再抽象共性——避免「先建框架后找场景」。

---

# 6. 外部分析稿的裁定明细（收敛记录）

来源：ChatGPT 综合分析（2026-11-04 用户提供）。

| 处理 | 内容 |
|---|---|
| **采纳** | 滚动式规划；Plan 双表示 + 版本化；约束可信度分级表；Git worktree + 权限分级 + Git Gateway；「CI 结果才是权威」；契约先行；项目工作台从事件流投影（不作为独立事实源） |
| **修正** | 新建 `delivery` 域 → 归属现有域（§3.2）；`Integration Agent`（LLM 判集成）→ 确定性集成检查为主（编译 + API 契约 diff），LLM 仅兜底意图；OpenHands 作 Runtime → 只取 ACP 思路不接运行时（§3.4）；Temporal「值得研究」→ 明确禁止现在引入 |
| **否决 / 推迟** | 现在铺 Document / PPT / Video / 3D 全家桶（先跑通代码闭环再抽象）；项目工作台 UI 先行（工作台是投影，事实源先行） |
| **其未覆盖、本设计补充** | 成本约束（§3.8）；计划漂移判据（§3.9）；验收机先行（§3.3 定为能力地基） |
| **可信度警示** | 其引用来源含 `images.openai.com` CDN 链接（非真实出处），`mini-swe-agent SWE-bench 74%` 等数字未经核实，**不采为论据** |

---

# 7. 待验证项（出 `plan/` 前必须回答）

| # | 问题 | 验证方式 |
|---|---|---|
| V1 | 外部 Coding Agent 能否以「任务包下发 + 结果回传」方式无头运行（Trae / Claude Code CLI / Aider 各自的 headless 能力） | 各取一家实测一轮 |
| V2 | `git worktree` + 平台代建分支 + 短期凭证下发的最小闭环 | 本机两个 worktree 并行实验 |
| V3 | 验收机容器形态（镜像带 JDK17 + Maven 缓存预热）能否在可接受时长内完成一次 `ci-gate` 级验证 | 容器实测计时 |
| V4 | `touchedPaths` 由 planner 产出的可用率（LLM 能否稳定给出路径集合） | 真实需求样本测试 |
| V5 | 接续语义：worker 中断后，事件流 + worktree 快照能否支撑另一 worker 接手 | kill 实验 |

---

# 8. 引用材料

- 差距锚点：`../HelloAI 实现差距表.md` `G-027`
- 关联差距：`G-007`（Quality Gate——验收机是其 Rule + Test 通道的承载）、`G-026`（成本口径）、`G-009`（Dynamic Workflow，长期编排）
- 判据来源：判据 4（同一事实双写必分叉）、判据 9（提示词约束是愿望）、`SR-x`「自述不算证据」口径
- 外部参考：OpenHands（ACP 接入思路）、`git worktree` 官方文档、GitHub merge queue 语义（每合并一步重验）
