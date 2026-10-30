package com.helloai.core.planner.support;

/**
 * 需求对话「每会话轮次互斥锁」的键与租期常量（RC-1，2026-10-30）。
 *
 * <p>需求对话页放开前端全局 {@code sending} 闸门（自由切换）后，同一会话可能被并发请求
 * 命中同步前置段（读 roundCount → addMessage → setRoundCount(+1) → updateById，非原子）。
 * 用一把握在同步前置段入口的每会话锁串行化该窗口；unlock 后再入异步 LLM 流段（见
 * {@code plan/HelloAI 需求对话自由切换与并发互斥实施计划（2026-10-30）.md} §4.1）。
 * 本类是锁键与租期的<b>唯一事实源</b>：{@code ClarifyStreamServiceImpl}（流式轮/新建）与
 * {@code ClarifyRoundOrchestratorServiceImpl}（同步轮 + 内部 round 出口）共用，
 * 杜绝两处各写一份字符串/秒数导致漂移。</p>
 */
public final class ConversationRoundLock {

    /** 每会话轮次锁键前缀（完整键 = 前缀 + conversationId + 后缀）。 */
    public static final String KEY_PREFIX = "chat:conversation:";

    /** 完整键后缀（分隔 round 语义，避免与前缀内其他键撞）。 */
    private static final String KEY_SUFFIX = ":round";

    /**
     * 锁租期（秒）：同步前置段实测毫秒~亚秒级，普通删除后主动释放；
     * 取 30s 仅为兜底崩溃残留自动释放，显式 leaseTime 禁用看门狗。
     * <b>不得</b>取技能执行那种 600s 长租期——会话轮长流不持锁（§4.1）。
     */
    public static final long TTL_SECONDS = 30L;

    private ConversationRoundLock() {
    }

    /** 拼接某会话的轮次互斥锁完整键。 */
    public static String key(Long conversationId) {
        return KEY_PREFIX + conversationId + KEY_SUFFIX;
    }
}