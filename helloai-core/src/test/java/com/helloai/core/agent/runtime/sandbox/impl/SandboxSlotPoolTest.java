package com.helloai.core.agent.runtime.sandbox.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
