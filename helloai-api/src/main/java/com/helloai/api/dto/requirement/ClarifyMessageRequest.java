package com.helloai.api.dto.requirement;

import com.helloai.core.planner.RequirementClarifyService;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ClarifyMessageRequest {
    @NotBlank(message = "消息内容不能为空")
    private String message;

    /** 手动指定的 Planner Agent ID（仅新建会话时生效；空=系统自动选择） */
    private Long plannerAgentId;

    /** 结构化选项回答快照（V33，仅追加消息时生效；空=纯文本回答） */
    private List<RequirementClarifyService.ClarifySelection> selectedOptions;
}
