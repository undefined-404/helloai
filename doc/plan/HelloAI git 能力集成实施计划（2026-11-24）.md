# HelloAI git 能力集成实施计划（2026-11-24）

> **状态：Active**（立项完成，实施批次待排——按「JGit 嵌入 → 渐进式提交 + reviewer 沙箱验证 → 拉分支集成测试 → 分支谱系与 attempt 接力 → 单向发布与工作区前进」五小步推进）
> 最后更新：2026-11-24（**本轮新增 `GT-12` 单向发布与工作区前进**：混合协作（内部 LLM + 外部 agent 交替）下内部产出须发布到远端外部 agent 才拉得到；`GT-7` 创建时机由 lazy ensure 改为 **confirmPlan 后异步建**（lazy ensure 降为兜底）；`GT-8` 补写者切分（本地 bare 仓只作缓存与合并执行点、发布方向单向）；实测双向「同步」同名 ref 会静默覆盖提交（`research` §7.9）。**前序**：本轮早先新增 `GT-11` 分支谱系与 attempt 接力（多 agent 接力场景的尝试分支建法 + 起点策略 + 派发载荷四项）；`GT-7` 定扁平命名（ref D/F 冲突实测）、`GT-8` 定合并方向与禁 squash、`GT-3` 补 `SubTask-Id` trailer、`GT-5` 补 git 四字段拦截；`research` §7.6 为结论来源。**更早**：按 research §7 续研订正回写——新增 `GT-0` 可行性实测；`GT-2` 补两个前置；`GT-3` 收窄为平台侧工具；`GT-8` worktree → 独立 clone。当日另修复 `deploy/quickstart` 缺 `[storage]`+`SANDBOX_VOLUME_*` 的部署侧覆盖缺口，见 `LOG-20261124` 补充批次 3。**再次修订**：原 `GT-3` 的「硬前置 C 容器可见性」抽出为独立并行轨 `GT-10`——它与 JGit 无耦合、且影响面仅容器部署形态，不应作为 `GT-3` 子项阻塞主链；同时回填空值 bind mount 静默降级实测，明确 `volumes:` 须走独立 override 文件）

---

# 0. 定位与边界（★先读）

## 0.1 问题来源

- 差距表 `G-033` 待办①「仓库注入适用性调查」：执行侧沙箱工作区仅 `/workspace/draft.md`，**仓库不进沙箱**——外部 agent 代码开发类任务无法基于真实仓库作业，也无法审计「改了什么」。
- 用户 2026-11-24 演进思路（验证报告 §5）：外部 agent 代码任务需「git 地址 / 拉取方式 / 运行环境」前置准备 + **渐进式代码提交**（代码即交付物）+ **reviewer 沙箱基于提交代码验证** + 中期 **gittree 类拉分支集成测试**。
- 本计划形态定案来源：`research/HelloAI git 能力集成与外部代码任务调研（2026-11-24）.md`（选型 JGit / 参照 CAID 与 AGENTS.md / 三小步论证）。

## 0.2 本计划做什么（五小步 + 前置实测）

0. **第 0 步 `GT-0`：可行性实测**——JGit 最小闭环（clone → 挂沙箱 → diff 与原生对账）+ 六项待实测项出结论。先钉死可行性再接线。
1. **第 1 步 `GT-1`~`GT-3`：JGit 嵌入**——平台代 clone（白名单仓库 + commit 锁定；深度待 `GT-0` 定，见 `GT-2`）→ 仓库卷内就位（挂 `/workspace/repo` + 只读基线）→ 平台侧 git status/diff 审计工具；`repoPath` 约束通道随之生效（G-037 正式引入 §4 末行）。
2. **第 2 步 `GT-4`~`GT-6`：渐进式提交 + reviewer 沙箱验证**——产物通道补「执行中代码文件」增量提交语义；`ExecutionPrereqChecker` 派发校验（缺前置信息阻塞领取）；reviewer 基于提交代码 + 同一规范在沙箱内验证（编译 / 规范命中 / 测试）。
3. **第 3 步 `GT-7`~`GT-9`：拉分支集成测试**——承接 G-027⑤⑥：平台代建分支（**扁平命名**，禁嵌套）+ 短期凭证 + **独立 clone 隔离**（非 worktree）；沙箱拉取 agent 分支做集成测试；审查基于 commit diff；多 agent 按 touchedPaths 交集判串行、每合并一支重跑验收机。
4. **第 4 步 `GT-11`：分支谱系与 attempt 接力**——同一子任务被不同 agent 接力（`trae` 失败 → 人工改判 `codex`）时，尝试分支 `helloai/t{taskId}-s{subId}-a{n}` 的建法与起点策略（`continue`/`fresh`/人选定）；派发载荷四项写死。
5. **第 5 步 `GT-12`：单向发布与工作区前进**——混合协作（内部 LLM + 外部 agent 交替）时，内部产出必须**发布**到远端外部 agent 才拉得到；平台工作区须在子任务边界快进前进。**禁双向「同步」**（实测会静默覆盖提交）。
6. **并行轨 `GT-10`：部署形态能力（app 容器工作区可见性）**——**不在主链上、不阻塞任何主链任务开发**：本地直跑形态无需它，仅容器部署形态（`deploy/app` / quickstart）的验收依赖它。建议并入 `GT-2` 批次实施（同改卷挂载链路）。

## 0.3 本计划不做什么（边界）

| 不做 | 为什么 |
|---|---|
| 前端工作台 git 可视化（commit 图 / 文件树） | gittree 类 UI 后置，不参与执行链 |
| 审查策略规则化（ClaudeShip approvals.yml 式 override） | 待 reviewer 门禁数据支撑后评估 |
| AGENTS.md 生态对齐（约束文档入仓） | 随 `repoPath` 生效自然演进，不独立立项 |
| 任务依赖/矛盾规划期校验 | 另立专项（`design/HelloAI 任务依赖与矛盾校验设计（规划期双闸）.md`） |
| 完整 git 服务托管（Gitea 部署） | 目标仓库已在远端托管时直接透传；Gitea 仅按需作网关 |
| 子任务分支的 UI 展示（分支图 / 谱系树） | gittree 类 UI 后置，同「前端工作台 git 可视化」 |
| 自动合并策略（rerere / 自动解冲突） | 冲突须人工介入：自动 rerere 会让「哪次合入引入错误」不可归因，违背审计初衷 |
| **本地↔远端双向「同步」** | 实测双向写同名 ref 会静默覆盖提交（`research` §7.9.1）；平台侧只做**单向发布**，每个 ref 只有一个写者 |

## 0.4 诚实边界

- 本计划为**立项文档**：调研锚点已齐（形态已定案），五小步实施批次待排——每步开工前须按既有纪律补 [实测] 验证（同沙箱集成时先直测后接线）；
- **分支谱系的结论有一半是语义推导**：`research` §7.6 的 ref D/F 冲突是原生 git 2.55.0 实测，但 **JGit `RefUpdate` 对同类冲突的错误码未实测**（归 `GT-0` 待实测清单第 5 项）；「`continue` vs `fresh` 哪种起点验收通过率更高」也**未经对照实验**，本计划只定「按失败语义分档」的规则，不下「哪种更好」的结论；
- **写者切分的分叉有实测，拦截手段未实测**：`research` §7.9.1 三条佐证证明双向写同名 ref 会丢提交，但「JGit 侧 `setForceUpdate(false)` 是否真能拦住」未实测（归 `GT-0` 第 6 项）；「子任务边界发布」的 push 耗时尚无数据；
- 外部 Agent 无头运行能力（Trae / Claude Code CLI / Aider）等 G-027 前置待验证项，在本计划第 2~3 步内逐项完成，不预设结论；
- 本轮改动不自动 commit（沿用约定）。

---

# 1. 编号建号声明（防撞号）

- **号段**：`GT-0` ~ `GT-12`（**GiT** – git 能力集成；`GT-0` = 可行性实测前置，`GT-10` = 并行轨部署形态能力，`GT-11` = 分支谱系与 attempt 接力，`GT-12` = 单向发布与工作区前进）
- **`x` 取值域**：正整数，不跳号（`GT-10` 为并行轨扩展号、`GT-11` 为多 agent 场景扩展号、`GT-12` 为混合协作发布扩展号，均按序接在 `GT-9` 之后；**不复用 `GT-13` 空号**——治理规则禁跳号）
- **登记位置**：差距表 §0（`G-033` 锚点，`GT-x` 号段并入）
- 实测 `doc/**` 全库 `GT-` 零命中，不撞号；刻意不用 `G-`（前缀冲突）、`GR-`（语义过泛）

---

# 2. 现状（勘察结论，[实测]）

| # | 锚点 | 现状事实 |
|---|---|---|
| 1 | 沙箱卷挂载（OpenSandbox） | `allowed_host_paths` 白名单 + `file:` 独立引用 + `/workspace` 卷内语义已通（G-037 实测）；仓库本体不进沙箱；**白名单现只有 `E:/yhzx/1027/unip-review-workspace`**（`opensandbox-config.toml:51`） |
| 2 | 执行器形态 | 技能型执行（宿主循环 + 持久 shell + 文件工具直读），无「repo 拷贝进工作区」环节；JGit 接管 clone/diff 不改变执行内核 |
| 3 | 平台仓库触达 | 平台进程唯一 `ProcessBuilder` 为 `PgDumpRunner`；无 git 操作能力 |
| 4 | 产物通道 | `attachment` 方向已支持产物登记；缺「执行中代码文件」增量提交语义 |
| 5 | 派发校验 | 无 `ExecutionPrereqChecker`，技能 AND 硬匹配不派发 ⇒ 无原因 `PENDING` |
| 6 | 约束通道 | manifest 有平台侧 `path`；`sandboxPath` / `repoPath` 字段**已落地**（G-037 第 3 项②，2026-11-24），`repoPath` 待 GT-3 启用为第一通道 |
| 7 | 卷隔离 | **无**——只有一份全局 `volume.host-path`（`application.yml:199`），所有任务开卷即同一目录；GT-2 须先补按 `WorkspaceRef` 解析 |
| 8 | 沙箱内 git 能力 | **无**——预设镜像不带 git（`node:20-bookworm-slim`），且 `network-policy.default-action: deny` 拉不到远端 ⇒ clone 必须平台侧完成 |
| 9 | JGit worktree | **不支持**——7.8.1 jar 无 worktree 命令类、不解析 `commondir`（research §7.2 类级实测）⇒ GT-8 用独立 clone |
| 10 | 容器形态工作区可见性 | **缺失**——`deploy/app` 的 app 服务只挂 jar + `./logs`，quickstart 的 app 无 volumes（jar 打进镜像）⇒ app 容器内**看不见** `SANDBOX_VOLUME_HOST_PATH` 目录；本地「IDEA 直跑」形态不受影响（进程即宿主）。归 `GT-10` |
| 11 | 空值 bind mount 行为 [实测] | **静默降级不报错**——`- ${V}:${V}` 在 `V` 为空/未定义时，`docker compose config` RC=0，展开为 `type: volume` + `target: ':'`（匿名卷挂非法路径）。⇒ **`volumes:` 不得无条件写入主 compose**，须用独立的 override 文件按需 `-f` 加载（不启用时零影响，启用时正确合并为 `type: bind`）。归 `GT-10` |
| 12 | 双向写同名 ref [实测] | **静默覆盖提交**——`local.git` 与远端各自在 `helloai/t42` 上提交后，平台向远端 push：普通 push 被拒（non-fast-forward），`--force` 成功但**退出码仍为 0**，被覆盖的提交退出 ref 后变为不可达（`cat-file -e` 在远端返回 RC=128，对象根本不存在）。⇒ 本地↔远端**只做单向发布**，每个 ref 只有一个写者。归 `GT-12` |

---

# 3. 任务表（GT-0 ~ GT-12）

## 第 0 步：可行性实测（GT-0，开工前置）

### GT-0 JGit 最小闭环实测

> **状态**：待开始 ｜ **剩余动作**：clone 白名单仓库 → 挂进沙箱 → 平台 JGit diff 与原生 `git diff` 对账；Windows 宿主 mode/EOL / 半写文件一致性 / clone 体积耗时 / 白名单空列语义 / JGit 合并并发锁 / **JGit 推非快进与 `setForceUpdate(false)`** 六项待实测项出结论

- **做什么**：钉死可行性再接线（同沙箱集成时的做法）。六项实测（research §7.8）：①Windows 宿主 clone 后挂进 Linux 容器的 mode/EOL 表现（`core.fileMode=false` 是否足够）；②平台 JGit 读「容器正在写的挂载目录」的一致性（`FileTreeIterator` 快照 vs 半写文件）；③单任务多 agent 独立 clone 的体积/耗时（决定浅克隆/单分支）；④本仓钉住的 OpenSandbox 1.1.0 与 latest 在「空 `allowed_host_paths`」上的行为差异；⑤JGit `MergeCommand` 在同一分支两处 checkout 下的失败形态与并发合并锁行为；⑥**JGit `PushCommand` 在非快进下的异常类型 + `RefSpec.setForceUpdate(false)` 实际行为**（`GT-12` 的「永不 force」依赖它，须确认 JGit 侧是否与原生 git 一样拒绝而非静默覆盖）。
- **验收判据**：平台 JGit 算出的 diff 与原生 `git diff` 逐行一致；六项实测各出结论并回填本计划 §2。

## 第 1 步：JGit 嵌入（GT-1 ~ GT-3）

### GT-1 JGit 依赖与仓库白名单

> **状态**：待开始 ｜ **剩余动作**：根 POM 加 `<jgit.version>` + `dependencyManagement`（`helloai-core` 显式依赖）；仓库白名单配置（`sys_config` / 配置文件，防任意仓库拉取）；新增架构守卫「`org.eclipse.jgit` 只许出现在 `runtime.git` 包」

- **做什么**：根 POM 加 `<jgit.version>` 属性 + `dependencyManagement` 条目（对齐 `opensandbox.version` 做法），`helloai-core/pom.xml` 直接依赖；仓库白名单配置（`sys_config` 或配置文件，防任意仓库拉取）。
- **版本**：`org.eclipse.jgit:org.eclipse.jgit:7.8.1.202611041320-r`（`JavaSE-17` 与根 POM `java.version=17` 匹配，无升级 JDK 压力）；HTTPS 远端无需 `jgit.ssh.apache`（目标仓库是 Gitee HTTPS）。
- **架构守卫**：`check-arch-freeze.sh` 只统计跨域 import、**不拦第三方包** ⇒ 须新增「`org.eclipse.jgit` 只许出现在 `com.helloai.core.agent.runtime.git`」规则，挂 ci-gate 门禁 3。
- **验收判据**：白名单外仓库 clone 请求被拒；JGit 版本锁定并过架构守卫（git 包外出现 `org.eclipse.jgit` 即红）。

### GT-2 平台代 clone + 卷内就位

> **状态**：待开始 ｜ **剩余动作**：**前置 A** 按 `WorkspaceRef` 解析宿主目录（现只有一份全局 `volume.host-path`）；**前置 B** 新仓库根进 `[storage].allowed_host_paths` 白名单（现只有 `E:/yhzx/1027/unip-review-workspace`）+ `--force-recreate`；再实施 clone → 挂载 `/workspace/repo`（不是 `/workspace`）

- **做什么**：任务声明 `repo`（地址 + 分支/commit 锁定）→ 平台 JGit 克隆到任务工作区 → 经沙箱卷挂载进 **`/workspace/repo`**（只读基线，防止篡改）。
- **★clone 深度待 `GT-0` 定（2026-11-24 晚实测，先记约束不预设结论）**：`--depth 1` 能让 agent 工作区变轻（浅克隆后提交、`git diff <baseSha> HEAD`、push 回全量 bare 均实测可用），但**浅克隆下 `merge-base` 返回 RC=1、merge 报 `refusing to merge unrelated histories`**；`--unshallow` 后 RC=0，`--depth=100` 亦 RC=0。⇒ **合并（`GT-8`）必须落在全量或足够深的裸仓上，浅克隆只可作 agent 工作区**。本步骤的「任务工作区」属哪一类，由 `GT-0` 第 ③ 项（单任务多 agent 独立 clone 的体积/耗时）出结论后校准——**在结论出来前不把「浅克隆」写死为默认**。
- **硬前置 A（隔离）**：`application.yml:199-200` 只有一份全局 `volume.host-path`，`SandboxFactory.applyWorkspaceVolume()` 直接读全局配置 ⇒ **今天所有任务开卷就是同一个目录**。须新增 `workspace-root`，目录取 `{root}/{kind}-{ownerId}`，`SandboxFactory.create()` 改接收解析后的 hostPath（槽键已带 kind/ownerId/spec/access，改动集中在 factory 签名与调用点）。
- **硬前置 B（白名单）**：`opensandbox-config.toml:51` 现只有 `E:/yhzx/1027/unip-review-workspace`；该字段是 **path prefixes** allowlist ⇒ 登记 root 一次即覆盖 `{root}/{taskId}/...` 子目录，不必逐任务登记；改后需 `--force-recreate`。**版本陷阱**：钉住的 release-1.1.0 实测「空列表=全拒」（server breaking change #750/#751），不要照 latest 文档把白名单清空。
- **挂载点修正**：**必须 `/workspace/repo` 而非 `/workspace`**——`DeliverableReader` 在 `/workspace` 下按 depth=2 挑最新 `.md`，仓库挂进根目录会让 `README.md`/`CLAUDE.md` 被误当交付物；同时给 `DeliverableReader` 加「跳过 repo 子树」。
- **本批次建议顺带做 `GT-10`**：`GT-2` 改的正是卷挂载链路（`SandboxFactory` 签名 / `application.yml` 的 `workspace-root` / 宿主目录解析），而 `GT-10` 的 app 容器 bind mount 依赖同一个 `SANDBOX_VOLUME_HOST_PATH` 变量——两件事同批实施可一次到位，避免 `GT-10` 单独改一遍后又被 `GT-2` 的链路改动作废。
- **验收判据**：clone 成功后沙箱内 `git status` / `git diff` 可查；工作区与远端基线条目一致；commit 锁定在基线 sha；**两个不同任务/agent 的卷目录互不覆盖**；交付物回读不捞到仓库内 `.md`。

### GT-3 执行器 git 审计工具 + 约束 `repoPath` 生效

> **状态**：待开始 ｜ **剩余动作**：以**平台侧 JGit 实现** `internal_git_status` / `internal_git_diff`（读挂载目录，不在沙箱内跑 git）；manifest `repoPath` 语义变为 `repo/doc/…` 并启用为第一通道；`SubTask-Id` commit trailer 定契约（供 reviewer 按子任务分组审 diff）

- **做什么**：执行侧工具补 `internal_git_status` / `internal_git_diff`（改了什么可审计）；manifest `repoPath` 字段生效（规范随仓库版本一致，G-037 正式引入 §4 末行通道首次可用）。
- **前置依赖（形态相关，非开发阻塞）**：本任务的**开发**在「本地 IDEA 直跑」形态下即可完成（平台进程即宿主，直接读挂载目录无阻）；**容器部署形态的验收**依赖 `GT-10`（app 容器工作区可见性）。二者解耦——GT-10 未完成不阻塞本任务开发，只阻塞 `deploy/app` / quickstart 形态的验收。详见 `GT-10`。
- **实现收窄（重要）**：**工具必须是平台侧 JGit 实现**，不得假设沙箱内有 git——预设镜像 `node:20-bookworm-slim` 不带 git，且 `network-policy.default-action: deny` 使沙箱拉不到远端。原「执行侧 git 语义」表述据此收窄为「平台侧工具 + 卷内目录只读」。
- **diff 算法**：base commit tree 对工作区 `FileTreeIterator` 走 `DiffFormatter`，**不读写 index** ⇒ 与沙箱并发写文件不抢 `index.lock`。
- **子任务归属标注**：`GT-11` 定「子任务默认不建分支」，同 Task 内串行子任务靠 commit trailer `SubTask-Id: <id>` 标注归属 ⇒ 本任务的 `internal_git_diff` 须能按 trailer 过滤（`--grep` 语义由 JGit `RevWalk` 实现，不解析 CLI 输出）。字段名与格式在 `GT-11` 定稿前先按 `SubTask-Id` 预留。
- **验收判据**：执行器读约束走 `repoPath`（仓库内相对路径 `repo/doc/…`）；diff 审计在时间线留痕；平台 JGit 与原生 `git diff` 一致（GT-0 已证）；按 `SubTask-Id` trailer 能滤出单子任务改动；不新增 `ProcessBuilder`。**本地直跑形态**即可判本任务完成；容器形态验收随 `GT-10` 收口后补测。

## 第 2 步：渐进式提交 + reviewer 沙箱验证（GT-4 ~ GT-6）

### GT-4 代码文件增量提交通道

> **状态**：待开始 ｜ **剩余动作**：产物通道补「执行中代码文件」语义（增量登记 + reviewer 侧可见）

- **做什么**：产物通道补「执行中代码文件」语义——执行器按阶段提交代码文件本体 + 改动说明，报告退居说明位。
- **验收判据**：代码文件集增量登记可查（storageUrl / 文件路径 / 改动说明），reviewer 侧可见。

### GT-5 ExecutionPrereqChecker 派发校验

> **状态**：待开始 ｜ **剩余动作**：派发前校验 `accessType` + 执行环境（git 地址 / 拉取方式 / 运行环境缺项 → BLOCKED 带原因）；`CreateTaskRequest` 写 `context` 通道；**git 四字段缺项拦截**（`repoUrl` / `baseSha` / `branchName` / `startFrom`，字段契约见 `GT-11`）

- **做什么**：派发前校验 `accessType` + 执行环境（git 地址 / 拉取方式 / 运行环境缺项 → 阻塞领取并带原因，替代无原因 `PENDING`）。
- **git 四字段**：外部 agent 在**自己机器**上执行，平台无法约束其行为，只能在派发时把 git 语义写死（`repoUrl` / `baseSha` / `branchName` / `startFrom`，见 `GT-11`）。缺任一项即 `BLOCKED` 并指名缺哪个——否则 agent 自行决定基线，diff 不可比、留痕不可核。这与本任务原有「无原因 `PENDING`」缺陷同源，同批修。
- **验收判据**：缺前置信息的任务被 BLOCKED 且原因可读；补全后正常派发；git 四字段缺项时阻塞原因能指出具体字段名。

### GT-6 reviewer 沙箱基于提交代码验证

> **状态**：待开始 ｜ **剩余动作**：reviewer 在沙箱内基于提交代码 + 同一规范验证（编译 / 规范条款命中 / 测试），报告引用 commit diff

- **做什么**：reviewer 在沙箱内基于提交的代码 + 同一规范做验证（编译 / 规范条款命中 / 测试）；审查报告引用 commit diff 而非整包回传。
- **验收判据**：reviewer 验证基于真实提交代码；判据矩阵引用 commit sha / diff 行号；行为对齐 ClaudeShip CodeReview 对抗式校验语义。

## 第 3 步：拉分支集成测试（GT-7 ~ GT-9，承接 G-027⑤⑥）

### GT-7 平台代建分支 + 短期凭证

> **状态**：待开始 ｜ **剩余动作**：平台建**扁平命名**分支 `helloai/t{taskId}`（**禁嵌套 `/`**，ref D/F 冲突实测见 research §7.6.2）；建分支由 **`confirmPlan` 后异步触发**（lazy ensure 降为兜底）；短期凭证签发（Gitea 网关或远端透传）；不 push 主干

- **做什么**：平台为每个 agent 任务代建分支；短期凭证签发（Gitea 网关或远端透传）；不 push 主干。
- **★命名形态（已定，research §7.6.2 实测）**：`helloai/t{taskId}`——**扁平单段，禁止 `helloai/t{taskId}/sub` 这类层级**。git ref 是目录树，父段一旦被用作分支名其下永不能再建子段（`git branch a` 后 `git branch a/b` 报 `cannot lock ref`，RC=128）；反向同理。`taskId` 全局唯一 ⇒ 派生 Task 天然拿到 `helloai/t{Y}`，**不需要把 `depth` 编进名字**，分支名长度与派生深度无关。
- **★创建时机改为「`confirmPlan` 后异步建」（2026-11-24 续研二订正，research §7.9.4）**：原表述「lazy ensure，首次需要工作区时建」在**纯外部 agent 任务上永不触发**——外部 agent 不从平台工作区取代码，只按派发载荷里的 `repoUrl`/`branchName` 自行 clone，分支在远端不存在时它只能自建 ⇒ 分叉。正解：`confirmPlan` 落「待建分支」意图（复用现有 outbox / job 触发机制），由 `helloai-job` 异步建分支并发布；**lazy ensure 保留为兜底**（覆盖草案期派生等未走 confirmPlan 的路径）。可照搬范式：`helloai-job/.../task/PlanAttemptDispatchTask.java`、`ReviewerRecheckTask.java`（定时巡检 + fail-open）。
- **★不得内联进 `confirmPlan`**：clone / push 是网络 IO，挂在同步 HTTP 请求路径上会让确认操作耗时不可控；且 `ADR-008` §18.2「派生 = 同库单事务」与 §13.5④ 已否掉「git IO 混入单事务」。另注意 `PlannerAnalysisServiceImpl` 类上只有 `@Service` + `@RequiredArgsConstructor`（无类级 `@Transactional`），但「无事务」不等于「可以慢」。
- **★建分支失败不得空等**：分支建不出来时**不得**让子任务停在无原因 `PENDING`，须置 `BLOCKED` 带原因（远端不可达 / 凭证无效 / 基线 sha 不存在）——与 `GT-5` 同源缺陷同批修。
- **验收判据**：分支隔离（agent 间互不可见变更）；凭证短时有效；主干无 agent 直推；**派生 Task 的分支与父分支命名形态一致、互不冲突**（父 `helloai/t{X}` 与派生 `helloai/t{Y}` 可共存）；**纯外部 agent 任务在派发前远端已存在 `helloai/t{taskId}`**（不需 agent 自建）；建分支失败时子任务为 `BLOCKED` 且原因可读。

### GT-8 独立 clone 隔离 + 合并队列

> **状态**：待开始 ｜ **剩余动作**：平台建本地 bare 仓为源，每 agent `JGit.clone` 一份独立工作区（**替代 worktree**）；合并队列每合并一支重跑验收机；touchedPaths 交集判串行

- **做什么**：每 agent 一份隔离工作区；合并队列每合并一支重跑验收机（命令退出码权威）；多 agent 按 touchedPaths 交集判串行。
- **★订正（research §7.2 jar 级实测）**：**JGit 7.8.1 不支持 linked worktree**——jar 内无 `WorktreeAdd/List/RemoveCommand`，无 `worktrees/` 目录串，`commondir` 不参与仓库打开路径解析。原「JGit worktree 隔离」不可实现。
- **替代形态（A 独立 clone，已定）**：平台建一个本地 bare 仓为源，每 agent `JGit.clone` 一份完整工作区。隔离最彻底、天然规避「同一分支不能两处 checkout」；代价是磁盘 O(repo×agent)，任务内 2~5 agent 量级完全够。**不选** B（JGit+宿主 CLI，引入 git binary 部署前置）与 C（libgit2/JNI，native 多平台分发）。
- **★写者切分（2026-11-24 续研二新增，research §7.9.2）**：本地 bare 仓是**缓存 + 合并执行点，不是第二事实源**；每个 ref 只有一个写者——`master` 归人、`helloai/t{taskId}` 归平台、`helloai/t{taskId}-s{subId}-a{n}` 归执行 agent 本人。平台的本地↔远端动作必须叫**「发布」**（单向、只推自有 ref、写前必 fetch、禁 force、ref 白名单限 `helloai/` 命名空间），**禁止**叫「同步」或做双向写同名 ref——实测双向写会静默覆盖提交（research §7.9.1 三条佐证，强推后 `push` 退出码仍为 0）。
- **★合并方向（已定，research §7.6.3）**：合并**只沿派生边单向**——`a{n}` → `t{task}` → 父 `t{X}` → … → `master`；**仅 `depth=0` 根 Task 分支可直连 `master`**，派生分支永不直连。方向唯一 ⇒ 不存在「派生分支与 master 平级、先后不明确」的歧义。
- **★禁 squash**：必须用 `MergeCommand` 真实合并，**squash 会压掉 merge-base** ⇒ 层级不可回溯，回退从「revert 一次 merge」退化成「手工挑文件」。
- **★冲突即中止**：`MergeResult` 非 `MERGED`/`FAST_FORWARD` ⇒ 回 `BLOCKED` 带原因。**不做自动策略**（自动 reroute 会让「哪次合入引入了错误」不可归因）。
- **★执行期派生的 A2 是空位**：`ADR-008` §13.1 规定 A2 冻结、四要素不改、产出由派生任务承担 ⇒ 父分支上那次 merge 必须打 `A2 via Y` 标记，否则 reviewer 会判「A2 缺交付」。
- **重复派生不叠加**：`ADR-008` §13.3 允许同一子任务重复派生（`Y` 失败再派 `Z`）——每次是**新 Task 新分支**（`helloai/t{Y}` / `helloai/t{Z}`），旧分支按遗弃处理，**绝不在同一分支上继续叠 commit**。
- **验收判据**：并行 agent 变更不互覆；合并触发验收机重跑；touchedPaths 冲突串行化生效；隔离由独立 clone 提供而非 worktree；**合并冲突时子任务回 `BLOCKED` 且原因可读**；**派生链三层合并后 master 只看到一次根分支合入**；**平台发布失败（非快进 / 远端已前进）时不覆盖、不静默成功**。

### GT-9 沙箱拉分支集成测试

> **状态**：待开始 ｜ **剩余动作**：沙箱拉取 agent 分支做集成测试；G-027 前置待验证项（无头运行 / worktree+凭证闭环 / 验收机计时 / touchedPaths 可用率 / worker 中断接续）逐项完成并回填

- **做什么**：沙箱内拉取外部 agent 提交后的分支，基于最新代码做集成测试 / 调试；G-027 前置待验证项（外部 Agent 无头运行能力 / worktree + 代建分支 + 短期凭证闭环 / 验收机容器计时 / touchedPaths 产出可用率 / worker 中断接续）逐项完成并回填。
- **验收判据**：集成测试基于 agent 最新分支代码；G-027 V1~V5 各项验证结论回填差距表。

---

## 第 4 步：分支谱系与 attempt 接力（GT-11，承接多 agent 场景）

### GT-11 子任务尝试分支与多 agent 接力谱系

> **状态**：待开始 ｜ **剩余动作**：扁平命名建 `helloai/t{taskId}-s{subId}-a{n}` 分支（独立只增序号，不复用 `attempt_total`）；attempt 起点策略按失败语义分档（`continue` / `fresh` / 人工显式选）；派发载荷四项写死；旧分支打 `rejected` tag 不删除

- **问题来源**（`research` §7.6）：外部 agent 经 `claimSubTask` 在**各自机器**上执行、不进服务器沙箱 ⇒ 同 Task 子任务在平台沙箱里天然串行（`WorkspaceRef.Kind.TASK` 任务级共享 + 槽内命令串行），但**外部 agent 之间是真并行**；且一个子任务可能被不同 agent 接力（trae 多次失败进死信 → 人工改判 codex）。
- **做什么**：
  1. **尝试分支**：`helloai/t{taskId}-s{subTaskId}-a{n}`（扁平、`-` 分隔，见 `GT-7` 命名形态）。**仅在该子任务存在多次尝试或被并行派给多个外部 agent 时创建**；串行单次执行只用 `helloai/t{taskId}` + commit trailer `SubTask-Id`。
  2. **起点策略**：按**失败语义**定，不让人猜——租约超时/离线回收（未跑完）⇒ `continue` 从 `a{n}` HEAD；审核驳回（这版不合格）⇒ `fresh` 从 `t{task}` 基线；`reworkFresh` / `redispatchDeadLetter`（人裁定）⇒ **人显式选**。
  3. **旧分支保留**：`a{n}` **永不删除**，返工时打 `rejected` tag——它是审计证据，reviewer 对比 `a1` vs `a2` 即可判「新 agent 是修复还是重写」。
  4. **派发载荷四项**：`repoUrl` / `baseSha` / `branchName` / `startFrom`（+ 参考分支）写进派发上下文，缺项由 `GT-5` 阻塞（不在本任务实现校验，只定契约）。
- **★三处必须避开的坑**：
  - **旧提交失效 ≠ 删分支**：`invalidateAttachmentsOnRework` 现把旧附件置 `INACTIVE`（`SubTaskStateMachineServiceImpl.java:594` 附近）；分支版等价动作是打 tag，删分支会连审计证据一起删。
  - **分支代次不得复用 `attempt_total`**：`reworkFresh` / `redispatchDeadLetter` 都会 `resetAttemptTotal` 清零该计数器（`SubTaskStateMachineServiceImpl.java:590`、`SubTaskDispatchServiceImpl.java:405`）。复用会让「预算已清零」与「分支已到 `a3`」语义对不上；让分支号跟着回 `a1` 则发生覆盖。⇒ **独立只增序号**。
  - **「当前有效分支」是投影，不是新事实源**：从最新 `agent_execution_record` 推导，**禁加 `current_branch_seq` 列**（同一事实双写必分叉，同 `ADR-008` §14）。
- **不做什么（边界）**：
  - 不改沙箱作用域（`WorkspaceRef` / 槽池不动）——并行是外部 agent 的固有属性，不是平台沙箱的需求；
  - 不做子任务分支的 UI 展示（归 gittree 类 UI，§0.3 已划出）；
  - 不实现 `GT-5` 的校验逻辑（本任务只定字段契约）。
- **前置**：`GT-7`（扁平命名建分支）/ `GT-8`（独立 clone + 合并方向）。与 `GT-1`~`GT-3` 无耦合。
- **验收判据**：同一子任务 `a1`（trae）与 `a2`（codex）两分支共存且 `a1` 仍可读；`continue` 与 `fresh` 两种起点产出的 diff 基线可区分；旧分支 tag `rejected` 后不被当作当前基线；派发上下文含四项且缺项时 `GT-5` 能指名缺哪个；**派生链（`t{X}` → `t{Y}` → `t{Z}`）三层存在时分支名形态一致、无 ref 冲突**。

## 第 5 步：发布与工作区前进（GT-12，承接混合协作）

### GT-12 平台向远端发布 + 平台工作区前进

> **状态**：待开始 ｜ **剩余动作**：实现**单向发布** `JGit PushCommand`（写前必 fetch、只接受 fast-forward、`RefSpec.setForceUpdate(false)`、ref 白名单限 `helloai/`）；子任务边界（`DONE`/审核通过）触发发布；平台工作区在子任务边界 `fetch` + 仅快进前进，不干净即 `BLOCKED`

- **问题来源**（`research` §7.9）：混合协作时 S1 由内部 LLM 完成、S2 派给外部 agent，而外部 agent 在**另一台机器**上执行、只能从远端取代码。若平台不把内部产出推到远端，外部 agent 拿到的是分叉前的旧基线；反过来若两边都写同名 ref，则静默互相覆盖（§7.9.1 实测：强推后 `push` 退出码仍为 0，被覆盖的提交变为不可达）。
- **做什么**：
  1. **单向发布**：`helloai/t{taskId}` 由平台 push 到远端。**写前必 fetch**；只接受 `FAST_FORWARD`；`RefSpec.setForceUpdate(false)`；目标 ref 必须在 `helloai/` 命名空间内（ref 白名单，防越权推 `master`）。发布失败（非快进 / 远端已前进）⇒ 重取远端后重算，仍非快进则 `BLOCKED`，**不覆盖、不静默成功**。
  2. **发布时机 = 子任务边界**（`DONE` / 审核通过），不是每次 commit。未通过审核不发布——外部 agent 拿不到半成品。
  3. **工作区前进**：子任务边界对平台工作区 `{root}/{taskId}/repo` 执行 `fetch` + 仅快进更新（`PullCommand#setFastForwardMode(ONLY)` 或 `MergeCommand` 结果必须 `FAST_FORWARD`）。工作区有未提交改动 ⇒ `BLOCKED` 带原因，**不自动 stash、不丢弃**（stash 会让「上次执行的产物去哪了」不可归因）。
  4. **写者切分**：`master` 归人、`helloai/t{taskId}` 归平台、`helloai/t{taskId}-s{subId}-a{n}` 归执行 agent 本人。平台**永不**写 `-s*-a*`，agent **永不**写 `t{taskId}`。
- **★术语纪律**：平台侧本地↔远端动作一律称**「发布」（publish，单向）**，**禁用「同步」（sync）**——「同步」暗示双向，双向写同名 ref 正是 §7.9.1 分叉的成因。代码命名同此（`GitGateway#publish` 而非 `sync`）。
- **不做什么（边界）**：
  - 不做外部 agent 分支到 `t{taskId}` 的合并（归 `GT-8` 合并队列）；
  - 不做冲突自动解（`GT-8` 已定「冲突即中止」）；
  - 不实现凭证签发（归 `GT-7`；本任务只消费凭证）。
- **前置**：`GT-7`（分支已存在 + 凭证可用）/ `GT-8`（合并队列 + 合并方向唯一）。`GT-1`~`GT-3` 完成后即可开发，但**验收**需 `GT-7`/`GT-8` 就绪。
- **验收判据**：内部 LLM 完成并审核通过后，远端 `helloai/t{taskId}` 前进且含其提交；外部 agent 随后 clone 得到该提交（**无需 agent 自建分支**）；平台在远端已前进时发布被拒而非覆盖；`--force` 路径在代码中不存在（静态检查 `setForceUpdate(true)` 零命中）；工作区有未提交改动时子任务 `BLOCKED` 且原因可读。

## 并行轨：部署形态能力（GT-10，不阻塞主链）

### GT-10 app 容器工作区可见性（同路径 bind mount）

> **状态**：待开始 ｜ **剩余动作**：两套部署的 app 服务以**独立 override 文件**（`docker-compose.volume.yml`）按需追加同路径 bind mount，由 `SANDBOX_VOLUME_ENABLED=true` 时 `-f` 加载；建议并入 `GT-2` 批次实施（GT-2 本就要改卷挂载链路，一次到位）

- **问题**：容器部署形态下 **app 进程看不见沙箱工作区**——`deploy/app` 的 app 服务只挂 `helloai-start.jar` + `./logs`，quickstart 的 app 无 volumes（jar 打进镜像）。`SANDBOX_VOLUME_HOST_PATH` 仅作为**字符串**传给沙箱服务（沙箱服务在宿主 daemon 上，能看见），app 容器自身不可见 ⇒ 平台侧 JGit `readFile` / `diff` 全部 `NoSuchFileException`。
- **影响面（已订正，勿再扩大）**：**仅容器部署形态**受影响。
  - 本地 IDEA 直跑 / 本地 docker（app 仍 IDEA 跑）→ **不受影响**，进程即宿主。
  - `deploy/app`（云服务器）、`deploy/quickstart`（一键包）→ **受影响**。
- **解法**：`- ${SANDBOX_VOLUME_HOST_PATH}:${SANDBOX_VOLUME_HOST_PATH}`（**容器内外同一路径** —— 一个变量同时服务 app 与沙箱服务，不引入 `SANDBOX_WORKSPACE_IN_CONTAINER` 第二个键）。
- **★空值陷阱 [实测]**：该写法**不得**无条件写入主 compose。`V` 为空或未定义时 `docker compose config` **RC=0 不报错**，而是静默退化为：
  ```yaml
  volumes:
    - type: volume
      target: ':'        # 匿名卷挂非法路径
  ```
  ⇒ 必须用**独立 override 文件**（`docker-compose.volume.yml`）承载，`SANDBOX_VOLUME_ENABLED=true` 时才 `-f` 追加：不启用时主文件零影响；启用时正确合并为 `type: bind`（两种情形均已实测）。
- **与主链关系**：**不阻塞** `GT-1`~`GT-3` 的开发（本地形态可完整开发与验收），只阻塞**容器形态的验收**。故列为并行轨，不占第 1~3 步的位置。
- **验收判据**：`deploy/app` 与 quickstart 在 `SANDBOX_VOLUME_ENABLED=true` 下 `docker compose config` 展开为 `type: bind` 且 source/target 同路径；app 容器内 `test -d $SANDBOX_VOLUME_HOST_PATH` 为真；GT-3 的 diff 在该形态下均有内容。`SANDBOX_VOLUME_ENABLED=false`（默认）时 compose 展开与升级前**完全一致**（零迁移）。

---

# 4. 验收判据（一行）

> 白名单仓库经 JGit 代 clone 进沙箱 `/workspace/repo`（commit 锁定）→ 执行器经平台侧工具做 git status/diff 审计、约束走 `repoPath` 读取 → 代码文件渐进提交 + 前置校验阻塞缺项 → reviewer 沙箱按提交代码 + 同一规范验证并引用 diff → agent 分支可被拉取做集成测试、独立 clone 隔离、合并触发验收机重跑 → 同一子任务的多次尝试各有分支且旧分支保留为审计证据 → **内部产出在子任务边界单向发布到远端，外部 agent 与后续内部子任务都基于同一最新基线**。**`GT-0` 实测六项出结论 + 五小步全通 + G-027 前置待验证项有结论**，即验收通过。
>
> **形态口径（`GT-10` 限定）**：主链（`GT-0`~`GT-9` + `GT-11`~`GT-12`）在**本地 IDEA 直跑形态**验收即可成立；「云服务器容器形态可用」需在 `GT-10` 收口后补一轮容器形态验收（`deploy/app` 与 quickstart 各一次）。**两种形态的结论分开记，不用本地结论替代容器结论。**

> **排期口径（2026-11-24 裁定，与视觉验收轴的关系）**：
> 1. **两轴并行、互不为前置**。本计划（`GT-x`，代码配置类证据轴）与 `plan/HelloAI 视觉验收与沙箱浏览器实施计划（2026-11-24）.md`（`VU-x`，界面游戏网页类证据轴）**同时可开工**，两者不共享代码落点（`GT-1` 动 `helloai-execution`/`helloai-shared` 的 git 依赖与仓库白名单；`VU-1` 动 `helloai-core` 的 `AgentTask`/`AgentLoopInput`/`ChatModelToolLoop`）。
> 2. **唯一交叉点在「前端代码任务」**：一个前端代码任务的验收 = `GT-6`（静态 + 构建）+ `VU-4`/`VU-5`（运行时与截图），**缺一不可**。其余任务形态各走一条轴：纯后端代码任务只需 `GT-6`；纯界面 / 游戏网页任务只需 `VU-x`。交叉点排期时以「谁先收口谁先跑」为准，不互相阻塞。
> 3. **视觉直读先行不改本计划**：`ATT-2` / `VU-1` 打通后，reviewer 可直读截图，但本计划的 `GT-6` 验收（编译 / 规范命中 / 测试）**不依赖视觉通道**，不得因视觉通道未通而缓办。

# 5. 引用材料

- 调研（形态定案来源）：`research/HelloAI git 能力集成与外部代码任务调研（2026-11-24）.md`
- 并行轴（同一结论的三条证据轴之一）：`plan/HelloAI 视觉验收与沙箱浏览器实施计划（2026-11-24）.md`（`VU-1`~`VU-7`）；事实待核清单 `plan/运行时 UI 验收能力待核清单（2026-11-05）.md`（`RUI-1`~`RUI-10`）
- 差距表：`G-033`（承载体，待办①结论已回填）、`G-027`（Git 线⑤⑥实施承载体）、`G-037`（第 3 项② `repoPath` 为 GT-3 前置）
- 设计：`design/HelloAI 交付编排能力设计.md`、`design/HelloAI 任务约束类型化设计（正式引入）.md`（§4 末行）、`design/adr/ADR-008-task-decomposition-derivation.md`（§13.5 派生链的 git 拓扑）
- 代码落点（`GT-12` 前置勘察）：`PlannerAnalysisServiceImpl#confirmPlan`（`confirmPlan` 唯一放行口，类上仅 `@Service` + `@RequiredArgsConstructor`，无类级 `@Transactional`）、`helloai-job/src/main/java/com/helloai/job/task/PlanAttemptDispatchTask.java`、`ReviewerRecheckTask.java`（异步任务范式）
- 报告：`review/HelloAI G-037 任务约束真机验证报告（内外组对比，2026-11-24）.md`（§5）