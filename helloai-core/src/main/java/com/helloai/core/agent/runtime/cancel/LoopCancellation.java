package com.helloai.core.agent.runtime.cancel;

/**
 * 一次 Agent Turn 的可取消上下文（REF-8.6）——取消信号 + 单工具超时的**单一入参载体**。
 *
 * <p><b>为什么打包成一个值对象</b>：二者是同一件事的两个面（「本轮还能不能继续」与
 * 「单个动作等多久算卡住」），且必须在**同一处**装配（{@code AgentLoopInput} 的紧凑构造器）
 * 才可能「无法绕过」。拆成两个组件只会让调用方更容易漏配一个。</p>
 *
 * <p><b>{@link #none()} 的身份语义</b>：{@code none()} 返回的实例其 token 恒为
 * {@link CancellationToken#NONE}（共享常量）⇒ 紧凑构造器以<b>身份比较</b>判定
 * 「无需装配装饰器」，保证不限取消时<b>行为与改造前逐字一致</b>（零包装、零开销、零线程）。</p>
 *
 * @param token            取消信号（不可空）
 * @param toolTimeoutMillis 单工具超时毫秒；{@code <= 0} = 不限
 */
public record LoopCancellation(CancellationToken token, long toolTimeoutMillis) {

    /** 共享的「不限」实例（默认值；身份比较用于跳过装饰）。 */
    private static final LoopCancellation NONE = new LoopCancellation(CancellationToken.NONE, 0L);

    public LoopCancellation {
        if (token == null) {
            token = CancellationToken.NONE;
        }
    }

    /** 不限取消、不限工具超时（= 改造前行为）。 */
    public static LoopCancellation none() {
        return NONE;
    }

    /**
     * 按配置装配：截止时间令牌 + 单工具超时。
     *
     * @param deadlineSeconds   本轮截止秒数；{@code <= 0} = 不限（token 退化为 {@code NONE}）
     * @param toolTimeoutMillis 单工具超时毫秒；{@code <= 0} = 不限
     */
    public static LoopCancellation of(long deadlineSeconds, long toolTimeoutMillis) {
        CancellationToken deadline = CancellationToken.deadlineAfter(deadlineSeconds);
        if (deadline == CancellationToken.NONE && toolTimeoutMillis <= 0) {
            return NONE;
        }
        return new LoopCancellation(deadline, toolTimeoutMillis);
    }

    /** 是否需要装配装饰器（token 非「不限」或工具超时开启）。 */
    public boolean active() {
        return token != CancellationToken.NONE || toolTimeoutMillis > 0;
    }
}
