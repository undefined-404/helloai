package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.service.SubTaskExecutionService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.spec.ExecutionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.helloai.core.agent.command.ExecutionResultHandler;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConversationService;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.service.PluginSkillSpecService;
import com.helloai.core.agent.quality.service.AgentQualityProfileService;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskExecutionServiceImpl implements SubTaskExecutionService {

    /**
     * 单条前置产出注入的字符上限；超出部分截断并显式标注，避免依赖产出无限膨胀挤占 token。
     */
    private static final int DEP_CONTENT_MAX_CHARS = 4000;

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
    private final PluginSkillSpecService pluginSkillSpecService;
    private final ConversationService conversationService;
    private final AttachmentService attachmentService;
    private final AgentQualityProfileService agentQualityProfileService;

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
                // best-effort：调试配置读取失败即放弃，不影响主链路
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
            // best-effort：调试上报失败忽略，不影响执行链路
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

        // 1) 全局段 = Baseline（总体目标/平台约束）+ ContextSummary（全局进度），来源 task.context JSONB / 独立表
        // 2) 插件规范段 = 任务 required_skills 命中 eng-* 规范库标签时注入「执行速览」
        // 3) 历史表现段 = 执行者画像摘要（反馈回路第 2 层）：画像缺失/查询失败返回空串，
        //    零注入零阻断（N18 P1 同款哲学；renderHistorySection 内部已防御，此处再兜底防扩散）
        // 4) 依赖段 = 直接前置（dependsOnIdList）的结构化摘要 + 完成内容本体（物化附件优先、原始产出回退）
        //    综合注入供 LLM 结合"前置做了什么 + 本轮任务要求"分析执行
        String promptSection = taskRunningSpecService.buildExecutorPromptSection(subTask.getTaskId());
        String pluginSection = pluginSkillSpecService.renderSection(subTask.getTaskId());
        promptSection = mergeSpecSections(promptSection, pluginSection);
        String historySection = renderHistorySectionSafely(agent);
        promptSection = mergeSpecSections(promptSection, historySection);
        DependencySectionResult dependencySection = buildDependencySection(subTask);
        Map<String, Object> context = new HashMap<>();
        context.put("taskId", subTask.getTaskId());
        context.put("subTaskId", subTaskId);
        AgentTask task = AgentTask.builder()
                .subTaskId(subTaskId)
                .systemPrompt("")
                .userPrompt(buildUserPrompt(subTask, promptSection, dependencySection.section))
                .context(context)
                .requiredCapabilities(Map.of())
                .build();
        dbg("sub_task_execute_before_platform", safeMap(
                "subTaskId", subTaskId,
                "agentId", agent.getId()
        ));
        // Task Running Spec 上下文装配可观测：无论有无依赖均记录一次装配事实与统计
        taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId, "sub_task_spec_context_loaded",
                AgentRole.EXECUTOR, agent.getId(),
                safeMap("agentId", agent.getId(),
                        "depCount", dependencySection.depCount,
                        "loadedCount", dependencySection.loadedCount,
                        "truncatedCount", dependencySection.truncatedCount,
                        "degraded", dependencySection.degraded,
                        "pluginSpec", pluginSection != null && !pluginSection.isBlank(),
                        "historySummary", historySection != null && !historySection.isBlank()));
        taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId, "sub_task_llm_call_start",
                AgentRole.EXECUTOR, agent.getId(),
                Map.of("agentId", agent.getId(), "agentName", agent.getName()));
        // §6.41 执行对话流 user 视角落库：实际送给 LLM 的 prompt 全量入 conversation_message
        // 失败路径（executeSync 抛异常）prompt 已保留，与 ExecutionResultHandler 写 sub_task_execute_failed 互补
        try {
            conversationService.addMessage(subTaskId, agent.getId(),
                    "user", "agent",
                    task.getUserPrompt(),
                    "sub_task_execute_user_prompt");
        } catch (Exception e) {
            log.warn("执行请求对话流写入失败（不阻断主链路）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }
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

    /**
     * 合并 Task Running Spec 全局段与平台技能规范段：
     * 插件段为空时原样返回全局段；全局段为空时返回插件段；两者皆有按空行拼接。
     */
    private String mergeSpecSections(String specSection, String pluginSection) {
        boolean pluginBlank = pluginSection == null || pluginSection.isBlank();
        boolean specBlank = specSection == null || specSection.isBlank();
        if (pluginBlank) {
            return specSection;
        }
        if (specBlank) {
            return pluginSection;
        }
        return specSection + "\n\n" + pluginSection;
    }

    /**
     * 历史表现段安全渲染（反馈回路第 2 层）：画像查询/渲染任何异常都降级为空串，
     * 绝不让执行主链路被画像副链路拖死（N18 P1 同款哲学 + §6.128 best-effort 教训）。
     */
    private String renderHistorySectionSafely(Agent agent) {
        try {
            return agentQualityProfileService.renderHistorySection(agent.getId());
        } catch (Exception e) {
            log.debug("历史表现段渲染失败（best-effort 降级，零注入）: agentId={}, err={}",
                    agent.getId(), e.getMessage());
            return "";
        }
    }

    /**
     * 组装执行 Prompt：任务全局上下文 + 依赖产出参考（直接前置）+ 当前子任务四要素 + 回填要求。
     *
     * <p>全局上下文来自 Task Running Spec（Baseline + ContextSummary）；依赖产出参考由
     * {@link #buildDependencySection} 按 dependsOnIdList 收集直接前置的结构化摘要与内容本体，
     * 供 LLM 将"前置已完成的内容"与"本轮任务要求"综合分析后执行。回填要求指导 executor
     * 按 EXECUTION_RECORD 协议输出结构化摘要，供后续下游 executor 消费。</p>
     */
    private String buildUserPrompt(SubTask subTask, String runningSpecSection, String dependencySection) {
        StringBuilder sb = new StringBuilder();

        // 任务全局上下文（Task Running Spec）
        if (runningSpecSection != null && !runningSpecSection.isBlank()) {
            sb.append(runningSpecSection);
            sb.append("\n---\n\n");
        }

        // 依赖产出参考（直接前置）：有依赖才注入，无依赖零注入
        if (dependencySection != null && !dependencySection.isBlank()) {
            sb.append(dependencySection);
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

        // 返工上下文：上次提交被审核驳回，需参考驳回意见修正
        appendReworkContext(sb, subTask);

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

        // 可选：LLM manifest 多文件产出协议（方案3）——命中时多文件物化，未命中降级纯文本单 .md，零影响
        sb.append("\n你也可以选择用如下 JSON 结构返回多文件产出（放在 ```json 代码块中，位于 EXECUTION_RECORD 块之前）：\n\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"summary\": \"本次产出的简要说明\",\n");
        sb.append("  \"files\": [\n");
        sb.append("    { \"name\": \"README.md\", \"type\": \"text/markdown\", \"content\": \"...\" },\n");
        sb.append("    { \"name\": \"main.py\", \"type\": \"text/x-python\", \"content\": \"...\" }\n");
        sb.append("  ]\n");
        sb.append("}\n");
        sb.append("```\n");
        sb.append("若无需拆分文件，直接输出正文即可；若选择该结构，请把正文按文件拆分放入 files，"
                + "summary 概括本次产出，并在文件概览后保留 EXECUTION_RECORD 回填块。\n");

        return sb.toString();
    }

    /**
     * 返工上下文注入：从 {@code sub_task.context.reviewHistory}（List<Map>）按轮次铺开
     * REVIEWER 历史审核意见，作为修正指引注入 Prompt。
     *
     * <p>兼容 回填前的过渡期：旧单 Map 形态的 {@code lastAutoReview} 仍可读取，
     * 保证新老子任务的 prompt 拼接都不中断。{@code executorDoneIssues} 字段预留读取
     * 但本轮不主动写入（执行回填 hook 留待后续轮次）。</p>
     */
    @SuppressWarnings("unchecked")
    private void appendReworkContext(StringBuilder sb, SubTask subTask) {
        Map<String, Object> ctx = subTask.getContext();
        if (ctx == null) {
            return;
        }
        // 优先读 reviewHistory（List），缺失时回退到 lastAutoReview（Map）作单轮兼容
        Object historyObj = ctx.get("reviewHistory");
        List<?> history = null;
        if (historyObj instanceof List<?> historyList && !historyList.isEmpty()) {
            history = historyList;
        } else if (ctx.get("lastAutoReview") instanceof Map<?, ?> legacy) {
            history = List.of(legacy);
        }
        if (history == null) {
            return;
        }

        sb.append("\n---\n\n");
        sb.append("## 返工修正指引（共 ").append(history.size()).append(" 轮历史审核）\n");
        sb.append("你之前提交被 REVIEWER 多次驳回，请按以下历史审核意见逐轮修正：\n\n");

        int round = 1;
        for (Object item : history) {
            if (!(item instanceof Map<?, ?> review)) {
                continue;
            }
            sb.append("### 第 ").append(round++).append(" 轮\n");
            Object ts = review.get("ts");
            if (ts instanceof String tsStr && !tsStr.isBlank()) {
                sb.append("- 时间: ").append(tsStr).append("\n");
            }
            Object issues = review.get("issues");
            if (issues instanceof List<?> issueList && !issueList.isEmpty()) {
                sb.append("- 审核问题:\n");
                for (Object issue : issueList) {
                    sb.append("  - ").append(issue).append("\n");
                }
            } else if (issues instanceof String issueStr && !issueStr.isBlank()) {
                // 兼容旧 lastAutoReview.issues (String 形态)
                sb.append("- 审核问题: ").append(issueStr).append("\n");
            }
            Object comment = review.get("comment");
            if (comment instanceof String commentStr && !commentStr.isBlank()) {
                sb.append("- 审核评语: ").append(commentStr).append("\n");
            }
            Object score = review.get("score");
            if (score instanceof Number n) {
                sb.append("- 审核评分: ").append(n.intValue()).append(" / 5\n");
            }
            // 预留字段：executorDoneIssues 本轮不主动写，仅读
            Object done = review.get("executorDoneIssues");
            if (done instanceof List<?> doneList && !doneList.isEmpty()) {
                String joined = doneList.stream()
                        .map(Object::toString)
                        .collect(Collectors.joining("、"));
                sb.append("- 上一轮你已自认修复: ").append(joined).append("\n");
            }
            sb.append("\n");
        }

        sb.append("请务必针对未自认修复的问题继续修正后重新提交。\n");
    }

    /**
     * 按 dependsOnIdList 收集直接前置的依赖产出参考段（双轨：结构化摘要 + 内容本体）。
     *
     * <p><b>多前置安全契约：</b>所有前置经 Map 全量收集后<b>按声明顺序</b>逐条渲染，
     * 每条都在循环内 append 到 StringBuilder——禁止单变量复用覆盖，否则会丢失除最后一个
     * 前置外的全部信息。内容本体优先取物化附件（local:// 平台直读），读取失败/无附件时
     * 回退 {@code context.lastExecution.output} 原始产出；单条超 {@link #DEP_CONTENT_MAX_CHARS}
     * 截断并显式标注。任何异常一律 warn 降级为不注入（不阻断执行），降级仍可观测。</p>
     */
    private DependencySectionResult buildDependencySection(SubTask subTask) {
        List<Long> dependsOn = subTask.dependsOnIdList();
        if (dependsOn == null || dependsOn.isEmpty()) {
            return DependencySectionResult.empty();
        }
        try {
            List<SubTask> deps = subTaskService.listByIds(dependsOn);
            Map<Long, SubTask> depMap = new HashMap<>();
            if (deps != null) {
                for (SubTask dep : deps) {
                    depMap.put(dep.getId(), dep);
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("## 依赖产出参考（直接前置）\n");
            sb.append("你必须综合参考以下前置子任务已完成的内容，结合当前任务要求综合分析后执行：\n\n");

            int loadedCount = 0;
            int truncatedCount = 0;
            int idx = 1;
            for (Long depId : dependsOn) {
                SubTask dep = depMap.get(depId);
                if (dep == null) {
                    continue;
                }
                ExecutionRecord record = taskRunningSpecService.findRecord(subTask.getTaskId(), depId);
                sb.append("### 前置 ").append(idx++).append("：").append(dep.getTitle())
                        .append("（状态：").append(dep.getStatus() != null ? dep.getStatus() : "UNKNOWN")
                        .append("）\n");
                if (record != null && record.summary() != null && !record.summary().isBlank()) {
                    sb.append("**产出摘要**: ").append(record.summary()).append('\n');
                }
                String content = loadUpstreamContent(dep);
                if (content == null || content.isBlank()) {
                    sb.append("（该前置子任务无可用产出内容）\n\n");
                    continue;
                }
                loadedCount++;
                sb.append("**内容**:\n");
                String render = content;
                if (render.length() > DEP_CONTENT_MAX_CHARS) {
                    render = render.substring(0, DEP_CONTENT_MAX_CHARS);
                    truncatedCount++;
                }
                sb.append(render).append('\n');
                if (render.length() < content.length()) {
                    sb.append("\n（已截断至 ").append(DEP_CONTENT_MAX_CHARS).append(" 字符）\n");
                }
                sb.append('\n');
            }
            return new DependencySectionResult(sb.toString(), dependsOn.size(), loadedCount, truncatedCount, false);
        } catch (Exception e) {
            log.warn("依赖产出上下文装配失败，降级跳过注入: subTaskId={}, err={}", subTask.getId(), e.getMessage());
            return DependencySectionResult.degraded(dependsOn.size());
        }
    }

    /**
     * 读取前置子任务的完成内容本体：物化附件（local:// 平台直读，仅 ACTIVE 有效版本——
     * 同名历史版本已由 {@code AttachmentService.register} 自动去活）优先，失败/无附件回退
     * {@code context.lastExecution.output} 原始产出；两者均无返回 null。
     */
    private String loadUpstreamContent(SubTask dep) {
        try {
            List<Attachment> attachments = attachmentService.listActive(dep.getId());
            if (attachments != null) {
                for (Attachment attachment : attachments) {
                    if (attachmentService.isContentLoadable(attachment)) {
                        byte[] bytes = attachmentService.loadContent(attachment.getId());
                        if (bytes != null && bytes.length > 0) {
                            return new String(bytes, StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取前置物化附件内容失败，回退原始产出: subTaskId={}, err={}",
                    dep.getId(), e.getMessage());
        }
        return SubTaskOutputExtractor.extractExecutionOutput(dep);
    }

    /** 依赖产出段装配结果：渲染文本 + 可观测统计（depCount/loadedCount/truncatedCount/degraded）。 */
    private static final class DependencySectionResult {
        private final String section;
        private final int depCount;
        private final int loadedCount;
        private final int truncatedCount;
        private final boolean degraded;

        private DependencySectionResult(String section, int depCount, int loadedCount, int truncatedCount,
                                        boolean degraded) {
            this.section = section;
            this.depCount = depCount;
            this.loadedCount = loadedCount;
            this.truncatedCount = truncatedCount;
            this.degraded = degraded;
        }

        private static DependencySectionResult empty() {
            return new DependencySectionResult("", 0, 0, 0, false);
        }

        private static DependencySectionResult degraded(int depCount) {
            return new DependencySectionResult("", depCount, 0, 0, true);
        }
    }

}
