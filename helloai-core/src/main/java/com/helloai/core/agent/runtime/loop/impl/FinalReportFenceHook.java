package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.chat.ChatResponseContentExtractor;
import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import com.helloai.core.shared.util.VerificationFence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内部 LLM 提交前自我审计的收尾轮（REF-8.4 ②，{@link LoopHook} 的 {@code beforeTerminate} 首条实现）。
 *
 * <p><b>语义（用户口径）</b>：内部 LLM 在提交产物 / 标记任务完成之前，必须先对自身产出做一次
 * 自审——这条 {@code VERIFICATION/STATUS/NEXT_STEPS} 围栏就是它自证「已核对过自己交付物」的声明。
 * 缺/不合格围栏 ⇒ 有界一次无工具重跑（追加收尾指令）让模型自纠；只对内部 LLM 生效，外部 Agent
 * （有自己工作空间/沙箱，走平台 reviewer 审核）不参与。产出物<b>是否真符合</b>不由此判定——
 * 统一仍归平台 reviewer 角色审核（标准一致）。</p>
 *
 * <p><b>有界保证</b>：恒在「自然 STOP」且「围栏不合格」时恰好追加一次收尾轮，收尾轮本身不再重入
 * （二次仍不合格不循环，交由 {@code RuntimeTurnExecutor} 出口分类 fail-close → FAILED）。</p>
 *
 * <p><b>红线合规</b>：只改 {@link AgentLoopResult}（循环出口），不碰 {@code SubTaskStatus} / 平台
 * 状态机；{@link AgentLoopTermination#messages()} 为主调用方持有的可变列表，收尾指令即加其上。</p>
 */
@Slf4j
@Component
public class FinalReportFenceHook implements LoopHook {

    /** 收尾指令：让模型把既有最终结果整理为带固定围栏的《最终交付报告》。 */
    private static final String FINALIZE_INSTRUCTION =
            "请对上面的最终结果做一次自我审计，并整理为完整的《最终交付报告》，"
                    + "必须依次显式包含如下围栏三行（不要省略、不要调用任何工具）：\n"
                    + "VERIFICATION:\n- <针对交付物的核实说明，确认它满足本轮需求要点>\n"
                    + "STATUS: <DONE 或 BLOCKED>\n"
                    + "NEXT_STEPS: <如已全部完成则填 无，否则列出下一步>";

    @Override
    public AgentLoopResult beforeTerminate(AgentLoopTermination termination) {
        AgentLoopResult draft = termination.draft();
        // 只对内部 LLM 生效（外部 Agent 自审走自身工作空间/沙箱 + 平台 reviewer）
        if (!termination.internalLlm()) {
            return null;
        }
        // 只在「自然 STOP」的草稿上触发收尾轮（MAX_ITERATIONS / ERROR 属硬终态，不给自纠机会）
        if (draft == null || !draft.success() || !"STOP".equals(draft.finishReason())) {
            return null;
        }
        // 围栏已合格 ⇒ 零额外 LLM 调用（自审已完成）
        if (VerificationFence.parse(draft.text()).adequate()) {
            return null;
        }
        return tryFinalizeRound(termination, draft);
    }

    /** 有界一次无工具重跑：追加收尾指令 → 重跑一次模型 → 返回修订结果；失败沿用草稿。 */
    private AgentLoopResult tryFinalizeRound(AgentLoopTermination termination, AgentLoopResult draft) {
        List<Message> messages = termination.messages();
        UserMessage finalize = new UserMessage(FINALIZE_INSTRUCTION);
        messages.add(finalize);
        try {
            ChatModel chatModel = termination.chatModel();
            // 不设 toolCallbacks ⇒ 收尾轮只产文本，不重入任何工具（有界、无副作用）
            DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();
            options.setInternalToolExecutionEnabled(false);
            ChatResponse response = chatModel.call(new Prompt(messages, options));
            ChatResponseContentExtractor.ExtractedContent extracted =
                    ChatResponseContentExtractor.extract(response);
            String text = extracted.text();
            String thinking = extracted.thinking() != null && !extracted.thinking().isBlank()
                    ? extracted.thinking()
                    : draft.thinking();
            // 收尾轮视为额外一次模型轮：iterations+1，工具数不变；token 沿用 draft（best-effort）
            return AgentLoopResult.stop(text, thinking,
                    draft.iterations() + 1, draft.toolCallCount(), draft.tokenUsage());
        } catch (Exception e) {
            log.warn("FinalReportFenceHook: 收尾轮失败，沿用原草稿（交出口分类 fail-close）: err={}",
                    e.getMessage());
            return draft;
        }
    }
}