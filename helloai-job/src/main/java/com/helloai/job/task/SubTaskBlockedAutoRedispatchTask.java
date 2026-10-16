package com.helloai.job.task;

import com.helloai.common.base.BizException;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskDispatchService.RedispatchResult;
import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * BLOCKED 子任务自动重派巡检。
 *
 * <p><b>填补的缺口</b>（plan §10.7.6 方案 b「巡检接线」）：{@code dispatchBlockedSubTask} 此前
 * <b>只有人工入口</b>（{@code POST /api/sub-tasks/reassignById/{id}} 需 {@code subtask:reassign}；
 * {@code redispatchInProgressById}），而 BLOCKED 恰恰是两条自动路径的落点：</p>
 * <ul>
 *     <li><b>重启撞上在途任务</b>（{@code REF-8.6} 引入取消树后）：Spring AMQP 优雅停机中断
 *         MQ 消费者 ⇒ {@code CancellableToolExecutor} 抛 {@code AgentCancelledException}
 *         ⇒ 执行回写判 {@code FAILED} ⇒ {@code ExecutionResultHandler} 落 {@code BLOCKED}
 *         （payload {@code agent_runtime_cancelled: interrupted while waiting tool: ...}）；</li>
 *     <li><b>执行者自报无能力</b>（{@code REF-8.1.1}）：内部执行者调 {@code internal_report_blocked}
 *         ⇒ 落 {@code BLOCKED} + 人工介入标记。</li>
 * </ul>
 * <p>两条路径都<b>不消耗</b>重派预算、也不再被任何定时任务扫描（PENDING 孤儿任务只收 PENDING）
 * ⇒ 无人干预时该子任务永久 BLOCKED、其下游依赖恒 PENDING、整条流水线停摆。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *     <li>每 60s 扫一次：{@code status='BLOCKED' AND update_time &lt; now - 阈值}（默认 5 分钟），
 *         单批上限 50 条；阈值留出人工处置窗口，避免与人工操作抢跑。</li>
 *     <li>逐条调用 {@link SubTaskDispatchService#dispatchBlockedSubTaskAuto}：按角色选人 →
 *         进入既有 {@code dispatchBlockedSubTask} 链（退避 + 熔断 + 选人 + fallback + 时间线），
 *         <b>不改状态机</b>。</li>
 *     <li>ShedLock 实例级互斥，防多实例重复重派。</li>
 * </ul>
 *
 * <h3>归属判定（判在 core Service，不在本类）</h3>
 * <p>「哪些 BLOCKED 可以自动重派」是业务判定（人工介入标记 / 自报阻塞原因码 / 角色推导 / 选人），
 * 全部落在 {@code SubTaskDispatchServiceImpl#dispatchBlockedSubTaskAuto}；本类只做
 * 「扫描 → 逐条调用 → 汇总计数」，与 {@link SubTaskPendingOrphanTask} 同构。</p>
 *
 * <h3>幂等 / 安全</h3>
 * <ol>
 *     <li>每条先按 id 补读最新状态（防回读到陈旧 BLOCKED）与依赖就绪（{@code isReady}）后才重派；</li>
 *     <li>并发冲突（子任务刚被人工处置 / 其它路径推进）抛 {@link BizException} ⇒ 视为 skip，
 *         不计入失败、不中断同轮其它记录；</li>
 *     <li>重派本身仍受既有闸门约束：退避窗口内不重派、预算达
 *         {@code helloai.dispatch.max-reassign-attempts}（默认 5）转 DEAD_LETTER 待人工兜底
 *         ⇒ <b>不会无限循环</b>。</li>
 * </ol>
 *
 * <h3>配置项</h3>
 * <ul>
 *     <li>{@code helloai.execution.blocked-redispatch-enabled}（默认 true）</li>
 *     <li>{@code helloai.execution.blocked-redispatch-scan-interval-ms}（默认 60000）</li>
 *     <li>{@code helloai.execution.blocked-redispatch-threshold-minutes}（默认 5）</li>
 *     <li>{@code helloai.execution.blocked-redispatch-batch-size}（默认 50）</li>
 *     <li>{@code helloai.execution.blocked-redispatch-self-reported-enabled}（默认 true）——
 *         是否把「执行者自报阻塞」也纳入自动重派</li>
 * </ul>
 *
 * @see SubTaskPendingOrphanTask
 * @see SubTaskDispatchService#dispatchBlockedSubTaskAuto
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubTaskBlockedAutoRedispatchTask {

    private final SubTaskService subTaskService;
    private final SubTaskDispatchService subTaskDispatchService;
    private final AgentExecutionProperties executionProperties;

    /**
     * 周期扫描入口。
     *
     * <p>{@code fixedDelayString} 直接绑定配置项，支持 yaml / 环境变量动态调整；默认 60 秒一跑。</p>
     */
    @Scheduled(fixedDelayString = "${helloai.execution.blocked-redispatch-scan-interval-ms:60000}")
    @SchedulerLock(name = "subTaskBlockedAutoRedispatch", lockAtMostFor = "PT60S")
    public void scan() {
        if (!executionProperties.isBlockedRedispatchEnabled()) {
            return;
        }

        try {
            int thresholdMinutes = executionProperties.getBlockedRedispatchThresholdMinutes();
            int batchSize = executionProperties.getBlockedRedispatchBatchSize();
            boolean selfReportedEligible = executionProperties.isBlockedRedispatchSelfReportedEnabled();
            OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(thresholdMinutes);

            List<Long> blockedIds = subTaskService.listStaleBlockedSubTaskIds(cutoff, batchSize);
            if (blockedIds.isEmpty()) {
                return;
            }

            log.warn("BLOCKED 自动重派巡检: 发现 {} 个停滞 BLOCKED 子任务 (threshold={}min)",
                    blockedIds.size(), thresholdMinutes);

            int redispatch = 0;
            int skipStatusChanged = 0;
            int skipNotReady = 0;
            int skipManualIntervention = 0;
            int skipGated = 0;
            int skipNoCandidate = 0;
            int failed = 0;

            for (Long subTaskId : blockedIds) {
                try {
                    // 防御性回读：避免回读到陈旧 BLOCKED 状态（某条可能刚被人工/其它路径处置）
                    SubTask latest = subTaskService.getById(subTaskId);
                    if (latest == null) {
                        log.debug("跳过：子任务不存在: subTaskId={}", subTaskId);
                        continue;
                    }
                    if (latest.getStatus() != SubTaskStatus.BLOCKED) {
                        skipStatusChanged++;
                        log.debug("跳过：状态已变更: subTaskId={}, currentStatus={}",
                                subTaskId, latest.getStatus());
                        continue;
                    }
                    // 依赖未就绪的 BLOCKED 节点不触发重派（等上游 DONE 解锁），
                    // 避免巡检把依赖编排中的阻塞节点误推入调度链
                    if (!subTaskService.isReady(latest)) {
                        skipNotReady++;
                        log.debug("跳过：依赖未就绪: subTaskId={}, dependsOn={}",
                                subTaskId, latest.dependsOnIdList());
                        continue;
                    }

                    RedispatchResult result =
                            subTaskDispatchService.dispatchBlockedSubTaskAuto(subTaskId, selfReportedEligible);
                    if (result.applied()) {
                        redispatch++;
                        log.info("BLOCKED 子任务已自动重派: subTaskId={}", subTaskId);
                    } else if ("no_candidate".equals(result.reason())) {
                        // 可自愈等待态：本轮无候选，保持 BLOCKED 待下轮（不落事件避免刷屏）
                        skipNoCandidate++;
                        log.debug("BLOCKED 自动重派本轮无候选: subTaskId={}", subTaskId);
                    } else if ("manual_intervention".equals(result.reason())) {
                        // 等人工拍板（返工超限 / 降级能力不匹配 / 无候选长等待等），自动链不碰
                        skipManualIntervention++;
                        log.debug("BLOCKED 自动重派跳过（等人工）: subTaskId={}", subTaskId);
                    } else {
                        // backoff（退避窗口未过）/ circuit_open（预算耗尽已转死信）
                        skipGated++;
                        log.debug("BLOCKED 自动重派被重派闸门拦截: subTaskId={}, reason={}, nextAllowed={}",
                                subTaskId, result.reason(), result.nextAllowed());
                    }
                } catch (BizException bizEx) {
                    // 子任务状态被外部改写（典型：刚被人工处置 / 并发路径推进）——视为并发冲突
                    skipStatusChanged++;
                    log.info("BLOCKED 自动重派跳过（状态冲突）: subTaskId={}, reason={}",
                            subTaskId, bizEx.getMessage());
                } catch (Exception e) {
                    failed++;
                    log.error("BLOCKED 自动重派失败: subTaskId={}", subTaskId, e);
                }
            }

            if (redispatch > 0 || failed > 0 || skipStatusChanged > 0 || skipNotReady > 0
                    || skipManualIntervention > 0 || skipGated > 0 || skipNoCandidate > 0) {
                log.info("BLOCKED 自动重派巡检完成: 扫描={}, 重派={}, 跳过（状态冲突）={}, 跳过（依赖未就绪）={}, "
                                + "跳过（人工介入）={}, 跳过（闸门/退避）={}, 跳过（暂无候选）={}, 失败={}",
                        blockedIds.size(), redispatch, skipStatusChanged, skipNotReady,
                        skipManualIntervention, skipGated, skipNoCandidate, failed);
            }

        } catch (Exception e) {
            log.error("SubTaskBlockedAutoRedispatchTask 执行异常", e);
        }
    }
}
