package com.helloai.api.dto.requirement;

import com.helloai.core.planner.assistant.service.AssistantProfileService.AssistantSnapshot;

import java.util.List;

/**
 * 对话页头部身份卡响应（PA-3）。
 */
public record AssistantProfileVO(String name, String identity, String model,
                                 List<Long> skillIds, String greeting) {

    public static AssistantProfileVO from(AssistantSnapshot s) {
        return new AssistantProfileVO(s.name(), s.identity(), s.model(),
                s.skillIds() == null ? List.of() : s.skillIds(), s.greeting());
    }
}