package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.common.constant.AgentRole;
import com.helloai.common.constant.TimelineEventCode;
import com.helloai.core.agent.port.TaskTimelinePort;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxCallLedger;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.runtime.sandbox.SkillInstallResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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

    /** 构造被测服务；台账与时间线端口用真实 / 空实现（守卫层用例不触发二者）。 */
    private OpenSandboxExecutionService newService(OpenSandboxProperties props) {
        TaskTimelinePort noopTimeline = (taskId, subTaskId, eventType, role, agentId, payload) -> {
        };
        return new OpenSandboxExecutionService(props, new SkillPackageLoader(),
                new SandboxCallLedger(), noopTimeline);
    }

    @Test
    @DisplayName("未启用 → 结构化失败（exitCode=-1，不抛异常）")
    void shouldReturnErrorWhenDisabled() {
        SandboxExecutionService service = newService(props(false));

        ExecuteResponse response = service.execute(10L, "echo hello", null);

        assertThat(response.exitCode()).isEqualTo(ExecuteResponse.EXIT_CODE_ERROR);
        assertThat(response.output()).contains("disabled");
        assertThat(response.truncated()).isFalse();
        assertThat(response.succeeded()).isFalse();
    }

    @Test
    @DisplayName("空白命令 → 结构化失败（exitCode=-1，不抛异常）")
    void shouldReturnErrorWhenCommandBlank() {
        SandboxExecutionService service = newService(props(true));

        ExecuteResponse response = service.execute(10L, "  ", 30);

        assertThat(response.exitCode()).isEqualTo(ExecuteResponse.EXIT_CODE_ERROR);
        assertThat(response.output()).contains("blank");
        assertThat(response.succeeded()).isFalse();
    }

    @Test
    @DisplayName("null 命令 → 结构化失败（exitCode=-1，不抛异常）")
    void shouldReturnErrorWhenCommandNull() {
        SandboxExecutionService service = newService(props(true));

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
        OpenSandboxExecutionService service = newService(props(true));

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
        OpenSandboxExecutionService disabled = newService(props(false));
        disabled.execute(10L, "echo hi", 30);
        disabled.release(10L);

        OpenSandboxExecutionService enabled = newService(props(true));
        enabled.execute(10L, "   ", 30);
        enabled.release(10L);

        assertThat(disabled.activeSandboxes()).isZero();
        assertThat(enabled.activeSandboxes()).isZero();
    }

    // ===== SKX-6：技能包注入的守卫层（2026-10-26） =====

    @Test
    @DisplayName("注入：未启用 ⇒ 结构化失败（不抛异常、不建沙箱）")
    void installShouldFailWhenDisabled() {
        OpenSandboxExecutionService service = newService(props(false));

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
        OpenSandboxExecutionService service = newService(props);

        SkillInstallResult result = service.installSkillPackage(10L, "archify", "3.1.0");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("reuse-enabled");
    }

    @Test
    @DisplayName("注入：subTaskId 为 null ⇒ 结构化失败（无槽键可用）")
    void installShouldFailWhenSubTaskIdNull() {
        OpenSandboxExecutionService service = newService(props(true));

        SkillInstallResult result = service.installSkillPackage(null, "archify", "3.1.0");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("subTaskId");
        assertThat(service.activeSandboxes()).isZero();
    }

    @Test
    @DisplayName("注入：包不存在 / 包名越界 ⇒ 加载阶段就失败（先于建沙箱，不浪费容器）")
    void installShouldFailOnBadPackageBeforeCreatingSandbox() {
        OpenSandboxExecutionService service = newService(props(true));

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
                newService(props(false));
        OpenSandboxExecutionService enabled =
                newService(props(true));

        assertThat(disabled.readArtifact(10L, "/workspace/out/x.html", 0)).isNull();
        assertThat(enabled.readArtifact(null, "/workspace/out/x.html", 0)).isNull();
        // 相对路径 / 越界路径一律拒绝（不按 /workspace 补全——静默读到另一个文件比明确失败更难查）
        assertThat(enabled.readArtifact(10L, "out/x.html", 0)).isNull();
        assertThat(enabled.readArtifact(10L, "/workspace/../etc/passwd", 0)).isNull();
        assertThat(enabled.readArtifact(10L, "  ", 0)).isNull();
        assertThat(enabled.activeSandboxes()).isZero();
    }

    // ===== 建沙箱失败的可观测性（2026-10-27，LOG-20261027-026） =====

    @Test
    @DisplayName("建沙箱失败消息：带出 SDK 原始 cause + 连接配置摘要（不再是 opener returned null）")
    void createFailureMessageShouldCarryRealCause() {
        OpenSandboxExecutionService service =
                newService(props(true));

        String message = service.describeCreateFailure(
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
        OpenSandboxExecutionService service = newService(props);

        String message = service.describeCreateFailure(new IllegalStateException("boom"));

        assertThat(message).contains("url=未配置").contains("apiKey=已配置");
        // 安全约束：只报「是否配置」，值永不入日志/界面
        assertThat(message).doesNotContain("super-secret-key-value");
    }

    @Test
    @DisplayName("建沙箱失败消息：detail 缺失时回落历史文案（调用方与旧排障文档的对应关系不断）")
    void createFailureMessageShouldFallbackToLegacyText() {
        assertThat(OpenSandboxExecutionService.createFailedMessage(null))
                .isEqualTo("sandbox create failed (opener returned null)");
        assertThat(OpenSandboxExecutionService.createFailedMessage("  "))
                .isEqualTo("sandbox create failed (opener returned null)");
        assertThat(OpenSandboxExecutionService.createFailedMessage("sandbox create failed: X: y"))
                .isEqualTo("sandbox create failed: X: y");
    }

    // ===== SR-5：沙箱调用台账 + timeline 观测 =====

    @Test
    @DisplayName("SR-5 台账：一次调用 ⇒ 台账条目字段齐全 + timeline 事件（digest 与输出一致）")
    void recordCallShouldLedgerAndEmitTimeline() {
        TaskTimelinePort timeline = mock(TaskTimelinePort.class);
        SandboxCallLedger ledger = new SandboxCallLedger();
        OpenSandboxExecutionService service = new OpenSandboxExecutionService(
                props(true), new SkillPackageLoader(), ledger, timeline);
        ExecuteResponse response = new ExecuteResponse("hello\n", 0, false);
        AtomicReference<String> eventType = new AtomicReference<>();
        AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        doAnswer(invocation -> {
            eventType.set(invocation.getArgument(2));
            payload.set(invocation.getArgument(5));
            return null;
        }).when(timeline).recordEvent(any(), any(), any(), any(), any(), any());

        service.recordCall(10L, "echo hello", response);

        List<SandboxCallLedger.Entry> entries = ledger.recent(10L);
        assertThat(entries).hasSize(1);
        SandboxCallLedger.Entry entry = entries.get(0);
        assertThat(entry.command()).isEqualTo("echo hello");
        assertThat(entry.exitCode()).isZero();
        assertThat(entry.truncated()).isFalse();
        assertThat(entry.digest()).isEqualTo(response.digest());
        assertThat(entry.at()).isNotNull();

        assertThat(eventType.get()).isEqualTo(TimelineEventCode.SANDBOX_COMMAND_EXECUTED.code());
        assertThat(payload.get())
                .containsEntry("command", "echo hello")
                .containsEntry("commandChars", 10)
                .containsEntry("exitCode", 0)
                .containsEntry("truncated", false)
                .containsEntry("digest", response.digest());
    }

    @Test
    @DisplayName("SR-5 台账：超时(124) / SDK 异常(-1) 同样入账")
    void ledgerShouldRecordFailedAndTimedOutCalls() {
        SandboxCallLedger ledger = new SandboxCallLedger();
        OpenSandboxExecutionService service = new OpenSandboxExecutionService(
                props(true), new SkillPackageLoader(), ledger,
                (taskId, subTaskId, eventType, role, agentId, payload) -> {
                });

        service.recordCall(11L, "sleep 999", new ExecuteResponse("timeout", ExecuteResponse.EXIT_CODE_TIMEOUT, false));
        service.recordCall(11L, "boom", new ExecuteResponse("sandbox execution failed",
                ExecuteResponse.EXIT_CODE_ERROR, false));

        assertThat(ledger.recent(11L))
                .extracting(SandboxCallLedger.Entry::exitCode)
                .containsExactly(ExecuteResponse.EXIT_CODE_TIMEOUT, ExecuteResponse.EXIT_CODE_ERROR);
    }

    @Test
    @DisplayName("SR-5 台账：无子任务上下文 ⇒ 不入账、不发事件（Reviewer / 终报告判定路径）")
    void ledgerShouldSkipWhenSubTaskIdMissing() {
        TaskTimelinePort timeline = mock(TaskTimelinePort.class);
        SandboxCallLedger ledger = new SandboxCallLedger();
        OpenSandboxExecutionService service = new OpenSandboxExecutionService(
                props(true), new SkillPackageLoader(), ledger, timeline);

        service.recordCall(null, "echo hi", new ExecuteResponse("hi", 0, false));

        assertThat(ledger.recent(10L)).isEmpty();
        verifyNoInteractions(timeline);
    }

    @Test
    @DisplayName("ExecuteResponse.digest()：sha256 前缀 + 与输出字节一致 + null 输出按空串")
    void digestShouldFollowOutputBytes() {
        assertThat(new ExecuteResponse("abc", 0, false).digest())
                .isEqualTo("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(new ExecuteResponse(null, 0, false).digest())
                .isEqualTo(new ExecuteResponse("", 0, false).digest());
        assertThat(new ExecuteResponse("abc", 0, false).digest())
                .isNotEqualTo(new ExecuteResponse("abd", 0, false).digest());
    }
}
