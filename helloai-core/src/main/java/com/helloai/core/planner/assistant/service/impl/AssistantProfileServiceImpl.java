package com.helloai.core.planner.assistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.core.planner.assistant.entity.AssistantProfile;
import com.helloai.core.planner.mapper.AssistantProfileMapper;
import com.helloai.core.planner.assistant.service.AssistantProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AssistantProfileService} 实现（PA-3）。
 *
 * <p>选取优先序：用户级置顶 → 用户级任一 → 平台置顶 → 平台任一 → 默认兜底。
 * 取不到（表空 / 异常）一律 {@link AssistantProfileService#DEFAULT}，前端永远能渲染。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantProfileServiceImpl implements AssistantProfileService {

    private final AssistantProfileMapper assistantProfileMapper;

    @Override
    public AssistantSnapshot getActiveByUser(Long userId) {
        try {
            if (userId != null) {
                AssistantProfile userProfile = pick(userId);
                if (userProfile != null) {
                    return toSnapshot(userProfile);
                }
            }
            AssistantProfile platform = pick(null);
            if (platform != null) {
                return toSnapshot(platform);
            }
        } catch (Exception e) {
            log.warn("取助理身份失败，回落默认身份: userId={}, err={}", userId, e.getMessage());
        }
        return DEFAULT;
    }

    /** 针对给定 owner（null=平台预置）选一条：置顶优先，否则任一；无则 null。 */
    private AssistantProfile pick(Long ownerId) {
        LambdaQueryWrapper<AssistantProfile> base =
                new LambdaQueryWrapper<AssistantProfile>()
                        .eq(ownerId != null, AssistantProfile::getOwnerId, ownerId)
                        // ownerId 为 null 时用 IS NULL 匹配平台预置（eq(null) 会生成 owner_id=null，恒空）
                        .isNull(ownerId == null, AssistantProfile::getOwnerId)
                        .eq(AssistantProfile::getPinned, 1)
                        .last("LIMIT 1");
        AssistantProfile pinned = assistantProfileMapper.selectOne(base);
        if (pinned != null) {
            return pinned;
        }
        LambdaQueryWrapper<AssistantProfile> any =
                new LambdaQueryWrapper<AssistantProfile>()
                        .eq(ownerId != null, AssistantProfile::getOwnerId, ownerId)
                        .isNull(ownerId == null, AssistantProfile::getOwnerId)
                        .last("LIMIT 1");
        return assistantProfileMapper.selectOne(any);
    }

    private AssistantSnapshot toSnapshot(AssistantProfile p) {
        return new AssistantSnapshot(
                p.getName() != null && !p.getName().isBlank() ? p.getName() : DEFAULT.name(),
                p.getIdentity() != null && !p.getIdentity().isBlank() ? p.getIdentity() : DEFAULT.identity(),
                p.getModel() != null && !p.getModel().isBlank() ? p.getModel() : DEFAULT.model(),
                p.getSkillIds() != null ? new ArrayList<>(p.getSkillIds()) : DEFAULT.skillIds(),
                p.getGreeting() != null && !p.getGreeting().isBlank() ? p.getGreeting() : DEFAULT.greeting());
    }
}