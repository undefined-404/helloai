package com.helloai.core.agent.runtime.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WorkspaceRef} 单测（`SL-8`，2026-11-13）。
 *
 * <p><b>为什么值得单测</b>：句柄的不变量（一次性 ⇔ 临时隔离、每种作用域唯一合法寿命）
 * 是槽键正确性的前提——非法组合若放过去，表现是「槽键对不上、悄悄多一个容器」，
 * 在真机上极难定位。构造期失败比运行期漂移便宜得多。</p>
 */
@DisplayName("WorkspaceRef")
class WorkspaceRefTest {

    @Test
    @DisplayName("任务级句柄：共享作用域 + 读写 + 活到任务终态；contextId 只作观测归属")
    void taskFactoryShouldBuildSharedHandle() {
        WorkspaceRef ref = WorkspaceRef.task(77L, 600L);

        assertThat(ref.kind()).isEqualTo(WorkspaceRef.Kind.TASK);
        assertThat(ref.ownerId()).isEqualTo(77L);
        assertThat(ref.contextId()).isEqualTo(600L);
        assertThat(ref.isolation()).isEqualTo(WorkspaceRef.Isolation.TASK_WORKSPACE);
        assertThat(ref.access()).isEqualTo(WorkspaceRef.Access.READ_WRITE);
        assertThat(ref.lifetime()).isEqualTo(WorkspaceRef.Lifetime.TASK);
        assertThat(ref.pooled()).isTrue();
        assertThat(ref.readOnly()).isFalse();
        assertThat(ref.key()).isEqualTo("task:77");
    }

    @Test
    @DisplayName("核验者句柄：任务级共享但只读意图（容器级只读随 SL-10）")
    void taskReadOnlyShouldCarryReadOnlyIntent() {
        WorkspaceRef ref = WorkspaceRef.taskReadOnly(77L, 600L);

        assertThat(ref.kind()).isEqualTo(WorkspaceRef.Kind.TASK);
        assertThat(ref.readOnly()).isTrue();
        assertThat(ref.pooled()).isTrue();
    }

    @Test
    @DisplayName("子任务句柄（回滚模式）：活到本轮结束；contextId 即自身")
    void subtaskFactoryShouldLiveForTurn() {
        WorkspaceRef ref = WorkspaceRef.subtask(42L);

        assertThat(ref.kind()).isEqualTo(WorkspaceRef.Kind.SUBTASK);
        assertThat(ref.lifetime()).isEqualTo(WorkspaceRef.Lifetime.TURN);
        assertThat(ref.contextId()).isEqualTo(42L);
        assertThat(ref.key()).isEqualTo("subtask:42");
        assertThat(ref.pooled()).isTrue();
    }

    @Test
    @DisplayName("会话技能句柄：作用域键与观测归属分离（contextId 缺省回落 scopeId）")
    void scopeFactoryShouldSeparateScopeAndContext() {
        WorkspaceRef ref = WorkspaceRef.scope(9L, 600L);

        assertThat(ref.kind()).isEqualTo(WorkspaceRef.Kind.SCOPE);
        assertThat(ref.ownerId()).isEqualTo(9L);
        assertThat(ref.contextId()).isEqualTo(600L);
        assertThat(ref.key()).isEqualTo("scope:9");
        // 无会话上下文时观测归属回落到作用域自身，避免 logs 里出现 null
        assertThat(WorkspaceRef.scope(9L, null).contextId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("一次性句柄：不进池 + 只读 + 只活本次调用；null 上下文用 NO_OWNER 占位")
    void oneShotFactoryShouldBeEphemeral() {
        WorkspaceRef ref = WorkspaceRef.oneShot(600L);

        assertThat(ref.kind()).isEqualTo(WorkspaceRef.Kind.ONESHOT);
        assertThat(ref.isolation()).isEqualTo(WorkspaceRef.Isolation.EPHEMERAL_VERIFY);
        assertThat(ref.lifetime()).isEqualTo(WorkspaceRef.Lifetime.CALL);
        assertThat(ref.readOnly()).isTrue();
        assertThat(ref.pooled()).isFalse();
        assertThat(ref.ownerId()).isEqualTo(600L);

        assertThat(WorkspaceRef.oneShot(null).ownerId()).isEqualTo(WorkspaceRef.NO_OWNER);
        assertThat(WorkspaceRef.oneShot(null).contextId()).isNull();
    }

    @Test
    @DisplayName("withSpecFingerprint：只收窄规格，其余字段逐字保留")
    void withSpecFingerprintShouldNarrowOnlyFingerprint() {
        WorkspaceRef base = WorkspaceRef.task(77L, 600L);
        WorkspaceRef narrowed = base.withSpecFingerprint("py3.12-node20");

        assertThat(narrowed.specFingerprint()).isEqualTo("py3.12-node20");
        assertThat(narrowed.kind()).isEqualTo(base.kind());
        assertThat(narrowed.ownerId()).isEqualTo(base.ownerId());
        assertThat(narrowed.contextId()).isEqualTo(base.contextId());
        assertThat(narrowed.lifetime()).isEqualTo(base.lifetime());
        // 收窄是返回新实例，不改原值
        assertThat(base.specFingerprint()).isNull();
    }

    @Test
    @DisplayName("不变量：ONESHOT 与 EPHEMERAL_VERIFY 必须同时出现")
    void oneShotAndEphemeralMustPair() {
        assertThatThrownBy(() -> new WorkspaceRef(WorkspaceRef.Kind.ONESHOT, 1L, 1L, null,
                WorkspaceRef.Isolation.TASK_WORKSPACE, WorkspaceRef.Access.READ_ONLY,
                WorkspaceRef.Lifetime.CALL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ONESHOT");

        assertThatThrownBy(() -> new WorkspaceRef(WorkspaceRef.Kind.TASK, 1L, 1L, null,
                WorkspaceRef.Isolation.EPHEMERAL_VERIFY, WorkspaceRef.Access.READ_WRITE,
                WorkspaceRef.Lifetime.TASK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EPHEMERAL_VERIFY");
    }

    @Test
    @DisplayName("不变量：每种作用域只有一种合法寿命（任务终态 / 本轮 / 本次调用）")
    void kindAndLifetimeMustPair() {
        assertThatThrownBy(() -> new WorkspaceRef(WorkspaceRef.Kind.TASK, 1L, 1L, null,
                WorkspaceRef.Isolation.TASK_WORKSPACE, WorkspaceRef.Access.READ_WRITE,
                WorkspaceRef.Lifetime.TURN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("寿命");

        assertThatThrownBy(() -> new WorkspaceRef(WorkspaceRef.Kind.SUBTASK, 1L, 1L, null,
                WorkspaceRef.Isolation.TASK_WORKSPACE, WorkspaceRef.Access.READ_WRITE,
                WorkspaceRef.Lifetime.TASK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("不变量：核心字段不得为 null（含 ownerId，槽键依赖它）")
    void coreFieldsMustNotBeNull() {
        assertThatThrownBy(() -> WorkspaceRef.task(null, 600L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ownerId");
        assertThatThrownBy(() -> new WorkspaceRef(null, 1L, 1L, null,
                WorkspaceRef.Isolation.TASK_WORKSPACE, WorkspaceRef.Access.READ_WRITE,
                WorkspaceRef.Lifetime.TASK))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("kind");
    }
}
