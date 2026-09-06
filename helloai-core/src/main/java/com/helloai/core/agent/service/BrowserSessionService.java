package com.helloai.core.agent.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.common.constant.BrowserSessionStatus;
import com.helloai.core.agent.entity.BrowserSession;

/**
 * Browser 会话服务（N-003，C3-S1）。
 *
 * <p>登记/展示语义：begin/markActive/close/markFailed + 分页查询；
 * 会话状态是派生态投影，不反向约束 task/sub_task（反锁禁令）。</p>
 */
public interface BrowserSessionService {

    /** 开始会话（BEGIN）。 */
    BrowserSession beginSession(Long agentId, Long taskId);

    /** 标记活跃（BEGIN/ACTIVE → ACTIVE，刷新 URL/截图引用）。 */
    BrowserSession markActive(Long sessionId, String currentUrl, String lastScreenshotRef);

    /** 正常关闭（→ CLOSED，记录 close_time）。 */
    BrowserSession closeSession(Long sessionId, String currentUrl);

    /** 异常终止（→ FAILED）。 */
    BrowserSession markFailed(Long sessionId);

    /** 分页查询（agentId/status 可选）。 */
    IPage<BrowserSession> pageSessions(long page, long size, Long agentId, BrowserSessionStatus status);

    /** 按 id 查询（不存在抛 BizException）。 */
    BrowserSession getSession(Long id);
}
