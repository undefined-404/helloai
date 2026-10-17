package com.helloai.core.agent.runtime.sandbox;

import com.helloai.common.config.OpenSandboxProperties;
import com.helloai.core.agent.tool.ToolCallbackContributor;
import com.helloai.core.agent.tool.ToolContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Predicate;

/**
 * {@code internal_run_in_sandbox} 的「按条件可用」声明（S-8，沙箱执行链设计方案 §3.4）。
 *
 * <p><b>为什么存在</b>：{@link InternalLlmExecutorTools} 是 {@code @Component} 而非
 * {@link ToolCallbackContributor}，其工具名的「条件可用」由本贡献者独立声明——
 * {@code helloai.sandbox.execution.enabled=false}（默认关闭，对齐 REF-6.13）时，
 * {@code internal_run_in_sandbox} 从模型可见列表<b>摘除</b>（fail-closed，不静默降级），
 * 避免模型看到一个必然返回「sandbox execution disabled」的工具却无从判断「是没启用还是坏了」。</p>
 *
 * <p><b>契约遵守（ToolCallbackContributor.toolAvailability）</b>：纯函数（只读配置）、
 * 每 Turn 热路径廉价（读 boolean）、不引用 {@code agent_mcp_server} 授权事实
 * （声明的是「平台此刻是否具备运行条件」，不是「某 Agent 是否被授权」）、与上下文无关
 * （不依赖 {@link ToolContext} 任何字段）⇒ 天然满足 fail-open 契约（不知道 ≠ 不具备）。</p>
 *
 * <p><b>判定委托能力所有者</b>：{@link OpenSandboxProperties#isEnabled()} 是执行通道
 * {@code OpenSandboxExecutionService} 的唯一启用事实源，此处只读它（不在本类重算，
 * 避免与执行服务漂移）。</p>
 *
 * <p><b>摘除不落库、不改管理面、不改 MCP {@code tools/list} 暴露面</b>：
 * 本工具本就是 {@code InternalToolCallbacks} 非 provider 承载、外部 MCP 不可见；
 * 此声明只作用于进程内 Runtime 的模型可见列表（内部 LLM / Reviewer 循环）。</p>
 */
@Slf4j
@Component
public class SandboxExecutionToolContributor implements ToolCallbackContributor {

    /** 本贡献者声明的工具名（与 {@link InternalLlmExecutorTools} 的 @Tool name 严格一致）。 */
    public static final String TOOL_INTERNAL_RUN_IN_SANDBOX = "internal_run_in_sandbox";

    private final OpenSandboxProperties properties;

    public SandboxExecutionToolContributor(OpenSandboxProperties properties) {
        this.properties = properties;
    }

    @Override
    public Object toolObject() {
        return this;
    }

    /**
     * 「按条件可用」声明：沙箱执行未启用 ⇒ {@code internal_run_in_sandbox} 从模型可见列表摘除。
     */
    @Override
    public Map<String, Predicate<ToolContext>> toolAvailability() {
        return Map.of(TOOL_INTERNAL_RUN_IN_SANDBOX, context -> properties.isEnabled());
    }
}
