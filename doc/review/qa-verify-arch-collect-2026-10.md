# 独立验证报告 —— 架构收口改动（7 项声明）

- 验证人：秦戈（QA，职责：证伪）
- 验证日期：2026-10-01
- 验证方式：**只读静态复核**（未执行任何 `mvn` / 构建命令；未做任何 git 写操作；未修改任何既有文件）
- 项目：`E:/yhzx/1027/helloai`（Java 17 + Spring Boot 多模块）
- 依赖链：`planner > review > task > agent > system > shared`；`shared` 须为叶子域
- 被验证改动实施人：白客

---

## 【测试范围】

- 被验证对象：白客提交的 7 项架构收口声明的**静态事实一致性**
  （包位置、FQN 引用残留、调用点数量、语义等价性、组件扫描配置、冻结基线、git 状态）
- 测试账号 / 运行环境：不适用（本轮无运行时行为，无接口、无 token；功能/边界/安全三层实测**未执行**）
- 复算命令均在本机对工作区实时执行，输出见各条证据块

> 说明：本轮为**静态复核**。凡必须运行构建/接口才能判定的项，均显式标注「静态不可判，需构建/运行阶段验证」，**不臆断为通过**。

---

## 【执行摘要】

| 层面 | 核对项数 | 证实 | 证伪/部分 |
|---|---|---|---|
| 声明 1（测试包漂移） | 4 | 4 | 0 |
| 声明 2（SubTaskDependencyOrder 下移） | 5 | 5 | 0 |
| 声明 3（SubTaskOutputExtractor 去实体化，重点） | 5 | 5 | 0 |
| 声明 4（doorbell 整包搬迁） | 4 | 3 | 1（P3） |
| 声明 5（ExecutionCommandCreatedEvent 搬迁） | 3 | 3 | 0（1 处笔误 P3） |
| 声明 6（门禁与基线） | 3 | 3 | 0 |
| 声明 7（无 git 提交） | 3 | 3 | 0（原 P1-1/P2-1 经主理人核实**非缺陷**，已更正） |
| 功能路径实测 | — | — | 静态不可判（需构建） |
| 边界实测 | — | — | 静态不可判（需构建） |
| 安全实测 | — | — | 静态不可判（需构建） |

**缺陷合计：P0 ×0，P1 ×0，P2 ×0，P3 ×4（+ 提交卫生提示 ×1，非阻塞）。**

> **更正记录（2026-10-01，主理人核实后）**：
> - 原 **P1-1**（暂存区 2 笔删除）系上一批次（Q1-①）**有意执行的 `git rm`**（两调试笔记出库，内容已备份至被忽略的 `.workbuddy/notes/`），一直处于已暂存未提交状态。**非误操作、非交付阻塞**，降级为「**提交卫生提示**」（保留风险说明）。
> - 原 **P2-1**（HEAD 与 e2c7e8c 不符）系主理人简报引用了过期审计数字；HEAD `2bb78b8`（"文档更新"，作者 `shihang`，2026-09-30 23:28）是**正确的、早于本会话**的提交，**无任何成员提交过代码**。**非缺陷**，改记为「**回归基线以 `2bb78b8` 为准**」。

---

## 【缺陷清单】

### 提交卫生提示-1（原 P1-1，经主理人核实**非缺陷**，保留风险说明）

- **描述**：`git diff --cached --stat` **非空**，存在 2 个已暂存（staged）的文件删除：`debug-blocked-redispatch-stuck.md`、`debug-redispatch-stuck-blocked.md`。
- **定性（更正）**：经主理人核实，这是**上一批次（Q1-①）有意执行的 `git rm`**（两个调试笔记出库，内容已备份到被忽略的 `.workbuddy/notes/`），一直处于已暂存未提交状态。**属预期操作，不构成交付阻塞**。
- **复现步骤**：
  1. `cd E:/yhzx/1027/helloai`
  2. `git diff --cached --stat`
  3. `git status --short -- debug-blocked-redispatch-stuck.md debug-redispatch-stuck-blocked.md | cat -A`
- **实际结果**：
  ```text
  $ git diff --cached --stat
   debug-blocked-redispatch-stuck.md      | 39 ---------------------------------------
   debug-redispatch-stuck-blocked.md      | 30 ------------------------------
   2 files changed, 69 deletions(-)

  $ git status --short | cat -A
  D  debug-blocked-redispatch-stuck.md$
  D  debug-redispatch-stuck-blocked.md$
  ```
  首列 `D`（后接空格）＝ **已暂存** 删除。
- **保留的风险说明（提交卫生）**：在本次架构收口统一提交时，若使用 `git commit -a` / `git add -A`，这两笔删除会被**一并带入**；请提交者知悉其来源为 Q1-① 的预期清理，避免误判。**建议在最终提交前 `git status` 复核一次**。
- **证据**：见上代码块。
- **定位**：git 暂存区（非代码行）。

### 基线说明（原 P2-1，经主理人核实**非缺陷**）

- **描述**：原简报期望 HEAD 为 `e2c7e8c`；实测 HEAD 为 `2bb78b8`（"文档更新"，作者 `shihang`，2026-09-30 23:28）。
- **定性（更正）**：`2bb78b8` 是**正确的、早于本会话的提交**；`e2c7e8c` 来自上一份审计报告，是**过期数字**。**无任何成员提交过代码**。
- **处置**：本报告及后续回归**统一以 `2bb78b8` 为回归基线**。声明 7 的核心（7 项改动未 `add`、未提交）**成立**。
- **证据**：`git rev-parse HEAD` → `2bb78b85dc84e1bef4409892392725e3ae5a0ed0`；`git log --oneline -5` → `2bb78b8 文档更新` / `e2c7e8c ...`。
- **补充（对声明 7 有利的部分）**：7 项改动对应的文件在 `git status` 中均表现为 ` M` / ` D` / `??`（工作区已改未暂存 / 未跟踪），**未见 `M ` / `A ` 等已暂存标记** —— 即架构改动本身确实未被 `git add`、未被提交。

### P3-1　旧包 FQN 在「全仓」范围并非为空（声明 4b）

- **描述**：`core.shared.doorbell` FQN 仍存在于非源码产物中：`.tmp/*.log`（历史构建日志堆栈）、`doc/archive/legacy/HelloAI_迭代执行记录_V1.md:2609`（归档迭代记录）、`.qoder/repowiki/**`（自动生成的仓库 wiki）。
- **证据**：
  ```text
  $ grep -rn "core\.shared\.doorbell" . --exclude-dir=target --exclude-dir=.git --exclude-dir=node_modules
  ./.tmp/c3-step0-full.log:2206: at com.helloai.core.shared.doorbell.DoorbellKeepaliveTask...
  ./doc/archive/legacy/HelloAI_迭代执行记录_V1.md:2609: ... package 声明 com.helloai.core.shared.doorbell ...
  ./.qoder/repowiki/zh/content/API 参考文档/Webhook 与事件接口.md:6: [DoorbellService.java](.../core/shared/doorbell/DoorbellService.java)
  ...（.tmp 其余 7 个日志同理）

  $ grep -rn "core\.shared\.doorbell" helloai-core/src helloai-api/src helloai-start/src helloai-common/src helloai-job/src
  [exit 1]      # 真源码 0 命中
  ```
- **影响范围**：无构建/扫描影响（均非源码、非配置、非 Spring 元数据）；仅「文档/wiki 与代码事实漂移」。
- **定位**：`.tmp/`（可清理）、`doc/archive/legacy/`（历史归档，一般不回改）、`.qoder/`（自动生成）。
- **修复建议**：声明口径改为「**真源码/配置/Spring 元数据**中为空」；`.qoder` 自动 wiki 建议重生成；`.tmp` 日志可忽略或清理。

### P3-2　事件类计数笔误（声明 5）

- **描述**：声明称 `shared/event` 下「其余 **8** 个事件类」；实测原始共 **8** 个（含被迁走的 `ExecutionCommandCreatedEvent`），迁走后剩 **7** 个。
- **证据**：
  ```text
  $ git ls-tree --name-only HEAD:helloai-core/src/main/java/com/helloai/core/shared/event/ | wc -l
  8                     # 原始 8 个（含被迁走的 1 个）
  $ ls helloai-core/src/main/java/com/helloai/core/shared/event/
  DutyLeaseClosedEvent.java  InboxMessageCreatedEvent.java  SubTaskAssignedEvent.java
  SubTaskCompletedEvent.java  SubTaskSubmittedForReviewEvent.java
  TaskAutoCompletedEvent.java  TaskFinalReportGeneratedEvent.java   # 剩 7 个
  ```
- **影响范围**：仅声明描述层面，无功能影响。
- **修复建议**：口径更正为「其余 **7** 个」。

### P3-3　声明对旧实现的描述不完整（声明 3）

- **描述**：声明描述旧 `extractExecutionOutput(SubTask)` 时为「内部 `Map ctx = subTask.getContext();`」，**未提及**旧方法首行存在 `if (subTask == null) return null;` 守卫。实测**旧方法对 `subTask` 本身就是 null 容忍的**。
- **证据**：
  ```text
  $ git show HEAD:helloai-core/src/main/java/com/helloai/core/shared/util/SubTaskOutputExtractor.java
  public static String extractExecutionOutput(SubTask subTask) {
      if (subTask == null) {
          return null;                     # ← 旧方法确有 subTask null 守卫
      }
      Map<String, Object> ctx = subTask.getContext();
      ...
  ```
- **影响范围**：不影响等价性结论（下方已逐点核对：8 处调用点全部安全）。但声明描述与历史代码不符，易误导后续复核。
- **修复建议**：修正描述或补注。

### P3-4　新 API 语义收窄的防御性提示（非现网缺陷）

- **描述**：新方法不再容忍「来源实体本身为 null」，改由调用方在实参处完成解引用（`x.getContext()`）。当前 8 处调用点**均安全**（详见声明 3 核对），无现存缺陷；但属 API 契约收窄。
- **修复建议**：现有 javadoc 已注明「`context` 可为 null」，建议再补一句「调用方需自行保证 `subTask`/`dep` 等来源实体非空」，防未来新调用点误用。无需落库，属建议级。

---

## 【逐条声明核对】

### 声明 1 —— 测试包漂移修复 → ✅ 证实

| 核对点 | 结论 | 依据 |
|---|---|---|
| 旧路径不存在 | ✅ | `ls .../core/system/service/AttachmentServiceImplTest.java` → No such file；`find` 仅返回新路径 |
| 新路径存在且 package 正确 | ✅ | `.../core/task/service/impl/AttachmentServiceImplTest.java:1` = `package com.helloai.core.task.service.impl;` |
| 被删 import 确为冗余 | ✅ | 第 42 行 `private AttachmentServiceImpl service;` 用简单名，与新文件同包（`AttachmentServiceImpl.java:1` 同包），无需 import |
| 其余 import 是否都能解析 / 有无原同包包级私有类型依赖 | ✅ **无编译风险** | 文件未 import 任何 `com.helloai.core.system.service.*`；被引用类型全部显式 import 或 JDK；`AttachmentServiceImpl` 为 `public`（`:33`）且 `@RequiredArgsConstructor` 生成 3 参构造器（`SubTaskService, TaskService, ArtifactStorage`，`:35-37`），与测试第 52 行 `new AttachmentServiceImpl(subTaskService, taskService, artifactStorage)` 一致 |

> 注：白客声称「22 用例通过」未复跑（按约定本轮不跑构建），但**静态层面未发现编译风险**。

### 声明 2 —— `SubTaskDependencyOrder` 下移 → ✅ 证实

| 核对点 | 结论 | 依据 |
|---|---|---|
| `shared.util.SubTaskDependencyOrder` FQN 全仓为空（含 Javadoc `@link`） | ✅ | `grep -rn "shared\.util\.SubTaskDependencyOrder" helloai-*/src doc scripts deploy` → 0 命中 |
| 旧文件不存在 | ✅ | `find -name SubTaskDependencyOrder.java` 仅 `.../core/task/util/SubTaskDependencyOrder.java` |
| 新文件 package 正确 | ✅ | `task/util/SubTaskDependencyOrder.java:1` = `package com.helloai.core.task.util;` |
| 4 处调用方 import 已更新 | ✅ | `PlannerAnalysisServiceImpl:10`、`TaskDeliverableServiceImpl:7`、`TaskFinalReportServiceImpl:18`、`TaskIterationServiceImpl:8` 均为 `com.helloai.core.task.util.SubTaskDependencyOrder` |
| 有无第 5 处引用（测试/非 Java） | ✅ **无功能引用** | 其余命中仅为：`SubTaskDependencyOrder.java` 自身、`helloai-ui/src/utils/subTaskDag.ts:5`（中文注释提概念，无 FQN）、`doc/archive|doc/review|doc/log`（历史路径串，非 FQN）。无需改。Javadoc `{@link SubTaskDependencyOrder}`（`PlannerAnalysisServiceImpl:298`）用简单名且同文件已 import → **不会静默失效** |

### 声明 3 —— `SubTaskOutputExtractor` 去实体化【重点】→ ✅ 证实（附 P3-3/P3-4 提示）

**a) 调用点数量与列表**：全仓 `grep -rn "SubTaskOutputExtractor"` 命中 **8 个调用点 / 7 个文件**，与声明一致，**无第 9 处**（测试文件 0 引用）：

| # | 文件:行 | 实参 |
|---|---|---|
| 1 | `agent/service/AgentRuntimeContextAssembler.java:625` | `dep.getContext()` |
| 2 | `agent/service/impl/McpToolServiceImpl.java:943` | `dep.getContext()` |
| 3 | `review/support/ReviewEvidenceAssembler.java:74` | `subTask.getContext()` |
| 4 | `task/listener/SubTaskCompletionListener.java:181` | `subTask.getContext()` |
| 5 | `task/service/impl/TaskDeliverableServiceImpl.java:201` | `subTask == null ? null : subTask.getContext()` |
| 6 | `task/service/impl/TaskFinalReportServiceImpl.java:798` | `subTask == null ? null : subTask.getContext()` |
| 7 | `task/service/impl/TaskIterationServiceImpl.java:95` | `st.getContext()` |
| 8 | `task/service/impl/TaskIterationServiceImpl.java:253` | `st.getContext()` |

**b) 语义等价性 / null 是否可能**（旧方法对 `subTask` null 容忍，故须逐点确认实参变量非空）：

| 调用点 | 变量是否可能为 null | 证据（同方法内先解引用处） | 结论 |
|---|---|---|---|
| 1 `dep` | 否 | 同方法首行 `agent/service/.../AgentRuntimeContextAssembler.java:594` `attachmentService.listActive(dep.getId())`；catch 亦用 `dep.getId()`（:623） | 安全 |
| 2 `dep` | 否 | `McpToolServiceImpl.java:913` `attachmentService.listActive(dep.getId())`；catch 亦用 `dep.getId()`（:941） | 安全 |
| 3 `subTask` | 否 | `ReviewEvidenceAssembler.java:73` `readableAttachments(subTask.getId())` **先于** :74 解引用 | 安全 |
| 4 `subTask` | 否 | `SubTaskCompletionListener.java:145` `attachmentService.listActive(subTask.getId())`；catch 亦用 `subTask.getId()`（:179） | 安全 |
| 5 helper 入参 | 是（**已显式守卫**） | `TaskDeliverableServiceImpl.java:201` `subTask == null ? null : subTask.getContext()` | 安全 |
| 6 helper 入参 | 是（**已显式守卫**） | `TaskFinalReportServiceImpl.java:798` 同上 | 安全 |
| 7 `st` | 否 | `TaskIterationServiceImpl.java:80` `reviewPort.isLatestReviewApproved(st.getId())`、`:87` `st.getAssignedAgentId()` | 安全 |
| 8 `st` | 否 | `:252` 循环元素来自 `subTasks`（DB `list()` 结果，元素非空），`:253` 为其唯一解引用 | 安全 |

→ **无 P0**：不存在「变量可能为 null 且依赖旧 null 容忍」的调用点。凡变量为 null，均会在**早于提取器的同一方法**处先抛 NPE（旧代码同样会在此处 NPE），行为未变。

**c) 两个私有 helper**：✅ 确实保留 null 容忍，且**签名未变**（仍 `(SubTask)`），故**外层调用点一行未改**（diff 仅含 import 行 + helper 方法体 + javadoc）：

```java
// TaskDeliverableServiceImpl.java:200-202 / TaskFinalReportServiceImpl.java:797-799
private static String extractExecutionOutput(SubTask subTask) {
    return SubTaskOutputExtractor.extractExecutionOutput(subTask == null ? null : subTask.getContext());
}
```

**d) 输入等价性**（新实现逐分支 vs 旧实现）：

| 输入 | 旧返回 | 新返回 | 等价 |
|---|---|---|---|
| `context == null` | `null`（旧：`ctx != null` 为假） | `null`（首行守卫） | ✅ |
| `lastExecution` 不存在 | `null` | `null` | ✅ |
| `lastExecution` 非 `Map` | `null`（`instanceof` 假） | `null` | ✅ |
| `output` 非 `String` | `null`（`instanceof` 假） | `null` | ✅ |
| `output` 为空串 `""` | `""`（`instanceof String` 真，原样返回） | `""` | ✅ |

**e) 归属与依赖**：✅ 类**仍在** `helloai-core/src/main/java/com/helloai/core/shared/util/SubTaskOutputExtractor.java`（package `com.helloai.core.shared.util`）；文件**仅** `import java.util.Map;`，**不再 import 任何 `com.helloai.core.task.*`**，javadoc 亦无 `{@link SubTask}`。

> 提示见 P3-3（声明对旧实现描述不完整）、P3-4（API 收窄建议）。

### 声明 4 —— doorbell 整包搬迁 → ⚠️ 部分证实（源码/配置全对；仅「全仓为空」口径不严，P3-1）

| 核对点 | 结论 | 依据 |
|---|---|---|
| a) 旧目录不存在 / 新目录 7 主文件 + 5 测试 | ✅ | `core/shared/doorbell`（主）与 `core/shared/doorbell`（测试）均 No such file；`core/agent/doorbell` 含 7 主文件；`core/agent/doorbell`（测试）含 5 测试 |
| a) package 正确 | ✅ | 7 主 + 5 测试 `package com.helloai.core.agent.doorbell;` 全部一致 |
| b) `core.shared.doorbell` 真源码/配置为空 | ✅（口径修正见 P3-1） | `grep` 于 `helloai-*/src` → 0 命中；残留仅在 `.tmp` 日志 / `doc/archive` / `.qoder` wiki |
| c) 组件扫描 / 配置按包引用 | ✅ **无静默丢 Bean 风险** | 扫描根 `HelloAIApplication.java:13` `@SpringBootApplication(scanBasePackages = "com.helloai")` → 搬迁后仍在扫描域内；`@MapperScan`（:15-25）仅列 9 个 mapper 包，**不含** doorbell（doorbell 无 mapper）；`ItTestApplication` 同为 `com.helloai`；全库 `find spring.factories / *.imports` → 0 |
| d) 两条 agent service import 必要性 | ✅ | `DoorbellServiceImpl.java:3-4` import `com.helloai.core.agent.service.AgentDutyLeaseService`、`...agent.service.HeartbeatService`；二者位于 `core/agent/service/`（非 `core/agent/doorbell`），**确为跨包、删除会编译失败**。搬迁后 shared→agent 反向依赖消除（doorbell 现处 agent 域） |
| 附带 | ✅ | `AgentDoorbellController.java:3` `import com.helloai.core.agent.doorbell.DoorbellService;` 已更新；全库 `import com.helloai.core.agent.doorbell` 仅此 1 处（其余 doorbell 类同包互引无需 import） |

### 声明 5 —— `ExecutionCommandCreatedEvent` 搬迁 → ✅ 证实（1 处笔误 P3-2）

| 核对点 | 结论 | 依据 |
|---|---|---|
| 无 `core.shared.event.ExecutionCommandCreatedEvent` 残留 | ✅ | 全仓 grep → 0 命中 |
| 新 package 正确 + 4 处 import 更新 | ✅ | `agent/event/ExecutionCommandCreatedEvent.java:1` `package com.helloai.core.agent.event;`；`import com.helloai.core.agent.event.ExecutionCommandCreatedEvent` 共 **4** 处：`LocalExecutionCommandConsumer.java:17`、`ExecutionCommandServiceImpl.java:15`、`ExecutionCommandServiceDispatchTest.java:13`、`ExecutionCommandServiceTest.java:13` |
| `shared/event` 其余事件类是否干净 | ✅ **全部干净** | `grep -rn "import com\.helloai\.core\.\(task\|agent\|planner\|review\|system\)" shared/event/` → 0 命中。7 个剩余事件类（`DutyLeaseClosed/InboxMessageCreated/SubTaskAssigned/SubTaskCompleted/SubTaskSubmittedForReview/TaskAutoCompleted/TaskFinalReportGenerated`）均**无业务域 import**（仅 lombok/JDK） |
| 计数笔误 | ⚠️ | 声明称「其余 8 个」，实为 7（见 P3-2） |

### 声明 6 —— 门禁与基线 → ✅ 证实

**`scripts/ci/arch-baseline.txt` 逐行核对**（第 17-36 行共 **20** 条规则）：

| 规则 | 基线值 | 规则 | 基线值 |
|---|---|---|---|
| `task->planner` | 0 | `shared->planner` | 0 |
| `task->review` | 0 | `shared->review` | 0 |
| `agent->task` | **68** | `shared->task` | **0** |
| `agent->planner` | 0 | `shared->agent` | **0** |
| `agent->review` | 0 | `shared->system` | 0 |
| `system->planner` | 0 | `planner->task.mapper` | 0 |
| `system->review` | 0 | `review->task.mapper` | 0 |
| `system->task` | 0 | `agent->task.mapper` | 0 |
| `system->agent` | 0 | `task->agent.mapper` | 0 |
| `planner->agent` | **34** | `task->agent` | **45** |

非零仅 `agent->task=68`、`planner->agent=34`、`task->agent=45`，与声明一致。

**独立复算（不依赖脚本，直接数 import 行）**：

```text
$ grep -rE "^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?com\.helloai\.core\.task([.;]|$)" helloai-core/src/main/java/com/helloai/core/shared --include=*.java | wc -l
0                                            # 期望 0 ✅

$ grep -rE "^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?com\.helloai\.core\.agent([.;]|$)" helloai-core/src/main/java/com/helloai/core/shared --include=*.java | wc -l
0                                            # 期望 0 ✅

$ grep -rE "^[[:space:]]*import[[:space:]]+(static[[:space:]]+)?com\.helloai\.core\.task([.;]|$)" helloai-core/src/main/java/com/helloai/core/agent --include=*.java | wc -l
68                                           # 期望 68 ✅
```

**`shared` 保持叶子域终检（超出声明要求，全量）**：

```text
$ 逐域 import 计数：shared->planner=0  shared->review=0  shared->task=0  shared->agent=0  shared->system=0

$ grep -rnE "com\.helloai\.core\.(planner|review|task|agent|system)\." helloai-core/src/main/java/com/helloai/core/shared --include=*.java
[exit 1]                                     # 内联 FQN 亦 0

$ grep -rn "import com\.helloai\.core" helloai-core/src/main/java/com/helloai/core/shared --include=*.java | wc -l
0                                            # shared 对 com.helloai.core.* 零 import
```

> 健全性校验：`shared` 共 14 个 `.java`，其全部 import 仅为 `lombok` / `java.*` / `mybatis` / `jackson` / `postgresql`；**连 `com.helloai.common` 都不依赖**。对照 `agent` 域确有 24 个文件 import `com.helloai.core.task`（证明 grep 有效、非误报 0）。→ `shared` 为**严格叶子域**。

### 声明 7 —— 无 git 提交 → ✅ 证实（原 P1-1/P2-1 经核实非缺陷）

| 核对点 | 结论 | 依据 |
|---|---|---|
| HEAD（以 `2bb78b8` 为基线，非简报的过期数字 `e2c7e8c`） | ✅ | 实测 HEAD=`2bb78b8`（"文档更新"，2026-09-30 23:28，早于本会话）；无成员提交过代码 |
| `git diff --cached --stat` 为空 | ⚠️ 非缺陷 | 暂存区含 2 笔**上一批次 Q1-① 有意 `git rm`** 的删除（非本次改动、非误操作）；见「提交卫生提示-1」 |
| 7 项改动应表现为 ` M` / `??`，不应有 `M `/`A ` | ✅ | 架构改动文件均为 ` M`（改） / ` D`（删） / `??`（新）；**无** `M `/`A ` 的已暂存标记 |

---

## 【验收标准核对】

| 声明 | 结论 | 依据（摘要） |
|---|---|---|
| 1 测试包漂移修复 | ✅ 证实 | 旧路径消失、新路径与 package 正确、import 冗余判断成立、无编译风险 |
| 2 SubTaskDependencyOrder 下移 | ✅ 证实 | 旧 FQN 全仓 0 命中、新 package 正确、恰 4 处调用点、Javadoc link 不失效 |
| 3 SubTaskOutputExtractor 去实体化 | ✅ 证实 | 8 调用点无遗漏、逐点无 P0、helper 保留 null 容忍且外层未改、5 类输入等价、类留 shared 且零 task import |
| 4 doorbell 整包搬迁 | ⚠️ 部分证实 | 目录/package/扫描配置/import 必要性**全对**；仅「全仓为空」口径不严（P3-1） |
| 5 ExecutionCommandCreatedEvent 搬迁 | ✅ 证实 | 无残留、4 import 更新、shared/event 其余类干净；计数笔误 P3-2 |
| 6 门禁与基线 | ✅ 证实 | 20 条基线逐条核对一致；3 条关键规则独立复算一致；shared 叶子域终检通过 |
| 7 无 git 提交 | ✅ 证实 | 架构改动确未 add/提交；HEAD 以 `2bb78b8` 为基线；暂存区 2 笔删除属上一批次预期操作（提交卫生提示） |

---

## 【静态不可判 / 需构建或运行阶段验证】

以下项本轮**未验证**，不得视为通过：

1. **编译是否通过**：本轮约定不执行 `mvn`。声明 1/2/3/4/5 的包搬迁与签名变更**静态层面未发现编译错误**（import 均可解析、构造器匹配、无残留旧 FQN/旧签名调用），但**最终以一次完整 `mvn compile` 为准**。
2. **Spring 上下文能否启动 / Bean 是否齐全**：已静态确认扫描根 `com.helloai` 覆盖新包、无 `spring.factories`，**推断**无 Bean 丢失；但**组件实际装配**需启动一次 `ApplicationContext` 验证。
3. **22 个测试用例是否真跑通**：声明称跑通，本轮未复跑。建议在**隔离 `target/`** 的前提下由我统一跑全量回归验证。
4. **运行时语义等价**：声明 3 的等价性为**代码层静态推理**结论；如需强证据，建议构造 `context==null / lastExecution 缺失 / 非 Map / output 非 String / output=空串` 五例单测实测（但该方法为静态纯函数，静态等价推理已充分）。

**建议的后续验证方案**：由我在另一位工程师停止构建、`target/` 释放后，单独执行
`mvn -q -pl helloai-core,helloai-api,helloai-start -am test`（或项目约定命令），
并针对 `AttachmentServiceImplTest`（22 例）与 doorbell 5 测试单独核对用例数，
再回归 `scripts/ci/arch-freeze` 门禁。

---

## 【测试结论】

**7 项声明全部证实（声明 4 附 1 条口径 P3）。可交付（附 1 条提交卫生提示）。**

- 6 项架构语义声明（1/2/3/4/5/6）**实质成立**，其中声明 3 这一「最需证伪」项经 8 处调用点逐点核对**未发现 P0**；声明 4 仅口径不严（P3）。
- **声明 7 更正为 ✅ 证实**：原 P1-1（暂存区 2 笔删除）经主理人核实为**上一批次 Q1-① 的预期 `git rm`**，非缺陷、非阻塞，降级为「提交卫生提示」；原 P2-1（HEAD）系简报引用过期数字，**基线应为 `2bb78b8`**，非缺陷。
- 剩余无阻塞项：P3-1 / P3-2 / P3-3（文档/口径清理待办，见缺陷清单）。
- 后续：`target/` 已释放，全量构建/回归由本人统一执行（见步骤④验证章节）。

### 测试数据与清理

- 本轮**未产生任何测试数据**（无 `pretest_` 前缀数据），无需清理。
- 本轮**未修改任何既有文件**；仅新增本报告文件：
  `doc/review/qa-verify-arch-collect-2026-10.md`（`git status` 中会多出一条 `??`，非源码、不影响构建）。

---

## 【步骤④ 独立复跑验证（构建期）】—— helloai-job 6 类去 Mapper 直连

- 执行人：秦戈（独立复跑，非复述白客数字）
- 环境：`source scripts/ci/lib-jdk.sh` → JDK `17.0.20.1`；`mvn -o -B --no-transfer-progress -DskipTests=false clean test`
- 白客已停，`target/` 已释放

### 原始数字（独立复跑）

```text
[INFO] BUILD SUCCESS
MVN_EXIT=0
Reactor: HelloAI .. SUCCESS / Common SUCCESS / MQ SUCCESS / Core SUCCESS[02:37] / Job SUCCESS / API SUCCESS / Start SUCCESS

# 用例数：数 <testcase> 元素（非读 testsuite 属性）
全量 <testcase> = 1783     （期望 1783 ✅）
job  <testcase> = 85       （期望 85   ✅）
<failure>=0  <error>=0  <skipped>=0   （期望 0 ✅）
Maven 模块汇总：job → "Tests run: 85, Failures: 0, Errors: 0, Skipped: 0"

$ bash scripts/ci/check-arch-freeze.sh
[arch-freeze] ✅ 全部规则未超冻结基线。   GATE_EXIT=0
（20 条规则全 ✅ 持平，含 agent->task 68 / planner->agent 34 / task->agent 45）
```

> 说明：按 `*.txt` 逐类汇总得 1144，低于 XML 的 1783——因带 `@Nested` 的类其 `.txt` 只记外层类计数、XML 逐嵌套计数；**以 `<testcase>` 元素为准 = 1783**。

### 探针 1：SQL 等价性（最高风险）—— ✅ 无差异

`AgentHealthCheckTask` 三处「手写 wrapper → Service 方法」，逐条件比对：

| 原（`SubTaskMapper` + `LambdaQueryWrapper`） | 新（`SubTaskService`） | 条件比对 |
|---|---|---|
| `eq(assignedAgentId, agentId).in(status, ASSIGNED, IN_PROGRESS)`（`selectCount`） | `existsInFlightAssignedOrInProgress`：**同** wrapper | 字段/操作符/状态集合**完全一致** ✅ |
| `eq(assignedAgentId, agentId).in(status, ASSIGNED, IN_PROGRESS)`（`selectList`） | `listInFlightAssignedOrInProgress`：**同** wrapper | 一致 ✅ |
| `eq(assignedAgentId, agentId).eq(status, PAUSED).le(updateTime, expiredBefore)` | `listPausedBefore`：**同** wrapper | 一致 ✅ |

- **状态集合**：A3/A4 = `ASSIGNED + IN_PROGRESS`（**2 状态，不含 REWORK**）✅；A5 = `PAUSED` ✅。
- **无凭空多出的 `limit` / `orderBy`** ✅（接口 javadoc 亦显式注明「无 limit、无排序——加 limit 会截断重派范围」）。
- 其余 5 类的 8 个调用点均为**同名 Mapper 方法的薄委托**（`selectTimedOutAssigned` / `incrementExternalFallbackCount` / `selectPendingUnassignedWithoutActiveExecutionRecord` / `selectStalePendingWithoutExecutionRecord` / `selectTimedOutPlanning` / `selectByLastSeenBefore` / `markOfflineIfStale` / `selectByStatusAndCreateTimeBefore` / `selectByStatusAndStartTimeBefore`），**参数顺序/语义不变** ✅。
  - 证据：`AgentServiceImpl.listStaleSince → baseMapper.selectByLastSeenBefore`；`markOfflineIfStale → baseMapper.markOfflineIfStale(5 参)`；`AgentExecutionRecordServiceImpl → baseMapper.selectByStatusAndCreateTimeBefore/StartTimeBefore`；`TaskServiceImpl.listTimedOutPlanning → baseMapper.selectTimedOutPlanning`；`SubTaskServiceImpl` 各方法 → 对应 `baseMapper.*`。

**结论：未发现任何 SQL 语义漂移 → 无 P0。**

### 探针 2：断言是否被削弱 —— ✅ 未被削弱

- 6 个 job 测试类 `@Test` 数量 **新旧逐一对齐，无一被删**：

  | 测试类 | 原(HEAD) | 现 | 判定 |
  |---|---|---|---|
  | AgentHealthCheckTaskTest | 21 | 21 | ✅ |
  | SubTaskPendingOrphanTaskTest | 12 | 12 | ✅ |
  | AssignedSubTaskTimeoutTaskTest | 6 | 6 | ✅ |
  | ExecutionCompensationTaskTest | 3 | 3 | ✅ |
  | ExternalAgentFallbackTaskTest | 14 | 14 | ✅ |
  | PlanningTimeoutTaskTest | 4 | 4 | ✅ |
  | 小计 | **60** | **60** | ✅ |

- `SubTaskPendingOrphanTaskTest.shouldSkipWhenDisabled` 删除的 `verifyNoInteractions(subTaskMapper)` —— **其等价断言 `verifyNoInteractions(subTaskService)` 保留**（且同方法内另有 `verifyNoInteractions(subTaskDispatchService)`），**非弱化** ✅。
- `AgentHealthCheckTaskTest` 全部 `verify/when` 为 **1:1 mock 替换**（mapper→service），断言强度不变；仅类头说明与导入同步更新（移除已无用的 `LambdaQueryWrapper`/`SubTaskMapper`/`AgentMapper`/`Field`/`atLeastOnce` 导入——由编译通过验证确属无用）✅。

### 探针 3：接口与实现双侧落地 —— ✅ 全部双侧

| 方法 | 接口 | Impl |
|---|---|---|
| `AgentService.listStaleSince` / `markOfflineIfStale` | ✅ | ✅ `baseMapper.*` |
| `SubTaskService.existsInFlightAssignedOrInProgress` / `listInFlightAssignedOrInProgress` / `listPausedBefore` / `listTimedOutAssigned` / `incrementExternalFallbackCount` / `listPendingUnassignedWithoutActiveExecutionRecord` / `listStalePendingWithoutExecutionRecord` | ✅ | ✅ |
| `SubTaskService.selectInFlightByAgent`（复用，非新增） | ✅（`SubTaskService.java:374`，**早于本次改动**） | ✅（`SubTaskServiceImpl.java:1302`） |
| `TaskService.listTimedOutPlanning` | ✅ | ✅ |
| `AgentExecutionRecordService.listByStatusCreatedBefore` / `listByStatusStartedBefore` | ✅ | ✅ |

### 探针 4：job main 侧去 Mapper 直连实现 —— ✅ 清零

```text
$ grep -rn "^[[:space:]]*import[[:space:]]\+com\.helloai\.core\..*\.mapper\." helloai-job/src/main/java
[exit 1]                       # 0 命中 ✅
$ grep -rn "Mapper" helloai-job/src/main/java
… 仅 NotificationConsumer / OutboxRelayTask 的 jackson ObjectMapper（与域 Mapper 无关、且非本次改动）
```
6 个类构造器已无 Mapper 参数，6 个测试构造调用同步更新 → **编译通过**（BUILD SUCCESS）佐证一致 ✅。`ExecutionCompensationTask` 内已无 `Agent` 类型引用（"CLI_CLIENT Agent" 仅为注释文本），删 `import ...entity.Agent` 安全 ✅。

### 探针 5：用例数口径 —— ✅ 见「原始数字」

### 探针 6：跨域计数 —— ✅

```text
agent->task = 68   （期望 68 ✅）
shared->task = 0   （期望 0  ✅）
shared->agent = 0  （期望 0  ✅）
```

### ④ 声明逐条核对总表

| 白客声明 | 结论 | 依据 |
|---|---|---|
| 6 个类删 Mapper 注入、改调域 Service | ✅ 证实 | 6 类 diff；job main mapper import=0 |
| `AgentService.listStaleSince` / `markOfflineIfStale`（5 参 CAS） | ✅ 证实 | 双侧落地；薄委托同一 Mapper 方法 |
| `SubTaskService` 7 个新方法 + 复用 `selectInFlightByAgent` | ✅ 证实 | 双侧落地；SQL 口径一致 |
| `TaskService.listTimedOutPlanning` | ✅ 证实 | 双侧落地 |
| `AgentExecutionRecordService.listByStatus{,Started}Before` | ✅ 证实 | 双侧落地 |
| `ExecutionCompensationTask` 删死注入 `agentMapper`（+ 无用 import） | ✅ 证实 | 文件内已无 `Agent` 引用；编译通过 |
| job 模块 85 用例全绿 | ✅ 证实 | job 汇总 85/0/0/0；`<testcase>`=85 |
| 全量 1783 / 0 / 0 / 0 | ✅ 证实 | `<testcase>`=1783；failure/error/skipped=0 |
| 门禁 EXIT=0 | ✅ 证实 | `GATE_EXIT=0`，20 条全持平 |
| `agent->task` 仍 68 | ✅ 证实 | 独立复算 68；门禁一致 |

### ④ 缺陷清单

**P0 ×0，P1 ×0，P2 ×0，P3 ×0。** 未发现与声明不符之处；无需返修要点。

### ④ 遗留 / 静态不可判

- 「PAUSED 未超宽限不回收」这一时间窗口条件由 SQL 承担，单测层 Mockito 无法构造（测试注释亦已声明）——属**集成/E2E 覆盖范围**，本机无 PG，**NOT RUN**。
- 本轮为 `clean test`（单元测试）；真实 PG / MinIO / Redis 的 E2E 未执行。

---

## 操作留痕

- 静态复核阶段：**未**执行 `mvn` / git 写操作（无 add/commit/stash/checkout）。
- 步骤④ 构建期（经主理人授权、白客停机后）：执行 `mvn -o -B -DskipTests=false clean test`（1 次全量复跑）+ `bash scripts/ci/check-arch-freeze.sh`；输出留存 `.tmp/qa-step4-fulltest.log`。
- git 只读命令使用：`git rev-parse`、`git log`、`git show`、`git diff`、`git diff --cached`、`git status`、`git ls-tree`、`git cat-file`。
- 文件系统只读操作：`ls` / `find` / `grep` / Read。
