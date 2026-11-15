package com.helloai.core.agent.runtime.sandbox.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * 「按 key 复用的执行资源槽池」（`ADR-005` / `LOG-20261025-005`）。
 *
 * <p><b>为什么需要它</b>：沙箱实现此前的调用模型是「每次调用都新建沙箱、
 * 跑一条命令、{@code finally} 无条件 {@code kill()}」⇒ <b>文件不跨调用保留</b>，模型只能
 * 整篇重写正文（MiniMax 20 轮把 3820 字压到 2787 字仍不达标，轮次耗尽）。对齐
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
 * <p><b>key 为什么是泛型</b>（`SKX-5`）：引入「按调用分层规格」后，同一个
 * {@code subTaskId} 可能要同时持有<b>不同镜像</b>的沙箱（先 python 验算、再 node 出图）。
 * 若 key 仍是裸 {@code Long}，第二次调用会命中第一次的槽，拿到<b>没有 node 的 python 容器</b>。
 * 故 key 交给调用方组合（生产 = {@code (subTaskId, specFingerprint)}），本类只做相等性比较。</p>
 *
 * @param <K> 作用域键（生产 = {@code (subTaskId, specFingerprint)} 复合键）
 * @param <T> 被复用的执行资源（生产中 = OpenSandbox SDK {@code Sandbox}）
 */
final class SandboxSlotPool<K, T> {

    private final Map<K, Slot<K, T>> slots = new ConcurrentHashMap<>();

    /**
     * 取一个可用槽：未过期则复用；已过期则先 {@code closer} 销毁再加入新建。
     *
     * @param key           作用域键（生产 = {@code (subTaskId, specFingerprint)}）
     * @param idleTtlMillis 空闲上限（毫秒）；{@code <=0} 表示不做过期判定（恒复用）
     * @param opener        创建资源的工厂；返回 {@code null} 表示创建失败（不登记，用方降到结构化失败）
     * @param closer        销毁资源的回调（过期淘汰 / 显式移除 / 替换时调用）
     * @return 可用槽；创建失败返回 {@code null}
     */
    Slot<K, T> acquire(K key, long idleTtlMillis, Supplier<T> opener, Consumer<T> closer) {
        if (key == null) {
            return null;
        }
        Slot<K, T> existing = slots.get(key);
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
    boolean remove(K key, Consumer<T> closer) {
        if (key == null) {
            return false;
        }
        Slot<K, T> removed = slots.remove(key);
        if (removed == null) {
            return false;
        }
        closer.accept(removed.resource());
        return true;
    }

    /**
     * 移除并销毁<b>所有匹配的槽</b>（`SKX-5`：turn 结束要释放该子任务的全部规格槽）。
     *
     * <p>语义：{@code release(subTaskId)} 不能只按精确 key 删——同一子任务可能同时在册
     * 多个规格（python + node），漏删的那个会活到空闲超时，且它的文件跨 turn 残留。</p>
     *
     * @param matcher 键匹配条件（生产 = {@code k.subTaskId().equals(subTaskId)}）
     * @param closer  销毁回调
     * @return 实际移除的槽数
     */
    int removeMatching(Predicate<K> matcher, Consumer<T> closer) {
        if (matcher == null) {
            return 0;
        }
        int removed = 0;
        for (Map.Entry<K, Slot<K, T>> entry : slots.entrySet()) {
            if (!matcher.test(entry.getKey())) {
                continue;
            }
            if (slots.remove(entry.getKey(), entry.getValue())) {
                closer.accept(entry.getValue().resource());
                removed++;
            }
        }
        return removed;
    }

    /** 当前在册槽数（观测用）。 */
    int size() {
        return slots.size();
    }

    /**
     * 移除并销毁所有匹配、且<b>当前无人持有槽锁</b>的槽（`SL-2`，：任务终态回收）。
     *
     * <p><b>为什么不能直接复用 {@link #removeMatching}</b>：任务终态事件到达时，该任务可能
     * 仍有命令在跑（如最后一个子任务的收尾轮）。无条件摘除并 {@code closer} 会 kill 正在执行的
     * 容器 ⇒ 正在写的正文丢失。故先 {@code tryLock}：拿到锁说明槽空闲，摘除并销毁；拿不到
     * 说明有命令在跑，<b>跳过并交给空闲 TTL 兜底</b>（`ADR-005` §6 风险 4）。</p>
     *
     * @param matcher       键匹配条件（生产 = {@code kind == TASK && scopeId == taskId}）
     * @param closer        销毁回调
     * @param tryLockMillis 单槽尝试持锁上限（毫秒）；{@code <=0} 表示只试一次不等待
     * @return 释放结果（已释放槽数 / 因在跑而跳过的槽数）
     */
    ReleaseResult removeMatchingIfIdle(Predicate<K> matcher, Consumer<T> closer, long tryLockMillis) {
        if (matcher == null) {
            return new ReleaseResult(0, 0);
        }
        int released = 0;
        int busy = 0;
        for (Map.Entry<K, Slot<K, T>> entry : slots.entrySet()) {
            if (!matcher.test(entry.getKey())) {
                continue;
            }
            Slot<K, T> slot = entry.getValue();
            boolean locked;
            try {
                locked = tryLockMillis > 0
                        ? slot.lock().tryLock(tryLockMillis, TimeUnit.MILLISECONDS)
                        : slot.lock().tryLock();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!locked) {
                busy++;
                continue;
            }
            try {
                if (slots.remove(entry.getKey(), slot)) {
                    closer.accept(slot.resource());
                    released++;
                }
            } finally {
                slot.lock().unlock();
            }
        }
        return new ReleaseResult(released, busy);
    }

    /**
     * 后台回收：销毁「空闲超过阈值」的槽（`SL-7`）。
     *
     * <p><b>为什么必须有它</b>：{@link #acquire} 的空闲淘汰只在<b>同键再次访问</b>时发生——
     * 一个再也不会被访问的键（任务被放弃、会话结束）永远不会触发淘汰，<b>大 TTL 等于泄漏</b>。
     * 有了定时回收，「保活 8 小时」才是安全的上限而不是泄漏时长。</p>
     *
     * <p><b>阈值按键区分</b>：由调用方给出「该键的空闲上限」。任务级槽要活到任务终态
     * （可能长时间等人工审核），会话/子任务作用域槽没有终态释放点，可以按短空闲上限回收。
     * 返回 {@code <=0} 表示该类键不参与回收。</p>
     *
     * <p><b>与在跑命令互斥</b>：{@code tryLock} 拿不到说明该槽有命令在执行，<b>跳过（计入 busy）</b>
     * ——回收不能杀掉正在写文件的命令（与 {@link #removeMatchingIfIdle} 同一口径）。</p>
     *
     * @param ttlMillisOf 键 ⇒ 空闲上限（毫秒）；{@code <=0} ⇒ 该键不回收
     * @param closer      销毁回调
     * @return 回收结果（已销毁 / 在跑跳过 / 未到期保留）
     */
    ReapResult reapIdle(ToLongFunction<K> ttlMillisOf, Consumer<T> closer) {
        if (ttlMillisOf == null) {
            return new ReapResult(0, 0, 0);
        }
        int reaped = 0;
        int busy = 0;
        int kept = 0;
        for (Map.Entry<K, Slot<K, T>> entry : slots.entrySet()) {
            Slot<K, T> slot = entry.getValue();
            long ttlMillis = ttlMillisOf.applyAsLong(entry.getKey());
            if (!slot.idleExpired(ttlMillis)) {
                kept++;
                continue;
            }
            if (!slot.lock().tryLock()) {
                busy++;
                continue;
            }
            try {
                if (slots.remove(entry.getKey(), slot)) {
                    closer.accept(slot.resource());
                    reaped++;
                } else {
                    kept++;
                }
            } finally {
                slot.lock().unlock();
            }
        }
        return new ReapResult(reaped, busy, kept);
    }

    /**
     * 空闲回收结果（`SL-7`）。{@code busy > 0} 说明回收时刻仍有命令占用，本轮跳过、下一轮再试。
     *
     * @param reaped 已摘除并销毁的槽数
     * @param busy   因槽被占用（命令在跑）而跳过的槽数
     * @param kept   未到空闲阈值而保留的槽数
     */
    record ReapResult(int reaped, int busy, int kept) {
    }

    /**
     * 批量释放结果（`SL-2`）。{@code busy > 0} 是排障信号：说明终态时刻仍有命令占用该槽，
     * 回收被推迟到空闲 TTL。
     *
     * @param released 已摘除并销毁的槽数
     * @param busy     因槽被占用（命令在跑）而跳过的槽数
     */
    record ReleaseResult(int released, int busy) {
    }

    /**
     * 是否已有在册槽（**不创建**）。
     *
     * <p>供「终态回读工作区」这类<b>只读探测</b>使用：没有活跃沙箱就不该调用
     * {@link #acquire}，否则会白建一个沙箱（3 秒 + 一份资源）。</p>
     */
    boolean contains(K key) {
        return key != null && slots.containsKey(key);
    }

    /**
     * 是否存在<b>任意</b>匹配的在册槽（**不创建**）。语义同 {@link #contains}，
     * 但按谓词匹配——回读时只知道 {@code subTaskId}，不知道当时用的是哪个规格。
     */
    boolean containsMatching(Predicate<K> matcher) {
        return findKey(matcher).isPresent();
    }

    /**
     * 找出<b>全部</b>匹配的在册键（**不创建**；`SL-3`）。
     *
     * <p>回读需要它而不是 {@link #findKey}：同一任务可能有多个规格槽，先拿到全部候选
     * 才能按「同规格优先 / 唯一才回退 / 多规格拒绝」的规则选，避免读到另一个规格的容器。</p>
     *
     * @param matcher 键匹配条件
     * @return 匹配键的快照（无匹配 ⇒ 空列表；{@code matcher == null} ⇒ 空列表）
     */
    List<K> keysMatching(Predicate<K> matcher) {
        if (matcher == null) {
            return List.of();
        }
        List<K> keys = new ArrayList<>();
        for (K key : slots.keySet()) {
            if (matcher.test(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * 找出第一个匹配的在册键（**不创建**）。
     *
     * <p>回读路径需要它：{@link #acquire} 要求精确键，而调用方只有 {@code subTaskId}。</p>
     */
    Optional<K> findKey(Predicate<K> matcher) {
        if (matcher == null) {
            return Optional.empty();
        }
        for (K key : slots.keySet()) {
            if (matcher.test(key)) {
                return Optional.ofNullable(key);
            }
        }
        return Optional.empty();
    }

    /**
     * 一个复用槽：资源 + 串行锁 + 最后使用时刻。
     *
     * @param <K> 作用域键类型
     * @param <T> 资源类型
     */
    static final class Slot<K, T> {

        private final K key;
        private final T resource;
        private final ReentrantLock lock = new ReentrantLock();
        private volatile long lastUsedAt;
        /** 服务端过期时刻（毫秒时间戳；{@code 0} = 未知）。{@code renew} 是绝对语义，靠它决定要不要补。 */
        private volatile long expiresAt;

        Slot(K key, T resource) {
            this.key = key;
            this.resource = resource;
            this.lastUsedAt = System.currentTimeMillis();
        }

        K key() {
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
         * 记录服务端过期时刻（`SL-7`）。{@code renew} 是无返回值的绝对设置，
         * 本地记下目标时刻才能判断「剩余是否已不足、要不要补一次」。
         *
         * @param epochMillis 过期时刻（毫秒时间戳）
         */
        void markExpiresAt(long epochMillis) {
            this.expiresAt = epochMillis;
        }

        /** 服务端过期时刻；{@code 0} = 未知（尚未 renew 过）。 */
        long expiresAt() {
            return expiresAt;
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

        @Override
        public String toString() {
            return "Slot{key=" + key + ", resource=" + (resource == null ? "null" : resource) + "}";
        }
    }
}
