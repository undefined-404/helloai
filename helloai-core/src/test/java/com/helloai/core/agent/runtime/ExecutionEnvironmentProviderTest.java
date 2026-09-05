package com.helloai.core.agent.runtime;

import com.helloai.common.constant.AgentAccessType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行环境解析器单元测试（Phase 1 Step 4）：accessType → ExecutionEnvironment
 * 路由契约（与 AgentExecutorRouter 同构的 supports 过滤语义）。纯对象测试，
 * 直接以真实实现列表装配（两个环境实现均为无状态轻量组件，无需 mock）。
 */
@DisplayName("ExecutionEnvironmentProvider")
class ExecutionEnvironmentProviderTest {

    private final ExecutionEnvironmentProvider provider = new ExecutionEnvironmentProvider(
            List.of(new RemoteAgentEnvironment(), new LocalProcessEnvironment()));

    @Test
    @DisplayName("resolve：CLI_CLIENT → remote-agent（外部 Agent 自有环境执行）")
    void shouldResolveRemoteAgentForCliClient() {
        ExecutionEnvironment environment = provider.resolve(AgentAccessType.CLI_CLIENT);
        assertThat(environment).isNotNull();
        assertThat(environment.name()).isEqualTo(RemoteAgentEnvironment.NAME);
    }

    @Test
    @DisplayName("resolve：WEB_BROWSER → remote-agent（网页版 AI 桥接，外部 AI 服务执行）")
    void shouldResolveRemoteAgentForWebBrowser() {
        assertThat(provider.resolve(AgentAccessType.WEB_BROWSER).name())
                .isEqualTo(RemoteAgentEnvironment.NAME);
    }

    @Test
    @DisplayName("resolve：API_KEY_LLM → local-process（平台进程内 ChatClient 直调）")
    void shouldResolveLocalProcessForApiKeyLlm() {
        assertThat(provider.resolve(AgentAccessType.API_KEY_LLM).name())
                .isEqualTo(LocalProcessEnvironment.NAME);
    }

    @Test
    @DisplayName("resolve：null accessType → null（契约：不抛异常，Phase 0 语义兼容）")
    void shouldReturnNullForNullAccessType() {
        assertThat(provider.resolve(null)).isNull();
    }

    @Test
    @DisplayName("supports：两个实现互斥覆盖全部接入类型，无重叠路由")
    void shouldHaveDisjointSupportsAcrossEnvironments() {
        RemoteAgentEnvironment remote = new RemoteAgentEnvironment();
        LocalProcessEnvironment local = new LocalProcessEnvironment();
        for (AgentAccessType accessType : AgentAccessType.values()) {
            int hits = (remote.supports(accessType) ? 1 : 0) + (local.supports(accessType) ? 1 : 0);
            assertThat(remote.supports(accessType) || local.supports(accessType))
                    .as("accessType=%s 应被恰好一个环境承接", accessType)
                    .isTrue();
            assertThat(remote.supports(accessType) && local.supports(accessType))
                    .as("accessType=%s 不应被两个环境同时承接", accessType)
                    .isFalse();
            assertThat(hits).isEqualTo(1);
        }
        assertThat(remote.supports(null)).isFalse();
        assertThat(local.supports(null)).isFalse();
    }
}
