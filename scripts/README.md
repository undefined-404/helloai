# scripts/ 索引

本目录是 HelloAI 的验证与运维脚本库。共 **87 个 PowerShell（`powershell/` 86 + 根目录 `run-it-local.ps1`）+ 35 个 Shell + 1 个 Java 工具 + 1 个 SQL**（**2026-10-12 按 `git ls-files` 重新校准**：PowerShell 84→86 = 当日新增的两个 REF-8 脚本；Shell 28→30 = 同批 macOS 侧补齐的同名 .sh；**2026-10-13 Shell 30→31 = 新增 `shell/verify-provider-catalog-parity.sh`（M-2③ 供应商注册信息一致性守卫，`794cd631` 建、`d20a0160` 扩全；同批 `shell/gen-provider-catalog.py` 为 .py，不属本行口径）**；**2026-10-15 Shell 31→32 = 新增 `shell/verify-skill-capability-parity.sh`（`M-9` 技能/能力三源 parity 守卫，`7ca82558` 建、`D-2026-10-15-1` 扩为 S1~S5）——该行自 2026-10-14 起漏同步，是跑门禁时由 `verify-executor-doc-parity.sh` S6 当场抓出的欠账，非本次新增所致**；此前该行自 2026-10-11 起未随新增同步——**新增脚本必须先 `git add` 再同步本行**，否则 `verify-executor-doc-parity.sh` S6 红；**2026-10-15 CI 门禁脚本 4→5 = 新增 `ci/check-shell-portability.sh`（Shell 可移植性守卫，`LOG-20261015-008`）—— 把门禁 3 上「守卫自身失真」的三类缺陷（bash 4 语法致静默假绿 / `$VAR` 后紧跟多字节字符致静默假绿 / 管道右端 `grep -q` + `pipefail` 致间歇假红）变为机械断言。Shell 计数不变：S6 的 `real_sh` 只数 `scripts/shell/*.sh`，`ci/` 侧脚本计入 CI 门禁脚本一栏**；**2026-10-18 Shell 32→33 = 新增 `shell/verify-websearch-e2e.sh`（联网搜索开关 E2E，S-6 验证集同批）——该行自 2026-10-15 起漏同步，由 `verify-executor-doc-parity.sh` S6 当场抓出（README 记 32 / 在册 33），非本次新增所致**；**2026-10-24 Shell 33→35 = 补记 `shell/verify-plan-status.sh`（计划逐项状态守卫，`f32a4558`）与 `shell/verify-attachment-budget.sh`（附件预算 E2E，`1ca5dd84`）——二者 2026-10-23 入库时漏同步本行，由 `verify-executor-doc-parity.sh` S6 当场抓出（README 记 33 / 在册 35），非本次新增所致**），另有 **5 个 CI 门禁脚本 + 1 个架构冻结基线**（`scripts/ci/`）。

- `ci/`：**跨平台 CI 门禁与架构守卫**（bash，Git Bash / Linux CI 通用；详细见下方第六节）
- `powershell/`：Windows 侧（pwsh / Windows PowerShell 5.1），含全部规范类与大部分 E2E 验收脚本
- `shell/`：macOS/Linux 侧（zsh/bash），E2E 验收为主 + 少量构建运维
- `powershell/tools/`：脚本共享工具（PgExec.java，JDBC 通道，供 verify-reviewer-dual 等在无 docker psql 时执行 SQL）
- `sql/`：一次性数据回填 SQL
- `shell/.tmp/`：E2E 运行期日志与临时产物，已被根 `.gitignore`（`.tmp/`）覆盖，不入库
- `powershell/logs/`：脚本运行期日志与 jar 解包残留，已由根 `.gitignore`（`scripts/powershell/logs/`）覆盖，不入库

> **脚本头注释里的「规则 N」**（如「规则 6：脚本 UTF-8 编码 + PS 5.1 单引号 + 拼接」）出自**本地 preflight 技能**
>（`.agents/` / `.qoder/` 下的 `helloai-preflight`，属本地产物、不入仓 2026-09-30）——仓库内没有对应文件，
> 按注释所述纪律执行即可，不必去找文件。2026-09-30 治理时已把脚本头里的死路径替换为「本地 preflight 技能」字样。

## 一、验收脚本（verify-*，66 ps1 + 25 sh）

命名即入口：`powershell -File .\scripts\powershell\<name>.ps1` / `zsh scripts/shell/<name>.sh`。
绝大多数前置为「后端 6565 已启动 + admin/$HELLOAI_ADMIN_PASSWORD」，个别需 Docker 中间件（postgres:15432 / redis:26379 / rabbitmq:25672 / minio:29000）。

### 运行前置：管理员口令环境变量

自 2026-10-07 起，所有调用管理员登录的验证脚本（`verify-*.ps1` / `verify-*.sh`）**不再内置默认口令**，
运行前需先导出环境变量 `HELLOAI_ADMIN_PASSWORD`：

```bash
export HELLOAI_ADMIN_PASSWORD='<你的管理员登录口令>'   # zsh / bash
```

```powershell
$env:HELLOAI_ADMIN_PASSWORD = '<你的管理员登录口令>'   # PowerShell
```

未设置时脚本会 fail-fast 退出（提示「请设置环境变量 HELLOAI_ADMIN_PASSWORD」）。

### 双平台对实现（ps1 与 sh 各有对应版本）

| 领域 | ps1 | sh |
|---|---|---|
| C3 灰度六件套（env/events/reconcile/rollback/route/seed） | verify-c3-*.ps1 | verify-c3-*.sh（共享库 c3-common.sh） |

> **C3 六件套现状（2026-10-10 实测）**：`verify-c3-rollback` 判 **N/A** —— 它依赖的 `gray-percent`
> 开关已随 G-002「单轨硬切」删除（`D-2026-09-30-3`：回退手段 = `git revert`），演练前提不存在；
> 脚本已改为显式打印 N/A 并 `exit 0`（判 N/A 而非 PASS，不留恒定红的假失败）。其余五件套仍适用。
| AgentHub 值班 P0/P1 | verify-agenthub-duty-e2e.ps1 | verify-agenthub-duty-e2e.sh |
| Dashboard 值班概览 | verify-dashboard-duty-leases.ps1 | verify-dashboard-duty-leases.sh |
| MCP 鉴权 / 业务闭环 | verify-mcp-auth.ps1 / verify-mcp-e2e.ps1 | 同名 .sh |
| REF-8 内部 LLM 能力面（2026-10-12） | verify-mcp-external-surface.ps1 / verify-internal-llm-tools-e2e.ps1 | 同名 .sh（macOS 侧补齐；两者都不替代 verify-inner-loop-e2e（主链闭环）） |
| MinIO 附件链路 | verify-minio-artifact.ps1 | verify-minio-artifact.sh |

> **MinIO 附件链路前置（2026-10-10 订正）**：`-Token` 取管理端登录态，脚本按 **`X-Admin-Token`**
> 头发送（旧写法 `Authorization: Bearer` 实测返回 401，会让 G2 假失败）；`-MinioHealthUrl` 传
> **基址**（如 `http://localhost:29000`）即可，脚本自行补 `/minio/health/live`（基址直打根路径是 403，同样会假失败）。
| Planner 自动拆解 | verify-planner-decompose.ps1 | verify-planner-decompose.sh |
| C3 Step4 灰度观察 | watch-step4.ps1 | watch-step4.sh |

> **REF-8 内部 LLM 能力面（2026-10-12 新增，双平台）**：verify-mcp-external-surface（**对外 MCP
> `tools/list` 不含 `internal_*`**，验 `D-2026-10-12-5` B；**必须走 SSE 通道**——`GET /api/mcp/tools`
> 与 `POST /api/mcp/jsonrpc` 的 `tools/list` 都是**手写静态清单**，与 spring-ai 自动配置聚合出的真实
> 对外面**不同源**，断言它们等于没断言）、verify-internal-llm-tools-e2e（**内部 LLM 真的自己调
> `internal_*`**：建「必须先读附件再作答」的任务 → 子任务一被指派就以**该执行者身份**上传探针附件 →
> 断言 `internal_get_sub_task_detail` / `internal_read_attachment` 被调用、且 marker 经工具输出回流进
> 交付物；对应 `REF-8.4` 收敛判据③「能力可验证」）。
> macOS 侧 `.sh` 相对 ps1 的增强（**不改变断言口径**）：① SSE 回包改「轮询等目标 id 出现」而非固定
> sleep；② 内部 LLM 脚本额外打印**探针附件上传时刻 ↔ 工具调用时刻**对照表，用于判 H3/H4 失败是
> 「附件没赶在执行前落地」还是「模型调了工具但没用内容」（两因结论完全不同）；③ 新增
> `--fail-on-foreign-assignee`（子任务被派给本次注册之外的 Agent 时立即退出，默认仅告警）；
> ④ **STEP2.8 打卡上班（默认开，`--no-check-in` 关）**——`AgentSelector.pickPreferred` 排序首位是
> `dutyRank`，而 dev 库通常已存在 score 更高的存量 `API_KEY_LLM` 候选（实测本机 4 个：score 40 的
> ONLINE 者必胜），此时 `force-access-type` **过滤不掉**同 accessType 的存量候选 ⇒ 不打卡则子任务
> 派给存量 Agent，探针附件无法以「该执行者身份」上传（403），H3/H4 恒红且**与内部 LLM 能力无关**。
> 打卡后本次 Agent dutyRank=1 胜出，离场 trap 自动 `checkOut`。
> ⑤ **Agent 来源：复用存量（`--reuse-agents` / 占用时自动）**——平台限「同一模型在同一角色下唯一
> （`API_KEY_LLM` + 未删除）」，dev 库已被常驻 `inner-<model>-<role>` 占满 ⇒ 注册路径必 **409**；
> 复用走管理端列表（`GET /api/admin/agents/list` 的 `AgentListItemVO.apiKey`）取现存 Agent，
> **不改库、不绑凭证**，用其自带托管凭证。
> ⑥ **两层结论 + 证据先出**：流程未收敛（`DEAD_LETTER`/`BLOCKED`/超时）**不**当场退出，仍跑 STEP8
> 采集工具调用证据；退出码 0=两层皆过 / 1=判据层红 / 2=判据层绿但流程层未收敛。起因见下节「实测订正」。
>
> **实测订正（2026-10-12，macOS 首次跑通）**：① `GET /api/sub-tasks?taskId=` 已随「描述性路径」整改
> 改名为 **`/api/sub-tasks/list?taskId=`，旧路径实测 405** —— ps1 原写的是旧路径（Windows 侧会卡在同一处），**2026-10-12 已同批订正**；
> ② 平台 `auto-review-max-rework`（默认 3）会让严格评审把「交付物缺原文/无 VERIFICATION 段」的子任务
> 打到 `DEAD_LETTER`——这属**评审/交付格式**未收敛，与「模型会不会调 `internal_*`」是两回事，故拆两层结论。

### 仅 ps1（Windows 侧）

- **规范红线**：verify-dependency-direction（依赖方向）、verify-code-style-p0-layer（Controller 分层）、verify-code-style-p1-paths（路径命名）、verify-code-style-p1-ui-sync（前后端路径同步）、verify-contract-first（契约先行）、verify-tool-matrix（工具面一致）
- **任务/拆解/澄清**：verify-a1-task-policy、verify-a2-skill-derive、verify-a3b-agent-edit-skills、verify-674-remove-specialization、verify-requirement-clarify-structured、verify-step9b-depends-on、verify-task-running-spec-phase-b、verify-inner-loop-e2e、verify-conversation-flow-e2e、verify-e2e-batch-a、verify-m5-scenarios、verify-g014
  > 同批订正 verify-inner-loop-e2e 的 **STEP8 端点**：`/api/sub-tasks/{id}/timeline` 已在 `dcd2ae75`（2026-08-04「接口路径整改至 §8.2 描述性风格」）改名为 `/api/sub-tasks/listTimelineBySubTaskId/{id}`，旧路径必 404；并补工具调用证据输出（`tool_call_started` 按工具名聚合，仅打印不断言——该脚本任务是纯文本无附件）
- **执行/调度**：verify-execution-dispatch-guard（启动期 fail-fast 守卫）、verify-poller-e2e（DB Poller）、verify-subtask-redispatch-auto-execution（重派自动执行；`-Scenario blocked|offline` **两场景互斥** —— target agent 硬编码同一模型 `deepseek:deepseek-v4-pro`，而平台限「同角色同模型唯一」，先跑的占用后跑的即 409；同批新增改派后 `task_timeline` 保留断言）、verify-subtask-deadletter（死信兜底）、verify-agent-execution-preview、verify-agent-llm-connectivity（真 LLM 冒烟）、verify-single-track-e2e（内部单轨：计划/执行/评审/报告全绿）
- **审查/质量**：verify-reviewer-dual（双评审）、verify-quality-profile、verify-quality-dashboard、verify-artifact-content-review、verify-llm-conversation-stream
- **Agent/技能/配置**：verify-agent-skill-capability、verify-skill-packages（**2026-10-09 重写**：解析对象由 Java `KNOWN_SPECS` 源码文本改为 `skills/plugins/*.md` 的 YAML frontmatter；4 组断言等价保留 + 新增 E1/E2/E3 结构约束与 F 测试资源遮蔽守卫）、verify-platform-config、verify-api-key-verify、verify-llm-provider-models、verify-admin-authz、verify-attachment-version
- **MCP/门铃/外部 Agent 入职**：verify-mcp（最小连通）、verify-mcp-session-e2e、verify-doorbell-e2e、verify-onboarding（+ -doorbell / -heartbeat / -pull / -submit 五步链）、verify-external-agent-e2e（外部 CLI_CLIENT 多 Agent 实接单，含 -AssertOnly 断言模式）
- **MQ**：verify-outbox-relay-confirm-e2e（Outbox/Confirm 失败路径，配合 start-sb-e2e-mq.ps1）
- **备份/恢复**：verify-backup-restore（触发备份 → 校验台账字段 → preflight → **真恢复进一次性探针库**（`helloai_restore_probe`）逐表对账 → 清理；三类非法恢复必拒绝由单测 `RestoreGateTest` 覆盖，app 一键恢复端点按停机操作**不**自动跑）

### 仅 sh（macOS/Linux 侧）

verify-login-e2e、verify-requirement-clarify、verify-websearch-e2e、verify-planner-chat-dual-mode、verify-deps-context-e2e、verify-external-executor-e2e、verify-redispatch-in-progress、verify-provider-catalog-parity、verify-skill-capability-parity（另有与 ps1 对实现的 12 个见上表）

**OpenSandbox 集成契约守卫（静态，已在门禁 3 内执行，2026-10-17 建，S-6 验证集）**：`shell/verify-opensandbox-contract.sh` —— 断言 ① S-1~S-5 契约文件齐全（SandboxSpec / ExecutionPolicy / Provider / Probe / Environment / Properties）；② `openSandbox()` 诚实五边界**恰好 3×ISOLATED**（filesystem/resource/credential；network/process 保持 PARTIAL，防「安全沙箱已完成」误读）；③ `ENVIRONMENT_RESOLVED` 埋点语义不变（仍在 `RuntimeTurnExecutor` step=7 按事实记录 environment + accessType，既有沙箱观测日志零回归）；④ Provider 默认关闭（`enabled=false`，REF-6.13）。纯文本 grep，零外部依赖，CI 可跑。

**《差距表》结构守卫（bash 实现，**已在门禁 3 内执行**）**：`shell/verify-doc-gap-table.sh` —— 《差距表》结构守卫，四项只读断言：① 表格完整性（每行竖线数 == 表头，防裸 `|` 切格）② 无转义竖线 ③ 处置列首词 ∈ 九态词集（`协作规约 §6.3`）+ 一行一态 + 矩阵 20 行齐备 ④ 矩阵两列无过程叙述信号（日期 / 用例数 / 施工动词 / 分期标签 / 代码行号）。建立背景见 `LOG-20261009-014`；**2026-10-11 由 zsh 改 bash 实现并接入 `ci-gate.sh` 门禁 3**（原 zsh 在无 zsh 环境 `emulate: command not found` 后落空却仍 EXIT=0 —— 静默假绿，致状态词漂移累积），见 `LOG-20261011-010`；配套口径见《差距表》§0「状态词表」。

**交付文档一致性守卫（无运行时依赖，**已在门禁 3 内执行**）**：`shell/verify-executor-doc-parity.sh` —— 治「代码改了、交付给外部 Agent 的说明书没跟」这类漂移。五组只读断言：**S1 工具面三源静态对齐**（`onboarding/executor/guide.md` §0.1 表格 ↔ `McpController.TOOL_NAMES` ↔ `McpMcpServer` 的 `@Tool(name=…)`，并校验标题声明数量）——是 `verify-tool-matrix.ps1` 的**离线版**（后者需后端在跑 + 管理员口令）；**S2 已知错误口径黑名单**（「每前置 N 字符」误述 / `getSubTaskDetail` 与「等价」同行）；**S3 关键口径必备词**（取回链 / 总预算口径 / 可见性判据，交付手册与内部手册各一份）；**S4 内部手册 ↔ 拼装产物**（改源未重跑 `assemble-manual.ps1` 即红）；**S5 文档限额数字 ↔ 代码常量**（执行侧 `DEP_CONTENT_MAX_CHARS` 与核验侧每附件/总计）；**S6 本文件的脚本计数 ↔ 仓库在册实数**（`git ls-files` —— 索引里的数字不许悄悄过期）。**bash 实现、零 zsh 依赖**（三平台可跑；正则类断言用 `python3`，属三平台标准件；`verify-doc-gap-table.sh` 已于 2026-10-11 同批由 zsh 改 bash 并接入门禁 3）。建立背景与扰动验证见 `LOG-20261010-012`。

> **S6 的口径契约（改本文件开头那一行时须知）**：S6 按**标注词**解析——须保留 `共 **N 个 PowerShell（…）+ N 个 Shell + N 个 Java 工具 + N 个 SQL**` 与 `N 个 CI 门禁脚本 + N 个架构冻结基线` 这些措辞；改坏措辞不会静默通过，而是判「无法解析」。口径为**仓库在册**（`git ls-files`）：on-disk 会混入 `powershell/logs/` 下解包 jar 的残留（实测 sql 在册 1 / on-disk 13）。故**新增脚本后先 `git add` 并同步本行计数**，二者缺一即红。

**事件码 parity 守卫（无运行时依赖，**已在门禁 3 内执行**）**：`shell/verify-event-key-parity.sh` —— **REF-6.8 收口后（`LOG-20261011-006`）职责转为「事件码服务端单一来源」**：断言显示通道写点码 ∪ 库内真值 **⊆** `TimelineEventCode` 枚举（缺条目即前端查目录无 label ⇒ 回退裸英文），并断言**前端无 EVENT_META / sequenceFlow.LABEL 硬编码残留**（防单一来源被复制回前端）。两种模式：

- **静态（默认，CI 用，零依赖）**：写点码集 = 邻接形态字面量 `"<snake_case>",` 紧邻 `AgentRole.`；断言 ⊆ 枚举 + 前端无残留两处。
- **`--db`（本地/运维，地面真值）**：再叠加库内 `SELECT DISTINCT event_type FROM task_timeline`（并集），覆盖常量传参 / 辅助方法参数 / 历史遗留码等静态盲区；Docker / 容器不可用时 SKIP（不制造假失败，协作规约 §27）。

建立背景、存量债务（46 个未登记码）见 `LOG-20261010-013`；单一来源收口与前端字典化见 `LOG-20261011-006`；判据锚点 `REF-6.8`。

**供应商注册信息一致性守卫（bash，**已在门禁 3 内执行**）**：`shell/verify-provider-catalog-parity.sh` —— 治「预置目录 / 迁移种子 / `application.yml` / Java 兜底常量」四处的内置供应商**默认模型漂移**（历史缺陷 `D-a`~`D-f`：`moonshot-v1-8k` 已下线 ⇒ 密钥验证 404 直接根因；`deepseek-chat` / `qwen-plus` / `MiniMax-Text-01` 已不在模型目录 ⇒ 注册被拒）。断言：① **覆盖完整性**——从 `providerCatalog.ts` 反解全部 `providerCode`，与「有种子集 `SEEDED` ∪ 显式无种子白名单 `NO_SEED`」集合比对，归类漏了即红（防新增预置**静默不受保护**）；② **四方比对**（有种子项）——预置 `defaultModel` ↔ 种子 `llm_provider_model.is_default=1` ↔ `application.yml` `default-model` ↔ `LlmProviderKeyVerifyServiceImpl.FALLBACK_MODELS` / `DeepSeekProviderChatClientFactory.DEFAULT_MODEL`；③ **全预置结构性检查**（含无种子项）——`protocolType` ∈ 工厂支持枚举、`baseUrl` 不得自带 `/v1`（否则拼成 `/v1/v1/chat/completions`）。**双向反证**：`--inject-deviation`（篡改期望）/ `--inject-catalog`（篡改目录副本，副本落 `tmp/`）均须 EXIT≠0。**真值口径**：唯一真值是 `llm_provider_model.is_default=1`（`application.yml` 自身也会漂移——2026-10-13 实测其 2/4 为陈旧值）。建号 `M-2③`（`plan` §11）；建立 `794cd631`、扩全 `d20a0160`，见 `LOG-20261013-015`/`-016`。

**技能/能力词表一致性守卫（bash，**已在门禁 3 内执行**）**：`shell/verify-skill-capability-parity.sh` —— 治「模型能力谓词 ↔ 技能标签 ↔ 技能包引用边」三处漂移。断言五项：`S1` **三源同集合** —— 后端 `AgentSkillDeriver.STANDARD_SKILLS` ⇔ 前端 `agentSkills.ts` ⇔ `SkillCapabilityRequirements.REQUIRED` 键集（任一处增删而另两处未同步 ⇒ 前端可选而后端判定不了）；`S2` **退役列无回写** —— `capability_skills` / `available_optional_skills`（`M-9` 已 `V112` 物理删列）只允许出现在白名单迁移内，出现在其他任何源文件即红（防「列的代码幽灵」复发）；`S3` **新迁移必须写谓词** —— 凡 `INSERT INTO llm_provider_model` 的新迁移须在语义位置写 `capabilities`，判定**剥 SQL 注释 + 词边界匹配**（整文件子串匹配会被「注释里出现 capabilities」绕过，属**自证陷阱**）；`S4` **技能包谓词词表闭合**（`D-2026-10-15-1`）—— 内置技能包 frontmatter 的 `requiredBaseCapabilities` 元素必须 ∈ 已登记谓词词表（**拼错的谓词会「放宽」而非收紧匹配**，属放宽型失效方向，必须 fail-closed）；`S5` **解析器键同步** —— `SkillPackageParser.KNOWN_KEYS` 与 `SkillPackage` record 分量须同时含 `requiredBaseCapabilities`（防「frontmatter 加了键但解析器不认」的契约半改）。**反证开关**：`--inject-missing-skill`（S1）· `--inject-ghost-column`（S2）· `--inject-legacy-insert` / `--inject-comment-bypass`（S3）· `--inject-bad-predicate`（S4）· `--inject-unknown-key`（S5）——**守卫的真实验证强度要用「独立手工注入」测，作者内置开关能拦 ≠ 不可绕过**。建立 `7ca82558`（`M-9`），扩为 S4/S5 见 `D-2026-10-15-1` / `LOG-20261014-009` / `LOG-20261015-001`。

**一次性/生成辅助**：`shell/gen-provider-catalog.py` —— 从 magpie `presets.go` + `models.dev/api.json` 生成 `providerCatalog.ts` 的结构骨架（`defaultModel` 取该 provider 当前可用文本对话模型的启发式默认；`baseUrl` 自动去 `/v1`；`monogram`/`brandColor`/`tagline` 留人工）。仅供策展审阅，**不直接改文件**；`M-1` 建号（`plan` §11），见 `LOG-20261013-015`。

## 二、运维启停

| 脚本 | 用途 |
|---|---|
| powershell/start-sb.ps1 | 启动后端（jar 新于 5min 跳过重建；日志 spring-boot-run.log，PID 写 .spring-boot-pid） |
| powershell/start-sb-e2e-mq.ps1 | 以 dispatch-mode=BOTH + 双开关启动，供 MQ E2E 使用 |
| powershell/restart-sb-mock.ps1 | 以 mock 执行模式重启（poller-e2e 等需要） |
| powershell/kill-old.ps1 | 释放 6565 端口（重启前调用） |
| powershell/clean-minio-bucket.ps1 | MinIO 桶清理（清库前置，P-1 批次 A4）：默认 dry-run 列举统计，`-Execute` 真实批量删除（DeleteObjects ≤1000/批，删后复核），`-Prefix` 缩小范围；纯 PowerShell SigV4 零依赖，`-Endpoint` 支持本地 29000 / dev 共享 39.106.204.43:29000 |
| powershell/run-redispatch-diagnose.ps1 | 一键编排「重启 → 等就绪 → 跑重派验收 → 打印排障 SQL」 |
| shell/build-all.sh | 全模块编译打包（JDK17 + IntelliJ 内置 Maven，跳过测试） |
| shell/run-core-tests.sh | 运行 helloai-core 指定单测 |
| shell/stop-helloai.sh | 停止后端（macOS/Linux） |

## 三、外部 Agent 值班/守护（powershell/）

- executor-onboard.ps1：外部 EXECUTOR 一键自助注册（生成 executor-config.json）
- executor-duty.ps1 / executor-api.ps1：值班动作轮询 / REST 业务端点调用
- outer-trae-daemon.ps1、qoder-ceshi-daemon.ps1：特定外部 Agent（trae / qoder）常驻值班守护
- qoder-ceshi-checkin.ps1 / -doorbell.ps1 / -poll.ps1：qoder-ceshi 的 checkIn / 门铃 SSE / REST 轮询
- mcp-sse-keepalive.ps1：MCP SSE 长连接保持

## 四、一次性/调试工具

| 脚本 | 用途 | 备注 |
|---|---|---|
| powershell/assemble-manual.ps1 | 从已审章节确定性装配 executor-duty 手册（字节级幂等） | 被 doc/manual/executor-duty/06 引用，保留 |
| powershell/backfill-task-iterations.ps1 | V42 历史任务迭代记录回填（调 POST /api/tasks/backfillTaskIterations） | 一次性 |
| powershell/count-testcases.ps1 | 统计 surefire XML testcase 数 | 小工具 |
| powershell/wait-verdict.ps1 | 等指定子任务审查结论 | **一次性调试**：含硬编码 subTaskId `2097935069198065670`，复用需改 ID |
| powershell/watch-step4.ps1 / shell/watch-step4.sh | C3 Step4 全量灰度持续观察 | 阶段性 |
| sql/backfill-timeout-reassign-timeline.sql | 超时重派 timeline 回填 | 一次性 |

## 五、已知问题与维护约定

1. **硬编码 Windows 路径**：`start-sb.ps1`、`verify-code-style-p0-layer.ps1` 等含 `e:\yhzx\1027\helloai` 绝对路径，换机需改（历史遗留，列为后续治理项）。
2. **平台不对称**：验收资产 ps1 远多于 sh，macOS/Linux 贡献者无法完整复现验收流程（评审 6.5）。**新增验收口径优先下沉为 JUnit / ArchUnit（随 `mvn test` 跨平台执行），ps1/sh 只做薄封装**；跑 Maven 测试必须显式 `-DskipTests=false`（根 pom 打包默认跳过）。
3. **2026-09-28 整理记录**：
   - 移除误入的外项目脚本 `powershell/下单支付E2E验收.ps1`（Trade Cloud 电商验收，gateway :8299，与本仓库零关联；如需可从 git 历史恢复）。
   - 修复 `verify-code-style-p0-layer.ps1` 对已删除的 `tmp\package-backend.ps1` / `tmp\kill-backend.ps1` / `tmp\wait-backend.ps1` 的三处坏引用（内联等价实现，未经 pwsh 实跑）。
4. **`ci/check-shell-portability.sh` 的 P3 范围与已知例外（2026-10-15，`LOG-20261015-008`）**：P3（管道右端 `grep -q…`）判 **`scripts/`** 下的 `.sh`，且**与当前是否启用 `pipefail` 无关**——该形态的正确性取决于「生产者恰好一次小写入」这一**隐性前提**，还受 **source 它的调用方**的 `set -o pipefail` 影响（`shell/c3-common.sh` 自身不启用、其调用方 `verify-c3-*.sh` 启用）。**`deploy/` 侧同族 4 处登记为已知例外**（`docker-host-preflight.sh:350` · `init-rabbitmq-admin.sh:111,154` · `middleware/scripts/migrate.sh:77`）：均为一次性部署例程、改动风险大于收益；纳入方式 = 把 P3 的 scope 判据由 `scripts/` 放宽到 `all`。另：`| head -N` 形态本次**未**纳入 P3（多在**赋值**中取首行、其退出码不被检查），若将来出现同族实例再收窄。

## 六、CI 门禁（ci/，2026-09-29 新增）

背景：2026-09-29 架构与质量审计发现本仓库**无任何 CI**，且根 POM 默认 `skipTests=true`，导致「单测全绿」全靠人工执行、且极易得到「0 用例通过」的假绿结论。本节脚本把验证变成可自动执行、不可绕过的门禁。

| 脚本 | 用途 |
|---|---|
| `ci/ci-gate.sh` | **主门禁**：固定可用 JDK → 构建+真实单测（显式 `-DskipTests=false`）→ 断言「用例数 > 0」→ 架构漂移冻结 → 前端 type-check/build。**用例数计数口径（2026-10-01 修正）**：按 surefire XML 内的 `<testcase>` 元素计，**不可**读 `<testsuite tests="N">` 属性——JUnit5 `@Nested` 用例会被写进外层类 XML，但该文件 `tests` 属性仍为 0（实测 `AgentProviderResolverTest.xml`：`tests="0"` 而 `<testcase>` 12 个），属性口径会使本仓库漏计约 **639** 个用例（1144 vs 真实 1783） |
| `ci/lib-jdk.sh` | JDK 解析库：优先 `$HELLOAI_JAVA_HOME` → `$JAVA_HOME` → 探测 `~/.jdks/*`；**黑名单排除 ms-17.0.19**（本机必然 JVM 崩溃）；并**实测 `java -version` 大版本必须为 17**（防 Gitee 自有主机上 Agent 自带的 JDK 8 被误用） |
| `ci/check-arch-freeze.sh` | 跨域依赖守卫。**2026-10-02 起规则表改为「生成式」**（20 条手写 → 按依赖链生成），并补齐三类盲区（A1 组 2 由 4 条硬编组合扩为全部有序域对；A2 扫描范围由 `helloai-core` 扩至 `helloai-api`/`job`/`mq`/`start`；A3 新增「前向跨域实体泄漏」计数）。**2026-10-23 域链由 6 扩至 9**，补登记此前未纳入守卫的 `knowledge` / `event` / `dlx`（规则随生成式自动从 70 条扩到 154 条，既有域相对顺序与语义不变）。**分两档**：组 1/2（反向依赖 + 跨域 Mapper）「只降不升」→ **硬拦截**；组 3（前向依赖 + 前向实体泄漏）→ **仅提示不拦截**。`--update-baseline` 刷新冻结基线，`--verbose` 打印明细。**条数一律由 `DOMAINS` / `NON_CORE_MODULES` 长度推导，勿在任何位置硬编码**。**性能**：每目录只全树抽一次 import 行 + 一次 awk 完成全部计数（子进程数与规则数无关），勿退回「每条规则一次 grep」（本机单次 grep ≈ 300ms） |
| `ci/arch-baseline.txt` | 冻结基线（**154 条**规则的计数快照，自动生成，勿手改数字；按组分段注释，组 3 段标注「仅提示」） |
| `ci/host-prepare.sh` | **自有主机侧准备与自检**（Gitee Go 主机组跑 CI 时用）：默认只检测；`--install` 装 JDK17/Maven/Node20；`--swap 2G` 给 4G 内存机器建 swap |
| `ci/check-shell-portability.sh` | **Shell 脚本可移植性守卫**（2026-10-15 建，`LOG-20261015-008`）。治「**守卫自身失真**」——守卫存在 ≠ 守卫生效，守卫报错 ≠ 报了真错。三项只读断言：**P1** bash 4+ 专属语法（关联数组 `declare -A` / `local -A` / `mapfile` / `readarray` / `local -n` / `wait -n` / `${V^^}`）——macOS 自带 bash 3.2 下**该形态能「中止脚本却返回 0」**（实测：`declare -A X=( [k]=v )` 退化后下标走算术求值 ⇒ `set -u` 致命错 ⇒ 退出码 **0**；对照普通变量未绑定返回 1）；**P2** `$VAR` 后紧跟非 ASCII 字符（须写 `${VAR}`）——bash 3.2 + UTF-8 locale 下会把多字节字符**首字节并入变量名**，同样「中止却 EXIT=0」；**P3** 管道右端 `grep -q…`——命中即退出 + `set -o pipefail` ⇒ 上游收 SIGPIPE(141) ⇒ **间歇假红**（实测同族判据 270 次判里误判 **187 次**）。**反证三连**：`--inject-bash4` / `--inject-mb` / `--inject-pipefail` 均须 EXIT≠0；另有**反空转地板**（bash 实现数 < 20 即红）与**自证**（未产出结论行即判未生效）。P1/P2 覆盖全部在册 `.sh`，P3 覆盖 `scripts/`（`deploy/` 侧同族 4 处为**已登记例外**，见 §五.4） |

常用命令：

```bash
bash scripts/ci/ci-gate.sh                    # 全量，等价于 CI
bash scripts/ci/ci-gate.sh --quick --skip-ui  # 本地快速自检
bash scripts/ci/check-arch-freeze.sh          # 只跑架构冻结校验
bash scripts/ci/check-shell-portability.sh    # 只跑 Shell 可移植性守卫（反证开关见脚本头）
bash scripts/ci/host-prepare.sh               # 自有主机自检（只读，不改系统）
```

平台接入：`.workflow/helloai-ci.yml`（Gitee Go，对应本仓库远程）与 `.github/workflows/ci.yml`（GitHub Actions 备份）。**平台配置只是薄封装**——门禁语义全在上表的脚本里，因此本地执行同一脚本即可复现 CI 结论（这正是 §5.2 约定「新增验收口径优先下沉为可跨平台执行的形式」的落地）。

**架构红线门禁的两层分工（2026-09-30 明确）**

| 层 | 脚本 | 语义 | 是否进 CI |
|---|---|---|---|
| 冻结层（增量） | `ci/check-arch-freeze.sh`（bash，跨平台） | **§6 全量 + §7.1 + 前向实体泄漏**，**154 条规则（生成式）分两档**：**组 1/2（反向依赖 + 跨域 Mapper）「只降不升」硬拦截**（组 1 现已全部归零，故 112 条 block 规则均为 0 值回归护栏）；**组 3（前向依赖 `planner->agent`/`task->agent` + 前向实体泄漏 40 条）仅提示**——前向是 §6 合法方向，也是「端口反转」的承载方向；实体泄漏不拦截但**记数暴露**，使其与反向依赖一样「看得见才可能被清偿」。**扫描范围**：core 九域 + `helloai-api`/`job`/`mq`/`start` | ✅ 门禁 3 |
| 断言层（存量/命名） | `powershell/verify-dependency-direction.ps1` | 严格断言「应为 0」的红线（2026-10-01 起 `system->task` 已端口反转清零，**亦为严格 0**，不再有 `[DEBT]` 豁免）+ **`@MapperScan` 注册守卫**（漏注册包 → 启动失败） | ❌ 本地/评审辅助 |

```bash
# 两层都跑（冻结层跨平台可进 CI；断言层需 Windows PowerShell）
bash scripts/ci/check-arch-freeze.sh
powershell -File scripts/powershell/verify-dependency-direction.ps1
```


**Gitee Go 的两种执行形态（2026-09-29 补充）**

| 形态 | 插件 | 消耗额度 | 环境 |
|---|---|---|---|
| 云端构建机（原方案） | `build@maven` / `build@nodejs` | **消耗核分**（= 运行分钟 × CPU 核数） | 开箱即用（CentOS 8.3 基础镜像 + 阿里源） |
| 自有主机组（现方案） | `shell@agent` + `hostGroupID` | **不消耗核分** | 需自备 JDK17/Maven/Node20（用 `host-prepare.sh` 一键自检/安装） |

依据：Gitee 官方计费规则「仅当您使用 Gitee 提供的云端构建资源，且流水线中的任务属于计费模型时，任务运行才会消耗核分」。回退到云端形态：`git show b02dce3:.workflow/helloai-ci.yml > .workflow/helloai-ci.yml`。

**自有主机组的前置开关（换主机时必看）**：流水线需在 Gitee 侧打开该任务的「**是否克隆代码**」开关，主机上才会有仓库副本（前置：主机 SSH 公钥已加入 Gitee）。脚本在找不到仓库时会以非零码明确失败，**不会假绿**——这条与「Agent 自带 JDK 8」是自有主机上仅有的两个静默失效点。

**尚未覆盖（后续项）**：本门禁只做「可编译 + 单测 + 依赖方向增量 + 前端」四类校验；**B 级集成测试已同日落地并与门禁 5 一起挂接**（详见下方第七节与 §0.3），已在本机 Docker 实跑**全绿**（2026-10-05：**7 个 IT 类 / 27 用例**；`run-it-local.ps1` 一键复跑）；E2E 仍依赖需 Docker 的 ps1/sh 脚本（审计建议 #6，待排期）。

## 七、本地 B 级集成测试一键跑（scripts/run-it-local.ps1，2026-09-29 新增）

本机 Docker 上实跑 Testcontainers B 级 IT（B1 Flyway 全量 apply / B2 MQ 幂等 / B3 Outbox 事务边界 / B4 状态机 CAS / TaskAgentMember Phase B / TaskRunningSpec Phase B / SubTaskBackoffClock Phase B，共 **7 类 27 用例**）的一条命令入口，封装三前置：会话级 PATH 清洗（剔除注册表坏项/残片，**不代改注册表**）、`DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`（新版 Docker Desktop 强制 Host 头，须配 testcontainers 2.0.5 / docker-java 4.x）、`JAVA_HOME=ms-17.0.20.1`（17.0.19 必崩，见 ci/lib-jdk.sh 黑名单），再执行 `mvn -s .tmp\settings-aliyun.xml -pl helloai-start -am test -DskipTests=false -Dtest=*IT`。

```powershell
powershell -File .\scripts\run-it-local.ps1
```

- **输出**：完整日志 `.tmp/it-run-local.log`（UTF-16，Select-String 可自动识别）；控制台汇总各 IT 类的 Tests run 行（当前 7 类）+ 聚合行；退出码 0 = 全绿。
- **退出码**：2 = mvn 缺失 / 3 = docker CLI 缺失 / 4 = Docker 引擎不可达 / 5 = JDK 缺失 / 1 = IT 失败。
- **坏项提示**：脚本开头检测系统注册表 PATH 中的「以空格结尾的坏条目」与「相对路径残片」——本机实测 `C:\Program `（坏条目）与 `iles\Docker\Docker\resources\bin`（残片）是 Docker CLI 完整路径被分号劈开的两半，脚本给出合并修复命令，但仅会话级绕过、不代改注册表。
- **编码注意**：PS5.1 下 mvn(java) 输出为 GBK 字节流，脚本临时按 GBK(936) 解码保证日志与汇总中文正确（含 PS5.1 两个经典坑的规避：Stop 模式 stderr 会变 NativeCommandError、Tee-Object 无 -Encoding）。
