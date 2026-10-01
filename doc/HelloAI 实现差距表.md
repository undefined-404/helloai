# HelloAI 实现差距表

> **状态：CURRENT GAP**
>
> 本文只记录当前 → 目标的真实差距。
>
> 最后更新：2026-09-26（G-015 心跳/重派止血 B1~B4 落地，含 V94 迁移；B4.1/B4.5 文档同步待办。补充依据见 `doc/review/HelloAI_外部Agent_v3反馈代码级复核.md`）
> 最后更新：2026-09-28（G-016 报告整合质量登记 + G-004/G-008 增量 + N1~N6 回流登记；补充依据见 `doc/archive/implemented/HelloAI_契约层能力注入与最终报告整合改造方案.md` §8）
> 最后更新：2026-09-28（G-016 阶段四：§12 遗留三项落地——V95 单槽列 + rollback、审查异步化 REVIEWING + 专用池 + 三重陈旧守卫、轮次去状态化）
> 最后更新：2026-09-28（G-017 附件存储一致性登记：存储抽象 + 对账巡检落地，R1/R3/R4/R5 处置完成；依据 `doc/review/HelloAI_附件存储一致性排查报告.md` §7.1）
> 最后更新：2026-09-29（**口径订正批次**：G-002 处置列改为诚实口径「契约层收官 60% / 生产态未收敛」、G-014 行 T04b/T08b 由「未做（挂起）」订正为「已完成」、`@SaCheckPermission` 计数 131→144；依据 `doc/review/HelloAI 架构V2进度与质量审计报告（2026-09-29）.md`。新增 §0 口径订正记录作为后续订正的统一落点）
> 最后更新：2026-09-29（G-016 §12.5 缺陷 #4/#5 修复收口：#4 AFTER_COMMIT 兜底落库下沉 `FinalReportReviewFallbackWriter`（两方法声明 `REQUIRES_NEW`）独立事务确定提交；#5 L2 抢锁失败改抛 `ReviewNotExecutedException` → markFailed + `basicNack(requeue=false)` 入死信重投；全量 core+job+api **1756 例 0 失败 0 错误** BUILD SUCCESS。#1~#5 全部已修，前端 #6/#7 可选精修仍登记待修）
> 最后更新：2026-09-30（**G-002 双轨→单轨硬切落地**：删除 RuntimeAgentRuntimeRouter / LegacyExecutorAdapter / TurnLlmCaller 全部旧链入口，RuntimeTurnExecutor 成为唯一 `AgentRuntime` 实现；新建 `AgentRuntimeContextAssembler` 承载装配（prompt/chatModel/会话/对话流），`LocalExecutionCommandConsumer` 重构为分层编排（startIfNeeded→markRunning CAS→装配→execute→afterTurn→CAS 终态→ExecutionResultHandler 回写）；清理 `v2-enabled`/`gray-percent`/`runtime-enabled` 死配置。验证：全量单测 BUILD SUCCESS（helloai-core 1551 例 0 失败）+ B 级 IT 8/8 全绿（本机 Docker，含 MQ 消费单轨链路）+ 修复 AgentSession.snapshot jsonb 类型不匹配（换用 PgJsonbTypeHandler）。详见 §0.1 C-7 与 `doc/log/2026-09.md` 2026-09-30 条目）
> 最后更新：2026-09-30（**外部 CLI_CLIENT 多 Agent E2E 验证 A 级全绿**：两个真实外部 agent 接单 5 任务 15 子任务全 DONE、同名任务不重发；评审真实拦截 3 类交付缺陷；心跳掉线自动回收 + 退避重派闭环；`verify-external-agent-e2e.ps1` 修复 PS 时区误判（新鲜度判定移 SQL 侧）+ STEP6 断言适配外部链路（外部走 MCP claimSubTask 无 `task_assigned`，改断言 `sub_task_dispatch_prepare`）+ 新增 `-AssertOnly` 模式。差距项状态无变化，详见 `doc/log/2026-09.md` 2026-09-30 条目）
> 最后更新：2026-09-30（**审计 §11.4 三项风险响应**：①`AgentRuntimeContextAssemblerTest` 定向单测落地（5 组 21 例——prompt 各段装配 / 凭据 fail-close / 恢复与降级；此前 ConsumerTest 中装配器为 @Mock，内部逻辑零真实覆盖）；②`AgentExecutionProperties.mockMode` 代码默认改 `false`（mock 须显式 `helloai.execution.mock-mode=true` 开启，防新增 profile / 绑定异常静默 mock）；③**checkpoint 升格为下批次首个任务**（先于 tokenUsage，纠正审计 §7-4 逆序）。全量 7 模块 BUILD SUCCESS（core 1572 / job 85 / api 70，0 失败 0 错误，口径见 §0.4）。详见 §0.4 与 `doc/log/2026-09.md` 2026-09-30 条目）
> 最后更新：2026-09-30（**checkpoint 每轮落库落地（审计 §11.7 #1 下批首任务收口）**：loop 每轮 iteration 边界经 `LoopCheckpointListener` 落 `agent_session.snapshot.loop`（循环进度事实：已完成轮数 / 工具调用次数 / 已执行工具），重派恢复段渲染接续进度；零 DDL、保持 V66 边界（不做 LLM 级断点续接、不回放消息历史）；`AgentLoopInput` 旧 12 参构造器保留，现有调用点零改动。全量 7 模块 BUILD SUCCESS（core 1585 = 1572 + 13 新增，0 失败 0 错误）。详见 §0.4 与 `doc/log/2026-09.md` 2026-09-30 条目）
> 最后更新：2026-09-30（**checkpoint E2E DB 实证通过（审计 §12.6 #3 闭环）**：内部链路真实 deepseek 任务 `ckpt-e2e-01-inner-loop-web-search`（taskId `2105185642520064002`）3 子任务全 DONE + 自动评审通过；直查 `agent_session.snapshot->'loop'` 同一子任务随轮次增长（iteration 1→2→3、toolCallCount 3→7→10、messageCount 4→6→8），终态 executedTools 含 `web_search`；新增复验脚本 `scripts/powershell/verify-inner-loop-checkpoint-e2e.ps1`（-AssertOnly）SCRIPT_EXIT=0；过程修复本地 AES 密钥注入环境坑（Tag mismatch：本地启动须注入 `HELLOAI_CREDENTIAL_AES_KEY_BASE64` 原密钥，值见本地私有 `.env`）。详见 `doc/log/2026-09.md` 2026-09-30 条目与审计报告 §12.9）
> 最后更新：2026-09-30（**tokenUsage 端到端落地（审计 §12.3 P2 清偿 / B5 清零）**：`ChatModelToolLoop` 每轮累加 `usage.totalTokens` → `AgentLoopResult` / `AgentExecutionResult.tokenUsage` → 终态 CAS 落 `agent_execution_record.token_usage`（V97 迁移）；`AGENT_COMPLETED` payload 增 `tokens`；内部链路真实任务 `tku-e2e-01-token-usage`（taskId `2105197269487259649`）E2E 三通道（结构化列 / 事件 payload / `sub_task.context.lastExecution.tokens`）7 个 token 值完全一致，新增复验脚本 `scripts/powershell/verify-token-usage-e2e.ps1`（-AssertOnly）SCRIPT_EXIT=0。全量 7 模块 BUILD SUCCESS（core 1591 = 1585 + 6，0 失败 0 错误）。**边界**：外部执行者（CLI_CLIENT）token 回传仍为盲区（G-008 协议未含 tokens）。详见 §0.6 与 `doc/log/2026-09.md` 2026-09-30 条目）
> 最后更新：2026-09-30（**P-1「跨子任务产出传递截断」修复批次**：根因精化——真病灶 = 附件选择顺序（非 4000/8000 截断，见 §0.1 C-12 与审计报告 §14.1）；完整防御三层（A1 病灶选择修复 + A2-1 行边界截断与结构化标注 + A2-2 重复失败短路）+ A4 `clean-minio-bucket.ps1` 清桶工具交付（关闭 C-11）；全量 core **1614** 例 0 失败 BUILD SUCCESS。详见 §0.7 与 `doc/log/2026-09.md` 2026-09-30 条目）
> 最后更新：2026-09-30（**审计 §15 两项残留修复落地（R1 短路判据反转 / R2 附件配额渲染）**：**R1** 主判据改为「相邻两轮 `score` 双可读时未严格提升即无实质进展」（`currScore <= prevScore`），相似度 0.85 降为 `score` 缺失时兜底；三处 payload 增 `basis`/`scoreTrend` 证据——真实形态（2→2）第 2 轮即短路，可省 **214,175 tokens**（120,624 + 93,551）。**R2** 新增 `UpstreamAttachmentRenderer` 逐附件配额渲染（主保底 1000 + 次要最低 500 + 逐附件行边界截断 + `[TRUNCATED] file=` 标注 + 总长 ≤ 预算硬上界），`AgentRuntimeContextAssembler` / `McpToolServiceImpl` 两处接入；渲染层 truncatedCount 口径并入「顶层 ∪ 附件标注」。真实语料夹具（sub3 三轮 issues，2753/1526/928 字符）相似度锚点 0.2537/0.1927/0.1799；定向 4 类全绿 + 新增 11 例；全量 core **1625**（=1614+11）/ job 85 / api 70，0 失败 BUILD SUCCESS。详见 §0.1 C-15 与 `doc/log/2026-09.md` 2026-09-30 条目）

> 最后更新：2026-10-01（**Q1-③ 架构收口批次 + 架构维度专项复核**：4 条反向依赖红线**全部清零**——`planner→task.mapper` 1→0、`system→task` 2→0（端口反转）、`shared→task` 2→0（`SubTaskDependencyOrder` 下移 `task/util` + `SubTaskOutputExtractor` 去实体化）、`shared→agent` 3→0（doorbell 整包迁 `agent/doorbell` + `ExecutionCommandCreatedEvent` 迁 `agent/event`），净清零 **8 处越界 import**；`shared` 恢复**叶子域**；守卫规则集 **3 → 20 条**（§6 全量 14 + §7.1 Mapper 红线 4 + 存量 2，其中 16 条为 0 值护栏）；基线外 `helloai-job` 6 类去 Mapper 直连（`src/main` 内 `*.mapper` import **0 命中**）。**未动**：`agent→task=68`（唯一最大存量，预案见《优先级决策分析》§16 五批）、B2 Sandbox 真实隔离 / B3 Event Recovery·Fork / B4 Quality Gate / B5 Fleet 成本选人（tokenUsage 已采未入选人策略）、上帝类未拆（`SubTaskServiceImpl` 1310→1365）。独立复跑：`check-arch-freeze.sh` 20 条全 ✅ EXIT=0；`mvn -DskipTests=false clean test` Reactor 6/6 SUCCESS，**用例 1783**（core 1628 / job 85 / api 70，0 失败 0 错误 0 跳过，`<testcase>` 元素口径）。详见 §0.1 C-16 与 `doc/review/HelloAI 架构V2进度与质量审计报告（2026-09-30）.md` §17）

# 0. 口径订正与文档一致性记录

> 本表是审计者依赖的「事实源」，因此**订正必须留痕**：就地修正正文的同时，在此登记订正前后差异与证据。
> 依据：2026-09-29 架构 V2 进度与质量审计（`doc/review/HelloAI 架构V2进度与质量审计报告（2026-09-29）.md`）。

## 0.1 文档订正记录

| # | 订正项 | 原文表述 | 订正后 | 证据 |
|---|---|---|---|---|
| C-1 | **G-002 口径**（§1 G-002 行「处置」列） | 「P0 主线收官 + 真灰度闭环」 | 「**契约层收官（≈60%）/ 生产态未收敛**」——灰度系 2026-09-08 一次性联调后即回滚，非持续运行态 | `AgentExecutionProperties.java:76`（`runtimeEnabled` 默认 false）、`application.yml:196`（false）、`LegacyExecutorAdapter.java:33`（`@Order(2)` 恒注册）；与《目标架构》§11 自身列出的非目标「❌ Dual Executor 永久双轨」冲突 |
| C-2 | **G-014 T04b**（§1 G-014 行「处置」列） | 「**未做（挂起）**：T04b 附件读端归属授权……任意有效 Agent Key 可读他人子任务附件正文」 | 「**已完成**」（代码层闭环，验证强度 C 级） | `AttachmentController.java:124-146`（`_authType=="agent"` 归属比对 + `BizException(403)`），四端点 `:52/65/79/106` 全部接线；`SubTaskController.java:113/129` 同校验；单测 `AttachmentControllerAuthScopeTest`（三态）；commit `5f48a64`（2026-09-24）；log `2026-09.md` 9/24 已记完成 |
| C-3 | **G-014 T08b**（同上） | 「未做：T08b `SKILL.md` 与 `doc/manual/executor-duty/` 文档一致性」 | 「**部分已完成**」——返工出口文档已同步；`doc/manual/executor-duty/` 限额章节口径待复核 | `helloai-core/src/main/resources/onboarding/executor/guide.md` 含 `startSubTask` 9 处 |
| C-4 | **`@SaCheckPermission` 覆盖数** | 「131 处 / 23 控制器」（截至 2026-09-13，见 §1 G-013 行与《基础架构调整实施计划》§9.5） | 实测 **144 处**（2026-09-29） | `grep -rc "@SaCheckPermission" helloai-api/src/main/java --include=*.java` 汇总 = 144 |
| C-5 | **scripts 目录规模表述** | 审计初稿曾记「401 个脚本 / 148 jar + 105 class 二进制入库」 | **git 实际跟踪 103 个**（76 ps1 + 24 sh + 1 sql + 1 md + 1 java）；另有 298 个**未跟踪**的 jar/class/log 构建产物。**「二进制入库」不成立**——根 `.gitignore` 的 `*.jar` / `*.class` / `*.log` 已覆盖 | `git ls-files scripts \| wc -l` = 103；`git ls-files scripts \| grep -c '\.jar$'` = 0；`.gitignore` |
| C-6 | **迁移缺号** | 未集中说明 | Flyway 迁移 **85 个**（V1、V2、V13~V95），**V3~V12 缺号**（历史合并所致，非丢失） | `ls helloai-start/src/main/resources/db/migration/` |
| C-7 | **G-002 双轨→单轨硬切**（§G-002 章节与 09-29/09-30 审计口径） | 「契约层收官（≈60%）/ 生产态未收敛」、双轨待用户决策（09-29 审计 §8 选项 A 退场时点 / B WONTFIX） | **已硬切单轨（2026-09-30 用户决策：不设灰度不设退场，直接切）**——旧链入口全部删除，RuntimeTurnExecutor 为唯一 `AgentRuntime` 实现；`AgentExecutionProperties.runtimeEnabled` / yml `v2-enabled` / `gray-percent` / `runtime-enabled` 死配置全部删除；数据清空执行 `cleanup-test-data.sql`（43 张业务表 TRUNCATE + MinIO 清桶） | 删除文件：`RuntimeAgentRuntimeRouter` / `LegacyExecutorAdapter` / `TurnLlmCaller` / `TurnLlmCallContext`；新建 `AgentRuntimeContextAssembler`；`LocalExecutionCommandConsumer` 分层编排；全量单测 BUILD SUCCESS（helloai-core 1551 例 0 失败）+ B 级 IT 8/8 全绿（本机 Docker）；详见 `doc/log/2026-09.md` 2026-09-30 条目 |
| C-8 | **G-014 / G-015 的 E2E 状态**（§1 两行「处置」列）——本表**正文行滞后于表头与 §0.1** 的同类漂移 | 「**E2E 脚本与 Docker 实测 NOT RUN**」「**E2E 与生产复测 NOT RUN**（需部署 + PG/Docker）」 | 「**✅ E2E 已实跑（2026-09-30）**」——以**真实外部 CLI_CLIENT Agent 端到端 A 级实测**替代脚本级抽验 | 脚本 `verify-external-agent-e2e.ps1`（含 `-AssertOnly`）+ `verify-single-track-e2e.ps1`；**DB 独立复核（2026-09-30 午后审计）**：`task` 7/7 DONE、`sub_task` 24/24 DONE（零卡死）、`route=agent_runtime` 9/9（零 legacy）、`agent_offline`×4、`sub_task_unclaimed_timeout_reassign`×3、`sub_task_auto_review_rejected`×6、`sub_task_dispatch_prepare`×29；详见 `doc/log/2026-09.md` 2026-09-30 条目 |

| C-9 | **`cleanup-test-data.sql` 覆盖范围**（§0.1 C-7、审计报告 §10「数据清空」行、`be59c3a` commit message 均记为「43 张业务表 TRUNCATE + MinIO 清桶」） | 「43 张业务表 + MinIO 清桶」 | **实测 35 张业务表**（DO 动态 TRUNCATE，缺表自动跳过）；**脚本内不含 MinIO 清桶**（无任何 minio/mc/bucket 语句） | `cleanup-test-data.sql:41-72` 的 `tables TEXT[]` 实数 = 35（任务链 13 + 执行痕迹 8 + 需求对话 4 + 审计附件 2 + 基础设施业务 8），文件自身注释亦写「35 张业务表」；`grep -iE "minio\|mc rm\|bucket" cleanup-test-data.sql` = 0 命中 |
| C-10 | **token E2E 任务闭合口径 + 新发现「跨子任务产出传递截断」缺陷 P-1**（审计 §12.10(2) 记「3 子任务真实执行」，未登记任务本身未闭合） | 隐含「E2E 全绿」 | **token 采集验收已达成**（三通道一致为真，审计方 DB 复核证实），但**任务 `tku-e2e-01-token-usage` 本身未闭合**——子任务 3 被自动评审连续拒绝 4 次 → 死信 → 待人工，任务停在 IN_PROGRESS。**根因非模型能力**：`AgentRuntimeContextAssembler.java:84` `DEP_CONTENT_MAX_CHARS=4000`（执行侧）+ `AttachmentContentPolicy.java:25` `ATTACHMENT_CONTENT_PER_FILE_LIMIT=8000`（核验侧）把上游 22685 字符产出截断，下游物理上拿不到 7 条关键词中的 5 条 → 重派 4 次均在重试「结构性不可能」任务 | 评审 payload 原文含 `[TRUNCATED] file=… shown=8000 total=22685 reason=per_file_limit`；子任务 3 产出自述 5 条「not contained in the material available to this subtask」；`task_timeline` 事件链 `sub_task_auto_review_rejected`×4 → `sub_task_review_dead_letter` → `sub_task_manual_intervention_required`；详见审计报告 §13.2。**⚠️ 本行根因判定（「4000/8000 截断丢 5 条」）与次数（「拒 4 次」）均已由 C-12 / 审计 §15.1 订正**——真病灶为附件选择顺序，实际为「拒 3 次 + 第 4 轮熔断」 |
| C-11 | **MinIO 清桶工具仍缺**（C-9 遗留未解决） | C-9 记「脚本内不含 MinIO 清桶」 | **至今无任何 MinIO 清理工具**——`scripts/` 下仅有 `verify-minio-artifact.ps1/.sh`（**核验**脚本，非清理） | `grep -rlniE "mc rm\|bucket" scripts/` 无清理命中；用户已声明「可强制清理 MinIO 附件数据」，但执行前须先补清理手段（否则 TRUNCATE `attachment` 表后桶内对象成孤儿） |
| C-12 | **P-1 根因精化 + 修复落地**（订正 C-10 根因判定；审计 §13.2 → §14.1） | C-10 记根因 = 「4000/8000 截断把 22685 字符产出截到丢 5 条关键词」 | **根因修正：真病灶 = 附件选择顺序**——sub2 两个 ACTIVE 附件中 `web_search_raw_links_appendix.md`（19294 字符）比主文件（7809 字符）晚 **34.9ms** 创建，`listActive` 按 `create_time desc` 使其排首，消费方取首个可加载附件即 return → 下游拿到的是 appendix（7 条关键词仅 2/7）；**22685 = v1 主文件（已 INACTIVE）**且其 7 条关键词全在首 4000 内（截断不丢）。修复：完整防御三层——A1 三处同源（正序 + 全附件拼接）+ A2-1（行边界截断 + 结构化标注）+ A2-2（重复失败短路），详见 §0.7 | sub3 产出原文自述 "the upstream material attached to this subtask is **the appendix link register**"（直接证认，解除审计「未取到 Prompt 快照」局限）；附件表 `create_time` 07:38:27.440192 / .475050；逐文件关键词位置实测（v1 22685 字符与 v2 主文件均 7/7 < 4000、appendix 2/7）；新增 23 用例 + 全量 core **1614** BUILD SUCCESS |
| C-13 | **MinIO 清桶工具交付**（关闭 C-11） | C-11 记「至今无任何 MinIO 清理工具」 | **已交付** `scripts/powershell/clean-minio-bucket.ps1`——纯 PowerShell SigV4 实现（零外部依赖）；**dry-run 默认**，`-Execute` 才执行批量删除（≤1000/批 + Content-MD5）；`-Prefix` 支持隔离前缀；RFC3986 自定义编码兼容中文 key | 本地闭环实测：临时前缀 PUT 3 对象（含中文 key）→ dry-run 列出 3 → `-Execute` 删除（HTTP 200 / 失败 0 / 复核剩余 0）；远程 dev 实例 dry-run HTTP 200；`scripts/README.md` 已登记 |
| C-14 | **审计 §15 复核发现 2 项修复残留**（A2-2 阈值失效 R1 / 拼接后单点截断 R2） | C-12 记修复「完整防御三层」已落地 | **R1（高）：A2-2 短路层在真实场景不触发**——用 sub3 三轮真实 `issues`（base64 无损导出）复现算法（复现已逐条对上单测 5 个期望值）：全文 Jaccard **0.2537 / 0.1927**、Overlap(min) 0.48 / 0.42、首条 `[defect]` 0.12 / 0.16，**全部 « 阈值 0.85** → tku-e2e-01 仍会走完 3 次重派、346K tokens 照烧。根因：字符 bigram 对长度（真实 928~2753 字符 vs 单测 40 字符）与**指代体系漂移**（三轮分别用 `A2/A3/B1-B3`、`call 1/2`、`R3-R7` 指同一批缺失项）高度敏感。**唯一稳定信号 = `score` 三轮恒 2（未提升）**。**R2（中）：A1 拼接全部附件后仍按单点 `DEP_CONTENT_MAX_CHARS=4000` 截断**——拼接后 ≈27103 字符 → 只保留第一个附件前 4000 字符，**第二个附件永远不可见**（sub3 个案因主文件排首而侥幸解决）。 | 审计报告 §15.3 / §15.4（含复现一致性验证：1.0/1.0/1.0/0.8723/0.0 对上单测）；MinIO `xl.meta` 精确解码：v1 28736B→**22685 字符**（= 评审 `total=22685`）、v2 9943B→7809、appendix 21307B→19294；`review_record` sub3 三轮 score 均 2 且仅 3 条 REJECTED。**建议**：R1 主判据改「连续 N 轮 score 未严格提升」+ 真实语料补回归；R2 改「每附件配额」并在 `[TRUNCATED]` 标注行补 `file=` |
| C-15 | **审计 §15 残留 R1/R2 修复落地**（响应 C-14 两项） | C-14 记「**R1（高）** 短路层真实场景不触发（相似度 0.2537/0.1927/0.1799 « 阈值 0.85）/ **R2（中）** 拼接 ≈27103 字符后仍单点 4000 截断、第二附件永远不可见」+ 建议两项 | **已修复**。**R1 判据反转**：`run noProgress = scoreComparable ? currScore <= prevScore : similarity >= threshold`——两轮 `score` 双可读以「未严格提升」为主判据（不依赖文本措辞），任一轮缺失回退相似度 ≥ 0.85 兜底；skip / deadLetter / manualIntervention 三处 payload 增 `basis`（`score_stall` / `text_repeat`）+ `scoreTrend`（如 `2->2` / `n/a`）；真实形态（2→2）第 2 轮即短路，可省 r3+r4 = **214,175 tokens**（120,624 + 93,551）。**R2 配额渲染**：新增 `UpstreamAttachmentRenderer`（`shared/util`，纯静态）——主附件保底 `MAIN_MIN_BODY_CHARS=1000` + 次要最低 `MINOR_MIN_BODY_CHARS=500` + 逐附件行边界截断 + `[TRUNCATED] file=... shown=... total=...` 机读标注 + 输出总长 ≤ 预算的硬上界（每附件预留 `2×len(name)+84`）；`AgentRuntimeContextAssembler` / `McpToolServiceImpl.getDepsSummary` 两处接入；渲染层 `truncatedCount` / `item.truncated` 口径并入「顶层截断 ∪ 附件标注检测」 | 真实语料夹具 `helloai-core/src/test/resources/review-corpus/tku-e2e-01-sub3-r{1,2,3}.txt`（2753/1526/928 字符，`.gitattributes -text` 锁定逐字符快照）→ 相似度锚点 **0.2537/0.1927/0.1799**（±0.005，与审计 §15.3 复现值一致）；定向 4 类全绿（Assembler 26 / SubTaskReviewService 54 / ReviewFailureSignature 9 / UpstreamAttachmentRenderer 6）+ **新增 11 例**；全量 core **1625**（=1614+11）/ job 85 / api 70，0 失败 BUILD SUCCESS；`McpToolServiceTest` 断言适配 R2（单附件 `attachment-22`：预留 110 → shown=3890/total=5000 + `file=` 标注）。环境备忘：本机 `ms-17.0.19` / `jbr-17.0.14` 的 `lib\modules` 为 0 字节损坏（JVM 启动即崩），跑 mvn 须显式 `JAVA_HOME=C:\Users\史航\.jdks\ms-17.0.20.1` |
| C-16 | **架构收口批次事实登记 + 架构维度专项复核**（2026-10-01，Q1-③） | §0.1 C-12/C-15 只记 P-1 / R1 / R2 修复；架构红线收口未集中登记 | **登记本轮架构收口事实**（详见审计报告 §17）：<br>① **4 条反向依赖清零**（净 8 处 import）——`planner→task.mapper` **1→0**（收口为 `SubTaskService.physicalDeleteByTaskId`）/ `system→task` **2→0**（**端口反转**：`system.port.ArtifactReferencePort` 消费方定义 + `ArtifactReference.java` record 值对象 + `task/service/impl/ArtifactReferencePortAdapter` 实现，`task → system` **顺向**）/ `shared→task` **2→0**（`SubTaskDependencyOrder` **下移** `task/util`；`SubTaskOutputExtractor` 签名 `SubTask` → `Map<String,Object>` **去实体化**，8 调用点 7 文件）/ `shared→agent` **3→0**（`shared/doorbell/*` 7 主类 + 5 测试 **整包迁** `agent/doorbell`；`ExecutionCommandCreatedEvent` 迁 `agent/event`）。`shared` 随之恢复**叶子域**（`shared→planner/review/task/agent/system` 五条全 0）。<br>② **守卫规则集 3 → 20 条**（`planner→agent=34` / `task→agent=45` 沿用；`agent→task=68` 存量标债；其余 17 条为 0 值护栏），`arch-baseline.txt` `--update-baseline` 刷新，接入 `ci-gate.sh` **门禁 3**。<br>③ **基线外**：`helloai-job` 6 类（`AgentHealthCheckTask` / `AssignedSubTaskTimeoutTask` / `ExecutionCompensationTask` / `ExternalAgentFallbackTask` / `PlanningTimeoutTask` / `SubTaskPendingOrphanTask`）去 Mapper 直连 → `helloai-job/src/main` 内 `*.mapper` import **0 命中**。<br>④ **未动**：`agent→task=68`（预案《优先级决策分析》§16 五批 10+19+2+20+17）/ B2 / B3 / B4 / B5 / 上帝类。<br>⑤ **口径订正**：core 用例数当前值 **1628**（C-15 记 1625 系 §15 端口反转批次 +3 之前的快照，非笔误）。`planner→task.mapper` 归零后 `verify-dependency-direction.ps1` 断言层同步；`system→task` 由 `-KnownDebt 1` 收紧为**严格 0**。 | 独立复跑：`bash scripts/ci/check-arch-freeze.sh` → **20 条全 ✅ 持平 / EXIT=0**；`mvn -o -B -DskipTests=false -pl helloai-common,helloai-core,helloai-api,helloai-job -am clean test` → Reactor **6/6 SUCCESS**、`<testcase>` 元素口径 **1783**（core 1628 / job 85 / api 70，failure/error/skipped = 0/0/0，属性口径对照 1144）。逐条比对 HEAD→工作区：`system→task` 2→0、`shared→task` 2→0、`shared→agent` 3→0、`planner→task.mapper` 1→0、`agent→task` 68→68。详见审计报告 §17。

## 0.2 范围漂移补登记与优先级取舍声明（2026-09-29）

> 目的：把审计发现的**未登记漂移**与**未声明取舍**转为书面决策，使「重心偏离」不再停留在「未察觉」状态（审计建议 §7-9）。

### (1) 范围漂移补登记

| # | 日期 | 漂移内容 | 性质 | 当时的登记状态 | 补登记结论 |
|---|---|---|---|---|---|
| S-1 | 2026-09-13 | **联网搜索三连升级**：博查 AI Search 供应商接入 → 单次条数 5→15 → `answer=true` 大模型总结 + Deep Research 多轮补搜 | 业务能力增强，**非任何 GAP 编号** | 当时**未挂 GAP 编号**（G-008 的「web_search 能力目录化」是很晚（2026-09-28）才补的前置登记） | **接受为正式范围扩张**，挂靠 **G-008** 能力目录线（`WebSearchToolCallback` → `ToolCallbackContributor` 端口反转 → 平台工具 `web_search`）；但**须承认**它发生在 P1 剩余项零推进的窗口内（见审计报告 §3.2） |
| S-2 | 2026-09-12 | **基础架构底座（RBAC）** 以《目标架构》§12 **新增章节**方式纳入目标 | 目标边界扩张（原五层不含 RBAC） | 自我声明「与业务五层正交」（`目标架构.md:343-345`） | **「正交」在文档层成立、在工程关注点层不成立**——BASE-4.3 改写了 `AdminOnlyInterceptor` 守门语义并就地修订 `CODE_STYLE §43`，并触及 `McpAuthFilter` 旁路口径。建议把 §12 定义为「**支撑底座 foundation**」而非「与五层正交的第六层」，并把「守门语义变更须回归五层契约」写入红线（见审计报告 §3.1） |
| S-3 | 持续 | `scripts/` 治理成本随脚本资产增长 | 治理成本 | 按「验收口径优先下沉 JUnit/ArchUnit」约定管理（`scripts/README.md` §5.2） | **本批已开始下沉**：CI 门禁与架构冻结守卫落到 `scripts/ci/`，随构建跨平台执行，不再依赖本机不可运行的 pwsh |

### (2) 优先级取舍声明（显式化）

> 审计判定「重心偏离」为**部分成立**：从主线进度看是失焦，从可用性看是必要清偿。以下转为**显式决策**。

**决策 D-2026-09-29-1：MVP 可用性 > P1 剩余项推进（阶段性有效）**

- **依据**：2026-09-24 ~ 09-28 落地的 G-014/G-015/G-016/G-017 **全部是登记在册的 P0/P1 主线项**，且均由**生产实测暴露的真实事故**驱动（外部 Agent 被判离线 → 在飞任务重派 → 打满死信 → DAG 级联卡死；附件 40.8% 悬空；报告永久卡「审核中」）。不修则 MVP 不可用。
- **代价（必须承认）**：P1 剩余的 Sandbox 真实隔离 / Event Recovery·Fork / Quality Gate 统一决策 / Agent Fleet 成本观测，自 2026-09-14 起**零推进**。
- **有效期**：本决策仅在「MVP 可用性问题优先清偿」的前提下成立。**事故清偿告一段落后必须回到 P1 剩余项**，否则「可用但未收敛」会固化为常态。
- **复审触发条件**（任一满足即复审）：① 出现新的 P0 事故；② P1 剩余项连续 2 个迭代未启动；③ 用户明确要求推进隔离/治理类能力。

**决策 D-2026-09-29-2：验证与工程化基础设施优先于功能推进**

- **依据**：审计确认当前最短板不是功能，而是**验证与工程化基础设施**（无 CI、默认跳过测试、0 个真实 DB 集成测试、验证强度集中 C 级）。在此状态下继续堆功能，缺陷只能线上暴露——G-016 宣称「1686 用例 0 失败 PASS」之后仍查出确定性回归（状态机缺 `FAILED→DONE`）即为实证。
- **本批已落地**：CI 门禁 + 可用 JDK 固定 + 架构漂移冻结守卫（见 §0.3）。
- **已落地（同日续）**：Testcontainers 补 B 级集成（4 个 IT + 门禁 5；见 §0.3）。
- **未落地（待排期）**：R2/R3 的 A 级 E2E 补证（M）；见审计报告 §7 建议 6。

## 0.3 本批落地新增事实（2026-09-29）

- **CI 已落地**：`.workflow/helloai-ci.yml`（Gitee Go，对应本仓库远程）+ `.github/workflows/ci.yml`（GitHub Actions 备援）；门禁逻辑在 `scripts/ci/ci-gate.sh`（显式 `-DskipTests=false` + 断言「用例数 > 0」）。此前「无任何 CI」的 P0 缺口已闭合。
- **可用 JDK 已固定**：`scripts/ci/lib-jdk.sh` 黑名单排除本机必然 JVM 崩溃的 `ms-17.0.19`，优先 `ms-17.0.20.1`。此前归因于 JDK 崩溃的 NOT RUN 可复用该脚本一键消除。
- **架构漂移守卫已建立**：`scripts/ci/check-arch-freeze.sh` + `scripts/ci/arch-baseline.txt`，跨域反向依赖（agent→task 68 / planner→agent 34 / task→agent 45）计数**只降不升**。此前「无 ArchUnit、红线靠自觉」的 P1 缺口已部分闭合（编译期 ArchUnit 仍为后续项）。
- **B 级集成测试已建立（2026-09-29 续）且已在本机 Docker 实跑 8/8 全绿**：`helloai-start/src/test/java/com/helloai/it/` 4 个 IT（B1 Flyway 迁移全量 apply / B2 MQ 幂等消费 / B3 Outbox 事务边界+Relay 闭环 / B4 状态机 CAS 并发），Testcontainers PG16/Redis7/RabbitMQ3 与 docker-compose 同版本；`ci-gate.sh` 门禁 5（无 Docker 输出 [NOT RUN]，协作规约 §27 语义）。**首跑排障链**：testcontainers ≤1.20.x 被新版 Docker Desktop npipe 强制 Host 头拒 400 → 升级 2.0.5（依赖改名 testcontainers-postgresql/rabbitmq/junit-jupiter）；webEnvironment NONE→MOCK（HttpServletRequest 构造器注入）；ItTestApplication 排除 HelloAIApplication（@EnableScheduling 泄入竞态）；4 个 IT 测试代码缺陷修复（outbox SMALLINT 枚举 / B4 id 段 9302 / @MockitoBean 计数跨类累计 / relay 轮询触发）。实测：**Tests run: 8, Failures: 0, Errors: 0**（详见 log 2026-09-29 实跑条目）。
- **仍为空白（未闭合）**：R2/R3 的 A 级 E2E 证据（建议 #6 待排期）。见审计报告 §7 建议 6。

## 0.4 审计 §11.4 三项风险响应与 checkpoint 落地（2026-09-30）

> 来源：`doc/review/HelloAI 架构V2进度与质量审计报告（2026-09-30）.md` §11.4；用户决策：风险 2/3 立即修复、风险 1 登记排期（同日实施落地，见下表 R1）。

| # | 风险 | 核实结论 | 处置 |
|---|---|---|---|
| R1 | **逆序执行的代价**（高）：同层兜底已删（`LegacyExecutorAdapter` 不存在），真身 loop 无每轮 checkpoint | **成立**（核实于 2026-09-30 排期时）——`agent/runtime/` grep `checkpoint` 零命中；loop 级失败仅靠子任务级重派链兜底（代价 = 整子任务重跑 + 重派预算） | **✅ 已落地（2026-09-30 当日实施）**：`LoopCheckpoint` / `LoopCheckpointListener`（loop 包）+ `ChatModelToolLoop` 每轮 iteration 边界触发（终态轮不触发）+ `AgentLoopInput` / `AgentContext` 旁路通道 + `AgentSessionService.saveLoopCheckpoint`（merge `snapshot.loop`，零 DDL，无 ACTIVE 会话跳过）+ 恢复段渲染循环进度；保持 V66 边界（不做 LLM 级断点续接、不回放消息历史）。实现详情与测试证据见 `doc/log/2026-09.md` 2026-09-30 条目 |
| R2 | **测试净损失**（中）：`SubTaskExecutionServiceTest` 1346 → 97 行；装配器（688 行）无专属单测 | **成立且比审计描述更严重**——ConsumerTest L80-81 装配器为 `@Mock`，688 行内部逻辑（含全链唯一 fail-close 点）零真实覆盖 | **已修复**：新增 `AgentRuntimeContextAssemblerTest`（607 行 / 5 组 21 例：状态守卫 / prompt 装配 / 恢复上下文 / 凭据 fail-close / best-effort 降级与会话事件），定向 21/21 全绿 |
| R3 | **mockMode 默认值**（低）：代码默认 `true`，仅 yml 覆盖 `false` | **成立**——未来新增 profile / 属性绑定异常会静默走 mock（「看着执行了，其实没调 LLM」） | **已修复**：`AgentExecutionProperties.mockMode` 代码默认改 `false` + javadoc 同步（mock 须显式 `helloai.execution.mock-mode=true` 开启） |

**回归证据**：`mvn -o test -DskipTests=false` 7 模块 BUILD SUCCESS（MVN_EXIT=0）；core **1572** 例（= 1551 基线 + 21 新增）、job 85、api 70，failures=0 / errors=0。
**回归证据（checkpoint 落地后，2026-09-30 当日续跑）**：`mvn test -DskipTests=false` 7 模块 BUILD SUCCESS；core **1585** 例（= 1572 + 13 新增：loop +4 / session +5 / runtime +2 / 装配器 +2）、job 85、api 70、start 8，failures=0 / errors=0；定向 4 类 62 例全绿。
**E2E 实证（2026-09-30，审计 §12.6 #3 闭环）**：内部链路真实任务 `ckpt-e2e-01-inner-loop-web-search`（taskId `2105185642520064002`）3 子任务全 DONE（自动评审 `review_approved` 齐，task 自动关闭）；`agent_session.snapshot->'loop'` 同一子任务跨时刻直查随轮次增长（iteration 1→2→3 / toolCallCount 3→7→10 / messageCount 4→6→8），终态 executedTools 含 `web_search`（2/3 子任务——契约文档子任务按设计不调工具）；`agent_event` 工具计数与 loop `toolCallCount` 对账一致（9/10/5）；复验脚本 `scripts/powershell/verify-inner-loop-checkpoint-e2e.ps1 -AssertOnly` SCRIPT_EXIT=0。明细见 `doc/log/2026-09.md` 2026-09-30 条目。
**口径备忘**：surefire 3.0.2 **txt 报告与 `testsuite@tests` 漏计 @Nested 用例**（实证：装配器 txt 显示 `Tests run: 0`、XML 含 21 个 testcase）——全量用例数以 XML `<testcase>` 数或 Maven 控制台汇总为准。

## 0.5 决策登记（2026-09-30）：不补回退手段

**决策 D-2026-09-30-3：撤销「回退手段」诉求（硬切 = 单向门，确认成立）**

- **背景**：G-002 双轨→单轨硬切后，双轨所提供的「配置级回退开关」（`runtime-enabled=false` 切回旧链）随旧链一并消失。审计报告 §12.5 P5 将其列为「待用户决策的唯一项」。
- **用户决策（2026-09-30）**：**不补回退手段**。理由：该能力本质是「退回旧链」，而本项目为**自研项目，无生产数据、无客户数据** —— 可强制清理数据库业务数据与 MinIO 附件数据，**一切以架构改造最终代码（V2 目标态）为准**，不为旧实现留退路。
- **边界（必须区分，避免误伤）**：
  - ✅ **撤销的是「回退开关」**（切回旧实现的人工/配置手段，需重启部署）；
  - ❌ **不撤销「兜底策略」** —— 自动兜底链（超时重派 `ResilientDispatcher` / 掉线回收 `HeartbeatService` / 死信 `DeadLetterRecoveryService` / 孤儿巡检 `ExecutionCommandPoller` / 租约 `AgentDutyLeaseService`）**照常保留且必须保留**，`be59c3a` 未触及其中任何一个；
  - ❌ **不撤销「同层恢复」** —— checkpoint 每轮落库（§0.4 R1）照常推进；它是「新 Runtime 内的恢复能力」，不是「退回旧链」。
- **后果**：出故障的唯一回退手段 = `git revert be59c3a`（+ 后续提交）。此为「硬切即单向门」的既定代价，与《目标架构》§11 非目标「❌ Dual Executor 永久双轨」完全一致。
- **依据**：审计报告 §12.7（被删 4 文件全文定性：均为「回退能力 + 旧主实现」，非兜底策略）。

## 0.6 tokenUsage 端到端落地（审计 §12.3 P2 清偿，B5 清零，2026-09-30）

> 来源：审计 §11.7 #3「补 tokenUsage（loop 统计 + 落库 / 回传）」/ §12.1 P2 行 / §12.3 P2 段；承接 checkpoint 收口后的「tokenUsage 为下一优先项」。

| 项 | 落地内容 | 证据 |
|---|---|---|
| 数据源 | `ChatModelToolLoop` 每轮读 `response.metadata.usage.totalTokens` 累加（best-effort：metadata / usage / totalTokens 缺失保持原值不阻断；全程无 usage 恒 null） | 定向 4 新增用例（累加 25 / 缺失恒 null / 部分轮只累加存在轮 / MAX_ITERATIONS 携带） |
| 契约 | `AgentLoopResult.tokenUsage`（stop / maxIterations / error 工厂旧签名保留 + 带值重载透传）；`RuntimeTurnExecutor` 成功 / 失败两路径映射 + `AGENT_COMPLETED` payload `tokens` | `RuntimeTurnExecutorTest` +2 例 |
| 落库 | V97 `agent_execution_record.token_usage INTEGER`（IF NOT EXISTS + COMMENT + NOTICE 探针）；markSuccess / markFailed 带 tokenUsage 重载（CAS `status=RUNNING` + `@Version` 双条件不变；旧签名保留委托 null） | 启动实测 Flyway `version 97` + 列存在；E2E 三通道对账 |
| E2E 实证 | `scripts/powershell/verify-token-usage-e2e.ps1 -AssertOnly` `SCRIPT_EXIT=0`；三通道（结构化列 / 事件 payload / `context.lastExecution.tokens`）7 值完全一致（21842 / 101142 / 111375 / 41731 / 90691 / 93551 / 120624）；TIMEOUT 记录 null 安全 | `.tmp/tku-e2e-assert2.log`；任务 `tku-e2e-01-token-usage`（taskId `2105197269487259649`） |
| 回归 | `mvn -o test -DskipTests=false` 7 模块 BUILD SUCCESS；core **1591**（= 1585 + 6 新增）、job 85、api 70，0 失败 0 错误 | `.tmp/tku-verify-full.log`；定向 3 类 35 例 `.tmp/tku-verify-targeted.log` |

**边界（必须区分，避免误读为「成本观测全通」）**：本批清偿的是**平台内部 Runtime 执行链**（进程内 loop）的 token 采集与落库；**外部执行者**（CLI_CLIENT 经 MCP `submitResult`）的 token 回传仍为盲区（提交协议未含 tokens），仍属 G-008 的推进前置（该行「外部执行 tokens=null」暂不变）。

## 0.7 P-1「跨子任务产出传递截断」修复批次（审计 §13.2 响应，2026-09-30）

> 来源：审计报告 §13.2（P-1 缺陷）+ §13.3（口径订正 C-10/C-11）；用户决策四项全做（病灶修复 / 完整防御 / 文档+提交 / MinIO 清桶）。**根因精化**：审计初判「4000/8000 截断丢 5 条关键词」经实施方原始数据复核不成立——真病灶为**附件选择顺序**（见 §0.1 C-12 与审计报告 §14.1）。

| 项 | 落地内容 | 证据 |
|---|---|---|
| A1 病灶 | 三处消费方同源修复：**创建时间正序 + 拼接全部可加载 ACTIVE 附件**（各带 `【文件：{name}】` 分割标题行；单附件失败仅跳过；全失败回退）——`AgentRuntimeContextAssembler` / `McpToolServiceImpl` / `SubTaskCompletionListener` | 定向 +4 用例（多附件正序拼接 / 单附件失败跳过）；修复后注入内容含全部附件且正文在前 |
| A2-1 截断 | `TextTruncator.truncateAtLineBoundary`（行边界回退窗口 512）+ 结构化 `[TRUNCATED] shown=X total=Y reason=...`（shown=实际长度）+ 执行侧指引句（缺失显式声明、禁臆测补全）；接入执行 / 评审 / 报告三侧 | `TextTruncatorTest` 7 例；Assembler 既有断言适配（`已截断至 4000 字符` → 结构化标注） |
| A2-2 短路 | `ReviewFailureSignature`（字符 bigram Jaccard + canonical 规范化，阈值 0.85）→ 相似重复失败**不再重派**直接 `DEAD_LETTER`（`sub_task_auto_review_skip_repeated_failure` → reason=`repeated_failure_signature`）；开关 `autoReviewRepeatFailureShortCircuit` 默认 true | 签名 8 例 + 评审 +3 例（短路触发 / score 提升不短路 / 开关关闭不短路） |
| A4 清桶 | `scripts/powershell/clean-minio-bucket.ps1`（纯 PS SigV4；dry-run 默认；`-Execute` 批删；`-Prefix` 隔离）——**关闭 C-11** | 本地闭环（PUT 3 → 删 3 → 复核 0）+ 远程 dry-run；README 登记 |
| 回归 | `mvn -o -DskipTests=false clean test` 7 模块 BUILD SUCCESS；core **1614**（= 1591 + 23）、job 85、api 70，0 失败 0 错误 | `.tmp/p1-full2.log`（定向 6 类 `.tmp/p1-test5.log`） |

**边界**：A1 为消费方同源修复（未抽共享组件，三处消费语义不同）；`listActive` SQL 排序未动；A2-2 阈值 0.85 宁漏勿错杀（极端重写型 issue 文本可能漏判）；修复后同模式任务 E2E 再验证列入下批。

# 1. 总体矩阵

| ID | 能力 | 当前状态 | 目标 | 优先级 | 处置 |
|---|---|---|---|---|---|
| G-001 | Agent Event Stream | 已有 Run/Turn/Step + Event 基础 + Timeline 并轨（A6）+ Replay/Audit 读侧（A7） | 统一事件契约和消费体系 | **P0** | P0-A 完整闭环（A1~A7 已落地，验收全量成立） |
| G-002 | Executor 迁移 | **✅ 单轨收敛（2026-09-30 硬切）**：实现层唯一化——旧链入口全部删除，`RuntimeTurnExecutor` 为唯一 `AgentRuntime`（`agentRuntimes.get(0)` 直取，无路由无排序） | Runtime 成为唯一执行契约，旧实现退出 | **P0** | ✅ **已收敛：双轨→单轨硬切（2026-09-30，见 §0.1 C-7）**——用户决策「不设灰度、不设退场管理，一次性禁用旧链」（自研项目无生产数据）。**落地**：删除 `RuntimeAgentRuntimeRouter` / `LegacyExecutorAdapter` / `TurnLlmCaller` / `TurnLlmCallContext` + 3 专属测试；新建 `AgentRuntimeContextAssembler`（687 行）承载装配；`SubTaskExecutionServiceImpl` 906→30 行；`LocalExecutionCommandConsumer` 分层编排（startIfNeeded → markRunning CAS → assemble → execute → afterTurn → 终态 CAS → ExecutionResultHandler 回写）；`runtimeEnabled` / `v2-enabled` / `gray-percent` 死配置全部删除。**验证**：全量单测 BUILD SUCCESS（core 1551 例 0 失败，独立复跑 3m12s）；B 级 IT 8/8 绿（B2 MQ 消费走真链路）；**DB 实证单轨路由 `route=agent_runtime` 9/9 命中，零 legacy**。**演进遗留（见 §0.4）**：①**真身 loop 每轮 checkpoint——✅ 已落地（2026-09-30，当日实施）**：`ChatModelToolLoop` 每轮 iteration 边界经 `LoopCheckpointListener` 落 `agent_session.snapshot.loop`（零 DDL；`AgentLoopInput` 旧 12 参构造器保留，调用点零改动），重派恢复段渲染循环进度接续上下文；保持 V66 边界（不做 LLM 级断点续接、不回放消息历史），loop 级失败的轮次级进度自此可观测、可接续（「整子任务重跑」为唯一兜底的口径相应收窄）。实现详情见 `doc/log/2026-09.md` 2026-09-30 条目。②**tokenUsage 端到端落地——✅ 已落地（2026-09-30，B5 清偿）**：loop 每轮累加 `usage.totalTokens` → `AgentLoopResult` / `AgentExecutionResult.tokenUsage` → 终态 CAS 落 `agent_execution_record.token_usage`（V97 迁移），`AGENT_COMPLETED` payload 增 `tokens`；内部链路真实任务 E2E 三通道对账 7 值完全一致（见 §0.6）。以下为历史口径（09-29 订正记录，保留追溯）⚠️（口径订正见 §0 C-1）——2026-09-08 dev 真身联调（RuntimeAgentLoop 点亮）/ 对账全绿 / 回滚零差异 / 外部 Agent 回归通过 / 真实任务全链闭环（外部端到端 14 分钟 5 子任务零故障，见 log 2026-09-08）；但该次灰度系**一次性联调后即回滚**，非持续运行态。**当前生产恒走 Legacy**：`runtimeEnabled` 默认 false（`AgentExecutionProperties.java:76`、`application.yml:196`），`LegacyExecutorAdapter` 恒注册（`LegacyExecutorAdapter.java:33` `@Order(2)`），消费侧构造 AgentContext 不注入 chatModel/prompt → `RuntimeAgentRuntimeRouter` 恒回落；`application.yml:117-121` 的 `v2-enabled: true` 为**零读取死配置**。**本行「目标」列要求「旧实现退出」，与现状自相矛盾，且命中《目标架构》§11 自身列为非目标的「❌ Dual Executor 永久双轨」**。**收敛要求**：设定明确退场时点，或显式登记 WONTFIX 并撤销该目标（见审计报告 §7 建议 4）。**剩余阻断项 B1**（单轨收敛 + Legacy 退场，工作量 L，最高风险）：`RuntimeTurnExecutor.java:65` 硬校验 subTaskId/agentId/chatModel/prompt，真身 loop 每轮 checkpoint 已落地（2026-09-30，见演进遗留①）、tokens 统计已落地（2026-09-30，见演进遗留②） |
| G-003 | AgentRuntime | 八件套已全部落地（Context / Session / Skill / Tool / Loop / Event / Environment / SandboxProvider 契约） | Context + Session + Skill + Tool + Loop + Event + Sandbox | **P0** | P0 完整闭环（P0-A/B/C 收官）；真实 provider tool-calling 循环 2026-09-08 联调通过，无边界问题 |
| G-004 | Skill Capability | SkillPackage 元数据层已落地（name/version/description/requiredTools/dependencies/inputSchema/outputSchema/validationRules，3 个 eng-* 已结构化）+ **requiredTools→tools 联动已接**（Legacy/Runtime 双链 mergeTools 并集去重保序，TOOL_RESOLVED 前合并）+ **SKILL_RESOLVED 携带 resolvedVersions**（Replay/前端按字段投影兼容）+ **拆解技能通路已接**（task.required_skills → 拆解 Prompt 注入，规划/验收与技能规范对齐；增量 B） | Metadata / Version / Tools / Schema / Dependencies 全量 + **requiredTools→tools 联动** + SKILL_RESOLVED 携带版本 + 真实任务行使（任务级创建/拆解/派发/执行四段已贯通；带 required_skills 真实任务实测已于 2026-09-10 完成，见处置列） | **P1** | 元数据层（ed14e40 / 234bed4）+ 联动接线（增量 A）+ 拆解技能通路（增量 B，全量 1295 单测 0 失败）落地；真实任务带 required_skills 端到端实测已于 2026-09-10 完成（Round2 全任务 eng-doc-standard 硬门槛准入 + Round3 技能分布派单与 135:20 分排序实证，见 log 2026-09-10）；**契约层统一技能注入（增量 C，2026-09-28）**：AgentTask.skills 承载 + PlatformAgentExecutionService.execute/executeStream 统一注入（checkCapability 后、调执行器前，skills 空不注入行为零变化）；5 类同步 LLM 调用挂点同源（子任务执行/执行流/拆解/审查收口/报告）；新技能包 eng-web-research（requiredTools=[web_search]，复用联动） |
| G-005 | Sandbox Provider | 已有 Environment / Provider + SandboxProvider 契约（诚实策略，无 ISOLATED） | 真正 Provider 化执行环境与隔离策略 | **P1** | 契约已落地；Docker/K8s 隔离能力后置 |
| G-006 | Replay / Audit | 写侧+对账闭环；Timeline 已暴露（API+UI）；Replay/Audit 读侧 service 就绪；**Replay/Audit API 已暴露**（增量 C1：helloai-api AgentEventController——GET /api/agent-events/traceByRunId/{runId} + GET /api/agent-events/pageAuditByTaskId/{taskId}，API 层 DTO 投影 + ControllerTest 5 用例）+ **UI 工作台已上线**（/event-stream 事件流：Replay 轨迹时间线 + Audit 分页表格 + eventType 过滤 + payload 原文折叠；事件字典抽离 utils/eventMeta 与 SubTaskDetail 时间线同源共享）+ **增量 D**（traceByTaskId / traceBySubTaskId 任务/子任务维度端点 + 工作台选择器 / Agent 名称解析 / payload 结构化展开 / 事件流深链） | 基于统一 Event 查询/回放；**外部执行轨迹对齐**（外部路径 agent_execution_record 0 行、事件仅完成态，Replay 时外部任务仅「派发→完成」细线） | **P1** | 增量 C1 落地（2026-09-08）：Timeline ✅ / Replay ✅ / Audit ✅（API+UI，api 56 单测 + type-check/build 全绿）；增量 C2 落地（2026-09-08）：外部认领埋点 AGENT_STARTED + Replay run 级汇总卡（core 799 单测 + type-check/build 全绿），外部轨迹加厚为「AGENT_STARTED → AGENT_COMPLETED → REVIEW_STARTED → REVIEW_APPROVED」四事件；增量 D 落地（2026-09-10）：任务/子任务维度查询端点 + UI 工作台增强（选择器 / 名称解析 / payload 展开 / 深链） |
| G-007 | Quality Gate | Reviewer 闭环已存在 | Rule + Test + LLM 统一决策 | **P2** | 现有链上增强 |
| G-008 | Agent Fleet Routing | 已有 Agent 选择机制；**多外部执行者同台已实证**（2026-09-10 Round3：双执行者背靠背竞态 231ms 唯一赢家 / 技能硬门槛内按分排序 / 2 执行者 3 子任务并行持有）；外部执行 tokens=null（成本观测盲区，submitResult 未回传） | Capability + Health + Load + Policy | **P2** | 渐进升级；多外部执行者对照场景已于 2026-09-10 验证完成（见 log）；token 回传（成本观测）仍为推进前置；**web_search 能力目录化前置登记（2026-09-28）**：联网搜索 Capability 化——planner 域 WebSearchToolCallback 经 ToolCallbackContributor 端口反转注册为平台工具 web_search（McpToolConfig 单 bean 收集合并）+ 技能包 eng-web-research（requiredTools 联动）+ ClarifyWebSearchOrchestrator 按工具名调 ToolExecutor.execute（失败空列表降级，webSearchEnabled 会话开关保留）；WebSearchService 直调路径保留至端到端验证通过后删除 |
| G-009 | Dynamic Workflow | 已有模板/实例化/DAG | 动态分支、复杂运行期编排 | **P3** | 后置 |
| G-010 | Planner 能力感知与自适应粒度 | **S1~S4 已落地（2026-09-09）**：S1 数据层（V74 sub_task.required_skills JSONB + constraints TEXT）+ S2 拆解侧（技能目录常驻注入 / 子任务级 requiredSkills+constraints 指派 / 目录过滤 task_plan_skill_filtered 审计 / rule-based 粒度三档 FINE/STANDARD/COARSE + 目录超 20 项截断）+ S3 传递链（mergeSkills 并集装箱 5 装箱点同源 / inbox 技能要求行 / REST 下行 / 草案确认 UI 展示编辑 + updateDraftById 端点 / SKILL.md 增量） | 技能目录注入拆解 Prompt + 子任务级 requiredSkills/constraints 指派（V74 新列，并集装箱）+ 粒度三档 FINE/STANDARD/COARSE 自适应（**rule-based 决策矩阵**：执行者画像 × difficulty 调制，2026-09-09 拍板）+ 外部感知下行通道（可选字段向后兼容）+ 技能回流贡献规范 | **P1** | S1~S3 PASS（2026-09-09，见 log）；S4 实测平台内链 PASS（2026-09-09，与 G-011 S5 合并执行）——verify-login-e2e 10 项 / verify-requirement-clarify 10 步 / verify-planner-decompose 12 步 EXIT=0（登录 → 澄清 → 终稿 → 建任务 → AI 拆解 → 确认/拒绝闭环）；外部执行链（外部 EXECUTOR agent 场景）已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log）；**后置缺口**：①技能回流贡献规范（D5-3 DB 化）②verify-skill-packages.ps1 校验脚本（D5-2 未交付）；③审查侧 constraints/requiredSkills 注入核验（D4 验收口径）④COARSE 档 constraints 缺失的 timeline WARN 级事件（设计 §2.3 承诺）已由 G-011 S3/S4 清偿（2026-09-09）；**P0「打通下发」补强（2026-09-11）**：claimSubTask 返回体内联子任务全文（content/deliverable/acceptance/constraints/uncertainties/requiredSkills）+ 新增 getSubTaskDetail 工具（三通道 12 工具对齐；V76 seed + 默认 12 + 懒启用三重兜底）+ inbox sub_task.assigned 摘要升级为「交付物 + 验收标准 + 约束 + 待确认正文」结构化文本；纯增量向后兼容，66 相关单测全绿（新增 1 处 agent→task entity 只读引用已按 CODE_STYLE §6.1 显式豁免，记录见 SubTaskDetail Javadoc）；**P2-3（2026-09-11）**：COARSE 档 acceptance 补「与交付物的可观察判定方式（判定动作 + 预期结果）」（不动 STANDARD/FINE 两档） |
| G-011 | 需求包准入与不确定性显式管理 | **S1~S5 已落地（2026-09-09）**：S1 数据层（V75 双列 sub_task.uncertainties JSONB DEFAULT '[]' + requirement_conversation.final_package JSONB + 双实体字段 + Uncertainty 值对象落 task 域避反向依赖 + RequirementPackageParser 防御式静态工具）+ S2 澄清侧（两处终稿提示词 package 结构化五字段 + ClarifyReply.package JsonNode 防御承接 + updateFinalDraftFields 条件覆盖写 + buildTaskFromDraft 双写）+ S3 拆解侧（模板四增量 + uncertainties 落库归一 + COARSE constraints 缺失 WARN + D5 兜底审计）+ S4 传递链（执行注入 D6 三段 / 审查核验 D7 双占位符 + 轨道 A 第 8/9 条 / REST 下行四参 updateDraft / inbox 待确认行 / 草案 UI 不确定性列）+ S5 实测（平台内链 PASS，与 G-010 S4 合并执行） | 结构化需求包（goal / scope / outOfScope / assumptions / openQuestions，会话列 + task.context 双写）+ 拆解继承为 sub_task.uncertainties[ASSUMPTION|UNCONFIRMED] 显式 JSONB 列 + 执行侧注入（含 constraints 补偿）+ 审查侧分级语义（假设不成立 ≠ 执行缺陷）+ 外部下行可选字段向后兼容 | **P1** | S1~S4 PASS（2026-09-09，见 log：V75+实体+解析器 10 单测 / 澄清侧 / 拆解侧 / 传递链各增量，core 全量单测 1359 用例 0 失败，api 编译 + UI type-check 通过）；S5 实测（2026-09-09，与 G-010 S4 合并）：平台内链 PASS——澄清终稿 final_package jsonb 真实落库（jsonb 定点写 bug 修复：Mapper+XML ::jsonb 条件写，RequirementClarifyServiceTest 92 用例全绿）+ 确认卡 selections 协议核验 + 异步拆解轮询适配（360s 窗口）；外部执行链已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log：审查者引用 uncertainties 申报作驳回依据实证）；**不建自动闸门**（openQuestions 不阻断，人工裁决 + fail-close BLOCKED 链上报）；gap_kind 实现路径分类与任务后蒸馏闭环后置批次二/三；技术债：Agent 注册幂等顺序缺陷仍在（validateModelType 先于 registerOrGet；配套的 api_key_hash 落库缺列已于 2026-09-10 修复）；配套修复：JSONB uncertainties CCE 死锁（inbox 通知静默失败 → 外部链死锁根因，2026-09-10）；**uncertainties 正文下行（2026-09-11，P0「打通下发」）**：ASSUMPTION / UNCONFIRMED 逐条 note 随子任务全文（摘要 + claimSubTask 内联 + getSubTaskDetail）直达执行者，分级后缀与执行/审查侧同源；**P1/P2 批次（2026-09-11，生成能力退化修复）**：①P1-1 需求包补回任务级 acceptanceCriteria（六字段，设计 §7#11；#2 决策反转已同步登记，JSONB 加键零迁移）+ planner-decompose「任务级验收覆盖」规则（封闭集合全覆盖，无需求包任务不做回溯要求；提示词硬约束 + 人工核验，不做 fail-close）②P1-2 拆解四必填字段（title/content/deliverable/acceptance）缺失即整批 BizException + timeline `task_plan_draft_field_missing` 审计（设计 §7#14）③P1-3 终稿四小节关键词组校验 + 单次重试 + fail-open（timeline `requirement_description_section_missing`）+ 两提示词 description/package 职责写死（设计 §7#13）④P2-1 主任务详情弹窗展示需求包六字段（后端零改动，无需求包整块隐藏）⑤P2-2 verify-requirement-clarify-structured 扩至「澄清 → 终稿」软断言（小节组 ≥3 / 长度 ≥300 / final_package 存在）⑥P2-3 COARSE acceptance 可观察判定方式⑦P2-4 审查 missingEvidence 缺失证据清单（轨道 A 第 10 条 + reviewHistory 加键 + 返工 inbox 摘要携带，设计 §7#17）；**无 DDL 迁移、无新增 agent→task 依赖**；单测 core 全量 1380 + api 58 用例 0 失败，UI type-check 通过 |
| G-012 | 登录鉴权与 RBAC 权限体系（Sa-Token） | **底座 + 闭环已落地（2026-09-12）**：登录会话由自建 Redis Token 切换为 Sa-Token（token 走 X-Admin-Token 头，active-timeout 8h 滑动续期）；授权从 AdminOnlyInterceptor 前缀二元判断升级为「角色-权限码」RBAC（V77 四表 + 内置 SUPER_ADMIN/ADMIN + 存量用户 role 迁移 + StpInterfaceImpl + @SaCheckPermission）；管理侧 API（角色 CRUD / 角色-权限绑定 / 用户-角色分配 / 权限码列表）+ 登录响应携带 permissions/roles + 前端菜单按权限码动态过滤 | 自建登录模块 → Sa-Token 统一会话 + 用户/角色/权限码管理 API + 接口注解鉴权 + 前端动态菜单；后续可按需做菜单树建表与页面化管理 | **P2** | 底座 S1~S4 PASS（2026-09-12，见 log）：V77 迁移 + 四实体/四 Mapper + 角色服务 + 权限查询服务 + 三管理 Controller + SaInterceptor 注解鉴权 + NotLogin/NotPermission 异常归一；单测 SysRoleServiceImplTest 6 + SysPermissionQueryServiceImplTest 5 + AuthServiceTest 10 = 20 用例 0 失败，core 全量 1393 用例 0 失败；UI type-check 通过；V77 在 dev 库事务回滚验证 PASS（2 角色 + 23 权限码 + 20 角色权限 + 存量用户迁移）。**后置缺口（原登记三项已全部关闭，2026-09-12 同日闭环）**：①角色/权限/用户管理前端页面（S5 PASS，三页 + 路由 + rbac.ts）；②存量会话无缝迁移（S2 PASS，原 token 重建会话删旧 key，Docker 实测）；③菜单树 DB 化（S6 PASS，MenuController /api/admin/menus/tree + MainLayout 动态渲染）；配套基础设施修复 S7 PASS（分页 total 恒 0 根因二连）。**深化项已转 BASE 专项（见 G-013）** |
| G-013 | 基础架构深化（RBAC 底座，参考 JeecgBoot） | **批次一/二/三全部落地（2026-09-12）**：①前端动态路由（权限=路由可达性，无权限 URL 404）；②v-auth 按钮级权限；③动作级权限码；④菜单树携带 component 驱动动态 addRoute；⑤可视化菜单/权限管理页（树形 CRUD）；⑥角色授权差异更新；⑦**路由渲染增强**（隐藏菜单/页面缓存/外链）；⑧**部门/岗位组织架构**（部门树 + 用户-部门/岗位多对多 + 两管理页 + 用户「组织归属」分配）；⑨**数据权限规则**（受控枚举 ALL/DEPT/DEPT_AND_CHILD/CUSTOM，作用于用户列表可见范围） | 对齐 JeecgBoot 标杆：权限 = 路由可达性 + v-auth 按钮级权限 + 动作级权限码 + 菜单树携带 component 动态 addRoute + 可视化菜单树 CRUD + 角色授权差异更新 + 渲染增强/组织架构/数据权限 | **P1** | **三批次全部 PASS（2026-09-12，见 log）**：BASE-1.x（V79 component+动作码 / V80 path 补丁 / SysPermissionService CRUD + DTO 投影 / 白名单守卫动态路由 / v-auth / 菜单管理页 / 授权差集）；BASE-3.1（V81 hidden/keep_alive/external_link + 前端渲染适配）；BASE-3.2（V82 部门/岗位四表 + 8 权限码 + SysDepart/SysPosition Service+Controller + 用户组织归属 + DepartList/PositionList 页）；BASE-3.3（V83 rule_flag + 数据规则表 + 受控枚举解析 + 用户列表数据权限 + 规则配置 UI）。**实测修复 5 个缺陷**：SaInterceptor 未注册（G-012 疏漏）/ addRoute 父参数须为 name / 登出后路由权限串用 / **catch-all redirect 导致登录后 404**（改直接渲染 NotFound）/ **会话失效静默落 404**（HTTP 401 未清态 → 补 request 拦截器清登录态 + 守卫跳登录页）。**验证**：core 1444 用例 0 失败 + UI type-check/build + Docker 全链路 + 浏览器实测（SUPER_ADMIN 23 菜单含部门/岗位页；ADMIN 17 菜单且 `/system/departs` 404；数据权限 CUSTOM 过滤生效）；既有 PS1 本机无 pwsh → NOT RUN（等价断言 PASS） |
| G-014 | 外部 Agent 执行通道缺陷修复（依赖门禁 / 返工出口 / 附件可发现 / 截断元数据 / 在线语义 / 核验边界） | **P0-1、P0-2 与 P1-3~P1-7、P2-8、P2-10 已修复（2026-09-24）**：①依赖门禁——`listAvailable` 批量就绪过滤 + `claimSubTask` 复用 `isReady`（新增 reason `dependency_not_ready`）；②返工出口——新增 MCP `startSubTask`（ASSIGNED/REWORK/PAUSED→IN_PROGRESS，归属校验 + 幂等），`DEFAULT_EXECUTOR_TOOLS` 12→13，状态机补 `REWORK→BLOCKED`（返工途中可上报阻塞）；③`getDepsSummary` 增 `ready`/`notReadyCount`（与 `degraded` 正交，区分「前置未就绪」与「采集异常」）与 `DepItem.loaded`/`contentChars`；④`SubTaskDetail` 内联 `attachments`（含 attachmentId，附件可发现）；⑤核验侧截断输出结构化 `[TRUNCATED] file/shown/total/reason` 标注行 + 核验 Prompt 增「不可见内容不得补全」条款；⑥`SubTaskDetail.contributors`（产出归属可见性）；⑦在线判定引入 ACTIVE 值班租约作为存活证据（心跳过期不直接判 OFFLINE）；⑧`renewLease` 到期时刻单调钳制；⑨JSON-RPC `tools/list` 全量补 `required` + `checkIn` 补 `skills` | 外部 Agent 通道与内部分发链同口径：依赖/技能约束一致、返工可自救（不依赖人工放行）、前置产出与附件可发现可读、核验结论只基于可见证据、在线判定不误伤在岗 Agent | **P0** | 按 8 任务落地（T01~T08）：T01 依赖门禁 / T02 返工出口 / T03 结构化元数据 / T04 附件可发现（功能性）/ T05 在线语义 / T06 核验边界 + contributors / T07 schema / T08 租约单调。**已做（口径订正见 §0 C-2 / C-3）**：~~T04b 附件读端归属授权~~ ✅ **已完成**——`AttachmentController.java:124-146` 实测存在 `_authType=="agent"` 归属比对 + `BizException(403,"无权访问…")`，四端点（`:52/65/79/106`）全部接线；`SubTaskController.java:113/129` 的 `listTimeline` / `listConversation` 亦调 `assertSubTaskReadableByAgent`；配套单测 `AttachmentControllerAuthScopeTest`（平台账号放行 / 归属者放行 / 非归属者 403 三态）；commit `5f48a64`（2026-09-24）。**原「任意有效 Agent Key 可读他人子任务附件正文 / 核验 Prompt」的越权读风险在代码层已闭环**（验证强度 C 级：单测，E2E 未跑）。~~T08b `SKILL.md` 与 `doc/manual/executor-duty/` 文档一致性~~ ✅ **部分已完成**——`helloai-core/src/main/resources/onboarding/executor/guide.md` 含 `startSubTask` 9 处（返工出口文档已同步）；`doc/manual/executor-duty/` 限额章节口径（2000→4000 / blocked 日志承诺 / startById 指引）待复核。**验证**：单测批次全绿（McpToolService 45 / SubTaskStateMachine 12 / SubTaskReviewService 46 / HeartbeatServiceActive 12 / McpControllerJsonrpc 16 / AgentDutyLeaseService 17，均 0 失败）；**✅ E2E 已实跑（2026-09-30 订正，见 §0.1 C-8）**——**原「NOT RUN（本机无 Docker / PG 客户端且 `ms-17.0.19` JVM 崩溃，需服务器复验）」的阻塞已解除**：JDK 固定（`lib-jdk.sh` 黑名单 + 实测大版本）与 Docker 环境到位后，以**真实外部 CLI_CLIENT Agent 端到端 A 级实测**替代脚本级抽验——5 任务 15 子任务全 DONE（同名去重不重发）、技能 AND 匹配派单、评审真实拦截 3 类交付缺陷并返工闭环、心跳掉线回收 + 退避重派无死信；DB 独立复核：`sub_task_dispatch_prepare`×29 / `sub_task_unclaimed_timeout_reassign`×3 / `agent_offline`×4 / `sub_task_auto_review_rejected`×6。脚本 `verify-external-agent-e2e.ps1`（含 `-AssertOnly` 回查模式）。**仍待补**：`verify-tool-matrix.ps1` / `verify-mcp-e2e.ps1` / `verify-agenthub-duty-e2e.ps1` 的**脚本级全量矩阵**复验（当前由真实 Agent 端到端覆盖主链，但 13 工具矩阵与租约在线语义未逐项断言） |
| G-015 | 外部 Agent 心跳/重派止血（B1：误判离线 → 在飞任务被重派打满死信） | **B1 止血已落地（2026-09-26）**：①写侧值班租约守卫——`AgentHealthCheckTask.processStaleAgent` 在 CAS 标 OFFLINE 前先判 `AgentDutyLeaseService.isOnDuty`，持 ACTIVE 租约即跳过离线处置，与读侧 `HeartbeatServiceImpl.checkOnlineStatus`「租约 ACTIVE → IDLE」同口径；②在飞子任务宽限——持 `ASSIGNED/IN_PROGRESS` 子任务时改用 `AgentHealthProperties.inFlightGraceMinutes`（新增，默认 30min，`max(grace, offlineMinutes)` 决定扫描窗口）计算 CAS cutoff，阈值放宽而非取消；③只读/登记类工具（pullTasks/ack/startSubTask/uploadArtifact/reportBlocked/getAgentStatus/getDepsSummary/getSubTaskDetail）在 `refreshDutyLease` 持租约时顺带 `seen()` 刷 `last_seen_time`（heartbeat 工具已显式 `seen()` 故传 `true` 避免重复双写）；④「租约 ACTIVE + dbOnlineStatus OFFLINE」双视图分裂随 ①③ 收敛。**无新增 Flyway 迁移、不改状态机、不加新枚举** | 外部 Agent 埋头执行长任务（数分钟不触网）不被 5min 心跳窗口误判离线；判离线的读/写两视图口径统一 | **P0** | **B1 PASS（2026-09-26）**：编译 SUCCESS；单测 `AgentHealthCheckTaskTest` 17（含新增 G-015 B1 守卫 5 例：持租约跳过 / 在飞宽限 cutoff / 常规 cutoff / 租约查询异常 fail-close / 在飞查询异常 fail-close）+ `McpToolServiceTest` 47（含新增 seen 刷新 2 例）+ `HeartbeatServiceActiveTest` 12，均 0 失败。**B2~B4 已落地（2026-09-26）**：B2.1 离线重派补偿路径改走 `dispatchPendingSubTaskCompensating`（复用闸门但不重复计数，单轮最多 +1 而非 +2）；B2.2 重派退避 `REASSIGN_BACKOFF_SECONDS={60,180,600,1800}`（退避时刻取 `sub_task.update_time`，零新增列/context 键）；B2.3 离线在飞 IN_PROGRESS 子任务置 PAUSED 保留归属（不消耗重派预算）+ `reclaimExpiredPausedTasks` 在 CAS 前回收「PAUSED 超宽限」任务（经 `redispatchInProgress` 改派链）；B3.1 `redispatchDeadLetter` 补清 `dead_letter_reason`/`attempt_total`/`max_reassign_attempts` 残留 context 键；B3.2 修 `startSubTask` 越权分支回显自相矛盾（改为回显真实归属）；B4.2 REST 双通道（直通 + JSON-RPC）透传 `skills`，`resolveSkills` 兼容数组与 CSV（`mergedSkills` 不再恒 null）；B4.3 **V94** 新增 `agent_duty_lease.ttl_minutes` 并持久化签发窗口、空闲续约复用该值（消除 P2-10 窗口跳变）；B4.4 附件上传按「无归属 409 / 归属他人 403 / 子任务不存在 404」语义化报错（原单参 BizException → 500 误导）。**未做**：B4.1/B4.5 的 `SKILL.md` + `doc/manual/executor-duty/` 文档同步（P1-4-c 8000 每附件 / 24000 总 / `output` 同受 8000；`attachmentId` 字段名）。**验证**：`clean test` 全量 **PASS**（core / job / api 三模块 BUILD SUCCESS）；**✅ E2E 已实跑（2026-09-30 订正，见 §0.1 C-8）**——**B1 止血的有效性已获 A 级实证**：真实外部 Agent（TeleAgent）04:04–04:20 UTC 断线 → 其认领的 3 个子任务被回收（ASSIGNED）→ 退避窗口 `REASSIGN_BACKOFF_SECONDS` 第 3 档 600s 到期后自然重派 → 心跳恢复后正常接单，**无死信（`maxReassignAttempts` 未打满）**；DB 证据 `agent_offline`×4 + `sub_task_unclaimed_timeout_reassign`×3。原「E2E 与生产复测 NOT RUN（需部署 + PG/Docker）」已解除。**残留口径**：在飞续约仍用 `maxTtlMinutes(240)` 保活（E1 既定设计），仅体现在 `expire_time`，不污染 `ttl_minutes`；「无候选空烧预算 + NO_ELIGIBLE_AGENT 独立告警」未做，登记为后续观察点 |
| G-016 | 报告整合质量 | 阶段二/三/四已完成（2026-09-28）：报告读取与子任务链同口径（物化附件优先 + ExecutionRecord SUMMARY/DELIVERABLES 注入 + output 兜底）；附件限额/族判定单源（AttachmentContentPolicy 上移 shared）；Markdown 块级截断 + 超限标注（[SUMMARIZED]/[TRUNCATED]，删「以已提供部分为准」字样）；3B 报告 Prompt 目标重写（读者与用途/篇幅预算/主线论点/覆盖追溯表/冲突矛盾显式输出）；3C 大纲先行两段式（出纲失败降级单次调用）；3A 核验/返工闭环（TaskFinalReportGeneratedEvent + FinalReportReviewListener + rework + 自审自过硬守卫 review_skipped）；§12 遗留三项（2026-09-28）：**V95 单槽列**（final_report_prev/_prev_agent_id/_prev_time 三列，覆盖前 setSql 落槽，rollback 端点 current↔prev 整体互换可反复切换，无 prev/在途 409）+ **审查异步化**（REVIEWING 状态 + reportReviewExecutor 专用池 AbortPolicy + 事件 reportTime 微秒截断 + 三重陈旧守卫（审查前/rework 前/收敛条件写回）+ 6 出口收敛 DONE + 开关 autoFinalReportReviewEnabled）+ **轮次去状态化**（rework 3 参显式传轮次，删进程内 Map 计数） | 报告按主题归并有覆盖追溯，与执行链同口径读事实源；生成后自动核验、驳回可返工；自审自过不静默放行；审查不阻塞前端请求；历史报告可恢复上一版 | **P1** | 阶段二~四 PASS（2026-09-28）：编译 7 模块 BUILD SUCCESS；TaskFinalReportServiceTest 36 + FinalReportReviewListenerTest 19 全绿；core 全量 **1546 用例 0 失败**（`.tmp/core-full-test-r12.log`）；前端 type-check 通过；依赖方向 verify 10/10 PASS；ui-sync Channel B 0 违规（Channel A 的 1 项为既有脚本正则盲区，非本批引入）。**附注**：附件限额/族判定口径已单源（AttachmentContentPolicy）；装配渲染层归并为可选优化；**§12.5 三级容错补齐（2026-09-28）**：FinalReportStateMachine 状态机收口写口 + FinalReportPersistService 事务边界（报告写回 / agent_outbox_event / L1 事件三者原子，消除双写丢失）+ L1 改 @TransactionalEventListener(AFTER_COMMIT) + L2 helloai.report-review.queue 幂等消费（eventId 幂等键，AgentEventCompensationTask 15s 补投）+ L3 FinalReportReviewOrphanTask 巡检（REVIEWING 超 300s 收敛 DONE，刻意不重投审查）+ 前端 FinalReportDialog 轮询修复（收敛后未 emit status-change 致列表行永久卡「报告审核中」的直接根因，改为逐轮广播 + 关窗补发）；core+job 全量 **1686 用例 0 失败**（新增状态机 5 / 事务边界 3 / L3 巡检 6 例，FinalReportReviewServiceImplTest 22 / TaskFinalReportServiceTest 36），UI type-check 通过。**§12.5 已知遗留与修复（代码审查发现，详见改造方案 §12.5.5）**：✅#1（已修）状态机缺 `FAILED→DONE` + `rollback` 断言在 CAS 之后 → 补迁移 + 断言前移到 CAS 之前 + CAS 改 `eq(from)` 与断言同源 + `GENERATING` 前置拦截（消除「FAILED 且 prev 非空点『恢复上一版』500 且库已静默改」确定性回归）；✅#2（已修）`generateWithAttempt` 断言用陈旧快照 → 前置拦截 + 断言前移 + CAS 改 `eq(from)`，对齐 `transitFinalReportStatus` 正确范式（消除并发下永久卡 GENERATING）；✅#3（已修）L3 孤儿巡检不抢防双审锁 + 阈值 300s<锁 TTL 600s → 抽 `FinalReportReviewLock`（键前缀 + `TTL_SECONDS=600` 唯一事实源，L1/L2/L3 共用）+ `converge()` 抢同款锁抢不到即跳过不收敛 + 阈值 300→660（`TaskServiceImpl` 兜底默认同步）；✅#4（已修，2026-09-29）AFTER_COMMIT 兜底落库未声明 REQUIRES_NEW（池饱和时收敛/审计可能丢）→ 下沉 `FinalReportReviewFallbackWriter`（`convergeToDone` / `recordSkippedAndConverge` 两方法声明 `REQUIRES_NEW`），AFTER_COMMIT 发布线程的兜底收敛与 `review_skipped` 审计经独立事务确定提交，池饱和不再丢（同 `ExternalAgentFailureTracker` 模式，规避私有方法自调用不走代理）；✅#5（已修，2026-09-29）L2 抢锁失败被误判消费成功并 ACK（L1 崩溃后无重投）→ `reviewInternal` 抢锁失败/中断/锁异常统一抛 `ReviewNotExecutedException`：L1 `reviewQuietly` 捕获记 debug 静默跳过，L2 向上传播经 `tryConsume` markFailed + `basicNack(requeue=false)` 入死信台账可重放；已抢到锁后 `doReview` 异常仍就地吞掉（避免重投重复烧 LLM）；前端列表兜底轮询 #6 keep-alive 退出未停表 / #7 无失败退避为可选精修。**#1~#5 已全部修复（#1~#3 见 2026-09-28 批次；#4/#5 于 2026-09-29 修复，全量 core+job+api `mvn -o test -DskipTests=false` **1756 例 0 失败 0 错误** BUILD SUCCESS，新增 FallbackWriter 委托 2 例 + L2 抢锁失败抛出 / 锁后异常吞掉 2 例回归）；前端 #6/#7 可选精修仍登记待修。** |
| G-017 | 附件存储一致性（产物对象 ↔ attachment 记录对账） | **存储抽象与对账巡检已落地（2026-09-28）**：ArtifactStorage 契约（Local/Minio 双实现 + Composite 按 type/URL 前缀路由；exists fail-open / listObjects fail-safe / removeObject fail-close 默认策略）；`AttachmentServiceImpl.register` 前置校验（storageUrl 必填 + validateAddress + supports 时 exists 校验，不存在 400 拒绝，@Transactional 回滚）；对账巡检 `ArtifactStorageReconcileTask`（6h fixedRate + ShedLock PT20M + reconcile-enabled 开关——attachment 全量含逻辑删除 ↔ 桶内对象双向比对，悬空/孤儿/字节不符三态）；孤儿清理默认关闭 + 三重保险（开关 + 24h 时间窗 + 单轮上限 200）；MCP `uploadArtifact` 描述重写（storageUrl 格式固定 + bucket 白名单 + 错误示例 + 整合子任务复用上游引用）；dev 端点配置已覆盖（R1） | DB 与对象存储两侧一致；悬空/孤儿可发现可报告；不再产生僵尸附件；孤儿清理受控有保险 | **P1** | R1/R3/R4/R5 处置落地（2026-09-28）：storage 定向测试 **72 用例 0 失败**（Composite 13 / Local 8 / Minio 20 / 对账 13 / AttachmentService 18，`.tmp/test-storage-r12.log`）；R2 缓解（对账可见可报告，历史 106 条悬空不自动修复）；R6 文档面引导（描述复用上游引用）；R7 部署侧安全动作待用户执行（改 MinIO 默认凭据 + 29000/29001/15432/26379/25672 端口收安全组白名单） |

# 2. P0 主线

## G-001 Event Stream

验收：

- Legacy 与 Runtime 产生同一 Event Model；
- Timeline / Audit 逐步统一从 Event 获取事实；
- 一个 Run 可以按 sequence 重建轨迹；
- Event 不成为第二业务状态源；
- 写入具备幂等和可对账能力。

## G-002 Dual Executor

原则：

> Dual Executor 只是迁移策略，不是长期架构。

```text
ExecutionRouter
      ↓
 ┌────┴─────┐
 ↓          ↓
Runtime    LegacyAdapter
```

禁止：

```text
复制完整业务链
复制第二套状态机
复制第二套 Review
无幂等地再次执行副作用
```

**落地状态（2026-09-30 硬切单轨，C-7）**：上述迁移结构图已随旧链删除而失效——`RuntimeAgentRuntimeRouter` / `LegacyExecutorAdapter` / `TurnLlmCaller` 及全部旧链入口已删除，`RuntimeTurnExecutor` 为唯一 `AgentRuntime` 实现，消费链路全部真实经 `AgentRuntimeContextAssembler` 装配后走入真身；「Dual Executor 只是迁移策略」的约束已达成（双轨不复存在）。

## G-003 AgentRuntime

推荐提取顺序：

```text
1. Context
2. EventRecorder
3. ToolRegistry / ToolExecutor
4. AgentLoop
5. Session
6. Sandbox
```

# 3. P1

### Skill Capability

保持 Markdown 兼容，同时增加：

```text
version
requiredTools
dependencies
inputSchema
outputSchema
validationRules
```

### Sandbox Provider

第一阶段只完成 Provider Contract，不把 Docker/K8s 当作已完成安全隔离。

### Event Consumers

顺序：

```text
Timeline / Replay
→ Audit
→ Recovery
→ Fork
```

### Planner 能力感知（G-010）

设计：`doc/design/Planner_Capability_Awareness.md`（2026-09-09 落稿，§7 决策 1~7 全部拍板）。

已拍板：

```text
登记口径：新 G-010（不并入 G-004）
粒度决策：rule-based 矩阵（执行者画像 × difficulty；LLM 自判后置）
装箱语义：并集（子任务级 ∪ 任务级，去重保序，子任务级在前）
目录注入：常驻（每技能一行摘要，超 20 项截断）
constraints：显式列（仅 COARSE 必填）
回流：classpath 声明态（DB 化/热加载后置）
混合粒度：FINE（白名单为空 = STANDARD）
```

实施状态（2026-09-09）：

- S1 数据层：PASS（V74 迁移 + SubTask 实体/DTO + 单测）；
- S2 拆解侧：PASS（提示词三段 + PlanDraftItem 扩展 + PlannerGranularityResolver 矩阵单测 + buildDrafts 目录过滤落库）；
- S3 传递链：PASS（mergeSkills 并集装箱：SubTaskAutoExecutionDispatcher / ReviewServiceImpl / SubTaskReviewServiceImpl / SubTaskController.execute / SubTaskDispatchServiceImpl 选人约束五点同源；inbox summary 技能要求行；SubTaskResponse REST 下行；草案确认 UI 展示/编辑 + updateDraftById fail-close 端点；executor SKILL.md 子任务级指派说明）；
- S4 双场景实测（2026-09-09，平台内链）：PASS——本机 docker 四件套 + 6565 后端 + 5173 前端实测「登录 → 平台 PLANNER 注册/绑定（API_KEY_LLM + DeepSeek）→ 需求澄清终稿 → finalize 建任务 → 异步拆解（经 findPlanByTaskId 轮询）→ 确认/拒绝」全闭环；verify-login-e2e 10 项 / verify-requirement-clarify 10 步 / verify-planner-decompose 12 步全过（EXIT=0，与 G-011 S5 合并执行，见 log 2026-09-09）。外部执行链（外部 EXECUTOR agent 场景）已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log 2026-09-10）。

验证基线：core 全量单测 0 失败；api 模块编译通过；UI type-check 通过。

### 需求包准入与不确定性显式管理（G-011）

设计：`doc/design/Requirement_Package_Uncertainty.md`（2026-09-09 落稿，§8 决策 1~10 全部拍板）。

已拍板：

```text
登记口径：新 G-011（G-010=拆解侧，G-011=准入侧+契约侧，同 G-010 D7 边界论证）
需求包：5 字段压缩版（goal / scope / outOfScope / assumptions / openQuestions）
存储：会话列 + task.context 双写
uncertainties：显式 JSONB 列（kind=ASSUMPTION / UNCONFIRMED；命名与 gap_kind 消歧）
gap_kind：后置到批次二（「已有能力」须可最小验证，依赖 G-008 能力可验证基线）
闸门：不建自动闸门（openQuestions 不阻断；人工裁决 + fail-close BLOCKED 链上报）
```

实施状态（2026-09-09）：

- S1 数据层：PASS（V75 迁移 sub_task.uncertainties JSONB DEFAULT '[]' + requirement_conversation.final_package JSONB，含列注释与验证日志；SubTask 实体 uncertainties（JacksonTypeHandler 同 dependsOn 模式）+ RequirementConversation 实体 finalPackage；Uncertainty 值对象落 task 域（kind=ASSUMPTION/UNCONFIRMED 常量，避开 task→planner 反向依赖）；RequirementPackage record + RequirementPackageParser 静态工具（planner 域，同 TaskAgentPolicy 防御式模式：键缺失/类型异常回落空集合、数组元素仅保留字符串；fromContext 键空间隔离；render 五字段逐项列表空数组字段不渲染）；单测 10 用例覆盖全字段/降级/键隔离/渲染；core 全量单测 1329 用例 0 失败）；
- S2 澄清侧：PASS（两处终稿提示词 requirement-clarify.md / requirement-finalize.md 同步增 package 五字段结构化输出要求——goal/scope/outOfScope/assumptions/openQuestions + 提炼约束（推断项进 assumptions 且 description 同步标注（推断）、六维自检第 6 维产出落 outOfScope、数组可为空不得虚构条目）；ClarifyReply 增 package 字段（JsonNode 防御接收防非法类型击穿解析 + @JsonProperty 映射关键字键名）；ClarifyReplyParser.resolvePackage 防御式解析（缺失/非对象形态 → null 降级纯文本终稿 = 现状行为）；updateFinalDraftFields 定点写扩展 final_package 条件覆盖写（null 不动列），runFinalizeLlmRound（/task 直出）与 runLlmRound（澄清轮）两终稿轮同步扩展；buildTaskFromDraft 建任务双写 task.context.requirementPackage（regenerate 复用会话侧需求包自动双写；与 runningSpec 键空间隔离）；单测 7 用例（终稿带/无/非法 package 三态 + finalize/regenerate 双写 + 两模板契约防回退）；core 全量单测 1336 用例 0 失败）；
- S3 拆解侧：PASS（planner-decompose.md 模板四增量——占位符清单增 {{REQUIREMENT_PACKAGE}} + 「需求包（结构化准入产物）」段（明确 openQuestions=经人工确认的待确认缺口、assumptions=已申报推断项，拆解须按下文继承规则逐条评估）+ 拆解要求第 10 条 uncertainties 申报与继承规则（assumptions 强相关条目继承 ASSUMPTION、openQuestions 相关条目必须继承 UNCONFIRMED、禁止把推断 silently 写进目标）+ 拆解原则增 outOfScope 边界硬约束（明确排除项绝不拆进任何子任务）+ schema 增 uncertainties 字段（可空数组，kind 只能取 ASSUMPTION/UNCONFIRMED）；PlanDraftItem 增 uncertainties（JsonNode 防御承接，非数组/转换失败回落空列表不阻断拆解）；renderPrompt 增 {{REQUIREMENT_PACKAGE}} 渲染（RequirementPackageParser.render(fromContext) 统一入口，无需求包渲染占位文案行为零变化）；buildDrafts 三增量——uncertainties 落库（非法 kind 降级 UNCONFIRMED 不丢弃 + timeline task_plan_uncertainty_degraded 审计；空白 note 丢弃；与 G-010 幻觉标签丢弃模式差异理由：note 是自由文本标注本身有信息量，降级到更严语义符合 fail-close）+ COARSE constraints 缺失 timeline task_plan_constraints_missing WARN（G-010 缺口④清偿；粒度判定提前 doDecompose 一次判定 renderPrompt 与 buildDrafts 共用）+ D5 兜底审计（需求包 openQuestions 非空但拆解产物无任何 UNCONFIRMED 继承 → task_plan_uncertainty_missing WARN，仅审计 openQuestions→UNCONFIRMED 不审计 assumptions，不阻断落库）；单测 11 用例（渲染/占位/落库/降级审计/非数组防御/COARSE 两态/D5 三态）+ 模板契约测试扩 planner 1 用例防回退；core 全量单测 1347 用例 0 失败）；
- S4 传递链：PASS（五个消费点全链路落地——①内部执行 prompt：buildUserPrompt 四要素段后增 D6 三段（执行约束补偿行（G-010 缺口清偿：约束落库后执行者不可见）+ 不确定性分级申报（ASSUMPTION 后缀「可自行验证，推翻即上报」/ 其余含人工编辑非法值一律 UNCONFIRMED 语义后缀 fail-close）+ 验收事实回源声明固定文本常驻，空值零注入）；②审查装配：ReviewExecutionEngine 增 {{CONSTRAINTS}}/{{UNCERTAINTIES}} 两占位符（空时渲染「（无）」）+ subtask-review.md 待核验段两行 + 轨道 A 增第 8 条执行约束遵守核验（G-010 缺口③清偿）与第 9 条不确定性分级核验（ASSUMPTION 不构成驳回理由、UNCONFIRMED 产出须含验证结论或 BLOCKED 上报痕迹，否则不达标）；③REST 下行：SubTaskResponse + toResponse 同步 uncertainties + DraftUpdateRequest/Controller/Service 四参 updateDraft 链（null=不修改/空数组=清空/空白 note 丢弃/非法 kind 降级 UNCONFIRMED，D3 fail-close 同口径）；④inbox 摘要：ASSIGNED 分支 UNCONFIRMED 计数>0 追加「待确认: N 项」（ASSUMPTION 不计入，纯文本形态不动 inbox 契约面）；⑤草案确认 UI：实体/API 类型扩展 + PlanReviewDialog 不确定性列（「N 项待确认 · M 项假设」压缩展示）与编辑弹窗逐条增删改（kind 下拉 + note 输入 + 保存前过滤空行）；单测 12 用例（执行 prompt 四用例：注入/零注入/空列表/非法降级 + updateDraft 归一化三用例 + 审查渲染两用例 + inbox 摘要两用例 + subtask-review 模板契约一用例）；core 全量单测 1359 用例 0 失败；api 编译通过；UI type-check 通过）；
- S5 实测（2026-09-09，与 G-010 S4 合并执行）：PASS（平台内链）——①jsonb 定点写 bug 修复：updateFinalDraftFields 原 lambdaUpdate.set(Map) 直绑 SQL 被 PG 拒（wrapper 路径不套用实体 JacksonTypeHandler），改 RequirementConversationMapper + XML `#{finalPackageJson}::jsonb` 条件写（null 不动列，参照 SubTaskMapper.updateDependsOn 先例），RequirementClarifyServiceTest 92 用例全绿，实测终稿 finalTitle/finalPackage 真实落库；②确认卡协议核验：卡切换分支仅认 selections 快照（isAcceptSelected），纯文本「确认」不触发切换，脚本改带 selectedOptions 应答（questionId=confirm-switch）后终稿正常产出；③脚本端点失配修复（N-0xx RPC 风格化收口效应，shell 2 脚本 20 处 + ps1 6 脚本 22 处）：sendMessageById/finalizeById/abandonById 系列 + planById/findPlanByTaskId/confirmPlanByTaskId/rejectPlanByTaskId 系列 + 拆解异步化轮询适配（planById 恒空返回，deepseek v4-pro 实测拆解约 4 分钟 → 轮询窗口默认 360s）；④实测结果：verify-requirement-clarify 10 步（终稿 finalTitle=内部日报统计模块 → finalize → FINALIZED → 异步拆解 6 草案 → abandon 回归）、verify-planner-decompose 12 步（5 草案 → PLANNING → confirm 转正 PENDING/ASSIGNED → IN_PROGRESS；reject 路径 CANCELLED → Task 回退 PENDING；重复拆解守卫 500）全过；⑤技术债登记：AgentController.validateModelType 在 registerOrGet 幂等查找之前执行，同模型同角色残留 agent 重复注册必 500（重跑前须逻辑删除残留 agent）；实测残留数据：agent 5 + task 3 + 会话 2 + 草案若干（cleanup-test-data.sql 可清理）。外部执行链（外部 agent 场景）已于 2026-09-10 双轮全链闭环（Round2/Round3，见 log 2026-09-10）。

- P1/P2 批次（2026-09-11，生成能力退化修复）：PASS（代码 + 单测；实测回归脚本已升级待跑）——①P1-1 任务级验收条目：RequirementPackage 六字段 + RequirementPackageParser（KEY_ACCEPTANCE_CRITERIA / parse / render，JSONB 加键**无 DDL 迁移**）+ requirement-clarify.md / requirement-finalize.md package schema + planner-decompose.md「任务级验收覆盖」bullet（封闭集合全覆盖 + 子任务 acceptance 适用时标注「（对应任务级验收条目 N）」+ 契约/过程性子任务可不标注 + acceptanceCriteria 空则不做要求）；设计文档同步修订（状态横幅 P1/P2 登记 + D1 六字段 + §2.2 schema + §4 S2/S3/S5 + §6 验收 7~11 + §7 决策 #2 反转与 11~17），差距表本行同批登记（诚实登记原则）；②P1-2 拆解必填字段 fail-close：parseDraftItems 逐条校验 title/content/deliverable/acceptance（null/blank 即整批 BizException），抛错前记 timeline `task_plan_draft_field_missing`（payload: draftSeq/field/title/rawOutputSummary）；③P1-3 终稿信息量：四小节关键词组（背景|目标 / 范围|边界 / 交付物|交付 / 验收）宽容匹配，缺小节同轮追加纠偏指令**重试 1 次**；重试异常/非 final/仍缺 → 放行首轮 + timeline WARN `requirement_description_section_missing`（fail-open，不阻断建任务；runFinalizeLlmRound / runLlmRound 两终稿轮同覆盖），两提示词「与 description 同源」改为职责划分（description=完整规格四小节正文，package=结构化索引，禁止以 package 概括代替正文）；④P2-1 主任务详情：TaskList.vue 描述弹窗扩展为「任务详情」（任务描述 + 需求包六字段含任务级验收标准块），`task.context.requirementPackage` 缺失/非法/六字段全空整块隐藏（后端零改动，context 无 @JsonIgnore）；⑤P2-2 回归口径：verify-requirement-clarify-structured.ps1 由「追问即 abandon」扩至「澄清 → 终稿」，nudge「没有其他要求，请直接生成终稿」后 finalizeById，软断言 description 小节组 ≥3 / 长度 ≥300 / final_package 存在（LLM 输出不可控不 hard fail；FINALIZED 时跳过 abandon 并提示清理测试数据）；⑥P2-3：planner-decompose.md COARSE 行 acceptance 补「与交付物的可观察判定方式（判定动作 + 预期结果）」（不动 STANDARD/FINE）；⑦P2-4 审查驳回可执行性：subtask-review.md 轨道 A 第 10 条「缺失证据清单」+ 输出 schema `missingEvidence`（acceptanceRef 用验收标准**原文子串**机械可核 / missing / howTo），VerdictParser.normalizeMissingEvidence 防御归一（缺失/非数组/元素非对象 → 空清单），rejectAndRework 写入 `context.reviewHistory` 当前轮（JSONB 加键零迁移，同 executorDoneIssues 先例），buildReworkSummary 渲染「缺失证据清单」段随返工 inbox 摘要下行（不做 acceptance 编号化协议，方案 B 否决理由见设计 §7#17）。**测试证据**：P1-1 相关四单测文件全绿（RequirementPackageParserTest / RequirementClarifyServiceTest / PlannerDecomposeAsyncServiceImplTest / RequirementPromptTemplateContractTest）；P2-4 相关（SubTaskReviewServiceTest 46 / SubTaskServiceHandoverTest 16 / 模板契约 4）0 失败；core 全量 1380 + api 58 用例 0 失败；UI `npm run type-check` 0 error。**红线**：全程无 DDL 迁移、无新增 agent→task 依赖（RequirementPackage 在 planner 域、review 读 task context 均为既有合法方向）。


### 登录鉴权与 RBAC 权限体系（G-012）

登记口径：新 G-012（登录自建 → Sa-Token 统一会话 + RBAC 授权；2026-09-12 底座 + 闭环落地）。

已落地：

- S1 数据层：PASS——V77 迁移四表（sys_role / sys_permission / sys_user_role / sys_role_permission）+ 内置种子（SUPER_ADMIN 全权限 / ADMIN 显式 20 权限码，23 权限码 = 17 MENU + 6 API）+ 存量 sys_user.role 单字段迁移关联表；dev 库事务回滚验证 PASS。V78 扩展 sys_permission 增加 parent_id / path / icon（菜单树 DB 化数据底座）+ 新增 user:view / role:view / permission:view / deadletter:view 四个 MENU 权限码 + ADMIN 绑定 deadletter:view。
- S2 会话层：PASS——AuthServiceImpl 由自建 Redis Token 会话切换 Sa-Token（StpUtil.login / getLoginIdByToken / logoutByTokenValue，token 走 X-Admin-Token 头，active-timeout=28800s 滑动续期；Redis 会话键前缀取 sa-token.token-name，即 X-Admin-Token:，非库默认 satoken:——勘误 2026-09-13，见《基础架构调整实施计划》§12.1）；AuthService 接口清掉旧常量与文档；AuthServiceTest 10 用例覆盖。**存量会话无缝迁移**：validateAdminToken 在 getLoginIdByToken 返回 null（Sa-Token 对未命中 token 返回 null 而非抛异常，1.44.0 实测语义）时回退读旧 Redis key（auth:admin:token:{token}），命中则以**原 token 值**重建 Sa-Token 会话并删除旧 key——前端零改动、旧会话自动续用；Docker 实测：预置旧会话 → /api/auth/me 200 且旧 key 清除、同一 token 二次请求仍 200（已由 Sa-Token 会话承接）。损坏 JSON 清理 + 双未命中 401 有单测覆盖。
- S3 授权层：PASS——StpInterfaceImpl（用户 → 角色码 + 权限码，SUPER_ADMIN 返回 "*" 全权限通配）；SaInterceptor 注册 /api/** 注解鉴权；SysRoleController（role:manage）/ SysUserRoleController（user:manage）/ SysPermissionController（role:manage）@SaCheckPermission 接入；GlobalExceptionHandler 补 NotLoginException→401 / NotPermissionException→403。
- S4 前端菜单过滤：PASS——登录响应携带 permissions/roles（SUPER_ADMIN="*"）；auth store 持久化权限码 + hasPermission（"*" 通配兜底）；MainLayout 菜单数据化按权限码过滤（15 个菜单项 ↔ V77 MENU 权限码一一对应）。
- S5 角色 / 权限 / 用户管理前端页面：PASS——新增 UserList.vue / RoleList.vue / PermissionList.vue 三页（用户分页+搜索+编辑+分配角色+重置密码 / 角色 CRUD+权限绑定（菜单/接口分组勾选，SUPER_ADMIN 禁删）/ 权限码列表+搜索）；路由 system/users|roles|permissions；rbac.ts + paths.rbac + types/system.ts 接入；Element Plus 表格/对话框/分页齐全。
- S6 菜单树 DB 化：PASS——MenuController /api/admin/menus/tree + SysPermissionQueryService.listMenuTree（type=MENU + 权限码过滤 + parent 挂接 + 「DB 有子但过滤后无可见子节点」剔除空父）；MainLayout 改调菜单树接口动态渲染（icon 按 @element-plus/icons-vue 组件名映射）；Docker 实测：SUPER_ADMIN 返回 18 根节点（含系统设置→用户/角色/权限管理父子层级、死信池 query 菜单），ADMIN 无 user/role/permission:view 时系统设置整体隐藏。
- S7 基础设施修复：PASS——用户分页 total 恒 0 根因二连：① mybatis-plus 3.5.9 起 PaginationInnerInterceptor 拆分到 mybatis-plus-jsqlparser 独立模块且为 optional，需显式引入（helloai-start 增加 mybatis-plus-jsqlparser-4.9）；② mybatis-plus 版本 3.5.9 → 3.5.12（聚合 POM 已含 jsqlparser 模块，本地 m2 全量缓存）。修复后 Docker 实测 /api/admin/users/page 返回 total=2 / pages=1 / LIMIT 生效。

验证基线：core 全量单测 1399 用例 0 失败（AuthServiceTest 10 含会话迁移 3 用例 + SysRoleServiceImplTest 6 + SysPermissionQueryServiceImplTest 5 等，mybatis-plus 3.5.12 下复跑全绿）；api 全量单测 58 用例 0 失败；UI vue-tsc type-check + 生产构建通过。**Docker 本机实测**（postgres/redis/rabbitmq/minio 容器 + local profile，2026-09-12）：admin/admin123 登录 → /api/auth/me 权限=["*"]；菜单树 18 根节点；角色 CRUD + 权限绑定（创建 TESTER→绑 18/19→回读→改名→删除）全链路 200；用户分页 total=2、testuser1 分配 ADMIN 角色后 roleCodes=['ADMIN']；存量旧 Redis 会话无缝迁移 200 且旧 key 清除、同 token 复用 200；前端登录 + 侧边栏动态菜单渲染（浏览器实测），三管理页面模块 Vite 转换 200 无报错。

后置缺口（原登记三项已全部关闭）：无。

### 基础架构深化（G-013，参考 JeecgBoot，实施编排独立）

登记口径：G-012 闭环后，参考同级目录标杆项目 JeecgBoot-main 的菜单/用户/角色/权限实现，
梳理出基础架构深化差距（动态路由 / 按钮权限 / 动作级权限码 / 菜单管理页 / 差异更新 / 扩展能力），
作为**基础架构专项**独立实施——任务编号 **BASE-1.x / BASE-2.x / BASE-3.x**，
与差距表 G-xxx、重构实施计划 P0~P3 / A1~A7 / S1~S8 完全错开，不混用。

- 专项文档：`doc/HelloAI 基础架构调整实施计划.md`（背景 / 已完成基线 G-012 S1~S8 / 标杆设计要点 / 三批次任务明细 / 验收）。
- 目标架构融合：`doc/HelloAI 目标架构.md` 新增 §12 基础架构（平台底座），与业务五层正交。
- 批次状态：**三批次（BASE-1.1~1.6 / 2.1~2.3 / 3.1~3.3）已全部落地（2026-09-12，PASS）**——详见 `log/2026-09.md`「批次一 + 批次二全量落地」「批次三全量落地」两段。
- **批次四（2026-09-13 立项，部分落地）**：认证收口 + 角色体系 + 全量接口授权化（BASE-4.1~4.5）——代码核查暴露三处缺口：① Sa-Token 认证未收口（全仓 0 处 `StpUtil.checkLogin()`，`active-timeout` 滑动续期实际失效）；② 身份数据双写不一致（`sys_user.role` 死字段 + `SysUserServiceImpl.create()` 漏写权威关联表）；③ 接口授权覆盖极低（`@SaCheckPermission` 仅 24 / 234，业务面 125 接口全无授权，只读角色无法落地）。任务明细见《HelloAI 基础架构调整实施计划》§9，目标边界收敛见《HelloAI 目标架构》§12.2/§12.3。**BASE-4.1 PASS**（新增 `authenticateAdmin` 标准 checkLogin 守门 + `AdminOnlyInterceptor` 事实源回归 Sa-Token + `/registerWithToken` 白名单修正；core 1449 用例 0 失败；E2E 授权断言改造前后一致 + 滑动续期实证 + 存量会话迁移 OK；残留：`McpAuthFilter` 未收口、active-timeout 超时触发未实测）；**BASE-4.2 PASS**（V87 补齐 + `DROP COLUMN sys_user.role`；`create()` 增 `remark` 参数并补插 `sys_user_role`；删 `SysUserItem.role`/`LoginResponse.role`/`AdminSession.role` + 前端类型同步；core 1453 / api 58 / job 69 用例 0 失败；E2E 列已删 + 登录 `permissions=["*"]` + 建号签发角色与 remark 落库 + 测试数据零残留）；**BASE-4.3 / 4.4 合并实施（PASS，2026-09-13）**：V88 新增 71 动作码（管理面 32 + 业务面 39，id 49~119）+ 6 个粗粒度码退役；V89 新增 NORMAL_USER / GUEST 角色并完成 ADMIN (57) / NORMAL_USER (55) / GUEST (16) 绑定（GUEST 零写码）；`@SaCheckPermission` 由 24 → **131 处**（新增 107，覆盖 23 控制器；**2026-09-29 复测为 144 处，见 §0 C-4**），Agent / 公开白名单 12 控制器实测 0 注解；前端 v-auth 对齐 9 个页面 + 补漏 4 处。**核对**：V88 的 71 码全部被引用（零幽灵码），注解引用 88 码 = 71 + 既有 17；编译 exit 0 / core+job+api 单测全绿 / UI type-check 0 error；V88/V89 事务内干跑通过（INSERT 0 32+39 / 2 角色，ROLLBACK 无残留）；**四角色差异 E2E 待重启后执行**。**BASE-4.5 已实施（2026-09-13）**：管理员建号 `POST /api/admin/users`（`user:add`，建号即签发角色）+ 自助注册 `POST /api/auth/register`（受 `sys_config.auth.register.enabled` 门控，**默认关闭** —— V90；注册固定绑 GUEST 只读）+ UserList「新增用户」+ 登录页注册表单（开关开启时渲染）；`/api/setup/getStatus` 增 `registerEnabled` 供登录页判定。验证：编译 exit 0 / core+job+api 单测全绿 / UI type-check 0 error；E2E 待重启复验。**批次四（4.1~4.5）实施完毕**。详见 `doc/log/2026-09.md`「批次四收官」段。
- 授权补充（2026-09-12，PASS）：**ADMIN 角色绑定部门管理权限**（BASE-3.2 授权补充）——V84 迁移 `V84__rbac_admin_depart_permission.sql`（role_id=2 × `depart:view/add/edit/delete`，`ON CONFLICT DO NOTHING` 幂等）+ 界面等效路径 `PUT /api/admin/roles/2/permissions`（`lastPermissionIds` 差集，原 21 码零丢失 → 25 码）。验证：Flyway version=84 success=t 且二次执行 `INSERT 0 0` 无重复；ADMIN 身份 `/api/auth/me` 含 4 个 depart 码、菜单树含「系统设置 → 部门管理」、部门 tree/新增/删除全 200；未授予 `position:*`；测试数据零残留。详见 `log/2026-09.md`「ADMIN 角色绑定部门管理权限」段。
- 落地要点：V79 component + 12 动作级权限码（V80 补 path）；SysPermissionService CRUD + DTO 投影；三 Controller 方法级 `@SaCheckPermission`；**SaInterceptor 注册修复**（G-012 遗漏）；前端动态路由（`router/dynamic.ts` + 白名单守卫 + NotFound）+ v-auth；菜单管理页树形 CRUD；授权 `lastPermissionIds` 差集；**BASE-3.1** V81 hidden/keep_alive/external_link + 前端渲染适配；**BASE-3.2** V82 部门四表（部门 + 用户-部门）+ Service/Controller + DepartList + 用户组织归属；**BASE-3.3** V83 rule_flag + 数据规则表 + 受控枚举解析 + 用户列表数据权限。
- 收口（2026-09-12，PASS）：**岗位能力彻底移除 + 拆出独立「菜单管理」入口**（V85 / V86）——岗位判定对本系统无场景（部门已承担组织归属 + 数据权限范围），经确认彻底移除：V85 清 `position:*` 关联并软删 4 码 + `DROP TABLE sys_user_position`/`sys_position`（表中 0 行，不可逆）；**V86 将软删遗留行物理清理**（`DELETE FROM sys_permission WHERE code LIKE 'position:%'`，含任意 deleted 状态；关联表防御性清理）。后端删 11 文件（entity×2/mapper×2/Service(+Impl)×2/Controller/DTO×3/单测）+ 收敛 SysUserRoleController/SysUserItem；前端删 PositionList.vue + 岗位 API/路径/类型 + UserList 岗位列与勾选（保留部门）。菜单维护独立：V85 新增 `menu:view`（id=48，`/system/menus` → `system/MenuList`），新增 MenuList.vue（仅 type=MENU 树形 CRUD）、PermissionList.vue 收敛为「权限管理」= 仅 type=API 权限码 + 数据规则，两页共用 `/api/admin/permissions`。详见 `log/2026-09.md`「岗位能力移除 + 菜单管理独立入口」段。
- 实测修复（5 个）：① SaInterceptor 从未注册；② `addRoute` 父参数须为路由 name；③ 登出后 `routesBuilt` 未重置致权限串用（改按 token 跟踪）；④ **catch-all 用 redirect 导致登录后落 404**（改直接渲染 NotFound 组件，保证「首次导航 → 构建 → 重入原目标」链路）；⑤ **会话失效静默落 404**（HTTP 401 走 axios error 分支未清登录态 → `request.ts` 补 401 清态 + `router` 守卫区分 401/未登录跳 `/login`、其余失败重试 1 次并提示）。
- 验证：core **1437 用例 0 失败**（V85 后移除岗位测试 7 用例）/ api 58 用例 0 失败 / UI type-check + build / Docker API 全链路（部门/数据权限/渲染字段；岗位端点已 404）/ 浏览器实测（SUPER_ADMIN 系统设置 = 用户/角色/菜单/权限/部门，无「岗位管理」；ADMIN 授权前无系统设置且 `/system/departs` 404，V84 授权后可见「系统设置 → 部门管理」并可 CRUD；数据权限 CUSTOM 过滤生效）；既有 PS1 因本机无 pwsh 为 NOT RUN（等价断言 PASS，环境限制非跳过）。
- 红线：不新增第二套权限体系、不触碰 Event/状态机/Scheduler/Workflow/Review、外部 Agent 契约不变、V77~V85 已提交 DDL 只读（新增 V86）、数据权限受控枚举无动态 SQL 拼接。
- 合规基线（2026-09-12）：专项文档 §4 强制遵循《HelloAI_AI开发协作规约》（Step 0~9 生命周期 / 复用既有 PS1 / 完成报告 16 节模板）+《HelloAI_CODE_STYLE》（RBAC 归 system 域不反向依赖业务域 / DTO 投影不暴露 Entity / @Transactional / Flyway V79+ / §43 认证授权分离 / v-auth 统一 hasPermission）。


```text
Quality Gate
Capability-based Agent Routing
Historical Success
Cost / Latency
```

# 5. P3

```text
Dynamic Workflow
LLM-generated branching
Advanced Sandbox
Cross-session optimization
```

# 6. 明确不再作为开发要求的口径

```text
❌ 为了凑 Harness 功能而复制 Harness
❌ SkillRegistry 作为独立“大框架”重新建设
❌ Sandbox = Local/Remote Environment 的同义词
❌ Dual Executor 永久双轨
❌ Planner / Executor / Reviewer 各自拥有完整执行能力
❌ 新建第二套 Workflow Runtime
```

# 7. Gap 登记与回流规则

## 7.1 孤儿项回流

设计文档（专项设计 / 执行方案 / ADR）中作出「推迟到 X 期」「划远期」「降级交付」的决定时，必须在本表同步登记对应条目或显式记 WONTFIX，不得只留存在设计文档内——否则该承诺会随批次闭环从索引中消失。

### 7.1.1 契约层能力注入与最终报告整合（2026-09-28，依据 `doc/archive/implemented/HelloAI_契约层能力注入与最终报告整合改造方案.md` §8）

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| N1 | 报告分章 map-reduce（逐子任务章节独立成文） | 登记待决策项（A5），决策前不实施 | G-016 |
| N2 | SYNTHESIZER 角色 / 任务级 reportAgentId（报告执笔 Agent） | 依赖 Role Layer 角色模型调整，后置 | G-016 |
| N3 | 同步 LLM 调用迁入 AgentRuntime | 后置；契约层注入挂点随迁 | G-002 / G-016 |
| N4 | 通用 Quality Gate（Rule + Test + LLM 统一决策） | 后置；阶段三 A 为报告场景实例 | G-007 / G-016 |
| N5 | Skill Instructions 字段结构化 | 约定反转登记，后置 | G-004 |
| N6 | final_report 注册为 attachment（交付形态统一） | 后置 | G-016 |

## 7.2 历史编号映射（2026-09-07 文档重构）

| 旧编号（已归档） | 新编号 | 能力 |
|---|---|---|
| N-013 | G-003 | Harness 执行循环（AgentLoop / ToolExecutor） |
| N-014 | G-004 | Skill 元数据结构化 |
| N-015 | G-005 | Sandbox Provider 化 |
| N-016 | G-006 | Event Stream 消费侧（Replay / Audit） |
| N-017 | G-008 | Agent Fleet 能力化选人 |
| N-018 | G-007 | Quality Gate |
| N-019 | G-009 | Workflow Engine 增强（远期） |

旧编号明细与登记背景见 `archive/legacy/V1_HelloAI 实现差距表.md` 与 `doc/log/2026-09.md`（LOG-20260907-001）。
