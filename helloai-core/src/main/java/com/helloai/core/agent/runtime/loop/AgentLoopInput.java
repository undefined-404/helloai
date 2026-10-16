package com.helloai.core.agent.runtime.loop;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.event.AgentEventRecorder;
import com.helloai.core.agent.runtime.cancel.CancellableChatModel;
import com.helloai.core.agent.runtime.cancel.CancellableToolExecutor;
import com.helloai.core.agent.runtime.cancel.LoopCancellation;
import com.helloai.core.agent.tool.ToolExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Agent 循环输入（P0-C）。
 *
 * <p>一次 Agent Turn 循环的完整输入：模型 + 提示词 + 允许调用的工具回调（调用方已按
 * 启用工具名过滤，模型可见其 schema）+ 工具执行回路 + 事件定位与记录器。
 * provider 无关——只依赖 spring-ai {@link ChatModel} 契约。</p>
 *
 * <p><b>★可取消性装配点（REF-8.6）</b>：{@code cancellation} 非「不限」时，本记录的
 * <b>紧凑构造器</b>会把 {@code chatModel} / {@code toolExecutor} 包上取消装饰器
 * （{@link CancellableChatModel} / {@link CancellableToolExecutor}）。
 * 这里是全平台把可取消性装进循环的<b>唯一咽喉</b>——任何调用方（现有两个 + 未来新增）
 * <b>都无法绕过</b>；若改为「各调用方自行包装饰器」，就会重现已登记缺陷模式
 * 「<b>规则存在 ≠ 全路径强制</b>」（新增调用方漏包 ⇒ 取消静默失效）。
 * 循环本体（{@code ChatModelToolLoop}）因此一行不改（§10.8 红线：不动循环逻辑、只动入参 / 出口）。</p>
 *
 * <p><b>边界</b>：协作式取消——检查点＝每次模型调用前后 + 每次工具调用前（覆盖循环内全部动作
 * 类型）；在途 HTTP 请求本身不打断（{@code ChatModel} 契约无 signal 通道）。详见
 * {@link com.helloai.core.agent.runtime.cancel.CancellationToken}。</p>
 *
 * @param chatModel            底层模型（spring-ai 契约，不可空）
 * @param systemPrompt         系统提示词（可空，按空串处理）
 * @param userPrompt           用户提示词（可空，按空串处理）
 * @param toolExecutor 工具执行回路（不可空）
 * @param enabledToolCallbacks 允许循环调用的工具回调（可空/空 = 循环内无工具）
 * @param maxIterations        LLM 调用轮数硬上限（null/&lt;=0 时取 {@link #DEFAULT_MAX_ITERATIONS}）
 * @param runId                Run 标识（事件定位，ADR-001）
 * @param taskId               主任务 ID（可空）
 * @param subTaskId            子任务 ID（可空）
 * @param turn                 Turn 序号（从 1 起）
 * @param agentId              执行 Agent ID（可空）
 * @param eventRecorder        事件记录器（可空：不记录 TOOL_CALL 事件）
 * @param loopCheckpointListener 循环进度回调（可空：不落循环检查点）
 * @param accessType           接入类型（REF-8.4 ② 收尾轮 internal-only 门控；null = 非内部）
 * @param cancellation         可取消上下文（REF-8.6；null = {@link LoopCancellation#none()} 不限）
 */
public record AgentLoopInput(
        ChatModel chatModel,
        String systemPrompt,
        String userPrompt,
        ToolExecutor toolExecutor,
        List<ToolCallback> enabledToolCallbacks,
        Integer maxIterations,
        String runId,
        Long taskId,
        Long subTaskId,
        int turn,
        Long agentId,
        AgentEventRecorder eventRecorder,
        LoopCheckpointListener loopCheckpointListener,
        AgentAccessType accessType,
        LoopCancellation cancellation) {

    /** 默认最大循环轮数（LLM 调用次数硬上限，防死循环）。 */
    public static final int DEFAULT_MAX_ITERATIONS = 5;

    /**
     * 内部 LLM 执行者（API_KEY_LLM）默认最大循环轮数（REF-8.2 分档的硬默认）。
     *
     * <p>内部执行者需多步调工具 / 读附件 / 写产物，档位高于外部一次性（5 轮）。
     * 配置项 {@code AgentExecutionProperties.internalMaxIterations}（默认 10）优先；
     * 本量作为配置缺失 / 非正时的回落硬默认，保证行为确定。独立命名避开
     * {@link #DEFAULT_MAX_ITERATIONS}（外部默认）以免语义混淆。</p>
     */
    public static final int DEFAULT_MAX_ITERATIONS_EXTRA = 10;

    public AgentLoopInput {
        if (maxIterations == null || maxIterations <= 0) {
            maxIterations = DEFAULT_MAX_ITERATIONS;
        }
        if (enabledToolCallbacks == null) {
            enabledToolCallbacks = List.of();
        }
        if (cancellation == null) {
            cancellation = LoopCancellation.none();
        }
        // REF-8.6：唯一咽喉装配取消装饰器（详见类 javadoc）；不限时零包装（行为与改造前逐字一致）
        if (cancellation.active()) {
            if (chatModel != null) {
                chatModel = new CancellableChatModel(chatModel, cancellation.token());
            }
            if (toolExecutor != null) {
                toolExecutor = new CancellableToolExecutor(
                        toolExecutor, cancellation.token(), cancellation.toolTimeoutMillis());
            }
        }
    }

    /**
     * 兼容构造器（旧 14 参签名，可取消上下文缺省「不限」）：现有调用点零改动
     * ——REF-8.6 接入前只有执行链与业务面入口两处生产调用点，均在本批显式传
     * {@link LoopCancellation}；本构造器保留供单测 / 历史接点使用（行为与改造前逐字一致）。
     */
    public AgentLoopInput(ChatModel chatModel, String systemPrompt, String userPrompt,
                          ToolExecutor toolExecutor, List<ToolCallback> enabledToolCallbacks,
                          Integer maxIterations, String runId, Long taskId, Long subTaskId,
                          int turn, Long agentId, AgentEventRecorder eventRecorder,
                          LoopCheckpointListener loopCheckpointListener, AgentAccessType accessType) {
        this(chatModel, systemPrompt, userPrompt, toolExecutor, enabledToolCallbacks, maxIterations,
                runId, taskId, subTaskId, turn, agentId, eventRecorder, loopCheckpointListener,
                accessType, LoopCancellation.none());
    }

    /**
     * 兼容构造器（旧 13 参签名，接入类型缺省 null = 非内部）：现有调用点
     * （单测/历史接点）零改动；REF-8.4 ② 收尾轮需显式传 {@link #accessType} 才触发 internal 门控。
     */
    public AgentLoopInput(ChatModel chatModel, String systemPrompt, String userPrompt,
                          ToolExecutor toolExecutor, List<ToolCallback> enabledToolCallbacks,
                          Integer maxIterations, String runId, Long taskId, Long subTaskId,
                          int turn, Long agentId, AgentEventRecorder eventRecorder,
                          LoopCheckpointListener loopCheckpointListener) {
        this(chatModel, systemPrompt, userPrompt, toolExecutor, enabledToolCallbacks, maxIterations,
                runId, taskId, subTaskId, turn, agentId, eventRecorder, loopCheckpointListener,
                null, LoopCancellation.none());
    }

    /**
     * 兼容构造器（旧 12 参签名，既无循环进度回调也无接入类型）：现有调用点零改动。
     */
    public AgentLoopInput(ChatModel chatModel, String systemPrompt, String userPrompt,
                          ToolExecutor toolExecutor, List<ToolCallback> enabledToolCallbacks,
                          Integer maxIterations, String runId, Long taskId, Long subTaskId,
                          int turn, Long agentId, AgentEventRecorder eventRecorder) {
        this(chatModel, systemPrompt, userPrompt, toolExecutor, enabledToolCallbacks, maxIterations,
                runId, taskId, subTaskId, turn, agentId, eventRecorder, null, null,
                LoopCancellation.none());
    }
}
