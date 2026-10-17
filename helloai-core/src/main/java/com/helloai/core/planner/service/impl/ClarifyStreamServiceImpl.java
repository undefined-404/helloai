package com.helloai.core.planner.service.impl;

import com.helloai.common.base.BizException;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.clarify.ChatRoundDecisionParser;
import com.helloai.core.planner.clarify.ClarifyReplyParser;
import com.helloai.core.planner.clarify.ClarifyWebSearchOrchestrator;
import com.helloai.core.planner.clarify.ConfirmCardProtocol;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.service.ClarifyCommandSearchService;
import com.helloai.core.planner.service.ClarifyPromptRenderService;
import com.helloai.core.planner.service.ClarifyRoundOrchestratorService;
import com.helloai.core.planner.service.ClarifyRoundOrchestratorService.RoundDecision;
import com.helloai.core.planner.service.ClarifyStreamService;
import com.helloai.core.planner.service.RequirementClarifyService.ChatStreamEvent;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyReply;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifySelection;
import com.helloai.core.planner.service.RequirementConversationService;
import com.helloai.core.planner.service.RequirementMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link ClarifyStreamService} 实现：澄清会话的 <b>SSE 流式面</b>
 * （RM9-c 从 {@link RequirementClarifyServiceImpl} 抽出的副作用簇，与同步链零耦合）。
 *
 * <p>流式轮实现：同步前置段（校验/落库/决策/搜索）在订阅线程执行，主回复段返回 token 流；
 * 任何前置/流式异常统一转 {@code error} 事件，Controller 层无需再做异常兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClarifyStreamServiceImpl implements ClarifyStreamService {

    /** 确认卡（intent=clarify）流式播放分片大小（字符/片）：question 按此切片发射 token 帧驱动打字机。 */
    private static final int CLARIFY_PLAY_CHUNK_SIZE = 5;

    /** 确认卡流式播放片间延时（毫秒）：模拟逐字节奏，避免瞬间整段出现。 */
    private static final long CLARIFY_PLAY_DELAY_MS = 12L;

    private final ClarifyRoundOrchestratorService orchestrator;
    private final ClarifyPromptRenderService promptRenderService;
    private final ClarifyCommandSearchService commandSearch;
    private final RequirementMessageService messageService;
    private final RequirementConversationService conversationService;
    private final PlannerAgentPicker plannerAgentPicker;
    private final PlatformAgentExecutionService platformAgentExecutionService;
    private final ClarifyReplyParser replyParser;
    private final ConfirmCardProtocol confirmCardProtocol;
    private final ClarifyWebSearchOrchestrator webSearchOrchestrator;

    @Override
    public Flux<ChatStreamEvent> streamRound(Long conversationId, String message,
                                             List<ClarifySelection> selections) {
        return Flux.defer(() -> runStreamRound(conversationId, message, selections))
                .onErrorResume(e -> {
                    log.warn("流式对话轮失败: conversationId={}, err={}", conversationId, e.getMessage());
                    return Flux.just(ChatStreamEvent.error(streamErrorMessage(e)));
                });
    }

    /** 流式轮实现：同步前置段（校验/落库/决策/搜索）在订阅线程执行，主回复段返回 token 流。 */
    private Flux<ChatStreamEvent> runStreamRound(Long conversationId, String message,
                                                 List<ClarifySelection> selections) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty()) {
            throw new BizException("消息不能为空");
        }
        RequirementConversation conversation = commandSearch.requireActive(conversationId);
        // 斜杠命令（/planner /task）暂不支持流式，保持普通发送（结构化轮次前端走同步）
        if (commandSearch.isTaskCommand(trimmed) || commandSearch.isPlannerCommand(trimmed)) {
            throw new BizException("斜杠命令暂不支持流式，请使用普通发送");
        }
        if (commandSearch.isChatMode(conversation)
                && Boolean.TRUE.equals(conversation.getPendingClarifyConfirm())) {
            throw new BizException("存在待确认卡片，请使用普通发送回答确认卡");
        }
        int rounds = conversation.getRoundCount() != null ? conversation.getRoundCount() : 0;
        if (rounds >= ClarifyRoundOrchestratorServiceImpl.MAX_CHAT_ROUNDS) {
            throw new BizException("对话轮数已达上限 " + ClarifyRoundOrchestratorServiceImpl.MAX_CHAT_ROUNDS
                    + "，可输入 /planner 转为方案模式，或新建会话");
        }
        String userPayload = replyParser.buildSelectionPayload(selections);
        messageService.addMessage(conversationId, ClarifyCommandSearchServiceImpl.ROLE_USER, trimmed, userPayload);
        conversation.setRoundCount(rounds + 1);
        conversationService.updateById(conversation);
        log.info("流式对话轮开始: conversationId={}, round={}, mode={}",
                conversationId, rounds + 1, conversation.getMode());

        // CLARIFY 模式：绕过意图决策，规则搜索后真流式主回复（结构化 JSON 正文流式展示）
        if (commandSearch.isClarifyMode(conversation)) {
            return runClarifyStream(conversation, trimmed, userPayload);
        }

        // CHAT 模式：联合决策（intent + 澄清问题 + 搜索决策）
        RoundDecision roundDecision = orchestrator.makeRoundDecision(conversation, trimmed);
        if (roundDecision.decision().isClarify()) {
            // 确认卡流式播放：question 分片打字机 → 落库确认卡 → done（前端 done 后收敛为卡片）
            return playClarifyQuestion(conversation, roundDecision.decision());
        }
        WebSearchOutcome webSearchOutcome = commandSearch.resolveChatSearchOutcome(conversation, trimmed, userPayload,
                roundDecision.decision(), roundDecision.degraded());

        // 主回复 prompt 构造（与 runLlmRound CHAT 分支同构：固定 CHAT 模板）
        String webSearchContext = webSearchOutcome != null ? webSearchOutcome.toContextText() : "";
        AgentProfileSnapshot planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(promptRenderService.renderChatPrompt(conversationId, webSearchContext))
                .context(Map.of("conversationId", conversationId, "scene", "requirement_chat"))
                .requiredCapabilities(Map.of())
                .build();
        // 流式主回复：增量转发 → 完成后与 runLlmRound 同语义落库 → done
        StringBuilder buffer = new StringBuilder();
        return platformAgentExecutionService.executeStream(planner.id(), agentTask)
                .map(token -> {
                    buffer.append(token);
                    return token;
                })
                .map(ChatStreamEvent::token)
                .concatWith(Flux.defer(() -> {
                    orchestrator.persistChatOutcome(conversation, buffer.toString(), webSearchOutcome);
                    log.info("流式对话轮完成并落库: conversationId={}, round={}", conversationId, rounds + 1);
                    return Flux.just(ChatStreamEvent.done());
                }));
    }

    /**
     * CLARIFY 模式流式主回复：与同步澄清轮同语义（绕过决策、开关开启则规则搜索），仅 LLM 调用走
     * {@code executeStream} 增量流——token 转发给前端打字机展示，完整 output 收集后落库（追问卡/终稿）→ done。
     */
    private Flux<ChatStreamEvent> runClarifyStream(RequirementConversation conversation,
                                                   String userMessage, String userPayload) {
        Long conversationId = conversation.getId();
        WebSearchOutcome webSearchOutcome;
        if (commandSearch.isWebSearchEnabled(conversation)) {
            webSearchOutcome = webSearchOrchestrator.doWebSearch(
                    commandSearch.resolveSearchSource(conversationId, userMessage, userPayload));
        } else {
            webSearchOutcome = null;
        }
        String webSearchContext = webSearchOutcome != null ? webSearchOutcome.toContextText() : "";
        AgentProfileSnapshot planner = plannerAgentPicker.pick(conversation.getPlannerAgentId());
        String prompt = promptRenderService.renderClarifyPrompt(conversationId, webSearchContext);
        AgentTask agentTask = AgentTask.builder()
                .systemPrompt("")
                .userPrompt(prompt)
                .context(Map.of("conversationId", conversationId, "scene", "requirement_clarify"))
                .requiredCapabilities(Map.of())
                .build();
        StringBuilder buffer = new StringBuilder();
        return platformAgentExecutionService.executeStream(planner.id(), agentTask)
                .map(token -> {
                    buffer.append(token);
                    return token;
                })
                .map(ChatStreamEvent::token)
                .concatWith(Flux.defer(() -> {
                    // 与 runLlmRound 同语义解析落库（描述守卫纠偏走同步 LLM，罕见路径可接受）
                    ClarifyReply reply = replyParser.parseReply(buffer.toString());
                    reply = promptRenderService.applyDescriptionSectionGuard(conversation, planner, prompt,
                            "requirement_clarify", reply);
                    orchestrator.persistClarifyOutcome(conversation, reply, webSearchOutcome);
                    log.info("流式澄清轮完成并落库: conversationId={}", conversationId);
                    return Flux.just(ChatStreamEvent.done());
                }));
    }

    /**
     * 确认卡流式播放（intent=clarify）：把<b>确认意图正文</b>按小块分片、小延时发射 token 帧，
     * 打字机效果；播放完成后落库确认卡并置待确认标记 → done。
     */
    private Flux<ChatStreamEvent> playClarifyQuestion(RequirementConversation conversation,
                                                      ChatRoundDecisionParser.ChatRoundDecision decision) {
        String text = confirmCardProtocol.buildAskText();
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += CLARIFY_PLAY_CHUNK_SIZE) {
            chunks.add(text.substring(i, Math.min(text.length(), i + CLARIFY_PLAY_CHUNK_SIZE)));
        }
        return Flux.fromIterable(chunks)
                .delayElements(Duration.ofMillis(CLARIFY_PLAY_DELAY_MS))
                .map(ChatStreamEvent::token)
                .concatWith(Flux.defer(() -> {
                    orchestrator.applyClarifyDecision(conversation, decision);
                    log.info("确认卡播放完成并落库: conversationId={}", conversation.getId());
                    return Flux.just(ChatStreamEvent.done());
                }));
    }

    /** 流式错误事件文本：取根因 message（BizException 友好信息），缺失时回落类名。 */
    private String streamErrorMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getMessage();
    }
}
