package com.helloai.core.agent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.agent.mapper.AgentExecutionRecordMapper;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Agent 执行记录服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionRecordServiceImpl extends ServiceImpl<AgentExecutionRecordMapper, AgentExecutionRecord>
        implements AgentExecutionRecordService {

    /**
     * 创建 PENDING 执行记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentExecutionRecord createPending(String eventId, Long subTaskId,
                                              Long agentId, AgentAccessType accessType, String trigger) {
        AgentExecutionRecord record = new AgentExecutionRecord();
        record.setEventId(eventId);
        record.setSubTaskId(subTaskId);
        record.setAgentId(agentId);
        record.setAccessType(accessType);
        record.setTriggerType(trigger);
        record.setStatus(ExecutionStatus.PENDING);
        record.setWorkerNode(getHostName());
        record.setRetryCount(0);
        save(record);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markRunning(Long id) {
        return lambdaUpdate()
                .eq(AgentExecutionRecord::getId, id)
                .eq(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING)
                .set(AgentExecutionRecord::getStatus, ExecutionStatus.RUNNING)
                .set(AgentExecutionRecord::getStartTime, OffsetDateTime.now())
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markSuccess(Long id) {
        return lambdaUpdate()
                .eq(AgentExecutionRecord::getId, id)
                .eq(AgentExecutionRecord::getStatus, ExecutionStatus.RUNNING)
                .set(AgentExecutionRecord::getStatus, ExecutionStatus.SUCCESS)
                .set(AgentExecutionRecord::getEndTime, OffsetDateTime.now())
                .update();
    }

    @Override
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

    @Override
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

    @Override
    public boolean hasPendingOrRunning(Long subTaskId) {
        return lambdaQuery()
                .eq(AgentExecutionRecord::getSubTaskId, subTaskId)
                .in(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING, ExecutionStatus.RUNNING)
                .count() > 0;
    }

    /**
     * DB Poller 扫描：查找「长时间未被消费的 PENDING」记录。
     */
    @Override
    public List<AgentExecutionRecord> listOrphanPending(int thresholdSeconds, int limit) {
        if (thresholdSeconds < 0 || limit <= 0) {
            return List.of();
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(thresholdSeconds);
        return lambdaQuery()
                .eq(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING)
                .and(w -> w.isNull(AgentExecutionRecord::getLastAttemptTime)
                        .or().lt(AgentExecutionRecord::getLastAttemptTime, cutoff))
                .orderByAsc(AgentExecutionRecord::getCreateTime)
                .last("LIMIT " + limit)
                .list();
    }

    /**
     * 旧 DB Poller 主消费扫描：查找「所有未被消费的 PENDING」记录（不限于孤儿）。
     */
    @Override
    @Deprecated
    public List<AgentExecutionRecord> listAllPending(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return lambdaQuery()
                .eq(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING)
                .orderByAsc(AgentExecutionRecord::getCreateTime)
                .last("LIMIT " + limit)
                .list();
    }

    /**
     * DB Poller 触及痕迹：更新 {@code last_attempt_at} 为当前时间。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markPolled(Long id) {
        return lambdaUpdate()
                .eq(AgentExecutionRecord::getId, id)
                .set(AgentExecutionRecord::getLastAttemptTime, OffsetDateTime.now())
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
