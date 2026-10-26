package com.helloai.core.agent.runtime.sandbox;

import com.helloai.core.agent.runtime.sandbox.impl.SkillExecutionSupport;
import com.helloai.core.agent.tool.ToolCallbackContributor;
import com.helloai.core.agent.tool.ToolContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Predicate;

/**
 * {@code internal_run_skill} / {@code internal_read_artifact} 的「按条件可用」声明（`SKX-8`，2026-10-26）。
 *
 * <p><b>为什么需要</b>：两个工具的方法体在 {@link com.helloai.core.agent.runtime.tool.InternalLlmExecutorTools}，
 * 但「此刻能不能用」取决于两件与代码无关的事实——沙箱是否启用（{@code helloai.sandbox.opensandbox.enabled}）、
 * 以及是否至少有一个技能包随构建入库。任一不满足即从<b>模型可见列表</b>摘除（fail-closed）：
 * 让模型看到一个必然失败的入口，比不出现更糟——它会反复试，并据此认为「平台能力坏了」。</p>
 *
 * <p><b>两条判定刻意不同</b>：{@code internal_read_artifact} / {@code internal_export_artifact}
 * 只依赖「沙箱启用」（回读与导出不需要技能包）；{@code internal_run_skill} 还要求「有包可用」
 * （没包就没有可跑的东西）。合并成一条会让「没包」的场景把回读/导出能力也一起摘掉，
 * 而它们对「用 internal_run_in_sandbox 手写脚本」的链路同样有用。</p>
 *
 * <p><b>契约遵守（ToolCallbackContributor.toolAvailability）</b>：纯函数、读缓存后的目录索引
 * （不读包内容、不发 HTTP）、不引用 {@code agent_mcp_server} 授权事实、与 {@link ToolContext} 无关。
 * 摘除只作用于进程内 Runtime 的模型可见列表，不改管理面、不改对外 MCP {@code tools/list}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillExecutionToolContributor implements ToolCallbackContributor {

    /** 技能执行工具名（与 {@code InternalLlmExecutorTools} 的 {@code @Tool(name=...)} 严格一致）。 */
    public static final String TOOL_INTERNAL_RUN_SKILL = "internal_run_skill";

    /** 产物回读工具名（同上）。 */
    public static final String TOOL_INTERNAL_READ_ARTIFACT = "internal_read_artifact";

    /** 产物导出工具名（`SKX-11`，2026-10-26：沙箱字节 → 对象存储 → 附件登记）。 */
    public static final String TOOL_INTERNAL_EXPORT_ARTIFACT = "internal_export_artifact";

    private final SkillExecutionSupport skillExecutionSupport;

    /**
     * 纯声明类 ⇒ 返回 {@code null}（工具本体在 {@code InternalLlmExecutorTools}，经
     * {@code McpToolConfig.internalToolCallbacks} 承载，不进对外 MCP 面）。
     */
    @Override
    public Object toolObject() {
        return null;
    }

    @Override
    public Map<String, Predicate<ToolContext>> toolAvailability() {
        return Map.of(
                TOOL_INTERNAL_RUN_SKILL, context -> skillExecutionSupport.skillAvailable(),
                TOOL_INTERNAL_READ_ARTIFACT, context -> skillExecutionSupport.sandboxEnabled(),
                TOOL_INTERNAL_EXPORT_ARTIFACT, context -> skillExecutionSupport.sandboxEnabled());
    }
}
