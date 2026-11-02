package com.helloai.core.shared.util;

/**
 * 当前请求用户 id 的线程绑定上下文（PA-2a）。
 *
 * <p><b>为什么而有</b>：Sa-Token 1.44 的 Context Filter 仅注册 REQUEST，异步线程无 Sa-Token
 * ThreadLocal；而 {@code chatStreamExecutor} 的流式对话链路需要在异步线程里取登录用户 id。
 * 本项目对「异步丢上下文」的既有解法是 {@code TaskDecorator}（提交时捕获、执行时恢复），
 * 本类即这个恢复动作的载体——不在拦截器里大改，只做一个可复制的 ThreadLocal。</p>
 *
 * <p><b>不使用 {@code InheritableThreadLocal}</b>：线程池复用线程，父子线程继承会残留上一次
 * 任务的用户（跨请求串号）。TaskDecorator 提交时显式 set、finally 里 remove 才是干净路径。</p>
 */
public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    /** 绑定当前用户 id（请求入口 / TaskDecorator 提交时调用）。 */
    public static void set(Long userId) {
        USER_ID.set(userId);
    }

    /** 取当前线程绑定用户 id；未绑定返回 null。 */
    public static Long userId() {
        return USER_ID.get();
    }

    /** 清理绑定（请求结束 / TaskDecorator 任务执行后调用）。 */
    public static void clear() {
        USER_ID.remove();
    }
}