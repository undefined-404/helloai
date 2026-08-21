package com.helloai.core.task.service.impl;

import com.helloai.core.task.entity.TaskExecutionRecordEntity;
import com.helloai.core.task.entity.TaskRunningSpecEntity;
import com.helloai.core.task.mapper.TaskExecutionRecordMapper;
import com.helloai.core.task.mapper.TaskRunningSpecMapper;
import com.helloai.core.task.service.TaskRunningSpecService;
import com.helloai.core.task.spec.ExecutionRecord;
import com.helloai.core.task.spec.TaskBaseline;
import com.helloai.core.task.spec.TaskRunningSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase B 实现：TaskRunningSpecService 接口的独立表实现。
 *
 * <p>启用条件：配置 {@code helloai.task-running-spec.storage=table}；
 * 默认（未配置或 {@code jsonb}）仍由 {@link TaskRunningSpecJsonbServiceImpl} 承载，
 * 保持向后兼容，可一键切换做 A/B 对比或故障回退。</p>
 *
 * <p>实现要点：
 * <ul>
 *   <li>{@code task_running_spec}（1 行 / task）：存 Baseline（JSONB）与 ContextSummary</li>
 *   <li>{@code task_execution_record}（N 行 / task）：(task_id, sub_task_id) 唯一，rework 时 DELETE + INSERT</li>
 *   <li>每次写记录后重算 ContextSummary（基于去重后的全量记录），保持与 Phase A JSONB 一致的语义</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "helloai.task-running-spec.storage", havingValue = "table", matchIfMissing = false)
public class TaskRunningSpecTableServiceImpl implements TaskRunningSpecService {

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendExecutionRecord(Long taskId, ExecutionRecord record) {
        // DB 层 UPSERT：先 DELETE 旧记录（按 taskId+subTaskId），再 INSERT 新记录
        recordMapper.physicalDeleteByTaskIdAndSubTaskId(taskId, record.subTaskId());

        TaskExecutionRecordEntity entity = toEntity(taskId, record);
        recordMapper.insert(entity);

        // 基于去重后的全量记录重新编译 ContextSummary 并写回
        String newSummary = compileSummaryFromRecords(loadExecutionRecords(taskId));
        specMapper.updateContextSummary(taskId, newSummary);

        log.info("ExecutionRecord 已写入: taskId={}, subTaskId={}, deduped=false",
                taskId, record.subTaskId());
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
        TaskRunningSpec spec = getOrCreate(taskId);
        if (spec.isEmpty()) {
            return "";
        }
        return JsonbPromptRenderer.render(spec);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void compileContextSummary(Long taskId) {
        String summary = compileSummaryFromRecords(loadExecutionRecords(taskId));
        if (summary == null || summary.isBlank()) {
            return;
        }
        specMapper.updateContextSummary(taskId, summary);
        log.debug("ContextSummary 已重新编译: taskId={}", taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateContract(Long taskId, Map<String, Object> contract) {
        // DB 层 UPDATE：契约写入独立列（JSONB），行级更新天然并发安全
        // （Phase A JSONB 实现按 taskId 分段锁串行，两实现语义一致）
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

    /**
     * 与 Phase A JsonbService 保持同一编译逻辑：N 条已去重记录拼接成一连贯段落。
     */
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

    // ──────────────── 静态提示词渲染（与 Phase A 共享，避免重复实现） ────────────────

    /**
     * 把 TaskRunningSpec 渲染成 Markdown 提示词段——逻辑与 Phase A 完全一致，
     * 抽到这里共用，避免两套实现各写一遍漂移。
     */
    private static final class JsonbPromptRenderer {
        static String render(TaskRunningSpec spec) {
            StringBuilder sb = new StringBuilder();
            sb.append("## 任务全局上下文（Task Running Spec）\n");
            TaskBaseline bl = spec.baseline();
            if (bl != null) {
                sb.append("\n### 总体目标\n");
                sb.append(bl.goal()).append('\n');
                if (bl.constraints() != null && !bl.constraints().isBlank()) {
                    sb.append("\n### 平台约束\n");
                    sb.append(bl.constraints()).append('\n');
                }
            }
            String cs = spec.contextSummary();
            if (cs != null && !cs.isBlank()) {
                sb.append("\n### 全局进度与关键事实\n");
                sb.append(cs).append('\n');
            }
            // 任务契约（契约先行拆解）：与 Jsonb 侧 buildExecutorPromptSection
            // 渲染逻辑保持一致，作为独立二级节全局注入所有下游执行 Prompt
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
            // 子任务执行记录明细不在此全量铺开：依赖上下文由调用方按 dependsOnIdList
            // 经 findRecord 逐条收集渲染（见 SubTaskExecutionService.buildDependencySection）
            return sb.toString();
        }
    }
}
