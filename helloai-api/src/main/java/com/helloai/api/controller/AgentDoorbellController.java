package com.helloai.api.controller;

import com.helloai.core.shared.doorbell.DoorbellService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent 门铃通道 Controller（AgentHub V3 门铃内核 PR-1）。
 *
 * <p>为外部 Agent 提供一条"服务端 → Agent"的单向 SSE 长连接（门铃）：Agent 建连后，
 * 平台侧一旦有新收件箱消息即可秒级响铃唤醒，Agent 收到信号自行走 MCP {@code pullTasks}
 * 取正文，替代 30 秒轮询的感知延迟。详见 {@code doc/HelloAI_门铃通知通道设计.md}。</p>
 *
 * <p>认证由 AuthInterceptor 处理（从 {@code _authId} 获取 agentId），路径落在 {@code /api/**}
 * 覆盖范围内。PR-1 只做连接内核：建连、回推 {@code connected} 握手、断连清理；
 * 响铃来源在 PR-2 由 InboxMessageCreatedEvent 驱动。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/agents/doorbell")
@RequiredArgsConstructor
public class AgentDoorbellController {

    private final DoorbellService doorbellService;

    /**
     * 建立门铃 SSE 长连接。
     *
     * <p>返回的 {@link SseEmitter} 由 Spring MVC 直接作为 {@code text/event-stream} 响应体承载，
     * 连接建立后会立即收到一条 {@code connected} 握手信号。</p>
     *
     * @param agentId 已鉴权的 Agent ID（由 AuthInterceptor 注入）
     * @return SSE 门铃连接
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@RequestAttribute("_authId") Long agentId) {
        log.info("门铃建连请求: agentId={}", agentId);
        return doorbellService.connect(agentId);
    }
}
