package com.helloai.core.agent.runtime.sandbox.impl;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
 * <p><b>生命周期（2026-10-25 改为「同子任务复用」，`ADR-005` / `LOG-20261025-005`）</b>：
 * 同一 {@code subTaskId} 复用同一沙箱（{@link SandboxSlotPool} 按 key 缓存 + 每次使用
 * {@code renew} 续期 + 空闲超时淘汰），命令在槽内串行执行；<b>沙箱内文件跨调用保留</b>，
 * 模型可对上一轮写出的文件做增量编辑（对齐 deepseek-harness
 * {@code dsh-tool-bash-persistent} 的产品级口径）。子任务 turn 结束由
 * {@link #release(Long)} 显式销毁（防「上次尝试残留文件影响下次尝试」+ 保证不跨子任务共享）；
 * 命令超时 / SDK 异常 ⇒ 立即丢弃该槽（状态未知，下次调用重建）。</p>
 *
 * <p><b>退路</b>：{@code helloai.sandbox.opensandbox.reuse-enabled=false} 或
 * {@code subTaskId == null}（Reviewer / S-9 判定等无子任务上下文的调用）⇒ 回落
 * 原「即建即毁」一次性语义，零行为变化。</p>
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

    /** 回读工作区文件的命令超时（秒）：只是 `find` + `base64`，给 20s 足够。 */
    private static final int READ_FILE_TIMEOUT_SECONDS = 20;

    /**
     * 工作区回读时单文件读取上限（字节，python 侧截断）。
     *
     * <p>取值必须让 base64 后仍低于 {@link #MAX_OUTPUT_CHARS}：60000 字节 ⇒ 80000 字符
     * （base64 膨胀 4/3），留 20% 余量。中文正文按 UTF-8 3 字节/字估算 ≈ 2 万字，
     * 覆盖文档类验收（本事故 1800~2300 字）绰绰有余。</p>
     */
    private static final int READ_FILE_MAX_BYTES = 60_000;

    /** 回读结果里文件名头的前缀（便于日志定位「到底交回哪份文件」）。 */
    private static final String FILE_HEADER_PREFIX = "FILE:";

    /**
     * 回读命令：挑出工作区里<b>最大的 .md</b>，以 <b>base64</b> 单行输出。
     *
     * <p><b>为什么用 base64 而不是直接 {@code cat}</b>：本服务的输出通路会把多条 stdout 消息用
     * {@code \n} 拼接（{@link #renderOutput}），直接打印 Markdown 会被插入额外换行、破坏正文结构；
     * base64 单行无换行，Java 侧解码后得到<b>字节级原样</b>的内容（含换行、缩进、代码块）。</p>
     *
     * <p><b>为什么不用 {@code tail}/{@code head} 截断</b>：那会切坏多字节字符与 Markdown 结构；
     * 截断放在 python 侧按<b>字节</b>做（{@link #READ_FILE_MAX_BYTES}）。</p>
     *
     * <p><b>为什么优先「含 Markdown 标题」的文件</b>：工作区里常有比正文更大的<b>草稿/日志</b>
     * （{@code scratch.md}、构建输出、统计结果），只按字节数取最大会把垃圾当交付物送出去。
     * 含行首 {@code # 标题} 是「这是一篇文档」的最廉价信号；全都不是文档时才退回「最大 .md」。</p>
     */
    private static final String READ_WORKSPACE_FILE_COMMAND = """
            cd /workspace 2>/dev/null || cd /tmp 2>/dev/null || exit 0; \
            f=$(find . -maxdepth 2 -type f -name '*.md' 2>/dev/null | xargs -r grep -lE '^#{1,6} ' 2>/dev/null | xargs -r wc -c 2>/dev/null | grep -v total | sort -rn | head -1 | awk '{print $NF}'); \
            [ -z "$f" ] && f=$(find . -maxdepth 2 -type f -name '*.md' -size +200c 2>/dev/null | xargs -r wc -c 2>/dev/null | grep -v total | sort -rn | head -1 | awk '{print $NF}'); \
            [ -z "$f" ] && exit 0; \
            echo "FILE:$f"; \
            python3 -c 'import base64,sys;d=open(sys.argv[1],"rb").read()[:60000];sys.stdout.write(base64.b64encode(d).decode())' "$f"
            """;

    private final OpenSandboxProperties properties;

    /**
     * 复用槽池（`ADR-005`，2026-10-25）：key = {@code subTaskId}，槽内命令串行执行。
     *
     * <p>只在 {@code reuse-enabled=true} 且 {@code subTaskId != null} 时使用；
     * 生命周期出口 = {@link #release(Long)}（turn 结束）/ 空闲超时 / 命令未正常结束。</p>
     */
    private final SandboxSlotPool<Sandbox> pool = new SandboxSlotPool<>();

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
        // 退路：关闭复用 / 无子任务上下文（Reviewer、S-9 判定）⇒ 原「即建即毁」一次性语义
        if (!properties.isReuseEnabled() || subTaskId == null) {
            return executeOneShot(subTaskId, command, timeoutSec, start);
        }
        SandboxSlotPool.Slot<Sandbox> slot =
                pool.acquire(subTaskId, idleTtlMillis(), () -> openSandbox(subTaskId), this::destroy);
        if (slot == null) {
            return new ExecuteResponse("sandbox create failed (opener returned null)",
                    ExecuteResponse.EXIT_CODE_ERROR, false);
        }
        slot.lock().lock();
        try {
            return runInSlot(subTaskId, slot, command, timeoutSec, start);
        } finally {
            slot.lock().unlock();
        }
    }

    @Override
    public void release(Long subTaskId) {
        if (subTaskId == null) {
            return;
        }
        if (pool.remove(subTaskId, this::destroy)) {
            log.info("OpenSandboxExecutionService: 复用沙箱已释放 subTaskId={}, 在册={}",
                    subTaskId, pool.size());
        }
    }

    /** 当前在册的复用沙箱数（观测 / 单测用）。 */
    public int activeSandboxes() {
        return pool.size();
    }

    /**
     * 回读工作区里最大的一份 {@code .md} 正文（`LOG-20261025-010`）。
     *
     * <p><b>只读</b>：{@link SandboxSlotPool#contains} 无活跃槽即返回 null，绝不为了读文件
     * 白建沙箱（3 秒 + 一份资源，且必然读到空目录）。opener 传 {@code () -> null}：
     * 即使 contains 与 acquire 之间槽被别的线程摘掉，也只会得到 null 而不是新建。</p>
     *
     * <p>失败一律降级为 {@code null}（与 {@link #execute} 同一条容错哲学）：
     * 回读是「锦上添花」——拿不到时调用方照原样提交对话正文，不得因回读失败影响执行结果。</p>
     */
    @Override
    public String readWorkspaceFile(Long subTaskId) {
        if (!properties.isEnabled() || !properties.isReuseEnabled() || subTaskId == null) {
            return null;
        }
        if (!pool.contains(subTaskId)) {
            log.debug("OpenSandboxExecutionService: 无活跃工作区，跳过回读 subTaskId={}", subTaskId);
            return null;
        }
        SandboxSlotPool.Slot<Sandbox> slot =
                pool.acquire(subTaskId, idleTtlMillis(), () -> null, this::destroy);
        if (slot == null) {
            log.debug("OpenSandboxExecutionService: 工作区已并发释放，跳过回读 subTaskId={}", subTaskId);
            return null;
        }
        slot.lock().lock();
        try {
            ExecuteResponse response = runCommand(subTaskId, slot.resource(),
                    READ_WORKSPACE_FILE_COMMAND, READ_FILE_TIMEOUT_SECONDS, System.currentTimeMillis());
            if (response.exitCode() != 0) {
                log.info("OpenSandboxExecutionService: 回读工作区非正常退出 subTaskId={}, exitCode={}",
                        subTaskId, response.exitCode());
                return null;
            }
            return decodeWorkspacePayload(subTaskId, response.output());
        } catch (Exception e) {
            log.warn("OpenSandboxExecutionService: 回读工作区异常 subTaskId={}, cause={}",
                    subTaskId, e.getMessage());
            return null;
        } finally {
            slot.lock().unlock();
        }
    }

    /**
     * 解析回读结果：首行 {@code FILE:<name>} 取名（仅日志用），其余为 base64 正文。
     *
     * <p>先剔全部空白再解码——{@link #renderOutput} 会在多条 stdout 消息之间插入 {@code \n}，
     * base64 对空白不敏感，剔除后即为字节级原样内容。</p>
     */
    private String decodeWorkspacePayload(Long subTaskId, String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String payload = output.strip();
        String fileName = "";
        int newline = payload.indexOf('\n');
        if (payload.startsWith(FILE_HEADER_PREFIX) && newline > 0) {
            fileName = payload.substring(FILE_HEADER_PREFIX.length(), newline).trim();
            payload = payload.substring(newline + 1);
        }
        StringBuilder encoded = new StringBuilder(payload.length());
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (!Character.isWhitespace(c)) {
                encoded.append(c);
            }
        }
        if (encoded.length() == 0) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encoded.toString());
            String text = new String(raw, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return null;
            }
            log.info("OpenSandboxExecutionService: 工作区正文回读成功 subTaskId={}, file={}, chars={}",
                    subTaskId, fileName, text.length());
            return text;
        } catch (IllegalArgumentException e) {
            // 多半是输出越过 MAX_OUTPUT_CHARS 被截断（半个 base64 块）或 stderr 混入
            log.warn("OpenSandboxExecutionService: 工作区正文解码失败（疑似输出被截断）subTaskId={}, "
                    + "payloadChars={}, cause={}", subTaskId, encoded.length(), e.getMessage());
            return null;
        }
    }

    /** 一次性语义（复用关闭 / 无子任务上下文）：建 → 跑 → 无条件 kill（原 S-7 行为，零变化）。 */
    private ExecuteResponse executeOneShot(Long subTaskId, String command, long timeoutSec, long start) {
        Sandbox sandbox = null;
        try {
            sandbox = openSandbox(subTaskId);
            if (sandbox == null) {
                return new ExecuteResponse("sandbox create failed (opener returned null)",
                        ExecuteResponse.EXIT_CODE_ERROR, false);
            }
            return runCommand(subTaskId, sandbox, command, timeoutSec, start);
        } catch (Exception e) {
            log.warn("OpenSandboxExecutionService: command failed subTaskId={}, cause={}",
                    subTaskId, e.getMessage());
            return new ExecuteResponse("sandbox execution failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), ExecuteResponse.EXIT_CODE_ERROR, false);
        } finally {
            destroy(sandbox);
        }
    }

    /**
     * 在复用槽内执行：成功后 {@code renew} 续期保活（文件保留）；命令未正常结束 ⇒ 丢弃该槽。
     *
     * <p>「未正常结束」= 超时（{@code exitCode=124}）或 SDK 异常（{@code -1}）——此时容器内状态未知
     * （对齐 DSH：timeout / 取消会关闭 shell 并告知下一轮从干净环境开始）。普通非零退出
     * （如 {@code grep} 没命中、{@code python} 校验脚本报 False）<b>不</b>丢弃——那正是
     * 「对上一轮产物做校验」的正常用法。</p>
     */
    private ExecuteResponse runInSlot(Long subTaskId, SandboxSlotPool.Slot<Sandbox> slot,
                                      String command, long timeoutSec, long start) {
        Sandbox sandbox = slot.resource();
        ExecuteResponse response;
        try {
            response = runCommand(subTaskId, sandbox, command, timeoutSec, start);
        } catch (Exception e) {
            pool.remove(subTaskId, this::destroy);
            log.warn("OpenSandboxExecutionService: 复用沙箱执行异常，已丢弃待重建 subTaskId={}, cause={}",
                    subTaskId, e.getMessage());
            return new ExecuteResponse("sandbox execution failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), ExecuteResponse.EXIT_CODE_ERROR, false);
        }
        if (response.exitCode() == ExecuteResponse.EXIT_CODE_TIMEOUT
                || response.exitCode() == ExecuteResponse.EXIT_CODE_ERROR) {
            pool.remove(subTaskId, this::destroy);
            log.info("OpenSandboxExecutionService: 命令未正常结束（exitCode={}），丢弃复用沙箱 subTaskId={}",
                    response.exitCode(), subTaskId);
            return response;
        }
        renewQuietly(sandbox, subTaskId);
        slot.touch();
        return response;
    }

    /** 执行一条命令并渲染统一结果（纯执行，不碰生命周期）。 */
    private ExecuteResponse runCommand(Long subTaskId, Sandbox sandbox, String command,
                                       long timeoutSec, long start) {
        String sandboxId = sandbox.getId();
        Execution execution = sandbox.commands().run(
                RunCommandRequest.builder()
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
    }

    /**
     * 建沙箱（复用槽 opener 与一次性路径共用）。失败返回 {@code null}，
     * 由调用方降级为结构化失败（不抛异常，与既有容错哲学一致）。
     */
    private Sandbox openSandbox(Long subTaskId) {
        try {
            Sandbox sandbox = Sandbox.builder()
                    .connectionConfig(connectionConfig())
                    .image(resolveImage())
                    .readyTimeout(Duration.ofSeconds(READY_TIMEOUT_SECONDS))
                    .timeout(Duration.ofSeconds(SANDBOX_TIMEOUT_SECONDS))
                    .build();
            log.info("OpenSandboxExecutionService: sandbox created id={}, subTaskId={}, reuse={}, ttl={}s",
                    sandbox.getId(), subTaskId,
                    properties.isReuseEnabled() && subTaskId != null, SANDBOX_TIMEOUT_SECONDS);
            return sandbox;
        } catch (Exception e) {
            log.warn("OpenSandboxExecutionService: sandbox create failed subTaskId={}, cause={}",
                    subTaskId, e.getMessage());
            return null;
        }
    }

    /** 续期（保活工作区）；失败只降级日志，不阻断命令结果返回。 */
    private void renewQuietly(Sandbox sandbox, Long subTaskId) {
        try {
            sandbox.renew(Duration.ofSeconds(Math.max(properties.getIdleTtlSeconds(), 60L)));
        } catch (Exception e) {
            log.debug("OpenSandboxExecutionService: renew 失败（不阻断）subTaskId={}, cause={}",
                    subTaskId, e.getMessage());
        }
    }

    /** 销毁沙箱（幂等、绝不抛异常）：SDK {@code close()} 不删远端沙箱，必须显式 {@code kill()}。 */
    private void destroy(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        String sandboxId = null;
        try {
            sandboxId = sandbox.getId();
            sandbox.kill();
            log.info("OpenSandboxExecutionService: sandbox killed id={}", sandboxId);
        } catch (Exception killEx) {
            log.warn("OpenSandboxExecutionService: kill failed sandboxId={}, cause={}",
                    sandboxId, killEx.getMessage());
        }
        try {
            sandbox.close();
        } catch (Exception closeEx) {
            log.warn("OpenSandboxExecutionService: close failed sandboxId={}, cause={}",
                    sandboxId, closeEx.getMessage());
        }
    }

    /** 复用的空闲上限（毫秒）；{@code <=0} ⇒ 不做过期判定。 */
    private long idleTtlMillis() {
        return Math.max(properties.getIdleTtlSeconds(), 0L) * 1000L;
    }

    /** app → OpenSandbox server 的连接配置（拓扑：不同网时经 server 代理，`LOG-20261017-009`）。 */
    private ConnectionConfig connectionConfig() {
        return ConnectionConfig.builder()
                .domain(properties.getUrl())
                .apiKey(properties.getApiKey())
                .useServerProxy(true)
                .requestTimeout(Duration.ofSeconds(MAX_TIMEOUT_SECONDS + 60))
                .build();
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
