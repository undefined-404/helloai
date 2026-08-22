package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.AgentSkillDeriver;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 技能推导与模型能力校验组件。
 *
 * <p>从 {@code AgentServiceImpl} 拆分（§7.8 类规模红线）：收口技能落库推导
 * （能力驱动 / 基础推导）与 modelType 格式、可用性、角色唯一性校验，
 * 与 {@link AgentSkillDeriver} 纯函数配合，职责内聚于技能策略域。</p>
 */
@Service
public class AgentSkillPolicyService {

    private final AgentMapper agentMapper;
    private final LlmProviderModelQueryService llmProviderModelQueryService;

    public AgentSkillPolicyService(AgentMapper agentMapper,
                                   LlmProviderModelQueryService llmProviderModelQueryService) {
        this.agentMapper = agentMapper;
        this.llmProviderModelQueryService = llmProviderModelQueryService;
    }

    /**
     * 校验 Agent skills 不超出模型能力。
     *
     * <p>规则（D2=A 标准校验 + 自定义豁免）：仅标准技能标签查模型白名单
     * （capabilitySkills ∪ availableOptionalSkills）；非标准项视为自定义技能豁免；
     * modelType 为 null/blank 或模型未识别（表中不存在）时直接通过（降级兼容）。</p>
     */
    public void validateAgentSkills(String modelType, List<String> skills) {
        if (modelType == null || modelType.isBlank()) {
            return;
        }
        List<String> cleaned = AgentSkillDeriver.clean(skills);
        if (cleaned.isEmpty()) {
            return;
        }
        Optional<LlmProviderModel> capability = llmProviderModelQueryService.findCapabilityByModelType(modelType);
        if (capability.isEmpty()) {
            // 未识别模型：不校验（降级兼容）
            return;
        }
        Set<String> whitelist = new HashSet<>();
        if (capability.get().getCapabilitySkills() != null) {
            whitelist.addAll(capability.get().getCapabilitySkills());
        }
        if (capability.get().getAvailableOptionalSkills() != null) {
            whitelist.addAll(capability.get().getAvailableOptionalSkills());
        }
        List<String> invalid = cleaned.stream()
                .filter(AgentSkillDeriver.STANDARD_SKILLS::contains)
                .filter(s -> !whitelist.contains(s))
                .toList();
        if (!invalid.isEmpty()) {
            throw new BizException("模型 " + modelType + " 不支持技能: " + String.join(", ", invalid));
        }
    }

    /**
     * 收口技能落库推导。
     *
     * <p>API_KEY_LLM 且 modelType 已识别 → 能力驱动推导（能力锁定 + 白名单过滤 + 自定义豁免）；
     * 其他接入类型或未识别模型 → 走基础推导（显式优先）。</p>
     */
    public List<String> deriveSkillsForRegistration(Agent agent, List<String> explicitSkills) {
        if (agent == null) {
            return new ArrayList<>();
        }
        AgentAccessType accessType = agent.getAccessType();
        String modelType = agent.getModelType();
        if (accessType == AgentAccessType.API_KEY_LLM && modelType != null && !modelType.isBlank()) {
            Optional<LlmProviderModel> capability = llmProviderModelQueryService.findCapabilityByModelType(modelType);
            if (capability.isPresent()) {
                return AgentSkillDeriver.deriveWithCapabilities(
                        accessType, agent.getName(), agent.getRemark(),
                        explicitSkills,
                        capability.get().getCapabilitySkills(),
                        capability.get().getAvailableOptionalSkills());
            }
        }
        // 非 API_KEY_LLM / 未识别模型：基础推导（显式优先，不合并基础技能）
        return AgentSkillDeriver.derive(accessType, agent.getName(), agent.getRemark(), explicitSkills);
    }

    /**
     * 校验 modelType 格式、可用性及角色唯一性。
     *
     * <p>格式：providerCode:modelName。模型须启用。同模型在同一角色下只能被一个 API_KEY_LLM Agent 使用。</p>
     *
     * @param modelType       待校验的 modelType，null/blank 时跳过校验（保留原值场景）
     * @param role            Agent 角色
     * @param excludeAgentId  排除的 Agent ID（编辑自身时排除；新增时传 null）
     */
    public void validateModelType(String modelType, AgentRole role, Long excludeAgentId) {
        if (modelType == null || modelType.isBlank()) {
            return;
        }
        int colonIdx = modelType.indexOf(':');
        if (colonIdx <= 0 || colonIdx == modelType.length() - 1) {
            throw new BizException("modelType 格式错误，应为 providerCode:modelName");
        }
        String providerCode = modelType.substring(0, colonIdx);
        String modelName = modelType.substring(colonIdx + 1);
        if (!llmProviderModelQueryService.isModelAvailable(providerCode, modelName)) {
            throw new BizException("模型不可用或已禁用: " + modelType);
        }
        validateModelUniqueInRole(providerCode, modelName, role, excludeAgentId);
    }

    /**
     * 校验同一模型在同一角色下唯一。
     *
     * <p>规则：deepseek-v4-flash 和 kimi-k3 可同时注册为 Planner；
     * 但 deepseek-v4-flash 不能注册两个 Planner。</p>
     *
     * @param providerCode   供应商编码（如 deepseek）
     * @param modelName      模型名称（如 deepseek-v4-flash）
     * @param role           Agent 角色（PLANNER/EXECUTOR/REVIEWER）
     * @param excludeAgentId 排除的 Agent ID（编辑场景下排除自身）
     * @throws BizException 当同一模型在同一角色下已存在时抛出
     */
    public void validateModelUniqueInRole(String providerCode, String modelName, AgentRole role, Long excludeAgentId) {
        String modelType = providerCode + ":" + modelName;
        Long exists = agentMapper.selectCount(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getRole, role)
                .eq(Agent::getAccessType, AgentAccessType.API_KEY_LLM)
                .eq(Agent::getModelType, modelType)
                .eq(Agent::getDeleted, 0)
                .ne(excludeAgentId != null, Agent::getId, excludeAgentId));
        if (exists != null && exists > 0) {
            throw new BizException("角色 " + role + " 已存在使用模型 " + modelName + " 的Agent，同一模型在同一角色下只能注册一个");
        }
    }
}
