package com.helloai.core.agent.dispatcher;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.domain.ExecutionCommand;
import com.helloai.core.agent.mqconsumer.ExecutionCommandConsumer;
import com.helloai.core.agent.mqconsumer.LocalExecutionCommandConsumer;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.task.entity.SubTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;

/**
 * 执行命令 DB Poller 兜底扫描器。
 *
 * <p><b>T5 起重塑</b>：本类从"主消费载体"降级为"孤儿 / 超时 / 补偿兜底恢复机制"。
 * 与架构设计参考 §5.1 阶段一拍板、差距表 N6 处理建议对齐：
 * MQ 主链（{@code MqExecutionCommandConsumer}）已成为主消费路径，
 * 本 Poller 保留作为 MQ 主链异常（broker 丢消息、Consumer Bean 未注册、JVM 异常退出、
 * 主消费路径被 disable）的恢复机制，不再扫全量 PENDING 作为主路径。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *     <li><b>所有模式统一扫描孤儿 PENDING</b>：{@code listOrphanPending(threshold, batchSize)}，
 *         扫描条件 {@code status='PENDING' AND (last_attempt_at IS NULL OR last_attempt_at < now - thresholdSeconds)}；</li>
 *     <li><b>不区分 consumer-mode</b>：无论 EVENT/POLLER/BOTH，本 Poller 行为一致——只兜底、不主推。</li>
 *     <li><b>不依赖 Spring 事务事件 / @Async 线程池</b>，跨进程/跨实例可独立工作。</li>
 * </ul>
 *
 * <h3>与主路径的关系</h3>
 * <p>任何模式下，本 Poller 都是兜底路径；主消费路径失效时它接管重新触发消费。
 * 区分仅在于主消费路径的载体：</p>
 * <ul>
 *     <li>{@code consumer-mode=EVENT}：{@code LocalExecutionCommandConsumer.onCommandCreated}
 *         （{@code @Async + @TransactionalEventListener}）作为主消费；</li>
 *     <li>{@code consumer-mode=POLLER}（默认）：{@code MqExecutionCommandConsumer}（{@code @RabbitListener}）
 *         作为主消费，读 MQ 消息委托给 {@code LocalExecutionCommandConsumer.consume}；</li>
 *     <li>{@code consumer-mode=BOTH}：本地事务事件 + MQ 双主消费并行，
 *         由 Consumer 内部 CAS {@code markRunning} 保证幂等。</li>
 * </ul>
 *
 * <h3>与 ExecutionCompensationTask 的边界</h3>
 * <p>本 Poller <em>不</em>承担"超时补偿"职责。PENDING/RUNNING 超时回收由
 * {@code ExecutionCompensationTask}（helloai-job）独立承担：30s 周期扫 create_time < now - pendingTimeoutMinutes
 * 或 start_time < now - runningTimeoutMinutes 的记录，调 {@code markTimeout} + subTask BLOCKED；
 * 本 Poller 1s 周期扫孤儿 PENDING 重新触发消费——两条职责、两条节奏，互不干扰。</p>
 *
 * <h3>幂等保护</h3>
 * <p>Poller 触发的 consume 调用已经天然幂等：</p>
 * <ol>
 *     <li>本 Poller 先调用 {@code markPolled(id)} 留下扫描痕迹；</li>
 *     <li>Consumer 内部继续走 {@code markRunning(id)} CAS：PENDING→RUNNING；</li>
 *     <li>若主路径已经把 PENDING 行推进到 RUNNING/SUCCESS/FAILED/TIMEOUT，Poller 的 consume
 *         会在 markRunning 步骤被 CAS 拒绝，自然跳过；</li>
 *     <li>若主路径已经丢失（如 MQ Consumer Bean 未注册 / @Async 线程池积压 / JVM 异常退出），Poller 接管并完成消费。</li>
 * </ol>
 *
 * <h3>配置项</h3>
 * <ul>
 *     <li>{@code helloai.execution.poller-enabled}（默认 true）</li>
 *     <li>{@code helloai.execution.poller-interval-ms}（默认 1000）</li>
 *     <li>{@code helloai.execution.poller-orphan-threshold-seconds}（默认 60；任何模式都用）</li>
 *     <li>{@code helloai.execution.poller-batch-size}（默认 20）</li>
 *     <li>{@code helloai.execution.consumer-mode}（T5 起仅描述主消费路径载体，与 Poller 扫描行为无关）</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "helloai.execution.poller-enabled", havingValue = "true", matchIfMissing = true)
public class ExecutionCommandPoller {

    private final AgentExecutionRecordService agentExecutionRecordService;
    private final ExecutionCommandConsumer executionCommandConsumer;
    private final TaskTimelineService taskTimelineService;
    private final SubTaskService subTaskService;
    private final AgentExecutionProperties executionProperties;

    /**
     * 显式绑定 {@link LocalExecutionCommandConsumer}：Poller 是孤儿 / 超时 / 补偿兜底路径，
     * 必须投递到本地执行链，禁止循环回 MQ。此显式构造器同时解决 dispatch-mode=BOTH 场景下
     * ExecutionCommandConsumer 接口存在两个实现（Local / MQ）导致的 UnsatisfiedDependency。
     */
    public ExecutionCommandPoller(AgentExecutionRecordService agentExecutionRecordService,
                                  LocalExecutionCommandConsumer executionCommandConsumer,
                                  TaskTimelineService taskTimelineService,
                                  SubTaskService subTaskService,
                                  AgentExecutionProperties executionProperties) {
        this.agentExecutionRecordService = agentExecutionRecordService;
        this.executionCommandConsumer = executionCommandConsumer;
        this.taskTimelineService = taskTimelineService;
        this.subTaskService = subTaskService;
        this.executionProperties = executionProperties;
    }

    /**
     * DB Poller 周期扫描入口。
     *
     * <p>{@code fixedDelayString} 直接绑定配置项，支持通过 yaml / 环境变量动态调整扫描周期。</p>
     *
     * <p><b>T5 起行为收口</b>：本 Poller 不再区分 {@code consumer-mode}，统一调用
     * {@code listOrphanPending(threshold, batchSize)} 扫描孤儿 PENDING。
     * 主消费路径由 {@code MqExecutionCommandConsumer}（POLLER/BOTH）或
     * {@code LocalExecutionCommandConsumer.onCommandCreated}（EVENT）承担。</p>
     */
    @Scheduled(fixedDelayString = "${helloai.execution.poller-interval-ms:1000}")
    public void poll() {
        if (!executionProperties.isPollerEnabled()) {
            return;
        }

        int batchSize = executionProperties.getPollerBatchSize();
        int threshold = executionProperties.getPollerOrphanThresholdSeconds();

        // T5 起：所有 consumer-mode 统一扫孤儿 PENDING，不再调用 listAllPending；
        // 主消费路径由 MQ Consumer（POLLER/BOTH）或本地事务事件（EVENT）承担。
        List<AgentExecutionRecord> candidates = agentExecutionRecordService.listOrphanPending(threshold, batchSize);
        String scanType = "listOrphanPending";

        if (candidates.isEmpty()) {
            return;
        }

        log.info("DB Poller 扫描到 {} 条孤儿 PENDING (consumer-mode={}, scan={}, threshold={}s, batchSize={})",
                candidates.size(), executionProperties.getConsumerMode(), scanType, threshold, batchSize);

        for (AgentExecutionRecord record : candidates) {
            try {
                processRecord(record, scanType);
            } catch (Exception e) {
                // 单条记录异常不影响整批扫描
                log.error("DB Poller 处理孤儿 PENDING 异常: recordId={}, eventId={}, subTaskId={}, scan={}",
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
        //    T5 起：scanType 恒为 listOrphanPending，timeline 事件统一使用 sub_task_execution_command_poll_recovery
        String timelineEvent = "sub_task_execution_command_poll_recovery";
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
                            "scan", scanType,
                            "consumerMode", executionProperties.getConsumerMode().name()));
        }

        // 5. 构造 ExecutionCommand，trigger 前缀统一使用 poll-recovery:
        String originalTrigger = record.getTrigger() != null ? record.getTrigger() : "unknown";
        String triggerPrefix = "poll-recovery:";
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