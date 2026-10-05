package com.helloai.core.task.service.impl;

import com.helloai.core.task.entity.TaskExecutionRecordEntity;
import com.helloai.core.task.entity.TaskRunningSpecEntity;
import com.helloai.core.task.mapper.TaskExecutionRecordMapper;
import com.helloai.core.task.mapper.TaskRunningSpecMapper;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.spec.ExecutionRecord;
import com.helloai.core.task.spec.TaskBaseline;
import com.helloai.core.task.spec.TaskRunningSpec;
import com.helloai.core.task.spec.TaskRunningSpecPromptRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link TaskRunningSpecService} 的唯一实现（存于独立表）。
 *
 * <p>历史上曾有「{@code task.context.runningSpec} JSONB / 独立表」双实现；2026-10-02 按
 * **分布式改造目标**二选一，保留独立表、删除 JSONB 实现与
 * {@code helloai.task-running-spec.storage} 开关。判据：JSONB 形态依赖
 * 「读整行 task → 改 context → 整行写回」+ JVM 本地分段锁串行化，**锁不跨实例**；
 * 独立表用行级 upsert（{@code (task_id, sub_task_id)} 唯一）天然无覆盖竞态、
 * 无需任何锁，与「单应用 → 分布式」改造方向一致。</p>
 *
 * <p>实现要点：
 * <ul>
 *   <li>{@code task_running_spec}（1 行 / task）：存 Baseline（JSONB）与 ContextSummary</li>
 *   <li>{@code task_execution_record}（N 行 / task）：(task_id, sub_task_id) 唯一，rework 时 DELETE + INSERT</li>
 *   <li>每次写记录后按已去重的全量记录重算 ContextSummary</li>
 *   <li>Prompt 段渲染已外移 {@link TaskRunningSpecPromptRenderer}（§38 Prompt 规范）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRunningSpecServiceImpl implements TaskRunningSpecService {

    private final TaskRunningSpecMapper specMapper;
    private final TaskExecutionRecordMapper recordMapper;

    @Override
    public TaskRunningSpec getOrCreate(Long taskId) {
        TaskRunningSpecEntity entity = specMapper.selectByTaskId(taskId);
        if (entity == null) {
            return TaskRunningSpec.EMPTY;
        }
        List<ExecutionRecord> records = loadExecutionRecords(taskId);
        return assembleDomain(entity, records);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initialize(Long taskId, TaskBaseline baseline) {
        TaskRunningSpecEntity existing = specMapper.selectByTaskId(taskId);
        if (existing != null && existing.getBaseline() != null) {
            log.debug("TaskRunningSpec baseline 已存在，跳过初始化: taskId={}", taskId);
            return;
        }
        TaskRunningSpecEntity entity = new TaskRunningSpecEntity();
        entity.setTaskId(taskId);
        entity.setVersion(1);
        entity.setBaseline(baseline != null ? baseline.toMap() : null);
        if (existing == null) {
            specMapper.insert(entity);
        } else {
            existing.setBaseline(baseline != null ? baseline.toMap() : null);
            existing.setContextSummary(null);
            specMapper.updateById(existing);
        }
        log.info("TaskRunningSpec baseline 初始化完成: taskId={}", taskId);
    }

    /**
     * 追加一条执行记录并重算 ContextSummary。
     *
     * <p><b>复杂度（明示取舍）</b>：每追加 1 条即按当前全量记录重算 summary
     * （一次全表读 + 内存拼接），故整任务累计 O(n²)（n = 子任务数）。这是
     * 「summary 必须与全量去重记录一致」的刻意取舍；n 的量级为单任务子任务数
     * （通常 &lt; 30），非瓶颈。若将来 n 显著增大，再改为增量维护（须同时维护去重不变量）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendExecutionRecord(Long taskId, ExecutionRecord record) {
        // 按 taskId 取 Postgres advisory 事务锁：跨实例串行化「建 spec 行 + 记录 upsert」。
        // 为什么必须在事前串行化而不是事后补救：Postgres 一旦报 duplicate key，
        // 整个事务进入 aborted（25P02），后续语句全部失败 —— 捕获 DuplicateKeyException
        // 后在同一事务里改走 UPDATE 是无效的（B 级 IT 实测）。
        specMapper.acquireTaskLock(String.valueOf(taskId));

        // DB 层 UPSERT：先 DELETE 旧记录（按 taskId+subTaskId），再 INSERT 新记录。
        // 持锁后不存在「两个线程都 DELETE 完再各自 INSERT」的窗口，唯一索引不再被撞。
        recordMapper.physicalDeleteByTaskIdAndSubTaskId(taskId, record.subTaskId());
        recordMapper.insert(toEntity(taskId, record));

        // 基于去重后的全量记录重新编译 ContextSummary 并写回
        String newSummary = compileSummaryFromRecords(loadExecutionRecords(taskId));
        persistContextSummary(taskId, newSummary);

        log.info("ExecutionRecord 已写入: taskId={}, subTaskId={}",
                taskId, record.subTaskId());
    }

    /**
     * 回写 ContextSummary：spec 行不存在时补建一行，避免编译结果被静默丢弃。
     *
     * <p>2026-10-02 修复：原实现只做 {@code UPDATE ... WHERE task_id=?}，
     * 而 spec 行由 Planner 的 {@link #initialize} 创建。未初始化 baseline 的历史任务
     * （或 initialize 尚未执行的竞态窗口）下该 UPDATE 影响 0 行 —— 无异常、无日志，
     * 编译结果直接丢失（B 级 IT 用例 1 实测查不到 context_summary）。</p>
     */
    private void persistContextSummary(Long taskId, String summary) {
        // 与 appendExecutionRecord 同一把 advisory 锁（可重入）：串行化「补建 spec 行」
        specMapper.acquireTaskLock(String.valueOf(taskId));
        if (specMapper.updateContextSummary(taskId, summary) > 0) {
            return;
        }
        TaskRunningSpecEntity entity = new TaskRunningSpecEntity();
        entity.setTaskId(taskId);
        entity.setVersion(1);
        entity.setContextSummary(summary);
        specMapper.insert(entity);
    }

    @Override
    public ExecutionRecord findRecord(Long taskId, Long subTaskId) {
        if (taskId == null || subTaskId == null) {
            return null;
        }
        TaskExecutionRecordEntity entity = recordMapper.selectByTaskIdAndSubTaskId(taskId, subTaskId);
        return entity != null ? fromEntity(entity) : null;
    }

    @Override
    public String buildExecutorPromptSection(Long taskId) {
        return TaskRunningSpecPromptRenderer.render(getOrCreate(taskId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void compileContextSummary(Long taskId) {
        String summary = compileSummaryFromRecords(loadExecutionRecords(taskId));
        if (summary == null || summary.isBlank()) {
            return;
        }
        persistContextSummary(taskId, summary);
        log.debug("ContextSummary 已重新编译: taskId={}", taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateContract(Long taskId, Map<String, Object> contract) {
        // DB 层 UPDATE：契约写入独立列（JSONB），行级更新天然并发安全
        specMapper.updateContract(taskId, contract);
        log.info("TaskRunningSpec 契约已写入: taskId={}, contractKeys={}",
                taskId, contract != null ? contract.keySet() : null);
    }

    // ──────────────── 内部 ────────────────

    private List<ExecutionRecord> loadExecutionRecords(Long taskId) {
        List<TaskExecutionRecordEntity> entities = recordMapper.selectByTaskId(taskId);
        List<ExecutionRecord> records = new ArrayList<>(entities.size());
        for (TaskExecutionRecordEntity e : entities) {
            records.add(fromEntity(e));
        }
        return records;
    }

    private TaskRunningSpec assembleDomain(TaskRunningSpecEntity entity, List<ExecutionRecord> records) {
        TaskBaseline baseline = entity.getBaseline() != null
                ? TaskBaseline.fromMap(entity.getBaseline())
                : null;
        TaskRunningSpec.Builder builder = TaskRunningSpec.builder()
                .version(entity.getVersion() != null ? entity.getVersion() : 1)
                .baseline(baseline)
                .contextSummary(entity.getContextSummary())
                .contract(entity.getContract())
                .lastUpdatedAt(entity.getUpdateTime() != null
                        ? entity.getUpdateTime().toString()
                        : OffsetDateTime.now().toString());
        for (ExecutionRecord r : records) {
            builder.addExecutionRecord(r);
        }
        return builder.build();
    }

    private static TaskExecutionRecordEntity toEntity(Long taskId, ExecutionRecord record) {
        TaskExecutionRecordEntity e = new TaskExecutionRecordEntity();
        e.setTaskId(taskId);
        e.setSubTaskId(record.subTaskId());
        e.setAgentId(record.agentId());
        e.setTitle(record.title());
        e.setSummary(record.summary());
        e.setKeyDecisions(record.keyDecisions());
        e.setDownstreamNotes(record.downstreamNotes());
        e.setDeliverables(record.deliverables());
        return e;
    }

    private static ExecutionRecord fromEntity(TaskExecutionRecordEntity e) {
        ExecutionRecord.Builder b = ExecutionRecord.builder()
                .subTaskId(e.getSubTaskId())
                .title(e.getTitle())
                .agentId(e.getAgentId())
                .summary(e.getSummary());
        if (e.getKeyDecisions() != null) for (String s : e.getKeyDecisions()) b.addKeyDecision(s);
        if (e.getDownstreamNotes() != null) for (String s : e.getDownstreamNotes()) b.addDownstreamNote(s);
        if (e.getDeliverables() != null) for (String s : e.getDeliverables()) b.addDeliverable(s);
        if (e.getCreateTime() != null) b.completedAt(e.getCreateTime().toString());
        return b.build();
    }

    /** 把 N 条已去重记录拼接成一连贯段落（ContextSummary）。 */
    private String compileSummaryFromRecords(List<ExecutionRecord> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已完成 ").append(records.size()).append(" 个子任务：\n");
        int idx = 1;
        for (ExecutionRecord rec : records) {
            sb.append(idx++).append(". **")
                    .append(rec.title() != null ? rec.title() : ("#" + rec.subTaskId()))
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
