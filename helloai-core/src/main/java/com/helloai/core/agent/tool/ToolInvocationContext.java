package com.helloai.core.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 内部执行链的「工具调用上下文」——把执行者的真实标识<b>带外</b>注入内部工具的调用参数
 * （REF-8.1 A1 接线，2026-10-12）。
 *
 * <p><b>为什么存在</b>：内部 LLM（{@code API_KEY_LLM}）走进程内 {@code AgentLoop}，模型
 * <b>无从得知</b>自己本轮的执行者 {@code agentId} 与当前 {@code subTaskId}（这两个值不进入
 * systemPrompt / userPrompt；{@code AgentLoopInput.agentId} 仅用于事件埋点）。而
 * {@link com.helloai.core.agent.runtime.tool.InternalLlmExecutorTools} 的读型 / 交付登记工具
 * 需要这两个标识做归属校验——若交给模型自报，只会得到幻觉值。故由执行链在调用工具前
 * <b>带外</b>注入真实值，模型无需也无法自报。</p>
 *
 * <p><b>为什么用 {@link ThreadLocal} 而非显式传参</b>：工具执行入口
 * {@link ToolExecutor#execute(String, String)} 的签名由循环调用点固定（{@code ChatModelToolLoop}），
 * 而在「不动 {@code ChatModelToolLoop} 循环本体」的红线下（借鉴落地 §10.5），
 * 上下文无法经方法参数下沉到执行器——带外绑定是<b>唯一合规路径</b>。绑定/解绑严格成对
 * （{@code RuntimeTurnExecutor} 以 {@code try/finally} 保证），且为请求内线程局部状态，
 * <b>不构成单实例假设</b>（区别于进程级注册表式缺陷）。</p>
 *
 * <p><b>注入纪律（两道门控，避免误伤）</b>：
 * <ol>
 *   <li><b>前缀门控</b>——仅 {@link #INTERNAL_TOOL_PREFIX}（{@code internal_}）前缀工具注入，
 *       外部 MCP 工具（{@code web_search} / {@code get_sub_task_detail} 等）逐字不变；</li>
 *   <li><b>schema 门控</b>——仅注入工具自身 {@code @ToolParam} <b>已声明</b>的字段
 *       （从 {@code inputSchema} 的 {@code properties} 派生，与工具注解<b>同源防漂移</b>），
 *       无参 / 无该字段的工具（如 {@code internal_list_skills}）不受影响。</li>
 * </ol>
 * 平台注入值<b>覆盖</b>模型所给值（执行链鉴定的标识为权威，不信任入参）。</p>
 *
 * <p><b>纯静态工具</b>：无 Spring 依赖，可独立单测（对齐 {@code UpstreamAttachmentRenderer} 风格）。</p>
 */
@Slf4j
public final class ToolInvocationContext {

    /** 内部 LLM 进程内工具名前缀（与外部 MCP 工具区分，见 {@code InternalLlmExecutorTools}）。 */
    public static final String INTERNAL_TOOL_PREFIX = "internal_";

    /** 平台注入字段名——须与 {@code InternalLlmExecutorTools} 的 {@code @ToolParam} 同名。 */
    private static final String PARAM_AGENT_ID = "agentId";
    private static final String PARAM_SUB_TASK_ID = "subTaskId";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 当前线程绑定的执行者标识（未绑定为 {@code null}）。 */
    private static final ThreadLocal<Ids> CURRENT = new ThreadLocal<>();

    private ToolInvocationContext() {
    }

    /** 本轮工具调用的执行者标识对；任一项可为 null（该字段则不注入）。 */
    public record Ids(Long agentId, Long subTaskId) {
    }

    /**
     * 绑定本轮执行链的真实标识（由 {@code RuntimeTurnExecutor} 在 {@code agentLoop.run()} 前调用）。
     * 与 {@link #unbind()} 严格成对，二次绑定覆盖旧值。
     */
    public static void bind(Long agentId, Long subTaskId) {
        CURRENT.set(new Ids(agentId, subTaskId));
    }

    /** 解绑（{@code agentLoop.run()} 返回后必须调用，防线程复用串味）。 */
    public static void unbind() {
        CURRENT.remove();
    }

    /** 当前线程绑定的标识；未绑定返回 {@code null}（仅供诊断 / 测试）。 */
    public static Ids current() {
        return CURRENT.get();
    }

    /**
     * 把平台标识注入内部工具的调用参数（两道门控见类 javadoc）。
     *
     * @param toolName      工具名（非 {@link #INTERNAL_TOOL_PREFIX} 前缀则<b>原样返回</b>）
     * @param inputSchema   工具自身的输入 schema（JSON 字符串，取自
     *                      {@code ToolCallback.getToolDefinition().inputSchema()}）；
     *                      null / 空白 / 解析失败时按「放行」处理（已受前缀门控约束，
     *                      且多重键会被 spring-ai 按参数名取参时忽略）
     * @param argumentsJson 模型产出的调用参数（原样透传；非法 JSON 时退化为「仅平台标识」对象）
     * @return 注入后的参数 JSON；未绑定上下文或非内部工具时返回入参本身
     */
    public static String inject(String toolName, String inputSchema, String argumentsJson) {
        Ids ids = CURRENT.get();
        if (ids == null || toolName == null || !toolName.startsWith(INTERNAL_TOOL_PREFIX)) {
            return argumentsJson;
        }
        ObjectNode node = parseObject(argumentsJson);
        if (ids.agentId() != null && declares(inputSchema, PARAM_AGENT_ID)) {
            node.put(PARAM_AGENT_ID, ids.agentId());
        }
        if (ids.subTaskId() != null && declares(inputSchema, PARAM_SUB_TASK_ID)) {
            node.put(PARAM_SUB_TASK_ID, ids.subTaskId());
        }
        return node.toString();
    }

    /** 解析模型参数为可写对象；null / 空白 → 空对象；非法 JSON → 空对象（仅保留平台标识）。 */
    private static ObjectNode parseObject(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(argumentsJson);
            if (node != null && node.isObject()) {
                return (ObjectNode) node;
            }
            return MAPPER.createObjectNode();
        } catch (Exception e) {
            log.warn("工具调用参数非合法 JSON，退化为仅注入平台标识: err={}", e.getMessage());
            return MAPPER.createObjectNode();
        }
    }

    /** schema 是否声明了某字段（从 {@code properties} 派生，与 {@code @ToolParam} 同源）。 */
    private static boolean declares(String inputSchema, String param) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return true;
        }
        try {
            JsonNode properties = MAPPER.readTree(inputSchema).path("properties");
            return properties.isObject() && properties.has(param);
        } catch (Exception e) {
            log.debug("工具 inputSchema 解析失败，按放行处理: err={}", e.getMessage());
            return true;
        }
    }
}
