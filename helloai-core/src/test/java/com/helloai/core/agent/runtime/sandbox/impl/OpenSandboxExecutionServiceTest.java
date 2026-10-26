package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.runtime.sandbox.SkillInstallResult;
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
        SandboxExecutionService service = new OpenSandboxExecutionService(props(false), new SkillPackageLoader());

        ExecuteResponse response = service.execute(10L, "echo hello", null);

        assertThat(response.exitCode()).isEqualTo(ExecuteResponse.EXIT_CODE_ERROR);
        assertThat(response.output()).contains("disabled");
        assertThat(response.truncated()).isFalse();
        assertThat(response.succeeded()).isFalse();
    }

    @Test
    @DisplayName("空白命令 → 结构化失败（exitCode=-1，不抛异常）")
    void shouldReturnErrorWhenCommandBlank() {
        SandboxExecutionService service = new OpenSandboxExecutionService(props(true), new SkillPackageLoader());

        ExecuteResponse response = service.execute(10L, "  ", 30);

        assertThat(response.exitCode()).isEqualTo(ExecuteResponse.EXIT_CODE_ERROR);
        assertThat(response.output()).contains("blank");
        assertThat(response.succeeded()).isFalse();
    }

    @Test
    @DisplayName("null 命令 → 结构化失败（exitCode=-1，不抛异常）")
    void shouldReturnErrorWhenCommandNull() {
        SandboxExecutionService service = new OpenSandboxExecutionService(props(true), new SkillPackageLoader());

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

    // ===== ADR-005：同子任务复用的生命周期出口（2026-10-25） =====

    @Test
    @DisplayName("release：null / 未登记子任务 ⇒ 静默返回（幂等，不抛异常）")
    void releaseShouldBeIdempotent() {
        OpenSandboxExecutionService service = new OpenSandboxExecutionService(props(true), new SkillPackageLoader());

        service.release(null);
        service.release(999L);

        assertThat(service.activeSandboxes()).isZero();
    }

    @Test
    @DisplayName("复用默认开启，且可配置关闭（关闭 ⇒ 回落一次性语义）")
    void reuseEnabledShouldBeConfigurable() {
        OpenSandboxProperties props = props(true);
        // 默认值：对齐 DSH dsh-tool-bash-persistent 的「同子任务工作区持久」口径
        assertThat(props.isReuseEnabled()).isTrue();
        assertThat(props.getIdleTtlSeconds()).isEqualTo(900L);

        props.setReuseEnabled(false);
        assertThat(props.isReuseEnabled()).isFalse();
    }

    @Test
    @DisplayName("未启用 / 命令空白时不得登记任何沙箱（守卫先于建沙箱）")
    void guardShouldNotRegisterSandbox() {
        OpenSandboxExecutionService disabled = new OpenSandboxExecutionService(props(false), new SkillPackageLoader());
        disabled.execute(10L, "echo hi", 30);
        disabled.release(10L);

        OpenSandboxExecutionService enabled = new OpenSandboxExecutionService(props(true), new SkillPackageLoader());
        enabled.execute(10L, "   ", 30);
        enabled.release(10L);

        assertThat(disabled.activeSandboxes()).isZero();
        assertThat(enabled.activeSandboxes()).isZero();
    }

    // ===== SKX-6：技能包注入的守卫层（2026-10-26） =====

    @Test
    @DisplayName("注入：未启用 ⇒ 结构化失败（不抛异常、不建沙箱）")
    void installShouldFailWhenDisabled() {
        OpenSandboxExecutionService service = new OpenSandboxExecutionService(props(false), new SkillPackageLoader());

        SkillInstallResult result = service.installSkillPackage(10L, "archify", "3.1.0");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("disabled");
        assertThat(result.fileCount()).isZero();
        assertThat(service.activeSandboxes()).isZero();
    }

    @Test
    @DisplayName("注入：复用关闭 ⇒ 结构化失败（一次性沙箱装完即销毁，包无处可用）")
    void installShouldFailWhenReuseDisabled() {
        OpenSandboxProperties props = props(true);
        props.setReuseEnabled(false);
        OpenSandboxExecutionService service = new OpenSandboxExecutionService(props, new SkillPackageLoader());

        SkillInstallResult result = service.installSkillPackage(10L, "archify", "3.1.0");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("reuse-enabled");
    }

    @Test
    @DisplayName("注入：subTaskId 为 null ⇒ 结构化失败（无槽键可用）")
    void installShouldFailWhenSubTaskIdNull() {
        OpenSandboxExecutionService service = new OpenSandboxExecutionService(props(true), new SkillPackageLoader());

        SkillInstallResult result = service.installSkillPackage(null, "archify", "3.1.0");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("subTaskId");
        assertThat(service.activeSandboxes()).isZero();
    }

    @Test
    @DisplayName("注入：包不存在 / 包名越界 ⇒ 加载阶段就失败（先于建沙箱，不浪费容器）")
    void installShouldFailOnBadPackageBeforeCreatingSandbox() {
        OpenSandboxExecutionService service = new OpenSandboxExecutionService(props(true), new SkillPackageLoader());

        SkillInstallResult missing = service.installSkillPackage(10L, "not-exist-package", "9.9.9");
        SkillInstallResult traversal = service.installSkillPackage(10L, "../../etc", "3.1.0");

        assertThat(missing.success()).isFalse();
        assertThat(missing.message()).contains("技能包不存在");
        assertThat(traversal.success()).isFalse();
        assertThat(traversal.message()).contains("packageName");
        assertThat(service.activeSandboxes()).isZero();
    }
    // ===== SKX-7：产物回读的守卫层（2026-10-26） =====

    @Test
    @DisplayName("回读：未启用 / 无子任务 / 非法路径 ⇒ null（不建沙箱、不抛异常）")
    void readArtifactShouldReturnNullOnGuards() {
        OpenSandboxExecutionService disabled =
                new OpenSandboxExecutionService(props(false), new SkillPackageLoader());
        OpenSandboxExecutionService enabled =
                new OpenSandboxExecutionService(props(true), new SkillPackageLoader());

        assertThat(disabled.readArtifact(10L, "/workspace/out/x.html", 0)).isNull();
        assertThat(enabled.readArtifact(null, "/workspace/out/x.html", 0)).isNull();
        // 相对路径 / 越界路径一律拒绝（不按 /workspace 补全——静默读到另一个文件比明确失败更难查）
        assertThat(enabled.readArtifact(10L, "out/x.html", 0)).isNull();
        assertThat(enabled.readArtifact(10L, "/workspace/../etc/passwd", 0)).isNull();
        assertThat(enabled.readArtifact(10L, "  ", 0)).isNull();
        assertThat(enabled.activeSandboxes()).isZero();
    }
}
