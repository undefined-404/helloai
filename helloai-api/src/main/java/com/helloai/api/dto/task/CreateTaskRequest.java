package com.helloai.api.dto.task;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CreateTaskRequest {
    @NotBlank(message = "任务名称不能为空")
    private String title;
    private String description;

    /** 任务级 SLA 分钟数（A0-7 新增，V48；可空，null=无时限；confirmPlan 时下发子任务 deadline）。 */
    private Integer slaMinutes;

    /**
     * 任务级 Agent 指定策略（V47，A1 透传；可空，null=不设置）。
     *
     * <p>键结构见 {@code TaskAgentPolicy}：plannerAgentId / executorAgentIds[] /
     * reviewerAgentId / fallbackPolicy / difficulty；编辑时传空 Map 表示清空。</p>
     */
    private Map<String, Object> agentPolicy;

    /**
     * 任务要求的能力列表（V47，A1 透传；可空，null=不设置；编辑时空列表表示清空）。
     *
     * <p>非空时执行者必须全部具备（AND 语义）。</p>
     */
    private List<String> requiredSkills;
}
