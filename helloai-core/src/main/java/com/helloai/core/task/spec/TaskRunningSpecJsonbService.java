package com.helloai.core.task.spec;

import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase A 实现：以 {@code task.context.runningSpec} JSONB 存储 Task Running Spec。
 *
 * <p>Phase B（{@link TaskRunningSpecTableService}）采用独立表，
 * 本实现保留为 fallback，通过配置切换。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "helloai.task-running-spec.storage", havingValue = "jsonb", matchIfMissing = true)
public class TaskRunningSpecJsonbService implements TaskRunningSpecService {

    private static final String RUNNING_SPEC_KEY = "runningSpec";

    private final TaskService taskService;

    @Override
    public TaskRunningSpec getOrCreate(Long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            return TaskRunningSpec.EMPTY;
        }
        return readFromTask(task);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initialize(Long taskId, TaskBaseline baseline) {
        Task task = requireTask(taskId);
        TaskRunningSpec current = readFromTask(task);
        if (current.baseline() != null) {
            log.debug("TaskRunningSpec baseline 已存在，跳过初始化: taskId={}", taskId);
            return;
        }
        TaskRunningSpec updated = current.toBuilder()
                .baseline(baseline)
                .build();
        writeToTask(task, updated);
        taskService.updateById(task);
        log.info("TaskRunningSpec baseline 初始化完成: taskId={}", taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendExecutionRecord(Long taskId, ExecutionRecord record) {
        Task task = requireTask(taskId);
        TaskRunningSpec current = readFromTask(task);

        // 按 subTaskId 去重：rework 时覆盖旧记录，避免同一子任务堆积多条记录
        List<ExecutionRecord> deduped = new ArrayList<>();
        boolean replaced = false;
        for (ExecutionRecord existing : current.executionRecords()) {
            if (existing.subTaskId().equals(record.subTaskId())) {
                deduped.add(record);
                replaced = true;
                log.debug("ExecutionRecord 已覆盖（rework）: taskId={}, subTaskId={}", taskId, record.subTaskId());
            } else {
                deduped.add(existing);
            }
        }
        if (!replaced) {
            deduped.add(record);
        }

        // 基于去重后的全量记录重新编译 ContextSummary
        String newSummary = compileSummaryFromRecords(deduped);

        TaskRunningSpec.Builder builder = TaskRunningSpec.builder()
                .version(current.version())
                .baseline(current.baseline());
        for (ExecutionRecord rec : deduped) {
            builder.addExecutionRecord(rec);
        }
        TaskRunningSpec updated = builder.contextSummary(newSummary).build();

        writeToTask(task, updated);
        taskService.updateById(task);
        log.info("ExecutionRecord 已写入: taskId={}, subTaskId={}, totalRecords={}, deduped={}",
                taskId, record.subTaskId(), updated.executionRecords().size(), replaced);
    }

    @Override
    public String buildExecutorPromptSection(Long taskId) {
        TaskRunningSpec spec = getOrCreate(taskId);
        if (spec.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 任务全局上下文（Task Running Spec）\n");

        // Baseline
        TaskBaseline bl = spec.baseline();
        if (bl != null) {
            sb.append("\n### 总体目标\n");
            sb.append(bl.goal()).append('\n');
            if (bl.constraints() != null && !bl.constraints().isBlank()) {
                sb.append("\n### 平台约束\n");
                sb.append(bl.constraints()).append('\n');
            }
        }

        // Context Summary
        String cs = spec.contextSummary();
        if (cs != null && !cs.isBlank()) {
            sb.append("\n### 全局进度与关键事实\n");
            sb.append(cs).append('\n');
        }

        // 已完成的执行记录
        if (!spec.executionRecords().isEmpty()) {
            sb.append("\n### 前置任务摘要\n");
            int idx = 1;
            for (ExecutionRecord rec : spec.executionRecords()) {
                String title = rec.title() != null ? rec.title() : ("子任务#" + rec.subTaskId());
                sb.append("#### ").append(idx++).append(". ").append(title).append('\n');
                if (rec.summary() != null) {
                    sb.append("**产出**: ").append(rec.summary()).append('\n');
                }
                if (!rec.downstreamNotes().isEmpty()) {
                    sb.append("**下游须知**:\n");
                    for (String note : rec.downstreamNotes()) {
                        sb.append("- ").append(note).append('\n');
                    }
                }
                if (!rec.deliverables().isEmpty()) {
                    sb.append("**产出文件**: ");
                    sb.append(String.join(", ", rec.deliverables())).append('\n');
                }
            }
        }
        return sb.toString();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void compileContextSummary(Long taskId) {
        Task task = requireTask(taskId);
        TaskRunningSpec current = readFromTask(task);
        String summary = compileSummaryFromRecords(current.executionRecords());
        if (summary == null || summary.isBlank()) {
            return;
        }
        TaskRunningSpec updated = current.toBuilder()
                .contextSummary(summary)
                .build();
        writeToTask(task, updated);
        taskService.updateById(task);
        log.debug("ContextSummary 已重新编译: taskId={}", taskId);
    }

    // ──────────────── 内部 ────────────────

    private Task requireTask(Long taskId) {
        Task task = taskService.getById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return task;
    }

    @SuppressWarnings("unchecked")
    private TaskRunningSpec readFromTask(Task task) {
        Map<String, Object> ctx = task.getContext();
        if (ctx == null) {
            return TaskRunningSpec.EMPTY;
        }
        Object specObj = ctx.get(RUNNING_SPEC_KEY);
        if (specObj instanceof Map<?, ?> specMap) {
            return TaskRunningSpec.fromMap((Map<String, Object>) specMap);
        }
        return TaskRunningSpec.EMPTY;
    }

    private void writeToTask(Task task, TaskRunningSpec spec) {
        Map<String, Object> ctx = task.getContext();
        if (ctx == null) {
            ctx = new LinkedHashMap<>();
            task.setContext(ctx);
        } else {
            // JacksonTypeHandler 反序列化的 Map 可能不可变，用 HashMap 包装
            ctx = new HashMap<>(ctx);
            task.setContext(ctx);
        }
        ctx.put(RUNNING_SPEC_KEY, spec.toMap());
    }

    /**
     * 从所有 ExecutionRecords 全量重新编译摘要。
     */
    private String compileSummaryFromRecords(List<ExecutionRecord> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已完成 ").append(records.size()).append(" 个子任务：\n");
        int idx = 1;
        for (ExecutionRecord rec : records) {
            sb.append(idx++).append(". **").append(rec.title() != null ? rec.title() : ("#" + rec.subTaskId()))
                    .append("**: ").append(rec.summary()).append('\n');
            if (!rec.downstreamNotes().isEmpty()) {
                for (String note : rec.downstreamNotes()) {
                    sb.append("   - ").append(note).append('\n');
                }
            }
        }
        return sb.toString().trim();
    }
}
