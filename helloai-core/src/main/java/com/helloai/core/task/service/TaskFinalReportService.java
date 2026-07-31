package com.helloai.core.task.service;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.execution.PlatformAgentExecutionService;
import com.helloai.core.planner.PlannerAgentPicker;
import com.helloai.core.shared.event.TaskAutoCompletedEvent;
import com.helloai.core.shared.util.SubTaskDependencyOrder;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务最终整合报告生成（V32）。
 *
 * <p>任务收口后由 Planner 把全部 DONE 子任务产出整合为一份连贯的最终报告
 * （执行摘要 + 重组正文 + 结论），写入 {@code task.final_report} 专列；
 * 交付物 zip（{@link TaskDeliverableService}）与前端报告弹窗均从该列读取。</p>
 *
 * <p>触发方式（两条路径共用 {@link #generate}）：</p>
 * <ul>
 *   <li><b>自动</b>：{@code SubTaskCompletionListener.tryCloseTask} CAS 收口成功后发布
 *       {@link TaskAutoCompletedEvent}，本类 {@code @Async + @EventListener} 承接
 *       （发布点已无事务上下文，不能用 @TransactionalEventListener）；失败仅记
 *       timeline，不影响任务 DONE 状态——报告是增值物，非交付门槛。</li>
 *   <li><b>手动</b>：{@code POST /api/tasks/{id}/final-report}（历史已 DONE 任务补生成
 *       / 报告不满意重新生成，直接覆盖旧报告）。</li>
 * </ul>
 *
 * <p>不加类级事务：LLM 调用耗时长（与 PlannerAnalysisService.decompose 同哲学）；
 * 报告写回用 lambdaUpdate 只更新三列，不做全行覆盖。选人复用
 * {@link PlannerAgentPicker#pickForTask}，澄清→拆解→整合同一 Planner 跟随。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskFinalReportService {

    private static final String PROMPT_TEMPLATE_PATH = "prompts/task-final-report.md";
    /**
     * 单个子任务产出喂给 LLM 的截断上限阶梯（字符）。首档 8000 面向大上下文模型保信息量；
     * 命中模型 token 上限错误时逐档收紧重试，适配 8k 级小上下文模型（子任务多时
     * 逐段截断挡不住总量爆炸，实测 13 段 ×8000 字符可达 4.5w token）。
     */
    private static final int[] SECTION_OUTPUT_LIMITS = {8000, 2000, 500};
    private static final int TIMELINE_SUMMARY_LIMIT = 300;

    private final TaskService taskService;
    private final SubTaskService subTaskService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final AgentDispatchProperties dispatchProperties;

    /** 任务自动收口后异步生成报告；已有报告或开关关闭时跳过，异常吞掉（手动端点兜底）。 */
    @Async
    @EventListener
    public void onTaskAutoCompleted(TaskAutoCompletedEvent event) {
        if (!dispatchProperties.isAutoFinalReportEnabled()) {
            log.debug("自动整合报告未启用，跳过: taskId={}", event.getTaskId());
            return;
        }
        try {
            Task task = taskService.getById(event.getTaskId());
            if (task == null) {
                return;
            }
            if (task.getFinalReport() != null && !task.getFinalReport().isBlank()) {
                log.debug("整合报告已存在，自动生成跳过: taskId={}", event.getTaskId());
                return;
            }
            generate(event.getTaskId());
        } catch (Exception e) {
            log.warn("自动整合报告生成失败（可手动重新生成兜底）: taskId={}, err={}",
                    event.getTaskId(), e.getMessage());
        }
    }

    /**
     * 生成（或重新生成）任务最终整合报告，成功后返回最新 Task。
     *
     * <p>前置：任务必须已 DONE 且存在有产出的 DONE 子任务。重复调用直接覆盖旧报告
     * （last-write-wins；自动触发由 CAS 收口赢家唯一保证单次）。</p>
     */
    public Task generate(Long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new BizException(404, "任务不存在: " + taskId);
        }
        if (task.getStatus() != TaskStatus.DONE) {
            throw new BizException("只有已完成（DONE）的任务才能生成整合报告: taskId=" + taskId
                    + ", status=" + task.getStatus());
        }
        List<SubTask> sections = collectDoneSubTasksWithOutput(taskId);
        if (sections.isEmpty()) {
            throw new BizException("没有可整合的子任务产出（无 DONE 子任务或产出为空）: taskId=" + taskId);
        }

        Agent planner = plannerAgentPicker.pickForTask(taskId);
        // 截断阶梯降档重试：命中模型 token 上限错误且还有更紧档位时收紧重试，其余错误直接失败
        for (int i = 0; i < SECTION_OUTPUT_LIMITS.length; i++) {
            int limit = SECTION_OUTPUT_LIMITS[i];
            boolean lastTier = i == SECTION_OUTPUT_LIMITS.length - 1;
            String prompt = renderPrompt(task, sections, limit);
            taskTimelineService.recordEvent(taskId, null, "task_final_report_llm_call_start",
                    AgentRole.PLANNER, planner.getId(),
                    Map.of("agentId", planner.getId(),
                            "agentName", planner.getName(),
                            "sectionCount", sections.size(),
                            "sectionOutputLimit", limit));
            try {
                AgentTask agentTask = AgentTask.builder()
                        .systemPrompt("")
                        .userPrompt(prompt)
                        .context(Map.of("taskId", taskId, "scene", "task_final_report"))
                        .requiredCapabilities(Map.of())
                        .build();
                AgentResult result = platformAgentExecutionService.executeSync(planner, agentTask);
                if (result == null || !result.isSuccess()) {
                    throw new BizException("Planner LLM 调用失败: "
                            + (result != null ? result.getErrorMessage() : "null_result"));
                }
                String report = result.getOutput();
                if (report == null || report.isBlank()) {
                    throw new BizException("Planner LLM 返回空报告");
                }
                OffsetDateTime now = OffsetDateTime.now();
                taskService.lambdaUpdate()
                        .eq(Task::getId, taskId)
                        .set(Task::getFinalReport, report)
                        .set(Task::getFinalReportAgentId, planner.getId())
                        .set(Task::getFinalReportTime, now)
                        .update();
                taskTimelineService.recordEvent(taskId, null, "task_final_report_generated",
                        AgentRole.PLANNER, planner.getId(),
                        Map.of("agentId", planner.getId(),
                                "agentName", planner.getName(),
                                "sectionCount", sections.size(),
                                "sectionOutputLimit", limit,
                                "reportLength", report.length(),
                                "reportSummary", summarize(report)));
                log.info("任务整合报告生成完成: taskId={}, plannerAgentId={}, reportLength={}, sectionOutputLimit={}",
                        taskId, planner.getId(), report.length(), limit);
                return taskService.getById(taskId);
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (!lastTier && isTokenLimitError(errMsg)) {
                    log.warn("整合 prompt 超出模型上下文，降档重试: taskId={}, sectionOutputLimit={} -> {}, err={}",
                            taskId, limit, SECTION_OUTPUT_LIMITS[i + 1], errMsg);
                    continue;
                }
                taskTimelineService.recordEvent(taskId, null, "task_final_report_failed",
                        AgentRole.PLANNER, planner.getId(),
                        Map.of("error", errMsg, "sectionOutputLimit", limit));
                log.warn("任务整合报告生成失败: taskId={}", taskId, e);
                if (e instanceof BizException be) {
                    throw be;
                }
                throw new BizException("整合报告生成失败: " + errMsg);
            }
        }
        // 阶梯内必有 return 或 throw，此处仅为满足编译器
        throw new BizException("整合报告生成失败: taskId=" + taskId);
    }

    /** 识别 LLM 提供商的上下文/token 超限错误（moonshot/openai/deepseek 等措辞覆盖）。 */
    private static boolean isTokenLimitError(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("token limit")
                || lower.contains("context length")
                || lower.contains("context_length")
                || lower.contains("maximum context")
                || lower.contains("too many tokens")
                || lower.contains("input is too long");
    }

    /** 收集有产出的 DONE 子任务，拓扑序排列（与交付物 zip 的收录顺序一致）。 */
    private List<SubTask> collectDoneSubTasksWithOutput(Long taskId) {
        List<SubTask> subTasks = subTaskService.lambdaQuery()
                .eq(SubTask::getTaskId, taskId)
                .orderByAsc(SubTask::getCreateTime)
                .list();
        List<SubTask> visible = new ArrayList<>();
        for (SubTask st : subTasks != null ? subTasks : List.<SubTask>of()) {
            if (st.getStatus() != SubTaskStatus.DONE) {
                continue;
            }
            String output = extractExecutionOutput(st);
            if (output != null && !output.isBlank()) {
                visible.add(st);
            }
        }
        return SubTaskDependencyOrder.orderByDependency(visible);
    }

    /** 加载 classpath 模板并替换占位符（与 PlannerAnalysisService.renderPrompt 同款先例）。 */
    private String renderPrompt(Task task, List<SubTask> sections, int sectionOutputLimit) {
        ClassPathResource resource = new ClassPathResource(PROMPT_TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new BizException("未找到整合报告 Prompt 模板: " + PROMPT_TEMPLATE_PATH);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("读取整合报告 Prompt 模板失败: " + e.getMessage());
        }
        return template
                .replace("{{TASK_TITLE}}", task.getTitle() != null ? task.getTitle() : "")
                .replace("{{TASK_DESCRIPTION}}",
                        task.getDescription() != null && !task.getDescription().isBlank()
                                ? task.getDescription() : "（无补充描述）")
                .replace("{{SUB_TASK_SECTIONS}}", buildSections(sections, sectionOutputLimit));
    }

    /** 拼接各子任务四要素 + 产出正文（逐段截断保护上下文窗口，截断上限由降档阶梯传入）。 */
    private static String buildSections(List<SubTask> sections, int sectionOutputLimit) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (SubTask st : sections) {
            sb.append("### 子任务 ").append(i++).append('：')
                    .append(st.getTitle() != null ? st.getTitle() : "（无标题）").append('\n');
            if (st.getDeliverable() != null && !st.getDeliverable().isBlank()) {
                sb.append("- 交付物要求：").append(st.getDeliverable()).append('\n');
            }
            if (st.getAcceptance() != null && !st.getAcceptance().isBlank()) {
                sb.append("- 验收标准：").append(st.getAcceptance()).append('\n');
            }
            String output = extractExecutionOutput(st);
            sb.append("\n产出正文：\n\n");
            if (output.length() > sectionOutputLimit) {
                sb.append(output, 0, sectionOutputLimit)
                        .append("\n\n（产出超长，以上为截断内容，整合时以已提供部分为准）\n");
            } else {
                sb.append(output).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 读取 context.lastExecution.output（与 TaskDeliverableService.extractExecutionOutput 同款先例）。 */
    private static String extractExecutionOutput(SubTask subTask) {
        Map<String, Object> ctx = subTask.getContext();
        if (ctx != null && ctx.get("lastExecution") instanceof Map<?, ?> lastExecution) {
            Object output = lastExecution.get("output");
            if (output instanceof String text) {
                return text;
            }
        }
        return null;
    }

    private static String summarize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= TIMELINE_SUMMARY_LIMIT
                ? trimmed : trimmed.substring(0, TIMELINE_SUMMARY_LIMIT) + "...";
    }
}
