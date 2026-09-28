# HelloAI 契约层能力注入与最终报告整合改造方案

> **文档定位**：本方案在原有「契约层能力注入 + 最终报告链路修复」计划基础上，**新增阶段三「报告整合范式改造（3A/3B/3C）」**，并给出「按 V2 架构能到达的质量档位」与「是否需进一步调整 V2 架构」的结论。本阶段**仅产出本文档**，不修改任何生产代码 / 测试 / Migration / 配置 / 手册。
>
> **方案来源**：用户自拟《契约层能力注入与最终报告链路改造计划》（四阶段）+ 评审结论（2026-09-28）。
>
> **依据规约**：`doc/README.md`（权威链）、`doc/HelloAI_AI开发协作规约.md`（§6/§8/§25/§27/§28/§32/§34/§40）、`doc/HelloAI_CODE_STYLE.md`（§5.3/§6.1/§7.1/§7.2/§30.3/§35/§38/§50.7/§54/§55/§64）、`doc/HelloAI 目标架构.md`（§3/§6/§8/§9/§11）、`doc/HelloAI 实现差距表.md`（§7.1 孤儿项回流）、`doc/HelloAI 项目基线文档.md`。
>
> **复核口径**：凡结论标注 `[代码事实]`（已逐行定位，给出 `文件:行`）或 `[分析判断]`（推断，未运行验证）。工作区只读，未改动 git 工作区。
>
> **最后更新**：2026-09-28

---

## 0. 现状 → 问题 → 目标

### 0.1 现状（`[代码事实]`）

| 项 | 事实 | 位置 |
|---|---|---|
| 执行输入契约 | `AgentTask` 只有 `subTaskId / systemPrompt / userPrompt / context / requiredCapabilities / temperature`，**无技能声明字段** | `agent/domain/AgentTask.java:16-40` |
| 执行服务 | 仅做 路由 → checkCapability → heartbeat → 执行器直传，**无技能注入** | `agent/service/impl/PlatformAgentExecutionServiceImpl.java` |
| 技能注入 | 只存在于子任务执行链内部（`SubTaskExecutionServiceImpl.executeOnce` 自拼 section，走 `ExecutionCommand.requiredSkills`） | 同上 |
| 报告链路 | 全平台**唯一零技能注入**的 LLM 调用；`buildSections` 只读 `context.lastExecution.output`；`SECTION_OUTPUT_LIMITS={8000,2000,500}` 裸字符头部截断；**无核验、无返工** | `task/service/impl/TaskFinalReportServiceImpl.java:107/123/276-299` |
| 报告 Prompt | 铁律 2/3/4 与「强制全覆盖」叠加 ⇒ 数学上等价于「换标题重新贴一遍」；末尾 HTML 自检清单**无任何代码读取** | `resources/prompts/task-final-report.md:13-37/95-120` |
| 联网搜索 | `WebSearchService`（Router 三实现）以 planner 域内服务直连 `ClarifyWebSearchOrchestrator`，不进 `ToolRegistry`、无 TOOL_CALL 事件、外部 Agent 不可见 | `planner/service/WebSearchService.java`、`planner/search/WebSearchServiceRouter.java`、`planner/clarify/ClarifyWebSearchOrchestrator.java:33-50` |
| 已存在的可复用资产 | `TaskRunningSpecService.findRecord(taskId, subTaskId)` / `buildExecutorPromptSection(taskId)`；`AttachmentService.listActive/isContentLoadable/loadContent`；`AgentSkillSpecService.resolve(...)→ResolvedSpec(requiredSkills, matchedLabels, section, ...)`；`SubTaskService.mergeSkills(SubTask)`；**`review/support/ReviewEvidenceAssembler`（附件优先 + 每附件/总计限额 + 文本族判定全套实现）；`review/picker/ReviewerPicker.pickSingle`（javadoc 注明 subTask 可空、taskId 为空走回退链——报告审查传 null 零改动复用；`[代码事实]` 注：null 会**跳过**「任务级指定」分支，见 §3A 评审补正 v2；另注：该组件限额/族集合常量为 `private static final`（`:37-:56`），跨域引用须先抽共享——**已定稿路径①**（新增 `shared/util/AttachmentContentPolicy`，两域共用），见 §2.2-7）** | 均已存在 `[代码事实]` |
| 子任务链为何更好 | 双轨核验 + `helloai.dispatch.auto-review-max-rework`（默认 3）轮自动返工，驳回意见写回 `context.reviewHistory` 重新下发 | `review/service/impl/SubTaskReviewServiceImpl.java:263-284` |

### 0.2 问题

1. **契约层缺 skills 挂点** ⇒ 三条同步链（拆解 / 审查 / 报告）各自接线，违反 CODE_STYLE §50.7 平行接线风险。
2. **报告读取口径错** ⇒ 只读 `output`（物化开启时仅为 displayText 摘要）、未用 `ExecutionRecord`、未用物化附件、裸字符截断破坏 Markdown 块。
3. **报告无质量闭环** ⇒ 单次调用、成功即落库，唯一重试条件是 token 超限，且重试方式是压缩输入而非改进输出。
4. **报告 Prompt 目标自相矛盾** ⇒ 强制「独立成章 + 100% 保留 + 章节数=总览表行数」，把模型推向复制粘贴，天然产出「比子任务原文更短的拼接版」。
5. **联网搜索绕过 Capability Layer** ⇒ 出现第二套 Provider 路由，且未登记（违反差距表 §7.1）。

### 0.3 目标状态

- 同步 LLM 调用在**契约层**统一获得技能解析注入（`AgentTask.skills` 声明；执行链不填 ⇒ 行为零变化）。
- 最终报告与子任务产出**同口径读事实源**（物化附件优先、结构化执行记录注入）。
- 报告具备**核验 → 驳回 → 重写**闭环，与子任务链路同构。
- 报告 Prompt 目标自洽：读者 / 用途 / 篇幅预算 / 主线论点 / 跨章去重 / 章节按读者顺序。
- 新平台能力**二选一进目录**：技能包（`requiredTools` 声明）或 `ToolRegistry` 工具回调；禁止域内 Service 直给 LLM / 编排器接线。
- 全程**无 DB / MQ / 状态机变更**。

### 0.4 质量档位（同一把标尺）

| 档位 | 构成 | 报告相对子任务文档 |
|---|---|---|
| **≈40** | 现状 | 明显更差（缺料 + 碎表格 + 无主线） |
| **≈70** | 本方案阶段一 + 阶段二 | ≈ 子任务文档平均水平（规范、不缺料，但仍是「拼接」，无新增价值） |
| **≈75** | + 阶段三 **A**（核验/返工闭环） | 下限被抬高：不合格即打回重写 |
| **≈80** | + 阶段三 **B**（Prompt 目标重写） | 从「拼接」转为「提炼」：有读者、有篇幅预算、有主线 |
| **≈85** | + 阶段三 **C**（大纲先行两段式） | 跨章去重与主线真正成立 |
| **≈90** | + **V2 架构调整**（§9）：Synthesis 环节 + SYNTHESIZER 角色 + Quality Gate 泛化 + Skill Instructions | 达到参考线（WorkBuddy / DeepSeek 量级） |

> **结论预告**：**不动 V2 架构，天花板约 85**；要到参考线 90，**必须动架构**（详见 §9）。

---

## 1. 阶段一：`AgentTask.skills` + 契约层统一技能注入

### 1.1 修改文件

| 文件 | 改动 |
|---|---|
| `helloai-core/.../agent/domain/AgentTask.java` | 新增 `@Builder.Default List<String> skills = Collections.emptyList()`；新增 `@With` 以生成 `withSystemPrompt`（`@Value + @Builder` 兼容 Lombok `@With`；若编译异常改手写 `withSystemPrompt`，见风险 R2） |
| `helloai-core/.../agent/service/impl/PlatformAgentExecutionServiceImpl.java` | 注入 `AgentSkillSpecService`；`execute(Agent, AgentTask)` 与 `executeStream` 两处，在 `checkCapability` 之后、调执行器之前执行统一注入 |
| `helloai-core/.../planner/service/impl/PlannerDecomposeAsyncServiceImpl.java` | `doDecompose` 构造 `AgentTask` 处声明 `.skills(task.getRequiredSkills())`（null 防御为 `List.of()`） |
| `helloai-core/.../review/support/ReviewExecutionEngine.java` | `execute(subTask, reviewer, channel)` 构造 `AgentTask` 处声明 `.skills(subTaskService.mergeSkills(subTask))`（review → task 方向允许） |
| `helloai-core/.../task/service/impl/TaskFinalReportServiceImpl.java` | `generate` 构造 `AgentTask` 处声明 `.skills(task.getRequiredSkills())`（null 防御） |
| `helloai-core/src/test/.../PlatformAgentExecutionServiceTest.java` | 适配新增构造参数 + 新增用例 |

### 1.2 注入逻辑（定稿）

```java
// skills 非空才注入；子任务执行链不填 → 行为逐字节不变
if (task.getSkills() != null && !task.getSkills().isEmpty()) {
    AgentSkillSpecService.ResolvedSpec resolved = agentSkillSpecService.resolve(task.getSkills());
    if (resolved != null && resolved.section() != null && !resolved.section().isBlank()) {
        String base = task.getSystemPrompt() == null ? "" : task.getSystemPrompt();
        String section = "## 平台技能规范（执行速览）\n" + resolved.section();
        task = task.withSystemPrompt(base.isBlank() ? section : base + "\n\n" + section);
    }
}
```

判据：`skills` 为空不注入（缺省 `List.of()`），全部既有构造点零改动编译通过。

### 1.3 兼容性与风险

- **API / MQ / DB**：无。**Prompt 模板**：不改任何模板文件（动态拼 `systemPrompt`），按 CODE_STYLE §55 补测试样例锚定注入行为。
- **双轨边界（须显式声明）**：子任务执行链**不填** `AgentTask.skills`，继续走 `ExecutionCommand.requiredSkills` 自拼 section，以保护 `SKILL_RESOLVED / TOOL_RESOLVED` 事件与 `requiredTools` 联动。**因此本阶段并非「全链统一」，而是「3 条同步链统一 + 执行链保持既有机制」**——须在 CODE_STYLE 新规则（阶段五）中写明该边界，避免后人误判「已统一」。
- **模板悬空引用同步（必须同批）**：在 `task-final-report.md` 中把「速查见 `eng-doc-standard` D2 清单」替换为内联 8 类清单原文（原悬空引用在阶段二处理，见 §2.2-8）。
- **回滚**：纯增量字段 + 单类注入，撤销提交即可，无迁移。

---

## 2. 阶段二：最终报告读取链路修复（报告链单类 + 附件口径上移 shared）

### 2.1 修改文件

| 文件 | 修改/新增 | 说明 |
|---|---|---|
| `helloai-core/.../shared/util/AttachmentContentPolicy.java` | **新增** | §2.2-7 路径①：限额常量 + 四组族判定 + 判定纯函数（`public final` 纯静态，无 Spring） |
| `helloai-core/.../review/support/ReviewEvidenceAssembler.java` | **修改（纯重构）** | 删除本地私有常量与两个 private 判定方法，改调 `AttachmentContentPolicy`；**装配逻辑与阈值不动，行为零变化** |
| `helloai-core/.../task/service/impl/TaskFinalReportServiceImpl.java` | **修改** | 报告链唯一业务类（§2.2 条款 1~6） |
| `helloai-core/src/main/resources/prompts/task-final-report.md` | **修改（模板，非业务类）** | §2.2-8：D2 悬空引用替换为内联 8 类清单原文 |
| `helloai-core/src/test/.../AttachmentContentPolicyTest.java` | **新增** | 口径单源断言 |
| `helloai-core/src/test/.../TaskFinalReportServiceTest.java` | **修改** | §2.4 用例 |

> **范围说明（较原计划扩大，须显式承认）**：本阶段不再是「绝对单类」——路径① 需**一次口径上移**（新增 1 个 `shared/util` 纯工具类 + 1 处 review 域**纯重构**）。两处改动均**行为零变化**，`review → shared` 合规（§6），`shared/util` 引用 task 域有既有先例（§2.2-7）。之外仅 1 处模板修改（§2.2-8，非业务类）；除此外**无其他文件改动**。

### 2.2 改动定稿

1. **注入**：`TaskRunningSpecService`、`AttachmentService`（均 task 域，无跨域问题）。
2. **每段读取口径对齐**：物化附件优先（`attachmentService.listActive(subTaskId)` → `isContentLoadable` → `loadContent` → UTF-8），`context.lastExecution.output` 兜底——与执行链 `loadUpstreamContent` 同一事实源。
3. **结构化资产注入**：经 `taskRunningSpecService.findRecord(taskId, subTaskId)` 取 `ExecutionRecord`，非 null 时在产出正文前渲染「执行摘要 SUMMARY」与「交付物清单 DELIVERABLES / MANIFEST」。
4. **替换裸字符截断**：按 Markdown 块边界（``` 围栏、表格行、段落）切分，保证块完整。
5. **全局上下文**：`renderPrompt` 在 sections 前拼接 `taskRunningSpecService.buildExecutorPromptSection(taskId)`（自带标题，非空才拼），模板结构不动。
6. **降档阶梯保留**：`SECTION_OUTPUT_LIMITS` 逻辑不动，但块级截断使降档不再破坏 Markdown 结构。
7. **限额口径对齐（评审补正 v3 · ⚠️ 定稿：路径① 抽共享）**：task 域不能注入 review 域（§6.1），附件读取逻辑在 task 域内实现（§50.3 最小变更），但**限额与族判定口径必须与 `review/support/ReviewEvidenceAssembler` 单一同源**（该组件已实现「物化附件优先 + 单附件限额 + 总计限额 + 文本族/媒体族判定 + 清单/正文双装配」全套模式）。既有口径为：

   - `OUTPUT_SUMMARY_LIMIT=4000`（`output` 摘要口径，**与报告链 `SECTION_OUTPUT_LIMITS` 是两套不同用途的限额，不得混用**）；
   - `ATTACHMENT_CONTENT_PER_FILE_LIMIT=8000` / `ATTACHMENT_CONTENT_TOTAL_LIMIT=24000`；
   - **四组**族判定集：`TEXTUAL_MIME_EXACT` / `TEXTUAL_EXTENSIONS` / `MEDIA_MIME_PREFIXES` / `MEDIA_EXTENSIONS`。

   **定稿做法（路径①）**：新增 `helloai-core/.../shared/util/AttachmentContentPolicy.java`（`public final` 纯静态工具，**无 Spring / 无业务实体依赖**），承载上述 3 个限额常量 + 4 组族集合 + 判定纯函数 `isTextual(String mimeType, String fileName)` / `isMedia(String mimeType, String fileName)` / `extensionOf(String fileName)`；`ReviewEvidenceAssembler` 删除本地 `private static final` 常量（`:37-:56`）与两个 private 判定方法（`isTextualAttachment` / `isMediaAttachment`），**改调该策略（纯重构，行为零变化）**；报告链引用**同一**类。

   - **合规性（`[代码事实]`）**：`* → shared` 合规（§6）；且 `shared/util` 引用 task 域**已是既有先例**——`shared/util/SubTaskOutputExtractor.java:3` 直接 `import com.helloai.core.task.entity.SubTask`，现被 8 处（agent / review / task 三域）消费。
   - **参数据设计**：策略方法取 `(mimeType, fileName)` **原始值**而非 `Attachment` 实体，使 `shared` 不依赖任何业务实体（比 `SubTaskOutputExtractor` 先例更严格；该先例必须收实体是因要读 `context`）。
   - **收益**：两域共用同一常量源 ⇒ **测试可直接 `import AttachmentContentPolicy` 断言**（原「private 常量无法跨域引用」的阻塞消除）；「复刻 + 放宽可见性 / 反射断言」路径（v2 的路径②）**作废**；**口径漂移风险归零**。
   - **抽取边界（须写明，防后人误解为「已全量归并」）**：
     - **上移（本批）**：口径（限额 + 族判定）——这是「两套限额各说各话」风险的全部来源；
     - **不上移（本批）**：装配循环与文本渲染（`buildAttachmentList` / `buildAttachmentContent` / `buildMediaVisibilityNote` / `readAttachmentContent` 留在 review 域；报告链自渲染）。理由：① 渲染文本是域内展示需求；② 报告链必须**块级截断**（§2.2-4），而既有实现是**字符级** `substring` 截断（`ReviewEvidenceAssembler.java:201-202`）——强行统一装配会引入 review 域行为变更风险；
     - **登记为可选优化（非漂移风险）**：把 `truncateAtBlockBoundary(content, limit)` 作为**共享纯函数**加入策略，供报告链本批使用；review 域是否跟进改为块级截断，登记为后续可选优化（**不在本批动 review 行为**）。
   - **开关语义（须显式决策）**：`helloai.dispatch.attachment-content-enabled`（`AgentDispatchProperties.isAttachmentContentEnabled()`，消费点 `ReviewEvidenceAssembler.java:175`）原为「核验侧附件正文注入」开关。报告链接入后**同一开关将牵动第二个消费方**。**建议：报告链不受该开关约束**（报告链已自带 `SECTION_OUTPUT_LIMITS` 降档阶梯），即开关语义**收窄为「仅核验侧」**并写入其 javadoc / 配置说明——避免「关掉核验注入顺带把最终报告内容也砍掉」的隐性耦合。
   - **另注**：附件限额 `8000` 与 `TaskFinalReportServiceImpl.SECTION_OUTPUT_LIMITS` 首档 `8000`、`agent/quality/ExecutorIssueResolutionAssessor.OUTPUT_LIMIT=8000` 是**三个互不相关的限额**（`[代码事实]` 全仓共 3 处 8000）——这正是「附件限额必须是**具名共享常量**、不得裸写数字」的直接证据。
8. **模板悬空引用内联（与阶段一必须同批，§1.3）**：把 `task-final-report.md` 中「速查见 `eng-doc-standard` D2 清单」替换为内联 8 类思维链泄漏清单原文——该引用对模型**始终不可见**（技能注入的 section 是技能摘要，不承载 D2 清单原文，注入与否都悬空）；阶段一接入技能注入后悬空引用即有害，故与本批同批。按 CODE_STYLE §55 配套渲染断言（断言渲染输出不含「见 `eng-doc-standard`」字样、含 8 类清单原文）。

### 2.3 需定死的 4 个细节（评审补正）

| # | 问题 | 定稿 |
|---|---|---|
| V1 | 原计划写「段尾标注 `[TRUNCATED] 该段已摘要化`」，但**未定义由谁做摘要**——若无实际摘要动作，只是标注，内容依然丢失（从「丢尾部碎块」变为「丢完整块」） | **二选一，不得含糊**：<br>① **真摘要**：超限块走一次轻量 LLM 摘要（或映射到 `ExecutionRecord.SUMMARY` / `DELIVERABLES`）后再注入，标注 `[SUMMARIZED]`；<br>② **不摘要**：标注改为 `[TRUNCATED] 该段已截断，完整内容见子任务产出/附件`，并删除「以已提供部分为准」字样。<br>**本轮推荐 ①（映射到已有结构化记录，零额外调用）**，仅对无结构化记录的子任务走 ②。 |
| V2 | 「附件优先」与 `SECTION_OUTPUT_LIMITS` 首档 8000 的关系未定 | 预算优先取首档；**单附件超预算时块级截断 + 走 V1 策略**；多附件按预算顺序拼接，多附件合计超预算时**优先保留有 `EXECUTION_RECORD` 的附件** |
| V3 | 附件优先后单段可能比 `output` 更大，加剧 token 爆炸 | 保留降档阶梯；**新增**：降档时优先降「附件正文」而非「结构化摘要」（结构化摘要始终保留） |
| V4 | 多文件（manifest 协议）子任务的读取 | 与执行链完全同源：`listActive` 返回全部 active 附件，非仅首个；避免「只取一份」 |

### 2.4 测试与验证

新增/更新用例：附件优先命中、`findRecord` 摘要注入、多附件容量预算、块级截断不切表格/代码块、超限标注、**口径同源断言**（`AttachmentContentPolicyTest` 断言限额/族集合为**唯一常量源**；`TaskFinalReportServiceTest` 断言报告链与 `ReviewEvidenceAssembler` 均引用该策略，而非各自私有常量）、**`ReviewEvidenceAssembler` 重构后行为不变回归**（改调策略后既有核验侧用例全绿）、`skills` 声明注入（与阶段一联动）。Prompt 修改按 CODE_STYLE §55 提供渲染断言样例。

---

## 3. 阶段三：报告整合范式改造（**A → B → C**，本方案新增）

> **定位**：阶段一 / 二是「止损」（让报告不再残缺、格式规范）；本阶段才是「让它具备整合质量」。三者与 §0.4 档位一一对应：A→≈75、B→≈80、C→≈85。
>
> **硬约束（贯穿 3A/3B/3C）**：**不得创建第二套 Review Runtime / 第二套 Workflow Runtime / 第二套状态机**（协作规约 §40、目标架构 §11）；**不得新增 `task → review` 反向依赖**（CODE_STYLE §6.1，仅 `review → task` 合法）。

### 3A. 报告核验 / 返工闭环（性价比最高）

**问题**：`[代码事实]` 全仓 `finalReport` 相关 `review / rework / verdict` 代码为空 —— 报告是「单次 best-effort」。而子任务之所以好，是被 `auto-review-max-rework`（默认 3）轮打回改出来的。

**候选方案对比**

| 方案 | 做法 | 评价 |
|---|---|---|
| A1 在 task 域内直接加审查 | `TaskFinalReportServiceImpl` 内加一次审查 LLM 调用 | **违反 §6.1**：task → review 不允许（需 `VerdictParser` 等 review 域资产） |
| **A2 事件驱动 + review 域闭环（推荐）** | task 域生成成功后发 `TaskFinalReportGeneratedEvent`；review 域监听 → 调审查 → `fail` 则回调 task 域 `rework(taskId, feedback)` 重新生成 | **依赖方向合规**（review → task 合法）；**复用** `PlatformAgentExecutionService` + `VerdictParser`（不新建 Runtime）；与既有 `TaskAutoCompletedEvent → TaskFinalReportServiceImpl` 监听模式同构 |
| A3 新建 `ReportReviewRuntime` | 独立审核服务 | **禁止**：§40 第二套 Review Runtime |

**推荐**：**A2**。

**改动文件清单**

| 文件 | 修改/新增 | 说明 |
|---|---|---|
| `helloai-core/.../shared/event/TaskFinalReportGeneratedEvent.java` | 新增 | 携带 `taskId / reportLength / sectionCount / attempt` |
| `helloai-core/.../task/service/TaskFinalReportService.java` | 修改 | 新增 `rework(Long taskId, String reviewFeedback)` |
| `helloai-core/.../task/service/impl/TaskFinalReportServiceImpl.java` | 修改 | 生成成功后发事件；`renderPrompt` 支持注入 `{{REVIEW_FEEDBACK}}`（非空时拼「上轮审查驳回意见」，与子任务返工修正指引同构）；`rework` 复用 `generate` 主链并递增 attempt |
| `helloai-core/src/main/resources/prompts/task-final-report-review.md` | 新增 | 审查 Prompt：验收标准 = ①覆盖追溯表完整 ②跨章重复率 ③表格/代码块完整性 ④主线是否成立 ⑤是否有子任务里没有的提炼；输出沿用 `VerdictParser` 可解析格式 |
| `helloai-core/.../review/service/impl/FinalReportReviewListener.java` | 新增 | `@EventListener` 监听生成事件；选 reviewer 复用 `ReviewerPicker`（传 null 会跳过任务级指定，见下方补正）；审查证据装配复用 review 域既有 `ReviewEvidenceAssembler`（本监听器在 review 域，**可直接复用、无需复刻**）——与子任务审查同一装配口径；调审查 → `pass=false` 且未达上限则回调 `rework`；**须含「自审自过守卫」（`reviewer != writer`）** |
| `helloai-common/.../config/AgentDispatchProperties.java` | 修改 | 新增 `auto-final-report-max-review`（默认 **1**，保守，避免与 `auto-review-max-rework` 叠加烧钱） |

> **评审补正（ReviewerPicker 复用成本）**：`ReviewerPicker.pickSingle(SubTask)` javadoc 明确「subTask 可空；taskId 为空时直接走回退链」（`review/picker/ReviewerPicker.java:26`）——报告审查**无子任务，传 null 即走回退链，零改动复用**。
>
> **⚠️ 口径收紧（评审补正 v2）**：实现 `ReviewerPickerImpl.pickSingle` 对「任务级指定 `reviewerAgentId`」有 `subTask != null && subTask.getTaskId() != null` 守卫（`ReviewerPickerImpl.java:41`，`:44` 为读取 `policyReviewerId` 行）——**传 null 会跳过该分支**。因此报告审查实际链路为 `AgentSelector 优选 REVIEWER → 同角色 API_KEY_LLM → PLANNER 兜底`（**不含任务级指定**）；若需让任务级 `agent_policy.reviewerAgentId` 对报告生效，可改传仅含 taskId 的探针（`SubTask` 仅 `@Data` 无 `@Builder`，须 `new SubTask(); probe.setTaskId(taskId);`——守卫 :41 通过，零实现改动）。
>
> **⚠️ 新增硬要求（自审自过守卫）**：回退链末档为 **PLANNER**，而报告 writer 恰由 Planner 担任 —— 若平台无可用 REVIEWER 角色的 `API_KEY_LLM` Agent，`reviewer` 将与 `writer` **同源**，形成「自审自过」（不是假设，是该回退链的下界行为）。**A2 必须加守卫**：`reviewer.getId() != writerAgentId`；不满足则**跳过审查**并落库 + 标记 `review_skipped`（或按 HUMAN_REVIEW 处理），**不得**静默判 pass。

- **DB / MQ**：无（纯进程内事件 + 字段更新）。
- **API**：`TaskFinalReportService.rework` 为内部接口；手动重生成端点语义不变。
- **红线合规**：复用 `PlatformAgentExecutionService` + `VerdictParser` + 一个 Prompt —— **未创建第二套 Review Runtime**。
- **风险**：① 审查成本（每次报告多 1 次 LLM 调用）；② **自审自过**：回退链末档为 PLANNER，与 writer 同源 → **必须**落地上方「自审自过守卫」（`reviewer != writer`，否则跳过审查）；根治靠 §9 的 SYNTHESIZER / reviewer 角色分离（A1）。

### 3B. 报告 Prompt 目标重写

**问题**：`[代码事实]` 铁律 2（每子任务独立成章、严禁合并）+ 铁律 3/4（契约事实 100% 保留、禁止「详见」）+ 强制全覆盖（章节数 = 总览表行数）三条叠加，**数学上等价于「把 N 份子任务文档换掉标题重新贴一遍」**；且末尾 HTML 自检清单**无代码读取**，纯耗输出 token。

**候选方案对比**

| 方案 | 做法 | 评价 |
|---|---|---|
| B1 微调措辞 | 保留结构，只删「禁止合并」 | 治标，主线/篇幅/读者仍缺失 |
| **B2 目标重写（推荐）** | 删除自相矛盾铁律，改为「归并 + 提炼」目标函数，补读者/篇幅/主线/去重/顺序/正例 | 直接改变模型的收益最优策略 |
| B3 换用外部成熟模板 | 引入第三方报告模板 | 与 CODE_STYLE §55（Prompt 即业务规则，须有测试）冲突，且不解决本项目输入结构 |

**推荐**：**B2**。

**新模板条款（替换 `task-final-report.md` 的「核心铁律」与「强制全覆盖」两节）**

1. **删除**铁律 2「每子任务独立成章、严禁合并」→ 改为 **「按主题归并」**：同一主题的多个子任务产出**必须合并**为一章；一个子任务可拆入多章。
2. **改写**铁律 3 → **「契约事实在被完整引用的章节保留完整」**；跨章重复的事实**只在一处完整出现**，其余写 `详见 §X`（**解除原「禁止详见」与「去重」的冲突**）。
3. **删除**「章节数 = 总览表行数」机械等式 → 改为 **「覆盖追溯表」**（子任务 → 落点章节映射，**允许多对一 / 一对多**），并保留末尾参考来源表（改为覆盖追溯表）。
4. **删除**末尾「强制自检清单（HTML 注释）」整节（无人读取）。
5. **新增**：
   - **读者与用途**：明确「本报告交付给谁、用于什么决策」；
   - **篇幅预算**：总字数上限 + 每章字数上限（如总 ≤ 8000 字、每章 ≤ 1200 字）；
   - **主线论点**：要求在「执行摘要」中列出 3–5 条贯穿全文的结论，并逐章展开（**主线的落点是「结论先行」而非章节罗列**）；
   - **章节顺序**：按**读者理解顺序**（是什么 → 怎么做 → 结果 → 风险 → 下一步），**不按拓扑序**（拓扑序仅用于输入收集）；
   - **1 个 few-shot 正例**：演示「两份子任务文档 → 一章有主线的叙述」；
   - **冲突与矛盾**：保留原第 4 步（差异与冲突澄清），升级为**必须输出**（无则显式写「无」）。
6. **保留**：信息密度优先、契约性事实保留、参考来源（→ 覆盖追溯表）。

- **Prompt 属业务行为变更**（CODE_STYLE §55）：必须配套渲染断言测试（`TaskFinalReportServiceTest` 断言「新模板含主线论点条款 / 无 HTML 自检注释」）。
- **风险**：解除「禁止合并」后，若模型过度合并会丢章 → 用「覆盖追溯表 + 3A 的核验」双保险（**这正是为何 B 与 A 必须同批**）。

### 3C. 大纲先行的轻量两段式（不引入完整 map-reduce）

**问题**：单次 stuff 的物理上限 = 一个交集子集；跨章去重与主线在「边读边写」时无法成立。

**候选方案对比**

| 方案 | 做法 | 评价 |
|---|---|---|
| C1 完整 map-reduce | Map 逐段提炼 → Reduce 归并 → Write 按章 N 次生成 | 收益最大，但引入「分章生成」= 新的编排形态，**触及目标架构 Orchestration Layer 定位**（§9 待决策），**本轮不做** |
| **C2 大纲先行两段式（推荐）** | Call-1「归并出纲」：输入 = 各子任务 `EXECUTION_RECORD`（SUMMARY / KEY_DECISIONS / DELIVERABLES）+ 标题 / 验收（**结构化，非全文**）→ 输出「覆盖追溯表 + 主线论点 + 章节顺序 + 矛盾清单」；Call-2「据纲成文」：输入 = 大纲 + 各章对应子任务正文（按章节预算分配） | 仍是 2 次调用，**不新增编排形态**；已能拿到「主线 + 去重」；是 C1 的自然前置 |
| C3 仅改 Prompt 顺序 | 不新增调用 | 无法真正去重（模型看不到全局） |

**推荐**：**C2**，并把 C1 登记为后置债务（§8）。

**改动文件清单**

| 文件 | 修改/新增 | 说明 |
|---|---|---|
| `helloai-core/.../task/service/impl/TaskFinalReportServiceImpl.java` | 修改 | 生成主链改为 2 次调用：`buildOutlinePrompt` → `executeSync` → `renderPrompt(task, sections, outline, limit)`；大纲调用失败时**降级为单次调用**（保留现状兜底） |
| `helloai-core/src/main/resources/prompts/task-final-report-outline.md` | 新增 | 归并出纲模板（复用 `ExecutionRecord` 字段，输入体积天然小） |
| `helloai-core/.../task/service/impl/TaskFinalReportServiceImpl.java` | 修改 | 正文顺序改为**按大纲章节顺序**而非 `SubTaskDependencyOrder`（拓扑序降级为仅输入收集顺序） |

- **token 预算**：Call-1 输入为结构化摘要（体量小）；Call-2 按「章 → 子任务」映射分片，**每章预算独立**，避免全量拼接。
- **风险**：多一次调用（成本 + 延迟）→ 与 §3A 的 review 调用合计 **报告链最多 3 次 LLM 调用**（出纲 + 成文 + 审查），须在 §7 成本项登记。
- **与阶段二关系**：阶段二的「附件优先 + `ExecutionRecord` 注入」正是 Call-1 的输入格式，**不返工**。

### 3D. 实施顺序建议（与文档 A→B→C 编号解耦）

文档按 A→B→C 编排；**实施顺序建议 B → C → A**：

1. **先 B**（改目标，成本最低、无新调用）——否则 A 的审查标准无据可依；
2. **再 C**（出纲 + 成文）——B 的条款需要大纲承载；
3. **最后 A**（核验闭环）——审查标准直接镜像 B 的条款，且能验证 B/C 的实效。

> A / C 均可在 B 落地后并行开发，A 的审查 Prompt 依赖 B 定稿。

---

## 4. 阶段四：联网搜索 Capability 化（防再出现域内接线）

### 4.1 第一步（事实调查，不臆造）

- 读 `agent/tool/impl/ToolRegistryImpl.java` 确认工具目录来源：与 `ToolCallbackToolExecutor` 同源自 spring-ai `ToolCallbackProvider.getToolCallbacks()`（`[代码事实]` `ToolDefinition` 注释已声明「单一事实源，零漂移」）。
- 确认平台内置工具注册形态（参照 `EchoMcpTool` 的 `ToolCallbackProvider` 自动注册先例）与 MCP 工具装配方式。
- 确认 `ClarifyWebSearchOrchestrator`（`planner/clarify/ClarifyWebSearchOrchestrator.java:33-50`，注入 `WebSearchService`）调用面，以及 `WebSearchService` 三实现的接口形态。

### 4.2 改动（原则已定，落点以调查事实为准）

1. 新建 `ToolDefinition("web_search", ...)` 注册进工具目录（`ToolRegistry` 同款路径）。
2. 新建技能包 `eng-web-research`（SKILL.md + SkillPackage 登记，`requiredTools=["web_search"]`），复用 G-004 已建的 `requiredTools→tools` 联动。
3. `web_search` 工具回调：**依赖方向合规**（禁止 `agent → planner`）——回调适配实现位于 planner 域（能力归 planner，符合 CODE_STYLE §5.3「planner 域职责：搜索」），经 spring-ai `ToolCallbackProvider` 注册为平台工具；若调查确认注册点必须在 agent 域，则 agent 域定义工具注册端口（Port 反转 §7.2）+ planner 域实现适配器。
4. `ClarifyWebSearchOrchestrator` 改为按工具名调 `ToolExecutor.execute("web_search", args)`，失败语义保持空列表降级；`webSearchEnabled` 会话开关保留（业务开关 ≠ 能力注册）。
5. 事件可观测：TOOL_CALL_STARTED / TOOL_CALL_COMPLETED 随既有 ToolExecutor 埋点进入 Event Stream。

### 4.3 验收与回滚

- `verify-tool-matrix.ps1` 工具矩阵含 `web_search`；澄清链 E2E 行为不回归（失败降级空列表语义不变）；TOOL_CALL 事件可查。
- 回滚：保留 `WebSearchService` 直调路径直至验证通过（迁移完成删旧路径，CODE_STYLE §50.7 **不平行常驻**）。

> **注意**：本阶段对「报告整合质量」**正交**（属架构归位，非质量杠杆），不计入 §0.4 档位提升。

---

## 5. 阶段五：防回归规则与文档回填

### 5.1 规则（CODE_STYLE 新增小节）

在 `doc/HelloAI_CODE_STYLE.md` 新增「平台能力接线规则」：

```text
LLM 可调用能力  → 技能包 + AgentTask.skills 声明
程序化能力      → ToolRegistry / 工具回调
禁止：域内 Service 直给 LLM / 编排器接线
例外（须显式声明）：子任务执行链走 ExecutionCommand.requiredSkills（阶段一 1.3 双轨边界）
```

并在 §64 Checklist 的「Agent / Planner」组下加勾选项：
- [ ] 新增 LLM 可调用能力已入技能包（未直连域内 Service）
- [ ] 新增同步 LLM 调用已声明 `AgentTask.skills` 或书面说明为何不需要

### 5.2 文档变更

| 文件 | 变更 |
|---|---|
| `doc/HelloAI_CODE_STYLE.md` | 新增「平台能力接线规则」小节 + §64 Checklist 勾选项 |
| `doc/HelloAI 实现差距表.md` | ① G-004 增量登记「契约层技能注入挂点」；② G-008 前置登记「`web_search` 能力目录化」；③ 报告链路缺口行更新；④ **新增 `G-016 报告整合质量（核验闭环 / 目标重写 / 大纲先行）`**；⑤ **§7.1 回流登记**：把 §8「不做清单」中的「分章 map-reduce」「SYNTHESIZER 角色」「联网搜索登记」逐条登记或显式 WONTFIX（**不得只留在本设计文档内**）；⑥ **G-016 附注**：登记「附件限额/族判定口径已单源（`shared/util/AttachmentContentPolicy`）；装配渲染层归并为**可选优化**（非漂移风险）」 |
| `doc/HelloAI 项目基线文档.md` | `AgentTask` 字段、契约层注入事实、报告链路读取口径更新（真实架构变化时） |
| `doc/log/*` | 实施报告按协作规约 §34 模板输出 |

### 5.3 §34 Implementation Report（每阶段结束必须输出）

按协作规约 §34 模板 16 节逐项填写，**区分 `PASS / FAIL / NOT RUN / BLOCKED / NOT APPLICABLE`**（§27），禁止以「代码看起来正确」代替验证。

---

## 6. 验证集合（协作规约 §25）

| 类别 | 项 |
|---|---|
| **Required** | `verify-dependency-direction.ps1`（无新增反向依赖；`review → shared` 不属反向）、`verify-agent-skill-capability.ps1`（技能解析/注入）、阶段四加 `verify-tool-matrix.ps1`；**新增 `AttachmentContentPolicyTest`（口径单源，§2.2-7 路径①）**；编译 `mvn -pl helloai-start -am compile` + core 模块单测全量 |
| **Regression** | `verify-c3-route.ps1`（执行契约未破坏）、`verify-c3-events.ps1`（事件流不变）、`verify-execution-dispatch-guard.ps1`（派发守卫不变）、`verify-artifact-content-review.ps1`（阶段二读取口径） |
| **Diagnosis** | 按需在 `.tmp/` 用诊断脚本，**不纳入正式验证**（§26） |
| **新增单测（阶段三）** | `TaskFinalReportServiceTest`：①新模板含主线/篇幅/覆盖追溯条款且**无** HTML 自检注释；②附件优先命中；③块级截断不切表格 / 代码块；④`REVIEW_FEEDBACK` 非空时注入；⑤`rework` 达上限后不再重写；⑥大纲调用失败降级为单次调用；⑦**阶段二回归**：`ReviewEvidenceAssembler` 改调 `AttachmentContentPolicy` 后既有核验侧用例全绿（行为不变） |

完成后输出：`git diff/status` 自检（§28）+ Implementation Report（§34）。

---

## 7. 风险清单与成本

1. **契约层注入与执行链自注入的边界（最核心）**：以「skills 非空才注入 + 执行链不填」双轨隔离；测试锚定「执行链 `AgentTask` 缺省 skills 时 platform 层零注入」。
2. **`@With` 与 `@Value/@Builder` 共存兼容性**：Lombok 标准支持，编译期验证；若异常改手写 `withSystemPrompt`。
3. **阶段四依赖 spring-ai `ToolCallback` 装配形态**：以调查事实为准，已设允许路径（planner 域适配 + 端口反转）。
4. **报告链路最多 3 次 LLM 调用**（出纲 + 成文 + 审查）：成本与延迟上升，须在 `AgentDispatchProperties` 提供开关（`auto-final-report-review-enabled`、`auto-final-report-outline-enabled`），默认下**可按环境关闭审查/出纲**以回退到 2 次或 1 次。
5. **解除「禁止合并」的丢章风险**：由「覆盖追溯表 + 3A 核验」双保险对冲；B 与 A 建议同批。
6. **`agent → task` 存量债禁增**（CODE_STYLE §6.1）：本方案新增的跨域调用全部为 `review → task`，无新增反向依赖。
7. **路径① 触碰 review 域（纯重构）**：`ReviewEvidenceAssembler` 由「自有私有常量」改为「调用 `AttachmentContentPolicy`」——风险是重构引入行为变更。缓解：**只替换常量/族判定引用，不改装配逻辑与阈值**，并以既有核验侧用例全绿作为「行为不变」证据（§6 新增单测⑦）；若回归失败则回退路径① 中该处改动（报告链仍可暂以本地常量落地，但须保留同源断言待修复）。

---

## 8. 不做清单（后置批次，控制范围）

> **须按差距表 §7.1 逐条登记或 WONTFIX**（见 §5.2-⑤），不得只留存于本设计文档。

| # | 不做项 | 理由 | 登记动作 |
|---|---|---|---|
| N1 | 分章 map-reduce（Map 提炼 → Reduce 归并 → Write 按章 N 次生成） | 引入「分章生成」= 新编排形态，触及 Orchestration Layer 定位（见 §9-D3） | 差距表登记 + 待决策项 |
| N2 | SYNTHESIZER 角色与 `reportAgentId` | 依赖目标架构 Role Layer 调整（§9） | 差距表登记 |
| N3 | 同步 LLM 调用迁入 AgentRuntime | `RuntimeTurnExecutor` subTaskId 硬校验放宽属 G-002 延续 | 差距表登记（G-002 关联） |
| N4 | 通用 Quality Gate（G-007）落地 | 阶段三 A 是其「报告场景实例」；通用化另行立项（§9-D4） | 差距表 G-007 关联 |
| N5 | Skill `Instructions` 字段（目标架构 §6，**反转既有决策**：`SkillPackage` javadoc 明确「instructions 由 markdown 文件承载」） | 承载「整合写作规范」技能包的前置 | 差距表 G-004 关联 |
| N6 | `final_report` 注册为 attachment 纳入交付 zip | 交付形态问题，非质量杠杆 | 差距表登记 |

---

## 9. 按 V2 架构能到达的水平 + 是否需进一步调整

### 9.1 结论

- **不动 V2 架构**：本方案（阶段一~五）落地后，报告质量约 **85**（从现状 ≈40 提升），**≈ 参考线的「及格偏上」，但仍非参考线本身**。
- **要达到参考线（≈90）**：**必须调整 V2 架构**。原因是——**V2 目标架构根本没有为「整合报告」预留角色或环节**：

### 9.2 架构缺口（`[代码事实]`）

| 缺口 | 事实 | 影响 |
|---|---|---|
| **D1「整合」无角色** | `AgentRole` 枚举仅 `PLANNER / EXECUTOR / REVIEWER / SYSTEM`（`helloai-common/.../AgentRole.java`）；目标架构 §3 Role Layer 仅列 Planner / Reviewer·Quality Gate / Governance | 报告由 `plannerPickerPort.pickForTask()` 选出的「最空闲 Planner」来写 —— 一个被调教成输出 JSON 拆解草案的模型去写长文，**本身就不对路** |
| **D2 执行链无 Synthesis 环节** | 目标架构 §9 最终执行链为 `… → Agent Event Stream → Reviewer / Quality Gate → PASS/REWORK/HUMAN_REVIEW/BLOCK` | 整合报告**不在目标执行链上**，是 Planner 的附属动作，无独立验收位 |
| **D3 分章生成为何不能默认做** | 目标架构 §11 非目标含「第二套 Scheduler / 第二套 Workflow Runtime」；Orchestration Layer 才管 Workflow / DAG | 「按章 N 次生成」本质是一个 Workflow → **归属未定**（是做成平台 Workflow，还是留作 Planner 内部实现？）→ 必须**显式决策**，不能默认 |
| **D4 Quality Gate 未落地** | 差距表 G-007「Quality Gate」仍为未做；目标架构 §9 承诺 PASS/REWORK/HUMAN_REVIEW/**BLOCK** | 阶段三 A 是「只针对报告」的临时闭环；若不与通用 Quality Gate 收敛，**等于为报告单开一套判定**（长期会与子任务审查链分叉） |
| **D5 Skill Instructions 未落地** | `agent/skill/SkillPackage.java` record 无 `instructions` 字段（目标架构 §6 承诺有）——**注意：这是「有意不做的既有决策」**：`SkillPackage` javadoc 明确「instructions 继续由 markdown 文件承载（fileName 指向 classpath skills/plugins/xxx.md），保持 Markdown 兼容、不重写现有 Skill 系统（§50.7 不建平行 Registry）」 | 「整合写作规范」无法以技能包声明式承载，只能塞 Prompt 模板；A4 因此是**反转既有决策**而非简单补字段 |

### 9.3 需要的 V2 架构调整（按必要性）

| # | 调整 | 落点文档 | 与前序阶段关系 |
|---|---|---|---|
| **A1** | **Role Layer 增 `SYNTHESIZER` 角色**（或明确 Planner 的整合职责边界 + 专用 Agent 策略），并按协作规约 §8「Role 与能力必须解耦」给出独立 Agent 选取口径 | `HelloAI 目标架构.md` §3 / §8；`AgentRole` 枚举 | 解除 D1；阶段三 A 的「writer / reviewer 必须分离」依赖此项 |
| **A2** | **§9 执行链增加 Synthesis 环节**（建议置于 `Agent Event Stream` 与 `Reviewer / Quality Gate` 之间），其出口复用 Quality Gate 决策 | `HelloAI 目标架构.md` §9 | 解除 D2；把报告从「Planner 附属动作」提升为**一等执行环节** |
| **A3** | **Quality Gate（G-007）落地并把阶段三 A 作为其首个消费者**（而非为报告单开判定） | 差距表 G-007；目标架构 §9 | 解除 D4；避免报告审查链与子任务审查链分叉 |
| **A4** | **Skill `Instructions` 字段落地（反转既有决策）**：`SkillPackage` javadoc 曾明确「instructions 由 markdown 文件承载，不重写 Skill 系统」——须按架构变更流程修订目标架构 §6 + 变更记录，并在差距表登记「反转动议」（防后人当 Bug 修），再新增 `eng-report-synthesis` 技能包承载整合写作规范 | `SkillPackage`；目标架构 §6；差距表 G-004 | 解除 D5；阶段一/三 的技能注入才有「报告专用规范」可注 |
| **A5** | **「分章生成」的架构归属决策**：登记为待决策项（平台 Workflow vs Planner 内部实现），**在决策前不实施 N1** | `HelloAI 重构实施计划.md` / 差距表 | 解除 D3 |

### 9.4 一句话

> **V2 架构当前把「整合报告」当作 Planner 的副产品，因此它的质量上限由「Planner 单次调用」决定（≈85 封顶）。**
> 参考线（WorkBuddy / DeepSeek）把「整合」当作**独立环节 + 独立角色 + 独立验收**，所以能到 ≈90。
> **因此答案是：需要调整 V2 架构 —— 增加 Synthesis 环节与 SYNTHESIZER 角色，并把 Quality Gate 泛化。** 否则阶段三 A/B/C 只能把报告从「拼接」提升到「像样的提炼」，无法形成参考线那种「迭代收敛 + 独立角色 + 独立验收」的整合能力。

---

## 10. 第三轮：机械前置门 + 预算 / 去重收敛（**本轮已落地**）

> **定位**：3A/3B/3C 落地后，用真实任务重跑做了效果复核（`[实测]`，非推演）。结论是**结构升级、事实层反退**——故本轮只治"事实层"。三项改动均**不动架构**（无新调用、无新角色、无 DB/MQ 变更）。

### 10.1 复核证据（同一任务、同一批交付物，新旧流水线对照）

`[实测]` task `2103026929396867074`（数据中台用户机构统一管理调研），timeline 2026-09-28：

| 时刻 | 事件 | 判定 |
|---|---|---|
| 12:31:14 | `task_final_report_outline_ready` | 3C 出纲确实执行（9 章 / 5 主线 / 4 冲突） |
| 12:32:09 | `task_final_report_generated` | 正文 **12,385 字符** |
| 12:32:29 | `task_final_report_review_passed` | 3A 审查执行且判 pass（reviewer ≠ writer，自审守卫生效） |

对照旧流水线 2026-09-24 产出的 **44,867 字符**：新流水线输出 **−72%**。

**四项残留问题**：

1. **核心矩阵外置且审查放过**：§4 原文「完整 11×8 矩阵取值**见分册第4章**」只给 4/11 行；§3（9×8）、§7（20×5）零矩阵行——三张矩阵 **260 格**是本次调研的全部决策依据，**全部外置**。这**直接违反** `task-final-report-review.md` 验收标准 #3（禁止"详见附件/详见子任务X"式空壳引用），**但 LLM 审查仍判 pass=true（score 4）** → **3A 会触发，但判定不可靠**。
2. **预算与"契约事实 100% 保留"数学冲突**：模板同时要求「正文 ≤8000 字」+「所有表格保留全部行列」；输入侧为 8 段 ×8000 字符。光抄事实即超预算、实际输出还超预算 55%（12,385 字）→ 模型只能二选一，"见分册"是**被模板逼出来的**。
3. **跨章重复未去**：8 条风险清单在 §1 与 §8 逐字重复；"MaxKey 为基线"6 次；覆盖追溯表与参考来源表是同一映射写两遍。
4. **章节顺序未重排**：§2→§9 与子任务 1→8 **一一对应**，仅 §1 前移——出纲规则 3 未生效。

### 10.2 本轮四项修法

| # | 修法 | 落点 | 性质 |
|---|---|---|---|
| **R1** | **确定性机械前置门**（新增 `review/support/FinalReportFidelityChecker`） | 审查前先跑，硬违规（空壳引用 / 围栏失衡）**直接机械驳回**，不给 LLM 放过的机会 | 治问题 1 |
| **R2** | **叙事预算制** | 模板 `task-final-report.md` 篇幅预算节改为「叙事 ≤3000 字 / 每章 ≤400 字，**契约性事实不计入**」 | 治问题 2 |
| **R3** | **跨章去重硬规则 + 结构合并** | 模板目标1 增"同一事实只在一处完整出现"；覆盖追溯表与参考来源表**合并为一张**（末尾只留一行归档说明） | 治问题 3 |
| **R4** | **排序自检（双层）** | 出纲模板规则3 增硬自检；正文模板第3步增硬自检；服务侧 `warnIfOutlineOrderUnchanged` 命中落 `task_final_report_outline_order_suspect` 告警 | 治问题 4 |

**R1 详细设计**（分级，避免误杀）：

| 级别 | 规则 | 处置 |
|---|---|---|
| HARD | `shell_reference`（见分册 / 详见子任务X / 详见前文 / 不再赘述 …） | **直接驳回**，走既有 `rework` 分支，落 `task_final_report_review_rejected`（`source=mechanical`），**不消耗审查 LLM 调用** |
| HARD | `code_fence_unbalanced`（` ``` ` 计数为奇数） | 同上 |
| SOFT | `cross_chapter_duplicate`（同一 ≥4 行块出现在 ≥2 章） | 落 `task_final_report_review_warned` 告警，**不驳回**，交 LLM 复核 |
| SOFT | `coverage_table_missing`（无覆盖追溯表） | 同上 |

> **刻意不判**：正文出现「附件」字样（二进制 / 大文件交付物引用属合法，仅表格清单外置到附件才算空壳引用）。
> **容错**：校验器自身异常一律吞掉返回空结果——**绝不阻断审查主链**（与报告"只增不减"同哲学）。

**驳回统一处置**：`rejectOrRework(event, reviewerAgentId, score, issues, source)` 抽取为共享方法，机械驳回与 LLM 驳回**同一路径**（含返工上限判断）；`source` 字段区分来源，机械路径 `reviewerAgentId` 为 null。

### 10.3 本轮修改文件

| 文件 | 类型 | 说明 |
|---|---|---|
| `helloai-core/.../review/support/FinalReportFidelityChecker.java` | 新增 | 纯静态校验器（4 条规则 + HARD/SOFT 分级 + 异常自吞） |
| `helloai-core/.../review/service/impl/FinalReportReviewListener.java` | 修改 | 接入机械前置门；抽取 `rejectOrRework`；软违规落 `review_warned` |
| `helloai-core/.../task/service/impl/TaskFinalReportServiceImpl.java` | 修改 | 新增 `warnIfOutlineOrderUnchanged`（出纲排序自检告警） |
| `helloai-core/src/main/resources/prompts/task-final-report.md` | 修改 | 叙事预算 / 跨章去重硬规则 / 第1步摘要口径 / 覆盖追溯表合并参考来源 / 第3步排序自检 |
| `helloai-core/src/main/resources/prompts/task-final-report-review.md` | 修改 | 标准 #2 增逐字重复判据；#3 增空壳引用判据 + 机械门说明；新增 #6 叙事预算自洽 |
| `helloai-core/src/main/resources/prompts/task-final-report-outline.md` | 修改 | 规则 3 增排序自检硬要求 |
| `helloai-core/src/test/.../FinalReportFidelityCheckerTest.java` | 新增 | 8 例（硬 / 软 / 零违规 / 不误判） |
| `helloai-core/src/test/.../FinalReportReviewListenerTest.java` | 修改 | 新增机械驳回 + 软违规告警 2 例 |
| `helloai-core/src/test/.../TaskFinalReportServiceTest.java` | 修改 | 模板断言更新为叙事预算口径 |

- **DB / MQ / API**：无（纯进程内校验 + Prompt 文案 + 两个新事件名 `task_final_report_review_warned` / `task_final_report_outline_order_suspect`）。
- **依赖方向**：校验器在 review 域，被同域监听器静态调用——**无新增跨域依赖**。
- **红线合规**：未新建 Runtime / Scheduler；机械门复用既有事件与 `rework` 链路。

### 10.4 本轮不解决（仍属 §9 架构层）

- **自审自过根治**（role 分离 A1）、**Synthesis 独立环节**（A2）、**Quality Gate 泛化**（A3）、**分章生成**（A5 / N1）——均**不动**。
- 机械门是"确定性兜底"，**不能替代** LLM 语义审查：本轮只收紧了"可机械判定"的一类违规（外置引用 / 结构损坏），验收标准第 1 / 2 / 4 / 5 条仍依赖 LLM。

---

## 11. 第四轮：报告生成轮次隔离（**本轮已落地**）

> **定位**：第三轮结束后，核实"手动点「重新生成」能否替代改造"时发现一处**反直觉退化**——它不影响单次生成质量，但会让"多试几次"这条最自然的用户路径**越试越差**。本轮只修这一处，**不动架构**。

### 11.1 问题：手动「重新生成」会消耗审查返工额度

`[代码事实]`「重新生成」入口链路：`FinalReportDialog.vue` → `POST /tasks/generateFinalReportByTaskId/{id}`（`TaskController:181`，权限 `task:report`）→ `TaskFinalReportServiceImpl.generate(taskId)` → `generateWithAttempt(taskId, null)`——**与自动首次生成同一条主链**，唯一区别是 `reviewFeedback = null`。

退化链路（修复前）：

1. `reportAttempts` 为**进程内 `ConcurrentHashMap<Long, AtomicInteger>`（`:89`），只增不减、无复位**；`nextAttempt` 即 `incrementAndGet`，且**位于 CAS 防重入之前**（原 `:139`）；
2. `attempt` 经 `TaskFinalReportGeneratedEvent` 传给审查方（`:215`）；
3. `FinalReportReviewListener` 以 `maxReview > 0 && attempt <= maxReview`（默认 `auto-final-report-max-review = 1`）决定是否自动返工（`:174`）。

**后果**：用户每手动点一次「重新生成」，`attempt` 就 +1。点到第 2 次之后，审查一旦驳回即直接落 `task_final_report_max_review_reached`——**自动返工静默失效**。用户视角是"多点几次反而不会自动改好了"。同一缺陷还包含：**被拒的点击（任务非 DONE / 无子任务产出 / CAS 正在生成中）也消耗额度**。

### 11.2 修法：轮次隔离（复位 + 写入时机）

| # | 改动 | 落点 |
|---|---|---|
| **T1** | 新增 `startNewCycle(taskId)`：计数**复位为 1**，使本轮获得与首次生成完全一致的审查返工额度；`nextAttempt` 保持累加，**仅供轮内返工**调用 | `TaskFinalReportServiceImpl` |
| **T2** | 轮次计算**从 CAS 之前移到 CAS 成功之后**：被拒的重复点击不消耗额度 | `TaskFinalReportServiceImpl.generateWithAttempt` |
| **T3** | 容量兜底 `evictIfOversized()`：计数表超 `MAX_TRACKED_TASKS = 10_000` 整体清零（兜底无界增长） | `TaskFinalReportServiceImpl` |

**判据（定稿）**：`attempt = (reviewFeedback == null) ? startNewCycle(taskId) : nextAttempt(taskId)`——
**新一轮生成复位、同轮返工累加**。语义等价性：自动首次生成与手动重新生成都走 `reviewFeedback == null` 分支 → **两者轮次起点、审查返工额度、失败兜底完全一致**，即"重新生成 = 首次生成的等价效果"。

**语义边界（写进 javadoc 防误改）**：`nextAttempt` **不得复位**（否则返工上限失效、可能无限重写）；清零兜底的代价是并发中的轮次可能多获得一次返工机会（**方向安全，不会少返工**）。

### 11.3 本轮修改文件

| 文件 | 类型 | 说明 |
|---|---|---|
| `helloai-core/.../task/service/impl/TaskFinalReportServiceImpl.java` | 修改 | 新增 `startNewCycle` / `evictIfOversized` + `MAX_TRACKED_TASKS`；轮次计算移至 CAS 之后；`reportAttempts` 字段 javadoc 写明轮次隔离语义 |
| `helloai-core/src/test/.../TaskFinalReportServiceTest.java` | 修改 | 新增 3 例轮次隔离不变量（连续重生恒为 1 / 轮内累加跨轮复位 1→2→1 / CAS 被拒不消耗额度） |

- **DB / MQ / API / Prompt**：**全部无**（纯进程内计数语义 + 方法位置调整）。
- **依赖方向**：无新增跨域调用。
- **红线合规**：未新建 Runtime / Scheduler；未改事件名与 payload 结构。
- **验证**：编译 `BUILD SUCCESS`；定向单测 **50 例 0 失败**（`.tmp/test-round4.log`）；core 全量单测 **1533 例 0 失败**（`.tmp/test-core-full-round4.log`，较上轮 1530 例 +3 为本轮新增）。

### 11.4 本轮明确不做（用户须知）

- **不改"重新生成会覆盖上一版报告"**：`task.final_report` 单列覆盖写，**无版本回滚**（如需保留历史版，属独立需求）。
- **不改审查同步执行**：`FinalReportReviewListener` 为 `@EventListener`（非 `@Async`），一次点击可串行触发"出纲 → 正文（最多 3 档降档）→ 机械门 → 审查 → 驳回返工（再出纲 + 正文）→ 再审查"，**最坏约 8 次 LLM 调用**，可能超过前端 240s（`TIMEOUT.longReport`）——表现为"前端报超时但后端仍会落库"。改异步属独立立项。
- **不改 `attempt` 的存储介质**：仍为进程内 Map（进程重启归零）。改持久化需 DB 变更，与"本批无 DB 变更"冲突，**不本批做**。

> **后续更新（2026-09-28，§12 已实施）**：上述三条"不做"均已随 §12 关闭——①改"重新生成会覆盖上一版"→ **12.1 单槽列 V95**（覆盖前现值落 prev，rollback 端点可恢复上一版）；②改"审查同步执行"→ **12.2 审查异步化**（REVIEWING 状态 + 专用池 + 三重陈旧守卫）；③改"attempt 存储介质"→ **12.3 轮次去状态化**（不持久化而是直接删除计数，轮次可推导）。

---

## 12. 遗留三项：方案 → 实施结果（**2026-09-28 已全部实施并验证**）

> **背景**：第四轮（§11）修掉"轮次额度被消耗"后刻意留了三项。用户已确认口径：**历史报告只保留上一版，不做全量版本历史**。本节先保留拍板方案，各子节末尾附**实施结果**（`[代码事实]`，记录实际落地与方案差异，以代码为准）——已按 12.3 → 12.1 → 12.2 顺序实施完毕，验证见 §12.4。

### 12.1 报告版本：只保留上一版（单槽撤回）

**现状** `[代码事实]`：`task.final_report TEXT`（`V32__task_final_report.sql`）+ `final_report_status VARCHAR(16) NOT NULL DEFAULT 'NONE'`（`V41__task_final_report_status.sql`，**无 CHECK 约束**）。`generateWithAttempt` 成功即覆盖写 `final_report`，**旧版不留存**。

**候选方案对比**

| 方案 | 落点 | 结论 |
|---|---|---|
| **A（推荐）单槽列** | Flyway `V95`：`task` 加 `final_report_prev TEXT` + `final_report_prev_time TIMESTAMPTZ` | 与"只保留上一版"**语义精确对齐**；`V32` 已有同款先例；无新表、无关联查询 |
| B 复用 `attachment` | — | `[代码事实]` 该表只有 `sub_task_id`（**无 task 级 owner**）→ 反而要改表，且引入"报告两个事实源" |
| C 复用 `task_iteration` | — | `[代码事实]` 该表是**按子任务一行**的快照，且 `backfillForTask` 每次生成**先 `deleteByTaskId` 再重建**——语义是"子任务迭代"、非报告版本，用即误导 |
| D 旧报告存 `task_timeline` payload | — | 污染审计流；单条 payload 可达数万字符 |

**推荐 A 的完整设计**

1. **迁移** `V95__task_final_report_prev.sql`：加 3 列 + COMMENT（照 `V32` 风格；**实际比方案多 1 列**，见实施结果）。**明确不建版本表**——"只保留上一版"是列级需求，上版本表即过度设计。
2. **写入点**（`TaskFinalReportServiceImpl` 报告写回处）：**覆盖前**把现值落 prev——`final_report` 非空才写（首次生成不产生 prev），同时写 `final_report_prev_time`；与主业合并进**同一条 `lambdaUpdate`**，不新增事务。
3. **撤回端点** `POST /tasks/rollbackFinalReportByTaskId/{id}`（权限 `task:report`）：语义为**交换**（current ↔ prev），使"恢复上一版"可反复切换；无 prev 时按既有语义化惯例返回 409。落事件 `task_final_report_rolled_back`（含两个版本的时间与长度）。
4. **UI** `FinalReportDialog.vue`：有 `prev` 时显示「恢复上一版」，撤回后刷新。
5. **验收**：连续 `generate` 两次 → prev == 第一版；撤回后 current/prev 互换；无 prev 撤回 409；单测覆盖。
6. **不做**：多版本历史、版本 diff、版本备注。

**实施结果（2026-09-28）** `[代码事实]`：

- 迁移实际落地 **3 列**：`final_report_prev TEXT` + `final_report_prev_agent_id BIGINT` + `final_report_prev_time TIMESTAMPTZ`（方案 2 列 + 生成者）。撤回语义是 current ↔ prev **整体交换**（含"上一版出自谁"），两列方案丢失生成者审计信息，实施时扩展为三列（交换语义完整性，评估时已向用户说明）。
- 写回点用 `.setSql("final_report_prev = CASE WHEN final_report IS NOT NULL AND final_report <> '' THEN final_report END, ...")` 在**同一条 lambdaUpdate** 内取"更新前现值"落槽——SQL 侧取旧值不受 Java 快照陈旧影响；首次生成 prev 保持 NULL。`generateWithAttempt` 报告写回处 `TaskFinalReportServiceImpl.java:236-259`。
- 端点实落 `TaskController.rollbackFinalReport`：`POST /api/tasks/rollbackFinalReportByTaskId/{id}`（权限 `task:report`）；CAS 条件 `ne(GENERATING)` 拦在途生成；无 prev 抛 `BizException(409, "没有可恢复的上一版整合报告")`；互换三列 + 落事件 `task_final_report_rolled_back`（actor=prevAgentId，payload 含 restoredGeneratedAt 等）。
- UI：`FinalReportDialog.vue`「恢复上一版」按钮（`TaskFinalReportResponse` 新增 `hasPrev` / `prevGeneratedAt`），互换后重拉并回传状态。
- 测试：`TaskFinalReportServiceTest` 新增 5 例（落槽 setSql 断言 / 互换 CAS / 无 prev 409 / 生成中 409 / 任务缺失 404），**36 例全绿**。

### 12.2 审查异步化（消除「前端超时但后端已落库」）

**现状** `[代码事实]`：最终报告审查是 `@EventListener`（**同步**，`FinalReportReviewListener:80`），与生成**同线程** → 一次点击串行「出纲 → 正文（≤3 档降档）→ 机械门 → 审查 → 驳回返工（再出纲 + 正文）→ 再审查」，最坏约 8 次 LLM 调用，可超前端 240s（`TIMEOUT.longReport`，`api/request.ts:37`）。

**对照**：子任务审查已是 `@Async + @TransactionalEventListener(AFTER_COMMIT)` + 专用池（`SubTaskReviewServiceImpl:160-162` + `ReviewDualExecutorConfig`）——**最终报告审查是全平台唯一同步的审查链**，属"抄作业没抄全"。

| # | 改动 | 落点 |
|---|---|---|
| 1 | 监听改 `@Async("reportReviewExecutor")` + `@EventListener` | `FinalReportReviewListener` |
| 2 | 新增专用池 core 1 / max 2 / queue 50 + **AbortPolicy**（提交处捕获 `RejectedExecutionException` 落 `review_skipped(reason=executor_saturated)`） | 新建 `ReportReviewExecutorConfig`（helloai-start，照 `DoorbellExecutorConfig` 风格） |
| 3 | 新增状态 `REVIEWING`：生成写回后置 `REVIEWING`，审查收敛（pass / max_review_reached / skipped / unparseable）后置 `DONE` | `FinalReportStatus` 枚举——**`final_report_status` 为 `VARCHAR(16)` 且无 CHECK → 零迁移** |
| 4 | UI 轮询条件由 `GENERATING` 扩为 `GENERATING \| REVIEWING` | `FinalReportDialog.vue:92/102`——**轮询机制已存在**（5s 一次），仅扩状态集合 |
| 5 | **陈旧审查防覆盖（异步化必配）** | 事件携带 `reportTime`；异步审查前与 `rework` 前**双重校验** `task.final_report_time` 仍等于事件值，不等则丢弃并落 `task_final_report_rework_discarded_stale` |

> **第 5 项的必要性** `[分析判断]`：异步化后"审查在途"与"用户再次点重新生成"可并发。若不加校验，一次针对**旧版**报告的驳回返工会覆盖用户刚生成的新版（last-write-wins）——**这是异步化引入的新缺陷，守卫必须同批落地，不是可选项**。
>
> **为什么不用 `AFTER_COMMIT`** `[代码事实]`：`TaskFinalReportServiceImpl` 全类**无 `@Transactional`**（仅 `@Service/@RequiredArgsConstructor`），`@TransactionalEventListener` 不会触发；故沿用 `@Async @EventListener`，与 `TaskAutoCompletedEvent` 同款。若后续给生成主链加事务，再统一迁 `AFTER_COMMIT`。

**收益**：前端请求时长回落到"出纲 + 正文"（正常 10~40s），审查与返工在专用池内收敛，UI 靠既有 5s 轮询收口。**代价**：新增 `REVIEWING` 语义 + 陈旧守卫。
**不做**：把返工扩成独立 Workflow（触碰 §9 非目标"不建第二套 Workflow Runtime"）。

**实施结果（2026-09-28）** `[代码事实]`（表 1-5 项全部落地，**两处与方案不同**）：

1. **入口改为同步 `@EventListener` + 手动 `reportReviewExecutor.execute(() -> reviewQuietly(event))`**（放弃方案 1 的 `@Async`）：`@Async` 的拒绝异常走 `AsyncUncaughtExceptionHandler`、**不回发布线程**，无法可靠落 `review_skipped(executor_saturated)`；手动 `execute` + try-catch `RejectedExecutionException` 才能保证饱和时发布线程内兜底落库（`FinalReportReviewListener.onFinalReportGenerated`）。
2. **陈旧守卫为三重而非方案 5 的两处**：① 审查前 `isStale` 校验，落 `task_final_report_review_discarded_stale` 不选人；② `rework` 前二次 `getById` + 校验，落 `task_final_report_rework_discarded_stale`（不重写不收敛）；③ **收敛置 `DONE` 用条件写回** `.eq(Task::getFinalReportTime, event.reportTime)`——update 影响 0 行即新链已接管，静默放弃不覆盖。Guard③ 防"审查在途 + 用户重新生成"并发下旧链收敛把新链覆盖回 DONE。
3. 事件 `TaskFinalReportGeneratedEvent` 新增 `reportTime` 字段，**`truncatedTo(MICROS)`** 截断——TIMESTAMPTZ 只存微秒，不截断则 DB 读回不相等导致 stale 误判。
4. 收敛出口**全覆盖 6 处**（方案 4 处 + 2）：pass / unparseable / `result==null`（LLM failed）/ max_review / `skipped`（executor_saturated / no_reviewer_available / self_review_guard）/ 开关关闭直接 DONE——任一出口收敛 `DONE`，否则 UI 永久轮询。
5. 开关：`AgentDispatchProperties.autoFinalReportReviewEnabled`（默认 `true`）决定生成写回置 `REVIEWING` 还是直接 `DONE`；`FinalReportStatus` 枚举加 `REVIEWING`（VARCHAR(16) 无 CHECK，零迁移）。
6. 专用池 `ReportReviewExecutorConfig`（helloai-start/config）：core 1 / max 2 / queue 50 / **AbortPolicy**——异于 `ReviewDualExecutorConfig` 的 CallerRunsPolicy：报告审查的入口已占发布线程（EventListener），CallerRuns 会串死发布线程，AbortPolicy + 手动捕获才正确。
7. UI：`FinalReportDialog.vue` 轮询条件/按钮文案/`status-change` 扩 `REVIEWING`；`TaskList.vue` 状态列 tag + 报告按钮扩 `REVIEWING`（`reportInFlight` helper）；`enums.ts` 全局 `FinalReportStatus` 补 `'REVIEWING'`（真实 TS 错误由 type-check 兜出）。
8. 测试：`FinalReportReviewListenerTest` 新增 7 例（专用池提交-执行分离 / 池饱和兜底收敛 / 审查前 stale 丢弃 / rework 前 stale 丢弃 / 收敛 reportTime 守卫 / 收敛竞争放弃（不覆盖新链）/ unparseable 收敛），**19 例全绿**。

### 12.3 轮次去状态化（`attempt` 不必持久化，可**直接消灭**）

**先纠正原命题** `[分析判断]`：「把进程内计数改成 DB 列」是**次优解**——轮次是**可推导的**，根本不需要状态。

| | 现状（§11 之后） | 去状态化（推荐） |
|---|---|---|
| 新一轮生成起点 | `startNewCycle` 复位为 1 | **恒为 1**（`generate` 无状态） |
| 轮内返工 | `nextAttempt` 自增 | 监听器把 `event.getAttempt() + 1` **显式传入** `rework(taskId, feedback, nextAttempt)` |
| 计数器 | 进程内 Map | **删除** |
| 进程重启 | 归零 | 无状态可归零 |
| 内存泄漏 | 靠 `MAX_TRACKED_TASKS` 兜底 | 不存在 |

**判据**：唯一需要"记住"轮次的场景是"同轮返工"，而该场景的调用方（`FinalReportReviewListener:174-178`）**手里就握着 `event.getAttempt()`**——把 `+1` 随调用传下去即可，链路自洽、无需任何存储。

**落点**：`TaskFinalReportService` 增 `rework(Long taskId, String feedback, int attempt)`（保留 2 参重载默认 `1`，供人工重写入口）；删除 `reportAttempts` / `startNewCycle` / `nextAttempt` / `evictIfOversized` / `MAX_TRACKED_TASKS`。
**备选（仅在有展示/审计需求时）**：`V95` 加 `final_report_review_round INT`，与 `sub_task.rework_count` 同构。**纯正确性不需要它**。

**实施结果（2026-09-28）** `[代码事实]`：按推荐落点完成——3 参 `rework(taskId, feedback, attempt)` + 2 参重载默认 `1`；监听器 `rejectOrRework` 内 `rework(taskId, issues, event.getAttempt() + 1)` 显式传轮次（`FinalReportReviewListener.java:254-255`）；删除 `reportAttempts` / `startNewCycle` / `nextAttempt` / `evictIfOversized` / `MAX_TRACKED_TASKS`；**无 DB 变更**。测试：`TaskFinalReportServiceTest` 轮次语义用例适配为"恒为 1 + 轮内累加"断言，全绿。

### 12.4 依赖关系与排期建议

```
12.3 轮次去状态化（最小、无 DB、删代码）
     └─ 建议先做：12.2 的陈旧守卫应建在「无状态轮次」之上，否则两改动互相干扰
12.1 只保留上一版（1 个 V95 迁移）── 与 12.2 / 12.3 正交，落点不重叠，可并行
12.2 审查异步化（最复杂：状态 + 池 + 陈旧守卫 + UI）
```

| 优先级 | 项 | 理由 |
|---|---|---|
| **P1** | 12.3 | 成本最低（净删代码）、纯正确性、**是 12.2 的前置** |
| **P1** | 12.1 | 用户明确需求；1 个迁移 + 3 处改动，收益直观（可回退） |
| **P2** | 12.2 | 收益是"体验/超时"而非"正确性"，但改动面最大；建议单独一批 |

**建议切批**：**批次甲 = 12.3 + 12.1**（一次迁移、一次验证，可立刻用上"恢复上一版"）；**批次乙 = 12.2**（含 `REVIEWING` 状态、专用池、陈旧守卫与 UI 扩状态）。
**红线自查**：三项均不新建 Runtime / Scheduler / Workflow；12.2 复用既有 `@EventListener` 基础设施与既有轮询；12.1 迁移遵守 Flyway 版本化（`V95`）。

**实施结果（2026-09-28）**：按建议顺序 **12.3 → 12.1 → 12.2**（12.2 单独一批）全部落地。全量验证：

- `mvn -pl helloai-start -am compile`：**BUILD SUCCESS**（7 模块，`ReportReviewExecutorConfig` 接入）；
- core 全量单测 **1546 例 0 失败**（含本批新增 12 例；`.tmp/core-full-test-r12.log`）；
- 前端 `npm run type-check`：通过（两次）；
- 静态 verify：`verify-dependency-direction.ps1` **ALL PASSED**（10/10 + MapperScan）；`verify-code-style-p1-ui-sync.ps1` Channel B **0 违规**（Channel A 1 项为**既有脚本盲区**——`streamSendById` 的 `value= /streamSendById/{id}`, produces=...` 注解形式不被正则 `\(\s*"` 捕获，后端端点真实存在，非本批引入）；`verify-code-style-p1-paths.ps1` **TaskController 0 违规**（22 处 FAIL 全为历史 Controller 既有项：mq-recovery / permissions / roles / users / teams / workflow，非本批引入）；
- 迁移：`V95__task_final_report_prev.sql`（+ `V94` 为外部 Agent v3 批次既有，未动）。

**红线复查**：未新建 Runtime / Scheduler / Workflow；无第二套状态机（REVIEWING 是既有状态机新增合法状态，出口全收敛）；Event 仍为单一事实流（沿用 `task_timeline` 既有事件名）；依赖方向守护通过。

---

### 12.5 报告审查链三级容错补齐 + 事务边界 + 状态机收口（2026-09-28 二次改造）

> **触发**：线上任务「AI短剧快速上手图文教程（抖音竖屏）」重新生成后**永久停在「报告审查中」**。
> 排查结论：**报告链后端本身跑通**（DB 实证 `final_report_status=DONE`，19:11:05 由 `max_review_reached` 收敛），
> **直接原因是前端**（§12.5.4）；但排查同时暴露了一个**后端结构性缺口**——§12.2 只给报告审查链补了
> 「异步 + 陈旧守卫」，**没有像子任务核验链那样补齐三级容错**，且「写回」与「审查触发」是双写。

#### 12.5.1 缺口：双写无原子性 + 只有 L1

**改造前事实** `[代码事实]`：

- `TaskFinalReportServiceImpl.generateWithAttempt` 写回用 `taskService.lambdaUpdate()...set(final_report_status, REVIEWING)`，
  **紧接着**另行 `applicationEventPublisher.publishEvent(new TaskFinalReportGeneratedEvent(...))`——
  两步之间**无任何原子性**（该类全程无 `@Transactional`）。进程在两步之间挂掉即「报告已落库、审查永不触发」的永久 `REVIEWING`。
- `FinalReportReviewListener` 入口是**普通 `@EventListener`**（非 `AFTER_COMMIT`）：一旦给生成主链补上事务，它会在**提交前**触发并命中陈旧守卫被丢弃（潜在的相位 bug）。
- `grep "TaskFinalReportGeneratedEvent|task_final_report"` 在 `helloai-job` / `helloai-mq` / `review/mqconsumer` 下**为空**：
  报告审查链**只有 L1（进程内事件）**，无 L2 Outbox/MQ 补投、无 L3 兜底巡检。对比子任务核验链的「三级容错」，报告链是「一级容错」。
- 日志实证：`07-31 20:49:41` 存在一条 `task_final_report_generated`，**无配对的审查事件**——该链真的丢过一次。
- `review_discarded_stale` / `rework_discarded_stale` 两条分支**明确不收敛**（旧实现注释自认「需人工介入」），进一步放大卡死面。

**是不是「写成了一个长事务」** `[分析判断]`：**不是**。改造前后 `TaskFinalReportServiceImpl` 全类都**没有 `@Transactional`**，
这是刻意的——主链含 1~3 次 LLM 调用（出纲 + 正文 + 降档重试），进事务就会长事务持锁。
真正的缺陷不是「事务过长」，而是**缺三件事**：① 缺事务边界（一次**短**事务包住写回+触发，而非包住 LLM）；② 缺持久化触发（L1 进程内事件重启即丢）；③ 缺补偿（无 L2 补投、无 L3 巡检）。

#### 12.5.2 修法 A + B + C（与子任务核验链同构）

| 层 | 改动 | 落点 |
|---|---|---|
| **B-①** 事务边界 | 新增 `FinalReportPersistService.persistAndRequestReview(...)`，`@Transactional(rollbackFor = Exception.class)` 内一次性完成：报告写回（含 §12.1 prev 槽）→ L2 Outbox 落库 → L1 事件发布 | 新建 `core/task/service/impl/FinalReportPersistService` |
| **B-②** L2 持久化 | `AgentOutboxService.createReportReviewEvent(...)` 落 `agent_outbox_event`；由既有 `AgentEventCompensationTask`（`@Scheduled` 15s）补投 | `core/agent/service` + 既有 job |
| **B-③** L2 队列/消费者 | `RabbitMQConfig` 加 `helloai.report-review.queue`（DLX + `x-max-length` + `reject-publish`）；新建 `MqFinalReportReviewConsumer extends AbstractIdempotentConsumer` | `helloai-mq` + `core/review/mqconsumer` |
| **B-④** L1 相位 | 监听改 `@TransactionalEventListener(phase = AFTER_COMMIT)`；收敛体抽成 `FinalReportReviewService.onFinalReportGenerated`（L1/L2/L3 三路共用执行体） | `core/review/service(+impl)` |
| **A** L3 兜底 | 新建 `FinalReportReviewOrphanTask`：`@Scheduled(fixedDelay)` + `@SchedulerLock` 扫 `final_report_status=REVIEWING AND final_report_time < now() - threshold`，**收敛 DONE**（不重投审查） | `helloai-job/task` |
| **C** 状态机 | 新建 `FinalReportStateMachine`（显式合法迁移表 + `assertTransit`）；状态写口收口到 `TaskService.transitFinalReportStatus` / `convergeFinalReportToDone` | `core/task/statemachine` + `TaskService` |

**为什么不允许长事务** `[分析判断]`：本项目既有范式（子任务核验链 §12.2 三级容错 + Outbox）就是
「**状态机定当前态 + 事件记录发生过什么 + Outbox/MQ 解耦最终一致**」。本批把这套范式**抄全**到报告链，
而不是把 LLM 调用与状态写回塞进一个事务——后者才是真正的架构倒退。

**A 为什么收敛 DONE 而不是重投审查** `[分析判断]`：超过阈值仍停在 `REVIEWING`，说明触发链已丢失。
此时报告正文**已落库且已交付**（`final_report` 列有值，只是没走完审查）。重投审查会**无界消耗 LLM**
（且若触发链本身有 bug，重投仍会再卡住）；收敛 `DONE` 让 UI 立刻可用，用户若要补审查可点「重新生成」。

**三路触发的幂等**（L1/L2/L3 会各触发一次审查，必须幂等）：
- **Redis 防双审锁** `final_report_review:{taskId}`（`tryLock(0, 600s)`，抢不到直接跳过）；
- **状态守卫**：审查体入口 `if (task.getFinalReportStatus() != REVIEWING) return;`；
- **陈旧守卫**（沿用 §12.2）：`reportTime` 锚点 + `isEqual` 比较（**非 `.equals`**——后者比 offset，跨时区必假，是已修的线上 bug）。

**配置**：`finalReportReviewOrphanThresholdSeconds`（默认 300s；正常审查 LLM 判定约 30s）、
`finalReportReviewOrphanBatchSize`（默认 20）、`helloai.mq.report-review.consumer-enabled`（默认 true）。

#### 12.5.3 实施结果（2026-09-28）`[代码事实]`

- **新增生产类 7 个**：`FinalReportStateMachine`、`FinalReportReviewConst`、`FinalReportPersistService`、
  `FinalReportReviewService`(+`Impl`)、`MqFinalReportReviewConsumer`、`FinalReportReviewOrphanTask`；
  **删除** `FinalReportReviewListener`（职责由 `FinalReportReviewServiceImpl` 承接）。
- **修改**：`TaskFinalReportServiceImpl`（写回改走事务边界 Bean；`markFailed` 改走状态机迁移；
  **`rollback` 的 `assertTransit` 从 CAS 之前移到 CAS 之后**——否则任务快照为 `GENERATING` 时会抛
  `IllegalStateException` 而非可读的 409，属本次自查发现并修正）、`TaskService`(+`Impl`)（3 个状态机写口）、
  `TaskMapper`（孤儿巡检轻量列查询）、`AgentOutboxService`(+`Impl`)、`RabbitMQConfig`、`AgentDispatchProperties`、`application.yml`。
- **状态机迁移表**（`from → 允许的 to`）：`NONE→GENERATING`；`GENERATING→REVIEWING|DONE|FAILED`；
  `REVIEWING→GENERATING|DONE`；`DONE→GENERATING|DONE`；`FAILED→GENERATING`。
  （`REVIEWING→GENERATING` 合法 = 审查驳回触发返工重写；`DONE→DONE` 合法 = §12.1 回滚与审查收敛的幂等写回。）
- **测试**：新增 `FinalReportStateMachineTest`(5) / `FinalReportPersistServiceTest`(3) / `FinalReportReviewOrphanTaskTest`(6)；
  `FinalReportReviewListenerTest` 改名 `FinalReportReviewServiceImplTest`(22，新增「状态守卫幂等跳过」「防双审锁抢占失败」2 例)；
  `TaskFinalReportServiceTest`(36) 断言由「`taskUpdateChain.set/update`」迁移为「`persistAndRequestReview` 入参」
  （写回已不在本类，`taskUpdateChain` 桩随之删除）。
- **全量验证**：`mvn -o -pl helloai-core,helloai-job -am test -DskipTests=false` → **1686 例，0 失败 0 错误**
  （注意 `-DskipTests=false` 必需：根 pom `properties` 里 `<skipTests>true</skipTests>` 是打包默认）；
  前端 `npm run type-check` 见 §12.5.4。
- **红线复查**：未新建 Runtime / Scheduler / Workflow（L3 复用既有 `@Scheduled` + ShedLock 范式）；
  报告状态机是既有 `FinalReportStatus` 的**显式化**，不是第二套状态机；Event 仍单一事实流（沿用 `task_timeline`）。

#### 12.5.4 遗留：前端误报「报告审查中」（本批一并修）`[代码事实]`

**根因**：`helloai-ui/src/views/task/components/FinalReportDialog.vue` 的 `startPolling()` 每轮只更新弹窗本地
`report.value`，**收敛时直接 `stopPolling()` 却不 `emit('status-change')`**；而父组件 `TaskList.vue` 的
`onReportStatusChange` 只 patch 列表行的 `finalReportStatus`，且列表**无自动刷新**——于是列表行永久停在
被 patch 的 `REVIEWING`（状态列显示「报告审查中」、「审查中」按钮禁用），**而此时库里其实早已是 `DONE`**。
用户的观感就是「卡在报告审核中」。

**修法（本批已改）**：
1. `startPolling()` 的轮询回调**每轮 `emit('status-change', r.status)`**（收敛轮同样广播）；
2. 弹窗**关闭时补一次广播**（`watch(visible)` 的 `!v` 分支 `stopPolling()` 后 emit 最后已知状态）。

**残留（未修，独立小需求）**：用户**在 `REVIEWING` 期间关闭弹窗**后随即离开页面，则既无轮询也无列表刷新——
列表行仍会显示旧状态，需手动刷新或重新进页面才会更正。彻底解法是列表订阅/定时刷新该行，属独立 UI 需求。

---

## 附：本阶段产出边界与回填建议

- **本阶段仅新增本文档**：`doc/design/HelloAI_契约层能力注入与最终报告整合改造方案.md`。**未**修改任何生产/测试代码、Migration、配置、手册（符合任务纪律）。
- **回填建议（实施后）**：
  - `doc/HelloAI 实现差距表.md`：新增 `G-016 报告整合质量`；§7.1 回流登记 N1~N6（§8）。
  - `doc/HelloAI 目标架构.md`：若采纳 §9.3 A1/A2/A3，需在 §3 Role Layer、§9 执行链、§12 边界补充中正式登记（并同步 `log/HelloAI 架构变更记录.md`）。
  - `doc/HelloAI 项目基线文档.md`：若 `AgentTask` 增 `skills` 字段、报告链改为 2~3 次调用，需更新「当前架构事实」。
  - `doc/HelloAI_CODE_STYLE.md`：新增「平台能力接线规则」小节 + §64 勾选项（§5.1）。
- **证据口径**：`[代码事实]` 均已给出 `文件:行`；`[分析判断]`（成本、档位、风险）未运行验证，实施时须以实测为准（协作规约 §27）。
