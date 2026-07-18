package com.helloai.api.controller;

import com.helloai.api.dto.agent.AgentRegistrationResponse;
import com.helloai.api.dto.agent.AgentResponse;
import com.helloai.common.base.R;
import com.helloai.common.config.AgentConfigProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.system.service.PromptTemplateService;
import com.helloai.core.util.AgentCapability;
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

    @GetMapping
    public R<List<AgentResponse>> list() {
        return R.ok(agentService.list().stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public R<AgentResponse> getById(@PathVariable("id") Long id) {
        Agent agent = agentService.getById(id);
        if (agent == null) {
            return R.fail("Agent 不存在");
        }
        return R.ok(toResponse(agent));
    }

    @PostMapping("/register")
    public R<AgentRegistrationResponse> register(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        AgentRole role = AgentRole.valueOf(((String) body.get("role")).toUpperCase());
        String description = (String) body.getOrDefault("description", "");
        Agent agent = agentService.register(name, role, description);
        applyRegistrationExtras(agent, body);
        log.info("Agent 手动注册成功: name={}, role={}, id={}", name, role, agent.getId());
        return R.ok(toRegistrationResponse(agent));
    }

    @PostMapping("/register-with-token")
    public R<AgentRegistrationResponse> registerWithToken(@RequestBody Map<String, Object> body) {
        if (!agentConfig.isAllowRegistration()) {
            return R.fail(403, "Agent 自注册已关闭，请联系管理员创建");
        }

        String token = (String) body.get("registrationToken");
        if (token == null || !token.equals(agentConfig.getRegistrationToken())) {
            return R.fail(403, "注册令牌无效");
        }

        String name = (String) body.get("name");
        String roleStr = (String) body.get("role");
        String description = (String) body.getOrDefault("description", "");
        AgentRole role = AgentRole.valueOf(roleStr.toUpperCase());
        Agent agent = agentService.register(name, role, description);
        applyRegistrationExtras(agent, body);

        log.info("Agent 自助注册成功: name={}, role={}, id={}, accessType={}",
                name, role, agent.getId(), agent.getAccessType());
        return R.ok(toRegistrationResponse(agent));
    }

    /**
     * 处理注册入参的可选扩展字段：accessType / specializationSlug / capabilities / labels。
     *
     * <p>accessType 默认 CLI_CLIENT；capabilities 按 accessType 默认值填充后允许调用方覆盖；
     * labels 直接存储。</p>
     */
    @SuppressWarnings("unchecked")
    private void applyRegistrationExtras(Agent agent, Map<String, Object> body) {
        // 1) specializationSlug（已有逻辑）
        String specializationSlug = (String) body.get("specializationSlug");
        if (specializationSlug != null && !specializationSlug.isBlank()) {
            agent.setSpecializationSlug(specializationSlug);
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

        // 阶段 0 默认值：新注册的 Agent 在线状态为 OFFLINE
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

        // 持久化所有可选字段变更
        agentService.updateById(agent);
    }

    @GetMapping("/me/skill")
    public R<Map<String, Object>> getSkill(@RequestHeader("Authorization") String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return R.fail(401, "认证失败");
        }
        String apiKey = auth.substring(7);
        Agent agent = agentService.getByApiKey(apiKey);
        if (agent == null) {
            return R.fail(401, "无效的 API Key");
        }

        String role = agent.getRole().name().toLowerCase();
        String baseUrl = agentConfig.getBaseUrl() != null && !agentConfig.getBaseUrl().isBlank()
                ? agentConfig.getBaseUrl() : "http://localhost:6565";

        // 从文件系统读取 SKILL 内容
        try {
            String content = promptTemplateService.getSkillForAgent(
                    agent.getRole().name(), apiKey, baseUrl, agent.getName(), agent.getId());
            return R.ok(Map.of("role", role, "content", content));
        } catch (Exception e) {
            log.warn("获取 SKILL 失败，回退到文件: role={}", role, e);
        }

        // 文件兜底（jar 兼容）
        try {
            ClassPathResource resource = new ClassPathResource("skills/" + role + "/SKILL.md");
            if (!resource.exists()) {
                return R.fail("未找到 " + role + " 角色的 SKILL.md");
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
            log.error("读取 SKILL.md 失败", e);
            return R.fail("读取技能文件失败");
        }
    }

    private AgentResponse toResponse(Agent agent) {
        AgentResponse response = new AgentResponse();
        response.setId(agent.getId());
        response.setName(agent.getName());
        response.setRole(agent.getRole());
        response.setApiKey(agent.getApiKey());
        response.setModelType(agent.getModelType());
        response.setModelConfig(agent.getModelConfig());
        response.setSpecializationSlug(agent.getSpecializationSlug());
        response.setStatus(agent.getStatus());
        response.setScore(agent.getScore());
        response.setRemark(agent.getRemark());
        response.setCreateTime(agent.getCreateTime());
        response.setUpdateTime(agent.getUpdateTime());
        // 阶段 0 补全 + 阶段 4 三件套
        response.setAccessType(agent.getAccessType());
        response.setCapabilities(agent.getCapabilities());
        response.setLabels(agent.getLabels());
        response.setLastSeenAt(agent.getLastSeenAt());
        response.setLastActiveAt(agent.getLastActiveAt());
        response.setOnlineStatus(agent.getOnlineStatus());
        response.setOfflineReason(agent.getOfflineReason());
        response.setOfflineAt(agent.getOfflineAt());
        return response;
    }

    private AgentRegistrationResponse toRegistrationResponse(Agent agent) {
        AgentRegistrationResponse response = new AgentRegistrationResponse();
        response.setId(agent.getId());
        response.setName(agent.getName());
        response.setRole(agent.getRole().name());
        response.setApiKey(agent.getApiKey());
        response.setMessage("注册成功，请保存 API Key");
        return response;
    }
}
