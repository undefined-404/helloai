package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.entity.AgentExecutionRecord;
import com.helloai.core.mapper.AgentExecutionRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionRecordService extends ServiceImpl<AgentExecutionRecordMapper, AgentExecutionRecord> {

    /**
     * 创建 PENDING 执行记录。
     *
     * <p>冗余存储 trigger / agentId / accessType 到记录行本身，便于后续 DB Poller
     * 在不查 Agent 表的情况下恢复 ExecutionCommand。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public AgentExecutionRecord createPending(String eventId, Long subTaskId,
                                              Long agentId, AgentAccessType accessType, String trigger) {
        AgentExecutionRecord record = new AgentExecutionRecord();
        record.setEventId(eventId);
        record.setSubTaskId(subTaskId);
        record.setAgentId(agentId);
        record.setAccessType(accessType);
        record.setTrigger(trigger);
        record.setStatus(ExecutionStatus.PENDING);
        record.setWorkerNode(getHostName());
        record.setRetryCount(0);
        save(record);
        return record;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markRunning(Long id) {
        return lambdaUpdate()
                .eq(AgentExecutionRecord::getId, id)
                .eq(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING)
                .set(AgentExecutionRecord::getStatus, ExecutionStatus.RUNNING)
                .set(AgentExecutionRecord::getStartTime, OffsetDateTime.now())
                .update();
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markSuccess(Long id) {
        return lambdaUpdate()
                .eq(AgentExecutionRecord::getId, id)
                .eq(AgentExecutionRecord::getStatus, ExecutionStatus.RUNNING)
                .set(AgentExecutionRecord::getStatus, ExecutionStatus.SUCCESS)
                .set(AgentExecutionRecord::getEndTime, OffsetDateTime.now())
                .update();
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markFailed(Long id, String errorMsg) {
        String truncated = errorMsg != null && errorMsg.length() > 500
                ? errorMsg.substring(0, 500)
                : errorMsg;
        return lambdaUpdate()
                .eq(AgentExecutionRecord::getId, id)
                .eq(AgentExecutionRecord::getStatus, ExecutionStatus.RUNNING)
                .set(AgentExecutionRecord::getStatus, ExecutionStatus.FAILED)
                .set(AgentExecutionRecord::getEndTime, OffsetDateTime.now())
                .set(AgentExecutionRecord::getErrorMsg, truncated)
                .update();
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean markTimeout(Long id) {
        return lambdaUpdate()
                .eq(AgentExecutionRecord::getId, id)
                .in(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING, ExecutionStatus.RUNNING)
                .set(AgentExecutionRecord::getStatus, ExecutionStatus.TIMEOUT)
                .set(AgentExecutionRecord::getEndTime, OffsetDateTime.now())
                .set(AgentExecutionRecord::getErrorMsg, "执行命令超时")
                .update();
    }

    public boolean hasPendingOrRunning(Long subTaskId) {
        return lambdaQuery()
                .eq(AgentExecutionRecord::getSubTaskId, subTaskId)
                .in(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING, ExecutionStatus.RUNNING)
                .count() > 0;
    }

    /**
     * DB Poller 扫描：查找「长时间未被消费的 PENDING」记录。
     *
     * <p>扫描条件：{@code status='PENDING' AND (last_attempt_at IS NULL OR last_attempt_at < now - thresholdSeconds)}。</p>
     * <p>按 {@code create_time} 升序遍历：先扫最早创建的 PENDING 行，优先避免堆积。</p>
     *
     * @param thresholdSeconds 兜底阈值（秒）：超过该时间未被 Poller 触及的 PENDING 行视为孤儿
     * @param limit             单批扫描上限，避免扫到大量孤儿记录时阻塞调度线程
     * @return 孤儿 PENDING 记录列表
     */
    public List<AgentExecutionRecord> listOrphanPending(int thresholdSeconds, int limit) {
        if (thresholdSeconds < 0 || limit <= 0) {
            return List.of();
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(thresholdSeconds);
        return lambdaQuery()
                .eq(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING)
                .and(w -> w.isNull(AgentExecutionRecord::getLastAttemptAt)
                        .or().lt(AgentExecutionRecord::getLastAttemptAt, cutoff))
                .orderByAsc(AgentExecutionRecord::getCreateTime)
                .last("LIMIT " + limit)
                .list();
    }

    /**
     * DB Poller 触及痕迹：更新 {@code last_attempt_at} 为当前时间。
     *
     * <p>本方法不限制 status：</p>
     * <ul>
     *     <li>通常 Poller 只会扫到 PENDING 行，markPolled 后 CAS markRunning 会推进到 RUNNING；</li>
     *     <li>极端并发下，可能在 markPolled 写入之前 record 已被主路径推进，本方法允许状态已经变化，
     *         仅作为"我们曾尝试过"的痕迹记录。</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markPolled(Long id) {
        return lambdaUpdate()
                .eq(AgentExecutionRecord::getId, id)
                .set(AgentExecutionRecord::getLastAttemptAt, OffsetDateTime.now())
                .update();
    }

    private String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
