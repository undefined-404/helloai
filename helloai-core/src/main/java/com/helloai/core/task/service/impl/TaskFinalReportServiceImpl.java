package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.shared.event.TaskAutoCompletedEvent;
import com.helloai.core.shared.util.SubTaskDependencyOrder;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskFinalReportService;
import com.helloai.core.task.service.TaskIterationService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
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
 * 任务最终整合报告生成实现。
 *
 * <p>任务收口后由 Planner 把全部 DONE 子任务产出整合为一份连贯的最终报告
 * （执行摘要 + 重组正文 + 结论），写入 {@code task.final_report} 专列。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskFinalReportServiceImpl implements TaskFinalReportService {

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
    private final TaskIterationService taskIterationService;

    /** 任务自动收口后异步生成报告；已有报告或开关关闭时跳过，异常吞掉（手动端点兜底）。 */
    @Override
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
            if (task.getFinalReportStatus() == FinalReportStatus.GENERATING) {
                // 已有一次生成在途（手动端点抢先触发），自动路径不再并发触发
                log.debug("整合报告正在生成中，自动生成跳过: taskId={}", event.getTaskId());
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

    @Override
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

        // CAS 防重入：仅当当前状态非 GENERATING 时置位成功；失败说明另一条路径正在生成
        boolean casOk = taskService.update(new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .ne(Task::getFinalReportStatus, FinalReportStatus.GENERATING)
                .set(Task::getFinalReportStatus, FinalReportStatus.GENERATING));
        if (!casOk) {
            throw new BizException("任务整合报告正在生成中，请稍候后再试: taskId=" + taskId);
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
                        .systemPrompt("注意：按信息密度优先原则整合——契约性事实（表格/代码/参数/阈值/路径）必须完整保留，叙事文字压缩至必要最小，禁止用过程叙事或铺垫填充篇幅。")
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
                        .set(Task::getFinalReportStatus, FinalReportStatus.DONE)
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
                // 回填 task_iteration 表（失败不阻断报告生成）
                backfillIterationsQuietly(taskId, sections, planner);
                return taskService.getById(taskId);
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (!lastTier && isRetryableError(e, errMsg)) {
                    log.warn("整合 prompt 超出模型上下文或调用超时，降档重试: taskId={}, sectionOutputLimit={} -> {}, err={}",
                            taskId, limit, SECTION_OUTPUT_LIMITS[i + 1], errMsg);
                    continue;
                }
                taskTimelineService.recordEvent(taskId, null, "task_final_report_failed",
                        AgentRole.PLANNER, planner.getId(),
                        Map.of("error", errMsg, "sectionOutputLimit", limit));
                log.warn("任务整合报告生成失败: taskId={}", taskId, e);
                // 最终失败置 FAILED，允许手动重试（避免 GENERATING 卡死无恢复口）
                markFailed(taskId, errMsg);
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

    /**
     * 判断是否可降档重试的错误：token 限制 或 读超时（大 prompt 导致 LLM 生成太慢）。
     * 同时扫描异常消息和 cause 链中的 SocketTimeoutException。
     */
    private static boolean isRetryableError(Throwable e, String message) {
        if (isTokenLimitError(message)) {
            return true;
        }
        return hasCauseAssignableTo(e, java.net.SocketTimeoutException.class);
    }

    private static boolean hasCauseAssignableTo(Throwable e, Class<? extends Throwable> target) {
        if (e == null) {
            return false;
        }
        Throwable current = e;
        while (current != null) {
            if (target.isAssignableFrom(current.getClass())) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return false;
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
        String sectionsText = buildSections(sections, sectionOutputLimit);
        return template
                .replace("{{TASK_TITLE}}", task.getTitle() != null ? task.getTitle() : "")
                .replace("{{TASK_DESCRIPTION}}",
                        task.getDescription() != null && !task.getDescription().isBlank()
                                ? task.getDescription() : "（无补充描述）")
                .replace("{{SUB_TASK_SECTIONS}}", sectionsText);
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

    /** 读取 context.lastExecution.output（统一走 SubTaskOutputExtractor，与 TaskDeliverableService 同一事实源）。 */
    private static String extractExecutionOutput(SubTask subTask) {
        return SubTaskOutputExtractor.extractExecutionOutput(subTask);
    }

    /**
     * 标记报告生成最终失败（FAILED）。
     *
     * <p>只负责状态回写，失败不外抛（避免掩盖原始 LLM 异常）；置 FAILED 后手动端点可重试。</p>
     */
    private void markFailed(Long taskId, String error) {
        try {
            taskService.update(new LambdaUpdateWrapper<Task>()
                    .eq(Task::getId, taskId)
                    .set(Task::getFinalReportStatus, FinalReportStatus.FAILED));
        } catch (Exception e) {
            log.warn("标记整合报告生成失败状态异常: taskId={}, err={}", taskId, e.getMessage());
        }
    }

    private static String summarize(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        return trimmed.length() <= TIMELINE_SUMMARY_LIMIT
                ? trimmed : trimmed.substring(0, TIMELINE_SUMMARY_LIMIT) + "...";
    }

    /**
     * 回填 task_iteration 表（静默失败，不阻断报告生成主流程）。
     *
     * <p>报告生成成功后调用，把全部 DONE 子任务的执行迭代数据一次性固化到
     * task_iteration 表。回填失败仅记 timeline + 日志，不影响报告生成结果。</p>
     */
    private void backfillIterationsQuietly(Long taskId, List<SubTask> sections, Agent planner) {
        try {
            taskIterationService.backfillForTask(taskId, sections, planner);
        } catch (Exception e) {
            log.warn("task_iteration 回填失败（不影响报告生成）: taskId={}, err={}",
                    taskId, e.getMessage());
            taskTimelineService.recordEvent(taskId, null, "task_iteration_backfill_failed",
                    AgentRole.PLANNER, planner != null ? planner.getId() : null,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }
}
