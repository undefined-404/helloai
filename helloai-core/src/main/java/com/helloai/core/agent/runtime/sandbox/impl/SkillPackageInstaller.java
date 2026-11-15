package com.helloai.core.agent.runtime.sandbox.impl;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.SandboxRuntime;
import com.helloai.core.agent.runtime.sandbox.SandboxSpec;
import com.helloai.core.agent.runtime.sandbox.SkillInstallResult;
import com.helloai.core.agent.runtime.sandbox.WorkspaceRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 技能包注入（`SL-9`）：把 classpath 里的技能包写进复用工作区。
 *
 * <p><b>为什么这是独立职责</b>：装包是「技能的安装步骤」，不是「运行时能力」。它需要与
 * {@link OpenSandboxRuntime} <b>同一个槽</b>（装完紧接着 {@code node bin/xxx.mjs} 必须命中同一容器），
 * 但变化原因不同——包格式、幂等标记、校验口径会独立演进。</p>
 *
 * <p><b>失败即丢弃槽</b>：批量写可能「写了一半」，半装包会在后续 {@code node} 运行时报缺模块，
 * 排查成本远高于重建容器。与「命令传输层失败即丢弃」是同一条口径。</p>
 */
@Slf4j
@Component
public class SkillPackageInstaller {

    /** 技能包在容器内的落点根（`SKX-6`；依赖 {@link SandboxRuntime#WORKSPACE_DIR} 已预建）。 */
    static final String SKILL_ROOT_DIR = SandboxRuntime.WORKSPACE_DIR + "/skills";

    /** 幂等标记文件名（写在包根，记录版本 + 内容哈希树）。 */
    private static final String SKILL_MARKER_FILE = ".helloai-skill.json";

    /** 技能包文件权限（644）：全是数据文件，不需要可执行位（入口靠 {@code node bin/xxx.mjs} 显式调用）。 */
    private static final int SKILL_FILE_MODE = 644;

    /** 目录权限（755）。 */
    private static final int SKILL_DIR_MODE = 755;

    private final OpenSandboxProperties properties;
    private final SandboxWorkspaceRegistry registry;
    private final SkillPackageLoader skillPackageLoader;

    /** 标记文件读写用的 JSON 编解码（只处理平台自己写的小对象，不承载业务模型）。 */
    private final ObjectMapper markerMapper = new ObjectMapper();

    public SkillPackageInstaller(OpenSandboxProperties properties, SandboxWorkspaceRegistry registry,
                                 SkillPackageLoader skillPackageLoader) {
        this.properties = properties;
        this.registry = registry;
        this.skillPackageLoader = skillPackageLoader;
    }

    /**
     * 把 classpath 里的技能包批量写入该句柄的复用工作区（`SKX-6`）。
     *
     * <p><b>为什么走「同一槽」而不是「新建一个沙箱装包」</b>：技能执行的形态是
     * 「装包 → 跑 {@code node bin/xxx.mjs}」（`SKX-9`），两步必须在<b>同一个容器</b>里。
     * 槽键与 {@link OpenSandboxRuntime#exec} 完全一致（作用域 + 规格指纹），所以装完包紧接着
     * 执行命令时直接命中这个已装好的槽，省掉一次建沙箱（0.7~1.2s）也保证文件在。</p>
     *
     * @param ref         工作区句柄（必须可入池：一次性沙箱装完即销毁，包无处可用）
     * @param spec        建槽规格；{@code null} ⇒ 全局默认（技能应显式传目标运行时规格）
     * @param packageName 包名（对应 {@code classpath:skills/packages/<packageName>/}）
     * @param version     版本（对应包目录名）
     * @return 注入结果（失败也结构化返回，不抛异常）
     */
    public SkillInstallResult install(WorkspaceRef ref, SandboxSpec spec,
                                      String packageName, String version) {
        if (!properties.isEnabled()) {
            return SkillInstallResult.fail(
                    "sandbox execution disabled (helloai.sandbox.opensandbox.enabled=false)");
        }
        if (!properties.isReuseEnabled()) {
            return SkillInstallResult.fail("skill install requires reuse-enabled=true"
                    + "（一次性沙箱装完即销毁，包无处可用）");
        }
        if (ref == null || !ref.pooled()) {
            return SkillInstallResult.fail("workspace ref must be pooled when installing a skill package");
        }
        LoadedSkillPackage pkg;
        try {
            pkg = skillPackageLoader.load(packageName, version);
        } catch (Exception e) {
            log.warn("SkillPackageInstaller: 技能包加载失败 name={}, version={}, cause={}",
                    packageName, version, e.getMessage());
            return SkillInstallResult.fail("技能包加载失败: " + e.getMessage());
        }

        SandboxSpec effective = spec != null ? spec : SandboxSpecMapper.toDomain(properties.getSpec());
        String targetDir = SKILL_ROOT_DIR + "/" + pkg.name();
        long start = System.currentTimeMillis();
        AtomicReference<String> createFailure = new AtomicReference<>();
        SandboxSlotPool.Slot<SandboxWorkspaceRegistry.SlotKey, Sandbox> slot =
                registry.open(ref, effective, createFailure);
        if (slot == null) {
            // 注入失败的原因必须带出去：它会被上层拼成「技能包注入失败：<此消息>」显示给用户
            return SkillInstallResult.fail(SandboxFactory.createFailedMessage(createFailure.get()));
        }
        slot.lock().lock();
        try {
            SkillInstallResult result = installInto(ref.contextId(), slot.resource(), pkg, targetDir, start);
            if (result.success()) {
                registry.renewIfNeeded(slot, ref.contextId());
                slot.touch();
            } else {
                registry.remove(slot.key());
            }
            return result;
        } catch (Exception e) {
            registry.remove(slot.key());
            log.warn("SkillPackageInstaller: 技能包注入异常，已丢弃沙箱 scope={}, name={}, cause={}",
                    ref.key(), pkg.name(), e.getMessage());
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
    private SkillInstallResult installInto(Long contextId, Sandbox sandbox, LoadedSkillPackage pkg,
                                           String targetDir, long start) {
        sandbox.files().createDirectories(directoryEntries(pkg, targetDir));
        if (alreadyInstalled(sandbox, targetDir, pkg)) {
            long costMs = System.currentTimeMillis() - start;
            log.info("SkillPackageInstaller: 技能包已存在同内容，跳过注入 contextId={}, name={}, "
                            + "version={}, files={}, costMs={}",
                    contextId, pkg.name(), pkg.version(), pkg.fileCount(), costMs);
            return SkillInstallResult.ok(pkg.fileCount(), pkg.totalBytes(), targetDir, true,
                    "已存在同版本同内容（" + pkg.name() + "@" + pkg.version() + "），跳过写入");
        }

        writePayload(contextId, sandbox, pkg, targetDir);
        sandbox.files().writeFile(markerEntry(targetDir + "/" + SKILL_MARKER_FILE, pkg));
        verifyInstalled(sandbox, pkg, targetDir);

        long costMs = System.currentTimeMillis() - start;
        log.info("SkillPackageInstaller: 技能包注入完成 contextId={}, name={}, version={}, "
                        + "files={}, bytes={}, target={}, costMs={}",
                contextId, pkg.name(), pkg.version(), pkg.fileCount(), pkg.totalBytes(), targetDir, costMs);
        return SkillInstallResult.ok(pkg.fileCount(), pkg.totalBytes(), targetDir, false,
                "注入完成 files=" + pkg.fileCount() + " bytes=" + pkg.totalBytes() + " costMs=" + costMs);
    }

    /**
     * 幂等探测：目标目录里存在标记文件且哈希树一致 ⇒ 已装好。
     *
     * <p>用 {@code listDirectory} 而不是直接读标记：目录已在上一步建好，列目录不会 404，
     * 「标记不存在」表现为<b>空列表</b>而非异常——首次安装不该在日志里留下 ERROR。</p>
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
            log.info("SkillPackageInstaller: 已有技能包内容不同，将覆盖写入 name={}, installed={}, expected={}",
                    pkg.name(), abbreviate(installedTree), abbreviate(pkg.sha256Tree()));
            return false;
        } catch (Exception e) {
            log.debug("SkillPackageInstaller: 幂等标记探测失败（按未安装处理）path={}, cause={}",
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
    private void writePayload(Long contextId, Sandbox sandbox, LoadedSkillPackage pkg, String targetDir) {
        List<WriteEntry> entries = new ArrayList<>(pkg.fileCount());
        for (LoadedSkillPackage.Entry entry : pkg.entries()) {
            entries.add(fileEntry(targetDir + "/" + entry.relativePath(), entry.data()));
        }
        long start = System.currentTimeMillis();
        try {
            sandbox.files().write(entries);
            log.info("SkillPackageInstaller: 技能包批量写入完成 contextId={}, files={}, costMs={}",
                    contextId, entries.size(), System.currentTimeMillis() - start);
            return;
        } catch (Exception batchError) {
            log.warn("SkillPackageInstaller: 批量写入失败，降级逐文件重试 contextId={}, files={}, cause={}",
                    contextId, entries.size(), batchError.getMessage());
        }
        List<String> failed = new ArrayList<>();
        for (LoadedSkillPackage.Entry entry : pkg.entries()) {
            try {
                sandbox.files().writeFile(fileEntry(targetDir + "/" + entry.relativePath(), entry.data()));
            } catch (Exception e) {
                failed.add(entry.relativePath());
                if (failed.size() <= 3) {
                    log.warn("SkillPackageInstaller: 逐文件写入失败 path={}, cause={}",
                            entry.relativePath(), e.getMessage());
                }
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("逐文件重试后仍有 " + failed.size() + " 个文件写入失败，首个: "
                    + failed.get(0));
        }
        log.info("SkillPackageInstaller: 技能包逐文件写入完成 contextId={}, files={}, costMs={}",
                contextId, pkg.fileCount(), System.currentTimeMillis() - start);
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
     * {@code /workspace}（`SKX-1` 实测 {@code pwd=/}）；虽然建容器后已预建（`SL-3`），这里保留
     * 是为了让「目录清单」自身完整、不依赖调用顺序。</p>
     */
    private static List<WriteEntry> directoryEntries(LoadedSkillPackage pkg, String targetDir) {
        Set<String> dirs = new LinkedHashSet<>();
        dirs.add(SandboxRuntime.WORKSPACE_DIR);
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
}
