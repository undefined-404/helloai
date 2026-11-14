package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxCallLedger;
import com.helloai.core.agent.runtime.sandbox.SandboxRuntime;
import com.helloai.core.agent.runtime.sandbox.WorkspaceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OpenSandboxRuntime} 单测（`SL-9`，2026-11-13）。
 *
 * <p><b>只覆盖守卫与降级契约</b>：{@code enabled=true} 且参数合法的路径会触发真实 SDK 建沙箱，
 * 属探针 / 集成测试范畴。本类验证：未启用 → 结构化失败；空命令 → 结构化失败；
 * 读 / 写 / 探测 / 列目录在无活跃工作区时一律降级——即「失败也结构化返回、不抛异常」的容错哲学。</p>
 */
@DisplayName("OpenSandboxRuntime")
class OpenSandboxRuntimeTest {

    private OpenSandboxProperties props(boolean enabled) {
        OpenSandboxProperties props = new OpenSandboxProperties();
        props.setEnabled(enabled);
        props.setUrl("http://localhost:8090");
        props.setApiKey("dev-test-key");
        return props;
    }

    /** 构造被测运行时；台账与时间线端口用真实 / 空实现（守卫层用例不触发二者）。 */
    private OpenSandboxRuntime newRuntime(OpenSandboxProperties props) {
        return runtime(props, new SandboxWorkspaceRegistry(props, new SandboxFactory(props)));
    }

    /** 与给定注册表共用的运行时（用例需要断言槽池在册数时用）。 */
    private OpenSandboxRuntime runtime(OpenSandboxProperties props, SandboxWorkspaceRegistry registry) {
        TaskTimelinePort noopTimeline = (taskId, subTaskId, eventType, role, agentId, payload) -> {
        };
        return new OpenSandboxRuntime(props, new SandboxFactory(props), registry,
                new SandboxObservation(new SandboxCallLedger(), noopTimeline));
    }

    /** 句柄（`SL-8`）：守卫层用例统一按子任务级句柄构造，不关心作用域解析。 */
    private static WorkspaceRef ref(Long subTaskId) {
        return WorkspaceRef.subtask(subTaskId);
    }

    @Test
    @DisplayName("未启用 → 结构化失败（exitCode=-1，不抛异常）")
    void shouldReturnErrorWhenDisabled() {
        OpenSandboxRuntime runtime = newRuntime(props(false));

        ExecuteResponse response = runtime.exec(ref(10L), "echo hello", null);

        assertThat(response.exitCode()).isEqualTo(ExecuteResponse.EXIT_CODE_ERROR);
        assertThat(response.output()).contains("disabled");
        assertThat(response.truncated()).isFalse();
        assertThat(response.succeeded()).isFalse();
    }

    @Test
    @DisplayName("空白命令 → 结构化失败（exitCode=-1，不抛异常）")
    void shouldReturnErrorWhenCommandBlank() {
        OpenSandboxRuntime runtime = newRuntime(props(true));

        ExecuteResponse response = runtime.exec(ref(10L), "  ", 30);

        assertThat(response.exitCode()).isEqualTo(ExecuteResponse.EXIT_CODE_ERROR);
        assertThat(response.output()).contains("blank");
        assertThat(response.succeeded()).isFalse();
    }

    @Test
    @DisplayName("null 命令 → 结构化失败（exitCode=-1，不抛异常）")
    void shouldReturnErrorWhenCommandNull() {
        OpenSandboxRuntime runtime = newRuntime(props(true));

        ExecuteResponse response = runtime.exec(ref(10L), null, 30);

        assertThat(response.exitCode()).isEqualTo(ExecuteResponse.EXIT_CODE_ERROR);
        assertThat(response.succeeded()).isFalse();
    }

    @Test
    @DisplayName("SL-7 命令超时：缺省 300s（30s 会让一次 npm install 就丢工作区）、上限 300s")
    void timeoutShouldDefaultTo300AndClamp() {
        assertThat(OpenSandboxRuntime.resolveTimeoutSeconds(null)).isEqualTo(300L);
        assertThat(OpenSandboxRuntime.resolveTimeoutSeconds(0)).isEqualTo(300L);
        assertThat(OpenSandboxRuntime.resolveTimeoutSeconds(-5)).isEqualTo(300L);
        assertThat(OpenSandboxRuntime.resolveTimeoutSeconds(10)).isEqualTo(10L);
        assertThat(OpenSandboxRuntime.resolveTimeoutSeconds(9_999)).isEqualTo(300L);
    }

    @Test
    @DisplayName("未启用 / 命令空白时不得登记任何沙箱（守卫先于建沙箱）")
    void guardShouldNotRegisterSandbox() {
        OpenSandboxProperties disabledProps = props(false);
        SandboxWorkspaceRegistry disabledRegistry = new SandboxWorkspaceRegistry(
                disabledProps, new SandboxFactory(disabledProps));
        runtime(disabledProps, disabledRegistry).exec(ref(10L), "echo hi", 30);
        disabledRegistry.release(ref(10L));

        OpenSandboxProperties enabledProps = props(true);
        SandboxWorkspaceRegistry enabledRegistry = new SandboxWorkspaceRegistry(
                enabledProps, new SandboxFactory(enabledProps));
        runtime(enabledProps, enabledRegistry).exec(ref(10L), "   ", 30);
        enabledRegistry.release(ref(10L));

        assertThat(disabledRegistry.activeCount()).isZero();
        assertThat(enabledRegistry.activeCount()).isZero();
    }

    @Test
    @DisplayName("回读：未启用 / null 句柄 / 一次性句柄 / 非法路径 ⇒ null（不建沙箱、不抛异常）")
    void readFileShouldReturnNullOnGuards() {
        OpenSandboxRuntime disabled = newRuntime(props(false));
        OpenSandboxRuntime enabled = newRuntime(props(true));

        assertThat(disabled.readFile(ref(10L), "/workspace/out/x.html", 0)).isNull();
        assertThat(enabled.readFile(null, "/workspace/out/x.html", 0)).isNull();
        // 一次性句柄不进池 ⇒ 只读通道无从定位容器
        assertThat(enabled.readFile(WorkspaceRef.oneShot(10L), "/workspace/out/x.html", 0)).isNull();
        // 相对路径 / 越界路径一律拒绝（不按 /workspace 补全——静默读到另一个文件比明确失败更难查）
        assertThat(enabled.readFile(ref(10L), "out/x.html", 0)).isNull();
        assertThat(enabled.readFile(ref(10L), "/workspace/../etc/passwd", 0)).isNull();
        assertThat(enabled.readFile(ref(10L), "  ", 0)).isNull();
    }

    @Test
    @DisplayName("探测 / 写入：未启用 / 非池化句柄 / 非法路径 / null 内容 ⇒ 降级 false（不抛异常）")
    void statAndWriteFileShouldDegrade() {
        OpenSandboxRuntime disabled = newRuntime(props(false));
        OpenSandboxRuntime enabled = newRuntime(props(true));

        assertThat(disabled.stat(ref(10L), "/workspace/a.md")).isFalse();
        assertThat(enabled.stat(null, "/workspace/a.md")).isFalse();
        assertThat(enabled.stat(WorkspaceRef.oneShot(10L), "/workspace/a.md")).isFalse();
        assertThat(enabled.stat(ref(10L), "relative/a.md")).isFalse();
        // 无活跃槽 ⇒ 只读探测不建沙箱，直接判 false
        assertThat(enabled.stat(ref(10L), "/workspace/a.md")).isFalse();

        assertThat(disabled.writeFile(ref(10L), "/workspace/a.md", new byte[]{1})).isFalse();
        assertThat(enabled.writeFile(null, "/workspace/a.md", new byte[]{1})).isFalse();
        assertThat(enabled.writeFile(ref(10L), "/workspace/a.md", null)).isFalse();
        assertThat(enabled.writeFile(ref(10L), "relative/a.md", new byte[]{1})).isFalse();
    }

    @Test
    @DisplayName("SL-9 列目录：未启用 / null 句柄 / 一次性句柄 / 非法路径 / 无活跃槽 ⇒ 空列表")
    void listFilesShouldDegradeToEmpty() {
        OpenSandboxRuntime disabled = newRuntime(props(false));
        OpenSandboxRuntime enabled = newRuntime(props(true));

        assertThat(disabled.listFiles(ref(10L), SandboxRuntime.WORKSPACE_DIR, 2)).isEmpty();
        assertThat(enabled.listFiles(null, SandboxRuntime.WORKSPACE_DIR, 2)).isEmpty();
        assertThat(enabled.listFiles(WorkspaceRef.oneShot(10L), SandboxRuntime.WORKSPACE_DIR, 2)).isEmpty();
        assertThat(enabled.listFiles(ref(10L), "workspace", 2)).isEmpty();
        // 读路径不建沙箱：没有在册槽 ⇒ 空列表，而不是先花 3 秒建一个空目录
        assertThat(enabled.listFiles(ref(10L), SandboxRuntime.WORKSPACE_DIR, 2)).isEmpty();
    }
}
