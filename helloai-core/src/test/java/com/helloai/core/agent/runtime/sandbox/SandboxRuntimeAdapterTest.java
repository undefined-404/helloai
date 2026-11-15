package com.helloai.core.agent.runtime.sandbox;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.port.SubTaskQueryPort;
import com.helloai.core.agent.port.SubTaskSnapshot;
import com.helloai.core.agent.runtime.sandbox.impl.SkillPackageInstaller;
import com.helloai.core.agent.tool.ToolInvocationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SandboxRuntimeAdapter} 单测（`SL-8`，2026-11-13；`SL-9` 改注入技能安装器）。
 *
 * <p><b>为什么这一段值得单独测</b>：`SL-8` 把「ID → 作用域」的猜测从运行时挪到了适配层，
 * 适配层因此成为<b>唯一允许查库的地方</b>。解析顺序一旦写反，症状是「同一任务两个子任务拿到
 * 不同容器」或「每条命令多一次查库」——两者都不报错，只在真机上表现为工作区互相看不见或
 * 性能劣化。本测试用捕获式假运行时逐条钉住解析结果与查库次数。</p>
 */
@DisplayName("SandboxRuntimeAdapter")
class SandboxRuntimeAdapterTest {

    private final CapturingRuntime runtime = new CapturingRuntime();
    private final SkillPackageInstaller installer = mock(SkillPackageInstaller.class);
    private final SubTaskQueryPort port = mock(SubTaskQueryPort.class);
    private final SandboxScopes scopes = new SandboxScopes(port);

    @AfterEach
    void clearBinding() {
        ToolInvocationContext.unbind();
    }

    private SandboxRuntimeAdapter adapter(OpenSandboxProperties props) {
        return new SandboxRuntimeAdapter(runtime, installer, scopes, props);
    }

    private OpenSandboxProperties props() {
        OpenSandboxProperties props = new OpenSandboxProperties();
        props.setEnabled(true);
        props.setUrl("http://localhost:8090");
        props.setApiKey("dev-test-key");
        props.setReuseEnabled(true);
        return props;
    }

    private void givenSubTaskOfTask(Long subTaskId, Long taskId) {
        when(port.findById(subTaskId)).thenReturn(new SubTaskSnapshot(subTaskId, null, taskId,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null));
    }

    @Test
    @DisplayName("上下文为 null ⇒ 一次性句柄（不进池，ownerId 用 NO_OWNER 占位）")
    void nullContextShouldMapToOneShot() {
        adapter(props()).execute(null, "echo hi", 30);

        WorkspaceRef ref = runtime.lastRef;
        assertThat(ref.kind()).isEqualTo(WorkspaceRef.Kind.ONESHOT);
        assertThat(ref.ownerId()).isEqualTo(WorkspaceRef.NO_OWNER);
        assertThat(ref.pooled()).isFalse();
    }

    @Test
    @DisplayName("复用关闭 ⇒ 一次性句柄（并保留上下文用于台账归属）")
    void reuseDisabledShouldMapToOneShot() {
        OpenSandboxProperties props = props();
        props.setReuseEnabled(false);

        adapter(props).execute(600L, "echo hi", 30);

        assertThat(runtime.lastRef.kind()).isEqualTo(WorkspaceRef.Kind.ONESHOT);
        assertThat(runtime.lastRef.contextId()).isEqualTo(600L);
        assertThat(runtime.lastRef.pooled()).isFalse();
    }

    @Test
    @DisplayName("执行链已绑定 taskId ⇒ 任务级句柄且不查库（热路径零额外查询）")
    void boundTaskIdShouldSkipQuery() {
        ToolInvocationContext.bind(1L, 77L, 600L, 2);

        adapter(props()).execute(600L, "echo hi", 30);

        assertThat(runtime.lastRef.kind()).isEqualTo(WorkspaceRef.Kind.TASK);
        assertThat(runtime.lastRef.ownerId()).isEqualTo(77L);
        assertThat(runtime.lastRef.contextId()).isEqualTo(600L);
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("无绑定 ⇒ 查库解析为任务级；同 ID 第二次命中 LRU 不再查库")
    void unboundShouldResolveTaskScopeAndCache() {
        givenSubTaskOfTask(600L, 77L);
        SandboxRuntimeAdapter adapter = adapter(props());

        adapter.execute(600L, "echo hi", 30);
        adapter.execute(600L, "echo hi again", 30);

        assertThat(runtime.refs)
                .extracting(WorkspaceRef::kind)
                .containsOnly(WorkspaceRef.Kind.TASK);
        assertThat(runtime.refs).extracting(WorkspaceRef::ownerId).containsOnly(77L);
        verify(port, times(1)).findById(600L);
    }

    @Test
    @DisplayName("无绑定且非子任务（会话技能 scopeId）⇒ 直接作用域")
    void unboundNonSubTaskShouldResolveScope() {
        when(port.findById(9L)).thenReturn(null);

        adapter(props()).execute(9L, "echo hi", 30);

        assertThat(runtime.lastRef.kind()).isEqualTo(WorkspaceRef.Kind.SCOPE);
        assertThat(runtime.lastRef.ownerId()).isEqualTo(9L);
        assertThat(runtime.lastRef.contextId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("scope=subtask 回滚模式 ⇒ 子任务级句柄，不查库")
    void subtaskScopeRollbackShouldSkipQuery() {
        OpenSandboxProperties props = props();
        props.setScope("subtask");

        adapter(props).execute(600L, "echo hi", 30);

        assertThat(runtime.lastRef.kind()).isEqualTo(WorkspaceRef.Kind.SUBTASK);
        assertThat(runtime.lastRef.ownerId()).isEqualTo(600L);
        assertThat(runtime.lastRef.lifetime()).isEqualTo(WorkspaceRef.Lifetime.TURN);
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("releaseByTask ⇒ 按任务作用域全规格释放 + 失效作用域缓存（下次解析重新查库）")
    void releaseByTaskShouldReleaseAndInvalidateCache() {
        givenSubTaskOfTask(600L, 77L);
        SandboxRuntimeAdapter adapter = adapter(props());

        adapter.execute(600L, "echo hi", 30);   // 缓存 TASK(77)
        adapter.releaseByTask(77L);
        adapter.execute(600L, "echo hi", 30);   // 缓存已失效 ⇒ 重新查库

        assertThat(runtime.released).hasSize(1);
        assertThat(runtime.released.get(0).kind()).isEqualTo(WorkspaceRef.Kind.TASK);
        assertThat(runtime.released.get(0).ownerId()).isEqualTo(77L);
        assertThat(runtime.released.get(0).specFingerprint()).isNull();
        verify(port, times(2)).findById(600L);
    }

    @Test
    @DisplayName("release / releaseByTask 的 null 入参在适配层短路（不构造句柄、不碰运行时）")
    void releaseNullShouldShortCircuit() {
        SandboxRuntimeAdapter adapter = adapter(props());

        adapter.release(null);
        adapter.releaseByTask(null);

        assertThat(runtime.released).isEmpty();
    }

    // ===== SL-10（2026-11-13）：reviewer 路由 ⇒ 任务级只读句柄 =====

    @Test
    @DisplayName("SL-10 绑定 reviewer=true ⇒ 任务级只读句柄（readOnly=true）")
    void boundReviewerShouldMapToReadOnlyTaskRef() {
        ToolInvocationContext.bind(1L, 77L, 600L, 2, true);

        adapter(props()).execute(600L, "echo hi", 30);

        assertThat(runtime.lastRef.kind()).isEqualTo(WorkspaceRef.Kind.TASK);
        assertThat(runtime.lastRef.ownerId()).isEqualTo(77L);
        assertThat(runtime.lastRef.readOnly()).isTrue();
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("SL-10 绑定 reviewer=false（旧重载缺省）⇒ 任务级读写句柄（readOnly=false）")
    void boundExecutorShouldMapToReadWriteTaskRef() {
        ToolInvocationContext.bind(1L, 77L, 600L, 2);   // 旧重载：缺省非 reviewer

        adapter(props()).execute(600L, "echo hi", 30);

        assertThat(runtime.lastRef.kind()).isEqualTo(WorkspaceRef.Kind.TASK);
        assertThat(runtime.lastRef.ownerId()).isEqualTo(77L);
        assertThat(runtime.lastRef.readOnly()).isFalse();
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("SL-10 无绑定 + 查库解析 TASK 分支跟随 reviewer ⇒ 只读")
    void resolvedTaskBranchShouldFollowReviewerFlag() {
        givenSubTaskOfTask(600L, 77L);
        ToolInvocationContext.bind(1L, null, 600L, 0, true);   // taskId 未绑定 ⇒ 走查库解析

        adapter(props()).execute(600L, "echo hi", 30);

        assertThat(runtime.lastRef.kind()).isEqualTo(WorkspaceRef.Kind.TASK);
        assertThat(runtime.lastRef.ownerId()).isEqualTo(77L);
        assertThat(runtime.lastRef.readOnly()).isTrue();
        verify(port, times(1)).findById(600L);
    }

    @Test
    @DisplayName("SL-10 未绑定 + 查库解析 TASK 分支默认非 reviewer ⇒ 读写句柄")
    void resolvedTaskBranchShouldDefaultToReadWrite() {
        givenSubTaskOfTask(600L, 77L);

        adapter(props()).execute(600L, "echo hi", 30);

        assertThat(runtime.lastRef.kind()).isEqualTo(WorkspaceRef.Kind.TASK);
        assertThat(runtime.lastRef.readOnly()).isFalse();
    }

    @Test
    @DisplayName("执行参数透传：规格 / 超时 / 来源 / 轮次逐字交给运行时")
    void executeShouldForwardAllArguments() {
        SandboxSpec spec = new SandboxSpec("opensandbox", "python:3.12", List.of(), null, null,
                SandboxSpec.Scope.FIXED, 900L);

        adapter(props()).execute(600L, spec, "python -V", 42, SandboxCallOrigin.REVIEWER, 3);

        assertThat(runtime.lastSpec).isSameAs(spec);
        assertThat(runtime.lastTimeout).isEqualTo(42);
        assertThat(runtime.lastOrigin).isEqualTo(SandboxCallOrigin.REVIEWER);
        assertThat(runtime.lastRound).isEqualTo(3);
    }

    @Test
    @DisplayName("回读 / 列目录 / 产物 / 技能注入映射到同一作用域；null 入参短路不碰运行时")
    void readPathsShouldMapSameRef() {
        givenSubTaskOfTask(600L, 77L);
        SandboxRuntimeAdapter adapter = adapter(props());

        assertThat(adapter.listWorkspaceFiles(600L, SandboxRuntime.WORKSPACE_DIR))
                .extracting(WorkspaceEntry::path)
                .containsExactly("/workspace/draft.md");
        assertThat(runtime.lastListRef.kind()).isEqualTo(WorkspaceRef.Kind.TASK);
        assertThat(runtime.lastListRef.ownerId()).isEqualTo(77L);
        assertThat(runtime.lastListDir).isEqualTo(SandboxRuntime.WORKSPACE_DIR);
        assertThat(runtime.lastListDepth).isEqualTo(2);

        SandboxArtifact artifact = adapter.readArtifact(600L, "/workspace/out/x.html", 1024);
        assertThat(artifact.path()).isEqualTo("/workspace/out/x.html");
        assertThat(runtime.lastReadRef.kind()).isEqualTo(WorkspaceRef.Kind.TASK);

        when(installer.install(any(), any(), eq("archify"), eq("3.1.0")))
                .thenReturn(SkillInstallResult.ok(1, 10L, "/workspace/skills/archify", false, "ok"));
        SkillInstallResult installed = adapter.installSkillPackage(600L, null, "archify", "3.1.0");
        assertThat(installed.success()).isTrue();
        assertThat(runtime.listCalls).isEqualTo(1);

        runtime.listCalls = 0;
        runtime.readCalls = 0;
        assertThat(adapter.listWorkspaceFiles(null, SandboxRuntime.WORKSPACE_DIR)).isEmpty();
        assertThat(adapter.readArtifact(null, "/workspace/out/x.html", 0)).isNull();
        assertThat(adapter.installSkillPackage(null, "archify", "3.1.0").success()).isFalse();
        assertThat(runtime.listCalls).isZero();
        assertThat(runtime.readCalls).isZero();
    }

    @Test
    @DisplayName("RV-15 writeReviewerArtifact：默认口径 ⇒ taskReadOnly 句柄（kind=TASK、readOnly=true、owner=taskId）")
    void writeReviewerArtifactShouldMapToReadOnlyTaskRef() {
        byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);

        boolean ok = adapter(props()).writeReviewerArtifact(77L, 600L, "/tmp/staged/9-a.csv", bytes);

        assertThat(ok).isTrue();
        assertThat(runtime.lastWriteRef.kind()).isEqualTo(WorkspaceRef.Kind.TASK);
        assertThat(runtime.lastWriteRef.ownerId()).isEqualTo(77L);
        assertThat(runtime.lastWriteRef.contextId()).isEqualTo(600L);
        assertThat(runtime.lastWriteRef.readOnly()).isTrue();
        verifyNoInteractions(port);   // 编排层显式传 taskId，不查库
        assertThat(runtime.lastWritePath).isEqualTo("/tmp/staged/9-a.csv");
    }

    @Test
    @DisplayName("RV-15 F1：复用关闭 ⇒ 直接 false，不构造必然失败的写入（不碰运行时）")
    void writeReviewerArtifactShouldFailWhenReuseOff() {
        OpenSandboxProperties props = props();
        props.setReuseEnabled(false);

        boolean ok = adapter(props).writeReviewerArtifact(77L, 600L, "/tmp/staged/9-a.csv", new byte[1]);

        assertThat(ok).isFalse();
        assertThat(runtime.writeCalls).isZero();
    }

    @Test
    @DisplayName("RV-15 scope=subtask 回滚模式 ⇒ 子任务级句柄（turn 末释放）")
    void writeReviewerArtifactShouldMapToSubtaskRefOnRollback() {
        OpenSandboxProperties props = props();
        props.setScope("subtask");

        boolean ok = adapter(props).writeReviewerArtifact(77L, 600L, "/tmp/staged/9-a.csv", new byte[1]);

        assertThat(ok).isTrue();
        assertThat(runtime.lastWriteRef.kind()).isEqualTo(WorkspaceRef.Kind.SUBTASK);
        assertThat(runtime.lastWriteRef.ownerId()).isEqualTo(600L);
    }

    @Test
    @DisplayName("RV-15 writeReviewerArtifact：null 入参短路（不构造句柄、不碰运行时）")
    void writeReviewerArtifactShouldShortCircuitOnNull() {
        SandboxRuntimeAdapter adapter = adapter(props());

        assertThat(adapter.writeReviewerArtifact(null, 600L, "/tmp/staged/x", new byte[1])).isFalse();
        assertThat(adapter.writeReviewerArtifact(77L, null, "/tmp/staged/x", new byte[1])).isFalse();
        assertThat(adapter.writeReviewerArtifact(77L, 600L, null, new byte[1])).isFalse();
        assertThat(adapter.writeReviewerArtifact(77L, 600L, "/tmp/staged/x", null)).isFalse();
        assertThat(runtime.writeCalls).isZero();
    }

    // ===== 捕获式假实现（只记录，不触碰 SDK） =====

    private static final class CapturingRuntime implements SandboxRuntime {
        final List<WorkspaceRef> refs = new ArrayList<>();
        final List<WorkspaceRef> released = new ArrayList<>();
        WorkspaceRef lastRef;
        WorkspaceRef lastReadRef;
        WorkspaceRef lastListRef;
        String lastListDir;
        int lastListDepth;
        SandboxSpec lastSpec;
        Integer lastTimeout;
        SandboxCallOrigin lastOrigin;
        int lastRound;
        int readCalls;
        int listCalls;
        int writeCalls;
        WorkspaceRef lastWriteRef;
        String lastWritePath;

        @Override
        public ExecuteResponse exec(WorkspaceRef ref, SandboxSpec spec, String command,
                                    Integer timeoutSeconds, SandboxCallOrigin origin, int round) {
            lastRef = ref;
            lastSpec = spec;
            lastTimeout = timeoutSeconds;
            lastOrigin = origin;
            lastRound = round;
            refs.add(ref);
            return new ExecuteResponse("ok", 0, false);
        }

        @Override
        public SandboxArtifact readFile(WorkspaceRef ref, String path, int maxBytes) {
            lastReadRef = ref;
            readCalls++;
            return new SandboxArtifact(path, "hi".getBytes(StandardCharsets.UTF_8),
                    "text/plain", "sha256:stub", false);
        }

        @Override
        public boolean stat(WorkspaceRef ref, String path) {
            return false;
        }

        @Override
        public List<WorkspaceEntry> listFiles(WorkspaceRef ref, String dir, int depth) {
            lastListRef = ref;
            lastListDir = dir;
            lastListDepth = depth;
            listCalls++;
            return List.of(new WorkspaceEntry(dir + "/draft.md", 128L, null, "file"));
        }

        @Override
        public boolean writeFile(WorkspaceRef ref, String path, byte[] content) {
            lastWriteRef = ref;
            lastWritePath = path;
            writeCalls++;
            return true;
        }

        @Override
        public void release(WorkspaceRef ref) {
            released.add(ref);
        }
    }
}
