package com.helloai.core.planner.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.helloai.core.planner.entity.RequirementConversation;

/**
 * 需求澄清会话薄 CRUD 服务。
 *
 * <p>业务编排（LLM 调用、终稿建任务）统一收口在
 * {@link com.helloai.core.planner.service.RequirementClarifyService}，本服务只提供数据访问。</p>
 */
public interface RequirementConversationService extends IService<RequirementConversation> {
}
