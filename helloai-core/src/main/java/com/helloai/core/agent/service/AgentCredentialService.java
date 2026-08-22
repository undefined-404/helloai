package com.helloai.core.agent.service;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.system.crypto.AgentApiKeyCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * Agent 工牌 consumerToken 键管组件。
 *
 * <p>从 {@code AgentServiceImpl} 拆分（§7.8 类规模红线）：负责 consumerToken
 * 下发（{@code ak_} 前缀随机十六进制）与重置落库，等保加密与 hash 由
 * {@link AgentApiKeyCipher} 统一托管。</p>
 */
@Slf4j
@Service
public class AgentCredentialService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AgentMapper agentMapper;
    private final AgentApiKeyCipher agentApiKeyCipher;

    public AgentCredentialService(AgentMapper agentMapper, AgentApiKeyCipher agentApiKeyCipher) {
        this.agentMapper = agentMapper;
        this.agentApiKeyCipher = agentApiKeyCipher;
    }

    /**
     * 重置 Agent 工牌 consumerToken（等保加密落库，明文仅本次响应返回一次）。
     */
    @Transactional(rollbackFor = Exception.class)
    public String resetApiKey(Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) throw new BizException("Agent 不存在: " + agentId);
        String newKey = issueConsumerToken();
        agent.setApiKey(agentApiKeyCipher.encrypt(newKey));
        agent.setApiKeyHash(agentApiKeyCipher.sha256Hex(newKey));
        agentMapper.updateById(agent);
        log.info("Agent 工牌 consumerToken 重置: id={}", agentId);
        return newKey;
    }

    /**
     * 下发 Agent 工牌 consumerToken。
     *
     * <p>语义收口后，`agent.api_key` 保持字段名不变，但含义升级为 consumerToken。
     * 真实 LLM 凭证不得再落在该字段。</p>
     */
    public String issueConsumerToken() {
        return "ak_" + generateRandomHex(32);
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
