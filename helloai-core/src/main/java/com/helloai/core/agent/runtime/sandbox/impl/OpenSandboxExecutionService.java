package com.helloai.core.agent.runtime.sandbox.impl;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkPolicy;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.ExecuteResponse;
import com.helloai.core.agent.runtime.sandbox.SandboxArtifact;
import com.helloai.core.agent.runtime.sandbox.SandboxExecutionService;
import com.helloai.core.agent.runtime.sandbox.SandboxSpec;
import com.helloai.core.agent.runtime.sandbox.SkillInstallResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** 技能包在容器内的落点根（`SKX-6`；容器内默认<b>没有</b> {@code /workspace}，须先建）。 */
    private static final String SKILL_ROOT_DIR = "/workspace/skills";

    /** 幂等标记文件名（写在包根，记录版本 + 内容哈希树）。 */
    private static final String SKILL_MARKER_FILE = ".helloai-skill.json";

    /** 技能包文件权限（644）：全是数据文件，不需要可执行位（入口靠 {@code node bin/xxx.mjs} 显式调用）。 */
    private static final int SKILL_FILE_MODE = 644;

    /** 目录权限（755）。 */
    private static final int SKILL_DIR_MODE = 755;

    /** 默认单次产物回读上限（字节，4MB）：覆盖 archify 的 ~820KB HTML 及其回执，也不会一次读爆内存。 */
    private static final int DEFAULT_ARTIFACT_MAX_BYTES = 4 * 1024 * 1024;

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

    /** 技能包加载器（classpath 枚举 + 完整性校验，`SKX-6`）。 */
    private final SkillPackageLoader skillPackageLoader;

    /**
     * 复用槽池（`ADR-005`，2026-10-25）：key = {@code (subTaskId, 规格指纹)}，槽内命令串行执行。
     *
     * <p>只在 {@code reuse-enabled=true} 且 {@code subTaskId != null} 时使用；
     * 生命周期出口 = {@link #release(Long)}（turn 结束，释放该子任务<b>全部规格</b>）/ 空闲超时 /
     * 命令未正常结束。</p>
     */
    private final SandboxSlotPool<SlotKey, Sandbox> pool = new SandboxSlotPool<>();

    /**
     * 复用槽键（`SKX-5`）：子任务 + 规格指纹。
     *
     * <p>为什么不能只用 {@code subTaskId}：按调用分层规格后，同一子任务会先后要两种镜像
     * （python 验算 / node 出图）。裸 {@code subTaskId} 当键 ⇒ 第二次命中第一次的槽，
     * 拿到没有目标运行时的容器，命令以「node: not found」失败，且失败看起来像环境问题。</p>
     */
    private record SlotKey(Long subTaskId, String specFingerprint) {
    }

    /** 标记文件读写用的 JSON 编解码（只处理平台自己写的小对象，不承载业务模型）。 */
    private final ObjectMapper markerMapper = new ObjectMapper();

    public OpenSandboxExecutionService(OpenSandboxProperties properties,
                                       SkillPackageLoader skillPackageLoader) {
        this.properties = properties;
        this.skillPackageLoader = skillPackageLoader;
    }

    @Override
    public ExecuteResponse execute(Long subTaskId, String command, Integer timeoutSeconds) {
        return execute(subTaskId, null, command, timeoutSeconds);
    }

    @Override
    public ExecuteResponse execute(Long subTaskId, SandboxSpec spec, String command,
                                   Integer timeoutSeconds) {
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
        // 退路：关闭复用 / 无子任务上下文（Reviewer、S-9 判定）⇒ 原「即建即毁」一次性语义
        if (!properties.isReuseEnabled() || subTaskId == null) {
            return executeOneShot(subTaskId, effective, command, timeoutSec, start);
        }
        SlotKey key = new SlotKey(subTaskId, SandboxSpecMapper.fingerprint(effective));
        SandboxSlotPool.Slot<SlotKey, Sandbox> slot =
                pool.acquire(key, idleTtlMillis(), () -> openSandbox(subTaskId, effective), this::destroy);
        if (slot == null) {
            return new ExecuteResponse("sandbox create failed (opener returned null)",
                    ExecuteResponse.EXIT_CODE_ERROR, false);
        }
        slot.lock().lock();
        try {
            return runInSlot(key, subTaskId, slot, command, timeoutSec, start);
        } finally {
            slot.lock().unlock();
        }
    }

    @Override
    public void release(Long subTaskId) {
        if (subTaskId == null) {
            return;
        }
        int removed = pool.removeMatching(key -> Objects.equals(key.subTaskId(), subTaskId), this::destroy);
        if (removed > 0) {
            log.info("OpenSandboxExecutionService: 复用沙箱已释放 subTaskId={}, 释放槽数={}, 在册={}",
                    subTaskId, removed, pool.size());
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
        // 回读只知道 subTaskId，不知道当时用的规格 ⇒ 按谓词找在册键（不创建）
        SlotKey key = pool.findKey(k -> Objects.equals(k.subTaskId(), subTaskId)).orElse(null);
        if (key == null) {
            log.debug("OpenSandboxExecutionService: 无活跃工作区，跳过回读 subTaskId={}", subTaskId);
            return null;
        }
        SandboxSlotPool.Slot<SlotKey, Sandbox> slot =
                pool.acquire(key, idleTtlMillis(), () -> null, this::destroy);
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

    // ---------------------------------------------------------------------
    // 技能包注入（`SKX-6`，2026-10-26）
    // ---------------------------------------------------------------------

    /**
     * 把 classpath 里的技能包批量写入该子任务的复用工作区（`SKX-6`）。
     *
     * <p><b>为什么走「子任务槽」而不是「新建一个沙箱装包」</b>：技能执行的形态是
     * 「装包 → 跑 {@code node bin/xxx.mjs}」（`SKX-9`），两步必须在<b>同一个容器</b>里。
     * 复用槽键与 {@link #execute} 完全一致（子任务 + 规格指纹），所以装完包紧接着执行命令时
     * 直接命中这个已装好的槽，省掉一次建沙箱（实测 0.7~1.2s）也保证文件在。</p>
     *
     * <p><b>失败即丢弃槽</b>：批量写可能「写了一半」，留在容器里的半装包会在后续
     * {@code node} 运行时报缺模块——那种错误的排查成本远高于重建一个容器。
     * 与「命令未正常结束即丢弃槽」是同一条口径。</p>
     */
    @Override
    public SkillInstallResult installSkillPackage(Long subTaskId, SandboxSpec spec,
                                                  String packageName, String version) {
        if (!properties.isEnabled()) {
            return SkillInstallResult.fail(
                    "sandbox execution disabled (helloai.sandbox.opensandbox.enabled=false)");
        }
        if (!properties.isReuseEnabled()) {
            return SkillInstallResult.fail("skill install requires reuse-enabled=true"
                    + "（一次性沙箱装完即销毁，包无处可用）");
        }
        if (subTaskId == null) {
            return SkillInstallResult.fail("subTaskId must not be null when installing a skill package");
        }
        LoadedSkillPackage pkg;
        try {
            pkg = skillPackageLoader.load(packageName, version);
        } catch (Exception e) {
            log.warn("OpenSandboxExecutionService: 技能包加载失败 name={}, version={}, cause={}",
                    packageName, version, e.getMessage());
            return SkillInstallResult.fail("技能包加载失败: " + e.getMessage());
        }

        SandboxSpec effective = spec != null ? spec : SandboxSpecMapper.toDomain(properties.getSpec());
        SlotKey key = new SlotKey(subTaskId, SandboxSpecMapper.fingerprint(effective));
        String targetDir = SKILL_ROOT_DIR + "/" + pkg.name();
        long start = System.currentTimeMillis();
        SandboxSlotPool.Slot<SlotKey, Sandbox> slot = pool.acquire(key, idleTtlMillis(),
                () -> openSandbox(subTaskId, effective), this::destroy);
        if (slot == null) {
            return SkillInstallResult.fail("sandbox create failed (opener returned null)");
        }
        slot.lock().lock();
        try {
            SkillInstallResult result = installInto(subTaskId, slot.resource(), pkg, targetDir, start);
            if (result.success()) {
                renewQuietly(slot.resource(), subTaskId);
                slot.touch();
            } else {
                pool.remove(key, this::destroy);
            }
            return result;
        } catch (Exception e) {
            pool.remove(key, this::destroy);
            log.warn("OpenSandboxExecutionService: 技能包注入异常，已丢弃沙箱 subTaskId={}, name={}, cause={}",
                    subTaskId, pkg.name(), e.getMessage());
            return SkillInstallResult.fail("技能包注入异常: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            slot.lock().unlock();
        }
    }

    /**
     * 在已锁定的槽内完成「建目录 → 幂等探测 → 批量写 → 写标记 → 校验」。
     *
     * <p><b>为什么先建目录再探测</b>：SDK 的 {@code readFileInfo} 对不存在的路径会抛 404，并在
     * <b>ERROR 级别</b>打一行 {@code Failed to get file info}——而「首次安装、标记还不存在」是
     * 完全正常的路径。把它留在日志里，等于每次正常安装都印一条假错误，告警迟早会误报。
     * 先 {@code createDirectories}（幂等）再 {@code listDirectory} 找标记，探测就永不 404。</p>
     *
     * <p><b>标记最后写</b>：让「标记存在」等价于「整包已写完并通过校验」。若先写标记，
     * 进程中途退出会留下「标记说有、文件不全」的容器，下次进来直接跳过 ⇒ 永久坏包。</p>
     */
    private SkillInstallResult installInto(Long subTaskId, Sandbox sandbox, LoadedSkillPackage pkg,
                                           String targetDir, long start) {
        sandbox.files().createDirectories(directoryEntries(pkg, targetDir));
        if (alreadyInstalled(sandbox, targetDir, pkg)) {
            long costMs = System.currentTimeMillis() - start;
            log.info("OpenSandboxExecutionService: 技能包已存在同内容，跳过注入 subTaskId={}, name={}, "
                            + "version={}, files={}, costMs={}",
                    subTaskId, pkg.name(), pkg.version(), pkg.fileCount(), costMs);
            return SkillInstallResult.ok(pkg.fileCount(), pkg.totalBytes(), targetDir, true,
                    "已存在同版本同内容（" + pkg.name() + "@" + pkg.version() + "），跳过写入");
        }

        writePayload(subTaskId, sandbox, pkg, targetDir);
        sandbox.files().writeFile(markerEntry(targetDir + "/" + SKILL_MARKER_FILE, pkg));
        verifyInstalled(sandbox, pkg, targetDir);

        long costMs = System.currentTimeMillis() - start;
        log.info("OpenSandboxExecutionService: 技能包注入完成 subTaskId={}, name={}, version={}, "
                        + "files={}, bytes={}, target={}, costMs={}",
                subTaskId, pkg.name(), pkg.version(), pkg.fileCount(), pkg.totalBytes(), targetDir, costMs);
        return SkillInstallResult.ok(pkg.fileCount(), pkg.totalBytes(), targetDir, false,
                "注入完成 files=" + pkg.fileCount() + " bytes=" + pkg.totalBytes() + " costMs=" + costMs);
    }

    /**
     * 幂等探测：目标目录里存在标记文件且哈希树一致 ⇒ 已装好。
     *
     * <p>用 {@code listDirectory} 而不是直接读标记：目录已在上一步建好，列目录不会 404，
     * 「标记不存在」表现为<b>空列表</b>而非异常——首次安装不该在日志里留下 ERROR（见调用处注释）。</p>
     */
    private boolean alreadyInstalled(Sandbox sandbox, String targetDir, LoadedSkillPackage pkg) {
        String markerPath = targetDir + "/" + SKILL_MARKER_FILE;
        try {
            boolean markerExists = sandbox.files().listDirectory(targetDir, null).stream()
                    .anyMatch(info -> info.getPath() != null && info.getPath().endsWith("/" + SKILL_MARKER_FILE));
            if (!markerExists) {
                return false;
            }
            JsonNode marker = markerMapper.readTree(sandbox.files().readFile(markerPath, "UTF-8", null));
            String installedTree = marker.path("sha256Tree").asText(null);
            if (pkg.sha256Tree().equalsIgnoreCase(installedTree)) {
                return true;
            }
            log.info("OpenSandboxExecutionService: 已有技能包内容不同，将覆盖写入 name={}, installed={}, expected={}",
                    pkg.name(), abbreviate(installedTree), abbreviate(pkg.sha256Tree()));
            return false;
        } catch (Exception e) {
            log.debug("OpenSandboxExecutionService: 幂等标记探测失败（按未安装处理）path={}, cause={}",
                    markerPath, e.getMessage());
            return false;
        }
    }

    /**
     * 写载荷：先批量（`SKX-3` 实测 146ms / 1 次往返），失败再降级逐文件重试。
     *
     * <p><b>为什么保留逐文件退路</b>：批量写是「全有或全无」的形态——一批里任何一个文件被拒
     * （权限 / 路径 / 服务端限制）整批就失败，而失败信息往往只给第一个错。逐文件重试能<b>定位</b>
     * 到具体哪个文件装不进去，也能救回「只有个别文件失败」的场景。写是覆盖语义，重试天然幂等。</p>
     */
    private void writePayload(Long subTaskId, Sandbox sandbox, LoadedSkillPackage pkg, String targetDir) {
        List<WriteEntry> entries = new ArrayList<>(pkg.fileCount());
        for (LoadedSkillPackage.Entry entry : pkg.entries()) {
            entries.add(fileEntry(targetDir + "/" + entry.relativePath(), entry.data()));
        }
        long start = System.currentTimeMillis();
        try {
            sandbox.files().write(entries);
            log.info("OpenSandboxExecutionService: 技能包批量写入完成 subTaskId={}, files={}, costMs={}",
                    subTaskId, entries.size(), System.currentTimeMillis() - start);
            return;
        } catch (Exception batchError) {
            log.warn("OpenSandboxExecutionService: 批量写入失败，降级逐文件重试 subTaskId={}, files={}, cause={}",
                    subTaskId, entries.size(), batchError.getMessage());
        }
        List<String> failed = new ArrayList<>();
        for (LoadedSkillPackage.Entry entry : pkg.entries()) {
            try {
                sandbox.files().writeFile(fileEntry(targetDir + "/" + entry.relativePath(), entry.data()));
            } catch (Exception e) {
                failed.add(entry.relativePath());
                if (failed.size() <= 3) {
                    log.warn("OpenSandboxExecutionService: 逐文件写入失败 path={}, cause={}",
                            entry.relativePath(), e.getMessage());
                }
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("逐文件重试后仍有 " + failed.size() + " 个文件写入失败，首个: "
                    + failed.get(0));
        }
        log.info("OpenSandboxExecutionService: 技能包逐文件写入完成 subTaskId={}, files={}, costMs={}",
                subTaskId, pkg.fileCount(), System.currentTimeMillis() - start);
    }

    /**
     * 注入后校验：文件数与总字节必须与包一致。
     *
     * <p>校验的是<b>服务端视角</b>（{@code readFileInfo} 读回的大小），不是「我们发了多少」——
     * 传输截断、服务端配额导致的部分写入都会在这里暴露，而这正是「装进去了但跑不起来」的根因。</p>
     */
    private void verifyInstalled(Sandbox sandbox, LoadedSkillPackage pkg, String targetDir) {
        List<String> paths = new ArrayList<>(pkg.fileCount());
        for (LoadedSkillPackage.Entry entry : pkg.entries()) {
            paths.add(targetDir + "/" + entry.relativePath());
        }
        Map<String, EntryInfo> actual = sandbox.files().readFileInfo(paths);
        int actualCount = actual == null ? 0 : actual.size();
        long actualBytes = actual == null ? 0L
                : actual.values().stream().mapToLong(EntryInfo::getSize).sum();
        if (actualCount != pkg.fileCount() || actualBytes != pkg.totalBytes()) {
            throw new IllegalStateException("注入校验失败 target=" + targetDir
                    + "：期望 files=" + pkg.fileCount() + " bytes=" + pkg.totalBytes()
                    + "，实际 files=" + actualCount + " bytes=" + actualBytes);
        }
    }

    /**
     * 目录清单：目标根 + 全部祖先目录，按路径升序（父在前，避免服务端不做递归建目录时子目录失败）。
     *
     * <p>{@code /workspace} 与 {@code /workspace/skills} 也显式列出：容器内默认<b>没有</b>
     * {@code /workspace}（`SKX-1` 实测 {@code pwd=/}），不能指望上层已建。</p>
     */
    private static List<WriteEntry> directoryEntries(LoadedSkillPackage pkg, String targetDir) {
        Set<String> dirs = new LinkedHashSet<>();
        dirs.add("/workspace");
        dirs.add(SKILL_ROOT_DIR);
        dirs.add(targetDir);
        for (LoadedSkillPackage.Entry entry : pkg.entries()) {
            String relative = entry.relativePath();
            int index = relative.lastIndexOf('/');
            while (index > 0) {
                dirs.add(targetDir + "/" + relative.substring(0, index));
                index = relative.lastIndexOf('/', index - 1);
            }
        }
        List<String> sorted = new ArrayList<>(dirs);
        sorted.sort(Comparator.naturalOrder());
        List<WriteEntry> entries = new ArrayList<>(sorted.size());
        for (String dir : sorted) {
            entries.add(WriteEntry.builder().path(dir).data(new byte[0]).mode(SKILL_DIR_MODE).build());
        }
        return entries;
    }

    private static WriteEntry fileEntry(String path, byte[] data) {
        return WriteEntry.builder().path(path).data(data).mode(SKILL_FILE_MODE).build();
    }

    /** 幂等标记内容（版本 + 内容哈希树 + 计数 + 时间；不含任何平台凭据）。 */
    private WriteEntry markerEntry(String markerPath, LoadedSkillPackage pkg) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("packageName", pkg.name());
        marker.put("version", pkg.version());
        marker.put("fileCount", pkg.fileCount());
        marker.put("totalBytes", pkg.totalBytes());
        marker.put("sha256Tree", pkg.sha256Tree());
        marker.put("installedAt", OffsetDateTime.now().toString());
        try {
            return WriteEntry.builder()
                    .path(markerPath)
                    .data(markerMapper.writeValueAsString(marker))
                    .mode(SKILL_FILE_MODE)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("幂等标记序列化失败: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String value) {
        return value == null || value.length() <= 16 ? value : value.substring(0, 16) + "…";
    }

    // ---------------------------------------------------------------------
    // 产物按指定路径回读（`SKX-7`，2026-10-26）
    // ---------------------------------------------------------------------

    /**
     * 回读指定路径的产物（`SKX-7`）。
     *
     * <p><b>三步都省不掉</b>：① {@code readFileInfo} 判存在 + 拿大小（决定要不要走 Range 读）；
     * ② {@code readByteArray} 取字节（一次读回 823KB 实测 34ms，分段读只会更慢）；
     * ③ 本地算 {@code sha256}——哈希必须算在<b>字节</b>上，先解码成字符串再算会被编码差异毁掉。</p>
     *
     * <p><b>超限不报错而是截断</b>：调用方可能只想读一份 10MB 日志的头几 KB 做判断。但截断后
     * 的 {@code sha256} 只代表片段，故结果里带 {@code truncated} 硬标志——**拿截断结果去和完整
     * 文件回执对账，比出来必然不一致**，消费方必须先看这个标志。</p>
     */
    @Override
    public SandboxArtifact readArtifact(Long subTaskId, String path, int maxBytes) {
        if (!properties.isEnabled() || !properties.isReuseEnabled() || subTaskId == null) {
            return null;
        }
        String normalized = normalizeArtifactPath(path);
        if (normalized == null) {
            log.warn("OpenSandboxExecutionService: 产物路径非法（须为容器内绝对路径且不含 ..）path={}", path);
            return null;
        }
        // 只读：按谓词找在册键，绝不为了读文件新建沙箱（3s + 一份资源，且必然读到空目录）
        SlotKey key = pool.findKey(k -> Objects.equals(k.subTaskId(), subTaskId)).orElse(null);
        if (key == null) {
            log.debug("OpenSandboxExecutionService: 无活跃工作区，跳过产物回读 subTaskId={}", subTaskId);
            return null;
        }
        SandboxSlotPool.Slot<SlotKey, Sandbox> slot =
                pool.acquire(key, idleTtlMillis(), () -> null, this::destroy);
        if (slot == null) {
            log.debug("OpenSandboxExecutionService: 工作区已并发释放，跳过产物回读 subTaskId={}", subTaskId);
            return null;
        }
        slot.lock().lock();
        long start = System.currentTimeMillis();
        try {
            Sandbox sandbox = slot.resource();
            Map<String, EntryInfo> info = sandbox.files().readFileInfo(List.of(normalized));
            EntryInfo entry = info == null ? null : info.get(normalized);
            if (entry == null) {
                log.info("OpenSandboxExecutionService: 产物不存在 subTaskId={}, path={}", subTaskId, normalized);
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
            renewQuietly(sandbox, subTaskId);
            slot.touch();
            log.info("OpenSandboxExecutionService: 产物回读完成 subTaskId={}, path={}, size={}, readBytes={}, "
                            + "truncated={}, sha256={}, costMs={}",
                    subTaskId, normalized, expectedSize, bytes.length, truncated, sha256,
                    System.currentTimeMillis() - start);
            return new SandboxArtifact(normalized, bytes, mimeOf(normalized), sha256, truncated);
        } catch (Exception e) {
            log.warn("OpenSandboxExecutionService: 产物回读失败 subTaskId={}, path={}, cause={}",
                    subTaskId, normalized, e.getMessage());
            return null;
        } finally {
            slot.lock().unlock();
        }
    }

    /**
     * 规整产物路径：必须是容器内绝对路径、不含 {@code ..}、不含空白。
     *
     * <p>不做「相对路径按 /workspace 补全」——那会把「模型写错路径」变成「读到了另一个文件」，
     * 静默错误比明确失败难查得多。</p>
     */
    private static String normalizeArtifactPath(String path) {
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

    /** 一次性语义（复用关闭 / 无子任务上下文）：建 → 跑 → 无条件 kill（原 S-7 行为，零变化）。 */
    private ExecuteResponse executeOneShot(Long subTaskId, SandboxSpec spec, String command,
                                           long timeoutSec, long start) {
        Sandbox sandbox = null;
        try {
            sandbox = openSandbox(subTaskId, spec);
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
    private ExecuteResponse runInSlot(SlotKey key, Long subTaskId, SandboxSlotPool.Slot<SlotKey, Sandbox> slot,
                                      String command, long timeoutSec, long start) {
        Sandbox sandbox = slot.resource();
        ExecuteResponse response;
        try {
            response = runCommand(subTaskId, sandbox, command, timeoutSec, start);
        } catch (Exception e) {
            pool.remove(key, this::destroy);
            log.warn("OpenSandboxExecutionService: 复用沙箱执行异常，已丢弃待重建 subTaskId={}, spec={}, cause={}",
                    subTaskId, key.specFingerprint(), e.getMessage());
            return new ExecuteResponse("sandbox execution failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), ExecuteResponse.EXIT_CODE_ERROR, false);
        }
        if (response.exitCode() == ExecuteResponse.EXIT_CODE_TIMEOUT
                || response.exitCode() == ExecuteResponse.EXIT_CODE_ERROR) {
            pool.remove(key, this::destroy);
            log.info("OpenSandboxExecutionService: 命令未正常结束（exitCode={}），丢弃复用沙箱 subTaskId={}, spec={}",
                    response.exitCode(), subTaskId, key.specFingerprint());
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
     *
     * <p><b>`SKX-5` 起 spec 真正被消费</b>（2026-10-26）。此前只喂了 {@code image}，
     * {@code resourceLimits} / {@code networkPolicy} / {@code entrypoint} / {@code timeoutSeconds}
     * 全是「声明了但没接」，于是「按调用分层规格」名不副实。现在逐项落到 SDK Builder
     * （实测 `sandbox-1.1.1` 的 Builder 支持 {@code image} / {@code entrypoint} /
     * {@code resource} / {@code networkPolicy} / {@code timeout}）。</p>
     *
     * <p><b>刻意不喂的字段</b>：{@code scope}——SDK 无对应概念，且「按 scope 复用」是另一套
     * 策略（当前复用口径是「按子任务 + 规格」），单独裁定前不接。</p>
     *
     * @param subTaskId 子任务 id（仅日志定位，可为 null）
     * @param spec      生效规格（非 null；调用方已回落默认）
     */
    private Sandbox openSandbox(Long subTaskId, SandboxSpec spec) {
        long sandboxTimeout = resolveSandboxTimeoutSeconds(spec);
        try {
            Sandbox.Builder builder = Sandbox.builder()
                    .connectionConfig(connectionConfig())
                    .image(resolveImage(spec))
                    .readyTimeout(Duration.ofSeconds(READY_TIMEOUT_SECONDS))
                    .timeout(Duration.ofSeconds(sandboxTimeout));
            applyEntrypoint(builder, spec);
            applyResourceLimits(builder, spec);
            applyNetworkPolicy(builder, spec);
            Sandbox sandbox = builder.build();
            log.info("OpenSandboxExecutionService: sandbox created id={}, subTaskId={}, reuse={}, "
                            + "image={}, cpu={}, memory={}, ttl={}s, fingerprint={}",
                    sandbox.getId(), subTaskId, properties.isReuseEnabled() && subTaskId != null,
                    resolveImage(spec), resourceOf(spec, true), resourceOf(spec, false), sandboxTimeout,
                    SandboxSpecMapper.fingerprint(spec));
            return sandbox;
        } catch (Exception e) {
            log.warn("OpenSandboxExecutionService: sandbox create failed subTaskId={}, cause={}",
                    subTaskId, e.getMessage());
            return null;
        }
    }

    /** 入口命令：spec 显式给了才覆盖 SDK 默认（{@code tail -f /dev/null} 保持存活）。 */
    private void applyEntrypoint(Sandbox.Builder builder, SandboxSpec spec) {
        if (spec.entrypoint() == null || spec.entrypoint().isEmpty()) {
            return;
        }
        builder.entrypoint(new ArrayList<>(spec.entrypoint()));
    }

    /**
     * 资源限制：{@code cpu} / {@code memory} 为 K8s 风格字符串，与 SDK 的 {@code resource}
     * 映射逐字对齐；两项都空则不设（沿用 SDK 默认，防「配置缺字段 ⇒ 显式给 0」）。
     */
    private void applyResourceLimits(Sandbox.Builder builder, SandboxSpec spec) {
        SandboxSpec.ResourceLimits limits = spec.resourceLimits();
        if (limits == null) {
            return;
        }
        Map<String, String> resource = new LinkedHashMap<>();
        if (limits.cpu() != null && !limits.cpu().isBlank()) {
            resource.put("cpu", limits.cpu().trim());
        }
        if (limits.memory() != null && !limits.memory().isBlank()) {
            resource.put("memory", limits.memory().trim());
        }
        if (!resource.isEmpty()) {
            builder.resource(resource);
        }
    }

    /**
     * 网络策略：默认动作 + egress 白名单 ⇒ SDK {@link NetworkPolicy}。
     *
     * <p>{@code egress} 里每条都映射为 {@code ALLOW} 规则（白名单语义，与 `REF-5.4` 同向：
     * 默认禁出、白名单放行）。{@code defaultAction} 只认 {@code allow}/{@code deny}，
     * 其它值按 {@code deny} 处理——失败方向必须是「更严」而不是「更松」。</p>
     */
    private void applyNetworkPolicy(Sandbox.Builder builder, SandboxSpec spec) {
        SandboxSpec.NetworkPolicy policy = spec.networkPolicy();
        if (policy == null) {
            return;
        }
        List<NetworkRule> rules = new ArrayList<>();
        if (policy.egress() != null) {
            for (String target : policy.egress()) {
                if (target == null || target.isBlank()) {
                    continue;
                }
                rules.add(NetworkRule.builder()
                        .action(NetworkRule.Action.ALLOW)
                        .target(target.trim())
                        .build());
            }
        }
        NetworkPolicy.Builder sdkPolicy = NetworkPolicy.builder()
                .defaultAction("allow".equalsIgnoreCase(policy.defaultAction())
                        ? NetworkPolicy.DefaultAction.ALLOW
                        : NetworkPolicy.DefaultAction.DENY);
        if (!rules.isEmpty()) {
            sdkPolicy.egress(rules);
        }
        builder.networkPolicy(sdkPolicy.build());
    }

    /** 沙箱生命周期上限：spec 显式值优先，否则回落到历史常量。 */
    private long resolveSandboxTimeoutSeconds(SandboxSpec spec) {
        long fromSpec = spec == null ? 0L : spec.timeoutSeconds();
        return fromSpec > 0 ? fromSpec : SANDBOX_TIMEOUT_SECONDS;
    }

    private static String resourceOf(SandboxSpec spec, boolean cpu) {
        if (spec == null || spec.resourceLimits() == null) {
            return "-";
        }
        String value = cpu ? spec.resourceLimits().cpu() : spec.resourceLimits().memory();
        return value == null || value.isBlank() ? "-" : value;
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
    /**
     * 解析生效镜像：spec 优先，其次全局配置，最后兜底 {@code python:3.12}。
     *
     * <p>`SKX-5` 起镜像来自「调用方指定的规格」，不再是全局单例——同子任务要跑 node 出图时
     * 由调用方把 node 规格传进来。</p>
     */
    private String resolveImage(SandboxSpec spec) {
        if (spec != null && spec.image() != null && !spec.image().isBlank()) {
            return spec.image();
        }
        OpenSandboxProperties.Spec configured = properties.getSpec();
        if (configured != null && configured.getImage() != null && !configured.getImage().isBlank()) {
            return configured.getImage();
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
