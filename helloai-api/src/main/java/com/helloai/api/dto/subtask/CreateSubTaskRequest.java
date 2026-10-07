package com.helloai.api.dto.subtask;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateSubTaskRequest {
    @NotNull(message = "任务ID不能为空")
    private Long taskId;
    private Long moduleId;
    @NotBlank(message = "子任务名称不能为空")
    private String title;
    private String description;
    private String deliverable;
    private String acceptance;
    private String priority;
    /** Agent ID：命名不符 {@code *Id} 约定，须显式声明为字符串以免雪花 ID 丢精度。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long assignedAgent;
}
