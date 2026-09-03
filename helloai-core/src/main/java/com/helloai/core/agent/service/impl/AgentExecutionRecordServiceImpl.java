package com.helloai.core.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.ExecutionStatus;
import com.helloai.common.util.HostNameUtils;
import com.helloai.core.agent.entity.AgentExecutionRecord;
import com.helloai.core.agent.mapper.AgentExecutionRecordMapper;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        record.setWorkerNode(HostNameUtils.getHostName());
        record.setRetryCount(0);
        save(record);
        return record;
    }

    /**
     * PENDING → RUNNING（CAS：status + @Version 乐观锁双条件）。
     *
     * <p>Phase 0 A2.1：由 lambdaUpdate 链式改为 {@code update(entity, wrapper)} 形式——
     * 链式更新不触发 MyBatis-Plus OptimisticLockerInnerInterceptor（规范 §15），
     * entity 快照带 version 才能启用拦截器的 version 比较与自增，杜绝并发状态覆盖。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markRunning(Long id) {
        AgentExecutionRecord record = getById(id);
        if (record == null) {
            return false;
        }
        record.setStatus(ExecutionStatus.RUNNING);
        record.setStartTime(OffsetDateTime.now());
        return update(record, new LambdaUpdateWrapper<AgentExecutionRecord>()
                .eq(AgentExecutionRecord::getId, id)
                .eq(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING));
    }

    /**
     * RUNNING → SUCCESS（CAS：status + @Version 乐观锁双条件）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markSuccess(Long id) {
        AgentExecutionRecord record = getById(id);
        if (record == null) {
            return false;
        }
        record.setStatus(ExecutionStatus.SUCCESS);
        record.setEndTime(OffsetDateTime.now());
        return update(record, new LambdaUpdateWrapper<AgentExecutionRecord>()
                .eq(AgentExecutionRecord::getId, id)
                .eq(AgentExecutionRecord::getStatus, ExecutionStatus.RUNNING));
    }

    /**
     * RUNNING → FAILED（CAS：status + @Version 乐观锁双条件，errorMsg 截断 500 字符）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markFailed(Long id, String errorMsg) {
        String truncated = errorMsg != null && errorMsg.length() > 500
                ? errorMsg.substring(0, 500)
                : errorMsg;
        AgentExecutionRecord record = getById(id);
        if (record == null) {
            return false;
        }
        record.setStatus(ExecutionStatus.FAILED);
        record.setEndTime(OffsetDateTime.now());
        record.setErrorMsg(truncated);
        return update(record, new LambdaUpdateWrapper<AgentExecutionRecord>()
                .eq(AgentExecutionRecord::getId, id)
                .eq(AgentExecutionRecord::getStatus, ExecutionStatus.RUNNING));
    }

    /**
     * PENDING/RUNNING → TIMEOUT（CAS：status + @Version 乐观锁双条件）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markTimeout(Long id) {
        AgentExecutionRecord record = getById(id);
        if (record == null) {
            return false;
        }
        record.setStatus(ExecutionStatus.TIMEOUT);
        record.setEndTime(OffsetDateTime.now());
        record.setErrorMsg("执行命令超时");
        return update(record, new LambdaUpdateWrapper<AgentExecutionRecord>()
                .eq(AgentExecutionRecord::getId, id)
                .in(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING, ExecutionStatus.RUNNING));
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
}
