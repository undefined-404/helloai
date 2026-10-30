package com.helloai.core.planner.support;

import com.helloai.common.base.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 需求对话「每会话轮次互斥锁」工具聚焦测试（RC-3）。
 *
 * <p>覆盖四件事：① 抢锁成功进入临界区并在 {@code finally} 释放；② 抢占失败（waitTime=0 拿不到）
 * → 抛 {@link BizException} 可读文案且不进临界区；③ 获取被中断 → 抛 {@link BizException} 并还原中断标志；
 * ④ 释放时的 {@code isHeldByCurrentThread} 防御（租期已到被他人接管时不得误放他人锁）。
 * 多通道共锁的判据由 {@link ConversationRoundLock#key} 的键恒定断言体现（流式/同步轮共用同一前缀）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConversationRoundLocker 会话轮次互斥锁")
class ConversationRoundLockerTest {

    private static final Long CONVERSATION_ID = 2002L;

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private ConversationRoundLocker locker;

    @BeforeEach
    void setUp() {
        locker = new ConversationRoundLocker(redissonClient);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    @DisplayName("抢锁成功：进入临界区执行前后置段，finally 释放锁并解锁")
    void runLocked_executesSectionAndUnlocks() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        AtomicInteger executed = new AtomicInteger(0);

        locker.runLocked(CONVERSATION_ID, () -> {
            executed.incrementAndGet();
            return null;
        });

        assertThat(executed.get()).isEqualTo(1);
        // waitTime=0（抢占失败即拒）+ 显式 leaseTime（禁看门狗，TTL 兜底崩溃残留）
        verify(lock).tryLock(0L, ConversationRoundLock.TTL_SECONDS, TimeUnit.SECONDS);
        verify(lock).unlock();
        // 键与防双审范式同源：完整键 = 共享前缀 + 会话 id（流式/同步轮共用，杜绝两套键）
        verify(redissonClient).getLock("chat:conversation:2002:round");
    }

    @Test
    @DisplayName("抢占失败（同会话另一轮在跑）：抛可读 BizException，不进临界区也不解锁")
    void runLocked_rejectsWhenLockNotAcquired() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);
        AtomicInteger executed = new AtomicInteger(0);

        assertThatThrownBy(() -> locker.runLocked(CONVERSATION_ID, () -> {
            executed.incrementAndGet();
            return null;
        }))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("本会话正在处理上一条消息，请稍候再试");

        assertThat(executed.get()).isZero();
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("临界区抛业务异常：锁仍被 finally 释放（不成对泄漏）")
    void runLocked_releasesLockOnSectionException() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);

        assertThatThrownBy(() -> locker.runLocked(CONVERSATION_ID, () -> {
            throw new IllegalArgumentException("前置段校验失败");
        })).isInstanceOf(IllegalArgumentException.class);

        verify(lock).unlock();
    }

    @Test
    @DisplayName("获取被中断：抛 BizException 并还原中断标志（供上层感知线程被中断）")
    void lock_propagatesInterruption() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any()))
                .thenThrow(new InterruptedException("interrupted"));

        assertThatThrownBy(() -> locker.runLocked(CONVERSATION_ID, () -> null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("获取会话处理锁被中断");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        // 清掉中断标志：Mockito/JUnit 后续调用若在中断态线程上执行，Socket/锁类操作会误抛中断异常
        Thread.interrupted();
    }

    @Test
    @DisplayName("释放防御：租期已到被他人接管（isHeldByCurrentThread=false）→ 不得解锁他人持有锁")
    void unlock_skipsWhenNotHeldByCurrentThread() {
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        locker.unlock(lock);

        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("键唯一事实源：共享前缀恒定，多通道通过同 key 命中同一把锁（§4.3）")
    void conversationLockKey_isStablePrefixSharedAcrossChannels() {
        // 流式轮 / 流式新建 / 同步轮 / 内部 round 出口全部走 key(conversationId) ——
        // 键不随通道变 ⇒ 同会话任意通道并发都撞同一把锁
        assertThat(ConversationRoundLock.key(CONVERSATION_ID))
                .isEqualTo("chat:conversation:2002:round")
                .isEqualTo(ConversationRoundLock.KEY_PREFIX + CONVERSATION_ID + ":round");
    }
}