package com.helloai.core.agent.runtime.sandbox.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SandboxSlotPool} 单测（`ADR-005`，2026-10-25）。
 *
 * <p><b>为什么单独测这一层</b>：真正建/销毁沙箱要连 OpenSandbox server（属探针范畴），
 * 而复用语义的<b>决策逻辑</b>（复用 / 空闲过期 / 显式移除 / 创建失败不登记 / 销毁回调）
 * 与之无关——把决策抽到泛型池后用 {@code String} 当资源即可确定性覆盖，
 * 避免「只能靠真机验证复用是否生效」。</p>
 */
@DisplayName("SandboxSlotPool")
class SandboxSlotPoolTest {

    @Test
    @DisplayName("同一 key 复用同一资源：只创建一次、不销毁")
    void shouldReuseSameResourceForSameKey() {
        SandboxSlotPool<Long, String> pool = new SandboxSlotPool<>();
        AtomicInteger created = new AtomicInteger();
        List<String> destroyed = new ArrayList<>();

        SandboxSlotPool.Slot<Long, String> first = pool.acquire(7L, 60_000L, () -> "sb-" + created.incrementAndGet(),
                destroyed::add);
        SandboxSlotPool.Slot<Long, String> second = pool.acquire(7L, 60_000L, () -> "sb-" + created.incrementAndGet(),
                destroyed::add);

        assertThat(first).isNotNull();
        assertThat(second).isSameAs(first);
        assertThat(first.resource()).isEqualTo("sb-1");
        assertThat(created.get()).isEqualTo(1);
        assertThat(destroyed).isEmpty();
        assertThat(pool.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 key 各自独立：不共享工作区")
    void shouldNotShareAcrossKeys() {
        SandboxSlotPool<Long, String> pool = new SandboxSlotPool<>();
        AtomicInteger created = new AtomicInteger();

        SandboxSlotPool.Slot<Long, String> a = pool.acquire(1L, 60_000L, () -> "sb-" + created.incrementAndGet(), s -> { });
        SandboxSlotPool.Slot<Long, String> b = pool.acquire(2L, 60_000L, () -> "sb-" + created.incrementAndGet(), s -> { });

        assertThat(a.resource()).isEqualTo("sb-1");
        assertThat(b.resource()).isEqualTo("sb-2");
        assertThat(created.get()).isEqualTo(2);
        assertThat(pool.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("空闲过期 ⇒ 先销毁旧资源再重建")
    void shouldEvictIdleSlotAndRecreate() {
        SandboxSlotPool<Long, String> pool = new SandboxSlotPool<>();
        AtomicInteger created = new AtomicInteger();
        List<String> destroyed = new ArrayList<>();

        SandboxSlotPool.Slot<Long, String> first = pool.acquire(9L, 1L, () -> "sb-" + created.incrementAndGet(),
                destroyed::add);
        first.touch();
        // idleTtl=1ms：等到判定必然过期（用 0 表示不过期，故这里等 5ms 即可）
        sleep(5);
        SandboxSlotPool.Slot<Long, String> second = pool.acquire(9L, 1L, () -> "sb-" + created.incrementAndGet(),
                destroyed::add);

        assertThat(second).isNotSameAs(first);
        assertThat(second.resource()).isEqualTo("sb-2");
        assertThat(destroyed).containsExactly("sb-1");
        assertThat(pool.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("idleTtl<=0 ⇒ 不做过期判定（恒复用）")
    void shouldNeverEvictWhenTtlNonPositive() {
        SandboxSlotPool<Long, String> pool = new SandboxSlotPool<>();
        AtomicInteger created = new AtomicInteger();

        pool.acquire(3L, 0L, () -> "sb-" + created.incrementAndGet(), s -> { });
        sleep(5);
        SandboxSlotPool.Slot<Long, String> again = pool.acquire(3L, 0L, () -> "sb-" + created.incrementAndGet(), s -> { });

        assertThat(again.resource()).isEqualTo("sb-1");
        assertThat(created.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("remove ⇒ 调用销毁回调并清空登记（幂等）")
    void shouldDestroyOnRemove() {
        SandboxSlotPool<Long, String> pool = new SandboxSlotPool<>();
        List<String> destroyed = new ArrayList<>();
        pool.acquire(5L, 60_000L, () -> "sb", destroyed::add);

        assertThat(pool.remove(5L, destroyed::add)).isTrue();
        assertThat(destroyed).containsExactly("sb");
        assertThat(pool.size()).isZero();
        // 幂等：再移除不再触发销毁
        assertThat(pool.remove(5L, destroyed::add)).isFalse();
        assertThat(destroyed).containsExactly("sb");
    }

    @Test
    @DisplayName("创建失败（opener 返回 null）⇒ 不登记、返回 null，且下次仍会重试创建")
    void shouldNotRegisterWhenOpenerReturnsNull() {
        SandboxSlotPool<Long, String> pool = new SandboxSlotPool<>();
        AtomicInteger attempts = new AtomicInteger();

        SandboxSlotPool.Slot<Long, String> failed = pool.acquire(4L, 60_000L, () -> {
            attempts.incrementAndGet();
            return null;
        }, s -> { });

        assertThat(failed).isNull();
        assertThat(pool.size()).isZero();

        SandboxSlotPool.Slot<Long, String> retried = pool.acquire(4L, 60_000L, () -> {
            attempts.incrementAndGet();
            return "sb-ok";
        }, s -> { });

        assertThat(retried).isNotNull();
        assertThat(retried.resource()).isEqualTo("sb-ok");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("null key ⇒ 返回 null（调用方走一次性路径，不登记）")
    void shouldReturnNullForNullKey() {
        SandboxSlotPool<Long, String> pool = new SandboxSlotPool<>();
        assertThat(pool.acquire(null, 60_000L, () -> "sb", s -> { })).isNull();
        assertThat(pool.remove(null, s -> { })).isFalse();
        assertThat(pool.size()).isZero();
    }

    @Test
    @DisplayName("槽带串行锁：同一槽的命令可被 lock 串行化")
    void slotShouldExposeLock() {
        SandboxSlotPool<Long, String> pool = new SandboxSlotPool<>();
        SandboxSlotPool.Slot<Long, String> slot = pool.acquire(8L, 60_000L, () -> "sb", s -> { });

        assertThat(slot.lock()).isNotNull();
        slot.lock().lock();
        try {
            assertThat(slot.lock().isHeldByCurrentThread()).isTrue();
        } finally {
            slot.lock().unlock();
        }
    }

    @Test
    @DisplayName("复合键：同子任务不同规格指纹 ⇒ 两个沙箱，互不复用（SKX-5）")
    void shouldNotShareAcrossSpecFingerprints() {
        SandboxSlotPool<String, String> pool = new SandboxSlotPool<>();
        AtomicInteger created = new AtomicInteger();

        SandboxSlotPool.Slot<String, String> pythonSlot =
                pool.acquire("1|aaaa", 60_000L, () -> "sb-" + created.incrementAndGet(), s -> { });
        SandboxSlotPool.Slot<String, String> nodeSlot =
                pool.acquire("1|bbbb", 60_000L, () -> "sb-" + created.incrementAndGet(), s -> { });

        assertThat(pythonSlot).isNotSameAs(nodeSlot);
        assertThat(pythonSlot.resource()).isEqualTo("sb-1");
        assertThat(nodeSlot.resource()).isEqualTo("sb-2");
        assertThat(created.get()).isEqualTo(2);
        assertThat(pool.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("removeMatching：释放该子任务全部规格槽，不误伤别的子任务（SKX-5）")
    void shouldRemoveAllSlotsOfSubTask() {
        SandboxSlotPool<String, String> pool = new SandboxSlotPool<>();
        List<String> destroyed = new ArrayList<>();
        pool.acquire("1|aaaa", 60_000L, () -> "py", destroyed::add);
        pool.acquire("1|bbbb", 60_000L, () -> "node", destroyed::add);
        pool.acquire("2|aaaa", 60_000L, () -> "other", destroyed::add);

        int removed = pool.removeMatching(key -> key.startsWith("1|"), destroyed::add);

        assertThat(removed).isEqualTo(2);
        assertThat(destroyed).containsExactlyInAnyOrder("py", "node");
        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.contains("2|aaaa")).isTrue();
    }

    @Test
    @DisplayName("findKey / containsMatching：只按谓词探测在册槽，不创建新沙箱（SKX-5）")
    void shouldFindKeyWithoutCreating() {
        SandboxSlotPool<String, String> pool = new SandboxSlotPool<>();
        AtomicInteger created = new AtomicInteger();
        pool.acquire("7|aaaa", 60_000L, () -> "sb-" + created.incrementAndGet(), s -> { });

        assertThat(pool.containsMatching(key -> key.startsWith("7|"))).isTrue();
        assertThat(pool.findKey(key -> key.startsWith("7|"))).contains("7|aaaa");
        assertThat(pool.containsMatching(key -> key.startsWith("8|"))).isFalse();
        assertThat(pool.findKey(key -> key.startsWith("8|"))).isEmpty();
        assertThat(created.get()).isEqualTo(1);
    }

    // ===== SL-2（2026-11-12）：任务终态回收走「带 tryLock 的批量移除」 =====

    @Test
    @DisplayName("removeMatchingIfIdle：空闲槽全部释放，不匹配的键保留")
    void shouldReleaseAllIdleMatchingSlots() {
        SandboxSlotPool<String, String> pool = new SandboxSlotPool<>();
        List<String> destroyed = new ArrayList<>();
        pool.acquire("task:1|python", 60_000L, () -> "py", destroyed::add);
        pool.acquire("task:1|node", 60_000L, () -> "node", destroyed::add);
        pool.acquire("task:2|python", 60_000L, () -> "other", destroyed::add);

        SandboxSlotPool.ReleaseResult result =
                pool.removeMatchingIfIdle(key -> key.startsWith("task:1|"), destroyed::add, 0L);

        assertThat(result.released()).isEqualTo(2);
        assertThat(result.busy()).isZero();
        assertThat(destroyed).containsExactlyInAnyOrder("py", "node");
        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.contains("task:2|python")).isTrue();
    }

    @Test
    @DisplayName("removeMatchingIfIdle：槽锁被占用 ⇒ 跳过并交给 TTL，不销毁在跑工作区")
    void shouldSkipBusySlot() throws Exception {
        SandboxSlotPool<String, String> pool = new SandboxSlotPool<>();
        List<String> destroyed = new ArrayList<>();
        SandboxSlotPool.Slot<String, String> running =
                pool.acquire("task:1|python", 60_000L, () -> "py", destroyed::add);
        pool.acquire("task:1|node", 60_000L, () -> "node", destroyed::add);

        // 模拟「该槽内仍有命令在跑」：必须由**另一个线程**持锁——ReentrantLock 可重入，
        // 同线程持锁后 tryLock 必然成功，测不出跳过语义（首版就踩了这个坑）。
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread runner = new Thread(() -> {
            running.lock().lock();
            try {
                locked.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.lock().unlock();
            }
        }, "slot-runner");
        runner.setDaemon(true);
        runner.start();
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            SandboxSlotPool.ReleaseResult result =
                    pool.removeMatchingIfIdle(key -> key.startsWith("task:1|"), destroyed::add, 0L);

            assertThat(result.released()).isEqualTo(1);
            assertThat(result.busy()).isEqualTo(1);
            assertThat(destroyed).containsExactly("node");
            assertThat(pool.size()).isEqualTo(1);
            assertThat(pool.contains("task:1|python")).isTrue();
        } finally {
            release.countDown();
            runner.join(5_000L);
        }
    }

    @Test
    @DisplayName("removeMatchingIfIdle：无匹配 ⇒ 零释放零跳过（幂等，重复事件无副作用）")
    void shouldBeIdempotentWhenNoMatch() {
        SandboxSlotPool<String, String> pool = new SandboxSlotPool<>();

        SandboxSlotPool.ReleaseResult empty = pool.removeMatchingIfIdle(key -> true, s -> { }, 0L);
        SandboxSlotPool.ReleaseResult nullMatcher = pool.removeMatchingIfIdle(null, s -> { }, 0L);

        assertThat(empty.released()).isZero();
        assertThat(empty.busy()).isZero();
        assertThat(nullMatcher.released()).isZero();
        assertThat(nullMatcher.busy()).isZero();
    }

    // ===== SL-7（2026-11-13）：后台空闲回收 =====

    @Test
    @DisplayName("reapIdle：空闲过期槽被销毁，未到期槽保留")
    void shouldReapOnlyExpiredSlots() {
        SandboxSlotPool<String, String> pool = new SandboxSlotPool<>();
        List<String> destroyed = new ArrayList<>();
        pool.acquire("expired|a", 60_000L, () -> "old", destroyed::add);
        pool.acquire("fresh|b", 60_000L, () -> "new", destroyed::add);
        // 让 expired 槽已空闲超过 5ms，fresh 槽在回收前刚刚使用过
        sleep(8);
        pool.acquire("fresh|b", 60_000L, () -> "new", destroyed::add).touch();

        SandboxSlotPool.ReapResult result =
                pool.reapIdle(key -> key.startsWith("expired") ? 1L : 600_000L, destroyed::add);

        assertThat(result.reaped()).isEqualTo(1);
        assertThat(result.busy()).isZero();
        assertThat(result.kept()).isEqualTo(1);
        assertThat(destroyed).containsExactly("old");
        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.contains("fresh|b")).isTrue();
    }

    @Test
    @DisplayName("reapIdle：ttl<=0 ⇒ 该键不参与回收（豁免语义）")
    void shouldSkipReapWhenTtlNonPositive() {
        SandboxSlotPool<String, String> pool = new SandboxSlotPool<>();
        List<String> destroyed = new ArrayList<>();
        pool.acquire("keep|a", 60_000L, () -> "sb", destroyed::add);
        sleep(8);

        SandboxSlotPool.ReapResult result = pool.reapIdle(key -> 0L, destroyed::add);

        assertThat(result.reaped()).isZero();
        assertThat(result.kept()).isEqualTo(1);
        assertThat(destroyed).isEmpty();
        assertThat(pool.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("reapIdle：槽被命令占用 ⇒ 跳过（本轮不杀在跑工作区）")
    void shouldSkipBusySlotOnReap() throws Exception {
        SandboxSlotPool<String, String> pool = new SandboxSlotPool<>();
        List<String> destroyed = new ArrayList<>();
        SandboxSlotPool.Slot<String, String> running =
                pool.acquire("task:1|python", 1L, () -> "py", destroyed::add);
        sleep(8);

        // 必须由另一个线程持锁：ReentrantLock 可重入，同线程 tryLock 必然成功
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread runner = new Thread(() -> {
            running.lock().lock();
            try {
                locked.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.lock().unlock();
            }
        }, "slot-runner");
        runner.setDaemon(true);
        runner.start();
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            SandboxSlotPool.ReapResult result = pool.reapIdle(key -> 1L, destroyed::add);

            assertThat(result.reaped()).isZero();
            assertThat(result.busy()).isEqualTo(1);
            assertThat(destroyed).isEmpty();
            assertThat(pool.contains("task:1|python")).isTrue();
        } finally {
            release.countDown();
            runner.join(5_000L);
        }
    }

    @Test
    @DisplayName("reapIdle：null 阈值函数 / 空池 ⇒ 零结果（幂等）")
    void shouldBeIdempotentOnReap() {
        SandboxSlotPool<String, String> pool = new SandboxSlotPool<>();

        SandboxSlotPool.ReapResult nullFn = pool.reapIdle(null, s -> { });
        SandboxSlotPool.ReapResult emptyPool = pool.reapIdle(key -> 1L, s -> { });

        assertThat(nullFn.reaped()).isZero();
        assertThat(nullFn.busy()).isZero();
        assertThat(nullFn.kept()).isZero();
        assertThat(emptyPool.reaped()).isZero();
        assertThat(emptyPool.busy()).isZero();
        assertThat(emptyPool.kept()).isZero();
    }

    @Test
    @DisplayName("keysMatching：列出同作用域全部规格键（只读枚举，不建沙箱）")
    void shouldListAllMatchingKeys() {
        SandboxSlotPool<String, String> pool = new SandboxSlotPool<>();
        AtomicInteger created = new AtomicInteger();
        Supplier<String> opener = () -> "s" + created.incrementAndGet();

        pool.acquire("task:1|python", 0L, opener, s -> { });
        pool.acquire("task:1|node", 0L, opener, s -> { });
        pool.acquire("task:2|python", 0L, opener, s -> { });
        int before = created.get();

        List<String> taskOne = pool.keysMatching(key -> key.startsWith("task:1|"));

        assertThat(taskOne).containsExactlyInAnyOrder("task:1|python", "task:1|node");
        // 只读枚举：不得顺带创建资源，也不得改变池内容
        assertThat(created.get()).isEqualTo(before);
        assertThat(pool.size()).isEqualTo(3);
        assertThat(pool.keysMatching(null)).isEmpty();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
