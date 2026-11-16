package com.helloai.core.system.notification;

/**
 * 人侧消息类型注册表（MSG-1，设计 §3.2）。
 *
 * <p><b>注册表即清单</b>：新增一类消息必须先在此登记，禁止 {@code type} 传裸字符串
 * （编译期挡住散落）。文案集中于此——标题、摘要模板、默认严重度、深链归属类型一处改，
 * 业务侧只上报事实（{@link NotificationEvent#getPayload()}），不拼句子、不拼 URL。</p>
 *
 * <p>摘要模板用 {@code {key}} 占位，由消息域从 payload 渲染（如 BUCKET 折叠的 {@code {count}}）；
 * 深链归属类型取事件源对象类型（{@code sourceType}），此处 refType 仅作例外通道兜底，
 * 路由由前端按 refType + refId 推导，后端不耦合 Vue 路由。</p>
 *
 * <p>首批 6 类被 {@link NotificationMapping} 激活（PLAN_DRAFT_READY / FINAL_REPORT_READY /
 * SUBTASK_SUBMITTED / SUBTASK_DEAD_LETTER / SUBTASK_NO_CANDIDATE / FINAL_REPORT_REVIEW_STUCK）；
 * 其余登记待 MSG-7 补映射。</p>
 */
public enum NotificationType {

    PLAN_DRAFT_READY("任务拆解草案已就绪", "拆解草案已生成，请前往审核。", Severity.NORMAL, "TASK"),
    PLAN_FAILED("任务拆解失败", "拆解未能完成，请查看失败原因后重试。", Severity.HIGH, "TASK"),
    PLAN_GATE_BLOCKED("拆解被契约门拦截", "拆解产出未通过运行期契约门，需人工处理。", Severity.HIGH, "TASK"),
    PLAN_LEASE_RECALLED("拆解超时已恢复", "拆解超租约被巡检恢复，请重新发起。", Severity.HIGH, "TASK"),
    DERIVED_PLAN_READY("派生拆解草案已就绪", "派生拆解草案已生成，请前往审核。", Severity.NORMAL, "TASK"),
    FINAL_REPORT_READY("整合报告已生成", "整合报告已生成，可查看。", Severity.NORMAL, "TASK"),
    FINAL_REPORT_FAILED("整合报告生成失败", "整合报告未能生成，请查看失败原因。", Severity.HIGH, "TASK"),
    FINAL_REPORT_REVIEW_STUCK("报告审查达上限，需人工处理", "整合报告多次审查未通过，已停止再审，请人工查看。", Severity.URGENT, "TASK"),
    TASK_DONE("任务已完成", "任务全部子任务已收尾。", Severity.NORMAL, "TASK"),
    SUBTASK_DEAD_LETTER("子任务进入死信，需人工处理", "子任务多次失败进入死信池，需人工决定通过或改派。", Severity.URGENT, "SUB_TASK"),
    SUBTASK_NO_CANDIDATE("暂无可用执行者", "近 1 小时内多次无可用执行者，需人工介入或调整承接配置。", Severity.HIGH, "SUB_TASK"),
    SUBTASK_BLOCKED("子任务受阻", "子任务执行受阻，需人工介入。", Severity.HIGH, "SUB_TASK"),
    RESTRUCTURE_STUCK("子任务重构卡住", "子任务卡在重构态，需人工介入。", Severity.HIGH, "SUB_TASK"),
    REVIEW_INCONCLUSIVE("核验无法定论，需人工", "核验无法给出确定结论，已转人工处理。", Severity.HIGH, "SUB_TASK"),
    CLARIFY_STALLED("澄清轮数用尽", "澄清轮数用尽仍未收敛，请补充需求描述。", Severity.NORMAL, "CONVERSATION"),
    EXTERNAL_AGENT_ONLINE("外部 Agent 已上线", "外部 Agent 已上线，可承接派发。", Severity.NORMAL, "AGENT"),
    SUBTASK_SUBMITTED("子任务产出已提交", "执行 Agent 已提交本轮产出。", Severity.NORMAL, "SUB_TASK"),
    ANNOUNCEMENT_PUBLISHED("平台公告已发布", "平台发布了新的置顶公告。", Severity.NORMAL, "RELEASE");

    private final String title;
    private final String summaryTemplate;
    private final Severity defaultSeverity;
    private final String refType;

    NotificationType(String title, String summaryTemplate, Severity defaultSeverity, String refType) {
        this.title = title;
        this.summaryTemplate = summaryTemplate;
        this.defaultSeverity = defaultSeverity;
        this.refType = refType;
    }

    public String getTitle() {
        return title;
    }

    public String getSummaryTemplate() {
        return summaryTemplate;
    }

    public Severity getDefaultSeverity() {
        return defaultSeverity;
    }

    public String getRefType() {
        return refType;
    }
}