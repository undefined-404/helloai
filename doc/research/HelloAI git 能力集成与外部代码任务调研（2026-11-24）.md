# HelloAI git 能力集成与外部代码任务调研（2026-11-24）

> **状态：Analysis**
> 最后更新：2026-11-24（**§7.9 新增「本地仓与远端仓的写者切分」**：双向同步会静默丢提交的三条实测佐证、按 ref 归属切分唯一写者、「发布」替代「同步」、confirmPlan 异步建分支、平台工作区落后缺口；§7.7 补 `GT-7`/`GT-8` 修正行，§7.8 待实测清单增至 6 项）
>
> **结论先行**：
> 1. **git 能力不能替代 G-037 第 3 项修复**——两者正交：第 3 项是「约束机制的完整性」问题（声明引导 / 执行侧路径 / coverage 引导），git 是「代码交付的信任与审计」问题。git 引入后第 3 项不改，`constraintRefs=[]` 与 coverage BLOCK 依旧发生。
> 2. **git 能力应当引入，且开源生态比沙箱集成时更成熟**——嵌入库选 **JGit**（纯 Java / Eclipse 基金会 / Gerrit/GitHub 后端在用 / 唯一支持 worktree 的主流库），宿主服务可选 **Gitea / Forgejo**（开源自托管，对应 OpenSandbox 的服务地位）。不是从零造轮子的大工程。
> 3. **多 agent git worktree 协作范式已被实证**：CMU CAID 论文（OpenHands 之上）用 worktree + 依赖图 + merge + 测试验证协调多 agent，Commit0 **+14.3%**、PaperBench **+26.7%** 绝对提升，跨三个模型族一致——与 G-027⑤⑥⑦ 设计几乎一一对应。
> 4. **「项目文档约束异质 agent」在业界已有标准答案**：Linux Foundation 旗下 **AGENTS.md** 标准（Codex / Claude Code / Cursor / Devin / Kimi / OpenHands 均原生读取），其核心纪律「Prose advises; code and sandboxes enforce」与 G-027⑦ 同频。
> 5. **「规划期任务依赖 / 前后矛盾校验」无开源可抄**，各厂商均靠执行期测试兜底；HelloAI 的 DraftGateChecker + confirmPlan 规划期静态闸在业界属领先位置，应作为差异化自研区扩展（另立专项，见 `design/HelloAI 任务依赖与矛盾校验设计（规划期双闸）.md`）。

> **Scope**：G-033 待办①「仓库注入适用性调查」的结论回填；为 G-027 的 Git 线（⑤ Git worktree 隔离 + 权限分级 + Git Gateway 短期凭证）提供形态定案输入；为「外部 agent 代码开发类任务」的三小步（JGit 嵌入 → 渐进式提交 + reviewer 沙箱验证 → 拉分支集成测试）提供调研锚点。**不含**实施任务分解与排期（属 `plan/`）。
> **调研口径**：外部生态结论基于 2026-11-24 联网检索（厂商官网 / 开源仓库 / 论文）；**2026-11-24 续研已对 JGit 发行 jar 做类级实测**（见 §7.2），其余仍为文档口径。实施前须按既有纪律补 [实测] 验证（同沙箱集成时的做法）。
> **续研订正（2026-11-24）**：§2.1「JGit worktree 全支持」经 jar 级实测**不成立**——JGit 7.8.1 无 `worktree` 命令类、不解析 linked worktree 的 `commondir`。GT-8 的隔离形态须改为「独立 clone」。两个开源项目的集成形态与「是否下载源码」结论见 §7。
> **差距表锚点**：G-033（承载体）、G-027（Git 线）、G-037（repoPath 字段承接）、G-036（C 档阻塞解除依赖 G-033）

---

# 1. 定位与背景

## 1.1 为什么现在调研（触发链）

1. **G-033 待办①**（差距表「执行环境维度」）：拆解/继承侧已闭环，执行侧未做（沙箱工作区仅 `/workspace/draft.md`，仓库不进沙箱）；派发校验未做（无 `ExecutionPrereqChecker`）。待办① = 「仓库注入适用性调查（技能型语义下成立与否 + 形态，结论回填本行现状列）」——本调研即该待办的结论来源。
2. **用户 2026-11-24 演进思路**（G-037 验证报告 §5 完整记录）：外部 agent 代码改造任务须带「git 地址 / 拉取方式 / 运行环境」前置准备；渐进式代码提交（代码即交付物）；reviewer 沙箱基于提交代码 + 同一规范验证；中期 git 组件集成（gittree 类）拉分支做集成测试；对照参照系 workbuddy / trae / claude code。
3. **本次直接触发**：用户评估「引入 git 能力是否使 G-037 第 3 项（三个最小缺陷修复）迎刃而解」——本调研的回答是**否（正交）**，且给出 git 自身应如何引入的形态定案。

## 1.2 背景事实（内部现状，实测口径）

- G-037 试点真机结论：约束文档 manifest 白名单 + 章节标题匹配 + 内外双通道读取全部真实闭环；**声明环未触发**（planner 输出 `constraintRefs=[]`）；外部组伪造留痕根因 = manifest 只有平台侧绝对路径、缺执行侧路径。
- 沙箱现状：OpenSandbox 卷挂载已通（`allowed_host_paths` 白名单 + `file:` 独立引用 + 卷内 `/workspace` 语义），外部 agent 已学会 `/workspace` 相对路径。
- 执行器形态：技能型执行（宿主循环 + 持久 shell + 文件工具直读），**无「repo 拷贝进工作区」环节**（对照 `research/沙箱内 LLM 执行流程三仓对照调研`）。
- 平台进程碰不到宿主仓库（唯一 `ProcessBuilder` 为 `PgDumpRunner` 备份专用）；`G-027` 设计已定 Git 九条（⑤ worktree 隔离 + 权限分级 + Git Gateway 短期凭证）。

---

# 2. 外部生态调研

## 2.1 嵌入式 git 库选型：JGit（首选）

| 库 | 语言/形态 | 关键能力 | 适配判断 |
|---|---|---|---|
| **JGit** | 纯 Java（Eclipse 基金会） | clone/fetch/commit/branch/diff 支持（**linked worktree 不支持**，7.8.1 jar 实测见 §7.2）；`FileRepositoryBuilder` / `DfsRepository`（可 S3/HDFS 背存储）；Gerrit 与 GitHub 后端在用 | **首选**。HelloAI 为 Java 栈（Java 17，JGit 7.x 的 `JavaSE-17` 要求正好匹配），进程内完成全部 git 操作、无 native 依赖、无 git binary 部署问题；隔离形态用「独立 clone」替代 worktree |
| go-git | 纯 Go | 95% 常用操作；**不支持 git worktree / 完整 submodule** | 排除（Java 栈无优势 + submodule 缺口） |
| libgit2 | C | 最全、安全审计最多；**含 worktree API**；GitHub/GitLab/VS 在用 | 需 native 绑定（JNI），部署复杂度高于 JGit，Java 栈无优势 |

> **worktree 订正**：原判断「JGit 唯一支持 worktree」有误。7.8.1 发行 jar 内无 `WorktreeAdd/ListCommand`，`commondir` 只在 `Constants`/`FS` 出现、仓库打开路径不解析，故 linked worktree 既不能建也不能完整读。真正有 worktree API 的是 libgit2（C）。HelloAI 选 JGit 的理由不变，但「worktree 隔离」改用独立 clone（见 §7.2）。

**关键纪律**（社区共识 + Pro Git 附录 B）：**绝不解析 git CLI stdout**（文本格式脆弱、错误恢复差），一律用原生库 API——JGit 天然满足。实施时注意 `index.lock` 并发互斥（HelloAI 多子任务并行场景须串行化对同一仓库的写操作）。

## 2.2 宿主 git 服务：Gitea / Forgejo（对应 OpenSandbox 地位）

- 开源自托管 GitHub 替代：仓库托管 + 分支/标签 + PR/合并权限 + WebHook + 短期 Access Token。
- 对应 G-027⑤ 的「Git Gateway 短期凭证」：平台代建分支、按任务签发短期 token、`worktree` 隔离、不 push 主干、收 commit hash + diff。
- 若目标仓库已在 Gitee/GitHub/GitLab 托管（如本仓 gitee.com/undefined_404/helloai），Gitea 只作「平台侧代理/网关」或直接透传远端——形态待实施批确定（先只读 + 分支，不引入完整镜像）。

## 2.3 厂商形态参照（抄语义，不集成——均为产品内建，无可复用组件）

| 产品 | git 相关形态 | 对 HelloAI 的启示 |
|---|---|---|
| Claude Code | 终端内直接执行 git 命令；`/commit` `/diff` `/undo`；CLAUDE.md 声明 commit 约定；attribution（Co-Authored-By） | 渐进式提交的交互语义；commit 约定进约束文档 |
| ClaudeShip（2026-11-23 发布） | CodeReview（对抗式校验三档）/ PRSteward（处理 CI 失败/冲突/评审意见）/ ClaudeApprovals（读 `.claude/approvals.yml`：`paths`+`verdict`+`seats`+`override`） | 审查层策略化：`override` 条件（如 `claude/code_review=pass` → 减人工席位）≈ HelloAI「kimi score≥阈值免人工」的规则化形态，可作 reviewer 门禁演进参照 |
| Codex | 三档沙箱（read-only / workspace-write / full-access）；原生读 AGENTS.md（32KiB 预算、最近文件优先） | 沙箱权限分级可参照；AGENTS.md 读取语义即「项目文档约束」的标准通道 |
| Qoder | Spec-Driven Development（先写 spec 再委派实现）；多智能体角色分工（规划/编码/测试/审查） | 与 HelloAI 两级拆解 + confirmPlan 同思想，验证方向正确 |
| Trae | IDE + Work 云端任务；规则/自定义 Agent 共享代码库索引 | git 能力是产品内建而非组件，参照形态即可 |

## 2.4 AGENTS.md 标准（★与用户核心洞察直接对应）

- 现状：Linux Foundation 旗下 **Agentic AI Foundation** 管理（2026）；Claude Code 读 CLAUDE.md（无则 AGENTS.md），Codex / Cursor / Devin / Kimi Code CLI / OpenHands / Gemini CLI 均原生读取。
- 内容定位：仓库根一份 Markdown，声明项目是什么 / 精确构建测试命令 / 布局 / 约定 / **边界（不许碰什么、先问谁）** / **「完成 = 哪些检查必须过」**。核心思想：**「one file for all agents」**——与用户「项目 doc 文档是项目级约束，不同厂商工具/不同 LLM 都能把代码写好，像团队临时加外部人员」完全同构。
- 关键纪律：**「Prose advises; code and sandboxes enforce」**——指令文件只负责「让 agent 知道」，硬性拦截靠权限 / 沙箱 / CI；嵌套文件（临近被编辑文件者优先）；保持精简（Claude Code 目标 <200 行、Codex 预算 32KiB）。
- **对 HelloAI 的映射**：manifest + `constraintRefs` 机制 ≈「平台管理的 AGENTS.md 集合 + 声明式引用」；差异仅在 HelloAI 是平台侧托管（白名单 + scope 裁剪 + 双通道注入），AGENTS.md 是仓库内文件。**git 落地后两者可融合**：规范文档随仓库版本一致（`repoPath`），外部工具天然读仓库根 AGENTS.md，等于把约束通道免费标准化——这也是 G-037 正式引入 §4「git 前置后」通道的业界背书。

## 2.5 CAID 论文：多 agent git worktree 协作已被实证（★G-027 的最强参照）

- 出处：CMU（Jiayi Geng 等）《Effective Strategies for Asynchronous Software Engineering Agents》，构建在开源 OpenHands 上，论文 + 博客公开。
- 范式：**manager 拆解任务并构建依赖图**（识别可并行 vs 必须等上游）→ 每个 engineer 一个**独立 git worktree** → 完成即 commit → manager **merge 回主干、解冲突、动态更新计划** → **测试套件在每步提供自动验证**。全部通信走结构化 JSON + git commits，不用自由对话。
- 结果：Commit0 **+14.3%**、PaperBench **+26.7%** 绝对提升，跨 Claude / GLM / MiniMax 三个模型族一致；单 agent 增加算力会进入平台期，多 agent 并行持续受益。
- **与 G-027 的对应**：① 计划层滚动式拆解 ↔ manager 依赖图+动态更新；② Git worktree 隔离 + 权限分级 ↔ engineer worktree；③ 验收机（命令退出码权威）↔ test-based verification；④ touchedPaths 交集判并行 ↔ 依赖图识别并行性。**设计方向已被学界实证，无需自证范式可行性。**

## 2.6 gittree 本体澄清

- 「gittree」在开源生态中指 git 客户端 / commit 图可视化工具（如 makalin/gittree 终端 TUI、git-tree-app/gittree 桌面客户端、GitTreeView 终端查看器），**均为客户端工具，不是服务端集成件**。
- 结论：HelloAI 要集成的是「**git 能力**」（沙箱内 clone/status/diff/commit 语义 + 平台侧 worktree/diff 审计），**不是 gittree 组件本身**；可视化成份（如需）由前端工作台承接，属后置项。

---

# 3. 为什么 git 不是 G-037 第 3 项的解药（正交论证）

| 第 3 项缺陷 | git 能解决吗 | 论证 |
|---|---|---|
| ① 声明引导偏差（planner 诊断类任务输出 `[]`） | **否** | 拆解 prompt 的宽松出口 + 示例偏差 + 缺默认注入——git 改变不了「planner 要不要声明约束」 |
| ② manifest 缺执行侧路径（外部 agent 猜宿主路径 → 伪造留痕） | **半** | git 的 `repoPath` 是②的更优形态（规范随仓库版本一致、零额外传输）；但②的根因是「外部 agent 缺不靠猜的取数通道」，`sandboxPath` 卷内相对路径今天就治，不依赖 git |
| ③ reviewer coverage 声明缺失（4 次转人工全此因） | **完全无关** | 审查端 prompt 引导问题 |

**反向依赖更要紧**：git 引入本身依赖约束机制——没有约束，「从 git 拉代码改完提交」只是「改了代码」，不等于「符合规范」。**次序 = 先有约束（第 3 项修复），后有 git 执行。** 两者不是替代关系，是承托关系：git 让「代码即交付物」成为可能（渐进式提交 + diff 审查），约束让「代码符合规范」可被判定（判据矩阵 + coverage）。

---

# 4. 三小步实施路径（形态定案）

> 以下为**形态定案**（调研结论），实施任务分解与排期见 `plan/HelloAI git 能力集成实施计划（2026-11-24）.md`。

## 第 1 步：JGit 嵌入（执行环境仓库化）

- **做什么**：沙箱工作区内引入 git 仓库语义；平台侧经 JGit 完成 clone（浅克隆/指定 commit 锁定）、工作区挂载；执行器在 `/workspace` 内获得 `repo`（git status / diff 可查）。
- **形态**：G-033① 调查结论——**「仓库注入」成立**，形态为「平台代 clone + 卷内 repo + 只读基线」；执行器仍走技能型执行（不改执行内核），git 语义只补「代码从哪来、改了什么可审计」。
- **约束文档随之就位**：`repoPath` 生效，规范随仓库版本一致（G-037 正式引入 §4 末行通道首次可用）。
- **护栏**：白名单仓库（防任意仓库拉取）；只读挂载共享目录防篡改基线；`index.lock` 串行化防并发写冲突。

## 第 2 步：渐进式代码提交 + reviewer 沙箱验证

- **做什么**：执行器任务中按阶段提交**代码文件本体**（java/js 等 + 改动说明），报告退居说明位；reviewer 在沙箱内**基于提交的代码 + 同一规范**做验证（编译 / 规范条款命中 / 测试），行为对齐 ClaudeShip CodeReview 的对抗式校验语义。
- **前置**：产物通道补「执行中代码文件」增量提交语义（现 `attachment` 方向已支持产物登记，需补执行侧代码文件通道）；`ExecutionPrereqChecker` 派发校验（缺 git 地址/拉取方式/环境 → 阻塞领取）。
- **审查参照**：ClaudeShip approvals.yml 的 `override` 规则可作为 reviewer 门禁策略化演进参照（后置）。

## 第 3 步：拉分支集成测试（承接 G-027⑤⑥）

- **做什么**：沙箱内拉取外部 agent 提交后的分支，基于最新代码做集成测试 / 调试；代码审查基于 commit diff 而非整包回传；多 agent 并行时按 touchedPaths 交集判串行、每合并一支重跑验收机（G-027⑥ 既定）。
- **形态**：平台代建分支 + 短期凭证（Gitea 或远端透传）；worktree 隔离（JGit 支持）；提交后收 commit hash + diff。
- **与 G-027 关系**：本步即 G-027 的 Git 线（⑤⑥）的实施承载体，G-027 的 V1~V5 验证（外部 Agent 无头运行能力、worktree + 代建分支 + 短期凭证闭环、验收机容器计时、touchedPaths 产出可用率、worker 中断接续）在本步内逐项完成。

---

# 5. 演进边界（只沉淀不立项）

| 项 | 承载体/说明 |
|---|---|
| AGENTS.md 生态对齐（约束文档入仓 / 外部工具原生读取） | 随第 1 步 `repoPath` 生效自然演进，不做独立立项 |
| 审查策略规则化（approvals.yml 式 override） | 待 reviewer 门禁数据支撑后评估（同 MA-9 口径） |
| 前端工作台 git 可视化（commit 图/文件树） | gittree 类 UI 后置，不参与执行链 |
| 任务依赖/矛盾规划期校验 | 另立专项：`design/HelloAI 任务依赖与矛盾校验设计（规划期双闸）.md` |

# 6. 关联

- 差距表：`G-033`（本调研即待办①结论，回填现状列）、`G-027`（Git 线承载体）、`G-037`（repoPath 承接）、`G-036`（C 档阻塞解除依赖 G-033）
- 设计：`design/HelloAI 交付编排能力设计.md`（Git 九条）、`design/HelloAI 任务约束类型化设计（正式引入）.md`（§4 末行 git 通道）
- 报告：`review/HelloAI G-037 任务约束真机验证报告（内外组对比，2026-11-24）.md`（§5 演进思路）
- 实施：`plan/HelloAI git 能力集成实施计划（2026-11-24）.md`
- 外部来源（2026-11-24 检索）：agentfriendlycode.com（AGENTS.md）/ agenticdevelopercookbook.com（指令文件）/ developer toolkit（AGENTS.md 分层）/ Anthropic ClaudeShip 发布报道 / openhands.dev CAID 博客与论文 / Pro Git 附录 B（嵌入 git 库）/ gittree 各开源仓库

---

# 7. 两个开源项目的集成形态（2026-11-24 续研，含 jar 级实测）

## 7.0 「两个开源项目」指代与范围

本文可集成的开源项目只有两个：**JGit**（嵌入库）与 **Gitea / Forgejo**（宿主 git 服务）。其余被引用者均**不是集成对象**：

| 名称 | 性质 | 是否进代码/部署 | 说明 |
|---|---|---|---|
| JGit | Java 库 | **是**（Maven 坐标） | 进程内 git 操作，替代 git binary |
| Gitea / Forgejo | 服务端产品 | **第 3 步按需**（容器镜像） | 分支隔离 / 短期凭证 / PR；本轮不部署 |
| OpenHands + CAID | 论文 + 参照实现 | 否 | 只抄「依赖图 + worktree + merge + 测试」范式 |
| gittree 各仓 | 客户端 UI | 否 | 可视化成份后置，不参与执行链 |
| AGENTS.md | 仓库内约定文件 | 否 | 随 `repoPath` 生效自然演进 |

**两个都不需要下载源码**。接入方式分三类：

| 形态 | 接入方式 | 何时才需要源码 |
|---|---|---|
| 库（JGit） | Maven 坐标，构建时自动拉 jar | 只有要打补丁 / 调试库内部时才需要 |
| 服务（Gitea） | 官方容器镜像 | 只有要改服务端行为时才需要 |
| 范式（CAID） | 读论文/博客 | 不需要 |

## 7.1 JGit 依赖事实（[实测] jar 元数据）

- Maven Central 最新版：`org.eclipse.jgit:org.eclipse.jgit:7.8.1.202611041320-r`（metadata `lastUpdated=2026-11-04`）。
- 该 jar 的 `MANIFEST.MF`：`Java-Version: 17` / `Bundle-RequiredExecutionEnvironment: JavaSE-17` —— 与根 POM `java.version=17`（`pom.xml:15`）完全匹配，无升级 JDK 压力。
- HTTPS 远端只需一个 artifact；SSH 远端再加 `org.eclipse.jgit:org.eclipse.jgit.ssh.apache`（本仓目标仓库是 Gitee HTTPS，暂不需要）。
- 落点建议：根 POM 加 `<jgit.version>` 属性 + `dependencyManagement` 条目（对齐 `opensandbox.version` 的做法，`pom.xml:61`），`helloai-core/pom.xml` 直接依赖。

## 7.2 ★实测订正：JGit 不支持 linked worktree

对 7.8.1 发行 jar 做类级扫描，三项证据：

1. `org/eclipse/jgit/api/` 下无 `WorktreeAddCommand` / `WorktreeListCommand` / `WorktreeRemoveCommand`；
2. 整个 jar 无 `worktrees/` 管理目录字符串（`git worktree` 的元数据落点）；
3. `commondir` 只出现在 `Constants` / `FS`，仓库打开路径（`BaseRepositoryBuilder`）不解析它——linked worktree 的 `.git` 文件指向 `.git/worktrees/<name>` 后，无法回溯主仓 objects/refs。

因此 `GT-8`「每 agent 一个 worktree」**不能靠 JGit API 直接实现**。三个可选形态：

| 形态 | 做法 | 评价 |
|---|---|---|
| **A 独立 clone（推荐）** | 平台建一个本地 bare 仓为源，每 agent `JGit.clone` 一份完整工作区 | 隔离最彻底；天然规避「同一分支不能两处 checkout」；代价是磁盘 O(repo×agent)。任务内 2~5 个 agent 量级完全够 |
| B JGit + 宿主 git CLI | `git worktree add` 由宿主进程执行 | 引入 git binary 部署前置，与「不解析 CLI 输出」的纪律精神相悖（执行≠解析，但仍是新增外部依赖） |
| C libgit2 / JNI | 用 libgit2 的 worktree API | 能力最全，但 native 绑定 + 多平台分发，部署复杂度最高 |

建议 `GT-8` 改写为「**独立 clone + 合并队列**」，合并队列与 touchedPaths 串行判据不变。

## 7.3 JGit 落点与边界（与现有代码对齐）

| 事项 | 结论 | 证据 |
|---|---|---|
| 依赖声明 | 根 POM 属性 + `dependencyManagement`；`helloai-core` 引用 | `pom.xml:15`、`helloai-core/pom.xml:136` 附近 |
| 代码落点 | 新增 `com.helloai.core.agent.runtime.git`：`GitGateway` 端口 + `JGitGateway` 适配器；其他域只依赖端口，不 import `org.eclipse.jgit` | 与 `sandbox` 端口/适配器同构 |
| 架构守卫 | `check-arch-freeze.sh` 只统计**跨域 import**，**不拦第三方包** → 需新增一条「`org.eclipse.jgit` 只许出现在 git 包」规则，挂在 ci-gate 门禁 3 | `scripts/ci/check-arch-freeze.sh:1-30`、`scripts/ci/ci-gate.sh:178` |
| 单一写入者 | **平台 JGit 是唯一 git 写者**；沙箱只写文件、不跑 git（理由见 7.4②） | 见下 |
| diff 计算 | 用 base commit tree 对工作区 `FileTreeIterator` 走 `DiffFormatter`，**不读写 index** → 与沙箱并发写文件不抢 `index.lock` | JGit API |
| 进程面 | 不新增 ProcessBuilder；JGit 是进程内调用（现状唯一 ProcessBuilder 是 `PgDumpRunner`） | 计划 §2 #3 |

## 7.4 与现有实现的三处硬冲突（GT-2/GT-3/GT-4 的真实工作量）

### ① 卷是「全局单目录」，无法按任务/agent 隔离

`application.yml:198-201` 只有一份 `volume.host-path`，`SandboxFactory.applyWorkspaceVolume()`（`SandboxFactory.java:136-160`）直接读全局配置——**今天所有任务若开卷就是同一个目录**。

→ GT-2 必须先补「按 `WorkspaceRef` 解析宿主目录」：新增 `workspace-root` 配置，目录取 `{root}/{kind}-{ownerId}`，`SandboxFactory.create()` 改为接收解析后的 hostPath。槽键已带 kind/ownerId/spec/access（`SandboxWorkspaceRegistry.java:70-85`），改动集中在 factory 签名与调用点，不需要重新设计槽池。

### ② 沙箱内不能 clone：无 git 二进制 + 默认断网

- 预设镜像 `node:20-bookworm-slim`（`application.yml:204`）及 python 镜像均**不带 git**；
- 预设 `network-policy.default-action: deny`（`application.yml:209`）——沙箱**拉不到远端**。

→ 结论：clone 必须在**平台侧 JGit** 完成再挂进沙箱（与计划形态一致）；`GT-3` 的 `internal_git_status/diff` 也应是**平台侧工具**（读挂载目录），而不是沙箱内命令。计划里「执行侧工具」的表述需按此收窄。
→ 运维前置：新仓库根目录必须进沙箱服务器 `[storage].allowed_host_paths`（`opensandbox-config.toml` 末行），改配置后需 `--force-recreate`；当前白名单只有 `E:/yhzx/1027/unip-review-workspace`。
→ **前缀语义已核（2026-11-24 官方文档）**：该字段是「host path **prefixes** allowlist，只有其下的路径才能挂载」，即登记 root 一次即可覆盖 `{root}/{taskId}/...` 子目录，不必逐任务登记。
→ **版本陷阱**：官方 latest 文档写「空列表 = 全部放行」，而本仓钉住的 release-1.1.0 实测是「空列表 = 全拒」（server breaking change #750/#751）。以部署版本实测为准，不要照 latest 文档把白名单清空。

### ③ 挂到 `/workspace` 会污染交付物回读

`DeliverableReader` 在 `/workspace` 下按 depth=2 挑**最新 `.md`**（`DeliverableReader.java:43/62/107`）。仓库一旦挂进来，`README.md` / `CLAUDE.md` 就可能被当成本轮交付物捞走。

→ 挂载点建议 `/workspace/repo`（不是 `/workspace`），并给 `DeliverableReader` 加「跳过 repo 子树」；`sandboxPath` 语义相应变为 `repo/doc/…`（相对 `/workspace`，`ConstraintDocCatalog.java:216-217` 已透出字段，无需改表）。

### Windows 宿主额外风险（本地 dev 专用）

- bind mount 的 mode / symlink 语义与 Linux 不一致 → clone 后显式 `core.fileMode=false`、`core.autocrlf=false`，否则 diff 噪声；
- NTFS 挂载 I/O 慢 → 本地只做功能验证，性能结论留到 Linux 服务器再测。

## 7.5 Gitea / Forgejo：前两步不需要，第三步按需

| 阶段 | 是否需要 Gitea | 替代做法 |
|---|---|---|
| 第 1 步 clone 基线 | 否 | JGit 直接 clone 远端（只读、commit 锁定） |
| 第 2 步 渐进提交 | 否 | 平台本地 bare 仓 + JGit commit（不 push） |
| 第 3 步 分支集成 | 视需要 | **A**：平台自持 bare 仓，agent 永不 push，合并后由平台推远端（零新服务、安全最强）；**B**：自托管 Gitea/Forgejo 提供 per-agent 短期 token + 分支隔离 + PR 界面 |

选择判据：只要「分支隔离 + 审计」→ A 足够；要「agent 直接 push + 人审 PR + 多团队」→ 才上 B。B 的部署形态用官方镜像（不下载源码），与 `opensandbox-net` 同网，DB 可复用现有 PG。**本轮（第 1~2 步）不部署，计划 §0.3「完整 git 服务托管不做」结论维持。**

## 7.6 多 agent 接力下的分支谱系（2026-11-24 续研，含 ref D/F 实测）

> **本节回答**：同一 Task 的子任务并行派给多个外部 agent、以及「一个子任务被不同 agent 接力执行」两种情形下，分支该如何组织。**只给调研结论与建议先后顺序；正式任务分解、编号、验收与排期由后续 `plan/` 决定。**

### 7.6.1 先厘清两件事，它们不是同一个原语

| 现象 | 数据模型上的实际动作 | 是否新建 Task | 现有入口 |
|---|---|---|---|
| 一个子任务换执行者**重跑** | 同一 `sub_task` 行改 `assigned_agent_id` | **否** | `rework` / `reworkFresh` / `redispatchDeadLetter` |
| 一个子任务**拆出去**做 | `INSERT task`（`parent_sub_task_id` 锚定、`depth+1`） | **是** | 草案期派生 / 执行期重构（`ADR-008`） |

`[实测]` `agent_execution_record` 在 `sub_task_id` 上**只有普通索引、无唯一约束**（`V1__init_all.sql:643` `idx_exec_record_sub_task`），因此「同一子任务被多个 agent 先后执行」在数据层本就支持、历史全留。

⇒ **推论**：「换 agent 接力」不需要新建分支层级体系，它需要的是**同一子任务下的多次尝试有各自的代码起点**。这与「派生」是两个正交维度。

### 7.6.2 ★ref directory/file 冲突实测（决定命名形态的硬约束）

本地 git 2.55.0 实测三组：

| # | 操作序列 | 结果 |
|---|---|---|
| 1 | `git branch helloai/task-1` → `git branch helloai/task-1/sub` | **RC=128**，`cannot lock ref '.../task-1/sub': 'refs/heads/helloai/task-1' exists` |
| 2 | `git branch helloai/task-1` → `git branch helloai/task-1.d1-22` | **RC=0**，两者共存 |
| 3 | 先建 `helloai/t9/main` + `helloai/t9/d1-22` → 再建 `helloai/t9/d1-22/s1`；再试建 `helloai/t9` | 前者 **RC=128**（`d1-22` 已占该路径）；后者 **RC=128**（`t9` 已被当目录） |

**结论**：git ref 存储是**一层目录树**，任一中间段一旦被用作分支名，其下**永远不能再建子段**（D/F 冲突），反向同理。因此：

- ❌ 「分支下面建分支」（`helloai/task-1/s1`）在 git 层不成立，与 JGit 无关；
- ❌ 层级嵌套会让分支名长度随派生深度增长，且父分支一建就锁死子空间；
- ✅ **用扁平单段名把两维压进一个名字**，分支名长度与派生深度**无关**。

### 7.6.3 建议形态：扁平命名 + 层级交给 merge DAG

```text
helloai/t{taskId}                     每个 Task 一条汇总分支（taskId 全局唯一 ⇒ 派生 Task 零命名冲突）
helloai/t{taskId}-s{subTaskId}-a{n}   子任务第 n 次尝试分支（n 从 1 起，只增）
```

- **为什么 taskId 足够**：`task.id` 全局唯一，派生 Task `Y` 天然拿到 `helloai/t{Y}`，**不需要把 depth 编进名字**；3 层派生与 1 层派生的分支名形态完全一致。
- **为什么 `-` 不用 `/`**：见 7.6.2 实测 1/2——`-` 是同一 ref 名内的字符，`/` 会创建目录段。
- **层级体现在 merge DAG 而非 ref 名**：

```text
master
  └─ helloai/t{X} ──●──●──────●(merge Y)────────●
                       \      /                  \
                        ●────● (A1 子任务 commit)  \
                                                   \
     helloai/t{Y} ──●────●──────●(merge Z)
                        \     /
                         ●───●  (B1 子任务 commit)
```

- **合并方向唯一**：`a{n}` → `t{task}` → 父 `t{X}` → … → `master`；**仅 `depth=0` 根 Task 分支可直连 `master`**，派生分支永不直连。方向唯一 ⇒ 不存在「派生分支与 master 平级、先后不明确」的歧义。
- **子任务默认不建分支**：同 Task 内子任务串行时用 `SubTask-Id: <id>` commit trailer 标注，reviewer 按 trailer 分组审 diff。分支只在该子任务**存在多次尝试**或**被并行派给多个外部 agent** 时才出现。

### 7.6.4 attempt 起点策略：按失败语义定，不让人猜

| 触发入口 | 失败语义 | 建议起点 |
|---|---|---|
| 租约超时 / 离线回收 | **未跑完**（中断，非质量问题） | **continue**：从 `a{n}` HEAD 切 `a{n+1}` |
| 审核驳回返工 | 这一版不合格 | **fresh**：从 `t{task}` 基线切 `a{n+1}` |
| 人工驳回改派 | 人裁定 | **人显式选**（continue / fresh） |
| 死信改派 | 机器判不了 | **人显式选**（continue / fresh） |

- **两条路共用同一命名空间，只差 start point**——fresh 与 continue 产出的都是 `a{n+1}`，形态一致。
- **旧分支永不删除**：`a1` 保留为审计证据，reviewer 对比 `a1` vs `a2` 的 diff 即可判「新 agent 是**修复**还是**重写**」。这是多 agent 接力最该留的证据，也是「代码即交付物」在本场景的具体含义。
- **不建议默认 continue**：死信/驳回的语义是「这一版不行」，在旧代码上继续容易继承错误假设；但也**不建议默认 fresh**——人工介入点必须给选择权，否则人无法表达「只要它补个测试」这类局部意图。

### 7.6.5 与现有实现的四处真实冲突（[实测] 代码位置）

1. **旧提交失效的等价物不是删分支**：`invalidateAttachmentsOnRework` 在返工时把旧提交附件全置 `INACTIVE`（`SubTaskStateMachineServiceImpl.java:594` 附近）。代码走分支后，等价动作应是给 `a{n}` 打 `rejected` tag——**保留可审计，但明确不作当前交付基线**。删分支会同时删掉审计证据。
2. **两套计数器必须分开**：`reworkFresh` 与 `redispatchDeadLetter` 都调用 `resetAttemptTotal` 清零 `attempt_total`（`SubTaskStateMachineServiceImpl.java:590`、`SubTaskDispatchServiceImpl.java:405`）。若分支代次复用该计数器，则「预算已清零」与「分支已到 `a3`」语义对不上；若分支编号跟着回 `a1`，则发生覆盖。⇒ **分支代次用独立只增序号，不复用 `attempt_total`**。
3. **「当前有效分支」是投影，不是新事实源**：谁审哪份代码应从最新 `agent_execution_record` 推导，**不得新增 `current_branch_seq` 列做第二事实源**——与 `ADR-008` §14「双向关联只存单向」同一个坑（同一事实双写必分叉）。
4. **平台内部不存在并行**：`WorkspaceRef.Kind.TASK` 是 `task:{taskId}` 级共享（`WorkspaceRef.java:121-125`），`SandboxWorkspaceRegistry` 槽内命令**串行执行**（`SandboxWorkspaceRegistry.java:46`）⇒ 同 Task 子任务在平台沙箱里天然串行，建子任务分支压不出并行度。**并行是外部 agent 的固有属性**（它们经 `claimSubTask` 在本机执行，不进服务器沙箱）。这个定位差别很重要：子任务分支不是「平台的并行需求」，是「外部 agent 的隔离需求」；搞错会去改沙箱作用域做无用功。

### 7.6.6 派发载荷必须显式写死的四项

外部 agent 在自己机器上执行，平台无法约束其行为，只能在**派发时把 git 语义写明白**：

| 字段 | 含义 | 缺项后果 |
|---|---|---|
| `repoUrl` | 远端地址 | agent 自己去猜/伪造，留痕不可核 |
| `baseSha` | 基线 commit（clone 锁定点） | 不同 agent 从不同基线开工，diff 不可比 |
| `branchName` | 该 agent 应推的分支（扁平名） | 互相覆盖，或推到主干 |
| `startFrom` | `continue` / `fresh` + 参考分支 | 人无法表达「在驳回代码上改」还是「重来」 |

⇒ 缺项时应**阻塞派发并带原因**，而不是让 agent 自行决定。这与 G-033 待办②「无原因 `PENDING`」是同一类缺陷。

### 7.6.7 与既有设计的一致性

- `ADR-008` §13.3 允许同一子任务**重复派生**（`Y` 失败再派 `Z`）：每次是**新 Task、新分支**（`helloai/t{Y}` / `helloai/t{Z}`），旧分支按遗弃处理，**绝不在同一分支上继续叠 commit**。
- `ADR-008` §18.2 定「派生 = 同库单事务」：git 是外部 IO，**不得塞进派生事务**，否则该单事务失去原子性。分支创建应放在**首次需要工作区时**（`WorkspaceRef` 解析到宿主管时）lazy ensure，幂等收敛。
  > **【口径订正 2026-11-24 晚二】** 上句「lazy ensure 作为主路径」已作废——它在**纯外部 agent 任务上永不触发**（外部 agent 不从平台工作区取代码）。正解见 §7.9.4：**`confirmPlan` 落意图 → job 异步建分支，lazy ensure 降为兜底**。「不得进派生事务」的结论不变。

### 7.6.8 建议先后顺序（非排期）

1. **先做扁平命名的建分支能力**（与 clone/挂载同批，同属「仓库就位」链路）——没有它，后续所有多 agent 场景都无从谈起。
2. **再做 attempt 起点策略与分发载荷四项**——两者同属「派发契约」，一起改才不必二次改调用点。
3. **最后做沿派生边合并 + 冲突中止 + 旧分支标 `rejected`**——依赖前两步产出的分支。

> **可并行**：容器形态工作区可见性与上述三项无耦合，可独立推进。**前置依赖**：三项都依赖「平台侧 JGit 写者」这一前提成立。

## 7.7 对 GT 计划的影响（建议，计划正文未改）

| 编号 | 建议改动 | 原因 |
|---|---|---|
| GT-0（新增） | JGit 最小闭环实测：clone 白名单仓库 → 挂进沙箱 → 平台 diff 与原生 `git diff` 对账 | 先钉死可行性再接线（同沙箱集成时的做法） |
| GT-2 | +「宿主目录按 `WorkspaceRef` 解析」+「`allowed_host_paths` 白名单」两个前置 | 7.4① / 7.4② |
| GT-3 | 工具改为平台侧 JGit 实现，不依赖镜像装 git | 7.4② |
| GT-8 | worktree → **独立 clone**；合并队列 / touchedPaths 判据不变 | 7.2 |
| GT-11（新增） | 分支谱系与 attempt 接力：扁平命名 `t{taskId}` / `t{taskId}-s{subId}-a{n}`；起点策略按失败语义；派发载荷四项 | 7.6 |
| GT-7 | lazy ensure → **confirmPlan 异步建分支 + lazy ensure 兜底**；新增「发布」动作（单向、只推自有 ref、禁 force、ref 白名单） | 7.9.2 / 7.9.4 |
| GT-8 | +「本地 bare 仓只作缓存与合并执行点，发布方向单向」写者约束 | 7.9.1 / 7.9.2 |

## 7.8 待实测清单（GT-0 / 开工前）

1. JGit 7.8.1 在 Windows 宿主 clone、再挂进 Linux 容器后的 mode/EOL 表现（`core.fileMode=false` 是否足够）。
2. 平台 JGit 读「容器正在写的挂载目录」时的一致性（`FileTreeIterator` 取快照 vs 读到半写文件）。
3. 单任务多 agent 独立 clone 的体积/耗时（决定是否浅克隆 / 单分支）。
4. 本仓钉住的 1.1.0 与 latest 在「空 `allowed_host_paths`」上的行为差异（只影响文档口径，不影响「显式登记 root」的结论）。
5. **JGit `MergeCommand` 在「同一分支两处 checkout」下的失败形态**（7.6.3 的合流依赖它；JGit 无 worktree ⇒ 合并必须在平台侧单点执行，需确认并发合并的锁行为）。
6. **JGit `PushCommand` 在非快进下的异常类型 + `RefSpec.setForceUpdate(false)` 实际行为**（7.9.2 的「永不 force」依赖它；须确认 JGit 侧是否与原生 git 一样拒绝而非静默覆盖，以及抛的是 `TransportException` 还是返回 `RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD`）。此项由 §7.9 结论派生。

## 7.9 本地仓与远端仓的写者切分（2026-11-24 续研二，含双写丢失提交实测）

> **本节回答**：混合协作（内部 LLM + 外部 agent 交替执行同一任务）时，「平台本地仓」与「远端托管仓」如何保持一致，以及分支该由谁建、何时建。

### 7.9.1 ★实测：双向「保持同步」会静默丢提交

**复现步骤与结果**（原生 git 2.55.0，`file://` 本地裸仓模拟远端）：

```text
【第 1 步】local.git = git clone --bare remote.git   ← GT-8 设计的平台本地裸仓
【第 2 步】内部 LLM 走 local.git 提交                → local  helloai/t42 = 4c363e9
【第 3 步】外部 agent 走 remote.git 提交（同名分支）  → remote helloai/t42 = cd60970
【第 4 步】平台把 local 的 t42 推给远端
   普通 push  → rejected (non-fast-forward)，退出码 1
   --force   → + cd60970...4c363e9 helloai/t42 -> helloai/t42  (forced update)
              → remote t42 = 4c363e9，agent 的 cd60970 从 ref 上消失
```

关键佐证三条：

| 检查 | 命令 | 结果 |
|---|---|---|
| 两提交是否互含 | `merge-base --is-ancestor <llm> <agent>` | **RC=1**（互不为祖先） |
| LLM 提交对象在远端是否存在 | `git --git-dir=remote.git cat-file -e <llm>` | **RC=128（对象不存在）** |
| 强推后 agent 提交是否可达 | `rev-parse helloai/t42` | 指向 `4c363e9` ⇒ `cd60970` **无 ref 指向 = 不可达** |

⇒ **这不是「分支可见性问题」，是提交真的不在远端。** 「本地分支与远端分支保持同步」若实现为双向写同名 ref，结果就是互相覆盖；而覆盖过程**退出码常常是 0**（强推场景），平台无法从返回码发现。

### 7.9.2 定案：按 ref 归属切分唯一写者，不做同步

这是判据 4（同一事实双写必分叉）在 git 上的直接应用。**每个 ref 只有一个写者**：

| ref | 唯一写者 | 平台动作 | 外部 agent 权限 |
|---|---|---|---|
| `master` | **人**（IDEA 合并） | 只 fetch，永不写 | 禁止 |
| `helloai/t{taskId}` | **平台** | commit + push（发布） | **禁止** |
| `helloai/t{taskId}-s{subId}-a{n}` | **执行者（agent 本人）** | 只 fetch + 读 | 只能推自己那条 |

三条推论（都是单向性带来的，不是额外约束）：

1. 外部 agent 永不写 `t{taskId}` ⇒ 平台推该 ref **永远是 fast-forward**，不存在同名碰撞；
2. 平台永不写 `-s*-a*` ⇒ 外部 agent 之间不会互相覆盖（各自独立 ref）；
3. 平台本地裸仓是**缓存 + 合并执行点**，不是第二事实源 ⇒ 与 `ADR-008` §14「同一事实双写必分叉」同源。

**「同步」与「发布」的语义差别必须写清**：

| 概念 | 方向 | ref 归属 | 是否允许 |
|---|---|---|---|
| **发布**（publish） | 单向、平台 → 远端 | 只推**自己拥有的** ref | ✅ 本方案采用 |
| **同步**（sync） | 双向、无主 | 双方都写同名 ref | ❌ 造 7.9.1 的分叉 |

⇒ 平台侧动作命名用「发布」，代码与文档禁用「同步」描述本地↔远端关系。

### 7.9.3 内部 LLM 完成后不建临时分支，但要「发布」

**不需要临时分支**：同一 Task 的串行子任务共享同一 checkout（`WorkspaceRef.Kind.TASK` = `task:{taskId}` 级共享，`WorkspaceRef.java:121-125`），代码天然累积；另开临时分支反而制造「哪条是当前基线」的第二事实源。

**但必须发布**——这是混合协作的真实需求：S1 由内部 LLM 完成、S2 派给外部 agent 时，S2 在**另一台机器**上执行，只能从远端取代码。若不发布，外部 agent 拿到的是分叉前的旧基线。

**发布时机 = 子任务边界（`DONE` / 审核通过时），不是每次 commit**：

- 未通过审核不发布 ⇒ 外部 agent 拿不到半成品，语义正确；
- 每个子任务都发布 ⇒ 提交粒度与子任务对齐，reviewer 按 `SubTask-Id` trailer 分组审 diff 仍然成立。

**发布前置校验（缺一即 `BLOCKED`，不静默跳过）**：① 写前必 `fetch`；② 只接受 `FAST_FORWARD` 或快进合并（`MergeCommand` 结果须为 `MERGED`/`FAST_FORWARD`）；③ 禁用 `--force`（JGit `RefSpec.setForceUpdate(false)`）；④ 目标 ref 必须在 `helloai/` 命名空间内（ref 白名单校验，防越权推 master）。

### 7.9.4 派生的子任务取值时机：必须早于任何执行者，且不能内联

**纯外部 agent 任务上「lazy ensure」永不触发**——外部 agent 不从平台工作区取代码，只按派发载荷里的 `repoUrl`/`branchName` 自己去 clone。若分支只在「首次需要工作区时」创建，则外部 agent 拿到的分支在远端根本不存在，它只能自己建 ⇒ 分叉。

⇒ **分支创建必须由「草案确认」驱动，而不是由「工作区访问」驱动**：

| 触发 | 是否够 |
|---|---|
| 草案确认后建（`confirmPlan` 之后） | ✅ 覆盖内部 LLM 与外部 agent 两种执行者 |
| 首次访问工作区时 lazy ensure | ❌ 外部 agent 路径永不触发（仅可作兜底） |

**但不能内联在 `confirmPlan` 里**：

- clone / push 是网络 IO，挂在同步 HTTP 请求路径上会让确认操作耗时不可控（白名单仓库可大）；
- `confirmPlan` 刻意不开类级事务（`PlannerAnalysisServiceImpl` 类上只有 `@Service` + `@RequiredArgsConstructor`），但「无事务」不等于「可以慢」；
- `ADR-008` §18.2 定「派生 = 同库单事务」，git 是外部 IO，混入会破坏原子性（同 §13.5④）。

⇒ **`confirmPlan` 只写"待建分支"的意图（复用现有 outbox / job 触发机制），由 `helloai-job` 异步建分支与发布；lazy ensure 降为兜底。** 可照搬的既有范式：`helloai-job/src/main/java/com/helloai/job/task/PlanAttemptDispatchTask.java`、`ReviewerRecheckTask.java`（定时巡检 + fail-open）。

**建分支失败的处理**：分支建不出来时**不得**让子任务进入 `PENDING` 空等——按 `GT-5` 同源缺陷处理，置 `BLOCKED` 带原因（远端不可达 / 凭证无效 / 基线 sha 不存在）。

### 7.9.5 平台工作区会落后于远端（新增缺口）

外部 agent 合并进 `t{taskId}` 后该 ref 前进，但内部 LLM 的工作区 `{root}/{taskId}/repo` 仍停在旧 sha ⇒ **下一个内部子任务读到旧代码**。这不是缓存问题，是正确性问题。

规则：

- **子任务边界执行 `fetch` + 仅快进更新**（`MergeCommand` 结果必须 `FAST_FORWARD`，或 `PullCommand` 配置 `setFastForwardMode(ONLY)`）；
- **工作区不干净（有未提交改动）⇒ `BLOCKED` 带原因**，不静默丢弃、不自动 stash（stash 会让「上次执行的产物去哪了」不可归因）；
- 工作区被外部（沙箱容器/编辑器）正在写时，同上回 `BLOCKED`，不重试覆盖。

### 7.9.6 由此修正的两处既有结论

1. **`GT-7`「lazy ensure 建分支」改为「confirmPlan 异步建分支 + lazy ensure 兜底」**——原表述在纯外部 agent 任务上不成立（7.9.4）。
2. **`GT-8`「平台本地 bare 仓为源」需补写者约束**——本地 bare 仓只作缓存与合并执行点，**发布方向单向**（7.9.2）；否则 7.9.1 的分叉成立。

### 7.9.7 本节边界

- 7.9.1 的双写分叉是**原生 git 实测**；JGit `PushCommand` 在非快进下的异常类型与 `setForceUpdate(false)` 的实际行为**未实测**（归 `GT-0` 待实测清单第 6 项）。
- `confirmPlan` 异步化只定「意图落库 + job 建分支」的形态，**不定义具体表结构**（属 `GT-7` 实现细节）。
- 未验证「每个子任务都发布」在大仓库（万级 commit）下的 push 耗时；若超阈值，退路是「按阶段发布」——但**不得**退化为「任务末尾一次性发布」，那会让外部 agent 在任务中途拿不到最新代码。

---

# 8. 本轮续研的边界（诚实说明）

- 7.6 的分支谱系结论基于**本地 git 2.55.0 实测 ref 行为** + **JGit 7.8.1 jar 类级扫描**；JGit 的 `RefUpdate` 对同名 D/F 冲突的错误码**尚未实测**（只有原生 git 的 RC=128 实证），实施前须补。
- 7.6.4 的起点策略是**语义推导**，未经真机验证「fresh vs continue 对最终验收通过率的实际影响」——若要下「哪种更好」的结论，需对照实验（参照 `MEMORY-DETAIL-工程纪律与判据.md` 判据 18 的对照实验口径）。
- 7.9 的写者切分同样是**原生 git 实测 + 语义推导**：分叉本身有实测（7.9.1 三条佐证），但「JGit 侧禁 force 是否真能拦住」未实测；「子任务边界发布」的 push 耗时尚无数据。
- 本节**不含**排期、工期、里程碑与逐项状态；正式任务编号与验收由 `plan/` 决定。