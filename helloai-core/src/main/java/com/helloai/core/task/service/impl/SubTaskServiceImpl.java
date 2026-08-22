package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.AgentUnavailableException;
import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.HeartbeatService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.ConcurrencyQuotaService;
import com.helloai.core.shared.event.SubTaskAssignedEvent;
import com.helloai.core.shared.event.SubTaskCompletedEvent;
import com.helloai.core.task.entity.ReviewRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.score.ImplicitScoreCalculator;
import com.helloai.core.task.score.ImplicitScoreCalculator.ScoreResult;
import com.helloai.core.task.service.RewardService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import com.helloai.core.task.statemachine.SubTaskStateMachine;
import com.helloai.core.task.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 子任务领域服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubTaskServiceImpl extends ServiceImpl<SubTaskMapper, SubTask>
        implements SubTaskService {

    private final AgentOutboxService agentOutboxService;
    private final AgentInboxService agentInboxService;
    // 懒解析打破循环：AgentServiceImpl 依赖 SubTaskService（阶段五级联清理收口），
    // 直接注入 AgentService 会形成 agentServiceImpl ↔ subTaskServiceImpl 构造器环
    private final ObjectProvider<AgentService> agentServiceProvider;
    private final HeartbeatService heartbeatService;
    private final ReviewRecordMapper reviewRecordMapper;
    private final ImplicitScoreCalculator implicitScoreCalculator;
    private final RewardService rewardService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final TaskTimelineService taskTimelineService;
    private final AgentDispatchProperties agentDispatchProperties;
    private final ConcurrencyQuotaService concurrencyQuotaService;
    // 懒解析打破循环：AttachmentServiceImpl 依赖 SubTaskService（register 归属校验）
    private final ObjectProvider<AttachmentService> attachmentServiceProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SubTask create(SubTask subTask, Long assignedAgentId) {
        subTask.setAssignedAgentId(null);
        subTask.setStatus(SubTaskStatus.PENDING);
        save(subTask);
        if (assignedAgentId != null) {
            claim(subTask.getId(), assignedAgentId);
        }
        return getById(subTask.getId());
    }

    @Override
    public IPage<SubTask> list(Long taskId, SubTaskStatus status, Long assignedAgentId, Integer page, int pageSize) {
        LambdaQueryWrapper<SubTask> wrapper = new LambdaQueryWrapper<SubTask>()
                .eq(taskId != null, SubTask::getTaskId, taskId)
                .eq(status != null, SubTask::getStatus, status)
                .eq(assignedAgentId != null, SubTask::getAssignedAgentId, assignedAgentId)
                .orderByDesc(SubTask::getCreateTime);

        if (page == null || page <= 0) {
            // 不分页：全量列表包装成 IPage，保持返回类型统一
            List<SubTask> all = list(wrapper);
            if (all == null) {
                all = Collections.emptyList();
            }
            Page<SubTask> full = new Page<>(1, Math.max(all.size(), 1));
            full.setRecords(all);
            full.setTotal(all.size());
            return full;
        }
        return page(new Page<>(page, pageSize), wrapper);
    }

    @Override
    public List<SubTask> listAvailable() {
        return lambdaQuery()
                .eq(SubTask::getStatus, SubTaskStatus.PENDING)
                .orderByDesc(SubTask::getCreateTime)
                .list();
    }

    @Override
    public List<SubTask> listMine(Long assignedAgentId) {
        return lambdaQuery()
                .eq(SubTask::getAssignedAgentId, assignedAgentId)
                .orderByDesc(SubTask::getCreateTime)
                .list();
    }

    @Override
    public SubTask getByIdForUpdate(Long subTaskId) {
        return lambdaQuery()
                .eq(SubTask::getId, subTaskId)
                .last("FOR UPDATE")
                .one();
    }

    @Override
    public boolean isReady(SubTask subTask) {
        if (subTask == null) {
            return false;
        }
        List<Long> deps = subTask.dependsOnIdList();
        if (deps.isEmpty()) {
            return true;
        }
        long doneCount = lambdaQuery()
                .in(SubTask::getId, deps)
                .eq(SubTask::getStatus, SubTaskStatus.DONE)
                .count();
        return doneCount >= deps.size();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDependsOn(Long subTaskId, List<Long> dependsOnIds) {
        List<Long> ids = dependsOnIds != null ? dependsOnIds : Collections.emptyList();
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(ids.get(i));
        }
        json.append(']');
        int updated = baseMapper.updateDependsOn(subTaskId, json.toString(), OffsetDateTime.now());
        if (updated == 0) {
            throw new BizException("子任务不存在或已删除，无法写入依赖: " + subTaskId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId) {
        changeStatus(subTaskId, newStatus, agentId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId, Map<String, Object> contextPatch) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }

        SubTaskStatus oldStatus = subTask.getStatus();
        // 变更前执行者快照——换人/回收时需告知旧执行者任务已转移
        Long oldAgentId = subTask.getAssignedAgentId();
        SubTaskStateMachine.validate(oldStatus, newStatus);

        if (contextPatch != null && !contextPatch.isEmpty()) {
            Map<String, Object> ctx = new HashMap<>(subTask.getContext() != null ? subTask.getContext() : Map.of());
            ctx.putAll(contextPatch);
            subTask.setContext(ctx);
        }

        subTask.setStatus(newStatus);
        if (newStatus == SubTaskStatus.PENDING && agentId == null) {
            subTask.setAssignedAgentId(null);
        } else if (agentId != null) {
            subTask.setAssignedAgentId(agentId);
        }

        boolean updated = updateById(subTask);
        if (!updated) {
            throw new BizException("并发修改，请重试");
        }

        agentOutboxService.createEvent(subTask, newStatus);

        // 投递收件箱通知
        sendInboxNotification(subTask, newStatus, oldStatus);
        // 执行者变更（换人/回收）时告知旧执行者，防止误以为任务仍在名下继续干活
        notifyAgentHandover(subTask, oldAgentId);
        publishAssignmentEvent(subTask, newStatus);
        if (subTask.getAssignedAgentId() != null
                && (newStatus == SubTaskStatus.IN_PROGRESS || newStatus == SubTaskStatus.REVIEW)) {
            heartbeatService.active(subTask.getAssignedAgentId());
        }

        log.info("子任务状态变更: subTaskId={}, from={}, to={}, agentId={}",
                subTaskId, oldStatus, newStatus, agentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void claim(Long subTaskId, Long agentId) {
        changeStatus(subTaskId, SubTaskStatus.ASSIGNED, agentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void start(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.IN_PROGRESS, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.REVIEW, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long subTaskId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);

        // 幂等性: 已是 DONE 状态直接返回，避免前端/重试重复调用报 DONE→DONE 错误
        if (subTask.getStatus() == SubTaskStatus.DONE) {
            log.info("complete() 幂等跳过: subTaskId={} 已是 DONE", subTaskId);
            return;
        }

        SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.DONE);
        subTask.setStatus(SubTaskStatus.DONE);
        subTask.setCompleteTime(OffsetDateTime.now());

        // 隐式评分集成
        if (subTask.getAssignedAgentId() != null) {
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
                            subTask.getAssignedAgentId(),
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
        // 审查通过补发收件箱通知（携带最新一轮 review 评分/评语），
        // 外部 Agent 轮询 pullTasks 即可感知交付结果反馈
        sendApprovedInboxNotification(subTask);

        // 闭环收尾：事务提交后异步解锁下游依赖节点 + 尝试 Task 自动收尾
        // （AFTER_COMMIT 监听在 SubTaskCompletionListener，避免与分发服务形成循环依赖）
        applicationEventPublisher.publishEvent(new SubTaskCompletedEvent(subTaskId, subTask.getTaskId()));
        log.info("子任务审查通过: subTaskId={}", subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pause(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.PAUSED, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resume(Long subTaskId) {
        changeStatus(subTaskId, SubTaskStatus.IN_PROGRESS, null);
    }

    /**
     * 驳回统一补发收件箱通知（自动核验 rejectAndRework 与人工驳回 rework/reworkFresh 共用），
     * 摘要携带最近一轮 review 结果（评分/评语/问题），外部 Agent 轮询 pullTasks 即可感知返工原因。
     * 发送失败只 warn 不阻断（返工主链路优先）。
     */
    private void sendReworkInboxNotification(SubTask subTask) {
        try {
            Long agentId = subTask.getAssignedAgentId();
            if (agentId == null) {
                return;
            }
            String eventId = "subtask." + subTask.getId() + ".rejected." + System.currentTimeMillis();
            agentInboxService.send(agentId, eventId, "sub_task.rejected",
                    "任务需返工: " + subTask.getTitle(),
                    buildReworkSummary(subTask),
                    "sub_task", subTask.getId(), "HIGH");
        } catch (Exception e) {
            log.warn("驳回收件箱通知发送失败（不阻断返工）: subTaskId={}, err={}", subTask.getId(), e.getMessage());
        }
    }

    /**
     * 从 context.reviewHistory 最新一轮（格式 {round,ts,reviewerAgentId,issues,comment,score,...}）
     * 提取驳回摘要；无历史（如人工直接驳回）时回退默认文案。
     */
    @SuppressWarnings("unchecked")
    private String buildReworkSummary(SubTask subTask) {
        Map<String, Object> ctx = subTask.getContext();
        if (ctx != null && ctx.get("reviewHistory") instanceof List<?> history && !history.isEmpty()) {
            Object last = history.get(history.size() - 1);
            if (last instanceof Map<?, ?> m) {
                StringBuilder sb = new StringBuilder("审查未通过");
                Object score = m.get("score");
                if (score instanceof Number n) {
                    sb.append("，评分 ").append(n.intValue()).append("/5");
                }
                Object comment = m.get("comment");
                if (comment instanceof String c && !c.isBlank()) {
                    sb.append("；评语: ").append(clip(c, 150));
                }
                Object issues = m.get("issues");
                if (issues instanceof List<?> issueList && !issueList.isEmpty()) {
                    String joined = issueList.stream().map(Object::toString).collect(Collectors.joining("、"));
                    sb.append("；问题: ").append(clip(joined, 150));
                } else if (issues instanceof String issueStr && !issueStr.isBlank()) {
                    sb.append("；问题: ").append(clip(issueStr, 150));
                }
                return sb.toString();
            }
        }
        return "请查审查记录了解具体问题";
    }

    /**
     * 审查通过补发收件箱通知（携带最新一轮 review 评分/评语），
     * 外部 Agent 轮询 pullTasks 即可感知交付结果反馈。发送失败只 warn 不阻断。
     */
    private void sendApprovedInboxNotification(SubTask subTask) {
        try {
            Long agentId = subTask.getAssignedAgentId();
            if (agentId == null) {
                return;
            }
            String eventId = "subtask." + subTask.getId() + ".approved." + System.currentTimeMillis();
            agentInboxService.send(agentId, eventId, "sub_task.approved",
                    "任务审查通过: " + subTask.getTitle(),
                    buildApprovedSummary(subTask),
                    "sub_task", subTask.getId(), "NORMAL");
        } catch (Exception e) {
            log.warn("审查通过收件箱通知发送失败（不阻断完成）: subTaskId={}, err={}", subTask.getId(), e.getMessage());
        }
    }

    /**
     * 从 review_record 取最新一轮（round 最大）的评分/评语拼摘要；无记录时回退默认文案。
     */
    private String buildApprovedSummary(SubTask subTask) {
        try {
            List<ReviewRecord> reviews = reviewRecordMapper.selectList(
                    new LambdaQueryWrapper<ReviewRecord>()
                            .eq(ReviewRecord::getSubTaskId, subTask.getId())
                            .orderByDesc(ReviewRecord::getRound)
                            .last("LIMIT 1"));
            if (reviews != null && !reviews.isEmpty()) {
                ReviewRecord latest = reviews.get(0);
                StringBuilder sb = new StringBuilder("审查通过");
                if (latest.getScore() != null) {
                    sb.append("，评分 ").append(latest.getScore()).append("/5");
                }
                if (latest.getComment() != null && !latest.getComment().isBlank()) {
                    sb.append("；评语: ").append(clip(latest.getComment(), 150));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            log.debug("审查通过摘要读取失败，回退默认: subTaskId={}, err={}", subTask.getId(), e.getMessage());
        }
        return "审查通过，请查看详情";
    }

    private String clip(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    /**
     * 向相关 Agent 投递收件箱通知
     */
    private void sendInboxNotification(SubTask subTask, SubTaskStatus newStatus, SubTaskStatus oldStatus) {
        String eventId = "subtask." + subTask.getId() + "." + System.currentTimeMillis();
        Long agentId = subTask.getAssignedAgentId();
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
                    String reason = null;
                    if (subTask.getContext() != null && subTask.getContext().get("blockedReason") instanceof String r) {
                        reason = r;
                    }
                    String summary = reason != null && !reason.isBlank()
                            ? ("阻塞原因: " + reason)
                            : "需要 Planner 排障处理";
                    AgentService agentService = agentServiceProvider.getIfAvailable();
                    List<Agent> planners = agentService == null
                            ? List.of() : agentService.listByRole(AgentRole.PLANNER);
                    for (Agent planner : planners) {
                        agentInboxService.send(planner.getId(), eventId, "sub_task.blocked",
                                "任务阻塞: " + title,
                                summary,
                                "sub_task", subTask.getId(), "URGENT");
                    }
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
                    // 修复: EXECUTOR 提交后，通知所有 PLANNER/REVIEWER 来审查
                    try {
                        AgentService agentService = agentServiceProvider.getIfAvailable();
                        List<Agent> planners = agentService == null
                                ? List.of() : agentService.listByRole(AgentRole.PLANNER);
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

    /**
     * 执行者变更撤销通知：旧执行者收不到"任务已转移"事件时，
     * 会误以为任务仍在名下继续干活（trae 1925 冷启动白做）。
     *
     * <p>在换人（旧执行者 A → 新执行者 B）或回收（执行者被清空）时，向旧执行者
     * 补发 {@code sub_task.reassigned} / {@code sub_task.unassigned} 收件箱通知：
     * <ul>
     *   <li>换人（newAgentId != null）：任务已改派给其他 Agent，立即停止执行；</li>
     *   <li>回收（newAgentId == null）：任务已从名下回收（回 PENDING / 死信），停止执行。</li>
     * </ul>
     * 初始分配（oldAgentId == null）与原地保留（old == new）不通知。
     * API_KEY_LLM 旧执行者由 {@link AgentInboxService#send} 内部守卫跳过（其消费链走 outbox→MQ）。</p>
     */
    private void notifyAgentHandover(SubTask subTask, Long oldAgentId) {
        Long newAgentId = subTask.getAssignedAgentId();
        if (oldAgentId == null || oldAgentId.equals(newAgentId)) {
            return;
        }
        String eventId = "subtask." + subTask.getId() + ".handover." + System.currentTimeMillis();
        String title = subTask.getTitle();
        if (newAgentId != null) {
            agentInboxService.send(oldAgentId, eventId, "sub_task.reassigned",
                    "任务已改派: " + title,
                    "该任务已改派给其他 Agent，请立即停止执行并等待新任务",
                    "sub_task", subTask.getId(), "HIGH");
        } else {
            agentInboxService.send(oldAgentId, eventId, "sub_task.unassigned",
                    "任务已回收: " + title,
                    "该任务已从你名下回收，请立即停止执行并等待新任务",
                    "sub_task", subTask.getId(), "HIGH");
        }
    }

    private void publishAssignmentEvent(SubTask subTask, SubTaskStatus newStatus) {
        if (newStatus != SubTaskStatus.ASSIGNED || subTask.getAssignedAgentId() == null) {
            return;
        }
        applicationEventPublisher.publishEvent(
                new SubTaskAssignedEvent(subTask.getId(), subTask.getAssignedAgentId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rework(Long subTaskId, Long reworkAgentId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);
        SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.REWORK);
        // 变更前执行者快照（自动驳回一般保持原执行者，防御性兼容换人）
        Long oldAgentId = subTask.getAssignedAgentId();
        subTask.setStatus(SubTaskStatus.REWORK);
        subTask.setReworkCount(subTask.getReworkCount() != null ? subTask.getReworkCount() + 1 : 1);
        if (reworkAgentId != null) {
            subTask.setAssignedAgentId(reworkAgentId);
        }
        updateById(subTask);
        // 执行者变更（换人/回收）时告知旧执行者
        notifyAgentHandover(subTask, oldAgentId);
        // 驳回统一补发收件箱通知（自动核验 rejectAndRework 与人工 reworkById 共用本入口），
        // 摘要携带最近一轮 review 结果（评分/评语/问题），外部 Agent 轮询 pullTasks 即可感知返工原因
        sendReworkInboxNotification(subTask);
        agentOutboxService.createEvent(subTask, SubTaskStatus.REWORK);
        // §6.104 打回失效：旧提交附件全部置 INACTIVE，返工必须重新上传最新版
        // （与核验/装载/打包的 listActive 可信视角闭环，旧证据不再进入下次核验）
        invalidateAttachmentsOnRework(subTaskId);
        log.info("子任务驳回返工: subTaskId={}, reworkCount={}", subTaskId, subTask.getReworkCount());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reworkFresh(Long subTaskId, Long reworkAgentId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);
        SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.REWORK);
        // 变更前执行者快照——人工驳回改派时需告知旧执行者任务已转移
        Long oldAgentId = subTask.getAssignedAgentId();
        subTask.setStatus(SubTaskStatus.REWORK);
        subTask.setReworkCount(0);
        if (reworkAgentId != null) {
            subTask.setAssignedAgentId(reworkAgentId);
        }
        // 人工已拍板：清除人工介入标记，避免前端面板残留、PENDING 兜底巡检继续跳过
        Map<String, Object> ctx = new HashMap<>(subTask.getContext() != null ? subTask.getContext() : Map.of());
        if (ctx.remove("manualIntervention") != null) {
            subTask.setContext(ctx);
        }
        updateById(subTask);
        // 执行者变更（换人/回收）时告知旧执行者
        notifyAgentHandover(subTask, oldAgentId);
        // 人工驳回同样补发收件箱通知（携带 review 摘要，无历史时回退默认文案）
        sendReworkInboxNotification(subTask);
        agentOutboxService.createEvent(subTask, SubTaskStatus.REWORK);
        // 注意：Map.of 不接受 null 值，reworkAgentId 可能为 null（不换派原执行者重做），必须用 HashMap
        Map<String, Object> extra = new HashMap<>();
        extra.put("reworkCountReset", "true");
        extra.put("reworkAgentId", reworkAgentId);
        taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                "sub_task_manual_rework_reset", AgentRole.SYSTEM, reworkAgentId, extra);
        // §6.104 打回失效：人工驳回同步让旧 ACTIVE 附件失效，新执行者重新产出后须 uploadArtifact 重建 ACTIVE
        invalidateAttachmentsOnRework(subTaskId);
        log.info("人工驳回重置返工计数: subTaskId={}, reworkCount=0, reworkAgentId={}", subTaskId, reworkAgentId);
    }

    /**
     * 打回失效：将该子任务全部 ACTIVE 附件批量置 INACTIVE，
     * 与 {@code AttachmentServiceImpl.register} 的同名去活互补。
     *
     * <p>通过 {@code ObjectProvider<AttachmentService>} 懒解析打破与 AttachmentServiceImpl
     * 的构造器循环（后者依赖本服务的 register 归属校验）；解析失败/异常一律 warn
     * 不阻断返工主链路（与 sendReworkInboxNotification 的 best-effort 哲学一致），
     * 旧证据残留可在下次核验或人工打捞时识别（状态为 INACTIVE）。</p>
     */
    private void invalidateAttachmentsOnRework(Long subTaskId) {
        try {
            AttachmentService attachmentService = attachmentServiceProvider.getIfAvailable();
            if (attachmentService == null) {
                log.warn("附件服务不可用，跳过打回失效: subTaskId={}", subTaskId);
                return;
            }
            attachmentService.invalidateBySubTask(subTaskId);
        } catch (Exception e) {
            log.warn("附件打回失效失败（不阻断返工主链路）: subTaskId={}, err={}",
                    subTaskId, e.getMessage());
        }
    }

    @Override
    public void markManualIntervention(Long subTaskId, String reason, Map<String, Object> extra) {
        try {
            SubTask fresh = getById(subTaskId);
            if (fresh == null) {
                return;
            }
            Map<String, Object> ctx = new HashMap<>(fresh.getContext() != null ? fresh.getContext() : Map.of());
            Map<String, Object> mark = new HashMap<>();
            mark.put("reason", reason);
            mark.put("ts", OffsetDateTime.now().toString());
            if (extra != null) {
                mark.putAll(extra);
            }
            ctx.put("manualIntervention", mark);
            fresh.setContext(ctx);
            updateById(fresh);
            // 可观测：人工介入标记落 timeline（OPS 泳道），时序图可见"等待人工处置"节点；
            // 不能复用 Map.of——mark 可能含 null 值（extra 传入时）
            taskTimelineService.recordEvent(fresh.getTaskId(), subTaskId,
                    "sub_task_manual_intervention_required", AgentRole.SYSTEM, null,
                    new HashMap<>(mark));
            log.info("人工介入标记已写入: subTaskId={}, reason={}", subTaskId, reason);
        } catch (Exception e) {
            log.warn("人工介入标记写入失败（不影响主链路）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void block(Long subTaskId) {
        block(subTaskId, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void block(Long subTaskId, String reason, Long reporterAgentId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);
        if (subTask.getStatus() != SubTaskStatus.IN_PROGRESS
                && subTask.getStatus() != SubTaskStatus.ASSIGNED
                && subTask.getStatus() != SubTaskStatus.REWORK) {
            throw new BizException("只能对 IN_PROGRESS/ASSIGNED/REWORK 状态的子任务标记 BLOCKED");
        }

        Map<String, Object> patch = new HashMap<>();
        if (reason != null && !reason.isBlank()) {
            patch.put("blockedReason", reason);
        }
        if (reporterAgentId != null) {
            patch.put("blockedByAgentId", reporterAgentId);
        }
        patch.put("blockedAt", OffsetDateTime.now().toString());

        changeStatus(subTaskId, SubTaskStatus.BLOCKED, null, patch);

        SubTask updated = getById(subTaskId);
        if (updated != null) {
            taskTimelineService.recordEvent(
                    updated.getTaskId(),
                    updated.getId(),
                    "sub_task_report_blocked",
                    AgentRole.EXECUTOR,
                    reporterAgentId,
                    Map.of(
                            "reason", reason != null ? reason : "",
                            "source", "agent_report"));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long subTaskId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);
        if (subTask.getStatus() == SubTaskStatus.DONE || subTask.getStatus() == SubTaskStatus.CANCELLED) {
            throw new BizException("已完成或已取消的子任务不能再次取消");
        }
        changeStatus(subTaskId, SubTaskStatus.CANCELLED, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignNext(Long agentId, Long subTaskId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);
        if (subTask.getStatus() != SubTaskStatus.PENDING) {
            throw new BizException("只有 PENDING 状态的子任务才能分配，当前状态: " + subTask.getStatus());
        }
        // E2：并发额度原子防线——FOR UPDATE 锁 agent 行，串行化同一 Agent 的并发派发；
        // 锁内重新统计在飞数判定额度（选人通过 ≠ 落库安全，杜绝并发超发窗口）。
        // 满额抛 AgentUnavailableException：不计入熔断统计，由 ResilientDispatcher
        // 走 fallback 换人；并发窗口下 fallback 内仍满额则异常冒泡，任务保持 PENDING 由定时兜底重试。
        AgentService agentService = agentServiceProvider.getIfAvailable();
        if (agentService == null) {
            throw new BizException("Agent 服务不可用");
        }
        agentService.lockByIdForUpdate(agentId);
        if (agentDispatchProperties.isEnforceMaxConcurrent()
                && !concurrencyQuotaService.canAccept(agentId)) {
            throw new AgentUnavailableException(
                    "Agent 并发额度已满: agentId=" + agentId + ", subTaskId=" + subTaskId, agentId);
        }
        changeStatus(subTaskId, SubTaskStatus.ASSIGNED, agentId);
    }

    @Override
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
        subTask.setAssignedAgentId(null);
        boolean updated = updateById(subTask);
        if (!updated) {
            throw new BizException("并发修改，请重试");
        }
        return getById(subTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reassign(Long subTaskId, Long newAgentId) {
        resetToPendingForDispatch(subTaskId, Set.of(SubTaskStatus.BLOCKED));
        changeStatus(subTaskId, SubTaskStatus.ASSIGNED, newAgentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<SubTask> createBatch(List<BatchCreateItem> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        List<SubTask> created = new java.util.ArrayList<>(items.size());
        int failCount = 0;
        for (BatchCreateItem item : items) {
            if (item == null || item.getSubTask() == null) {
                failCount++;
                continue;
            }
            try {
                SubTask subTask = create(item.getSubTask(), item.getAssignedAgentId());
                created.add(subTask);
            } catch (Exception e) {
                failCount++;
                log.warn("子任务批量派发单项失败: title={}, agentId={}, err={}",
                        item.getSubTask().getTitle(), item.getAssignedAgentId(), e.getMessage());
            }
        }
        log.info("子任务批量派发: total={}, success={}, failed={}",
                items.size(), created.size(), failCount);
        return created;
    }

    @Override
    public List<SubTask> listReviewOrphans(int thresholdSeconds, int limit) {
        OffsetDateTime threshold = OffsetDateTime.now().minusSeconds(thresholdSeconds);
        List<SubTask> candidates = lambdaQuery()
                .eq(SubTask::getStatus, SubTaskStatus.REVIEW)
                .le(SubTask::getUpdateTime, threshold)
                .orderByAsc(SubTask::getUpdateTime)
                .last("LIMIT " + limit)
                .list();
        candidates.removeIf(st -> {
            Map<String, Object> ctx = st.getContext();
            return ctx != null && ctx.get("manualIntervention") != null;
        });
        return candidates;
    }

    // ══════════════════════════════════════════════════════════════
    //  阶段五 agent→task.mapper 清零承接（统计 / 解绑 / 原子认领）
    // ══════════════════════════════════════════════════════════════

    @Override
    public Map<String, Integer> countByStatusForAgent(Long agentId) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("assignedCount", 0);
        m.put("inProgressCount", 0);
        m.put("doneCount", 0);
        m.put("blockedCount", 0);
        m.put("reviewCount", 0);
        if (agentId == null) return m;

        List<SubTask> subs = lambdaQuery()
                .select(SubTask::getStatus)
                .eq(SubTask::getAssignedAgentId, agentId)
                .list();
        for (SubTask s : subs) {
            if (s.getStatus() == SubTaskStatus.DONE) m.merge("doneCount", 1, Integer::sum);
            else if (s.getStatus() == SubTaskStatus.IN_PROGRESS) m.merge("inProgressCount", 1, Integer::sum);
            else if (s.getStatus() == SubTaskStatus.BLOCKED) m.merge("blockedCount", 1, Integer::sum);
            else if (s.getStatus() == SubTaskStatus.REVIEW) m.merge("reviewCount", 1, Integer::sum);
            else m.merge("assignedCount", 1, Integer::sum);
        }
        return m;
    }

    @Override
    public long countByAssignedAgent(Long agentId) {
        if (agentId == null) return 0;
        return lambdaQuery().eq(SubTask::getAssignedAgentId, agentId).count();
    }

    @Override
    public long countReviewByReviewerAgent(Long agentId) {
        if (agentId == null) return 0;
        return reviewRecordMapper.selectCount(
                new LambdaQueryWrapper<ReviewRecord>().eq(ReviewRecord::getReviewerAgentId, agentId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlinkByAssignedAgent(Long agentId) {
        lambdaUpdate()
                .eq(SubTask::getAssignedAgentId, agentId)
                .set(SubTask::getAssignedAgentId, null)
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean claimAtomic(Long subTaskId, Long agentId) {
        // ServiceImpl<SubTaskMapper, SubTask>：baseMapper 即 SubTaskMapper
        return baseMapper.claimAtomic(subTaskId, agentId) > 0;
    }

    @Override
    public List<SubTask> selectInFlightByAgent(Long agentId, int limit) {
        return baseMapper.selectInFlightByAgent(agentId, limit);
    }

    @Override
    public int countInFlightByAgent(Long agentId) {
        return baseMapper.countInFlightByAgent(agentId);
    }
}
