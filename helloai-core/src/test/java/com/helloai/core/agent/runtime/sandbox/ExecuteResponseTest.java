package com.helloai.core.agent.runtime.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExecuteResponse} 单测（`SL-4`，2026-11-13）。
 *
 * <p>只覆盖新增的第 4 分量 {@code callId} 的兼容语义：三参构造（旧调用零改动）⇒
 * {@code callId=null}；{@code withCallId} 回填其余分量原样保留、不覆盖已有 ID。</p>
 */
@DisplayName("ExecuteResponse")
class ExecuteResponseTest {

    @Test
    @DisplayName("三参构造：callId 缺省 null（既有调用与断言零改动）")
    void legacyConstructorShouldLeaveCallIdNull() {
        ExecuteResponse response = new ExecuteResponse("out", 0, false);

        assertThat(response.callId()).isNull();
        assertThat(response.output()).isEqualTo("out");
        assertThat(response.succeeded()).isTrue();
    }

    @Test
    @DisplayName("withCallId：其余分量不变；已有 callId 不被覆盖")
    void withCallIdShouldPreserveOtherComponents() {
        ExecuteResponse response = new ExecuteResponse("out", 124, true).withCallId("c7");

        assertThat(response.callId()).isEqualTo("c7");
        assertThat(response.output()).isEqualTo("out");
        assertThat(response.exitCode()).isEqualTo(ExecuteResponse.EXIT_CODE_TIMEOUT);
        assertThat(response.truncated()).isTrue();
        assertThat(response.withCallId("c9").callId()).isEqualTo("c7");
    }

    @Test
    @DisplayName("succeeded()：exitCode=0 且未截断才为 true")
    void succeededShouldRequireZeroExitAndFullOutput() {
        assertThat(new ExecuteResponse("ok", 0, false).succeeded()).isTrue();
        // 超时（124）不算成功
        assertThat(new ExecuteResponse("timeout", ExecuteResponse.EXIT_CODE_TIMEOUT, false).succeeded()).isFalse();
        // 截断不算成功（输出不完整，不得据残缺输出下结论）
        assertThat(new ExecuteResponse("partial", 0, true).succeeded()).isFalse();
        // SDK 异常不算成功
        assertThat(new ExecuteResponse("err", ExecuteResponse.EXIT_CODE_ERROR, false).succeeded()).isFalse();
    }

    @Test
    @DisplayName("digest()：sha256 前缀 + 与输出字节一致 + null 输出按空串")
    void digestShouldFollowOutputBytes() {
        assertThat(new ExecuteResponse("abc", 0, false).digest())
                .isEqualTo("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(new ExecuteResponse(null, 0, false).digest())
                .isEqualTo(new ExecuteResponse("", 0, false).digest());
        assertThat(new ExecuteResponse("abc", 0, false).digest())
                .isNotEqualTo(new ExecuteResponse("abd", 0, false).digest());
    }
}
