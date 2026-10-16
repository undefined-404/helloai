package com.helloai.core.agent.runtime.cancel;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 可取消的 {@link ChatModel} 装饰器（REF-8.6）。
 *
 * <p><b>为什么用装饰器而不是改循环</b>：取消检查的语义位就是「每次模型调用的边界」，而
 * {@code ChatModel.call(Prompt)} 正是这个边界本身。以装饰器承载 ⇒
 * <b>{@code ChatModelToolLoop} 循环体一行不改</b>（§10.8 红线：不动循环逻辑、只动入参 /
 * 出口），且装配点在 {@code AgentLoopInput} 的紧凑构造器（唯一咽喉 ⇒ 调用方无法绕过）。</p>
 *
 * <p><b>两道检查</b>：调用<b>前</b>（不发起注定被取消的请求）+ 调用<b>后</b>（在途结果不采用）。
 * 二者共同保证「已取消 ⇒ 不会带着新结果进入下一轮」。</p>
 */
public class CancellableChatModel implements ChatModel {

    private final ChatModel delegate;
    private final CancellationToken token;

    public CancellableChatModel(ChatModel delegate, CancellationToken token) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate ChatModel 不可为空");
        }
        this.delegate = delegate;
        this.token = token != null ? token : CancellationToken.none();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        token.throwIfCancelled();
        ChatResponse response = delegate.call(prompt);
        // 在途结果不采用：模型已返回但期间收到取消 ⇒ 不把结果喂回循环（协作式取消的边界）
        token.throwIfCancelled();
        return response;
    }

    /**
     * 流式：订阅时检查一次 + 每个增量帧检查一次（帧粒度取消）。
     *
     * <p>取消以 {@link AgentCancelledException} 经 {@code Flux} 错误通道传播（与
     * {@code reactor.core.Exceptions.propagate} 的运行时异常语义一致）。</p>
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            token.throwIfCancelled();
            return delegate.stream(prompt).doOnNext(response -> token.throwIfCancelled());
        });
    }
}
