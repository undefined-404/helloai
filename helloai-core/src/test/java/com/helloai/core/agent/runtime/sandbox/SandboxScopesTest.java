package com.helloai.core.agent.runtime.sandbox;

import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * `SL-1` SandboxScopes 单元测试（2026-11-12）。
 *
 * <p><b>覆盖边界</b>：解析三态（命中子任务 → {@code TASK(父任务ID)} / 未命中 → {@code SCOPE(原ID)}
 * / 查询异常保守回落 {@code SCOPE}）、null 入参、LRU 命中不重复查库、{@code invalidateTask}
 * 只清目标任务的 {@code TASK} 条目。</p>
 *
 * <p><b>不覆盖</b>：槽级释放本身（{@code removeMatching} 谓词依赖真实 SDK 建槽，属
 * `SandboxWorkspaceRegistry` 守卫层与 `SL-6` 真机验证范畴，与既有测试注释口径一致）。</p>
 */
class SandboxScopesTest {

    private final SubTaskQueryPort port = mock(SubTaskQueryPort.class);

    /** 构造最小快照（仅 id / taskId 有意义，其余字段按投影「按需填充」惯例置 null）。 */
    private static SubTaskSnapshot subTask(Long id, Long taskId) {
        return new SubTaskSnapshot(id, null, taskId, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("命中子任务 ⇒ 解析为 TASK(父任务ID)，槽键升级为任务级共享")
    void hitSubTaskResolvesToTask() {
        when(port.findById(100L)).thenReturn(subTask(100L, 7L));
        SandboxScopes scopes = new SandboxScopes(port);

        SandboxScopes.Resolved resolved = scopes.resolve(100L);

        assertThat(resolved.kind()).isEqualTo(SandboxScopes.Kind.TASK);
        assertThat(resolved.id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("未命中（findById 返回 null）⇒ 回落 SCOPE(原ID)，保留会话技能直接作用域语义")
    void missFallsBackToScope() {
        when(port.findById(200L)).thenReturn(null);
        SandboxScopes scopes = new SandboxScopes(port);

        SandboxScopes.Resolved resolved = scopes.resolve(200L);

        assertThat(resolved).isEqualTo(SandboxScopes.Resolved.scope(200L));
    }

    @Test
    @DisplayName("查询抛异常 ⇒ 保守回落 SCOPE，DB 抖动不阻断执行")
    void queryErrorFallsBackToScope() {
        when(port.findById(300L)).thenThrow(new RuntimeException("db 抖动"));
        SandboxScopes scopes = new SandboxScopes(port);

        assertThat(scopes.resolve(300L)).isEqualTo(SandboxScopes.Resolved.scope(300L));
    }

    @Test
    @DisplayName("null 入参 ⇒ null，且不触达查询端口")
    void nullContextResolvesToNull() {
        SandboxScopes scopes = new SandboxScopes(port);

        assertThat(scopes.resolve(null)).isNull();
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("LRU 缓存：同 ID 连续解析只查一次库（第二次起命中缓存）")
    void cacheHitSkipsQuery() {
        when(port.findById(400L)).thenReturn(subTask(400L, 9L));
        SandboxScopes scopes = new SandboxScopes(port);

        scopes.resolve(400L);
        scopes.resolve(400L);
        scopes.resolve(400L);

        verify(port, times(1)).findById(400L);
        assertThat(scopes.resolve(400L)).isEqualTo(SandboxScopes.Resolved.task(9L));
    }

    @Test
    @DisplayName("invalidateTask 只清目标任务下的 TASK 条目；SCOPE 与其它任务条目不受影响")
    void invalidateTaskOnlyClearsTargetTask() {
        when(port.findById(500L)).thenReturn(subTask(500L, 9L));
        when(port.findById(501L)).thenReturn(subTask(501L, 10L));
        when(port.findById(502L)).thenReturn(null);
        SandboxScopes scopes = new SandboxScopes(port);

        scopes.resolve(500L);   // TASK(9)
        scopes.resolve(501L);   // TASK(10)
        scopes.resolve(502L);   // SCOPE(502)

        scopes.invalidateTask(9L);

        // 失效后重新解析 500L ⇒ 缓存已清，须重查库
        assertThat(scopes.resolve(500L)).isEqualTo(SandboxScopes.Resolved.task(9L));
        verify(port, times(2)).findById(500L);
        // 其它条目未受影响：不产生新查询
        verify(port, times(1)).findById(501L);
        verify(port, times(1)).findById(502L);
    }

    @Test
    @DisplayName("invalidateTask(null) 静默忽略，不触达查询端口")
    void invalidateNullIsNoop() {
        SandboxScopes scopes = new SandboxScopes(port);

        scopes.invalidateTask(null);

        verifyNoInteractions(port);
    }
}