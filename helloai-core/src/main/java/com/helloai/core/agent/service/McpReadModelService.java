package com.helloai.core.agent.service;

import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.service.McpToolService.GetDepsSummaryResult;
import com.helloai.core.agent.service.McpToolService.SubTaskDetail;

/**
 * MCP 只读组装服务契约（RM9-a 从 {@link McpToolService} 实现类拆出的职责契约）：
 * <b>纯只读组装</b>（零状态变更）。
 *
 * <p>实现见 {@code com.helloai.core.agent.service.impl.McpReadModelServiceImpl}。
 * 按 {@code HelloAI_CODE_STYLE.md §8.1} 组织。</p>
 */
public interface McpReadModelService {

    /** 查询上游依赖摘要。 */
    GetDepsSummaryResult getDepsSummary(Long agentId, Long subTaskId);

    /** 查询子任务详情。 */
    SubTaskDetail getSubTaskDetail(Long agentId, Long subTaskId);

    /** 组装子任务详情（认领成功路径复用的只读投影）。 */
    SubTaskDetail buildSubTaskDetail(SubTaskSnapshot subTask);
}
