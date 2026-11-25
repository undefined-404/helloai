package com.helloai.common.constant;

/**
 * {@code sub_task.context} 的键常量（`TD-11` 起收口）。
 *
 * <p><b>为什么放 {@code helloai-common} 而不是某个域</b>：`sub_task.context` 是自由 JSONB，
 * 键名靠字符串约定。本轮的写入方在 **planner 域**（拆解落库时标注），读取方在 **review 域**
 * （核验时判是否豁免）。按依赖链 {@code planner(0) > review(1) > ...}，review 依赖 planner
 * 是**反向依赖**（会被 {@code check-arch-freeze} 阻断），planner 依赖 review 也不该有；
 * 故键名只能落在两侧都可依赖的最低层。与 {@link UncertaintyKind} 放同一处的理由完全一致。</p>
 *
 * <p>同族既有键（{@code reviewHistory} / {@code lastAutoReview} / {@code manualIntervention}）
 * 仍散落在写入方各自的私有常量里，暂不迁移——本轮只保证**新键**只有一个事实源。</p>
 */
public final class SubTaskContextKeys {

    /**
     * 验收前置未满足的引用清单（{@code List<String>}，元素形如 {@code U1}）。
     *
     * <p>语义 = 「本子任务的某几条验收要求，依赖某个**至今仍是待确认缺口**的外部事实，
     * 因此此刻无法被满足」。<b>只标注，不改任何既有字段</b>——`acceptance` 文本、
     * `acceptanceKind`、`dependsOn` 全部保持原样，也不拒绝拆解（`TD-11` 决策 13）。</p>
     *
     * <p>写入：{@code PlannerDecomposeAsyncServiceImpl#buildDrafts}（拆解落库前）。
     * 读取：核验侧（`TD-12`，尚未接线）。<b>键缺失 = 无标注</b>，与「标注了 0 条」等价，
     * 两侧都必须按「无豁免」处理（fail-safe）。</p>
     */
    public static final String ACCEPTANCE_PRECONDITIONS = "acceptancePreconditions";

    /**
     * 门禁结论（{@code Map}：{@code findings / ruleVersion / checkedAt | clearedAt}，``）。
     *
     * <p>写入：{@code SubTaskGateFindingsStore}（**唯一写点**，拆解 / 展开 / 重生成 / 编辑 /
     * 确认五条路径共用）。<b>结构恒存在</b>：无问题写 {@code findings: []} + {@code clearedAt}，
     * 消费方只看 {@code findings} 是否为空即可判「当前是否带病」，不必猜键有没有。</p>
     *
     * <p>旧实现挂 {@code task.context.gateFindings}：任务级单值 ⇒ 无法按草案过滤、{@code draftRef}
     * 只能是 LLM 输出序号、清理只能整体清。改挂草案后三者同时消失。</p>
     */
    public static final String GATE_FINDINGS = "gateFindings";

    /**
     * 模型/用户**显式声明**的 {@code acceptanceKind} 原文（{@code String}，合法枚举才写）。
     *
     * <p><b>为什么必须单独存</b>：{@code acceptance_kind} 列经 {@code AcceptanceKind.parse} 落库，
     * 缺失/非法值统一回落 {@code AUTOMATED}——落库后**无法区分「显式声明 AUTOMATED」与
     * 「没声明」**。而 kind 判据对这两种情况处置不同：显式声明与文本写法不符是 `block`，
     * 未声明只是 `warn`（存量产物常态）。丢了这个区分，普通文本会被判成「显式 AUTOMATED」
     * 而大面积误拦（`LOG-20261024-040` 已踩过一次）。</p>
     */
    public static final String ACCEPTANCE_KIND_DECLARED = "acceptanceKindDeclared";

    /**
     * 执行期重构轮次（{@code Integer}，每次进入 {@code RESTRUCTURING} 递增，`ADR-008` §12.3）。
     *
     * <p>与 {@code reworkCount} 正交：后者是「被驳回次数」的事实记录，改需求不等于驳回被撤销，
     * 故进入重构<b>不清零</b>；本键只回答「这是第几次重构」，供归因与界面展示。</p>
     */
    public static final String RESTRUCTURE_ROUND = "restructureRound";

    /** 进入重构的时刻（ISO-8601 字符串）。 */
    public static final String RESTRUCTURE_ENTERED_AT = "restructureEnteredAt";

    /** 人工填写的重构原因（{@code String}，可空）。 */
    public static final String RESTRUCTURE_REASON = "restructureReason";

    /**
     * 整改前的字段快照列表（{@code List<Map>}，元素含 title / content / deliverable / acceptance /
     * constraints / editedAt / editorId）。
     *
     * <p>只在「执行期编辑」时追加，首元素即**进入重构前的原始要求**——{@code G5} 用它作为
     * 「下游当初依赖的产出基线」。不落这份快照，「为什么这次跑出来和上次不一样」永远查不清
     * （`ADR-008` §12.3）。上限 {@code RESTRUCTURE_HISTORY_LIMIT} 条，防 JSONB 无限膨胀。</p>
     */
    public static final String RESTRUCTURE_HISTORY = "restructureHistory";

    /** 整改通过后回派发的时刻（ISO-8601 字符串）。 */
    public static final String RESTRUCTURE_APPLIED_AT = "restructureAppliedAt";

    /**
     * {@code G5_RUNTIME_CONTRACT_BREAK} 结论（结构同 {@link #GATE_FINDINGS}：findings / ruleVersion / checkedAt）。
     *
     * <p>本轮只记**通过**的结论；不通过时整体回滚（用户调整要求后重试），
     * 不留半截结论——与草案编辑「结论与编辑同生共死」同口径。</p>
     */
    public static final String RESTRUCTURE_GATE_FINDINGS = "restructureGateFindings";

    /** {@link #RESTRUCTURE_HISTORY} 的条数上限：超出时保留首条（原始基线）与最近条目。 */
    public static final int RESTRUCTURE_HISTORY_LIMIT = 50;

    /**
     * 派单决策快照（{@code Map}：{@code attempt / path / decidedAt / candidateTotal /
     * truncated / candidates[]}，`MA-2`）。
     *
     * <p>语义 = 「最近一次派单当时，候选池里有谁、各自什么位次、中选的是谁、走的是哪条路径」。
     * 答案此前只存在于日志里，无法事后按子任务查。</p>
     *
     * <p><b>写入</b>：{@code SubTaskDispatchServiceImpl}（**唯一写点**，四条选人路径共用），
     * 按键合并（{@code updateContextInNewTx}）——<b>整段覆写会清掉 {@code gateFindings} /
     * {@code plannerAgentId} 等其它写方的键</b>。写入是 best-effort：失败只 {@code log.warn}，
     * 绝不让「记不下来」把派单打回。</p>
     *
     * <p><b>读取</b>：子任务详情接口（{@code getById} 已下发 {@code context}）；
     * <b>无前端展示</b>，本期是数据契约。若长期无消费方，须按判据 2 重新评估是否保留。</p>
     *
     * <p><b>覆盖式</b>：每次派单覆盖写一份，只有「最近一次」；要全量历史必须建表，属另一批。
     * <b>键缺失 = 从未走过选人</b>（如子任务还没派过、或派单前就被拦下），
     * 与「有快照但候选为空」刻意区分开——后者不写。</p>
     */
    public static final String DISPATCH_SNAPSHOT = "dispatchSnapshot";

    /**
     * 显式声明「本次无产物、无需闸门校验」（{@code Boolean} 或字符串 {@code "true"}，`MA-4`）。
     *
     * <p>语义 = 分析 / 决策类任务的「结论即交付物」，不产生文件 / 分片 / 文本产物，
     * 交付物登记闸（{@code DeliveryEvidenceEvaluator}）命中本键即豁免，不记
     * {@code delivery_gate_miss}。</p>
     *
     * <p><b>为什么放这里</b>：写入方 = 外部 Agent 提交时经 {@code submitResult} 在 context 带上，
     * 读取方 = {@code agent.command.DeliveryEvidenceEvaluator}（agent 域）。键名靠字符串约定，
     * 与 {@link #ACCEPTANCE_KIND_DECLARED} 同族，放 {@code common} 让两侧共享同一事实源。</p>
     *
     * <p><b>诚实边界</b>：声明式天然可造假（模型/用户自报），本期接受——与 {@code parts}
     * 声明式同源（蓝图 §4 `MA-4`④诚实边界 3），第三段如需更强证据再议。</p>
     */
    public static final String NO_DELIVERABLE = "noDeliverable";

    /**
     * 等待人工审批的挂起标记（{@code Map}：{@code approvalId / actionType / requestedAt}，`MA-9`）。
     *
     * <p>语义 = 「本子任务正在等同一个人点确认，这段时间没有进展<b>但不该判死</b>」。</p>
     *
     * <p><b>写入</b>：{@code ApprovalGateService}（`MA-9` 唯一写点，审批请求落库后同步写本键，
     * 答复 / 过期 / 拒绝时清除）。<b>读取</b>：{@code ExecutionCompensationTask}
     * （`MA-10` 改动 3 的豁免分支）与子任务详情接口。</p>
     *
     * <p><b>为什么用 context 而不建独立状态</b>：`MA-9` 明确「同 turn 阻塞、零状态迁移」——
     * 子任务仍在 {@code IN_PROGRESS}，不新增状态、不新增边。豁免判定只读本键的存在性，
     * 不引入对审批表的依赖（`MA-10` 不需要知道审批业务模型）。</p>
     *
     * <p><b>键缺失 = 未在等审批</b>（正常执行），与「审批已答完但没清干净」不同——
     * 后者是缺陷，由 `MA-9` 的清理逻辑保证。</p>
     */
    public static final String PENDING_APPROVAL = "pendingApproval";

    /**
     * 子任务级轮次预算提示（{@code Integer}，`MA-10` 改动 2）。
     *
     * <p>语义 = 「拆解阶段由 planner 按任务类型给出的执行轮次预算」。缺省（不存在 / 非正整数）
     * ⇒ 回落到接入类型分档默认（{@code internalMaxIterations} / {@code externalMaxIterations}），
     * 行为与改造前一致。</p>
     *
     * <p><b>为什么必须支持子任务级覆盖</b>：全局 20 轮是「文档写作 12~14 轮 + 沙箱数轮」的
     * 经验值，不是预算。文档写作 14 轮够用，代码重构 20 轮不够——同一个数字不可能对两类任务
     * 都合适（蓝图 §4 `MA-10` 改动 2）。</p>
     *
     * <p><b>为什么要上限钳制</b>：planner 给的数字可能离谱（模型输出 500），必须钳到
     * 一个有意义的区间，否则一次拆解就能把单任务成本放大 25 倍。钳制逻辑在
     * {@code RuntimeTurnExecutor#resolveMaxIterations}（唯一消费点）。</p>
     */
    public static final String MAX_ITERATIONS_HINT = "maxIterationsHint";

    /**
     * 本子任务**专属引用的会话附件 ID 清单**（{@code List<Long>}，元素为 {@code chat_attachment.id}，`G-042`）。
     *
     * <p>语义 = 「拆解时 Planner 判定这几份用户上传的附件与<b>本子任务</b>相关，执行期只需把这几份
     * 送给模型」。解决的是「多图会话下每个子任务都拿到全会话图片、模型把 A 图的需求落到 B 子任务上」。</p>
     *
     * <p><b>为什么存 {@code context} 而不建桥表 / 不写 {@code attachment} 双行</b>：{@code attachment}
     * 表的每一处消费（交付物闸 {@code DeliveryEvidenceEvaluator}、上游产出装载、契约回流、核验证据集、
     * MCP 读模型、「我的产物」）都以「这一行 = 某子任务<b>产出</b>的东西」为前提。把用户上传的需求图
     * 写成 {@code attachment} 行，会让这些判据把「输入」误认成「产出」——最直接的后果是
     * 交付物闸对所有带图子任务恒为「有产物」。{@code context} 是拆解期的自由 JSONB，天然属于
     * 「拆解判定」而非「产物登记」，零迁移、零新表、不影响任何既有判据。</p>
     *
     * <p><b>写入</b>：{@code PlannerDecomposeAsyncServiceImpl#buildDrafts}（把 LLM 输出的
     * 1-based 清单序号解析成附件 ID 后落库，越界 / 非法序号直接丢弃）。
     * <b>读取</b>：{@code ConversationMediaRefPortAdapter}（planner 域，随 {@code taskId}
     * 一起解析会话）。<b>agent 域不认识本键</b>——它只传 {@code subTaskId}，键的解析责任留在
     * 提供方，否则键改名会让执行链静默看不到图（判据 4）。</p>
     *
     * <p><b>键缺失 / 空数组 = 未声明</b> ⇒ 回落会话全量并**显式标注回落原因**；
     * 这与「声明了但全部无效」等价（同样回落），刻意不与「明确要 0 张」混为一谈——
     * 后者在本期没有表达方式，因为 Planner 不可能判定「这个子任务与任何附件都无关」比
     * 「我漏判了」更可信。</p>
     */
    public static final String MEDIA_REF_IDS = "mediaRefIds";

    private SubTaskContextKeys() {
    }
}
