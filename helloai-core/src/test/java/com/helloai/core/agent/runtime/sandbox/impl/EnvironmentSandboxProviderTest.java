package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.runtime.ExecutionEnvironmentProvider;
import com.helloai.core.agent.runtime.LocalProcessEnvironment;
import com.helloai.core.agent.runtime.RemoteAgentEnvironment;
import com.helloai.core.agent.runtime.sandbox.ExecutionPolicy;
import com.helloai.core.agent.runtime.sandbox.Sandbox;
import com.helloai.core.agent.runtime.sandbox.SandboxContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 沙箱提供方第一阶段实现单元测试（P0-C Phase 4）。
 *
 * <p>验证 {@link EnvironmentSandboxProvider} 的环境解析透传与<b>诚实策略</b>：
 * 当前实现一律不标 ISOLATED（无真实安全沙箱，差距表 §6 口径），
 * remote-agent 网络边界为天然 PARTIAL、local-process 全 NONE。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnvironmentSandboxProvider")
class EnvironmentSandboxProviderTest {

    @Mock
    private ExecutionEnvironmentProvider environmentProvider;

    @Test
    @DisplayName("CLI_CLIENT → remote-agent + network=PARTIAL 其余 NONE（诚实策略）")
    void shouldResolveRemoteAgentWithHonestPolicy() {
        when(environmentProvider.resolve(AgentAccessType.CLI_CLIENT))
                .thenReturn(new RemoteAgentEnvironment());

        Sandbox sandbox = new EnvironmentSandboxProvider(environmentProvider)
                .resolve(new SandboxContext(AgentAccessType.CLI_CLIENT, 1L, 10L, 3L));

        assertThat(sandbox).isNotNull();
        assertThat(sandbox.providerName()).isEqualTo(EnvironmentSandboxProvider.PROVIDER_NAME);
        assertThat(sandbox.environment().name()).isEqualTo(RemoteAgentEnvironment.NAME);
        assertThat(sandbox.policy().network()).isEqualTo(ExecutionPolicy.IsolationLevel.PARTIAL);
        assertThat(sandbox.policy().filesystem()).isEqualTo(ExecutionPolicy.IsolationLevel.NONE);
        assertThat(sandbox.policy().process()).isEqualTo(ExecutionPolicy.IsolationLevel.NONE);
        assertThat(sandbox.policy().resource()).isEqualTo(ExecutionPolicy.IsolationLevel.NONE);
        assertThat(sandbox.policy().credential()).isEqualTo(ExecutionPolicy.IsolationLevel.NONE);
    }

    @Test
    @DisplayName("API_KEY_LLM → local-process + 全 NONE（平台内直跑无隔离）")
    void shouldResolveLocalProcessWithNoIsolation() {
        when(environmentProvider.resolve(AgentAccessType.API_KEY_LLM))
                .thenReturn(new LocalProcessEnvironment());

        Sandbox sandbox = new EnvironmentSandboxProvider(environmentProvider)
                .resolve(new SandboxContext(AgentAccessType.API_KEY_LLM, null, null, null));

        assertThat(sandbox).isNotNull();
        assertThat(sandbox.environment().name()).isEqualTo(LocalProcessEnvironment.NAME);
        assertThat(sandbox.policy()).isEqualTo(ExecutionPolicy.noIsolation());
    }

    @Test
    @DisplayName("accessType 为 null → null（契约：不抛异常）")
    void shouldReturnNullWhenAccessTypeNull() {
        assertThat(new EnvironmentSandboxProvider(environmentProvider)
                .resolve(new SandboxContext(null, 1L, 10L, 3L))).isNull();
    }

    @Test
    @DisplayName("context 为 null → null")
    void shouldReturnNullWhenContextNull() {
        assertThat(new EnvironmentSandboxProvider(environmentProvider).resolve(null)).isNull();
    }

    @Test
    @DisplayName("无环境命中 → null")
    void shouldReturnNullWhenNoEnvironmentMatched() {
        when(environmentProvider.resolve(AgentAccessType.WEB_BROWSER)).thenReturn(null);

        assertThat(new EnvironmentSandboxProvider(environmentProvider)
                .resolve(new SandboxContext(AgentAccessType.WEB_BROWSER, 1L, 10L, 3L))).isNull();
    }

    @Test
    @DisplayName("诚实性守护：所有环境策略均无 ISOLATED（不宣称安全沙箱）")
    void shouldNeverClaimIsolated() {
        when(environmentProvider.resolve(AgentAccessType.CLI_CLIENT))
                .thenReturn(new RemoteAgentEnvironment());
        when(environmentProvider.resolve(AgentAccessType.API_KEY_LLM))
                .thenReturn(new LocalProcessEnvironment());

        EnvironmentSandboxProvider provider = new EnvironmentSandboxProvider(environmentProvider);
        Sandbox remote = provider.resolve(new SandboxContext(AgentAccessType.CLI_CLIENT, null, null, null));
        Sandbox local = provider.resolve(new SandboxContext(AgentAccessType.API_KEY_LLM, null, null, null));

        assertThat(allNoneOrPartial(remote.policy())).isTrue();
        assertThat(allNoneOrPartial(local.policy())).isTrue();
    }

    private static boolean allNoneOrPartial(ExecutionPolicy policy) {
        return policy.filesystem() != ExecutionPolicy.IsolationLevel.ISOLATED
                && policy.network() != ExecutionPolicy.IsolationLevel.ISOLATED
                && policy.process() != ExecutionPolicy.IsolationLevel.ISOLATED
                && policy.resource() != ExecutionPolicy.IsolationLevel.ISOLATED
                && policy.credential() != ExecutionPolicy.IsolationLevel.ISOLATED;
    }
}
