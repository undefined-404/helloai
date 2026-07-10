package com.helloai.core.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.SubTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskExecutionService {

    private final SubTaskService subTaskService;
    private final AgentService agentService;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final ExecutionResultHandler executionResultHandler;

    // #region debug-point redispatch-stuck-blocked
    private static final ObjectMapper DBG_MAPPER = new ObjectMapper();
    private static final HttpClient DBG_HTTP = HttpClient.newHttpClient();
    private static volatile String DBG_URL;

    private static String dbgUrl() {
        if (DBG_URL != null) {
            return DBG_URL;
        }
        synchronized (SubTaskExecutionService.class) {
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
    // #endregion debug-point redispatch-stuck-blocked

    /**
     * 按执行命令执行子任务——系统唯一公共执行入口。
     *
     * <p>一次加载 SubTask + Agent 并校验，消除二次读库 TOCTOU 窗口。</p>
     */
    public AgentResult executeCommand(ExecutionCommand command) {
        if (command == null) {
            throw new BizException("执行命令不能为空");
        }
        if (command.getSubTaskId() == null) {
            throw new BizException("执行命令缺少 subTaskId");
        }
        if (command.getAgentId() == null) {
            throw new BizException("执行命令缺少 agentId");
        }

        // 一次加载，一次校验（消除 TOCTOU）
        SubTask subTask = subTaskService.getById(command.getSubTaskId());
        if (subTask == null) {
            throw new BizException("子任务不存在: " + command.getSubTaskId());
        }
        if (subTask.getAssignedAgent() == null) {
            throw new BizException("子任务未分配 Agent: " + command.getSubTaskId());
        }

        if (!command.getAgentId().equals(subTask.getAssignedAgent())) {
            throw new BizException("命令 Agent 与子任务分配 Agent 不匹配: command="
                    + command.getAgentId() + ", assigned=" + subTask.getAssignedAgent());
        }

        Agent agent = agentService.getById(subTask.getAssignedAgent());
        if (agent == null) {
            throw new BizException("Agent 不存在: " + subTask.getAssignedAgent());
        }

        return executeOnce(subTask, agent);
    }

    private AgentResult executeOnce(SubTask subTask, Agent agent) {
        Long subTaskId = subTask.getId();

        if (subTask.getStatus() == SubTaskStatus.DONE || subTask.getStatus() == SubTaskStatus.CANCELLED) {
            throw new BizException("子任务不可执行: status=" + subTask.getStatus());
        }

        dbg("sub_task_execute_enter", safeMap(
                "subTaskId", subTaskId,
                "status", subTask.getStatus() != null ? subTask.getStatus().name() : null,
                "assignedAgent", subTask.getAssignedAgent(),
                "agentAccessType", agent.getAccessType() != null ? agent.getAccessType().name() : null,
                "agentOnlineStatus", agent.getOnlineStatus() != null ? agent.getOnlineStatus().name() : null
        ));

        startIfNeeded(subTaskId, subTask.getStatus());
        taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId, "sub_task_execute_start",
                AgentRole.EXECUTOR, agent.getId(), Map.of("executor", "platform"));

        try {
            Map<String, Object> context = new HashMap<>();
            context.put("taskId", subTask.getTaskId());
            context.put("subTaskId", subTaskId);
            AgentTask task = AgentTask.builder()
                    .subTaskId(subTaskId)
                    .systemPrompt("")
                    .userPrompt(buildUserPrompt(subTask))
                    .context(context)
                    .requiredCapabilities(Map.of())
                    .build();
            dbg("sub_task_execute_before_platform", safeMap(
                    "subTaskId", subTaskId,
                    "agentId", agent.getId()
            ));
            AgentResult result = platformAgentExecutionService.executeSync(agent, task);
            executionResultHandler.handleSuccess(subTaskId, agent.getId(), result);
            dbg("sub_task_execute_success", safeMap(
                    "subTaskId", subTaskId,
                    "agentId", agent.getId(),
                    "success", result.isSuccess(),
                    "executor", result.getExecutorName(),
                    "finishReason", result.getFinishReason()
            ));
            return result;
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            dbg("sub_task_execute_exception", safeMap(
                    "subTaskId", subTaskId,
                    "agentId", agent.getId(),
                    "exception", e.getClass().getName(),
                    "message", e.getMessage(),
                    "rootException", root.getClass().getName(),
                    "rootMessage", root.getMessage()
            ));
            executionResultHandler.handleFailure(subTaskId, agent.getId(), e);
            throw e;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void startIfNeeded(Long subTaskId, SubTaskStatus status) {
        if (status == SubTaskStatus.IN_PROGRESS) {
            return;
        }
        if (status == SubTaskStatus.ASSIGNED || status == SubTaskStatus.REWORK || status == SubTaskStatus.PAUSED) {
            subTaskService.start(subTaskId);
            return;
        }
        throw new BizException("子任务状态不允许执行: subTaskId=" + subTaskId + ", status=" + status);
    }

    private String buildUserPrompt(SubTask subTask) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务标题: ").append(subTask.getTitle()).append("\n");
        if (subTask.getContent() != null && !subTask.getContent().isBlank()) {
            sb.append("任务描述: ").append(subTask.getContent()).append("\n");
        }
        if (subTask.getDeliverable() != null && !subTask.getDeliverable().isBlank()) {
            sb.append("交付物要求: ").append(subTask.getDeliverable()).append("\n");
        }
        if (subTask.getAcceptance() != null && !subTask.getAcceptance().isBlank()) {
            sb.append("验收标准: ").append(subTask.getAcceptance()).append("\n");
        }
        sb.append("请输出交付结果，尽量结构化。");
        return sb.toString();
    }
}
