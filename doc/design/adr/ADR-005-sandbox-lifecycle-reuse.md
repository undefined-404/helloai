# ADR-005：沙箱生命周期改为「同子任务复用」（对齐 deepseek-harness 工作区语义）

| 项目       | 内容 |
| -------- | ---- |
| **编号**   | ADR-005 |
| **状态**   | Accepted（2026-10-25） |
| **日期**   | 2026-10-25 |
| **取代**   | `ADR-004 §2` 第 1 条生命周期口径（「每次执行即建即毁、不跨调用复用」） |
| **关联文档** | [`ADR-004`](ADR-004-sandbox-execution-chain-and-fidelity-gate.md)（执行链与判定接入）、[`archive/implemented/HelloAI 沙箱执行链设计方案（2026-10-17）.md`](../../archive/implemented/HelloAI%20沙箱执行链设计方案（2026-10-17）.md) §3.3（原设计实为「复用」，与实施分叉）、`LOG-20261025-003`（现象取证）、`LOG-20261025-004`（三方口径对照）、`LOG-20261025-005`（本次落地） |
| **决策类型** | 沙箱生命周期（复用 vs 即建即毁）+ 模型侧工作区契约 |

---

## 1. 背景与问题

`ADR-004` 落地 S-7 时把沙箱实现成「每次 `execute` 新建 → 跑一条命令 → `finally` 无条件 `kill()`」，
并在文档中记为「不跨调用复用，防残留文件影响下一个」。

**后果（实测，`LOG-20261025-003`）**：沙箱内文件**不跨调用保留**，模型无法对上一轮产物做增量编辑，
只能每轮把整篇正文重新 heredoc 一遍：

- MiniMax 执行「撰写企业现状与挑战…」子任务时，连续 20 轮反复重写同一份稿件，
  `WHOLE no_ws` 从 3820 字压到 2787 字后卡住（验收窗口 ≤2300），轮次耗尽 → `MAX_ITERATIONS` → BLOCKED；
- 每轮重写都要重发 40~48KB 上下文（`inputTokens=73569 / outputTokens=46905`）；
- 内容在 20 个版本间漂移，系统内不存在「当前版」，无法做定向修订；
- 10:55:28 出现铁证：上一轮 `cat > /tmp/w/doc.md` 成功，下一轮读同路径即
  `No such file or directory` / `FileNotFoundError`。

同期 DeepSeek 同批 4/4 成功，靠的是「每轮整篇重写、每轮砍 100~250 字、自写 `in_range` 判据、达标即交」——
即**用更高的 token 成本绕过缺陷**，不是缺陷不存在。

## 2. 决策

**沙箱改为「按子任务复用」的工作区**：

1. **作用域**：key = `subTaskId`。同一子任务内的多次 `internal_run_in_sandbox` 调用**复用同一沙箱**，
   写入的文件**跨调用保留**，可 `sed -i` / `python` 定点修改；**跨子任务必不共享**。
2. **保活**：每次执行成功即 `sandbox.renew(idleTtlSeconds)` 续期（默认 900s），空闲超时才淘汰重建。
3. **释放**：turn 结束由 `LocalExecutionCommandConsumer#runViaRuntime` 的 `finally` 调
   `SandboxExecutionService.release(subTaskId)`（成功 / 失败 / 异常 / 取消都释放）——
   保证「一次执行尝试的工作区不跨尝试残留」，同时兜住「不跨子任务共享」。
4. **丢弃重建**：命令超时（`exitCode=124`）或 SDK 异常（`-1`）⇒ 容器内状态未知，
   立即丢弃该槽，下次调用重建（对齐 DSH「timeout / 取消会关闭 shell 并从干净环境重启」）。
   **普通非零退出不丢弃**（`grep` 未命中、校验脚本报 False 正是正常工作方式）。
5. **退路**：`helloai.sandbox.opensandbox.reuse-enabled=false` 或 `subTaskId == null`
   （Reviewer / S-9 机械判定等无子任务上下文的调用）⇒ 回落原「即建即毁」一次性语义，行为零变化。
6. **并发**：同一沙箱内的命令**串行执行**（槽级 `ReentrantLock`），对齐 DSH
   「commands run sequentially」。

**模型侧契约同步改口**（`internal_run_in_sandbox` 工具描述 + 系统提示）：

- 明写「同一子任务内复用、上一轮文件这一轮还在」；
- 明写「改稿请定点修改，**不要每轮整篇重写**」；
- 明写「多步操作写在同一条命令里」；
- **保留**「沙箱内文件不是交付物，平台只采集对话正文」这条硬约束不变。

## 3. 理由（为什么对齐 deepseek-harness）

| 维度 | DSH `dsh-sandbox` + 两种 bash 工具 | 本决策 |
|---|---|---|
| 隔离层级 | **同世界进程监禁**（bwrap/Landlock/Seatbelt/ACL），README 明写「still shares the host kernel and filesystem」 | 保留远程容器（安全等级更高，不改） |
| 文件系统 | **持久**（共享宿主） | **同子任务内持久**（容器内工作区） |
| shell 状态 | `tool-bash`：每次新 shell（cwd/变量不持久）；`tool-bash-persistent`：跨调用持久 | 每次新命令，但**文件持久**（等价于 `tool-bash` 的「换 shell 不换盘」） |
| 对模型的交代 | 明写「什么持久 / 什么不持久」 | 工具描述 + 系统提示明写工作区语义 |
| 生命周期 | `tool-bash-persistent` 的 state 到 `exit` / 超时 / reset 才清 | turn 结束 `release` + 空闲 TTL 兜底 |

**核心判断**：DSH 换 shell **从不丢文件系统**；旧实现换的是**整个容器**，文件一起没了。
「换不换 shell」是有意义的隔离尺度，「丢不丢工作区」不是——后者只会把「编辑」退化成「重写」。

同时纠正 `ADR-004` 的一处**同文档自相矛盾**：原设计方案 §3.3 第 5 条写的是
「同一 Agent **复用同一沙箱**，执行结束后**不自动销毁**」，而 §5 的 S-7 实施清单写成
「`finally` **无条件销毁**」——实施单方面覆盖了设计意图且无人发现。本 ADR 明确取「复用」，
并把作用域从「agent」收窄为「subTask」（agent 维度复用会与设计方案 §3.4
「不跨子任务共享」直接冲突）。

## 4. 后果与边界

- **收益**：模型可增量编辑，不再整篇重写；轮次与 token 消耗显著下降；
  「内容漂移 / 无当前版」问题消除；执行时长可预测。
- **成本**：沙箱常驻带来资源占用（受「turn 末释放 + 空闲 900s TTL + 跨子任务不共享」三重收敛）；
  进程异常退出时靠 TTL 兜底回收。
- **安全边界不变**：零平台凭据 env、默认禁网、容器级 filesystem/resource 隔离、
  `requireInternalChain` 调用闸门、fail-closed 工具摘除——均未放松。
- **未覆盖**：`S-10` 危险命令守卫仍为后置登记项；进程重启后远端沙箱只能靠 TTL 自然回收
  （不做跨进程重连，`Sandbox.connector()` 留作后续按需启用）。
- **不做**：不改成「同世界进程监禁」（我们跑的是模型现场写的不可信脚本，容器隔离不能退）。

---

## 6. 修订记录：作用域升格为任务级（`SL-1` / `SL-2`，2026-11-12）

> 落地记录：`LOG-20261112`（A 段执行环境真机取证 / J 段 CI / K 段差距表登记）；对照依据：
> `research/沙箱内 LLM 执行流程三仓对照调研`（deepseek-harness / OpenSandbox / octop-harness 三仓，
> 结论「没有一家在执行轮结束时销毁工作区，工作区寿命与任务/会话对齐」）。
> 本修订**不改变**「同作用域复用 + 文件跨调用保留 + 命令串行」的核心决策，只改**作用域粒度**与**释放时机**。

| 原条款（§2） | 修订后（`SL-1`/`SL-2`） | 落地状态 |
|---|---|---|
| §2-1 作用域：key = `subTaskId`，**跨子任务必不共享** | key = `SandboxScopes` 解析出的 `(Kind, scopeId)` + 规格指纹：命中子任务 ⇒ `task:{taskId}`（**同任务子任务共享**，上游产物跨子任务可见、返工可续作）；会话技能 `scopeId` / 未命中 ⇒ `scope:{id}`（既有语义不变） | ✅ `SL-1`（2026-11-12，代码 + 测试已落） |
| §2-3 释放：turn 结束 `LocalExecutionCommandConsumer#runViaRuntime` 的 `finally` 调 `release(subTaskId)` | turn 末释放**默认删除**；改为任务终态（`DONE` / `CANCELLED` / 级联删除）事务提交后经 `SandboxExecutionService.releaseByTask(taskId)` 一次性销毁该任务全部规格槽（逐槽 `tryLock`，拿不到锁交给空闲 TTL）；**回滚模式** `scope=subtask` 仍保留 turn 末释放 | ✅ `SL-2`（2026-11-12：`TaskTerminalStateEvent` + `SandboxTaskTerminalListener`（`@Async` + `AFTER_COMMIT` + `fallbackExecution`）+ 三处终态入口发布 + `releaseByTask` 逐槽互斥与作用域缓存失效） |

**为什么改粒度（动因）**：`G-032` 真机取证（`task=2120174471616131074` / `subTask=2120174821500776454`）证明
「跨子任务必不共享」矫枉过正——同一任务的上游产物是下游子任务核验的引用对象（`storageUrl` + 文档名/章节/行号），
子任务级隔离迫使执行器每轮在沙箱内**重建**上游文档（5 轮），且 rework 轮拿不到上一轮文件。
这与 `SL-1` 的槽键 `(Kind, scopeId, specFingerprint)` 三元组配合：同一任务的多个子任务按「默认规格」命中同一槽，
不同规格（python 验算 / node 出图，`SKX-5`）仍分槽。

**中间态提示（已于 `SL-2` 闭合，2026-11-12）**：`SL-1` 单独落地时，turn 末 `release(subTaskId)` 已改为「按解析作用域释放」——
任务级模式下会释放**整个任务**的槽（比旧的「只清本子任务」更激进）。`SL-2` 删除 turn 末释放之前，
任务级共享会在每个 turn 末被清空，名存实亡。**故 `SL-1` 与 `SL-2` 不得拆批交付**（本批已同批完成）。

**回滚退路（与 §2-5 并列）**：`helloai.sandbox.opensandbox.scope=subtask` ⇒ 解析短路直接作用域、不查库，
且命令消费侧恢复 turn 末释放（`LocalExecutionCommandConsumer#isSubtaskScope`），
行为与 2026-11-11 前一致（子任务级 + turn 末释放），供部署回滚 / 排障使用。

## 5. 附属决策：工作区回读（交付物通道对齐，2026-10-25）

> 落地记录 `LOG-20261025-010`。本节是 §2 的直接推论，故并入本 ADR 而非另开一份。

**问题**：复用让文件在工作区里活下来了，但**平台只采集对话正文**（`AgentExecutionResult.output`）。
实测 MiniMax-M3 两次执行都是 20 轮在沙箱里写 `/workspace/draft.md`（3008 字真正文），
对话区只剩 604 字节写作过程独白 ⇒ 终态 `STOP` + 围栏齐全 ⇒ 假成功 ⇒ 评审连驳 3 轮 ⇒ 死信。
**正文早就写出来了，缺的只是「交出来」这一步。**

**决策**：`SandboxExecutionService#readWorkspaceFile(Long subTaskId)` —— turn 末（`release` 之前）
把工作区里「**含 Markdown 标题且最大**」的 `.md` 以 **base64** 回读；
`LocalExecutionCommandConsumer` 在「对话正文不构成可交付正文」时用它顶替。

| 决策点 | 取值 | 理由 |
|---|---|---|
| 触发条件 | 对话正文不可交付 **且** 工作区正文可交付 **且** 工作区 ≥ 对话正文 **2 倍** | 第三条防误伤：短交付类验收（300~400 字）的正常短答在判据一下也算「不可交付」，无此条会被沙箱草稿顶掉 |
| 判据归属 | `DeliverableDraft`（shared util） | 与收尾轮兜底共用同一事实源，防两处门槛漂移 |
| 候选选择 | 优先含行首 `#{1,6} ` 的文件，全无才退回最大 `.md` | 工作区常有更大的草稿/日志，按字节取最大会把垃圾当交付物 |
| 传输编码 | base64 单行 + Java 侧解码 | 输出通路按「多条 stdout 用 `\n` 拼接」渲染，直接 `cat` 会插进额外换行、破坏 Markdown 结构 |
| 截断位置 | python 侧按字节（60000），不做 `head/tail` | `head/tail` 会切坏多字节字符；60000 B → 80000 base64 字符 < 输出上限 100000 |
| 生命周期 | 只读探测，无活跃槽返回 `null`，**不新建沙箱** | 为读文件白建沙箱 = 3 秒 + 一份资源，且必读空目录 |
| 失败语义 | 一律降级 `null` / 原样提交 | 回读是锦上添花，不得影响执行结果 |

**边界不变**：回读不改变「沙箱文件 ≠ 交付物」这条口径（`S-7` 原始约束），
它是**平台侧**主动采集，而不是把沙箱文件当成交付物登记的通道。
**副作用**：交付物正文从此可能来自文件而非对话 —— 评审侧看到的是同一份文本（围栏段会从原对话拼回末尾）。

**不做**：不做「工作区文件列表 → 模型自选上交」（多一轮 LLM 且仍有判断成本）；
不回传非 `.md`（二进制无审阅价值，改由附件通道处理）。

---

## 7. 修订记录：容器寿命两段口径与空闲回收（`SL-7`，2026-11-13）

> 计划依据：`doc/plan/HelloAI 沙箱生命周期与台账证据改造计划（2026-11-11）.md` §3 SL-7。
> 本修订**不改变**「同作用域复用 + 文件跨调用保留 + 命令串行」的核心决策，只改**寿命口径**与**失败处置**。

| 原条款（§2） | 修订后（`SL-7`） | 落地状态 |
|---|---|---|
| §2-2 保活：每次执行成功即 `renew(idleTtlSeconds)`（900s） | 拆成两段：`keep-alive-seconds`（默认 28800 = 8h）是 `renew` 的**绝对**过期目标；`idle-ttl-seconds`（900s）只用于**判活**。仅在剩余寿命不足目标一半时补 `renew`（避免每条命令白付一次 RPC） | ✅ 代码 + 单测 |
| §2-4 丢弃重建：`124` / `-1` ⇒ 丢弃槽、下次重建 | `124`（命令超时）与 `-1`（SDK 未给退出码）**不再销毁容器**——两者只说明这条命令没跑完，销毁会连带丢掉整个任务已产出的文件；超时单列时间线事件 `sandbox_command_timed_out`。**传输层异常**（`commands().run()` 抛异常、状态未知）仍丢弃重建 | ✅ 代码 + 单测 |
| §2-6 并发：槽内命令串行 | 不变。新增后台回收同样逐槽 `tryLock`：拿不到锁 ⇒ 跳过（本轮不杀在跑工作区） | ✅ 代码 + 单测 |
| §4 成本：靠「turn 末释放 + 空闲 900s TTL」收敛 | turn 末释放已由 `SL-2` 改为任务终态释放；空闲收敛改为**键区分阈值**：任务级槽按 `keep-alive`（8h）回收，会话技能 / 子任务级槽按 `idle-ttl`（900s）回收。原因见下 | ✅ 代码 + 单测 |

**为什么必须拆两个量（探针实测）**：`renew(Duration)` 设置的是**绝对**过期时刻，不是增量——
2026-11-13 对本地 OpenSandbox 1.1.1 的 REST 探针证明：创建 300s 的沙箱可 `renew` 到 600s（延长），
也可把 15:24 的过期改到 15:06（**缩短**，并如期 404）。因此：
① 拿 900s 续期等于把容器寿命压在 15 分钟，长任务 / 人工审查期间工作区必丢；
② 直接把 `renew` 写到 8h 又必须配回收器，否则「同键不再访问」的槽永远不会被 `acquire` 淘汰（等于泄漏）。

**为什么任务级槽不按 900s 判活**：任务级槽有终态释放点（`SL-2`），空闲往往只是「在等人工审查 / 等下游」——
按 15 分钟淘汰会丢掉整个任务的工作区，也与已续期到 8h 的真实寿命脱节。会话技能 `scope:{id}` 与
`scope=subtask` 回滚槽没有终态释放点，只能按短空闲上限收敛。

**回收器的调度位置**：判定逻辑在 `SandboxWorkspaceRegistry#reapIdle()`（core，可单测；`SL-9` 前在实现类），
`@Scheduled` 放在 `helloai-job` 的 `SandboxIdleReaperTask`（规范 §22）。**不加 ShedLock**：
槽池是进程内资源，集群单例会致其余实例的槽永远无人回收（与 `WatchdogLeaseRenewTask` 同理）。

**回滚退路**：`keep-alive-seconds<=0` ⇒ 不主动续期，容器只活到创建时的 `spec.timeout-seconds`；
`idle-ttl-seconds<=0` ⇒ 非任务级槽不参与空闲淘汰（行为回到「只靠显式释放」）。

---

## 8. 修订记录：工作区语义与回读（`SL-3`，2026-11-13）

> 计划依据：`doc/plan/HelloAI 沙箱生命周期与台账证据改造计划（2026-11-11）.md` §3 SL-3。
> 本修订**不改变**作用域与寿命决策，只补「`/workspace` 是唯一工作区」的执行面语义，并修掉回读链路的三处错配。

| 原状 | 修订后（`SL-3`） | 落地状态 |
|---|---|---|
| 命令默认 cwd 为 `/`，`./x.py` 与 `/workspace/x.py` 不同址 | 容器创建后预建 `/workspace`（失败只 warn）；`RunCommandRequest` 统一 `.workingDirectory("/workspace")` | ✅ 代码 + 单测 |
| 回读按路径序取第一份 `.md`，会命中上游旧稿 | 按 `mtime` 取**最近修改**的一份（`find -printf '%T@ %p' \| sort -rn \| head -1`）；无 `.md` 时按体积兜底；`-maxdepth 2` 保留子目录草稿 | ✅ 代码 + 单测 |
| 槽键匹配只比 `(kind, scopeId)` ⇒ 同一任务多规格时可能读到另一个规格的容器（R5） | 回读路径先枚举同作用域全部槽，按「**同规格优先 → 唯一候选回退 → 多规格拒绝**」选；拒绝时 warn 打印候选指纹。不做严格等值：技能以 `node` 预设执行，而回读入口没有规格参数，严格等值会让技能产物读不到 | ✅ 代码 + 单测 |
| 大产物内联按**字节**判上限 ⇒ 中文 3 字节/字，2666 字就被判「过大」 | 按 UTF-8 解码后的**字符数**判（8000 字符），超限截断并标 `truncated=true`，不劈开代理对 | ✅ 代码 + 单测 |
| Prompt / 工具说明仍写「同一子任务内复用、子任务结束即销毁」 | 统一为「同一任务内复用（跨子任务文件可见）、任务终态销毁、命令串行、进程重启丢失、沙箱文件不是交付物」 | ✅ 文案 + Prompt |

**为什么不按路径序取最新草稿就一定错**：任务级复用后，同一 `/workspace` 里同时存在上游子任务与本轮的文件；
`find | sort | head -1` 命中的是字典序最小者，等于**优先回读别人的旧稿**，返工轮会把上游版本当作本轮结果。

**为什么回读要拒绝歧义而不是随便挑一个**：同任务内 python / node 两个规格槽的文件互不可见（计划 §2「同任务多规格 = 多个沙箱」），
挑错容器读到的可能是「本次根本没写过」的旧文件——静默返回错内容比回读失败更难查。



## 9. 修订记录：台账条目坐标与来源（`SL-4`，2026-11-13）

> 计划依据：`doc/plan/HelloAI 沙箱生命周期与台账证据改造计划（2026-11-11）.md` §3 SL-4。
> 本修订**不改变**作用域、寿命与工作区决策，只让台账条目可被稳定引用，并回答「这条命令是谁跑的、第几轮跑的」。

| 原状 | 修订后（`SL-4`） | 落地状态 |
|---|---|---|
| 台账条目只有「命令前缀 + 退出码 + 摘要 + 时间」，无法被引用 | 增加平台生成的 `callId`（`c<seq>`，`AtomicLong` 进程内单调递增且唯一），由 `recordCall` 写回 `ExecuteResponse` 第 4 分量 | ✅ 代码 + 单测 |
| 不记录来源，reviewer / 平台内部校验命令与作者执行混在一本账里 | 增加 `origin`（`executor` / `reviewer` / `platform`），**由调用侧显式声明**：内部工具与技能 = `executor`，`FinalReportFidelityGate` 一次性校验 = `platform`，`REVIEWER` 由 `SL-10` 的 `isReviewerScene`（`subtask_review` / `task_final_report_review`）接线 | ✅ 代码 + 单测 |
| 不记录轮次，跨轮证据无法区分 | 增加 `round`，取执行轮次 `turn = 1 + reworkCount + attemptTotal`，由 `RuntimeTurnExecutor` 在每轮起点一次绑定进 `ToolInvocationContext`，随命令透传到台账；无上下文记 0 | ✅ 代码 + 单测 |
| 每子任务台账上限 50 条，长任务里上一轮证据被静默淘汰 | 上限 50 → 200（ring buffer 语义不变），`callId`/`origin`/`round` 同步写入 timeline payload | ✅ 代码 + 单测 |

**为什么 `round` 取 `turn` 而不是字面的 `rework_count + 1`**：重试（`attemptTotal`）与返工（`reworkCount`）都会让「本轮产物」与「上轮产物」成为不同轮次；只用返工数会让重试轮的证据被当成同轮，跨轮判据失真。

**为什么 `origin` 由调用侧声明而不是从上下文反推**：平台内部一次性校验走 `execute(null, ...)`，没有 agent 上下文，反推必然退化为 `executor`——恰恰会把「平台自测」当成「作者证据」，这是比缺字段更危险的错归类。

**为什么条数上限要配合 `callId` 一起加**：`callId` 只在账本内可反查；账本淘汰了条目，引用就会判不存在。200 条覆盖「150 次调用后早轮条目仍在」的验收口径，同时受 LRU 512 子任务约束（内存封顶）。

---

## 10. 修订记录：证据引用化与台账查询（`SL-5`，2026-11-13）

> 计划依据：`doc/plan/HelloAI 沙箱生命周期与台账证据改造计划（2026-11-11）.md` §3 SL-5。
> 本修订**不改变**作用域、寿命、工作区与台账字段决策；只把「模型自报证据」从手抄命令改成引用台账条目，并让核对口径跟得上 `SL-4` 给出的坐标。

| 原状 | 修订后（`SL-5`） | 落地状态 |
|---|---|---|
| `EVIDENCE` 围栏要求模型手抄 `command`（还要自己写 `exitCode`/`truncated`/`digest`），核对靠命令前缀文本匹配 | 引用式 `{"kind":"sandbox_exec","callId":"c17"}` 为主口径；`callId` 由平台生成，模型只需抄 ID | ✅ 代码 + 单测 |
| 手抄不可避免有省略/改写，导致必然 mismatch | 旧四字段格式保留一版兼容：有 `callId` 走引用分支，没有才走旧匹配分支；`callId` 一旦填写即为唯一判据，不再回退文本匹配 | ✅ 代码 + 单测（兼容期一版，后续下线） |
| 引用条目不存在时只能模糊报「没匹配上」 | 未命中直接报 `引用 callId=… 在台账中不存在或已被其它证据占用`；同一 `callId` 被第二份证据引用即判 mismatch（一份证据只能锚定一次执行事实） | ✅ 代码 + 单测 |
| 自报 `exitCode`/`truncated` 与台账不一致时可能被放过 | 引用分支对可选字段做矛盾检测：自填值与台账不符即 mismatch，并给出双侧数值 | ✅ 代码 + 单测 |
| reviewer / 平台命令可被当作作者证据 | 命中条目 `origin` 非 `executor` 时记 `evidence_origin_non_author` 观测告警（不阻断，留痕给 reviewer） | ✅ 代码 + 单测 |
| 跨轮引用同一命令无法识别 | 命中条目 `round` 与当前轮不同则优先同轮条目，无同轮可命中时回退并记 `evidence_round_crossed` 观测告警 | ✅ 代码 + 单测 |
| 闸门只要账本里有任意一条成功记录就放行 | 存在被证实条目时，闸门只在**这些条目**里判「有无 `exitCode=0` 且未截断」；引用一条失败记录不得由账本其它成功条目背书；无引用（如模型填 `[]`）才回退全账本旧口径 | ✅ 代码 + 单测 |
| 模型不知道自己的 `callId`，只能靠回放记忆 | 新增 `internal_list_sandbox_calls`（limit 默认 20、上限 50、下限 1），返回 `callId`/`command`/`exitCode`/`truncated`/`origin`/`round`/时间；收尾轮与执行工具同时放行，**只有执行工具计预算**（N=3），查询不占额度 | ✅ 代码 + 单测 |

**为什么让查询工具不占收尾预算**：收尾轮的核心风险是模型继续「用沙箱改结果」，查询台账只读不产生产物；把它计入 N=3 会让「先查后写报告」在第三步被拒，正好挡住正确收尾路径。

**为什么兼容旧格式只留一版**：旧格式要求模型重抄命令，与 `SL-4` 的目标直接冲突；保留是为了让存量 Prompt/技能包与历史轨迹在同一版本内可解释，而不是长期双轨——下一版应移除旧分支并同步删测试，避免两套口径长期漂移。

**为什么矛盾检测只在「自报了字段」时触发**：引用式证据的最小形态只有 `callId`；模型未自报的字段不能凭空判矛盾，否则「只引用不解释」会被误杀。判据是「自报即须一致」，不是「必须自报」。

---

## 11. 修订记录：工作区句柄化与运行时接口（`SL-8`，2026-11-13）

> 计划依据：`doc/plan/HelloAI 沙箱生命周期与台账证据改造计划（2026-11-11）.md` §3 SL-8。
> 本修订**不改变**作用域、寿命、台账与证据决策；只把「调用方传一个 `Long`、沙箱层猜它是什么」改成
> 「调用方构造 `WorkspaceRef`，沙箱层只按句柄行事」，并把查库整体移出运行时。

| 原状 | 修订后（`SL-8`） | 落地状态 |
|---|---|---|
| `execute(Long subTaskId, …)` 的 `Long` 同时表示子任务 / 会话技能 scopeId / `null`（一次性），语义靠 `SandboxScopes` 查库猜 | `WorkspaceRef` 承载 `kind` / `ownerId` / `contextId` / `specFingerprint` / `isolation` / `access` / `lifetime`；运行时不再解释 ID | ✅ 代码 + 单测 |
| 非法作用域组合只能在运行时表现为「槽键对不上、悄悄多一个容器」 | 构造期校验两条不变式：`ONESHOT` ⇔ `EPHEMERAL_VERIFY` 必须同时出现；每种 `Kind` 只有一种合法 `Lifetime`（`TASK`/`SCOPE`⇒`TASK`、`SUBTASK`⇒`TURN`、`ONESHOT`⇒`CALL`） | ✅ 代码 + 单测 |
| `OpenSandboxExecutionService` 构造器持有 `SubTaskQueryPort` + `SandboxScopes`，每条命令都可能查库 | 实现类构造器只剩 `(properties, loader, ledger, timeline)`；`SandboxRuntimeAdapter` 成为 **唯一** 可查库的适配层，并作为唯一的 `SandboxExecutionService` bean | ✅ 代码 + 单测 |
| 工具层只有 `(agentId, subTaskId)`，任务级句柄只能靠查库获得 `taskId` | `ToolInvocationContext.Ids` 扩为 `(agentId, taskId, subTaskId, round)`，执行链在 `RuntimeTurnExecutor` / `BusinessSurfaceLoopRunner` 绑定 `taskId`；热路径直接构造 `TASK` 句柄，**零查库** | ✅ 代码 + 单测 |
| 回读只有一个 `readArtifact(Long, …)`；没有「存在性探测」与「写入」能力 | `SandboxRuntime` 五方法：`exec` / `readFile` / `stat`（只读探测不建沙箱）/ `writeFile`（与命令共用槽锁）/ `release`；老签名在适配层原样保留 | ✅ 代码 + 单测 |
| 「运行时不得查库」只是文档约定 | `check-arch-freeze.sh` 新增组 4：`runtime/sandbox/impl` 不得 import `SubTaskQueryPort` / `SandboxScopes`，硬拦截、目标恒为 0 | ✅ 守卫 + 基线锁定 |

**为什么句柄要多一个 `contextId`**：工作区是**任务级**共享，台账与 timeline 却是**子任务级**分账（`SL-5` 的证据引用按子任务查）。只有 `ownerId` 会让审计归属从子任务漂到任务——「谁跑的这条命令」会变成「这个任务跑过什么」，证据链对不上。故 `ownerId` 只进槽键，`contextId` 只进观测。

**为什么适配层而不是让实现类自己兼容老签名**：如果实现类同时是适配层，查库与执行仍在同一个类里，「运行时零业务查询」就退化为自觉。拆出 `SandboxRuntimeAdapter` 后，实现类连 `SubTaskQueryPort` 的类型都拿不到，架构守卫才有可执行的对象。

**为什么绑定 `taskId` 优先于查库**：绑定的值来自执行链自身（`RuntimeTurnExecutor` 已持有 `ctx.getTaskId()`），比二次查库更准；未绑定（turn 末释放 / 非执行链调用）才回落 `SandboxScopes`。`scope=subtask` 回滚模式在查库前短路，行为回到 2026-11-11 前。

**为什么 `writeFile` 不带 `spec` 参数**：当前唯一写入方是跨文档证据落盘，其容器与命令默认规格一致。计划给出的五方法里也没有该参数；需要专用规格时再扩签名，不提前挂一个无人使用的参数。

## 12. 修订记录：实现类按职责切分（`SL-9`，2026-11-13）

> 计划依据：`doc/plan/HelloAI 沙箱生命周期与台账证据改造计划（2026-11-11）.md` §3 SL-9。
> 本修订**不改变**作用域、寿命、台账与证据决策，只把 1192 行的单一实现类按动词族切开，
> 让「执行 / 生命周期 / 建销 / 记账 / 注入 / 选交付物」各自可独立测试与替换。

| 原状 | 修订后（`SL-9`） | 落地状态 |
|---|---|---|
| `OpenSandboxExecutionService` 同时承担槽池、命令执行、回读、技能注入、台账、终态回收 | `OpenSandboxRuntime`（执行：守卫 / 超时钳制 / 截断 / 一次性与复用分支）/ `SandboxWorkspaceRegistry`（句柄→槽键 / 槽池 / TTL / `renew` / `release` / `releaseByTask` / 空闲回收）/ `SandboxObservation`（台账 + timeline）/ `SkillPackageInstaller`（包注入）/ `DeliverableReader`（选交付物） | ✅ 代码 + 单测 |
| 建沙箱与销毁在实现类里，registry 与 installer 都要用它 | 抽无状态 `SandboxFactory`（建 / 销 / 规格映射），三方依赖成 DAG，不再有 `runtime → registry → runtime` 构造环 | ✅ 代码 + 单测 |
| 台账记录散落在 `execute` / 一次性路径 | `SandboxObservation` 由 runtime 持有，提供 `record(...)` / `timedOut(...)`，统一回填 `callId` 与 `origin` / `round` | ✅ 代码 + 单测 |
| 回读靠 `find -printf '%T@ %p'` 命令，平台排查命令也进台账 | `SandboxRuntime` 增第六方法 `listFiles(ref, dir, depth)`，走 SDK `Filesystem.listDirectory` 只读通道；`DeliverableReader` 按最新 `.md` 优先、体积兜底选文件 | ✅ 代码 + 单测 |
| 过渡接口 `SandboxWorkspaceOps` 暂存两条转发方法 | 方法归位后接口删除；`SandboxRuntimeAdapter` 仍保留老签名转发，成为唯一装配点 | ✅ 代码 |

**为什么 `SandboxObservation` 不是装饰器**：装饰器会包住 `exec` 入口，把「未启用 / 空命令」这类**尚未下发到沙箱**的守卫失败也写进台账。台账自 `SR-5` 起的语义是「沙箱实际调用」一一对应，证据引用（`SL-5`）也建立在这一点上；改为协作对象后，只有真正下发命令的路径记账，语义不变。

**为什么交付物选择器在 `mqconsumer` 而不是沙箱层**：「哪一份文件是交付物」是「对话正文兜底」这一场景的业务判据，不是沙箱能力。沙箱层只回答「目录里有什么」（`WorkspaceEntry` 纯元数据），策略留在消费侧才可独立单测，也不会把另一种调用场景绑在同一种取文件口味上。

**为什么 `listFiles` 取代 `find` 命令**：命令通道会把平台自身的排查动作写进台账与 timeline，制造 `origin=platform` 噪声，直接影响证据审计的可读性；`Filesystem.listDirectory` 是只读探测，不占槽锁、不入账，并且保留 `modifiedAt` 供选择器使用。

## 13. 修订记录：审核隔离（`SL-10`，2026-11-13）

> 计划依据：`doc/plan/HelloAI 沙箱生命周期与台账证据改造计划（2026-11-11）.md` §3 SL-10。
> 本修订把 `READ_ONLY` 从「平台层建议」升级为「容器层只读」：`/workspace` volume 化，
> executor 挂 `readOnly=false`、reviewer 挂 `readOnly=true`；一次性校验走 `EPHEMERAL_VERIFY`，不进槽池。

| 原状 | 修订后（`SL-10`） | 落地状态 |
|---|---|---|
| `/workspace` 是容器可写层；`READ_ONLY` 只是平台层建议，reviewer 与执行器共用同一可写容器，`internal_run_in_sandbox` 的任意 shell 都能改文件 | `/workspace` 挂 volume（本地 Host / 服务器 PVC / OSSFS）；executor 挂 `readOnly=false`、reviewer 挂 `readOnly=true`（EROFS——`touch /workspace/x` 被容器层拒），`/tmp` 保持可写 | ✅ 代码 + 单测（真机随 `SL-6`） |
| 槽键 `(kind, ownerId, specFingerprint)` 不含访问意图，reviewer 命中的仍是 executor 那个可写容器 | `SlotKey` 增 `WorkspaceRef.Access`（`READ_WRITE` / `READ_ONLY`）：executor 与 reviewer 各一个实例，`release` 用 `expectedAccess=null` 同时释放两种 access 槽 | ✅ 代码 + 单测 |
| 一次性校验走 `execute(null, …)`，未建模成独立通道 | `WorkspaceRef.oneShot()`（`ONESHOT` / `READ_ONLY` / `EPHEMERAL_VERIFY` / `CALL`）+ `executeOneShot`：创建→执行→销毁，不进槽池；卷挂载 readonly 按 `ref.readOnly()` 透传 | ✅ 代码 + 单测 |
| `/workspace` 写权限无卷配置项 | `volume-enabled`（默认 `false`，回落容器可写层零变化）+ `volume.host-path` / `pvc` / `ossfs` / `mount-path`（默认 `/workspace`） | ✅ 配置 + 代码 |

**为什么槽键纳入 `Access` 而非 `Isolation`（对计划「纳入 `Isolation`」的精确化）**：`WorkspaceRef.task()` 与 `.taskReadOnly()` 隔离形态都是 `TASK_WORKSPACE`，区别只在 `Access`。若把 `Isolation` 纳入键，executor 与 reviewer 仍共享一个实例；只有把 `Access` 与 `Isolation` 分工——`Isolation` 决定「是否进槽池」，`Access` 决定「同一规格下的读写实例」——审核隔离才成立。

**为什么 reviewer 必须能写 `/tmp`**：reviewer 落临时脚本做校验不能写 `/workspace`（目标文件），但需要写临时环境；只有 `/workspace` 只读、`/tmp` 可写才能既挡住篡改交付物、又不阻止校验动作本身。台账 `access=read_only` 只标记意图，不阻断。


---

## 14. 收口记录：`SL-6` 真机验证、`renew` 语义复核与「角色不入沙箱」边界（2026-11-13）

> 计划依据：`doc/plan/HelloAI 沙箱生命周期与台账证据改造计划（2026-11-11）.md` §3 SL-6。
> 本段只记录真机结论与由此订正的两处实现缺陷，不改变 §2 的核心决策。

| 验证项 | 真机结论（本地 OpenSandbox 1.1.1 + `python:3.12`） |
|---|---|
| 返工续作 | 同一任务沙箱 `5c917554-…` 内，人工驳回后的返工轮先 `ls -la /workspace`、`cat stage_a_receipt.json / stage_a_report.md`，再提交；上一轮产物可读 |
| 跨子任务可见 | 下游子任务在同一沙箱读到上游 `probe_result.txt`（`sha256=fb1b9c5a…`），B 的回执与 A 的产物逐字节一致 |
| 空闲保活 | 执行槽在最后一条命令后空闲 132s（`idle-ttl` 临时覆盖为 60s，`jinfo -sysprops` 确认生效），随后返工轮仍命中同一沙箱 id，文件仍在；任务级槽的回收阈值取 `keep-alive`（8h），`idle-ttl` 不参与任务级淘汰 |
| 终态销毁 | 任务 `DONE` 后 `sandbox killed` 两槽（执行槽 + 审核槽），`GET /v1/sandboxes` 归零 |
| 证据来源 | executor 证据 `origin=executor`（c7/c8），reviewer 核验 `origin=reviewer`（c9–c12）；证据闸 `gatePassed=true` 只认执行侧条目 |
| 审核隔离 | reviewer 容器 `Mounts` 为同一宿主目录 `RW=false`：`touch /workspace/x` 报 `Read-only file system`，`/tmp` 可写，执行侧同一文件 sha256 未变；executor 容器 `RW=true` |
| 回滚路径 | `scope=subtask` 覆盖后沙箱 `contextId=子任务 ID`，turn 结束即 `sandbox killed`（回到任务级之前的释放语义） |

**`renew` 语义复核**：真机 `expiresAt` 在命令后由创建时刻 + 3600s 续到 +8h（绝对时刻），与 §7 的两段口径一致；空闲回收器在任务级槽上只按 `keep-alive` 判活，`idle-ttl` 只对无终态释放点的槽生效。

**「角色不入沙箱」边界（澄清）**：沙箱不承载 Agent 角色，也不按角色建不同镜像或入口；执行者与审核者命中的是**同一任务级工作区的两个实例**，隔离只由挂载访问意图（`Access` / `readOnly`）表达。因此「审核隔离」的语义是「同一份工作区的只读视图」，不是「审核者被放进另一个沙箱世界」——跨文档核验要能读到执行侧产物，这条边界不能反过来收紧成物理隔离。

**由此订正的两处实现缺陷（只在开启卷后暴露）**：

1. **SDK `Volume` 缺 `name`**：`Volume.builder().build()` 在 1.1.1 校验 `name`（DNS label）非空，缺失即抛 `IllegalArgumentException: Name must be specified`，表现为「开启卷后所有沙箱创建失败、执行为 `BLOCKED`」。`SandboxFactory.applyWorkspaceVolume` 补 `.name("workspace")`（同一沙箱只挂一个工作区卷）。
2. **sandbox server 拒绝宿主路径**：server 侧 `storage.allowed_host_paths` 默认为空，任何 host bind mount 都返回 `VOLUME::HOST_PATH_NOT_ALLOWED`。本地验证需在 server `config.toml` 的 `[storage]` 放行工作区目录；仓库默认不放行（临时验证后已回退），部署形态改用 PVC / OSSFS 时同样需要在 server 侧登记卷源。

**回滚退路（与 §7 一致）**：`volume-enabled=false` 回落容器可写层（零变化）；`scope=subtask` 回到「子任务级 + turn 末释放」；`keep-alive-seconds<=0` 不主动续期。
