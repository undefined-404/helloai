package com.helloai.core.agent.runtime.cancel;

/**
 * 取消信号（REF-8.6，对齐 DSH `AbortSignal` 的 Java 等价物）。
 *
 * <p><b>为什么需要它</b>：REF-8.6 实测确认，HelloAI 的 Agent 循环此前<b>完全没有取消语义</b>——
 * {@code AgentLoopInput} 无任何可取消上下文，{@code ChatModelToolLoop} 与 {@code ToolCallbackToolExecutor}
 * 也不检查任何停止信号 ⇒ 一旦某轮模型调用或某次工具调用卡住，整条执行链只能靠外部的
 * 「超时回收」（事后补救）而非「主动中断」。参照实现 DSH 把取消做成一等公民
 * （`agent.ts:46,50` 的 `AbortController` + 16 处 `signal.throwIfAborted()` + 专测 `tests/cancel.spec.ts`）。</p>
 *
 * <p><b>语义边界（务必读完）</b>：本域是<b>协作式取消</b>——信号只保证「<b>不再发起下一步</b>」
 * 与「<b>不采用在途结果</b>」，<b>不</b>保证能打断一个已经进入 HTTP 客户端的请求：
 * spring-ai 的 {@code ChatModel} 契约没有 signal 通道（参照实现之所以能真打断，是因其 provider
 * 客户端接受 signal）。故检查点落在<b>每次模型调用前后</b>与<b>每次工具调用前</b>
 * （见 {@link CancellableChatModel} / {@link CancellableToolExecutor}），这三处已覆盖循环内
 * 全部动作类型。</p>
 *
 * <p><b>三个供给源</b>：{@link #none()}（不限，默认）、{@link #deadlineAfter(long)}（截止时间，
 * 自驱动的真实武装源）、{@link #mutable()}（外部主动取消，供后续接线：任务取消 / 人工中止）。</p>
 *
 * <p><b>★不得用进程级注册表承载外部取消</b>：若将来接线「按任务 / 子任务取消」，信号源必须是
 * 跨实例一致的载体（Redisson / MQ），<b>不得</b>新增进程级 `static Map` 缓存运行中的 token——
 * 那是已清偿缺陷 `D-2`（MCP 鉴权进程级注册表）的同类（写 A 读 B ⇒ 取消无效），教训见
 * MEMORY「进程级 static 状态」类。</p>
 */
public interface CancellationToken {

    /** 未取消的共享实例（不限；{@link LoopCancellation} 靠身份比较判定「无需装配装饰器」）。 */
    CancellationToken NONE = () -> false;

    /** 是否已请求取消。 */
    boolean isCancelled();

    /** 取消原因（写入异常与日志；默认给一个通用原因）。 */
    default String reason() {
        return "cancellation requested";
    }

    /**
     * 已取消则抛 {@link AgentCancelledException}（对齐 DSH `signal.throwIfAborted()`）。
     *
     * @throws AgentCancelledException 已取消
     */
    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new AgentCancelledException(reason());
        }
    }

    /** 不限（永不取消）。 */
    static CancellationToken none() {
        return NONE;
    }

    /**
     * 截止时间令牌：自创建起 {@code seconds} 秒后自动进入已取消态。
     *
     * @param seconds 秒数；{@code <= 0} = 不限（返回 {@link #none()}）
     */
    static CancellationToken deadlineAfter(long seconds) {
        if (seconds <= 0) {
            return NONE;
        }
        long deadlineNanos = System.nanoTime() + seconds * 1_000_000_000L;
        return new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return System.nanoTime() - deadlineNanos >= 0;
            }

            @Override
            public String reason() {
                return "turn deadline exceeded (" + seconds + "s)";
            }
        };
    }

    /** 外部主动取消令牌（可跨线程调用 {@link Mutable#cancel(String)}）。 */
    static Mutable mutable() {
        return new Mutable();
    }

    /** 可被外部取消的令牌实现（`volatile` 读写，跨线程可见）。 */
    final class Mutable implements CancellationToken {

        private volatile boolean cancelled;
        private volatile String reason = "cancellation requested";

        private Mutable() {
        }

        /** 请求取消（幂等；首次原因保留）。 */
        public void cancel(String reason) {
            if (reason != null && !reason.isBlank()) {
                this.reason = reason;
            }
            this.cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public String reason() {
            return reason;
        }
    }
}
