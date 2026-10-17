package com.helloai.core.agent.runtime.sandbox.impl;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.helloai.common.config.OpenSandboxProperties;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * OpenSandbox 沙箱探针（S-4，沙箱集成计划 §3/§4.4；对齐 REF-3.4）。
 *
 * <p>对真实 OpenSandbox server 做「写 → 读 → 删」往返验证（参照 octop-harness
 * probe_backend / Octop probe.py 的探针语义）：创建独立探针沙箱 → 写入
 * {@code .helloai-probe-<uuid>.txt} → 读回比对 → 删除文件 → 显式销毁沙箱。
 * 结果以 {@link ProbeResult} 返回（可观测登记），供部署验证 / 未来执行链接线。</p>
 *
 * <p><b>失败路径也销毁</b>：探针沙箱在 {@code finally} 中<b>无条件</b>调用
 * {@code sandbox.kill()}（对齐 REF-3.4「失败路径也 destroy()」）。判据（
 * LOG-20261017-009 实测）：SDK 的 {@code close()} 只关本地 HTTP 资源、
 * <b>不销毁远端沙箱</b>，{@code kill()} 才删——故必须显式 kill，防沙箱容器残留。</p>
 *
 * <p><b>timeline 登记边界</b>：探针当前是「独立部署验证形态」（CLI / 部署脚本调用），
 * 不在任何 task/subtask 执行上下文中 ⇒ 不写 task 时间线（避免「无消费方的时间线记录」
 * 反模式）。未来业务执行链接线时，由调用方在拥有 task/subTask 上下文处经
 * {@code TaskTimelinePort} 登记（对齐 REF-3.4「结果落 timeline」）。</p>
 */
@Slf4j
public class OpenSandboxProbe {

    /** 探针文件前缀（参照 Octop probe.py 的 _PROBE_TEST_ID 语义）。 */
    private static final String PROBE_FILE_PREFIX = ".helloai-probe-";

    /** 探针沙箱 readiness 等待上限（秒）。 */
    private static final long READY_TIMEOUT_SECONDS = 120L;

    /** 探针沙箱超时（生命周期上限；探针短命，60s 足够）。 */
    private static final long PROBE_TIMEOUT_SECONDS = 60L;

    private final OpenSandboxProperties properties;

    public OpenSandboxProbe(OpenSandboxProperties properties) {
        this.properties = properties;
    }

    /**
     * 执行一次探针往返。
     *
     * @param image 探针沙箱镜像（如 python:3.12；由调用方传入，不硬绑定配置默认）
     * @return 探针结果（ok + 详情）
     */
    public ProbeResult probe(String image) {
        String probeId = UUID.randomUUID().toString();
        String path = "/" + PROBE_FILE_PREFIX + probeId + ".txt";
        String content = "helloai-probe-" + probeId;
        long start = System.currentTimeMillis();
        String sandboxId = null;

        ConnectionConfig config = ConnectionConfig.builder()
                .domain(properties.getUrl())
                .apiKey(properties.getApiKey())
                // 拓扑必需：app 与沙箱不同网时，execd 请求经 server 代理（LOG-20261017-009）
                .useServerProxy(true)
                .requestTimeout(Duration.ofSeconds(60))
                .build();

        Sandbox sandbox = null;
        try {
            sandbox = Sandbox.builder()
                    .connectionConfig(config)
                    .image(image)
                    .readyTimeout(Duration.ofSeconds(READY_TIMEOUT_SECONDS))
                    .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                    .build();
            sandboxId = sandbox.getId();
            log.info("OpenSandboxProbe: sandbox created id={}, image={}", sandboxId, image);

            // 写
            sandbox.files().writeFile(path, content);
            // 读
            String readBack = sandbox.files().readFile(path, "UTF-8", null);
            if (!content.equals(readBack)) {
                return new ProbeResult(false, sandboxId, "readback mismatch: expected=" + content
                        + " actual=" + readBack, elapsed(start));
            }
            // 删
            sandbox.files().deleteFiles(List.of(path));
            return new ProbeResult(true, sandboxId, "write-read-delete roundtrip ok", elapsed(start));
        } catch (Exception e) {
            log.warn("OpenSandboxProbe: probe failed sandboxId={}, cause={}", sandboxId,
                    e.getMessage());
            return new ProbeResult(false, sandboxId,
                    "probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    elapsed(start));
        } finally {
            // 失败路径也销毁：无条件 kill（SDK close() 不删远端沙箱，必须显式 kill）
            if (sandbox != null) {
                try {
                    sandbox.kill();
                    log.info("OpenSandboxProbe: sandbox killed sandboxId={}", sandboxId);
                } catch (Exception killEx) {
                    log.warn("OpenSandboxProbe: kill failed sandboxId={}, cause={}", sandboxId,
                            killEx.getMessage());
                }
                try {
                    sandbox.close();
                } catch (Exception closeEx) {
                    log.warn("OpenSandboxProbe: close failed sandboxId={}, cause={}", sandboxId,
                            closeEx.getMessage());
                }
            }
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /**
     * CLI 入口（部署验证）：java ... OpenSandboxProbe [image]
     *
     * <p>从环境变量读取连接配置（OPENSANDBOX_URL / OPENSANDBOX_API_KEY，本地默认），
     * 镜像参数取首个 args 或默认 alpine:latest（轻量、验证链路足够）。</p>
     *
     * @param args 可选：探针沙箱镜像（默认 alpine:latest）
     */
    public static void main(String[] args) {
        String image = args.length > 0 ? args[0] : "alpine:latest";
        OpenSandboxProperties props = new OpenSandboxProperties();
        props.setUrl(System.getenv().getOrDefault("OPENSANDBOX_URL", "http://localhost:8090"));
        props.setApiKey(System.getenv().getOrDefault("OPENSANDBOX_API_KEY", "dev-test-key"));

        ProbeResult result = new OpenSandboxProbe(props).probe(image);
        System.out.println((result.ok() ? "[OK] " : "[FAIL] ") + result.message()
                + " (sandboxId=" + result.sandboxId() + ", " + result.elapsedMillis() + "ms)");
        System.exit(result.ok() ? 0 : 1);
    }

    /** 探针结果（可观测登记）。 */
    public record ProbeResult(boolean ok, String sandboxId, String message, long elapsedMillis) {
    }
}
