package com.helloai.core.agent.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 带 LLM 并发限流的 {@link ChatModel} 装饰器（`G8`，2026-10-15）。
 *
 * <p><b>修的缺陷</b>：平台有 {@link LlmCallConcurrencyGuard}（默认 8 许可）用于防上游 429，
 * 但其 javadoc 声称覆盖「对话澄清 / 拆解 / 审查 / <b>执行</b>」，
 * 实测<b>只接在 {@code AgentChatClientServiceImpl.generate/generateStream}</b>
 * （即 chat 路径）—— 而<b>执行链走的是另一条缝</b>：
 * {@code AgentRuntimeContextAssembler} → {@code AgentChatClientService#buildChatModel}
 * 拿到<b>裸 ChatModel</b> → {@code ChatModelToolLoop} 直接 {@code chatModel.call(...)}。</p>
 *
 * <p>后果：{@code maxConcurrentLlmCalls} 对<b>最高频的 LLM 路径</b>完全无效 ——
 * 执行命令队列 {@code concurrency: 5 / max-concurrency: 20}，最多 20 个执行线程，
 * 各自在循环里多次调用模型，可同时打上游且不受任何平台侧约束。
 * 这与同仓 `QA-M9-03` 属同类缺陷：<b>「规则存在 ≠ 全路径强制」</b>，
 * 且本例的 javadoc 还<b>主动声称</b>了覆盖面。</p>
 *
 * <p><b>修法为什么取「装饰器 + 在 {@code buildChatModel} 出口包一层」</b>：</p>
 * <ul>
 *   <li>该出口是执行链的<b>唯一</b>取模型通道（调用方只有
 *       {@code AgentRuntimeContextAssembler}，以及经其注入的
 *       {@code ChatModelToolLoop} / {@code ContextCompactor} / {@code FinalReportFenceHook}）
 *       ⇒ 一处包装即<b>全覆盖</b>，无需逐调用点插入 acquire，避免日后新增调用点再漏；</li>
 *   <li><b>不会与既有闸门重复获取</b>：{@code buildChatModel} 与 chat 路径用的
 *       {@code buildChatClient} 是<b>两条平行缝</b>（前者 {@code providerRegistry.createChatModel}、
 *       后者 {@code createChatClient}），互不经过 —— 若在 {@code generate} 与装饰器上<b>都</b>取许可，
 *       8 许可下会出现「各持 1 个再等第 2 个」的<b>信号量死锁</b>；</li>
 *   <li>语义更准：限流单位由「一次 <i>generate</i> 调用」变为「一次<b>底层模型调用</b>」，
 *       与「限制并发打上游」的本意一致（执行循环每轮各占一次、轮间释放，不累计）。</li>
 * </ul>
 *
 * <p><b>mock 模式</b>：{@code throttled=false} ⇒ 纯委托、不占许可
 * （本地即时返回无上游压力，与 chat 路径的既有判定口径一致）。</p>
 */
@Slf4j
public class ConcurrencyGuardedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final LlmCallConcurrencyGuard guard;
    /** 是否占用许可（真实 Provider 模式 true；mock 模式 false ⇒ 纯委托）。 */
    private final boolean throttled;

    public ConcurrencyGuardedChatModel(ChatModel delegate, LlmCallConcurrencyGuard guard, boolean throttled) {
        this.delegate = delegate;
        this.guard = guard;
        this.throttled = throttled;
    }

    /** 同步调用：占许可 → 调用 → finally 释放（异常路径亦释放）。 */
    @Override
    public ChatResponse call(Prompt prompt) {
        if (!throttled) {
            return delegate.call(prompt);
        }
        guard.acquire();
        try {
            return delegate.call(prompt);
        } finally {
            guard.release();
        }
    }

    /**
     * 流式调用：订阅前占许可，{@code doFinally} 保证完成 / 异常 / 取消三条路径都释放
     * （流式时长不定，不能依赖同步 finally —— 与 {@code AgentChatClientServiceImpl.generateStream}
     * 的既有处理同款）。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (!throttled) {
            return delegate.stream(prompt);
        }
        guard.acquire();
        return delegate.stream(prompt).doFinally(signal -> guard.release());
    }
}
