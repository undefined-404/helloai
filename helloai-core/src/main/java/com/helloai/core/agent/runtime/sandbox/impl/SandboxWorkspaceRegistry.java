package com.helloai.core.agent.runtime.sandbox.impl;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.SandboxIdleReaper;
import com.helloai.core.agent.runtime.sandbox.SandboxSpec;
import com.helloai.core.agent.runtime.sandbox.WorkspaceRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 复用工作区注册表（`SL-9`，2026-11-13）：槽池 / 键 / TTL / 续期 / 回收。
 *
 * <p><b>为什么单独一个类</b>：这些事回答的是「哪个容器在册、还能活多久、什么时候该销毁」，
 * 与「命令怎么跑、文件怎么读」是两套变化原因。运行时与技能安装器共用同一份注册表，
 * 否则「先装包再跑命令」会拿到两个容器。</p>
 *
 * <p><b>键</b>：{@code kind:ownerId}（取自 {@link WorkspaceRef}）+ 规格指纹。句柄的
 * {@code contextId} 只进台账/时间线，<b>不进键</b>——任务级工作区要跨子任务共享。</p>
 *
 * <p><b>空闲上限按作用域区分</b>（`SL-7`）：任务级槽有终态释放点，按 {@code keep-alive} 判活；
 * 会话/子任务级槽没有终态释放点，按 {@code idle-ttl} 收敛。后台回收见 {@link #reapIdle()}。</p>
 *
 * <p><b>销毁归工厂</b>：本类只判「该不该摘」，摘除后的 {@code kill/close} 交给
 * {@link SandboxFactory#destroy(Sandbox)}——同一套销毁语义被池淘汰、任务终态、空闲回收复用。</p>
 */
@Slf4j
@Component
public class SandboxWorkspaceRegistry implements SandboxIdleReaper {

    /**
     * 任务终态回收时单槽持锁等待上限（`SL-2`）。拿到锁 ⇒ 槽空闲、可安全销毁；
     * 拿不到 ⇒ 有命令在跑，跳过并交给空闲 TTL（`ADR-005` §6 风险 4）。
     */
    private static final long RELEASE_TRYLOCK_MILLIS = 5_000L;

    private final OpenSandboxProperties properties;
    private final SandboxFactory factory;

    /**
     * 复用槽池（`ADR-005`）：key = {@code (kind:ownerId, 规格指纹)}，槽内命令串行执行。
     *
     * <p>只在句柄可入池（{@link WorkspaceRef#pooled()}）且 {@code reuse-enabled=true} 时使用。</p>
     */
    private final SandboxSlotPool<SlotKey, Sandbox> pool = new SandboxSlotPool<>();

    public SandboxWorkspaceRegistry(OpenSandboxProperties properties, SandboxFactory factory) {
        this.properties = properties;
        this.factory = factory;
    }

    /**
     * 复用槽键（`SKX-5` + `SL-1` + `SL-8` + `SL-10`）：句柄作用域（类型 + 归属 ID）+ 规格指纹 + 访问意图。
     *
     * <p><b>为什么不能只用裸 ID</b>：① 按调用分层规格后，同一子任务会先后要两种镜像
     * （python 验算 / node 出图），裸 ID 当键会拿到没有目标运行时的容器；② `SL-1` 起复用面
     * 上移到任务级，同一任务的子任务要共享同一规格沙箱。</p>
     *
     * <p><b>为什么纳入 {@link WorkspaceRef.Access}（`SL-10` 对「槽键纳入 Isolation」措辞的精确化）</b>：
     * 执行者与核验者的 {@link WorkspaceRef} 是同一个 {@code kind:ownerId}（同任务、同规格），
     * {@code Isolation} 也相同（都是 {@code TASK_WORKSPACE}），区别只在 {@code Access}——
     * 若槽键不含访问意图，核验者复用的仍是执行者那个可写容器，只读挂载无从谈起。故把
     * {@code Access} 纳入键：执行者（{@code READ_WRITE}）与核验者（{@code READ_ONLY}）各自一个槽实例。</p>
     */
    record SlotKey(WorkspaceRef.Kind kind, Long ownerId, String specFingerprint,
                   WorkspaceRef.Access access) {

        /**
         * 是否命中某句柄作用域（{@code expectedFingerprint == null} ⇒ 不限规格；
         * {@code expectedAccess == null} ⇒ 不限访问意图；释放路径用）。
         */
        boolean matches(WorkspaceRef.Kind expectedKind, Long expectedOwnerId,
                        String expectedFingerprint, WorkspaceRef.Access expectedAccess) {
            return slotMatches(kind, ownerId, specFingerprint, access,
                    expectedKind, expectedOwnerId, expectedFingerprint, expectedAccess);
        }
    }

    /**
     * 槽键匹配判据（`SL-3` + `SL-10` 纯函数，便于单测）。
     *
     * @param kind                槽键作用域类型
     * @param ownerId             槽键作用域 ID
     * @param slotFingerprint     槽键规格指纹
     * @param slotAccess          槽键访问意图
     * @param expectedKind        待匹配的作用域类型（{@code null} ⇒ 不匹配）
     * @param expectedOwnerId     待匹配的作用域 ID
     * @param expectedFingerprint 期望规格指纹（{@code null} ⇒ 不限规格）
     * @param expectedAccess      期望访问意图（{@code null} ⇒ 不限访问；释放路径用）
     */
    static boolean slotMatches(WorkspaceRef.Kind kind, Long ownerId, String slotFingerprint,
                               WorkspaceRef.Access slotAccess,
                               WorkspaceRef.Kind expectedKind, Long expectedOwnerId,
                               String expectedFingerprint, WorkspaceRef.Access expectedAccess) {
        if (expectedKind == null || kind != expectedKind || !Objects.equals(ownerId, expectedOwnerId)) {
            return false;
        }
        if (expectedAccess != null && slotAccess != expectedAccess) {
            return false;
        }
        return expectedFingerprint == null || Objects.equals(slotFingerprint, expectedFingerprint);
    }

    /**
     * 回读侧选键（`SL-3` 纯函数）：同规格优先，其次「作用域唯一槽」，多规格并存且都不匹配 ⇒ {@code null}。
     *
     * <p><b>为什么允许回退到唯一槽</b>：回读调用拿不到调用时的规格；技能执行用 node 规格、
     * 全局默认是 python，若严格要求同规格，读技能产物的路径会全部落空。</p>
     *
     * <p><b>为什么多规格并存时拒绝</b>：没有任何依据判断「该从哪个容器读」，猜一个的结果是
     * 读到另一个规格里的同名文件（内容对不上却看起来成功了）。</p>
     *
     * @param candidates           同作用域的在册槽键
     * @param preferredFingerprint 期望规格指纹（{@code null} ⇒ 不限）
     * @return 选中的槽键；无候选或多规格歧义 ⇒ {@code null}
     */
    static SlotKey selectReadKey(List<SlotKey> candidates, String preferredFingerprint) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (SlotKey key : candidates) {
            if (preferredFingerprint != null && preferredFingerprint.equals(key.specFingerprint())) {
                return key;
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 生成复用槽键（`SL-8` + `SL-10`）：句柄作用域 + 规格指纹 + 访问意图。
     *
     * <p>句柄未给指纹时按<b>生效规格</b>补算（执行路径总是知道本轮用哪个规格）。</p>
     */
    SlotKey slotKey(WorkspaceRef ref, SandboxSpec effective) {
        String fingerprint = ref.specFingerprint() != null
                ? ref.specFingerprint() : SandboxSpecMapper.fingerprint(effective);
        return new SlotKey(ref.kind(), ref.ownerId(), fingerprint, ref.access());
    }

    /**
     * 打开（或复用）一个槽，必要时调用 {@link SandboxFactory#create} 建容器。
     *
     * @param ref            工作区句柄（调用方已保证可入池）
     * @param effective      生效规格
     * @param createFailure  创建失败原因出口
     * @return 可用槽；创建失败 ⇒ {@code null}
     */
    SandboxSlotPool.Slot<SlotKey, Sandbox> open(WorkspaceRef ref, SandboxSpec effective,
                                                AtomicReference<String> createFailure) {
        SlotKey key = slotKey(ref, effective);
        return pool.acquire(key, slotTtlMillis(key),
                () -> factory.create(effective, ref.contextId(), createFailure,
                        ref.access() == WorkspaceRef.Access.READ_ONLY),
                factory::destroy);
    }

    /**
     * 取一个<b>已在册</b>的同作用域槽（**不创建**）。
     *
     * <p>只读通道（回读 / 探测 / 写回 / 列目录）用它：没有活跃槽时绝不为读文件白建容器。</p>
     *
     * @param ref 工作区句柄
     * @return 槽；无在册候选 / 多规格歧义 / 已被并发摘除 ⇒ {@code null}
     */
    SandboxSlotPool.Slot<SlotKey, Sandbox> openIfPresent(WorkspaceRef ref) {
        SlotKey key = readKey(ref);
        if (key == null) {
            return null;
        }
        return pool.acquire(key, slotTtlMillis(key), () -> null, factory::destroy);
    }

    /**
     * 回读路径找在册键（`SL-3`，不创建沙箱）：按「句柄作用域 + 规格」选，
     * 规则见 {@link #selectReadKey(List, String)}。
     */
    SlotKey readKey(WorkspaceRef ref) {
        if (ref == null) {
            return null;
        }
        List<SlotKey> candidates = pool.keysMatching(
                key -> key.matches(ref.kind(), ref.ownerId(), null, ref.access()));
        String preferred = ref.specFingerprint() != null
                ? ref.specFingerprint() : defaultSpecFingerprint();
        SlotKey key = selectReadKey(candidates, preferred);
        if (key == null && candidates.size() > 1) {
            log.warn("SandboxWorkspaceRegistry: 同一作用域存在 {} 个规格槽且均非默认规格，"
                            + "拒绝跨规格回读 scope={}, fingerprints={}",
                    candidates.size(), ref.key(),
                    candidates.stream().map(SlotKey::specFingerprint).toList());
        }
        return key;
    }

    /** 显式摘除并销毁某槽（技能注入失败后丢弃半装包）。 */
    void remove(SlotKey key) {
        pool.remove(key, factory::destroy);
    }

    /**
     * 释放句柄作用域下的复用工作区（`SL-8`）。
     *
     * <p>{@code specFingerprint == null} ⇒ 释放该 {@code kind:ownerId} 下的<b>全部规格</b>槽
     * （turn 末释放 / 任务终态回收），非 null ⇒ 只释放该规格。一次性句柄不进池，静默返回。</p>
     */
    void release(WorkspaceRef ref) {
        if (ref == null || !ref.pooled()) {
            return;
        }
        SandboxSlotPool.ReleaseResult result = pool.removeMatchingIfIdle(
                key -> key.matches(ref.kind(), ref.ownerId(), ref.specFingerprint(), null),
                factory::destroy, RELEASE_TRYLOCK_MILLIS);
        if (result.released() > 0 || result.busy() > 0) {
            log.info("SandboxWorkspaceRegistry: 复用沙箱已释放 scope={}, fingerprint={}, 释放槽数={}, "
                            + "在跑跳过={}, 在册={}",
                    ref.key(), ref.specFingerprint(), result.released(), result.busy(), pool.size());
        }
    }

    /**
     * 按需续期（`SL-7`）：把容器寿命补到 {@link OpenSandboxProperties#getKeepAliveSeconds()}。
     *
     * <p><b>{@code renew} 是绝对过期时刻</b>（探针实测），所以补的是「目标寿命」而不是增量；
     * 只在剩余寿命不足目标的一半时才发请求。失败不阻断（续期是旁路，命令结果已经拿到）。</p>
     *
     * @param slot      复用槽（携带本地记录的过期时刻）
     * @param contextId 上下文 ID（仅日志）
     */
    void renewIfNeeded(SandboxSlotPool.Slot<SlotKey, Sandbox> slot, Long contextId) {
        long keepAliveSeconds = properties.getKeepAliveSeconds();
        if (!renewDue(slot.expiresAt(), System.currentTimeMillis(), keepAliveSeconds)) {
            return;
        }
        try {
            slot.resource().renew(Duration.ofSeconds(keepAliveSeconds));
            slot.markExpiresAt(System.currentTimeMillis() + keepAliveSeconds * 1000L);
        } catch (Exception e) {
            log.debug("SandboxWorkspaceRegistry: renew 失败（不阻断，下条命令再试）contextId={}, cause={}",
                    contextId, e.getMessage());
        }
    }

    /**
     * 是否需要补一次续期（`SL-7` 纯判据，便于单测）。
     *
     * <p>规则：{@code keepAliveSeconds <= 0} ⇒ 不主动续期；过期时刻未知（{@code 0}）⇒ 需要补；
     * 剩余寿命不足目标的一半 ⇒ 需要补。</p>
     *
     * @param expiresAtMillis  服务端过期时刻（{@code 0} = 未知）
     * @param nowMillis        当前时刻
     * @param keepAliveSeconds 目标寿命（秒）
     */
    static boolean renewDue(long expiresAtMillis, long nowMillis, long keepAliveSeconds) {
        if (keepAliveSeconds <= 0) {
            return false;
        }
        if (expiresAtMillis <= 0) {
            return true;
        }
        return expiresAtMillis - nowMillis < keepAliveSeconds * 1000L / 2;
    }

    /**
     * 槽的空闲上限（`SL-7`）：任务级槽按保活目标判活，其余按 {@code idle-ttl-seconds}。
     *
     * <p><b>为什么按键区分</b>：任务级槽有终态释放点，空闲往往只是「在等人审查 / 等下游」——
     * 按 15 分钟淘汰会连带丢掉整个任务已产出的工作区。会话技能 / 子任务级槽没有终态释放点，
     * 只能靠短空闲上限收敛。</p>
     */
    long slotTtlMillis(SlotKey key) {
        boolean taskScope = key != null && key.kind() == WorkspaceRef.Kind.TASK;
        return idleTtlMillisOf(taskScope, properties.getKeepAliveSeconds(), properties.getIdleTtlSeconds());
    }

    /**
     * 槽空闲上限的纯判据（`SL-7`，便于单测）。
     *
     * @param taskScope        是否任务级槽
     * @param keepAliveSeconds 保活目标（秒，{@code <=0} ⇒ 未启用主动续期）
     * @param idleTtlSeconds   空闲上限（秒，非任务级槽使用）
     * @return 空闲上限（毫秒）；{@code <=0} ⇒ 该槽不参与空闲淘汰
     */
    static long idleTtlMillisOf(boolean taskScope, long keepAliveSeconds, long idleTtlSeconds) {
        if (taskScope && keepAliveSeconds > 0) {
            return keepAliveSeconds * 1000L;
        }
        return Math.max(idleTtlSeconds, 0L) * 1000L;
    }

    /**
     * 后台空闲回收（`SL-7`）：销毁空闲超过阈值的槽。
     *
     * <p><b>为什么需要它</b>：{@code acquire} 的空闲淘汰只在同键再次访问时发生，一个再也不会
     * 被访问的槽（任务被放弃、会话结束）永远不会触发淘汰——保活 8h 会变成泄漏 8h。</p>
     *
     * <p><b>互斥与降级</b>：逐槽 {@code tryLock}，拿不到说明有命令在跑，跳过留待下一轮。
     * 回收属运维动作，任何异常都不上抛（否则调度线程会因单槽故障停摆）。</p>
     */
    @Override
    public ReapReport reapIdle() {
        if (!properties.isEnabled() || !properties.isReuseEnabled()) {
            return ReapReport.empty();
        }
        try {
            SandboxSlotPool.ReapResult result = pool.reapIdle(this::slotTtlMillis, factory::destroy);
            if (result.reaped() > 0 || result.busy() > 0) {
                log.info("SandboxWorkspaceRegistry: 空闲回收完成 reaped={}, busy={}, kept={}, 在册={}",
                        result.reaped(), result.busy(), result.kept(), pool.size());
            }
            return new ReapReport(result.reaped(), result.busy(), result.kept());
        } catch (Exception e) {
            log.warn("SandboxWorkspaceRegistry: 空闲回收异常（本轮放弃，下轮重试）cause={}", e.getMessage());
            return ReapReport.empty();
        }
    }

    /** 当前在册的复用沙箱数（观测 / 单测用）。 */
    public int activeCount() {
        return pool.size();
    }

    /** 配置默认规格的指纹（回读侧只能按它比对；配置缺失时按 {@code "default"}）。 */
    private String defaultSpecFingerprint() {
        OpenSandboxProperties.Spec configured = properties.getSpec();
        return SandboxSpecMapper.fingerprint(
                configured == null ? null : SandboxSpecMapper.toDomain(configured));
    }
}
