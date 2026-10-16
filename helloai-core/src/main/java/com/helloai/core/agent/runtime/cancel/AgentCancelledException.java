package com.helloai.core.agent.runtime.cancel;

/**
 * 取消异常（REF-8.6）：由 {@link CancellationToken#throwIfCancelled()} 抛出，
 * 沿执行链上抛至循环调用方（{@code RuntimeTurnExecutor} / {@code BusinessSurfaceLoopRunner}）
 * 并被映射为带 {@code finishReason=CANCELLED} 的失败结果。
 *
 * <p><b>与普通失败的区别</b>：取消<b>不是</b>「模型 / 工具出错」，而是「本轮不应继续」——
 * 故以独立异常类型表达，便于调用方分别处理（例如：取消不触发「可重试失败」的退避重派语义，
 * 而由外层按取消来源决定后续动作）。</p>
 *
 * <p><b>不继承 {@code BizException}</b>：{@code BizException} 是业务语义异常（会被全局异常处理器
 * 转成对用户的提示）；取消是运行时控制信号，不该走业务异常通道。</p>
 */
public class AgentCancelledException extends RuntimeException {

    public AgentCancelledException(String message) {
        super(message);
    }
}
