package com.helloai.core.agent.service;

import com.helloai.core.agent.service.McpToolService.CheckInResult;
import com.helloai.core.agent.service.McpToolService.CheckOutResult;
import com.helloai.core.agent.service.McpToolService.GetAgentStatusResult;
import com.helloai.core.agent.service.McpToolService.HeartbeatResult;
import com.helloai.core.agent.service.McpToolService.ReportBlockedResult;
import com.helloai.core.agent.service.McpToolService.SubmitResultResult;
import com.helloai.core.agent.service.McpToolService.UploadArtifactResult;

import java.util.List;

/**
 * MCP 上报面服务契约（RM9-a 从 {@link McpToolService} 实现类拆出的职责契约）：
 * 「Agent 上报自身状态与结果」。
 *
 * <p>实现见 {@code com.helloai.core.agent.service.impl.McpAgentReportServiceImpl}。
 * 按 {@code HelloAI_CODE_STYLE.md §8.1} 组织。</p>
 */
public interface McpAgentReportService {

    /** 心跳（在班续约 + 配额回执）。 */
    HeartbeatResult heartbeat(Long agentId);

    /** 上传产物（附件登记）。 */
    UploadArtifactResult uploadArtifact(Long agentId, Long subTaskId,
                                        String fileName, String mimeType,
                                        Long fileSize, String storageUrl);

    /** 上报执行结果。 */
    SubmitResultResult submitResult(Long agentId, Long subTaskId, String resultId,
                                    Boolean success, String output, String error, String finishReason,
                                    Integer tokenUsage);

    /** 上报阻塞。 */
    ReportBlockedResult reportBlocked(Long agentId, Long subTaskId, String reason);

    /** 打卡上班（注册在班租约 + 上报技能）。 */
    CheckInResult checkIn(Long agentId, String workMode, Integer maxConcurrent, Integer ttlMinutes,
                          List<String> reportedSkills);

    /** 打卡下班。 */
    CheckOutResult checkOut(Long agentId, String reason);

    /** 查询 Agent 自身状态。 */
    GetAgentStatusResult getAgentStatus(Long agentId);
}
