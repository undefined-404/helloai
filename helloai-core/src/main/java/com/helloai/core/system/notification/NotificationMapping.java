package com.helloai.core.system.notification;

import java.util.Map;

/**
 * 事件码 → 消息映射注册表（MSG-1，设计 §3.5）。
 *
 * <p><b>白名单，不是黑名单</b>：时间线有 167 条码，未登记的事件码一律不发消息
 * （{@link #resolve} 返回 {@code null}），否则等于把审计流水复制一遍到用户眼前。</p>
 *
 * <p>每行声明三形态 {@link Form}：{@code ONCE} 逐条发 / {@code BUCKET} 按窗口折叠累加计数 /
 * {@code SILENT} 显式登记为不发（防止后人顺手加）。默认 {@code ONCE}，循环重试类显式标 {@code BUCKET}。</p>
 *
 * <p>本类只承载「事件码 → 类型/严重度/形态」的静态映射；dedupe_key 拼装与窗口计算在
 * {@link NotificationService#publish} 内完成（依赖运行期时钟与事件实例）。首批 A1/A3/A5/B1/B2
 * 已登记（MSG-4）；其余 A2/A4/B3~B7/C1~C5 随 MSG-7 补齐。</p>
 */
public final class NotificationMapping {

    /** 消息形态：逐条 / 窗口折叠 / 显式不发。 */
    public enum Form {
        ONCE,
        BUCKET,
        SILENT
    }

    /**
     * 映射条目。{@code SILENT} 时 {@code type}/{@code severity} 无意义（不生成）。
     *
     * @param bucketHours BUCKET 折叠窗口（小时）；非 BUCKET 形态为 0
     */
    public record Entry(NotificationType type, Severity severity, Form form, int bucketHours) {
    }

    private static final Entry SILENT = new Entry(null, null, Form.SILENT, 0);

    private static final Map<String, Entry> REGISTRY = Map.ofEntries(
            // ── SILENT：过程心跳 / 单条草案操作 / 门禁告警，用户看了不能做任何事（设计 §3.5）──
            Map.entry("sub_task_dispatch_prepare", SILENT),
            Map.entry("sub_task_dispatch_skip_dependency", SILENT),
            Map.entry("sub_task_dispatch_skip_no_capability", SILENT),
            Map.entry("sub_task_llm_call_start", SILENT),
            Map.entry("sub_task_llm_call_end", SILENT),
            Map.entry("task_plan_llm_call_start", SILENT),
            Map.entry("task_plan_llm_call_end", SILENT),
            Map.entry("task_final_report_llm_call_start", SILENT),
            Map.entry("task_plan_draft_regenerated", SILENT),
            Map.entry("task_plan_gate_warned", SILENT),

            // ── A 类：异步长任务完成，用户只需知道（设计 §3.5，首批 ONCE）──
            Map.entry("task_plan_generated", new Entry(NotificationType.PLAN_DRAFT_READY, Severity.NORMAL, Form.ONCE, 0)),
            Map.entry("task_final_report_generated", new Entry(NotificationType.FINAL_REPORT_READY, Severity.NORMAL, Form.ONCE, 0)),
            // A5：内部/外部 Agent 提交共用收口，主通道自动覆盖（设计 §3.5 A5）
            Map.entry("sub_task_execute_submit", new Entry(NotificationType.SUBTASK_SUBMITTED, Severity.NORMAL, Form.ONCE, 0)),

            // ── B1：必须人拍板的死信/核验熔断/报告达上限，URGENT 逐条（设计 §3.5 B1）──
            // 报告达上限是 task 级事实（无子任务进死信），单列 TASK 型，不复用 SUBTASK_DEAD_LETTER
            Map.entry("sub_task_dead_letter", new Entry(NotificationType.SUBTASK_DEAD_LETTER, Severity.URGENT, Form.ONCE, 0)),
            Map.entry("sub_task_review_dead_letter", new Entry(NotificationType.SUBTASK_DEAD_LETTER, Severity.URGENT, Form.ONCE, 0)),
            Map.entry("task_final_report_max_review_reached", new Entry(NotificationType.FINAL_REPORT_REVIEW_STUCK, Severity.URGENT, Form.ONCE, 0)),

            // ── B2：无候选/无替代/技能无承接者，HIGH 按 1h 窗口折叠累加，避免重试刷屏（设计 §3.5 B2）──
            Map.entry("sub_task_no_candidate", new Entry(NotificationType.SUBTASK_NO_CANDIDATE, Severity.HIGH, Form.BUCKET, 1)),
            Map.entry("sub_task_dispatch_no_alternative", new Entry(NotificationType.SUBTASK_NO_CANDIDATE, Severity.HIGH, Form.BUCKET, 1)),
            Map.entry("task_plan_skill_unmatched", new Entry(NotificationType.SUBTASK_NO_CANDIDATE, Severity.HIGH, Form.BUCKET, 1))
    );

    private NotificationMapping() {
    }

    /** 按事件码解析映射；未登记返回 {@code null}（白名单默认不发）。 */
    public static Entry resolve(String timelineEventCode) {
        if (timelineEventCode == null) {
            return null;
        }
        return REGISTRY.get(timelineEventCode);
    }
}