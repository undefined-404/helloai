package com.helloai.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.core.agent.service.impl.LlmProviderKeyVerifyServiceImpl;
import com.helloai.core.agent.service.LlmProviderKeyVerifyService;
import com.helloai.core.agent.service.PlatformProviderConfigService;
import com.helloai.core.system.entity.LlmProvider;
import com.helloai.core.system.service.LlmProviderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * LlmProviderKeyVerifyService 前置守卫单测（V59 API Key 连通性验证）。
 *
 * <p>锁定"不发请求即失败"的本地守卫路径：Provider 不存在 / Key 未配置 /
 * Base URL 未配置 / 默认模型缺失，均收敛为 success=false + 可读 message，
 * 绝不抛异常（前端可直接展示）。真实 HTTP 探测由
 * {@code scripts/powershell/verify-api-key-verify.ps1} 冒烟覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
class LlmProviderKeyVerifyServiceTest {

    private static final Long PROVIDER_ID = 99L;
    private static final String PROVIDER_CODE = "verify-probe";

    @Mock
    private LlmProviderService providerService;
    @Mock
    private PlatformProviderConfigService platformProviderConfigService;

    private LlmProviderKeyVerifyService verifyService;

    @BeforeEach
    void setUp() {
        verifyService = new LlmProviderKeyVerifyServiceImpl(
                providerService, platformProviderConfigService, new ObjectMapper());
    }

    private LlmProvider provider(String defaultModel) {
        LlmProvider p = new LlmProvider();
        p.setId(PROVIDER_ID);
        p.setProviderCode(PROVIDER_CODE);
        p.setProtocolType("OPENAI_COMPATIBLE");
        p.setDefaultModel(defaultModel);
        return p;
    }

    @Test
    @DisplayName("verifyById：Provider 不存在时返回失败结果，不抛异常")
    void verifyById_providerNotFound_returnsFailure() {
        when(providerService.getById(PROVIDER_ID)).thenReturn(null);

        Map<String, Object> result = verifyService.verifyById(PROVIDER_ID);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat((String) result.get("message")).contains("不存在");
    }

    @Test
    @DisplayName("verifyById：API Key 未配置时返回失败并提示先保存密钥")
    void verifyById_apiKeyMissing_returnsFailure() {
        when(providerService.getById(PROVIDER_ID)).thenReturn(provider("probe-model"));
        when(platformProviderConfigService.getApiKey(PROVIDER_CODE)).thenReturn(null);

        Map<String, Object> result = verifyService.verifyById(PROVIDER_ID);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat((String) result.get("message")).contains("API Key");
    }

    @Test
    @DisplayName("verifyById：Base URL 未配置时返回失败")
    void verifyById_baseUrlMissing_returnsFailure() {
        when(providerService.getById(PROVIDER_ID)).thenReturn(provider("probe-model"));
        when(platformProviderConfigService.getApiKey(PROVIDER_CODE)).thenReturn("sk-test");
        when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn(" ");

        Map<String, Object> result = verifyService.verifyById(PROVIDER_ID);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat((String) result.get("message")).contains("Base URL");
    }

    @Test
    @DisplayName("verifyById：默认模型缺失且无兜底时返回失败，不发起请求")
    void verifyById_modelMissing_returnsFailure() {
        when(providerService.getById(PROVIDER_ID)).thenReturn(provider(null));
        when(platformProviderConfigService.getApiKey(PROVIDER_CODE)).thenReturn("sk-test");
        when(platformProviderConfigService.getBaseUrl(PROVIDER_CODE)).thenReturn("https://probe.example.com");
        when(platformProviderConfigService.getDefaultModel(PROVIDER_CODE)).thenReturn(null);

        Map<String, Object> result = verifyService.verifyById(PROVIDER_ID);

        assertThat(result.get("success")).isEqualTo(false);
        assertThat((String) result.get("message")).contains("默认模型");
        assertThat(result.get("model")).isNull();
    }
}
