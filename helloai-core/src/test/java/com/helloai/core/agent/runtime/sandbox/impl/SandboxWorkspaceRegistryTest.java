package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.SandboxIdleReaper;
import com.helloai.core.agent.runtime.sandbox.WorkspaceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SandboxWorkspaceRegistry} 单测（`SL-9`，2026-11-13）。
 *
 * <p>覆盖「哪个容器在册、还能活多久、什么时候该摘」三件事：槽键匹配 / 回读选键（`SL-3`）、
 * 空闲上限与续期判据（`SL-7`）、释放与回收的幂等（`SL-2`）。建容器的真实路径不在此处——
 * 守卫与纯判据都不触碰 SDK。</p>
 */
@DisplayName("SandboxWorkspaceRegistry")
class SandboxWorkspaceRegistryTest {

    private OpenSandboxProperties props(boolean enabled) {
        OpenSandboxProperties props = new OpenSandboxProperties();
        props.setEnabled(enabled);
        props.setUrl("http://localhost:8090");
        props.setApiKey("dev-test-key");
        return props;
    }

    private SandboxWorkspaceRegistry registry(OpenSandboxProperties props) {
        return new SandboxWorkspaceRegistry(props, new SandboxFactory(props));
    }

    private static WorkspaceRef ref(Long contextId) {
        return WorkspaceRef.subtask(contextId);
    }

    @Test
    @DisplayName("复用默认开启，且可配置关闭（关闭 ⇒ 回落一次性语义）")
    void reuseEnabledShouldBeConfigurable() {
        OpenSandboxProperties props = props(true);
        // 默认值：对齐 DSH dsh-tool-bash-persistent 的「同子任务工作区持久」口径
        assertThat(props.isReuseEnabled()).isTrue();
        assertThat(props.getIdleTtlSeconds()).isEqualTo(900L);

        props.setReuseEnabled(false);
        assertThat(props.isReuseEnabled()).isFalse();
    }

    @Test
    @DisplayName("SL-7 配置默认：idle-ttl 900s（回收判活）+ keep-alive 28800s（renew 目标）")
    void lifecycleDefaultsShouldBeSplit() {
        OpenSandboxProperties props = new OpenSandboxProperties();

        assertThat(props.getIdleTtlSeconds()).isEqualTo(900L);
        assertThat(props.getKeepAliveSeconds()).isEqualTo(28_800L);
    }

    @Test
    @DisplayName("SL-3 槽匹配：作用域命中且未指定规格 ⇒ 命中；指定规格不符 ⇒ 不命中")
    void slotMatchesShouldHonorScopeAndFingerprint() {
        assertThat(SandboxWorkspaceRegistry.slotMatches(
                WorkspaceRef.Kind.TASK, 1L, "py", WorkspaceRef.Access.READ_WRITE,
                WorkspaceRef.Kind.TASK, 1L, null, null)).isTrue();
        // 回读路径：规格不符不得命中（旧实现只比 (kind, id)，会读到另一个规格的容器）
        assertThat(SandboxWorkspaceRegistry.slotMatches(
                WorkspaceRef.Kind.TASK, 1L, "node", WorkspaceRef.Access.READ_WRITE,
                WorkspaceRef.Kind.TASK, 1L, "py", null)).isFalse();
        assertThat(SandboxWorkspaceRegistry.slotMatches(
                WorkspaceRef.Kind.SCOPE, 1L, "py", WorkspaceRef.Access.READ_WRITE,
                WorkspaceRef.Kind.TASK, 1L, null, null)).isFalse();
        assertThat(SandboxWorkspaceRegistry.slotMatches(
                WorkspaceRef.Kind.TASK, 2L, "py", WorkspaceRef.Access.READ_WRITE,
                WorkspaceRef.Kind.TASK, 1L, null, null)).isFalse();
        assertThat(SandboxWorkspaceRegistry.slotMatches(
                WorkspaceRef.Kind.TASK, 1L, "py", WorkspaceRef.Access.READ_WRITE,
                null, null, null, null)).isFalse();
    }

    @Test
    @DisplayName("SL-10 槽匹配：访问意图纳入判据——读写与只读互为不同槽；expectedAccess=null 不限")
    void slotMatchesShouldHonorAccess() {
        // 读写槽 ≠ 只读槽（核验者不能复用执行者可写容器，否则只读挂载落空）
        assertThat(SandboxWorkspaceRegistry.slotMatches(
                WorkspaceRef.Kind.TASK, 1L, "py", WorkspaceRef.Access.READ_WRITE,
                WorkspaceRef.Kind.TASK, 1L, null, WorkspaceRef.Access.READ_ONLY)).isFalse();
        // 同访问意图 ⇒ 命中
        assertThat(SandboxWorkspaceRegistry.slotMatches(
                WorkspaceRef.Kind.TASK, 1L, "py", WorkspaceRef.Access.READ_ONLY,
                WorkspaceRef.Kind.TASK, 1L, null, WorkspaceRef.Access.READ_ONLY)).isTrue();
        // 释放路径：expectedAccess=null ⇒ 不限访问意图（同时释放读写 + 只读两槽）
        assertThat(SandboxWorkspaceRegistry.slotMatches(
                WorkspaceRef.Kind.TASK, 1L, "py", WorkspaceRef.Access.READ_WRITE,
                WorkspaceRef.Kind.TASK, 1L, null, null)).isTrue();
        assertThat(SandboxWorkspaceRegistry.slotMatches(
                WorkspaceRef.Kind.TASK, 1L, "py", WorkspaceRef.Access.READ_ONLY,
                WorkspaceRef.Kind.TASK, 1L, null, null)).isTrue();
    }

    @Test
    @DisplayName("SL-3 回读选槽：同规格优先 → 唯一候选回退 → 多规格拒绝（不猜）")
    void selectReadKeyShouldPreferSameSpecAndRefuseAmbiguity() {
        SandboxWorkspaceRegistry.SlotKey py = new SandboxWorkspaceRegistry.SlotKey(
                WorkspaceRef.Kind.TASK, 1L, "py", WorkspaceRef.Access.READ_WRITE);
        SandboxWorkspaceRegistry.SlotKey node = new SandboxWorkspaceRegistry.SlotKey(
                WorkspaceRef.Kind.TASK, 1L, "node", WorkspaceRef.Access.READ_WRITE);

        // 同规格优先：技能以 node 规格执行，回读不能落到默认 python 槽
        assertThat(SandboxWorkspaceRegistry.selectReadKey(List.of(py, node), "node"))
                .isEqualTo(node);
        // 唯一候选：即便没有默认规格槽也回退（保住无规格参数的回读入口）
        assertThat(SandboxWorkspaceRegistry.selectReadKey(List.of(node), "py"))
                .isEqualTo(node);
        // 多规格且无一匹配 ⇒ null（宁可回读失败，也不跨规格读错文件）
        assertThat(SandboxWorkspaceRegistry.selectReadKey(List.of(py, node), "other"))
                .isNull();
        assertThat(SandboxWorkspaceRegistry.selectReadKey(List.of(py, node), null))
                .isNull();
        assertThat(SandboxWorkspaceRegistry.selectReadKey(List.of(), "py")).isNull();
        assertThat(SandboxWorkspaceRegistry.selectReadKey(null, "py")).isNull();
    }

    @Test
    @DisplayName("SL-7 续期判据：未续期过要补；剩余不足一半要补；剩余充足不补；0 表示不续期")
    void renewDueShouldFollowHalfLifeRule() {
        long now = 1_000_000L;
        long keepAlive = 28_800L;          // 目标 8h
        long full = keepAlive * 1000L;

        // 从未续期（expiresAt=0，只有创建时的 timeout-seconds）⇒ 必须补
        assertThat(SandboxWorkspaceRegistry.renewDue(0L, now, keepAlive)).isTrue();
        // 刚续完（剩余 = 目标）⇒ 不补（否则每条命令白付一次 RPC）
        assertThat(SandboxWorkspaceRegistry.renewDue(now + full, now, keepAlive)).isFalse();
        // 只剩 1 分钟 ⇒ 补
        assertThat(SandboxWorkspaceRegistry.renewDue(now + 60_000L, now, keepAlive)).isTrue();
        // 已过期 ⇒ 补
        assertThat(SandboxWorkspaceRegistry.renewDue(now - 1L, now, keepAlive)).isTrue();
        // keep-alive 未启用（<=0）⇒ 永不主动续期
        assertThat(SandboxWorkspaceRegistry.renewDue(0L, now, 0L)).isFalse();
    }

    @Test
    @DisplayName("SL-7 槽空闲上限：任务级按 keep-alive（8h），会话/子任务级按 idle-ttl（900s）")
    void slotTtlShouldDependOnScope() {
        // 任务级槽：长时间等人工审查也不能按 15 分钟销毁
        assertThat(SandboxWorkspaceRegistry.idleTtlMillisOf(true, 28_800L, 900L))
                .isEqualTo(28_800_000L);
        // 会话技能 / 子任务级：没有终态释放点，按短空闲上限收敛
        assertThat(SandboxWorkspaceRegistry.idleTtlMillisOf(false, 28_800L, 900L))
                .isEqualTo(900_000L);
        // keep-alive 未启用 ⇒ 任务级回落到 idle-ttl
        assertThat(SandboxWorkspaceRegistry.idleTtlMillisOf(true, 0L, 900L))
                .isEqualTo(900_000L);
        // idle-ttl<=0 ⇒ 不参与空闲淘汰
        assertThat(SandboxWorkspaceRegistry.idleTtlMillisOf(false, 0L, 0L)).isZero();
    }

    @Test
    @DisplayName("SL-7 回收器：沙箱未启用 / 复用关闭 ⇒ 空报告（不碰槽池）")
    void reapShouldBeNoopWhenDisabled() {
        OpenSandboxProperties disabled = props(false);
        SandboxWorkspaceRegistry disabledRegistry = registry(disabled);

        OpenSandboxProperties noReuse = props(true);
        noReuse.setReuseEnabled(false);
        SandboxWorkspaceRegistry noReuseRegistry = registry(noReuse);

        assertThat(disabledRegistry.reapIdle().reaped()).isZero();
        assertThat(noReuseRegistry.reapIdle().reaped()).isZero();
        assertThat(disabledRegistry.activeCount()).isZero();
        assertThat(noReuseRegistry.activeCount()).isZero();
    }

    @Test
    @DisplayName("SL-7 回收器：空池 ⇒ 全零报告（幂等，可在任意周期触发）")
    void reapShouldBeIdempotentOnEmptyPool() {
        SandboxWorkspaceRegistry registry = registry(props(true));

        SandboxIdleReaper.ReapReport first = registry.reapIdle();
        SandboxIdleReaper.ReapReport second = registry.reapIdle();

        assertThat(first.reaped()).isZero();
        assertThat(first.busy()).isZero();
        assertThat(first.kept()).isZero();
        assertThat(second.kept()).isZero();
    }

    @Test
    @DisplayName("release：null / 未登记 / 一次性句柄 ⇒ 静默返回（幂等，不抛异常）")
    void releaseShouldBeIdempotent() {
        SandboxWorkspaceRegistry registry = registry(props(true));

        registry.release(null);
        registry.release(ref(999L));
        registry.release(WorkspaceRef.task(777L, null));
        // 一次性句柄不进池，释放无副作用
        registry.release(WorkspaceRef.oneShot(999L));

        assertThat(registry.activeCount()).isZero();
    }
}
