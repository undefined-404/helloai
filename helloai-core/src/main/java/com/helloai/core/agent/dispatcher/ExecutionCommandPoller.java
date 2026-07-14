package com.helloai.core.agent.dispatcher;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.LocalExecutionCommandConsumer;
import com.helloai.core.entity.AgentExecutionRecord;
import com.helloai.core.entity.SubTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.helloai.core.service.AgentExecutionRecordService;
import com.helloai.core.service.SubTaskService;
import com.helloai.core.service.TaskTimelineService;

/**
 * 执行命令 DB Poller 消费者。
 *
 * <p>对应架构设计参考 §5.1 第一阶段「将本地 Spring 事件消费者继续收口到独立 MQ / DB poller 消费模型」。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *     <li>定时扫描 {@code agent_execution_record} 表中长时间未被消费的 PENDING 行；</li>
 *     <li>对这些「孤儿 PENDING」重新触发 {@link LocalExecutionCommandConsumer#consume}；</li>
 *     <li>不依赖 Spring 事务事件、不依赖 @Async 线程池，跨进程 / 跨实例可独立工作。</li>
 * </ul>
 *
 * <h3>与主路径的关系</h3>
 * <p>本 Poller 是<b>兜底路径</b>，主路径仍然是事务事件触发
 * （{@code SubTaskAutoExecutionDispatcher → ExecutionCommandService →
 * publishEvent → @Async @TransactionalEventListener → LocalExecutionCommandConsumer}）。</p>
 *
 * <p>Poller 触发的 consume 调用已经天然幂等：</p>
 * <ol>
 *     <li>本 Poller 先调用 {@code markPolled(id)} 留下扫描痕迹；</li>
 *     <li>Consumer 内部继续走 {@code markRunning(id)} CAS：PENDING→RUNNING；</li>
 *     <li>若主路径已经把 PENDING 行推进到 RUNNING/SUCCESS/FAILED/timeout，Poller 的 consume
 *         会在 markRunning 步骤被 CAS 拒绝，自然跳过；</li>
 *     <li>若主路径已经丢失（如应用重启 / @Async 线程池积压），Poller 接管并完成消费。</li>
 * </ol>
 *
 * <h3>配置项</h3>
 * <ul>
 *     <li>{@code helloai.execution.poller-enabled}（默认 true）</li>
 *     <li>{@code helloai.execution.poller-interval-ms}（默认 30000）</li>
 *     <li>{@code helloai.execution.poller-orphan-threshold-seconds}（默认 60）</li>
 *     <li>{@code helloai.execution.poller-batch-size}（默认 20）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "helloai.execution.poller-enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionCommandPoller {

    private final AgentExecutionRecordService agentExecutionRecordService;
    private final LocalExecutionCommandConsumer localExecutionCommandConsumer;
    private final TaskTimelineService taskTimelineService;
    private final SubTaskService subTaskService;
    private final AgentExecutionProperties executionProperties;

    /**
     * DB Poller 周期扫描入口。
     *
     * <p>{@code fixedDelayString} 直接绑定配置项，支持通过 yaml / 环境变量动态调整扫描周期。</p>
     */
    @Scheduled(fixedDelayString = "${helloai.execution.poller-interval-ms:30000}")
    public void poll() {
        if (!executionProperties.isPollerEnabled()) {
            return;
        }

        int threshold = executionProperties.getPollerOrphanThresholdSeconds();
        int batchSize = executionProperties.getPollerBatchSize();
        List<AgentExecutionRecord> orphans = agentExecutionRecordService.listOrphanPending(threshold, batchSize);
        if (orphans.isEmpty()) {
            return;
        }

        log.info("DB Poller 扫描到 {} 条孤儿 PENDING 命令 (threshold={}s, batchSize={})",
                orphans.size(), threshold, batchSize);

        for (AgentExecutionRecord record : orphans) {
            try {
                processOrphanRecord(record);
            } catch (Exception e) {
                // 单条记录异常不影响整批扫描
                log.error("DB Poller 处理孤儿命令异常: recordId={}, eventId={}, subTaskId={}",
                        record.getId(), record.getEventId(), record.getSubTaskId(), e);
            }
        }
    }

    private void processOrphanRecord(AgentExecutionRecord record) {
        // 1. 标记已 Poller 触及（防止下个周期重复扫到，同时留下审计痕迹）
        agentExecutionRecordService.markPolled(record.getId());

        // 2. 数据完整性校验：缺关键字段则跳过（理论上不会发生，调度侧已做校验，防御性兜底）
        if (record.getSubTaskId() == null || record.getAgentId() == null || record.getAccessType() == null) {
            log.warn("DB Poller 跳过：孤儿记录缺关键字段 recordId={}, eventId={}",
                    record.getId(), record.getEventId());
            return;
        }

        // 3. 恢复 subTask 用于 timeline 记录
        SubTask subTask = subTaskService.getById(record.getSubTaskId());

        // 4. 记录 timeline：Poller 兜底恢复事件
        if (subTask != null) {
            taskTimelineService.recordEvent(
                    subTask.getTaskId(),
                    record.getSubTaskId(),
                    "sub_task_execution_command_poll_recovery",
                    AgentRole.SYSTEM,
                    record.getAgentId(),
                    safeMap(
                            "recordId", record.getId(),
                            "eventId", record.getEventId(),
                            "originalTrigger", record.getTrigger(),
                            "accessType", record.getAccessType().name()));
        }

        // 5. 构造 ExecutionCommand，trigger 前缀 poll-recovery，便于审计追溯兜底来源
        String originalTrigger = record.getTrigger() != null ? record.getTrigger() : "unknown";
        ExecutionCommand command = ExecutionCommand.builder()
                .recordId(record.getId())
                .eventId(record.getEventId())
                .subTaskId(record.getSubTaskId())
                .agentId(record.getAgentId())
                .accessType(record.getAccessType())
                .trigger("poll-recovery:" + originalTrigger)
                .build();

        // 6. 触发消费（消费内部已经包含 CAS markRunning 幂等保护）
        try {
            localExecutionCommandConsumer.consume(command);
        } catch (BizException e) {
            // consume 已内部吞掉 BizException 走 skipped timeline，这里只是兜底防御
            log.warn("DB Poller 触发消费被拒绝: recordId={}, error={}", record.getId(), e.getMessage());
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
}