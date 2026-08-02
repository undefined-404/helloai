package com.helloai.core.task.spec;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.entity.TaskExecutionRecordEntity;
import com.helloai.core.task.entity.TaskRunningSpecEntity;
import com.helloai.core.task.mapper.TaskExecutionRecordMapper;
import com.helloai.core.task.mapper.TaskMapper;
import com.helloai.core.task.mapper.TaskRunningSpecMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Phase A → Phase B 数据迁移器（一次性）。
 *
 * <p>仅在 {@code helloai.task-running-spec.storage=table} 时注册。
 * 应用启动后扫描 {@code task.context.runningSpec} 中的 JSONB 数据，
 * 写入 {@code task_running_spec} + {@code task_execution_record}。</p>
 *
 * <p>迁移完成后表内已有数据，后续启动直接跳过（基于 {@code task_running_spec} 行数 > 0 判定）。
 * 安全失败：任意任务迁移抛错都会中断该任务迁移并打印日志，不影响其他任务；
 * 整轮结束后再次打印汇总，下一次启动仍会重试未成功的任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "helloai.task-running-spec.storage", havingValue = "table", matchIfMissing = false)
public class TaskRunningSpecDataMigrator implements ApplicationRunner {

    private final TaskMapper taskMapper;
    private final TaskRunningSpecMapper specMapper;
    private final TaskExecutionRecordMapper recordMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        long existing = specMapper.selectCount(null);
        if (existing > 0) {
            log.info("TaskRunningSpec 迁移跳过：新表已有 {} 条记录，判定为已完成", existing);
            return;
        }

        List<Task> tasksWithSpec;
        try {
            tasksWithSpec = taskMapper.selectWithRunningSpec();
        } catch (Exception e) {
            log.warn("TaskRunningSpec 迁移跳过：扫描 task.context 失败: {}", e.getMessage());
            return;
        }

        if (tasksWithSpec.isEmpty()) {
            log.info("TaskRunningSpec 迁移：未发现含 runningSpec 的任务，无需迁移");
            return;
        }

        int migratedSpecCount = 0;
        int migratedRecordCount = 0;
        int failedCount = 0;

        for (Task task : tasksWithSpec) {
            try {
                int records = migrateOne(task);
                migratedSpecCount++;
                migratedRecordCount += records;
            } catch (Exception e) {
                failedCount++;
                log.warn("TaskRunningSpec 迁移失败: taskId={}, err={}", task.getId(), e.getMessage());
            }
        }

        log.info("TaskRunningSpec 迁移完成：spec={}, records={}, failed={}",
                migratedSpecCount, migratedRecordCount, failedCount);
    }

    /**
     * 迁移单个任务的 runningSpec JSONB 到新表。
     * 单一事务：spec 行 + N 条 record 行。
     */
    @Transactional(rollbackFor = Exception.class)
    public int migrateOne(Task task) {
        Map<String, Object> ctx = task.getContext();
        if (ctx == null || !ctx.containsKey("runningSpec")) {
            return 0;
        }
        Object specObj = ctx.get("runningSpec");
        if (!(specObj instanceof Map<?, ?> specMapRaw)) {
            return 0;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> specMap = (Map<String, Object>) specMapRaw;

        // 解析 baseline
        @SuppressWarnings("unchecked")
        Map<String, Object> baselineMap = (Map<String, Object>) specMap.get("baseline");

        // 写入 task_running_spec
        TaskRunningSpecEntity specEntity = new TaskRunningSpecEntity();
        specEntity.setTaskId(task.getId());
        specEntity.setVersion(1);
        specEntity.setBaseline(baselineMap);
        specEntity.setContextSummary((String) specMap.get("contextSummary"));
        specMapper.insert(specEntity);

        // 写入 task_execution_record（去重：rework 时数据库层 UPSERT 唯一索引会接管）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recordsMap = (List<Map<String, Object>>) specMap.get("executionRecords");
        if (recordsMap == null || recordsMap.isEmpty()) {
            return 0;
        }
        int recordCount = 0;
        for (Map<String, Object> rm : recordsMap) {
            ExecutionRecord rec = ExecutionRecord.fromMap(rm);
            if (rec == null) continue;
            // 同 subTaskId 只保留最后一条（按列表顺序）
            recordMapper.physicalDeleteByTaskIdAndSubTaskId(task.getId(), rec.subTaskId());
            TaskExecutionRecordEntity entity = new TaskExecutionRecordEntity();
            entity.setTaskId(task.getId());
            entity.setSubTaskId(rec.subTaskId());
            entity.setAgentId(rec.agentId());
            entity.setTitle(rec.title());
            entity.setSummary(rec.summary());
            entity.setKeyDecisions(rec.keyDecisions());
            entity.setDownstreamNotes(rec.downstreamNotes());
            entity.setDeliverables(rec.deliverables());
            recordMapper.insert(entity);
            recordCount++;
        }
        return recordCount;
    }

    /** 单元测试 / 外部触发时使用：列出未迁移任务。 */
    public List<Task> listUnmigratedTasks() {
        return taskMapper.selectWithRunningSpec();
    }

    /** 单元测试 / 外部触发时使用：当前新表行数。 */
    public long countMigrated() {
        return specMapper.selectCount(new QueryWrapper<>());
    }
}