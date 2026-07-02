package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionRecordService extends ServiceImpl<AgentExecutionRecordMapper, AgentExecutionRecord> {

    @Transactional(rollbackFor = Exception.class)
    public AgentExecutionRecord createPending(String eventId, Long subTaskId) {
        AgentExecutionRecord record = new AgentExecutionRecord();
        record.setEventId(eventId);
        record.setSubTaskId(subTaskId);
        record.setStatus(ExecutionStatus.PENDING);
        record.setWorkerNode(getHostName());
        record.setRetryCount(0);
        save(record);
        return record;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRunning(Long id) {
        lambdaUpdate()
                .eq(AgentExecutionRecord::getId, id)
                .set(AgentExecutionRecord::getStatus, ExecutionStatus.RUNNING)
                .set(AgentExecutionRecord::getStartTime, OffsetDateTime.now())
                .update();
    }

    @Transactional(rollbackFor = Exception.class)
    public void markSuccess(Long id) {
        lambdaUpdate()
                .eq(AgentExecutionRecord::getId, id)
                .set(AgentExecutionRecord::getStatus, ExecutionStatus.SUCCESS)
                .set(AgentExecutionRecord::getEndTime, OffsetDateTime.now())
                .update();
    }

    @Transactional(rollbackFor = Exception.class)
    public void markFailed(Long id, String errorMsg) {
        String truncated = errorMsg != null && errorMsg.length() > 500
                ? errorMsg.substring(0, 500)
                : errorMsg;
        lambdaUpdate()
                .eq(AgentExecutionRecord::getId, id)
                .set(AgentExecutionRecord::getStatus, ExecutionStatus.FAILED)
                .set(AgentExecutionRecord::getEndTime, OffsetDateTime.now())
                .set(AgentExecutionRecord::getErrorMsg, truncated)
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
