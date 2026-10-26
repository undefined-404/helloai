package com.helloai.core.agent.runtime.sandbox;

import com.helloai.common.config.OpenSandboxProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命名规格表（{@code spec-presets}）的<b>绑定契约</b>测试（`SKX-5`，2026-10-26）。
 *
 * <p><b>为什么需要它</b>：`application.yml` 里写的是 kebab-case（{@code spec-presets} /
 * {@code resource-limits}），Java 字段是 camelCase（{@code specPresets} / {@code resourceLimits}）。
 * 这类映射错了不会编译报错、单测也照样绿——只有在真启动时才炸，或更糟：静默不绑定，
 * 于是「明明配了 node 规格却跑了 python 容器」。这里用 {@link Binder} 直接对
 * {@code helloai.sandbox.opensandbox.*} 做一次绑定，把这份契约钉住。</p>
 *
 * <p>键名与 `helloai-start/src/main/resources/application.yml` 的 {@code spec-presets.node}
 * 保持逐字一致，改 yml 时本测试一并改。</p>
 */
@DisplayName("OpenSandboxProperties 命名规格绑定")
class OpenSandboxPropertiesPresetBindingTest {

    private static OpenSandboxProperties bind(MockEnvironment environment) {
        return Binder.get(environment)
                .bind("helloai.sandbox.opensandbox", Bindable.of(OpenSandboxProperties.class))
                .orElseGet(OpenSandboxProperties::new);
    }

    @Test
    @DisplayName("spec-presets.node 能绑定到 specPresets[\"node\"]（kebab → camel）")
    void shouldBindNodePreset() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("helloai.sandbox.opensandbox.spec.image", "python:3.12")
                .withProperty("helloai.sandbox.opensandbox.spec-presets.node.image", "node:20-bookworm-slim")
                .withProperty("helloai.sandbox.opensandbox.spec-presets.node.resource-limits.cpu", "1")
                .withProperty("helloai.sandbox.opensandbox.spec-presets.node.resource-limits.memory", "1Gi")
                .withProperty("helloai.sandbox.opensandbox.spec-presets.node.network-policy.default-action", "deny")
                .withProperty("helloai.sandbox.opensandbox.spec-presets.node.timeout-seconds", "900");

        OpenSandboxProperties properties = bind(environment);
        OpenSandboxProperties.Spec node = properties.findSpecPreset("node");

        assertThat(node).isNotNull();
        assertThat(node.getImage()).isEqualTo("node:20-bookworm-slim");
        assertThat(node.getResourceLimits().getCpu()).isEqualTo("1");
        assertThat(node.getResourceLimits().getMemory()).isEqualTo("1Gi");
        assertThat(node.getNetworkPolicy().getDefaultAction()).isEqualTo("deny");
        assertThat(node.getTimeoutSeconds()).isEqualTo(900L);
    }

    @Test
    @DisplayName("未指定的规格名 ⇒ 回落全局 spec（default 不重复定义）")
    void shouldFallbackToGlobalSpec() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("helloai.sandbox.opensandbox.spec.image", "python:3.12")
                .withProperty("helloai.sandbox.opensandbox.spec-presets.node.image", "node:20-bookworm-slim");

        OpenSandboxProperties properties = bind(environment);

        assertThat(properties.resolveSpec(null).getImage()).isEqualTo("python:3.12");
        assertThat(properties.resolveSpec("not-configured").getImage()).isEqualTo("python:3.12");
        assertThat(properties.resolveSpec("node").getImage()).isEqualTo("node:20-bookworm-slim");
        assertThat(properties.findSpecPreset("not-configured")).isNull();
    }
    @Test
    @DisplayName("skill-spec-preset 能绑定到 skillSpecPreset（SKX-8：技能规格的 kebab → camel）")
    void shouldBindSkillSpecPreset() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("helloai.sandbox.opensandbox.skill-spec-preset", "node")
                .withProperty("helloai.sandbox.opensandbox.spec-presets.node.image", "node:20-bookworm-slim");

        OpenSandboxProperties properties = bind(environment);

        assertThat(properties.getSkillSpecPreset()).isEqualTo("node");
        // 默认值也必须是指向真实预设的名字（配漏了会静默用 python 跑 node 技能）
        assertThat(new OpenSandboxProperties().getSkillSpecPreset()).isEqualTo("node");
    }
}
