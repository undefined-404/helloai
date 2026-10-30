package com.helloai.core.planner.support;

import com.helloai.common.base.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 需求对话「每会话轮次互斥锁」的获取 / 释放 / 临界区工具（RC-1，2026-10-30）。
 *
 * <p>把同步前置段（读 roundCount → addMessage → setRoundCount(+1) → updateById）串行化，
 * 抢占失败即拒（waitTime=0）并抛 {@link BizException}（RC-2 可读文案）。
 * 锁租期与键见 {@link ConversationRoundLock}。流式与同步两条对话链共用本品，
 * 杜绝每处各写一套 tryLock/unlock 造成漂移。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConversationRoundLocker {

    private final RedissonClient redissonClient;

    /**
     * 抢会话轮次锁：waitTime=0 保持「抢占失败即拒」语义；显式 leaseTime 禁看门狗，TTL 兜底崩溃残留。
     *
     * @throws BizException 抢占失败（同会话另一轮在跑）/ 锁不可用（Redis 异常）/ 获取被中断
     */
    public RLock lock(Long conversationId) {
        RLock lock = redissonClient.getLock(ConversationRoundLock.key(conversationId));
        boolean locked;
        try {
            locked = lock.tryLock(0, ConversationRoundLock.TTL_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("获取会话处理锁被中断，请重试");
        } catch (Exception e) {
            // 锁不可用（Redis 异常）：无法串行化。拒绝而非静默放行——无锁下并发仍会竞写 roundCount/seq。
            log.error("会话处理锁不可用: conversationId={}", conversationId, e);
            throw new BizException("会话处理锁不可用，请稍候再试");
        }
        if (!locked) {
            throw new BizException("本会话正在处理上一条消息，请稍候再试");
        }
        return lock;
    }

    /** 释放锁：isHeldByCurrentThread 防御——租期已到被他人接管时，本线程不得释放他人持有的锁。 */
    public void unlock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /** 按会话持锁执行同步前置段（临界区），结束后自动释放；期间异常同样释放后上抛。 */
    public <T> T runLocked(Long conversationId, Supplier<T> section) {
        RLock lock = lock(conversationId);
        try {
            return section.get();
        } finally {
            unlock(lock);
        }
    }
}