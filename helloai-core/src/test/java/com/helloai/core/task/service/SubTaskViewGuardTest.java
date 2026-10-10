package com.helloai.core.task.service;

import com.helloai.common.base.BizException;
import com.helloai.core.task.policy.AttachmentVisibilityPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SubTaskViewGuard} 单元测试（2026-10-10 视图读判据统一）。
 *
 * <p>本类只做「判定 → 403」收口，判定一律委托 {@link AttachmentVisibilityPolicy}；
 * 故此处只钉两件事：① 放行/拒绝的转译正确；② 判据**确实**走的是 policy（不是自己重排一套）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubTaskViewGuard 子任务视图读收口")
class SubTaskViewGuardTest {

    private static final long SUB_TASK_ID = 500L;
    private static final long AGENT_ID = 7L;

    @Mock
    private AttachmentVisibilityPolicy visibilityPolicy;

    @InjectMocks
    private SubTaskViewGuard guard;

    @Test
    @DisplayName("团队成员（policy 放行）→ 不抛异常")
    void passesWhenPolicyAllows() {
        when(visibilityPolicy.canReadTaskScoped(AGENT_ID, SUB_TASK_ID)).thenReturn(true);

        assertThatCode(() -> guard.assertAgentCanView(SUB_TASK_ID, AGENT_ID)).doesNotThrowAnyException();
        verify(visibilityPolicy).canReadTaskScoped(AGENT_ID, SUB_TASK_ID);
    }

    @Test
    @DisplayName("非成员（policy 拒绝）→ 403 + 可读原因")
    void throws403WhenPolicyDenies() {
        when(visibilityPolicy.canReadTaskScoped(AGENT_ID, SUB_TASK_ID)).thenReturn(false);

        assertThatThrownBy(() -> guard.assertAgentCanView(SUB_TASK_ID, AGENT_ID))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不在该任务的团队成员范围内")
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((BizException) e).getCode())
                        .isEqualTo(403));
    }

    @Test
    @DisplayName("agentId 为 null → 403（平台通道不得走本判据，交给管理侧鉴权）")
    void throws403WhenAgentNull() {
        when(visibilityPolicy.canReadTaskScoped(null, SUB_TASK_ID)).thenReturn(false);

        assertThatThrownBy(() -> guard.assertAgentCanView(SUB_TASK_ID, null))
                .isInstanceOf(BizException.class);
    }
}
