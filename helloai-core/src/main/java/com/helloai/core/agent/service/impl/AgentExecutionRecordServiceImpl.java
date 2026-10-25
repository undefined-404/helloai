package com.helloai.core.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
     * <p>由 lambdaUpdate 链式改为 {@code update(entity, wrapper)} 形式——
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

    /**
     * 「平台内在飞记录」的唯一匹配器（{@link #hasPendingOrRunning} 与 {@link #closeInFlight} 共用）。
     *
     * <p><b>必须共用一处</b>：这两件事是同一判定的正反两面——「判得出有没有」却「清不掉」，
     * 就会出现「改派被永久挡住」的僵尸态（2026-10-25 实测现场）。各写一份条件必然漂移。</p>
     */
    private LambdaQueryWrapper<AgentExecutionRecord> inFlightFilter(Long subTaskId) {
        return new LambdaQueryWrapper<AgentExecutionRecord>()
                .eq(AgentExecutionRecord::getSubTaskId, subTaskId)
                .in(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING, ExecutionStatus.RUNNING)
                .and(w -> w.isNull(AgentExecutionRecord::getAccessType)
                        .or().eq(AgentExecutionRecord::getAccessType, AgentAccessType.API_KEY_LLM));
    }

    /**
     * 子任务是否存在<b>平台内</b>的 PENDING/RUNNING 记录（防重复发命令）。
     *
     * <p><b>G-006 口径收窄</b>：本方法的原始语义是「这条子任务是否已有一条<b>待消费/执行中的执行命令</b>」，
     * 用来挡住 {@code ExecutionCommandService} / 管理端 {@code execute} 的重复派发。
     * 外部路径的 attempt 行由认领动作顺带落库（{@code McpTaskClaimServiceImpl}），
     * <b>它不对应任何平台命令</b>——若把它算进来，外部 Agent 领了任务就再也无法被
     * 「阈值回退 / 人工改派」重新派给平台 LLM，也会让暂停后的管理端重跑被误挡。
     * 故只统计 {@code access_type} 为空（历史行）或 {@code API_KEY_LLM} 的记录。</p>
     */
    @Override
    public boolean hasPendingOrRunning(Long subTaskId) {
        return count(inFlightFilter(subTaskId)) > 0;
    }

    /**
     * 收口子任务在飞的平台执行记录（PENDING/RUNNING → FAILED）。
     *
     * <p>逐条带 status CAS（{@code in(PENDING, RUNNING)}）更新，与 {@code markFailed} 同范式：
     * 收口过程中若某条已被他路推进到终态，CAS 失败即跳过并计数差，不覆盖别人的终态。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int closeInFlight(Long subTaskId, String reason) {
        if (subTaskId == null) {
            return 0;
        }
        List<AgentExecutionRecord> inFlight = list(inFlightFilter(subTaskId));
        if (inFlight.isEmpty()) {
            return 0;
        }
        String truncated = reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason;
        OffsetDateTime now = OffsetDateTime.now();
        int closed = 0;
        for (AgentExecutionRecord record : inFlight) {
            AgentExecutionRecord patch = new AgentExecutionRecord();
            patch.setId(record.getId());
            patch.setStatus(ExecutionStatus.FAILED);
            patch.setEndTime(now);
            patch.setErrorMsg(truncated);
            if (update(patch, new LambdaUpdateWrapper<AgentExecutionRecord>()
                    .eq(AgentExecutionRecord::getId, record.getId())
                    .in(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING, ExecutionStatus.RUNNING))) {
                closed++;
            }
        }
        if (closed < inFlight.size()) {
            log.warn("在飞执行记录收口部分跳过（已被他路推进，不覆盖其终态）: subTaskId={}, 目标={}, 实际={}",
                    subTaskId, inFlight.size(), closed);
        }
        log.info("收口在飞执行记录: subTaskId={}, closed={}, reason={}", subTaskId, closed, truncated);
        return closed;
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

    // ══════════════════════════════════════════════════════════════
    //  G-006 外部执行轨迹对齐：外部 attempt 生命周期
    // ══════════════════════════════════════════════════════════════

    /**
     * 开启外部执行 attempt（RUNNING，幂等）。见接口 javadoc 的语义说明。
     *
     * <p>并发下的「重复开行」由调用侧顺序兜住：认领/开工均为单次调用路径，
     * 且本方法先查 open 记录再插入；即便极端并发插入两行，收口时也会一并关闭
     * （{@link #closeExternalAttempt} 关闭<b>全部</b> open 行），不会留下永久 RUNNING 行。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentExecutionRecord openExternalAttempt(Long subTaskId, Long agentId,
                                                    AgentAccessType accessType, String trigger, String workerNode) {
        if (subTaskId == null) {
            return null;
        }
        // 平台内执行（API_KEY_LLM）由派发链 createPending/markRunning 建行；此处不越界
        if (accessType == AgentAccessType.API_KEY_LLM) {
            return null;
        }
        AgentExecutionRecord open = findOpenAttempt(subTaskId);
        if (open != null) {
            return open;
        }
        AgentExecutionRecord record = new AgentExecutionRecord();
        // eventId 语义：外部路径无 MQ event，用「子任务 + 时间戳」保证同子任务多轮 attempt 互异
        record.setEventId("ext-" + subTaskId + "-" + System.currentTimeMillis());
        record.setSubTaskId(subTaskId);
        record.setAgentId(agentId);
        record.setAccessType(accessType);
        record.setTriggerType(trigger);
        record.setStatus(ExecutionStatus.RUNNING);
        record.setStartTime(OffsetDateTime.now());
        record.setRetryCount(0);
        // worker_node 为 varchar(64)：外部执行者名来自 Agent 名（用户可改），先截断再落库，
        // 否则超长名称会让整条审计写入失败
        String node = (workerNode != null && !workerNode.isBlank())
                ? workerNode : HostNameUtils.getHostName();
        record.setWorkerNode(node.length() > 64 ? node.substring(0, 64) : node);
        save(record);
        log.debug("外部执行 attempt 开启: subTaskId={}, agentId={}, accessType={}, trigger={}",
                subTaskId, agentId, accessType, trigger);
        return record;
    }

    /**
     * 收口外部路径 open attempt。逐条走既有 CAS（{@code markSuccess/markFailed/markTimeout}），
     * 因此与补偿任务/派发链并发时不会双写终态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int closeExternalAttempt(Long subTaskId, ExecutionStatus terminal, String errorMsg, Integer tokenUsage) {
        if (subTaskId == null || terminal == null
                || terminal == ExecutionStatus.PENDING || terminal == ExecutionStatus.RUNNING) {
            return 0;
        }
        List<AgentExecutionRecord> open = lambdaQuery()
                .eq(AgentExecutionRecord::getSubTaskId, subTaskId)
                .in(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING, ExecutionStatus.RUNNING)
                // 只收口外部路径：平台内记录由派发链/补偿任务自己收尾（access_type 历史行可能为 NULL）
                .and(w -> w.ne(AgentExecutionRecord::getAccessType, AgentAccessType.API_KEY_LLM)
                        .or().isNull(AgentExecutionRecord::getAccessType))
                .list();
        int closed = 0;
        for (AgentExecutionRecord record : open) {
            boolean ok = switch (terminal) {
                case SUCCESS -> markSuccess(record.getId(), tokenUsage);
                case FAILED -> markFailed(record.getId(),
                        errorMsg != null && !errorMsg.isBlank() ? errorMsg : "external_attempt_failed", tokenUsage);
                case TIMEOUT -> markTimeout(record.getId());
                default -> false;
            };
            if (ok) {
                closed++;
            }
        }
        if (closed > 0) {
            log.debug("外部执行 attempt 收口: subTaskId={}, terminal={}, closed={}", subTaskId, terminal, closed);
        }
        return closed;
    }

    /** 查该子任务当前的 open attempt（PENDING/RUNNING，任一路径）。 */
    private AgentExecutionRecord findOpenAttempt(Long subTaskId) {
        return lambdaQuery()
                .eq(AgentExecutionRecord::getSubTaskId, subTaskId)
                .in(AgentExecutionRecord::getStatus, ExecutionStatus.PENDING, ExecutionStatus.RUNNING)
                .orderByDesc(AgentExecutionRecord::getId)
                .last("LIMIT 1")
                .one();
    }
}
