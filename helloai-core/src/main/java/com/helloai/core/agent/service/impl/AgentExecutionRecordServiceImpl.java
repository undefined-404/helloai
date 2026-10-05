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
        return markSuccess(id, null);
    }

    /**
     * RUNNING → SUCCESS（CAS：status + @Version 乐观锁双条件；落 Token 用量）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markSuccess(Long id, Integer tokenUsage) {
        AgentExecutionRecord record = getById(id);
        if (record == null) {
            return false;
        }
        record.setStatus(ExecutionStatus.SUCCESS);
        record.setEndTime(OffsetDateTime.now());
        record.setTokenUsage(tokenUsage);
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
        return markFailed(id, errorMsg, null);
    }

    /**
     * RUNNING → FAILED（CAS：status + @Version 乐观锁双条件，errorMsg 截断 500 字符；落 Token 用量）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean markFailed(Long id, String errorMsg, Integer tokenUsage) {
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
        record.setTokenUsage(tokenUsage);
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
     *
     * <p><b>2026-10-05（804 双消费假阻塞）</b>：新增 {@code create_time < cutoff} 条件——
     * 此前 {@code last_attempt_time IS NULL} 分支会把「<b>刚创建、MQ 主路径即将消费</b>」的
     * PENDING 也纳入兜底，导致 Poller 与 MQ 同时消费同一 {@code recordId}（同一命令双消费）。
     * 现要求记录创建时间早于阈值，只兜底<b>长期滞留</b>的记录，不再抢占新鲜 PENDING。</p>
     */
    @Override
    public List<AgentExecutionRecord> listOrphanPending(int thresholdSeconds, int limit) {
        if (thresholdSeconds < 0 || limit <= 0) {
            return List.of();
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(thresholdSeconds);
        return lambdaQuery()
                .eq(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING)
                // 只兜底「长期滞留」：创建时间须早于同一阈值，避免抢占刚创建、主路径即将消费的 PENDING
                .lt(AgentExecutionRecord::getCreateTime, cutoff)
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

    // ══════════════════════════════════════════════════════════════
    //  §7.1 helloai-job 去 Mapper 直连收口（同域薄委托，SQL 口径不变）
    // ══════════════════════════════════════════════════════════════

    @Override
    public List<AgentExecutionRecord> listByStatusCreatedBefore(ExecutionStatus status, OffsetDateTime before) {
        return baseMapper.selectByStatusAndCreateTimeBefore(status, before);
    }

    @Override
    public List<AgentExecutionRecord> listByStatusStartedBefore(ExecutionStatus status, OffsetDateTime before) {
        return baseMapper.selectByStatusAndStartTimeBefore(status, before);
    }

    // ══════════════════════════════════════════════════════════════
    //  B5.2 Fleet 成本选人：成本画像聚合
    // ══════════════════════════════════════════════════════════════

    /**
     * 最近 N 次成功执行的 token 均值（best-effort：任何异常都降级为「无成本数据」）。
     *
     * <p>无样本 / SQL 返回 NULL / 查询异常 一律返回 {@code null}，语义统一为
     * 「该 Agent 无成本画像」——由选人侧 {@code AgentSelector.resolveCostRanks}
     * 记 0 档（不参与成本维度比较），与质量画像缺失口径保持一致。</p>
     */
    @Override
    public Integer averageRecentSuccessTokens(Long agentId, int limit) {
        if (agentId == null) {
            return null;
        }
        int sampleLimit = limit > 0 ? limit : 1;
        try {
            Double avg = baseMapper.selectRecentAvgTokenUsage(agentId, sampleLimit);
            if (avg == null) {
                return null;
            }
            return (int) Math.round(avg);
        } catch (Exception e) {
            // 防御式：成本画像查询异常不得阻断选人（等同于无成本数据）
            log.debug("成本画像查询异常（按无成本数据处理）: agentId={}, err={}", agentId, e.getMessage());
            return null;
        }
    }
}
