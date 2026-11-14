package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.SkillInstallResult;
import com.helloai.core.agent.runtime.sandbox.WorkspaceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SkillPackageInstaller} 单测（`SL-9`，2026-11-13）。
 *
 * <p><b>只覆盖守卫层与加载期失败</b>：成功路径会触发真实 SDK 建容器，属集成测试范畴。
 * 这里钉住的是「失败必须结构化、且尽量早失败」：未启用 / 复用关闭 / 非池化句柄 / 包不存在
 * 都在建沙箱之前返回——一次装包失败不该白烧一个容器。</p>
 */
@DisplayName("SkillPackageInstaller")
class SkillPackageInstallerTest {

    private OpenSandboxProperties props(boolean enabled) {
        OpenSandboxProperties props = new OpenSandboxProperties();
        props.setEnabled(enabled);
        props.setUrl("http://localhost:8090");
        props.setApiKey("dev-test-key");
        return props;
    }

    private SandboxWorkspaceRegistry registry(OpenSandboxProperties props) {
        return new SandboxWorkspaceRegistry(props, new SandboxFactory(props));
    }

    private SkillPackageInstaller installer(OpenSandboxProperties props, SandboxWorkspaceRegistry registry) {
        return new SkillPackageInstaller(props, registry, new SkillPackageLoader());
    }

    private static WorkspaceRef ref(Long subTaskId) {
        return WorkspaceRef.subtask(subTaskId);
    }

    @Test
    @DisplayName("注入：未启用 ⇒ 结构化失败（不抛异常、不建沙箱）")
    void installShouldFailWhenDisabled() {
        OpenSandboxProperties props = props(false);
        SandboxWorkspaceRegistry registry = registry(props);

        SkillInstallResult result = installer(props, registry)
                .install(ref(10L), null, "archify", "3.1.0");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("disabled");
        assertThat(result.fileCount()).isZero();
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    @DisplayName("注入：复用关闭 ⇒ 结构化失败（一次性沙箱装完即销毁，包无处可用）")
    void installShouldFailWhenReuseDisabled() {
        OpenSandboxProperties props = props(true);
        props.setReuseEnabled(false);

        SkillInstallResult result = installer(props, registry(props))
                .install(ref(10L), null, "archify", "3.1.0");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("reuse-enabled");
    }

    @Test
    @DisplayName("注入：null / 非池化句柄 ⇒ 结构化失败（无槽键可用）")
    void installShouldFailWithoutPooledRef() {
        OpenSandboxProperties props = props(true);
        SandboxWorkspaceRegistry registry = registry(props);
        SkillPackageInstaller installer = installer(props, registry);

        SkillInstallResult nullRef = installer.install(null, null, "archify", "3.1.0");
        SkillInstallResult oneShot = installer.install(WorkspaceRef.oneShot(10L), null, "archify", "3.1.0");

        assertThat(nullRef.success()).isFalse();
        assertThat(nullRef.message()).contains("pooled");
        assertThat(oneShot.success()).isFalse();
        assertThat(oneShot.message()).contains("pooled");
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    @DisplayName("注入：包不存在 / 包名越界 ⇒ 加载阶段就失败（先于建沙箱，不浪费容器）")
    void installShouldFailOnBadPackageBeforeCreatingSandbox() {
        OpenSandboxProperties props = props(true);
        SandboxWorkspaceRegistry registry = registry(props);
        SkillPackageInstaller installer = installer(props, registry);

        SkillInstallResult missing = installer.install(ref(10L), null, "not-exist-package", "9.9.9");
        SkillInstallResult traversal = installer.install(ref(10L), null, "../../etc", "3.1.0");

        assertThat(missing.success()).isFalse();
        assertThat(missing.message()).contains("技能包不存在");
        assertThat(traversal.success()).isFalse();
        assertThat(traversal.message()).contains("packageName");
        assertThat(registry.activeCount()).isZero();
    }
}
