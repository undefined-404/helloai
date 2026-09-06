package com.helloai.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.helloai.api.dto.PageResult;
import com.helloai.api.dto.browser.BrowserSessionResponse;
import com.helloai.common.base.R;
import com.helloai.common.constant.BrowserSessionStatus;
import com.helloai.core.agent.entity.BrowserSession;
import com.helloai.core.agent.service.BrowserSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Browser 会话展示端点（N-003，C3 配套；/api/admin/* 由 AuthInterceptor 统一鉴权）。
 *
 * <p>纯参数接收 + DTO 装配 + R 封装，无编排（§6.3 红线）；会话为登记/展示语义，
 * 不提供任何反向写 task/sub_task 端点（反锁禁令）。</p>
 */
@RestController
@RequestMapping("/api/admin/browser-sessions")
@RequiredArgsConstructor
public class BrowserSessionController {

    private final BrowserSessionService browserSessionService;

    @GetMapping
    public R<PageResult<BrowserSessionResponse>> page(
            @RequestParam(value = "page", defaultValue = "1") long page,
            @RequestParam(value = "size", defaultValue = "20") long size,
            @RequestParam(value = "agentId", required = false) Long agentId,
            @RequestParam(value = "status", required = false) BrowserSessionStatus status) {
        IPage<BrowserSession> result = browserSessionService.pageSessions(page, size, agentId, status);
        return R.ok(PageResult.of(result, this::toResponse));
    }

    @GetMapping("/{id}")
    public R<BrowserSessionResponse> get(@PathVariable("id") Long id) {
        return R.ok(toResponse(browserSessionService.getSession(id)));
    }

    private BrowserSessionResponse toResponse(BrowserSession s) {
        BrowserSessionResponse resp = new BrowserSessionResponse();
        resp.setId(s.getId());
        resp.setAgentId(s.getAgentId());
        resp.setTaskId(s.getTaskId());
        resp.setStatus(s.getStatus() != null ? s.getStatus().name() : null);
        resp.setCurrentUrl(s.getCurrentUrl());
        resp.setLastScreenshotRef(s.getLastScreenshotRef());
        resp.setBeginTime(s.getBeginTime());
        resp.setCloseTime(s.getCloseTime());
        resp.setCreateTime(s.getCreateTime());
        return resp;
    }
}
