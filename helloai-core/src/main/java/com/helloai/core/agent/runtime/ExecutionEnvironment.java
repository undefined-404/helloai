package com.helloai.core.agent.runtime;

import com.helloai.common.constant.AgentAccessType;

import java.util.Collections;
import java.util.Map;

/**
 * 执行环境抽象（坑 4 预留，仅定义接口；落位两个实现）。
 *
 * <p>定义「在哪里执行」的抽象：远程 Agent（MCP/REST）、本地进程，以及未来 Docker Sandbox。
 * 由 {@link ExecutionEnvironmentProvider} 按 {@code Agent.accessType}
 * 解析并注入 {@link AgentContext#environment}；DockerSandbox 推迟到 P2、K8sSandbox
 * 推迟到 P3（执行方案坑 4 结论：当前真正缺的不是沙箱隔离，而是「谁在执行」的显式定义）。</p>
 *
 * <p><b>env 注入契约（Paperclip 阶段一·1，2026-10-22）</b>：本接口新增
 * {@link #environmentOverrides()} —— 实现类可声明<b>静态配置 env</b>（非运行时 key）。
 * 任何实现类的 overrides 在装配前<b>必须</b>经 {@link EnvironmentBlacklist} 过滤
 * （黑名单键丢弃 + 告警，不拒绝整个执行），且<b>运行时 key 恒优先</b>（合并顺序
 * base &lt; 配置 overrides &lt; 运行时 key，后者不可被覆盖）。当前三个实现类返回空 Map
 * = 行为零变化；该契约为阶段二 CLI 通道的 env 注入预立安全边界。</p>
 */
public interface ExecutionEnvironment {

    /**
     * 环境名称（如 remote-agent / local-process / docker-sandbox），
     * 用于日志与路由标识，与 {@code AgentExecutor.getName()} 同风格的轻量标识。
     */
    String name();

    /**
     * 当前环境是否承接该接入类型的执行。
     *
     * <p>路由契约与 {@code AgentExecutorRouter} 同构：Provider 按 supports 过滤取首个命中。</p>
     *
     * @param accessType Agent 接入类型（可空：null 恒不匹配）
     */
    boolean supports(AgentAccessType accessType);

    /**
     * 本环境的<b>静态配置 env 覆盖项</b>（key → value）。
     *
     * <p>默认返回空 Map（三个现役实现无配置 env，行为零变化）。提供方实现时<b>必须</b>：
     * <ul>
     *   <li>不写入任何敏感 / 凭据类 key（那是运行时 key 的职责，且黑名单会拦截）；</li>
     *   <li>装配侧合并顺序固定为 {@code base < overrides < 运行时 key}，本方法返回值
     *       恒低于运行时 key 优先级。</li>
     * </ul>
     * 返回的 Map 应不可变（调用方只读）。
     */
    default Map<String, String> environmentOverrides() {
        return Collections.emptyMap();
    }
}
