package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S-7 OpenSandboxExecutionService 单元测试（沙箱执行链设计方案 §3.3）。
 *
 * <p><b>只覆盖契约层守卫</b>：不依赖真实 OpenSandbox server（enabled=true 会触发真实
 * SDK 建沙箱，属探针 / 集成测试范畴）。本测试验证：未启用 → 结构化失败；空命令 →
 * 结构化失败——即「失败也结构化返回、不抛异常」的容错哲学。</p>
 *
 * <p>真实执行路径（建沙箱 → commands.run → kill）由探针类真实环境用例覆盖
 * （复用 S-4 探针载体 + 未来 S-9 机械判定集成用例）。</p>
 */
@DisplayName("OpenSandboxExecutionService")
class OpenSandboxExecutionServiceTest {

    private OpenSandboxProperties props(boolean enabled) {
        OpenSandboxProperties props = new OpenSandboxProperties();
        props.setEnabled(enabled);
        props.setUrl("http://localhost:8090");
        props.setApiKey("dev-test-key");
        return props;
    }

    @Test
    @DisplayName("未启用 → 结构化失败（exitCode=-1，不抛异常）")
    void shouldReturnErrorWhenDisabled() {
        SandboxExecutionService service = new OpenSandboxExecutionService(props(false));

        ExecuteResponse response = service.execute(10L, "echo hello", null);

        assertThat(response.exitCode()).isEqualTo(ExecuteResponse.EXIT_CODE_ERROR);
        assertThat(response.output()).contains("disabled");
        assertThat(response.truncated()).isFalse();
        assertThat(response.succeeded()).isFalse();
    }

    @Test
    @DisplayName("空白命令 → 结构化失败（exitCode=-1，不抛异常）")
    void shouldReturnErrorWhenCommandBlank() {
        SandboxExecutionService service = new OpenSandboxExecutionService(props(true));

        ExecuteResponse response = service.execute(10L, "  ", 30);

        assertThat(response.exitCode()).isEqualTo(ExecuteResponse.EXIT_CODE_ERROR);
        assertThat(response.output()).contains("blank");
        assertThat(response.succeeded()).isFalse();
    }

    @Test
    @DisplayName("null 命令 → 结构化失败（exitCode=-1，不抛异常）")
    void shouldReturnErrorWhenCommandNull() {
        SandboxExecutionService service = new OpenSandboxExecutionService(props(true));

        ExecuteResponse response = service.execute(10L, null, 30);

        assertThat(response.exitCode()).isEqualTo(ExecuteResponse.EXIT_CODE_ERROR);
        assertThat(response.succeeded()).isFalse();
    }

    @Test
    @DisplayName("ExecuteResponse.succeeded()：exitCode=0 且未截断才为 true")
    void succeededSemantics() {
        assertThat(new ExecuteResponse("ok", 0, false).succeeded()).isTrue();
        // 超时（124）不算成功
        assertThat(new ExecuteResponse("timeout", ExecuteResponse.EXIT_CODE_TIMEOUT, false).succeeded()).isFalse();
        // 截断不算成功（输出不完整，不得据残缺输出下结论）
        assertThat(new ExecuteResponse("partial", 0, true).succeeded()).isFalse();
        // SDK 异常不算成功
        assertThat(new ExecuteResponse("err", ExecuteResponse.EXIT_CODE_ERROR, false).succeeded()).isFalse();
    }
}
