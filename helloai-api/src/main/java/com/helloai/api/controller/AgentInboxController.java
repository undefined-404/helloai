package com.helloai.api.controller;

import com.helloai.common.base.R;
import com.helloai.core.entity.AgentInbox;
import com.helloai.core.agent.service.AgentInboxService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent 收件箱 Controller。
 * Agent 通过此 API 查收通知、标记已读、归档。
 * 认证由 AuthInterceptor 处理（从 _authId 获取 agentId）。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/inbox")
@RequiredArgsConstructor
public class AgentInboxController {

    private final AgentInboxService inboxService;

    /**
     * 查收件箱
     */
    @GetMapping
    public R<List<AgentInbox>> list(
            @RequestAttribute("_authId") Long agentId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return R.ok(inboxService.getUnread(agentId, limit));
    }

    /**
     * 未读数量
     */
    @GetMapping("/count")
    public R<Map<String, Object>> count(@RequestAttribute("_authId") Long agentId) {
        return R.ok(Map.of("total_unread", inboxService.countUnread(agentId)));
    }

    /**
     * 标记已读
     */
    @PutMapping("/read/{id}")
    public R<Void> markRead(@RequestAttribute("_authId") Long agentId,
                            @PathVariable("id") Long id) {
        inboxService.markRead(agentId, id);
        return R.ok();
    }

    /**
     * 归档
     */
    @PutMapping("/archive/{id}")
    public R<Void> markArchived(@RequestAttribute("_authId") Long agentId,
                                @PathVariable("id") Long id) {
        inboxService.markArchived(agentId, id);
        return R.ok();
    }
}
