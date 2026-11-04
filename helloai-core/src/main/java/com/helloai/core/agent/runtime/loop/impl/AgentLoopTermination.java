package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 循环终止前上下文（REF-8.4 ② 收尾轮）。
 *
 * <p>{@link LoopHook#beforeTerminate} 的可写观察面：携带当前消息历史与草稿结果，使挂点在
 * 需要时能<b>有界重入一次模型</b>（追加收尾指令 → 重跑 → 返回修订结果）。默认 no-op（返回
 * null）⇒ 行为与改造前逐字一致。</p>
 *
 * @param chatModel   底层模型（收尾轮重跑用；不可空）
 * @param messages    当前会话消息历史（调用方持有的<b>可变</b>列表，挂点可追加收尾指令）
 * @param internalLlm 是否内部 LLM 执行链（{@code accessType == API_KEY_LLM}；收尾轮 internal-only 门控）
 * @param draft       自然 STOP 的草稿结果（挂点据此判定是否触发收尾轮）
 * @param maxIterations 本轮最大 LLM 调用轮数（收尾轮计 iterations 时与之取 min，防超配置上限）
 * @param scene       业务面场景标识（{@code AgentLoopInput.scene} 透传；null = 子任务执行链）。
 *                    `TD-9` 事故修复：业务面循环（planner_decompose 等）不得触发 VERIFICATION 收尾轮，
 *                    由 {@code FinalReportFenceHook} 据此门控。
 * @param availableToolCallbacks 本轮模型可见的工具回调（`AgentLoopInput.enabledToolCallbacks` 透传）。
 *                    `SR-1`：收尾轮绕开主循环且默认无工具；挂点只能从本清单白名单过滤出
 *                    {@code internal_run_in_sandbox} 注入收尾轮（沙箱未启用时清单本就不含该工具）。
 * @param taskId      主任务 ID（`SR-2` 自审证据事件定位；可空）
 * @param subTaskId   子任务 ID（`SR-2` 按子任务读沙箱台账；可空）
 */
public record AgentLoopTermination(ChatModel chatModel, List<Message> messages,
                                   boolean internalLlm, AgentLoopResult draft, int maxIterations,
                                   String scene, List<ToolCallback> availableToolCallbacks,
                                   Long taskId, Long subTaskId) {

    /** 兼容构造器：`SR-1` / `SR-2` 新增上下文缺省（无可用工具、无任务定位）。 */
    public AgentLoopTermination(ChatModel chatModel, List<Message> messages, boolean internalLlm,
                                AgentLoopResult draft, int maxIterations, String scene) {
        this(chatModel, messages, internalLlm, draft, maxIterations, scene, List.of(), null, null);
    }

    public AgentLoopTermination {
        availableToolCallbacks = availableToolCallbacks == null ? List.of() : List.copyOf(availableToolCallbacks);
    }
}
