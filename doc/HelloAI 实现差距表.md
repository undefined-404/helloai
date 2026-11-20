# HelloAI 实现差距表

> **状态：CURRENT GAP**
>
> 本文只记录**当前 → 目标**的真实差距；**只保留最新状态、随迭代就地更新**，不累加过程记录。
>
> **最后更新：2026-11-20（`MA-8` 安全扫描只记不拦落地 + 第二轮追加：**首轮 6 条规则 + 3 挂点 + 纯判据 + 唯一「扫+记」入口，`SensitiveContentScannerTest` 20 例全绿，payload 结构上不含原文；**第二轮（用户最关注项）补挂点④ `ATTACHMENT`**——实测附件三通路绕过既有三挂点（澄清/对话链 `{{ATTACHMENT_CONTEXT}}` 拼装、`internal_read_attachment` 直读、消息 content 只扫正文），`ChatAttachmentParseWorker` 的 `markReady` 前是唯一可见附件完整原文的位置，扫描后命中三态（`null`/`0`/`>0`）落 `chat_attachment.scan_hit_count`（`V153` 追加列）+ 前端 `RequirementChat.vue` 胶囊标记与提示行（**上传即告知，不弹卡**——弹卡属 `MA-9` 需两硬前置）；**规则加第 7 条 `CONFIG_SECRET`**（键值赋值形态 + 占位值排除）；顺带修复「附件从未被扫 + scan 异常冒泡致整条解析 FAILED」两真实缺陷（调用点 try-catch，判据 27）。`SensitiveContentScannerTest` 27/27 + 新增 `ChatAttachmentParseWorkerScanTest` 7/7。`MA-10` 执行链超时治理四条改动全部落地：尽力交付（**落点重构**——不改 `AgentLoopResult.success` 而新增 `isPartialDelivery()` + 只改消费侧，因该字段被 `FinalReportFenceHook`/`BusinessSurfaceLoopRunner`/既有 45 例单测共同消费）/ 次数分档 + `maxIterationsHint` 覆盖 / 无进展兜底（`V153` + 审批豁免持续刷新型）/ 删子任务链 `turnDeadline` 死代码；**两项均为「落地，观察期中」不标「已完成」**；`ci-gate.sh --no-clean --skip-ui` **复跑 0 失败 5 门禁全绿**（此前 target 残留旧 V153 致 B 级集成失败，`rm -f` 残留后确认通过））**
>
> 本轮三件：
>
> ① **前端收口（15:50）**：草案审阅弹窗等待页按原型实现 **4 态动态过程卡**（步骤徽标 / 加载圆环 / 文案轮播 / 进度条 / 四步步骤条 / 三张提示卡 / 底部已用时），步骤与百分比是「已用时 + 已轮询草案数」推算的**展示态**（后端无阶段上报）。原「文字看不清」根因是**未复位的 `v-loading` 遮罩**（明暗两套同因），已消除；「产出后站内提醒」文案保留 = 已登记空头承诺（`design/HelloAI 站内消息与置顶公告设计.md` A1 兑现），非缺陷。详见 `LOG-20261112` `#### G`。
>
> ② **dev 清库重建 + 冒烟（16:54）**：dev 库按用户裁定**直接清库**重建为现役迁移链 **`V146`~`V150`**（140 个迁移本机空库演练一次通过为闸门；`scratch/dev-reset-schema.sql`）。dev 已从 V1 完整 apply 至 V150（68 表、种子齐全），用户初始化 4 provider 凭据 + 7 内部 agent；全链路冒烟通过（建任务→拆解 5 草案→confirmPlan 物化→**ADR-008 派生式子任务自动生成**，`requirement_conversation` 落 `task_id=父` + `derived_task_id=子` + `source_draft_code` 双指针）。**发现缺陷候选**：`deleteTaskCascade` 不级联清理派生任务。详见 `LOG-20261112` `#### H`。
>
> ③ **派生执行链真机验证 + 「整理报告卡住」根因定位（18:30）**：执行链闭环**已验证通过**——派生任务 D 全子任务 DONE ⇒ D 自动收尾 DONE ⇒ 父 C-001 由 `AWAITING_DERIVED` 自动传播 `DONE`（`propagateDerivedCompletion` sync + reconcile 双路径真实生效）；依赖解锁正常（前驱 DONE ⇒ 下游自动 ASSIGNED/IN_PROGRESS）。用户看到的「整理报告一直卡住」根因 = **父任务 C 未收尾**（C 的 C-002/C-003 停 REVIEW），非报告功能故障（对照：D 的报告 17:29 正常生成 15125 字符、`final_report_status=DONE`）。卡点双重根因：**① 沙箱 401（环境）**——`application-dev.yml`（skip-worktree 本地版）sandbox `api-key` 默认值被写成服务器 key，本地 127.0.0.1:8090 只认 `dev-test-key` ⇒ 全部 `internal_run_in_sandbox` 401 ⇒ executor 降级纯 LLM 撰写、交付文档夹带过程叙事 ⇒ 评审连环打回；**② `INCONCLUSIVE` 挂起（系统）**——C-002 第二轮评审命中静态校验阻断项 `unconfirmed_silently_dropped` ⇒ 判 `INCONCLUSIVE` 转人工 ⇒ **REVIEW 状态不在任何定时任务推进集，>1h 零推进**（C-003 则陷评审-返工循环 + 一轮 30min 超时后被 `ExecutionCompensationTask` 补偿重派）。**缺陷候选登记**：A 沙箱 api-key 默认值错配（改 `dev-test-key` 或注入 env）；B 评审 `INCONCLUSIVE` 后无超时升级/定时重试/站内提醒 ⇒ 永久挂起；C `unconfirmed_silently_dropped` 把单条申报问题升级为整轮评审不可信 ⇒ 中断执行链。C-002~C-005 与任务 C 为遗留测试数据未清理。详见 `LOG-20261112` `#### I`。
>
> 过程与订正历史按「做过什么」归 `doc/log/`：2026-09 批次见 `doc/archive/log/2026-09.md`，2026-10 批次见 `doc/log/2026-10.md`，2026-11 批次见 `doc/log/2026-11.md`。
>
> 当前进度**以 §1 总体矩阵为准**；本表是「当前差距」的唯一事实源。

# 0. 当前生效的口径与决策

> 只登记**仍然生效**的口径与决策；过程与订正历史见 `doc/archive/log/2026-09.md` / `doc/log/2026-10.md`。

**编号口径（全项目唯一，防撞号）**

| 编号 | 含义 |
|---|---|
| `G-001`~`G-036` | 本表差距项（见 §1 总体矩阵）；`G-021` / `G-022` / `G-023` / `G-024` / `G-025` / `G-026` / `G-027` 分别于 2026-10-12 / 2026-10-13 / 2026-10-16 / 2026-10-23 / 2026-10-24 / 2026-10-26 / 2026-11-04 登记；`G-028` 于 2026-11-05 补登（个人助理工作台，`PA-x` 落地但无 G 锚点）；`G-029`/`G-030` 于 2026-11-10 登记（跨文档核验真机缺陷）；`G-031` 于 2026-11-11 登记（Task-Team 成员表运行期无写入）；`G-032` 于 2026-11-11 登记（沙箱生命周期与台账证据，`SL-x` 建号锚点）；`G-033` 于 2026-11-12 登记（执行环境执行侧与派发校验缺口，源自 `LOG-20261112` A 段遗留；「仓库注入」形态经用户裁定先调查）；`G-034` 于 2026-11-13 登记（审核侧证据可见性与驳回依据可达性，`RV-8`~`RV-13` 建号锚点）;`G-035` 于 2026-11-15 登记（站内消息与置顶公告，`MSG-x` 建号锚点;首批 + 公告 + 平台开关，`MSG-7` 扩展列后续）；`G-036` 于 2026-11-16 登记（A2A 兼容边界，`ADR-009` 定边界、`AQ-1`~`AQ-13` 待验；**不扩新号段**，A 档四洞沿用 `G-036` 锚定） |
| `P0`~`P3` | **架构重构主线阶段**（唯一含义）；审计优先级档已改用 `PR0`~`PR3` |
| `RM1`~`RM13` | 代码质量路线图（与目标架构能力层不再撞号）；**该计划已于 2026-10-17 归档 `Done`**（`doc/archive/implemented/HelloAI_RM存量缺口清偿计划（2026-10-04）.md`），号段不再新增 |
| `B1`~`B5` | 目标架构能力层（阻断项）；**4 项达成**（B1 单轨 / B3 Recover / B4 QualityGate / B5 用量与成本），**仅 `B2 Sandbox` 条件触发（暂缓）**（2026-10-18 复核：契约 + 五边界诚实标注 + 真实执行通道已建，`G-005` 三触发条件仍未命中） |
| `BASE-x.y` | 基础架构专项任务（`doc/archive/implemented/HelloAI 基础架构调整实施计划.md`，2026-10-12 已完成归档；编号已退役，不再新增） |
| `REF-x.y` | 借鉴落地专项任务（`plan/HelloAI 借鉴落地实施计划.md`，2026-10-09 建号）；`x` 取 1~8 —— **`REF-8` 内部 LLM 能力面**（2026-10-12 新增，`D-2026-10-12-1`） |
| `M-x` | **供应商 / 模型注册信息完善**（magpie 借鉴，2026-10-13 建号，`x` 取 1~9）：来源 `research/magpie_借鉴分析.md`，实施见 `plan/HelloAI 借鉴落地实施计划.md` **§11**。与 `REF-x.y` **并列**——`REF-` 号段 `x` 已占满 1~8，不扩号段 |
| `S-x` | **OpenSandbox 沙箱专项任务**（2026-10-17 建号，`x` 取 1~10）：`S-1`~`S-6` 实施见 `archive/implemented/HelloAI OpenSandbox 沙箱集成计划.md`；**`S-7`~`S-9` 沙箱执行链**（S-7 执行通道 / S-8 工具接线 / S-9 机械判定接入）实施见 `archive/implemented/HelloAI 沙箱执行链设计方案（2026-10-17）.md`；**`S-10` 危险命令守卫 = 后置登记不启动**（待执行通道被真实业务高频使用后评估误用面再启动，对齐 REF-8.9 独立预算闸同款处置）。与 `REF-x.y` / `M-x` 并列——`REF-` 号段不扩，另立号段（实测 `doc/archive/**` 无 `S-` 号段 ⇒ 不撞号） |
| `W-x` | **联网搜索效果优化专项任务**（2026-10-18 建号，`x` 取 1~7）：P0（W-1 编排器请求 answer / W-3 动态 freshness）+ P1（W-2 并行搜索 / W-4 搜索词质量）+ P2（W-5 Rerank / W-6 Grounding / W-7 模态卡）；实施见 `plan/HelloAI 联网搜索效果优化实施计划（2026-10-18）.md`。**P3 Agentic 多轮搜索 = 登记不启动**。与 `S-x` / `M-x` / `REF-x.y` / `T-n` 并列——实测 `doc/archive/**` 无 `W-` 号段 ⇒ 不撞号 |
| `ATT-x` | **需求对话附件专项任务**（2026-10-23 建号，`x` 取 1~8）：`ATT-1` 文本 + PDF 文本层 + 两层 Prompt 注入（**已落地**，`LOG-20261022-014`）/ `ATT-2` 视觉直读（图片与扫描件共用一条通道）/ `ATT-3` 大文件 RAG + 引用回源 / `ATT-4` 版面增强（Docling sidecar，**登记不启动**）/ `ATT-5` 附件意图三态判定（`none`/`answerable`/`needs_external`）/ `ATT-6` 分析后检索（不确定点→检索词，补搜多变体）/ `ATT-7` 附件预算窗口化（按模型窗口折算注入预算）/ `ATT-8` 进度文案取真实候选词（2026-11-07 建号：先规划检索词、再发进度帧、后执行检索）。实施见 `plan/HelloAI 需求对话附件实施计划（2026-10-22）.md`（2026-10-23 由「设计方案」拆分更名；设计见 `design/HelloAI 需求对话附件设计.md`，调研见 `research/HelloAI 需求对话附件产品对标调研（2026-10-22）.md`）。与 `S-x` / `W-x` / `M-x` / `REF-x.y` 并列——`REF-` 号段不扩。实测 `doc/**` 全库无 `ATT-` 号段 ⇒ 不撞号；**刻意不用 `A-`**：`archive/implemented/deploy-checklist-arch-collect-2026-10.md` 内有 `A-1`/`A-2` 操作选项号，且「REF-5 四项之外 A 档」已在用「A 档」二字，两处都会混 |
| `PC-x` | **Paperclip 借鉴落地专项任务**（2026-10-23 建号，`x` 取 1~19）：`PC-1`~`PC-6` 现有机制补硬（env 黑名单 / 唤醒原因 / 409 语义 / 三配额分离 / 双入口契约 / 成本闸门）/ `PC-7`~`PC-11` CLI 推通道（适配器 / headless 契约 / 输出解析 / session 续接 / run 归一）/ `PC-12`~`PC-19` 有条件再做。实施见 `plan/HelloAI Paperclip 借鉴落地计划（2026-10-21）.md`。**建号动因**：旧稿用「阶段 + 序号」寻址（如「阶段二·1」），该地址不可被外部引用，且违反治理规则 §6.5「分期标签已冻结」——本次一并清偿，治理规则 §6.5 同步记录。与 `S-x` / `W-x` / `ATT-x` / `M-x` / `REF-x.y` 并列。实测 `doc/**` 无 `PC-` 号段 ⇒ 不撞号 |
| `TD-x` | **任务拆解领域无关化专项任务**（2026-10-24 建号，`x` 取 1~12）：`TD-1` `task.task_archetype` 六类拆分维度 / `TD-2` `sub_task.acceptance_kind` 四类验收判据 / `TD-3` 拆解提示词按 archetype 分支（**生效开关**）/ `TD-4` archetype 判定链 / `TD-5` 验收校验按 kind 分流 / `TD-6` 拆解期上下文快照 / `TD-7` `uncertainties` 扩 `TO_LOOK_UP`+`TO_DERIVE` / `TD-8` 澄清侧 stall 计数 / `TD-9` 单条 expand+regenerate / `TD-10` ledger 通道（**登记不启动**）/ `TD-11` 验收契约可满足性标注（2026-10-25 续号，只标不拒）/ `TD-12` 核验侧前置条件豁免（2026-10-25 续号，读 `TD-11` 标注做 `SKIPPED(blocked_by)`）。实施见 `plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md`，设计见 `design/HelloAI 任务拆解领域无关化设计.md`。与 `S-x` / `W-x` / `ATT-x` / `PC-x` / `M-x` / `REF-x.y` 并列。实测 `doc/**` 全库无 `TD-` 号段 ⇒ 不撞号；**刻意不用 `T-`**（`T-n` 已是专项工作项局部编号，`M-9` 起在用，复用会与 `T1`~`T10` 混）；**不用「阶段 / Phase」寻址**（违反治理规则 §6.5 分期标签冻结令，`PC-` 建号时同类问题已清偿一次） |
| `SKX-x` | **技能执行面接入专项任务**（2026-10-26 建号，`x` 取 1~10；2026-10-27 续至 15）：`SKX-1` 沙箱镜像基线（`node >= 18`，不装 Chromium）/ `SKX-2` 容器内 `spawn` 行为验证（决定是否带宿主补丁）/ `SKX-3` 注入通道实测（逐文件 `writeFile` vs `tar`）/ `SKX-4` 技能包入库形态（裁剪 + 版本锚 + 清单 + 许可）/ `SKX-5` 沙箱规格按调用分层（槽 key 带规格指纹）/ `SKX-6` 技能包文件注入 / `SKX-7` 产物按指定路径回传（走 `files().readFile`）/ `SKX-8` 工具面新增 `internal_run_skill`（**生效开关**）/ `SKX-9` 端到端验收（C1 档）/ `SKX-10` 事实登记与文档回填 / `SKX-11` 产物导出为可下载附件 / `SKX-12` 对话内技能直出（选技能当场出图，不建任务）/ `SKX-13` 技能执行异步化（脱离 HTTP 线程）/ `SKX-14` spec 预校验与自动纠偏（`$ref` 内联）/ `SKX-15` 部署面收口（`SANDBOX_*` 贯通 / preflight fail-close / 侧车端口）。实施见 `plan/HelloAI 技能执行面接入实施计划（2026-10-26）.md`，调研见 `research/Archify 类技能执行面接入可行性调查（2026-10-26）.md`。**触发口径**（2026-10-26 用户裁定，2026-11-01 复核）：`G-005` 条件②「技能包被执行」**已命中**——SKX-9 真机实测模型在工具循环自主触发 `internal_run_skill` 于开沙箱容器内执行；条件①「平台增加碰宿主工具」仍不命中（沙箱执行在容器内、`filesystem=ISOLATED`、「碰宿主」不成立），条件③「平台自持浏览器」仍不命中（C1 先行不装 Chromium）。`SKX-9` 经用户裁定**按通过**回填。与 `S-x` / `W-x` / `ATT-x` / `PC-x` / `TD-x` / `M-x` / `REF-x.y` 并列；实测 `doc/**` 全库无 `SKX-` 号段 ⇒ 不撞号；**刻意不用 `SE-` / `AX-` / `SK-`**（同为 0 命中但语义过泛或易与 `S-` 混） |
| `RC-x` | **需求对话自由切换与并发互斥专项任务**（2026-10-30 建号，`x` 取 1~6）：`RC-1` 后端每会话 Redisson RLock 互斥（前置段整体串行化）/ `RC-2` 抢占失败错误语义 / `RC-3` 后端单测 / `RC-4` 前端流式态 `Map<conversationId>` 化 / `RC-5` 前端 `sending` 闸门降级为仅当前会话 / `RC-6` 端到端验收 + 登记回填。实施见 `archive/implemented/HelloAI 需求对话自由切换与并发互斥实施计划（2026-10-30）.md`（2026-10-31 已归档 `Done`，号段退役不再新增）。实施次序**先 B 后 A**——A 拆前端闸门前 B 先兜住并发竞写。与 `S-x` / `W-x` / `ATT-x` / `PC-x` / `TD-x` / `SKX-x` / `M-x` / `REF-x.y` 并列；实测 `doc/**` 全库无 `RC-` 号段 ⇒ 不撞号；**刻意不用 `FS-`**（只覆盖 A 半边、语义过窄） |
| `PA-x` | **个人助理工作台专项任务**（2026-11-02 建号，`x` 取 1~17；2026-11-05 续 `PA-13`~`PA-17`：模型偏好记忆 / 先思考后检索 / 工作概览上下文 / 重生成端点 / 消息操作条——原 `WB-x` 草案并入，废弃该号段）：`PA-1` 用户归属模型（owner 落会话/任务/长期记忆 + 查询过滤）/ `PA-2` 用户级长期记忆（`long_term_memory` 启 `USER` 类型 + 画像表职责分层）/ `PA-3` 助理身份（`assistant_profile` 表 + 对话头部展示）/ `PA-4` 统一工作台（`RequirementChat` 左对话 + 右栏）/ `PA-5` 产物聚合读取侧（执行产物 + 会话附件 + 技能产物 三源归一）/ `PA-6` 事件订阅扩展（复用 SSE 实时刷新任务状态）/ `PA-7` 成本可见性（接 `G-026` 额度监控）/ `PA-8`~`PA-10` 后置登记不启动（自动化 / 插件模板市场 / IM 与移动端）/ `PA-11` 助理对话与跨历史检索（会话 `kind` 分流 + 助理 pipeline + 检索/建任务工具）/ `PA-12` 上下文窗口与压缩（token 分项占用 + 手动/自动压缩，2026-11-03 续号）。实施见 `plan/HelloAI 个人助理工作台实施计划（2026-11-02）.md`，设计见 `design/HelloAI 助理对话与上下文窗口设计.md`，调研见 `research/HelloAI 个人助理可行性评估与落地建议（2026-11-02）.md`。与 `S-x` / `W-x` / `ATT-x` / `PC-x` / `TD-x` / `SKX-x` / `M-x` / `REF-x.y` 并列；实测 `doc/**` 全库无 `PA-` 号段（仅 research 提及）⇒ 不撞号；**刻意不用 `A-`**（与 archive `A-1`/`A-2` 及「REF-5 之外 A 档」混）**不用 `WB-`/`WM-`**（与 `M-` 语义过泛）；**不用「阶段 / Phase」**（违反治理规则 §6.5 分期标签冻结令） |
| `RV-x` / `SR-x` | **核验双域改造专项任务**（2026-11-04 建号，`RV-x` 取 1~15；2026-11-13 续 `RV-8`~`RV-13`，2026-11-15 续 `RV-14` 并同日再续 `RV-15`）：`RV-1` 纯静态判定校验器（`analysis` / `acceptanceRef` / `pass`↔`score` / 宽松解析，分级 `Warn`→`Block`）/ `RV-2` 三态结论 `INCONCLUSIVE`（含 `review_recheck_log` 约束同步 + 统计 SQL 排除）/ `RV-3` `coverage[]` 结构化 + 静态覆盖批评器（覆盖判据 = `criterionRef` 子串归属，不用条数相等）/ `RV-4` 抽检独立性 + LLM 失败可观测 / `RV-5` 判定快照 / `RV-6` 驳回侧抽检（**已完成**，2026-11-05 口径定 B——驳回即冻结正文快照、复判复用冻结对象，`rejected` 桶出数经真机闭环，见 `LOG-20261105`）/ `RV-7` 交付正文编码损坏检测（2026-11-05 续号；三层修复：写侧清洗 / 核验侧检测 / 平台失败信号注入 `reviewer` 可见，`LOG-20261105`）/ `RV-8`~`RV-13` 审核侧证据可见性与驳回依据可达性（2026-11-13 续号：证据台账 / 按族配额与区间注入 / 表格机械预检 / 驳回依据可达性闸 / 有界分页工具 / 注入规则同源，见 `G-034`）/ `RV-14` 附件惰性注入沙箱 stage-in（2026-11-15 续号：`internal_stage_attachment_to_sandbox`，归属硬校验限本子任务 + sha256 对账，证据可达性从「可读」延伸到「可执行」，见 `G-034`）；`RV-15` 审核证据自动物化（2026-11-15 续号：有界证据集由平台注入 reviewer 沙箱 `/tmp/staged/`，stage-in 退为现场补拉通道；评审 F1~F4 修正口径 + 护栏 `maxFiles=40` 见核验双域实施计划 §3 `RV-15`，见 `G-034`）；`SR-1` 收尾轮放开仅 `internal_run_in_sandbox`（单轮上限按基线差值）/ `SR-2` 自审围栏 `EVIDENCE` 段 + 解析器 / `SR-3` `SelfReviewGate` + 同 turn 有界重跑 / `SR-4` 降级与观测 / `SR-5` 沙箱调用台账（**`SR-2`/`SR-3` 的事实源，前置**）。实施见 `plan/HelloAI 核验双域改造实施计划（2026-11-04）.md`，差距项锚定 `G-007`。**两域不混**：`RV-` = 他审（reviewer），`SR-` = 自审（执行收尾）。与 `S-x` / `W-x` / `ATT-x` / `PC-x` / `TD-x` / `SKX-x` / `RC-x` / `PA-x` / `M-x` / `REF-x.y` 并列；实测 `doc/**` 全库除该计划外无 `RV-`/`SR-` 号段 ⇒ 不撞号；**`SR-` 与 `S-`（OpenSandbox）前缀相近但可区分**（`S-\d+` 要求 `S` 后紧跟 `-`），文档一律写全 `SR-`，出现实际歧义改 `SF-x` 并同批修引用；**不用「阶段 / Phase」**（违反治理规则 §6.5 分期标签冻结令） |
| `SL-x` | **沙箱生命周期与台账证据专项任务**（2026-11-11 建号，`x` 取 1~10；2026-11-13 续 `SL-7`~`SL-10`）：`SL-1` 任务级作用域 / `SL-2` 终态销毁 / `SL-3` 工作区与回读 / `SL-4` 台账 `callId`+`origin`/`round` / `SL-5` 证据引用化 / `SL-6` 验证收口 / `SL-7` 容器寿命正确性（TTL 拆分 + 124 不销毁 + 空闲回收器）/ `SL-8` 沙箱运行时接口化（`WorkspaceRef` + `SandboxRuntime` 五方法 + 薄适配层）/ `SL-9` 职责拆分（runtime / registry / observation / installer / deliverable reader）/ `SL-10` 审核隔离（volume + `readOnly` + `EPHEMERAL_VERIFY`）；实施见 `plan/HelloAI 沙箱生命周期与台账证据改造计划（2026-11-11）.md`；差距项 `G-032` |
| `MSG-x` | **站内消息与置顶公告专项任务**（2026-11-12 建号，`x` 取 1~8）：`MSG-1` 共用出口契约（`NotificationType` 枚举 + `NotificationEvent` + `NotificationService` + **事件码→消息映射表**，标准化契约不标准化文案）/ `MSG-2` `user_notification` 表与幂等唯一索引 / `MSG-3` 钩子接线（挂在 `TaskTimelineService.recordEvent` 写点后，`recordEventInNewTx` 委托之、一处覆盖两入口，幂等键借 `task_timeline.id`，业务侧零插桩）/ `MSG-4` 首批映射登记 A1 草案就绪 · A3 报告完成 · A5 Agent 提交（内外共用，主通道）· B1 死信 · B2 无候选 / `MSG-5` 前端铃铛 + 消息中心 + 深链 / `MSG-6` 置顶公告（版本已读 + 编辑历史 + `announcement.enabled` 开关）**〔2026-11-15 二次裁定反转暂缓，纳入交付〕**/ `MSG-7` 覆盖补全与外部 Agent 上线消息（产物提交已归 A5 主通道）+ 时间线↔消息对账 / `MSG-8` 验证收口（含多副本幂等）。设计见 `design/HelloAI 站内消息与置顶公告设计.md`（`Status=Active`，已立项实现;2026-11-15 首次复核对当前代码订正：第二写入口 caveat、事件码行号刷新、外部 Agent 提交归主通道 A5;同日二次裁定反转置顶暂缓 + 新增 §3.10 平台开关，见该文档 §6 修订记录两次条目）。实施见 `plan/HelloAI 站内消息与置顶公告实施计划（2026-11-15）.md`（§3 逐条进度唯一事实源）;差距项锚定 `G-035`。**两开关 `notification.enabled` / `announcement.enabled` 默认 OFF、停发 + 隐藏，启用后才在工作台可见**;本次交付 `MSG-1`~`MSG-6` + `MSG-8` 核心验证，`MSG-7` 扩展覆盖列后续。与 `SL-x` / `PA-x` / `TD-x` / `S-x` / `W-x` / `ATT-x` / `PC-x` / `RV-x`·`SR-x` / `M-x` / `REF-x.y` 并列；实测 `doc/**` 全库无 `MSG-` 号段 ⇒ 不撞号；**刻意不用 `M-`**（已被「供应商 / 模型注册信息完善」占满）**不用 `N-`**（与 `B1`~`B5` 阻断项及「REF-5 之外 N 档」叙述易混）；**不用「阶段 / Phase」**（违反治理规则 §6.5 分期标签冻结令） |
| `MA-x` | **深度审核改造专项任务**（2026-11-18 建号，`x` 取 1~10）：`MA-1` 补 `parts` 读取方 / `MA-2` 候选集快照 / `MA-3` 技能执行台账（子任务维度，新建表不复用 `chat_skill_run`）/ `MA-4` 交付物登记闸（先单侧 warn-only）/ `MA-5` 工具面按需加载（只动内部面）/ `MA-6` 状态契约外化（**等触发条件**）/ `MA-7` ACP 适配器小样 / `MA-8` 安全扫描只记不拦 / `MA-9` 敏感动作审批（拦截 → 询问 → 恢复，**2026-11-19 追加**，前置 `MA-8` + `MA-10`；不新增任务级子号段，开工时再建 `MA9-x`）/ `MA-10` 执行链超时治理（次数主 + 时间兜底，**2026-11-19 追加**；`MA-9` 反过来依赖它；不新增任务级子号段，开工时再建 `MA10-x`）。蓝图见 `design/HelloAI 深度审核改造蓝图.md`（**点位与排序的唯一事实源**，不承载排期、不设进度百分比）；上游结论见 `research/HelloAI 开源对标复核（2026-11-18）.md`。与 `MSG-x` / `RV-x`·`SR-x` / `SL-x` / `SKX-x` / `TD-x` / `PA-x` / `PC-x` / `S-x` / `W-x` / `ATT-x` / `M-x` / `REF-x.y` 并列；实测 `doc/**` 全库 `MA-[0-9]` 仅命中该蓝图自身 ⇒ 不撞号；**刻意不用 `B-`**（`B1`~`B5` 已被目标架构能力层占用，`research` 里的 `B-1`~`B-7` 只是该报告内的临时序号、不入全局号段）；**不用「阶段 / Phase」**（违反治理规则 §6.5 分期标签冻结令）。**任务级子号段 `MA1-x` / `MA2-x` / `MA3-x`**（2026-11-18 建号）：分别对应 `MA-1` / `MA-2` / `MA-3` 的 plan 任务，`x` 取正整数、计划内连续不跳号、追加从 `MA<x>-10` 续编；实施见 `plan/HelloAI MA-1 执行结果结构化契约实施计划（2026-11-18）.md`、`plan/HelloAI MA-2 候选集快照实施计划（2026-11-18）.md`、`plan/HelloAI MA-3 子任务技能执行台账实施计划（2026-11-18）.md`（各计划 §3 任务表为逐项进度唯一事实源）。**任务级子号段 `MA4-x` / `MA5-x`**（2026-11-19 建号）：分别对应 `MA-4` 交付物登记闸 / `MA-5` 工具面按需加载的 plan 任务，`x` 取正整数、计划内连续不跳号、追加从 `MA<x>-10` 续编；实施见 `plan/HelloAI MA-4 交付物登记闸实施计划（2026-11-19）.md`、`plan/HelloAI MA-5 工具面按需加载实施计划（2026-11-19）.md`（各计划 §3 任务表为逐项进度唯一事实源；`MA-4` 本批只做**第一段「只观察不改行为」**，`MA-5` 只做**第一步「目录 + 按需取」**，均需观察期后方可判定完成）。**任务级子号段 `MA8-x` / `MA10-x`**（2026-11-20 建号）：分别对应 `MA-8` 安全扫描只记不拦 / `MA-10` 执行链超时治理的 plan 任务，`x` 取正整数、计划内连续不跳号、追加从 `MA<x>-10` 续编；实施见 `plan/HelloAI MA-8 安全扫描只记不拦实施计划（2026-11-20）.md`、`plan/HelloAI MA-10 执行链超时治理实施计划（2026-11-20）.md`（各计划 §3 任务表为逐项进度唯一事实源；两项均按观察期纪律**不标「已完成」**——`MA-8` 待真实误报率数字，`MA-10` 待「无进展」命中分布）。**2026-11-18 `MA-2` + `MA-3` 落地**：`MA-2` 候选集快照——选人器新增 `pickPreferredWithSnapshot` / `pickAlternativeWithSnapshot`，与原入口**共用同一条实现**（`selectFromCandidates`），返回 `Selection(Agent, DispatchDecisionSnapshot)`；四条选人路径（BLOCKED 自动重派 / PENDING 自动分配 / ASSIGNED 超时换人 / N11 回退）在**派单成功之后**按键合并写 `sub_task.context.dispatchSnapshot`（`updateContextInNewTx` 独立事务 + best-effort，写失败只 warn；无候选不写；`attempt` 重读 `attempt_total` 回填而非内存推算）；快照只留投影字段（`agentId` / `name` / `role` / `score` / `qualityRank` / `costRank` / `inFlight` / `tier` / `winner`），`model` / `apiKey` / `prompt` / `endpoint` 一律不入；维度未参与或未采集时记 `null` 而非 0（「不可比」与「最差」必须区分）。**查询口径（`MA2-7`）**：不新增端点——`SubTaskResponse` 已下发 `context`，事后用现有子任务详情 `getById` 读 `context.dispatchSnapshot` 即可。`MA-3` 子任务技能执行台账——新建 `sub_task_skill_run`（`V152`）；`SkillExecutionSupport.run` 收口为唯一入口 `SkillRunContext(scopeId, subTaskId, taskId, agentId, round)`（**旧三重载已删，无绕行入口**）；`finally` 里 best-effort 落库（`SkillRunLedgerRecorder` + `REQUIRES_NEW`，写失败不影响执行结果），成功 / 非零退出 / 超时(124) / SDK 异常(-1) / 输出截断全部记；`task_id` 为刻意冗余列（报表聚合省一次 join，代价显式登记）；`command_prefix` 只记前 200 字符（**非命令指纹**，判重须用 `exit_code + skill_name + round`）；业务面对话技能执行（`SkillPackageExecutionServiceImpl`，`scopeId` 是会话工作区键）**不落本台账**，其记录仍在 `chat_skill_run`。mapper 已备「按子任务列最近 N 条」与「按任务 + 技能聚合成功/失败」两个查询入口，**但 `SK-1` 技能效果报表仍未做**（仅数据就绪，报表与看板属另一批）。验证：core 定向单测 **165 例全绿**（新增 `DispatchSnapshotTest` 6 / `SkillRunLedgerTest` 8 / `SubTaskDispatchServiceTest` 增 2）；本地库手工执行 `V152` 幂等 SQL 并实测「建表 + 两条索引 + 按 MP 列清单 insert（含 `round`）+ 聚合」；架构冻结守卫未超基线。已知边界：快照覆盖式只有「最近一次」、不覆盖「为什么不派」（闸门拒绝原因走时间线事件）；沙箱未启用时台账表恒空属正常状态，不是故障。**2026-11-19 真机/复核补全**：`MA-2` 派单真机验证通过（`dispatchPendingSubTaskAuto` → `dispatchById/2120788867300339714`，`context.dispatchSnapshot` winner 唯一、`attempt=2` 重读回填、按 tier 排序、无敏感字段，与日志 `preferredAgentId=2120170093043707905` 一致；依赖未就绪的子任务被 ready 守卫拦截、不产快照亦属预期）；`MA-3` 无命令投递端点记为本机真机边界（代码+单测+真库已验证）；`MA-1` 对话流读侧剥离复核通过——`sub_task_execute` 产出 DB 12612B→API 9156B 精确切到块头前、无块消息原样返回，**prompt 注入消息（`sub_task_execute_user_prompt` / `subtask_review_prompt`）中的 `## EXECUTION_RECORD` 属模板示例代码块 / 待审素材注入，按设计原样展示（prompt 可观测性），不是剥离残留**。**2026-11-19 `MA-4` 第一段落地（只观察不改行为）**：判据组件 `DeliveryEvidenceEvaluator`（三取一：`parts` 非空 / 活跃 `Attachment` 存在（复用 `AttachmentPort.listActive`，**无新增 Mapper**）/ `output` 长度 > 阈值 `helloai.delivery.min-output-length`，默认保守可配置）+ 豁免（`noDeliverable` 或 `acceptanceKindDeclared` ∈ {`REVIEWED`,`ATTESTED`}）；`handleReport` 仅 `source=EXTERNAL && success=true` 时评估，不通过 → `log.warn` + 时间线事件 `delivery_gate_miss`（payload：`source`/`partsHit`/`attachmentHit`/`outputHit`/`outputLength`/`exempted`/`exemptReason`），**不改状态、不抛异常、不写 `gateFindings`**；内部链与失败路径零改动（测试锁死）。验证：core 定向单测 **48 例全绿**（`DeliveryEvidenceEvaluatorTest` 16 / `ExecutionResultHandlerDeliveryGateTest` 5 / 既有 `ExecutionResultHandlerTest` 21 / `ExecutionResultHandlerIntegrationTest` 6）；`verify-event-key-parity.sh` 全绿。观察期：用 `scripts/sql/observe-delivery-gate.sql`（命中分布 / 每日频次 / 产出长度分位 / 误报候选取证 / 事件明细五段，只读）核对误报率，**跑满一周后人工给出「升级第二段 / 维持第一段 / 关闭闸门」结论，在此前状态 = 「第一段落地，观察期中」，不标「已完成」**（蓝图停止条件：观察期内有人按约定周期看过误报率）。**2026-11-19 `MA-5` 第一步「目录 + 按需取」落地**：目录装配 `ToolCatalogAssembler`（生效集逐条出目录，`name` + 摘要，默认 160 码点封顶 `helloai.tool-catalog.description-max-length`、码点安全 + ASCII 词边界回退；白名单 `internal_run_in_sandbox` / `internal_get_sub_task_detail` / `internal_load_skill` / `internal_get_deps_summary` 保留全文）；`internal_load_tool(name)` 严格匹配 + turn 内缓存（`ToolInvocationContext.TOOL_CACHE`，生命周期同 `CALLED`）；注入点 = `RuntimeTurnExecutor.resolveVisibleCallbacks`（`TOOL_RESOLVED` 的 `tools`/`effectiveTools`/`removedTools` 语义零变化、`resolvedTools` 仍记原始描述）；`INTERNAL_LLM_TOOLS` 已登记；`ObjectProvider<ToolRegistry>` 破 Spring 循环依赖。验证：MA5-5 定向单测 24 例全绿（`ToolCatalogAssemblerTest` 10 / `RuntimeTurnExecutorToolCatalogTest` 6 / `AgentMcpServerServiceImplTest` 8）；对外守卫 H1/H2/H3 全 OK（无 `internal_*` 泄漏）。**重启后实测（11-19 14:19~14:33 真实链路）**：`tool_resolved` 生效集 18 工具含 `internal_load_tool`（改造前 16 且无此工具）、条件摘除 `search_knowledge` 如实反映；目录摘要使同一 resolve 集描述总量 **7223 → 4202 字符（省 41.8%）**，4 个白名单保留全文；三指标 平均 `inputTokens` 110180→32992、`iterations` 7.63→2.50、`toolCalls` 10.13→3.25、成功率 81.48%→100%（13/13）。`ci-gate.sh --no-clean` **5 门禁全部通过（0 项失败，用例 4099）**。**诚实边界**：两组任务类型不同（基线为 A2A/真机回放类、改造后为纯文本写作类）⇒ token 降幅不可单独归因于 MA-5，可归因部分只有「描述注入体积 41.8%」这一确定值；`internal_load_tool` 真实调用 **0 次**（模型 13 次调用全落白名单/短描述工具 ⇒ 「摘要够用」符合预期，但该工具触发率未观测到，需更长观察期）；改造后仅 8 个 `agent_completed`，不构成统计显著。状态 = **「第一步落地并有运行期实证」**，收益达成待更长观察期，不标「收益已验证」。**2026-11-20 `MA-8` + `MA-10` 落地（同日两批）**：`MA-8` 安全扫描只记不拦——6 条规则枚举唯一事实源 `SensitiveScanRule`（身份证过 GB 11643 校验位 / 银行卡过 Luhn / 令牌要求词边界+载荷长度 / 云 AK 厂商前缀白名单 / 前缀按长到短排列）+ 3 挂点枚举 `SensitiveScanPoint` + 配置 `helloai.sensitive-scan`（`enabled` **默认 true**：log-only 零行为变化，默认关掉就拿不到观察期数据）+ 纯判据 `SensitiveContentScanner`（`core/shared/util`，只 import `common.constant`；`MAX_HITS_PER_RULE=8`；`Hit` 刻意只含 `rule`/`offset`/`sha256`）+ 唯一「扫+记」入口 `SensitiveScanRecorder`（**绝不抛出**；`MAX_HITS_IN_PAYLOAD=12`；payload 结构上没有任何可放原文的字段）+ 时间线码 `SENSITIVE_CONTENT_DETECTED`。三挂点收口：① `RequirementMessageServiceImpl.addMessage` 的 `"user".equals(role)` 分支（澄清/助理/流式/编辑重跑四路共用，**挂各 Controller 会漏「编辑消息重跑」**）；② `ExecutionResultHandler` 成功分支（扫 `output` 而非 `parts` 全文，防多份大文件正文拖慢提交主链）；③ `AgentRuntimeContextAssembler` 的 `userPrompt` 装配之后（**覆盖「依赖产物把上游密钥传到下游」这条最易漏路径**）。验证：`SensitiveContentScannerTest` **20/20 全绿**（含 5 条形似不误报：`sk-learn` / 普通 16 位 / 时间戳 / 短前缀；含「结果结构不含原文」断言）；`verify-event-key-parity.sh` PASS=5；`ci-gate.sh` 5 门禁全绿。`MA-10` 执行链超时治理——**改动 1（尽力交付）落点重构**：原方案「`AgentLoopResult.maxIterations` 改 `success`」实测不可行（该字段被 `FinalReportFenceHook` 收尾轮判据（依赖 `success=false`）/ `BusinessSurfaceLoopRunner` / 既有 45 例单测共同消费）⇒ 改为新增 `isPartialDelivery()`（`!success && finishReason=MAX_ITERATIONS && text 非空白`）+ **只改消费侧映射**，既实现尽力交付又不掩盖「循环层未自然终止」真值；`VERIFICATION` 出口闸以 `naturalStop = loopResult.success()` 显式前置**豁免尽力交付**（模型没有第 21 轮可以补围栏）；空正文仍走空产出护栏判 `FAILED`（不被绕过）。**改动 2**：`resolveMaxIterations` = `clampHint(hint) ?: tierDefault`；`AgentContext.maxIterationsHint` 由装配层从 `SubTaskSnapshot.context()` 解析一次注入（执行层不查库、不耦合键字符串）；`MIN_MAX_ITERATIONS=2` 是循环体结构性下限（`forceFinalizeFromIteration = maxIterations - 1` ⇒ `<=1` 时护栏从第 1 轮就生效，模型连一次工具都调不了）；**非正值在消费点也收口**（实测抓出 `hint=0` 绕过装配层后被 `Math.min` 抬到下限，行为与「没给 hint」不同）。**改动 3**：新增列 `agent_execution_record.last_progress_at`（`V153`）+ Mapper `selectRunningWithoutProgressBefore`（条件 `COALESCE(last_progress_at, start_time)` ⇒ **存量 NULL 回落保证与改造前等价**）+ `touchProgress` + `ExecutionProgressRecorder`（`touch` / `touchWithApprovalAwareness`；**审批豁免 = 持续刷新而非跳过不写**——跳过会让时间冻在等待开始时刻，拖过阈值仍被杀；复用 `SubTaskQueryPort.findById` 的 `SubTaskSnapshot.context()`，**不为读一个键新增端口方法**）+ `ExecutionCompensationTask` 按 `noProgressDetectionEnforced` 分岔（`true` ⇒ 无进展接管且 `runningStuck=List.of()` 防同一记录处置两次；`false` ⇒ 只 warn 观察、仍走旧判据，**与 `MA-4` 第一段同源观察期纪律**）+ `isAwaitingApproval` 只读 `sub_task.context.pendingApproval` **存在性**（不引入审批表依赖 ⇒ `MA-9`/`MA-10` 两可独立回滚）。**改动 4**：新增 `LoopCancellation.ofToolTimeoutOnly`，删子任务链 `turnDeadlineSeconds` 装配（该闸在子任务链上本就是死代码——10 分钟看门狗先到），业务面 `BusinessSurfaceLoopRunner:189` 保留（该配置项仍有读取方，按判据 2 不删）。`lastAttemptTime` **不可复用**（语义是 DB Poller 扫描时间，不是进展时间）。验证：`RuntimeMaxIterationsResolutionTest` 11 / `RuntimePartialDeliveryTest` 13 / `ExecutionProgressRecorderTest` 9 / `ExecutionCompensationTaskTest` 7（原 3 + 新增接管/观察期/审批豁免/外部跳过 4）/ `RuntimeTurnExecutorTest` 21 / `BusinessSurfaceLoopRunnerTest` 11 / `FinalReportFenceHookTest` 45 全绿；`check-arch-freeze` 未超基线；`ci-gate.sh --no-clean` **5 门禁全绿 0 失败**。**两项状态均为「落地，观察期中」不标「已完成」**：`MA-8` 待真实误报率数字（蓝图停止条件），`MA-10` 待「无进展」命中分布（`noProgressDetectionEnforced=false` 只 warn）。**连带修复**：`ExecutionResultHandlerTest` / `ExecutionResultHandlerDeliveryGateTest` / `AgentRuntimeContextAssemblerTest` 三处 `@InjectMocks` 缺 `sensitiveScanRecorder` mock 致 NPE；`RuntimeTurnExecutorTest.shouldMapTokenUsageOnFailure` 原用「非空正文+有工具」验证失败分支（改动 1 已改判 SUCCESS）⇒ 改走空产出护栏保住原判据；`scripts/README.md` SQL 计数 1→2（`observe-delivery-gate.sql` 入库时漏同步，S6 当场抓出）。**同日第二轮（用户最关注项「上传/粘贴配置文件会不会提示」）`MA-8` 补挂点④ + `CONFIG_SECRET`**：三洞实测——对话/澄清链附件正文经 `ChatAttachmentPromptRenderer` 的 `{{ATTACHMENT_CONTEXT}}` 拼装绕过挂点③、`internal_read_attachment` 直读对象存储绕过③、消息 content 挂点①只扫正文不扫附件（改造前附件**从未被扫**，非「只记不拦」而是「根本没记」）。新增挂点④ `ATTACHMENT`：`ChatAttachmentParseWorker.parse` 的 `markReady` 之前扫描（markReady 后正文即注入 Prompt / ingest KB，此位之前是唯一可见附件完整原文的位置）；payload 带 `conversationId + attachmentId` 回溯具体文件；命中三态（`null`=未扫描 / `0`=无命中 / `>0`=命中，判据 10 如实区分）写 `chat_attachment.scan_hit_count`（`V153` 追加列）+ `scan_rules`；`markReady` 合并为一次 UPDATE（防「正文已落、摘要未写」中间态）。规则加第 7 条 `CONFIG_SECRET`（`password/secret/token/api_key/...` 键后 `=`/`:` 值赋值形态，排除占位值 changeme/xxx/`$VAR` 引用）。前端「上传即告知」：`ChatAttachmentVO` 带出 `scanHitCount`/`scanRules` → `RequirementChat.vue` 命中附件胶囊加敏感标记 + 提示行（含 `config_secret` 标签），**告知不弹卡**（弹卡属 `MA-9`，需误报率 + `MA-10` 验证两硬前置）。**顺带修复两真实缺陷**：①附件从未被扫；②scan 异常曾冒泡致整条附件解析 FAILED、用户须重传——挂点④ 调用点整体 try-catch（观测不得伤主链，判据 27，不依赖 recorder 单方面承诺）。验证：`SensitiveContentScannerTest` **27/27**（+7 CONFIG_SECRET）+ 新增 `ChatAttachmentParseWorkerScanTest` **7/7**（三态/开关关闭/scan 异常不阻断）+ 受影响 7 类共 130 例全绿；`verify-event-key-parity` PASS=5；`check-arch-freeze` 未超基线（planner(0)→agent(4) 顺向）；`ci-gate.sh --no-clean --skip-ui` **复跑 0 失败**（先前 integration 因 `target/classes` 残留旧 `V153__agent_execution_record_last_progress_at.sql` 与新版本号重复致 Flyway 失败，`rm -f` 残留后复跑通过，B 级集成 55 例全绿）。 |
| `ADR-x` | **架构决策记录**（`design/adr/ADR-00x-*.md`，三位数字）：`ADR-001` Run/Turn/Step 标识模型 / `ADR-002` 嵌入模型供应商 / `ADR-003` OpenSandbox 部署拓扑 / `ADR-004` 沙箱执行链与保真闸 / `ADR-005` 沙箱生命周期复用 / `ADR-006` app 加入沙箱网络 / `ADR-007` 技能执行异步化 / **`ADR-008` 任务拆解改为「派生式子任务」**（2026-11-09 立项，**2026-11-10 已接受**；暂存区/编号设计已并入其 §19，原设计文档已删除）。**`ADR-009` A2A 兼容边界**（2026-11-16 已接受；双平面 / 12 态内核不改 / 出站优先 / A2A task 身份与 subTask 解耦，见 `G-036`）。**已作废**：`TD-16a` 的 `blockedBy` 契约、`TD-16d②`、`TD-16e-3-1b`（`G3_ORPHAN`）、`TD-16e-4`（确认时扁平化）、`TD-16f`（废除栅栏）。`ADR-*` 原名「架构决策」，与 `ARCH-`（变更记录）分工：`ADR-*` = 决策本身、长期有效；`ARCH-*` = 决策发生的记录 |
| `ARCH-YYYYMMDD-NNN` | 架构决策（`log/HelloAI 架构变更记录.md`） |
| `LOG-YYYYMMDD-NNN` | 迭代记录（`log/YYYY-MM.md`） |
| `T-n` | **专项工作项局部编号**（2026-10-14 建号，`M-9` 起）：单个专项内部的任务拆分（如 `T1`~`T10`），**仅在该专项的文档/报告内寻址**；不承载排期、不进 §1 矩阵、不用于跨专项引用。实测 `doc/archive/**` 无 `T-` 号段 ⇒ 不撞号 |
| `QA-<专项>-nn` | **质量验证缺陷局部编号**（2026-10-14 建号）：随该专项的验证报告落 `doc/review/`，两个 `99` 门限内 `nn` 递增（如 `QA-M9-01`）；**不承载排期**，仅用于报告内寻址与整改闭环追溯 |

> `C-1`~`C-42`（原「差距表订正留痕」编号）**已于 2026-10-09 退役**——差距表订正与产出它的迭代记录同源，不再单独编号；一律改用 `LOG-YYYYMMDD-NNN` 寻址。`doc/archive/**` 内的 `C-NN` 为历史现场。

**状态词表（协作规约 §6.3 的九态；本表一律写中文值，英文名只作与协约对照）**

| 中文值 | 协约英文名 | 含义 |
|---|---|---|
| 待开始 | `TODO` | 未启动 |
| 设计中 | `DESIGNING` | 方案未定稿 |
| 实现中 | `IMPLEMENTING` | 正在写代码 |
| 验证中 | `VERIFYING` | 已实现、待验证 |
| 已完成 | `DONE` | 验收成立；剩余动作必须为 `—` |
| 部分完成 | `PARTIAL` | 主体已交付，仍有未达成项 |
| 阻塞 | `BLOCKED` | 有明确外部阻塞 |
| 暂缓 | `DEFERRED` | 条件触发或后置，不排期 |
| 明确不做 | `WONTFIX` | 已裁定不做 |

> **本表落位 = §1 矩阵「状态」列**（严格等于上表中文值之一，不含任何叙述）。四条配套口径：① 一行只允许一个状态值；子项状态不同时取最需关注态（偏序 `阻塞 > 暂缓 > 部分完成 > 待开始 > 设计中 > 实现中 > 验证中 > 已完成`），明细写在「剩余动作」列；② `明确不做` 只用于整行都不做，单子项不做不占用行状态；③ 第 3 列「当前状态」**不得**使用状态词（改用事实语），两列都表状态是新漂移源；④ **不设进度百分比列**，需要量化时用子项计数（`未达成 3 项`）。
>
> 机械守卫：`scripts/shell/verify-doc-gap-table.sh`（bash 实现，**已在 `ci-gate.sh` 门禁 3 执行**）。

> **历史分期编号注册表（2026-10-09 登记并冻结）**
>
> 项目历史上**并行存在 6 套互不相同的分期标签**，且**均以裸名 `Phase` / `阶段` / `Step` 引用**（限定词被省略），长期造成「同号不同义」。**自 2026-10-09 起全部冻结**：活文档与生产代码**不得再新增**分期标签；分期口径只进 `doc/log/`（做过什么）与本注册表（曾有哪些）。
>
> | # | 体系 | 语义 | 取值 | 历史载体 |
> |---|---|---|---|---|
> | ① | **P0-C 能力吸收** | `AgentRuntime` 八件套逐件落地 | `Phase 0`（契约）→ `Phase 1 Step 1~4`（Skill / Tool / Session / Env 供电）→ `Phase 2`（Tool Executor）→ `Phase 3`（AgentLoop）→ `Phase 4`（Sandbox） | `doc/log/` 各月；`design/adr/ADR-001`；协作规约 §19 |
> | ② | **ADR-001 标识模型** | Run / Turn / Step 落地 | `Phase 0`（仅定义契约）/ `Phase 2`（AgentLoop 多步化）/ `Phase 3`（Plan 实体路线） | `design/adr/ADR-001-run-turn-step-model.md` |
> | ③ | **执行命令链** | 双轨切换 / Outbox / Doorbell / 命令 MQ | `Phase 0 A*`·`B*`·`C*`；`Phase 2D`·`2E`·`2F`·`2H` | 生产代码 javadoc 历史原注 |
> | ④ | **最终报告整合** | 报告链改造 | `阶段二` / `三` / `四`（`3A` / `3B` / `3C`） | `G-016`；`LOG-20260928-*` |
> | ⑤ | **V2 里程碑** | 项目级路线 | `阶段 0`（MVP）~ `阶段 5`（能力经济性） | `review/HelloAI 架构V2进度与质量审计报告（2026-09-29 / 09-30）.md` |
> | ⑥ | **开发生命周期** | 每次开发任务的流程（**仍活跃 ⇒ 保留**） | `Step 0`（工作区检查）~ `Step 9` | 协作规约 §10-14 |
> | ⑦ | **运行时字段** | `step` = 事件类型槽位（**非分期**，仅同名） | `0` / `5` / `6` / `7` … | `ADR-001 §5.2`；`agent_event.step` |
> | ⑧ | **真身灰度联调**（原口径名「灰度第 0 步」，2026-10-09 补登记） | P0-B 双轨切换的联调执行口径（现已终结：灰度机制随单轨硬切不复存在） | `第 0 步`（2026-09-08） | `doc/archive/log/2026-09.md` `LOG-20260908-006`；`archive/implemented/HelloAI 重构实施计划.md`（已改称能力名） |
>
> **已知同号冲突（去标签的直接动因）**：①↔② 对 `Phase 2` 定义不同（① = Tool Executor，② = AgentLoop 多步化）；①↔③ 撞号（`Phase 2E/2F/2H` 属命令链）；`Step`／`步` 多义（① 能力供电 / ⑥ 开发流程 / ⑦ 字段值 / ⑧ 灰度联调）；`阶段` 二义（④ 报告 / ⑤ 里程碑）。
>
> **处置（2026-10-09）**：①~⑤ 已在**活文档与生产代码**中去除裸标签（保留能力名、`ADR-*` / `LOG-*` / `N-0xx` 等**durable 锚点**）；`doc/archive/**` 与 `doc/review/**`（历史现场 / 报告）**保留原样**；⑥ 保留；**⑧ 于 2026-10-09 补登记并同批去标签**（`archive/implemented/HelloAI 重构实施计划.md` 三处改称「真身灰度联调」+ 挂 `LOG-20260908-006` 锚点）。

**当前生效的取舍决策**

> **只列仍然约束未来判断的取舍。** 其余裁定的完整论证、反证过程与验收细节在 `log/`（按 `D-YYYY-MM-DD-N` 编号可检索）——本表不复述，复述必与代码事实分叉。

### 仍然生效的取舍

- **`D-2026-09-29-1`｜MVP 可用性优先于 P1 剩余项推进**（阶段性有效）：事故清偿告一段落后**必须回到 P1 剩余项**。**复审触发**：① 新 P0 事故；② P1 剩余项连续 2 个迭代未启动；③ 用户要求推进隔离 / 治理类能力。
- **`D-2026-09-29-2`｜验证与工程化基础设施优先于功能推进**：新建功能之前先补齐 CI 门禁、可用 JDK 固定、架构漂移冻结守卫、B 级 IT。
- **`D-2026-09-30-3`｜不补回退手段（硬切 = 单向门）**：撤销的是「回退开关」；**不撤销**「兜底策略」（超时重派 / 掉线回收 / 死信 / 孤儿巡检）与「同层恢复」。回退手段 = `git revert`。

### 其余裁定索引（编号可检索，正文只在 log）

| 日期 | 编号 | 裁定 | 记录 |
|---|---|---|---|
| 2026-10-09 | `D-2026-10-09-4` | 借鉴落地专项立项与四项裁定 | `log/2026-10.md` |
| 2026-10-09 | `D-2026-10-09-5` | Fork 驱动执行不立项（WONTFIX） | `log/2026-10.md` |
| 2026-10-09 | `D-2026-10-09-6` | 借鉴落地专项余下五项裁定 | `log/2026-10.md` |
| 2026-10-10 | `D-2026-10-10-1` | 能力包安装入口（REF-1.6）技术裁定 | `log/2026-10.md` |
| 2026-10-11 | `D-2026-10-11-1` | RAG 嵌入模型选型与密钥管理四项裁定 | `log/2026-10.md` |
| 2026-10-11 | `D-2026-10-11-2` | 事件码改服务端单一来源（取代 D-2026-10-09-6③-4 的「事件码 guard-only」） | `LOG-20261011-008` |
| 2026-10-10 | `D-2026-10-10-2` | REF-2 备份 / 恢复实施方案六项裁定 | `log/2026-10.md` |
| 2026-10-12 | `D-2026-10-12-1` | 内部 LLM 能力面立项（REF-8 / G-021） | `LOG-20261011-007` |
| 2026-10-12 | `D-2026-10-12-2` | REF-8 开工 + 主线指针 + 覆盖面清单 | `log/2026-10.md` |
| 2026-10-12 | `D-2026-10-12-3` | LOG-20261011-007 A+B 立项 | `LOG-20261011-007` |
| 2026-10-12 | `D-2026-10-12-4` | REF-7 三约束定稿 + 继续挂起 | `log/2026-10.md` |
| 2026-10-12 | `D-2026-10-12-5` | REF-8.1 对外面隔离 + 执行链绑定闸门（安全口径收口） | `LOG-20261012-006` |
| 2026-10-13 | `D-2026-10-13-6` | REF-8.4 自动触发接线 | `LOG-20261013-011` |
| 2026-10-13 | `D-2026-10-13-7` | REF-8.4 可移植性 | `LOG-20261013-011` |
| 2026-10-13 | `D-2026-10-13-8` | A/B 词表是否拆分 | `LOG-20261013-011` |
| 2026-10-13 | `D-2026-10-13-9` | 形态甲/乙（V52 加列退列 vs 泛化能力谓词表） | `LOG-20261013-011` |
| 2026-10-13 | `D-2026-10-13-10` | requiredBaseCapabilities 挂点 | `LOG-20261013-011` |
| 2026-10-13 | `D-2026-10-13-11` | M-x 号段建号 + minimax 换代口径（含「发布前修改已 apply 的 Migration」例外留痕） | `LOG-20261013-015` |
| 2026-10-14 | `D-2026-10-14-1` | M-3 模型目录种子的播种口径与承载（含「新增 provider 不建 yml 段」） | `LOG-20261014-001` |
| 2026-10-14 | `D-2026-10-14-2` | M-4 能力元数据回填的承载形态与口径（形态乙落地 + deepseek-v4-pro 模态冲突留痕） | `LOG-20261014-003` |
| 2026-10-14 | `D-2026-10-14-3` | M-5「extraConfig 只写不读」闭环 | `LOG-20261014-004` |
| 2026-10-14 | `D-2026-10-14-4` | M-6 Key 指纹标识的承载与去重口径（含「指纹不可 SQL 回填 ⇒ 应用层惰性回填」「回填必须落在 system 域」） | `LOG-20261014-005` |
| 2026-10-14 | `D-2026-10-14-5` | M-6 指纹「对齐对象」归属订正（保留 24 字符口径） | `LOG-20261014-006` |
| 2026-10-14 | `D-2026-10-14-6` | M-7 批量录入去重的拆分口径与落库形态（含「不覆盖主 key」「备位只能落 DISABLED」） | `LOG-20261014-007` |
| 2026-10-14 | `D-2026-10-14-7` | M-8 多 Key 主备的手动切换语义与「启用位不可缺失」判据（含脱敏口径统一、明确不移植 failover/routing） | `LOG-20261014-008` |
| 2026-10-14 | `D-2026-10-14-8` | M-9 能力谓词消费方切换与 V52 两列退役（含 modality 不映射技能词表、thinking 改由 reasoning 驱动） | `LOG-20261014-009` |
| 2026-10-15 | `D-2026-10-15-1` | G-022 补引用边（不统一词表）；QA-M9-03 收口 | `LOG-20261015-001` |
| 2026-10-15 | `D-2026-10-15-2` | 外部漏洞清单核验结论 + 4 项可靠性修复（选择性修复） | `LOG-20261015-002` |
| 2026-10-15 | `D-2026-10-15-3` | 三项遗留项落地（HIGH-3 事件契约订正 / G4 幂等键 / MED-1 核验侧质量分） | `LOG-20261015-003` |
| 2026-10-15 | `D-2026-10-15-4` | 剩余四项评估：MED-3/G8 升级为真缺陷并修复，MED-4/G6 经核不改（含订正我先前的错误建议） | `LOG-20261015-004` |
| 2026-10-15 | `D-2026-10-15-5` | D-2 多实例鉴权：MCP 鉴权上下文外置 Redis（静态工具类 → @Component） | `LOG-20261015-005` |
| 2026-10-15 | `D-2026-10-15-6` | REF-8.4 交付状态回填 + A/B 挂点口径订正（文档漂移清偿） | `LOG-20261015-007` |
| 2026-10-15 | `D-2026-10-15-7` | 门禁 3「守卫自身失真」三类缺陷清偿 + Shell 可移植性守卫 | `LOG-20261015-008` |
| 2026-10-16 | `D-2026-10-16-1` | LOG-20261011-007 A（能力闸）+ B（收敛熔断）实施，含两处前提/判据订正 | `LOG-20261011-007` |
| 2026-10-16 | `D-2026-10-16-2` | 内部 LLM 运行时合同核对的**基准口径**改为「以 DSH 为准」（原报告 G1~G4 重排） | `LOG-20261016-002` |
| 2026-10-16 | `D-2026-10-16-3` | plan/ 侧文档漂移清偿 + ReviewProperties 注释恢复 | `LOG-20261016-003` |
| 2026-10-16 | `D-2026-10-16-4` | REF-8.5 覆盖面边界裁定 + G1~G4 立项（REF-8.6~REF-8.9）+ 上线清单触发条件改写 | `LOG-20261016-004` |
| 2026-10-16 | `D-2026-10-16-5` | REF-8.5 落地形态三条裁定 + 两条单口径抽出 | `LOG-20261016-005` |
| 2026-10-16 | `D-2026-10-16-6` | REF-8.6 取消 / 超时树落地：装配点收敛「唯一咽喉」+ 超时与取消语义分离 | `LOG-20261016-006` |
| 2026-10-16 | `D-2026-10-16-7` | REF-8.7 max-tokens 终态落地：sticky 靠「出口定性」而非挂点自觉 | `LOG-20261016-007` |
| 2026-10-16 | `D-2026-10-16-8` | REF-8.8 观测面 usage 互斥拆分落地：「只扩字段、不改控制流」用「加分量 + 末位装配」 | `LOG-20261016-008` |
| 2026-10-16 | `D-2026-10-16-9` | 整合报告「自检围栏顶替正文」事故修复：改写型挂点的「原物仍在」出口校验 + 交付物净化 + 审查判定宽松兜底 | `LOG-20261016-009` |
| 2026-10-16 | `D-2026-10-16-10` | BLOCKED 自动重派巡检落地：关闭 plan §10.7.6 的唯一缺口（方案 b） | `LOG-20261016-010` |
| 2026-10-16 | `D-2026-10-16-11` | Recovery 消费面方向与落地（T1 序 4，会议裁定） | `LOG-20261016-011` |
| 2026-10-16 | `D-2026-10-16-12` | RM9-a：McpToolServiceImpl 上帝类拆分首笔（切分线执行 + 锚点重取 + 守卫留门面） | `LOG-20261016-012` |
| 2026-10-16 | `D-2026-10-16-13` | RM9-b：SubTaskServiceImpl 上帝类拆分第二笔（状态机唯一入口整链搬迁 + 两档验收口径落地） | `LOG-20261016-013` |
| 2026-10-17 | `D-2026-10-17-1` | RM9-c：RequirementClarifyServiceImpl 上帝类拆分第三笔（门面 ⇄ 编排器破环 + renderPrompt 按模板拆分） | `LOG-20261017-001` |
| 2026-10-17 | `D-2026-10-17-2` | RM9 命名合规整改：service.impl 命名归位 + 静态投影助手迁出 {domain}.projection | `LOG-20261017-002` |**当前冻结基线**

**当前冻结基线**

- **§11.3「Controller 不得暴露 core Entity」**：命中 **19 处 / 8 控制器**（冻结未清偿 —— 改动 API 响应契约须与 `helloai-ui` 联动，单独立项）；校验 `scripts/powershell/verify-code-style-p0-layer.ps1 -StaticOnly`。
- **架构守卫**：组 1 反向 / 组 2 mapper 为 `block`，基线全 0；组 3 前向为 `warn`（`task->agent` 57 / `planner->agent` 34 —— 后者含 `WebSearchToolCallback` 对 `ToolContext` 的声明面依赖，属 §7.2 端口反转的机制性代价，**不刷冻结基线**）。**组 3c `api->core.entity` 当前 52（基线 49）**：该 +3 系 `InstalledSkillPackage` / `PlatformBackup` / `SysUser` 三条 `...core.<domain>.entity.` import 于基线刷新（`1d38508f`）之后落地，**与 `M-6`~`M-8` 无关**（`M-8` 新增 import 全落 `core.*.port`，不匹配该规则）；同属 `warn` ⇒ 不拦截、**不刷冻结基线**。**`M-9` 复核（2026-10-14）**：本批新增 import 全落 `core.system.port` / `core.agent` / `core.shared`，同样不匹配 `...core.<domain>.entity.` 规则 ⇒ 组 3 各计数**不变**，`M-9` 贡献 **0 漂移**（亦无新增告警）。**`BLOCKED` 自动重派批（2026-10-16）**：新增 `job->core.entity` **14 → 15**（`SubTaskBlockedAutoRedispatchTask` 读 `SubTask` 实体，与既有 `SubTaskPendingOrphanTask` **同型**，属 job 层既有惯例、不新增模式）；同期另有 `planner->agent` 33 → 34、`api->core.entity` 49 → 52 两条提示，均于本批之前落地。组 1（反向依赖）/ 组 2（跨域 Mapper）**仍全 0** ⇒ 同属 `warn`，不刷冻结基线。**`RM9-b`（2026-10-16）**：`task->agent` **57 → 60**（+3）—— 拆分把门面的 11 条 agent import **重新分布**到 3 个职责类共 14 条（净 +3），**全为 §6 合法前向、未新增依赖模式**（`SubTaskStateMachineService` / `SubTaskNotificationService` / `SubTaskLeaseService` 三类的 agent 依赖与门面原有一一对应）；`planner->agent` 34、`api->core.entity` 52、`job->core.entity` 15 均经 `git grep … HEAD` 实测为**提交前既有**；组 1/2 **仍全 0** ⇒ 同属 `warn`，不刷冻结基线。

---

# 1. 总体矩阵

| 编号 | 项目 | 当前状态 | 目标（最终口径） | 优先级 | 状态 | 剩余动作 |
|---|---|---|---|---|---|---|
| G-001 | Agent Event Stream | Run / Turn / Step + Event 契约；Timeline 并轨 agent_event；Replay / Audit 经 REST 四端点 + UI 事件流工作台 | 统一事件契约与消费体系；Recovery 消费面提供只读恢复点投影与续跑兜底 | PR0 | 已完成 | — |
| G-002 | Executor 迁移 | `RuntimeTurnExecutor` 为唯一 `AgentRuntime`；旧链入口全删；无配置级回退开关 | Runtime 成为唯一执行契约 | PR0 | 已完成 | — |
| G-003 | AgentRuntime | 八件套契约齐备；工具侧两个语义位（按条件可用 / 按上下文动态描述）生效；`CRITICAL_TOOLS` 三件套不可关闭 | Context + Session + Skill + Tool + Loop + Event + Environment + SandboxProvider 全落地 | PR0 | 已完成 | — |
| G-004 | Skill Capability | 元数据源 = classpath frontmatter + 目录扫描（9 字段）；技能包安装入口含摄入闸门、受控存储与审计 | Metadata / Version / Tools / Schema / Dependencies 全量 + requiredTools→tools 联动 + 受控存储安装 | PR1 | 已完成 | — |
| G-005 | Sandbox Provider | **OpenSandbox Provider 现役并被执行链消费**（`S-1`~`S-9` 完成，`OpenSandboxProvider` + 声明式 spec + 五边界策略 + 探针；`RuntimeTurnExecutor` 经 `SandboxProvider.resolve` 使用）；`S-10` 危险命令守卫未实现；技能执行链已在容器内真机跑通（`internal_run_skill` 模型自主触发，SKX-9 已验真） | 真正的 Provider 化执行环境与隔离策略 | PR1 | 暂缓 | 条件②已命中，重排期待议。**2026-11-06 事实并入**：Provider/执行链事实见 `design/Sandbox_Provider.md`，实施记录归档于 `archive/implemented/HelloAI OpenSandbox 沙箱集成计划.md`；**触发条件①「平台增加碰宿主工具」③「平台自持浏览器」仍未命中**；SKX-9 判据③外链口径经用户裁定保留署名、技能按通过。详见 `plan/HelloAI 技能执行面接入实施计划（2026-10-26）.md` §SKX-9 |
| G-006 | Replay / Audit | 写侧 + 对账闭环；Timeline / Replay / Audit 三读侧经 REST + UI 工作台；外部执行轨迹与事件同址 | 基于统一 Event 查询回放；外部 Agent 全周期轨迹可对齐 | PR1 | 已完成 | — |
| G-007 | Quality Gate | Reviewer 闭环（执行 → Review → PASS / REJECT → REWORK） | Rule + Test + LLM 统一决策 | PR2 | 待开始 | 统一决策未启动，当前仅有链上增强 |
| G-008 | Agent Fleet Routing | 选人四维（Capability / Health / Load / Policy）+ 成本维度已入比较链；外部 tokens 回报协议就绪 | 五维选人完整生效，成本可参与候选间比较 | PR2 | 部分完成 | 成本候选间比较待第 2 个 LLM agent 样本；成本闸门接线决策 |
| G-009 | Dynamic Workflow | 模板 / 版本 / 实例化 / DAG | 动态分支与复杂运行期编排 | PR3 | 暂缓 | 动态分支与运行期编排未启动 |
| G-010 | Planner 能力感知与自适应粒度 | 技能目录注入拆解 Prompt；子任务级 requiredSkills / constraints 指派；粒度三档 rule-based 决策矩阵；审查侧同口径核验 | 技能感知 + 粒度自适应 + 外部下行通道 + 技能回流贡献规范 | PR1 | 已完成 | — |
| G-011 | 需求包准入与不确定性显式管理 | 结构化需求包双写；uncertainties 显式 JSONB 列；执行侧注入 + 审查侧分级语义；驳回带结构化返工线索 | 需求包准入与不确定性显式管理的全链路闭环 | PR1 | 部分完成 | `gap_kind` 实现路径分类与任务后蒸馏闭环 |
| G-012 | 登录鉴权与 RBAC（Sa-Token） | Sa-Token 会话 + 角色-权限码 RBAC + 管理 API + 前端动态菜单按权限码过滤 | 统一会话 + 权限码管理 + 注解鉴权 + 动态菜单 | PR2 | 已完成 | — |
| G-013 | 基础架构深化（RBAC 底座） | 动态路由（权限 = 可达性）+ v-auth 按钮级 + 动作级权限码 + 菜单树驱动 addRoute + 组织架构 + 数据权限规则 | 对齐 JeecgBoot 标杆的权限与组织体系 | PR1 | 已完成 | — |
| G-014 | 外部 Agent 执行通道 | 外部通道与内部分发同口径：依赖门禁 / 返工出口 / 附件可发现 / 截断元数据 / 在线语义 / 核验边界 | 外部 Agent 通道与内部分发链同口径，返工可自救 | PR0 | 已完成 | — |
| G-015 | 心跳 / 重派止血 | 值班租约守卫 + 在飞宽限 + 退避独立列 + 离线置 PAUSED + 无候选 409 可读原因 | 长任务不被心跳窗口误判离线；判离线读写两视图口径统一 | PR0 | 已完成 | — |
| G-016 | 报告整合质量 | 报告链贯通：物化附件优先 + 附件限额单源 + 块级截断 + 审查异步化 + 三级容错 | 报告按主题归并可追溯、驳回可返工、自审不静默放行 | PR1 | 已完成 | — |
| G-017 | 附件存储一致性 | `ArtifactStorage` 双实现 + 注册前置校验 + 6h 对账巡检（三类引用源）+ 孤儿清理三重保险（默认关闭） | DB 与对象存储两侧一致，悬空 / 孤儿可发现可修复 | PR1 | 部分完成 | 部署侧改 MinIO 默认凭据 + 端口白名单；技能包来源正面样本待造（本机为空集） |
| G-018 | 备份 / 恢复 | pg_dump 全库 + 对象清单 + manifest peek + 单飞锁 + 恢复三门 + 停机 runbook | 全库与对象可备份可恢复；运行中备份不保证多文件同一瞬间 | PR1 | 已完成 | — |
| G-019 | RAG 知识库 | `kb_chunk`（vector + HNSW-cosine）+ 余弦检索 + `search_knowledge`（无 KB 即摘）+ 注入预算 + 引用溯源 | PG 单后端检索 + 条件可用语义位 + 引用溯源可点开 | PR2 | 已完成 | — |
| G-020 | 外部 Agent 工作详情快照 | 完全空白：只能审计到订单层面，子任务内部执行为黑盒 | 事后、经审批、可选提交工作详情快照 → 原文落 MinIO + 结构化审计事件 | PR2 | 待开始 | 动手前须定三项约束：与审计 schema 的张力 / 审批方 / 落时间线粒度 |
| G-021 | 内部 LLM 能力面 | 工具面已解封（读详情 / 依赖 / 状态 / 附件、交付登记、技能目录与惰性加载、自报阻塞）；五个业务面进入循环，其余十一面留单次 | 内部 LLM 有工具、有手脚、能按需装配技能 | PR1 | 已完成 | — |
| G-022 | 选人技能门（命名空间一致性） | 引用边已补（`requiredBaseCapabilities` + `satisfiesPackage` 唯一判据）；注册链派生；拆解侧承接者校验 | 技能门两端同口径：声明侧声明即有人具备 | PR1 | 部分完成 | 存量 `agent.skills` 回填（须重注册触发）；`eng-*` 装进内部 LLM 工具授权集 |
| G-023 | 内部 LLM 运行时合同 | 取消 / 超时树 + max-tokens 独立终态 + usage 五分量拆分均已接线；独立预算闸未做 | 可取消可超时、截断不静默成功、用量可拆解 | PR1 | 已完成 | — |
| G-024 | 附件与联网检索的编排 | 决策视图注入 + 模板「附件优先」四条硬规则 + 元词过滤（含兜底同源判定）+ `ATT-5` 附件意图三态（真机三轮验证：`answerable` 不联网 / `none` 不注入正文 / `needs_external` 带锚点检索）+ `ATT-6` 补搜多变体（**真机通过**：检索词为附件外部锚点、补搜 3 条不同角度、回答区分附件结论与外部核验，另补「首轮满额短路」防白付评估 LLM）+ `ATT-8` 进度文案取真实候选词（**真机通过**：检索词「规划 → 发进度帧 → 再执行」两阶段，进度帧与结果帧同源，不检索时显示跳过原因；另 `ATT-3` 片段重排接入附件检索并真机通过：开启有重排日志 / 关闭保持向量序，开关默认关） | 检索词由附件外部锚点构成；附件无可核验实体时不触发联网 | PR1 | 已完成 | — |

| G-025 | 任务拆解的领域无关化 | 拆解只有一档：静态 DAG + 软件交付语法（`planner-decompose.md:60-93` 契约先行/验收模板均为研发语境）；`GRANULARITY` 只控「拆多细」不控「按什么维度拆」；`task` 表无领域或交付物类型维度；拆解输入仅 `title`+`description`+`requiredSkills`+`difficulty`+需求包；验收 `acceptance` 为自由文本、无判据类型 | 按交付物判据分档：`task.task_archetype` 六类（BUILD/DOCUMENT/RESEARCH/DATA/OPERATE/DECIDE）+ `sub_task.acceptance_kind` 四类（AUTOMATED/STRUCTURAL/REVIEWED/ATTESTED）+ 每类对应的拆分维度与终止锚 + 拆解期上下文快照 + 「计划未知」任务的 ledger 通道 | PR1 | 部分完成 | `TD-1` / `TD-2` **已完成**（迁移 `V124` / `V125` 应用重启后复核 `Success`：存量 10 条任务全 `BUILD`、45 条子任务全 `AUTOMATED`，CHECK 拒绝非法值 `NOPE`/`MAYBE` 已实测）；`TD-3` 提示词分档已落地 + **真实样本验收②通过**（`taskArchetype=DOCUMENT` 任务产出 5 条草案，title 脱离「写引言/写正文/写结论」结构；**观察项已解决**：模型已遵从 `acceptanceKind` 结构化字段——重拆实测 5 条全显式声明，见 `LOG-20261024-043`）；`TD-4` **已完成**（`package.archetype` → 防御式解析 → 写 `task.task_archetype`，未走澄清链路回落 `BUILD`，单测锁定回落路径；六类真实判定待澄清链路回归观测）；`TD-5` **已完成**（`AcceptanceKindValidator` 四套 kind 校验 + 显式声明 fail-close / 缺失回落 Warn 放行 + `acceptance ≥ 10` / `deliverable ≥ 8` 字符下限质量闸；**真实回归全通过**——fail-close 拦截显式错配（`2113976255295021058` 整批拒绝 + `task_plan_failed`）、长度闸零误伤（真实产物最短 219/46 字符）、**写侧缺口根治**（`SubTaskMapper.xml` 自定义 `insert`/`updateById` 补 `acceptance_kind` 列，重拆终验库里 5 条落显式 kind：STRUCTURAL×4 + REVIEWED×1，insert 日志参数带 `STRUCTURAL(String)` 铁证）、**验收③ REVIEWED 接审查域完成**（timeline payload 补 `acceptanceKind`+`analysis` 逐条核验、对话流结果文本补「逐条核验」段，单测 61+8 例全绿，见 `LOG-20261024-044`；另人工修正两个历史回归任务 `2113947011709964289`/`2113971974332534786` 共 11 条草案 kind——写侧缺陷期间产物，文本 STRUCTURAL/REVIEWED 风格库记 AUTOMATED，修正后 9 STRUCTURAL + 2 REVIEWED）；`TD-6` / `TD-7` 已完成；`TD-8` **已完成**（澄清侧 stall 计数——**判据经用户拍板 = 用户信息量判据**：selections 的 values/customText 全空 + 纯文本敷衍词即「无新信息」，连续 2 轮（可配置）强制收敛出终稿、不自动建任务、未决项 openQuestions 完整列出；`ClarifyStallGuard` 纯函数 + `runConvergeRound` 接入，单测 13+3 例全绿，见 `LOG-20261024-045`）；`TD-9` **已完成**（单条草案细拆 `expandById` + 同粒度重写 `regenerateById`——expand：单条 → N 条 FINE 子草案、组内依赖 Kahn 校验后落库回写、**逻辑删原草案**、被其他草案依赖拒绝展开（幽灵依赖 fail-close）；regenerate：**不变量白名单**只改 content/deliverable/acceptance/acceptanceKind，title/id/dependsOn 一律不动（LLM 输出被忽略取原值）；专用提示词 `planner-expand.md`/`planner-regenerate.md` + `SubTaskService.updateDraftContent` 写命令 + `POST /sub-tasks/expandDraftById/{id}`/`regenerateDraftById/{id}`（`subtask:edit`）+ 前端草案审阅弹窗「展开」「重生成」按钮；同步执行 `@Transactional` 失败回滚不破坏原草案；单测 5 例全绿 + 全量 2601 例全绿，见 `LOG-20261024-046`），`TD-10` 登记不启动；**能力部分生效**——`TD-4` 已接 ⇒ 走澄清链路的任务可自动判 archetype，未走链路仍 `BUILD`；`TD-5` 已接 ⇒ 显式声明的 `acceptance_kind` 触发分流校验（错配 fail-close）且落库保留显式 kind（写侧已补列），缺失回落放行不影响存量；`TD-8` 已接 ⇒ 连续无信息收敛出终稿不建任务；`TD-9` 已接 ⇒ 草案态支持局部返工（细拆/重写）；**2026-10-25 续建 `TD-11`（验收契约可满足性标注，只标不拒）+ `TD-12`（核验侧前置条件豁免）**——起因是契约死锁事故：`content` 要求「不要自行替换成其他产品」∧ `acceptance` 要求「≥15 条带真实 URL 的事实」⇒ 执行者无解，两版产出各走极端均判 2 分，且驳回理由因 `SubTaskReviewServiceImpl:712` 的 `rework()` 早退被整条吞掉（`B1a`）；`TD-5` 未拦住是因 `validateAcceptanceKinds` 签名不含 `uncertainties`、`AcceptanceKindValidator` 只判文本写法（见 `LOG-20261025-014`）；**2026-10-25 批次 6 已落地 `B1a`（驳回留痕前移到 `rework()` 之前）+ `B1b`（事件行渲染评分与 issues）+ `TD-11`（只标注不阻断，`AcceptanceSatisfiabilityChecker` 四条件 + `ref` 补齐）**，全量 2930 例 0 失败、守卫全绿，`TD-11` 置「验证中」待真机样本；`B2` 按计划留第 8 批（见 `LOG-20261025-015`）；**同日真机全链路实测 + 一次纠错**：拆解产出 8 条草案、`TD-11` 标注机制全通（`ref` 落库 / `context` 键 / 时间线事件均正常）但**准确率 0**——标了 2 条都不阻断、漏了实际 `BLOCKED` 的 2 条；期间一度据「`acceptance` 内部自相矛盾」提出「甲判据」，被同一任务的终审记录推翻（草案 4 的第 5 条与草案 2 **一字不差**，且 round 2 `APPROVED score=4`，理由明写「有官方来源」∧「全文未出现代号—真实产品对应表述」）⇒ **甲判据撤回**、`TD-12` 豁免入口维持 `acceptancePreconditions` 不变（见 `LOG-20261025-018`）；同轮修 `B3`（终稿 `archetype` 回落不可见 → `TaskArchetypeResolver` 纯函数 + `task_archetype_fallback` 事件 + 7 例单测，真机现场：终稿包顶层键**根本没有 `archetype`**，落库却是 `BUILD`）并登记 `B4`（`sub_task_report_blocked` 的 `reason` 恒为空，失败原因在相邻 `execute_failed` 事件上 ⇒ 转展示层合并，不动执行侧契约）；见 `plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md` §0 ④；**2026-11-07 新增 `TD-13`（拆解整批自检与局部重修）并落地 `S1`/`S4`**——门 1/2 落库前阻断（`PlanBatchGateChecker`，`ruleVersion=s4-5`）+ 阻断留痕独立事务（`task.context.gateFindings` + `task_plan_gate_blocked`，真机 4 provider 验证、全量 3222 例 0 失败）；同日续补 `P1-D`（确认前重跑门 2，杜绝「拆解时合规、编辑后转正」）与 `P2-1`/`P2-2`（`expandById` 返回写后回读视图；`gateFindings` 写与清收进 `PlanGateFindingsStore` 单出口，修掉 `confirmPlan`/`rejectPlan` 成功路径残留阻断记录）；`TD-11` 任务级聚合补偿补丁已回退；**2026-11-08 `TD-13 S4` 重构为「草案实体化」（`ruleVersion=s5-0`）**——门禁落点由「`saveDrafts` 之前整批报废」改为「落库之后按草案诊断 + 确认放行」：判据下沉 `helloai-common/util/DraftGateChecker`（入参中性快照、`draftRef` 改真实 `sub_task.id`），结论按草案写 `sub_task.context.gateFindings`（唯一写点 `SubTaskGateFindingsStore`，写与清同源；唯一编排 `PlanGateRunner`），处置按操作性质分三类（生成类只诊断 / 改造类回滚 / 确认拒绝），门 2 并入 `G2_KIND_FORM_CONFLICT`（显式声明 block、未声明 warn），新增契约位归一 `ContractSlotNormalizer`；草案审阅弹窗加「自检」列 + 行内明细 + 顶部汇总。`helloai-core` 3233 例 0 失败、架构守卫与前端 `type-check` 全绿。**同日续建 `TD-14`（拆解生命周期）并完成**——`PLANNING` 一义两用拆出 `AWAITING_PLAN_CONFIRM`（`PLANNING` 纯化为拆解中）；判活由 `update_time` 心跳改拆解租约 `task.decompose_lease_until`（删 `NOT EXISTS (草案)` 补丁，与 `OrderExpireTask` 同构：超时任务扫痕迹不扫主体状态）；落库段用 `TransactionTemplate` 包住（模型调用留事务外）+ `(task_id, decompose_attempt_id, draft_seq)` 唯一索引幂等 + `attemptId` 在入口生成；CAS 失败按「已回收→推回待确认 / 已取消→回滚 / 已是待确认→幂等」三分流；迁移 `V146` 含存量订正；**同日补决策 9**——状态推进/回退的 CAS 一律带 `attemptId`（`TaskService.casStatusForAttempt`，四处调用点），堵住唯一索引管不到的「**跨 attempt 迟到写入**」：A 跑超租约被巡检回收 → 用户重拆得到 B（状态又回 `PLANNING`）→ A 苏醒落库，只比状态的 CAS 会命中 B 的 `PLANNING` 使两批草案混存。`helloai-common` + `helloai-core` 3251 例 0 失败；**同日真机验证 10 项全通过**（`V146` 已 apply、存量 9 条 `PLANNING`→待确认且 0 残留；其中首轮抓到 P0 = `SubTaskMapper.xml` 自定义 `<insert>` 显式列清单漏 `decompose_attempt_id`/`draft_seq` ⇒ 值静默丢弃 ⇒ 唯一索引条件 `IS NOT NULL` 恒假而失效 ⇒「跨节点不重复落库」运行期不成立；已补列并复验：5 条草案 `attemptId` 全等 + `draftSeq` 连续 1~5；预插冲突行触发 `DuplicateKeyException` 被当「已消费」，`advanced=true` 未回退 `PENDING`）。**同日域 D 续：`TD-11` 三段断点收口 + 新增 `TD-15`**——① `TD-13 S2` **完成**：`uncertainties[]` 新增 `blockedBy: int[]`（本批 1-based 草案序号），补齐「一个缺口卡住哪几条草案」这个此前**没有字段能表达**的能力（`TD-11` 断点① 的根因：`ref` 被钉成本子任务局部，模型只能就近贴在「看起来最相关」的草案上，2026-10-25 事故里真正被卡的草案 2/3/4 一条标注都没拿到）；提示词按「规则段 / JSON 示例 / 字段说明段」三处同改，归一化（越界 / 自引用 / 重复）挂在唯一一次 `normalizeUncertainties` 里、按草案聚合一条 WARN；**纯 JSONB 键、零迁移**，不需要动 `SubTaskMapper.xml` 列清单（加键 ≠ 加列）。② `TD-13 S3` **部分完成**：草案审阅弹窗成为 `acceptancePreconditions` 的**首处读取方**（新增「验收前置未满足」区块 + 不确定性明细展示 `ref` / 「卡住 #2/#3」），关闭断点③ 第一半；剩余 `review_record` 侧展示、任务详情页「被拦规则明细」、子任务页「自检告警」空态、任务列表「未通过自检」角标。③ `TD-14` 遗留 2 收口：任务列表对 `AWAITING_PLAN_CONFIRM` 显示「已等待 N 分钟/小时/天」（**只做可见性，自动作废草案明确不做**）。④ **新增 `TD-15` 并完成**：`decompose` 入口的 `DELETE FROM sub_task WHERE task_id=?` **无状态过滤**（安全性全靠调用点那句 count），已收窄为 `status='CANCELLED' AND NOT EXISTS(agent_execution_record)`，原无过滤方法只保留给任务级联删除；真机只读核验 **25 条 CANCELLED 里旧 SQL 会错删 1 条有执行记录的行**（`sub_task.id=2119277757896400897`），当前因所属任务已 `CANCELLED` 而不可触发，属潜伏缺陷。`helloai-common` + `helloai-core` **3256 例 0 失败**（+5 例），架构守卫与前端 `type-check` 全绿；`blockedBy` 端到端真机未跑（后端为本轮改动前启动的 IntelliJ debug 进程，无法自行重启），**不报告为已生效**。**同日 17:30 复验补记**：`blockedBy` 端到端**已跑通**（真实模型输出草案 1 `blockedBy=[2,4,5]`、草案 4 `[5]`，均不含自身），并当场抓到**一处自己引入的 P0**——门禁 findings 同事务写入误用整段覆写的 `updateContext`，把 `acceptancePreconditions` 等键擦掉（新增 `mergeContextInTx` + 调用点守门测试修复，存量 18 条草案 / 4 个任务、仅 1 条需修）；同时 `draftSeq` 纳入 `SubTaskView` 让「卡住 #N」能对上界面行号。全量 **3260 例**。剩余 `S0`/`S5`/`S6` + `S3` 展示面（详见 `plan/…` §3 `TD-11` / `TD-13` / `TD-14` / `TD-15`、`LOG-20261107`、`LOG-20261108` 域 C/D） |
| G-026 | LLM 额度与用量监控 | 4 家外部供应商（deepseek / moonshot / minimax / dashscope）余额分散在各自控制台，平台侧仅能通过「调用失败」感知额度耗尽；`credential_vault` PLATFORM 级 10 条凭据（deepseek 3 / moonshot 3 / minimax 2 / dashscope 2）；博查 key 另存 `sys_config[web-search.bocha.api-key]`；`agent_completed` 的 `usage` 字段 **0 条落库**、token 仅单值无 model 维度 | 4 家（deepseek / moonshot / minimax / 博查）余额在平台内一处可查，每项带抓取时间与失败标记；通义无公开接口故不显示；只读悬浮控件 | PR2 | 已完成 | — |
| G-027 | 项目级交付编排 | 平台只有「一次任务 → 静态 DAG → 派发 → LLM 验收」内循环：无项目级计划实体（planner 域 entity 仅 `ChatAttachment`/`ChatSkillRun`/`RequirementConversation`/`RequirementMessage` 四个会话相关表）；平台进程碰不到宿主仓库（唯一 `ProcessBuilder` 为 `PgDumpRunner` 备份专用）；沙箱 `network-policy: deny` 且无仓库持有模型；代码类验收无可执行判据（LLM 判 `pass/score`，fail-open） | 计划双表示（Document + Spec）+ 滚动式拆解（Phase 基于当时 `baseCommitSha` 细拆）；可执行验收机（命令退出码为权威，LLM 只判意图）；`git worktree` 隔离 + Git 权限分级；`touchedPaths` 交集判并行安全；Context Bundle 按任务组装约束；成本预算内建 | PR2 | 设计中 | 设计成文（`design/HelloAI 交付编排能力设计.md`，`Status=Planned`）；出 `plan/` 批次；`V1`~`V5` 待验证 |

| G-028 | 个人助理工作台 | 助理对话与需求对话按 `kind` 分流（独立 `assistant` pipeline，读写隔离）；工作台 = 左对话 + 右栏三段（任务概况 / 最近任务 / 最近产物），产物三源归一（执行产物 + 会话附件 + 技能产物）；用户归属模型（`owner`）覆盖会话 / 任务 / 长期记忆；会话级模型钉住（`requirement_conversation.model_type` + `AgentModelPin` 覆盖入参，选「无角色纯 LLM 模型」与 Planner 选择彻底分离）；上下文窗口 token 分项占用 + 手动/自动压缩；`PA-13`~`PA-17` 补模型偏好记忆 / 先思考后检索 / 工作概览注入 / 重新生成 / 消息操作条 | 个人助理成为平台一等入口：能对话、能看全局、能取产物，且不新增第二套执行链 | PR2 | 部分完成 | `PA-8`~`PA-10` 登记不启动（自动化定时任务 / 插件模板市场 / IM 与移动端）；详见 `plan/HelloAI 个人助理工作台实施计划（2026-11-02）.md` |
| G-029 | 内部 LLM 跨文档核验（沙箱证据与上游引用） | 跨子任务核验类验收要求「引用上游 `storageUrl` + 文档名/章节/行号」，但沙箱按子任务隔离、上游产物只以**截断文本**注入；执行器在沙箱内 `ls /`、`find *.md`、探测 `127.0.0.1:8080/3000/9000` 均找不到附件，返工后改为在沙箱**重建**上游文档；自审闸门按命令文本精确匹配 SR-5 台账，出现 `mismatch`（自报命令无匹配）与 `missing`（自报 0 / 台账 23）后模型反复执行同一条命令（`cat verify_out.txt` 同 digest `5c94c878…` 出现两次），最终报告缺 `VERIFICATION` 围栏 → `agent_runtime_missing_verification` → `BLOCKED`。真机样本：`task=2120174471616131074` / `subTask=2120174821500776454` | 跨文档核验可满足：①上游附件以 `storageUrl` 列表 + 只读工具注入（不依赖沙箱文件系统）；②自审台账按 digest/命令前缀归一匹配，并允许「本次 attempt 已执行」复用；③文档类交付输出契约拦截工具调用残留/思维链；④长核验子任务按模型能力路由（MiniMax-M3 易写阻塞叙事） | PR2 | 待开始 | 见 `LOG-20261110`；本轮迁移验收按**环境/内容缺陷**收口，不计为迁移失败；**2026-11-15 关联进展**：`RV-14`（stage-in）已提供「附件注入沙箱」的通道形态（`SandboxExecutionService.writeArtifact`），与期望①「不依赖沙箱文件系统找附件」同向——但 stage-in 归属硬校验现限**本子任务**有效附件，G-029 要的**上游**附件注入需先做任务级附件列举，本行维持待开始（见 `LOG-20261115` #### B） |
| G-030 | 自审闸门失败未 fail-close | `FinalReportFenceHook#tryFinalizeRound` 在 `self_review_failed`（重跑耗尽、`gatePassed=false`）后仍返回 `AgentLoopResult.stop(...)`，交付物照常 `execute_submit`；真机 `subTask=2120174821500776454` 第一次执行：`self_review_evidence_checked(gatePassed=false)` → `self_review_failed(retryCount=1)` → `llm_call_end success=true` → `execute_submit`，直到 reviewer 驳回。第二次被拦是“缺 VERIFICATION 围栏”的另一条 fail-close，不是自审闸门 | 自审闸门失败 = 不可提交：返回 FAILED 态，走既有 BLOCKED/退避重派链；错误结果不得进入评审或落附件 | PR1 | 待开始 | 真机 `task=2120174471616131074`；当前设计注释明写“只留痕、不改出口”，需用户裁定改口径 |
| G-031 | Task-Team 成员表运行期无写入 | 原状：`TaskAgentMemberService.register` 无生产调用方，唯一写入是启动时 `TaskAgentMemberRebuildRunner`；drop 重建后新任务分配不留成员行（`task=2120174471616131074`），详情页“参与成员”为空。根因修正：并非“启动重建漏跑”，而是运行期从未挂过入队钩子。修法（钩子为主路径）：在状态写入唯一入口 `SubTaskStateMachineServiceImpl` 同事务登记——`changeStatus(...agentId)`→`ASSIGNED`（拆解分配/调度派发/死信人工指派）、`claimAtomic`→`CLAIMED`（外部 MCP 认领）、`reassign`/`rework`/`reworkFresh` 换人→`REASSIGNED`；成员行写失败随归属事务整体回滚。兜底保留：启动重建（存量）+ 鉴权 derive-on-miss + `listMembers` 读时差集补齐（差集为空零写入）。证据：单测 7 例；`McpTaskClaimConcurrencyIT` 增断言“认领成功即写 `CLAIMED` 成员行”；真机认领路径（内部 `claimById` 与外部 MCP `claimSubTask` 均即时落 `join_source=ASSIGNED`/`CLAIMED`，members API 立即可见，按 `(task_id, agent_id)` 去重，无重启） | 参与成员展示与运行期一致：归属一变更即实时入队，且保留重建/读时兜底 | PR1 | 已完成 | — |
| G-032 | 沙箱生命周期与台账证据 | 原状：沙箱按子任务、turn 结束即 `release`，rework/下游看不到上一轮与其它子任务文件（真机 `/workspace` 每轮为空、5 轮重建上游文档）；自审证据靠模型手抄命令与台账做文本前缀匹配，省略式命令必然 mismatch（`ledgerCount` 饱和 50 vs 72 条命令）。**`SL-1`+`SL-2` 推进（`LOG-20261112`）**：槽键升格 `(Kind, scopeId, specFingerprint)`、`SandboxScopes` 任务级解析（LRU 1024 + `invalidateTask`）、`releaseByTask`；销毁时机由 turn 末改为任务终态——`TaskTerminalStateEvent` 经 `SandboxTaskTerminalListener`（`@Async` + `AFTER_COMMIT` + `fallbackExecution`）触发回收，逐槽 `tryLock` 不误杀在跑工作区，三处终态出口（自动收尾 / 改状态 / 级联删除）全覆盖；`scope`∈{task,subtask} 回滚开关（回滚模式保留 turn 末释放）。**`SL-7` 推进**：容器寿命拆两段——`keep-alive-seconds`（28800s）是 `renew` 的**绝对**过期目标（探针实测可延长/可缩短），`idle-ttl-seconds`（900s）只作回收判活，半衰期才补 `renew`；`124`/`-1` 不再销毁容器（单列 `sandbox_command_timed_out`），仅传输层异常重建；命令超时缺省 30s→300s；新增 `SandboxIdleReaper` + `helloai-job` 的 `SandboxIdleReaperTask`（任务级槽按 `keep-alive`、`scope:{id}` 槽按 `idle-ttl` 回收，逐槽 `tryLock` 跳过在跑，ShedLock 豁免）。**`SL-3` 推进**：容器创建后预建 `/workspace` 且所有命令注入 `workingDirectory=/workspace`（`./x.py` 与 `/workspace/x.py` 同址）；草稿回读按 `mtime` 取最近修改的一份（原按路径序命中上游旧稿），无 `.md` 时按体积兜底；回读选槽由「只比 `(kind, scopeId)`」改为「同规格优先 → 唯一候选回退 → 多规格拒绝并 warn」（技能以 `node` 预设执行、回读入口无规格参数，严格等值会让技能产物读不到）；大产物内联由按字节改为按字符（8000 字符，不劈代理对）；六处 Prompt/工具说明统一为「同一任务内复用、任务终态销毁、命令串行、重启丢失、沙箱文件不是交付物」。**`SL-4` 推进**：台账条目加平台生成的 `callId`（`c<seq>`，进程内单调唯一）、`origin`（`executor` / `reviewer` / `platform`，调用侧显式声明）、`round`（执行轮次 `turn = 1 + reworkCount + attemptTotal`，执行链起点一次绑定，无上下文记 0）；`ExecuteResponse` 增第 4 分量 `callId` 并由 `recordCall` 回填（三参构造保留，`withCallId` 幂等）；每子任务上限 50→200，`callId`/`origin`/`round` 同步进 timeline payload——证据从此可按条目坐标引用（`SL-5` 前置）。**`SL-5` 推进**：`EVIDENCE` 围栏由手抄命令改为引用式 `{"kind":"sandbox_exec","callId":"c17"}`（旧四字段格式保留一版兼容）；对账按 `callId` 精确命中台账条目，未命中 / 自报字段（`exitCode`、`truncated`）与台账矛盾 / 同一条目被两份证据重复占用均判 mismatch；`origin` 非作者（`reviewer`/`platform`）记 `evidence_origin_non_author` warn、跨轮命中记 `evidence_round_crossed` warn 且优先同轮；闸门改为对「被证实的条目」判成功（引用一条失败记录时不得由台账其它成功条目背书，无引用时回退全账本旧口径）；新增工具 `internal_list_sandbox_calls`（limit 默认 20 / 上限 50）让模型自查 `callId`，收尾轮与执行工具同时放行、仅执行工具计预算（N=3）。**`SL-8` 推进**：新增 `WorkspaceRef`（`kind` / `ownerId` / `contextId` / `specFingerprint` / `isolation` / `access` / `lifetime`；构造期校验「`ONESHOT` ⇔ `EPHEMERAL_VERIFY` 必须同时出现」「每种作用域唯一合法寿命」）与 `SandboxRuntime` 五方法（`exec` / `readFile` / `stat` / `writeFile` / `release`）；`SandboxExecutionService` 退为薄适配层 `SandboxRuntimeAdapter`（**唯一允许查库**的地方，解析顺序 = null ⇒ 一次性 / 复用关闭 ⇒ 一次性 / 绑定 `taskId` ⇒ 任务级且零查库 / `scope=subtask` ⇒ 子任务级且零查库 / 其余 ⇒ `SandboxScopes`），`OpenSandboxExecutionService` 降为纯运行时实现（构造器只剩 `properties` / `loader` / `ledger` / `timeline`，编译期拿不到 `SubTaskQueryPort`）；`ToolInvocationContext.Ids` 扩为 `(agentId, taskId, subTaskId, round)` 并在 `RuntimeTurnExecutor` / `BusinessSurfaceLoopRunner` 绑定 `taskId`，热路径不再每条命令查一次库；`readArtifact(Long)` 改 `readFile(WorkspaceRef)`、新增 `stat`（只读探测不建沙箱）与 `writeFile`（与命令共用槽锁）；`check-arch-freeze.sh` 新增组 4 两条硬拦截（`runtime/sandbox/impl` 不得 import `SubTaskQueryPort` / `SandboxScopes`，目标恒为 0，已锁基线）。**`SL-9` 推进**：原 1192 行 `OpenSandboxExecutionService` 按职责拆为 6 片——`OpenSandboxRuntime`（执行通道：守卫顺序 / 超时钳制 / 输出截断 / 一次性与复用分支；实现 `SandboxRuntime` 六方法，新增 `listFiles` 走 SDK `Filesystem.listDirectory`，不再用 `find -printf` 命令污染台账）、`SandboxWorkspaceRegistry`（句柄→槽键 / 槽池 / TTL / `renew` / `release` / `releaseByTask` / `SandboxIdleReaper`）、`SandboxFactory`（建销 + 规格映射，拆掉 runtime↔registry 构造环）、`SandboxObservation`（台账 + timeline 协作对象，只记真正下发的命令并回填 `callId`；不做装饰器，避免把未下发命令的守卫失败也记进台账）、`SkillPackageInstaller`（包注入流水线）、`DeliverableReader`（`mqconsumer` 包：最新 `.md` 优先 + 体积兜底，消费者只决定「要不要换正文」）；`SandboxRuntimeAdapter` 成为唯一装配点与唯一查库点，过渡接口 `SandboxWorkspaceOps` 删除。单测按切片迁移（31 例 → 5 类，新增 `DeliverableReaderTest` 5 例），定向 147 例通过；`verify-opensandbox-contract.sh` 断言 ⑤ 落点同步到 `OpenSandboxRuntime` / `SandboxFactory` / `SandboxWorkspaceRegistry`。**`SL-10` 推进**：`/workspace` volume 化——`SandboxSpec`/`OpenSandboxProperties` 增 `volume-enabled`（默认 `false`，回落容器可写层零变化）+ `volume`（host-path / pvc / ossfs / mount-path 默认 `/workspace`），`SandboxFactory.create` 增 `readOnly` 参数并按 Host/PVC/OSSFS 挂载、未启用卷时回落原 `prepareWorkspace` 预建；`SlotKey` 增 `Access`（`READ_WRITE`/`READ_ONLY`，对计划「纳入 `Isolation`」的精确化——executor 与 reviewer 隔离形态同为 `TASK_WORKSPACE`、区别只在访问意图），`release` 用 `expectedAccess=null` 同时释放两种 access 槽；reviewer 路由——`SandboxRuntimeAdapter.toRef` 绑定 `taskId` 分支按 `ToolInvocationContext.currentReviewer() ? taskReadOnly : task` 三分、查库 `TASK` 分支同样跟随 reviewer（`SandboxRuntimeAdapter` 增至 14 测试），`BusinessSurfaceLoopRunner.isReviewerScene`（`subtask_review`/`task_final_report_review`）五参 `bind` 声明 reviewer、`InternalLlmExecutorTools.internalRunInSandbox` 按 `currentReviewer()` 选 `SandboxCallOrigin.REVIEWER`/`EXECUTOR`；一次性校验建模 `EPHEMERAL_VERIFY`——`WorkspaceRef.oneShot()`（`ONESHOT`/`READ_ONLY`/`EPHEMERAL_VERIFY`/`CALL`）+ `executeOneShot` 创建→执行→销毁、不进槽池、卷挂载 readonly 按 `ref.readOnly()` 透传；`application.yml` 加 `volume-enabled`/`volume` 配置。定向测试集 95 例通过（`SandboxFactory` 4 + `SandboxWorkspaceRegistry` 10 + `OpenSandboxRuntime` 8 + `ToolInvocationContext` + `SandboxRuntimeAdapter` 14 + 其余） **`SL-6` 收口**：真机六项均通过——返工轮读上一轮产物、下游子任务读上游 `/workspace` 文件、空闲超 `idle-ttl` 后同沙箱复用、任务终态两槽 `killed` 且沙箱清空、reviewer 台账 `origin=reviewer` 与执行侧 `executor` 分离、reviewer 容器 `/workspace` 只读（`touch` 报 `Read-only file system`、`/tmp` 可写、交付物 sha256 未变）；`scope=subtask` 回滚路径验证（沙箱按子任务建、turn 结束释放）。开启卷时暴露并修复两处缺陷：SDK `Volume` 缺 `name` 致建沙箱失败；sandbox server `storage.allowed_host_paths` 为空拒绝宿主路径。**dev 复验**：跨子任务同一执行沙箱（空闲七分钟后再命中）、执行与审核双槽终态同时销毁、报告审查走一次性校验即建即销；审核侧 `/workspace` 挂载只读。 实施依据见 `plan/` 沙箱生命周期与台账证据改造计划，决策见 `ADR-005`。 | 任务级沙箱（`taskId→sandbox`）+ 任务终态销毁；证据改为平台生成的 `callId` 引用台账条目，模型不再手抄命令 | PR0 | 已完成 | — |
| G-033 | 执行环境维度：拆解侧闭环、执行侧与派发校验未闭环 | 拆解侧：`ExecutionEnv` 四态判别 + 防御解析（`helloai-common`）+ `planner-decompose.md` 第 12 条 env-first + 主拆解/单条重写双渲染点 + 终稿人工声明搬入；二次拆解：重构会话种子⑤段 + 派生任务 `inheritExecutionEnv`（只搬该键，`putIfAbsent`）。**执行侧未做**：沙箱工作区仅 `/workspace/draft.md`，仓库不进沙箱；**派发校验未做**：无 `ExecutionPrereqChecker`，技能 AND 硬匹配不派发 ⇒ 无原因 `PENDING`（比 `BLOCKED` 更难排查）。架构基准：执行器是**技能型执行**（deepseek-harness 思路——宿主循环 + 持久 shell + 文件工具直读，**无「repo 拷贝进工作区」环节**，对照见 `` `research/沙箱内 LLM 执行流程三仓对照调研` ``）；SWE-agent `repo.py` 的 `LocalRepoConfig.copy()`（upload + `chown`）属 SWE-bench「模型改具体仓库」场景，与技能型执行是否兼容**待调查**（用户裁定：先调查，不预设形态） | 拆解/继承侧保持闭环；执行侧按调查结论定案（仓库注入成立与否、或任务级共享工作区即足）；派发校验 = 派发前比 `accessType` + 执行环境，不匹配即 `BLOCKED` 且带原因 | PR2 | 待开始 | ①「仓库注入」适用性调查（技能型语义下成立与否 + 形态，结论回填本行现状列）；②`ExecutionPrereqChecker` 派发校验；③`CreateTaskRequest` 写 `context` 的通道（现唯一可行写入路径是直改库） |
| G-034 | 审核侧证据可见性与驳回依据可达性 | 代码复勘：①附件注入只有自然语言 `[TRUNCATED] file=… shown=… total=… reason=…`（`ReviewEvidenceAssembler:305`/`:318`），无附件 id、无注入行区间；附件清单只给名称/类型/大小/可否直读（`buildAttachmentList:222`）⇒ Reviewer 既无法引用也无法定向补读；②注入按 `create_time DESC` 先到先得（`AttachmentServiceImpl:289`），主件与分片族结构上必然溢出，`ATT-3` 的「均分保底 + 余量回收」配额算法（`ChatAttachmentPromptRenderer:559`）未用于核验侧；③`ReviewCoverageCritic:79` 只核 `pass=true` 的覆盖缺口，驳回侧无「缺陷依据是否可见」闸，`pass=false` 的每条缺陷无人核依据是否在视野内；④`INCONCLUSIVE` 路由与「不返工」已存在（`SubTaskReviewServiceImpl:488`），但缺「只引用不可见证据的缺陷 ⇒ 降级 `evidence_unavailable`」的判据；⑤`coverage.method` 白名单仅 `attachment／sandbox`（`ReviewCoverageParser:45-46`/`:75`），无 `mechanical`；⑥`internal_read_attachment` 返回全文但被工具结果剪枝改为头 4,096 + 尾 1,024 码点（阈值 8,192，`ToolResultPruner` 类注释口径），155k 字符 CSV 实际可见约 5k，反向劣于注入的 64k | 证据可见性结构化（`EvidenceLedger`：id + `injected` 区间 + `absent` 原因，随 `review_record.snapshot` 冻结，需同步改 `freezeEvidence`/`frozenEvidenceView`）；预算按证据族配额 + 交付物优先 + 区间注入；表格类走确定性机械预检（行数 / 聚合 / 报告声明比对，输出 `[MECHANICAL_CHECK]`）；驳回侧 `evidence_unavailable` 降级 `INCONCLUSIVE` 且不计返工；有界分页/摘要工具；注入规则对执行方同源可见（`verify-executor-doc-parity.sh` 守卫） | PR0 | 验证中 | 代码 + 单测已落地（2026-11-14）；2026-11-15 关闭真机暴露的 3 项遗留（reviewer 误用沙箱找附件 ⇒ prompt 两处指路 / 不可解析即时重试 + 锁 TTL 补「调用次数」因子 / 孤儿候选注释按实现订正），见 `LOG-20261115`；**同日续 `RV-14` 并落地**（附件惰性注入沙箱 stage-in：`SandboxExecutionService.writeArtifact` + `internal_stage_attachment_to_sandbox`，归属硬校验限本子任务有效附件、sha256 与 `internal_attachment_digest` 同源对账、沙箱未启用时随同一开关摘除；69 例定向 0 失败 + 文档守卫 30 项 PASS，见 `LOG-20261115` #### B）；**剩余**：上游附件任务级列举（stage-in 归属范围现限本子任务）、真实 35 附件批真机回放（`balance` 口径校验与机械比对）未跑，待复判任务回放补证；**2026-11-15 追加真机闭环**：stage-in 场景单跑通过（子任务 `2121499900000000022`：reviewer 先调 `internal_stage_attachment_to_sandbox` 注入脚本+数据，再 `mkdir /workspace/test_rv14 && cp /tmp/staged/…` 摆同目录后 `python3 check_data.py` exitCode=0 输出 `claim=8`，与机械预检 `data=8` 对账一致 ⇒ PASS，见 `LOG-20261115 #### C`）；**同日续 `RV-15` 立档**（审核证据自动物化：有界证据集平台注入 reviewer 沙箱、stage-in 退为补拉通道；F1~F4 修正口径 + 护栏 `maxFiles=40` 见核验双域实施计划 §3 `RV-15`；真机「无指路」验证通过前本行维持验证中）；**同日 `RV-15` 代码层落地**（`StagedPathUtil` 抽公共 / `ReviewEvidenceMaterializer` 三闸护栏物化 / `SandboxExecutionService.writeReviewerArtifact` 显式身份写入绕 ThreadLocal 分面 / `ReviewExecutionEngine` 三参物化 + 冻结复判 F3 跳过 / S-8 与 reviewer guide 改「证据已自动物化」口径；单测定向 5 类 99 例 + 波及类 176 例全绿；35 附件批真实字节：本地/dev 库均无现成数据——属本行剩余「真实 35 批回放」待补证项，真机批回放时实测是否落 `maxTotalBytes=50MB` 内，超则调阈值，见 `LOG-20261115 #### E`）；**2026-11-15 真机「无指路」闭环**（子任务 `2121499900000000042`：物化器 `materialized=2 total=1167` 自动写盘，reviewer **首轮命令即** `mkdir -p /workspace/rv14 && cp /tmp/staged/2121812913725468674-data.csv … && cp /tmp/staged/2121812913079545857-check_data.py …`，**零 find 白跑、零指路**；随后实测 `python3 check_data.py`（claim=8）+ 独立重算 8 行 + 机械预检 data=8 三源对账一致，且 diff staged 原件与执行副本 identical 证明物化文件即 MinIO 原件 ⇒ APPROVED score=4 round=1，判定**自动物化方向成立**，stage-in 退为补拉通道；见 `LOG-20261115 #### F`） |
| G-035 | 站内消息与置顶公告 | 人侧无消息载体（`user_notification` / `announcement` 全仓 0 命中）;`DEAD_LETTER` 零通知;拆解/报告异步长任务无提醒（`PlanReviewDialog.vue:199-200` 已写明「产出后站内提醒」却是空头承诺）;公告无载体;无平台级启用开关。主通道钩子点完好（`TaskTimelineServiceImpl.recordEvent` 写点后，`recordEventInNewTx` 委托之、一处覆盖两入口）;SSE 骨架 `WorkbenchEventHub` 可复用;开关先例 `SysConfigServiceImpl.isRegisterEnabled()`（默认 OFF）。**收口形态**：单一出口 `NotificationService.publish`（开关闸 → 幂等键 → 落库 → `AFTER_COMMIT` 推送）;钩子挂在 `TaskTimelineServiceImpl.recordEvent` 写点后、`recordEventInNewTx` 委托覆盖两入口，无主任务按设计跳过（建任务一侧同步补齐 `owner_id`，HTTP 建任务与外部通道同源）;白名单六类 `PLAN_DRAFT_READY` / `FINAL_REPORT_READY` / `SUBTASK_SUBMITTED` / `SUBTASK_DEAD_LETTER` / `SUBTASK_NO_CANDIDATE` / `FINAL_REPORT_REVIEW_STUCK`;前端铃铛停靠与悬浮二选一（拖出成悬浮、拖回成停靠、轻点开抽屉）+ 抽屉（类型筛选 / 全部已读 / 归档）+ 深链（`/tasks?taskId=` 与 `/sub-tasks/{id}`）;置顶公告三表 + 版本已读 + revision 快照 + 管理页;两开关 `notification.enabled` / `announcement.enabled` 默认 OFF（停发 + 隐藏）;SSE 通道 `X-Accel-Buffering: no` + 心跳 25s + 客户端断开按连接收敛（不记系统异常）;幂等靠唯一索引 `COALESCE(receiver_id,0)`。真机全链路（拆解任务 → 时间线 → 消息行 → 角标 → 抽屉 → 深链 → 已读回写;SSE 实时推送无刷新;开关 OFF 停发隐藏;公告发布 / 已读 / 归档全流程）通过 | 单一出口 `NotificationService.publish` + 事件码→消息白名单映射 + 首批 A1/A3/A5/B1/B2 + 铃铛/抽屉/工作台入口 + 置顶公告（版本已读）+ 两平台开关（`notification.enabled` / `announcement.enabled`，默认 OFF、停发 + 隐藏）+ SSE `AFTER_COMMIT` 信令 + 多副本幂等（DB 唯一索引 `COALESCE(receiver_id,0)`） | PR2 | 已完成 | — |
| G-036 | A2A 兼容边界 | 对外执行通道只有自建 MCP 13 工具，与 A2A（v1.0.1）三层不对齐：**参数级**无 `contextId` 会话分组维（`derived_task_id` 是层级关系，非会话分组）；**操作级**无 cancel（13 工具 grep 零命中，executor 卡死后平台只能改库或等租约过期）；**结构级** `submitResult.output` 为纯 `String`（`McpToolService` 的 `SubmitResultResult`），`uploadArtifact` 只收平台桶内 `minio://`。状态机 12 态 vs A2A 8 态，`REWORK`/`AWAITING_DERIVED`/`RESTRUCTURING`/`PAUSED`/`REVIEW`/`DEAD_LETTER` 无落点，而 A2A 终态不可重开。外部 executor 仅 2 个（`trae-excutor` / `TeleAgent-executor`），接入门槛 = 实现 13 个工具 + 读 SKILL.md + 维持心跳。`CancelTask`/`a2a-sdk`/`a2a-java` 全仓 0 命中；`agent-card` 唯一命中是前端 UI 组件 `helloai-ui/src/views/agent/components/AgentCard.vue`（同名不同义，勿当已有实现） | **双平面**：A2A 只做口岸（三次翻译：身份 / 任务 / 产物），私有 MCP 仍是管控面；**12 态内核一个不改**。对外投影三原则（无对应态挤 `WORKING` + `status.message`；终态只在 `DONE`/`CANCELLED`/`DEAD_LETTER` 发出；`BLOCKED` 不投影 `INPUT_REQUIRED`）。**A2A task 身份 ≠ subTask 身份**（一次交付尝试 = 一个 A2A task）⇒ 终态不可重开与本仓可重跑不再矛盾。三档：**A 档**（现在做，1~2 天）补四洞 `cancelSubTask`/`submitResult.parts`/`contextId` 预留/`sessionId` 解耦；**B 档**（主线闭环后）出站 client（`AgentAccessType.A2A_AGENT` + push 分支 + 远端 taskId 对账）；**C 档**（三判据任一命中且 `G-033` 有解）入站 server，先只读面后完整面 | PR2 | 部分完成 | 边界唯一事实源 `doc/design/adr/ADR-009-a2a-compatibility-boundary.md`；待验问题 `AQ-1`~`AQ-13` 见 `research/HelloAI A2A 兼容与私有 MCP 改造调研清单（2026-11-16）.md`（结论以 ADR-009 为准，清单不含结论）。**A 档的 `cancelSubTask` 与 A2A 无关**，是独立缺陷，收益先到；A 档状态机改动面为零（`SubTaskStateMachine` 除 `DONE`/`CANCELLED` 外 10 态均有 → `CANCELLED` 出边）。C 档被 `G-033`（`CreateTaskRequest` 写 `context` 只能直改库）硬阻塞。**不做**：压状态机 / 「A2A 模式」开关 / 复用 `WorkspaceRef.contextId` / 另建任务表 / 未解 `AQ-3` 就接自动派发（`AssignedSubTaskTimeoutTask` 10 分钟回收会误杀跨小时任务）。**2026-11-17 A 档第一批落地（A-1 + A-4）**：`cancelSubTask` 三通道补齐（外部工具面 13→14；`SubTaskCommandPort.cancel` 原子命令 → 协议适配层归属 + 三终态 DONE/CANCELLED/DEAD_LETTER 拦截 `BizException(400)` → 状态机既有 cancel，零状态机改动；存量 Agent 由 `isToolEnabled` 自动补授权行）；SSE 通道 14 工具鉴权参数解耦——新推荐名 `authSessionId`，优先级 `authSessionId > sessionId > _sessionId`，旧两名保留一个兼容周期（REST/JSON-RPC 工牌通道无此参数不受影响）。定向单测 144 例全绿（core 114 + api 30）；真机复验同日通过（SSE 真实面 17 工具含 cancelSubTask；三通道 cancel / 越权 400 / 终态 400 / 新旧参数名 23 项断言 0 FAIL，对外面守卫 exit 0）；实施计划与逐项证据见 `plan/HelloAI ADR-009 A档第一批实施计划（2026-11-17）.md`。文档回填同日收口（executor-SKILL.md 镜像 + executor-duty 手册源 3 章修订与重装 + 基线文档 bullet；ZIP 为运行时现打包已含新口径；parity 守卫 PASS=30）。**2026-11-17 AQ-7 / AQ-4 已拍板**：A-2 采方案 A（context JSONB `lastExecution.parts` + output 纯文本投影；file part 只存 `minio://` 引用且第一批显式 400 拒收、物化推第二批；单写点 `ExecutionResultHandler.handleReport` 一次性构造；投影复用 `OUTPUT_SUMMARY_LIMIT` 64k 截断常量）；A-3 对外保名 `contextId`、内部 `conversationGroupId` = taskId 最小投影（三处撞名登记，`WorkspaceRef` 不改名）。结论回填 ADR-009 §8.6/§8.7，调研清单 AQ-4 增补第三义，拍板记录见 LOG-20261117（同日三批已整合为单条）。**A 档状态**：四洞（A-1 `cancelSubTask` / A-2 `parts` / A-3 `contextId` / A-4 `sessionId` 解耦）**已全部落地**；B 档（出站 client）待 §D6 判据命中后启动、C 档被 `G-033` 阻塞。**2026-11-17 A-2 + A-3 落地**：`submitResult` 加可选 `parts`（三通道一致；协议适配层 `McpToolServiceImpl.validateParts` 拒收 file / 未知 type / 空正文 / 单片 >64k / >20 片，全部双参 400）；单写点 `ExecutionResultHandler.handleReport` 一次性构造 `lastExecution{parts, output}`，投影复用 `OUTPUT_SUMMARY_LIMIT`，`truncateHeadTail` 保头保尾（EXECUTION_RECORD 不丢）；`SubTaskDetail.conversationGroupId` 加 `@JsonProperty("contextId")` 使对外 wire 名名副其实。**实施中修的三处真缺陷**：① 工具入参 JSON Schema 把所有无 `@Nullable` 字段标成必填（`[{type:"text"}]` 被拒），已加注解、实测 `items.required=["type"]`；② `EXECUTION_RECORD` 回填 / 返工问题回填 / 空回复归因三处仍读原始 `output`，parts-only 提交会静默丢回填，已统一改读投影 `baseOutput`；③ 仅靠 Java 字段名时 wire 键是 `conversationGroupId`，「对外保名」不成立。验证：common+core 932 文件 + api 192 文件全量编译 0 错；定向单测 149 例全绿（core 119 + api 30）；parity 守卫 PASS=30。执行器侧三源文档（手册 v1.3 / onboarding 引导 / SKILL 镜像）已同步 `parts` 与 `contextId`。**2026-11-17 A 档验收集齐**：`ci-gate.sh --no-clean` **0 项失败**（4021 单测 / 55 B 级 IT / 8 静态守卫 / 前端 type-check+build）；`parts` 契约真机 **16 断言 0 FAIL**（JSON-RPC schema `items.required=["type"]` + `enum=["text","data"]`；text+data 分片落 `lastExecution.parts` 2 片且 `output` 投影含 json 围栏；file 分片 JSON-RPC `-32000` + REST **HTTP 400**；不带 `parts` 时零变化）；外部工具面 17 工具在 `.ps1` / `.sh` 两实现下均通过。**残留两项**：① `parts` 仍无原生读方（A-2 第二批必须补至少一处，否则成死数据）；② SSE 侧 `parts.items.properties.type` 无 enum（运行时 `validateParts` 统一 400，改成 Java 枚举会让 file 拒收文案退化，判为不修）；③ **存量 Agent 授权行滞后（2026-11-17 实测登记）**：`agent_mcp_server` 现有 8 个 Agent 全部 `has_cancelSubTask=false`（行数 2/3/15），因 `enableDefaultsForAgent` 只在注册时插入、`cancelSubTask` 是本次新加入 `DEFAULT_EXECUTOR_TOOLS` 的成员。当前靠 `isToolEnabled` 的「DEFAULT 成员无行即自动补行」懒授权兜底，首次调用即写行放行（无需迁移）；但 `getEnabledTools`（读侧授权清单）只并 `CRITICAL_TOOLS`（`pullTasks`/`submitResult`/`heartbeat`）**不并** `DEFAULT_EXECUTOR_TOOLS` ⇒ 老 Agent 的注入清单缺 `cancelSubTask` 而调用可通，形成读侧/校验侧双口径。**对外部 Agent 影响为零**（注入清单经 `ExecutionCommand.tools` 只喂进程内 LLM，`pullTasks` 返回体不含工具清单，外部 Agent 从自拉 `tools/list` 拿全量 17）；对内部 LLM 亦为零（走 `INTERNAL_LLM_TOOLS`，本不含 MCP 会话工具）。故判「惰性不一致」不急于修，触发条件 = 管理面要求工具开关行整齐，或在 `getEnabledTools` 之上新增真实消费方。**2026-11-18 残留①收口（MA-1 执行结果结构化契约）**：`parts` 补原生读取方——新只读端点 `GET /api/sub-tasks/getExecutionResultById/{id}` 返回 `{format, markdown 或 parts, sections, truncated}`（读模型「选择+打包」，`format` 二选一、`markdown` 直取已冻结 `output` 并剥离尾部 `## EXECUTION_RECORD`、`parts` 保原序、`sections` 恒 null 演进位）；`EXECUTION_RECORD` 起止标记收口为唯一常量 `ExecutionRecordProtocol`（生成侧 `AgentRuntimeContextAssembler`、回填侧 `ExecutionRecordParser`、迭代摘要提取 `TaskIterationServiceImpl` 三处同源；块定位与剥离统一走 `locateBlockStart`（取最后一次且在行首），剥离侧与回填侧同一口径；提取侧 `ExecutionOutputParser` 靠「JSON 之后的位置」判断、不含该字面，无需引用常量）；前端 `SubTaskDetail` 按 `format` 三路选渲染器（`markdown`→`MarkdownView`、`parts`→新 `PartListView`、缺失→老 `StructuredTextView` 逐像素不变），独立只读请求不阻塞详情主请求、失败静默回落；`sub_task.context` 定性「内部状态，客户端不得依赖」并登记三项待迁读点 `derivedTaskId`（派生血缘下钻，读已先经时间线兜底）/ `noCandidate` / `manualIntervention`（人工介入面板），移动端开工前必须完成迁移。验证：后端核心+API 定向单测（`ExecutionRecordProtocolTest` 9 + `SubTaskExecutionResultAssemblerTest` 9，含 JSONB 读出的 `List<Map>` 真实形态）全绿；前端 type-check + `vue-tsc -b` build 通过；`ci-gate.sh` 全量 0 项失败（含架构冻结守卫 `agent->task` 反向依赖回归 0）。**页面级三条修正**（实现后实测暴露）：无 `lastExecution` 时空正文不再渲染出空标题（`hasRenderableResult`）；有「执行结果」块时对话流最后一条执行产出默认收起（同页不再两份正文，仍可展开复制 / 导出）；对话流读侧同样剥机器块（`SubTaskController#toConversationItem`，写侧与库内原文不动，Web / 移动端 / 小程序共用同一份可读正文）。存量数据形态已核：`output` 16 条中 10 条含块、对话消息 19 条中 12 条含块，全部位于行首且单次出现 ⇒ 新剥离口径对存量零差异。详见 `plan/HelloAI MA-1 执行结果结构化契约实施计划（2026-11-18）.md` |

## 1.1 专项任务索引（任务视角，状态不复述）

> G 矩阵（上方）回答「还差什么能力」；本表回答「各专项的任务编号落在哪个文件」。**两者编号体系不同，不可互相替代。**
>
> 状态只在「唯一事实源」里维护。本表不复制进度——同一事实写两处必然漂移，而漂移后的第一受害者正是来查状态的人。

| 体系 | 编号 | 项数 | 唯一事实源（状态只见此处） |
|---|---|---|---|
| `REF-x.y` | REF-1 ~ REF-8（含 `REF-8.1`~`REF-8.9` 子项） | 8 组 | `plan/HelloAI 借鉴落地实施计划.md` §3 ~ §10 各节 `Status` |
| `M-x` | M-1 ~ M-9 | 9 | `plan/HelloAI 借鉴落地实施计划.md` §11（**全部完成**） |
| `S-x` | S-1 ~ S-10 | 10 | `archive/implemented/HelloAI OpenSandbox 沙箱集成计划.md` §3；`S-7`~`S-9` 见 `archive/implemented/HelloAI 沙箱执行链设计方案（2026-10-17）.md`；`S-10` 登记不启动 |
| `W-x` | W-1 ~ W-7 | 7 | `plan/HelloAI 联网搜索效果优化实施计划（2026-10-18）.md` 各节；`P3 Agentic 多轮搜索` 登记不启动 |
| `ATT-x` | ATT-1 ~ ATT-8 | 8 | `plan/HelloAI 需求对话附件实施计划（2026-10-22）.md` 头部状态行 + `ATT-x` 各节 |
| Paperclip | 阶段一 / 二 / 三（不引入编号前缀） | 3 阶段 | `plan/HelloAI Paperclip 借鉴落地计划（2026-10-21）.md` §1 ~ §3 |
| `TD-x` | TD-1 ~ TD-12 | 12 | `plan/HelloAI 任务拆解领域无关化实施计划（2026-10-24）.md` §3 任务表（各 `### TD-x` 标题下的 `**状态**` 行）；`TD-10` 登记不启动；`TD-11`/`TD-12` 为 2026-10-25 续号 |
| `SKX-x` | SKX-1 ~ SKX-15 | 15 | `plan/HelloAI 技能执行面接入实施计划（2026-10-26）.md` §3 任务表（各 `### SKX-x` 标题下的 `**状态**` 行） |
| `RC-x` | RC-1 ~ RC-6 | 已退役 | `archive/implemented/HelloAI 需求对话自由切换与并发互斥实施计划（2026-10-30）.md`（`Done`，号段不再新增） |
| `PA-x` | PA-1 ~ PA-17 | 17 | `plan/HelloAI 个人助理工作台实施计划（2026-11-02）.md` §3 任务表（各 `### PA-x` 标题下的 `**状态**` 行）；`PA-8`~`PA-10` 登记不启动；`PA-11`/`PA-12` 为 2026-11-03 续号（`PA-11` / `PA-12` 已完成）；`PA-4` 于 2026-11-05 按设计稿修订「选模型 / 活跃 Agent」口径（见 `LOG-20261105`），同日按用户裁定二次修订：失效模型改为**不下发**、面板按设计稿重做、默认模型订正为 `deepseek-v4-flash`（见 `LOG-20261105`） |
| `MSG-x` | MSG-1 ~ MSG-8 | 8 | `plan/HelloAI 站内消息与置顶公告实施计划（2026-11-15）.md` §3 任务表（各 `### MSG-x` 标题下的 `**状态**` 行）;`MSG-7` 扩展覆盖列后续;差距项 `G-035` |
| `RM1`~`RM13` | 已退役 | — | `archive/implemented/HelloAI_RM存量缺口清偿计划（2026-10-04）.md`（`Done`，号段不再新增） |
| `BASE-x.y` | 已退役 | — | `archive/implemented/HelloAI 基础架构调整实施计划.md`（`Done`，号段不再新增） |

**维护口径**：本表只登记「编号体系 → 落点」。**项数变化时**（如 `S-x` 扩到 11、`W-x` 新增 8）须同步本表与 §0 编号口径表两处；只改一处即视为失效。

# 2. P0 主线

## G-001 Event Stream

验收：

- Legacy 与 Runtime 产生同一 Event Model；
- Timeline / Audit 逐步统一从 Event 获取事实；
- 一个 Run 可以按 sequence 重建轨迹；
- Event 不成为第二业务状态源；
- 写入具备幂等和可对账能力。

## G-002 Dual Executor

> **已终结**：双轨不复存在（旧链入口与 Adapter 全部删除，无灰度开关），`RuntimeTurnExecutor` 为唯一 `AgentRuntime` 实现。

单轨后的结构约束（长期有效）见 `design/Agent_Runtime.md` §迁移模型。

**落地事实与验证证据**：`LOG-20260930-001`（硬切）/ `LOG-20260930-002`（外部 CLI_CLIENT E2E）。【过程记录原在 `archive/implemented/HelloAI 重构实施计划.md`，已于同批回填 `LOG-20260908-006`】

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

### Sandbox Provider（**条件触发，不排期**）

第一阶段只完成 Provider Contract，不把 Docker/K8s 当作已完成安全隔离。

> **2026-10-09 降级（`D-2026-10-09-6③`）**：本项**不排期**。判据见 `G-005` 行实测——**当前没有可隔离的执行对象**：
>
> ```text
> 外部 agent        → 跑在它自己的终端（平台定位 = 派单方 ≠ 执行方）
> 内部 LLM agent    → 工具面 = 13 个平台 API 工具 + web_search，无 shell / 文件写
> 平台自身          → 全库无脚本引擎 / 表达式求值器，不执行任何外部内容
> ```
>
> **「五边界」中对 helloai 当前唯一有真实意义的是网络边界**——而它的正确实现是**出站 SSRF 守卫**（`REF-5.4`，平台自己发起的外联），**不是容器网络隔离**。**该项已交付**（`LOG-20261010-010`）：两层防线 —— **URL 层**（协议白名单默认只放 https + IP 字面量直判）与**地址层**（`OutboundUrlGuard.SafeDns` 解析即判、并以同一结果建连即 pinning）；跳转逐跳重跑，故旧实现 `Redirect.NORMAL` 的重定向式 SSRF 一并关闭。**实测结论**：OkHttp 对 **IP 字面量不走 `Dns`**（`http://169.254.169.254/` 这类最高危形态只在 URL 层拦得住），这是两层必须同时存在的直接依据。
>
> **触发条件（任一成立 ⇒ 重新进入排期，届时启动专项并出 ADR）**：① 平台增加碰宿主的工具；② 技能包要被执行；③ 平台自持浏览器。**预案**（spec 声明化 / 探针 / scope 生命周期 / 形态 = 独立沙箱服务）见 `plan/HelloAI 借鉴落地实施计划.md` §5。

### Event Consumers

顺序：

```text
Timeline / Replay
→ Audit
→ Recovery
```

> **✅ Recovery 已建（2026-10-16，`D-2026-10-16-11`，记录 `LOG-20261016-011`）**：四读侧齐备。Recovery = 回答「**从哪继续**」（Timeline / Replay / Audit 回答「发生了什么」）：① **只读投影** `AgentEventQueryService#projectRecoveryPoint` → `RecoveryPoint`（未闭合轮次 / 中断点工具 / 该轮已完成工具 / 末事件元数据），零阈值结构量判定（事件层不定义失败终态事件 ⇒ 「无 `AGENT_COMPLETED`」是唯一未终结信号）；② **只读端点** `GET /api/agent-events/recoveryPointBySubTaskId/{subTaskId}`；③ **续跑注入兜底**：`AgentRuntimeContextAssembler` 的恢复上下文改为「`agent_session` 优先、**事件流恢复点兜底**」（会话表在「进程中途被中断 ⇒ 该行停在 ACTIVE / 不存在」时静默零注入，事件流的悬挂 `TOOL_CALL_STARTED` 永不被覆盖）。设计边界见 `design/Agent_Event_Stream.md`「Recovery 消费面」。
>
> **边界（登记不隐藏）**：本项**只读**——不落库、不改业务状态、不反向修正（同 `EventReconciliationService` 的「只报告不修正」口径）；仅提供「Prompt 级续跑注入 + 只读暴露」，**非** ADR-001 §8 的 Step 级续跑（不重跑已成功 Step，仍后置）。前端事件流工作台暂未加 Recovery 视图（后端端点已就绪）。

> **Fork 处置（`D-2026-10-09-5`）**：**WONTFIX**——「触发入口 / 原 Run 冻结 / 驱动新 Run 执行」三项**不立项**。`AgentEventForkService` 作为**未接线的内部能力保留**（快照复制能力，**零生产调用方**）。依据：①「驱动执行」自始即为后置项；② 其实际用法（分叉重跑 / 路径对比）已被 **Return**（REWORK → 驳回 → 改派 → 重开工，闭环完整）与 **Replay 工作台**覆盖；③ 驱动执行须改 Run 标识模型（ADR-001）与 execution command 载荷（协作规约 §30/§31），成本与收益不匹配。**Recovery 保持在本次序内推进，优先级不因本节变化**。

### Planner 能力感知（G-010）

- 设计（唯一事实源）：`doc/design/Planner_Capability_Awareness.md` §7（决策 1~7 全部拍板）
- 当前状态与未达成项：见 §1 矩阵 `G-010` 行
- 实现过程与验证证据：`LOG-20260909-001` ~ `LOG-20260909-012`、`LOG-20260910-001`、`LOG-20261009-011`、`LOG-20261009-013`

### 选人技能门（G-022，2026-10-13 登记 · 2026-10-15 补引用边 · 2026-10-15 补拆解侧前置校验）

- 当前状态与未达成项：见 §1 矩阵 `G-022` 行
- 问题与判定：**技能门两端命名空间不一致**——声明侧用平台技能标签（`eng-*`，4 个技能包前缀），能力侧 Agent 声明的是模型能力标签（`thinking` / `code-review`），全平台**无任何 Agent 声明 `eng-*`** ⇒ 选人 AND 匹配恒失败 ⇒ 被声明了 `eng-*` 的子任务**永久 PENDING**（静默等待：仅时间线与 `context.noCandidate` 可查，操作面不可见）
- 实现过程与验证证据：`LOG-20261013-001`（证据链：`sub_task.required_skills` / `agent.skills` / 全平台 `eng-*` 计数为 0 / 时间线 `sub_task_no_candidate` + 退避字段）；修法三选见 §1 矩阵行

#### 2026-10-15 实施：补「细粒度技能 → 粗粒度模型能力」引用边（修法①的精确化）

**实测起点（本地 PG）**：`sub_task.required_skills` **34/34 行全为 `eng-*`**；`agent` 存活 9 个，其中带 `eng-*` 的仅 **3 个**（全部靠 `checkIn` 自报塞入），4 个 `API_KEY_LLM` 中仅 1 个带 ⇒ `SkillNormalizer` 同义词表只覆盖 8 项标准词、`eng-*` **零映射** ⇒ AND 匹配必失败。

**★关键判定：修法不是「统一词表」**。统一词表会迫使技能包**点名模型**，而 DSH 判据是「**技能不点名模型** ≠ **技能不提需求**」——两者常被混同。故补的是**引用边**：

- **承载**：`SkillPackage` 增 `requiredBaseCapabilities`（frontmatter 白名单 **8→9 键**，属契约变更，四处联动）；`skill_package` 增同名列（迁移 `V113`）。
- **判定式**：技能可派 ⟺ `requires ⊆ provides`，实现在 `SkillCapabilityRequirements.satisfiesPackage`（**唯一判据**，与标准技能的 `satisfies` 同构但**不可合并为一张表**——两个关系正交）。
- **填充口径（诚实边界）**：4 个内置包中**只有 `eng-web-research` 声明 `webSearch`**（依据：其 `requiredTools` 含 `web_search`，与 `web-search ⟸ webSearch` 同一事实源）；其余 3 个（`eng-code-review` / `eng-doc-standard` / `eng-verification`）**有意留空**，理由逐包写入 frontmatter 注释——它们是**作业规范**（与 `M-9` 中 6 项恒真技能同源），声明谓词只会凭空缩小可派面（`reasoning` 会拒掉 **48** 个模型、`toolCalling` 会拒掉 **8** 个），属**新增拒绝面**而非消除越界。`eng-verification` 的真实缺口在**工具面**（内部 LLM 拿不到执行工具），**不在谓词面**。
- **消费方①（注册链）**：`AgentSkillDeriver.deriveWithCapabilities` 新增第 ⑤ 步——把「本模型谓词可承接的技能包标签」并入 `agent.skills`，于是选人处 AND 匹配**有了真实的边**，且边由模型事实驱动、随模型目录变化自动重算。兼容重载（不传包面）**逐字保持原行为**。
- **消费方②（`checkIn` 自报）**：`QA-M9-03` 收口——自报技能此前**完全绕过**能力校验，任一持牌 Agent 可把模型不支持的标签写进 `agent.skills`（实测即 `inner-deepseek-flash-excutor` 被塞 `web-search` + `eng-web-research`，而其模型 `deepseek-v4-flash` 的 `webSearch=false`）。现走**同款判据**，越界项**丢弃并告警**（不抛错：抛错会打断打卡主链）；作用域**限定 `API_KEY_LLM`**（外部 Agent 的模型由其自身掌控，平台无从判其能力）。
- **守卫**：`verify-skill-capability-parity.sh` 增 **S4**（frontmatter 谓词 ∈ 已登记词表；**拼错的谓词会放宽而非收紧匹配**，属放宽型失效方向，必须 fail-closed）+ **S5**（解析器白名单与 record 分量同步，防「契约半改」），各配 `--inject-bad-predicate` / `--inject-unknown-key` 两个反证开关。
- **仍未达成**：① **存量 `agent.skills` 未回填**——须逐个重注册/编辑才能触发挂载（本批**刻意不写迁移**：挂载逻辑依赖技能包目录与模型谓词双读，SQL 侧复现会造第二个事实源）；② ~~拆解侧**无前置校验**（产出 `eng-*` 时不校验「是否存在可承接的 Agent」，静默等待仍在）~~ **已清偿（2026-10-15，`LOG-20261015-006`）**：草案声明技能后校验在岗 Agent 是否可承接（走 `SkillNormalizer.matches`，与选人门**同一匹配器**），无则落时间线 `task_plan_skill_unmatched`（**告警而非阻断**）；③ `eng-*` 装进内部 LLM 工具面的授权集（真实缺口在 `requiredTools` → `ToolRegistry` 的接线，不在本引用边）。

### 需求包准入与不确定性显式管理（G-011）

- 设计（唯一事实源）：`doc/design/Requirement_Package_Uncertainty.md` §7（决策 1~17，含 P1/P2 修订与决策反转登记）
- 当前状态与未达成项：见 §1 矩阵 `G-011` 行
- 实现过程与验证证据：`LOG-20260909-006` ~ `LOG-20260909-012`、`LOG-20260910-001`、`LOG-20261009-009`、`LOG-20261009-013`

### 登录鉴权与 RBAC 权限体系（G-012）

- 会话：Sa-Token 承载（token 走 `X-Admin-Token` 头，active-timeout 8h 滑动续期）；授权：角色-权限码 RBAC（四表 + 内置 SUPER_ADMIN / ADMIN + 注解鉴权）；`active-timeout` 过期触发的实测归 `G-013`（同底座）
- 当前状态与未达成项：见 §1 矩阵 `G-012` 行
- 实现过程与验证证据：`LOG-20260912-001`、`LOG-20260913-007` / `LOG-20260913-008`
- 菜单分组（2026-11-06）：一级菜单由 17 条平铺收敛为 4 个目录 + 个人工作台 + 系统设置；目录沿用既有「聚合父」形态（`type=MENU` + `path` 非空 + `component` 空 + 有子节点，`V143`），不新增类型 / 列；读侧补「祖先链放行 + 逐层剪枝空目录 + `sort` 补 `id` 兜底」，见 `LOG-20261106`
- 登录落点可配置（2026-11-06）：`sys_permission.is_default_home`（`V145` + 部分唯一索引，全平台仅一个）+ 菜单管理页开关；前端 `resolveDefaultHomePath` 优先取标记、无权或无标记时回退菜单树首个可见路由。**0.2.1 镜像首版（12:28）漏带 `V145` 整批，已于 13:38 重打并确认 `sha256:211557c7…` 入 ACR**，见 `LOG-20261106` 末节
- 深化项已转**基础架构专项**（见 `G-013`）

### 基础架构深化（G-013，参考 JeecgBoot，实施编排独立）

- 专项计划（**已执行完成，2026-10-12 迁入归档**）：`doc/archive/implemented/HelloAI 基础架构调整实施计划.md`（批次明细 / 验收 / 遗留）
- 残留两项（**不在该专项范围**）：`active-timeout` 超时**过期触发**未实测；`McpAuthFilter` 沿用 `validateAdminToken` 属**有意设计**
- 编号：`BASE-x.y`——与 `G-xxx` 及重构主线编号完全错开，不混用
- 目标架构融合：`doc/HelloAI 目标架构.md` §12 基础架构（平台底座），与业务五层正交
- 当前状态与未达成项：见 §1 矩阵 `G-013` 行
- 实现过程与验证证据：`LOG-20260912-002` ~ `LOG-20260913-008`、`LOG-20260929-001`
- **设计边界（非缺口）**：`McpAuthFilter` 沿用 `validateAdminToken`——其异常不流经 `@RestControllerAdvice`，改 `checkLogin` 会把 401 变成 500
- 红线：不新增第二套权限体系；不触碰 Event / 状态机 / Scheduler / Workflow / Review；外部 Agent 契约不变；已提交 DDL 只读（新增迁移编号递增）
- 合规基线：专项文档 §4 强制遵循《协作规约》（生命周期 / 复用既有 PS1 / 完成报告 16 节）与《CODE_STYLE》（RBAC 归 system 域 / DTO 投影 / `@Transactional` / §43 认证授权分离 / `v-auth` 统一 `hasPermission`）

### 备份 / 恢复（G-018）

任务锚点：`plan/HelloAI 借鉴落地实施计划.md` `REF-2.3`（备份/恢复）与 `REF-2.4`（停机恢复流程文档化）；设计参考 Octop `infra/backup/`（`pg_dump -Fc` + manifest 前置 peek + 单飞锁 + 仅淘汰自动备份）。

链路：

```text
备份：pg_dump -Fc 全库 ──► MinIO 对象清单（listObjects 递归枚举）──► manifest 前置写 ──► 分布式单飞锁
恢复：manifest peek 校验 ──► 恢复侧安全闸门 ──► pg_restore（灌入目标库，验证口径 = 空库）──► 停机恢复流程（手册）
```

不变量与诚实边界：

```text
运行中备份 ≠ 多文件同一瞬间一致（须在文档中明示，不得宣称「一致快照」）
对象【本体】不在备份内（只有 key + size 清单）⇒ 恢复不回填对象内容，亦不列为目标
listObjects 为空 / 失败 ⇒ 备份显式失败，不得产出「完整备份」
手动备份永不被自动保留策略淘汰（仅自动备份参与 prune）
恢复侧三门：跨引擎拒绝 / schema 版本高过运行时拒绝 / 在线恢复拒绝
恢复端点不带 --clean（冻结硬约束），故对已有同名对象的库直接执行会以非 0 退出码结束
```

**状态**：`REF-2.3` / `REF-2.3b` / `REF-2.4` **均已交付** —— 备份编排（`DatabaseBackupService`）+
恢复侧三门（`RestoreGate`）+ `platform_backup`（迁移 `V105`）+ 停机恢复流程手册
（`doc/manual/platform-backup-restore/runbook.md`）；同批补上生产镜像的 `postgresql-client-16`
（`Dockerfile` app 阶段，计划 `D-2026-10-10-2①` 承诺而此前未落地），使备份在服务器侧真正可用。

验证载体：单元测试 `RestoreGateTest` / `DbSchemaVersionReaderTest` / `PgDumpRunnerTest`；
端到端演练 `scripts/powershell/verify-backup-restore.ps1`（备份 → preflight → 真恢复进一次性探针库 → 逐表对账 → 清理）；
运维手册 `doc/manual/platform-backup-restore/runbook.md`（含停机恢复清单与诚实边界）。

**不变量**

```text
门② 的判据来源 flywayMaxVersion 必须取 flyway_schema_history.version 的「数值最大」
（该列为 VARCHAR，SQL max() 走字典序会取小，进而放行本该拒绝的更新备份）
—— 由 DbSchemaVersionReader 保证（按前导整数求最大，与 MigrationVersionResolver 同口径）
恢复演练只对一次性探针库，绝不指向配置库；app 一键恢复端点按停机操作不自动跑
```

# 4. P2

```text
Quality Gate
Capability-based Agent Routing
Historical Success
Cost / Latency
RAG 知识库（G-019，可后置）
外部 Agent 工作详情快照（G-020，可后置）
LLM 额度与用量监控（G-026，价值项）
项目级交付编排（G-027）
```

### RAG 知识库（G-019）

任务锚点：`plan/HelloAI 借鉴落地实施计划.md` `REF-4.x`；设计参考 Octop `infra/knowledge/`（chunk → parse → embed → index → retrieve → citations），但**不抄其 SQLite 侧库**——PG 单后端是既有优势。

前置三件（未完成前不进入实现）：

```text
① 基础设施：PG 镜像换 pgvector 版 —— ✅ 已落地（`pgvector/pgvector:0.8.0-pg16`，迁移 `V106` 已 `CREATE EXTENSION vector`；本地真库验证 `vector|0.8.0`，三份 compose + Testcontainers 同步）
①b 通义千问 LLM 有效性：✅ 已验证（`qwen-plus` chat 探针 200）
② 架构决策：嵌入模型供应商 / 维度 / 密钥管理（credential_vault）落 design/adr/ —— ✅ 已定稿（`ADR-002`）
③ 接线口径：search_knowledge 经 ToolCallbackContributor 端口注册 + 登记技能包（CODE_STYLE §35.1）
```

先定「什么不许进上下文」，再做检索：

```text
无 KB ⇒ 工具从列表摘除（消费条件可用语义位，与 G-004 同源）
注入预算（char_budget 契约）⇒ 超预算截断可断言
引用溯源 marker ⇒ 喂模型前剥离、前端渲染卡片
```

### 外部 Agent 工作详情快照（G-020）

任务锚点：`plan/HelloAI 借鉴落地实施计划.md` §8（`REF-7`）。**来源 = 用户对平台定位的澄清**：平台**不干涉**外部 agent 怎么做（派单方 ≠ 执行方），但需要一个**事后、经审批、可选**的取证通道，把子任务内部过程补进审计。

链路（形态待三项约束拍板后细化）：

```text
外部 Agent（事后、可选）→ 提交工作详情快照（新 MCP 工具，走既有守卫）
        → 原文落 MinIO（内容哈希 key）
        → 平台解析 → 结构化摘要
        → 时间线插入一条审计事件（闭合 schema + snapshotRef）
```

**动手前三项约束必须先定**（未定不进实现）：

```text
C1 与 REF-6.2「审计闭合 schema」的张力 ⇒ 必须双面（原文落对象存储 + 时间线只放结构化索引 + ref）
C2 审批方是谁 ⇒ 倾向复用人工介入（HUMAN_REVIEW）通道，不建第二套审批
C3 落时间线的粒度 ⇒ 倾向先做「一条汇总事件 + ref」，逐阶段拆解后置
```

**红线**：新增工具须走既有 `McpMcpServer` + `assertAgentActive` / `assertToolEnabled` / `refreshDutyLease` 守卫（不建平行通道），且**默认关闭**（`REF-6.13`：新能力默认关闭、通过注入启用）。★**射程澄清（`D-2026-10-12-5`，2026-10-12）**：本红线射程 = 「**对外暴露的新工具**」；**内部进程内通道**（如 `REF-8.1` 的 `internal_*`）为**显式例外**——以「**执行链绑定闸门**」（`ToolInvocationContext`）替代会话守卫，且**不进对外 MCP `tools/list`**。判据：「**绕过外部守卫**」合理 ≠ 「**暴露到外部面**」合理。

### 内部 LLM 能力面（G-021，`REF-8`，2026-10-12 立项）

**问题一句话**：平台的多 Agent 编排（`planner > review > task > agent`）已是成熟 P&E 骨架，但**内部 LLM 这一层 = 「循环骨架 + 空工具面 + 无手脚 + 只覆盖 1/16 的 LLM 面」** ⇒ 退化为单轮文本生成器。这与 `G-003`（AgentRuntime）不冲突：`G-003` 验收的是「**循环能跑**」（八件套落地），`G-021` 记的是「**循环的两端空**」（注什么工具 / 哪些面在循环里）。

**内核轴 = 「可见面收敛」**（`D-2026-10-12-1③`）：内核唯一输出契约 = **模型的可见集**。

```text
L1 可见工具集    由 ToolRegistry.resolve 单口径产出（源不得被上游剥空）      ← REF-8.1
L2 可见技能目录  全量目录（name + 封顶 description），正文惰性加载           ← REF-8.3
L3 可见上下文    system prompt（非空）+ 工作区 / 附件读手脚                 ← REF-8.2
```

**五个动作**（详案见 `plan/HelloAI 借鉴落地实施计划.md` §10）：

| 编号 | 动作 | 现状证据 |
|---|---|---|
| `REF-8.1` | **工具面解封**——给 `API_KEY_LLM` 一条**不依赖 MCP 会话**的工具通道（读原附件 / 文件读写 / 正式交付动作），复用 `ToolRegistry` 语义位 + `ToolCallbackContributor`，与 `resolve` 保持单口径 | ✅ **M1 已实施（2026-10-12）**：新增 `InternalLlmExecutorTools`（进程内 @Tool：读详情/读依赖/读状态/交付登记/读附件）+ 独立进 `McpToolConfig` provider；`AgentMcpServerServiceImpl:267-275` 对 `API_KEY_LLM` 返回内部授权集（而非空）；外部 MCP 13 工具仍对内部 LLM 剔除（防 L3 P1-1）。单测 `InternalLlmExecutorToolsTest` 6/6 + `AgentMcpServerServiceImplTest` 7/7；全量 1976 单测绿、架构守卫 EXIT=0。**2026-11-17 注**：外部工具面已为 14（ADR-009 A-1/A-4），剔除机制不变 |
| `REF-8.2` | **补手脚 + 上下文**——新增 `WorkspaceAccess`（先只读 + 只写产物目录）+ 读附件工具；`systemPrompt` 从 `""` 改为「角色 + 工具使用指引 + 输出契约」；`maxIterations` 按任务类型分档 | ✅ **M2 已实施（2026-10-12）**：`AgentRuntimeContextAssembler` 新增 `buildSystemPrompt`（角色 + 内部 LLM 工具指引 + 输出契约，替换 `systemPrompt("")`）；`maxIterations` 分档——`AgentExecutionProperties` 增 `internalMaxIterations=10/externalMaxIterations=5` + `RuntimeTurnExecutor.resolveMaxIterations` 按 accessType 选档（`AgentLoopInput` 加 `DEFAULT_MAX_ITERATIONS_EXTRA=10`）。**手脚已被 REF-8.1 覆盖**（`internal_read_attachment` 读附件 + `internal_register_output_attachment` 交付登记），不再建 WorkSpaceAccess 文件系统工具（需 sandbox，REF-3 条件触发不排期）。单测 3 个新增；全量 1978 绿 |
| `REF-8.3` | **技能改「全量目录 + 惰性加载」**——注入目录段（非速览），新增惰性加载工具，`AgentSkillSpecService` 加 `get(name)`；保留 `requiredTools` 作能力级声明 | ✅ **M3 已实施（2026-10-12）**：`AgentSkillSpecService` 加 `getPackage(name)` / `loadBody(name)`（委托 `SkillPackageCatalog.byName/bodyOf`）；`InternalLlmExecutorTools` 加 `internal_list_skills`（全量目录，name/version/description/requiredTools 封顶）+ `internal_load_skill`（惰性加载正文）；`INTERNAL_LLM_TOOLS` 加 2 工具；`buildSystemPrompt` 增技能发现指引；`requiredTools` 保留能力级声明。单测 2 个新增；全量 1980 绿 |
| `REF-8.1.1` | **自报阻塞通道**——内部 LLM 缺「做不到」出口 ⇒ 只能把「做不到」写进正文（物化交付物 → 评审打回 → 返工至死信） | ✅ **已实施（2026-10-12）**：新增 `internal_report_blocked`（进程内 @Tool，带**知情拒绝闸门**——须先读过子任务详情、原因 ≥10 字）→ `block()` + 人工介入标记；阻塞后循环结束的 INTERNAL 回写因「状态非 `IN_PROGRESS`」被丢弃 ⇒ 不进评审。单测 4 例；形状对齐 DSH `update_goal.blocked`（研究文档 §1.5.3） |
| `REF-8.1.2` | **persona 两槽位（身份面）**——systemPrompt 身份写死且不可覆盖，缺「专业角色面」 | ✅ **已实施（2026-10-12）**：`AgentExecutionProperties` 增 `personaPrefix` / `personaSuffix`（`helloai.execution.*`，默认空 ⇒ 行为零变化）；装配槽位序「内置身份行 → persona prefix → 工具指引 / 输出契约 → persona suffix」；取值顺序 `agent.labels` → 部署配置 → 空（**不动表结构**）。对齐 DSH `deployment:persona-*` + `dsh-persona` 遮蔽纪律。单测 3 例 |
| `REF-8.2.1` | **返工上下文「替换而非追加」**——原把**所有轮次**审核意见全量拼入 ⇒ 思考链与 token 随轮数线性膨胀 | ✅ **已实施（2026-10-12）**：只铺开最近一轮（时间 / 问题 / 评语 / 评分 / 已自认修复），历史轮次压成一行并明写「已被本轮取代」。回归断言：历史轮次正文不进 prompt |
| `REF-8.2.2` | **验证证据围栏（结构化轮报）**——执行侧输出契约缺证据段，而评审侧 fail-close 明文要求 `VERIFICATION` 段 ⇒ 内循环必然返工至死信 | ✅ **已实施（2026-10-12）**：输出契约补 `VERIFICATION:` 围栏（证据 + `STATUS` + `NEXT_STEPS`），**复用评审侧既有判据**（`ReviewEvidenceAssembler.verificationSignal`）⇒ 评审侧零改动。实测：同一「撰写摘要并引用 marker」子任务由「返工 3 次 → 死信」变为首轮通过 |
| `REF-8.2.3` | **上下文压缩**——无预算与压缩、工具直读附件无上限、提供方报超窗即失败 | ✅ **已实施（2026-10-12）**：两道（确定性工具结果剪枝 → 仍超预算才 LLM 摘要）+ 两触发（每轮调用前压力检查 / 提供方报错后的溢出恢复）；含**净收益守卫**（仅净减才接受，否则回滚原文）与**摘要额度**（单次执行封顶）。摘要复用同一 ChatModel 与 options、逐字保留最近 `retainRatio` 尾部、不拆 assistant + tool 结果对；新增 `CONTEXT_COMPACTED` 审计事件。对齐 DSH `compaction-basic` + `compaction-tool-result-pruner` |
| `REF-8.4` | **拦截链** —— **循环自有 `LoopHook` 三档**（`beforeRequest` / `onRequestError` / `beforeTerminate`；**不套** Spring AI `Advisors`）；`LOG-20261011-007` 的 **B（收敛熔断）** 挂此 | ✅ **已交付（2026-10-13）**：① 三档挂点骨架 `09ecd747` · ② 收尾轮 `873bb559` · ③ `VerificationFence` / ④ 出口分类 `3b5224a6`（接线点 `ChatModelToolLoop:129/142/236`）；全量 2031 例绿（`LOG-20261013-010`/`-011`/`-012`）。**A 能力闸已出列** ⇒ 落 `ResilientDispatcher`（`D-2026-10-15-6`） |
| `REF-8.5` | **循环覆盖面收敛**——逐面判「该不该进循环」（子任务评审、终报告生成理论上都该带工具） | ✅ **已交付（2026-10-16，`LOG-20261016-005`）**：覆盖面 16 项**已逐项裁定**（进循环 4 项 / 留单次 12 项，边界即 `plan §10.6`）；新增业务面循环入口 `BusinessSurfaceLoopRunner` + **单咽喉插点**（`PlatformAgentExecutionServiceImpl.execute`，不散改 4 个调用点）+ 独立开关 `helloai.execution.business-loop-enabled` 与 scene 白名单（默认即裁定四项）。**三条实现裁定**（形态见 `plan §10.9`）：① 业务面**不复用** `RuntimeTurnExecutor`（子任务链契约三处冲突：入参守卫 / `VERIFICATION` 围栏误杀 / 子任务语义事件污染对账）而与它并列复用 `AgentLoop`；② 业务面**不写 `agent_event`**（防 `EventReconciliationService` 末条事件对账误报 + 不新增第二事实通道）；③ 开关**默认开启**（裁定即生效），回退通道保留。同批抽出两条单口径实现（`VisibleToolCallbacks` / `EnabledToolNames`，后者原为三处副本） |

**收敛完成判据**（内核自洽，较「组完成」更严）：① 不依赖 MCP 会话可独立构造 / 测试；② **可见面单口径**（模型可见工具集 = `ToolRegistry.resolve` 结果，上游无第二处二次过滤——对应已证缺陷模式「**规则存在 ≠ 全路径强制**」）；③ **能力可验证**（拿一条「需读原附件 / 写产物」的验收任务，内部 LLM 能**自己调工具**完成）；④ 事件不双写（模型可见消息与事件流同源）。

**红线**：**不新建 append-only 表**（`agent_event` 已是主表 + Outbox 双写，见 `AgentEventRecorder.java:11-15`）——正确动作是**提升已有 `agent_event`**（补 `surface` 投影 + `deriveMessages()`）；**不动 `ChatModelToolLoop` 循环本体**；**不引入 LangGraph4j**（触发信号出现前）。

### LLM 额度与用量监控（G-026，2026-10-26 立项）

任务锚点：设计 `design/HelloAI LLM 额度与用量监控设计.md`（`Status=Implemented`）；实施计划 `archive/implemented/HelloAI 额度与用量监控实施计划（2026-11-02）.md`（`Q-1`~`Q-10` 已完成，验收证据见该计划 §8）。

**要解决的事**：4 家外部供应商的余额分散在各自控制台，当前只能靠「调用失败」事后感知额度耗尽。

**设计已定的六条**（详见设计文档，此处只留结论）：

```text
① SPI           BalanceProvider（key / supports / fetch），fetch 不抛异常、失败返回 failed(reason)
② 密钥来源      BalanceCredentialResolver 抽象两层：vault（3 家 LLM）/ sys_config（博查）
③ 数据          只建一张 llm_balance_snapshot；不建 llm_account（与 vault 双写）、不建 llm_usage_daily（与事件源双写）
④ 缓存          Redis RBucket TTL 60s（多实例不重复打外部接口）+ Resilience4j quotaFetch 熔断
⑤ 刷新          前端按 `sys_config["quota.refresh.interval-seconds"]` 轮询（默认 60s，60~3600 可配，0=仅手动）+ 定时采集 15min 且服从该间隔；不采用 SSE（低频数据，且已有跨实例踩坑）
⑥ 前端          MainLayout 悬挂浮球，折叠显示总额、展开列 4 家；金额型与额度型分开（MiniMax 是百分比）
```

**范围裁定**（2026-10-26 用户拍板）：通义千问**不显示**——无公开余额接口，需阿里云 AK/SK 签名，而本平台只有 `sk-` key。
「显示一个取不到数的行」比「不显示这一行」更容易被当成缺陷。

**与外部建议的三处有意分歧**（外部方案 vs 本设计）：

| 外部建议 | 本设计 | 理由 |
|---|---|---|
| 三张表（account / snapshot / usage_daily） | **只建 snapshot** | 另两张分别与 `credential_vault`、`agent_event` 双写，必然漂移 |
| SSE 增量推 | 按配置间隔轮询 + 60s 缓存 | 低频数据建高频通道，成本高于收益 |
| 余额 5 分钟轮询 | 15 分钟 | 4 家 × 96 次/天已足够，余额是日级变化 |

**前置待验证**（写 plan 前必须确认，否则实现会返工）：博查 `remaining` 单位（元/分）、博查 base-url 用 `api.bochaai.com` 还是 `api.bocha.cn`、
MiniMax `token_plan/remains` 实际结构、`deepseek-v4-flash` 是否仍适用峰谷价、`credential_vault` 同 provider 多条 ACTIVE 时的取数规则。

**明确不做**（M1）：AGENT 级 22 条凭据余额、调度联动（额度低停止派单）、MCP 工具 `queryBalance`、token 五分量与趋势图、阈值告警。

**落地与验收（2026-11-02，计划已归档）**：`Q-1`~`Q-10` 全部已完成，计划迁 `archive/implemented/HelloAI 额度与用量监控实施计划（2026-11-02）.md`（`Status: Done`，验收证据见该计划 §8）。4 家真机出数（deepseek / moonshot 金额型、MiniMax 百分比额度型、博查无 key 时 `FAILED` 不伪报 0）；刷新间隔可配（`sys_config["quota.refresh.interval-seconds"]`，`0`=仅手动 / `60~3600` 秒，系统设置「额度监控」区）+ 手动模式只读落库快照不触网 + 浮球四向可拖；quota 单测 45 例、`helloai-core` 全量 2862 例 0 失败，前端构建与架构守卫全绿；用户确认「额度监控没问题」⇒ 浏览器端可视化验收通过。

### 项目级交付编排（G-027，2026-11-04 立项）

任务锚点：设计 `design/HelloAI 交付编排能力设计.md`（`Status=Planned`）；实施计划未立（待 `V1`~`V5` 验证后出 `plan/` 批次）。

**要解决的事**：需求文档进来后，平台只有「一次任务 → 静态 DAG → 派发 → LLM 验收」内循环，没有项目级载体——后续阶段无法基于已改完的代码滚动拆解，代码类验收缺确定性判据，执行环境无可持有的代码工作区。

**设计已定的九条**（详见设计文档 §3，此处只留结论）：

```text
① 计划层      Plan 双表示（Document 人读 + Spec 执行，Markdown 只是读侧投影）+ 滚动式规划（Phase 开始才细拆）
② 领域归属    不新建 delivery 域：Plan 归 planner / 工作区归 task / 验收机归 review / Provider 归 agent
③ 验收机      命令退出码为权威（验收机上跑，不在执行 Agent 机器上跑）；LLM 只判意图；ArtifactVerifier SPI
④ 执行通道    外部 Coding Agent 起步（复用 executor 通道）；CodingAgentProvider SPI 预留 ACP 形态；不自建执行内核
⑤ Git         worktree 隔离 + 权限分级（平台代建分支、不 push 主干、收 commit hash + diff）+ Git Gateway 短期凭证
⑥ 并行        touchedPaths 交集判据（非空强制串行）+ 合并队列每合并一支重跑验收机
⑦ 约束        Context Bundle 五层 + 可信度分级（拦截靠工具与验证，Prompt 只做「知道」）
⑧ 成本        Phase 级 token 预算 + 超限熔断（复用 G-026 口径）
⑨ 漂移        PLAN_DRIFTED 三信号（基点后变更触及 touchedPaths / 用户改 Plan / 计划假设不成立）
```

**前置待验证**（写 plan 前必须确认，否则实现会返工）：外部 Agent 无头运行能力（Trae / Claude Code CLI / Aider 各实测）、worktree + 平台代建分支 + 短期凭证闭环、验收机容器计时、`touchedPaths` 产出可用率、worker 中断接续。

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

### 内部 LLM 运行时合同（`G-023`，`REF-8.6`~`REF-8.9`，2026-10-16 立项）

> **立项裁定**：`D-2026-10-16-4`（记录 `LOG-20261016-004`）。**依据**：`review/内部 LLM 执行链运行时合同核对·以 DSH 为基准的重判（2026-10-16）.md`。

**为什么独立于 `G-021`**：`G-021` 的名称已限定为「工具面 / 手脚 / 技能装配 / 循环覆盖面」（**可见面收敛轴**）；本项是**运行时语义轴**（终止 / 取消 / 观测），两轴并存、互不替代（重判报告 §七）。

| 编号 | 缺口 | 优先序 | 验收要点 |
|---|---|---|---|
| **`REF-8.6`** | 取消 / 超时树缺失 | **1（最高）** | ✅ **已交付（`LOG-20261016-006`；形态见 `plan §10.10`）**：协作式取消（装配点收敛为 `AgentLoopInput` 紧凑构造器「唯一咽喉」，任何调用方无法绕过）+ 单工具超时（返回失败 observation 交模型决策、**不整链失败**）；**反证用例** = 工具调用中途取消 ⇒ 循环不再发起下一轮模型调用 |
| **`REF-8.7`** | `max-tokens` 截断被静默当成功 | **2** | ✅ **已交付（`LOG-20261016-007`；形态见 `plan §10.11`）**：新增独立终态 `MAX_TOKENS`（`success=false`、保留部分正文）；**sticky** 由「出口定性」实现 —— `truncated` 只置不撤，全部出口在进 `beforeTerminate` 挂点**前**即定为截断草稿 ⇒ 现有挂点自然跳过；结束原因取值容错、**未知取值 fail-open** |
| **`REF-8.8`** | 观测面 usage 只有单一 `totalTokens` | **3** | ✅ **已交付（`LOG-20261016-008`；形态见 `plan §10.12`）**：新增 `AgentUsage`（互斥五分量 + `totalTokens` + latency）；`AgentLoopResult` 增第 9 分量 `usage` + `withUsage`（**既有 8 参 factory 全保留**）；落 `AGENT_COMPLETED` payload 扩 6 键（不新增事件类型）；provider 缺字段**降级为 null、不报错** |
| **`REF-8.9`** | 独立预算闸缺失 | **4（登记不启动）** | **非对齐项 / 自研增强** —— 参照实现同样不做；前置 = 「每步可查询用量状态」载体（现不存在） |

> **落地进度**：`REF-8.6` / `REF-8.7` / `REF-8.8` 均已交付（形态分别见 `plan §10.10` / `§10.11` / `§10.12`）；`REF-8.9` 保持 `DEFERRED`（登记不启动）。详见 `plan §10.8`（立项与优先序）。

**明确不做的两项**（防后人按表面严重度反向排期）：`refusal` 当成功（参照实现同样不处理）· 独立预算闸作为「对齐项」（参照实现完全不做）。

---

# 7. Gap 登记与回流规则

## 7.1 孤儿项回流规则与已登记项

设计文档（专项设计 / 执行方案 / ADR）中作出「推迟到 X 期」「划远期」「降级交付」的决定时，必须在本表同步登记对应条目或显式记 WONTFIX，不得只留存在设计文档内——否则该承诺会随批次闭环从索引中消失。

### 7.1.1 契约层能力注入与最终报告整合（2026-09-28，依据 `doc/archive/implemented/HelloAI_契约层能力注入与最终报告整合改造方案.md` §8）

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| N1 | 报告分章 map-reduce（逐子任务章节独立成文） | 登记待决策项（A5），决策前不实施 | G-016 |
| N2 | SYNTHESIZER 角色 / 任务级 reportAgentId（报告执笔 Agent） | 依赖 Role Layer 角色模型调整，后置 | G-016 |
| N3 | 同步 LLM 调用迁入 AgentRuntime | 后置；契约层注入挂点随迁 | G-002 / G-016 |
| N4 | 通用 Quality Gate（Rule + Test + LLM 统一决策） | 后置；报告场景已有实例 | G-007 / G-016 |
| N5 | Skill Instructions 字段结构化 | 约定反转登记，后置 | G-004 |
| N6 | final_report 注册为 attachment（交付形态统一） | 后置 | G-016 |

### 7.1.2 借鉴落地专项（2026-10-09，依据 `plan/HelloAI 借鉴落地实施计划.md` + `D-2026-10-09-5`）

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| R1 | **Fork 触发入口 / 原 Run 冻结 / 驱动新 Run 执行** | **WONTFIX**（用户裁定，2026-10-09）——「驱动执行」自 2026-10-04 起即为后置项，其用法已被 Return + Replay 覆盖；须改 ADR-001 Run 模型与 execution command 载荷，成本收益不匹配 | G-001 · G-006 |
| R2 | `AgentEventForkService` 快照服务本体 | **保留**（未接线的内部能力，零生产调用方、零运行成本）；删除与否另行裁定 | — |
| R3 | 备份/恢复与 RAG 知识库 | 正式立项 → `G-018` / `G-019`（非回流项，此处仅登记立项出处） | G-018 · G-019 |

### 7.1.3 技能包安装入口（2026-10-10，依据 `plan/HelloAI 借鉴落地实施计划.md` §REF-1.6 节 + `D-2026-10-10-1`）

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| R4 | **外部技能目录**（`REF-1.2a` 的「可选外部目录」配置项） | **WONTFIX**（2026-10-10）——「新增技能零发版」的目标改由 `REF-1.6` 安装入口满足；保留目录会与受控存储形成两个事实源，且 Docker 部署需挂卷 | G-004 |
| R5 | 技术债：`MyBatisPlusMetaObjectHandler.getCurrentUser()` 恒返 `"system"`，全平台 `create_by` 不记操作人 | 登记；**不在 REF-1.6 内修**（平台级语义变更，牵动所有表），另行立项 | — |
| R6 | 沙箱 ADR（「平台与宿主文件系统的边界」这类系统性议题） | 登记升级触发；`REF-1.6` 走受控存储**不触发** `G-005` 条件①，本批**不建新 ADR** | G-005 |
| R7 | `SaFirewall`（Sa-Token 1.44 **默认防火墙**，应用未配置）对非法 HTTP Method 回 **HTTP 200 + 错误体**而非 4xx | 登记为**低危技术债，暂不修**：触发条件苛刻（需调用方自身发出非法方法）；修它要配 `SaFirewallCheckFailHandleFunction`，会改变**所有**非法请求的响应形状。防线改落**验证脚本侧**——须断言响应体语义、不得只看状态码（判据见 `LOG-20261010-005`） | — |

### 7.1.4 REF-5 四项之外 A 档（2026-10-10，依据 `plan/HelloAI 借鉴落地实施计划.md` §7）

> A 档六项里 `REF-5.2a` / `5.1`（`LOG-20261010-009`）与 `REF-5.3` / `5.4`（`LOG-20261010-010`）已交付；
> 本表给**每一项**留永久锚点，避免它们随批次闭环从索引里消失（`REF-5.6` 已并入 `REF-2`，故不列）。
> `R14` 为 2026-10-12 补登记 —— A1「载荷旁路」的**配套第二条**此前仅在 `research/` 正文出现，无 `REF-` 落点、未记 WONTFIX（孤儿项回流，`LOG-20261012-001`）。

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| R8 | **初始化期服务锁定**（`REF-5.3`） | **已交付**：判据 = **用户数 == 0**（不用 `system.setup_finished` —— 该值在 `V1__init_all.sql` 被预置为 `'1'`，当判据**永不生效**）；落点 `SetupLockInterceptor`，除 `/api/setup/**` 与 `/api/health/**` 外一律 `503 setup_required`，与前端 `!hasUsers` 同源。**边界**：闩锁在内存、单向 —— 清空用户表后恢复锁定需重启 | — |
| R11 | **子任务视图读判据统一**（2026-10-10 用户裁定） | **已交付**：原为**两套判据且都有问题** —— 时间线 / 对话流只认 `sub_task.assigned_agent_id`（实测把**合法团队成员**也拒：加入 team 后读附件 200、读时间线仍 403）；`/api/sub-tasks/getById` **无任何校验**（任何有效 Agent API Key 可读任意子任务详情）。三处一律收敛到 `SubTaskViewGuard` → `AttachmentVisibilityPolicy.canReadTaskScoped`（与附件读侧**同一判据**，逻辑零复制）。**未动**：MCP `getSubTaskDetail` 的「本人已分配 / 未分配且 PENDING」属**工作面**约束，与可见性面语义不同 | `G-014` |
| R14 | **图片 / 二进制载荷「只存路径引用」**（A1「载荷旁路」**配套第二条**，来源 Octop `infra/agents/middleware/workspace_image.py`） | **条件触发**（2026-10-12 用户裁定）：判据 = 图片在 checkpoint / history **只存 `workspace://` 路径引用**，仅在 model request 时展开为 `data:` URI ⇒ `base64` 永不落 state / 不进历史 payload（与 A1 主条目同属「数据面 / 控制面分离」，治的是**图片 base64** 那一半）。**当前不适用**（实测：平台**无图片 / 多模态输入通道**，业务侧 `base64` / `image_url` / `data:image` / `vision` 零命中；附件注入 `UpstreamAttachmentRenderer` 为**纯文本按行截断**）。**触发条件**：平台新增图片 / 二进制多模态输入通道 ⇒ 按 `REF-5.2b` 同思路落地（state 只留 `ref`）。**不排期** | `REF-5.2b` · `G-005` |

### 7.1.5 REF-6 判据守卫与前端事件字典（2026-10-11，依据 `plan/HelloAI 借鉴落地实施计划.md` §9 与 `LOG-20261010-013`）

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| R12 | **前后端事件码 key parity 守卫**（`REF-6.8`） | **已交付并随形两次重构**：`scripts/shell/verify-event-key-parity.sh`（静态模式入驻 ci-gate 门禁 3；`--db` 地面真值模式覆盖常量传参 / 历史码等静态盲区）。当前三断言 = 写点无裸字面量 / 写点枚举引用常量存在 / 库内真值 ⊆ 枚举。**边界**：前端硬编码字典已随服务端单一来源收口删除，守卫不再对账副本 | `G-001` · `G-006` |
| R13 | **事件码服务端单一来源 + 写点枚举化**（`REF-6.8` 另半） | **已交付**：`TimelineEventCode` 枚举（141 码）为唯一事实源 + `GET /api/events/catalog` 下发（前端删副本改拉目录）；后端展示通道写点 119 处由裸字面量改为 `TimelineEventCode.<CONST>.code()`（core 25 文件 + job 6 文件），无残留。验证见 `LOG-20261011-006` / `LOG-20261011-008` | `REF-6.8` · `G-001` |
| R9 | **载荷双视图**（`REF-5.2b`） | **已评估，结论 = 改形**（`doc/review/HelloAI REF-5.2b 与 REF-7 合并评估（2026-10-10）.md`）：不引入 `ToolResult` 双视图（该落点**在代码中不存在**）；实测前提修正 —— **数据没丢**（原文全量在 PG/MinIO），丢的是消费侧**可见性**；且 **`DEP_CONTENT_MAX_CHARS` 是整块总预算**（多前置附件合计越限即截断），按总预算实测**现网已在触发**（至少 2 条、最坏 12 条子任务越限）。**改形 = 标注行补 `id=<attachmentId>`（已交付）+ 复用既有 REST 取回通道**（实测团队成员 200），**不新增 MCP 工具**（工具面保持 13） | `G-020` · `REF-7` · `R11` |
| R10 | **目录守卫 + 结构化拒绝码**（`REF-5.5`） | **未来前置**：开放「用户指定工作目录」之前置，当前平台无该能力 ⇒ 不排期 | `G-005` |

### 7.1.6 会话过期回收（2026-10-18，`G-013` 超时实测的副产品）

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| R14 | **Sa-Token 过期会话键不回收** | **登记不修（低危）**。实测：`sa-token.timeout = -1`（token 永不过期）⇒ 会话键无 TTL；`active-timeout = 28800s` 超时后 `checkLogin` 正确抛「登录已过期」，但 `X-Admin-Token:login:token:<tk>` 与 `X-Admin-Token:login:last-active:<tk>` **两键 TTL 仍为 `-1` 且不被删除**，反复调用不复活也不清理。**影响面**：仅管理端登录会话（数量级小），且过期后行为安全（恒 401）；实际表现是 Redis 键**缓慢无界增长**。**不修理由**：① 主动 `logout` 路径（`AuthServiceImpl#logoutByTokenValue`）会正常清理，只在「不登出直接放到过期」时残留；② 修法要么改 `timeout` 为非 -1（会连带改变会话语义），要么新增清理任务（新增能力，收益不匹配）。**重估触发条件**：若管理端会话量进入千级、或 Redis 内存出现可见增长，再立清理任务 | `G-012` · `G-013` |

### 7.1.7 `ADR-008` 派生式子任务（2026-11-09，依据 `design/adr/ADR-008-task-decomposition-derivation.md`）

> **状态：已落地（2026-11-11，`AD-1`~`AD-6` 真机通过；迁移收敛为 `V146`~`V149` 空库链，原 `V150`~`V152` 已在提交前取消）。** 本 ADR 取代原「草案暂存区与稳定编号设计」§4.3（S3 展开）与 §7（深度按 code 段数）；该设计文档的有效内容已并入 ADR §19 后删除。
> **迁移链订正（2026-11-12）**：现役为 **`V146`~`V150`**——`V150__conversation_derivation_lineage.sql` 承载 A8-11/A8-13 两条血缘列（并入原独立 `V151`；用户裁定「一次版本变更只出一个迁移号」）。本机已 apply；dev 尚未 apply（`LOG-20261112`）。
>
> **定稿四项**：① 完成态传播 = 同步 + 第 23 个对账任务，**不建生命周期 outbox**；② 草案期派生不建会话、直接建 `Y` 并触发拆解，执行期重构建 ACTIVE 会话并记 `source_sub_task_id`；③ `Y` 继承父 `archetype`，只许更严；④ ADR 为唯一设计源，计划只留批次/状态/验收。
>
> **直接作废、不再排期**：`TD-16a` 的 `blockedBy` 契约、`TD-16d②`、「确认时扁平化」、`draft_code` 多段 / `DraftCode.child()` / `resolveParentDraftCode`、`expandById` / `expandDraftByCode`、`referencingDrafts`、`TD-16e-3-1b`、`TD-16e-4`、`TD-16f`、暂存区 `parent_code` / `depth` 语义。

| 编号 | 回流项 | 决定 | 关联 |
|---|---|---|---|
| A8-1 | **失败传播**（派生任务 `Y` 失败/废除时父 `A2` 处置） | **已收敛**（`ADR-008` §13.4）——`A2 → RESTRUCTURING` 人工接管，不做 `FAILED` 冒泡。原「三选一未决」作废 | `TD-16f` 栅栏同型 |
| A8-2 | **报告回挂方式**（派生任务产出如何让下游读到） | **已定：方案 C**（§15.2）——把「`A2` 原产出 + `Y.final_report`」合并物化附件挂到 `A2`，`loadUpstreamContent` 天然附件优先 ⇒ 读侧零改、审计链不被伪造记录污染。**两个前置**（§15.4）：`AttachmentServiceImpl#register` 当前强制 agent 归属，需先开平台写入入口；且必须合并渲染，不能只挂报告（否则顶掉原产出） | `G-016` · `N6` |
| A8-3 | **`DONE` 是否允许重构** | **已定：不允许**（§11.2 保守口径）——`DONE`/`CANCELLED` 是终态，不出边。若要放开「`DONE` 但下游未消费」，须先加「谁读过我的产出」消费埋点（当前 `buildDependencySection` 读而不落痕） | — |
| A8-4 | **`RESTRUCTURING` 是否需要自动超时** | **决定：不设**（§17.3）——自动转走违背「人工接管」本意。替代：进「待我处理」列表 + 看板告警 | — |
| A8-5 | **`G5_RUNTIME_CONTRACT_BREAK` 确定性部分的误报率** | **未验**——文本包含匹配（下游 `acceptance` 是否出现本任务产出的字段名）需实测样本，误报高则整条判据走模型 | `G-007` |
| A8-6 | **派生会话入口落点** | **已落地（2026-11-12 起双入口都建会话）**——草案审核页「拆出去」改走 `POST /tasks/draftDeriveSession/{taskId}/{code}`（`task:plan`，锚 `(task_id, source_draft_code)`，`V150`；定稿才建 Y）；子任务详情页 `subtask:restructure`（进入重构 / 编辑要求 / 整改回派发）与 `subtask:derive`（拆出去，建 ACTIVE 会话）。旧的「草案期直接派生不建会话」`TaskController.deriveDraftByCode` 仅作为外部 API 契约保留、前端已不再调用。种子与落地断言见 `V148` / `V150` | `TD-16e-3-2` |
| A8-7 | **一致性契约：outbox / TCC / Saga 的取舍** | **已定（2026-11-10 修订）**——TCC 与 Saga 在生产代码命中 0（从未实现）；TCC 仍不引入（单体同库无跨服务预留）；Saga 只要动作表（`task_plan_attempt` + `DERIVE`/`RESTRUCTURE`）。**完成态传播不建生命周期 outbox**：现有两张 outbox 表分别绑 `ExecutionCommandMqMessage` 与 agent 路由键，均不承载任务生命周期；改为 `tryCloseTask` 同步传播 + 第 23 个 `TaskLifecycleReconcileTask` 三项对账 | — |
| A8-8 | **`G5` 的模型调用预算** | **未决**（§17.6）——`LOG-20261109` 实测语义门 30s **余量为零**（5 样本 2 触顶）。`G5` 是运行期门、频率更高且用户正在点「重构」，30s 等待更难接受。方向：只做确定性部分 + 语义异步补判 / 改「分批判」 | `A8-5` |
| A8-9 | **上 k3s 是否要分「单机 / 分布式」两套代码** | **已定：不分**（§18.7）——用「默认按分布式正确写 + 就绪度清单 + CI 守卫」代替双路径。盘点：**A 已安全**（`shedlock` 用 `RedisLockProvider`）/ **B 补一行**（仅 `ExecutionCommandPoller:150` 丢弃 `markPolled` 返回值且非 CAS）/ **C 本就各管各** / **D 需重设计：无**。新增 CI 守卫「`@Scheduled` 必须带 `@SchedulerLock`，白名单除外」 | `A8-7` |
| A8-10 | **草案期派生的父锚**（`X` 确认前 `A2` 尚不存在） | **已定**（§3）——`Y` 用 `(parent_task_id, parent_draft_code)` 锚定，`X` 确认物化 `A2` 时回填 `parent_sub_task_id`；两条条件唯一索引分别管两种锚 | `A8-1` |
| A8-11 | **执行期重构会话的血缘列** | **已定**（§19.6）——`requirement_conversation` 新增 `source_sub_task_id` + 部分索引（`V148`）；草案期派生同样建会话，锚为 `(task_id, source_draft_code)`（`V150`，2026-11-12 起；此前草案期不建会话故无此列）。两条血缘列（本行的 `source_draft_code` 与 A8-13 的 `derived_task_id`）**合并为同一个迁移 `V150`**，各带一只部分索引。**产出任务不在 `task_id`**（派生会话的 `task_id` 是父任务 X），见 A8-13 | `A8-6` |
| A8-13 | **派生会话的产出指针** | **已落地（2026-11-12）**——`requirement_conversation.derived_task_id`（`V150`，与本行 A8-11 的 `source_draft_code` 同属一条迁移 `V150__conversation_derivation_lineage.sql`）。派生会话的 `task_id` 按 §13.1 仍是父任务 X，产出任务 Y 单列；读侧统一 `derivedTaskId ?: taskId`（`taskExists` / 「查看任务」/ `regenerate` 守卫）。此前三者都读 `task_id` ⇒「查看任务」跳到父任务，用户在 X 上点「拒绝重拆」把整条链（含 Y 与 Y 的子任务）重置；`regenerate` 亦因 X 恒存在而永不可用。父任务 `rejectPlan` 级联取消 Y 时同步把派生会话置 `ABANDONED` | `A8-6` |
| A8-12 | **`Y` 的 archetype** | **已定**（§19.7）——继承父任务，只许更严（`maxDepth = min(父档, 本档)`），不重新判定 | `A8-1` |

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

旧编号明细与登记背景见 `archive/legacy/V1_HelloAI 实现差距表.md` 与 `doc/archive/log/2026-09.md`（LOG-20260907-001）。
