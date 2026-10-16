package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.common.util.HostNameUtils;
import com.helloai.core.agent.session.service.AgentSessionService;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.port.SubTaskView;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子任务<b>租约 / 回收 / 巡检</b>职责类（RM9-b 切分线 ②「副作用簇」）：
 * 调度侧只读候选 + 租约续期 + 过期回收 + 外部回退计数。
 *
 * <p><b>为什么单独成类</b>：这一簇由<b>调度侧</b>（{@code helloai-job} 的看门狗 / 回收 / 巡检定时任务）
 * 独占消费，生命周期与请求侧的状态机完全不同（周期触发、批量、CAS 竞争、best-effort），
 * 但原类把它与请求侧状态迁就混在同一份文件中。</p>
 *
 * <p><b>状态迁移唯一入口未被稀释（§4.3.2 验收口径 ii）</b>：本类只做三件非状态写入的事 ——
 * ① 续期只写 {@code lease_until}；② 回退只累加 {@code external_fallback_count}；
 * ③ 挑候选/巡检只读。唯一涉及状态写入的 {@code reclaimExpiredLeases} 已把
 * 「校验 + 置 PENDING + 清 owner/lease + 落库」下沉到
 * {@link SubTaskStateMachineService#reclaimToPending(SubTask)}（RM9-b 微调④），
 * 本类只保留「挑候选 + 回收副作用编排」。</p>
 *
 * <p><b>CAS / best-effort 纪律（原样保留）</b>：续期与回收都可能与对方并发竞争，
 * {@code updateById} 返回 false 视为「对方赢」，跳过即可；timeline 与 session 中断点
 * 均为 best-effort，写失败仅 warn、绝不阻断回收主链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskLeaseService {

    private final SubTaskMapper subTaskMapper;
    private final TaskTimelineService taskTimelineService;
    /** 执行会话服务（租约回收时记录中断点 + ABORT，N-007 恢复载体）。 */
    private final AgentSessionService agentSessionService;
    /** 回收半段的状态写入唯一入口（不反向依赖查询类，方向：租约 → 状态机）。 */
    private final SubTaskStateMachineService stateMachineService;

    // ══════════════════════════════════════════════════════════════
    //  巡检只读候选（§7.1 helloai-job 去 Mapper 直连收口，SQL 口径不变）
    // ══════════════════════════════════════════════════════════════

    /**
     * 返工达上限卡死的 REVIEW 孤儿扫描（§6.52）：候选已人工介入的不返回，
     * 让「返工上限熔断后晾在 REVIEW」的任务能被兜底发现。
     */
    public List<SubTask> listReviewOrphans(int thresholdSeconds, int limit) {
        OffsetDateTime threshold = OffsetDateTime.now().minusSeconds(thresholdSeconds);
        List<SubTask> candidates = subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getStatus, SubTaskStatus.REVIEW)
                .le(SubTask::getUpdateTime, threshold)
                .orderByAsc(SubTask::getUpdateTime)
                .last("LIMIT " + limit));
        candidates.removeIf(st -> {
            Map<String, Object> ctx = st.getContext();
            return ctx != null && ctx.get("manualIntervention") != null;
        });
        return candidates;
    }

    public List<SubTaskView> listReviewOrphanViews(int thresholdSeconds, int limit) {
        return listReviewOrphans(thresholdSeconds, limit).stream()
                .map(SubTaskProjectionMapper::toView).toList();
    }

    public List<SubTask> listPausedBefore(Long agentId, OffsetDateTime expiredBefore) {
        return subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getAssignedAgentId, agentId)
                .eq(SubTask::getStatus, SubTaskStatus.PAUSED)
                .le(SubTask::getUpdateTime, expiredBefore));
    }

    public List<SubTask> listTimedOutAssigned(OffsetDateTime deadline, int limit) {
        return subTaskMapper.selectTimedOutAssigned(deadline, limit);
    }

    // ══════════════════════════════════════════════════════════════
    //  租约续期 / 过期回收 / 外部回退计数
    // ══════════════════════════════════════════════════════════════

    /**
     * 看门狗续期：本机 owner 名下 IN_PROGRESS 且租约最早到期的 {@code limit} 条，统一推到
     * {@code newLeaseUntil}。只写 {@code lease_until}，<b>不触碰 status</b>。
     */
    @Transactional(rollbackFor = Exception.class)
    public int renewCurrentNodeLeases(OffsetDateTime newLeaseUntil, int limit) {
        String owner = HostNameUtils.getHostName();
        List<SubTask> owned = subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getOwner, owner)
                .eq(SubTask::getStatus, SubTaskStatus.IN_PROGRESS)
                .orderByAsc(SubTask::getLeaseUntil)
                .last("LIMIT " + limit));
        int renewed = 0;
        for (SubTask subTask : owned) {
            subTask.setLeaseUntil(newLeaseUntil);
            if (subTaskMapper.updateById(subTask) > 0) {
                renewed++;
            } else {
                // 与 Reconciler 并发竞争失败（状态离开 IN_PROGRESS / version 变更），赢者生效，跳过即可
                log.warn("租约续期 CAS 冲突，跳过: subTaskId={}", subTask.getId());
            }
        }
        if (renewed > 0) {
            log.info("看门狗续期完成: owner={}, 续期条数={}", owner, renewed);
        }
        return renewed;
    }

    /**
     * 过期租约回收：IN_PROGRESS 且有 owner 且租约已过期 → 回 PENDING 重派。
     * 存量数据 owner 为 NULL 的不受本机制管理，继续走既有超时巡检路径。
     *
     * <p>状态写入经 {@link SubTaskStateMachineService#reclaimToPending(SubTask)}；
     * 本方法负责「挑候选 → 逐条回收 → 追加审计与恢复载体事件」。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public int reclaimExpiredLeases(int limit) {
        OffsetDateTime now = OffsetDateTime.now();
        // 存量数据 owner 为 NULL：不受本机制管理，继续走既有超时巡检路径
        List<SubTask> expired = subTaskMapper.selectList(new LambdaQueryWrapper<SubTask>()
                .eq(SubTask::getStatus, SubTaskStatus.IN_PROGRESS)
                .isNotNull(SubTask::getOwner)
                .lt(SubTask::getLeaseUntil, now)
                .orderByAsc(SubTask::getLeaseUntil)
                .last("LIMIT " + limit));
        int reclaimed = 0;
        for (SubTask subTask : expired) {
            Long subTaskId = subTask.getId();
            try {
                // 先捕获审计字段，再交给状态机改写实体（与原「validate → 捕获 → 改写」时序一致）
                String oldOwner = subTask.getOwner();
                Long taskId = subTask.getTaskId();
                if (!stateMachineService.reclaimToPending(subTask)) {
                    // 与 Watchdog 续期并发竞争失败（version 已变更），赢者生效，跳过即可
                    log.warn("租约回收 CAS 冲突，跳过: subTaskId={}", subTaskId);
                    continue;
                }
                reclaimed++;
                try {
                    // 回收事件仅审计用途，写入失败不阻断回收主链路
                    taskTimelineService.recordEvent(taskId, subTaskId,
                            TimelineEventCode.SUB_TASK_LEASE_RECLAIMED.code(), AgentRole.SYSTEM, null,
                            Map.of("owner", oldOwner, "reason", "lease_expired"));
                } catch (Exception e) {
                    log.warn("租约回收 timeline 事件写入失败，不影响回收: subTaskId={}", subTaskId, e);
                }
                // （N-007 恢复载体）：识别未完成执行 → 记录中断点
                // （turn/step/agentId/恢复上下文快照）→ ABORT 幂等防重入；best-effort 不阻断回收
                try {
                    AgentSessionService.InterruptedSession interrupted =
                            agentSessionService.interrupt(subTaskId);
                    if (interrupted != null) {
                        Map<String, Object> interruptPayload = new HashMap<>();
                        interruptPayload.put("sessionId", interrupted.sessionId());
                        interruptPayload.put("turn", interrupted.turn());
                        interruptPayload.put("step", interrupted.step());
                        interruptPayload.put("contextSummary",
                                interrupted.snapshot() != null ? interrupted.snapshot() : Map.of());
                        taskTimelineService.recordEvent(taskId, subTaskId,
                                TimelineEventCode.SUB_TASK_SESSION_INTERRUPTED.code(), AgentRole.SYSTEM,
                                interrupted.agentId(), interruptPayload);
                    }
                } catch (Exception e) {
                    log.warn("执行会话中断点记录失败（best-effort，不影响回收）: subTaskId={}", subTaskId, e);
                }
            } catch (Exception e) {
                log.warn("租约回收单条处理异常，跳过: subTaskId={}", subTaskId, e);
            }
        }
        if (reclaimed > 0) {
            log.info("租约过期回收完成: 回收条数={}", reclaimed);
        }
        return reclaimed;
    }

    /** 原子累加 {@code external_fallback_count}（N11 阈值回退口径；不触碰 status）。 */
    public int incrementExternalFallbackCount(Long subTaskId, OffsetDateTime now) {
        return subTaskMapper.incrementExternalFallbackCount(subTaskId, now);
    }
}
