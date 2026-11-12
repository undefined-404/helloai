package com.helloai.core.planner.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.mapper.RequirementConversationMapper;
import com.helloai.core.planner.service.RequirementClarifyService;
import com.helloai.core.planner.service.RequirementConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 需求澄清会话薄 CRUD 服务实现。
 *
 * <p>业务编排（LLM 调用、终稿建任务）统一收口在
 * {@link com.helloai.core.planner.service.RequirementClarifyService}，本实现只提供数据访问。</p>
 */
@Slf4j
@Service
public class RequirementConversationServiceImpl
        extends ServiceImpl<RequirementConversationMapper, RequirementConversation>
        implements RequirementConversationService {

    @Override
    public RequirementConversation findActiveBySourceSubTask(Long sourceSubTaskId) {
        if (sourceSubTaskId == null) {
            return null;
        }
        return lambdaQuery()
                .eq(RequirementConversation::getSourceSubTaskId, sourceSubTaskId)
                .eq(RequirementConversation::getStatus, RequirementClarifyService.STATUS_ACTIVE)
                .orderByDesc(RequirementConversation::getId)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public RequirementConversation findActiveBySourceDraft(Long parentTaskId, String draftCode) {
        if (parentTaskId == null || draftCode == null || draftCode.isBlank()) {
            return null;
        }
        return lambdaQuery()
                .eq(RequirementConversation::getTaskId, parentTaskId)
                .eq(RequirementConversation::getSourceDraftCode, draftCode)
                .eq(RequirementConversation::getStatus, RequirementClarifyService.STATUS_ACTIVE)
                .orderByDesc(RequirementConversation::getId)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public boolean abandonByDerivedTask(Long derivedTaskId) {
        if (derivedTaskId == null) {
            return false;
        }
        return lambdaUpdate()
                .eq(RequirementConversation::getDerivedTaskId, derivedTaskId)
                .eq(RequirementConversation::getStatus, RequirementClarifyService.STATUS_FINALIZED)
                .set(RequirementConversation::getStatus, RequirementClarifyService.STATUS_ABANDONED)
                .update();
    }
}
