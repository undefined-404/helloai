package com.helloai.core.agent.runtime;

import com.helloai.common.constant.AgentAccessType;
import org.springframework.stereotype.Component;

/**
 * 本地进程执行环境（Phase 1 Step 4，坑 4 结论两个实现之一）。
 *
 * <p>执行发生在平台自身进程内（API_KEY_LLM：平台经 ChatClient 直调 LLM API，
 * 无外部执行体）；「简单 subprocess，够开发自测」的本地隔离形态留待后续按需扩展，
 * 本轮仅落环境标识与路由事实（纯契约完备性，运行时行为零变化）。</p>
 */
@Component
public class LocalProcessEnvironment implements ExecutionEnvironment {

    /** 环境标识（与 {@code AgentExecutor.getName()} 同风格的轻量路由标识）。 */
    public static final String NAME = "local-process";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(AgentAccessType accessType) {
        return accessType == AgentAccessType.API_KEY_LLM;
    }
}
