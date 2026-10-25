package com.helloai.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenSandbox 沙箱集成配置（S-3 / S-5，沙箱集成计划 §4.2/§4.7）。
 *
 * <p>对应配置前缀 {@code helloai.sandbox.opensandbox}。新能力默认关闭
 * （{@code enabled=false}，对齐 REF-6.13：通过注入启用）；开启后才由
 * {@code OpenSandboxSandboxProvider} 消费。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "helloai.sandbox.opensandbox")
public class OpenSandboxProperties {

    /** 是否启用 OpenSandbox 沙箱提供方（默认 false，REF-6.13 新能力默认关闭）。 */
    private boolean enabled = false;

    /** OpenSandbox server 地址（如 http://localhost:8090，dev；compose 内为服务名地址）。 */
    private String url = "http://localhost:8090";

    /**
     * app → OpenSandbox server 的认证 api_key（OPEN-SANDBOX-API-KEY 头）。
     *
     * <p>与平台凭据体系【分离】（凭据红线 §4.5）：api_key 存配置 / 环境变量，
     * 不进 {@code credential_vault}；沙箱容器绝不注入平台凭据 env。</p>
     */
    private String apiKey;

    /** 声明式沙箱规格（纯声明，不承载提供方 API 调用参数；见 {@link SandboxSpec}）。 */
    private Spec spec = new Spec();

    /**
     * 是否启用<b>同子任务复用</b>沙箱（`ADR-005`，2026-10-25；默认 true）。
     *
     * <p>对齐 deepseek-harness `dsh-tool-bash-persistent` 的产品级口径：同一子任务内的多次
     * `internal_run_in_sandbox` 调用复用同一沙箱，<b>写入的文件跨调用保留</b>，模型可对上一轮
     * 产物做增量编辑（`sed -i` / `python` 定点改），而不是每轮整篇重写。</p>
     *
     * <p>置 false ⇒ 回落原「每次调用新建 + 无条件 kill」的一次性语义（行为零变化，
     * 用于排障 / 需要绝对隔离的场景）。</p>
     */
    private boolean reuseEnabled = true;

    /**
     * 复用沙箱的空闲上限（秒，默认 900 = 15min）。每次执行成功即 {@code renew} 续期；
     * 空闲超时后再次访问会被淘汰重建。turn 结束另有显式 {@code release}，
     * 本值只作「进程异常退出 / 未走 release」的兜底。
     */
    private long idleTtlSeconds = 900L;

    /**
     * 声明式沙箱规格字段（与 OpenSandbox API 请求体逐字对齐，S-1）。
     */
    @Data
    public static class Spec {

        /** 沙箱类型标识（当前支持 opensandbox）。 */
        private String type = "opensandbox";

        /** 容器镜像（如 python:3.12）。 */
        private String image = "python:3.12";

        /** 容器入口命令（默认 tail -f /dev/null 保持存活）。 */
        private List<String> entrypoint = new ArrayList<>(List.of("tail", "-f", "/dev/null"));

        /** 资源限制（K8s 风格字符串口径）。 */
        private ResourceLimits resourceLimits = new ResourceLimits();

        /** 网络策略（默认禁出 + egress 白名单）。 */
        private NetworkPolicy networkPolicy = new NetworkPolicy();

        /** 生命周期作用域（agent / user / fixed）。 */
        private String scope = "agent";

        /** 沙箱超时秒数（默认 3600 = 1h）。 */
        private long timeoutSeconds = 3600L;
    }

    /** 资源限制（K8s 风格字符串，与 OpenSandbox API 逐字对齐）。 */
    @Data
    public static class ResourceLimits {

        private String cpu = "500m";

        private String memory = "512Mi";
    }

    /** 网络策略：默认动作 + egress 白名单（默认禁出，与 REF-5.4 同向）。 */
    @Data
    public static class NetworkPolicy {

        private String defaultAction = "deny";

        private List<String> egress = new ArrayList<>();
    }
}
