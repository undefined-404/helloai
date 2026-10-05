package com.helloai.core.task.adapter;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.SubTaskStatus;
import com.helloai.core.agent.port.SubTaskCommandPort;

import com.helloai.core.task.service.SubTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * {@link SubTaskCommandPort} 的提供方实现（task 域）。
 *
 * <p>承接原 agent 域 {@code SubTaskExecutionServiceImpl.startIfNeeded} 的<b>判定逻辑</b>
 * （状态机规则归 task 域），并由本域 {@link SubTaskService} 完成实际写入；
 * 事务边界与原实现一致（{@code rollbackFor = Exception.class}，提供方为
 * {@code @Transactional} 传播加入）。实现侧依赖 {@code task → agent.port} 属顺向合法。</p>
 */
@Service
@RequiredArgsConstructor
public class SubTaskCommandPortAdapter implements SubTaskCommandPort {

    private final SubTaskService subTaskService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startIfNeeded(Long subTaskId, SubTaskStatus status) {
        if (status == SubTaskStatus.IN_PROGRESS) {
            return;
        }
        if (status == SubTaskStatus.ASSIGNED || status == SubTaskStatus.REWORK || status == SubTaskStatus.PAUSED) {
            subTaskService.start(subTaskId);
            return;
        }
        throw new BizException("子任务状态不允许执行: subTaskId=" + subTaskId + ", status=" + status);
    }

    @Override
    public void unlinkByAssignedAgent(Long agentId) {
        // 级联删除调用方持有事务，此处不再叠加 @Transactional（与原 SubTaskService 语义一致：
        // SubTaskService.unlinkByAssignedAgent 自身按 REQUIRED 传播）。
        subTaskService.unlinkByAssignedAgent(agentId);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>原子命令、零判定</b>：直接委派 {@code SubTaskService#start}（内部由
     * {@code SubTaskStateMachine} 校验合法性 + {@code @Version} 乐观锁兜并发）。
     * 调用方自带的「状态白名单 + 对外 reason 码」属其协议适配职责，不在此复刻。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void start(Long subTaskId) {
        subTaskService.start(subTaskId);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>原子命令、零判定</b>：直接委派 {@code SubTaskService#submit}
     * （内部 {@code changeStatus(REVIEW)}，状态合法性由 {@code SubTaskStateMachine} 保障）。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long subTaskId) {
        subTaskService.submit(subTaskId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>互斥条件（{@code WHERE status='PENDING' AND (assigned_agent IS NULL OR = agentId)}）
     * 是状态机的一部分、写在 SQL 条件更新里，消费方无从复现，故整体委派。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean claimAtomic(Long subTaskId, Long agentId) {
        return subTaskService.claimAtomic(subTaskId, agentId);
    }

    /**
     * {@inheritDoc}
     *
     * <p>阻塞写入与 PLANNER 通知均在 {@code SubTaskService#block} 内整体完成，
     * 本适配器不加任何判定。调用方（MCP）已持有事务，此处按 REQUIRED 加入。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void block(Long subTaskId, String reason, Long agentId) {
        subTaskService.block(subTaskId, reason, agentId);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>整体不透明</b>：PENDING 前置校验 + Agent 行锁（FOR UPDATE）串行化 +
     * 锁内并发额度判定 + {@code changeStatus(ASSIGNED)} 全部落在
     * {@code SubTaskService#assignNext} 内；本适配器零判定、零额外读写。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignNext(Long agentId, Long subTaskId) {
        subTaskService.assignNext(agentId, subTaskId);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：{@code SubTaskService#markManualIntervention}
     * 按 CODE_STYLE §7.1「单语句原子写豁免」口径自持 try-catch 降级（失败仅告警、不影响主链路）。
     * 若在此叠加事务，会把「best-effort 降级」变成「随调用方事务回滚」，改变既有语义。</p>
     */
    @Override
    public void markManualIntervention(Long subTaskId, String reason, Map<String, Object> extra) {
        subTaskService.markManualIntervention(subTaskId, reason, extra);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>读改写回写整体不透明</b>：消费方只给出目标 {@code context}，本适配器自行
     * {@code getById → setContext → updateById}。取<b>最新行</b>而非消费方快照，
     * 既保留同行互斥（提供方事务内 {@code @Version} CAS 仍生效），又避免消费方携带的
     * 陈旧 {@code version} 让 {@code context} 写入静默丢失。子任务不存在时静默返回，
     * 与原 {@code updateById} 更新 0 行同样不抛错。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateContext(Long subTaskId, Map<String, Object> context) {
        subTaskService.updateContext(subTaskId, context);
    }
}
