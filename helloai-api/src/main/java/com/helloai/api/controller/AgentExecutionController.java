package com.helloai.api.controller;

import com.helloai.api.dto.agent.AgentExecutionConnectivityRequest;
import com.helloai.api.dto.agent.AgentExecutionConnectivityResponse;
import com.helloai.api.dto.agent.AgentExecutionPreviewRequest;
import com.helloai.api.dto.agent.AgentExecutionPreviewResponse;
import com.helloai.common.base.R;
import com.helloai.core.agent.domain.AgentExecutionConnectivityResult;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentExecutionConnectivityService;
import com.helloai.core.agent.service.AgentExecutionPreviewService;
import com.helloai.core.agent.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Agent 执行验证入口。
 *
 * <p>T5 先提供一个最小管理员入口，验证“选择执行器 → 执行 → 返回结果”的平台内闭环。</p>
 */
@RestController
@RequestMapping("/api/agent-executions")
@RequiredArgsConstructor
public class AgentExecutionController {

    private final AgentExecutionConnectivityService agentExecutionConnectivityService;
    private final AgentExecutionPreviewService agentExecutionPreviewService;
    private final AgentService agentService;

    @PostMapping("/checkConnectivityByAgentId/{agentId}")
    public R<AgentExecutionConnectivityResponse> checkConnectivityByAgentId(
            @PathVariable("agentId") Long agentId,
            @RequestBody(required = false) AgentExecutionConnectivityRequest request) {
        AgentExecutionConnectivityResult result = agentExecutionConnectivityService.probe(
                agentId,
                request != null ? request.getSystemPrompt() : null,
                request != null ? request.getUserPrompt() : null
        );

        AgentExecutionConnectivityResponse response = new AgentExecutionConnectivityResponse();
        response.setAgentId(result.getAgentId());
        response.setAgentName(result.getAgentName());
        response.setRole(result.getRole());
        response.setAccessType(result.getAccessType());
        response.setProvider(result.getProvider());
        response.setModel(result.getModel());
        response.setMockMode(result.isMockMode());
        response.setHasActiveVaultCredential(result.isHasActiveVaultCredential());
        response.setHasEncryptedValue(result.isHasEncryptedValue());
        response.setHasSecretRef(result.isHasSecretRef());
        response.setCredentialReady(result.isCredentialReady());
        response.setSuccess(result.isSuccess());
        response.setStage(result.getStage());
        response.setLatencyMs(result.getLatencyMs());
        response.setOutput(result.getOutput());
        response.setThinking(result.getThinking());
        response.setErrorMessage(result.getErrorMessage());
        response.setRootException(result.getRootException());
        response.setRootMessage(result.getRootMessage());
        response.setTokenUsage(result.getTokenUsage());
        return R.ok(response);
    }

    @PostMapping("/previewByAgentId/{agentId}")
    public R<AgentExecutionPreviewResponse> previewByAgentId(@PathVariable("agentId") Long agentId,
                                                    @RequestBody AgentExecutionPreviewRequest request) {
        AgentResult result = agentExecutionPreviewService.preview(
                agentId,
                request.getSubTaskId(),
                request.getSystemPrompt(),
                request.getUserPrompt(),
                request.getContext(),
                request.getRequiredCapabilities()
        );

        Agent agent = agentService.getById(agentId);
        AgentExecutionPreviewResponse response = new AgentExecutionPreviewResponse();
        response.setAgentId(agent.getId());
        response.setAgentName(agent.getName());
        response.setRole(agent.getRole());
        response.setAccessType(agent.getAccessType());
        response.setExecutorName(result.getExecutorName());
        response.setSuccess(result.isSuccess());
        response.setOutput(result.getOutput());
        response.setThinking(result.getThinking());
        response.setErrorMessage(result.getErrorMessage());
        response.setFinishReason(result.getFinishReason());
        response.setTokenUsage(result.getTokenUsage());
        return R.ok(response);
    }
}
