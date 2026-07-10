package com.helloai.core.service;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.SubTask;
import com.helloai.core.event.SubTaskAssignedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;

/**
 * 子任务自动执行命令派发器。
 *
 * <p>当子任务进入 ASSIGNED 后，在事务提交后异步判断是否应生成执行命令。
 * 当前只对 {@link AgentAccessType#API_KEY_LLM} 创建 execution command；
 * CLI_CLIENT 仍走收件箱/MCP 拉取链路。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubTaskAutoExecutionDispatcher {

    private final AgentService agentService;
    private final SubTaskService subTaskService;
    private final ExecutionCommandService executionCommandService;
    private final TaskTimelineService taskTimelineService;

    private static Map<String, Object> safeMap(Object... keyValues) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key instanceof String keyString) {
                result.put(keyString, keyValues[i + 1]);
            }
        }
        return result;
    }

    // #region debug-point redispatch-stuck-blocked
    private static final ObjectMapper DBG_MAPPER = new ObjectMapper();
    private static final HttpClient DBG_HTTP = HttpClient.newHttpClient();
    private static volatile String DBG_URL;

    private static String dbgUrl() {
        if (DBG_URL != null) {
            return DBG_URL;
        }
        synchronized (SubTaskAutoExecutionDispatcher.class) {
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
            evt.put("ts", java.time.OffsetDateTime.now().toString());
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
    // #endregion debug-point redispatch-stuck-blocked

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssigned(SubTaskAssignedEvent event) {
        Agent agent = agentService.getById(event.getAgentId());
        if (agent == null) {
            log.warn("自动执行跳过：Agent 不存在, subTaskId={}, agentId={}",
                    event.getSubTaskId(), event.getAgentId());
            return;
        }
        if (agent.getAccessType() != AgentAccessType.API_KEY_LLM) {
            log.debug("自动执行跳过：accessType={}, subTaskId={}, agentId={}",
                    agent.getAccessType(), event.getSubTaskId(), event.getAgentId());
            return;
        }

        SubTask subTask = subTaskService.getById(event.getSubTaskId());
        if (subTask == null) {
            log.warn("自动执行跳过：子任务不存在, subTaskId={}", event.getSubTaskId());
            return;
        }

        dbg("sub_task_auto_execute_dispatch_enter", safeMap(
                "subTaskId", event.getSubTaskId(),
                "agentId", agent.getId(),
                "agentAccessType", agent.getAccessType() != null ? agent.getAccessType().name() : null,
                "subTaskStatus", subTask.getStatus() != null ? subTask.getStatus().name() : null
        ));

        taskTimelineService.recordEvent(
                subTask.getTaskId(),
                subTask.getId(),
                "sub_task_auto_execute_dispatch",
                AgentRole.SYSTEM,
                agent.getId(),
                Map.of("trigger", "assigned", "accessType", agent.getAccessType().name()));

        try {
            executionCommandService.createAssignedCommand(event.getSubTaskId(), agent.getId(), "assigned");
            log.info("执行命令派发成功: subTaskId={}, agentId={}", event.getSubTaskId(), agent.getId());
            dbg("sub_task_auto_execute_dispatch_ok", safeMap(
                    "subTaskId", event.getSubTaskId(),
                    "agentId", agent.getId()
            ));
        } catch (Exception e) {
            log.error("执行命令派发失败: subTaskId={}, agentId={}", event.getSubTaskId(), agent.getId(), e);
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            dbg("sub_task_auto_execute_dispatch_fail", safeMap(
                    "subTaskId", event.getSubTaskId(),
                    "agentId", agent.getId(),
                    "exception", e.getClass().getName(),
                    "message", e.getMessage(),
                    "rootException", root.getClass().getName(),
                    "rootMessage", root.getMessage()
            ));
        }
    }
}
