package com.helloai.api.dto;

import lombok.Data;

@Data
public class SubTaskDTO {
    private Long id;
    private Long taskId;
    private Long moduleId;
    private String title;
    private String status;
    private Long assignedAgent;
    private String content;
    private Integer compositeScore;
    private String scoreGrade;
    private String createTime;
    private String updateTime;
}
