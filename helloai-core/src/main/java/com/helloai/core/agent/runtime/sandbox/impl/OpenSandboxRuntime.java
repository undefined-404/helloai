package com.helloai.core.agent.runtime.sandbox.impl;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxArtifact;
import com.helloai.core.agent.runtime.sandbox.SandboxCallOrigin;
import com.helloai.core.agent.runtime.sandbox.SandboxRuntime;
import com.helloai.core.agent.runtime.sandbox.SandboxSpec;
import com.helloai.core.agent.runtime.sandbox.WorkspaceEntry;
import com.helloai.core.agent.runtime.sandbox.WorkspaceRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * OpenSandbox 运行时（`SL-9`）：把一条命令 / 一次读写交给容器执行。
 *
 * <p><b>本类只回答「命令怎么跑、文件怎么读写」</b>。容器何时建、复用多久、何时销毁归
 * {@link SandboxWorkspaceRegistry}；ID → 句柄的映射归 {@code SandboxRuntimeAdapter}；
 * 台账 / 时间线归 {@link SandboxObservation}；技能包注入归 {@link SkillPackageInstaller}。
 * 拆开之前这五件事在一千五百行里互相借私有字段，任何一处改动都要读全文件。</p>
 *
 * <p><b>安全边界（对齐沙箱执行链设计方案 §3.4）</b>：容器零平台凭据 env、默认禁网
 * （egress 白名单外不可达）、文件系统 / 资源 / 进程受容器隔离——执行不可信脚本的安全边界
 * 由 OpenSandbox server 强制（诚实五边界 {@code ExecutionPolicy.openSandbox()} 标注）。
 * 本实现<b>不注入任何平台 env</b>。</p>
 *
 * <p><b>失败也结构化返回</b>：SDK 调用异常 ⇒ {@code ExecuteResponse("<err>", EXIT_CODE_ERROR, false)}，
 * 不抛异常；超时 ⇒ {@code exitCode=124}（对齐 octop-harness）。读 / 写 / 探测失败降级为
 * {@code null} / {@code false} / 空列表。</p>
 *
 * <p><b>寿命与销毁（`SL-1`/`SL-2`/`SL-7`）</b>：命令超时（{@code 124}）与 SDK 错误（{@code -1}）
 * <b>不再销毁容器</b>——命令超时不代表容器坏，销毁会连带丢掉整个任务已产出的文件；
 * 只有 {@code commands().run()} <b>抛异常</b>（传输层失败，容器状态不可判）才丢弃待重建。</p>
 *
 * <p><b>退路</b>：{@code helloai.sandbox.opensandbox.reuse-enabled=false} 或句柄为
 * {@link WorkspaceRef.Kind#ONESHOT} ⇒ 回落「即建即毁」一次性语义，零行为变化。</p>
 */
@Slf4j
@Component
public class OpenSandboxRuntime implements SandboxRuntime {

    /** 默认命令超时秒数（`SL-7` 起 30s → 300s：一次 {@code npm install} 就会超过 30s，超时即丢工作区）。 */
    static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** 命令超时上限（秒）：超出钳制到上限，防单次执行长期占用沙箱资源（`SL-7` 起与默认值同档）。 */
    static final int MAX_TIMEOUT_SECONDS = 300;

    /** 输出截断上限（字符；对齐 octop-harness max_output_bytes=100_000 的 100KB 量级）。 */
    private static final int MAX_OUTPUT_CHARS = 100_000;

    /** 默认单次产物回读上限（字节，4MB）：覆盖 archify 的 ~820KB HTML 及其回执，也不会一次读爆内存。 */
    private static final int DEFAULT_ARTIFACT_MAX_BYTES = 4 * 1024 * 1024;

    /** 写入文件权限（644）：工作区文件全是数据文件，不需要可执行位。 */
    private static final int FILE_MODE = 644;

    private final OpenSandboxProperties properties;
    private final SandboxFactory factory;
    private final SandboxWorkspaceRegistry registry;
    private final SandboxObservation observation;

    public OpenSandboxRuntime(OpenSandboxProperties properties, SandboxFactory factory,
                              SandboxWorkspaceRegistry registry, SandboxObservation observation) {
        this.properties = properties;
        this.factory = factory;
        this.registry = registry;
        this.observation = observation;
    }

    @Override
    public ExecuteResponse exec(WorkspaceRef ref, SandboxSpec spec, String command,
                                Integer timeoutSeconds, SandboxCallOrigin origin, int round) {
        Objects.requireNonNull(ref, "ref must not be null");
        // 入口守卫（纵深防御：工具已按 enabled 摘除，正常情况下不会被调用）
        if (!properties.isEnabled()) {
            return new ExecuteResponse("sandbox execution disabled (helloai.sandbox.opensandbox.enabled=false)",
                    ExecuteResponse.EXIT_CODE_ERROR, false);
        }
        if (command == null || command.isBlank()) {
            return new ExecuteResponse("command must not be blank", ExecuteResponse.EXIT_CODE_ERROR, false);
        }
        long timeoutSec = resolveTimeoutSeconds(timeoutSeconds);
        SandboxSpec effective = spec != null ? spec : SandboxSpecMapper.toDomain(properties.getSpec());
        long start = System.currentTimeMillis();
        Long contextId = ref.contextId();
        // 退路：关闭复用 / 一次性句柄（EPHEMERAL_VERIFY 校验通道、S-9 判定）⇒ 原「即建即毁」一次性语义。
        // 只读与否按句柄声明的 access 落底：oneShot 是 READ_ONLY，校验通道建即毁无需写工作区。
        if (!properties.isReuseEnabled() || !ref.pooled()) {
            return observation.record(contextId, command,
                    executeOneShot(contextId, effective, command, timeoutSec, start, ref.readOnly()),
                    origin, round);
        }
        // opener 返回 null 只表达「创建失败」，真实原因经 errorSink 带出
        AtomicReference<String> createFailure = new AtomicReference<>();
        SandboxSlotPool.Slot<SandboxWorkspaceRegistry.SlotKey, Sandbox> slot =
                registry.open(ref, effective, createFailure);
        if (slot == null) {
            return observation.record(contextId, command,
                    new ExecuteResponse(SandboxFactory.createFailedMessage(createFailure.get()),
                            ExecuteResponse.EXIT_CODE_ERROR, false), origin, round);
        }
        slot.lock().lock();
        try {
            return observation.record(contextId, command,
                    runInSlot(slot, contextId, command, timeoutSec, start), origin, round);
        } finally {
            slot.lock().unlock();
        }
    }

    /**
     * 一次性语义（复用关闭 / 无复用上下文）：建 → 跑 → 无条件 kill（原 S-7 行为，零变化）。
     */
    private ExecuteResponse executeOneShot(Long contextId, SandboxSpec spec, String command,
                                           long timeoutSec, long start, boolean readOnly) {
        Sandbox sandbox = null;
        try {
            AtomicReference<String> createFailure = new AtomicReference<>();
            sandbox = factory.create(spec, contextId, createFailure, readOnly);
            if (sandbox == null) {
                return new ExecuteResponse(SandboxFactory.createFailedMessage(createFailure.get()),
                        ExecuteResponse.EXIT_CODE_ERROR, false);
            }
            return runCommand(contextId, sandbox, command, timeoutSec, start);
        } catch (Exception e) {
            log.warn("OpenSandboxRuntime: command failed contextId={}, cause={}", contextId, e.getMessage());
            return new ExecuteResponse("sandbox execution failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), ExecuteResponse.EXIT_CODE_ERROR, false);
        } finally {
            factory.destroy(sandbox);
        }
    }

    /**
     * 在复用槽内执行：命令结束后按需续期（保活工作区）。
     *
     * <p>`SL-7` 起 {@code exitCode=124} 与 {@code -1} <b>不销毁容器</b>（只说明这条命令没跑完），
     * 超时另记一条时间线事件；只有执行抛异常才丢弃槽待重建。</p>
     */
    private ExecuteResponse runInSlot(SandboxSlotPool.Slot<SandboxWorkspaceRegistry.SlotKey, Sandbox> slot,
                                      Long contextId, String command, long timeoutSec, long start) {
        Sandbox sandbox = slot.resource();
        ExecuteResponse response;
        try {
            response = runCommand(contextId, sandbox, command, timeoutSec, start);
        } catch (Exception e) {
            registry.remove(slot.key());
            log.warn("OpenSandboxRuntime: 复用沙箱执行异常，已丢弃待重建 contextId={}, spec={}, cause={}",
                    contextId, slot.key().specFingerprint(), e.getMessage());
            return new ExecuteResponse("sandbox execution failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), ExecuteResponse.EXIT_CODE_ERROR, false);
        }
        if (response.exitCode() == ExecuteResponse.EXIT_CODE_TIMEOUT) {
            observation.timedOut(contextId, command, timeoutSec);
            log.info("OpenSandboxRuntime: 命令超时（exitCode=124），保留工作区 contextId={}, spec={}, "
                    + "timeoutSeconds={}", contextId, slot.key().specFingerprint(), timeoutSec);
        }
        registry.renewIfNeeded(slot, contextId);
        slot.touch();
        return response;
    }

    /**
     * 执行一条命令并渲染统一结果（纯执行，不碰生命周期）。
     *
     * <p><b>cwd 固定 {@link SandboxRuntime#WORKSPACE_DIR}（`SL-3`）</b>：模型写 {@code ./x.py} 与
     * {@code /workspace/x.py} 必须落到同一个文件；不注入 cwd 时前者落在 {@code /}（`SKX-1` 实测
     * {@code pwd=/}），回读永远找不到。</p>
     */
    private ExecuteResponse runCommand(Long contextId, Sandbox sandbox, String command,
                                       long timeoutSec, long start) {
        String sandboxId = sandbox.getId();
        Execution execution = sandbox.commands().run(
                RunCommandRequest.builder()
                        .command(command)
                        .workingDirectory(WORKSPACE_DIR)
                        .timeout(Duration.ofSeconds(timeoutSec))
                        .build());

        int exitCode = execution.getExitCode() != null ? execution.getExitCode() : ExecuteResponse.EXIT_CODE_ERROR;
        String output = renderOutput(execution);
        boolean truncated = output.length() > MAX_OUTPUT_CHARS;
        if (truncated) {
            output = output.substring(0, MAX_OUTPUT_CHARS);
        }
        log.info("OpenSandboxRuntime: command finished id={}, contextId={}, exitCode={}, "
                        + "truncated={}, outputChars={}, costMs={}",
                sandboxId, contextId, exitCode, truncated, output.length(),
                System.currentTimeMillis() - start);
        return new ExecuteResponse(output, exitCode, truncated);
    }

    @Override
    public SandboxArtifact readFile(WorkspaceRef ref, String path, int maxBytes) {
        if (!properties.isEnabled() || !properties.isReuseEnabled() || ref == null || !ref.pooled()) {
            return null;
        }
        String normalized = normalizeArtifactPath(path);
        if (normalized == null) {
            log.warn("OpenSandboxRuntime: 产物路径非法（须为容器内绝对路径且不含 ..）path={}", path);
            return null;
        }
        // 只读：按「作用域 + 规格」找在册槽，绝不为了读文件新建沙箱（3s + 一份资源）
        SandboxSlotPool.Slot<SandboxWorkspaceRegistry.SlotKey, Sandbox> slot =
                registry.openIfPresent(ref);
        if (slot == null) {
            log.debug("OpenSandboxRuntime: 无活跃工作区，跳过产物回读 scope={}", ref.key());
            return null;
        }
        slot.lock().lock();
        long start = System.currentTimeMillis();
        try {
            Sandbox sandbox = slot.resource();
            Map<String, EntryInfo> info = sandbox.files().readFileInfo(List.of(normalized));
            EntryInfo entry = info == null ? null : info.get(normalized);
            if (entry == null) {
                log.info("OpenSandboxRuntime: 产物不存在 scope={}, path={}", ref.key(), normalized);
                return null;
            }
            int limit = maxBytes > 0 ? maxBytes : DEFAULT_ARTIFACT_MAX_BYTES;
            long expectedSize = entry.getSize();
            boolean truncated = expectedSize > limit;
            byte[] data = truncated
                    ? sandbox.files().readByteArray(normalized, "bytes=0-" + (limit - 1))
                    : sandbox.files().readByteArray(normalized, null);
            byte[] bytes = data == null ? new byte[0] : data;
            String sha256 = sha256Hex(bytes);
            registry.renewIfNeeded(slot, ref.contextId());
            slot.touch();
            log.info("OpenSandboxRuntime: 产物回读完成 scope={}, path={}, size={}, readBytes={}, "
                            + "truncated={}, sha256={}, costMs={}",
                    ref.key(), normalized, expectedSize, bytes.length, truncated, sha256,
                    System.currentTimeMillis() - start);
            return new SandboxArtifact(normalized, bytes, mimeOf(normalized), sha256, truncated);
        } catch (Exception e) {
            log.warn("OpenSandboxRuntime: 产物回读失败 scope={}, path={}, cause={}",
                    ref.key(), normalized, e.getMessage());
            return null;
        } finally {
            slot.lock().unlock();
        }
    }

    @Override
    public boolean stat(WorkspaceRef ref, String path) {
        if (!properties.isEnabled() || !properties.isReuseEnabled() || ref == null || !ref.pooled()) {
            return false;
        }
        String normalized = normalizeArtifactPath(path);
        if (normalized == null) {
            return false;
        }
        SandboxSlotPool.Slot<SandboxWorkspaceRegistry.SlotKey, Sandbox> slot =
                registry.openIfPresent(ref);
        if (slot == null) {
            return false;
        }
        slot.lock().lock();
        try {
            Map<String, EntryInfo> info = slot.resource().files().readFileInfo(List.of(normalized));
            EntryInfo entry = info == null ? null : info.get(normalized);
            registry.renewIfNeeded(slot, ref.contextId());
            slot.touch();
            return entry != null;
        } catch (Exception e) {
            log.warn("OpenSandboxRuntime: 路径探测失败 scope={}, path={}, cause={}",
                    ref.key(), normalized, e.getMessage());
            return false;
        } finally {
            slot.lock().unlock();
        }
    }

    @Override
    public List<WorkspaceEntry> listFiles(WorkspaceRef ref, String dir, int depth) {
        if (!properties.isEnabled() || !properties.isReuseEnabled() || ref == null || !ref.pooled()) {
            return List.of();
        }
        String normalized = normalizeArtifactPath(dir);
        if (normalized == null) {
            return List.of();
        }
        SandboxSlotPool.Slot<SandboxWorkspaceRegistry.SlotKey, Sandbox> slot =
                registry.openIfPresent(ref);
        if (slot == null) {
            log.debug("OpenSandboxRuntime: 无活跃工作区，跳过目录列举 scope={}, dir={}", ref.key(), normalized);
            return List.of();
        }
        slot.lock().lock();
        try {
            List<EntryInfo> infos = slot.resource().files()
                    .listDirectory(normalized, depth > 0 ? depth : null);
            if (infos == null || infos.isEmpty()) {
                return List.of();
            }
            List<WorkspaceEntry> entries = new ArrayList<>(infos.size());
            for (EntryInfo info : infos) {
                if (info.getPath() == null) {
                    continue;
                }
                entries.add(new WorkspaceEntry(info.getPath(), info.getSize(),
                        info.getModifiedAt(), info.getType()));
            }
            registry.renewIfNeeded(slot, ref.contextId());
            slot.touch();
            return entries;
        } catch (Exception e) {
            log.warn("OpenSandboxRuntime: 目录列举失败 scope={}, dir={}, cause={}",
                    ref.key(), normalized, e.getMessage());
            return List.of();
        } finally {
            slot.lock().unlock();
        }
    }

    /**
     * 写入工作区内的一个文件（`SL-8`；`SL-10` 跨文档交叉验证落证据用）。
     *
     * <p><b>与 {@link #exec} 共用槽锁</b>：写入与命令执行并发会互相踩文件，故同一把槽锁串行化。
     * 写入失败一律降级为 {@code false}，不抛异常（与读侧一致）。</p>
     */
    @Override
    public boolean writeFile(WorkspaceRef ref, String path, byte[] content) {
        if (!properties.isEnabled() || !properties.isReuseEnabled() || ref == null || !ref.pooled()
                || content == null) {
            return false;
        }
        String normalized = normalizeArtifactPath(path);
        if (normalized == null) {
            log.warn("OpenSandboxRuntime: 写入路径非法（须为容器内绝对路径且不含 ..）path={}", path);
            return false;
        }
        SandboxSpec effective = SandboxSpecMapper.toDomain(properties.getSpec());
        AtomicReference<String> createFailure = new AtomicReference<>();
        SandboxSlotPool.Slot<SandboxWorkspaceRegistry.SlotKey, Sandbox> slot =
                registry.open(ref, effective, createFailure);
        if (slot == null) {
            log.warn("OpenSandboxRuntime: 写入失败（工作区不可用）scope={}, path={}, cause={}",
                    ref.key(), normalized, SandboxFactory.createFailedMessage(createFailure.get()));
            return false;
        }
        slot.lock().lock();
        try {
            slot.resource().files().write(List.of(WriteEntry.builder()
                    .path(normalized).data(content).mode(FILE_MODE).build()));
            registry.renewIfNeeded(slot, ref.contextId());
            slot.touch();
            log.info("OpenSandboxRuntime: 文件写入完成 scope={}, path={}, bytes={}",
                    ref.key(), normalized, content.length);
            return true;
        } catch (Exception e) {
            log.warn("OpenSandboxRuntime: 文件写入失败 scope={}, path={}, cause={}",
                    ref.key(), normalized, e.getMessage());
            return false;
        } finally {
            slot.lock().unlock();
        }
    }

    @Override
    public void release(WorkspaceRef ref) {
        registry.release(ref);
    }

    /**
     * 规整产物路径：必须是容器内绝对路径、不含 {@code ..}、不含空白。
     *
     * <p>不做「相对路径按 {@code /workspace} 补全」——那会把「模型写错路径」变成「读到了另一个文件」，
     * 静默错误比明确失败难查得多。</p>
     */
    static String normalizeArtifactPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.trim();
        if (!trimmed.startsWith("/") || trimmed.contains("..")) {
            return null;
        }
        return trimmed.length() > 1 && trimmed.endsWith("/")
                ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** 按扩展名推断 MIME（推断不出 ⇒ 二进制兜底；只覆盖当前会产出的几类）。 */
    private static String mimeOf(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return "application/octet-stream";
        }
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "html", "htm" -> "text/html";
            case "json" -> "application/json";
            case "md" -> "text/markdown";
            case "txt" -> "text/plain";
            case "css" -> "text/css";
            case "js", "mjs" -> "text/javascript";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    /** 字节的 SHA-256（64 位小写十六进制，与容器内 {@code sha256sum} 同口径）。 */
    private static String sha256Hex(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用: " + e.getMessage(), e);
        }
    }

    /** 解析命令超时（默认 300s，钳制到 [1, 300]；`SL-7` 起取代 30s）。 */
    static long resolveTimeoutSeconds(Integer timeoutSeconds) {
        long resolved = timeoutSeconds != null && timeoutSeconds > 0
                ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        return Math.min(resolved, MAX_TIMEOUT_SECONDS);
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
