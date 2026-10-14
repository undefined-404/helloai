package com.helloai.api.controller;

import com.helloai.common.config.AgentConfigProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.common.constant.AgentRole;
import com.helloai.core.agent.AgentCapability;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.AgentService;
import com.helloai.core.agent.service.LlmProviderCatalogService;
import com.helloai.api.support.AgentBaseUrlResolver;
import com.helloai.core.system.crypto.AgentApiKeyCipher;
import com.helloai.core.system.port.LlmProviderModelProfile;
import com.helloai.core.system.port.ModelCapabilities;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import com.helloai.core.system.service.PromptTemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code agent.capabilities} 模态三键由模型能力谓词收敛（T8）单测。
 *
 * <p>规则 = 客户端声明 <b>AND</b> 模型支持（只缩不放）；模型无对应模态（含无 modelType / 未识别）⇒ false；
 * {@code pdf} 等未知模态 token 不影响三键。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentControllerModalityConvergenceTest {

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

    private static final String MODEL = "deepseek:deepseek-v4-flash";

    private static LlmProviderModelProfile profile(String... inputModalities) {
        return new LlmProviderModelProfile(
                new ModelCapabilities(List.of(inputModalities), List.of("text"), true, true, false));
    }

    private Agent newAgent() {
        Agent agent = new Agent();
        agent.setId(950001L);
        agent.setName("it-agent");
        agent.setRole(AgentRole.EXECUTOR);
        return agent;
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> modalityOverride(boolean image, boolean audio, boolean video) {
        Map<String, Object> m = new HashMap<>();
        m.put(AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING, image);
        m.put(AgentCapability.SUPPORTS_AUDIO_UNDERSTANDING, audio);
        m.put(AgentCapability.SUPPORTS_VIDEO_UNDERSTANDING, video);
        return m;
    }

    /** 走注册链路并捕获落库前的 Agent。 */
    private Map<String, Object> registerAndCaptureCapabilities(Map<String, Object> reqBody) {
        Agent agent = newAgent();
        when(agentConfig.isAllowRegistration()).thenReturn(true);
        when(agentService.register(anyString(), any(), any())).thenReturn(agent);
        controller.register(reqBody);
        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agentService).updateAgentExtras(captor.capture());
        return captor.getValue().getCapabilities();
    }

    @Test
    @DisplayName("T8①：模型支持 image 且客户端传 true ⇒ 保持 true（客户端传 false ⇒ false）")
    void imageSupportedRespectsClientChoice() {
        when(llmProviderModelQueryService.isModelAvailable("deepseek", "deepseek-v4-flash")).thenReturn(true);
        when(llmProviderModelQueryService.findCapabilityProfileByModelType(MODEL))
                .thenReturn(profile("text", "image"));

        Map<String, Object> caps = registerAndCaptureCapabilities(body(
                "name", "it-agent", "role", "EXECUTOR", "accessType", "API_KEY_LLM",
                "modelType", MODEL, "capabilities", modalityOverride(true, true, true)));

        assertThat(caps.get(AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING)).isEqualTo(true);
        assertThat(caps.get(AgentCapability.SUPPORTS_AUDIO_UNDERSTANDING)).isEqualTo(false);
        assertThat(caps.get(AgentCapability.SUPPORTS_VIDEO_UNDERSTANDING)).isEqualTo(false);
    }

    @Test
    @DisplayName("T8②：模型支持 image 但客户端传 false ⇒ 保持 false（只缩不放）")
    void imageSupportedButClientFalse() {
        when(llmProviderModelQueryService.isModelAvailable("deepseek", "deepseek-v4-flash")).thenReturn(true);
        when(llmProviderModelQueryService.findCapabilityProfileByModelType(MODEL))
                .thenReturn(profile("text", "image"));

        Map<String, Object> caps = registerAndCaptureCapabilities(body(
                "name", "it-agent", "role", "EXECUTOR", "accessType", "API_KEY_LLM",
                "modelType", MODEL, "capabilities", modalityOverride(false, false, false)));

        assertThat(caps.get(AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING)).isEqualTo(false);
    }

    @Test
    @DisplayName("T8③：模型不支持 image 而客户端传 true ⇒ 落 false（收敛）")
    void imageUnsupportedConvergesToFalse() {
        when(llmProviderModelQueryService.isModelAvailable("deepseek", "deepseek-v4-flash")).thenReturn(true);
        when(llmProviderModelQueryService.findCapabilityProfileByModelType(MODEL))
                .thenReturn(profile("text"));

        Map<String, Object> caps = registerAndCaptureCapabilities(body(
                "name", "it-agent", "role", "EXECUTOR", "accessType", "API_KEY_LLM",
                "modelType", MODEL, "capabilities", modalityOverride(true, true, true)));

        assertThat(caps.get(AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING)).isEqualTo(false);
        assertThat(caps.get(AgentCapability.SUPPORTS_AUDIO_UNDERSTANDING)).isEqualTo(false);
        assertThat(caps.get(AgentCapability.SUPPORTS_VIDEO_UNDERSTANDING)).isEqualTo(false);
    }

    @Test
    @DisplayName("T8④：模型支持 video ⇒ video 落 true（逐模态独立收敛）")
    void videoSupportedConvergesToTrue() {
        when(llmProviderModelQueryService.isModelAvailable("deepseek", "deepseek-v4-flash")).thenReturn(true);
        when(llmProviderModelQueryService.findCapabilityProfileByModelType(MODEL))
                .thenReturn(profile("text", "video"));

        Map<String, Object> caps = registerAndCaptureCapabilities(body(
                "name", "it-agent", "role", "EXECUTOR", "accessType", "API_KEY_LLM",
                "modelType", MODEL, "capabilities", modalityOverride(true, true, true)));

        assertThat(caps.get(AgentCapability.SUPPORTS_VIDEO_UNDERSTANDING)).isEqualTo(true);
        assertThat(caps.get(AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING)).isEqualTo(false);
        assertThat(caps.get(AgentCapability.SUPPORTS_AUDIO_UNDERSTANDING)).isEqualTo(false);
    }

    @Test
    @DisplayName("T8⑤：pdf 等未知模态 token 不影响三键（只认 image/audio/video）")
    void unknownModalityTokenIgnored() {
        when(llmProviderModelQueryService.isModelAvailable("deepseek", "deepseek-v4-flash")).thenReturn(true);
        when(llmProviderModelQueryService.findCapabilityProfileByModelType(MODEL))
                .thenReturn(profile("text", "image", "pdf"));

        Map<String, Object> caps = registerAndCaptureCapabilities(body(
                "name", "it-agent", "role", "EXECUTOR", "accessType", "API_KEY_LLM",
                "modelType", MODEL, "capabilities", modalityOverride(true, true, true)));

        assertThat(caps.get(AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING)).isEqualTo(true);
        assertThat(caps.get(AgentCapability.SUPPORTS_AUDIO_UNDERSTANDING)).isEqualTo(false);
        assertThat(caps.get(AgentCapability.SUPPORTS_VIDEO_UNDERSTANDING)).isEqualTo(false);
    }

    @Test
    @DisplayName("T8⑥：无 modelType（CLI_CLIENT）⇒ 三键一律 false（fail-closed，只缩不放）")
    void noModelTypeConvergesAllFalse() {
        Map<String, Object> caps = registerAndCaptureCapabilities(body(
                "name", "it-agent", "role", "EXECUTOR", "accessType", "CLI_CLIENT",
                "capabilities", modalityOverride(true, true, true)));

        assertThat(caps.get(AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING)).isEqualTo(false);
        assertThat(caps.get(AgentCapability.SUPPORTS_AUDIO_UNDERSTANDING)).isEqualTo(false);
        assertThat(caps.get(AgentCapability.SUPPORTS_VIDEO_UNDERSTANDING)).isEqualTo(false);
    }

    @Test
    @DisplayName("T8⑦：modelType 已识别失败（profile=null）⇒ 三键一律 false")
    void unrecognizedModelConvergesAllFalse() {
        when(llmProviderModelQueryService.isModelAvailable("legacy", "old-model")).thenReturn(true);
        when(llmProviderModelQueryService.findCapabilityProfileByModelType("legacy:old-model")).thenReturn(null);

        Map<String, Object> caps = registerAndCaptureCapabilities(body(
                "name", "it-agent", "role", "EXECUTOR", "accessType", "API_KEY_LLM",
                "modelType", "legacy:old-model", "capabilities", modalityOverride(true, true, true)));

        assertThat(caps.get(AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING)).isEqualTo(false);
        assertThat(caps.get(AgentCapability.SUPPORTS_VIDEO_UNDERSTANDING)).isEqualTo(false);
    }

    @Test
    @DisplayName("T8⑧：其余能力键不受收敛影响（supportsPull/maxConcurrentTasks 原样保留）")
    void otherCapabilityKeysUntouched() {
        when(llmProviderModelQueryService.isModelAvailable("deepseek", "deepseek-v4-flash")).thenReturn(true);
        when(llmProviderModelQueryService.findCapabilityProfileByModelType(MODEL))
                .thenReturn(profile("text", "image"));

        Map<String, Object> override = modalityOverride(true, false, false);
        override.put("maxConcurrentTasks", 9);
        Map<String, Object> caps = registerAndCaptureCapabilities(body(
                "name", "it-agent", "role", "EXECUTOR", "accessType", "API_KEY_LLM",
                "modelType", MODEL, "capabilities", override));

        assertThat(caps.get("maxConcurrentTasks")).isEqualTo(9);
        assertThat(caps.get(AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING)).isEqualTo(true);
    }
}
