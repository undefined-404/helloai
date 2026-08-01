package com.helloai.core.agent.execution;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.SubTask;
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
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.spec.TaskRunningSpecService;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskExecutionService {

    /**
     * 子任务执行服务。
     *
     * <p>职责划分（对齐架构设计参考 §3.1 调度分离）：</p>
     * <ul>
     *     <li>{@link #executeCommand(ExecutionCommand)}：完整编排入口，含参数校验、加载、状态推进、执行、回写。</li>
     *     <li>{@link #executeOnce(SubTask, Agent)}：纯执行入口，只负责组装 AgentTask + 调平台执行器 + 观测 timeline。</li>
     *     <li>{@link #startIfNeeded(Long, SubTaskStatus)}：状态推进前置，允许消费者在调 {@link #executeOnce} 之前调用。</li>
     * </ul>
     *
     * <p>消费者（LocalExecutionCommandConsumer 或未来 MQ/DB poller）可以组合调用
     * {@code startIfNeeded + executeOnce + ExecutionResultHandler.handleSuccess/Failure} 实现分层，
     * 也可以直接调用 {@link #executeCommand(ExecutionCommand)} 拿完整链路（向后兼容入口）。</p>
     */

    private final SubTaskService subTaskService;
    private final AgentService agentService;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final ExecutionResultHandler executionResultHandler;
    private final TaskRunningSpecService taskRunningSpecService;

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
     * <p>完整编排：参数校验 → 加载 subTask + agent → 一致性校验 → 状态推进 → 纯执行 → 结果回写。
     * 本入口保留向后兼容，面向「外部 API 层直接调用」或「未分层消费者」场景；
     * 已经分层的消费者（如 LocalExecutionCommandConsumer）应组合调用
     * {@link #startIfNeeded} + {@link #executeOnce} + ExecutionResultHandler 实现完整链。</p>
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
        if (subTask.getAssignedAgentId() == null) {
            throw new BizException("子任务未分配 Agent: " + command.getSubTaskId());
        }

        if (!command.getAgentId().equals(subTask.getAssignedAgentId())) {
            throw new BizException("命令 Agent 与子任务分配 Agent 不匹配: command="
                    + command.getAgentId() + ", assigned=" + subTask.getAssignedAgentId());
        }

        Agent agent = agentService.getById(subTask.getAssignedAgentId());
        if (agent == null) {
            throw new BizException("Agent 不存在: " + subTask.getAssignedAgentId());
        }

        // 状态推进前置
        startIfNeeded(subTask.getId(), subTask.getStatus());

        try {
            AgentResult result = executeOnce(subTask, agent);
            executionResultHandler.handleSuccess(command.getSubTaskId(), command.getAgentId(), result);
            return result;
        } catch (Exception e) {
            executionResultHandler.handleFailure(command.getSubTaskId(), command.getAgentId(), e);
            throw e;
        }
    }

    /**
     * 纯执行入口。
     *
     * <p>负责：状态守卫、组装 AgentTask、调平台执行器、记录 LLM 调用 timeline。
     * 不做：状态推进（{@link #startIfNeeded}）、结果回写（由调用方负责）。</p>
     *
     * <p>设计参考 §3.1 调度分离：执行层只负责消费命令 + 执行 + 回传原始结果，
     * 不再携带状态机推进与回写职责，让 {@link LocalExecutionCommandConsumer}
     * 或未来 MQ/DB poller 消费者统一拿到「调度 + 回写」两端能力。</p>
     */
    public AgentResult executeOnce(SubTask subTask, Agent agent) {
        Long subTaskId = subTask.getId();

        if (subTask.getStatus() == SubTaskStatus.DONE || subTask.getStatus() == SubTaskStatus.CANCELLED) {
            throw new BizException("子任务不可执行: status=" + subTask.getStatus());
        }

        dbg("sub_task_execute_enter", safeMap(
                "subTaskId", subTaskId,
                "status", subTask.getStatus() != null ? subTask.getStatus().name() : null,
                "assignedAgent", subTask.getAssignedAgentId(),
                "agentAccessType", agent.getAccessType() != null ? agent.getAccessType().name() : null,
                "agentOnlineStatus", agent.getOnlineStatus() != null ? agent.getOnlineStatus().name() : null
        ));

        // Task Running Spec 上下文装配：从 task.context JSONB 读取结构化运行态文档，
        // 替代 V35 原始产出注入，避免噪声污染下游 executor 上下文
        String promptSection = taskRunningSpecService.buildExecutorPromptSection(subTask.getTaskId());
        Map<String, Object> context = new HashMap<>();
        context.put("taskId", subTask.getTaskId());
        context.put("subTaskId", subTaskId);
        AgentTask task = AgentTask.builder()
                .subTaskId(subTaskId)
                .systemPrompt("")
                .userPrompt(buildUserPrompt(subTask, promptSection))
                .context(context)
                .requiredCapabilities(Map.of())
                .build();
        dbg("sub_task_execute_before_platform", safeMap(
                "subTaskId", subTaskId,
                "agentId", agent.getId()
        ));
        // Task Running Spec 上下文装配可观测：有上下文段时记录 timeline 事件
        if (promptSection != null && !promptSection.isBlank()) {
            taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId, "sub_task_spec_context_loaded",
                    AgentRole.EXECUTOR, agent.getId(),
                    Map.of("agentId", agent.getId()));
        }
        taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId, "sub_task_llm_call_start",
                AgentRole.EXECUTOR, agent.getId(),
                Map.of("agentId", agent.getId(), "agentName", agent.getName()));
        AgentResult result = platformAgentExecutionService.executeSync(agent, task);
        taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId, "sub_task_llm_call_end",
                AgentRole.EXECUTOR, agent.getId(),
                safeMap("agentId", agent.getId(), "success", result.isSuccess(),
                        "finishReason", result.getFinishReason(),
                        "tokens", result.getTokenUsage()));
        dbg("sub_task_execute_success", safeMap(
                "subTaskId", subTaskId,
                "agentId", agent.getId(),
                "success", result.isSuccess(),
                "executor", result.getExecutorName(),
                "finishReason", result.getFinishReason()
        ));
        return result;
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
        return buildUserPrompt(subTask, "");
    }

    /**
     * 组装执行 Prompt：任务全局上下文 + 当前子任务四要素 + 回填要求。
     *
     * <p>全局上下文来自 Task Running Spec（Baseline + ContextSummary + 前置任务摘要），
     * 替代 V35 原始产出注入。回填要求指导 executor 按 EXECUTION_RECORD 协议输出
     * 结构化摘要，供后续下游 executor 消费。</p>
     */
    private String buildUserPrompt(SubTask subTask, String runningSpecSection) {
        StringBuilder sb = new StringBuilder();

        // 任务全局上下文（Task Running Spec）
        if (runningSpecSection != null && !runningSpecSection.isBlank()) {
            sb.append(runningSpecSection);
            sb.append("\n---\n\n");
        }

        // 当前子任务四要素
        sb.append("## 当前任务\n");
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

        // 回填要求（EXECUTION_RECORD 协议）
        sb.append("\n---\n\n");
        sb.append("## 产出回填要求\n");
        sb.append("请在完成交付物输出后，在输出的最后附上以下结构化回填块：\n\n");
        sb.append("```\n");
        sb.append("## EXECUTION_RECORD\n");
        sb.append("SUMMARY: <1-2句核心产出描述>\n");
        sb.append("KEY_DECISIONS:\n");
        sb.append("- <关键决策1>\n");
        sb.append("DOWNSTREAM_NOTES:\n");
        sb.append("- <下游子任务需要注意的事项>\n");
        sb.append("DELIVERABLES:\n");
        sb.append("- <产出文件路径>\n");
        sb.append("```\n");

        return sb.toString();
    }

}
