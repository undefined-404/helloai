package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.executor.AgentExecutor;
import com.helloai.core.agent.executor.AgentExecutorRouter;
import com.helloai.core.agent.entity.Agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import com.helloai.core.agent.service.AgentService;

/**
 * 平台内 Agent 执行入口。
 *
 * <p>T3 先作为统一 service 入口，避免未来把执行编排逻辑散落到 Controller / MQ consumer 中。</p>
 */
@Service
@RequiredArgsConstructor
public class PlatformAgentExecutionServiceImpl implements PlatformAgentExecutionService {

    private final AgentService agentService;
    private final AgentExecutorRouter agentExecutorRouter;
    private final HeartbeatService heartbeatService;
    private final AgentExecutionProperties executionProperties;

    // #region debug-point redispatch-stuck-blocked
    private static final ObjectMapper DBG_MAPPER = new ObjectMapper();
    private static final HttpClient DBG_HTTP = HttpClient.newHttpClient();
    private static volatile String DBG_URL;

    private static String dbgUrl() {
        if (DBG_URL != null) {
            return DBG_URL;
        }
        synchronized (PlatformAgentExecutionService.class) {
            if (DBG_URL != null) {
                return DBG_URL;
            }
            String envUrl = System.getenv("DEBUG_SERVER_URL");
            if (envUrl != null && !envUrl.isBlank()) {
                DBG_URL = envUrl;
                return DBG_URL;
            }
            try {
                Path envFile = Path.of(".dbg", "redispatch-stuck-blocked.env");
                if (Files.exists(envFile)) {
                    for (String line : Files.readAllLines(envFile)) {
                        if (line.startsWith("DEBUG_SERVER_URL=")) {
                            String url = line.substring("DEBUG_SERVER_URL=".length()).trim();
                            if (!url.isBlank()) {
                                DBG_URL = url;
                                return DBG_URL;
                            }
                        }
                    }
                }
            } catch (Exception ignore) {
            }
            return null;
        }
    }

    private static void dbg(String point, Map<String, Object> data) {
        String url = dbgUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            Map<String, Object> evt = new HashMap<>();
            evt.put("sessionId", "redispatch-stuck-blocked");
            evt.put("point", point);
            evt.put("ts", OffsetDateTime.now().toString());
            evt.put("data", data != null ? data : Map.of());
            String body = DBG_MAPPER.writeValueAsString(evt);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            DBG_HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignore) {
        }
    }

    /** 埋点参数构造：允许 null 值（Map.of 遇 null 会 NPE，拆解等无 subTaskId 场景会踩中）。 */
    private static Map<String, Object> dbgMap(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
    // #endregion debug-point redispatch-stuck-blocked

    /**
     * 按 agentId 路由并执行。
     */
    public AgentResult execute(Long agentId, AgentTask task) {
        Agent agent = agentService.getById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
        return execute(agent, task);
    }

    /**
     * 按 Agent 实体路由并执行。
     */
    public AgentResult execute(Agent agent, AgentTask task) {
        dbg("platform_execute_enter", dbgMap(
                "agentId", agent != null ? agent.getId() : null,
                "subTaskId", task != null ? task.getSubTaskId() : null
        ));
        AgentExecutor executor = agentExecutorRouter.route(agent);
        dbg("platform_execute_routed", dbgMap(
                "agentId", agent.getId(),
                "subTaskId", task.getSubTaskId(),
                "executor", executor.getName()
        ));
        if (!executor.checkCapability(agent, task.getRequiredCapabilities())) {
            throw new BizException("Agent 能力不足: agentId=" + agent.getId()
                    + ", executor=" + executor.getName());
        }
        dbg("platform_execute_before_active", dbgMap(
                "agentId", agent.getId(),
                "subTaskId", task.getSubTaskId()
        ));
        heartbeatService.active(agent.getId());
        dbg("platform_execute_after_active", dbgMap(
                "agentId", agent.getId(),
                "subTaskId", task.getSubTaskId()
        ));
        dbg("platform_execute_before_executor", dbgMap(
                "agentId", agent.getId(),
                "subTaskId", task.getSubTaskId()
        ));
        return executor.execute(agent, task);
    }

    /**
     * 同步执行，便于最小验证入口和测试使用。
     */
    public AgentResult executeSync(Agent agent, AgentTask task) {
        return execute(agent, task);
    }

    /**
     * 同步执行，按 agentId 路由。
     */
    public AgentResult executeSync(Long agentId, AgentTask task) {
        return execute(agentId, task);
    }
}
