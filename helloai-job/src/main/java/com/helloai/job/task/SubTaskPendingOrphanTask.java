package com.helloai.job.task;

import com.helloai.common.base.BizException;
import com.helloai.common.base.NoCandidateAgentException;
import com.helloai.common.config.AgentDispatchProperties;
import com.helloai.common.config.AgentExecutionProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.task.entity.SubTask;
import com.helloai.core.task.service.SubTaskDispatchService;
import com.helloai.core.task.service.SubTaskService;
import com.helloai.core.task.service.TaskTimelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PENDING 孤儿子任务巡检任务。
 *
 * <p>填补可靠性缺口：dispatch-mode=EVENT 主路径上 Spring 事务事件丢失、
 * {@code agent_execution_record} 行未被创建、但 {@code sub_task} 一直停在 PENDING。
 * 现有 {@link ExecutionCommandPoller} 扫描的是 {@code status='PENDING' AND
 * status='PENDING' AND last_attempt_at < cutoff} —— 针对的"已有 execution_record
 * 但积压未消费"的孤儿，覆盖不到"压根没建 record"的情况。本任务补上这个间隙。</p>
 *
 * <h3>职责</h3>
 * <ul>
 *     <li>每 60s 扫一次：{@code status='PENDING' AND create_time &lt; now - 5min
 *         AND NOT EXISTS agent_execution_record}（阈值 30min → 5min）</li>
 *     <li>逐条重新触发 {@link SubTaskDispatchService#dispatchPendingSubTaskAuto}：
 *         PENDING 状态的子任务自动按角色选人并进入弹性调度链</li>
 *     <li>ShedLock 实例级互斥（@SchedulerLock，Redis 存储锁记录）保证多实例串行，单批上限 50 条防阻塞</li>
 *     <li>覆盖两种"无人接管"场景：① 主路径事件丢失（原始设计目标）；
 *         ② 依赖解锁瞬间无空闲候选导致分发失败（收窄阈值后 5 分钟内自动重试）</li>
 * </ul>
 *
 * <h3>与现有任务边界</h3>
 * <ul>
 *     <li>{@link AssignedSubTaskTimeoutTask}：处理 ASSIGNED 超时未 claim</li>
 *     <li>{@link ExecutionCompensationTask}：处理已有 execution_record 但 PENDING/RUNNING
 *         超时</li>
 *     <li>{@code SubTaskPendingOrphanTask（本类）}：处理无 execution_record 的 PENDING 孤儿</li>
 *     <li>三条职责互不重叠，各管一片</li>
 * </ul>
 *
 * <h3>幂等保护</h3>
 * <ol>
 *     <li>每条孤儿重新触发调度时先按 id 查最新状态，避免回读到陈旧 PENDING 状态</li>
 *     <li>{@code dispatchPendingSubTaskAuto} 本身要求子任务当前为 PENDING 状态，
 *         若已被外部 Agent claim 或被其它路径推进到非 PENDING，会抛 BizException；
 *         catch 后仅记录日志，不影响同轮其他记录</li>
 *     <li><b>「暂无可用候选」是可自愈等待态（2026-10-05 修 P1-1）</b>：
 *         {@code dispatchPendingSubTaskAuto} 选不出人时抛 {@link NoCandidateAgentException}
 *         （不消耗重派预算），本类按异常类型<b>单独分流</b>并落
 *         {@code sub_task_no_candidate} 时间线，不再被静默归入 skipStatusChanged；
 *         下一轮巡检（60s）候选恢复后自动重试。返回值 {@code null}（闸门拦截 / 退避 /
 *         依赖未就绪）计入 skipGated，不与「已重派」混为一谈。</li>
 *     <li><b>时钟 C · 每子任务重试节拍（2026-10-05）</b>：连续无候选时按
 *         {@code no-candidate-retry-interval-seconds} + 抖动 写回
 *         {@code context.noCandidate.nextDispatchAt}，未到点则 {@code skipWaiting} 跳过
 *         （不再每 60s 空扫）；达 {@code no-candidate-max-rounds} 轮转人工介入
 *         （{@code no_candidate_long_wait}）。选中执行者即清除节拍、计数归零。</li>
 * </ol>
 *
 * <h3>配置项</h3>
 * <ul>
 *     <li>{@code helloai.execution.pending-orphan-enabled}（默认 true）</li>
 *     <li>{@code helloai.execution.pending-orphan-scan-interval-ms}（默认 60000，时钟 A）</li>
 *     <li>{@code helloai.execution.pending-orphan-threshold-minutes}（默认 5）</li>
 *     <li>{@code helloai.execution.pending-orphan-batch-size}（默认 50）</li>
 *     <li>{@code helloai.dispatch.no-candidate-max-rounds}（默认 10，时钟 C）</li>
 *     <li>{@code helloai.dispatch.no-candidate-retry-interval-seconds}（默认 120，时钟 C）</li>
 *     <li>{@code helloai.dispatch.no-candidate-retry-jitter-seconds}（默认 15，时钟 C）</li>
 * </ul>
 *
 * @see AssignedSubTaskTimeoutTask
 * @see ExecutionCompensationTask
 * @see ExecutionCommandPoller
 * @see SubTaskDispatchService#dispatchPendingSubTaskAuto
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubTaskPendingOrphanTask {

    private final SubTaskService subTaskService;
    private final SubTaskDispatchService subTaskDispatchService;
    private final AgentExecutionProperties executionProperties;
    private final TaskTimelineService taskTimelineService;
    private final AgentDispatchProperties agentDispatchProperties;

    /**
     * 周期扫描入口。
     *
     * <p>{@code fixedDelayString} 直接绑定配置项，支持通过 yaml / 环境变量动态调整。
     * 默认 60 秒一跑；每条孤儿由 {@link SubTaskDispatchService#dispatchPendingSubTaskAuto}
     * 统一进入选人 → ASSIGNED 链。</p>
     */
    @Scheduled(fixedDelayString = "${helloai.execution.pending-orphan-scan-interval-ms:60000}")
    @SchedulerLock(name = "subTaskPendingOrphan", lockAtMostFor = "PT60S")
    public void scan() {
        if (!executionProperties.isPendingOrphanEnabled()) {
            return;
        }

        try {
            int thresholdMinutes = executionProperties.getPendingOrphanThresholdMinutes();
            int batchSize = executionProperties.getPendingOrphanBatchSize();
            OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(thresholdMinutes);

            List<Long> orphanIds = subTaskService.listStalePendingWithoutExecutionRecord(cutoff, batchSize);

            if (orphanIds.isEmpty()) {
                return;
            }

            log.warn("PENDING 孤儿巡检: 发现 {} 个无 execution_record 的 PENDING 子任务 (threshold={}min)",
                    orphanIds.size(), thresholdMinutes);

            int recovered = 0;
            int failed = 0;
            int skipStatusChanged = 0;
            int skipNotReady = 0;
            int skipManualIntervention = 0;
            int skipGated = 0;
            int skipNoCandidate = 0;
            int skipWaiting = 0;
            int escalated = 0;

            for (Long subTaskId : orphanIds) {
                try {
                    // 防御性回读：避免回读到陈旧 PENDING 状态（某条可能刚被其它路径推进）
                    SubTask latest = subTaskService.getById(subTaskId);
                    if (latest == null) {
                        log.debug("跳过：子任务不存在: subTaskId={}", subTaskId);
                        continue;
                    }
                    if (latest.getStatus() != com.helloai.common.constant.SubTaskStatus.PENDING) {
                        // 已被其它路径推进（claim/submit/block），自然跳过
                        skipStatusChanged++;
                        log.debug("跳过：状态已变更: subTaskId={}, currentStatus={}",
                                subTaskId, latest.getStatus());
                        continue;
                    }
                    // 依赖未就绪的节点不触发分发（保持 PENDING 等上游 DONE 后解锁），
                    // 避免孤儿扫描误伤依赖编排中的合法阻塞节点
                    if (!subTaskService.isReady(latest)) {
                        skipNotReady++;
                        log.debug("跳过：依赖未就绪: subTaskId={}, dependsOn={}",
                                subTaskId, latest.dependsOnIdList());
                        continue;
                    }
                    // 有人工介入标记的 PENDING 不自动重派（等人工处置），
                    // 避免兜底巡检把"无能力/返工超限"等人工场景反复打回调度链
                    if (SubTaskDispatchService.isManualInterventionMarked(latest)) {
                        skipManualIntervention++;
                        log.debug("跳过：已标记人工介入: subTaskId={}", subTaskId);
                        continue;
                    }

                    // // 时钟 C（2026-10-05）：每子任务重试节拍 —— 上一轮「无候选」写入的
                    // nextDispatchAt 未到之前，不重试、不计数、不落事件（避免 60s 空扫噪音）。
                    // 该门控必须在 dispatchPendingSubTaskAuto 之前，否则每次 tick 都会再试一次。
                    OffsetDateTime nextDispatchAt = readNextDispatchAt(latest);
                    if (nextDispatchAt != null && OffsetDateTime.now().isBefore(nextDispatchAt)) {
                        skipWaiting++;
                        log.debug("跳过：无候选重试节拍未到: subTaskId={}, nextDispatchAt={}", subTaskId, nextDispatchAt);
                        continue;
                    }

                    // PENDING 孤儿重派：默认按 EXECUTOR 角色选人
                    // —— 因为 PENDING 子任务通常还未指定角色，Module.role 在更上层传入；
                    // 本任务作为兜底路径，统一按 EXECUTOR 处理最常见场景
                    Long dispatchedAgentId = subTaskDispatchService.dispatchPendingSubTaskAuto(subTaskId, AgentRole.EXECUTOR);
                    if (dispatchedAgentId != null) {
                        recovered++;
                        log.info("PENDING 孤儿已重派: subTaskId={}, agentId={}", subTaskId, dispatchedAgentId);
                        // 时钟 C 复位：选中人即计数归零，清除 noCandidate 节拍（下次若再失败重新起算）
                        clearNoCandidateContext(latest);
                    } else {
                        // 闸门拦截 / 退避窗口 / 依赖未就绪 —— 本轮未派出，不算重派（P1-1 修误报）
                        skipGated++;
                        log.debug("PENDING 孤儿本轮未派出（闸门拦截/退避/依赖未就绪）: subTaskId={}", subTaskId);
                    }
                } catch (NoCandidateAgentException noCand) {
                    // 「暂无可用执行者」是可自愈的等待态：不消耗重派预算、不误判死信；
                    // 落 timeline 让用户可见（不再静默），并按时钟 C 安排下一次重试。
                    skipNoCandidate++;
                    String reason = noCand.getMessage() != null ? noCand.getMessage() : "no_available_candidate";
                    log.warn("PENDING 孤儿暂无可用执行者（等待自动重试）: subTaskId={}, reason={}", subTaskId, reason);
                    try {
                        // latest 在 try 内声明、catch 不可见，故 taskId 传 null
                        //（recordEvent 允许 taskId 为空，属系统级等待态事件；前端按 subTaskId 可查）
                        taskTimelineService.recordEvent(null, subTaskId, "sub_task_no_candidate",
                                AgentRole.SYSTEM, null,
                                Map.of("reason", reason, "waitFor", "pending_orphan_scan"));
                    } catch (Exception te) {
                        log.debug("记录 sub_task_no_candidate 事件失败: subTaskId={}, err={}", subTaskId, te.getMessage());
                    }
                    // 时钟 C 计数推进：rounds+1；达阈值转人工介入，否则写回 nextDispatchAt。
                    // catch 内重新 getById（`latest` 作用域不可见；且需最新 context 防丢并发写）。
                    if (accumulateNoCandidateOrEscalate(subTaskId, reason)) {
                        escalated++;
                    }
                } catch (BizException bizEx) {
                    // 子任务状态被外部改写（典型 case：刚被另外路径 claim / block / cancel）
                    // —— 视为并发冲突，skip 而不是 fail
                    skipStatusChanged++;
                    log.info("PENDING 孤儿跳过（状态冲突）: subTaskId={}, reason={}",
                            subTaskId, bizEx.getMessage());
                } catch (Exception e) {
                    failed++;
                    log.error("PENDING 孤儿重派失败: subTaskId={}", subTaskId, e);
                }
            }

            if (recovered > 0 || failed > 0 || skipStatusChanged > 0 || skipNotReady > 0
                    || skipManualIntervention > 0 || skipGated > 0 || skipNoCandidate > 0
                    || skipWaiting > 0 || escalated > 0) {
                log.info("PENDING 孤儿巡检完成: 扫描={}, 重派={}, 跳过（状态冲突）={}, 跳过（依赖未就绪）={}, 跳过（人工介入）={}, 跳过（闸门/退避）={}, 跳过（暂无候选）={}, 跳过（节拍未到）={}, 转人工={}, 失败={}",
                        orphanIds.size(), recovered, skipStatusChanged, skipNotReady, skipManualIntervention,
                        skipGated, skipNoCandidate, skipWaiting, escalated, failed);
            }

        } catch (Exception e) {
            log.error("SubTaskPendingOrphanTask 执行异常", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  时钟 C（2026-10-05）：每子任务无候选重试节拍
    //  与时钟 A（孤儿扫描 60s tick）/ 时钟 B（重派退避）正交，独立于 dispatch 本体
    // ═══════════════════════════════════════════════════════════════

    /** 读 {@code context.noCandidate.nextDispatchAt}（缺失/非法返回 null ⇒ 视为无节拍、放行重试）。 */
    @SuppressWarnings("unchecked")
    private static OffsetDateTime readNextDispatchAt(SubTask subTask) {
        Map<String, Object> ctx = subTask.getContext();
        if (ctx == null) {
            return null;
        }
        Object noCandidate = ctx.get("noCandidate");
        if (!(noCandidate instanceof Map)) {
            return null;
        }
        Object raw = ((Map<String, Object>) noCandidate).get("nextDispatchAt");
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 时钟 C 复位：清除 {@code context.noCandidate} 计数器（选中执行者成功时 / 达阈值转人工时）。
     * {@code updateContext} 为整体覆盖 —— 先复制旧 map、移除 noCandidate 键、再整体写回，保留其它 key
     *（含 {@code manualIntervention} 等）。
     *
     * @param subTask 必须是最新回读的实体（含刚写入的 manualIntervention），否则整体覆盖会抹掉它
     */
    private void clearNoCandidateContext(SubTask subTask) {
        if (subTask == null) {
            return;
        }
        Map<String, Object> ctx = subTask.getContext();
        if (ctx == null || !ctx.containsKey("noCandidate")) {
            return; // 无节拍可清，避免无谓写
        }
        Map<String, Object> copy = new HashMap<>(ctx);
        copy.remove("noCandidate");
        try {
            subTaskService.updateContext(subTask.getId(), copy);
        } catch (Exception e) {
            log.debug("清除无候选节拍失败（不影响主链路）: subTaskId={}, err={}", subTask.getId(), e.getMessage());
        }
    }

    /**
     * 时钟 C 计数推进：连续无候选 {@code rounds + 1}。
     *
     * <p>达 {@link AgentDispatchProperties#getNoCandidateMaxRounds} 阈值 ⇒
     * {@code markManualIntervention("no_candidate_long_wait")}（该 API 内部已落
     * {@code sub_task_manual_intervention_required} 事件），此后不再写 {@code nextDispatchAt}，
     * 后续扫描由 manualIntervention 分支自然跳过；未达阈值 ⇒ 写回
     * {@code context.noCandidate = {rounds, nextDispatchAt, reason, updatedAt}}，
     * 安排下一次重试时刻（{@code now + interval + rand[0, jitter]}）。</p>
     *
     * <p>catch 内重新 {@code getById} 取最新 context（防丢并发写）；{@code updateContext}
     * 为整体覆盖，故先复制旧 map 再 put。整段 try-catch 降级，失败不影响主链路。</p>
     *
     * @return true=本轮达阈值已转人工介入；false=仅累加并安排下次重试（或写失败降级）
     */
    @SuppressWarnings("unchecked")
    private boolean accumulateNoCandidateOrEscalate(Long subTaskId, String reason) {
        try {
            SubTask fresh = subTaskService.getById(subTaskId);
            if (fresh == null) {
                return false;
            }
            Map<String, Object> oldCtx = fresh.getContext() != null ? fresh.getContext() : Map.of();
            Map<String, Object> noCandidate = oldCtx.get("noCandidate") instanceof Map
                    ? new HashMap<>((Map<String, Object>) oldCtx.get("noCandidate"))
                    : new HashMap<>();

            int rounds = 1;
            Object prev = noCandidate.get("rounds");
            if (prev instanceof Number n) {
                rounds = n.intValue() + 1;
            } else if (prev != null) {
                try {
                    rounds = Integer.parseInt(prev.toString()) + 1;
                } catch (NumberFormatException ignored) {
                    rounds = 1;
                }
            }

            OffsetDateTime now = OffsetDateTime.now();
            int maxRounds = agentDispatchProperties.getNoCandidateMaxRounds();
            if (maxRounds > 0 && rounds >= maxRounds) {
                long waitedMs = resolveWaitedMs(noCandidate, rounds, now);
                subTaskService.markManualIntervention(subTaskId, "no_candidate_long_wait",
                        Map.of("rounds", rounds, "waitedMs", waitedMs));
                // 转人工 = 交棒给人的终态动作：一并清除残留的 noCandidate 计数器（2026-10-05 修 P3）。
                // 否则人工日后清除标记重开（如死信人工重派 SubTaskDispatchServiceImpl 会
                // remove("manualIntervention")）时，残留 rounds（如 9）会接着累加 ⇒ 下一轮无候选
                // 立刻又达阈值（「复活即再死」），且 waitedMs 会用旧 firstSeenAt 算出偏大错误值。
                // 清除后重开可得到一个全新的完整等待窗口，与死信重派重置计数的既有范式一致。
                // ⚠️ 必须重读最新 context 再整体覆盖：markManualIntervention 已自行写库（含
                // manualIntervention），若沿用 mark 之前的旧 map 回写会把刚写入的标记抹掉。
                clearNoCandidateContext(subTaskService.getById(subTaskId));
                log.warn("PENDING 孤儿连续 {} 轮无候选，转人工介入并清除计数器: subTaskId={}, waitedMs={}",
                        rounds, subTaskId, waitedMs);
                return true;
            }

            int interval = agentDispatchProperties.getNoCandidateRetryIntervalSeconds();
            int jitter = agentDispatchProperties.getNoCandidateRetryJitterSeconds();
            int delay = interval + (jitter > 0 ? ThreadLocalRandom.current().nextInt(jitter + 1) : 0);
            noCandidate.put("rounds", rounds);
            noCandidate.put("nextDispatchAt", now.plusSeconds(delay).toString());
            noCandidate.put("reason", reason);
            noCandidate.putIfAbsent("firstSeenAt", now.toString());
            noCandidate.put("updatedAt", now.toString());

            Map<String, Object> newCtx = new HashMap<>(oldCtx);
            newCtx.put("noCandidate", noCandidate);
            subTaskService.updateContext(subTaskId, newCtx);
            log.info("PENDING 孤儿无候选第 {} 轮，{}s 后重试: subTaskId={}", rounds, delay, subTaskId);
            return false;
        } catch (Exception e) {
            log.debug("推进无候选重试节拍失败（不影响主链路）: subTaskId={}, err={}", subTaskId, e.getMessage());
            return false;
        }
    }

    /** 估算已等待时长：优先用 {@code firstSeenAt} 起算，缺失则按 {@code rounds × interval} 兜底。 */
    private long resolveWaitedMs(Map<String, Object> noCandidate, int rounds, OffsetDateTime now) {
        Object firstSeen = noCandidate.get("firstSeenAt");
        if (firstSeen != null) {
            try {
                return Math.max(0L, java.time.Duration.between(OffsetDateTime.parse(firstSeen.toString()), now).toMillis());
            } catch (Exception ignored) {
                // 落到兜底
            }
        }
        return (long) rounds * Math.max(0, agentDispatchProperties.getNoCandidateRetryIntervalSeconds()) * 1000L;
    }

}
