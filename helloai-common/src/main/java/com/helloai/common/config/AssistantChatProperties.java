package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 个人助理对话配置（PA-11 / N-1，2026-11-03）。
 *
 * <p>前缀 {@code helloai.assistant}。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.assistant")
public class AssistantChatProperties {

    /**
     * 助理对话整轮互斥锁 TTL <b>下限</b>（秒）。默认 <b>240</b>。
     *
     * <p><b>不变量（N-1）</b>：助理轮在同步 LLM 调用期间持锁，锁 TTL 不得短于单次 LLM
     * 的最坏时长（由 provider 读超时给出）。原实现硬编码 180s，而 local/dev 读超时为
     * 600s ⇒ 锁先过期、另一请求可抢占，两轮并发写回。</p>
     *
     * <p><b>本值语义 = 下限</b>：有效 TTL 由 {@code AssistantRoundLockTtlResolver} 计算为
     * {@code max(本值, 各 provider 读超时最大值 + 1s 余量)}；方向唯一（只能调大），
     * 故自动抬高安全，同时 WARN 留痕。</p>
     */
    private long roundLockTtlSeconds = 240;
}