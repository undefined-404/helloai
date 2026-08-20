package com.helloai.core.agent.service.impl;

import com.helloai.core.agent.service.CircuitBreakerAlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 熔断报警服务。
 *
 * <p>熔断器打开/关闭时，通过 Webhook 向钉钉/飞书发送报警。
 * 支持按渠道配置独立 URL，未配置的渠道自动跳过。
 * 采用 @Async 异步发送，不阻塞熔断事件处理线程。</p>
 *
 * <p>配置示例（application.yml）：
 * <pre>
 * helloai:
 *   alert:
 *     dingtalk-webhook-url: "https://oapi.dingtalk.com/robot/send?access_token=xxx"
 *     feishu-webhook-url: "https://open.feishu.cn/open-apis/bot/v2/hook/xxx"
 * </pre>
 */
@Slf4j
@Component
public class CircuitBreakerAlertServiceImpl implements CircuitBreakerAlertService {

    private final RestTemplate restTemplate;

    @Value("${helloai.alert.dingtalk-webhook-url:}")
    private String dingtalkUrl;

    @Value("${helloai.alert.feishu-webhook-url:}")
    private String feishuUrl;

    public CircuitBreakerAlertServiceImpl() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 熔断器状态变更时发送报警。
     *
     * @param cbName    熔断器名称
     * @param agentId   关联 Agent ID
     * @param fromState 变更前状态
     * @param toState   变更后状态
     */
    @Async
    public void onCircuitStateChange(String cbName, Long agentId,
                                     String fromState, String toState) {
        String title = buildTitle(cbName, agentId, toState);
        String text = buildText(cbName, agentId, fromState, toState);

        // 钉钉
        if (dingtalkUrl != null && !dingtalkUrl.isBlank()) {
            sendDingTalk(title, text);
        }

        // 飞书
        if (feishuUrl != null && !feishuUrl.isBlank()) {
            sendFeishu(title, text);
        }
    }

    private String buildTitle(String cbName, Long agentId, String toState) {
        String emoji = switch (toState) {
            case "OPEN" -> "🔴";
            case "CLOSED" -> "🟢";
            case "HALF_OPEN" -> "🟡";
            default -> "⚪";
        };
        return String.format("%s [HelloAI] Agent 熔断器状态变更: %s (agentId=%d)",
                emoji, toState, agentId);
    }

    private String buildText(String cbName, Long agentId,
                             String fromState, String toState) {
        return String.format(
                "熔断器: %s\nAgent ID: %d\n状态: %s → %s\n时间: %s",
                cbName, agentId, fromState, toState,
                OffsetDateTime.now().toString());
    }

    // ──── 钉钉 Markdown 消息 ────

    private void sendDingTalk(String title, String text) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msgtype", "markdown");

            Map<String, String> markdown = new LinkedHashMap<>();
            markdown.put("title", title);
            markdown.put("text", "## " + title + "\n\n" + text);
            body.put("markdown", markdown);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(dingtalkUrl, new HttpEntity<>(body, headers), String.class);

            log.info("钉钉报警已发送: title={}", title);
        } catch (Exception e) {
            log.error("钉钉报警发送失败: url={}", dingtalkUrl, e);
        }
    }

    // ──── 飞书 交互式卡片 ────

    private void sendFeishu(String title, String text) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msg_type", "interactive");

            Map<String, Object> card = new LinkedHashMap<>();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("title", Map.of("content", title, "tag", "plain_text"));
            header.put("template", "red");
            card.put("header", header);

            card.put("elements", java.util.List.of(
                    Map.of("tag", "div", "text",
                            Map.of("content", text, "tag", "plain_text"))
            ));
            body.put("card", card);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(feishuUrl, new HttpEntity<>(body, headers), String.class);

            log.info("飞书报警已发送: title={}", title);
        } catch (Exception e) {
            log.error("飞书报警发送失败: url={}", feishuUrl, e);
        }
    }
}
