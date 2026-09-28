package com.helloai.core.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.helloai.common.constant.FinalReportStatus;
import com.helloai.core.agent.service.AgentOutboxService;
import com.helloai.core.shared.event.TaskFinalReportGeneratedEvent;
import com.helloai.core.task.entity.Task;
import com.helloai.core.task.service.TaskService;
import com.helloai.core.task.statemachine.FinalReportStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 最终整合报告「写回 + 审查触发」的事务边界（§12.2 审查链改造）。
 *
 * <p><b>为什么单独一个 Bean</b>：{@code TaskFinalReportServiceImpl.generateWithAttempt}
 * 内含多次 LLM 调用（出纲 + 正文 + 可能降档重试），绝不能进事务；而"报告落库"与
 * "审查触发"必须原子。两者拆开后，若在同一个类里给私有方法加 {@code @Transactional}
 * 会因<b>自调用不走代理</b>而静默失效——所以独立 Bean 是保证事务真正生效的必要条件，
 * 不是过度拆分。</p>
 *
 * <p><b>修的是什么</b>：改造前是典型的 dual-write——先
 * {@code lambdaUpdate()} 写回 {@code REVIEWING}，再另行
 * {@code applicationEventPublisher.publishEvent(...)}。两步之间没有任何原子性，
 * 进程在中间挂掉就是"报告已落库、审查永不触发"的永久卡死（实测已发生）。
 * 现在两件事同事务提交：报告写回成功 ⟺ 审查请求落库成功。</p>
 *
 * <p><b>审查触发的两条腿</b>（都在本事务内落库/发布）：</p>
 * <ul>
 *   <li><b>L1</b>：{@link TaskFinalReportGeneratedEvent} 在<b>事务内</b>发布，
 *       由 {@code FinalReportReviewServiceImpl.onFinalReportGenerated}
 *       以 {@code @TransactionalEventListener(AFTER_COMMIT)} 承接——提交后才触发，
 *       避免审查读到未提交的报告（旧实现是普通 {@code @EventListener}，
 *       一旦调用方加了事务就会在提交前触发并命中陈旧守卫被丢弃）。</li>
 *   <li><b>L2</b>：{@link AgentOutboxService#createReportReviewEvent} 落 Outbox 行，
 *       进程重启后由 {@code AgentEventCompensationTask} 补投到报告审查队列。</li>
 * </ul>
 *
 * <p>L1 与 L2 会各触发一次审查，重复由 {@code FinalReportReviewServiceImpl} 的
 * Redis 防双审锁 + {@code final_report_status} 状态守卫消解（先到者收敛后，
 * 后到者见状态已非 {@code REVIEWING} 直接跳过）。这与子任务核验链的
 * 三级容错设计一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinalReportPersistService {

    private final TaskService taskService;
    private final AgentOutboxService agentOutboxService;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 同一事务内完成：报告写回（含 §12.1 prev 槽换入）+ L2 Outbox 落库 + L1 事件发布。
     *
     * @param taskId         任务 ID
     * @param report         报告正文
     * @param plannerAgentId 生成者（Planner）Agent ID
     * @param now            报告写回时间（微秒截断，作为陈旧守卫锚点）
     * @param attempt        生成轮次（首次=1，返工递增）
     * @param sectionCount   参与整合的子任务数（事件度量用）
     * @param reviewEnabled  自动审查开关：关时直接写 {@code DONE}，不产生审查触发
     * @return true=写回成功；false=CAS 未命中（状态已被其它链路接管，本次写回作废）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean persistAndRequestReview(Long taskId, String report, Long plannerAgentId,
                                           OffsetDateTime now, int attempt, int sectionCount,
                                           boolean reviewEnabled) {
        FinalReportStatus from = FinalReportStatus.GENERATING;
        FinalReportStatus to = reviewEnabled ? FinalReportStatus.REVIEWING : FinalReportStatus.DONE;
        // §12.5 状态机单点校验：写回只允许 GENERATING -> REVIEWING/DONE
        FinalReportStateMachine.assertTransit(from, to);

        // CAS 条件 eq(final_report_status, GENERATING) 是幂等护栏：生成期间状态恒为 GENERATING
        // （并发生成被入口 CAS 的 ne(GENERATING) 挡住、回滚被 ne(GENERATING) 挡住），
        // 因此条件成立是必然的；若不成立说明有链路绕过状态机直写了状态——宁可丢这次写回并告警，
        // 也不能覆盖别人刚写入的状态。
        LambdaUpdateWrapper<Task> update = new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, taskId)
                .eq(Task::getFinalReportStatus, from)
                // §12.1 单槽列：覆盖前把被覆盖的那一版整体（正文/生成 Agent/时间）落入 prev 槽；
                // setSql 内列引用取本语句执行前的行值，不受 Java 侧快照陈旧影响；无旧版保持 null
                .setSql("final_report_prev = CASE WHEN final_report IS NOT NULL AND final_report <> '' "
                        + "THEN final_report END, "
                        + "final_report_prev_agent_id = CASE WHEN final_report IS NOT NULL "
                        + "AND final_report <> '' THEN final_report_agent_id END, "
                        + "final_report_prev_time = CASE WHEN final_report IS NOT NULL "
                        + "AND final_report <> '' THEN final_report_time END")
                .set(Task::getFinalReport, report)
                .set(Task::getFinalReportAgentId, plannerAgentId)
                .set(Task::getFinalReportTime, now)
                .set(Task::getFinalReportStatus, to);
        boolean updated = taskService.update(update);
        if (!updated) {
            log.warn("报告写回 CAS 未命中（状态已被其它链路接管），本次写回与审查触发一并作废: "
                    + "taskId={}, expectedStatus={}", taskId, from);
            return false;
        }

        if (!reviewEnabled) {
            return true;
        }

        // L2：审查请求持久化（同事务）——进程重启/线程丢失后仍可补投
        agentOutboxService.createReportReviewEvent(taskId, now, attempt, report.length());
        // L1：事务内发布，AFTER_COMMIT 承接（见类注释）
        applicationEventPublisher.publishEvent(new TaskFinalReportGeneratedEvent(
                taskId, report.length(), sectionCount, attempt, now));
        return true;
    }
}
