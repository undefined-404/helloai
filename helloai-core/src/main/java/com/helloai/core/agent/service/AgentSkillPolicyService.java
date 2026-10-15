package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.AgentSkillDeriver;
import com.helloai.core.agent.SkillCapabilityRequirements;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.agent.skill.AgentSkillSpecService;
import com.helloai.core.agent.skill.SkillPackage;
import com.helloai.core.system.port.LlmProviderModelProfile;
import com.helloai.core.system.port.ModelCapabilities;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 技能推导与模型能力校验组件。
 *
 * <p>从 {@code AgentServiceImpl} 拆分（§7.8 类规模红线）：收口技能落库推导
 * （能力驱动 / 基础推导）与 modelType 格式、可用性、角色唯一性校验，
 * 与 {@link AgentSkillDeriver} 纯函数配合，职责内聚于技能策略域。</p>
 */
@Slf4j
@Service
public class AgentSkillPolicyService {

    private final AgentMapper agentMapper;
    private final LlmProviderModelQueryService llmProviderModelQueryService;
    private final AgentSkillSpecService agentSkillSpecService;

    public AgentSkillPolicyService(AgentMapper agentMapper,
                                   LlmProviderModelQueryService llmProviderModelQueryService,
                                   AgentSkillSpecService agentSkillSpecService) {
        this.agentMapper = agentMapper;
        this.llmProviderModelQueryService = llmProviderModelQueryService;
        this.agentSkillSpecService = agentSkillSpecService;
    }

    /**
     * 校验 Agent skills 不超出模型能力。
     *
     * <p>规则（D2=A 标准校验 + 自定义豁免）：仅标准技能标签按模型能力谓词判定
     * （{@link com.helloai.core.agent.SkillCapabilityRequirements#satisfies}）；非标准项视为自定义技能豁免；
     * modelType 为 null/blank 或模型未识别（表中不存在）时直接通过（降级兼容）。</p>
     *
     * <p><b>`M-9` 换判据</b>：由 V52 两列白名单（{@code capabilitySkills ∪ availableOptionalSkills}）
     * 改为能力谓词（{@code thinking←reasoning}、{@code web-search←webSearch}；
     * 作业类型技能恒真）。错误码 / 文案逐字保持。</p>
     */
    public void validateAgentSkills(String modelType, List<String> skills) {
        if (modelType == null || modelType.isBlank()) {
            return;
        }
        List<String> cleaned = AgentSkillDeriver.clean(skills);
        if (cleaned.isEmpty()) {
            return;
        }
        LlmProviderModelProfile profile = llmProviderModelQueryService.findCapabilityProfileByModelType(modelType);
        if (profile == null) {
            // 未识别模型：不校验（降级兼容）
            return;
        }
        // 越界 = 标准技能且能力谓词不满足（satisfies 恒真会让门失效，故判据必须走谓词）。
        List<String> invalid = cleaned.stream()
                .filter(AgentSkillDeriver.STANDARD_SKILLS::contains)
                .filter(s -> !SkillCapabilityRequirements.satisfies(s, profile.capabilities()))
                .toList();
        if (!invalid.isEmpty()) {
            // 技能超出模型能力属可自纠入参错误：显式 code=400 而非单参构造的默认 500，
            // 与同族修复同口径（本类 validateModelType 格式/可用性 → 400、validateModelUniqueInRole → 409，
            // 以及 AgentController 的 name/role 校验 → 400）。单参构造会让 GlobalExceptionHandler
            // 把「你请求了一个该模型不支持的技能」也报成服务端故障（2026-10-10 修复）。
            throw new BizException(400, "模型 " + modelType + " 不支持技能: " + String.join(", ", invalid));
        }
    }

/**
 * 收口技能落库推导。
     *
     * <p>API_KEY_LLM 且 modelType 已识别 → 能力驱动推导（能力锁定 + 谓词过滤 + 自定义豁免+
     * <b>技能包自动挂载</b>）；其他接入类型或未识别模型 → 走基础推导（显式优先）。</p>
     *
     * <p><b>★技能包自动挂载（`G-022`，2026-10-15）</b>：内部 LLM Agent 的
     * {@code skills} 由模型谓词派生（粗粒度），而拆解侧 {@code required_skills}
     * 全是 {@code eng-*} 技能包（细粒度）⇒ 选人处 AND 匹配跨命名空间恒失败。
     * 本方法把「本模型谓词可承接的技能包标签」并入落库值，
     * 使两个命名空间之间存在<b>由模型事实驱动</b>的边。</p>
     */
    public List<String> deriveSkillsForRegistration(Agent agent, List<String> explicitSkills) {
        if (agent == null) {
            return new ArrayList<>();
        }
        AgentAccessType accessType = agent.getAccessType();
        String modelType = agent.getModelType();
        if (accessType == AgentAccessType.API_KEY_LLM && modelType != null && !modelType.isBlank()) {
            LlmProviderModelProfile profile = llmProviderModelQueryService.findCapabilityProfileByModelType(modelType);
            if (profile != null) {
                ModelCapabilities caps = profile.capabilities();
                return AgentSkillDeriver.deriveWithCapabilities(
                        accessType, agent.getName(), agent.getRemark(),
                        explicitSkills,
                        caps,
                        mountablePackageLabels(caps));
            }
        }
        // 非 API_KEY_LLM / 未识别模型：基础推导（显式优先，不合并基础技能）
        return AgentSkillDeriver.derive(accessType, agent.getName(), agent.getRemark(), explicitSkills);
    }

    /**
     * 本模型谓词可承接的平台技能包标签（`G-022` 挂载面）。
     *
     * <p>判定逐包走 {@link SkillCapabilityRequirements#satisfiesPackage}（唯一判据），
     * 引用边来自 {@code SkillPackage.requiredBaseCapabilities}（frontmatter 新增键 /
     * {@code V113} 新增列）；空引用边的技能包（作业规范类）恒可挂载。</p>
     *
     * <p><b>只喂健康包</b>（{@code listPackages()} 已按目录语义过滤坏包）——坏包若被挂载，
     * 会让 Agent「可被指派但注入不出内容且无提示」（同
     * {@code SkillPackageCatalog} 目录面/健康面的口径差异）。</p>
     *
     * <p>技能包面不可用（目录未就绪 / 抛错）时返回空列表 ⇒ 行为退化为挂载前逐字一致，
     * <b>不让技能面故障阻断 Agent 注册</b>。</p>
     */
    private List<String> mountablePackageLabels(ModelCapabilities caps) {
        try {
            List<SkillPackage> packages = agentSkillSpecService.listPackages();
            if (packages == null || packages.isEmpty()) {
                return List.of();
            }
            List<String> mountable = new ArrayList<>();
            for (SkillPackage pkg : packages) {
                String label = pkg == null ? null : pkg.name();
                if (label == null || label.isBlank()) {
                    continue;
                }
                if (SkillCapabilityRequirements.satisfiesPackage(pkg.requiredBaseCapabilities(), caps)) {
                    mountable.add(label);
                }
            }
            return mountable;
        } catch (RuntimeException e) {
            log.warn("技能包目录读取失败，Agent 技能推导跳过技能包挂载（降级为挂载前行为）: reason={}", e.toString());
            return List.of();
        }
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
        // 入参校验失败属客户端错误：显式 code=400（BizException 单参构造默认 500，
        // 会让 GlobalExceptionHandler 把「格式错误」这类可自纠的问题也返 500；
        // 2026-10-05 修复：Agent 注册接口非法 modelType 一律 500 → 现 400）。
        if (colonIdx <= 0 || colonIdx == modelType.length() - 1) {
            throw new BizException(400, "modelType 格式错误，应为 providerCode:modelName");
        }
        String providerCode = modelType.substring(0, colonIdx);
        String modelName = modelType.substring(colonIdx + 1);
        if (!llmProviderModelQueryService.isModelAvailable(providerCode, modelName)) {
            throw new BizException(400, "模型不可用或已禁用: " + modelType);
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
            // 唯一性冲突属资源冲突：显式 code=409（原单参构造默认 500，2026-10-05 修复）
            throw new BizException(409, "角色 " + role + " 已存在使用模型 " + modelName + " 的Agent，同一模型在同一角色下只能注册一个");
        }
    }
}
