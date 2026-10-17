package com.helloai.core.agent.runtime.sandbox;

import java.util.List;

/**
 * 沙箱声明式规格（S-1，OpenSandbox 沙箱集成计划 §3/§4.2）。
 *
 * <p>只描述「想要什么沙箱」——镜像 / 入口 / 资源限制 / 网络策略 / 生命周期作用域，
 * 是<b>纯声明</b>，不承载任何提供方 API 调用参数（对齐 REF-3.2 预案：spec 不得内嵌
 * Docker API 调用参数，否则将来换成独立沙箱服务要返工）。渲染成具体提供方请求体
 * （如 OpenSandbox {@code POST /sandboxes}）是 Provider（S-3）的职责。</p>
 *
 * <p>字段口径：{@code cpu} / {@code memory} 采用 K8s 风格字符串（如 {@code "500m"} /
 * {@code "512Mi"}），与 OpenSandbox API 请求体逐字对齐。</p>
 *
 * @param type           沙箱类型标识（当前支持 {@code opensandbox}）
 * @param image          容器镜像（如 {@code python:3.12}）
 * @param entrypoint     容器入口命令（空列表 = 使用镜像默认入口）
 * @param resourceLimits 资源限制（cpu / memory）
 * @param networkPolicy  网络策略（默认动作 + egress 白名单）
 * @param scope          生命周期作用域（agent / user / fixed）
 * @param timeoutSeconds 沙箱超时秒数（生命周期上限，防御资源泄漏）
 */
public record SandboxSpec(
        String type,
        String image,
        List<String> entrypoint,
        ResourceLimits resourceLimits,
        NetworkPolicy networkPolicy,
        Scope scope,
        long timeoutSeconds) {

    /** 资源限制（K8s 风格字符串口径，与 OpenSandbox API 请求体逐字对齐）。 */
    public record ResourceLimits(String cpu, String memory) {
    }

    /** 网络策略：默认动作 + egress 白名单（与 REF-5.4 同向：默认禁出、白名单放行）。 */
    public record NetworkPolicy(String defaultAction, List<String> egress) {
    }

    /** 生命周期作用域。 */
    public enum Scope {
        /** 同一 Agent 复用同一沙箱（命名复用，对齐 REF-3.5；显式回收、不自动销毁）。 */
        AGENT,
        /** 按用户隔离。 */
        USER,
        /** 固定实例。 */
        FIXED
    }
}
