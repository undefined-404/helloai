package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.SandboxSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SandboxSpecMapper} 单测（`SKX-5`，2026-10-26）。
 *
 * <p><b>为什么单独测</b>：规格指纹是「同一子任务能不能复用到错镜像的沙箱」的唯一防线，
 * 而它只是纯字符串逻辑——不需要连沙箱就能确定性覆盖。真正建沙箱的部分属探针 / e2e 范畴。</p>
 */
@DisplayName("SandboxSpecMapper")
class SandboxSpecMapperTest {

    private static OpenSandboxProperties.Spec configSpec() {
        OpenSandboxProperties.Spec spec = new OpenSandboxProperties.Spec();
        spec.setType("opensandbox");
        spec.setImage("node:20-bookworm-slim");
        spec.setEntrypoint(List.of("tail", "-f", "/dev/null"));
        OpenSandboxProperties.ResourceLimits limits = new OpenSandboxProperties.ResourceLimits();
        limits.setCpu("500m");
        limits.setMemory("512Mi");
        spec.setResourceLimits(limits);
        OpenSandboxProperties.NetworkPolicy network = new OpenSandboxProperties.NetworkPolicy();
        network.setDefaultAction("deny");
        network.setEgress(List.of("example.com"));
        spec.setNetworkPolicy(network);
        spec.setScope("fixed");
        spec.setTimeoutSeconds(900L);
        return spec;
    }

    @Test
    @DisplayName("配置规格 → 领域规格：字段逐一映射（scope 解析为枚举）")
    void shouldMapAllFields() {
        SandboxSpec spec = SandboxSpecMapper.toDomain(configSpec());

        assertThat(spec.type()).isEqualTo("opensandbox");
        assertThat(spec.image()).isEqualTo("node:20-bookworm-slim");
        assertThat(spec.entrypoint()).containsExactly("tail", "-f", "/dev/null");
        assertThat(spec.resourceLimits().cpu()).isEqualTo("500m");
        assertThat(spec.resourceLimits().memory()).isEqualTo("512Mi");
        assertThat(spec.networkPolicy().defaultAction()).isEqualTo("deny");
        assertThat(spec.networkPolicy().egress()).containsExactly("example.com");
        assertThat(spec.scope()).isEqualTo(SandboxSpec.Scope.FIXED);
        assertThat(spec.timeoutSeconds()).isEqualTo(900L);
    }

    @Test
    @DisplayName("scope 解析：大小写不敏感，未知值回落 AGENT（不因配置错字崩执行链）")
    void shouldParseScopeLeniently() {
        assertThat(SandboxSpecMapper.parseScope("user")).isEqualTo(SandboxSpec.Scope.USER);
        assertThat(SandboxSpecMapper.parseScope("  AgEnT ")).isEqualTo(SandboxSpec.Scope.AGENT);
        assertThat(SandboxSpecMapper.parseScope("nonsense")).isEqualTo(SandboxSpec.Scope.AGENT);
        assertThat(SandboxSpecMapper.parseScope(null)).isEqualTo(SandboxSpec.Scope.AGENT);
    }

    @Test
    @DisplayName("指纹：同规格稳定相等，16 位十六进制")
    void fingerprintShouldBeStable() {
        SandboxSpec a = SandboxSpecMapper.toDomain(configSpec());
        SandboxSpec b = SandboxSpecMapper.toDomain(configSpec());

        assertThat(SandboxSpecMapper.fingerprint(a))
                .isEqualTo(SandboxSpecMapper.fingerprint(b))
                .hasSize(16)
                .matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("指纹：镜像不同 ⇒ 不同（同子任务跨镜像不能复用同一槽）")
    void fingerprintShouldDifferByImage() {
        SandboxSpec node = SandboxSpecMapper.toDomain(configSpec());
        OpenSandboxProperties.Spec pythonConfig = configSpec();
        pythonConfig.setImage("python:3.12");

        assertThat(SandboxSpecMapper.fingerprint(node))
                .isNotEqualTo(SandboxSpecMapper.fingerprint(SandboxSpecMapper.toDomain(pythonConfig)));
    }

    @Test
    @DisplayName("指纹：资源 / 入口 / 网络 / 超时任一不同 ⇒ 不同")
    void fingerprintShouldDifferByBuildRelevantFields() {
        String base = SandboxSpecMapper.fingerprint(SandboxSpecMapper.toDomain(configSpec()));

        OpenSandboxProperties.Spec moreMemory = configSpec();
        moreMemory.getResourceLimits().setMemory("1Gi");
        assertThat(SandboxSpecMapper.fingerprint(SandboxSpecMapper.toDomain(moreMemory))).isNotEqualTo(base);

        OpenSandboxProperties.Spec otherEntrypoint = configSpec();
        otherEntrypoint.setEntrypoint(List.of("sleep", "infinity"));
        assertThat(SandboxSpecMapper.fingerprint(SandboxSpecMapper.toDomain(otherEntrypoint))).isNotEqualTo(base);

        OpenSandboxProperties.Spec allowAll = configSpec();
        allowAll.getNetworkPolicy().setDefaultAction("allow");
        assertThat(SandboxSpecMapper.fingerprint(SandboxSpecMapper.toDomain(allowAll))).isNotEqualTo(base);

        OpenSandboxProperties.Spec longerTtl = configSpec();
        longerTtl.setTimeoutSeconds(3600L);
        assertThat(SandboxSpecMapper.fingerprint(SandboxSpecMapper.toDomain(longerTtl))).isNotEqualTo(base);
    }

    @Test
    @DisplayName("指纹：scope 不参与（当前未被消费，算进去会让同容器各占一槽）")
    void fingerprintShouldIgnoreScope() {
        OpenSandboxProperties.Spec agentScope = configSpec();
        agentScope.setScope("agent");
        OpenSandboxProperties.Spec fixedScope = configSpec();
        fixedScope.setScope("fixed");

        assertThat(SandboxSpecMapper.fingerprint(SandboxSpecMapper.toDomain(agentScope)))
                .isEqualTo(SandboxSpecMapper.fingerprint(SandboxSpecMapper.toDomain(fixedScope)));
    }

    @Test
    @DisplayName("null 规格 ⇒ 指纹固定为 default（回落全局默认路径）")
    void fingerprintShouldHandleNull() {
        assertThat(SandboxSpecMapper.fingerprint(null)).isEqualTo("default");
    }
}
