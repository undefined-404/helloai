# Octop 源码借鉴分析 —— 沙箱 / 技能化 / Web Agent 接入

> **Status: `Analysis`**（借鉴预研，不改变任何实现、不承诺排期）
> **Scope**: 只回答「Octop 的哪些设计值得 helloai 借鉴」，不覆盖 Octop 全貌
> **分析日期**: 2026-10-09
> **勘察对象**: `~/Downloads/Octop-main`（TencentCloud/Octop `1.0.2b6`，main 分支快照，打包时间 2026-10-08 12:04）
> **关联**: [`Sandbox_Provider.md`](../design/Sandbox_Provider.md)（G-005）· [`Skill_Capability.md`](../design/Skill_Capability.md)（G-004）· [`../archive/implemented/HelloAI_Phase2_C3_BrowserAgent设计预研.md`](../archive/implemented/HelloAI_Phase2_C3_BrowserAgent设计预研.md)
> **勘察口径**: 下文凡标 `[实测]` 的论断均由本地读取解压源码/文档得出，附 `路径:行号`；标 `[推断]` 的为基于 Octop 侧用法与文档的反推，已单独说明。

---

## 0. 结论（先读这段）

1. **你点的三个薄弱项，Octop 确实都有成熟方案，但成熟度差别很大**：
   - **沙箱** —— Octop 的解法最完整（可执行的声明式 spec + 5 边界字段 + 容器生命周期 + 自检探针），而 helloai 目前**只有契约、零实现**（`ExecutionPolicy` 五个维度全是 `NONE`）。这是**补齐成本最低、收益最直接**的一项。
   - **技能化** —— Octop 的解法是「**目录即真相 + SKILL.md 包 + 两级治理 + 市场**」，helloai 是「**编译期硬编码 `KNOWN_SPECS` + classpath markdown**」。差距不在格式，而在**是否具备「可安装/可导出/来源可追溯/第三方摄入安全」这四个能力**。
   - **Web 端 Agent 接入** —— 两者**路线相反**：helloai 是「把任务推给外部 Browser Agent 服务、同步等结果」（形态 A 桥接）；Octop 是「平台自持浏览器 + WS 直播 + 人机控制权移交」。**Octop 的价值不是替你选路线，而是补齐你现在这条路线缺的三件事**（可观测、可接管、隔离态）。

2. **真正最该抄的不是功能，而是三条「工程纪律」**（第 5 章）：
   - **模型可传参的敏感参数，必须在框架层强制覆写**（`BrowserProfileMiddleware`）——helloai 的 MCP Server 现在正缺这一层，且代码注释已自认。
   - **工具治理用 denylist + 不可关闭清单**（`CRITICAL_TOOLS`）——比 allowlist 更抗漂移。
   - **设计文档先行、且带「已确认决策」枚举**（`docs/agent-interop-mailbox.md` §11、`docs/expert-teams.md`）——这份文档的形状本身就是可借鉴物。

3. **明确不要抄的**：ADR-001 单进程无队列、SQLite/PG 双后端、进程内 APScheduler、Python 网关原样移植。理由见第 6 章。

> **关于「harness 工程思维」的一个重要澄清**：`octop-harness` / `octop-gateway` / `octop-memory` / `octop-browser` 是 **PyPI 外部依赖，不在快照里**（`pyproject.toml:17-33`）。所以**我们读不到 harness 的源码实现**。本章所称的 harness 契约，来源是 Octop 自己写的**契约文档**（`docs/agent-interop-mailbox.md` 含完整 dataclass / Protocol / worker 流程）与**宿主侧适配代码**。这与「读过 harness 源码」有本质区别，结论强度不同——凡引用均已标明出处。

---

## 1. 勘察边界（先划清，避免错误结论）

| 项 | 事实 |
|---|---|
| 快照规模 | `src/octop` 656 个 py / 144,008 行；`tests` 501 个 py / 90,411 行 `[实测]` |
| 可读的业务包 | `infra/{backend,skills,connectors,browser,agents,knowledge,history,gateway,cron,db,backup,...}` 共 24 个 `[实测]`（`src/octop/infra/`） |
| **不可读**（外部依赖） | `octop-harness`（LangGraph 运行时）· `octop-gateway` · `octop-memory` · `octop-browser` `[实测]`（`pyproject.toml` dependencies） |
| 可读的契约文档 | `docs/` 共 21 篇 md（含 `adr/` 下 2 篇）`[实测]`（`docs/`） |
| 本次未采信 | 各类 star 数、外部安全事件等网络信息 —— 与「借鉴」无关，不引入 |

**这意味着什么**：Octop 的**业务侧设计**（沙箱 spec 怎么组装、技能包怎么校验、连接器怎么归类）我们可以逐行核实；但**运行时内核**（LangGraph 图怎么建、checkpoint 怎么落、token 怎么流）只能从它自己写的契约文档里读，不能验证实现是否一致。

---

## 2. Harness 工程思维：5 条可迁移的思维方式

Octop 的 harness 思维，本质是**「把多 Agent 协作的复杂度，压到最小的状态面上」**。以下 5 条按可迁移性排序。

### 2.1 状态面最小化：**只存路由，不存结果**

`docs/agent-interop-mailbox.md:49-68` 定义 `InboxMessage` 时有一条明确的决策注记：

> `target.call` 的结果与 `source` 合成回复都是 worker 内的**局部过程值**，不挂在 `InboxMessage` 上：不入库、历史靠 checkpoint，没有事后查结果的场景。

对应 §11「决策（已确认）」第 2、6 条：**纯内存、不入库**；`InboxMessage` 字段不保存 `result_text` / `reply_text`。

**为什么值得抄**：helloai 的 `TaskDispatch` 链路把「执行结果」落库（`ExecutionRecord`、`agent_event`），这是**正确的**——因为 helloai 有「事后查结果」的场景（报告、审查、回溯）。但**中间过程值**（本轮 turn 的临时拼接、review 的中间态）如果也落库，就会变成状态面的债务。**判断标准就一句话：有没有「事后查」的查询方**。有 → 落库；没有 → 只留在调用栈里。

### 2.2 新能力默认关闭：**通过注入启用，不污染默认工具集**

`docs/agent-interop-mailbox.md:164-174`：`HarnessAgentManager(team_processor=None)` 表示完全不建 inbox、`stream`/`call` 行为与重构前**逐位一致**；传入 processor 才启用。同时 `peer_agent` 工具被**从 `builtin/` 移出**到 `teams/`，理由是「不写进 HarnessAgent 默认工具」（`:202`）。

**为什么值得抄**：helloai 的 `SandboxProvider` / `SkillPackage` 都属于「新增能力」，若一上线就并入默认链路，会让**旧路径的回归验证失去基准**。Octop 的做法是：**默认行为必须是可证明不变的**，新能力只在显式注入时生效。这正好呼应 helloai 既有的「Run/Turn/Step 模型」——能力开关应挂在 Run 配置上，而不是改执行链默认分支。

### 2.3 串行消费者：**用「同 target 天然串行」代替分布式锁**

`docs/agent-interop-mailbox.md:151`：**全局单 worker 串行**消费，不按 target 分队列；同一 `target` 因此天然串行。后续 `docs/expert-teams.md`（"运行时"节）演化为「**inbox 按 callee 并发：同一成员串行，不同成员并行**」。

**为什么值得抄**：helloai 已经用 `@SchedulerLock` + Redisson 解决分布式互斥——**这条不要改**。可借鉴的是**粒度判断**：Octop 把「同一 Agent 不能被并发写同一 thread」这一约束，**收敛到收件箱的消费粒度**上，而不是散落到每个写入点加锁。helloai 的 `SubTaskReview` / `SubTaskClaim` 已有等价约束，值得反查一次：**是否所有「同 Agent 并发写」都被同一处挡住**，还是靠多把锁拼出来。

### 2.4 回叫闭环：**失败也必须由发起方收尾，禁止静默**

`docs/agent-interop-mailbox.md:148` worker 流程最后一行：异常 → `status=failed`，**仍然调用 `on_reply(status=failed, error_text=...)`**，§11 第 5 条明确「让 source agent 兜底说明」。同理 `docs/expert-teams.md`「已确认决策」第 15 条：

> 对方没运行则派工失败，主持人权走失败回叫，**不自动启动**。

**为什么值得抄**：这是 helloai 最该复用的语义。helloai 的三级容错（子任务核验链、报告审查链）已经解决了「失败怎么重试」，但**「失败之后由谁向用户解释」**是另一件事——Octop 的口径是：**派工方必须收到失败回叫并给出说明**，不允许任务静默丢失。「不自动启动」同样值得抄：**平台不应替用户拉起一个未运行的执行体**，那是权限边界。

### 2.5 房间模型：**复用 Thread，不引入新实体**

`docs/expert-teams.md`「身份模型」「房间与转播」两节：

> 团队不是平行实体，而是 `agents.kind = team` 的特殊专家；`team_id` = 主持人 `agent_id`。
> 成员编制只写在主持人工作区 `.octop/manifest.json`——**没有成员表**。
> `conversation_id` = 主持人 `thread_id`；成员 checkpoint = `conversation_id~成员id`。

**为什么值得抄**：这是一种**「用命名约定代替 schema」**的做法。helloai 的 planner → 子任务 → 执行 Agent 已经高度同构（planner ≈ 主持人、子任务 ≈ 派工），差别在 Octop 把「团队编制」这个**低写入频次、强所属关系**的数据放在**工作区的文件**里，而不是建成员表。这一点对 helloai 的**专家/团队**功能是直接可用的设计。

同时注意 Octop 对主持人做的**工具收窄**（`docs/expert-teams.md`："Octop 主持人装配"节）：

> `tools_disabled` 只保留 `agent_list` / `ask_agent` / 记忆 / `current_time`（含原本不可关的文件系统与 `task`）

**这是对 helloai 最有价值的一条**：调度者**必须**被剥夺动手能力，否则它会自己干活、不派工。helloai 的 planner 若发现「本该派出去的子任务被 planner 自己做了」，根因大概率就在这里。

---

## 3. 沙箱：Octop 的实现 vs helloai 的契约

### 3.1 helloai 现状（已核实）

| 组件 | 事实 |
|---|---|
| `ExecutionEnvironment` | 表达「在哪执行」——`local-process` / `remote-agent`（`agent/runtime/ExecutionEnvironment.java`） |
| `ExecutionPolicy` | 表达「隔离到什么程度」——文件/网络/进程/资源/凭证**五边界** × `ISOLATED`/`PARTIAL`/`NONE`（`agent/runtime/sandbox/ExecutionPolicy.java`） |
| `SandboxProvider` | `Sandbox resolve(SandboxContext)`，当前**唯一实现** `EnvironmentSandboxProvider` 只做环境路由（53 行） |
| **实现事实** | 代码注释自述：**当前无任何环境达到 `ISOLATED`，一律 `NONE`/`PARTIAL`**；[`Sandbox_Provider.md`](../design/Sandbox_Provider.md) 亦声明 `Status: Planned` |

也就是说：**helloai 的沙箱抽象已经设计对了（环境与策略分离、五边界、诚实标注），缺的是「让五边界真的成立」的那个实现。**

### 3.2 Octop 的沙箱形态（实测）

Octop 把执行环境做成一个**声明式 spec**，由数据（DB 行 / Agent 配置）驱动，运行时再解析：

```
storage_backends 表行  ──(adapter.py:44 row_to_backend_spec)──►  harness backend spec (dict)
agent.config.backend   ──(resolver.py:79  resolve_agent_backend_spec)──►  展开 named / composite
                                    │
                                    ▼
              resolve_backend(spec, workspace_dir=...)  ← harness 侧（外部包）
```

支持的 `type`（`src/octop/infra/backend/adapter.py:11-24` `[实测]`）：
`cos` / `s3` / `oss` / `obs` / `custom` / `filesystem` / `local_shell` / `postgres` / **`docker`** / **`opensandbox`**，另有两个**组合子**：
- **`named`** —— 间接引用（Agent 配置只写名字，真实 spec 在库里），便于集中轮换
- **`composite`** —— `default` + `routes{前缀→子spec}`（`resolver.py:103-117`）`[实测]`

> `composite` 是**一个虚拟文件系统的挂载表**：默认落到沙箱，`skills/` 前缀可以落到本地目录，`artifacts/` 前缀可以落到对象存储。**Agent 只看见一棵树，平台在下面拼。**

Docker 沙箱的完整字段（`src/octop/infra/backend/docker_spec.py:8-27` `[实测]`）与 helloai 五边界的**逐项映射**：

| helloai `ExecutionPolicy` 边界 | Octop docker spec 字段 | 语义 |
|---|---|---|
| `filesystem` | `workspace_path` · `volumes` | 容器内**同名绝对路径**、**不 bind-mount**；`volumes` 原样透传、**不自动挂 named volume** |
| `network` | **`allow_network`** | **默认 `false`**；需要 pip/curl 才显式打开 |
| `process` | `pids_limit` · `auto_remove` | PID 数上限；容器一次性/常驻由 `auto_remove` 决定 |
| `resource` | `memory` · `cpus` · `command_timeout` · `max_output_bytes` | 内存/CPU/单命令超时/输出字节上限 |
| `credential` | `env`（`execute_env.py:68-90`）· `environment_file` | **只注入 4 个变量**：`OCTOP_AGENT_ID` / `OCTOP_AUTH_DIR` / `OCTOP_HOME` / `OCTOP_SKILLS_DIR`；不继承宿主全量环境 |

另有一个**非 Docker 的轻量隔离**方案（`docs/agent-backend-file-io.md` §12）`[实测]`：

> Linux + `virtual_mode=True` + `root_dir` 非主机 `/` + 宿主有 `bwrap` ⇒ 走 `BubbledLocalShellBackend`，把 `execute`（含技能脚本）包进 **bubblewrap**：工作根绑到 `/`，与文件工具的虚拟路径对齐。

**这就是「沙箱不该只有一个 Docker 实现」的具体答案**：Docker 给强隔离，bubblewrap 给**能力降级时的最小可用隔离**，无 `bwrap` 时明确退化为**无目录狱**（并诚实标注）。

### 3.3 三个可直接搬的设计细节

**(a) `sandbox_scope`：容器复用策略 = 你缺的「权限与资源策略」**（`docs/agent-backend-file-io.md` §13）`[实测]`

| scope | 容器名 | 语义 |
|---|---|---|
| `agent`（默认） | `{prefix}_agent_{agentId}` | 一 Agent 一沙箱 |
| `user` | `{prefix}_{username}` | **同用户多专家共用**一个沙箱 |
| `fixed` | `sandbox_id` | 固定共享（需显式指定） |

生命周期口径极其克制：**没有则创建；`close()` / 删除专家都不删容器；只有显式 `destroy()` 才 stop+remove**。

> [`Sandbox_Provider.md`](../design/Sandbox_Provider.md) 的实施原则 4 说「安全沙箱的引入必须有权限与资源策略，而不是只增加一个 Docker 类」——`sandbox_scope` 正是那个「策略」的最小载体。

**(b) 自检探针：真跑一次写→读→删**（`src/octop/infra/backend/probe.py:118-213`）`[实测]`

`_probe_docker()` 的逻辑：确保镜像 → 建一个 `auto_remove=True` 的**临时容器** → `backend.write()` 写入探针串 → `backend.read()` 读回 → 比对内容 → `execute("rm")` → `destroy()` + 清理临时目录。

**这比「容器能起来就是好的」强得多**，也比 `docker ps` 强得多。helloai 的 `AgentSelector` / 执行体健康检查可以照抄这个形状：**探针必须做一次真实业务往返，并保证回收**。

**(c) `previewable`：隔离与可观测性显式二选一**（`docker_spec.py:39-51`）`[实测]`

Docker 后端默认**不让** Admin 浏览文件（只有 `fixed` scope 默认可浏览，或显式 `previewable: true`）；但「探测」按钮始终可用。**理由**：`agent`/`user` scope 的容器是运行时私有态，浏览会破坏隔离语义。

### 3.4 落地路线（对齐 helloai 既有设计，不改架构）

> 只写「做什么、验收什么」，不写工期。

| 步 | 动作 | 验收 |
|---|---|---|
| S1 | 给 `ExecutionPolicy` 补 `DockerPolicy` / `BubblewrapPolicy` 两个静态工厂，**只声明事实**；`EnvironmentSandboxProvider` 增加 `docker` 分支（先只做**解析**，不做执行） | 单测：`SandboxContext(docker 配置)` → 返回带非 `NONE` 五边界的 `Sandbox` |
| S2 | 定义 **`SandboxSpec`（声明式）**：`type` + 五边界字段 + `scope`；来源可以是 Agent 配置或平台默认（对应 Octop 的 `named` 间接层） | 同一份 spec 能渲染出容器创建参数；**配置可被单测断言**，不需要真起 Docker |
| S3 | 实现 `DockerSandboxProvider`：起容器 + 注入**白名单 env（≤4 个变量）** + `allow_network=false` 默认 + 资源上限 | 集成测试（Testcontainers）：容器内 `ls/read/write/execute` 可用；**宿主环境变量不出现在容器内** |
| S4 | 照抄 probe：**写→读→删的真实往返** + 保证回收 | 失败路径也必须 `destroy()`（用带清理的 try/finally 断言） |
| S5 | `scope`（agent/user/fixed）与容器生命周期：**不自动销毁，显式回收** | 单测覆盖「同 Agent 复用同一容器」「删除 Agent 不删容器」 |

**强烈建议**：S2 的 spec **必须声明式、可序列化**（不要写成 Java 里的一堆 `if (isDocker())`）。这正是 Octop 与 helloai `Sandbox_Provider.md` 两边的共识——**Runtime 不直接绑定 Docker/K8s API**。

---

## 4. 技能化：从「编译期常量」到「可安装的包」

### 4.1 helloai 现状（已核实）

| 维度 | 事实 |
|---|---|
| 载体 | classpath markdown：`helloai-core/src/main/resources/skills/plugins/eng-*.md`（**4 个**）`[实测]` |
| 注册 | **编译期硬编码**：`AgentSkillSpecServiceImpl.KNOWN_SPECS = knownSpecs()`（`skill/AgentSkillSpecServiceImpl.java:31`） `[实测]` |
| 元数据 | `SkillPackage` record：name/version/description/requiredTools/dependencies/inputSchema/outputSchema/validationRules/fileName `[实测]` |
| 文件格式 | **无 YAML frontmatter**，纯 markdown（`# 标题` + `## 执行速览` + `---` + `## 详细规范`）`[实测]` |
| 解析 | `required_skills`（任务声明）→ 标签命中 → 渲染速览段 → 拼进执行 Prompt；**纯函数式** `[实测]` |
| 缺失能力 | **无** 安装 / 导出 / 分享 / 市场 / 来源标记 / 每 Agent 覆盖 / 技能自带脚本 / 第三方摄入安全 |

对照 [`Skill_Capability.md`](../design/Skill_Capability.md) 的生命周期，helloai 只覆盖了 **Resolve**，`Discover` 和 `Load` 是空的。

### 4.2 Octop 的技能形态（实测）

**(a) SKILL.md 包格式**（`.cursor/skills/publish/SKILL.md`、`plugins/demo-greeting-skill/skills/polite-greeting/SKILL.md`）`[实测]`

```yaml
---
name: polite-greeting
description: >-            # ← 这是模型做「技能发现」时唯一看到的字段，必须写清「何时用」
  Use a concise, polite greeting ... Trigger this skill when the user says hello ...
metadata:
  octop:
    emoji: "👋"
---
# 标题 + 正文
```

`metadata` 命名空间**同时接受 5 个生态**（`skill/presentation.py:12` `[实测]`）：

```python
_EXTENSION_NAMESPACES = ("octop", "harness", "lightclaw", "orca", "openclaw")
```

**含义**：一份为别家写的 SKILL.md，只要用了这些命名空间，emoji/icon 照样能被读出来。**这是零成本的生态互通**。

**(b) 「目录即真相」**（`skill/workspace_catalog.py:131-170`）`[实测]`

技能目录扫描 `skills/` 与 `.octop/skills`，**没有技能表**。三个细节值得抄：

1. **`removed` 墓碑**：frontmatter 里 `removed: true` 的技能被跳过 —— 软删除靠**元数据**，不靠删目录。
2. **损坏不静默**：`SKILL.md` 非 UTF-8 时先尝试修复，修不了则以 `corrupt: true` + `error: invalid_utf8` **显式暴露**在列表里，不假装不存在。
3. **`enabled` 是「不在禁用集合」**：支持按 slug **或** `name` 禁用（`workspace_catalog.py:167`）。

**(c) 两级治理：全局技能包 ↔ Agent 工作区**

```
SkillPackageStore（全局共享包：DB 行 + 磁盘内容 + copy_policy）
        ▲  copy_package_skills_to_workspace / copy_workspace_skill_to_package
        ▼
{workspace}/skills/<slug>/（Agent 私有技能目录）
```

`copy_policy ∈ {snapshot, lock, deny}`（`skill/skill_package_store.py:32`）`[实测]`：
- `snapshot`：拷贝成独立快照
- `lock`：拷贝时在 SKILL.md 盖上 `origin: <package_id>` + `locked: true`（`skill/skill_transfer.py:54-72`）—— **来源可追溯 + 禁止被下游改坏**
- `deny`：不允许他人拷出

冲突语义也很干净：目标已存在**活跃**技能时**拒绝覆盖**（`SkillTransferConflict`），而不是静默合并。

**(d) 第三方摄入的供应链安全**（这是最该抄的一段）

`skill/skill_packages.py:10-11` 与 `skill/skillhub_market.py:189-240` `[实测]`：

| 检查 | 阈值/规则 |
|---|---|
| 文件数 | ≤ 2000 |
| 解压后总大小 | ≤ 64 MB |
| 单条目压缩比 | > 100 且原始 > 1 MB ⇒ **拒绝**（zip bomb） |
| 路径 | 绝对路径 / `..` / 盘符 / 反斜杠 / NUL ⇒ 拒绝 |
| 重复条目 | 拒绝 |
| 条目类型 | symlink / 非常规文件 ⇒ 拒绝；**加密条目 ⇒ 拒绝** |
| 读取时 | 边读边按**声明大小**设上限，读完校验 `total == file_size`（防尺寸谎报） |
| 清单 | 必须含根 `SKILL.md`，必须 UTF-8 |
| 形态归一 | 若整包只有一层包裹目录且内含 `SKILL.md` ⇒ 自动提升一层 |
| HTTP | 响应体 ≤ 32 MB，分块读 64 KB |

市场契约（`skillhub_market.py:32-42`）`[实测]`：`/api/v1/search`、`/api/v1/download`、`/api/v1/showcase/{hot,featured,newest,recommended,trending,paid}`。

**来源中立**：市场包、URL 包、CLI 安装目录、工作区上传，四种来源全部归一到同一个 `ResolvedSkillPackage(slug, files, source, source_url)`，再走同一个 `commit_skill_install(target, package)`（`skill/install.py:142-154`）`[实测]`。**装进 Agent 工作区还是装进全局包，只是换一个 target 实现。**

### 4.3 落地路线（对齐 `Skill_Capability.md` 的「先兼容、不建第二套运行时」）

| 步 | 动作 | 验收 |
|---|---|---|
| K1 | 给现有 4 个 `eng-*.md` **补 YAML frontmatter**（`name` / `description` / `version` / `required_tools`），保留正文不变 | 现有渲染行为不变（回归）；新增解析器能读出 frontmatter |
| K2 | `KNOWN_SPECS` 硬编码 → **目录扫描**（`skills/plugins/` + 可选外部目录），解析失败**显式报 corrupt** | 单测：坏文件出现在列表且带 `error`，不静默跳过 |
| K3 | 引入「**技能来源标记**」：`origin` + `locked`，拷贝进 Agent 工作区时打标 | 单测：带标技能被下游改写时能被识别 |
| K4 | **第三方摄入安全闸门**（照抄 4.2(d) 的表）：路径/类型/压缩比/大小/条目数/清单 | 针对每一类攻击各写一个**必失败**用例（zip bomb、`../`、symlink、无 SKILL.md） |
| K5 | 导出/安装闭环：`export`（打包为 zip）→ `install`（过 K4 闸门） | 端到端：导出再导入，技能可解析且 `requiredTools` 一致 |
| K6 | （可选）每 Agent 技能启用/禁用：启用 = 不在禁用集合（支持 slug 与 display name 两种匹配） | 单测覆盖两种匹配 |

> **不要做的**：不要为了技能化引入独立的技能运行时。Octop 的技能就是**目录 + markdown + 可选脚本**，脚本通过**已有的 `execute` 能力**在沙箱里跑。helloai 也应当如此——技能的「执行」是沙箱的事（第 3 章），不是新引擎的事。这正是 `Skill_Capability.md` 原则里那句「不因为 Capability Package 而建立第二套运行时」。

---

## 5. Web 端 Agent 接入：Octop 的三模式连接器 + 自持浏览器

### 5.1 helloai 现状（已核实）

```
AgentAccessType.WEB_BROWSER
   └─ BrowserAgentExecutor.execute()  ──►  BrowserAgentGateway.push(agent, task)  ──►  外部 Browser Agent 服务
                                          （HttpBrowserAgentGateway，同步等待 output 文本）
```
（`agent/executor/BrowserAgentExecutor.java`、`agent/browser/gateway/BrowserAgentGateway.java`）

这是 **「形态 A 桥接」**：平台把子任务推给外部自持 Playwright 的服务，同步等结果。代码注释亦自述「外部服务执行网页任务后回传结果」。

**这条路线本身没错**（省去平台级浏览器运维），但它天然缺三件事：
1. **不可观测** —— 平台看不到浏览器里发生了什么；
2. **不可接管** —— 遇到登录/验证码/二次确认，没有任何入口让人工介入；
3. **态不可控** —— 会话/登录态存在外部服务里，隔离边界不在平台手上。

### 5.2 Octop 的做法（实测）

**(a) 连接器的三模式模型**（`src/octop/infra/connectors/catalog.py`）`[实测]`

26 个目录条目，`mcp_mode` 分布：`gateway` 14 · `remote` 11 · `internal` 1。

| 模式 | 含义 |
|---|---|
| `remote` | harness **直接**连厂商的 MCP URL（标准 MCP client） |
| `gateway` | **Octop 进程内写一个 Python 适配器，把没有 MCP 的服务包成 MCP**（`gateway/protocol.py` 只用了 ~80 行实现 `initialize` / `tools/list` / `tools/call` / `ping`） |
| `internal` | Octop 自托管的 HTTP MCP（`/api/internal/mcp`） |

适配器契约极小（`gateway/registry.py:22-30`）：

```python
class GatewayAdapter(Protocol):
    def list_tools(self) -> list[dict[str, Any]]: ...
    def call_tool(self, creds: dict, name: str, args: dict) -> str: ...
    def probe_credentials(self, creds: dict) -> None: ...   # 自检
```

**这才是「对接 web 端各类 AI / SaaS」的通用姿势**：不去要求对方提供 MCP，而是**平台侧写薄适配器，把异构能力统一成 MCP**——对 harness 而言 26 个连接器长得完全一样。

鉴权也被声明化（`catalog.py:10-20`）`[实测]`：`AuthKind` 8 类，含 **`session_cookie`**（浏览器会话凭证）与 `oauth2` / `auth_code` / `imap_app_password`；`RemoteTransport` 3 类（`raw_http` / `streamable_http` / `sse`）。凭证表单本身也是**数据**（`ConnectorCredentialField`：key/label/field_type/required/placeholder/help/secret）。

**(b) 自持浏览器：直播 + 人机接管 + 录制回放**

helloai 缺的三件事，Octop 各有对应实现：

| helloai 缺口 | Octop 对应 |
|---|---|
| 不可观测 | `api/routers/browser/stream.py`：CDP **screencast WebSocket**，逐帧 base64 jpeg 推到前端；协议在文件头完整写明（`{"type":"frame"...}` / `{"type":"tabs"...}`）`[实测]` |
| 不可接管 | `api/routers/browser/harness.py:24-41`：**控制权归属** `_CONTROL_OWNERS[session_id] ∈ {agent, user}`，`HandoffBody{target, reason}`；注释明确它是**协调提示**（agent 在用户接管期间暂停交互），且**独立于实时会话存储**，所以仪表盘刷新 / WS 重连后接管状态不丢 `[实测]` |
| 录制回放 | `api/routers/browser/record_replay.py`（324 行）`[实测]` |

**(c) 一条通用的健壮性教训**（`harness.py:43-60`）`[实测]`：`_is_session_alive()` 用一个**零副作用的 `Runtime.evaluate("1")`** 去探测缓存的 CDP 会话是否还活着。注释解释了为什么必须这么做：

> 之前注册过的会话，底层 CDP WebSocket 可能已经死掉（浏览器崩溃 / OOM / 网络抖动），但 Python 对象永远留在 `_registry` 里。复用死会话会让后续每个动作都报 `no close frame received or sent` 这类低层错误。

**这条对 helloai 直接适用**：任何「缓存的长连接/执行体句柄」都必须有**廉价探活**，不能因为拿到对象就认为可用。helloai 的 `BrowserAgentGateway`（HTTP 桥接）与 `MCP` 会话都应加同形探活。

### 5.3 你必须先做的架构决策

**Octop 的浏览器是「平台自持」，helloai 的是「外部桥接」——这两条路线不能只抄一半。** 三种可选落点：

| 方案 | 做什么 | 代价 |
|---|---|---|
| **A. 只补观测与控制面**（推荐先做） | 保留外部 Browser Agent，但要求它**回传过程事件**（截图帧 + 当前 URL + 步骤），平台侧复用；并加一个**「请求人工接管」信号 + 人工指令回传**通道 | 需要外部服务配合改造；但不动平台执行链，风险最低 |
| **B. 平台自持浏览器** | 在 helloai 侧引入 CDP/Playwright 执行体，`browser_use` 类工具，profile 按 **用户维度隔离**（见 6.1） | 平台要承担浏览器运维、镜像、并发与资源治理；但观测/接管/隔离三件事一次性解决 |
| **C. 桥接 + 自持双形态** | 两者并存，按 Agent 的 `accessType` 路由（`BrowserAgentExecutor` 已经是路由器的一部分） | 维护两套；只在 B 稳定后再谈 |

**建议**：先按 A 补「可观测 + 可接管」——因为这两件事是**用户能直接感知的**，且不改变你已建好的调度/选人/心跳豁免链。B 作为独立排期，且**必须先把第 3 章沙箱做完**：自持浏览器本身就是「需要被沙箱化的执行体」。

---

## 6. 横切：两条必须抄的工程纪律（与功能无关）

### 6.1 模型可传参的敏感参数，必须在框架层强制覆写

`src/octop/infra/agents/middleware/browser_profile.py` `[实测]` —— 这是一个**无条件拦截 `browser_use` 工具调用**的中间件，它做三件事：

1. 从 LangGraph 运行时上下文取**当前用户 id**；
2. **覆写**工具参数里的 `profile`（模型传什么都被覆盖）；
3. 如果**取不到用户 id**，直接**拒绝这次工具调用**（fail-closed），返回 `status="error"`。

文件 docstring 一句话点题：

> Prevent model-selected profiles from crossing Octop user boundaries.

**为什么这条对 helloai 是「必须」而不是「值得」**：helloai 的 MCP Server 现在正是相反的做法——`McpMcpServer.java` 的类注释自述：

> 当前 `agentId` 字段由客户端通过 `@ToolParam` 传入。阶段会通过 spring-ai `McpSyncServerExchange` 从 Authorization 头提取真实 agentId，**覆盖客户端传入值，确保不可伪造身份**。

**这是一个已登记但未修复的身份伪造面**（10 个 MCP 工具全部依赖调用方自报 `agentId`）。Octop 的实现给出了完整答案，且形状可直接照抄：

- 覆写点选在**工具调用拦截层**（不是每个工具自己校验）；
- 参数**不可选**（不是「建议」，是「覆盖」）；
- **拿不到隔离键就拒绝**，而不是退化为默认值。

> 同一条纪律适用于**所有**模型可见的敏感参数：`agentId` / `tenantId` / `userId` / `workspaceDir` / `credentialId` / `profile`。**只要它是模型能填的，就一定有人在提示词注入下会去填别人的。**

### 6.2 工具治理：denylist + 不可关闭清单

`src/octop/infra/agents/settings/tool_catalog.py:10-19` `[实测]`：

```python
CRITICAL_TOOLS = frozenset({"ls", "read_file", "glob", "grep", "write_todos", "task"})
# "Tools that must remain available; ignored if present in tools_disabled."
```

三条设计合起来很讲究：
1. **默认全开**（denylist 而非 allowlist）—— 新增工具自动可用，不会因为忘了加白名单而「工具神秘消失」；
2. **不可关闭清单**容错——即使被写进 `tools_disabled` 也会被剔除（`normalize_tools_disabled` 里做 `names - CRITICAL_TOOLS`）；
3. **可用性另行判定**（`builtin_tool_available`）：按配置条件决定是否挂载（记忆开关、媒体开关、`web_search_tools`、`acp.tool_enabled`）——**「禁用了」与「不具备」是两个不同的事实**，Octop 分开表达。

对 helloai 的意义：`ToolRegistry` / `ToolDefinition` 已有元数据面，可补的正是**「不可关闭」与「条件可用」这两个语义位**。另有一个实际收益：planner 的工具收窄（见 2.5）用这套机制表达只需要一行配置。

---

## 7. 三档采纳清单

### A. 建议直接采纳（按 ROI 排序）

| # | 项 | Octop 出处 | helloai 落点 | 为什么排这里 |
|---|---|---|---|---|
| **A1** | **MCP 身份覆写中间件** | `agents/middleware/browser_profile.py` | `agent/mcp/McpAuthFilter` + 工具调用拦截 | **修一个已登记的安全缺口**，改动量小，形状可直接照抄（6.1） |
| **A2** | **沙箱 spec 声明化 + docker 五边界字段** | `backend/docker_spec.py`、`docs/agent-backend-file-io.md` §13 | `Sandbox_Provider.md` 的 S2/S3 | 把 `Status: Planned` 变成有实现的第一刀；五边界与既有 `ExecutionPolicy` **天然一一对应**（3.2） |
| **A3** | **沙箱自检探针（真往返 + 保证回收）** | `backend/probe.py` | 执行体健康检查 / 沙箱 Provider | 约 100 行，替换「能起容器就算好」的假验证（3.3b） |
| **A4** | **技能包摄入安全闸门** | `skill_packages.py`、`skillhub_market.py` | 技能导入链路（K4） | 一旦开放技能导入，**没有这层就是供应链漏洞**；规则表可直接抄（4.2d） |
| **A5** | **SKILL.md frontmatter + 目录扫描 + corrupt 显式化** | `workspace_catalog.py` | `AgentSkillSpecServiceImpl` | Discover/Load 从 0 到 1；保持「不建第二套运行时」（4.3 K1/K2） |
| **A6** | **CRITICAL_TOOLS + 条件可用** | `tool_catalog.py` | `ToolRegistry` | 让 planner 工具收窄（2.5）变成配置而非硬编码 |
| **A7** | **技能来源标记（origin/locked）+ 冲突拒绝覆盖** | `skill_transfer.py` | K3 | 技能可搬迁而不失溯源；防止下游改坏受管技能 |

### B. 需改造借鉴（能力缺口，Java 重实现）

| # | 能力 | 抄什么 | 不抄什么 |
|---|---|---|---|
| B1 | **连接器目录（三模式）** | 目录条目**声明化**（auth_kind / credential 表单 / transport / mode）+ `gateway` 模式：**平台侧薄适配器把非 MCP 服务包成 MCP** | 不要抄 26 个 Python 适配器；本地化只保留你真实要接的几家 |
| B2 | **浏览器可观测 + 人机接管** | 「控制权归属」这个**一等的状态**（agent / user）+ 接管状态**独立于实时会话**存储 + CDP 直播的帧协议 | 不必抄 screencast 的具体编码；先做「步骤事件 + 截图」即可（5.3 方案 A） |
| B3 | **缓存长连接探活** | `_is_session_alive` 的**廉价零副作用探针** | —— |
| B4 | **失败回叫闭环语义** | 「派工失败 ⇒ 派工方必须收到失败回叫并给出说明」「不自动拉起未运行执行体」 | 不要改成异步队列；你的 MQ 已经更强（2.4） |
| B5 | **两级技能治理（全局包 ↔ 工作区）** | `copy_policy{snapshot,lock,deny}` + 全局包 `created_by` 归属 + 管理员旁路 | 包存储位置可放 DB/MinIO，不必照抄「磁盘根 + DB 行」 |
| B6 | **知识库 RAG** | `citations`（引用可溯源）+ `gate`（注入闸门）：**先定「什么不许进上下文」，再谈检索** | OCR / 本地 ONNX embedding 可后置 |
| B7 | **ACP 双向** | 出站 `acpRunner` 的**会话状态机**（`list/start/message/respond/status/close`）+ **外部 Agent 的权限询问转发到对话里让人选** | stdio 传输可换成 HTTP 长连接 |
| B8 | **ADR 制度** | `docs/adr/00N-*.md` 的形状（Context / Decision / Rationale / Trade-offs / Consequences）+ **`Status` 字段** | 不必重构既有 `架构变更记录.md`，补 `Status` 即可 |

### C. 明确不要采纳

| # | 项 | 为什么 |
|---|---|---|
| C1 | ADR-001 单进程、无外部队列 | 与 helloai 已建的 **MQ + Outbox + 补偿巡检 + 幂等消费**完全相反。这是路线分歧，不是优劣。反向改会丢掉刚建成的三级容错 |
| C2 | SQLite / PostgreSQL 双后端 | 为双后端付出的代价（双份迁移、`?`→`%s` 边界改写、双备份格式）helloai 不需要。**PG-only 是优势不是负担** |
| C3 | 进程内 APScheduler | helloai 的 `@Scheduled` + `@SchedulerLock` 是多实例正确解；退回进程内调度会在多实例下重复执行 |
| C4 | Python IM 网关原样移植 | 语言不通，且 helloai 是**任务编排平台**而非聊天助手。若将来接 IM，应按「通道归一化管线」在 Java 侧重写 |
| C5 | 桌面端 / 移动端(adb) / 语音 / NAS 打包 / 自更新 / Let's Encrypt | 属于自托管个人产品的交付面，与 helloai 的定位无关 |
| C6 | 「用命名约定代替 schema」**无边界地**用 | 团队编制放文件是好的（低写入频次 + 强所属关系）；但**执行状态、认领、幂等**这类并发写 + 需要查询的数据，绝不能放文件 |

---

## 8. 落地顺序建议（与既有差距表锚点对齐）

```
第 1 步（安全，立刻可做）
  A1 MCP 身份覆写中间件          ← 补已登记缺口，不依赖任何其他改动

第 2 步（沙箱从 0 到 1）
  A2 沙箱 spec 声明化 ──► A3 自检探针 ──► Docker 实现（S2→S5）
  ↑ 必须先于「平台自持浏览器」（B2），因为浏览器本身就是待沙箱化的执行体

第 3 步（技能化从 0 到 1）
  A5 frontmatter + 目录扫描 ──► A4 摄入安全闸门 ──► A7 来源标记 ──► B5 两级治理

第 4 步（观测与控制面）
  B2 浏览器可观测 + 人机接管（方案 A）──► B3 探活 ──► 再评估是否平台自持

并行可做（工具治理）
  A6 CRITICAL_TOOLS + 条件可用 ──► planner 工具收窄（用配置表达，不改执行链）
```

**依赖关系的两条硬约束**：
1. **技能包的脚本要在沙箱里跑** ⇒ 技能化（第 3 步）的「执行」环节依赖沙箱（第 2 步）。若第 2 步未完成，第 3 步只做「安装/解析/校验」，**不要开放技能脚本执行**。
2. **平台自持浏览器是执行体** ⇒ 必须先有沙箱，否则等于把「运行任意网页驱动代码」直接放到平台主机上，正是 6.1 要防的形态。

---

## 9. 事实核查与未核实项

**已实地核实**（逐文件读取快照源码/文档）：

- 沙箱：`infra/backend/{adapter,resolver,docker_spec,probe}.py` 全文；`infra/agents/workspace/execute_env.py` 全文；`docs/agent-backend-file-io.md` 全文（含 §13 Docker sandbox backend）
- 技能：`infra/skills/{skill_packages,install,skill_transfer,skill_package_store,workspace_catalog,presentation,skillhub_common}.py` 全文；`skillhub_market.py` 的 zip 校验与 HTTP 客户端部分
- 连接器：`infra/connectors/catalog.py`（26 条目录 + 枚举）；`gateway/{protocol,registry,cli_runner,cli_fingerprint}.py` 全文
- 浏览器：`api/routers/browser/{harness,stream}.py` 头部与接管逻辑；`infra/agents/middleware/browser_profile.py` 全文
- 工具治理：`infra/agents/settings/tool_catalog.py` 全文
- 契约文档：`docs/{architecture,agent-interop-mailbox,expert-teams,acp}.md` 全文；`AGENTS.md` §1/§5/§7/§8/§10 全文
- helloai 侧：`agent/runtime/{ExecutionEnvironment,ExecutionPolicy,Sandbox,SandboxProvider,EnvironmentSandboxProvider}`；`agent/skill/*`；`agent/executor/BrowserAgentExecutor.java`；`agent/browser/gateway/*`；`agent/mcp/McpMcpServer.java`；`doc/design/{Sandbox_Provider,Skill_Capability}.md`

**未能核实**：

1. **`octop-harness` / `octop-gateway` / `octop-memory` / `octop-browser` 的源码不在快照内**（PyPI 依赖）。第 2 章的 harness 契约、第 3 章的「容器内同名路径」「bwrap 路由」、第 5 章的 CDP 会话管理，**实现一律未核实**，只核实了 Octop 侧的调用与文档描述。若要在这些点上做架构决策，需另行获取这四个包的源码。
2. **`InboxMessage` 的实际并发行为**（单 worker 串行 vs 后续版本按 callee 并发）：文档 §11 决策为「全局单 worker 串行」，但 `expert-teams.md` 描述为「按 callee 并发」——**两处文档不一致**，未去代码验证（代码在 harness 里，不可读）。上表按「文档所述」采信，**已标注不确定性**。
3. **快照无 git 历史**（`Octop-main.zip` 为 main 分支打包），无法判断各设计的演进顺序与稳定性。
4. 本次**未采信**任何网络来源的 star 数、安全事件、社区评价——与「借鉴设计」无关。

---

## 10. 一句话收尾

Octop 的代码在语言上对 helloai 没用一个字节有用；**但它把「沙箱该有哪些字段」「技能包该怎么校验」「没有 MCP 的服务怎么接进来」这三件事的答案写成了几乎可以直接照抄的形状**。真正值得抄的第一名甚至不是功能，而是那条纪律：**模型能填的参数，平台必须自己再填一遍。**
