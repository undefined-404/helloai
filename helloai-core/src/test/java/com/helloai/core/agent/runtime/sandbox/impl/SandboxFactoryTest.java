package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.SandboxSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SandboxFactory} 单测（`SL-9`，2026-11-13）。
 *
 * <p><b>只覆盖失败消息契约</b>：{@code create} 会触发真实 SDK，属探针 / 集成测试范畴。
 * 这里钉住的是 `LOG-20261027-026` 引入的可观测性：失败消息必须带出 SDK 原始 cause 与连接配置
 * 摘要，且 <b>永不打印 apiKey 值</b>——那是一条安全约束，值得被测试锁死。</p>
 */
@DisplayName("SandboxFactory")
class SandboxFactoryTest {

    private OpenSandboxProperties props(boolean enabled) {
        OpenSandboxProperties props = new OpenSandboxProperties();
        props.setEnabled(enabled);
        props.setUrl("http://localhost:8090");
        props.setApiKey("dev-test-key");
        return props;
    }

    @Test
    @DisplayName("建沙箱失败消息：带出 SDK 原始 cause + 连接配置摘要（不再是 opener returned null）")
    void createFailureMessageShouldCarryRealCause() {
        SandboxFactory factory = new SandboxFactory(props(true));

        String message = factory.describeCreateFailure(
                new IllegalStateException("Server error : 500 DOCKER::SANDBOX_START_FAILED"));

        // ① 真实 cause 必须在：这正是旧实现丢掉、让我们把「配置缺失」误判成「镜像问题」的那一段
        assertThat(message).contains("IllegalStateException")
                .contains("SANDBOX_START_FAILED");
        // ② 配置摘要必须在：用于区分「env 没生效」与「沙箱服务本身不健康」
        assertThat(message).contains("url=http://localhost:8090")
                .contains("apiKey=已配置")
                .contains("enabled=true");
    }

    @Test
    @DisplayName("建沙箱失败消息：url / apiKey 未配置时明确标注，且绝不打印 apiKey 明文")
    void createFailureMessageShouldNeverLeakApiKey() {
        OpenSandboxProperties props = props(true);
        props.setUrl(null);
        props.setApiKey("super-secret-key-value");
        SandboxFactory factory = new SandboxFactory(props);

        String message = factory.describeCreateFailure(new IllegalStateException("boom"));

        assertThat(message).contains("url=未配置").contains("apiKey=已配置");
        // 安全约束：只报「是否配置」，值永不入日志/界面
        assertThat(message).doesNotContain("super-secret-key-value");
    }

    @Test
    @DisplayName("建沙箱失败消息：detail 缺失时回落历史文案（调用方与旧排障文档的对应关系不断）")
    void createFailureMessageShouldFallbackToLegacyText() {
        assertThat(SandboxFactory.createFailedMessage(null))
                .isEqualTo("sandbox create failed (opener returned null)");
        assertThat(SandboxFactory.createFailedMessage("  "))
                .isEqualTo("sandbox create failed (opener returned null)");
        assertThat(SandboxFactory.createFailedMessage("sandbox create failed: X: y"))
                .isEqualTo("sandbox create failed: X: y");
    }

    @Test
    @DisplayName("沙箱寿命上限：spec 显式值优先，否则回落 900s")
    void sandboxTimeoutShouldPreferSpec() {
        assertThat(SandboxFactory.resolveSandboxTimeoutSeconds(null)).isEqualTo(900L);
        assertThat(SandboxFactory.resolveSandboxTimeoutSeconds(
                new SandboxSpec("opensandbox", "python:3.12", List.of(), null, null,
                        SandboxSpec.Scope.FIXED, 1800L)))
                .isEqualTo(1800L);
    }
}
