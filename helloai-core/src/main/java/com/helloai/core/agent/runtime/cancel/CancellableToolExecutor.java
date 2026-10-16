package com.helloai.core.agent.runtime.cancel;

import com.helloai.core.agent.tool.ToolExecutionResult;
import com.helloai.core.agent.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可取消 + 可超时的 {@link ToolExecutor} 装饰器（REF-8.6，对齐 DSH `TOOL_TIMEOUT`）。
 *
 * <p><b>两道机制，语义不同，不要混</b>：</p>
 * <ol>
 *   <li><b>取消</b>（{@link CancellationToken}）：调用前检查，已取消则抛
 *       {@link AgentCancelledException} ⇒ <b>整链终止</b>（本轮不应继续）；</li>
 *   <li><b>单工具超时</b>（{@code timeoutMillis}）：超时<b>不抛异常</b>，而是返回
 *       {@link ToolExecutionResult#failure} —— 结果作为 observation 回喂模型，
 *       <b>交模型决策</b>（换工具 / 换参数 / 收尾），<b>不整链失败</b>。</li>
 * </ol>
 *
 * <p><b>为什么超时要用线程池</b>：{@code ToolExecutor.execute(String, String)} 是阻塞式
 * 无超时能力的接口（其签名由 {@code ChatModelToolLoop} 固定，改签名即动循环 ⇒ 触碰红线）。
 * 故在装饰器内以 {@code Future.get(timeout)} 实现等待上限，超时后
 * {@code future.cancel(true)} 尽力中断。<b>边界（登记不隐藏）</b>：若工具实现不响应中断，
 * 其底层动作可能仍在后台跑完（结果被丢弃）——这是「不给工具加超时」与「改接口签名」之间
 * 唯一的折中；线程池用守护线程 + 有界队列，避免泄漏拖住 JVM 退出。</p>
 */
@Slf4j
public class CancellableToolExecutor implements ToolExecutor {

    /**
     * 工具超时等待池：守护线程、0 核心 / 64 上限、{@code SynchronousQueue}（无排队 ⇒
     * 不会把「排队等待」计入 {@code get} 超时）、满载时 CallerRuns（不回绝调用、
     * 退化为无超时直调，保证「不因装饰器而失败」）。
     */
    private static final ExecutorService TIMEOUT_POOL = new ThreadPoolExecutor(
            0, 64, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            newTimeoutThreadFactory(), new ThreadPoolExecutor.CallerRunsPolicy());

    private final ToolExecutor delegate;
    private final CancellationToken token;
    private final long timeoutMillis;

    /**
     * @param delegate      被装饰的执行器（不可空）
     * @param token         取消令牌（可空 ⇒ 不限）
     * @param timeoutMillis 单工具超时毫秒；{@code <= 0} = 不限（直调，零开销）
     */
    public CancellableToolExecutor(ToolExecutor delegate, CancellationToken token, long timeoutMillis) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate ToolExecutor 不可为空");
        }
        this.delegate = delegate;
        this.token = token != null ? token : CancellationToken.none();
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public ToolExecutionResult execute(String toolName, String argumentsJson) {
        token.throwIfCancelled();
        if (timeoutMillis <= 0) {
            return delegate.execute(toolName, argumentsJson);
        }
        Future<ToolExecutionResult> future =
                TIMEOUT_POOL.submit(() -> delegate.execute(toolName, argumentsJson));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("工具调用超时（交模型决策，不整链失败）: tool={}, timeoutMs={}", toolName, timeoutMillis);
            return ToolExecutionResult.failure(toolName,
                    "tool_timeout: 工具调用超过 " + timeoutMillis + "ms 未返回，已放弃等待；"
                            + "请改用其它手段或基于已有信息收尾");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("interrupted while waiting tool: " + toolName);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof AgentCancelledException cancelled) {
                throw cancelled;   // 工具内部触发的取消：透传，整链终止
            }
            log.warn("工具执行异常（作为失败 observation 回喂模型）: tool={}, err={}", toolName, cause.getMessage());
            return ToolExecutionResult.failure(toolName,
                    cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        }
    }

    private static java.util.concurrent.ThreadFactory newTimeoutThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "agent-tool-timeout-" + seq.incrementAndGet());
            thread.setDaemon(true);   // 守护：不阻止 JVM 退出
            return thread;
        };
    }
}
