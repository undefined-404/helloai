# HelloAI 深度审核改造蓝图

> **状态：Active**（方案已定稿，未开工；各项进度见 `doc/plan/` 对应实施计划）
>
> **最后更新：2026-11-19**
>
> **编号：`MA-1` ~ `MA-9`**（Mechanism Alignment，机制对齐；`MA-9` 于 2026-11-19 追加，安全面向从「只观测」扩到「拦截 + 询问」）
>
> **上游**：`research/HelloAI 开源对标复核（2026-11-18）.md`（结论与证据）；`research/HelloAI A2A 兼容与私有 MCP 改造调研清单（2026-11-16）.md`（`AQ-1`~`AQ-13`）
>
> **下游**：`plan/` 按 `MA-` 编号逐项开实施计划；本文**不承载排期**、不写时间承诺、不使用分期标签。

---

## 0. 这份文档是什么 / 不是什么

### 0.1 它是什么

一份**按可行性排好序的改造蓝图**。回答四个问题：

1. 我们现在哪几处「看起来做完了，实际是半截」；
2. 每一处要动哪个文件、哪一行，以及为什么是那里而不是别处；
3. 开源项目里同一件事是怎么做的，我们能抄哪一半、不能抄哪一半；
4. 为什么这八项要按这个顺序，而不是按重要性或按难度。

每一节**自包含**——可以从中间任意一项开始读、任意一项单独开工，不必先读完全文。

### 0.2 它不是什么

| 不是 | 为什么 |
|---|---|
| **不是排期表** | 只排先后，不排时间。任何「第几周 / P0 / 阶段一」的写法都会过期，且违反 `doc/文档体系分类与治理规则.md` §6.5 的分期标签冻结令。**注**：§2.1 的「批次」是**依赖分组与停点**（哪几项互不相干、停在哪里不烂尾），不含任何时间承诺，与分期标签无关 |
| **不是任务拆解书** | 拆到「一次提交能做完」的粒度是 `plan/` 的事。本文写到「落点 + 方案 + 判据」为止 |
| **不是代码级设计** | 行号会漂移（`research` 已经吃过这个亏）。本文写「文件 + 方法名 + 当前行为」，行号只作参考并标注勘察日期 |
| **不是推翻现有主线** | `ADR-009` 的 A2A 双平面、`TD-11` 的验收契约标注、`G-036` 的 B 档出站，**一个都不动**。本文只做增量，交叉关系见 §6 |

### 0.3 与 research 的分工

| 层 | 文档 | 管什么 |
|---|---|---|
| 方向 | `research/` | 别人怎么做、原文哪里错、哪些值得借鉴 |
| **点位** | **本文** | **我们改什么、改在哪、为什么、什么顺序** |
| 动作 | `plan/` | 开工的那几项怎么拆、验证怎么做 |

`research` 只能给出方向——这是它的定位决定的（它回答的是「外部世界长什么样」）。要落到「哪个类的哪一行」，必须回到本仓代码重新核一遍，这正是本文第 4 节每一节的「③ 代码现状」在做的事。

---

## 1. 终局：八项做完，HelloAI 变成什么样

> **口径说明（2026-11-19）**：本节标题与下表保持八项原貌（`MA-1`~`MA-8` 是机制对齐主线）；`MA-9` 是在八项之后追加的安全强化项，`MA-10` 是其前置的执行链超时治理项。二者终局各自单独一句——`MA-9`：**「敏感凭证不再只有事后日志，而是当场由人决定放行方式」**；`MA-10`：**「任务跑不完时交出已做到的，而不是把整个子任务判失败」**。

现在的问题不是「功能不够」，而是**平台对自己做过的事没有可靠的记忆**：

- 子任务显示完成，但说不清产物在哪；
- 派单给了某个 Agent，但说不清为什么不是别人；
- 技能跑了，但说不清跑了几次、效果如何；
- 状态流转靠代码，但换一个语言/服务要重建一遍语义；
- 外部执行器只能等对方实现我们的私有协议。

八项做完之后，**每一个「为什么」都能用平台自己的数据回答**：

| 问题 | 现在 | 八项之后 |
|---|---|---|
| 这个子任务交了什么？ | 一段文本（分片被拍平，结构靠前端猜） | 后端声明格式，各端按同一份契约渲染 |
| 为什么派给他？ | 只有中选者分数 | 候选池 + 各自分数 + 中选理由 |
| 这个技能有用吗？ | 答不上来 | 技能 × 子任务 × 成败 可统计 |
| 完成 = 交付吗？ | 不等于 | 完成态要求产物证据（可豁免） |
| 模型看得见多少工具？ | 全部 | 目录 + 按需取（内部面） |
| 状态语义能被别人用吗？ | 不能，焊在 Java 里 | 一份数据契约，多语言可读 |
| 能接别人的执行器吗？ | 只能等对方实现 MCP | 多一条 ACP 路径（已验证可行） |
| 用户贴了敏感信息知道吗？ | 不知道 | 有命中记录，可控 |

一句话概括这次改造的方向：**从「状态驱动」转向「事实驱动」**——状态是平台说的话，事实是平台能证明的事。

---

## 2. 总览

| 编号 | 名称 | 一句话 | 依赖 | 改动半径 | 能否独立验证 |
|---|---|---|---|---|---|
| `MA-1` | 执行结果结构化契约 | 分片存下来了没人看，格式也没声明 | 无 | 后端 1 个读模型 + 前端 1 处切换 | 能 |
| `MA-2` | 候选集快照 | 选人的分数算完就丢了 | 无 | 1 个方法 + 1 个 key | 能 |
| `MA-3` | 技能执行台账 | 子任务跑了什么技能没记录 | 无 | 1 个方法 + 1 张表 | 能 |
| `MA-4` | 交付物登记闸 | 完成不代表有产物 | 建议 `MA-1` 先行 | 1 个写点 + 1 个开关 | 能（造样本） |
| `MA-5` | 工具面按需加载 | 每次请求都带全部工具定义 | 建议 `MA-3`（先有数据） | 1 个 resolve 点 + 1 张目录 | 需对比实验 |
| `MA-6` | 状态契约外化 | 12 态表焊在 Java 里 | `MA-7` 或 A2A 出站 | 1 个类 → 1 份 JSON | 能（对拍） |
| `MA-7` | ACP 适配器小样 | 只验证「能不能通」 | 无 | 独立脚本 | 能 |
| `MA-8` | 安全扫描只记不拦 | 完全空白 | 无 | 4 个挂点 + 1 套规则 | 能（造样本） |
| `MA-9` | 敏感动作审批（拦截 + 询问 + 恢复） | 发现敏感内容后只能记日志，不能当场问用户 | **`MA-8` 先行**（规则集即「什么该问」的判据源）；**`MA-10` 先行**（挂起豁免与收尾语义） | 1 张审批表 + 1 条状态挂起/恢复通道 + 1 个策略判定 + 前端审批卡 | 能（造样本，但恢复语义要真机走一遍） |
| `MA-10` | 执行链超时治理（次数主 + 时间兜底） | 轮次一用完就判失败，三层时间闸层级错位 | 无（`MA-9` 反过来依赖它） | 3 个配置 + 1 个出口语义 + 1 列 + 1 个扫描条件 | 能（造样本 + 对比实验） |

**可并行**：`MA-1` / `MA-2` / `MA-3` / `MA-7` 四项互不依赖，可同时开。

**依赖关系图**：

```text
MA-1 ──(建议先行)──> MA-4
MA-3 ──(提供数据)──> MA-5
MA-7 ──(验证可行性)──> MA-6
MA-2 ── 独立，随时可插
MA-8 ──(规则集 / 命中事件)──> MA-9
MA-10 ──(挂起豁免 / 收尾语义)──> MA-9
```

**为什么 `MA-10` 是 `MA-9` 的硬前置（2026-11-19 追加）**：`MA-9` 的同 turn 阻塞等待要占用执行线程，而 `ExecutionCompensationTask` 每 30 秒按 `start_time + runningTimeoutMinutes`（10 分钟）把 RUNNING 判超时 ⇒ 子任务落 `BLOCKED`。也就是说**任何超过 10 分钟的等待都必然变成一次「超时失败」，与用户点了什么无关**。要么把审批等待压到 10 分钟以内（业务上等于「没人点就作废」），要么让补偿任务识别「正在等人」并豁免——**后者正是 `MA-10` 的产出，`MA-9` 不应重复造一套**。

**为什么 `MA-10` 不进 `MA-9`**：`MA-10` 的问题在 `MA-9` 之前就存在——它现在就在让 `turnDeadlineSeconds=1800` 变成死代码（被 10 分钟看门狗遮蔽），也在让「轮次用完 = 判失败」。把这两件事塞进 `MA-9` 会让审批项的验收范围失控，且 `MA-10` 无法独立验证。

**为什么 `MA-9` 必须等 `MA-8`**：`MA-9` 要回答的不是「怎么弹卡」，而是「**哪些动作该弹**」。这个判据来自 `MA-8` 的规则集与命中事件——没有它，审批策略只能凭感觉拍档位，误报会直接变成「每做一步都要点确认」，比不弹更糟（与 `MA-8` 拒绝 fail-close 同一条判据）。`MA-8` 跑出误报率，才有资格谈「哪一档升为必问」。

**为什么 `MA-9` 不并进 `MA-8`**：`MA-8` 的定位是「只记录、随时可停、不通也不亏」的纯观测项；`MA-9` 要动的是**执行链的挂起与恢复**（子任务跑到一半停下等人回答，答完接着跑）。这两件事的失败代价差一个量级——前者最差是多一堆没用的日志，后者最差是任务永久卡死。合并会让 `MA-8` 失去「可随时插队」的性质。

**为什么 `MA-4` 建议等 `MA-1`**：闸门要判断「有没有产物」，前提是产物**能被人看见**。如果 `parts` 还没渲染出来，闸门判错的样本也没法复核——两个都是"看不见"，会分不清是闸门错还是渲染漏。不是硬依赖，是取证效率。

### 2.1 在哪停是安全的（批次划分）

**总原则：批次可以断，项不能断。**

先说一个容易被忽略的前提：这八项里**没有一项是「产品坏了必须马上修」**——它们全是机制改进（补数据、补读取方、补闸门）。用户现在用系统，不会因为 `MA-2` 没做就少一个功能。所以这八项天然可以按「有多少人力、有多少时间」分批，而不必按「不改就出事」的紧急度连做。

| 批次 | 包含 | 停在这里安全吗 | 这批的共同特点 |
|---|---|---|---|
| **第一批** | `MA-1` + `MA-2` + `MA-3` | **安全，且是干净的停点。系统状态完整、可交付** | 三项零依赖，都是「把已经存在的东西补完整」。改完立刻有可见变化 |
| **第二批** | `MA-4` + `MA-5` | 安全，但**停下来也要留观察期** | 改完**无法马上判断对错**：`MA-4` 要跑一周看误报率，`MA-5` 要对比实验看省了多少 token。工期不在写代码，在等数据 |
| **第三批** | `MA-6` | 不该现在开工 | 有触发条件（见 §4 `MA-6`）。现在做没有第二个读方 = 纯搬运 |
| **随时插队** | `MA-7` + `MA-8` | 随时停，不通也不亏 | 与主线完全无关，哪天有空就做 |
| **`MA-8` 之前** | `MA-10` | **安全停点，且是唯一的「先做收益最大」项** | 它修的是现在就在发生的错（轮次用完判失败、30 分钟闸是死代码）。改完立刻让「尽力交付」替代「轮次耗尽即失败」 |
| **`MA-8` 之后** | `MA-9` | **只有回退开关做完才算安全停点**（见 §4 `MA-9` ⑧） | 它动执行链的挂起与恢复，是十项里唯一「停在中途会让任务卡死」的项。前置 = `MA-8` 的误报数据 + `MA-10` 的挂起豁免 |

**做第一批量力评估的用途**：第一批做完就是一个可以停下来汇报、可以交给别人接手、也可以就此搁置半年而不烂尾的节点。后面的批次是「有了更好」，不是「不做就残」。

### 2.2 每项的停止条件（「做完」的判定口径）

「分批」不等于「做一半」。下表八项逐条给出「做完」的判定口径；其中 `MA-1`~`MA-4` 四项如果停在中途，产出的东西比不做更糟——因为它会变成新的「只写不读」。`MA-7` 是唯一没有半成品风险的项（验证类，结论可以是「不通」）。

| 项 | 为什么不能半途而止 | 停止条件（一句话判定） |
|---|---|---|
| `MA-1` | 后端端点上了、前端没切 = 又一个「只写不读」，正好撞上我们自己的判据 | 一次含 `text` + `data` 的提交，在详情页出现**两种不同呈现** |
| `MA-2` | 快照写进去了但没有任何地方能看 = 数据白存 | 对着一个**真实子任务**能回答「当时有几个候选、为什么选它」——日志 / 接口 / 页面任一即可 |
| `MA-3` | 建了表但永远 0 行 = 死表 | 跑完一次子任务后表里有行，且 `sub_task_id` 非空、能 join 出「这个子任务用了什么技能」 |
| `MA-4` | 开关打开但没人看日志 = 等于没做，且会变成永久开着的告警 | 观察期内**有人按约定周期**看过误报率，并给出「升级 / 维持 / 关闭」的结论 |
| `MA-5` | 只让一部分工具按需加载 = 模型能看见什么变得不确定，出问题查不出来 | 对比实验两组数据齐全（token 消耗 + 工具调用成功率**无退化**）。要么全切，要么不切 |
| `MA-6` | 一半 Java 一半 JSON = 两份事实必然分叉 | 对拍测试全绿 + **故意写错一个状态名必须启动失败** |
| `MA-7` | ——（验证类，本身没有半成品风险） | 用 ACP 驱动一次 DSH 会话，**「不通」并给出明确原因也是合格结果** |
| `MA-8` | 直接开拦 = 误报卡住正常业务，比不做更糟 | 跑出**真实的误报率数字**，哪怕数字难看 |
| `MA-9` | 弹了卡但答完接不回去 = 子任务永久卡在等待态；弹得太多 = 每步都要人点，比不弹更糟 | 造一次「敏感凭证访问」样本，用户点在选项内的决策后**同一子任务续跑到完成**；且误报率沿用 `MA-8` 的观察数据 |
| `MA-10` | 轮次耗尽改判成功但正文为空 = 把失败伪装成交付；「无进展」阈值定错 = 慢任务被误杀 | 跑满轮次预算的任务**产出可交付正文并判 `MAX_ITERATIONS` 收尾**（非 `FAILED`）；且造一个「工具卡死」样本，**无进展检测能在工具超时之前先触发** |

**总判据**：每一项都必须能回答「做完之后，我怎么知道它生效了」。回答不上来的，就是还没到能停的地方。

> 各条的具体验收步骤、样本构造方式与「不做会怎样」，见 §4 对应项的 ⑧ 节——**本文档不在两处维护同一份验收清单**。

---

## 3. 排序依据：三条判据

排序不是按「重要性」也不是按「难度」，而是按**能不能真做完**。三条判据，每条都对应一类失败模式：

### 判据一：有没有现成的「唯一入口」

**含义**：要改的行为，在代码里是不是只有一个地方能改。

**为什么重要**：有唯一入口 ⇒ 改动可穷举、可验证；没有 ⇒ 要改一片，每漏一处就是静默不一致（判据 1「规则存在 ≠ 全路径强制」）。

本仓恰好有三处干净的唯一入口，这也是它们能排在前面的一半原因：

| 能力 | 唯一入口 | 证据 |
|---|---|---|
| 子任务状态迁移 | `SubTaskStateMachineServiceImpl.changeStatus` | 类注释自述「拆分后仍只有这一条写状态路径」 |
| 技能执行 | `SkillExecutionSupport.run(subTaskId, target, command, timeoutSeconds, round)` | 工具面只有 `internal_run_skill` 一个入口 |
| 模型可见工具 | `ToolRegistryImpl.resolve(enabledTools, context)` | `AgentRuntimeContextAssembler` 注释自述「生效形态的唯一接线点是 RuntimeTurnExecutor」 |

### 判据二：能不能独立验证

**含义**：做完这一项，能不能不依赖别的项就证明它对了。

**为什么重要**：需要等别的项才能验证的改造，会在等待期里变成"不知道有没有做对"的悬案，最后要么返工要么被遗忘。

八项里唯一不能单独立证的是 `MA-6`（外化后没有第二个读取方，验不出价值）和 `MA-5`（要看 token 与成功率的对比，需要跑真实任务）。

### 判据三：做完之后有没有人读

**含义**：新增的数据，有没有消费方。

**为什么重要**：这是本仓已经踩过的坑——「无读取方的列 = 反模式」（判据 2）。`MA-1` 之所以排第一，就是因为它正是在**补一个已经踩了的坑**。

这一条也直接决定了 `MA-6` 的顺位：状态契约外化本身成本极低（56 行），但**现在做完没有第二个读方**，那就是主动制造第二份"只写不读"的事实。所以它不进第一梯队，等 A2A 出站开始设计投影、或要开「状态机只读端点」时再做——那时候做叫"统一事实源"，现在做叫"多一份要维护的东西"。

---

## 4. 逐项展开

> **每节的固定骨架**：① 说人话 ② 现在的问题 ③ 代码现状 ④ 参考实现 ⑤ 我们的做法 ⑥ 为什么这样改 ⑦ 边界与风险 ⑧ 验收判据 + 不做会怎样。
>
> 行号以 **2026-11-18** 勘察为准，跨版本会漂移；以「文件 + 方法名」为准绳。

---

### MA-1 执行结果的结构化读取方

> **方案已定稿（2026-11-18）**：走「后端组装读模型」这条路（原方案 B 的简版），`sections` 作为演进位预留、本期不实现。`context` 一并标记为**客户端不应依赖**。

#### ① 说人话

外部 Agent 已经可以分片提交结果了，数据也确实存进去了——但前端一个字符都没显示过它。更要紧的是：**后端从来没告诉过任何客户端"这段产出是什么格式"**，于是每个前端只能自己猜。

#### ② 现在的问题

`ADR-009` A 档给外部执行通道加了 `parts`（结构化分片）能力，`submitResult` 接收 `text` / `data` 两类分片并存进 JSONB。问题有四层：

**第一层：白做风险。** 按本仓判据「无读取方的列 = 反模式」，一个写进去没人读的字段，三个月后看就是"没做完"。这不是推测——`ExecutionPart.java` 的注释自己就写着「落 `sub_task.context.lastExecution.parts`（JSONB，不加表不加列）」，而全仓除了写入点，没有任何读取方。

**第二层：用户看不见结构。** 外部 Agent 提交的 `data` 分片（结构化 JSON）会被 `projectToText` 拍平成一坨文本塞进 `output`。用户在子任务详情里看到的是一段挤在一起的 JSON 字符串——**正是 2026-11-17 修过的同一类问题**（当时是 planner 产出"一坨"，加了 `StructuredTextView`）。

**第三层：格式根本没被声明，前端只能猜。** 内部 LLM 的产出**本来就是 Markdown**（`AgentRuntimeContextAssembler` 的提示词明写「写出完整交付物正文（Markdown，覆盖全部验收要点）」），但接口只下发一段 `content` 字符串。于是前端写了一整套 415 行的启发式解析器（`structuredText.ts`）去"猜这段是枚举还是步骤流水"——**这层猜测才是真正多余的那层投影**，上一轮「一坨」的根因就在这里。

**第四层：客户端已经在读平台内部状态。** `SubTaskResponseMapper.toResponse` 把整个 `sub_task.context` 原样下发给客户端（`:95`），`lastExecution` 只是其中一个键。移动端一旦照这个形状开发，今后每次往 `context` 加键/改名都是一次客户端破坏性变更。

#### ③ 代码现状

| 位置 | 行为 |
|---|---|
| `core/agent/command/ExecutionResultHandler.java` `handleReport` | **唯一写点**（内外共用），`source` = `INTERNAL` / `EXTERNAL` |
| 同上（约 :311） | `String projectedParts = ExecutionPart.projectToText(report.getParts());` —— 分片被拍平成文本 |
| 同上（约 :327） | `last.put("parts", report.getParts());` —— 原始分片原样进 JSONB |
| 同上（约 :333） | `ctx.put("lastExecution", last);` —— 落到 `sub_task.context` |
| `core/agent/command/ExecutionPart.java:19` | 注释自述落点与「不加表不加列」的口径 |
| `core/agent/command/ExecutionPart.java:116` | `projectToText` 是**唯一**投影实现（写侧单点调用） |
| `api/dto/subtask/SubTaskResponseMapper.java:95` | `response.setContext(subTask.getContext())` —— 整个内部状态原样下发 |
| `helloai-ui/src/**` | **全仓零 `lastExecution` 引用**（`grep` 无命中） |
| `helloai-ui/src/views/subtask/SubTaskDetail.vue`（约 :1074） | 只读 `p.output`，不知道 `parts` 存在 |
| `helloai-ui/src/utils/structuredText.ts`（415 行） | 从纯文本猜结构：枚举 / 步骤流水 / 括号斜杠 / 分号 / 段落 |
| `helloai-ui/src/components/StructuredTextView.vue`（265 行） | 对应渲染器，**全仓只有 `SubTaskDetail.vue` 一处使用** |

**`output` 的消费方清单（决定"能不能删掉投影"的硬约束）** `[实测]`：

| 谁读 `output` | 用途 | 能否改读 `parts` |
|---|---|---|
| `context.lastExecution.output` | 落库事实 | 否 |
| `conversation_message.content` | 前端对话流 | 只能换契约，不能只删 |
| `ExecutionOutputParser.parse` | 物化 `.md` 附件 | 否（它的职责就是把文本写成文件） |
| `parseAndAppendExecutionRecord` | 回填 Task Running Spec | 否（解析的是文本块） |
| `AgentRuntimeContextAssembler.loadUpstreamContent` | **下游子任务读上游产出** | 否（LLM 只吃文本） |
| `McpReadModelServiceImpl`（约 :160） | **外部 Agent 读上游产出** | 否（同上） |

⇒ 结论：`output` 是给机器看的正式产物，**不是前端投影，删不掉**。真正该退役的是前端那层「从纯文本猜结构」。

**已有的可复用资产**（这是本项成本低的关键）：

- `helloai-ui/src/components/MarkdownView.vue` + `src/utils/markdown.ts` —— 已有 `markdown-it` + `DOMPurify`，且 `html:false`，可直接承接 `format=markdown`
- `helloai-ui/src/components/StructuredTextView.vue` —— 2026-11-17 为"内容一坨"做的结构化渲染器，已支持：枚举、步骤流水、括号斜杠清单、表格、行内代码（本项改为**历史数据兜底**）
- `helloai-ui/src/utils/structuredText.ts` —— 对应的解析器（本项之后退为 legacy 路径）

#### ④ 参考实现

**DSH：把交付物做成一等事件** `[实测]`

`deepseek-harness-master/packages/deliverables/tool-present/src/types.ts:1-16`：

```ts
/** Durable file deliveries produced by the present tool. */
export interface PresentedFile {
  path: string
  description?: string
}

declare module '@deepseek-ai/dsh-session/types' {
  interface SessionEventMap {
    'deliverables/presented': { turn: number; callId: ToolCallId; files: PresentedFile[] }
  }
}
```

**思路**：交付物不是一个字符串，而是**带类型的事件载荷**——有 `turn`、`callId`、结构化的 `files[]`，会话层可以订阅、可以重放、可以按类型分别渲染。

**对我们的映射**：我们的 `parts` 已经是同一思路的雏形（`type` = `text` / `data`），差的只是**没有消费方把它当结构用**，而是拍平成文本。

**不能直接抄的部分**：DSH 靠会话事件总线分发（`SessionEventMap`），我们没有等价物；我们的通道是"落 JSONB → 前端拉取"。所以只能抄「带类型的载荷」这一半，抄不了「事件分发」那一半。

#### ⑤ 我们的做法（方案 B 简版，已定稿）

一句话概括：**后端不做第二次投影，只做「选择 + 打包」。**

为什么这句话重要：`output` 是写时算好并**冻结**的。如果读时再按 `parts` 重算一遍文本，投影规则一改，老数据的 `output` 与新算出来的文本就会不一致——判据 4「同一事实双写必分叉」。所以读模型只做两件事：判断**用哪份事实**、把它按**稳定形状**包起来。

**新增读模型（唯一组装点）**

`GET /api/sub-tasks/getExecutionResultById/{id}`，权限复用既有 `assertSubTaskReadableByAgent`：

> **端点命名口径（2026-11-18 勘察后订正）**：首版写的是 `GET /api/sub-tasks/{id}/execution-result`。实测 `SubTaskController` 类级为 `@RequestMapping("/api/sub-tasks")`，既有端点全部是「动词 + `ById/{id}`」（`/getById/{id}`、`/listConversationBySubTaskId/{id}`、`/listTimelineBySubTaskId/{id}`）⇒ 改为同族命名，**不引入第二套风格**。落地版本见 `plan/HelloAI MA-1 执行结果结构化契约实施计划（2026-11-18）.md` §3 `MA1-4`。

```json
{ "format": "markdown", "markdown": "<lastExecution.output 原文>", "sections": null }
```

```json
{ "format": "parts",
  "parts": [ {"type":"text","text":"…"}, {"type":"data","data":"{…}"} ],
  "sections": null }
```

装配规则固定四条，不留解释空间：

1. `format` = `lastExecution.parts` 非空 ? `parts` : `markdown` —— **不存在第三种判断**；
2. `markdown` 直接取 `lastExecution.output`，**不重新拼装、不二次截断**；
3. `parts` 直接取 `lastExecution.parts`，**保持原序**（有序数组，不按 `type` 重排）；
4. `sections` 本期恒 `null`，字段保留作演进位（见 ⑥ 最后一条）。

**一件必须顺手做的事：剥掉尾部 `## EXECUTION_RECORD` 块。**

它是给 `parseAndAppendExecutionRecord` 回填用的机器块，因为"要保留尾部"（`ExecutionResultHandler:314` 注释）一直留在 `output` 里。换成 Markdown 渲染后它会变成一个显眼的二级标题 + 几行 `SUMMARY:`，反而比现在更难看。剥离规则必须与回填侧**共用同一个起止标记常量**，否则两处会分叉。

**前端切换（三行判断）**

```text
format === 'markdown' → MarkdownView（已有，html:false + DOMPurify）
format === 'parts'    → 新 PartListView
                        ├─ type=text → 仍走 StructuredTextView（不动）
                        ├─ type=data → 折叠 JSON 块（复制 / 展开全部）
                        └─ type=file → 附件卡（本批未开放，先留渲染分支）
字段缺失（老后端 / 老数据）→ StructuredTextView 兜底，渲染逐像素不变
```

**`context` 的处理（本次一并定）**

`context` 是平台内部状态，**客户端不应依赖**。落地不是删字段（内部链与 Agent 通道还在读它），而是三步：

- DTO 注释与文档标注「内部状态，客户端不得依赖；对外读执行结果请用 `execution-result` 端点」；
- 前端现有读 `context` 的地点逐个评估迁移：`derivedTaskId` / `noCandidate` / `manualIntervention` 三项能升成显式 DTO 字段的就升，本期不升的登记为待迁；
- 移动端 / 小程序接入时只准读显式字段，**禁止读 `context`**（写进移动端接入说明）。

#### ⑥ 为什么这样改

- **为什么不再选「前端各端自己分流」**：现在只有 Web，那一版确实更便宜（约 0.5 天）。但移动端 / 小程序每加一个端，就得把「`parts` 怎么变成界面」重写一遍，`data` 的折叠、`file` 的卡片必然各写各的。契约放后端、端侧只做「kind → 组件」映射，是唯一能收敛的分工。**让它从 A 变 B 的不是成本，是「客户端数量会变成 2 个以上」这个既定计划。**
- **为什么"选择 + 打包"而不是"重新投影"**：`output` 写时冻结。读时重算 = 同一事实两个算法，规则一改老数据就漂（判据 4）。读模型只决定"用哪份"，不决定"怎么算"。
- **为什么简版 + 预留 `sections`**：Web 有现成 `markdown-it`，简版立刻见效；小程序没有 `v-html`，将来要"每端一套原生组件"时才需要后端把 Markdown 归一化成块。**预留字段，不预留代码**——现在实现 `sections` 等于给一个没有读方的字段写解析器（判据 2）。
- **为什么独立端点，不塞进 `SubTaskResponse`**：`SubTaskResponse` 被列表接口共用（分页 / `listAvailable` / `listMine` 全走 `SubTaskResponseMapper`），而 `parts` 单行上界约 1.28M 字符。塞进去 = 列表页无条件拖一份大 JSON。
- **为什么不后端直接出 HTML**：小程序没有 `v-html`，出 HTML 等于把 Web 的技术选型锁成平台契约。读模型必须下发**数据**，不是渲染结果。
- **为什么不删 `projectToText`**：见 ③ 的消费方表——`output` 有 6 个读者，其中两个是 LLM 上下文。删了等于让下游子任务和外部 Agent 立刻读不到上游成果。
- **为什么不干脆删 `parts` 只留文本**：它是 A 档对外公开的 schema，删了是破坏兼容；而且把结构化数据降级成文本、再让前端正则解析回来，就是"有 schema 不用，去猜"。

#### ⑦ 边界与风险

**七个必须提前定掉的问题点**：

| # | 问题 | 处置 | 严重度 |
|---|---|---|---|
| 1 | **内部 LLM 根本不产 `parts`** | 内部链走 `doHandleSuccess`（`ExecutionResultHandler:106`），只 `setOutput`，没有分片。所以本项**必须同时给内部产出打 `format:"markdown"`**，否则只解决了外部 Agent 那一半、截图里的"一坨"毫无变化 | **最高（最容易漏）** |
| 2 | **尾部 `EXECUTION_RECORD` 会露在页面上** | 它是给回填用的机器块，`output` 一直保留着它。Markdown 渲染后会变成正经标题，比现在更显眼 ⇒ 读模型里剥除，且与回填侧共用同一常量 | 高 |
| 3 | **历史轮次救不了** | `lastExecution` 只有最后一轮；返工多轮的旧产出在 `conversation_message` 里，是纯文本。本期不改写侧 ⇒ 翻历史轮仍是文本，需在文档里写成**已知边界**而不是缺陷 | 中（预期管理） |
| 4 | **老数据 / 老后端没有该字段** | 前端必须按「`format` 缺失 → 回落 `StructuredTextView`」实现，不能只判两种值 | 高 |
| 5 | **`data` 分片未必是合法 JSON** | 先 `try/parse`，失败按纯文本渲染，不报错、不空白 | 中 |
| 6 | **XSS** | `parts.data` 是外部 Agent 塞进来的字符串。Markdown 渲染保持 `html:false` + `DOMPurify`；`data` 只作文本节点，**禁止 `innerHTML`** | 高 |
| 7 | **体积 / 窄屏** | 端点按需取；`parts` 超阈值只返回前 N 片并置 `truncated:true`；窄屏（<480px）下 `data` 默认折叠 | 中 |

**顺带记录的边界**：

| 边界 | 说明 |
|---|---|
| 深色主题 | 上一次结构化改造的深色渐隐未实测（已知遗留），本次随 `MarkdownView` 一并验 |
| 分片顺序 | `parts` 是有序数组，渲染必须保序，不能按 `type` 分组重排 |
| `sections` 空位 | 本期恒 `null`。将来填它时**不得**改动 `format` / `markdown` / `parts` 三个已有字段的语义 |

#### ⑧ 验收判据 + 不做会怎样

**验收（每条都能单独跑，不依赖其他项）**：

1. 一次含 `text` + `data` 两个分片的提交 → `execution-result` 返回 `format:"parts"`，详情页出现**两种不同呈现**（文本流 + 可展开 JSON）；
2. 一次内部 LLM 子任务 → 返回 `format:"markdown"`，详情页按 Markdown 渲染（标题 / 列表 / 代码块 / 表格正常），**不再依赖启发式猜测**；
3. 一次无 `parts` 的老数据 → 返回 `format:"markdown"`，渲染与改造前**逐像素一致**；
4. 详情页任何位置**看不到** `## EXECUTION_RECORD` 文本；
5. `data` 为非法 JSON → 降级纯文本，不报错、不空白；
6. 列表接口响应体大小与改造前一致（新读模型不出现在列表路径上）；
7. 深色主题 + 窄屏（<480px）下两种呈现均可读。

**不做会怎样**：A 档对外承诺"支持结构化分片"，但用户在界面上看不出任何区别 ⇒ 能力等于不存在，半年后无人能说清 `parts` 是干什么用的。更贵的代价在后面：移动端会把 `context` 当契约开发，届时一次 `context` 改动就是一次客户端破坏性变更。

---

### MA-2 候选集快照

#### ① 说人话

平台能说清"这单派给了谁、他得了多少分"，但说不清"当时还有谁、各得多少分、为什么不是他"。

#### ② 现在的问题

派单是平台最核心的决策之一，但**决策过程不可复盘**：

- 出事时（"这单为什么给了他"）只能翻日志猜；
- 想做「选人质量」评估（派出去的人后来通过率如何）没有对照组；
- 候选池里被淘汰的那些，分数算完就丢了。

按 `research` §4.3 的结论，`DC-1`（决策记录）**已经有一半**：`sub_task` 表里有 `assigned_agent_id` / `composite_score` / `score_grade` / `score_factors`（五维因子）。缺的只有「被淘汰候选的分数」。

#### ③ 代码现状

**关键发现：改造比设想的便宜得多。** 排序用的中间量**全部已经算好了**，只是没保存。

| 位置 | 行为 |
|---|---|
| `core/agent/executor/AgentSelector.java` `pickFromCandidates`（约 :173） | 方法内**已持有**：`candidates`（原始候选）、`qualityRanks`、`costRanks`、`inFlight`（在飞数）、`pool`（过滤后）、`tierComparator`（分档比较器）、`tierBest`。方法结束时 `return` 一个 `Agent`，其余全部丢弃 |
| `core/task/service/impl/SubTaskDispatchServiceImpl.java` `pickApiKeyLlmAgent`（约 :562） | 回退路径同样：`filter` 一串 → `max(comparing(score))`，只返回一个人 |
| `core/task/entity/SubTask.java` | 已有 `assignedAgentId` / `compositeScore` / `scoreGrade` / `scoreFactors` |

**注意两处选人的语义不同**（不能只改一处）：主链路是「分档 + 同档内多维比较」，回退路径是「单维分数取最高」。快照要能区分这两种口径，否则以后看数据会误判。

#### ④ 参考实现

**AgentTeams：把决策类别抽成数据** `[实测]`

`AgentTeams-main/plugins/teamharness/contracts/decision-classes.json` —— 整份契约的 `status` 是 `defined-not-enforced`，自述 *Data only - no runtime code references this file*。

**这个例子要正反两面看**：

- **正面**：它把「决策有哪几类」写成了机器可读的数据，读者一眼能看出「这类决策目前没有强制」；
- **反面**：正因为没有代码引用，它自己是"只写不读"的典型——**这是我们不要学的部分**。

**更值得抄的是它的幂等规则** `[实测]`，见 `contracts/signal-mapping.json`：

```text
No raw timestamp is ever part of the identity
event_id = 'ev_' + sha256(team_id|project_id|...|audit_seq|attempt)[:16]
```

**思路**：决策记录要能被**重放**（同一决策重试落回同一身份）和**去重**。用持久化的序号（`audit_seq`）参与身份，不用时间戳——因为同一秒内的多次操作在秒级精度下状态字节完全相同，用时间戳会撞号。

**对我们的映射**：我们的快照不是事件，不需要内容哈希；但要继承「**决策记录必须能区分"同一决策的重试"与"新一次决策"**」这一点——所以快照要带 `attempt` 序号，而不是每次覆盖。

**DSH 的对应物**：`packages/session` 的事件流可 replay，思路同样是"决策落事件、事后可重建"。但它依赖一套会话事件总线，我们没有。

#### ⑤ 我们的做法

**核心原则：只加"记录"，不动"决策"。**

```text
AgentSelector.pickFromCandidates(...)
  在 return 之前：
    1. 把 pool 里每个候选投影成
       { agentId, name, role, score, qualityRank, costRank, inFlight, tier }
    2. 按最终比较器的顺序排序，截断到 top N（建议 10）+ 记录候选总数
    3. 标记中选者（winner=true）
    4. 序列化 → 随派单结果一起返回上层

落点：sub_task.context.dispatchSnapshot（JSONB，不加表不加列）
      key 常量登记进 common/constant/SubTaskContextKeys.java（避免字面量散落）
```

**回退路径** `pickApiKeyLlmAgent` 同样处理，但快照里要标 `path: "fallback"`，与主链路的 `path: "primary"` 区分。

**快照形状**（建议）：

```json
{
  "attempt": 1,
  "path": "primary",
  "decidedAt": "2026-11-18T13:00:00+08:00",
  "candidateTotal": 7,
  "candidates": [
    {"agentId": 101, "name": "executor-a", "role": "EXECUTOR",
     "score": 88, "qualityRank": 1, "costRank": 2, "inFlight": 0, "tier": 1, "winner": true},
    {"agentId": 102, "name": "executor-b", "role": "EXECUTOR",
     "score": 85, "qualityRank": 2, "costRank": 1, "inFlight": 1, "tier": 1, "winner": false}
  ],
  "truncated": false
}
```

#### ⑥ 为什么这样改

- **为什么不用新表**：一次派单一份快照，查询模式是"按子任务取最新一条"——这正是 JSONB 上下文的形状。`lastExecution` 已经证明这个模式可行（不加表不加列）。真要统计时再从 JSONB 抽到报表，那时才建表。
- **为什么不重新算一遍分数**：重算会出现"快照里的分"与"当时真正用的分"不一致（比如权重配置改过）。**快照的价值就是记录当时的真实值**。
- **为什么不动选人逻辑**：一旦在选人里加新判据，就说不清"行为变化是快照引起的还是新判据引起的"。这是判据 18「对照实验的前提 = 两组只差被研究的那一个变量」的直接应用。
- **为什么带 `attempt`**：无候选时会反复重派（`dispatchBlockedSubTaskAuto`），如果每次覆盖，就永远只能看到最后一次——而"为什么第一次没派出去"往往才是要查的。

#### ⑦ 边界与风险

| 风险 | 处置 |
|---|---|
| 候选池可能很大（上百 Agent） | 截断 top N（建议 10）+ `candidateTotal` 记真实总数 + `truncated` 标记 |
| 快照里混入敏感配置 | **只存投影字段**（id / name / role / 分数 / 排名 / 在飞数 / 档位），不存 model、key、prompt |
| JSONB 膨胀 | 覆盖式写入（按 `attempt` 递增，不无限追加）；只保留最近 N 次 |
| 派单性能 | 序列化开销在一次派单里可忽略；`pool` 已经在内存里，不额外查库 |
| 两处选人口径不同 | 快照里必须带 `path`，否则后续统计会把两条路径混算 |

#### ⑧ 验收判据 + 不做会怎样

**验收**：

1. 任意一次派单后，`sub_task.context.dispatchSnapshot` 能查到：候选总数、top N 候选及各自分数与排名、中选者、走的是哪条路径；
2. **派单结果不变**：同一输入（同候选池、同约束）改造前后选出的人一致；
3. 无候选（`NoCandidateAgentException`）时不写快照或写空快照，不报错；
4. 重派一次，`attempt` 递增且旧快照可追溯。

**不做会怎样**：`DC-1` 的缺口长期存在，"为什么选他"永远答不上来；后续任何"选人质量"评估都没有对照组。

---

### MA-3 技能执行补子任务维度台账

#### ① 说人话

子任务里跑了哪些技能、跑了几次、成功没成功——现在查不到，只能翻对话正文猜。

#### ② 现在的问题

`research` 的 `SK-1` 想算「技能 × 子任务一次通过率」，前置是**有可 join 的键**。原判断"把 `chat_skill_run` 关联到 `sub_task_id` 就行"——**二次核验后不成立**：

- `chat_skill_run` 属 **planner 澄清域**（`planner/entity/ChatSkillRun.java`，注释自述「对话内技能执行运行记录」），只有 `conversationId`，没有 `subTaskId`；
- `agent` 域对它**零引用**——两条链压根没打通，不是"加个关联"的事；
- 而子任务侧的技能执行，唯一入口 `SkillExecutionSupport.run` **入参里已经带着 `subTaskId`、技能名、版本、轮次**。

所以真实缺口是：**子任务维度压根没有技能执行台账**。

#### ③ 代码现状

| 位置 | 行为 |
|---|---|
| `core/agent/runtime/sandbox/impl/SkillExecutionSupport.java` `run(subTaskId, target, command, timeoutSeconds, round)` | **唯一入口**。`target` 里有 `name` / `version`；内部 `sandboxExecutionService.execute(subTaskId, target.spec(), wrapped, timeout, EXECUTOR, round)` |
| `core/planner/entity/ChatSkillRun.java` | 字段：`conversationId` / `userId` / `skillName` / `triggerSource` / `status` / `phase` / `attachmentId` / `resultPayload` / `timeoutSeconds` / `startedAt` / `finishedAt`。**无 `subTaskId`** |
| `core/agent/runtime/tool/SandboxCallLedger`（`SL-5`） | **进程内内存台账**，重启即清空；只记 `callId` / `command` 前缀 / `exitCode` / `origin` / `round` / `at`。**无 `skillName`**，且不能作为跨会话统计源 |
| `InternalLlmExecutorTools.java:663` `internal_run_skill` | 唯一的技能执行工具；`SkillExecutionToolContributor` 决定它何时对模型可见 |

**为什么内存台账不够**：`internal_list_sandbox_calls` 的设计目的只是"当前子任务内引用证据"，重启即清、且分不清"这条命令是技能还是随手跑的命令"。做统计必须落库。

#### ④ 参考实现

**AgentTeams：状态变化落 history 表** `[实测]`

`plugins/agentteams-teamharness/mcp/lifecycle/transitions.py:197-249` —— 每次转移（含被拒绝的）都落 `decision_status_history`，拒绝本身也留 `refused` 条目。**思路**：不是"成功才记"，而是"**尝试就记**"，否则只看到成功的那一半。

**DSH：埋点是独立包，不侵入业务** `[实测]`

`deepseek-harness-master/packages/telemetry/` —— 独立成包，业务代码只发信号，采集侧独立演进。

**思路**：埋点的读写边界要独立，**埋点失败不能影响主链**。这在本仓有直接对应判据——`SubmissionActivityRecorder` 就是为了"活动日志写失败不能把 PG 事务标记 aborted"而独立事务化（见 `McpAgentReportServiceImpl` 的类注释）。

**对我们的映射**：两层都要抄——① 尝试就记（失败也算一次）；② 写入 best-effort（独立事务或 try-catch + warn）。

#### ⑤ 我们的做法

**第一步：落一张新表 `sub_task_skill_run`**

```sql
sub_task_skill_run
  id              bigint pk
  sub_task_id     bigint not null   -- join 键，索引
  task_id         bigint            -- 便于按任务聚合，避免 join sub_task
  agent_id        bigint            -- 谁跑的
  skill_name      varchar(128) not null
  skill_version   varchar(64)
  round           int               -- 对应 SL-4 的轮次
  command_prefix  varchar(200)      -- 只记前缀，防密钥进库（与 SandboxCallLedger 同口径）
  exit_code       int               -- 124 = 超时
  truncated       boolean
  duration_ms     int
  created_at      timestamptz
```

**第二步：在唯一入口埋点**

```text
SkillExecutionSupport.run(...)
  ├─ 开始：记 startedAt（内存变量）
  ├─ 执行：sandboxExecutionService.execute(...)（现有逻辑，一行不改）
  └─ finally：写一行台账（成功/失败/超时都写）
       try { repo.insert(...) } catch (Exception e) { log.warn(...) }  ← 绝不影响主链
```

**为什么不复用 `chat_skill_run`**：

- **域归属不对**：它在 planner（澄清域），语义是"用户在对话里点了个技能"。往它上面加 `sub_task_id` = 一张表两种语义，正是判据「一列两义」的反模式；
- **成本差别很小**：新表就是一条迁移 + 一个 entity/mapper，比改一张有 5 个读方的表更安全；
- **查询模式不同**：对话技能按 `runId` 单条轮询；子任务技能按 `sub_task_id` 批量聚合。

#### ⑥ 为什么这样改

- **为什么在 `SkillExecutionSupport.run` 埋而不是在工具层埋**：工具层（`internalRunSkill`）只是入口之一，`SkillExecutionSupport` 才是真正执行的地方——判据 1「穷举所有入口」，埋在最里层才不漏。
- **为什么记 `command_prefix` 而不记完整命令**：完整命令可能含密钥/令牌。`SandboxCallLedger` 已经确立"最多 200 字符前缀"的口径，沿用即可。
- **为什么 `task_id` 冗余一列**：报表要按任务聚合，`sub_task_id → task_id` 的 join 在大表上是多余的。冗余一列换查询简单，这是常见的反范式取舍，代价要写进注释（避免"为什么有两处 task_id"的困惑）。
- **为什么失败也记**：只记成功会让"失败率"永远算不出来，而失败率恰恰是技能能不能用的核心指标。

#### ⑦ 边界与风险

| 风险 | 处置 |
|---|---|
| 表会增长 | 一次子任务可能跑几十次技能。按 `created_at` 做保留期（与日志策略同口径），或按 `task_id` 级联清理 |
| 埋点拖慢执行 | 单次 insert 在毫秒级；且放在 `finally`、独立事务，不占主链事务 |
| 沙箱未启用 | `internal_run_skill` 本身不可见（`SkillExecutionToolContributor` 摘除），表为空是正常的，不要当异常 |
| 历史数据缺失 | 上线前的子任务没有台账，报表要能区分"没跑过"与"没记录"（用上线时间切） |
| 迁移批次 | 按 Flyway 铁律：同批次只占一个版本号，**功能与回归全确认后**才固化成生产级迁移 |

#### ⑧ 验收判据 + 不做会怎样

**验收**：

1. 跑一个用 `internal_run_skill` 的子任务 → 表里出现对应行，`sub_task_id` / `skill_name` / `exit_code` 正确；
2. 造一次技能失败（命令返回非 0）→ 也落一行，`exit_code` 反映真实值；
3. 造一次台账写入异常（临时改表名）→ **技能执行结果不受影响**，只有 warn 日志；
4. 一次子任务跑 3 次同一技能 → 3 行，`round` / `created_at` 可区分。

**不做会怎样**：`SK-1`（技能效果报表）永远做不了；"这个技能到底提升了多少成功率"无法回答；技能包该不该保留/淘汰只能靠感觉。

---

### MA-4 交付物登记闸

#### ① 说人话

子任务显示"已完成"，只代表状态迁移成功，**不代表真的有东西交出来**。

#### ② 现在的问题

按本仓现役链路：`submitResult` → `ExecutionResultHandler.handleReport` → 状态迁到 `REVIEW` / `DONE`。**全链路没有任何一步验证"产物到底存不存在"**。

AgentTeams 那句注释把问题说得很直白 `[实测]`：

> *a completed task counts only if a real artifact was registered. … The status alone is not evidence of delivery — that is exactly the fake-completion loophole this gate removes.*

**这条正中我们的软肋**：`submitResult` 允许 `output` 为空仍算成功（有"空回复归因"逻辑），交付物存在性校验在别处、且**不是完成态的前置**。后果是「假完成」无法识别，而所有基于 `DONE` 的统计（通过率、技能效果、Agent 评分）全都建立在不可信的基础上。

#### ③ 代码现状

| 位置 | 行为 |
|---|---|
| `core/agent/service/impl/McpToolServiceImpl.java` `submitResult` | 入口校验：`assertAgentActive` / `assertToolEnabled` / `refreshDutyLease` / `validateParts`。**只校验分片格式，不校验产物有无** |
| `core/agent/service/impl/McpAgentReportServiceImpl.java` `submitResult` | 构造 `ExecutionResultReport`，`source="EXTERNAL"`，调 `handleReport` |
| `core/agent/command/ExecutionResultHandler.java` `handleReport` | **唯一写点**，`source` 区分 `INTERNAL` / `EXTERNAL` |
| `core/task/entity/Attachment.java` | 已有 `subTaskId` / `objectKey` / `storageUrl` / `status` / `uploaderAgentId` / `fileSize` **⇒ 判据数据源现成，不用加表** |
| `core/agent/service/ExecutionArtifactService.java` | 内部链产物物化（`lastExecution.output` → 附件），说明内部链本就有产物 |
| `core/agent/runtime/loop/impl/FinalReportFenceHook.java` | 已有"围栏"概念，但管的是报告正文，不是产物存在性 |

#### ④ 参考实现

**三种范式，各有取舍**（详见 `research` §5.3.1，本次二次核验补入）：

| 范式 | 代表 | 做法 | 能不能造假 | 代价 |
|---|---|---|---|---|
| **登记式** | AgentTeams `mcp/project/flow.py:191-213` `_completion_artifact_registered` | 查 `result.md` 是否存在，或任务记录带已校验且非空的 `deliverables` | 较难（要真造出文件） | 中 |
| **声明式** | DSH `packages/deliverables/tool-present/src/types.ts` | 模型主动调工具 present 文件 → 发 `deliverables/presented` 事件 | **能**（模型说交了但文件不存在） | 低 |
| **事实式** | DSH `packages/deliverables/workspace-changes/src/recorder.ts` | turn 开始打 git 树快照，turn 结束 diff，记录实际变动 | **很难**（不依赖模型自述） | 高（要快照 + diff） |

**AgentTeams 的关键实现细节** `[实测]`（`flow.py:191-213`）：

```python
# 判据两条，满足任一：
#   ① 任务产物文件真实存在（默认 result.md，或 meta 里指定的 result_path）
#   2  任务记录带「已校验」且「非空」的 deliverables
# 注意 result_path 存在但文件不存在时，直接 return False（不继续查 deliverables）
```

**对我们的映射**：我们已有的 `parts` **本质是声明式**（模型自报分片）。如果只配一个"查文件在不在"，仍然依赖模型的诚实——声明式和登记式都是"看谁在说"，只有事实式是"看实际发生了什么"。

我们的"事实"侧有现成来源：**附件表**（`Attachment.subTaskId` + `objectKey` 有值 = 真的落库了）。这比 git diff 轻得多，因为我们本来就有产物落库这一步。

#### ⑤ 我们的做法：三段递进，先单侧、先告警

**不一次到位**。理由见 ⑥。

```text
第一段（本次）：只观察，不改行为
  在 handleReport 里，对 source=EXTERNAL 且 success=true 的提交：
    判据（满足任一即算"有产物"）：
      ① parts 非空（声明式：模型自报了分片）
      ② 该 subTask 存在有效 Attachment（事实式：真落库了）
      ③ output 长度 > 阈值（兜底：纯文本交付）
    不通过 → 只 log.warn + 落一条时间线事件 delivery_gate_miss（不阻断、不改状态）

第二段（观察期后）：可见但不拦
    不通过 → 写进 sub_task.context.gateFindings（与 DraftGateChecker 的结论同一存放惯例）
    → 子任务详情页可见，人工能看到"这次完成没有产物证据"

第三段（误报率可接受后）：升为拒收
    不通过 → accepted=false, reason=no_deliverable
    受 delivery.enforce 开关控制，默认 OFF
```

> **落地标注（2026-11-19，`MA-4` 第一段）**：第一段已实现——判据组件 `DeliveryEvidenceEvaluator`（`parts` 非空 / 活跃 `Attachment`（复用 `AttachmentPort.listActive`）/ `output` 长度 > `helloai.delivery.min-output-length`，三取一 + 豁免 `noDeliverable` 或 `acceptanceKindDeclared` ∈ {`REVIEWED`,`ATTESTED`}）；`handleReport` 仅 `source=EXTERNAL && success=true` 评估，不通过 → `log.warn` + `delivery_gate_miss` 时间线事件，不改状态。观察期手段 `scripts/sql/observe-delivery-gate.sql`，跑满一周出三选一结论后本项才可标「已完成」。

**三条硬约束**：

1. **只判 `EXTERNAL`，内部链零改动**。内部链的产物由 `ExecutionArtifactService` 物化，本来可靠；外部链是黑盒，才是风险点；
2. **必须有豁免通道**。纯分析类子任务（结论就是交付物、没有文件）要能显式声明"无产物"，否则闸门会误伤；
3. **开关默认 OFF**，上线先观察。

#### ⑥ 为什么这样改

- **为什么单侧**：`handleReport` 是内外共用写点，在这里加硬闸会连内部链一起卡。而内部链的产物路径与外部完全不同（一个平台物化、一个靠上传），统一判据必然有一侧误判。
- **为什么先 warn-only**：判据「约束强弱看 fail-open / fail-close」——但**在能定义"什么算产物"之前，fail-close 一定会误伤**。纯分析类任务、只改配置的任务、只回一句话的任务，都没有文件产物。先用 warn 拿到真实分布，才知道阈值定在哪。
- **为什么"有产物"要三个判据取或**：只查附件会把"纯文本交付"全判成假完成；只查 `parts` 会漏掉"老协议 Agent 不上传分片"；只查 `output` 长度会被"复述一遍提示词"骗过。**三取或是当前信息量下的最大覆盖**。
- **为什么放进 `gateFindings` 而不是新表**：`DraftGateChecker` 已经确立了"闸门结论写 `context.gateFindings`"的惯例，前端也已有读取能力。复用同一存放点，避免第二套。

#### ⑦ 边界与风险

| 风险 | 处置 |
|---|---|
| **纯分析类任务被误判** | 豁免通道：`context.acceptanceKind` 已声明为分析类，或显式 `noDeliverable: true` |
| **历史外部 Agent 集体失败**（第三段） | 开关默认 OFF；升级前先通知 + 给两个现役 Agent 留过渡期 |
| **`Attachment` 表有悬空记录**（库里有一行但桶里没对象） | 第一段只查行存在，第三段才查 `objectKey` 非空；**不要**在这一项里做对象存储可达性探测（那是另一个问题，且会让闸门依赖 MinIO 可用性） |
| **阈值怎么定** | 不拍脑袋：第一段跑一周，把 `output` 长度分布打出来再定 |
| **内部链行为** | 零改动。任何"顺手把内部链也管上"的想法都要拒绝——那会让这一项的验证无法隔离 |

#### ⑧ 验收判据 + 不做会怎样

**验收**：

1. 造一次"成功但零产物"的外部提交 → 第一段：`DONE` 照常，日志/事件有 `delivery_gate_miss`；第三段（开关打开）：被拒，`reason=no_deliverable`；
2. 造一次"有附件"的外部提交 → 三段行为完全一致（都通过）；
3. 造一次"有 `parts` 无附件"的外部提交 → 三段都通过（声明式算数）；
4. **内部链跑一个正常子任务 → 行为与改造前完全一致**（这条最容易漏测）；
5. 一条纯分析类子任务（带豁免声明）→ 不被拦。

**不做会怎样**：`DONE` 永远只是"状态迁移成功"，不是"东西交出来了"。而 `MA-3`、`MA-2`、技能效果统计全部建立在 `DONE` 上——**底下那层不牢，上面全废**。

---

### MA-5 工具面按需加载

#### ① 说人话

模型每次请求都背着全部工具的定义，工具越多，背得越重、选错越多。

#### ② 现在的问题

内部执行链有 16 个进程内工具，对外 MCP 有 17 个。**每次请求都把完整定义塞进提示词**，带来三个后果：

1. **固定开销随工具数线性增长**——加一个工具，每次请求都多一点；
2. **选择困难**——工具越多，模型选错的概率越高（尤其是有相似工具时，如 `internal_run_in_skill` vs `internal_run_in_sandbox`）；
3. **维护成本**——工具清单散落在多处（`@Tool` 注解、`DEFAULT_EXECUTOR_TOOLS`、`INTERNAL_LLM_TOOLS`、contributor），加一个工具要改好几处，漏一处就是"注册了但不可用"。

**而我们的技能侧已经是按需加载了**：`internal_list_skills` 只给名字 + 简介（封顶），`internal_load_skill` 才取正文。`InternalLlmExecutorTools.java:473` 的注释写得很清楚：

> 「REF-8.3 全量目录注入，对齐 DSH `tool-skill` 全量目录 + 惰性加载」

所以这一项不是"引入新范式"，而是**把已经在技能上验证过的范式平移到工具面**。

#### ③ 代码现状

| 位置 | 行为 |
|---|---|
| `core/agent/service/AgentRuntimeContextAssembler.java`（约 :152） | `List<String> enabledTools = mergeTools(tools, resolved.requiredTools())` —— 这是**请求集** |
| 同上（类注释） | 自述「此处**刻意不做** `ToolRegistry.resolve`——生效形态（条件可用摘除 + 动态描述）的唯一接线点是 `RuntimeTurnExecutor`」 |
| `core/agent/runtime/RuntimeTurnExecutor.java`（约 :122） | `List<ToolDefinition> resolvedTools = toolRegistry.resolve(enabledTools, toolContext)` —— 这才是**生效集**（模型可见） |
| `core/agent/tool/ToolRegistry.java` + `impl/ToolRegistryImpl.java` | `List<ToolDefinition> resolve(List<String> enabledToolNames, ToolContext context)`，唯一实现 |
| `core/agent/runtime/tool/InternalLlmExecutorTools.java` | 16 个 `@Tool`（`internal_` 前缀） |
| `core/agent/mcp/McpToolConfig.java` | 对外面 `mcpToolCallbacks()`（echo + `McpMcpServer` + contributor）／内部面 `internalToolCallbacks()`，两 Bean 分离 |
| `core/agent/service/impl/AgentMcpServerServiceImpl.java`（约 :140-172） | `DEFAULT_EXECUTOR_TOOLS` / `INTERNAL_LLM_TOOLS` 白名单常量 |

> **口径订正提醒**：`research` 首版把注入点写成 `AgentRuntimeContextAssembler:206`，**写错层级**——那里只 set 了请求集，真正的生效点是 `RuntimeTurnExecutor:122`。改造这一项必须动后者。

#### ④ 参考实现

**DSH：工具加载策略 + 目录摘要封顶** `[实测]`

`deepseek-harness-master/packages/mcp/mcp-client/src/config.ts:67`：

```ts
toolLoading: z.union(['eager', 'on-demand']).default('eager'),
catalogDescriptionMaxLength: z.natural().min(4).default(DEFAULT_CATALOG_DESCRIPTION_MAX_LENGTH),
```

`:23`：`const DEFAULT_CATALOG_DESCRIPTION_MAX_LENGTH = 160`
`connection.ts:142`：按策略选择 loader

**`on-demand` 的含义**（这是关键，别理解成"延迟加载代码"）：**不把服务端全部工具定义塞进提示词，只给一张带摘要的目录，由模型按需加载**。

**要注意的两个细节**：

1. **默认是 `eager`**，不是 `on-demand`。说明 on-demand 有代价（多一次工具调用往返），不是无脑更优；
2. **目录摘要封顶 160 字符**——这是防止"目录本身变成新的负担"。

**对我们的映射**：我们已经有目录工具（`internal_list_skills`）、有封顶惯例（技能 description 封顶）。缺的是**工具的目录**。

#### ⑤ 我们的做法：分两步，先内部面

**第一步（低风险，先做）：给工具面加"目录 + 按需取"**

```text
现状：resolve(enabledTools) → 全部工具完整定义
改为：
  ├─ 目录（始终注入）：每个工具只给 name + 一句话摘要（封顶 160 字符）
  ├─ 一个新增工具 internal_load_tool(name)：返回完整 schema / description
  └─ 高频核心工具（3~5 个）仍给全定义（避免每次都要加载）

只动内部面（16 个）；对外 17 个保持全量
```

**第二步（可选，看第一步效果）：按 `ToolContext` 预筛**

按角色 / 任务类型 / 执行阶段，只把"可能用到"的工具放进目录。这一步风险高（漏了就"模型不知道有这个工具"），**除非第一步的实测数据支持，否则不做**。

#### ⑥ 为什么这样改

- **为什么先内部面**：对外 17 个是**签约面**——`research` 和 `tools/list` 的 schema 已经对外公开，2026-11-17 刚验过 `verify-mcp-external-surface.sh`。改了就是破坏兼容，而收益一样能在内部面拿到。
- **为什么保留 3~5 个高频工具给全定义**：如果连最常用的工具都要先加载一次，模型在简单任务上会多花一轮。这个"白名单"要根据真实调用频次定，不是拍脑袋（`MA-3` 台账不覆盖工具调用，所以这一步要看现有的 `agent_event` 工具调用统计）。
- **为什么目录必须全量**：按需加载的前提是"模型知道有哪些工具可选"。目录漏一个，那个工具就等于不存在——这比"定义太长"严重得多。
- **为什么不是一次全切 on-demand**：DSH 自己默认 `eager`。要先用对比实验证明净收益（token 降了、成功率没降），再决定默认值。

#### ⑦ 边界与风险

| 风险 | 处置 |
|---|---|
| **模型不知道某工具存在** | 目录必须全量（只省"定义"不省"名字"）；目录条目封顶 160 字符 |
| **多一轮工具调用** | 高频工具给全定义；用 `MA-` 的对比实验量化净收益 |
| **对外兼容** | 不动 `McpMcpServer` 与 `mcpToolCallbacks()`；`verify-mcp-external-surface.sh` 必须保持绿 |
| **加载工具本身占位** | `internal_load_tool` 也是工具，算进 16 个里；净收益要扣掉它 |
| **缓存** | 同一 turn 内重复加载应命中缓存，否则模型会反复取同一个 |
| **条件可用性** | `ToolRegistry.resolve` 已经会摘除条件不可用的工具（沙箱未启用等），目录要基于**resolve 之后**的集合，不能基于全量注册表 |

#### ⑧ 验收判据 + 不做会怎样

**验收**：

1. **对比实验**（判据 18：两组只差一个变量）：同一批任务（建议 10 个以上），改造前后的①单次请求提示词 token 数 ②工具调用成功率 ③平均轮次；
2. 目录里出现的工具数 = `resolve` 之后的工具数（不漏不重）；
3. 模型能通过 `internal_load_tool` 拿到完整定义并成功调用（至少覆盖 3 个工具）；
4. 对外 `tools/list` 输出**逐字符不变**（用 `verify-mcp-external-surface.sh` 验）。

**不做会怎样**：每加一个工具，所有请求都多一份固定开销；工具越多模型越容易选错；工具清单继续散落多处，"注册了但不可用"这类问题会反复出现。

> **落地标注（2026-11-19，`MA-5` 第一步「目录 + 按需取」）**：第一步已实现——目录装配 `ToolCatalogAssembler`（生效集逐条出目录，`name` + 摘要，默认 160 码点封顶 `helloai.tool-catalog.description-max-length`、码点安全 + ASCII 词边界回退，白名单 `internal_run_in_sandbox` / `internal_get_sub_task_detail` / `internal_load_skill` / `internal_get_deps_summary` 保留全文）；`internal_load_tool(name)` 严格匹配 + turn 内缓存（`ToolInvocationContext.TOOL_CACHE`，生命周期同 `CALLED`）；接线点 = `RuntimeTurnExecutor.resolveVisibleCallbacks`（生效集，`TOOL_RESOLVED` 的 `tools`/`effectiveTools`/`removedTools` 语义零变化、`resolvedTools` 仍记原始描述）；`INTERNAL_LLM_TOOLS` 已登记；`ObjectProvider<ToolRegistry>` 破 Spring 循环依赖。⑧ 验收逐项：② 目录不漏不重 → `ToolCatalogAssemblerTest` 10 例锁死；③ `internal_load_tool` 完整定义 + 缓存命中 → `RuntimeTurnExecutorToolCatalogTest` 6 例 + `InternalLlmExecutorToolsTest` 4 例；④ `tools/list` 逐字符不变 → `verify-mcp-external-surface.ps1` H1/H2/H3 全 OK。**① 对比实验已跑出两组数据（2026-11-19 14:19~14:33 重启后真实链路）**：基线（11-15~17，n=9 带 token 字段）平均 `inputTokens` 110180 / `iterations` 7.63 / `toolCalls` 10.13 / 成功率 81.48%；改造后（n=8）32992 / 2.50 / 3.25 / 100%（13/13）。同一 resolve 集下工具描述注入 **7223 → 4202 字符（省 41.8%）**、`internal_load_tool` 已在生效集（18 工具，改造前 16 无此工具）；`ci-gate.sh --no-clean` **5 门禁全部通过（0 项失败，用例 4099）**。**诚实边界**：两组任务类型不同（基线 A2A/真机回放类 vs 改造后纯文本写作类）⇒ token 降幅不可单独归因于 `MA-5`，可归因部分仅「描述注入体积 41.8%」；`internal_load_tool` 真实调用 0 次（模型 13 次调用全落白名单/短描述工具，说明「摘要够用」符合预期，但该工具触发率尚未观测到）；改造后样本仅 8 条、不构成统计显著。当前状态 = 「第一步落地并有运行期实证」，收益达成与触发率待更长观察期，状态以差距表 §0 `MA-x` 行为准。

---

### MA-6 状态契约外化

#### ① 说人话

"哪些状态能转到哪些状态"这张表写在 Java 代码里，前端、A2A 投影、外部执行器要各写一遍。

#### ② 现在的问题

`SubTaskStateMachine` 有 12 个状态、一张转移表。现状是**只有 Java 能读它**：

- 前端**目前没有**迁移表（实测全仓只有 `types/enums.ts` 的状态枚举与列表筛选清单，没有任何"当前态允许去哪"的判断）——这意味着一旦要显示"下一步能做什么"，前端只能把这张表**再写死一份**；
- A2A 投影（`ADR-009` D3 的三条投影原则）是手写的映射表，与状态机是两个地方；
- 外部执行器根本不知道平台的状态语义，只能靠 `submitResult` / `reportBlocked` 试错吃 400。

**但这一项现在做没有价值**——理由见 ⑥。写在蓝图里是为了**记住它的触发条件**，不是"越早越好"。

#### ③ 代码现状

| 位置 | 行为 |
|---|---|
| `common/constant/SubTaskStatus.java` | 12 态：`PENDING` / `ASSIGNED` / `IN_PROGRESS` / `PAUSED` / `REVIEW` / `DONE` / `REWORK` / `BLOCKED` / `CANCELLED` / `DEAD_LETTER` / `AWAITING_DERIVED` / `RESTRUCTURING` |
| `core/task/statemachine/SubTaskStateMachine.java`（**56 行**） | `EnumMap<SubTaskStatus, Set<SubTaskStatus>>` + 静态块 **12 条 `put`**，每条带 2~4 行注释解释"为什么允许这条边" |
| 同上 `validate` | 非法转移抛 `BizException("非法状态转换: %s -> %s")` —— **不带 guidance**（不说"当前态允许去哪"） |
| `core/task/service/impl/SubTaskStateMachineServiceImpl.java` `changeStatus`（约 :139） | **唯一消费点**。类注释自述「拆分后仍只有这一条写状态路径 —— 含跨类调用」 |
| `design/adr/ADR-009-a2a-compatibility-boundary.md` D3 | 投影三原则（无对应态挤 `WORKING`、终态只在三处发出等）—— 手写映射 |

**两个关键利好**：

1. 状态机只有 56 行，转成数据是"照抄一遍"的体力活；
2. 消费点只有一个类，改造半径极小。

**一个关键难点**：那 12 条 `put` 上的注释承载了大量"为什么允许这条边"的知识（例如"`PENDING` 仅限租约过期回收路径"）。**转 JSON 时这些知识不能丢**，否则三个月后就没人知道为什么 `IN_PROGRESS → PENDING` 是合法的。

#### ④ 参考实现

**AgentTeams：状态机做成跨语言数据 + 强制转移带 guidance** `[实测]`

`AgentTeams-main/plugins/teamharness/contracts/task-transitions.json`：

```text
states  = planned, prepared, assigned, in_progress, submitted,
          completed, revision, blocked, cancelled          （9 态）
terminal= completed, revision, blocked, cancelled
transitions:
  planned     → prepared | assigned | in_progress | submitted | cancelled
  prepared    → assigned | cancelled
  assigned    → in_progress | submitted | cancelled
  in_progress → submitted | cancelled
  submitted   → completed | revision | blocked | cancelled
```

文件头注明这是**跨语言黄金契约**，Go 与 Python 两侧在测试里互校（`mcp/common/constants.py:26-32` 另有一份 Python 常量）。

`mcp/lifecycle/transitions.py:197-249` 的 `_assert_transition` 做四件事：

- 同态重入 = 合法 no-op（幂等重试不需要额外放行）；
- 终态不可变 ⇒ 报 `task is in terminal state ... (immutable)`；
- 非法转移 ⇒ 抛 `IllegalTransitionError`，**且回带 guidance**（告诉调用方"当前态允许去哪"）；
- 每次拒绝都发 `illegal_transition_rejected` 信号，带 `from|to|action` 判别位。

**和我们对比**：

| 维度 | HelloAI | AgentTeams |
|---|---|---|
| 状态数 | 12 | 9 + 5 个决策子态 |
| 落点 | 代码（`EnumMap`） | 代码 + **跨语言数据契约** |
| 非法转移 | 抛异常 | 抛异常 + **guidance** + 拒绝信号 |
| 机器可读 | 无 | 有 |

**结论**：状态机这一项我们是**同一量级、不是领先**。真正的差异只在"表能不能被别的语言读"和"拒绝时说不说人话"。

#### ⑤ 我们的做法（等触发条件）

**触发条件**（满足任一才启动）：

1. **主触发：A2A B 档出站开始设计投影** —— 投影不能再手写一张表；
2. `MA-7` 的 ACP 适配器验证通过，且要开始做真实接入；
3. 要给前端 / 外部 Agent 开「状态机只读查询」。

**为什么加了条件 3**：外化本身只是搬数据，唯一的实质价值是"给别的东西当锚点"。若想提前做，就必须**同时**开一个只读端点（`GET /api/meta/subtask-state-machine`，返回 `states` / `transitions` / 每条边的 `reason` / `version`）——**有了这个读方，提前做才不叫"制造第二份没人读的事实"**。只外化、不开读方，就是纯搬运，收益为零。

**做法**：

```text
① 12 条 put 抽成 resources/contracts/sub-task-transitions.json
   每条边带 reason 字段（把 Java 注释搬过去，一字不丢）
② SubTaskStateMachine 启动时读 JSON 构建 EnumMap
   Java 侧仍是"唯一事实源"的执行者：JSON 是数据，代码是读取方
③ validate 失败时，报错信息加 guidance：
   "非法状态转换: IN_PROGRESS -> DONE；当前态允许：PENDING/REVIEW/BLOCKED/PAUSED/CANCELLED/DEAD_LETTER"
④ A2A 投影映射从同一份 JSON 派生
⑤ 写一个对拍测试：JSON 产出的表 == 原静态块产出的表
⑥ （仅当按触发条件 3 提前做时）开只读端点：
   GET /api/meta/subtask-state-machine
   → { version, states[], transitions[{from, to, reason}] }
   只有读，不提供任何变更能力
```

#### ⑥ 为什么现在不做

**这是判据 4「同一事实双写必分叉」的直接应用**：

- 现在做，外化出来的 JSON **没有第二个读方**——它就是"第二份没人读的事实"；
- 一旦有人漏改其中一边（比如加了新状态只改了 JSON 没改代码），就会出现两套真相；
- 而它的价值（"让非 Java 侧共享状态语义"）**必须等有非 Java 侧存在才兑现**。

**现在做叫"多一份要维护的东西"，触发条件满足后做叫"统一事实源"。**

这也是 `research` §8 把 `B-3` 排在第四位的理由，本文把它推到第六——因为本文的排序判据里多了一条"做完有没有人读"。

**放在第六，前面五项要跟着返工吗？不用。** 唯一可能耦合的是 `MA-4`（交付物闸）：它的落点在 `handleReport`（提交侧），不碰迁移表。只要 `MA-4` 坚持「闸在提交侧」，`MA-6` 排第几都不影响它。反过来说，若要让 `MA-4` 变成「进入 `DONE` 的前置条件写进状态契约」，那它就必须等 `MA-6` —— **不建议**，那会把一个只需 warn-only 观察的闸门绑死在状态机上。

#### ⑦ 边界与风险

**三条硬约束（写死，不许临场放宽）**：

1. **1:1 搬运，禁顺手优化。** 外化只搬数据，不改语义。特别注意 `SubTaskStateMachineServiceImpl` 里有 **5 处带明确目标态的 `validate`**（`:139` 通用、`:361`→`PENDING`、`:480`→`DONE`、`:545`/`:584`→`REWORK`）。任何一条"看着没人走"的边被删掉，这 5 处就会从"用户可纠正的 400"变成线上异常。
2. **加载失败必须 fail-fast。** JSON 里写错一个状态名，等于全部迁移被拒，**内部 LLM 与外部 Agent 两条链一起卡死**。启动期抛异常让服务起不来；禁止 fail-open（回落全放行），也禁止静默 fail-close（回落全禁止）——两种都会把配置错误伪装成业务故障。
3. **枚举一致性守卫。** 启动时断言 `JSON.states == SubTaskStatus.values()`，缺一个状态即启动失败。专治"加了新状态忘改 JSON"这类漂移。

| 风险 | 处置 |
|---|---|
| **注释里的知识丢失** | JSON 里必须有 `reason` 字段，搬注释不是"可选优化"而是硬要求 |
| **JSON 打进 jar，改它要重新打包** | 如果将来要运行时可改，属于另一个需求（外置配置）；先说清楚当前形态 |
| **对拍测试是唯一保护** | `SubTaskStateMachineTest` 已有 **63 条断言**，外化后必须全绿——这是"行为等价"最硬的第一证据；双份存在期间必须有对拍，对拍绿了才允许删 Java 静态块 |
| **文档里的第三份副本** | `doc/diagrams/html/subtask-state-machine.html` 是手工维护的图，与 JSON 会成第三份事实 ⇒ 外化后改为指针，不保留手写表 |
| **12 态 vs 9 态的语义差异** | 不照抄 AgentTeams 的 `revision` 作终态（与我们 `REWORK` 可重跑冲突，`ADR-009` D4 已裁定） |
| **决策子态** | 不引入 `frozen` 吸收态（属"明确不做"项，见 §5） |

#### ⑧ 验收判据 + 不做会怎样

**验收**：

1. 对拍测试：JSON 构建的表与原静态块构建的表**完全一致**（12 个状态的出边集合逐个比对）；
2. `SubTaskStateMachineTest` 现有 **63 条断言全绿**（外化前后同一套用例，这是行为等价的主证据）；
3. 每条边都有非空 `reason`；
4. 非法转移的报错信息包含"当前态允许去哪"；
5. 启动守卫生效：故意删掉 JSON 里一个状态名 → **服务启动失败**（不是运行期静默放过）；
6. A2A 投影映射若已存在，改为从 JSON 派生且输出不变；
7. 若同时开了只读端点：其返回的 `states` / `transitions` 与 JSON 逐项一致，且端点无任何写能力。

**不做会怎样**：A2A 投影继续靠人工维护（`ADR-009` D3 的映射改一处漏一处）；换人接手时状态语义要靠读 56 行代码重建；外部执行器无法机读平台状态。

---

### MA-7 ACP 适配器小样

#### ① 说人话

花一两天，验证一下"能不能用 ACP 驱动一个现成的外部 Agent"，只求通不通，不求能上生产。

#### ② 现在的问题

我们和外部 Agent 只有**一条通道**：对方实现我们的私有 MCP 工具面（`heartbeat` → `getSubTasks` → `submitResult` 轮询式）。

这条通道的问题：

- 对方要**适配我们**——每个新执行器都得写一遍；
- 我们的协议不是标准，生态里没人实现；
- 想接 Claude Code / Codex / OpenCode 这类现成的编码 agent，没有现成路径。

#### ③ 代码现状

| 位置 | 行为 |
|---|---|
| `core/agent/mcp/McpMcpServer.java` | 对外 17 个 `@Tool`（14 个 `@Tool` + echo + contributor） |
| `helloai-api/.../McpController.java` | 双入口：REST + JSON-RPC |
| 外部执行器接入方式 | **拉模式**：Agent 主动 `heartbeat` 保活 → 拉任务 → `submitResult` |
| `AgentAccessType` | 枚举里有 `API_KEY_LLM` 等；`A2A_AGENT` 是 B 档计划项，尚未落地 |
| ACP | **无**（全仓零命中） |

#### ④ 参考实现

**三个 harness 库全部实现 ACP，且全部双向** `[实测]`（二次核验后的结论）

| 库 | 被驱动（server） | 驱动别人（client） |
|---|---|---|
| DSH | `packages/acp/acp/` | `packages/subagent/subagent-acp/src/run.ts`（自述 *Fresh-process ACP subagent **client***，`import { client as createAcpClientApp } from '@agentclientprotocol/sdk'`） |
| Octop | `docs/acp.md` Inbound（被 Zed / OpenCode 驱动） | `src/octop/infra/agents/acp/session_modes.py:92-127`（codebuddy / claude_code / opencode / kimi_code / codex 五个后端） |
| octop-harness | `src/octop_harness/acp/server.py` | `src/octop_harness/acp/client.py` |

**DSH 的 client 侧实现细节**（最值得抄的部分）：

```text
subagent-acp/src/run.ts
  ├─ spawn 子进程
  ├─ NDJSON stream 双向通信
  ├─ PROTOCOL_VERSION 握手
  ├─ permission 请求：固定策略（allow 或 reject），不弹窗
  └─ 拥有 cancel 与 quiescent disposal（子进程清理）
```

**一个重要的边界认知**（`research` §6 已论证）：

> **ACP 回答的是「一个 harness 如何驱动另一个 harness 跑一次会话」**，**A2A 回答的是「两个平台如何交换任务与产物」**。两者不在同一层，**不冲突**。

所以这项验证**不是否定 A2A**，而是给"接外部执行器"多一条路。

#### ⑤ 我们的做法：最小 demo

**目标只有一个**：跑通一次完整会话，回答三个问题。

```text
范围（严格限定）：
  写一个最小 ACP client（Python 或 Node，几十行）
  → 连 DSH 的 ACP server（本地进程）
  → createSession → sendPrompt("用一句话介绍你自己") → 收结果 → cancel/close

不做的部分：
  ✗ 不接进 HelloAI 主链
  ✗ 不做权限 UI
  ✗ 不做多会话管理
  ✗ 不做产物回传（只观察 ACP 的 ContentBlock 能不能承载我们的 parts）
```

**要回答的三个问题**：

1. **握手复杂度**：从 spawn 到第一个 prompt，需要几步、几个必需字段？
2. **权限请求怎么处理**：ACP 有 `permission` 机制，我们的场景（沙箱外执行）怎么映射？
3. **产物能不能结构化**：ACP 的 `ContentBlock` 能不能承载"文本 + 结构化数据"两态？如果能，`parts` 与 ACP 有天然映射；如果不能，就要评估转换损失。

**产出**：一份 1~2 页的验证记录（放 `doc/research/`），含可复跑脚本的位置。

#### ⑥ 为什么值得花这一两天

- **成本极低，信息量极大**：不通也就一两天，通了可能一票改变"外部执行器怎么接"的路线；
- **三个库都是双向** ⇒ 不是小众实验，是这个层级的共识做法；
- **它不阻塞任何东西**：与 A2A B 档是两条路，随时可插。

**为什么不直接做完整适配层**：`research` §8 说得对——**以「最小 demo 跑通一次会话」为验证目标，而不是先做完整适配层**。完整适配层要处理 session 生命周期、权限、错误、重连，这些都要在"确定要接入"之后才值得做。

#### ⑦ 边界与风险

| 风险 | 处置 |
|---|---|
| **DSH 自述不安全** | `SAFETY.md:13,15` 明写不保证隔离、不可作唯一安全控制。**只在本机跑 demo，不当生产依赖** |
| **误以为要替换 A2A** | ACP 是会话层，A2A 是任务层。写清楚"不冲突" |
| **子进程/网络权限** | demo 只在本机，不涉及平台凭据；不要用生产环境的 key |
| **依赖版本漂移** | `@agentclientprotocol/sdk` 版本要锁定，写进验证记录 |
| **结论过度外推** | 只验证"DSH 的 ACP 能通"，不推广成"所有 ACP 实现都能通" |

#### ⑧ 验收判据 + 不做会怎样

**验收**：

1. 一次完整会话跑通（含 cancel），有可复跑的脚本；
2. 三个问题都有明确答案（握手几步 / 权限怎么映射 / 产物能否承载 `parts`）；
3. 记录依赖版本与踩坑点。

**不做会怎样**：继续只有 MCP 一条路。如果以后有人问"能不能接 Claude Code / Codex / OpenCode"，只能回答"要对方实现我们的协议"——而这三家的 ACP 都是现成的。

---

### MA-8 安全扫描只记不拦

#### ① 说人话

用户往对话里贴了一段带密钥的配置，平台现在完全不知道。这一项先让它"知道"，暂时不动手。

#### ② 现在的问题

`research` §3.2 复核了四个洞，全部成立：

| 洞 | 现状 |
|---|---|
| ① 输入侧无敏感内容扫描 | 全仓无输入扫描器 |
| ② 输出侧无交付物扫描 | 全仓无出站扫描 |
| ③ 沙箱内无危险命令守卫 | `grep CommandGuard\|dangerousPattern` 零命中 |
| ④ LLM 出口无内容网关 | `SecretMask` 仅 3 处调用，全在凭据回显 |

**但原文把 SEC 排在最前的论证不成立**。原文理由是"ToB 门票 + 成本最低"——本复核对成本判断有保留：

- 规则库 + 误报调优 + 三态交互（脱敏 / 阻断 / 放行留痕）**并不便宜**；
- 收益依赖"真有用户贴敏感文档"这一前提，而这个前提**目前没有数据支撑**；
- **直接阻断的误报会卡住正常需求，比不做更糟**。

#### ③ 代码现状

| 位置 | 行为 |
|---|---|
| 输入进对话 | `RequirementConversationController` / `ClarifyRoundOrchestratorServiceImpl` —— 无扫描 |
| 产物落库 | `ExecutionArtifactService` / `Attachment` 写入 —— 无扫描 |
| LLM 请求出站 | `AgentRuntimeContextAssembler` 构造 prompt → provider 调用 —— 无扫描 |
| `SecretMask` | 3 处，全在凭据回显：`PlatformProviderConfigServiceImpl:358` / `WebSearchCredentialKeyStore:74` / `CredentialVaultServiceImpl:646` |
| 沙箱命令 | `SkillExecutionSupport.run` / `internal_run_in_sandbox` —— 无命令守卫（`S-10` 已登记为"后置不启动"） |

#### ④ 参考实现

**Octop：HITL 三态流水线** `[实测]`

`src/octop/infra/gateway/hitl/coordinator.py:283-343` —— `pending → approved / rejected / expired`，每步都留痕。

`src/octop/infra/agents/security/hitl_session.py:18` —— 会话级策略：`ask` / `allow_all` / `allow_tools`（"始终允许"是按会话记的，不是全局）。

**DSH：升档阶梯 + 四态审批** `[实测]`

`packages/sandbox/sandbox/src/escalation.ts:65-77`（只允许从低档升到高档）、`:141`（`EscalationOutcome` 四态）、`packages/interaction/user-approval/src/types.ts:32`（`ApprovalOutcome` 四态）。

**但我们不能把 DSH 当安全权威**——它自己的 `SAFETY.md:7,13,15` 明写：

> *experimental developer-preview software. It has not undergone a security audit...*
> *Sandboxing, approval prompts, and permission controls... do not guarantee isolation or prevent damage.*
> *Do not rely on DeepSeek Harness as the sole security control for untrusted workloads.*

**判据**：抄它的**设计**（策略进请求、带理由申请提权、四态审批、拒绝留痕）不构成问题；但"安全对齐 DSH"这个论证方式在 ToB 场景会被反问。安全论证必须落到"我们自己的威胁模型 + 资源限额"上。

**明确不抄**：**纯命令黑名单**。`rm -rf` 的变形挡不住；能保住平台的是**资源限额**（CPU / 内存 / 磁盘 / 进程 / 出网白名单），黑名单只能当误操作提示。

#### ⑤ 我们的做法：只做 log-only

```text
规则集（最小起步，6~8 条）：
  身份证号 / 手机号 / 银行卡号 / 私钥头（-----BEGIN ... PRIVATE KEY-----）
  云厂商 AK 特征（AKID / LTAI 等前缀）/ 常见 token 前缀（sk- / ghp_ 等）
  配置项密码（**2026-11-20 新增**：`CONFIG_SECRET`——`password/secret/token/api_key/...` 键后紧跟 `=` / `:` 的值赋值形态；
  排除占位值 changeme / xxx / `$VAR` 引用等，专治「配置文件整段贴进对话」这一用户最关注场景）

四个挂点（都只记录，不阻断）：
  ① 用户输入进对话前
  ② 子任务产物落库前
  ③ LLM 请求出站前
  ④ 附件正文进 Prompt / 进知识库前（**2026-11-20 新增**：对话/澄清链的附件正文经 `{{ATTACHMENT_CONTEXT}}` 拼装绕过③，`internal_read_attachment` 直读对象存储亦绕过③——唯一能看到附件完整原文的位置是 `ChatAttachmentParseWorker.parse` 的 `markReady` **之前**，markReady 后正文即注入 Prompt / ingest 进 KB，故挂④于此处）

记录内容（关键）：
  规则 id / 挂点 / 命中位置（字段名 + 字符偏移）/ 命中片段的 SHA-256
  ★ 不存原文 ★（否则扫描器自己变成泄露源）

输出：一条事件 + 一天一份聚合（命中数 / 规则分布 / 场景分布）
```

**观察一个月**，拿到三个数：命中量、误报率、场景分布。**再决定要不要升阻断。**

**升阻断的前提**（三条都满足才行）：

1. 误报率可接受（具体阈值到时按业务定）；
2. 有豁免通道（用户可以说"这段没问题，继续"）；
3. 有开关，默认 OFF。

#### ⑥ 为什么这样改

- **为什么先 log-only**：判据「约束强弱看 fail-open / fail-close」——**在误报率未知的情况下 fail-close 是赌博**。而安全扫描的误报（把正常代码里的 `sk-` 前缀当密钥）几乎必然存在。
- **为什么不存原文**：扫描器本身会成为新的泄露面。存哈希 + 位置足够定位问题。
- **为什么三个挂点都要**：只扫输入会漏"模型自己生成的密钥"（比如从代码里读出来的）；只扫输出会漏"用户贴进来的"。三个点各挡一类。
- **为什么不把命令黑名单放进来**：见 ④ 的「明确不抄」。黑名单给我们一种"已经做了安全"的错觉，而实际挡不住任何真正的攻击。

#### ⑦ 边界与风险

| 风险 | 处置 |
|---|---|
| **误报** | 只记不拦，先量化；规则要能单个开关 |
| **性能** | 正则在长文本上可能慢；只扫描新增内容（不是每次全量重扫），超长文本截断 |
| **扫描器自身泄露** | 只存哈希 + 位置，不存原文 |
| **规则库维护** | 起步就 5~8 条，别追求覆盖；规则多了误报会爆炸 |
| **合规误读** | log-only **不等于**"已经合规"。文档要写清楚这是观测能力，不是防护能力 |
| **`S-10` 的关系** | `S-10`（沙箱危险命令守卫）已登记"后置不启动"，本项不改变它的状态 |

#### ⑧ 验收判据 + 不做会怎样

**验收**：

1. 造 5 条命中样本（各规则各一条）→ 全部命中，事件里有规则 id + 位置 + 哈希；
2. 造 5 条不命中样本（含形似规则的正常内容，如文档里写 `sk-learn`）→ 不误报（或误报被记录并计入统计）；
3. **全链路行为零变化**：只是多了一条事件，用户无感；
4. 事件里**没有**任何原文片段。

**不做会怎样**：用户贴敏感信息平台无感知；一旦发生泄露事件，连"什么时候贴的、贴到哪"都查不到。

---

### MA-9 敏感动作审批（拦截 → 询问 → 恢复）

#### ① 说人话

`MA-8` 让平台「看见」敏感内容后只写日志。`MA-9` 让它**当场停下问用户**：Agent 要进一次敏感动作时，页面弹一张卡——「放行 / 禁止」，选完子任务**接着跑**，而不是重来一遍。

> **2026-11-19 订正**：原描述写「允许访问 / 允许加密访问（推荐）/ 禁止访问」三档。经核实，**Agent 在当前体系里拿不到任何凭证**（凭证只在平台内存用于装配 LLM 客户端，沙箱 `credential=ISOLATED`、17 个内部工具无读取能力），因此「明文 / 加密」两档都指向空目标。落地形态为**两档**，第三档留契约位不上线——理由见 ⑤ 与 ⑦。

#### ② 现在的问题

平台没有「询问」这个动作，只有「卡住」和「失败」两种结局：

| 现象 | 后果 |
|---|---|
| 内部 LLM 的 17 个工具里**没有一个能向用户提问**（`grep ask_user\|user_confirm\|requestInput` 零命中） | Agent 遇到需要授权的事只能 `internal_report_blocked` 整块停下 |
| 子任务停下来只留 `context.manualIntervention` 标记，**没有「等哪一个问题、有哪些选项」的载体** | 人工介入面板只能说「这个子任务卡了」，说不出「它在问什么」 |
| 恢复路径不存在：被挂起的子任务只能走 `BLOCKED → PENDING` 重派或 `PAUSED → IN_PROGRESS` 重启 | 一旦答完想「原样接着跑」，只能重跑——已花的 token 再花一遍 |
| `SecretMask` 是纯静态函数（前 4 + `…` + 后 4），**无调用方上下文、无回调** | 它只能让密钥「显示得模糊」，无法表达「这次要不要问用户」 |

#### ③ 代码现状

| 位置 | 行为 |
|---|---|
| `helloai-common/.../SubTaskStatus.java` | 11 态含 `BLOCKED` / `PAUSED`，**无「等待审批」态** |
| `helloai-core/.../statemachine/SubTaskStateMachine.java:21` | `PAUSED → {IN_PROGRESS, CANCELLED, RESTRUCTURING}`——**没有「回到挂起前状态」这条边** |
| `helloai-core/.../SubTaskStateMachineServiceImpl#markManualIntervention` | 只写 `context.manualIntervention{reason, ts, extra...}` + 落 timeline，**无结构化问题体** |
| `helloai-core/.../shared/util/SecretMask.java:42-51` | 纯函数，3 个调用点全在凭据回显（`PlatformProviderConfigServiceImpl:358` / `WebSearchCredentialKeyStore:74` / `CredentialVaultServiceImpl:646`） |
| `helloai-core/.../runtime/tool/InternalLlmExecutorTools.java` | 17 个 `@Tool`，含 `internal_run_in_sandbox`（危险动作入口）、`internal_report_blocked`（唯一「停下」手段） |
| `helloai-core/.../runtime/sandbox/impl/SkillExecutionSupport#run` | 沙箱执行**唯一入口**（`S-10` 就是在这里挂命令守卫） |
| `helloai-ui/src/views/requirement/StructuredQuestionCard.vue` | 已有「题面 + 纵列选项 + 推荐标记 + 自定义补充 + 只读回显」全套，**但只在需求对话域使用** |
| 全仓 `grep "class .*Approval\|approval_request"` | **零命中**（`helloai-core` / `helloai-api` / `helloai-common` 三模块） |

#### ④ 参考实现（三家真实代码，逐条标「能抄 / 不能抄」）

**DSH：审批四态 + 升档阶梯 + 一次性授权**

| 事实 | 原文位置 | 我们能抄什么 |
|---|---|---|
| 三档沙箱 `read-only` < `workspace-write` < `danger-full-access` | `packages/sandbox/sandbox/src/index.ts:38` | **档位顺序**（只升不降）；但我们的档位按「放行 / 禁止」重定义（第三档加密访问留契约位），不照搬文件写权限语义 |
| 升档只允许从低到高：`WIDER_MODES = { 'read-only': ['workspace-write','danger-full-access'], 'workspace-write': ['danger-full-access'] }` | `escalation.ts:64-67` | **直接抄**：策略表 + 查表拒绝，不写 if 链 |
| 请求档位 = 当前档位 ⇒ 无需审批直接放行 | `escalation.ts:209` `if (mode === effectiveMode) return effectiveMode` | **直接抄**：这是防「每次都弹卡」的第一道闸 |
| 四态：`'allowed-once' \| 'rejected' \| 'cancelled' \| 'unavailable'` | `escalation.ts:141`、`interaction/user-approval/src/types.ts:32` | **抄三态**（once/rejected/cancelled），`unavailable` 在我们这里等价于「无人可问 ⇒ 按默认拒绝」，合并进 `rejected` |
| 申请必须带理由：`EscalationRequest { requestedMode, justification, effectiveMode, subject }`，且 `justification` 非空才合法 | `escalation.ts:186-196`、`:88-98` `validateEscalationArgs` | **直接抄**：Agent 申请凭证必须写清「拿它干什么」，这句话原样展示给用户 |
| 授权只对本次调用有效（one-shot），会话级记忆走**策略事件**而非审批 | `user-approval/src/index.ts:3`、`:240` | **直接抄**：审批卡不记「以后都允许」；要记就记成独立的策略变更事件，可回放 |
| 策略事件 `sandbox/mode` 落事件流、**不进模型对话历史**、replay 可重建 | `sandbox-policy/src/session-mode.ts:29`、`:53-55`、`index.ts:178-180` | **直接抄结构**：审批结论落 `sub_task` 事件 + timeline，**不塞进 `conversation_message`** |
| 生效档位 = 本轮显式批准 > 会话 override > 部署默认 | `sandbox-policy/src/index.ts:215` | **直接抄优先级**，三者缺一不可 |
| 拒绝后**只抛该动作的错误**，不中断整个会话，文案要求模型「停下并解释，不要绕过」 | `escalation.ts:247-251` | **直接抄**：错误文本要明确禁止模型换条路再来一次 |
| **没有** `expiresAt` / TTL，超时靠 `AbortSignal` 归为 `cancelled` | `user-approval/src/index.ts:296,321-333` | **不抄**：我们这边没有长时间挂起的 AbortSignal 链路，需要真 TTL（见下 Octop） |
| 审批卡字段：`requestKey / title / description / warning / subject / callId / allowLabel / canAnswer / onAllow / onReject / onAlways?` | `client/ui-approval/src/client/contract/slots.ts:207-230` | **抄字段划分**（尤其是 `subject` 明确「绝不作为授权身份」），我们的卡需要 `subtitle` + `link` 两个额外位 |
| 中文文案已有：等待审批 / 工具 {toolName} 请求越权执行 / 拒绝 / 允许 / 允许一次 / 始终允许 | `client/ui-approval/src/client/locales.ts:4-12` | **对齐措辞**，减少用户认知成本 |

**Octop：HITL 流水线 + 会话级免打扰 + 惰性过期**

| 事实 | 原文位置 | 我们能抄什么 |
|---|---|---|
| 四态 `pending / approved / rejected / expired` | `infra/gateway/hitl/store.py:11` | **抄 `expired`**（DSH 没有），这是我们需要的第四态 |
| 审批记录字段：`pending_id / thread_id / agent_id / user_id / session_key / channel_type / action_requests / review_configs / created_at / status / ask_question_index / ask_answers / resume_agent_id / resume_thread_id` | `store.py:47-64` | **抄 `resume_agent_id` / `resume_thread_id`**——这两个字段正是「答完接着跑」的锚点；我们没有 thread，等价物是 `subTaskId + agentId + round` |
| 审批单与动作绑定：每条 action = `{name, args, description}`，另有 `review_configs` 带 `action_name` + `allowed_decisions` | `format.py:56-71`、`:74-79` | **直接抄**：卡片必须展示「要调什么工具、参数摘要是什么」，并且**每个动作可以有不同可选集合**（有的只许拒绝） |
| **不记审批人 / 时间 / 理由**（`mark_resolved` 只改 status） | `store.py:316-323` | **不抄这个缺口**：我们落库时必须带 `decidedAt` / `decidedBy` / `decisionNote` |
| 会话级三档 `ask / allow_all / allow_tools`，白名单是**工具名精确匹配**（`tool_name in self.tools`） | `agents/security/hitl_session.py:18,47-61` | **抄 `allow_tools` 粒度**；不抄 `allow_all`（对我们风险过大，见 ⑥） |
| 策略按 thread 存，落 DB 列 `threads.hitl_policy`，内存做缓存 | `hitl_session.py:185-219` | **抄「策略是可持久化的对象」**，不抄具体列名 |
| sticky 三态 `all / tool / once`：`once` 不写策略 | `coordinator.py:764-800` | **抄 `once` 不落盘**这条——默认路径不留痕，避免「一次同意变成永久同意」 |
| `ask_user_question` 类审批**永不被免打扰绕过** | `hitl_session.py:55`、`:272-301` | **直接抄**：审批卡本身不受任何白名单影响 |
| 命令护栏类审批强制走人工（`_TOOL_GUARD_MARKERS = ("command guard", "command-guard", "命令护栏")`） | `coordinator.py:80`、`:83-94` | **直接抄思路**：高危类别标记为「不可豁免」 |
| 状态校验：decision type ∉ `allowed_decisions` ⇒ 400 拒绝 | `coordinator.py:243-257` | **直接抄**：服务端必须复核，不能只信前端 |
| 30 分钟 TTL，**惰性判定**（每次读时检查 `time.time() - created_at > ttl`），无后台任务 | `store.py:13`、`:136-138`、`:334-345` | **直接抄惰性过期**：不引入调度器，读时顺带 GC |
| 卡片有 4 个按钮：Approve / Allow for this chat / Reject / Allow all tools in this chat | `dashboard/src/pages/Chat/components/HitlApprovalCard.tsx:117-171` | **只抄前两个思路**；「Allow all」我们不提供 |
| **没有**「加密访问」中间档；decision 类型固定 `approve/edit/reject/respond` | `api/routers/chat/models.py:10` | **没有可抄的**——「允许加密访问」是本项目独有的第三档，必须自己定义 |
| **没有**敏感凭据识别（只有 `tool_guard.dangerous_shell_commands` 命令规则） | `agents/security/tool_guard_rules.py:17` | **不抄**：识别靠 `MA-8` 的规则集，不从 Octop 取 |
| 拦截点：流式投影收到 `hitl_required` chunk 就发卡并 `return`，**停住本轮** | `gateway/process/stream_project.py:196-213` | **抄「在流中断点弹卡」的形状**，我们的等价点见 ⑤ |
| 恢复：`resume_hitl(agent_id, thread_id, decisions)`，拒绝消息 `{"type":"reject","message":"Rejected by user"}` 原样注入模型 | `agents/manager.py:2168`、`coordinator.py:317-318` | **直接抄**：拒绝要作为**结构化消息进上下文**，而不是只改状态 |

**AgentTeams：凭据不出网关 + 审批等级（但要区分「文本约束」与「代码强制」）**

| 事实 | 原文位置 | 我们能抄什么 |
|---|---|---|
| 「凭据集中放网关 MCP 配置，Worker 不能直接访问」 | `manager/agent/SOUL.md:47` | **仅作原则参考**：它是 system prompt 文本，`bridge.py` 只把 SOUL.md 当文件复制/同步，**无任何代码读取并执行这些规则** |
| 「Worker 只用自己的 Consumer key-auth 访问 MCP」 | `SOUL.md:48` | **不抄**：我们没有网关层多租户 key 体系 |
| **代码强制面**：只有指向可信网关 host 的条目才注入 `Authorization: Bearer`，host 不可解析时 **fail closed** | `agentteams-controller/internal/agentconfig/mcporter.go:16-26`、`:59-63` | **抄这个判据形态**：把「可信目标白名单」做成**代码闸**，而不是靠提示词嘱咐 |
| 临时凭据有明确的过期字段：`STSToken{ ExpiresInSec }`，签发走 `IssueForCaller`（short-lived STS triple） | `internal/credentials/types.go:3`、`sts.go:32,52,68-101` | **抄「临时凭据带过期」**；**回收（revoke）在该仓库未找到**，我们得自己补 |
| 真正的审批机制是 `approval_level` 四级 `STRICT / SMART / AUTO / OFF` | `internal/server/worker_approval.go:2-15`、`:107-112` | **抄四级对称性**：任何安全开关都必须有 OFF，且 OFF 是**需要专门权限**的变更 |
| 关闭审批需 `CapabilityApprovalPolicy`，否则 403，并写 audit 事件 `approval_level_off` | `worker_approval.go:307-311`、`:404-419` | **直接抄**：安全开关的每次关闭都要落审计，不能静默 |
| 宿主机文件授权、prompt injection 检测：**均无代码实现**（只有 `SOUL.md:49,50` 文本） | 实测零命中 | **不抄、也不引用**——把它们当「已实现」会在 ToB 场景造成误判 |

**三家的共同结论**：真正可复用的只有**状态机 + 策略表 + 一次性授权 + 惰性过期 + 流中断点**这五件事；「允许加密访问」这个第三档、以及「凭据识别」这件事，**三家都没有现成实现**，必须我们自己定义。

#### ⑤ 我们的做法

```text
策略档位（**2026-11-19 订正：落地形态只有两档，第三档留契约位不上线**）：
  1 放行      —— 按既有路径继续（不改变任何现有行为）
  2 禁止      —— 不提供，Agent 必须换方案或停下

  契约位（定义而不启用，⑦ 已写死「通道未落地则该档不得出现在选项里」）：
  3 加密访问  —— 平台持有明文，执行方只拿一次性句柄 / 脱敏副本

  为什么第三档不能上线：实测 CredentialSecret 的两个消费方都是平台侧「建 ChatModel 用」
  （PlatformProviderConfigServiceImpl#getApiKey、AgentExecutionConnectivityServiceImpl），
  17 个内部工具里没有任何一个能读凭证，沙箱 ExecutionPolicy 的 credential=ISOLATED
  （注释写死「平台凭据密钥绝不进沙箱」）⇒ Agent 无论如何批都拿不到凭证，
  「允许加密访问」点了没有任何效果。且「明文放行」这一档在当前代码里同样指向空目标。
  ⇒ 两档的真实差异是「这个动作做 / 不做」，不是「凭证怎么给」。
  要真做加密档，须先自建：谁解密 / 句柄形态 / 注入点（沙箱零凭据 + 网络隔离，
  且 G-005② 禁止与 app 容器共享 env/网络）/ 有效期与销毁 / 审计——是独立项目，不算审批本身。

审批四态（DSH 三态 + Octop 的 expired）：
  PENDING / APPROVED / REJECTED / EXPIRED

数据形态：
  approval_request 表（新建）
    id / sub_task_id / task_id / agent_id / round
    action_type        （READ_CREDENTIAL / SANDBOX_EXEC / ...）
    action_subject     （要读哪个凭证、或要执行的命令摘要）
    justification      （Agent 写的理由，非空校验）
    options            （JSONB：本条动作允许的档位子集，服务端复核用）
    policy_level       当前策略档（默认档）
    status / created_at / expires_at
    decided_at / decided_by / decision_note   ← Octop 缺的三个字段，我们必填

挂起与恢复（本项最难的一环）：
  子任务 → BLOCKED + context.pendingApproval{approvalId}
  用户答复 → 写 approval_request.status
          → 清 context.pendingApproval
          → 以「原 round 续跑」方式回到 IN_PROGRESS
          → 决策结果作为**结构化消息**注入下一轮上下文（拒绝也要注入，含禁止绕过措辞）

策略通道（不进对话历史）：
  审批结论 + 策略变更落 sub_task 事件 / timeline（可回放）
  与 conversation_message 完全分离（沿用 MA-1 已确立的通道边界）
```

**挂起点选择**：不改状态机状态枚举，复用既有 `BLOCKED`（`markManualIntervention` 已提供写点与 timeline 事件），但**新增 `context.pendingApproval` 子结构**承载「在问什么、有哪些选项、何时过期」。理由：新增一个 `AWAITING_APPROVAL` 态要同步改 12 态投影（`ADR-009`）、A2A 出站映射与前端全部状态分支，半径远超本项收益。

**恢复语义**（**2026-11-19 订正**，原方案两跳不成立）：原文写「清标记 + 置 `PAUSED` + 再回 `IN_PROGRESS` 两跳，均为既有合法边」——**实测不成立**。`SubTaskStateMachine.java:31` 的 `BLOCKED` 出边只有 `{PENDING, RESTRUCTURING, CANCELLED, DEAD_LETTER}`，**没有 `BLOCKED → PAUSED`**，第一跳即非法，`validate()` 会抛「非法状态转换」。

订正后的做法（**同一 turn 内阻塞，不跨 turn**）：

```text
挂起：不落 BLOCKED，仍在 IN_PROGRESS（执行线程阻塞等待）
      仅写 context.pendingApproval{approvalId, actionType, options, expiresAt}
      同时置 agent_execution_record 的「等待中」语义（由 MA-10 的豁免判断识别）
恢复：不涉及任何状态迁移 —— 答复写入后阻塞返回，同 turn 继续跑
      toolCall.id 天然对齐，答复作为 tool response 回喂模型，round 不变
      ⇒ 状态机零改动，不新增任何边
```

**为什么不用落库挂起 + 跨 turn 续跑**：`LoopCheckpoint.java:14-15` 写死「**V66 边界：不做 LLM 级断点续接——只落进度事实，不回放消息历史**」。跨 turn 时 `AgentRuntimeContextAssembler.assemble` 重建 prompt，只带 systemPrompt + userPrompt + 依赖产物 + `RecoveryPoint`（工具名列表，非 messages）。所谓「原样续跑」在跨 turn方案下只能是「新 turn + 注入一段『你申请的 X 已批准』」，`agent_session.advance` 必然 round+1 ⇒ 字面上就是第二次派单，**⑧ 验收第 2 条永远过不了**。两害相权：同 turn 的代价是占用执行线程（`exec-cmd` 池 4~16），跨 turn 的代价是推翻 V66 边界 + 改状态机 + 改验收口径。

**同 turn 方案的硬约束**（`MA-10` 必须先行）：

- 等待上限必须**小于单工具超时** `toolCallTimeoutMillis=300s`（建议 120s）——否则 `tool_timeout` 会先返回 `failure`，模型看到「工具失败」而非「等你回答」，会换工具绕过（正是 ⑦ 要禁止的行为）
- 等待期间 `last_progress_at` 不更新，**必须被 `MA-10` 的无进展扫描豁免**，否则 10 分钟看门狗直接把子任务打 `BLOCKED`
- 超时按 `EXPIRED` 处置：写 `approval_request.status=EXPIRED` + 子任务落 `BLOCKED` + `manualIntervention` 原因码 `approval_expired`（人工面板可见），**不停留在无标记等待**

**两个必做的前置**（缺一不可）：
1. `MA-8` 的规则集先跑出误报率——否则不知道哪些动作该进「必问」；
2. `MA-10` 的挂起豁免先落地——否则任何等待都必然变超时；
3. 一个**总开关**，默认 OFF。开启后先只对一个动作类型生效——**建议用 `SANDBOX_EXEC` 而非 `READ_CREDENTIAL`**（订正：`READ_CREDENTIAL` 在代码里不存在，唯一真实存在的敏感入口是 `internal_run_in_sandbox`，且它易造样本）；先证明「弹卡 → 答 → 续跑」这条链是通的，再扩动作类型。

#### ⑥ 为什么这样改

- **为什么不把审批做成「策略即提示词」**：AgentTeams 的 `SOUL.md:45-50` 全是文本约束，代码零执行。我们的场景里模型可能被上游内容诱导，**只有代码闸算数**。所以策略档位必须落在「工具调用入口的前置判定」上，不是写进 system prompt 就完事。
- **为什么不加 `AWAITING_APPROVAL` 状态**：12 态是 A2A 投影与前端状态的共同事实源，加态等于三处同步改。用 `BLOCKED + context.pendingApproval` 能表达同一件事，且 `BLOCKED` 本来就有「等人」语义和既有 timeline 事件。
- **为什么第一版只有两档**（**2026-11-19 订正**）：原论断是「『允许加密访问』是默认推荐档，因为禁止会让正常任务失败、明文放行等于把 `MA-8` 白做」。订正理由更硬：**Agent 在当前体系里拿不到任何凭证**（凭证只存在于平台内存用于装配 LLM 客户端，沙箱隔离、无读取工具）⇒ 「明文 / 加密」两个档位都指向空目标，真实可辨的差异只有「这个动作做 / 不做」。给一个点了没效果的按钮比少给一档更糟（⑦ 已写死此判据）。第三档留契约位，等执行通道造出来再启用。
- **为什么一定要 `expired` 态**：同 turn 阻塞方案的等待上限很短（建议 120s），超时后必须有一个明确落点——`EXPIRED` + 子任务 `BLOCKED` + `manualIntervention` 原因码。没有 `expired` 的话「等过期了」和「正在等人答」在库里长得一模一样。
- **为什么审批结论不进对话历史**：`MA-1` 已经把「机器事实」与「呈现投影」分开过；审批结论是机器事实（谁批的、批的哪一档），塞进 `conversation_message` 会污染 LLM 上下文，且历史轮次读不出来。
- **为什么不提供「全部允许」**：Octop 有 `allow_all`，但我们面向的是 ToB 多租户——一个人点「全部允许」等于替整个任务集开了口子。最多做到「本子任务内对该工具免打扰」（对应 `allow_tools` 粒度），且必须显式记事件。

#### ⑦ 边界与风险

| 风险 | 处置 |
|---|---|
| **弹卡过多，业务无法推进** | 总开关默认 OFF + 先只对一个动作类型生效 + 复用 `MA-8` 的误报率数据决定是否扩类型 |
| **答完接不回去，子任务永久卡死** | 同 turn 方案下天然接得回（阻塞返回即可）；剩余风险是**进程重启**丢内存状态 ⇒ `approval_request` 表即使走同 turn 也必须落库，它同时承担「重启后那次挂起的审批去哪了」的唯一凭据。启动时扫 `status=PENDING` 的历史请求 → 一律转 `EXPIRED` + 对应子任务 `BLOCKED` + 人工面板可见，**不允许停留在「等待态」** |
| **等待期间被超时看门狗打死**（**2026-11-19 新增**） | `MA-10` 先行：`last_progress_at` 无进展扫描必须识别「等待中」并豁免。**这是 `MA-9` 的硬前置，不是可以并行的优化** |
| **`tool_timeout` 先返回，模型绕过审批**（**2026-11-19 新增**） | 等待上限（120s）必须小于单工具超时（300s）；且拒绝/超时文案必须带「停止并解释，不要换用其他工具达成同一目的」 |
| **Agent 绕过审批换条路** | 拒绝消息必须带「停止并解释，不要换用其他工具达成同一目的」（对齐 `escalation.ts:247-251` 的措辞强度） |
| **审批卡被伪造** | 服务端在答复时必须复核 `options` 与 `action_subject`：前端传来的决策若不在 `allowed_decisions` 内 ⇒ 400（Octop `coordinator.py:243-257` 同款） |
| **「加密访问」没有真实实现就成了空头档位** | 若加密通道尚未落地，则该档**不得出现在选项里**——宁可只给两档，也不给一个点了没用效果的按钮 |
| **凭证值出现在卡片里** | 卡上只显示 `subject`（凭证名 / 用途），**永不显示明文或脱敏值**；值只在服务端内部通道流转 |
| **与 `S-10` 命令守卫的关系** | 两者互补不重叠：`S-10` 判「命令本身危不危险」，本项判「要不要问人」。`S-10` 仍后置不启动，本项不改变它的状态 |
| **与 `B-7` 凭据引用的关系** | `B-7` 是根因方案（配置只存名字），本项是运行时闸。`B-7` 仍未启动；本项落地后，`B-7` 的价值不减但紧迫性下降 |

#### ⑧ 验收判据 + 不做会怎样

**验收**（**2026-11-19 按「同 turn + 两档」订正三条**）：

1. 造一个触发敏感动作的子任务（用 `SANDBOX_EXEC` 样本）→ 开关 ON 时**弹卡**，卡上显示动作对象、Agent 给的理由、选项；开关 OFF 时行为与改造前**完全一致**；
2. 点「放行」→ 子任务**同一 round 续跑到完成**，时间线可见「审批通过 → 续跑」两个事件，且不出现第二次派单、`round` 不变（DB 核对 `agent_session.turn`）；
3. 点「禁止」→ 子任务按拒绝处置（不进入死循环重试），拒绝内容进上下文且含禁止绕过措辞；
4. 超时（造一个 `expires_at` 已过的请求）→ `status` 变 `EXPIRED`，子任务落 `BLOCKED` 且人工介入面板可见（原因码 `approval_expired`），**不停留在无标记的等待中**；
5. 伪造一次越权答复（决策值不在 `options` 内）→ 服务端 400，状态不变；
6. 卡片任何位置**不出现凭证明文或脱敏值**；
7. 审批结论**不出现在** `conversation_message` 中（DB 直查核对）；
8. 等待期间血液检测：造一个等待超过 `noProgressTimeoutMinutes` 的样本 → 子任务**未被判超时**（验证 `MA-10` 豁免生效，这条是跨项证明）；
9. 进程重启：在 `PENDING` 状态下重启 → 启动扫尾把该请求转 `EXPIRED`、子任务 `BLOCKED`、人工面板可见；
10. 全链路回归：`MA-8` 的 log-only 行为零变化。

**停止条件**：第 2 条（答完续跑、同 round）通过才叫完成。只做到「能弹卡、能记录」而接不回去，等于把子任务从「卡在 LLM」变成「卡在人工队列」，比不做更糟。

**明确不做（守住范围）**：跨 turn 续跑（撞 V66 边界）、`AWAITING_APPROVAL` 状态（撞 12 态投影）、「加密访问」档上线（执行通道不存在）、「全部允许」策略（ToB 多租户风险）、挂起豁免机制（归 `MA-10`）。

**不做会怎样**：`MA-8` 永远停在「事后能在日志里查到用户贴过密钥」，而用户当场既不知道、也无法制止。事件发生后可以追责，但拦不住泄露本身。

---

### MA-10 执行链超时治理（次数主 + 时间兜底）

> **2026-11-19 追加**，原为 `MA-9` 的前置发现，独立成项。**这一项修的错现在就在发生**，不是为 `MA-9` 做准备。

#### ① 说人话

一个 Agent 干活时会被三层「时间」和一层「次数」同时管着。问题是：**次数用完被判死刑，而三层时间是倒挂的**——最外层（10 分钟）比最内层（30 分钟）还紧，所以那道 30 分钟的闸从来没机会开火。结果是：**一个跑了 19 轮、手里已经有八成成品的任务，因为第 20 轮没出终态，被整单判失败。**

`MA-10` 要做的只有一件事：**把「次数」变成真正的主闸（用完就体面收尾），把「时间」降级成只在真的卡住时才开火的兜底。**

#### ② 现在的问题

| 现象 | 后果 |
|---|---|
| `AgentLoopResult.maxIterations` 的 `success` 是 `false`（javadoc 明写「maxIterations 耗尽 = false」） | 轮次用完 ⇒ `RuntimeTurnExecutor:266` 落 `ExecutionStatus.FAILED` ⇒ `handleFailure` ⇒ 子任务 `BLOCKED` ⇒ 退避重派。**已做到的 19 轮成果全丢，token 再花一遍** |
| 三层时间闸倒挂：工具 5min < 执行记录 10min < turn 30min | 子任务链上 `runningTimeoutMinutes=10` 先到，`turnDeadlineSeconds=1800` **永远等不到** ⇒ 后者是死代码（只在业务面 `BusinessSurfaceLoopRunner` 生效） |
| 10 分钟是**固定总时长**，不是「无进展」 | 同一预算在快模型上太宽（浪费）、在慢模型+沙箱上太紧（误杀）。20 轮 × 慢工具 5 分钟超时 = 100 分钟，与 10 分钟毫无关系 |
| 无「无进展」概念 | 工具连续超时/重复调用时，系统只会等到总时长耗尽才动手；没有「跑了很久但一点没动」的判据 |
| `MA-9` 的同 turn 阻塞等待会被 10 分钟看门狗判超时 | 用户还在思考，子任务已被打成 `BLOCKED` 并进入自动重派。**审批与超时治理不共用一套机制，`MA-9` 必失败** |

#### ③ 代码现状

| 位置 | 行为 |
|---|---|
| `helloai-common/.../config/AgentExecutionProperties.java:73/81` | `internalMaxIterations=20` / `externalMaxIterations=5`（次数闸，两档） |
| 同上 `:103` / `:111` / `:209` | `turnDeadlineSeconds=1800` / `toolCallTimeoutMillis=300000` / `runningTimeoutMinutes=10`（三层时间闸） |
| `helloai-core/.../loop/impl/ChatModelToolLoop.java:167-168` | `forceFinalizeFromIteration = maxIterations - 1` 起禁用工具调用、强制出正文（**已做对了大半**） |
| 同上 `:259` | 出口固定走 `AgentLoopResult.maxIterations(...)` ⇒ `success=false` |
| `helloai-core/.../loop/AgentLoopResult.java:17,43` | `maxIterations` 恒 `success=false` + `finishReason="MAX_ITERATIONS"` |
| `helloai-core/.../runtime/RuntimeTurnExecutor.java:266-272` | `!loopResult.success()` ⇒ `status(FAILED)`（不区分「轮次耗尽」与「真空失败」） |
| 同上 `:188-198` | `AgentCancelledException` ⇒ `status(FAILED) + finishReason="CANCELLED"` |
| 同上 `:329` | `LoopCancellation.of(turnDeadlineSeconds, toolCallTimeoutMillis)` —— 在子任务链上被 10 分钟遮蔽 |
| `helloai-core/.../cancel/CancellableChatModel.java:34/37/51` | 取消靠**装饰器**在模型调用前后检查，不改循环体（正确解法，保留） |
| `helloai-job/.../task/ExecutionCompensationTask.java:45,64` | `@Scheduled(fixedRate=30000)`，按 `listByStatusStartedBefore(RUNNING, now - 10min)` 判超时 ⇒ `markTimeout` + `handleFailure` |
| 同上 `:97-110` | `isPlatformExecution` 只挡外部路径（`G-006`），平台内一律按 10 分钟判 |
| `helloai-core/.../entity/AgentExecutionRecord.java:56` | `lastAttemptTime` 语义是「DB Poller 扫描时间」，**不是进展时间**，不能复用 |
| `helloai-core/.../loop/LoopCheckpoint.java` | 每轮末落「进度事实」（轮数/工具数/消息深度），但**只 merge 进 `agent_session.snapshot`**，不触达 `agent_execution_record` |

#### ④ 参考实现（主流框架的实际默认值，2026-11 核对）

| 框架 | 主闸（次数） | 时间闸 | 撞线后交回什么 |
|---|---|---|---|
| LangChain `AgentExecutor` | `max_iterations=15` | `max_execution_time=None`（**默认关闭**） | `early_stopping_method="force"` 返回固定文案；`"generate"` 再调一次 LLM 合成答案 |
| LangGraph | `recursion_limit=25`（超步） | 无 | 抛 `GraphRecursionError` |
| OpenAI Agents SDK | `max_turns=10` | 无 | 抛 `MaxTurnsExceededError`，可挂 `error_handlers` 兜底输出 |
| CrewAI | `max_iter=25` | `max_rpm`（速率非超时） | Agent 停止迭代 |
| Mastra | `maxSteps=20` | 无 | **正常返回**，结束原因放进结果 |
| Vercel AI SDK `ToolLoopAgent` | `stopWhen` 20 步 | 无 | 正常返回 |
| Claude Agent SDK | `maxTurns` **无默认** | `maxBudgetUsd` **无默认** | `error_max_turns` / `error_max_budget_usd` |

**能抄的三件事**：
1. **主闸是次数、时间闸默认关闭**（除 LangChain 外六家都没有时间闸；LangChain 有但默认 `None`）⇒ 与我们「次数主 + 时间兜底」同向。
2. **撞线后优先交出部分成果**，而不是抛错：Mastra / AI SDK 正常返回并带结束原因；LangChain 的 `"generate"` 明确「基于已有步骤再合成一次」。**这正是我们现在缺的**。
3. **撞线形态分两类**：正常返回（Mastra / AI SDK）与抛错（LangGraph / OpenAI SDK / Claude）。我们应选**正常返回 + `finishReason` 标注**——抛错会把「尽力交付」变成「失败」，与判据相反。

**不能抄的一件事**：这些框架都**没有**「无进展检测」。LangChain 的时间闸是总时长 wall-clock，对「不可中断的工具」无效（`CancellableToolExecutor` 类注释自承此边界）。重复调用检测只在那篇 LangGraph 实战文里作为「自己补的防护」出现（记录 `[name, args]` 签名、连续三次相同则停）——**框架不给，得自己造**。

#### ⑤ 我们的做法

```text
四条改动，按依赖顺序（前一条不成立，后一条不许动）：

改动 1（出口语义，必须先做）
  轮次耗尽不再是「失败」，而是「尽力交付」：
    AgentLoopResult.maxIterations → success = !isBlank(text)
    finishReason 保留 MAX_ITERATIONS（下游可区分「自然 STOP」与「预算用尽」）
  守卫：text 为空 ⇒ 仍判 FAILED（复用既有 agent_runtime_empty_output 护栏，不新造旁路）
  不改：ChatModelToolLoop 循环体（§10.8 红线）；forceFinalizeFromIteration 现逻辑保留

改动 2（次数分档与覆盖）
  现状：internalMaxIterations=20 是「文档写作 12~14 轮 + 沙箱数轮」的经验值，非预算
  做法：① 保留 internal / external 两档（不引入第三套命名）
        ② 支持子任务级覆盖 sub_task.context.maxIterationsHint（拆解阶段由 planner 给预算）
        ③ 未给 hint ⇒ 回落档位默认（行为与改造前一致）
  注意：forceFinalizeFromIteration = maxIterations - 1 ⇒ 实际可用工具轮次 = maxIterations - 1
        （off-by-one，改数值时必须同步检查，否则「加了预算但实际没变」）
  不在本项做：按任务类型的自动分类器（那需要先有基线数据，见 ⑦ 问题 2）

改动 3（时间闸降级为「无进展」兜底）
  新增列 agent_execution_record.last_progress_at（V153 起按批次分配，不改已 apply 迁移）
  写点：LoopCheckpointListener 每轮末顺带更新该列（best-effort，失败只 warn）
        + 工具调用开始/结束时也更新（避免「单轮内跑 20 分钟」被误判无进展）
  改条件：ExecutionCompensationTask 的 RUNNING 扫描从
          「start_time < now - runningTimeoutMinutes」
          改为「last_progress_at < now - noProgressTimeoutMinutes」
          （last_progress_at 为 NULL 的历史行回落 start_time，保持存量行为）
  豁免：status=WAITING_APPROVAL 语义（由 MA-9 写入 context.pendingApproval）⇒ 跳过该行
        该判断只读 context 字段，不在本项引入审批表依赖

改动 4（收敛时间闸层级，最后做）
  保留：toolCallTimeoutMillis（单工具兜底，可按类别分档）
  保留：noProgressTimeoutMinutes（真正的兜底，替换 runningTimeoutMinutes 的总时长语义）
  删除：turnDeadlineSeconds 在子任务链的装配（RuntimeTurnExecutor:329 改为只传工具超时）
        业务面 BusinessSurfaceLoopRunner:189 保留（那里没有 execution record，无次数兜底之外的保护）
  理由：两道时间闸并存会让「谁先开火」取决于任务类型，无法解释、无法测试
```

**落点不是 `TaskDispatchPort`，在出口与扫描条件上**：改动 1 在 `ChatModelToolLoop` 出口 + `RuntimeTurnExecutor` 的状态映射；改动 3 在 `ExecutionCompensationTask` 的查询条件 + 一列。`SubTaskStateMachine` **零改动**（不新增状态、不新增边）。

#### ⑥ 为什么这样改

- **为什么轮次耗尽要算「尽力交付」而不是失败**：Mastra / AI SDK 都这么做，LangChain 的 `"generate"` 是同一意图。判据是「用户拿到八成成品」优于「任务失败需要重跑」——后者要再花一遍 token，且用户不知道前 19 轮做过什么。**但空正文必须仍判失败**，否则把失败伪装成交付，撞上已有护栏。
- **为什么要「无进展」而不是「总时长」**：总时长把「慢但有效」和「卡死」判成同一件事。前述 20 轮 × 5 分钟工具超时 = 100 分钟，固定 10 分钟总时长会误杀一个正常的长任务，而「无进展」不会。这也是主流框架没解决的部分——它们的时间闸是 wall-clock，本项目有条件做得更准（因为已有 `LoopCheckpoint` 这个天然的进展信号）。
- **为什么改动 1 必须在撤销 10 分钟之前**：现在 10 分钟看门狗是轮次耗尽缺陷的**兜底**。如果先把看门狗放宽或改语义、而轮次耗尽仍判失败，跑满预算的任务会从「10 分钟失败」变成「20 分钟失败」，只是推迟，不是修复。
- **为什么不给 `turnDeadlineSeconds` 加一个更大的值**：那会让「两道时间闸」永久并存。次数主 + 时间兜底的正确形态是**一道兜底**，不是三道叠着。留两道就有「为什么这个任务 12 分钟死、那个 40 分钟才死」的解释不了的问题。
- **为什么 `MA-9` 不再单独造挂起豁免**：审批等待与「无进展」在机制上是同一件事——都是「这段时间没有新进展，但不该判死」。`MA-10` 提供 `last_progress_at` + 豁免判断，`MA-9` 只需写入一个可被识别的标记。**再造一套等于两套阈值互相打架。**

#### ⑦ 边界与风险

| 风险 | 处置 |
|---|---|
| **轮次耗尽改判成功，把「没做完」说成「做完了」** | `finishReason=MAX_ITERATIONS` 必须被下游消费：`MA-4` 交付闸要能识别并按「尽力交付」评估（而非按失败跳过）；正文为空仍判 `FAILED`（既有护栏） |
| **`internalMaxIterations` 分档值没有数据支撑** | `MA-5` 刚生效（描述注入省 41.8%），且改造后仅 8 个 `agent_completed` ⇒ **当前基线不可用**。必须先按任务类型分别重采基线（见 `MA-5` 实测的诚实边界），再定分档值。**在此之前只做「支持覆盖」，不改默认值** |
| **「无进展」阈值定错，误杀慢任务** | 默认值必须**比单工具超时明显更长**（工具 300s ⇒ 无进展至少 3~4 倍）；且阈值可配置、先只 warn 观察再转 fail（与 `MA-4` 第一段同一条纪律：先观察不改行为） |
| **`last_progress_at` 成为「无读取方的列」** | 本项自身即消费方（`ExecutionCompensationTask` 扫描条件），不是死列。但历史行回落 `start_time` 的逻辑必须测（否则存量 RUNNING 行行为改变） |
| **每轮末写一次 `agent_execution_record` 增加写压力** | 与 `LoopCheckpoint` 合并成一次写（它本来就每轮写 `agent_session.snapshot`）；单轮内的工具调用更新可为 best-effort（失败只 warn，不阻断执行） |
| **删 `turnDeadlineSeconds` 后失去「总时长」硬上限** | 这是**有意的取舍**：次数主意味着总时长的上界由「轮次 × 单轮」给出，而非一个拍出来的固定值。剩余风险（单轮极慢）由单工具超时 + 无进展双兜底覆盖 |
| **与 `G-006` 外部路径的关系** | 不动。`isPlatformExecution` 的判断保留，无进展检测同样只作用于平台内执行（外部执行者的合法时长由租约与人工节奏决定） |
| **与 `MA-3` 台账 `round` 的关系** | `round` 语义不变（仍取 `ToolInvocationContext.turn`）。只要不做跨 turn 续跑，台账不出现「同一轮记两个 round」的分叉 |

#### ⑧ 验收判据 + 不做会怎样

**验收**：

1. 造一个跑满轮次预算的任务（把 `maxIterationsHint` 调低）→ 终态 `ExecutionStatus=SUCCESS` + `finishReason=MAX_ITERATIONS` + 正文非空；**不再出现** `agent_runtime_max_iterations` 一类失败；
2. 同上但模型末轮空正文 → 仍判 `FAILED`（护栏未被破坏，既有单测锁死）；
3. 造一个「工具卡死」样本（工具阻塞超过单工具超时且反复重试）→ **无进展检测在总时长耗尽前先开火**，`last_progress_at` 时间线与终态可对上；
4. 存量 RUNNING 行（`last_progress_at` 为 NULL）→ 回落 `start_time` 判定，行为与改造前一致（回归测试）；
5. 同 turn 阻塞等待（人工介入标记）超过 `noProgressTimeoutMinutes` → **该行被豁免，不判超时**（这条是 `MA-9` 的前置证明）；
6. 子任务链上不再存在「10 分钟先于 30 分钟开火」的倒挂：`turnDeadline` 不再参与子任务链装配（代码可 grep 验证）；
7. 业务面（需求对话 / 评审）行为零变化（`BusinessSurfaceLoopRunner` 仍用 `turnDeadlineSeconds`）；
8. `ci-gate.sh --no-clean` 全绿（含 `LoopCheckpoint` 与 `ExecutionCompensationTask` 既有单测）。

**停止条件**：第 1、3、5 三条同时通过才算完成。只做到「轮次耗尽判成功」而没做无进展检测，等于**把失败推迟**——原来 10 分钟死，现在 100 分钟死，用户反而更晚知道。

**不做会怎样**：`MA-9` 无法开工（审批等待必然撞超时）；且当前状态是「跑了 19 轮有用工，第 20 轮判失败」——每一个接近完成的长任务都在被系统性浪费，而这件事**不记录、不上报**，只在日志里留一句 `agent loop reached max iterations`。

---

## 5. 明确不做

| 不做 | 依据 |
|---|---|
| **决策子态 / `frozen` 吸收态**（B-6） | 要动 12 态状态机，而 A2A 投影刚对齐（`ADR-009`）。引入吸收态会让"终态不可重开"与我们的 `REWORK` 可重跑直接冲突 |
| **凭据引用重构**（B-7） | 动的是安全主链（`CredentialVaultServiceImpl` / `SecretMask`），收益不直接可见。等有具体威胁场景再做 |
| **阻断式安全扫描** | 见 `MA-8` ⑥：误报率未知时 fail-close 是赌博 |
| **纯命令黑名单当安全边界** | `rm -rf` 的变形挡不住；真能保住平台的是资源限额。黑名单只可作误操作提示 |
| **AgentTeams 的 `revision` 作终态** | 与 `REWORK` 可重跑冲突，`ADR-009` D4 已裁定 A2A task 与 subTask 身份解耦 |
| **AgentTeams 的「Matrix IM 作协作总线」** | 把协作语义塞进聊天房间，代价是"谁在做什么"靠消息推断；我们的 `sub_task` 结构化事实更可审计 |
| **DSH 的「沙箱 = 安全边界」** | `SAFETY.md:13,15` 自述不保证隔离、不可作唯一安全控制 |

**进了视野但没进 `MA` 编号的四项**（说清楚去向，避免"为什么漏了"）：

| 项 | 去向 | 理由 |
|---|---|---|
| **B-4 契约自带 `defined-not-enforced` 标注** | 随时可插，不单独开号 | 成本极低（给机制级契约加一个 `status` 字段），但收益是间接的（防止"文档说有能力、代码没有"）。等 `MA-6` 真的产出契约文件时一并加最自然 |
| **B-5 事件 id 确定性幂等** | 待勘察，暂不立项 | 需要先看我们事件流现状（是否有内容哈希、是否有可用的持久序号）。没勘察完就排顺序是瞎排 |
| **B-6 决策子态** | 明确不做（见上表） | — |
| **B-7 凭据引用** | 明确不做（见上表）；**运行时替代见 `MA-9`** | — |

---

## 6. 与现有主线的交叉

这一节是为了防止一个误读：**这份蓝图不是要替代现有计划**。

| 现有主线 | 与本文的关系 | 冲突风险 |
|---|---|---|
| **`ADR-009` A2A 双平面**（A 档已落地，B 档待启动） | `MA-1` 是 A 档的**收尾**（把 `parts` 从"写进去"变成"可被各端稳定读取"）；`MA-6` 要**等** B 档（投影需要共享状态语义） | 无。本文不动 12 态内核、不动投影原则 |
| **`G-036` A2A 兼容边界** | `MA-1` 直接服务它（残留③「`parts` 无原生读方」） | 无 |
| **`TD-11` 验收契约可满足性标注** | 与 `MA-4` 在**同一条线上**（都在管"验收判据"），但对象不同：`TD-11` 标"这条判据能不能被满足"，`MA-4` 查"产物到底有没有" | 有**概念重叠**风险。开工 `MA-4` 前要先读 `TD-11` 的实现，避免两套"判据标注"并存 |
| **`TD-10` ledger 通道**（登记不启动） | 与 `MA-3` 相关（都是"记录执行事实"） | **需要确认**：`MA-3` 落地后，`TD-10` 是否还有独立价值，或可以合并 |
| **`SL-5` 沙箱调用台账** | `MA-3` 要**超越**它（内存 → 落库；加 `skillName`） | 无冲突，`SL-5` 继续服务"当前子任务内证据引用" |
| **`S-10` 危险命令守卫**（后置不启动） | `MA-8` **不改变**它的状态；`MA-9` 也不改变（两者判的是不同问题：`S-10` 判「命令危不危险」，`MA-9` 判「要不要问人」） | 无 |
| **`B-7` 明文不落地的凭据引用**（明确不做） | `MA-9` 是它的**运行时闸**替代品：`B-7` 从根上让配置只存名字（未启动），`MA-9` 在访问时拦一次问人。两者互补，`MA-9` 落地不降低 `B-7` 的价值 | 无。但要注意别宣称「做了 `MA-9` 就等于做了凭据隔离」——`MA-9` 只拦被识别出的动作 |
| **`MSG-x` 站内消息** | `MA-4` 第二段的"gateFindings 可见"可能想发通知 | 若要发通知，走 `MSG-3` 的事件码→消息映射表，不要新开一条通道 |

**能并行开工的**：`MA-1` / `MA-2` / `MA-3` / `MA-7` —— 四项互不依赖，也不依赖任何现有主线。

---

## 7. 编号与状态

| 编号 | 名称 | 状态 | 实施计划 |
|---|---|---|---|
| `MA-1` | 执行结果结构化契约 | 已完成 | `plan/HelloAI MA-1 执行结果结构化契约实施计划（2026-11-18）.md` |
| `MA-2` | 候选集快照 | 已完成 | `archive/implemented/HelloAI MA-2 候选集快照实施计划（2026-11-18）.md`（2026-11-24 归档） |
| `MA-3` | 技能执行台账 | 已完成 | `archive/implemented/HelloAI MA-3 子任务技能执行台账实施计划（2026-11-18）.md`（2026-11-24 归档） |
| `MA-4` | 交付物登记闸 | 第一段落地，观察期中（跑满一周看误报率后三选一） | `plan/HelloAI MA-4 交付物登记闸实施计划（2026-11-19）.md` |
| `MA-5` | 工具面按需加载 | 第一步「目录 + 按需取」落地并有运行期实证（生效集 18 工具含 `internal_load_tool`、描述注入 7223→4202 字符）；收益达成待更长观察期 | `plan/HelloAI MA-5 工具面按需加载实施计划（2026-11-19）.md` |
| `MA-6` | 状态契约外化 | 待开始（**等触发条件**：A2A B 档开始设计投影 / 开只读端点） | 未开 |
| `MA-7` | ACP 适配器小样 | 待开始 | 未开 |
| `MA-8` | 安全扫描只记不拦 | 验证中（**四条挂点 + 7 条规则已落地，含单测 27+7 例**：三挂点 `INPUT/ARTIFACT/LLM_OUTBOUND` + 新增挂点④ `ATTACHMENT`（附件原文唯一可拦点，`markReady` 前）；规则新增 `CONFIG_SECRET`（配置键值赋值形态，排除占位值）；前端 `RequirementChat.vue` 命中附件加敏感标记胶囊与提示行；观察期未跑出真实误报率 ⇒ 按 §2.2 停止条件不标「已完成」） | 已开 |
| `MA-9` | 敏感动作审批（拦截 → 询问 → 恢复） | 待开始（**前置 `MA-8` 已落地**：规则集即「什么该问」的判据源；**前置 `MA-10` 已落地**：挂起豁免 `ExecutionProgressRecorder.touchWithApprovalAwareness` + `ExecutionCompensationTask.isAwaitingApproval` 均已就位） | 未开 |
| `MA-10` | 执行链超时治理（次数主 + 时间兜底） | 验证中（**四条改动已全部落地**：尽力交付 / 次数分档+hint 覆盖 / 无进展兜底+审批豁免 / 删子任务链 turnDeadline；`ci-gate.sh` 5 门禁全绿。**观察期未跑 `noProgressDetectionEnforced=true` 的真实分布** ⇒ 按观察期纪律不标「已完成」） | 已开 |

> 状态词沿用差距表 §0 的九态词表（本文只用 `待开始` / `设计中` / `实现中` / `验证中` / `已完成` / `暂缓` / `明确不做`）。
>
> **本文不设进度百分比**，需要量化时用子项计数。各项的真实进度以 `plan/` 对应实施计划为准，本文只维护"方案是否还成立"。

---

## 8. 修订记录

| 日期 | 变更 | 触发 |
|---|---|---|
| 2026-11-18 | 首版。八项 + 三条排序判据 + 逐项八段骨架 | `research/HelloAI 开源对标复核（2026-11-18）.md` 二次核验后落点位 |
| 2026-11-18 | `MA-1` 由「A/B 待决策」改为「B 简版定稿」：新增独立读模型 `execution-result`（只做选择+打包，不重新投影）、`context` 标记为客户端不应依赖、`sections` 仅预留；补七个问题点与七条验收。`MA-6` 补三条硬约束、「只读端点」触发条件，并记明排第六不需返工前五项 | 决策：移动端/小程序在计划内 ⇒ 客户端数量会变成 2 个以上，各端自己分流必然重复实现 |
| 2026-11-18 | 新增 §2.1「在哪停是安全的（批次划分）」与 §2.2「每项的停止条件」：四批次划分（第一批 = 干净停点）、八项各自的「做完」判定口径。§2 只给一句话口径，详细验收仍以 §4 各项 ⑧ 为唯一出处 | 问题「这批改造是否必须一次全部完成」⇒ 补「分批在哪停」这一层，原 §2 只有依赖图、没有停点信息 |
| 2026-11-18 | `MA-1` ⑤ 端点名订正：`GET /api/sub-tasks/{id}/execution-result` → **`GET /api/sub-tasks/getExecutionResultById/{id}`**，与 `SubTaskController` 既有「动词 + `ById/{id}`」风格一致 | `MA-1` 实施计划勘察时发现原写法会引入第二套端点命名 |
| 2026-11-19 | 新增 **`MA-9` 敏感动作审批（拦截 → 询问 → 恢复）**，八项扩为九项；§2 总览 / §2.1 批次 / §2.2 停止条件 / §7 状态表同步登记。定位：`MA-8` 只做「发现」，`MA-9` 补「询问 + 恢复」；`MA-9` 前置 `MA-8`（规则集是「什么该问」的判据源）。参考实现只取三家**代码强制**面：DSH 升档查表与审批四态（`escalation.ts:64-67,141,209`）、Octop 惰性过期与策略持久化（`hitl/store.py:11,136-138`、`hitl_session.py:47-61`）、AgentTeams 的审批等级 OFF 审计（`worker_approval.go:307-311`）；并明确标出三家**都没有**的两件事——「允许加密访问」第三档与凭据识别，须本项目自定 | 用户提出「期望拦截提示卡（允许 / 允许加密访问 / 禁止）」；实测算例：全仓 `Approval` 零命中、内部 17 工具无提问能力、`PAUSED` 无回挂起前状态的边 |
| 2026-11-19 | **新增 `MA-10` 执行链超时治理（次数主 + 时间兜底）**，九项扩为十项；`MA-9` 增加第二个硬前置 `MA-10`。§1 口径 / §2 总览+依赖图 / §2.1 批次 / §2.2 停止条件 / §4 新增整节（八段骨架）/ §7 状态表 / §8 同步登记。**`MA-9` 五处订正**：①恢复路径「`BLOCKED`→`PAUSED`→`IN_PROGRESS` 两跳」实测不成立（`SubTaskStateMachine:31` 无该边）⇒ 改为同 turn 阻塞、零状态迁移；②「跨 turn 挂几小时」放弃（撞 `LoopCheckpoint` V66 边界：不回放消息历史，跨 turn 必然 round+1 = 第二次派单）；③策略三档降为**两档**（Agent 在当前体系拿不到任何凭证 ⇒ 明文/加密两档都指向空目标，第三档留契约位）；④首个动作类型从 `READ_CREDENTIAL` 改为 `SANDBOX_EXEC`（前者代码中不存在）；⑤⑦/⑧ 补「等待被超时看门狗打死」与「`tool_timeout` 先返回导致模型绕过」两条风险 | 问题「单工具超时 / turn 截止 / RUNNING 超时是如何处理的，开源代码是否也是写死时间」⇒ 核实发现本项目已有次数主闸（`internalMaxIterations=20`/`external=5`）但**轮次耗尽判 `FAILED`**，且三层时间闸层级倒挂（10min 遮蔽 30min，后者在子任务链上是死代码）；对照七家框架（LangChain 15 / LangGraph 25 / OpenAI SDK 10 / CrewAI 25 / Mastra 20 / AI SDK 20 步 / Claude 无默认）确认「次数主 + 时间闸默认关闭」是行业形态，且撞线后应「交出部分成果」而非抛错。用户决策：**改蓝图原文、`MA-9` 复用共享机制不再单独造一套** |
| 2026-11-20 | **`MA-8` 补挂点④ `ATTACHMENT` + 规则 `CONFIG_SECRET`**（用户最关注项「上传/粘贴配置文件会不会提示」的落地）。**三洞实测**：①对话/澄清链附件正文经 `ChatAttachmentPromptRenderer` 的 `{{ATTACHMENT_CONTEXT}}` 拼装绕过挂点③；②`internal_read_attachment` 直读对象存储绕过③；③消息 content 挂点① 只扫正文不扫附件。**选点**：`ChatAttachmentParseWorker.parse` 的 `markReady` 之前是唯一能看到附件完整原文的位置（之后正文即注入 Prompt / ingest KB），故新增挂点④；扫描经 `SensitiveScanRecorder.scanAttachmentAndRecord` 带 `conversationId + attachmentId` 回溯到具体文件，命中数三态写入 `chat_attachment.scan_hit_count`（`V153` 追加列，`null`=未扫描 / `0`=无命中 / `>0`=命中），`ChatAttachmentVO` 与 `RequirementChat.vue` 带出——**上传即告知**（胶囊标记 + 提示行），不弹卡。**规则**：`CONFIG_SECRET` 识别 `password/secret/token/api_key/...` 键后 `=` / `:` 赋值形态，排除占位值（changeme / xxx / `$VAR` 引用）。**顺带修复真实缺陷**：①原先附件根本没被扫描（非「只记不拦」，是「根本没记」）；②scan 异常曾冒泡导致整条附件解析 FAILED、用户须重传（挂点④ 调用点整体 try-catch，观测不伤主链，判据 27）。验证：`SensitiveContentScannerTest` 27/27、新增 `ChatAttachmentParseWorkerScanTest` 7/7（三态 / 开关关闭 / scan 异常不阻断）；`ci-gate.sh --no-clean` 待本轮复跑确认（先前因 target 残留旧 V153 重复版本失败，残留已删） | 用户追问「上传附件 / 粘贴含敏感性配置文件内容，平台会弹出提示么？还是只拦截？」；实测三通路均绕过既有三挂点，且扫描器从未在附件上运行过 |
| 2026-11-20 | **`MA-8` 与 `MA-10` 落地**（本轮代码改动，零方案订正）。`MA-8`：6 条规则枚举唯一事实源 `SensitiveScanRule` + 3 挂点 `SensitiveScanPoint` + 纯判据 `SensitiveContentScanner`（身份证过 GB 11643 校验位 / 银行卡过 Luhn，抗误报）+ 唯一「扫+记」入口 `SensitiveScanRecorder`（**payload 结构上不含原文，只留 SHA-256 + 偏移**）+ 时间线码 `sensitive_content_detected`；三挂点收口于 `RequirementMessageServiceImpl.addMessage` user 分支（澄清/助理/流式/编辑重跑四路共用）、`ExecutionResultHandler` 成功分支（扫 `output` 非 `parts` 全文）、`AgentRuntimeContextAssembler`（`userPrompt` 装配后，覆盖「依赖产物把上游密钥传到下游」）。`MA-10`：**改动 1（尽力交付）落点重构**——原方案「`AgentLoopResult.maxIterations` 改 `success`」实测不可行（该字段被 `FinalReportFenceHook` 收尾轮判据 / `BusinessSurfaceLoopRunner` / 多个既有单测共同消费）⇒ 改为新增 `isPartialDelivery()` 判据 + **只改消费侧映射**，既实现尽力交付又不掩盖「循环层未自然终止」真值；`VERIFICATION` 出口闸以 `naturalStop` 前置**显式豁免尽力交付**（模型没有第 21 轮可以补围栏）。改动 2：`resolveMaxIterations` 分档 + `sub_task.context.maxIterationsHint` 覆盖（装配层解析一次注入 `AgentContext`，执行层是唯一消费点故钳制也收口于此；`MIN_MAX_ITERATIONS=2` 是循环体结构性下限不是运维偏好）。改动 3：新增列 `agent_execution_record.last_progress_at`（`V153`）+ 扫描条件 `COALESCE(last_progress_at, start_time)`（**存量 NULL 回落保证等价**）+ `ExecutionProgressRecorder` 双写点（循环回调合并写 + 审批感知续命）+ `ExecutionCompensationTask` 按 `noProgressDetectionEnforced` 分岔（**默认 false = 只 warn 不处置，与 `MA-4` 第一段同源观察期纪律**）。改动 4：删子任务链 `turnDeadlineSeconds` 装配（`LoopCancellation.ofToolTimeoutOnly`），业务面 `BusinessSurfaceLoopRunner:189` 保留。验证：`ci-gate.sh --no-clean` **5 门禁全绿 0 失败**（含 B 级集成 55 例）；定向单测 `RuntimeMaxIterationsResolutionTest` 11 / `RuntimePartialDeliveryTest` 13 / `ExecutionProgressRecorderTest` 9 / `ExecutionCompensationTaskTest` 7（原 3 + 新增 4）/ `SensitiveContentScannerTest` 20 全绿；`check-arch-freeze` 未超基线；`verify-event-key-parity` PASS=5 | 用户「MA-8 + MA-7 开工吧，代码要符合规范，建议从 MA8 一直到 MA10」；实施中撞出两处真实缺陷：`PRIVATE_KEY_HEADER` 漏捕获组致 `No group 1` 崩溃（改为「漏写括号退化为精度损失而非运行期崩溃」双保险）、`maxIterationsHint=0` 绕过装配层过滤后仍被 `Math.min` 抬到下限（证明钳制必须在消费点收口）；另修 `ExecutionContext` 三处测试缺 mock（`ExecutionResultHandlerTest` / `ExecutionResultHandlerDeliveryGateTest` / `AgentRuntimeContextAssemblerTest`）与 `RuntimeTurnExecutorTest.shouldMapTokenUsageOnFailure` 选例失真（改动 1 已把「非空正文 + 有工具」改判 SUCCESS ⇒ 改走空产出护栏保住原判据） |
