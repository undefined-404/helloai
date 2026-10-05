package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TaskStatus;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.task.port.PlannerAgentRef;
import com.helloai.core.task.port.TaskPlannerPickerPort;
import com.helloai.core.shared.event.TaskAutoCompletedEvent;
import com.helloai.core.shared.util.AttachmentContentPolicy;
import com.helloai.core.task.util.SubTaskDependencyOrder;
import com.helloai.core.shared.util.SubTaskOutputExtractor;
import com.helloai.core.shared.util.TextTruncator;
import com.helloai.core.task.entity.Attachment;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.AttachmentService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskFinalReportService;
import com.helloai.core.task.service.TaskIterationService;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.spec.ExecutionRecord;
import com.helloai.core.task.statemachine.FinalReportStateMachine;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final String OUTLINE_TEMPLATE_PATH = "prompts/task-final-report-outline.md";
    /**
     * 单个子任务产出喂给 LLM 的截断上限阶梯（字符）。2026-10-03 由 {8000,2000,500} 上调为
     * {64000,16000,4000}：所有 LLM 走官方 DeepSeek（64K 上下文），首档 8000 是自我阉割——
     * 普通子任务产出（几万字）被硬截掉大半，与「整合报告须信息密度高、可独立交付」的目标冲突。
     * 首档 64000 让正常任务全量注入；命中模型 token 上限错误时仍逐档收紧重试（降档语义保留，
     * 兜底极端超大产出的场景，64K 并非无限）。
     */
    private static final int[] SECTION_OUTPUT_LIMITS = {64000, 16000, 4000};
    private static final int TIMELINE_SUMMARY_LIMIT = 300;
    /** 大纲 JSON 解析器（record 反序列化；LLM 输出经模板约束为标准 JSON）。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TaskService taskService;
    private final SubTaskService subTaskService;
    private final TaskPlannerPickerPort plannerPickerPort;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final TaskTimelineService taskTimelineService;
    private final AgentDispatchProperties dispatchProperties;
    private final TaskIterationService taskIterationService;
    /** 结构化执行记录/全局上下文（基线 + Context Summary）：报告链与子任务执行链同口径读取事实源。 */
    private final TaskRunningSpecService taskRunningSpecService;
    /** 物化附件读取：报告链附件优先（与核验链同口径）。 */
    private final AttachmentService attachmentService;
    /**
     * 报告写回 + 审查触发的事务边界（§12.2）：写 final_report、L2 Outbox 落库、
     * L1 事件发布在同一短事务内完成。独立 Bean 保证 @Transactional 真正生效
     * （本类内自调用不走代理）。
     */
    private final FinalReportPersistService finalReportPersistService;

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
        return generateWithAttempt(taskId, null, 1);
    }

    @Override
    public Task rework(Long taskId, String reviewFeedback) {
        return rework(taskId, reviewFeedback, 1);
    }

    @Override
    public Task rework(Long taskId, String reviewFeedback, int attempt) {
        if (reviewFeedback == null || reviewFeedback.isBlank()) {
            throw new BizException("reviewFeedback 不能为空");
        }
        log.info("最终报告审查驳回返工重写: taskId={}, attempt={}, feedback={}", taskId, attempt,
                reviewFeedback.length() > 200
                        ? reviewFeedback.substring(0, 200) + "..." : reviewFeedback);
        return generateWithAttempt(taskId, reviewFeedback, attempt);
    }

    @Override
    public Task rollback(Long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new BizException(404, "任务不存在: " + taskId);
        }
        String prev = task.getFinalReportPrev();
        if (prev == null || prev.isBlank()) {
            throw new BizException(409, "没有可恢复的上一版整合报告: taskId=" + taskId);
        }
        FinalReportStatus statusBeforeRollback = task.getFinalReportStatus();
        // 生成在途（GENERATING）禁止互换：给可读业务异常（而非让状态机断言抛 IllegalStateException）
        if (statusBeforeRollback == FinalReportStatus.GENERATING) {
            throw new BizException(409, "任务整合报告正在生成中，暂不能恢复上一版: taskId=" + taskId);
        }
        // §12.5 状态机单点校验【前移到 CAS 之前】：非法迁移必须在动库前快速失败，杜绝
        // 「CAS 已改库、断言再抛异常」导致的库静默变更 + 接口 500 + 审计不落（#1）。
        // prev 槽非空的可达状态为 DONE / REVIEWING / FAILED，三者 -> DONE 均合法
        // （FAILED -> DONE：markFailed 不清 prev 槽，失败后恢复上一版是合法收敛）。
        FinalReportStateMachine.assertTransit(statusBeforeRollback, FinalReportStatus.DONE);
        // CAS 与断言同源：仅当库中状态仍等于快照时才互换（乐观锁），快照陈旧时不误改
        boolean casOk = taskService.update(new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .eq(Task::getFinalReportStatus, statusBeforeRollback)
                .set(Task::getFinalReport, task.getFinalReportPrev())
                .set(Task::getFinalReportAgentId, task.getFinalReportPrevAgentId())
                .set(Task::getFinalReportTime, task.getFinalReportPrevTime())
                .set(Task::getFinalReportStatus, FinalReportStatus.DONE)
                .set(Task::getFinalReportPrev, task.getFinalReport())
                .set(Task::getFinalReportPrevAgentId, task.getFinalReportAgentId())
                .set(Task::getFinalReportPrevTime, task.getFinalReportTime()));
        if (!casOk) {
            throw new BizException(409, "任务整合报告状态已变化，请刷新后重试: taskId=" + taskId);
        }
        taskTimelineService.recordEvent(taskId, null, "task_final_report_rolled_back",
                AgentRole.PLANNER, task.getFinalReportPrevAgentId(),
                Map.of("restoredGeneratedAt",
                        task.getFinalReportPrevTime() != null ? String.valueOf(task.getFinalReportPrevTime()) : "",
                        "restoredAgentId",
                        task.getFinalReportPrevAgentId() != null ? task.getFinalReportPrevAgentId() : ""));
        log.info("最终整合报告已回滚恢复上一版: taskId={}, restoredAgentId={}, restoredAt={}",
                taskId, task.getFinalReportPrevAgentId(), task.getFinalReportPrevTime());
        return taskService.getById(taskId);
    }

    /**
     * 生成主链（generate/rework 共用；轮次为<b>显式入参</b>，无任何状态存储——全新一轮
     * 恒传 {@code 1}，同轮返工由监听器传 {@code event.getAttempt() + 1}）；
     * 驳回意见非 null 时注入 {@code {{REVIEW_FEEDBACK}}}。
     */
    private Task generateWithAttempt(Long taskId, String reviewFeedback, int attempt) {
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

        FinalReportStatus statusBeforeGenerate = task.getFinalReportStatus();
        // 快照即 GENERATING：已有生成在途，给可读业务异常（不再靠“CAS 后断言”兜，避免陈旧快照误判）
        if (statusBeforeGenerate == FinalReportStatus.GENERATING) {
            throw new BizException("任务整合报告正在生成中，请稍候后再试: taskId=" + taskId);
        }
        // §12.5 状态机单点校验【前移到 CAS 之前】：非法迁移在动库前快速失败，杜绝
        // 「CAS 已置 GENERATING、断言再抛」把状态永久卡在 GENERATING（L3 只扫 REVIEWING，无恢复口）（#2）。
        // 快照非 GENERATING ⇒ NONE/DONE/REVIEWING/FAILED -> GENERATING 均合法。
        FinalReportStateMachine.assertTransit(statusBeforeGenerate, FinalReportStatus.GENERATING);
        // CAS 与断言同源：仅当库中状态仍等于快照时才置 GENERATING（乐观锁），
        // 快照陈旧（并发被接管）时 CAS 失败、不改库、给可读重试异常
        boolean casOk = taskService.update(new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .eq(Task::getFinalReportStatus, statusBeforeGenerate)
                .set(Task::getFinalReportStatus, FinalReportStatus.GENERATING));
        if (!casOk) {
            throw new BizException("任务整合报告状态已变化（可能正在生成中），请稍候后再试: taskId=" + taskId);
        }

        PlannerAgentRef planner = plannerPickerPort.pickForTask(taskId);
        // 3C 大纲先行两段式：先归并出纲（覆盖追溯表/主线论点/章节顺序/矛盾清单），
        // 失败/解析失败/开关关闭降级为 null，正文渲染走单次调用兜底（与开关引入前行为一致）
        OutlinePlan outline = planOutlineQuietly(task, sections, planner);
        // 截断阶梯降档重试：命中模型 token 上限错误且还有更紧档位时收紧重试，其余错误直接失败
        for (int i = 0; i < SECTION_OUTPUT_LIMITS.length; i++) {
            int limit = SECTION_OUTPUT_LIMITS[i];
            boolean lastTier = i == SECTION_OUTPUT_LIMITS.length - 1;
            String prompt = renderPrompt(task, sections, outline, limit, reviewFeedback);
            taskTimelineService.recordEvent(taskId, null, "task_final_report_llm_call_start",
                    AgentRole.PLANNER, planner.id(),
                    Map.of("agentId", planner.id(),
                            "agentName", planner.name(),
                            "sectionCount", sections.size(),
                            "sectionOutputLimit", limit));
            try {
                AgentTask agentTask = AgentTask.builder()
                        .systemPrompt("注意：按信息密度优先原则整合——契约性事实（表格/代码/参数/阈值/路径）必须完整保留，叙事文字压缩至必要最小，禁止用过程叙事或铺垫填充篇幅。")
                        .userPrompt(prompt)
                        .context(Map.of("taskId", taskId, "scene", "task_final_report"))
                        .requiredCapabilities(Map.of())
                        // G-016 契约层技能注入：与任务级 required_skills 同源（null 防御为 List.of()）
                        .skills(task.getRequiredSkills() != null ? task.getRequiredSkills() : List.of())
                        .build();
                AgentResult result = platformAgentExecutionService.executeSync(planner.id(), agentTask);
                if (result == null || !result.isSuccess()) {
                    throw new BizException("Planner LLM 调用失败: "
                            + (result != null ? result.getErrorMessage() : "null_result"));
                }
                String report = result.getOutput();
                if (report == null || report.isBlank()) {
                    throw new BizException("Planner LLM 返回空报告");
                }
                OffsetDateTime now = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                // §12.2/§12.3：写回 + 审查触发（L1 事件 + L2 Outbox）由 FinalReportPersistService
                // 在同一事务内完成——审查开启时写回 REVIEWING（待审查链收敛 DONE，UI 显示审查中），
                // 关闭时直接 DONE（无审查链存在，避免状态无人收敛）
                boolean reviewEnabled = dispatchProperties.isAutoFinalReportReviewEnabled();
                boolean persisted = finalReportPersistService.persistAndRequestReview(
                        taskId, report, planner.id(), now, attempt, sections.size(), reviewEnabled);
                if (!persisted) {
                    // CAS 未命中 = 状态已被其它链路接管：本次写回与审查触发一并作废，
                    // 不覆盖别人刚写入的状态，也不误判 FAILED（PersistService 内已告警）
                    log.warn("报告写回被接管，放弃本轮生成结果: taskId={}, attempt={}", taskId, attempt);
                    return taskService.getById(taskId);
                }
                taskTimelineService.recordEvent(taskId, null, "task_final_report_generated",
                        AgentRole.PLANNER, planner.id(),
                        Map.of("agentId", planner.id(),
                                "agentName", planner.name(),
                                "sectionCount", sections.size(),
                                "sectionOutputLimit", limit,
                                "reportLength", report.length(),
                                "reportSummary", summarize(report)));
                log.info("任务整合报告生成完成: taskId={}, plannerAgentId={}, reportLength={}, sectionOutputLimit={}",
                        taskId, planner.id(), report.length(), limit);
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
                        AgentRole.PLANNER, planner.id(),
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

    /**
     * 3C：大纲先行出纲调用（选中 Planner 归并出纲）。任何失败路径（开关关闭/调用失败/
     * 输出非 JSON/解析后章节为空/运行期异常）一律返回 null，由调用方降级为单次调用——
     * 大纲规划绝不阻断报告生成主链。
     */
    private OutlinePlan planOutlineQuietly(Task task, List<SubTask> sections, PlannerAgentRef planner) {
        if (!dispatchProperties.isAutoFinalReportOutlineEnabled()) {
            return null;
        }
        try {
            String outlinePrompt = renderOutlinePrompt(task, sections);
            AgentTask agentTask = AgentTask.builder()
                    .systemPrompt("你是任务最终整合报告的归并出纲师。严格只输出一个 JSON 对象（不带 Markdown 围栏、不带任何其他文字）。")
                    .userPrompt(outlinePrompt)
                    .context(Map.of("taskId", task.getId(), "scene", "task_final_report_outline"))
                    // G-016 契约层技能注入：与正文生成同源
                    .skills(task.getRequiredSkills() != null ? task.getRequiredSkills() : List.of())
                    .build();
            AgentResult result = platformAgentExecutionService.executeSync(planner.id(), agentTask);
            OutlinePlan plan = (result != null && result.isSuccess()
                    && result.getOutput() != null && !result.getOutput().isBlank())
                    ? parseOutline(result.getOutput()) : null;
            if (plan == null) {
                taskTimelineService.recordEvent(task.getId(), null, "task_final_report_outline_failed",
                        AgentRole.PLANNER, planner.id(),
                        Map.of("error", result != null && !result.isSuccess()
                                ? result.getErrorMessage() : "parse_failed_or_empty"));
                log.warn("报告大纲规划失败，降级为单次调用: taskId={}, error={}", task.getId(),
                        result != null && !result.isSuccess() && result.getErrorMessage() != null
                                ? result.getErrorMessage() : "parse_failed_or_empty");
                return null;
            }
            taskTimelineService.recordEvent(task.getId(), null, "task_final_report_outline_ready",
                    AgentRole.PLANNER, planner.id(),
                    Map.of("agentId", planner.id(), "agentName", planner.name(),
                            "chapterCount", plan.sections().size(),
                            "mainThesisCount", plan.mainTheses() != null ? plan.mainTheses().size() : 0,
                            "conflictCount", plan.conflicts() != null ? plan.conflicts().size() : 0));
            warnIfOutlineOrderUnchanged(task.getId(), planner.id(), plan);
            log.info("报告大纲规划完成: taskId={}, chapterCount={}", task.getId(), plan.sections().size());
            return plan;
        } catch (Exception e) {
            log.warn("报告大纲规划异常，降级为单次调用: taskId={}, err={}", task.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 3C 排序自检：大纲章节的引用顺序若与子任务编号顺序完全一致（#1、#2、…），
     * 说明未按「读者理解顺序」重排（出纲模板规则3 的硬要求）——落告警事件
     * {@code task_final_report_outline_order_suspect}，不阻断生成（重排质量由 3A 审查兜底）。
     */
    private void warnIfOutlineOrderUnchanged(Long taskId, Long plannerAgentId, OutlinePlan plan) {
        List<String> flat = new ArrayList<>();
        for (OutlinePlan.OutlineSection section : plan.sections()) {
            if (section.subTaskRefs() != null) {
                flat.addAll(section.subTaskRefs());
            }
        }
        if (flat.size() < 2) {
            return;
        }
        for (int i = 0; i < flat.size(); i++) {
            String ref = flat.get(i) != null ? flat.get(i).trim() : "";
            if (!("#" + (i + 1)).equals(ref)) {
                return;
            }
        }
        taskTimelineService.recordEvent(taskId, null, "task_final_report_outline_order_suspect",
                AgentRole.PLANNER, plannerAgentId,
                Map.of("chapterCount", plan.sections().size(),
                        "reason", "order_equals_subtask_sequence"));
        log.warn("报告大纲章节顺序与子任务顺序完全一致，疑似未按读者理解顺序重排: taskId={}, chapterCount={}",
                taskId, plan.sections().size());
    }

    /** 渲染出纲 Prompt：任务信息 + 各子任务标题/交付物/验收 + 结构化执行记录摘要（不以正文为输入，控制成本）。 */
    private String renderOutlinePrompt(Task task, List<SubTask> sections) {
        ClassPathResource resource = new ClassPathResource(OUTLINE_TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new BizException("未找到报告大纲 Prompt 模板: " + OUTLINE_TEMPLATE_PATH);
        }
        String template;
        try (InputStream in = resource.getInputStream()) {
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("读取报告大纲 Prompt 模板失败: " + e.getMessage());
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (SubTask st : sections) {
            sb.append("### #").append(i++).append(' ')
                    .append(st.getTitle() != null ? st.getTitle() : "（无标题）").append('\n');
            if (st.getDeliverable() != null && !st.getDeliverable().isBlank()) {
                sb.append("- 交付物要求：").append(st.getDeliverable()).append('\n');
            }
            if (st.getAcceptance() != null && !st.getAcceptance().isBlank()) {
                sb.append("- 验收标准：").append(st.getAcceptance()).append('\n');
            }
            ExecutionRecord record = taskRunningSpecService.findRecord(task.getId(), st.getId());
            if (record != null) {
                if (record.summary() != null && !record.summary().isBlank()) {
                    sb.append("- 执行摘要：").append(record.summary()).append('\n');
                }
                if (!record.deliverables().isEmpty()) {
                    sb.append("- 交付物清单：").append(String.join("、", record.deliverables())).append('\n');
                }
                if (!record.keyDecisions().isEmpty()) {
                    sb.append("- 关键决策：").append(String.join("；", record.keyDecisions())).append('\n');
                }
            }
            sb.append('\n');
        }
        return template
                .replace("{{TASK_TITLE}}", task.getTitle() != null ? task.getTitle() : "")
                .replace("{{TASK_DESCRIPTION}}",
                        task.getDescription() != null && !task.getDescription().isBlank()
                                ? task.getDescription() : "（无补充描述）")
                .replace("{{SUB_TASK_OUTLINE_INPUTS}}", sb.toString().trim());
    }

    /** 解析大纲 JSON；剥离可能的 Markdown 围栏，解析失败或章节为空返回 null（调用方降级单次调用）。 */
    private static OutlinePlan parseOutline(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = raw.trim();
        if (json.startsWith("```")) {
            int firstLineEnd = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstLineEnd > 0 && lastFence > firstLineEnd) {
                json = json.substring(firstLineEnd, lastFence).trim();
            }
        }
        try {
            OutlinePlan plan = OBJECT_MAPPER.readValue(json, OutlinePlan.class);
            if (plan.sections() == null || plan.sections().isEmpty()) {
                return null;
            }
            return plan;
        } catch (Exception e) {
            return null;
        }
    }

    /** 加载 classpath 模板并替换占位符（与 PlannerAnalysisService.renderPrompt 同款先例）。 */
    private String renderPrompt(Task task, List<SubTask> sections, OutlinePlan outline, int sectionOutputLimit,
                                String reviewFeedback) {
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
        // 3C：大纲非空时按章节归属分片（每章预算独立），否则按拓扑序单次拼接（降级兜底）
        String sectionsText = (outline == null || outline.sections().isEmpty())
                ? buildSections(task.getId(), sections, sectionOutputLimit)
                : buildSectionsByOutline(task.getId(), sections, outline, sectionOutputLimit);
        // 大纲注入段：规划产物渲染为覆盖追溯表/主线论点/矛盾清单（空时模板行留空）
        String outlineSection = outline == null ? "" : renderOutlineSection(outline);
        // 全局上下文段（基线/进度摘要）：非空才拼（模板占位符独占一行，空时整行消失）
        String contextSection = taskRunningSpecService.buildExecutorPromptSection(task.getId());
        String taskContext = contextSection == null ? "" : contextSection;
        return template
                .replace("{{TASK_TITLE}}", task.getTitle() != null ? task.getTitle() : "")
                .replace("{{TASK_DESCRIPTION}}",
                        task.getDescription() != null && !task.getDescription().isBlank()
                                ? task.getDescription() : "（无补充描述）")
                .replace("{{TASK_CONTEXT_SECTION}}", taskContext)
                .replace("{{OUTLINE_SECTION}}", outlineSection)
                .replace("{{REVIEW_FEEDBACK}}", reviewFeedback != null ? reviewFeedback : "")
                .replace("{{SUB_TASK_SECTIONS}}", sectionsText);
    }

    /** 大纲规划产物渲染为正文模板注入段：主线论点 + 覆盖追溯表 + 矛盾清单（章节组织最高权威约束）。 */
    private static String renderOutlineSection(OutlinePlan outline) {
        StringBuilder sb = new StringBuilder();
        if (outline.mainTheses() != null && !outline.mainTheses().isEmpty()) {
            sb.append("主线论点（执行摘要必须以此为准）：\n");
            for (String thesis : outline.mainTheses()) {
                sb.append("- ").append(thesis).append('\n');
            }
        }
        sb.append("覆盖追溯表（章节与主题已归并规划，必须严格遵守章节顺序与归属）：\n\n");
        sb.append("| 章节编号 | 章节主题 | 覆盖子任务 |\n|---------|---------|-----------|\n");
        int idx = 1;
        for (OutlinePlan.OutlineSection os : outline.sections()) {
            List<String> refs = os.subTaskRefs() != null ? os.subTaskRefs() : List.of();
            sb.append("| §").append(idx++).append(" | ")
                    .append(os.title()).append(" | ")
                    .append(String.join("、", refs)).append(" |\n");
        }
        if (outline.conflicts() != null && !outline.conflicts().isEmpty()) {
            sb.append("\n规划阶段矛盾清单（第4步差异与冲突澄清必须逐条覆盖）：\n");
            for (String conflict : outline.conflicts()) {
                sb.append("- ").append(conflict).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /**
     * 拼接各子任务四要素 + 产出正文。读取口径与子任务执行链一致：
     * ① 结构化执行记录非 null 时注入 SUMMARY/DELIVERABLES（摘要口径，不受降档截断影响）；
     * ② 正文物化附件优先（可直读文本附件拼接入正文），displayText 兜底；
     * ③ Markdown 块级截断（段落/围栏/表格行边界），超限按 V1 策略标注：有记录映射
     * {@code [SUMMARIZED]}（摘要在上方完整提供），无记录标 {@code [TRUNCATED] 完整内容见附件}。
     * 截断上限由降档阶梯传入——降档收紧的是附件正文，结构化摘要保持完整。
     */
    private String buildSections(Long taskId, List<SubTask> sections, int sectionOutputLimit) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (SubTask st : sections) {
            appendSectionDetail(sb, taskId, st, i++, sectionOutputLimit);
        }
        return sb.toString();
    }

    /**
     * 单个子任务产出块：编号/标题/交付物/验收/结构化执行记录/附件优先正文 + 块级截断标注。
     * 读取口径与子任务执行链一致：① 结构化执行记录非 null 时注入 SUMMARY/DELIVERABLES
     * （摘要口径，不受降档截断影响）；② 正文物化附件优先（可直读文本附件拼接入正文），
     * displayText 兜底；③ Markdown 块级截断（段落/围栏/表格行边界），超限按 V1 策略标注：
     * 有记录映射 {@code [SUMMARIZED]}（摘要在上方完整提供），无记录标 {@code [TRUNCATED] 完整内容见附件}。
     */
    private void appendSectionDetail(StringBuilder sb, Long taskId, SubTask st, int seq, int limit) {
        sb.append("### 子任务 ").append(seq).append('：')
                .append(st.getTitle() != null ? st.getTitle() : "（无标题）").append('\n');
        if (st.getDeliverable() != null && !st.getDeliverable().isBlank()) {
            sb.append("- 交付物要求：").append(st.getDeliverable()).append('\n');
        }
        if (st.getAcceptance() != null && !st.getAcceptance().isBlank()) {
            sb.append("- 验收标准：").append(st.getAcceptance()).append('\n');
        }
        // 结构化执行记录（与子任务执行链同口径）：摘要/交付物/关键决策契约注入
        ExecutionRecord record = taskRunningSpecService.findRecord(taskId, st.getId());
        boolean hasRecord = record != null && record.summary() != null && !record.summary().isBlank();
        if (hasRecord) {
            sb.append("- 执行摘要：").append(record.summary()).append('\n');
            if (!record.deliverables().isEmpty()) {
                sb.append("- 交付物清单：").append(String.join("、", record.deliverables())).append('\n');
            }
            if (!record.keyDecisions().isEmpty()) {
                sb.append("- 关键决策：").append(String.join("；", record.keyDecisions())).append('\n');
            }
        }
        sb.append("\n产出正文：\n\n");
        // 附件优先：可直读文本附件正文；无可用附件时回退 displayText
        String body = buildAttachmentText(st.getId());
        if (body.isBlank()) {
            body = extractExecutionOutput(st);
        }
        if (body.length() > limit) {
            sb.append(truncateAtBlockBoundary(body, limit)).append('\n');
            sb.append(hasRecord
                    ? "\n[SUMMARIZED] 产出正文超长已块级截断，结构化执行摘要见上方「执行摘要」行，完整内容见任务附件。\n"
                    : "\n[TRUNCATED] 完整内容见附件。\n");
        } else {
            sb.append(body).append('\n');
        }
        sb.append('\n');
    }

    /**
     * 3C：按大纲章节分片渲染子任务产出（每章预算独立——总预算均摊到章，超长章块级截断
     * 不影响其余章完整注入）。章节顺序 = 大纲顺序（读者理解顺序）；大纲遗漏的子任务
     * 兜底追加「其余产出」组，保证不丢信息（与覆盖规则闭合）。
     */
    private String buildSectionsByOutline(Long taskId, List<SubTask> sections, OutlinePlan outline,
                                          int sectionOutputLimit) {
        Map<String, SubTask> byRef = new LinkedHashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            byRef.put("#" + (i + 1), sections.get(i));
        }
        int chapterCount = Math.max(1, outline.sections().size());
        // 每章预算独立：总预算均摊（下限 100 字符保底，防止极端分段导致整章空白）
        int perSectionLimit = Math.max(100, sectionOutputLimit / chapterCount);
        StringBuilder sb = new StringBuilder();
        Set<String> used = new HashSet<>();
        int chapter = 1;
        for (OutlinePlan.OutlineSection os : outline.sections()) {
            sb.append("### 章节 ").append(chapter++).append('：').append(os.title())
                    .append("（大纲归并主题）\n");
            List<String> refs = os.subTaskRefs() != null ? os.subTaskRefs() : List.of();
            for (String ref : refs) {
                SubTask st = byRef.get(ref);
                if (st == null) {
                    continue;
                }
                used.add(ref);
                appendSectionDetail(sb, taskId, st, refSeq(ref), perSectionLimit);
            }
        }
        // 未归属子任务兜底（大纲遗漏时仍注入，防丢章）
        for (int i = 0; i < sections.size(); i++) {
            String ref = "#" + (i + 1);
            if (!used.contains(ref)) {
                sb.append("### 章节 ").append(chapter++).append("：其余产出（大纲未归属，按拓扑序保留）\n");
                appendSectionDetail(sb, taskId, sections.get(i), i + 1, perSectionLimit);
            }
        }
        return sb.toString();
    }

    /** 大纲引用编号 {@code #N} → 序号；非法引用按 0 处理（仅用于展示编号）。 */
    private static int refSeq(String ref) {
        try {
            return Integer.parseInt(ref.substring(1));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 物化附件正文拼接（与核验链同口径）：仅平台可直读且文本族附件注入正文；
     * 限额常量单源 {@link AttachmentContentPolicy}（每附件 / 总计上限超限截断并以结构化标注行收尾）；
     * 无可用附件返回空串（调用方以 displayText 兜底）。
     */
    private String buildAttachmentText(Long subTaskId) {
        List<Attachment> attachments = attachmentService.listActive(subTaskId);
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int totalChars = 0;
        for (Attachment att : attachments) {
            if (!attachmentService.isContentLoadable(att)
                    || !AttachmentContentPolicy.isTextual(att.getMimeType(), att.getFileName())) {
                continue;
            }
            String content = readAttachmentUtf8(att);
            if (content == null || content.isEmpty()) {
                continue;
            }
            int originalChars = content.length();
            boolean perFileTruncated = false;
            if (content.length() > AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT) {
                // P-1 防御：行边界回退截断（避免拦腰切断 URL/代码行）
                content = TextTruncator.truncateAtLineBoundary(
                        content, AttachmentContentPolicy.ATTACHMENT_CONTENT_PER_FILE_LIMIT);
                perFileTruncated = true;
            }
            if (totalChars + content.length() > AttachmentContentPolicy.ATTACHMENT_CONTENT_TOTAL_LIMIT) {
                int remaining = AttachmentContentPolicy.ATTACHMENT_CONTENT_TOTAL_LIMIT - totalChars;
                if (remaining > 0) {
                    String partial = TextTruncator.truncateAtLineBoundary(content, remaining);
                    sb.append("#### 附件：").append(att.getFileName()).append('\n')
                            .append(partial).append('\n');
                }
                sb.append("[TRUNCATED] 附件正文总量超限，完整内容见附件文件\n");
                break;
            }
            totalChars += content.length();
            sb.append("#### 附件：").append(att.getFileName()).append('\n').append(content).append('\n');
            if (perFileTruncated) {
                sb.append("[TRUNCATED] file=").append(att.getFileName())
                        .append(" shown=").append(content.length())
                        .append(" total=").append(originalChars)
                        .append(" reason=per_file_limit\n");
            }
        }
        return sb.toString().trim();
    }

    /** 读取可直读附件 UTF-8 正文；不可读/为空返回 null（跳过该附件，不阻断整体注入）。 */
    private String readAttachmentUtf8(Attachment att) {
        try {
            byte[] bytes = attachmentService.loadContent(att.getId());
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("附件内容读取失败，报告链跳过该附件: attachmentId={}, err={}", att.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Markdown 块级截断：
     * ① 段落（空行）边界首选；② limit 落在代码围栏内时回退到该块打开围栏前、
     * 落在闭合围栏后时断在闭合处——绝不拦腰切断代码行；③ 表格行边界回退（行首断，行完整）；
     * 找不到任何边界时按字符裸切（保底不抛）。
     */
    public static String truncateAtBlockBoundary(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text == null ? "" : text;
        }
        String head = text.substring(0, limit);
        int floor = Math.max(0, limit / 2);
        // ① 段落边界（首选：信息损失最小）
        int paragraph = head.lastIndexOf("\n\n");
        if (paragraph >= floor) {
            return head.substring(0, paragraph);
        }
        // ② 代码围栏：围栏计数为奇数（limit 在代码块内）回退到打开围栏前；
        // 偶数（在块外）断到最后一个闭合围栏末尾，保证输出块始终闭合
        int fenceCount = countFenceTokens(head);
        if (fenceCount > 0) {
            int lastFence = head.lastIndexOf("```");
            if (fenceCount % 2 == 1) {
                return head.substring(0, lastFence);
            }
            return head.substring(0, lastFence + 3);
        }
        // ③ 表格行边界：断在上一个行首（该行完整，不拦腰）
        int tableRow = head.lastIndexOf("\n|");
        if (tableRow >= floor) {
            return head.substring(0, tableRow);
        }
        return head;
    }

    /** 统计文本中 ``` 围栏标记出现次数。 */
    private static int countFenceTokens(String text) {
        int count = 0;
        int idx = text.indexOf("```");
        while (idx >= 0) {
            count++;
            idx = text.indexOf("```", idx + 3);
        }
        return count;
    }

    /** 读取 context.lastExecution.output（统一走 SubTaskOutputExtractor，读取 subTask.getContext()，与 TaskDeliverableService 同一事实源；入参可为 null）。 */
    private static String extractExecutionOutput(SubTask subTask) {
        return SubTaskOutputExtractor.extractExecutionOutput(subTask == null ? null : subTask.getContext());
    }

    /**
     * 标记报告生成最终失败（FAILED）。
     *
     * <p>只负责状态回写，失败不外抛（避免掩盖原始 LLM 异常）；置 FAILED 后手动端点可重试。</p>
     */
    private void markFailed(Long taskId, String error) {
        try {
            // §12.5 状态机收口：GENERATING -> FAILED 经 CAS 迁移，不直接改状态字段。
            // CAS 未命中（状态已被接管）时静默跳过——不覆盖新链状态。
            taskService.transitFinalReportStatus(taskId, FinalReportStatus.GENERATING, FinalReportStatus.FAILED);
        } catch (Exception e) {
            log.warn("标记整合报告生成失败状态异常: taskId={}, err={}", taskId, e.getMessage());
        }
        log.debug("整合报告生成失败已标记: taskId={}, reason={}", taskId, error);
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
    private void backfillIterationsQuietly(Long taskId, List<SubTask> sections, PlannerAgentRef planner) {
        try {
            taskIterationService.backfillForTask(taskId, sections, planner != null ? planner.id() : null);
        } catch (Exception e) {
            log.warn("task_iteration 回填失败（不影响报告生成）: taskId={}, err={}",
                    taskId, e.getMessage());
            taskTimelineService.recordEvent(taskId, null, "task_iteration_backfill_failed",
                    AgentRole.PLANNER, planner != null ? planner.id() : null,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * 大纲规划产物（出纲 LLM 的 JSON 输出解析结果）：
     * 主线论点 / 章节顺序（含子任务归属引用 #N）/ 矛盾清单。
     */
    record OutlinePlan(List<String> mainTheses, List<OutlineSection> sections, List<String> conflicts) {

        /** 单个规划章节：主题名 + 覆盖的子任务引用编号（#N，允许多对一/一对多）。 */
        record OutlineSection(String title, List<String> subTaskRefs) {
        }
    }
}
