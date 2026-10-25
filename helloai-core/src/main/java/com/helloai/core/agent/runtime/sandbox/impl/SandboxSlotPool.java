package com.helloai.core.agent.runtime.sandbox.impl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 「按 key 复用的执行资源槽池」（2026-10-25，`ADR-005` / `LOG-20261025-005`）。
 *
 * <p><b>为什么需要它</b>：原 {@code OpenSandboxExecutionService} 每次调用都新建沙箱、
 * 跑一条命令、{@code finally} 无条件 {@code kill()} ⇒ <b>文件不跨调用保留</b>，模型只能
 * 整篇重写正文（实测 MiniMax 20 轮把 3820 字压到 2787 字仍不达标、轮次耗尽）。对齐
 * deepseek-harness 的产品级口径（`dsh-tool-bash-persistent`：同一 agent 的 shell 状态与
 * <b>文件系统</b>跨调用持久），改为「同一子任务复用同一沙箱」。</p>
 *
 * <p><b>本类只做生命周期决策</b>（不碰 SDK、不碰业务语义），因此可用任意 {@code T} 做单测：
 * 命中复用 / 空闲过期重建 / 显式移除 / 创建失败不登记。真正的建/销毁由调用方以
 * {@code opener} / {@code closer} 注入。</p>
 *
 * <p><b>并发口径</b>：同一槽内的命令必须串行（对齐 DSH「commands run sequentially」），
 * 由 {@link Slot#lock()} 保证；槽级别的创建竞态下可能多建一个，胜出者保留、败者由
 * 调用方按「未使用即视为空闲」回收——本类不为此引入全局锁（消费者本就按子任务串行）。</p>
 *
 * @param <T> 被复用的执行资源（生产中 = OpenSandbox SDK {@code Sandbox}）
 */
final class SandboxSlotPool<T> {

    private final Map<Long, Slot<T>> slots = new ConcurrentHashMap<>();

    /**
     * 取一个可用槽：未过期则复用；已过期则先 {@code closer} 销毁再加入新建。
     *
     * @param key           作用域键（生产 = subTaskId）
     * @param idleTtlMillis 空闲上限（毫秒）；{@code <=0} 表示不做过期判定（恒复用）
     * @param opener        创建资源的工厂；返回 {@code null} 表示创建失败（不登记，调用方降到结构化失败）
     * @param closer        销毁资源的回调（过期淘汰 / 显式移除 / 替换时调用）
     * @return 可用槽；创建失败返回 {@code null}
     */
    Slot<T> acquire(Long key, long idleTtlMillis, Supplier<T> opener, Consumer<T> closer) {
        if (key == null) {
            return null;
        }
        Slot<T> existing = slots.get(key);
        if (existing != null) {
            if (!existing.idleExpired(idleTtlMillis)) {
                return existing;
            }
            // 空闲过期：先摘除再销毁，避免「已销毁的槽被别的线程拿到」
            if (slots.remove(key, existing)) {
                closer.accept(existing.resource());
            }
        }
        return slots.computeIfAbsent(key, k -> {
            T resource = opener.get();
            return resource == null ? null : new Slot<>(k, resource);
        });
    }

    /**
     * 显式移除并销毁某槽（turn 结束释放 / 命令失败后丢弃）。幂等。
     *
     * @param key    作用域键
     * @param closer 销毁回调（未登记时不调用）
     * @return 是否真的移除了一个槽
     */
    boolean remove(Long key, Consumer<T> closer) {
        if (key == null) {
            return false;
        }
        Slot<T> removed = slots.remove(key);
        if (removed == null) {
            return false;
        }
        closer.accept(removed.resource());
        return true;
    }

    /** 当前在册槽数（观测用）。 */
    int size() {
        return slots.size();
    }

    /**
     * 是否已有在册槽（**不创建**）。
     *
     * <p>供「终态回读工作区」这类<b>只读探测</b>使用：没有活跃沙箱就不该调用
     * {@link #acquire}，否则会白建一个沙箱（3 秒 + 一份资源）。</p>
     */
    boolean contains(Long key) {
        return key != null && slots.containsKey(key);
    }

    /**
     * 一个复用槽：资源 + 串行锁 + 最后使用时刻。
     *
     * @param <T> 资源类型
     */
    static final class Slot<T> {

        private final Long key;
        private final T resource;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile long lastUsedAt;

        Slot(Long key, T resource) {
            this.key = key;
            this.resource = resource;
            this.lastUsedAt = System.currentTimeMillis();
        }

        Long key() {
            return key;
        }

        T resource() {
            return resource;
        }

        /** 同槽命令串行锁（对齐 DSH：同一 shell / 同一 workspace 内的命令顺序执行）。 */
        ReentrantLock lock() {
            return lock;
        }

        /** 记录一次使用（续期判据）。 */
        void touch() {
            this.lastUsedAt = System.currentTimeMillis();
        }

        long lastUsedAt() {
            return lastUsedAt;
        }

        /**
         * 是否已空闲超时。
         *
         * @param idleTtlMillis 空闲上限毫秒；{@code <=0} ⇒ 恒不过期
         */
        boolean idleExpired(long idleTtlMillis) {
            if (idleTtlMillis <= 0) {
                return false;
            }
            return System.currentTimeMillis() - lastUsedAt > idleTtlMillis;
        }
    }
}
