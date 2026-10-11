package com.helloai.common.constant;

/**
 * 时间线展示事件码（REF-6.8 服务端单一来源）。
 *
 * <p>承载前端事件时间线（sequenceFlow / eventMeta）渲染所需的 <b>代码 → 人话 label/desc</b>
 * 字典，作为<b>唯一事实源</b>：前端不再保留硬编码副本，经 {@code /api/events/catalog}
 * 拉取（与技能目录 REF-1.2c 同一范式）。</p>
 *
 * <p><b>与 {@link AgentEventType} 的区别</b>：{@link AgentEventType} 是 agent_event 的粗粒度
 * 分类（Run/Turn/Step/Review，16 条）；本类是 task_timeline/对话消息展示用的细粒度事件码</p>
 *
 * <p><b>收录口径</b>：仅收<b>用户可见</b>的展示码（code + label 非空）。内部码（无前端
 * 展示）不在此枚举，另见写点常量。新增展示码 = 在此补一条 + 前端经 catalog 自动可见。</p>
 */
public enum TimelineEventCode {

    AGENT_COMPLETED("agent_completed", "Agent 完成", "执行 Agent 完成本轮处理并提交"),
    AGENT_EXTERNAL_FALLBACK_TRIGGERED("agent_external_fallback_triggered", "外部执行兜底触发", "外部 Agent 无在跑子任务时触发阈值回退兜底（仅写冷却标记）"),
    AGENT_OFFLINE("agent_offline", "Agent 离线", "Agent 判为离线（历史事件；payload 带离线原因与触发上下文）"),
    AGENT_SLEEP("agent_sleep", "Agent 休眠", "Agent 进入休眠，不再接收派单（次日打卡后恢复在岗）"),
    AGENT_STARTED("agent_started", "Agent 开始", "执行 Agent 开始处理子任务"),
    AGENT_WAKE("agent_wake", "Agent 唤醒", "Agent 从休眠被唤醒（转离线，待再次打卡恢复在岗）"),
    ATTACHMENT_DELETED("attachment_deleted", "删除产出", "执行 Agent 删除了此前上传的产出附件（止损即停、零残留）"),
    CONTEXT_BUILT("context_built", "上下文装配", "执行上下文已装配"),
    ENVIRONMENT_RESOLVED("environment_resolved", "环境解析", "已确定执行环境"),
    REQUIREMENT_DESCRIPTION_SECTION_MISSING("requirement_description_section_missing", "需求小节缺失", "澄清会话的需求描述缺少必需小节（会话级事件，payload 带 conversationId）"),
    REVIEW_APPROVED("review_approved", "核验通过", "核验通过，子任务完成"),
    REVIEW_REJECTED("review_rejected", "核验驳回", "核验未通过，需要返工"),
    REVIEW_STARTED("review_started", "核验开始", "系统开始核验产出"),
    REWORK_STARTED("rework_started", "返工开始", "子任务进入返工流程"),
    RUN_COMPLETED("run_completed", "Run 完成", "一次需求完整执行完成"),
    RUN_CREATED("run_created", "Run 创建", "一次需求完整执行启动"),
    SKILL_RESOLVED("skill_resolved", "技能解析", "已解析执行所需技能"),
    SUB_TASK_ARTIFACT_MATERIALIZED("sub_task_artifact_materialized", "产出物化", "执行产出已物化为附件，可在产出附件中下载"),
    SUB_TASK_AUTO_EXECUTE_DISPATCH("sub_task_auto_execute_dispatch", "自动派单", "系统自动把子任务分派给执行 Agent"),
    SUB_TASK_AUTO_EXECUTE_DISPATCH_ENTER("sub_task_auto_execute_dispatch_enter", "进入派单", "系统进入自动派单流程"),
    SUB_TASK_AUTO_EXECUTE_DISPATCH_FAIL("sub_task_auto_execute_dispatch_fail", "派单失败", "暂时没有空闲的执行 Agent，稍后重试"),
    SUB_TASK_AUTO_EXECUTE_DISPATCH_OK("sub_task_auto_execute_dispatch_ok", "派单成功", "已成功把子任务交给执行 Agent"),
    SUB_TASK_AUTO_REVIEW_PASSED("sub_task_auto_review_passed", "核验通过", "自动核验通过，子任务达标"),
    SUB_TASK_AUTO_REVIEW_REJECTED("sub_task_auto_review_rejected", "核验驳回", "自动核验未通过，需要返工"),
    SUB_TASK_AUTO_REVIEW_SKIP_MAX_REWORK("sub_task_auto_review_skip_max_rework", "跳过核验", "已达最大返工次数，跳过核验"),
    SUB_TASK_AUTO_REVIEW_SKIP_REPEATED_FAILURE("sub_task_auto_review_skip_repeated_failure", "核验重复失败短路", "自动核验连续失败且判定为结构性失败，短路转死信不再耗轮次"),
    SUB_TASK_AUTO_REVIEW_UNPARSEABLE("sub_task_auto_review_unparseable", "核验异常", "核验结果无法解析"),
    SUB_TASK_CLAIM_REJECTED("sub_task_claim_rejected", "认领被拒", "该 Agent 不在任务执行者白名单内或缺少必需技能，认领未通过"),
    SUB_TASK_CONTRACT_BACKFILLED("sub_task_contract_backfilled", "契约内容回填", "完成时回填子任务契约内容（原 content 为空，从产出补齐）"),
    SUB_TASK_DEAD_LETTER("sub_task_dead_letter", "进入死信", "多次失败，子任务进入死信池"),
    SUB_TASK_DEAD_LETTER_MANUAL_ASSIGN("sub_task_dead_letter_manual_assign", "死信重派", "人工把死信子任务重新指派给 Agent"),
    SUB_TASK_DEPS_CONTEXT_LOADED("sub_task_deps_context_loaded", "参考上游产出", "执行 Agent 已读取前置子任务的交付结果，作为本次执行的参考"),
    SUB_TASK_DISPATCH_DEFERRED("sub_task_dispatch_deferred", "派发推迟", "下游节点分发失败，保持 PENDING 等待兜底重派"),
    SUB_TASK_DISPATCH_FALLBACK("sub_task_dispatch_fallback", "熔断降级换人", "原执行 Agent 不可用（熔断 / 离线），系统已把该子任务改派给替代 Agent 执行"),
    SUB_TASK_DISPATCH_NO_ALTERNATIVE("sub_task_dispatch_no_alternative", "无可用替代执行者", "原执行 Agent 不可用且当前无满足任务约束的候选执行者，改派未发生，需稍后重试或人工介入"),
    SUB_TASK_DISPATCH_PREPARE("sub_task_dispatch_prepare", "准备分发", "系统开始为该子任务寻找合适的执行 Agent"),
    SUB_TASK_DISPATCH_SKIP_DEPENDENCY("sub_task_dispatch_skip_dependency", "跳过派发（依赖未就绪）", "前置依赖未就绪，本轮不派发"),
    SUB_TASK_DISPATCH_SKIP_NO_CAPABILITY("sub_task_dispatch_skip_no_capability", "跳过派发（无本机能力）", "候选 Agent 无本机执行能力，不派发"),
    SUB_TASK_DUAL_REVIEW_CONSENTED("sub_task_dual_review_consented", "双审共识", "两位评审结论一致，按共识策略落地"),
    SUB_TASK_DUAL_REVIEW_DEGRADED("sub_task_dual_review_degraded", "双审降级", "双审候选不足，降级为单审"),
    SUB_TASK_DUAL_REVIEW_INCOMPLETE("sub_task_dual_review_incomplete", "双审缺失", "双审核验不完整，子任务停留 REVIEW 等人工"),
    SUB_TASK_EXECUTE("sub_task_execute", "执行产出", "执行 Agent 产出了内容"),
    SUB_TASK_EXECUTE_BEFORE_PLATFORM("sub_task_execute_before_platform", "执行前准备", "执行前的平台准备工作"),
    SUB_TASK_EXECUTE_ENTER("sub_task_execute_enter", "开始执行", "执行 Agent 开始处理子任务"),
    SUB_TASK_EXECUTE_FAILED("sub_task_execute_failed", "执行失败", "子任务执行失败"),
    SUB_TASK_EXECUTE_RESULT_DISCARDED("sub_task_execute_result_discarded", "结果丢弃", "本次执行结果被丢弃（可能已过期）"),
    SUB_TASK_EXECUTE_START("sub_task_execute_start", "开始执行", "执行 Agent 开始处理子任务"),
    SUB_TASK_EXECUTE_SUBMIT("sub_task_execute_submit", "提交产出", "执行 Agent 提交了本次产出"),
    SUB_TASK_EXECUTE_SUCCESS("sub_task_execute_success", "执行成功", "子任务执行成功"),
    SUB_TASK_EXECUTE_THINKING("sub_task_execute_thinking", "思考过程", "执行 Agent 的思考 / 推理过程"),
    SUB_TASK_EXECUTION_COMMAND_CONSUME("sub_task_execution_command_consume", "领取指令", "执行 Agent 已领取指令，准备开始"),
    SUB_TASK_EXECUTION_COMMAND_CONSUME_SKIPPED("sub_task_execution_command_consume_skipped", "跳过指令", "该执行指令被跳过（可能已被处理）"),
    SUB_TASK_EXECUTION_COMMAND_CREATED("sub_task_execution_command_created", "生成执行指令", "系统已生成执行指令，等待 Agent 领取"),
    SUB_TASK_EXECUTION_COMMAND_POLL_RECOVERY("sub_task_execution_command_poll_recovery", "指令恢复", "系统巡检恢复了一条遗漏的执行指令"),
    SUB_TASK_EXECUTION_TIMEOUT_REASSIGN("sub_task_execution_timeout_reassign", "执行超时改派", "执行超过时限，系统判定超时并转入失败回退链重新分发"),
    SUB_TASK_EXECUTOR_DONE_ISSUES("sub_task_executor_done_issues", "执行完成问题回填", "执行者完成时上报的问题回填到子任务（payload.state 区分成功 / 跳过 / 失败）"),
    SUB_TASK_FALLBACK_SKIP_NEED_HUMAN("sub_task_fallback_skip_need_human", "放弃降级（需人工）", "熔断降级的替代 Agent 也无本机执行能力，标记人工介入"),
    SUB_TASK_FALLBACK_SKIP_POLICY("sub_task_fallback_skip_policy", "放弃降级（策略）", "按任务约束策略不执行熔断降级"),
    SUB_TASK_LEASE_RECLAIMED("sub_task_lease_reclaimed", "租约回收", "在飞租约过期被回收（仅审计用途，不阻断回收主链路）"),
    SUB_TASK_LLM_CALL_END("sub_task_llm_call_end", "大模型返回", "大模型已返回生成结果"),
    SUB_TASK_LLM_CALL_FAILED("sub_task_llm_call_failed", "大模型失败", "调用大模型失败（超时或网络异常）"),
    SUB_TASK_LLM_CALL_START("sub_task_llm_call_start", "调用大模型", "执行 Agent 开始请求大模型生成内容"),
    SUB_TASK_MANUAL_INTERVENTION_REQUIRED("sub_task_manual_intervention_required", "人工介入", "系统判定该子任务需要人工处置（改派/人工通过/人工驳回）"),
    SUB_TASK_MANUAL_REVIEW_PASSED("sub_task_manual_review_passed", "人工验收通过", "管理员人工审定通过，子任务完成（LOG-20260903-005 新增，与自动核验对称）"),
    SUB_TASK_MANUAL_REVIEW_REJECTED("sub_task_manual_review_rejected", "人工驳回", "管理员人工驳回返工/改派（LOG-20260903-005 新增，与自动核验对称）"),
    SUB_TASK_MANUAL_REWORK_RESET("sub_task_manual_rework_reset", "人工改派", "人工驳回并改派执行者，同时重置返工计数"),
    SUB_TASK_NO_CANDIDATE("sub_task_no_candidate", "暂无可用执行者", "当前没有空闲或可用的候选执行 Agent，系统将自动重试派发"),
    SUB_TASK_OFFLINE_REASSIGN("sub_task_offline_reassign", "离线改派", "分配后 Agent 心跳丢失（离线），系统自动改派其他 Agent"),
    SUB_TASK_RECHECK_CONSISTENT("sub_task_recheck_consistent", "抽检一致", "抽检复审与原判定一致"),
    SUB_TASK_RECHECK_DISCREPANCY("sub_task_recheck_discrepancy", "抽检分歧", "抽检复审与原判定不一致，仅度量不改状态"),
    SUB_TASK_REDISPATCH_SKIPPED("sub_task_redispatch_skipped", "跳过重派", "闸门判定不满足重派条件，本轮跳过（不空烧重派预算）"),
    SUB_TASK_REPORT_BLOCKED("sub_task_report_blocked", "执行受阻", "子任务被标记为阻塞，需要人工介入"),
    SUB_TASK_REPORT_BLOCKED_SKIPPED("sub_task_report_blocked_skipped", "跳过阻塞上报", "重复或无效的阻塞上报被忽略"),
    SUB_TASK_REVIEW_DEAD_LETTER("sub_task_review_dead_letter", "核验熔断", "核验返工超过上限，子任务进入死信池，等待人工决定通过/改派"),
    SUB_TASK_REVIEW_SKIP_NO_CAPABILITY("sub_task_review_skip_no_capability", "核验跳过（无本机能力）", "执行密集任务由无本机能力 Agent 提交，自动核验跳过"),
    SUB_TASK_REVIEW_SKIP_NO_EVIDENCE("sub_task_review_skip_no_evidence", "核验跳过（无证据）", "无产出证据支撑，自动核验跳过"),
    SUB_TASK_REVIEWER_DISAGREEMENT("sub_task_reviewer_disagreement", "双审分歧", "两位评审结论不一致，转人工介入"),
    SUB_TASK_SESSION_INTERRUPTED("sub_task_session_interrupted", "会话中断", "执行会话被中断（payload 保留上下文摘要）"),
    SUB_TASK_SPEC_CONTEXT_LOADED("sub_task_spec_context_loaded", "装配任务上下文", "执行 Agent 已装配任务全局上下文与直接前置产出，作为本次执行的参考"),
    SUB_TASK_UNCLAIMED_TIMEOUT_REASSIGN("sub_task_unclaimed_timeout_reassign", "超时未领取改派", "分配后 Agent 超时未领取，系统自动改派其他 Agent"),
    SUBTASK_DUAL_REVIEW_PROMPT("subtask_dual_review_prompt", "双审请求", "双审发起对产出的核验"),
    SUBTASK_DUAL_REVIEW_RESULT("subtask_dual_review_result", "双审共识", "双审按共识策略落定的结论"),
    SUBTASK_DUAL_REVIEW_THINKING("subtask_dual_review_thinking", "双审思考", "双审的分析过程"),
    SUBTASK_DUAL_REVIEW_VERDICT("subtask_dual_review_verdict", "双审结论", "双审给出的核验结论"),
    SUBTASK_RECHECK_PROMPT("subtask_recheck_prompt", "抽检请求", "抽检复审发起的核验"),
    SUBTASK_RECHECK_RESULT("subtask_recheck_result", "抽检结论", "抽检复审结论（只度量不改状态）"),
    SUBTASK_RECHECK_THINKING("subtask_recheck_thinking", "抽检思考", "抽检复审的分析过程"),
    SUBTASK_RECHECK_VERDICT("subtask_recheck_verdict", "抽检审查", "抽检复审给出的核验结论"),
    SUBTASK_REVIEW_PROMPT("subtask_review_prompt", "核验请求", "发起对产出的核验"),
    SUBTASK_REVIEW_SKIP_DISAGREEMENT("subtask_review_skip_disagreement", "跳过核验·双审分歧", "双审结论不一致，停留 REVIEW 等人工裁决"),
    SUBTASK_REVIEW_SKIP_MAX_REWORK("subtask_review_skip_max_rework", "跳过核验·返工超限", "返工次数达上限，跳过自动核验并转入死信等待人工"),
    SUBTASK_REVIEW_SKIP_NO_CAPABILITY("subtask_review_skip_no_capability", "跳过核验·无本机能力", "执行密集任务由无本机执行能力的 Agent 提交，跳过自动核验转人工"),
    SUBTASK_REVIEW_SKIP_NO_EVIDENCE("subtask_review_skip_no_evidence", "跳过核验·无产出证据", "无产出本体（正文与附件皆空），跳过自动核验转人工"),
    SUBTASK_REVIEW_SKIP_REPEATED_FAILURE("subtask_review_skip_repeated_failure", "跳过核验·重复失败", "判定为结构化重复失败，熔断转死信等人工"),
    SUBTASK_REVIEW_THINKING("subtask_review_thinking", "核验思考", "核验的分析过程"),
    SUBTASK_REVIEW_VERDICT("subtask_review_verdict", "核验结论", "核验给出的结论"),
    TASK_ASSIGNED("task_assigned", "任务分配", "子任务已分配给执行 Agent"),
    TASK_AUTO_COMPLETED("task_auto_completed", "任务完成", "所有子任务完成，主任务自动收尾"),
    TASK_CREATED("task_created", "任务创建", "主任务已创建"),
    TASK_CREATED_FROM_CLARIFY("task_created_from_clarify", "澄清完成建单", "澄清会话定稿后创建任务"),
    TASK_FINAL_REPORT_FAILED("task_final_report_failed", "报告失败", "整合报告生成失败（分章降级后仍失败）"),
    TASK_FINAL_REPORT_GENERATED("task_final_report_generated", "报告已生成", "整合报告生成完成并写回"),
    TASK_FINAL_REPORT_LLM_CALL_START("task_final_report_llm_call_start", "报告调用大模型", "开始调用大模型生成报告章节"),
    TASK_FINAL_REPORT_MAX_REVIEW_REACHED("task_final_report_max_review_reached", "报告审查达上限", "报告审查轮次达上限，停止再审"),
    TASK_FINAL_REPORT_OUTLINE_FAILED("task_final_report_outline_failed", "报告大纲失败", "报告大纲生成或解析失败"),
    TASK_FINAL_REPORT_OUTLINE_ORDER_SUSPECT("task_final_report_outline_order_suspect", "报告大纲顺序可疑", "大纲章节顺序异常，疑似顺序错乱"),
    TASK_FINAL_REPORT_OUTLINE_READY("task_final_report_outline_ready", "报告大纲就绪", "大纲生成完成，进入分章写作"),
    TASK_FINAL_REPORT_REVIEW_DISCARDED_STALE("task_final_report_review_discarded_stale", "报告审查丢弃（陈旧）", "审查前发现已被重新生成 / 回滚接管，旧链审查丢弃"),
    TASK_FINAL_REPORT_REVIEW_FAILED("task_final_report_review_failed", "报告审查失败", "报告审查调用失败"),
    TASK_FINAL_REPORT_REVIEW_ORPHAN_CONVERGED("task_final_report_review_orphan_converged", "报告审查孤儿收敛", "孤儿巡检把长时间停留审查中的报告收敛为完成"),
    TASK_FINAL_REPORT_REVIEW_PASSED("task_final_report_review_passed", "报告审查通过", "报告审查通过"),
    TASK_FINAL_REPORT_REVIEW_REJECTED("task_final_report_review_rejected", "报告审查驳回", "报告审查驳回，需返工"),
    TASK_FINAL_REPORT_REVIEW_SKIPPED("task_final_report_review_skipped", "报告审查跳过", "报告审查跳过并收敛为完成（记录原因）"),
    TASK_FINAL_REPORT_REVIEW_UNPARSEABLE("task_final_report_review_unparseable", "报告审查不可解析", "审查结论无法解析，按证据不足处理"),
    TASK_FINAL_REPORT_REVIEW_WARNED("task_final_report_review_warned", "报告审查告警", "机械软违规（跨章逐字重复 / 覆盖追溯表缺失），交审查重点关注"),
    TASK_FINAL_REPORT_REWORK_DISCARDED_STALE("task_final_report_rework_discarded_stale", "报告返工丢弃（陈旧）", "返工基于旧链已无意义，丢弃不重写不收敛"),
    TASK_FINAL_REPORT_ROLLED_BACK("task_final_report_rolled_back", "报告已回滚", "报告回滚到上一版（当前版 ↔ 上一版互换）"),
    TASK_ITERATION_BACKFILL_FAILED("task_iteration_backfill_failed", "轮次回填失败", "任务轮次回填失败（不影响报告生成）"),
    TASK_PLAN_ASYNC_SUBMITTED("task_plan_async_submitted", "拆解已异步提交", "拆解已提交异步执行，HTTP 线程返回，进度查草案"),
    TASK_PLAN_CONFIRMED("task_plan_confirmed", "确认拆解", "拆解草案已确认"),
    TASK_PLAN_CONSTRAINTS_MISSING("task_plan_constraints_missing", "拆解约束缺失", "COARSE 粒度子项缺 constraints（记审计，不阻断落库）"),
    TASK_PLAN_DRAFT_FIELD_MISSING("task_plan_draft_field_missing", "拆解草案缺字段", "草案子项缺必需字段（审计先于抛出，可回溯字段与原始输出）"),
    TASK_PLAN_FAILED("task_plan_failed", "拆解失败", "任务拆解失败"),
    TASK_PLAN_GENERATED("task_plan_generated", "生成拆解", "已生成任务拆解草案"),
    TASK_PLAN_LLM_CALL_END("task_plan_llm_call_end", "拆解大模型返回", "拆解调用大模型结束（payload 含耗时与 token 用量）"),
    TASK_PLAN_LLM_CALL_START("task_plan_llm_call_start", "拆解调模型", "开始请求大模型进行任务拆解"),
    TASK_PLAN_REJECTED("task_plan_rejected", "驳回拆解", "拆解草案被驳回"),
    TASK_PLAN_SKILL_FILTERED("task_plan_skill_filtered", "拆解技能被过滤", "草案中的技能不在已知目录，过滤后不落库"),
    TASK_PLAN_TIMEOUT_RECOVERED("task_plan_timeout_recovered", "拆解超时恢复", "拆解超时由定时任务恢复（重置为可重试）"),
    TASK_PLAN_UNCERTAINTY_DEGRADED("task_plan_uncertainty_degraded", "不确定性降级", "不确定性条目的类型不在白名单内，降级处理"),
    TASK_PLAN_UNCERTAINTY_MISSING("task_plan_uncertainty_missing", "不确定性缺失", "草案未申报待确认的不确定性（提示可能漏报）"),
    TOOL_CALL_COMPLETED("tool_call_completed", "工具返回", "工具调用已返回结果"),
    TOOL_CALL_STARTED("tool_call_started", "工具调用", "Agent 开始调用工具"),
    TOOL_RESOLVED("tool_resolved", "工具解析", "已解析启用的工具清单"),
    REWORK_LIMIT("rework_limit", "核验返工达上限"),
    REVIEW_SKIP_EXECUTION_DENSE_NO_CAPABILITY("review_skip_execution_dense_no_capability", "提交者无本机执行能力"),
    REVIEW_SKIP_NO_EVIDENCE("review_skip_no_evidence", "交付物无物化证据"),
    FALLBACK_SKIP_POLICY("fallback_skip_policy", "任务级策略禁止自动回退"),
    FALLBACK_SKIP_POLICY_RESTRICTED("fallback_skip_policy_restricted", "回退目标不在白名单"),
    FALLBACK_SKIP_EXECUTION_DENSE("fallback_skip_execution_dense", "执行密集不可回退无能力Agent"),
    DISPATCH_SKIP_EXECUTION_DENSE("dispatch_skip_execution_dense", "执行密集不可分配无能力Agent"),
    ;

    private final String code;
    private final String label;
    private final String desc;

    TimelineEventCode(String code, String label) {
        this(code, label, "");
    }

    TimelineEventCode(String code, String label, String desc) {
        this.code = code;
        this.label = label;
        this.desc = desc == null ? "" : desc;
    }

    /** 数据库存储值（snake_case）。 */
    public String code() { return code; }

    /** 人话短标签（非空 = 用户可见，可下发目录）。 */
    public String label() { return label; }

    /** 详细说明（可空）。 */
    public String desc() { return desc; }

    /** 反向查：code → 枚举；未知返回 null。 */
    public static TimelineEventCode of(String code) {
        if (code == null) { return null; }
        for (TimelineEventCode c : values()) {
            if (c.code.equals(code)) { return c; }
        }
        return null;
    }
}
