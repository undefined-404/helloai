package com.helloai.core.agent.service;

import com.helloai.common.base.BizException;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.mapper.AgentMapper;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link AgentSkillPolicyService#validateModelType} 入参校验的错误码契约（2026-10-05 修复）。
 *
 * <p><b>背景缺陷</b>：{@code new BizException(String)} 的默认 {@code code=500}，使 Agent 注册
 * 对「modelType 格式错误 / 模型不可用 / 角色下模型重复」三类可自纠入参错误全部返 HTTP 500；
 * 经 {@code GlobalExceptionHandler} 的 {@code code ∈ [400,600)} 分支透出后，客户端无法据状态码区分。
 * 本次修复显式携带错误码：格式/可用性 → 400，角色唯一性冲突 → 409。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentSkillPolicyService.validateModelType 错误码契约")
class AgentSkillPolicyServiceValidationTest {

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private LlmProviderModelQueryService llmProviderModelQueryService;

    @InjectMocks
    private AgentSkillPolicyService service;

    @Test
    @DisplayName("modelType 格式错误 → BizException(code=400)")
    void formatErrorIsBadRequest() {
        assertThatThrownBy(() -> service.validateModelType("badformat", AgentRole.EXECUTOR, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("modelType 格式错误")
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(400));
    }

    @Test
    @DisplayName("模型不可用 → BizException(code=400)")
    void unavailableModelIsBadRequest() {
        when(llmProviderModelQueryService.isModelAvailable("deepseek", "not-exist")).thenReturn(false);

        assertThatThrownBy(() -> service.validateModelType("deepseek:not-exist", AgentRole.EXECUTOR, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("模型不可用或已禁用")
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(400));
    }

    @Test
    @DisplayName("角色下模型重复 → BizException(code=409)")
    void duplicateModelInRoleIsConflict() {
        when(llmProviderModelQueryService.isModelAvailable("deepseek", "deepseek-v4-flash")).thenReturn(true);
        when(agentMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.validateModelType("deepseek:deepseek-v4-flash", AgentRole.EXECUTOR, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("同一角色下只能注册一个")
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo(409));
    }
}
