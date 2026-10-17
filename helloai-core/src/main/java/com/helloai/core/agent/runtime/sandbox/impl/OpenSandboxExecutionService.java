package com.helloai.core.agent.runtime.sandbox.impl;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * OpenSandbox 沙箱执行通道实现（S-7，沙箱执行链设计方案 §3.3）。
 *
 * <p>把命令交给 OpenSandbox 容器执行并返回统一 {@link ExecuteResponse}：
 * 创建短命沙箱（短 TTL，防资源泄漏）→ {@code commands.run} 同步执行（服务端按
 * {@code timeout} 超时，返回 {@code exitCode=124}）→ finally <b>无条件 kill()</b>
 * （对齐 S-4 探针语义：SDK {@code close()} 不删远端沙箱，{@code kill()} 才删）。</p>
 *
 * <p><b>安全边界（对齐沙箱执行链设计方案 §3.4）</b>：沙箱容器零平台凭据 env、
 * 默认禁网（egress 白名单外不可达）、文件系统/资源/进程受容器隔离——
 * 执行不可信脚本的安全边界由 OpenSandbox server 强制（诚实五边界
 * {@code ExecutionPolicy.openSandbox()} 标注）。本实现<b>不注入任何平台 env</b>。</p>
 *
 * <p><b>失败也结构化返回</b>：SDK 调用异常 ⇒ {@code ExecuteResponse("<err>", EXIT_CODE_ERROR, false)}，
 * 不抛异常（与 {@code SandboxProvider.resolve} 的 null 语义一致的容错哲学）；
 * 超时 ⇒ {@code exitCode=124}（对齐 octop-harness）。</p>
 *
 * <p><b>生命周期</b>：每次执行创建独立沙箱（短 TTL，默认 15min 上限），执行完 finally
 * 无条件 kill——不跨调用复用（防「上一个命令的残留文件影响下一个」，对齐
 * octop-harness {@code sandbox_scope} 的隔离语义）。</p>
 */
@Slf4j
@Component
public class OpenSandboxExecutionService implements SandboxExecutionService {

    /** 默认命令超时秒数（LLM/Reviewer 验证命令通常秒级；宽松给 30s）。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 命令超时上限（秒）：超出钳制到上限，防单次执行长期占用沙箱资源。 */
    private static final int MAX_TIMEOUT_SECONDS = 300;

    /** 沙箱 readiness 等待上限（秒）。 */
    private static final long READY_TIMEOUT_SECONDS = 120L;

    /** 沙箱生命周期上限（短 TTL；执行用沙箱短命，15min 足够，防资源泄漏）。 */
    private static final long SANDBOX_TIMEOUT_SECONDS = 900L;

    /** 输出截断上限（字符；对齐 octop-harness max_output_bytes=100_000 的 100KB 量级）。 */
    private static final int MAX_OUTPUT_CHARS = 100_000;

    private final OpenSandboxProperties properties;

    public OpenSandboxExecutionService(OpenSandboxProperties properties) {
        this.properties = properties;
    }

    @Override
    public ExecuteResponse execute(Long subTaskId, String command, Integer timeoutSeconds) {
        // 入口守卫（纵深防御：工具已按 enabled 摘除，正常情况下不会被调用）
        if (!properties.isEnabled()) {
            return new ExecuteResponse("sandbox execution disabled (helloai.sandbox.opensandbox.enabled=false)",
                    ExecuteResponse.EXIT_CODE_ERROR, false);
        }
        if (command == null || command.isBlank()) {
            return new ExecuteResponse("command must not be blank", ExecuteResponse.EXIT_CODE_ERROR, false);
        }
        long timeoutSec = resolveTimeoutSeconds(timeoutSeconds);
        long start = System.currentTimeMillis();
        String sandboxId = null;

        ConnectionConfig config = ConnectionConfig.builder()
                .domain(properties.getUrl())
                .apiKey(properties.getApiKey())
                // 拓扑必需：app 与沙箱不同网时，execd 请求经 server 代理（LOG-20261017-009）
                .useServerProxy(true)
                .requestTimeout(Duration.ofSeconds(MAX_TIMEOUT_SECONDS + 60))
                .build();

        Sandbox sandbox = null;
        try {
            sandbox = Sandbox.builder()
                    .connectionConfig(config)
                    .image(resolveImage())
                    .readyTimeout(Duration.ofSeconds(READY_TIMEOUT_SECONDS))
                    .timeout(Duration.ofSeconds(SANDBOX_TIMEOUT_SECONDS))
                    .build();
            sandboxId = sandbox.getId();
            log.info("OpenSandboxExecutionService: sandbox created id={}, subTaskId={}", sandboxId, subTaskId);

            Execution execution = sandbox.commands().run(
                    com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest.builder()
                            .command(command)
                            .timeout(Duration.ofSeconds(timeoutSec))
                            .build());

            int exitCode = execution.getExitCode() != null ? execution.getExitCode() : ExecuteResponse.EXIT_CODE_ERROR;
            String output = renderOutput(execution);
            boolean truncated = output.length() > MAX_OUTPUT_CHARS;
            if (truncated) {
                output = output.substring(0, MAX_OUTPUT_CHARS);
            }
            log.info("OpenSandboxExecutionService: command finished id={}, subTaskId={}, exitCode={}, "
                            + "truncated={}, outputChars={}, costMs={}",
                    sandboxId, subTaskId, exitCode, truncated, output.length(),
                    System.currentTimeMillis() - start);
            return new ExecuteResponse(output, exitCode, truncated);
        } catch (Exception e) {
            log.warn("OpenSandboxExecutionService: command failed sandboxId={}, subTaskId={}, cause={}",
                    sandboxId, subTaskId, e.getMessage());
            return new ExecuteResponse("sandbox execution failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), ExecuteResponse.EXIT_CODE_ERROR, false);
        } finally {
            // 失败路径也销毁：无条件 kill（SDK close() 不删远端沙箱，必须显式 kill）
            if (sandbox != null) {
                try {
                    sandbox.kill();
                    log.info("OpenSandboxExecutionService: sandbox killed id={}, subTaskId={}", sandboxId, subTaskId);
                } catch (Exception killEx) {
                    log.warn("OpenSandboxExecutionService: kill failed sandboxId={}, subTaskId={}, cause={}",
                            sandboxId, subTaskId, killEx.getMessage());
                }
                try {
                    sandbox.close();
                } catch (Exception closeEx) {
                    log.warn("OpenSandboxExecutionService: close failed sandboxId={}, subTaskId={}, cause={}",
                            sandboxId, subTaskId, closeEx.getMessage());
                }
            }
        }
    }

    /** 解析命令超时（默认 30s，钳制到 [1, 300]）。 */
    private static long resolveTimeoutSeconds(Integer timeoutSeconds) {
        long resolved = timeoutSeconds != null && timeoutSeconds > 0
                ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        return Math.min(resolved, MAX_TIMEOUT_SECONDS);
    }

    /** 沙箱镜像：优先配置 spec.image，回落 python:3.12（OpenSandboxProperties 默认）。 */
    private String resolveImage() {
        OpenSandboxProperties.Spec spec = properties.getSpec();
        if (spec != null && spec.getImage() != null && !spec.getImage().isBlank()) {
            return spec.getImage();
        }
        return "python:3.12";
    }

    /** 渲染命令输出：stdout + stderr 消息拼接（无消息时回落 result text）。 */
    private static String renderOutput(Execution execution) {
        StringBuilder sb = new StringBuilder();
        List<OutputMessage> stdout = execution.getLogs() != null && execution.getLogs().getStdout() != null
                ? execution.getLogs().getStdout() : List.of();
        List<OutputMessage> stderr = execution.getLogs() != null && execution.getLogs().getStderr() != null
                ? execution.getLogs().getStderr() : List.of();
        appendMessages(sb, stdout);
        appendMessages(sb, stderr);
        if (sb.length() == 0 && execution.getResult() != null) {
            String resultText = execution.getResult().stream()
                    .map(r -> r.getText() != null ? r.getText() : "")
                    .collect(Collectors.joining("\n"));
            if (!resultText.isBlank()) {
                sb.append(resultText);
            }
        }
        return sb.toString();
    }

    private static void appendMessages(StringBuilder sb, List<OutputMessage> messages) {
        for (OutputMessage msg : messages) {
            if (msg.getText() == null || msg.getText().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(msg.getText());
        }
    }
}
