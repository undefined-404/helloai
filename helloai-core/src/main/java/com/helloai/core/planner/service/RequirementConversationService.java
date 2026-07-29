package com.helloai.core.planner.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.mapper.RequirementConversationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 需求澄清会话薄 CRUD 服务。
 *
 * <p>业务编排（LLM 调用、终稿建任务）统一收口在
 * {@link com.helloai.core.planner.RequirementClarifyService}，本服务只提供数据访问。</p>
 */
@Slf4j
@Service
public class RequirementConversationService
        extends ServiceImpl<RequirementConversationMapper, RequirementConversation> {
}
