package com.helloai.core.agent.chat;

import com.helloai.core.agent.entity.Agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentProviderResolver")
class AgentProviderResolverTest {

    @Nested
    @DisplayName("resolveProvider")
    class ResolveProvider {

        @Test
        @DisplayName("标准格式 deepseek:deepseek-chat → deepseek")
        void shouldExtractProviderFromProviderColonModel() {
            Agent agent = new Agent();
            agent.setModelType("deepseek:deepseek-chat");

            String result = AgentProviderResolver.resolveProvider(agent, "fallback");

            assertThat(result).isEqualTo("deepseek");
        }

        @Test
        @DisplayName("标准格式 openai:gpt-4 → openai")
        void shouldExtractOpenAiProvider() {
            Agent agent = new Agent();
            agent.setModelType("openai:gpt-4");

            String result = AgentProviderResolver.resolveProvider(agent, "fallback");

            assertThat(result).isEqualTo("openai");
        }

        @Test
        @DisplayName("modelType 为 null 时返回 fallback")
        void shouldReturnFallbackWhenModelTypeIsNull() {
            Agent agent = new Agent();

            String result = AgentProviderResolver.resolveProvider(agent, "deepseek");

            assertThat(result).isEqualTo("deepseek");
        }

        @Test
        @DisplayName("modelType 为空字符串时返回 fallback")
        void shouldReturnFallbackWhenModelTypeIsBlank() {
            Agent agent = new Agent();
            agent.setModelType("  ");

            String result = AgentProviderResolver.resolveProvider(agent, "deepseek");

            assertThat(result).isEqualTo("deepseek");
        }

        @Test
        @DisplayName("modelType 不含冒号时整串当 provider 返回")
        void shouldReturnWholeStringAsProviderWhenNoColon() {
            Agent agent = new Agent();
            agent.setModelType("deepseek");

            String result = AgentProviderResolver.resolveProvider(agent, "fallback");

            assertThat(result).isEqualTo("deepseek");
        }
    }

    @Nested
    @DisplayName("resolveModel")
    class ResolveModel {

        @Test
        @DisplayName("标准格式 deepseek:deepseek-chat → deepseek-chat")
        void shouldExtractModelFromProviderColonModel() {
            Agent agent = new Agent();
            agent.setModelType("deepseek:deepseek-chat");

            String result = AgentProviderResolver.resolveModel(agent, "fallback-model");

            assertThat(result).isEqualTo("deepseek-chat");
        }

        @Test
        @DisplayName("openai:gpt-4-turbo → gpt-4-turbo")
        void shouldExtractGptModel() {
            Agent agent = new Agent();
            agent.setModelType("openai:gpt-4-turbo");

            String result = AgentProviderResolver.resolveModel(agent, null);

            assertThat(result).isEqualTo("gpt-4-turbo");
        }

        @Test
        @DisplayName("modelType 为 null 时返回 fallback")
        void shouldReturnFallbackWhenModelTypeIsNull() {
            Agent agent = new Agent();

            String result = AgentProviderResolver.resolveModel(agent, "fallback-model");

            assertThat(result).isEqualTo("fallback-model");
        }

        @Test
        @DisplayName("modelType 为空字符串时返回 fallback")
        void shouldReturnFallbackWhenModelTypeIsBlank() {
            Agent agent = new Agent();
            agent.setModelType("  ");

            String result = AgentProviderResolver.resolveModel(agent, "fallback-model");

            assertThat(result).isEqualTo("fallback-model");
        }

        @Test
        @DisplayName("冒号后无模型名 deepseek: → null（交由 factory 用默认值）")
        void shouldReturnNullWhenNoModelAfterColon() {
            Agent agent = new Agent();
            agent.setModelType("deepseek:");

            String result = AgentProviderResolver.resolveModel(agent, "fallback-model");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("只有冒号时 → null")
        void shouldReturnNullWhenOnlyColon() {
            Agent agent = new Agent();
            agent.setModelType(":");

            String result = AgentProviderResolver.resolveModel(agent, null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("不含冒号时 → null（无法确定是 provider 还是 model）")
        void shouldReturnNullWhenNoColon() {
            Agent agent = new Agent();
            agent.setModelType("deepseek");

            String result = AgentProviderResolver.resolveModel(agent, null);

            assertThat(result).isNull();
        }
    }
}
