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
 * <p><b>有界保证</b>：恒在「自然 STOP」且围栏不合格时<b>恰好一次</b>收尾轮，收尾轮本身不再重入。
 * 收尾后仍未合格<b>不循环</b>——由 {@code RuntimeTurnExecutor} 出口分类 ④ 收口。口径 A
 * （`D-2026-10-13-6`）下 ④ 用宽松 {@code containsMarker}（有 {@code VERIFICATION:} 痕迹即放行），
 * 故「二次仍缺 STATUS」这类会被放行交 reviewer 大环兜底；只有非空且<b>完全无</b> {@code VERIFICATION:}
 * 痕迹才 fail-close → FAILED。</p>
 *
 * <p><b>记录/健壮性</b>：收尾轮采用精简 prompt（只喂「草稿 + 收尾指令」、不喂整段工具历史）从根上规避
 * 长上下文溢出；重跑有界重试一次（覆盖瞬时故障）；收尾那一轮 token 计入累计；iterations 与
 * maxIterations 取 min，不超配置上限。</p>
 *
 * <p><b>红线合规</b>：只改 {@link AgentLoopResult}（循环出口），不碰 {@code SubTaskStatus} / 平台
 * 状态机；不动 {@link AgentLoopTermination#messages()}（精简 prompt，避免对循环消息列表产生副作用）。</p>
 */
@Slf4j
@Component
public class FinalReportFenceHook implements LoopHook {

    /** 收尾指令：让模型对草稿做自我审计并整理为带固定围栏的《最终交付报告》。 */
    private static final String FINALIZE_HEADER_INSTRUCTION =
            "请对下方《报告草稿》做一次自我审计，并整理为完整的《最终交付报告》，"
                    + "必须依次显式包含如下围栏三行（不要省略、不要调用任何工具）：\n"
                    + "VERIFICATION:\n- <针对交付物的核实说明，确认它满足本轮需求要点>\n"
                    + "STATUS: <DONE 或 BLOCKED>\n"
                    + "NEXT_STEPS: <如已全部完成则填 无，否则列出下一步>";

    /** 收尾轮模型调用有界重试次数（覆盖瞬时故障，如网络 / 429 抖动）。 */
    private static final int FINALIZE_MAX_ATTEMPTS = 2;

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

    /** 有界一次无工具重跑：只喂「草稿 + 收尾指令」，返回修订结果；重跑失败沿用草稿。 */
    private AgentLoopResult tryFinalizeRound(AgentLoopTermination termination, AgentLoopResult draft) {
        String draftText = draft.text() != null ? draft.text() : "";
        UserMessage finalize = new UserMessage(
                FINALIZE_HEADER_INSTRUCTION + "\n\n《报告草稿》\n" + draftText);
        // 精简 prompt（不喂整段历史）：自审视交付物文本本身，规避长上下文在收尾时溢出（③b 治本）
        List<Message> lean = List.of(finalize);
        ChatModel chatModel = termination.chatModel();
        ChatResponse response = null;
        RuntimeException lastEx = null;
        for (int attempt = 0; attempt < FINALIZE_MAX_ATTEMPTS && response == null; attempt++) {
            try {
                // 不设 toolCallbacks ⇒ 收尾轮只产文本，不重入任何工具（有界、无副作用）
                DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();
                options.setInternalToolExecutionEnabled(false);
                response = chatModel.call(new Prompt(lean, options));
            } catch (RuntimeException e) {
                lastEx = e;
            }
        }
        if (response == null) {
            log.warn("FinalReportFenceHook: 收尾轮失败，沿用原草稿（交出口分类按口径 A 宽判 / reviewer 大环兜底）: err={}",
                    lastEx == null ? "unknown" : lastEx.getMessage());
            return draft;
        }
        ChatResponseContentExtractor.ExtractedContent extracted =
                ChatResponseContentExtractor.extract(response);
        String text = extracted.text();
        String thinking = extracted.thinking() != null && !extracted.thinking().isBlank()
                ? extracted.thinking()
                : draft.thinking();
        // ③a：收尾那一轮的 token 计入累计（复用 delta 累加逻辑）
        Integer tokens = ChatModelToolLoop.accumulateUsage(response, draft.tokenUsage());
        // ③c：收尾轮计为额外一次模型轮，与 maxIterations 取 min，不超配置上限
        int iterations = Math.min(draft.iterations() + 1, termination.maxIterations());
        return AgentLoopResult.stop(text, thinking, iterations, draft.toolCallCount(), tokens);
    }
}