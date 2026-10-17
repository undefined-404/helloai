package com.helloai.core.agent.runtime.sandbox;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.tool.ToolContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SandboxExecutionToolContributor} 单测（S-8，沙箱执行链设计方案 §3.4 fail-closed 摘除）。
 *
 * <p>验证「按条件可用」声明的判定：{@code helloai.sandbox.opensandbox.enabled} 为 false（默认）
 * ⇒ {@code internal_run_in_sandbox} 从模型可见列表摘除；true ⇒ 可用。
 * 判定是纯函数（只读配置 boolean）、与上下文无关（null context 仍按配置判定）。</p>
 */
@DisplayName("SandboxExecutionToolContributor 条件可用（S-8 fail-closed）")
class SandboxExecutionToolContributorTest {

    private final OpenSandboxProperties properties = new OpenSandboxProperties();

    @Test
    @DisplayName("默认（enabled=false）：internal_run_in_sandbox 从模型可见列表摘除（fail-closed）")
    void shouldHideToolWhenDisabledByDefault() {
        properties.setEnabled(false);
        SandboxExecutionToolContributor contributor = new SandboxExecutionToolContributor(properties);

        Map<String, Predicate<ToolContext>> availability = contributor.toolAvailability();

        assertThat(availability).containsKey(SandboxExecutionToolContributor.TOOL_INTERNAL_RUN_IN_SANDBOX);
        // null context：与上下文无关的判定必须稳定（fail-open 契约：不知道 ≠ 不具备，但此处判定只取决于配置）
        assertThat(availability.get(SandboxExecutionToolContributor.TOOL_INTERNAL_RUN_IN_SANDBOX).test(null))
                .isFalse();
    }

    @Test
    @DisplayName("enabled=true：internal_run_in_sandbox 可用")
    void shouldShowToolWhenEnabled() {
        properties.setEnabled(true);
        SandboxExecutionToolContributor contributor = new SandboxExecutionToolContributor(properties);

        Map<String, Predicate<ToolContext>> availability = contributor.toolAvailability();

        assertThat(availability.get(SandboxExecutionToolContributor.TOOL_INTERNAL_RUN_IN_SANDBOX).test(null))
                .isTrue();
    }
}
