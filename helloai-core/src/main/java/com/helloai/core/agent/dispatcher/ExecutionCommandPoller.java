package com.helloai.core.agent.dispatcher;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandConsumer;
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
 * <p>对应架构设计参考 §5.1 第一阶段「将本地 Spring 事件消费者继续收口到独立 MQ / DB poller 消费模型」，
 * 以及 §5.2 第二阶段「DB Poller 主线化」。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *     <li>EVENT 模式：定时扫描 {@code agent_execution_record} 表中长时间未被消费的 PENDING 行（孤儿），重发到消费链路；</li>
 *     <li>POLLER / BOTH 模式：定时扫描「所有」PENDING 行作为主消费路径；</li>
 *     <li>不依赖 Spring 事务事件、不依赖 @Async 线程池，跨进程 / 跨实例可独立工作。</li>
 * </ul>
 *
 * <h3>与主路径的关系</h3>
 * <p>本 Poller 在 EVENT 模式下为<b>兜底路径</b>，主路径为事务事件触发
 * （{@code SubTaskAutoExecutionDispatcher → ExecutionCommandService →
 * publishEvent → @Async @TransactionalEventListener → LocalExecutionCommandConsumer}）。</p>
 * <p>本 Poller 在 POLLER 模式下为<b>主路径</b>，命令创建服务不发布本地事务事件，
 * 所有 PENDING 记录的推进都走本 Poller，跨进程 / 跨实例可独立扩容。</p>
 * <p>本 Poller 在 BOTH 模式下与事件消费者并行运行，由 Consumer 内部 CAS markRunning 保证幂等。</p>
 *
 * <h3>幂等保护</h3>
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
 *     <li>{@code helloai.execution.poller-interval-ms}（默认 1000）</li>
 *     <li>{@code helloai.execution.poller-orphan-threshold-seconds}（默认 60；仅 EVENT 模式使用）</li>
 *     <li>{@code helloai.execution.poller-batch-size}（默认 20）</li>
 *     <li>{@code helloai.execution.consumer-mode}（默认 POLLER）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "helloai.execution.poller-enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionCommandPoller {

    private final AgentExecutionRecordService agentExecutionRecordService;
    private final ExecutionCommandConsumer executionCommandConsumer;
    private final TaskTimelineService taskTimelineService;
    private final SubTaskService subTaskService;
    private final AgentExecutionProperties executionProperties;

    /**
     * DB Poller 周期扫描入口。
     *
     * <p>{@code fixedDelayString} 直接绑定配置项，支持通过 yaml / 环境变量动态调整扫描周期。</p>
     */
    @Scheduled(fixedDelayString = "${helloai.execution.poller-interval-ms:1000}")
    public void poll() {
        if (!executionProperties.isPollerEnabled()) {
            return;
        }

        int batchSize = executionProperties.getPollerBatchSize();

        // POLLER / BOTH 模式：扫所有 PENDING 作为主路径
        // EVENT 模式：仅扫孤儿 PENDING 作为兜底
        List<AgentExecutionRecord> candidates;
        String scanType;
        if (executionProperties.isPollerMain()) {
            candidates = agentExecutionRecordService.listAllPending(batchSize);
            scanType = "listAllPending";
            // POLLER 主消费模式下推荐较短的扫描间隔
            if (executionProperties.getPollerIntervalMs() > 5000L) {
                log.warn("Poller 主消费模式 (consumer-mode=POLLER|BOTH) 下扫描周期 {} ms 偏长，建议调短到 1000-3000 ms 以避免主路径延迟",
                        executionProperties.getPollerIntervalMs());
            }
        } else {
            int threshold = executionProperties.getPollerOrphanThresholdSeconds();
            candidates = agentExecutionRecordService.listOrphanPending(threshold, batchSize);
            scanType = "listOrphanPending";
        }

        if (candidates.isEmpty()) {
            return;
        }

        log.info("DB Poller 扫描到 {} 条待处理 PENDING (mode={}, scan={}, batchSize={})",
                candidates.size(), executionProperties.getConsumerMode(), scanType, batchSize);

        for (AgentExecutionRecord record : candidates) {
            try {
                processRecord(record, scanType);
            } catch (Exception e) {
                // 单条记录异常不影响整批扫描
                log.error("DB Poller 处理 PENDING 异常: recordId={}, eventId={}, subTaskId={}, scan={}",
                        record.getId(), record.getEventId(), record.getSubTaskId(), scanType, e);
            }
        }
    }

    private void processRecord(AgentExecutionRecord record, String scanType) {
        // 1. 标记已 Poller 触及（防止下个周期重复扫到，同时留下审计痕迹）
        agentExecutionRecordService.markPolled(record.getId());

        // 2. 数据完整性校验：缺关键字段则跳过（理论上不会发生，调度侧已做校验，防御性兜底）
        if (record.getSubTaskId() == null || record.getAgentId() == null || record.getAccessType() == null) {
            log.warn("DB Poller 跳过：记录缺关键字段 recordId={}, eventId={}, scan={}",
                    record.getId(), record.getEventId(), scanType);
            return;
        }

        // 3. 恢复 subTask 用于 timeline 记录
        SubTask subTask = subTaskService.getById(record.getSubTaskId());

        // 4. 记录 timeline：Poller 处理事件
        //    - listAllPending（主消费）使用 sub_task_execution_command_polled_main
        //    - listOrphanPending（兜底）使用 sub_task_execution_command_poll_recovery
        String timelineEvent = "listAllPending".equals(scanType)
                ? "sub_task_execution_command_polled_main"
                : "sub_task_execution_command_poll_recovery";
        if (subTask != null) {
            taskTimelineService.recordEvent(
                    subTask.getTaskId(),
                    record.getSubTaskId(),
                    timelineEvent,
                    AgentRole.SYSTEM,
                    record.getAgentId(),
                    safeMap(
                            "recordId", record.getId(),
                            "eventId", record.getEventId(),
                            "originalTrigger", record.getTrigger(),
                            "accessType", record.getAccessType().name(),
                            "scan", scanType));
        }

        // 5. 构造 ExecutionCommand，trigger 前缀区分主路径 / 兜底
        String originalTrigger = record.getTrigger() != null ? record.getTrigger() : "unknown";
        String triggerPrefix = "listAllPending".equals(scanType) ? "poll-main:" : "poll-recovery:";
        ExecutionCommand command = ExecutionCommand.builder()
                .recordId(record.getId())
                .eventId(record.getEventId())
                .subTaskId(record.getSubTaskId())
                .agentId(record.getAgentId())
                .accessType(record.getAccessType())
                .trigger(triggerPrefix + originalTrigger)
                .build();

        // 6. 触发消费（消费内部已经包含 CAS markRunning 幂等保护）
        try {
            executionCommandConsumer.consume(command);
        } catch (BizException e) {
            // consume 已内部吞掉 BizException 走 skipped timeline，这里只是兜底防御
            log.warn("DB Poller 触发消费被拒绝: recordId={}, scan={}, error={}", record.getId(), scanType, e.getMessage());
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