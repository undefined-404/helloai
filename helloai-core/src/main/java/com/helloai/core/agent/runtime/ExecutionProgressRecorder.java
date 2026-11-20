package com.helloai.core.agent.runtime;

import com.helloai.common.constant.SubTaskContextKeys;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.service.AgentExecutionRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 执行进展写点（`MA-10` 改动 3）。
 *
 * <p><b>只做一件事</b>：把「这个子任务刚刚动了」写到 {@code agent_execution_record.last_progress_at}，
 * 供 {@code ExecutionCompensationTask} 用「无进展」而不是「总时长」判超时。</p>
 *
 * <h3>为什么需要独立的记录器</h3>
 * 写点分布在两处（循环每轮末的 {@code LoopCheckpointListener} 回调、工具调用边界），
 * 且都要做同一组防护（best-effort、不抛、空 subTaskId 忽略、审批豁免短路）。
 * 各处复制这组判断必然分叉（判据 4「同一事实双写必分叉」）。</p>
 *
 * <h3>审批等待豁免（`MA-9` 复用点）</h3>
 * {@code MA-9} 的同 turn 阻塞等待期间，模型并没有在「推进」，但它在<b>等人</b>而不是卡死。
 * 因此本类在写进展前先读 {@code sub_task.context.pendingApproval}：只要该键存在，
 * 就<b>照常刷新</b>进展时间。这样做的效果是「等待期间进展时间一直在动」⇒ 不会被无进展判据杀掉。
 *
 * <p><b>为什么不直接「跳过不写」</b>：跳过不写会让 {@code last_progress_at} 冻在等待开始前的时刻，
 * 一旦审批拖过阈值仍会被判超时——豁免必须是「持续刷新」而不是「不更新」。这是本节最容易写错的一点。</p>
 */
@Slf4j
@Component
public class ExecutionProgressRecorder {

    private final AgentExecutionRecordService agentExecutionRecordService;
    private final SubTaskQueryPort subTaskQueryPort;

    public ExecutionProgressRecorder(AgentExecutionRecordService agentExecutionRecordService,
                                     SubTaskQueryPort subTaskQueryPort) {
        this.agentExecutionRecordService = agentExecutionRecordService;
        this.subTaskQueryPort = subTaskQueryPort;
    }

    /**
     * 记录一次进展（best-effort，绝不抛出）。
     *
     * @param subTaskId 子任务 ID（{@code null} 直接忽略——业务面循环没有子任务）
     */
    public void touch(Long subTaskId) {
        if (subTaskId == null) {
            return;
        }
        try {
            agentExecutionRecordService.touchProgress(subTaskId, OffsetDateTime.now());
        } catch (Exception e) {
            log.warn("进展记录失败（不影响执行）: subTaskId={}, err={}", subTaskId, e.getMessage());
        }
    }

    /**
     * 记录一次进展，并在审批等待期间显式刷新（`MA-9` 豁免）。
     *
     * <p>与 {@link #touch(Long)} 的差别仅在于「是否检查审批挂起」。两者并存不是冗余：
     * 循环回调与工具边界只需要 {@code touch}（它们本来就在推进），
     * 而「等待中定时续命」需要本方法。</p>
     *
     * @param subTaskId 子任务 ID
     * @return true = 当前处于审批等待（调用方据此决定是否缩短下一次续命间隔）
     */
    public boolean touchWithApprovalAwareness(Long subTaskId) {
        if (subTaskId == null) {
            return false;
        }
        touch(subTaskId);
        try {
            // 复用既有 findById（SubTaskSnapshot 已带 context，见 CODE_STYLE §7.2「跨域取数走服务接口投影」）；
            // 不为「读一个键」新增端口方法——那会让 agent 域对 task 域的取数面持续膨胀。
            SubTaskSnapshot snapshot = subTaskQueryPort.findById(subTaskId);
            Map<String, Object> context = snapshot != null ? snapshot.context() : null;
            return context != null && context.get(SubTaskContextKeys.PENDING_APPROVAL) != null;
        } catch (Exception e) {
            // 读不到 context 不能判成「在等审批」——那会给出一个凭空的豁免
            log.debug("审批挂起判定失败（按未等待处理）: subTaskId={}, err={}", subTaskId, e.getMessage());
            return false;
        }
    }
}
