package com.helloai.core.agent.runtime;

import com.helloai.common.constant.AgentAccessType;
import org.springframework.stereotype.Component;

/**
 * OpenSandbox 容器沙箱执行环境（S-3，沙箱集成计划 §4.3）。
 *
 * <p>表达「执行发生在 OpenSandbox 容器沙箱内」的场所标签。与
 * {@link RemoteAgentEnvironment} / {@link LocalProcessEnvironment} 并列——本环境
 * 是平台首个<b>真实隔离</b>的执行场所（容器），其隔离事实由
 * {@code ExecutionPolicy.openSandbox()} 诚实标注（filesystem/resource/credential=
 * ISOLATED，network/process=PARTIAL，不标全 ISOLATED）。</p>
 *
 * <p>路由契约：{@link #supports} 恒返回 false——本环境<b>不参与</b>按
 * {@code AgentAccessType} 的自动路由（OpenSandbox 由配置开关
 * {@code helloai.sandbox.opensandbox.enabled} 显式启用，而不是按接入类型路由），
 * 只作为 {@code OpenSandboxSandboxProvider} 解析结果的场所标签。</p>
 */
@Component
public class OpenSandboxEnvironment implements ExecutionEnvironment {

    /** 环境标识（与 {@code AgentExecutor.getName()} 同风格的轻量路由标识）。 */
    public static final String NAME = "opensandbox";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(AgentAccessType accessType) {
        // 不参与按接入类型路由：OpenSandbox 由配置开关显式启用（见类 javadoc）
        return false;
    }
}
