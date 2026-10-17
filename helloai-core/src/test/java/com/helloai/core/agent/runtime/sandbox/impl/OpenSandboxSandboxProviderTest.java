package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.runtime.OpenSandboxEnvironment;
import com.helloai.core.agent.runtime.sandbox.ExecutionPolicy;
import com.helloai.core.agent.runtime.sandbox.Sandbox;
import com.helloai.core.agent.runtime.sandbox.SandboxContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-3 OpenSandboxSandboxProvider 单元测试（沙箱集成计划 §3）。
 *
 * <p>验证：enabled=true 且 context 就绪时返回带诚实五边界策略的 Sandbox；
 * enabled=false → null（沿既有 null 契约）；scope=agent 复用 + 显式回收。</p>
 */
@DisplayName("OpenSandboxSandboxProvider")
class OpenSandboxSandboxProviderTest {

    private final OpenSandboxEnvironment environment = new OpenSandboxEnvironment();

    private OpenSandboxProperties enabledProps() {
        OpenSandboxProperties props = new OpenSandboxProperties();
        props.setEnabled(true);
        props.setUrl("http://localhost:8090");
        props.setApiKey("dev-test-key");
        return props;
    }

    @Test
    @DisplayName("enabled=true → 返回 opensandbox + 诚实五边界（非全 NONE）")
    void shouldResolveOpenSandboxWhenEnabled() {
        OpenSandboxSandboxProvider provider =
                new OpenSandboxSandboxProvider(enabledProps(), environment);

        Sandbox sandbox = provider.resolve(
                new SandboxContext(AgentAccessType.API_KEY_LLM, 1L, 10L, 3L));

        assertThat(sandbox).isNotNull();
        assertThat(sandbox.providerName()).isEqualTo(OpenSandboxSandboxProvider.PROVIDER_NAME);
        assertThat(sandbox.environment().name()).isEqualTo(OpenSandboxEnvironment.NAME);
        // 诚实五边界：filesystem/resource/credential=ISOLATED，network/process=PARTIAL
        assertThat(sandbox.policy().filesystem()).isEqualTo(ExecutionPolicy.IsolationLevel.ISOLATED);
        assertThat(sandbox.policy().resource()).isEqualTo(ExecutionPolicy.IsolationLevel.ISOLATED);
        assertThat(sandbox.policy().credential()).isEqualTo(ExecutionPolicy.IsolationLevel.ISOLATED);
        assertThat(sandbox.policy().network()).isEqualTo(ExecutionPolicy.IsolationLevel.PARTIAL);
        assertThat(sandbox.policy().process()).isEqualTo(ExecutionPolicy.IsolationLevel.PARTIAL);
        // 不标全 ISOLATED：存在共享面（network/process），诚实标注为 PARTIAL
        assertThat(sandbox.policy()).isEqualTo(ExecutionPolicy.openSandbox());
    }

    @Test
    @DisplayName("enabled=false → null（沿既有 null 契约，不抛异常）")
    void shouldReturnNullWhenDisabled() {
        OpenSandboxProperties props = enabledProps();
        props.setEnabled(false);
        OpenSandboxSandboxProvider provider =
                new OpenSandboxSandboxProvider(props, environment);

        assertThat(provider.resolve(
                new SandboxContext(AgentAccessType.API_KEY_LLM, 1L, 10L, 3L))).isNull();
    }

    @Test
    @DisplayName("accessType 为 null → null")
    void shouldReturnNullWhenAccessTypeNull() {
        OpenSandboxSandboxProvider provider =
                new OpenSandboxSandboxProvider(enabledProps(), environment);

        assertThat(provider.resolve(
                new SandboxContext(null, 1L, 10L, 3L))).isNull();
    }

    @Test
    @DisplayName("context 为 null → null")
    void shouldReturnNullWhenContextNull() {
        OpenSandboxSandboxProvider provider =
                new OpenSandboxSandboxProvider(enabledProps(), environment);

        assertThat(provider.resolve(null)).isNull();
    }

    @Test
    @DisplayName("scope=agent：同 Agent 复用登记，release 显式回收（幂等）")
    void shouldReuseAndReleasePerAgent() {
        OpenSandboxSandboxProvider provider =
                new OpenSandboxSandboxProvider(enabledProps(), environment);

        SandboxContext ctx = new SandboxContext(AgentAccessType.API_KEY_LLM, 1L, 10L, 3L);
        provider.resolve(ctx);
        provider.resolve(ctx);
        assertThat(provider.activeAgentScopes()).isEqualTo(1); // 同 Agent 只登记 1 个作用域

        provider.release(3L);
        assertThat(provider.activeAgentScopes()).isZero();

        // 幂等：重复 release 不抛异常
        provider.release(3L);
        assertThat(provider.activeAgentScopes()).isZero();

        // null 幂等
        provider.release(null);
    }
}
