package com.helloai.core.agent.session.service.impl;

import com.helloai.common.constant.SessionStatus;
import com.helloai.core.agent.event.AgentEventContextResolver;
import com.helloai.core.agent.session.entity.AgentSession;
import com.helloai.core.agent.session.mapper.AgentSessionMapper;
import com.helloai.core.agent.session.service.AgentSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * {@link AgentSessionService} 实现（Phase 1 Step 3）。
 *
 * <p>写入纪律：全部 best-effort——方法内部 try/catch + 日志含定位字段
 * （subTaskId/turn，§27），失败不阻断执行主链路；start/interrupt 为
 * 读 + 写需原子性，标注事务；advance/complete/fail 为单语句定点更新。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSessionServiceImpl implements AgentSessionService {

    /** error 截断长度（与 agent_execution_record.error_msg 截断口径一致）。 */
    private static final int ERROR_MAX_CHARS = 500;

    private final AgentSessionMapper agentSessionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void start(Long taskId, Long subTaskId, Long agentId, int turn, int step,
                      Map<String, Object> snapshot) {
        try {
            AgentSession existing = agentSessionMapper.selectLatestActiveBySubTaskId(subTaskId);
            if (existing != null && existing.getTurn() != null && existing.getTurn() == turn) {
                // 同 turn 重入：刷新快照与 step，不重复建行
                existing.setStep(step);
                existing.setSnapshot(snapshot != null ? snapshot : Map.of());
                agentSessionMapper.updateById(existing);
                return;
            }
            AgentSession session = new AgentSession();
            session.setRunId(AgentEventContextResolver.resolveRunId(taskId));
            session.setTaskId(taskId);
            session.setSubTaskId(subTaskId);
            session.setAgentId(agentId);
            session.setTurn(turn);
            session.setStep(step);
            session.setStatus(SessionStatus.ACTIVE.name());
            session.setSnapshot(snapshot != null ? snapshot : Map.of());
            agentSessionMapper.insert(session);
        } catch (Exception e) {
            log.warn("AgentSession.start 写入失败（best-effort 不阻断执行链）: subTaskId={}, turn={}, err={}",
                    subTaskId, turn, e.getMessage());
        }
    }

    @Override
    public void advance(Long subTaskId, Long agentId, int turn, int step) {
        try {
            int updated = agentSessionMapper.advanceStep(subTaskId, turn, step);
            if (updated == 0) {
                log.debug("AgentSession.advance 无匹配 ACTIVE 会话，跳过: subTaskId={}, turn={}", subTaskId, turn);
            }
        } catch (Exception e) {
            log.warn("AgentSession.advance 写入失败（best-effort 不阻断执行链）: subTaskId={}, turn={}, err={}",
                    subTaskId, turn, e.getMessage());
        }
    }

    @Override
    public void complete(Long subTaskId, Long agentId, int turn) {
        try {
            agentSessionMapper.completeBySubTaskAndTurn(subTaskId, turn);
        } catch (Exception e) {
            log.warn("AgentSession.complete 写入失败（best-effort 不阻断执行链）: subTaskId={}, turn={}, err={}",
                    subTaskId, turn, e.getMessage());
        }
    }

    @Override
    public void fail(Long subTaskId, Long agentId, int turn, String error) {
        try {
            String truncated = error != null && error.length() > ERROR_MAX_CHARS
                    ? error.substring(0, ERROR_MAX_CHARS) : error;
            agentSessionMapper.failBySubTaskAndTurn(subTaskId, turn, truncated);
        } catch (Exception e) {
            log.warn("AgentSession.fail 写入失败（best-effort 不阻断执行链）: subTaskId={}, turn={}, err={}",
                    subTaskId, turn, e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InterruptedSession interrupt(Long subTaskId) {
        try {
            AgentSession active = agentSessionMapper.selectLatestActiveBySubTaskId(subTaskId);
            if (active == null) {
                return null;
            }
            int aborted = agentSessionMapper.abortActiveBySubTaskId(subTaskId);
            log.info("AgentSession 回收中断: subTaskId={}, sessionId={}, turn={}, step={}, aborted={}",
                    subTaskId, active.getId(), active.getTurn(), active.getStep(), aborted);
            return new InterruptedSession(active.getId(), active.getAgentId(),
                    active.getTurn() != null ? active.getTurn() : 0,
                    active.getStep() != null ? active.getStep() : 0,
                    active.getSnapshot());
        } catch (Exception e) {
            log.warn("AgentSession.interrupt 失败（best-effort）: subTaskId={}, err={}", subTaskId, e.getMessage());
            return null;
        }
    }
}
