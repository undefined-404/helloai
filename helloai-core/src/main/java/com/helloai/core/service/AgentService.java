package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.AgentStatus;
import com.helloai.core.entity.Agent;
import com.helloai.core.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService extends ServiceImpl<AgentMapper, Agent> {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Transactional(rollbackFor = Exception.class)
    public Agent register(String name, AgentRole role, String description) {
        var existing = lambdaQuery().eq(Agent::getName, name).one();
        if (existing != null) {
            throw new BizException("名称 '" + name + "' 已被注册");
        }

        Agent agent = new Agent();
        agent.setName(name);
        agent.setRole(role);
        agent.setApiKey("ak_" + generateRandomHex(32));
        agent.setStatus(AgentStatus.ACTIVE);
        agent.setScore(0);
        agent.setRemark(description);
        save(agent);

        log.info("Agent 注册成功: name={}, role={}, id={}", name, role, agent.getId());
        return agent;
    }

    public Agent getByApiKey(String apiKey) {
        return lambdaQuery().eq(Agent::getApiKey, apiKey).one();
    }

    public List<Agent> listByRole(AgentRole role) {
        return lambdaQuery().eq(Agent::getRole, role).list();
    }

    public List<Agent> listActive() {
        return lambdaQuery()
                .eq(Agent::getStatus, AgentStatus.ACTIVE)
                .orderByDesc(Agent::getScore)
                .list();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long agentId, AgentStatus status) {
        Agent agent = getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
        agent.setStatus(status);
        updateById(agent);
        log.info("Agent 状态变更: id={}, status={}", agentId, status);
    }

    /**
     * 重置 Agent API Key
     */
    @Transactional(rollbackFor = Exception.class)
    public String resetApiKey(Long agentId) {
        Agent agent = getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
        String newKey = "ak_" + generateRandomHex(32);
        agent.setApiKey(newKey);
        updateById(agent);
        log.info("Agent API Key 重置: id={}", agentId);
        return newKey;
    }

    /**
     * 删除 Agent（逻辑删除）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAgent(Long agentId) {
        Agent agent = getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
        removeById(agentId);
        log.info("Agent 删除: id={}, name={}", agentId, agent.getName());
    }

    /**
     * 更新 Agent 信息
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateAgent(Long agentId, String name, String modelType, String remark) {
        Agent agent = getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
        if (name != null) agent.setName(name);
        if (modelType != null) agent.setModelType(modelType);
        if (remark != null) agent.setRemark(remark);
        updateById(agent);
        log.info("Agent 信息更新: id={}", agentId);
    }

    private String generateRandomHex(int length) {
        byte[] bytes = new byte[length / 2];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
