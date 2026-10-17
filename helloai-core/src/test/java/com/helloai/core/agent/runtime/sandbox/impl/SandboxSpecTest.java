package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.core.agent.runtime.sandbox.SandboxSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-1 声明式 SandboxSpec 单元测试（OpenSandbox 沙箱集成计划 §3/§4.2）。
 *
 * <p>验证 spec 是<b>纯声明</b>：字段与 OpenSandbox {@code POST /sandboxes} 请求体
 * 逐字对齐（image / entrypoint / resourceLimits(cpu,memory) / networkPolicy），
 * 可被 Provider（S-3）原样渲染，不内嵌任何提供方 API 调用参数。</p>
 */
@DisplayName("SandboxSpec 声明式规格")
class SandboxSpecTest {

    @Test
    @DisplayName("构造一份 opensandbox spec，字段与 OpenSandbox 请求体逐字对齐")
    void shouldHoldDeclarativeFields() {
        SandboxSpec spec = new SandboxSpec(
                "opensandbox",
                "python:3.12",
                List.of("tail", "-f", "/dev/null"),
                new SandboxSpec.ResourceLimits("500m", "512Mi"),
                new SandboxSpec.NetworkPolicy("deny", List.of()),
                SandboxSpec.Scope.AGENT,
                3600L);

        assertThat(spec.type()).isEqualTo("opensandbox");
        assertThat(spec.image()).isEqualTo("python:3.12");
        assertThat(spec.entrypoint()).containsExactly("tail", "-f", "/dev/null");
        assertThat(spec.resourceLimits().cpu()).isEqualTo("500m");
        assertThat(spec.resourceLimits().memory()).isEqualTo("512Mi");
        assertThat(spec.networkPolicy().defaultAction()).isEqualTo("deny");
        assertThat(spec.networkPolicy().egress()).isEmpty();
        assertThat(spec.scope()).isEqualTo(SandboxSpec.Scope.AGENT);
        assertThat(spec.timeoutSeconds()).isEqualTo(3600L);
    }

    @Test
    @DisplayName("K8s 风格字符串口径：cpu/memory 保留原样，不做数值换算")
    void shouldKeepCpuMemoryAsK8sStrings() {
        SandboxSpec.ResourceLimits limits = new SandboxSpec.ResourceLimits("500m", "512Mi");

        assertThat(limits.cpu()).isEqualTo("500m");
        assertThat(limits.memory()).isEqualTo("512Mi");
    }
}
