package com.helloai.core.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.helloai.common.base.BizException;
import com.helloai.common.constant.BrowserSessionStatus;
import com.helloai.core.agent.entity.BrowserSession;
import com.helloai.core.agent.mapper.BrowserSessionMapper;
import com.helloai.core.agent.service.BrowserSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Browser 会话服务实现（N-003，C3-S1）。
 *
 * <p>登记/展示语义：会话状态由执行结果回写（begin → markActive → close/markFailed），
 * 不持有浏览器实例、不反向写 task/sub_task（反锁禁令）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrowserSessionServiceImpl
        extends ServiceImpl<BrowserSessionMapper, BrowserSession>
        implements BrowserSessionService {

    @Transactional(rollbackFor = Exception.class)
    @Override
    public BrowserSession beginSession(Long agentId, Long taskId) {
        if (agentId == null) {
            throw new BizException("Browser 会话 agentId 必填");
        }
        BrowserSession session = new BrowserSession();
        session.setAgentId(agentId);
        session.setTaskId(taskId);
        session.setStatus(BrowserSessionStatus.BEGIN);
        session.setBeginTime(OffsetDateTime.now());
        save(session);
        log.info("Browser 会话开始: id={}, agentId={}, taskId={}", session.getId(), agentId, taskId);
        return session;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public BrowserSession markActive(Long sessionId, String currentUrl, String lastScreenshotRef) {
        BrowserSession session = requireSession(sessionId);
        if (session.getStatus() == BrowserSessionStatus.CLOSED) {
            return session;
        }
        session.setStatus(BrowserSessionStatus.ACTIVE);
        if (currentUrl != null) {
            session.setCurrentUrl(currentUrl);
        }
        if (lastScreenshotRef != null) {
            session.setLastScreenshotRef(lastScreenshotRef);
        }
        updateById(session);
        return session;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public BrowserSession closeSession(Long sessionId, String currentUrl) {
        BrowserSession session = requireSession(sessionId);
        if (session.getStatus() == BrowserSessionStatus.CLOSED) {
            return session;
        }
        session.setStatus(BrowserSessionStatus.CLOSED);
        if (currentUrl != null) {
            session.setCurrentUrl(currentUrl);
        }
        session.setCloseTime(OffsetDateTime.now());
        updateById(session);
        log.info("Browser 会话关闭: id={}", sessionId);
        return session;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public BrowserSession markFailed(Long sessionId) {
        BrowserSession session = requireSession(sessionId);
        if (session.getStatus() == BrowserSessionStatus.CLOSED) {
            return session;
        }
        session.setStatus(BrowserSessionStatus.FAILED);
        session.setCloseTime(OffsetDateTime.now());
        updateById(session);
        log.info("Browser 会话异常终止: id={}", sessionId);
        return session;
    }

    @Override
    public IPage<BrowserSession> pageSessions(long page, long size, Long agentId, BrowserSessionStatus status) {
        LambdaQueryWrapper<BrowserSession> wrapper = new LambdaQueryWrapper<BrowserSession>()
                .eq(agentId != null, BrowserSession::getAgentId, agentId)
                .eq(status != null, BrowserSession::getStatus, status)
                .orderByDesc(BrowserSession::getCreateTime);
        return baseMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @Override
    public BrowserSession getSession(Long id) {
        return requireSession(id);
    }

    private BrowserSession requireSession(Long id) {
        BrowserSession session = getById(id);
        if (session == null) {
            throw new BizException("Browser 会话不存在: id=" + id);
        }
        return session;
    }
}
