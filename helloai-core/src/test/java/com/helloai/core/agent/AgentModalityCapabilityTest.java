package com.helloai.core.agent;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.entity.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 模态能力建模：三类 accessType 默认 capabilities 均含图片/音频/视频理解键
 * （默认 false，模态能力取决于底层模型而非接入通道），注册覆盖后经 hasCapability 可读。
 */
@DisplayName("AgentModalityCapability")
class AgentModalityCapabilityTest {

    @Test
    @DisplayName("defaultCapabilities：三类 accessType 均含 3 个模态能力键且默认 false")
    void shouldDefaultModalityKeysToFalseForAllAccessTypes() {
        for (AgentAccessType type : AgentAccessType.values()) {
            Map<String, Object> caps = type.defaultCapabilities();
            assertThat(caps)
                    .containsEntry(AgentAccessType.CAP_SUPPORTS_IMAGE_UNDERSTANDING, false)
                    .containsEntry(AgentAccessType.CAP_SUPPORTS_AUDIO_UNDERSTANDING, false)
                    .containsEntry(AgentAccessType.CAP_SUPPORTS_VIDEO_UNDERSTANDING, false);
        }
    }

    @Test
    @DisplayName("AgentCapability 常量与键名一致；未声明视为 false，注册覆盖后可读为 true")
    void shouldExposeModalityConstantsAndReadOverrides() {
        assertThat(AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING).isEqualTo("supportsImageUnderstanding");
        assertThat(AgentCapability.SUPPORTS_AUDIO_UNDERSTANDING).isEqualTo("supportsAudioUnderstanding");
        assertThat(AgentCapability.SUPPORTS_VIDEO_UNDERSTANDING).isEqualTo("supportsVideoUnderstanding");

        Agent agent = new Agent();
        assertThat(AgentCapability.hasCapability(agent, AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING)).isFalse();

        Map<String, Object> merged = AgentCapability.mergeDefaults(AgentAccessType.API_KEY_LLM,
                Map.of(AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING, true));
        agent.setCapabilities(merged);
        assertThat(AgentCapability.hasCapability(agent, AgentCapability.SUPPORTS_IMAGE_UNDERSTANDING)).isTrue();
        assertThat(AgentCapability.hasCapability(agent, AgentCapability.SUPPORTS_VIDEO_UNDERSTANDING)).isFalse();
    }
}
