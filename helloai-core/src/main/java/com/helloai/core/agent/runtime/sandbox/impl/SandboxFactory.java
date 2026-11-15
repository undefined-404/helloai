package com.helloai.core.agent.runtime.sandbox.impl;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.Host;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkPolicy;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkRule;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.OSSFS;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.PVC;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.Volume;
import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.SandboxRuntime;
import com.helloai.core.agent.runtime.sandbox.SandboxSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenSandbox 容器工厂（`SL-9`）：建容器 / 销毁容器 / 规格映射。
 *
 * <p><b>为什么从运行时里抽出来</b>：建槽池（{@code SandboxWorkspaceRegistry}）与技能包注入
 * （{@code SkillPackageInstaller}）都需要「按规格开一个容器」，而销毁又是两类回收路径
 * （空闲淘汰 / 任务终态）的公共动作。留在运行时里会让注册表与安装器反向依赖运行时实现；
 * 抽成本类后依赖图单向：{@code factory ← registry ← runtime/installer}。</p>
 *
 * <p><b>职责边界</b>：本类只回答「怎么按规格建/毁一个容器」，不决定「何时建、谁来复用、
 * 何时释放」——那两件事由 {@link SandboxWorkspaceRegistry} 判。</p>
 *
 * <p><b>规格真正被消费</b>（`SKX-5`）：{@code image} / {@code entrypoint} / {@code resourceLimits} /
 * {@code networkPolicy} / {@code timeoutSeconds} 逐项落到 SDK Builder。刻意不喂 {@code scope}——
 * SDK 无对应概念，「按 scope 复用」是槽池策略而不是容器属性。</p>
 *
 * <p><b>失败带出原因</b>（`LOG-20261027-026`）：{@code errorSink} 把 SDK 原始 cause 与连接配置摘要
 * 带回调用方；{@code apiKey} 只报「是否配置」，值永不入日志/界面。</p>
 */
@Slf4j
@Component
public class SandboxFactory {

    /** 沙箱 readiness 等待上限（秒）。 */
    private static final long READY_TIMEOUT_SECONDS = 120L;

    /** 沙箱生命周期上限（短 TTL；执行用沙箱短命，15min 足够，防资源泄漏）。 */
    private static final long SANDBOX_TIMEOUT_SECONDS = 900L;

    /** SDK 请求超时（秒）：命令超时上限 300s + 60s 余量。 */
    private static final long REQUEST_TIMEOUT_SECONDS = 360L;

    /** 建沙箱失败时上报的 cause 上限（字符，600；防网关整页 HTML 把失败消息撑爆）。 */
    private static final int MAX_CAUSE_CHARS = 600;

    /** 目录权限（755）。 */
    private static final int DIR_MODE = 755;

    private final OpenSandboxProperties properties;

    public SandboxFactory(OpenSandboxProperties properties) {
        this.properties = properties;
    }

    /**
     * 建一个容器（复用槽 opener 与一次性路径共用）。失败返回 {@code null}，
     * 由调用方降级为结构化失败（不抛异常，与既有容错哲学一致）。
     *
     * @param spec       生效规格（非 null；调用方已回落默认）
     * @param contextId  上下文 ID（仅日志定位，可为 null）
     * @param errorSink  失败原因出口（可 null；仅在失败时写入，含 SDK cause 与连接配置摘要）
     * @param readOnly   `/workspace` 是否只读挂载（reviewer 用；executor 传 false）
     * @return 已就绪的容器；失败 ⇒ {@code null}
     */
    public Sandbox create(SandboxSpec spec, Long contextId, AtomicReference<String> errorSink,
                          boolean readOnly) {
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
            applyWorkspaceVolume(builder, readOnly);
            Sandbox sandbox = builder.build();
            // 卷未启用时 `/workspace` 仍是容器可写层，落库实例上建好根目录
            if (!workspaceVolumeConfigured()) {
                prepareWorkspace(sandbox, contextId);
            }
            log.info("SandboxFactory: sandbox created id={}, contextId={}, reuse={}, "
                            + "image={}, cpu={}, memory={}, ttl={}s, fingerprint={}, {}",
                    sandbox.getId(), contextId, properties.isReuseEnabled() && contextId != null,
                    resolveImage(spec), resourceOf(spec, true), resourceOf(spec, false), sandboxTimeout,
                    SandboxSpecMapper.fingerprint(spec), volumeLog(workspaceVolumeConfigured(), readOnly));
            return sandbox;
        } catch (Exception e) {
            String detail = describeCreateFailure(e);
            if (errorSink != null) {
                errorSink.set(detail);
            }
            log.warn("SandboxFactory: sandbox create failed contextId={}, image={}, {}",
                    contextId, resolveImage(spec), detail);
            return null;
        }
    }

    /**
     * 是否实际挂 `/workspace` 卷（`SL-10`）：开关开启且配置了任一卷源。
     *
     * <p><b>缺省即安全</b>：开关默认 false，或开关开但 host/pvc/ossfs 全空 ⇒ 不挂卷，
     * 回落既有「容器可写层」行为（零变化）。</p>
     */
    private boolean workspaceVolumeConfigured() {
        if (!properties.isVolumeEnabled()) {
            return false;
        }
        OpenSandboxProperties.Volume volume = properties.getVolume();
        return volume != null
                && (hasText(volume.getHostPath()) || hasText(volume.getPvc()) || hasText(volume.getOssfs()));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 挂 `/workspace` 卷（`SL-10`）：executor 读 {@code readOnly=false}，reviewer 读
     * {@code readOnly=true}——同一 host 目录，靠容器层只读实现写权限分面（EROFS）。
     */
    private void applyWorkspaceVolume(Sandbox.Builder builder, boolean readOnly) {
        if (!workspaceVolumeConfigured()) {
            return;
        }
        OpenSandboxProperties.Volume configured = properties.getVolume();
        String hostPath = hasText(configured.getHostPath()) ? configured.getHostPath() : null;
        // SDK 1.1.1 的 Volume 必须有 name（DNS label），缺失会在 build() 抛
        // IllegalArgumentException("Name must be specified")，沙箱创建整体失败。
        // 同一沙箱内仅挂一个工作区卷，故用固定标识。
        Volume.Builder volumeBuilder = Volume.builder()
                .name("workspace")
                .mountPath(hasText(configured.getMountPath()) ? configured.getMountPath() : "/workspace")
                .readOnly(readOnly);
        if (hostPath != null) {
            volumeBuilder.host(Host.of(hostPath));
        } else if (hasText(configured.getPvc())) {
            volumeBuilder.pvc(PVC.of(configured.getPvc().trim()));
        } else {
            // ossfs 字段承载 OSS 桶名；endpoint/密钥走服务器环境变量，不在代码里新增配置面
            volumeBuilder.ossfs(OSSFS.builder().bucket(configured.getOssfs().trim()).build());
        }
        builder.volume(volumeBuilder.build());
    }

    /** 卷挂载日志摘要（只报开关与方位，不含路径之外的敏感值）。 */
    private static String volumeLog(boolean attached, boolean readOnly) {
        return attached ? (" /workspace volume " + (readOnly ? "ro" : "rw")) : " /workspace layered";
    }

    /** 销毁容器（幂等、绝不抛异常）：SDK {@code close()} 不删远端沙箱，必须显式 {@code kill()}。 */
    public void destroy(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        String sandboxId = null;
        try {
            sandboxId = sandbox.getId();
            sandbox.kill();
            log.info("SandboxFactory: sandbox killed id={}", sandboxId);
        } catch (Exception killEx) {
            log.warn("SandboxFactory: kill failed sandboxId={}, cause={}", sandboxId, killEx.getMessage());
        }
        try {
            sandbox.close();
        } catch (Exception closeEx) {
            log.warn("SandboxFactory: close failed sandboxId={}, cause={}", sandboxId, closeEx.getMessage());
        }
    }

    /**
     * 拼「建沙箱失败」的可读原因：SDK 异常 + 连接配置摘要（<b>绝不打印 apiKey 值</b>）。
     *
     * <p>包级可见（非 private）仅为让单测直接断言这条消息契约——尤其「apiKey 只报是否配置、
     * 绝不打印值」是一条安全约束，值得被测试钉住。</p>
     */
    String describeCreateFailure(Exception e) {
        String url = properties.getUrl();
        String urlText = url == null || url.isBlank() ? "未配置" : url;
        String keyText = properties.getApiKey() == null || properties.getApiKey().isBlank()
                ? "未配置" : "已配置";
        String message = e.getMessage() == null ? "(no message)" : e.getMessage();
        if (message.length() > MAX_CAUSE_CHARS) {
            message = message.substring(0, MAX_CAUSE_CHARS) + "…(truncated)";
        }
        return "sandbox create failed: " + e.getClass().getSimpleName() + ": " + message
                + " [url=" + urlText + ", apiKey=" + keyText + ", enabled=" + properties.isEnabled() + "]";
    }

    /**
     * 失败消息统一出口：有 cause 用 cause，没有则回落历史文案。
     *
     * <p>回落的必要性：槽池在 {@code key == null} 时直接返回 null（不经 opener），此时 sink
     * 必然是空的。保留原文案是为了让调用方与既有排障文档的对应关系不断。</p>
     */
    public static String createFailedMessage(String detail) {
        return detail == null || detail.isBlank()
                ? "sandbox create failed (opener returned null)"
                : detail;
    }

    /**
     * 预建工作区根（`SL-3`）：所有命令的 cwd 都是 {@link SandboxRuntime#WORKSPACE_DIR}，
     * 不存在就会让<b>每条命令</b>都失败，所以建容器后第一件事就是把它建出来。
     *
     * <p><b>失败只 warn</b>：建目录走 SDK 文件通道，服务端异常不该把「沙箱已建好」的结果作废。</p>
     */
    private void prepareWorkspace(Sandbox sandbox, Long contextId) {
        try {
            sandbox.files().createDirectories(List.of(WriteEntry.builder()
                    .path(SandboxRuntime.WORKSPACE_DIR).data(new byte[0]).mode(DIR_MODE).build()));
        } catch (Exception e) {
            log.warn("SandboxFactory: 预建工作区失败（命令以该目录为 cwd，可能无法执行）"
                    + "contextId={}, dir={}, cause={}",
                    contextId, SandboxRuntime.WORKSPACE_DIR, e.getMessage());
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
     * <p>{@code egress} 里每条都映射为 {@code ALLOW} 规则（白名单语义：默认禁出、白名单放行）。
     * {@code defaultAction} 只认 {@code allow}，其它值一律按 {@code deny} 处理——失败方向必须更严。</p>
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
    static long resolveSandboxTimeoutSeconds(SandboxSpec spec) {
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

    /** app → OpenSandbox server 的连接配置（拓扑：不同网时经 server 代理，`LOG-20261017-009`）。 */
    private ConnectionConfig connectionConfig() {
        return ConnectionConfig.builder()
                .domain(properties.getUrl())
                .apiKey(properties.getApiKey())
                .useServerProxy(true)
                .requestTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .build();
    }

    /** 解析生效镜像：spec 优先，其次全局配置，最后兜底 {@code python:3.12}。 */
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
}
