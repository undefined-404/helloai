# HelloAI SKX-9 端到端验收记录（2026-11-01）

> **结论先行**：`SKX-9` 的**核心证实目标——「模型在工具循环里自主决定调 `internal_run_skill`」——已取得（已验真）**；
> **2026-11-01 用户裁定**：判据③「外链=0」与 archify 出厂模板的 GitHub 署名 `href` 冲突，**保留署名**（archify 为
> GitHub 开源），`SKX-9` **按通过**回填。此系**裁决式通过**——不是「自动化检查通过」；
> 执行侧子任务 7059 曾以 rework_limit_exceeded 死信（事实留档），C1 档「三段门禁全绿」未整链自动化达成，差异如实标注。

---

## 1. 验收背景与口径（防误判）

- 本项属 `SKX-9`，验的是「模型在 `RuntimeTurnExecutor` 工具循环里**自己决定**调 `internal_run_skill`」
  这条路径（子任务执行者侧，internal executor 2088623654343675905，inner-deepseek-flash-excutor）。
- **与 `SKX-12` 不互借**：`SKX-12` 是 `ConversationSkillRunner` 服务层直接注入 + 执行 + 回读，
  不经过模型选工具。本项实测走的是模型工具循环，两者共享 `SkillPackageExecutionService` 但入口/触发主体不同。
  `SKX-12` 只证明「执行通道能跑通」，本项负责证明「模型会用」（计划正文 2026-10-27 口径订正，line 358-362）。
- 运行环境：本地 local（前后端 IDEA 启动，后端 6565），沙箱 = opensandbox，DB = helloai-postgres。
- 验证任务：`task id=2116759635065503745`「SKX-9 自动化验收：用 archify 画 checkout 请求时序图」，状态 IN_PROGRESS（阻塞于出口裁决）。

## 2. 已验事实（证伪优先，只写可查证的）

### 2.1 模型经工具触发（`internal_run_skill` 等）——已实测命中

后端日志 `logs/helloai.log`（时间窗 2026-11-01 13:20–13:30）`ToolCallbackToolExecutor` 逐次记下模型在 agentLoop 里的工具调用：

- `internal_get_sub_task_detail`、`internal_list_skills`、`internal_load_skill`、`internal_run_skill`、
  `internal_run_in_sandbox`、`internal_export_artifact`、`internal_read_artifact` 均出现；
- `internal_run_skill` 高频调用且多数「执行成功」（13:20:20～13:30:20 共 20+ 次）；
- `internal_run_in_sandbox` 用于落地产物字节核验（13:29:38 / 13:30:10 / 13:21:07 等）；
- `internal_export_artifact` + `internal_read_artifact` 在 13:28:07~08 连续出现（导出 + 回读）。

⇒ **「模型会用」这条（计划 line 362）已由日志佐证为真。**

### 2.2 执行记录（`agent_execution_record`，子任务 7059 四次尝试，全部 SUCCESS 落盘）

| 记录 id | trigger_type | start_time (+08) | end_time | 耗时 | token_usage |
|---|---|---|---|---|---|
| 2116763060788461570 | assigned | 13:23:36 | 13:26:13 | 2m37s | 423,939 |
| 2116763859304251393 | auto-review-rework | 13:26:46 | 13:28:52 | 2m7s | 485,563 |
| 2116764465863524353 | auto-review-rework | 13:29:11 | 13:32:16 | 3m4s | 418,512 |
| 2116765306787921922 | auto-review-rework | 13:32:31 | 13:36:03 | 3m32s | 719,474 |

配套输入子任务 7058（编写 sequence 输入 JSON）：13:20:08 → 13:22:10（2m2s，295,986 次 token），DONE。
其产出的 `validate` 实跑 `ok:true`、9 项 artifact checks `issues:[]`、schema 统计 participants=6/messages=16 达标，
输入 sha256 = `1ed9d1a3…c210161`（3518 B）。

### 2.3 三段门禁在沙箱内的实际状态（模型自报 + 回执物化）

- `validate`：`exit=0`、`ok:true`、`issues:[]`、errors/warnings=0。
- `deliver sequence … --quality showcase`：`exit=0`，回执 `artifact.sha256 = 904474e692ff370b1ccd11f4955adc32c176edd49a021e6d4e0692e87a993394`（64 位 hex），产物 = 820,084 B。
- `finalize …`（任务接口②指定命令）：`exit=2`，`failedStage=browser-check`，`viewer/chrome-unavailable`（沙箱无 Chromium），产物字节与 deliver 一致。执行者改用 `deliver` 替代并在交付正文如实披露。
- `check --json`：`ok=true`、`provenance=current`、`composition.issues=[]`、9/9 checks true、summary errors/warnings=0，`deliveryReceiptId` 与 deliver 回执一致。回执物化为附件 `943cf3ba-check-receipt.json`（3,202 B，status INACTIVE）。
- 沙箱内自核：执行者对产物 `sha256sum` 与 deliver 回执 `artifact.sha256` 独立复核一致（附件 `offline-link-audit.txt` 头部，2,597 B）。

### 2.4 sha256 三方一致（沙箱内自证 + 平台级独立回读均一致）

- 三条口径：① 文件实际 `sha256sum`；② deliver 回执 `artifact.sha256`；③ check 回执 `artifact.sha256`。
- 执行者在沙箱内把 ① 与 ②/(③) 对齐为 `904474e6…9394`，字节对齐到 820,084 B（`offline-link-audit.txt` 头部白纸黑字记录该「artifact sha256/bytes」）。
- **平台级未再独立回读（原）**：SKX-9 原计划第 3 步「`readArtifact` 回读比对三方」本应由子任务 7060「导出 HTML 为可下载附件并与回执哈希对账」承载；因上游 7059 死信（非 DONE），7060 保持 PENDING 未被分发，平台级三方对账**曾未自动执行**，曾如实标注「待核」。
- **（2026-11-01 补收口）平台级独立回读已验真**：从本机对象存储（MinIO `helloai-artifacts`）直接 `get_object` 平台已物化的产物对象
  `…/2116760891167277059/351ff288-checkout-sequence.html`，实测 **820,084 B**、`sha256 = 904474e692ff370b1ccd11f4955adc32c176edd49a021e6d4e0692e87a993394`，
  **与 deliver 回执（`d4e4f600-checkout-sequence.delivery.json`）、check 回执（`943cf3ba-check-receipt.json`）的 `artifact.sha256` 逐字一致** ⇒ 三种口径（回读字节 sha256 ↔ 回执 artifact.sha256 ↔ 文件实际字节）在**平台侧独立回读**下成立。此即补上了原待核的平台级三方对账，判据 5 从「平台级待核」转「**平台级已核**」。
- 说明（保留）：`SKX-11` 探针（`SkxExportProbe`）曾走平台 `readArtifact` 回读比对一致，是同一套导出读回通路，但那是探针；本项补收口直接在平台对象层独立回读核对，与探针不互借。

## 3. 验收标准逐条判定（对照计划 line 376）

| # | 验收标准 | 判定 | 依据 |
|---|---|---|---|
| 1 | 三段全部 `exitCode=0` | **未满足（裁决豁免）** | `finalize`=2（无 Chromium）；deliver=0。用户裁定保留署名并放行，finalize 依赖沙箱浏览器的差异如实留档（§5-2） |
| 2 | `check` 返回 `issues:[]` 且 `provenance:current` | 沙箱内满足 | check 回执物化，`provenance=current`、`issues=[]`、9/9 true；平台侧对 HTML 独立性被 per_file_limit 截断未直读 |
| 3 | 外链 grep 计数 = 0（可离线双击） | **未满足（经用户裁定放行）** | 实测 = 1，唯一命中 `template.html` 内 GitHub 署名 `<a … href>`（**加载型资源已全内联 data:，仅此 1 条署名超链接**）。用户裁定：archify 为 GitHub 开源，**保留署名**，判据③按通过回填 |
| 4 | 命令链在**技能根目录** `node bin/archify.mjs …` 可复现 | **WARN（留存）** | 第 4 次为达外链 0 改副本模板被复审否定（绕开官方 CLI）；随判据③放行，此口径不再作为强制项，出厂包原样可复现保持成立 |
| 5 | sha256 三方一致 | **平台级已核（2026-11-01 补收口）** | 见 §2.4：平台对象层独立回读 820,084 B，sha256 `904474e6…9394` == deliver/check 回执 `artifact.sha256` 逐字一致；三种口径一致成立 |
| 6 | 验收记录写明「自动化检查通过；未经视觉复核」 | **不适用** | 判据③经裁决放行，本记录据实写「裁决式通过」，不伪装为自动化通过 |

## 4. 结果
- 子任务 7059 → `DEAD_LETTER`（`sub_task_review_dead_letter`，reason=`rework_limit_exceeded`，maxRework=3、reworkCount=3，4 轮全被拒 score=2）。**留档事实**：死信源于判据③与出厂模板冲突，非执行质量缺陷。
- 子任务 7060（导出 + 哈希对账）→ `PENDING`，因上游死信未分发。
- 父任务 2116759635065503745 → `IN_PROGRESS`，`final_report_status=NONE`。

## 5. 阻塞根因、裁决缺口与裁决落地

### 5.1 阻塞根因（留档）
1. **第 3 条「外链 = 0」与出厂技能包模板冲突（主 blocker）**：`archify@3.1.0` 的 `assets/template.html`
   硬编码一条 GitHub 署名超链接，全包**无 `--no-credit` / ENV / schema 开关**（已 `--help` / doctor 查证）。
   要让官方 CLI 原样渲染出「0 外链」不可达——唯一出路是改模板，而改模板即破坏 `provenance=current` 与「出厂包可复现」。
   执行者在第 4 轮改副本被复审否定，证明这不是执行者边界内能解的。
2. **`finalize` 依赖沙箱浏览器（次 blocker）**：`finalize` 内部 `browser-check` 需 Chromium，沙箱无则 EXIT=2；
   执行者以 `deliver` 替代并披露，产物字节与 finalize 一致。
3. **平台级三方对账（原阻塞，2026-11-01 已补收口）**：7060 因上游死信未分发，平台级三方对账原曾未跑；2026-11-01 从平台对象层（MinIO `helloai-artifacts`）对已物化产物 `351ff288-checkout-sequence.html` 独立回读，sha256 == 回执 `artifact.sha256` 逐字一致（见 §2.4），**平台级三方对账已核**，本阻塞项关闭。

### 5.2 裁决落地（2026-11-01 用户裁定）
- **判据③**：archify 使用的是 GitHub 开源项目，**保留署名、不撤链接**，`SKX-9` **按通过**回填。
  依据：离线 HTML 已自包含（加载型资源全内联 data:，唯一 h=`GitHub` 署名 `href` 不影响双击离线浏览）。
- **不放行 `--no-credit` 改造**：用户选择保留，不给 archify 增加 `--no-credit` 开关。
- **口径**：技能按「裁决式通过」收口，不重跑 7058→7059→7060 全链求自动化 DONE。
- **留待（已在 2026-11-01 补收口）**：平台级 sha256 三方对账曾为待核（不影响技能通过判定）；2026-11-01 从平台对象层独立回读已验真，判据 5 转「平台级已核」（见 §2.4 / §3 判据 5）。

## 6. 诚实边界说明（证伪声明）
- 本记录**不是**全量自动化通过：判据③为**用户裁决放行**，C1 档「三段门禁全绿」未整链自动化达成。
- 「模型经 `internal_run_skill` 触发」为**已验真**；「三段全 exit=0」为**未达成**；「平台级 sha256 三方一致」**已由 2026-11-01 平台对象层独立回读补核**（见 §2.4）。
- 验收环境为本机 local（沙箱 opensandbox、DB docker）；`doc/review/` 命名与既有档一致。

## 7. 关联
- 计划：`doc/plan/HelloAI 技能执行面接入实施计划（2026-10-26）.md` §SKX-9（line 354-377）。
- 证据来源：`logs/helloai.log`；`agent_execution_record` / `sub_task` / `sub_task_review_dead_letter` / `attachment`（helloai-postgres）。
- 相关既有验收：`SKX-11` 探针三、`SKX-12` 对话直出（与本项路径不同，结论不可互借）。