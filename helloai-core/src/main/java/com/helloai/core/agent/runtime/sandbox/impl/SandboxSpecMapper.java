package com.helloai.core.agent.runtime.sandbox.impl;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.runtime.sandbox.SandboxSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 配置规格 → 领域规格的转换与「规格指纹」（`SKX-5`，2026-10-26）。
 *
 * <p><b>为什么需要两个类型</b>：{@link OpenSandboxProperties.Spec} 是 <b>Spring 可绑定的配置类</b>
 * （字段可变、默认值散布在字段初始化器里），{@link SandboxSpec} 是<b>领域纯声明 record</b>
 * （不可变、不承载提供方调用参数，见 `REF-3.2`：spec 不得内嵌 Docker API 参数，否则换提供方要返工）。
 * 两者不能合并：前者给运维写 yml，后者给执行链传值。</p>
 *
 * <p><b>指纹干什么用</b>：{@link SandboxSlotPool} 的 key 是 `(subTaskId, 指纹)`。同一子任务
 * 「先 python 验算、再 node 出图」时，若 key 只有 {@code subTaskId}，第二次会命中第一次的槽，
 * 拿到<b>没有 node 的 python 容器</b>。指纹只覆盖<b>真正决定容器身份</b>的字段。</p>
 */
final class SandboxSpecMapper {

    /** 指纹取前 16 位十六进制（64 bit）：碰撞概率对「几个规格槽」量级完全够，日志里也可读。 */
    private static final int FINGERPRINT_HEX_LEN = 16;

    private SandboxSpecMapper() {
    }

    /**
     * 配置规格 → 领域规格。
     *
     * @param config 配置侧规格（不得为 null；调用方保证）
     * @return 不可变领域规格
     */
    static SandboxSpec toDomain(OpenSandboxProperties.Spec config) {
        OpenSandboxProperties.ResourceLimits limits = config.getResourceLimits();
        OpenSandboxProperties.NetworkPolicy network = config.getNetworkPolicy();
        return new SandboxSpec(
                config.getType(),
                config.getImage(),
                config.getEntrypoint() == null ? List.of() : List.copyOf(config.getEntrypoint()),
                limits == null ? null : new SandboxSpec.ResourceLimits(limits.getCpu(), limits.getMemory()),
                network == null ? null
                        : new SandboxSpec.NetworkPolicy(network.getDefaultAction(),
                        network.getEgress() == null ? List.of() : List.copyOf(network.getEgress())),
                parseScope(config.getScope()),
                config.getTimeoutSeconds());
    }

    /**
     * 解析 scope；未知值按 {@code AGENT} 处理（配置错字不该让执行链崩，且 scope 当前未被消费）。
     *
     * @param scope 配置里的 scope 字符串（{@code agent}/{@code user}/{@code fixed}，大小写不敏感）
     */
    static SandboxSpec.Scope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return SandboxSpec.Scope.AGENT;
        }
        try {
            return SandboxSpec.Scope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SandboxSpec.Scope.AGENT;
        }
    }

    /**
     * 规格指纹：稳定、与容器身份无关的字段不参与。
     *
     * <p><b>参与</b>：type / image / entrypoint / cpu / memory / networkPolicy / timeoutSeconds——
     * 任一项不同都是「另一个容器」。</p>
     *
     * <p><b>不参与</b>：{@code scope}。当前 {@code scope} 未被任何消费方读取（`SKX-5` 明确不做
     * 「scope 接入槽策略」），把它算进指纹会让「仅 scope 不同」的两个规格各占一个容器，白烧资源。</p>
     *
     * @param spec 领域规格
     * @return 16 位十六进制指纹；{@code spec == null} ⇒ {@code "default"}
     */
    static String fingerprint(SandboxSpec spec) {
        if (spec == null) {
            return "default";
        }
        List<String> parts = new ArrayList<>();
        parts.add(spec.type());
        parts.add(spec.image());
        parts.add(spec.entrypoint() == null ? "" : String.join(",", spec.entrypoint()));
        parts.add(spec.resourceLimits() == null ? "" : spec.resourceLimits().cpu());
        parts.add(spec.resourceLimits() == null ? "" : spec.resourceLimits().memory());
        parts.add(spec.networkPolicy() == null ? "" : spec.networkPolicy().defaultAction());
        parts.add(spec.networkPolicy() == null || spec.networkPolicy().egress() == null
                ? "" : String.join(",", spec.networkPolicy().egress()));
        parts.add(Long.toString(spec.timeoutSeconds()));
        return sha256Hex(String.join("|", parts)).substring(0, FINGERPRINT_HEX_LEN);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 是 JDK 必备算法；走到这里说明运行环境异常，退回稳定但不加密的表示
            return Integer.toHexString(value.hashCode()) + "00000000";
        }
    }
}
