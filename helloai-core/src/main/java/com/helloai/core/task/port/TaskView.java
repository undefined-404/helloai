package com.helloai.core.task.port;

import com.helloai.common.constant.FinalReportStatus;
import com.helloai.common.constant.TaskStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 顶层任务只读快照（task 域对外契约，RM5 批 4）。
 *
 * <p><b>归属判据（CODE_STYLE §7.2 情形②）</b>：消费方 review 域（下标 1）【高于】提供方
 * task 域（下标 2），故读契约落【提供方】{@code task.port}；消费方 {@code review → task.port}
 * 属顺向合法，从此不再 import {@code task.entity.Task}。</p>
 *
 * <p><b>字段只纳入消费方实际读取项</b>（review 域实测）：{@code id} / {@code title} /
 * {@code description} / {@code finalReport} / {@code finalReportStatus} /
 * {@code finalReportAgentId} / {@code finalReportTime} / {@code agentPolicy}。</p>
 */
public record TaskView(
        Long id,
        String title,
        String description,
        String finalReport,
        FinalReportStatus finalReportStatus,
        Long finalReportAgentId,
        OffsetDateTime finalReportTime,
        Map<String, Object> agentPolicy,
        TaskStatus status,
        Integer slaMinutes,
        String priority,
        Map<String, Object> context,
        List<String> requiredSkills,
        OffsetDateTime createTime,
        OffsetDateTime updateTime
) {

    /** 兼容构造：不含 planner 侧扩展字段（review 场景）。 */
    public TaskView(Long id, String title, String description, String finalReport,
                    FinalReportStatus finalReportStatus, Long finalReportAgentId,
                    OffsetDateTime finalReportTime, Map<String, Object> agentPolicy) {
        this(id, title, description, finalReport, finalReportStatus, finalReportAgentId,
                finalReportTime, agentPolicy, null, null, null, null, null, null, null);
    }
}
