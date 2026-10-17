package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.core.agent.runtime.sandbox.ExecutionPolicy;
import com.helloai.core.agent.runtime.sandbox.SandboxSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-2 诚实五边界策略单元测试（OpenSandbox 沙箱集成计划 §4.3）。
 *
 * <p>验证 {@link ExecutionPolicy#openSandbox()} 的诚实边界标注：
 * filesystem / resource / credential 由平台强制隔离 → ISOLATED；
 * network / process 存在真实限制但有共享面 → PARTIAL；
 * 不标全 ISOLATED（防「安全沙箱已完成」误读）。</p>
 */
@DisplayName("ExecutionPolicy.openSandbox 诚实五边界")
class OpenSandboxExecutionPolicyTest {

    @Test
    @DisplayName("filesystem/resource/credential = ISOLATED（平台强制隔离）")
    void shouldMarkPlatformForcedBoundariesIsolated() {
        ExecutionPolicy policy = ExecutionPolicy.openSandbox();

        assertThat(policy.filesystem()).isEqualTo(ExecutionPolicy.IsolationLevel.ISOLATED);
        assertThat(policy.resource()).isEqualTo(ExecutionPolicy.IsolationLevel.ISOLATED);
        assertThat(policy.credential()).isEqualTo(ExecutionPolicy.IsolationLevel.ISOLATED);
    }

    @Test
    @DisplayName("network/process = PARTIAL（有真实限制但存在共享面，不标 ISOLATED）")
    void shouldMarkSharedSurfacesPartial() {
        ExecutionPolicy policy = ExecutionPolicy.openSandbox();

        assertThat(policy.network()).isEqualTo(ExecutionPolicy.IsolationLevel.PARTIAL);
        assertThat(policy.process()).isEqualTo(ExecutionPolicy.IsolationLevel.PARTIAL);
    }

    @Test
    @DisplayName("诚实性守护：五边界不出现 NONE（真实沙箱，非本地直跑）")
    void shouldNeverMarkNone() {
        ExecutionPolicy policy = ExecutionPolicy.openSandbox();

        assertThat(policy.filesystem()).isNotEqualTo(ExecutionPolicy.IsolationLevel.NONE);
        assertThat(policy.network()).isNotEqualTo(ExecutionPolicy.IsolationLevel.NONE);
        assertThat(policy.process()).isNotEqualTo(ExecutionPolicy.IsolationLevel.NONE);
        assertThat(policy.resource()).isNotEqualTo(ExecutionPolicy.IsolationLevel.NONE);
        assertThat(policy.credential()).isNotEqualTo(ExecutionPolicy.IsolationLevel.NONE);
    }
}
