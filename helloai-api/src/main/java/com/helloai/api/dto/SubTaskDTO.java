package com.helloai.api.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

@Data
public class SubTaskDTO {
    private Long id;
    private Long taskId;
    private Long moduleId;
    private String title;
    private String status;
    /** Agent ID：命名不符 {@code *Id} 约定，须显式声明为字符串以免雪花 ID 丢精度。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long assignedAgent;
    private String content;
    private Integer compositeScore;
    private String scoreGrade;
    private String createTime;
    private String updateTime;
}
