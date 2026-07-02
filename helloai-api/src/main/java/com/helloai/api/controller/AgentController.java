package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.entity.Agent;
import com.helloai.core.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @GetMapping
    public R<List<Agent>> list() {
        return R.ok(agentService.list());
    }

    @GetMapping("/{id}")
    public R<Agent> getById(@PathVariable Long id) {
        Agent agent = agentService.getById(id);
        if (agent == null) {
            return R.fail("Agent 不存在");
        }
        return R.ok(agent);
    }

    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        AgentRole role = AgentRole.valueOf(body.get("role").toUpperCase());
        String description = body.getOrDefault("description", "");
        Agent agent = agentService.register(name, role, description);
        return R.ok(Map.of(
                "id", agent.getId(),
                "name", agent.getName(),
                "role", agent.getRole().name(),
                "apiKey", agent.getApiKey(),
                "message", "注册成功，请保存 API Key"
        ));
    }

    @GetMapping("/me/skill")
    public R<String> getSkill(@RequestHeader("Authorization") String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) {
            return R.fail(401, "认证失败");
        }
        String apiKey = auth.substring(7);
        Agent agent = agentService.getByApiKey(apiKey);
        if (agent == null) {
            return R.fail(401, "无效的 API Key");
        }
        // TODO: 返回角色对应的 SKILL.md
        return R.ok("SKILL.md for " + agent.getRole().name());
    }
}
