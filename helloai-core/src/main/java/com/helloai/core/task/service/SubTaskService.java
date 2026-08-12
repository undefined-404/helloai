package com.helloai.core.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.task.entity.ReviewRecord;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.shared.event.SubTaskAssignedEvent;
import com.helloai.core.shared.event.SubTaskCompletedEvent;
import com.helloai.core.agent.observability.HeartbeatService;
import com.helloai.core.task.mapper.ReviewRecordMapper;
import com.helloai.core.task.mapper.SubTaskMapper;
import com.helloai.core.task.score.ImplicitScoreCalculator;
import com.helloai.core.task.score.ImplicitScoreCalculator.ScoreResult;
import com.helloai.core.task.statemachine.SubTaskStateMachine;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.agent.service.AgentInboxService;
import com.helloai.core.agent.service.AgentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final TaskTimelineService taskTimelineService;

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

    /**
     * 子任务条件查询（主任务 / 状态 / 负责 Agent 组合过滤）。
     *
     * <p>按 §6.3 分层红线从 SubTaskController 收口：条件构造归 Service 层。
     * {@code page == null || page <= 0} 时返回全量列表（包装成 IPage，便于 Controller 统一处理），
     * 兼容 SKILL.md 外部 Agent 不分页调用契约；否则按分页参数返回。</p>
     *
     * @param taskId          主任务 ID，可为 null
     * @param status          子任务状态，可为 null
     * @param assignedAgentId 负责 Agent ID，可为 null
     * @param page            页码，null 或 <=0 表示不分页
     * @param pageSize        每页条数（仅分页时生效）
     * @return 分页结果或全量列表；绝不返回 null
     */
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

    /**
     * 待领取子任务列表（PENDING 状态，按创建时间倒序）。
     *
     * <p>按 §6.3 分层红线从 SubTaskController 收口。</p>
     */
    public List<SubTask> listAvailable() {
        return lambdaQuery()
                .eq(SubTask::getStatus, SubTaskStatus.PENDING)
                .orderByDesc(SubTask::getCreateTime)
                .list();
    }

    /**
     * 指定 Agent 负责的子任务列表（按创建时间倒序）。
     *
     * <p>按 §6.3 分层红线从 SubTaskController 收口。</p>
     */
    public List<SubTask> listMine(Long assignedAgentId) {
        return lambdaQuery()
                .eq(SubTask::getAssignedAgentId, assignedAgentId)
                .orderByDesc(SubTask::getCreateTime)
                .list();
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

    /**
     * ready 语义判定（V27 内循环依赖编排）：{@code depends_on} 中所有前置子任务
     * 均为 DONE 才允许分发；空依赖直接就绪（旧数据行为与现状完全一致）。
     *
     * <p>分发链两处复用：{@code SubTaskDispatchService.dispatchPendingSubTaskAuto}
     * 与 {@code SubTaskPendingOrphanTask} 孤儿扫描，依赖检查逻辑收敛在本方法。</p>
     */
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

    /**
     * 写入依赖 id 数组（V27）：手工拼 JSON 数字数组后走专用 Mapper SQL（::jsonb），
     * 不走 updateById 全列覆盖，避免乐观锁 version 参数依赖。
     * 专供 PlannerAnalysisService 拆解落库后的"序号→真实 id"回写。
     *
     * <p>不能用全局 ObjectMapper 序列化：它注册了 Long→String（JacksonConfig，
     * 防前端精度丢失），会把依赖 id 写成字符串数组，导致 ready 守卫读取时
     * 归一化失败、有依赖节点被误判为就绪。</p>
     */
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

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId) {
        changeStatus(subTaskId, newStatus, agentId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long subTaskId, SubTaskStatus newStatus, Long agentId, Map<String, Object> contextPatch) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) {
            throw new BizException("子任务不存在: " + subTaskId);
        }

        SubTaskStatus oldStatus = subTask.getStatus();
        // A0-1（§6.60）：变更前执行者快照——换人/回收时需告知旧执行者任务已转移
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

        // v1.1: 投递收件箱通知
        sendInboxNotification(subTask, newStatus, oldStatus);
        // A0-1（§6.60）：执行者变更（换人/回收）时告知旧执行者，防止误以为任务仍在名下继续干活
        notifyAgentHandover(subTask, oldAgentId);
        publishAssignmentEvent(subTask, newStatus);
        if (subTask.getAssignedAgentId() != null
                && (newStatus == SubTaskStatus.IN_PROGRESS || newStatus == SubTaskStatus.REVIEW)) {
            heartbeatService.active(subTask.getAssignedAgentId());
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
        subTask.setCompleteTime(OffsetDateTime.now());

        // v1.1: 隐式评分集成
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
        // A0-4（§6.63）：审查通过补发收件箱通知（携带最新一轮 review 评分/评语），
        // 外部 Agent 轮询 pullTasks 即可感知交付结果反馈
        sendApprovedInboxNotification(subTask);

        // V27 闭环收尾：事务提交后异步解锁下游依赖节点 + 尝试 Task 自动收尾
        // （AFTER_COMMIT 监听在 SubTaskCompletionListener，避免与分发服务形成循环依赖）
        applicationEventPublisher.publishEvent(new SubTaskCompletedEvent(subTaskId, subTask.getTaskId()));
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
     * A0-4（§6.63）：驳回统一补发收件箱通知（自动核验 rejectAndRework 与人工驳回 rework/reworkFresh 共用），
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
     * 从 context.reviewHistory 最新一轮（V38 格式 {round,ts,reviewerAgentId,issues,comment,score,...}）
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
     * A0-4（§6.63）：审查通过补发收件箱通知（携带最新一轮 review 评分/评语），
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
                    List<Agent> planners = agentService.listByRole(AgentRole.PLANNER);
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

    /**
     * A0-1（§6.60）执行者变更撤销通知：旧执行者收不到"任务已转移"事件时，
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

    @Transactional(rollbackFor = Exception.class)
    public void rework(Long subTaskId, Long reworkAgentId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);
        SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.REWORK);
        // A0-1（§6.60）：变更前执行者快照（自动驳回一般保持原执行者，防御性兼容换人）
        Long oldAgentId = subTask.getAssignedAgentId();
        subTask.setStatus(SubTaskStatus.REWORK);
        subTask.setReworkCount(subTask.getReworkCount() != null ? subTask.getReworkCount() + 1 : 1);
        if (reworkAgentId != null) {
            subTask.setAssignedAgentId(reworkAgentId);
        }
        updateById(subTask);
        // A0-1（§6.60）：执行者变更（换人/回收）时告知旧执行者
        notifyAgentHandover(subTask, oldAgentId);
        // A0-4（§6.63）：驳回统一补发收件箱通知（自动核验 rejectAndRework 与人工 reworkById 共用本入口），
        // 摘要携带最近一轮 review 结果（评分/评语/问题），外部 Agent 轮询 pullTasks 即可感知返工原因
        sendReworkInboxNotification(subTask);
        agentOutboxService.createEvent(subTask, SubTaskStatus.REWORK);
        log.info("子任务驳回返工: subTaskId={}, reworkCount={}", subTaskId, subTask.getReworkCount());
    }

    /**
     * §6.57 人工驳回重置：返工计数归零并清除人工介入标记，开启新一轮执行。
     *
     * <p>与 {@link #rework} 的分工：rework 供自动核验驳回使用（reworkCount 累加，
     * 达 {@code auto-review-max-rework} 后停留 REVIEW 等人工）；人工审查（review API）
     * 驳回代表用户拍板开启新一轮，必须重置计数并清除 manualIntervention 标记，
     * 否则新执行者提交后仍命中 skip_max_rework 跳过自动核验、任务无节点流转。</p>
     *
     * @param subTaskId      子任务 ID
     * @param reworkAgentId  改派目标 Agent（可空：驳回原执行者重做，执行者保持不变）
     */
    @Transactional(rollbackFor = Exception.class)
    public void reworkFresh(Long subTaskId, Long reworkAgentId) {
        SubTask subTask = getById(subTaskId);
        if (subTask == null) throw new BizException("子任务不存在: " + subTaskId);
        SubTaskStateMachine.validate(subTask.getStatus(), SubTaskStatus.REWORK);
        // A0-1（§6.60）：变更前执行者快照——人工驳回改派时需告知旧执行者任务已转移
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
        // A0-1（§6.60）：执行者变更（换人/回收）时告知旧执行者
        notifyAgentHandover(subTask, oldAgentId);
        // A0-4（§6.63）：人工驳回同样补发收件箱通知（携带 review 摘要，无历史时回退默认文案）
        sendReworkInboxNotification(subTask);
        agentOutboxService.createEvent(subTask, SubTaskStatus.REWORK);
        // 注意：Map.of 不接受 null 值，reworkAgentId 可能为 null（不换派原执行者重做），必须用 HashMap
        Map<String, Object> extra = new HashMap<>();
        extra.put("reworkCountReset", "true");
        extra.put("reworkAgentId", reworkAgentId);
        taskTimelineService.recordEvent(subTask.getTaskId(), subTaskId,
                "sub_task_manual_rework_reset", AgentRole.SYSTEM, reworkAgentId, extra);
        log.info("人工驳回重置返工计数: subTaskId={}, reworkCount=0, reworkAgentId={}", subTaskId, reworkAgentId);
    }

    /**
     * §6.52 人工介入标记：写入子任务 context.manualIntervention。
     *
     * <p>自动链路（返工达上限、降级能力不匹配）不再继续打回/重派时调用，
     * 标记该子任务等待人工处置；前端据此展示"人工介入"面板，
     * 由用户选择 agent 驳回改派或直接通过。幂等覆盖写入，失败不抛异常。</p>
     *
     * @param subTaskId 子任务 ID
     * @param reason    触发原因（rework_limit / fallback_skip_execution_dense 等）
     * @param extra     附加信息（reworkCount / maxRework / failedAgentId 等，可空）
     */
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
            log.info("人工介入标记已写入: subTaskId={}, reason={}", subTaskId, reason);
        } catch (Exception e) {
            log.warn("人工介入标记写入失败（不影响主链路）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void block(Long subTaskId) {
        block(subTaskId, null, null);
    }

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
        subTask.setAssignedAgentId(null);
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

    /**
     * 批量创建子任务（v2.5 M4.5 派发控制台——同内容 fan-out 派给多个 Agent）。
     *
     * <p>传入的是已经由 Controller 完成 DTO→Entity 映射的实体集合 + 各自关联的 assignedAgentId。
     * 逐项调用现有 {@link #create(SubTask, Long)} 单建逻辑：</p>
     * <ul>
     *   <li>复用单建的所有装配与状态机逻辑（禁止复制方法体）</li>
     *   <li>每项自身独立事务，单项失败不阻挡其他项（catch 隔离）</li>
     *   <li>返回成功创建的实体列表（不含失败项）</li>
     * </ul>
     *
     * <p>Controller 不感知事务边界，只负责传入、调用、转换为 Response DTO。</p>
     *
     * @param items 创建参数（实体 + assignedAgentId）
     * @return 成功创建的 SubTask 列表（顺序与输入一致，跳过失败项）
     */
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

    /**
     * 批量创建单项参数（v2.5 M4.5）。
     *
     * <p>实体已由 Controller 完成 DTO 映射（含 taskId / moduleId / title / content /
     * deliverable / acceptance / priority / status=PENDING）；assignedAgentId 为直派 Agent ID
     * （可空，为空时走 PENDING 等自动派发）。</p>
     */
    @Data
    public static class BatchCreateItem {
        private SubTask subTask;
        private Long assignedAgentId;
    }

    /**
     * 列出超过阈值秒数仍处于 REVIEW 且未被人工介入的子任务（孤儿 REVIEW）。
     *
     * <p>作为 {@link com.helloai.core.review.SubTaskReviewService#onSubmittedForReview}
     * 事件链的兜底扫描：当 AFTER_COMMIT 事务事件因线程池 / 序列化等原因丢失时，
     * 本方法提供基于 DB 状态的二次发现能力。</p>
     *
     * <p>§6.52 修复：不能以"已有审查记录"排除候选——返工达上限的任务同样持有
     * review_record，若事件链丢失，这类任务将永远不被兜底扫描，永久卡死 REVIEW
     * （前端人工介入面板依赖本扫描写入 manualIntervention 标记）。改为排除
     * 已标记人工介入的任务（人工处置中，不再自动打扰）。</p>
     *
     * @param thresholdSeconds 子任务 update_time 早于 now - thresholdSeconds 的才进入候选
     * @param limit            返回上限
     * @return REVIEW 孤儿子任务列表
     */
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
}
