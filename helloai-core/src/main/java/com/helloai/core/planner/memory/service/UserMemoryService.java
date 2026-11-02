package com.helloai.core.planner.memory.service;

/**
 * 用户级画像 / USER 记忆服务（PA-2c）。
 *
 * <p>从一段 user 消息「每轮异步抽取」画像事实 → 结构化画像表 {@code user_profile} 合并 +
 * 更新 USER 型长期记忆精选子集。双轨落地：{@code user_profile}=结构化画像（规则合并 + 覆盖），
 * {@code long_term_memory(USER)}=精选记忆子集，{@code requirement_message}=真相源只存不查。</p>
 *
 * <p><b>宽松语义</b>：{@code userId} 为 null（外部 Agent / 未登录 / 异步无上下文）时静默跳过，
 * 不落画像——画像必须挂人，挂不上就不写，绝不阻断主链路。</p>
 */
public interface UserMemoryService {

    /**
     * 每轮触发：从 user 消息抽取画像并异步落画像表 + USER 记忆。
     *
     * <p>{@code userId} 为调用方显式传入（在同步链路里 {@code CurrentUser.userId()} 可靠获取），
     * 而非本地再查——异步线程（{@code appEventExecutor}）无 {@link com.helloai.core.shared.util.UserContext}
     * 与 Sa-Token 上下文，传参最稳。</p>
     *
     * @param userId      归属用户 id；null = 跳过（画像必须挂人）
     * @param userMessage 用户消息原文（抽画像事实）
     */
    void triggerFromMessage(Long userId, String userMessage);
}