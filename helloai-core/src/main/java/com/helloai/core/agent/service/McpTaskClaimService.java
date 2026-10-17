package com.helloai.core.agent.service;

import com.helloai.core.agent.service.McpToolService.AckResult;
import com.helloai.core.agent.service.McpToolService.ClaimSubTaskResult;
import com.helloai.core.agent.service.McpToolService.PullTasksResult;
import com.helloai.core.agent.service.McpToolService.StartSubTaskResult;

/**
 * MCP 任务获取链服务契约（RM9-a 从 {@link McpToolService} 实现类拆出的职责契约）：
 * 「任务获取 → 确认 → 认领 → 开工」同一条前置链。
 *
 * <p>实现见 {@code com.helloai.core.agent.service.impl.McpTaskClaimServiceImpl}。
 * 按 {@code HelloAI_CODE_STYLE.md §8.1}「{@code {domain}.service} 放 {@code XxxService} 接口、
 * {@code {domain}.service.impl} 放 {@code XxxServiceImpl} 实现」组织。</p>
 */
public interface McpTaskClaimService {

    /** 拉取待办任务（含可选只读任务）。 */
    PullTasksResult pullTasks(Long agentId, String role, int max, boolean includeRead);

    /** 确认收到消息。 */
    AckResult ack(Long agentId, String messageId);

    /** 认领子任务。 */
    ClaimSubTaskResult claimSubTask(Long agentId, Long subTaskId);

    /** 开始子任务（开工）。 */
    StartSubTaskResult startSubTask(Long agentId, Long subTaskId);
}
