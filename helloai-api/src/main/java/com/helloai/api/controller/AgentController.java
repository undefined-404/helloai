package com.helloai.api.controller;

import com.helloai.api.dto.agent.AgentRegistrationResponse;
import com.helloai.api.dto.agent.AgentResponse;
import com.helloai.api.support.AgentBaseUrlResolver;
import com.helloai.common.base.R;
import com.helloai.common.config.AgentConfigProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.LlmProviderCatalogService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.system.crypto.AgentApiKeyCipher;
import com.helloai.core.system.entity.LlmProviderModel;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import com.helloai.core.system.service.PromptTemplateService;
import com.helloai.core.agent.AgentCapability;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final AgentConfigProperties agentConfig;
    private final PromptTemplateService promptTemplateService;
    private final LlmProviderCatalogService llmProviderCatalogService;
    private final LlmProviderModelQueryService llmProviderModelQueryService;
    private final AgentBaseUrlResolver agentBaseUrlResolver;
    private final AgentApiKeyCipher agentApiKeyCipher;

    @GetMapping("/list")
    public R<List<AgentResponse>> list() {
        return R.ok(agentService.list().stream().map(this::toResponse).toList());
    }

    /**
     * 可指派执行者候选（改派/认领/死信重派选择器数据源）。
     *
     * <p>仅返回 role=EXECUTOR 且 ACTIVE 的 Agent：内部 LLM（API_KEY_LLM）
     * 由平台代为调用恒可用，外部 Agent 要求在线（ONLINE/IDLE）——
     * 避免人工改派给 PLANNER/REVIEWER 或离线执行者后任务无人消费。</p>
     */
    @GetMapping("/listAssignableExecutors")
    public R<List<AgentResponse>> listAssignableExecutors() {
        return R.ok(agentService.listAssignableExecutors().stream().map(this::toResponse).toList());
    }

    @GetMapping("/getById/{id}")
    public R<AgentResponse> getById(@PathVariable("id") Long id) {
        Agent agent = agentService.getById(id);
        if (agent == null) {
            return R.fail("Agent 不存在");
        }
        return R.ok(toResponse(agent));
    }

    @PostMapping("/register")
    public R<AgentRegistrationResponse> register(@RequestBody Map<String, Object> body) {
        // 与 registerWithToken 共用自注册开关：关闭后公开注册通道同样不可用
        if (!agentConfig.isAllowRegistration()) {
            return R.fail(403, "Agent 自注册已关闭，请联系管理员创建");
        }
        String name = resolveName(body);
        AgentRole role = resolveRole(body);
        String description = (String) body.getOrDefault("description", "");
        boolean idempotent = Boolean.TRUE.equals(body.get("idempotent"));
        // 注册前预校验 modelType（格式/可用性/角色唯一性）与 skills 白名单：两类校验都前置于
        // 创建 Agent，失败时库里不留任何痕迹。skills 是后补的一项——此前只预校验了 modelType，
        // skills 校验留在 applyRegistrationExtras 内，抛错时 register() 已提交，会残留
        // access_type=CLI_CLIENT / model_type=NULL 的脏 Agent（2026-10-10 修复）。
        String modelType = (String) body.get("modelType");
        agentService.validateModelType(modelType, role, null);
        agentService.validateAgentSkills(modelType, resolveExplicitSkills(body));
        Agent agent = idempotent
                ? agentService.registerOrGet(name, role, description)
                : agentService.register(name, role, description);
        applyRegistrationExtras(agent, body);
        log.info("Agent 手动注册成功: name={}, role={}, id={}, idempotent={}", name, role, agent.getId(), idempotent);
        return R.ok(toRegistrationResponse(agent));
    }

    @PostMapping("/registerWithToken")
    public R<AgentRegistrationResponse> registerWithToken(@RequestBody Map<String, Object> body) {
        if (!agentConfig.isAllowRegistration()) {
            return R.fail(403, "Agent 自注册已关闭，请联系管理员创建");
        }

        String token = (String) body.get("registrationToken");
        if (token == null || !token.equals(agentConfig.getRegistrationToken())) {
            return R.fail(403, "注册令牌无效");
        }

        String name = resolveName(body);
        String description = (String) body.getOrDefault("description", "");
        AgentRole role = resolveRole(body);
        boolean idempotent = Boolean.TRUE.equals(body.get("idempotent"));
        // 注册前预校验 modelType（格式/可用性/角色唯一性）与 skills 白名单：两类校验都前置于
        // 创建 Agent，失败时库里不留任何痕迹。skills 是后补的一项——此前只预校验了 modelType，
        // skills 校验留在 applyRegistrationExtras 内，抛错时 register() 已提交，会残留
        // access_type=CLI_CLIENT / model_type=NULL 的脏 Agent（2026-10-10 修复）。
        String modelType = (String) body.get("modelType");
        agentService.validateModelType(modelType, role, null);
        agentService.validateAgentSkills(modelType, resolveExplicitSkills(body));
        Agent agent = idempotent
                ? agentService.registerOrGet(name, role, description)
                : agentService.register(name, role, description);
        applyRegistrationExtras(agent, body);

        log.info("Agent 自助注册成功: name={}, role={}, id={}, accessType={}",
                name, role, agent.getId(), agent.getAccessType());
        return R.ok(toRegistrationResponse(agent));
    }

    /**
     * 解析并校验注册入参 name。
     *
     * <p>空白 name 一律抛 {@code BizException(400, ...)}（经
     * {@link com.helloai.api.advice.GlobalExceptionHandler} 透出 HTTP 400，与 {@link #resolveRole}
     * 同口径）：此前空 name 会正常建库，生成一个「无名 Agent」，而删除入口
     * {@code deleteById} 的 confirmName 校验会因 {@code isBlank()} 直接拒绝，
     * 导致该空名 Agent <b>无法删除</b>（2026-10-05 修复）。</p>
     */
    private static String resolveName(Map<String, Object> body) {
        Object raw = body.get("name");
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new com.helloai.common.base.BizException(400, "name 不能为空");
        }
        return String.valueOf(raw);
    }

    /**
     * 解析并校验注册入参 role。
     *
     * <p>缺失/空白/非法取值一律抛 {@code BizException(400, ...)}（经
     * {@link com.helloai.api.advice.GlobalExceptionHandler} 透出 HTTP 400）：
     * 此前 {@code AgentRole.valueOf} 的 {@code IllegalArgumentException} 在 role 为 null 时
     * 退化为 NPE → 500，且非法枚举值会把枚举类路径泄漏进错误消息（2026-10-05 修复）。</p>
     */
    private static AgentRole resolveRole(Map<String, Object> body) {
        Object raw = body.get("role");
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new com.helloai.common.base.BizException(400,
                    "role 不能为空，应为 PLANNER/EXECUTOR/REVIEWER");
        }
        try {
            return AgentRole.valueOf(String.valueOf(raw).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.helloai.common.base.BizException(400,
                    "role 取值非法: " + raw + "，应为 PLANNER/EXECUTOR/REVIEWER");
        }
    }

    /**
     * 从注册入参解析显式 skills；未传 / 非数组返回 {@code null}（语义为「不显式声明，交由推导」）。
     *
     * <p>由 {@link #applyRegistrationExtras} 与注册前预校验（{@code validateAgentSkills}）
     * 共用同一份解析，保证「预校验的集合」与「实际落库的集合」口径一致。</p>
     */
    private static List<String> resolveExplicitSkills(Map<String, Object> body) {
        Object skillsObj = body.get("skills");
        if (!(skillsObj instanceof List<?> rawSkills)) {
            return null;
        }
        List<String> explicit = new java.util.ArrayList<>();
        for (Object s : rawSkills) {
            explicit.add(String.valueOf(s));
        }
        return explicit;
    }

    /**
     * 处理注册入参的可选扩展字段：accessType / capabilities / labels / modelType / skills。
     *
     * <p>accessType 默认 CLI_CLIENT；capabilities 按 accessType 默认值填充后允许调用方覆盖；
     * labels 直接存储；modelType 供 API_KEY_LLM Agent 指定 provider:model（缺省走平台默认 provider）；
     * skills显式传入则使用显式值，否则已有技能为空时按 accessType + 名称/描述关键词 best-effort 推导，
     * 幂等复用（已有技能）不被推导覆盖。</p>
     */
    @SuppressWarnings("unchecked")
    private void applyRegistrationExtras(Agent agent, Map<String, Object> body) {
        // 1) modelType（校验 provider:model 格式及模型可用性）
        String modelType = (String) body.get("modelType");
        if (modelType != null && !modelType.isBlank()) {
            int colonIdx = modelType.indexOf(':');
            // 入参校验失败属客户端错误，显式 code=400（BizException 单参构造默认 500；
            // 2026-10-05 修复：Agent 注册非法入参由 500 收敛为 400，经 GlobalExceptionHandler 透出）。
            if (colonIdx <= 0 || colonIdx == modelType.length() - 1) {
                throw new com.helloai.common.base.BizException(400,
                        "modelType 格式错误，应为 providerCode:modelName，例如 deepseek:deepseek-v4-flash");
            }
            String providerCode = modelType.substring(0, colonIdx);
            String modelName = modelType.substring(colonIdx + 1);
            if (!llmProviderModelQueryService.isModelAvailable(providerCode, modelName)) {
                throw new com.helloai.common.base.BizException(400,
                        "模型不可用或已禁用: " + modelType);
            }
            // 同一模型在同一角色下全局唯一（与 registerWithExtras 路径的 validateModelType 对齐）
            agentService.validateModelUniqueInRole(providerCode, modelName, agent.getRole(), null);
            agent.setModelType(modelType);
        }

        // 2) accessType（默认 CLI_CLIENT）
        String accessTypeStr = (String) body.get("accessType");
        AgentAccessType accessType = AgentAccessType.CLI_CLIENT;
        if (accessTypeStr != null && !accessTypeStr.isBlank()) {
            try {
                accessType = AgentAccessType.valueOf(accessTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("未知 accessType={}，回退默认 CLI_CLIENT", accessTypeStr);
            }
        }
        agent.setAccessType(accessType);

        // 默认值：新注册的 Agent 在线状态为 OFFLINE
        if (agent.getOnlineStatus() == null) {
            agent.setOnlineStatus(com.helloai.common.constant.AgentOnlineStatus.OFFLINE);
        }

        // 3) labels（直接存储）
        Object labelsObj = body.get("labels");
        if (labelsObj instanceof Map<?, ?> rawLabels) {
            final Map<String, Object> labels = new java.util.HashMap<>();
            rawLabels.forEach((k, v) -> labels.put(String.valueOf(k), v));
            agent.setLabels(labels);
        }

        // 4) capabilities（默认值 + 调用方覆盖）
        Object capsObj = body.get("capabilities");
        final Map<String, Object> override;
        if (capsObj instanceof Map<?, ?> rawCaps) {
            Map<String, Object> tmp = new java.util.HashMap<>();
            rawCaps.forEach((k, v) -> tmp.put(String.valueOf(k), v));
            override = tmp;
        } else {
            override = null;
        }
        agent.setCapabilities(AgentCapability.mergeDefaults(accessType, override));

        // 5) skills（能力驱动）：显式传入优先；否则已有技能为空时按 accessType + 模型能力推导。
        // 白名单校验已在注册前完成（register / registerWithToken），此处只做推导与落库。
        List<String> explicitSkills = resolveExplicitSkills(body);
        if (explicitSkills != null) {
            // 先按模型能力校验（标准技能查白名单、自定义豁免、未识别模型放行），
            // 再按能力驱动落库（API_KEY_LLM + 已识别模型时 thinking 锁定不回退）
            agentService.validateAgentSkills(agent.getModelType(), explicitSkills);
            agent.setSkills(agentService.deriveSkillsForRegistration(agent, explicitSkills));
        } else if (agent.getSkills() == null || agent.getSkills().isEmpty()) {
            // 无显式技能：走能力驱动推导（未识别模型降级为基础推导）
            agent.setSkills(agentService.deriveSkillsForRegistration(agent, null));
        }

        // 持久化所有可选字段变更
        agentService.updateAgentExtras(agent);

        // 6) API_KEY_LLM：注册后核对平台凭证就绪性（尽力而为，不阻断注册）——执行链
        //    按 modelType 关联平台级凭证实时取 Key，内部 LLM Agent 不再持有
        //    AGENT 级密钥快照，系统管理轮换 API Key 后已注册 Agent 实时生效
        if (accessType == AgentAccessType.API_KEY_LLM) {
            llmProviderCatalogService.provisionPlatformCredential(agent);
        }
    }

    /**
     * 查询所有可用的 Provider 及其启用模型列表（供 Agent 注册时选择模型）。
     *
     * <p>供 Agent 注册/编辑弹窗调用，返回结构：[{providerCode, providerName, defaultModel, models: [modelName, ...]}]。
     * 仅返回 available=true 的 Provider，且只列出 enabled=1 的模型。</p>
     */
    @GetMapping("/listAvailableModels")
    public R<List<Map<String, Object>>> listAvailableModels() {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (LlmProviderCatalogService.ProviderCatalogItem item
                : llmProviderCatalogService.listProviders()) {
            if (!item.available()) {
                continue;
            }
            List<LlmProviderModel> models =
                    llmProviderModelQueryService.listEnabledByProviderCode(item.provider());
            if (models.isEmpty()) {
                continue;
            }
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("providerCode", item.provider());
            entry.put("providerName", item.providerName());
            entry.put("defaultModel", item.defaultModel());
            entry.put("models", models.stream().map(LlmProviderModel::getModelName).toList());
            result.add(entry);
        }
        return R.ok(result);
    }

    @GetMapping("/getMySkill")
    public R<Map<String, Object>> getMySkill(@RequestHeader("Authorization") String auth,
                                             HttpServletRequest request) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return R.fail(401, "认证失败");
        }
        String apiKey = auth.substring(7);
        Agent agent = agentService.getByApiKey(apiKey);
        if (agent == null) {
            return R.fail(401, "无效的 API Key");
        }

        String role = agent.getRole().name().toLowerCase();
        // 外网地址统一解析：sys_config（设置页可写）> yml > 请求推导 > localhost 兜底
        String baseUrl = agentBaseUrlResolver.resolve(request);

        // 从文件系统读取角色接入手册（onboarding/{role}/guide.md）
        try {
            String content = promptTemplateService.getSkillForAgent(
                    agent.getRole().name(), apiKey, baseUrl, agent.getName(), agent.getId());
            return R.ok(Map.of("role", role, "content", content));
        } catch (Exception e) {
            log.warn("获取角色接入手册失败，回退到文件: role={}", role, e);
        }

        // 文件兜底（jar 兼容）
        try {
            ClassPathResource resource = new ClassPathResource("onboarding/" + role + "/guide.md");
            if (!resource.exists()) {
                return R.fail("未找到 " + role + " 角色的接入手册");
            }
            String content;
            try (InputStream in = resource.getInputStream()) {
                content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            content = content.replace("<注册后填入>", apiKey);
            content = content.replace("{{BASE_URL}}", baseUrl);
            content = content.replace("<你的ID>", String.valueOf(agent.getId()));
            content = content.replace("<你的 ID>", String.valueOf(agent.getId()));

            return R.ok(Map.of(
                    "role", role,
                    "content", content
            ));
        } catch (IOException e) {
            log.error("读取角色接入手册（guide.md）失败", e);
            return R.fail("读取技能文件失败");
        }
    }

    private AgentResponse toResponse(Agent agent) {
        AgentResponse response = new AgentResponse();
        response.setId(agent.getId());
        response.setName(agent.getName());
        response.setRole(agent.getRole());
        response.setApiKey(agentApiKeyCipher.decrypt(agent.getApiKey()));
        response.setModelType(agent.getModelType());
        response.setModelConfig(agent.getModelConfig());
        response.setStatus(agent.getStatus());
        response.setScore(agent.getScore());
        response.setRemark(agent.getRemark());
        response.setCreateTime(agent.getCreateTime());
        response.setUpdateTime(agent.getUpdateTime());
        // 补全 + 三件套
        response.setAccessType(agent.getAccessType());
        response.setCapabilities(agent.getCapabilities());
        response.setLabels(agent.getLabels());
        response.setSkills(agent.getSkills());
        response.setLastSeenAt(agent.getLastSeenTime());
        response.setLastActiveAt(agent.getLastActiveTime());
        response.setOnlineStatus(agent.getOnlineStatus());
        response.setOfflineReason(agent.getOfflineReason());
        response.setOfflineAt(agent.getOfflineTime());
        return response;
    }

    private AgentRegistrationResponse toRegistrationResponse(Agent agent) {
        AgentRegistrationResponse response = new AgentRegistrationResponse();
        response.setId(agent.getId());
        response.setName(agent.getName());
        response.setRole(agent.getRole().name());
        response.setApiKey(agentApiKeyCipher.decrypt(agent.getApiKey()));
        response.setMessage("注册成功，请保存 API Key");
        return response;
    }
}
