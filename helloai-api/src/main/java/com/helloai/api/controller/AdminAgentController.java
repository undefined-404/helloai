package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.admin.AgentCreateRequest;
import com.helloai.api.dto.admin.AgentUpdateRequest;
import com.helloai.api.dto.agent.AgentRegistrationResponse;
import com.helloai.api.dto.agent.AgentResponse;
import com.helloai.api.dto.agent.ApiKeyResponse;
import com.helloai.common.base.R;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.entity.Agent;
import com.helloai.core.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/admin/agents")
@RequiredArgsConstructor
public class AdminAgentController {

    private final AgentService agentService;

    /**
     * Agent 列表（分页 + 过滤）
     */
    @GetMapping
    public R<PageResult<AgentResponse>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword) {
        AgentRole roleFilter = (role != null && !role.isBlank()) ? AgentRole.valueOf(role.toUpperCase()) : null;
        AgentStatus statusFilter = (status != null && !status.isBlank()) ? AgentStatus.valueOf(status.toUpperCase()) : null;
        var wrapper = new LambdaQueryWrapper<Agent>()
                .eq(roleFilter != null, Agent::getRole, roleFilter)
                .eq(statusFilter != null, Agent::getStatus, statusFilter)
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(Agent::getName, keyword)
                        .or().like(Agent::getRemark, keyword))
                .orderByDesc(Agent::getCreateTime);
        Page<Agent> result = agentService.page(new Page<>(page, pageSize), wrapper);
        return R.ok(PageResult.of(result, this::toResponse));
    }

    /**
     * Agent 详情
     */
    @GetMapping("/{id}")
    public R<AgentResponse> getById(@PathVariable Long id) {
        Agent agent = agentService.getById(id);
        if (agent == null) return R.fail("Agent 不存在");
        return R.ok(toResponse(agent));
    }

    /**
     * 创建 Agent
     */
    @PostMapping
    public R<AgentRegistrationResponse> create(@RequestBody AgentCreateRequest req) {
        AgentRole role = AgentRole.valueOf(req.getRole().toUpperCase());
        Agent agent = agentService.register(req.getName(), role, req.getRemark());
        agent.setModelType(req.getModelType());
        agentService.updateById(agent);
        AgentRegistrationResponse response = new AgentRegistrationResponse();
        response.setId(agent.getId());
        response.setName(agent.getName());
        response.setRole(agent.getRole().name());
        response.setApiKey(agent.getApiKey());
        return R.ok(response);
    }

    /**
     * 更新 Agent 信息
     */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody AgentUpdateRequest req) {
        agentService.updateAgent(id, req.getName(), req.getModelType(), req.getRemark());
        return R.ok();
    }

    /**
     * 更新 Agent 状态
     */
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        AgentStatus status = AgentStatus.valueOf(body.get("status").toUpperCase());
        agentService.updateStatus(id, status);
        return R.ok();
    }

    /**
     * 重置 API Key
     */
    @PostMapping("/{id}/reset-key")
    public R<ApiKeyResponse> resetKey(@PathVariable Long id) {
        String newKey = agentService.resetApiKey(id);
        ApiKeyResponse response = new ApiKeyResponse();
        response.setApiKey(newKey);
        return R.ok(response);
    }

    /**
     * 删除 Agent
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        agentService.deleteAgent(id);
        return R.ok();
    }

    private AgentResponse toResponse(Agent agent) {
        AgentResponse response = new AgentResponse();
        response.setId(agent.getId());
        response.setName(agent.getName());
        response.setRole(agent.getRole());
        response.setApiKey(agent.getApiKey());
        response.setModelType(agent.getModelType());
        response.setStatus(agent.getStatus());
        response.setScore(agent.getScore());
        response.setRemark(agent.getRemark());
        response.setCreateTime(agent.getCreateTime());
        response.setUpdateTime(agent.getUpdateTime());
        return response;
    }
}
