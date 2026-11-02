package com.helloai.core.shared.util;

import cn.dev33.satoken.stp.StpUtil;

/**
 * 当前登录用户 id 解析（PA-1 宽松并入 + PA-2a 异步传播）。
 *
 * <p><b>取值顺序</b>：先 {@link #userContext()}（每请求/每线程显式写入的 ThreadLocal），
 * 回退 {@code Sa-Token}（同步请求线程能取到真实登录用户；异步线程无 Sa-Token 上下文时
 * {@code getLoginIdDefaultNull()} 返回 null）。</p>
 *
 * <p><b>为什么要先查 ThreadLocal</b>：Sa-Token 1.44 的 Context Filter 仅注册 REQUEST，
 * 异步线程（{@code chatStreamExecutor} 等）没有 Sa-Token ThreadLocal。而
 * {@code chatStreamExecutor} 配了 {@code TaskDecorator}：提交时把请求线程的 userId 捕获
 * 写入 {@link UserContext}，执行时在异步线程内恢复——这样流式对话链路（建会话/落消息）
 * 也能取到真实登录用户。UserContext 优先保证这条被装饰的异步链路拿到正确用户。</p>
 *
 * <p><b>宽松语义</b>：两条路径都取不到时返回 null。调用方按「写 owner 时 null = 不归属，
 * 读过滤时 null = 取全部」宽放即可。</p>
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** 当前登录用户 id；未登录 / 异步无上下文线程返回 null。 */
    public static Long userId() {
        Long fromContext = UserContext.userId();
        if (fromContext != null) {
            return fromContext;
        }
        return saTokenUserId();
    }

    private static Long saTokenUserId() {
        try {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId == null) {
                return null;
            }
            return Long.valueOf(loginId.toString());
        } catch (Exception e) {
            return null;
        }
    }
}