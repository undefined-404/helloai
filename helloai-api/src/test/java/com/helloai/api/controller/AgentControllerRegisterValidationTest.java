package com.helloai.api.controller;

import com.helloai.api.advice.GlobalExceptionHandler;
import com.helloai.api.support.AgentBaseUrlResolver;
import com.helloai.common.base.BizException;
import com.helloai.common.base.R;
import com.helloai.common.config.AgentConfigProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.LlmProviderCatalogService;
import com.helloai.core.system.crypto.AgentApiKeyCipher;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import com.helloai.core.system.service.PromptTemplateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 注册入参校验的错误码 / HTTP 状态契约（2026-10-05 修复）。
 *
 * <p><b>背景缺陷</b>：{@code POST /api/agents/register} 对「modelType 格式错误 / 模型不可用 /
 * 角色下模型重复」三类可自纠入参错误一律返 <b>HTTP 500</b>，客户端无法据状态码区分；
 * 根因是 {@code new BizException(String)} 默认 {@code code=500}。</p>
 *
 * <p><b>修复口径</b>：沿用项目既有统一异常处理（{@link GlobalExceptionHandler} 对
 * {@code BizException.code ∈ [400,600)} 落 HTTP 状态），把入参校验错误显式携带错误码——
 * 格式 / 可用性 → 400，角色唯一性冲突 → 409。本测试用「controller 抛异常 → GlobalExceptionHandler
 * 翻译 → HTTP 状态」还原真实链路（无 MockMvc，与 {@code AgentEventControllerTest} 同口径）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentController 注册入参校验：400/409 而非 500")
class AgentControllerRegisterValidationTest {

    @Mock
    private AgentService agentService;
    @Mock
    private AgentConfigProperties agentConfig;
    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private LlmProviderCatalogService llmProviderCatalogService;
    @Mock
    private LlmProviderModelQueryService llmProviderModelQueryService;
    @Mock
    private AgentBaseUrlResolver agentBaseUrlResolver;
    @Mock
    private AgentApiKeyCipher agentApiKeyCipher;

    @InjectMocks
    private AgentController controller;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 普通 JSON 请求（非 SSE），命中「R 包裹 + 设置 HTTP 状态码」分支。 */
    private HttpServletRequest jsonRequest() {
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        when(request.getDispatcherType()).thenReturn(jakarta.servlet.DispatcherType.REQUEST);
        when(request.getHeader(HttpHeaders.ACCEPT)).thenReturn(MediaType.APPLICATION_JSON_VALUE);
        return request;
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** 把 controller 抛出的异常交给统一异常处理器，返回落定的 HTTP 状态码。 */
    private int statusOf(Runnable call) {
        HttpServletResponse response = org.mockito.Mockito.mock(HttpServletResponse.class);
        try {
            call.run();
            throw new AssertionError("期望抛出 BizException，实际未抛");
        } catch (BizException e) {
            R<Void> r = handler.handleBizException(e, jsonRequest(), response);
            if (r.getCode() != null && r.getCode() >= 400 && r.getCode() < 600) {
                verify(response).setStatus(r.getCode());
            }
            return r.getCode();
        }
    }

    @Test
    @DisplayName("modelType 格式错误 → HTTP 400 + 可读消息")
    void illegalModelTypeFormatReturns400() {
        when(agentConfig.isAllowRegistration()).thenReturn(true);
        when(agentService.register(anyString(), any(), any())).thenReturn(newAgent());

        int code = statusOf(() -> controller.register(body(
                "name", "it-agent", "role", "EXECUTOR", "modelType", "badformat")));

        assertThat(code).isEqualTo(400);
    }

    @Test
    @DisplayName("模型不可用 → HTTP 400 + 可读消息")
    void unavailableModelReturns400() {
        when(agentConfig.isAllowRegistration()).thenReturn(true);
        when(agentService.register(anyString(), any(), any())).thenReturn(newAgent());
        when(llmProviderModelQueryService.isModelAvailable("deepseek", "deepseek-chat")).thenReturn(false);

        int code = statusOf(() -> controller.register(body(
                "name", "it-agent", "role", "EXECUTOR", "modelType", "deepseek:deepseek-chat")));

        assertThat(code).isEqualTo(400);
    }

    @Test
    @DisplayName("角色下模型重复 → HTTP 409 + 可读消息")
    void duplicateModelInRoleReturns409() {
        when(agentConfig.isAllowRegistration()).thenReturn(true);
        doThrow(new BizException(409, "角色 EXECUTOR 已存在使用模型 deepseek-v4-flash 的Agent，同一模型在同一角色下只能注册一个"))
                .when(agentService).validateModelType(any(), any(), any());

        int code = statusOf(() -> controller.register(body(
                "name", "it-agent", "role", "EXECUTOR", "modelType", "deepseek:deepseek-v4-flash")));

        assertThat(code).isEqualTo(409);
    }

    @Test
    @DisplayName("技能超出模型白名单 → HTTP 400，且不创建 Agent（不再残留脏 Agent）")
    void unsupportedSkillReturns400AndCreatesNothing() {
        when(agentConfig.isAllowRegistration()).thenReturn(true);
        doThrow(new BizException(400, "模型 deepseek:deepseek-v4-pro 不支持技能: web-search"))
                .when(agentService).validateAgentSkills(any(), any());

        int code = statusOf(() -> controller.register(body(
                "name", "it-agent", "role", "EXECUTOR",
                "modelType", "deepseek:deepseek-v4-pro",
                "skills", List.of("web-search"))));

        assertThat(code).isEqualTo(400);
        // 回归点：校验前置于创建，因此任何 register 路径都不得被触达 ——
        // 一旦触达，applyRegistrationExtras 抛错后就会留下
        // access_type=CLI_CLIENT / model_type=NULL 的孤儿行（2026-10-10 修复）。
        verify(agentService, never()).register(anyString(), any(), any());
        verify(agentService, never()).registerOrGet(anyString(), any(), any());
    }

    @Test
    @DisplayName("role 非法/缺失 → HTTP 400（不再 NPE 500 / 泄漏枚举类路径）")
    void illegalRoleReturns400() {
        when(agentConfig.isAllowRegistration()).thenReturn(true);

        assertThatThrownBy(() -> controller.register(body("name", "it-agent", "role", "FOO")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("role 取值非法");
        assertThat(statusOf(() -> controller.register(body("name", "it-agent", "role", "FOO")))).isEqualTo(400);
    }

    @Test
    @DisplayName("name 空白/缺失 → HTTP 400（不再建出无法删除的无名 Agent）")
    void blankNameReturns400() {
        when(agentConfig.isAllowRegistration()).thenReturn(true);

        assertThatThrownBy(() -> controller.register(body("name", "   ", "role", "EXECUTOR")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("name 不能为空");
        assertThat(statusOf(() -> controller.register(body("name", "   ", "role", "EXECUTOR")))).isEqualTo(400);
        assertThat(statusOf(() -> controller.register(body("role", "EXECUTOR")))).isEqualTo(400);
    }

    @Test
    @DisplayName("合法入参 → R.ok（code=200，零回归）")
    void validInputReturns200() {
        when(agentConfig.isAllowRegistration()).thenReturn(true);
        when(agentService.register(anyString(), any(), any())).thenReturn(newAgent());
        when(agentApiKeyCipher.decrypt(any())).thenReturn("it-key");

        R<?> result = controller.register(body("name", "it-agent", "role", "EXECUTOR"));

        assertThat(result.getCode()).isEqualTo(200);
    }

    private Agent newAgent() {
        Agent agent = new Agent();
        agent.setId(940001L);
        agent.setName("it-agent");
        agent.setRole(AgentRole.EXECUTOR);
        agent.setAccessType(AgentAccessType.CLI_CLIENT);
        agent.setApiKey("enc:it");
        return agent;
    }
}
