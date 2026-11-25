package com.helloai.core.agent.support;

import com.helloai.core.agent.entity.Agent;
import com.helloai.core.system.port.LlmProviderModelProfile;
import com.helloai.core.system.port.ModelCapabilities;
import com.helloai.core.system.service.LlmProviderModelQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * `VU-10` 图片能力闸的判定判据。
 *
 * <p>本类存在的理由：这个判定原先只长在对话面 {@code AgentChatClientServiceImpl}（`VU-9`），
 * 执行面没有任何闸（`ChatModelToolLoop` 无条件把 media 塞进请求体）。抽成共享策略后，
 * 两个消费面必须对同一个模型给出<b>同一个答案</b>——本类把这条固化成断言，
 * 其中最关键的是「fail-close」与「不抛异常」两条：前者防 provider 400，后者防主链失败。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VU-10 图片能力闸（唯一判定）")
class ImageInputCapabilityPolicyTest {

    @Mock
    private LlmProviderModelQueryService llmProviderModelQueryService;

    private ImageInputCapabilityPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ImageInputCapabilityPolicy(llmProviderModelQueryService);
    }

    @Test
    @DisplayName("模型声明 image 输入 ⇒ 支持")
    void shouldSupportWhenModelDeclaresImage() {
        when(llmProviderModelQueryService.findCapabilityProfileByModelType("kimi-k3"))
                .thenReturn(profile("text", "image"));

        assertThat(policy.supportsImageInput(agent("kimi-k3"))).isTrue();
    }

    @Test
    @DisplayName("模型只声明 text ⇒ 不支持（deepseek 全系正是此形态：V155 因 SDK 丢图而 fail-close）")
    void shouldNotSupportWhenTextOnly() {
        when(llmProviderModelQueryService.findCapabilityProfileByModelType("deepseek-v4-pro"))
                .thenReturn(profile("text"));

        assertThat(policy.supportsImageInput(agent("deepseek-v4-pro"))).isFalse();
    }

    @Test
    @DisplayName("查不到模型配置 ⇒ fail-close 判不支持（绝不「查不到就当支持」）")
    void shouldFailCloseWhenProfileMissing() {
        when(llmProviderModelQueryService.findCapabilityProfileByModelType(anyString())).thenReturn(null);

        assertThat(policy.supportsImageInput(agent("unknown-model"))).isFalse();
    }

    @Test
    @DisplayName("profile 存在但 capabilities 为 null ⇒ 不支持")
    void shouldNotSupportWhenCapabilitiesNull() {
        when(llmProviderModelQueryService.findCapabilityProfileByModelType(anyString()))
                .thenReturn(new LlmProviderModelProfile(null));

        assertThat(policy.supportsImageInput(agent("m"))).isFalse();
    }

    @Test
    @DisplayName("查询抛异常 ⇒ 降级为不支持且不冒泡（媒体是增强，不得让主链失败）")
    void shouldDegradeWithoutThrowingWhenQueryFails() {
        when(llmProviderModelQueryService.findCapabilityProfileByModelType(anyString()))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(policy.supportsImageInput(agent("m"))).isFalse();
    }

    @Test
    @DisplayName("agent 为 null 或 modelType 为空 ⇒ 不支持且不查库")
    void shouldNotSupportWithoutQueryingWhenAgentIncomplete() {
        assertThat(policy.supportsImageInput(null)).isFalse();
        assertThat(policy.supportsImageInput(agent(""))).isFalse();
        assertThat(policy.supportsImageInput(agent("   "))).isFalse();

        verifyNoInteractions(llmProviderModelQueryService);
    }

    @Test
    @DisplayName("空 modelType 不触发查询（先判空再查，避免无意义的库往返）")
    void shouldNotQueryWhenModelTypeBlank() {
        policy.supportsImageInput(agent(null));

        verify(llmProviderModelQueryService, never()).findCapabilityProfileByModelType(anyString());
    }

    @Test
    @DisplayName("降级文案本体在策略类（两消费面共用，禁各写一套）")
    void shouldOwnTheDegradeNote() {
        // 文案必须命中三条语义：说清「看不到」、禁止凭文件名推测、给出替代建议
        assertThat(ImageInputCapabilityPolicy.MEDIA_NOT_VISIBLE_NOTE)
                .contains("无法查看图像内容")
                .contains("推测图片内容")
                .contains("支持图片输入的模型");
    }

    // ---------- 辅助 ----------

    private static LlmProviderModelProfile profile(String... inputModalities) {
        return new LlmProviderModelProfile(ModelCapabilities.fromRaw(
                Map.of("input", List.of(inputModalities), "output", List.of("text"))));
    }

    private static Agent agent(String modelType) {
        Agent agent = new Agent();
        agent.setModelType(modelType);
        return agent;
    }
}