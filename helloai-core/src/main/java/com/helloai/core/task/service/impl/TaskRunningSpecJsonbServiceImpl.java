package com.helloai.core.task.service.impl;

import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.spec.ExecutionRecord;
import com.helloai.core.task.spec.TaskBaseline;
import com.helloai.core.task.spec.TaskRunningSpec;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase A 实现：以 {@code task.context.runningSpec} JSONB 存储 Task Running Spec。
 *
 * <p>Phase B（{@link TaskRunningSpecTableServiceImpl}）采用独立表，
 * 本实现保留为 fallback，通过配置切换。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "helloai.task-running-spec.storage", havingValue = "jsonb", matchIfMissing = true)
public class TaskRunningSpecJsonbServiceImpl implements TaskRunningSpecService {

    private static final String RUNNING_SPEC_KEY = "runningSpec";

    /**
     * taskId 粒度分段锁：JSONB 存储的 append/initialize 是"读-改-写"非原子操作，
     * 同一任务下多个子任务（尤其多前置并行）完成时并发回填可能互相覆盖丢记录。
     * 锁住整段保证单实例下串行；多实例部署需切 Phase B（独立表行级天然安全）或升级 Redis 锁。
     */
    private final ConcurrentHashMap<Long, Object> taskLocks = new ConcurrentHashMap<>();

    private final TaskService taskService;

    /** 获取 taskId 粒度锁对象（分段锁，无锁清理——任务数有限，锁对象可复用）。 */
    private Object lockFor(Long taskId) {
        return taskLocks.computeIfAbsent(taskId, k -> new Object());
    }

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
        synchronized (lockFor(taskId)) {
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
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendExecutionRecord(Long taskId, ExecutionRecord record) {
        synchronized (lockFor(taskId)) {
            Task task = requireTask(taskId);
            TaskRunningSpec current = readFromTask(task);

            // 按 subTaskId 去重：rework 时覆盖旧记录，避免同一子任务堆积多条记录；
            // 不同 subTaskId（多前置）互不覆盖，全部保留
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
                    .baseline(current.baseline())
                    .contract(current.contract());
            for (ExecutionRecord rec : deduped) {
                builder.addExecutionRecord(rec);
            }
            TaskRunningSpec updated = builder.contextSummary(newSummary).build();

            writeToTask(task, updated);
            taskService.updateById(task);
            log.info("ExecutionRecord 已写入: taskId={}, subTaskId={}, totalRecords={}, deduped={}",
                    taskId, record.subTaskId(), updated.executionRecords().size(), replaced);
        }
    }

    @Override
    public ExecutionRecord findRecord(Long taskId, Long subTaskId) {
        if (taskId == null || subTaskId == null) {
            return null;
        }
        TaskRunningSpec spec = getOrCreate(taskId);
        for (ExecutionRecord rec : spec.executionRecords()) {
            if (subTaskId.equals(rec.subTaskId())) {
                return rec;
            }
        }
        return null;
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

        // Context Summary（全局进度；子任务明细由调用方按依赖注入，不在此全量铺开）
        String cs = spec.contextSummary();
        if (cs != null && !cs.isBlank()) {
            sb.append("\n### 全局进度与关键事实\n");
            sb.append(cs).append('\n');
        }

        // 任务契约（契约先行拆解）：契约定义子任务产出回流后，
        // 作为独立二级节全局注入所有下游执行 Prompt（与 Table 侧
        // JsonbPromptRenderer 渲染逻辑保持一致）
        Map<String, Object> contract = spec.contract();
        if (contract != null && !contract.isEmpty()) {
            sb.append("\n## 任务契约\n\n");
            Object title = contract.get("title");
            if (title != null && !String.valueOf(title).isBlank()) {
                sb.append("契约来源：").append(title).append("\n\n");
            }
            Object content = contract.get("content");
            if (content != null && !String.valueOf(content).isBlank()) {
                sb.append(content).append('\n');
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateContract(Long taskId, Map<String, Object> contract) {
        synchronized (lockFor(taskId)) {
            Task task = requireTask(taskId);
            TaskRunningSpec current = readFromTask(task);
            TaskRunningSpec updated = current.toBuilder()
                    .contract(contract)
                    .build();
            writeToTask(task, updated);
            taskService.updateById(task);
            log.info("TaskRunningSpec 契约已写入: taskId={}, contractKeys={}",
                    taskId, contract != null ? contract.keySet() : null);
        }
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
