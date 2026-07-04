package com.helloai.api.controller;

import com.helloai.api.dto.agent.AgentRegistrationResponse;
import com.helloai.api.dto.agent.AgentResponse;
import com.helloai.common.base.R;
import com.helloai.common.config.AgentConfigProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.entity.Agent;
import com.helloai.core.service.AgentService;
import com.helloai.core.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    public R<AgentResponse> getById(@PathVariable Long id) {
        Agent agent = agentService.getById(id);
        if (agent == null) {
            return R.fail("Agent 不存在");
        }
        return R.ok(toResponse(agent));
    }

    @PostMapping("/register")
    public R<AgentRegistrationResponse> register(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        AgentRole role = AgentRole.valueOf(body.get("role").toUpperCase());
        String description = body.getOrDefault("description", "");
        Agent agent = agentService.register(name, role, description);
        return R.ok(toRegistrationResponse(agent));
    }

    @PostMapping("/register-with-token")
    public R<AgentRegistrationResponse> registerWithToken(@RequestBody Map<String, String> body) {
        if (!agentConfig.isAllowRegistration()) {
            return R.fail(403, "Agent 自注册已关闭，请联系管理员创建");
        }

        String token = body.get("registrationToken");
        if (token == null || !token.equals(agentConfig.getRegistrationToken())) {
            return R.fail(403, "注册令牌无效");
        }

        String name = body.get("name");
        String roleStr = body.get("role");
        String description = body.getOrDefault("description", "");
        AgentRole role = AgentRole.valueOf(roleStr.toUpperCase());
        Agent agent = agentService.register(name, role, description);

        log.info("Agent 自助注册成功: name={}, role={}, id={}", name, role, agent.getId());
        return R.ok(toRegistrationResponse(agent));
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

        // v1.1: DB 优先 — 从 prompt_template 表获取 SKILL 内容
        try {
            String content = promptTemplateService.getSkillForAgent(
                    agent.getRole().name(), apiKey, baseUrl, agent.getName());
            return R.ok(Map.of("role", role, "content", content));
        } catch (Exception e) {
            log.warn("从 DB 获取 SKILL 失败，回退到文件: role={}", role, e);
        }

        // 文件兜底
        try {
            ClassPathResource resource = new ClassPathResource("skills/" + role + "/SKILL.md");
            if (!resource.exists()) {
                return R.fail("未找到 " + role + " 角色的 SKILL.md");
            }
            String content = Files.readString(resource.getFile().toPath(), StandardCharsets.UTF_8);
            content = content.replace("<注册后填入>", apiKey);
            content = content.replace("{{BASE_URL}}", baseUrl);

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
        response.setStatus(agent.getStatus());
        response.setScore(agent.getScore());
        response.setRemark(agent.getRemark());
        response.setCreateTime(agent.getCreateTime());
        response.setUpdateTime(agent.getUpdateTime());
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
