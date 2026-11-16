package com.helloai.core.system.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NotificationMapping} 白名单注册表单测（MSG-1，设计 §3.5）。
 *
 * <p>锁定「未登记返回 null（默认不发）」「SILENT 显式登记」与首批 A1/A3/A5/B1/B2
 * 的类型/严重度/形态（设计 §3.5）。</p>
 */
@DisplayName("MSG-1 事件码映射白名单")
class NotificationMappingTest {

    @Test
    @DisplayName("未登记事件码返回 null（白名单默认不发）")
    void unregisteredResolvesToNull() {
        assertThat(NotificationMapping.resolve("agent_completed")).isNull();
        assertThat(NotificationMapping.resolve("not_a_real_code")).isNull();
        assertThat(NotificationMapping.resolve(null)).isNull();
    }

    @Test
    @DisplayName("过程心跳码显式登记为 SILENT")
    void processCodesAreSilent() {
        NotificationMapping.Entry entry = NotificationMapping.resolve("task_plan_llm_call_start");
        assertThat(entry).isNotNull();
        assertThat(entry.form()).isEqualTo(NotificationMapping.Form.SILENT);
    }

    @Test
    @DisplayName("首批 A1/A3/A5：ONCE + NORMAL，类型对齐设计 §3.5")
    void firstBatchAsyncCompletionOnceNormal() {
        assertEntry("task_plan_generated", NotificationType.PLAN_DRAFT_READY, Severity.NORMAL, NotificationMapping.Form.ONCE, 0);
        assertEntry("task_final_report_generated", NotificationType.FINAL_REPORT_READY, Severity.NORMAL, NotificationMapping.Form.ONCE, 0);
        assertEntry("sub_task_execute_submit", NotificationType.SUBTASK_SUBMITTED, Severity.NORMAL, NotificationMapping.Form.ONCE, 0);
    }

    @Test
    @DisplayName("首批 B1：死信/熔断 → SUBTASK_DEAD_LETTER，报告达上限 → FINAL_REPORT_REVIEW_STUCK，URGENT + ONCE")
    void firstBatchDeadLetterUrgentOnce() {
        assertEntry("sub_task_dead_letter", NotificationType.SUBTASK_DEAD_LETTER, Severity.URGENT, NotificationMapping.Form.ONCE, 0);
        assertEntry("sub_task_review_dead_letter", NotificationType.SUBTASK_DEAD_LETTER, Severity.URGENT, NotificationMapping.Form.ONCE, 0);
        assertEntry("task_final_report_max_review_reached", NotificationType.FINAL_REPORT_REVIEW_STUCK, Severity.URGENT, NotificationMapping.Form.ONCE, 0);
    }

    @Test
    @DisplayName("首批 B2：三无候选码 → SUBTASK_NO_CANDIDATE，HIGH + BUCKET(1h)")
    void firstBatchNoCandidateHighBucket() {
        assertEntry("sub_task_no_candidate", NotificationType.SUBTASK_NO_CANDIDATE, Severity.HIGH, NotificationMapping.Form.BUCKET, 1);
        assertEntry("sub_task_dispatch_no_alternative", NotificationType.SUBTASK_NO_CANDIDATE, Severity.HIGH, NotificationMapping.Form.BUCKET, 1);
        assertEntry("task_plan_skill_unmatched", NotificationType.SUBTASK_NO_CANDIDATE, Severity.HIGH, NotificationMapping.Form.BUCKET, 1);
    }

    private static void assertEntry(String code, NotificationType type, Severity severity,
                                    NotificationMapping.Form form, int bucketHours) {
        NotificationMapping.Entry entry = NotificationMapping.resolve(code);
        assertThat(entry).as("事件码 %s 应已登记", code).isNotNull();
        assertThat(entry.type()).isEqualTo(type);
        assertThat(entry.severity()).isEqualTo(severity);
        assertThat(entry.form()).isEqualTo(form);
        assertThat(entry.bucketHours()).isEqualTo(bucketHours);
    }
}