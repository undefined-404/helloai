package com.helloai.core.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.entity.Agent;
import com.helloai.core.entity.ReviewRecord;
import com.helloai.core.entity.SubTask;
import com.helloai.core.event.SubTaskAssignedEvent;
import com.helloai.core.mapper.ReviewRecordMapper;
import com.helloai.core.mapper.SubTaskMapper;
import com.helloai.core.service.score.ImplicitScoreCalculator;
import com.helloai.core.service.score.ImplicitScoreCalculator.ScoreResult;
import com.helloai.core.statemachine.SubTaskStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskService extends ServiceImpl<SubTaskMapper, SubTask> {

    private final AgentOutboxService agentOutboxService;
    private final AgentInboxService agentInboxService;
    private final AgentService agentService;
    private final HeartbeatService heartbeatService;
    private final ReviewRecordMapper reviewRecordMapper;
    private final ImplicitScoreCalculator implicitScoreCalculator;
    private final RewardService rewardService;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional(rollbackFor = Exception.class)
    public SubTask create(SubTask subTask, Long assignedAgentId) {
        subTask.setAssignedAgent(null);
        subTask.setStatus(SubTaskStatus.PENDING);
        save(subTask);
        if (assignedAgentId != null) {
            claim(subTask.getId(), assignedAgentId);
        }
        return getById(subTask.getId());
    }

    /**
     * 按子任务主键加行级锁读取。
     *
     * <p>用于命令创建等需要“读现状 + 紧接写入”原子化的路径，
     * 避免在同一子任务上出现并发重复发命令。</p>
     */
    public SubTask getByIdForUpdate(Long subTaskId) {
        return lambdaQuery()
                .eq(SubTask::getId, subTaskId)
                .last("FOR UPDATE")
                .one();
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }

        SubTaskStatus oldStatus = subTask.getStatus();
        SubTaskStateMachine.validate(oldStatus, newStatus);

        subTask.setStatus(newStatus);
        if (newStatus == SubTaskStatus.PENDING && agentId == null) {
            subTask.setAssignedAgent(null);
        } else if (agentId != null) {
            subTask.setAssignedAgent(agentId);
        }

        boolean updated = updateById(subTask);
        if (!updated) {
            throw new BizException("并发修改，请重试");
        }

        agentOutboxService.createEvent(subTask, newStatus);

        // v1.1: 投递收件箱通知
        sendInboxNotification(subTask, newStatus, oldStatus);
        publishAssignmentEvent(subTask, newStatus);
        if (subTask.getAssignedAgent() != null
                && (newStatus == SubTaskStatus.IN_PROGRESS || newStatus == SubTaskStatus.REVIEW)) {
            heartbeatService.active(subTask.getAssignedAgent());
        }

        log.info("子任务状态变更: subTaskId={}, from={}, to={}, agentId={}",
                subTaskId, oldStatus, newStatus, agentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void claim(Long subTaskId, Long agentId) {
        changeStatus(subTaskId, SubTaskStatus.ASSIGNED, agentId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void start(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.IN_PROGRESS, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.REVIEW, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(Long subTaskId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);

        // v1.1 幂等性: 已是 DONE 状态直接返回，避免前端/重试重复调用报 DONE→DONE 错误
        if (subTask.getStatus() == SubTaskStatus.DONE) {
            log.info("complete() 幂等跳过: subTaskId={} 已是 DONE", subTaskId);
            return;
        }

        SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.DONE);
        subTask.setStatus(SubTaskStatus.DONE);
        subTask.setCompletedAt(OffsetDateTime.now());

        // v1.1: 隐式评分集成
        if (subTask.getAssignedAgent() != null) {
            try {
                List<ReviewRecord> reviews = reviewRecordMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ReviewRecord>()
                                .eq(ReviewRecord::getSubTaskId, subTaskId)
                                .orderByAsc(ReviewRecord::getRound));
                int blockCount = 0; // 可扩展: 从 activity_log 统计
                int timeoutCount = subTask.getTimeoutCount() != null ? subTask.getTimeoutCount() : 0;

                ScoreResult scoreResult = implicitScoreCalculator.calculate(subTask, reviews, blockCount, timeoutCount);

                // 写回评分结果
                Map<String, Object> factors = new HashMap<>();
                factors.put("timeScore", scoreResult.getFactors().getTimeScore());
                factors.put("qualityScore", scoreResult.getFactors().getQualityScore());
                factors.put("coopScore", scoreResult.getFactors().getCoopScore());
                factors.put("stabilityScore", scoreResult.getFactors().getStabilityScore());
                factors.put("efficiencyScore", scoreResult.getFactors().getEfficiencyScore());
                subTask.setScoreFactors(factors);
                subTask.setCompositeScore(scoreResult.getCompositeScore());
                subTask.setScoreGrade(scoreResult.getGrade());

                // 隐式积分奖惩
                if (scoreResult.getRewardDelta() != null && scoreResult.getRewardDelta() != 0) {
                    rewardService.addReward(
                            subTask.getAssignedAgent(),
                            "隐式评分(" + scoreResult.getGrade() + "级)",
                            scoreResult.getRewardDelta(),
                            subTaskId);
                }

                log.info("隐式评分完成: subTaskId={}, grade={}, composite={}, delta={}",
                        subTaskId, scoreResult.getGrade(), scoreResult.getCompositeScore(),
                        scoreResult.getRewardDelta());
            } catch (Exception e) {
                log.error("隐式评分计算失败: subTaskId={}", subTaskId, e);
                // 评分失败不阻塞 DONE 状态转换
            }
        }

        updateById(subTask);
        agentOutboxService.createEvent(subTask, SubTaskStatus.DONE);
        log.info("子任务审查通过: subTaskId={}", subTaskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void pause(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.PAUSED, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void resume(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.IN_PROGRESS, null);
    }

    /**
     * 向相关 Agent 投递收件箱通知
     */
    private void sendInboxNotification(SubTask subTask, SubTaskStatus newStatus, SubTaskStatus oldStatus) {
        String eventId = "subtask." + subTask.getId() + "." + System.currentTimeMillis();
        Long agentId = subTask.getAssignedAgent();
        String title = subTask.getTitle();

        try {
            switch (newStatus) {
                case ASSIGNED -> {
                    if (agentId != null) {
                        agentInboxService.send(agentId, eventId, "sub_task.assigned",
                                "新任务已分配: " + title,
                                "交付物: " + (subTask.getDeliverable() != null ? subTask.getDeliverable() : "待确认"),
                                "sub_task", subTask.getId(), "HIGH");
                    }
                }
                case REWORK -> {
                    if (agentId != null) {
                        agentInboxService.send(agentId, eventId, "sub_task.rejected",
                                "任务需返工: " + title,
                                "请查审查记录了解具体问题",
                                "sub_task", subTask.getId(), "HIGH");
                    }
                }
                case BLOCKED -> {
                    agentInboxService.send(agentId != null ? agentId : 0L, eventId, "sub_task.blocked",
                            "任务异常: " + title,
                            "需要 Planner 排障处理",
                            "sub_task", subTask.getId(), "URGENT");
                }
                case PAUSED -> {
                    if (agentId != null) {
                        agentInboxService.send(agentId, eventId, "sub_task.paused",
                                "任务已暂停: " + title,
                                "请保存当前进度，等待恢复通知",
                                "sub_task", subTask.getId(), "HIGH");
                    }
                }
                case REVIEW -> {
                    // v1.1 修复: EXECUTOR 提交后，通知所有 PLANNER/REVIEWER 来审查
                    try {
                        List<Agent> planners = agentService.listByRole(AgentRole.PLANNER);
                        for (Agent planner : planners) {
                            agentInboxService.send(planner.getId(), eventId, "sub_task.review",
                                    "任务已提交审查: " + title,
                                    "请 PLANNER/REVIEWER 评分并完成审查",
                                    "sub_task", subTask.getId(), "HIGH");
                        }
                        log.info("REVIEW 通知发送: subtaskId={}, planners={}", subTask.getId(), planners.size());
                    } catch (Exception e) {
                        log.error("REVIEW 通知发送失败: subtaskId={}", subTask.getId(), e);
                    }
                }
                default -> {}
            }
        } catch (Exception e) {
            log.error("收件箱通知发送失败: subTaskId={}, status={}", subTask.getId(), newStatus, e);
            // 通知失败不影响主流程
        }
    }

    private void publishAssignmentEvent(SubTask subTask, SubTaskStatus newStatus) {
        if (newStatus != SubTaskStatus.ASSIGNED || subTask.getAssignedAgent() == null) {
            return;
        }
        applicationEventPublisher.publishEvent(
                new SubTaskAssignedEvent(subTask.getId(), subTask.getAssignedAgent()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void rework(Long subTaskId, Long reworkAgentId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);
        SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.REWORK);
        subTask.setStatus(SubTaskStatus.REWORK);
        subTask.setReworkCount(subTask.getReworkCount() != null ? subTask.getReworkCount() + 1 : 1);
        if (reworkAgentId != null) {
            subTask.setAssignedAgent(reworkAgentId);
        }
        updateById(subTask);
        agentOutboxService.createEvent(subTask, SubTaskStatus.REWORK);
        log.info("子任务驳回返工: subTaskId={}, reworkCount={}", subTaskId, subTask.getReworkCount());
    }

    @Transactional(rollbackFor = Exception.class)
    public void block(Long subTaskId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);
        if (subTask.getStatus() != SubTaskStatus.IN_PROGRESS
                && subTask.getStatus() != SubTaskStatus.ASSIGNED
                && subTask.getStatus() != SubTaskStatus.REWORK) {
            throw new BizException("只能对 IN_PROGRESS/ASSIGNED/REWORK 状态的子任务标记 BLOCKED");
        }
        changeStatus(subTaskId, SubTaskStatus.BLOCKED, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long subTaskId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);
        if (subTask.getStatus() == SubTaskStatus.DONE || subTask.getStatus() == SubTaskStatus.CANCELLED) {
            throw new BizException("已完成或已取消的子任务不能再次取消");
        }
        changeStatus(subTaskId, SubTaskStatus.CANCELLED, null);
    }

    /**
     * 将 PENDING 子任务分配给指定 Agent（v2.4 §4.5 熔断调度入口）。
     *
     * <p><b>⚠️ 调用约束：本方法只能由 {@link ResilientDispatcher} 调用！</b>
     * 业务方必须走 {@code resilientDispatcher.assignNext(agentId, subTaskId)}，
     * 直接调用本方法将<b>绕过熔断保护</b>，导致不可用 Agent 仍被分配任务。</p>
     *
     * <p>只允许 PENDING 状态，避免抢任务冲突。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignNext(Long agentId, Long subTaskId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);
        if (subTask.getStatus() != SubTaskStatus.PENDING) {
            throw new BizException("只有 PENDING 状态的子任务才能分配，当前状态: " + subTask.getStatus());
        }
        changeStatus(subTaskId, SubTaskStatus.ASSIGNED, agentId);
    }

    /**
     * 将子任务重置为待重新调度的 PENDING 状态。
     *
     * <p>该方法用于离线补偿、阻塞重分配等“需要重新走弹性调度器”的系统路径。
     * 会清空当前 assignedAgent，让后续 {@link ResilientDispatcher#assignNext(Long, Long)}
     * 重新发布标准 ASSIGNED 事件与自动执行链。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public SubTask resetToPendingForDispatch(Long subTaskId, Set<SubTaskStatus> allowedStatuses) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }
        if (!allowedStatuses.contains(subTask.getStatus())) {
            throw new BizException("子任务状态不允许重新调度: subTaskId=" + subTaskId + ", status=" + subTask.getStatus());
        }

        subTask.setStatus(SubTaskStatus.PENDING);
        subTask.setAssignedAgent(null);
        boolean updated = updateById(subTask);
        if (!updated) {
            throw new BizException("并发修改，请重试");
        }
        return getById(subTaskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reassign(Long subTaskId, Long newAgentId) {
        resetToPendingForDispatch(subTaskId, Set.of(SubTaskStatus.BLOCKED));
        changeStatus(subTaskId, SubTaskStatus.ASSIGNED, newAgentId);
    }
}
